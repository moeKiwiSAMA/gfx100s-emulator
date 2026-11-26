package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.github.nyabanban.fuji.emulator.desktop.e2e.E2eHarness
import com.github.nyabanban.fuji.emulator.desktop.e2e.QrTestCodec
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioInputState
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class M3StillMovieSwitchE2eTest {

    @Test
    fun panelCatalogWiresTopStillMovieAsLatchingMovieModeSwitch() {
        val panels = PanelCatalog.load()
        val topStillMovie = panels.flatMap { it.controls }.singleOrNull { it.id == "top-still-movie" }
        assertNotNull(topStillMovie, "Expected top-still-movie control in GFX100S panel catalog")
        assertEquals("movieMode", topStillMovie.buttonKey)
        assertTrue("movieMode" in PanelCatalog.latchingButtonKeys, "movieMode must be a latching hardware lever")
    }

    @Test
    fun studioInputStateTogglesMovieModeLatchingDeterministically() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val input = StudioInputState(send = { key, down ->
            sent += key to down
            CompletableFuture.completedFuture(Unit)
        })
        assertTrue(input.isLatching("movieMode"))
        assertFalse(input.desired("movieMode"), "Default lever state must be STILL (movieMode=false)")
        input.toggle("movieMode")
        assertTrue(input.desired("movieMode"))
        input.toggle("movieMode")
        assertFalse(input.desired("movieMode"))
        assertEquals(listOf("movieMode" to true, "movieMode" to false), sent)
    }

    private fun extendedMovieStillRamPlan(snapshotDir: Path): AdditionalRamPlan {
        val manifest = Formats.json.readTree(snapshotDir.resolve("manifest.json").toFile())
        val planNode = manifest.path("memory").path("additionalRamReceipt").path("plan")
        require(!planNode.isMissingNode && !planNode.isNull) { "Missing additionalRamReceipt.plan in $snapshotDir" }
        @Suppress("UNCHECKED_CAST")
        val rawPlan = Formats.json.convertValue(planNode, Map::class.java) as Map<String, Any?>
        val basePlan = AdditionalRamPlan.parse(rawPlan)
        val extraRegions = listOf(
            MemoryRegion("liveview-dma-ring-1a0ca000", 0x1a0ca000L, 2_957_312L, 3),
            MemoryRegion("still-pic1-liveview-ring-3d79a000", 0x3d79a000L, 10_485_760L, 3),
            MemoryRegion("movie-return-work-7fb00000", 0x7fb00000L, 5_242_880L, 3),
        )
        val merged = basePlan.additionalRegions.toMutableList()
        for (region in extraRegions) {
            if (merged.none { it.id == region.id }) merged.add(region)
        }
        return AdditionalRamPlan(
            baseProfile = basePlan.baseProfile,
            sourceProfile = basePlan.sourceProfile,
            sourceCommit = basePlan.sourceCommit,
            catalogSha256 = basePlan.catalogSha256,
            baseRegions = basePlan.baseRegions,
            additionalRegions = merged,
            profile = basePlan.profile,
        )
    }

    private fun captureDisplayMain(rt: EmulatorRuntime): BufferedImage? {
        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(main.width * main.height)
        if (!main.copyArgb(px)) return null
        return BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, main.width, main.height, px, 0, main.width)
        }
    }

    private fun countNonBlackPixels(img: BufferedImage): Int {
        var count = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) and 0x00ffffff) != 0) count++
            }
        }
        return count
    }

    @Suppress("UNCHECKED_CAST")
    private fun prepareMovieStillHome(sourceHome: Path, tempRoot: Path, firmwareFile: Path, nativeLib: Path): Path {
        val home = E2eHarness.cloneBringupHome(
            sourceHome,
            tempRoot,
            copySdImage = true,
            overrideSdReadOnly = false,
        )
        // Update staged component jars from current build if available
        val stagedCompDir = Path.of("build/emulator/compoments").toAbsolutePath().normalize()
            .let { if (Files.isDirectory(it)) it else Path.of("../build/emulator/compoments").toAbsolutePath().normalize() }
        if (Files.isDirectory(stagedCompDir)) {
            Files.list(stagedCompDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
                    Files.copy(jar, home.resolve("compoments").resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        // Enable soc-gpio v4 (binds movieMode -> GPIO Bank 7 Pin 5 active-LOW)
        val gpioYaml = home.resolve("compoments/soc-gpio/soc-gpio.yaml")
        val gpioCfg = Formats.read(gpioYaml).toMutableMap()
        gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v4"
        Formats.atomicYaml(gpioYaml, gpioCfg)

        // Enable repeatable DRAM command plans + repeatable post-ACK PHY replay
        val dramInputYaml = home.resolve("compoments/soc-dram/input.yaml")
        val dramInput = Formats.read(dramInputYaml).toMutableMap()
        val banks = (dramInput["banks"] as Map<String, Any?>).toMutableMap()
        for (bankKey in listOf("0", "1", "2", "3")) {
            val bank = (banks[bankKey] as Map<String, Any?>).toMutableMap()
            bank["commandPlanMode"] = "any-qualified-mode-repeatable-v1"
            if (bankKey in listOf("1", "2", "3")) {
                bank["postAckPhyControl"] = "dat-replay-repeatable-v1"
            }
            banks[bankKey] = bank
        }
        dramInput["banks"] = banks
        Formats.atomicYaml(dramInputYaml, dramInput)

        // Promote soc-isp to isp-banks-experimental-v2 (extends result-surface selectors 18..32 for STILL live-view)
        val ispDir = home.resolve("compoments/soc-isp")
        Files.createDirectories(ispDir)
        Formats.atomicYaml(
            ispDir.resolve("soc-isp.yaml"),
            mapOf(
                "profile" to "isp-banks-experimental-v2",
                "initialFill" to 0,
            ),
        )

        // Promote soc-panel to panel-init-repeatable-v1 (allows repeated STILL<->MOVIE panel init cycles)
        val panelDir = home.resolve("compoments/soc-panel")
        Files.createDirectories(panelDir)
        Formats.atomicYaml(
            panelDir.resolve("soc-panel.yaml"),
            mapOf("profile" to "panel-init-repeatable-v1"),
        )

        // Promote soc-uart to retained-uart-experimental-v1 (avoids uart-bank polling filling DiscoveryJournal)
        val uartDir = home.resolve("compoments/soc-uart")
        Files.createDirectories(uartDir)
        Formats.atomicYaml(
            uartDir.resolve("soc-uart.yaml"),
            mapOf("profile" to "retained-uart-experimental-v1"),
        )

        // Enable frameSync followups (keep continuousScanout=false so live shooting 0x030=0x20080001 renders)
        val vglibYaml = home.resolve("compoments/soc-vglib-init/soc-vglib-init.yaml")
        val vglibCfg = Formats.read(vglibYaml).toMutableMap()
        vglibCfg["firstScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
        vglibCfg["frameSyncResumeTick"] = 650_000_000L
        vglibCfg["frameSyncFollowupTicks"] = (0 until 80).joinToString(",") { (716_000_000L + it * 3_000_000L).toString() }
        Formats.atomicYaml(vglibYaml, vglibCfg)

        // Patch emulator.yaml: migrate legacy ISP discovery regions -> soc-isp v2, repeatable fff49530/49500, current paths
        val emuYaml = home.resolve("config/emulator.yaml")
        val emuText = Files.readString(emuYaml, Charsets.UTF_8)
            .replace("fff49530-write-pair-once-v1", "fff49530-write-pair-repeatable-v1")
            .replace("fff49534-49500-write-tail-once-v1", "fff49534-49500-write-tail-repeatable-v1")
        Files.writeString(emuYaml, emuText, Charsets.UTF_8)
        val emuCfg = Formats.read(emuYaml).toMutableMap()
        val enabledDevices = (emuCfg["enabledDevices"] as? List<*>).orEmpty().map { it.toString() }.toMutableList()
        if ("soc-isp" !in enabledDevices) enabledDevices.add("soc-isp")
        if ("soc-panel" !in enabledDevices) enabledDevices.add("soc-panel")
        if ("soc-uart" !in enabledDevices) enabledDevices.add("soc-uart")
        emuCfg["enabledDevices"] = enabledDevices
        val discovery = (emuCfg["discovery"] as? Map<String, Any?>)?.toMutableMap()
        if (discovery != null) {
            for (legacyKey in listOf(
                "image50000",
                "image50000Control",
                "image51000",
                "image59000",
                "image68000",
                "image6e000",
                "image70000",
                "imageIrq106Status",
                "imageLiveViewBanks",
                "imageResultStats",
            )) {
                discovery.remove(legacyKey)
            }
            val inputs = (discovery["inputs"] as? Map<String, Any?>)?.toMutableMap()
            if (inputs != null) {
                for (retiredId in listOf(
                    "gfx100s.fff69000.config.candidate",
                    "gfx100s.fff69100.config-set.candidate",
                    "gfx100s.fff6b000.config.candidate",
                    "gfx100s.fff11060.result-surface.candidate",
                    "gfx100s.fff72028.config-words.candidate",
                    "gfx100s.panel.init-words.candidate",
                    "gfx100s.fff3a000.uart-bank.candidate",
                    "gfx100s.fff3a030.uart-config.candidate",
                    "gfx100s.fff3a034.uart-tail.candidate",
                )) {
                    inputs.remove(retiredId)
                }
                discovery["inputs"] = inputs
            }
            emuCfg["discovery"] = discovery
        }
        val repoRoot = Path.of("configurations/jvm/firmware/gfx100s-213.yaml").toAbsolutePath().normalize()
            .let { if (Files.isRegularFile(it)) Path.of(".").toAbsolutePath().normalize() else Path.of("..").toAbsolutePath().normalize() }
        val freshCatalog = repoRoot.resolve("catalog/compiled/catalog.json")
        if (Files.isRegularFile(freshCatalog)) {
            Files.copy(freshCatalog, home.resolve("config/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
            val compiled = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.load(freshCatalog)
            val baseMeta = compiled.legacyFirmwareMetadata().toMutableMap()
            val profMeta = compiled.firmwareMetadata(
                mapOf(
                    "soc-sd-host" to "gfx100s-sd-host-address-extension-experimental-v7",
                    "soc-vglib-init" to "lcdc-functional-experimental-v1",
                    "soc-isp" to "isp-banks-experimental-v2",
                ),
            )
            val overrideIds = setOf("soc-sd-host", "soc-vglib-init", "soc-isp")
            val mergedDevices = (baseMeta["devices"] as List<Map<String, Any?>>).filter { it["id"] !in overrideIds } +
                (profMeta["devices"] as List<Map<String, Any?>>).filter { it["id"] in overrideIds }
            baseMeta["devices"] = mergedDevices
            val metaYaml = home.resolve("config/firmware-metadata-m3.yaml")
            Formats.atomicYaml(metaYaml, baseMeta)
            emuCfg["firmwareMetadata"] = metaYaml.toString()
        }
        emuCfg["firmwareFile"] = firmwareFile.toAbsolutePath().normalize().toString()
        emuCfg["nativeLibrary"] = nativeLib.toAbsolutePath().normalize().toString()
        emuCfg["httpPort"] = 0
        Formats.atomicYaml(emuYaml, emuCfg)
        return home
    }

    @Test
    fun liveMovieStillSwitchWithCmosQrOverlay(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_M3_LIVE") == "1", "Set FUJI_M3_LIVE=1 to run live STILL<->MOVIE E2E")
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val snapRootEnv = System.getenv("FUJI_M3_SNAPSHOT_ROOT")?.takeIf { it.isNotBlank() }
        assumeTrue(fw != null && lib != null && snapRootEnv != null, "Missing firmware/native/FUJI_M3_SNAPSHOT_ROOT")
        val snapRoot = Path.of(snapRootEnv!!)
        val sourceHome = snapRoot.resolve("home")
        val snapDir = snapRoot.resolve("snap")
        assumeTrue(Files.isDirectory(sourceHome) && Files.isDirectory(snapDir), "Missing home/ or snap/ under FUJI_M3_SNAPSHOT_ROOT")

        val exportDir = System.getenv("FUJI_M3_EXPORT_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val home = prepareMovieStillHome(sourceHome, tempRoot.resolve("home"), fw!!, lib!!)

        val payload = "FUJI-GFX100S-M3-QR"
        val qrPath = home.resolve("compoments/cmos/m3-qr.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))

        val ramPlan = extendedMovieStillRamPlan(snapDir)
        val cfg = Formats.read(home.resolve("config/emulator.yaml"))
        EmulatorRuntime(home, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    snapDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 920_000_000L,
                        allowCompatibleProfileChanges = setOf("soc-gpio", "soc-dram", "soc-vglib-init", "soc-isp", "soc-panel", "soc-uart"),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                rt.session.extendAdditionalRam(ramPlan)
                // Set idle-high unbound active-LOW GPIOs (0.14 AF-ON, 2.2/2.13, 6.1 DRIVE, 7.6 VIEW, 7.13 Focus-S)
                val gpio = rt.host.components().single { it.descriptor.id == "soc-gpio" }
                rt.host.patch(
                    "soc-gpio",
                    gpio.revision,
                    mapOf("input0" to 0x4000L, "input2" to 0x2004L, "input6" to 0x0012L, "input7" to 0x2040L),
                ).join()
                // Keep lever in MOVIE mode at restore (matching snapshot state)
                val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                rt.host.patch("buttons", btn.revision, mapOf("movieMode" to true)).join()
                // Patch CMOS live-view QR scene AFTER 5-key snapshot restore
                val cmos = rt.host.components().single { it.descriptor.id == "cmos" }
                rt.host.patch("cmos", cmos.revision, mapOf("sourceKind" to "JPEG", "sourceFile" to "m3-qr.jpg", "loop" to true)).join()
            }.join()

            fun patchMovieMode(movie: Boolean) {
                rt.owner.submit {
                    val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                    rt.host.patch("buttons", btn.revision, mapOf("movieMode" to movie)).join()
                }.join()
            }

            fun inspectIrqs(): String = rt.owner.submit {
                val sink = rt.host.ports.find("soc-gic.lines", InterruptSink::class.java) as EnabledEdgeInterruptSink
                val watch = listOf(100, 102, 106, 108, 117, 118, 133, 296, 297)
                watch.filter { sink.lineEnabled(it) }.joinToString(",") { line ->
                    val st = sink.edgeState(line)
                    "$line(p=${if (st.pending) 1 else 0},h=${if (st.high) 1 else 0})"
                }
            }.join()

            fun inspectPipeline(): String = rt.owner.submit {
                val mem = checkNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                fun r32(addr: Long): Long {
                    val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    mem.read(addr, buf)
                    return buf.getInt(0).toLong() and 0xffffffffL
                }
                val t79 = r32(0x035059B8L)
                val t80 = r32(0x032BD294L)
                val t74 = r32(0x02C46BFCL)
                "T79=0x${t79.toString(16)} T80=0x${t80.toString(16)} T74=0x${t74.toString(16)}"
            }.join()

            fun stepAndObserve(
                deltaAttempts: Long,
                label: String,
                stopOnQr: Boolean = false,
                stopOnQrAfter296: Boolean = false,
                autoDriveHardwareIrqs: Boolean = false,
            ): Triple<BufferedImage?, String?, Long> {
                var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                var lastImg: BufferedImage? = null
                var decoded: String? = null
                var framesSeen = 0
                val startAtt = rt.owner.submit { rt.session.checkpointAttempts() }.join()
                val targetAtt = startAtt + deltaAttempts
                var nextLogAtt = startAtt + 5_000_000L
                var nextIrq117Att = startAtt + 4_000_000L
                var nextImageEdgeAtt = startAtt + 4_000_000L
                var last296Att = -1L
                var saw296Delivered = false
                var sawTeardown = false
                var rrCursor = 0
                val edgeOrder = listOf(106 to 0x41L, 108 to 1L, 100 to 1L, 102 to 1L)
                while (true) {
                    val outcome = rt.owner.submit {
                        val running = rt.session.step()
                        val att = rt.session.checkpointAttempts()
                        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                        var img: BufferedImage? = null
                        if (main.sequence > lastSeq) {
                            lastSeq = main.sequence
                            img = captureDisplayMain(rt)
                        }
                        if (autoDriveHardwareIrqs) {
                            val sink = rt.host.ports.find("soc-gic.lines", InterruptSink::class.java) as EnabledEdgeInterruptSink
                            // 1. Periodic LCDC IRQ 117 when enabled (after mode-switch teardown begins)
                            if (att >= nextIrq117Att && sink.lineEnabled(117) && (!sink.lineEnabled(108) || sawTeardown)) {
                                val st117 = sink.edgeState(117)
                                if (st117.edge && !st117.high && !st117.pending) {
                                    sink.setLine(117, true)
                                    sink.setLine(117, false)
                                    println("[M3][$label] Pulsed LCDC IRQ 117 at att=$att")
                                    nextIrq117Att = att + 4_000_000L
                                }
                            }
                            // 2. Level-sensitive ISP IRQ 296 when firmware enables GIC 296 + 0xfff6910c bit1
                            if (att >= last296Att + 2_000_000L && sink.lineEnabled(296)) {
                                val st296 = sink.edgeState(296)
                                if (!st296.high && !st296.pending) {
                                    try {
                                        rt.session.signalImageInterrupt(296, true)
                                        last296Att = att
                                        saw296Delivered = true
                                        rrCursor = 0
                                        nextImageEdgeAtt = att + 1_500_000L
                                        println("[M3][$label] Delivered ISP IRQ 296 bit1 at att=$att")
                                    } catch (_: Exception) {
                                        // 0xfff6910c bit1 not armed yet
                                    }
                                }
                            }
                            // 3. Round-robin edge-triggered ISP IRQs (106/108/100/102) strictly after 296 fired
                            if (saw296Delivered && att >= nextImageEdgeAtt) {
                                for (step in edgeOrder.indices) {
                                    val idx = (rrCursor + step) % edgeOrder.size
                                    val (line, mask) = edgeOrder[idx]
                                    if (sink.lineEnabled(line)) {
                                        val st = sink.edgeState(line)
                                        if (st.edge && !st.high && !st.pending) {
                                            try {
                                                rt.session.signalImageInterrupt(line, false, mask)
                                                rrCursor = (idx + 1) % edgeOrder.size
                                                println("[M3][$label] Delivered ISP IRQ $line (mask=0x${mask.toString(16)}) at att=$att")
                                                nextImageEdgeAtt = att + 1_000_000L
                                                break
                                            } catch (_: Exception) {
                                                // preconditions not yet armed
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Triple(running, att, img)
                    }.join()
                    val (running, att, img) = outcome
                    if (img != null) {
                        framesSeen++
                        lastImg = img
                        val dec = QrTestCodec.decode(img)
                        val nonBlack = countNonBlackPixels(img)
                        if (nonBlack < 50_000) sawTeardown = true
                        println("[M3][$label] frame seq=$lastSeq att=$att nonBlack=$nonBlack qr=${dec ?: "<none>"} ${inspectPipeline()}")
                        if (dec != null && (!stopOnQrAfter296 || saw296Delivered)) {
                            decoded = dec
                            if ((stopOnQr || (stopOnQrAfter296 && saw296Delivered)) && dec == payload) break
                        }
                    }
                    if (att >= nextLogAtt) {
                        println("[M3][$label] progress att=$att enabledIrqs=[${inspectIrqs()}] ${inspectPipeline()}")
                        nextLogAtt += 5_000_000L
                    }
                    if (!running || att >= targetAtt) break
                }
                val endAtt = rt.owner.submit { rt.session.checkpointAttempts() }.join()
                val summary = rt.owner.submit { rt.session.stateWithoutDiscovery() }.join()
                val lcdcDiag = rt.owner.submit {
                    val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)
                    if (lcdc == null) "lcdc=null" else {
                        val r008 = lcdc.read(0x008, 4)
                        val r030 = lcdc.read(0x030, 4)
                        val r044 = lcdc.read(0x044, 4)
                        val r164 = lcdc.read(0x164, 4)
                        val r168 = lcdc.read(0x168, 4)
                        val r16c = lcdc.read(0x16c, 4)
                        val r178 = lcdc.read(0x178, 4)
                        val r17c = lcdc.read(0x17c, 4)
                        "lcdc(008=0x${r008.toString(16)},030=0x${r030.toString(16)},osd=0x${r044.toString(16)},pic1=0x${(((r164 and 3L) shl 32) or r168).toString(16)},stride=0x${r16c.toString(16)},size=0x${r178.toString(16)},out=0x${r17c.toString(16)})"
                    }
                }.join()
                println("[M3][$label] done att=$endAtt status=${summary["status"]} frontier=${summary["frontier"]} frames=$framesSeen qr=${decoded ?: "<none>"} enabledIrqs=[${inspectIrqs()}] ${inspectPipeline()} $lcdcDiag")
                return Triple(lastImg, decoded, endAtt)
            }

            // Phase 1: Verify MOVIE HUD + CMOS QR overlay
            val (movieImg, movieQr, _) = stepAndObserve(10_000_000L, "MOVIE-INIT", stopOnQr = true)
            assertNotNull(movieImg, "Expected MOVIE live-view frame")
            assertEquals(payload, movieQr, "Expected CMOS QR to decode cleanly under MOVIE HUD")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                ImageIO.write(movieImg, "png", dir.resolve("m3-movie-hud-qr.png").toFile())
            }

            // Phase 2: Flip physical lever MOVIE -> STILL (movieMode=false -> GPIO 7.5 HIGH)
            patchMovieMode(false)
            val (stillImg, stillQr, _) = stepAndObserve(
                110_000_000L,
                "MOVIE->STILL",
                stopOnQr = false,
                stopOnQrAfter296 = true,
                autoDriveHardwareIrqs = true,
            )
            assertNotNull(stillImg, "Expected STILL HUD + live-view frame after switching movieMode=false")
            assertEquals(payload, stillQr, "Expected CMOS QR to decode cleanly under STILL HUD")
            assertTrue(countNonBlackPixels(stillImg) > 150_000, "Expected full STILL live-view QR + HUD")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                ImageIO.write(stillImg, "png", dir.resolve("m3-still-hud-qr.png").toFile())
            }

            // Phase 3: Flip physical lever STILL -> MOVIE (movieMode=true -> GPIO 7.5 LOW)
            patchMovieMode(true)
            val (returnImg, returnQr, _) = stepAndObserve(
                30_000_000L,
                "STILL->MOVIE",
                stopOnQr = false,
                stopOnQrAfter296 = true,
                autoDriveHardwareIrqs = true,
            )
            println("[M3][SUMMARY] movieQr=$movieQr stillQr=$stillQr returnQr=$returnQr returnNonBlack=${returnImg?.let(::countNonBlackPixels)}")
            assertNotNull(returnImg, "Expected MOVIE return HUD frame after switching movieMode=true")
            assertTrue(countNonBlackPixels(returnImg) > 15_000, "Expected crisp non-blank MOVIE return HUD")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                ImageIO.write(returnImg, "png", dir.resolve("m3-movie-return-hud-qr.png").toFile())
            }
        }
    }
}
