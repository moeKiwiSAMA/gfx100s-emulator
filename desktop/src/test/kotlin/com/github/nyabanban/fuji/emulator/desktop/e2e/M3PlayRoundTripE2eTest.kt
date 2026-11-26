package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryStrategy
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class M3PlayRoundTripE2eTest {

    @Test
    fun m3OptInHardwareProfilesStrictRefusalAndDiscoveryAdmission() {
        val catalog = AddressCatalog.bundled()
        val devices = catalog.data["devices"] as List<*>

        val panelProfiles = devices.filterIsInstance<Map<*, *>>()
            .single { it["componentId"] == "soc-panel" }["profiles"] as List<*>
        assertTrue("panel-init-repeatable-v1" in panelProfiles)

        val jpegProfiles = devices.filterIsInstance<Map<*, *>>()
            .single { it["componentId"] == "soc-jpeg" }["profiles"] as List<*>
        assertTrue("soc-jpeg-decoder-experimental-v1" in jpegProfiles)

        // Discovery-only 768 MiB / 40-region admission (required for 102MP PLAY zoom pyramid)
        val base = listOf(MemoryRegion("base", 0x0100_0000L, 0x0100_0000L, 7))
        val largePlayRegions = listOf(
            MemoryRegion("play-yc", 0x2e55_a000L, 0x0c22_d000L, 3),
            MemoryRegion("play-zoom", 0x59fe_6000L, 0x0c24_8000L, 3),
            MemoryRegion("play-zoom2", 0xcc34_4000L, 0x0c00_0000L, 3),
        )
        val admitted = AdditionalRamRegions.validate(base, largePlayRegions)
        assertEquals(4, admitted.size)

        // Strict mode must refuse every M3 discovery hardware extension at construction
        for ((key, profile) in listOf(
            "imageScalerE000" to "fff7e000-irq132-completion-v1",
            "playDspFa00" to "fa000000-ready-idle-v1",
            "imageResultStats" to "fff16000-fff1d000-bank1-zero-v1",
            "ispAutoSync" to "isp-tg-auto-sync-v1",
        )) {
            assertFailsWith<IllegalArgumentException>("Strict mode must refuse $key=$profile") {
                CatalogRuntime(catalog, "strict", Path.of("."), mapOf("discovery" to mapOf(key to profile)))
            }
        }

        val strictRuntime = CatalogRuntime(
            catalog,
            "strict",
            Path.of("."),
            mapOf(
                "discovery" to mapOf(
                    "inputs" to mapOf(
                        "gfx100s.fff7801c.poll.candidate" to mapOf("strategy" to DiscoveryStrategy.SETUP_REPEATABLE_7801C.id),
                    ),
                ),
            ),
        )
        assertEquals(
            null,
            strictRuntime.createBus(
                object : DiscoveryContext {
                    override fun core() = 0
                    override fun tick() = 0L
                    override fun pc() = 0L
                    override fun lr() = 0L
                },
            ),
        )
        val memoryProfile = "flashload-request-buffer-experimental-v24"
        val baseRegions = catalog.memoryProfiles.getValue(memoryProfile)
        assertFailsWith<IllegalArgumentException> {
            strictRuntime.bindPorts(
                emptyList(),
                memoryProfile,
                baseRegions,
                additionalRamProfile = AdditionalRamRegions.PROFILE,
            )
        }
    }

    @Test
    @Tag("e2e-m3")
    fun m3PhotoCardPlayFullRoundTrip(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val snapRootSpec = System.getProperty("fuji.m3.photoSnapshotDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_M3_PHOTO_SNAPSHOT_DIR")?.takeIf { it.isNotBlank() }
        val snapRoot = snapRootSpec?.let { Path.of(it) }
        assumeTrue(
            fw != null && lib != null && snapRoot != null &&
                Files.isDirectory(snapRoot.resolve("home")) &&
                Files.isDirectory(snapRoot.resolve("snap")),
            "Set FUJI_FIRMWARE_FILE, FUJI_UNICORN_LIBRARY, and FUJI_M3_PHOTO_SNAPSHOT_DIR to run live M3 photo PLAY E2E",
        )

        val enterWork = tempRoot.resolve("enter")
        val enterHome = prepareM3Home(snapRoot!!, snapRoot.resolve("snap"), enterWork, fw!!, lib!!)
        val enterFrames = enterWork.resolve("frames")
        val playSnapDir = enterWork.resolve("snap-play")
        Files.createDirectories(enterFrames)

        val ramPlanFile = writeExtendedRamPlan(snapRoot.resolve("snap"), enterWork.resolve("ram-plan.json"))
        val appendA300 = buildAppendA300Spec(snapRoot.resolve("snap"), targetLength = 2048)
        val sourceCommit = readSourceCommit(enterHome)

        // Phase 1: Press real PLAY button from Shooting HUD -> decode 102MP DSCF0001.JPG -> reach steady-state C_PLAY
        val enterRes = E2eHarness.runDisplayBringupResume(
            home = enterHome,
            sourceCommit = sourceCommit,
            snapshotDir = snapRoot.resolve("snap"),
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", PROFILE_CHANGES,
                "--budget", "1370000000",
                "--extend-ram-plan", ramPlanFile.toString(),
                "--append-a300", appendA300,
                "--button-events", "614000000:play:down,622000000:play:up",
                "--export-frames-dir", enterFrames.toString(),
                "--max-frame-exports", "128",
                "--save-snapshot-at", "1365000000:$playSnapDir",
            ),
            timeoutSeconds = 600,
            jvmArgs = listOf("-Xmx4g"),
        )
        assertEquals(0, enterRes.exitCode, "Photo PLAY enter failed:\n${enterRes.stdout.takeLast(1200)}\n${enterRes.stderr.takeLast(1200)}")

        val enterSummary = Formats.json.readTree(enterHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", enterSummary.path("status").asText(), "Photo PLAY enter stopped early: ${enterSummary.path("frontier")}")
        assertEquals(2L, enterSummary.path("executedButtonEventCount").asLong(), "Expected 2 button events (PLAY down/up)")
        assertEquals(0L, enterSummary.path("executedImageIrqEventCount").asLong(), "Zero manual --image-irq-events allowed")
        val enterResult = Formats.json.readTree(enterHome.resolve("bringup.result.json").toFile())
        val jpeg = enterResult.path("devices").path("soc-jpeg")
        assertTrue(jpeg.path("decodeCount").asLong() >= 1L, "Expected soc-jpeg decodeCount >= 1, got ${jpeg.path("decodeCount")}")
        assertEquals(0L, jpeg.path("errorCount").asLong(), "Expected soc-jpeg errorCount == 0")

        // Verify visually decoded photo frame matches DSCF0001.JPG on the SD card
        val sdImg = enterHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val dscfBytes = E2eHarness.extractFat32File(sdImg, "DCIM/100_FUJI/DSCF0001.JPG")
        val refPhoto = E2eHarness.decodeJpegSubsampled(dscfBytes, subsample = 16)

        val enterFrameEntries = Formats.json.readTree(enterHome.resolve("bringup.frames.json").toFile()).path("frames")
        var bestPhotoCorr = -1.0
        var bestPhotoFrame: Path? = null
        var bestPhotoColors = 0
        for (entry in enterFrameEntries) {
            val file = Path.of(entry.path("file").asText())
            if (!Files.isRegularFile(file)) continue
            val img = E2eHarness.readPng(file)
            val px = E2eHarness.toArgbPixels(img)
            val colors = E2eHarness.distinctRgbCount(px)
            if (colors > 2000) {
                val corr = E2eHarness.maxPlayCorrelation(img, refPhoto)
                if (corr > bestPhotoCorr) {
                    bestPhotoCorr = corr
                    bestPhotoFrame = file
                    bestPhotoColors = colors
                }
            }
        }
        assertNotNull(bestPhotoFrame, "Expected decoded photo playback frame with >2000 distinct colors")
        assertTrue(bestPhotoColors > 2000, "Expected rich photo playback colors, got $bestPhotoColors")
        assertTrue(bestPhotoCorr > 0.70, "Expected photo playback NCC > 0.70 against DSCF0001.JPG, got $bestPhotoCorr")

        // Phase 2: Press real PLAY button from steady-state C_PLAY -> return cleanly to Shooting HUD
        val returnWork = tempRoot.resolve("return")
        val returnHome = prepareM3Home(snapRoot, playSnapDir, returnWork, fw, lib, fallbackSnapDir = snapRoot.resolve("snap"))
        val returnFrames = returnWork.resolve("frames")
        Files.createDirectories(returnFrames)

        val returnRes = E2eHarness.runDisplayBringupResume(
            home = returnHome,
            sourceCommit = sourceCommit,
            snapshotDir = playSnapDir,
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", PROFILE_CHANGES,
                "--budget", "1530000000",
                "--button-events", "1370000000:play:down,1371500000:play:up",
                "--export-frames-dir", returnFrames.toString(),
                "--max-frame-exports", "200",
            ),
            timeoutSeconds = 600,
            jvmArgs = listOf("-Xmx4g"),
        )
        assertEquals(0, returnRes.exitCode, "Photo PLAY return failed:\n${returnRes.stdout.takeLast(1200)}\n${returnRes.stderr.takeLast(1200)}")

        val returnSummary = Formats.json.readTree(returnHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", returnSummary.path("status").asText(), "Photo PLAY return stopped early: ${returnSummary.path("frontier")}")
        assertEquals(2L, returnSummary.path("executedButtonEventCount").asLong(), "Expected 2 button events (PLAY return down/up)")
        assertEquals(0L, returnSummary.path("executedImageIrqEventCount").asLong(), "Zero manual --image-irq-events allowed")
        val returnResult = Formats.json.readTree(returnHome.resolve("bringup.result.json").toFile())
        assertTrue(returnResult.path("discovery").path("ispAutoSync").path("irq100Count").asLong() > 0L, "Expected IspAutoSync VSYNC during return to Shooting HUD")

        val photoPx = E2eHarness.toArgbPixels(E2eHarness.readPng(bestPhotoFrame))
        val returnFrameEntries = Formats.json.readTree(returnHome.resolve("bringup.frames.json").toFile()).path("frames")
        var foundReturnHud = false
        for (entry in returnFrameEntries) {
            if (entry.path("attempts").asLong() < 1470_000_000L) continue
            val file = Path.of(entry.path("file").asText())
            if (!Files.isRegularFile(file)) continue
            val px = E2eHarness.toArgbPixels(E2eHarness.readPng(file))
            val nonBlack = E2eHarness.nonBlackRatio(px)
            val diffFromPhoto = E2eHarness.changedPixelRatio(px, photoPx)
            if (nonBlack in 0.03..0.25 && diffFromPhoto > 0.50) {
                foundReturnHud = true
                break
            }
        }
        assertTrue(foundReturnHud, "Expected returned Shooting HUD frame after exiting photo PLAY")
    }

    @Test
    @Tag("e2e-m3")
    fun m3BlankFormattedCardPlayFullRoundTrip(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val snapRootSpec = System.getProperty("fuji.m3.blankSnapshotDir")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_M3_BLANK_SNAPSHOT_DIR")?.takeIf { it.isNotBlank() }
        val snapRoot = snapRootSpec?.let { Path.of(it) }
        assumeTrue(
            fw != null && lib != null && snapRoot != null &&
                Files.isDirectory(snapRoot.resolve("home")) &&
                Files.isDirectory(snapRoot.resolve("snap")),
            "Set FUJI_FIRMWARE_FILE, FUJI_UNICORN_LIBRARY, and FUJI_M3_BLANK_SNAPSHOT_DIR to run live M3 blank-card PLAY E2E",
        )

        val workDir = tempRoot.resolve("blank-roundtrip")
        val snapDir = snapRoot!!.resolve("snap")
        val workHome = prepareM3Home(snapRoot, snapDir, workDir, fw!!, lib!!)
        val framesDir = workDir.resolve("frames")
        Files.createDirectories(framesDir)

        val ramPlanFile = writeExtendedRamPlan(snapDir, workDir.resolve("ram-plan.json"))
        val sourceCommit = readSourceCommit(workHome)
        val appendA300 = buildAppendA300Spec(snapDir, targetLength = 2048)

        // Full round-trip: blank-post-format -> Shooting HUD -> PLAY ("No Image") -> PLAY -> Shooting HUD
        val res = E2eHarness.runDisplayBringupResume(
            home = workHome,
            sourceCommit = sourceCommit,
            snapshotDir = snapDir,
            args = listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", PROFILE_CHANGES,
                "--budget", "1640000000",
                "--extend-ram-plan", ramPlanFile.toString(),
                "--append-a300", appendA300,
                "--button-events", "1350000000:play:down,1358000000:play:up,1460000000:play:down,1461500000:play:up",
                "--export-frames-dir", framesDir.toString(),
                "--max-frame-exports", "256",
            ),
            timeoutSeconds = 600,
            jvmArgs = listOf("-Xmx4g"),
        )
        assertEquals(0, res.exitCode, "Blank-card PLAY round-trip failed:\n${res.stdout.takeLast(1200)}\n${res.stderr.takeLast(1200)}")

        val summaryJson = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summaryJson.path("status").asText(), "Blank PLAY round-trip stopped early: ${summaryJson.path("frontier")}")
        assertEquals(4L, summaryJson.path("executedButtonEventCount").asLong(), "Expected 4 button events (enter+exit PLAY)")
        assertEquals(0L, summaryJson.path("executedImageIrqEventCount").asLong(), "Zero manual --image-irq-events allowed")
        val resultJson = Formats.json.readTree(workHome.resolve("bringup.result.json").toFile())
        assertEquals(0L, resultJson.path("devices").path("soc-jpeg").path("decodeCount").asLong(), "Blank card must not invoke JPEG decode")
        assertEquals(0L, resultJson.path("devices").path("soc-jpeg").path("errorCount").asLong(), "Zero JPEG errors expected")
        assertTrue(resultJson.path("discovery").path("ispAutoSync").path("irq100Count").asLong() > 0L, "Expected IspAutoSync VSYNC for Shooting HUD")

        val frames = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        var initialHudPx: IntArray? = null
        var blankPlayPx: IntArray? = null
        var returnHudPx: IntArray? = null

        for (entry in frames) {
            val att = entry.path("attempts").asLong()
            val file = Path.of(entry.path("file").asText())
            if (!Files.isRegularFile(file)) continue
            val img = E2eHarness.readPng(file)
            val px = E2eHarness.toArgbPixels(img)
            val topBright = brightRatioInRows(px, 640, 0, 80, threshold = 160)
            val centerNonBlack = brightRatioInRows(px, 640, 210, 270, threshold = 80)

            when {
                att in 1335_000_000L..1350_000_000L && topBright > 0.03 -> initialHudPx = px
                att in 1420_000_000L..1466_000_000L && topBright < 0.005 && centerNonBlack > 0.40 -> blankPlayPx = px
                att >= 1600_000_000L && topBright > 0.03 -> returnHudPx = px
            }
        }

        assertNotNull(initialHudPx, "Expected initial Shooting HUD frame before PLAY press")
        assertNotNull(blankPlayPx, "Expected authentic 'No Image' playback banner frame in C_PLAY")
        assertNotNull(returnHudPx, "Expected returned Shooting HUD frame after exiting blank PLAY")

        val hudVsBlankDiff = E2eHarness.changedPixelRatio(initialHudPx, blankPlayPx)
        assertTrue(hudVsBlankDiff > 0.10, "Expected blank PLAY screen to differ clearly from Shooting HUD (diff=$hudVsBlankDiff)")

        // Compare the top HUD bar (y in 0..79) between initial and returned Shooting HUD
        val initTop = E2eHarness.cropArgb(initialHudPx, 640, 480, 0, 0, 640, 80)
        val retTop = E2eHarness.cropArgb(returnHudPx, 640, 480, 0, 0, 640, 80)
        val topHudCorr = E2eHarness.normalizedCrossCorrelation(initTop, 640, 80, retTop, 640, 80)
        assertTrue(topHudCorr > 0.70, "Expected returned Shooting HUD top bar to match initial Shooting HUD (NCC=$topHudCorr)")
    }

    private fun brightRatioInRows(pixels: IntArray, width: Int, yStart: Int, yEndExclusive: Int, threshold: Int): Double {
        var count = 0
        val total = (yEndExclusive - yStart) * width
        for (y in yStart until yEndExclusive) {
            val row = y * width
            for (x in 0 until width) {
                val px = pixels[row + x]
                val r = (px ushr 16) and 0xff
                val g = (px ushr 8) and 0xff
                val b = px and 0xff
                if (r > threshold || g > threshold || b > threshold) count++
            }
        }
        return count.toDouble() / total.toDouble()
    }

    private fun prepareM3Home(
        snapRoot: Path,
        snapDir: Path,
        workDir: Path,
        fw: Path,
        lib: Path,
        fallbackSnapDir: Path? = null,
    ): Path {
        val workHome = workDir.resolve("home")
        E2eHarness.cloneBringupHome(
            sourceHome = snapRoot.resolve("home"),
            targetHome = workHome,
            firmwareFile = fw,
            nativeLibrary = lib,
        )

        // Ensure snapshot's firmware metadata declares optional M3 hardware devices
        val fwMeta = workHome.resolve("config/firmware/gfx100s-dram-213.yaml")
        if (Files.isRegularFile(fwMeta)) {
            var metaTxt = Files.readString(fwMeta)
            val additions = StringBuilder()
            if ("id: \"soc-jpeg\"" !in metaTxt) {
                additions.append("- id: \"soc-jpeg\"\n  address: \"0xfffa1000\"\n  size: \"0x12c\"\n  port: \"soc-jpeg.registers\"\n  optional: true\n")
            }
            if ("id: \"soc-panel\"" !in metaTxt) {
                additions.append("- id: \"soc-panel\"\n  address: \"0xfffab000\"\n  size: \"0x18\"\n  port: \"soc-panel\"\n  optional: true\n")
            }
            if ("id: \"soc-hdmi\"" !in metaTxt) {
                additions.append("- id: \"soc-hdmi\"\n  address: \"0xfff7b000\"\n  size: \"0xa90\"\n  port: \"soc-hdmi\"\n  optional: true\n")
            }
            if ("soc-uart" !in metaTxt) {
                additions.append("- id: \"soc-uart\"\n  address: \"0xfff3a000\"\n  size: \"0x30\"\n  port: \"soc-uart.uart-bank\"\n  optional: true\n")
                additions.append("- id: \"soc-uart\"\n  address: \"0xfff3a030\"\n  size: \"0x4\"\n  port: \"soc-uart.uart-config\"\n  optional: true\n")
                additions.append("- id: \"soc-uart\"\n  address: \"0xfff3a034\"\n  size: \"0x8\"\n  port: \"soc-uart.uart-tail\"\n  optional: true\n")
            }
            if (additions.isNotEmpty()) {
                metaTxt = metaTxt.replaceFirst("\nevidence:\n", "\n${additions}evidence:\n")
                Files.writeString(fwMeta, metaTxt)
            }
        }

        // Configure explicit opt-in M3 hardware profiles in the isolated temp home
        val emuYaml = workHome.resolve("config/emulator.yaml")
        val cfg = Formats.read(emuYaml).toMutableMap()

        val enabledDevices = ((cfg["enabledDevices"] as? List<*>)?.map { it.toString() } ?: emptyList()).toMutableList()
        for (dev in listOf("soc-jpeg", "soc-panel", "soc-hdmi", "soc-uart")) {
            if (dev !in enabledDevices) enabledDevices.add(dev)
        }
        cfg["enabledDevices"] = enabledDevices

        val idleClock = ((cfg["idleClock"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()).toMutableMap()
        if (idleClock.isNotEmpty()) {
            idleClock["maxAdvances"] = 65536
            idleClock["maxIdleTicks"] = 1_000_000_000
            cfg["idleClock"] = idleClock
        }

        val disc = ((cfg["discovery"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()).toMutableMap()
        disc.remove("hdmiLinkStatusReads")
        disc.remove("hdmiStatusPairs")
        disc["imageScalerE000"] = "fff7e000-irq132-completion-v1"
        disc["playDspFa00"] = "fa000000-ready-idle-v1"
        disc["imageResultStats"] = "fff16000-fff1d000-bank1-zero-v1"
        disc["ispAutoSync"] = "isp-tg-auto-sync-v1"

        val inputs = ((disc["inputs"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()).toMutableMap()
        for (superseded in listOf(
            "gfx100s.panel.init-words.candidate",
            "gfx100s.hdmi.init-words.candidate",
            "gfx100s.fff3a000.uart-bank.candidate",
            "gfx100s.fff3a030.uart-config.candidate",
            "gfx100s.fff3a034.uart-tail.candidate",
        )) {
            inputs.remove(superseded)
        }
        inputs["gfx100s.fff7801c.poll.candidate"] = mapOf("strategy" to DiscoveryStrategy.SETUP_REPEATABLE_7801C.id)
        if ("gfx100s.fff49500.write-tail.candidate" in inputs) {
            inputs["gfx100s.fff49500.write-tail.candidate"] = mapOf("strategy" to "fff49534-49500-write-tail-repeatable-v1")
        }
        if ("gfx100s.fff49530.write-pair.candidate" in inputs) {
            inputs["gfx100s.fff49530.write-pair.candidate"] = mapOf("strategy" to "fff49530-write-pair-repeatable-v1")
        }
        if ("gfx100s.fff6c240.image.reply.candidate" in inputs) {
            @Suppress("UNCHECKED_CAST")
            val reply = (inputs["gfx100s.fff6c240.image.reply.candidate"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
            reply["strategy"] = DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE.id
            inputs["gfx100s.fff6c240.image.reply.candidate"] = reply
        }
        disc["inputs"] = inputs
        cfg["discovery"] = disc
        Formats.atomicYaml(emuYaml, cfg)

        writeComponentYaml(workHome, "soc-jpeg", mapOf("profile" to "soc-jpeg-decoder-experimental-v1", "completionDelayTicks" to 64))
        writeComponentYaml(workHome, "soc-panel", mapOf("profile" to "panel-init-repeatable-v1"))
        writeComponentYaml(workHome, "soc-uart", mapOf("profile" to "pl011-tx-ready-experimental-v1", "initialFill" to 0))
        configureHdmiRepeatable(snapDir, workHome, fallbackSnapDir)
        patchComponentYaml(workHome, "soc-dram", mapOf("repeatableCommandModes" to true))
        patchComponentYaml(workHome, "soc-serial-engine", mapOf("repeatLastPlan" to true))
        patchComponentYaml(workHome, "soc-spi", mapOf("continuousFixedMiso" to true))
        patchComponentYaml(workHome, "power-peer", mapOf("repeatLastCandidateRead" to true))
        patchComponentYaml(workHome, "soc-vglib-init", mapOf("continuousScanout" to true))

        val dramInput = workHome.resolve("compoments/soc-dram/input.yaml")
        if (Files.isRegularFile(dramInput)) {
            val txt = Files.readString(dramInput).replace("dat-replay-once-v1", "dat-replay-repeatable-v1")
            Files.writeString(dramInput, txt)
        }
        return workHome
    }

    private fun readJsonMap(path: Path): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return Formats.json.readValue(path.toFile(), Map::class.java) as Map<String, Any?>
    }

    private fun configureHdmiRepeatable(snapDir: Path, workHome: Path, fallbackSnapDir: Path?) {
        val manifest = readJsonMap(snapDir.resolve("manifest.json"))
        @Suppress("UNCHECKED_CAST")
        var hdmiStatus = (manifest["discovery"] as? Map<*, *>)?.get("hdmiStatus") as? Map<*, *>
        if (hdmiStatus == null && fallbackSnapDir != null) {
            val fallback = readJsonMap(fallbackSnapDir.resolve("manifest.json"))
            hdmiStatus = (fallback["discovery"] as? Map<*, *>)?.get("hdmiStatus") as? Map<*, *>
        }
        val pairs = (hdmiStatus?.get("pairs") as? List<*>)?.takeIf { it.isNotEmpty() } ?: listOf(listOf(0, 0))
        val links = (hdmiStatus?.get("linkReads") as? List<*>)?.takeIf { it.isNotEmpty() } ?: listOf(0)
        val hdmiDir = workHome.resolve("compoments/soc-hdmi")
        Files.createDirectories(hdmiDir)
        Formats.atomicYaml(
            hdmiDir.resolve("soc-hdmi.yaml"),
            mapOf(
                "profile" to "hdmi-init-retained-experimental-v1",
                "inputFile" to "input.json",
                "repeatLastLinkRead" to true,
            ),
        )
        Formats.writeJson(
            hdmiDir.resolve("input.json"),
            mapOf(
                "schema" to 1,
                "profile" to "hdmi-init-retained-experimental-v1",
                "initialFill" to 0,
                "pairs" to pairs,
                "linkReads" to links,
            ),
        )
    }

    private fun writeComponentYaml(home: Path, componentId: String, content: Map<String, Any>) {
        val dir = home.resolve("compoments/$componentId")
        Files.createDirectories(dir)
        Formats.atomicYaml(dir.resolve("$componentId.yaml"), content)
    }

    private fun patchComponentYaml(home: Path, componentId: String, updates: Map<String, Any>) {
        val yaml = home.resolve("compoments/$componentId/$componentId.yaml")
        if (!Files.isRegularFile(yaml)) return
        val cfg = Formats.read(yaml).toMutableMap()
        cfg.putAll(updates)
        Formats.atomicYaml(yaml, cfg)
    }

    private fun writeExtendedRamPlan(snapDir: Path, targetFile: Path): Path {
        val manifest = readJsonMap(snapDir.resolve("manifest.json"))
        @Suppress("UNCHECKED_CAST")
        val mem = manifest["memory"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val receipt = mem["additionalRamReceipt"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val plan = (receipt["plan"] as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val existing = (plan["additionalRegions"] as List<Map<String, Any?>>).toMutableList()
        val existingIntervals = existing.map { r ->
            val start = (r["address"] as Number).toLong()
            start until (start + (r["size"] as Number).toLong())
        }
        for (region in PLAY_ADDITIONAL_RAM_REGIONS) {
            val start = (region["address"] as Number).toLong()
            val end = start + (region["size"] as Number).toLong()
            val overlaps = existingIntervals.any { iv -> start <= iv.last && (end - 1L) >= iv.first }
            if (!overlaps) {
                existing.add(region)
            }
        }
        plan["additionalRegions"] = existing
        Formats.writeJson(targetFile, plan)
        return targetFile
    }

    private fun buildAppendA300Spec(snapDir: Path, targetLength: Int): String {
        val manifest = readJsonMap(snapDir.resolve("manifest.json"))
        @Suppress("UNCHECKED_CAST")
        val disc = manifest["discovery"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val regions = disc["regions"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val a300 = regions["gfx100s-sd-finite-lens-completion-a300-diagnostic-v1"] as Map<String, Any?>
        val pos = (a300["position"] as Number).toInt()
        @Suppress("UNCHECKED_CAST")
        val prev = ((a300["scriptTailSelection"] as Map<String, Any?>)["samples"] as List<Number>).map { it.toLong() }
        val padded = prev + List(maxOf(0, targetLength - prev.size)) { 0L }
        return "$pos:${padded.joinToString(",")}"
    }

    private fun readSourceCommit(home: Path): String =
        Formats.json.readTree(home.resolve("bringup-input.generated.json").toFile())
            .path("sourceCommit").asText()

    companion object {
        private const val PROFILE_CHANGES =
            "soc-jpeg,soc-vglib-init,soc-spi,power-peer,soc-dram,soc-panel,soc-hdmi,soc-uart,soc-serial-engine"

        private val PLAY_ADDITIONAL_RAM_REGIONS = listOf(
            mapOf("id" to "setup-menu-dram-1a0ca000", "address" to 0x1a0c_a000L, "size" to 0x002d_2000L, "permissions" to 3),
            mapOf("id" to "play-jpeg-3a787000", "address" to 0x3a78_7000L, "size" to 0x02a0_0000L, "permissions" to 3),
            mapOf("id" to "play-work-2d562000", "address" to 0x2d56_2000L, "size" to 0x0064_0000L, "permissions" to 3),
            mapOf("id" to "play-disp-2b3a2000", "address" to 0x2b3a_2000L, "size" to 0x0028_0000L, "permissions" to 3),
            mapOf("id" to "play-workspace-40000000", "address" to 0x4000_0000L, "size" to 0x0140_0000L, "permissions" to 3),
            mapOf("id" to "play-yc-2e55a000", "address" to 0x2e55_a000L, "size" to 0x0c22_d000L, "permissions" to 3),
            mapOf("id" to "play-di-2c482000", "address" to 0x2c48_2000L, "size" to 0x000c_4000L, "permissions" to 3),
            mapOf("id" to "play-di-2c548000", "address" to 0x2c54_8000L, "size" to 0x0000_6000L, "permissions" to 3),
            mapOf("id" to "play-di-2c59e000", "address" to 0x2c59_e000L, "size" to 0x0001_4000L, "permissions" to 3),
            mapOf("id" to "play-di-2c5b6000", "address" to 0x2c5b_6000L, "size" to 0x0004_c000L, "permissions" to 3),
            mapOf("id" to "play-exif-556b2000", "address" to 0x556b_2000L, "size" to 0x0004_0000L, "permissions" to 3),
            mapOf("id" to "play-yc2-4949c000", "address" to 0x4949_c000L, "size" to 0x0010_0000L, "permissions" to 3),
            mapOf("id" to "play-zoom-59fe6000", "address" to 0x59fe_6000L, "size" to 0x0c24_8000L, "permissions" to 3),
            mapOf("id" to "play-zoom2-cc344000", "address" to 0xcc34_4000L, "size" to 0x0c00_0000L, "permissions" to 3),
        )
    }
}
