package com.github.nyabanban.fuji.emulator.components.panel

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import java.nio.ByteBuffer

/**
 * Opt-in hardware LUT/DMA audio bit-packing peripheral at `0xFFFA8000..0xFFFA8047` (GIC IRQ 144, logical IRQ 115).
 *
 * DAT producers/consumers in `firmware-data/gfx100s/2.13/decoded/section4.bin` (`0x011FC418..0x011FCC3C`
 * and `TSK_SOUNDIO` `0x01297DB4..0x01297FEC` + 24-bit mic level meter `0x0129B7DC..0x0129B840`):
 * - `0x011FC704..0x011FC740`: clears bit 0 of `+0x00` (`0xFFFA8000`) and busy-waits (`tst r0, #1; beq`)
 *   until bit 0 self-sets back to `1` (reset complete).
 * - `0x011FC744..0x011FC808`: uploads one of 4 byte-packing microcode LUTs (`0x015B1940` TX24 3B->4B,
 *   `0x015B1AC0` RX24 4B->3B, `0x015B1880` TX16 2B->4B, `0x015B1A00` RX16 4B->2B) to `+0x0C`/`+0x10`,
 *   clears bit 0 of `+0x08`, writes bit 0 = `1` to `+0x04` (`0xFFFA8004`), and busy-waits until bit 0 clears.
 * - `0x011FC814..0x011FCA88` + `0x011FCC24`: programs `+0x08` (`srcFmt` bits[10:8], `dstFmt` bits[26:24]:
 *   `1`=16-bit 2B, `2`=24-bit 3B, `3`=32-bit 4B), `+0x14/+0x18` (src phys addr), `+0x1C/+0x20` (src line
 *   bytes & line count), `+0x24/+0x28` (dst phys addr), `+0x2C/+0x30` (dst line bytes & line count),
 *   `+0x34/+0x38` (src/dst strides), `+0x3C` (block count), resolves logical IRQ `115` (`0x73`) ->
 *   **GIC IRQ 144** (edge-triggered), and writes `1` to `+0x04` to execute the RAM-to-RAM bit-packing DMA
 *   transfer and pulse completion GIC IRQ 144.
 */
internal class ToneLutA8000Device : TickAwareMmioDevice {
    override val clockOrder = 74

    private val words = LongArray(WORD_COUNT).also { it[0] = RESET_READY_BIT }
    private var irqAsserted = false
    private var irqSink: InterruptSink? = null
    private var dmaMemory: DmaMemory? = null
    private var dmaMemoryProvider: (() -> DmaMemory?)? = null
    private var readCount = 0L
    private var writeCount = 0L
    private var irqPulseCount = 0L
    private var dmaJobCount = 0L
    private var dmaBytesWritten = 0L
    private var srcScratch = ByteArray(0)
    private var dstScratch = ByteArray(0)

    fun attachInterruptSink(sink: InterruptSink?) {
        irqSink = sink
        if (irqAsserted && sink != null) {
            sink.setLine(IRQ_LINE, true)
        }
    }

    fun detachInterruptSink() {
        if (irqAsserted) {
            irqAsserted = false
            irqSink?.setLine(IRQ_LINE, false)
        }
        irqSink = null
    }

    fun attachMemory(memory: DmaMemory?) {
        dmaMemory = memory
    }

    fun attachMemoryProvider(provider: (() -> DmaMemory?)?) {
        dmaMemoryProvider = provider
        dmaMemory = provider?.invoke()
    }

    fun detachMemory() {
        dmaMemory = null
        dmaMemoryProvider = null
    }

    private fun resolveMemory(): DmaMemory? {
        dmaMemory?.let { return it }
        val found = dmaMemoryProvider?.invoke()
        if (found != null) dmaMemory = found
        return found
    }

    override fun nextDeadline(): Long? = null

    override fun nextInterestedTick(): Long = if (irqAsserted) 0L else Long.MAX_VALUE

    override fun readMayMutateSchedule(): Boolean = false

    override fun beforeInstruction(tick: Long) {
        if (irqAsserted) {
            irqAsserted = false
            irqSink?.setLine(IRQ_LINE, false)
        }
    }

    private fun checkAccess(offset: Long, size: Int): Int {
        require(size == 4 && offset >= 0L && offset < WINDOW_SIZE && (offset and 3L) == 0L) {
            "soc-panel.tone-lut-a8000 requires aligned 32-bit word within [0, $WINDOW_SIZE)"
        }
        return (offset ushr 2).toInt()
    }

    override fun read(offset: Long, size: Int): Long {
        val index = checkAccess(offset, size)
        if (readCount < Long.MAX_VALUE) readCount++
        return words[index]
    }

    override fun write(offset: Long, size: Int, value: Long) {
        val index = checkAccess(offset, size)
        require(value in 0L..0xffffffffL) { "soc-panel.tone-lut-a8000 write value outside u32" }
        if (writeCount < Long.MAX_VALUE) writeCount++
        when (index) {
            0 -> {
                if ((value and RESET_READY_BIT) == 0L) {
                    words.fill(0L)
                    words[0] = value or RESET_READY_BIT
                    if (irqAsserted) {
                        irqAsserted = false
                        irqSink?.setLine(IRQ_LINE, false)
                    }
                } else {
                    words[0] = value
                }
            }
            1 -> {
                // Bit 0 self-clears immediately so LUT upload (0x011FC7F0) never hangs.
                words[1] = value and START_BUSY_BIT.inv()
                val jobMode = (words[2] and JOB_MODE_BIT) != 0L
                if ((value and START_BUSY_BIT) != 0L && jobMode) {
                    executeBitpackJob()
                    irqAsserted = true
                    if (irqPulseCount < Long.MAX_VALUE) irqPulseCount++
                    irqSink?.setLine(IRQ_LINE, true)
                }
            }
            else -> words[index] = value
        }
    }

    private fun executeBitpackJob() {
        val mem = resolveMemory() ?: return
        val cfg = words[2]
        val srcFmt = ((cfg ushr 8) and 0x7L).toInt()
        val dstFmt = ((cfg ushr 24) and 0x7L).toInt()
        val srcBps = formatBytesPerSample(srcFmt)
        val dstBps = formatBytesPerSample(dstFmt)
        if (srcBps <= 0 || dstBps <= 0) return

        val srcAddr = ((words[5] and 0x3L) shl 32) or (words[6] and 0xFFFFFFFFL)
        val dstAddr = ((words[9] and 0x3L) shl 32) or (words[10] and 0xFFFFFFFFL)
        if (srcAddr == 0L || dstAddr == 0L) return

        val srcLineBytes = (words[7] and 0xFFFFFL).toInt()
        val srcLines = (words[8] and 0xFFFFL).toInt()
        val dstLineBytes = (words[11] and 0xFFFFFL).toInt()
        val dstLines = (words[12] and 0xFFFFL).toInt()
        if (srcLineBytes <= 0 || srcLines <= 0 || dstLineBytes <= 0 || dstLines <= 0) return

        val blocks = (words[15] and 0xFFFFL).toInt().coerceAtLeast(1)
        val srcTotalLong = srcLineBytes.toLong() * srcLines.toLong() * blocks.toLong()
        val dstTotalLong = dstLineBytes.toLong() * dstLines.toLong() * blocks.toLong()
        if (srcTotalLong !in 1L..MAX_DMA_JOB_BYTES || dstTotalLong !in 1L..MAX_DMA_JOB_BYTES) return

        val samples = minOf(srcTotalLong / srcBps, dstTotalLong / dstBps).toInt()
        if (samples <= 0) return
        val srcReadLen = samples * srcBps
        val dstWriteLen = samples * dstBps

        val accessOk = runCatching {
            mem.check(srcAddr, srcReadLen.toLong(), false)
            mem.check(dstAddr, dstWriteLen.toLong(), true)
            true
        }.getOrDefault(false)
        if (!accessOk) return

        if (srcScratch.size < srcReadLen) srcScratch = ByteArray(srcReadLen)
        if (dstScratch.size < dstWriteLen) dstScratch = ByteArray(dstWriteLen)
        val src = srcScratch
        val dst = dstScratch

        mem.read(srcAddr, ByteBuffer.wrap(src, 0, srcReadLen))
        packSamples(src, dst, samples, srcBps, dstBps)
        mem.write(dstAddr, ByteBuffer.wrap(dst, 0, dstWriteLen))

        if (dmaJobCount < Long.MAX_VALUE) dmaJobCount++
        dmaBytesWritten += dstWriteLen.toLong()
    }

    fun state(): Map<String, Any> = linkedMapOf(
        "words" to words.toList(),
        "irqLine" to IRQ_LINE,
        "irqAsserted" to irqAsserted,
        "irqPulseCount" to irqPulseCount,
        "readCount" to readCount,
        "writeCount" to writeCount,
        "dmaJobCount" to dmaJobCount,
        "dmaBytesWritten" to dmaBytesWritten,
        "basis" to "DAT section4 0x011FC418..0x011FCC3C, 0x01297DB4..0x01297FEC & 0x015B1880..0x015B1B40 (0xFFFA8000..0xFFFA8044, logical IRQ 115 -> GIC IRQ 144, 4B<->3B/2B audio PCM bit-packing DMA)",
    )

    fun saveSnapshot(): Map<String, Any> = linkedMapOf(
        "words" to words.toList(),
        "irqAsserted" to irqAsserted,
        "irqPulseCount" to irqPulseCount,
        "readCount" to readCount,
        "writeCount" to writeCount,
    )

    internal class ValidatedSnapshot(
        val words: LongArray,
        val irqAsserted: Boolean,
        val irqPulseCount: Long,
        val readCount: Long,
        val writeCount: Long,
    )

    fun validateSnapshot(raw: Any?): ValidatedSnapshot {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot toneLutA8000 requires map")
        require(m.keys == SNAPSHOT_KEYS) { "Unexpected toneLutA8000 snapshot keys" }
        val rawWords = m["words"] as? List<*> ?: throw IllegalArgumentException("Snapshot toneLutA8000.words requires list")
        require(rawWords.size == WORD_COUNT) { "Snapshot toneLutA8000.words requires $WORD_COUNT entries" }
        val parsedWords = LongArray(WORD_COUNT) { idx ->
            val v = rawWords[idx]
            require(v is Byte || v is Short || v is Int || v is Long) { "Snapshot toneLutA8000.words[$idx] requires integer" }
            val n = (v as Number).toLong()
            require(n in 0L..0xffffffffL) { "Snapshot toneLutA8000.words[$idx] out of u32 range" }
            n
        }
        require((parsedWords[0] and RESET_READY_BIT) != 0L) { "Snapshot toneLutA8000.words[0] must preserve reset-ready bit0" }
        require((parsedWords[1] and START_BUSY_BIT) == 0L) { "Snapshot toneLutA8000.words[1] cannot retain pending busy bit0" }
        val nextIrqAsserted = m["irqAsserted"] as? Boolean
            ?: throw IllegalArgumentException("Snapshot toneLutA8000.irqAsserted requires boolean")
        fun cnt(key: String): Long {
            val v = m[key]
            require(v is Byte || v is Short || v is Int || v is Long) { "Snapshot toneLutA8000.$key requires integer" }
            val n = (v as Number).toLong()
            require(n in 0L..Long.MAX_VALUE) { "Snapshot toneLutA8000.$key out of range" }
            return n
        }
        return ValidatedSnapshot(
            words = parsedWords,
            irqAsserted = nextIrqAsserted,
            irqPulseCount = cnt("irqPulseCount"),
            readCount = cnt("readCount"),
            writeCount = cnt("writeCount"),
        )
    }

    fun applySnapshot(s: ValidatedSnapshot) {
        s.words.copyInto(words)
        irqAsserted = s.irqAsserted
        irqPulseCount = s.irqPulseCount
        readCount = s.readCount
        writeCount = s.writeCount
        dmaJobCount = 0L
        dmaBytesWritten = 0L
        irqSink?.setLine(IRQ_LINE, irqAsserted)
    }

    fun resetToDefault() {
        words.fill(0L)
        words[0] = RESET_READY_BIT
        if (irqAsserted) {
            irqAsserted = false
            irqSink?.setLine(IRQ_LINE, false)
        }
        irqPulseCount = 0L
        readCount = 0L
        writeCount = 0L
        dmaJobCount = 0L
        dmaBytesWritten = 0L
    }

    companion object {
        const val PORT_MAIN_V2 = "soc-panel.main-v2"
        const val PORT_TONE_LUT = "soc-panel.tone-lut-a8000"
        const val WINDOW_SIZE = 72L // 0x48 bytes (0xFFFA8000..0xFFFA8047)
        const val WORD_COUNT = 18
        /** DAT section4 0x011FC5B0 logical IRQ 115 (0x73) -> 0x015A89AC[115] = {0x90, 0xC0, 0x3, 0x1} -> GIC IRQ 144. */
        const val IRQ_LINE = 144
        const val RESET_READY_BIT = 1L
        const val START_BUSY_BIT = 1L
        const val JOB_MODE_BIT = 1L
        private const val MAX_DMA_JOB_BYTES = 1_048_576L

        private val SNAPSHOT_KEYS = setOf("words", "irqAsserted", "irqPulseCount", "readCount", "writeCount")

        internal fun formatBytesPerSample(fmtCode: Int): Int = when (fmtCode) {
            1 -> 2 // 16-bit packed LE (LUT2 TX16 / LUT3 RX16)
            2 -> 3 // 24-bit packed LE (LUT0 TX24 / LUT1 RX24)
            3 -> 4 // 32-bit I2S FIFO word per sample
            else -> 0
        }

        /**
         * Authentic SPX4 `0xFFFA8000` PCM sample bit-packing / unpacking matching DAT LUTs
         * `0x015B1880` (2B->4B), `0x015B1940` (3B->4B), `0x015B1A00` (4B->2B), `0x015B1AC0` (4B->3B).
         */
        internal fun packSamples(
            src: ByteArray,
            dst: ByteArray,
            samples: Int,
            srcBps: Int,
            dstBps: Int,
        ) {
            when {
                srcBps == 4 && dstBps == 3 -> {
                    var s = 0
                    var d = 0
                    for (i in 0 until samples) {
                        dst[d] = src[s]
                        dst[d + 1] = src[s + 1]
                        dst[d + 2] = src[s + 2]
                        s += 4
                        d += 3
                    }
                }
                srcBps == 4 && dstBps == 2 -> {
                    var s = 0
                    var d = 0
                    for (i in 0 until samples) {
                        dst[d] = src[s]
                        dst[d + 1] = src[s + 1]
                        s += 4
                        d += 2
                    }
                }
                srcBps == 3 && dstBps == 4 -> {
                    var s = 0
                    var d = 0
                    for (i in 0 until samples) {
                        val b2 = src[s + 2]
                        dst[d] = src[s]
                        dst[d + 1] = src[s + 1]
                        dst[d + 2] = b2
                        dst[d + 3] = if ((b2.toInt() and 0x80) != 0) 0xFF.toByte() else 0
                        s += 3
                        d += 4
                    }
                }
                srcBps == 2 && dstBps == 4 -> {
                    var s = 0
                    var d = 0
                    for (i in 0 until samples) {
                        val b1 = src[s + 1]
                        val sign = if ((b1.toInt() and 0x80) != 0) 0xFF.toByte() else 0
                        dst[d] = src[s]
                        dst[d + 1] = b1
                        dst[d + 2] = sign
                        dst[d + 3] = sign
                        s += 2
                        d += 4
                    }
                }
                srcBps == dstBps -> {
                    System.arraycopy(src, 0, dst, 0, samples * srcBps)
                }
                else -> {
                    val copy = minOf(srcBps, dstBps)
                    var s = 0
                    var d = 0
                    for (i in 0 until samples) {
                        for (k in 0 until copy) dst[d + k] = src[s + k]
                        for (k in copy until dstBps) dst[d + k] = 0
                        s += srcBps
                        d += dstBps
                    }
                }
            }
        }
    }
}
