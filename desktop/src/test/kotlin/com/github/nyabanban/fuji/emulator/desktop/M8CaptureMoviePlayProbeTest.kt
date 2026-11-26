package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Env-gated empirical diagnostic probe (`FUJI_M8_LIVE_PROBE=1`) covering:
 * 1. Cold boot -> OOBE -> STILL Live View (`Scr=0x0100`)
 * 2. STILL Shutter Capture (`shutterHalf` -> `shutterFull` -> `DCIM/100_FUJI/`)
 * 3. MOVIE Mode Switch (`movieMode=true`) + MOVIE Recording (`shutterFull`)
 * 4. PLAY Mode (`play`) -> PLAY Menu (`menu`) -> submenu crawl
 *
 * Prints exact guest screen/error/ch21/RECCF state, enabled GIC IRQs, SD card DCIM files,
 * and deduplicated `[RELAXED/MMIO]` hits at each phase.
 */
class M8CaptureMoviePlayProbeTest {

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst)
                }
            }
        }
    }

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long): Long {
        val start = rt.owner.submit { (rt.session.state()["attempts"] as Number).toLong() }.join()
        val target = start + deltaAttempts
        var current = start
        while (current < target) {
            current = rt.owner.submit {
                var i = 0
                while (i < 2048) {
                    if (!rt.session.stepFast()) break
                    i++
                }
                (rt.session.state()["attempts"] as Number).toLong()
            }.join()
            val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            if (status != "running") break
        }
        return current
    }

    private fun patchButton(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit {
            val btn = rt.host.components().single { it.descriptor.id == "buttons" }
            rt.host.patch("buttons", btn.revision, mapOf(key to down)).join()
        }.join()
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdAttempts: Long, settleAttempts: Long) {
        patchButton(rt, key, true)
        stepBy(rt, holdAttempts)
        patchButton(rt, key, false)
        stepBy(rt, settleAttempts)
    }

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun dumpState(rt: EmulatorRuntime, label: String, sdDir: Path) {
        val report = rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            fun r8(a: Long): Int { val b = ByteBuffer.allocate(1); mem.read(a, b); return b.get(0).toInt() and 0xff }
            fun r16(a: Long): Int { val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getShort(0).toInt() and 0xffff }
            fun r32(a: Long): Long { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getInt(0).toLong() and 0xffffffffL }
            val sink = rt.host.ports.find("soc-gic.lines", InterruptSink::class.java) as? EnabledEdgeInterruptSink
            val gicEn = if (sink != null) (32..319).filter { sink.lineEnabled(it) } else emptyList()
            val disp = rt.host.ports.find("display.main", DisplayPort::class.java)
            val jpegSt = rt.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state()
            val ispSt = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
            val setupSt = rt.host.components().single { it.descriptor.id == "soc-isp-setup" }.component.state()
            val st = rt.session.stateWithoutDiscovery()
            val rel = rt.session.relaxedFeedbackSummaryReport()
            val files = if (Files.isDirectory(sdDir)) {
                Files.list(sdDir).use { s -> s.map { "${it.fileName}(${Files.size(it)})" }.toList().sorted() }
            } else emptyList()
            """
            |=== [$label] ===
            |  status=${st["status"]} attempts=${st["attempts"]} tick=${rt.telemetry.virtualTick} dispSeq=${disp?.sequence}
            |  scr=0x${r32(0x01CC6424L).toString(16)} prev=0x${r32(0x01CC6420L).toString(16)} cur=0x${r32(0x01CBFFD4L).toString(16)} bDsc=${r8(0x01CBFCA2L)} errno=0x${r16(0x01CC0628L).toString(16)}
            |  ch21=(req=0x${r32(0x02D77FE8L).toString(16)},in=0x${r32(0x02D77FECL).toString(16)},done=0x${r32(0x02D77FF0L).toString(16)}) reccf=(M0=${r16(0x02D6F838L)},M2=${r16(0x02D6F83CL)})
            |  T79=0x${r32(0x035059B8L).toString(16)} T80=0x${r32(0x032BD294L).toString(16)} T74=0x${r32(0x02C46BFCL).toString(16)}
            |  soc-jpeg: enc=${jpegSt["encodeCount"]} errEnc=${jpegSt["encoderErrorCount"]} dec=${jpegSt["decodeCount"]} lastEnc=${jpegSt["lastEncodedBytes"]}
            |  soc-isp: irq106=${ispSt["irq106"]} irq296=${ispSt["irq296Pending"]}
            |  soc-isp-setup: pollPos=${setupSt["pollPosition"]} i2s=${setupSt["i2sAudio"]}
            |  gicEnabled=$gicEn
            |  sdDCIM=$files
            |  relaxed:
            |$rel
            """.trimMargin()
        }.join()
        println(report)
    }

    @Test
    fun probeStillCaptureMovieSwitchRecAndPlayMenu(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_M8_LIVE_PROBE") == "1", "Set FUJI_M8_LIVE_PROBE=1 to run empirical probe")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = System.getenv("FUJI_M8_STAGED_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(Files.isDirectory(stagedRoot), "Missing staged release root: $stagedRoot")

        val workRoot = tempRoot.resolve("release-probe")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        val dcimDir = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI")

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot -> OOBE -> STILL Live View (0x0100)
            stepBy(rt, 620_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 20) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
            }
            dumpState(rt, "1-STILL-LIVE-VIEW", dcimDir)

            // 2. STILL Shutter Half -> Shutter Full Capture
            patchButton(rt, "shutterHalf", true)
            stepBy(rt, 30_000_000L)
            dumpState(rt, "2A-AFTER-SHUTTER-HALF", dcimDir)

            patchButton(rt, "shutterFull", true)
            stepBy(rt, 20_000_000L)
            patchButton(rt, "shutterFull", false)
            patchButton(rt, "shutterHalf", false)
            stepBy(rt, 120_000_000L)
            dumpState(rt, "2B-AFTER-SHUTTER-FULL", dcimDir)

            // 3. MOVIE Mode Switch -> Movie Record Start/Stop
            patchButton(rt, "movieMode", true)
            stepBy(rt, 100_000_000L)
            dumpState(rt, "3A-AFTER-MOVIE-SWITCH", dcimDir)

            tapButton(rt, "shutterFull", 10_000_000L, 80_000_000L)
            dumpState(rt, "3B-AFTER-MOVIE-REC-START", dcimDir)

            tapButton(rt, "shutterFull", 10_000_000L, 50_000_000L)
            dumpState(rt, "3C-AFTER-MOVIE-REC-STOP", dcimDir)

            // 4. Switch back to STILL -> PLAY -> PLAY MENU
            patchButton(rt, "movieMode", false)
            stepBy(rt, 80_000_000L)
            tapButton(rt, "play", 5_000_000L, 80_000_000L)
            dumpState(rt, "4A-IN-PLAY", dcimDir)

            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            dumpState(rt, "4B-IN-PLAY-MENU", dcimDir)
        }
    }
}
