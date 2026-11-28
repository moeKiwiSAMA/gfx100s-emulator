package com.github.nyabanban.fuji.emulator.components.isp

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaliV61DecodeTest {
    @Test
    fun absentAndInvalidColourDescriptionsKeepLegacySps() {
        val enc = MaliV61BitstreamEncoder
        val parse = MaliV61BitstreamEncoder.ColourDescription.Companion::fromMve
        assertEquals(null, parse(intArrayOf(15, -4)))
        assertEquals(null, parse(intArrayOf(15, -4, 0x03030102)))
        assertEquals(null, parse(intArrayOf(15, -4, 0x09120903)))

        // Recorded from the original public SPS overloads at 1920x1080 and 30 fps.
        val avc = listOf(
            "000000016742c02ada01e0089f961001b7740066ff300840",
            "000000016742c02ada01e0089f966c800dbba00337f98042",
        )
        val hevc = listOf(
            "00000001420101022000000300b00000030000030099a003c0801107cad94baba08804006ddd000cdfe60020",
            "00000001420101022000000300b00000030000030099a003c0801107cad94baba089b020036ee80066ff3001",
        )
        for ((index, full) in listOf(false, true).withIndex()) {
            assertEquals(avc[index], enc.buildH264Sps(1920, 1080, 30L shl 16, full).toHexString())
            assertEquals(hevc[index], enc.buildH265Sps(1920, 1080, true, 30L shl 16, full).toHexString())
        }
    }

    @Test
    fun avcAndHevcSignalMveColourDescriptionInTheElementaryStream(@TempDir dir: Path) {
        val enc = MaliV61BitstreamEncoder
        for (packed in listOf(0x06060101, 0x09120902, 0x06060100)) {
            val colour = requireNotNull(MaliV61BitstreamEncoder.ColourDescription.fromMve(intArrayOf(15, -4, packed)))
            // A range of zero inherits the existing full-range input-surface setting.
            val expectedRange = if (colour.range == 1) 0 else 1
            val fps = 30L shl 16
            val streams = mapOf(
                "h264" to enc.buildH264Sps(16, 16, fps, true, colour) + enc.buildH264Pps() +
                    enc.buildH264IdrSlice(16, 16, 0),
                "hevc" to enc.buildH265Vps(true) + enc.buildH265Sps(16, 16, true, fps, true, false, colour) +
                    enc.buildH265Pps() + enc.buildH265IdrSlice(16, 16, bitDepth10 = true),
            )
            for ((codec, data) in streams) {
                val input = Files.write(dir.resolve("picture.$codec"), data)
                val log = dir.resolve("trace.txt")
                val explicit = System.getenv("FUJI_FFMPEG")
                val trace = try {
                    ProcessBuilder(explicit ?: "ffmpeg", "-hide_banner", "-i", input.toString(), "-c:v", "copy",
                        "-bsf:v", "trace_headers", "-frames:v", "1", "-f", "null", "-")
                        .redirectError(log.toFile()).start()
                } catch (e: IOException) {
                    if (explicit != null) throw e
                    assumeTrue(false, "FFmpeg unavailable: ${e.message}")
                    return
                }
                try {
                    assertTrue(trace.waitFor(30, TimeUnit.SECONDS), "FFmpeg header trace timed out")
                    val headers = Files.readString(log)
                    assertEquals(0, trace.exitValue(), headers)
                    val expected = mapOf("video_full_range_flag" to expectedRange,
                        "colour_description_present_flag" to 1, "colour_primaries" to colour.primaries,
                        "transfer_characteristics" to colour.transfer, "matrix_coefficients" to colour.matrix)
                    for ((field, value) in expected) {
                        assertTrue(Regex("""(?m)\b$field\s+[01]+ = $value\s*$""").containsMatchIn(headers),
                            "$codec missing $field=$value: $headers")
                    }
                } finally {
                    if (trace.isAlive) trace.destroyForcibly()
                }
            }
        }
    }

    @Test
    fun avcCoarseFramesKeepNeutralChromaBesideColouredBlocks(@TempDir dir: Path) {
        val width = 256
        val height = 128
        val columns = width / 16
        val blocks = columns * (height / 16)
        val input = dir.resolve("neutral.h264")
        val output = dir.resolve("neutral.yuv")
        val encoder = MaliV61BitstreamEncoder
        fun chroma(block: Int, red: Boolean): Int {
            val x = block % columns
            val y = block / columns
            return when {
                y == 0 -> if (red) 186 - (x % 4) * 23 else 95 + (x % 4) * 18
                y <= 2 && x < 4 -> if (red) 45 + x * 18 else 175 - x * 20
                else -> 128
            }
        }
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(width, height, 30L shl 16))
            stream.write(encoder.buildH264Pps())
            var previous: AvcReferenceFrame? = null
            repeat(2) { n ->
                val cb = ByteArray(blocks) { chroma(it, false).toByte() }
                val cr = ByteArray(blocks) { chroma(it, true).toByte() }
                val luma = ByteArray(blocks) { if (it == 5 && n == 1) 145.toByte() else 123 }
                val frame = AvcReferenceFrame(width, height, luma, cb, cr, null)
                stream.write(encoder.buildH264Picture(frame, previous, n, n))
                previous = frame
            }
        }
        decode(input, output, dir.resolve("neutral.log"), "yuv420p")
        val raw = Files.readAllBytes(output)
        val samples = width * height
        val frameSize = samples * 3 / 2
        assertEquals(frameSize * 2, raw.size)
        for (n in 0..1) for (plane in 0..1) for (block in 0 until blocks) {
            val reference = chroma(block, plane == 1)
            val origin = n * frameSize + samples + plane * samples / 4 +
                (block / columns) * 8 * (width / 2) + (block % columns) * 8
            // Sample the centre of every 4x4 chroma quadrant, away from filtered block edges.
            for (y in listOf(2, 6)) for (x in listOf(2, 6)) {
                val actual = raw[origin + y * (width / 2) + x].toUByte().toInt()
                assertTrue(abs(actual - reference) <= 2, "frame=$n plane=$plane block=$block ($x,$y): $actual vs $reference")
            }
        }
    }

    @Test
    fun avcConstantRateReadsOnlyBlocksThatCanUseRawPixels() {
        val reads = ArrayList<Int>()
        var marker = 400
        val source = MvePcmSource { x, _, pixels ->
            reads += x
            pixels.fill(400, 0, 256)
            pixels.fill(512, 256, 384)
            pixels[17] = marker.toShort()
            true
        }
        var previous: AvcReferenceFrame? = null
        var frameNum = 0
        fun picture(firstY: Int, budget: Int): AvcReferenceFrame {
            reads.clear()
            val current = AvcReferenceFrame(32, 16, byteArrayOf(firstY.toByte(), 100),
                ByteArray(2) { 128.toByte() }, ByteArray(2) { 128.toByte() }, ByteArray(32) { 100 },
                trackChroma = true)
            MaliV61BitstreamEncoder.buildH264Picture(current, previous, 0, frameNum++, source, budget, true)
            previous = current
            return current
        }
        picture(100, 0)
        assertTrue(reads.isEmpty())
        picture(100, 2000)
        assertTrue(reads.isEmpty())
        val changed = picture(104, 2000)
        assertEquals(listOf(0), reads)
        val prior = requireNotNull(changed.copyPcmBlockBytes(0))
        val repeated = picture(104, 0)
        assertEquals(listOf(0), reads)
        assertTrue(prior.contentEquals(repeated.copyPcmBlockBytes(0)))
        marker += 4
        val refreshed = picture(104, 0)
        assertEquals(listOf(0), reads)
        assertEquals(null, refreshed.copyPcmBlockBytes(0))
    }

    @Test
    fun avcPredictivePicturesRefreshMotionBetweenCoarseSampleSites(@TempDir dir: Path) {
        val encoder = MaliV61BitstreamEncoder
        val frames = listOf(55, 55, 190, 190)
        val input = dir.resolve("subblock-motion.h264")
        val output = dir.resolve("subblock-motion.yuv")
        var previous: AvcReferenceFrame? = null
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(32, 16, 30L shl 16))
            stream.write(encoder.buildH264Pps())
            for ((n, marker) in frames.withIndex()) {
                // Every coarse sample is identical. Only an interior 2x2 patch moves in the original surface.
                val frame = AvcReferenceFrame(32, 16, ByteArray(2) { 100 }, ByteArray(2) { 128.toByte() },
                    ByteArray(2) { 128.toByte() }, ByteArray(32) { 100 }, trackChroma = true)
                val raw = MvePcmSource { mx, _, pixels ->
                    pixels.fill(400, 0, 256)
                    pixels.fill(512, 256, 384)
                    if (mx == 0) for (y in 7..8) for (x in 7..8) pixels[y * 16 + x] = (marker * 4).toShort()
                    true
                }
                val coded = encoder.buildH264Picture(frame, previous, n, n, raw, 2000, false)
                if (n == 1 || n == 3) assertTrue(coded.size <= 12)
                stream.write(coded)
                previous = frame
            }
        }
        decode(input, output, dir.resolve("raw-motion.log"), "yuv420p")
        val pixels = Files.readAllBytes(output)
        for ((n, expected) in frames.withIndex()) {
            assertEquals(expected, pixels[n * 32 * 16 * 3 / 2 + 7 * 32 + 7].toUByte().toInt())
        }
    }


    @Test
    fun avcConstantBitrateSpendsPictureBytesOnOriginalPcmSamples(@TempDir dir: Path) {
        val width = 128
        val height = 64
        val cols = width / 16
        val blocks = cols * height / 16
        val encoder = MaliV61BitstreamEncoder
        val desired = listOf(2000, 4500, 8000)
        val input = dir.resolve("pcm.h264")
        val output = dir.resolve("pcm.yuv")
        val masks = ArrayList<BooleanArray>()
        fun intensity(x: Int, y: Int) = 86 + (x / 4 % 2) * 48 + (if ((x + y) % 2 == 0) 5 else -5)
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(width, height, 30L shl 16))
            stream.write(encoder.buildH264Pps())
            for (goal in desired) {
                val used = BooleanArray(blocks)
                val source = MvePcmSource { mx, my, pixels ->
                    used[my * cols + mx] = true
                    for (y in 0 until 16) for (x in 0 until 16) {
                        pixels[y * 16 + x] = (intensity(mx * 16 + x, my * 16 + y) shl 2).toShort()
                    }
                    pixels.fill(384, 256, 320)
                    pixels.fill(640, 320, 384)
                    true
                }
                val frame = AvcReferenceFrame(width, height, ByteArray(blocks) { 108 }, ByteArray(blocks) { 96 },
                    ByteArray(blocks) { 160.toByte() }, ByteArray(blocks * 16) { i -> if (i % 2 == 0) 86 else 134.toByte() })
                val coded = encoder.buildH264Picture(frame, null, goal, 0, source, goal, true)
                assertTrue(abs(coded.size - goal) < 410, "target=$goal actual=${coded.size}")
                masks += used
                stream.write(coded)
            }
        }
        assertTrue(masks.zipWithNext().all { (a,b) -> a.count { it } < b.count { it } })
        decode(input, output, dir.resolve("pcm.log"), "yuv420p")
        val raw = Files.readAllBytes(output)
        val frameSize = width * height * 3 / 2
        assertEquals(desired.size * frameSize, raw.size)
        for ((index, mask) in masks.withIndex()) {
            for (block in mask.indices.filter { mask[it] }) {
                val x = (block % cols) * 16 + 7
                val y = (block / cols) * 16 + 7
                val offset = index * frameSize + y * width + x
                assertTrue(abs(raw[offset].toUByte().toInt() - intensity(x, y)) <= 2)
                assertTrue(abs(raw[offset + 1].toUByte().toInt() - intensity(x + 1, y)) <= 2)
            }
        }
    }

    @Test
    fun avcPredictivePicturesKeepStaticBlocksAndRefreshChangedSamples(@TempDir dir: Path) {
        val width = 94
        val height = 46
        val cols = (width + 15) / 16
        val rows = (height + 15) / 16
        val encoder = MaliV61BitstreamEncoder
        val input = dir.resolve("gop.h264")
        val output = dir.resolve("gop.yuv")
        val images = ArrayList<AvcReferenceFrame>()
        var last: AvcReferenceFrame? = null
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(width, height, 30L shl 16))
            stream.write(encoder.buildH264Pps())
            for (n in 0 until 35) {
                val phase = n / 3
                val moving = phase % (cols * rows)
                val y = ByteArray(cols * rows) { i -> (50 + i % 4 * 20 + if (i == moving) 68 else 0).toByte() }
                val cb = ByteArray(cols * rows) { (96 + (phase and 1) * 16).toByte() }
                val cr = ByteArray(cols * rows) { (150 - (phase and 1) * 20).toByte() }
                val detail = ByteArray(cols * rows * 16) { i ->
                    val x = i % (cols * 4)
                    val block = i / (cols * 4) / 4 * cols + x / 4
                    (y[block].toUByte().toInt() + if (block == moving && x % 2 == 0) 30 else 0).toByte()
                }
                val frame = AvcReferenceFrame(width, height, y, cb, cr, detail)
                val pos = n % 20 // exercises frame_num wrap between IDRs
                val packet = encoder.buildH264Picture(frame, last.takeIf { pos != 0 }, n, pos)
                assertEquals(if (pos == 0) 5 else 1, packet[4].toInt() and 31)
                if (pos != 0 && n % 3 != 0) assertTrue(packet.size <= 12, "identical frame encoded as ${packet.size} bytes")
                stream.write(packet)
                images += frame
                last = frame
            }
        }
        decode(input, output, dir.resolve("gop.log"), "yuv420p")
        val raw = Files.readAllBytes(output)
        val area = width * height
        val frameSize = area * 3 / 2
        assertEquals(images.size * frameSize, raw.size)
        for ((index, image) in images.withIndex()) {
            val offset = index * frameSize
            if (index % 20 != 0 && index % 3 != 0) {
                assertTrue(java.util.Arrays.equals(raw, offset - frameSize, offset, raw, offset, offset + frameSize))
            }
            for (y in 2 until height step 4) for (x in 2 until width step 4) {
                val expected = requireNotNull(image.detail)[(y / 4) * cols * 4 + x / 4].toUByte().toInt()
                val decoded = raw[offset + y * width + x].toUByte().toInt()
                assertTrue(abs(expected - decoded) <= 5, "frame=$index ($x,$y) wanted=$expected got=$decoded")
            }
            assertTrue(abs(raw[offset + area].toUByte().toInt() - requireNotNull(image.cb)[0].toUByte().toInt()) <= 2)
            assertTrue(abs(raw[offset + area * 5 / 4].toUByte().toInt() - requireNotNull(image.cr)[0].toUByte().toInt()) <= 2)
        }
    }

    @Test
    fun decodedFlatBlocksPreserveLumaAndChromaExtremes(@TempDir dir: Path) {
        val samples = listOf(0, 16, 64, 128, 192, 235, 255)
        val input = dir.resolve("blocks.h264")
        val output = dir.resolve("decoded.yuv")
        val errors = dir.resolve("ffmpeg.log")
        val encoder = MaliV61BitstreamEncoder
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(16, 16, 30L shl 16))
            stream.write(encoder.buildH264Pps())
            samples.forEachIndexed { index, y ->
                stream.write(encoder.buildH264IdrSlice(
                    16, 16, index, byteArrayOf(y.toByte()),
                    byteArrayOf((255 - y).toByte()), byteArrayOf(y.toByte()),
                ))
            }
        }
        decode(input, output, errors, "yuv420p")
        val bytes = Files.readAllBytes(output)
        assertEquals(384 * samples.size, bytes.size)
        samples.forEachIndexed { frame, y ->
            for (offset in 0 until 384) {
                val expected = if (offset in 256 until 320) 255 - y else y
                val actual = bytes[frame * 384 + offset].toInt() and 255
                assertTrue(abs(expected - actual) <= 1, "frame=$frame offset=$offset expected=$expected actual=$actual")
            }
        }
    }
    @Test
    fun avcPreservesContrastWithinOneMacroblock(@TempDir dir: Path) {
        val input = dir.resolve("detail.h264")
        val output = dir.resolve("detail.yuv")
        val encoder = MaliV61BitstreamEncoder
        val width = 34
        val height = 18
        val blockCols = (width + 15) / 16
        val blockRows = (height + 15) / 16
        val centers = ByteArray(blockCols * blockRows) { 128.toByte() }
        val detail = ByteArray(centers.size * 16) { i ->
            if (i % (blockCols * 4) < 4) 128.toByte() else if (i % 2 == 0) 48 else 204.toByte()
        }
        Files.newOutputStream(input).use { stream ->
            stream.write(encoder.buildH264Sps(width, height, 25L shl 16))
            stream.write(encoder.buildH264Pps())
            stream.write(encoder.buildH264IdrSlice(width, height, 0, centers, centers, centers, detail))
        }
        decode(input, output, dir.resolve("avc.log"), "yuv420p")
        val pixels = Files.readAllBytes(output)
        assertEquals(width * height * 3 / 2, pixels.size)
        for (y in listOf(2, 14, 17)) {
            for (x in listOf(2, 14, 18, 22, 26, 30, 33)) {
                val expected = detail[(y / 4) * blockCols * 4 + x / 4].toInt() and 255
                val actual = pixels[y * width + x].toInt() and 255
                assertTrue(abs(actual - expected) <= 5, "x=$x y=$y decoded=$actual source=$expected")
            }
        }
    }

    @Test
    fun hevcReconstructsChangingPixelsAtBothBitDepths(@TempDir dir: Path) {
        val width = 34
        val height = 18
        val blocks = ((width + 15) / 16) * ((height + 15) / 16)
        val targets = listOf(Triple(16, 128, 128), Triple(235, 128, 128), Triple(58, 76, 158))
        val encoder = MaliV61BitstreamEncoder
        for (ten in listOf(false, true)) {
            val input = dir.resolve("blocks-$ten.hevc")
            val output = dir.resolve("blocks-$ten.yuv")
            Files.newOutputStream(input).use { stream ->
                stream.write(encoder.buildH265Vps(ten))
                val sps = encoder.buildH265Sps(width, height, ten, 1_964_114L)
                assertTrue(sps.size <= 64, "SPS exceeds the firmware header slot")
                stream.write(sps)
                stream.write(encoder.buildH265Pps())
                for ((y, cb, cr) in targets) {
                    stream.write(encoder.buildH265IdrSlice(width, height,
                        ByteArray(blocks) { y.toByte() }, ByteArray(blocks) { cb.toByte() }, ByteArray(blocks) { cr.toByte() }))
                }
            }
            decode(input, output, dir.resolve("hevc-$ten.log"), if (ten) "yuv420p10le" else "yuv420p")
            val bytes = Files.readAllBytes(output)
            val samples = width * height * 3 / 2
            assertEquals(targets.size * samples * (if (ten) 2 else 1), bytes.size)
            targets.forEachIndexed { frame, (y, cb, cr) ->
                for (i in 0 until samples) {
                    val index = frame * samples + i
                    val actual = if (ten) {
                        ((bytes[index * 2].toInt() and 255) or ((bytes[index * 2 + 1].toInt() and 255) shl 8))
                    } else bytes[index].toInt() and 255
                    val expected = when {
                        i < width * height -> y
                        i < width * height * 5 / 4 -> cb
                        else -> cr
                    } * (if (ten) 4 else 1)
                    assertTrue(abs(actual - expected) <= (if (ten) 4 else 1),
                        "frame=$frame sample=$i ten=$ten expected=$expected actual=$actual")
                }
            }
        }
    }

    @Test
    fun hevcRetainsFourPixelHorizontalVerticalAndDiagonalContrast(@TempDir dir: Path) {
        val width = 192
        val height = 128
        val blockCols = width / 16
        val blocks = blockCols * (height / 16)
        val encoder = MaliV61BitstreamEncoder
        for (ten in listOf(false, true)) {
            val input = dir.resolve("detail-$ten.hevc")
            val sourceY = ByteArray(blocks) { 65 }
            val cb = ByteArray(blocks) { 76 }
            val cr = ByteArray(blocks) { 160.toByte() }
            fun light(pattern: Int, x: Int, y: Int): Boolean = when (pattern) {
                0 -> (x and 1) != 0
                1 -> (y and 1) != 0
                else -> ((x + y) and 1) != 0
            }
            Files.newOutputStream(input).use { stream ->
                stream.write(encoder.buildH265Vps(ten))
                stream.write(encoder.buildH265Sps(width, height, ten))
                stream.write(encoder.buildH265Pps())
                for (pattern in 0..2) {
                    val fine = ByteArray(blocks * 16) { index ->
                        if (light(pattern, index % (width / 4), index / (width / 4))) 85 else 45
                    }
                    stream.write(encoder.buildH265IdrSlice(width, height, sourceY, cb, cr, fine, ten))
                }
            }
            val output = dir.resolve("detail-$ten.yuv")
            decode(input, output, dir.resolve("detail-$ten.log"), if (ten) "yuv420p10le" else "yuv420p")
            val bytes = Files.readAllBytes(output)
            val frameSamples = width * height * 3 / 2
            assertEquals(frameSamples * 3 * (if (ten) 2 else 1), bytes.size)
            fun sample(offset: Int): Double = if (ten) {
                ((bytes[2 * offset].toInt() and 255) + ((bytes[2 * offset + 1].toInt() and 255) shl 8)) / 4.0
            } else (bytes[offset].toInt() and 255).toDouble()
            for (pattern in 0..2) {
                val frame = pattern * frameSamples
                var dark = 0.0
                var bright = 0.0
                for (y in 0 until height) for (x in 0 until width) {
                    if (light(pattern, x / 4, y / 4)) bright += sample(frame + y * width + x)
                    else dark += sample(frame + y * width + x)
                }
                val half = width * height / 2
                assertTrue(abs(dark / half - 45.0) < 5.0 && abs(bright / half - 85.0) < 5.0,
                    "ten=$ten pattern=$pattern dark=${dark / half} bright=${bright / half}")
                assertTrue(abs(sample(frame + width * height) - 76.0) <= 2.0)
                assertTrue(abs(sample(frame + frameSamples - 1) - 160.0) <= 2.0)
            }
        }
    }

    @Test
    fun hevcLongGopPreservesStaticTilesAndUpdatesMovingTexture(@TempDir dir: Path) {
        val encoder = MaliV61BitstreamEncoder
        val width = 94
        val height = 46
        val cols = (width + 15) / 16
        val blocks = cols * ((height + 15) / 16)
        for (ten in listOf(false, true)) {
            val deltaPath = dir.resolve("gop-$ten.hevc")
            val intraPath = dir.resolve("all-intra-$ten.hevc")
            Files.newOutputStream(deltaPath).use { delta ->
                Files.newOutputStream(intraPath).use { intra ->
                    for (stream in listOf(delta, intra)) {
                        stream.write(encoder.buildH265Vps(ten))
                        stream.write(encoder.buildH265Sps(width, height, ten, 1_964_114L))
                        stream.write(encoder.buildH265Pps())
                    }
                    var reference: HevcReferenceFrame? = null
                    repeat(32) { number ->
                        val position = (number / 2) % blocks
                        val y = ByteArray(blocks) { if (it == position) 160.toByte() else 80 }
                        val cb = ByteArray(blocks) { if (it == position) 170.toByte() else 90 }
                        val cr = ByteArray(blocks) { if (it == position) 60 else 140.toByte() }
                        val fine = ByteArray(blocks * 16) { i ->
                            val x = i % (cols * 4)
                            val row = i / (cols * 4)
                            val block = row / 4 * cols + x / 4
                            ((y[block].toInt() and 255) + if ((x + row) % 2 == 0) 15 else -15).toByte()
                        }
                        val frame = HevcReferenceFrame(width, height, y, cb, cr, fine, ten)
                        val key = number % 15 == 0
                        val slice = encoder.buildH265Picture(frame, if (key) null else reference, number % 15)
                        val independent = encoder.buildH265IdrSlice(width, height, y, cb, cr, fine, ten)
                        assertEquals(if (key) 19 else 1, (slice[4].toInt() and 126) shr 1)
                        if (!key && number % 2 == 1) assertTrue(slice.size * 4 < independent.size)
                        delta.write(slice)
                        intra.write(independent)
                        reference = frame
                    }
                }
            }
            val format = if (ten) "yuv420p10le" else "yuv420p"
            val decoded = dir.resolve("gop-$ten.yuv")
            val expected = dir.resolve("all-intra-$ten.yuv")
            decode(deltaPath, decoded, dir.resolve("gop-$ten.log"), format)
            decode(intraPath, expected, dir.resolve("all-intra-$ten.log"), format)
            val raw = Files.readAllBytes(decoded)
            val baseline = Files.readAllBytes(expected)
            val frameBytes = width * height * 3 / 2 * (if (ten) 2 else 1)
            assertEquals(32 * frameBytes, raw.size)
            assertEquals(raw.size, baseline.size)
            for (number in 0 until 32) {
                val start = number * frameBytes
                if (number % 2 == 1 && number % 15 != 0) {
                    assertTrue(java.util.Arrays.equals(raw, start - frameBytes, start, raw, start, start + frameBytes),
                        "Unchanged picture $number must exactly match its reference")
                }
                fun sample(bytes: ByteArray, i: Int) = if (ten) {
                    ((bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8)) / 4.0
                } else (bytes[i].toInt() and 255).toDouble()
                var difference = 0.0
                for (i in start until start + frameBytes step (if (ten) 2 else 1)) {
                    difference += abs(sample(raw, i) - sample(baseline, i))
                }
                assertTrue(difference / (width * height * 1.5) < 1.5,
                    "Decoded P picture $number differs from independently encoded intra picture (sum=$difference)")
            }
        }
    }

    @Test
    fun hevcAllIntraSpendsBitrateOnOriginalPixels(@TempDir dir: Path) {
        val cols = 12
        val rows = 6
        val width = 16 * cols
        val height = 16 * rows
        val encoder = MaliV61BitstreamEncoder
        for (ten in listOf(false, true)) {
            val targets = listOf(0, 3_000, 12_000, 22_000)
            val exactBlocks = targets.map { BooleanArray(cols * rows) }
            val input = dir.resolve("pcm-$ten.hevc")
            fun sourceValue(block: Int, sample: Int) =
                (if ((block + sample) % 2 == 0) 384 else 640) + (sample + block) % 17 - 8
            Files.newOutputStream(input).use { stream ->
                stream.write(encoder.buildH265Vps(ten))
                val sps = encoder.buildH265Sps(width, height, ten, pcmEnabled = true)
                assertTrue(sps.size <= 64)
                stream.write(sps)
                stream.write(encoder.buildH265Pps())
                targets.forEachIndexed { n, target ->
                    val frame = HevcReferenceFrame(width, height, null, null, null, null, ten, keepReference = false)
                    val source = MvePcmSource { x, y, pixels ->
                        val block = y * cols + x
                        if (block % 11 == 0) false else {
                            for (i in pixels.indices) pixels[i] = sourceValue(block, i).toShort()
                            exactBlocks[n][block] = true
                            true
                        }
                    }
                    val slice = encoder.buildH265Picture(frame, null, 0, source, target)
                    if (target > 0) {
                        assertTrue(slice.size in target - 520..target + 16, "target=$target bytes=${slice.size}")
                        assertTrue(exactBlocks[n].count { it } > exactBlocks[n - 1].count { it })
                    }
                    stream.write(slice)
                }
            }
            val output = dir.resolve("pcm-$ten.yuv")
            decode(input, output, dir.resolve("pcm-$ten.log"), if (ten) "yuv420p10le" else "yuv420p")
            val raw = Files.readAllBytes(output)
            val samples = width * height * 3 / 2
            assertEquals(targets.size * samples * (if (ten) 2 else 1), raw.size)
            var previousError = Double.POSITIVE_INFINITY
            for (n in targets.indices) {
                var totalError = 0L
                for (block in 0 until cols * rows) for (i in 0 until 384) {
                    val chroma = i >= 256
                    val side = if (chroma) 8 else 16
                    val planeStart = if (!chroma) 0 else if (i < 320) width * height else width * height * 5 / 4
                    val within = if (chroma) (i - 256) % 64 else i
                    val x = block % cols * side + within % side
                    val y = block / cols * side + within / side
                    val offset = n * samples + planeStart + y * (if (chroma) width / 2 else width) + x
                    val actual = if (ten) (raw[offset * 2].toInt() and 255) or
                        ((raw[offset * 2 + 1].toInt() and 255) shl 8) else raw[offset].toInt() and 255
                    val expected = sourceValue(block, i) shr (if (ten) 0 else 2)
                    val error = abs(actual - expected)
                    if (exactBlocks[n][block]) assertEquals(0, error, "PCM n=$n block=$block sample=$i")
                    totalError += error
                }
                val meanError = totalError.toDouble() / samples
                assertTrue(meanError < previousError, "target=${targets[n]} error=$meanError previous=$previousError")
                previousError = meanError
            }
        }
    }

    @Test
    fun hevcLongGopRechecksOriginalPixelsBeforeSkip(@TempDir dir: Path) {
        val encoder = MaliV61BitstreamEncoder
        val width = 64
        val height = 32
        for (ten in listOf(false, true)) {
            val input = dir.resolve("predictive-pcm-$ten.hevc")
            val sizes = mutableListOf<Int>()
            val delta = if (ten) 1 else 4
            fun original(i: Int) = if (i % 2 == 0) 425 else 599
            Files.newOutputStream(input).use { stream ->
                stream.write(encoder.buildH265Vps(ten))
                stream.write(encoder.buildH265Sps(width, height, ten, pcmEnabled = true))
                stream.write(encoder.buildH265Pps())
                var previous: HevcReferenceFrame? = null
                for (step in 0..5) {
                    val frame = HevcReferenceFrame(width, height, null, null, null, null, ten)
                    val source = MvePcmSource { x, y, samples ->
                        for (i in samples.indices) samples[i] = original(i).toShort()
                        if (x == 1 && y == 0 && step >= 2) {
                            // Only original luma and chroma change. All coarse samples remain identical.
                            samples[37] = (original(37) + delta).toShort()
                            samples[268] = (original(268) + delta).toShort()
                            samples[331] = (original(331) - delta).toShort()
                        }
                        !(x == 1 && y == 0 && step == 4)
                    }
                    val slice = encoder.buildH265Picture(frame, previous, step, source, 100_000, constantRate = false)
                    stream.write(slice)
                    sizes.add(slice.size)
                    previous = frame
                }
            }
            val output = dir.resolve("predictive-pcm-$ten.yuv")
            decode(input, output, dir.resolve("predictive-pcm-$ten.log"), if (ten) "yuv420p10le" else "yuv420p")
            val raw = Files.readAllBytes(output)
            val frameSize = width * height * 3 / 2
            assertEquals(6 * frameSize * (if (ten) 2 else 1), raw.size)
            for (step in 0..5) {
                for (block in 0 until 8) for (i in 0 until 384) {
                    if (step == 4 && block == 1) continue // failed DMA read uses the regular intra predictor
                    val side = if (i < 256) 16 else 8
                    val stride = if (i < 256) width else width / 2
                    val plane = if (i < 256) 0 else if (i < 320) width * height else width * height * 5 / 4
                    val at = if (i < 256) i else (i - 256) % 64
                    val offset = step * frameSize + plane + (block / 4 * side + at / side) * stride +
                        block % 4 * side + at % side
                    val decoded = if (ten) (raw[offset * 2].toInt() and 255) or
                        ((raw[offset * 2 + 1].toInt() and 255) shl 8) else raw[offset].toInt() and 255
                    val change = if (step >= 2 && block == 1) when (i) {
                        37, 268 -> delta
                        331 -> -delta
                        else -> 0
                    } else 0
                    assertEquals((original(i) + change) shr if (ten) 0 else 2, decoded,
                        "step=$step block=$block index=$i")
                }
            }
            assertTrue(sizes[1] < sizes[2] && sizes[2] < sizes[0])
            assertTrue(sizes[3] < sizes[2] && sizes[4] < sizes[5])
        }
    }

    private fun decode(input: Path, output: Path, errors: Path, format: String) {
        val explicit = System.getenv("FUJI_FFMPEG")
        val decoder = try {
            ProcessBuilder(explicit ?: "ffmpeg", "-v", "error", "-xerror", "-i", input.toString(),
                "-vsync", "0", "-pix_fmt", format, "-f", "rawvideo", output.toString())
                .redirectError(errors.toFile()).start()
        } catch (e: IOException) {
            if (explicit != null) throw e
            assumeTrue(false, "FFmpeg unavailable: ${e.message}")
            return
        }
        try {
            assertTrue(decoder.waitFor(30, TimeUnit.SECONDS), "FFmpeg decode timed out")
            assertEquals(0, decoder.exitValue(), Files.readString(errors))
        } finally {
            if (decoder.isAlive) decoder.destroyForcibly()
        }
    }
}
