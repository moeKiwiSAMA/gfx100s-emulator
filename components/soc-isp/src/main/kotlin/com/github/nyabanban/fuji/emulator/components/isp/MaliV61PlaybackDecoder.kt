package com.github.nyabanban.fuji.emulator.components.isp

import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One host decoder per MVE session. Packet boundaries come from the firmware bitstream descriptors. */
internal class MaliV61PlaybackDecoder : AutoCloseable {
    private enum class Codec(val demuxer: String, val delimiter: ByteArray, val endSequence: ByteArray) {
        AVC("h264", byteArrayOf(0, 0, 0, 1, 0x09, 0xf0.toByte()), byteArrayOf(0, 0, 0, 1, 0x0a, 0x80.toByte())),
        HEVC("hevc", byteArrayOf(0, 0, 0, 1, 0x46, 1, 0x50), byteArrayOf(0, 0, 0, 1, 0x48, 1, 0x80.toByte())),
    }

    private enum class PictureOrder { UNKNOWN, DECODE_ORDER, MAY_REORDER }

    private data class Packet(val bytes: ByteArray, val picture: Boolean)
    data class Frame(val width: Int, val height: Int, val bitDepth: Int, val pixels: ByteArray)

    private var codec: Codec? = null
    private var process: Process? = null
    private val input = ArrayBlockingQueue<Packet>(4)
    private val output = ArrayBlockingQueue<Frame>(2)
    private var writer: Thread? = null
    private var reader: Thread? = null
    var acceptedPictures = 0L
        private set
    private var outstanding = 0
    @Volatile private var announcedPictures = 0L
    private var consumedPictures = 0L
    private var pictureOrder = PictureOrder.UNKNOWN
    private var waitForPictureStarted = 0L
    @Volatile private var ending = false
    @Volatile private var drained = false

    @Volatile private var closed = false
    @Volatile var lastError: String? = null
        private set

    /**
     * GFX100S MVE option 1, value 16 uses four-byte little-endian NAL lengths.
     * Validate the entire descriptor before transferring any bytes to the decoder.
     */
    fun submit(lengthPrefixedLittleEndian: ByteArray): Boolean {
        if (closed || ending || lastError != null || lengthPrefixedLittleEndian.size !in 5..MAX_PACKET_BYTES) return false
        val bytes = lengthPrefixedLittleEndian.copyOf()
        var offset = 0
        var stream = codec
        var order = pictureOrder
        var picture = false
        while (offset < bytes.size) {
            if (bytes.size - offset < 5) return false
            val length = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
            if (length !in 1..(bytes.size - offset - 4)) return false
            val header = bytes[offset + 4].toInt() and 255
            if (header and 0x80 != 0) return false
            if (stream == null) {
                stream = when {
                    header and 31 == 7 -> Codec.AVC
                    header ushr 1 in 32..35 || header ushr 1 == 39 -> Codec.HEVC
                    header and 31 in 6..9 -> Codec.AVC
                    else -> return false
                }
            }
            if (stream == Codec.AVC && header and 31 == 7 && order != PictureOrder.MAY_REORDER) {
                order = if (isDecodeOrderAvcSps(bytes, offset + 4, length)) {
                    PictureOrder.DECODE_ORDER
                } else {
                    PictureOrder.MAY_REORDER
                }
            }
            if (stream == Codec.HEVC && (length < 2 || bytes[offset + 5].toInt() and 7 == 0)) return false
            picture = picture || if (stream == Codec.AVC) header and 31 in 1..5 else header ushr 1 < 32
            bytes[offset] = 0
            bytes[offset + 1] = 0
            bytes[offset + 2] = 0
            bytes[offset + 3] = 1
            offset += 4 + length
        }
        val selected = stream ?: return false
        if (process == null && !start(selected)) return false
        if (!input.offer(Packet(bytes, picture), 2, TimeUnit.SECONDS)) return false
        pictureOrder = order
        if (picture) {
            outstanding++
            acceptedPictures++
        }
        return true
    }

    /** Close stdin at an MVE end-of-stream marker, allowing delayed B pictures to reach the display queue. */
    fun finishInput(): Boolean {
        if (closed || process == null || lastError != null) return false
        if (ending) return true
        ending = true
        // Wake an idle writer. A full queue already has work and the writer checks ending after each packet.
        input.offer(END)
        return true
    }

    /** Returns a display-order frame with contiguous Y, U, V planes. The codec owns its reference pictures. */
    fun takeFrame(width: Int, height: Int): ByteArray? = takeFrame(width, height, 2_000L)

    fun takeFrame(width: Int, height: Int, waitMillis: Long): ByteArray? {
        val frame = takeNativeFrame(width, height, waitMillis) ?: return null
        if (frame.bitDepth == 8) return frame.pixels
        return ByteArray(frame.pixels.size / 2) { index ->
            val offset = index * 2
            val sample = (frame.pixels[offset].toInt() and 255) or ((frame.pixels[offset + 1].toInt() and 3) shl 8)
            ((sample + 2) ushr 2).coerceAtMost(255).toByte()
        }
    }

    /** Poll for announced pixels or a picture in a constrained low-delay AVC sequence. */
    fun takeAnnouncedFrame(width: Int, height: Int): Frame? {
        val waitMillis = when {
            announcedPictures > consumedPictures -> PIXEL_TRANSFER_WAIT_MILLIS
            pictureOrder == PictureOrder.DECODE_ORDER && outstanding > 0 -> {
                val now = System.nanoTime()
                if (waitForPictureStarted == 0L) waitForPictureStarted = now
                // Yield often so firmware can pause or cancel. Fall back to guest polling if the child stalls.
                if (now - waitForPictureStarted < LOW_DELAY_HOST_BUDGET_NANOS) LOW_DELAY_POLL_MILLIS else 0L
            }
            else -> 0L
        }
        return takeNativeFrame(width, height, waitMillis)
    }

    /** Ten-bit planes store one low-aligned little-endian 16-bit sample per pixel. */
    fun takeNativeFrame(width: Int, height: Int, waitMillis: Long = 2_000L): Frame? {
        if (closed || outstanding == 0) return null
        val frame = output.poll() ?: if (waitMillis > 0 && !drained && lastError == null) {
            output.poll(waitMillis, TimeUnit.MILLISECONDS)
        } else null
        if (frame == null) {
            // All queued frames are visible before the output worker publishes EOF.
            if (ending && drained && output.isEmpty() && lastError == null) {
                lastError = "FFmpeg ended before the pending picture"
            }
            return null
        }
        waitForPictureStarted = 0L
        consumedPictures++
        outstanding--
        if (frame.width != width || frame.height != height) {
            lastError = "Decoded ${frame.width}x${frame.height} for ${width}x${height} surface"
            return null
        }
        return frame
    }

    private fun bundledExecutable(): String? {
        val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "ffmpeg.exe" else "ffmpeg"
        val jar = javaClass.protectionDomain?.codeSource?.location?.takeIf { it.protocol == "file" }
            ?.let { Path.of(it.toURI()) }
        // The component loader opens runtime/compoments/soc-isp-*.jar directly from the selected emulator home.
        val media = jar?.parent?.takeIf { it.fileName?.toString() == "compoments" }?.parent?.resolve("media")
        return media?.resolve(name)?.takeIf(Files::isRegularFile)?.toAbsolutePath()?.toString()
    }

    private fun start(stream: Codec): Boolean {
        val executable = System.getProperty("fuji.ffmpeg.bin")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_FFMPEG_BIN")?.takeIf { it.isNotBlank() }
            ?: bundledExecutable()
            ?: System.getenv("FUJI_FFMPEG")?.takeIf { it.isNotBlank() }
            ?: "ffmpeg"
        val child = try {
            ProcessBuilder(
                executable, "-nostdin", "-v", "error", "-xerror", "-probesize", "32", "-analyzeduration", "0",
                "-fpsprobesize", "0", "-f", stream.demuxer, "-threads", "1", "-i", "pipe:0",
                "-map", "0:v:0", "-threads", "1", "-vf", "format=pix_fmts=yuv420p|yuv420p10le",
                "-strict", "unofficial", "-fps_mode", "passthrough",
                "-flush_packets", "1", "-f", "yuv4mpegpipe", "pipe:1",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: IOException) {
            lastError = e.message ?: "Unable to start FFmpeg"
            return false
        }
        process = child
        codec = stream
        writer = worker("input", child) {
            child.outputStream.buffered().use { sink ->
                while (!closed) {
                    val packet = input.take()
                    if (packet === END) break
                    sink.writeBitstreamPacket(packet.bytes)
                    // Terminate the access unit so the elementary-stream parser can emit it before the next request.
                    if (packet.picture) sink.write(stream.delimiter)
                    sink.flush()
                    if (ending && input.isEmpty()) break
                }
                if (!closed) sink.write(stream.endSequence)
            }
        }
        reader = worker("output", child) {
            child.inputStream.buffered().use { source ->
                val header = source.readHeader()?.split(' ') ?: throw IOException("FFmpeg omitted the video header")
                require(header.first() == "YUV4MPEG2")
                val bitDepth = when (header.single { it.startsWith("C") }) {
                    "C420", "C420jpeg", "C420mpeg2", "C420paldv" -> 8
                    "C420p10" -> 10
                    else -> throw IOException("Unsupported decoder output pixel format")
                }
                val width = header.single { it.startsWith("W") }.drop(1).toInt()
                val height = header.single { it.startsWith("H") }.drop(1).toInt()
                require(width in 1..4096 && height in 1..2160)
                val samplesPerFrame = width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2)
                val bytesPerFrame = samplesPerFrame * ((bitDepth + 7) / 8)
                while (!closed) {
                    val frameHeader = source.readHeader()
                    if (frameHeader == null) {
                        if (!ending) throw IOException("FFmpeg stopped before end of stream")
                        drained = true
                        break
                    }
                    require(frameHeader.substringBefore(' ') == "FRAME")
                    announcedPictures++
                    val pixels = ByteArray(bytesPerFrame)
                    source.readPicturePixels(pixels)
                    output.put(Frame(width, height, bitDepth, pixels))
                }
            }
        }
        return true
    }

    private fun worker(name: String, child: Process, block: () -> Unit): Thread =
        Thread.ofVirtual().name("mali-v61-decoder-$name").start {
            try {
                block()
            } catch (failure: Throwable) {
                if (!closed) {
                    synchronized(this) {
                        if (lastError == null) lastError = failure.message ?: failure.javaClass.simpleName
                    }
                    child.destroyForcibly()
                }
                if (failure !is Exception) throw failure
            }
        }

    private fun BufferedInputStream.readHeader(): String? {
        val text = StringBuilder()
        while (text.length < 512) {
            val next = read()
            if (next == -1 && text.isEmpty()) return null
            if (next == 10) return text.toString()
            if (next !in 32..126) throw IOException("Invalid or missing YUV4MPEG header")
            text.append(next.toChar())
        }
        throw IOException("Oversized YUV4MPEG header")
    }

    fun abortDrain() {
        lastError = "Decoder did not drain before the MVE picture deadline"
        close()
    }

    override fun close() {
        closed = true
        process?.destroyForcibly()
        reader?.interrupt()
        writer?.interrupt()
        input.clear()
        output.clear()
        outstanding = 0
    }

    private companion object {
        val END = Packet(byteArrayOf(), false)
        // Cap an owner turn while the reader transfers the planar payload through the host pipe.
        const val PIXEL_TRANSFER_WAIT_MILLIS = 16L
        const val LOW_DELAY_POLL_MILLIS = 4L
        val LOW_DELAY_HOST_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(500)
        const val MAX_PACKET_BYTES = 16 * 1024 * 1024
    }
}

/** Constrained Baseline without B slices, using decoding order for picture order counts (H.264 8.2.1.3). */
internal fun isDecodeOrderAvcSps(nal: ByteArray, offset: Int, length: Int): Boolean {
    if (length < 5 || offset < 0 || offset > nal.size - length) return false
    if (nal[offset].toInt() and 31 != 7 || nal[offset + 1].toInt() != 66 || nal[offset + 2].toInt() and 0xc0 != 0xc0) return false

    // For Baseline these are the first three Exp-Golomb fields after level_idc. Their legal lengths
    // are at most 11, 7 and 3 bits, so an emulation-prevention sequence cannot occur between them.
    val byteCount = minOf(3, length - 4)
    var bits = 0
    for (i in 0 until byteCount) bits = bits or ((nal[offset + 4 + i].toInt() and 255) shl (24 - 8 * i))
    var available = byteCount * 8
    fun readUnsignedGolomb(): Int {
        val zeros = Integer.numberOfLeadingZeros(bits)
        val count = 2 * zeros + 1
        if (count > available) return -1
        val value = (bits ushr (32 - count)) - 1
        bits = bits shl count
        available -= count
        return value
    }
    return readUnsignedGolomb() in 0..31 && readUnsignedGolomb() in 0..12 && readUnsignedGolomb() == 2
}
