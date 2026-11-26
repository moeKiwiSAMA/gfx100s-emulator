package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsrawBayerFormatTest {
    private val width = 768
    private val height = 48

    private fun encode(bits: Int, compressed: Boolean, capacity: Long = 0): ByteArray {
        val memory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) = Unit

            override fun read(address: Long, destination: ByteBuffer) {
                var index = (address / 2).toInt()
                while (destination.hasRemaining()) {
                    val x = index % width
                    val y = index / width
                    val pixel = (3 * x + 7 * y + (x and 1) * 557 + (y and 1) * 1301) and ((1 shl bits) - 1)
                    destination.put(pixel.toByte()).put((pixel ushr 8).toByte())
                    index++
                }
            }

            override fun write(address: Long, source: ByteBuffer) = error("Read-only Bayer input")
        }
        return LsrawCodecEngine.encodeFujifilmBayer(
            memory, 0, width, width, height, width, 1, height / 6, bits, compressed, capacity, useNative = false,
        )
    }

    @Test
    fun bayerWireFormatMatchesIndependentlyDecodedStrips() {
        // These first-strip prefixes come from full-size GFX100S streams checked pixel for pixel
        // with LibRaw 0.22.2. The width of a strip and its predictors are independent of frame size.
        val prefixes = mapOf(
            (12 to false) to "15a62b178b80ac241bf3ffe730497d159946ad4632b704b35fab906b6438a10f",
            (12 to true) to "cc1548b2b4f084ab8372a36d3381875f6c3b2f6efa1a11d072cccfcecf8552aa",
            (14 to false) to "8b35de24420c028912bbff6a29ac90d148a6b871bf9eedca85fcbfc38745472b",
            (14 to true) to "7b439bb34f8661b6b280775cf1829728b099a52a509a8053cfd91367a2c4980c",
            (16 to false) to "b1282ea88c00fdd565d98574c84e392b806e73d6efa5f87f1922bc04a94825bc",
            (16 to true) to "3a3c83871a7b6981b53c588c291beccb6701ad33fc2b8916833c65078b0f023f",
        )
        val headerBytes = LsrawCodecEngine.headerBytesForStrips(1)
        for ((mode, prefix) in prefixes) {
            val (bits, compressed) = mode
            val frame = encode(bits, compressed)
            assertContentEquals(byteArrayOf(0x49, 0x53, if (compressed) 0 else 1, 0, bits.toByte()), frame.copyOf(5))
            val payload = headerBytes + if (compressed) 16 else 0
            assertEquals(frame.size - payload, ByteBuffer.wrap(frame).getInt(16))
            assertTrue((headerBytes until payload).all { frame[it].toInt() == 0 })
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(frame, payload, 128)
            assertEquals(prefix, HexFormat.of().formatHex(digest.digest()), "bits=$bits compressed=$compressed")
            if (bits == 16 && !compressed) {
                digest.update(frame, payload, 8192)
                assertEquals("6158b070c4cb549652088619efb0eb58cc319cdd5f52e8b1641ba89421d4820a",
                    HexFormat.of().formatHex(digest.digest()))
            }
        }
    }

    @Test
    fun outputCapacityMustContainEveryStripAndItsPadding() {
        val expected = encode(14, compressed = false)
        assertContentEquals(expected, encode(14, compressed = false, capacity = expected.size.toLong()))
        assertTrue(encode(14, compressed = false, capacity = expected.size - 1L).isEmpty())
    }
}
