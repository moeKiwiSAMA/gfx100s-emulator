package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.LensCommunicationMode
import com.github.nyabanban.fuji.emulator.api.LensControlContract
import com.github.nyabanban.fuji.emulator.api.LensModule
import com.github.nyabanban.fuji.emulator.api.LensOpticalHook
import com.github.nyabanban.fuji.emulator.api.LensOpticalSpec
import com.github.nyabanban.fuji.emulator.api.LenscomIdentitySpec
import com.github.nyabanban.fuji.emulator.api.MountAdapterModule
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.logging.Logger
import java.util.zip.ZipFile

/**
 * Resolved modular lens (+ optional mount adapter) combination with a strictly mutually exclusive
 * [LensControlContract] (`MANUAL` | `REAL_FIRMWARE` | `GENERIC_SIMULATED`).
 */
data class ResolvedLensAssembly(
    val templateId: String,
    val lensId: String,
    val adapterId: String?,
    val opticalSpec: LensOpticalSpec,
    val controlContract: LensControlContract,
    val loadedFromJar: Boolean,
    val lensModule: LensModule? = null,
    val adapterModule: MountAdapterModule? = null,
) {
    val communicationMode: LensCommunicationMode get() = controlContract.mode

    /** Evaluates dynamic SPI v2 [LensOpticalSpec] at current focal length, focus distance, and aperture. */
    fun opticalSpecAt(focalLengthMm: Double, focusDistanceM: Double, aperture: Double): LensOpticalSpec {
        val lens = lensModule ?: return opticalSpec
        val adapter = adapterModule
        return if (adapter != null) {
            adapter.bridgeOpticalSpecAt(lens, focalLengthMm, focusDistanceM, aperture)
        } else {
            lens.opticalSpecAt(focalLengthMm, focusDistanceM, aperture)
        }
    }

    /** Returns optional specialty-optics hook (SPI v2), or `null` for standard lenses. */
    fun opticalHook(): LensOpticalHook? {
        val lens = lensModule ?: return null
        val adapter = adapterModule
        return if (adapter != null) adapter.bridgeOpticalHook(lens) else lens.opticalHook()
    }

    /** Returns validated custom 128 KiB Optical EEPROM bytes (SPI v2), or `null`. */
    fun eepromBytes(): ByteArray? {
        val lens = lensModule ?: return null
        val adapter = adapterModule
        val bytes = if (adapter != null) adapter.bridgeEepromBytes(lens) else lens.eepromBytes()
        if (bytes != null) {
            require(bytes.size == LensModule.LENS_EEPROM_BYTES) {
                "Custom lens/adapter eepromBytes() for '$lensId' must be exactly ${LensModule.LENS_EEPROM_BYTES} bytes, got ${bytes.size}"
            }
        }
        return bytes
    }
}

/**
 * Discovers modular [LensModule] and [MountAdapterModule] JARs from `<home>/lenses/`
 * (via [URLClassLoader] + [ServiceLoader]) with classpath [ServiceLoader] fallback and built-in
 * [LensTemplateCatalog] synthesis for isolated unit tests.
 */
class LensModuleRegistry internal constructor(
    val lenses: List<LensModule>,
    val adapters: List<MountAdapterModule>,
    val scannedDirectory: Path?,
    val jarCount: Int,
    private val jarModuleIds: Set<String>,
    private val classLoaders: List<URLClassLoader> = emptyList(),
) : AutoCloseable {
    private val lensesById: Map<String, LensModule> = lenses.associateBy { it.id }
    private val adaptersById: Map<String, MountAdapterModule> = adapters.associateBy { it.id }
    private val adaptersByLegacyTemplate: Map<String, MountAdapterModule> = buildMap {
        for (a in adapters) {
            put(a.id, a)
            for (legacy in a.legacyTemplateIds) put(legacy, a)
        }
    }
    private val lensesByLegacyTemplate: Map<String, LensModule> = buildMap {
        for (l in lenses) {
            put(l.id, l)
            for (legacy in l.legacyTemplateIds) putIfAbsent(legacy, l)
        }
    }

    fun findLens(id: String): LensModule? = lensesById[id] ?: lensesByLegacyTemplate[id]

    fun findAdapter(id: String): MountAdapterModule? = adaptersById[id] ?: adaptersByLegacyTemplate[id]

    /**
     * Resolves a template ID and optional explicit `adapterId` into a [ResolvedLensAssembly].
     *
     * Supports:
     * - Native GF modular lenses (`gf45-100`, `gf110`, etc.) with no adapter.
     * - Explicit adapter + lens pairing (`adapterId = "adapter-fringer-ef-gfx"`, `template = "canon-ef85-f14l"`).
     * - Backwards-compatible legacy adapter template IDs (`adapter-ef-gfx`, `adapter-m42-dumb`,
     *   `adapter-hasselblad-v`), automatically pairing the adapter with its default mounted lens.
     * - `custom` profile (generic simulated by default unless `communicationMode` is overridden).
     */
    fun resolve(templateOrLensId: String, explicitAdapterId: String? = null): ResolvedLensAssembly {
        val cleanAdapterId = explicitAdapterId?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }

        if (cleanAdapterId != null) {
            val adapter = findAdapter(cleanAdapterId)
                ?: throw IllegalArgumentException("Unknown mount adapter module: '$cleanAdapterId'")
            adapter.validateContract()
            val lensIdToUse = if (templateOrLensId == LensTemplateCatalog.CUSTOM_ID || templateOrLensId == adapter.id || templateOrLensId in adapter.legacyTemplateIds) {
                adapter.defaultLensId
            } else {
                templateOrLensId
            }
            val lens = findLens(lensIdToUse)
                ?: throw IllegalArgumentException("Unknown lens module '$lensIdToUse' for adapter '${adapter.id}'")
            lens.validateContract()
            val bridgedSpec = adapter.bridgeOpticalSpec(lens)
            val bridgedContract = adapter.bridgeControlContract(lens)
            return ResolvedLensAssembly(
                templateId = bridgedSpec.id,
                lensId = lens.id,
                adapterId = adapter.id,
                opticalSpec = bridgedSpec,
                controlContract = bridgedContract,
                loadedFromJar = adapter.id in jarModuleIds || lens.id in jarModuleIds,
                lensModule = lens,
                adapterModule = adapter,
            )
        }

        // Legacy adapter template ID (e.g. "adapter-ef-gfx", "adapter-m42-dumb", "adapter-hasselblad-v")
        val legacyAdapter = adaptersByLegacyTemplate[templateOrLensId]
        if (legacyAdapter != null) {
            legacyAdapter.validateContract()
            val defaultLens = findLens(legacyAdapter.defaultLensId)
            if (defaultLens != null) {
                defaultLens.validateContract()
                return ResolvedLensAssembly(
                    templateId = templateOrLensId,
                    lensId = defaultLens.id,
                    adapterId = legacyAdapter.id,
                    opticalSpec = legacyAdapter.bridgeOpticalSpec(defaultLens),
                    controlContract = legacyAdapter.bridgeControlContract(defaultLens),
                    loadedFromJar = legacyAdapter.id in jarModuleIds || defaultLens.id in jarModuleIds,
                    lensModule = defaultLens,
                    adapterModule = legacyAdapter,
                )
            }
        }

        // Direct lens module (native GF or adapted lens whose mount requires matching adapter)
        val directLens = lensesById[templateOrLensId]
        if (directLens != null) {
            directLens.validateContract()
            if (directLens.mountType != "GF") {
                val matchingAdapter = adapters.firstOrNull { it.lensMount == directLens.mountType }
                if (matchingAdapter != null) {
                    matchingAdapter.validateContract()
                    return ResolvedLensAssembly(
                        templateId = directLens.id,
                        lensId = directLens.id,
                        adapterId = matchingAdapter.id,
                        opticalSpec = matchingAdapter.bridgeOpticalSpec(directLens),
                        controlContract = matchingAdapter.bridgeControlContract(directLens),
                        loadedFromJar = matchingAdapter.id in jarModuleIds || directLens.id in jarModuleIds,
                        lensModule = directLens,
                        adapterModule = matchingAdapter,
                    )
                }
            }
            return ResolvedLensAssembly(
                templateId = directLens.id,
                lensId = directLens.id,
                adapterId = null,
                opticalSpec = directLens.opticalSpec,
                controlContract = directLens.controlContract,
                loadedFromJar = directLens.id in jarModuleIds,
                lensModule = directLens,
                adapterModule = null,
            )
        }

        // Fallback to built-in LensTemplateCatalog (including "custom" and isolated unit tests without lenses/*.jar)
        val tpl = LensTemplateCatalog.find(templateOrLensId)
            ?: throw IllegalArgumentException("Unknown lens template or module: '$templateOrLensId'")
        val spec = tpl.toOpticalSpec()
        val contract: LensControlContract = if (!tpl.electronicContacts) {
            LensControlContract.Manual
        } else {
            LensControlContract.GenericSimulated(tpl.toDefaultIdentitySpec())
        }
        return ResolvedLensAssembly(
            templateId = tpl.id,
            lensId = tpl.id,
            adapterId = null,
            opticalSpec = spec,
            controlContract = contract,
            loadedFromJar = false,
            lensModule = null,
            adapterModule = null,
        )
    }

    fun lensSummaries(): List<Map<String, Any>> = lenses.map { m ->
        linkedMapOf(
            "id" to m.id,
            "displayName" to m.displayName,
            "mountType" to m.mountType,
            "communicationMode" to m.controlContract.mode.name,
            "electronicContacts" to m.opticalSpec.electronicContacts,
            "minFocalLengthMm" to m.opticalSpec.minFocalLengthMm,
            "maxFocalLengthMm" to m.opticalSpec.maxFocalLengthMm,
            "minAperture" to m.opticalSpec.minAperture,
            "maxAperture" to m.opticalSpec.maxAperture,
            "minFocusDistanceM" to m.opticalSpec.minFocusDistanceM,
            "focusMotorType" to m.opticalSpec.focusMotorType,
            "focusSweepSeconds" to m.opticalSpec.focusSweepSeconds,
            "loadedFromJar" to (m.id in jarModuleIds),
            "metadata" to m.metadata.toStateMap(),
            "hasOpticalHook" to (m.opticalHook() != null),
            "hasCustomEeprom" to (m.eepromBytes() != null),
        )
    }

    fun adapterSummaries(): List<Map<String, Any>> = adapters.map { a ->
        linkedMapOf(
            "id" to a.id,
            "displayName" to a.displayName,
            "bodyMount" to a.bodyMount,
            "lensMount" to a.lensMount,
            "communicationMode" to a.communicationMode.name,
            "electronicContacts" to a.electronicContacts,
            "defaultLensId" to a.defaultLensId,
            "loadedFromJar" to (a.id in jarModuleIds),
            "metadata" to a.metadata.toStateMap(),
        )
    }

    override fun close() {
        for (loader in classLoaders) {
            runCatching { loader.close() }
        }
    }
}

object LensModuleLoader {
    private val LOGGER = Logger.getLogger(LensModuleLoader::class.java.name)

    fun discover(componentDir: Path, explicitLensesDir: String = ""): LensModuleRegistry {
        val candidateDir = resolveLensesDir(componentDir, explicitLensesDir)
        val jarPaths = if (candidateDir != null && Files.isDirectory(candidateDir, NOFOLLOW_LINKS)) {
            Files.list(candidateDir).use { stream ->
                stream
                    .filter {
                        !Files.isSymbolicLink(it) &&
                            Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                            it.fileName.toString().endsWith(".jar", ignoreCase = true)
                    }
                    .sorted()
                    .map { it.toAbsolutePath().normalize() }
                    .toList()
            }
        } else {
            emptyList()
        }

        val discoveredLenses = LinkedHashMap<String, LensModule>()
        val discoveredAdapters = LinkedHashMap<String, MountAdapterModule>()
        val jarModuleIds = LinkedHashSet<String>()
        val activeLoaders = ArrayList<URLClassLoader>()
        var loadedJarCount = 0

        for (jarPath in jarPaths) {
            val zipOk = try {
                ZipFile(jarPath.toFile()).use { zf -> zf.size() >= 0 }
            } catch (err: Throwable) {
                LOGGER.warning(
                    "[FALLBACK][lens-plugin] Skipping broken/unreadable lens JAR '$jarPath': " +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
                false
            }
            if (!zipOk) continue

            var loader: URLClassLoader? = null
            var loadedAnyFromJar = false
            try {
                loader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), LensModule::class.java.classLoader)
                val lensIt = ServiceLoader.load(LensModule::class.java, loader).iterator()
                while (lensIt.hasNext()) {
                    try {
                        val module = lensIt.next()
                        if (module.javaClass.classLoader !== loader) continue
                        module.validateContract()
                        discoveredLenses[module.id] = module
                        jarModuleIds.add(module.id)
                        loadedAnyFromJar = true
                    } catch (err: Throwable) {
                        LOGGER.warning(
                            "[FALLBACK][lens-plugin] Skipping invalid LensModule in '$jarPath': " +
                                "${err.javaClass.simpleName}: ${err.message}",
                        )
                    }
                }
                val adapterIt = ServiceLoader.load(MountAdapterModule::class.java, loader).iterator()
                while (adapterIt.hasNext()) {
                    try {
                        val adapter = adapterIt.next()
                        if (adapter.javaClass.classLoader !== loader) continue
                        adapter.validateContract()
                        discoveredAdapters[adapter.id] = adapter
                        jarModuleIds.add(adapter.id)
                        loadedAnyFromJar = true
                    } catch (err: Throwable) {
                        LOGGER.warning(
                            "[FALLBACK][lens-plugin] Skipping invalid MountAdapterModule in '$jarPath': " +
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
                    "[FALLBACK][lens-plugin] Skipping failed lens plugin JAR '$jarPath': " +
                        "${err.javaClass.simpleName}: ${err.message}",
                )
                runCatching { loader?.close() }
            }
        }

        // Classpath ServiceLoader fallback (e.g., unit tests placing lens JARs on testRuntimeClasspath)
        for (module in ServiceLoader.load(LensModule::class.java, LensModule::class.java.classLoader)) {
            module.validateContract()
            discoveredLenses.putIfAbsent(module.id, module)
        }
        for (adapter in ServiceLoader.load(MountAdapterModule::class.java, MountAdapterModule::class.java.classLoader)) {
            adapter.validateContract()
            discoveredAdapters.putIfAbsent(adapter.id, adapter)
        }

        return LensModuleRegistry(
            lenses = discoveredLenses.values.toList(),
            adapters = discoveredAdapters.values.toList(),
            scannedDirectory = candidateDir,
            jarCount = loadedJarCount,
            jarModuleIds = jarModuleIds,
            classLoaders = activeLoaders,
        )
    }

    private fun resolveLensesDir(componentDir: Path, explicitLensesDir: String): Path? {
        if (explicitLensesDir.isNotBlank()) {
            val p = Path.of(explicitLensesDir)
            val resolved = if (p.isAbsolute) p.normalize() else componentDir.resolve(p).toAbsolutePath().normalize()
            if (Files.isDirectory(resolved, NOFOLLOW_LINKS)) return resolved
        }
        val abs = componentDir.toAbsolutePath().normalize()
        // Walk up 0..4 parents to locate `<home>/lenses` (handles runtime/compoments/lens, configs/components/lens, build/emulator/compoments/lens)
        var cur: Path? = abs
        repeat(5) {
            if (cur != null) {
                val cand = cur.resolve("lenses")
                if (Files.isDirectory(cand, NOFOLLOW_LINKS)) return cand
                cur = cur.parent
            }
        }
        return null
    }
}

internal fun LensTemplate.toOpticalSpec(): LensOpticalSpec = LensOpticalSpec(
    id = id,
    displayName = displayName,
    mountType = mountType,
    modelName = modelName,
    defaultFocalLengthMm = defaultFocalLengthMm,
    referenceFocalLengthMm = referenceFocalLengthMm,
    minFocalLengthMm = minFocalLengthMm,
    maxFocalLengthMm = maxFocalLengthMm,
    defaultAperture = defaultAperture,
    minAperture = minAperture,
    maxAperture = maxAperture,
    minFocusDistanceM = minFocusDistanceM,
    teleMinFocusDistanceM = teleMinFocusDistanceM,
    defaultFocusDistanceM = defaultFocusDistanceM,
    transmission = transmission,
    focusBreathing = focusBreathing,
    distortionK1 = distortionK1,
    distortionK2 = distortionK2,
    caRedScale = caRedScale,
    caBlueScale = caBlueScale,
    longitudinalCaDefocusMm = longitudinalCaDefocusMm,
    wideOpenContrastDrop = wideOpenContrastDrop,
    vignettingCos4 = vignettingCos4,
    vignettingMechanical = vignettingMechanical,
    imageCircleMm = imageCircleMm,
    electronicContacts = electronicContacts,
    oisEnabled = oisEnabled,
    defaultFocusMode = defaultFocusMode,
    focusMotorType = focusMotorType,
    focusSweepSeconds = focusSweepSeconds,
    flareStrength = flareStrength,
    flareTintR = flareTintR,
    flareTintG = flareTintG,
    flareTintB = flareTintB,
    apertureBladeCount = apertureBladeCount,
    apertureBladeRoundness = apertureBladeRoundness,
    bokehCatsEye = bokehCatsEye,
    bokehOnionRing = bokehOnionRing,
    bokehRimHighlight = bokehRimHighlight,
    opticalCharacterSummary = opticalCharacterSummary,
)

internal fun LensTemplate.toDefaultIdentitySpec(): LenscomIdentitySpec {
    val cleanShort = buildString {
        for (ch in id.uppercase()) {
            if (ch in 'A'..'Z' || ch in '0'..'9') append(ch)
            if (length >= 7) break
        }
        if (isEmpty()) append("GFLENS")
    }
    val uniqueSerial = cleanShort.padEnd(8, '0') + "01"
    return LenscomIdentitySpec(
        shortId = cleanShort,
        modelAscii = modelName.ifBlank { "GF Lens" }.take(63),
        serialAscii = uniqueSerial.take(10),
        capabilityFlags1 = if (mountType == "GF") 0x01 else 0x00, // DAT 0x0122C088: hdr[101] bit 0 = LMO/LEQ support on native GF lenses
        isZoom = maxFocalLengthMm > minFocalLengthMm + 0.5,
        hasOis = oisEnabled,
        hasApertureRing = true,
        minFocalMm = minFocalLengthMm,
        maxFocalMm = maxFocalLengthMm,
        minAperture = minAperture,
        maxAperture = maxAperture,
        focusMotorType = focusMotorType,
        focusSweepSeconds = focusSweepSeconds,
    )
}
