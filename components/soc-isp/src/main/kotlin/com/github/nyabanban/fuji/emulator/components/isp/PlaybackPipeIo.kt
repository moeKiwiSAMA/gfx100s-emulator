package com.github.nyabanban.fuji.emulator.components.isp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

// OpenJDK process streams use a native heap buffer for requests larger than 8 KiB.
private const val PIPE_BLOCK_BYTES = 8 * 1024

internal fun InputStream.readPicturePixels(pixels: ByteArray) {
    var offset = 0
    while (offset < pixels.size) {
        val count = read(pixels, offset, minOf(PIPE_BLOCK_BYTES, pixels.size - offset))
        if (count <= 0) throw EOFException("Truncated YUV420 picture")
        offset += count
    }
}

internal fun OutputStream.writeBitstreamPacket(packet: ByteArray) {
    var offset = 0
    while (offset < packet.size) {
        val count = minOf(PIPE_BLOCK_BYTES, packet.size - offset)
        write(packet, offset, count)
        offset += count
    }
}
