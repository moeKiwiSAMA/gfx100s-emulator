package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * Registry of built-in, classpath, and externally discovered `.jar` [OpticalFilterElementProvider]s.
 */
class OpticalFilterModuleRegistry internal constructor(
    val providers: List<OpticalFilterElementProvider>,
    val scannedDirectories: List<Path>,
    val jarCount: Int,
    val jarFilterTypes: Set<String>,
    private val classLoaders: List<URLClassLoader> = emptyList(),
) : AutoCloseable {
    fun providerSummaries(): List<Map<String, Any>> = providers.map { p ->
        linkedMapOf(
            "type" to p.filterType,
            "displayName" to p.displayName,
            "loadedFromJar" to (p.filterType in jarFilterTypes),
            "metadata" to p.metadata.toStateMap(),
            "parameterSchema" to p.parameterSchema().map { it.toStateMap() },
        )
    }

    override fun close() {
        for (loader in classLoaders) {
            runCatching { loader.close() }
        }
    }
}

/**
 * Discovers external third-party [OpticalFilterElementProvider] `.jar` plugins from the
 * `<run-dir>/filters/` and `<component-dir>/filters/` directories via [URLClassLoader] + [ServiceLoader],
 * matching `LensModuleLoader`.
 *
 * Corrupt, unreadable, or misconfigured plugin JARs are skipped with an explicit
 * `WARN [FALLBACK][optical-filter-plugin]` diagnostic instead of crashing startup.
 */
object OpticalFilterModuleLoader {
    private val LOGGER = Logger.getLogger(OpticalFilterModuleLoader::class.java.name)

    fun discover(
        componentDir: Path,
        explicitFiltersDir: String = "",
        extraProviders: List<OpticalFilterElementProvider> = emptyList(),
    ): OpticalFilterModuleRegistry {
        val byType = LinkedHashMap<String, OpticalFilterElementProvider>()
        val builtIns = listOf(
            UvFilterProvider(),
            NdFilterProvider(),
            BokehMaskFilterProvider(),
            StarFilterProvider(),
            BlackMistFilterProvider(),
            GndFilterProvider(),
        )
        for (p in builtIns) byType[p.filterType] = p

        // Classpath ServiceLoader providers (e.g. bundled modules or test classpath extensions)
        runCatching {
            val it = ServiceLoader.load(
                OpticalFilterElementProvider::class.java,
                OpticalFilterElementProvider::class.java.classLoader,
            ).iterator()
            while (it.hasNext()) {
                try {
                    val provider = it.next()
                    if (provider.filterType.isNotBlank()) {
                        byType.putIfAbsent(provider.filterType, provider)
                    }
                } catch (err: Throwable) {
                    LOGGER.warning(
                        "[FALLBACK][optical-filter-plugin] Failed to instantiate classpath OpticalFilterElementProvider: " +
                            "${err.javaClass.simpleName}: ${err.message}",
                    )
                }
            }
        }

        val candidateDirs = resolveFilterDirs(componentDir, explicitFiltersDir)
        val jarPaths = LinkedHashSet<Path>()
        for (dir in candidateDirs) {
            try {
                Files.list(dir).use { stream ->
                    stream
                        .filter {
                            !Files.isSymbolicLink(it) &&
                                Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                                it.fileName.toString().endsWith(".jar", ignoreCase = true)
                        }
                        .sorted()
                        .forEach { jarPaths.add(it.toAbsolutePath().normalize()) }
                }
            } catch (err: Throwable) {
                LOGGER.warning(
                    "[FALLBACK][optical-filter-plugin] Could not scan filter plugin directory '$dir': " +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
            }
        }

        val jarFilterTypes = LinkedHashSet<String>()
        val activeLoaders = ArrayList<URLClassLoader>()
        var loadedJarCount = 0

        for (jarPath in jarPaths) {
            // Pre-validate ZIP/JAR readability before creating URLClassLoader
            val zipOk = try {
                ZipFile(jarPath.toFile()).use { zf ->
                    zf.size() >= 0
                }
            } catch (err: Throwable) {
                LOGGER.warning(
                    "[FALLBACK][optical-filter-plugin] Skipping broken/unreadable filter JAR '$jarPath': " +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
                false
            }
            if (!zipOk) continue

            var loader: URLClassLoader? = null
            var loadedAnyFromJar = false
            try {
                loader = URLClassLoader(
                    arrayOf(jarPath.toUri().toURL()),
                    OpticalFilterElementProvider::class.java.classLoader,
                )
                val it = ServiceLoader.load(OpticalFilterElementProvider::class.java, loader).iterator()
                while (it.hasNext()) {
                    try {
                        val provider = it.next()
                        // Only register providers actually defined inside this external JAR (skip parent-delegated built-ins).
                        if (provider.javaClass.classLoader !== loader) continue
                        val typeId = provider.filterType.trim()
                        if (typeId.isEmpty()) {
                            LOGGER.warning(
                                "[FALLBACK][optical-filter-plugin] Ignoring provider with blank filterType in '$jarPath'",
                            )
                            continue
                        }
                        byType[typeId] = provider
                        jarFilterTypes.add(typeId)
                        loadedAnyFromJar = true
                    } catch (err: Throwable) {
                        LOGGER.warning(
                            "[FALLBACK][optical-filter-plugin] Skipping broken provider entry in JAR '$jarPath': " +
                                "${err.javaClass.simpleName}: ${err.message}",
                        )
                    }
                }
                if (loadedAnyFromJar) {
                    activeLoaders.add(loader)
                    loadedJarCount++
                } else {
                    runCatching { loader.close() }
                }
            } catch (err: Throwable) {
                LOGGER.warning(
                    "[FALLBACK][optical-filter-plugin] Skipping failed filter plugin JAR '$jarPath': " +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
                runCatching { loader?.close() }
            }
        }

        for (p in extraProviders) {
            if (p.filterType.isNotBlank()) {
                byType[p.filterType] = p
            }
        }

        return OpticalFilterModuleRegistry(
            providers = byType.values.toList(),
            scannedDirectories = candidateDirs,
            jarCount = loadedJarCount,
            jarFilterTypes = jarFilterTypes,
            classLoaders = activeLoaders,
        )
    }

    internal fun resolveFilterDirs(componentDir: Path, explicitFiltersDir: String): List<Path> {
        val dirs = LinkedHashSet<Path>()
        if (explicitFiltersDir.isNotBlank()) {
            val p = Path.of(explicitFiltersDir)
            val resolved = if (p.isAbsolute) p.normalize() else componentDir.resolve(p).toAbsolutePath().normalize()
            if (Files.isDirectory(resolved, NOFOLLOW_LINKS)) {
                dirs.add(resolved)
            }
        }
        val abs = componentDir.toAbsolutePath().normalize()
        // Walk 0..4 parents to find `<component-dir>/filters` AND `<run-dir>/filters`.
        var cur: Path? = abs
        repeat(5) {
            val base = cur ?: return@repeat
            val cand = base.resolve("filters")
            if (Files.isDirectory(cand, NOFOLLOW_LINKS)) {
                dirs.add(cand.toAbsolutePath().normalize())
            }
            cur = base.parent
        }
        return dirs.toList()
    }
}
