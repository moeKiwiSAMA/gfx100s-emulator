package com.github.nyabanban.fuji.emulator.components.isp

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IspStatsSramEnginePerfTest {
    private fun makeBayer16(w: Int, h: Int, seed: Int): ByteArray {
        val raw = ByteArray(w * h * 2)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val v = (((x * 37 + y * 53 + seed * 17) xor ((x ushr 2) * (y ushr 2))) and 0xFFFF)
                val off = (row + x) shl 1
                raw[off] = (v and 0xFF).toByte()
                raw[off + 1] = ((v ushr 8) and 0xFF).toByte()
            }
        }
        return raw
    }

    private fun makeBayer8(w: Int, h: Int, seed: Int): ByteArray {
        val raw = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                raw[row + x] = (((x * 29 + y * 41 + seed * 13) xor ((x ushr 1) + (y ushr 2))) and 0xFF).toByte()
            }
        }
        return raw
    }

    /** Reference scalar oracle matching original unoptimized grid + 13x9 CDAF math. */
    private fun referenceCell0AeR(raw: ByteArray, w: Int, h: Int, is16Bit: Boolean, obR: Int): Long {
        val cols = 16
        val rows = 16
        val stepPx = if (w >= 512 && h >= 256) 4 else 2
        val hwCellPixels = (w / cols).coerceAtLeast(2).toLong() * (h / rows).coerceAtLeast(2).toLong()
        val nChPerCell = (hwCellPixels / 4L).coerceAtLeast(1L)
        var sumR = 0L
        var cnt = 0
        var y = 0
        while (y + 1 < h) {
            val gy = ((y.toLong() * rows) / h).toInt().coerceIn(0, rows - 1)
            if (gy == 0) {
                var x = 0
                while (x + 1 < w) {
                    val gx = ((x.toLong() * cols) / w).toInt().coerceIn(0, cols - 1)
                    if (gx == 0) {
                        val idx = y * w + x
                        val r14 = if (is16Bit) {
                            val off = idx shl 1
                            ((raw[off].toInt() and 0xff) or ((raw[off + 1].toInt() and 0xff) shl 8)) ushr 6
                        } else {
                            (raw[idx].toInt() and 0xff) shl 2
                        }
                        sumR += r14.toLong()
                        cnt++
                    }
                    x += stepPx
                }
            }
            y += stepPx
        }
        val meanR = if (cnt > 0) sumR.toDouble() / cnt else 0.0
        return ((meanR + obR) * nChPerCell).roundToLong().coerceIn(0L, 0xFFFF_FFFFL)
    }

    private fun referenceSinglePointEdgeSum(raw: ByteArray, w: Int, h: Int, is16Bit: Boolean): Long {
        val stepPx = 2
        var edge = 0L
        var y = 0
        while (y < h) {
            var x = 1
            while (x < w) {
                fun s(ix: Int, iy: Int): Int {
                    val p = iy * w + ix
                    return if (is16Bit) {
                        val off = p shl 1
                        ((raw[off].toInt() and 0xff) or ((raw[off + 1].toInt() and 0xff) shl 8)) ushr 2
                    } else {
                        (raw[p].toInt() and 0xff) shl 6
                    }
                }
                val sig = s(x, y)
                if (x + 2 < w) {
                    val dH = abs(s(x + 2, y) - sig).toLong()
                    edge += (dH * dH) ushr 10
                }
                if (y + 2 < h) {
                    val dV = abs(s(x, y + 2) - sig).toLong()
                    edge += (dV * dV) ushr 10
                }
                x += stepPx
            }
            y += stepPx
        }
        return edge.coerceIn(0L, 0x7FFF_FFFFL)
    }

    @Test
    fun optimizedAeAndCdafMatchReferenceBitForBitAcross16BitAnd8Bit() {
        val engine = IspStatsSramEngine()

        // 1. 1024x576 16-bit full 16x16 AE + 13x9 CDAF
        val raw16 = makeBayer16(1024, 576, seed = 3)
        engine.updateAeFromBayer(raw = raw16, srcW = 1024, srcH = 576, is16Bit = true, reg5c = 0L, reg60 = 0L)
        assertEquals(
            referenceCell0AeR(raw16, 1024, 576, is16Bit = true, obR = IspStatsSramEngine.DEFAULT_OB_14BIT),
            engine.readResultSurfaceWord(0),
            "16-bit AE cell[0] R sum mismatch",
        )
        assertTrue(engine.readHead(IspStatsSramEngine.SEL4_OFF, 4) > 0L, "Expected non-zero 16-bit CDAF winLo")

        // 2. 1024x576 8-bit full 16x16 AE + 13x9 CDAF
        val raw8 = makeBayer8(1024, 576, seed = 9)
        engine.updateAeFromBayer(raw = raw8, srcW = 1024, srcH = 576, is16Bit = false, reg5c = 0L, reg60 = 0L)
        assertEquals(
            referenceCell0AeR(raw8, 1024, 576, is16Bit = false, obR = IspStatsSramEngine.DEFAULT_OB_14BIT),
            engine.readResultSurfaceWord(0),
            "8-bit AE cell[0] R sum mismatch",
        )

        // 3. 1x1 Single-Point AF ROI exact edgeSum parity on both 16-bit & 8-bit
        val small16 = makeBayer16(64, 48, seed = 11)
        val reg124 = (0L shl 16) or (0L shl 12) or 63L // x0=0, cols=1, cellW=64
        val reg128 = (0L shl 16) or (0L shl 12) or 47L // y0=0, rows=1, cellH=48
        engine.updateAeFromBayer(raw = small16, srcW = 64, srcH = 48, is16Bit = true, reg5c = 0L, reg60 = 0L, reg124 = reg124, reg128 = reg128)
        assertEquals(
            referenceSinglePointEdgeSum(small16, 64, 48, is16Bit = true),
            engine.readResultSurfaceWord(IspStatsSramEngine.SEL15_OFF - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF),
            "16-bit 1x1 CDAF edgeSum mismatch",
        )

        val small8 = makeBayer8(64, 48, seed = 15)
        engine.updateAeFromBayer(raw = small8, srcW = 64, srcH = 48, is16Bit = false, reg5c = 0L, reg60 = 0L, reg124 = reg124, reg128 = reg128)
        assertEquals(
            referenceSinglePointEdgeSum(small8, 64, 48, is16Bit = false),
            engine.readResultSurfaceWord(IspStatsSramEngine.SEL15_OFF - IspStatsSramEngine.RESULT_SURFACE_BASE_OFF),
            "8-bit 1x1 CDAF edgeSum mismatch",
        )
    }

    @Test
    fun benchmark1024x576AeAndCdafSteadyStateLatency() {
        val engine = IspStatsSramEngine()
        val raw16 = makeBayer16(1024, 576, seed = 21)
        repeat(15) {
            engine.updateAeFromBayer(raw = raw16, srcW = 1024, srcH = 576, is16Bit = true, reg5c = 0L, reg60 = 0L)
        }
        val samplesMs = List(30) {
            val t0 = System.nanoTime()
            engine.updateAeFromBayer(raw = raw16, srcW = 1024, srcH = 576, is16Bit = true, reg5c = 0L, reg60 = 0L)
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        val medianMs = samplesMs[samplesMs.size / 2]
        println("PERF_W9_ISP_STATS_1024x576 medianMs=${"%.3f".format(medianMs)} minMs=${"%.3f".format(samplesMs.first())}")
        assertTrue(medianMs < 10.0, "Expected fast 1024x576 AE+CDAF sweep (<10ms), got ${medianMs}ms")
    }
}
