package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Pinned spi.rs bounded FIFO/loopback law; timing/reset hypotheses, external input never guessed. */
class SpiFactory : ComponentFactory {
    companion object {
        private const val MAX_AUDIO_WORDS = 4096
        private fun spi2Replies(raw: Any?, continuous: Boolean = false): PanelWordPlan {
            if (raw is Map<*, *>) {
                require(raw["fixedMisoHigh"] == false) { "SPI2 fixed input requires low MISO" }
            }
            return PanelWordPlan(raw, 256, "bounded-spi2-miso-low-v1", bitsPerWord = 8, continuous = continuous)
        }
        private fun audioWordPlan(raw: Any?, continuous: Boolean = false): PanelWordPlan {
            val maximumWords = if (raw is Map<*, *>) 16384 else MAX_AUDIO_WORDS
            return PanelWordPlan(raw, maximumWords, "bounded-audio-miso-v1", continuous = continuous)
        }
        private fun parseIntegralLong(value: Any?, fieldName: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Integral value required for $fieldName" }
            return (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $fieldName")
        }
        private fun parseIntegralInt(value: Any?, fieldName: String): Int {
            val numberValue = parseIntegralLong(value, fieldName)
            require(numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out of Int range for $fieldName" }
            return numberValue.toInt()
        }
    }
    override val descriptor=ComponentDescriptor("soc-spi","0.0.1",settings=listOf(
        Setting("profile",ValueKind.TEXT,"spi-fifo-no-peer-experimental-v1",choices=listOf("spi-fifo-no-peer-experimental-v1","spi-fifo-peer-experimental-v2"),live=false),
        Setting("peerPort",ValueKind.TEXT,"",live=false),Setting("portsFile",ValueKind.TEXT,"",live=false),
        Setting("continuousFixedMiso",ValueKind.BOOLEAN,false,live=false)))
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Bank(context,config)
    private inner class Bank(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-spi",context,config),StatefulComponent,NativeMmioMirrorPort {
        private val ownerThread: Thread = Thread.currentThread()
        @Volatile
        private var cachedOrientationPort: CameraOrientationPort? = null
        @Volatile
        private var cachedLensPort: LensPort? = null
        private val continuousFixedMiso = config.getOrDefault("continuousFixedMiso", false) as Boolean
        private val primary: Controller
        private val extra=ArrayList<Controller>()
        private var biscomPeer: Frame32SpiPeer? = null
        private var wm8962Peer: Wm8962SpiPeer? = null
        private var panelBacklight: PanelBacklightDecoder? = null
        init {
            val peer=config.getValue("peerPort") as String
            require((config.getValue("profile")=="spi-fifo-peer-experimental-v2")==peer.isNotEmpty()) { "SPI profile/peer selection mismatch" }
            primary=Controller(context,1,peer)
            val file=config.getValue("portsFile") as String
            if(file.isNotEmpty()) {
                val data=context.readData(file);require(data.keys==setOf("profile","controllers") && data["profile"]=="spi-additional-ports-experimental-v1")
                val rows=data["controllers"];require(rows is List<*> && rows.size in 1..6);val seen=HashSet<Int>()
                rows.forEach { raw->
                    require(raw is Map<*,*> && raw.keys - setOf("panel16Replies", "audio16Replies", "spi2ByteReplies", "requestEnable3Limit") == setOf("index","peerPort"));val index=raw["index"]
                    require(index is Int || index is Long);val n=(index as Number).toLong();require(n in 0..6 && n!=1L && seen.add(n.toInt())) { "duplicate/invalid extra SPI index" }
                    val name=raw["peerPort"];require(name is String && name.length<=128)
                    val panel = if ("spi2ByteReplies" in raw) {
                        require(n == 2L && name.isEmpty() && "requestEnable3Limit" in raw && "audio16Replies" !in raw && "panel16Replies" !in raw)
                        spi2Replies(raw["spi2ByteReplies"], continuous = continuousFixedMiso)
                    } else if ("audio16Replies" in raw) {
                        require(n == 4L && name.isEmpty() && "panel16Replies" !in raw)
                        audioWordPlan(raw["audio16Replies"], continuous = continuousFixedMiso)
                    } else if ("panel16Replies" in raw) {
                        require(n == 5L && name.isEmpty()) { "Panel replies require unwired SPI5" }
                        PanelWordPlan(raw["panel16Replies"], continuous = continuousFixedMiso, sharedBus = continuousFixedMiso)
                    } else null
                    val requestLimit = if ("requestEnable3Limit" in raw) {
                        require((n == 2L && (name.isEmpty() || name == "soc-spi.biscom")) || (n == 0L && name == "mount.spi"))
                        val maxRequestLimit = if (name == "soc-spi.biscom" || name == "mount.spi") 16384 else 64
                        parseIntegralInt(raw["requestEnable3Limit"], "requestEnable3Limit").also { require(it in 1..maxRequestLimit) }
                    } else 0
                    extra.add(Controller(context,n.toInt(),name,panel,requestLimit,continuousFixedMiso))
                }
                extra.sortBy {it.clockIndex}
            }
            if (values["peerPort"] == "soc-spi.biscom" || extra.any { it.peerPort == "soc-spi.biscom" }) {
                val ibisGpio by lazy(LazyThreadSafetyMode.NONE) { context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java) }
                val peerInstance = Frame32SpiPeer(
                    powerOnSequence = {
                        val gpio = ibisGpio
                        // The 2.13 IBIS startup at 0x01237910 enables GPIO 3.4.
                        // GPIO 12.2 also switches during an ordinary movie/REC mode change.
                        if (gpio != null && gpio.outputLevel(3, 4)) gpio.outputSequence(3, 4) else -1L
                    },
                    orientationProvider = {
                        if (Thread.currentThread() === ownerThread) {
                            (context.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java)
                                ?: context.ports.find("i2c-bus.orientation", CameraOrientationPort::class.java)).also {
                                cachedOrientationPort = it
                            }
                        } else {
                            cachedOrientationPort
                        }
                    },
                    lensPortProvider = {
                        if (Thread.currentThread() === ownerThread) {
                            context.ports.find("lens", LensPort::class.java).also { cachedLensPort = it }
                        } else {
                            cachedLensPort
                        }
                    },
                )
                biscomPeer = peerInstance
                context.ports.register(id, "soc-spi.biscom", SpiPeer::class.java, peerInstance)
                context.ports.register(id, IbisShiftPort.PORT_NAME, IbisShiftPort::class.java, peerInstance)
            }
            if (values["peerPort"] == Wm8962SpiPeer.PEER_PORT || extra.any { it.peerPort == Wm8962SpiPeer.PEER_PORT }) {
                val peerInstance = Wm8962SpiPeer()
                wm8962Peer = peerInstance
                context.ports.register(id, Wm8962SpiPeer.PEER_PORT, SpiPeer::class.java, peerInstance)
                context.ports.register(id, Wm8962SpiPeer.CODEC_PORT, AudioCodecControlPort::class.java, peerInstance)
            }
            val spi5 = extra.firstOrNull { it.clockIndex == 5 }
            if (spi5 != null) {
                val decoder = PanelBacklightDecoder()
                panelBacklight = decoder
                spi5.panelBacklight = decoder
                context.ports.register(id, PanelBacklightPort.PORT_NAME, PanelBacklightPort::class.java, decoder)
            }
        }
        override fun start() {
            super<ScalarComponent>.start()
            cachedOrientationPort = context.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java)
                ?: context.ports.find("i2c-bus.orientation", CameraOrientationPort::class.java)
            cachedLensPort = context.ports.find("lens", LensPort::class.java)
            panelBacklight?.attachGpio(context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java))
        }
        override fun close() {
            detachMmioMirrors()
            panelBacklight?.detachGpio()
            super<ScalarComponent>.close()
        }
        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> =
            (listOf(primary) + extra).map { it.mirrorWindowSpec() }
        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            if (portName == "soc-spi.spi${primary.clockIndex}") {
                primary.attachMirror(handle)
                return
            }
            extra.firstOrNull { portName == "soc-spi.spi${it.clockIndex}" }?.attachMirror(handle)
        }
        override fun detachMmioMirrors() {
            primary.detachMirror()
            for (c in extra) c.detachMirror()
        }
        override fun additionalMmioPorts()=extra.map {"soc-spi.spi${it.clockIndex}"}
        override fun state(): Map<String,Any?> = super.state()+primary.state()+mapOf("additionalControllers" to extra.map {it.state()},"additionalProfile" to if(extra.isEmpty())"disabled" else "spi-additional-ports-experimental-v1") +
            (biscomPeer?.let { mapOf("biscomPeer" to it.snapshot(), "ibis" to it.ibisStateMap()) } ?: emptyMap()) +
            (wm8962Peer?.let { mapOf("wm8962Peer" to it.state()) } ?: emptyMap()) +
            (panelBacklight?.let { mapOf("panelBacklight" to it.stateMap()) } ?: emptyMap())
        override fun saveSnapshot(): Map<String,Any?> = linkedMapOf<String, Any?>(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "scalarValues" to saveScalarValues(),
            "primary" to primary.saveSnapshot(),
            "additionalControllers" to extra.map { controller -> controller.saveSnapshot() },
        ) + (biscomPeer?.takeUnless { it.isInitial() }?.let { mapOf("biscomPeer" to it.snapshot()) } ?: emptyMap()) +
            (wm8962Peer?.takeUnless { it.isInitial() }?.let { mapOf("wm8962Peer" to it.snapshot()) } ?: emptyMap()) +
            (panelBacklight?.takeUnless { it.isInitial() }?.let { mapOf("panelBacklight" to it.snapshot()) } ?: emptyMap())
        override fun restoreSnapshot(data: Map<String,Any?>) {
            require(data.keys - setOf("biscomPeer", "wm8962Peer", "panelBacklight") == setOf("schema", "componentId", "scalarValues", "primary", "additionalControllers")) {
                "Unexpected soc-spi snapshot keys"
            }
            require(parseIntegralInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported soc-spi snapshot schema" }
            require(data["componentId"] == id) { "Mismatched soc-spi componentId" }
            val rawScalars = data["scalarValues"] as? Map<*, *> ?: throw IllegalArgumentException("Missing soc-spi scalarValues")
            require(rawScalars.keys.all { key -> key is String && descriptor.settings.any { setting -> setting.key == key } }) {
                "Unknown scalar setting key in snapshot for $id"
            }
            require(descriptor.settings.all { setting -> rawScalars.containsKey(setting.key) || setting.key == "continuousFixedMiso" }) {
                "Missing scalar setting key in snapshot for $id"
            }
            val normalizedScalars = linkedMapOf<String, Any?>()
            for (setting in descriptor.settings) {
                val present = rawScalars.containsKey(setting.key)
                val rawSetting = rawScalars[setting.key]
                    ?: if (!present && setting.key == "continuousFixedMiso") false else throw IllegalArgumentException("Null setting ${setting.key} in $id")
                val validated = setting.validate(rawSetting)
                val current = values[setting.key] ?: if (setting.key == "continuousFixedMiso") false else null
                val promotedContinuous = setting.key == "continuousFixedMiso" && current == true && validated == false
                require(current == validated || promotedContinuous) { "Restart-only setting ${setting.key} differs in snapshot for $id" }
                if (present || values.containsKey(setting.key)) normalizedScalars[setting.key] = if (promotedContinuous) true else validated
            }
            val parsedPrimary = primary.parseSnapshot(data["primary"])
            val rawExtra = data["additionalControllers"] as? List<*>
                ?: throw IllegalArgumentException("Invalid additionalControllers in soc-spi snapshot")
            val activated = if (rawExtra.size + 1 == extra.size) when (extra.map { it.clockIndex }) {
                listOf(0, 5) -> extra.last()
                listOf(0, 4, 5) -> extra[1]
                listOf(0, 2, 4, 5) -> when ((rawExtra[1] as? Map<*, *>)?.get("index")) {
                    4, 4L -> extra[1]
                    2, 2L -> extra[2]
                    else -> null
                }
                else -> null
            } else null
            require(rawExtra.size == extra.size || activated?.peerPort == "") { "Additional SPI controller count mismatch" }
            val savedControllers = extra.filter { it !== activated }
            val parsedExtra = rawExtra.mapIndexed { position, saved -> savedControllers[position].parseSnapshot(saved) }
            val parsedBiscom = data["biscomPeer"]?.let { rawPeer ->
                requireNotNull(biscomPeer) { "Snapshot contains biscomPeer state without active soc-spi.biscom peer" }.parse(rawPeer)
            }
            val parsedWm8962 = data["wm8962Peer"]?.let { rawPeer ->
                requireNotNull(wm8962Peer) { "Snapshot contains wm8962Peer state without active soc-spi.wm8962 peer" }.parse(rawPeer)
            }
            val parsedBacklight = data["panelBacklight"]?.let { rawBl ->
                requireNotNull(panelBacklight) { "Snapshot contains panelBacklight state without active SPI5 controller" }.parse(rawBl)
            }
            restoreScalarValues(normalizedScalars, descriptor)
            primary.applySnapshot(parsedPrimary)
            for (position in parsedExtra.indices) {
                savedControllers[position].applySnapshot(parsedExtra[position])
            }
            activated?.applySnapshot(Controller.resetState)
            biscomPeer?.restore(parsedBiscom)
            wm8962Peer?.restore(parsedWm8962)
            panelBacklight?.restore(parsedBacklight)
        }
    }
    private class Controller(private val context: ComponentContext,override val clockIndex: Int,val peerPort: String,
        private val panel: PanelWordPlan? = null, private val requestLimit: Int = 0, private val continuous: Boolean = false) : TickAwareMmioDevice {
        var panelBacklight: PanelBacklightDecoder? = null
        private var requestEnable = 0L
        private var requestWrites = 0
        private fun requestState() = if (requestLimit == 0) emptyMap() else mapOf("requestEnable3Limit" to requestLimit,
            "requestEnable" to requestEnable, "requestEnable3Writes" to requestWrites)
        private val replyKey = when (clockIndex) {
            2 -> "spi2ByteReplies"
            4 -> "audio16Replies"
            else -> "panel16Replies"
        }
        override val clockOrder=3 // explicit functional order: after timer, before flash/DMA/method4
        private var control=0L;private var enable=0L;private var divider=0L;private var causes=0L
        private val tx=ArrayDeque<Int>();private val rx=ArrayDeque<Int>()
        private var mirror: MmioMirrorHandle? = null
        fun mirrorWindowSpec(): MmioMirrorWindowSpec = MmioMirrorWindowSpec(
            portName = "soc-spi.spi$clockIndex",
            windowSize = 0x28L,
            slots = listOf(
                MmioMirrorSlotSpec(0x00L, 4), // control
                MmioMirrorSlotSpec(0x04L, 4), // enable
                // 0x08 (DATA) intentionally omitted: reads pop rx FIFO
                MmioMirrorSlotSpec(0x0CL, 4), // status
                MmioMirrorSlotSpec(0x10L, 4), // divider
                MmioMirrorSlotSpec(0x14L, 4), // causes
                MmioMirrorSlotSpec(0x24L, 4), // requestEnable
            ),
        )
        fun attachMirror(handle: MmioMirrorHandle) {
            mirror = handle
            syncMirror()
        }
        fun detachMirror() {
            mirror?.invalidateAll()
            mirror = null
        }
        private fun statusWord(): Int =
            (if (tx.isEmpty()) 1 else 0) or
                (if (tx.size < 8) 2 else 0) or
                (if (rx.isNotEmpty()) 4 else 0) or
                (if (rx.size == 8) 8 else 0) or
                (if (pending != null) 16 else 0)
        private fun syncMirror() {
            val h = mirror ?: return
            h.writeSlotRaw(0, control.toInt())
            h.writeSlotRaw(1, enable.toInt())
            h.writeSlotRaw(2, statusWord())
            h.writeSlotRaw(3, divider.toInt())
            h.writeSlotRaw(4, causes.toInt())
            h.writeSlotRaw(5, requestEnable.toInt())
            h.setValidMask(h.allSlotsMask)
        }
        private fun syncMirrorStatus() {
            mirror?.writeSlot(2, statusWord())
        }
        private data class Shift(val at: Long,val tx: Int,val bits: Int)
        data class ParsedSnapshot(
            val control: Long,
            val enable: Long,
            val divider: Long,
            val causes: Long,
            val txWords: List<Int>,
            val rxWords: List<Int>,
            val pendingShift: Shift?,
            val eventCount: Long,
            val eventEntries: List<Map<String, Any>>,
            val requestEnable: Long = 0,
            val requestWrites: Int = 0,
            val biscomNextPollTick: Long? = null,
            val biscomIrqLine: Int = 0,
            val lenscomNextPollTick: Long? = null,
            val lenscomIrqLine: Int = 0,
        )
        companion object {
            private const val BISCOM_READY_FALLING_IRQ = 171
            private const val BISCOM_READY_RISING_IRQ = 155
            private const val BISCOM_GPIO_PORT = 0
            private const val BISCOM_GPIO_PIN = 4
            private const val LENSCOM_READY_RISING_IRQ = 166
            private const val LENSCOM_READY_FALLING_IRQ = 182
            private const val LENSCOM_READY_GPIO_PORT = 15
            private const val LENSCOM_READY_GPIO_PIN = 1
            private const val LENSCOM_AUX_RISING_IRQ = 157
            private const val LENSCOM_AUX_FALLING_IRQ = 173
            private const val LENSCOM_AUX_GPIO_PORT = 10
            private const val LENSCOM_AUX_GPIO_PIN = 13
            private const val BISCOM_IDLE_POLL_INTERVAL = 50_000L
            private const val BISCOM_ACTIVE_POLL_INTERVAL = 2_000L
            private const val BISCOM_RELEASE_POLL_INTERVAL = 1_000L
            private const val LENSCOM_READY_ARM_SETTLE_TICKS = 200_000L
            private const val LENSCOM_READY_SPI_QUIET_TICKS = 150_000L
            val resetState = ParsedSnapshot(0, 0, 0, 0, emptyList(), emptyList(), null, 0, emptyList())
        }
        private var pending: Shift?=null
        private var biscomNextPollTick: Long? = null
        private var biscomIrqLine = 0
        private var biscomFastPolls = 0
        private var lenscomNextPollTick: Long? = null
        private var lenscomIrqLine = 0
        private var lenscomFastPolls = 0
        private var lenscomReadyArmTick = -1L
        private var lenscomSawFocusBusy = false
        private var lenscomLastSpiTick = -1_000_000L
        private var cachedPeer: SpiPeer? = null
        private var cachedGic: EnabledEdgeInterruptSink? = null
        private var cachedGpioIn: GpioInputPort? = null
        private var cachedModularLens: ModularLensPort? = null
        private fun isBiscom() = clockIndex == 2 && peerPort == "soc-spi.biscom"
        private fun isLenscom() = clockIndex == 0 && peerPort == "mount.spi" && requestLimit > 0
        override fun nextDeadline():Long?=pending?.at
        override fun nextInterestedTick():Long {
            val p = pending?.at ?: Long.MAX_VALUE
            val b = if (isBiscom()) (biscomNextPollTick ?: 0L) else Long.MAX_VALUE
            val l = if (isLenscom()) (lenscomNextPollTick ?: 0L) else Long.MAX_VALUE
            return minOf(p, b, l)
        }
        override fun readMayMutateSchedule(): Boolean = false
        private class SpiEventRing {
            private val cap = 256
            private val ticks = LongArray(cap)
            private val bits = IntArray(cap)
            private val txs = IntArray(cap)
            private val rxs = IntArray(cap)
            private var head = 0
            var size = 0
                private set
            fun clear() { head = 0; size = 0 }
            fun add(tick: Long, b: Int, tx: Int, rx: Int) {
                val slot = if (size < cap) {
                    val s = (head + size) and 255
                    size++
                    s
                } else {
                    val s = head
                    head = (head + 1) and 255
                    s
                }
                ticks[slot] = tick; bits[slot] = b; txs[slot] = tx; rxs[slot] = rx
            }
            fun toList(): List<Map<String, Any>> {
                val out = ArrayList<Map<String, Any>>(size)
                for (i in 0 until size) {
                    val s = (head + i) and 255
                    out.add(linkedMapOf("tick" to ticks[s], "bits" to bits[s], "tx" to txs[s], "rx" to rxs[s]))
                }
                return out
            }
            fun restore(entries: List<Map<String, Any>>) {
                clear()
                for (e in entries) {
                    add(
                        (e.getValue("tick") as Number).toLong(),
                        (e.getValue("bits") as Number).toInt(),
                        (e.getValue("tx") as Number).toInt(),
                        (e.getValue("rx") as Number).toInt(),
                    )
                }
            }
        }
        private val events = SpiEventRing(); private var eventCount = 0L
        init {
            context.ports.register("soc-spi","soc-spi.spi$clockIndex",MmioDevice::class.java,this)
            if ((clockIndex == 0 || clockIndex == 2) && requestLimit > 0) {
                val fifo = object : SpiDmaFifo {
                    private fun selected() = requestEnable == 3L && enable == 10L && control and 15 == 7L && divider in 2..254 && divider % 2 == 0L
                    override fun readableBytes() = if (selected()) rx.size else 0
                    override fun writableBytes() = if (selected()) 8 - tx.size else 0
                    override fun read(destination: ByteBuffer) {
                        val count = destination.remaining()
                        require(!destination.isReadOnly && count in 1..readableBytes()) { "SPI$clockIndex DMA RX FIFO unavailable" }
                        repeat(count) { destination.put(rx.removeFirst().toByte()) }
                        syncMirrorStatus()
                    }
                    override fun write(source: ByteBuffer) {
                        val count = source.remaining()
                        require(count in 1..writableBytes()) { "SPI$clockIndex DMA TX FIFO unavailable" }
                        val now = context.clock.ticks()
                        if (pending == null) deadline(now)
                        repeat(count) { tx.addLast(source.get().toInt() and 255) }
                        start(now)
                        syncMirrorStatus()
                    }
                }
                context.ports.register("soc-spi", "soc-spi.spi$clockIndex.dma", SpiDmaFifo::class.java, fifo)
            }
        }
        private fun deadline(now: Long): Long { require(now>=0); return Math.addExact(now,((control and 15)+1)*divider*(((control ushr 8) and 255)+1)) }
        private fun start(now: Long) {
            if(pending==null && enable and 2!=0L && tx.isNotEmpty()) {
                val at=deadline(now);pending=Shift(at,tx.removeFirst(),((control and 15)+1).toInt())
            }
        }
        override fun beforeInstruction(tick: Long) {
            var mirrorDirty = false
            while(pending?.let { it.at<=tick }==true) {
                val shift=checkNotNull(pending);require(rx.size<8) { "SPI receive FIFO overflow" }
                val mask=(1 shl shift.bits)-1;val word=shift.tx and mask
                val result=if (panel != null) {
                    require(enable and 1L == 0L) { "Scripted SPI exchange requires external mode" }
                    panel.exchange(eventCount, shift.bits, word)
                } else if(enable and 1!=0L) word else {
                    val peer = cachedPeer ?: if (peerPort.isNotEmpty()) {
                        context.ports.find(peerPort, SpiPeer::class.java)?.also { cachedPeer = it }
                    } else null
                    checkNotNull(peer) { "SPI exchange requires explicit external peer; no MISO value supplied" }.exchange(shift.bits,word,shift.at)
                }
                require(result in 0..mask) { "SPI peer result exceeds selected word width" }
                rx.addLast(result);causes=causes or 1;pending=null
                events.add(shift.at, shift.bits, word, result); eventCount++
                panelBacklight?.onShift(shift.bits, word, shift.at)
                if (isBiscom()) {
                    biscomFastPolls = 30
                    val fastTick = shift.at + BISCOM_ACTIVE_POLL_INTERVAL
                    if (biscomNextPollTick == null || biscomNextPollTick!! > fastTick) biscomNextPollTick = fastTick
                }
                if (isLenscom()) {
                    lenscomFastPolls = 30
                    lenscomLastSpiTick = shift.at
                    val fastTick = shift.at + BISCOM_ACTIVE_POLL_INTERVAL
                    if (lenscomNextPollTick == null || lenscomNextPollTick!! > fastTick) lenscomNextPollTick = fastTick
                }
                start(shift.at)
                mirrorDirty = true
            }
            if (mirrorDirty) {
                val h = mirror
                if (h != null) {
                    h.writeSlot(2, statusWord())
                    h.writeSlot(4, causes.toInt())
                }
            }
            if (isLenscom()) {
                val pollTick = lenscomNextPollTick ?: 0L
                if (tick >= pollTick) {
                    val gic = cachedGic ?: (context.ports.find("soc-gic.lines", InterruptSink::class.java) as? EnabledEdgeInterruptSink)?.also { cachedGic = it }
                    if (gic != null) {
                        if (lenscomIrqLine != 0) {
                            if (!gic.lineEnabled(lenscomIrqLine)) {
                                gic.setLine(lenscomIrqLine, false)
                                lenscomIrqLine = 0
                                lenscomFastPolls = 30
                                lenscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                            } else {
                                lenscomNextPollTick = tick + BISCOM_RELEASE_POLL_INTERVAL
                            }
                        } else {
                            // DAT S4 0x0121A9AC / 0x0121ADA0 / 0x01362D58 / 0x01362F0C / 0x01364E54:
                            // GF-mount GPIO(15,1) (LENS_RDY: IRQ 166 rising / IRQ 182 falling) stays LOW while
                            // the lens focus motor is actively driving, and rises HIGH (firing IRQ 166) only when
                            // the focus motor stops/arrives. SPI0 LENSCOM framing uses LENSCOM_AUX (IRQ 157/173,
                            // GPIO(10,13)) so live cmd 0x020/0x021/0x058 traffic continues unimpeded during slew.
                            val modularLens = cachedModularLens
                                ?: (context.ports.find("lens", LensPort::class.java) as? ModularLensPort)?.also { cachedModularLens = it }
                            val focusBusy = modularLens?.focusMotorBusy == true
                            val readyRisingEnabled = gic.lineEnabled(LENSCOM_READY_RISING_IRQ)
                            if (!readyRisingEnabled) {
                                lenscomReadyArmTick = -1L
                                lenscomSawFocusBusy = false
                            } else if (lenscomReadyArmTick < 0L) {
                                lenscomReadyArmTick = tick
                                lenscomSawFocusBusy = focusBusy
                            }
                            if (focusBusy) {
                                lenscomSawFocusBusy = true
                                val gpioIn = cachedGpioIn ?: context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)?.also { cachedGpioIn = it }
                                gpioIn?.setInputLevel(LENSCOM_READY_GPIO_PORT, LENSCOM_READY_GPIO_PIN, false)
                            }
                            // Firmware enables rising-edge IRQ 166 BEFORE clocking cmd 0x54/0x55/0x56 over SPI0
                            // (DAT S4 0x01362D58 / 0x01362F0C / 0x01364E54). On real GF-mount hardware, GPIO(15,1)
                            // is already HIGH when armed, drops LOW once cmd 0x56 starts the motor, and generates
                            // its single rising edge when the motor arrives (focusBusy: true -> false). Wait for
                            // that busy->idle edge (or SPI0 quiescence after arming for boot / zero-delta moves).
                            val readyRisingAllowed = readyRisingEnabled && !focusBusy && (
                                modularLens == null ||
                                    lenscomSawFocusBusy ||
                                    (tick - lenscomReadyArmTick >= LENSCOM_READY_ARM_SETTLE_TICKS &&
                                        tick - lenscomLastSpiTick >= LENSCOM_READY_SPI_QUIET_TICKS)
                                )
                            val targetIrq = when {
                                gic.lineEnabled(LENSCOM_READY_FALLING_IRQ) -> LENSCOM_READY_FALLING_IRQ
                                readyRisingAllowed -> LENSCOM_READY_RISING_IRQ
                                gic.lineEnabled(LENSCOM_AUX_FALLING_IRQ) -> LENSCOM_AUX_FALLING_IRQ
                                gic.lineEnabled(LENSCOM_AUX_RISING_IRQ) -> LENSCOM_AUX_RISING_IRQ
                                else -> 0
                            }
                            if (targetIrq == LENSCOM_READY_RISING_IRQ) {
                                lenscomReadyArmTick = -1L
                                lenscomSawFocusBusy = false
                            }
                            if (targetIrq != 0) {
                                val st = gic.edgeState(targetIrq)
                                if (!st.high && !st.pending) {
                                    val gpioIn = cachedGpioIn ?: context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)?.also { cachedGpioIn = it }
                                    when (targetIrq) {
                                        LENSCOM_READY_RISING_IRQ -> gpioIn?.setInputLevel(LENSCOM_READY_GPIO_PORT, LENSCOM_READY_GPIO_PIN, true)
                                        LENSCOM_READY_FALLING_IRQ -> gpioIn?.setInputLevel(LENSCOM_READY_GPIO_PORT, LENSCOM_READY_GPIO_PIN, false)
                                        LENSCOM_AUX_RISING_IRQ -> gpioIn?.setInputLevel(LENSCOM_AUX_GPIO_PORT, LENSCOM_AUX_GPIO_PIN, true)
                                        LENSCOM_AUX_FALLING_IRQ -> gpioIn?.setInputLevel(LENSCOM_AUX_GPIO_PORT, LENSCOM_AUX_GPIO_PIN, false)
                                    }
                                    gic.setLine(targetIrq, true)
                                    lenscomFastPolls = 30
                                    if (st.edge) {
                                        gic.setLine(targetIrq, false)
                                        lenscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                                    } else {
                                        lenscomIrqLine = targetIrq
                                        lenscomNextPollTick = tick + BISCOM_RELEASE_POLL_INTERVAL
                                    }
                                } else {
                                    lenscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                                }
                            } else if (lenscomFastPolls > 0) {
                                lenscomFastPolls--
                                lenscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                            } else {
                                lenscomNextPollTick = tick + BISCOM_IDLE_POLL_INTERVAL
                            }
                        }
                    } else {
                        lenscomNextPollTick = tick + BISCOM_IDLE_POLL_INTERVAL
                    }
                }
            }
            if (isBiscom()) {
                val pollTick = biscomNextPollTick ?: 0L
                if (tick >= pollTick) {
                    val gic = cachedGic ?: (context.ports.find("soc-gic.lines", InterruptSink::class.java) as? EnabledEdgeInterruptSink)?.also { cachedGic = it }
                    if (gic != null) {
                        if (biscomIrqLine != 0) {
                            if (!gic.lineEnabled(biscomIrqLine)) {
                                gic.setLine(biscomIrqLine, false)
                                biscomIrqLine = 0
                                biscomFastPolls = 30
                                biscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                            } else {
                                biscomNextPollTick = tick + BISCOM_RELEASE_POLL_INTERVAL
                            }
                        } else {
                            val targetIrq = when {
                                gic.lineEnabled(BISCOM_READY_FALLING_IRQ) -> BISCOM_READY_FALLING_IRQ
                                gic.lineEnabled(BISCOM_READY_RISING_IRQ) -> BISCOM_READY_RISING_IRQ
                                else -> 0
                            }
                            if (targetIrq != 0) {
                                val st = gic.edgeState(targetIrq)
                                if (!st.high && !st.pending) {
                                    val gpioIn = cachedGpioIn ?: context.ports.find("soc-gpio.inputs", GpioInputPort::class.java)?.also { cachedGpioIn = it }
                                    gpioIn?.setInputLevel(BISCOM_GPIO_PORT, BISCOM_GPIO_PIN, targetIrq == BISCOM_READY_RISING_IRQ)
                                    gic.setLine(targetIrq, true)
                                    biscomFastPolls = 30
                                    if (st.edge) {
                                        gic.setLine(targetIrq, false)
                                        biscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                                    } else {
                                        biscomIrqLine = targetIrq
                                        biscomNextPollTick = tick + BISCOM_RELEASE_POLL_INTERVAL
                                    }
                                } else {
                                    biscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                                }
                            } else if (biscomFastPolls > 0) {
                                biscomFastPolls--
                                biscomNextPollTick = tick + BISCOM_ACTIVE_POLL_INTERVAL
                            } else {
                                biscomNextPollTick = tick + BISCOM_IDLE_POLL_INTERVAL
                            }
                        }
                    } else {
                        biscomNextPollTick = tick + BISCOM_IDLE_POLL_INTERVAL
                    }
                }
            }
        }
        private fun check(offset: Long, size: Int) {
            val valid = size == 4 && (
                offset == 0L || offset == 4L || offset == 8L || offset == 0xcL ||
                    offset == 0x10L || offset == 0x14L || offset == 0x20L || offset == 0x24L
                )
            require(valid) { "unmodeled SPI register/width" }
        }
        override fun read(offset: Long,size: Int): Long {
            check(offset,size)
            return when(offset) {
                0L->control;4L->enable;8L->{check(rx.isNotEmpty()) { "SPI receive FIFO underflow" };val v=rx.removeFirst().toLong();syncMirrorStatus();v}
                0xcL->statusWord().toLong()
                0x10L->divider;0x14L->causes;0x24L->requestEnable;else->0
            }
        }
        override fun write(offset: Long,size: Int,value: Long) {
            check(offset,size);require(value in 0..0xffffffffL);val now=context.clock.ticks()
            when(offset) {
                0L->{require(pending==null && value and 0xffffL.inv()==0L && value and 0x30==0L) { "unsupported SPI format or active reconfiguration" };control=value;mirror?.writeSlot(0, control.toInt())}
                4L->{require(value and 0xbL.inv()==0L) { "SPI slave mode not modeled" };require(pending==null || value and 2==0L || value==enable) { "SPI mode change while shifting" }
                    if(pending==null && value and 2!=0L && tx.isNotEmpty()) deadline(now)
                    enable=value;if(value and 2==0L){pending=null;tx.clear()}else start(now)
                    mirror?.let { h -> h.writeSlot(1, enable.toInt()); h.writeSlot(2, statusWord()) }}
                8L->{require(control and 15>=3L && divider in 2..254 && divider%2==0L) { "SPI word length/divider not configured" };require(tx.size<8) { "SPI transmit FIFO overflow" }
                    if(pending==null && enable and 2!=0L) deadline(now)
                    tx.addLast((value and 0xffff).toInt());start(now);syncMirrorStatus()}
                0xcL->error("write to SPI status")
                0x10L->{require(pending==null) { "SPI clock change while shifting" };divider=value;mirror?.writeSlot(3, divider.toInt())}
                0x14L->{causes=causes and value.inv();mirror?.writeSlot(4, causes.toInt())}
                0x20L->{require(value and 3L.inv()==0L) { "unknown SPI FIFO clear bits" };if(value and 1!=0L) tx.clear();if(value and 2!=0L) rx.clear();syncMirrorStatus()}
                0x24L->{
                    require(value == 0L || value == 3L && (requestLimit > 0 && (continuous || requestWrites < requestLimit))) { "SPI request enable unsupported or exhausted" }
                    if (value == 3L) requestWrites++
                    requestEnable = value
                    mirror?.writeSlot(5, requestEnable.toInt())
                }
            }
        }
        fun state()=mapOf("port" to "soc-spi.spi$clockIndex","index" to clockIndex,"peerPort" to peerPort,
            "control" to control,"enable" to enable,"divider" to divider,"causes" to causes,"tx" to tx.toList(),"rx" to rx.toList(),
            "pending" to pending?.let { mapOf("at" to it.at,"tx" to it.tx,"bits" to it.bits) },"events" to events.toList(),"eventCount" to eventCount,"eventLogTruncated" to (eventCount>events.size),
            "basis" to "Pinned FIFO/loopback, explicit zero reset and bits*divider*factor instruction-tick latency; actual queues/peer words; missing peer retains pending and refuses, no physical clock/protocol claim") +
            (panel?.let { mapOf(replyKey to it.settings(), "replyConfidence" to "HYPOTHESIS") } ?: emptyMap()) +
            requestState() + (if (requestLimit > 0) mapOf("requestEnableConfidence" to "HYPOTHESIS") else emptyMap())
        fun saveSnapshot(): Map<String,Any?> = linkedMapOf(
            "index" to clockIndex,
            "peerPort" to peerPort,
            "control" to control,
            "enable" to enable,
            "divider" to divider,
            "causes" to causes,
            "tx" to tx.toList(),
            "rx" to rx.toList(),
            "pending" to pending?.let { shift -> linkedMapOf("at" to shift.at, "tx" to shift.tx, "bits" to shift.bits) },
            "eventCount" to eventCount,
            "events" to events.toList(),
        ) + (panel?.let { mapOf(replyKey to it.settings()) } ?: emptyMap()) + requestState() +
            (biscomNextPollTick?.let { mapOf("biscomNextPollTick" to it) } ?: emptyMap()) +
            (if (biscomIrqLine != 0) mapOf("biscomIrqLine" to biscomIrqLine) else emptyMap()) +
            (lenscomNextPollTick?.let { mapOf("lenscomNextPollTick" to it) } ?: emptyMap()) +
            (if (lenscomIrqLine != 0) mapOf("lenscomIrqLine" to lenscomIrqLine) else emptyMap())
        fun parseSnapshot(raw: Any?): ParsedSnapshot {
            val controllerMap = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid SPI controller snapshot")
            val hasRequests = "requestEnable3Limit" in controllerMap
            val requestKeys = if (hasRequests) setOf("requestEnable3Limit","requestEnable","requestEnable3Writes") else emptySet()
            val biscomKeys = setOf("biscomNextPollTick", "biscomIrqLine", "biscomIrqHigh", "lenscomNextPollTick", "lenscomIrqLine")
            require(controllerMap.keys - replyKey - requestKeys - biscomKeys == setOf("index", "peerPort", "control", "enable", "divider", "causes", "tx", "rx", "pending", "eventCount", "events")) {
                "Unexpected SPI controller snapshot keys"
            }
            require(parseIntegralInt(controllerMap["index"], "index") == clockIndex) { "SPI controller index mismatch" }
            val savedPeerPort = controllerMap["peerPort"] as? String ?: throw IllegalArgumentException("Invalid SPI controller peerPort")
            val nextControl = parseIntegralLong(controllerMap["control"], "control")
            require(nextControl in 0L..0xffffL && (nextControl and 0x30L) == 0L) { "Invalid SPI control word in snapshot" }
            val nextEnable = parseIntegralLong(controllerMap["enable"], "enable")
            require(nextEnable in 0L..0xffffffffL && (nextEnable and 0xbL.inv()) == 0L) { "Invalid SPI enable word in snapshot" }
            val nextDivider = parseIntegralLong(controllerMap["divider"], "divider")
            require(nextDivider in 0L..0xffffffffL) { "Invalid SPI divider word in snapshot" }
            val nextCauses = parseIntegralLong(controllerMap["causes"], "causes")
            require(nextCauses in 0L..1L) { "Invalid SPI causes word in snapshot" }
            val rawTx = controllerMap["tx"] as? List<*> ?: throw IllegalArgumentException("Invalid SPI tx FIFO in snapshot")
            require(rawTx.size <= 8) { "SPI tx FIFO exceeds capacity 8" }
            val nextTx = rawTx.map { word -> parseIntegralInt(word, "txWord").also { parsed -> require(parsed in 0..0xffff) } }
            val rawRx = controllerMap["rx"] as? List<*> ?: throw IllegalArgumentException("Invalid SPI rx FIFO in snapshot")
            require(rawRx.size <= 8) { "SPI rx FIFO exceeds capacity 8" }
            val nextRx = rawRx.map { word -> parseIntegralInt(word, "rxWord").also { parsed -> require(parsed in 0..0xffff) } }
            val rawPending = controllerMap["pending"]
            val nextPending = if (rawPending == null) null else {
                val pendingMap = rawPending as? Map<*, *> ?: throw IllegalArgumentException("Invalid SPI pending shift in snapshot")
                require(pendingMap.keys == setOf("at", "tx", "bits")) { "Unexpected SPI pending shift keys" }
                val shiftAt = parseIntegralLong(pendingMap["at"], "pending.at")
                val shiftTx = parseIntegralInt(pendingMap["tx"], "pending.tx")
                val shiftBits = parseIntegralInt(pendingMap["bits"], "pending.bits")
                require(shiftAt >= 0L && shiftTx in 0..0xffff && shiftBits in 4..16) { "Out-of-range SPI pending shift fields" }
                require((nextEnable and 2L) != 0L) { "SPI pending shift requires master enable" }
                require(shiftBits == ((nextControl and 15L) + 1L).toInt()) { "SPI pending shift bits disagree with control" }
                require(nextDivider in 2L..254L && nextDivider % 2L == 0L) { "SPI pending shift requires valid even divider" }
                Shift(shiftAt, shiftTx, shiftBits)
            }
            require((nextEnable and 2L) == 0L || nextTx.isEmpty() || nextPending != null) {
                "Enabled SPI controller with non-empty TX queue requires active pending shift"
            }
            val nextEventCount = parseIntegralLong(controllerMap["eventCount"], "eventCount")
            require(nextEventCount >= 0L) { "Negative SPI eventCount in snapshot" }
            val attachPreExchange = savedPeerPort.isEmpty() && (peerPort == "mount.spi" || peerPort == "soc-spi.biscom" || peerPort == Wm8962SpiPeer.PEER_PORT) &&
                nextEventCount == 0L && nextTx.isEmpty() && nextRx.isEmpty() && nextPending == null
            require(savedPeerPort == peerPort || attachPreExchange) { "SPI controller peerPort mismatch" }
            val rawEvents = controllerMap["events"] as? List<*> ?: throw IllegalArgumentException("Invalid SPI events list in snapshot")
            require(rawEvents.size == minOf(nextEventCount, 256L).toInt()) { "SPI events log size disagrees with eventCount" }
            val nextEvents = rawEvents.map { rawEvent ->
                val eventMap = rawEvent as? Map<*, *> ?: throw IllegalArgumentException("Invalid SPI event entry in snapshot")
                require(eventMap.keys == setOf("tick", "bits", "tx", "rx")) { "Unexpected SPI event keys" }
                val eventTick = parseIntegralLong(eventMap["tick"], "event.tick")
                val eventBits = parseIntegralInt(eventMap["bits"], "event.bits")
                require(eventTick >= 0L && eventBits in 4..16) { "Invalid SPI event tick or bits" }
                val wordMask = (1 shl eventBits) - 1
                val eventTx = parseIntegralInt(eventMap["tx"], "event.tx")
                val eventRx = parseIntegralInt(eventMap["rx"], "event.rx")
                require(eventTx in 0..wordMask && eventRx in 0..wordMask) { "SPI event word exceeds bit width" }
                mapOf("tick" to eventTick, "bits" to eventBits, "tx" to eventTx, "rx" to eventRx)
            }
            if (replyKey in controllerMap) {
                val saved = when (clockIndex) {
                    2 -> spi2Replies(controllerMap[replyKey], continuous = continuous)
                    4 -> audioWordPlan(controllerMap[replyKey], continuous = continuous)
                    else -> PanelWordPlan(controllerMap[replyKey], continuous = continuous, sharedBus = (clockIndex == 5 && continuous))
                }
                require(panel != null && panel.accepts(saved) && (saved.isContinuousFixedMiso || nextEventCount <= saved.exchangeLimit))
                nextEvents.forEachIndexed { index, row ->
                    require(saved.exchange(nextEventCount - nextEvents.size + index, row.getValue("bits") as Int, row.getValue("tx") as Int) == row["rx"])
                }
                require(nextRx == nextEvents.takeLast(nextRx.size).map { it["rx"] })
            } else if (panel != null) {
                require(nextEventCount == 0L && nextRx.isEmpty()) { "Scripted SPI input needs a pre-exchange snapshot" }
            }
            val savedLimit = if (hasRequests) parseIntegralInt(controllerMap["requestEnable3Limit"], "requestEnable3Limit").also {
                require(it in 1..requestLimit)
            } else 0
            val writes = if (hasRequests) parseIntegralInt(controllerMap["requestEnable3Writes"], "requestEnable3Writes") else 0
            val request = if (hasRequests) parseIntegralLong(controllerMap["requestEnable"], "requestEnable") else 0L
            require(writes in 0..savedLimit && (request == 0L || request == 3L && writes > 0))
            val nextBiscomPoll = controllerMap["biscomNextPollTick"]?.let { parseIntegralLong(it, "biscomNextPollTick") }
            val nextBiscomIrqLine = when (val rawLine = controllerMap["biscomIrqLine"]) {
                null -> when (val rawHigh = controllerMap["biscomIrqHigh"]) {
                    null -> 0
                    is Boolean -> if (rawHigh) BISCOM_READY_FALLING_IRQ else 0
                    else -> throw IllegalArgumentException("Invalid biscomIrqHigh in SPI snapshot")
                }
                else -> parseIntegralInt(rawLine, "biscomIrqLine").also {
                    require(it == BISCOM_READY_FALLING_IRQ || it == BISCOM_READY_RISING_IRQ) { "Invalid biscomIrqLine in SPI snapshot" }
                }
            }
            val nextLenscomPoll = controllerMap["lenscomNextPollTick"]?.let { parseIntegralLong(it, "lenscomNextPollTick") }
            val nextLenscomIrqLine = controllerMap["lenscomIrqLine"]?.let {
                parseIntegralInt(it, "lenscomIrqLine").also { line ->
                    require(
                        line == LENSCOM_READY_RISING_IRQ || line == LENSCOM_READY_FALLING_IRQ ||
                            line == LENSCOM_AUX_RISING_IRQ || line == LENSCOM_AUX_FALLING_IRQ,
                    ) { "Invalid lenscomIrqLine in SPI snapshot" }
                }
            } ?: 0
            return ParsedSnapshot(nextControl, nextEnable, nextDivider, nextCauses, nextTx, nextRx, nextPending, nextEventCount, nextEvents, request, writes, nextBiscomPoll, nextBiscomIrqLine, nextLenscomPoll, nextLenscomIrqLine)
        }
        fun applySnapshot(parsed: ParsedSnapshot) {
            control = parsed.control
            enable = parsed.enable
            divider = parsed.divider
            causes = parsed.causes
            requestEnable = parsed.requestEnable
            requestWrites = parsed.requestWrites
            tx.clear()
            tx.addAll(parsed.txWords)
            rx.clear()
            rx.addAll(parsed.rxWords)
            pending = parsed.pendingShift
            eventCount = parsed.eventCount
            events.restore(parsed.eventEntries)
            panel?.resetCursor()
            if (panel != null && clockIndex == 5 && continuous && parsed.eventEntries.isNotEmpty()) {
                parsed.eventEntries.forEachIndexed { index, row ->
                    panel.exchange(parsed.eventCount - parsed.eventEntries.size + index, row.getValue("bits") as Int, row.getValue("tx") as Int)
                }
            }
            biscomNextPollTick = parsed.biscomNextPollTick
            biscomIrqLine = parsed.biscomIrqLine
            lenscomNextPollTick = parsed.lenscomNextPollTick
            lenscomIrqLine = parsed.lenscomIrqLine
            lenscomReadyArmTick = -1L
            lenscomSawFocusBusy = false
            lenscomLastSpiTick = -1_000_000L
            syncMirror()
        }
    }
}
