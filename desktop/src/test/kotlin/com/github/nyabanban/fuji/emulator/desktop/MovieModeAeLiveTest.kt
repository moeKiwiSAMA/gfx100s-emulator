package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live Dynarmic JIT regression test on unmodified stock FUJIFILM GFX100S 2.13 (`GXUP0004.DAT`)
 * verifying Movie-mode AE metering and primary `SHR1` (`0x0016/0x0017`) electronic shutter readout
 * across Movie `P` (`AUTO`) standby, active Movie recording, bright/dark scene sweeps, and Movie
 * `S`/`M` shutter-dial control (`FUJI_RUN_MOVIE_AE_LIVE=1`).
 */
class MovieModeAeLiveTest {
    private data class FrameSample(
        val luma: Double,
        val nonBlackPct: Double,
        val brightPct: Double,
        val mdsel8: Int,
        val shr15: Int,
        val movieShr15: Int,
        val movieFrameRows: Int,
        val durationNanos: Long,
        val iso: Int,
    )

    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target)
                }
            }
        }
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, b)
        b.getInt(0)
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(1)
        mem.read(addr, b)
        b.get(0).toInt() and 0xFF
    }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long) {
        rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            while (rt.session.checkpointAttempts() < target) {
                if (!rt.session.stepFast()) break
            }
        }.join()
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target) {
            val ok = rt.owner.submit {
                var i = 0
                var running = true
                while (i < 512 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) {
                        running = false
                        break
                    }
                    i++
                }
                running
            }.join()
            if (!ok) break
        }
    }

    private fun patchBtn(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf(key to down), persistToDisk = false) }.join()
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 5_000_000L, settle: Long = 45_000_000L) {
        patchBtn(rt, key, true)
        stepBy(rt, hold)
        patchBtn(rt, key, false)
        stepBy(rt, settle)
    }

    private fun sampleState(rt: EmulatorRuntime, tag: String, outDir: Path?): FrameSample = rt.owner.submit {
        val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = dp.width
        val h = dp.height
        val px = IntArray(w * h)
        dp.copyArgb(px)
        var sum = 0.0
        var nonBlack = 0
        var bright = 0
        for (y in h / 6 until (h * 5) / 6) {
            for (x in w / 6 until (w * 5) / 6) {
                val argb = px[y * w + x]
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val yLuma = 0.299 * r + 0.587 * g + 0.114 * b
                sum += yLuma
                if (yLuma > 8.0) nonBlack++
                if (yLuma > 240.0) bright++
            }
        }
        val n = ((h * 5) / 6 - h / 6) * ((w * 5) / 6 - w / 6)
        val luma = sum / n
        val nonBlkPct = 100.0 * nonBlack / n
        val brightPct = 100.0 * bright / n
        if (outDir != null) {
            Files.createDirectories(outDir)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(img, "png", outDir.resolve("$tag.png").toFile())
        }
        val exp = requireNotNull(rt.host.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java))
        val eff = Imx461ExposurePort.computeEffectiveExposure(exp)
        val st = exp.effectiveExposureState
        val sample = FrameSample(
            luma = luma,
            nonBlackPct = nonBlkPct,
            brightPct = brightPct,
            mdsel8 = exp.mdsel8,
            shr15 = st.shr15,
            movieShr15 = st.movieShr15,
            movieFrameRows = st.movieFrameRows,
            durationNanos = eff.durationNanos,
            iso = eff.iso,
        )
        println(
            "[MovieModeAeLiveTest][$tag] luma=${"%.1f".format(luma)} nonBlk=${"%.1f".format(nonBlkPct)}% " +
                "bright=${"%.1f".format(brightPct)}% mdsel=0x${sample.mdsel8.toString(16)} " +
                "shr1=${sample.shr15} shr2=${sample.movieShr15} mRows=${sample.movieFrameRows} " +
                "dur=${sample.durationNanos}ns iso=${sample.iso}",
        )
        sample
    }.join()

    @Test
    fun stock213MovieModeAeConvergesNaturallyAcrossStandbyRecordingAndManualShutter(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_MOVIE_AE_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(Files.isDirectory(stagedRoot), "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("movie-ae-live")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_MOVIE_AE_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        fun writeSolidJpg(name: String, rgb: Int): String {
            val img = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
            val c = (rgb shl 16) or (rgb shl 8) or rgb
            for (y in 0 until 360) for (x in 0 until 640) img.setRGB(x, y, c)
            for (dir in listOf(workRoot.resolve("configs/components/cmos"), workRoot.resolve("runtime/compoments/cmos"))) {
                Files.createDirectories(dir)
                ImageIO.write(img, "jpg", dir.resolve(name).toFile())
            }
            return name
        }

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 550_000_000L)
            for (i in 0 until 25) {
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
                stepBy(rt, 30_000_000L)
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L))
            tapInstr(rt, "menu")
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "menu")
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "right", 4_000_000L, 12_000_000L)
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "menu")
            tapInstr(rt, "menu")
            for (i in 0 until 28) {
                if (readU32(rt, 0x01CC6424L) == 0x0100 && readU8(rt, 0x01CBFCA2L) == 0) break
                stepBy(rt, 8_000_000L)
            }
            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("modeDial" to "P"), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 180_000_000L)
            val stillP = sampleState(rt, "still_P", outDir)
            assertTrue(stillP.luma in 95.0..190.0, "Still P baseline luma out of range: $stillP")

            // 1. Switch to Movie P (AUTO) standby and verify natural exposure & non-panicked ISO
            rt.owner.submit { rt.host.patchLatestDirect("buttons", mapOf("movieMode" to true), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 240_000_000L)
            val movieP = sampleState(rt, "movie_P_standby", outDir)
            assertEquals(Imx461ExposurePort.MDSEL_MOVIE_FHD_60P, movieP.mdsel8)
            assertTrue(movieP.luma in 105.0..185.0, "Movie P standby should not be dark or blown out: $movieP")
            assertTrue(movieP.nonBlackPct >= 95.0, "Movie P standby has black regions: $movieP")
            assertTrue(movieP.iso <= 400, "Movie P AE panicked to high ISO (SHR2 underexposure regression): $movieP")

            // 2. Active Movie recording (start -> record -> stop) preserves converged natural exposure
            patchBtn(rt, "shutterFull", true)
            stepVirtualTicks(rt, 20_000_000L)
            patchBtn(rt, "shutterFull", false)
            stepVirtualTicks(rt, 200_000_000L)
            val movieRec = sampleState(rt, "movie_P_recording", outDir)
            assertTrue(movieRec.luma in 110.0..185.0, "Movie P recording luma out of range: $movieRec")
            assertTrue(movieRec.nonBlackPct >= 95.0, "Movie P recording has black regions: $movieRec")
            assertTrue(movieRec.iso <= 400, "Movie P recording ISO too high: $movieRec")
            patchBtn(rt, "shutterFull", true)
            stepVirtualTicks(rt, 20_000_000L)
            patchBtn(rt, "shutterFull", false)
            stepVirtualTicks(rt, 120_000_000L)

            // 3. Bright (+3 EV) & Dark (-3 EV) scene sweep confirms Movie P AE actively drives SHR1 + PGC
            val brightJpg = writeSolidJpg("bright_245.jpg", 245)
            val darkJpg = writeSolidJpg("dark_35.jpg", 35)
            rt.owner.submit {
                rt.host.patchLatestDirect("cmos", mapOf("sourceKind" to "JPEG", "sourceFile" to brightJpg), persistToDisk = false)
            }.join()
            stepVirtualTicks(rt, 360_000_000L)
            val movieBright = sampleState(rt, "movie_P_bright", outDir)
            assertTrue(movieBright.shr15 > 320, "Movie P AE must shorten primary SHR1 on bright scene: $movieBright")
            assertTrue(movieBright.luma in 115.0..185.0, "Movie P bright scene should meter to mid-gray: $movieBright")
            assertTrue(movieBright.brightPct <= 5.0, "Movie P bright scene blew out: $movieBright")

            rt.owner.submit {
                rt.host.patchLatestDirect("cmos", mapOf("sourceKind" to "JPEG", "sourceFile" to darkJpg), persistToDisk = false)
            }.join()
            stepVirtualTicks(rt, 240_000_000L)
            val movieDark = sampleState(rt, "movie_P_dark", outDir)
            assertTrue(movieDark.shr15 <= 270, "Movie P AE must reopen SHR1 on dark scene: $movieDark")
            assertTrue(movieDark.luma >= 80.0 && movieDark.nonBlackPct >= 95.0, "Movie P dark scene stayed too dark: $movieDark")

            // 4. Restore qr-ball and verify Movie S and Movie M shutter dial (1/60 -> 1/125) tracks primary SHR1
            rt.owner.submit {
                rt.host.patchLatestDirect("cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://qr-ball"), persistToDisk = false)
            }.join()
            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("modeDial" to "S"), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 300_000_000L)
            val movieS = sampleState(rt, "movie_S", outDir)
            assertTrue(movieS.luma in 115.0..185.0, "Movie S luma out of range: $movieS")
            assertTrue(movieS.iso <= 300, "Movie S ISO too high: $movieS")

            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("modeDial" to "M"), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 120_000_000L)
            val movieM60 = sampleState(rt, "movie_M_60", outDir)
            assertTrue(movieM60.shr15 <= 16, "Movie M 1/60s should program SHR1 near 2: $movieM60")
            assertTrue(movieM60.durationNanos >= 3800L, "Movie M 1/60s durationNanos too short: $movieM60")
            assertTrue(movieM60.iso <= 250, "Movie M 1/60s ISO too high: $movieM60")

            rt.owner.submit { rt.host.patchLatestDirect("soc-adc", mapOf("rearDialDetents" to 12L), persistToDisk = false) }.join()
            stepVirtualTicks(rt, 140_000_000L)
            val movieM125 = sampleState(rt, "movie_M_125", outDir)
            assertTrue(movieM125.shr15 in 260..360, "Movie M 1/125s should program SHR1 near 337: $movieM125")
            assertTrue(movieM125.durationNanos in 1700L..2500L, "Movie M 1/125s durationNanos out of range: $movieM125")
            assertTrue(movieM125.luma in 115.0..185.0, "Movie M 1/125s luma out of range: $movieM125")
            assertTrue(movieM125.iso <= 250, "Movie M 1/125s ISO too high: $movieM125")

            // Zero MMIO fallbacks / flash refusals
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = (relaxed?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
            val flashRefusals = (relaxed?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            assertEquals(0L, mmioFallbacks, "Expected ZERO MMIO fallbacks in Movie AE live test")
            assertEquals(0L, flashRefusals, "Expected ZERO flash refusals in Movie AE live test")
        }
    }
}
