package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.BlockStoragePort
import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.ValueKind
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import com.github.nyabanban.fuji.emulator.desktop.AdditionalRamPlan
import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.tools.JvmStudioEvidence
import com.github.nyabanban.fuji.emulator.firmware.research.BootRomEntryResearch
import com.github.nyabanban.fuji.emulator.runtime.ControlServer
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Tier 4: Real-World End-User Camera Workflow Scenarios (6 scenarios).
 */
@Tag("e2e")
@Tag("e2e-tier4")
class E2eTier4RealWorldScenariosTest {

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun t4_01_blankCardFormatShootQrMenuStrict(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing runtime or blank-card snapshot assets",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe-fmt")) &&
            E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe-cmos")) &&
            E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M5", "T4-BlankFormatShootQrMenuDiscovery", ready, "Full M1+M2+M5+M6 workflow not yet merged")

        val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val payload = "FUJI-T4-FORMAT-SHOOT-QR"
        val qrFile = home.resolve("compoments/cmos/t4-qr.jpg")
        Files.createDirectories(qrFile.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile.toFile()))
        Formats.atomicYaml(home.resolve("compoments/cmos/cmos.yaml"), mapOf("sourceKind" to "JPEG", "sourceFile" to "t4-qr.jpg", "loop" to true))
        val framesDir = tempRoot.resolve("frames")
        val res = E2eHarness.runInCameraFormat(
            home,
            framesDir,
            extraButtonEvents = "650000000:menu:down,650500000:menu:up,654000000:back:down,654500000:back:up",
            ramPlan = ramPlan,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val fat = E2eHarness.inspectFatFilesystem(home.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img"))
        assertTrue(fat.fsType.startsWith("FAT32"), "Expected FAT32, got ${fat.fsType}")
        assertEquals(512, fat.bytesPerSector)
        assertTrue(fat.fat0MediaByte in setOf(0xF8, 0xF0), "Invalid FAT0 media byte: ${fat.fat0MediaByte}")

        val frames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(frames.size >= 3, "Expected format, menu, and post-back QR frames, got ${frames.size}")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(frames.first()))
        assertTrue(frames.drop(1).any { E2eHarness.changedPixelRatio(firstPx, E2eHarness.toArgbPixels(E2eHarness.readPng(it))) > 0.05 })
        val decoded = frames.mapNotNull { QrTestCodec.decode(E2eHarness.readPng(it)) }
        assertTrue(payload in decoded, "Expected decoded QR '$payload', got $decoded")

        // Discovery-mode catalog provenance & zero-unknown converged-peripheral verification
        CatalogCompiler.verify(E2eHarness.projectRoot)
        val (discHome, discCfg) = E2eHarness.stageDiscoveryBootHome(tempRoot.resolve("disc-check"), instructionBudget = 200000)
        EmulatorRuntime(discHome, discCfg).use { rt ->
            rt.serve()
            val port = checkNotNull(rt.server).address.port
            rt.probe()
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                assertEquals("discovery", client.getStateJson()["machine"]["executionMode"].asText())
                val unknown = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                assertEquals(0, unknown["accesses"].size())
            }
        }
        Formats.atomicYaml(discHome.resolve("config/emulator.yaml"), discCfg)
        val cov = E2eHarness.runMainCli(discHome, listOf("--query-coverage"))
        assertEquals(0, cov.exitCode, cov.stderr)
        assertEquals("discovery", Formats.json.readTree(cov.stdout).path("executionMode").asText())
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun t4_02_photoCardPlaybackEvfDumpSnapshot(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing runtime or photo-card snapshot assets",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe-m3")) &&
            E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe-evf")) &&
            E2eHarness.probeI2cEyeSensorReady(tempRoot.resolve("probe-eye"))
        E2eGates.assumeOrFailFeature("M4", "T4-PhotoPlayEvfDump", ready, "M3 PLAY + M4 EVF linkage not yet merged")

        val srcSha = E2eHarness.sha256(srcSdImg)
        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val eyeCtrl = PanelCatalog.load().flatMap { it.controls }.single { it.id == "rear-eye-sensor" }

        val (home1, ramPlanPath) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home1"), overrideSdReadOnly = false)
        val cfg1 = Formats.read(home1.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
        val midSnapDir = tempRoot.resolve("mid-snap")
        lateinit var evfBefore: IntArray
        lateinit var stateBefore: Map<String, Any?>
        EmulatorRuntime(home1, cfg1).use { rt1 ->
            rt1.serve()
            val port = checkNotNull(rt1.server).address.port
            rt1.owner.submit {
                rt1.session.initialize()
                rt1.session.restoreEmulatorSnapshot(
                    E2eHarness.photoCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 592_000_000L,
                        allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                rt1.session.extendAdditionalRam(AdditionalRamPlan.read(ramPlanPath))
            }.join()
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                fun patchComp(id: String, changes: Map<String, Any?>) {
                    val rev = client.getStateJson()["components"].single { it["id"].asText() == id }["revision"].asLong()
                    assertEquals(200, client.patchComponent(id, rev, changes).statusCode())
                }
                while (rt1.owner.submit { rt1.session.checkpointAttempts() < 581_000_000L && rt1.session.step() }.join()) {}
                patchComp("buttons", mapOf("play" to true))
                repeat(4) { rt1.owner.submit { rt1.session.step() }.join() }
                patchComp("buttons", mapOf("play" to false))
                while (rt1.owner.submit { rt1.session.checkpointAttempts() < 585_000_000L && rt1.session.step() }.join()) {}

                val (mainImg, shoulderState) = rt1.owner.submit {
                    val main = checkNotNull(rt1.host.ports.find("display.main", DisplayPort::class.java))
                    val px = IntArray(main.width * main.height)
                    val copied = main.copyArgb(px)
                    val img = if (copied) BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
                        setRGB(0, 0, main.width, main.height, px, 0, main.width)
                    } else null
                    val shoulder = rt1.host.components().single { it.descriptor.id == "shoulder-display" }.component.state()
                    img to shoulder
                }.join()
                assertTrue(mainImg != null && E2eHarness.maxPlayCorrelation(mainImg, refImg) >= 0.70, "Expected DSCF0001 playback on display.main")
                assertTrue((shoulderState["sequence"] as Number).toLong() >= 0L)

                val eyeId = eyeCtrl.componentId ?: "buttons"
                val eyeKey = eyeCtrl.settingKey ?: "eyeSensor"
                val eyeSetting = rt1.owner.submit {
                    rt1.host.components().single { it.descriptor.id == eyeId }.descriptor.settings.single { it.key == eyeKey }
                }.join()
                val nearValue: Any = when (eyeSetting.kind) {
                    ValueKind.BOOLEAN -> true
                    ValueKind.INTEGER -> {
                        val minVal = (eyeSetting.minimum ?: 0.0).toLong()
                        val maxVal = (eyeSetting.maximum ?: 1.0).toLong()
                        val defVal = (eyeSetting.default as? Number)?.toLong() ?: minVal
                        if (defVal == maxVal) minVal else maxVal
                    }
                    ValueKind.DECIMAL -> {
                        val minVal = eyeSetting.minimum ?: 0.0
                        val maxVal = eyeSetting.maximum ?: 1.0
                        val defVal = (eyeSetting.default as? Number)?.toDouble() ?: minVal
                        if (defVal == maxVal) minVal else maxVal
                    }
                    ValueKind.TEXT -> eyeSetting.choices.firstOrNull { it != eyeSetting.default } ?: eyeSetting.choices.firstOrNull() ?: "near"
                }
                patchComp(eyeId, mapOf(eyeKey to nearValue))
                while (rt1.owner.submit { rt1.session.checkpointAttempts() < 589_000_000L && rt1.session.step() }.join()) {}

                evfBefore = rt1.owner.submit {
                    val evf = checkNotNull(rt1.host.ports.find("display.evf", DisplayPort::class.java))
                    val px = IntArray(evf.width * evf.height)
                    assertTrue(evf.copyArgb(px), "Expected active frame on display.evf")
                    px
                }.join()
                assertTrue(E2eHarness.nonBlackRatio(evfBefore) > 0.05 && E2eHarness.distinctRgbCount(evfBefore) > 32)

                val dumpResp = client.postEmptyJson("/api/v1/dump")
                assertEquals(201, dumpResp.statusCode())
                val dumpName = Formats.json.readTree(dumpResp.body())["dump"].asText()
                val dumpZip = home1.resolve("dumps").resolve(dumpName)
                assertTrue(Files.isRegularFile(dumpZip))
                ZipFile(dumpZip.toFile()).use { z -> assertTrue(z.entries().asSequence().any()) }

                stateBefore = rt1.owner.submit { rt1.session.state() }.join()
                rt1.owner.submit { rt1.session.saveEmulatorSnapshot(midSnapDir) }.join()
            }
        }

        // Restore midSnapDir into fresh EmulatorRuntime and verify state + EVF parity
        val home2 = E2eHarness.cloneBringupHome(
            home1,
            tempRoot.resolve("home2"),
            copySdImage = true,
            overrideSdReadOnly = false,
            preserveSourceCatalog = true,
        )
        val cfg2 = Formats.read(home2.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
        EmulatorRuntime(home2, cfg2).use { rt2 ->
            rt2.owner.submit {
                rt2.session.initialize()
                rt2.session.restoreEmulatorSnapshot(midSnapDir)
            }.join()
            val stateAfter = rt2.owner.submit { rt2.session.state() }.join()
            assertEquals(stateBefore["attempts"], stateAfter["attempts"])
            assertEquals(stateBefore["registers"], stateAfter["registers"])
            val evfAfter = rt2.owner.submit {
                val evf = checkNotNull(rt2.host.ports.find("display.evf", DisplayPort::class.java))
                val px = IntArray(evf.width * evf.height)
                assertTrue(evf.copyArgb(px))
                px
            }.join()
            assertContentEquals(evfBefore, evfAfter)
        }
        assertEquals(srcSha, E2eHarness.sha256(srcSdImg))
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun t4_03_qrCaptureSessionHttpControl(@TempDir tempRoot: Path) {
        val cmosReady = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe-cmos"))
        E2eGates.assumeOrFailFeature("M2", "T4-QrHttpSession", cmosReady, "M2 CMOS QR capture not yet active")

        val payload1 = "FUJI-T4-HTTP-QR-1"
        val payload2 = "FUJI-T4-HTTP-QR-2"
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val liveReady = fw != null && lib != null &&
            Files.isDirectory(E2eHarness.blankCardHomeDir) &&
            Files.isDirectory(E2eHarness.blankCardSnapshotDir) &&
            E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe-m1"))

        if (liveReady) {
            val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
            val qrFile1 = home.resolve("compoments/cmos/http-qr-1.jpg")
            val qrFile2 = home.resolve("compoments/cmos/http-qr-2.jpg")
            Files.createDirectories(qrFile1.parent)
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload1, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile1.toFile()))
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload2, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile2.toFile()))

            val cfg = Formats.read(home.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
            EmulatorRuntime(home, cfg).use { rt ->
                rt.owner.submit {
                    rt.session.initialize()
                    rt.session.restoreEmulatorSnapshot(
                        E2eHarness.blankCardSnapshotDir,
                        EmulatorSnapshot.RestoreOptions(
                            overrideBudget = 650_000_000L,
                            allowCompatibleProfileChanges = setOf("power-peer", "soc-gpio", "soc-vglib-init"),
                        ),
                    )
                    rt.session.extendAdditionalRam(AdditionalRamPlan.read(ramPlan))
                }.join()
                rt.serve()
                val port = checkNotNull(rt.server).address.port

                fun stepAndDecodeQr(expected: String): String? {
                    var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                    repeat(8000) {
                        var found: String? = null
                        val more = rt.owner.submit {
                            val running = rt.session.step()
                            val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                            if (main.sequence > lastSeq) {
                                lastSeq = main.sequence
                                val px = IntArray(main.width * main.height)
                                if (main.copyArgb(px)) {
                                    val img = BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
                                        setRGB(0, 0, main.width, main.height, px, 0, main.width)
                                    }
                                    val dec = QrTestCodec.decode(img)
                                    if (dec == expected) found = dec
                                }
                            }
                            running
                        }.join()
                        if (found != null) return found
                        if (!more) return null
                    }
                    return null
                }

                E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                    val cmosRev1 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                    assertEquals(200, client.patchComponent("cmos", cmosRev1, mapOf("sourceKind" to "JPEG", "sourceFile" to "http-qr-1.jpg", "loop" to true)).statusCode())
                    assertEquals(payload1, stepAndDecodeQr(payload1), "Expected live-view decode of HTTP-patched payload1")

                    fun patchButtons(changes: Map<String, Any?>) {
                        val rev = client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()
                        assertEquals(200, client.patchComponent("buttons", rev, changes).statusCode())
                    }
                    patchButtons(mapOf("shutterHalf" to true))
                    repeat(4) { rt.owner.submit { rt.session.step() }.join() }
                    assertEquals(true, client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["state"]["shutterHalf"].asBoolean())
                    patchButtons(mapOf("shutterFull" to true))
                    repeat(8) { rt.owner.submit { rt.session.step() }.join() }
                    patchButtons(mapOf("shutterFull" to false, "shutterHalf" to false))
                    repeat(8) { rt.owner.submit { rt.session.step() }.join() }
                    assertEquals(false, client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["state"]["shutterHalf"].asBoolean())

                    val cmosRev2 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                    assertEquals(200, client.patchComponent("cmos", cmosRev2, mapOf("sourceKind" to "JPEG", "sourceFile" to "http-qr-2.jpg", "loop" to true)).statusCode())
                    assertEquals(payload2, stepAndDecodeQr(payload2), "Expected live-view decode of hot-swapped payload2")

                    assertTrue(client.getStateJson()["machine"]["executionMode"].asText().isNotBlank())
                    val unknownNode = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                    assertTrue(unknownNode["accesses"].isArray)
                }
            }
            return
        }

        // Deterministic real-component HTTP ControlServer + CMOS hot-swap + shutter pulse + LCDC1 live-view QR session
        val home = E2eHarness.stageCmosLiveViewComponentHome(tempRoot.resolve("home"))
        val qrFile1 = home.resolve("compoments/cmos/http-qr-1.jpg")
        val qrFile2 = home.resolve("compoments/cmos/http-qr-2.jpg")
        Files.createDirectories(qrFile1.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload1, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile1.toFile()))
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload2, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile2.toFile()))

        val catalog = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.load(E2eHarness.projectRoot.resolve("catalog/compiled/catalog.json"))
        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picRam = ByteArray(575 * picStride + 1024 * 2)
        val osdAddr = 0x0614_0000L
        val osdRam = ByteArray(1024 * 768 * 4)
        E2eHarness.paintShootingHudOsd0(osdRam)

        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            val bus = owner.submit {
                val sensor = checkNotNull(host.ports.find("cmos", com.github.nyabanban.fuji.emulator.api.SensorPort::class.java))
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java))
                val dma = object : com.github.nyabanban.fuji.emulator.api.DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        val okPic = address == picAddr && size == picRam.size.toLong()
                        val okOsd = !write && address == osdAddr && size == osdRam.size.toLong()
                        require(okPic || okOsd) { "Unexpected DMA 0x${address.toString(16)} size=$size" }
                    }
                    override fun read(address: Long, destination: ByteBuffer) {
                        if (address == osdAddr) destination.put(osdRam, 0, destination.remaining())
                        else destination.put(picRam, 0, destination.remaining())
                    }
                    override fun write(address: Long, source: ByteBuffer) {
                        check(address, source.remaining().toLong(), true)
                        source.get(picRam, 0, source.remaining())
                    }
                }
                host.ports.register("cpu", "cpu.memory", com.github.nyabanban.fuji.emulator.api.DmaMemory::class.java, dma)
                val discoveryCfg = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                    ),
                )
                val ctx = object : com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext {
                    override fun core() = 1
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x11beac4L
                    override fun tick() = 631_000_000L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = dma
                    override fun lensParameters() = com.github.nyabanban.fuji.emulator.api.LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val b = checkNotNull(com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime(catalog, "discovery", E2eHarness.projectRoot, discoveryCfg).createBus(ctx))
                var seq = 0L
                b.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                b.access(++seq, 0xfff730c4L, 4, true, picAddr)
                b.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                b.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)
                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = true)
                b
            }.join()

            var frameTick = 631_000_001L
            fun pumpAndDecode(): String? = owner.submit {
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertTrue(bus.pumpLiveViewDma(frameTick++))
                lcdc1.write(0x600, 4, 1L)
                val px = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(px))
                QrTestCodec.decode(px, main.width, main.height)
            }.join()

            val unknownProvider = { mapOf("executionMode" to "discovery", "accesses" to emptyList<Any?>()) }
            ControlServer(
                control = host,
                port = 0,
                machine = { mapOf("executionMode" to "discovery", "status" to "ready") },
                addressQuery = { unknownProvider() },
            ).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    val cmosRev1 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                    assertEquals(200, client.patchComponent("cmos", cmosRev1, mapOf("sourceKind" to "JPEG", "sourceFile" to "http-qr-1.jpg", "loop" to true)).statusCode())
                    assertEquals(payload1, pumpAndDecode(), "Expected live-view decode of HTTP-patched payload1")

                    fun patchButtons(changes: Map<String, Any?>) {
                        val rev = client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()
                        assertEquals(200, client.patchComponent("buttons", rev, changes).statusCode())
                    }
                    patchButtons(mapOf("shutterHalf" to true))
                    assertEquals(true, client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["state"]["shutterHalf"].asBoolean())
                    patchButtons(mapOf("shutterFull" to true))
                    assertEquals(true, client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["state"]["shutterFull"].asBoolean())
                    patchButtons(mapOf("shutterFull" to false, "shutterHalf" to false))
                    assertEquals(false, client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["state"]["shutterHalf"].asBoolean())

                    val cmosRev2 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                    assertEquals(200, client.patchComponent("cmos", cmosRev2, mapOf("sourceKind" to "JPEG", "sourceFile" to "http-qr-2.jpg", "loop" to true)).statusCode())
                    assertEquals(payload2, pumpAndDecode(), "Expected live-view decode of hot-swapped payload2")

                    assertTrue(client.getStateJson()["machine"]["executionMode"].asText().isNotBlank())
                    val unknownNode = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                    assertTrue(unknownNode["accesses"].isArray)
                }
            }
        }
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun t4_04_sixFaceStudioConfigCenterAndLcdLinkage(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe-evf"))
        E2eGates.assumeOrFailFeature("M4", "T4-SixFaceStudio", ready, "M4 EVF/studio linkage not yet merged")
        E2eGates.requireGraphicalDisplay("T4-SixFaceStudio")

        val panels = PanelCatalog.load()
        assertEquals(6, panels.size)
        val displayMap = panels.flatMap { it.controls }.filter { it.kind == "display" }.associateBy { it.id }
        assertEquals("display.main", displayMap.getValue("rear-lcd").displayPort)
        assertEquals("display.shoulder", displayMap.getValue("top-shoulder").displayPort)
        assertEquals("display.evf", displayMap.getValue("rear-evf").displayPort)

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        Formats.atomicYaml(
            home.resolve("config/emulator.yaml"),
            mapOf("schema" to 1, "components" to "compoments", "httpPort" to 0, "firmwareMetadata" to E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(), "firmwareFile" to "", "nativeLibrary" to "", "instructionBudget" to 1000, "enabledDevices" to emptyList<String>()),
        )
        E2eHarness.withComponentHost(home) { _, host ->
            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    assertEquals(400, client.patchComponent("battery", 0, mapOf("chargePercent" to 150.0)).statusCode())
                    assertEquals(200, client.patchComponent("battery", 0, mapOf("chargePercent" to 82.5)).statusCode())
                }
            }
        }
        val res = E2eHarness.runMainCli(home, listOf("--smoke-ui", "ui-t4.png"), timeoutSeconds = 60)
        assertEquals(0, res.exitCode, res.stderr)
        val checks = JvmStudioEvidence.checks(Formats.readData(home.resolve("ui-panels.json")), Formats.readData(home.resolve("ui-interactions.json")))
        assertTrue(checks.values.all { it }, "Failed studio evidence checks: ${checks.filterValues { !it }}")
        val shotChecks = JvmStudioEvidence.screenshotChecks(home)
        assertTrue(shotChecks.values.all { it }, "Failed screenshot checks: ${shotChecks.filterValues { !it }}")
        val uiPng = E2eHarness.readPng(home.resolve("ui-t4.png"))
        assertTrue(E2eHarness.distinctRgbCount(E2eHarness.toArgbPixels(uiPng)) > 64)
    }

    @Test
    @Tag("e2e-m6")
    @Tag("e2e-milestone-gated")
    fun t4_05_coldBootRomToCatalogVerifiedSession(@TempDir tempRoot: Path) {
        val archive = E2eHarness.resolveBootRomArchive()
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GFX100S_DUMP+GXUP0004+libunicorn", archive != null && fw != null && lib != null, "Missing GFX100S_DUMP.zip, GXUP0004.DAT, or libunicorn.so.2")
        val ready = E2eHarness.probeBootRomProfileReady() && E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M6", "T4-ColdBootVerified", ready, "M5 Boot ROM + M6 catalog convergence not yet merged")
        CatalogCompiler.verify(E2eHarness.projectRoot)

        val research = BootRomEntryResearch.analyze(E2eHarness.projectRoot, E2eHarness.projectRoot)
        @Suppress("UNCHECKED_CAST")
        val firstMmio = research["firstMmio"] as Map<String, Any?>
        val firstMmioAddr = firstMmio["address"].toString()
        assertEquals("0xFFF4A400", firstMmioAddr)

        val bootRomBytes = ZipFile(archive!!.toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
        Formats.atomicYaml(home.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
        Formats.atomicYaml(home.resolve("compoments/soc-system-control/boot-strap.json"), mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8))
        Formats.atomicYaml(home.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"))
        Formats.atomicYaml(home.resolve("compoments/soc-flash/soc-flash.yaml"), mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"))
        Formats.atomicYaml(home.resolve("compoments/flash-image/flash-image.yaml"), mapOf("sourcePort" to "firmware.update", "tailProfile" to "strict", "regionsProfile" to "strict", "regionsFile" to ""))

        val cfg = mapOf(
            "schema" to 1,
            "executionMode" to "discovery",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to E2eHarness.resolveBootRomMetadataPath().toString(),
            "firmwareFile" to fw!!.toString(),
            "bootRomFile" to "boot-rom.bin",
            "nativeLibrary" to lib!!.toString(),
            "instructionBudget" to 100000,
            "enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"),
        )
        Formats.atomicYaml(home.resolve("config/emulator.yaml"), cfg)
        val cliQuery = E2eHarness.runMainCli(home, listOf("--query-address", firstMmioAddr, "--width", "4", "--direction", "write"))
        assertEquals(0, cliQuery.exitCode, cliQuery.stderr)
        val cliNode = Formats.json.readTree(cliQuery.stdout)
        val cliCov = E2eHarness.runMainCli(home, listOf("--query-coverage"))
        assertEquals(0, cliCov.exitCode, cliCov.stderr)
        val covNode = Formats.json.readTree(cliCov.stdout)
        assertEquals("discovery", covNode["executionMode"].asText())

        EmulatorRuntime(home, cfg).use { rt ->
            rt.serve()
            val port = checkNotNull(rt.server).address.port
            val probe = rt.probe()
            assertEquals(true, probe["bootRomExecuted"])
            assertTrue((probe["attempts"] as Number).toLong() > 0L)
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                val httpQuery = client.getCatalogJson("address=$firstMmioAddr&width=4&direction=write")
                assertEquals(cliNode["classification"], httpQuery["classification"])
                assertEquals(cliNode["registerId"], httpQuery["registerId"])
                assertEquals(cliNode["permitted"], httpQuery["permitted"])
                assertEquals("discovery", client.getStateJson()["machine"]["executionMode"].asText())
                val unknown = client.getCatalogJson("unknown=true")
                val unknownAccesses = unknown["accesses"]
                for (i in 0 until unknownAccesses.size()) {
                    assertEquals(
                        0xFFF41500L,
                        unknownAccesses[i]["addressPhysical"].asLong(),
                        "Unexpected unmodeled Boot ROM peripheral access: ${unknownAccesses[i]}",
                    )
                }
            }
        }
        val (discZeroHome, discZeroCfg) = E2eHarness.stageDiscoveryBootHome(tempRoot.resolve("disc-zero"), instructionBudget = 200000)
        EmulatorRuntime(discZeroHome, discZeroCfg).use { rt ->
            rt.probe()
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val discState = rt.owner.submit { rt.session.discoveryState() }.join()
            assertEquals("discovery", sessionState["executionMode"])
            assertEquals(emptyList<Any?>(), discState["accesses"])
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun t4_06_dualCardRecoveryAndNonOverwriteGuard(@TempDir tempRoot: Path) {
        val dcf = E2eHarness.resolveDcfFixtureDir()
        val snapDir = E2eHarness.blankCardSnapshotDir
        E2eGates.requireAssets(
            "dcfFixture+blankSnapshot",
            dcf != null && Files.isDirectory(snapDir),
            "Missing DCF fixture or blank-card snapshot",
        )
        // Record read-only reference digests before running isolated sessions
        val refSnapManifestSha = E2eHarness.sha256(snapDir.resolve(EmulatorSnapshot.MANIFEST_FILE))
        val refDcfImgSha = E2eHarness.sha256(dcf!!.resolve("slot1.img"))

        // Clone into isolated @TempDir A and verify writable SD card + lease contention + dirty sector snapshot recovery
        val homeA = E2eHarness.stageComponentHome(tempRoot.resolve("home-a"))
        val sdDirA = homeA.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDirA)
        val imgA = sdDirA.resolve("slot1.img")
        Files.copy(dcf.resolve("slot1.img"), imgA)
        Formats.atomicYaml(sdDirA.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "slot1.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Inserted" to false))

        lateinit var savedSdSnap: Map<String, Any?>
        E2eHarness.withComponentHost(homeA) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                val origSector = ByteBuffer.allocate(512)
                assertEquals(512, sd.read(1, 0L, origSector))
                assertEquals(512, sd.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x6B })))
                val stateful = host.components().single { it.descriptor.id == "sdcard-reader" }.component as com.github.nyabanban.fuji.emulator.api.StatefulComponent
                savedSdSnap = stateful.saveSnapshot()
                // Concurrent second ComponentHost pointing to the SAME writable homeA must be rejected by SdImageLease
                assertFails("SdImageLease must reject concurrent lease on same SD image") {
                    E2eHarness.withComponentHost(homeA) { _, _ -> }
                }
                // Mutate sector 0 further then restore snapshot to verify rollback to 0x6B
                assertEquals(512, sd.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x11 })))
                stateful.restoreSnapshot(savedSdSnap)
                val check = ByteBuffer.allocate(512)
                assertEquals(512, sd.read(1, 0L, check))
                assertTrue(check.array().all { it == 0x6B.toByte() })

                // Also exercise buttons + display in the same isolated host
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertEquals(640, main.width)
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                assertEquals(PanelCatalog.buttonKeys.size, PanelCatalog.buttonKeys.count { !btn.pressed(it) })
            }.join()
        }

        // Verify external read-only references were NEVER modified
        assertEquals(refSnapManifestSha, E2eHarness.sha256(snapDir.resolve(EmulatorSnapshot.MANIFEST_FILE)))
        assertEquals(refDcfImgSha, E2eHarness.sha256(dcf.resolve("slot1.img")))
    }
}
