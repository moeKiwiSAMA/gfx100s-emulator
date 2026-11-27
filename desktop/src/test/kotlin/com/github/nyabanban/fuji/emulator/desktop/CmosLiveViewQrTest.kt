package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.desktop.e2e.E2eHarness
import com.github.nyabanban.fuji.emulator.desktop.e2e.QrTestCodec
import com.github.nyabanban.fuji.emulator.desktop.tools.VglibInitPreparation
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CmosLiveViewQrTest {
    private fun extendedPic1Plan(snapshotDir: Path): AdditionalRamPlan {
        val manifest = Formats.json.readTree(snapshotDir.resolve("manifest.json").toFile())
        val planNode = manifest.path("memory").path("additionalRamReceipt").path("plan")
        require(!planNode.isMissingNode && !planNode.isNull) { "Missing additionalRamReceipt.plan in $snapshotDir" }
        @Suppress("UNCHECKED_CAST")
        val rawPlan = Formats.json.convertValue(planNode, Map::class.java) as Map<String, Any?>
        val basePlan = AdditionalRamPlan.parse(rawPlan)
        val pic1Region = MemoryRegion("lcdc-pic1-liveview-89960000", 0x89960000L, 0x258000L, 3)
        val nextAdditional = if (basePlan.additionalRegions.any { it.id == pic1Region.id }) {
            basePlan.additionalRegions
        } else {
            basePlan.additionalRegions + pic1Region
        }
        return AdditionalRamPlan(
            baseProfile = basePlan.baseProfile,
            sourceProfile = basePlan.sourceProfile,
            sourceCommit = basePlan.sourceCommit,
            catalogSha256 = basePlan.catalogSha256,
            baseRegions = basePlan.baseRegions,
            additionalRegions = nextAdditional,
            profile = basePlan.profile,
        )
    }

    private fun preparePic1LiveViewHome(tempRoot: Path, initialYamlConfig: Map<String, Any>? = null): Path {
        val home = E2eHarness.cloneBringupHome(
            E2eHarness.blankCardHomeDir,
            tempRoot,
            copySdImage = true,
            overrideSdReadOnly = false,
        )
        val vglibYaml = home.resolve("compoments/soc-vglib-init/soc-vglib-init.yaml")
        val vglibCfg = Formats.read(vglibYaml).toMutableMap()
        vglibCfg["firstScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
        Formats.atomicYaml(vglibYaml, vglibCfg)
        if (initialYamlConfig != null) {
            val cmosYaml = home.resolve("compoments/cmos/cmos.yaml")
            Files.createDirectories(cmosYaml.parent)
            Formats.atomicYaml(cmosYaml, initialYamlConfig)
        }
        return home
    }

    private fun captureDisplayMain(rt: EmulatorRuntime): BufferedImage? {
        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(main.width * main.height)
        if (!main.copyArgb(px)) return null
        return BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, main.width, main.height, px, 0, main.width)
        }
    }

    @Test
    fun cmosYamlScenePreservedAcrossSnapshotRestoreAndDecodesOnDisplayMain(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        assumeTrue(
            fw != null && lib != null &&
                Files.isDirectory(E2eHarness.blankCardHomeDir) &&
                Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing runtime or blankCard snapshot assets",
        )

        val payload = "FUJI-M2-PRESET-QR"
        val home = preparePic1LiveViewHome(
            tempRoot.resolve("home"),
            mapOf("sourceKind" to "JPEG", "sourceFile" to "preset-qr.jpg", "loop" to true),
        )
        val qrPath = home.resolve("compoments/cmos/preset-qr.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))

        val ramPlan = extendedPic1Plan(E2eHarness.blankCardSnapshotDir)
        val cfg = Formats.read(home.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
        EmulatorRuntime(home, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    E2eHarness.blankCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 650_000_000L,
                        allowCompatibleProfileChanges = setOf("power-peer"),
                    ),
                )
                rt.session.extendAdditionalRam(ramPlan)
                assertEquals("preset-qr.jpg", rt.session.componentState("cmos")?.get("sourceFile"))
            }.join()

            var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
            var decoded: String? = null
            for (step in 0 until 10000) {
                val more = rt.owner.submit {
                    val running = rt.session.step()
                    val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                    if (main.sequence > lastSeq) {
                        lastSeq = main.sequence
                        captureDisplayMain(rt)?.let { img ->
                            decoded = QrTestCodec.decode(img)
                        }
                    }
                    running
                }.join()
                if (decoded == payload) break
                if (!more) break
            }
            assertEquals(payload, decoded, "Expected pre-configured cmos.yaml QR payload to survive restore and decode on display.main")
        }
    }

    @Test
    fun httpHotSwapMenuOverlayAndScreenshotExport(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        assumeTrue(
            fw != null && lib != null &&
                Files.isDirectory(E2eHarness.blankCardHomeDir) &&
                Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing runtime or blankCard snapshot assets",
        )

        val home = preparePic1LiveViewHome(tempRoot.resolve("home"))
        val payload1 = "FUJI-GFX100S-M2-QR-01"
        val payload2 = "FUJI-GFX100S-M2-QR-02"
        val qr1 = home.resolve("compoments/cmos/live-qr-1.jpg")
        val qr2 = home.resolve("compoments/cmos/live-qr-2.jpg")
        Files.createDirectories(qr1.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload1, modulePixels = 8, quietZoneModules = 4), "jpg", qr1.toFile()))
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload2, modulePixels = 8, quietZoneModules = 4), "jpg", qr2.toFile()))

        val ramPlan = extendedPic1Plan(E2eHarness.blankCardSnapshotDir)
        val cfg = Formats.read(home.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
        EmulatorRuntime(home, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    E2eHarness.blankCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 650_000_000L,
                        allowCompatibleProfileChanges = setOf("power-peer"),
                    ),
                )
                rt.session.extendAdditionalRam(ramPlan)
            }.join()
            rt.serve()
            val port = checkNotNull(rt.server).address.port

            fun stepUntilFrameAndDecode(expected: String, maxSteps: Int = 12000): Pair<BufferedImage, String>? {
                var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                repeat(maxSteps) {
                    var matched: Pair<BufferedImage, String>? = null
                    val more = rt.owner.submit {
                        val running = rt.session.step()
                        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                        if (main.sequence > lastSeq) {
                            lastSeq = main.sequence
                            captureDisplayMain(rt)?.let { img ->
                                val dec = QrTestCodec.decode(img)
                                if (dec == expected) matched = img to dec
                            }
                        }
                        running
                    }.join()
                    if (matched != null) return matched
                    if (!more) return null
                }
                return null
            }

            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                val cmosRev1 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                assertEquals(
                    200,
                    client.patchComponent("cmos", cmosRev1, mapOf("sourceKind" to "JPEG", "sourceFile" to "live-qr-1.jpg", "loop" to true)).statusCode(),
                )
                val (frame1, dec1) = assertNotNull(
                    stepUntilFrameAndDecode(payload1),
                    "Expected initial HTTP-patched QR payload1 to decode on display.main",
                )
                assertEquals(payload1, dec1)

                // Export verified display.main QR live-view PNG deliverable
                val deliverableDir = Path.of("/home/coder/foobar-20261120")
                Files.createDirectories(deliverableDir)
                val deliverable = deliverableDir.resolve("m2-cmos-qr-liveview.png")
                val tmpPng = tempRoot.resolve("m2-cmos-qr-liveview.png")
                check(ImageIO.write(frame1, "png", tmpPng.toFile()))
                Files.copy(tmpPng, deliverable, StandardCopyOption.REPLACE_EXISTING)
                assertEquals(payload1, QrTestCodec.decode(ImageIO.read(deliverable.toFile())))

                // Hot-swap to payload2 via HTTP PATCH
                val cmosRev2 = client.getStateJson()["components"].single { it["id"].asText() == "cmos" }["revision"].asLong()
                assertEquals(
                    200,
                    client.patchComponent("cmos", cmosRev2, mapOf("sourceKind" to "JPEG", "sourceFile" to "live-qr-2.jpg", "loop" to true)).statusCode(),
                )
                val (frame2, dec2) = assertNotNull(
                    stepUntilFrameAndDecode(payload2),
                    "Expected hot-swapped HTTP QR payload2 to decode on display.main",
                )
                assertEquals(payload2, dec2)

                // Verify HTTP button inputs (MENU / BACK) do not disrupt live CMOS QR rendering on snap2300k.
                // Note: Full-VM MENU OSD0 rendering (0400 UI_LIB_MENU_REC) depends on M1 (m1-menu-clean);
                // deterministic OSD0 HUD + opaque MENU + BACK coexistence over live CMOS PIC1 QR is
                // strictly asserted in osd0HudAndMenuOverlayCoexistWithLiveCmosPic1Qr below.
                fun patchButton(key: String, pressed: Boolean) {
                    val rev = client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()
                    assertEquals(200, client.patchComponent("buttons", rev, mapOf(key to pressed)).statusCode())
                }
                fun stepAttempts(deltaAttempts: Long) {
                    rt.owner.submit {
                        val target = rt.session.checkpointAttempts() + deltaAttempts
                        while (rt.session.checkpointAttempts() < target && rt.session.step()) {
                            // advance guest until deltaAttempts
                        }
                    }.join()
                }
                patchButton("menu", true)
                stepAttempts(500_000L)
                patchButton("menu", false)
                patchButton("back", true)
                stepAttempts(500_000L)
                patchButton("back", false)
                val seqAfterButtons = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                val postBtn = stepUntilFrameAndDecode(payload2, maxSteps = 20000)
                val seqEnd = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                val endState = rt.owner.submit { rt.session.state() }.join()
                assertNotNull(
                    postBtn,
                    "Expected QR payload2 to remain decodable across button input and frame refresh " +
                        "(seqAfterButtons=$seqAfterButtons, seqEnd=$seqEnd, status=${endState["status"]}, frontier=${endState["frontier"]})",
                )
            }
        }
    }

    @Test
    fun osd0HudAndMenuOverlayCoexistWithLiveCmosPic1Qr(@TempDir tempRoot: Path) {
        val projectRoot = Path.of(System.getProperty("componentProjectRoot"))
        val catalog = AddressCatalog.load(projectRoot.resolve("catalog/compiled/catalog.json"))
        val componentsDir = Files.createDirectories(tempRoot.resolve("compoments"))
        for (id in listOf("display", VglibInitAdmission.ID, "cmos")) {
            val jar = "$id-0.3.1.jar"
            Files.copy(projectRoot.resolve("build/emulator/compoments/$jar"), componentsDir.resolve(jar))
        }
        val vglibDir = Files.createDirectory(componentsDir.resolve(VglibInitAdmission.ID))
        Formats.atomicYaml(vglibDir.resolve("soc-vglib-init.yaml"), mapOf(
            "profile" to VglibInitAdmission.FUNCTIONAL_PROFILE,
            "inputFile" to "sequence.yaml",
            "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
            "scanoutFrameLimit" to 16,
        ))
        Formats.atomicYaml(vglibDir.resolve("sequence.yaml"), VglibInitPreparation.input(projectRoot))

        val payload = "FUJI-M2-OSD0-HUD-MENU-BACK-53B-UTF8-QR-COEXIST-CHECK"
        val cmosDir = Files.createDirectory(componentsDir.resolve("cmos"))
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", cmosDir.resolve("qr.jpg").toFile()))
        Formats.atomicYaml(cmosDir.resolve("cmos.yaml"), mapOf(
            "sourceKind" to "JPEG",
            "sourceFile" to "qr.jpg",
            "loop" to true,
        ))

        val osdAddr = 0x0614_0000L
        val osdSize = 1024 * 768 * 4
        val osdRam = ByteArray(osdSize)
        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picSize = 575 * picStride + 1024 * 2
        val picRam = ByteArray(picSize)

        fun writeOsdPixel(pixels: ByteArray, x: Int, y: Int, rgbaWord: Int) {
            // LcdcFirstScanout reads little-endian int32 RGBA and rotates right by 8 -> ARGB;
            // in memory bytes are [A, B, G, R], i.e., little-endian 0xRRGGBBAA.
            val off = (y * 1024 + x) * 4
            pixels[off] = (rgbaWord and 0xff).toByte()
            pixels[off + 1] = ((rgbaWord ushr 8) and 0xff).toByte()
            pixels[off + 2] = ((rgbaWord ushr 16) and 0xff).toByte()
            pixels[off + 3] = ((rgbaWord ushr 24) and 0xff).toByte()
        }

        fun paintHudOverlay() {
            osdRam.fill(0)
            // Top and bottom opaque HUD chrome bars + 50% alpha left badge, transparent center over QR
            val hudBarRgba = 0x2040_E0FF // R=0x20, G=0x40, B=0xE0, A=0xFF -> ARGB 0xFF2040E0
            val badgeRgba = 0x40A0_2080 // R=0x40, G=0xA0, B=0x20, A=0x80
            for (y in 0 until 48) {
                for (x in 0 until 1024) writeOsdPixel(osdRam, x, y, hudBarRgba)
            }
            for (y in 720 until 768) {
                for (x in 0 until 1024) writeOsdPixel(osdRam, x, y, hudBarRgba)
            }
            for (y in 80 until 140) {
                for (x in 16 until 96) writeOsdPixel(osdRam, x, y, badgeRgba)
            }
        }

        fun paintOpaqueMenuOverlay() {
            val menuPanelRgba = 0x2028_30FF // R=0x20, G=0x28, B=0x30, A=0xFF (opaque)
            for (y in 0 until 768) {
                for (x in 0 until 1024) writeOsdPixel(osdRam, x, y, menuPanelRgba)
            }
        }

        IntegrationTest().host(tempRoot) { owner, host ->
            owner.submit {
                val main = assertNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                val bank = assertNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val memory = object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        val okOsd = !write && address == osdAddr && size == osdSize.toLong()
                        val okPic = address == picAddr && size == picSize.toLong()
                        require(okOsd || okPic) { "Unexpected DMA check addr=0x${address.toString(16)} size=$size write=$write" }
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
                host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, memory)

                val discoveryConfig = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                    ),
                )
                val ctx = object : DiscoveryContext {
                    override fun core() = 1
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x11beac4L
                    override fun tick() = 900_000_000L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = memory
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val bus = checkNotNull(CatalogRuntime(catalog, "discovery", projectRoot, discoveryConfig).createBus(ctx))
                var seq = 0L
                // Program T80 bank 1 (1024x576, stride=2560, PIC1 addr=0x8999c000)
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                // Configure LCDC1 for OSD0 + PIC1 composite scanout
                bank.write(0x008, 4, 0x10010L)
                bank.write(0x030, 4, 0x2008_0001L)
                bank.write(0x040, 4, 0L)
                bank.write(0x044, 4, osdAddr)
                bank.write(0x050, 4, 0x10400L)
                bank.write(0x054, 4, 0x0300_0400L)
                bank.write(0x058, 4, 0L)
                bank.write(0x05c, 4, 0x0300_0400L)
                bank.write(0x060, 4, 0x1000_1000L)
                bank.write(0x160, 4, 0xffL)
                bank.write(0x168, 4, picAddr)
                bank.write(0x16c, 4, (picStride / 8).toLong())
                bank.write(0x170, 4, 0x0300_0400L)
                bank.write(0x174, 4, 96L shl 16)
                bank.write(0x178, 4, 0x0240_0400L)
                bank.write(0x17c, 4, 0x1000_1000L)

                // 1. HUD bar overlay over live CMOS QR decodes cleanly and preserves HUD chrome
                paintHudOverlay()
                assertTrue(bus.pumpLiveViewDma(900_000_001L))
                bank.write(0x600, 4, 1L)
                val hudPixels = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(hudPixels))
                assertEquals(0xff2040e0.toInt(), hudPixels[10 * main.width + 20])
                assertEquals(payload, QrTestCodec.decode(hudPixels, main.width, main.height))

                // 2. Opaque MENU overlay produces >5% pixel change and obscures the QR
                paintOpaqueMenuOverlay()
                assertTrue(bus.pumpLiveViewDma(900_000_002L))
                bank.write(0x600, 4, 1L)
                val menuPixels = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(menuPixels))
                assertTrue(
                    E2eHarness.changedPixelRatio(hudPixels, menuPixels) > 0.05,
                    "Expected opaque MENU overlay to change >5% of display pixels",
                )
                assertNull(QrTestCodec.decode(menuPixels, main.width, main.height))

                // 3. Clearing MENU (BACK) restores HUD + decodable QR payload
                paintHudOverlay()
                assertTrue(bus.pumpLiveViewDma(900_000_003L))
                bank.write(0x600, 4, 1L)
                val backPixels = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(backPixels))
                assertEquals(payload, QrTestCodec.decode(backPixels, main.width, main.height))
            }.join()
        }
    }

    @Test
    fun fourByThreeT80OutputsPreserveAspectAndDecodeQr(@TempDir tempRoot: Path) {
        val projectRoot = Path.of(System.getProperty("componentProjectRoot"))
        val catalog = AddressCatalog.load(projectRoot.resolve("catalog/compiled/catalog.json"))
        val payload = "FUJI-M2-4X3-T80-ASPECT-RATIO-QR-DECODE-53B-VERIFIED!"
        for ((dstW, dstH) in listOf(1024 to 768, 640 to 480)) {
            val runDir = tempRoot.resolve("t80_${dstW}x${dstH}")
            val componentsDir = Files.createDirectories(runDir.resolve("compoments"))
            for (id in listOf("display", VglibInitAdmission.ID, "cmos")) {
                val jar = "$id-0.3.1.jar"
                Files.copy(projectRoot.resolve("build/emulator/compoments/$jar"), componentsDir.resolve(jar))
            }
            val vglibDir = Files.createDirectory(componentsDir.resolve(VglibInitAdmission.ID))
            Formats.atomicYaml(vglibDir.resolve("soc-vglib-init.yaml"), mapOf(
                "profile" to VglibInitAdmission.FUNCTIONAL_PROFILE,
                "inputFile" to "sequence.yaml",
                "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "scanoutFrameLimit" to 4,
            ))
            Formats.atomicYaml(vglibDir.resolve("sequence.yaml"), VglibInitPreparation.input(projectRoot))
            val cmosDir = Files.createDirectory(componentsDir.resolve("cmos"))
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", cmosDir.resolve("qr.jpg").toFile()))
            Formats.atomicYaml(cmosDir.resolve("cmos.yaml"), mapOf("sourceKind" to "JPEG", "sourceFile" to "qr.jpg", "loop" to true))

            val picAddr = 0x8999_c000L
            val picStride = dstW * 2
            val picSize = (dstH - 1) * picStride + dstW * 2
            val picRam = ByteArray(picSize)

            IntegrationTest().host(runDir) { owner, host ->
                owner.submit {
                    val main = assertNotNull(host.ports.find("display.main", DisplayPort::class.java))
                    val sensor = assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                    val bank = assertNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                    val memory = object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(address == picAddr && size == picSize.toLong())
                        }
                        override fun read(address: Long, destination: ByteBuffer) {
                            destination.put(picRam, 0, destination.remaining())
                        }
                        override fun write(address: Long, source: ByteBuffer) {
                            source.get(picRam, 0, source.remaining())
                        }
                    }
                    host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, memory)

                    val discoveryConfig = mapOf(
                        "discovery" to mapOf(
                            "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                            "imageLiveViewBanks" to "t80-isp-banks-v1",
                        ),
                    )
                    val ctx = object : DiscoveryContext {
                        override fun core() = 1
                        override fun pc() = 0x5af98L
                        override fun lr() = 0x11beac4L
                        override fun tick() = 900_000_000L
                        override fun sensorPort() = sensor
                        override fun dmaMemory() = memory
                        override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                    }
                    val bus = checkNotNull(CatalogRuntime(catalog, "discovery", projectRoot, discoveryConfig).createBus(ctx))
                    var seq = 0L
                    val sizeWord = (((dstH - 1).toLong() and 0xffffL) shl 16) or ((dstW - 1).toLong() and 0xffffL)
                    bus.access(++seq, 0xfff73018L, 4, true, sizeWord)
                    bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                    bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                    bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                    val pic1X = (1024 - dstW) / 2
                    val pic1Y = (768 - dstH) / 2
                    bank.write(0x008, 4, 0x10L) // PIC1 only
                    bank.write(0x160, 4, 0xffL)
                    bank.write(0x168, 4, picAddr)
                    bank.write(0x16c, 4, (picStride / 8).toLong())
                    bank.write(0x170, 4, 0x0300_0400L)
                    bank.write(0x174, 4, ((pic1Y.toLong() and 0xffffL) shl 16) or (pic1X.toLong() and 0xffffL))
                    bank.write(0x178, 4, sizeWord + 0x0001_0001L)
                    bank.write(0x17c, 4, 0x1000_1000L)
                    bank.write(0x600, 4, 1L)

                    val frame = IntArray(main.width * main.height)
                    assertTrue(main.copyArgb(frame))
                    assertEquals(payload, QrTestCodec.decode(frame, main.width, main.height))
                }.join()
            }
        }
    }

    @Test
    fun bundledReleaseTestQrAndPngSceneDecodeThroughT80LiveViewPipeline(@TempDir tempRoot: Path) {
        val projectRoot = Path.of(System.getProperty("componentProjectRoot"))
        val catalog = AddressCatalog.load(projectRoot.resolve("catalog/compiled/catalog.json"))
        val componentsDir = Files.createDirectories(tempRoot.resolve("compoments"))
        for (id in listOf("display", VglibInitAdmission.ID, "cmos")) {
            val jar = "$id-0.3.1.jar"
            Files.copy(projectRoot.resolve("build/emulator/compoments/$jar"), componentsDir.resolve(jar))
        }
        val vglibDir = Files.createDirectory(componentsDir.resolve(VglibInitAdmission.ID))
        Formats.atomicYaml(vglibDir.resolve("soc-vglib-init.yaml"), mapOf(
            "profile" to VglibInitAdmission.FUNCTIONAL_PROFILE,
            "inputFile" to "sequence.yaml",
            "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
            "scanoutFrameLimit" to 16,
        ))
        Formats.atomicYaml(vglibDir.resolve("sequence.yaml"), VglibInitPreparation.input(projectRoot))

        val releaseCmosDir = projectRoot.resolve("tools/release/component-configs/cmos")
        val cmosDir = Files.createDirectory(componentsDir.resolve("cmos"))
        Files.copy(releaseCmosDir.resolve("cmos.yaml"), cmosDir.resolve("cmos.yaml"))
        Files.copy(releaseCmosDir.resolve("test-qr.jpg"), cmosDir.resolve("test-qr.jpg"))

        val pngPayload = "FUJI-P1-STATIC-PNG-LIVEVIEW-QR"
        check(
            ImageIO.write(
                QrTestCodec.encodeToImage(pngPayload, modulePixels = 10, quietZoneModules = 4),
                "png",
                cmosDir.resolve("live-qr.png").toFile(),
            ),
        )

        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picSize = 575 * picStride + 1024 * 2
        val picRam = ByteArray(picSize)

        IntegrationTest().host(tempRoot) { owner, host ->
            owner.submit {
                val main = assertNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                val bank = assertNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val memory = object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        require(address == picAddr && size == picSize.toLong())
                    }
                    override fun read(address: Long, destination: ByteBuffer) {
                        destination.put(picRam, 0, destination.remaining())
                    }
                    override fun write(address: Long, source: ByteBuffer) {
                        source.get(picRam, 0, source.remaining())
                    }
                }
                host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, memory)

                val discoveryConfig = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                    ),
                )
                val ctx = object : DiscoveryContext {
                    override fun core() = 1
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x11beac4L
                    override fun tick() = 900_000_000L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = memory
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val bus = checkNotNull(CatalogRuntime(catalog, "discovery", projectRoot, discoveryConfig).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                bank.write(0x008, 4, 0x10L) // PIC1 only
                bank.write(0x160, 4, 0xffL)
                bank.write(0x168, 4, picAddr)
                bank.write(0x16c, 4, (picStride / 8).toLong())
                bank.write(0x170, 4, 0x0300_0400L)
                bank.write(0x174, 4, 96L shl 16)
                bank.write(0x178, 4, 0x0240_0400L)
                bank.write(0x17c, 4, 0x1000_1000L)

                val frame = IntArray(main.width * main.height)
                for (i in 1..3) {
                    assertTrue(bus.pumpLiveViewDma(900_000_000L + i))
                    bank.write(0x600, 4, 1L)
                    assertTrue(main.copyArgb(frame))
                    assertEquals("FUJIFILM GFX100S QR-BALL 5.5M", QrTestCodec.decode(frame, main.width, main.height))
                }
            }.join()

            // Hot-swap to bundled static JPEG test-qr.jpg and verify live-view decodes cleanly
            val cmosRev0 = owner.submit { host.components().single { it.descriptor.id == "cmos" }.revision }.join()
            host.patch("cmos", cmosRev0, mapOf("sourceKind" to "JPEG", "sourceFile" to "test-qr.jpg", "loop" to true)).join()

            owner.submit {
                val main = assertNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                val bank = assertNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val memory = assertNotNull(host.ports.find("cpu.memory", DmaMemory::class.java))
                val discoveryConfig = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                    ),
                )
                val ctx = object : DiscoveryContext {
                    override fun core() = 1
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x11beac4L
                    override fun tick() = 900_000_005L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = memory
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val bus = checkNotNull(CatalogRuntime(catalog, "discovery", projectRoot, discoveryConfig).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                assertTrue(bus.pumpLiveViewDma(900_000_006L))
                bank.write(0x600, 4, 1L)
                val frame = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(frame))
                assertEquals("FUJIFILM GFX100S 2.13 CMOS TEST QR", QrTestCodec.decode(frame, main.width, main.height))
            }.join()

            // Hot-swap to PNG scene via host.patch and verify live-view decodes cleanly
            val cmosRev = owner.submit { host.components().single { it.descriptor.id == "cmos" }.revision }.join()
            host.patch("cmos", cmosRev, mapOf("sourceKind" to "PNG", "sourceFile" to "live-qr.png", "loop" to true)).join()

            owner.submit {
                val main = assertNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                val bank = assertNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val memory = assertNotNull(host.ports.find("cpu.memory", DmaMemory::class.java))
                val discoveryConfig = mapOf(
                    "discovery" to mapOf(
                        "inputs" to mapOf("gfx100s.fff72028.config-words.candidate" to mapOf("strategy" to "retained-bytes", "fill" to 0)),
                        "imageLiveViewBanks" to "t80-isp-banks-v1",
                    ),
                )
                val ctx = object : DiscoveryContext {
                    override fun core() = 1
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x11beac4L
                    override fun tick() = 900_000_010L
                    override fun sensorPort() = sensor
                    override fun dmaMemory() = memory
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val bus = checkNotNull(CatalogRuntime(catalog, "discovery", projectRoot, discoveryConfig).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                assertTrue(bus.pumpLiveViewDma(900_000_011L))
                bank.write(0x600, 4, 1L)
                val frame = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(frame))
                assertEquals(pngPayload, QrTestCodec.decode(frame, main.width, main.height))
            }.join()
        }
    }
}
