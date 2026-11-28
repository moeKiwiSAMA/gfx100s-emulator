package com.github.nyabanban.fuji.emulator.components.ispcmd

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.util.Base64

/**
 * ISP command/control registers [0xFFF6C000, 0xFFF6C42C):
 * - soc-isp-cmd.c000-words   (0xFFF6C000..0xFFF6C204): 20 evidenced wordRuns, 19 prohibited holes, optional C000 stop bit0 poll
 * - soc-isp-cmd.c204-control (0xFFF6C204..0xFFF6C20C): START (+0) & MODE (+4), optional start-bit0-clear-after-read
 * - soc-isp-cmd.c20c-config  (0xFFF6C20C..0xFFF6C220): 5 burst config words
 * - soc-isp-cmd.reply        (0xFFF6C224..0xFFF6C244): 8-word reply queue using DmaMemory (0x028F2000 / 0x028F2940)
 * - soc-isp-cmd.config-bank-0..6 (0xFFF6C280..0xFFF6C42C): 7 retained config banks (6x28B + 1x44B)
 */
class IspCmdFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    enum class StopPhase { IDLE, REQUESTED, ACKNOWLEDGED, CANCELLED }

    companion object {
        const val PROFILE = "isp-cmd-retained-experimental-v1"
        const val C000_STOP_ONCE = "stop-bit0-set-after-poll-once-v1"
        const val C000_STOP_REPEATABLE = "stop-bit0-set-after-poll-repeatable-v1"
        const val REPLY_ZERO_ONCE = "zero-once-v1"
        const val REPLY_AF_ONCE = "af-once-v1"
        const val REPLY_820040_ONCE = "zero-820040-once-v1"
        const val REPLY_820041_ONCE = "zero-820041-once-v1"
        const val REPLY_16_PAIRS = "zero-alternating-16-pairs-v1"
        const val REPLY_PROGRAMMED = ProgrammedCommandReply.PROFILE
        const val REPLY_STATEFUL = "committed-81-stateful-v1"
        const val REPLY_IMX461_SILICON = Imx461SpiShadow.PROFILE
        const val IRQ_PIEZO = UltrasonicPiezoController.IRQ_LINE

        const val PORT_C000 = "soc-isp-cmd.c000-words"
        const val PORT_C204 = "soc-isp-cmd.c204-control"
        const val PORT_C20C = "soc-isp-cmd.c20c-config"
        const val PORT_REPLY = "soc-isp-cmd.reply"
        const val PORT_IMX461_EXPOSURE = "soc-isp-cmd.imx461.exposure"
        val CONFIG_BANK_PORTS = List(7) { bank -> "soc-isp-cmd.config-bank-$bank" }
        val ALL_PORTS = listOf(PORT_C000, PORT_C204, PORT_C20C, PORT_REPLY) + CONFIG_BANK_PORTS

        const val C000_SIZE = 516
        const val C204_SIZE = 8
        const val C20C_SIZE = 20
        const val REPLY_SIZE = 32
        val CONFIG_BANK_SIZES = List(7) { bank -> if (bank == 6) 44 else 28 }

        private const val REQUEST_ADDR = 0x028f2000L
        private const val C000_BASE = 0xfff6c000L
        private const val C204_BASE = 0xfff6c204L
        private const val C20C_BASE = 0xfff6c20cL
        private const val REPLY_PAIR_WORDS = 16
        private const val REPLY_TOTAL_WORDS = 16 * REPLY_PAIR_WORDS

        const val CH2_IRQ = 97
        const val CH3_IRQ = 98
        const val CHANNEL_IRQ_PERIOD_TICKS = 2_000_000L
        private const val CH2_START_OFF = 0x40
        private const val CH2_MASK_OFF = 0x60
        private const val CH2_STATUS_OFF = 0x64
        private const val CH3_START_OFF = 0xc0
        private const val CH3_MASK_OFF = 0xe0
        private const val CH3_STATUS_OFF = 0xe4
        private val CH_START_OFFS = intArrayOf(CH2_START_OFF, CH3_START_OFF)
        private val CH_MASK_OFFS = intArrayOf(CH2_MASK_OFF, CH3_MASK_OFF)
        private val CH_STATUS_OFFS = intArrayOf(CH2_STATUS_OFF, CH3_STATUS_OFF)
        private val CH_IRQS = intArrayOf(CH2_IRQ, CH3_IRQ)

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-isp-cmd",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE), live = false),
                Setting("c000InitialFill", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 255.0, live = false),
                Setting("initialFill", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 255.0, live = false),
                Setting(
                    "c000StopProfile",
                    ValueKind.TEXT,
                    "",
                    choices = listOf("", C000_STOP_ONCE, C000_STOP_REPEATABLE),
                    live = false,
                ),
                Setting("clearC204Start", ValueKind.BOOLEAN, false, live = false),
                Setting("channelIrqsEnabled", ValueKind.BOOLEAN, false, live = false),
                Setting(
                    "replyProfile",
                    ValueKind.TEXT,
                    "",
                    choices = listOf(
                        "",
                        REPLY_ZERO_ONCE,
                        REPLY_AF_ONCE,
                        REPLY_820040_ONCE,
                        REPLY_820041_ONCE,
                        REPLY_16_PAIRS,
                        REPLY_PROGRAMMED,
                        REPLY_STATEFUL,
                        REPLY_IMX461_SILICON,
                    ),
                    live = false,
                ),
                Setting(
                    "interleaved820001",
                    ValueKind.TEXT,
                    "",
                    choices = listOf("", REPLY_ZERO_ONCE, REPLY_AF_ONCE),
                    live = false,
                ),
                Setting("memoryPort", ValueKind.TEXT, "cpu.memory", live = false),
            ),
        )

        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "enabled",
            "c000WordsBase64", "c000Phase", "c204Words", "c20cWords",
            "configBankWords", "pairPosition", "reply01Position", "programmedReply",
        )
        private val OPTIONAL_CHANNEL_SNAPSHOT_KEYS = setOf(
            "channelStartWritten", "channelMaskWritten", "channelDrainPending", "channelStatus",
            "channelIrqAsserted", "channelNextIrqTick", "channelIrqCount",
        )

        private data class WordRun(val first: Int, val last: Int, val read: Boolean, val write: Boolean)

        private val C000_WORD_RUNS = listOf(
            WordRun(0, 36, read = true, write = true),
            WordRun(40, 40, read = true, write = false),
            WordRun(44, 48, read = true, write = true),
            WordRun(52, 52, read = true, write = false),
            WordRun(56, 56, read = true, write = true),
            WordRun(60, 60, read = true, write = false),
            WordRun(64, 100, read = true, write = true),
            WordRun(104, 112, read = true, write = false),
            WordRun(116, 140, read = true, write = true),
            WordRun(144, 144, read = true, write = true),
            WordRun(192, 228, read = true, write = true),
            WordRun(232, 244, read = true, write = false),
            WordRun(248, 268, read = true, write = true),
            WordRun(272, 272, read = true, write = false),
            WordRun(276, 276, read = true, write = true),
            WordRun(320, 320, read = true, write = true),
            WordRun(324, 324, read = false, write = true),
            WordRun(384, 432, read = true, write = true),
            WordRun(448, 448, read = true, write = false),
            WordRun(452, 472, read = true, write = true),
            WordRun(512, 512, read = true, write = true),
        )

        private val C000_READABLE = BooleanArray(C000_SIZE).apply {
            for (run in C000_WORD_RUNS) if (run.read) for (off in run.first..run.last step 4) this[off] = true
        }
        private val C000_WRITABLE = BooleanArray(C000_SIZE).apply {
            for (run in C000_WORD_RUNS) if (run.write) for (off in run.first..run.last step 4) this[off] = true
        }

        private fun readWord(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)

        private fun writeWord(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = (value and 0xffL).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xffL).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xffL).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xffL).toByte()
        }

        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "Snapshot field $field out of range: $converted" }
            return converted
        }

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")

        private fun snapshotString(value: Any?, field: String): String =
            value as? String ?: throw IllegalArgumentException("Snapshot field $field requires string")
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-isp-cmd", context, config), StatefulComponent {
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val active = profile == PROFILE
        private val c000Fill = (config.getValue("c000InitialFill") as Number).toInt()
        private val fillByte = (config.getValue("initialFill") as Number).toInt()
        private val c000StopProfile = config.getValue("c000StopProfile") as String
        private val clearC204Start = config.getValue("clearC204Start") as Boolean
        private val channelIrqsEnabled = (config["channelIrqsEnabled"] as? Boolean) ?: false
        private val replyProfile = config.getValue("replyProfile") as String
        private val interleaved820001 = config.getValue("interleaved820001") as String
        private val memoryPortName = config.getValue("memoryPort") as String

        private val dmaMemory by lazy {
            require(memoryPortName.isNotBlank()) { "soc-isp-cmd memoryPort must be configured" }
            context.ports.find(memoryPortName, DmaMemory::class.java)
                ?: error("soc-isp-cmd DmaMemory peer missing: $memoryPortName")
        }
        private val irqSink: InterruptSink? by lazy {
            context.ports.find("soc-gic.lines", InterruptSink::class.java)
        }

        private val c000Bytes = if (active) ByteArray(C000_SIZE) { c000Fill.toByte() } else null
        private var c000Phase = StopPhase.IDLE
        private val channelStartWritten = BooleanArray(2)
        private val channelMaskWritten = BooleanArray(2)
        private val channelDrainPending = BooleanArray(2)
        private val channelStatus = LongArray(2)
        private val channelIrqAsserted = BooleanArray(2)
        private val channelNextIrqTick = LongArray(2) { -1L }
        private val channelIrqCount = LongArray(2)
        private val c204Words = LongArray(2)
        private val c20cWords = LongArray(5)
        private val configBankWords = List(7) { bank -> LongArray(CONFIG_BANK_SIZES[bank] / 4) }
        private var interruptSink: InterruptSink? = null
        private val piezoController: UltrasonicPiezoController? = if (active) {
            UltrasonicPiezoController(
                words = configBankWords[6],
                clockTicks = { context.clock.ticks() },
                sinkProvider = {
                    interruptSink ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also {
                        interruptSink = it
                    }
                },
                isClosed = { closed },
            )
        } else {
            null
        }

        private var pairPosition = 0
        private var reply01Position = 0
        private var programmedReply: ProgrammedCommandReply? =
            if (active && replyProfile == REPLY_PROGRAMMED) ProgrammedCommandReply() else null
        private val statefulRegisters = linkedMapOf<Int, Int>()
        private var imx461Shadow: Imx461SpiShadow? =
            if (active && replyProfile == REPLY_IMX461_SILICON) Imx461SpiShadow() else null
        private var imx461LastReadPtr: Long = REQUEST_ADDR
        private val exposureLatch = Imx461ExposureLatch(context.clock)
        private var stillReadoutBits = 14
        private var committedMovieFrameRows = 0
        private var stillRearCurtainDelayTicks: Long? = null
        private var stillFrontCurtainDelayTicks: Long? = null
        private var stillExposureSpiWritten = false

        private fun isLiveViewTimingActive(): Boolean =
            c000Bytes?.let { readWord(it, 0x024) and 0x20000L == 0L } == true

        private fun statefulHasExposureRegs(): Boolean =
            Imx461SpiShadow.REG_SHR_15_LO in statefulRegisters ||
                Imx461SpiShadow.REG_SHR_15_HI in statefulRegisters ||
                Imx461SpiShadow.REG_PGC_12_LO in statefulRegisters ||
                Imx461SpiShadow.REG_PGC_12_HI in statefulRegisters

        private fun statefulShr15(): Int =
            (statefulRegisters[Imx461SpiShadow.REG_SHR_15_LO] ?: 0) or
                (((statefulRegisters[Imx461SpiShadow.REG_SHR_15_HI] ?: 0) and 0x7F) shl 8)

        private fun statefulSvr12(): Int =
            (statefulRegisters[Imx461SpiShadow.REG_SVR_LO] ?: 0) or
                (((statefulRegisters[Imx461SpiShadow.REG_SVR_HI] ?: 0) and 0x0F) shl 8)

        private fun statefulVmax12(): Int =
            (statefulRegisters[Imx461SpiShadow.REG_VMAX_LO] ?: 0) or
                (((statefulRegisters[Imx461SpiShadow.REG_VMAX_HI] ?: 0) and 0x0F) shl 8)

        private fun statefulPgc12(): Int =
            (statefulRegisters[Imx461SpiShadow.REG_PGC_12_LO] ?: 0) or
                (((statefulRegisters[Imx461SpiShadow.REG_PGC_12_HI] ?: 0) and 0x0F) shl 8)

        private fun statefulHcgEnabled(): Boolean =
            ((statefulRegisters[Imx461SpiShadow.REG_HCG] ?: 0) and 0x01) != 0

        private fun statefulRegholdActive(): Boolean =
            ((statefulRegisters[Imx461SpiShadow.REG_REGHOLD] ?: 0) and Imx461SpiShadow.REGHOLD_BIT) != 0

        private fun statefulMdsel8(): Int = statefulRegisters[Imx461SpiShadow.REG_MDSEL] ?: 0

        private fun statefulModeFlags04(): Int = statefulRegisters[Imx461SpiShadow.REG_MODE_FLAGS_04] ?: 0

        private fun statefulModeFlags05(): Int = statefulRegisters[Imx461SpiShadow.REG_MODE_FLAGS_05] ?: 0

        private fun statefulMovieShr15(): Int =
            (statefulRegisters[Imx461SpiShadow.REG_HMAX_LO] ?: 0) or
                (((statefulRegisters[Imx461SpiShadow.REG_HMAX_HI] ?: 0) and 0x7F) shl 8)

        private fun currentRawMovieFrameRows(mode: Int): Int {
            if (!Imx461ExposurePort.isMovieShutterMode(mode)) return 0
            val bytes = c000Bytes ?: return 0
            // Enabled CCDTG pulse entry C194[15:0] holds timing[1] in half SHR2 units.
            val timer = readWord(bytes, 0x194)
            if ((timer ushr 16) != 2L) return 0
            return (timer.toInt() and 0xFFFF) ushr 1
        }

        private fun observeCurrentExposureLatch(commitFrameRows: Boolean = true) {
            val shadow = imx461Shadow
            val hasRegs = shadow?.hasExposureRegs() ?: statefulHasExposureRegs()
            if (!hasRegs) return
            val shr = shadow?.shr15() ?: statefulShr15()
            val svr = shadow?.svr12() ?: statefulSvr12()
            val vmax = shadow?.vmax12() ?: statefulVmax12()
            val pgc = shadow?.pgc12() ?: statefulPgc12()
            val hcg = shadow?.hcgEnabled() ?: statefulHcgEnabled()
            val reghold = shadow?.isRegholdActive ?: statefulRegholdActive()
            val mode = shadow?.mdsel8() ?: statefulMdsel8()
            val flags04 = shadow?.modeFlags04() ?: statefulModeFlags04()
            val mShr = shadow?.movieShr15() ?: statefulMovieShr15()
            val rawRows = currentRawMovieFrameRows(mode)
            if (commitFrameRows || exposureLatch.latchedState == null) {
                committedMovieFrameRows = if (Imx461ExposurePort.isMovieShutterMode(mode)) rawRows else 0
            } else {
                // On a bare CCDTG 0x194 frame-period write, do not pair the new movieFrameRows with
                // a stale SHR1 (which would fabricate a bright white flash when 0x194 increases
                // before firmware commits the matching SHR1 burst). Only adopt rawRows immediately
                // if the already-written SHR1 was out-of-frame for the old period and now fits.
                if (!Imx461ExposurePort.isMovieShutterMode(mode)) return
                val wasOutOfFrame = !Imx461ExposurePort.isShutterWithinFrame(
                    shr, svr, vmax, mode, flags04, mShr, committedMovieFrameRows,
                )
                val nowInFrame = Imx461ExposurePort.isShutterWithinFrame(
                    shr, svr, vmax, mode, flags04, mShr, rawRows,
                )
                if (!wasOutOfFrame || !nowInFrame) return
                committedMovieFrameRows = rawRows
            }
            exposureLatch.observe(
                shr15 = shr,
                svr12 = svr,
                vmax12 = vmax,
                pgc12 = pgc,
                hcgEnabled = hcg,
                regholdActive = reghold,
                mdsel8 = mode,
                modeFlags04 = flags04,
                movieShr15 = mShr,
                movieFrameRows = if (Imx461ExposurePort.isMovieShutterMode(mode)) committedMovieFrameRows else 0,
            )
        }

        init {
            require(profile in listOf("disabled", PROFILE)) { "Unsupported soc-isp-cmd profile: $profile" }
            require(c000Fill in 0..255 && fillByte in 0..255) { "soc-isp-cmd fill out of 0..255" }
            if (!active) {
                require(
                    c000Fill == 0 && fillByte == 0 && c000StopProfile.isEmpty() && !clearC204Start &&
                        !channelIrqsEnabled && replyProfile.isEmpty() && interleaved820001.isEmpty(),
                ) { "Disabled soc-isp-cmd cannot enable optional sub-profiles" }
            } else {
                if (replyProfile == REPLY_PROGRAMMED) {
                    require(clearC204Start && interleaved820001 in listOf(REPLY_ZERO_ONCE, REPLY_AF_ONCE)) {
                        "soc-isp-cmd committed-81-read-once-v1 requires clearC204Start=true and interleaved820001"
                    }
                    require(memoryPortName.isNotBlank()) { "soc-isp-cmd programmed reply requires memoryPort" }
                }
                if (replyProfile == REPLY_STATEFUL) {
                    require(clearC204Start) { "soc-isp-cmd committed-81-stateful-v1 requires clearC204Start=true" }
                    require(memoryPortName.isNotBlank()) { "soc-isp-cmd stateful reply requires memoryPort" }
                }
                if (replyProfile == REPLY_IMX461_SILICON) {
                    require(clearC204Start) { "soc-isp-cmd $REPLY_IMX461_SILICON requires clearC204Start=true" }
                    require(memoryPortName.isNotBlank()) { "soc-isp-cmd $REPLY_IMX461_SILICON requires memoryPort" }
                }
                if (interleaved820001.isNotEmpty()) {
                    require(replyProfile in listOf(REPLY_16_PAIRS, REPLY_PROGRAMMED)) {
                        "soc-isp-cmd interleaved820001 requires 16-pair or programmed replyProfile"
                    }
                }
                val fillWord = (fillByte.toLong() and 0xffL).let { b ->
                    b or (b shl 8) or (b shl 16) or (b shl 24)
                }
                c204Words.fill(fillWord)
                c20cWords.fill(fillWord)
                configBankWords.forEach { it.fill(fillWord) }
                registerPorts()
            }
        }

        private fun isChannelRunning(ch: Int): Boolean {
            if (!channelIrqsEnabled || !active || closed) return false
            if (!channelStartWritten[ch] || !channelMaskWritten[ch]) return false
            val buf = c000Bytes ?: return false
            val enabled = (readWord(buf, CH_START_OFFS[ch]) and 1L) != 0L
            val mask = readWord(buf, CH_MASK_OFFS[ch]) and 0x3L
            return enabled && mask != 0L
        }

        private fun isChannelMaskActive(ch: Int): Boolean {
            if (!channelIrqsEnabled || !active || closed || !channelMaskWritten[ch]) return false
            val buf = c000Bytes ?: return false
            return (readWord(buf, CH_MASK_OFFS[ch]) and 0x3L) != 0L
        }

        private fun isChannelArmed(ch: Int): Boolean =
            isChannelRunning(ch) || (channelDrainPending[ch] && isChannelMaskActive(ch))

        private fun syncChannelSchedule(ch: Int) {
            if (!channelIrqsEnabled || closed) return
            if (!isChannelMaskActive(ch)) {
                channelDrainPending[ch] = false
                channelNextIrqTick[ch] = -1L
                if (channelIrqAsserted[ch]) {
                    channelIrqAsserted[ch] = false
                    irqSink?.setLine(CH_IRQS[ch], false)
                }
                return
            }
            if (isChannelRunning(ch)) {
                channelDrainPending[ch] = false
                if (channelNextIrqTick[ch] < 0L) {
                    channelNextIrqTick[ch] = context.clock.ticks() + CHANNEL_IRQ_PERIOD_TICKS
                }
            } else if (channelDrainPending[ch]) {
                if (channelNextIrqTick[ch] < 0L) {
                    channelNextIrqTick[ch] = context.clock.ticks() + CHANNEL_IRQ_PERIOD_TICKS
                }
            } else {
                channelNextIrqTick[ch] = -1L
            }
        }

        private fun completeChannel(ch: Int, tick: Long) {
            if (!isChannelArmed(ch)) {
                channelNextIrqTick[ch] = -1L
                return
            }
            channelNextIrqTick[ch] = tick + CHANNEL_IRQ_PERIOD_TICKS
            if (channelStatus[ch] != 0L) return
            val buf = c000Bytes ?: return
            val mask = readWord(buf, CH_MASK_OFFS[ch])
            channelStatus[ch] = if (mask and 1L != 0L) 1L else 2L
            writeWord(buf, CH_STATUS_OFFS[ch], channelStatus[ch])
            if (!channelIrqAsserted[ch]) {
                channelIrqAsserted[ch] = true
                irqSink?.setLine(CH_IRQS[ch], true)
            }
            channelIrqCount[ch]++
            if (channelDrainPending[ch] && !isChannelRunning(ch)) {
                channelDrainPending[ch] = false
                channelNextIrqTick[ch] = -1L
            }
        }

        private fun registerPorts() {
            if (channelIrqsEnabled) {
                context.ports.register(id, CcdMovieFrameInput.PORT_ID, CcdMovieFrameInput::class.java,
                    CcdMovieFrameInput { tick -> completeChannel(0, tick) })
            }
            context.ports.register(id, PORT_C000, MmioDevice::class.java, object : TickAwareMmioDevice {
                override val clockOrder = 76
                override fun readMayMutateSchedule(): Boolean = false
                override fun nextDeadline(): Long? {
                    if (!channelIrqsEnabled || closed) return null
                    var earliest: Long? = null
                    for (ch in 0..1) {
                        val d = channelNextIrqTick[ch]
                        if (d >= 0L && (earliest == null || d < earliest)) earliest = d
                    }
                    return earliest
                }
                override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
                override fun beforeInstruction(tick: Long) {
                    if (!channelIrqsEnabled || closed) return
                    for (ch in 0..1) {
                        if (channelNextIrqTick[ch] !in 0L..tick) continue
                        if (ch == 0 && context.ports.find(CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java)
                                ?.movieFrameClockActive == true) {
                            // CCDIF supplies channel 2's edges during recording. Keep a standby
                            // deadline for the transition back to free-running operation.
                            channelNextIrqTick[ch] = tick + CHANNEL_IRQ_PERIOD_TICKS
                        } else {
                            completeChannel(ch, tick)
                        }
                    }
                }
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && offset >= 0L && offset < C000_SIZE && offset % 4L == 0L) {
                        "$PORT_C000 requires aligned 32-bit word within [0, $C000_SIZE)"
                    }
                    val off = offset.toInt()
                    require(C000_READABLE[off]) { "$PORT_C000 refused read at 0x${off.toString(16)}" }
                    if (channelIrqsEnabled && (off == CH2_STATUS_OFF || off == CH3_STATUS_OFF)) {
                        val ch = if (off == CH2_STATUS_OFF) 0 else 1
                        return channelStatus[ch]
                    }
                    val buf = checkNotNull(c000Bytes)
                    val before = readWord(buf, off)
                    if (off == 0 && c000StopProfile.isNotEmpty() && c000Phase == StopPhase.REQUESTED) {
                        c000Phase = StopPhase.ACKNOWLEDGED
                        writeWord(buf, 0, before or 1L)
                    }
                    return before
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && offset >= 0L && offset < C000_SIZE && offset % 4L == 0L) {
                        "$PORT_C000 requires aligned 32-bit word within [0, $C000_SIZE)"
                    }
                    require(value in 0L..0xffffffffL) { "$PORT_C000 write outside u32" }
                    val off = offset.toInt()
                    require(C000_WRITABLE[off]) { "$PORT_C000 refused write at 0x${off.toString(16)}" }
                    val buf = checkNotNull(c000Bytes)
                    // DAT section4 0x011b2348 / 0x011b257c / 0x011b2458:
                    // 0xFFF6C064 (ch2 -> GIC IRQ 97) and 0xFFF6C0E4 (ch3 -> GIC IRQ 98) are W1C status registers.
                    if (channelIrqsEnabled && (off == CH2_STATUS_OFF || off == CH3_STATUS_OFF)) {
                        val ch = if (off == CH2_STATUS_OFF) 0 else 1
                        channelStatus[ch] = channelStatus[ch] and (value and 0xfL).inv()
                        writeWord(buf, off, channelStatus[ch])
                        if (channelStatus[ch] == 0L && channelIrqAsserted[ch]) {
                            channelIrqAsserted[ch] = false
                            irqSink?.setLine(CH_IRQS[ch], false)
                        }
                        return
                    }
                    val before = readWord(buf, off)
                    if (off == 0 && c000StopProfile.isNotEmpty()) {
                        if (value == (before and 1L.inv())) {
                            when {
                                c000Phase == StopPhase.IDLE ||
                                    (c000StopProfile == C000_STOP_REPEATABLE && c000Phase == StopPhase.ACKNOWLEDGED) ->
                                    c000Phase = StopPhase.REQUESTED
                                c000Phase == StopPhase.REQUESTED ->
                                    c000Phase = StopPhase.CANCELLED
                                else -> error("soc-isp-cmd c000 stop cycle already spent")
                            }
                        } else if (c000Phase == StopPhase.REQUESTED) {
                            c000Phase = StopPhase.CANCELLED
                        }
                    }
                    writeWord(buf, off, value)
                    if (off == 0x024 && before and 0x20000L != 0L && value and 0x20000L == 0L) {
                        stillRearCurtainDelayTicks = null
                        stillFrontCurtainDelayTicks = null
                        stillExposureSpiWritten = false
                        context.ports.find(CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java)?.resumeLiveView()
                    }
                    if (off == 0x194 && value != before) observeCurrentExposureLatch(commitFrameRows = false)
                    // GFX100S CCDTG: +0x024 bit 17 selects still capture. Each channel's
                    // +0x008 two-bit format is 0 for 16-bit and 1 for 14-bit. Single-shot
                    // restores the live-view format before RECC_S consumes the exposure.
                    if (off == 0x008 && (readWord(buf, 0x024) and 0x20000L) != 0L) {
                        when (value and 0xfL) {
                            0x0L -> stillReadoutBits = 16
                            0x5L -> stillReadoutBits = 14
                        }
                    }
                    if (channelIrqsEnabled) {
                        when (off) {
                            CH2_START_OFF -> {
                                val wasRunning = channelStartWritten[0] && (before and 1L) != 0L && isChannelMaskActive(0)
                                channelStartWritten[0] = true
                                if (wasRunning && (value and 1L) == 0L) {
                                    channelDrainPending[0] = true
                                }
                                syncChannelSchedule(0)
                            }
                            CH2_MASK_OFF -> {
                                channelMaskWritten[0] = true
                                syncChannelSchedule(0)
                            }
                            CH3_START_OFF -> {
                                val wasRunning = channelStartWritten[1] && (before and 1L) != 0L && isChannelMaskActive(1)
                                channelStartWritten[1] = true
                                if (wasRunning && (value and 1L) == 0L) {
                                    channelDrainPending[1] = true
                                }
                                syncChannelSchedule(1)
                            }
                            CH3_MASK_OFF -> {
                                channelMaskWritten[1] = true
                                syncChannelSchedule(1)
                            }
                        }
                    }
                    // BUG-A2-5 fix (DAT section5 0x0239341c -> section4 0x011b5b4c, debug strings 'TG_ACT_VI'/'TG_ACT'):
                    // 0xFFF6C184 (offset 0x184) rising 0->1 is CCDTG TG_ACT (IMX461 Timing Generator Activate),
                    // NOT a mechanical shutter curtain solenoid. Never emit "curtainRelease" here so pure Electronic
                    // Shutter (ES) captures never fabricate mechanical curtain pulses on GPIO(0,10) / EXTINT #10.
                    if (off == 0x184 && (before and 1L) == 0L && (value and 1L) != 0L) {
                        val tick = context.clock.ticks()
                        context.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java)?.onTgActRising(tick)
                        context.events.publish(
                            ComponentEvent(
                                id,
                                "tgAct",
                                tick,
                                mapOf("offset" to off, "value" to value),
                            ),
                        )
                    }
                }
            })

            context.ports.register(id, PORT_C204, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 76
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && (offset == 0L || offset == 4L)) {
                        "$PORT_C204 requires aligned 32-bit word at offset 0 or 4"
                    }
                    val slot = (offset / 4L).toInt()
                    val before = c204Words[slot]
                    val model = programmedReply
                    if (model != null && reply01Position == 8) {
                        model.observe(C204_BASE + offset, write = false, value = before, before = before, tick = context.clock.ticks()) { dmaMemory }
                    }
                    if (slot == 0 && clearC204Start) {
                        c204Words[0] = before and 1L.inv()
                    }
                    return before
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && (offset == 0L || offset == 4L)) {
                        "$PORT_C204 requires aligned 32-bit word at offset 0 or 4"
                    }
                    require(value in 0L..0xffffffffL) { "$PORT_C204 write outside u32" }
                    val slot = (offset / 4L).toInt()
                    val before = c204Words[slot]
                    val model = programmedReply
                    if (model != null && reply01Position == 8) {
                        model.observe(C204_BASE + offset, write = true, value = value, before = before, tick = context.clock.ticks()) { dmaMemory }
                    }
                    if (replyProfile == REPLY_STATEFUL && slot == 0 && (value and 1L) != 0L) {
                        observeStatefulStart()
                    }
                    if (replyProfile == REPLY_IMX461_SILICON && slot == 0 && (value and 1L) != 0L) {
                        observeImx461Start()
                    }
                    c204Words[slot] = value
                }
            })

            context.ports.register(id, PORT_C20C, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 76
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && offset >= 0L && offset < C20C_SIZE && offset % 4L == 0L) {
                        "$PORT_C20C requires aligned 32-bit word within [0, $C20C_SIZE)"
                    }
                    return c20cWords[(offset / 4L).toInt()]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && offset >= 0L && offset < C20C_SIZE && offset % 4L == 0L) {
                        "$PORT_C20C requires aligned 32-bit word within [0, $C20C_SIZE)"
                    }
                    require(value in 0L..0xffffffffL) { "$PORT_C20C write outside u32" }
                    val slot = (offset / 4L).toInt()
                    val before = c20cWords[slot]
                    val model = programmedReply
                    if (model != null && reply01Position == 8) {
                        model.observe(C20C_BASE + offset, write = true, value = value, before = before, tick = context.clock.ticks()) { dmaMemory }
                    }
                    c20cWords[slot] = value
                }
            })

            context.ports.register(id, PORT_REPLY, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 76
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-isp-cmd closed" }
                    require(size == 4 && offset >= 0L && offset < REPLY_SIZE && offset % 4L == 0L) {
                        "$PORT_REPLY requires aligned 32-bit word within [0, $REPLY_SIZE)"
                    }
                    return readReply(offset)
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    error("$PORT_REPLY is read-only")
                }
            })

            CONFIG_BANK_PORTS.forEachIndexed { bank, portName ->
                if (bank == 6) {
                    context.ports.register(id, portName, MmioDevice::class.java, checkNotNull(piezoController))
                    return@forEachIndexed
                }
                val bankSize = CONFIG_BANK_SIZES[bank]
                val words = configBankWords[bank]
                context.ports.register(id, portName, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 76
                    override fun read(offset: Long, size: Int): Long {
                        check(!closed) { "soc-isp-cmd closed" }
                        require(size == 4 && offset >= 0L && offset < bankSize && offset % 4L == 0L) {
                            "$portName requires aligned 32-bit word within [0, $bankSize)"
                        }
                        return words[(offset / 4L).toInt()]
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        check(!closed) { "soc-isp-cmd closed" }
                        require(size == 4 && offset >= 0L && offset < bankSize && offset % 4L == 0L) {
                            "$portName requires aligned 32-bit word within [0, $bankSize)"
                        }
                        require(value in 0L..0xffffffffL) { "$portName write outside u32" }
                        words[(offset / 4L).toInt()] = value
                        // DAT section4 0x011b66a4..0x011b7520 / section5 0x021f405c..0x021f45f8 & 0x02393dc4..0x02393f70:
                        // Config banks 0..5 are 6 hardware pulse/PWM channels:
                        //   +0x00 (words[0] bit 0): commit/update trigger (1 = apply shadow config)
                        //   +0x04 (words[1] bit 0): channel active/running status (polled by 0x02393f54 until 0)
                        //   +0x08 (words[2] bits [18:16]): operating mode (0..3 = active modes, 4 = stop/idle)
                        //   +0x0C (words[3] bits [27:0]): 28-bit delay in 72 MHz TGCLK ticks (bank 0 = Rear Curtain, bank 1 = Front Curtain)
                        // Committing mode 4 via +0x00 bit 0 halts the channel and clears +0x04 bit 0.
                        val mode = (words[2] ushr 16) and 0x7L
                        val delayTicks = words[3] and 0x0FFF_FFFFL
                        if (offset == 0L && (value and 1L) != 0L) {
                            if (mode == 4L) {
                                words[1] = words[1] and 1L.inv()
                                if ((bank == 0 || bank == 1) && isLiveViewTimingActive()) {
                                    if (bank == 0) stillRearCurtainDelayTicks = null else stillFrontCurtainDelayTicks = null
                                }
                            } else if ((bank == 0 || bank == 1) && delayTicks > 0L) {
                                // Ignore zero-delay init/reset pulses (DAT S5 0x02508adc).
                                if (bank == 0) stillRearCurtainDelayTicks = delayTicks else stillFrontCurtainDelayTicks = delayTicks
                            }
                        } else if ((bank == 0 || bank == 1) && offset == 4L && (value and 1L) != 0L && mode != 4L && delayTicks > 0L) {
                            if (bank == 0) stillRearCurtainDelayTicks = delayTicks else stillFrontCurtainDelayTicks = delayTicks
                        }
                    }
                })
            }

            if (replyProfile == REPLY_STATEFUL || replyProfile == REPLY_IMX461_SILICON) {
                context.ports.register(
                    id,
                    PORT_IMX461_EXPOSURE,
                    Imx461ExposurePort::class.java,
                    object : Imx461ExposurePort {
                        override val stillReadoutBitDepth: Int get() = stillReadoutBits
                        override val liveViewTimingActive: Boolean
                            get() = isLiveViewTimingActive()

                        private fun reg(sel: Int): Int =
                            imx461Shadow?.let { s ->
                                when (sel) {
                                    Imx461SpiShadow.REG_SVR_LO -> s.svr12() and 0xFF
                                    Imx461SpiShadow.REG_SVR_HI -> (s.svr12() ushr 8) and 0x0F
                                    Imx461SpiShadow.REG_SHR_15_LO -> s.shr15() and 0xFF
                                    Imx461SpiShadow.REG_SHR_15_HI -> (s.shr15() ushr 8) and 0x7F
                                    Imx461SpiShadow.REG_PGC_12_LO -> s.pgc12() and 0xFF
                                    Imx461SpiShadow.REG_PGC_12_HI -> (s.pgc12() ushr 8) and 0x0F
                                    Imx461SpiShadow.REG_PGC2_LO -> s.pgcSecondary12() and 0xFF
                                    Imx461SpiShadow.REG_PGC2_HI -> (s.pgcSecondary12() ushr 8) and 0x0F
                                    Imx461SpiShadow.REG_HCG -> if (s.hcgEnabled()) 1 else 0
                                    Imx461SpiShadow.REG_VMAX_LO -> s.vmax12() and 0xFF
                                    Imx461SpiShadow.REG_VMAX_HI -> (s.vmax12() ushr 8) and 0x0F
                                    Imx461SpiShadow.REG_DGAIN -> s.dgain8()
                                    else -> 0
                                }
                            } ?: (statefulRegisters[sel] ?: 0)

                        override val hasExposureRegs: Boolean
                            get() = imx461Shadow?.hasExposureRegs() ?: statefulHasExposureRegs()
                        override val shr15: Int
                            get() = imx461Shadow?.shr15() ?: statefulShr15()
                        override val svr12: Int
                            get() = imx461Shadow?.svr12() ?: statefulSvr12()
                        override val pgc12: Int
                            get() = imx461Shadow?.pgc12()
                                ?: (reg(Imx461SpiShadow.REG_PGC_12_LO) or ((reg(Imx461SpiShadow.REG_PGC_12_HI) and 0x0F) shl 8))
                        override val pgcSecondary12: Int
                            get() = imx461Shadow?.pgcSecondary12()
                                ?: (reg(Imx461SpiShadow.REG_PGC2_LO) or ((reg(Imx461SpiShadow.REG_PGC2_HI) and 0x0F) shl 8))
                        override val hcgEnabled: Boolean
                            get() = imx461Shadow?.hcgEnabled() ?: ((reg(Imx461SpiShadow.REG_HCG) and 0x01) != 0)
                        override val vmax12: Int
                            get() = imx461Shadow?.vmax12() ?: statefulVmax12()
                        override val dgain8: Int
                            get() = imx461Shadow?.dgain8() ?: reg(Imx461SpiShadow.REG_DGAIN)
                        override val mdsel8: Int
                            get() = imx461Shadow?.mdsel8() ?: statefulMdsel8()
                        override val modeFlags04: Int
                            get() = imx461Shadow?.modeFlags04() ?: statefulModeFlags04()
                        override val movieShr15: Int
                            get() = imx461Shadow?.movieShr15() ?: statefulMovieShr15()
                        override val effectiveExposureState: Imx461ExposureState
                            get() = exposureLatch.resolveState(
                                shr15 = shr15,
                                svr12 = svr12,
                                vmax12 = vmax12,
                                pgc12 = pgc12,
                                hcgEnabled = hcgEnabled,
                                regholdActive = imx461Shadow?.isRegholdActive ?: statefulRegholdActive(),
                                mdsel8 = mdsel8,
                                modeFlags04 = modeFlags04,
                                movieShr15 = movieShr15,
                                movieFrameRows = if (Imx461ExposurePort.isMovieShutterMode(mdsel8)) committedMovieFrameRows else 0,
                            )

                        override val stillShutterTiming: StillShutterHardwareTiming?
                            get() {
                                val mode = mdsel8
                                if (!Imx461ExposurePort.isStillCaptureMode(mode)) return null
                                val rear = stillRearCurtainDelayTicks
                                val front = stillFrontCurtainDelayTicks
                                val hasStillTiming = (rear != null && rear > 0L) ||
                                    (!liveViewTimingActive && stillExposureSpiWritten)
                                if (!hasStillTiming) return null
                                val bytes = c000Bytes
                                val timer194 = if (bytes != null) readWord(bytes, 0x194) else 0L
                                val ccdtgFrameLines = if ((timer194 ushr 16) != 0L) (timer194.toInt() and 0xFFFF) else 0
                                return StillShutterHardwareTiming(
                                    mdsel8 = mode,
                                    shr15 = shr15,
                                    svr12 = svr12,
                                    pgc12 = pgc12,
                                    hcgEnabled = hcgEnabled,
                                    frameLines = if (ccdtgFrameLines >= 500) ccdtgFrameLines else 0,
                                    rearCurtainDelayTicks = rear,
                                    frontCurtainDelayTicks = front,
                                    modeFlags05 = imx461Shadow?.modeFlags05() ?: statefulModeFlags05(),
                                )
                            }
                    },
                )
            }
        }

        private fun readRequestHeader(address: Long = REQUEST_ADDR): ByteArray {
            val mem = dmaMemory
            mem.check(address, 3L, false)
            val header = ByteArray(3)
            val buf = ByteBuffer.wrap(header)
            mem.read(address, buf)
            check(!buf.hasRemaining()) { "soc-isp-cmd short DmaMemory read at 0x${address.toString(16)}" }
            return header
        }

        private fun observeImx461Start() {
            val shadow = imx461Shadow ?: return
            val mode = c204Words[1]
            val modeOp = (mode ushr 16) and 0x3L
            val count = (mode and 0xffL).toInt()
            val pointer = c20cWords[1]
            if (modeOp == 1L && count in 1..255 && pointer != 0L) {
                val size = count * 8
                val mem = dmaMemory
                mem.check(pointer, size.toLong(), false)
                val payload = ByteArray(size)
                val buf = ByteBuffer.wrap(payload)
                mem.read(pointer, buf)
                check(!buf.hasRemaining()) { "soc-isp-cmd DmaMemory short read at 0x${pointer.toString(16)}" }
                var touchedStillExposure = false
                for (off in 0 until size step 8) {
                    if (payload[off] == 0x81.toByte()) {
                        val selector = ((payload[off + 1].toInt() and 0xff) shl 8) or (payload[off + 2].toInt() and 0xff)
                        shadow.writeRegister(selector, payload[off + 3].toInt() and 0xff)
                        if (isStillExposureSelector(selector)) touchedStillExposure = true
                    }
                }
                val mode = shadow.mdsel8()
                if (!Imx461ExposurePort.isStillCaptureMode(mode)) {
                    stillExposureSpiWritten = false
                } else if (touchedStillExposure) {
                    stillExposureSpiWritten = true
                }
                observeCurrentExposureLatch(commitFrameRows = true)
            } else if (modeOp == 3L && pointer != 0L) {
                imx461LastReadPtr = pointer
            }
        }

        private fun observeStatefulStart() {
            val mode = c204Words[1]
            val count = (mode and 0xffL).toInt()
            val pointer = c20cWords[1]
            if ((mode ushr 16) != 1L || count !in 1..255 || pointer == 0L) return
            val size = count * 8
            val mem = dmaMemory
            mem.check(pointer, size.toLong(), false)
            val payload = ByteArray(size)
            val buf = ByteBuffer.wrap(payload)
            mem.read(pointer, buf)
            check(!buf.hasRemaining()) { "soc-isp-cmd DmaMemory short read at 0x${pointer.toString(16)}" }
            var touchedStillExposure = false
            for (off in 0 until size step 8) {
                if (payload[off] == 0x81.toByte()) {
                    val selector = ((payload[off + 1].toInt() and 0xff) shl 8) or (payload[off + 2].toInt() and 0xff)
                    statefulRegisters[selector] = payload[off + 3].toInt() and 0xff
                    if (isStillExposureSelector(selector)) touchedStillExposure = true
                }
            }
            val mdsel = statefulMdsel8()
            if (!Imx461ExposurePort.isStillCaptureMode(mdsel)) {
                stillExposureSpiWritten = false
            } else if (touchedStillExposure) {
                stillExposureSpiWritten = true
            }
            observeCurrentExposureLatch(commitFrameRows = true)
        }

        private fun isStillExposureSelector(selector: Int): Boolean =
            selector == Imx461SpiShadow.REG_SHR_15_LO ||
                selector == Imx461SpiShadow.REG_SHR_15_HI ||
                selector == Imx461SpiShadow.REG_SVR_LO ||
                selector == Imx461SpiShadow.REG_SVR_HI ||
                selector == Imx461SpiShadow.REG_PGC_12_LO ||
                selector == Imx461SpiShadow.REG_PGC_12_HI ||
                selector == Imx461SpiShadow.REG_HCG

        private fun readReply(offset: Long): Long {
            require(replyProfile.isNotEmpty()) { "$PORT_REPLY is not configured" }
            if (replyProfile == REPLY_IMX461_SILICON) {
                val header = readRequestHeader(imx461LastReadPtr)
                require(header[0] == 0x82.toByte()) {
                    "$PORT_REPLY expected 0x82 header in guest RAM at 0x${imx461LastReadPtr.toString(16)}"
                }
                if (offset != 0x1cL) return 0L
                val selector = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
                return (checkNotNull(imx461Shadow).readRegister(selector).toLong() and 0xffL) shl 16
            }
            val header = readRequestHeader()
            if (replyProfile == REPLY_STATEFUL) {
                require(header[0] == 0x82.toByte()) {
                    "$PORT_REPLY expected 0x82 header in guest RAM at 0x028f2000"
                }
                val selector = ((header[1].toInt() and 0xff) shl 8) or (header[2].toInt() and 0xff)
                return if (offset == 0x1cL) (statefulRegisters[selector] ?: 0).toLong() shl 16 else 0L
            }
            require(header[0] == 0x82.toByte() && header[1] == 0.toByte()) {
                "$PORT_REPLY expected 0x82 0x00 header in guest RAM at 0x028f2000"
            }
            val actualSelector = header[2].toInt() and 0xff
            val interleaved = replyProfile == REPLY_16_PAIRS || replyProfile == REPLY_PROGRAMMED
            if (interleaved && interleaved820001.isNotEmpty() && actualSelector == 1) {
                require(pairPosition % 8 == 0) { "$PORT_REPLY cannot interleave 82 00 01 mid-reply" }
                if (reply01Position < 8) {
                    val word = reply01Position
                    val expectedOffset = (0x1c - word * 4).toLong()
                    require(offset == expectedOffset) {
                        "$PORT_REPLY expected offset 0x${expectedOffset.toString(16)} got 0x${offset.toString(16)}"
                    }
                    reply01Position++
                    return if (word == 0 && interleaved820001 == REPLY_AF_ONCE) 0x00af0000L else 0L
                }
                val model = programmedReply ?: error("$PORT_REPLY 82 00 01 exhausted")
                check(model.words < 8) { "$PORT_REPLY programmed reply exhausted" }
                val expectedOffset = (0x1c - model.words * 4).toLong()
                require(offset == expectedOffset) {
                    "$PORT_REPLY expected offset 0x${expectedOffset.toString(16)} got 0x${offset.toString(16)}"
                }
                return model.read()
            }

            if (interleaved) {
                require(reply01Position !in 1..7 && programmedReply?.words !in 1..7) {
                    "$PORT_REPLY cannot resume pair reads while 82 00 01 is partial"
                }
                require(pairPosition < REPLY_TOTAL_WORDS) { "$PORT_REPLY 16-pair sequence exhausted" }
                val expectedSelector = if (pairPosition % REPLY_PAIR_WORDS < 8) 0x40 else 0x41
                val word = pairPosition % 8
                val expectedOffset = (0x1c - word * 4).toLong()
                require(actualSelector == expectedSelector && offset == expectedOffset) {
                    "$PORT_REPLY selector/offset mismatch: expected sel=0x${expectedSelector.toString(16)} off=0x${expectedOffset.toString(16)} actual sel=0x${actualSelector.toString(16)} off=0x${offset.toString(16)}"
                }
                pairPosition++
                return 0L
            }

            val expectedSelector = when (replyProfile) {
                REPLY_ZERO_ONCE, REPLY_AF_ONCE -> 1
                REPLY_820040_ONCE -> 0x40
                REPLY_820041_ONCE -> 0x41
                else -> error("Unsupported replyProfile: $replyProfile")
            }
            require(pairPosition < 8) { "$PORT_REPLY single reply exhausted" }
            val expectedOffset = (0x1c - pairPosition * 4).toLong()
            require(actualSelector == expectedSelector && offset == expectedOffset) {
                "$PORT_REPLY selector/offset mismatch"
            }
            val value = if (pairPosition == 0 && replyProfile == REPLY_AF_ONCE) 0x00af0000L else 0L
            pairPosition++
            return value
        }

        override fun start() {
            check(!closed) { "soc-isp-cmd closed" }
            if (active) {
                interruptSink = context.ports.find("soc-gic.lines", InterruptSink::class.java)
            }
        }

        override fun close() {
            if (!closed && channelIrqsEnabled) {
                for (ch in 0..1) {
                    if (channelIrqAsserted[ch]) {
                        channelIrqAsserted[ch] = false
                        irqSink?.setLine(CH_IRQS[ch], false)
                    }
                }
            }
            closed = true
        }

        override fun additionalMmioPorts(): List<String> = if (active) ALL_PORTS else emptyList()

        override fun interruptRoutes(): List<InterruptRoute> = buildList {
            if (active) add(InterruptRoute("soc-gic.lines", IRQ_PIEZO..IRQ_PIEZO))
            if (active && channelIrqsEnabled) add(InterruptRoute("soc-gic.lines", CH2_IRQ..CH3_IRQ))
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active,
            "ports" to additionalMmioPorts(),
            "c000Phase" to c000Phase.name,
            "channelIrqsEnabled" to channelIrqsEnabled,
            "channelStatus" to channelStatus.toList(),
            "channelIrqCount" to channelIrqCount.toList(),
            "c204Words" to c204Words.toList(),
            "c20cWords" to c20cWords.toList(),
            "pairPosition" to pairPosition,
            "reply01Position" to reply01Position,
            "programmedReply" to when (replyProfile) {
                REPLY_STATEFUL -> linkedMapOf("profile" to REPLY_STATEFUL, "registers" to statefulRegisters.size)
                REPLY_IMX461_SILICON -> imx461Shadow?.state()
                else -> programmedReply?.receipt()
            },
            "piezo" to piezoController?.state(),
            "basis" to "DAT section4/5 addresses, wordRuns & ultrasonic piezo controller (0xFFF6C400..0xFFF6C42B, IRQ 104); C000StopReply, clearC204Start, ch2/ch3 IRQ 97/98 W1C, ProgrammedCommandReply, Imx461SpiShadow DmaMemory readback, and config banks are HYPOTHESIS; physical identity/timing UNKNOWN",
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-isp-cmd closed" }
            val result = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to active,
                "c000WordsBase64" to c000Bytes?.let { Base64.getEncoder().encodeToString(it) },
                "c000Phase" to c000Phase.name,
                "c204Words" to if (active) c204Words.toList() else emptyList<Long>(),
                "c20cWords" to if (active) c20cWords.toList() else emptyList<Long>(),
                "configBankWords" to if (active) configBankWords.map { it.toList() } else emptyList<List<Long>>(),
                "pairPosition" to pairPosition,
                "reply01Position" to reply01Position,
                "programmedReply" to when (replyProfile) {
                    REPLY_STATEFUL -> linkedMapOf(
                        "profile" to REPLY_STATEFUL,
                        "registers" to statefulRegisters.entries.associate { (k, v) -> k.toString() to v.toLong() },
                    )
                    REPLY_IMX461_SILICON -> imx461Shadow?.snapshot()
                    else -> programmedReply?.snapshot()
                },
            )
            piezoController?.takeUnless { it.isInitial() }?.let { result["piezo"] = it.snapshot() }
            exposureLatch.snapshot()?.let { result["exposureLatch"] = it }
            if (stillReadoutBits != 14) result["stillReadoutBits"] = stillReadoutBits.toLong()
            stillRearCurtainDelayTicks?.let { result["stillRearCurtainDelayTicks"] = it }
            stillFrontCurtainDelayTicks?.let { result["stillFrontCurtainDelayTicks"] = it }
            if (stillExposureSpiWritten) result["stillExposureSpiWritten"] = true
            if (active && channelIrqsEnabled) {
                result["channelStartWritten"] = channelStartWritten.toList()
                result["channelMaskWritten"] = channelMaskWritten.toList()
                result["channelDrainPending"] = channelDrainPending.toList()
                result["channelStatus"] = channelStatus.toList()
                result["channelIrqAsserted"] = channelIrqAsserted.toList()
                result["channelNextIrqTick"] = channelNextIrqTick.toList()
                result["channelIrqCount"] = channelIrqCount.toList()
            }
            return result
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-isp-cmd closed" }
            val hasPiezo = "piezo" in data
            val hasExposureLatch = "exposureLatch" in data
            val nextReadoutBits = if ("stillReadoutBits" in data) {
                val bits = snapshotLong(data["stillReadoutBits"], "stillReadoutBits", 14L, 16L).toInt()
                require(active && (bits == 14 || bits == 16)) { "Invalid still readout bit depth" }
                bits
            } else 14
            val nextStillRear = data["stillRearCurtainDelayTicks"]?.let {
                require(active) { "Disabled soc-isp-cmd snapshot cannot carry still shutter timing" }
                snapshotLong(it, "stillRearCurtainDelayTicks", 1L, 0x0FFF_FFFFL)
            }
            val nextStillFront = data["stillFrontCurtainDelayTicks"]?.let {
                require(active) { "Disabled soc-isp-cmd snapshot cannot carry still shutter timing" }
                snapshotLong(it, "stillFrontCurtainDelayTicks", 1L, 0x0FFF_FFFFL)
            }
            val nextStillSpiWritten = if ("stillExposureSpiWritten" in data) {
                require(active) { "Disabled soc-isp-cmd snapshot cannot carry still shutter timing" }
                snapshotBoolean(data["stillExposureSpiWritten"], "stillExposureSpiWritten")
            } else {
                false
            }
            val allowedKeys = SNAPSHOT_KEYS + OPTIONAL_CHANNEL_SNAPSHOT_KEYS +
                setOf("stillReadoutBits", "stillRearCurtainDelayTicks", "stillFrontCurtainDelayTicks", "stillExposureSpiWritten") +
                (if (hasPiezo) setOf("piezo") else emptySet()) +
                (if (hasExposureLatch) setOf("exposureLatch") else emptySet())
            require(data.keys.containsAll(SNAPSHOT_KEYS) && allowedKeys.containsAll(data.keys)) {
                "Unexpected snapshot keys for $id"
            }
            require(snapshotLong(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            val normalizedScalars = if ("channelIrqsEnabled" in scalarsRaw) {
                scalarsRaw
            } else {
                scalarsRaw + ("channelIrqsEnabled" to false)
            }
            require(normalizedScalars.keys == DESCRIPTOR.settings.map { it.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            val nextC000Bytes: ByteArray?
            val nextC000Phase: StopPhase
            val nextStartWritten = BooleanArray(2)
            val nextMaskWritten = BooleanArray(2)
            val nextDrainPending = BooleanArray(2)
            val nextStatus = LongArray(2)
            val nextIrqAsserted = BooleanArray(2)
            val nextIrqTick = LongArray(2) { -1L }
            val nextIrqCount = LongArray(2)
            val nextC204Words = LongArray(2)
            val nextC20CWords = LongArray(5)
            val nextConfigBanks = List(7) { bank -> LongArray(CONFIG_BANK_SIZES[bank] / 4) }
            val nextPairPosition: Int
            val nextReply01Position: Int
            val nextProgrammed: ProgrammedCommandReply?
            val nextStatefulRegs = linkedMapOf<Int, Int>()
            var nextImx461Shadow: Imx461SpiShadow? = null
            var nextPiezo: UltrasonicPiezoController.ParsedSnapshot? = null
            val nextExposureLatch = Imx461ExposureLatch(context.clock)
            try {
                require(snapshotBoolean(data["enabled"], "enabled") == active) {
                    "Snapshot enabled flag differs for $id"
                }
                if (!active) {
                    require(!hasPiezo) { "Disabled soc-isp-cmd snapshot cannot carry piezo state" }
                    require(!hasExposureLatch) { "Disabled soc-isp-cmd snapshot cannot carry exposureLatch state" }
                    restoreScalarValues(normalizedScalars, DESCRIPTOR)
                    require(
                        data["c000WordsBase64"] == null &&
                            data["c000Phase"] == StopPhase.IDLE.name &&
                            snapshotList(data["c204Words"], "c204Words").isEmpty() &&
                            snapshotList(data["c20cWords"], "c20cWords").isEmpty() &&
                            snapshotList(data["configBankWords"], "configBankWords").isEmpty() &&
                            snapshotLong(data["pairPosition"], "pairPosition", 0L, 0L) == 0L &&
                            snapshotLong(data["reply01Position"], "reply01Position", 0L, 0L) == 0L &&
                            data["programmedReply"] == null,
                    ) { "Disabled soc-isp-cmd snapshot cannot carry active state" }
                    nextC000Bytes = null
                    nextC000Phase = StopPhase.IDLE
                    nextPairPosition = 0
                    nextReply01Position = 0
                    nextProgrammed = null
                } else {
                    val savedStop = normalizedScalars["c000StopProfile"] as? String ?: ""
                    val savedReply = normalizedScalars["replyProfile"] as? String ?: ""
                    val savedInterleaved = normalizedScalars["interleaved820001"] as? String ?: ""
                    val savedChannelIrqs = normalizedScalars["channelIrqsEnabled"] as? Boolean ?: false
                    val compatible = mutableSetOf<String>()
                    if (savedStop != c000StopProfile) {
                        val validStopPromo = (savedStop == "" && c000StopProfile in listOf(C000_STOP_ONCE, C000_STOP_REPEATABLE)) ||
                            (savedStop == C000_STOP_ONCE && c000StopProfile == C000_STOP_REPEATABLE)
                        require(validStopPromo) {
                            "Incompatible c000StopProfile transition: $savedStop -> $c000StopProfile"
                        }
                        compatible += "c000StopProfile"
                    }
                    val savedPairPos = snapshotLong(data["pairPosition"], "pairPosition", 0L, REPLY_TOTAL_WORDS.toLong()).toInt()
                    val saved01Pos = snapshotLong(data["reply01Position"], "reply01Position", 0L, 8L).toInt()
                    if (savedReply != replyProfile) {
                        val validReplyPromo = (savedReply == REPLY_16_PAIRS && replyProfile == REPLY_PROGRAMMED && saved01Pos == 8) ||
                            (savedReply == REPLY_820041_ONCE && replyProfile in listOf(REPLY_16_PAIRS, REPLY_PROGRAMMED) && savedPairPos == 8) ||
                            (savedReply == "" && replyProfile.isNotEmpty() && savedPairPos == 0 && saved01Pos == 0)
                        require(validReplyPromo) {
                            "Incompatible replyProfile transition: $savedReply -> $replyProfile"
                        }
                        compatible += "replyProfile"
                    }
                    if (savedInterleaved != interleaved820001) {
                        require(savedInterleaved == "" && interleaved820001.isNotEmpty() && saved01Pos == 0) {
                            "Incompatible interleaved820001 transition: $savedInterleaved -> $interleaved820001"
                        }
                        compatible += "interleaved820001"
                    }
                    if (normalizedScalars["clearC204Start"] != clearC204Start) {
                        require(normalizedScalars["clearC204Start"] == false && clearC204Start) {
                            "Cannot disable clearC204Start on restore"
                        }
                        compatible += "clearC204Start"
                    }
                    if (savedChannelIrqs != channelIrqsEnabled) {
                        require(!savedChannelIrqs && channelIrqsEnabled) {
                            "Cannot disable channelIrqsEnabled on restore"
                        }
                        compatible += "channelIrqsEnabled"
                    }
                    restoreScalarValues(normalizedScalars, DESCRIPTOR, compatible)

                    val decodedC000 = Base64.getDecoder().decode(snapshotString(data["c000WordsBase64"], "c000WordsBase64"))
                    require(decodedC000.size == C000_SIZE) { "c000WordsBase64 size mismatch" }
                    nextC000Bytes = decodedC000
                    nextC000Phase = StopPhase.valueOf(snapshotString(data["c000Phase"], "c000Phase"))
                    if (savedStop == "" && c000StopProfile.isNotEmpty()) {
                        require(nextC000Phase == StopPhase.IDLE) { "Promoted c000StopProfile requires IDLE phase" }
                    }
                    if (nextC000Phase == StopPhase.REQUESTED) {
                        require((decodedC000[0].toInt() and 1) == 0) {
                            "REQUESTED c000Phase requires bit0 clear at 0xfff6c000"
                        }
                    }

                    if (data["channelStatus"] != null) {
                        val rawSw = snapshotList(data["channelStartWritten"], "channelStartWritten")
                        val rawMw = snapshotList(data["channelMaskWritten"], "channelMaskWritten")
                        val rawDp = data["channelDrainPending"]?.let { snapshotList(it, "channelDrainPending") }
                        val rawSt = snapshotList(data["channelStatus"], "channelStatus")
                        val rawIa = snapshotList(data["channelIrqAsserted"], "channelIrqAsserted")
                        val rawNt = snapshotList(data["channelNextIrqTick"], "channelNextIrqTick")
                        val rawIc = snapshotList(data["channelIrqCount"], "channelIrqCount")
                        require(rawSw.size == 2 && rawMw.size == 2 && (rawDp == null || rawDp.size == 2) && rawSt.size == 2 && rawIa.size == 2 && rawNt.size == 2 && rawIc.size == 2) {
                            "Channel IRQ snapshot arrays require 2 entries"
                        }
                        for (ch in 0..1) {
                            nextStartWritten[ch] = snapshotBoolean(rawSw[ch], "channelStartWritten[$ch]")
                            nextMaskWritten[ch] = snapshotBoolean(rawMw[ch], "channelMaskWritten[$ch]")
                            nextDrainPending[ch] = rawDp?.let { snapshotBoolean(it[ch], "channelDrainPending[$ch]") } ?: false
                            nextStatus[ch] = snapshotLong(rawSt[ch], "channelStatus[$ch]", 0L, 0xfL)
                            nextIrqAsserted[ch] = snapshotBoolean(rawIa[ch], "channelIrqAsserted[$ch]")
                            nextIrqTick[ch] = snapshotLong(rawNt[ch], "channelNextIrqTick[$ch]", -1L, Long.MAX_VALUE)
                            nextIrqCount[ch] = snapshotLong(rawIc[ch], "channelIrqCount[$ch]", 0L, Long.MAX_VALUE)
                        }
                    }

                    val rawC204 = snapshotList(data["c204Words"], "c204Words")
                    require(rawC204.size == 2) { "c204Words requires 2 entries" }
                    for (i in 0..1) nextC204Words[i] = snapshotLong(rawC204[i], "c204Words[$i]", 0L, 0xffffffffL)

                    val rawC20C = snapshotList(data["c20cWords"], "c20cWords")
                    require(rawC20C.size == 5) { "c20cWords requires 5 entries" }
                    for (i in 0..4) nextC20CWords[i] = snapshotLong(rawC20C[i], "c20cWords[$i]", 0L, 0xffffffffL)

                    val rawBanks = snapshotList(data["configBankWords"], "configBankWords")
                    require(rawBanks.size == 7) { "configBankWords requires 7 banks" }
                    for (b in 0..6) {
                        val bankList = snapshotList(rawBanks[b], "configBankWords[$b]")
                        require(bankList.size == nextConfigBanks[b].size) { "configBankWords[$b] size mismatch" }
                        for (w in bankList.indices) {
                            nextConfigBanks[b][w] = snapshotLong(bankList[w], "configBankWords[$b][$w]", 0L, 0xffffffffL)
                        }
                    }

                    nextPairPosition = if (savedReply == REPLY_820041_ONCE && replyProfile != REPLY_820041_ONCE) {
                        REPLY_PAIR_WORDS
                    } else {
                        savedPairPos
                    }
                    nextReply01Position = saved01Pos
                    val rawProg = data["programmedReply"]
                    if (hasPiezo) {
                        nextPiezo = UltrasonicPiezoController.parseSnapshot(data["piezo"])
                    }
                    if (hasExposureLatch) {
                        nextExposureLatch.restore(snapshotMap(data["exposureLatch"], "exposureLatch"))
                    }
                    nextProgrammed = when {
                        replyProfile == REPLY_IMX461_SILICON -> {
                            nextImx461Shadow = Imx461SpiShadow().apply {
                                if (rawProg != null) {
                                    restore(snapshotMap(rawProg, "programmedReply"))
                                }
                            }
                            null
                        }
                        replyProfile == REPLY_STATEFUL -> {
                            if (rawProg != null) {
                                val progMap = snapshotMap(rawProg, "programmedReply")
                                require(progMap.keys == setOf("profile", "registers") && progMap["profile"] == REPLY_STATEFUL) {
                                    "Unexpected stateful programmedReply snapshot format"
                                }
                                val regsMap = snapshotMap(progMap["registers"], "programmedReply.registers")
                                for ((k, v) in regsMap) {
                                    val key = (k as String).toInt()
                                    require(key in 0..0xffff) { "Stateful register selector out of range: $key" }
                                    nextStatefulRegs[key] = snapshotLong(v, "programmedReply.registers[$k]", 0L, 0xffL).toInt()
                                }
                            }
                            null
                        }
                        rawProg != null -> {
                            require(replyProfile == REPLY_PROGRAMMED) {
                                "Snapshot programmedReply present while replyProfile=$replyProfile"
                            }
                            ProgrammedCommandReply.restore(snapshotMap(rawProg, "programmedReply")).also { model ->
                                model.verifyBacking { addr ->
                                    when (addr) {
                                        in C204_BASE until C204_BASE + C204_SIZE -> nextC204Words[((addr - C204_BASE) / 4L).toInt()]
                                        in C20C_BASE until C20C_BASE + C20C_SIZE -> nextC20CWords[((addr - C20C_BASE) / 4L).toInt()]
                                        else -> error("Invalid programmed address 0x${addr.toString(16)}")
                                    }
                                }
                            }
                        }
                        replyProfile == REPLY_PROGRAMMED -> {
                            require(savedReply != REPLY_PROGRAMMED) { "Missing programmedReply in snapshot" }
                            ProgrammedCommandReply()
                        }
                        else -> null
                    }
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }

            if (nextC000Bytes != null) {
                nextC000Bytes.copyInto(checkNotNull(c000Bytes))
            }
            c000Phase = nextC000Phase
            nextStartWritten.copyInto(channelStartWritten)
            nextMaskWritten.copyInto(channelMaskWritten)
            nextDrainPending.copyInto(channelDrainPending)
            nextStatus.copyInto(channelStatus)
            nextIrqAsserted.copyInto(channelIrqAsserted)
            nextIrqTick.copyInto(channelNextIrqTick)
            nextIrqCount.copyInto(channelIrqCount)
            if (channelIrqsEnabled) {
                for (ch in 0..1) {
                    irqSink?.setLine(CH_IRQS[ch], channelIrqAsserted[ch])
                }
            }
            nextC204Words.copyInto(c204Words)
            nextC20CWords.copyInto(c20cWords)
            for (b in 0..6) nextConfigBanks[b].copyInto(configBankWords[b])
            pairPosition = nextPairPosition
            reply01Position = nextReply01Position
            programmedReply = nextProgrammed
            statefulRegisters.clear()
            statefulRegisters.putAll(nextStatefulRegs)
            imx461Shadow = nextImx461Shadow
            stillReadoutBits = nextReadoutBits
            stillRearCurtainDelayTicks = nextStillRear
            stillFrontCurtainDelayTicks = nextStillFront
            stillExposureSpiWritten = nextStillSpiWritten
            imx461LastReadPtr = REQUEST_ADDR
            if (hasExposureLatch) {
                exposureLatch.restore(nextExposureLatch.snapshot())
                committedMovieFrameRows = nextExposureLatch.latchedState?.movieFrameRows
                    ?: currentRawMovieFrameRows(imx461Shadow?.mdsel8() ?: statefulMdsel8())
            } else {
                committedMovieFrameRows = 0
                exposureLatch.reset()
                observeCurrentExposureLatch(commitFrameRows = true)
            }
            piezoController?.applySnapshot(nextPiezo)
        }
    }
}
