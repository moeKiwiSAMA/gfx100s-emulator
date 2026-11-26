package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.InterruptSink

/**
 * Real-silicon SP-X4 Dual-Bank SLVS-EC RX (`0xFFF69000..0xFFF69250` Bank 0 & `0xFFF6A000..0xFFF6A250`
 * Bank 1) + SLVS-EC PHY (`0xFFF6B000..0xFFF6B0F4`) behavioral model (`DAT` from `S4 0x011C7018..0x011C9C08`,
 * `S5 0x02393084..0x02395FB8`, `S5 0x024F6A8C`).
 *
 * Interrupt wiring (`S4 0x015A905C..0x015A908C`, all LEVEL-sensitive on CPU2):
 * - **IRQ `296`**: SLVS-EC Ch0 Normal (`+0x008` W1C & `+0x00C` enable, 6 bits `0xF3`: `b7,b6,b5,b4,b1,b0`)
 * - **IRQ `298`**: SLVS-EC Ch1 Normal (`+0x108` W1C & `+0x10C` enable, 6 bits `0xF3`: `b7,b6,b5,b4,b1,b0`)
 * - **IRQ `297`**: Shared SLVS-EC Error combining:
 *   - Global PCS errors (`+0x024` W1C & `+0x028` enable, 24 bits: `[23:16]` IllegalPointComma, `[15:8]` RunningDisparityError, `[7:0]` InvalidCode)
 *   - Ch0/Ch1 Link errors (`+0x010/+0x110` W1C & `+0x014/+0x114` enable, 10 bits `0x3FF`)
 *   - Ch0/Ch1 MAC errors (`+0x018/+0x118` W1C & `+0x020/+0x120` enable, 4 bits `0x01010101`)
 *   - Ch0/Ch1 FIFO errors (`+0x01C/+0x11C` W1C & `+0x024/+0x124` enable, 16 bits `0xFFFF`)
 */
internal class SlvsEcRxPhyEngine(
    private val bank0GlobalWords: LongArray,
    private val bank0ChWords: LongArray,
    private val phyWords: LongArray,
    private val sinkProvider: () -> InterruptSink?,
) {
    val bank1GlobalWords = LongArray(IspCoreOffsets.CONFIG_69000_OFFSETS.size)
    val bank1ChWords = LongArray(IspCoreOffsets.CONFIG_69100_OFFSETS.size)

    var irq296Asserted: Boolean = false
        private set
    var irq297Asserted: Boolean = false
        private set
    var irq298Asserted: Boolean = false
        private set

    fun globalWordsFor(bank: Int): LongArray = when (bank) {
        0 -> bank0GlobalWords
        1 -> bank1GlobalWords
        else -> throw IllegalArgumentException("SLVS-EC bank out of range: $bank")
    }

    fun chWordsFor(bank: Int): LongArray = when (bank) {
        0 -> bank0ChWords
        1 -> bank1ChWords
        else -> throw IllegalArgumentException("SLVS-EC bank out of range: $bank")
    }

    fun readGlobal(bank: Int, off: Int): Long {
        val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
            ?: error("SLVS-EC bank $bank global unmodeled offset 0x${off.toString(16)}")
        return globalWordsFor(bank)[idx]
    }

    fun writeGlobal(bank: Int, off: Int, value: Long) {
        val idx = IspCoreOffsets.CONFIG_69000_INDEX[off]
            ?: error("SLVS-EC bank $bank global unmodeled offset 0x${off.toString(16)}")
        val words = globalWordsFor(bank)
        when (off) {
            0x024 -> {
                // W1C 3x u8 PCS Error lane masks
                words[idx] = words[idx] and (value and PCS_ERR_MASK).inv()
                recomputeIrqs()
            }
            0x028 -> {
                words[idx] = value and PCS_ERR_MASK
                recomputeIrqs()
            }
            else -> words[idx] = value
        }
    }

    fun readChannel(bank: Int, off: Int): Long {
        val idx = IspCoreOffsets.CONFIG_69100_INDEX[off]
            ?: error("SLVS-EC bank $bank channel unmodeled offset 0x${off.toString(16)}")
        return chWordsFor(bank)[idx]
    }

    fun writeChannel(bank: Int, off: Int, value: Long) {
        require(off in IspCoreOffsets.CONFIG_69100_WRITE_OFFSETS) {
            "SLVS-EC bank $bank channel write refused at RO offset 0x${off.toString(16)}"
        }
        val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(off)
        val words = chWordsFor(bank)
        val localOff = off and 0xFF
        when (localOff) {
            0x008 -> {
                words[idx] = words[idx] and (value and NORMAL_IRQ_MASK).inv()
                recomputeIrqs()
            }
            0x00C -> {
                words[idx] = value and NORMAL_IRQ_MASK
                recomputeIrqs()
            }
            0x010 -> {
                words[idx] = words[idx] and (value and LINK_ERR_MASK).inv()
                recomputeIrqs()
            }
            0x014 -> {
                words[idx] = value and LINK_ERR_MASK
                recomputeIrqs()
            }
            0x018 -> {
                words[idx] = words[idx] and (value and MAC_ERR_MASK).inv()
                recomputeIrqs()
            }
            0x01C -> {
                words[idx] = words[idx] and (value and FIFO_ERR_MASK).inv()
                recomputeIrqs()
            }
            0x020 -> {
                words[idx] = value and MAC_ERR_MASK
                recomputeIrqs()
            }
            0x024 -> {
                words[idx] = value and FIFO_ERR_MASK
                recomputeIrqs()
            }
            else -> words[idx] = value
        }
    }

    fun readPhy(off: Int): Long {
        val idx = IspCoreOffsets.CONFIG_6B000_INDEX[off]
            ?: error("SLVS-EC PHY unmodeled offset 0x${off.toString(16)}")
        val stored = phyWords[idx]
        if (off == 0xF4 && isPhyLocked()) {
            return stored or 0x1L
        }
        return stored
    }

    fun writePhy(off: Int, value: Long) {
        val idx = IspCoreOffsets.CONFIG_6B000_INDEX[off]
            ?: error("SLVS-EC PHY unmodeled offset 0x${off.toString(16)}")
        phyWords[idx] = value
    }

    /** Active 8-lane bitmask on [bank]: global `+0x0C[7:0]` is active-LOW (`0` = enabled, `1` = disabled). */
    fun activeLaneMask(bank: Int): Int {
        val reg0C = globalWordsFor(bank)[IspCoreOffsets.CONFIG_69000_INDEX.getValue(0x0C)]
        return (reg0C.inv() and 0xFFL).toInt()
    }

    fun isPhyLocked(): Boolean {
        val b004 = phyWords[IspCoreOffsets.CONFIG_6B000_INDEX.getValue(0x04)]
        val b008 = phyWords[IspCoreOffsets.CONFIG_6B000_INDEX.getValue(0x08)]
        val b0f4 = phyWords[IspCoreOffsets.CONFIG_6B000_INDEX.getValue(0xF4)]
        val phyConfigured = (b004 != 0L || b008 != 0L || b0f4 != 0L)
        val lanesEnabled = activeLaneMask(0) != 0 || activeLaneMask(1) != 0
        return phyConfigured && lanesEnabled
    }

    /** Latches Normal IRQ bits (`0xF3`) on `(bank, ch)` and asserts IRQ 296 (ch0) or IRQ 298 (ch1) if enabled. */
    fun raiseNormalIrq(bank: Int, channel: Int, bits: Long) {
        require(channel in 0..1) { "SLVS-EC channel must be 0 or 1" }
        val base = channel * 0x100
        val idxSt = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x008)
        val words = chWordsFor(bank)
        words[idxSt] = words[idxSt] or (bits and NORMAL_IRQ_MASK)
        recomputeIrqs()
    }

    /** Latches global PCS error bits (`0xFFFFFF`) on [bank] and asserts shared error IRQ 297 if enabled. */
    fun raisePcsError(bank: Int, bits: Long) {
        val idxSt = IspCoreOffsets.CONFIG_69000_INDEX.getValue(0x024)
        val words = globalWordsFor(bank)
        words[idxSt] = words[idxSt] or (bits and PCS_ERR_MASK)
        recomputeIrqs()
    }

    /** Latches channel Link/MAC/FIFO error bits on `(bank, ch)`, increments RO error counters `+0x44/+0x48`, and updates IRQ 297. */
    fun raiseChannelError(
        bank: Int,
        channel: Int,
        linkBits: Long = 0L,
        macBits: Long = 0L,
        fifoBits: Long = 0L,
    ) {
        require(channel in 0..1) { "SLVS-EC channel must be 0 or 1" }
        val base = channel * 0x100
        val words = chWordsFor(bank)
        if (linkBits != 0L) {
            val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x010)
            words[idx] = words[idx] or (linkBits and LINK_ERR_MASK)
        }
        if (macBits != 0L) {
            val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x018)
            words[idx] = words[idx] or (macBits and MAC_ERR_MASK)
        }
        if (fifoBits != 0L) {
            val idx = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x01C)
            words[idx] = words[idx] or (fifoBits and FIFO_ERR_MASK)
        }
        if ((linkBits or macBits or fifoBits) != 0L) {
            // Increment RO error diagnostic counter fields (+0x44[12:0] and +0x48[12:0]) read by ccdtgSlvsEcErrInt
            val idx44 = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x044)
            val count44 = ((words[idx44] and 0x1FFFL) + 1L) and 0x1FFFL
            words[idx44] = (words[idx44] and 0x1FFFL.inv()) or count44
            val idx48 = IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x048)
            val count48 = ((words[idx48] and 0x1FFFL) + 1L) and 0x1FFFL
            words[idx48] = (words[idx48] and 0x1FFFL.inv()) or count48
        }
        recomputeIrqs()
    }

    fun recomputeIrqs() {
        var ch0Normal = false
        var ch1Normal = false
        var anyError = false
        for (bank in 0..1) {
            val gw = globalWordsFor(bank)
            val pcsPending = gw[IspCoreOffsets.CONFIG_69000_INDEX.getValue(0x024)] and
                gw[IspCoreOffsets.CONFIG_69000_INDEX.getValue(0x028)] and PCS_ERR_MASK
            if (pcsPending != 0L) anyError = true

            val cw = chWordsFor(bank)
            val ch0Norm = cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x008)] and
                cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x00C)] and NORMAL_IRQ_MASK
            if (ch0Norm != 0L) ch0Normal = true

            val ch1Norm = cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x108)] and
                cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x10C)] and NORMAL_IRQ_MASK
            if (ch1Norm != 0L) ch1Normal = true

            for (base in intArrayOf(0x000, 0x100)) {
                val link = cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x010)] and
                    cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x014)] and LINK_ERR_MASK
                val mac = cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x018)] and
                    cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x020)] and MAC_ERR_MASK
                val fifo = cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x01C)] and
                    cw[IspCoreOffsets.CONFIG_69100_INDEX.getValue(base + 0x024)] and FIFO_ERR_MASK
                if ((link or mac or fifo) != 0L) anyError = true
            }
        }
        val sink = sinkProvider()
        if (ch0Normal != irq296Asserted) {
            irq296Asserted = ch0Normal
            sink?.setLine(IRQ_SLVSEC_CH0, ch0Normal)
        }
        if (anyError != irq297Asserted) {
            irq297Asserted = anyError
            sink?.setLine(IRQ_SLVSEC_ERR, anyError)
        }
        if (ch1Normal != irq298Asserted) {
            irq298Asserted = ch1Normal
            sink?.setLine(IRQ_SLVSEC_CH1, ch1Normal)
        }
    }

    fun syncIrqLines() {
        val sink = sinkProvider() ?: return
        sink.setLine(IRQ_SLVSEC_CH0, irq296Asserted)
        sink.setLine(IRQ_SLVSEC_ERR, irq297Asserted)
        sink.setLine(IRQ_SLVSEC_CH1, irq298Asserted)
    }

    fun clearIrqsOnClose() {
        val sink = sinkProvider()
        if (irq296Asserted) {
            irq296Asserted = false
            sink?.setLine(IRQ_SLVSEC_CH0, false)
        }
        if (irq297Asserted) {
            irq297Asserted = false
            sink?.setLine(IRQ_SLVSEC_ERR, false)
        }
        if (irq298Asserted) {
            irq298Asserted = false
            sink?.setLine(IRQ_SLVSEC_CH1, false)
        }
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "bank0ActiveLaneMask" to activeLaneMask(0),
        "bank1ActiveLaneMask" to activeLaneMask(1),
        "bank0Ch0Enabled" to ((bank0ChWords[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x000)] and 1L) != 0L),
        "bank0Ch1Enabled" to ((bank0ChWords[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x100)] and 1L) != 0L),
        "bank1Ch0Enabled" to ((bank1ChWords[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x000)] and 1L) != 0L),
        "bank1Ch1Enabled" to ((bank1ChWords[IspCoreOffsets.CONFIG_69100_INDEX.getValue(0x100)] and 1L) != 0L),
        "phyLocked" to isPhyLocked(),
        "irq296Asserted" to irq296Asserted,
        "irq297Asserted" to irq297Asserted,
        "irq298Asserted" to irq298Asserted,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "bank1GlobalWords" to bank1GlobalWords.toList(),
        "bank1ChWords" to bank1ChWords.toList(),
        "irq296Asserted" to irq296Asserted,
        "irq297Asserted" to irq297Asserted,
        "irq298Asserted" to irq298Asserted,
    )

    fun restoreSnapshot(raw: Any?) {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid slvsEc snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected slvsEc snapshot keys" }
        val g1 = decodeU32List(map["bank1GlobalWords"], "slvsEc.bank1GlobalWords", bank1GlobalWords.size)
        val c1 = decodeU32List(map["bank1ChWords"], "slvsEc.bank1ChWords", bank1ChWords.size)
        val i296 = map["irq296Asserted"] as? Boolean ?: throw IllegalArgumentException("slvsEc.irq296Asserted requires boolean")
        val i297 = map["irq297Asserted"] as? Boolean ?: throw IllegalArgumentException("slvsEc.irq297Asserted requires boolean")
        val i298 = map["irq298Asserted"] as? Boolean ?: throw IllegalArgumentException("slvsEc.irq298Asserted requires boolean")
        g1.copyInto(bank1GlobalWords)
        c1.copyInto(bank1ChWords)
        irq296Asserted = i296
        irq297Asserted = i297
        irq298Asserted = i298
    }

    companion object {
        const val PORT_SLVSEC_BANK1_GLOBAL = "soc-isp.slvsec-bank1-global"
        const val PORT_SLVSEC_BANK1_CH = "soc-isp.slvsec-bank1-ch"
        const val IRQ_SLVSEC_CH0 = 296
        const val IRQ_SLVSEC_ERR = 297
        const val IRQ_SLVSEC_CH1 = 298

        const val NORMAL_IRQ_MASK = 0xF3L // bits 7,6,5,4,1,0
        const val PCS_ERR_MASK = 0x00FF_FFFFL // 3x u8 lane masks
        const val LINK_ERR_MASK = 0x3FFL // bits [9:0]
        const val MAC_ERR_MASK = 0x0101_0101L // bits 24,16,8,0
        const val FIFO_ERR_MASK = 0xFFFFL // [15:8] UnderFlow, [7:0] OverFlow

        private val SNAPSHOT_KEYS = setOf(
            "bank1GlobalWords",
            "bank1ChWords",
            "irq296Asserted",
            "irq297Asserted",
            "irq298Asserted",
        )

        private fun decodeU32List(raw: Any?, field: String, expectedSize: Int): LongArray {
            val list = raw as? List<*> ?: throw IllegalArgumentException("$field requires list")
            require(list.size == expectedSize) { "$field size mismatch: ${list.size} != $expectedSize" }
            return LongArray(expectedSize) { i ->
                val v = list[i]
                require(v is Byte || v is Short || v is Int || v is Long) { "$field[$i] requires integer" }
                val n = (v as Number).toLong()
                require(n in 0L..0xFFFF_FFFFL) { "$field[$i] out of u32 range: $n" }
                n
            }
        }
    }
}
