package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.IdentityHashMap

/** EX-006/007, MD-004/005, HYP-004. Physical bases are exclusively board metadata. */
class DmaFactory : ComponentFactory {
    companion object {
        const val CH0_CONTROL_PORT = "soc-dma.ch0-control"
        const val CH0_PARAMS_PORT = "soc-dma.ch0-params"
        const val CH0_TIMER_IRQ = 41
        const val CH4_CONTROL_PORT = "soc-dma.ch4-control"
        const val CH4_PARAMS_PORT = "soc-dma.ch4-params"
        const val CH7_ENABLE_PORT = "soc-dma.ch7-enable"
        const val CH7_CONTROL_PORT = "soc-dma.ch7-control"
        const val CH7_PARAMS_PORT = "soc-dma.ch7-params"
        const val CH8_CONTROL_PORT = "soc-dma.ch8-control"
        const val CH8_PARAMS_PORT = "soc-dma.ch8-params"
        const val CH8_TIMER_IRQ = 49
        const val CH10_CONTROL_PORT = "soc-dma.ch10-control"
        const val CH10_PARAMS_PORT = "soc-dma.ch10-params"
        const val CH10_TIMER_IRQ = 51
        const val CH11_CONTROL_PORT = "soc-dma.ch11-control"
        const val CH11_PARAMS_PORT = "soc-dma.ch11-params"
        const val CH14_CONTROL_PORT = "soc-dma.ch14-control"
        const val CH14_PARAMS_PORT = "soc-dma.ch14-params"
        const val CH14_TIMER_PROFILE = "timer-ch14-irq55-v1"
        const val CH14_GIC_IRQ = 55
        const val CH14_TIMER_IRQ = 55
        const val WRITE_WORDS_PROFILE = "write-words-v1"
        const val CONTROL_READBACK_PROFILE = "control-readback-after-write-v1"
        const val RETAINED_ZERO_PROFILE = "retained-words-zero-v1"
        const val ONESHOT_IRQ41_PROFILE = "oneshot-irq41-v1"
        const val ONESHOT_IRQ49_PROFILE = "oneshot-irq49-v1"
        const val ONESHOT_IRQ51_PROFILE = "oneshot-irq51-v1"
        const val ONESHOT_IRQ55_PROFILE = "oneshot-irq55-v1"
        const val TIMER0_ALL_CHANNELS_PROFILE = "all-16-channels-v1"
        const val CHAINED_RING_PROFILE = "chained-descriptor-ring-v1"
        const val AUDIO_RX_SOURCE_PROFILE = "dual-i2s-rx-source-v1"
        private const val CHAINED_RING_BEAT_TICKS = 500_000L
        private const val CHAINED_RING_STEP_BYTES = 64L
        private const val AUDIO_RING_STEP_BYTES = 512L
        private val AUDIO_DMA_REQUESTS = setOf(28, 29, 30)
        // 54 MHz SoC clock / 48 kHz I2S LRCLK = 1,125 ticks per stereo frame:
        // 4 bytes/frame for 16-bit packed stereo (1 word/frame) and 8 bytes/frame for 24/32-bit stereo (2 words/frame,
        // e.g. chained TSK_SOUNDIO rings 5,760 B = 720 frames = 15.000 ms / 810,000 ticks), keeping both STILL 16-bit
        // and MOVIE 24-bit I2S0 RX / I2S1 TX locked to true 48,000 Hz/vsec (BUG-V3-1-3, BUG-V3-7-5).
        private const val AUDIO_TICKS_PER_FRAME = 1_125L

        private val DESCRIPTOR = ComponentDescriptor("soc-dma", "0.0.1", settings = listOf(
            Setting("profile", ValueKind.TEXT, "standalone-experimental-v1", choices = listOf("standalone-experimental-v1"), live = false),
            Setting("memoryPort", ValueKind.TEXT, "cpu.memory", live = false),
            Setting("softwareProfile",ValueKind.TEXT,"",choices = listOf("","software-request44-experimental-v1"),live = false),
            Setting("spi0DmaProfile", ValueKind.TEXT, "", choices = listOf("", Spi0Dma.PROFILE), live = false),
            Setting("spi2DmaProfile", ValueKind.TEXT, "", choices = listOf("", Spi2Dma.PROFILE), live = false),
            Setting("chainedRingProfile", ValueKind.TEXT, "", choices = listOf("", CHAINED_RING_PROFILE), live = false),
            Setting("audioRxSourceProfile", ValueKind.TEXT, "", choices = listOf("", AUDIO_RX_SOURCE_PROFILE), live = false),
            Setting("channel0Programming", ValueKind.TEXT, "", choices = listOf("", ONESHOT_IRQ41_PROFILE), live = false),
            Setting("channel4Programming", ValueKind.TEXT, "", choices = listOf("", WRITE_WORDS_PROFILE, CONTROL_READBACK_PROFILE), live = false),
            Setting("channel7Programming", ValueKind.TEXT, "", choices = listOf("", RETAINED_ZERO_PROFILE), live = false),
            Setting("channel8Programming", ValueKind.TEXT, "", choices = listOf("", ONESHOT_IRQ49_PROFILE), live = false),
            Setting("channel10Programming", ValueKind.TEXT, "", choices = listOf("", ONESHOT_IRQ51_PROFILE), live = false),
            Setting("channel11Programming", ValueKind.TEXT, "", choices = listOf("", WRITE_WORDS_PROFILE, CONTROL_READBACK_PROFILE), live = false),
            Setting("channel14TimerProfile", ValueKind.TEXT, "", choices = listOf("", CH14_TIMER_PROFILE), live = false),
            Setting("channel14Programming", ValueKind.TEXT, "", choices = listOf("", ONESHOT_IRQ55_PROFILE), live = false),
            Setting("timer0AllChannelsProfile", ValueKind.TEXT, "", choices = listOf("", TIMER0_ALL_CHANNELS_PROFILE), live = false),
            Setting("transmitPeerPort", ValueKind.TEXT, "", live = false), Setting("destinationPeersFile",ValueKind.TEXT,"",live=false), Setting("flashPeerPort", ValueKind.TEXT, "", live = false), Setting("interruptPort", ValueKind.TEXT, "", live = false),
            Setting("irqBase", ValueKind.INTEGER, 207L, 32.0, 496.0, live = false),
            Setting("auditTransfers", ValueKind.BOOLEAN, true, live = true),
        ))
        private val OPTIONAL_SCALAR_KEYS = setOf("spi0DmaProfile", "spi2DmaProfile", "chainedRingProfile", "audioRxSourceProfile", "channel0Programming", "channel4Programming", "channel7Programming", "channel8Programming", "channel10Programming", "channel11Programming", "channel14TimerProfile", "channel14Programming", "timer0AllChannelsProfile", "auditTransfers")
        private const val UNAUDITED_SHA256 = ""
        private const val DIRECT_INVALIDATE_COALESCE_GAP = 65536L
        private val LITTLE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)
        private val PROGRAMMING_SNAPSHOT_KEYS = setOf(
            "ch4Control", "ch4ControlWritten", "ch4Params",
            "ch7Enable", "ch7Control", "ch7Params",
            "ch11Control", "ch11ControlWritten", "ch11Params",
        )
        private val CH14_TIMER_SNAPSHOT_KEYS = setOf("ch14Control", "ch14Params", "ch14Running", "ch14Deadline", "ch14FireCount")
        private fun timerSnapshotKeys(prefix: String): Set<String> = setOf(
            "${prefix}Control", "${prefix}ControlWritten", "${prefix}Params", "${prefix}Deadline", "${prefix}Expirations",
        )
        private val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalars", "channels", "mux", "muxChanged", "requests", "eventCount", "events")
        private val EXTENDED_SNAPSHOT_KEYS = SNAPSHOT_KEYS + "channelProgramming"
        private val CHANNEL_SNAPSHOT_KEYS = setOf("registers", "deadline", "submittedAt", "fromFlash", "toPeer", "destinationPeer", "pending", "completed")
        private val BASE_EVENT_KEYS = setOf("channel", "source", "destination", "size", "completedAt", "observedAt", "sourceFixed", "sourceSha256", "destinationSha256")
        private val PEER_EVENT_KEYS = BASE_EVENT_KEYS + setOf("destinationFixed", "destinationPeer", "request")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private fun requireLong(value: Any?, field: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            return (value as? Number)?.toLong() ?: error("Unreachable integer conversion for $field")
        }
        private fun requireInt(value: Any?, field: String): Int {
            val number = requireLong(value, field)
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out-of-range int for $field" }
            return number.toInt()
        }
        private fun requireBoolean(value: Any?, field: String): Boolean {
            require(value is Boolean) { "Expected boolean for $field" }
            return value
        }
        private fun requireSha256(value: Any?, field: String, allowEmpty: Boolean = false): String {
            require(value is String && ((allowEmpty && value.isEmpty()) || HEX_SHA256.matches(value))) {
                "Expected SHA-256 hex for $field"
            }
            return value
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Dma(context, config)
    class Dma(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("soc-dma", context, config), StatefulComponent, NativeMmioMirrorPort {
        private var registersMirror: MmioMirrorHandle? = null
        private var muxMirror: MmioMirrorHandle? = null
        private var requestsMirror: MmioMirrorHandle? = null
        private var ch7EnableMirror: MmioMirrorHandle? = null
        private val timerControlMirrors = arrayOfNulls<MmioMirrorHandle>(16)

        private fun regSlotIndex(channelIdx: Int, regOffset: Int): Int {
            if (channelIdx in 0..4) {
                val sub = when (regOffset) {
                    0x00 -> 0
                    in 0x10..0x34 -> if ((regOffset and 3) == 0) 1 + ((regOffset - 0x10) ushr 2) else -1
                    else -> -1
                }
                return if (sub >= 0) channelIdx * 11 + sub else -1
            }
            if (channelIdx in 5..8) {
                val base = 55 + (channelIdx - 5) * 2
                return when (regOffset) {
                    0x28 -> base
                    0x30 -> base + 1
                    else -> -1
                }
            }
            return -1
        }

        private fun syncChannelRegMirror(channelIdx: Int, regOffset: Int, value: Long) {
            val slot = regSlotIndex(channelIdx, regOffset)
            if (slot >= 0) registersMirror?.writeSlot(slot, value.toInt())
        }

        private fun syncChannelMirror(channelIdx: Int) {
            val h = registersMirror ?: return
            val r = channels[channelIdx].registers
            if (channelIdx in 0..4) {
                val base = channelIdx * 11
                h.writeSlot(base, r[0].toInt())
                for (k in 0..9) h.writeSlot(base + 1 + k, r[4 + k].toInt())
            } else if (channelIdx in 5..8) {
                val base = 55 + (channelIdx - 5) * 2
                h.writeSlot(base, r[10].toInt())
                h.writeSlot(base + 1, r[12].toInt())
            }
        }

        private fun syncAllMirrors() {
            registersMirror?.let { h ->
                for (ch in 0..4) {
                    val r = channels[ch].registers
                    val base = ch * 11
                    h.writeSlotRaw(base, r[0].toInt())
                    for (k in 0..9) h.writeSlotRaw(base + 1 + k, r[4 + k].toInt())
                }
                for (ch in 5..8) {
                    val r = channels[ch].registers
                    val base = 55 + (ch - 5) * 2
                    h.writeSlotRaw(base, r[10].toInt())
                    h.writeSlotRaw(base + 1, r[12].toInt())
                }
                h.setValidMask(h.allSlotsMask)
            }
            muxMirror?.let { h ->
                for (i in mux.indices) h.writeSlotRaw(i, mux[i].toInt())
                h.setValidMask(h.allSlotsMask)
            }
            requestsMirror?.let { h ->
                for (i in requests.indices) h.writeSlotRaw(i, requests[i].toInt())
                h.setValidMask(h.allSlotsMask)
            }
            ch7EnableMirror?.writeSlot(0, ch7EnableWord.toInt())
            for (t in oneshotTimers) {
                if (t.enabled) timerControlMirrors[t.channel]?.writeSlot(0, t.controlWord.toInt())
            }
        }

        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> = buildList {
            val regSlots = ArrayList<MmioMirrorSlotSpec>(63)
            for (ch in 0..4) {
                val base = ch.toLong() shl 12
                regSlots.add(MmioMirrorSlotSpec(base, 4))
                for (off in 0x10..0x34 step 4) regSlots.add(MmioMirrorSlotSpec(base + off.toLong(), 4))
            }
            for (ch in 5..8) {
                val base = ch.toLong() shl 12
                regSlots.add(MmioMirrorSlotSpec(base + 0x28L, 4))
                regSlots.add(MmioMirrorSlotSpec(base + 0x30L, 4))
            }
            add(MmioMirrorWindowSpec("$id.registers", 0x10000L, regSlots))
            add(MmioMirrorWindowSpec("$id.mux", 0x20L, List(8) { i -> MmioMirrorSlotSpec(i * 4L, 4) }))
            add(MmioMirrorWindowSpec("$id.requests", 0x80L, List(32) { i -> MmioMirrorSlotSpec(i * 4L, 4) }))
            if (ch7Programming == RETAINED_ZERO_PROFILE || ch14TimerEnabled || anyOneshotTimerEnabled) {
                add(MmioMirrorWindowSpec(CH7_ENABLE_PORT, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            }
            for (t in oneshotTimers) {
                if (t.enabled) add(MmioMirrorWindowSpec(t.controlPort, 4L, listOf(MmioMirrorSlotSpec(0L, 4))))
            }
        }

        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            when (portName) {
                "$id.registers" -> { registersMirror = handle; syncAllMirrors() }
                "$id.mux" -> { muxMirror = handle; syncAllMirrors() }
                "$id.requests" -> { requestsMirror = handle; syncAllMirrors() }
                CH7_ENABLE_PORT -> { ch7EnableMirror = handle; handle.writeSlot(0, ch7EnableWord.toInt()) }
                else -> {
                    val t = oneshotTimers.firstOrNull { it.enabled && it.controlPort == portName } ?: return
                    timerControlMirrors[t.channel] = handle
                    handle.writeSlot(0, t.controlWord.toInt())
                }
            }
        }

        override fun detachMmioMirrors() {
            registersMirror?.invalidateAll(); registersMirror = null
            muxMirror?.invalidateAll(); muxMirror = null
            requestsMirror?.invalidateAll(); requestsMirror = null
            ch7EnableMirror?.invalidateAll(); ch7EnableMirror = null
            for (i in timerControlMirrors.indices) {
                timerControlMirrors[i]?.invalidateAll()
                timerControlMirrors[i] = null
            }
        }
        override fun interruptRoutes(): List<InterruptRoute> {
            val sink = values.getValue("interruptPort") as String; val base = (values.getValue("irqBase") as Number).toInt()
            if (sink.isEmpty()) return emptyList()
            val routes = mutableListOf(InterruptRoute(sink, base..base + 15))
            if (timer0AllChannels) {
                routes += InterruptRoute(sink, 41..56)
            } else {
                if (ch14TimerEnabled) routes += InterruptRoute(sink, CH14_GIC_IRQ..CH14_GIC_IRQ)
                for (timer in oneshotTimers) {
                    if (timer.enabled) routes += InterruptRoute(sink, timer.irq..timer.irq)
                }
            }
            return routes
        }
        private class Channel {
            val registers = LongArray(14)
            var deadline: Long? = null
            var submittedAt: Long? = null
            var fromFlash = false
            var spi0: Spi0Dma? = null
            var spi0Offset = 0L
            var spi2: Spi2Dma? = null
            var chainedRing = false
            var softwareChain = false
            var ringEnd = 0L
            var ringChunkBytes = 0L
            var toPeer = false
            var destinationBinding:DestinationRoutes.Binding? = null
            var audioRxPeerName: String? = null
            var audioRxPeer: DmaSourcePeer? = null
            var pending = false
            var completed = 0L
        }
        private data class ValidatedChannel(
            val registers: LongArray,
            val deadline: Long?,
            val submittedAt: Long?,
            val fromFlash: Boolean,
            val toPeer: Boolean,
            val destinationBinding: DestinationRoutes.Binding?,
            val audioRxPeerName: String? = null,
            val audioRxPeer: DmaSourcePeer? = null,
            val pending: Boolean,
            val completed: Long,
            val spi0: Spi0Dma?,
            val spi0Offset: Long = 0L,
            val spi2: Spi2Dma?,
            val chainedRing: Boolean = false,
            val softwareChain: Boolean = false,
            val ringEnd: Long = 0L,
            val ringChunkBytes: Long = 0L,
        )
        private var closed=false
        private val channels = Array(16) { Channel() }
        private val mux = LongArray(8)
        private val muxChanged = LongArray(16)
        private val requests = LongArray(32)
        private val events = ArrayDeque<Map<String, Any>>()
        private var eventCount = 0L
        private val buffer = ByteArray(65536)
        private val verification = ByteArray(65536)
        private val offsets = setOf(0, 0x10, 0x14, 0x18, 0x1c, 0x20, 0x24, 0x28, 0x2c, 0x30, 0x34)
        private val transferDigest = MessageDigest.getInstance("SHA-256")
        private val hexFormat = java.util.HexFormat.of()
        private val interruptPortName = values.getValue("interruptPort") as String
        private val irqBase = (values.getValue("irqBase") as Number).toInt()
        private val irqSink by lazy {
            if (interruptPortName.isEmpty()) null else context.ports.find(interruptPortName, InterruptSink::class.java) ?: error("DMA IRQ sink missing")
        }
        private val memoryPort by lazy {
            context.ports.find(values.getValue("memoryPort") as String, DmaMemory::class.java) ?: error("DMA memory peer missing")
        }
        private val flashPeer by lazy {
            val name = values.getValue("flashPeerPort") as String
            if (name.isEmpty()) null else context.ports.find(name, DmaSourcePeer::class.java) ?: error("DMA flash peer missing")
        }
        private val spi0FifoPort by lazy {
            context.ports.find(Spi0Dma.PORT, SpiDmaFifo::class.java) ?: error("SPI0 DMA FIFO port missing")
        }
        private val spi2FifoPort by lazy {
            context.ports.find(Spi2Dma.PORT, SpiDmaFifo::class.java) ?: error("SPI2 DMA FIFO port missing")
        }
        private var syncedPendingMask = -1
        private fun memory() = memoryPort
        private fun auditTransfersEnabled(): Boolean = (values["auditTransfers"] as? Boolean) ?: true
        private fun softwareEnabled() = values.getValue("softwareProfile") == "software-request44-experimental-v1"
        private val spi0Enabled = config["spi0DmaProfile"] == Spi0Dma.PROFILE
        private val spi2Enabled = config["spi2DmaProfile"] == Spi2Dma.PROFILE
        private val chainedRingEnabled = config["chainedRingProfile"] == CHAINED_RING_PROFILE
        private val audioRxEnabled = config["audioRxSourceProfile"] == AUDIO_RX_SOURCE_PROFILE
        private val i2s0RxPeer by lazy { context.ports.find("soc-isp-setup.i2s0-dma-rx", DmaSourcePeer::class.java) }
        private val i2s1RxPeer by lazy { context.ports.find("soc-isp-setup.i2s1-dma-rx", DmaSourcePeer::class.java) }
        private fun audioRxPeerByName(name: String): DmaSourcePeer? = when (name) {
            "soc-isp-setup.i2s0-dma-rx" -> i2s0RxPeer
            "soc-isp-setup.i2s1-dma-rx" -> i2s1RxPeer
            else -> null
        }
        private fun audioRxPeerFor(source: Long, count: Long): Pair<String, DmaSourcePeer>? {
            if (!audioRxEnabled) return null
            i2s0RxPeer?.takeIf { it.overlaps(source, count) }?.let { return "soc-isp-setup.i2s0-dma-rx" to it }
            i2s1RxPeer?.takeIf { it.overlaps(source, count) }?.let { return "soc-isp-setup.i2s1-dma-rx" to it }
            return null
        }
        private fun transferDuration(count: Long, route: Int, bytesPerFrame: Int = 4): Long {
            val base = 16L + (count + 15L) / 16L
            if (route !in AUDIO_DMA_REQUESTS) return base
            val bpf = bytesPerFrame.coerceAtLeast(4).toLong()
            val frames = maxOf(1L, (count + bpf - 1L) / bpf)
            return maxOf(base, frames * AUDIO_TICKS_PER_FRAME)
        }
        /**
         * Phase-locks periodic I2S audio DMA beats to the scheduled [deadline] so positive
         * observation jitter (`tick - deadline`) never accumulates into 48 kHz sample-rate drift.
         * If execution stalled for longer than a full beat (`tick - deadline > duration`), re-anchors
         * cleanly to [tick] to avoid a burst catch-up storm.
         */
        private fun nextPhaseLockedDeadline(deadline: Long, tick: Long, duration: Long): Long {
            val anchor = if (tick >= deadline && tick - deadline <= duration) deadline else tick
            return Math.addExact(anchor, duration)
        }
        private class OneshotTimer(
            val channel: Int,
            val irq: Int,
            val controlPort: String,
            val paramsPort: String,
            val settingKey: String?,
            val profileValue: String,
            val prefix: String,
            val configuredValue: String,
            val enabled: Boolean,
        ) {
            val snapshotKeys: Set<String> = timerSnapshotKeys(prefix)
            var controlWord: Long = 0L
            var controlWritten: Boolean = false
            val paramWords: LongArray = LongArray(4)
            var deadline: Long? = null
            var expirations: Long = 0L
        }
        private val ch4Programming = (config["channel4Programming"] as? String).orEmpty()
        private val ch7Programming = (config["channel7Programming"] as? String).orEmpty()
        private val ch11Programming = (config["channel11Programming"] as? String).orEmpty()
        private val ch14TimerEnabled = config["channel14TimerProfile"] == CH14_TIMER_PROFILE
        private val timer0AllChannelsConfig = (config["timer0AllChannelsProfile"] as? String).orEmpty()
        private val timer0AllChannels = timer0AllChannelsConfig == TIMER0_ALL_CHANNELS_PROFILE
        private val oneshotTimers: List<OneshotTimer> = listOf(0, 1, 2, 3, 5, 6, 8, 9, 10, 12, 13, 14, 15).map { ch ->
            val settingKey = when (ch) {
                0 -> "channel0Programming"
                8 -> "channel8Programming"
                10 -> "channel10Programming"
                14 -> "channel14Programming"
                else -> null
            }
            val profileVal = "oneshot-irq${41 + ch}-v1"
            val cfgVal = if (settingKey != null) (config[settingKey] as? String).orEmpty() else ""
            OneshotTimer(
                channel = ch,
                irq = 41 + ch,
                controlPort = "soc-dma.ch$ch-control",
                paramsPort = "soc-dma.ch$ch-params",
                settingKey = settingKey,
                profileValue = profileVal,
                prefix = "ch$ch",
                configuredValue = cfgVal,
                enabled = (cfgVal == profileVal || timer0AllChannels) && !(ch == 14 && ch14TimerEnabled),
            )
        }
        private val anyOneshotTimerEnabled: Boolean = oneshotTimers.any { it.enabled }
        private val anyProgrammingEnabled = ch4Programming.isNotEmpty() || ch7Programming.isNotEmpty() || ch11Programming.isNotEmpty() || anyOneshotTimerEnabled || timer0AllChannels
        private var ch4ControlWord = 0L
        private var ch4ControlWritten = false
        private val ch4ParamWords = LongArray(4)
        private var ch4Deadline: Long? = null
        private var ch4Expirations = 0L
        private var ch7EnableWord = 0L
        private var ch7ControlWord = 0L
        private val ch7ParamWords = LongArray(4)
        private var ch7Deadline: Long? = null
        private var ch7Expirations = 0L
        private var ch11ControlWord = 0L
        private var ch11ControlWritten = false
        private val ch11ParamWords = LongArray(4)
        private var ch11Deadline: Long? = null
        private var ch11Expirations = 0L
        private var ch14ControlWord = 0L
        private val ch14ParamWords = LongArray(4)
        private var ch14Running = false
        private var ch14Deadline: Long? = null
        private var ch14FireCount = 0L
        private fun spi0Fifo() = spi0FifoPort
        private fun spi2Fifo() = spi2FifoPort
        private fun muxRequest(index: Int, words: LongArray = mux) = (words[index / 2] ushr (index % 2 * 8)) and 63L
        private fun peer(): DmaSourcePeer? = flashPeer
        private fun transmitName() = values["transmitPeerPort"] as? String ?: ""
        private fun transmit(): DmaDestinationPeer? { val name=transmitName();return if(name.isEmpty())null else context.ports.find(name,DmaDestinationPeer::class.java)?:error("DMA transmit peer missing") }
        private val routesFile=(config["destinationPeersFile"]?:"").also{require(it is String){"DMA destination file must be text"}} as String
        private val destinationRoutes=if(routesFile.isEmpty())null else DestinationRoutes(context,transmitName(),context.readData(routesFile))
        private fun destinationBindingFor(address:Long):DestinationRoutes.Binding? = destinationRoutes?.select(address) ?: if(destinationRoutes!=null)null else transmit()?.takeIf{it.matches(address)}?.let{DestinationRoutes.Binding(transmitName(),it)}
        private data class Submission(val deadline:Long?,val fromFlash:Boolean=false,val toPeer:Boolean=false,val binding:DestinationRoutes.Binding?=null,val audioRxPeerName:String?=null,val audioRxPeer:DmaSourcePeer?=null,val spi0:Spi0Dma?=null,val spi2:Spi2Dma?=null,val chainedRing:Boolean=false,val softwareChain:Boolean=false,val ringEnd:Long=0L,val ringChunkBytes:Long=0L)
        private fun currentPendingMask(): Int {
            var mask = 0
            for (i in channels.indices) if (channels[i].pending) mask = mask or (1 shl i)
            return mask
        }
        private fun sync() {
            val mask = currentPendingMask()
            val prev = syncedPendingMask
            if (mask == prev) return
            val sink = irqSink
            if (sink != null) {
                for (i in channels.indices) {
                    val bit = 1 shl i
                    if (prev < 0 || ((mask xor prev) and bit) != 0) {
                        sink.setLine(irqBase + i, (mask and bit) != 0)
                    }
                }
            }
            syncedPendingMask = mask
        }
        private fun read(ram: DmaMemory, address: Long, bytes: ByteArray, size: Int) {
            val view = ByteBuffer.wrap(bytes, 0, size); ram.read(address, view); check(!view.hasRemaining()) { "DMA memory peer short read" }
        }
        private fun write(ram: DmaMemory, address: Long, bytes: ByteArray, size: Int) {
            val view = ByteBuffer.wrap(bytes, 0, size); ram.write(address, view); check(!view.hasRemaining()) { "DMA memory peer short write" }
        }
        /**
         * Resolves a channel's effective 34-bit AXI physical RAM address from its low-32-bit register
         * and the per-channel `(srcHi, dstHi)` upper-address bank at `0xFFF4A800..0xFFF4A87F`
         * (`soc-dma.requests`: `requests[ch * 2]` = source PA[33:32], `requests[ch * 2 + 1]` = dest PA[33:32];
         * verified from DAT `section4.bin` `0x011e5a1c`, `0x011e5a5c`, `0x011e46ac..0x011e46cc`,
         * `0x011e47ac..0x011e47cc`). Falls back to [low] on 32-bit-only memory harnesses or when
         * legacy snapshot tests write non-selector sentinel words (`> 3`).
         */
        private fun resolveRamAddress(ram: DmaMemory, channelIndex: Int, low: Long, size: Long, write: Boolean): Long {
            val hiWord = requests[channelIndex * 2 + (if (write) 1 else 0)]
            if (hiWord !in 1L..3L) {
                ram.check(low, size, write)
                return low
            }
            val full = (hiWord shl 32) or low
            return try {
                ram.check(full, size, write)
                full
            } catch (highFailure: IllegalArgumentException) {
                try {
                    ram.check(low, size, write)
                    low
                } catch (_: IllegalArgumentException) {
                    throw highFailure
                }
            }
        }
        private fun advanceCh14Timer(tick: Long) {
            if (!ch14TimerEnabled || !ch14Running) return
            val deadline = ch14Deadline ?: return
            if (tick < deadline) return
            val periodic = (ch14ControlWord and 0x10L) != 0L
            if (periodic) {
                val period = maxOf(1L, ch14ParamWords[0])
                val elapsed = (tick - deadline) / period + 1L
                ch14Deadline = Math.addExact(deadline, Math.multiplyExact(elapsed, period))
            } else {
                ch14Running = false
                ch14Deadline = null
                ch7EnableWord = ch7EnableWord and 0x4000L.inv()
                ch7EnableMirror?.writeSlot(0, ch7EnableWord.toInt())
            }
            ch14FireCount++
            irqSink?.let { sink ->
                sink.setLine(CH14_GIC_IRQ, true)
                sink.setLine(CH14_GIC_IRQ, false)
            }
        }
        private fun computeNextDeadline(): Long? {
            var min: Long? = null
            for (timer in oneshotTimers) {
                val d = if (timer.enabled) timer.deadline else null
                if (d != null && (min == null || d < min)) min = d
            }
            if (timer0AllChannels) {
                ch4Deadline?.let { d -> if (min == null || d < min) min = d }
                ch7Deadline?.let { d -> if (min == null || d < min) min = d }
                ch11Deadline?.let { d -> if (min == null || d < min) min = d }
            }
            for (c in channels) {
                val d = c.deadline ?: continue
                if (min == null || d < min) min = d
            }
            if (ch14TimerEnabled && ch14Running) {
                val d = ch14Deadline
                if (d != null && (min == null || d < min)) min = d
            }
            return min
        }
        init {
            require(config["profile"] == "standalone-experimental-v1")
            require(!config.containsKey("transmitPeerPort") || config["transmitPeerPort"] is String){"DMA transmit peer setting must be text"}
            context.ports.register(id, "$id.registers", MmioDevice::class.java, object : TickAwareMmioDevice {
                override val clockOrder = 10
                override fun nextDeadline(): Long? { check(!closed) { "DMA closed" }; return computeNextDeadline() }
                override fun nextInterestedTick(): Long {
                    check(!closed) { "DMA closed" }
                    if (currentPendingMask() != syncedPendingMask) return 0L
                    var min = Long.MAX_VALUE
                    for (timer in oneshotTimers) {
                        if (timer.enabled) timer.deadline?.let { d -> if (d < min) min = d }
                    }
                    if (timer0AllChannels) {
                        ch4Deadline?.let { d -> if (d < min) min = d }
                        ch7Deadline?.let { d -> if (d < min) min = d }
                        ch11Deadline?.let { d -> if (d < min) min = d }
                    }
                    for (c in channels) {
                        val d = c.deadline
                        if (d != null) {
                            if (d < min) min = d
                        } else if (c.spi0 != null || c.spi2 != null || ((c.fromFlash || c.toPeer || c.audioRxPeer != null) && c.submittedAt != null)) {
                            return 0L
                        }
                    }
                    if (ch14TimerEnabled && ch14Running) {
                        val d = ch14Deadline
                        if (d != null && d < min) min = d
                    }
                    return min
                }
                override fun readMayMutateSchedule(): Boolean = false
                override fun read(offset: Long, size: Int) = access(offset, size, false, 0)
                override fun write(offset: Long, size: Int, value: Long) { access(offset, size, true, value) }
                override fun beforeInstruction(tick: Long) {
                    advance(tick); sync()
                    check(currentPendingMask() == 0 || interruptPortName.isNotEmpty()) { "DMA completion IRQ pending: JVM GIC route not qualified" }
                }
            })
            context.ports.register(id, "$id.mux", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int): Long { word(offset, size, 0x20); return mux[(offset / 4).toInt()] }
                override fun write(offset: Long, size: Int, value: Long) {
                    word(offset, size, 0x20); require(value in 0..0xffffffffL && value and 0x01013f3fL.inv() == 0L)
                    val index = (offset / 4).toInt(); var old = mux[index]
                    for (lane in 0..1) {
                        val shift = lane * 8; val strobe = if (lane == 0) 0x10000L else 0x1000000L
                        if (value and strobe != 0L && (old xor value) and (63L shl shift) != 0L) {
                            check(channels[index*2+lane].spi0 == null) { "DMA SPI0 route change while armed" }
                            check(channels[index*2+lane].spi2 == null) { "DMA SPI2 route change while armed" }
                            check(if(channels[index*2+lane].toPeer && channels[index*2+lane].submittedAt!=null)false else (peer() == null && !softwareEnabled()) || channels[index*2+lane].deadline==null) { "DMA route change after transfer started" }
                        }
                    }
                    for (lane in 0..1) {
                        val shift = lane * 8; val strobe = if (lane == 0) 0x10000L else 0x1000000L
                        if (value and strobe != 0L && (old xor value) and (63L shl shift) != 0L) muxChanged[index * 2 + lane] = context.clock.ticks()
                    }
                    if (value and 0x10000 != 0L) old = (old and 63L.inv()) or (value and 63)
                    if (value and 0x1000000 != 0L) old = (old and 0x3f00L.inv()) or (value and 0x3f00)
                    mux[index] = old
                    muxMirror?.writeSlot(index, old.toInt())
                }
            })
            context.ports.register(id, "$id.requests", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int): Long { word(offset, size, 0x80); return requests[(offset / 4).toInt()] }
                override fun write(offset: Long, size: Int, value: Long) {
                    word(offset, size, 0x80); require(value in 0..0xffffffffL)
                    val idx = (offset / 4).toInt()
                    requests[idx] = value
                    requestsMirror?.writeSlot(idx, value.toInt())
                }
            })
            if (ch4Programming.isNotEmpty()) {
                context.ports.register(id, CH4_CONTROL_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        require(ch4Programming == CONTROL_READBACK_PROFILE && ch4ControlWritten) {
                            "DMA channel 4 control read requires control-readback-after-write-v1 after a write"
                        }
                        return ch4ControlWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel 4 control write outside u32" }
                        ch4ControlWord = value
                        ch4ControlWritten = true
                    }
                })
                context.ports.register(id, CH4_PARAMS_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 16L)
                        error("DMA channel 4 parameter words are write-only")
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 16L)
                        require(value in 0L..0xffffffffL) { "DMA channel 4 param write outside u32" }
                        ch4ParamWords[(offset / 4L).toInt()] = value
                    }
                })
            }
            if (ch7Programming == RETAINED_ZERO_PROFILE || ch14TimerEnabled || anyOneshotTimerEnabled) {
                context.ports.register(id, CH7_ENABLE_PORT, MmioDevice::class.java, object : TickAwareMmioDevice {
                    override val clockOrder = 12
                    override fun nextDeadline(): Long? {
                        check(!closed) { "DMA closed" }
                        return if (ch14TimerEnabled && ch14Running) ch14Deadline else null
                    }
                    override fun nextInterestedTick(): Long {
                        check(!closed) { "DMA closed" }
                        return if (ch14TimerEnabled && ch14Running) (ch14Deadline ?: Long.MAX_VALUE) else Long.MAX_VALUE
                    }
                    override fun readMayMutateSchedule(): Boolean = false
                    override fun beforeInstruction(tick: Long) {
                        check(!closed) { "DMA closed" }
                        advanceCh14Timer(tick)
                    }
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        return ch7EnableWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel 7 enable write outside u32" }
                        if (ch14TimerEnabled && (value and 0x4000_0000L) != 0L) {
                            if ((value and 0x4000L) != 0L) {
                                val now = context.clock.ticks()
                                require(now >= 0L)
                                val period = maxOf(1L, ch14ParamWords[0])
                                ch14Running = true
                                ch14Deadline = Math.addExact(now, period)
                                ch7EnableWord = (ch7EnableWord and 0x4000_4000L.inv()) or (value and 0x4000_4000L)
                            } else {
                                ch14Running = false
                                ch14Deadline = null
                                ch7EnableWord = (ch7EnableWord and 0x4000_4000L.inv()) or (value and 0x4000_0000L)
                            }
                        } else if (ch14TimerEnabled && ch14Running) {
                            ch7EnableWord = (ch7EnableWord and 0x4000L) or (value and 0x4000L.inv())
                        } else {
                            ch7EnableWord = value
                        }
                        ch7EnableMirror?.writeSlot(0, ch7EnableWord.toInt())
                        for (timer in oneshotTimers) {
                            if (timer.enabled && (value and (1L shl (16 + timer.channel))) != 0L) {
                                timer.deadline = if ((value and (1L shl timer.channel)) != 0L) {
                                    Math.addExact(context.clock.ticks(), timer.paramWords[0].coerceAtLeast(1L))
                                } else {
                                    null
                                }
                            }
                        }
                        if (timer0AllChannels) {
                            if ((value and (1L shl 20)) != 0L) {
                                ch4Deadline = if ((value and (1L shl 4)) != 0L) Math.addExact(context.clock.ticks(), ch4ParamWords[0].coerceAtLeast(1L)) else null
                            }
                            if ((value and (1L shl 23)) != 0L) {
                                ch7Deadline = if ((value and (1L shl 7)) != 0L) Math.addExact(context.clock.ticks(), ch7ParamWords[0].coerceAtLeast(1L)) else null
                            }
                            if ((value and (1L shl 27)) != 0L) {
                                ch11Deadline = if ((value and (1L shl 11)) != 0L) Math.addExact(context.clock.ticks(), ch11ParamWords[0].coerceAtLeast(1L)) else null
                            }
                        }
                    }
                })
            }
            if (ch7Programming == RETAINED_ZERO_PROFILE) {
                context.ports.register(id, CH7_CONTROL_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        return ch7ControlWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel 7 control write outside u32" }
                        ch7ControlWord = value
                    }
                })
                context.ports.register(id, CH7_PARAMS_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 16L)
                        return ch7ParamWords[(offset / 4L).toInt()]
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 16L)
                        require(value in 0L..0xffffffffL) { "DMA channel 7 param write outside u32" }
                        ch7ParamWords[(offset / 4L).toInt()] = value
                    }
                })
            }
            if (ch14TimerEnabled) {
                context.ports.register(id, CH14_CONTROL_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        return ch14ControlWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel 14 control write outside u32" }
                        ch14ControlWord = value
                    }
                })
                context.ports.register(id, CH14_PARAMS_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 16L)
                        return ch14ParamWords[(offset / 4L).toInt()]
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 16L)
                        require(value in 0L..0xffffffffL) { "DMA channel 14 param write outside u32" }
                        ch14ParamWords[(offset / 4L).toInt()] = value
                    }
                })
            }
            if (ch11Programming.isNotEmpty()) {
                context.ports.register(id, CH11_CONTROL_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        require(ch11Programming == CONTROL_READBACK_PROFILE && ch11ControlWritten) {
                            "DMA channel 11 control read requires control-readback-after-write-v1 after a write"
                        }
                        return ch11ControlWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel 11 control write outside u32" }
                        ch11ControlWord = value
                        ch11ControlWritten = true
                    }
                })
                context.ports.register(id, CH11_PARAMS_PORT, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 16L)
                        error("DMA channel 11 parameter words are write-only")
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 16L)
                        require(value in 0L..0xffffffffL) { "DMA channel 11 param write outside u32" }
                        ch11ParamWords[(offset / 4L).toInt()] = value
                    }
                })
            }
            for (timer in oneshotTimers) {
                if (!timer.enabled) continue
                context.ports.register(id, timer.controlPort, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 4L)
                        return timer.controlWord
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 4L)
                        require(value in 0L..0xffffffffL) { "DMA channel ${timer.channel} control write outside u32" }
                        timer.controlWord = value
                        timer.controlWritten = true
                        timerControlMirrors[timer.channel]?.writeSlot(0, value.toInt())
                    }
                })
                context.ports.register(id, timer.paramsPort, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 12
                    override fun read(offset: Long, size: Int): Long {
                        word(offset, size, 16L)
                        error("DMA channel ${timer.channel} parameter words are write-only")
                    }
                    override fun write(offset: Long, size: Int, value: Long) {
                        word(offset, size, 16L)
                        require(value in 0L..0xffffffffL) { "DMA channel ${timer.channel} param write outside u32" }
                        timer.paramWords[(offset / 4L).toInt()] = value
                    }
                })
            }
        }
        private fun word(offset: Long, size: Int, extent: Long) { check(!closed){"DMA closed"};require(size == 4 && offset >= 0 && offset < extent && offset % 4 == 0L) { "DMA unmodeled register/width" } }
        private fun access(offset: Long, size: Int, write: Boolean, value: Long): Long {
            word(offset, size, 0x10000)
            val register = (offset and 0xfffL).toInt()
            require(register == 0 || (register in 0x10..0x34 && (register and 3) == 0)) { "DMA reserved register" }
            val channelIndex = (offset ushr 12).toInt()
            val channel = channels[channelIndex]; val r = channel.registers; val index = register ushr 2
            if (!write) return r[index]
            require(value in 0..0xffffffffL)
            when (register) {
                0x28 -> when {
                    value and 0x10000001 == 0x10000001L -> {
                        val submission = validate(channel, value, channelIndex)
                        channel.spi0 = submission.spi0
                        channel.spi0Offset = 0L
                        channel.spi2 = submission.spi2
                        channel.chainedRing = submission.chainedRing
                        channel.softwareChain = submission.softwareChain
                        channel.ringEnd = submission.ringEnd
                        channel.ringChunkBytes = submission.ringChunkBytes
                        channel.audioRxPeerName = submission.audioRxPeerName
                        channel.audioRxPeer = submission.audioRxPeer
                        r[index] = value; r[0x30 / 4] = 0x10000; channel.deadline = submission.deadline; channel.fromFlash = submission.fromFlash; channel.toPeer = submission.toPeer; channel.destinationBinding=submission.binding; channel.submittedAt = context.clock.ticks()
                        syncChannelRegMirror(channelIndex, 0x28, value)
                        syncChannelRegMirror(channelIndex, 0x30, 0x10000L)
                    }
                    value == 0L || value == 1L || channel.chainedRing || (peer() != null && (value == 0x00201011L || value == 0x00200011L)) || (softwareEnabled() && value == 0x01100001L) || ((channel.spi0 != null || channel.spi2 != null || audioRxEnabled || transmit() != null||destinationRoutes!=null) && value == 0x00201011L) -> {
                        channel.spi0 = null
                        channel.spi0Offset = 0L
                        channel.spi2 = null
                        channel.chainedRing = false
                        channel.softwareChain = false
                        channel.ringEnd = 0L
                        channel.ringChunkBytes = 0L
                        channel.audioRxPeer?.release(channelIndex)
                        channel.audioRxPeer = null
                        channel.audioRxPeerName = null
                        peer()?.release(channelIndex); if(channel.toPeer)channel.destinationBinding?.peer?.release(channelIndex); channel.destinationBinding=null; r[index] = value; r[0x30 / 4] = r[0x30 / 4] and 0x10000L.inv(); channel.deadline = null; channel.submittedAt = null
                        syncChannelRegMirror(channelIndex, 0x28, value)
                        syncChannelRegMirror(channelIndex, 0x30, r[0x30 / 4])
                    }
                    else -> error("unsupported standalone DMA control transition")
                }
                0x30 -> { r[index] = 0; channel.pending = false; syncChannelRegMirror(channelIndex, 0x30, 0L) }
                else -> { check(channel.submittedAt == null) { "DMA reconfiguration while active" }; r[index] = value; syncChannelRegMirror(channelIndex, register, value) }
            }
            // Sample source levels at beforeInstruction, consistently with completion events.
            return 0
        }
        private fun validate(channel: Channel, control: Long, channelIndex: Int): Submission {
            check(channel.submittedAt == null) { "DMA start while busy" }
            val r = channel.registers; val count = r[4] + 1; val source = r[5]; val destination = r[6]
            val flash = peer()
            val binding = destinationBindingFor(destination)
            if (chainedRingEnabled && r[9] and 1L != 0L && count in 4L..0x08000000L) {
                val now = context.clock.ticks(); require(now >= 0L)
                // Case 1: Chained TX ring from RAM to a registered peripheral destination peer (e.g. I2S0/I2S1 TX FIFO).
                if (binding != null && source < 0xfff00000L && count % 4L == 0L && source % 4L == 0L) {
                    val step = minOf(AUDIO_RING_STEP_BYTES, count)
                    val route = ((mux[channelIndex / 2] ushr ((channelIndex % 2) * 8)) and 63).toInt()
                    val beat = if (route in AUDIO_DMA_REQUESTS) transferDuration(step, route, maxOf(8, binding.peer.pacedBytesPerFrame())) else CHAINED_RING_BEAT_TICKS
                    return Submission(
                        deadline = Math.addExact(now, beat),
                        toPeer = true,
                        binding = binding,
                        chainedRing = true,
                        ringEnd = source + count,
                        ringChunkBytes = count,
                    )
                }
                // Case 2: Chained RX ring from a registered audio RX source peer (I2S0/I2S1 RX FIFO -> RAM).
                val rxPair = if (source >= 0xfff00000L && count % 4L == 0L) audioRxPeerFor(source, count) else null
                if (rxPair != null) {
                    val step = minOf(AUDIO_RING_STEP_BYTES, count)
                    val route = ((mux[channelIndex / 2] ushr ((channelIndex % 2) * 8)) and 63).toInt()
                    val beat = if (route in AUDIO_DMA_REQUESTS) transferDuration(step, route, maxOf(8, rxPair.second.pacedBytesPerFrame())) else CHAINED_RING_BEAT_TICKS
                    return Submission(
                        deadline = Math.addExact(now, beat),
                        audioRxPeerName = rxPair.first,
                        audioRxPeer = rxPair.second,
                        chainedRing = true,
                        ringEnd = destination + count,
                        ringChunkBytes = count,
                    )
                }
                // Case 3: Legacy generic peripheral->RAM chained ring.
                if (source >= 0xfff00000L) {
                    return Submission(
                        deadline = Math.addExact(now, CHAINED_RING_BEAT_TICKS),
                        chainedRing = true,
                        ringEnd = destination + count,
                        ringChunkBytes = count,
                    )
                }
            }
            if (softwareEnabled() && control == 0x11100001L && (r[9] and 1L) != 0L && binding == null && audioRxPeerFor(source, count) == null) {
                val route = (mux[channelIndex / 2] ushr ((channelIndex % 2) * 8)) and 63L
                val firstDescAddr = r[9] and 0xFFFF_FFF0L
                require(route == 44L && firstDescAddr != 0L && (r[7] and 0x30F04L.inv()) == 0L && (r[8] and 0x30F00L.inv()) == 0L) {
                    "unsupported software-chain DMA route/geometry ch=$channelIndex route=$route r7=0x${r[7].toString(16)} r8=0x${r[8].toString(16)} r9=0x${r[9].toString(16)}"
                }
                memory().check(firstDescAddr, 24L, false)
                val now = context.clock.ticks(); require(now >= 0L)
                return Submission(deadline = Math.addExact(now, 1024L), softwareChain = true)
            }
            require(count <= 0x08000000 && r[9] == 0L) { "unsupported DMA count/repeat" }
            if (spi0Enabled && (source == Spi0Dma.DATA || destination == Spi0Dma.DATA)) {
                val direction = requireNotNull(Spi0Dma.select(source, destination))
                require(binding == null && flash?.overlaps(source, count) != true && flash?.overlaps(destination, count) != true)
                direction.checkGeometry(channelIndex, r, control, muxRequest(channelIndex))
                direction.checkRam(memory(), r)
                val ready = direction.ready(spi0Fifo(), count.toInt())
                return Submission(if (ready) Math.addExact(context.clock.ticks(), 17L) else null, spi0 = direction)
            }
            if (spi2Enabled && (source == Spi2Dma.DATA || destination == Spi2Dma.DATA)) {
                val direction = requireNotNull(Spi2Dma.select(source, destination))
                require(binding == null && flash?.overlaps(source, count) != true && flash?.overlaps(destination, count) != true)
                direction.checkGeometry(channelIndex, r, control, muxRequest(channelIndex))
                direction.checkRam(memory(), r)
                val ready = direction.ready(spi2Fifo())
                return Submission(if (ready) Math.addExact(context.clock.ticks(), 17L) else null, spi2 = direction)
            }
            val tx=binding?.peer
            if(tx != null) {
                require(control == 0x10201011L && ((r[7] == 0x20000L && r[8] == 0x20004L) || (r[7] == 0x20F00L && r[8] == 0x20F04L))) {
                    "unsupported transmit DMA control/geometry"
                }
                require(count%4==0L && source%4==0L && destination%4==0L && source+count<=0x1_0000_0000L && flash?.overlaps(source,count)!=true) { "unaligned/wrapped or dual-peer transmit DMA" }
                check(channels.none{it.toPeer&&it.submittedAt!=null&&it.destinationBinding?.peer===tx}) { "DMA transmit request already submitted" }
                val now=context.clock.ticks();require(now>=0);val route=((mux[channelIndex/2] ushr ((channelIndex%2)*8))and 63).toInt()
                resolveRamAddress(memory(),channelIndex,source,count,false);tx.check(destination,count,4,route)
                val duration = transferDuration(count, route, tx.pacedBytesPerFrame()); Math.addExact(now, duration); check(eventCount<Long.MAX_VALUE&&channel.completed<Long.MAX_VALUE) { "DMA transmit counters overflow" }
                val accepted=tx.acceptedAt();val deadline=accepted?.let{Math.addExact(maxOf(now,it,muxChanged[channelIndex]),duration)}
                if(accepted!=null){val claimed=tx.claim(channelIndex,destination,count,4,route);if(claimed!=accepted){tx.release(channelIndex);error("DMA transmit claim changed")}}
                return Submission(deadline,toPeer=true,binding=binding)
            }
            val audioRx = audioRxPeerFor(source, count)
            if (audioRx != null) {
                val (rxName, rxPeer) = audioRx
                val route = ((mux[channelIndex / 2] ushr ((channelIndex % 2) * 8)) and 63).toInt()
                require(control == 0x10201011L && ((r[7] == 0x20F04L && r[8] == 0x20F00L) || (r[7] == 0x20004L && r[8] == 0x20000L))) {
                    "unsupported audio RX DMA control/geometry"
                }
                require(route in AUDIO_DMA_REQUESTS && count % 4L == 0L && destination % 4L == 0L && destination + count <= 0x1_0000_0000L) {
                    "unsupported audio RX DMA route/alignment"
                }
                check(channels.none { it.audioRxPeer === rxPeer && it.submittedAt != null }) { "DMA audio RX request already submitted" }
                val now = context.clock.ticks(); require(now >= 0L)
                rxPeer.check(source, count); resolveRamAddress(memory(), channelIndex, destination, count, true)
                val duration = transferDuration(count, route, rxPeer.pacedBytesPerFrame()); Math.addExact(now, duration)
                val accepted = rxPeer.acceptedAt()
                val deadline = accepted?.let { Math.addExact(maxOf(now, it, muxChanged[channelIndex]), duration) }
                if (accepted != null) {
                    val claimed = rxPeer.claim(channelIndex, source, count)
                    if (claimed != accepted) {
                        rxPeer.release(channelIndex)
                        error("DMA audio RX claim changed")
                    }
                }
                return Submission(deadline, audioRxPeerName = rxName, audioRxPeer = rxPeer)
            }
            if (flash != null && flash.overlaps(source, count)) {
                require(control in listOf(0x10000001L,0x10201011L,0x10200011L) && ((r[7] == 0x30f04L && r[8] == 0x30f00L) || (r[7] == 0x20f04L && r[8] == 0x20f00L) || (r[7] == 0x20004L && r[8] == 0x20000L))) { "unsupported flash DMA control/geometry" }
                val width = 1L shl ((r[8] ushr 16).toInt() and 3)
                require(count % width == 0L && source % width == 0L && destination % width == 0L && !flash.overlaps(destination, count) && destination + count < 0x1_0000_0000L)
                flash.check(source, count); resolveRamAddress(memory(), channelIndex, destination, count, true)
                Math.addExact(context.clock.ticks(), 16 + (count + 15) / 16)
                return Submission(null,fromFlash=true)
            }
            val software = control == 0x11100001L && softwareEnabled()
            require(control == 0x10000001L || software) { "unsupported standalone DMA start control" }
            if (software) {
                val route = (mux[channelIndex / 2] ushr ((channelIndex % 2) * 8)) and 63L
                val r7Burst = (r[7] ushr 8) and 0xFL
                val r8Burst = (r[8] ushr 8) and 0xFL
                val r7Width = (r[7] ushr 16) and 0x3L
                val r8Width = (r[8] ushr 16) and 0x3L
                val validBurstPair = (r[7] and 0x30F04L.inv()) == 0L &&
                    (r[8] and 0x30F00L.inv()) == 0L &&
                    r7Width == r8Width &&
                    r7Burst == r8Burst
                require(route == 44L && validBurstPair) {
                    "unsupported software-request DMA route/geometry ch=$channelIndex route=$route r7=0x${r[7].toString(16)} r8=0x${r[8].toString(16)} src=0x${source.toString(16)} dst=0x${destination.toString(16)} cnt=$count"
                }
            } else {
                require(r[7] and 0x30004L.inv() == 0L && r[8] and 0x30004L.inv() == 0L) { "unsupported DMA geometry/peripheral peer" }
            }
            require(r[8] and 4 == 0L && flash?.overlaps(destination,count) != true) { "DMA fixed destination or flash write unqualified" }
            val width = 1L shl ((r[7] ushr 16).toInt() and 3); val destinationWidth = 1L shl ((r[8] ushr 16).toInt() and 3)
            val fixed = r[7] and 4 != 0L; val sourceCount = if (fixed) width else count
            // Real Socionext/Milbeaut HDMAC software mem-to-mem DMA (0x11100001, route 44, !fixed)
            // packs/shifts arbitrary byte-aligned sources, destinations, and lengths across 64-bit AXI beats
            // (used directly by GFX100S MOV muxer copying variable-length H.264/H.265 frames into mdat).
            if (!software || fixed) {
                require(count % width == 0L && count % destinationWidth == 0L && source % width == 0L && destination % destinationWidth == 0L) { "unaligned DMA" }
            }
            require(destination + count < 0x1_0000_0000L && (fixed || source + count < 0x1_0000_0000L)) { "DMA address register wrap" }
            val ram = memory()
            val effectiveSource = resolveRamAddress(ram, channelIndex, source, sourceCount, false)
            val effectiveDestination = resolveRamAddress(ram, channelIndex, destination, count, true)
            require(effectiveSource >= effectiveDestination + count || effectiveDestination >= effectiveSource + sourceCount) { "overlapping DMA" }
            val now = context.clock.ticks(); require(now >= 0)
            return Submission(Math.addExact(now, 16 + (count + 15) / 16))
        }
        /** Completes bytes at the explicit deadline; delivery remains a separate strict guard. */
        fun advance(tick: Long) {
            check(!closed){"DMA closed"};require(tick >= 0)
            advanceCh14Timer(tick)
            for (timer in oneshotTimers) {
                if (!timer.enabled) continue
                val due = timer.deadline
                if (due != null && tick >= due) {
                    timer.deadline = if ((timer.controlWord and 0x10L) != 0L) {
                        Math.addExact(tick, timer.paramWords[0].coerceAtLeast(1L))
                    } else {
                        null
                    }
                    timer.expirations++
                    irqSink?.let { sink ->
                        sink.setLine(timer.irq, true)
                        sink.setLine(timer.irq, false)
                    }
                }
            }
            if (timer0AllChannels) {
                ch4Deadline?.let { due ->
                    if (tick >= due) {
                        ch4Deadline = if ((ch4ControlWord and 0x10L) != 0L) Math.addExact(tick, ch4ParamWords[0].coerceAtLeast(1L)) else null
                        ch4Expirations++
                        irqSink?.let { sink -> sink.setLine(45, true); sink.setLine(45, false) }
                    }
                }
                ch7Deadline?.let { due ->
                    if (tick >= due) {
                        ch7Deadline = if ((ch7ControlWord and 0x10L) != 0L) Math.addExact(tick, ch7ParamWords[0].coerceAtLeast(1L)) else null
                        ch7Expirations++
                        irqSink?.let { sink -> sink.setLine(48, true); sink.setLine(48, false) }
                    }
                }
                ch11Deadline?.let { due ->
                    if (tick >= due) {
                        ch11Deadline = if ((ch11ControlWord and 0x10L) != 0L) Math.addExact(tick, ch11ParamWords[0].coerceAtLeast(1L)) else null
                        ch11Expirations++
                        irqSink?.let { sink -> sink.setLine(52, true); sink.setLine(52, false) }
                    }
                }
            }
            var ready = 0
            channels.forEachIndexed { index, channel ->
                channel.spi0?.let { direction ->
                    val remaining = ((channel.registers[4] + 1L) - channel.spi0Offset).toInt()
                    if (channel.deadline == null && direction.ready(spi0Fifo(), remaining)) channel.deadline = Math.addExact(tick, 17L)
                }
                channel.spi2?.let { direction ->
                    if (channel.deadline == null && direction.ready(spi2Fifo())) channel.deadline = Math.addExact(tick, 17L)
                }
                if (channel.fromFlash && channel.submittedAt != null && channel.deadline == null) {
                    val route = (mux[index / 2] ushr ((index % 2) * 8)) and 63
                    val flash = checkNotNull(peer())
                    val accepted = flash.acceptedAt()
                    if (route == 42L && accepted != null) {
                        val at = Math.addExact(maxOf(checkNotNull(channel.submittedAt), accepted, muxChanged[index]), 16 + (channel.registers[4] + 16) / 16)
                        check(flash.claim(index, channel.registers[5], channel.registers[4] + 1) == accepted)
                        channel.deadline = at
                    }
                }
                if(channel.toPeer && channel.submittedAt!=null && channel.deadline==null) {
                    val tx=checkNotNull(channel.destinationBinding).peer;val route=((mux[index/2] ushr ((index%2)*8))and 63).toInt();val count=channel.registers[4]+1
                    tx.check(channel.registers[6],count,4,route);val accepted=tx.acceptedAt()
                    if(accepted!=null){val at=Math.addExact(maxOf(checkNotNull(channel.submittedAt),accepted,muxChanged[index]),transferDuration(count,route,tx.pacedBytesPerFrame()));val claimed=tx.claim(index,channel.registers[6],count,4,route);if(claimed!=accepted){tx.release(index);error("DMA transmit claim changed")};channel.deadline=at}
                }
                if (channel.audioRxPeer != null && channel.submittedAt != null && channel.deadline == null) {
                    val rxPeer = checkNotNull(channel.audioRxPeer)
                    val route = ((mux[index / 2] ushr ((index % 2) * 8)) and 63).toInt()
                    val count = channel.registers[4] + 1
                    if (route in AUDIO_DMA_REQUESTS) {
                        val accepted = rxPeer.acceptedAt()
                        if (accepted != null) {
                            val at = Math.addExact(maxOf(checkNotNull(channel.submittedAt), accepted, muxChanged[index]), transferDuration(count, route, rxPeer.pacedBytesPerFrame()))
                            val claimed = rxPeer.claim(index, channel.registers[5], count)
                            if (claimed != accepted) {
                                rxPeer.release(index)
                                error("DMA audio RX claim changed")
                            }
                            channel.deadline = at
                        }
                    }
                }
                val deadline = channel.deadline ?: return@forEachIndexed
                if (tick >= deadline) ready = ready or (1 shl index)
            }
            // Preserve actual deadline order after a delayed observation; index breaks simultaneous ties.
            while (ready != 0) {
                var index = -1; var earliest = Long.MAX_VALUE
                for (candidate in channels.indices) if (ready and (1 shl candidate) != 0) {
                    val at = checkNotNull(channels[candidate].deadline)
                    if (index == -1 || at < earliest) { index = candidate; earliest = at }
                }
                ready = ready and (1 shl index).inv(); complete(index,tick); syncChannelMirror(index)
            }
        }
        private fun complete(index: Int, tick: Long) {
                val channel = channels[index]; val deadline = checkNotNull(channel.deadline)
                val r = channel.registers; val count = r[4] + 1; val source = r[5]; val destination = r[6]
                if (channel.softwareChain) {
                    completeSoftwareChain(index, channel, deadline, tick)
                    return
                }
                if (channel.chainedRing) {
                    val route = ((mux[index / 2] ushr ((index % 2) * 8)) and 63).toInt()
                    val isAudioTxRing = channel.toPeer && channel.destinationBinding != null
                    val isAudioRxRing = channel.audioRxPeer != null
                    if (isAudioTxRing) {
                        val txPeer = checkNotNull(channel.destinationBinding).peer
                        val remaining = (channel.ringEnd - r[5]).coerceAtLeast(0L)
                        val step = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, remaining - (remaining % 4L)))
                        if (step >= 4L && r[5] + step <= channel.ringEnd) {
                            read(memory(), r[5], buffer, step.toInt())
                            if (txPeer.acceptedAt() != null && route in AUDIO_DMA_REQUESTS) {
                                val claimed = txPeer.claim(index, r[6], step, 4, route)
                                if (claimed != null) {
                                    val srcView = ByteBuffer.wrap(buffer, 0, step.toInt())
                                    txPeer.write(index, r[6], 0L, srcView)
                                    txPeer.finish(index, deadline, tick)
                                }
                            }
                        }
                        val nextSrc = r[5] + step
                        if (nextSrc < channel.ringEnd) {
                            r[5] = nextSrc
                            val nextStep = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, channel.ringEnd - nextSrc))
                            channel.deadline = nextPhaseLockedDeadline(deadline, tick, transferDuration(nextStep, route, maxOf(8, txPeer.pacedBytesPerFrame())))
                        } else {
                            val linkWord = r[9]
                            if (linkWord and 1L == 0L) {
                                r[5] = channel.ringEnd
                                channel.chainedRing = false
                                channel.ringEnd = 0L
                                channel.ringChunkBytes = 0L
                                channel.toPeer = false
                                channel.destinationBinding = null
                                finishChannel(channel)
                            } else {
                                val nextDescAddr = linkWord and 0xFFFF_FFF0L
                                val descBytes = ByteArray(24)
                                read(memory(), nextDescAddr, descBytes, 24)
                                // Playback can clear a linked descriptor before its predecessor completes.
                                // A cleared node has no samples or destination, even when the old ring had a length.
                                if (descBytes.all { it == 0.toByte() }) {
                                    r[5] = channel.ringEnd
                                    r[9] = 0L
                                    channel.chainedRing = false
                                    channel.ringEnd = 0L
                                    channel.ringChunkBytes = 0L
                                    channel.toPeer = false
                                    channel.destinationBinding = null
                                    finishChannel(channel)
                                    return
                                }
                                val bb = ByteBuffer.wrap(descBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val w0 = bb.int.toLong() and 0xffffffffL
                                val w1 = bb.int.toLong() and 0xffffffffL
                                val w2 = bb.int.toLong() and 0xffffffffL
                                val w3 = bb.int.toLong() and 0xffffffffL
                                val w4 = bb.int.toLong() and 0xffffffffL
                                val w5 = bb.int.toLong() and 0xffffffffL
                                val chunkLen = if (w0 > 0L) w0 + 1L else channel.ringChunkBytes
                                r[4] = w0; r[5] = w1; r[6] = w2; r[7] = w3; r[8] = w4; r[9] = w5
                                channel.ringChunkBytes = chunkLen
                                channel.ringEnd = w1 + chunkLen
                                val nextBinding = requireNotNull(destinationBindingFor(w2)) {
                                    "Unmapped audio TX descriptor at 0x${nextDescAddr.toString(16)}: " +
                                        listOf(w0, w1, w2, w3, w4, w5).joinToString { it.toString(16) }
                                }
                                channel.destinationBinding = nextBinding
                                val nextStep = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, chunkLen))
                                val nextTxPeer = nextBinding.peer
                                channel.deadline = nextPhaseLockedDeadline(deadline, tick, transferDuration(nextStep, route, maxOf(8, nextTxPeer.pacedBytesPerFrame())))
                            }
                        }
                        return
                    }
                    if (isAudioRxRing) {
                        val rxPeer = checkNotNull(channel.audioRxPeer)
                        val remaining = (channel.ringEnd - r[6]).coerceAtLeast(0L)
                        val step = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, remaining - (remaining % 4L)))
                        if (step >= 4L && r[6] + step <= channel.ringEnd) {
                            if (rxPeer.acceptedAt() != null) {
                                val claimed = rxPeer.claim(index, r[5], step)
                                if (claimed != null) {
                                    val dstView = ByteBuffer.wrap(buffer, 0, step.toInt())
                                    rxPeer.read(index, r[5], dstView)
                                    rxPeer.consume(index)
                                    write(memory(), r[6], buffer, step.toInt())
                                }
                            }
                        }
                        val nextDest = r[6] + step
                        if (nextDest < channel.ringEnd) {
                            r[6] = nextDest
                            val nextStep = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, channel.ringEnd - nextDest))
                            channel.deadline = nextPhaseLockedDeadline(deadline, tick, transferDuration(nextStep, route, maxOf(8, rxPeer.pacedBytesPerFrame())))
                        } else {
                            val linkWord = r[9]
                            if (linkWord and 1L == 0L) {
                                r[6] = channel.ringEnd
                                channel.chainedRing = false
                                channel.ringEnd = 0L
                                channel.ringChunkBytes = 0L
                                channel.audioRxPeer = null
                                channel.audioRxPeerName = null
                                finishChannel(channel)
                            } else {
                                val nextDescAddr = linkWord and 0xFFFF_FFF0L
                                val descBytes = ByteArray(24)
                                read(memory(), nextDescAddr, descBytes, 24)
                                val bb = ByteBuffer.wrap(descBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                val w0 = bb.int.toLong() and 0xffffffffL
                                val w1 = bb.int.toLong() and 0xffffffffL
                                val w2 = bb.int.toLong() and 0xffffffffL
                                val w3 = bb.int.toLong() and 0xffffffffL
                                val w4 = bb.int.toLong() and 0xffffffffL
                                val w5 = bb.int.toLong() and 0xffffffffL
                                val chunkLen = if (w0 > 0L) w0 + 1L else channel.ringChunkBytes
                                r[4] = w0; r[5] = w1; r[6] = w2; r[7] = w3; r[8] = w4; r[9] = w5
                                channel.ringChunkBytes = chunkLen
                                channel.ringEnd = w2 + chunkLen
                                val nextStep = minOf(AUDIO_RING_STEP_BYTES, maxOf(4L, chunkLen))
                                channel.deadline = nextPhaseLockedDeadline(deadline, tick, transferDuration(nextStep, route, maxOf(8, rxPeer.pacedBytesPerFrame())))
                            }
                        }
                        return
                    }
                    val remaining = channel.ringEnd - r[6]
                    val step = minOf(CHAINED_RING_STEP_BYTES, maxOf(4L, remaining))
                    val nextDest = r[6] + step
                    if (nextDest < channel.ringEnd) {
                        r[6] = nextDest
                        channel.deadline = Math.addExact(tick, CHAINED_RING_BEAT_TICKS)
                    } else {
                        val linkWord = r[9]
                        if (linkWord and 1L == 0L) {
                            r[6] = channel.ringEnd
                            channel.chainedRing = false
                            channel.ringEnd = 0L
                            channel.ringChunkBytes = 0L
                            finishChannel(channel)
                        } else {
                            val nextDescAddr = linkWord and 1L.inv()
                            val descBytes = ByteArray(24)
                            read(memory(), nextDescAddr, descBytes, 24)
                            val bb = ByteBuffer.wrap(descBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            val w0 = bb.int.toLong() and 0xffffffffL
                            val w1 = bb.int.toLong() and 0xffffffffL
                            val w2 = bb.int.toLong() and 0xffffffffL
                            val w3 = bb.int.toLong() and 0xffffffffL
                            val w4 = bb.int.toLong() and 0xffffffffL
                            val w5 = bb.int.toLong() and 0xffffffffL
                            val chunkLen = if (w0 > 0L) w0 + 1L else channel.ringChunkBytes
                            r[4] = w0
                            r[5] = w1
                            r[6] = w2
                            r[7] = w3
                            r[8] = w4
                            r[9] = w5
                            channel.ringChunkBytes = chunkLen
                            channel.ringEnd = w2 + chunkLen
                            channel.deadline = Math.addExact(tick, CHAINED_RING_BEAT_TICKS)
                        }
                    }
                    return
                }
                channel.spi0?.let { direction ->
                    check(eventCount < Long.MAX_VALUE && channel.completed < Long.MAX_VALUE) { "SPI0 DMA counters overflow" }
                    val remaining = (count - channel.spi0Offset).toInt()
                    val chunk = direction.beatBytes(spi0Fifo(), remaining)
                    check(chunk > 0) { "SPI0 DMA FIFO request withdrawn" }
                    direction.transferChunk(spi0Fifo(), memory(), r, channel.spi0Offset, chunk)
                    channel.spi0Offset += chunk.toLong()
                    if (channel.spi0Offset < count) {
                        channel.deadline = null
                        return
                    }
                    val details = direction.finishReceipt(memory(), r, auditTransfersEnabled())
                    if (events.size == 256) events.removeFirst()
                    events.addLast(mapOf("channel" to index, "source" to source, "destination" to destination,
                        "size" to count, "completedAt" to deadline, "observedAt" to tick,
                        "sourceFixed" to (direction == Spi0Dma.RX)) + details)
                    eventCount++
                    if (direction == Spi0Dma.RX) r[6] += count else r[5] += count
                    channel.spi0 = null
                    channel.spi0Offset = 0L
                    finishChannel(channel)
                    return
                }
                channel.spi2?.let { direction ->
                    check(eventCount < Long.MAX_VALUE && channel.completed < Long.MAX_VALUE) { "SPI2 DMA counters overflow" }
                    val details = direction.transfer(spi2Fifo(), memory(), r, auditTransfersEnabled())
                    if (events.size == 256) events.removeFirst()
                    events.addLast(mapOf("channel" to index, "source" to source, "destination" to destination,
                        "size" to count, "completedAt" to deadline, "observedAt" to tick,
                        "sourceFixed" to (direction == Spi2Dma.RX)) + details)
                    eventCount++
                    if (direction == Spi2Dma.RX) r[6] += count else r[5] += count
                    channel.spi2 = null
                    finishChannel(channel)
                    return
                }
                channel.audioRxPeer?.let { rxPeer ->
                    val ram = memory()
                    val effectiveDestination = resolveRamAddress(ram, index, destination, count, true)
                    val audit = auditTransfersEnabled()
                    val transferHash = if (audit) transferDigest.apply { reset() } else null
                    var done = 0L
                    while (done < count) {
                        val n = minOf(buffer.size.toLong(), count - done).toInt()
                        val view = ByteBuffer.wrap(buffer, 0, n)
                        rxPeer.read(index, source, view)
                        check(!view.hasRemaining()) { "short audio RX DMA peer" }
                        transferHash?.update(buffer, 0, n)
                        write(ram, effectiveDestination + done, buffer, n)
                        if (audit) {
                            read(ram, effectiveDestination + done, verification, n)
                            check(java.util.Arrays.equals(buffer, 0, n, verification, 0, n)) { "DMA readback differs" }
                        }
                        done += n
                    }
                    val digestHex = if (transferHash != null) hexFormat.formatHex(transferHash.digest()) else UNAUDITED_SHA256
                    rxPeer.consume(index)
                    if (events.size == 256) events.removeFirst()
                    events.addLast(mapOf("channel" to index, "source" to source, "destination" to effectiveDestination, "size" to count,
                        "completedAt" to deadline, "observedAt" to tick, "sourceFixed" to true, "sourceSha256" to digestHex, "destinationSha256" to digestHex))
                    eventCount++
                    r[6] += count
                    channel.audioRxPeer = null
                    channel.audioRxPeerName = null
                    finishChannel(channel)
                    return
                }
                val fixed = r[7] and 4 != 0L; val width = 1 shl ((r[7] ushr 16).toInt() and 3)
                val ram = memory()
                val effectiveSource = if (channel.fromFlash) source else resolveRamAddress(ram, index, source, if (fixed) width.toLong() else count, false)
                val effectiveDestination = if (channel.toPeer) destination else resolveRamAddress(ram, index, destination, count, true)
                val tx=if(channel.toPeer)checkNotNull(channel.destinationBinding).peer else null
                if(tx!=null){val route=((mux[index/2] ushr ((index%2)*8))and 63).toInt();tx.check(destination,count,width,route)}
                val audit = auditTransfersEnabled()
                val directRam = if (!audit && !channel.fromFlash && tx == null) ram as? DirectGuestRamPort else null
                val directDone = if (directRam != null) {
                    if (fixed) directRam.fillPatternDirect(effectiveSource, effectiveDestination, width, count)
                    else directRam.copyWithin(effectiveSource, effectiveDestination, count)
                } else false
                val digestHex: String
                if (directDone) {
                    digestHex = UNAUDITED_SHA256
                } else {
                    val transferHash = if (audit) transferDigest.apply { reset() } else null
                    if (fixed && !channel.fromFlash) {
                        read(ram, effectiveSource, buffer, width)
                        var filled = width
                        while (filled < buffer.size) { val n = minOf(filled, buffer.size - filled); buffer.copyInto(buffer, filled, 0, n); filled += n }
                    }
                    var done = 0L
                    while (done < count) {
                        val n = minOf(buffer.size.toLong(), count - done).toInt()
                        if (channel.fromFlash) {
                            val view = ByteBuffer.wrap(buffer, 0, n); checkNotNull(peer()).read(index, source + done, view); check(!view.hasRemaining()) { "short flash DMA peer" }
                        } else if (!fixed) read(ram, effectiveSource + done, buffer, n)
                        transferHash?.update(buffer, 0, n)
                        if(tx!=null){
                            val data=ByteBuffer.wrap(buffer,0,n);tx.write(index,destination,done,data);check(!data.hasRemaining()){ "short transmit DMA peer write" }
                            if (audit) {
                                val back=ByteBuffer.wrap(verification,0,n);tx.readBack(index,destination,done,back);check(!back.hasRemaining()){ "short transmit DMA peer readback" }
                                check(java.util.Arrays.equals(buffer, 0, n, verification, 0, n)) { "DMA readback differs" }
                            }
                        } else {
                            write(ram, effectiveDestination + done, buffer, n)
                            if (audit) {
                                read(ram, effectiveDestination + done, verification, n)
                                check(java.util.Arrays.equals(buffer, 0, n, verification, 0, n)) { "DMA readback differs" }
                            }
                        }
                        done += n
                    }
                    digestHex = if (transferHash != null) hexFormat.formatHex(transferHash.digest()) else UNAUDITED_SHA256
                }
                if(tx!=null)tx.finish(index,deadline,tick)
                if (events.size == 256) events.removeFirst()
                events.addLast(mapOf("channel" to index, "source" to effectiveSource, "destination" to effectiveDestination, "size" to count,
                    "completedAt" to deadline, "observedAt" to tick, "sourceFixed" to fixed, "sourceSha256" to digestHex, "destinationSha256" to digestHex)+(if(tx==null)emptyMap()else mapOf("destinationFixed" to true,"destinationPeer" to checkNotNull(channel.destinationBinding).name,"request" to ((mux[index/2] ushr ((index%2)*8))and 63))))
                eventCount++
                if (channel.fromFlash) checkNotNull(peer()).consume(index)
                if (!fixed) r[5] += count
                if(tx==null)r[6] += count
                finishChannel(channel)
        }
        private fun completeSoftwareChain(index: Int, channel: Channel, deadline: Long, tick: Long) {
            val r = channel.registers
            val ram = memory()
            val audit = auditTransfersEnabled()
            val directRam = if (!audit) ram as? DirectGuestRamPort else null
            val descBytes = ByteArray(24)
            val bb = ByteBuffer.wrap(descBytes).order(ByteOrder.LITTLE_ENDIAN)
            val transferHash = if (audit) transferDigest.apply { reset() } else null
            var descAddr = r[9] and 0xFFFF_FFF0L
            var firstSrc = r[5]
            var firstDst = r[6]
            var firstFixed = false
            var totalBytes = 0L
            var descCount = 0
            // Coalesced dirty range for batched JIT cache invalidation across LLI scatter-gather rows
            var dirtyStart = -1L
            var dirtyEnd = -1L
            fun flushDirtyRange() {
                if (directRam != null && dirtyStart >= 0L && dirtyEnd > dirtyStart) {
                    directRam.notifyDirectWrite(dirtyStart, dirtyEnd - dirtyStart)
                }
                dirtyStart = -1L
                dirtyEnd = -1L
            }
            fun recordDirtyRange(dstAddr: Long, segLen: Long) {
                val segEnd = dstAddr + segLen
                if (dirtyStart < 0L) {
                    dirtyStart = dstAddr
                    dirtyEnd = segEnd
                } else if (dstAddr <= dirtyEnd + DIRECT_INVALIDATE_COALESCE_GAP && segEnd + DIRECT_INVALIDATE_COALESCE_GAP >= dirtyStart) {
                    if (dstAddr < dirtyStart) dirtyStart = dstAddr
                    if (segEnd > dirtyEnd) dirtyEnd = segEnd
                } else {
                    flushDirtyRange()
                    dirtyStart = dstAddr
                    dirtyEnd = segEnd
                }
            }
            // DAT S4 0x011e5918..0x011e5a08: 6-word (24-byte) hardware LLI descriptors [w0=cnt-1, w1=src, w2=dst, w3=r7, w4=r8, w5=nextLink(bit0=enable)]
            while (descAddr != 0L) {
                require(descCount < 65536) { "Software DMA descriptor chain exceeded max 65536 entries on ch=$index" }
                val w0: Long
                val w1: Long
                val w2: Long
                val w3: Long
                val w4: Long
                val w5: Long
                val descSeg = directRam?.sliceDirect(descAddr, 24L, write = false)
                if (descSeg != null) {
                    w0 = descSeg.get(LITTLE_INT, 0L).toLong() and 0xffffffffL
                    w1 = descSeg.get(LITTLE_INT, 4L).toLong() and 0xffffffffL
                    w2 = descSeg.get(LITTLE_INT, 8L).toLong() and 0xffffffffL
                    w3 = descSeg.get(LITTLE_INT, 12L).toLong() and 0xffffffffL
                    w4 = descSeg.get(LITTLE_INT, 16L).toLong() and 0xffffffffL
                    w5 = descSeg.get(LITTLE_INT, 20L).toLong() and 0xffffffffL
                } else {
                    ram.check(descAddr, 24L, false)
                    read(ram, descAddr, descBytes, 24)
                    w0 = bb.getInt(0).toLong() and 0xffffffffL
                    w1 = bb.getInt(4).toLong() and 0xffffffffL
                    w2 = bb.getInt(8).toLong() and 0xffffffffL
                    w3 = bb.getInt(12).toLong() and 0xffffffffL
                    w4 = bb.getInt(16).toLong() and 0xffffffffL
                    w5 = bb.getInt(20).toLong() and 0xffffffffL
                }
                val segCount = w0 + 1L
                val fixed = (w3 and 4L) != 0L
                val width = 1 shl ((w3 ushr 16).toInt() and 3)
                val segSrc = resolveRamAddress(ram, index, w1, if (fixed) width.toLong() else segCount, false)
                val segDst = resolveRamAddress(ram, index, w2, segCount, true)
                if (descCount == 0) {
                    firstSrc = segSrc
                    firstDst = segDst
                    firstFixed = fixed
                }
                var segmentCopiedDirect = false
                if (directRam != null) {
                    val srcSeg = directRam.sliceDirect(segSrc, if (fixed) width.toLong() else segCount, write = false)
                    val dstSeg = if (srcSeg != null) directRam.sliceDirect(segDst, segCount, write = true) else null
                    if (srcSeg != null && dstSeg != null) {
                        if (!fixed) {
                            MemorySegment.copy(srcSeg, 0L, dstSeg, 0L, segCount)
                            recordDirtyRange(segDst, segCount)
                            segmentCopiedDirect = true
                        } else if (width in 1..8) {
                            if (width == 1) {
                                dstSeg.fill(srcSeg.get(ValueLayout.JAVA_BYTE, 0L))
                            } else {
                                var filled = minOf(width.toLong(), segCount)
                                MemorySegment.copy(srcSeg, 0L, dstSeg, 0L, filled)
                                while (filled < segCount) {
                                    val step = minOf(filled, segCount - filled)
                                    MemorySegment.copy(dstSeg, 0L, dstSeg, filled, step)
                                    filled += step
                                }
                            }
                            recordDirtyRange(segDst, segCount)
                            segmentCopiedDirect = true
                        }
                    }
                }
                if (!segmentCopiedDirect) {
                    if (fixed) {
                        read(ram, segSrc, buffer, width)
                        var filled = width
                        while (filled < buffer.size) {
                            val n = minOf(filled, buffer.size - filled)
                            buffer.copyInto(buffer, filled, 0, n)
                            filled += n
                        }
                    }
                    var done = 0L
                    while (done < segCount) {
                        val n = minOf(buffer.size.toLong(), segCount - done).toInt()
                        if (!fixed) read(ram, segSrc + done, buffer, n)
                        transferHash?.update(buffer, 0, n)
                        write(ram, segDst + done, buffer, n)
                        done += n
                    }
                }
                totalBytes += segCount
                descCount++
                r[4] = w0
                r[5] = if (fixed) w1 else w1 + segCount
                r[6] = w2 + segCount
                r[7] = w3
                r[8] = w4
                r[9] = w5
                if ((w5 and 1L) == 0L) break
                descAddr = w5 and 0xFFFF_FFF0L
            }
            flushDirtyRange()
            val digestHex = if (transferHash != null) hexFormat.formatHex(transferHash.digest()) else UNAUDITED_SHA256
            if (events.size == 256) events.removeFirst()
            events.addLast(
                mapOf(
                    "channel" to index,
                    "source" to firstSrc,
                    "destination" to firstDst,
                    "size" to maxOf(1L, totalBytes),
                    "completedAt" to deadline,
                    "observedAt" to tick,
                    "sourceFixed" to firstFixed,
                    "sourceSha256" to digestHex,
                    "destinationSha256" to digestHex,
                ),
            )
            eventCount++
            channel.softwareChain = false
            finishChannel(channel)
        }
        private fun finishChannel(channel: Channel) {
            val registers = channel.registers
            registers[12] = 1
            registers[10] = registers[10] and 0x10000000L.inv()
            channel.deadline = null
            channel.submittedAt = null
            channel.pending = true
            channel.completed++
        }
        override fun additionalMmioPorts(): List<String> = buildList {
            if (ch4Programming.isNotEmpty()) { add(CH4_CONTROL_PORT); add(CH4_PARAMS_PORT) }
            if (ch7Programming == RETAINED_ZERO_PROFILE || ch14TimerEnabled || anyOneshotTimerEnabled) { add(CH7_ENABLE_PORT) }
            if (ch7Programming == RETAINED_ZERO_PROFILE) { add(CH7_CONTROL_PORT); add(CH7_PARAMS_PORT) }
            if (ch11Programming.isNotEmpty()) { add(CH11_CONTROL_PORT); add(CH11_PARAMS_PORT) }
            if (ch14TimerEnabled) { add(CH14_CONTROL_PORT); add(CH14_PARAMS_PORT) }
            for (timer in oneshotTimers) {
                if (timer.enabled) { add(timer.controlPort); add(timer.paramsPort) }
            }
        }
        override fun start() {check(!closed){"DMA closed"}}
        override fun close() { if(closed)return;try { channels.forEachIndexed { index,c -> if(c.toPeer&&c.submittedAt!=null){c.destinationBinding?.peer?.release(index);c.destinationBinding=null;c.deadline=null;c.submittedAt=null}; if (c.audioRxPeer != null) { c.audioRxPeer?.release(index); c.audioRxPeer = null; c.audioRxPeerName = null; c.deadline = null; c.submittedAt = null } } }finally{closed=true} }
        override fun state() = (if(transmitName().isEmpty())super.state()-"transmitPeerPort"else super.state())
            .let{if(routesFile.isEmpty())it-"destinationPeersFile"else it}
            .let { if (spi0Enabled) it else it - "spi0DmaProfile" }
            .let { if (spi2Enabled) it else it - "spi2DmaProfile" }
            .let { if (chainedRingEnabled) it else it - "chainedRingProfile" }
            .let { if (audioRxEnabled) it else it - "audioRxSourceProfile" }
            .let { if (ch4Programming.isNotEmpty()) it else it - "channel4Programming" }
            .let { if (ch7Programming.isNotEmpty()) it else it - "channel7Programming" }
            .let { if (ch11Programming.isNotEmpty()) it else it - "channel11Programming" }
            .let { if (ch14TimerEnabled) it else it - "channel14TimerProfile" }
            .let { if (timer0AllChannels) it else it - "timer0AllChannelsProfile" }
            .let { if (!auditTransfersEnabled()) it else it - "auditTransfers" }
            .let { map -> oneshotTimers.fold(map) { acc, t -> val k = t.settingKey; if (k != null && t.configuredValue == t.profileValue) acc else if (k != null) acc - k else acc } } +
            (if (anyProgrammingEnabled) mapOf("channelProgramming" to buildMap<String, Any?> {
                put("ch4Control", ch4ControlWord); put("ch4ControlWritten", ch4ControlWritten); put("ch4Params", ch4ParamWords.toList())
                put("ch7Enable", ch7EnableWord); put("ch7Control", ch7ControlWord); put("ch7Params", ch7ParamWords.toList())
                put("ch11Control", ch11ControlWord); put("ch11ControlWritten", ch11ControlWritten); put("ch11Params", ch11ParamWords.toList())
                if (timer0AllChannels) {
                    put("ch4Deadline", ch4Deadline); put("ch4Expirations", ch4Expirations)
                    put("ch7Deadline", ch7Deadline); put("ch7Expirations", ch7Expirations)
                    put("ch11Deadline", ch11Deadline); put("ch11Expirations", ch11Expirations)
                }
                for (t in oneshotTimers) if (t.enabled) {
                    put("${t.prefix}Control", t.controlWord)
                    put("${t.prefix}ControlWritten", t.controlWritten)
                    put("${t.prefix}Params", t.paramWords.toList())
                    put("${t.prefix}Deadline", t.deadline)
                    put("${t.prefix}Expirations", t.expirations)
                }
            }) else emptyMap()) +
            (if (ch14TimerEnabled) mapOf("ch14Timer" to mapOf(
                "control" to ch14ControlWord,
                "params" to ch14ParamWords.toList(),
                "running" to ch14Running,
                "deadline" to ch14Deadline,
                "fireCount" to ch14FireCount,
            )) else emptyMap()) +
            mapOf("resetProfile" to "experimental-zero-registers-v1", "timingProfile" to "functional-16-setup-16-bytes-v1",
            "irqDelivery" to ((values.getValue("interruptPort") as String).isNotEmpty()), "channels" to channels.map { c -> mapOf("registers" to offsets.associate { "0x${it.toString(16)}" to c.registers[it / 4] }, "deadline" to c.deadline, "submittedAt" to c.submittedAt, "irqPending" to c.pending, "completed" to c.completed)+(if(transmitName().isEmpty())emptyMap()else mapOf("toPeer" to c.toPeer)) },
            "mux" to mux.toList(), "requests" to requests.toList(), "events" to events.toList(), "eventCount" to eventCount, "eventLogTruncated" to (eventCount > events.size))

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "DMA closed" }
            val savedChannels = channels.map { channel ->
                linkedMapOf<String, Any?>(
                    "registers" to channel.registers.toList(),
                    "deadline" to channel.deadline,
                    "submittedAt" to channel.submittedAt,
                    "fromFlash" to channel.fromFlash,
                    "toPeer" to channel.toPeer,
                    "destinationPeer" to (if (channel.toPeer && channel.submittedAt != null) checkNotNull(channel.destinationBinding).name else null),
                    "pending" to channel.pending,
                    "completed" to channel.completed,
                ) + (channel.spi0?.let {
                    if (channel.spi0Offset > 0L) mapOf("spi0Fifo" to it.name, "spi0Offset" to channel.spi0Offset)
                    else mapOf("spi0Fifo" to it.name)
                } ?: emptyMap()) +
                    (channel.spi2?.let { mapOf("spi2Fifo" to it.name) } ?: emptyMap()) +
                    (channel.audioRxPeerName?.takeIf { channel.submittedAt != null }?.let { mapOf("audioRxPeer" to it) } ?: emptyMap()) +
                    (if (channel.chainedRing) mapOf("chainedRing" to true, "ringEnd" to channel.ringEnd, "ringChunkBytes" to channel.ringChunkBytes) else emptyMap()) +
                    (if (channel.softwareChain) mapOf("softwareChain" to true) else emptyMap())
            }
            val savedScalars = saveScalarValues()
                .let { if (spi0Enabled) it else it - "spi0DmaProfile" }
                .let { if (spi2Enabled) it else it - "spi2DmaProfile" }
                .let { if (chainedRingEnabled) it else it - "chainedRingProfile" }
                .let { if (audioRxEnabled) it else it - "audioRxSourceProfile" }
                .let { if (ch4Programming.isNotEmpty()) it else it - "channel4Programming" }
                .let { if (ch7Programming.isNotEmpty()) it else it - "channel7Programming" }
                .let { if (ch11Programming.isNotEmpty()) it else it - "channel11Programming" }
                .let { if (ch14TimerEnabled) it else it - "channel14TimerProfile" }
                .let { if (timer0AllChannels) it else it - "timer0AllChannelsProfile" }
                .let { if (!auditTransfersEnabled()) it else it - "auditTransfers" }
                .let { map -> oneshotTimers.fold(map) { acc, t -> val k = t.settingKey; if (k != null && t.configuredValue == t.profileValue) acc else if (k != null) acc - k else acc } }
            return linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to savedScalars,
                "channels" to savedChannels,
                "mux" to mux.toList(),
                "muxChanged" to muxChanged.toList(),
                "requests" to requests.toList(),
                "eventCount" to eventCount,
                "events" to events.map { entry -> LinkedHashMap(entry) },
            ).apply {
                if (anyProgrammingEnabled) {
                    val prog = linkedMapOf<String, Any?>(
                        "ch4Control" to ch4ControlWord,
                        "ch4ControlWritten" to ch4ControlWritten,
                        "ch4Params" to ch4ParamWords.toList(),
                        "ch7Enable" to ch7EnableWord,
                        "ch7Control" to ch7ControlWord,
                        "ch7Params" to ch7ParamWords.toList(),
                        "ch11Control" to ch11ControlWord,
                        "ch11ControlWritten" to ch11ControlWritten,
                        "ch11Params" to ch11ParamWords.toList(),
                    )
                    if (timer0AllChannels) {
                        prog["ch4Deadline"] = ch4Deadline; prog["ch4Expirations"] = ch4Expirations
                        prog["ch7Deadline"] = ch7Deadline; prog["ch7Expirations"] = ch7Expirations
                        prog["ch11Deadline"] = ch11Deadline; prog["ch11Expirations"] = ch11Expirations
                    }
                    for (t in oneshotTimers) if (t.enabled) {
                        prog["${t.prefix}Control"] = t.controlWord
                        prog["${t.prefix}ControlWritten"] = t.controlWritten
                        prog["${t.prefix}Params"] = t.paramWords.toList()
                        prog["${t.prefix}Deadline"] = t.deadline
                        prog["${t.prefix}Expirations"] = t.expirations
                    }
                    put("channelProgramming", prog)
                }
                if (ch14TimerEnabled) {
                    put("ch14Timer", linkedMapOf(
                        "ch14Control" to ch14ControlWord,
                        "ch14Params" to ch14ParamWords.toList(),
                        "ch14Running" to ch14Running,
                        "ch14Deadline" to ch14Deadline,
                        "ch14FireCount" to ch14FireCount,
                    ))
                }
            }
        }

        private class ValidatedTimerState(
            val control: Long = 0L,
            val written: Boolean = false,
            val params: LongArray = LongArray(4),
            val deadline: Long? = null,
            val expirations: Long = 0L,
        )

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "DMA closed" }
            val baseKeys = if (anyProgrammingEnabled) EXTENDED_SNAPSHOT_KEYS else SNAPSHOT_KEYS
            val hasCh14Timer = "ch14Timer" in data
            if (hasCh14Timer) require(ch14TimerEnabled) { "Unexpected DMA snapshot keys" }
            val expectedKeys = if (hasCh14Timer) baseKeys + "ch14Timer" else baseKeys
            require(data.keys == expectedKeys) { "Unexpected DMA snapshot keys" }
            require(requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported DMA snapshot schema" }
            require(data["componentId"] == id) { "DMA snapshot componentId mismatch" }
            val rawScalars = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing DMA scalars map")
            val optionalTimerKeys = oneshotTimers.mapNotNull { it.settingKey }.toSet() + "timer0AllChannelsProfile"
            val requiredScalarKeys = values.keys - setOf("spi0DmaProfile", "spi2DmaProfile", "chainedRingProfile", "audioRxSourceProfile", "channel14TimerProfile", "auditTransfers") - optionalTimerKeys -
                (if (ch4Programming.isEmpty()) setOf("channel4Programming") else emptySet()) -
                (if (ch7Programming.isEmpty()) setOf("channel7Programming") else emptySet()) -
                (if (ch11Programming.isEmpty()) setOf("channel11Programming") else emptySet())
            require(rawScalars.keys.containsAll(requiredScalarKeys) && rawScalars.keys.all { it in values.keys }) {
                "DMA scalar setting keys differ"
            }
            val compatibleScalarKeys = mutableSetOf<String>()
            val savedSpi0 = rawScalars["spi0DmaProfile"] as? String ?: ""
            if (savedSpi0 != (if (spi0Enabled) Spi0Dma.PROFILE else "")) {
                require(savedSpi0.isEmpty() && spi0Enabled) {
                    "Incompatible DMA spi0DmaProfile transition: $savedSpi0 -> ${if (spi0Enabled) Spi0Dma.PROFILE else ""}"
                }
                compatibleScalarKeys += "spi0DmaProfile"
            }
            val savedCh14Timer = rawScalars["channel14TimerProfile"] as? String ?: ""
            if (savedCh14Timer != (if (ch14TimerEnabled) CH14_TIMER_PROFILE else "")) {
                require(savedCh14Timer.isEmpty() && ch14TimerEnabled) {
                    "Incompatible DMA channel14TimerProfile transition: $savedCh14Timer -> ${if (ch14TimerEnabled) CH14_TIMER_PROFILE else ""}"
                }
                compatibleScalarKeys += "channel14TimerProfile"
            }
            val savedCh4 = rawScalars["channel4Programming"] as? String ?: ""
            if (savedCh4 != ch4Programming) {
                require(savedCh4 == WRITE_WORDS_PROFILE && ch4Programming == CONTROL_READBACK_PROFILE) {
                    "Incompatible DMA channel4Programming transition: $savedCh4 -> $ch4Programming"
                }
                compatibleScalarKeys += "channel4Programming"
            }
            val savedCh11 = rawScalars["channel11Programming"] as? String ?: ""
            if (savedCh11 != ch11Programming) {
                require(savedCh11 == WRITE_WORDS_PROFILE && ch11Programming == CONTROL_READBACK_PROFILE) {
                    "Incompatible DMA channel11Programming transition: $savedCh11 -> $ch11Programming"
                }
                compatibleScalarKeys += "channel11Programming"
            }
            val savedTimer0All = rawScalars["timer0AllChannelsProfile"] as? String ?: ""
            if (savedTimer0All != timer0AllChannelsConfig) {
                require(savedTimer0All.isEmpty() && timer0AllChannels) {
                    "Incompatible DMA timer0AllChannelsProfile transition: $savedTimer0All -> $timer0AllChannelsConfig"
                }
                compatibleScalarKeys += "timer0AllChannelsProfile"
            }
            val savedTimerProfiles = oneshotTimers.associateWith { t ->
                val k = t.settingKey
                if (k != null) (rawScalars[k] as? String ?: "") else ""
            }
            for (t in oneshotTimers) {
                val k = t.settingKey ?: continue
                val saved = savedTimerProfiles.getValue(t)
                if (saved != t.configuredValue) {
                    require(saved.isEmpty() && t.configuredValue == t.profileValue) {
                        "Incompatible DMA $k transition: $saved -> ${t.configuredValue}"
                    }
                    compatibleScalarKeys += k
                }
            }
            val nextCh4Control: Long
            val nextCh4Written: Boolean
            val nextCh4Params = LongArray(4)
            var nextCh4Deadline: Long? = null
            var nextCh4Expirations = 0L
            val nextCh7Enable: Long
            val nextCh7Control: Long
            val nextCh7Params = LongArray(4)
            var nextCh7Deadline: Long? = null
            var nextCh7Expirations = 0L
            val nextCh11Control: Long
            val nextCh11Written: Boolean
            val nextCh11Params = LongArray(4)
            var nextCh11Deadline: Long? = null
            var nextCh11Expirations = 0L
            val nextTimerStates = linkedMapOf<OneshotTimer, ValidatedTimerState>()
            if (anyProgrammingEnabled) {
                val prog = data["channelProgramming"] as? Map<*, *>
                    ?: throw IllegalArgumentException("Missing DMA channelProgramming snapshot map")
                val expectedProgKeys = PROGRAMMING_SNAPSHOT_KEYS.toMutableSet()
                val legacyTimerMetaKeys = setOf("ch4Deadline", "ch4Expirations", "ch7Deadline", "ch7Expirations", "ch11Deadline", "ch11Expirations")
                val hasLegacyTimerMeta = legacyTimerMetaKeys.all { it in prog.keys }
                if (hasLegacyTimerMeta) {
                    require(timer0AllChannels) { "Unexpected DMA channelProgramming snapshot keys" }
                    expectedProgKeys += legacyTimerMetaKeys
                } else {
                    require(savedTimer0All != TIMER0_ALL_CHANNELS_PROFILE) { "Unexpected DMA channelProgramming snapshot keys" }
                }
                val timerHasKeys = linkedMapOf<OneshotTimer, Boolean>()
                val savedCh14Own = savedCh14Timer == CH14_TIMER_PROFILE
                for (t in oneshotTimers) {
                    val hasKeys = t.snapshotKeys.all { it in prog.keys }
                    timerHasKeys[t] = hasKeys
                    if (hasKeys) expectedProgKeys += t.snapshotKeys
                    val savedEnabled = (savedTimerProfiles.getValue(t) == t.profileValue || savedTimer0All == TIMER0_ALL_CHANNELS_PROFILE) && !(t.channel == 14 && savedCh14Own)
                    require((!hasKeys || t.enabled) && (!savedEnabled || hasKeys)) {
                        "Unexpected DMA channelProgramming snapshot keys"
                    }
                }
                require(prog.keys == expectedProgKeys) { "Unexpected DMA channelProgramming snapshot keys" }
                fun u32(value: Any?, field: String): Long =
                    requireLong(value, field).also { require(it in 0L..0xffffffffL) { "Out-of-range u32 for $field" } }
                fun params4(raw: Any?, field: String, dest: LongArray) {
                    val list = raw as? List<*> ?: throw IllegalArgumentException("Missing list for $field")
                    require(list.size == 4) { "$field requires 4 entries" }
                    for (i in 0..3) dest[i] = u32(list[i], "$field[$i]")
                }
                nextCh4Control = u32(prog["ch4Control"], "channelProgramming.ch4Control")
                nextCh4Written = requireBoolean(prog["ch4ControlWritten"], "channelProgramming.ch4ControlWritten")
                params4(prog["ch4Params"], "channelProgramming.ch4Params", nextCh4Params)
                nextCh7Enable = u32(prog["ch7Enable"], "channelProgramming.ch7Enable")
                nextCh7Control = u32(prog["ch7Control"], "channelProgramming.ch7Control")
                params4(prog["ch7Params"], "channelProgramming.ch7Params", nextCh7Params)
                nextCh11Control = u32(prog["ch11Control"], "channelProgramming.ch11Control")
                nextCh11Written = requireBoolean(prog["ch11ControlWritten"], "channelProgramming.ch11ControlWritten")
                params4(prog["ch11Params"], "channelProgramming.ch11Params", nextCh11Params)
                if (hasLegacyTimerMeta) {
                    nextCh4Deadline = prog["ch4Deadline"]?.let { requireLong(it, "channelProgramming.ch4Deadline") }
                    nextCh4Expirations = requireLong(prog["ch4Expirations"], "channelProgramming.ch4Expirations")
                    nextCh7Deadline = prog["ch7Deadline"]?.let { requireLong(it, "channelProgramming.ch7Deadline") }
                    nextCh7Expirations = requireLong(prog["ch7Expirations"], "channelProgramming.ch7Expirations")
                    nextCh11Deadline = prog["ch11Deadline"]?.let { requireLong(it, "channelProgramming.ch11Deadline") }
                    nextCh11Expirations = requireLong(prog["ch11Expirations"], "channelProgramming.ch11Expirations")
                    require(nextCh4Expirations >= 0L && nextCh7Expirations >= 0L && nextCh11Expirations >= 0L) { "Negative timer expirations" }
                }
                for (t in oneshotTimers) {
                    if (timerHasKeys.getValue(t)) {
                        val p = LongArray(4)
                        val ctrl = u32(prog["${t.prefix}Control"], "channelProgramming.${t.prefix}Control")
                        val wr = requireBoolean(prog["${t.prefix}ControlWritten"], "channelProgramming.${t.prefix}ControlWritten")
                        params4(prog["${t.prefix}Params"], "channelProgramming.${t.prefix}Params", p)
                        val dl = prog["${t.prefix}Deadline"]?.let { requireLong(it, "channelProgramming.${t.prefix}Deadline") }
                        val exp = requireLong(prog["${t.prefix}Expirations"], "channelProgramming.${t.prefix}Expirations")
                        require(exp >= 0L) { "Negative ${t.prefix}Expirations" }
                        nextTimerStates[t] = ValidatedTimerState(ctrl, wr, p, dl, exp)
                    } else {
                        nextTimerStates[t] = ValidatedTimerState()
                    }
                }
                if (savedCh4 == WRITE_WORDS_PROFILE && ch4Programming == CONTROL_READBACK_PROFILE) {
                    require(nextCh4Written) { "Promoting channel4Programming to readback requires prior write" }
                }
                if (savedCh11 == WRITE_WORDS_PROFILE && ch11Programming == CONTROL_READBACK_PROFILE) {
                    require(nextCh11Written) { "Promoting channel11Programming to readback requires prior write" }
                }
            } else {
                nextCh4Control = 0L; nextCh4Written = false
                nextCh7Enable = 0L; nextCh7Control = 0L
                nextCh11Control = 0L; nextCh11Written = false
                for (t in oneshotTimers) nextTimerStates[t] = ValidatedTimerState()
            }
            val nextCh14Control: Long
            val nextCh14Params = LongArray(4)
            val nextCh14Running: Boolean
            val nextCh14Deadline: Long?
            val nextCh14FireCount: Long
            if (hasCh14Timer) {
                val timer = data["ch14Timer"] as? Map<*, *>
                    ?: throw IllegalArgumentException("Missing DMA ch14Timer snapshot map")
                require(timer.keys == CH14_TIMER_SNAPSHOT_KEYS) { "Unexpected DMA ch14Timer snapshot keys" }
                fun u32Timer(value: Any?, field: String): Long =
                    requireLong(value, field).also { require(it in 0L..0xffffffffL) { "Out-of-range u32 for $field" } }
                nextCh14Control = u32Timer(timer["ch14Control"], "ch14Timer.ch14Control")
                val rawParams = timer["ch14Params"] as? List<*>
                    ?: throw IllegalArgumentException("Missing list for ch14Timer.ch14Params")
                require(rawParams.size == 4) { "ch14Timer.ch14Params requires 4 entries" }
                for (i in 0..3) nextCh14Params[i] = u32Timer(rawParams[i], "ch14Timer.ch14Params[$i]")
                nextCh14Running = requireBoolean(timer["ch14Running"], "ch14Timer.ch14Running")
                nextCh14Deadline = timer["ch14Deadline"]?.let { requireLong(it, "ch14Timer.ch14Deadline") }
                if (nextCh14Running) {
                    require(nextCh14Deadline != null && nextCh14Deadline >= 0L) { "Running ch14Timer requires non-negative deadline" }
                } else {
                    require(nextCh14Deadline == null) { "Stopped ch14Timer cannot carry deadline" }
                }
                nextCh14FireCount = requireLong(timer["ch14FireCount"], "ch14Timer.ch14FireCount").also {
                    require(it >= 0L) { "Negative ch14Timer.ch14FireCount" }
                }
            } else {
                nextCh14Control = 0L
                nextCh14Running = false
                nextCh14Deadline = null
                nextCh14FireCount = 0L
            }
            val previousValues = values
            val validatedValues = try {
                restoreScalarValues(rawScalars, DESCRIPTOR, compatibleScalarKeys)
                values
            } finally {
                values = previousValues
            }

            val rawMux = data["mux"] as? List<*> ?: throw IllegalArgumentException("Missing DMA mux list")
            require(rawMux.size == mux.size) { "DMA mux size mismatch" }
            val nextMux = LongArray(mux.size) { muxIndex ->
                val word = requireLong(rawMux[muxIndex], "mux[$muxIndex]")
                require(word in 0L..0x3f3fL && (word and 0x3f3fL.inv()) == 0L) { "Invalid DMA mux word at $muxIndex" }
                word
            }

            val rawMuxChanged = data["muxChanged"] as? List<*> ?: throw IllegalArgumentException("Missing DMA muxChanged list")
            require(rawMuxChanged.size == muxChanged.size) { "DMA muxChanged size mismatch" }
            val nextMuxChanged = LongArray(muxChanged.size) { channelIndex ->
                requireLong(rawMuxChanged[channelIndex], "muxChanged[$channelIndex]").also { tick ->
                    require(tick >= 0L) { "Negative DMA muxChanged tick at $channelIndex" }
                }
            }

            val rawRequests = data["requests"] as? List<*> ?: throw IllegalArgumentException("Missing DMA requests list")
            require(rawRequests.size == requests.size) { "DMA requests size mismatch" }
            val nextRequests = LongArray(requests.size) { requestIndex ->
                requireLong(rawRequests[requestIndex], "requests[$requestIndex]").also { word ->
                    require(word in 0L..0xffffffffL) { "DMA request word out of range at $requestIndex" }
                }
            }

            val rawChannels = data["channels"] as? List<*> ?: throw IllegalArgumentException("Missing DMA channels list")
            require(rawChannels.size == channels.size) { "DMA channels count mismatch" }
            val claimedDestinationPeers = IdentityHashMap<DmaDestinationPeer, Boolean>()
            val flashConfigured = (validatedValues["flashPeerPort"] as? String ?: "").isNotEmpty()
            val transmitConfigured = (validatedValues["transmitPeerPort"] as? String ?: "").isNotEmpty() || routesFile.isNotEmpty()
            val interruptConfigured = (validatedValues["interruptPort"] as? String ?: "").isNotEmpty()
            val nextChannels = Array(channels.size) { channelIndex ->
                val row = rawChannels[channelIndex] as? Map<*, *> ?: throw IllegalArgumentException("Invalid DMA channel entry $channelIndex")
                val rowKeysWithoutOptional = row.keys - setOf("spi0Fifo", "spi0Offset", "spi2Fifo", "audioRxPeer", "chainedRing", "softwareChain", "ringEnd", "ringChunkBytes")
                require(rowKeysWithoutOptional == CHANNEL_SNAPSHOT_KEYS) { "Unexpected DMA channel keys at $channelIndex" }
                val rawRegisters = row["registers"] as? List<*> ?: throw IllegalArgumentException("Missing registers for DMA channel $channelIndex")
                require(rawRegisters.size == 14) { "DMA channel $channelIndex register count mismatch" }
                val nextRegisters = LongArray(14) { registerIndex ->
                    val word = requireLong(rawRegisters[registerIndex], "channels[$channelIndex].registers[$registerIndex]")
                    if (registerIndex * 4 !in offsets) {
                        require(word == 0L) { "Non-zero reserved DMA register at channel $channelIndex index $registerIndex" }
                    } else if (registerIndex in 5..6) {
                        require(word in 0L..0x1_0000_0000L) { "DMA address register out of range at channel $channelIndex" }
                    } else {
                        require(word in 0L..0xffffffffL) { "DMA register out of range at channel $channelIndex" }
                    }
                    word
                }
                val nextDeadline = row["deadline"]?.let { rawDeadline -> requireLong(rawDeadline, "channels[$channelIndex].deadline") }
                val nextSubmittedAt = row["submittedAt"]?.let { rawSubmitted -> requireLong(rawSubmitted, "channels[$channelIndex].submittedAt") }
                val nextFromFlash = requireBoolean(row["fromFlash"], "channels[$channelIndex].fromFlash")
                val nextToPeer = requireBoolean(row["toPeer"], "channels[$channelIndex].toPeer")
                val rawDestinationPeer = row["destinationPeer"]
                require(rawDestinationPeer == null || rawDestinationPeer is String) { "Invalid destinationPeer at channel $channelIndex" }
                val nextPending = requireBoolean(row["pending"], "channels[$channelIndex].pending")
                val nextCompleted = requireLong(row["completed"], "channels[$channelIndex].completed")
                val spi0 = if ("spi0Fifo" in row) {
                    require(spi0Enabled && rawScalars["spi0DmaProfile"] == Spi0Dma.PROFILE && nextSubmittedAt != null &&
                        !nextFromFlash && !nextToPeer && rawDestinationPeer == null)
                    Spi0Dma.valueOf(row["spi0Fifo"] as String).also {
                        it.checkGeometry(channelIndex, nextRegisters, nextRegisters[10], muxRequest(channelIndex, nextMux))
                        it.checkRam(memory(), nextRegisters)
                        spi0Fifo()
                    }
                } else null
                if (spi0Enabled && nextSubmittedAt != null && Spi0Dma.DATA in listOf(nextRegisters[5], nextRegisters[6])) {
                    require(spi0 != null) { "active SPI0 DMA lacks saved FIFO route" }
                }
                val nextSpi0Offset = if ("spi0Offset" in row) {
                    require(spi0 != null) { "spi0Offset requires active spi0Fifo at channel $channelIndex" }
                    requireLong(row["spi0Offset"], "channels[$channelIndex].spi0Offset").also {
                        require(it in 1L..nextRegisters[4]) { "spi0Offset out of range at channel $channelIndex" }
                    }
                } else 0L
                val spi2 = if ("spi2Fifo" in row) {
                    require(spi2Enabled && rawScalars["spi2DmaProfile"] == Spi2Dma.PROFILE && nextSubmittedAt != null &&
                        !nextFromFlash && !nextToPeer && rawDestinationPeer == null)
                    Spi2Dma.valueOf(row["spi2Fifo"] as String).also {
                        it.checkGeometry(channelIndex, nextRegisters, nextRegisters[10], muxRequest(channelIndex, nextMux))
                        it.checkRam(memory(), nextRegisters)
                        spi2Fifo()
                    }
                } else null
                if (spi2Enabled && nextSubmittedAt != null && Spi2Dma.DATA in listOf(nextRegisters[5], nextRegisters[6])) {
                    require(spi2 != null) { "active SPI2 DMA lacks saved FIFO route" }
                }
                require(nextCompleted >= 0L) { "Negative completed count at channel $channelIndex" }
                require(!(nextFromFlash && nextToPeer)) { "DMA channel $channelIndex cannot target both flash and destination peer" }
                if (nextFromFlash) require(flashConfigured) { "DMA channel $channelIndex has fromFlash without configured flashPeerPort" }
                if (nextToPeer) require(transmitConfigured) { "DMA channel $channelIndex has toPeer without configured destination route" }
                val nextAudioRxPeerName = if ("audioRxPeer" in row) {
                    require(audioRxEnabled && rawScalars["audioRxSourceProfile"] == AUDIO_RX_SOURCE_PROFILE && nextSubmittedAt != null && !nextFromFlash && !nextToPeer)
                    (row["audioRxPeer"] as? String) ?: throw IllegalArgumentException("Invalid audioRxPeer at channel $channelIndex")
                } else null
                val nextAudioRxPeer = nextAudioRxPeerName?.let {
                    audioRxPeerByName(it) ?: throw IllegalArgumentException("DMA audio RX peer missing: $it")
                }
                val resolvedBinding: DestinationRoutes.Binding?
                if (nextSubmittedAt == null) {
                    require(nextDeadline == null && rawDestinationPeer == null) { "Idle DMA channel $channelIndex cannot have deadline or destinationPeer" }
                    require((nextRegisters[10] and 0x10000001L) != 0x10000001L && (nextRegisters[12] and 0x10000L) == 0L) {
                        "Idle DMA channel $channelIndex carries active control/status bits"
                    }
                    resolvedBinding = null
                } else {
                    require(nextSubmittedAt >= 0L) { "Negative submittedAt at channel $channelIndex" }
                    require((nextRegisters[10] and 0x10000001L) == 0x10000001L && nextRegisters[12] == 0x10000L) {
                        "Active DMA channel $channelIndex lacks active control/status bits"
                    }
                    if (nextDeadline != null) {
                        require(nextDeadline >= nextSubmittedAt) { "DMA channel $channelIndex deadline precedes submittedAt" }
                    }
                    if (nextToPeer) {
                        val expectedPeerName = rawDestinationPeer
                            ?: throw IllegalArgumentException("Active peer DMA channel $channelIndex missing destinationPeer")
                        val binding = destinationBindingFor(nextRegisters[6])
                            ?: throw IllegalArgumentException("Unbound destination address on active DMA channel $channelIndex")
                        require(binding.name == expectedPeerName) { "Destination peer name mismatch at channel $channelIndex" }
                        require(claimedDestinationPeers.put(binding.peer, true) == null) {
                            "Multiple active DMA channels claim the same destination peer"
                        }
                        resolvedBinding = binding
                    } else {
                        require(rawDestinationPeer == null) { "Non-peer DMA channel $channelIndex cannot carry destinationPeer" }
                        if (nextFromFlash) {
                            peer() ?: throw IllegalArgumentException("DMA flash peer missing for active channel $channelIndex")
                        } else if (spi0 == null && spi2 == null && nextAudioRxPeer == null) {
                            require(nextDeadline != null) { "Active standalone DMA channel $channelIndex requires deadline" }
                        }
                        resolvedBinding = null
                    }
                }
                val nextChainedRing = if ("chainedRing" in row) {
                    require(chainedRingEnabled && rawScalars["chainedRingProfile"] == CHAINED_RING_PROFILE && nextSubmittedAt != null)
                    requireBoolean(row["chainedRing"], "channels[$channelIndex].chainedRing")
                } else false
                val nextSoftwareChain = if ("softwareChain" in row) {
                    require(softwareEnabled() && nextSubmittedAt != null)
                    requireBoolean(row["softwareChain"], "channels[$channelIndex].softwareChain")
                } else false
                val nextRingEnd = if (nextChainedRing) requireLong(row["ringEnd"], "channels[$channelIndex].ringEnd") else 0L
                val nextRingChunk = if (nextChainedRing) requireLong(row["ringChunkBytes"], "channels[$channelIndex].ringChunkBytes") else 0L
                ValidatedChannel(
                    registers = nextRegisters,
                    deadline = nextDeadline,
                    submittedAt = nextSubmittedAt,
                    fromFlash = nextFromFlash,
                    toPeer = nextToPeer,
                    destinationBinding = resolvedBinding,
                    audioRxPeerName = nextAudioRxPeerName,
                    audioRxPeer = nextAudioRxPeer,
                    pending = nextPending,
                    completed = nextCompleted,
                    spi0 = spi0,
                    spi0Offset = nextSpi0Offset,
                    spi2 = spi2,
                    chainedRing = nextChainedRing,
                    softwareChain = nextSoftwareChain,
                    ringEnd = nextRingEnd,
                    ringChunkBytes = nextRingChunk,
                )
            }

            val nextEventCount = requireLong(data["eventCount"], "eventCount")
            require(nextEventCount >= 0L) { "Negative DMA eventCount" }
            val rawEvents = data["events"] as? List<*> ?: throw IllegalArgumentException("Missing DMA events list")
            val expectedEventsSize = minOf(nextEventCount, 256L).toInt()
            require(rawEvents.size == expectedEventsSize) { "DMA events size inconsistent with eventCount" }
            val nextEvents = rawEvents.mapIndexed { eventIndex, rawEntry ->
                val entry = rawEntry as? Map<*, *> ?: throw IllegalArgumentException("Invalid DMA event at $eventIndex")
                val hasPeerFields = entry.containsKey("destinationPeer")
                val spi0 = if ("spi0Fifo" in entry) {
                    require(spi0Enabled && rawScalars["spi0DmaProfile"] == Spi0Dma.PROFILE)
                    Spi0Dma.valueOf(entry["spi0Fifo"] as String)
                } else null
                val spi2 = if ("spi2Fifo" in entry) {
                    require(spi2Enabled && rawScalars["spi2DmaProfile"] == Spi2Dma.PROFILE)
                    Spi2Dma.valueOf(entry["spi2Fifo"] as String)
                } else null
                require(spi0 == null || spi2 == null) { "DMA event cannot carry both spi0Fifo and spi2Fifo at $eventIndex" }
                val expectedKeys = when {
                    spi0 == Spi0Dma.TX -> BASE_EVENT_KEYS - "destinationSha256" + setOf("spi0Fifo", "request")
                    spi0 == Spi0Dma.RX -> BASE_EVENT_KEYS + setOf("spi0Fifo", "request")
                    spi2 == Spi2Dma.TX -> BASE_EVENT_KEYS - "destinationSha256" + setOf("spi2Fifo", "request")
                    spi2 == Spi2Dma.RX -> BASE_EVENT_KEYS + setOf("spi2Fifo", "request")
                    else -> if (hasPeerFields) PEER_EVENT_KEYS else BASE_EVENT_KEYS
                }
                require(entry.keys == expectedKeys) {
                    "Unexpected DMA event keys at $eventIndex"
                }
                val eventChannel = requireInt(entry["channel"], "events[$eventIndex].channel")
                require(eventChannel in 0..15) { "DMA event channel out of range at $eventIndex" }
                val eventSource = requireLong(entry["source"], "events[$eventIndex].source")
                val eventDestination = requireLong(entry["destination"], "events[$eventIndex].destination")
                val eventSize = requireLong(entry["size"], "events[$eventIndex].size")
                val completedAt = requireLong(entry["completedAt"], "events[$eventIndex].completedAt")
                val observedAt = requireLong(entry["observedAt"], "events[$eventIndex].observedAt")
                require(eventSource >= 0L && eventDestination >= 0L && eventSize > 0L && completedAt >= 0L && observedAt >= completedAt) {
                    "Invalid DMA event geometry/timing at $eventIndex"
                }
                val sourceFixed = requireBoolean(entry["sourceFixed"], "events[$eventIndex].sourceFixed")
                val allowEmptySha = (validatedValues["auditTransfers"] as? Boolean) == false
                val sourceSha256 = requireSha256(entry["sourceSha256"], "events[$eventIndex].sourceSha256", allowEmptySha)
                val normalized = linkedMapOf<String, Any>(
                    "channel" to eventChannel,
                    "source" to eventSource,
                    "destination" to eventDestination,
                    "size" to eventSize,
                    "completedAt" to completedAt,
                    "observedAt" to observedAt,
                    "sourceFixed" to sourceFixed,
                    "sourceSha256" to sourceSha256,
                )
                if (spi0 != Spi0Dma.TX && spi2 != Spi2Dma.TX) normalized["destinationSha256"] = requireSha256(entry["destinationSha256"], "events[$eventIndex].destinationSha256", allowEmptySha)
                if (spi0 != null) {
                    require(eventChannel == spi0.channel && eventSize in 1L..Spi0Dma.MAX_SIZE.toLong() &&
                        Spi0Dma.select(eventSource, eventDestination) == spi0 && sourceFixed == (spi0 == Spi0Dma.RX) &&
                        requireLong(entry["request"], "request") == spi0.request)
                    if (spi0 == Spi0Dma.RX) require(normalized["destinationSha256"] == sourceSha256)
                    normalized["spi0Fifo"] = spi0.name
                    normalized["request"] = spi0.request
                }
                if (spi2 != null) {
                    require(eventChannel == spi2.channel && eventSize == Spi2Dma.SIZE.toLong() &&
                        Spi2Dma.select(eventSource, eventDestination) == spi2 && sourceFixed == (spi2 == Spi2Dma.RX) &&
                        requireLong(entry["request"], "request") == spi2.request)
                    if (spi2 == Spi2Dma.RX) require(normalized["destinationSha256"] == sourceSha256)
                    normalized["spi2Fifo"] = spi2.name
                    normalized["request"] = spi2.request
                }
                if (hasPeerFields) {
                    val destinationFixed = requireBoolean(entry["destinationFixed"], "events[$eventIndex].destinationFixed")
                    require(destinationFixed) { "Peer DMA event must have destinationFixed=true at $eventIndex" }
                    val peerName = entry["destinationPeer"] as? String
                        ?: throw IllegalArgumentException("Missing destinationPeer string at event $eventIndex")
                    require(peerName.isNotEmpty()) { "Empty destinationPeer at event $eventIndex" }
                    val requestLine = requireLong(entry["request"], "events[$eventIndex].request")
                    require(requestLine in 0L..63L) { "DMA event request route out of range at $eventIndex" }
                    normalized["destinationFixed"] = true
                    normalized["destinationPeer"] = peerName
                    normalized["request"] = requestLine
                }
                normalized
            }

            channels.forEachIndexed { channelIndex, existing ->
                val incoming = nextChannels[channelIndex]
                if (existing.submittedAt != null && incoming.submittedAt == null) {
                    if (existing.fromFlash) peer()?.release(channelIndex)
                    if (existing.toPeer) existing.destinationBinding?.peer?.release(channelIndex)
                    existing.audioRxPeer?.release(channelIndex)
                }
            }
            values = validatedValues
            nextMux.copyInto(mux)
            nextMuxChanged.copyInto(muxChanged)
            nextRequests.copyInto(requests)
            nextChannels.forEachIndexed { channelIndex, validated ->
                val target = channels[channelIndex]
                validated.registers.copyInto(target.registers)
                target.deadline = validated.deadline
                target.submittedAt = validated.submittedAt
                target.fromFlash = validated.fromFlash
                target.spi0 = validated.spi0
                target.spi0Offset = validated.spi0Offset
                target.spi2 = validated.spi2
                target.chainedRing = validated.chainedRing
                target.softwareChain = validated.softwareChain
                target.ringEnd = validated.ringEnd
                target.ringChunkBytes = validated.ringChunkBytes
                target.toPeer = validated.toPeer
                target.destinationBinding = validated.destinationBinding
                target.audioRxPeerName = validated.audioRxPeerName
                target.audioRxPeer = validated.audioRxPeer
                target.pending = validated.pending
                target.completed = validated.completed
            }
            events.clear()
            events.addAll(nextEvents)
            eventCount = nextEventCount
            ch4ControlWord = nextCh4Control
            ch4ControlWritten = nextCh4Written
            nextCh4Params.copyInto(ch4ParamWords)
            ch4Deadline = nextCh4Deadline
            ch4Expirations = nextCh4Expirations
            ch7EnableWord = nextCh7Enable
            ch7ControlWord = nextCh7Control
            nextCh7Params.copyInto(ch7ParamWords)
            ch7Deadline = nextCh7Deadline
            ch7Expirations = nextCh7Expirations
            ch11ControlWord = nextCh11Control
            ch11ControlWritten = nextCh11Written
            nextCh11Params.copyInto(ch11ParamWords)
            ch11Deadline = nextCh11Deadline
            ch11Expirations = nextCh11Expirations
            ch14ControlWord = nextCh14Control
            nextCh14Params.copyInto(ch14ParamWords)
            ch14Running = nextCh14Running
            ch14Deadline = nextCh14Deadline
            ch14FireCount = nextCh14FireCount
            for (t in oneshotTimers) {
                val st = nextTimerStates.getValue(t)
                t.controlWord = st.control
                t.controlWritten = st.written
                st.params.copyInto(t.paramWords)
                t.deadline = st.deadline
                t.expirations = st.expirations
            }
            if (interruptConfigured) {
                val sinkName = values.getValue("interruptPort") as String
                context.ports.find(sinkName, InterruptSink::class.java)?.let { sink ->
                    val base = (values.getValue("irqBase") as Number).toInt()
                    channels.forEachIndexed { channelIndex, channel ->
                        sink.setLine(base + channelIndex, channel.pending)
                    }
                    syncedPendingMask = currentPendingMask()
                } ?: run { syncedPendingMask = -1 }
            } else {
                syncedPendingMask = -1
            }
            syncAllMirrors()
        }
    }
}
