package com.github.nyabanban.fuji.emulator.components.isp

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

class SocIspDirectRamFastpathTest {
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

        fun dump(address: Long, size: Int): ByteArray {
            checkBounds(address, size.toLong())
            val out = ByteArray(size)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address - base, out, 0, size)
            return out
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
    fun lsrawDirectRamEncodeAndDecodeMatchDmaFallbackBitExactAndBenchmark() {
        val base = 0x8000_0000L
        val width = 3072
        val height = 768
        val blocksX = 4
        val blocksY = height / 6
        val srcPa = base + 0x0000_0000L
        val encPa = base + 0x0060_0000L
        val decPa = base + 0x00C0_0000L
        val maxCap = 6L * 1024L * 1024L

        val raw = ByteArray(width * height * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val px = ((x * 11 + y * 29) xor ((x ushr 4) * 97)) and 0x3FFF
                val off = (y * width + x) * 2
                raw[off] = (px and 0xFF).toByte()
                raw[off + 1] = ((px ushr 8) and 0xFF).toByte()
            }
        }

        fun runRoundTrip(ram: OffHeapDirectRam): Pair<ByteArray, ByteArray> {
            ram.load(srcPa, raw)
            val words = LongArray(0x148 / 4)
            val axiHigh = LongArray(0x60 / 4)
            words[0x80 / 4] = (1L shl 8) or 1L // Lossless 14-bit Bayer
            words[0x84 / 4] = width.toLong()
            words[0x88 / 4] = (height.toLong() shl 16) or width.toLong()
            words[0x8C / 4] = ((blocksY - 1).toLong() shl 16) or (blocksX - 1).toLong()
            words[0x90 / 4] = (height.toLong() shl 16) or width.toLong()
            words[0x9C / 4] = maxCap
            words[0x144 / 4] = srcPa
            words[0x140 / 4] = encPa
            val encRes = LsrawCodecEngine.executeEncode(words, axiHigh, ram)
            assertTrue(encRes.dmaWritten && encRes.outputBytes > 0L)
            val encoded = ram.dump(encPa, encRes.outputBytes.toInt())

            words[0x144 / 4] = encPa
            words[0x140 / 4] = decPa
            val decRes = LsrawCodecEngine.executeDecode(words, axiHigh, ram)
            assertTrue(decRes.dmaWritten)
            val decoded = ram.dump(decPa, raw.size)
            return encoded to decoded
        }

        OffHeapDirectRam(base, 20L * 1024L * 1024L, allowDirect = false).use { slowRam ->
            OffHeapDirectRam(base, 20L * 1024L * 1024L, allowDirect = true).use { directRam ->
                val (slowEnc, slowDec) = runRoundTrip(slowRam)
                val (dirEnc, dirDec) = runRoundTrip(directRam)
                assertContentEquals(slowEnc, dirEnc, "DirectGuestRamPort LSRAW encode must match DmaMemory fallback bit-exact")
                assertContentEquals(raw, dirDec, "DirectGuestRamPort LSRAW decode must reconstruct original 14-bit Bayer bit-exact")
                assertContentEquals(slowDec, dirDec)

                if (NativeCodecBridge.hasLsraw) {
                    assertEquals(0, directRam.slowWrites, "Direct native LSRAW must execute zero slow DMA writes")
                    assertTrue(
                        directRam.directWrites.any { it.first == encPa && it.second == dirEnc.size.toLong() },
                        "LSRAW encode must notifyDirectWrite for encoded output",
                    )
                    assertTrue(
                        directRam.directWrites.any { it.first == decPa && it.second == raw.size.toLong() },
                        "LSRAW decode must notifyDirectWrite for decoded Bayer output",
                    )

                    val iters = 5
                    val tSlow0 = System.nanoTime()
                    repeat(iters) { runRoundTrip(slowRam) }
                    val slowMs = (System.nanoTime() - tSlow0) / 1e6 / iters

                    val tDir0 = System.nanoTime()
                    repeat(iters) { runRoundTrip(directRam) }
                    val dirMs = (System.nanoTime() - tDir0) / 1e6 / iters
                    println(
                        "[W12-LSRAW-BENCH] ${width}x$height 14b Bayer (${raw.size / (1024 * 1024)} MiB) encode+decode: " +
                            "DmaMemory=${"%.2f".format(slowMs)} ms/op -> DirectGuestRamPort=${"%.2f".format(dirMs)} ms/op " +
                            "(slowWrites=${directRam.slowWrites})",
                    )
                }
            }
        }
    }

    @Test
    fun liveViewDirectRamStreamingAndStridedWriteMatchDmaFallbackBitExact() {
        val base = 0x8800_0000L
        val srcW = 1024
        val srcH = 576
        val dstW = 1024
        val dstH = 768
        val stride = dstW * 2 + 64 // Strided 2D row pitch to exercise coalesced invalidation
        val frameBytes = (dstH - 1) * stride + dstW * 2
        val sizeWord = ((dstH - 1).toLong() shl 16) or (dstW - 1).toLong()
        val lens = LensParameters("GF45-100", 63.0, 4.0, 2.5, 0.9, 0.0)

        fun makeRaw(seed: Int): ByteArray {
            val raw = ByteArray(srcW * srcH * 2)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    bb.putShort((((x * 37 + y * 61 + seed * 97) and 0x0FFF) shl 4).toShort())
                }
            }
            return raw
        }

        val frames = Array(4) { makeRaw(it + 1) }
        var frameIdx = 0
        val sensor = object : SensorPort {
            override fun currentFrameGeneration(): Long = frameIdx.toLong()
            override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
                val src = frames[frameIdx]
                destination.put(src)
                return src.size
            }
        }

        val slowEngine = LiveViewDmaEngine()
        val directEngine = LiveViewDmaEngine()
        OffHeapDirectRam(base, 4L * 1024L * 1024L, allowDirect = false).use { slowRam ->
            OffHeapDirectRam(base, 4L * 1024L * 1024L, allowDirect = true).use { directRam ->
                // Run 4 dynamic consecutive frames so frames #2 and #3 hit the direct-demosaic streaming path
                for (f in frames.indices) {
                    frameIdx = f
                    assertTrue(
                        slowEngine.pumpLiveViewDma(
                            tick = (f + 1).toLong(),
                            addr = base,
                            strideLong = stride.toLong(),
                            sizeWord = sizeWord,
                            ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                            sensor = sensor,
                            memory = slowRam,
                            lens = lens,
                        ),
                    )
                    assertTrue(
                        directEngine.pumpLiveViewDma(
                            tick = (f + 1).toLong(),
                            addr = base,
                            strideLong = stride.toLong(),
                            sizeWord = sizeWord,
                            ctrl = LiveViewDmaEngine.ENABLE_BIT_28,
                            sensor = sensor,
                            memory = directRam,
                            lens = lens,
                        ),
                    )
                    assertContentEquals(
                        slowRam.dump(base, frameBytes),
                        directRam.dump(base, frameBytes),
                        "Frame $f LiveView direct RAM output must match DmaMemory fallback bit-exact",
                    )
                }
                assertEquals(0, directRam.slowWrites, "Direct LiveView DMA must perform zero slow DMA writes")
                assertEquals(frames.size, directRam.directWrites.size, "Each strided 768-row frame must coalesce into 1 notifyDirectWrite")
                assertTrue(slowRam.slowWrites >= frames.size * dstH, "Fallback path writes row-by-row for strided 2D")
                println(
                    "[W12-LV-BENCH] 1024x768 strided LiveView (4 frames): " +
                        "DmaMemory slowWrites=${slowRam.slowWrites} -> DirectGuestRamPort slowWrites=${directRam.slowWrites}, " +
                        "coalesced notifyDirectWrite=${directRam.directWrites.size}",
                )
            }
        }
    }
}
