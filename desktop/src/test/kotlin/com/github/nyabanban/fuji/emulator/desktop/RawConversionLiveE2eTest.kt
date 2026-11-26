package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` (Dynarmic JIT) end-to-end verification for `P1-FIX-RAW-CONV` (`BUG-V3-8-2`):
 * 1. Switches `IMAGE QUALITY -> RAW` via live menu and captures a genuine `~207 MB` `DSCF0001.RAF` onto SD Slot 1.
 * 2. Enters `PLAY -> PLAY BACK MENU -> RAW CONVERSION` (`scr=0x0373`), executes `Q (CREATE)` -> `OK (STORE)`
 *    under default `STD` (PROVIA color), verifying a brand-new `11648x8736` color `.JPG` (`chroma >= 15.0`,
 *    non-clipped optical luma & contrast) is written to `DCIM/100_FUJI/`.
 * 3. Re-opens `RAW CONVERSION` on the same `.RAF`, switches `FILM SIMULATION -> MONOCHROME+Ye`, executes
 *    `Q (CREATE)` -> `OK (STORE)`, verifying a second brand-new `11648x8736` true-monochrome `.JPG`
 *    (`chroma <= 3.0`) is written to `DCIM/100_FUJI/`.
 * 4. Verifies `0` JPEG encoder errors and `0` `[RELAXED/MMIO]` / `[RELAXED/FLASH/REFUSED]` / `[RELAXED/DABT]` fallbacks.
 *
 * Opt-in gate: `-DFUJI_RUN_RAW_CONV_E2E=1` or `FUJI_RUN_RAW_CONV_E2E=1`.
 */
class RawConversionLiveE2eTest {

    private data class ImageStats(
        val width: Int,
        val height: Int,
        val meanLuma: Double,
        val stdLuma: Double,
        val meanChroma: Double,
    )

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 4096 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
    }

    private fun tapButton(
        rt: EmulatorRuntime,
        key: String,
        holdTicks: Long = 5_000_000L,
        settleTicks: Long = 35_000_000L,
    ) {
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to true), persistToDisk = false) }.join()
        stepVirtualTicks(rt, holdTicks)
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to false), persistToDisk = false) }.join()
        stepVirtualTicks(rt, settleTicks)
    }

    private fun tapQButton(
        rt: EmulatorRuntime,
        holdTicks: Long = 8_000_000L,
        settleTicks: Long = 120_000_000L,
    ) {
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-adc" }
                .component.applySettings(mapOf("qButton" to true))
        }.join()
        stepVirtualTicks(rt, holdTicks)
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-adc" }
                .component.applySettings(mapOf("qButton" to false))
        }.join()
        stepVirtualTicks(rt, settleTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun displayNonBlack(rt: EmulatorRuntime): Int = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(d.width * d.height)
        d.copyArgb(px)
        px.count { (it and 0x00ffffff) != 0 }
    }.join()

    private fun listDcimFiles(workRoot: Path, suffix: String): List<Path> {
        val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcimRoot)) return emptyList()
        return Files.walk(dcimRoot).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().uppercase().endsWith(suffix) }
                .sorted()
                .toList()
        }
    }

    private fun analyzeJpeg(path: Path): ImageStats {
        val img = assertNotNull(ImageIO.read(path.toFile()), "Failed to decode JPEG ${path.fileName}")
        val w = img.width
        val h = img.height
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var chromaSum = 0.0
        var samples = 0L
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val rgb = img.getRGB(x, y)
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                lumaSqSum += luma * luma
                chromaSum += (mx - mn).toDouble()
                samples++
                x += 32
            }
            y += 32
        }
        val meanL = lumaSum / samples
        val stdL = sqrt((lumaSqSum / samples - meanL * meanL).coerceAtLeast(0.0))
        return ImageStats(w, h, meanL, stdL, chromaSum / samples)
    }

    @Test
    fun inCameraRawConversionProducesValid102MpColorAndMonochromeJpegs(@TempDir tempRoot: Path) {
        val enabled = System.getenv("FUJI_RUN_RAW_CONV_E2E") == "1" ||
            System.getProperty("FUJI_RUN_RAW_CONV_E2E") == "1"
        assumeTrue(enabled, "Set FUJI_RUN_RAW_CONV_E2E=1 to run live 102MP RAW conversion E2E")

        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT")),
            "Requires staged release at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("raw-conv-e2e")
        copyDirectory(stagedRoot, workRoot)
        val cmosYaml = workRoot.resolve("configs/components/cmos/cmos.yaml")
        if (Files.isRegularFile(cmosYaml)) {
            Files.writeString(cmosYaml, Files.readString(cmosYaml).replace("scene://qr-ball", "scene://depth-studio"))
        }

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        // Phase 1: Capture a genuine ~207 MB 102MP .RAF onto SD Slot 1 via live IMAGE QUALITY -> RAW menu.
        val jpgsBeforeShot = listDcimFiles(workRoot, ".JPG").toSet()
        val rafFile: Path = EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            for (i in 0 until 60) {
                stepVirtualTicks(rt, 12_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected OOBE language screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 35) {
                stepVirtualTicks(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && displayNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL LiveView 0x0100")

            // Switch IMAGE QUALITY -> RAW (I.Q. row 1 -> DOWN x5 from FINE -> RAW)
            tapButton(rt, "menu", 5_000_000L, 65_000_000L)
            tapButton(rt, "down", 5_000_000L, 35_000_000L)
            tapButton(rt, "right", 5_000_000L, 55_000_000L)
            repeat(5) { tapButton(rt, "down", 4_500_000L, 25_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 55_000_000L)
            tapButton(rt, "back", 5_000_000L, 55_000_000L)
            if (readGuestU32(rt, 0x01CC6424L) !in listOf(0x0100, 0x0102)) {
                tapButton(rt, "back", 5_000_000L, 55_000_000L)
            }

            // Set MF (input7=16448) and capture a genuine ~207 MB .RAF onto SD Slot 1
            rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-gpio" }
                    .component.applySettings(mapOf("input7" to 16448L))
            }.join()
            stepVirtualTicks(rt, 12_000_000L)
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("shutterHalf" to true), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 12_000_000L)
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("shutterFull" to true), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 10_000_000L)
            rt.owner.submit {
                rt.host.patchLatestDirect("buttons", mapOf("shutterFull" to false, "shutterHalf" to false), persistToDisk = false)
            }.join()

            val rafsBeforeShot = listDcimFiles(workRoot, ".RAF").toSet()
            var captured: Path? = null
            var lastSz = -1L
            var stable = 0
            for (i in 0 until 20) {
                stepVirtualTicks(rt, 30_000_000L)
                val rafs = listDcimFiles(workRoot, ".RAF").filter { it !in rafsBeforeShot }
                if (rafs.isNotEmpty()) {
                    val f = rafs.first()
                    val sz = Files.size(f)
                    if (sz > 200_000_000L) {
                        if (sz == lastSz) {
                            stable++
                            if (stable >= 2) {
                                captured = f
                                break
                            }
                        } else {
                            stable = 0
                        }
                    }
                    lastSz = sz
                }
            }
            val found = assertNotNull(captured, "Expected captured .RAF file on SD Slot 1")
            println("[RAW-CONV-E2E][CAPTURED_RAF] file=${found.fileName} size=${Files.size(found)}")
            found
        }
        assertTrue(Files.size(rafFile) > 200_000_000L, "Expected >200 MB GFX100S 102MP .RAF, got ${Files.size(rafFile)}")
        val rafHeader = Files.newInputStream(rafFile).use { it.readNBytes(16) }
        assertEquals("FUJIFILMCCD-RAW ", String(rafHeader, Charsets.US_ASCII), "Captured .RAF must carry authentic Fujifilm RAF magic")
        assertEquals(
            emptyList(),
            listDcimFiles(workRoot, ".JPG").filter { it !in jpgsBeforeShot },
            "RAW-only capture must produce 0 new JPEGs",
        )

        // Phase 2: Mount the freshly captured 207.5 MB .RAF onto a clean SD Slot 1 work directory and execute
        // in-camera PLAY -> PLAY BACK MENU -> RAW CONVERSION for both STD (PROVIA color) and MONOCHROME+Ye,
        // verifying both 11648x8736 JPEGs and zero fallbacks.
        val playRoot = tempRoot.resolve("raw-conv-e2e-play")
        copyDirectory(stagedRoot, playRoot)
        val playCmosYaml = playRoot.resolve("configs/components/cmos/cmos.yaml")
        if (Files.isRegularFile(playCmosYaml)) {
            Files.writeString(playCmosYaml, Files.readString(playCmosYaml).replace("scene://qr-ball", "scene://depth-studio"))
        }
        val playRafDst = playRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI").resolve(rafFile.fileName)
        Files.createDirectories(playRafDst.parent)
        Files.copy(rafFile, playRafDst, StandardCopyOption.REPLACE_EXISTING)
        val jpgsBeforeConv = listDcimFiles(playRoot, ".JPG").toSet()
        val playConfig = Formats.read(playRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(playRoot, playConfig, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            for (i in 0 until 60) {
                stepVirtualTicks(rt, 12_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected OOBE language screen 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 35) {
                stepVirtualTicks(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && displayNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL LiveView 0x0100 before PLAY")

            // PLAY -> PLAY BACK MENU -> RAW CONVERSION (scr=0x0373) -> Q (CREATE) -> OK (STORE) [STD Color]
            tapButton(rt, "play", 5_000_000L, 120_000_000L)
            tapButton(rt, "menu", 5_000_000L, 65_000_000L)
            tapButton(rt, "down", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 100_000_000L)
            assertEquals(0x0373, readGuestU32(rt, 0x01CC6424L), "Expected RAW CONVERSION menu scr=0x0373")

            tapQButton(rt, 8_000_000L, 130_000_000L)
            assertEquals(0x0375, readGuestU32(rt, 0x01CC6424L), "Expected RAW CONVERSION OK STORE preview scr=0x0375")
            tapButton(rt, "menu", 5_000_000L, 120_000_000L)

            val jpgsAfterStd = listDcimFiles(playRoot, ".JPG").filter { it !in jpgsBeforeConv }
            assertEquals(1, jpgsAfterStd.size, "STD RAW CONVERSION must save 1 new JPEG on SD Slot 1")
            val stdJpg = jpgsAfterStd.single()
            val stdStats = analyzeJpeg(stdJpg)
            println("[RAW-CONV-E2E][STD] file=${stdJpg.fileName} size=${Files.size(stdJpg)} $stdStats")
            assertEquals(11648, stdStats.width, "Expected 102MP full-res width 11648")
            assertEquals(8736, stdStats.height, "Expected 102MP full-res height 8736")
            assertTrue(stdStats.meanLuma in 30.0..220.0, "STD converted JPEG must not be black or white-clipped: $stdStats")
            assertTrue(stdStats.stdLuma >= 25.0, "STD converted JPEG must have real optical contrast: $stdStats")
            assertTrue(stdStats.meanChroma >= 15.0, "STD converted JPEG must preserve vivid color: $stdStats")

            // 5. Reopen RAW CONVERSION on the same .RAF (after STORE, PLAY stays on the .RAF and
            //    PLAY BACK MENU retains the cursor on RAW CONVERSION), switch FILM SIMULATION -> MONOCHROME+Ye,
            //    Q (CREATE) -> OK (STORE).
            tapButton(rt, "menu", 5_000_000L, 65_000_000L)
            tapButton(rt, "menu", 5_000_000L, 100_000_000L)
            if (readGuestU32(rt, 0x01CC6424L) != 0x0373) {
                tapButton(rt, "back", 5_000_000L, 45_000_000L)
                tapButton(rt, "left", 5_000_000L, 65_000_000L)
                tapButton(rt, "menu", 5_000_000L, 65_000_000L)
                tapButton(rt, "menu", 5_000_000L, 100_000_000L)
            }
            assertEquals(0x0373, readGuestU32(rt, 0x01CC6424L), "Expected RAW CONVERSION menu scr=0x0373 on re-entry")

            repeat(5) { tapButton(rt, "down", 4_500_000L, 25_000_000L) }
            tapButton(rt, "right", 4_500_000L, 55_000_000L)
            repeat(4) { tapButton(rt, "up", 4_500_000L, 25_000_000L) }
            tapButton(rt, "menu", 4_500_000L, 55_000_000L)
            tapQButton(rt, 8_000_000L, 140_000_000L)
            assertEquals(0x0375, readGuestU32(rt, 0x01CC6424L), "Expected MONOCHROME OK STORE preview scr=0x0375")
            tapButton(rt, "menu", 5_000_000L, 120_000_000L)

            val jpgsAfterMono = listDcimFiles(playRoot, ".JPG").filter { it !in jpgsBeforeConv }
            assertEquals(2, jpgsAfterMono.size, "MONOCHROME RAW CONVERSION must save a second JPEG on SD Slot 1")
            val monoJpg = jpgsAfterMono.single { it != stdJpg }
            val monoStats = analyzeJpeg(monoJpg)
            println("[RAW-CONV-E2E][MONO] file=${monoJpg.fileName} size=${Files.size(monoJpg)} $monoStats")
            assertEquals(11648, monoStats.width)
            assertEquals(8736, monoStats.height)
            assertTrue(monoStats.meanLuma in 30.0..220.0, "MONO converted JPEG must not be black or white-clipped: $monoStats")
            assertTrue(monoStats.stdLuma >= 25.0, "MONO converted JPEG must have real optical contrast: $monoStats")
            assertTrue(monoStats.meanChroma <= 3.0, "MONOCHROME RAW CONVERSION must output neutral grayscale (chroma <= 3.0): $monoStats")

            // 6. Verify 0 JPEG encoder errors and 0 relaxed/MMIO/flash/dabt fallbacks
            val jpegState = rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state()
            }.join()
            assertEquals(0L, (jpegState["encoderErrorCount"] as Number).toLong(), "Expected 0 soc-jpeg encoder errors")
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            if (relaxed != null) {
                assertEquals(0L, (relaxed["deviceFallbacks"] as? Number)?.toLong() ?: 0L, "Expected 0 deviceFallbacks: $relaxed")
                assertEquals(0L, (relaxed["discoveryFallbacks"] as? Number)?.toLong() ?: 0L, "Expected 0 discoveryFallbacks: $relaxed")
                assertEquals(0L, (relaxed["flashRefusals"] as? Number)?.toLong() ?: 0L, "Expected 0 flashRefusals: $relaxed")
            }
        }
    }
}
