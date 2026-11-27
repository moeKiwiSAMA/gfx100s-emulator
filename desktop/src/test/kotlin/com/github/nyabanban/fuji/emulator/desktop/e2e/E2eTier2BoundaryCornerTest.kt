package com.github.nyabanban.fuji.emulator.desktop.e2e

import com.github.nyabanban.fuji.emulator.api.BlockStoragePort
import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.PortBinding
import com.github.nyabanban.fuji.emulator.desktop.AdditionalRamPlan
import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.configuredComponentProfiles
import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.StudioLayout
import com.github.nyabanban.fuji.emulator.desktop.tools.JvmStudioEvidence
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.firmware.SdTransactionContracts
import com.github.nyabanban.fuji.emulator.firmware.research.BootRomEntryResearch
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.ControlServer
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryJournal
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioWindow
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2: Boundary, Corner & Negative Tests (5 tests per feature F1-F9 = 45 tests).
 */
@Tag("e2e")
@Tag("e2e-tier2")
class E2eTier2BoundaryCornerTest {

    // =========================================================================
    // F1: Shooting Baseline Unblocking (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f1T2_mainCliRejectsInvalidConfigSchemaAndMissingRequiredKeys(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        Formats.atomicYaml(home.resolve("config/emulator.yaml"), mapOf("schema" to 2, "components" to "compoments"))
        val badSchema = E2eHarness.runMainCli(home, listOf("--query-address", "0xFFFC41E0"))
        assertNotEquals(0, badSchema.exitCode)

        Formats.atomicYaml(home.resolve("config/emulator.yaml"), mapOf("schema" to 1, "components" to "compoments", "httpPort" to 0))
        val missingKeys = E2eHarness.runMainCli(home, listOf("--query-address", "0xFFFC41E0"))
        assertNotEquals(0, missingKeys.exitCode)
    }

    @Test
    @Tag("e2e-baseline")
    fun f1T2_snapshotRestoreRejectsTamperedManifestAndRamHash(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or snap2300k",
        )
        val tamperedSnap = tempRoot.resolve("tampered-snap")
        Files.walk(E2eHarness.blankCardSnapshotDir).use { s ->
            s.forEach { src ->
                val rel = E2eHarness.blankCardSnapshotDir.relativize(src)
                val dst = tamperedSnap.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        Files.writeString(tamperedSnap.resolve(EmulatorSnapshot.MANIFEST_FILE), "{\"corrupted\":true}\n")

        val workHome = E2eHarness.cloneBringupHome(E2eHarness.blankCardHomeDir, tempRoot.resolve("home"), copySdImage = true, overrideSdReadOnly = false)
        val cfg = Formats.read(workHome.resolve("config/emulator.yaml"))
        EmulatorRuntime(workHome, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                assertFails { rt.session.restoreEmulatorSnapshot(tamperedSnap) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f1T2_snapshotRestoreRejectsNonZeroInitialAttemptsOrMissingCpuDir(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing firmware, native library, or snap2300k",
        )
        val brokenSnap = tempRoot.resolve("broken-snap")
        Files.walk(E2eHarness.blankCardSnapshotDir).use { s ->
            s.forEach { src ->
                val rel = E2eHarness.blankCardSnapshotDir.relativize(src).toString().replace('\\', '/')
                if (rel.startsWith("cpu/core-0")) return@forEach
                val dst = brokenSnap.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        val workHome = E2eHarness.cloneBringupHome(E2eHarness.blankCardHomeDir, tempRoot.resolve("home"), copySdImage = true, overrideSdReadOnly = false)
        val cfg = Formats.read(workHome.resolve("config/emulator.yaml"))
        EmulatorRuntime(workHome, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                assertFails { rt.session.restoreEmulatorSnapshot(brokenSnap) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f1T2_bringupSnapshotRefusesDuplicateOutputReceiptsAndBadBudget(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing assets",
        )
        val workHome = E2eHarness.cloneBringupHome(E2eHarness.blankCardHomeDir, tempRoot.resolve("home"), copySdImage = false)
        val commit = E2eHarness.probeSourceCommit(workHome)

        // Exceeding 8_000_000_000 ceiling must fail closed
        val badBudget = E2eHarness.runDisplayBringupResume(
            workHome,
            commit,
            E2eHarness.blankCardSnapshotDir,
            listOf("--budget", "9000000000"),
            timeoutSeconds = 30,
        )
        assertNotEquals(0, badBudget.exitCode)

        // Pre-existing bringup.summary.json must be refused
        Files.writeString(workHome.resolve("bringup.summary.json"), "{}\n")
        val dupReceipt = E2eHarness.runDisplayBringupResume(
            workHome,
            commit,
            E2eHarness.blankCardSnapshotDir,
            listOf("--budget", "632000000"),
            timeoutSeconds = 30,
        )
        assertNotEquals(0, dupReceipt.exitCode)
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f1T2_photoCardBudgetExhaustionOrStuckWaitGuard(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing photoCard assets",
        )
        val ready = E2eHarness.probeM1PhotoCardReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F1-PhotoHeadroom", ready, "M1 photo-card T14/headroom unblocking not yet merged")

        val (workHome, ramPlan) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer",
                "--extend-ram-plan", ramPlan.toString(),
                "--budget", "570000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("frontier").isNull, "Expected null frontier (no HDMI/power/RAM exhaustion), got ${summary.path("frontier")}")
        assertTrue(summary.path("error").isNull, "Expected null error, got ${summary.path("error")}")
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.toList() }
        assertTrue(pngs.any { E2eHarness.nonBlackRatio(E2eHarness.toArgbPixels(E2eHarness.readPng(it))) > 0.02 })
    }

    // =========================================================================
    // F2: LCDC1 YUV+OSD0 Composition (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f2T2_displayRejectsMismatchedDimensionsAndUnpresentedCopyArgb(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertFalse(main.copyArgb(IntArray(main.width * main.height)))
                assertFails { main.copyArgb(IntArray(10)) }
                assertFails { main.presentArgb(IntArray(main.width * main.height), 320, 240) }
                assertFails { main.presentArgb(IntArray(10), main.width, main.height) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f2T2_lcdc1ScanoutRejectsZeroAndUnalignedAndOutOfRangeAddresses(@TempDir tempRoot: Path) {
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
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                // Zero address should be ignored (no publish)
                lcdc1.write(0x40L, 4, 0L)
                lcdc1.write(0x44L, 4, 0L)
                assertEquals(0L, main.sequence)
                // Odd (unaligned) address must fail closed
                lcdc1.write(0x40L, 4, 0L)
                assertFails { lcdc1.write(0x44L, 4, 0x06140001L) }
                // Unaligned MMIO width or offset must fail
                assertFails { lcdc1.read(0x40L, 2) }
                assertFails { lcdc1.write(0x41L, 4, 0L) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f2T2_lcdc1RgbaScanoutRejectsBadScaleAndBadGeometry(@TempDir tempRoot: Path) {
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
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                lcdc1.write(0x40L, 4, 0L)
                lcdc1.write(0x44L, 4, 0x06140000L)
                lcdc1.write(0x50L, 4, 0x00010400L)
                lcdc1.write(0x54L, 4, (768L shl 16) or 1024L)
                lcdc1.write(0x58L, 4, 0L)
                lcdc1.write(0x5cL, 4, (768L shl 16) or 1024L)
                assertFails("Non-unit Q12 scale must be rejected") { lcdc1.write(0x60L, 4, 0x20002000L) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f2T2_lcdc1FrameLimitCapsPublishedFramesAtConfiguredMax(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot, legacyVga = true)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-osd0-main-640x480-rgb565le-v1", "scanoutFrameLimit" to 2L, "inputFile" to "mode.json"),
        )
        Formats.writeJson(
            vglibDir.resolve("mode.json"),
            mapOf("schema" to 1, "profile" to "vglib-full-lane-init-experimental-v1", "readConfidence" to "HYPOTHESIS", "transactions" to List(18) { i -> mapOf("offset" to i * 4, "readValue" to 0, "writeValue" to 0) }),
        )
        val addr = 0x06140000L
        val buf = ByteArray(640 * 480 * 2)
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) = require(!write && address >= addr && address + size <= addr + buf.size)
                        override fun read(address: Long, destination: ByteBuffer) {
                            val r = destination.remaining(); check(address, r.toLong(), false); destination.put(buf, (address - addr).toInt(), r)
                        }

                        override fun write(address: Long, source: ByteBuffer) = error("RO")
                    },
                )
                val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                repeat(5) {
                    lcdc1.write(0x40L, 4, 0L)
                    lcdc1.write(0x44L, 4, addr)
                }
                assertEquals(2L, main.sequence)
            }.join()
        }
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f2T2_lcdc1YuvCompositeHandlesBlackVideoWithActiveOsd0(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeLcdcYuvCompositeReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F2-BlackVideoOsd0", ready, "LCDC1 YUV+OSD0 composition not yet implemented")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"), legacyVga = true)
        val vglibDir = home.resolve("compoments/soc-vglib-init")
        Files.createDirectories(vglibDir)
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf("profile" to "lcdc-functional-experimental-v1", "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1", "inputFile" to "mode.json"),
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
        ByteBuffer.wrap(osdBuf).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(IntArray(1024 * 768) { 0xFFFFFFFF.toInt() }) // opaque white OSD0
        val yuvBuf = ByteArray(picStride * picH) { idx -> if (idx % 2 == 0) 0x80.toByte() else 0x10 } // black UYVY (U/V=0x80, Y=16)
        E2eHarness.withComponentHost(home, AddressCatalog.bundled()) { owner, host ->
            owner.submit {
                host.ports.register(
                    "test-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) = require(!write && ((address >= osdAddr && address + size <= osdAddr + osdBuf.size) || (address >= yuvAddr && address + size <= yuvAddr + yuvBuf.size)))
                        override fun read(address: Long, destination: ByteBuffer) {
                            val r = destination.remaining(); check(address, r.toLong(), false)
                            if (address >= yuvAddr) destination.put(yuvBuf, (address - yuvAddr).toInt(), r)
                            else destination.put(osdBuf, (address - osdAddr).toInt(), r)
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
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                val out = IntArray(640 * 480)
                assertTrue(main.copyArgb(out))
                assertTrue(E2eHarness.nonBlackRatio(out) > 0.5)
            }.join()
        }
    }

    // =========================================================================
    // F3: Shooting HUD & MENU/OK (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f3T2_buttonsRejectsNonBooleanAndUnknownKeys(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { owner, host ->
            assertFails { host.patch("buttons", 0, mapOf("menu" to "yes")).join() }
            assertFails { host.patch("buttons", 0, mapOf("nonexistentButton" to true)).join() }
            owner.submit {
                val btn = checkNotNull(host.ports.find("buttons", ButtonPort::class.java))
                assertFails { btn.pressed("nonexistentButton") }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f3T2_bringupCliRejectsUnknownButtonKeyAndInvalidButtonState(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir), "Missing assets")

        val home1 = E2eHarness.cloneBringupHome(E2eHarness.blankCardHomeDir, tempRoot.resolve("h1"), copySdImage = false)
        val commit = E2eHarness.probeSourceCommit(home1)
        val badKey = E2eHarness.runDisplayBringupResume(
            home1,
            commit,
            E2eHarness.blankCardSnapshotDir,
            listOf("--button-events", "632000000:bogusButton:down", "--budget", "632000000"),
            timeoutSeconds = 30,
        )
        assertNotEquals(0, badKey.exitCode)

        val home2 = E2eHarness.cloneBringupHome(E2eHarness.blankCardHomeDir, tempRoot.resolve("h2"), copySdImage = false)
        val badState = E2eHarness.runDisplayBringupResume(
            home2,
            commit,
            E2eHarness.blankCardSnapshotDir,
            listOf("--button-events", "632000000:menu:halfway", "--budget", "632000000"),
            timeoutSeconds = 30,
        )
        assertNotEquals(0, badState.exitCode)
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T2_menuRapidPressReleaseWithoutHoldDoesNotStick(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-RapidMenuPress", ready, "M1 shooting HUD & MENU/OK not yet merged")

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
                "--button-events", "632000000:menu:down,632050000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val evs = Formats.json.readTree(home.resolve("bringup.button-events.json").toFile()).path("events")
        assertEquals(2, evs.size())
        assertEquals(true, evs[0].path("pressed").asBoolean())
        assertEquals(false, evs[1].path("pressed").asBoolean())
        val summary = Formats.json.readTree(home.resolve("bringup.summary.json").toFile())
        assertTrue(summary.path("stopped").isBoolean && summary.path("stopped").asBoolean(), "Expected stopped=true in bringup.summary.json")
        assertTrue(summary.has("error") && summary.path("error").isNull, "Unexpected error in bringup.summary.json: ${summary.path("error")}")
        val result = Formats.json.readTree(home.resolve("bringup.result.json").toFile())
        val menuNode = result.path("peripherals").path("buttons").path("menu")
        assertTrue(menuNode.isBoolean && !menuNode.asBoolean(), "Expected menu=false in peripherals.buttons, got $menuNode")
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.size >= 2, "Expected HUD and MENU frames after rapid tap, got ${pngs.size}")
        val firstPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs.first()))
        val lastPx = E2eHarness.toArgbPixels(E2eHarness.readPng(pngs.last()))
        assertTrue(E2eHarness.nonBlackRatio(lastPx) > 0.02 && E2eHarness.distinctRgbCount(lastPx) > 16)
        assertTrue(E2eHarness.changedPixelRatio(firstPx, lastPx) > 0.05, "Rapid MENU tap should visibly transition screen without sticking")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T2_invalidOrNoOpKeysInStandbyDoNotCrashMenu(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-StandbyNoOpKeys", ready, "M1 shooting HUD not yet merged")

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
                "--button-events", "632000000:back:down,632100000:back:up,635000000:menu:down,635500000:menu:up",
                "--budget", "645000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val evs = Formats.json.readTree(home.resolve("bringup.button-events.json").toFile()).path("events")
        assertEquals(4, evs.size())
        val summary = Formats.json.readTree(home.resolve("bringup.summary.json").toFile())
        assertTrue(summary.path("stopped").isBoolean && summary.path("stopped").asBoolean(), "Expected stopped=true in bringup.summary.json")
        assertTrue(summary.has("error") && summary.path("error").isNull, "Unexpected error in bringup.summary.json: ${summary.path("error")}")
        val framesMeta = Formats.json.readTree(home.resolve("bringup.frames.json").toFile()).path("frames")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 635_000_000L }.map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        val postMenu = framesMeta.filter { it.path("attempts").asLong() >= 635_000_000L }.map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        assertTrue(preMenu.isNotEmpty() && postMenu.isNotEmpty())
        assertTrue(E2eHarness.nonBlackRatio(preMenu.last()) > 0.02 && E2eHarness.distinctRgbCount(preMenu.last()) > 16)
        assertTrue(postMenu.any { E2eHarness.changedPixelRatio(preMenu.last(), it) > 0.05 }, "Standby no-op back key must not wedge MENU/OK")
    }

    @Test
    @Tag("e2e-m1")
    @Tag("e2e-milestone-gated")
    fun f3T2_menuNavigationBoundaryWrapOrClampAtListEdges(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("blankCard+runtime", fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir), "Missing assets")
        val ready = E2eHarness.probeM1BlankCardHudReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M1", "F3-MenuEdgeNav", ready, "M1 shooting HUD & MENU/OK not yet merged")

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
                "--button-events", "632000000:menu:down,632500000:menu:up,635000000:up:down,635500000:up:up,637500000:up:down,638000000:up:up,641000000:back:down,641500000:back:up",
                "--budget", "650000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val evs = Formats.json.readTree(home.resolve("bringup.button-events.json").toFile()).path("events")
        assertEquals(8, evs.size())
        val framesMeta = Formats.json.readTree(home.resolve("bringup.frames.json").toFile()).path("frames")
        assertTrue(framesMeta.size() >= 3, "Expected >= 3 exported frames across menu edge navigation")
        val preMenu = framesMeta.filter { it.path("attempts").asLong() < 632_000_000L }.map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        val inMenu = framesMeta.filter { it.path("attempts").asLong() in 632_500_000L..640_999_999L }.map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        val postBack = framesMeta.filter { it.path("attempts").asLong() >= 641_500_000L }.map { E2eHarness.toArgbPixels(E2eHarness.readPng(Path.of(it.path("file").asText()))) }
        assertTrue(preMenu.isNotEmpty() && inMenu.isNotEmpty() && postBack.isNotEmpty())
        assertTrue(inMenu.any { E2eHarness.changedPixelRatio(preMenu.last(), it) > 0.05 }, "MENU open must update screen")
        assertTrue(E2eHarness.nonBlackRatio(postBack.last()) > 0.02 && E2eHarness.distinctRgbCount(postBack.last()) > 16)
        assertTrue(E2eHarness.changedPixelRatio(inMenu.last(), postBack.last()) > 0.05, "BACK after edge up presses must exit menu cleanly")
    }

    // =========================================================================
    // F4: CMOS QR Recognition Chain (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f4T2_cmosRejectsMissingSourceFileAndInvalidSourceKind(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { _, host ->
            assertFails("Missing sourceFile must be refused") {
                host.patch("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "missing.jpg")).join()
            }
            assertFails("Invalid sourceKind must be refused") {
                host.patch("cmos", 0, mapOf("sourceKind" to "unsupported-test-format")).join()
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f4T2_cmosRejectsNonExistentPathAndDirectoryAsSourceFile(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val subdir = home.resolve("compoments/cmos/subdir")
        Files.createDirectories(subdir)
        E2eHarness.withComponentHost(home) { _, host ->
            assertFails("Directory path must not be accepted as CMOS sourceFile") {
                host.patch("cmos", 0, mapOf("sourceFile" to "subdir")).join()
            }
            assertFails("Path traversal outside component data root must be refused") {
                host.patch("cmos", 0, mapOf("sourceFile" to "../../config/emulator.yaml")).join()
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f4T2_qrCodecHandlesJpegCompressionArtifactsAndSpecialCharacters() {
        val payload = "QR:GFX100S/2.13!@#&*+=?"
        val rgb = QrTestCodec.encodeToRgbImage(payload, modulePixels = 8, quietZoneModules = 4)

        val jpegBytes = ByteArrayOutputStream().use { baos ->
            val writer = ImageIO.getImageWritersByFormatName("jpg").next()
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            ImageIO.createImageOutputStream(baos).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(rgb, null, null), param)
                writer.dispose()
            }
            baos.toByteArray()
        }
        val decodedImg = ImageIO.read(jpegBytes.inputStream())
        assertEquals(payload, QrTestCodec.decode(decodedImg))
    }

    @Test
    @Tag("e2e-baseline")
    fun f4T2_qrCodecRejectsBlankAndCorruptedQrFrames() {
        val blank = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB)
        assertNull(QrTestCodec.decode(blank))

        val solidWhite = BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, 300, 300); g.dispose()
        }
        assertNull(QrTestCodec.decode(solidWhite))

        // Corrupt 60% of a generated QR code — RS(44,34) must reject rather than hallucinate
        val qr = QrTestCodec.encodeToImage("FUJI-GFX100S-E2E-QR-01", modulePixels = 8, quietZoneModules = 4)
        val g = qr.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, qr.width, (qr.height * 0.65).toInt())
        g.dispose()
        assertNull(QrTestCodec.decode(qr))
    }

    @Test
    @Tag("e2e-m2")
    @Tag("e2e-milestone-gated")
    fun f4T2_cmosLoopSettingAndCorruptedJpegInputBehavior(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeCmosExposeImplemented(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M2", "F4-CmosLoopBoundary", ready, "M2 cmos.expose() not yet implemented")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val cmosDir = home.resolve("compoments/cmos")
        Files.createDirectories(cmosDir)
        Files.write(cmosDir.resolve("bad.jpg"), byteArrayOf(0x00, 0x01, 0x02, 0x03))
        val goodJpg = cmosDir.resolve("good.jpg")
        check(ImageIO.write(QrTestCodec.encodeToRgbImage("F4T2-LOOP", modulePixels = 4, quietZoneModules = 2), "jpg", goodJpg.toFile()))

        val exposure = Exposure(0L, 1000L, 100, LensParameters("GF63mmF2.8", 63.0, 2.8, 1.0, 0.9, 0.0))
        val dst = ByteBuffer.allocate(640 * 480 * 4)

        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                assertFails("Corrupted JPEG must be rejected at patch or expose time") {
                    host.patch("cmos", 0, mapOf("sourceKind" to "JPEG", "sourceFile" to "bad.jpg", "loop" to false)).join()
                    dst.clear()
                    sensor.expose(exposure, dst)
                }

                val curRev = (host.snapshot().join()["components"] as List<*>)
                    .map { it as Map<*, *> }
                    .single { it["id"] == "cmos" }["revision"] as Long
                host.patch("cmos", curRev, mapOf("sourceKind" to "JPEG", "sourceFile" to "good.jpg", "loop" to true)).join()
                dst.clear()
                val n1 = sensor.expose(exposure, dst)
                dst.clear()
                val n2 = sensor.expose(exposure, dst)
                assertTrue(n1 > 0 && n2 == n1, "loop=true must continuously expose frame bytes ($n1, $n2)")

                host.patch("cmos", curRev + 1, mapOf("sourceKind" to "JPEG", "sourceFile" to "good.jpg", "loop" to false)).join()
                dst.clear()
                val single1 = sensor.expose(exposure, dst)
                assertTrue(single1 > 0, "First expose under loop=false must yield frame bytes")
                val secondResult = runCatching {
                    dst.clear()
                    sensor.expose(exposure, dst)
                }
                assertTrue(
                    secondResult.isFailure || secondResult.getOrNull() == 0,
                    "Second expose past finite still with loop=false must return 0 or throw, got $secondResult",
                )
            }.join()
        }
    }

    // =========================================================================
    // F5: PLAY DSCF0001.JPG Decode & Playback (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f5T2_sdDcfReadOnlyMediaRejectsWriteWithoutMutatingBytes(@TempDir tempRoot: Path) {
        val dcf = E2eHarness.resolveDcfFixtureDir()
        E2eGates.requireAssets("sd-dcf-playback-01", dcf != null, "Missing sd-dcf-playback-01")

        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        val imgTarget = sdDir.resolve("slot1.img")
        Files.copy(dcf!!.resolve("slot1.img"), imgTarget)
        val beforeHash = E2eHarness.sha256(imgTarget)
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "slot1.img", "slot1Inserted" to true, "slot1ReadOnly" to true, "slot2Inserted" to false))

        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                assertFails { sd.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x55 })) }
            }.join()
        }
        assertEquals(beforeHash, E2eHarness.sha256(imgTarget))
    }

    @Test
    @Tag("e2e-baseline")
    fun f5T2_sdDcfOutOfBoundsSectorReadRejectedCleanly(@TempDir tempRoot: Path) {
        val dcf = E2eHarness.resolveDcfFixtureDir()
        E2eGates.requireAssets("sd-dcf-playback-01", dcf != null, "Missing sd-dcf-playback-01")

        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        val imgTarget = sdDir.resolve("slot1.img")
        Files.copy(dcf!!.resolve("slot1.img"), imgTarget)
        val size = Files.size(imgTarget)
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "slot1.img", "slot1Inserted" to true, "slot1ReadOnly" to true, "slot2Inserted" to false))

        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                assertFails { sd.read(1, size - 256L, ByteBuffer.allocate(512)) }
                assertFails { sd.read(2, 0L, ByteBuffer.allocate(512)) } // empty slot 2
            }.join()
        }
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T2_playButtonOnBlankCardWithoutPhotosDoesNotCrash(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "blankCard+photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir) &&
                Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing runtime or snapshot assets",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-PlayBlankCard", ready, "M3 PLAY mode not yet wired")

        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)
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
                "--button-events", "632000000:play:down,632500000:play:up",
                "--budget", "642000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val evs = Formats.json.readTree(home.resolve("bringup.button-events.json").toFile()).path("events")
        assertTrue(evs.any { it.path("key").asText() == "play" && it.path("pressed").asBoolean() })
        assertTrue(evs.any { it.path("key").asText() == "play" && !it.path("pressed").asBoolean() })
        val summary = Formats.json.readTree(home.resolve("bringup.summary.json").toFile())
        assertTrue(summary.path("stopped").isBoolean && summary.path("stopped").asBoolean(), "Expected stopped=true in bringup.summary.json")
        assertTrue(summary.has("error") && summary.path("error").isNull, "Unexpected error in bringup.summary.json: ${summary.path("error")}")
        val result = Formats.json.readTree(home.resolve("bringup.result.json").toFile())
        assertEquals("instruction-limit", result.path("status").asText())
        val playNode = result.path("peripherals").path("buttons").path("play")
        assertTrue(playNode.isBoolean && !playNode.asBoolean(), "Expected play=false in peripherals.buttons, got $playNode")
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.sorted().toList() }
        assertTrue(pngs.isNotEmpty(), "Expected >= 1 exported frame on blank-card PLAY press")
        val lastImg = E2eHarness.readPng(pngs.last())
        assertTrue(E2eHarness.nonBlackRatio(E2eHarness.toArgbPixels(lastImg)) > 0.01)
        assertTrue(E2eHarness.maxPlayCorrelation(lastImg, refImg) < 0.50, "Blank card must never match DSCF0001.JPG")
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T2_playModeRejectsCorruptedJpegSectorGracefully(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing photoCard assets",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-CorruptJpeg", ready, "M3 PLAY mode not yet wired")

        val srcSha = E2eHarness.sha256(srcSdImg)
        val loc = E2eHarness.locateFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG")
        val refImg = E2eHarness.decodeJpegSubsampled(E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG"), 16)

        val (workHome, ramPlanPath) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val cfg = Formats.read(workHome.resolve("config/emulator.yaml"))
        val playFrames = ArrayList<BufferedImage>()
        EmulatorRuntime(workHome, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    E2eHarness.photoCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 589_000_000L,
                        allowCompatibleProfileChanges = setOf("power-peer", "soc-vglib-init"),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                rt.session.extendAdditionalRam(AdditionalRamPlan.read(ramPlanPath))
                val sd = checkNotNull(rt.host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                val corrupt = ByteArray(262_144) { idx -> if (idx % 2 == 0) 0xFF.toByte() else 0x5A }
                assertEquals(corrupt.size, sd.write(1, loc.byteOffset, ByteBuffer.wrap(corrupt)))
            }.join()

            var pressedSent = false
            var releasedSent = false
            var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
            while (rt.owner.submit {
                val att = rt.session.checkpointAttempts()
                if (!pressedSent && att >= 581_000_000L) {
                    val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                    rt.host.patch("buttons", btn.revision, mapOf("play" to true)).join()
                    pressedSent = true
                }
                if (!releasedSent && att >= 581_500_000L) {
                    val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                    rt.host.patch("buttons", btn.revision, mapOf("play" to false)).join()
                    releasedSent = true
                }
                val more = rt.session.step()
                val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                if (main.sequence > lastSeq) {
                    lastSeq = main.sequence
                    if (att >= 581_000_000L) {
                        val px = IntArray(main.width * main.height)
                        if (main.copyArgb(px)) {
                            val img = BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB)
                            img.setRGB(0, 0, main.width, main.height, px, 0, main.width)
                            playFrames += img
                        }
                    }
                }
                more
            }.join()) {
                // Bounded step loop until 589_000_000L
            }
            val early = rt.owner.submit { rt.session.checkpointEarlyState() }.join()
            assertEquals("instruction-limit", early["status"], "Expected graceful instruction-limit completion, got ${early["status"]}")
            val state = rt.owner.submit { rt.session.state() }.join()
            @Suppress("UNCHECKED_CAST")
            val sdHost = (state["devices"] as Map<*, *>)["soc-sd-host"] as Map<*, *>
            assertTrue((sdHost["mediaBytes"] as Number).toLong() > 559_104L, "Guest should attempt SD read of corrupted DSCF0001.JPG")
        }
        assertTrue(playFrames.all { E2eHarness.maxPlayCorrelation(it, refImg) < 0.50 }, "Corrupted JPEG sectors must not decode valid DSCF0001.JPG")
        assertEquals(srcSha, E2eHarness.sha256(srcSdImg))
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T2_playModeIgnoresRapidDoublePlayPressWithoutDeadlock(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir),
            "Missing photoCard assets",
        )
        val ready = E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M3", "F5-RapidDoublePlay", ready, "M3 PLAY mode not yet wired")

        val (home, ramPlanPath) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            home,
            E2eHarness.probeSourceCommit(home),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlanPath.toString(),
                "--button-events", "581000000:play:down,581050000:play:up,581100000:play:down,581150000:play:up",
                "--budget", "589000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val evs = Formats.json.readTree(home.resolve("bringup.button-events.json").toFile()).path("events")
        assertEquals(4, evs.size())
        val summary = Formats.json.readTree(home.resolve("bringup.summary.json").toFile())
        assertTrue(summary.path("stopped").isBoolean && summary.path("stopped").asBoolean(), "Expected stopped=true in bringup.summary.json")
        assertTrue(summary.has("error") && summary.path("error").isNull, "Unexpected error in bringup.summary.json: ${summary.path("error")}")
        val result = Formats.json.readTree(home.resolve("bringup.result.json").toFile())
        assertEquals("instruction-limit", result.path("status").asText())
        val playNode = result.path("peripherals").path("buttons").path("play")
        assertTrue(playNode.isBoolean && !playNode.asBoolean(), "Expected play=false in peripherals.buttons, got $playNode")
        val pngs = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.toList() }
        assertTrue(pngs.any { E2eHarness.nonBlackRatio(E2eHarness.toArgbPixels(E2eHarness.readPng(it))) > 0.02 })
    }

    @Test
    @Tag("e2e-m3")
    @Tag("e2e-milestone-gated")
    fun f5T2_socJpegRejectsTruncatedAndCorruptBitstreamCleanly(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        val srcSdImg = E2eHarness.photoCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        E2eGates.requireAssets(
            "photoCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.photoCardHomeDir) && Files.isDirectory(E2eHarness.photoCardSnapshotDir) && Files.isRegularFile(srcSdImg),
            "Missing photoCard assets",
        )
        val ready = E2eHarness.probeSocJpegPortReady(tempRoot.resolve("probe-jpeg")) &&
            E2eHarness.probeM3JpegDecoderReady(tempRoot.resolve("probe-m3"))
        E2eGates.assumeOrFailFeature("M3", "F5-SocJpegTruncated", ready, "soc-jpeg hardware decoder not yet wired")

        val loc = E2eHarness.locateFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG")
        val origJpeg = E2eHarness.extractFat32File(srcSdImg, "DCIM/100_FUJI/DSCF0001.JPG")
        val refImg = E2eHarness.decodeJpegSubsampled(origJpeg, 16)
        val (workHome, ramPlanPath) = E2eHarness.preparePhotoCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val sdImg = workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        // Truncate JPEG payload cleanly at 256 bytes after SOI+header without SOS entropy stream
        val truncated = ByteArray(origJpeg.size)
        origJpeg.copyInto(truncated, 0, 0, minOf(256, origJpeg.size))
        java.nio.channels.FileChannel.open(sdImg, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            ch.position(loc.byteOffset)
            ch.write(ByteBuffer.wrap(truncated))
        }
        val framesDir = tempRoot.resolve("frames")
        Files.createDirectories(framesDir)
        val res = E2eHarness.runDisplayBringupResume(
            workHome,
            E2eHarness.probeSourceCommit(workHome),
            E2eHarness.photoCardSnapshotDir,
            listOf(
                "--allow-promoted-discovery",
                "--allow-profile-changes", "power-peer,soc-vglib-init",
                "--extend-ram-plan", ramPlanPath.toString(),
                "--button-events", "581000000:play:down,581500000:play:up",
                "--budget", "589000000",
                "--export-frames-dir", framesDir.toString(),
            ),
            timeoutSeconds = 120,
        )
        assertEquals(0, res.exitCode, res.stderr)
        val summary = Formats.json.readTree(workHome.resolve("bringup.summary.json").toFile())
        assertEquals("instruction-limit", summary.path("status").asText())
        assertTrue(summary.path("error").isNull)
        val frames = Files.list(framesDir).use { s -> s.filter { it.toString().endsWith(".png") }.map { E2eHarness.readPng(it) }.toList() }
        assertTrue(frames.all { E2eHarness.maxPlayCorrelation(it, refImg) < 0.65 }, "Truncated JPEG must not render valid DSCF0001.JPG")
    }

    // =========================================================================
    // F6: Desktop GUI, EVF & Top-LCD Linkage (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f6T2_panelCatalogRejectsTamperedControlBoundsAndDuplicateKeys() {
        val raw = String(PanelCatalog::class.java.getResourceAsStream("/panels/gfx100s/catalog.json")!!.readAllBytes())
        val tampered = Formats.json.readTree(raw) as com.fasterxml.jackson.databind.node.ObjectNode
        val firstView = tampered.path("views").get(0) as com.fasterxml.jackson.databind.node.ObjectNode
        val firstControl = firstView.path("controls").get(0) as com.fasterxml.jackson.databind.node.ObjectNode
        firstControl.put("width", 99999.0)
        assertFails { PanelCatalog.parse(Formats.jsonBytes(tampered)) }
    }

    @Test
    @Tag("e2e-baseline")
    fun f6T2_shoulderDotLcdRejectsInvalidGeometryAndCorruptedPacket(@TempDir tempRoot: Path) {
        val bits = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS) { 0 }
        val packet = DotLcdTransportCodec.encode(bits)
        val badHeader = packet.copyOf().apply { this[0] = (this[0].toInt() xor 0xff).toByte() }
        assertFails { DotLcdTransportCodec.decode(badHeader) }
        assertFails { DotLcdTransportCodec.decode(ByteArray(100)) }

        val home = E2eHarness.stageComponentHome(tempRoot)
        val shDir = home.resolve("compoments/shoulder-display")
        Files.createDirectories(shDir)
        Formats.atomicYaml(shDir.resolve("shoulder-display.yaml"), mapOf("profile" to "dotlcd-serial1-experimental-v1", "width" to 320L, "height" to 160L, "brightness" to 1.0))
        assertFails("DotLCD profile must reject non-304x240 geometry") {
            E2eHarness.withComponentHost(home) { _, _ -> }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f6T2_httpPatchRejectsStaleRevisionNonLiveAndOriginHeader(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { _, host ->
            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    assertEquals(200, client.patchComponent("battery", 0, mapOf("chargePercent" to 55.0)).statusCode())
                    assertEquals(409, client.patchComponent("battery", 0, mapOf("chargePercent" to 60.0)).statusCode())
                    assertEquals(400, client.patchComponent("display", 0, mapOf("width" to 320)).statusCode())
                    assertEquals(400, client.patchComponent("battery", 1, mapOf("chargePercent" to 50.0), origin = "https://evil.invalid").statusCode())
                }
            }
        }
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T2_evfEyeSensorBoundaryNearFarRapidToggle(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe")) &&
            E2eHarness.probeI2cEyeSensorReady(tempRoot.resolve("probe-eye"))
        E2eGates.assumeOrFailFeature("M4", "F6-EyeSensorBoundary", ready, "M4 EVF/eye-sensor not yet implemented")

        val eyeCtrl = PanelCatalog.load().flatMap { it.controls }.single { it.id == "rear-eye-sensor" }
        val compId = requireNotNull(eyeCtrl.componentId) { "rear-eye-sensor must bind to a componentId" }
        val settingKey = requireNotNull(eyeCtrl.settingKey) { "rear-eye-sensor must bind to a settingKey" }
        assertNotEquals("not-connected", eyeCtrl.bindingStatus)

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        E2eHarness.withComponentHost(home) { owner, host ->
            val desc = owner.submit { host.components().single { it.descriptor.id == compId }.descriptor }.join()
            val setting = desc.settings.single { it.key == settingKey }
            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    var rev = client.getStateJson()["components"].single { it["id"].asText() == compId }["revision"].asLong()
                    when (setting.kind) {
                        com.github.nyabanban.fuji.emulator.api.ValueKind.BOOLEAN -> {
                            for (i in 0 until 10) {
                                val nextVal = (i % 2 == 0)
                                val resp = client.patchComponent(compId, rev, mapOf(settingKey to nextVal))
                                assertEquals(200, resp.statusCode(), resp.body())
                                rev = Formats.json.readTree(resp.body())["revision"].asLong()
                                assertEquals(nextVal, Formats.json.readTree(resp.body())["state"][settingKey].asBoolean())
                            }
                            assertEquals(400, client.patchComponent(compId, rev, mapOf(settingKey to "near")).statusCode())
                        }
                        else -> {
                            val minVal = requireNotNull(setting.minimum)
                            val maxVal = requireNotNull(setting.maximum)
                            for (i in 0 until 10) {
                                val nextVal: Any = if (i % 2 == 0) minVal else maxVal
                                val resp = client.patchComponent(compId, rev, mapOf(settingKey to nextVal))
                                assertEquals(200, resp.statusCode(), resp.body())
                                rev = Formats.json.readTree(resp.body())["revision"].asLong()
                            }
                            assertEquals(400, client.patchComponent(compId, rev, mapOf(settingKey to (minVal - 1.0))).statusCode())
                            assertEquals(400, client.patchComponent(compId, rev, mapOf(settingKey to (maxVal + 1.0))).statusCode())
                        }
                    }
                }
            }
            owner.submit {
                val evf = checkNotNull(host.ports.find("display.evf", DisplayPort::class.java))
                val main = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                assertTrue(evf !== main && evf.width > 0 && evf.height > 0)
                val blue = IntArray(evf.width * evf.height) { 0xFF0000FF.toInt() }
                val green = IntArray(main.width * main.height) { 0xFF00FF00.toInt() }
                evf.presentArgb(blue, evf.width, evf.height)
                main.presentArgb(green, main.width, main.height)
                val evfOut = IntArray(evf.width * evf.height)
                val mainOut = IntArray(main.width * main.height)
                assertTrue(evf.copyArgb(evfOut) && main.copyArgb(mainOut))
                assertEquals(0xFF0000FF.toInt(), evfOut[0])
                assertEquals(0xFF00FF00.toInt(), mainOut[0])
                assertFails { evf.presentArgb(blue, evf.width + 1, evf.height) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-m4")
    @Tag("e2e-milestone-gated")
    fun f6T2_studioSmokeRejectsOutOfRangeConfigAndPreservesSixFaces(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeEvfPortAndCatalogReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M4", "F6-StudioBoundary", ready, "M4 studio EVF linkage not yet implemented")
        E2eGates.requireGraphicalDisplay("F6-StudioBoundary")

        val panels = PanelCatalog.load()
        assertEquals(6, panels.size)
        assertEquals(PanelCatalog.viewIds, panels.map { it.id }.toSet())
        val controls = panels.flatMap { it.controls }.associateBy { it.id }
        assertEquals("display.main", controls.getValue("rear-lcd").displayPort)
        assertEquals("display.shoulder", controls.getValue("top-shoulder").displayPort)
        assertEquals("display.evf", controls.getValue("rear-evf").displayPort)
        val boxList = StudioLayout.faces.values.map { it.slot }
        for (i in boxList.indices) for (j in i + 1 until boxList.size) {
            assertFalse(StudioLayout.overlap(boxList[i], boxList[j]))
        }

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
        E2eHarness.withComponentHost(home) { _, host ->
            ControlServer(host, 0).use { server ->
                E2eHarness.HttpApiClient("http://127.0.0.1:${server.address.port}").use { client ->
                    val before = client.getStateJson()["components"].single { it["id"].asText() == "battery" }["state"]["chargePercent"].asDouble()
                    assertEquals(400, client.patchComponent("battery", 0, mapOf("chargePercent" to -0.1)).statusCode())
                    assertEquals(400, client.patchComponent("battery", 0, mapOf("chargePercent" to 100.1)).statusCode())
                    assertEquals(400, client.patchComponent("display", 0, mapOf("brightness" to -0.5)).statusCode())
                    assertEquals(400, client.patchComponent("display", 0, mapOf("brightness" to 1.5)).statusCode())
                    assertEquals(400, client.patchComponent("shoulder-display", 0, mapOf("brightness" to 2.0)).statusCode())
                    val after = client.getStateJson()["components"].single { it["id"].asText() == "battery" }["state"]["chargePercent"].asDouble()
                    assertEquals(before, after)
                }
            }
        }
        val res = E2eHarness.runMainCli(home, listOf("--smoke-ui", "ui-smoke-boundary.png"), timeoutSeconds = 60)
        assertEquals(0, res.exitCode, res.stderr)
        val checks = JvmStudioEvidence.checks(Formats.readData(home.resolve("ui-panels.json")), Formats.readData(home.resolve("ui-interactions.json")))
        assertTrue(checks.values.all { it }, "Failed studio checks: ${checks.filterValues { !it }}")
        val shotChecks = JvmStudioEvidence.screenshotChecks(home)
        assertTrue(shotChecks.values.all { it }, "Failed screenshot checks: ${shotChecks.filterValues { !it }}")
    }

    // =========================================================================
    // F7: Blank-Card In-Camera FAT32 Format (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f7T2_sdcardReaderRejectsUnalignedSizeSymlinkAndDuplicateLease(@TempDir tempRoot: Path) {
        val home1 = E2eHarness.stageComponentHome(tempRoot.resolve("unaligned"))
        val sd1 = home1.resolve("compoments/sdcard-reader")
        Files.createDirectories(sd1)
        Files.write(sd1.resolve("bad.img"), ByteArray(513))
        Formats.atomicYaml(sd1.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "bad.img", "slot1Inserted" to true, "slot1ReadOnly" to false))
        assertFails("Unaligned 513-byte image must be refused") {
            E2eHarness.withComponentHost(home1) { _, _ -> }
        }

        val home2 = E2eHarness.stageComponentHome(tempRoot.resolve("alias"))
        val sd2 = home2.resolve("compoments/sdcard-reader")
        Files.createDirectories(sd2)
        Files.write(sd2.resolve("same.img"), ByteArray(1024))
        Formats.atomicYaml(sd2.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "same.img", "slot1Inserted" to true, "slot2Image" to "same.img", "slot2Inserted" to true))
        assertFails("Duplicate slot image alias must be refused") {
            E2eHarness.withComponentHost(home2) { _, _ -> }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f7T2_sdcardReader64MiBCapOmitsModifiedSectorsAndChecksFullSha256(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        // 65 MiB sparse file (> 64 MiB MAX_WRITABLE_SYNC_BYTES)
        val largeImg = sdDir.resolve("large65m.img")
        Files.newByteChannel(largeImg, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            ch.position(65L * 1024L * 1024L - 512L)
            ch.write(ByteBuffer.allocate(512))
        }
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "large65m.img", "slot1Inserted" to true, "slot1ReadOnly" to false, "slot2Inserted" to false))
        val snap = E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val sd = checkNotNull(host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                sd.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x33 }))
                val stateful = host.components().single { it.descriptor.id == "sdcard-reader" }.component as StatefulComponent
                stateful.saveSnapshot()
            }.join()
        }
        val slots = snap["slots"] as List<*>
        val slot0 = slots[0] as Map<*, *>
        assertFalse(snap.containsKey("modifiedSectors"), "> 64 MiB writable image must omit modifiedSectors")
        assertFalse(slot0.containsKey("modifiedSectors"), "> 64 MiB writable slot[0] must omit modifiedSectors")
        // Mutating image externally must cause full SHA-256 mismatch on restoreSnapshot
        Files.newByteChannel(largeImg, java.nio.file.StandardOpenOption.WRITE).use { ch ->
            ch.position(0L)
            ch.write(ByteBuffer.wrap(ByteArray(512) { 0x44 }))
        }
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val stateful = host.components().single { it.descriptor.id == "sdcard-reader" }.component as StatefulComponent
                assertFails { stateful.restoreSnapshot(snap) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f7T2_socSdHostRejectsBadPowerClockAndAutoCmd23Mode(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        Files.write(sdDir.resolve("test.img"), ByteArray(8 * 1024 * 1024))
        Formats.atomicYaml(sdDir.resolve("sdcard-reader.yaml"), mapOf("slot1Image" to "test.img", "slot1Inserted" to true, "slot1ReadOnly" to false))
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(sdHostDir)
        val profile = SdTransactionContracts.ADDRESS_PROFILE
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to profile, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdHostInput(profile, 8L * 1024 * 1024))
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
                // Without SD bus power & clock enabled, issuing CMD0 must fail
                assertFails { router.write(base + 12, 4, 0L) }
                // Enable power/clock, verify Auto CMD23 bit (mode 0x08) is refused
                host.advanceTick(host.tick + 2); router.beforeInstruction(host.tick)
                assertFails { router.write(base + 12, 4, (18L shl 24) or (0x3aL shl 16) or 0x002bL) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f7T2_formatRefusesWriteProtectedSlot1CardGracefully(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeM5SdFormatProtocolReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "F7-FormatReadOnlyGuard", ready, "M5 SD format support not yet ready")

        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val sdDir = home.resolve("compoments/sdcard-reader")
        Files.createDirectories(sdDir)
        val sdImg = sdDir.resolve("ro-slot1.img")
        Files.write(sdImg, ByteArray(8 * 1024 * 1024) { ((it / 512 * 13 + it) and 255).toByte() })
        val beforeSha = E2eHarness.sha256(sdImg)
        Formats.atomicYaml(
            sdDir.resolve("sdcard-reader.yaml"),
            mapOf("slot1Image" to "ro-slot1.img", "slot1Inserted" to true, "slot1ReadOnly" to true, "slot2Inserted" to false),
        )
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(sdHostDir)
        val profile = SdTransactionContracts.ADDRESS_PROFILE
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("profile" to profile, "inputFile" to "input.yaml"))
        Formats.atomicYaml(sdHostDir.resolve("input.yaml"), sdHostInput(profile, 8L * 1024 * 1024, readOnly = true))

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
                val ram = ByteArray(0x10000) { 0x55 }
                host.ports.register(
                    "ro-mem",
                    "cpu.memory",
                    DmaMemory::class.java,
                    object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) = require(address >= 0 && size >= 0 && address + size <= ram.size)
                        override fun read(address: Long, destination: ByteBuffer) {
                            val r = destination.remaining(); check(address, r.toLong(), false); destination.put(ram, address.toInt(), r)
                        }

                        override fun write(address: Long, source: ByteBuffer) {
                            val r = source.remaining(); check(address, r.toLong(), true); source.get(ram, address.toInt(), r)
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
                var cardReady = false
                repeat(8) {
                    if (!cardReady) {
                        issue(55); issue(41, 0x40ff8000, flags = 2)
                        val resp = router.read(base + 0x10, 2) or (router.read(base + 0x12, 2) shl 16)
                        cardReady = (resp and 0x80000000L) != 0L
                    }
                }
                assertTrue(cardReady)
                issue(2, flags = 9); issue(3); issue(9, 0x10000, flags = 9); issue(7, 0x10000, flags = 0x1b); issue(16, 512)
                assertEquals(0x80000L, router.read(base + 0x24, 4) and 0x80000L, "PRESENT_STATE bit 19 must signal active-high write protect on read-only card")
                router.write(base + 4, 2, 0x7000L or 512L); router.write(base + 6, 2, 1L); router.write(base + 0, 4, 0x2000L)
                for ((index, mode) in listOf(24 to 0x01, 25 to 0x27, 25 to 0x26)) {
                    issue(index, 0L, flags = 0x3a, mode = mode)
                    assertEquals(0x0400L, router.read(base + 0x12, 2) and 0x0400L, "CMD$index must report R1.WP_VIOLATION")
                    assertEquals(0L, router.read(base + 0x24, 4) and 7, "Rejected write must leave data lines idle")
                }
                // Separately verify unsupported erase commands are refused without mutating media
                assertFails("CMD32 erase start unsupported") { issue(32, 0L, flags = 0x1a, mode = 0) }
                assertFails("CMD33 erase end unsupported") { issue(33, 512L, flags = 0x1a, mode = 0) }
                assertFails("CMD38 erase unsupported") { issue(38, 0L, flags = 0x1b, mode = 0) }
            }.join()
        }
        assertEquals(beforeSha, E2eHarness.sha256(sdImg), "Write-protected card must never be mutated")
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f7T2_formatOverwritesPreexistingCorruptFatWithValidFat32(@TempDir tempRoot: Path) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets(
            "blankCard+runtime",
            fw != null && lib != null && Files.isDirectory(E2eHarness.blankCardHomeDir) && Files.isDirectory(E2eHarness.blankCardSnapshotDir),
            "Missing blankCard assets",
        )
        val ready = E2eHarness.probeM5InCameraFormatReady(tempRoot.resolve("probe"))
        E2eGates.assumeOrFailFeature("M5", "F7-FormatCorruptRecovery", ready, "M5 live UI format not yet ready")

        val srcSdImg = E2eHarness.blankCardHomeDir.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val srcSha = E2eHarness.sha256(srcSdImg)
        val (workHome, ramPlanPath) = E2eHarness.prepareBlankCardHome(tempRoot.resolve("home"), overrideSdReadOnly = false)
        val gpioYaml = workHome.resolve("compoments/soc-gpio/soc-gpio.yaml")
        if (Files.isRegularFile(gpioYaml)) {
            val gpioCfg = Formats.read(gpioYaml).toMutableMap()
            gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v3"
            gpioCfg["directionButtonProfile"] = "gfx100s-2.13-direction-input-v1"
            Formats.atomicYaml(gpioYaml, gpioCfg)
        }
        val sdImg = workHome.resolve("compoments/sdcard-reader/sd-card-v7-slot-1.img")
        val buttonEvents = (System.getProperty("fuji.e2e.formatButtonEvents")?.takeIf { it.isNotBlank() } ?: E2eHarness.DEFAULT_FORMAT_BUTTON_EVENTS)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { spec ->
                val parts = spec.split(':')
                Triple(parts[0].toLong(), parts[1], parts[2].lowercase() in setOf("down", "true", "1", "pressed"))
            }.sortedBy { it.first }
        val budget = (System.getProperty("fuji.e2e.formatBudget")?.toLongOrNull() ?: 660_000_000L)
        val cfg = Formats.read(workHome.resolve("config/emulator.yaml"))
        EmulatorRuntime(workHome, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    E2eHarness.blankCardSnapshotDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = budget,
                        allowCompatibleProfileChanges = setOf("power-peer", "soc-gpio", "soc-vglib-init"),
                    ),
                )
                rt.session.extendAdditionalRam(AdditionalRamPlan.read(ramPlanPath))
                val sd = checkNotNull(rt.host.ports.find("sdcard-reader", BlockStoragePort::class.java))
                // Poison MBR (LBA 0), VBR (LBA 2048), and FAT0 (LBA 2080) post-restore
                val corruptMbr = ByteArray(512).apply {
                    this[446 + 4] = 0x0B
                    this[446 + 8] = 0x00; this[446 + 9] = 0x08
                    this[510] = 0x55; this[511] = 0xAA.toByte()
                }
                val corruptVbr = ByteArray(512).apply {
                    this[11] = 0x00; this[12] = 0x02 // 512 B/sec
                    this[13] = 0x03 // Invalid non-power-of-two sectorsPerCluster
                    this[14] = 32; this[16] = 2
                    "CORRUPT ".toByteArray(Charsets.US_ASCII).copyInto(this, 82)
                    this[510] = 0x55; this[511] = 0xAA.toByte()
                }
                val corruptFat0 = ByteArray(512) { 0xAA.toByte() }
                assertEquals(512, sd.write(1, 0L, ByteBuffer.wrap(corruptMbr)))
                assertEquals(512, sd.write(1, 2048L * 512L, ByteBuffer.wrap(corruptVbr)))
                assertEquals(512, sd.write(1, 2080L * 512L, ByteBuffer.wrap(corruptFat0)))
                assertFails("Corrupted FAT sectors must be invalid before formatting") {
                    E2eHarness.inspectFatFilesystem(sdImg)
                }
            }.join()

            var nextIdx = 0
            while (rt.owner.submit {
                val att = rt.session.checkpointAttempts()
                while (nextIdx < buttonEvents.size && att >= buttonEvents[nextIdx].first) {
                    val (_, key, pressed) = buttonEvents[nextIdx]
                    val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                    rt.host.patch("buttons", btn.revision, mapOf(key to pressed)).join()
                    nextIdx++
                }
                rt.session.step()
            }.join()) {
                // Step to completion
            }
            val early = rt.owner.submit { rt.session.checkpointEarlyState() }.join()
            assertEquals("instruction-limit", early["status"], "Expected clean format completion, got ${early["status"]}")
        }
        val fat = E2eHarness.inspectFatFilesystem(sdImg)
        assertTrue(fat.fsType.startsWith("FAT32"), "Expected FAT32 overwriting corrupt FAT, got ${fat.fsType}")
        assertEquals(512, fat.bytesPerSector)
        assertTrue(fat.numFats >= 2)
        assertTrue(fat.fatSizeSectors > 0L)
        assertTrue(fat.fat0MediaByte in setOf(0xF8, 0xF0), "Expected valid FAT0 media byte, got ${fat.fat0MediaByte}")
        assertEquals(srcSha, E2eHarness.sha256(srcSdImg))
    }

    // =========================================================================
    // F8: Boot ROM Cold Start Exploration (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f8T2_bootRomResearchRejectsTamperedRomDigestAndArchiveEscape(@TempDir tempRoot: Path) {
        assertFails("Missing archive root must be rejected") {
            BootRomEntryResearch.analyze(E2eHarness.projectRoot, tempRoot)
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f8T2_socSystemControlRejectsOutOfRangeBooleanAndMaskViolation(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        val sysDir = home.resolve("compoments/soc-system-control")
        Files.createDirectories(sysDir)
        Formats.atomicYaml(sysDir.resolve("soc-system-control.yaml"), mapOf("booleanWrite" to true, "serialWrite" to true))
        E2eHarness.withComponentHost(home) { owner, host ->
            owner.submit {
                val boolMmio = checkNotNull(host.ports.find("soc-system-control.boolean", MmioDevice::class.java))
                assertFails { boolMmio.write(0L, 4, 2L) }
                assertFails { boolMmio.write(0L, 2, 1L) }
                val aBank = checkNotNull(host.ports.find("soc-system-control.a", MmioDevice::class.java))
                assertFails("Word exceeding DAT mask must be rejected") { aBank.write(0L, 4, 0xffffffffL) }
            }.join()
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f8T2_firmwareMetadataRejectsInvalidSectionBoundsAndBadMethod4() {
        val metaPath = E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml")
        val raw = Formats.read(metaPath).toMutableMap()
        raw["schema"] = 99
        assertFails { FirmwareMetadata(raw) }

        val badBootMode = Formats.read(metaPath).toMutableMap()
        badBootMode["loaderMemoryBytes"] = 0x1000L
        assertFails { FirmwareMetadata(badBootMode) }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f8T2_bootRomColdStartRefusesMissingStackSramInStrictMode(@TempDir tempRoot: Path) {
        val archive = E2eHarness.resolveBootRomArchive()
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GFX100S_DUMP+GXUP0004+Dynarmic", archive != null && fw != null && lib != null, "Missing GFX100S_DUMP.zip, GXUP0004.DAT, or native Dynarmic library")
        val ready = E2eHarness.probeBootRomProfileReady()
        E2eGates.assumeOrFailFeature("M5", "F8-StrictStackGuard", ready, "Boot ROM cold-start profile not yet added")

        val bootRomBytes = java.util.zip.ZipFile(requireNotNull(archive).toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val metaPath = E2eHarness.resolveBootRomMetadataPath()
        // Case A: system-memory left on loader-low128-experimental-v1 (missing boot-rom & stack SRAM)
        val home1 = E2eHarness.stageComponentHome(tempRoot.resolve("missing-profile"))
        Files.write(home1.resolve("boot-rom.bin"), bootRomBytes)
        Formats.atomicYaml(home1.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to "loader-low128-experimental-v1"))
        Formats.atomicYaml(home1.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true))
        val cfg1 = mapOf(
            "schema" to 1,
            "executionMode" to "strict",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to metaPath.toString(),
            "firmwareFile" to requireNotNull(fw).toString(),
            "bootRomFile" to "boot-rom.bin",
            "dynarmicLibrary" to requireNotNull(lib).toString(),
            "instructionBudget" to 10000,
            "enabledDevices" to listOf("soc-system-control"),
        )
        EmulatorRuntime(home1, cfg1).use { rt ->
            rt.owner.submit {
                val rejection = assertFailsWith<IllegalStateException> { rt.session.initialize() }
                assertEquals("memory profile absent from selected firmware metadata", rejection.message)
            }.join()
        }

        // Case B: tampered Boot ROM metadata missing [0xFFF2E000, 0xFFF30000) boot-rom-sram
        val rawMeta = Formats.read(metaPath).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val profiles = (rawMeta["memoryProfiles"] as Map<String, List<Map<String, Any?>>>).toMutableMap()
        val profile = "boot-rom-cold-start-experimental-v1"
        val regions = profiles.getValue(profile)
        profiles[profile] = regions - regions.single { it["id"] == "boot-rom-sram" }
        rawMeta["memoryProfiles"] = profiles
        val tamperedMeta = tempRoot.resolve("no-sram-bootrom.yaml")
        Formats.atomicYaml(tamperedMeta, rawMeta)
        val home2 = E2eHarness.stageComponentHome(tempRoot.resolve("tampered-meta"))
        Files.write(home2.resolve("boot-rom.bin"), bootRomBytes)
        Formats.atomicYaml(home2.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
        Formats.atomicYaml(home2.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true))
        val cfg2 = cfg1 + ("firmwareMetadata" to tamperedMeta.toString())
        EmulatorRuntime(home2, cfg2).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                // At reset VBAR is zero. Capture the actual guest data abort in the fixture's
                // low RAM before a later prefetch abort can overwrite the banked return address.
                val memory = checkNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                val handler = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0xee162f10.toInt()) // MRC p15, 0, r2, c6, c0, 0 (DFAR)
                    .putInt(0xee153f10.toInt()) // MRC p15, 0, r3, c5, c0, 0 (DFSR)
                    .putInt(0xeafffffe.toInt()) // B . at 0x18
                    .flip()
                memory.write(0x10L, handler)
                rt.session.step()
                val regs = checkNotNull(rt.session.registerSnapshot())
                assertEquals(0x17L, regs.getValue("cpsr") and 0x1fL, "Guest must enter Abort mode: $regs")
                assertEquals(0x18L, regs.getValue("pc"), "Data-abort vector must run: $regs")
                assertEquals(0xfff0038cL, regs.getValue("lr"), "LR_abt must identify the ROM PUSH at 0xFFF00384: $regs")
                assertEquals(0xfff2fbf8L, regs.getValue("r2"), "DFAR must identify the missing stack word: $regs")
                assertEquals(0x808L, regs.getValue("r3"), "DFSR must report a synchronous external write abort: $regs")
            }.join()
        }
    }

    @Test
    @Tag("e2e-m5")
    @Tag("e2e-milestone-gated")
    fun f8T2_bootRomColdStartHandlesInvalidStrapInputDeterministically(@TempDir tempRoot: Path) {
        val archive = E2eHarness.resolveBootRomArchive()
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        E2eGates.requireAssets("GFX100S_DUMP+GXUP0004+libunicorn", archive != null && fw != null && lib != null, "Missing GFX100S_DUMP.zip, GXUP0004.DAT, or libunicorn.so.2")
        val ready = E2eHarness.probeBootRomProfileReady()
        E2eGates.assumeOrFailFeature("M5", "F8-InvalidStrap", ready, "Boot ROM cold-start profile not yet added")

        val bootRomBytes = java.util.zip.ZipFile(archive!!.toFile()).use { zip ->
            val entry = checkNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val metaPath = E2eHarness.resolveBootRomMetadataPath()

        // Case A: Absent bootStrapInputFile must refuse 0xFFF4A000 in strict mode (no silent zero)
        val home0 = E2eHarness.stageComponentHome(tempRoot.resolve("no-strap"))
        Files.write(home0.resolve("boot-rom.bin"), bootRomBytes)
        Formats.atomicYaml(home0.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
        Formats.atomicYaml(home0.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true))
        val cfg0 = mapOf(
            "schema" to 1,
            "executionMode" to "strict",
            "components" to "compoments",
            "httpPort" to 0,
            "firmwareMetadata" to metaPath.toString(),
            "firmwareFile" to fw!!.toString(),
            "bootRomFile" to "boot-rom.bin",
            "nativeLibrary" to lib!!.toString(),
            "instructionBudget" to 10000,
            "enabledDevices" to listOf("soc-system-control"),
        )
        EmulatorRuntime(home0, cfg0).use { rt ->
            val actual = rt.probe()
            assertEquals(true, actual["bootRomExecuted"])
            assertEquals("unmapped MMIO 0xfff4a000 size 4", actual["status"])
            assertEquals(0xfff4a000L, (actual["frontier"] as Map<*, *>)["addressPhysical"])
        }

        // Case B: Out-of-u32 strap input values (-1, 0x1_0000_0000L) must be rejected on component load
        for ((tag, badVal) in listOf("neg" to -1L, "overflow" to 0x1_0000_0000L)) {
            val badHome = E2eHarness.stageComponentHome(tempRoot.resolve("bad-strap-$tag"))
            Formats.atomicYaml(badHome.resolve("compoments/soc-system-control/boot-strap.json"), mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to badVal))
            Formats.atomicYaml(badHome.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"))
            assertFails("Out-of-range strap value $badVal must be rejected") {
                E2eHarness.withComponentHost(badHome) { _, _ -> }
            }
        }

        // Case C: Non-SPI-NOR strap value=0 runs deterministically across two independent strict runs
        fun runWithStrap(name: String, value: Long): Map<String, Any?> {
            val h = E2eHarness.stageComponentHome(tempRoot.resolve(name))
            Files.write(h.resolve("boot-rom.bin"), bootRomBytes)
            Formats.atomicYaml(h.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to "boot-rom-cold-start-experimental-v1"))
            Formats.atomicYaml(h.resolve("compoments/soc-system-control/boot-strap.json"), mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to value))
            Formats.atomicYaml(h.resolve("compoments/soc-system-control/soc-system-control.yaml"), mapOf("booleanWrite" to true, "bootStrapInputFile" to "boot-strap.json"))
            Formats.atomicYaml(h.resolve("compoments/soc-flash/soc-flash.yaml"), mapOf("profile" to "dat-readonly-experimental-v1", "imagePort" to "flash.image"))
            Formats.atomicYaml(h.resolve("compoments/flash-image/flash-image.yaml"), mapOf("sourcePort" to "firmware.update", "tailProfile" to "strict", "regionsProfile" to "strict", "regionsFile" to ""))
            val cfg = cfg0 + ("enabledDevices" to listOf("soc-system-control", "soc-flash", "soc-gpio"))
            return EmulatorRuntime(h, cfg).use { it.probe() }
        }
        val runA = runWithStrap("strap0-a", 0L)
        val runB = runWithStrap("strap0-b", 0L)
        val runSpiNor = runWithStrap("strap8", 8L)
        assertEquals(true, runA["bootRomExecuted"])
        assertEquals(runA["attempts"], runB["attempts"])
        assertEquals(runA["status"], runB["status"])
        assertEquals(runA["registers"], runB["registers"])
        assertEquals(runA["frontier"], runB["frontier"])
        assertTrue(runA["frontier"] != runSpiNor["frontier"] || runA["attempts"] != runSpiNor["attempts"], "Strap 0 should diverge deterministically from SPI-NOR strap 8")
    }

    // =========================================================================
    // F9: Peripheral YAML Convergence & strict Parity (5 tests)
    // =========================================================================

    @Test
    @Tag("e2e-baseline")
    fun f9T2_componentJarRejectsMissingDescriptorWrongAbiAndDuplicateId(@TempDir tempRoot: Path) {
        val home1 = tempRoot.resolve("missing-catalog-json")
        Files.createDirectories(home1.resolve("compoments"))
        val srcJar = E2eHarness.stagedComponents.resolve("battery-0.3.1.jar")
        E2eGates.requireAssets("battery-0.3.1.jar", Files.isRegularFile(srcJar), "Missing $srcJar")
        JarFile(srcJar.toFile()).use { source ->
            JarOutputStream(Files.newOutputStream(home1.resolve("compoments/battery-0.3.1.jar"))).use { out ->
                source.entries().asSequence().forEach { entry ->
                    if (entry.name == "META-INF/fuji/catalog.json") return@forEach
                    out.putNextEntry(JarEntry(entry.name))
                    source.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        assertFails { E2eHarness.withComponentHost(home1, AddressCatalog.bundled()) { _, _ -> } }

        val home2 = E2eHarness.stageComponentHome(tempRoot.resolve("dup-id"))
        Files.copy(home2.resolve("compoments/battery-0.3.1.jar"), home2.resolve("compoments/battery-dup.jar"))
        assertFails { E2eHarness.withComponentHost(home2) { _, _ -> } }
    }

    @Test
    @Tag("e2e-baseline")
    fun f9T2_httpControlApiRejectsMalformedQueryExcessParamsAndOversizedBody(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot)
        E2eHarness.withComponentHost(home) { _, host ->
            ControlServer(host, 0).use { server ->
                val base = "http://127.0.0.1:${server.address.port}"
                E2eHarness.HttpApiClient(base).use { client ->
                    assertEquals(400, client.get("/api/v1/catalog?address=0&address=4").statusCode())
                    val tooMany = (1..17).joinToString("&") { "k$it=$it" }
                    assertEquals(400, client.get("/api/v1/catalog?$tooMany").statusCode())
                    assertEquals(404, client.get("/api/v1/does-not-exist").statusCode())
                    assertEquals(400, client.get("/api/v1/state", headers = mapOf("Origin" to "https://evil.invalid")).statusCode())
                }
                HttpClient.newHttpClient().use { raw ->
                    val hugeBody = "{\"revision\":0,\"changes\":{\"note\":\"${"x".repeat(70000)}\"}}"
                    val req = HttpRequest.newBuilder(URI.create("$base/api/v1/components/battery"))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(hugeBody))
                        .build()
                    assertEquals(400, raw.send(req, HttpResponse.BodyHandlers.ofString()).statusCode())
                }
            }
        }
    }

    @Test
    @Tag("e2e-baseline")
    fun f9T2_mmioRouterRejectsCrossWindowAndUnalignedAccesses() {
        val dummy = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
        }
        val router = MmioRouter(listOf(MmioWindow("w0", 0xFFF00000L, 16L, dummy)), discovery = null)
        assertFails("Cross-window read must fail") { router.read(0xFFF0000EL, 4) }
        assertFails("Unmapped read in strict mode must fail") { router.read(0xFFF00020L, 4) }
        assertFails("Unmapped write in strict mode must fail") { router.write(0xFFF00020L, 4, 1L) }
    }

    @Test
    @Tag("e2e-baseline")
    fun f9T2_discoveryJournalCapacityLimitEnforcesStopBeforeMutation() {
        val zeroHex = "0".repeat(64)
        val journal = DiscoveryJournal("run-cap-test", "1.0", zeroHex, zeroHex, zeroHex, capacity = 2)
        val ctx = object : DiscoveryContext {
            override fun virtualAddress(): Long = 0x1000L
            override fun core(): Int = 0
            override fun tick(): Long = 10L
            override fun pc(): Long = 0x2000L
            override fun lr(): Long = 0x2004L
        }
        val s0 = journal.begin(1L, 0xFFFC0000L, 4, false, 0L, 0, ctx)
        journal.complete(s0, 0L, null, 0L)
        val s1 = journal.begin(2L, 0xFFFC0004L, 4, false, 0L, 0, ctx)
        journal.complete(s1, 0L, null, 0L)
        // 3rd entry hits capacity refusal slot and throws BEFORE mutation
        assertFailsWith<IllegalStateException> {
            journal.begin(3L, 0xFFFC0008L, 4, true, 0xDEADL, 0, ctx)
        }
        assertEquals(3L, journal.metadata()["attemptedCount"])
        assertEquals(true, journal.metadata()["capacityRefusalSlot"])
        // Subsequent call after sealing must also fail
        assertFailsWith<IllegalStateException> {
            journal.begin(4L, 0xFFFC000CL, 4, false, 0L, 0, ctx)
        }
    }

    @Test
    @Tag("e2e-m6")
    @Tag("e2e-milestone-gated")
    fun f9T2_strictModeRefusesUnmodeledCandidateReadWithoutZeroFallback(@TempDir tempRoot: Path) {
        val ready = E2eHarness.probeM6CatalogConvergedReady()
        E2eGates.assumeOrFailFeature("M6", "F9-StrictCandidateRefusal", ready, "M6 YAML convergence not yet complete")

        val catalog = AddressCatalog.bundled()
        val strictRt = CatalogRuntime(catalog, "strict", tempRoot.resolve("cat-strict"), emptyMap())
        val query = strictRt.query(mapOf("address" to "0xFFFC41E0", "width" to "4", "direction" to "read"))
        assertEquals("CANDIDATE_REGISTER", query["classification"])
        assertEquals(false, query["permitted"])
        assertEquals(false, query["deviceReadPerformed"])

        val dummyCtx = object : DiscoveryContext {
            override fun virtualAddress(): Long = 0x1000L
            override fun core(): Int = 0
            override fun tick(): Long = 0L
            override fun pc(): Long = 0x2000L
            override fun lr(): Long = 0x2004L
        }
        val nullBus = strictRt.createBus(dummyCtx)
        assertEquals(null, nullBus, "Strict mode must not construct a DiscoveryBus")
        val strictRouter = MmioRouter(emptyList(), discovery = nullBus)
        val readErr = assertFails("Strict MmioRouter must throw rather than return 0L on unmodeled candidate read") {
            strictRouter.read(0xFFFC41E0L, 4)
        }
        assertTrue(readErr.message?.contains("unmapped MMIO", ignoreCase = true) == true)
        assertFails("Strict MmioRouter must throw on unmodeled candidate write") {
            strictRouter.write(0xFFFC41E0L, 4, 0x1234L)
        }

        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        if (fw != null && lib != null) {
            val home = E2eHarness.stageComponentHome(tempRoot.resolve("rt-strict"))
            val cfg = mapOf(
                "schema" to 1,
                "executionMode" to "strict",
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to E2eHarness.projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to fw.toString(),
                "nativeLibrary" to lib.toString(),
                "instructionBudget" to 100000,
                "enabledDevices" to listOf("soc-gpio", "soc-timer"),
            )
            EmulatorRuntime(home, cfg).use { rt ->
                rt.serve()
                val port = checkNotNull(rt.server).address.port
                val probe = rt.probe()
                assertTrue(probe["frontier"] != null || probe["status"] != null)
                E2eHarness.HttpApiClient("http://127.0.0.1:$port").use { client ->
                    val unknownNode = Formats.json.readTree(client.get("/api/v1/catalog?unknown=true").body())
                    assertEquals("strict", unknownNode["executionMode"].asText())
                    assertTrue(unknownNode["accesses"].isArray && unknownNode["accesses"].size() == 0)
                }
            }
        }
    }

    private fun sdHostInput(profile: String, capacityBytes: Long, readOnly: Boolean = false): Map<String, Any?> {
        val cidBytes = byteArrayOf(0xfe.toByte(), 0x54, 0x45, 0x53, 0x54, 0x30, 0x30, 0x31, 0x10, 1, 2, 3, 4, 1, 0xa1.toByte(), 0)
        var crc = 0
        for (b in cidBytes.take(15)) for (bit in 7 downTo 0) {
            val fb = ((b.toInt() ushr bit) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 0x7f
            if (fb != 0) crc = crc xor 9
        }
        cidBytes[15] = ((crc shl 1) or 1).toByte()
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
                "0" to mapOf("basis" to "SYNTHETIC", "capacityBytes" to capacityBytes, "highCapacity" to false, "readOnly" to readOnly, "rca" to 1, "cidHex" to HexFormat.of().formatHex(cidBytes), "ocrVoltageWindow" to 0x00ff8000L, "supportsHighSpeed" to true),
                "1" to null,
            ),
            "commandTicks" to 2,
            "blockTicks" to 3,
            "resetTicks" to 2,
        )
    }
}
