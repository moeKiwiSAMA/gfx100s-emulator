package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.IspStillReadout
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.RetainedMmioBank
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice

/**
 * DAT-verified SP-X4 Capture, Movie, and Playback Menu Companion Hardware Engines (`soc-isp` v3).
 *
 * Each engine models the exact reset-ready poll semantics, DMA idle handshake bits, start trigger,
 * and level-sensitive GIC interrupt acknowledge path disassembled from GFX100S 2.13 `section4.bin`
 * and `section5.bin`:
 * - `MOVDC` (`0xFFF7D000..0xFFF7D13B`, GIC IRQ `110`): `S5 0x021F0240..0x021F1190`
 * - `Companion 0xFFF80000` (`0xFFF80000..0xFFF8003F`, GIC IRQ `126`): `S5 0x021E1B00..0x021E2360`
 * - `Companion 0xFFFB9000` (`0xFFFB9000..0xFFFB904B`, GIC IRQ `127`): `S5 0x021E23B4..0x021E2DA0`
 * - `Companion 0xFFF6F000` (`0xFFF6F000..0xFFF6F08B`, GIC IRQ `134`): `S5 0x021E2DA4..0x021E3C00`
 * - `LSRAW` (`0xFFFA2000..0xFFFA2147`, GIC IRQ `137`): `S5 0x021E6920..0x021E7CA0`
 * - `UniDmac` (`0xFFFBA000..0xFFFBA04F`, GIC IRQ `139`): `S5 0x021EF328..0x021F0020`
 * - `RETCH` (`0xFFFA3000..0xFFFA30AB`, GIC IRQ `142`): `S5 0x021E5270..0x021E687C`
 * - `AXI High-34 Bank` (`0xFFF4A700..0xFFF4A793`): `S4 0x011E2F10`/`0x011FF464`, `S5 0x021E7078`
 * - `VCodec Idle Status` (`0xFA400518..0xFA40051B`): `S4 0x011E2DD0..0x011E2DDC`
 * - `RECC_S Main STILL Raw->YUV Engine` (`0xFFF82000..0xFFF82097` rising-edge GIC IRQ `129`,
 *   companion config banks `0xFFF83000..0xFFF836BB` & `0xFFF85000..0xFFF850EF`): `S5 0x021E922C..0x021EF328`
 * - `Companion 0xFFFBB000` (`0xFFFBB000..0xFFFBB09F`, level GIC IRQ `140`): `S5 0x021E3EE4..0x021E5270`
 *
 * Completion delay (`COMPLETION_DELAY_TICKS = 64`) and internal pixel pipeline latency remain HYPOTHESIS.
 */
internal class ImagingCompanionEngines(
    private val clockProvider: () -> Long,
    private val sinkProvider: () -> InterruptSink?,
) {
    companion object {
        const val COMPLETION_DELAY_TICKS = 64L

        const val PORT_MOVDC = "soc-isp.movdc"
        const val PORT_COMPANION_80000 = "soc-isp.companion-80000"
        const val PORT_COMPANION_B9000 = "soc-isp.companion-b9000"
        const val PORT_COMPANION_6F000 = "soc-isp.companion-6f000"
        const val PORT_LSRAW = "soc-isp.lsraw"
        const val PORT_UNIDMAC_BA000 = "soc-isp.unidmac-ba000"
        const val PORT_COMPANION_BB000 = "soc-isp.companion-bb000"
        const val PORT_RETCH = "soc-isp.retch"
        const val PORT_AXI_HIGH34 = "soc-isp.axi-high34"
        const val PORT_VCODEC_IDLE = "soc-isp.vcodec-idle"
        const val PORT_ISP_82000 = "soc-isp.isp-82000"
        const val PORT_ISP_83000 = "soc-isp.isp-83000"
        const val PORT_ISP_84000 = "soc-isp.isp-84000"
        const val PORT_ISP_85000 = "soc-isp.isp-85000"
        const val PORT_ISP_86000 = "soc-isp.isp-86000"
        const val PORT_ISP_87000 = "soc-isp.isp-87000"
        const val PORT_ISP_88000 = "soc-isp.isp-88000"
        const val PORT_ISP_89000 = "soc-isp.isp-89000"
        const val PORT_ISP_8A000 = "soc-isp.isp-8a000"
        const val PORT_ISP_90000 = "soc-isp.isp-90000"
        const val PORT_ISP_98000 = "soc-isp.isp-98000"

        const val MOVDC_SIZE = 0x13CL
        const val COMPANION_80000_SIZE = 0x40L
        const val COMPANION_B9000_SIZE = 0x4CL
        const val COMPANION_6F000_SIZE = 0x8CL
        const val LSRAW_SIZE = 0x148L
        const val UNIDMAC_BA000_SIZE = 0x50L
        const val COMPANION_BB000_SIZE = 0xA0L
        const val RETCH_SIZE = 0xACL
        const val AXI_HIGH34_SIZE = 0x94L
        const val VCODEC_IDLE_SIZE = 0x4L
        const val ISP_82000_SIZE = 0x98L
        const val ISP_83000_SIZE = 0x6BCL
        const val ISP_84000_SIZE = 0x78L
        const val ISP_85000_SIZE = 0xF0L
        const val ISP_86000_SIZE = 0x304L
        const val ISP_87000_SIZE = 0xF04L
        const val ISP_88000_SIZE = 0xFA4L
        const val ISP_89000_SIZE = 0xFA4L
        const val ISP_8A000_SIZE = 0x66CL
        const val ISP_90000_SIZE = 0x8000L
        const val ISP_98000_SIZE = 0x1A8L

        const val IRQ_MOVDC = 110
        const val IRQ_80000 = 126
        const val IRQ_B9000 = 127
        const val IRQ_ISP_82000 = 129
        const val IRQ_6F000 = 134
        const val IRQ_LSRAW = 137
        const val IRQ_UNIDMAC = 139
        const val IRQ_BB000 = 140
        const val IRQ_RETCH = 142

        // LSRAW 12-bit IRQ layout (S5 0x021E6988..0x021E6B00, 0x021E7150..0x021E7CF0):
        //   0xFFFA2008 is an ACTIVE-LOW IRQ MASK (0 = unmasked/enabled, 1 = masked/disabled):
        //     - Open/Init (0x021E7270 -> 0xFFFA2100 = 1): unmasks bit 16 (byte[6]), ISR 0x021E7150 -> state=2
        //     - Encode   (0x021E7710 -> 0xFFFA2000 = 1): unmasks bit  0 (byte[11]), ISR 0x021E7C04 -> state=4 (bits 1/2 are errors!)
        //     - Decode   (0x021E78AC -> 0xFFFA2000 = 2): unmasks bit  8 (byte[8]),  ISR 0x021E7A00 -> state=7 (bit 10 is error!)
        const val LSRAW_IRQ_VALID_MASK = 0x00FD_0507L
        const val LSRAW_IRQ_INIT_DONE = 1L shl 16
        const val LSRAW_IRQ_DECODE_DONE = 1L shl 8
        const val LSRAW_IRQ_ENCODE_DONE = 1L shl 0
        private const val RETCH_IRQ_VALID_MASK = 0x0000_1331L
        private const val RETCH_IRQ_DONE_BIT = 1L

        private val SNAPSHOT_KEYS_LEGACY = setOf(
            "movdc", "c80000", "cb9000", "c6f000", "lsraw", "unidmac", "retch",
            "axiHigh34Words", "isp82000Words",
        )
        private val SNAPSHOT_KEYS_V3_1 = SNAPSHOT_KEYS_LEGACY + setOf(
            "cbb000", "isp83000Words", "isp85000Words", "isp82000PendingTick", "isp82000Completions",
        )
        private val SNAPSHOT_KEYS_V3_2 = SNAPSHOT_KEYS_V3_1 + setOf(
            "isp84000Words", "isp86000Words", "isp87000Words", "isp88000Words", "isp89000Words", "isp8a000Words",
        )
        private val SNAPSHOT_KEYS = SNAPSHOT_KEYS_V3_2 + setOf(
            "isp90000Words", "isp98000Words",
        )
        private val ENGINE_SNAPSHOT_KEYS = setOf(
            "words", "irqStatus", "irqEnable", "pendingTick", "irqAsserted", "completions",
        )
    }

    internal data class EngineSpec(
        val portId: String,
        val windowSize: Long,
        val irqLine: Int,
        val resetBitMask: Long, // bit asserted on +0x000 read (1L or 0x80000000L)
        val resetOnZero: Boolean, // true if writing 0 to +0x000 resets; false if clearing resetBitMask resets
        val startOffset: Int, // offset where writing bit 0 triggers execution
        val idleOffset: Int, // -1 if none
        val idleReadyBits: Long, // bits asserted on idleOffset read
        val irqStatusOffset: Int, // RO or RW status register
        val irqClearOffset: Int, // W1C register (may equal irqStatusOffset)
        val irqEnableOffset: Int, // -1 if always enabled
        val irqValidMask: Long,
        val defaultEnableMask: Long,
        val clockIndex: Int,
        val irqEnableActiveLow: Boolean = false, // true when 0 = unmasked/enabled, 1 = masked (e.g. LSRAW 0xFFFA2008)
    )

    internal inner class IrqCompanionEngine(val spec: EngineSpec) {
        val words = LongArray((spec.windowSize / 4L).toInt())
        var irqStatus = 0L
        var irqEnable = spec.defaultEnableMask
        var pendingTick: Long? = null
        var irqAsserted = false
        var completions = 0L

        internal fun updateIrq() {
            val enabledMask = if (spec.irqEnableActiveLow) irqEnable.inv() else irqEnable
            val active = (irqStatus and enabledMask and spec.irqValidMask) != 0L
            if (active != irqAsserted) {
                irqAsserted = active
                sinkProvider()?.setLine(spec.irqLine, active)
            }
        }

        fun syncIrqLine() {
            sinkProvider()?.setLine(spec.irqLine, irqAsserted)
        }

        fun clearIrqOnClose() {
            if (irqAsserted) {
                irqAsserted = false
                sinkProvider()?.setLine(spec.irqLine, false)
            }
        }

        fun softReset() {
            pendingTick = null
            irqStatus = 0L
            words[spec.startOffset / 4] = words[spec.startOffset / 4] and 1L.inv()
            updateIrq()
        }

        fun createMmioDevice(
            isClosed: () -> Boolean,
            completionBitsProvider: (LongArray) -> Long = { 1L },
            onComplete: ((LongArray) -> Unit)? = null,
        ): TickAwareMmioDevice = object : TickAwareMmioDevice {
            override val clockOrder = 83
            override val clockIndex = spec.clockIndex

            override fun nextDeadline(): Long? = if (isClosed()) null else pendingTick
            override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

            override fun beforeInstruction(tick: Long) {
                if (isClosed()) return
                val due = pendingTick ?: return
                if (tick < due) return
                pendingTick = null
                words[spec.startOffset / 4] = words[spec.startOffset / 4] and 1L.inv()
                onComplete?.invoke(words)
                val bits = completionBitsProvider(words) and spec.irqValidMask
                irqStatus = irqStatus or bits
                completions++
                updateIrq()
            }

            override fun read(offset: Long, size: Int): Long {
                check(!isClosed()) { "${spec.portId} closed" }
                require(size == 4 && offset in 0L until spec.windowSize && offset % 4L == 0L) {
                    "${spec.portId} requires aligned 32-bit read within [0, 0x${spec.windowSize.toString(16)}): off=0x${offset.toString(16)} size=$size"
                }
                val off = offset.toInt()
                return when (off) {
                    0x000 -> words[0] or spec.resetBitMask
                    spec.idleOffset -> words[off / 4] or spec.idleReadyBits
                    spec.irqStatusOffset -> irqStatus and spec.irqValidMask
                    spec.irqEnableOffset -> irqEnable
                    else -> words[off / 4]
                }
            }

            override fun write(offset: Long, size: Int, value: Long) {
                check(!isClosed()) { "${spec.portId} closed" }
                require(size == 4 && offset in 0L until spec.windowSize && offset % 4L == 0L) {
                    "${spec.portId} requires aligned 32-bit write within [0, 0x${spec.windowSize.toString(16)}): off=0x${offset.toString(16)} size=$size"
                }
                require(value in 0L..0xFFFF_FFFFL) { "${spec.portId} write value outside u32: $value" }
                val off = offset.toInt()
                when {
                    off == 0x000 && ((spec.resetOnZero && value == 0L) || (!spec.resetOnZero && spec.resetBitMask != 0L && (value and spec.resetBitMask) == 0L)) -> {
                        words[0] = value
                        softReset()
                    }
                    off == spec.irqClearOffset -> {
                        irqStatus = irqStatus and (value and spec.irqValidMask).inv()
                        words[off / 4] = value
                        updateIrq()
                    }
                    off == spec.irqEnableOffset -> {
                        irqEnable = value and spec.irqValidMask
                        words[off / 4] = irqEnable
                        updateIrq()
                    }
                    off == spec.startOffset -> {
                        words[off / 4] = value
                        val isResetBit = spec.portId == PORT_LSRAW && (value and 0x100L) != 0L
                        if (isResetBit) {
                            words[off / 4] = value and 0x100L.inv()
                            softReset()
                        } else if ((value and 1L) != 0L) {
                            pendingTick = clockProvider() + COMPLETION_DELAY_TICKS
                        }
                    }
                    else -> words[off / 4] = value
                }
            }
        }

        fun state(): Map<String, Any?> = linkedMapOf(
            "irqLine" to spec.irqLine,
            "irqStatus" to irqStatus,
            "irqEnable" to irqEnable,
            "irqAsserted" to irqAsserted,
            "pendingTick" to pendingTick,
            "completions" to completions,
        )

        fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
            "words" to words.toList(),
            "irqStatus" to irqStatus,
            "irqEnable" to irqEnable,
            "pendingTick" to pendingTick,
            "irqAsserted" to irqAsserted,
            "completions" to completions,
        )

        fun restoreSnapshot(raw: Any?, name: String) {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid $name snapshot")
            require(map.keys == ENGINE_SNAPSHOT_KEYS) { "Unexpected $name snapshot keys" }
            val rawWords = map["words"] as? List<*> ?: throw IllegalArgumentException("$name.words requires list")
            require(rawWords.size == words.size) { "$name.words size mismatch" }
            val nextWords = LongArray(words.size) { i -> reqLong(rawWords[i], "$name.words[$i]", 0L, 0xFFFF_FFFFL) }
            val nextStatus = reqLong(map["irqStatus"], "$name.irqStatus", 0L, 0xFFFF_FFFFL)
            val nextEnable = reqLong(map["irqEnable"], "$name.irqEnable", 0L, 0xFFFF_FFFFL)
            val nextPending = map["pendingTick"]?.let { reqLong(it, "$name.pendingTick", 0L, Long.MAX_VALUE) }
            val nextIrq = map["irqAsserted"] as? Boolean ?: throw IllegalArgumentException("$name.irqAsserted requires boolean")
            val nextComp = reqLong(map["completions"], "$name.completions", 0L, Long.MAX_VALUE)
            nextWords.copyInto(words)
            irqStatus = nextStatus
            irqEnable = nextEnable
            pendingTick = nextPending
            irqAsserted = nextIrq
            completions = nextComp
        }
    }

    val movdc = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_MOVDC,
            windowSize = MOVDC_SIZE,
            irqLine = IRQ_MOVDC,
            resetBitMask = 1L,
            resetOnZero = true,
            startOffset = 0x004,
            idleOffset = 0x124,
            idleReadyBits = 0x0001_0001L,
            irqStatusOffset = 0x134,
            irqClearOffset = 0x134,
            irqEnableOffset = 0x138,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 1,
        ),
    )

    val companion80000 = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_COMPANION_80000,
            windowSize = COMPANION_80000_SIZE,
            irqLine = IRQ_80000,
            resetBitMask = 0x8000_0000L,
            resetOnZero = false,
            startOffset = 0x000,
            idleOffset = 0x028,
            idleReadyBits = 0x0100_0100L,
            irqStatusOffset = 0x02C,
            irqClearOffset = 0x02C,
            irqEnableOffset = -1,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 2,
        ),
    )

    val companionB9000 = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_COMPANION_B9000,
            windowSize = COMPANION_B9000_SIZE,
            irqLine = IRQ_B9000,
            resetBitMask = 1L,
            resetOnZero = true,
            startOffset = 0x004,
            idleOffset = 0x040,
            idleReadyBits = 0x0001_0001L,
            irqStatusOffset = 0x044,
            irqClearOffset = 0x044,
            irqEnableOffset = -1,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 3,
        ),
    )

    val companion6F000 = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_COMPANION_6F000,
            windowSize = COMPANION_6F000_SIZE,
            irqLine = IRQ_6F000,
            resetBitMask = 0x8000_0000L,
            resetOnZero = false,
            startOffset = 0x000,
            idleOffset = 0x078,
            idleReadyBits = 0x0100_0100L,
            irqStatusOffset = 0x080,
            irqClearOffset = 0x084,
            irqEnableOffset = 0x088,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 4,
        ),
    )

    val lsraw = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_LSRAW,
            windowSize = LSRAW_SIZE,
            irqLine = IRQ_LSRAW,
            resetBitMask = 0L,
            resetOnZero = false,
            startOffset = 0x000,
            idleOffset = -1,
            idleReadyBits = 0L,
            irqStatusOffset = 0x004,
            irqClearOffset = 0x004,
            irqEnableOffset = 0x008,
            irqValidMask = LSRAW_IRQ_VALID_MASK,
            defaultEnableMask = LSRAW_IRQ_VALID_MASK, // active-low mask: all bits masked at reset
            clockIndex = 5,
            irqEnableActiveLow = true,
        ),
    )

    val unidmacBA000 = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_UNIDMAC_BA000,
            windowSize = UNIDMAC_BA000_SIZE,
            irqLine = IRQ_UNIDMAC,
            resetBitMask = 0x8000_0000L,
            resetOnZero = false,
            startOffset = 0x000,
            idleOffset = 0x040,
            idleReadyBits = 0x0100_0100L,
            irqStatusOffset = 0x044,
            irqClearOffset = 0x048,
            irqEnableOffset = 0x04C,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 6,
        ),
    )

    val companionBB000 = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_COMPANION_BB000,
            windowSize = COMPANION_BB000_SIZE,
            irqLine = IRQ_BB000,
            resetBitMask = 1L,
            resetOnZero = true,
            startOffset = 0x004,
            idleOffset = 0x098,
            idleReadyBits = 0x0004_0004L,
            irqStatusOffset = 0x09C,
            irqClearOffset = 0x09C,
            irqEnableOffset = -1,
            irqValidMask = 1L,
            defaultEnableMask = 1L,
            clockIndex = 8,
        ),
    )

    val retch = IrqCompanionEngine(
        EngineSpec(
            portId = PORT_RETCH,
            windowSize = RETCH_SIZE,
            irqLine = IRQ_RETCH,
            resetBitMask = 1L,
            resetOnZero = true,
            startOffset = 0x004,
            idleOffset = 0x098,
            idleReadyBits = 0x0001_0001L,
            irqStatusOffset = 0x0A0,
            irqClearOffset = 0x0A4,
            irqEnableOffset = 0x0A8,
            irqValidMask = RETCH_IRQ_VALID_MASK,
            defaultEnableMask = RETCH_IRQ_VALID_MASK,
            clockIndex = 7,
        ),
    )

    private val axiHigh34Words = LongArray((AXI_HIGH34_SIZE / 4L).toInt())
    private val isp82000Words = LongArray((ISP_82000_SIZE / 4L).toInt())
    private val isp83000Words = LongArray((ISP_83000_SIZE / 4L).toInt())
    private val isp84000Words = LongArray((ISP_84000_SIZE / 4L).toInt())
    private val isp85000Words = LongArray((ISP_85000_SIZE / 4L).toInt())
    private val isp86000Words = LongArray((ISP_86000_SIZE / 4L).toInt())
    private val isp87000Words = LongArray((ISP_87000_SIZE / 4L).toInt())
    private val isp88000Words = LongArray((ISP_88000_SIZE / 4L).toInt())
    private val isp89000Words = LongArray((ISP_89000_SIZE / 4L).toInt())
    private val isp8a000Words = LongArray((ISP_8A000_SIZE / 4L).toInt())
    private val isp90000Words = LongArray((ISP_90000_SIZE / 4L).toInt())
    private val isp98000Words = LongArray((ISP_98000_SIZE / 4L).toInt())
    internal val isp86000Regs: LongArray get() = isp86000Words
    internal val isp87000Regs: LongArray get() = isp87000Words
    internal val isp88000Regs: LongArray get() = isp88000Words
    internal val isp98000Regs: LongArray get() = isp98000Words
    private var isp82000PendingTick: Long? = null
    private var isp82000Completions = 0L
    private var lastLsrawEncode: LsrawCodecEngine.EncodeResult? = null

    private val allIrqEngines = listOf(
        movdc,
        companion80000,
        companionB9000,
        companion6F000,
        lsraw,
        unidmacBA000,
        companionBB000,
        retch,
    )

    fun createLsrawMmioDevice(
        isClosed: () -> Boolean,
        memoryProvider: () -> DmaMemory? = { null },
    ): TickAwareMmioDevice = object : TickAwareMmioDevice {
        override val clockOrder = 83
        override val clockIndex = lsraw.spec.clockIndex

        override fun nextDeadline(): Long? = if (isClosed()) null else lsraw.pendingTick
        override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

        override fun beforeInstruction(tick: Long) {
            if (isClosed()) return
            val due = lsraw.pendingTick ?: return
            if (tick < due) return
            lsraw.pendingTick = null

            val w = lsraw.words
            val initPending = (w[0x100 / 4] and 1L) != 0L
            val decPending = (w[0x000 / 4] and 2L) != 0L
            val encPending = (w[0x000 / 4] and 1L) != 0L

            val completionBit = when {
                initPending -> {
                    w[0x100 / 4] = w[0x100 / 4] and 1L.inv()
                    LSRAW_IRQ_INIT_DONE
                }
                decPending -> {
                    w[0x000 / 4] = w[0x000 / 4] and 3L.inv()
                    LsrawCodecEngine.executeDecode(w, axiHigh34Words, memoryProvider())
                    LSRAW_IRQ_DECODE_DONE
                }
                encPending -> {
                    w[0x000 / 4] = w[0x000 / 4] and 3L.inv()
                    lastLsrawEncode = LsrawCodecEngine.executeEncode(w, axiHigh34Words, memoryProvider())
                    LSRAW_IRQ_ENCODE_DONE
                }
                else -> LSRAW_IRQ_ENCODE_DONE
            }

            lsraw.irqStatus = lsraw.irqStatus or (completionBit and LSRAW_IRQ_VALID_MASK)
            lsraw.completions++
            lsraw.updateIrq()
        }

        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_LSRAW closed" }
            require(size == 4 && offset in 0L until LSRAW_SIZE && offset % 4L == 0L) {
                "$PORT_LSRAW requires aligned 32-bit read within [0, 0x${LSRAW_SIZE.toString(16)}): off=0x${offset.toString(16)} size=$size"
            }
            val off = offset.toInt()
            return when (off) {
                0x004 -> lsraw.irqStatus and LSRAW_IRQ_VALID_MASK
                0x008 -> lsraw.irqEnable and LSRAW_IRQ_VALID_MASK
                0x00C -> 0L // Diagnostic error flags: 0 = no error
                else -> lsraw.words[off / 4]
            }
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_LSRAW closed" }
            require(size == 4 && offset in 0L until LSRAW_SIZE && offset % 4L == 0L) {
                "$PORT_LSRAW requires aligned 32-bit write within [0, 0x${LSRAW_SIZE.toString(16)}): off=0x${offset.toString(16)} size=$size"
            }
            require(value in 0L..0xFFFF_FFFFL) { "$PORT_LSRAW write value outside u32: $value" }
            val off = offset.toInt()
            when (off) {
                0x000 -> {
                    lsraw.words[0] = value
                    if ((value and 0x100L) != 0L) {
                        // Soft reset (S5 0x021E7228): self-clears bit 8 immediately
                        lsraw.words[0] = value and 0x103L.inv()
                        lsraw.words[0x100 / 4] = lsraw.words[0x100 / 4] and 1L.inv()
                        lsraw.softReset()
                    } else if ((value and 3L) != 0L) {
                        // bit 0 = Encode Start (0x021E77E8), bit 1 = Decode Start (0x021E7984)
                        lsraw.pendingTick = clockProvider() + COMPLETION_DELAY_TICKS
                    }
                }
                0x004 -> {
                    lsraw.irqStatus = lsraw.irqStatus and (value and LSRAW_IRQ_VALID_MASK).inv()
                    lsraw.words[off / 4] = value
                    lsraw.updateIrq()
                }
                0x008 -> {
                    lsraw.irqEnable = value and LSRAW_IRQ_VALID_MASK
                    lsraw.words[off / 4] = lsraw.irqEnable
                    lsraw.updateIrq()
                }
                0x100 -> {
                    lsraw.words[off / 4] = value and 1L
                    if ((value and 1L) != 0L) {
                        // Power-on/Init trigger (S5 0x021E7010 / 0x021E734C) -> asserts bit 16
                        lsraw.pendingTick = clockProvider() + COMPLETION_DELAY_TICKS
                    }
                }
                else -> lsraw.words[off / 4] = value
            }
        }
    }

    fun createRetchMmioDevice(isClosed: () -> Boolean, memoryProvider: () -> DmaMemory?): TickAwareMmioDevice = retch.createMmioDevice(
        isClosed = isClosed,
        completionBitsProvider = { RETCH_IRQ_DONE_BIT },
        onComplete = { words -> RetchResampler.execute(words, memoryProvider()) },
    )

    fun createAxiHigh34Device(isClosed: () -> Boolean): MmioDevice = object : MmioDevice {
        override val clockOrder = 82
        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_AXI_HIGH34 closed" }
            require(size == 4 && offset in 0L until AXI_HIGH34_SIZE && offset % 4L == 0L) {
                "$PORT_AXI_HIGH34 invalid read off=0x${offset.toString(16)} size=$size"
            }
            return axiHigh34Words[(offset / 4L).toInt()]
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_AXI_HIGH34 closed" }
            require(size == 4 && offset in 0L until AXI_HIGH34_SIZE && offset % 4L == 0L) {
                "$PORT_AXI_HIGH34 invalid write off=0x${offset.toString(16)} size=$size"
            }
            require(value in 0L..0xFFFF_FFFFL) { "$PORT_AXI_HIGH34 value outside u32: $value" }
            axiHigh34Words[(offset / 4L).toInt()] = value and 0x3L
        }
    }

    fun createVcodecIdleDevice(isClosed: () -> Boolean): MmioDevice = object : MmioDevice {
        override val clockOrder = 82
        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_VCODEC_IDLE closed" }
            require(size == 4 && offset == 0L) { "$PORT_VCODEC_IDLE invalid read off=0x${offset.toString(16)} size=$size" }
            // Bit 1 == 0 signals VCodec bus idle (S4 0x011E2DD0..0x011E2DDC)
            return 0L
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_VCODEC_IDLE closed" }
            require(size == 4 && offset == 0L) { "$PORT_VCODEC_IDLE invalid write off=0x${offset.toString(16)} size=$size" }
        }
    }

    fun createIsp82000Device(
        isClosed: () -> Boolean,
        onComplete: ((LongArray) -> Unit)? = null,
    ): TickAwareMmioDevice = object : TickAwareMmioDevice {
        override val clockOrder = 82
        override val clockIndex = 9

        override fun nextDeadline(): Long? = if (isClosed()) null else isp82000PendingTick
        override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE

        override fun beforeInstruction(tick: Long) {
            if (isClosed()) return
            val due = isp82000PendingTick ?: return
            if (tick < due) return
            isp82000PendingTick = null
            isp82000Words[0x004 / 4] = isp82000Words[0x004 / 4] and 1L.inv()
            onComplete?.invoke(isp82000Words)
            isp82000Completions++
            // GIC descriptor[73] (0x015a89ac): IRQ 129 is RISING-EDGE triggered (w2 = 0x3).
            // ISR 0x021e922c posts RTOS semaphore 48 with no W1C status register.
            sinkProvider()?.let { sink ->
                sink.setLine(IRQ_ISP_82000, true)
                sink.setLine(IRQ_ISP_82000, false)
            }
        }

        override fun read(offset: Long, size: Int): Long {
            check(!isClosed()) { "$PORT_ISP_82000 closed" }
            require(size == 4 && offset in 0L until ISP_82000_SIZE && offset % 4L == 0L) {
                "$PORT_ISP_82000 invalid read off=0x${offset.toString(16)} size=$size"
            }
            val off = offset.toInt()
            val stored = isp82000Words[off / 4]
            // 0xFFF82000 bit 0 == 1 signals reset/shadow-latch ready (S5 0x021EA8A4..0x021EA8C8).
            // 0xFFF82094 polls (val & 0x111) == 0x111 (S5 0x021EB020..0x021EB030).
            return when (off) {
                0x000 -> stored or 1L
                0x094 -> stored or 0x111L
                else -> stored
            }
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!isClosed()) { "$PORT_ISP_82000 closed" }
            require(size == 4 && offset in 0L until ISP_82000_SIZE && offset % 4L == 0L) {
                "$PORT_ISP_82000 invalid write off=0x${offset.toString(16)} size=$size"
            }
            require(value in 0L..0xFFFF_FFFFL) { "$PORT_ISP_82000 value outside u32: $value" }
            val off = offset.toInt()
            isp82000Words[off / 4] = value
            if (off == 0x004 && (value and 1L) != 0L) {
                isp82000PendingTick = clockProvider() + COMPLETION_DELAY_TICKS
            }
        }
    }

    private fun createRetainedWordBank(portId: String, sizeBytes: Long, words: LongArray, isClosed: () -> Boolean): MmioDevice =
        object : RetainedMmioBank {
            override val clockOrder = 82
            override fun read(offset: Long, size: Int): Long {
                check(!isClosed()) { "$portId closed" }
                require(size == 4 && offset in 0L until sizeBytes && offset % 4L == 0L) {
                    "$portId invalid read off=0x${offset.toString(16)} size=$size"
                }
                return words[(offset / 4L).toInt()]
            }

            override fun write(offset: Long, size: Int, value: Long) {
                check(!isClosed()) { "$portId closed" }
                require(size == 4 && offset in 0L until sizeBytes && offset % 4L == 0L) {
                    "$portId invalid write off=0x${offset.toString(16)} size=$size"
                }
                require(value in 0L..0xFFFF_FFFFL) { "$portId value outside u32: $value" }
                words[(offset / 4L).toInt()] = value
            }
        }

    fun createIsp83000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_83000, ISP_83000_SIZE, isp83000Words, isClosed)

    fun createIsp84000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_84000, ISP_84000_SIZE, isp84000Words, isClosed)

    fun createIsp85000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_85000, ISP_85000_SIZE, isp85000Words, isClosed)

    /** Apply the RECC_S developer's gains to this exposure without copying its Bayer samples. */
    fun colorReadout(readout: IspStillReadout): IspStillReadout {
        val redGreen = isp85000Words[0xd4 / 4]
        val red = (redGreen ushr 16).toInt() and 0x7fff
        val green = redGreen.toInt() and 0x7fff
        val blue = isp85000Words[0xd8 / 4].toInt() and 0x7fff
        if (red == 0 || green == 0 || blue == 0) return readout

        // RECC_S 0xFFF850D4/D8 uses Q8 gain scaling (unity G ~= 256, daylight R:G:B ~= 460:256:486),
        // half the Q9 scale of LiveView 0xFFF68108/10C (919:512:972). Scale by 2048 so daylight
        // develops at unity (~1024) instead of -1 EV (~513), preserving the same CFA WB ratios.
        return IspStillReadout(
            readout.tick, readout.width, readout.height,
            (red * 2048 / 919).coerceIn(64, 8192),
            (green * 2048 / 512).coerceIn(64, 8192),
            (blue * 2048 / 972).coerceIn(64, 8192),
            readout.bayerSamples(),
        )
    }

    fun createIsp86000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_86000, ISP_86000_SIZE, isp86000Words, isClosed)

    fun createIsp87000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_87000, ISP_87000_SIZE, isp87000Words, isClosed)

    fun createIsp88000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_88000, ISP_88000_SIZE, isp88000Words, isClosed)

    fun createIsp89000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_89000, ISP_89000_SIZE, isp89000Words, isClosed)

    fun createIsp8a000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_8A000, ISP_8A000_SIZE, isp8a000Words, isClosed)

    fun createIsp90000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_90000, ISP_90000_SIZE, isp90000Words, isClosed)

    fun createIsp98000Device(isClosed: () -> Boolean): MmioDevice =
        createRetainedWordBank(PORT_ISP_98000, ISP_98000_SIZE, isp98000Words, isClosed)

    fun syncIrqLines() {
        for (e in allIrqEngines) e.syncIrqLine()
    }

    fun clearIrqsOnClose() {
        for (e in allIrqEngines) e.clearIrqOnClose()
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "movdc" to movdc.state(),
        "companion80000" to companion80000.state(),
        "companionB9000" to companionB9000.state(),
        "companion6F000" to companion6F000.state(),
        "lsraw" to (lsraw.state() + (lastLsrawEncode?.let { r ->
            mapOf(
                "lastOutputBytes" to r.outputBytes,
                "lastBlocksX" to r.blocksX,
                "lastBlocksY" to r.blocksY,
                "lastDmaWritten" to r.dmaWritten,
                "lastCompressed" to r.compressed,
                "lastSrcPa" to "0x${r.srcPa.toString(16)}",
                "lastDstPa" to "0x${r.dstPa.toString(16)}",
                "lastRawBits" to r.rawBits,
            )
        } ?: emptyMap())),
        "unidmacBA000" to unidmacBA000.state(),
        "companionBB000" to companionBB000.state(),
        "retch" to retch.state(),
        "isp82000" to linkedMapOf(
            "irqLine" to IRQ_ISP_82000,
            "pendingTick" to isp82000PendingTick,
            "completions" to isp82000Completions,
        ),
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "movdc" to movdc.saveSnapshot(),
        "c80000" to companion80000.saveSnapshot(),
        "cb9000" to companionB9000.saveSnapshot(),
        "c6f000" to companion6F000.saveSnapshot(),
        "lsraw" to lsraw.saveSnapshot(),
        "unidmac" to unidmacBA000.saveSnapshot(),
        "cbb000" to companionBB000.saveSnapshot(),
        "retch" to retch.saveSnapshot(),
        "axiHigh34Words" to axiHigh34Words.toList(),
        "isp82000Words" to isp82000Words.toList(),
        "isp83000Words" to isp83000Words.toList(),
        "isp84000Words" to isp84000Words.toList(),
        "isp85000Words" to isp85000Words.toList(),
        "isp86000Words" to isp86000Words.toList(),
        "isp87000Words" to isp87000Words.toList(),
        "isp88000Words" to isp88000Words.toList(),
        "isp89000Words" to isp89000Words.toList(),
        "isp8a000Words" to isp8a000Words.toList(),
        "isp90000Words" to isp90000Words.toList(),
        "isp98000Words" to isp98000Words.toList(),
        "isp82000PendingTick" to isp82000PendingTick,
        "isp82000Completions" to isp82000Completions,
    )

    fun restoreSnapshot(raw: Any?) {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid companionEngines snapshot")
        val hasLut90Banks = map.keys == SNAPSHOT_KEYS
        val hasStillBanks = hasLut90Banks || map.keys == SNAPSHOT_KEYS_V3_2
        val hasExtended = hasStillBanks || map.keys == SNAPSHOT_KEYS_V3_1
        require(hasExtended || map.keys == SNAPSHOT_KEYS_LEGACY) { "Unexpected companionEngines snapshot keys" }
        fun readOptWordArray(key: String, target: LongArray, present: Boolean): LongArray {
            if (!present) return LongArray(target.size)
            val rawList = map[key] as? List<*> ?: throw IllegalArgumentException("$key requires list")
            require(rawList.size == target.size) { "$key size mismatch" }
            return LongArray(target.size) { i -> reqLong(rawList[i], "$key[$i]", 0L, 0xFFFF_FFFFL) }
        }
        val rawAxi = map["axiHigh34Words"] as? List<*> ?: throw IllegalArgumentException("axiHigh34Words requires list")
        require(rawAxi.size == axiHigh34Words.size) { "axiHigh34Words size mismatch" }
        val nextAxi = LongArray(axiHigh34Words.size) { i -> reqLong(rawAxi[i], "axiHigh34Words[$i]", 0L, 0x3L) }
        val next82 = readOptWordArray("isp82000Words", isp82000Words, true)
        val next83 = readOptWordArray("isp83000Words", isp83000Words, hasExtended)
        val next84 = readOptWordArray("isp84000Words", isp84000Words, hasStillBanks)
        val next85 = readOptWordArray("isp85000Words", isp85000Words, hasExtended)
        val next86 = readOptWordArray("isp86000Words", isp86000Words, hasStillBanks)
        val next87 = readOptWordArray("isp87000Words", isp87000Words, hasStillBanks)
        val next88 = readOptWordArray("isp88000Words", isp88000Words, hasStillBanks)
        val next89 = readOptWordArray("isp89000Words", isp89000Words, hasStillBanks)
        val next8a = readOptWordArray("isp8a000Words", isp8a000Words, hasStillBanks)
        val next90 = readOptWordArray("isp90000Words", isp90000Words, hasLut90Banks)
        val next98 = readOptWordArray("isp98000Words", isp98000Words, hasLut90Banks)
        val next82Pending = if (hasExtended) {
            map["isp82000PendingTick"]?.let { reqLong(it, "isp82000PendingTick", 0L, Long.MAX_VALUE) }
        } else {
            null
        }
        val next82Completions = if (hasExtended) {
            reqLong(map["isp82000Completions"], "isp82000Completions", 0L, Long.MAX_VALUE)
        } else {
            0L
        }

        movdc.restoreSnapshot(map["movdc"], "movdc")
        companion80000.restoreSnapshot(map["c80000"], "c80000")
        companionB9000.restoreSnapshot(map["cb9000"], "cb9000")
        companion6F000.restoreSnapshot(map["c6f000"], "c6f000")
        lsraw.restoreSnapshot(map["lsraw"], "lsraw")
        unidmacBA000.restoreSnapshot(map["unidmac"], "unidmac")
        if (hasExtended) {
            companionBB000.restoreSnapshot(map["cbb000"], "cbb000")
        } else {
            companionBB000.words.fill(0L)
            companionBB000.softReset()
            companionBB000.completions = 0L
        }
        retch.restoreSnapshot(map["retch"], "retch")
        nextAxi.copyInto(axiHigh34Words)
        next82.copyInto(isp82000Words)
        next83.copyInto(isp83000Words)
        next84.copyInto(isp84000Words)
        next85.copyInto(isp85000Words)
        next86.copyInto(isp86000Words)
        next87.copyInto(isp87000Words)
        next88.copyInto(isp88000Words)
        next89.copyInto(isp89000Words)
        next8a.copyInto(isp8a000Words)
        next90.copyInto(isp90000Words)
        next98.copyInto(isp98000Words)
        isp82000PendingTick = next82Pending
        isp82000Completions = next82Completions
    }

    private fun reqLong(value: Any?, field: String, min: Long, max: Long): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot $field requires integer" }
        val v = (value as Number).toLong()
        require(v in min..max) { "Snapshot $field out of range: $v" }
        return v
    }
}
