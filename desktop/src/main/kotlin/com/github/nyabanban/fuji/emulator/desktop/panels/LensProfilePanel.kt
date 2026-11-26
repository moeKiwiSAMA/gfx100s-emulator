package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.api.PluginMetadata
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.animation.AnimationTimer
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.Slider
import javafx.scene.control.TextField
import javafx.scene.control.TitledPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.FlowPane
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.StringConverter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * UI-side selectable optical compute backend (`auto` | `vulkan` | `cpu`).
 */
data class OpticsBackendOption(
    val id: String,
    val labelKey: String,
) {
    val displayName: String get() = I18n.t(labelKey)

    companion object {
        val ALL: List<OpticsBackendOption> = listOf(
            OpticsBackendOption("auto", "lens.gpu.backend.auto"),
            OpticsBackendOption("vulkan", "lens.gpu.backend.vulkan"),
            OpticsBackendOption("cpu", "lens.gpu.backend.cpu"),
        )

        fun find(id: String?): OpticsBackendOption {
            val norm = id?.trim()?.lowercase(Locale.ROOT)
            return ALL.firstOrNull { it.id == norm } ?: ALL.first()
        }
    }
}

/**
 * UI-side selectable Vulkan physical GPU option (`auto` or an enumerated physical device).
 */
data class VulkanDeviceOption(
    val spec: String,
    val index: Int,
    val name: String,
    val deviceType: String,
    val vendorId: Int = 0,
    val deviceId: Int = 0,
    val supportsExternalMemory: Boolean = false,
) {
    val isAuto: Boolean get() = spec.equals("auto", ignoreCase = true) || index < 0

    fun displayLabel(): String {
        if (isAuto) return I18n.t("lens.gpu.device.auto")
        val typeLabel = I18n.t(
            when (deviceType.uppercase(Locale.ROOT)) {
                "DISCRETE_GPU" -> "lens.gpu.type.discrete"
                "INTEGRATED_GPU" -> "lens.gpu.type.integrated"
                "VIRTUAL_GPU" -> "lens.gpu.type.virtual"
                "CPU_SOFTWARE" -> "lens.gpu.type.cpu"
                else -> "lens.gpu.type.other"
            },
        )
        val extSuffix = if (supportsExternalMemory) " \u00B7 ${I18n.t("lens.gpu.extmem")}" else ""
        return "[$index] $name ($typeLabel$extSuffix)"
    }

    companion object {
        val AUTO = VulkanDeviceOption(
            spec = "auto",
            index = -1,
            name = "auto",
            deviceType = "AUTO",
        )
    }
}

/**
 * UI-side modular mount adapter selector option aligned with `lenses/adapter-*.jar`.
 */
data class LensAdapterOption(
    val id: String,
    val displayNameKey: String,
    val communicationMode: String,
    val customDisplayName: String = "",
    val metadata: PluginMetadata = PluginMetadata.DEFAULT,
) {
    val displayName: String
        get() = if (customDisplayName.isNotBlank()) {
            metadata.localizedName(customDisplayName, I18n.localeCode())
        } else {
            I18n.t(displayNameKey)
        }

    companion object {
        val BUILTIN_ADAPTERS: List<LensAdapterOption> = listOf(
            LensAdapterOption("", "lens.adapter.none", "GENERIC_SIMULATED"),
            LensAdapterOption("adapter-fringer-ef-gfx", "lens.adapter.fringer_ef", "GENERIC_SIMULATED"),
            LensAdapterOption("adapter-m42-gfx", "lens.adapter.m42", "MANUAL"),
            LensAdapterOption("adapter-hasselblad-v-gfx", "lens.adapter.hasselblad_v", "MANUAL"),
        )

        fun find(id: String?): LensAdapterOption =
            BUILTIN_ADAPTERS.firstOrNull { it.id.equals(id.orEmpty(), ignoreCase = true) }
                ?: BUILTIN_ADAPTERS.first()
    }
}

/**
 * UI-side lens template specification aligned with Track O1 (`LensTemplateCatalog`) and modular
 * `lenses/` JAR physical optical profiles.
 */
data class LensTemplateSpec(
    val id: String,
    val displayName: String,
    val mountType: String,
    val minFocalMm: Double,
    val maxFocalMm: Double,
    val defaultFocalMm: Double,
    val minAperture: Double,
    val maxAperture: Double,
    val defaultAperture: Double,
    val minFocusM: Double,
    val electronicContacts: Boolean,
    val oisSupported: Boolean,
    val imageCircleMm: Double,
    val teleMinFocusM: Double = minFocusM,
    val defaultAdapterId: String = "",
    val defaultCommunicationMode: String = if (electronicContacts) "GENERIC_SIMULATED" else "MANUAL",
    val focusMotorType: String = if (electronicContacts) "LM" else "MANUAL",
    val focusSweepSeconds: Double = if (electronicContacts) 0.35 else 0.0,
    val apertureBladeCount: Int = 9,
    val opticalCharacterSummaryKey: String = "",
) {
    val isZoom: Boolean get() = maxFocalMm - minFocalMm > 0.05
    val opticalCharacterSummary: String
        get() = if (opticalCharacterSummaryKey.isNotEmpty()) I18n.t(opticalCharacterSummaryKey) else ""

    companion object {
        const val GFX_SENSOR_WIDTH_MM: Double = 43.8
        const val GFX_SENSOR_HEIGHT_MM: Double = 32.9
        const val GFX_CROP_FACTOR_TO_35MM: Double = 0.79
        const val GFX_COC_LIMIT_MM: Double = 0.035

        val BUILTIN_TEMPLATES: List<LensTemplateSpec> = listOf(
            LensTemplateSpec(
                id = "gf45-100",
                displayName = "GF 45-100mm F4 R LM OIS WR",
                mountType = "GF Native",
                minFocalMm = 45.0,
                maxFocalMm = 100.0,
                defaultFocalMm = 45.0,
                minAperture = 4.0,
                maxAperture = 32.0,
                defaultAperture = 4.0,
                minFocusM = 0.65,
                teleMinFocusM = 0.82,
                electronicContacts = true,
                oisSupported = true,
                imageCircleMm = 55.0,
                focusMotorType = "LM",
                focusSweepSeconds = 0.35,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf45_100",
            ),
            LensTemplateSpec(
                id = "gf20-35",
                displayName = "GF 20-35mm F4 R WR",
                mountType = "GF Native",
                minFocalMm = 20.0,
                maxFocalMm = 35.0,
                defaultFocalMm = 20.0,
                minAperture = 4.0,
                maxAperture = 22.0,
                defaultAperture = 4.0,
                minFocusM = 0.35,
                electronicContacts = true,
                oisSupported = false,
                imageCircleMm = 55.0,
                focusMotorType = "STM",
                focusSweepSeconds = 0.30,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf20_35",
            ),
            LensTemplateSpec(
                id = "gf32-64",
                displayName = "GF 32-64mm F4 R LM WR",
                mountType = "GF Native",
                minFocalMm = 32.0,
                maxFocalMm = 64.0,
                defaultFocalMm = 32.0,
                minAperture = 4.0,
                maxAperture = 32.0,
                defaultAperture = 4.0,
                minFocusM = 0.50,
                teleMinFocusM = 0.60,
                electronicContacts = true,
                oisSupported = false,
                imageCircleMm = 55.0,
                focusMotorType = "LM",
                focusSweepSeconds = 0.32,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf32_64",
            ),
            LensTemplateSpec(
                id = "gf63",
                displayName = "GF 63mm F2.8 R WR",
                mountType = "GF Native",
                minFocalMm = 63.0,
                maxFocalMm = 63.0,
                defaultFocalMm = 63.0,
                minAperture = 2.8,
                maxAperture = 32.0,
                defaultAperture = 2.8,
                minFocusM = 0.50,
                electronicContacts = true,
                oisSupported = false,
                imageCircleMm = 55.0,
                focusMotorType = "DC_CORELESS",
                focusSweepSeconds = 0.78,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf63",
            ),
            LensTemplateSpec(
                id = "gf110",
                displayName = "GF 110mm F2 R LM WR",
                mountType = "GF Native",
                minFocalMm = 110.0,
                maxFocalMm = 110.0,
                defaultFocalMm = 110.0,
                minAperture = 2.0,
                maxAperture = 22.0,
                defaultAperture = 2.0,
                minFocusM = 0.90,
                electronicContacts = true,
                oisSupported = false,
                imageCircleMm = 55.0,
                focusMotorType = "LM_FAST",
                focusSweepSeconds = 0.28,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf110",
            ),
            LensTemplateSpec(
                id = "gf50",
                displayName = "GF 50mm F3.5 R LM WR",
                mountType = "GF Native",
                minFocalMm = 50.0,
                maxFocalMm = 50.0,
                defaultFocalMm = 50.0,
                minAperture = 3.5,
                maxAperture = 32.0,
                defaultAperture = 3.5,
                minFocusM = 0.55,
                electronicContacts = true,
                oisSupported = false,
                imageCircleMm = 55.0,
                focusMotorType = "LM_FAST",
                focusSweepSeconds = 0.22,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.gf50",
            ),
            LensTemplateSpec(
                id = "adapter-ef-gfx",
                displayName = "Fringer EF->GFX Pro Adapter (EF 85mm f/1.4L IS USM)",
                mountType = "Electronic Adapter (EF->GF)",
                minFocalMm = 85.0,
                maxFocalMm = 85.0,
                defaultFocalMm = 85.0,
                minAperture = 1.4,
                maxAperture = 22.0,
                defaultAperture = 1.4,
                minFocusM = 0.85,
                electronicContacts = true,
                oisSupported = true,
                imageCircleMm = 43.3,
                defaultAdapterId = "adapter-fringer-ef-gfx",
                defaultCommunicationMode = "GENERIC_SIMULATED",
                focusMotorType = "USM",
                focusSweepSeconds = 0.42,
                apertureBladeCount = 9,
                opticalCharacterSummaryKey = "lens.char.adapter_ef_gfx",
            ),
            LensTemplateSpec(
                id = "adapter-m42-dumb",
                displayName = "M42 Mechanical Adapter (Super-Takumar 50mm f/1.4)",
                mountType = "Mechanical Adapter (Manual)",
                minFocalMm = 50.0,
                maxFocalMm = 50.0,
                defaultFocalMm = 50.0,
                minAperture = 1.4,
                maxAperture = 16.0,
                defaultAperture = 1.4,
                minFocusM = 0.45,
                electronicContacts = false,
                oisSupported = false,
                imageCircleMm = 44.5,
                defaultAdapterId = "adapter-m42-gfx",
                defaultCommunicationMode = "MANUAL",
                focusMotorType = "MANUAL",
                focusSweepSeconds = 0.0,
                apertureBladeCount = 8,
                opticalCharacterSummaryKey = "lens.char.adapter_m42",
            ),
            LensTemplateSpec(
                id = "adapter-hasselblad-v",
                displayName = "Hasselblad V 6x6 Adapter (Zeiss Planar 80mm f/2.8)",
                mountType = "Mechanical Adapter (6x6->GF)",
                minFocalMm = 80.0,
                maxFocalMm = 80.0,
                defaultFocalMm = 80.0,
                minAperture = 2.8,
                maxAperture = 22.0,
                defaultAperture = 2.8,
                minFocusM = 0.90,
                electronicContacts = false,
                oisSupported = false,
                imageCircleMm = 79.0,
                defaultAdapterId = "adapter-hasselblad-v-gfx",
                defaultCommunicationMode = "MANUAL",
                focusMotorType = "MANUAL",
                focusSweepSeconds = 0.0,
                apertureBladeCount = 5,
                opticalCharacterSummaryKey = "lens.char.adapter_hasselblad_v",
            ),
            LensTemplateSpec(
                id = "custom",
                displayName = "Custom Optical Profile",
                mountType = "Custom",
                minFocalMm = 14.0,
                maxFocalMm = 400.0,
                defaultFocalMm = 63.0,
                minAperture = 1.0,
                maxAperture = 32.0,
                defaultAperture = 2.8,
                minFocusM = 0.20,
                electronicContacts = true,
                oisSupported = true,
                imageCircleMm = 55.0,
                opticalCharacterSummaryKey = "lens.char.custom",
            ),
        )

        private val BY_ID: Map<String, LensTemplateSpec> = BUILTIN_TEMPLATES.associateBy { it.id }

        fun find(id: String?): LensTemplateSpec =
            id?.lowercase(Locale.ROOT)?.let { BY_ID[it] } ?: BY_ID.getValue("custom")

        fun isKnownTemplate(id: String): Boolean = id.lowercase(Locale.ROOT) in BY_ID
    }
}

/**
 * Pure immutable snapshot of `lens` + `mount` component telemetry for [LensProfilePanel] and unit tests.
 */
data class LensProfileState(
    val template: String,
    val templateDisplayName: String,
    val mountType: String,
    val mountAttached: Boolean,
    val mountProtocol: String,
    val opticsEnabled: Boolean,
    val electronicContacts: Boolean,
    val oisEnabled: Boolean,
    val noiseEnabled: Boolean,
    val focusMode: String,
    val focalLengthMm: Double,
    val minFocalLengthMm: Double,
    val maxFocalLengthMm: Double,
    val aperture: Double,
    val minAperture: Double,
    val maxAperture: Double,
    val focusDistanceM: Double,
    val minFocusDistanceM: Double,
    val subjectDistanceM: Double,
    val afTrigger: Long,
    val distortionK1: Double,
    val distortionK2: Double,
    val caRedScale: Double,
    val caBlueScale: Double,
    val vignettingCos4: Double,
    val vignettingMechanical: Double,
    val focusBreathing: Double,
    val transmission: Double,
    val imageCircleMm: Double,
    val ibisShiftXPx: Double,
    val ibisShiftYPx: Double,
    val ibisRollRad: Double,
    val equivalent35mmFocalMm: Double,
    val horizontalFovDeg: Double,
    val cocMicrons: Double,
    val cocPixels: Double,
    val inFocus: Boolean,
    val dofNearM: Double,
    val dofFarM: Double,
    val framesFiltered: Long,
    val adapterId: String = "",
    val communicationMode: String = if (electronicContacts) "GENERIC_SIMULATED" else "MANUAL",
    val loadedFromJar: Boolean = false,
    val realFirmwareFile: String = "",
    val realFirmwareVerifiedSha256: String = "",
    val longitudinalCaDefocusMm: Double = 0.0,
    val wideOpenContrastDrop: Double = 0.0,
    val focusMotorType: String = if (electronicContacts) "LM" else "MANUAL",
    val focusSweepSeconds: Double = if (electronicContacts) 0.35 else 0.0,
    val focusMotorInstant: Boolean = false,
    val focusMotorActive: Boolean = false,
    val focusMotorProgress: Double = 1.0,
    val flareStrength: Double = 0.0,
    val flareTintR: Double = 1.0,
    val flareTintG: Double = 0.95,
    val flareTintB: Double = 0.88,
    val apertureBladeCount: Int = 9,
    val apertureBladeRoundness: Double = 0.85,
    val bokehCatsEye: Double = 0.0,
    val bokehOnionRing: Double = 0.0,
    val bokehRimHighlight: Double = 0.0,
    val hyperfocalM: Double = 0.0,
    val opticalCharacterSummary: String = "",
    val apertureRingMode: String = if (electronicContacts) "A" else "MANUAL",
    val bodyIrisSynced: Boolean = false,
    val bodyCommandedAperture: Double? = null,
    val sceneIs3d: Boolean = false,
    val rescanPlugins: Long = 0L,
    val discoveredExternalLenses: List<LensTemplateSpec> = emptyList(),
    val discoveredExternalAdapters: List<LensAdapterOption> = emptyList(),
    val activeLensMetadata: PluginMetadata = PluginMetadata.DEFAULT,
    val activeAdapterMetadata: PluginMetadata? = null,
    val opticsBackend: String = "auto",
    val vulkanDevice: String = "auto",
    val activeOpticsBackend: String = "CPU",
    val activeVulkanDeviceIndex: Int = -1,
    val activeVulkanDeviceName: String = "",
    val activeVulkanDeviceType: String = "CPU_FALLBACK",
    val activeVulkanExtMem: Boolean = false,
    val vulkanFallbackReason: String = "",
    val availableVulkanDevices: List<VulkanDeviceOption> = emptyList(),
    val focusTargetDistanceM: Double = focusDistanceM,
) {
    fun telemetrySummaryLines(): List<String> {
        val dofFarStr = if (!dofFarM.isFinite() || dofFarM >= 500.0) "\u221E" else String.format(Locale.ROOT, "%.2fm", dofFarM)
        val dofNearStr = String.format(Locale.ROOT, "%.2fm", dofNearM.coerceAtLeast(0.0))
        val hyperStr = if (!hyperfocalM.isFinite() || hyperfocalM <= 0.0 || hyperfocalM >= 500.0) "\u221E" else String.format(Locale.ROOT, "%.1fm", hyperfocalM)
        val focusBadge = when {
            focusMotorActive -> I18n.t("lens.telemetry.motor_active", focusMotorProgress * 100.0)
            inFocus -> I18n.t("lens.telemetry.in_focus")
            else -> I18n.t("lens.telemetry.defocus")
        }
        val modeBadge = when (communicationMode) {
            "MANUAL" -> I18n.t("lens.telemetry.mode.manual")
            "REAL_FIRMWARE" -> if (realFirmwareVerifiedSha256.isNotEmpty()) {
                I18n.t("lens.telemetry.mode.real_verified", realFirmwareVerifiedSha256.take(8))
            } else {
                I18n.t("lens.telemetry.mode.real")
            }
            else -> I18n.t("lens.telemetry.mode.simulated")
        }
        val mountStr = if (!mountAttached) {
            I18n.t("lens.telemetry.mount.detached")
        } else if (electronicContacts) {
            I18n.t("lens.telemetry.mount.electronic", mountProtocol)
        } else {
            I18n.t("lens.telemetry.mount.mechanical")
        }
        val ringTag = I18n.t(
            when (apertureRingMode) {
                "A" -> "lens.telemetry.ring.auto"
                "C" -> "lens.telemetry.ring.command"
                else -> "lens.telemetry.ring.manual"
            },
        )
        val syncTag = I18n.t(
            when {
                bodyIrisSynced -> "lens.telemetry.sync.synced"
                electronicContacts -> "lens.telemetry.sync.waiting"
                else -> "lens.telemetry.sync.manual"
            },
        )
        val jarTag = I18n.t(if (loadedFromJar) "lens.telemetry.source.jar" else "lens.telemetry.source.builtin")
        val motorStr = if (focusMotorType == "MANUAL") {
            I18n.t("lens.telemetry.motor.manual")
        } else {
            String.format(Locale.ROOT, "%s (%.2fs)", focusMotorType, focusSweepSeconds)
        }
        val effectiveCharSummary = activeLensMetadata.localizedDescription(I18n.localeCode())
            .ifBlank { LensTemplateSpec.find(template).opticalCharacterSummary }
            .ifBlank { opticalCharacterSummary }
        val traitLine = buildString {
            append(I18n.t("lens.telemetry.trait", apertureBladeCount, ringTag, syncTag, motorStr, modeBadge, jarTag))
            if (effectiveCharSummary.isNotBlank()) {
                append(" \u00B7 ")
                append(effectiveCharSummary)
            }
        }
        return listOf(
            I18n.t("lens.telemetry.line1", equivalent35mmFocalMm, horizontalFovDeg, hyperStr),
            I18n.t("lens.telemetry.line2", cocMicrons, cocPixels, focusBadge),
            I18n.t("lens.telemetry.line3", dofNearStr, dofFarStr, framesFiltered),
            I18n.t("lens.telemetry.line4", mountStr, imageCircleMm),
            traitLine,
        )
    }

    companion object {
        val FOCUS_MODES: List<String> = listOf("AF-S", "AF-C", "MF")
        val APERTURE_RING_MODES: List<String> = listOf("A", "C", "MANUAL")
        val COMMUNICATION_MODES: List<String> = listOf("GENERIC_SIMULATED", "MANUAL", "REAL_FIRMWARE")
        private val STANDARD_THIRD_STOPS: DoubleArray = doubleArrayOf(
            1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0,
            4.5, 5.0, 5.6, 6.4, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0,
            18.0, 20.0, 22.0, 25.0, 29.0, 32.0,
        )

        /** Snaps a slider f-number to the nearest photographic 1/3-stop within `[minAperture, maxAperture]`. */
        fun snapApertureThirdStop(value: Double, minAperture: Double, maxAperture: Double): Double {
            val lo = min(minAperture, maxAperture)
            val hi = max(minAperture, maxAperture)
            val candidates = STANDARD_THIRD_STOPS.filter { it in (lo - 0.02)..(hi + 0.02) }
            if (candidates.isEmpty()) return value.coerceIn(lo, hi)
            return candidates.minByOrNull { kotlin.math.abs(it - value) } ?: value.coerceIn(lo, hi)
        }

        fun buildApertureRingPatch(mode: String, manualAperture: Double? = null): Map<String, Any> {
            val normalized = mode.trim().uppercase(Locale.ROOT)
            require(normalized in APERTURE_RING_MODES) { I18n.t("lens.error.ring_mode", mode) }
            val out = linkedMapOf<String, Any>("apertureRingMode" to normalized)
            if (normalized == "MANUAL" && manualAperture != null && manualAperture.isFinite()) {
                out["aperture"] = manualAperture
            }
            return out
        }

        /** Maps focus distance in meters to a logarithmic slider coordinate `[0.0, 1.0]`. */
        fun focusDistanceToSlider(distanceM: Double, minFocusM: Double, maxFiniteM: Double = 50.0): Double {
            val lo = minFocusM.coerceAtLeast(0.05)
            val hi = max(maxFiniteM, lo * 2.0)
            if (!distanceM.isFinite() || distanceM >= hi * 1.5) return 1.0
            val clamped = distanceM.coerceIn(lo, hi)
            return (ln(clamped / lo) / ln(hi / lo)).coerceIn(0.0, 0.995)
        }

        /** Maps logarithmic slider coordinate `[0.0, 1.0]` back to focus distance in meters (`>= 0.995` -> `1000.0m` / infinity). */
        fun sliderToFocusDistance(sliderPos: Double, minFocusM: Double, maxFiniteM: Double = 50.0): Double {
            val lo = minFocusM.coerceAtLeast(0.05)
            val hi = max(maxFiniteM, lo * 2.0)
            if (sliderPos >= 0.995) return 1000.0
            val t = sliderPos.coerceIn(0.0, 0.994)
            return lo * exp(t * ln(hi / lo))
        }

        fun formatDistance(meters: Double): String = when {
            !meters.isFinite() || meters >= 500.0 -> "\u221E"
            meters < 1.0 -> String.format(Locale.ROOT, "%.2f m", meters)
            meters < 10.0 -> String.format(Locale.ROOT, "%.2f m", meters)
            else -> String.format(Locale.ROOT, "%.1f m", meters)
        }

        /** Maps focus distance in meters to normalized vergence/diopter motor coordinate `[0.0=inf .. 1.0=MFD]`. */
        fun distanceToMotorPosition(distanceM: Double, minFocusM: Double): Double {
            val safeMfd = minFocusM.coerceAtLeast(0.01)
            if (!distanceM.isFinite() || distanceM >= 500.0) return 0.0
            return (safeMfd / distanceM.coerceAtLeast(safeMfd)).coerceIn(0.0, 1.0)
        }

        /** Inverse of [distanceToMotorPosition]: maps `[0.0=inf .. 1.0=MFD]` back to meters. */
        fun motorPositionToDistance(pos: Double, minFocusM: Double): Double {
            val safeMfd = minFocusM.coerceAtLeast(0.01)
            val p = pos.coerceIn(0.0, 1.0)
            return if (p <= 1e-4) 1000.0 else (safeMfd / p).coerceIn(safeMfd, 1e9)
        }

        /** Returns true when panel focus edits should follow the lens's physical motor velocity rather than snapping instantly. */
        fun shouldAnimateFocusMotor(state: LensProfileState): Boolean =
            !state.focusMotorInstant &&
                state.focusSweepSeconds > 1e-4 &&
                !state.focusMotorType.equals("MANUAL", ignoreCase = true)

        /** Builds a live focus-target patch so motorized lenses slew at their physical `focusSweepSeconds` velocity. */
        fun buildFocusTargetPatch(distanceM: Double, minFocusM: Double): Map<String, Any> = linkedMapOf(
            "focusTargetDistanceM" to distanceM.coerceIn(minFocusM.coerceAtLeast(0.01), 1e9),
        )

        /**
         * Advances a focus distance by [dtSeconds] in vergence/diopter space at the lens's
         * [sweepSeconds] full-stroke velocity (matching `LensFactory.advanceFocusMotor` 1:1).
         * Returns `(nextDistanceM, arrived)`.
         */
        fun stepMotorDistance(
            currentDistanceM: Double,
            targetDistanceM: Double,
            minFocusM: Double,
            sweepSeconds: Double,
            dtSeconds: Double,
        ): Pair<Double, Boolean> {
            val safeMfd = minFocusM.coerceAtLeast(0.01)
            val clampedTarget = targetDistanceM.coerceIn(safeMfd, 1e9)
            if (sweepSeconds <= 1e-4) return clampedTarget to true
            val curPos = distanceToMotorPosition(currentDistanceM, safeMfd)
            val dstPos = distanceToMotorPosition(clampedTarget, safeMfd)
            val remaining = dstPos - curPos
            if (abs(remaining) <= 1e-5) return clampedTarget to true
            if (dtSeconds <= 0.0) return currentDistanceM.coerceIn(safeMfd, 1e9) to false
            val maxDelta = dtSeconds / sweepSeconds
            if (abs(remaining) <= maxDelta) return clampedTarget to true
            val nextPos = (curPos + if (remaining > 0.0) maxDelta else -maxDelta).coerceIn(0.0, 1.0)
            return motorPositionToDistance(nextPos, safeMfd) to false
        }

        /** Formats the focus readout label, displaying `"current → target"` while the physical motor is slewing. */
        fun formatFocusDisplay(
            currentDistanceM: Double,
            targetDistanceM: Double?,
            minFocusM: Double,
            motorMoving: Boolean,
        ): String {
            val curText = formatDistance(currentDistanceM)
            if (!motorMoving || targetDistanceM == null) return curText
            val curPos = distanceToMotorPosition(currentDistanceM, minFocusM)
            val tgtPos = distanceToMotorPosition(targetDistanceM, minFocusM)
            if (abs(curPos - tgtPos) <= 1e-4) return curText
            return "$curText \u2192 ${formatDistance(targetDistanceM)}"
        }

        fun buildTemplatePatch(templateId: String): Map<String, Any> = linkedMapOf(
            "template" to templateId,
            "opticsEnabled" to true,
        )

        fun buildAdapterPatch(adapterId: String): Map<String, Any> = linkedMapOf(
            "adapterId" to adapterId,
            "opticsEnabled" to true,
        )

        /**
         * Builds a strictly mutually exclusive communication-mode patch (`MANUAL` | `REAL_FIRMWARE` |
         * `GENERIC_SIMULATED`). Never silently downgrades `REAL_FIRMWARE` when `realFirmwareFile` is blank.
         */
        fun buildCommunicationModePatch(mode: String, realFirmwareFile: String = ""): Result<Map<String, Any>> {
            val normalized = mode.trim().uppercase(Locale.ROOT)
            val fw = realFirmwareFile.trim()
            return when (normalized) {
                "MANUAL" -> Result.success(
                    linkedMapOf(
                        "communicationMode" to "MANUAL",
                        "realFirmwareFile" to "",
                        "electronicContacts" to false,
                        "focusMode" to "MF",
                    ),
                )
                "REAL_FIRMWARE" -> {
                    if (fw.isEmpty()) {
                        Result.failure(IllegalArgumentException(I18n.t("lens.error.real_fw_required")))
                    } else {
                        Result.success(
                            linkedMapOf(
                                "communicationMode" to "REAL_FIRMWARE",
                                "realFirmwareFile" to fw,
                                "electronicContacts" to true,
                            ),
                        )
                    }
                }
                "GENERIC_SIMULATED" -> Result.success(
                    linkedMapOf(
                        "communicationMode" to "GENERIC_SIMULATED",
                        "realFirmwareFile" to "",
                        "electronicContacts" to true,
                    ),
                )
                else -> Result.failure(IllegalArgumentException(I18n.t("lens.error.comm_mode", mode)))
            }
        }

        fun buildAfTriggerPatch(currentAfTrigger: Long): Map<String, Any> = linkedMapOf(
            "afTrigger" to (currentAfTrigger + 1L),
        )

        fun buildRescanPluginsPatch(currentRescanPlugins: Long): Map<String, Any> = linkedMapOf(
            "rescanPlugins" to (currentRescanPlugins + 1L),
        )

        fun buildOpticsBackendPatch(backend: String): Map<String, Any> {
            val normalized = backend.trim().lowercase(Locale.ROOT)
            require(normalized in setOf("auto", "vulkan", "cpu")) { "Unsupported opticsBackend: $backend" }
            return linkedMapOf("opticsBackend" to normalized)
        }

        fun buildVulkanDevicePatch(deviceSpec: String, backendOverride: String? = null): Map<String, Any> {
            val spec = deviceSpec.trim().ifEmpty { "auto" }
            val out = linkedMapOf<String, Any>("vulkanDevice" to spec)
            if (backendOverride != null) {
                val normalized = backendOverride.trim().lowercase(Locale.ROOT)
                require(normalized in setOf("auto", "vulkan", "cpu")) { "Unsupported opticsBackend: $backendOverride" }
                out["opticsBackend"] = normalized
            }
            return out
        }

        fun buildAdvancedPatch(fields: Map<String, String>): Result<Map<String, Any>> {
            val decimalSpecs = listOf(
                Triple("distortionK1", -1.0, 1.0),
                Triple("distortionK2", -1.0, 1.0),
                Triple("caRedScale", 0.8, 1.2),
                Triple("caBlueScale", 0.8, 1.2),
                Triple("longitudinalCaDefocusMm", -25.0, 25.0),
                Triple("wideOpenContrastDrop", 0.0, 0.6),
                Triple("vignettingCos4", 0.0, 8.0),
                Triple("vignettingMechanical", 0.0, 1.0),
                Triple("focusBreathing", -0.5, 0.5),
                Triple("transmission", 0.0, 1.0),
                Triple("imageCircleMm", 20.0, 120.0),
                Triple("focusSweepSeconds", 0.0, 5.0),
                Triple("flareStrength", 0.0, 1.0),
                Triple("flareTintR", 0.0, 2.0),
                Triple("flareTintG", 0.0, 2.0),
                Triple("flareTintB", 0.0, 2.0),
                Triple("apertureBladeRoundness", 0.0, 1.0),
                Triple("bokehCatsEye", 0.0, 1.0),
                Triple("bokehOnionRing", 0.0, 0.5),
                Triple("bokehRimHighlight", 0.0, 0.6),
                Triple("ibisShiftXPx", -256.0, 256.0),
                Triple("ibisShiftYPx", -256.0, 256.0),
                Triple("ibisRollRad", -3.1416, 3.1416),
            )
            val out = LinkedHashMap<String, Any>()
            for ((key, minVal, maxVal) in decimalSpecs) {
                val raw = fields[key]?.trim() ?: continue
                if (raw.isEmpty()) continue
                val parsed = raw.toDoubleOrNull()
                    ?: return Result.failure(IllegalArgumentException(I18n.t("lens.error.must_be_number", key, raw)))
                if (!parsed.isFinite() || parsed !in minVal..maxVal) {
                    return Result.failure(IllegalArgumentException(I18n.t("lens.error.out_of_range", key, minVal, maxVal, parsed)))
                }
                out[key] = parsed
            }
            val bladeRaw = fields["apertureBladeCount"]?.trim()
            if (!bladeRaw.isNullOrEmpty()) {
                val parsed = bladeRaw.toLongOrNull()
                    ?: return Result.failure(IllegalArgumentException(I18n.t("lens.error.blades_int", bladeRaw)))
                if (parsed !in 5L..11L) {
                    return Result.failure(IllegalArgumentException(I18n.t("lens.error.blades_range", parsed)))
                }
                out["apertureBladeCount"] = parsed
            }
            return Result.success(out)
        }

        fun fromMaps(
            lensConfig: Map<String, Any?>?,
            lensState: Map<String, Any?>?,
            mountConfig: Map<String, Any?>?,
            mountState: Map<String, Any?>?,
            cmosConfig: Map<String, Any?>? = null,
            cmosState: Map<String, Any?>? = null,
        ): LensProfileState {
            fun get(key: String): Any? = lensState?.get(key) ?: lensConfig?.get(key)
            fun dbl(key: String, default: Double): Double = (get(key) as? Number)?.toDouble() ?: default
            fun lng(key: String, default: Long): Long = (get(key) as? Number)?.toLong() ?: default
            fun bool(key: String, default: Boolean): Boolean = (get(key) as? Boolean) ?: default
            fun str(key: String, default: String): String = (get(key) as? String)?.takeIf { it.isNotBlank() } ?: default

            val templateId = str("template", "custom")
            val spec = LensTemplateSpec.find(templateId)

            val focal = dbl("focalLengthMm", spec.defaultFocalMm)
            val minFocal = dbl("minFocalLengthMm", spec.minFocalMm)
            val maxFocal = max(minFocal, dbl("maxFocalLengthMm", spec.maxFocalMm))
            val aperture = dbl("aperture", spec.defaultAperture)
            val minAp = dbl("minAperture", spec.minAperture)
            val maxAp = max(minAp, dbl("maxAperture", spec.maxAperture))
            val minFocus = dbl("minFocusDistanceM", spec.minFocusM).coerceAtLeast(0.05)
            val focusM = dbl("focusDistanceM", 2.5).coerceAtLeast(minFocus)
            val focusTargetM = dbl("focusTargetDistanceM", focusM).coerceAtLeast(minFocus)
            val subjectM = dbl("subjectDistanceM", 2.5).coerceAtLeast(0.05)

            val mountAttached = (mountState?.get("attached") as? Boolean)
                ?: (mountConfig?.get("attached") as? Boolean)
                ?: true
            val mountProtocol = (mountState?.get("contactProtocol") as? String)
                ?: (mountConfig?.get("protocolProfile") as? String)
                ?: "g-mount"

            val eq35 = dbl("equivalent35mmFocalMm", focal * LensTemplateSpec.GFX_CROP_FACTOR_TO_35MM)
            val fovDeg = dbl(
                "horizontalFovDeg",
                Math.toDegrees(2.0 * atan(LensTemplateSpec.GFX_SENSOR_WIDTH_MM / (2.0 * focal.coerceAtLeast(1.0)))),
            )
            val fM = focal / 1000.0
            val hyperfocal = (fM * fM) / (aperture.coerceAtLeast(0.5) * (LensTemplateSpec.GFX_COC_LIMIT_MM / 1000.0)) + fM
            val calcCocMm = if (subjectM > fM && focusM > fM) {
                val denom = subjectM * (focusM - fM)
                if (denom > 1e-9) {
                    kotlin.math.abs(subjectM - focusM) * (fM * fM) / (aperture.coerceAtLeast(0.5) * denom) * 1000.0
                } else 0.0
            } else 0.0
            val cocMicrons = dbl("cocMicrons", calcCocMm * 1000.0)
            val cocPixels = dbl("cocPixels", calcCocMm / (LensTemplateSpec.GFX_SENSOR_WIDTH_MM / 1024.0))
            val inFocus = (get("inFocus") as? Boolean) ?: (cocPixels <= 1.25)
            val calcNear = (hyperfocal * focusM) / (hyperfocal + (focusM - fM)).coerceAtLeast(1e-6)
            val calcFar = if (focusM >= hyperfocal) Double.POSITIVE_INFINITY
            else (hyperfocal * focusM) / (hyperfocal - (focusM - fM)).coerceAtLeast(1e-6)

            val electronicContacts = bool("electronicContacts", spec.electronicContacts)
            val adapterId = (lensState?.get("activeAdapterId") as? String)
                ?: (get("adapterId") as? String)
                ?: spec.defaultAdapterId
            val commMode = (lensState?.get("communicationMode") as? String)?.takeIf { it.isNotBlank() }
                ?: (mountState?.get("communicationMode") as? String)?.takeIf { it.isNotBlank() && it != "DETACHED" }
                ?: (lensConfig?.get("communicationMode") as? String)?.takeIf { it.isNotBlank() }
                ?: if (electronicContacts) spec.defaultCommunicationMode else "MANUAL"
            val ringMode = if (!electronicContacts || commMode == "MANUAL") {
                "MANUAL"
            } else {
                str("apertureRingMode", "A").uppercase(Locale.ROOT).let { if (it in APERTURE_RING_MODES) it else "A" }
            }
            val bodySynced = bool("bodyIrisSynced", false)
            val bodyCmdAperture = (get("bodyCommandedAperture") as? Number)?.toDouble()
            val cmosKind = (cmosState?.get("sourceKind") as? String)
                ?: (cmosConfig?.get("sourceKind") as? String)
                ?: ""
            val cmosHasDepth = (cmosState?.get("hasDepthBuffer") as? Boolean) == true
            val sceneIs3d = cmosHasDepth || cmosKind.equals("optical-scene", ignoreCase = true)
            val builtinLensIds = LensTemplateSpec.BUILTIN_TEMPLATES.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) }
            val externalLenses = (lensState?.get("discoveredLensModules") as? List<*>)?.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (id.lowercase(Locale.ROOT) in builtinLensIds) return@mapNotNull null
                val name = (m["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: id
                val mount = (m["mountType"] as? String)?.takeIf { it.isNotBlank() } ?: "GF"
                val minF = (m["minFocalLengthMm"] as? Number)?.toDouble() ?: 50.0
                val maxF = (m["maxFocalLengthMm"] as? Number)?.toDouble() ?: minF
                val minA = (m["minAperture"] as? Number)?.toDouble() ?: 2.8
                val maxA = (m["maxAperture"] as? Number)?.toDouble() ?: 22.0
                val mfd = (m["minFocusDistanceM"] as? Number)?.toDouble() ?: 0.5
                val contacts = (m["electronicContacts"] as? Boolean) ?: true
                val comm = (m["communicationMode"] as? String) ?: if (contacts) "GENERIC_SIMULATED" else "MANUAL"
                val motor = (m["focusMotorType"] as? String) ?: if (contacts) "LM" else "MANUAL"
                val sweep = (m["focusSweepSeconds"] as? Number)?.toDouble() ?: 0.35
                LensTemplateSpec(
                    id = id,
                    displayName = name,
                    mountType = mount,
                    minFocalMm = minF,
                    maxFocalMm = maxF,
                    defaultFocalMm = minF,
                    minAperture = minA,
                    maxAperture = maxA,
                    defaultAperture = minA,
                    minFocusM = mfd,
                    electronicContacts = contacts,
                    oisSupported = false,
                    imageCircleMm = 55.0,
                    defaultCommunicationMode = comm,
                    focusMotorType = motor,
                    focusSweepSeconds = sweep,
                )
            }.orEmpty()
            val builtinAdapterIds = LensAdapterOption.BUILTIN_ADAPTERS.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) }
            val externalAdapters = (lensState?.get("discoveredAdapterModules") as? List<*>)?.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                if (id.lowercase(Locale.ROOT) in builtinAdapterIds) return@mapNotNull null
                val name = (m["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: id
                val comm = (m["communicationMode"] as? String) ?: "GENERIC_SIMULATED"
                val meta = PluginMetadata.fromStateMap(m["metadata"] as? Map<*, *>)
                LensAdapterOption(id = id, displayNameKey = "", communicationMode = comm, customDisplayName = name, metadata = meta)
            }.orEmpty()
            val activeLensMeta = PluginMetadata.fromStateMap(lensState?.get("activeLensMetadata") as? Map<*, *>)
            val activeAdapterMeta = (lensState?.get("activeAdapterMetadata") as? Map<*, *>)?.let { PluginMetadata.fromStateMap(it) }

            val diagMap = lensState?.get("opticsBackendDiagnostics") as? Map<*, *>
            val activeDevMap = lensState?.get("activeVulkanDevice") as? Map<*, *>
            val opticsBackendCfg = str("opticsBackend", "auto").lowercase(Locale.ROOT)
            val vulkanDeviceCfg = (get("vulkanDevice") as? String)?.trim()?.ifEmpty { "auto" } ?: "auto"
            val activeBackendName = (activeDevMap?.get("backend") as? String)
                ?: (diagMap?.get("backend") as? String)
                ?: "CPU"
            val activeVkIdx = (activeDevMap?.get("index") as? Number)?.toInt()
                ?: (diagMap?.get("deviceIndex") as? Number)?.toInt()
                ?: -1
            val activeVkName = (activeDevMap?.get("name") as? String)
                ?: (diagMap?.get("deviceName") as? String)
                ?: ""
            val activeVkType = (activeDevMap?.get("deviceType") as? String)
                ?: (diagMap?.get("deviceType") as? String)
                ?: "CPU_FALLBACK"
            val activeVkExtMem = (activeDevMap?.get("supportsExternalMemory") as? Boolean)
                ?: (diagMap?.get("externalMemorySupported") as? Boolean)
                ?: false
            val fallbackReason = (activeDevMap?.get("fallbackReason") as? String)
                ?: (diagMap?.get("fallbackReason") as? String)
                ?: ""
            val availVkDevices = (lensState?.get("availableVulkanDevices") as? List<*>)?.mapNotNull { entry ->
                val m = entry as? Map<*, *> ?: return@mapNotNull null
                val idx = (m["index"] as? Number)?.toInt() ?: return@mapNotNull null
                val name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val specStr = (m["spec"] as? String)?.takeIf { it.isNotBlank() } ?: "$idx:$name"
                val dtype = (m["deviceType"] as? String)?.takeIf { it.isNotBlank() } ?: "OTHER"
                val vid = (m["vendorId"] as? Number)?.toInt() ?: 0
                val did = (m["deviceId"] as? Number)?.toInt() ?: 0
                val extMem = (m["supportsExternalMemory"] as? Boolean) ?: false
                VulkanDeviceOption(
                    spec = specStr,
                    index = idx,
                    name = name,
                    deviceType = dtype,
                    vendorId = vid,
                    deviceId = did,
                    supportsExternalMemory = extMem,
                )
            }.orEmpty()

            return LensProfileState(
                template = templateId,
                templateDisplayName = str("templateDisplayName", spec.displayName),
                mountType = str("mountType", spec.mountType),
                mountAttached = mountAttached,
                mountProtocol = mountProtocol,
                opticsEnabled = bool("opticsEnabled", false),
                electronicContacts = electronicContacts,
                oisEnabled = bool("oisEnabled", spec.oisSupported),
                noiseEnabled = bool("noiseEnabled", false),
                focusMode = str("focusMode", if (electronicContacts) "AF-S" else "MF"),
                focalLengthMm = focal,
                minFocalLengthMm = minFocal,
                maxFocalLengthMm = maxFocal,
                aperture = aperture,
                minAperture = minAp,
                maxAperture = maxAp,
                focusDistanceM = focusM,
                minFocusDistanceM = minFocus,
                subjectDistanceM = subjectM,
                afTrigger = lng("afTrigger", 0L),
                distortionK1 = dbl("distortionK1", 0.0),
                distortionK2 = dbl("distortionK2", 0.0),
                caRedScale = dbl("caRedScale", 1.0),
                caBlueScale = dbl("caBlueScale", 1.0),
                vignettingCos4 = dbl("vignettingCos4", 0.0),
                vignettingMechanical = dbl("vignettingMechanical", 0.0),
                focusBreathing = dbl("focusBreathing", 0.0),
                transmission = dbl("transmission", 0.9),
                imageCircleMm = dbl("imageCircleMm", spec.imageCircleMm),
                ibisShiftXPx = dbl("ibisShiftXPx", 0.0),
                ibisShiftYPx = dbl("ibisShiftYPx", 0.0),
                ibisRollRad = dbl("ibisRollRad", 0.0),
                equivalent35mmFocalMm = eq35,
                horizontalFovDeg = fovDeg,
                cocMicrons = cocMicrons,
                cocPixels = cocPixels,
                inFocus = inFocus,
                dofNearM = dbl("dofNearM", calcNear),
                dofFarM = dbl("dofFarM", calcFar),
                framesFiltered = lng("framesFiltered", 0L),
                adapterId = adapterId,
                communicationMode = commMode,
                loadedFromJar = bool("loadedFromJar", false),
                realFirmwareFile = (get("realFirmwareFile") as? String).orEmpty(),
                realFirmwareVerifiedSha256 = (lensState?.get("realFirmwareVerifiedSha256") as? String).orEmpty(),
                longitudinalCaDefocusMm = dbl("longitudinalCaDefocusMm", 0.0),
                wideOpenContrastDrop = dbl("wideOpenContrastDrop", 0.0),
                focusMotorType = str("focusMotorType", spec.focusMotorType),
                focusSweepSeconds = dbl("focusSweepSeconds", spec.focusSweepSeconds),
                focusMotorInstant = bool("focusMotorInstant", false),
                focusMotorActive = bool("focusMotorActive", false),
                focusMotorProgress = dbl("focusMotorProgress", 1.0),
                flareStrength = dbl("flareStrength", 0.0),
                flareTintR = dbl("flareTintR", 1.0),
                flareTintG = dbl("flareTintG", 0.95),
                flareTintB = dbl("flareTintB", 0.88),
                apertureBladeCount = lng("apertureBladeCount", spec.apertureBladeCount.toLong()).toInt().coerceIn(5, 11),
                apertureBladeRoundness = dbl("apertureBladeRoundness", 0.85),
                bokehCatsEye = dbl("bokehCatsEye", 0.0),
                bokehOnionRing = dbl("bokehOnionRing", 0.0),
                bokehRimHighlight = dbl("bokehRimHighlight", 0.0),
                hyperfocalM = dbl("hyperfocalM", hyperfocal),
                opticalCharacterSummary = (lensState?.get("opticalCharacterSummary") as? String)?.takeIf { it.isNotBlank() }.orEmpty(),
                apertureRingMode = ringMode,
                bodyIrisSynced = bodySynced,
                bodyCommandedAperture = bodyCmdAperture,
                sceneIs3d = sceneIs3d,
                rescanPlugins = lng("rescanPlugins", 0L),
                discoveredExternalLenses = externalLenses,
                discoveredExternalAdapters = externalAdapters,
                activeLensMetadata = activeLensMeta,
                activeAdapterMetadata = activeAdapterMeta,
                opticsBackend = opticsBackendCfg,
                vulkanDevice = vulkanDeviceCfg,
                activeOpticsBackend = activeBackendName,
                activeVulkanDeviceIndex = activeVkIdx,
                activeVulkanDeviceName = activeVkName,
                activeVulkanDeviceType = activeVkType,
                activeVulkanExtMem = activeVkExtMem,
                vulkanFallbackReason = fallbackReason,
                availableVulkanDevices = availVkDevices,
                focusTargetDistanceM = focusTargetM,
            )
        }
    }
}

/**
 * Pure motor-following state tracker for the focus slider and readout label.
 * Smoothly animates between snapshot ticks at the lens's physical `focusSweepSeconds` velocity
 * while continuously reconciling with authoritative emulator snapshots (for both panel edits and AF).
 */
internal class FocusSliderMotorTracker {
    var active: Boolean = false
        private set
    var displayedDistanceM: Double = 2.5
        private set
    var targetDistanceM: Double = 2.5
        private set
    private var awaitingBackendAck: Boolean = false
    private var awaitingAckDeadlineNanos: Long = 0L

    fun onUserCommitTarget(targetM: Double, state: LensProfileState, nowNanos: Long): Double {
        val mfd = state.minFocusDistanceM.coerceAtLeast(0.01)
        val clampedTarget = targetM.coerceIn(mfd, 1e9)
        if (!LensProfileState.shouldAnimateFocusMotor(state)) {
            active = false
            awaitingBackendAck = false
            displayedDistanceM = clampedTarget
            targetDistanceM = clampedTarget
            return displayedDistanceM
        }
        val startDist = (if (active) displayedDistanceM else state.focusDistanceM).coerceIn(mfd, 1e9)
        val curPos = LensProfileState.distanceToMotorPosition(startDist, mfd)
        val dstPos = LensProfileState.distanceToMotorPosition(clampedTarget, mfd)
        if (abs(dstPos - curPos) <= 1e-5) {
            active = false
            awaitingBackendAck = false
            displayedDistanceM = clampedTarget
            targetDistanceM = clampedTarget
            return displayedDistanceM
        }
        displayedDistanceM = startDist
        targetDistanceM = clampedTarget
        active = true
        awaitingBackendAck = true
        awaitingAckDeadlineNanos = nowNanos + ACK_TIMEOUT_NANOS
        return displayedDistanceM
    }

    fun onAnimationTick(dtSeconds: Double, state: LensProfileState, nowNanos: Long): Boolean {
        if (!active) return false
        if (!LensProfileState.shouldAnimateFocusMotor(state)) {
            displayedDistanceM = targetDistanceM
            active = false
            awaitingBackendAck = false
            return false
        }
        val clampedDt = dtSeconds.coerceIn(0.0, 0.1)
        val (nextDist, arrived) = LensProfileState.stepMotorDistance(
            currentDistanceM = displayedDistanceM,
            targetDistanceM = targetDistanceM,
            minFocusM = state.minFocusDistanceM,
            sweepSeconds = state.focusSweepSeconds,
            dtSeconds = clampedDt,
        )
        displayedDistanceM = nextDist
        if (arrived && !state.focusMotorActive && (!awaitingBackendAck || nowNanos >= awaitingAckDeadlineNanos)) {
            active = false
            awaitingBackendAck = false
        }
        return active
    }

    fun onObservedState(state: LensProfileState, userDragging: Boolean, nowNanos: Long) {
        val mfd = state.minFocusDistanceM.coerceAtLeast(0.01)
        if (userDragging) return
        if (state.focusMotorActive && LensProfileState.shouldAnimateFocusMotor(state)) {
            awaitingBackendAck = false
            targetDistanceM = state.focusTargetDistanceM.coerceIn(mfd, 1e9)
            val backendDist = state.focusDistanceM.coerceIn(mfd, 1e9)
            if (!active) {
                displayedDistanceM = backendDist
                active = true
            } else {
                val backendPos = LensProfileState.distanceToMotorPosition(backendDist, mfd)
                val dispPos = LensProfileState.distanceToMotorPosition(displayedDistanceM, mfd)
                val tgtPos = LensProfileState.distanceToMotorPosition(targetDistanceM, mfd)
                val dir = when {
                    tgtPos > backendPos + 1e-6 -> 1.0
                    tgtPos < backendPos - 1e-6 -> -1.0
                    else -> 0.0
                }
                val lead = (dispPos - backendPos) * dir
                displayedDistanceM = when {
                    dir == 0.0 || lead <= 0.0 -> backendDist
                    lead > 0.08 -> backendDist
                    else -> {
                        val blendedPos = (backendPos + (dispPos - backendPos) * 0.35).coerceIn(0.0, 1.0)
                        LensProfileState.motorPositionToDistance(blendedPos, mfd)
                    }
                }
            }
            return
        }

        val backendDist = state.focusDistanceM.coerceIn(mfd, 1e9)
        if (awaitingBackendAck) {
            val backendPos = LensProfileState.distanceToMotorPosition(backendDist, mfd)
            val tgtPos = LensProfileState.distanceToMotorPosition(targetDistanceM, mfd)
            if (abs(backendPos - tgtPos) <= 1e-3 || nowNanos >= awaitingAckDeadlineNanos) {
                awaitingBackendAck = false
                active = false
                displayedDistanceM = backendDist
                targetDistanceM = backendDist
            }
            return
        }

        active = false
        displayedDistanceM = backendDist
        targetDistanceM = backendDist
    }

    fun formattedLabel(state: LensProfileState): String = LensProfileState.formatFocusDisplay(
        currentDistanceM = displayedDistanceM,
        targetDistanceM = if (active) targetDistanceM else null,
        minFocusM = state.minFocusDistanceM,
        motorMoving = active,
    )

    fun reset(distanceM: Double) {
        active = false
        awaitingBackendAck = false
        displayedDistanceM = distanceM
        targetDistanceM = distanceM
    }

    private companion object {
        const val ACK_TIMEOUT_NANOS = 1_500_000_000L
    }
}

/**
 * Interactive JavaFX Lens Profile & Focusing Panel used both inside the main Lens & Optics tab
 * and inside the pop-out floating [LensProfileWindow].
 */
class LensProfilePanel(
    private val patchComponent: (String, Map<String, Any>) -> CompletableFuture<*>,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
    private val onGraphicsChanged: (String?, String?) -> Unit = { _, _ -> },
) {
    val root: VBox = VBox(8.0).apply {
        padding = Insets(8.0)
        styleClass += "camera-studio"
    }

    private var syncing = false
    private var observedOnce = false
    private var lastState: LensProfileState = LensProfileState.fromMaps(null, null, null, null)
    private val focusMotorTracker = FocusSliderMotorTracker()
    private var focusSliderCommittedFromDrag = false
    private var focusAnimRunning = false
    private var focusAnimLastNanos = 0L
    private val focusMotorAnimTimer = object : AnimationTimer() {
        override fun handle(now: Long) {
            if (!focusAnimRunning || root.scene == null) {
                stopFocusMotorAnimation()
                return
            }
            if (focusSlider.isValueChanging) {
                focusAnimLastNanos = now
                return
            }
            val dtSec = if (focusAnimLastNanos > 0L && now > focusAnimLastNanos) {
                (now - focusAnimLastNanos) * 1e-9
            } else {
                0.0
            }
            focusAnimLastNanos = now
            val stillMoving = focusMotorTracker.onAnimationTick(dtSec, lastState, now)
            syncing = true
            try {
                updateFocusSliderVisuals()
            } finally {
                syncing = false
            }
            if (!stillMoving) {
                stopFocusMotorAnimation()
            }
        }
    }

    private val templateCombo = ComboBox<LensTemplateSpec>().apply {
        id = "lens-template-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(LensTemplateSpec.BUILTIN_TEMPLATES)
        converter = object : StringConverter<LensTemplateSpec>() {
            override fun toString(item: LensTemplateSpec?): String =
                if (item == null) "" else "${item.id} \u00B7 ${item.displayName}"
            override fun fromString(string: String?): LensTemplateSpec =
                LensTemplateSpec.find(string?.substringBefore(" \u00B7 "))
        }
        selectionModel.selectFirst()
    }
    private val adapterCombo = ComboBox<LensAdapterOption>().apply {
        id = "lens-adapter-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(LensAdapterOption.BUILTIN_ADAPTERS)
        converter = object : StringConverter<LensAdapterOption>() {
            override fun toString(item: LensAdapterOption?): String = item?.displayName.orEmpty()
            override fun fromString(string: String?): LensAdapterOption =
                LensAdapterOption.BUILTIN_ADAPTERS.firstOrNull { it.displayName == string }
                    ?: LensAdapterOption.BUILTIN_ADAPTERS.first()
        }
        setCellFactory {
            object : ListCell<LensAdapterOption>() {
                override fun updateItem(item: LensAdapterOption?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayName
                }
            }
        }
        selectionModel.selectFirst()
    }
    private val communicationModeCombo = ComboBox<String>().apply {
        id = "lens-comm-mode-combo"
        minWidth = 0.0
        items.setAll(LensProfileState.COMMUNICATION_MODES)
        value = "GENERIC_SIMULATED"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.comm.tooltip")) }
    }
    private val communicationModeBadge = Label("GENERIC_SIMULATED").apply { styleClass += "studio-brand-badge" }
    private val realFirmwareField = TextField().apply {
        id = "lens-real-firmware-file"
        promptTextProperty().bind(I18n.bind("lens.comm.firmware.prompt"))
        maxWidth = Double.MAX_VALUE
        isDisable = true
    }
    private val applyModeButton = Button().apply {
        id = "lens-apply-mode-btn"
        textProperty().bind(I18n.bind("lens.comm.apply"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.comm.apply.tooltip")) }
    }
    private val rescanPluginsButton = Button().apply {
        id = "lens-rescan-plugins-btn"
        styleClass += "studio-header-btn-secondary"
        textProperty().bind(I18n.bind("lens.rescan"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.rescan.tooltip")) }
    }
    private val mountTypeBadge = Label("GF Native").apply { styleClass += "studio-brand-badge" }

    private val mountAttachedCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.mount.attached")) }
    private val opticsEnabledCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.optics.enable")) }
    private val electronicContactsCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.contacts.enable")) }
    private val oisEnabledCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.ois.enable")) }
    private val motorSlewCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.motor.slew")) }
    private val noiseEnabledCheck = CheckBox().apply { textProperty().bind(I18n.bind("lens.noise.enable")) }
    private val focusModeCombo = ComboBox<String>().apply {
        id = "lens-focus-mode"
        items.setAll(LensProfileState.FOCUS_MODES)
        value = "AF-S"
    }
    private val focusSyncBadge = Label(I18n.t("lens.focus.sync.body")).apply { styleClass += "studio-telemetry-val" }

    private val ringAutoBtn = Button().apply {
        id = "lens-ring-a"
        textProperty().bind(I18n.bind("lens.aperture.ring.auto"))
        styleClass += "studio-chip-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.aperture.ring.auto.tooltip")) }
    }
    private val ringCmdBtn = Button().apply {
        id = "lens-ring-c"
        textProperty().bind(I18n.bind("lens.aperture.ring.command"))
        styleClass += "studio-chip-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.aperture.ring.command.tooltip")) }
    }
    private val ringManualBtn = Button().apply {
        id = "lens-ring-manual"
        textProperty().bind(I18n.bind("lens.aperture.ring.manual"))
        styleClass += "studio-chip-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.aperture.ring.manual.tooltip")) }
    }
    private val apertureSyncBadge = Label(I18n.t("lens.sync.waiting")).apply { styleClass += "studio-brand-badge" }

    private val focalValueLabel = Label("63.0 mm").apply { styleClass += "studio-telemetry-val" }
    private val focalSlider = Slider(14.0, 200.0, 63.0).apply {
        id = "lens-focal-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val apertureValueLabel = Label("f/4.0").apply { styleClass += "studio-telemetry-val" }
    private val apertureSlider = Slider(1.4, 32.0, 4.0).apply {
        id = "lens-aperture-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val focusValueLabel = Label("2.50 m").apply { styleClass += "studio-telemetry-val" }
    private val focusSlider = Slider(0.0, 1.0, 0.45).apply {
        id = "lens-focus-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val subjectValueLabel = Label("2.50 m").apply { styleClass += "studio-telemetry-val" }
    private val subjectSlider = Slider(0.0, 1.0, 0.45).apply {
        id = "lens-subject-slider"
        maxWidth = Double.MAX_VALUE
    }
    private val subjectRowContainer = VBox(2.0)

    private val afButton = Button().apply {
        id = "lens-af-button"
        textProperty().bind(I18n.bind("lens.focus.af.button"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("lens.focus.af.tooltip")) }
    }

    private val opticsBackendCombo = ComboBox<OpticsBackendOption>().apply {
        id = "lens-optics-backend-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(OpticsBackendOption.ALL)
        converter = object : StringConverter<OpticsBackendOption>() {
            override fun toString(item: OpticsBackendOption?): String = item?.displayName.orEmpty()
            override fun fromString(string: String?): OpticsBackendOption =
                OpticsBackendOption.ALL.firstOrNull { it.displayName == string } ?: OpticsBackendOption.ALL.first()
        }
        setCellFactory {
            object : ListCell<OpticsBackendOption>() {
                override fun updateItem(item: OpticsBackendOption?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayName
                }
            }
        }
        selectionModel.selectFirst()
    }
    private val vulkanDeviceCombo = ComboBox<VulkanDeviceOption>().apply {
        id = "lens-vulkan-device-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(listOf(VulkanDeviceOption.AUTO))
        converter = object : StringConverter<VulkanDeviceOption>() {
            override fun toString(item: VulkanDeviceOption?): String = item?.displayLabel().orEmpty()
            override fun fromString(string: String?): VulkanDeviceOption =
                items.firstOrNull { it.displayLabel() == string } ?: VulkanDeviceOption.AUTO
        }
        setCellFactory {
            object : ListCell<VulkanDeviceOption>() {
                override fun updateItem(item: VulkanDeviceOption?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.displayLabel()
                }
            }
        }
        selectionModel.selectFirst()
    }
    private val vulkanStatusLabel = Label(I18n.t("lens.gpu.status.cpu")).apply {
        id = "lens-vulkan-status-label"
        styleClass += "studio-telemetry-val"
        isWrapText = true
    }
    private val vulkanSameGpuTipLabel = Label().apply {
        id = "lens-vulkan-same-gpu-tip"
        textProperty().bind(I18n.bind("lens.gpu.same_gpu_tip"))
        styleClass += "studio-tip-banner"
        isWrapText = true
        maxWidth = Double.MAX_VALUE
    }

    private val advancedFields = linkedMapOf<String, TextField>()
    private val telemetryLabels = List(5) {
        Label(I18n.t("lens.telemetry.waiting")).apply {
            styleClass += "studio-telemetry-val"
            isWrapText = true
        }
    }

    init {
        buildLayout()
        wireActions()
        root.sceneProperty().addListener { _, _, newScene ->
            if (newScene == null) {
                stopFocusMotorAnimation()
            } else if (focusMotorTracker.active) {
                startFocusMotorAnimation()
            }
        }
        I18n.onLocaleChange { refreshLocalizedDynamicText() }
    }

    fun dispose() {
        stopFocusMotorAnimation()
    }

    private fun startFocusMotorAnimation() {
        if (!focusAnimRunning && focusMotorTracker.active) {
            focusAnimLastNanos = 0L
            focusAnimRunning = true
            focusMotorAnimTimer.start()
        }
    }

    private fun stopFocusMotorAnimation() {
        if (focusAnimRunning) {
            focusAnimRunning = false
            focusAnimLastNanos = 0L
            focusMotorAnimTimer.stop()
        }
    }

    private fun updateFocusSliderVisuals() {
        focusSlider.value = LensProfileState.focusDistanceToSlider(
            focusMotorTracker.displayedDistanceM,
            lastState.minFocusDistanceM,
        )
        focusValueLabel.text = focusMotorTracker.formattedLabel(lastState)
    }

    private fun commitFocusTarget(targetDistanceM: Double, statusLabel: String? = null) {
        val clamped = targetDistanceM.coerceIn(lastState.minFocusDistanceM.coerceAtLeast(0.01), 1e9)
        focusMotorTracker.onUserCommitTarget(clamped, lastState, System.nanoTime())
        syncing = true
        try {
            updateFocusSliderVisuals()
        } finally {
            syncing = false
        }
        if (focusMotorTracker.active) {
            startFocusMotorAnimation()
        } else {
            stopFocusMotorAnimation()
        }
        val labelText = statusLabel ?: LensProfileState.formatDistance(clamped)
        sendPatch(
            "lens",
            LensProfileState.buildFocusTargetPatch(clamped, lastState.minFocusDistanceM),
            I18n.t("lens.status.focus_dist", labelText),
        )
    }

    private fun refreshLocalizedDynamicText() {
        syncing = true
        try {
            val curAdapter = adapterCombo.value
            adapterCombo.items.setAll(LensAdapterOption.BUILTIN_ADAPTERS)
            adapterCombo.value = curAdapter
            val curBackend = opticsBackendCombo.value ?: OpticsBackendOption.ALL.first()
            opticsBackendCombo.items.setAll(OpticsBackendOption.ALL)
            opticsBackendCombo.value = curBackend
            val curDev = vulkanDeviceCombo.value ?: VulkanDeviceOption.AUTO
            val curDevList = vulkanDeviceCombo.items.toList()
            vulkanDeviceCombo.items.setAll(curDevList)
            vulkanDeviceCombo.value = curDev
        } finally {
            syncing = false
        }
        if (observedOnce) {
            applyObservedState(lastState)
        } else {
            focusSyncBadge.text = I18n.t("lens.focus.sync.body")
            apertureSyncBadge.text = I18n.t("lens.sync.waiting")
            vulkanStatusLabel.text = I18n.t("lens.gpu.status.cpu")
            val waiting = I18n.t("lens.telemetry.waiting")
            telemetryLabels.forEach { it.text = waiting }
        }
    }

    private fun buildLayout() {
        val templateCard = VBox(
            6.0,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.template.section"))
                    styleClass += "studio-section-title"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                rescanPluginsButton,
                mountTypeBadge,
            ).apply { alignment = Pos.CENTER_LEFT },
            templateCombo,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.adapter.label"))
                    styleClass += "studio-setting-label"
                },
                adapterCombo.apply { HBox.setHgrow(this, Priority.ALWAYS) },
            ).apply { alignment = Pos.CENTER_LEFT },
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.comm.label"))
                    styleClass += "studio-setting-label"
                },
                communicationModeCombo,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                communicationModeBadge,
            ).apply { alignment = Pos.CENTER_LEFT },
            HBox(
                6.0,
                realFirmwareField.apply { HBox.setHgrow(this, Priority.ALWAYS) },
                applyModeButton,
            ).apply { alignment = Pos.CENTER_LEFT },
            FlowPane(
                12.0,
                6.0,
                mountAttachedCheck,
                oisEnabledCheck,
            ),
        ).apply { styleClass += "studio-card" }

        val quickFocusRow = FlowPane(5.0, 5.0).apply {
            listOf("0.8m" to 0.8, "2.5m" to 2.5, "12m" to 12.0, "\u221E" to 1000.0).forEach { (label, dist) ->
                children += Button(label).apply {
                    styleClass += "studio-chip-btn"
                    setOnAction {
                        commitFocusTarget(dist, statusLabel = label)
                    }
                }
            }
        }

        val apertureRingRow = HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("lens.aperture.ring.label"))
                styleClass += "studio-setting-label"
            },
            ringAutoBtn,
            ringCmdBtn,
            ringManualBtn,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            apertureSyncBadge,
        ).apply { alignment = Pos.CENTER_LEFT }

        val focusSyncRow = HBox(
            8.0,
            Label().apply {
                textProperty().bind(I18n.bind("lens.focus.mode.label"))
                styleClass += "studio-setting-label"
            },
            focusSyncBadge,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            afButton,
        ).apply { alignment = Pos.CENTER_LEFT }

        subjectRowContainer.children.setAll(
            sliderRow("lens.subject.label", subjectValueLabel, subjectSlider),
        )

        val slidersCard = VBox(
            7.0,
            Label().apply {
                textProperty().bind(I18n.bind("lens.controls.section"))
                styleClass += "studio-section-title"
            },
            apertureRingRow,
            sliderRow("lens.aperture.label", apertureValueLabel, apertureSlider),
            sliderRow("lens.focal.label", focalValueLabel, focalSlider),
            focusSyncRow,
            sliderRow("lens.focus.distance", focusValueLabel, focusSlider),
            quickFocusRow,
            subjectRowContainer,
        ).apply { styleClass += "studio-card" }

        val advGrid = GridPane().apply {
            hgap = 8.0
            vgap = 5.0
            padding = Insets(4.0, 2.0, 4.0, 2.0)
            columnConstraints.addAll(
                ColumnConstraints().apply { minWidth = 110.0; prefWidth = 128.0; hgrow = Priority.SOMETIMES },
                ColumnConstraints().apply { minWidth = 72.0; hgrow = Priority.ALWAYS },
            )
        }
        val advKeys = listOf(
            "distortionK1" to "lens.adv.k1",
            "distortionK2" to "lens.adv.k2",
            "caRedScale" to "lens.adv.ca_red",
            "caBlueScale" to "lens.adv.ca_blue",
            "longitudinalCaDefocusMm" to "lens.adv.loca",
            "wideOpenContrastDrop" to "lens.adv.contrast_drop",
            "vignettingCos4" to "lens.adv.vig_cos4",
            "vignettingMechanical" to "lens.adv.vig_mech",
            "focusBreathing" to "lens.adv.breathing",
            "transmission" to "lens.adv.transmission",
            "imageCircleMm" to "lens.adv.image_circle",
            "focusSweepSeconds" to "lens.adv.sweep_sec",
            "flareStrength" to "lens.adv.flare_strength",
            "flareTintR" to "lens.adv.flare_r",
            "flareTintG" to "lens.adv.flare_g",
            "flareTintB" to "lens.adv.flare_b",
            "apertureBladeCount" to "lens.adv.blades",
            "apertureBladeRoundness" to "lens.adv.roundness",
            "bokehCatsEye" to "lens.adv.cats_eye",
            "bokehOnionRing" to "lens.adv.onion_ring",
            "bokehRimHighlight" to "lens.adv.rim_highlight",
            "ibisShiftXPx" to "lens.adv.ibis_x",
            "ibisShiftYPx" to "lens.adv.ibis_y",
            "ibisRollRad" to "lens.adv.ibis_roll",
        )
        advKeys.forEachIndexed { idx, (key, labelKey) ->
            val tf = TextField().apply {
                id = "lens-adv-$key"
                maxWidth = Double.MAX_VALUE
            }
            advancedFields[key] = tf
            advGrid.addRow(
                idx,
                Label().apply {
                    textProperty().bind(I18n.bind(labelKey))
                    styleClass += "studio-setting-label"
                    tooltip = Tooltip(key)
                },
                tf,
            )
        }
        val applyAdvBtn = Button().apply {
            textProperty().bind(I18n.bind("lens.adv.apply"))
            maxWidth = Double.MAX_VALUE
            setOnAction { applyAdvancedFields() }
        }
        val expertSwitches = VBox(
            6.0,
            FlowPane(
                10.0,
                6.0,
                opticsEnabledCheck,
                electronicContactsCheck,
                motorSlewCheck,
                noiseEnabledCheck,
            ),
            HBox(
                8.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.adv.offline_focus"))
                    styleClass += "studio-setting-label"
                },
                focusModeCombo,
            ).apply { alignment = Pos.CENTER_LEFT },
        )
        val advancedPane = TitledPane().apply {
            textProperty().bind(I18n.bind("lens.adv.title"))
            content = VBox(8.0, expertSwitches, advGrid, applyAdvBtn)
            isExpanded = false
            isAnimated = false
        }

        val graphicsCard = VBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("lens.gpu.section"))
                styleClass += "studio-section-title"
            },
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.gpu.backend.label"))
                    styleClass += "studio-setting-label"
                },
                opticsBackendCombo.apply { HBox.setHgrow(this, Priority.ALWAYS) },
            ).apply { alignment = Pos.CENTER_LEFT },
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("lens.gpu.device.label"))
                    styleClass += "studio-setting-label"
                },
                vulkanDeviceCombo.apply { HBox.setHgrow(this, Priority.ALWAYS) },
            ).apply { alignment = Pos.CENTER_LEFT },
            vulkanStatusLabel,
            vulkanSameGpuTipLabel,
        ).apply {
            id = "lens-graphics-card"
            styleClass += "studio-card"
        }

        val telemetryCard = VBox(
            4.0,
            Label().apply {
                textProperty().bind(I18n.bind("lens.telemetry.section"))
                styleClass += "studio-section-title"
            },
            *telemetryLabels.toTypedArray(),
        ).apply { styleClass += "studio-card" }

        root.children.addAll(templateCard, graphicsCard, slidersCard, telemetryCard, advancedPane)
    }

    private fun sliderRow(titleKey: String, valueLabel: Label, slider: Slider): VBox = VBox(
        2.0,
        HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind(titleKey))
                styleClass += "studio-setting-label"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            valueLabel,
        ).apply { alignment = Pos.CENTER_LEFT },
        slider,
    )

    private fun wireActions() {
        templateCombo.setOnAction {
            if (syncing) return@setOnAction
            val selected = templateCombo.value ?: return@setOnAction
            sendPatch("lens", LensProfileState.buildTemplatePatch(selected.id), I18n.t("lens.status.template", selected.displayName))
        }
        adapterCombo.setOnAction {
            if (syncing) return@setOnAction
            val selected = adapterCombo.value ?: return@setOnAction
            sendPatch("lens", LensProfileState.buildAdapterPatch(selected.id), I18n.t("lens.status.adapter", selected.displayName))
        }
        communicationModeCombo.setOnAction {
            val selectedMode = communicationModeCombo.value ?: return@setOnAction
            realFirmwareField.isDisable = selectedMode != "REAL_FIRMWARE"
            if (!syncing && selectedMode != "REAL_FIRMWARE") {
                LensProfileState.buildCommunicationModePatch(selectedMode, "").onSuccess { patch ->
                    sendPatch("lens", patch, I18n.t("lens.status.comm_mode", selectedMode))
                }.onFailure(onError)
            }
        }
        applyModeButton.setOnAction {
            val selectedMode = communicationModeCombo.value ?: "GENERIC_SIMULATED"
            LensProfileState.buildCommunicationModePatch(selectedMode, realFirmwareField.text)
                .onSuccess { patch -> sendPatch("lens", patch, I18n.t("lens.status.comm_mode", selectedMode)) }
                .onFailure(onError)
        }
        rescanPluginsButton.setOnAction {
            sendPatch("lens", LensProfileState.buildRescanPluginsPatch(lastState.rescanPlugins), I18n.t("lens.status.rescanned"))
        }
        opticsBackendCombo.setOnAction {
            if (syncing) return@setOnAction
            val selected = opticsBackendCombo.value ?: return@setOnAction
            vulkanDeviceCombo.isDisable = selected.id == "cpu"
            onGraphicsChanged(selected.id, null)
            sendPatch("lens", LensProfileState.buildOpticsBackendPatch(selected.id), I18n.t("lens.status.gpu_backend", selected.displayName))
        }
        vulkanDeviceCombo.setOnAction {
            if (syncing) return@setOnAction
            val selected = vulkanDeviceCombo.value ?: return@setOnAction
            onGraphicsChanged(null, selected.spec)
            sendPatch("lens", LensProfileState.buildVulkanDevicePatch(selected.spec), I18n.t("lens.status.gpu_device", selected.displayLabel()))
        }
        mountAttachedCheck.setOnAction {
            if (syncing) return@setOnAction
            val attached = mountAttachedCheck.isSelected
            sendPatch("mount", mapOf("attached" to attached), I18n.t(if (attached) "lens.status.mount.attached" else "lens.status.mount.detached"))
        }
        opticsEnabledCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch("lens", mapOf("opticsEnabled" to opticsEnabledCheck.isSelected), I18n.t("lens.status.optics", opticsEnabledCheck.isSelected))
        }
        electronicContactsCheck.setOnAction {
            if (syncing) return@setOnAction
            val enabled = electronicContactsCheck.isSelected
            val patch = if (enabled) {
                mapOf("communicationMode" to "GENERIC_SIMULATED", "electronicContacts" to true, "realFirmwareFile" to "")
            } else {
                mapOf("communicationMode" to "MANUAL", "electronicContacts" to false, "focusMode" to "MF", "realFirmwareFile" to "")
            }
            sendPatch("lens", patch, I18n.t("lens.status.contacts", enabled))
        }
        oisEnabledCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch("lens", mapOf("oisEnabled" to oisEnabledCheck.isSelected), I18n.t("lens.status.ois", oisEnabledCheck.isSelected))
        }
        motorSlewCheck.setOnAction {
            if (syncing) return@setOnAction
            val instant = !motorSlewCheck.isSelected
            sendPatch("lens", mapOf("focusMotorInstant" to instant), I18n.t("lens.status.motor_slew", motorSlewCheck.isSelected))
        }
        noiseEnabledCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch("lens", mapOf("noiseEnabled" to noiseEnabledCheck.isSelected), I18n.t("lens.status.noise", noiseEnabledCheck.isSelected))
        }
        focusModeCombo.setOnAction {
            if (syncing) return@setOnAction
            val mode = focusModeCombo.value ?: return@setOnAction
            sendPatch("lens", mapOf("focusMode" to mode), I18n.t("lens.status.focus_mode", mode))
        }
        ringAutoBtn.setOnAction {
            if (syncing) return@setOnAction
            sendPatch("lens", LensProfileState.buildApertureRingPatch("A"), I18n.t("lens.status.ring_auto"))
        }
        ringCmdBtn.setOnAction {
            if (syncing) return@setOnAction
            sendPatch("lens", LensProfileState.buildApertureRingPatch("C"), I18n.t("lens.status.ring_cmd"))
        }
        ringManualBtn.setOnAction {
            if (syncing) return@setOnAction
            val snapped = LensProfileState.snapApertureThirdStop(apertureSlider.value, lastState.minAperture, lastState.maxAperture)
            sendPatch("lens", LensProfileState.buildApertureRingPatch("MANUAL", snapped), I18n.t("lens.status.ring_manual", snapped))
        }
        afButton.setOnAction {
            sendPatch("lens", LensProfileState.buildAfTriggerPatch(lastState.afTrigger), I18n.t("lens.status.af_trigger"))
        }

        focalSlider.valueProperty().addListener { _, _, v ->
            focalValueLabel.text = String.format(Locale.ROOT, "%.1f mm", v.toDouble())
        }
        focalSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                sendPatch("lens", mapOf("focalLengthMm" to focalSlider.value), I18n.t("lens.status.focal", focalValueLabel.text))
            }
        }
        focalSlider.setOnMouseReleased {
            if (!syncing && !focalSlider.isDisable) {
                sendPatch("lens", mapOf("focalLengthMm" to focalSlider.value), I18n.t("lens.status.focal", focalValueLabel.text))
            }
        }

        apertureSlider.valueProperty().addListener { _, _, v ->
            val snapped = LensProfileState.snapApertureThirdStop(v.toDouble(), lastState.minAperture, lastState.maxAperture)
            apertureValueLabel.text = String.format(Locale.ROOT, "f/%.1f", snapped)
        }
        apertureSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                val snapped = LensProfileState.snapApertureThirdStop(apertureSlider.value, lastState.minAperture, lastState.maxAperture)
                sendPatch("lens", LensProfileState.buildApertureRingPatch("MANUAL", snapped), I18n.t("lens.status.ring_f", snapped))
            }
        }
        apertureSlider.setOnMouseReleased {
            if (!syncing) {
                val snapped = LensProfileState.snapApertureThirdStop(apertureSlider.value, lastState.minAperture, lastState.maxAperture)
                sendPatch("lens", LensProfileState.buildApertureRingPatch("MANUAL", snapped), I18n.t("lens.status.ring_f", snapped))
            }
        }

        focusSlider.valueProperty().addListener { _, _, v ->
            if (!syncing) {
                val dist = LensProfileState.sliderToFocusDistance(v.toDouble(), lastState.minFocusDistanceM)
                focusValueLabel.text = LensProfileState.formatDistance(dist)
            }
        }
        focusSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (syncing) return@addListener
            if (changing) {
                focusSliderCommittedFromDrag = false
            } else if (wasChanging) {
                focusSliderCommittedFromDrag = true
                val dist = LensProfileState.sliderToFocusDistance(focusSlider.value, lastState.minFocusDistanceM)
                commitFocusTarget(dist)
            }
        }
        focusSlider.setOnMouseReleased {
            if (syncing) return@setOnMouseReleased
            if (focusSliderCommittedFromDrag) {
                focusSliderCommittedFromDrag = false
                return@setOnMouseReleased
            }
            val dist = LensProfileState.sliderToFocusDistance(focusSlider.value, lastState.minFocusDistanceM)
            commitFocusTarget(dist)
        }

        subjectSlider.valueProperty().addListener { _, _, v ->
            val dist = LensProfileState.sliderToFocusDistance(v.toDouble(), 0.2, 50.0)
            subjectValueLabel.text = LensProfileState.formatDistance(dist)
        }
        subjectSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                val dist = LensProfileState.sliderToFocusDistance(subjectSlider.value, 0.2, 50.0)
                sendPatch("lens", mapOf("subjectDistanceM" to dist), I18n.t("lens.status.subject_dist", subjectValueLabel.text))
            }
        }
        subjectSlider.setOnMouseReleased {
            if (!syncing) {
                val dist = LensProfileState.sliderToFocusDistance(subjectSlider.value, 0.2, 50.0)
                sendPatch("lens", mapOf("subjectDistanceM" to dist), I18n.t("lens.status.subject_dist", subjectValueLabel.text))
            }
        }
    }

    private fun applyAdvancedFields() {
        val raw = advancedFields.mapValues { it.value.text }
        val built = LensProfileState.buildAdvancedPatch(raw)
        built.onSuccess { patch ->
            if (patch.isNotEmpty()) {
                sendPatch("lens", patch, I18n.t("lens.status.advanced_applied"))
            }
        }.onFailure { err ->
            onError(err)
        }
    }

    private fun sendPatch(componentId: String, changes: Map<String, Any>, message: String) {
        patchComponent(componentId, changes).whenComplete { _, err ->
            if (err != null) onError(err) else onStatus(message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun observeSnapshot(snapshot: Map<String, Any?>) {
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        val lensComp = components["lens"]
        val mountComp = components["mount"]
        val cmosComp = components["cmos"]
        if (lensComp == null && mountComp == null) return

        val state = LensProfileState.fromMaps(
            lensConfig = lensComp?.get("config") as? Map<String, Any?>,
            lensState = lensComp?.get("state") as? Map<String, Any?>,
            mountConfig = mountComp?.get("config") as? Map<String, Any?>,
            mountState = mountComp?.get("state") as? Map<String, Any?>,
            cmosConfig = cmosComp?.get("config") as? Map<String, Any?>,
            cmosState = cmosComp?.get("state") as? Map<String, Any?>,
        )
        applyObservedState(state)
    }

    fun applyObservedState(state: LensProfileState) {
        lastState = state
        observedOnce = true
        syncing = true
        try {
            val allTemplates = LensTemplateSpec.BUILTIN_TEMPLATES + state.discoveredExternalLenses
            if (templateCombo.items.map { it.id } != allTemplates.map { it.id }) {
                templateCombo.items.setAll(allTemplates)
            }
            val matchedTemplate = allTemplates.firstOrNull { it.id.equals(state.template, ignoreCase = true) }
                ?: LensTemplateSpec.find(state.template)
            if (templateCombo.value?.id != matchedTemplate.id) {
                templateCombo.value = matchedTemplate
            }
            val allAdapters = LensAdapterOption.BUILTIN_ADAPTERS + state.discoveredExternalAdapters
            if (adapterCombo.items.map { it.id } != allAdapters.map { it.id }) {
                adapterCombo.items.setAll(allAdapters)
            }
            val matchedAdapter = allAdapters.firstOrNull { it.id.equals(state.adapterId, ignoreCase = true) }
                ?: LensAdapterOption.find(state.adapterId)
            if (adapterCombo.value?.id != matchedAdapter.id) {
                adapterCombo.value = matchedAdapter
            }
            val locale = I18n.localeCode()
            val lensDesc = state.activeLensMetadata.localizedDescription(locale)
                .ifBlank { matchedTemplate.opticalCharacterSummary }
                .ifBlank { state.opticalCharacterSummary }
            templateCombo.tooltip = if (lensDesc.isNotBlank()) Tooltip(lensDesc) else null
            val adapterDesc = state.activeAdapterMetadata?.localizedDescription(locale)
                ?: matchedAdapter.metadata.localizedDescription(locale)
            adapterCombo.tooltip = if (adapterDesc.isNotBlank()) Tooltip(adapterDesc) else null
            if (state.communicationMode in LensProfileState.COMMUNICATION_MODES && communicationModeCombo.value != state.communicationMode) {
                communicationModeCombo.value = state.communicationMode
            }
            communicationModeBadge.text = state.communicationMode
            realFirmwareField.isDisable = state.communicationMode != "REAL_FIRMWARE"
            if (!realFirmwareField.isFocused && realFirmwareField.text != state.realFirmwareFile) {
                realFirmwareField.text = state.realFirmwareFile
            }
            mountTypeBadge.text = state.mountType
            mountAttachedCheck.isSelected = state.mountAttached
            opticsEnabledCheck.isSelected = state.opticsEnabled
            electronicContactsCheck.isSelected = state.electronicContacts
            oisEnabledCheck.isSelected = state.oisEnabled
            oisEnabledCheck.isDisable = state.communicationMode == "MANUAL" || !state.electronicContacts ||
                (state.template != "custom" && !matchedTemplate.oisSupported)
            motorSlewCheck.isSelected = !state.focusMotorInstant
            noiseEnabledCheck.isSelected = state.noiseEnabled
            if (state.focusMode in LensProfileState.FOCUS_MODES && focusModeCombo.value != state.focusMode) {
                focusModeCombo.value = state.focusMode
            }
            val isManualComm = state.communicationMode == "MANUAL" || !state.electronicContacts
            focusModeCombo.isDisable = isManualComm
            focusSyncBadge.text = I18n.t(if (isManualComm) "lens.focus.sync.manual" else "lens.focus.sync.body")
            afButton.isDisable = isManualComm || state.focusMode.equals("MF", ignoreCase = true)

            ringAutoBtn.isDisable = isManualComm
            ringCmdBtn.isDisable = isManualComm
            ringManualBtn.isDisable = false
            fun markActive(btn: Button, active: Boolean) {
                if (active) {
                    if ("lens-ring-active" !in btn.styleClass) btn.styleClass += "lens-ring-active"
                } else {
                    btn.styleClass.remove("lens-ring-active")
                }
            }
            markActive(ringAutoBtn, !isManualComm && state.apertureRingMode == "A")
            markActive(ringCmdBtn, !isManualComm && state.apertureRingMode == "C")
            markActive(ringManualBtn, isManualComm || state.apertureRingMode == "MANUAL")
            apertureSyncBadge.text = when {
                isManualComm -> I18n.t("lens.sync.manual_ring")
                state.bodyIrisSynced -> I18n.t("lens.sync.body_synced", state.bodyCommandedAperture ?: state.aperture)
                else -> I18n.t("lens.sync.waiting")
            }

            if (!focalSlider.isValueChanging) {
                val prime = state.maxFocalLengthMm - state.minFocalLengthMm < 0.05
                focalSlider.min = min(state.minFocalLengthMm, state.focalLengthMm)
                focalSlider.max = max(state.maxFocalLengthMm, state.focalLengthMm).let { if (it <= focalSlider.min) focalSlider.min + 1.0 else it }
                focalSlider.value = state.focalLengthMm.coerceIn(focalSlider.min, focalSlider.max)
                focalSlider.isDisable = prime
                val primeSuffix = if (prime) I18n.t("lens.focal.prime_suffix") else ""
                focalValueLabel.text = String.format(Locale.ROOT, "%.1f mm%s", state.focalLengthMm, primeSuffix)
            }

            if (!apertureSlider.isValueChanging) {
                apertureSlider.min = min(state.minAperture, state.aperture)
                apertureSlider.max = max(state.maxAperture, state.aperture).let { if (it <= apertureSlider.min) apertureSlider.min + 1.0 else it }
                apertureSlider.value = state.aperture.coerceIn(apertureSlider.min, apertureSlider.max)
                val ringSuffix = I18n.t(
                    when {
                        isManualComm -> "lens.aperture.suffix.manual"
                        state.apertureRingMode == "A" -> "lens.aperture.suffix.auto"
                        state.apertureRingMode == "C" -> "lens.aperture.suffix.command"
                        else -> "lens.aperture.suffix.ring"
                    },
                )
                apertureValueLabel.text = String.format(Locale.ROOT, "f/%.1f%s", state.aperture, ringSuffix)
            }

            focusMotorTracker.onObservedState(state, focusSlider.isValueChanging, System.nanoTime())
            if (!focusSlider.isValueChanging) {
                updateFocusSliderVisuals()
            }
            if (focusMotorTracker.active) {
                startFocusMotorAnimation()
            } else {
                stopFocusMotorAnimation()
            }

            subjectSlider.isDisable = state.sceneIs3d
            if (state.sceneIs3d) {
                subjectValueLabel.text = I18n.t("lens.subject.auto3d")
            } else if (!subjectSlider.isValueChanging) {
                subjectSlider.value = LensProfileState.focusDistanceToSlider(state.subjectDistanceM, 0.2, 50.0)
                subjectValueLabel.text = LensProfileState.formatDistance(state.subjectDistanceM)
            }

            val advValues = mapOf(
                "distortionK1" to formatDecimal(state.distortionK1),
                "distortionK2" to formatDecimal(state.distortionK2),
                "caRedScale" to formatDecimal(state.caRedScale),
                "caBlueScale" to formatDecimal(state.caBlueScale),
                "longitudinalCaDefocusMm" to formatDecimal(state.longitudinalCaDefocusMm),
                "wideOpenContrastDrop" to formatDecimal(state.wideOpenContrastDrop),
                "vignettingCos4" to formatDecimal(state.vignettingCos4),
                "vignettingMechanical" to formatDecimal(state.vignettingMechanical),
                "focusBreathing" to formatDecimal(state.focusBreathing),
                "transmission" to formatDecimal(state.transmission),
                "imageCircleMm" to formatDecimal(state.imageCircleMm),
                "focusSweepSeconds" to formatDecimal(state.focusSweepSeconds),
                "flareStrength" to formatDecimal(state.flareStrength),
                "flareTintR" to formatDecimal(state.flareTintR),
                "flareTintG" to formatDecimal(state.flareTintG),
                "flareTintB" to formatDecimal(state.flareTintB),
                "apertureBladeCount" to state.apertureBladeCount.toString(),
                "apertureBladeRoundness" to formatDecimal(state.apertureBladeRoundness),
                "bokehCatsEye" to formatDecimal(state.bokehCatsEye),
                "bokehOnionRing" to formatDecimal(state.bokehOnionRing),
                "bokehRimHighlight" to formatDecimal(state.bokehRimHighlight),
                "ibisShiftXPx" to formatDecimal(state.ibisShiftXPx),
                "ibisShiftYPx" to formatDecimal(state.ibisShiftYPx),
                "ibisRollRad" to formatDecimal(state.ibisRollRad),
            )
            advValues.forEach { (key, text) ->
                val field = advancedFields[key] ?: return@forEach
                if (!field.isFocused && field.text != text) {
                    field.text = text
                }
            }

            val lines = state.telemetrySummaryLines()
            telemetryLabels.forEachIndexed { idx, label ->
                label.text = lines.getOrElse(idx) { "" }
            }

            val matchedBackend = OpticsBackendOption.find(state.opticsBackend)
            if (opticsBackendCombo.value?.id != matchedBackend.id) {
                opticsBackendCombo.value = matchedBackend
            }
            val deviceChoices = listOf(VulkanDeviceOption.AUTO) + state.availableVulkanDevices
            if (vulkanDeviceCombo.items.map { it.spec } != deviceChoices.map { it.spec }) {
                vulkanDeviceCombo.items.setAll(deviceChoices)
            }
            val matchedDev = matchVulkanDeviceOption(state.vulkanDevice, deviceChoices)
            if (vulkanDeviceCombo.value?.spec != matchedDev.spec) {
                vulkanDeviceCombo.value = matchedDev
            }
            vulkanDeviceCombo.isDisable = matchedBackend.id == "cpu"
            vulkanStatusLabel.text = formatVulkanStatus(state)
        } finally {
            syncing = false
        }
    }

    private fun matchVulkanDeviceOption(spec: String, options: List<VulkanDeviceOption>): VulkanDeviceOption {
        val trimmed = spec.trim()
        if (trimmed.isEmpty() || trimmed.equals("auto", ignoreCase = true)) return VulkanDeviceOption.AUTO
        options.firstOrNull { it.spec.equals(trimmed, ignoreCase = true) }?.let { return it }
        trimmed.toIntOrNull()?.let { idx ->
            options.firstOrNull { it.index == idx }?.let { return it }
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx > 0) {
            val namePart = trimmed.substring(colonIdx + 1).trim()
            options.firstOrNull { it.name.equals(namePart, ignoreCase = true) }?.let { return it }
            val idxPart = trimmed.substring(0, colonIdx).trim().toIntOrNull()
            if (idxPart != null) options.firstOrNull { it.index == idxPart }?.let { return it }
        }
        options.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        return VulkanDeviceOption.AUTO
    }

    private fun formatVulkanStatus(state: LensProfileState): String {
        if (state.activeOpticsBackend.equals("VULKAN", ignoreCase = true)) {
            val typeLabel = I18n.t(
                when (state.activeVulkanDeviceType.uppercase(Locale.ROOT)) {
                    "DISCRETE_GPU" -> "lens.gpu.type.discrete"
                    "INTEGRATED_GPU" -> "lens.gpu.type.integrated"
                    "VIRTUAL_GPU" -> "lens.gpu.type.virtual"
                    "CPU_SOFTWARE" -> "lens.gpu.type.cpu"
                    else -> "lens.gpu.type.other"
                },
            )
            val idx = state.activeVulkanDeviceIndex.coerceAtLeast(0)
            val key = if (state.activeVulkanExtMem) "lens.gpu.status.vulkan_ext" else "lens.gpu.status.vulkan"
            return I18n.t(key, idx, state.activeVulkanDeviceName.ifBlank { "Vulkan GPU" }, typeLabel)
        }
        if (state.opticsBackend.equals("cpu", ignoreCase = true)) {
            return I18n.t("lens.gpu.status.cpu")
        }
        val reason = state.vulkanFallbackReason.ifBlank { I18n.t("lens.gpu.device.none") }
        return I18n.t("lens.gpu.status.cpu_fallback", reason)
    }

    private fun formatDecimal(value: Double): String =
        String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')
}

/**
 * Pop-out floating studio window hosting a live-synchronized [LensProfilePanel].
 * Hidden by default so existing six-face layouts and UI smoke stage counts remain untouched until opened.
 */
internal class LensProfileWindow(
    owner: Stage,
    patchComponent: (String, Map<String, Any>) -> CompletableFuture<*>,
    onStatus: (String) -> Unit,
    onError: (Throwable) -> Unit,
    onGraphicsChanged: (String?, String?) -> Unit = { _, _ -> },
) : AutoCloseable {
    val stage = Stage()
    val panel = LensProfilePanel(patchComponent, onStatus, onError, onGraphicsChanged)
    private var closed = false

    init {
        stage.initOwner(owner)
        stage.titleProperty().bind(I18n.bind("lens.window.title"))
        stage.minWidth = 380.0
        stage.minHeight = 480.0
        val scroll = ScrollPane(panel.root).apply {
            isFitToWidth = true
        }
        val shell = BorderPane(scroll).apply {
            styleClass += "studio-shell"
            styleClass += "studio-sidebar"
            style = "-fx-font-family: '${StudioTypography.family}';"
        }
        stage.scene = Scene(shell, 420.0, 680.0).also { scene ->
            com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.applyToScene(
                scene,
                com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.origin(),
            )
        }
        stage.setOnCloseRequest { event ->
            event.consume()
            setVisible(false)
        }
    }

    val isShowing: Boolean get() = stage.isShowing

    fun applyUiTheme(theme: com.github.nyabanban.fuji.emulator.desktop.theme.UiTheme) {
        if (closed) return
        stage.scene?.let {
            com.github.nyabanban.fuji.emulator.desktop.theme.UiThemeRegistry.applyToScene(it, theme)
        }
    }

    fun setVisible(visible: Boolean) {
        if (closed) return
        if (visible) {
            if (!stage.isShowing) stage.show()
            stage.toFront()
        } else if (stage.isShowing) {
            stage.hide()
        }
    }

    fun toggle() = setVisible(!isShowing)

    fun observeSnapshot(snapshot: Map<String, Any?>) = panel.observeSnapshot(snapshot)

    override fun close() {
        if (!closed) {
            closed = true
            panel.dispose()
            stage.titleProperty().unbind()
            stage.close()
        }
    }
}
