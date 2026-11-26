package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.CmosSourceStatus
import com.github.nyabanban.fuji.emulator.desktop.panels.InputSourceCategory
import com.github.nyabanban.fuji.emulator.desktop.panels.LensProfileState
import com.github.nyabanban.fuji.emulator.desktop.panels.LensTemplateSpec
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.*

class LensAndSourcePanelLogicTest {

    @Test
    fun builtinLensTemplateCatalogMatchesAllTenRequiredPresets() {
        val ids = LensTemplateSpec.BUILTIN_TEMPLATES.map { it.id }
        assertEquals(
            listOf(
                "gf45-100",
                "gf20-35",
                "gf32-64",
                "gf63",
                "gf110",
                "gf50",
                "adapter-ef-gfx",
                "adapter-m42-dumb",
                "adapter-hasselblad-v",
                "custom",
            ),
            ids,
        )
        assertTrue(LensTemplateSpec.find("gf45-100").isZoom)
        assertTrue(LensTemplateSpec.find("gf45-100").oisSupported)
        assertFalse(LensTemplateSpec.find("gf63").isZoom)
        assertTrue(LensTemplateSpec.find("adapter-ef-gfx").electronicContacts)
        assertTrue(LensTemplateSpec.find("adapter-ef-gfx").oisSupported)
        assertEquals(0.85, LensTemplateSpec.find("adapter-ef-gfx").minFocusM)
        assertEquals(43.3, LensTemplateSpec.find("adapter-ef-gfx").imageCircleMm)
        assertFalse(LensTemplateSpec.find("adapter-m42-dumb").electronicContacts)
        assertFalse(LensTemplateSpec.find("adapter-hasselblad-v").electronicContacts)
        assertEquals(5, LensTemplateSpec.find("adapter-hasselblad-v").apertureBladeCount)
        assertEquals(79.0, LensTemplateSpec.find("adapter-hasselblad-v").imageCircleMm)
    }

    @Test
    fun lensProfileStateParsesO1TelemetryAndComputesFallbackCleanly() {
        val state = LensProfileState.fromMaps(
            lensConfig = mapOf("template" to "gf45-100", "focalLengthMm" to 70.0, "aperture" to 4.0, "opticsEnabled" to true),
            lensState = mapOf(
                "template" to "gf45-100",
                "templateDisplayName" to "GF 45-100mm F4 R LM OIS WR",
                "mountType" to "GF Native",
                "focalLengthMm" to 70.0,
                "minFocalLengthMm" to 45.0,
                "maxFocalLengthMm" to 100.0,
                "aperture" to 4.0,
                "minAperture" to 4.0,
                "maxAperture" to 32.0,
                "focusDistanceM" to 2.5,
                "minFocusDistanceM" to 0.65,
                "subjectDistanceM" to 2.5,
                "equivalent35mmFocalMm" to 55.3,
                "horizontalFovDeg" to 34.7,
                "cocMicrons" to 0.0,
                "cocPixels" to 0.0,
                "inFocus" to true,
                "dofNearM" to 2.28,
                "dofFarM" to 2.76,
                "framesFiltered" to 18L,
                "afTrigger" to 3L,
            ),
            mountConfig = mapOf("attached" to true),
            mountState = mapOf("attached" to true, "contactProtocol" to "gf-lenscom-frame32-experimental-v1"),
        )
        assertEquals("gf45-100", state.template)
        assertEquals(55.3, state.equivalent35mmFocalMm)
        assertTrue(state.inFocus)
        assertEquals(18L, state.framesFiltered)
        val summary = state.telemetrySummaryLines().joinToString("\n")
        assertTrue(summary.contains("55.3 mm"), summary)
        assertTrue(summary.contains("IN FOCUS"), summary)
        assertTrue(summary.contains("gf-lenscom-frame32-experimental-v1"), summary)

        // Fallback math when running against a pre-O1 lens state map
        val fallback = LensProfileState.fromMaps(
            lensConfig = mapOf("focalLengthMm" to 110.0, "aperture" to 2.0, "focusDistanceM" to 1.0, "subjectDistanceM" to 8.0),
            lensState = null,
            mountConfig = mapOf("attached" to true),
            mountState = null,
        )
        assertTrue(fallback.equivalent35mmFocalMm in 85.0..88.0)
        assertTrue(fallback.cocPixels > 1.0, "Defocused subject should report non-zero CoC")
        assertFalse(fallback.inFocus)
    }

    @Test
    fun focusLogSliderAndPatchBuildersValidateBoundsCleanly() {
        val mfd = 0.65
        assertEquals(0.0, LensProfileState.focusDistanceToSlider(mfd, mfd), 1e-6)
        assertEquals(1.0, LensProfileState.focusDistanceToSlider(1000.0, mfd), 1e-6)
        assertEquals(1000.0, LensProfileState.sliderToFocusDistance(1.0, mfd), 1e-6)

        val midPos = LensProfileState.focusDistanceToSlider(2.5, mfd)
        val roundTrip = LensProfileState.sliderToFocusDistance(midPos, mfd)
        assertTrue(abs(roundTrip - 2.5) < 0.05, "Expected round-trip near 2.5m, got $roundTrip")

        assertEquals(
            mapOf("template" to "gf20-35", "opticsEnabled" to true),
            LensProfileState.buildTemplatePatch("gf20-35"),
        )
        assertEquals(mapOf("afTrigger" to 5L), LensProfileState.buildAfTriggerPatch(4L))

        val validAdv = LensProfileState.buildAdvancedPatch(
            mapOf(
                "distortionK1" to "-0.04",
                "caRedScale" to "1.002",
                "imageCircleMm" to "43.3",
                "longitudinalCaDefocusMm" to "3.5",
                "wideOpenContrastDrop" to "0.24",
                "flareStrength" to "0.45",
                "apertureBladeCount" to "5",
                "bokehCatsEye" to "0.50",
            ),
        ).getOrThrow()
        assertEquals(-0.04, validAdv["distortionK1"])
        assertEquals(1.002, validAdv["caRedScale"])
        assertEquals(43.3, validAdv["imageCircleMm"])
        assertEquals(3.5, validAdv["longitudinalCaDefocusMm"])
        assertEquals(0.24, validAdv["wideOpenContrastDrop"])
        assertEquals(0.45, validAdv["flareStrength"])
        assertEquals(5L, validAdv["apertureBladeCount"])
        assertEquals(0.50, validAdv["bokehCatsEye"])

        assertTrue(LensProfileState.buildAdvancedPatch(mapOf("distortionK1" to "not-a-number")).isFailure)
        assertTrue(LensProfileState.buildAdvancedPatch(mapOf("imageCircleMm" to "5.0")).isFailure)
        assertTrue(LensProfileState.buildAdvancedPatch(mapOf("apertureBladeCount" to "15")).isFailure)

        // Strict mutually exclusive 3-mode patch builder
        val manualPatch = LensProfileState.buildCommunicationModePatch("MANUAL").getOrThrow()
        assertEquals("MANUAL", manualPatch["communicationMode"])
        assertEquals(false, manualPatch["electronicContacts"])
        assertEquals("MF", manualPatch["focusMode"])
        assertEquals("", manualPatch["realFirmwareFile"])

        assertTrue(LensProfileState.buildCommunicationModePatch("REAL_FIRMWARE", "").isFailure)
        val realPatch = LensProfileState.buildCommunicationModePatch("REAL_FIRMWARE", "GFUP0004.DAT").getOrThrow()
        assertEquals("REAL_FIRMWARE", realPatch["communicationMode"])
        assertEquals("GFUP0004.DAT", realPatch["realFirmwareFile"])
        assertEquals(true, realPatch["electronicContacts"])

        val simPatch = LensProfileState.buildCommunicationModePatch("GENERIC_SIMULATED").getOrThrow()
        assertEquals("GENERIC_SIMULATED", simPatch["communicationMode"])
        assertEquals("", simPatch["realFirmwareFile"])
        assertEquals(true, simPatch["electronicContacts"])

        // Aperture ring mode patch builders & 1/3-stop snapping + 3D scene depth awareness
        assertEquals(mapOf("apertureRingMode" to "A"), LensProfileState.buildApertureRingPatch("A"))
        assertEquals(mapOf("apertureRingMode" to "C"), LensProfileState.buildApertureRingPatch("C"))
        assertEquals(
            mapOf("apertureRingMode" to "MANUAL", "aperture" to 5.6),
            LensProfileState.buildApertureRingPatch("MANUAL", 5.6),
        )
        assertEquals(5.6, LensProfileState.snapApertureThirdStop(5.7, 4.0, 32.0), 1e-6)
        assertEquals(3.5, LensProfileState.snapApertureThirdStop(3.5, 3.5, 32.0), 1e-6)

        val state3d = LensProfileState.fromMaps(
            lensConfig = mapOf("template" to "gf45-100"),
            lensState = mapOf("template" to "gf45-100", "apertureRingMode" to "C", "bodyIrisSynced" to true, "bodyCommandedAperture" to 8.0),
            mountConfig = mapOf("attached" to true),
            mountState = mapOf("attached" to true),
            cmosConfig = mapOf("sourceKind" to "optical-scene"),
            cmosState = mapOf("sourceKind" to "optical-scene", "hasDepthBuffer" to true),
        )
        assertEquals("C", state3d.apertureRingMode)
        assertTrue(state3d.bodyIrisSynced)
        assertEquals(8.0, state3d.bodyCommandedAperture)
        assertTrue(state3d.sceneIs3d)
        assertTrue(state3d.telemetrySummaryLines().joinToString("\n").contains("Synced with Body"))
    }

    @Test
    fun sourceSelectorInferencePatchResolutionAndScreenRegionWork() {
        val defaultChip = CmosSourceStatus.PRESET_CHIPS.first()
        assertEquals("QR Sphere 3D", defaultChip.label)
        assertEquals(InputSourceCategory.OPTICAL_SCENE, defaultChip.category)
        assertEquals("optical-scene", defaultChip.sourceKind)
        assertEquals("scene://qr-ball", defaultChip.sourceFile)
        assertTrue(defaultChip.loop)
        assertTrue(InputSourceCategory.OPTICAL_SCENE.promptText.contains("scene://qr-ball"))

        assertEquals(InputSourceCategory.OPTICAL_SCENE, InputSourceCategory.infer("optical-scene", "scene://qr-ball"))
        assertEquals(InputSourceCategory.OPTICAL_SCENE, InputSourceCategory.infer("optical-scene", "scene://depth-studio"))
        assertEquals(InputSourceCategory.GAME_SCREEN, InputSourceCategory.infer("screen-capture", "screen://primary"))
        assertEquals(InputSourceCategory.WEBCAM, InputSourceCategory.infer("ffmpeg-pipe", "webcam:default"))
        assertEquals(InputSourceCategory.NETWORK_STREAM, InputSourceCategory.infer("ffmpeg-pipe", "rtsp://127.0.0.1:8554/live"))
        assertEquals(InputSourceCategory.LOCAL_VIDEO, InputSourceCategory.infer("ffmpeg-pipe", "clip.mp4"))
        assertEquals(InputSourceCategory.STATIC_IMAGE, InputSourceCategory.infer("PNG", "chart.png"))

        assertEquals(
            mapOf("sourceKind" to "PNG", "sourceFile" to "chart.png", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.STATIC_IMAGE, "chart.png", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "JPEG", "sourceFile" to "test-qr.jpg", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.STATIC_IMAGE, "test-qr.jpg", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:9555", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.NETWORK_STREAM, "tcp://127.0.0.1:9555", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "ffmpeg-pipe", "sourceFile" to "rtsp://cam/stream", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.NETWORK_STREAM, "rtsp://cam/stream", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "screen-capture", "sourceFile" to "screen://primary", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.GAME_SCREEN, "screen://primary", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "stream-ingest", "sourceFile" to "tcp://127.0.0.1:9555", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.GAME_SCREEN, "tcp://127.0.0.1:9555", true).getOrThrow(),
        )
        assertEquals(
            mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://bokeh-city", "loop" to true),
            CmosSourceStatus.resolveSourcePatch(InputSourceCategory.OPTICAL_SCENE, "scene://bokeh-city", true).getOrThrow(),
        )
        assertTrue(CmosSourceStatus.resolveSourcePatch(InputSourceCategory.STATIC_IMAGE, "   ", true).isFailure)

        assertEquals(
            "screen://region/100,50,1280,720?fps=30",
            CmosSourceStatus.buildScreenRegionRef("100", "50", "1280", "720", "30").getOrThrow(),
        )
        assertEquals(
            "screen://primary?fps=24",
            CmosSourceStatus.buildScreenRegionRef("", "", "", "", "24").getOrThrow(),
        )
        assertTrue(CmosSourceStatus.buildScreenRegionRef("0", "0", "0", "720", "24").isFailure)
        assertTrue(CmosSourceStatus.buildScreenRegionRef("0", "0", "1280", "720", "120").isFailure)

        val status = CmosSourceStatus.fromMaps(
            config = mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true),
            state = mapOf(
                "pipeline" to "optical-scene-3d",
                "framesExposed" to 12L,
                "opticsFilterActive" to true,
                "hasDepthBuffer" to true,
                "sourceWidth" to 1024,
                "sourceHeight" to 768,
            ),
        )
        val summary = status.summaryText()
        assertTrue(summary.contains("optical-scene"), summary)
        assertTrue(summary.contains("Optics: ON"), summary)
        assertTrue(summary.contains("Depth: 3D"), summary)
        assertTrue(summary.contains("1024×768"), summary)
    }

    @Test
    fun vulkanGpuSelectorStatePatchesAndI18nSameGpuNoteWork() {
        assertEquals(mapOf("opticsBackend" to "cpu"), LensProfileState.buildOpticsBackendPatch("CPU"))
        assertEquals(mapOf("opticsBackend" to "vulkan"), LensProfileState.buildOpticsBackendPatch("vulkan"))
        assertEquals(
            mapOf("vulkanDevice" to "0:NVIDIA GeForce RTX 4090", "opticsBackend" to "vulkan"),
            LensProfileState.buildVulkanDevicePatch("0:NVIDIA GeForce RTX 4090", backendOverride = "vulkan"),
        )
        assertEquals(
            mapOf("vulkanDevice" to "auto"),
            LensProfileState.buildVulkanDevicePatch("   "),
        )

        val state = LensProfileState.fromMaps(
            lensConfig = mapOf("opticsBackend" to "vulkan", "vulkanDevice" to "0:NVIDIA GeForce RTX 4090"),
            lensState = mapOf(
                "opticsBackend" to "vulkan",
                "vulkanDevice" to "0:NVIDIA GeForce RTX 4090",
                "activeVulkanDevice" to mapOf(
                    "backend" to "VULKAN",
                    "preference" to "vulkan",
                    "requestedDevice" to "0:NVIDIA GeForce RTX 4090",
                    "index" to 0,
                    "name" to "NVIDIA GeForce RTX 4090",
                    "deviceType" to "DISCRETE_GPU",
                    "supportsExternalMemory" to true,
                    "fallbackReason" to "",
                ),
                "availableVulkanDevices" to listOf(
                    mapOf(
                        "index" to 0,
                        "name" to "NVIDIA GeForce RTX 4090",
                        "spec" to "0:NVIDIA GeForce RTX 4090",
                        "deviceType" to "DISCRETE_GPU",
                        "vendorId" to 0x10DE,
                        "deviceId" to 0x2684,
                        "supportsExternalMemory" to true,
                    ),
                    mapOf(
                        "index" to 1,
                        "name" to "Intel Iris Xe Graphics",
                        "spec" to "1:Intel Iris Xe Graphics",
                        "deviceType" to "INTEGRATED_GPU",
                        "vendorId" to 0x8086,
                        "deviceId" to 0x46A6,
                        "supportsExternalMemory" to false,
                    ),
                ),
            ),
            mountConfig = null,
            mountState = null,
        )
        assertEquals("vulkan", state.opticsBackend)
        assertEquals("0:NVIDIA GeForce RTX 4090", state.vulkanDevice)
        assertEquals("VULKAN", state.activeOpticsBackend)
        assertEquals(0, state.activeVulkanDeviceIndex)
        assertEquals("NVIDIA GeForce RTX 4090", state.activeVulkanDeviceName)
        assertTrue(state.activeVulkanExtMem)
        assertEquals(2, state.availableVulkanDevices.size)
        assertTrue(state.availableVulkanDevices[0].displayLabel().contains("[0] NVIDIA GeForce RTX 4090"))
        assertTrue(state.availableVulkanDevices[0].displayLabel().contains("ExtMem"))
    }
}
