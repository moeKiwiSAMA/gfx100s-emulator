package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LsrawCodecEngineTest {
    private class ArrayDmaMemory(private val base: Long, size: Int) : DmaMemory {
        val bytes = ByteArray(size)

        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && size >= 0L && (address - base) + size <= bytes.size.toLong()) {
                "DMA OOB addr=0x${address.toString(16)} size=$size"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            check(address, len.toLong(), false)
            destination.put(bytes, (address - base).toInt(), len)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            check(address, len.toLong(), true)
            source.get(bytes, (address - base).toInt(), len)
        }
    }

    @Test
    fun losslessEncodeAndDecodeRoundTripIsBitExactFor12_14_And16BitBayer() {
        val base = 0x1_0000_0000L // 34-bit AXI high-PA RAM (matches live GFX100S Bank 2/3)
        val ram = ArrayDmaMemory(base, 4 * 1024 * 1024)
        val srcPa = base + 0x0000_0000L
        val encPa = base + 0x0010_0000L
        val decPa = base + 0x0020_0000L

        val width = 3000 // 4 strips of 768 pixels (3072 padded)
        val height = 24 // 4 MCU blocks of 6 lines
        val blocksX = 4
        val blocksY = height / 6
        val hdrBytes = LsrawCodecEngine.headerBytesForStrips(blocksX)
        assertEquals(32, hdrBytes) // 16-byte header + 4*4B strip sizes = 32B

        for ((depthCode, bits) in listOf(0 to 12, 1 to 14, 2 to 16)) {
            val maxVal = (1 shl bits) - 1
            val original = ByteArray(width * height * 2)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val pattern = when ((y and 1) * 2 + (x and 1)) {
                        0 -> ((x * 7 + y * 31) and maxVal) // R
                        1 -> (((x * 13 + y * 5) xor 0x55) and maxVal) // G1
                        2 -> (((x * 11 + y * 17) + 128) and maxVal) // G2
                        else -> if ((x / 64 + y / 6) % 2 == 0) (maxVal * 3 / 4) else (maxVal / 5) // B
                    }
                    val off = (y * width + x) * 2
                    original[off] = (pattern and 0xFF).toByte()
                    original[off + 1] = ((pattern ushr 8) and 0xFF).toByte()
                }
            }
            ram.write(srcPa, ByteBuffer.wrap(original))

            var tick = 1000L
            val irqLines = HashMap<Int, Boolean>()
            val sink = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {
                    irqLines[irq] = high
                }
            }
            val comp = ImagingCompanionEngines({ tick }, { sink })
            val axi = comp.createAxiHigh34Device { false }
            val lsraw = comp.createLsrawMmioDevice({ false }, { ram })

            // Program 34-bit high-PA upper 2 bits (0xFFF4A750 = srcHi=1, 0xFFF4A754 = dstHi=1)
            axi.write(0x50, 4, (srcPa ushr 32) and 3L)
            axi.write(0x54, 4, (encPa ushr 32) and 3L)

            // 1. Open/Init handshake (0xFFFA2100 = 1 -> bit 16)
            lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE.inv())
            lsraw.write(0x100, 4, 1L)
            tick += ImagingCompanionEngines.COMPLETION_DELAY_TICKS
            lsraw.beforeInstruction(tick)
            assertEquals(true, irqLines[ImagingCompanionEngines.IRQ_LSRAW])
            assertEquals(ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE, lsraw.read(0x004, 4))
            lsraw.write(0x004, 4, ImagingCompanionEngines.LSRAW_IRQ_INIT_DONE)
            assertEquals(false, irqLines[ImagingCompanionEngines.IRQ_LSRAW])

            // 2. Encode (bit 8 = 1 -> Lossless, bit depth = depthCode)
            lsraw.write(0x080, 4, (1L shl 8) or depthCode.toLong())
            lsraw.write(0x084, 4, width.toLong())
            lsraw.write(0x088, 4, (height.toLong() shl 16) or (blocksX * 768).toLong())
            lsraw.write(0x08C, 4, ((blocksY - 1).toLong() shl 16) or (blocksX - 1).toLong())
            lsraw.write(0x090, 4, (height.toLong() shl 16) or width.toLong())
            lsraw.write(0x09C, 4, 1_048_576L)
            lsraw.write(0x144, 4, srcPa and 0xFFFF_FFFFL)
            lsraw.write(0x140, 4, encPa and 0xFFFF_FFFFL)
            lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE.inv())
            lsraw.write(0x000, 4, 1L)
            tick += ImagingCompanionEngines.COMPLETION_DELAY_TICKS
            lsraw.beforeInstruction(tick)

            assertEquals(true, irqLines[ImagingCompanionEngines.IRQ_LSRAW], "Encode IRQ asserted ($bits-bit)")
            assertEquals(ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE, lsraw.read(0x004, 4))
            assertEquals(0L, lsraw.read(0x000, 4) and 1L, "Busy bit cleared on encode done")
            val encodedSize = lsraw.read(0x0A4, 4).toInt()
            assertTrue(encodedSize in (hdrBytes + 256)..1_048_576, "Encoded size in bounds: $encodedSize")
            // Verify section5 @ 0x022B95CC..0x022B961C alignment invariant for Lossless (bit 8 == 1)
            assertEquals(0, (encodedSize - hdrBytes) and 0xFF, "Lossless 256-byte tail alignment ($bits-bit)")

            // Verify "IS" header bytes in DRAM
            val hdr = ByteArray(16)
            ram.read(encPa, ByteBuffer.wrap(hdr))
            assertEquals('I'.code, hdr[0].toInt() and 0xFF)
            assertEquals('S'.code, hdr[1].toInt() and 0xFF)
            assertEquals(1, hdr[2].toInt() and 0xFF)
            assertEquals(0, hdr[3].toInt() and 0xFF)
            assertEquals(bits, hdr[4].toInt() and 0xFF)
            lsraw.write(0x004, 4, ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE)
            assertEquals(false, irqLines[ImagingCompanionEngines.IRQ_LSRAW])

            // 3. Decode back into decPa (0xFFFA2000 = 2 -> bit 8)
            lsraw.write(0x144, 4, encPa and 0xFFFF_FFFFL)
            lsraw.write(0x140, 4, decPa and 0xFFFF_FFFFL)
            lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_DECODE_DONE.inv())
            lsraw.write(0x000, 4, 2L)
            tick += ImagingCompanionEngines.COMPLETION_DELAY_TICKS
            lsraw.beforeInstruction(tick)

            assertEquals(true, irqLines[ImagingCompanionEngines.IRQ_LSRAW], "Decode IRQ asserted ($bits-bit)")
            assertEquals(ImagingCompanionEngines.LSRAW_IRQ_DECODE_DONE, lsraw.read(0x004, 4))
            val decoded = ByteArray(original.size)
            ram.read(decPa, ByteBuffer.wrap(decoded))
            assertContentEquals(original, decoded, "Bit-exact lossless round-trip mismatch for $bits-bit Bayer")
        }
    }

    @Test
    fun compressedModeEncodesSmallerAlignedContainerAndRespectsMasking() {
        val base = 0x2000_0000L
        val ram = ArrayDmaMemory(base, 2 * 1024 * 1024)
        val srcPa = base
        val encPa = base + 0x0010_0000L
        val width = 1536
        val height = 12
        val blocksX = 2
        val blocksY = 2
        val hdrBytes = LsrawCodecEngine.headerBytesForStrips(blocksX)
        assertEquals(32, hdrBytes)

        val src = ByteArray(width * height * 2) { i -> ((i * 37) and 0xFF).toByte() }
        ram.write(srcPa, ByteBuffer.wrap(src))

        var tick = 2000L
        val irqLines = HashMap<Int, Boolean>()
        val sink = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqLines[irq] = high
            }
        }
        val comp = ImagingCompanionEngines({ tick }, { sink })
        val lsraw = comp.createLsrawMmioDevice({ false }, { ram })

        // Keep all IRQs masked (default = 0x00FD_0507): completion must NOT assert GIC line until unmasked!
        lsraw.write(0x080, 4, 2L) // bit 8 = 0 (Compressed), 16-bit
        lsraw.write(0x084, 4, width.toLong())
        lsraw.write(0x088, 4, (height.toLong() shl 16) or (blocksX * 768).toLong())
        lsraw.write(0x08C, 4, ((blocksY - 1).toLong() shl 16) or (blocksX - 1).toLong())
        lsraw.write(0x090, 4, (height.toLong() shl 16) or width.toLong())
        lsraw.write(0x09C, 4, 524_288L)
        lsraw.write(0x144, 4, srcPa)
        lsraw.write(0x140, 4, encPa)
        lsraw.write(0x000, 4, 1L)
        tick += ImagingCompanionEngines.COMPLETION_DELAY_TICKS
        lsraw.beforeInstruction(tick)

        assertFalse(irqLines[ImagingCompanionEngines.IRQ_LSRAW] == true, "Masked bit 0 must not assert GIC IRQ")
        assertEquals(ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE, lsraw.read(0x004, 4))
        val encSize = lsraw.read(0x0A4, 4).toInt()
        val metaTerm = (((blocksY - 1) ushr 4) + 1) * (blocksX shl 4)
        assertEquals(0, (encSize - hdrBytes - metaTerm) and 0xFF, "Compressed 256-byte tail alignment")

        // Unmasking bit 0 immediately raises GIC IRQ 137
        lsraw.write(0x008, 4, ImagingCompanionEngines.LSRAW_IRQ_VALID_MASK and ImagingCompanionEngines.LSRAW_IRQ_ENCODE_DONE.inv())
        assertEquals(true, irqLines[ImagingCompanionEngines.IRQ_LSRAW])
    }
}
