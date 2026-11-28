package com.github.nyabanban.fuji.emulator.components.lens

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.lens.optics.ApertureScale
import com.github.nyabanban.fuji.emulator.components.lens.optics.BorderMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.CmosNoiseConfig
import com.github.nyabanban.fuji.emulator.components.lens.optics.FocusMotorTypes
import com.github.nyabanban.fuji.emulator.components.lens.optics.IbisShiftState
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensModuleLoader
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensModuleRegistry
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensOpticalSceneFilter
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensTemplate
import com.github.nyabanban.fuji.emulator.components.lens.optics.LensTemplateCatalog
import com.github.nyabanban.fuji.emulator.components.lens.optics.OpticalLensProfile
import com.github.nyabanban.fuji.emulator.components.lens.optics.ResolvedLensAssembly
import com.github.nyabanban.fuji.emulator.components.lens.optics.SamplingMode
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticsBackendPreference
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.VulkanOpticalBackend
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt

class LensFactory : ComponentFactory {
    private class DepthSnapshot(
        val width: Int,
        val height: Int,
        val depthMeters: FloatArray,
    )

    override val descriptor = ComponentDescriptor("lens", "0.0.1", settings = listOf(
        Setting("template", ValueKind.TEXT, LensTemplateCatalog.CUSTOM_ID),
        Setting("adapterId", ValueKind.TEXT, ""),
        Setting(
            "communicationMode",
            ValueKind.TEXT,
            "",
            choices = listOf("", "MANUAL", "REAL_FIRMWARE", "GENERIC_SIMULATED"),
        ),
        Setting("realFirmwareFile", ValueKind.TEXT, ""),
        Setting(
            "realFirmwareFormat",
            ValueKind.TEXT,
            LensControlContract.RealFirmware.FORMAT_FUJI_TYPE4_DAT,
            choices = LensControlContract.RealFirmware.SUPPORTED_FORMATS.toList(),
        ),
        Setting("realFirmwareSha256", ValueKind.TEXT, ""),
        Setting("lensesDir", ValueKind.TEXT, "", live = false),
        Setting("lensFirmwareDir", ValueKind.TEXT, "", live = false),
        Setting("model", ValueKind.TEXT, "synthetic-research-lens"),
        Setting("focalLengthMm", ValueKind.DECIMAL, 63.0, 1.0, 2000.0),
        Setting("aperture", ValueKind.DECIMAL, 2.8, 0.5, 64.0),
        Setting("apertureRingMode", ValueKind.TEXT, "A", choices = listOf("A", "C", "MANUAL")),
        Setting("focusDistanceM", ValueKind.DECIMAL, 10.0, 0.01, 1e9),
        Setting("focusTargetDistanceM", ValueKind.DECIMAL, 10.0, 0.01, 1e9),
        Setting("focusMode", ValueKind.TEXT, "AF-S", choices = listOf("AF-S", "AF-C", "MF")),
        Setting("subjectDistanceM", ValueKind.DECIMAL, 2.5, 0.05, 1e6),
        Setting("afTrigger", ValueKind.INTEGER, 0L, 0.0, 9.0e18),
        Setting("electronicContacts", ValueKind.BOOLEAN, true),
        Setting("oisEnabled", ValueKind.BOOLEAN, true),
        Setting("transmission", ValueKind.DECIMAL, 0.9, 0.0, 1.0),
        Setting("focusBreathing", ValueKind.DECIMAL, 0.0, -0.5, 0.5),
        Setting("opticsEnabled", ValueKind.BOOLEAN, false),
        Setting("opticsBackend", ValueKind.TEXT, OpticsBackendPreference.AUTO.configValue, choices = OpticsBackendPreference.ALL_CONFIG_VALUES),
        Setting("vulkanDevice", ValueKind.TEXT, VulkanOpticalBackend.DEFAULT_VULKAN_DEVICE),
        Setting("referenceFocalLengthMm", ValueKind.DECIMAL, 63.0, 1.0, 2000.0),
        Setting("distortionK1", ValueKind.DECIMAL, 0.0, -1.0, 1.0),
        Setting("distortionK2", ValueKind.DECIMAL, 0.0, -1.0, 1.0),
        Setting("caRedScale", ValueKind.DECIMAL, 1.0, 0.8, 1.2),
        Setting("caBlueScale", ValueKind.DECIMAL, 1.0, 0.8, 1.2),
        Setting("longitudinalCaDefocusMm", ValueKind.DECIMAL, 0.0, -25.0, 25.0),
        Setting("wideOpenContrastDrop", ValueKind.DECIMAL, 0.0, 0.0, 0.6),
        Setting("vignettingCos4", ValueKind.DECIMAL, 0.0, 0.0, 8.0),
        Setting("vignettingMechanical", ValueKind.DECIMAL, 0.0, 0.0, 1.0),
        Setting("imageCircleMm", ValueKind.DECIMAL, 55.0, 20.0, 120.0),
        Setting("focusMotorType", ValueKind.TEXT, FocusMotorTypes.LM, choices = FocusMotorTypes.ALL),
        Setting("focusSweepSeconds", ValueKind.DECIMAL, 0.35, 0.0, 5.0),
        Setting("focusMotorInstant", ValueKind.BOOLEAN, false),
        Setting("flareStrength", ValueKind.DECIMAL, 0.0, 0.0, 1.0),
        Setting("flareTintR", ValueKind.DECIMAL, 1.0, 0.0, 2.0),
        Setting("flareTintG", ValueKind.DECIMAL, 0.95, 0.0, 2.0),
        Setting("flareTintB", ValueKind.DECIMAL, 0.88, 0.0, 2.0),
        Setting("apertureBladeCount", ValueKind.INTEGER, 9L, 5.0, 11.0),
        Setting("apertureBladeRoundness", ValueKind.DECIMAL, 1.0, 0.0, 1.0),
        Setting("bokehCatsEye", ValueKind.DECIMAL, 0.0, 0.0, 1.0),
        Setting("bokehOnionRing", ValueKind.DECIMAL, 0.0, 0.0, 0.5),
        Setting("bokehRimHighlight", ValueKind.DECIMAL, 0.0, 0.0, 0.6),
        Setting("ibisShiftXPx", ValueKind.DECIMAL, 0.0, -256.0, 256.0),
        Setting("ibisShiftYPx", ValueKind.DECIMAL, 0.0, -256.0, 256.0),
        Setting("ibisRollRad", ValueKind.DECIMAL, 0.0, -3.1416, 3.1416),
        Setting("noiseEnabled", ValueKind.BOOLEAN, false),
        Setting("noiseSeed", ValueKind.INTEGER, 0L, 0.0, 9.0e18),
        Setting("blackLevel", ValueKind.INTEGER, 0L, 0.0, 16383.0),
        Setting("rescanPlugins", ValueKind.INTEGER, 0L, 0.0, 9.0e18),
    ))

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        val explicitLensesDir = (config["lensesDir"] as? String).orEmpty()
        val initialRegistry = LensModuleLoader.discover(
            componentDir = context.directory,
            explicitLensesDir = explicitLensesDir,
        )
        try {
            return createLens(context, config, initialRegistry, explicitLensesDir)
        } catch (failure: Throwable) {
            initialRegistry.close()
            throw failure
        }
    }

    private fun createLens(
        context: ComponentContext, config: Map<String, Any>, initialRegistry: LensModuleRegistry, explicitLensesDir: String,
    ): HardwareComponent {
        val baseDefaults = descriptor.settings.associate { it.key to it.default }
        val explicitConfig = filterExplicitOverrides(baseDefaults, config)
        val initialTemplateId = (config["template"] as? String) ?: LensTemplateCatalog.CUSTOM_ID
        val initialAdapterId = (config["adapterId"] as? String).orEmpty()
        val initialAssembly = if (initialTemplateId != LensTemplateCatalog.CUSTOM_ID || initialAdapterId.isNotBlank()) {
            initialRegistry.resolve(initialTemplateId, initialAdapterId)
        } else {
            initialRegistry.resolve(LensTemplateCatalog.CUSTOM_ID, null)
        }
        val templateDefaults = if (initialTemplateId != LensTemplateCatalog.CUSTOM_ID || initialAdapterId.isNotBlank()) {
            opticalSpecToDefaultSettings(initialAssembly)
        } else {
            emptyMap()
        }
        val mergedConfig = baseDefaults + templateDefaults + explicitConfig

        return object : ScalarComponent("lens", context, mergedConfig),
            LensPort,
            FacePreviewScalerPort,
            ElectronicLensPort,
            LensControlPort,
            ModularLensPort,
            OpticalSceneFilter,
            SceneDepthPort,
            StatefulComponent {

            private val ownerThread: Thread = Thread.currentThread()
            private val registryLock = Any()

            @Volatile
            private var registry: LensModuleRegistry = initialRegistry

            private var lastRescanTrigger: Long = (mergedConfig["rescanPlugins"] as? Number)?.toLong() ?: 0L

            @Volatile
            private var cachedIbisShiftPort: IbisShiftPort? = null

            private val computeBackendLock = Any()
            @Volatile
            private var desiredOpticsBackend: OpticsBackendPreference =
                OpticsBackendPreference.parse(mergedConfig["opticsBackend"] as? String)
            @Volatile
            private var desiredVulkanDevice: String =
                (mergedConfig["vulkanDevice"] as? String)?.trim()?.ifEmpty { VulkanOpticalBackend.DEFAULT_VULKAN_DEVICE }
                    ?: VulkanOpticalBackend.DEFAULT_VULKAN_DEVICE
            @Volatile
            private var computeBackend: VulkanOpticalBackend? = null
            @Volatile
            private var cachedVulkanDevices: List<Map<String, Any?>>? = null
            @Volatile
            private var cachedOpticalFilterStackPort: OpticalFilterStackPort? = null

            private fun ensureComputeBackend(): VulkanOpticalBackend = synchronized(computeBackendLock) {
                val desired = desiredOpticsBackend
                val desiredDev = desiredVulkanDevice
                val existing = computeBackend
                if (existing != null && existing.preference == desired && existing.preferredDevice == desiredDev) return existing
                existing?.close()
                VulkanOpticalBackend(
                    preference = desired,
                    allowSoftwareVulkan = java.lang.Boolean.getBoolean("fuji.vulkan.allowSoftware"),
                    preferredDevice = desiredDev,
                ).also { created ->
                    computeBackend = created
                    val devs = created.availableDevices()
                    if (devs.isNotEmpty() || cachedVulkanDevices == null) {
                        cachedVulkanDevices = devs.map { it.toStateMap() }
                    }
                }
            }

            private fun currentVulkanDevices(): List<Map<String, Any?>> {
                cachedVulkanDevices?.let { return it }
                val devs = synchronized(computeBackendLock) {
                    cachedVulkanDevices ?: (computeBackend?.availableDevices()
                        ?: VulkanOpticalBackend.enumerateDevices()).map { it.toStateMap() }.also {
                        cachedVulkanDevices = it
                    }
                }
                return devs
            }

            private val opticalFilter = LensOpticalSceneFilter(
                ibisShiftPortProvider = {
                    if (Thread.currentThread() === ownerThread) {
                        context.ports.find(IbisShiftPort.PORT_NAME, IbisShiftPort::class.java).also {
                            cachedIbisShiftPort = it
                        }
                    } else {
                        cachedIbisShiftPort
                    }
                },
                clockTickProvider = { context.clock.ticks() },
                computeBackendProvider = { ensureComputeBackend() },
                opticalFilterStackProvider = {
                    if (Thread.currentThread() === ownerThread) {
                        context.ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java).also {
                            cachedOpticalFilterStackPort = it
                        }
                    } else {
                        cachedOpticalFilterStackPort
                    }
                },
            )
            @Volatile
            private var lastDepthSnapshot: DepthSnapshot? = null
            private var lastAfTrigger: Long = (mergedConfig["afTrigger"] as? Number)?.toLong() ?: 0L
            private var currentAssembly: ResolvedLensAssembly = initialAssembly
            private var activeContract: LensControlContract = initialAssembly.controlContract
            private var decodedRealFirmware: DecodedLensFirmwareImage? = null

            // Physical focus-motor slew state (vergence / diopter space: pos in [0.0=inf .. 1.0=MFD])
            private var focusTargetDistanceM: Double = (mergedConfig["focusDistanceM"] as? Number)?.toDouble() ?: 10.0
            @Volatile
            private var focusMotorActive: Boolean = false
            private var activeFocusSpeedPulsesPerSec: Int = 0
            private var focusStartPos: Double = 0.0
            private var focusTargetPos: Double = 0.0
            private var lastMotorTick: Long = context.clock.ticks()
            private var lastAdvancedClockTick: Long = Long.MIN_VALUE
            @Volatile
            private var focusMotorVersionNonce: Long = 0L

            // Real-silicon aperture ring & live iris blade sync state (LENSCOM cmd 0x020/0x022/0x030/0x062)
            private var ringStepIdx: Int = 0
            private var liveIrisStepIdx: Int = 0
            private var bodyIrisSynced: Boolean = false
            private var bodyCommandedAperture: Double? = null

            init {
                resolveAndValidateControlMode(explicitSettings = explicitConfig)
                if (!electronicContacts || activeContract is LensControlContract.Manual) {
                    values = values + ("apertureRingMode" to "MANUAL")
                }
                val initialStep = nearestApertureStopIndex(number("aperture"))
                ringStepIdx = initialStep
                liveIrisStepIdx = initialStep
                focusTargetDistanceM = number("focusDistanceM")
                values = values + ("focusTargetDistanceM" to focusTargetDistanceM)
                syncOpticalFilter()
                context.ports.register(id, "lens", LensPort::class.java, this)
                context.ports.register(id, "lens.uniresz", FacePreviewScalerPort::class.java, this)
                context.ports.register(id, SceneDepthPort.PORT_NAME, SceneDepthPort::class.java, this)
            }

            private fun apertureStops(): List<Double> = ApertureScale.stopsBetween(minAperture, maxAperture).take(31)

            private fun nearestApertureStopIndex(fNumber: Double): Int {
                val stops = apertureStops()
                if (stops.isEmpty()) return 0
                var bestIdx = 0
                var bestDist = Double.POSITIVE_INFINITY
                for (i in stops.indices) {
                    val d = abs(stops[i] - fNumber)
                    if (d < bestDist) {
                        bestDist = d
                        bestIdx = i
                    }
                }
                return bestIdx
            }

            private fun activeTemplate(): LensTemplate =
                LensTemplateCatalog.find(currentAssembly.templateId)
                    ?: LensTemplateCatalog.find(textVal("template"))
                    ?: LensTemplateCatalog.require(LensTemplateCatalog.CUSTOM_ID)

            private fun activeSpec(): LensOpticalSpec = currentAssembly.opticalSpecAt(
                focalLengthMm = number("focalLengthMm"),
                focusDistanceM = number("focusDistanceM"),
                aperture = number("aperture"),
            )

            private fun resolveFirmwareRoot(): Path {
                val explicit = textVal("lensFirmwareDir").trim()
                if (explicit.isNotEmpty()) {
                    val p = Path.of(explicit)
                    return if (p.isAbsolute) p.normalize() else context.directory.resolve(p).toAbsolutePath().normalize()
                }
                var cur: Path? = context.directory.toAbsolutePath().normalize()
                repeat(5) {
                    if (cur != null) {
                        val cand = cur.resolve("firmware-data").resolve("lenses")
                        if (Files.isDirectory(cand, NOFOLLOW_LINKS)) return cand
                        cur = cur.parent
                    }
                }
                return context.directory.toAbsolutePath().normalize().resolve("firmware-data").resolve("lenses")
            }

            /**
             * Enforces the strict three-way mutual exclusion contract (`MANUAL` | `REAL_FIRMWARE` |
             * `GENERIC_SIMULATED`) with fail-fast rejection on cross-mode contradictions or missing
             * real firmware artifacts.
             */
            private fun resolveAndValidateControlMode(explicitSettings: Map<String, Any>) {
                val requestedModeText = textVal("communicationMode").trim()
                val realFwFile = textVal("realFirmwareFile").trim()
                val realFwFormat = textVal("realFirmwareFormat").trim()
                val realFwSha = textVal("realFirmwareSha256").trim().takeIf { it.isNotEmpty() }

                val resolvedMode: LensCommunicationMode = if (requestedModeText.isNotEmpty()) {
                    LensCommunicationMode.valueOf(requestedModeText)
                } else if (realFwFile.isNotEmpty()) {
                    LensCommunicationMode.REAL_FIRMWARE
                } else {
                    currentAssembly.communicationMode
                }

                when (resolvedMode) {
                    LensCommunicationMode.MANUAL -> {
                        require(realFwFile.isEmpty()) {
                            "Mutually exclusive mode violation: MANUAL mode forbids realFirmwareFile='$realFwFile'"
                        }
                        if (explicitSettings["electronicContacts"] == true) {
                            throw IllegalArgumentException("Mutually exclusive mode violation: MANUAL mode forbids electronicContacts=true")
                        }
                        if ((explicitSettings["focusMode"] as? String)?.let { it != "MF" } == true && requestedModeText == "MANUAL") {
                            throw IllegalArgumentException("Mutually exclusive mode violation: MANUAL mode requires focusMode='MF'")
                        }
                        values = values + mapOf(
                            "electronicContacts" to false,
                            "focusMode" to "MF",
                            "focusMotorType" to FocusMotorTypes.MANUAL,
                            "focusSweepSeconds" to 0.0,
                        )
                        activeContract = LensControlContract.Manual
                        decodedRealFirmware = null
                    }
                    LensCommunicationMode.REAL_FIRMWARE -> {
                        if (explicitSettings["electronicContacts"] == false) {
                            throw IllegalArgumentException("Mutually exclusive mode violation: REAL_FIRMWARE mode forbids electronicContacts=false")
                        }
                        val baseReal = currentAssembly.controlContract as? LensControlContract.RealFirmware
                        val fwFile = realFwFile.ifEmpty { baseReal?.expectedFirmwareFile.orEmpty() }
                        require(fwFile.isNotEmpty()) {
                            "REAL_FIRMWARE mode requires non-blank realFirmwareFile (refusing silent fallback to GENERIC_SIMULATED or MANUAL)"
                        }
                        val fwFmt = if (realFwFile.isNotEmpty()) realFwFormat else (baseReal?.firmwareFormat ?: realFwFormat)
                        val fwSha = realFwSha ?: baseReal?.expectedSha256
                        val baseIdentity = baseReal?.identitySpec
                            ?: (currentAssembly.controlContract as? LensControlContract.GenericSimulated)?.identitySpec
                        val contract = LensControlContract.RealFirmware(
                            firmwareFormat = fwFmt,
                            expectedFirmwareFile = fwFile,
                            expectedSha256 = fwSha,
                            identitySpec = baseIdentity,
                        )
                        // Fail-fast: load & verify analyzed firmware image on disk (ZERO silent downgrade!)
                        val verified = FujiLensDatDecoder.loadVerified(contract, resolveFirmwareRoot())
                        values = values + ("electronicContacts" to true)
                        activeContract = contract
                        decodedRealFirmware = verified
                    }
                    LensCommunicationMode.GENERIC_SIMULATED -> {
                        require(realFwFile.isEmpty()) {
                            "Mutually exclusive mode violation: GENERIC_SIMULATED mode forbids realFirmwareFile='$realFwFile'"
                        }
                        if (explicitSettings["electronicContacts"] == false && requestedModeText == "GENERIC_SIMULATED") {
                            throw IllegalArgumentException("Mutually exclusive mode violation: GENERIC_SIMULATED mode forbids electronicContacts=false")
                        }
                        val existingId = (currentAssembly.controlContract as? LensControlContract.GenericSimulated)?.identitySpec
                            ?: activeTemplate().let { tpl ->
                                LenscomIdentitySpec(
                                    shortId = tpl.id.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(7).ifEmpty { "GFLENS" },
                                    modelAscii = textVal("model").ifBlank { "GF Lens" }.take(63),
                                    capabilityFlags1 = if (tpl.mountType == "GF") 0x01 else 0x00,
                                    isZoom = activeSpec().maxFocalLengthMm > activeSpec().minFocalLengthMm + 0.5,
                                    hasOis = boolVal("oisEnabled"),
                                    minFocalMm = activeSpec().minFocalLengthMm,
                                    maxFocalMm = activeSpec().maxFocalLengthMm,
                                    minAperture = activeSpec().minAperture,
                                    maxAperture = activeSpec().maxAperture,
                                    focusMotorType = textVal("focusMotorType"),
                                    focusSweepSeconds = number("focusSweepSeconds"),
                                )
                            }
                        activeContract = if (boolVal("electronicContacts")) {
                            LensControlContract.GenericSimulated(existingId)
                        } else {
                            LensControlContract.Manual
                        }
                        decodedRealFirmware = null
                    }
                }
            }

            override val enabled: Boolean get() = opticalFilter.enabled
            override val electronicContacts: Boolean get() = boolVal("electronicContacts")
            override val oisEnabled: Boolean get() = boolVal("oisEnabled")
            override val minFocalLengthMm: Double get() = activeSpec().minFocalLengthMm
            override val maxFocalLengthMm: Double get() = activeSpec().maxFocalLengthMm
            override val minAperture: Double get() = activeSpec().minAperture
            override val maxAperture: Double get() = activeSpec().maxAperture

            override val minFocusDistanceM: Double
                get() {
                    val spec = activeSpec()
                    if (spec.maxFocalLengthMm - spec.minFocalLengthMm <= 1e-3 || spec.teleMinFocusDistanceM == spec.minFocusDistanceM) {
                        return spec.minFocusDistanceM
                    }
                    val t = ((number("focalLengthMm") - spec.minFocalLengthMm) / (spec.maxFocalLengthMm - spec.minFocalLengthMm)).coerceIn(0.0, 1.0)
                    return spec.minFocusDistanceM + (spec.teleMinFocusDistanceM - spec.minFocusDistanceM) * t
                }

            override val focusMode: String get() = textVal("focusMode")

            override val activeLensId: String get() = currentAssembly.lensId
            override val activeAdapterId: String? get() = currentAssembly.adapterId
            override val communicationMode: LensCommunicationMode get() = activeContract.mode
            override val controlContract: LensControlContract get() = activeContract

            override val lenscomIdentity: LenscomIdentitySpec?
                get() = when (val c = activeContract) {
                    is LensControlContract.Manual -> null
                    is LensControlContract.GenericSimulated -> c.identitySpec
                    is LensControlContract.RealFirmware -> c.identitySpec
                }

            override val realFirmwarePayload: ByteArray?
                get() = decodedRealFirmware?.payload

            override val customEepromBytes: ByteArray?
                get() = currentAssembly.eepromBytes()

            override fun rescanExternalPlugins(): Map<String, Any?> = synchronized(registryLock) {
                val nextRegistry = LensModuleLoader.discover(
                    componentDir = context.directory,
                    explicitLensesDir = explicitLensesDir,
                )
                val oldRegistry = registry
                registry = nextRegistry
                runCatching {
                    currentAssembly = nextRegistry.resolve(textVal("template"), textVal("adapterId"))
                    resolveAndValidateControlMode(explicitSettings = emptyMap())
                }
                syncOpticalFilter()
                oldRegistry.close()
                linkedMapOf(
                    "jarCount" to nextRegistry.jarCount,
                    "lensCount" to nextRegistry.lenses.size,
                    "adapterCount" to nextRegistry.adapters.size,
                    "discoveredLensIds" to nextRegistry.lenses.map { it.id },
                    "discoveredAdapterIds" to nextRegistry.adapters.map { it.id },
                )
            }

            override val focusMotorBusy: Boolean
                get() {
                    advanceFocusMotorByClock()
                    return focusMotorActive
                }

            override val focusEncoderPosition: Int
                get() {
                    advanceFocusMotorByClock()
                    val pos = distanceToMotorPosition(number("focusDistanceM"), minFocusDistanceM)
                    return (pos * 0x7FFF.toDouble()).toInt().coerceIn(0, 0x7FFF)
                }

            override val apertureRingMode: String
                get() = if (!electronicContacts || activeContract is LensControlContract.Manual) {
                    "MANUAL"
                } else {
                    textVal("apertureRingMode")
                }

            override val irisStepIndex: Int get() = liveIrisStepIdx

            override val apertureRingStepIndex: Int get() = ringStepIdx

            override fun commandFocusEncoder(targetEncoder: Int) {
                commandFocusEncoder(targetEncoder, 0)
            }

            override fun commandFocusEncoder(targetEncoder: Int, speedPulsesPerSec: Int) {
                if (activeContract is LensControlContract.Manual || !electronicContacts) return
                advanceFocusMotorByClock()
                val clampedEnc = targetEncoder.coerceIn(0, 0x7FFF)
                val curPos = distanceToMotorPosition(number("focusDistanceM"), minFocusDistanceM)
                val curEnc = (curPos * 0x7FFF.toDouble()).toInt().coerceIn(0, 0x7FFF)
                if (clampedEnc == curEnc) {
                    focusMotorActive = false
                    activeFocusSpeedPulsesPerSec = 0
                    focusStartPos = curPos
                    focusTargetPos = curPos
                    focusTargetDistanceM = number("focusDistanceM")
                    values = values + ("focusTargetDistanceM" to focusTargetDistanceM)
                    syncOpticalFilter()
                    return
                }
                activeFocusSpeedPulsesPerSec = speedPulsesPerSec.coerceAtLeast(0)
                val normPos = clampedEnc.toDouble() / 0x7FFF.toDouble()
                val targetDist = motorPositionToDistance(normPos, minFocusDistanceM)
                driveFocusToward(targetDist, instantOverride = false, preserveCommandedSpeed = true)
            }

            override fun commandApertureThirdStopIndex(avThirds: Int) {
                if (activeContract is LensControlContract.Manual || !electronicContacts) return
                val stops = apertureStops()
                if (stops.isEmpty()) return
                val idx = avThirds.coerceIn(0, stops.lastIndex)
                val clampedF = stops[idx]
                liveIrisStepIdx = idx
                bodyIrisSynced = true
                bodyCommandedAperture = clampedF
                if (abs(number("aperture") - clampedF) > 1e-3) {
                    values = values + ("aperture" to clampedF)
                    syncOpticalFilter()
                }
            }

            private fun distanceToMotorPosition(distanceM: Double, effMfd: Double): Double {
                val safeMfd = effMfd.coerceAtLeast(0.01)
                if (!distanceM.isFinite() || distanceM >= 500.0) return 0.0
                return (safeMfd / distanceM.coerceAtLeast(safeMfd)).coerceIn(0.0, 1.0)
            }

            private fun motorPositionToDistance(pos: Double, effMfd: Double): Double {
                val safeMfd = effMfd.coerceAtLeast(0.01)
                val p = pos.coerceIn(0.0, 1.0)
                return if (p <= 1e-4) 1000.0 else (safeMfd / p).coerceIn(safeMfd, 1e9)
            }

            private fun driveFocusToward(
                targetDistanceM: Double,
                instantOverride: Boolean = false,
                preserveCommandedSpeed: Boolean = false,
            ) {
                if (!preserveCommandedSpeed || instantOverride) {
                    activeFocusSpeedPulsesPerSec = 0
                }
                val effMfd = minFocusDistanceM
                val clamped = targetDistanceM.coerceIn(effMfd, 1e9)
                val sweepSec = number("focusSweepSeconds")
                val instant = instantOverride || boolVal("focusMotorInstant") || sweepSec <= 1e-4 || textVal("focusMotorType") == FocusMotorTypes.MANUAL
                focusTargetDistanceM = clamped
                lastMotorTick = context.clock.ticks()
                lastAdvancedClockTick = Long.MIN_VALUE
                if (instant) {
                    focusMotorActive = false
                    activeFocusSpeedPulsesPerSec = 0
                    focusStartPos = distanceToMotorPosition(clamped, effMfd)
                    focusTargetPos = focusStartPos
                    values = values + mapOf(
                        "focusDistanceM" to clamped,
                        "focusTargetDistanceM" to clamped,
                    )
                } else {
                    val curPos = distanceToMotorPosition(number("focusDistanceM"), effMfd)
                    val dstPos = distanceToMotorPosition(clamped, effMfd)
                    if (abs(dstPos - curPos) <= 1e-5) {
                        focusMotorActive = false
                        activeFocusSpeedPulsesPerSec = 0
                        focusStartPos = dstPos
                        focusTargetPos = dstPos
                        values = values + mapOf(
                            "focusDistanceM" to clamped,
                            "focusTargetDistanceM" to clamped,
                        )
                    } else {
                        focusStartPos = curPos
                        focusTargetPos = dstPos
                        focusMotorActive = true
                        focusMotorVersionNonce++
                        values = values + ("focusTargetDistanceM" to clamped)
                    }
                }
                syncOpticalFilter()
            }

            private fun advanceFocusMotor(dtSeconds: Double) {
                if (!focusMotorActive || dtSeconds <= 0.0) return
                val sweepSec = number("focusSweepSeconds")
                val effMfd = minFocusDistanceM
                if (sweepSec <= 1e-4 || boolVal("focusMotorInstant")) {
                    focusMotorActive = false
                    activeFocusSpeedPulsesPerSec = 0
                    values = values + mapOf(
                        "focusDistanceM" to focusTargetDistanceM,
                        "focusTargetDistanceM" to focusTargetDistanceM,
                    )
                    syncOpticalFilter()
                    return
                }
                val curPos = distanceToMotorPosition(number("focusDistanceM"), effMfd)
                val speedScale = if (activeFocusSpeedPulsesPerSec in 1 until 0x7FFF) {
                    activeFocusSpeedPulsesPerSec.toDouble() / 0x7FFF.toDouble()
                } else {
                    1.0
                }
                val maxDelta = (dtSeconds / sweepSec) * speedScale
                val remaining = focusTargetPos - curPos
                if (abs(remaining) <= maxDelta) {
                    focusMotorActive = false
                    activeFocusSpeedPulsesPerSec = 0
                    values = values + mapOf(
                        "focusDistanceM" to focusTargetDistanceM,
                        "focusTargetDistanceM" to focusTargetDistanceM,
                    )
                } else {
                    val nextPos = (curPos + if (remaining > 0.0) maxDelta else -maxDelta).coerceIn(0.0, 1.0)
                    val nextDist = motorPositionToDistance(nextPos, effMfd)
                    values = values + ("focusDistanceM" to nextDist)
                }
                focusMotorVersionNonce++
                syncOpticalFilter()
            }

            private fun advanceFocusMotorByClock(fallbackDtSeconds: Double = 0.0) {
                if (Thread.currentThread() !== ownerThread) return
                val nowTick = context.clock.ticks()
                val deltaTicks = nowTick - lastMotorTick
                lastMotorTick = nowTick
                if (!focusMotorActive) return
                val dt = if (deltaTicks > 0L) {
                    lastAdvancedClockTick = nowTick
                    deltaTicks.toDouble() / NOMINAL_TICKS_PER_SECOND
                } else if (fallbackDtSeconds > 0.0 && nowTick != lastAdvancedClockTick) {
                    fallbackDtSeconds
                } else {
                    0.0
                }
                if (dt > 0.0) {
                    advanceFocusMotor(dt)
                }
            }

            private fun currentMotorProgress(): Double {
                if (!focusMotorActive) return 1.0
                val span = abs(focusTargetPos - focusStartPos)
                if (span <= 1e-6) return 1.0
                val curPos = distanceToMotorPosition(number("focusDistanceM"), minFocusDistanceM)
                val moved = abs(curPos - focusStartPos)
                return (moved / span).coerceIn(0.0, 1.0)
            }

            override fun triggerAutoFocus(): Double {
                if (focusMode == "MF" || !electronicContacts || activeContract is LensControlContract.Manual) {
                    return number("focusDistanceM")
                }
                val target = opticalFilter.lastCenterDepthMeters ?: number("subjectDistanceM")
                driveFocusToward(target)
                return number("focusDistanceM")
            }

            override fun setFocusDistanceM(distanceM: Double) {
                require(distanceM.isFinite() && distanceM in 0.01..1e9) {
                    "focusDistanceM out of range: $distanceM"
                }
                driveFocusToward(distanceM, instantOverride = true)
            }

            override fun fallbackSubjectDistanceM(): Double = number("subjectDistanceM")

            override fun sampleRegionDepthMeters(normX0: Double, normY0: Double, normX1: Double, normY1: Double): Double {
                val snap = lastDepthSnapshot
                val buf = snap?.depthMeters
                val w = snap?.width ?: 0
                val h = snap?.height ?: 0
                if (buf == null || w <= 0 || h <= 0 || buf.size < w * h) {
                    return fallbackSubjectDistanceM()
                }
                val x0 = normX0.coerceIn(0.0, 1.0)
                val y0 = normY0.coerceIn(0.0, 1.0)
                val x1 = normX1.coerceIn(x0, 1.0)
                val y1 = normY1.coerceIn(y0, 1.0)
                val samples = DoubleArray(25)
                var count = 0
                for (ry in 0 until 5) {
                    val ny = y0 + (y1 - y0) * ((ry + 0.5) / 5.0)
                    val py = (ny * (h - 1)).roundToInt().coerceIn(0, h - 1)
                    val rowOff = py * w
                    for (rx in 0 until 5) {
                        val nx = x0 + (x1 - x0) * ((rx + 0.5) / 5.0)
                        val px = (nx * (w - 1)).roundToInt().coerceIn(0, w - 1)
                        val d = buf[rowOff + px].toDouble()
                        if (d.isFinite() && d >= 0.01 && d <= 1e6) {
                            samples[count++] = d
                        }
                    }
                }
                if (count == 0) return fallbackSubjectDistanceM()
                java.util.Arrays.sort(samples, 0, count)
                return samples[count / 2]
            }

            override fun filterLinearRgbToBayer(
                srcWidth: Int,
                srcHeight: Int,
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                depthMeters: FloatArray?,
                exposure: Exposure,
                geometry: SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                lastDepthSnapshot = if (depthMeters != null && depthMeters.size == srcWidth * srcHeight) {
                    DepthSnapshot(srcWidth, srcHeight, depthMeters)
                } else {
                    null
                }
                advanceFocusMotorByClock(fallbackDtSeconds = exposure.durationNanos.coerceAtLeast(0L) * 1e-9)
                return opticalFilter.filterLinearRgbToBayer(
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    red = red,
                    green = green,
                    blue = blue,
                    depthMeters = depthMeters,
                    exposure = exposure,
                    geometry = geometry,
                    destination = destination,
                    frameIndex = frameIndex,
                )
            }

            override fun filterRadianceFrame(
                frame: LinearRadianceFrame,
                exposure: Exposure,
                geometry: SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                val depth = frame.depthMeters
                lastDepthSnapshot = if (depth != null && depth.size == frame.width * frame.height) {
                    DepthSnapshot(frame.width, frame.height, depth)
                } else {
                    null
                }
                advanceFocusMotorByClock(fallbackDtSeconds = exposure.durationNanos.coerceAtLeast(0L) * 1e-9)
                return opticalFilter.filterRadianceFrame(
                    frame = frame,
                    exposure = exposure,
                    geometry = geometry,
                    destination = destination,
                    frameIndex = frameIndex,
                )
            }

            override fun handlesSpatialFilterStack(filterStack: OpticalFilterStackPort?): Boolean =
                opticalFilter.handlesSpatialFilterStack(filterStack)

            override fun opticalStateVersion(): Long {
                advanceFocusMotorByClock()
                val base = opticalFilter.opticalStateVersion()
                if (base < 0L) return base
                if (!focusMotorActive) return base
                val nonce = ++focusMotorVersionNonce
                return ((base xor (nonce * -7046029254386353131L)) * 1099511628211L) and Long.MAX_VALUE
            }

            override fun supportsFusedIspStats(): Boolean = opticalFilter.supportsFusedIspStats()

            override fun sensorBlackLevelAdu(bitDepth: Int): Int = opticalFilter.sensorBlackLevelAdu(bitDepth)

            override fun hasHotStage1CacheForExposureOnlyChange(): Boolean =
                opticalFilter.hasHotStage1CacheForExposureOnlyChange()

            override fun tryFilterRadianceToIspYuv(
                frame: LinearRadianceFrame,
                geometry: SensorGeometry,
                request: LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
                frameIndex: Long,
            ): Boolean {
                advanceFocusMotorByClock(fallbackDtSeconds = 1.0 / 60.0)
                val ok = opticalFilter.tryFilterRadianceToIspYuv(
                    frame = frame,
                    geometry = geometry,
                    request = request,
                    outYOrPacked = outYOrPacked,
                    outUvPlane = outUvPlane,
                    frameIndex = frameIndex,
                )
                if (ok) {
                    val depth = frame.depthMeters
                    lastDepthSnapshot = if (depth != null && depth.size == frame.width * frame.height) {
                        DepthSnapshot(frame.width, frame.height, depth)
                    } else {
                        null
                    }
                }
                return ok
            }

            override fun supportsGpuScene3d(): Boolean = opticalFilter.supportsGpuScene3d()

            override val supportsFacePreviewResize: Boolean
                get() = computeBackend?.supportsFacePreviewResize == true

            override fun resizeFacePreview(source: java.lang.foreign.MemorySegment, destination: java.lang.foreign.MemorySegment, yyuv: Boolean): Boolean =
                resizeFacePreview(source, 2048, destination, yyuv)

            override fun resizeFacePreview(
                source: java.lang.foreign.MemorySegment, sourceStrideBytes: Int,
                destination: java.lang.foreign.MemorySegment, yyuv: Boolean,
            ): Boolean =
                computeBackend?.resizeFacePreview(source, sourceStrideBytes, destination, yyuv, yyuvIn = false) == true

            override fun resizeFacePreview(
                source: java.lang.foreign.MemorySegment, sourceStrideBytes: Int,
                destination: java.lang.foreign.MemorySegment, yyuv: Boolean, yyuvIn: Boolean,
            ): Boolean =
                computeBackend?.resizeFacePreview(source, sourceStrideBytes, destination, yyuv, yyuvIn) == true

            override fun tryFilterGpuScene3dToIspYuv(
                spec: GpuScene3dRenderSpec,
                geometry: SensorGeometry,
                request: LiveViewIspFusionRequest,
                outYOrPacked: ByteArray,
                outUvPlane: ByteArray?,
                frameIndex: Long,
            ): Boolean {
                advanceFocusMotorByClock(fallbackDtSeconds = 1.0 / 60.0)
                val ok = opticalFilter.tryFilterGpuScene3dToIspYuv(
                    spec = spec,
                    geometry = geometry,
                    request = request,
                    outYOrPacked = outYOrPacked,
                    outUvPlane = outUvPlane,
                    frameIndex = frameIndex,
                )
                if (ok) {
                    val g = opticalFilter.lastGpuDepthGrid
                    lastDepthSnapshot = if (g != null && g.depthMeters.size >= g.tilesX * g.tilesY) {
                        DepthSnapshot(g.tilesX, g.tilesY, g.depthMeters)
                    } else null
                }
                return ok
            }

            override fun tryFilterGpuScene3dToBayer(
                spec: GpuScene3dRenderSpec,
                exposure: Exposure,
                geometry: SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                advanceFocusMotorByClock(fallbackDtSeconds = exposure.durationNanos.coerceAtLeast(0L) * 1e-9)
                val written = opticalFilter.tryFilterGpuScene3dToBayer(
                    spec = spec,
                    exposure = exposure,
                    geometry = geometry,
                    destination = destination,
                    frameIndex = frameIndex,
                )
                if (written > 0) {
                    val g = opticalFilter.lastGpuDepthGrid
                    lastDepthSnapshot = if (g != null && g.depthMeters.size >= g.tilesX * g.tilesY) {
                        DepthSnapshot(g.tilesX, g.tilesY, g.depthMeters)
                    } else null
                }
                return written
            }

            override fun applySettings(settings: Map<String, Any>) {
                val prevValues = values
                val prevAssembly = currentAssembly
                val prevContract = activeContract
                val prevDecoded = decodedRealFirmware
                val prevAfTrigger = lastAfTrigger
                val prevRescanTrigger = lastRescanTrigger
                val prevFocusTarget = focusTargetDistanceM
                val prevMotorActive = focusMotorActive
                val prevFocusSpeed = activeFocusSpeedPulsesPerSec
                val prevStartPos = focusStartPos
                val prevTargetPos = focusTargetPos
                val prevRingStep = ringStepIdx
                val prevLiveIris = liveIrisStepIdx
                val prevBodySynced = bodyIrisSynced
                val prevBodyAperture = bodyCommandedAperture

                try {
                    val effectiveSettings = filterExplicitOverrides(baseDefaults, settings)
                    val requestedRescan = (effectiveSettings["rescanPlugins"] as? Number)?.toLong() ?: lastRescanTrigger
                    if (requestedRescan > lastRescanTrigger) {
                        lastRescanTrigger = requestedRescan
                        rescanExternalPlugins()
                    }
                    val expanded = LinkedHashMap<String, Any>()
                    val requestedTemplate = effectiveSettings["template"] as? String
                    val requestedAdapter = effectiveSettings["adapterId"] as? String
                    val templateOrAdapterChanged = requestedTemplate != null || requestedAdapter != null
                    if (templateOrAdapterChanged) {
                        val nextTemplate = requestedTemplate ?: textVal("template")
                        val nextAdapter = requestedAdapter ?: if (requestedTemplate != null) "" else textVal("adapterId")
                        val resolved = registry.resolve(nextTemplate, nextAdapter)
                        currentAssembly = resolved
                        if (nextTemplate != LensTemplateCatalog.CUSTOM_ID || nextAdapter.isNotBlank()) {
                            expanded.putAll(opticalSpecToDefaultSettings(resolved))
                        } else {
                            expanded["adapterId"] = ""
                            expanded["communicationMode"] = ""
                            expanded["realFirmwareFile"] = ""
                            expanded["apertureRingMode"] = "A"
                        }
                    }
                    expanded.putAll(effectiveSettings)
                    // Setting aperture directly without specifying apertureRingMode turns the physical lens ring to MANUAL.
                    if (
                        !templateOrAdapterChanged &&
                        effectiveSettings.containsKey("aperture") &&
                        !effectiveSettings.containsKey("apertureRingMode")
                    ) {
                        expanded["apertureRingMode"] = "MANUAL"
                    }
                    super.applySettings(expanded)
                    resolveAndValidateControlMode(explicitSettings = effectiveSettings)

                    // Variable-aperture zoom support (SPI v2): if zooming raises dynamic minAperture above current aperture, stop down naturally.
                    if (
                        !templateOrAdapterChanged &&
                        effectiveSettings.containsKey("focalLengthMm") &&
                        !effectiveSettings.containsKey("aperture")
                    ) {
                        val dynMinAp = activeSpec().minAperture
                        if (dynMinAp > number("aperture") + 1e-6) {
                            values = values + ("aperture" to dynMinAp)
                        }
                    }

                    if (!electronicContacts || activeContract is LensControlContract.Manual) {
                        values = values + ("apertureRingMode" to "MANUAL")
                    }
                    if (templateOrAdapterChanged) {
                        bodyIrisSynced = false
                        bodyCommandedAperture = null
                        val step = nearestApertureStopIndex(number("aperture"))
                        ringStepIdx = step
                        liveIrisStepIdx = step
                    } else if (apertureRingMode == "MANUAL") {
                        val step = nearestApertureStopIndex(number("aperture"))
                        ringStepIdx = step
                        liveIrisStepIdx = step
                    } else if (effectiveSettings.containsKey("apertureRingMode")) {
                        // Switched to A or C: keep current live iris step until camera body commands cmd 0x062
                        liveIrisStepIdx = nearestApertureStopIndex(number("aperture"))
                    }

                    val currentAfTrigger = longVal("afTrigger")
                    val afTriggered = currentAfTrigger > lastAfTrigger
                    lastAfTrigger = currentAfTrigger

                    val hasExplicitFocusDistance = effectiveSettings.containsKey("focusDistanceM")
                    val hasExplicitFocusTarget = effectiveSettings.containsKey("focusTargetDistanceM")
                    if (hasExplicitFocusDistance || (templateOrAdapterChanged && !hasExplicitFocusTarget)) {
                        focusTargetDistanceM = number("focusDistanceM")
                        focusMotorActive = false
                        activeFocusSpeedPulsesPerSec = 0
                        values = values + ("focusTargetDistanceM" to focusTargetDistanceM)
                    }

                    if (afTriggered && focusMode != "MF" && electronicContacts && activeContract !is LensControlContract.Manual) {
                        val target = opticalFilter.lastCenterDepthMeters ?: number("subjectDistanceM")
                        driveFocusToward(target)
                    } else if (hasExplicitFocusTarget && !hasExplicitFocusDistance) {
                        driveFocusToward(number("focusTargetDistanceM"), instantOverride = false)
                    } else if (
                        focusMode == "AF-C" &&
                        electronicContacts &&
                        activeContract !is LensControlContract.Manual &&
                        effectiveSettings.containsKey("subjectDistanceM") &&
                        !hasExplicitFocusDistance
                    ) {
                        val target = opticalFilter.lastCenterDepthMeters ?: number("subjectDistanceM")
                        driveFocusToward(target)
                    } else {
                        syncOpticalFilter()
                    }
                    context.ports.find("mount", MountPort::class.java)?.syncHardwareDetect()
                } catch (error: Throwable) {
                    values = prevValues
                    currentAssembly = prevAssembly
                    activeContract = prevContract
                    decodedRealFirmware = prevDecoded
                    lastAfTrigger = prevAfTrigger
                    lastRescanTrigger = prevRescanTrigger
                    focusTargetDistanceM = prevFocusTarget
                    focusMotorActive = prevMotorActive
                    activeFocusSpeedPulsesPerSec = prevFocusSpeed
                    focusStartPos = prevStartPos
                    focusTargetPos = prevTargetPos
                    ringStepIdx = prevRingStep
                    liveIrisStepIdx = prevLiveIris
                    bodyIrisSynced = prevBodySynced
                    bodyCommandedAperture = prevBodyAperture
                    syncOpticalFilter()
                    throw error
                }
            }

            private fun buildOpticalProfile(): OpticalLensProfile {
                val baseParams = parameters()
                val staticSpec = currentAssembly.opticalSpec
                val spec = activeSpec()
                fun dynDbl(key: String, staticVal: Double, dynVal: Double): Double =
                    if (dynVal != staticVal && abs(number(key) - staticVal) <= 1e-9) dynVal else number(key)
                fun dynInt(key: String, staticVal: Int, dynVal: Int): Int =
                    if (dynVal != staticVal && longVal(key).toInt() == staticVal) dynVal else longVal(key).toInt()
                val effTransmission = dynDbl("transmission", staticSpec.transmission, spec.transmission)
                val effBreathing = dynDbl("focusBreathing", staticSpec.focusBreathing, spec.focusBreathing)
                val params = if (effTransmission != baseParams.transmission || effBreathing != baseParams.focusBreathing) {
                    baseParams.copy(transmission = effTransmission, focusBreathing = effBreathing)
                } else {
                    baseParams
                }
                val effectiveMinFNumber = if (currentAssembly.templateId == LensTemplateCatalog.CUSTOM_ID) {
                    params.aperture
                } else {
                    spec.minAperture
                }
                return OpticalLensProfile.fromLensParameters(
                    lens = params,
                    referenceFocalLengthMm = number("referenceFocalLengthMm"),
                    k1 = dynDbl("distortionK1", staticSpec.distortionK1, spec.distortionK1),
                    k2 = dynDbl("distortionK2", staticSpec.distortionK2, spec.distortionK2),
                    caRedScale = dynDbl("caRedScale", staticSpec.caRedScale, spec.caRedScale),
                    caBlueScale = dynDbl("caBlueScale", staticSpec.caBlueScale, spec.caBlueScale),
                    longitudinalCaDefocusMm = dynDbl("longitudinalCaDefocusMm", staticSpec.longitudinalCaDefocusMm, spec.longitudinalCaDefocusMm),
                    cos4Power = dynDbl("vignettingCos4", staticSpec.vignettingCos4, spec.vignettingCos4),
                    mechanicalVignetting = dynDbl("vignettingMechanical", staticSpec.vignettingMechanical, spec.vignettingMechanical),
                    imageCircleMm = dynDbl("imageCircleMm", staticSpec.imageCircleMm, spec.imageCircleMm),
                    minFNumber = effectiveMinFNumber,
                    wideOpenContrastDrop = dynDbl("wideOpenContrastDrop", staticSpec.wideOpenContrastDrop, spec.wideOpenContrastDrop),
                    flareStrength = dynDbl("flareStrength", staticSpec.flareStrength, spec.flareStrength),
                    flareTintR = dynDbl("flareTintR", staticSpec.flareTintR, spec.flareTintR),
                    flareTintG = dynDbl("flareTintG", staticSpec.flareTintG, spec.flareTintG),
                    flareTintB = dynDbl("flareTintB", staticSpec.flareTintB, spec.flareTintB),
                    apertureBladeCount = dynInt("apertureBladeCount", staticSpec.apertureBladeCount, spec.apertureBladeCount).coerceIn(5, 11),
                    apertureBladeRoundness = dynDbl("apertureBladeRoundness", staticSpec.apertureBladeRoundness, spec.apertureBladeRoundness),
                    bokehCatsEye = dynDbl("bokehCatsEye", staticSpec.bokehCatsEye, spec.bokehCatsEye),
                    bokehOnionRing = dynDbl("bokehOnionRing", staticSpec.bokehOnionRing, spec.bokehOnionRing),
                    bokehRimHighlight = dynDbl("bokehRimHighlight", staticSpec.bokehRimHighlight, spec.bokehRimHighlight),
                )
            }

            private fun syncOpticalFilter() {
                val nextBackend = OpticsBackendPreference.parse(textVal("opticsBackend"))
                val nextDevice = textVal("vulkanDevice").trim().ifEmpty { VulkanOpticalBackend.DEFAULT_VULKAN_DEVICE }
                val backendChanged = nextBackend != desiredOpticsBackend || nextDevice != desiredVulkanDevice
                desiredOpticsBackend = nextBackend
                desiredVulkanDevice = nextDevice
                if (backendChanged) {
                    ensureComputeBackend()
                }
                val profile = buildOpticalProfile()
                val ibis = IbisShiftState(
                    shiftXPixels = number("ibisShiftXPx"),
                    shiftYPixels = number("ibisShiftYPx"),
                    rollRadians = number("ibisRollRad"),
                )
                val noise = CmosNoiseConfig(
                    seed = longVal("noiseSeed"),
                    enableNoise = boolVal("noiseEnabled"),
                    blackLevel = longVal("blackLevel").toInt(),
                    nominalExposureNanos = 1000L,
                    referenceFNumber = 2.8,
                )
                opticalFilter.update(
                    enabled = boolVal("opticsEnabled"),
                    profile = profile,
                    ibis = ibis,
                    noiseConfig = noise,
                    samplingMode = SamplingMode.BILINEAR,
                    borderMode = BorderMode.CLAMP,
                    planarSubjectDistanceM = number("subjectDistanceM"),
                    lensOpticalHook = currentAssembly.opticalHook(),
                )
            }

            override fun state(): Map<String, Any?> {
                advanceFocusMotorByClock()
                val spec = activeSpec()
                val profile = buildOpticalProfile()
                val targetDepth = opticalFilter.lastCenterDepthMeters ?: number("subjectDistanceM")
                val cocMicrons = profile.circleOfConfusionDiameterMicrons(targetDepth)
                val cocPixels = profile.circleOfConfusionRadiusPixels(targetDepth, LIVE_VIEW_REF_WIDTH_PX) * 2.0
                val (dofNearM, rawDofFarM) = profile.depthOfFieldRangeMeters()
                val dofFarM = if (rawDofFarM.isFinite()) rawDofFarM.coerceAtMost(MAX_JSON_DOF_FAR_M) else MAX_JSON_DOF_FAR_M
                val rawHyperfocal = profile.hyperfocalDistanceMeters()
                val hyperfocalM = if (rawHyperfocal.isFinite()) rawHyperfocal.coerceAtMost(MAX_JSON_DOF_FAR_M) else MAX_JSON_DOF_FAR_M
                val effMfd = minFocusDistanceM
                val stops = ApertureScale.stopsBetween(spec.minAperture, spec.maxAperture)

                    val tplFallback = activeTemplate()
                    val activeLensMeta = currentAssembly.lensModule?.metadata
                        ?: PluginMetadata(
                            description = tplFallback.opticalCharacterSummary,
                            descriptionZh = tplFallback.opticalCharacterSummaryZh,
                            descriptions = buildMap {
                                if (tplFallback.opticalCharacterSummary.isNotBlank()) put("en", tplFallback.opticalCharacterSummary)
                                if (tplFallback.opticalCharacterSummaryZh.isNotBlank()) put("zh_CN", tplFallback.opticalCharacterSummaryZh)
                            },
                        )
                    val activeAdapterMeta = currentAssembly.adapterModule?.metadata
                    return super.state() + linkedMapOf<String, Any?>(
                    "templateDisplayName" to spec.displayName,
                    "mountType" to spec.mountType,
                    "activeLensId" to currentAssembly.lensId,
                    "activeAdapterId" to (currentAssembly.adapterId ?: ""),
                    "communicationMode" to activeContract.mode.name,
                    "loadedFromJar" to currentAssembly.loadedFromJar,
                    "realFirmwareVerifiedSha256" to (decodedRealFirmware?.payloadSha256 ?: ""),
                    "minFocalLengthMm" to spec.minFocalLengthMm,
                    "maxFocalLengthMm" to spec.maxFocalLengthMm,
                    "minAperture" to spec.minAperture,
                    "maxAperture" to spec.maxAperture,
                    "apertureStops" to stops,
                    "apertureRingMode" to apertureRingMode,
                    "irisStepIndex" to liveIrisStepIdx,
                    "apertureRingStepIndex" to ringStepIdx,
                    "bodyIrisSynced" to bodyIrisSynced,
                    "bodyCommandedAperture" to bodyCommandedAperture,
                    "minFocusDistanceM" to effMfd,
                    "wideMinFocusDistanceM" to spec.minFocusDistanceM,
                    "teleMinFocusDistanceM" to spec.teleMinFocusDistanceM,
                    "equivalent35mmFocalMm" to profile.equivalent35mmFocalMm(),
                    "horizontalFovDeg" to profile.horizontalFovDeg(),
                    "cocMicrons" to cocMicrons,
                    "cocPixels" to cocPixels,
                    "inFocus" to (cocMicrons <= IN_FOCUS_COC_MICRONS),
                    "dofNearM" to dofNearM,
                    "dofFarM" to dofFarM,
                    "nearDofM" to dofNearM,
                    "farDofM" to dofFarM,
                    "hyperfocalM" to hyperfocalM,
                    "focusTargetDistanceM" to focusTargetDistanceM,
                    "focusMotorActive" to focusMotorActive,
                    "focusMotorProgress" to currentMotorProgress(),
                    "distortionK1" to profile.k1,
                    "distortionK2" to profile.k2,
                    "caRedScale" to profile.caRedScale,
                    "caBlueScale" to profile.caBlueScale,
                    "vignettingCos4" to profile.cos4Power,
                    "vignettingMechanical" to profile.mechanicalVignetting,
                    "opticalCharacterSummary" to spec.opticalCharacterSummary,
                    "hasOpticalHook" to (currentAssembly.opticalHook() != null),
                    "hasCustomEeprom" to (currentAssembly.eepromBytes() != null),
                    "activeLensMetadata" to activeLensMeta.toStateMap(),
                    "activeAdapterMetadata" to activeAdapterMeta?.toStateMap(),
                    "pluginJarCount" to registry.jarCount,
                    "framesFiltered" to opticalFilter.framesFiltered,
                    "opticsBackendDiagnostics" to ensureComputeBackend().diagnostics().toMap(),
                    "availableVulkanDevices" to currentVulkanDevices(),
                    "activeVulkanDevice" to ensureComputeBackend().diagnostics().let { diag ->
                        linkedMapOf<String, Any?>(
                            "backend" to diag.activeBackend.name,
                            "preference" to diag.preference.configValue,
                            "requestedDevice" to diag.requestedDevice,
                            "index" to diag.deviceIndex,
                            "name" to diag.deviceName,
                            "deviceType" to diag.deviceType,
                            "apiVersion" to diag.vulkanApiVersion,
                            "supportsExternalMemory" to diag.externalMemorySupported,
                            "fallbackReason" to (diag.fallbackReason ?: ""),
                        )
                    },
                    "availableTemplates" to TEMPLATE_SUMMARIES,
                    "discoveredLensModules" to registry.lensSummaries(),
                    "discoveredAdapterModules" to registry.adapterSummaries(),
                )
            }

            override fun parameters(): LensParameters {
                advanceFocusMotorByClock()
                return LensParameters(
                    textVal("model"),
                    number("focalLengthMm"),
                    number("aperture"),
                    number("focusDistanceM"),
                    number("transmission"),
                    number("focusBreathing"),
                )
            }

            override fun close() {
                synchronized(computeBackendLock) {
                    runCatching { computeBackend?.close() }
                    computeBackend = null
                }
                synchronized(registryLock) {
                    registry.close()
                }
                super<ScalarComponent>.close()
            }

            private fun textVal(key: String) = values.getValue(key) as String
            private fun number(key: String) = (values.getValue(key) as Number).toDouble()
            private fun longVal(key: String) = (values.getValue(key) as Number).toLong()
            private fun boolVal(key: String) = values.getValue(key) as Boolean

            override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to saveScalarValues(),
            )

            override fun restoreSnapshot(data: Map<String, Any?>) {
                require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
                require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
                require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
                restoreScalarValues(data["scalarValues"], descriptor)
                currentAssembly = registry.resolve(textVal("template"), textVal("adapterId"))
                resolveAndValidateControlMode(explicitSettings = emptyMap())
                if (!electronicContacts || activeContract is LensControlContract.Manual) {
                    values = values + ("apertureRingMode" to "MANUAL")
                }
                val restoredStep = nearestApertureStopIndex(number("aperture"))
                ringStepIdx = restoredStep
                liveIrisStepIdx = restoredStep
                bodyIrisSynced = false
                bodyCommandedAperture = null
                lastAfTrigger = longVal("afTrigger")
                focusTargetDistanceM = number("focusDistanceM")
                values = values + ("focusTargetDistanceM" to focusTargetDistanceM)
                focusMotorActive = false
                activeFocusSpeedPulsesPerSec = 0
                lastMotorTick = context.clock.ticks()
                lastAdvancedClockTick = Long.MIN_VALUE
                syncOpticalFilter()
                context.ports.find("mount", MountPort::class.java)?.restoreHardwareDetect()
            }
        }
    }

    private companion object {
        const val LIVE_VIEW_REF_WIDTH_PX = 1024
        const val IN_FOCUS_COC_MICRONS = 35.0
        const val MAX_JSON_DOF_FAR_M = 1e9
        const val NOMINAL_TICKS_PER_SECOND = 100_000_000.0
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")
        val TEMPLATE_SUMMARIES: List<Map<String, Any>> = LensTemplateCatalog.all.map { it.toSummaryMap() }

        /**
         * `ComponentLoader.validate(descriptor, supplied, partial = false)` fills every omitted
         * descriptor setting with its schema default before invoking [LensFactory.create] (and passes
         * that full map again during rollback). When a full-schema map is provided, strip keys still
         * equal to their descriptor default so `templateDefaults` from the selected modular lens /
         * adapter JAR are not clobbered back to `synthetic-research-lens` (or `electronicContacts=true`
         * on `MANUAL` dumb-adapter cold boots).
         */
        fun filterExplicitOverrides(baseDefaults: Map<String, Any>, raw: Map<String, Any>): Map<String, Any> {
            if (!raw.keys.containsAll(baseDefaults.keys)) return raw
            return raw.filter { (key, value) -> !defaultsEqual(baseDefaults[key], value) }
        }

        private fun defaultsEqual(defaultVal: Any?, actualVal: Any?): Boolean {
            if (defaultVal == actualVal) return true
            if (defaultVal is Number && actualVal is Number) {
                return defaultVal.toDouble() == actualVal.toDouble()
            }
            return false
        }

        fun opticalSpecToDefaultSettings(assembly: ResolvedLensAssembly): Map<String, Any> {
            val s = assembly.opticalSpec
            return linkedMapOf(
                "template" to assembly.templateId,
                "adapterId" to (assembly.adapterId ?: ""),
                "communicationMode" to "",
                "realFirmwareFile" to "",
                "model" to s.modelName,
                "focalLengthMm" to s.defaultFocalLengthMm,
                "referenceFocalLengthMm" to s.referenceFocalLengthMm,
                "aperture" to s.defaultAperture,
                "apertureRingMode" to (if (s.electronicContacts) "A" else "MANUAL"),
                "focusDistanceM" to s.defaultFocusDistanceM,
                "focusTargetDistanceM" to s.defaultFocusDistanceM,
                "transmission" to s.transmission,
                "focusBreathing" to s.focusBreathing,
                "distortionK1" to s.distortionK1,
                "distortionK2" to s.distortionK2,
                "caRedScale" to s.caRedScale,
                "caBlueScale" to s.caBlueScale,
                "longitudinalCaDefocusMm" to s.longitudinalCaDefocusMm,
                "wideOpenContrastDrop" to s.wideOpenContrastDrop,
                "vignettingCos4" to s.vignettingCos4,
                "vignettingMechanical" to s.vignettingMechanical,
                "imageCircleMm" to s.imageCircleMm,
                "electronicContacts" to s.electronicContacts,
                "oisEnabled" to s.oisEnabled,
                "focusMode" to s.defaultFocusMode,
                "focusMotorType" to s.focusMotorType,
                "focusSweepSeconds" to s.focusSweepSeconds,
                "flareStrength" to s.flareStrength,
                "flareTintR" to s.flareTintR,
                "flareTintG" to s.flareTintG,
                "flareTintB" to s.flareTintB,
                "apertureBladeCount" to s.apertureBladeCount.toLong(),
                "apertureBladeRoundness" to s.apertureBladeRoundness,
                "bokehCatsEye" to s.bokehCatsEye,
                "bokehOnionRing" to s.bokehOnionRing,
                "bokehRimHighlight" to s.bokehRimHighlight,
            )
        }

        fun exactInt(value: Any?, field: String): Int {
            val number: Long = when (value) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                is java.math.BigInteger -> {
                    require(value.bitLength() <= 63) { "$field out of Long range" }
                    value.toLong()
                }
                is java.math.BigDecimal -> try {
                    value.longValueExact()
                } catch (exception: ArithmeticException) {
                    throw IllegalArgumentException("$field requires exact integer", exception)
                }
                is Number -> {
                    val decimal = value.toDouble()
                    require(
                        decimal.isFinite() &&
                            decimal % 1.0 == 0.0 &&
                            decimal >= Long.MIN_VALUE.toDouble() &&
                            decimal < 9223372036854775808.0
                    ) { "$field requires exact integer" }
                    decimal.toLong()
                }
                else -> throw IllegalArgumentException("$field requires integer")
            }
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
