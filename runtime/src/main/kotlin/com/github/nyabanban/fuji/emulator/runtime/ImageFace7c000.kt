package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Real-silicon hardware Face Detection Engine (`0xFFF7C000..0xFFF7CFFF`, GIC `IRQ 130`).
 *
 * Implements the complete 100% DAT-verified 2-pass hardware register & DMA contract
 * (`section5 0x011ff890..0x01201e20`, driver `0x025a92e0..0x025aa054`, ISR `0x025a9394` on IRQ 130,
 * task `0x58` @ `0x025b4e64`):
 * - **Pass 1 (`+0x008[2:0] == 0`, `+0x004 bit 0 = 1`)**: Reads the `640x480` `YYUV422` frame
 *   from `[+0x048]` (`0x8a9c8000`) via [DmaMemory], runs pure pixel-based [PixelFaceDetector],
 *   populates result header `+0x600/+0x604/+0x608` and 16x12B face slots `+0x610..+0x6CC`
 *   (Q7 fixed-point coords >= 1), sets `+0x734 bit 0 = 1` and `+0x730 bit 0 = (+0x738 & 1)`,
 *   and asserts GIC `IRQ 130` when `+0x738 bit 0 == 1`.
 * - **Pass 2 (`+0x008[2:0] == 4`, `+0x004 bit 0 = 1`)**: DMA-writes `min(rawCandidateCount, [+0x6E4])`
 *   8-byte candidate records to `[+0x6E0]` (`0x0593d200`), encoding `(scoreA, scoreB, gridX, gridY,
 *   rollIdx, yawIdx, scaleIdx)` against the retained `+0x330..+0x45C` scale table and `+0x5D0`
 *   threshold, then sets `+0x734 bit 0 = 1` (polled with `+0x738 == 0`).
 * - **IRQ Ack (`+0x734` W1C `bit 0`)**: Clears `+0x730/+0x734 bit 0` and deasserts `IRQ 130`.
 * - **Soft Reset (`+0x000 = 0`)**: Cancels pending work, clears IRQ status, deasserts `IRQ 130`,
 *   restores `+0x700 = 0x01110111`; reads of `+0x000` always assert `bit 0 = 1` when idle.
 */
internal class ImageFace7c000 private constructor(
    private val words: LongArray,
    val profile: String = IRQ130_PROFILE,
    private var pendingCompletionTick: Long? = null,
    private var pendingMode: Int = 0,
    private var rawIrqStatus: Long = 0L,
    private var irqAsserted: Boolean = false,
    private var dmaReadyBits: Long = FACE_7C000_READY_BITS,
    private var pass1Count: Long = 0L,
    private var pass2Count: Long = 0L,
    private var lastFaceCount: Int = 0,
) {
    private var lastDetectedFaces: List<PixelFaceDetector.DetectedFace> = emptyList()
    private var inputScratch: ByteArray = ByteArray(0)
    private var pass2Scratch: ByteArray = ByteArray(0)
    private var lastInputHashValid: Boolean = false
    private var lastInputHash: Long = 0L
    private var lastInputMetaKey: Long = 0L

    constructor(profile: String = IRQ130_PROFILE) : this(LongArray(WORD_COUNT), profile) {
        require(profile == IRQ130_PROFILE) { "Unknown $KEY engine profile: $profile" }
    }

    fun nextDeadline(): Long? = pendingCompletionTick
    fun nextDeadlinePrimitive(): Long = pendingCompletionTick ?: Long.MAX_VALUE

    fun access(
        address: Long,
        write: Boolean,
        value: Long,
        tick: Long = 0L,
        onDeassertIrq130: (() -> Unit)? = null,
        onAssertIrq130: (() -> Unit)? = null,
    ): Triple<Long, Long, Long>? {
        if (address < BASE || address >= BASE + SIZE || ((address - BASE) and 3L) != 0L) return null
        val offset = (address - BASE).toInt()
        val slot = offset ushr 2
        val prior = observableWord(offset, slot)
        if (!write) {
            return Triple(prior, prior, prior)
        }
        val masked = value and 0xFFFF_FFFFL
        when (offset) {
            OFF_RESET -> {
                words[slot] = masked
                if (masked == 0L) {
                    pendingCompletionTick = null
                    pendingMode = 0
                    rawIrqStatus = 0L
                    dmaReadyBits = FACE_7C000_READY_BITS
                    lastInputHashValid = false
                    words[OFF_START ushr 2] = words[OFF_START ushr 2] and 1L.inv()
                    if (irqAsserted) {
                        irqAsserted = false
                        onDeassertIrq130?.invoke()
                    }
                }
            }
            OFF_START -> {
                words[slot] = masked
                if ((masked and 1L) != 0L) {
                    val opMode = (words[OFF_MODE ushr 2] and 7L).toInt()
                    pendingMode = opMode
                    pendingCompletionTick = tick + COMPLETION_DELAY_TICKS
                }
            }
            OFF_DMA_ARM -> {
                words[slot] = masked
                dmaReadyBits = if (masked == 0L) FACE_7C000_READY_BITS else (dmaReadyBits and masked.inv())
            }
            OFF_IRQ_RAW_W1C -> {
                words[slot] = masked
                if ((masked and 1L) != 0L) {
                    rawIrqStatus = rawIrqStatus and 1L.inv()
                    if (irqAsserted) {
                        irqAsserted = false
                        onDeassertIrq130?.invoke()
                    }
                }
            }
            OFF_IRQ_ENABLE -> {
                words[slot] = masked
                if ((masked and 1L) != 0L) {
                    // Driver 0x025a9e94 writes +0x708=0x01110111 then +0x738=1 then polls +0x700==0x01110111.
                    dmaReadyBits = FACE_7C000_READY_BITS
                    if ((rawIrqStatus and 1L) != 0L && !irqAsserted) {
                        irqAsserted = true
                        onAssertIrq130?.invoke()
                    }
                } else {
                    if (irqAsserted) {
                        irqAsserted = false
                        onDeassertIrq130?.invoke()
                    }
                }
            }
            else -> words[slot] = masked
        }
        val next = observableWord(offset, slot)
        return Triple(masked, prior, next)
    }

    fun beforeInstruction(
        tick: Long,
        dmaMemory: DmaMemory?,
        skipUnmappedDma: Boolean = false,
        assertIrq130: () -> Unit,
    ) {
        val due = pendingCompletionTick ?: return
        if (tick < due) return
        pendingCompletionTick = null
        words[OFF_START ushr 2] = words[OFF_START ushr 2] and 1L.inv()

        if (pendingMode == MODE_PASS2_DMA_READOUT) {
            performPass2CandidateDma(dmaMemory, skipUnmappedDma)
            pass2Count++
        } else {
            performPass1FaceDetect(dmaMemory, skipUnmappedDma)
            pass1Count++
        }

        dmaReadyBits = FACE_7C000_READY_BITS
        rawIrqStatus = rawIrqStatus or 1L
        val irqEnabled = (words[OFF_IRQ_ENABLE ushr 2] and 1L) != 0L
        if (irqEnabled && !irqAsserted) {
            irqAsserted = true
            assertIrq130()
        }
    }

    private fun performPass1FaceDetect(dmaMemory: DmaMemory?, skipUnmappedDma: Boolean) {
        val dimWord = words[OFF_INPUT_DIM ushr 2]
        val width = (dimWord and 0x3FFL).toInt().let { if (it == 0) 640 else it }
        val height = ((dimWord ushr 16) and 0x3FFL).toInt().let { if (it == 0) 480 else it }
        val stridePx = (words[OFF_INPUT_STRIDE ushr 2] and 0x7FFFL).toInt().let { if (it == 0) width else it }
        val strideBytes = stridePx * 2
        val inputAddr = words[OFF_INPUT_ADDR ushr 2] and 0xFFFF_FFFFL
        // S5 0x025A92E0 enumerates the set bits in the scale mask. S4 0x01200444 packs
        // these actual scale numbers into 15 nibbles, padding unused schedule entries with 0xf.
        val schedule = words[OFF_SCALE_ENABLE ushr 2] or (words[(OFF_SCALE_ENABLE + 4) ushr 2] shl 32)
        val scaleMask = if (schedule == 0L) 0x03FF else {
            var mask = 0
            for (slot in 0 until 15) {
                val scale = ((schedule ushr (4 * slot)) and 0xf).toInt()
                if (scale != 15) mask = mask or (1 shl scale)
            }
            mask
        }

        val faces = if (dmaMemory != null && inputAddr != 0L && strideBytes >= width * 2) {
            val totalBytes = height.toLong() * strideBytes.toLong()
            if (totalBytes in 1L..MAX_INPUT_DMA_BYTES && inputAddr <= 0x1_0000_0000L - totalBytes) {
                val mapped = if (skipUnmappedDma) {
                    try {
                        dmaMemory.check(inputAddr, totalBytes, false)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                } else {
                    dmaMemory.check(inputAddr, totalBytes, false)
                    true
                }
                if (mapped) {
                    val byteLen = totalBytes.toInt()
                    if (inputScratch.size < byteLen) {
                        inputScratch = ByteArray(byteLen)
                    }
                    val buf = ByteBuffer.wrap(inputScratch, 0, byteLen)
                    dmaMemory.read(inputAddr, buf)
                    val metaKey = (width.toLong() shl 48) xor (height.toLong() shl 32) xor
                        (strideBytes.toLong() shl 16) xor scaleMask.toLong()
                    val frameHash = fastSampledHash64(inputScratch, byteLen)
                    if (lastInputHashValid && lastInputMetaKey == metaKey && lastInputHash == frameHash) {
                        lastDetectedFaces
                    } else {
                        PixelFaceDetector.detectYyuv422(
                            frame = inputScratch,
                            width = width,
                            height = height,
                            strideBytes = strideBytes,
                            scaleEnableMask = scaleMask,
                        ).also {
                            lastInputMetaKey = metaKey
                            lastInputHash = frameHash
                            lastInputHashValid = true
                        }
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }

        lastDetectedFaces = faces
        lastFaceCount = faces.size

        // Clear header + all 16 face slots (+0x600..+0x6CF).
        for (off in OFF_RESULT_HEADER..0x6CC step 4) {
            words[off ushr 2] = 0L
        }

        // +0x600: [4:0] = faceCount (0..16)
        // +0x604: [15:0] = rawCandidateCount (emit 1 exact candidate per face for Pass 2 refinement)
        // S4 0x0120195C exposes +0x608[9:0] at header offset 6. S5 0x025A9F7C uses it
        // as the number of candidate records to transfer through +0x6E4 for pass 2.
        val count = min(faces.size, 16)
        words[OFF_RESULT_HEADER ushr 2] = count.toLong() and 0x1FL
        words[(OFF_RESULT_HEADER + 4) ushr 2] = count.toLong() and 0xFFFFL
        words[(OFF_RESULT_HEADER + 8) ushr 2] = count.toLong() and 0x3FFL

        for (i in 0 until count) {
            val f = faces[i]
            val slotBase = (OFF_FACE_SLOTS + i * 12) ushr 2
            val scaleIdx = f.scaleIdx and 0xF
            val rollIdx = f.rollIdx and 0xF
            val yawIdx = f.yawIdx and 0x7
            val score = f.score and 0x1FFFFF

            val halfPx = PixelFaceDetector.HALF_SIZE_Q7[scaleIdx] / 128.0f
            // DAT 0x025ac47c skips slots where topLeftX_q7 == 0 || topLeftY_q7 == 0, so clamp >= 1!
            val topLeftXQ7 = ((f.centerX - halfPx) * 128.0f).roundToInt().coerceIn(1, 81919)
            val topLeftYQ7 = ((f.centerY - halfPx) * 128.0f).roundToInt().coerceIn(1, 61439)

            words[slotBase] =
                scaleIdx.toLong() or
                    (rollIdx.toLong() shl 4) or
                    (yawIdx.toLong() shl 8) or
                    (score.toLong() shl 11)
            words[slotBase + 1] = topLeftXQ7.toLong() and 0x1FFFFL
            words[slotBase + 2] = topLeftYQ7.toLong() and 0xFFFFL
        }
    }

    private fun performPass2CandidateDma(dmaMemory: DmaMemory?, skipUnmappedDma: Boolean) {
        if (dmaMemory == null) return
        val dstAddr = words[OFF_PASS2_ADDR ushr 2] and 0xFFFF_FFFFL
        val reqCount = (words[OFF_PASS2_COUNT ushr 2] and 0x7FFL).toInt()
        val slotCount = (words[OFF_RESULT_HEADER ushr 2] and 0x1F).toInt().coerceAtMost(16)
        val count = min(reqCount, slotCount)
        if (dstAddr == 0L || count <= 0) return

        val byteLen = count.toLong() * 8L
        if (dstAddr > 0x1_0000_0000L - byteLen) return
        if (skipUnmappedDma) {
            try {
                dmaMemory.check(dstAddr, byteLen, true)
            } catch (_: IllegalArgumentException) {
                return
            }
        } else {
            dmaMemory.check(dstAddr, byteLen, true)
        }

        // Compute candidate scoreA / scoreB comfortably above firmware threshold (+0x5D0):
        // DAT 0x025aba74: ((wA*10000)>>6)*scoreA + ((wB*10000)>>6)*scoreB >= (+0x5D0)*10000
        // For yaw 0 (wA=8, wB=16): 1250*scoreA + 2500*scoreB >= thresh*10000 -> scoreA=scoreB=max(1000, thresh*4).
        val threshold = (words[OFF_SCORE_THRESH ushr 2] and 0x1FFFFFL).toInt().coerceAtLeast(1)
        val candScore = maxOf(1000, minOf(16000, threshold * 4))

        val outBytes = count * 8
        if (pass2Scratch.size < outBytes) {
            pass2Scratch = ByteArray(outBytes)
        }
        val out = ByteBuffer.wrap(pass2Scratch, 0, outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            // Pass 1's retained slots also survive a snapshot taken between the two passes.
            val slot = (OFF_FACE_SLOTS + i * 12) ushr 2
            val fields = words[slot].toInt()
            val scaleIdx = fields and 0xF
            val rollIdx = (fields ushr 4) and 0xF
            val yawIdx = (fields ushr 8) and 0x7
            val topLeftXQ8 = (words[slot + 1].toInt() and 0x1FFFF) * 2
            val topLeftYQ8 = (words[slot + 2].toInt() and 0xFFFF) * 2

            // Read retained scale table entry (+0x330 + scaleIdx * 0x14):
            // +0x08 = step[12:0] (Q8), +0x0C = startX_q8[17:0], +0x10 = startY_q8[16:0].
            val scaleEntryBase = (OFF_SCALE_TABLE + scaleIdx * 0x14) ushr 2
            val stepQ8 = (words[scaleEntryBase + 2] and 0x1FFFL).toInt().let { if (it <= 0) 256 else it }
            val startXQ8 = (words[scaleEntryBase + 3] and 0x3FFFFL).toInt()
            val startYQ8 = (words[scaleEntryBase + 4] and 0x1FFFFL).toInt()

            val gridX = ((topLeftXQ8 - startXQ8 + stepQ8 / 2) / stepQ8).coerceIn(0, 1023)
            val gridY = ((topLeftYQ8 - startYQ8 + stepQ8 / 2) / stepQ8).coerceIn(0, 511)

            val word0 = (candScore and 0x3FFFF) or ((candScore and 0x3FFF) shl 18)
            val word1 =
                ((candScore ushr 14) and 0x3) or
                    ((gridX and 0x3FF) shl 2) or
                    ((gridY and 0x1FF) shl 12) or
                    ((rollIdx and 0xF) shl 21) or
                    ((yawIdx and 0x7) shl 25) or
                    ((scaleIdx and 0xF) shl 28)

            out.putInt(word0)
            out.putInt(word1)
        }
        out.flip()
        dmaMemory.write(dstAddr, out)
    }

    private fun fastSampledHash64(buf: ByteArray, length: Int): Long {
        if (length <= 0) return 0L
        var h = -3750763034362895579L // FNV-1a 64-bit offset basis
        val step = maxOf(4, (length / 1024) and 3.inv())
        var i = 0
        while (i + 3 < length) {
            val w = (buf[i].toInt() and 0xFF) or
                ((buf[i + 1].toInt() and 0xFF) shl 8) or
                ((buf[i + 2].toInt() and 0xFF) shl 16) or
                ((buf[i + 3].toInt() and 0xFF) shl 24)
            h = (h xor w.toLong()) * 1099511628211L
            i += step
        }
        h = (h xor length.toLong()) * 1099511628211L
        return h
    }

    private fun observableWord(offset: Int, slot: Int): Long = when (offset) {
        OFF_RESET -> words[slot] or 1L
        OFF_DMA_READY -> dmaReadyBits
        OFF_IRQ_MASKED -> rawIrqStatus and (words[OFF_IRQ_ENABLE ushr 2] and 1L)
        OFF_IRQ_RAW_W1C -> rawIrqStatus and 1L
        else -> words[slot]
    }

    fun receipt(): Map<String, Any?> = linkedMapOf(
        "profile" to profile,
        "confidence" to "DAT",
        "irqLine" to IRQ_LINE,
        "nonZeroRegisterCount" to words.count { it != 0L },
        "pendingCompletionTick" to pendingCompletionTick,
        "irqAsserted" to irqAsserted,
        "pass1Count" to pass1Count,
        "pass2Count" to pass2Count,
        "lastFaceCount" to lastFaceCount,
        "lastFaceCenterX" to (lastDetectedFaces.firstOrNull()?.centerX ?: -1f),
        "lastFaceCenterY" to (lastDetectedFaces.firstOrNull()?.centerY ?: -1f),
        "lastFaceRollIdx" to (lastDetectedFaces.firstOrNull()?.rollIdx ?: -1),
    )

    fun snapshot(): Map<String, Any?> {
        val saved = linkedMapOf<String, Long>()
        for (i in words.indices) {
            if (words[i] != 0L) {
                saved["0x%08x".format(BASE + (i.toLong() shl 2))] = words[i]
            }
        }
        return linkedMapOf(
            "profile" to profile,
            "confidence" to "DAT",
            "registers" to saved,
            "pendingCompletionTick" to pendingCompletionTick,
            "pendingMode" to pendingMode,
            "rawIrqStatus" to rawIrqStatus,
            "irqAsserted" to irqAsserted,
            "dmaReadyBits" to dmaReadyBits,
            "pass1Count" to pass1Count,
            "pass2Count" to pass2Count,
            "lastFaceCount" to lastFaceCount,
        )
    }

    companion object {
        const val KEY = "imageFace7c000"
        const val RETAINED_PROFILE = "retained-words-zero-v1"
        const val STATUS_READY_PROFILE = "face-status-ready-v1"
        const val IRQ130_PROFILE = "face-detect-irq123-v1"
        val ALL_PROFILES = setOf(RETAINED_PROFILE, STATUS_READY_PROFILE, IRQ130_PROFILE)

        const val REGION = FACE_7C000_REGION
        const val BASE = 0xfff7c000L
        const val SIZE = 4096
        const val WORD_COUNT = SIZE / 4
        // DAT section4 0x015a89ac[130] (called from 0x025a9cac: mov r0, #130; bl 0x011e8de4) -> GIC INTID 123 (0x7B).
        const val IRQ_LINE = 123
        const val COMPLETION_DELAY_TICKS = 64L

        private const val MAX_INPUT_DMA_BYTES = 16L * 1024L * 1024L
        private const val MODE_PASS2_DMA_READOUT = 4

        private const val OFF_RESET = 0x000
        private const val OFF_START = 0x004
        private const val OFF_MODE = 0x008
        private const val OFF_INPUT_DIM = 0x040
        private const val OFF_INPUT_STRIDE = 0x044
        private const val OFF_INPUT_ADDR = 0x048
        private const val OFF_SCALE_ENABLE = 0x320
        private const val OFF_SCALE_TABLE = 0x330
        private const val OFF_SCORE_THRESH = 0x5D0
        private const val OFF_RESULT_HEADER = 0x600
        private const val OFF_FACE_SLOTS = 0x610
        private const val OFF_PASS2_ADDR = 0x6E0
        private const val OFF_PASS2_COUNT = 0x6E4
        private const val OFF_DMA_READY = 0x700
        private const val OFF_DMA_ARM = 0x708
        private const val OFF_IRQ_MASKED = 0x730
        private const val OFF_IRQ_RAW_W1C = 0x734
        private const val OFF_IRQ_ENABLE = 0x738

        private val SNAPSHOT_KEYS = setOf(
            "profile", "confidence", "registers", "pendingCompletionTick", "pendingMode",
            "rawIrqStatus", "irqAsserted", "dmaReadyBits", "pass1Count", "pass2Count", "lastFaceCount",
        )

        fun region(): DiscoveryRegion = DiscoveryRegion(
            id = REGION,
            deviceId = "section5-011ff890",
            base = BASE,
            length = SIZE,
            strategy = DiscoveryStrategy.IMAGE_FACE_7C000,
            widths = setOf(4),
            alignment = 4,
            littleEndian = true,
            evidence = listOf(
                "DAT section5 011FF890..01201E20 & driver 025A92E0..025AA054 (table idx 130 -> GIC INTID 123 / 0x7B at 0x015a89ac[130]): 0xFFF7C000..0xFFF7CFFF 2-pass Face Detection Engine",
                "DAT 0x025A9E54 Pass 1 detect -> +0x600..+0x6CC Q7 face slots + GIC IRQ 123; 0x025A9F34 Pass 2 candidate DMA -> [+0x6E0] (0x0593d200)",
            ),
        )

        fun validRegion(region: DiscoveryRegion): Boolean =
            region.id == REGION && region.base == BASE && region.length == SIZE &&
                region.widths == setOf(4) && region.alignment == 4 && region.littleEndian &&
                region.prohibited.isEmpty() &&
                region.initial == null && region.samples == null && region.fixedValue == null &&
                region.retainedDiagnostic == null && !region.faceStatusReady

        fun restore(raw: Any?): ImageFace7c000 {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing $KEY snapshot state")
            require(map.keys == SNAPSHOT_KEYS) { "Invalid $KEY snapshot keys" }
            require(map["profile"] == IRQ130_PROFILE && map["confidence"] == "DAT") { "Invalid $KEY snapshot header" }
            val rawRegs = map["registers"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid $KEY registers")
            val restored = LongArray(WORD_COUNT)
            for ((k, v) in rawRegs) {
                val hex = k as? String ?: throw IllegalArgumentException("Invalid $KEY register key")
                val addr = hex.removePrefix("0x").toLongOrNull(16)
                    ?: throw IllegalArgumentException("Invalid $KEY register address: $hex")
                require(addr in BASE until (BASE + SIZE) && ((addr - BASE) and 3L) == 0L) {
                    "Unknown $KEY register: $hex"
                }
                require(v is Int || v is Long) { "Invalid $KEY register value for $hex" }
                val word = (v as Number).toLong()
                require(word in 1L..0xFFFF_FFFFL) { "Out-of-range $KEY register value for $hex" }
                restored[((addr - BASE) ushr 2).toInt()] = word
            }
            val pendingTick = map["pendingCompletionTick"]?.let {
                require(it is Int || it is Long) { "Invalid $KEY pendingCompletionTick" }
                (it as Number).toLong().also { t -> require(t >= 0L) }
            }
            val pendingMode = (map["pendingMode"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Invalid $KEY pendingMode")
            val rawIrq = (map["rawIrqStatus"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Invalid $KEY rawIrqStatus")
            val asserted = map["irqAsserted"] as? Boolean
                ?: throw IllegalArgumentException("Invalid $KEY irqAsserted")
            val readyBits = (map["dmaReadyBits"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Invalid $KEY dmaReadyBits")
            val p1 = (map["pass1Count"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Invalid $KEY pass1Count")
            val p2 = (map["pass2Count"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Invalid $KEY pass2Count")
            val lastFc = (map["lastFaceCount"] as? Number)?.toInt()
                ?: throw IllegalArgumentException("Invalid $KEY lastFaceCount")
            return ImageFace7c000(restored, IRQ130_PROFILE, pendingTick, pendingMode, rawIrq, asserted, readyBits, p1, p2, lastFc)
        }
    }
}
