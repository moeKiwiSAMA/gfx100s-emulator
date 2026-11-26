package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.BlockStoragePort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.ValueKind
import com.github.nyabanban.fuji.emulator.desktop.AdditionalRamPlan
import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.firmware.research.BootRomEntryResearch
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tier 3: Cross-Feature Pairwise Combinatorial Tests (10 tests covering F1-F9 pairs).
 */
@Tag("e2e")
@Tag("e2e-tier3")
class E2eTier3CrossFeaturePairwiseTest {

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun t3_01_F1_F3_BlankCardShootingStandbyToMenuOkAndBack(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing assets",
        )
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "T3-F1+F3", ready, "M1 shooting HUD & MENU/OK not yet merged")

        val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            home,
            E2eHarness.probeSourceCommit(home),
            E2eHarness.blankCardSnapshotDir,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "632000000:menu:down,632500000:menu:up,636000000:back:down,636500000:back:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val frames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(frames.size >= 3, "Expected standby, MENU, and post-BACK standby frames, got ${frames.size}")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(frames.first()))
        val lastPx = E2eHarness.toArgbPixels(E2eHarness.readPng(frames.last()))
        assertTrue(E2eHarness.nonBlackRatio(firstPx) > 0.02 && E2eHarness.distinctRgbCount(firstPx) > 16)
        assertTrue(E2eHarness.nonBlackRatio(lastPx) > 0.02 && E2eHarness.distinctRgbCount(lastPx) > 16)
        val anyMenuChange = frames.drop(1).dropLast(1).any {
            E2eHarness.changedPixelRatio(firstPx, E2eHarness.toArgbPixels(E2eHarness.readPng(it))) > 0.05
        } || E2eHarness.changedPixelRatio(firstPx, lastPx) > 0.01
        assertTrue(anyMenuChange, "Expected visible LCD transition during MENU/BACK sequence")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun t3_02_F2_F3_Lcdc1CompositeWithActiveMenuOverlay(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing runtime or blank-card snapshot assets",
        )
        val ready = E2eHarness.probeLcdcYuvCompositeReady(tempRoot.resolve("probe-lcdc")) &&
            E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe-m1"))
        E2eGates.assumeOrFailFeature("M1", "T3-F2+F3", ready, "M1 LCDC1 YUV+OSD0 + MENU/OK not yet merged")

        val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            home,
            E2eHarness.probeSourceCommit(home),
            E2eHarness.blankCardSnapshotDir,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "632000000:menu:down,632500000:menu:up,636000000:menu:down,636500000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val frames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(frames.size >= 2, "Expected baseline and MENU/OK composite frames, got ${frames.size}")
        val preMenu = E2eHarness.toArgbPixels(E2eHarness.readPng(frames.first()))
        val postMenu = frames.drop(1).map { E2eHarness.toArgbPixels(E2eHarness.readPng(it)) }
        assertTrue(postMenu.all { E2eHarness.nonBlackRatio(it) > 0.05 && E2eHarness.distinctRgbCount(it) > 24 })
        assertTrue(postMenu.any { E2eHarness.changedPixelRatio(preMenu, it) > 0.05 }, "MENU/OK must alter composited OSD0+YUV output")
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun t3_03_F1_F4_ShootingStandbyWithCmosQrLiveViewDecode(@TempDir tempRoot: Path) {
        val cmosReady = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe-cmos"))
        E2eGates.assumeOrFailFeature("M2", "T3-F1+F4", cmosReady, "M2 CMOS QR liveview chain not yet active")

        val payload = "FUJI-GFX100S-T3-QR"
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val liveReady = fw != null && lib != null &&
            Files.isDirectory(E2eHarness.blankCardHomeDir) &&
            Files.isDirectory(E2eHarness.blankCardSnapshotDir) &&
            E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe-m1"))

        if (liveReady) {
            val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
            val qrPath = home.resolve("compoments/cmos/t3-qr.jpg")
            Files.createDirectories(qrPath.parent)
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))
            Formats.atomicYaml(home.resolve("compoments/cmos/cmos.yaml"), mapOf("sourceKind" to "JPEG", "sourceFile" to "t3-qr.jpg", "loop" to true))
            val framesDir = tempRoot.resolve("frames")
            Files.createDirectories(framesDir)
            val res = E2eHarness.runDisplayBringupResume(
                home,
                E2eHarness.probeSourceCommit(home),
                E2eHarness.blankCardSnapshotDir,
                listOf(
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--budget", "640000000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            assertEquals(0, res.exitCode, res.stderr)
            val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.toList() }
            assertTrue(pngs.isNotEmpty())
            val decoded = pngs.mapNotNull { QrTestCodec.decode(E2eHarness.readPng(it)) }
            assertTrue(payload in decoded, "Expected decoded QR '$payload', got $decoded")
            return
        }

        // Deterministic real-component standby HUD (OSD0) + CMOS QR (PIC1) composite on display.main
        val home = E2eHarness.stageCmosLiveViewComponentHome(
            tempRoot.resolve("home"),
            mapOf("sourceKind" to "JPEG", "sourceFile" to "t3-qr.jpg", "loop" to true),
        )
        val qrPath = home.resolve("compoments/cmos/t3-qr.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))

        val catalog = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.load(E2eHarness.projectRoot.resolve("catalog/compiled/catalog.json"))
        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picRam = ByteArray(575 * picStride + 1024 * 2)
        val osdAddr = 0x0614_0000L
        val osdRam = ByteArray(1024 * 768 * 4)
        E2eHarness.paintShootingHudOsd0(osdRam)

        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = checkNotNull(host.ports.find("cmos", com.github.nyabanban.fuji.emulator.api.SensorPort::class.java))
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java))
                val dma = object : com.github.nyabanban.fuji.emulator.api.DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        val okPic = address == picAddr && size == picRam.size.toLong()
                        val okOsd = !write && address == osdAddr && size == osdRam.size.toLong()
                        require(okPic || okOsd) { "Unexpected DMA 0x${address.toString(16)} size=$size" }
                    }
                    override fun read(address: Long, destination: java.nio.ByteBuffer) {
                        if (address == osdAddr) destination.put(osdRam, 0, destination.remaining())
                        else destination.put(picRam, 0, destination.remaining())
                    }
                    override fun write(address: Long, source: java.nio.ByteBuffer) {
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
                val bus = checkNotNull(com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime(catalog, "discovery", E2eHarness.projectRoot, discoveryCfg).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = true)
                assertTrue(bus.pumpLiveViewDma(631_000_001L))
                lcdc1.write(0x600, 4, 1L)

                val px = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(px))
                assertEquals(0xff2040e0.toInt(), px[10 * main.width + 20], "Expected standby HUD top bar pixel")
                assertEquals(payload, QrTestCodec.decode(px, main.width, main.height))
            }.join()
        }
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun t3_04_F3_F4_MenuOverlayOverCmosQrAndReturnToDecodableLiveView(@TempDir tempRoot: Path) {
        val cmosReady = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe-cmos"))
        E2eGates.assumeOrFailFeature("M2", "T3-F3+F4", cmosReady, "M2 CMOS QR + MENU overlay not yet active")

        val payload = "FUJI-T3-MENU-QR"
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val liveReady = fw != null && lib != null &&
            Files.isDirectory(E2eHarness.blankCardHomeDir) &&
            Files.isDirectory(E2eHarness.blankCardSnapshotDir) &&
            E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe-m1")) &&
            E2eHarness.probeM1BlankCh21Ready(tempRoot.resolve("probe-ch21"))

        if (liveReady) {
            val (home, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
            val qrPath = home.resolve("compoments/cmos/t3-menu-qr.jpg")
            Files.createDirectories(qrPath.parent)
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))
            Formats.atomicYaml(home.resolve("compoments/cmos/cmos.yaml"), mapOf("sourceKind" to "JPEG", "sourceFile" to "t3-menu-qr.jpg", "loop" to true))
            val framesDir = tempRoot.resolve("frames")
            Files.createDirectories(framesDir)
            val res = E2eHarness.runDisplayBringupResume(
                home,
                E2eHarness.probeSourceCommit(home),
                E2eHarness.blankCardSnapshotDir,
                listOf(
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--button-events", "632000000:menu:down,632500000:menu:up,636000000:back:down,636500000:back:up",
                    "--budget", "645000000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            assertEquals(0, res.exitCode, res.stderr)
            val framesMeta = Formats.json.readTree(home.resolve("bringup.frames.json").toFile()).path("frames")
            val initialFrames = framesMeta.filter { it.path("attempts").asLong() < 632_000_000L }.map { Path.of(it.path("file").asText()) }
            val menuFrames = framesMeta.filter { it.path("attempts").asLong() in 632_500_000L..635_999_999L }.map { Path.of(it.path("file").asText()) }
            val postBackFrames = framesMeta.filter { it.path("attempts").asLong() >= 636_500_000L }.map { Path.of(it.path("file").asText()) }
            assertTrue(initialFrames.isNotEmpty() && menuFrames.isNotEmpty() && postBackFrames.isNotEmpty())
            val basePx = E2eHarness.toArgbPixels(E2eHarness.readPng(initialFrames.last()))
            assertTrue(menuFrames.any { E2eHarness.changedPixelRatio(basePx, E2eHarness.toArgbPixels(E2eHarness.readPng(it))) > 0.05 }, "MENU overlay must visually change live-view frame")
            val postBackDecoded = postBackFrames.mapNotNull { QrTestCodec.decode(E2eHarness.readPng(it)) }
            assertTrue(payload in postBackDecoded, "Post-BACK live-view must decode QR '$payload', got $postBackDecoded")
            return
        }

        // Deterministic real-component HUD + MENU overlay + BACK restore over live CMOS PIC1 QR
        val home = E2eHarness.stageCmosLiveViewComponentHome(
            tempRoot.resolve("home"),
            mapOf("sourceKind" to "JPEG", "sourceFile" to "t3-menu-qr.jpg", "loop" to true),
        )
        val qrPath = home.resolve("compoments/cmos/t3-menu-qr.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrPath.toFile()))

        val catalog = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.load(E2eHarness.projectRoot.resolve("catalog/compiled/catalog.json"))
        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picRam = ByteArray(575 * picStride + 1024 * 2)
        val osdAddr = 0x0614_0000L
        val osdRam = ByteArray(1024 * 768 * 4)

        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = checkNotNull(host.ports.find("cmos", com.github.nyabanban.fuji.emulator.api.SensorPort::class.java))
                val buttons = checkNotNull(host.ports.find("buttons", com.github.nyabanban.fuji.emulator.api.ButtonPort::class.java))
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java))
                val dma = object : com.github.nyabanban.fuji.emulator.api.DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        val okPic = address == picAddr && size == picRam.size.toLong()
                        val okOsd = !write && address == osdAddr && size == osdRam.size.toLong()
                        require(okPic || okOsd) { "Unexpected DMA 0x${address.toString(16)} size=$size" }
                    }
                    override fun read(address: Long, destination: java.nio.ByteBuffer) {
                        if (address == osdAddr) destination.put(osdRam, 0, destination.remaining())
                        else destination.put(picRam, 0, destination.remaining())
                    }
                    override fun write(address: Long, source: java.nio.ByteBuffer) {
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
                val bus = checkNotNull(com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime(catalog, "discovery", E2eHarness.projectRoot, discoveryCfg).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = true)

                // 1. Live HUD + QR
                E2eHarness.paintShootingHudOsd0(osdRam)
                assertTrue(bus.pumpLiveViewDma(631_000_001L))
                lcdc1.write(0x600, 4, 1L)
                val initialPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(initialPx))
                assertEquals(payload, QrTestCodec.decode(initialPx, main.width, main.height))

                // 2. Press MENU button -> opaque MENU OSD0 overlay obscures live QR
                val btnRev1 = host.components().single { it.descriptor.id == "buttons" }.revision
                host.patch("buttons", btnRev1, mapOf("menu" to true)).join()
                assertTrue(buttons.pressed("menu"))
                E2eHarness.paintOpaqueMenuOsd0(osdRam)
                assertTrue(bus.pumpLiveViewDma(632_000_001L))
                lcdc1.write(0x600, 4, 1L)
                val menuPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(menuPx))
                assertTrue(E2eHarness.changedPixelRatio(initialPx, menuPx) > 0.05, "MENU overlay must visually change live-view frame")
                assertEquals(null, QrTestCodec.decode(menuPx, main.width, main.height))
                host.patch("buttons", btnRev1 + 1, mapOf("menu" to false)).join()

                // 3. Press BACK button -> restore HUD + decodable live QR
                host.patch("buttons", btnRev1 + 2, mapOf("back" to true)).join()
                assertTrue(buttons.pressed("back"))
                E2eHarness.paintShootingHudOsd0(osdRam)
                assertTrue(bus.pumpLiveViewDma(636_500_001L))
                lcdc1.write(0x600, 4, 1L)
                val postBackPx = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(postBackPx))
                assertEquals(payload, QrTestCodec.decode(postBackPx, main.width, main.height))
                host.patch("buttons", btnRev1 + 3, mapOf("back" to false)).join()
            }.join()
        }
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun t3_05_F1_F5_PhotoCardT14ReadyToPlayDscf0001Playback(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing runtime or photo-card snapshot assets",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "T3-F1+F5", ready, "M3 PLAY JPEG decode pipeline not yet merged")

        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val (home, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            home,
            E2eHarness.probeSourceCommit(home),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "581000000:play:down,581500000:play:up",
                "--budget", "589000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val frames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(frames.size >= 2, "Expected shooting and PLAY playback frames, got ${frames.size}")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(frames.first()))
        val lastImg = E2eHarness.readPng(frames.last())
        val lastPx = E2eHarness.toArgbPixels(lastImg)
        assertTrue(E2eHarness.nonBlackRatio(lastPx) > 0.10 && E2eHarness.distinctRgbCount(lastPx) > 32)
        assertTrue(E2eHarness.changedPixelRatio(firstPx, lastPx) > 0.05)
        assertTrue(frames.any { E2eHarness.maxPlayCorrelation(E2eHarness.readPng(it), refImg) >= 0.70 }, "Expected DSCF0001.JPG correlation >= 0.70")
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun t3_06_F5_F6_PlayDscf0001AcrossEvfAndShoulderDisplayLinkage(@TempDir tempRoot: Path) {
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
        E2eGates.assumeOrFailFeature("M4", "T3-F5+F6", ready, "M3 PLAY + M4 EVF linkage not yet merged")

        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val eyeCtrl = PanelCatalog.load().flatMap { it.controls }.single { it.id == "rear-eye-sensor" }
        val (home, ramPlanPath) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val cfg = Formats.read(home.resolve("config/emulator.yaml")) + mapOf("httpPort" to 0)
        EmulatorRuntime(home, cfg).use { rt ->
            rt.serve()
            val port = checkNotNull(rt.server).address.port
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    E2eHarness.photoCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 592_000_000L,
                        allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                rt.session.extendAdditionalRam(AdditionalRamPlan.read(ramPlanPath))
            }.join()
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                fun patchComp(id: String, changes: Map<String, Any?>) {
                    val rev = client.getStateJson()["components"].single { it["id"].asText() == id }["revision"].asLong()
                    assertEquals(200, client.patchComponent(id, rev, changes).statusCode())
                }
                while (rt.owner.submit { rt.session.checkpointAttempts() < 581_000_000L && rt.session.step() }.join()) {}
                patchComp("buttons", mapOf("play" to true))
                repeat(4) { rt.owner.submit { rt.session.step() }.join() }
                patchComp("buttons", mapOf("play" to false))
                while (rt.owner.submit { rt.session.checkpointAttempts() < 585_000_000L && rt.session.step() }.join()) {}

                val (mainImg, shoulderState1) = rt.owner.submit {
                    val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                    val px = IntArray(main.width * main.height)
                    val copied = main.copyArgb(px)
                    val img = if (copied) BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
                        setRGB(0, 0, main.width, main.height, px, 0, main.width)
                    } else null
                    val shoulder = rt.host.components().single { it.descriptor.id == "shoulder-display" }.component.state()
                    img to shoulder
                }.join()
                assertTrue(mainImg != null && E2eHarness.maxPlayCorrelation(mainImg, refImg) >= 0.70, "Expected DSCF0001 playback on display.main")
                assertTrue((shoulderState1["sequence"] as Number).toLong() >= 0L)

                val eyeId = eyeCtrl.componentId ?: "buttons"
                val eyeKey = eyeCtrl.settingKey ?: "eyeSensor"
                val eyeSetting = rt.owner.submit {
                    rt.host.components().single { it.descriptor.id == eyeId }.descriptor.settings.single { it.key == eyeKey }
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
                while (rt.owner.submit { rt.session.checkpointAttempts() < 589_000_000L && rt.session.step() }.join()) {}

                val (evfPx, shoulderState2) = rt.owner.submit {
                    val evf = checkNotNull(rt.host.ports.find("display.evf", DisplayPort::class.java))
                    val px = IntArray(evf.width * evf.height)
                    val copied = evf.copyArgb(px)
                    val shoulder = rt.host.components().single { it.descriptor.id == "shoulder-display" }.component.state()
                    (if (copied) px else null) to shoulder
                }.join()
                assertTrue(evfPx != null && E2eHarness.nonBlackRatio(evfPx) > 0.05 && E2eHarness.distinctRgbCount(evfPx) > 32, "Expected active playback on display.evf when eye sensor is near")
                assertTrue((shoulderState2["sequence"] as Number).toLong() >= (shoulderState1["sequence"] as Number).toLong())
            }
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun t3_07_F7_F1_InCameraFat32FormatThenMountInShootingStandby(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing runtime or blank-card snapshot assets",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "T3-F7+F1", ready, "M5 in-camera FAT32 format not yet merged")

        val srcSd = E2eHarness.blankCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val srcSha = E2eHarness.sha256(srcSd)

        // Phase A: Format SD in-camera and capture post-format snapshot
        val (homeA, ramPlanA) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home-a"), overrideSdReadOnly = false)
        val framesA = tempRoot.resolve("frames-a")
        val postFormatSnap = tempRoot.resolve("post-format-snap")
        val formatDone = System.getProperty("fuji.e2e.formatDoneAttempts")?.toLongOrNull()
            ?: System.getProperty("fuji.e2e.formatBudget")?.toLongOrNull()
            ?: 655_000_000L
        val resA = E2eHarness.runInCameraFormat(homeA, framesA, savePostFormatSnapshotDir = postFormatSnap, formatDoneAttempts = formatDone, ramPlan = ramPlanA)
        assertEquals(0, resA.exitCode, resA.stderr)
        val sdImgA = homeA.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val fatA = E2eHarness.inspectFatFilesystem(sdImgA)
        assertTrue(fatA.fsType.startsWith("FAT32"))
        assertEquals(512, fatA.bytesPerSector)
        assertTrue(fatA.fat0MediaByte in setOf(0xF8, 0xF0))

        // Phase B: Mount formatted image in fresh host AND resume postFormatSnap into shooting standby
        val mountHome = E2eHarness.cloneBringupHome(homeA, tempRoot.resolve("mount-home"), copySdImage = false, overrideSdReadOnly = false)
        val sdImgMount = mountHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eHarness.copySparsePreserving(sdImgA, sdImgMount)
        E2eHarness.withComponentHost(mountHome) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                val mbr = ByteBuffer.allocate(512)
                assertEquals(512, sd.read(1, 0L, mbr))
                assertEquals(0x55.toByte(), mbr.get(510))
                assertEquals(0xAA.toByte(), mbr.get(511))
            }.join()
        }
        val framesB = tempRoot.resolve("frames-b")
        Files.createDirectories(framesB)
        val menuDown = formatDone + 2_000_000L
        val menuUp = formatDone + 2_500_000L
        val backDown = formatDone + 5_000_000L
        val backUp = formatDone + 5_500_000L
        val resB = E2eHarness.runDisplayBringupResume(
            mountHome,
            E2eHarness.probeSourceCommit(mountHome),
            postFormatSnap,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--button-events", "$menuDown:menu:down,$menuUp:menu:up,$backDown:back:down,$backUp:back:up",
                "--budget", (formatDone + 10_000_000L).toString(),
                "--export-frames-dir", framesB.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, resB.exitCode, resB.stderr)
        val pngsB = Files.list(framesB).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngsB.size >= 2, "Expected standby/MENU frames on remounted formatted card")
        val firstB = E2eHarness.toArgbPixels(E2eHarness.readPng(pngsB.first()))
        assertTrue(E2eHarness.nonBlackRatio(firstB) > 0.02 && E2eHarness.distinctRgbCount(firstB) > 16)
        assertEquals(srcSha, E2eHarness.sha256(srcSd))
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun t3_08_F8_F9_BootRomColdStartUnderStrictExecutionMode(@TempDir tempRoot: Path) {
        val archive = E2eHarness.resolveBootRomArchive()
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GFX100S_DUMP+GXUP0004+libunicorn", archive != null && fw != null && lib != null, "Missing GFX100S_DUMP.zip, GXUP0004.DAT, or libunicorn.so.2")
        val ready = E2eHarness.probeBootRomProfileReady()
        E2eGates.assumeOrFailFeature("M5", "T3-F8+F9", ready, "M5 Boot ROM cold-start profile not yet merged")

        val research = BootRomEntryResearch.analyze(E2eHarness.projectRoot, E2eHarness.projectRoot)
        @Suppress("UNCHECKED_CAST")
        val inputInfo = research["input"] as Map<String, Any?>
        assertEquals("2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4", inputInfo["entrySha256"])

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
            "executionMode" to "strict",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to E2eHarness.resolveBootRomMetadataPath().toString(),
            "firmwareFile" to fw!!.toString(),
            "bootRomFile" to "boot-rom.bin",
            "nativeLibrary" to lib!!.toString(),
            "instructionBudget" to 100000,
            "enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"),
        )
        EmulatorRuntime(home, cfg).use { rt ->
            rt.serve()
            val port = checkNotNull(rt.server).address.port
            val probe = rt.probe()
            assertEquals(true, probe["bootRomExecuted"])
            assertTrue((probe["attempts"] as Number).toLong() > 0L)
            assertEquals("unmapped MMIO 0xfff41500 size 4", probe["status"])
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                val unknownNode = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                assertEquals("strict", unknownNode["executionMode"].asText())
                assertEquals(0, unknownNode["accesses"].size())
                val dumpResp = client.postEmptyJson("/api/v1/dump")
                assertEquals(201, dumpResp.statusCode())
                val dumpZip = home.resolve("dumps").resolve(Formats.json.readTree(dumpResp.body())["dump"].asText())
                assertTrue(Files.isRegularFile(dumpZip))
                ZipFile(dumpZip.toFile()).use { z -> assertTrue(z.entries().asSequence().any()) }
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun t3_09_F6_F9_HttpLiveControlPatchAndCatalogQueryWithActiveHost(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        Formats.atomicYaml(
            home.resolve("config/emulator.yaml"),
            mapOf(
                "schema" to 1,
                "executionMode" to "strict",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "not-loaded.yaml",
                "firmwareFile" to "",
                "nativeLibrary" to "",
                "instructionBudget" to 1000,
                "enabledDevices" to emptyList<String>(),
            ),
        )
        E2eHarness.startHeadlessServer(home).use { srv ->
            srv.client().use { client ->
                val state0 = client.getStateJson()
                val batRev = state0["components"].single { it["id"].asText() == "battery" }["revision"].asLong()
                val btnRev = state0["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()

                assertEquals(200, client.patchComponent("battery", batRev, mapOf("chargePercent" to 64.5)).statusCode())
                assertEquals(200, client.patchComponent("buttons", btnRev, mapOf("menu" to true, "shutterHalf" to true)).statusCode())

                val state1 = client.getStateJson()
                assertEquals(64.5, state1["components"].single { it["id"].asText() == "battery" }["state"]["chargePercent"].asDouble())
                assertEquals(true, state1["components"].single { it["id"].asText() == "buttons" }["state"]["menu"].asBoolean())

                val dumpResp = client.postEmptyJson("/api/v1/dump")
                assertEquals(201, dumpResp.statusCode())
                val dumpName = Formats.json.readTree(dumpResp.body())["dump"].asText()
                val dumpZip = home.resolve("dumps").resolve(dumpName)
                assertTrue(Files.isRegularFile(dumpZip))
                ZipFile(dumpZip.toFile()).use { zip ->
                    assertTrue(zip.entries().asSequence().any())
                }

                val catResp = client.getCatalogJson("address=0xFFFA9040&width=4&direction=write")
                assertEquals("strict", catResp["executionMode"].asText())
                assertFalse(catResp["deviceReadPerformed"].asBoolean())
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun t3_10_F1_F9_ExternalSnapshotRestoreInStrictVsDiscoveryBaseline(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GXUP0004+libunicorn", fw != null && lib != null, "Missing GXUP0004.DAT or libunicorn.so.2")

        val results = mutableMapOf<String, Map<String, Any?>>()
        for (mode in listOf("strict", "discovery")) {
            val home = E2eHarness.stageComponentHome(tempRoot.resolve(mode))
            val cfg = mapOf(
                "schema" to 1,
                "executionMode" to mode,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to fw!!.toString(),
                "nativeLibrary" to lib!!.toString(),
                "instructionBudget" to 100000,
                "enabledDevices" to listOf("soc-gpio", "soc-timer"),
            )
            EmulatorRuntime(home, cfg).use { rt ->
                results[mode] = rt.probe()
            }
        }
        val strictRes = results.getValue("strict")
        val discRes = results.getValue("discovery")
        assertEquals(strictRes["attempts"], discRes["attempts"])
        assertEquals(strictRes["registers"], discRes["registers"])
        // A missing MMIO window enters the ARM data-abort vector in both guest sessions.
        for (result in results.values) {
            assertEquals("instruction-limit", result["status"])
            assertNull(result["frontier"])
            val regs = assertIs<Map<*, *>>(result["registers"])
            assertEquals(0x17L, (regs["cpsr"] as Number).toLong() and 0x1fL)
        }

        // A mapped GIC with SGI disabled reaches a host-observable refusal in both modes.
        val frontiers = listOf("strict", "discovery").map { mode ->
            val (home, config) = E2eHarness.stageDiscoveryBootHome(
                tempRoot.resolve("mapped-$mode"), instructionBudget = 100000, enableBootSgi = false,
            )
            EmulatorRuntime(home, config + ("executionMode" to mode)).use { rt ->
                val frontier = assertIs<Map<*, *>>(rt.probe()["frontier"])
                assertEquals(0xffff1f00L, frontier["addressPhysical"])
                assertEquals(0x01000001L, frontier["value"])
                frontier
            }
        }
        assertEquals(frontiers.first(), frontiers.last())

        // Verify CLI --query-address matches strict/discovery expectations
        val cliHome = E2eHarness.stageComponentHome(tempRoot.resolve("cli-parity"))
        Formats.atomicYaml(
            cliHome.resolve("config/emulator.yaml"),
            mapOf("schema" to 1, "executionMode" to "strict", "components" to "compoments", "httpPort" to 0, "firmwareMetadata" to "unused.yaml", "firmwareFile" to "", "nativeLibrary" to "", "instructionBudget" to 1000, "enabledDevices" to emptyList<String>()),
        )
        val cli = E2eHarness.runMainCli(cliHome, listOf("--query-address", "0xFFF4A400", "--width", "4", "--direction", "write"))
        assertEquals(0, cli.exitCode, cli.stderr)
        val node = Formats.json.readTree(cli.stdout)
        assertEquals("strict", node["executionMode"].asText())
        assertEquals("REGISTER", node["classification"].asText())
    }
}
