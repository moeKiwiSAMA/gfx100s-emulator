package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for BUG-14: EVF 1280x960 (STILL, sz=0x03bf04ff) and 1280x720 (MOVIE, sz=0x02cf04ff)
 * LiveView DMA frames must pump cleanly into RAM instead of being rejected by a 1024x768 ceiling.
 */
class EvfLiveViewDmaTest {
    private val brightSensor = object : SensorPort {
        override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
            val totalPixels = 1024 * 576
            repeat(totalPixels) {
                destination.putShort(0x8000.toShort())
            }
            return totalPixels * 2
        }
    }

    @Test
    fun liveViewDmaPumpsEvf1280x960And1280x720UyvyFramesCleanly() {
        val engine = LiveViewDmaEngine()

        // 1. STILL EVF 1280x960 (sz = 0x03bf04ff, stride = 2560, slot 3 @ 0x3e0a2000)
        val stillAddr = 0x3e0a_2000L
        val stillBuf = ByteArray(1280 * 960 * 2)
        val stillMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == stillAddr && size == stillBuf.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(stillBuf, 0, source.remaining())
            }
        }
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 1000L,
                addr = stillAddr,
                strideLong = 2560L,
                sizeWord = 0x03bf_04ffL,
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = brightSensor,
                memory = stillMem,
                lens = null,
            ),
            "EVF STILL 1280x960 LiveView DMA must succeed",
        )
        // Center row (y=480, x=640) must carry non-zero luma from exposed sensor
        val stillCenterY = stillBuf[480 * 2560 + 640 * 2 + 1].toInt() and 0xff
        assertTrue(stillCenterY > 50, "Expected bright luma in 1280x960 EVF center, got $stillCenterY")

        // 2. MOVIE EVF 1280x720 (sz = 0x02cf04ff, stride = 2560, addr = 0x899ab000)
        val movieAddr = 0x899a_b000L
        val movieBuf = ByteArray(1280 * 720 * 2)
        val movieMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address == movieAddr && size == movieBuf.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                source.get(movieBuf, 0, source.remaining())
            }
        }
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 2000L,
                addr = movieAddr,
                strideLong = 2560L,
                sizeWord = 0x02cf_04ffL,
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = brightSensor,
                memory = movieMem,
                lens = null,
            ),
            "EVF MOVIE 1280x720 LiveView DMA must succeed",
        )
        val movieCenterY = movieBuf[360 * 2560 + 640 * 2 + 1].toInt() and 0xff
        assertTrue(movieCenterY > 50, "Expected bright luma in 1280x720 EVF center, got $movieCenterY")
        assertEquals(0x80, movieBuf[0].toInt() and 0xff, "Top-left neutral chroma U must be 0x80")
    }

    @Test
    fun strided2dUyvyDmaPreservesSideBySideDualDisplayPanesWithoutClobberingGap() {
        val engine = LiveViewDmaEngine()
        val baseAddr = 0x3d99_a000L
        val stride = 2560
        val sharedCanvas = ByteArray(768 * stride)
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(write && address >= baseAddr && (address - baseAddr) + size <= sharedCanvas.size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) = error("unused")
            override fun write(address: Long, source: ByteBuffer) {
                val off = (address - baseAddr).toInt()
                source.get(sharedCanvas, off, source.remaining())
            }
        }
        // Exact GFX100S 2.13 MF Dual Display side-by-side windows inside the same 2560-byte-stride ring buffer:
        // PIC0: (x=16, y=132) 672x504 -> 0x3d9ec820
        // PIC1: (x=736, y=288) 256x192 -> 0x3da4e5c0
        val pic0Addr = baseAddr + 132L * stride + 32L
        val pic1Addr = baseAddr + 288L * stride + 736L * 2L
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 10_000L,
                addr = pic0Addr,
                strideLong = stride.toLong(),
                sizeWord = ((504L - 1L) shl 16) or (672L - 1L),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = brightSensor,
                memory = mem,
                lens = null,
            ),
        )
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 11_000L,
                addr = pic1Addr,
                strideLong = stride.toLong(),
                sizeWord = ((192L - 1L) shl 16) or (256L - 1L),
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = brightSensor,
                memory = mem,
                lens = null,
            ),
        )
        // Middle row of PIC0 (y=384, which overlaps PIC1's Y range 288..480!) MUST stay intact (not zeroed by PIC1's stride padding!)
        val pic0MidY = sharedCanvas[384 * stride + 32 + 300 * 2 + 1].toInt() and 0xff
        val pic1MidY = sharedCanvas[384 * stride + 736 * 2 + 128 * 2 + 1].toInt() and 0xff
        assertTrue(pic0MidY > 50, "BUG-A3-4: PIC0 middle row (y=384) must NOT be clobbered by PIC1 strided DMA (got Y=$pic0MidY)")
        assertTrue(pic1MidY > 50, "BUG-A3-4: PIC1 magnified inset (y=384) must carry bright luma (got Y=$pic1MidY)")
    }
}
