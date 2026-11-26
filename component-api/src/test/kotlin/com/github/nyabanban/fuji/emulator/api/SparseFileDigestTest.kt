package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SparseFileDigestTest {
    private fun naiveChannelSha256(channel: FileChannel, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteBuffer.allocate(1024 * 1024)
        var pos = 0L
        while (pos < size) {
            val chunk = minOf(buf.capacity().toLong(), size - pos).toInt()
            buf.clear()
            buf.limit(chunk)
            while (buf.hasRemaining()) {
                val n = channel.read(buf, pos + buf.position())
                check(n > 0)
            }
            buf.flip()
            digest.update(buf)
            pos += chunk
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    @Test fun sparseFileWithHolesMatchesFullChannelSha256AndInvalidatesOnWrite() {
        val dir = Files.createTempDirectory("fuji-sparse-digest")
        try {
            val path = dir.resolve("sparse-sd.img")
            val totalSize = 18L * 1024 * 1024 + 512L
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
                val head = ByteArray(4096) { (it * 31 + 7).toByte() }
                val mid = ByteArray(1024 * 1024) { ((it ushr 3) xor 0x5a).toByte() }
                val tail = ByteArray(512) { (it + 0x11).toByte() }
                ch.write(ByteBuffer.wrap(head), 0L)
                ch.write(ByteBuffer.wrap(mid), 8L * 1024 * 1024 + 123L)
                ch.write(ByteBuffer.wrap(tail), totalSize - 512L)
            }
            val expectedBefore = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                naiveChannelSha256(ch, totalSize)
            }
            val actualBefore = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                SparseFileDigest.cachedOrCompute(path, ch, totalSize)
            }
            assertEquals(expectedBefore, actualBefore)

            // Modify data block, invalidate, and verify digest changes to match naive SHA-256.
            FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE).use { ch ->
                ch.write(ByteBuffer.wrap(byteArrayOf(0x42, 0x24)), 8L * 1024 * 1024 + 200L)
            }
            SparseFileDigest.invalidate(path)
            val expectedAfter = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                naiveChannelSha256(ch, totalSize)
            }
            val actualAfter = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
                SparseFileDigest.cachedOrCompute(path, ch, totalSize)
            }
            assertNotEquals(expectedBefore, expectedAfter)
            assertEquals(expectedAfter, actualAfter)

            // Also verify all-zero sparse file matches naive SHA-256.
            val zeroPath = dir.resolve("all-zero.img")
            val zeroSize = 10L * 1024 * 1024
            FileChannel.open(zeroPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ).use { ch ->
                ch.write(ByteBuffer.allocate(512), zeroSize - 512L)
            }
            val expectedZero = FileChannel.open(zeroPath, StandardOpenOption.READ).use { ch -> naiveChannelSha256(ch, zeroSize) }
            val actualZero = FileChannel.open(zeroPath, StandardOpenOption.READ).use { ch -> SparseFileDigest.computeSha256(zeroPath, ch, zeroSize) }
            assertEquals(expectedZero, actualZero)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
