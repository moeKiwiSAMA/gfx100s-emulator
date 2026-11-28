package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MfAssistPeakingAndMovdcTest {
    private class FlatDmaMemory(val base: Long, size: Int) : DmaMemory {
        val bytes = ByteArray(size)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && address + size <= base + bytes.size) {
                "Out of range: 0x${address.toString(16)} + $size"
            }
        }
        override fun read(address: Long, destination: ByteBuffer) {
            check(address, destination.remaining().toLong(), false)
            destination.put(bytes, (address - base).toInt(), destination.remaining())
        }
        override fun write(address: Long, source: ByteBuffer) {
            check(address, source.remaining().toLong(), true)
            source.get(bytes, (address - base).toInt(), source.remaining())
        }
    }

    /** Synthetic 8-bit Bayer sensor with a sharp high-contrast vertical stripe + medium contrast step. */
    private class CheckerEdgeSensor(private val w: Int, private val h: Int) : SensorPort {
        override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val v = when {
                        x in (w / 4)..(w / 2) && y in (h / 4)..(3 * h / 4) -> 240 // sharp white box
                        x in (3 * w / 5)..(4 * w / 5) && y in (h / 4)..(3 * h / 4) -> 120 // medium-contrast gray box
                        else -> 24
                    }
                    out[y * w + x] = v.toByte()
                }
            }
            destination.put(out)
            return out.size
        }
    }

    @Test
    fun focusPeakingOffLeavesOutputBitIdenticalAndSupportsAllFourLiveColors() {
        val w = 64
        val h = 48
        val stride = w * 2
        val sensor = CheckerEdgeSensor(w, h)
        val memOff = FlatDmaMemory(0x2000_0000L, stride * h)
        val engine = LiveViewDmaEngine()

        // 1. OFF (0xFFF68950 = 0x1FF)
        val cfgOff = MfAssistPeakingFilter.fromWords(0L, 0x03C0_0000L, 0L, 0L, 0x0000_01FFL)
        assertFalse(cfgOff.enabled)
        assertTrue(engine.pumpLiveViewDma(1L, 0x2000_0000L, stride.toLong(), ((h - 1).toLong() shl 16) or (w - 1).toLong(), 0x1005_0000L, sensor, memOff, null, peaking = cfgOff))
        val baseline = memOff.bytes.copyOf()

        // Re-pumping with OFF hits YUV cache and stays bit-identical
        assertTrue(engine.pumpLiveViewDma(2L, 0x2000_0000L, stride.toLong(), ((h - 1).toLong() shl 16) or (w - 1).toLong(), 0x1005_0000L, sensor, memOff, null, peaking = cfgOff))
        assertTrue(baseline.contentEquals(memOff.bytes))
        assertEquals(1L, engine.cacheHits)

        // 2. Live GFX100S 0xFFF68950 words for WHITE / RED / BLUE / YELLOW (HIGH)
        val colors = listOf(
            Triple("WHITE", 0x8080_0064L, 0x80 to 0x80),
            Triple("RED", 0xFF55_0064L, 0x55 to 0xFF),
            Triple("BLUE", 0x00AB_0064L, 0xAB to 0x00),
            Triple("YELLOW", 0x9500_0064L, 0x00 to 0x95),
        )
        var prevMisses = engine.cacheMisses
        for ((name, word950, expectedCbCr) in colors) {
            val cfg = MfAssistPeakingFilter.fromWords(0L, 0x03C0_0000L, 0x80L, 0L, word950)
            assertTrue(cfg.enabled, "$name must be enabled")
            assertEquals(expectedCbCr.first, cfg.cb, "$name Cb mismatch")
            assertEquals(expectedCbCr.second, cfg.cr, "$name Cr mismatch")
            assertTrue(engine.pumpLiveViewDma(10L, 0x2000_0000L, stride.toLong(), ((h - 1).toLong() shl 16) or (w - 1).toLong(), 0x1005_0000L, sensor, memOff, null, peaking = cfg))
            // Cache must invalidate whenever peaking config changes!
            assertEquals(prevMisses + 1L, engine.cacheMisses, "$name must invalidate YUV cache")
            prevMisses = engine.cacheMisses

            var stampedPairs = 0
            for (y in 1 until h - 1) {
                for (x in 0 until w step 2) {
                    val off = y * stride + x * 2
                    val u = memOff.bytes[off].toInt() and 0xFF
                    val y0 = memOff.bytes[off + 1].toInt() and 0xFF
                    val v = memOff.bytes[off + 2].toInt() and 0xFF
                    val y1 = memOff.bytes[off + 3].toInt() and 0xFF
                    if (u == expectedCbCr.first && v == expectedCbCr.second && (y0 == cfg.highlightY || y1 == cfg.highlightY)) {
                        stampedPairs++
                    }
                }
            }
            assertTrue(stampedPairs >= 20, "$name must stamp highlight edges (got $stampedPairs)")
            // Flat background corner (2, 2) must remain untouched
            val bgOff = 2 * stride + 2 * 2
            assertEquals(baseline[bgOff], memOff.bytes[bgOff], "$name must not stamp flat background U")
            assertEquals(baseline[bgOff + 1], memOff.bytes[bgOff + 1], "$name must not stamp flat background Y")
        }
    }

    @Test
    fun focusPeakingHighSensitivityHighlightsMoreEdgesThanLowInPackedAndSemiPlanar() {
        val w = 64
        val h = 48
        val stride = w * 2
        val sensor = CheckerEdgeSensor(w, h)
        val memHigh = FlatDmaMemory(0x2000_0000L, stride * h)
        val memLow = FlatDmaMemory(0x2000_0000L, stride * h)
        val engine = LiveViewDmaEngine()

        // HIGH: +0x914=0x03C00000, +0x90C=0 (exact live GFX100S PEAK_RED_HIGH)
        val cfgHigh = MfAssistPeakingFilter.fromWords(0L, 0x03C0_0000L, 0x80L, 0L, 0xFF55_0064L)
        // LOW: +0x90C=0x000003C0, +0x914=0 (exact live GFX100S PEAK_RED_LOW)
        val cfgLow = MfAssistPeakingFilter.fromWords(0x0000_03C0L, 0L, 0x80L, 0L, 0xFF55_0064L)
        assertTrue(cfgHigh.highSensitivity)
        assertFalse(cfgLow.highSensitivity)
        assertTrue(cfgHigh.effectiveThreshold < cfgLow.effectiveThreshold)

        assertTrue(engine.pumpLiveViewDma(1L, 0x2000_0000L, stride.toLong(), ((h - 1).toLong() shl 16) or (w - 1).toLong(), 0x1005_0000L, sensor, memHigh, null, peaking = cfgHigh))
        assertTrue(engine.pumpLiveViewDma(2L, 0x2000_0000L, stride.toLong(), ((h - 1).toLong() shl 16) or (w - 1).toLong(), 0x1005_0000L, sensor, memLow, null, peaking = cfgLow))

        fun countRedPairs(buf: ByteArray): Int {
            var n = 0
            for (off in 0 until buf.size step 4) {
                if ((buf[off].toInt() and 0xFF) == 0x55 && (buf[off + 2].toInt() and 0xFF) == 0xFF) n++
            }
            return n
        }
        val highCount = countRedPairs(memHigh.bytes)
        val lowCount = countRedPairs(memLow.bytes)
        assertTrue(lowCount > 0, "LOW peaking must still highlight sharp high-contrast edges")
        assertTrue(highCount > lowCount, "HIGH peaking ($highCount) must highlight more edges than LOW ($lowCount)")

        // Semi-planar NV12 path also applies peaking cleanly
        val memNv12 = FlatDmaMemory(0x2100_0000L, w * h * 2)
        assertTrue(
            engine.pumpLiveViewDma(
                tick = 3L,
                addr = 0x2100_0000L,
                strideLong = w.toLong(),
                sizeWord = ((h - 1).toLong() shl 16) or (w - 1).toLong(),
                ctrl = 0x1010_0000L, // bit 28 enable + bit 20 NV12
                sensor = sensor,
                memory = memNv12,
                lens = null,
                uvAddr = 0x2100_0000L + (w * h).toLong(),
                peaking = cfgHigh,
            ),
        )
        var nv12RedUvPairs = 0
        val uvBase = w * h
        for (i in 0 until (w * (h / 2)) step 2) {
            if ((memNv12.bytes[uvBase + i].toInt() and 0xFF) == 0x55 &&
                (memNv12.bytes[uvBase + i + 1].toInt() and 0xFF) == 0xFF
            ) {
                nv12RedUvPairs++
            }
        }
        assertTrue(nv12RedUvPairs > 20, "Semi-planar NV12 peaking must stamp red UV pairs (got $nv12RedUvPairs)")
    }

    @Test
    fun movdcDefocusGeneratesPhaseShiftAndCompanionBB000CompositesMonoColorSplitAndMicroprism() {
        val base = 0x3000_0000L
        val mem = FlatDmaMemory(base, 4 * 1024 * 1024)

        // Create a 64×48 packed YYUV422 LiveView Ch1 buffer with a vivid red vertical bar at x=32..35
        val lvW = 64
        val lvH = 48
        val lvStride = 160 // intentionally padded (> lvW * 2) to verify strided ROI writes
        val lvAddr = base + 0x1000L
        for (y in 0 until lvH) {
            for (x in 0 until lvW step 2) {
                val p = (lvAddr - base).toInt() + y * lvStride + x * 2
                val onBar = x in 30..35
                mem.bytes[p] = (if (onBar) 230 else 40).toByte() // Y0
                mem.bytes[p + 1] = (if (onBar) 230 else 40).toByte() // Y1
                mem.bytes[p + 2] = (if (onBar) 0x55 else 0xA0).toByte() // U
                mem.bytes[p + 3] = (if (onBar) 0xF0 else 0x60).toByte() // V
            }
        }

        val lv1Words = LongArray(IspCoreOffsets.LIVE_VIEW_CH_OFFSETS.size)
        lv1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0c4)] = lvAddr
        lv1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x0d0)] = lvStride.toLong()
        lv1Words[IspCoreOffsets.LIVE_VIEW_CH_INDEX.getValue(0x018)] = ((lvH - 1).toLong() shl 16) or (lvW - 1).toLong()

        // 1. MOVDC phase generation: defocused lens -> opposite horizontal shifts; in-focus lens -> aligned!
        val outA = base + 0x10_0000L
        val outB = base + 0x14_0000L
        val halfStride = lvW * 2 // activeW = lvW = 64, rowStride = 256
        val movdcWords = LongArray(0x140 / 4)
        movdcWords[0x40 / 4] = outA
        movdcWords[0x44 / 4] = outB
        movdcWords[0x48 / 4] = halfStride.toLong()
        movdcWords[0x90 / 4] = ((lvH - 1).toLong() shl 16)

        val defocusedLens = LensParameters("GF63", 63.0, 2.8, 3.0, 0.9, 0.0)
        val inFocusLens = LensParameters("GF63", 63.0, 2.8, MovdcMfAssistEngine.NOMINAL_SUBJECT_DISTANCE_M, 0.9, 0.0)
        assertTrue(MovdcMfAssistEngine.computeDefocusShiftPx(defocusedLens) > 0)
        assertEquals(0, MovdcMfAssistEngine.computeDefocusShiftPx(inFocusLens))

        assertTrue(MovdcMfAssistEngine.onMovdcComplete(movdcWords, mem, lv1Words, defocusedLens))
        val movdcStride = halfStride * 2
        fun findBrightCenterX(bufAddr: Long, row: Int): Double {
            var sumX = 0L
            var cnt = 0L
            val rOff = (bufAddr - base).toInt() + row * movdcStride
            for (x in 0 until lvW) {
                val yVal = mem.bytes[rOff + (x and 1.inv()) * 2 + (x and 1)].toInt() and 0xFF
                if (yVal > 180) {
                    sumX += x
                    cnt++
                }
            }
            return if (cnt == 0L) -1.0 else sumX.toDouble() / cnt
        }
        val cxA = findBrightCenterX(outA, 24)
        val cxB = findBrightCenterX(outB, 24)
        assertTrue(cxA > cxB + 4.0, "Defocused MOVDC Phase-A ($cxA) and Phase-B ($cxB) must split horizontally in opposite directions")

        // In-focus MOVDC aligns Phase-A and Phase-B
        assertTrue(MovdcMfAssistEngine.onMovdcComplete(movdcWords, mem, lv1Words, inFocusLens))
        assertEquals(findBrightCenterX(outA, 24), findBrightCenterX(outB, 24))

        // Restore defocused Phase-A / Phase-B for BB000 mask compositing checks
        assertTrue(MovdcMfAssistEngine.onMovdcComplete(movdcWords, mem, lv1Words, defocusedLens))

        // 2. COMPANION_BB000 Mono Split (0x11 / 0x22) -> neutral monochrome chroma U=V=0x80 + opposite strip shifts
        val roiW = 64
        val roiH = 48
        val maskAddr = base + 0x20_0000L
        val outCompAddr = base + 0x24_0000L
        for (y in 0 until roiH) {
            val mVal: Byte = if ((y / 12) % 2 == 0) 0x11 else 0x22
            for (x in 0 until roiW) mem.bytes[(maskAddr - base).toInt() + y * roiW + x] = mVal
        }
        val bbWords = LongArray(0xA4 / 4)
        bbWords[0x10 / 4] = lvAddr
        bbWords[0x14 / 4] = (lvStride / 2).toLong() // pixel stride (UYVY = 2 B/px)
        bbWords[0x18 / 4] = outA
        bbWords[0x1C / 4] = (movdcStride / 2).toLong()
        bbWords[0x20 / 4] = outB
        bbWords[0x24 / 4] = (movdcStride / 2).toLong()
        bbWords[0x30 / 4] = maskAddr
        bbWords[0x34 / 4] = roiW.toLong()
        bbWords[0x3C / 4] = outCompAddr
        bbWords[0x40 / 4] = (lvStride / 2).toLong()
        bbWords[0x44 / 4] = (roiH.toLong() shl 16) or roiW.toLong()

        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        // Band 0 (y=6) must be monochrome (U=V=0x80) and shifted like outA; Band 1 (y=18) monochrome & shifted like outB
        val band0Off = (outCompAddr - base).toInt() + 6 * lvStride + 10 * 2
        assertEquals(0x80, mem.bytes[band0Off + 2].toInt() and 0xFF, "Mono Split must desaturate U=0x80")
        assertEquals(0x80, mem.bytes[band0Off + 3].toInt() and 0xFF, "Mono Split must desaturate V=0x80")
        fun findCompCenterX(row: Int): Double {
            var s = 0L; var c = 0L
            val rOff = (outCompAddr - base).toInt() + row * lvStride
            for (x in 0 until roiW) {
                val yv = mem.bytes[rOff + (x and 1.inv()) * 2 + (x and 1)].toInt() and 0xFF
                if (yv > 180) { s += x; c++ }
            }
            return if (c == 0L) -1.0 else s.toDouble() / c
        }
        assertTrue(findCompCenterX(6) > findCompCenterX(18) + 4.0, "Mono Split alternating bands must show opposite horizontal split")

        // 3. COMPANION_BB000 Color Split (0x01 / 0x02) -> preserves vivid chroma + opposite shifts
        for (y in 0 until roiH) {
            val mVal: Byte = if ((y / 12) % 2 == 0) 0x01 else 0x02
            for (x in 0 until roiW) mem.bytes[(maskAddr - base).toInt() + y * roiW + x] = mVal
        }
        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        val colorBand0Bg = (outCompAddr - base).toInt() + 6 * lvStride + 2 * 2
        assertEquals(0xA0, mem.bytes[colorBand0Bg + 2].toInt() and 0xFF, "Color Split must preserve U chroma")
        assertEquals(0x60, mem.bytes[colorBand0Bg + 3].toInt() and 0xFF, "Color Split must preserve V chroma")
        assertTrue(findCompCenterX(6) > findCompCenterX(18) + 4.0, "Color Split alternating bands must show opposite horizontal split")

        // 4. COMPANION_BB000 Microprism (0x00 outside circle, 0x01/0x02 checkerboard inside circle)
        val cx = roiW / 2; val cy = roiH / 2; val r2 = 16 * 16
        for (y in 0 until roiH) {
            for (x in 0 until roiW) {
                val dx = x - cx; val dy = y - cy
                val mVal: Byte = if (dx * dx + dy * dy <= r2) {
                    if (((x / 4) xor (y / 4)) and 1 == 0) 0x01 else 0x02
                } else {
                    0x00
                }
                mem.bytes[(maskAddr - base).toInt() + y * roiW + x] = mVal
            }
        }
        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        // Corner (2, 2) is outside circle (0x00) -> exact passthrough from in0
        val cornerOut = (outCompAddr - base).toInt() + 2 * lvStride + 2 * 2
        val cornerIn0 = (lvAddr - base).toInt() + 2 * lvStride + 2 * 2
        assertEquals(mem.bytes[cornerIn0], mem.bytes[cornerOut], "Microprism outside circle must pass through in0 Y0")
        assertEquals(mem.bytes[cornerIn0 + 2], mem.bytes[cornerOut + 2], "Microprism outside circle must pass through in0 U")
        // Inside circle (y=20 vs y=24, alternating checkerboard rows) must differ in shifted luma
        var prismDiffs = 0
        for (x in (cx - 12) until (cx + 12)) {
            val y20 = mem.bytes[(outCompAddr - base).toInt() + 21 * lvStride + (x and 1.inv()) * 2 + (x and 1)]
            val y24 = mem.bytes[(outCompAddr - base).toInt() + 25 * lvStride + (x and 1.inv()) * 2 + (x and 1)]
            if (y20 != y24) prismDiffs++
        }
        assertTrue(prismDiffs > 4, "Microprism checkerboard inside circle must produce phase-shifted tile disparity (diffs=$prismDiffs)")
    }

    @Test
    fun companionBB000UnmaskedWeightedCombinePopulatesBoostLowLightOutputRing() {
        val base = 0x3D90_0000L
        val mem = FlatDmaMemory(base, 0x0120_0000)
        val width = 32
        val height = 16
        val pixelStride = 40 // strided (> width), matching 1024px at 1280px (0x500) stride on LCD
        val byteStride = pixelStride * 2
        val in0Addr = base + 0x00_0000L
        val in1Addr = base + 0x04_0000L
        val in2Addr = base + 0x08_0000L
        val in3Addr = base + 0x0C_0000L
        val outAddr = base + 0x10_0000L

        fun fillPlane(addr: Long, y0: Int, y1: Int, u: Int, v: Int) {
            val baseOff = (addr - base).toInt()
            for (y in 0 until height) {
                val rowOff = baseOff + y * byteStride
                for (x in 0 until width step 2) {
                    val p = rowOff + x * 2
                    mem.bytes[p] = y0.toByte()
                    mem.bytes[p + 1] = y1.toByte()
                    mem.bytes[p + 2] = u.toByte()
                    mem.bytes[p + 3] = v.toByte()
                }
            }
        }
        fillPlane(in0Addr, y0 = 100, y1 = 120, u = 160, v = 96)
        fillPlane(in1Addr, y0 = 140, y1 = 160, u = 120, v = 136)
        fillPlane(in2Addr, y0 = 180, y1 = 200, u = 100, v = 156)
        fillPlane(in3Addr, y0 = 220, y1 = 240, u = 140, v = 116)
        // Guard padding bytes beyond width must remain untouched
        val guardOff = (outAddr - base).toInt() + 3 * byteStride + width * 2
        mem.bytes[guardOff] = 0x5A

        val bbWords = LongArray(0xA4 / 4)
        bbWords[0x08 / 4] = 0x43L // 4-input mode 3 (BOOST AF PRIORITY - LOW LIGHT)
        bbWords[0x10 / 4] = in0Addr
        bbWords[0x14 / 4] = pixelStride.toLong()
        bbWords[0x18 / 4] = in1Addr
        bbWords[0x1C / 4] = pixelStride.toLong()
        bbWords[0x20 / 4] = in2Addr
        bbWords[0x24 / 4] = pixelStride.toLong()
        bbWords[0x28 / 4] = in3Addr
        bbWords[0x2C / 4] = pixelStride.toLong()
        bbWords[0x30 / 4] = 0L // unmasked
        bbWords[0x3C / 4] = outAddr
        bbWords[0x40 / 4] = pixelStride.toLong()
        bbWords[0x44 / 4] = (height.toLong() shl 16) or width.toLong()

        // 1. Bright-light passthrough (0x54 = 0x100 -> w0 = +256 = 1.0, w1=w2=w3=0)
        bbWords[0x54 / 4] = 0x0000_0100L
        bbWords[0x58 / 4] = 0x0000_0000L
        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        val outOff = (outAddr - base).toInt() + 5 * byteStride + 4 * 2
        assertEquals(100, mem.bytes[outOff].toInt() and 0xFF)
        assertEquals(120, mem.bytes[outOff + 1].toInt() and 0xFF)
        assertEquals(160, mem.bytes[outOff + 2].toInt() and 0xFF)
        assertEquals(96, mem.bytes[outOff + 3].toInt() and 0xFF)
        assertEquals(0x5A.toByte(), mem.bytes[guardOff], "Padding outside active width must not be clobbered")

        // 2. 4-frame equal Q8 temporal average (w0=w1=w2=w3 = 64 = 0x40 -> 0.25 each)
        bbWords[0x54 / 4] = 0x0040_0040L
        bbWords[0x58 / 4] = 0x0040_0040L
        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        assertEquals(160, mem.bytes[outOff].toInt() and 0xFF) // (100+140+180+220)/4 = 160
        assertEquals(180, mem.bytes[outOff + 1].toInt() and 0xFF) // (120+160+200+240)/4 = 180
        assertEquals(130, mem.bytes[outOff + 2].toInt() and 0xFF) // (160+120+100+140)/4 = 130
        assertEquals(126, mem.bytes[outOff + 3].toInt() and 0xFF) // (96+136+156+116)/4 = 126

        // 3. 2-frame low-light gain sum (w0=256, w1=256) saturates cleanly at 255
        bbWords[0x54 / 4] = 0x0100_0100L
        bbWords[0x58 / 4] = 0x0000_0000L
        assertTrue(MovdcMfAssistEngine.onCompanionBB000Complete(bbWords, mem))
        assertEquals(240, mem.bytes[outOff].toInt() and 0xFF) // 100 + 140 = 240
        assertEquals(255, mem.bytes[outOff + 1].toInt() and 0xFF) // 120 + 160 = 280 -> clamped to 255
    }
}
