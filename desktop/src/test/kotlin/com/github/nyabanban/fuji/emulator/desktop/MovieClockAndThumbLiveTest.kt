package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Env-gated (`FUJI_RUN_MOVIE_CLOCK_THUMB_LIVE=1`) live unmodified `GXUP0004.DAT` regression suite for:
 * 1. Movie recording virtual clock cadence (`59.94 fps` period = `900,900` ticks @ 54 MHz) and `.MOV`
 *    container `mvhd`/`mdhd` video & audio track duration consistency.
 * 2. Real-silicon Milbeaut packed `YYUV422` (`[Y0, Y1, Cb, Cr]`) end-to-end fidelity across Movie
 *    recording `MVTG` `160x120` JPEG thumbnail, single-frame movie playback review (translucent
 *    neutral-gray left/right film-strip sprocket holes with zero olive-green bars), 9-frame grid,
 *    and 100-frame grid.
 */
class MovieClockAndThumbLiveTest {
    private data class GreenScanResult(
        val totalGreen: Int,
        val leftGreen: Int,
        val rightGreen: Int,
        val topGreen: Int,
        val botGreen: Int,
        val greenColumns: List<Int>,
    )

    private data class TrackDuration(
        val handler: String,
        val timescale: Long,
        val duration: Long,
        val durationSec: Double,
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

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long, maxWallMs: Long = 120_000L) {
        val target = rt.telemetry.virtualTick + deltaTicks
        val deadline = System.nanoTime() + maxWallMs * 1_000_000L
        while (rt.telemetry.virtualTick < target && System.nanoTime() < deadline) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 1024 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                !rt.session.isSocPoweredOff
            }.join()
            if (!keepGoing) break
        }
    }

    private fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long) {
        stepVirtualTicks(rt, deltaMs * 54_000L)
    }

    private fun patchComp(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit { rt.host.patchLatestDirect(id, settings, persistToDisk = false) }.join()
    }

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.getInt(0)
    }.join()

    private fun tapButton(rt: EmulatorRuntime, key: String, holdMs: Long = 95L, settleMs: Long = 400L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComp(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun captureMain(rt: EmulatorRuntime, outPng: Path?): BufferedImage = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = display.width
        val h = display.height
        val px = IntArray(w * h)
        check(display.copyArgb(px)) { "No main display frame available" }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        if (outPng != null) {
            Files.createDirectories(outPng.parent)
            ImageIO.write(img, "png", outPng.toFile())
        }
        img
    }.join()

    private fun listDcimMovs(workRoot: Path): List<Path> {
        val dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcim)) return emptyList()
        return Files.walk(dcim).use { s ->
            s.filter { Files.isRegularFile(it) && it.toString().endsWith(".MOV", ignoreCase = true) }.sorted().toList()
        }
    }

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

    private fun extractMovieThumbnail(movie: Path): ByteArray = FileChannel.open(movie).use { ch ->
        fun child(start: Long, end: Long, name: String) = listChildBoxes(ch, start, end).single { it.first == name }
        val moov = child(0, ch.size(), "moov")
        val udta = child(moov.second, moov.third, "udta")
        val mvtg = child(udta.second, udta.third, "MVTG")
        val length = (mvtg.third - mvtg.second).toInt()
        val data = readFully(ch, mvtg.second, length)
        val fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val ifd = 16
        val count = fields.getShort(ifd).toInt() and 0xffff
        fun longTag(tag: Int): Int {
            val entry = (0 until count).map { ifd + 2 + it * 12 }.single { (fields.getShort(it).toInt() and 0xffff) == tag }
            return fields.getInt(entry + 8)
        }
        val start = ifd + longTag(0x201)
        val size = longTag(0x202)
        data.copyOfRange(start, start + size)
    }

    private fun readTrackDurations(movie: Path): Pair<Double, List<TrackDuration>> = FileChannel.open(movie).use { ch ->
        val moov = listChildBoxes(ch, 0, ch.size()).single { it.first == "moov" }
        val moovChildren = listChildBoxes(ch, moov.second, moov.third)
        val mvhd = moovChildren.single { it.first == "mvhd" }
        val mvhdBuf = ByteBuffer.wrap(readFully(ch, mvhd.second, (mvhd.third - mvhd.second).toInt())).order(ByteOrder.BIG_ENDIAN)
        val mvhdVersion = mvhdBuf.get(0).toInt() and 0xff
        val (movieTimescale, movieDuration) = if (mvhdVersion == 1) {
            (mvhdBuf.getInt(20).toLong() and 0xFFFF_FFFFL) to mvhdBuf.getLong(24)
        } else {
            (mvhdBuf.getInt(12).toLong() and 0xFFFF_FFFFL) to (mvhdBuf.getInt(16).toLong() and 0xFFFF_FFFFL)
        }
        val movieSec = if (movieTimescale > 0L) movieDuration.toDouble() / movieTimescale.toDouble() else 0.0
        val tracks = moovChildren.filter { it.first == "trak" }.mapNotNull { trak ->
            val mdia = listChildBoxes(ch, trak.second, trak.third).firstOrNull { it.first == "mdia" } ?: return@mapNotNull null
            val mdiaChildren = listChildBoxes(ch, mdia.second, mdia.third)
            val mdhd = mdiaChildren.firstOrNull { it.first == "mdhd" } ?: return@mapNotNull null
            val hdlr = mdiaChildren.firstOrNull { it.first == "hdlr" } ?: return@mapNotNull null
            val mdhdBuf = ByteBuffer.wrap(readFully(ch, mdhd.second, (mdhd.third - mdhd.second).toInt())).order(ByteOrder.BIG_ENDIAN)
            val v = mdhdBuf.get(0).toInt() and 0xff
            val (ts, dur) = if (v == 1) {
                (mdhdBuf.getInt(20).toLong() and 0xFFFF_FFFFL) to mdhdBuf.getLong(24)
            } else {
                (mdhdBuf.getInt(12).toLong() and 0xFFFF_FFFFL) to (mdhdBuf.getInt(16).toLong() and 0xFFFF_FFFFL)
            }
            val hdlrBytes = readFully(ch, hdlr.second, minOf(16, (hdlr.third - hdlr.second).toInt()))
            val handler = if (hdlrBytes.size >= 12) String(hdlrBytes, 8, 4, Charsets.ISO_8859_1) else "????"
            TrackDuration(handler, ts, dur, if (ts > 0L) dur.toDouble() / ts.toDouble() else 0.0)
        }
        movieSec to tracks
    }

    private fun scanGreenBorders(img: BufferedImage): GreenScanResult {
        val w = img.width
        val h = img.height
        var totalGreen = 0
        var leftGreen = 0
        var rightGreen = 0
        var topGreen = 0
        var botGreen = 0
        val colCounts = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = img.getRGB(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                if (g > r + 20 && g > b + 20 && g > 30) {
                    totalGreen++
                    colCounts[x]++
                    if (x < w / 6) leftGreen++
                    if (x >= w - w / 6) rightGreen++
                    if (y < h / 6) topGreen++
                    if (y >= h - h / 6) botGreen++
                }
            }
        }
        val greenCols = colCounts.indices.filter { colCounts[it] > h / 10 }
        return GreenScanResult(totalGreen, leftGreen, rightGreen, topGreen, botGreen, greenCols)
    }

    @Test
    fun movieClockCadenceAndPlaybackReviewHaveZeroGreenBars(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_MOVIE_CLOCK_THUMB_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("movie-clock-thumb")
        copyDirectory(stagedRoot, workRoot)
        val dumpDir = System.getenv("FUJI_DIAG_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        val rawConfig = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        val config = rawConfig + ("realTimePacer" to mapOf("enabled" to false))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            // OOBE -> STILL
            for (i in 0 until 80) {
                stepVirtualMs(rt, 200L)
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
            }
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
                if (readU32(rt, 0x01CC6424L) == 0x0100) break
            }
            stepVirtualMs(rt, 500L)

            // Switch to MOVIE mode (stock 2.13 default FHD 59.94p)
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 2_500L)
            captureMain(rt, dumpDir?.resolve("01-movie-standby.png"))

            val framesBefore = ((compState(rt, "soc-isp")["maliV61"] as Map<*, *>)["framesEncodedCount"] as Number).toLong()

            // Start REC
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 220L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

            for (w in 0 until 15) {
                stepVirtualMs(rt, 100L)
                val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
                if (readU32(rt, 0x01CC6424L) == 0x0208 && (vpu["framesEncodedCount"] as Number).toLong() > framesBefore) break
            }
            val recStartTick = rt.telemetry.virtualTick
            val recStartFrames = ((compState(rt, "soc-isp")["maliV61"] as Map<*, *>)["framesEncodedCount"] as Number).toLong()

            // Record 2 virtual seconds and verify 1:1 virtual clock frame cadence (~60 fps @ 59.94p).
            val targetVirtualSeconds = 2
            var prevFrames = recStartFrames
            for (sec in 1..targetVirtualSeconds) {
                stepVirtualTicks(rt, 54_000_000L)
                val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
                val frames = (vpu["framesEncodedCount"] as Number).toLong()
                val deltaFrames = frames - prevFrames
                val period = (vpu["activeVideoFramePeriodTicks"] as Number).toLong()
                assertEquals(900_900L, period, "FHD 59.94p video frame period must be exact 900,900 ticks @ 54 MHz")
                assertTrue(deltaFrames in 58L..62L, "Expected ~60 frames in virtual second $sec, got $deltaFrames")
                assertEquals(0L, (vpu["encodeErrorCount"] as Number).toLong(), "Zero VPU encode errors expected")
                captureMain(rt, dumpDir?.resolve("02-rec-vsec$sec.png"))
                prevFrames = frames
            }
            val recVirtualSec = (rt.telemetry.virtualTick - recStartTick) / 54_000_000.0
            val jpegState = compState(rt, "soc-jpeg")
            assertEquals(0L, (jpegState["encoderErrorCount"] as Number).toLong(), "Zero JPEG encode errors expected")

            // Stop REC and wait for finalized .MOV on SD card
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 160L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

            var movFile: Path? = null
            var lastSz = -1L
            var stable = 0
            for (w in 0 until 45) {
                stepVirtualMs(rt, 300L)
                val movs = listDcimMovs(workRoot)
                val activePeriod = (compState(rt, "soc-isp")["maliV61"] as Map<*, *>)["activeVideoFramePeriodTicks"]
                if (movs.isNotEmpty() && activePeriod == null) {
                    val sz = Files.size(movs.last())
                    if (sz > 16_384L) {
                        if (sz == lastSz) stable++ else { lastSz = sz; stable = 1 }
                        movFile = movs.last()
                        if (stable >= 2) break
                    }
                }
            }
            val mov = assertNotNull(movFile, "Expected finalized .MOV file on SD card")
            val totalEncodedFrames = ((compState(rt, "soc-isp")["maliV61"] as Map<*, *>)["framesEncodedCount"] as Number).toLong() - framesBefore
            val expectedVideoSecFromFrames = totalEncodedFrames * 1001.0 / 60_000.0

            // Verify .MOV container movie/video/audio durations match actual encoded frames & stay A/V synced
            val (movieSec, tracks) = readTrackDurations(mov)
            val videTrack = assertNotNull(tracks.firstOrNull { it.handler == "vide" }, "Expected video track in $mov")
            val sounTrack = assertNotNull(tracks.firstOrNull { it.handler == "soun" }, "Expected audio track in $mov")
            println(
                "[MOVIE-CLOCK] recVirtualSec=${"%.3f".format(recVirtualSec)} encodedFrames=$totalEncodedFrames " +
                    "expectedFromFrames=${"%.3f".format(expectedVideoSecFromFrames)} movieSec=${"%.3f".format(movieSec)} " +
                    "videSec=${"%.3f".format(videTrack.durationSec)} sounSec=${"%.3f".format(sounTrack.durationSec)} " +
                    "size=${Files.size(mov)}",
            )
            assertTrue(
                videTrack.durationSec >= recVirtualSec - 0.10 &&
                    abs(videTrack.durationSec - expectedVideoSecFromFrames) < 0.15,
                "Video track duration (${videTrack.durationSec}s) must match encoded frames ($totalEncodedFrames -> ${"%.3f".format(expectedVideoSecFromFrames)}s) and >= recVirtualSec (${recVirtualSec}s)",
            )
            assertTrue(
                abs(videTrack.durationSec - sounTrack.durationSec) < 0.25,
                "Video (${videTrack.durationSec}s) and audio (${sounTrack.durationSec}s) track durations must stay in sync",
            )

            // 1. Verify embedded 160x120 MVTG JPEG thumbnail has 0 green pixels
            val thumbJpeg = extractMovieThumbnail(mov)
            val thumbImg = assertNotNull(ImageIO.read(thumbJpeg.inputStream()), "MVTG JPEG thumbnail must decode cleanly")
            assertEquals(160, thumbImg.width)
            assertEquals(120, thumbImg.height)
            if (dumpDir != null) {
                Files.createDirectories(dumpDir)
                ImageIO.write(thumbImg, "png", dumpDir.resolve("03-mvtg-thumb.png").toFile())
            }
            val thumbGreen = scanGreenBorders(thumbImg)
            assertEquals(0, thumbGreen.totalGreen, "MVTG 160x120 thumbnail must have 0 green pixels: $thumbGreen")

            // 2. Press PLAY -> single-frame movie playback review with left/right film-strip sprocket holes
            tapButton(rt, "play", 95L, 500L)
            for (poll in 0 until 30) {
                stepVirtualMs(rt, 250L)
                val scr = readU32(rt, 0x01CC6424L)
                val vpu = compState(rt, "soc-isp")["maliV61"] as Map<*, *>
                val dec = (vpu["framesDecodedCount"] as Number).toLong()
                if (scr in 0x0300..0x03ff && dec > 0) {
                    stepVirtualMs(rt, 400L)
                    assertEquals(0L, (vpu["decodeErrorCount"] as Number).toLong(), "Zero VPU decode errors expected")
                    break
                }
            }
            val singleImg = captureMain(rt, dumpDir?.resolve("04-play-single.png"))
            val singleGreen = scanGreenBorders(singleImg)
            assertEquals(0, singleGreen.leftGreen, "Single-frame movie playback left sprocket border must have 0 green pixels: $singleGreen")
            assertEquals(0, singleGreen.rightGreen, "Single-frame movie playback right sprocket border must have 0 green pixels: $singleGreen")
            assertTrue(singleGreen.greenColumns.isEmpty(), "Single-frame movie playback must have 0 green vertical bar columns: $singleGreen")

            // 3. 9-frame playback grid -> 0 green pixels anywhere
            patchComp(rt, "soc-adc", mapOf("rearDialDetents" to -1L))
            stepVirtualMs(rt, 2_500L)
            val grid9Img = captureMain(rt, dumpDir?.resolve("05-play-grid9.png"))
            val grid9Green = scanGreenBorders(grid9Img)
            assertEquals(0, grid9Green.totalGreen, "9-frame playback grid must have 0 green pixels: $grid9Green")

            // 4. 100-frame playback grid -> 0 green pixels anywhere
            patchComp(rt, "soc-adc", mapOf("rearDialDetents" to -2L))
            stepVirtualMs(rt, 2_500L)
            val grid100Img = captureMain(rt, dumpDir?.resolve("06-play-grid100.png"))
            val grid100Green = scanGreenBorders(grid100Img)
            assertEquals(0, grid100Green.totalGreen, "100-frame playback grid must have 0 green pixels: $grid100Green")
        }
    }
}
