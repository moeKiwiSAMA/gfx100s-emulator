package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VglibDirectRamFastpathTest {
    private class OffHeapDirectRam(
        private val base: Long,
        private val sizeBytes: Long,
        private val allowDirect: Boolean,
    ) : DirectGuestRamPort, AutoCloseable {
        private val arena = Arena.ofConfined()
        val seg: MemorySegment = arena.allocate(sizeBytes, 64L)
        var slowReads = 0
        var slowWrites = 0
        val directWrites = ArrayList<Pair<Long, Long>>()

        private fun checkBounds(address: Long, size: Long) {
            require(address >= base && size >= 0L && (address - base) + size <= sizeBytes) {
                "OOB addr=0x${address.toString(16)} size=$size"
            }
        }

        fun load(address: Long, bytes: ByteArray) {
            checkBounds(address, bytes.size.toLong())
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, address - base, bytes.size)
        }

        override fun check(address: Long, size: Long, write: Boolean) = checkBounds(address, size)

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            checkBounds(address, len.toLong())
            slowReads++
            val tmp = ByteArray(len)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address - base, tmp, 0, len)
            destination.put(tmp)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            checkBounds(address, len.toLong())
            slowWrites++
            val tmp = ByteArray(len)
            source.get(tmp)
            MemorySegment.copy(tmp, 0, seg, ValueLayout.JAVA_BYTE, address - base, len)
        }

        override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
            if (!allowDirect) return null
            checkBounds(address, size)
            return seg.asSlice(address - base, size)
        }

        override fun notifyDirectWrite(address: Long, size: Long) {
            directWrites.add(address to size)
        }

        override fun close() = arena.close()
    }

    @Test
    fun lcdcDirectRamToPixelBufferMatchesDmaFallbackBitExactAndBenchmark() {
        val base = 0x8100_0000L
        val osdAddr = base + 0x0000_0000L
        val pic0Addr = base + 0x0040_0000L
        val pic1Addr = base + 0x0060_0000L

        val osdW = 1024
        val osdH = 768
        val osdStride = osdW * 4
        val osdBytes = ByteArray(osdStride * osdH)
        for (y in 0 until osdH) {
            for (x in 0 until osdW) {
                val p = y * osdStride + x * 4
                if (x in 64..320 && y in 48..200) {
                    osdBytes[p] = (x and 0xFF).toByte()
                    osdBytes[p + 1] = (y and 0xFF).toByte()
                    osdBytes[p + 2] = 0xCC.toByte()
                    osdBytes[p + 3] = 0xA0.toByte()
                }
            }
        }
        val pic0W = 1024
        val pic0H = 768
        val pic0Stride = pic0W * 2
        val pic0Bytes = ByteArray(pic0Stride * pic0H) { ((it * 29 + 17) and 0xFF).toByte() }
        val pic1W = 320
        val pic1H = 240
        val pic1Stride = pic1W * 2
        val pic1Bytes = ByteArray(pic1Stride * pic1H) { ((it * 13 + 73) and 0xFF).toByte() }

        fun setupAndPublish(ram: OffHeapDirectRam, outSeg: MemorySegment, iters: Int): Double {
            ram.load(osdAddr, osdBytes)
            ram.load(pic0Addr, pic0Bytes)
            ram.load(pic1Addr, pic1Bytes)

            val ctx = LcdcFunctionalTest.Context()
            ctx.entries["display.main"] = ArgbFrameBuffer(640, 480)
            ctx.entries["cpu.memory"] = ram

            val scanout = LcdcFirstScanout(
                context = ctx,
                frameLimit = 0,
                profile = LcdcFirstScanout.PIC_YUV_OSD0,
                continuous = true,
            )
            scanout.attachDirectArgbPreSink(
                sink = { w, h, writer ->
                    assertEquals(640, w)
                    assertEquals(480, h)
                    writer(outSeg)
                    true
                },
                mirrorToDisplayPort = false,
            )

            val words = LongArray(0x608 / 4)
            val writes = LongArray(0x608 / 4)
            fun setReg(off: Int, value: Long) {
                words[off / 4] = value
                writes[off / 4] = 1L
            }
            // Enable OSD0 + ACTIVE_GATE + PIC0 + PIC1
            setReg(
                0x008,
                LcdcFirstScanout.LAYER_EN_OSD0 or LcdcFirstScanout.LAYER_EN_ACTIVE_GATE or
                    LcdcFirstScanout.LAYER_EN_PIC0 or LcdcFirstScanout.LAYER_EN_PIC1,
            )
            // OSD0: 1024x768 RGBA with chroma-key control active
            setReg(0x030, 0x2008_0001L)
            setReg(0x040, 0L)
            setReg(0x044, osdAddr)
            setReg(0x050, (1L shl 16) or osdW.toLong())
            setReg(0x054, (osdH.toLong() shl 16) or osdW.toLong())
            setReg(0x058, 0L)
            setReg(0x05C, (osdH.toLong() shl 16) or osdW.toLong())
            setReg(0x060, 0x1000_1000L)
            // PIC0: 1024x768 UYVY (stride in 8-byte units at +0x138, size at +0x144, scale at +0x148)
            setReg(0x130, 0L)
            setReg(0x134, pic0Addr)
            setReg(0x138, (pic0Stride / 8).toLong())
            setReg(0x140, 0L)
            setReg(0x144, (pic0H.toLong() shl 16) or pic0W.toLong())
            setReg(0x148, 0x1000_1000L)
            // PIC1: 320x240 UYVY at (160, 120), alpha=128
            setReg(0x160, 128L)
            setReg(0x164, 0L)
            setReg(0x168, pic1Addr)
            setReg(0x16C, (pic1Stride / 8).toLong())
            setReg(0x174, (120L shl 16) or 160L)
            setReg(0x178, (pic1H.toLong() shl 16) or pic1W.toLong())
            setReg(0x17C, 0x1000_1000L)
            setReg(0x600, 1L)

            // Warmup + verify via LCDC shadow latch commit (+0x600 = 1)
            scanout.write(0x600L, 1L, words, writes)
            val t0 = System.nanoTime()
            repeat(iters) { scanout.write(0x600L, 1L, words, writes) }
            return (System.nanoTime() - t0) / 1e6 / iters
        }

        Arena.ofConfined().use { outArena ->
            val slowOutSeg = outArena.allocate(640L * 480L * 4L, 64L)
            val dirOutSeg = outArena.allocate(640L * 480L * 4L, 64L)
            OffHeapDirectRam(base, 10L * 1024L * 1024L, allowDirect = false).use { slowRam ->
                OffHeapDirectRam(base, 10L * 1024L * 1024L, allowDirect = true).use { directRam ->
                    val iters = 30
                    val slowMs = setupAndPublish(slowRam, slowOutSeg, iters)
                    val dirMs = setupAndPublish(directRam, dirOutSeg, iters)

                    val slowPx = IntArray(640 * 480)
                    val dirPx = IntArray(640 * 480)
                    MemorySegment.copy(slowOutSeg, ValueLayout.JAVA_INT_UNALIGNED, 0L, slowPx, 0, slowPx.size)
                    MemorySegment.copy(dirOutSeg, ValueLayout.JAVA_INT_UNALIGNED, 0L, dirPx, 0, dirPx.size)
                    assertContentEquals(slowPx, dirPx, "DirectGuestRamPort LCDC 3-plane composite must match DmaMemory fallback bit-exact")

                    if (NativeRenderBridge.hasDirectArgbPreComposite) {
                        assertEquals(0, directRam.slowReads, "Direct LCDC scanout must execute zero slow DMA reads")
                        assertTrue(slowRam.slowReads >= (iters + 1) * 3, "Fallback path reads all 3 planes every frame")
                        println(
                            "[W12-LCDC-BENCH] 3-plane PIC0+PIC1+OSD0 -> ARGB_PRE PixelBuffer (640x480): " +
                                "DmaMemory=${"%.3f".format(slowMs)} ms/frame (slowReads=${slowRam.slowReads}) -> " +
                                "DirectGuestRamPort=${"%.3f".format(dirMs)} ms/frame (slowReads=${directRam.slowReads})",
                        )
                    }
                }
            }
        }
    }
}
