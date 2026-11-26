package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Strictly mutually exclusive communication/control modes for every modular lens and mount adapter.
 *
 * - [MANUAL]: Purely mechanical lens or dumb adapter with zero electronic contacts, `MF` only,
 *   zero `LENSCOM` peer, and zero firmware image.
 * - [REAL_FIRMWARE]: Driven strictly by an analyzed real lens/adapter firmware image (e.g. Fujifilm
 *   Type-4 `GFUP00##.DAT` or Fringer `.bin`). Missing or unverified firmware MUST fail fast — never
 *   silently downgrade to [GENERIC_SIMULATED] or [MANUAL].
 * - [GENERIC_SIMULATED]: Protocol-level `LENSCOM` responder synthesized from real-world optical and
 *   mechanical specifications when the lens/adapter's own MCU firmware has not been analyzed yet.
 */
enum class LensCommunicationMode {
    MANUAL,
    REAL_FIRMWARE,
    GENERIC_SIMULATED,
}

/**
 * DAT-backed GF `LENSCOM` 128-byte Identity Header (`cmd 0x014/0x015`) & 128 KiB Optical EEPROM
 * (`cmd 0x0C0`) metadata exported by an electronic [LensModule] or bridged [MountAdapterModule].
 */
data class LenscomIdentitySpec(
    val shortId: String,
    val modelAscii: String,
    val mountCode: String = "GL",
    val serialAscii: String = "0000000001",
    val firmwareMajorBcd: Int = 0x01,
    val firmwareMinorBcd: Int = 0x00,
    val hardwareMajorBcd: Int = 0x01,
    val hardwareMinorBcd: Int = 0x00,
    val protocolMajorBcd: Int = 0x01,
    val protocolMinorBcd: Int = 0x30,
    val capabilityFlags0: Int = 0x30,
    val capabilityFlags1: Int = 0x00,
    val isAdapter: Boolean = false,
    val isZoom: Boolean = false,
    val hasOis: Boolean = false,
    val hasApertureRing: Boolean = true,
    val minFocalMm: Double = 63.0,
    val maxFocalMm: Double = 63.0,
    val minAperture: Double = 2.8,
    val maxAperture: Double = 32.0,
    val focusStepMin: Int = 0,
    val focusStepMax: Int = 0x7FFF,
    val focusMotorType: String = "LM",
    val focusSweepSeconds: Double = 0.35,
) {
    init {
        require(shortId.isNotBlank() && shortId.length <= 7 && shortId.all { it in 'A'..'Z' || it in '0'..'9' }) {
            "shortId must be 1..7 uppercase ASCII alphanumerics, got '$shortId'"
        }
        require(mountCode.length == 2 && mountCode.all { it in 'A'..'Z' || it in '0'..'9' }) {
            "mountCode must be 2 ASCII alphanumerics, got '$mountCode'"
        }
        require(modelAscii.isNotBlank() && modelAscii.length <= 63) {
            "modelAscii must be 1..63 chars, got '$modelAscii'"
        }
        require(minFocalMm > 0.0 && maxFocalMm >= minFocalMm) {
            "Invalid focal range: $minFocalMm..$maxFocalMm"
        }
        require(minAperture > 0.0 && maxAperture >= minAperture) {
            "Invalid aperture range: $minAperture..$maxAperture"
        }
        require(focusStepMin in 0..focusStepMax && focusStepMax <= 0x7FFF) {
            "Invalid focus step range: $focusStepMin..$focusStepMax"
        }
        require(focusSweepSeconds.isFinite() && focusSweepSeconds >= 0.0) {
            "Invalid focusSweepSeconds: $focusSweepSeconds"
        }
    }
}

/**
 * Type-safe strictly mutually exclusive control contract for a lens or bridged adapter+lens pair.
 */
sealed interface LensControlContract {
    val mode: LensCommunicationMode

    /** Purely mechanical lens / dumb mount adapter: zero contacts, zero LENSCOM, zero firmware. */
    data object Manual : LensControlContract {
        override val mode: LensCommunicationMode = LensCommunicationMode.MANUAL
    }

    /** Analyzed real lens/adapter firmware image; fails fast if firmware image is absent/unverified. */
    data class RealFirmware(
        val firmwareFormat: String,
        val expectedFirmwareFile: String,
        val expectedSha256: String? = null,
        val identitySpec: LenscomIdentitySpec? = null,
    ) : LensControlContract {
        override val mode: LensCommunicationMode = LensCommunicationMode.REAL_FIRMWARE

        init {
            require(firmwareFormat in SUPPORTED_FORMATS) {
                "Unsupported real lens firmware format '$firmwareFormat' (expected one of $SUPPORTED_FORMATS)"
            }
            require(expectedFirmwareFile.isNotBlank() && !expectedFirmwareFile.contains("..")) {
                "expectedFirmwareFile must be a safe non-blank relative path, got '$expectedFirmwareFile'"
            }
            if (expectedSha256 != null) {
                require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
                    "expectedSha256 must be 64 lowercase hex characters"
                }
            }
        }

        companion object {
            const val FORMAT_FUJI_TYPE4_DAT = "fuji-lens-type4-dat"
            const val FORMAT_FRINGER_BIN = "fringer-adapter-bin"
            val SUPPORTED_FORMATS: Set<String> = setOf(FORMAT_FUJI_TYPE4_DAT, FORMAT_FRINGER_BIN)
        }
    }

    /** Generic protocol-level simulated LENSCOM responder when real MCU firmware is not yet analyzed. */
    data class GenericSimulated(
        val identitySpec: LenscomIdentitySpec,
    ) : LensControlContract {
        override val mode: LensCommunicationMode = LensCommunicationMode.GENERIC_SIMULATED
    }
}

/**
 * Real-world optical, mechanical, bokeh, flare, and focus-motor specification for a modular lens
 * or adapter+lens combination.
 */
data class LensOpticalSpec(
    val id: String,
    val displayName: String,
    val mountType: String,
    val modelName: String,
    val defaultFocalLengthMm: Double,
    val referenceFocalLengthMm: Double,
    val minFocalLengthMm: Double,
    val maxFocalLengthMm: Double,
    val defaultAperture: Double,
    val minAperture: Double,
    val maxAperture: Double,
    val minFocusDistanceM: Double,
    val teleMinFocusDistanceM: Double = minFocusDistanceM,
    val defaultFocusDistanceM: Double = 2.5,
    val transmission: Double = 0.92,
    val focusBreathing: Double = 0.0,
    val distortionK1: Double = 0.0,
    val distortionK2: Double = 0.0,
    val caRedScale: Double = 1.0,
    val caBlueScale: Double = 1.0,
    val longitudinalCaDefocusMm: Double = 0.0,
    val wideOpenContrastDrop: Double = 0.0,
    val vignettingCos4: Double = 0.0,
    val vignettingMechanical: Double = 0.0,
    val imageCircleMm: Double = 56.0,
    val electronicContacts: Boolean = true,
    val oisEnabled: Boolean = false,
    val defaultFocusMode: String = "AF-S",
    val focusMotorType: String = "LM",
    val focusSweepSeconds: Double = 0.35,
    val flareStrength: Double = 0.0,
    val flareTintR: Double = 1.0,
    val flareTintG: Double = 0.95,
    val flareTintB: Double = 0.88,
    val apertureBladeCount: Int = 9,
    val apertureBladeRoundness: Double = 0.85,
    val bokehCatsEye: Double = 0.0,
    val bokehOnionRing: Double = 0.0,
    val bokehRimHighlight: Double = 0.0,
    val opticalCharacterSummary: String = "",
)

/**
 * Optional specialty-optics hook for third-party or exotic [LensModule] implementations (SPI v2):
 * Tilt-Shift Scheimpflug tilted focal plane, Anamorphic squeeze + oval bokeh, Fisheye projection,
 * Petzval field curvature, Helios swirly bokeh, or Mirror central obstruction.
 *
 * All methods have zero-effect defaults so implementations override only what they need.
 * When [LensModule.opticalHook] returns `null`, the optical pipeline incurs zero overhead.
 */
interface LensOpticalHook {
    /**
     * Custom inverse geometric UV warp for specialty projections (Fisheye, Anamorphic, Shift).
     *
     * Inputs `(nx, ny)` are normalized sensor coordinates (divided by half-diagonal, after IBIS
     * shift/roll). Returning `true` writes the warped normalized coordinates `(ux, uy)` into
     * `outUv[0]` and `outUv[1]` and replaces the default Brown-Conrady radial/tangential warp;
     * returning `false` falls through to standard Brown-Conrady distortion.
     */
    fun warpUv(
        nx: Double,
        ny: Double,
        focalLengthMm: Double,
        focusDistanceM: Double,
        aperture: Double,
        outUv: DoubleArray,
    ): Boolean = false

    /**
     * Returns the effective focus distance (in meters) at normalized sensor coordinates `(nx, ny)`
     * relative to [baseFocusDistanceM] (e.g. Scheimpflug tilted focal plane or Petzval field curvature).
     */
    fun effectiveFocusDistanceAt(
        nx: Double,
        ny: Double,
        baseFocusDistanceM: Double,
    ): Double = baseFocusDistanceM

    /**
     * Returns a multiplicative entrance-pupil transmission weight in `[0.0, 1.0]` for normalized
     * pupil coordinates `(ux, uy)` at normalized sensor position `(nx, ny)` and f-number [aperture]
     * (e.g. Anamorphic oval bokeh, Helios swirly bokeh, or Mirror central obstruction).
     */
    fun pupilWeight(
        ux: Double,
        uy: Double,
        nx: Double,
        ny: Double,
        aperture: Double,
    ): Double = 1.0

    /**
     * Optional custom SPIR-V compute shader pass (Plugin SPI v1.0) executed in-place on Vulkan
     * `DEVICE_LOCAL` VRAM (typically at [CustomSpirvStage.POST_OPTICS_RGB]) for the active optical
     * state `(width, height, focusDistanceM, fNumber, focalLengthMm)`.
     *
     * When non-null, the optical pipeline stays on Vulkan GPU and dispatches this custom `.spv`
     * pass instead of forcing CPU fallback. If Vulkan is disabled or `.spv` compilation fails,
     * the pipeline falls back cleanly to the CPU [LensOpticalHook] methods above.
     */
    fun vulkanSpirvPass(
        width: Int,
        height: Int,
        focusDistanceM: Double,
        fNumber: Double,
        focalLengthMm: Double,
    ): CustomSpirvPassSpec? = null

    /** Stable cache key for O(1) optical cache invalidation when hook parameters change. */
    fun cacheKey(): Long = 0L
}

/**
 * Discoverable modular lens JAR SPI (`META-INF/services/com.github.nyabanban.fuji.emulator.api.LensModule`).
 * Drop any JAR implementing [LensModule] into `<home>/lenses/` for automatic runtime discovery.
 */
interface LensModule {
    val id: String
    val legacyTemplateIds: List<String> get() = emptyList()
    val displayName: String
    val mountType: String
    val opticalSpec: LensOpticalSpec
    val controlContract: LensControlContract

    /** Self-describing plugin metadata (SPI v2). */
    val metadata: PluginMetadata get() = PluginMetadata.DEFAULT

    /**
     * Dynamic optical specification evaluated at the current [focalLengthMm], [focusDistanceM],
     * and [aperture] (SPI v2) — enabling variable-aperture zooms and zoom-dependent distortion,
     * CA, and vignetting curves. Defaults to [opticalSpec].
     */
    fun opticalSpecAt(
        focalLengthMm: Double,
        focusDistanceM: Double,
        aperture: Double,
    ): LensOpticalSpec = opticalSpec

    /** Optional specialty-optics hook (Tilt-Shift, Anamorphic, Fisheye, Petzval, etc.). */
    fun opticalHook(): LensOpticalHook? = null

    /**
     * Optional custom 128 KiB (`131,072` bytes) GF Optical EEPROM image served over
     * `LENSCOM cmd 0x0C0`. Takes precedence after explicit `mount.eepromFile` and before
     * synthesized default EEPROM.
     */
    fun eepromBytes(): ByteArray? = null

    /** Validates strict mutual exclusion between [controlContract] and [opticalSpec]. */
    fun validateContract() {
        when (val c = controlContract) {
            is LensControlContract.Manual -> {
                require(!opticalSpec.electronicContacts) {
                    "MANUAL lens '$id' cannot declare electronicContacts=true"
                }
                require(opticalSpec.defaultFocusMode == "MF") {
                    "MANUAL lens '$id' must declare defaultFocusMode='MF', got '${opticalSpec.defaultFocusMode}'"
                }
                require(opticalSpec.focusMotorType == "MANUAL" && opticalSpec.focusSweepSeconds == 0.0) {
                    "MANUAL lens '$id' must declare focusMotorType='MANUAL' and focusSweepSeconds=0.0"
                }
            }
            is LensControlContract.RealFirmware -> {
                require(opticalSpec.electronicContacts) {
                    "REAL_FIRMWARE lens '$id' requires electronicContacts=true"
                }
                require(c.expectedFirmwareFile.isNotBlank()) {
                    "REAL_FIRMWARE lens '$id' requires non-blank expectedFirmwareFile"
                }
            }
            is LensControlContract.GenericSimulated -> {
                require(opticalSpec.electronicContacts) {
                    "GENERIC_SIMULATED lens '$id' requires electronicContacts=true"
                }
            }
        }
        eepromBytes()?.let { bytes ->
            require(bytes.size == LENS_EEPROM_BYTES) {
                "Lens '$id' custom eepromBytes() must be exactly $LENS_EEPROM_BYTES bytes (128 KiB), got ${bytes.size}"
            }
        }
    }

    companion object {
        const val LENS_EEPROM_BYTES: Int = 131_072
    }
}

/**
 * Discoverable modular mount adapter JAR SPI
 * (`META-INF/services/com.github.nyabanban.fuji.emulator.api.MountAdapterModule`).
 */
interface MountAdapterModule {
    val id: String
    val legacyTemplateIds: List<String> get() = emptyList()
    val displayName: String
    val bodyMount: String get() = "GF"
    val lensMount: String
    val communicationMode: LensCommunicationMode
    val electronicContacts: Boolean
    val defaultLensId: String

    /** Self-describing plugin metadata (SPI v2). */
    val metadata: PluginMetadata get() = PluginMetadata.DEFAULT

    fun bridgeOpticalSpec(lens: LensModule): LensOpticalSpec
    fun bridgeControlContract(lens: LensModule): LensControlContract

    /** Dynamic bridged optical spec at current [focalLengthMm], [focusDistanceM], and [aperture] (SPI v2). */
    fun bridgeOpticalSpecAt(
        lens: LensModule,
        focalLengthMm: Double,
        focusDistanceM: Double,
        aperture: Double,
    ): LensOpticalSpec {
        val dynamic = lens.opticalSpecAt(focalLengthMm, focusDistanceM, aperture)
        val staticBase = lens.opticalSpec
        val bridged = bridgeOpticalSpec(lens)
        if (dynamic == staticBase) return bridged
        return bridged.copy(
            minAperture = dynamic.minAperture,
            maxAperture = dynamic.maxAperture,
            minFocusDistanceM = dynamic.minFocusDistanceM,
            teleMinFocusDistanceM = dynamic.teleMinFocusDistanceM,
            transmission = dynamic.transmission,
            focusBreathing = dynamic.focusBreathing,
            distortionK1 = dynamic.distortionK1,
            distortionK2 = dynamic.distortionK2,
            caRedScale = dynamic.caRedScale,
            caBlueScale = dynamic.caBlueScale,
            longitudinalCaDefocusMm = dynamic.longitudinalCaDefocusMm,
            wideOpenContrastDrop = dynamic.wideOpenContrastDrop,
            vignettingCos4 = dynamic.vignettingCos4,
            vignettingMechanical = dynamic.vignettingMechanical,
            flareStrength = dynamic.flareStrength,
            flareTintR = dynamic.flareTintR,
            flareTintG = dynamic.flareTintG,
            flareTintB = dynamic.flareTintB,
            apertureBladeCount = dynamic.apertureBladeCount,
            apertureBladeRoundness = dynamic.apertureBladeRoundness,
            bokehCatsEye = dynamic.bokehCatsEye,
            bokehOnionRing = dynamic.bokehOnionRing,
            bokehRimHighlight = dynamic.bokehRimHighlight,
        )
    }

    /** Optional bridged specialty-optics hook (SPI v2). Defaults to delegating to [lens]. */
    fun bridgeOpticalHook(lens: LensModule): LensOpticalHook? = lens.opticalHook()

    /** Optional custom 128 KiB Optical EEPROM image for this adapter + lens assembly (SPI v2). */
    fun bridgeEepromBytes(lens: LensModule): ByteArray? = lens.eepromBytes()

    /** Validates strict mutual exclusion between [communicationMode] and [electronicContacts]. */
    fun validateContract() {
        when (communicationMode) {
            LensCommunicationMode.MANUAL -> require(!electronicContacts) {
                "MANUAL adapter '$id' cannot declare electronicContacts=true"
            }
            LensCommunicationMode.REAL_FIRMWARE,
            LensCommunicationMode.GENERIC_SIMULATED,
            -> require(electronicContacts) {
                "$communicationMode adapter '$id' requires electronicContacts=true"
            }
        }
    }
}

/**
 * Decoded real lens/adapter firmware image returned by [FujiLensDatDecoder] / [RealFirmwareImageLoader].
 */
data class DecodedLensFirmwareImage(
    val format: String,
    val sourcePath: Path,
    val headerVersion: Int,
    val modelTag: String,
    val firmwareVersionMajor: Int,
    val firmwareVersionMinor: Int,
    val payloadSha256: String,
    val payload: ByteArray,
)

/**
 * Clean fail-fast decoder for Fujifilm Type-4 `GFUP00##.DAT` (`~byte` bit-inverted) lens update
 * packages and Fringer adapter `.bin` firmware files placed under `firmware-data/lenses/`.
 *
 * Never fabricates or downgrades missing/corrupt images.
 */
object FujiLensDatDecoder {
    private const val FUJI_TYPE4_MAGIC = 4
    private const val FUJI_TYPE4_HEADER_BYTES = 0x274
    private const val MAX_FIRMWARE_BYTES = 16 * 1024 * 1024

    fun decodeType4Dat(bytes: ByteArray, sourcePath: Path, expectedSha256: String? = null): DecodedLensFirmwareImage {
        require(bytes.size > FUJI_TYPE4_HEADER_BYTES && bytes.size <= MAX_FIRMWARE_BYTES) {
            "Invalid Fujifilm Type-4 lens DAT size (${bytes.size} B) at $sourcePath"
        }
        val hdr = ByteBuffer.wrap(bytes, 0, FUJI_TYPE4_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val typeMagic = hdr.getInt(0)
        require(typeMagic == FUJI_TYPE4_MAGIC) {
            "Expected Fujifilm Type-4 lens DAT magic $FUJI_TYPE4_MAGIC at $sourcePath, got $typeMagic"
        }
        val modelBytes = ByteArray(16)
        bytes.copyInto(modelBytes, 0, 0x04, 0x14)
        val modelTag = modelBytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII).trim()
        val major = hdr.getInt(0x1C) and 0xFFFF
        val minor = hdr.getInt(0x20) and 0xFFFF
        val payloadLen = bytes.size - FUJI_TYPE4_HEADER_BYTES
        val payload = ByteArray(payloadLen) { i -> (bytes[FUJI_TYPE4_HEADER_BYTES + i].toInt() xor 0xFF).toByte() }
        val sha = sha256Hex(payload)
        if (expectedSha256 != null) {
            require(sha == expectedSha256) {
                "Lens firmware SHA-256 mismatch at $sourcePath (expected $expectedSha256, got $sha)"
            }
        }
        return DecodedLensFirmwareImage(
            format = LensControlContract.RealFirmware.FORMAT_FUJI_TYPE4_DAT,
            sourcePath = sourcePath,
            headerVersion = typeMagic,
            modelTag = modelTag,
            firmwareVersionMajor = major,
            firmwareVersionMinor = minor,
            payloadSha256 = sha,
            payload = payload,
        )
    }

    fun decodeAdapterBin(bytes: ByteArray, sourcePath: Path, expectedSha256: String? = null): DecodedLensFirmwareImage {
        require(bytes.size in 256..MAX_FIRMWARE_BYTES) {
            "Invalid adapter firmware binary size (${bytes.size} B) at $sourcePath"
        }
        val sha = sha256Hex(bytes)
        if (expectedSha256 != null) {
            require(sha == expectedSha256) {
                "Adapter firmware SHA-256 mismatch at $sourcePath (expected $expectedSha256, got $sha)"
            }
        }
        return DecodedLensFirmwareImage(
            format = LensControlContract.RealFirmware.FORMAT_FRINGER_BIN,
            sourcePath = sourcePath,
            headerVersion = 1,
            modelTag = sourcePath.fileName.toString(),
            firmwareVersionMajor = 1,
            firmwareVersionMinor = 0,
            payloadSha256 = sha,
            payload = bytes.copyOf(),
        )
    }

    /**
     * Loads and strictly validates a [LensControlContract.RealFirmware] artifact from [firmwareRoot].
     * Throws [IllegalArgumentException] if absent, symlinked, or invalid.
     */
    fun loadVerified(contract: LensControlContract.RealFirmware, firmwareRoot: Path): DecodedLensFirmwareImage {
        val root = firmwareRoot.toAbsolutePath().normalize()
        val target = root.resolve(contract.expectedFirmwareFile).toAbsolutePath().normalize()
        require(target.startsWith(root) && target != root) {
            "Real lens firmware path escapes firmware root: ${contract.expectedFirmwareFile}"
        }
        require(!Files.isSymbolicLink(target) && Files.isRegularFile(target, NOFOLLOW_LINKS)) {
            "REAL_FIRMWARE mode requires analyzed firmware image at $target (refusing silent fallback to GENERIC_SIMULATED or MANUAL)"
        }
        val raw = Files.readAllBytes(target)
        return when (contract.firmwareFormat) {
            LensControlContract.RealFirmware.FORMAT_FUJI_TYPE4_DAT ->
                decodeType4Dat(raw, target, contract.expectedSha256)
            LensControlContract.RealFirmware.FORMAT_FRINGER_BIN ->
                decodeAdapterBin(raw, target, contract.expectedSha256)
            else -> throw IllegalArgumentException("Unsupported real firmware format: ${contract.firmwareFormat}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/**
 * Optional modular-lens runtime port exposed by `components/lens` so `components/mount` can
 * naturally bring up any modular GF lens or bridged adapter JAR through `LENSCOM` without direct
 * JAR coupling.
 */
interface ModularLensPort {
    val activeLensId: String
    val activeAdapterId: String?
    val communicationMode: LensCommunicationMode
    val controlContract: LensControlContract
    val lenscomIdentity: LenscomIdentitySpec?
    val realFirmwarePayload: ByteArray?
    val focusMotorBusy: Boolean
    val focusEncoderPosition: Int
    /**
     * Physical GF aperture ring position:
     * - `"A"`: Auto aperture (body P/S AE controls iris via LENSCOM `cmd 0x061/0x062`)
     * - `"C"`: Command Dial (`cmd 0x020` bits `[11:10] == 2` -> `KEY_M_IRIS_C 0x4020`; body dial drives iris)
     * - `"MANUAL"`: Manual F-stop click-stop on lens aperture ring (`cmd 0x030 = 1 + apertureRingStepIndex`)
     */
    val apertureRingMode: String get() = "A"
    /** 0-based 1/3-stop iris blade index into EEPROM `fnoTable` (`0..nIris-1`), reported by `cmd 0x022`. */
    val irisStepIndex: Int get() = 0
    /** 0-based 1/3-stop physical aperture ring click-stop index (`0..nIris-1`), reported by `cmd 0x030`. */
    val apertureRingStepIndex: Int get() = irisStepIndex
    fun commandFocusEncoder(targetEncoder: Int)
    fun commandFocusEncoder(targetEncoder: Int, speedPulsesPerSec: Int) {
        commandFocusEncoder(targetEncoder)
    }
    fun commandApertureThirdStopIndex(avThirds: Int)

    /**
     * Optional custom 128 KiB (`131,072` B) Optical EEPROM image provided by the active modular
     * lens or mount adapter (SPI v2). Consumed by `MountFactory` (`LENSCOM cmd 0x0C0`) when no
     * explicit `mount.eepromFile` is configured.
     */
    val customEepromBytes: ByteArray? get() = null

    /** Rescans external lens & adapter plugin JARs at runtime and returns a telemetry summary map (SPI v2). */
    fun rescanExternalPlugins(): Map<String, Any?> = emptyMap()
}
