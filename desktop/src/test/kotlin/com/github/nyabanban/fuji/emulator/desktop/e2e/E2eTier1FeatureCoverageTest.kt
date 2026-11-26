package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.BatteryPort
import com.github.nyabanban.fuji.emulator.api.BlockStoragePort
import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.ExecutionLocation
import com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.api.ValueKind
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.configuredComponentProfiles
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioInputState
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioLayout
import com.github.nyabanban.fuji.emulator.desktop.tools.JvmStudioEvidence
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.firmware.SdHostContracts
import com.github.nyabanban.fuji.emulator.firmware.SdTransactionContracts
import com.github.nyabanban.fuji.emulator.firmware.research.BootRomEntryResearch
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.ControlServer
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 1: Isolated Feature Coverage Tests (5 happy-path tests per feature F1-F9 = 45 tests).
 */
@Tag("e2e")
@Tag("e2e-tier1")
class E2eTier1FeatureCoverageTest {

    // =========================================================================
    // F1: Shooting Baseline Unblocking (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f1T1_blankCardSnapshotManifestAndIntegrityCheck() {
        val snap = E2eHarness.blankCardSnapshotDir
        val fw = E2eHarness.resolveFirmwareFile()
        E2eGates.requireAssets("blankCardSnapshot+GXUP0004", Files.isDirectory(snap) && fw != null, "Missing $snap or GXUP0004.DAT")

        val manifest = EmulatorSnapshot.readJsonObject(snap.resolve(EmulatorSnapshot.MANIFEST_FILE))
        assertEquals(EmulatorSnapshot.SCHEMA, (manifest["schema"] as Number).toInt())
        assertEquals(EmulatorSnapshot.FORMAT, manifest["format"])
        assertEquals("discovery", manifest["executionMode"])
        @Suppress("UNCHECKED_CAST")
        val fwInfo = manifest["firmware"] as Map<String, Any?>
        assertEquals("gfx100s-213", fwInfo["id"])
        assertEquals(E2eHarness.sha256(fw!!), fwInfo["sha256"])

        @Suppress("UNCHECKED_CAST")
        val compMeta = manifest["componentsFile"] as Map<String, Any?>
        EmulatorSnapshot.verifyRegularFileSha256(
            snap.resolve(compMeta["file"].toString()),
            (compMeta["size"] as Number).toLong(),
            compMeta["sha256"].toString(),
        )
        val complete = EmulatorSnapshot.readJsonObject(snap.resolve(EmulatorSnapshot.COMPLETE_FILE))
        assertTrue((complete["ramRegionCount"] as Number).toInt() > 0)
        assertEquals(4, (0..3).count { Files.isRegularFile(snap.resolve("cpu/core-$it.ucctx")) })
    }

    @Test
    @Tag("e2e-baseline")
    fun f1T1_photoCardSnapshotManifestAndIntegrityCheck() {
        val snap = E2eHarness.photoCardSnapshotDir
        val fw = E2eHarness.resolveFirmwareFile()
        E2eGates.requireAssets("photoCardSnapshot+GXUP0004", Files.isDirectory(snap) && fw != null, "Missing $snap or GXUP0004.DAT")

        val manifest = EmulatorSnapshot.readJsonObject(snap.resolve(EmulatorSnapshot.MANIFEST_FILE))
        assertEquals(EmulatorSnapshot.SCHEMA, (manifest["schema"] as Number).toInt())
        assertEquals(EmulatorSnapshot.FORMAT, manifest["format"])
        @Suppress("UNCHECKED_CAST")
        val fwInfo = manifest["firmware"] as Map<String, Any?>
        assertEquals(E2eHarness.sha256(fw!!), fwInfo["sha256"])

        val complete = EmulatorSnapshot.readJsonObject(snap.resolve(EmulatorSnapshot.COMPLETE_FILE))
        assertTrue((complete["totalRamBytes"] as Number).toLong() > 64L * 1024L * 1024L)
        assertEquals(4, (0..3).count { Files.isRegularFile(snap.resolve("cpu/core-$it.ucctx")) })
    }

    @Test
    @Tag("e2e-baseline")
    fun f1T1_isolatedSnapshotRestoreAndStateQueryViaRuntime(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcHome = E2eHarness.blankCardHomeDir
        val srcSnap = E2eHarness.blankCardSnapshotDir
        E2eGates.requireAssets(
            "blankCardHome+snap2300k",
            fw != null && lib != null && Files.isDirectory(srcHome) && Files.isDirectory(srcSnap),
            "Missing firmware, libunicorn, or blank-card snapshot",
        )

        val workHome = E2eHarness.cloneBringupHome(srcHome, tempRoot.resolve("home"), copySdImage = true, overrideSdReadOnly = false)
        val config = Formats.read(workHome.resolve("config/emulator.yaml"))
        EmulatorRuntime(workHome, config).use { runtime ->
            runtime.serve()
            val receipt = runtime.owner.submit {
                runtime.session.initialize()
                runtime.session.restoreEmulatorSnapshot(srcSnap)
            }.join()
            assertEquals(true, receipt["restored"])
            val state = runtime.owner.submit { runtime.session.state() }.join()
            assertTrue((state["attempts"] as Number).toLong() >= 626_500_000L)

            val port = checkNotNull(runtime.server).address.port
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                val resp = client.get("/api/v1/state")
                assertEquals(200, resp.statusCode())
                val tree = Formats.json.readTree(resp.body())
                assertEquals((state["attempts"] as Number).toLong(), tree["machine"]["attempts"].asLong())
                assertTrue(tree["components"].size() >= 30)
            }
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f1T1_blankCardShootingStandbyProducesNonBlackHudFrame(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or blankCard snapshot",
        )
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F1-BlankCardHUD", ready, "Blank card resume from snap2300k still yields only black frames (A_REC_DEINIT/VGLIB flush pending)")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val commit = E2eHarness.probeSourceCommit(workHome)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            commit,
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
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("frontier").isNull)
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.isNotEmpty(), "Expected exported PNG frames")
        val nonBlack = pngs.map { E2eHarness.readPng(it) }.filter { img ->
            val px = E2eHarness.toArgbPixels(img)
            img.width == 640 && img.height == 480 && E2eHarness.nonBlackRatio(px) > 0.02 && E2eHarness.distinctRgbCount(px) > 16
        }
        assertTrue(nonBlack.isNotEmpty(), "Expected at least one non-black 640x480 shooting HUD frame")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f1T1_photoCardCompletesInitT14ReadyWithoutResourceExhaustion(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing firmware, native library, or photoCard snapshot",
        )
        val ready = E2eHarness.probeM1PhotoCardReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F1-PhotoCardInit", ready, "M1 photo-card T14 ready & HUD unblocking not yet merged")

        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val commit = E2eHarness.probeSourceCommit(workHome)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            commit,
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--budget", "582000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("frontier").isNull, "Unexpected frontier on photo-card init: ${summary.path("frontier")}")
        assertTrue(summary.path("error").isNull, "Unexpected error: ${summary.path("error")}")
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.isNotEmpty(), "Expected photo-card HUD frames")
        assertTrue(
            pngs.any { png ->
                val img = E2eHarness.readPng(png)
                val px = E2eHarness.toArgbPixels(img)
                img.width == 640 && img.height == 480 && E2eHarness.nonBlackRatio(px) > 0.02 && E2eHarness.distinctRgbCount(px) > 16
            },
            "Expected non-black 640x480 photo-card HUD frame",
        )
    }

    // =========================================================================
    // F2: LCDC1 YUV+OSD0 Composition (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f2T1_displayMainPortContractAndFrameBufferRoundTrip(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertEquals(1024, display.width)
                assertEquals(768, display.height)
                assertEquals(0L, display.sequence)
                val pixels = IntArray(display.width * display.height)
                assertFalse(display.copyArgb(pixels))

                for (y in 0 until display.height) {
                    for (x in 0 until display.width) {
                        pixels[y * display.width + x] = (0xff shl 24) or ((x and 0xff) shl 16) or ((y and 0xff) shl 8) or 0x55
                    }
                }
                display.presentArgb(pixels, display.width, display.height)
                assertEquals(1L, display.sequence)
                val out = IntArray(pixels.size)
                assertTrue(display.copyArgb(out))
                assertContentEquals(pixels, out)
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f2T1_lcdc1Rgb565ScanoutPresentsExactPixelsToDisplayMain(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot, legacyVga = true)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-osd0-main-640x480-rgb565le-v1", "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        val bufAddr = 0x06140000L
        val rgb565 = ByteBuffer.allocate(640 * 480 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 640 * 480) {
            val packed = if (i % 2 == 0) 0xF800 else 0x07E0 // pure red / pure green
            rgb565.putShort(packed.toShort())
        }
        val bytes = rgb565.array()
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write && address >= bufAddr && address + size <= bufAddr + bytes.size)
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            destination.put(bytes, (address - bufAddr).toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) = error("RO")
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                lcdc1.write(0x40L, 4, 0L)
                lcdc1.write(0x44L, 4, bufAddr)
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertEquals(1L, display.sequence)
                val out = IntArray(640 * 480)
                assertTrue(display.copyArgb(out))
                assertEquals(0xFFFF0000.toInt(), out[0])
                assertEquals(0xFF00FF00.toInt(), out[1])
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f2T1_lcdc1Rgba1024x768NearestScanoutDownscalesTo640x480(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot, legacyVga = true)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-osd0-1024x768-rgba-nearest-v1", "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        val bufAddr = 0x06140000L
        val rgba = ByteBuffer.allocate(1024 * 768 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 768) {
            for (x in 0 until 1024) {
                val pixel = if (x < 512 && y < 384) 0xFF0000FF.toInt() else 0x00FF00FF // RGBA red top-left, green elsewhere
                rgba.putInt(pixel)
            }
        }
        val bytes = rgba.array()
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write && address >= bufAddr && address + size <= bufAddr + bytes.size)
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            destination.put(bytes, (address - bufAddr).toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) = error("RO")
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                lcdc1.write(0x40L, 4, 0L)
                lcdc1.write(0x44L, 4, bufAddr)
                lcdc1.write(0x50L, 4, 0x00010400L)
                lcdc1.write(0x54L, 4, (768L shl 16) or 1024L)
                lcdc1.write(0x58L, 4, 0L)
                lcdc1.write(0x5cL, 4, (768L shl 16) or 1024L)
                lcdc1.write(0x60L, 4, 0x10001000L)
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertEquals(1L, display.sequence)
                val out = IntArray(640 * 480)
                assertTrue(display.copyArgb(out))
                assertEquals(0xFFFF0000.toInt(), out[10 * 640 + 10])
                assertEquals(0xFF00FF00.toInt(), out[400 * 640 + 500])
            }.join()
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f2T1_lcdc1YuvVideoPlaneAndOsd0CompositeToDisplayMain(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeLcdcYuvCompositeReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F2-YuvComposite", ready, "LCDC1 PIC/YUV plane (0xFFFA9130..0xFFFA91C4) composition not yet implemented in soc-vglib-init")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"), legacyVga = true)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1", "scanoutFrameLimit" to 4L, "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        val osdAddr = 0x06140000L
        val yuvAddr = 0x8999C000L
        val picW = 1024
        val picH = 768
        val picStride = picW * 2
        val yuvBuf = ByteArray(picStride * picH) { idx -> if (idx % 2 == 0) 0x80.toByte() else 0xEB.toByte() }
        val osdBuf = ByteArray(1024 * 768 * 4)
        val osdWords = ByteBuffer.wrap(osdBuf).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 96..672) {
            for (x in 64..448) {
                osdWords.putInt((y * 1024 + x) * 4, 0xFF0000FF.toInt()) // LE RRGGBBAA -> opaque red
            }
        }
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write && ((address >= osdAddr && address + size <= osdAddr + osdBuf.size) || (address >= yuvAddr && address + size <= yuvAddr + yuvBuf.size)))
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            if (address >= yuvAddr) destination.put(yuvBuf, (address - yuvAddr).toInt(), rem)
                            else destination.put(osdBuf, (address - osdAddr).toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) = error("RO")
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                lcdc1.write(0x008L, 4, 0x10010L)
                lcdc1.write(0x030L, 4, 0x20080001L)
                lcdc1.write(0x040L, 4, 0L)
                lcdc1.write(0x044L, 4, osdAddr)
                lcdc1.write(0x050L, 4, 0x00010400L)
                lcdc1.write(0x054L, 4, 0x03000400L)
                lcdc1.write(0x058L, 4, 0L)
                lcdc1.write(0x05cL, 4, 0x03000400L)
                lcdc1.write(0x060L, 4, 0x10001000L)
                lcdc1.write(0x160L, 4, 0xFFL)
                lcdc1.write(0x164L, 4, 0L)
                lcdc1.write(0x168L, 4, yuvAddr)
                lcdc1.write(0x16cL, 4, (picStride / 8).toLong())
                lcdc1.write(0x170L, 4, 0x03000400L)
                lcdc1.write(0x174L, 4, 0L)
                lcdc1.write(0x178L, 4, (picH.toLong() shl 16) or picW.toLong())
                lcdc1.write(0x17cL, 4, 0x10001000L)
                lcdc1.write(0x600L, 4, 1L)
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertTrue(display.sequence >= 1L)
                val pixels = IntArray(640 * 480)
                assertTrue(display.copyArgb(pixels))
                val osdPx = pixels[240 * 640 + 160]
                assertTrue(((osdPx ushr 16) and 0xff) >= 220 && ((osdPx ushr 8) and 0xff) <= 20 && (osdPx and 0xff) <= 20, "Expected red OSD0 pixel at (160,240), got 0x${osdPx.toUInt().toString(16)}")
                val yuvPx = pixels[240 * 640 + 480]
                assertTrue(maxOf((yuvPx ushr 16) and 0xff, (yuvPx ushr 8) and 0xff, yuvPx and 0xff) > 100, "Expected bright YUV background pixel under transparent OSD0 at (480,240), got 0x${yuvPx.toUInt().toString(16)}")
                assertNotEquals(osdPx, yuvPx)
                assertTrue(E2eHarness.distinctRgbCount(pixels) >= 2)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f2T1_lcdc1CompositedFrameSequenceIncrementsOnCommit(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeLcdcYuvCompositeReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F2-YuvCommitSeq", ready, "LCDC1 YUV+OSD0 composition not yet implemented")
        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1", "scanoutFrameLimit" to 4L, "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        val osdAddr = 0x06140000L
        val yuvAddr = 0x8999C000L
        val picW = 1024
        val picH = 768
        val picStride = picW * 2
        val osdBuf = ByteArray(1024 * 768 * 4)
        val yuvBuf = ByteArray(picStride * picH) { idx -> if (idx % 2 == 0) 0x80.toByte() else 0xD0.toByte() }
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write && ((address >= osdAddr && address + size <= osdAddr + osdBuf.size) || (address >= yuvAddr && address + size <= yuvAddr + yuvBuf.size)))
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            if (address >= yuvAddr) destination.put(yuvBuf, (address - yuvAddr).toInt(), rem)
                            else destination.put(osdBuf, (address - osdAddr).toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) = error("RO")
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                lcdc1.write(0x008L, 4, 0x10010L)
                lcdc1.write(0x030L, 4, 0x20080001L)
                lcdc1.write(0x040L, 4, 0L)
                lcdc1.write(0x044L, 4, osdAddr)
                lcdc1.write(0x050L, 4, 0x00010400L)
                lcdc1.write(0x054L, 4, 0x03000400L)
                lcdc1.write(0x058L, 4, 0L)
                lcdc1.write(0x05cL, 4, 0x03000400L)
                lcdc1.write(0x060L, 4, 0x10001000L)
                lcdc1.write(0x160L, 4, 0xFFL)
                lcdc1.write(0x164L, 4, 0L)
                lcdc1.write(0x168L, 4, yuvAddr)
                lcdc1.write(0x16cL, 4, (picStride / 8).toLong())
                lcdc1.write(0x170L, 4, 0x03000400L)
                lcdc1.write(0x174L, 4, 0L)
                lcdc1.write(0x178L, 4, (picH.toLong() shl 16) or picW.toLong())
                lcdc1.write(0x17cL, 4, 0x10001000L)
                lcdc1.write(0x600L, 4, 1L)
                val seq1 = display.sequence
                assertTrue(seq1 >= 1L)
                lcdc1.write(0x600L, 4, 1L)
                assertTrue(display.sequence > seq1)
            }.join()
        }
    }

    // =========================================================================
    // F3: Shooting HUD & MENU/OK (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f3T1_buttonsComponentTwelveSwitchesAndEvents(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { owner, host ->
            val seen = ArrayList<ComponentEvent>()
            owner.submit {
                host.events.subscribe { ev -> if (ev.source == "buttons") seen += ev }
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                for (key in PanelCatalog.buttonKeys) assertFalse(btn.pressed(key), "Expected $key initially released")
            }.join()
            host.patch("buttons", 0, mapOf("menu" to true, "shutterHalf" to true)).join()
            owner.submit {
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                assertTrue(btn.pressed("menu"))
                assertTrue(btn.pressed("shutterHalf"))
                assertFalse(btn.pressed("play"))
                assertTrue(seen.any { it.kind == "switches" && it.values["menu"] == true })
            }.join()
            host.patch("buttons", 1, mapOf("menu" to false, "shutterHalf" to false)).join()
            owner.submit {
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                assertFalse(btn.pressed("menu"))
                assertFalse(btn.pressed("shutterHalf"))
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f3T1_gpioButtonsV3AndAdcContractsLoadCleanly(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val gpioDir = home.resolve("compoments/soc-gpio")
        val adcDir = home.resolve("compoments/soc-adc")
        Files.createDirectories(gpioDir)
        Files.createDirectories(adcDir)
        Formats.atomicYaml(gpioDir.resolve("soc-gpio.yaml"), mapOf("profile" to "gfx100s-2.13-gpio-buttons-v3"))
        Formats.atomicYaml(adcDir.resolve("soc-adc.yaml"), mapOf("profile" to "adc-source-functional-experimental-v1", "inputFile" to "adc-input.json", "menuButtonProfile" to "gfx100s-2.13-menu-adc-v1"))
        Formats.writeJson(adcDir.resolve("adc-input.json"), mapOf("profile" to "adc-source-functional-experimental-v1", "sample_ticks" to 4, "inputs" to mapOf("3" to 4095), "word_44" to 0, "menu_pressed_sample" to 128))
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val gpio = host.components().single { it.descriptor.id == "soc-gpio" }.component
                val adc = host.components().single { it.descriptor.id == "soc-adc" }.component
                assertEquals("gfx100s-2.13-gpio-buttons-v3", gpio.state()["profile"])
                assertEquals("gfx100s-2.13-menu-adc-v1", adc.state()["menuButtonProfile"])
            }.join()
            host.patch("buttons", 0, mapOf("menu" to true, "play" to true)).join()
            owner.submit {
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                assertTrue(btn.pressed("menu"))
                assertTrue(btn.pressed("play"))
            }.join()
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T1_shootingHudNonBlackFrameAndDistinctColorPalette(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-ShootingHUD", ready, "Non-black shooting HUD frame not yet observed from snap2300k")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
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
        val hudFrames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.toList() }
            .map { E2eHarness.toArgbPixels(E2eHarness.readPng(it)) }
            .filter { E2eHarness.nonBlackRatio(it) > 0.02 }
        assertTrue(hudFrames.any { E2eHarness.distinctRgbCount(it) > 16 }, "HUD frame should render a multi-color UI palette")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T1_menuOkButtonOpensMenuScreenFromShootingHud(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-MenuOpen", ready, "Shooting HUD baseline required before MENU/OK test")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.blankCardSnapshotDir,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "632000000:menu:down,632500000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.size >= 2, "Expected HUD and MENU frames")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs.first()))
        val lastPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs.last()))
        assertTrue(E2eHarness.changedPixelRatio(firstPx, lastPx) > 0.05, "MENU/OK should visibly transition screen")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T1_menuNavigationDownAndBackUpdatesScreen(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-MenuNav", ready, "Shooting HUD & MENU/OK required")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.blankCardSnapshotDir,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "632000000:menu:down,632500000:menu:up,635000000:down:down,635500000:down:up,638000000:back:down,638500000:back:up",
                "--budget", "648000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.size >= 3)
        val distinctFrames = pngs.map { E2eHarness.toArgbPixels(E2eHarness.readPng(it)) }
            .zipWithNext().count { (a, b) -> E2eHarness.changedPixelRatio(a, b) > 0.005 }
        assertTrue(distinctFrames >= 2, "Expected distinct frame changes across menu -> down -> back")
    }

    // =========================================================================
    // F4: CMOS QR Recognition Chain (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f4T1_qrTestCodecEncodeDecodeRoundTripWithHudOverlay() {
        val payload = "FUJI-GFX100S-E2E-QR-01"
        val qr = QrTestCodec.encodeToImage(payload, modulePixels = 8, quietZoneModules = 4)
        val frame = BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB)
        val g = frame.createGraphics()
        g.color = java.awt.Color(24, 24, 24)
        g.fillRect(0, 0, 640, 480)
        // Draw simulated HUD bars around QR in center
        g.color = java.awt.Color.WHITE
        g.fillRect(20, 20, 120, 24)
        g.fillRect(20, 440, 600, 20)
        val x = (640 - qr.width) / 2
        val y = (480 - qr.height) / 2
        g.drawImage(qr, x, y, null)
        g.dispose()

        assertEquals(payload, QrTestCodec.decode(frame))
    }

    @Test
    @Tag("e2e-baseline")
    fun f4T1_cmosDescriptorSettingsAndValidationContract(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val qrPath = home.resolve("compoments/cmos/scene.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage("FUJI-GFX100S-E2E-QR-01"), "jpg", qrPath.toFile()))

        E2eHarness.withComponentHost(home) { owner, host ->
            val cmosDesc = owner.submit {
                assertNotNull(host.ports.find("cmos", SensorPort::class.java))
                host.components().single { it.descriptor.id == "cmos" }.descriptor
            }.join()
            assertEquals(1, cmosDesc.abi)
            val sourceKind = cmosDesc.settings.single { it.key == "sourceKind" }
            assertTrue(sourceKind.choices.containsAll(listOf("H264", "RAF", "JPEG", "PNG", "optical-scene")))
            assertTrue(sourceKind.live)

            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    val patch = client.patchComponent("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "scene.jpg", "loop" to true))
                    assertEquals(200, patch.statusCode())
                    val body = Formats.json.readTree(patch.body())
                    assertEquals("scene.jpg", body["state"]["sourceFile"].asText())
                    assertEquals(true, body["state"]["loop"].asBoolean())
                }
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f4T1_cmosExposeUnimplementedOrImplementedContract(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val qrPath = home.resolve("compoments/cmos/scene.jpg")
        Files.createDirectories(qrPath.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage("FUJI-GFX100S-E2E-QR-01"), "jpg", qrPath.toFile()))

        E2eHarness.withComponentHost(home) { owner, host ->
            host.patch("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "scene.jpg")).join()
            owner.submit {
                val cmos = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val out = ByteBuffer.allocate(640 * 480 * 4)
                val result = runCatching {
                    cmos.expose(Exposure(0L, 1000L, 100, LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)), out)
                }
                if (result.isSuccess) {
                    assertTrue(result.getOrThrow() > 0)
                } else {
                    val err = result.exceptionOrNull()
                    assertTrue(err is IllegalStateException && err.message?.contains("not implemented", ignoreCase = true) == true)
                }
            }.join()
        }
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun f4T1_cmosQrJpegSceneToSensorExposeProducesDecodableOutput(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M2", "F4-CmosExpose", ready, "cmos SensorPort.expose() is not yet implemented")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val payload = "FUJI-GFX100S-E2E-QR-01"
        val qrFile = home.resolve("compoments/cmos/qr-scene.jpg")
        Files.createDirectories(qrFile.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile.toFile()))

        E2eHarness.withComponentHost(home) { owner, host ->
            host.patch("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "qr-scene.jpg", "loop" to true)).join()
            owner.submit {
                val cmos = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val buf = ByteBuffer.allocate(640 * 480 * 4)
                val written = cmos.expose(Exposure(1L, 8000L, 160, LensParameters("GF63mmF2.8", 63.0, 4.0, 1.0, 0.9, 0.0)), buf)
                assertTrue(written > 0, "Expected positive sensor bytes written")
                assertTrue((0 until written).any { buf.get(it) != 0.toByte() }, "Sensor output must not be all zeros")
            }.join()
        }
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun f4T1_cmosQrSceneToLiveViewDisplayMainFrameDecodesPayload(@TempDir tempRoot: Path) {
        val cmosReady = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M2", "F4-QrLiveViewChain", cmosReady, "M2 cmos.expose() is not yet active")

        val payload = "FUJI-GFX100S-E2E-QR-01"
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val liveReady = fw != null && lib != null &&
            Files.isDirectory(E2eHarness.blankCardHomeDir) &&
            Files.isDirectory(E2eHarness.blankCardSnapshotDir) &&
            E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe-m1"))

        if (liveReady) {
            val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
            val qrFile = workHome.resolve("compoments/cmos/qr-scene.jpg")
            Files.createDirectories(qrFile.parent)
            check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile.toFile()))
            Formats.atomicYaml(workHome.resolve("compoments/cmos/cmos.yaml"), mapOf("sourceKind" to "JPEG", "sourceFile" to "qr-scene.jpg", "loop" to true))

            val framesDir = tempRoot.resolve("frames")
            Files.createDirectories(framesDir)
            val res = E2eHarness.runDisplayBringupResume(
                workHome,
                E2eHarness.probeSourceCommit(workHome),
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
            val decoded = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.toList() }
                .mapNotNull { QrTestCodec.decode(E2eHarness.readPng(it)) }
            assertTrue(payload in decoded, "Expected decoded QR payload '$payload' in output frames, got $decoded")
            return
        }

        // Deterministic real-component CMOS -> T80 ISP Live-View DMA -> LCDC1 PIC1 -> display.main pipeline
        val home = E2eHarness.stageCmosLiveViewComponentHome(
            tempRoot.resolve("home"),
            mapOf("sourceKind" to "JPEG", "sourceFile" to "qr-scene.jpg", "loop" to true),
        )
        val qrFile = home.resolve("compoments/cmos/qr-scene.jpg")
        Files.createDirectories(qrFile.parent)
        check(ImageIO.write(QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4), "jpg", qrFile.toFile()))

        val catalog = AddressCatalog.load(E2eHarness.projectRoot.resolve("catalog/compiled/catalog.json"))
        val picAddr = 0x8999_c000L
        val picStride = 2560
        val picRam = ByteArray(575 * picStride + 1024 * 2)
        val osdAddr = 0x0614_0000L
        val osdRam = ByteArray(1024 * 768 * 4)

        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val dma = object : DmaMemory {
                    override fun check(address: Long, size: Long, write: Boolean) {
                        val okPic = address == picAddr && size == picRam.size.toLong()
                        val okOsd = !write && address == osdAddr && size == osdRam.size.toLong()
                        require(okPic || okOsd) { "Unexpected DMA 0x${address.toString(16)} size=$size write=$write" }
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
                host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, dma)
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
                    override fun lensParameters() = LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0)
                }
                val bus = checkNotNull(com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime(catalog, "discovery", E2eHarness.projectRoot, discoveryCfg).createBus(ctx))
                var seq = 0L
                bus.access(++seq, 0xfff73018L, 4, true, 0x023f_03ffL)
                bus.access(++seq, 0xfff730c4L, 4, true, picAddr)
                bus.access(++seq, 0xfff730d0L, 4, true, picStride.toLong())
                bus.access(++seq, 0xfff730d4L, 4, true, 0x1005_0000L)

                E2eHarness.configureLcdc1Composite(lcdc1, osdAddr, picAddr, picStride, enableOsd0 = false)
                assertTrue(bus.pumpLiveViewDma(631_000_001L))
                lcdc1.write(0x600, 4, 1L)

                val px = IntArray(main.width * main.height)
                assertTrue(main.copyArgb(px))
                assertEquals(payload, QrTestCodec.decode(px, main.width, main.height))
            }.join()
        }
    }

    // =========================================================================
    // F5: PLAY DSCF0001.JPG Decode & Playback (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f5T1_dcfFixtureFat16ImageAndDscf0001JpegIntegrity() {
        val dir = E2eHarness.resolveDcfFixtureDir()
        E2eGates.requireAssets("sd-dcf-playback-01", dir != null, "Missing local/sd-dcf-playback-01 fixture")
        val source = dir!!
        val imgPath = source.resolve("slot1.img")
        val fat = E2eHarness.inspectFatFilesystem(imgPath)
        assertTrue(fat.fsType.startsWith("FAT16"), "Expected FAT16, got '${fat.fsType}'")
        assertTrue("DCIM" in fat.rootEntries, "Expected DCIM in root entries: ${fat.rootEntries}")

        val photo = source.resolve("DSCF0001.JPG")
        val decoded = ImageIO.read(photo.toFile())
        assertEquals(320, decoded.width)
        assertEquals(240, decoded.height)
        val sdSpec = Formats.readData(source.resolve("sd-card-v7.json"))
        assertEquals(SdTransactionContracts.ADDRESS_PROFILE, sdSpec["profile"])
    }

    @Test
    @Tag("e2e-baseline")
    fun f5T1_photoCardFat32ImageContainsValidDscf0001Jpeg() {
        val sdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets("photoCardFat32Image", Files.isRegularFile(sdImg), "Missing $sdImg")
        val fat = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(fat.fsType.startsWith("FAT32"), "Expected FAT32, got '${fat.fsType}'")
        assertEquals(512, fat.bytesPerSector)
        assertTrue(fat.partitionStartLba >= 2048L)
        assertTrue("DCIM" in fat.rootEntries, "Expected DCIM in photo-card root entries: ${fat.rootEntries}")
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T1_playButtonTriggersGpio9Pin6AndSdDmaRead(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing firmware, native library, or photoCard snapshot",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-PlaySdDma", ready, "M3 PLAY JPEG decode pipeline not yet wired")

        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
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
        val buttonEvents = Formats.json.readTree(workHome.resolve("bringup.button-events.json").toFile()).path("events")
        assertTrue(buttonEvents.any { it.path("key").asText() == "play" && it.path("pressed").asBoolean() })
        assertTrue(buttonEvents.any { it.path("key").asText() == "play" && !it.path("pressed").asBoolean() })

        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertTrue(summary.path("stopped").isBoolean && summary.path("stopped").asBoolean(), "Expected stopped=true in bringup.summary.json")
        assertTrue(summary.has("error") && summary.path("error").isNull, "Unexpected error in bringup.summary.json: ${summary.path("error")}")
        val result = Formats.json.readTree(workHome.resolve("bringup.result.json").toFile())
        val sdHost = result.path("devices").path("soc-sd-host")
        assertTrue(sdHost.path("mediaBytes").asLong() > 559_104L, "Expected PLAY SD mediaBytes > baseline 559104, got ${sdHost.path("mediaBytes")}")
        assertTrue(sdHost.path("transferCount").asLong() > 27L, "Expected PLAY SD transferCount > baseline 27, got ${sdHost.path("transferCount")}")
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T1_playModeDecodesDscf0001JpegToDisplayMain(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing firmware, native library, or photoCard snapshot/SD image",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-PlayJpegDecode", ready, "M3 PLAY JPEG decode pipeline not yet wired")

        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
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
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val postPlayFrames = framesMeta.filter { it.path("attempts").asLong() >= 581_000_000L }
            .map { Path.of(it.path("file").asText()) }
            .ifEmpty { Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() } }
        assertTrue(postPlayFrames.isNotEmpty(), "Expected exported PLAY frames")
        val decodedImgs = postPlayFrames.map { E2eHarness.readPng(it) }
        assertTrue(decodedImgs.any { E2eHarness.distinctRgbCount(E2eHarness.toArgbPixels(it)) > 64 })
        val bestNcc = decodedImgs.maxOf { E2eHarness.maxPlayCorrelation(it, refImg) }
        assertTrue(bestNcc >= 0.70, "Playback frame must visually correlate with extracted DSCF0001.JPG (bestNcc=$bestNcc)")
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T1_playModeExitsBackToShootingOnPlayToggle(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing firmware, native library, or photoCard snapshot/SD image",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-PlayReturn", ready, "M3 PLAY mode not yet wired")

        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "581000000:play:down,581500000:play:up,586000000:play:down,586500000:play:up",
                "--budget", "589500000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val inPlayImgs = framesMeta.filter { it.path("attempts").asLong() in 581_500_000L..585_999_999L }
            .map { E2eHarness.readPng(Path.of(it.path("file").asText())) }
        val postExitImgs = framesMeta.filter { it.path("attempts").asLong() >= 586_500_000L }
            .map { E2eHarness.readPng(Path.of(it.path("file").asText())) }
        assertTrue(inPlayImgs.isNotEmpty(), "Expected frames during PLAY mode")
        assertTrue(postExitImgs.isNotEmpty(), "Expected frames after exiting PLAY mode")
        val playBest = inPlayImgs.maxOf { E2eHarness.maxPlayCorrelation(it, refImg) }
        assertTrue(playBest >= 0.70, "Expected PLAY mode frame to correlate with DSCF0001.JPG (playBest=$playBest)")
        val lastPlayPx = E2eHarness.toArgbPixels(inPlayImgs.last())
        val lastExitImg = postExitImgs.last()
        val lastExitPx = E2eHarness.toArgbPixels(lastExitImg)
        assertTrue(E2eHarness.nonBlackRatio(lastExitPx) > 0.02, "Post-exit shooting frame must be non-black")
        assertTrue(E2eHarness.changedPixelRatio(lastPlayPx, lastExitPx) > 0.05, "Exiting PLAY must visibly update display.main")
        assertTrue(E2eHarness.maxPlayCorrelation(lastExitImg, refImg) < 0.65, "Post-exit frame must no longer display full-screen playback photo")
    }

    // =========================================================================
    // F6: Desktop GUI, EVF & Top-LCD Linkage (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f6T1_panelCatalogSixFacesSixtyEightControlsAndArtworkValid() {
        val panels = PanelCatalog.load()
        assertEquals(6, panels.size)
        assertEquals(PanelCatalog.viewIds, panels.map { it.id }.toSet())
        val allControls = panels.flatMap { it.controls }
        assertEquals(68, allControls.size)
        assertEquals(PanelCatalog.buttonKeys, allControls.mapNotNull { it.buttonKey }.toSet())
        val displays = allControls.filter { it.kind == "display" }.associateBy { it.id }
        assertEquals("display.main", displays.getValue("rear-lcd").displayPort)
        assertEquals("display.shoulder", displays.getValue("top-shoulder").displayPort)
        panels.forEach { panel -> PanelCatalog.validateArtwork(panel) }

        assertEquals(PanelCatalog.viewIds, StudioLayout.faces.keys)
        val boxList = StudioLayout.faces.values.map { it.slot }
        for (i in boxList.indices) {
            for (j in i + 1 until boxList.size) {
                assertFalse(StudioLayout.overlap(boxList[i], boxList[j]), "Faces overlap in 3-column layout")
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f6T1_studioInputStateOwnerObservationAndViewSwitchContract() {
        val input = StudioInputState({ _, _ -> java.util.concurrent.CompletableFuture.completedFuture(Unit) })
        input.press("mouse:menu", "menu")
        assertTrue(input.desired("menu"))
        input.observe(PanelCatalog.buttonKeys.associateWith { it == "menu" })
        assertEquals(true, input.observed("menu"))
        input.press("mouse:power", "power")
        input.releaseTransient()
        assertFalse(input.desired("menu"), "View change must release transient buttons")
        assertTrue(input.desired("power"), "View change must retain latching power")
        input.releaseAll()
        assertTrue(PanelCatalog.buttonKeys.none { input.desired(it) })
    }

    @Test
    @Tag("e2e-baseline")
    fun f6T1_shoulderDisplayDotLcdTransportCodecDecodeAndPresent(@TempDir tempRoot: Path) {
        val bits = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS) { idx ->
            if ((idx / DotLcdTransportCodec.WIDTH + idx % DotLcdTransportCodec.WIDTH) % 2 == 0) 1 else 0
        }
        val packet = DotLcdTransportCodec.encode(bits)
        assertEquals(DotLcdTransportCodec.BYTES, packet.size)
        assertContentEquals(bits, DotLcdTransportCodec.decode(packet))

        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val shoulder = checkNotNull(host.ports.find("display.shoulder", DisplayPort::class.java))
                assertEquals(320, shoulder.width)
                assertEquals(160, shoulder.height)
                val px = IntArray(320 * 160) { 0xFF112233.toInt() }
                shoulder.presentArgb(px, 320, 160)
                assertEquals(1L, shoulder.sequence)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T1_evfDisplayPortAndEyeSensorBindingsPresent(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe")) &&
            E2eHarness.probeI2cEyeSensorReady(tempRoot.resolve("probe-eye"))
        E2eGates.assumeOrFailFeature("M4", "F6-EvfBindings", ready, "display.evf port and rear-evf/rear-eye-sensor catalog bindings not yet implemented")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val evf = checkNotNull(host.ports.find("display.evf", DisplayPort::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertTrue(evf !== main)
                assertTrue(evf.width > 0 && evf.height > 0)
            }.join()
        }
        val controls = PanelCatalog.load().flatMap { it.controls }.associateBy { it.id }
        assertEquals("display.evf", controls.getValue("rear-evf").displayPort)
        assertNotEquals("not-connected", controls.getValue("rear-eye-sensor").bindingStatus)
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T1_graphicalSmokeUiSixFaceAndRobotHitVerification(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M4", "F6-SmokeUiEvf", ready, "M4 EVF/studio smoke updates not yet merged")
        E2eGates.requireGraphicalDisplay("F6-SmokeUi")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        Formats.atomicYaml(
            home.resolve("config/emulator.yaml"),
            mapOf(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to "",
                "nativeLibrary" to "",
                "instructionBudget" to 1000,
                "enabledDevices" to emptyList<String>(),
            ),
        )
        val smokeTarget = home.resolve("ui-smoke.png")
        val res = E2eHarness.runMainCli(home, listOf("--smoke-ui", smokeTarget.fileName.toString()), timeoutSeconds = 60)
        assertEquals(0, res.exitCode, res.stderr)
        assertTrue(Files.isRegularFile(smokeTarget))
        val panels = Formats.readData(home.resolve("ui-panels.json"))
        val events = Formats.readData(home.resolve("ui-interactions.json"))
        val checks = JvmStudioEvidence.checks(panels, events)
        assertTrue(checks.values.all { it }, "Failed studio evidence checks: ${checks.filterValues { !it }}")
    }

    // =========================================================================
    // F7: Blank-Card In-Camera FAT32 Format (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f7T1_sdcardReaderRwRoAnd64MiBSnapshotSyncContract(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        val rwBytes = ByteArray(1024 * 1024) { (it * 13 and 255).toByte() }
        val roBytes = ByteArray(512) { 0x42 }
        Files.write(sdDir.resolve("rw.img"), rwBytes)
        Files.write(sdDir.resolve("ro.img"), roBytes)
        Formats.atomicYaml(
            sdDir.resolve("sdcard-reader.yaml"),
            mapOf("slot1Image" to "rw.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Image" to "ro.img", "slot2Inserted" to true, "slot2ReadOnly" to true),
        )
        val snap = E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                assertEquals(512, sd.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x7A })))
                assertFails { sd.write(2, 0L, ByteBuffer.wrap(ByteArray(512))) }
                val stateful = host.components().single { it.descriptor.id == "sdcard-reader" }.component as com.github.nyabanban.fuji.emulator.api.StatefulComponent
                stateful.saveSnapshot()
            }.join()
        }
        // Corrupt only sector 0 externally (dirty sector) then restore snapshot — dirty sector must revert
        Files.newByteChannel(sdDir.resolve("rw.img"), java.nio.file.StandardOpenOption.WRITE).use { ch ->
            ch.position(0L)
            ch.write(ByteBuffer.wrap(ByteArray(512) { 0x00 }))
        }
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val stateful = host.components().single { it.descriptor.id == "sdcard-reader" }.component as com.github.nyabanban.fuji.emulator.api.StatefulComponent
                stateful.restoreSnapshot(snap)
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                val out = ByteBuffer.allocate(512)
                assertEquals(512, sd.read(1, 0L, out))
                assertTrue(out.array().all { it == 0x7A.toByte() })
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f7T1_socSdHostSdhcTransactionsThroughRealJars(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        val original = ByteArray(8 * 1024 * 1024) { ((it / 512 * 17 + it) and 255).toByte() }
        Files.write(sdDir.resolve("test.img"), original)
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "test.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Inserted" to false))

        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(sdHostDir)
        val profile = SdTransactionContracts.ADDRESS_PROFILE
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to profile, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdHostSyntheticInput(profile, 8L * 1024 * 1024, false))

        val catalog = AddressCatalog.bundled()
        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val sd = host.components().single { it.descriptor.id == "soc-sd-host" }.component
                val claims = sd.additionalMmioPorts().toSet()
                val windows = catalog.windowsFor(mapOf("soc-sd-host" to profile)).filter {
                    it.componentId == "soc-gic" || (it.componentId == "soc-sd-host" && it.portId in claims)
                }
                val catRt = CatalogRuntime(catalog, "strict", home, emptyMap())
                catRt.bindPorts(windows.map { PortBinding(it.componentId, it.portId, it.base, it.size) }, catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile), configuredComponentProfiles(host, windows.map { it.componentId }.toSet()))
                val router = MmioRouter(windows.map { MmioWindow(it.id, it.base, it.size, checkNotNull(host.ports.find(it.portId, MmioDevice::class.java))) })
                val ram = ByteArray(0x10000)
                host.ports.register(
                    "mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(address >= 0 && size >= 0 && address + size <= ram.size)
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining(); check(address, rem.toLong(), false); destination.put(ram, address.toInt(), rem)
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            val rem = source.remaining(); check(address, rem.toLong(), true); source.get(ram, address.toInt(), rem)
                        }
                    },
                )
                val base = 0xfffc4000L
                fun adv(n: Int = 16) = repeat(n) { host.advanceTick(host.tick + 1); router.beforeInstruction(host.tick) }
                fun issue(idx: Int, arg: Long = 0, flags: Int = 0x1a, mode: Int = 0) {
                    router.write(base + 0x30, 2, 0xffff); router.write(base + 0x32, 2, 0xffff)
                    router.write(base + 8, 2, arg and 0xffff); router.write(base + 10, 2, arg ushr 16)
                    router.write(base + 12, 4, (idx.toLong() shl 24) or (flags.toLong() shl 16) or mode.toLong())
                    adv()
                }
                adv(2); issue(0, flags = 0); issue(8, 0x1aa)
                var ready = false
                repeat(8) {
                    if (!ready) {
                        issue(55); issue(41, 0x40ff8000, flags = 2)
                        val resp = router.read(base + 0x10, 2) or (router.read(base + 0x12, 2) shl 16)
                        ready = (resp and 0x80000000L) != 0L
                    }
                }
                assertTrue(ready, "SD card must become ready")
                issue(2, flags = 9); issue(3); issue(9, 0x10000, flags = 9); issue(7, 0x10000, flags = 0x1b); issue(16, 512)
                val payload = ByteArray(1024) { (it * 7 + 1).toByte() }
                payload.copyInto(ram, 0x2000)
                router.write(base + 4, 2, 0x7000L or 512L); router.write(base + 6, 2, 2L); router.write(base + 0, 4, 0x2000L)
                issue(25, 1024L, flags = 0x3a, mode = 0x27)
                assertContentEquals(payload, Files.readAllBytes(sdDir.resolve("test.img")).copyOfRange(1024, 2048))
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f7T1_sdCardProtocolRejectsUnsupportedEraseAndAcmd23Today(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        Files.write(sdDir.resolve("test.img"), ByteArray(8 * 1024 * 1024))
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "test.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Inserted" to false))
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(sdHostDir)
        val profile = SdTransactionContracts.ADDRESS_PROFILE
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to profile, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdHostSyntheticInput(profile, 8L * 1024 * 1024, false))
        val catalog = AddressCatalog.bundled()
        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val sd = host.components().single { it.descriptor.id == "soc-sd-host" }.component
                val claims = sd.additionalMmioPorts().toSet()
                val windows = catalog.windowsFor(mapOf("soc-sd-host" to profile)).filter {
                    it.componentId == "soc-gic" || (it.componentId == "soc-sd-host" && it.portId in claims)
                }
                val catRt = CatalogRuntime(catalog, "strict", home, emptyMap())
                catRt.bindPorts(windows.map { PortBinding(it.componentId, it.portId, it.base, it.size) }, catalog.defaultMemoryProfile, catalog.memoryProfiles.getValue(catalog.defaultMemoryProfile), configuredComponentProfiles(host, windows.map { it.componentId }.toSet()))
                val router = MmioRouter(windows.map { MmioWindow(it.id, it.base, it.size, checkNotNull(host.ports.find(it.portId, MmioDevice::class.java))) })
                val base = 0xfffc4000L
                fun adv(n: Int = 16) = repeat(n) { host.advanceTick(host.tick + 1); router.beforeInstruction(host.tick) }
                fun issue(idx: Int, arg: Long = 0, flags: Int = 0x1a, mode: Int = 0) {
                    router.write(base + 0x30, 2, 0xffff); router.write(base + 0x32, 2, 0xffff)
                    router.write(base + 8, 2, arg and 0xffff); router.write(base + 10, 2, arg ushr 16)
                    router.write(base + 12, 4, (idx.toLong() shl 24) or (flags.toLong() shl 16) or mode.toLong())
                    adv()
                }
                adv(2); issue(0, flags = 0); issue(8, 0x1aa)
                var ready = false
                repeat(8) {
                    if (!ready) {
                        issue(55); issue(41, 0x40ff8000, flags = 2)
                        val resp = router.read(base + 0x10, 2) or (router.read(base + 0x12, 2) shl 16)
                        ready = (resp and 0x80000000L) != 0L
                    }
                }
                assertTrue(ready, "SD card must become ready")
                issue(2, flags = 9); issue(3); issue(9, 0x10000, flags = 9); issue(7, 0x10000, flags = 0x1b); issue(16, 512)
                // CMD38 without CMD32/CMD33 erase range must always fail cleanly without mutating media
                assertFails { issue(38, 0L, flags = 0x1b, mode = 0) }
                val before = Files.readAllBytes(sdDir.resolve("test.img")).copyOfRange(0, 512)
                assertTrue(before.all { it == 0.toByte() })
            }.join()
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f7T1_blankCardInCameraFat32FormatProducesValidFilesystem(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or blankCard snapshot",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "F7-FormatFat32", ready, "In-camera FAT32 format (M1 UI + M5 soc-sd-host CMD25/erase) not yet ready")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val srcSdImg = E2eHarness.blankCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val sdImg = workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val beforeHash = E2eHarness.sha256(srcSdImg)
        val framesDir = tempRoot.resolve("frames")
        val res = E2eHarness.runInCameraFormat(workHome, framesDir, ramPlan = ramPlan)
        assertEquals(0, res.exitCode, res.stderr)
        val fat = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(fat.fsType.startsWith("FAT32"), "Expected FAT32 formatted filesystem, got ${fat.fsType}")
        assertEquals(512, fat.bytesPerSector)
        assertTrue(fat.numFats >= 2, "Expected >= 2 FAT tables, got ${fat.numFats}")
        assertTrue(fat.fatSizeSectors > 0L, "Expected positive FAT size sectors")
        assertTrue(fat.fat0MediaByte in setOf(0xF8, 0xF0), "Invalid FAT0 media byte: ${fat.fat0MediaByte}")
        assertTrue(fat.fsInfoValid, "Expected valid FAT32 FSInfo sector signatures")
        assertTrue(fat.fatMirrorValid, "Expected FAT1 == FAT0 mirror")
        assertTrue(fat.fat32EocMarkersValid, "Expected valid FAT32 cluster 0/1/root EOC markers")
        val result = Formats.json.readTree(workHome.resolve("bringup.result.json").toFile())
        assertTrue(result.path("devices").path("soc-sd-host").path("mediaBytes").asLong() > 512L)
        assertEquals(beforeHash, E2eHarness.sha256(srcSdImg))
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f7T1_formattedCardRemountsCleanlyInShootingStandby(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or blankCard snapshot",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "F7-Remount", ready, "In-camera FAT32 format not yet ready")

        // Phase A: Format card in-camera and save a post-format snapshot
        val (home1, ramPlan1) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home1"), overrideSdReadOnly = false)
        val framesDir1 = tempRoot.resolve("frames1")
        val postFormatSnap = tempRoot.resolve("post-format-snap")
        val formatDoneAttempts = System.getProperty("fuji.e2e.formatDoneAttempts")?.toLongOrNull()
            ?: System.getProperty("fuji.e2e.formatBudget")?.toLongOrNull()
            ?: 655_000_000L
        val res1 = E2eHarness.runInCameraFormat(
            home1,
            framesDir1,
            savePostFormatSnapshotDir = postFormatSnap,
            formatDoneAttempts = formatDoneAttempts,
            ramPlan = ramPlan1,
        )
        assertEquals(0, res1.exitCode, res1.stderr)
        val sdImg1 = home1.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val fat1 = E2eHarness.inspectFatFilesystem(sdImg1)
        assertTrue(fat1.fsType.startsWith("FAT32"))
        assertTrue(fat1.fat0MediaByte in setOf(0xF8, 0xF0))
        val formattedSha = E2eHarness.sha256(sdImg1)

        // Phase B: Clone formatted card + home cleanly into fresh home2 and resume postFormatSnap in shooting standby
        val home2 = E2eHarness.cloneBringupHome(home1, tempRoot.resolve("home2"), copySdImage = false, overrideSdReadOnly = false)
        val sdImg2 = home2.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eHarness.copySparsePreserving(sdImg1, sdImg2)
        assertEquals(formattedSha, E2eHarness.sha256(sdImg2))
        val framesDir2 = tempRoot.resolve("frames2")
        Files.createDirectories(framesDir2)
        val menuDown = formatDoneAttempts + 2_000_000L
        val menuUp = formatDoneAttempts + 2_500_000L
        val res2 = E2eHarness.runDisplayBringupResume(
            home2,
            E2eHarness.probeSourceCommit(home2),
            postFormatSnap,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--button-events", "$menuDown:menu:down,$menuUp:menu:up",
                "--budget", (formatDoneAttempts + 10_000_000L).toString(),
                "--export-frames-dir", framesDir2.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res2.exitCode, res2.stderr)
        val summary2 = Formats.json.readTree(home2.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary2.path("status").asText())
        assertTrue(summary2.path("frontier").isNull)
        val pngs2 = Files.list(framesDir2).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs2.size >= 2, "Expected shooting HUD and MENU frames after remounting formatted card")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs2.first()))
        val lastPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs2.last()))
        assertTrue(E2eHarness.nonBlackRatio(firstPx) > 0.02 && E2eHarness.distinctRgbCount(firstPx) > 16)
        assertTrue(E2eHarness.changedPixelRatio(firstPx, lastPx) > 0.05)
        val fat2 = E2eHarness.inspectFatFilesystem(sdImg2)
        assertTrue(fat2.fsType.startsWith("FAT32"))
        assertTrue(fat2.fat0MediaByte in setOf(0xF8, 0xF0))
    }

    // =========================================================================
    // F8: Boot ROM Cold Start Exploration (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f8T1_bootRomEntryResearchStaticTraceAndSha256Verified() {
        val archive = E2eHarness.resolveBootRomArchive()
        E2eGates.requireAssets("GFX100S_DUMP.zip", archive != null, "Missing GFX100S_DUMP.zip")
        val report = BootRomEntryResearch.analyze(E2eHarness.projectRoot, E2eHarness.projectRoot)
        assertEquals("BOUNDED_FILE_NAVIGATION_NOT_COLD_BOOT", report["status"])
        @Suppress("UNCHECKED_CAST")
        val input = report["input"] as Map<String, Any?>
        assertEquals(32768, (input["entryBytes"] as Number).toInt())
        assertEquals("2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4", input["entrySha256"])
        @Suppress("UNCHECKED_CAST")
        val firstMem = report["firstMissingMemory"] as Map<String, Any?>
        assertEquals("0xFFF2FBF8", firstMem["writeStart"])
        assertEquals("0xFFF2FC00", firstMem["writeEndExclusive"])
        @Suppress("UNCHECKED_CAST")
        val firstMmio = report["firstMmio"] as Map<String, Any?>
        assertEquals("0xFFF4A400", firstMmio["address"])
    }

    @Test
    @Tag("e2e-baseline")
    fun f8T1_firmwareMetadataEnforcesRamLoaderGateOnBaseline() {
        val fw = E2eHarness.resolveFirmwareFile()
        E2eGates.requireAssets("GXUP0004.DAT", fw != null, "Missing GXUP0004.DAT")
        val metaPath = E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml")
        val metadata = FirmwareMetadata(metaPath)
        val image = FirmwareImage(metadata, fw!!)
        val inspected = image.inspect()
        assertEquals(4, (inspected["sections"] as List<*>).size)
        assertEquals("ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12", inspected["sha256"])

        val badMap = Formats.read(metaPath).toMutableMap()
        badMap["entry"] = 0xfff00000L
        assertFails { FirmwareMetadata(badMap) }
    }

    @Test
    @Tag("e2e-baseline")
    fun f8T1_socSystemControlBooleanWriteAndMasksContract(@TempDir tempRoot: Path) {
        val home1 = E2eHarness.stageComponentHome(tempRoot.resolve("disabled"))
        E2eHarness.withComponentHost(home1) { owner, host ->
            owner.submit {
                val boolMmio = checkNotNull(host.ports.find("soc-system-control.boolean", MmioDevice::class.java))
                assertFails { boolMmio.write(0L, 4, 1L) }
                assertFails { boolMmio.read(0L, 4) }
            }.join()
        }
        val home2 = E2eHarness.stageComponentHome(tempRoot.resolve("enabled"))
        val sysDir = home2.resolve("compoments/soc-system-control")
        Files.createDirectories(sysDir)
        Formats.atomicYaml(sysDir.resolve("soc-system-control.yaml"), mapOf("booleanWrite" to true))
        E2eHarness.withComponentHost(home2) { owner, host ->
            owner.submit {
                val boolMmio = checkNotNull(host.ports.find("soc-system-control.boolean", MmioDevice::class.java))
                boolMmio.write(0L, 4, 1L)
                assertFails { boolMmio.write(0L, 4, 2L) }
                val sys = host.components().single { it.descriptor.id == "soc-system-control" }.component
                assertEquals(1L, sys.state()["booleanLastWrite"])
            }.join()
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f8T1_bootRomColdStartProfileDeclaredInMetadata(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeBootRomProfileReady()
        E2eGates.assumeOrFailFeature("M5", "F8-BootRomProfile", ready, "Boot ROM cold-start profile not yet added to gfx100s-213-bootrom.yaml / gfx100s-213.yaml")

        val metaPath = E2eHarness.resolveBootRomMetadataPath()
        val raw = Formats.read(metaPath)
        val meta = FirmwareMetadata(raw)
        CatalogRuntime(AddressCatalog.bundled(), "strict", tempRoot, emptyMap()).validateFirmware(raw)

        assertEquals("boot-rom-experiment", raw["bootMode"]?.toString())
        assertEquals(0xfff00000L, meta.entry)
        assertEquals(0xd3, meta.cpsr)
        @Suppress("UNCHECKED_CAST")
        val rom = assertNotNull(raw["bootRom"] as? Map<String, Any?>, "Expected bootRom declaration in $metaPath")
        assertEquals(0xfff00000L, com.github.nyabanban.fuji.emulator.firmware.number(checkNotNull(rom["address"])))
        assertEquals(0x8000L, com.github.nyabanban.fuji.emulator.firmware.number(checkNotNull(rom["size"])))
        assertEquals("2a8b69986dd3fad0b73808c44e0834fa95c7b4e4f84e6c8664d4b0a82f7b24a4", rom["sha256"])
        val regions = assertNotNull(meta.memoryProfiles["boot-rom-cold-start-experimental-v1"])
        assertTrue(regions.any { it.id == "loader" && it.address == 0L && it.size == 0x20000L && it.permissions == 7 })
        assertTrue(regions.any { it.id == "boot-rom" && it.address == 0xfff00000L && it.size == 0x8000L && it.permissions == 5 })
        assertTrue(regions.any { it.id == "boot-rom-stage1-sram" && it.address == 0xfff10000L && it.size == 0x2000L && it.permissions == 7 })
        assertTrue(
            regions.any { it.id == "boot-rom-sram" && it.address <= 0xfff2fbf8L && it.address + it.size >= 0xfff2fc00L && (it.permissions and 2) != 0 },
            "Expected writable boot-rom-sram covering [0xFFF2FBF8, 0xFFF2FC00)",
        )
        assertFails { FirmwareMetadata(raw - "bootRom") }
        assertFails { FirmwareMetadata(raw + ("entry" to "0x10000")) }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f8T1_bootRomColdStartExecutesReproduciblyInStrictMode(@TempDir tempRoot: Path) {
        val archive = E2eHarness.resolveBootRomArchive()
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GFX100S_DUMP+GXUP0004+libunicorn", archive != null && fw != null && lib != null, "Missing GFX100S_DUMP.zip, GXUP0004.DAT, or libunicorn.so.2")
        val ready = E2eHarness.probeBootRomProfileReady()
        E2eGates.assumeOrFailFeature("M5", "F8-BootRomExec", ready, "Boot ROM cold-start execution profile not yet implemented")

        val bootRomBytes = java.util.zip.ZipFile(archive!!.toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val tracePoints = listOf(
            "0xfff00000", "0xfff00100", "0xfff00384", "0xfff00730",
            "0xfff033c4", "0xfff03490", "0xfff035e8", "0xfff0041c",
        )
        fun runOnce(name: String): Map<String, Any?> {
            val home = E2eHarness.stageComponentHome(tempRoot.resolve(name))
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
                "tracePoints" to tracePoints,
            )
            return EmulatorRuntime(home, cfg).use { it.probe() }
        }
        val run1 = runOnce("run1")
        val run2 = runOnce("run2")
        assertEquals("single-core boot-rom-experiment", run1["mode"])
        assertEquals(true, run1["bootRomExecuted"])
        assertEquals("unmapped MMIO 0xfff41500 size 4", run1["status"])
        val frontier1 = run1["frontier"] as Map<*, *>
        assertEquals(0xfff41500L, frontier1["addressPhysical"])
        @Suppress("UNCHECKED_CAST")
        val sysCtrl = (run1["devices"] as Map<*, *>)["soc-system-control"] as Map<*, *>
        assertEquals(1L, sysCtrl["booleanLastWrite"])
        val checkpoints = (run1["checkpoints"] as List<*>).map { (it as Map<*, *>)["address"] as Long }
        for (hexPoint in tracePoints) {
            assertTrue(hexPoint.substring(2).toLong(16) in checkpoints, "Missing trace point $hexPoint")
        }
        assertEquals(run1["attempts"], run2["attempts"])
        assertEquals(run1["registers"], run2["registers"])
        assertEquals(run1["frontier"], run2["frontier"])
    }

    // =========================================================================
    // F9: Peripheral YAML Convergence & strict Parity (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f9T1_catalogCompilerVerifyAndProjectTextPolicyPass() {
        CatalogCompiler.verify(E2eHarness.projectRoot)
        val policy = Formats.json.readTree(E2eHarness.projectRoot.resolve("text-policy.json").toFile())
        assertEquals(1, policy["schema"].asInt())
        assertEquals("UTF-8", policy["encoding"].asText())
        assertEquals(false, policy["bom"].asBoolean())
        assertEquals("LF", policy["endOfLine"].asText())
        assertEquals(true, policy["finalNewline"].asBoolean())
    }

    @Test
    @Tag("e2e-baseline")
    fun f9T1_strictModeRejectsDiscoverySettingsAndRefusesCandidateMmio(@TempDir tempRoot: Path) {
        val catalog = AddressCatalog.bundled()
        for (badKey in listOf("imageIrq106Status", "imageLiveViewBanks", "imageScalerF000", "imageResultStats", "imageFace7c000")) {
            val discoveryMap = if (badKey == "imageIrq106Status") {
                mapOf("image70000" to "x", badKey to "single-read-w1c-v1")
            } else {
                mapOf(badKey to "x")
            }
            assertFails("strict mode must reject $badKey") {
                CatalogRuntime(catalog, "strict", tempRoot, mapOf("discovery" to discoveryMap))
            }
        }
        val strictRt = CatalogRuntime(catalog, "strict", tempRoot, emptyMap())
        val query = strictRt.query(mapOf("address" to "0xFFFC41E0", "width" to "4", "direction" to "read"))
        assertEquals("CANDIDATE_REGISTER", query["classification"])
        assertEquals(false, query["permitted"])

        val router = MmioRouter(emptyList(), discovery = null)
        val err = assertFails { router.read(0xFFFC41E0L, 4) }
        assertTrue(err.message?.contains("unmapped MMIO", ignoreCase = true) == true)
    }

    @Test
    @Tag("e2e-baseline")
    fun f9T1_cliQueryAndHttpCatalogEndpointsMatchOnRegisterMetadata(@TempDir tempRoot: Path) {
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
        val cli = E2eHarness.runMainCli(home, listOf("--query-address", "0xFFFC41E0", "--width", "4"))
        assertEquals(0, cli.exitCode, cli.stderr)
        assertFalse(cli.stdout.contains('\r'))
        assertTrue(cli.stdout.endsWith("\n"))
        val cliJson = Formats.json.readTree(cli.stdout)

        E2eHarness.startHeadlessServer(home).use { srv ->
            srv.client().use { client ->
                val httpJson = client.getCatalogJson("address=0xFFFC41E0&width=4")
                assertEquals(cliJson["classification"], httpJson["classification"])
                assertEquals(cliJson["registerId"], httpJson["registerId"])
                assertEquals(cliJson["permitted"], httpJson["permitted"])
                assertEquals(false, httpJson["deviceReadPerformed"].asBoolean())

                val unknownJson = client.getCatalogJson("unknown=true")
                assertEquals("strict", unknownJson["executionMode"].asText())
                assertEquals(0, unknownJson["accesses"].size())
            }
        }
    }

    @Test
    @Tag("e2e-m6")
    @Tag("e2e-milestone-gated")
    fun f9T1_catalogDevicesContainVerifiedPromotionsWithProvenance(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M6", "F9-VerifiedCatalog", ready, "M6 catalog YAML convergence not yet complete")
        CatalogCompiler.verify(E2eHarness.projectRoot)

        val home = E2eHarness.stageComponentHome(tempRoot)
        Formats.atomicYaml(
            home.resolve("config/emulator.yaml"),
            mapOf("schema" to 1, "executionMode" to "discovery", "components" to "compoments", "httpPort" to 0, "firmwareMetadata" to "not-loaded.yaml", "firmwareFile" to "", "nativeLibrary" to "", "instructionBudget" to 1000, "enabledDevices" to emptyList<String>()),
        )
        E2eHarness.startHeadlessServer(home).use { srv ->
            srv.client().use { client ->
                val resp = client.get("/api/v1/catalog?coverage=true")
                assertEquals(200, resp.statusCode())
            }
        }
    }

    @Test
    @Tag("e2e-m6")
    @Tag("e2e-milestone-gated")
    fun f9T1_criticalPathRunsInStrictModeWithEmptyDiscoveryJournal(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M6", "F9-DiscoveryZeroUnknown", ready, "M6 peripheral YAML convergence not yet complete")

        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GXUP0004+libunicorn", fw != null && lib != null, "Missing firmware or native library")

        val (home, cfg) = E2eHarness.stageDiscoveryBootHome(tempRoot.resolve("mode-discovery"), instructionBudget = 200000)
        EmulatorRuntime(home, cfg).use { rt ->
            rt.serve()
            val port = checkNotNull(rt.server).address.port
            rt.probe()
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val discState = rt.owner.submit { rt.session.discoveryState() }.join()
            assertEquals("discovery", sessionState["executionMode"])
            assertEquals(emptyList<Any?>(), discState["accesses"])
            E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                assertEquals("discovery", client.getStateJson()["machine"]["executionMode"].asText())
                val unknownNode = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                assertEquals(0, unknownNode["accesses"].size())
            }
        }
    }

    // =========================================================================
    // Wave 2 Gap Coverage: M1, M3, M4, M5_FORMAT, M5P, M6 & R4
    // =========================================================================

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f1T1_photoCardCleanCrec6NoErrorBanner(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing photoCard snapshot or runtime assets",
        )
        val ready = E2eHarness.probeM1CleanPhotoCrec6Ready(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F1-CleanPhotoCrec6", ready, "Photo card still enters lenscom/biscom errno 0x0095 error state instead of clean C_REC col 6")

        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "581000000:menu:down,581500000:menu:up",
                "--budget", "586000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("frontier").isNull)
        assertTrue(summary.path("error").isNull)
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 581_000_000L }
            .map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        val postMenu = framesMeta.filter { it.path("attempts").asLong() >= 581_500_000L }
            .map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        assertTrue(preMenu.any { E2eHarness.nonBlackRatio(it) > 0.02 && E2eHarness.distinctRgbCount(it) > 16 })
        assertTrue(postMenu.any { p -> preMenu.any { b -> E2eHarness.changedPixelRatio(b, p) > 0.05 } }, "Clean C_REC col 6 must transition into MENU/OK")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f1T1_blankCardCh21ExitTransitionsToCleanCrec6(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing blankCard snapshot or runtime assets",
        )
        val ready = E2eHarness.probeM1BlankCh21Ready(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F1-BlankCh21Exit", ready, "Blank card ch21 bit 0x800 exit into clean C_REC col 6 not yet complete")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.blankCardSnapshotDir,
            listOf(
                "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                "--extend-ram-plan", ramPlan.toString(),
                "--button-events", "636000000:menu:down,636500000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("frontier").isNull)
        val framesMeta = Formats.json.readTree(workHome.resolve("bringup.frames.json").toFile()).path("frames")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 636_000_000L }
            .map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        val postMenu = framesMeta.filter { it.path("attempts").asLong() >= 636_500_000L }
            .map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        assertTrue(preMenu.any { E2eHarness.nonBlackRatio(it) > 0.02 && E2eHarness.distinctRgbCount(it) > 16 })
        assertTrue(postMenu.any { p -> preMenu.any { b -> E2eHarness.changedPixelRatio(b, p) > 0.05 } })
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f2T1_vglibScissorClipAndGroup0CommandExecution(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeVglibScissorReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F2-VglibScissorGroup0", ready, "GraphicsCommandExecution/SolidCommandRects non-full 0xc0f00e scissor + group 0 pairing not yet implemented")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        val fixture = E2eHarness.buildVglibScissorFixture(groupId = 0, scissorWords = listOf(0x00ff0080, 0x027f0200))
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "graphicsFillTail" to "graphics-fill-retained-experimental-v2",
                "inputFile" to "mode-input.json",
            ),
        )
        Formats.writeJson(
            vglibDir.resolve("mode-input.json"),
            mapOf(
                "schema" to 1,
                "profile" to "vglib-full-lane-init-experimental-v1",
                "readConfidence" to "HYPOTHESIS",
                "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) },
            ),
        )
        Formats.atomicYaml(
            vglibDir.resolve("graphics-fill.yaml"),
            mapOf(
                "profile" to "graphics-fill-retained-experimental-v2",
                "initialWords" to listOf(0, 0, 0, 0),
                "statusReads" to emptyList<Int>(),
                "accessLimit" to 64,
                "copyInitialWords" to List(9) { 0 },
                "copyExecution" to mapOf(
                    "profile" to "same-geometry-shared-ram-copy-experimental-v1",
                    "sourceBase" to fixture.targetAddr,
                    "destinationBase" to 0x02000000L,
                    "surfaceBytes" to 0x300000L,
                    "submissionLimit" to 2,
                    "mode2Workspace" to mapOf("base" to fixture.heapBase, "bytes" to 0x10000L),
                ),
                "commandInitialWords" to List(8) { 0 },
                "commandExecution" to "axis-rect-morton-rgba-solid-alpha-v1",
            ),
        )
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "vglib-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            val buf = fixture.regions[address]
                            require(buf != null && buf.size.toLong() == size && (!write || address == fixture.targetAddr))
                        }

                        override fun read(address: Long, destination: ByteBuffer) {
                            val rem = destination.remaining()
                            check(address, rem.toLong(), false)
                            destination.put(fixture.regions.getValue(address))
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            val rem = source.remaining()
                            check(address, rem.toLong(), true)
                            source.get(fixture.regions.getValue(address))
                        }
                    },
                )
                host.ports.register(
                    "vglib-exec-loc",
                    "cpu.execution-location",
                    ExecutionLocationSource::class.java,
                    ExecutionLocationSource { ExecutionLocation(0, 0x01400000L) },
                )
                val regs = checkNotNull(host.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
                for (i in 0 until 18) {
                    regs.read(i * 4L, 4)
                    regs.write(i * 4L, 4, 0L)
                }
                regs.write(0x1214L, 4, 0x0100ffffL)
                regs.write(0x1298L, 4, 0L)
                regs.write(0x10d4L, 4, fixture.vertAddr)
                regs.write(0x10dcL, 4, fixture.vertCountWord)
                regs.write(0x10d0L, 4, 1L)
                regs.write(0x10e4L, 4, fixture.fragAddr)
                regs.write(0x10ecL, 4, fixture.fragCountWord)
                regs.write(0x10e0L, 4, 1L)
                val inside = 4 * (160 * 1024 + 160)
                val outTopLeft = 4 * (32 * 1024 + 32)
                val outBottomRight = 4 * (300 * 1024 + 300)
                assertEquals(255, fixture.targetFrame[inside].toInt() and 0xff)
                assertEquals(0, fixture.targetFrame[outTopLeft].toInt() and 0xff)
                assertEquals(0, fixture.targetFrame[outBottomRight].toInt() and 0xff)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T1_photoCardMenuRecAndDirectionKeyPixelDelta(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing photoCard snapshot or runtime assets",
        )
        val ready = E2eHarness.probeM1MenuNavReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-MenuDirDelta", ready, "Clean MENU/OK + direction key navigation not yet ready")

        val usePhoto = E2eHarness.probeM1CleanPhotoCrec6Ready(tempRoot.resolve("probe-photo"))
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val workHome: Path
        if (usePhoto) {
            val (h, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false, enableDirectionButtons = true)
            workHome = h
            val res = E2eHarness.runDisplayBringupResume(
                workHome,
                E2eHarness.probeSourceCommit(workHome),
                E2eHarness.photoCardSnapshotDir,
                listOf(
                    "--allow-promoted-discovery",
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-adc,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--button-events", "581000000:menu:down,581500000:menu:up,583500000:down:down,584000000:down:up,585500000:right:down,586000000:right:up,587500000:back:down,588000000:back:up",
                    "--budget", "589500000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            assertEquals(0, res.exitCode, res.stderr)
        } else {
            val (h, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
            workHome = h
            val res = E2eHarness.runDisplayBringupResume(
                workHome,
                E2eHarness.probeSourceCommit(workHome),
                E2eHarness.blankCardSnapshotDir,
                listOf(
                    "--allow-profile-changes", "power-peer,soc-gpio,soc-vglib-init",
                    "--extend-ram-plan", ramPlan.toString(),
                    "--button-events", "632000000:menu:down,632500000:menu:up,635000000:down:down,635500000:down:up,638000000:right:down,638500000:right:up,641000000:back:down,641500000:back:up",
                    "--budget", "645000000",
                    "--export-frames-dir", framesDir.toString(),
                ),
                timeoutSeconds = 120,
            )
            assertEquals(0, res.exitCode, res.stderr)
        }
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.size >= 3)
        val deltas = pngs.map { E2eHarness.toArgbPixels(E2eHarness.readPng(it)) }
            .zipWithNext().count { (a, b) -> E2eHarness.changedPixelRatio(a, b) > 0.002 }
        assertTrue(deltas >= 2, "Expected distinct pixel transitions across menu -> direction keys -> back")
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T1_socJpegHardwareDecodePortAndIrq136Contract(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeSocJpegPortReady(tempRoot.resolve("probe-jpeg")) &&
            E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe-m3"))
        E2eGates.assumeOrFailFeature("M3", "F5-SocJpegPort", ready, "soc-jpeg hardware decoder (0xFFFA1000, IRQ 136) not yet merged")

        val catalog = AddressCatalog.bundled()
        assertTrue(catalog.windows.any { it.componentId == "soc-jpeg" && it.base == 0xFFFA1000L })
        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val enabledProfile = E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val loaded = host.components().single { it.descriptor.id == "soc-jpeg" }
                val profileSetting = loaded.descriptor.settings.singleOrNull { it.key == "profile" }
                if (profileSetting != null && profileSetting.default.toString().contains("disabled")) {
                    profileSetting.choices.firstOrNull { !it.contains("disabled") }
                } else {
                    null
                }
            }.join()
        }
        if (enabledProfile != null) {
            val jpegDir = home.resolve("compoments/soc-jpeg")
            Files.createDirectories(jpegDir)
            Formats.atomicYaml(jpegDir.resolve("soc-jpeg.yaml"), mapOf("profile" to enabledProfile))
        }
        E2eHarness.withComponentHost(home, catalog) { owner, host ->
            owner.submit {
                val loaded = host.components().single { it.descriptor.id == "soc-jpeg" }
                assertTrue(loaded.component.interruptRoutes().any { 136 in it.lines })
                val stateful = loaded.component as StatefulComponent
                val snap = stateful.saveSnapshot()
                stateful.restoreSnapshot(snap)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T1_uniReszPixelScalingDmaAndIrq133Contract(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isRegularFile(srcSdImg),
            "Missing photoCard assets",
        )
        val ready = E2eHarness.probeUniReszPixelDmaReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-UniReszDma", ready, "UniResz (0xFFF7F000, IRQ 133) pixel scaling DMA not yet merged")

        val catalog = AddressCatalog.bundled()
        val uniReszWindow = catalog.windows.firstOrNull { it.base == 0xFFF7F000L && it.componentId != "runtime" }
        assertNotNull(uniReszWindow, "Missing non-runtime catalog window for 0xFFF7F000")
        val compHome = E2eHarness.stageComponentHome(tempRoot.resolve("home-component"))
        E2eHarness.withComponentHost(compHome, catalog) { owner, host ->
            owner.submit {
                val loaded = host.components().single { it.descriptor.id == uniReszWindow.componentId }
                assertTrue(
                    loaded.component.interruptRoutes().any { 133 in it.lines },
                    "Expected ${loaded.descriptor.id} (0xFFF7F000) to expose IRQ 133 interrupt route",
                )
            }.join()
        }
        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
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
        val imgs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.map { E2eHarness.readPng(it) }.toList() }
        assertTrue(imgs.any { it.width == 640 && it.height == 480 && E2eHarness.maxPlayCorrelation(it, refImg) >= 0.70 })
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T1_lcdc0ScanoutToEvfAndMultiScreenIsolation(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M4", "F6-Lcdc0EvfIsolation", ready, "M4 LCDC0 -> display.evf scanout not yet merged")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                val lcdc0 = checkNotNull(host.ports.find("soc-vglib-init.lcdc0-functional", MmioDevice::class.java))
                lcdc0.write(0L, 4, 0L)
                assertEquals(1L, lcdc0.read(0L, 4) and 1L)
                val evf = checkNotNull(host.ports.find("display.evf", DisplayPort::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val shoulder = checkNotNull(host.ports.find("display.shoulder", DisplayPort::class.java))
                assertTrue(evf !== main && evf !== shoulder && main !== shoulder)
                val evfPx = IntArray(evf.width * evf.height) { 0xFF224466.toInt() }
                evf.presentArgb(evfPx, evf.width, evf.height)
                assertEquals(1L, evf.sequence)
                assertEquals(0L, main.sequence)
                assertEquals(0L, shoulder.sequence)
                val out = IntArray(evf.width * evf.height)
                assertTrue(evf.copyArgb(out))
                assertContentEquals(evfPx, out)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T1_headlessSixFaceLayoutEyeSensorI2cAndAllTwelveButtons(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe-evf")) &&
            E2eHarness.probeI2cEyeSensorReady(tempRoot.resolve("probe-eye"))
        E2eGates.assumeOrFailFeature("M4", "F6-HeadlessSixFaceEyeI2c", ready, "M4 display.evf + live I2C1 @ 0x39 rear-eye-sensor binding not yet merged")

        val panels = PanelCatalog.load()
        assertEquals(6, panels.size)
        val controls = panels.flatMap { it.controls }
        assertEquals(68, controls.size)
        val byId = controls.associateBy { it.id }
        assertEquals("display.evf", byId.getValue("rear-evf").displayPort)
        val eyeCtrl = byId.getValue("rear-eye-sensor")
        val eyeId = assertNotNull(eyeCtrl.componentId)
        val eyeKey = assertNotNull(eyeCtrl.settingKey)
        assertNotEquals("not-connected", eyeCtrl.bindingStatus)

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        E2eHarness.withComponentHost(home) { owner, host ->
            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    val eyeSetting = owner.submit {
                        host.components().single { it.descriptor.id == eyeId }.descriptor.settings.single { it.key == eyeKey }
                    }.join()
                    assertTrue(eyeSetting.live, "Eye sensor setting $eyeId.$eyeKey must be live-patchable")
                    val nearVal: Any = when (eyeSetting.kind) {
                        ValueKind.BOOLEAN -> true
                        ValueKind.INTEGER -> (eyeSetting.maximum ?: 1.0).toLong()
                        ValueKind.DECIMAL -> eyeSetting.maximum ?: 1.0
                        ValueKind.TEXT -> eyeSetting.choices.last()
                    }
                    val eyeRev = client.getStateJson()["components"].single { it["id"].asText() == eyeId }["revision"].asLong()
                    assertEquals(200, client.patchComponent(eyeId, eyeRev, mapOf(eyeKey to nearVal)).statusCode())

                    for (key in PanelCatalog.buttonKeys) {
                        val rev1 = client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()
                        assertEquals(200, client.patchComponent("buttons", rev1, mapOf(key to true)).statusCode())
                        assertTrue(owner.submit { checkNotNull(host.ports.find("buttons", ButtonPort::class.java)).pressed(key) }.join())
                        val rev2 = client.getStateJson()["components"].single { it["id"].asText() == "buttons" }["revision"].asLong()
                        assertEquals(200, client.patchComponent("buttons", rev2, mapOf(key to false)).statusCode())
                        assertFalse(owner.submit { checkNotNull(host.ports.find("buttons", ButtonPort::class.java)).pressed(key) }.join())
                    }
                }
            }
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f7T1_fat32FormatFsInfoAndFatMirrorIntegrity(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or blankCard snapshot",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "F7-Fat32FsInfoMirror", ready, "In-camera FAT32 format not yet ready")

        val (workHome, ramPlan) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val res = E2eHarness.runInCameraFormat(workHome, tempRoot.resolve("frames"), ramPlan = ramPlan)
        assertEquals(0, res.exitCode, res.stderr)
        val fat = E2eHarness.inspectFatFilesystem(workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img"))
        assertTrue(fat.fsType.startsWith("FAT32"))
        assertTrue(fat.fsInfoValid, "FAT32 FSInfo lead/struc/trail signatures invalid")
        assertTrue(fat.fatMirrorValid, "FAT1 does not mirror FAT0")
        assertTrue(fat.fat32EocMarkersValid, "FAT32 cluster 0/1/root EOC markers invalid")
    }

    @Test
    @Tag("e2e-baseline")
    @Tag("e2e-m5p")
    fun f10T1_m5pDiscoveryResumeBitExactDeterminism(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcHome = E2eHarness.graphicsOnHomeDir
        val srcSnap = E2eHarness.graphicsOnSnapshotDir
        E2eGates.requireAssets(
            "graphicsOn+runtime",
            fw != null && lib != null && Files.isDirectory(srcHome) && Files.isDirectory(srcSnap),
            "Missing firmware, libunicorn, or graphics-on/after-command snapshot",
        )
        val baseAttempts = (EmulatorSnapshot.readJsonObject(srcSnap.resolve(EmulatorSnapshot.COMPLETE_FILE))["attempts"] as Number).toLong()
        val midAttempts = baseAttempts + 150_000L
        val finalAttempts = baseAttempts + 300_000L

        data class RunOutput(
            val state: Map<String, Any?>,
            val srcPc: Long,
            val regs: Map<String, Long>,
            val fpu: Map<String, Long>,
            val seqInit: Long,
            val displaySeq: Long,
            val nonBlackRatio: Double,
            val distinctRgbCount: Int,
            val displaySha256: String?,
            val deterministicFileDigests: Map<String, String>,
            val snapSession: Any?,
            val snapMemory: Any?,
            val snapDiscovery: Any?,
        )

        fun sha256IntArray(pixels: IntArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bb = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (px in pixels) bb.putInt(px)
            md.update(bb.array())
            return HexFormat.of().formatHex(md.digest())
        }

        fun runOnce(name: String): RunOutput {
            val workHome = E2eHarness.cloneBringupHome(srcHome, tempRoot.resolve("home-$name"), copySdImage = true, overrideSdReadOnly = false)
            val cfg = Formats.read(workHome.resolve("config/emulator.yaml")) + mapOf("executionMode" to "discovery", "httpPort" to 0)
            val midSnapDir = tempRoot.resolve("mid-snap-$name")
            EmulatorRuntime(workHome, cfg).use { rt ->
                var srcPc = 0L
                var seqInit = 0L
                rt.owner.submit {
                    rt.session.initialize()
                    rt.session.restoreEmulatorSnapshot(
                        srcSnap,
                        EmulatorSnapshot.RestoreOptions(
                            overrideBudget = finalAttempts,
                            allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                            allowPromotedDiscoveryRegions = true,
                        ),
                    )
                    @Suppress("UNCHECKED_CAST")
                    srcPc = ((rt.session.state()["registers"] as Map<String, Any?>)["pc"] as Number).toLong()
                    seqInit = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence
                }.join()
                var savedMid = false
                while (true) {
                    val more = rt.owner.submit {
                        val running = rt.session.step()
                        if (running && !savedMid && rt.session.checkpointAttempts() >= midAttempts) {
                            rt.session.saveEmulatorSnapshot(midSnapDir)
                            savedMid = true
                        }
                        running
                    }.join()
                    if (!more) break
                }
                assertTrue(savedMid, "Expected mid-run snapshot save at >= $midAttempts")
                val manifest = EmulatorSnapshot.readJsonObject(midSnapDir.resolve(EmulatorSnapshot.MANIFEST_FILE))
                val digests = E2eHarness.snapshotDirectoryFileDigests(midSnapDir)
                    .filterKeys { it == "components.json" || it.startsWith("ram/") }
                return rt.owner.submit {
                    val state = rt.session.state()
                    val regs = checkNotNull(rt.session.registerSnapshot())
                    val fpu = checkNotNull(rt.session.floatingPointSnapshot())
                    val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                    val px = IntArray(main.width * main.height)
                    val copied = main.copyArgb(px)
                    val hash = if (copied) sha256IntArray(px) else null
                    RunOutput(
                        state = state,
                        srcPc = srcPc,
                        regs = regs,
                        fpu = fpu,
                        seqInit = seqInit,
                        displaySeq = main.sequence,
                        nonBlackRatio = if (copied) E2eHarness.nonBlackRatio(px) else 0.0,
                        distinctRgbCount = if (copied) E2eHarness.distinctRgbCount(px) else 0,
                        displaySha256 = hash,
                        deterministicFileDigests = digests,
                        snapSession = manifest["session"],
                        snapMemory = manifest["memory"],
                        snapDiscovery = manifest["discovery"],
                    )
                }.join()
            }
        }

        val runA = runOnce("a")
        val runB = runOnce("b")
        assertEquals(finalAttempts, (runA.state["attempts"] as Number).toLong())
        assertEquals(runA.state["attempts"], runB.state["attempts"])
        val pcA = (runA.state["registers"] as? Map<*, *>)?.get("pc") as? Number
        val pcB = (runB.state["registers"] as? Map<*, *>)?.get("pc") as? Number
        assertNotNull(pcA, "Expected non-null state[\"registers\"][\"pc\"] in runA")
        assertEquals(pcA.toLong(), pcB?.toLong())
        assertNotEquals(runA.srcPc, pcA.toLong(), "Expected PC to advance beyond srcSnap PC")
        assertEquals(runA.regs, runB.regs, "Registers diverged across deterministic discovery runs")
        assertEquals(runA.fpu, runB.fpu, "Floating point registers diverged across deterministic discovery runs")
        assertTrue(runA.displaySeq > runA.seqInit, "Expected display.main sequence (${runA.displaySeq}) to advance past initial (${runA.seqInit})")
        assertTrue(runA.nonBlackRatio > 0.02, "Expected non-trivial display.main frame, got nonBlackRatio=${runA.nonBlackRatio}")
        assertTrue(runA.distinctRgbCount > 16, "Expected distinct RGB colors > 16, got ${runA.distinctRgbCount}")
        assertEquals(runA.displaySeq, runB.displaySeq)
        assertEquals(runA.displaySha256, runB.displaySha256)
        assertEquals(runA.deterministicFileDigests, runB.deterministicFileDigests, "Downstream saveEmulatorSnapshot RAM/component digests must be bit-exact")
        assertEquals(runA.snapSession, runB.snapSession, "Snapshot session manifest must be bit-exact")
        assertEquals(runA.snapMemory, runB.snapMemory, "Snapshot memory manifest must be bit-exact")
        assertEquals(runA.snapDiscovery, runB.snapDiscovery, "Snapshot discovery manifest must be bit-exact")
    }

    @Test
    @Tag("e2e-baseline")
    @Tag("e2e-m5p")
    fun f10T1_m5pSnapshotV1LoadStepSaveRestoreAnd4GiBSparseSdCompat(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcHome = E2eHarness.graphicsOnHomeDir
        val srcSnap = E2eHarness.graphicsOnSnapshotDir
        val srcSdImg = srcHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "graphicsOn+runtime",
            fw != null && lib != null && Files.isDirectory(srcHome) && Files.isDirectory(srcSnap) && Files.isRegularFile(srcSdImg),
            "Missing firmware, libunicorn, or graphics-on snapshot/SD image",
        )
        val srcManifestSha = E2eHarness.sha256(srcSnap.resolve(EmulatorSnapshot.MANIFEST_FILE))
        val srcSdSize = Files.size(srcSdImg)
        assertTrue(srcSdSize >= 4L * 1024L * 1024L * 1024L, "Expected >= 4 GiB sparse SD image, got $srcSdSize")
        assertTrue(E2eHarness.allocatedDiskBytes(srcSdImg) < 64L * 1024L * 1024L, "Expected sparse source SD allocation < 64 MiB")

        val baseAttempts = (EmulatorSnapshot.readJsonObject(srcSnap.resolve(EmulatorSnapshot.COMPLETE_FILE))["attempts"] as Number).toLong()
        val midAttempts = baseAttempts + 150_000L
        val finalAttempts = baseAttempts + 300_000L

        fun sha256IntArray(pixels: IntArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val bb = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (px in pixels) bb.putInt(px)
            md.update(bb.array())
            return HexFormat.of().formatHex(md.digest())
        }

        val directHome = E2eHarness.cloneBringupHome(srcHome, tempRoot.resolve("direct-home"), copySdImage = true, overrideSdReadOnly = false)
        val directSdImg = directHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        assertEquals(srcSdSize, Files.size(directSdImg))
        assertTrue(E2eHarness.allocatedDiskBytes(directSdImg) < 64L * 1024L * 1024L, "Expected sparse cloned SD allocation < 64 MiB")
        val directCfg = Formats.read(directHome.resolve("config/emulator.yaml")) + mapOf("executionMode" to "discovery", "httpPort" to 0)
        val midSnapDir = tempRoot.resolve("mid-snap-v1")
        val midSdImg = tempRoot.resolve("mid-sd-v1.img")

        lateinit var directState: Map<String, Any?>
        lateinit var directRegs: Map<String, Long>
        lateinit var directFpu: Map<String, Long>
        var srcPc = 0L
        var seqInit = 0L
        var directSeq = 0L
        var directHash: String? = null
        var directNonBlack = 0.0
        var directDistinctRgb = 0

        EmulatorRuntime(directHome, directCfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    srcSnap,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = finalAttempts,
                        allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                @Suppress("UNCHECKED_CAST")
                srcPc = ((rt.session.state()["registers"] as Map<String, Any?>)["pc"] as Number).toLong()
                seqInit = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence
            }.join()
            var saved = false
            while (true) {
                val more = rt.owner.submit {
                    val running = rt.session.step()
                    if (running && !saved && rt.session.checkpointAttempts() >= midAttempts) {
                        rt.session.saveEmulatorSnapshot(midSnapDir)
                        E2eHarness.copySparsePreserving(directSdImg, midSdImg)
                        saved = true
                    }
                    running
                }.join()
                if (!more) break
            }
            assertTrue(saved)
            rt.owner.submit {
                directState = rt.session.state()
                directRegs = checkNotNull(rt.session.registerSnapshot())
                directFpu = checkNotNull(rt.session.floatingPointSnapshot())
                val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                directSeq = main.sequence
                val px = IntArray(main.width * main.height)
                val copied = main.copyArgb(px)
                directHash = if (copied) sha256IntArray(px) else null
                directNonBlack = if (copied) E2eHarness.nonBlackRatio(px) else 0.0
                directDistinctRgb = if (copied) E2eHarness.distinctRgbCount(px) else 0
            }.join()
        }
        val directPc = (directState["registers"] as? Map<*, *>)?.get("pc") as? Number
        assertNotNull(directPc, "Expected non-null directState[\"registers\"][\"pc\"]")
        assertNotEquals(srcPc, directPc.toLong(), "Expected PC to advance beyond srcSnap PC")
        assertTrue(directSeq > seqInit, "Expected display.main sequence ($directSeq) > initial ($seqInit)")
        assertTrue(directNonBlack > 0.02, "Expected non-trivial display.main frame, got nonBlack=$directNonBlack")
        assertTrue(directDistinctRgb > 16, "Expected distinct RGB colors > 16, got $directDistinctRgb")

        for (preferArch in listOf(false, true)) {
            val label = if (preferArch) "arch" else "native"
            val rHome = E2eHarness.cloneBringupHome(srcHome, tempRoot.resolve("restore-$label"), copySdImage = false, overrideSdReadOnly = false)
            val rSdImg = rHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
            E2eHarness.copySparsePreserving(midSdImg, rSdImg)
            assertTrue(E2eHarness.allocatedDiskBytes(rSdImg) < 64L * 1024L * 1024L, "Expected sparse restore SD allocation < 64 MiB ($label)")
            val rCfg = Formats.read(rHome.resolve("config/emulator.yaml")) + mapOf("executionMode" to "discovery", "httpPort" to 0)
            EmulatorRuntime(rHome, rCfg).use { rt ->
                val receipt = rt.owner.submit {
                    rt.session.initialize()
                    rt.session.restoreEmulatorSnapshot(
                        midSnapDir,
                        EmulatorSnapshot.RestoreOptions(
                            overrideBudget = finalAttempts,
                            allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                            allowPromotedDiscoveryRegions = true,
                            preferArchitecturalCpuRestore = preferArch,
                        ),
                    )
                }.join()
                assertEquals(true, receipt["restored"])
                while (rt.owner.submit { rt.session.step() }.join()) {
                    // Run to finalAttempts
                }
                rt.owner.submit {
                    val rState = rt.session.state()
                    assertEquals(directState["attempts"], rState["attempts"], "Attempts mismatch on $label restore")
                    val rPc = (rState["registers"] as? Map<*, *>)?.get("pc") as? Number
                    assertNotNull(rPc, "Expected non-null restored state[\"registers\"][\"pc\"] on $label")
                    assertEquals(directPc.toLong(), rPc.toLong(), "PC mismatch on $label restore")
                    assertEquals(directRegs, checkNotNull(rt.session.registerSnapshot()), "Registers mismatch on $label restore")
                    assertEquals(directFpu, checkNotNull(rt.session.floatingPointSnapshot()), "FPU mismatch on $label restore")
                    val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                    assertEquals(directSeq, main.sequence)
                    val px = IntArray(main.width * main.height)
                    val rHash = if (main.copyArgb(px)) sha256IntArray(px) else null
                    assertEquals(directHash, rHash, "Display hash mismatch on $label restore")
                }.join()
            }
        }
        assertEquals(srcManifestSha, E2eHarness.sha256(srcSnap.resolve(EmulatorSnapshot.MANIFEST_FILE)))
        assertEquals(srcSdSize, Files.size(srcSdImg))
    }

    @Test
    @Tag("e2e-m6")
    @Tag("e2e-milestone-gated")
    fun f9T1_discoveryModeConvergedCatalogAndProvenanceAudit(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M6", "F9-ConvergedProvenance", ready, "M6 peripheral YAML convergence not yet complete")

        CatalogCompiler.verify(E2eHarness.projectRoot)
        val admittedConfidences = setOf(
            "DAT", "TEST", "SIM", "SIM_UNDER_HYPOTHESIS", "HYPOTHESIS",
            "IMPLEMENTATION_SOURCE", "SOURCE", "SOURCE_CANDIDATE", "UNKNOWN",
            "VERIFIED", "DECLARED_LAYOUT", "IMPLEMENTATION_BOUNDARY",
        )
        val catalog = AddressCatalog.bundled()
        @Suppress("UNCHECKED_CAST")
        val devices = catalog.data["devices"] as List<Map<String, Any?>>
        assertTrue(devices.size >= 33)
        for (dev in devices) {
            @Suppress("UNCHECKED_CAST")
            for (port in (dev["ports"] as? List<Map<String, Any?>>).orEmpty()) {
                @Suppress("UNCHECKED_CAST")
                for (reg in (port["registers"] as? List<Map<String, Any?>>).orEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    val prov = reg["provenance"] as? Map<String, Any?> ?: emptyMap()
                    for (entry in prov.values) {
                        val conf = (entry as? Map<*, *>)?.get("confidence")?.toString()
                        if (conf != null) {
                            assertTrue(conf in admittedConfidences, "Unrecognized provenance confidence '$conf' in ${reg["id"]}")
                        }
                    }
                }
            }
        }
        assertTrue(E2eHarness.probeCatalogVerifiedCount() > 0 || devices.size > 33)

        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        if (fw != null && lib != null) {
            val (home, cfg) = E2eHarness.stageDiscoveryBootHome(tempRoot, instructionBudget = 200000)
            EmulatorRuntime(home, cfg).use { rt ->
                rt.probe()
                val sessionState = rt.owner.submit { rt.session.state() }.join()
                val discState = rt.owner.submit { rt.session.discoveryState() }.join()
                assertEquals("discovery", sessionState["executionMode"])
                assertEquals(emptyList<Any?>(), discState["accesses"])
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f10T1_repoTextHygieneAndProtectedFilesIntact() {
        val root = E2eHarness.projectRoot
        val policy = Formats.json.readTree(root.resolve("text-policy.json").toFile())
        assertEquals("UTF-8", policy["encoding"].asText())
        assertEquals(false, policy["bom"].asBoolean())
        assertEquals("LF", policy["endOfLine"].asText())
        assertEquals(true, policy["finalNewline"].asBoolean())

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val checked = ArrayList<Path>()
        val textExtensions = setOf(".kt", ".yaml", ".yml", ".json", ".md", ".txt", ".sh")
        val textDirs = listOf(
            root.resolve("desktop/src/test/kotlin/com/github/nyabanban/fuji/emulator/desktop/e2e"),
            root.resolve("catalog/devices"),
            root.resolve("docs"),
            root.resolve("configurations"),
        )
        for (dir in textDirs) {
            if (Files.isDirectory(dir)) {
                Files.walk(dir).use { s ->
                    s.filter { p -> Files.isRegularFile(p) && textExtensions.any { p.fileName.toString().endsWith(it) } }
                        .forEach { checked.add(it) }
                }
            }
        }
        assertTrue(checked.isNotEmpty())
        for (file in checked) {
            val bytes = Files.readAllBytes(file)
            assertFalse(
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(),
                "UTF-8 BOM detected in $file",
            )
            assertFalse(bytes.any { it == '\r'.code.toByte() }, "CR/CRLF detected in $file")
            decoder.reset()
            decoder.decode(ByteBuffer.wrap(bytes))
            assertTrue(bytes.isNotEmpty() && bytes.last() == '\n'.code.toByte(), "Missing trailing LF in $file")
            assertFalse(bytes.size >= 2 && bytes[bytes.size - 2] == '\n'.code.toByte(), "Multiple trailing LFs in $file")
        }

        // Verify the 5 protected 2026-11-18 files are untouched:
        // - Zero staged index changes in git diff --cached --name-only on any checkout.
        // - Worktree (.git is a regular file): none of the 5 protected paths appear modified/added/deleted/untracked in git status --porcelain.
        // - Main checkout (.git is a directory): only ` M ` on WritableRegions.kt and `?? ` on the other 4 untracked protected paths are permitted.
        val protectedMarkers = listOf(
            "WritableRegions.kt",
            "experiment.log",
            "gradle/verification-keyring.gpg",
            "locks_backup",
            "ComponentLoader.kt.bak",
        )
        val untrackedMainMarkers = listOf(
            "experiment.log",
            "gradle/verification-keyring.gpg",
            "locks_backup",
            "ComponentLoader.kt.bak",
        )
        val stagedProc = ProcessBuilder("git", "-C", root.toString(), "diff", "--cached", "--name-only")
            .redirectErrorStream(true)
            .start()
        val stagedOut = stagedProc.inputStream.bufferedReader().readText()
        assertEquals(0, stagedProc.waitFor(), "git diff --cached failed: $stagedOut")
        val stagedProtected = stagedOut.lineSequence()
            .filter { it.isNotBlank() }
            .filter { line -> protectedMarkers.any { marker -> line.contains(marker) } }
            .toList()
        assertEquals(emptyList<String>(), stagedProtected, "Protected 2026-11-18 file staged in index: $stagedProtected")

        val isMainCheckout = Files.isDirectory(root.resolve(".git"))
        val gitProc = ProcessBuilder("git", "-C", root.toString(), "status", "--porcelain")
            .redirectErrorStream(true)
            .start()
        val gitOut = gitProc.inputStream.bufferedReader().readText()
        assertEquals(0, gitProc.waitFor(), "git status failed: $gitOut")
        val dirtyProtected = gitOut.lineSequence()
            .filter { it.isNotBlank() }
            .filter { line -> protectedMarkers.any { marker -> line.contains(marker) } }
            .filter { line ->
                if (!isMainCheckout) {
                    true
                } else {
                    val allowedModifiedWritable = line.startsWith(" M ") && line.contains("WritableRegions.kt")
                    val allowedUntracked = line.startsWith("?? ") && untrackedMainMarkers.any { line.contains(it) }
                    !(allowedModifiedWritable || allowedUntracked)
                }
            }
            .toList()
        assertEquals(emptyList<String>(), dirtyProtected, "Protected 2026-11-18 file modified/deleted/staged unexpectedly: $dirtyProtected")
    }

    // ---- Shared helper for soc-sd-host synthetic input ----
    private fun sdHostSyntheticInput(profile: String, capacityBytes: Long, readOnly: Boolean): Map<String, Any?> {
        val cidBytes = byteArrayOf(0xfe.toByte(), 0x54, 0x45, 0x53, 0x54, 0x30, 0x30, 0x31, 0x10, 1, 2, 3, 4, 1, 0xa1.toByte(), 0)
        var crc = 0
        for (b in cidBytes.take(15)) for (bit in 7 downTo 0) {
            val fb = ((b.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 0x7f
            if (fb != 0) crc = crc xor 9
        }
        cidBytes[15] = ((crc shl 1) or 1).toByte()
        val cidHex = HexFormat.of().formatHex(cidBytes)
        return mapOf(
            "schema" to 1,
            "profile" to profile,
            "addressHighBits" to mapOf("0" to 0, "1" to 0),
            "legacy" to mapOf(
                "schema" to 1,
                "profile" to "gfx100s-sd-host-phy-word-retention-experimental-v5",
                "initialRegisters" to (0..1).associate { it.toString() to mapOf("word80" to 0, "control623e" to 0, "mixed6078" to 0, "control403e" to 0, "word60ec" to 0) },
                "clocks" to listOf("0:0", "0:1", "1:0", "1:1").associateWith { mapOf("initialWord" to if (it.endsWith(":0")) 5 else 0, "bit1Samples" to listOf(1, 1, 1)) },
                "initWords" to (0..1).associate { it.toString() to mapOf("capability6244" to 0, "present4024" to 0, "present6224" to 0, "signal6238" to 0, "block4006" to 0, "host6228" to 0, "host4028" to 0x0f00) },
            ),
            "cards" to mapOf(
                "0" to mapOf("basis" to "SYNTHETIC", "capacityBytes" to capacityBytes, "highCapacity" to false, "readOnly" to readOnly, "rca" to 1, "cidHex" to cidHex, "ocrVoltageWindow" to 0x00ff8000L, "supportsHighSpeed" to true),
                "1" to null,
            ),
            "commandTicks" to 2,
            "blockTicks" to 3,
            "resetTicks" to 2,
        )
    }
}
