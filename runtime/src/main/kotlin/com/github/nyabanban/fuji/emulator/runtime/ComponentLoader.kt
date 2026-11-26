package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.componentLoadOrder
import com.google.inject.Guice
import com.google.inject.Module
import com.google.inject.name.Names
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

class RevisionConflict : IllegalArgumentException("configuration revision changed")
data class LoadedComponent(
    val descriptor: ComponentDescriptor, val component: HardwareComponent, val configFile: Path,
    val jarSha256: String, val loader: URLClassLoader,
    var config: Map<String, Any>, var revision: Long = 0,
)

class ComponentHost @JvmOverloads constructor(
    val owner: OwnerLoop,
    val root: Path,
    private val catalog: AddressCatalog? = null,
    val configRoot: Path? = null,
    val relaxedMode: Boolean = false,
    val norFlashPath: Path? = null,
    private val configOverrides: Map<String, Map<String, Any>> = emptyMap(),
) : ControlClient, AutoCloseable {
    val ports = PortRegistry(owner)
    val events = DirectEvents(owner)
    var tick: Long = 0
        private set
    @Volatile var configRevision: Long = 0L
        private set
    private val loaded = linkedMapOf<String, LoadedComponent>()

    fun advanceTick(value: Long) { owner.requireOwner(); require(value >= tick); tick = value }

    /** Resets the emulated host tick to 0 between [close] and [load] during a Sub-MCU cold reset. */
    fun resetClockForColdReset() {
        owner.requireOwner()
        check(loaded.isEmpty()) { "Cold-reset clock reset requires closed ComponentHost" }
        tick = 0L
    }

    private data class ScannedComponentJar(val jar: ComponentJar, val sha256: String)

    fun load() {
        owner.requireOwner(); check(loaded.isEmpty())
        Files.createDirectories(root)
        val jarPaths = Files.list(root).use { files ->
            files.filter { it.fileName.toString().endsWith(".jar") }.sorted().toList()
        }
        val scanned = scanComponentJars(jarPaths)
        val byId = scanned.associateBy { it.jar.id }
        require(byId.size == scanned.size) { "Duplicate component id: ${scanned.groupBy { it.jar.id }.filterValues { it.size > 1 }.keys}" }
        val order = componentLoadOrder(byId.mapValues { it.value.jar.depends })
        try {
            norFlashPath?.let { backing ->
                ports.removeOwner("host")
                ports.register(
                    "host",
                    NorFlashBackingPort.PORT_NAME,
                    NorFlashBackingPort::class.java,
                    object : NorFlashBackingPort {
                        override val imagePath: Path = backing.toAbsolutePath().normalize()
                    },
                )
            }
            order.forEach { load(byId.getValue(it)) }
            loaded.values.forEach { it.component.start() }
            configRevision++
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    private fun scanComponentJars(jarPaths: List<Path>): List<ScannedComponentJar> {
        if (jarPaths.size < 4) {
            return jarPaths.map { path -> ScannedComponentJar(ComponentJar.read(path, catalog), sha256(path)) }
        }
        val threads = minOf(jarPaths.size, Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        val pool = Executors.newFixedThreadPool(threads) { runnable ->
            Thread(runnable, "fuji-component-scan").apply { isDaemon = true }
        }
        return try {
            val futures = jarPaths.map { path ->
                pool.submit<ScannedComponentJar> {
                    ScannedComponentJar(ComponentJar.read(path, catalog), sha256(path))
                }
            }
            futures.map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun load(scanned: ScannedComponentJar) {
        val jar = scanned.jar
        val loader = URLClassLoader(arrayOf(jar.path.toUri().toURL()), componentParent)
        var component: HardwareComponent? = null
        try {
            val runtimeDir = containedPath(root, jar.id)
            val cfgDir = configRoot?.let { containedPath(it, jar.id) } ?: runtimeDir
            Files.createDirectories(runtimeDir)
            if (configRoot != null) Files.createDirectories(cfgDir)
            val context = object : ComponentContext {
                override val directory = runtimeDir
                override val ports = this@ComponentHost.ports
                override val events = this@ComponentHost.events
                override val clock = EmulationClock { tick }
                override val relaxedMode = this@ComponentHost.relaxedMode
                override fun dataPath(relative: String): Path {
                    val runtimeTarget = containedPath(runtimeDir, relative)
                    if (configRoot == null) return runtimeTarget
                    val configTarget = containedPath(cfgDir, relative)
                    return if (Files.exists(configTarget)) configTarget else runtimeTarget
                }
                override fun readData(relative: String): Map<String, Any?> {
                    owner.requireOwner()
                    return Formats.readData(dataPath(relative))
                }
            }
            val type = loader.loadClass(jar.provider).asSubclass(ComponentFactory::class.java)
            require(type.classLoader === loader) { "Component factory must be loaded from ${jar.path}" }
            val factory = instantiateFactory(type, jar, context)
            val descriptor = factory.descriptor
            require(descriptor.abi == COMPONENT_ABI && descriptor.id == jar.id) { "Component ABI or id invalid: ${jar.id}" }
            require(descriptor.settings.distinctBy { it.key }.size == descriptor.settings.size) { "Duplicate setting: ${jar.id}" }
            jar.checkProfiles(descriptor)
            val configFile = containedPath(cfgDir, "${jar.id}.yaml")
            val fallbackConfigFile = if (configRoot != null) containedPath(runtimeDir, "${jar.id}.yaml") else null
            val baseSupplied = when {
                Files.exists(configFile) -> Formats.read(configFile)
                fallbackConfigFile != null && Files.exists(fallbackConfigFile) -> Formats.read(fallbackConfigFile)
                else -> emptyMap()
            }
            val overrides = configOverrides[jar.id]
            val supplied = if (overrides.isNullOrEmpty()) baseSupplied else baseSupplied + overrides
            val config = validate(descriptor, supplied, false)
            component = factory.create(context, config)
            require(component.id == jar.id) { "Component id differs from factory: ${jar.id}" }
            if (!Files.exists(configFile)) {
                val diskConfig = if (overrides.isNullOrEmpty()) config else validate(descriptor, baseSupplied, false)
                Formats.atomicYaml(configFile, diskConfig)
            }
            loaded[jar.id] = LoadedComponent(descriptor, component, configFile, scanned.sha256, loader, config)
        } catch (error: Throwable) {
            runCatching { component?.close() }.onFailure(error::addSuppressed)
            ports.removeOwner(jar.id)
            runCatching { loader.close() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun instantiateFactory(
        type: Class<out ComponentFactory>,
        jar: ComponentJar,
        context: ComponentContext,
    ): ComponentFactory {
        if (!requiresGuiceInjection(type)) {
            val noArgCtor = try {
                type.getConstructor()
            } catch (_: NoSuchMethodException) {
                null
            }
            if (noArgCtor != null) {
                return try {
                    noArgCtor.newInstance()
                } catch (e: InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }
        }
        val injector = Guice.createInjector(Module { binder ->
            binder.bind(ComponentContext::class.java).toInstance(context)
            for (dependency in jar.depends) {
                val peer = loaded.getValue(dependency).component
                binder.bind(HardwareComponent::class.java).annotatedWith(Names.named(dependency)).toProvider { peer }
            }
        })
        return injector.getInstance(type)
    }

    private fun validate(d: ComponentDescriptor, changes: Map<String, Any>, live: Boolean): Map<String, Any> {
        require(changes.keys.all { key -> d.settings.any { it.key == key } }) { "unknown setting" }
        val output = if (live) linkedMapOf() else d.settings.associate { it.key to it.validate(it.default) }.toMutableMap()
        changes.forEach { (key, value) ->
            val setting = d.settings.single { it.key == key }
            require(!live || setting.live) { "$key requires restart" }
            output[key] = setting.validate(value)
        }
        return output.toMap()
    }

    fun logicalPortBindings(): List<com.github.nyabanban.fuji.emulator.catalog.LogicalPortBinding> {
        owner.requireOwner()
        return catalog?.logicalSpaces.orEmpty().mapNotNull { space ->
            val item=loaded[space.componentId] ?: return@mapNotNull null
            val profile=item.config["profile"] as? String ?: error("Logical endpoint profile missing: " + space.componentId)
            val port=ports.find(space.portId,PowerRegisterPort::class.java)
            if(profile=="disabled") {
                require(port==null) {"Disabled logical endpoint exported a port: " + space.portId}
                return@mapNotNull null
            }
            val described=port as? DescribedPowerRegisterPort ?: error("Logical endpoint contract missing: " + space.portId)
            val contracts=described.registerContracts().map { r ->
                com.github.nyabanban.fuji.emulator.catalog.LogicalRegisterContract(r.registerId,r.selector,r.wordBits,r.readable,r.writable)
            }
            com.github.nyabanban.fuji.emulator.catalog.LogicalPortBinding(space.componentId,space.portId,profile,contracts)
        }
    }
    fun candidateInputBindings(): List<ProtocolCandidateInputBinding> {
        owner.requireOwner()
        return loaded.values.mapNotNull { item ->
            val portId=item.descriptor.id+".candidate-inputs"
            val port=ports.find(portId,DescribedProtocolCandidateInputs::class.java)
            candidateInputBinding(item.descriptor.id,item.config["profile"] as? String,port)
        }
    }
    fun logicalComponentProfiles(): Map<String,String> {
        owner.requireOwner()
        val ids=catalog?.logicalSpaces.orEmpty().map {it.componentId}.toSet()
        return loaded.values.filter {it.descriptor.id in ids}.associate {item ->
            item.descriptor.id to (item.config["profile"] as? String ?: error("Logical component profile missing"))
        }
    }
    fun components(): List<LoadedComponent> { owner.requireOwner(); return loaded.values.toList() }
    private fun view(item: LoadedComponent) = mapOf(
        "id" to item.descriptor.id, "version" to item.descriptor.version, "abi" to item.descriptor.abi,
        "revision" to item.revision, "settings" to item.descriptor.settings, "config" to item.config,
        "state" to item.component.state(), "jarSha256" to item.jarSha256,
    )
    override fun snapshot() = owner.submit<Map<String, Any?>> { mapOf("tick" to tick, "components" to loaded.values.map(::view)) }

    /**
     * Lightweight Owner-thread snapshot for periodic UI refreshes when the full Diagnostics tab is
     * closed. Only invokes [HardwareComponent.state] on [stateComponentIds] (avoiding multi-megabyte
     * SoC history dumps such as `soc-vglib-init` / `soc-spi` / `soc-dma` on every UI tick) and only
     * attaches full config maps when [includeAllConfigs] is true.
     */
    fun uiSnapshotDirect(
        stateComponentIds: Set<String>,
        includeAllConfigs: Boolean,
    ): Map<String, Any?> {
        owner.requireOwner()
        val entries = ArrayList<Map<String, Any?>>(if (includeAllConfigs) loaded.size else stateComponentIds.size)
        for (item in loaded.values) {
            val id = item.descriptor.id
            val needState = id in stateComponentIds
            if (!needState && !includeAllConfigs) continue
            entries.add(
                if (needState) {
                    mapOf(
                        "id" to id,
                        "revision" to item.revision,
                        "config" to item.config,
                        "state" to item.component.state(),
                    )
                } else {
                    mapOf(
                        "id" to id,
                        "revision" to item.revision,
                        "config" to item.config,
                    )
                },
            )
        }
        return mapOf(
            "tick" to tick,
            "configRevision" to configRevision,
            "components" to entries,
        )
    }

    private data class PendingYamlPersist(
        val configFile: Path,
        val config: Map<String, Any>,
    )

    private val pendingYamlPersists = java.util.concurrent.ConcurrentHashMap<String, PendingYamlPersist>()
    private val persistFlushLock = Any()
    private val persistScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var persistExecutor: java.util.concurrent.ScheduledExecutorService? = null

    private fun ensurePersistExecutor(): java.util.concurrent.ScheduledExecutorService {
        persistExecutor?.let { return it }
        synchronized(persistFlushLock) {
            persistExecutor?.let { return it }
            return java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "fuji-config-persister").apply { isDaemon = true }
            }.also { persistExecutor = it }
        }
    }

    private fun scheduleAsyncPersist(id: String, configFile: Path, config: Map<String, Any>) {
        pendingYamlPersists[id] = PendingYamlPersist(configFile, LinkedHashMap(config))
        if (persistScheduled.compareAndSet(false, true)) {
            runCatching {
                ensurePersistExecutor().schedule(
                    { flushPendingPersists() },
                    150L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }.onFailure {
                persistScheduled.set(false)
            }
        }
    }

    /**
     * Synchronously flushes all coalesced background YAML config persists to disk.
     * Invoked automatically on [close] and before explicit synchronous [patch] calls.
     */
    fun flushPendingPersists() {
        persistScheduled.set(false)
        synchronized(persistFlushLock) {
            val keys = pendingYamlPersists.keys.toList()
            for (k in keys) {
                val entry = pendingYamlPersists.remove(k) ?: continue
                runCatching { Formats.atomicYaml(entry.configFile, entry.config) }
            }
        }
    }

    /**
     * Direct owner-thread patch helper avoiding nested [OwnerLoop.submit], O(N) component list
     * scans, and synchronous disk I/O stalls on the ARM emulation owner thread.
     * When [persistToDisk] is true, coalesces YAML persistence onto a dedicated background thread
     * (`fuji-config-persister`, flushed synchronously on [close] and before strict [patch]).
     */
    fun patchLatestDirect(
        id: String,
        changes: Map<String, Any>,
        persistToDisk: Boolean = true,
    ): Map<String, Any?> {
        owner.requireOwner()
        val item = loaded[id] ?: throw IllegalArgumentException("unknown component")
        val validated = validate(item.descriptor, changes, true)
        val old = item.config
        val merged = old + validated
        item.component.validateSettings(merged)
        try {
            item.component.applySettings(validated)
        } catch (error: Throwable) {
            runCatching {
                item.component.applySettings(old)
            }.onFailure { rollback -> error.addSuppressed(rollback) }
            throw error
        }
        item.config = merged
        item.revision++
        configRevision++
        if (persistToDisk) {
            scheduleAsyncPersist(id, item.configFile, merged)
        }
        events.publish(ComponentEvent(id, "configuration", tick, mapOf("revision" to item.revision, "keys" to validated.keys.toList())))
        return if (persistToDisk) {
            view(item)
        } else {
            mapOf("id" to id, "revision" to item.revision)
        }
    }

    override fun patch(id: String, revision: Long, changes: Map<String, Any>) = owner.submit<Map<String, Any?>> {
        val item = loaded[id] ?: throw IllegalArgumentException("unknown component")
        if (revision != item.revision) throw RevisionConflict()
        val validated = validate(item.descriptor, changes, true)
        val old = item.config
        item.component.validateSettings(old + validated)
        pendingYamlPersists.remove(id)
        Formats.atomicYaml(item.configFile, old + validated)
        try { item.component.applySettings(validated) }
        catch (error: Throwable) {
            runCatching { Formats.atomicYaml(item.configFile, old); item.component.applySettings(old) }
                .onFailure { rollback -> error.addSuppressed(rollback) }
            throw error
        }
        item.config = old + validated; item.revision++; configRevision++
        events.publish(ComponentEvent(id, "configuration", tick, mapOf("revision" to item.revision, "keys" to validated.keys.toList())))
        view(item)
    }
    override fun close() {
        owner.requireOwner()
        flushPendingPersists()
        persistExecutor?.let { exec ->
            persistExecutor = null
            runCatching { exec.shutdownNow() }
        }
        loaded.values.toList().asReversed().forEach {
            runCatching { it.component.close() }; ports.removeOwner(it.descriptor.id); runCatching { it.loader.close() }
        }
        loaded.clear()
    }
}

private val componentParent = object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (name.startsWith("com.github.nyabanban.fuji.emulator.api.") || name.startsWith("kotlin.") ||
            name.startsWith("jakarta.inject.") || name.startsWith("com.google.inject.")) {
            ComponentFactory::class.java.classLoader.loadClass(name)
        } else super.loadClass(name, resolve)
}

private val INJECT_ANNOTATION_NAMES = setOf(
    "com.google.inject.Inject",
    "jakarta.inject.Inject",
    "javax.inject.Inject",
)

internal fun requiresGuiceInjection(type: Class<*>): Boolean {
    var current: Class<*>? = type
    while (current != null && current != Any::class.java) {
        if (current.declaredConstructors.any { ctor -> ctor.annotations.any { it.annotationClass.java.name in INJECT_ANNOTATION_NAMES } }) {
            return true
        }
        if (current.declaredFields.any { field -> field.annotations.any { it.annotationClass.java.name in INJECT_ANNOTATION_NAMES } }) {
            return true
        }
        if (current.declaredMethods.any { method -> method.annotations.any { it.annotationClass.java.name in INJECT_ANNOTATION_NAMES } }) {
            return true
        }
        current = current.superclass
    }
    return false
}

fun sha256(path: Path): String = Files.newInputStream(path).use { stream ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
