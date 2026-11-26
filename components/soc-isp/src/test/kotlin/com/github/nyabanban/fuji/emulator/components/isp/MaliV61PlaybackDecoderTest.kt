package com.github.nyabanban.fuji.emulator.components.isp

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaliV61PlaybackDecoderTest {
    @Test
    fun rejectsInvalidLengthsAndClosedSessions() {
        MaliV61PlaybackDecoder().use { decoder ->
            for (input in listOf(byteArrayOf(), byteArrayOf(4, 0, 0, 0, 0x67), byteArrayOf(-1, -1, -1, -1, 0x67))) {
                assertFalse(decoder.submit(input))
            }
            decoder.close()
            assertFalse(decoder.submit(byteArrayOf(1, 0, 0, 0, 0x67)))
        }
    }

    @Test
    fun avcAndHevcKeepReferencesAcrossFirmwareRequests(@TempDir dir: Path) {
        val executable = System.getProperty("fuji.ffmpeg.bin") ?: System.getenv("FUJI_FFMPEG_BIN")
            ?: System.getenv("FUJI_FFMPEG") ?: "ffmpeg"
        val available = try {
            ProcessBuilder(executable, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                .waitFor(10, TimeUnit.SECONDS)
        } catch (ex: IOException) {
            if (executable != "ffmpeg") throw ex
            false
        }
        assumeTrue(available, "FFmpeg is required for decoder integration")
        fun ffmpeg(vararg args: String) {
            val log = dir.resolve("ffmpeg.log")
            val process = ProcessBuilder(listOf(executable, "-v", "error", "-y") + args)
                .redirectError(log.toFile()).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), Files.readString(log))
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        for ((codec, bFrames) in listOf("h264" to 0, "hevc" to 0, "h264" to 2, "hevc" to 2)) {
            val tenBit = codec == "hevc" && bFrames > 0
            val pixelFormat = if (tenBit) "yuv420p10le" else "yuv420p"
            val stream = dir.resolve("sequence.$codec")
            val encoder = if (codec == "h264") "libx264" else "libx265"
            val params = if (codec == "h264") "threads=1" else "pools=1:frame-threads=1:log-level=error"
            ffmpeg("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=5", "-frames:v", "5", "-c:v", encoder,
                if (codec == "h264") "-x264-params" else "-x265-params", "$params:bframes=$bFrames:b-adapt=0:keyint=10",
                "-pix_fmt", pixelFormat,
                "-f", codec, stream.toString())
            val reference = dir.resolve("reference.yuv")
            ffmpeg("-i", stream.toString(), "-pix_fmt", pixelFormat, "-f", "rawvideo", reference.toString())
            Files.newInputStream(reference).use { expected ->
                MaliV61PlaybackDecoder().use { decoder ->
                    var decoded = 0
                    var interPictures = 0
                    fun checkNextFrame() {
                        val size = 96 * 64 * 3 / 2
                        if (tenBit) {
                            val frame = assertNotNull(decoder.takeNativeFrame(96, 64), decoder.lastError)
                            assertEquals(10, frame.bitDepth)
                            assertContentEquals(expected.readNBytes(size * 2), frame.pixels)
                        } else {
                            val pixels = assertNotNull(decoder.takeFrame(96, 64), decoder.lastError)
                            assertContentEquals(expected.readNBytes(size), pixels)
                        }
                        decoded++
                    }
                    for (nal in annexBNals(Files.readAllBytes(stream))) {
                        val type = if (codec == "h264") nal[0].toInt() and 31 else (nal[0].toInt() ushr 1) and 63
                        val packet = ByteBuffer.allocate(nal.size + 4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(nal.size).put(nal).array()
                        assertTrue(decoder.submit(packet), "$codec type=$type error=${decoder.lastError}")
                        if (if (codec == "h264") type in 1..5 else type < 32) {
                            if (bFrames == 0) checkNextFrame()
                            if (if (codec == "h264") type == 1 else type in 0..9) interPictures++
                        }
                    }
                    assertTrue(decoder.finishInput())
                    if (bFrames > 0) repeat(5) { checkNextFrame() }
                    assertEquals(5, decoded, codec)
                    assertTrue(interPictures > 0, codec)
                    assertEquals(-1, expected.read())
                }
            }
        }
    }

    private fun annexBNals(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = -1
        var zeros = 0
        bytes.forEachIndexed { i, value ->
            if (value == 1.toByte() && zeros >= 2) {
                if (start >= 0) result += bytes.copyOfRange(start, i - zeros)
                start = i + 1
            }
            zeros = if (value == 0.toByte()) zeros + 1 else 0
        }
        if (start >= 0) result += bytes.copyOfRange(start, bytes.size)
        return result
    }
}
