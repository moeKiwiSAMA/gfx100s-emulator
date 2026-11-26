package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.desktop.audio.AudioPlaybackConfig
import com.github.nyabanban.fuji.emulator.desktop.audio.HostAudioPlayback
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live Dynarmic JIT end-to-end verification for BUG-A4-4:
 * Cold-boots real GFX100S 2.13 (`GXUP0004.DAT`) in Movie LiveView, injects 440 Hz synthetic
 * `TEST_TONE` audio into the I2S0 RX ring via [HostAudioPlayback], and proves:
 * 1. `0xFFFA8000` (`soc-panel.toneLutA8000`) executes authentic 4B->3B/3B->4B audio bit-packing DMA jobs.
 * 2. Both `rxOverruns` and `rxUnderruns` remain 0 (no simultaneous overrun+underrun spiral).
 * 3. Movie LiveView `L R MIC` level meter bars visibly deflect on `display.main` (`0` `[RELAXED/MMIO]`).
 * 4. Plugging 3.5mm headphones (`GPIO 8.4` active-HIGH >= 400 ms debounce) unmutes WM8962 `HEADPHONE_STEREO`
 *    and routes live non-zero PCM through `0xFFFA8000` -> I2S1 TX (`lastNonZeroFrameTick != null`).
 */
class MovieAudioRxMeterIntegrationTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, componentId: String, patch: Map<String, Any>) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == componentId }.revision }.join()
        rt.host.patch(componentId, rev, patch).join()
    }

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 350_000) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var calls = 0
        while (rt.telemetry.virtualTick < target && calls < maxCalls) {
            val cont = rt.owner.submit {
                var ok = true
                var batch = 0
                while (ok && batch < 64 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    ok = rt.session.stepFast()
                    batch++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                ok
            }.join()
            if (!cont || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun captureMain(rt: EmulatorRuntime): Triple<Int, Int, IntArray> =
        rt.owner.submit {
            val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val w = port.width
            val h = port.height
            val px = if (w > 0 && h > 0) IntArray(w * h).also { port.copyArgb(it) } else IntArray(0)
            Triple(w, h, px)
        }.join()

    private fun componentState(rt: EmulatorRuntime, id: String): Map<String, Any?> =
        rt.owner.submit {
            rt.host.components().single { it.descriptor.id == id }.component.state()
        }.join()

    private fun savePng(dir: Path?, name: String, w: Int, h: Int, argb: IntArray) {
        if (dir == null || w <= 0 || h <= 0 || argb.size < w * h) return
        Files.createDirectories(dir)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, argb, 0, w)
        ImageIO.write(img, "PNG", dir.resolve(name).toFile())
    }

    private fun countDiffPixelsInRegion(
        a: IntArray,
        b: IntArray,
        w: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Int {
        var diff = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                if ((a[row + x] and 0x00FFFFFF) != (b[row + x] and 0x00FFFFFF)) diff++
            }
        }
        return diff
    }

    private fun longField(map: Map<*, *>?, key: String): Long =
        (map?.get(key) as? Number)?.toLong() ?: 0L

    @Test
    fun liveMovieTestToneDeflectsMicLevelMeterWithZeroRxOverrunsAndUnderruns(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val dumpDir = System.getenv("FUJI_AUDIO_METER_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val workRoot = tempRoot.resolve("release-movie-audio")
        copyDirectory(stagedRoot, workRoot)

        // Enable cold-boot Movie mode (movieMode: true) before starting runtime
        val buttonsCfg = workRoot.resolve("configs/components/buttons/buttons.yaml")
        val updatedButtons = Files.readString(buttonsCfg).replace("movieMode: false", "movieMode: true")
        Files.writeString(buttonsCfg, updatedButtons)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            HostAudioPlayback(
                portsProvider = { rt.host.ports },
                initialConfig = AudioPlaybackConfig(enabled = false),
            ).use { audio ->
                rt.owner.submit {
                    rt.session.initialize()
                    audio.bindPorts(rt.host.ports)
                }.join()

                // 1. Cold boot through OOBE -> Live 1024x768 Movie LiveView
                stepBy(rt, 620_000_000L)
                assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
                tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Language -> Area
                tapButton(rt, "down", 5_000_000L, 25_000_000L)
                tapButton(rt, "down", 5_000_000L, 25_000_000L)
                tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Area -> Date/Time
                tapButton(rt, "up", 4_000_000L, 12_000_000L)
                tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Date/Time -> Start App
                tapButton(rt, "menu", 5_000_000L, 45_000_000L) // Start App -> Movie LiveView
                for (i in 0 until 30) {
                    stepBy(rt, 8_000_000L)
                    val (w, h, px) = captureMain(rt)
                    if (w == 1024 && h == 768 && px.count { (it and 0x00FFFFFF) != 0 } > 150_000) {
                        val panel = componentState(rt, "soc-panel")["toneLutA8000"] as? Map<*, *>
                        if (longField(panel, "dmaJobCount") > 0L) break
                    }
                }

                // Settle Movie LiveView with inputMode == DISABLED (idle silence)
                stepBy(rt, 30_000_000L)
                val (w0, h0, pxBefore) = captureMain(rt)
                assertEquals(1024, w0)
                assertEquals(768, h0)
                savePng(dumpDir, "01-before-test-tone.png", w0, h0, pxBefore)

                val beforeAudio = audio.status()
                val beforeRx = beforeAudio["rxStats"] as Map<*, *>
                val beforePanel = componentState(rt, "soc-panel")["toneLutA8000"] as Map<*, *>
                val beforeI2s = componentState(rt, "soc-isp-setup")["i2sAudio"] as Map<*, *>
                println("[AUDIO-E2E] BEFORE: rx=$beforeRx a8000_jobs=${beforePanel["dmaJobCount"]} a8000_bytes=${beforePanel["dmaBytesWritten"]} i2s_tx=${beforeI2s["totalTxFrames"]} codec=${beforeAudio["codecRoute"]}")

                assertEquals(0L, longField(beforeRx, "rxOverruns"), "Expected 0 rxOverruns while DISABLED: $beforeRx")
                assertEquals(0L, longField(beforeRx, "rxUnderruns"), "Expected 0 spurious rxUnderruns while DISABLED: $beforeRx")
                assertTrue(longField(beforePanel, "dmaJobCount") > 0L, "Expected active 0xFFFA8000 audio bit-packing DMA jobs in Movie LiveView")

                // 2. Enable 440 Hz TEST_TONE (+6 dB -> -6 dBFS) and step Movie LiveView ~400ms virtual time
                audio.applyPatch(mapOf("inputMode" to "TEST_TONE", "testToneHz" to 440.0, "inputGainDb" to 6.0))
                // Let capture loop prime target RX buffer, then advance Movie LiveView so TSK_SOUNDIO + peak meter update HUD
                Thread.sleep(60L)
                stepBy(rt, 36_000_000L) // ~667 ms virtual time (~40 HUD frames @ 60 Hz)

                val (w1, h1, pxAfter) = captureMain(rt)
                savePng(dumpDir, "02-after-test-tone.png", w1, h1, pxAfter)

                val afterAudio = audio.status()
                val afterRx = afterAudio["rxStats"] as Map<*, *>
                val afterPanel = componentState(rt, "soc-panel")["toneLutA8000"] as Map<*, *>
                val afterI2s = componentState(rt, "soc-isp-setup")["i2sAudio"] as Map<*, *>

                // Movie LiveView L R MIC level meter vertical bars sit at x=80..165, y=350..590
                val fullDiff = countDiffPixelsInRegion(pxBefore, pxAfter, w1, 0, 0, w1, h1)
                val micRoiDiff = countDiffPixelsInRegion(pxBefore, pxAfter, w1, 80, 350, 165, 590)
                println("[AUDIO-E2E] AFTER TEST_TONE: rx=$afterRx a8000_jobs=${afterPanel["dmaJobCount"]} a8000_bytes=${afterPanel["dmaBytesWritten"]} i2s_tx=${afterI2s["totalTxFrames"]} lastNonZero=${afterI2s["lastNonZeroFrameTick"]} fullDiff=$fullDiff micRoiDiff=$micRoiDiff")

                assertEquals(0L, longField(afterRx, "rxOverruns"), "BUG-A4-4: rxOverruns must stay 0 under TEST_TONE: $afterRx")
                assertEquals(0L, longField(afterRx, "rxUnderruns"), "BUG-A4-4: rxUnderruns must stay 0 under TEST_TONE: $afterRx")
                assertTrue(
                    longField(afterPanel, "dmaJobCount") > longField(beforePanel, "dmaJobCount"),
                    "Expected 0xFFFA8000 DMA job count to advance under TEST_TONE",
                )
                assertTrue(
                    micRoiDiff >= 200,
                    "BUG-A4-4: Expected Movie LiveView L R MIC meter bars to visibly deflect under 440Hz TEST_TONE (micRoiDiff=$micRoiDiff, fullDiff=$fullDiff)",
                )

                // 3. Plug 3.5mm headphones (GPIO 8.4 active-HIGH, 400ms debounce -> step 600ms virtual time)
                //    and verify real GFX100S firmware unmutes WM8962 headphone monitoring & routes non-zero TX PCM!
                audio.applyPatch(mapOf("headphonePlugged" to true))
                stepBy(rt, 45_000_000L) // ~833 ms virtual time (> 400ms GPIO 8.4 debounce)
                val hpAudio = audio.status()
                val hpCodec = hpAudio["codecRoute"] as Map<*, *>
                val hpI2s = componentState(rt, "soc-isp-setup")["i2sAudio"] as Map<*, *>
                val hpRx = hpAudio["rxStats"] as Map<*, *>
                println("[AUDIO-E2E] AFTER HEADPHONE PLUG: codec=$hpCodec rx=$hpRx lastNonZero=${hpI2s["lastNonZeroFrameTick"]}")

                assertEquals(0L, longField(hpRx, "rxOverruns"), "rxOverruns must remain 0 after headphone plug: $hpRx")
                assertEquals(0L, longField(hpRx, "rxUnderruns"), "rxUnderruns must remain 0 after headphone plug: $hpRx")
                assertEquals(true, hpCodec["headphoneEnabled"], "Expected WM8962 headphone output enabled after plugging 3.5mm jack: $hpCodec")
                assertEquals("HEADPHONE_STEREO", hpCodec["activeSink"], "Expected WM8962 activeSink=HEADPHONE_STEREO: $hpCodec")
                assertTrue(
                    (hpCodec["effectiveLeftGain"] as Number).toDouble() > 0.0,
                    "Expected non-zero WM8962 headphone gain: $hpCodec",
                )
                assertTrue(
                    hpI2s["lastNonZeroFrameTick"] != null,
                    "BUG-V3-7-5: Expected non-zero I2S1 TX headphone monitoring PCM after plugging 3.5mm jack: $hpI2s",
                )
                assertTrue(
                    longField(hpI2s, "totalTxFrames") > longField(afterI2s, "totalTxFrames"),
                    "BUG-V3-7-5: Expected continuous I2S1 TX headphone monitoring frames after plugging 3.5mm jack: before=${afterI2s["totalTxFrames"]} after=${hpI2s["totalTxFrames"]}",
                )

                // 4. Verify zero [RELAXED/MMIO] fallbacks and zero [RELAXED/FLASH] refusals
                val sessionState = rt.owner.submit { rt.session.state() }.join()
                val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
                val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
                assertEquals(0L, longField(relaxed, "mmioFallbacks"), "Expected ZERO [RELAXED/MMIO] fallbacks | $report")
                assertEquals(0L, longField(relaxed, "flashRefusals"), "Expected ZERO [RELAXED/FLASH] refusals | $report")

                audio.applyPatch(mapOf("inputMode" to "DISABLED"))
            }
        }
    }
}
