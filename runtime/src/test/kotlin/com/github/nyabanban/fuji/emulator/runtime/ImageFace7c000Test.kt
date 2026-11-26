package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageFace7c000Test {
    @TempDir lateinit var home: Path
    private val catalog = AddressCatalog.bundled()

    private fun bus(selection: String, mode: String = "discovery"): DiscoveryBus =
        checkNotNull(
            CatalogRuntime(catalog, mode, home, mapOf("discovery" to mapOf("imageFace7c000" to selection)))
                .createBus(object : DiscoveryContext {
                    override fun core() = 1
                    override fun tick() = 100L
                    override fun pc() = 0x5af98L
                    override fun lr() = 0x01201b00L
                }),
        )

    @Test
    fun strictModeRejectsImageFace7c000() {
        assertFails { bus("retained-words-zero-v1", mode = "strict") }
        assertFails { bus("face-status-ready-v1", mode = "strict") }
        assertFails { bus(ImageFace7c000.IRQ130_PROFILE, mode = "strict") }
    }

    @Test
    fun retainedWordsZeroRetainsAlignedReadsAndWritesAcross4KiB() {
        val b = bus("retained-words-zero-v1")
        var seq = 1L
        assertEquals(0L, b.access(seq++, 0xfff7c700L, 4, false))
        b.access(seq++, 0xfff7c008L, 4, true, 0x12345678L)
        b.access(seq++, 0xfff7cffcL, 4, true, 0xabcdef01L)
        assertEquals(0x12345678L, b.access(seq++, 0xfff7c008L, 4, false))
        assertEquals(0xabcdef01L, b.access(seq++, 0xfff7cffcL, 4, false))
        assertFails { b.access(seq++, 0xfff7c000L, 2, false) }
        assertFails { b.access(seq++, 0xfff7d000L, 4, false) }
    }

    @Test
    fun faceStatusReadyModelsCompletionAndAckAcrossSnapshotRestore() {
        val b = bus("face-status-ready-v1")
        var seq = 1L
        // Initial idle/ready bits {0, 4, 8, 16, 20, 24}
        assertEquals(FACE_7C000_READY_BITS, b.access(seq++, 0xfff7c700L, 4, false))
        // Ack clears status bits
        b.access(seq++, 0xfff7c708L, 4, true, FACE_7C000_READY_BITS)
        assertEquals(0L, b.access(seq++, 0xfff7c700L, 4, false))
        // Writing 0xfff7c708 = 0 restores ready bits (0x025a9798 wait-idle pattern)
        b.access(seq++, 0xfff7c708L, 4, true, 0L)
        assertEquals(FACE_7C000_READY_BITS, b.access(seq++, 0xfff7c700L, 4, false))
        // Clear again, then start via 0xfff7c738 = 1 (0x025a9e94 pattern)
        b.access(seq++, 0xfff7c708L, 4, true, FACE_7C000_READY_BITS)
        assertEquals(0L, b.access(seq++, 0xfff7c700L, 4, false))
        b.access(seq++, 0xfff7c738L, 4, true, 1L)
        assertEquals(FACE_7C000_READY_BITS, b.access(seq++, 0xfff7c700L, 4, false))
        // Stop via 0xfff7c738 = 0 sets 0xfff7c734 bit 0 (0x025a9f50 -> 0x025a9fbc pattern)
        b.access(seq++, 0xfff7c738L, 4, true, 0L)
        assertEquals(1L, b.access(seq++, 0xfff7c734L, 4, false))
        // Teardown reset write 0xfff7c000 = 0 completes immediately with bit 0 set (DAT s5:0x025a9874..a4, BUG-17)
        b.access(seq++, 0xfff7c000L, 4, true, 0L)
        assertEquals(1L, b.access(seq++, 0xfff7c000L, 4, false))
        b.access(seq++, 0xfff7c000L, 4, true, 0x10L)
        assertEquals(0x11L, b.access(seq++, 0xfff7c000L, 4, false))

        val snap = b.saveSnapshot()
        val resumed = bus("face-status-ready-v1").apply { restoreSnapshot(snap) }
        assertEquals(FACE_7C000_READY_BITS, resumed.access(seq++, 0xfff7c700L, 4, false))
        assertEquals(1L, resumed.access(seq++, 0xfff7c734L, 4, false))
        assertEquals(0x11L, resumed.access(seq++, 0xfff7c000L, 4, false))
        resumed.access(seq++, 0xfff7c734L, 4, true, 0L)
        assertEquals(0L, resumed.access(seq++, 0xfff7c734L, 4, false))
    }

    @Test
    fun faceDetectIrq130RunsTwoPassPixelDetectionAndPass2CandidateDmaWithSnapshotRoundTrip() {
        val width = 640
        val height = 480
        val strideBytes = width * 2
        val inputBase = 0x8a9c8000L
        val pass2Base = 0x0593d200L
        val inputFrame = synthesizeYyuvPortraitFrame(width, height, faceCx = 320f, faceCy = 220f, faceHalf = 64f)
        val pass2Buf = ByteArray(4096)

        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                val ok = (!write && address >= inputBase && address + size <= inputBase + inputFrame.size) ||
                    (write && address >= pass2Base && address + size <= pass2Base + pass2Buf.size)
                require(ok) { "Unmapped test DMA 0x${address.toString(16)} len=$size write=$write" }
            }

            override fun read(address: Long, destination: ByteBuffer) {
                val off = (address - inputBase).toInt()
                val n = destination.remaining()
                destination.put(inputFrame, off, n)
            }

            override fun write(address: Long, source: ByteBuffer) {
                val off = (address - pass2Base).toInt()
                val n = source.remaining()
                source.get(pass2Buf, off, n)
            }
        }

        var currentTick = 1000L
        var irq130Level = false
        val irqLog = mutableListOf<Boolean>()
        fun newBus() = checkNotNull(
            CatalogRuntime(catalog, "discovery", home, mapOf("discovery" to mapOf(ImageFace7c000.KEY to ImageFace7c000.IRQ130_PROFILE)))
                .createBus(object : DiscoveryContext {
                    override fun core() = 0
                    override fun tick() = currentTick
                    override fun pc() = 0x025a9f14L
                    override fun lr() = 0x025a9e54L
                    override fun dmaMemory() = dma
                    override fun interruptSink() = object : InterruptSink {
                        override fun setLine(irq: Int, high: Boolean) {
                            if (irq == ImageFace7c000.IRQ_LINE) {
                                irq130Level = high
                                irqLog += high
                            }
                        }
                    }
                }),
        )

        val b = newBus()
        var seq = 1L
        // Configure input 640x480 YYUV422 at 0x8a9c8000, only scale 6 in schedule slot 0, scale 6 table, threshold 139.
        b.access(seq++, 0xfff7c040L, 4, true, (height.toLong() shl 16) or width.toLong())
        b.access(seq++, 0xfff7c044L, 4, true, width.toLong())
        b.access(seq++, 0xfff7c048L, 4, true, inputBase)
        b.access(seq++, 0xfff7c320L, 4, true, 0xfffffff6L)
        b.access(seq++, 0xfff7c324L, 4, true, 0x0fffffffL)
        b.access(seq++, 0xfff7c5d0L, 4, true, 139L)
        // Scale 6 entry at +0x330 + 6*0x14 = +0x3A8: step=0x400 (4px Q8), startX=0, startY=0
        b.access(seq++, 0xfff7c3a8L + 8, 4, true, 0x400L)
        b.access(seq++, 0xfff7c3a8L + 12, 4, true, 0L)
        b.access(seq++, 0xfff7c3a8L + 16, 4, true, 0L)

        // Pass 1 arm & start (DAT 0x025a9e94..0x025a9f14)
        b.access(seq++, 0xfff7c708L, 4, true, FACE_7C000_READY_BITS)
        b.access(seq++, 0xfff7c738L, 4, true, 1L)
        assertEquals(FACE_7C000_READY_BITS, b.access(seq++, 0xfff7c700L, 4, false))
        b.access(seq++, 0xfff7c008L, 4, true, 0L)
        b.access(seq++, 0xfff7c004L, 4, true, 1L)

        val due1 = currentTick + ImageFace7c000.COMPLETION_DELAY_TICKS
        assertEquals(due1, b.nextDeadline())
        assertFalse(irq130Level)
        currentTick = due1
        b.beforeInstruction(currentTick)

        assertTrue(irq130Level, "Pass 1 must assert GIC IRQ 130")
        assertEquals(1L, b.access(seq++, 0xfff7c730L, 4, false))
        assertEquals(1L, b.access(seq++, 0xfff7c734L, 4, false))

        // Verify detected face header & Q7 coordinates
        val faceCount = (b.access(seq++, 0xfff7c600L, 4, false) and 0x1FL).toInt()
        val pass2RecordCount = (b.access(seq++, 0xfff7c608L, 4, false) and 0x3FFL).toInt()
        assertEquals(1, faceCount, "Expected exactly 1 detected face")
        assertEquals(1L, b.access(seq++, 0xfff7c604L, 4, false) and 0xFFFFL)
        assertEquals(1, pass2RecordCount)
        val w0 = b.access(seq++, 0xfff7c610L, 4, false)
        val topLeftXQ7 = b.access(seq++, 0xfff7c614L, 4, false)
        val topLeftYQ7 = b.access(seq++, 0xfff7c618L, 4, false)
        val scaleIdx = (w0 and 0xFL).toInt()
        assertEquals(6, scaleIdx, "The first schedule nibble names scale 6")
        val halfPx = PixelFaceDetector.HALF_SIZE_Q7[scaleIdx] / 128.0f
        val detectedCx = topLeftXQ7 / 128.0f + halfPx
        val detectedCy = topLeftYQ7 / 128.0f + halfPx
        assertTrue(topLeftXQ7 >= 1L && topLeftYQ7 >= 1L, "Q7 topLeft must be >= 1")
        assertTrue(abs(detectedCx - 320f) < 28f, "Detected cx=$detectedCx too far from 320")
        assertTrue(abs(detectedCy - 220f) < 28f, "Detected cy=$detectedCy too far from 220")

        // W1C +0x734 clears status & deasserts IRQ 130
        b.access(seq++, 0xfff7c734L, 4, true, 1L)
        assertFalse(irq130Level)
        assertEquals(0L, b.access(seq++, 0xfff7c730L, 4, false))
        assertEquals(0L, b.access(seq++, 0xfff7c734L, 4, false))

        // Pass 2 candidate DMA readout (+0x738=0, +0x6E0=0x0593d200, +0x6E4=1, +0x008=4, +0x004=1)
        b.access(seq++, 0xfff7c738L, 4, true, 0L)
        b.access(seq++, 0xfff7c6e0L, 4, true, pass2Base)
        b.access(seq++, 0xfff7c6e4L, 4, true, pass2RecordCount.toLong())
        b.access(seq++, 0xfff7c008L, 4, true, 4L)
        b.access(seq++, 0xfff7c004L, 4, true, 1L)
        val pendingPass2 = b.saveSnapshot()
        currentTick += ImageFace7c000.COMPLETION_DELAY_TICKS
        b.beforeInstruction(currentTick)

        assertFalse(irq130Level, "Pass 2 runs with +0x738=0 so IRQ 130 must stay masked")
        assertEquals(1L, b.access(seq++, 0xfff7c734L, 4, false))
        val candBb = ByteBuffer.wrap(pass2Buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
        val candW0 = candBb.int.toLong() and 0xFFFF_FFFFL
        val candW1 = candBb.int.toLong() and 0xFFFF_FFFFL
        val scoreA = (candW0 and 0x3FFFFL).toInt()
        val scoreB = (((candW0 ushr 18) and 0x3FFFL) or ((candW1 and 3L) shl 14)).toInt()
        assertTrue(1250L * scoreA + 2500L * scoreB >= 139L * 10000L, "Pass 2 score must pass firmware +0x5D0 threshold")
        assertEquals(scaleIdx, ((candW1 ushr 28) and 0xFL).toInt())
        val completedCandidate = pass2Buf.copyOfRange(0, 8)
        pass2Buf.fill(0)
        val pendingBus = newBus().apply { restoreSnapshot(pendingPass2) }
        pendingBus.beforeInstruction(currentTick)
        assertContentEquals(completedCandidate, pass2Buf.copyOfRange(0, 8))

        // Snapshot save & restore preserves retained registers & state
        val snap = b.saveSnapshot()
        val resumed = newBus().apply { restoreSnapshot(snap) }
        assertEquals(139L, resumed.access(seq++, 0xfff7c5d0L, 4, false))
        assertEquals(1L, resumed.access(seq++, 0xfff7c600L, 4, false))

        resumed.access(seq++, 0xfff7c320L, 4, true, 0xffffffffL)
        resumed.access(seq++, 0xfff7c008L, 4, true, 0L)
        resumed.access(seq++, 0xfff7c004L, 4, true, 1L)
        currentTick += ImageFace7c000.COMPLETION_DELAY_TICKS
        resumed.beforeInstruction(currentTick)
        assertEquals(0L, resumed.access(seq++, 0xfff7c608L, 4, false), "All 15 schedule slots are disabled")
    }

    @Test
    fun pixelFaceDetectorTracksMovingPortraitAndRejectsNonFaceScenes() {
        val leftFaces = PixelFaceDetector.detectYyuv422(
            synthesizeYyuvPortraitFrame(640, 480, faceCx = 190f, faceCy = 225f, faceHalf = 60f),
            640,
            480,
            1280,
        )
        val rightFaces = PixelFaceDetector.detectYyuv422(
            synthesizeYyuvPortraitFrame(640, 480, faceCx = 450f, faceCy = 225f, faceHalf = 60f),
            640,
            480,
            1280,
        )
        assertEquals(1, leftFaces.size)
        assertEquals(1, rightFaces.size)
        assertTrue(rightFaces[0].centerX - leftFaces[0].centerX > 200f, "Detector must track left->right face shift")

        // Negative control 1: uniform gray / blank frame
        val blank = ByteArray(640 * 480 * 2) { 0x80.toByte() }
        assertEquals(0, PixelFaceDetector.detectYyuv422(blank, 640, 480, 1280).size)

        // Negative control 2: high-contrast black/white QR checkerboard
        val qr = ByteArray(640 * 480 * 2)
        for (y in 60 until 420) {
            val row = y * 1280
            var x = 0
            while (x < 640) {
                val y0 = if (((x / 12) + (y / 12)) % 2 == 0) 240 else 16
                val y1 = if ((((x + 1) / 12) + (y / 12)) % 2 == 0) 240 else 16
                val off = row + (x ushr 1) * 4
                qr[off] = y0.toByte()
                qr[off + 1] = y1.toByte()
                qr[off + 2] = 128.toByte()
                qr[off + 3] = 128.toByte()
                x += 2
            }
        }
        assertEquals(0, PixelFaceDetector.detectYyuv422(qr, 640, 480, 1280).size)

        // Low-light (dim P-mode AE) portrait must still be detected cleanly (BUG-V3-5-2)
        val bright = synthesizeYyuvPortraitFrame(640, 480, faceCx = 440f, faceCy = 220f, faceHalf = 56f)
        val dim = ByteArray(bright.size)
        var p = 0
        while (p + 3 < bright.size) {
            // Compress luma to ~38% while preserving subtle warm chroma around 128
            val y0 = (((bright[p].toInt() and 0xFF) * 38) / 100).coerceIn(16, 235)
            val y1 = (((bright[p + 1].toInt() and 0xFF) * 38) / 100).coerceIn(16, 235)
            val cb = 128 + (((bright[p + 2].toInt() and 0xFF) - 128) * 65) / 100
            val cr = 128 + (((bright[p + 3].toInt() and 0xFF) - 128) * 65) / 100
            dim[p] = y0.toByte()
            dim[p + 1] = y1.toByte()
            dim[p + 2] = cb.coerceIn(16, 240).toByte()
            dim[p + 3] = cr.coerceIn(16, 240).toByte()
            p += 4
        }
        val dimFaces = PixelFaceDetector.detectYyuv422(dim, 640, 480, 1280)
        assertEquals(1, dimFaces.size, "Expected 1 face detected in dim low-light portrait, got $dimFaces")
        assertTrue(abs(dimFaces[0].centerX - 440f) < 30f, "Dim face cx=${dimFaces[0].centerX} should track 440")

        // Dark non-face uniform frame must remain 0 false positives
        val darkUniform = ByteArray(640 * 480 * 2) { idx -> if ((idx and 3) < 2) 24.toByte() else 128.toByte() }
        assertEquals(0, PixelFaceDetector.detectYyuv422(darkUniform, 640, 480, 1280).size)
    }

    @Test
    fun pixelFaceDetectorDetectsPortraitPlusMinus90DegFacesWithCorrectRollIdxAndFastScan() {
        // BUG-V3-5-7: Portrait +90 deg CW (rollIdx=3, portrait-ccw) & -90 deg CCW (rollIdx=9, portrait-cw)
        val cwFrame = synthesizeYyuvPortraitFrame(640, 480, faceCx = 320f, faceCy = 235f, faceHalf = 60f, rotDeg = 90)
        val ccwFrame = synthesizeYyuvPortraitFrame(640, 480, faceCx = 310f, faceCy = 240f, faceHalf = 60f, rotDeg = -90)

        val cwFaces = PixelFaceDetector.detectYyuv422(cwFrame, 640, 480, 1280)
        val ccwFaces = PixelFaceDetector.detectYyuv422(ccwFrame, 640, 480, 1280)
        assertEquals(1, cwFaces.size, "Expected 1 face for +90 deg CW portrait, got $cwFaces")
        assertEquals(1, ccwFaces.size, "Expected 1 face for -90 deg CCW portrait, got $ccwFaces")
        assertEquals(3, cwFaces[0].rollIdx, "Expected rollIdx=3 (+90 deg CW), got ${cwFaces[0]}")
        assertEquals(9, ccwFaces[0].rollIdx, "Expected rollIdx=9 (-90 deg CCW), got ${ccwFaces[0]}")
        assertTrue(abs(cwFaces[0].centerX - 320f) < 32f && abs(cwFaces[0].centerY - 235f) < 32f, "CW center mismatch: ${cwFaces[0]}")
        assertTrue(abs(ccwFaces[0].centerX - 310f) < 32f && abs(ccwFaces[0].centerY - 240f) < 32f, "CCW center mismatch: ${ccwFaces[0]}")

        // Verify ImageFace7c000 Pass-1 + Pass-2 DMA encodes portrait rollIdx=9 into slot word 0 & Pass-2 word 1
        val inputBase = 0x8a9c8000L
        val pass2Base = 0x0593d200L
        val pass2Buf = ByteArray(1024)
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                val ok = (!write && address >= inputBase && address + size <= inputBase + ccwFrame.size) ||
                    (write && address >= pass2Base && address + size <= pass2Base + pass2Buf.size)
                require(ok) { "Unmapped DMA 0x${address.toString(16)}" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(ccwFrame, (address - inputBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(pass2Buf, (address - pass2Base).toInt(), source.remaining())
            }
        }
        var tick = 1000L
        val b = checkNotNull(
            CatalogRuntime(catalog, "discovery", home, mapOf("discovery" to mapOf(ImageFace7c000.KEY to ImageFace7c000.IRQ130_PROFILE)))
                .createBus(object : DiscoveryContext {
                    override fun core() = 0
                    override fun tick() = tick
                    override fun pc() = 0x025a9f14L
                    override fun lr() = 0x025a9e54L
                    override fun dmaMemory() = dma
                    override fun interruptSink() = object : InterruptSink {
                        override fun setLine(irq: Int, high: Boolean) {}
                    }
                }),
        )
        var seq = 1L
        b.access(seq++, 0xfff7c040L, 4, true, (480L shl 16) or 640L)
        b.access(seq++, 0xfff7c044L, 4, true, 640L)
        b.access(seq++, 0xfff7c048L, 4, true, inputBase)
        b.access(seq++, 0xfff7c320L, 4, true, 0x23456789L)
        b.access(seq++, 0xfff7c324L, 4, true, 0x0fffff01L)
        b.access(seq++, 0xfff7c5d0L, 4, true, 139L)
        b.access(seq++, 0xfff7c738L, 4, true, 1L)
        b.access(seq++, 0xfff7c008L, 4, true, 0L)
        b.access(seq++, 0xfff7c004L, 4, true, 1L)
        tick += ImageFace7c000.COMPLETION_DELAY_TICKS
        b.beforeInstruction(tick)

        assertEquals(1L, b.access(seq++, 0xfff7c600L, 4, false) and 0x1FL)
        val slotW0 = b.access(seq++, 0xfff7c610L, 4, false)
        assertEquals(9, ((slotW0 ushr 4) and 0xFL).toInt(), "Pass-1 slot word 0 [7:4] must encode rollIdx=9")

        b.access(seq++, 0xfff7c734L, 4, true, 1L)
        b.access(seq++, 0xfff7c738L, 4, true, 0L)
        b.access(seq++, 0xfff7c6e0L, 4, true, pass2Base)
        b.access(seq++, 0xfff7c6e4L, 4, true, 1L)
        b.access(seq++, 0xfff7c008L, 4, true, 4L)
        b.access(seq++, 0xfff7c004L, 4, true, 1L)
        tick += ImageFace7c000.COMPLETION_DELAY_TICKS
        b.beforeInstruction(tick)

        val candW1 = ByteBuffer.wrap(pass2Buf, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
        assertEquals(9, ((candW1 ushr 21) and 0xFL).toInt(), "Pass-2 candidate word 1 [24:21] must encode rollIdx=9")

        // Performance check: warm up JIT and verify 3-orientation (0/+90/-90 deg) scan stays fast (< 2.0 ms typical; generous 15 ms CI ceiling)
        repeat(15) { PixelFaceDetector.detectYyuv422(ccwFrame, 640, 480, 1280) }
        val t0 = System.nanoTime()
        repeat(30) { PixelFaceDetector.detectYyuv422(ccwFrame, 640, 480, 1280) }
        val avgMs = (System.nanoTime() - t0) / 30e6
        println("[BUG-V3-5-7] 3-orientation PixelFaceDetector avg=${"%.2f".format(avgMs)} ms/frame")
        assertTrue(avgMs < 15.0, "3-orientation face scan unexpectedly slow: $avgMs ms/frame")
    }

    private fun synthesizeYyuvPortraitFrame(
        width: Int,
        height: Int,
        faceCx: Float,
        faceCy: Float,
        faceHalf: Float,
        rotDeg: Int = 0,
    ): ByteArray {
        val out = ByteArray(width * height * 2)
        val stride = width * 2
        // Fill 60-row top/bottom letterbox with 0x80 and active 640x360 with cool Tuscan background + portrait face.
        for (y in 0 until height) {
            val row = y * stride
            val inActive = y in 60 until 420
            var x = 0
            while (x < width) {
                val off = row + (x ushr 1) * 4
                if (!inActive) {
                    out[off] = 0x80.toByte()
                    out[off + 1] = 0x80.toByte()
                    out[off + 2] = 0x80.toByte()
                    out[off + 3] = 0x80.toByte()
                } else {
                    val (y0, cb0, cr0) = sampleSyntheticPortraitYCbCr(x.toFloat(), y.toFloat(), faceCx, faceCy, faceHalf, rotDeg)
                    val (y1, cb1, cr1) = sampleSyntheticPortraitYCbCr((x + 1).toFloat(), y.toFloat(), faceCx, faceCy, faceHalf, rotDeg)
                    out[off] = y0.toByte()
                    out[off + 1] = y1.toByte()
                    out[off + 2] = ((cb0 + cb1) / 2).toByte()
                    out[off + 3] = ((cr0 + cr1) / 2).toByte()
                }
                x += 2
            }
        }
        return out
    }

    private fun sampleSyntheticPortraitYCbCr(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        half: Float,
        rotDeg: Int = 0,
    ): Triple<Int, Int, Int> {
        // Cool blue-green Tuscan backdrop
        var yy = 78
        var cb = 136
        var cr = 114
        val rx = (x - cx) / half
        val ry = (y - cy) / half
        // Inverse rotate screen offset into canonical upright face space:
        // +90 deg CW face (forehead at +X): canonical (nx, ny) = (ry, -rx)
        // -90 deg CCW face (forehead at -X): canonical (nx, ny) = (-ry, rx)
        val (nx, ny) = when (rotDeg) {
            90 -> ry to -rx
            -90 -> -ry to rx
            else -> rx to ry
        }
        // Dark chestnut hair halo
        if (hypot(nx / 1.25, (ny + 0.12) / 1.30) < 1.0) {
            yy = 28; cb = 118; cr = 134
        }
        // Warm illuminated face oval
        if (hypot(nx / 0.82, ny / 0.96) < 1.0) {
            yy = 165; cb = 100; cr = 152
            // Subtle cheek/forehead shading
            if (ny < -0.35f) yy = 175
            // Twin dark eyes + eyebrows at ny ≈ -0.32, nx ≈ +-0.36
            val dLeftEye = hypot(((nx + 0.36f) / 0.16f).toDouble(), ((ny + 0.32f) / 0.11f).toDouble())
            val dRightEye = hypot(((nx - 0.36f) / 0.16f).toDouble(), ((ny + 0.32f) / 0.11f).toDouble())
            if (dLeftEye < 1.0 || dRightEye < 1.0) {
                yy = 38; cb = 114; cr = 140
            }
            // Bright nose bridge between eyes
            if (abs(nx) < 0.14f && ny in -0.35f..0.15f) {
                yy = 188
            }
            // Subtle mouth shadow
            if (abs(nx) < 0.24f && ny in 0.34f..0.46f) {
                yy = 96; cr = 162
            }
        }
        return Triple(yy, cb, cr)
    }
}
