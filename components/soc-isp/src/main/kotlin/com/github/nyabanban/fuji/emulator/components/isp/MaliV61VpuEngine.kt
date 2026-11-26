package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/**
 * ARM Mali-V61 H.265/H.264 Hardware Video Processing Unit (`0xFFE00000..0xFFE002FF`, GIC IRQ 138).
 *
 * Register layout, L1->L2 MMU, and MVE v2.4 6-ring Host<->RASC protocol verified against GFX100S 2.13 `section4.bin`:
 * - Global bank `+0x00..+0x50` (`0x01207334..0x01207504`, `0x01208D00..0x01208E74`):
 *   - `+0x00 VERSION`: `0x56610002` (Mali-V61 proto v2.4, RO)
 *   - `+0x04 ENABLE`: scheduler enable (`0` on reset, R/W; polled `0` by `h265_DisableScheduler` at `0x0120734C`)
 *   - `+0x08 NCORES`: `0x00010000` (RO)
 *   - `+0x0C NLSID`: `4` (RO)
 *   - `+0x10 CORELSID`: reset sets `0x0000FFFF` (polled by `h265_SoftReset` at `0x012074C0`)
 *   - `+0x14 JOBQUEUE`: reset sets `0x0F0F0F0F` (4 empty LSID slots, `0x012073B4..0x01207478`)
 *   - `+0x18 IRQVE`: RO pending VPU->Host LSID bitmask (`h265Int_Handler` `0x01204470`)
 *   - `+0x20 CLKIDLE`: RO idle clock status (`1`)
 *   - `+0x24 CLKFORCE`, `+0x44 BUSCTRL`: written `0x100` and `0x2` after soft reset (`0x012074F8..01207500`)
 *   - `+0x50 RESET`: WO write `1` triggers soft reset (`0x012074B4`)
 * - 4x Per-LSID banks at `+0x200 + lsid * 0x40` (`0x01207508..0x0120763C`):
 *   - `+0x00 CTRL`, `+0x04 MMU_CTRL` (`(L1_PA >> 10) | 3`), `+0x08 NPROT`, `+0x0C ALLOC`, `+0x10 FLUSH_ALL`, `+0x14 SCHED`
 *   - `+0x18 TERMINATE`: write `1` self-clears to `0` (`h265_MapSession` polls `0` at `0x01207598`)
 *   - `+0x1C IRQVE`: VPU->Host IRQ latch; writing `0` clears LSID pending bit (`h265Int_Handler` `0x01204488`)
 *   - `+0x20 IRQHOST`: Host->VPU doorbell; writing `1` schedules RASC MVE message processing & IRQ 138
 *   - `+0x24 INTSIG`, `+0x2C STREAMID`, `+0x30..+0x3C BUSATR0..3`
 * - MVE v2.4 Shared Communication Rings (`0x01205218..0x01208600`):
 *   - Mapped at MVE VAs `0x10079000..0x1007F000` (`MSG_IN`, `MSG_OUT`, `BUF_IN_HOST`, `BUF_IN_MVE`,
 *     `BUF_OUT_HOST`, `BUF_OUT_MVE`, `RPC`), each 1020 u32 words (`0x3FC`) at page `+0x10`.
 */
internal class MaliV61VpuEngine(
    private val clockProvider: () -> Long,
    private val sinkProvider: () -> InterruptSink?,
    private val dmaMemoryProvider: () -> DmaMemory? = { null },
    private val onVideoFramePeriodChanged: ((Long?) -> Unit)? = null,
) {
    companion object {
        const val CLOCK_HZ = 54_000_000L
        const val PORT_ID = "soc-isp.mali-v61"
        const val WINDOW_SIZE = 0x300L
        const val IRQ_LINE = 138
        const val COMPLETION_DELAY_TICKS = 64L

        const val VERSION_WORD = 0x5661_0002L // Mali-V61 proto v2.4
        const val NCORES_WORD = 0x0001_0000L
        const val NLSIDS_WORD = 0x0000_0004L
        const val CORELSID_IDLE = 0x0000_FFFFL
        const val JOBQUEUE_EMPTY = 0x0F0F_0F0FL

        private const val GLOBAL_WORDS = 21 // 0x00..0x50 inclusive (21 u32 words)
        private val UNAVAILABLE_PCM = MvePcmSource { _, _, _ -> false }
        private const val LSID_COUNT = 4
        private const val LSID_WORDS = 16 // 0x40 bytes per LSID
        private const val LSID_BASE = 0x200
        private const val LSID_STRIDE = 0x40

        private const val OFF_VERSION = 0x00
        private const val OFF_ENABLE = 0x04
        private const val OFF_NCORES = 0x08
        private const val OFF_NLSIDS = 0x0C
        private const val OFF_CORELSID = 0x10
        private const val OFF_JOBQUEUE = 0x14
        private const val OFF_IRQVE = 0x18
        private const val OFF_CLKIDLE = 0x20
        private const val OFF_TIMER = 0x28
        private const val OFF_RESET = 0x50

        private const val LSID_OFF_MMU_CTRL = 0x04
        private const val LSID_OFF_FLUSH_ALL = 0x10
        private const val LSID_OFF_TERMINATE = 0x18
        private const val LSID_OFF_IRQVE = 0x1C
        private const val LSID_OFF_IRQHOST = 0x20

        // Fixed MVE v2.4 communication area VAs (section4 0x01206958..0x01206A7C)
        private const val MVE_VA_MSG_IN = 0x1007_9000L
        private const val MVE_VA_MSG_OUT = 0x1007_A000L
        private const val MVE_VA_BUF_IN_HOST = 0x1007_B000L
        private const val MVE_VA_BUF_IN_MVE = 0x1007_C000L
        private const val MVE_VA_BUF_OUT_HOST = 0x1007_D000L
        private const val MVE_VA_BUF_OUT_MVE = 0x1007_E000L
        private const val RING_WORDS = 1020 // 0x3FC u32 words at page +0x10

        // MVE v2.4 request / response / buffer message codes (section4 0x01205900..0x012085E0)
        private const val REQ_CODE_GO = 1001
        private const val REQ_CODE_STOP = 1002
        private const val REQ_CODE_INPUT_FLUSH = 1003
        private const val REQ_CODE_OUTPUT_FLUSH = 1004
        private const val REQ_CODE_PING = 1007
        private const val REQ_CODE_DUMP = 1008
        private const val REQ_CODE_JOB = 1009
        private const val REQ_CODE_SET_OPTION = 1010

        private const val RSP_CODE_SWITCHED_IN = 2001
        private const val RSP_CODE_SET_OPTION = 2003
        private const val RSP_CODE_INPUT = 2005
        private const val RSP_CODE_OUTPUT = 2006
        private const val RSP_CODE_IN_FLUSHED = 2007
        private const val RSP_CODE_OUT_FLUSHED = 2008
        private const val RSP_CODE_PONG = 2009
        private const val RSP_CODE_ERROR = 2010
        private const val RSP_CODE_STATE_CHANGE = 2011
        private const val RSP_CODE_DUMP = 2012
        private const val RSP_CODE_SEQUENCE_PARAMETERS = 2015

        private const val BUF_CODE_FRAME = 3001
        private const val BUF_CODE_BITSTREAM = 3002
        private const val BUF_CODE_PARAM = 3003

        private const val HEADER_HANDLE_MASK = 0xFFFF_FFE0L
        private const val HEADER_HANDLE_PREFIX = 0x4B0D_A000L
        private const val DECODE_BITSTREAM_EOS = 0x01
        private const val DECODE_FRAME_PICTURE_PRESENT = 0x04
        private const val DECODE_FRAME_CORRUPT = 0x2000
        private const val DECODE_FRAME_EOS = 0x10000
        private const val FRAME_FLAG_EOS = 0x0001_0000
        private const val BITSTREAM_FLAG_EOS = 0x01
        private const val BITSTREAM_FLAG_ENDOFFRAME = 0x10
        private const val BITSTREAM_FLAG_SYNCFRAME = 0x20
        private const val BITSTREAM_FLAG_ENDOFSUBFRAME = 0x400
        private const val BITSTREAM_FLAG_CODECCONFIG = 0x80
        private const val MVE_STATE_STOPPED = 0
        private const val MVE_STATE_RUNNING = 1

        private val SNAPSHOT_KEYS = setOf(
            "globalWords", "lsidWords", "pendingTicks", "irqAsserted",
            "resetCount", "doorbellCount", "completionCount",
        )

        /**
         * Convert firmware Q16.16 movie frame rate (`BUF_CODE_PARAM` opt 9, e.g. `3928227` = `59.94 fps`)
         * to exact 54 MHz SoC ticks per frame so ISP frame IRQs remain 1:1 phase-locked with 48 kHz I2S
         * audio DMA during long movie recording (avoiding audio capture ring overflow at 27 Hz fallback).
         */
        fun framePeriodTicksForFpsQ16(fpsQ16: Long): Long {
            if (fpsQ16 <= 0L) return 900_900L
            val fps = fpsQ16.toDouble() / 65536.0
            return when {
                fps in 59.9..59.98 -> 900_900L       // 54,000,000 * 1001 / 60,000 (59.94 fps NTSC, exact)
                fps > 59.98 && fps <= 60.5 -> 900_000L
                fps in 49.5..50.5 -> 1_080_000L      // 50.00 fps exact
                fps in 29.9..29.99 -> 1_801_800L     // 54,000,000 * 1001 / 30,000 (29.97 fps NTSC, exact)
                fps > 29.99 && fps <= 30.5 -> 1_800_000L
                fps in 24.5..25.5 -> 2_160_000L      // 25.00 fps exact
                fps in 23.9..23.99 -> 2_252_250L     // 54,000,000 * 1001 / 24,000 (23.976 fps NTSC, exact)
                fps > 23.99 && fps <= 24.5 -> 2_250_000L
                else -> Math.round((CLOCK_HZ * 65536.0) / fpsQ16.toDouble()).coerceIn(225_000L, 4_500_000L)
            }
        }
    }

    private data class QueuedFrame(val rawWords: IntArray)
    private data class QueuedBitstream(val rawWords: IntArray)

    private class LsidSession {
        var running = false
        var headersEmitted = false
        var retryHeaders = false
        var naluFormat = 16
        var pFrames = 0
        var framesSinceIdr = 0
        var avcReference: AvcReferenceFrame? = null
        var hevcReference: HevcReferenceFrame? = null
        var rateControlMode = 0
        var targetBitrate = 0L
        var rateCredit = 0L
        var sawH264BitDepthOpt = false
        var hevcHeadersEmitted = false
        var hevcPcmEnabled = false
        var fpsQ16 = 3928227L // 59.94 fps default
        var encodedFrames = 0
        val pendingFrames = ArrayDeque<QueuedFrame>()
        val pendingHeaders = ArrayDeque<QueuedBitstream>()
        val pendingBitstreams = ArrayDeque<QueuedBitstream>()

        var needsSequenceNegotiation = true
        var awaitingOutputFlush = false
        var decodeReadyTick = 0L
        var decodeWaitStartTick = 0L
        var endOfStreamTag: Long? = null
        var decoder: MaliV61PlaybackDecoder? = null
        val decodeTags = ArrayDeque<Long>()
        val decodeSurfaces = ArrayDeque<IntArray>()

        fun resetDecoder() {
            decoder?.close()
            decoder = null
            needsSequenceNegotiation = true
            awaitingOutputFlush = false
            decodeReadyTick = 0L
            decodeWaitStartTick = 0L
            endOfStreamTag = null
            decodeTags.clear()
            decodeSurfaces.clear()
        }

        fun reset() {
            resetDecoder()
            running = false
            headersEmitted = false
            retryHeaders = false
            naluFormat = 16
            pFrames = 0
            resetGop()
            rateControlMode = 0
            targetBitrate = 0L
            rateCredit = 0L
            sawH264BitDepthOpt = false
            hevcHeadersEmitted = false
            hevcPcmEnabled = false
            fpsQ16 = 3928227L
            encodedFrames = 0
            pendingFrames.clear()
            pendingHeaders.clear()
            pendingBitstreams.clear()
        }

        fun resetGop() {
            avcReference = null
            hevcReference = null
            framesSinceIdr = 0
        }
    }

    private val globalWords = LongArray(GLOBAL_WORDS)
    private val lsidWords = Array(LSID_COUNT) { LongArray(LSID_WORDS) }
    private val pendingTicks = arrayOfNulls<Long>(LSID_COUNT)
    private val sessions = Array(LSID_COUNT) { LsidSession() }
    private var irqAsserted = false
    private var resetCount = 0L
    private var doorbellCount = 0L
    private var completionCount = 0L
    private var wroteResponse = false
    private var framesDecodedCount = 0L
    private var decodeErrorCount = 0L
    private var framesEncodedCount = 0L
    private var encodeErrorCount = 0L
    private var bytesEncodedCount = 0L

    private val wordBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    private val shortBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
    private val mvePageBuf = ByteArray(4096)
    private val mvePageView = ByteBuffer.wrap(mvePageBuf)
    private var pcmStrip = ByteBuffer.allocate(0)

    init {
        resetState(notify = false)
    }

    fun activeVideoFramePeriodTicks(): Long? {
        for (s in sessions) {
            if (s.running && s.decoder == null) return framePeriodTicksForFpsQ16(s.fpsQ16)
        }
        return null
    }

    private fun notifyVideoFramePeriodChanged() {
        onVideoFramePeriodChanged?.invoke(activeVideoFramePeriodTicks())
    }

    private fun resetState(notify: Boolean = true) {
        globalWords.fill(0L)
        globalWords[OFF_VERSION / 4] = VERSION_WORD
        globalWords[OFF_ENABLE / 4] = 0L
        globalWords[OFF_NCORES / 4] = NCORES_WORD
        globalWords[OFF_NLSIDS / 4] = NLSIDS_WORD
        globalWords[OFF_CORELSID / 4] = CORELSID_IDLE
        globalWords[OFF_JOBQUEUE / 4] = JOBQUEUE_EMPTY
        globalWords[OFF_CLKIDLE / 4] = 1L
        for (i in 0 until LSID_COUNT) {
            lsidWords[i].fill(0L)
            pendingTicks[i] = null
            sessions[i].reset()
        }
        updateIrq()
        if (notify) notifyVideoFramePeriodChanged()
    }

    private fun computeIrqVeMask(): Long {
        var mask = 0L
        for (i in 0 until LSID_COUNT) {
            if (lsidWords[i][LSID_OFF_IRQVE / 4] != 0L) {
                mask = mask or (1L shl i)
            }
        }
        return mask
    }

    private fun updateIrq() {
        val high = computeIrqVeMask() != 0L
        if (high != irqAsserted) {
            irqAsserted = high
            sinkProvider()?.setLine(IRQ_LINE, high)
        }
    }

    fun syncIrqLine() {
        sinkProvider()?.setLine(IRQ_LINE, irqAsserted)
    }

    fun clearIrqOnClose() {
        sessions.forEach { it.resetDecoder() }
        if (irqAsserted) {
            irqAsserted = false
            sinkProvider()?.setLine(IRQ_LINE, false)
        }
    }

    private fun readU32(mem: DmaMemory, pa: Long): Long? {
        return try {
            mem.check(pa, 4L, false)
            wordBuf.clear()
            mem.read(pa, wordBuf)
            wordBuf.getInt(0).toLong() and 0xFFFF_FFFFL
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeU32(mem: DmaMemory, pa: Long, value: Int): Boolean {
        return try {
            mem.check(pa, 4L, true)
            wordBuf.clear()
            wordBuf.putInt(0, value)
            mem.write(pa, wordBuf)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readU16(mem: DmaMemory, pa: Long): Int? {
        return try {
            mem.check(pa, 2L, false)
            shortBuf.clear()
            mem.read(pa, shortBuf)
            shortBuf.getShort(0).toInt() and 0xFFFF
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeU16(mem: DmaMemory, pa: Long, value: Int): Boolean {
        return try {
            mem.check(pa, 2L, true)
            shortBuf.clear()
            shortBuf.putShort(0, value.toShort())
            mem.write(pa, shortBuf)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Translate an MVE 32-bit virtual address via the LSID's 2-level MMU page table (`MMU_CTRL`). */
    private fun translateMveVa(mem: DmaMemory, l1Base: Long, mveVa: Long): Long? {
        val va = mveVa and 0xFFFF_FFFFL
        val l1Idx = (va ushr 22) and 0x3FFL
        val l1Entry = readU32(mem, l1Base + (l1Idx shl 2)) ?: return null
        if ((l1Entry and 3L) == 0L) return null
        val l2Base = (l1Entry and 0x3FFF_FFFCL) shl 10
        val l2Idx = (va ushr 12) and 0x3FFL
        val l2Entry = readU32(mem, l2Base + (l2Idx shl 2)) ?: return null
        if ((l2Entry and 3L) == 0L) return null
        val pagePa = (l2Entry and 0x3FFF_FFFCL) shl 10
        return pagePa or (va and 0xFFFL)
    }

    private fun writeMveBytes(mem: DmaMemory, l1Base: Long, mveVa: Long, src: ByteArray, length: Int = src.size): Boolean {
        if (length !in 0..src.size || mveVa < 0 || mveVa + length > 0x1_0000_0000L) return false
        var remaining = length
        var srcOff = 0
        var va = mveVa and 0xFFFF_FFFFL
        while (remaining > 0) {
            val pa = translateMveVa(mem, l1Base, va) ?: return false
            val pageRem = (0x1000L - (va and 0xFFFL)).toInt()
            val chunk = minOf(remaining, pageRem)
            try {
                mem.check(pa, chunk.toLong(), true)
                mem.write(pa, ByteBuffer.wrap(src, srcOff, chunk))
            } catch (_: Throwable) {
                return false
            }
            srcOff += chunk
            va = (va + chunk.toLong()) and 0xFFFF_FFFFL
            remaining -= chunk
        }
        return true
    }

    private fun readMveByte(mem: DmaMemory, l1Base: Long, mveVa: Long, scratch: ByteArray): Int {
        val pa = translateMveVa(mem, l1Base, mveVa) ?: return -1
        return try {
            mem.check(pa, 1L, false)
            mem.read(pa, ByteBuffer.wrap(scratch, 0, 1))
            scratch[0].toInt() and 0xFF
        } catch (_: Throwable) {
            -1
        }
    }

    private fun readMveSpan(mem: DmaMemory, l1Base: Long, base: Long, destination: ByteBuffer): Boolean {
        var address = base
        val limit = destination.limit()
        if (address < 0 || address + destination.remaining() > 0x1_0000_0000L) return false
        try {
            while (destination.position() < limit) {
                val physical = translateMveVa(mem, l1Base, address) ?: return false
                val count = minOf(limit - destination.position(), 4096 - (address.toInt() and 4095))
                val end = destination.position() + count
                destination.limit(end)
                mem.check(physical, count.toLong(), false)
                mem.read(physical, destination)
                if (destination.position() != end) return false
                address += count
            }
        } catch (_: IllegalArgumentException) {
            return false
        } catch (_: IllegalStateException) {
            return false
        } finally {
            destination.limit(limit)
        }
        return true
    }

    private fun popHostPacket(mem: DmaMemory, hostPagePa: Long, mvePagePa: Long): Pair<Int, IntArray>? {
        val inW = readU16(mem, hostPagePa + 2L) ?: return null
        val inR = readU16(mem, mvePagePa + 2L) ?: return null
        if (inR == inW || inR >= RING_WORDS || inW >= RING_WORDS) return null
        var pos = inR
        val hdr = readU32(mem, hostPagePa + 16L + (pos.toLong() shl 2)) ?: return null
        val code = (hdr and 0xFFFFL).toInt()
        val sizeBytes = ((hdr ushr 16) and 0xFFFFL).toInt()
        val wordCount = (sizeBytes + 3) ushr 2
        if (wordCount > RING_WORDS) return null
        pos = (pos + 1) % RING_WORDS
        val payload = IntArray(wordCount)
        for (i in 0 until wordCount) {
            val w = readU32(mem, hostPagePa + 16L + (pos.toLong() shl 2)) ?: return null
            payload[i] = w.toInt()
            pos = (pos + 1) % RING_WORDS
        }
        if (!writeU16(mem, mvePagePa + 2L, pos)) return null
        return code to payload
    }

    private fun pushMvePacket(
        mem: DmaMemory,
        hostPagePa: Long,
        mvePagePa: Long,
        code: Int,
        payloadBytes: Int,
        words: IntArray,
    ): Boolean {
        val outW = readU16(mem, mvePagePa) ?: return false
        val outR = readU16(mem, hostPagePa) ?: return false
        if (outW >= RING_WORDS || outR >= RING_WORDS) return false
        val wordCount = (payloadBytes + 3) ushr 2
        val free = if (outR > outW) outR - outW - 1 else RING_WORDS - outW + outR - 1
        if (free < wordCount + 1) return false
        var pos = outW
        val hdr = (code and 0xFFFF) or ((payloadBytes and 0xFFFF) shl 16)
        if (!writeU32(mem, mvePagePa + 16L + (pos.toLong() shl 2), hdr)) return false
        pos = (pos + 1) % RING_WORDS
        for (i in 0 until wordCount) {
            val w = if (i < words.size) words[i] else 0
            if (!writeU32(mem, mvePagePa + 16L + (pos.toLong() shl 2), w)) return false
            pos = (pos + 1) % RING_WORDS
        }
        val published = writeU16(mem, mvePagePa, pos)
        if (published) wroteResponse = true
        return published
    }

    private fun processLsidDoorbell(lsid: Int, mem: DmaMemory) {
        val mmuCtrl = lsidWords[lsid][LSID_OFF_MMU_CTRL / 4]
        if ((mmuCtrl and 3L) == 0L) return
        val l1Base = (mmuCtrl and 0x3FFF_FFFCL) shl 10
        if (l1Base == 0L) return

        val msgInPa = translateMveVa(mem, l1Base, MVE_VA_MSG_IN) ?: return
        val msgOutPa = translateMveVa(mem, l1Base, MVE_VA_MSG_OUT) ?: return
        val bufInHostPa = translateMveVa(mem, l1Base, MVE_VA_BUF_IN_HOST) ?: return
        val bufInMvePa = translateMveVa(mem, l1Base, MVE_VA_BUF_IN_MVE) ?: return
        val bufOutHostPa = translateMveVa(mem, l1Base, MVE_VA_BUF_OUT_HOST) ?: return
        val bufOutMvePa = translateMveVa(mem, l1Base, MVE_VA_BUF_OUT_MVE) ?: return

        val session = sessions[lsid]
        val emptyWords = IntArray(0)

        // 1. Process all Host -> VPU control messages on MSG_IN
        var guard = 0
        while (guard < 64) {
            val pkt = popHostPacket(mem, msgInPa, msgOutPa) ?: break
            guard++
            val (code, payload) = pkt
            when (code) {
                REQ_CODE_JOB -> {
                    session.headersEmitted = false
                    session.encodedFrames = 0
                    session.hevcHeadersEmitted = false
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_SWITCHED_IN, 0, emptyWords)
                }
                REQ_CODE_SET_OPTION -> {
                    if (payload.size >= 2) {
                        val optIndex = payload[0]
                        val optVal = payload[1]
                        when (optIndex) {
                            1 -> session.naluFormat = optVal
                            16 -> session.pFrames = optVal.coerceAtLeast(0)
                            38 -> session.sawH264BitDepthOpt = true
                        }
                    }
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_SET_OPTION, 0, emptyWords)
                }
                REQ_CODE_GO -> {
                    session.running = true
                    notifyVideoFramePeriodChanged()
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_STATE_CHANGE, 4, intArrayOf(MVE_STATE_RUNNING))
                }
                REQ_CODE_STOP -> {
                    session.running = false
                    notifyVideoFramePeriodChanged()
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_STATE_CHANGE, 4, intArrayOf(MVE_STATE_STOPPED))
                }
                REQ_CODE_INPUT_FLUSH -> {
                    session.pendingFrames.clear()
                    session.resetGop()
                    session.decodeTags.clear()
                    session.decoder?.close()
                    session.decoder = null
                    session.endOfStreamTag = null
                    session.decodeWaitStartTick = 0L
                    session.needsSequenceNegotiation = true
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_IN_FLUSHED, 0, emptyWords)
                }
                REQ_CODE_OUTPUT_FLUSH -> {
                    session.pendingHeaders.clear()
                    session.pendingBitstreams.clear()
                    session.decodeSurfaces.clear()
                    session.awaitingOutputFlush = false
                    session.decodeReadyTick = clockProvider() + framePeriodTicksForFpsQ16(session.fpsQ16)
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_OUT_FLUSHED, 0, emptyWords)
                }
                REQ_CODE_PING -> {
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_PONG, 0, emptyWords)
                }
                REQ_CODE_DUMP -> {
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_DUMP, 0, emptyWords)
                }
            }
        }

        // 2. Drain Host -> VPU input buffer queue (BUF_IN_HOST)
        guard = 0
        while (guard < 64) {
            val pkt = popHostPacket(mem, bufInHostPa, bufInMvePa) ?: break
            guard++
            val (code, payload) = pkt
            when (code) {
                BUF_CODE_BITSTREAM -> {
                    if (payload.size < 10) continue
                    val decoder = session.decoder ?: MaliV61PlaybackDecoder().also {
                        session.decoder = it
                        if (session.running) notifyVideoFramePeriodChanged()
                    }
                    val tag = payload[2].toUInt().toLong() or (payload[3].toLong() shl 32)
                    val eos = payload[4] and DECODE_BITSTREAM_EOS != 0
                    val picturesBefore = decoder.acceptedPictures
                    val accepted = if (payload[7] == 0 && eos) true else {
                        val compressed = readDecodeInput(mem, l1Base, payload)
                        compressed != null && decoder.submit(compressed)
                    }
                    if (accepted && (!eos || decoder.finishInput())) {
                        if (decoder.acceptedPictures > picturesBefore) session.decodeTags.addLast(tag)
                        if (eos) session.endOfStreamTag = tag
                        payload[7] = 0
                        pushMvePacket(mem, bufInHostPa, bufInMvePa, BUF_CODE_BITSTREAM, 40, payload)
                        pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_INPUT, 0, emptyWords)
                    } else {
                        decodeErrorCount++
                        pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_ERROR, 4, intArrayOf(2))
                    }
                }
                BUF_CODE_PARAM -> {
                    when (payload.firstOrNull()) {
                        9 -> if (payload.size >= 2) {
                            session.fpsQ16 = payload[1].toUInt().toLong()
                            if (session.running) notifyVideoFramePeriodChanged()
                        }
                        10 -> if (payload.size >= 3) {
                            session.rateControlMode = payload[1]
                            session.targetBitrate = payload[2].toUInt().toLong()
                            session.rateCredit = 0L
                        }
                        18 -> session.resetGop()
                    }
                }
                BUF_CODE_FRAME -> {
                    if (payload.size >= 18) {
                        session.pendingFrames.addLast(QueuedFrame(payload))
                    }
                }
            }
        }

        // 3. Drain Host -> VPU output buffer queue (BUF_OUT_HOST)
        guard = 0
        while (guard < 64) {
            val pkt = popHostPacket(mem, bufOutHostPa, bufOutMvePa) ?: break
            guard++
            val (code, payload) = pkt
            if (code == BUF_CODE_FRAME && payload.size >= 18) {
                session.decodeSurfaces.addLast(payload)
            }
            if (code == BUF_CODE_BITSTREAM && payload.size >= 10) {
                val handleLo = payload[0].toLong() and 0xFFFF_FFFFL
                val handleHi = payload[1].toLong() and 0xFFFF_FFFFL
                if (handleHi == 0L && (handleLo and HEADER_HANDLE_MASK) == HEADER_HANDLE_PREFIX) {
                    session.pendingHeaders.addLast(QueuedBitstream(payload))
                } else {
                    session.pendingBitstreams.addLast(QueuedBitstream(payload))
                }
            }
        }

        if (session.running && session.needsSequenceNegotiation && session.decodeTags.isNotEmpty() && session.decodeSurfaces.isNotEmpty()) {
            // A new sequence makes the host flush the old frame queue, then submit the surface again.
            // GFX100S 2.13 section4 0x01208584..0x012085D4 (SEQ_PARAMETERS) and 0x01208538 (OUT_FLUSHED).
            pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_SEQUENCE_PARAMETERS, 0, emptyWords)
            session.needsSequenceNegotiation = false
            session.awaitingOutputFlush = true
        }
        while (session.running && !session.awaitingOutputFlush && session.decodeSurfaces.isNotEmpty() && session.decodeTags.isNotEmpty()) {
            if (clockProvider() < session.decodeReadyTick) {
                pendingTicks[lsid] = session.decodeReadyTick
                break
            }
            val surface = session.decodeSurfaces.first()
            val width = surface[16] and 0xffff
            val height = surface[16] ushr 16
            val validGeometry = width in 2..4096 && height in 2..2160 && (width or height) and 1 == 0
            val decoder = requireNotNull(session.decoder)
            val decoded = if (validGeometry) decoder.takeNativeFrame(width, height, waitMillis = 1) else null
            if (decoded == null && validGeometry && decoder.lastError == null) {
                // Reordered pictures can need more input. Let the guest keep feeding its bitstream ring.
                val now = clockProvider()
                if (session.decodeWaitStartTick == 0L) session.decodeWaitStartTick = now
                if (now - session.decodeWaitStartTick < 2L * CLOCK_HZ) {
                    pendingTicks[lsid] = now + framePeriodTicksForFpsQ16(session.fpsQ16)
                    break
                }
            }
            session.decodeWaitStartTick = 0L
            session.decodeSurfaces.removeFirst()
            val tag = session.decodeTags.removeFirst()
            if (decoded == null || !writeDecodedSurface(mem, l1Base, surface, width, height, decoded)) {
                decodeErrorCount++
                surface[4] = surface[4] or DECODE_FRAME_CORRUPT
                surface[5] = 0
                pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_ERROR, 4, intArrayOf(2))
            } else {
                // The GFX100S driver gates DECODE_END on a nonempty frame at +0x10 (section4 0x01207FC8).
                surface[4] = surface[4] or DECODE_FRAME_PICTURE_PRESENT
                surface[5] = (width shl 16) or height
                framesDecodedCount++
            }
            if (session.endOfStreamTag != null && session.decodeTags.isEmpty()) {
                surface[4] = surface[4] or DECODE_FRAME_EOS
                session.endOfStreamTag = null
            }
            surface[2] = tag.toInt()
            surface[3] = (tag ushr 32).toInt()
            pushMvePacket(mem, bufOutHostPa, bufOutMvePa, BUF_CODE_FRAME, 72, surface)
            pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_OUTPUT, 0, emptyWords)
        }

        // A separately queued empty EOS can arrive after the last picture was already displayed.
        val eosTag = session.endOfStreamTag
        if (session.running && eosTag != null && session.decodeTags.isEmpty() && session.decodeSurfaces.isNotEmpty()) {
            val surface = session.decodeSurfaces.removeFirst()
            surface[2] = eosTag.toInt()
            surface[3] = (eosTag ushr 32).toInt()
            surface[4] = DECODE_FRAME_EOS
            surface[5] = 0
            session.endOfStreamTag = null
            pushMvePacket(mem, bufOutHostPa, bufOutMvePa, BUF_CODE_FRAME, 72, surface)
            pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_OUTPUT, 0, emptyWords)
        }

        // 4. Encode all paired (input frame, output bitstream) buffers when RUNNING
        while (session.running && session.pendingFrames.isNotEmpty() && session.pendingBitstreams.isNotEmpty()) {
            val frame = session.pendingFrames.removeFirst()
            val outBit = session.pendingBitstreams.removeFirst()
            val fw = frame.rawWords
            val bw = outBit.rawWords

            // Frame geometry from MVE_BUFFER_CODE_FRAME (section4 0x01205884..0x012058D0):
            // +0x14 (fw[5] lo16) = drv[0] (height), +0x16 (fw[5] hi16) = drv[4] (width)
            // +0x40 (fw[16] lo16) = drv[4] (width), +0x42 (fw[16] hi16) = drv[0] (height)
            val width = (fw[16] and 0xFFFF).takeIf { it in 16..4096 } ?: ((fw[5] ushr 16) and 0xFFFF).coerceIn(16, 4096)
            val height = ((fw[16] ushr 16) and 0xFFFF).takeIf { it in 16..2160 } ?: (fw[5] and 0xFFFF).coerceIn(16, 2160)
            val formatWord = fw[6] and 0xFFFF
            val yPlaneVa = fw[7].toLong() and 0xFFFF_FFFFL
            val uvPlaneVa = fw[8].toLong() and 0xFFFF_FFFFL
            val stride = if (formatWord == 0x201) fw[13].coerceAtLeast(width) else fw[13]

            // Detect H.265 vs H.264: GFX100S sends 3 header buffers (VPS, SPS, PPS) for H.265 and does NOT send opt 38.
            val isHevc = !session.sawH264BitDepthOpt && (session.hevcHeadersEmitted || session.pendingHeaders.size >= 3)
            var headersValid = !session.retryHeaders

            // 4a. Emit codec parameter sets (SPS/PPS or VPS/SPS/PPS) on the first frame
            if (!session.headersEmitted && session.pendingHeaders.isNotEmpty()) {
                val headers = if (isHevc) {
                    val bitDepth10 = true
                    session.hevcPcmEnabled = session.rateControlMode == 1 || session.rateControlMode == 3
                    listOf(
                        MaliV61BitstreamEncoder.buildH265Vps(bitDepth10),
                        MaliV61BitstreamEncoder.buildH265Sps(width, height, bitDepth10, session.fpsQ16,
                            fullRange = formatWord == 0x121, pcmEnabled = session.hevcPcmEnabled),
                        MaliV61BitstreamEncoder.buildH265Pps(),
                    )
                } else {
                    listOf(
                        MaliV61BitstreamEncoder.buildH264Sps(width, height, session.fpsQ16, fullRange = formatWord == 0x121),
                        MaliV61BitstreamEncoder.buildH264Pps(),
                    )
                }
                headersValid = session.pendingHeaders.size >= headers.size
                var hIdx = 0
                while (session.pendingHeaders.isNotEmpty() && hIdx < headers.size) {
                    val hdrBuf = session.pendingHeaders.removeFirst()
                    val nal = headers[hIdx++]
                    val hw = hdrBuf.rawWords.copyOf()
                    val dstVa = hw[8].toLong() and 0xFFFF_FFFFL
                    val storedHeader = nal.size <= hw[5].toUInt().toLong() && writeMveBytes(mem, l1Base, dstVa, nal)
                    headersValid = headersValid && storedHeader
                    // Firmware looks up frame_id from nTimestamp (+0x08) when nFlags & 0x80 (section4 0x01207F18)
                    hw[2] = fw[0]
                    hw[3] = fw[1]
                    hw[4] = BITSTREAM_FLAG_CODECCONFIG
                    hw[6] = 0 // nOffset
                    hw[7] = if (storedHeader) nal.size else 0 // nFilledLen
                    pushMvePacket(mem, bufOutHostPa, bufOutMvePa, BUF_CODE_BITSTREAM, 40, hw)
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_OUTPUT, 0, emptyWords)
                }
                session.headersEmitted = headersValid
                session.retryHeaders = !headersValid
                session.hevcHeadersEmitted = isHevc
            }

            val samples = if (yPlaneVa != 0L) {
                when (formatWord) {
                    0x201 -> if (fitsMvePlane(yPlaneVa, height, width, stride) && (uvPlaneVa == 0L ||
                            fitsMvePlane(uvPlaneVa, (height + 1) / 2, ((width + 1) / 2) * 2, stride))) {
                        sampleMacroblocks(mem, l1Base, yPlaneVa, uvPlaneVa, width, height, stride, packed10 = false)
                    } else null
                    0x121 -> if (fitsMvePlane(yPlaneVa, (height + 1) / 2, ((width + 1) / 2) * 8, stride)) {
                        sampleMacroblocks(mem, l1Base, yPlaneVa, 0L, width, height, stride, packed10 = true)
                    } else null
                    else -> null
                }
            } else null

            // 4c. The guest programs option 16 with the number of P pictures between IDRs.
            val allocLen = bw[5].toUInt().toLong()
            val previousAvc = session.avcReference?.takeIf { !isHevc && it.width == width && it.height == height }
            val previousHevc = session.hevcReference?.takeIf { isHevc && it.width == width && it.height == height }
            val forcedIdr = (fw[4] and 0xc00) != 0 // MVE FORCE_IDR / RESET_GOP
            val keyframe = forcedIdr || session.framesSinceIdr > session.pFrames || (previousAvc == null && previousHevc == null)
            val pictureOrder = if (keyframe) 0 else session.framesSinceIdr
            val periodTicks = framePeriodTicksForFpsQ16(session.fpsQ16)
            val frameBudget = if (session.rateControlMode == 0) 0L else session.targetBitrate * periodTicks / (8L * CLOCK_HZ)
            val budget = if (frameBudget > 0) frameBudget + session.rateCredit else Long.MAX_VALUE
            val targetBytes = if (frameBudget > 0) minOf(budget, frameBudget * 2, allocLen).toInt() else 0
            val pcm = if (targetBytes > 0 && samples != null) {
                val stripBytes = maxOf(((width + 1) / 2) * 8 * 8, width * 16 + ((width + 1) / 2) * 16)
                if (pcmStrip.capacity() < stripBytes) pcmStrip = ByteBuffer.allocate(stripBytes)
                MvePcmTileSource(width, height, stride, formatWord == 0x121, yPlaneVa, uvPlaneVa, pcmStrip) { address, bytes ->
                    readMveSpan(mem, l1Base, address, bytes)
                }
            } else null
            var nextAvc: AvcReferenceFrame? = null
            var nextHevc: HevcReferenceFrame? = null
            fun encode(detail: ByteArray?): ByteArray {
                if (isHevc) {
                    val frame = HevcReferenceFrame(width, height, samples?.y, samples?.cb, samples?.cr, detail,
                        bitDepth10 = true, keepReference = session.pFrames > 0)
                    nextHevc = frame.takeIf { session.pFrames > 0 }
                    // Once advertised by the SPS, every eligible CU carries pcm_flag even on a lower-detail retry.
                    val availablePcm = if (session.hevcPcmEnabled) pcm.takeIf { detail != null } ?: UNAVAILABLE_PCM else null
                    return MaliV61BitstreamEncoder.buildH265Picture(frame, previousHevc.takeUnless { keyframe }, pictureOrder,
                        availablePcm, targetBytes, constantRate = session.rateControlMode == 3)
                }
                if (session.pFrames == 0 && pcm == null) return MaliV61BitstreamEncoder.buildH264IdrSlice(width, height, session.encodedFrames,
                    samples?.y, samples?.cb, samples?.cr, detail)
                val frame = AvcReferenceFrame(width, height, samples?.y, samples?.cb, samples?.cr, detail,
                    keepReference = session.pFrames > 0, trackChroma = pcm != null && detail != null)
                nextAvc = frame.takeIf { session.pFrames > 0 }
                return MaliV61BitstreamEncoder.buildH264Picture(frame, previousAvc.takeUnless { keyframe }, session.encodedFrames,
                    pictureOrder, pcm.takeIf { detail != null }, targetBytes, session.rateControlMode == 3)
            }
            val detailed = if (headersValid && allocLen > 0 && samples != null) encode(samples.luma4x4) else null
            val exceedsRate = detailed != null && frameBudget > 0 && detailed.size > budget + maxOf(512, frameBudget / 50)
            val encodedNal = if (detailed != null && (exceedsRate || detailed.size > allocLen) && samples?.luma4x4 != null) {
                encode(null)
            } else detailed

            val dstVa = bw[8].toLong() and 0xFFFF_FFFFL
            val writeLen = encodedNal?.size ?: 0
            val stored = encodedNal != null && writeLen <= allocLen && writeMveBytes(mem, l1Base, dstVa, encodedNal)
            val emptyEos = yPlaneVa == 0L && (fw[4] and FRAME_FLAG_EOS) != 0
            if (stored) {
                session.avcReference = nextAvc
                session.hevcReference = nextHevc
                session.framesSinceIdr = pictureOrder + 1
                if (frameBudget > 0) session.rateCredit = (budget - writeLen).coerceIn(0, frameBudget * (session.pFrames + 1L))
            } else {
                session.resetGop()
                if (!emptyEos) {
                    encodeErrorCount++
                    pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_ERROR, 4, intArrayOf(2))
                }
            }

            // 4d. Return input frame descriptor on BUF_IN_MVE + RSP_INPUT (2005)
            pushMvePacket(mem, bufInHostPa, bufInMvePa, BUF_CODE_FRAME, 72, fw)
            pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_INPUT, 0, emptyWords)

            // 4e. Return encoded bitstream descriptor on BUF_OUT_MVE + RSP_OUTPUT (2006)
            // Firmware asserts nTimestamp (+0x08) == nHandle (+0x00) at section4 0x012082BC!
            val outResp = bw.copyOf()
            outResp[2] = outResp[0] // nTimestamp lo = nHandle lo (frame_id)
            outResp[3] = outResp[1] // nTimestamp hi = nHandle hi
            // A complete IDR ends this picture. End-of-stream belongs only to the final input.
            val eos = if ((fw[4] and FRAME_FLAG_EOS) != 0) BITSTREAM_FLAG_EOS else 0
            val sync = if (stored && keyframe) BITSTREAM_FLAG_SYNCFRAME else 0
            outResp[4] = BITSTREAM_FLAG_ENDOFFRAME or BITSTREAM_FLAG_ENDOFSUBFRAME or sync or eos
            outResp[6] = 0 // nOffset
            outResp[7] = if (stored) writeLen else 0 // unmapped input never becomes a synthetic grey picture
            outResp[9] = (outResp[9] and 0xff.inv()) or (if (keyframe) 0 else 1) // MVE frame_type
            pushMvePacket(mem, bufOutHostPa, bufOutMvePa, BUF_CODE_BITSTREAM, 40, outResp)
            pushMvePacket(mem, msgInPa, msgOutPa, RSP_CODE_OUTPUT, 0, emptyWords)

            if (stored) {
                session.encodedFrames++
                framesEncodedCount++
                bytesEncodedCount += writeLen.toLong()
            }
        }
    }

    private fun readDecodeInput(mem: DmaMemory, l1Base: Long, bitstream: IntArray): ByteArray? {
        val length = bitstream[7]
        val offset = bitstream[6]
        if (length !in 1..(16 * 1024 * 1024) || offset < 0 || offset.toLong() + length > bitstream[5]) return null
        var va = bitstream[8].toUInt().toLong() + offset
        if (va + length > 0x1_0000_0000L) return null
        val bytes = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val pa = translateMveVa(mem, l1Base, va) ?: return null
            val count = minOf(length - pos, 4096 - (va.toInt() and 4095))
            try {
                mem.check(pa, count.toLong(), false)
                mem.read(pa, ByteBuffer.wrap(bytes, pos, count))
            } catch (_: IllegalArgumentException) {
                return null
            }
            pos += count
            va += count
        }
        return bytes
    }

    private fun writeDecodedSurface(
        mem: DmaMemory, l1Base: Long, surface: IntArray, width: Int, height: Int, frame: MaliV61PlaybackDecoder.Frame,
    ): Boolean {
        val chromaBase = width * height
        val yuv = frame.pixels
        val nativeTenBit = frame.bitDepth == 10
        val samples = chromaBase + chromaBase / 2
        if (yuv.size != samples * (if (nativeTenBit) 2 else 1)) return false
        fun sample10(index: Int): Int {
            if (!nativeTenBit) return (yuv[index].toInt() and 255) shl 2
            val offset = index * 2
            return (yuv[offset].toInt() and 255) or ((yuv[offset + 1].toInt() and 3) shl 8)
        }
        val base = surface[7].toUInt().toLong()
        val stride = surface[13]
        if (base == 0L || stride <= 0) return false
        val crBase = chromaBase + chromaBase / 4
        when (surface[6] and 0xffff) {
            0x121 -> {
                val rowBytes = width * 4
                if (stride < rowBytes) return false
                val row = ByteArray(rowBytes)
                val words = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN)
                for (y in 0 until height step 2) {
                    words.clear()
                    var ch = y / 2 * (width / 2)
                    var top = y * width
                    for (x in 0 until width step 2) {
                        val bottom = top + width
                        fun pair(luma: Int, chroma: Int): Int = sample10(luma) or
                            (sample10(chroma) shl 10) or (sample10(luma + 1) shl 20)
                        words.putInt(pair(top, chromaBase + ch))
                        words.putInt(pair(bottom, crBase + ch))
                        top += 2
                        ch++
                    }
                    if (!writeMveBytes(mem, l1Base, base + (y / 2).toLong() * stride, row)) return false
                }
            }
            0x201 -> {
                val uvBase = surface[8].toUInt().toLong()
                val uvStride = surface[14]
                if (uvBase == 0L || stride < width || uvStride < width) return false
                val row = ByteArray(width)
                for (y in 0 until height) {
                    if (nativeTenBit) {
                        for (x in 0 until width) row[x] = (sample10(y * width + x) ushr 2).toByte()
                    } else {
                        yuv.copyInto(row, 0, y * width, (y + 1) * width)
                    }
                    if (!writeMveBytes(mem, l1Base, base + y.toLong() * stride, row)) return false
                }
                for (y in 0 until height / 2) {
                    for (x in 0 until width / 2) {
                        val index = y * (width / 2) + x
                        row[2 * x] = (sample10(chromaBase + index) ushr 2).toByte()
                        row[2 * x + 1] = (sample10(crBase + index) ushr 2).toByte()
                    }
                    if (!writeMveBytes(mem, l1Base, uvBase + y.toLong() * uvStride, row)) return false
                }
            }
            else -> return false
        }
        return true
    }

    /**
     * Sample one representative center pixel per 16x16 macroblock from NV12 planes or packed Y0L2 tiles
     * mapped in MVE virtual address space. Returns null if the luma plane is unreadable.
     */
    private data class BlockSamples(val y: ByteArray, val cb: ByteArray, val cr: ByteArray, val luma4x4: ByteArray?)

    private fun fitsMvePlane(base: Long, rows: Int, rowBytes: Int, stride: Int): Boolean {
        // 4K uses 0x5600 bytes per tile row. Padding is valid up to the end of the 32-bit MVE VA.
        return stride >= rowBytes && base + (rows - 1L) * stride + rowBytes <= 0x1_0000_0000L
    }

    private fun sampleMacroblocks(
        mem: DmaMemory,
        l1Base: Long,
        yPlaneVa: Long,
        uvPlaneVa: Long,
        width: Int,
        height: Int,
        stride: Int,
        packed10: Boolean,
    ): BlockSamples? {
        val mbW = (width + 15) / 16
        val mbH = (height + 15) / 16
        val total = mbW * mbH
        val yArr = ByteArray(total)
        val cbArr = ByteArray(total)
        val crArr = ByteArray(total)
        val oneByte = ByteArray(1)
        var cachedPageBase = -1L
        var cachedPageValid = false
        fun readCachedByte(va: Long): Int {
            val uVa = va and 0xFFFF_FFFFL
            val pageBase = uVa and 0xFFFF_F000L
            val pageOff = (uVa and 0xFFFL).toInt()
            if (pageBase != cachedPageBase) {
                cachedPageBase = pageBase
                cachedPageValid = false
                val pagePa = translateMveVa(mem, l1Base, pageBase)
                if (pagePa != null) {
                    cachedPageValid = try {
                        mem.check(pagePa, 4096L, false)
                        mem.read(pagePa, mvePageView.clear())
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
            }
            return if (cachedPageValid) {
                mvePageBuf[pageOff].toInt() and 0xFF
            } else {
                readMveByte(mem, l1Base, uVa, oneByte)
            }
        }
        for (my in 0 until mbH) {
            val py = minOf(my * 16 + 8, height - 1)
            if (packed10) {
                // Y0L2 holds two little-endian words per 2x2 tile. Each word has
                // Y(left), Cb/Cr, Y(right) in bits 0..9, 10..19, 20..29.
                val tileRow = yPlaneVa + (py / 2).toLong() * stride
                for (mx in 0 until mbW) {
                    val px = minOf(mx * 16 + 8, width - 1)
                    val tile = tileRow + (px / 2) * 8
                    fun bytePair(address: Long): Int {
                        val lo = readCachedByte(address)
                        val hi = readCachedByte(address + 1)
                        return if (lo < 0 || hi < 0) -1 else lo or (hi shl 8)
                    }
                    val yWord = tile + (py and 1) * 4
                    val y = if ((px and 1) == 0) bytePair(yWord) else bytePair(yWord + 2)
                    val cb = bytePair(tile + 1)
                    val cr = bytePair(tile + 5)
                    if (y < 0 || cb < 0 || cr < 0) return null
                    val i = my * mbW + mx
                    yArr[i] = (y ushr (if ((px and 1) == 0) 2 else 6)).toByte()
                    cbArr[i] = (cb ushr 4).toByte()
                    crArr[i] = (cr ushr 4).toByte()
                }
                continue
            }
            val yRowVa = yPlaneVa + py.toLong() * stride.toLong()
            val uvRowVa = if (uvPlaneVa != 0L) uvPlaneVa + (py / 2).toLong() * stride.toLong() else 0L
            val rowOff = my * mbW
            for (mx in 0 until mbW) {
                val px = minOf(mx * 16 + 8, width - 1)
                val yv = readCachedByte(yRowVa + px.toLong())
                if (yv < 0) return null
                yArr[rowOff + mx] = yv.toByte()
            }
            if (uvRowVa != 0L) {
                for (mx in 0 until mbW) {
                    val px = minOf(mx * 16 + 8, width - 1)
                    val uvX = (px and 1.inv()).toLong()
                    val cb = readCachedByte(uvRowVa + uvX)
                    val cr = readCachedByte(uvRowVa + uvX + 1L)
                    cbArr[rowOff + mx] = (if (cb >= 0) cb else 128).toByte()
                    crArr[rowOff + mx] = (if (cr >= 0) cr else 128).toByte()
                }
            } else {
                for (mx in 0 until mbW) {
                    cbArr[rowOff + mx] = 128.toByte()
                    crArr[rowOff + mx] = 128.toByte()
                }
            }
        }
        val blockCols = mbW * 4
        val detail = ByteArray(blockCols * mbH * 4) { i ->
            val x = minOf((i % blockCols) * 4 + 2, width - 1)
            val y = minOf((i / blockCols) * 4 + 2, height - 1)
            if (packed10) {
                val tile = yPlaneVa + (y / 2).toLong() * stride + (x / 2) * 8 + (y and 1) * 4
                val addr = tile + (if ((x and 1) == 0) 0 else 2)
                val lo = readCachedByte(addr)
                val hi = readCachedByte(addr + 1)
                if (lo < 0 || hi < 0) return null
                ((lo or (hi shl 8)) ushr (if ((x and 1) == 0) 2 else 6)).toByte()
            } else {
                val luma = readCachedByte(yPlaneVa + y.toLong() * stride + x)
                if (luma < 0) return null
                luma.toByte()
            }
        }
        return BlockSamples(yArr, cbArr, crArr, detail)
    }

    fun createMmioDevice(isClosed: () -> Boolean): TickAwareMmioDevice = object : TickAwareMmioDevice {
        override val clockOrder = 84
        override val clockIndex = 0

        override fun nextDeadline(): Long? {
            if (isClosed()) return null
            var earliest: Long? = null
            for (t in pendingTicks) {
                if (t != null && (earliest == null || t < earliest)) earliest = t
            }
            return earliest
        }

        override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

        override fun beforeInstruction(tick: Long) {
            if (isClosed()) return
            var fired = false
            for (lsid in 0 until LSID_COUNT) {
                val due = pendingTicks[lsid] ?: continue
                if (tick >= due) {
                    pendingTicks[lsid] = null
                    val hostDoorbell = lsidWords[lsid][LSID_OFF_IRQHOST / 4] != 0L
                    lsidWords[lsid][LSID_OFF_IRQHOST / 4] = 0L
                    wroteResponse = false
                    dmaMemoryProvider()?.let { mem -> processLsidDoorbell(lsid, mem) }
                    if (hostDoorbell || wroteResponse) {
                        lsidWords[lsid][LSID_OFF_IRQVE / 4] = 1L
                        completionCount++
                        fired = true
                    }
                }
            }
            if (fired) {
                updateIrq()
            }
        }

        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_ID closed" }
            require(size == 4 && offset in 0L until WINDOW_SIZE && offset % 4L == 0L) {
                "$PORT_ID requires aligned 32-bit access within [0, 0x300): off=0x${offset.toString(16)} size=$size"
            }
            val off = offset.toInt()
            return when {
                off <= OFF_RESET -> when (off) {
                    OFF_IRQVE -> computeIrqVeMask()
                    OFF_TIMER -> clockProvider() and 0xFFFF_FFFFL
                    OFF_RESET -> 0L
                    else -> globalWords[off / 4]
                }
                off >= LSID_BASE -> {
                    val rel = off - LSID_BASE
                    val lsid = rel / LSID_STRIDE
                    val reg = rel % LSID_STRIDE
                    when (reg) {
                        LSID_OFF_FLUSH_ALL, LSID_OFF_TERMINATE -> 0L
                        else -> lsidWords[lsid][reg / 4]
                    }
                }
                else -> 0L
            }
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_ID closed" }
            require(size == 4 && offset in 0L until WINDOW_SIZE && offset % 4L == 0L) {
                "$PORT_ID requires aligned 32-bit access within [0, 0x300): off=0x${offset.toString(16)} size=$size"
            }
            require(value in 0L..0xFFFF_FFFFL) { "$PORT_ID write value outside u32: $value" }
            val off = offset.toInt()
            when {
                off <= OFF_RESET -> when (off) {
                    OFF_RESET -> {
                        if ((value and 1L) != 0L) {
                            resetCount++
                            resetState()
                        }
                    }
                    OFF_ENABLE -> {
                        globalWords[OFF_ENABLE / 4] = value and 1L
                    }
                    OFF_VERSION, OFF_NCORES, OFF_NLSIDS, OFF_CORELSID, OFF_IRQVE, OFF_CLKIDLE, OFF_TIMER -> {
                        // Read-only hardware identity/status registers ignore writes cleanly
                    }
                    else -> globalWords[off / 4] = value
                }
                off >= LSID_BASE -> {
                    val rel = off - LSID_BASE
                    val lsid = rel / LSID_STRIDE
                    val reg = rel % LSID_STRIDE
                    when (reg) {
                        LSID_OFF_FLUSH_ALL -> {
                            lsidWords[lsid][reg / 4] = 0L
                        }
                        LSID_OFF_TERMINATE -> {
                            // Self-clearing terminate completes immediately so h265_MapSession / Unmap poll exits
                            pendingTicks[lsid] = null
                            sessions[lsid].reset()
                            lsidWords[lsid][LSID_OFF_IRQVE / 4] = 0L
                            lsidWords[lsid][LSID_OFF_IRQHOST / 4] = 0L
                            lsidWords[lsid][reg / 4] = 0L
                            updateIrq()
                            notifyVideoFramePeriodChanged()
                        }
                        LSID_OFF_IRQVE -> {
                            // ISR writes 0 to clear LSID VPU->Host interrupt latch
                            lsidWords[lsid][reg / 4] = value and 1L
                            updateIrq()
                        }
                        LSID_OFF_IRQHOST -> {
                            lsidWords[lsid][reg / 4] = value and 1L
                            if ((value and 1L) != 0L) {
                                doorbellCount++
                                pendingTicks[lsid] = clockProvider() + COMPLETION_DELAY_TICKS
                            }
                        }
                        else -> lsidWords[lsid][reg / 4] = value
                    }
                }
            }
        }
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "version" to VERSION_WORD,
        "enable" to globalWords[OFF_ENABLE / 4],
        "coreLsid" to globalWords[OFF_CORELSID / 4],
        "jobQueue" to globalWords[OFF_JOBQUEUE / 4],
        "irqVeMask" to computeIrqVeMask(),
        "irqAsserted" to irqAsserted,
        "resetCount" to resetCount,
        "doorbellCount" to doorbellCount,
        "completionCount" to completionCount,
        "framesEncodedCount" to framesEncodedCount,
        "encodeErrorCount" to encodeErrorCount,
        "framesDecodedCount" to framesDecodedCount,
        "decodeErrorCount" to decodeErrorCount,
        "bytesEncodedCount" to bytesEncodedCount,
        "activeVideoFramePeriodTicks" to activeVideoFramePeriodTicks(),
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "globalWords" to globalWords.toList(),
        "lsidWords" to lsidWords.map { it.toList() },
        "pendingTicks" to pendingTicks.toList(),
        "irqAsserted" to irqAsserted,
        "resetCount" to resetCount,
        "doorbellCount" to doorbellCount,
        "completionCount" to completionCount,
    )

    fun restoreSnapshot(raw: Any?) {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid maliV61 snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected maliV61 snapshot keys" }
        val rawGlobal = map["globalWords"] as? List<*> ?: throw IllegalArgumentException("maliV61 globalWords requires list")
        require(rawGlobal.size == GLOBAL_WORDS) { "maliV61 globalWords size mismatch" }
        val nextGlobal = LongArray(GLOBAL_WORDS) { i -> reqU32(rawGlobal[i], "maliV61.globalWords[$i]") }
        val rawLsids = map["lsidWords"] as? List<*> ?: throw IllegalArgumentException("maliV61 lsidWords requires list")
        require(rawLsids.size == LSID_COUNT) { "maliV61 lsidWords size mismatch" }
        val nextLsids = Array(LSID_COUNT) { lsid ->
            val row = rawLsids[lsid] as? List<*> ?: throw IllegalArgumentException("maliV61 lsidWords[$lsid] requires list")
            require(row.size == LSID_WORDS) { "maliV61 lsidWords[$lsid] size mismatch" }
            LongArray(LSID_WORDS) { j -> reqU32(row[j], "maliV61.lsidWords[$lsid][$j]") }
        }
        val rawPending = map["pendingTicks"] as? List<*> ?: throw IllegalArgumentException("maliV61 pendingTicks requires list")
        require(rawPending.size == LSID_COUNT) { "maliV61 pendingTicks size mismatch" }
        val nextPending = Array(LSID_COUNT) { idx ->
            rawPending[idx]?.let { reqLong(it, "maliV61.pendingTicks[$idx]", 0L, Long.MAX_VALUE) }
        }
        val nextIrq = map["irqAsserted"] as? Boolean ?: throw IllegalArgumentException("maliV61 irqAsserted requires boolean")
        val nextReset = reqLong(map["resetCount"], "maliV61.resetCount", 0L, Long.MAX_VALUE)
        val nextDoorbell = reqLong(map["doorbellCount"], "maliV61.doorbellCount", 0L, Long.MAX_VALUE)
        val nextComp = reqLong(map["completionCount"], "maliV61.completionCount", 0L, Long.MAX_VALUE)

        nextGlobal.copyInto(globalWords)
        for (i in 0 until LSID_COUNT) {
            nextLsids[i].copyInto(lsidWords[i])
            pendingTicks[i] = nextPending[i]
            sessions[i].reset()
        }
        irqAsserted = nextIrq
        resetCount = nextReset
        doorbellCount = nextDoorbell
        completionCount = nextComp
        framesEncodedCount = 0L
        encodeErrorCount = 0L
        bytesEncodedCount = 0L
        framesDecodedCount = 0L
        decodeErrorCount = 0L
        notifyVideoFramePeriodChanged()
    }

    private fun reqU32(value: Any?, field: String): Long = reqLong(value, field, 0L, 0xFFFF_FFFFL)

    private fun reqLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot $field requires integer" }
        val v = (value as Number).toLong()
        require(v in min..max) { "Snapshot $field out of range: $v" }
        return v
    }
}
