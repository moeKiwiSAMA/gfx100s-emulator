package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.PanelBacklightPort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.test.assertTrue

/**
 * Wave 14 Pre-`v0.3.0` Long Video Recording Stress Test (`W14-REAL-LONG-VIDEO-STRESS`).
 *
 * Opt-in gate: `FUJI_RUN_LONG_VIDEO_STRESS=1` (or `-Dfuji.runLongVideoStress=1`).
 *
 * Exercises a genuinely long, uninterrupted `≥75 vSec` MOVIE recording (`>= 600` Mali-V61
 * frames, `>= 30+ MB` `.MOV` on SD1 FAT32) on live `GXUP0004.DAT` (Dynarmic JIT) with animated
 * `scene://milk-frog` + live 48 kHz `SCENE_AUDIO` + mid-recording six-stop ND exposure & focus shifts:
 * 1. Continuous recording health: zero hangs, zero deadlocks, bounded JVM heap, zero SD stalls,
 *    zero `0x0508` errors, zero kernel exceptions, continuous Mali-V61 & display cadence.
 * 2. Deep streaming QuickTime `.MOV` ISO-BMFF audit (`ftyp`/`moov`/`mdat`, `mdhd`/`stsd`/`stts`/
 *    `stsz`/`stsc`/`stco`/`co64`):
 *    - Exact video sample count (`>= 600`), valid AVCC/HVCC NAL units at start/middle/ND/end,
 *      varying frame digests (non-frozen!) and live preview-vs-recording consistency;
 *    - Exact 48 kHz `lpcm`/`sowt`/`twos`/`in24` audio track with `< 0.15s` A/V duration drift
 *      and continuous non-silent PCM across start, middle, and end chunks.
 * 3. Clean stop, multi-10s-MB `.MOV` Playback without error, switch back to STILL, and clean
 *    102MP STILL JPEG capture with zero `[RELAXED/MMIO]` and zero `[RELAXED/FLASH]` refusals.
 */
class LongVideoRecordingStressE2eTest {
    private lateinit var evidenceDir: Path

    private data class TrackSummary(
        val handlerType: String,
        val codecTag: String,
        val timescale: Long,
        val durationUnits: Long,
        val durationSec: Double,
        val sampleCount: Int,
        val defaultSampleSize: Int,
        val sampleSizes: IntArray,
        val chunkOffsets: LongArray,
        val stscFirstChunk: IntArray,
        val stscSamplesPerChunk: IntArray,
    ) {
        fun sampleByteLocation(sampleIndex: Int): Pair<Long, Int>? {
            if (sampleIndex !in 0 until sampleCount || chunkOffsets.isEmpty() || stscFirstChunk.isEmpty()) return null
            val size = if (defaultSampleSize > 0) defaultSampleSize else sampleSizes.getOrNull(sampleIndex) ?: return null
            var curSample = 0
            for (chunkIdx in chunkOffsets.indices) {
                val chunkNum1Based = chunkIdx + 1
                var spc = stscSamplesPerChunk[0]
                for (r in stscFirstChunk.indices) {
                    if (stscFirstChunk[r] <= chunkNum1Based) spc = stscSamplesPerChunk[r] else break
                }
                if (sampleIndex < curSample + spc) {
                    var offset = chunkOffsets[chunkIdx]
                    if (defaultSampleSize > 0) {
                        offset += (sampleIndex - curSample).toLong() * defaultSampleSize.toLong()
                    } else {
                        for (s in curSample until sampleIndex) {
                            offset += sampleSizes[s].toLong()
                        }
                    }
                    return offset to size
                }
                curSample += spc
            }
            return null
        }
    }

    private data class VideoSampleProbe(
        val index: Int,
        val byteOffset: Long,
        val sizeBytes: Int,
        val nalCount: Int,
        val firstNalType: Int,
        val validFraming: Boolean,
        val sha256Prefix: String,
    )

    private data class MovDeepReport(
        val path: Path,
        val sizeBytes: Long,
        val topBoxes: List<String>,
        val hasFtyp: Boolean,
        val hasMoov: Boolean,
        val hasMdat: Boolean,
        val videoTrack: TrackSummary?,
        val audioTrack: TrackSummary?,
        val videoProbes: List<VideoSampleProbe>,
        val audioChunkPeaks: List<Pair<String, Int>>,
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

    private class PreviewCadence(private val log: Path) {
        private val startNanos = System.nanoTime()
        private var lastNanos = startNanos
        private var lastSequence = -1L
        private val pending = StringBuilder()
        var maxWallGapNanos = 0L
            private set
        var maxSequenceStep = 0L
            private set

        init {
            Files.writeString(log, "tick\tsequence\tcontentSequence\thostElapsedNs\thostGapNs\n")
        }

        fun sample(display: DisplayPort, tick: Long) {
            val sequence = display.sequence
            if (sequence == lastSequence) return
            val now = System.nanoTime()
            val gap = now - lastNanos
            if (lastSequence >= 0L) {
                maxWallGapNanos = maxOf(maxWallGapNanos, gap)
                maxSequenceStep = maxOf(maxSequenceStep, sequence - lastSequence)
            }
            pending.append(tick).append('\t').append(sequence).append('\t').append(display.contentSequence)
                .append('\t').append(now - startNanos).append('\t').append(gap).append('\n')
            lastSequence = sequence
            lastNanos = now
        }

        fun flush() {
            if (pending.isNotEmpty()) {
                Files.writeString(log, pending, StandardOpenOption.APPEND)
                pending.setLength(0)
            }
        }
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long, maxWallMs: Long = 120_000L, cadence: PreviewCadence? = null) {
        val target = rt.telemetry.virtualTick + deltaTicks
        val deadline = System.nanoTime() + maxWallMs * 1_000_000L
        while (rt.telemetry.virtualTick < target && System.nanoTime() < deadline) {
            val keepGoing = rt.owner.submit {
                val display = if (cadence == null) null else rt.host.ports.find("display.main", DisplayPort::class.java)
                var i = 0
                while (i < 1024 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    if (!rt.session.stepFast()) return@submit false
                    if (display != null) cadence?.sample(display, rt.telemetry.virtualTick)
                    i++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                !rt.session.isSocPoweredOff
            }.join()
            if (!keepGoing) break
        }
        cadence?.flush()
        check(rt.telemetry.virtualTick >= target) {
            "Camera stopped or missed the requested tick $target at ${rt.telemetry.virtualTick} within ${maxWallMs}ms"
        }
    }

    private fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long, maxWallMs: Long = 120_000L) {
        val target = rt.telemetry.virtualTick + deltaMs * 54_000L
        while (rt.telemetry.virtualTick < target) {
            stepVirtualTicks(rt, minOf(target - rt.telemetry.virtualTick, 54_000_000L), maxWallMs)
        }
    }

    private fun patchComp(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            rt.host.patchLatestDirect(id, settings, persistToDisk = false)
        }.join()
    }

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun tapButton(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 350L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComp(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xFF
    }.join()

    private fun firmwareAlarmsOnOwner(rt: EmulatorRuntime): ByteArray {
        // Section4 0x01158814 calls 0x01158468 with two groups of 63 error IDs in two little-endian u64s.
        val table = ByteArray(16)
        requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            .read(0x01CBFCA8L, ByteBuffer.wrap(table))
        return table
    }

    private fun describeAlarms(table: ByteArray): String {
        val ids = (0 until 126).filter { id ->
            val bit = (id / 63) * 64 + id % 63
            (table[bit / 8].toInt() ushr (bit % 8)) and 1 != 0
        }
        return "${HexFormat.of().formatHex(table)} ids=$ids"
    }

    private fun hasRestartWarning(pixels: IntArray, width: Int, height: Int): Boolean {
        // The red warning icon and white exclamation glyph are separate from the translated warning text.
        fun at(x: Int, y: Int) = pixels[(height * y / 768) * width + width * x / 1024]
        fun red(color: Int) = (color ushr 16 and 255) > 150 && (color ushr 8 and 255) < 40 && (color and 255) < 40
        fun white(color: Int) = (color ushr 16 and 255) > 235 && (color ushr 8 and 255) > 235 && (color and 255) > 235
        return red(at(116, 322)) && red(at(186, 445)) && red(at(121, 435)) && red(at(183, 348)) &&
            white(at(152, 341)) && white(at(152, 350)) && red(at(152, 375))
    }

    private fun captureMainStats(rt: EmulatorRuntime, png: Path? = null): Triple<Long, Int, Double> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = display.width
        val h = display.height
        val px = IntArray(w * h)
        val copied = display.copyArgb(px)
        val warning = copied && hasRestartWarning(px, w, h)
        val saved = png ?: if (warning) evidenceDir.resolve("camera-restart-warning-${rt.telemetry.virtualTick}.png") else null
        if (saved != null) {
            check(copied) { "No published preview for $saved" }
            val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, w, h, px, 0, w)
            check(ImageIO.write(image, "png", saved.toFile()))
            val lcdc = requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional",
                com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java))
            val offsets = listOf(0x100, 0x108, 0x10c, 0x134, 0x138, 0x140, 0x144, 0x168, 0x16c, 0x174, 0x178) +
                (0x4e8..0x508 step 4)
            val registers = offsets.joinToString(" ") { offset ->
                "${offset.toString(16)}=${lcdc.read(offset.toLong(), 4).toString(16)}"
            }
            val panel = rt.host.ports.find(PanelBacklightPort.PORT_NAME, PanelBacklightPort::class.java)?.backlightState()
            println("[MOVIE-LCD] ${saved.fileName} tick=${rt.telemetry.virtualTick} $registers " +
                "lcdGain=${panel?.lcdGain} lcdUpdates=${panel?.lcdUpdates}")
        }
        if (warning) {
            error("Camera restart warning visible at tick ${rt.telemetry.virtualTick}, alarms=${describeAlarms(firmwareAlarmsOnOwner(rt))}, screenshot=$saved")
        }
        var nonBlack = 0
        var lumaSum = 0.0
        var lumaCount = 0
        val x0 = (w * 0.2).toInt()
        val x1 = (w * 0.8).toInt()
        val y0 = (h * 0.2).toInt()
        val y1 = (h * 0.8).toInt()
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                if ((p and 0x00FFFFFF) != 0) nonBlack++
                if (x in x0 until x1 && y in y0 until y1) {
                    val r = (p ushr 16) and 0xFF
                    val g = (p ushr 8) and 0xFF
                    val b = p and 0xFF
                    lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                    lumaCount++
                }
            }
        }
        Triple(display.sequence, nonBlack, if (lumaCount > 0) lumaSum / lumaCount else 0.0)
    }.join()

    private fun decoderFrameCount(rt: EmulatorRuntime): Long {
        val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
        return (vpu["framesDecodedCount"] as Number).toLong()
    }

    private fun transmittedAudioFrames(rt: EmulatorRuntime): Long = rt.owner.submit {
        requireNotNull(rt.host.ports.find("soc-isp-setup.i2s-audio", I2sAudioPort::class.java))
            .playbackStats().totalTxFrames
    }.join()

    private fun openRecordedMovie(rt: EmulatorRuntime, movie: Path, evidence: Path, tag: String) {
        val before = decoderFrameCount(rt)
        tapButton(rt, "play", 95L, 500L)
        for (poll in 0 until 32) {
            // The firmware briefly blanks the LCD while loading the file. Wait for its actual decoder and poster.
            if (readU32(rt, 0x01CC6424L) in 0x0300..0x03ff && decoderFrameCount(rt) > before) {
                stepVirtualMs(rt, 250L)
                val (_, visiblePixels, _) = captureMainStats(rt)
                if (visiblePixels > 80_000) {
                    val poster = savePlaybackFrame(rt, evidence, "$tag-poster")
                    assertPosterMatchesFile(poster, movie, evidence)
                    return
                }
            }
            stepVirtualMs(rt, 250L)
        }
        captureMainStats(rt, evidence.resolve("$tag-play-timeout.png"))
        fail("Movie poster did not appear for ${movie.fileName} on screen 0x${readU32(rt, 0x01CC6424L).toString(16)}")
    }

    private fun savePlaybackFrame(rt: EmulatorRuntime, evidence: Path, name: String): IntArray = rt.owner.submit {
        val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(port.width * port.height)
        check(port.copyArgb(pixels))
        val image = java.awt.image.BufferedImage(port.width, port.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, port.width, port.height, pixels, 0, port.width)
        Files.createDirectories(evidence)
        ImageIO.write(image, "png", evidence.resolve("$name.png").toFile())
        sampleMovieInterior(image)
    }.join()

    private fun playbackClockGlyphs(rt: EmulatorRuntime): BooleanArray = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        check(display.copyArgb(pixels))
        // GFX100S 2.13 playback HUD: elapsed seconds at (850,16)..(900,51) on the 1024x768 LCD.
        BooleanArray(32 * 24) { index ->
            val x = display.width * (840 + 2 * (index % 32)) / 1024
            val y = display.height * (10 + 2 * (index / 32)) / 768
            (pixels[y * display.width + x] and 255) > 150
        }
    }.join()

    private fun sampleMovieInterior(image: java.awt.image.BufferedImage): IntArray {
        val h = minOf(image.height, image.width * 9 / 16)
        val top = (image.height - h) / 2
        return IntArray(32 * 20) { i ->
            val x = image.width * (18 + (i % 32) * 2) / 100
            val y = top + h * (18 + (i / 32) * 3) / 100
            image.getRGB(x, y)
        }
    }

    private fun assertPosterMatchesFile(poster: IntArray, movie: Path, evidenceDir: Path) {
        val reference = evidenceDir.resolve("movie-file-first-frame.png")
        val errors = evidenceDir.resolve("movie-poster-decoder.log")
        val ffmpeg = System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
        val process = ProcessBuilder(ffmpeg, "-nostdin", "-y", "-v", "error", "-i", movie.toString(),
            "-map", "0:v:0", "-frames:v", "1", "-threads", "1", reference.toString())
            .redirectError(errors.toFile()).start()
        try {
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "First movie frame decode timed out")
            assertEquals(0, process.exitValue(), Files.readString(errors))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val expected = sampleMovieInterior(ImageIO.read(reference.toFile()))
        var difference = 0L
        for (i in expected.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                difference += abs(((expected[i] ushr shift) and 255) - ((poster[i] ushr shift) and 255))
            }
        }
        val mean = difference / (expected.size * 3.0)
        println("[LONG-VIDEO][POSTER] rgbMeanAbsoluteError=$mean samples=${expected.size}")
        assertTrue(mean < 18.0, "Decoded LCD picture differs from the first video frame, RGB mean error=$mean")
    }

    private fun awaitStillReady(rt: EmulatorRuntime, after: String) {
        var lastSequence = captureMainStats(rt).first
        var freshSamples = 0
        repeat(60) {
            stepVirtualMs(rt, 150L)
            val (sequence, nonBlack, _) = captureMainStats(rt)
            val screen = readU32(rt, 0x01CC6424L)
            val busy = readU8(rt, 0x01CBFCA2L)
            val errno = readU8(rt, 0x01CC0628L) or (readU8(rt, 0x01CC0629L) shl 8)
            freshSamples = if ((screen == 0x0100 || screen == 0x0102) && busy == 0 && errno == 0 &&
                sequence > lastSequence && nonBlack > 140_000) freshSamples + 1 else 0
            lastSequence = sequence
            if (freshSamples >= 3) {
                assertHealthyCamera(rt, "STILL_READY after $after")
                println("[LONG-VIDEO][STILL_READY] after=$after tick=${rt.telemetry.virtualTick} seq=$sequence scr=0x${screen.toString(16)}")
                return
            }
        }
        error("Still LiveView failed to resume after $after, scr=0x${readU32(rt, 0x01CC6424L).toString(16)}")
    }

    private fun listDcimFiles(workRoot: Path): Map<String, Path> {
        val dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcim)) return emptyMap()
        val out = linkedMapOf<String, Path>()
        Files.walk(dcim).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sorted().forEach { p ->
                out[dcim.relativize(p).toString().replace('\\', '/')] = p
            }
        }
        return out
    }

    private fun assertHealthyCamera(rt: EmulatorRuntime, tag: String) {
        val status = rt.telemetry.status
        if (status != "running") {
            val state = rt.owner.submit { rt.session.state() }.join()
            error("[$tag] Session stopped with status $status: $state")
        }
        val exc = readU32(rt, 0x0008_4AD4L)
        assertEquals(0, exc, "[$tag] Non-zero kernel exception counter 0x00084AD4=$exc")
        val scr = readU32(rt, 0x01CC6424L)
        assertNotEquals(0x0508, scr, "[$tag] Hit fatal camera error screen scr=0x0508")
        val alarms = rt.owner.submit { firmwareAlarmsOnOwner(rt) }.join()
        if (alarms.any { it != 0.toByte() }) {
            val png = evidenceDir.resolve("firmware-alarm-${rt.telemetry.virtualTick}.png")
            captureMainStats(rt, png)
            error("[$tag] Firmware registered ${describeAlarms(alarms)}, screenshot=$png")
        }
        val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
        assertEquals(0L, (vpu["decodeErrorCount"] as Number).toLong(), "[$tag] Video decoder reported an error")
        var jpegSt = compState(rt, "soc-jpeg")
        // An observation can land between the two genuine MMIO submissions. Give the guest
        // one video frame to finish its retry, then still require every failure to be accounted for.
        for (poll in 0 until 4) {
            if (jpegSt["encoderOverflowPending"] != true) break
            stepVirtualMs(rt, 4L)
            jpegSt = compState(rt, "soc-jpeg")
        }
        val total = (jpegSt.getValue("encoderErrorCount") as Number).toLong()
        val recovered = (jpegSt.getValue("encoderRecoveredOverflowCount") as Number).toLong()
        val unrecovered = (jpegSt.getValue("encoderUnrecoveredErrorCount") as Number).toLong()
        assertEquals(total, recovered + unrecovered)
        assertEquals(0L, unrecovered, "[$tag] JPEG errors=$total recovered=$recovered: ${jpegSt["lastEncoderError"]}; ${jpegSt["lastEncoderRecovery"]}")
        if (recovered > 0) println("[LONG-VIDEO][JPEG_RETRY] stage=$tag total=$total recovered=$recovered ${jpegSt["lastEncoderRecovery"]}")
    }

    private fun assertDecodedFramesChange(movie: Path, tempRoot: Path, decoder: String) {
        val hashes = tempRoot.resolve("${movie.fileName}.framemd5")
        val errors = tempRoot.resolve("${movie.fileName}.decoder.log")
        val process = ProcessBuilder(
            decoder, "-v", "error", "-xerror", "-i", movie.toString(),
            "-map", "0:v:0", "-vsync", "0", "-f", "framemd5", "-",
        ).redirectOutput(hashes.toFile()).redirectError(errors.toFile()).start()
        try {
            assertTrue(process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS), "Movie decode timed out")
            assertEquals(0, process.exitValue(), Files.readString(errors))
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val distinct = Files.lines(hashes).use { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringAfterLast(',').trim() }.distinct().limit(4).count()
        }
        assertTrue(distinct >= 4, "Animated scene produced only $distinct distinct decoded video frames")
    }

    private fun usedHeapMb(): Double {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()).toDouble() / (1024.0 * 1024.0)
    }

    // ---- Streaming QuickTime / ISO-BMFF Deep Inspector ----

    private fun readFully(ch: FileChannel, pos: Long, len: Int): ByteArray {
        val arr = ByteArray(len)
        val bb = ByteBuffer.wrap(arr)
        var off = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, off)
            if (n <= 0) break
            off += n.toLong()
        }
        return arr
    }

    private fun readBoxHeader(ch: FileChannel, pos: Long, end: Long): Triple<String, Long, Long>? {
        if (pos + 8L > end) return null
        val hdr = ByteBuffer.wrap(readFully(ch, pos, 8)).order(ByteOrder.BIG_ENDIAN)
        val size32 = hdr.int.toLong() and 0xFFFF_FFFFL
        val type = String(hdr.array(), 4, 4, Charsets.ISO_8859_1)
        return when (size32) {
            0L -> Triple(type, pos + 8L, end)
            1L -> {
                if (pos + 16L > end) return null
                val size64 = ByteBuffer.wrap(readFully(ch, pos + 8L, 8)).order(ByteOrder.BIG_ENDIAN).long
                if (size64 < 16L || pos + size64 > end) null else Triple(type, pos + 16L, pos + size64)
            }
            else -> if (size32 < 8L || pos + size32 > end) null else Triple(type, pos + 8L, pos + size32)
        }
    }

    private fun listChildBoxes(ch: FileChannel, start: Long, end: Long): List<Triple<String, Long, Long>> {
        val out = ArrayList<Triple<String, Long, Long>>()
        var pos = start
        while (pos + 8L <= end) {
            val box = readBoxHeader(ch, pos, end) ?: break
            out.add(box)
            if (box.third <= pos) break
            pos = box.third
        }
        return out
    }

    // MVTG embeds a little-endian IFD after a 16-byte header.
    private fun extractMovieThumbnail(movie: Path): ByteArray = FileChannel.open(movie).use { ch ->
        fun child(start: Long, end: Long, name: String) = listChildBoxes(ch, start, end).single { it.first == name }
        val moov = child(0, ch.size(), "moov")
        val udta = child(moov.second, moov.third, "udta")
        val mvtg = child(udta.second, udta.third, "MVTG")
        val length = mvtg.third - mvtg.second
        check(length in 18..1_048_576) { "Invalid MVTG length $length in $movie" }
        val data = readFully(ch, mvtg.second, length.toInt())
        val fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = 16
        val count = fields.getShort(ifd).toInt() and 0xffff
        check(ifd + 2 + count * 12 + 4 <= data.size)
        fun longTag(tag: Int): Int {
            val entry = (0 until count).map { ifd + 2 + it * 12 }.single { (fields.getShort(it).toInt() and 0xffff) == tag }
            check(fields.getShort(entry + 2).toInt() == 4 && fields.getInt(entry + 4) == 1)
            return fields.getInt(entry + 8)
        }
        val start = ifd.toLong() + longTag(0x201)
        val size = longTag(0x202)
        check(start >= ifd && size > 0 && start + size <= data.size)
        data.copyOfRange(start.toInt(), start.toInt() + size)
    }

    private fun movieThumbnailError(movie: Path, evidence: Path): Double {
        val name = movie.fileName.toString()
        val jpeg = extractMovieThumbnail(movie)
        Files.write(evidence.resolve("$name-thumbnail.jpg"), jpeg)
        val thumbnail = assertNotNull(ImageIO.read(jpeg.inputStream()), "$name contains an undecodable thumbnail")
        assertEquals(160, thumbnail.width)
        assertEquals(120, thumbnail.height)
        val w = thumbnail.width
        val h = w * 9 / 16
        val rgb = thumbnail.getRGB(0, (thumbnail.height - h) / 2, w, h, null, 0, w)
        val dark = rgb.count { (it shr 16 and 255) < 12 && (it shr 8 and 255) < 12 && (it and 255) < 12 }
        val raw = Files.createTempFile(evidence, "thumbnail-reference-", ".rgb")
        val log = evidence.resolve("$name-thumbnail-decoder.log")
        try {
            val decoder = System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
            val proc = ProcessBuilder(decoder, "-nostdin", "-y", "-v", "error", "-xerror", "-i", movie.toString(),
                "-map", "0:v:0", "-t", "0.5", "-vf", "scale=$w:$h:flags=area", "-pix_fmt", "rgb24",
                "-threads", "1", "-f", "rawvideo", raw.toString()).redirectError(log.toFile()).start()
            try {
                check(proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { "Timed out decoding $name thumbnail references" }
                check(proc.exitValue() == 0) { Files.readString(log) }
            } finally {
                if (proc.isAlive) proc.destroyForcibly().waitFor()
            }
            val frames = Files.readAllBytes(raw)
            val bytesPerFrame = w * h * 3
            check(frames.isNotEmpty() && frames.size % bytesPerFrame == 0)
            val channels = intArrayOf(16, 8, 0)
            val errors = (0 until frames.size / bytesPerFrame).map { frame ->
                var sum = 0L
                var offset = frame * bytesPerFrame
                for (pixel in rgb) for (shift in channels) {
                    sum += abs((pixel ushr shift and 255) - (frames[offset++].toInt() and 255))
                }
                sum / bytesPerFrame.toDouble()
            }
            val best = errors.indices.minBy { errors[it] }
            val reference = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
            var offset = best * bytesPerFrame
            for (y in 0 until h) for (x in 0 until w) {
                val color = ((frames[offset++].toInt() and 255) shl 16) or
                    ((frames[offset++].toInt() and 255) shl 8) or (frames[offset++].toInt() and 255)
                reference.setRGB(x, y, color)
            }
            ImageIO.write(reference, "png", evidence.resolve("$name-thumbnail-reference.png").toFile())
            println("[LONG-VIDEO][THUMBNAIL] movie=$name jpegBytes=${jpeg.size} darkFraction=${dark / rgb.size.toDouble()} " +
                "referenceFrame=$best framesCompared=${errors.size} rgbMeanAbsoluteError=${errors[best]}")
            return errors[best]
        } finally {
            Files.deleteIfExists(raw)
        }
    }


    private fun parseTrackBox(ch: FileChannel, trakDataStart: Long, trakEnd: Long): TrackSummary? {
        val mdia = listChildBoxes(ch, trakDataStart, trakEnd).firstOrNull { it.first == "mdia" } ?: return null
        val mdiaChildren = listChildBoxes(ch, mdia.second, mdia.third)
        val mdhd = mdiaChildren.firstOrNull { it.first == "mdhd" } ?: return null
        val hdlr = mdiaChildren.firstOrNull { it.first == "hdlr" } ?: return null
        val minf = mdiaChildren.firstOrNull { it.first == "minf" } ?: return null

        val mdhdBytes = readFully(ch, mdhd.second, (mdhd.third - mdhd.second).toInt().coerceAtMost(64))
        val mdhdBb = ByteBuffer.wrap(mdhdBytes).order(ByteOrder.BIG_ENDIAN)
        val version = mdhdBytes[0].toInt() and 0xFF
        val timescale: Long
        val duration: Long
        if (version == 1 && mdhdBytes.size >= 32) {
            timescale = mdhdBb.getInt(20).toLong() and 0xFFFF_FFFFL
            duration = mdhdBb.getLong(24)
        } else if (mdhdBytes.size >= 20) {
            timescale = mdhdBb.getInt(12).toLong() and 0xFFFF_FFFFL
            duration = mdhdBb.getInt(16).toLong() and 0xFFFF_FFFFL
        } else {
            return null
        }

        val hdlrBytes = readFully(ch, hdlr.second, (hdlr.third - hdlr.second).toInt().coerceAtMost(64))
        val handlerType = if (hdlrBytes.size >= 12) String(hdlrBytes, 8, 4, Charsets.ISO_8859_1) else "????"

        val stbl = listChildBoxes(ch, minf.second, minf.third).firstOrNull { it.first == "stbl" } ?: return null
        val stblChildren = listChildBoxes(ch, stbl.second, stbl.third)

        var codecTag = "????"
        stblChildren.firstOrNull { it.first == "stsd" }?.let { stsd ->
            val stsdBytes = readFully(ch, stsd.second, (stsd.third - stsd.second).toInt().coerceAtMost(64))
            if (stsdBytes.size >= 16) {
                codecTag = String(stsdBytes, 12, 4, Charsets.ISO_8859_1)
            }
        }

        var defaultSampleSize = 0
        var sampleCount = 0
        var sampleSizes = IntArray(0)
        stblChildren.firstOrNull { it.first == "stsz" }?.let { stsz ->
            val len = (stsz.third - stsz.second).toInt()
            if (len >= 12) {
                val bb = ByteBuffer.wrap(readFully(ch, stsz.second, len)).order(ByteOrder.BIG_ENDIAN)
                defaultSampleSize = bb.getInt(4)
                sampleCount = bb.getInt(8)
                if (defaultSampleSize == 0 && sampleCount > 0 && len >= 12 + sampleCount * 4) {
                    sampleSizes = IntArray(sampleCount) { i -> bb.getInt(12 + i * 4) }
                }
            }
        }

        var chunkOffsets = LongArray(0)
        stblChildren.firstOrNull { it.first == "stco" }?.let { stco ->
            val len = (stco.third - stco.second).toInt()
            if (len >= 8) {
                val bb = ByteBuffer.wrap(readFully(ch, stco.second, len)).order(ByteOrder.BIG_ENDIAN)
                val count = bb.getInt(4)
                if (count > 0 && len >= 8 + count * 4) {
                    chunkOffsets = LongArray(count) { i -> bb.getInt(8 + i * 4).toLong() and 0xFFFF_FFFFL }
                }
            }
        }
        stblChildren.firstOrNull { it.first == "co64" }?.let { co64 ->
            val len = (co64.third - co64.second).toInt()
            if (len >= 8) {
                val bb = ByteBuffer.wrap(readFully(ch, co64.second, len)).order(ByteOrder.BIG_ENDIAN)
                val count = bb.getInt(4)
                if (count > 0 && len >= 8 + count * 8) {
                    chunkOffsets = LongArray(count) { i -> bb.getLong(8 + i * 8) }
                }
            }
        }

        var stscFirst = IntArray(0)
        var stscSpc = IntArray(0)
        stblChildren.firstOrNull { it.first == "stsc" }?.let { stsc ->
            val len = (stsc.third - stsc.second).toInt()
            if (len >= 8) {
                val bb = ByteBuffer.wrap(readFully(ch, stsc.second, len)).order(ByteOrder.BIG_ENDIAN)
                val count = bb.getInt(4)
                if (count > 0 && len >= 8 + count * 12) {
                    stscFirst = IntArray(count) { i -> bb.getInt(8 + i * 12) }
                    stscSpc = IntArray(count) { i -> bb.getInt(8 + i * 12 + 4) }
                }
            }
        }

        val durationSec = if (timescale > 0L) duration.toDouble() / timescale.toDouble() else 0.0
        return TrackSummary(
            handlerType = handlerType,
            codecTag = codecTag,
            timescale = timescale,
            durationUnits = duration,
            durationSec = durationSec,
            sampleCount = sampleCount,
            defaultSampleSize = defaultSampleSize,
            sampleSizes = sampleSizes,
            chunkOffsets = chunkOffsets,
            stscFirstChunk = stscFirst,
            stscSamplesPerChunk = stscSpc,
        )
    }

    private fun measureAudioPeaks(movie: Path, tempRoot: Path, track: TrackSummary): List<Pair<String, Int>> {
        val raw = Files.createTempFile(tempRoot, "decoded-audio-", ".pcm")
        try {
            val decoder = System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
            val process = ProcessBuilder(decoder, "-nostdin", "-v", "error", "-xerror", "-y", "-i", movie.toString(),
                "-map", "0:a:0", "-ac", "2", "-ar", "48000", "-f", "s16le", raw.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            try {
                check(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) { "Movie audio decode timed out" }
                check(process.exitValue() == 0) { "Movie audio decode exited ${process.exitValue()}" }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
            FileChannel.open(raw, StandardOpenOption.READ).use { ch ->
                val frames = ch.size() / 4L
                check(frames > 0 && frames * 4L == ch.size())
                check(abs(frames / 48000.0 - track.durationSec) <= 1.0 / 48000.0) { "Truncated decoded audio" }
                val windowFrames = minOf(frames, 6000L)
                return listOf("START" to 0L, "MID" to (frames - windowFrames) / 2L, "END" to frames - windowFrames).map { (label, first) ->
                    val pcm = ByteBuffer.wrap(readFully(ch, first * 4L, (windowFrames * 4L).toInt()))
                        .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var peak = 0
                    while (pcm.hasRemaining()) peak = maxOf(peak, abs(pcm.get().toInt()))
                    label to peak
                }
            }
        } finally {
            Files.deleteIfExists(raw)
        }
    }

    private fun inspectMovDeep(path: Path, tempRoot: Path): MovDeepReport {
        val fileSize = Files.size(path)
        val topBoxes = mutableListOf<String>()
        var videoTrack: TrackSummary? = null
        var audioTrack: TrackSummary? = null
        val videoProbes = mutableListOf<VideoSampleProbe>()
        val audioPeaks = mutableListOf<Pair<String, Int>>()

        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val top = listChildBoxes(ch, 0L, fileSize)
            for ((name, _, _) in top) topBoxes.add(name)
            val moov = top.firstOrNull { it.first == "moov" }
            if (moov != null) {
                for (child in listChildBoxes(ch, moov.second, moov.third)) {
                    if (child.first == "trak") {
                        val trk = parseTrackBox(ch, child.second, child.third) ?: continue
                        if (trk.handlerType == "vide") videoTrack = trk
                        if (trk.handlerType == "soun") audioTrack = trk
                    }
                }
            }

            // Probe video NAL framing & payload digests at start, ND segment, middle, and end
            val vt = videoTrack
            if (vt != null && vt.sampleCount >= 6) {
                val md = MessageDigest.getInstance("SHA-256")
                val hex = HexFormat.of()
                val probeIndices = linkedSetOf(
                    0,
                    1,
                    (vt.sampleCount * 4) / 10,
                    vt.sampleCount / 2,
                    (vt.sampleCount * 3) / 4,
                    vt.sampleCount - 2,
                    vt.sampleCount - 1,
                ).filter { it in 0 until vt.sampleCount }
                for (idx in probeIndices) {
                    val loc = vt.sampleByteLocation(idx) ?: continue
                    val (off, sz) = loc
                    if (off < 0L || off + sz.toLong() > fileSize || sz < 8) {
                        videoProbes.add(VideoSampleProbe(idx, off, sz, 0, -1, false, ""))
                        continue
                    }
                    val bytes = readFully(ch, off, sz)
                    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                    var pos = 0
                    var nalCount = 0
                    var firstNalType = -1
                    var valid = true
                    while (pos + 4 <= bytes.size) {
                        val nalLen = bb.getInt(pos)
                        if (nalLen <= 0 || pos + 4 + nalLen > bytes.size) {
                            // Also accept Annex-B 00 00 00 01 start code framing if present
                            if (pos == 0 && bytes[0].toInt() == 0 && bytes[1].toInt() == 0 && bytes[2].toInt() == 0 && bytes[3].toInt() == 1 && bytes.size > 5) {
                                nalCount = 1
                                firstNalType = bytes[4].toInt() and 0xFF
                                pos = bytes.size
                                break
                            }
                            valid = false
                            break
                        }
                        if (nalCount == 0) {
                            firstNalType = bytes[pos + 4].toInt() and 0xFF
                        }
                        nalCount++
                        pos += 4 + nalLen
                    }
                    valid = valid && nalCount > 0 && pos == bytes.size
                    md.reset()
                    val digest = hex.formatHex(md.digest(bytes)).substring(0, 16)
                    videoProbes.add(VideoSampleProbe(idx, off, sz, nalCount, firstNalType, valid, digest))
                }
            }

            // Probe PCM audio chunks at start, middle, and end for continuous non-silent audio
            audioTrack?.let { audioPeaks += measureAudioPeaks(path, tempRoot, it) }
        }

        return MovDeepReport(
            path = path,
            sizeBytes = fileSize,
            topBoxes = topBoxes,
            hasFtyp = "ftyp" in topBoxes,
            hasMoov = "moov" in topBoxes,
            hasMdat = "mdat" in topBoxes,
            videoTrack = videoTrack,
            audioTrack = audioTrack,
            videoProbes = videoProbes,
            audioChunkPeaks = audioPeaks,
        )
    }

    @Test
    fun longUninterruptedVideoRecordingStressWithAudioDriftPlaybackAndStillCapture(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_LONG_VIDEO_STRESS") == "1" ||
                System.getProperty("fuji.runLongVideoStress") == "1",
            "Set FUJI_RUN_LONG_VIDEO_STRESS=1 to run the long recording test; set FUJI_FFMPEG if ffmpeg is outside PATH",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                (Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) ||
                    Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))),
            "Requires :stageReleaseLinux output at $stagedRoot",
        )

        evidenceDir = System.getProperty("fuji.longVideoEvidenceDir")?.let(Path::of) ?: tempRoot
        Files.createDirectories(evidenceDir)
        val captureLog = evidenceDir.resolve("recording-frames.tsv")
        Files.writeString(captureLog, "slice\ttick\tdisplaySeq\tencodedFrames\tperiodTicks\tluma\tnonBlack\twallMs\theapMiB\tpng\n")
        val workRoot = tempRoot.resolve("long-video-stress")
        copyDirectory(stagedRoot, workRoot)

        val rawConfig = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        val config = rawConfig + ("realTimePacer" to mapOf("enabled" to false))

        val wallStartMs = System.currentTimeMillis()
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            val controls = evidenceDir.resolve("recording-controls.tsv")
            Files.writeString(controls, "event\ttick\tscreen\tmovieMode\thalf\tfull\tfirmwareAlarms\tactiveErrno\tbusy\n")
            fun checkControls(event: String, movie: Boolean, half: Boolean, full: Boolean) {
                val switches = rt.owner.submit {
                    val port = requireNotNull(rt.host.ports.find("buttons", ButtonPort::class.java))
                    listOf(port.pressed("movieMode"), port.pressed("shutterHalf"), port.pressed("shutterFull"))
                }.join()
                val screen = readU32(rt, 0x01CC6424L)
                val errno = readU8(rt, 0x01CC0628L) or (readU8(rt, 0x01CC0629L) shl 8)
                val row = listOf(event, rt.telemetry.virtualTick, screen.toString(16), *switches.toTypedArray(),
                    rt.owner.submit { describeAlarms(firmwareAlarmsOnOwner(rt)) }.join(), errno, readU8(rt, 0x01CBFCA2L)).joinToString("\t")
                Files.writeString(controls, row + "\n", StandardOpenOption.APPEND)
                assertEquals(listOf(movie, half, full), switches, "Unexpected control positions: $row")
            }
            fun checkMovieHud(event: String) {
                checkControls(event, true, false, false)
                val screen = readU32(rt, 0x01CC6424L)
                assertTrue(screen == 0x0100 || screen == 0x0102, "Expected live movie HUD at $event, screen=$screen")
                val window = rt.owner.submit {
                    requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional",
                        com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)).read(0x178L, 4)
                }.join()
                val width = window and 0xffff
                val height = window ushr 16
                assertTrue(width > 0 && width * 9 == height * 16,
                    "Firmware did not configure the FHD 16:9 preview at $event, picture=${width}x$height")
            }
            fun pressMovieShutter(event: String, halfHoldMs: Long = 220L) {
                checkControls("$event-before", true, false, false)
                patchComp(rt, "buttons", mapOf("shutterHalf" to true))
                stepVirtualMs(rt, halfHoldMs)
                checkControls("$event-half", true, true, false)
                patchComp(rt, "buttons", mapOf("shutterFull" to true))
                stepVirtualMs(rt, 180L)
                checkControls("$event-full", true, true, true)
                patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
                checkControls("$event-release", true, false, false)
            }


            // 1. Cold boot -> OOBE (0x0421) -> Live STILL HUD (0x0100)
            for (i in 0 until 80) {
                stepVirtualMs(rt, 200L)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE Language screen 0x0421")
            stepVirtualMs(rt, 400L)
            for (attempt in 0 until 3) {
                if (readU32(rt, 0x01CC6424L) != 0x0421) break
                tapButton(rt, "menu", 95L, 550L)
            }
            tapButton(rt, "down", 95L, 350L)
            tapButton(rt, "down", 95L, 350L)
            tapButton(rt, "menu", 95L, 550L)
            tapButton(rt, "up", 85L, 250L)
            tapButton(rt, "menu", 95L, 550L)
            tapButton(rt, "menu", 95L, 550L)
            for (i in 0 until 40) {
                stepVirtualMs(rt, 250L)
                val scr = readU32(rt, 0x01CC6424L)
                val (_, nb, _) = captureMainStats(rt)
                if (scr == 0x0100 && nb > 150_000) break
            }
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100 after OOBE")

            checkControls("still-ready", false, false, false)
            val bootFrame = captureMainStats(rt, evidenceDir.resolve("still-ready.png"))
            assertHealthyCamera(rt, "STILL_READY")
            println("[LONG-VIDEO][STILL_READY] tick=${rt.telemetry.virtualTick} seq=${bootFrame.first} " +
                "dcim=${listDcimFiles(workRoot).mapValues { Files.size(it.value) }}")
            // 2. Switch to animated scene://milk-frog with live 48 kHz SCENE_AUDIO + MOVIE mode
            patchComp(
                rt,
                "cmos",
                mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog?motion=on&audio=on", "loop" to true),
            )
            rt.audioPlayback.applyPatch(mapOf("inputMode" to "SCENE_AUDIO", "inputGainDb" to 0.0))
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 2_800L)
            checkMovieHud("movie-ready")
            val (seqPre, nbPre, lumaPre) = captureMainStats(rt, evidenceDir.resolve("movie-ready.png"))
            assertHealthyCamera(rt, "MOVIE_READY")
            assertTrue(nbPre > 140_000, "Movie LiveView blacked out before REC (nb=$nbPre)")

            val dcimBeforeMov = listDcimFiles(workRoot).keys
            val heapStartMb = usedHeapMb()
            val maliStart = (compState(rt, "soc-isp")["maliV61"] as? Map<*, *>)
            val framesStart = (maliStart?.get("framesEncodedCount") as? Number)?.toLong() ?: 0L

            // 3. Record for 75 virtual seconds, including ND and focus changes.
            pressMovieShutter("start")
            var recordingConfirmed = false
            for (attempt in 0 until 12) {
                stepVirtualMs(rt, 100L)
                assertHealthyCamera(rt, "START_RECORDING")
                val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
                val frames = (vpu["framesEncodedCount"] as Number).toLong()
                if (readU32(rt, 0x01CC6424L) == 0x0208 && frames > framesStart) {
                    checkControls("first-accepted-frame", true, false, false)
                    println("[LONG-VIDEO][FIRST_REC] tick=${rt.telemetry.virtualTick} frames=${frames - framesStart}")
                    recordingConfirmed = true
                    break
                }
            }
            if (!recordingConfirmed) {
                checkControls("start-failed", true, false, false)
                val png = evidenceDir.resolve("recording-start-failed.png")
                captureMainStats(rt, png)
                error("Camera did not enter REC with an encoded frame, screenshot=$png")
            }
            val recStartTick = rt.telemetry.virtualTick

            val targetSlices = 75
            var sawRecScr = false
            var lastFrames = framesStart
            var lastSeq = seqPre
            var seqAdvances = 0
            var zeroFrameSlices = 0
            var maxSliceWallMs = 0L
            var peakHeapMb = heapStartMb
            var baselineLumaSum = 0.0
            var baselineLumaN = 0
            var filteredLumaSum = 0.0
            var filteredLumaN = 0

            // Absolute PTS boundaries in a 60000/1001 stream. The first encoded frame may arrive
            // well after the REC button press; align controls to accepted frames instead of that button.
            val cadence = PreviewCadence(evidenceDir.resolve("preview-cadence.tsv"))
            val transitions = ArrayDeque(listOf(15, 35, 45))
            val framePeriodTicks = 900_900L
            for (slice in 1..targetSlices) {
                val sliceWall0 = System.currentTimeMillis()
                val untilTick = rt.telemetry.virtualTick + 54_000_000L
                var encoded = lastFrames - framesStart
                while (rt.telemetry.virtualTick < untilTick) {
                    val targetSecond = transitions.firstOrNull()
                    val framesUntilChange = if (targetSecond != null) {
                        (targetSecond * 54_000_000L + framePeriodTicks - 1L) / framePeriodTicks - encoded
                    } else Long.MAX_VALUE
                    if (targetSecond != null && framesUntilChange <= 0L) {
                        when (targetSecond) {
                            15 -> patchComp(rt, "optical-filter", mapOf("ndStops" to 6.0))
                            35 -> patchComp(rt, "lens", mapOf("focusDistanceM" to 1.55))
                            45 -> patchComp(rt, "optical-filter", mapOf("ndStops" to 0.0))
                        }
                        println("[LONG-VIDEO][CONTROL] targetSec=$targetSecond encoded=$encoded " +
                            "tick=${rt.telemetry.virtualTick} deltaFrames=${-framesUntilChange}")
                        assertTrue(-framesUntilChange <= 1L, "Camera control missed its target PTS")
                        transitions.removeFirst()
                        continue
                    }
                    val remaining = untilTick - rt.telemetry.virtualTick
                    val step = if (targetSecond == null || framesUntilChange > 60L) remaining else {
                        minOf(remaining, maxOf(framePeriodTicks / 2L, (framesUntilChange - 1L) * framePeriodTicks))
                    }
                    stepVirtualTicks(rt, step, cadence = cadence)
                    if (rt.telemetry.virtualTick < untilTick) {
                        val state = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
                        encoded = (state["framesEncodedCount"] as Number).toLong() - framesStart
                    }
                }
                val sliceWallMs = System.currentTimeMillis() - sliceWall0
                if (sliceWallMs > maxSliceWallMs) maxSliceWallMs = sliceWallMs

                val scr = readU32(rt, 0x01CC6424L)
                if (scr == 0x0208) sawRecScr = true
                val png = if (slice in listOf(4, 14, 16, 34, 36, 46, 75)) evidenceDir.resolve("preview-$slice.png") else null
                val (seqNow, nbNow, lumaNow) = captureMainStats(rt, png)
                if (seqNow > lastSeq) {
                    seqAdvances++
                    lastSeq = seqNow
                }
                if (slice in 2..7) {
                    baselineLumaSum += lumaNow
                    baselineLumaN++
                } else if (slice in 16..34) {
                    filteredLumaSum += lumaNow
                    filteredLumaN++
                }
                val maliNow = compState(rt, "soc-isp")["maliV61"] as? Map<*, *>
                val framesNow = (maliNow?.get("framesEncodedCount") as? Number)?.toLong() ?: 0L
                if (slice == 1) {
                    checkControls("first-recording-second", true, false, false)
                    assertEquals(0x0208, scr, "Camera did not enter the recording HUD after pressing the shutter")
                    assertTrue(framesNow > framesStart, "Recording HUD appeared without an encoded frame")
                }
                val bytesNow = (maliNow?.get("bytesEncodedCount") as? Number)?.toLong() ?: 0L
                val activePeriod = maliNow?.get("activeVideoFramePeriodTicks")
                val deltaFrames = framesNow - lastFrames
                if (slice >= 2 && deltaFrames <= 0L) zeroFrameSlices++
                lastFrames = framesNow

                val heapNow = usedHeapMb()
                Files.writeString(captureLog, listOf(slice, rt.telemetry.virtualTick, seqNow, framesNow - framesStart,
                    activePeriod, lumaNow, nbNow, sliceWallMs, heapNow, png?.fileName ?: "").joinToString("\t", postfix = "\n"),
                    StandardOpenOption.APPEND)
                if (heapNow > peakHeapMb) peakHeapMb = heapNow
                assertHealthyCamera(rt, "MOVIE_REC_SLICE_$slice")
                if (slice == 1 || slice % 4 == 0 || slice == targetSlices || deltaFrames <= 0L) {
                    val vSec = (rt.telemetry.virtualTick - recStartTick) / 54_000_000.0
                    println(
                        "[LONG-VIDEO][SLICE $slice/$targetSlices] vSec=${"%.2f".format(vSec)} scr=0x${scr.toString(16)} " +
                            "frames=$framesNow (+$deltaFrames) encMB=${"%.2f".format(bytesNow / 1_048_576.0)} " +
                            "period=$activePeriod nb=$nbNow luma=${"%.1f".format(lumaNow)} wall=${sliceWallMs}ms heap=${"%.1f".format(heapNow)}MB",
                    )
                }
            }
            println("[LONG-VIDEO][PREVIEW_CADENCE] maxWallGapMs=${cadence.maxWallGapNanos / 1e6} " +
                "maxSequenceStep=${cadence.maxSequenceStep}")
            assertTrue(transitions.isEmpty(), "Missed recording controls: $transitions")
            val recDurationVSec = (rt.telemetry.virtualTick - recStartTick) / 54_000_000.0

            // 4. Stop Movie REC cleanly & wait for full FAT32 .MOV finalization on SD1
            pressMovieShutter("stop", halfHoldMs = 160L)

            var movFile: Path? = null
            var lastMovSize = -1L
            var stablePolls = 0
            for (w in 0 until 45) {
                stepVirtualMs(rt, 350L)
                val newFiles = listDcimFiles(workRoot).filterKeys { it !in dcimBeforeMov }
                val candidate = newFiles.values.firstOrNull { it.toString().endsWith(".MOV", ignoreCase = true) }
                val activePeriod = (compState(rt, "soc-isp")["maliV61"] as? Map<*, *>)?.get("activeVideoFramePeriodTicks")
                if (candidate != null && Files.isRegularFile(candidate)) {
                    val sz = Files.size(candidate)
                    if (sz > 16_384L && activePeriod == null) {
                        if (sz == lastMovSize) {
                            stablePolls++
                        } else {
                            lastMovSize = sz
                            stablePolls = 1
                        }
                        movFile = candidate
                        if (stablePolls >= 2) break
                    }
                }
            }
            val scrPostStop = readU32(rt, 0x01CC6424L)
            val sdState = compState(rt, "sdcard-reader")
            val dcimAfterStop = listDcimFiles(workRoot).keys
            assertHealthyCamera(rt, "MOVIE_POST_STOP")
            val maliEnd = compState(rt, "soc-isp")["maliV61"] as? Map<*, *>
            val totalMaliFrames = ((maliEnd?.get("framesEncodedCount") as? Number)?.toLong() ?: 0L) - framesStart
            val totalMaliBytes = (maliEnd?.get("bytesEncodedCount") as? Number)?.toLong() ?: 0L
            val i2sRx = rt.owner.submit {
                rt.host.ports.find("soc-isp-setup.i2s-audio", I2sAudioPort::class.java)?.rxStats()?.toString() ?: "null"
            }.join()
            val heapEndMb = usedHeapMb()

            println(
                "[LONG-VIDEO][REC_DONE] saw0208=$sawRecScr scrPostStop=0x${scrPostStop.toString(16)} durVSec=${"%.2f".format(recDurationVSec)} " +
                    "maliFrames=$totalMaliFrames maliEncMB=${"%.2f".format(totalMaliBytes / 1_048_576.0)} " +
                    "seqAdv=$seqAdvances/$targetSlices zeroFrameSlices=$zeroFrameSlices maxSliceWall=${maxSliceWallMs}ms " +
                    "heap=${"%.1f".format(heapStartMb)}->${"%.1f".format(heapEndMb)}MB (peak=${"%.1f".format(peakHeapMb)}MB) " +
                    "luma(base=${"%.1f".format(baselineLumaSum / maxOf(1, baselineLumaN))},nd=${"%.1f".format(filteredLumaSum / maxOf(1, filteredLumaN))},pre=${"%.1f".format(lumaPre)}) " +
                    "i2sRx=$i2sRx dcim=$dcimAfterStop sd=$sdState mov=${movFile?.fileName}",
            )

            assertTrue(sawRecScr, "Expected Movie REC screen 0x0208 during long recording")
            assertTrue(recDurationVSec >= targetSlices, "Expected $targetSlices virtual recording seconds, got $recDurationVSec")
            assertTrue(totalMaliFrames >= 600L, "Expected >= 600 Mali-V61 encoded frames, got $totalMaliFrames")
            assertEquals(0, zeroFrameSlices, "Detected $zeroFrameSlices stalled 1-vSec slices with 0 encoded frames")
            assertTrue(seqAdvances >= targetSlices - 2, "Display stalled during long video recording (seqAdv=$seqAdvances/$targetSlices)")

            // 5. Deep .MOV ISO-BMFF container, video NAL, audio 48 kHz & A/V drift audit
            val recordedMov = assertNotNull(movFile, "Expected finalized .MOV file on SD1 DCIM after long recording")
            val mov = inspectMovDeep(recordedMov, tempRoot)
            val vt = assertNotNull(mov.videoTrack, "Missing video track in .MOV: $mov")
            val at = assertNotNull(mov.audioTrack, "Missing audio track in .MOV: $mov")
            val avDriftSec = abs(at.durationSec - vt.durationSec)
            val distinctDigests = mov.videoProbes.map { it.sha256Prefix }.filter { it.isNotEmpty() }.toSet()
            println(
                "[LONG-VIDEO][MOV_DEEP] file=${mov.path.fileName} sizeMB=${"%.2f".format(mov.sizeBytes / 1_048_576.0)} (${mov.sizeBytes} B) " +
                    "boxes=${mov.topBoxes} video=(${vt.codecTag} samples=${vt.sampleCount} dur=${"%.3f".format(vt.durationSec)}s ts=${vt.timescale}) " +
                    "audio=(${at.codecTag} samples=${at.sampleCount} dur=${"%.3f".format(at.durationSec)}s ts=${at.timescale}) " +
                    "avDriftSec=${"%.4f".format(avDriftSec)} distinctFrameHashes=${distinctDigests.size}/${mov.videoProbes.size} " +
                    "audioPeaks=${mov.audioChunkPeaks}",
            )
            for (p in mov.videoProbes) {
                println("  [LONG-VIDEO][VIDEO_PROBE] idx=${p.index} off=${p.byteOffset} sz=${p.sizeBytes} nals=${p.nalCount} nal0=0x${p.firstNalType.toString(16)} valid=${p.validFraming} sha=${p.sha256Prefix}")
            }

            assertTrue(mov.hasFtyp && mov.hasMoov && mov.hasMdat, "Invalid .MOV top-level boxes: ${mov.topBoxes}")
            assertTrue(mov.sizeBytes >= 30L * 1024L * 1024L, "Expected >= 30 MiB .MOV file, got ${mov.sizeBytes} B (${"%.2f".format(mov.sizeBytes / 1_048_576.0)} MiB)")
            assertTrue(vt.sampleCount >= 600, "Expected >= 600 video samples in .MOV stsz, got ${vt.sampleCount}")
            assertTrue(vt.durationSec >= targetSlices, "Expected at least $targetSlices video seconds, got ${vt.durationSec}")
            assertTrue(at.durationSec >= targetSlices, "Expected at least $targetSlices audio seconds, got ${at.durationSec}")
            assertTrue(avDriftSec < 0.15, "A/V duration drift exceeded 0.15s: video=${vt.durationSec}s audio=${at.durationSec}s drift=${avDriftSec}s")
            assertTrue(mov.videoProbes.isNotEmpty() && mov.videoProbes.all { it.validFraming && it.sizeBytes > 64 }, "Invalid video NAL framing in probes: ${mov.videoProbes}")
            assertTrue(distinctDigests.size >= 4, "Video frames appear frozen/static (only ${distinctDigests.size} distinct frame digests): ${mov.videoProbes}")
            assertTrue(
                mov.audioChunkPeaks.size == 3 && mov.audioChunkPeaks.all { it.second > 50 },
                "Expected continuous non-silent 48 kHz Milk Frog PCM audio at START/MID/END, got ${mov.audioChunkPeaks}",
            )

            // 6. In-camera playback of the MOV just recorded on this card.
            println("[LONG-VIDEO][PLAY_FILE] name=${recordedMov.fileName} bytes=${Files.size(recordedMov)}")
            openRecordedMovie(rt, recordedMov, evidenceDir, "movie")
            assertHealthyCamera(rt, "PLAYBACK_ENTER")

            tapButton(rt, "down", 95L, 500L)
            val playingCount = decoderFrameCount(rt)
            val playing = savePlaybackFrame(rt, evidenceDir, "movie-playing")
            val initialClock = playbackClockGlyphs(rt)
            assertTrue(initialClock.count { it } > 8, "Movie playback did not display its time counter")
            var clockAdvanced = false
            for (interval in 0 until 16) {
                stepVirtualMs(rt, 400L)
                val clock = playbackClockGlyphs(rt)
                if (clock.count { it } > 8 && clock.indices.count { clock[it] != initialClock[it] } > 8) {
                    clockAdvanced = true
                    break
                }
            }
            if (!clockAdvanced) {
                val stalled = savePlaybackFrame(rt, evidenceDir, "movie-counter-stalled")
                val changedPixels = playing.indices.count { playing[it] != stalled[it] }
                val vpu = compState(rt, "soc-isp")["maliV61"]
                val screen = readU32(rt, 0x01CC6424L).toString(16)
                val state = "screen=0x$screen decoderFramesAtPlay=$playingCount " +
                    "changedPictureSamples=$changedPixels/${playing.size} vpu=$vpu"
                Files.writeString(evidenceDir.resolve("movie-counter-stalled.txt"), state)
                fail("Movie time counter did not advance: $state")
            }
            val later = savePlaybackFrame(rt, evidenceDir, "movie-playing-later")
            assertTrue(decoderFrameCount(rt) > playingCount, "Video decoder stalled after play")
            assertTrue(playing.indices.count { playing[it] != later[it] } > playing.size / 20, "Playback picture is frozen")
            assertHealthyCamera(rt, "PLAYBACK_PLAYING_LONG_MOV")
            tapButton(rt, "down", 95L, 450L)
            val pausedCount = decoderFrameCount(rt)
            val paused = savePlaybackFrame(rt, evidenceDir, "movie-paused")
            stepVirtualMs(rt, 500L)
            assertEquals(pausedCount, decoderFrameCount(rt), "Decoder kept advancing during pause")
            assertTrue(paused.contentEquals(savePlaybackFrame(rt, evidenceDir, "movie-paused-later")), "Paused picture changed")
            tapButton(rt, "down", 95L, 750L)
            assertTrue(decoderFrameCount(rt) > pausedCount, "Resume did not advance decoder")
            tapButton(rt, "up", 95L, 450L)
            assertHealthyCamera(rt, "PLAYBACK_STOPPED_LONG_MOV")

            // 7. Switch back to STILL mode and capture a clean 102MP STILL JPEG
            patchComp(rt, "buttons", mapOf("movieMode" to false))
            stepVirtualMs(rt, 400L)
            tapButton(rt, "play", 95L, 800L)
            for (i in 0 until 8) {
                val s = readU32(rt, 0x01CC6424L)
                if (s in listOf(0x0100, 0x0102) && readU8(rt, 0x01CBFCA2L) == 0) break
                tapButton(rt, "back", 85L, 400L)
            }
            for (i in 0 until 35) {
                val s = readU32(rt, 0x01CC6424L)
                val (_, nb, _) = captureMainStats(rt)
                if (s in listOf(0x0100, 0x0102) && nb > 140_000) break
                stepVirtualMs(rt, 250L)
            }
            assertHealthyCamera(rt, "RETURN_TO_STILL")

            // Switch to MF so shutter release does not block on AF-S focus confirmation
            patchComp(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            stepVirtualMs(rt, 500L)
            awaitStillReady(rt, "movie playback")
            val newPhotos = mutableListOf<String>()
            repeat(3) { shot ->
                val scrPreStill = readU32(rt, 0x01CC6424L)
                val dcimBeforeStill = listDcimFiles(workRoot).keys
                patchComp(rt, "buttons", mapOf("shutterHalf" to true))
                stepVirtualMs(rt, 220L)
                patchComp(rt, "buttons", mapOf("shutterFull" to true))
                stepVirtualMs(rt, 350L)
                patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
                var stillJpg: Path? = null
                for (w in 0 until 45) {
                    stepVirtualMs(rt, 350L)
                    val added = listDcimFiles(workRoot).filterKeys { it !in dcimBeforeStill }
                    val cand = added.values.firstOrNull { it.toString().endsWith(".JPG", ignoreCase = true) }
                    if (cand != null && Files.size(cand) > 50_000L && readU8(rt, 0x01CBFCA2L) == 0) {
                        stillJpg = cand
                        break
                    }
                }
                assertHealthyCamera(rt, "POST_VIDEO_STILL_CAPTURE")
                val jpgPath = assertNotNull(
                    stillJpg,
                    "Expected valid 102MP STILL JPEG after long video playback (scrPreStill=0x${scrPreStill.toString(16)}, scrNow=0x${readU32(rt, 0x01CC6424L).toString(16)}, dcim=${listDcimFiles(workRoot).keys})",
                )
                val jpgBytes = Files.readAllBytes(jpgPath)
                assertTrue(jpgBytes.size > 50_000, "Expected >50 KB STILL JPEG, got ${jpgBytes.size} B")
                assertEquals(0xFF, jpgBytes[0].toInt() and 0xFF, "JPEG SOI[0] mismatch")
                assertEquals(0xD8, jpgBytes[1].toInt() and 0xFF, "JPEG SOI[1] mismatch")
                assertEquals(0xFF, jpgBytes[jpgBytes.size - 2].toInt() and 0xFF, "JPEG EOI[0] mismatch")
                assertEquals(0xD9, jpgBytes[jpgBytes.size - 1].toInt() and 0xFF, "JPEG EOI[1] mismatch")
                ImageIO.createImageInputStream(jpgPath.toFile()).use { input ->
                    val reader = ImageIO.getImageReaders(input).asSequence().first()
                    try {
                        reader.input = input
                        assertEquals(11648, reader.getWidth(0))
                        assertEquals(8736, reader.getHeight(0))
                        val params = reader.defaultReadParam.apply { setSourceSubsampling(16, 16, 0, 0) }
                        val decoded = reader.read(0, params)
                        assertEquals(728, decoded.width)
                        assertEquals(546, decoded.height)
                    } finally {
                        reader.dispose()
                    }
                }
                awaitStillReady(rt, jpgPath.fileName.toString())
                newPhotos += jpgPath.fileName.toString()
                println("[LONG-VIDEO][STILL] shot=${shot + 1} name=${jpgPath.fileName} bytes=${jpgBytes.size}")
            }
            assertEquals(3, newPhotos.distinct().size)
            val jpegDecodes = (compState(rt, "soc-jpeg")["decodeCount"] as Number).toLong()
            tapButton(rt, "play", 95L, 200L)
            for (poll in 0 until 30) {
                if (readU32(rt, 0x01CC6424L) in 0x0300..0x03ff &&
                    (compState(rt, "soc-jpeg")["decodeCount"] as Number).toLong() > jpegDecodes) break
                stepVirtualMs(rt, 100L)
            }
            assertTrue(readU32(rt, 0x01CC6424L) in 0x0300..0x03ff, "Last JPEG did not enter playback")
            assertTrue((compState(rt, "soc-jpeg")["decodeCount"] as Number).toLong() > jpegDecodes,
                "The camera did not decode the most recent JPEG for playback")
            savePlaybackFrame(rt, evidenceDir, "last-still-playback")
            tapButton(rt, "play", 95L, 600L)
            awaitStillReady(rt, "last JPEG playback")


            // Reopen REC in the same session after the JPEG playback round trip.
            val beforeSecondMovie = listDcimFiles(workRoot).keys
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 1_500L)
            checkMovieHud("second-movie-ready")
            captureMainStats(rt, evidenceDir.resolve("second-movie-ready.png"))
            pressMovieShutter("second-start")
            stepVirtualMs(rt, 6_000L)
            assertEquals(0x0208, readU32(rt, 0x01CC6424L), "Second recording did not reach REC")
            pressMovieShutter("second-stop")
            var secondMovie: Path? = null
            var observedSize = -1L
            for (poll in 0 until 40) {
                stepVirtualMs(rt, 300L)
                val nextMovie = listDcimFiles(workRoot).filterKeys { it !in beforeSecondMovie }
                    .values.singleOrNull { it.fileName.toString().endsWith(".MOV", true) } ?: continue
                val size = Files.size(nextMovie)
                if (size > 0 && size == observedSize && readU8(rt, 0x01CBFCA2L) == 0) {
                    secondMovie = nextMovie
                    break
                }
                observedSize = size
            }
            val finalized = assertNotNull(secondMovie, "Second MOV did not finalize")
            val secondReport = inspectMovDeep(finalized, tempRoot)
            assertTrue(assertNotNull(secondReport.videoTrack).durationSec >= 5.0, "Second video track is too short")
            assertTrue(assertNotNull(secondReport.audioTrack).durationSec >= 5.0, "Second audio track is too short")
            assertDecodedFramesChange(finalized, tempRoot, System.getenv("FUJI_FFMPEG") ?: "ffmpeg")
            val thumbnailErrors = listOf(recordedMov, finalized).associate { it.fileName to movieThumbnailError(it, evidenceDir) }
            assertHealthyCamera(rt, "SECOND_RECORDING_FINALIZED")
            assertTrue(thumbnailErrors.values.all { it < 18.0 }, "Embedded thumbnails differ from decoded video: $thumbnailErrors")
            println("[LONG-VIDEO][SECOND_MOV] name=${finalized.fileName} bytes=${Files.size(finalized)}")
            openRecordedMovie(rt, finalized, Files.createDirectories(evidenceDir.resolve("second-movie")), "second-movie")
            val beforeSecondPlay = decoderFrameCount(rt)
            val beforeSecondAudio = transmittedAudioFrames(rt)
            tapButton(rt, "down", 95L, 500L)
            stepVirtualMs(rt, 1_250L)
            assertTrue(decoderFrameCount(rt) > beforeSecondPlay + 3, "Second movie failed to advance during playback")
            assertTrue(transmittedAudioFrames(rt) > beforeSecondAudio, "Second movie produced no I2S playback frames")
            assertHealthyCamera(rt, "SECOND_MOVIE_PLAYBACK")

            // Container headers and IDR picture IDs can change while every decoded pixel stays gray.
            assertDecodedFramesChange(recordedMov, tempRoot, System.getenv("FUJI_FFMPEG") ?: "ffmpeg")
            val relaxed = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join().orEmpty()
            val wallSec = (System.currentTimeMillis() - wallStartMs) / 1000.0
            println(
                "[LONG-VIDEO][FINAL_PASS] wall=${"%.1f".format(wallSec)}s mov=${mov.path.fileName}(${"%.2f".format(mov.sizeBytes / 1_048_576.0)}MB) " +
                    "stills=$newPhotos relaxed=$relaxed",
            )
            assertTrue(!relaxed.contains("[RELAXED/MMIO]"), "Expected zero [RELAXED/MMIO], got $relaxed")
            assertTrue(!relaxed.contains("refused=") || relaxed.contains("refused=0"), "Expected zero flash refusals, got $relaxed")
        }
    }
}
