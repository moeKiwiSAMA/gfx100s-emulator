package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

/** Selected SDHCI memory-card model. Card instances, GFX applicability and tick delays are HYP. */
internal class SdTransactionHost(context: ComponentContext, config: Map<String, Any>) :
    ScalarComponent("soc-sd-host", context, config), StatefulComponent {
    companion object {
        fun port(slot: Int, addressExtension: Boolean = false) =
            if (addressExtension) "soc-sd-host.transaction-address-$slot" else "soc-sd-host.transaction-$slot"
        fun portForProfile(slot: Int, profile: String) = when (profile) {
            SdHostFactory.UHS_PHY_PROFILE -> "soc-sd-host.transaction-v9-$slot"
            SdHostFactory.VENDOR_PHY_PROFILE -> "soc-sd-host.transaction-v8-$slot"
            SdHostFactory.ADDRESS_PROFILE -> "soc-sd-host.transaction-address-$slot"
            else -> "soc-sd-host.transaction-$slot"
        }
        fun addressHighPort(slot: Int) = "soc-sd-host.address-high-$slot"
        fun addressHighPort(slot: Int, vendorPhy: Boolean) =
            if (vendorPhy) "soc-sd-host.address-high-v8-$slot" else addressHighPort(slot)
        fun addressHighPortForProfile(slot: Int, profile: String) = when (profile) {
            SdHostFactory.UHS_PHY_PROFILE -> "soc-sd-host.address-high-v9-$slot"
            SdHostFactory.VENDOR_PHY_PROFILE -> "soc-sd-host.address-high-v8-$slot"
            else -> addressHighPort(slot)
        }
        fun vendorAPort(slot: Int, uhsPhy: Boolean = false) = if (uhsPhy) "soc-sd-host.vendor-a-v9-$slot" else "soc-sd-host.vendor-a-$slot"
        fun phy6060Port(slot: Int, uhsPhy: Boolean = false) = if (uhsPhy) "soc-sd-host.phy6060-v9-$slot" else "soc-sd-host.phy6060-$slot"
        fun phy607cPort(slot: Int, uhsPhy: Boolean = false) = if (uhsPhy) "soc-sd-host.phy607c-v9-$slot" else "soc-sd-host.phy607c-$slot"
        fun phy6094Port(slot: Int, uhsPhy: Boolean = false) = if (uhsPhy) "soc-sd-host.phy6094-v9-$slot" else "soc-sd-host.phy6094-$slot"
        fun uhs4100Port(slot: Int) = "soc-sd-host.uhs4100-$slot"
        fun uhs4108Port(slot: Int) = "soc-sd-host.uhs4108-$slot"
        fun uhs4114Port(slot: Int) = "soc-sd-host.uhs4114-$slot"
        fun uhs4124Port(slot: Int) = "soc-sd-host.uhs4124-$slot"
        fun phyTrimPort(slot: Int) = "soc-sd-host.phy-trim-$slot"
        fun uhs2DmaPort(slot: Int) = "soc-sd-host.uhs2-dma-$slot"
        fun uhs26280Port(slot: Int) = "soc-sd-host.uhs2-6280-$slot"
        fun uhs2CmdPort(slot: Int) = "soc-sd-host.uhs2-cmd-$slot"
        fun uhs2629cPort(slot: Int) = "soc-sd-host.uhs2-629c-$slot"
        fun uhs2ErrPort(slot: Int) = "soc-sd-host.uhs2-err-$slot"
        val vendorPhyFields = listOf("vendor41e0", "vendor41e4", "phy6060", "phy607c", "phy6094")
        val uhsPhyU16Fields = listOf("uhs4100", "uhs4108", "uhs410a", "uhs410c", "uhs410e", "uhs2_6280", "uhs2_62c0", "uhs2_62c2")
        val uhsPhyU32Fields = listOf(
            "uhs4114", "uhs4124", "phy6020", "phy6024",
            "uhs2_6258", "uhs2_625c", "uhs2_6284", "uhs2_6288", "uhs2_628c", "uhs2_6290", "uhs2_629c",
            "uhs2_62c4", "uhs2_62c8", "uhs2_62cc",
        )
        val uhsPhyFields = uhsPhyU16Fields + uhsPhyU32Fields
        val uhsPhyReadOnlyFields = setOf("uhs410c", "uhs410e")
        private val lines = intArrayOf(230, 237)
        private const val NORMAL_EVENTS = 0x00fb
        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "SD integral value required" }
            return (value as Number).toLong()
        }
        private fun obj(value: Any?): Map<String, Any?> {
            require(value is Map<*, *> && value.keys.all { it is String })
            return value.entries.associate { it.key as String to it.value }
        }
        private fun suppressed(name: String) = (0..1).any { s -> name in setOf(
            "soc-sd-host.block4006-$s", "soc-sd-host.present4024-$s", "soc-sd-host.host4028-$s",
            "soc-sd-host.clock-$s-0", "soc-sd-host.control403e-$s") }
    }
    private val legacy: SdInitHost
    private val profileName = config["profile"] as String
    private val uhsPhy = profileName == SdHostFactory.UHS_PHY_PROFILE
    private val vendorPhy = profileName in setOf(SdHostFactory.VENDOR_PHY_PROFILE, SdHostFactory.UHS_PHY_PROFILE)
    private val addressExtension = profileName in setOf(SdHostFactory.ADDRESS_PROFILE, SdHostFactory.VENDOR_PHY_PROFILE, SdHostFactory.UHS_PHY_PROFILE)
    private val initialAddressHighBits: List<Long>
    private val initialVendorPhyWords: List<Map<String, Long>>
    private val initialUhsPhyWords: List<Map<String, Long>>
    private val slots: List<Slot>
    private val claims: List<String>
    private val commandTicks: Long
    private val blockTicks: Long
    private val resetTicks: Long
    private var initialSlots: List<Map<String, Any?>>
    private var identitySnapshots: Map<String, Any?>
    private val inputHash: String
    private var storage: CheckedBlockStoragePort? = null
    private var sink: InterruptSink? = null
    private var eventSubscription: AutoCloseable? = null
    private var started = false
    private var closed = false
    private var commandCount = 0L
    private var completedCommands = 0L
    private var transferCount = 0L
    private var transferredBytes = 0L
    private var mediaBytes = 0L
    private var failedTransactions = 0L

    init {
        require(profileName in setOf(SdHostFactory.CARD_PROFILE, SdHostFactory.ADDRESS_PROFILE, SdHostFactory.VENDOR_PHY_PROFILE, SdHostFactory.UHS_PHY_PROFILE))
        val file = config["inputFile"] as String
        require(file.isNotBlank()) { "Explicit SD transaction input required" }
        val input = context.readData(file)
        val keys = setOf("schema", "profile", "legacy", "cards", "commandTicks", "blockTicks", "resetTicks") +
            (if (addressExtension) setOf("addressHighBits") else emptySet()) +
            (if (vendorPhy) setOf("vendorPhyWords") else emptySet()) +
            (if (uhsPhy) setOf("uhsPhyWords") else emptySet())
        require(input.keys == keys && integer(input["schema"]) == 1L && input["profile"] == profileName)
        initialAddressHighBits = if (addressExtension) {
            val high = obj(input["addressHighBits"])
            require(high.keys == setOf("0", "1")) { "Two explicit SD address high bits required" }
            (0..1).map { integer(high[it.toString()]).also { bit -> require(bit in 0..1) { "SD address high bit must be 0 or 1" } } }
        } else listOf(0L, 0L)
        initialVendorPhyWords = if (vendorPhy) {
            val vp = obj(input["vendorPhyWords"])
            require(vp.keys == setOf("0", "1")) { "Two explicit SD vendor/PHY slot word maps required" }
            (0..1).map { slotIdx ->
                val row = obj(vp[slotIdx.toString()])
                require(row.keys == vendorPhyFields.toSet()) { "Exact SD vendor/PHY register fields required" }
                vendorPhyFields.associateWith { field ->
                    integer(row[field]).also { w -> require(w in 0L..0xffffffffL) { "SD vendor/PHY $field requires u32" } }
                }
            }
        } else listOf(emptyMap(), emptyMap())
        initialUhsPhyWords = if (uhsPhy) {
            val up = obj(input["uhsPhyWords"])
            require(up.keys == setOf("0", "1")) { "Two explicit SD UHS/PHY slot word maps required" }
            (0..1).map { slotIdx ->
                val row = obj(up[slotIdx.toString()])
                require(row.keys == uhsPhyFields.toSet()) { "Exact SD UHS/PHY register fields required" }
                uhsPhyFields.associateWith { field ->
                    val maxVal = if (field in uhsPhyU16Fields) 0xffffL else 0xffffffffL
                    integer(row[field]).also { w -> require(w in 0L..maxVal) { "SD UHS/PHY $field out of range" } }
                }
            }
        } else listOf(emptyMap(), emptyMap())
        fun delay(name: String) = integer(input[name]).also { require(it in 1..1_000_000) { "Positive bounded SD delay required" } }
        commandTicks = delay("commandTicks"); blockTicks = delay("blockTicks"); resetTicks = delay("resetTicks")
        val cards = obj(input["cards"]); require(cards.keys == setOf("0", "1"))
        val identities = (0..1).map { s -> cards[s.toString()]?.let { SdCardIdentity.parse(obj(it)) } }
        val oldInput = obj(input["legacy"])
        require(oldInput["profile"] == SdHostFactory.PHY_PROFILE)
        val captured = linkedMapOf<String, MmioDevice>()
        val proxy = object : ComponentContext by context {
            override fun readData(relative: String) = oldInput
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    require(owner == id && type == MmioDevice::class.java && port is MmioDevice)
                    require(captured.putIfAbsent(name, port as MmioDevice) == null)
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? =
                    captured[name]?.let { type.cast(it) } ?: context.ports.find(name, type)
                override fun removeOwner(owner: String) = error("SD delegate cannot remove registrations")
            }
        }
        legacy = SdInitHost(proxy, mapOf("profile" to SdHostFactory.PHY_PROFILE, "inputFile" to file), hardwareClockStable = true)
        require(captured.size == 28)
        val words = obj(oldInput["initWords"]); val registers = obj(oldInput["initialRegisters"]); val clocks = obj(oldInput["clocks"])
        slots = (0..1).map { s ->
            val row = obj(words[s.toString()]); val regs = obj(registers[s.toString()])
            val clock = obj(clocks[SdClockHost.key(s * 2)])
            Slot(s, identities[s], integer(row["block4006"]).toInt(), integer(row["host4028"]).toInt(),
                integer(regs["control403e"]).toInt(), integer(clock["initialWord"]).toInt(), integer(row["present4024"]),
                initialAddressHighBits[s], initialVendorPhyWords[s], initialUhsPhyWords[s])
        }
        val owned = (0..1).map { portForProfile(it, profileName) } +
            (if (addressExtension) (0..1).map { addressHighPortForProfile(it, profileName) } else emptyList()) +
            (if (vendorPhy) (0..1).flatMap { s -> listOf(vendorAPort(s, uhsPhy), phy6060Port(s, uhsPhy), phy607cPort(s, uhsPhy), phy6094Port(s, uhsPhy)) } else emptyList()) +
            (if (uhsPhy) (0..1).flatMap { s ->
                listOf(
                    uhs4100Port(s), uhs4108Port(s), uhs4114Port(s), uhs4124Port(s), phyTrimPort(s),
                    uhs2DmaPort(s), uhs26280Port(s), uhs2CmdPort(s), uhs2629cPort(s), uhs2ErrPort(s),
                )
            } else emptyList())
        claims = legacy.additionalMmioPorts().filterNot(::suppressed) + owned
        val claimCount = if (uhsPhy) 50 else if (vendorPhy) 30 else if (addressExtension) 22 else 20
        require(claims.size == claimCount && claims.distinct().size == claimCount)
        require((captured.keys + owned).all { context.ports.find(it, MmioDevice::class.java) == null }) { "SD transaction port alias" }
        identitySnapshots = identities.mapIndexed { s, card -> s.toString() to card?.snapshot() }.toMap()
        initialSlots = slots.map { it.snapshot() }
        val identityParts = listOf(legacy.state()["inputCanonicalSha256"], identitySnapshots, commandTicks, blockTicks, resetTicks)
        val identityText = (identityParts +
            (if (addressExtension) listOf(initialAddressHighBits) else emptyList()) +
            (if (vendorPhy) listOf(initialVendorPhyWords) else emptyList()) +
            (if (uhsPhy) listOf(initialUhsPhyWords) else emptyList())).joinToString("\n")
        inputHash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identityText.toByteArray(Charsets.UTF_8)))
        captured.filterKeys { !suppressed(it) }.forEach { (name, device) -> context.ports.register(id, name, MmioDevice::class.java, device) }
        slots.forEach {
            context.ports.register(id, portForProfile(it.slot, profileName), MmioDevice::class.java, it)
            if (addressExtension) context.ports.register(id, addressHighPortForProfile(it.slot, profileName), MmioDevice::class.java, it.addressHighPort)
            if (vendorPhy) {
                context.ports.register(id, vendorAPort(it.slot, uhsPhy), MmioDevice::class.java, it.vendorAPort)
                context.ports.register(id, phy6060Port(it.slot, uhsPhy), MmioDevice::class.java, it.phy6060Port)
                context.ports.register(id, phy607cPort(it.slot, uhsPhy), MmioDevice::class.java, it.phy607cPort)
                context.ports.register(id, phy6094Port(it.slot, uhsPhy), MmioDevice::class.java, it.phy6094Port)
            }
            if (uhsPhy) {
                context.ports.register(id, uhs4100Port(it.slot), MmioDevice::class.java, it.uhs4100Port)
                context.ports.register(id, uhs4108Port(it.slot), MmioDevice::class.java, it.uhs4108Port)
                context.ports.register(id, uhs4114Port(it.slot), MmioDevice::class.java, it.uhs4114Port)
                context.ports.register(id, uhs4124Port(it.slot), MmioDevice::class.java, it.uhs4124Port)
                context.ports.register(id, phyTrimPort(it.slot), MmioDevice::class.java, it.phyTrimPort)
                context.ports.register(id, uhs2DmaPort(it.slot), MmioDevice::class.java, it.uhs2DmaPort)
                context.ports.register(id, uhs26280Port(it.slot), MmioDevice::class.java, it.uhs26280Port)
                context.ports.register(id, uhs2CmdPort(it.slot), MmioDevice::class.java, it.uhs2CmdPort)
                context.ports.register(id, uhs2629cPort(it.slot), MmioDevice::class.java, it.uhs2629cPort)
                context.ports.register(id, uhs2ErrPort(it.slot), MmioDevice::class.java, it.uhs2ErrPort)
            }
        }
    }

    override fun start() {
        check(!closed && !started)
        val media = context.ports.find("sdcard-reader", BlockStoragePort::class.java) as? CheckedBlockStoragePort
            ?: error("SD transaction profile requires checked block storage")
        val irq = context.ports.find("soc-gic.lines", InterruptSink::class.java) ?: error("SD transaction profile requires GIC lines")
        val hotPlug = media as? HotPlugCardControlPort
        storage = media
        slots.forEach { s ->
            s.reconcileWithMedia(media, hotPlug)
        }
        identitySnapshots = slots.mapIndexed { idx, s -> idx.toString() to s.identity?.snapshot() }.toMap()
        legacy.start(); sink = irq
        lines.forEach { irq.setLine(it, false) }
        started = true
        eventSubscription = runCatching {
            context.events.subscribe { event ->
                if (event.source == "sdcard-reader" && started && !closed) {
                    val slotNum = (event.values["slot"] as? Number)?.toInt() ?: return@subscribe
                    if (slotNum !in 1..2) return@subscribe
                    val s = slots[slotNum - 1]
                    when (event.kind) {
                        "hotplug" -> when (event.values["action"]) {
                            "eject" -> s.onCardEjected()
                            "insert" -> {
                                s.onCardInserted(media, hotPlug)
                                identitySnapshots = slots.mapIndexed { idx, slotObj -> idx.toString() to slotObj.identity?.snapshot() }.toMap()
                            }
                        }
                        "write-protect" -> {
                            s.onWriteProtectChanged()
                            identitySnapshots = slots.mapIndexed { idx, slotObj -> idx.toString() to slotObj.identity?.snapshot() }.toMap()
                        }
                    }
                }
            }
        }.getOrNull()
        slots.forEach { it.startClock() }
        initialSlots = slots.map { it.snapshot() }
    }
    override fun additionalMmioPorts() = claims
    override fun interruptRoutes() = lines.map { InterruptRoute("soc-gic.lines", it..it) }
    override fun validateSettings(settings: Map<String, Any>) { check(!closed); require(settings.all { values[it.key] == it.value }) { "SD transactions require restart" } }
    override fun applySettings(settings: Map<String, Any>) = validateSettings(settings)
    override fun close() {
        if (closed) return
        runCatching { eventSubscription?.close() }
        eventSubscription = null
        slots.forEach { it.cancelHost() }
        if (started) lines.forEach { sink!!.setLine(it, false) }
        legacy.close(); closed = true
    }
    override fun state(): Map<String, Any?> = super.state() + mapOf(
        "enabled" to true, "closed" to closed, "started" to started, "legacyState" to legacy.state(),
        "cards" to identitySnapshots, "commandTicks" to commandTicks, "blockTicks" to blockTicks, "resetTicks" to resetTicks,
        "inputCanonicalSha256" to inputHash, "initialSlots" to initialSlots, "slots" to slots.map { it.snapshot() },
        "commandCount" to commandCount, "completedCommands" to completedCommands, "transferCount" to transferCount,
        "transferredBytes" to transferredBytes, "mediaBytes" to mediaBytes, "failedTransactions" to failedTransactions,
        "cardProtocolQualified" to false, "standardModelImplemented" to true,
        "basis" to "SOURCE SDHCI/SD memory transactions with real block storage; synthetic card identity, GFX applicability and tick delays HYP. No original-firmware/card or physical IRQ attestation."
    ) + (if (addressExtension) mapOf(
        "initialAddressHighBits" to (0..1).associate { it.toString() to initialAddressHighBits[it] },
        "addressHighBits" to slots.associate { it.slot.toString() to it.addressHighBit },
        "addressExtensionBasis" to "DAT mode0 writes the buffer high-word bit0 separately from normal-bank low word. Combining it as SDMA address bit32 and retaining the independent latch through host reset are explicit HYP. No readback, low-word carry or mode1 extension is modeled."
    ) else emptyMap()) + (if (vendorPhy) mapOf(
        "initialVendorPhyWords" to (0..1).associate { it.toString() to initialVendorPhyWords[it] },
        "vendorPhyWords" to slots.associate { it.slot.toString() to it.vendorPhyMap() },
        "vendorPhyBasis" to "DAT boot-exercised vendor41e0/vendor41e4 (0x11F1C54) and phy6060/phy607c/phy6094 (0x11F1CD8) promoted to exact component ports. Explicit initial words, full-u32 retention and reset persistence remain HYPOTHESIS; unexercised UHS-I tuning and UHS-II bank B registers remain unmodeled."
    ) else emptyMap()) + (if (uhsPhy) mapOf(
        "initialUhsPhyWords" to (0..1).associate { it.toString() to initialUhsPhyWords[it] },
        "uhsPhyWords" to slots.associate { it.slot.toString() to it.uhsPhyMap() },
        "uhsPhyBasis" to "DAT UHS-I tuning/1.8V (0xFFFC4100..0xFFFC4124), PHY analog trim (0xFFFC6020..0xFFFC6024), and UHS-II Bank B (0xFFFC6258..0xFFFC62CC) registers promoted under opt-in v9. Explicit initial words, full-width retention, and reset persistence remain HYPOTHESIS; unmodeled gap addresses stay strictly refused."
    ) else emptyMap())

    private inner class Pending(val plan: SdCardCommand, val transferMode: Int, val blockSize: Int, val autoStop: Boolean) {
        var done = 0L
        var cursor = 0
        var ready = false
        var dmaPaused = false
        var phase = 0 // command, data, card/auto-stop busy
        val buffer = ByteArray(blockSize.coerceAtLeast(1))
        val bufferView: ByteBuffer = ByteBuffer.wrap(buffer)
        val dma = transferMode and 1 != 0
    }
    private data class ParsedCommandPlan(
        val response: LongArray,
        val responseKind: SdResponseKind,
        val direction: SdDataDirection,
        val dataBytes: Long,
        val mediaOffset: Long?,
        val payload: ByteArray?,
        val busy: Boolean,
        val completionStateName: String?,
        val completionFunctions: IntArray?,
    )
    private data class ParsedPendingState(
        val transferMode: Int,
        val blockSize: Int,
        val autoStop: Boolean,
        val done: Long,
        val cursor: Int,
        val ready: Boolean,
        val dmaPaused: Boolean,
        val phase: Int,
        val buffer: ByteArray,
        val plan: ParsedCommandPlan,
    )
    private data class ParsedSlotState(
        val dmaAddress: Long,
        val addressHighBit: Long,
        val blockSizeWord: Int,
        val blockCount: Int,
        val argument: Long,
        val command: Long,
        val response: LongArray,
        val hostControl: Int,
        val control2: Int,
        val clockControl: Int,
        val normalStatus: Int,
        val errorStatus: Int,
        val normalEnable: Int,
        val errorEnable: Int,
        val normalSignal: Int,
        val errorSignal: Int,
        val reset: Int,
        val timeoutControl: Int,
        val autoError: Int,
        val irqHigh: Boolean,
        val deadline: Long?,
        val clockDeadline: Long?,
        val resetDeadline: Long?,
        val fault: String?,
        val cardSnapshot: SdCardProtocol.ParsedSnapshot?,
        val pendingState: ParsedPendingState?,
        val vendor41e0: Long = 0L,
        val vendor41e4: Long = 0L,
        val phy6094: Long = 0L,
        val uhsPhyWords: Map<String, Long> = emptyMap(),
    )
    private inner class Slot(
        val slot: Int, initialIdentity: SdCardIdentity?, private val initialCount: Int, private val initialHost: Int,
        private val initialControl: Int, private val initialClock: Int, private val initialPresent: Long,
        private val initialAddressHigh: Long, private val initialVendorPhy: Map<String, Long>,
        private val initialUhsPhy: Map<String, Long>,
    ) : TickAwareMmioDevice {
        var identity: SdCardIdentity? = initialIdentity
            private set
        var card: SdCardProtocol? = initialIdentity?.let { SdCardProtocol(it) { isSlotReadOnly() } }
            private set
        private var dmaAddress = 0L
        var addressHighBit = initialAddressHigh
            private set
        private var vendor41e0 = initialVendorPhy["vendor41e0"] ?: 0L
        private var vendor41e4 = initialVendorPhy["vendor41e4"] ?: 0L
        private val phy6060 = initialVendorPhy["phy6060"] ?: 0L
        private val phy607c = initialVendorPhy["phy607c"] ?: 0L
        private var phy6094 = initialVendorPhy["phy6094"] ?: 0L
        private val uhsPhyRegs = LinkedHashMap<String, Long>().apply {
            if (uhsPhy) {
                for (field in uhsPhyFields) put(field, initialUhsPhy[field] ?: 0L)
            }
        }
        private val initialUhs410c = initialUhsPhy["uhs410c"] ?: 0L
        private val initialUhs410e = initialUhsPhy["uhs410e"] ?: 0L
        private var blockSizeWord = 0
        private var blockCount = initialCount
        private var argument = 0L
        private var command = 0L
        private val response = LongArray(4)
        private var hostControl = initialHost
        private var control2 = initialControl
        private var clockControl = initialClock
        private var normalStatus = 0
        private var errorStatus = 0
        private var normalEnable = 0
        private var errorEnable = 0
        private var normalSignal = 0
        private var errorSignal = 0
        private var reset = 0
        private var timeoutControl = 0 // Explicit HYP initial value; timeout clock is not modeled.
        private var autoError = 0
        private var irqHigh = false
        private var deadline: Long? = null
        private var clockDeadline: Long? = null
        private var resetDeadline: Long? = null
        private var pending: Pending? = null
        private var fault: String? = null
        private var bulkScratch = ByteArray(0)
        fun vendorPhyMap(): Map<String, Long> = linkedMapOf(
            "vendor41e0" to vendor41e0,
            "vendor41e4" to vendor41e4,
            "phy6060" to phy6060,
            "phy607c" to phy607c,
            "phy6094" to phy6094,
        )
        fun uhsPhyMap(): Map<String, Long> = LinkedHashMap<String, Long>(uhsPhyFields.size).also { out ->
            for (field in uhsPhyFields) out[field] = uhsPhyRegs[field] ?: 0L
        }
        val addressHighPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 30 + slot
            override fun read(offset: Long, size: Int): Long = error("SD address sideband readback is not qualified")
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 4 && value in 0..1) { "SD address sideband requires exact WRITE4 bit0" }
                val p = pending
                require(p == null || p.dma && p.dmaPaused && p.phase == 1 && fault == null) { "SD address sideband write while active" }
                // Configuration only. The normal low-word write performs resume preflight and schedules work.
                addressHighBit = value
            }
        }
        val vendorAPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 32 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require((offset == 0L || offset == 4L) && size == 4) { "SD vendor-a requires aligned READ4 at +0/+4" }
                return if (offset == 0L) vendor41e0 else vendor41e4
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require((offset == 0L || offset == 4L) && size == 4 && value in 0L..0xffffffffL) { "SD vendor-a requires aligned WRITE4 u32 at +0/+4" }
                if (offset == 0L) vendor41e0 = value else vendor41e4 = value
            }
        }
        val phy6060Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 34 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 4) { "SD phy6060 requires exact READ4" }
                return phy6060
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                error("SD phy6060 is read-only")
            }
        }
        val phy607cPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 36 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 4) { "SD phy607c requires exact READ4" }
                return phy607c
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                error("SD phy607c is read-only")
            }
        }
        val phy6094Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 38 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 4) { "SD phy6094 requires exact READ4" }
                return phy6094
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 4 && value in 0L..0xffffffffL) { "SD phy6094 requires exact WRITE4 u32" }
                phy6094 = value
            }
        }
        val uhs4100Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 40 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 2) { "SD uhs4100 requires exact READ2" }
                return uhsPhyRegs.getValue("uhs4100")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 2 && value in 0L..0xffffL) { "SD uhs4100 requires exact WRITE2 u16" }
                uhsPhyRegs["uhs4100"] = value
            }
        }
        val uhs4108Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 42 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(size == 2) { "SD uhs4108 window requires exact READ2" }
                return when (offset) {
                    0L -> uhsPhyRegs.getValue("uhs4108")
                    2L -> uhsPhyRegs.getValue("uhs410a")
                    4L -> uhsPhyRegs.getValue("uhs410c")
                    6L -> uhsPhyRegs.getValue("uhs410e")
                    else -> throw IllegalArgumentException("Unsupported SD uhs4108 read offset $offset")
                }
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(size == 2 && value in 0L..0xffffL) { "SD uhs4108 window requires exact WRITE2 u16" }
                when (offset) {
                    0L -> uhsPhyRegs["uhs4108"] = value
                    2L -> uhsPhyRegs["uhs410a"] = value
                    4L, 6L -> error("SD uhs410c/uhs410e tuning status registers are read-only")
                    else -> throw IllegalArgumentException("Unsupported SD uhs4108 write offset $offset")
                }
            }
        }
        val uhs4114Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 44 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 4) { "SD uhs4114 requires exact READ4" }
                return uhsPhyRegs.getValue("uhs4114")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 4 && value in 0L..0xffffffffL) { "SD uhs4114 requires exact WRITE4 u32" }
                uhsPhyRegs["uhs4114"] = value
            }
        }
        val uhs4124Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 46 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 4) { "SD uhs4124 requires exact READ4" }
                return uhsPhyRegs.getValue("uhs4124")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 4 && value in 0L..0xffffffffL) { "SD uhs4124 requires exact WRITE4 u32" }
                uhsPhyRegs["uhs4124"] = value
            }
        }
        val phyTrimPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 48 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require((offset == 0L || offset == 4L) && size == 4) { "SD phy-trim requires aligned READ4 at +0/+4" }
                return if (offset == 0L) uhsPhyRegs.getValue("phy6020") else uhsPhyRegs.getValue("phy6024")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require((offset == 0L || offset == 4L) && size == 4 && value in 0L..0xffffffffL) { "SD phy-trim requires aligned WRITE4 u32 at +0/+4" }
                if (offset == 0L) uhsPhyRegs["phy6020"] = value else uhsPhyRegs["phy6024"] = value
            }
        }
        val uhs2DmaPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 50 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require((offset == 0L || offset == 4L) && size == 4) { "SD uhs2-dma requires aligned READ4 at +0/+4" }
                return if (offset == 0L) uhsPhyRegs.getValue("uhs2_6258") else uhsPhyRegs.getValue("uhs2_625c")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require((offset == 0L || offset == 4L) && size == 4 && value in 0L..0xffffffffL) { "SD uhs2-dma requires aligned WRITE4 u32 at +0/+4" }
                if (offset == 0L) uhsPhyRegs["uhs2_6258"] = value else uhsPhyRegs["uhs2_625c"] = value
            }
        }
        val uhs26280Port = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 52 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                require(offset == 0L && size == 2) { "SD uhs2-6280 requires exact READ2" }
                return uhsPhyRegs.getValue("uhs2_6280")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 2 && value in 0L..0xffffL) { "SD uhs2-6280 requires exact WRITE2 u16" }
                uhsPhyRegs["uhs2_6280"] = value
            }
        }
        val uhs2CmdPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 54 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                error("SD uhs2-cmd (+0x284..+0x290) is write-only")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(size == 4 && value in 0L..0xffffffffL) { "SD uhs2-cmd requires aligned WRITE4 u32" }
                when (offset) {
                    0L -> uhsPhyRegs["uhs2_6284"] = value
                    4L -> uhsPhyRegs["uhs2_6288"] = value
                    8L -> uhsPhyRegs["uhs2_628c"] = value
                    12L -> uhsPhyRegs["uhs2_6290"] = value
                    else -> throw IllegalArgumentException("Unsupported SD uhs2-cmd write offset $offset")
                }
            }
        }
        val uhs2629cPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 56 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                error("SD uhs2-629c is write-only")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                require(offset == 0L && size == 4 && value in 0L..0xffffffffL) { "SD uhs2-629c requires exact WRITE4 u32" }
                uhsPhyRegs["uhs2_629c"] = value
            }
        }
        val uhs2ErrPort = object : MmioDevice {
            override val clockOrder = 85
            override val clockIndex = 58 + slot
            override fun read(offset: Long, size: Int): Long {
                usable()
                return when (offset) {
                    0L -> { require(size == 2) { "SD uhs2_62c0 requires READ2" }; uhsPhyRegs.getValue("uhs2_62c0") }
                    2L -> { require(size == 2) { "SD uhs2_62c2 requires READ2" }; uhsPhyRegs.getValue("uhs2_62c2") }
                    4L -> { require(size == 4) { "SD uhs2_62c4 requires READ4" }; uhsPhyRegs.getValue("uhs2_62c4") }
                    8L -> { require(size == 4) { "SD uhs2_62c8 requires READ4" }; uhsPhyRegs.getValue("uhs2_62c8") }
                    12L -> error("SD uhs2_62cc is write-only")
                    else -> throw IllegalArgumentException("Unsupported SD uhs2-err read offset $offset")
                }
            }
            override fun write(offset: Long, size: Int, value: Long) {
                usable()
                when (offset) {
                    0L -> { require(size == 2 && value in 0L..0xffffL) { "SD uhs2_62c0 requires WRITE2 u16" }; uhsPhyRegs["uhs2_62c0"] = value }
                    2L -> { require(size == 2 && value in 0L..0xffffL) { "SD uhs2_62c2 requires WRITE2 u16" }; uhsPhyRegs["uhs2_62c2"] = value }
                    4L -> { require(size == 4 && value in 0L..0xffffffffL) { "SD uhs2_62c4 requires WRITE4 u32" }; uhsPhyRegs["uhs2_62c4"] = value }
                    8L -> { require(size == 4 && value in 0L..0xffffffffL) { "SD uhs2_62c8 requires WRITE4 u32" }; uhsPhyRegs["uhs2_62c8"] = value }
                    12L -> { require(size == 4 && value in 0L..0xffffffffL) { "SD uhs2_62cc requires WRITE4 u32" }; uhsPhyRegs["uhs2_62cc"] = value }
                    else -> throw IllegalArgumentException("Unsupported SD uhs2-err write offset $offset")
                }
            }
        }
        override val clockOrder = 85
        override val clockIndex = 28 + slot
        init { require(initialHost and 0x18 == 0) { "Only SDMA host selection supported" } }
        private fun usable() { check(started && !closed) { "SD transaction host not active" } }
        private fun now(): Long = context.clock.ticks().also { require(it >= 0) }
        private fun after(at: Long, delay: Long) = Math.addExact(at, delay)
        fun startClock() { if (clockControl and 1 != 0 && clockControl and 2 == 0) clockDeadline = after(now(), commandTicks) }
        private fun media() = storage ?: error("SD storage not started")
        private fun memory() = context.ports.find("cpu.memory", DmaMemory::class.java) ?: error("SDMA requires physical cpu.memory")
        private fun effectiveAddress(low: Long = dmaAddress) = (addressHighBit shl 32) or low
        private fun resolveDmaAddress(low: Long, size: Long, write: Boolean): Long {
            val full = effectiveAddress(low)
            val mem = memory()
            if (addressHighBit == 0L || profileName != SdHostFactory.UHS_PHY_PROFILE) {
                mem.check(full, size, write)
                return full
            }
            return try {
                mem.check(full, size, write)
                full
            } catch (highFailure: IllegalArgumentException) {
                try {
                    mem.check(low, size, write)
                    low
                } catch (_: IllegalArgumentException) {
                    throw highFailure
                }
            }
        }
        private fun recomputeIrq() {
            val next = normalStatus and normalSignal != 0 || errorStatus and errorSignal != 0
            if (next != irqHigh) { sink!!.setLine(lines[slot], next); irqHigh = next }
        }
        private fun latch(bits: Int) { normalStatus = normalStatus or (bits and normalEnable and NORMAL_EVENTS); recomputeIrq() }
        private fun status() = normalStatus or if (errorStatus != 0) 0x8000 else 0
        private fun isCardInserted(): Boolean {
            val media = storage ?: return identity != null
            val hotPlug = media as? HotPlugCardControlPort
            if (hotPlug != null) return hotPlug.slotHotPlugStatus(slot + 1).cardDetectAsserted
            return media.size(slot + 1) != null
        }
        private fun isSlotReadOnly(): Boolean {
            val media = storage ?: return identity?.readOnly ?: false
            if (!isCardInserted()) return false
            return runCatching { media.readOnly(slot + 1) }.getOrElse { identity?.readOnly ?: false }
        }
        fun reconcileWithMedia(media: CheckedBlockStoragePort, hotPlug: HotPlugCardControlPort?) {
            val slotNumber = slot + 1
            val mediaSize = media.size(slotNumber)
            if (mediaSize == null) {
                card?.resetToIdle()
                return
            }
            val mediaReadOnly = media.readOnly(slotNumber)
            val reconciled = identity?.withMediaProperties(mediaSize, mediaReadOnly)
                ?: SdCardIdentity.syntheticForMedia(slot, mediaSize, mediaReadOnly)
            if (identity != reconciled || card == null) {
                identity = reconciled
                card = SdCardProtocol(reconciled) { isSlotReadOnly() }
            }
            if (hotPlug == null || hotPlug.isMediaReady(slotNumber)) {
                media.checkAccess(slotNumber, 0, mediaSize, false)
            }
        }
        fun onCardEjected() {
            cancelHost()
            card?.resetToIdle()
            latch(0x80) // SDHCI CARD_REMOVAL
        }
        fun onCardInserted(media: CheckedBlockStoragePort, hotPlug: HotPlugCardControlPort?) {
            cancelHost()
            reconcileWithMedia(media, hotPlug)
            card?.resetToIdle()
            latch(0x40) // SDHCI CARD_INSERTION
        }
        fun onWriteProtectChanged() {
            // PRESENT_STATE bit 19, CSD bit 13, and CMD24/25 query isSlotReadOnly() live.
            // Also keep slot identity snapshot in sync for HTTP/UI telemetry.
            identity = identity?.let { it.withMediaProperties(it.capacityBytes, isSlotReadOnly()) }
        }
        private fun present(): Long {
            val p = pending
            var value = initialPresent and 0x000f0f07L.inv() and 0xffffffffL
            val hotPlug = storage as? HotPlugCardControlPort
            if (hotPlug != null) {
                val st = hotPlug.slotHotPlugStatus(slot + 1)
                if (!st.cardDetectAsserted || st.mediaReady) value = value or (1L shl 17) // CARD_STATE_STABLE
                if (st.cardDetectAsserted && (identity != null || storage?.size(slot + 1) != null)) {
                    value = value or (1L shl 16) or (1L shl 18) or (if (isSlotReadOnly()) 1L shl 19 else 0)
                }
            } else {
                value = value or (1L shl 17) // explicit stable, non-hotplug card model
                if (isCardInserted() && identity != null) {
                    value = value or (1L shl 16) or (1L shl 18) or (if (isSlotReadOnly()) 1L shl 19 else 0)
                }
            }
            if (p != null) {
                if (p.phase == 0) value = value or 1
                if (p.plan.direction != SdDataDirection.NONE || p.plan.busy) value = value or 6
                if (p.phase != 0 && p.plan.direction == SdDataDirection.READ) value = value or 0x200
                if (p.phase != 0 && p.plan.direction == SdDataDirection.WRITE) value = value or 0x100
                if (p.ready && !p.dma) value = value or (if (p.plan.direction == SdDataDirection.READ) 0x800 else 0x400)
            }
            return value
        }
        private fun checkRead(offset: Long, size: Int) {
            usable()
            require(when (offset.toInt()) {
                0x00, 0x0c, 0x20, 0x24 -> size == 4
                0x26 -> size == 2
                0x3c -> size == 2 || size == 4
                0x04, 0x06, 0x28, 0x2c, 0x2e, 0x32, 0x34, 0x36, 0x38, 0x3a, 0x3e -> size == 2
                0x30 -> size == 2 || size == 4
                0x2f -> size == 1
                in 0x10..0x1e -> offset % 2 == 0L && size == 2
                else -> false
            } && offset in 0..63) { "Unsupported SD transaction read width/offset" }
        }
        private fun checkWrite(offset: Long, size: Int, value: Long) {
            usable()
            require(when (offset.toInt()) {
                0x00, 0x0c, 0x20 -> size == 4
                0x04, 0x06, 0x08, 0x0a, 0x28, 0x2c, 0x2e, 0x30, 0x32, 0x34, 0x36, 0x38, 0x3a, 0x3e -> size == 2
                0x2f -> size == 1
                else -> false
            } && offset in 0..63 && value >= 0 && value <= if (size == 4) 0xffffffffL else (1L shl (size * 8)) - 1) { "Unsupported SD transaction write width/offset/value" }
        }
        override fun read(offset: Long, size: Int): Long {
            checkRead(offset, size)
            return when (offset.toInt()) {
                0x00 -> dmaAddress
                0x04 -> blockSizeWord.toLong()
                0x06 -> blockCount.toLong()
                0x0c -> command
                in 0x10..0x1e -> (response[(offset.toInt() - 0x10) / 4] ushr (if (offset.toInt() and 2 == 0) 0 else 16)) and 0xffff
                0x20 -> fifoRead()
                0x24 -> present()
                0x26 -> present() ushr 16
                0x28 -> hostControl.toLong()
                0x2c -> clockControl.toLong()
                0x2e -> timeoutControl.toLong() or (reset.toLong() shl 8)
                0x2f -> reset.toLong()
                0x30 -> status().toLong() or if (size == 4) errorStatus.toLong().shl(16) else 0
                0x32 -> errorStatus.toLong()
                0x34 -> normalEnable.toLong()
                0x36 -> errorEnable.toLong()
                0x38 -> normalSignal.toLong()
                0x3a -> errorSignal.toLong()
                0x3c -> autoError.toLong() or (if (size == 4) control2.toLong() shl 16 else 0L)
                0x3e -> control2.toLong()
                else -> error("validated offset missing")
            }
        }
        override fun write(offset: Long, size: Int, value: Long) {
            checkWrite(offset, size, value)
            val v = value.toInt()
            when (offset.toInt()) {
                0x00 -> {
                    val p = pending
                    if (p != null) {
                        require(p.dmaPaused && p.dma && p.phase == 1) { "SDMA address write while not paused" }
                        val remaining = p.plan.dataBytes - p.done - p.cursor
                        require(value <= 0x100000000L - remaining) { "SDMA address wraps" }
                        resolveDmaAddress(value, remaining, p.plan.direction == SdDataDirection.READ)
                        val next = after(now(), blockTicks)
                        dmaAddress = value; p.dmaPaused = false; deadline = next
                    } else dmaAddress = value
                }
                0x04 -> { require(pending == null && v and 0x8000 == 0 && (v and 0xfff) <= 2048); blockSizeWord = v }
                0x06 -> { require(pending == null); blockCount = v }
                0x08 -> { require(pending?.phase != 0); argument = argument and 0xffff0000L or value }
                0x0a -> { require(pending?.phase != 0); argument = argument and 0xffffL or (value shl 16) }
                0x0c -> issue(value)
                0x20 -> fifoWrite(value)
                0x28 -> { require(pending == null && v and 0x18 == 0) { "Unsupported SD DMA mode or active power change" }; hostControl = v }
                0x2c -> {
                    require(pending == null && v and 0x0038 == 0) { "Unsupported SD clock bits or active clock change" }
                    val next = if (v and 1 != 0 && clockControl and 2 == 0 && clockDeadline == null) after(now(), commandTicks) else clockDeadline
                    clockControl = (v and 2.inv()) or (if (v and 1 != 0) clockControl and 2 else 0)
                    clockDeadline = if (v and 1 == 0) null else next
                }
                0x2e -> {
                    val timeout = v and 0xff
                    require(timeout in 0..14) { "Reserved SD timeout configuration" }
                    val request = v ushr 8
                    val next = prepareReset(request) // All validation and deadline arithmetic precede mutation.
                    timeoutControl = timeout
                    commitReset(request, next)
                }
                0x2f -> { require(v in 1..7); commitReset(v, prepareReset(v)) }
                0x30 -> { normalStatus = normalStatus and (v and NORMAL_EVENTS).inv(); recomputeIrq() }
                0x32 -> { errorStatus = errorStatus and v.inv(); recomputeIrq() }
                0x34 -> { normalEnable = v and 0x7fff; normalStatus = normalStatus and normalEnable; recomputeIrq() }
                0x36 -> { errorEnable = v; errorStatus = errorStatus and v; recomputeIrq() }
                0x38 -> { normalSignal = v and 0x7fff; recomputeIrq() }
                0x3a -> { errorSignal = v; recomputeIrq() }
                0x3e -> { require(pending == null); control2 = v }
            }
        }
        private fun prepareReset(request: Int): Long? {
            require(request in 0..7 && (request == 0 || reset == 0)) { "Unsupported/overlapping SD reset" }
            return if (request == 0) null else after(now(), resetTicks)
        }
        private fun commitReset(request: Int, next: Long?) {
            if (next == null) return // Writing zero cannot clear or restart an in-flight reset.
            reset = request; resetDeadline = next
            if (request and 5 != 0 || pending?.phase == 0) cancelHost()
        }
        private fun issue(word: Long) {
            val mode = (word and 0xffff).toInt(); val cmd = (word ushr 16).toInt()
            val index = (cmd ushr 8) and 63
            val abort = index == 12 && pending?.phase == 1
            require(reset == 0 && (pending == null || abort) && fault == null) { "SD command/data controller busy or faulted" }
            require(hostControl and 0x100 != 0 && clockControl and 7 == 7) { "SD bus power/clock not enabled" }
            val hotPlug = storage as? HotPlugCardControlPort
            if (hotPlug != null) {
                val st = hotPlug.slotHotPlugStatus(slot + 1)
                require(st.cardDetectAsserted) { "SD command with ejected card in slot $slot" }
                require(st.mediaReady) {
                    "SD slot $slot card detected (CARD_DET=0) but mount/readiness delay not elapsed (tick=${now()} < readyAt=${st.readyAtTick})"
                }
            } else {
                require(isCardInserted()) { "SD command with no card" }
            }
            if (card == null) {
                storage?.let { reconcileWithMedia(it, hotPlug) }
            }
            val c = card ?: error("SD command with no card")
            require(mode and 0xffc8 == 0 && cmd and 0xc004 == 0 &&
                (cmd and 0xc0 == 0 || index == 12 && cmd and 0xc0 == 0xc0)) { "Unsupported SD transfer/command mode" }
            val multi = mode and 0x20 != 0; val auto = mode and 0x04 != 0
            require(!multi || mode and 2 != 0 && auto) { "Multiple-block mode requires bounded count and Auto CMD12" }
            require(!auto || multi)
            if (index in listOf(17, 18, 24, 25)) require(multi == (index == 18 || index == 25)) { "Card/host multi-block mode differs" }
            val blocks = if (multi) blockCount.also { require(it > 0) } else 1
            val size = blockSizeWord and 0xfff
            val plan = c.prepare(index, argument, size, blocks, auto)
            require(plan.response.size == 4 && plan.response.all { it in 0..0xffffffffL })
            val responseBits = cmd and 3
            val hasData = plan.direction != SdDataDirection.NONE
            require(responseBits == when (plan.responseKind) { SdResponseKind.NONE -> 0; SdResponseKind.SHORT -> if (plan.busy && !hasData) 3 else 2; SdResponseKind.LONG -> 1 }) { "Card/host response type differs" }
            // The host requested a data phase, but the card can reject the command in R1
            // before any blocks reach the FIFO or DMA.
            val rejectedWrite = (index == 24 || index == 25) && plan.writeProtectViolation
            require((cmd and 0x20 != 0) == (hasData || rejectedWrite))
            if (rejectedWrite) {
                require(mode and 0x10 == 0) { "Read transfer mode on a write command" }
            } else {
                require(if (hasData) size > 0 && plan.dataBytes == size.toLong() * blocks && (mode and 0x10 != 0) == (plan.direction == SdDataDirection.READ) else mode == 0)
            }
            val bytes = plan.dataBytes
            require(bytes in 0..(65535L * 2048))
            plan.mediaOffset?.let { media().checkAccess(slot + 1, it, bytes, plan.direction == SdDataDirection.WRITE) }
            if (hasData && mode and 1 != 0) {
                require(dmaAddress <= 0x100000000L - bytes) { "SDMA address wraps" }
                resolveDmaAddress(dmaAddress, bytes, plan.direction == SdDataDirection.READ)
            }
            require(commandCount < Long.MAX_VALUE); Math.addExact(transferredBytes, bytes)
            val next = after(now(), commandTicks)
            val accepted = Pending(plan, if (rejectedWrite) 0 else mode, if (hasData) size else 0, auto && hasData)
            plan.accept()
            command = word; pending = accepted; deadline = next; commandCount++
        }
        override fun nextDeadline(): Long? {
            var earliest = deadline
            val c = clockDeadline
            if (c != null && (earliest == null || c < earliest)) earliest = c
            val r = resetDeadline
            if (r != null && (earliest == null || r < earliest)) earliest = r
            return earliest
        }
        override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
        override fun beforeInstruction(tick: Long) {
            usable(); require(tick >= 0)
            var events = 0
            while (true) {
                val at = nextDeadline() ?: break
                if (at > tick) break
                check(++events <= 131_074) { "SD event budget exceeded" }
                if (clockDeadline == at) { clockDeadline = null; if (clockControl and 1 != 0) clockControl = clockControl or 2 }
                if (resetDeadline == at) { finishReset(); continue }
                if (deadline == at) {
                    deadline = null
                    val p = pending ?: continue
                    try {
                        if (uhsPhy && p.phase == 1 && p.dma && !p.ready && p.cursor == 0 && p.plan.mediaOffset != null) {
                            events += advanceBulkSdma(p, at, tick)
                        } else {
                            advance(p, at)
                        }
                    } catch (failure: Exception) {
                        p.ready = false; deadline = null; fault = failure.message?.take(512) ?: failure.javaClass.simpleName
                        failedTransactions++; throw failure
                    }
                }
            }
        }
        private fun advanceBulkSdma(p: Pending, at: Long, tick: Long): Int {
            val boundary = 4096L shl ((blockSizeWord ushr 12) and 7)
            val bytesToBoundary = boundary - (dmaAddress % boundary)
            val remainingBytes = p.plan.dataBytes - p.done
            val maxBlocksByBoundary = (bytesToBoundary / p.blockSize).toInt()
            val maxBlocksByRemaining = (remainingBytes / p.blockSize).toInt()
            val maxBulkBlocks = (256 * 1024) / p.blockSize
            val dueBlocks = (((tick - at) / blockTicks) + 1L).coerceAtMost(maxBulkBlocks.toLong()).toInt()
            val bulkBlocks = minOf(maxBlocksByBoundary, maxBlocksByRemaining, dueBlocks)
            if (bulkBlocks <= 1) {
                advance(p, at)
                return 0
            }
            val totalBytes = bulkBlocks * p.blockSize
            val isRead = p.plan.direction == SdDataDirection.READ
            val mediaOffset = p.plan.mediaOffset!! + p.done
            val memory = memory()
            val address = resolveDmaAddress(dmaAddress, totalBytes.toLong(), isRead)
            val directSeg = (memory as? DirectGuestRamPort)?.sliceDirect(address, totalBytes.toLong(), isRead)
            if (directSeg != null) {
                val directView = directSeg.asByteBuffer()
                if (isRead) {
                    require(media().read(slot + 1, mediaOffset, directView) == totalBytes) { "Short SD media read" }
                    memory.notifyDirectWrite(address, totalBytes.toLong())
                } else {
                    media().checkAccess(slot + 1, mediaOffset, totalBytes.toLong(), true)
                    require(media().write(slot + 1, mediaOffset, directView) == totalBytes) { "Short SD media write" }
                }
            } else {
                if (bulkScratch.size < totalBytes) {
                    bulkScratch = ByteArray(totalBytes)
                }
                val view = ByteBuffer.wrap(bulkScratch, 0, totalBytes)
                if (isRead) {
                    require(media().read(slot + 1, mediaOffset, view) == totalBytes) { "Short SD media read" }
                    view.flip()
                    memory.write(address, view)
                    require(!view.hasRemaining()) { "Short SDMA transfer" }
                } else {
                    media().checkAccess(slot + 1, mediaOffset, totalBytes.toLong(), true)
                    memory.read(address, view)
                    require(!view.hasRemaining()) { "Short SDMA transfer" }
                    view.flip()
                    require(media().write(slot + 1, mediaOffset, view) == totalBytes) { "Short SD media write" }
                }
            }
            dmaAddress = (dmaAddress + totalBytes) and 0xffffffffL
            transferredBytes += totalBytes
            mediaBytes += totalBytes
            p.done += totalBytes
            p.cursor = 0
            p.ready = false
            if (p.transferMode and 2 != 0) blockCount -= bulkBlocks
            val lastAt = after(at, (bulkBlocks - 1L) * blockTicks)
            if (p.done == p.plan.dataBytes) {
                if (p.autoStop || p.plan.direction == SdDataDirection.WRITE) {
                    p.phase = 2
                    deadline = after(lastAt, blockTicks)
                } else {
                    complete(p)
                }
            } else if (dmaAddress % boundary == 0L) {
                p.dmaPaused = true
                deadline = null
                latch(8)
            } else {
                deadline = after(lastAt, blockTicks)
            }
            return bulkBlocks - 1
        }
        private fun advance(p: Pending, at: Long) {
            when (p.phase) {
                0 -> {
                    p.plan.response.copyInto(response); completedCommands++; latch(1)
                    if (p.plan.direction == SdDataDirection.NONE) {
                        if (p.plan.busy) { p.phase = 2; deadline = after(at, blockTicks) }
                        else { p.plan.complete(); pending = null }
                    } else { p.phase = 1; deadline = after(at, blockTicks) }
                }
                1 -> {
                    if (!p.ready) {
                        p.cursor = 0
                        if (p.plan.direction == SdDataDirection.READ) {
                            val payload = p.plan.payload
                            if (payload != null) payload.copyInto(p.buffer, 0, p.done.toInt(), (p.done + p.blockSize).toInt())
                            else {
                                p.bufferView.clear()
                                p.bufferView.limit(p.blockSize)
                                require(media().read(slot + 1, p.plan.mediaOffset!! + p.done, p.bufferView) == p.blockSize) { "Short SD media read" }
                            }
                        } else if (!p.dma) {
                            p.buffer.fill(0)
                        }
                        p.ready = true
                    }
                    if (p.dma) dmaChunk(p, at) else latch(if (p.plan.direction == SdDataDirection.READ) 0x20 else 0x10)
                }
                2 -> complete(p)
            }
        }
        private fun dmaChunk(p: Pending, at: Long) {
            val boundary = 4096L shl ((blockSizeWord ushr 12) and 7)
            val bytes = minOf((p.blockSize - p.cursor).toLong(), boundary - dmaAddress % boundary).toInt()
            val range = p.bufferView
            range.limit(p.cursor + bytes)
            range.position(p.cursor)
            val memory = memory()
            val address = resolveDmaAddress(dmaAddress, bytes.toLong(), p.plan.direction == SdDataDirection.READ)
            if (p.plan.direction == SdDataDirection.READ) memory.write(address, range) else memory.read(address, range)
            require(!range.hasRemaining()) { "Short SDMA transfer" }
            p.cursor += bytes; dmaAddress = (dmaAddress + bytes) and 0xffffffffL; transferredBytes += bytes
            if (p.cursor == p.blockSize) finishBlock(p, at)
            if (pending === p && p.phase == 1 && dmaAddress % boundary == 0L && p.done + p.cursor < p.plan.dataBytes) {
                p.dmaPaused = true; deadline = null; latch(8)
            } else if (pending === p && p.phase == 1) deadline = after(at, blockTicks)
        }
        private fun fifoRead(): Long {
            val p = pending ?: error("SD FIFO not armed")
            require(fault == null && p.phase == 1 && !p.dma && p.ready && p.plan.direction == SdDataDirection.READ) { "SD FIFO read not ready" }
            val bytes = minOf(4, p.blockSize - p.cursor)
            val at = now(); after(at, blockTicks)
            var result = 0L
            repeat(bytes) { result = result or ((p.buffer[p.cursor + it].toLong() and 255) shl (it * 8)) }
            p.cursor += bytes; transferredBytes += bytes
            if (p.cursor == p.blockSize) finishBlock(p, at)
            return result // unconsumed high lanes are explicitly zero in this HYP implementation
        }
        private fun fifoWrite(value: Long) {
            val p = pending ?: error("SD FIFO not armed")
            require(fault == null && p.phase == 1 && !p.dma && p.ready && p.plan.direction == SdDataDirection.WRITE) { "SD FIFO write not ready" }
            val bytes = minOf(4, p.blockSize - p.cursor); val at = now(); after(at, blockTicks)
            repeat(bytes) { p.buffer[p.cursor + it] = (value ushr (it * 8)).toByte() }
            p.cursor += bytes; transferredBytes += bytes
            if (p.cursor == p.blockSize) {
                try { finishBlock(p, at) } catch (failure: Exception) {
                    p.ready = false; deadline = null; fault = failure.message?.take(512) ?: failure.javaClass.simpleName
                    failedTransactions++; throw failure
                }
            }
        }
        private fun finishBlock(p: Pending, at: Long) {
            if (p.plan.direction == SdDataDirection.WRITE) {
                val offset = requireNotNull(p.plan.mediaOffset) { "Missing media offset for SD write" }
                media().checkAccess(slot + 1, offset + p.done, p.blockSize.toLong(), true)
                p.bufferView.clear()
                p.bufferView.limit(p.blockSize)
                require(media().write(slot + 1, offset + p.done, p.bufferView) == p.blockSize) { "Short SD media write" }
            }
            if (p.plan.mediaOffset != null) mediaBytes += p.blockSize
            p.done += p.blockSize; p.cursor = 0; p.ready = false
            if (p.transferMode and 2 != 0) blockCount--
            if (p.done == p.plan.dataBytes) {
                if (p.autoStop || p.plan.direction == SdDataDirection.WRITE) { p.phase = 2; deadline = after(at, blockTicks) }
                else complete(p)
            } else deadline = after(at, blockTicks)
        }
        private fun complete(p: Pending) {
            // Auto CMD12 R1 reports state when STOP is received, before completion.
            if (p.autoStop) response[3] = card!!.status()
            p.plan.complete()
            if (p.plan.direction == SdDataDirection.WRITE) {
                storage?.flush(slot + 1)
            }
            if (p.plan.direction != SdDataDirection.NONE) transferCount++
            pending = null; deadline = null; latch(2)
        }
        fun cancelHost() { pending = null; deadline = null; fault = null }
        private fun finishReset() {
            val request = reset
            if (request and 1 != 0) {
                dmaAddress = 0; blockSizeWord = 0; blockCount = 0; argument = 0; command = 0; response.fill(0)
                hostControl = 0; control2 = 0; clockControl = 0; clockDeadline = null
                timeoutControl = 0
                normalStatus = 0; errorStatus = 0; normalEnable = 0; errorEnable = 0; normalSignal = 0; errorSignal = 0; autoError = 0
            } else {
                if (request and 2 != 0) { normalStatus = normalStatus and 1.inv(); command = 0 }
                if (request and 4 != 0) { normalStatus = normalStatus and 0x3e.inv(); errorStatus = 0; autoError = 0 }
            }
            reset = 0; resetDeadline = null; recomputeIrq()
        }
        fun buildInitialSnapshot(initialClockDeadline: Long?): Map<String, Any?> = mapOf(
            "slot" to slot, "dmaAddress" to 0L, "blockSize" to 0, "blockCount" to initialCount,
            "argument" to 0L, "command" to 0L, "response" to listOf(0L, 0L, 0L, 0L), "hostControl" to initialHost,
            "control2" to initialControl, "clockControl" to initialClock, "normalStatus" to 0, "errorStatus" to 0,
            "normalEnable" to 0, "errorEnable" to 0, "normalSignal" to 0, "errorSignal" to 0,
            "reset" to 0, "timeoutControl" to 0, "autoError" to 0, "irqHigh" to false, "deadline" to null,
            "clockDeadline" to initialClockDeadline, "resetDeadline" to null, "commandPending" to false,
            "dataPending" to false, "dmaPaused" to false, "dataDone" to null, "bufferCursor" to null,
            "bufferReady" to false, "fault" to null, "card" to identity?.let { cardIdentity -> SdCardProtocol(cardIdentity) { isSlotReadOnly() }.snapshot() },
        ) + (if (addressExtension) {
            mapOf("addressHighBit" to initialAddressHigh, "effectiveDmaAddress" to (initialAddressHigh shl 32))
        } else emptyMap()) + (if (vendorPhy) {
            mapOf("vendorPhyWords" to initialVendorPhy)
        } else emptyMap()) + (if (uhsPhy) {
            mapOf("uhsPhyWords" to initialUhsPhy)
        } else emptyMap())
        fun expectsInitialClockDeadline(): Boolean = (initialClock and 1) != 0 && (initialClock and 2) == 0
        fun saveSlotSnapshot(): Map<String, Any?> = linkedMapOf<String, Any?>(
            "slot" to slot,
            "dmaAddress" to dmaAddress,
            "addressHighBit" to addressHighBit,
            "blockSizeWord" to blockSizeWord,
            "blockCount" to blockCount,
            "argument" to argument,
            "command" to command,
            "response" to response.toList(),
            "hostControl" to hostControl,
            "control2" to control2,
            "clockControl" to clockControl,
            "rawNormalStatus" to normalStatus,
            "errorStatus" to errorStatus,
            "normalEnable" to normalEnable,
            "errorEnable" to errorEnable,
            "normalSignal" to normalSignal,
            "errorSignal" to errorSignal,
            "reset" to reset,
            "timeoutControl" to timeoutControl,
            "autoError" to autoError,
            "irqHigh" to irqHigh,
            "deadline" to deadline,
            "clockDeadline" to clockDeadline,
            "resetDeadline" to resetDeadline,
            "fault" to fault,
            "card" to card?.saveStateSnapshot(),
            "pending" to pending?.let { activePending ->
                linkedMapOf(
                    "transferMode" to activePending.transferMode,
                    "blockSize" to activePending.blockSize,
                    "autoStop" to activePending.autoStop,
                    "done" to activePending.done,
                    "cursor" to activePending.cursor,
                    "ready" to activePending.ready,
                    "dmaPaused" to activePending.dmaPaused,
                    "phase" to activePending.phase,
                    "bufferBase64" to Base64.getEncoder().encodeToString(activePending.buffer),
                    "plan" to linkedMapOf(
                        "response" to activePending.plan.response.toList(),
                        "responseKind" to activePending.plan.responseKind.name,
                        "direction" to activePending.plan.direction.name,
                        "dataBytes" to activePending.plan.dataBytes,
                        "mediaOffset" to activePending.plan.mediaOffset,
                        "payloadBase64" to activePending.plan.payload?.let { bytes -> Base64.getEncoder().encodeToString(bytes) },
                        "busy" to activePending.plan.busy,
                        "completionState" to activePending.plan.completionStateName,
                        "completionFunctions" to activePending.plan.completionFunctions?.toList(),
                    ),
                )
            },
        ).also { map ->
            if (vendorPhy) map["vendorPhyWords"] = vendorPhyMap()
            if (uhsPhy) map["uhsPhyWords"] = uhsPhyMap()
        }
        fun parseSlotSnapshot(rawSlot: Any?, savedStarted: Boolean): ParsedSlotState {
            val slotMap = rawSlot as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD transaction slot $slot snapshot")
            val expectedKeys = setOf(
                "slot", "dmaAddress", "addressHighBit", "blockSizeWord", "blockCount", "argument", "command",
                "response", "hostControl", "control2", "clockControl", "rawNormalStatus", "errorStatus",
                "normalEnable", "errorEnable", "normalSignal", "errorSignal", "reset", "timeoutControl",
                "autoError", "irqHigh", "deadline", "clockDeadline", "resetDeadline", "fault", "card", "pending",
            ) + (if (vendorPhy) setOf("vendorPhyWords") else emptySet()) + (if (uhsPhy) setOf("uhsPhyWords") else emptySet())
            require(slotMap.keys == expectedKeys) { "Unexpected SD transaction slot $slot snapshot keys" }
            require(SdHostSnapshotSupport.parseIntegralInt(slotMap["slot"], "slot.slot") == slot) { "SD slot index mismatch" }
            val nextDmaAddress = SdHostSnapshotSupport.parseUnsigned32(slotMap["dmaAddress"], "slot.dmaAddress")
            val nextAddressHighBit = SdHostSnapshotSupport.parseIntegralLong(slotMap["addressHighBit"], "slot.addressHighBit")
            if (addressExtension) require(nextAddressHighBit in 0L..1L) { "SD addressHighBit must be 0 or 1" }
            else require(nextAddressHighBit == 0L) { "Non-extension SD profile requires addressHighBit == 0" }
            val nextBlockSizeWord = SdHostSnapshotSupport.parseIntegralInt(slotMap["blockSizeWord"], "slot.blockSizeWord")
            require(nextBlockSizeWord in 0..0x7fff && (nextBlockSizeWord and 0xfff) <= 2048) { "Invalid SD blockSizeWord" }
            val nextBlockCount = SdHostSnapshotSupport.parseIntegralInt(slotMap["blockCount"], "slot.blockCount")
            require(nextBlockCount in 0..0xffff) { "Invalid SD blockCount" }
            val nextArgument = SdHostSnapshotSupport.parseUnsigned32(slotMap["argument"], "slot.argument")
            val nextCommand = SdHostSnapshotSupport.parseUnsigned32(slotMap["command"], "slot.command")
            val rawResponse = slotMap["response"] as? List<*> ?: throw IllegalArgumentException("Invalid SD response list")
            require(rawResponse.size == 4) { "SD response requires 4 words" }
            val nextResponse = LongArray(4) { wordIndex ->
                SdHostSnapshotSupport.parseUnsigned32(rawResponse[wordIndex], "slot.response.$wordIndex")
            }
            val nextHostControl = SdHostSnapshotSupport.parseIntegralInt(slotMap["hostControl"], "slot.hostControl")
            require(nextHostControl in 0..0xffff && (nextHostControl and 0x18) == 0) { "Invalid SD hostControl" }
            val nextControl2 = SdHostSnapshotSupport.parseIntegralInt(slotMap["control2"], "slot.control2")
            require(nextControl2 in 0..0xffff) { "Invalid SD control2" }
            val nextClockControl = SdHostSnapshotSupport.parseIntegralInt(slotMap["clockControl"], "slot.clockControl")
            require(nextClockControl in 0..0xffff) { "Invalid SD clockControl" }
            val nextNormalEnable = SdHostSnapshotSupport.parseIntegralInt(slotMap["normalEnable"], "slot.normalEnable")
            val nextErrorEnable = SdHostSnapshotSupport.parseIntegralInt(slotMap["errorEnable"], "slot.errorEnable")
            val nextNormalSignal = SdHostSnapshotSupport.parseIntegralInt(slotMap["normalSignal"], "slot.normalSignal")
            val nextErrorSignal = SdHostSnapshotSupport.parseIntegralInt(slotMap["errorSignal"], "slot.errorSignal")
            require(nextNormalEnable in 0..0x7fff && nextNormalSignal in 0..0x7fff && nextErrorEnable in 0..0xffff && nextErrorSignal in 0..0xffff) {
                "Invalid SD interrupt enable/signal registers"
            }
            val nextNormalStatus = SdHostSnapshotSupport.parseIntegralInt(slotMap["rawNormalStatus"], "slot.rawNormalStatus")
            val nextErrorStatus = SdHostSnapshotSupport.parseIntegralInt(slotMap["errorStatus"], "slot.errorStatus")
            require(nextNormalStatus in 0..NORMAL_EVENTS && (nextNormalStatus and NORMAL_EVENTS.inv()) == 0 && (nextNormalStatus and nextNormalEnable.inv()) == 0) {
                "Invalid SD rawNormalStatus"
            }
            require(nextErrorStatus in 0..0xffff && (nextErrorStatus and nextErrorEnable.inv()) == 0) { "Invalid SD errorStatus" }
            val nextReset = SdHostSnapshotSupport.parseIntegralInt(slotMap["reset"], "slot.reset")
            val nextTimeout = SdHostSnapshotSupport.parseIntegralInt(slotMap["timeoutControl"], "slot.timeoutControl")
            val nextAutoError = SdHostSnapshotSupport.parseIntegralInt(slotMap["autoError"], "slot.autoError")
            require(nextReset in 0..7 && nextTimeout in 0..14 && nextAutoError in 0..0xffff) { "Invalid SD reset/timeout/autoError" }
            val nextIrqHigh = slotMap["irqHigh"] as? Boolean ?: throw IllegalArgumentException("Invalid SD irqHigh")
            val expectedIrqHigh = if (savedStarted) {
                (nextNormalStatus and nextNormalSignal) != 0 || (nextErrorStatus and nextErrorSignal) != 0
            } else false
            require(nextIrqHigh == expectedIrqHigh) { "SD irqHigh disagrees with status and signal masks" }
            val nextDeadline = slotMap["deadline"]?.let { value -> SdHostSnapshotSupport.parseIntegralLong(value, "slot.deadline").also { parsed -> require(parsed >= 0L) } }
            val nextClockDeadline = slotMap["clockDeadline"]?.let { value -> SdHostSnapshotSupport.parseIntegralLong(value, "slot.clockDeadline").also { parsed -> require(parsed >= 0L) } }
            val nextResetDeadline = slotMap["resetDeadline"]?.let { value -> SdHostSnapshotSupport.parseIntegralLong(value, "slot.resetDeadline").also { parsed -> require(parsed >= 0L) } }
            require((nextReset != 0) == (nextResetDeadline != null)) { "SD reset register and resetDeadline disagree" }
            if (nextClockDeadline != null) require((nextClockControl and 1) != 0) { "SD clockDeadline requires internal clock enable" }
            val nextFault = slotMap["fault"]?.let { rawFault ->
                (rawFault as? String ?: throw IllegalArgumentException("Invalid SD fault")).also { text -> require(text.length in 1..512) }
            }
            if (!savedStarted) {
                require(slotMap["pending"] == null && nextDeadline == null && nextClockDeadline == null && nextResetDeadline == null && nextFault == null) {
                    "Unstarted SD slot cannot carry active deadlines, pending command, or fault"
                }
            }
            val parsedCard = if (identity == null) {
                require(slotMap["card"] == null && slotMap["pending"] == null) { "Empty SD slot cannot carry card or pending state" }
                null
            } else {
                checkNotNull(card).parseStateSnapshot(slotMap["card"])
            }
            val rawPending = slotMap["pending"]
            val parsedPending = if (rawPending == null) {
                require(nextDeadline == null) { "SD transaction deadline without pending command" }
                null
            } else {
                val cardIdentity = checkNotNull(identity)
                val cardSnap = checkNotNull(parsedCard)
                val pendingMap = rawPending as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD pending command map")
                require(pendingMap.keys == setOf("transferMode", "blockSize", "autoStop", "done", "cursor", "ready", "dmaPaused", "phase", "bufferBase64", "plan")) {
                    "Unexpected SD pending command keys"
                }
                val pendingMode = SdHostSnapshotSupport.parseIntegralInt(pendingMap["transferMode"], "pending.transferMode")
                require(pendingMode in 0..0xffff && (pendingMode and 0xffc8) == 0) { "Invalid SD pending transferMode" }
                val pendingBlockSize = SdHostSnapshotSupport.parseIntegralInt(pendingMap["blockSize"], "pending.blockSize")
                require(pendingBlockSize in 0..2048) { "Invalid SD pending blockSize" }
                val pendingAutoStop = pendingMap["autoStop"] as? Boolean ?: throw IllegalArgumentException("Invalid SD pending autoStop")
                require(pendingAutoStop == ((pendingMode and 0x04) != 0)) { "SD pending autoStop disagrees with transferMode" }
                val pendingDone = SdHostSnapshotSupport.parseIntegralLong(pendingMap["done"], "pending.done")
                val pendingCursor = SdHostSnapshotSupport.parseIntegralInt(pendingMap["cursor"], "pending.cursor")
                val pendingReady = pendingMap["ready"] as? Boolean ?: throw IllegalArgumentException("Invalid SD pending ready")
                val pendingDmaPaused = pendingMap["dmaPaused"] as? Boolean ?: throw IllegalArgumentException("Invalid SD pending dmaPaused")
                val pendingPhase = SdHostSnapshotSupport.parseIntegralInt(pendingMap["phase"], "pending.phase")
                require(pendingPhase in 0..2) { "Invalid SD pending phase" }
                val encodedBuffer = pendingMap["bufferBase64"] as? String ?: throw IllegalArgumentException("Invalid SD pending bufferBase64")
                val decodedBuffer = Base64.getDecoder().decode(encodedBuffer)
                require(decodedBuffer.size == pendingBlockSize.coerceAtLeast(1)) { "SD pending buffer byte length mismatch" }
                val planMap = pendingMap["plan"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD pending plan map")
                require(planMap.keys == setOf("response", "responseKind", "direction", "dataBytes", "mediaOffset", "payloadBase64", "busy", "completionState", "completionFunctions")) {
                    "Unexpected SD pending plan keys"
                }
                val rawPlanResponse = planMap["response"] as? List<*> ?: throw IllegalArgumentException("Invalid SD plan response")
                require(rawPlanResponse.size == 4) { "SD plan response requires 4 words" }
                val planResponse = LongArray(4) { wordIndex ->
                    SdHostSnapshotSupport.parseUnsigned32(rawPlanResponse[wordIndex], "plan.response.$wordIndex")
                }
                val responseKindName = planMap["responseKind"] as? String ?: throw IllegalArgumentException("Invalid SD plan responseKind")
                val planResponseKind = SdResponseKind.entries.firstOrNull { candidate -> candidate.name == responseKindName }
                    ?: throw IllegalArgumentException("Unknown SD responseKind $responseKindName")
                val directionName = planMap["direction"] as? String ?: throw IllegalArgumentException("Invalid SD plan direction")
                val planDirection = SdDataDirection.entries.firstOrNull { candidate -> candidate.name == directionName }
                    ?: throw IllegalArgumentException("Unknown SD direction $directionName")
                val planDataBytes = SdHostSnapshotSupport.parseIntegralLong(planMap["dataBytes"], "plan.dataBytes")
                require(planDataBytes in 0L..(65535L * 2048)) { "SD plan dataBytes out of bounds" }
                val planMediaOffset = planMap["mediaOffset"]?.let { offsetValue -> SdHostSnapshotSupport.parseIntegralLong(offsetValue, "plan.mediaOffset") }
                val planPayload = planMap["payloadBase64"]?.let { rawPayload ->
                    val encodedPayload = rawPayload as? String ?: throw IllegalArgumentException("Invalid SD plan payloadBase64")
                    Base64.getDecoder().decode(encodedPayload)
                }
                val planBusy = planMap["busy"] as? Boolean ?: throw IllegalArgumentException("Invalid SD plan busy")
                val completionStateName = planMap["completionState"]?.let { rawState ->
                    rawState as? String ?: throw IllegalArgumentException("Invalid SD plan completionState")
                }
                val completionFunctions = planMap["completionFunctions"]?.let { rawFns ->
                    val fnList = rawFns as? List<*> ?: throw IllegalArgumentException("Invalid SD plan completionFunctions")
                    require(fnList.size == 6) { "SD completionFunctions requires 6 items" }
                    IntArray(6) { groupIndex -> SdHostSnapshotSupport.parseIntegralInt(fnList[groupIndex], "plan.completionFunctions.$groupIndex") }
                }
                checkNotNull(card).validateRestoredCommandCompletion(completionStateName, completionFunctions)
                val hasData = planDirection != SdDataDirection.NONE
                if (!hasData) {
                    require(pendingBlockSize == 0 && planDataBytes == 0L && planMediaOffset == null && planPayload == null && !pendingAutoStop && pendingPhase != 1) {
                        "Non-data SD pending command carries data geometry"
                    }
                } else {
                    require(pendingBlockSize > 0 && planDataBytes > 0L && planDataBytes % pendingBlockSize == 0L) {
                        "Data SD pending command has invalid block geometry"
                    }
                    require((planMediaOffset == null) != (planPayload == null)) {
                        "Data SD pending command requires exactly one of mediaOffset or payloadBase64"
                    }
                    require(((pendingMode and 0x10) != 0) == (planDirection == SdDataDirection.READ)) {
                        "SD pending transferMode direction disagrees with plan direction"
                    }
                }
                if (planMediaOffset != null) {
                    require(planMediaOffset >= 0L && planMediaOffset % 512L == 0L && pendingBlockSize == 512 && planDataBytes <= cardIdentity.capacityBytes - planMediaOffset) {
                        "SD pending mediaOffset out of card capacity"
                    }
                    require(!isSlotReadOnly() || planDirection != SdDataDirection.WRITE) {
                        "Write pending command on read-only SD card"
                    }
                }
                if (planPayload != null) {
                    require(planDirection == SdDataDirection.READ && planPayload.size.toLong() == planDataBytes) {
                        "SD pending register payload size mismatch"
                    }
                }
                if (hasData || planBusy) {
                    require(cardSnap.pendingActive) { "Active SD data/busy pending command requires card pendingDataOrBusy" }
                }
                when (pendingPhase) {
                    0 -> require(pendingDone == 0L && pendingCursor == 0 && !pendingReady && !pendingDmaPaused) {
                        "Invalid SD phase 0 pending counters"
                    }
                    1 -> require(pendingDone in 0L..planDataBytes && pendingDone % pendingBlockSize == 0L && pendingCursor in 0..pendingBlockSize && (!pendingReady || pendingCursor < pendingBlockSize) && (pendingReady || pendingCursor == 0)) {
                        "Invalid SD phase 1 pending cursors"
                    }
                    2 -> require((planBusy || pendingAutoStop || planDirection == SdDataDirection.WRITE) && pendingDone == planDataBytes && pendingCursor == 0 && !pendingReady && !pendingDmaPaused) {
                        "Invalid SD phase 2 pending state"
                    }
                }
                val isDma = (pendingMode and 1) != 0
                if (pendingDmaPaused) require(isDma && pendingPhase == 1 && nextDeadline == null) { "Invalid SD dmaPaused state" }
                if (nextFault == null) {
                    if (pendingPhase != 1) require(nextDeadline != null) { "Active SD command/busy phase requires deadline" }
                    else if (!pendingDmaPaused && (isDma || !pendingReady)) require(nextDeadline != null) { "Active SD data phase requires deadline" }
                    else if (pendingReady && !isDma) require(nextDeadline == null) { "PIO bufferReady state must not schedule block deadline" }
                } else {
                    require(nextDeadline == null && !pendingReady) { "Faulted SD slot cannot stay ready or scheduled" }
                }
                ParsedPendingState(
                    pendingMode, pendingBlockSize, pendingAutoStop, pendingDone, pendingCursor,
                    pendingReady, pendingDmaPaused, pendingPhase, decodedBuffer,
                    ParsedCommandPlan(
                        planResponse, planResponseKind, planDirection, planDataBytes,
                        planMediaOffset, planPayload, planBusy, completionStateName, completionFunctions,
                    ),
                )
            }
            val nextVendor41e0: Long
            val nextVendor41e4: Long
            val nextPhy6094: Long
            if (vendorPhy) {
                val vpMap = slotMap["vendorPhyWords"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD vendorPhyWords for slot $slot")
                require(vpMap.keys == vendorPhyFields.toSet()) { "Unexpected SD vendorPhyWords keys for slot $slot" }
                nextVendor41e0 = SdHostSnapshotSupport.parseUnsigned32(vpMap["vendor41e0"], "slot.vendorPhyWords.vendor41e0")
                nextVendor41e4 = SdHostSnapshotSupport.parseUnsigned32(vpMap["vendor41e4"], "slot.vendorPhyWords.vendor41e4")
                val savedPhy6060 = SdHostSnapshotSupport.parseUnsigned32(vpMap["phy6060"], "slot.vendorPhyWords.phy6060")
                val savedPhy607c = SdHostSnapshotSupport.parseUnsigned32(vpMap["phy607c"], "slot.vendorPhyWords.phy607c")
                require(savedPhy6060 == phy6060) { "Read-only SD phy6060 differs from frozen initial value" }
                require(savedPhy607c == phy607c) { "Read-only SD phy607c differs from frozen initial value" }
                nextPhy6094 = SdHostSnapshotSupport.parseUnsigned32(vpMap["phy6094"], "slot.vendorPhyWords.phy6094")
            } else {
                nextVendor41e0 = 0L
                nextVendor41e4 = 0L
                nextPhy6094 = 0L
            }
            val nextUhsPhyWords: Map<String, Long> = if (uhsPhy) {
                val upMap = slotMap["uhsPhyWords"] as? Map<*, *> ?: throw IllegalArgumentException("Invalid SD uhsPhyWords for slot $slot")
                require(upMap.keys == uhsPhyFields.toSet()) { "Unexpected SD uhsPhyWords keys for slot $slot" }
                LinkedHashMap<String, Long>(uhsPhyFields.size).also { out ->
                    for (field in uhsPhyFields) {
                        val parsed = if (field in uhsPhyU16Fields) {
                            SdHostSnapshotSupport.parseUnsigned16(upMap[field], "slot.uhsPhyWords.$field")
                        } else {
                            SdHostSnapshotSupport.parseUnsigned32(upMap[field], "slot.uhsPhyWords.$field")
                        }
                        if (field == "uhs410c") require(parsed == initialUhs410c) { "Read-only SD uhs410c differs from frozen initial value" }
                        if (field == "uhs410e") require(parsed == initialUhs410e) { "Read-only SD uhs410e differs from frozen initial value" }
                        out[field] = parsed
                    }
                }
            } else {
                emptyMap()
            }
            return ParsedSlotState(
                nextDmaAddress, nextAddressHighBit, nextBlockSizeWord, nextBlockCount, nextArgument, nextCommand,
                nextResponse, nextHostControl, nextControl2, nextClockControl, nextNormalStatus, nextErrorStatus,
                nextNormalEnable, nextErrorEnable, nextNormalSignal, nextErrorSignal, nextReset, nextTimeout,
                nextAutoError, nextIrqHigh, nextDeadline, nextClockDeadline, nextResetDeadline, nextFault,
                parsedCard, parsedPending, nextVendor41e0, nextVendor41e4, nextPhy6094, nextUhsPhyWords,
            )
        }
        fun applySlotSnapshot(parsed: ParsedSlotState) {
            dmaAddress = parsed.dmaAddress
            addressHighBit = parsed.addressHighBit
            if (vendorPhy) {
                vendor41e0 = parsed.vendor41e0
                vendor41e4 = parsed.vendor41e4
                phy6094 = parsed.phy6094
            }
            if (uhsPhy) {
                for (field in uhsPhyFields) {
                    uhsPhyRegs[field] = parsed.uhsPhyWords.getValue(field)
                }
            }
            blockSizeWord = parsed.blockSizeWord
            blockCount = parsed.blockCount
            argument = parsed.argument
            command = parsed.command
            parsed.response.copyInto(response)
            hostControl = parsed.hostControl
            control2 = parsed.control2
            clockControl = parsed.clockControl
            normalStatus = parsed.normalStatus
            errorStatus = parsed.errorStatus
            normalEnable = parsed.normalEnable
            errorEnable = parsed.errorEnable
            normalSignal = parsed.normalSignal
            errorSignal = parsed.errorSignal
            reset = parsed.reset
            timeoutControl = parsed.timeoutControl
            autoError = parsed.autoError
            val previousIrqHigh = irqHigh
            irqHigh = parsed.irqHigh
            if (started && sink != null && sink !is StatefulComponent && previousIrqHigh != parsed.irqHigh) {
                sink!!.setLine(lines[slot], parsed.irqHigh)
            }
            deadline = parsed.deadline
            clockDeadline = parsed.clockDeadline
            resetDeadline = parsed.resetDeadline
            fault = parsed.fault
            if (parsed.cardSnapshot != null) {
                checkNotNull(card).applyStateSnapshot(parsed.cardSnapshot)
            }
            pending = parsed.pendingState?.let { pendingState ->
                val restoredPlan = checkNotNull(card).restoreActiveCommand(
                    pendingState.plan.response,
                    pendingState.plan.responseKind,
                    pendingState.plan.direction,
                    pendingState.plan.dataBytes,
                    pendingState.plan.mediaOffset,
                    pendingState.plan.payload,
                    pendingState.plan.busy,
                    pendingState.plan.completionStateName,
                    pendingState.plan.completionFunctions,
                )
                Pending(restoredPlan, pendingState.transferMode, pendingState.blockSize, pendingState.autoStop).also { restored ->
                    restored.done = pendingState.done
                    restored.cursor = pendingState.cursor
                    restored.ready = pendingState.ready
                    restored.dmaPaused = pendingState.dmaPaused
                    restored.phase = pendingState.phase
                    pendingState.buffer.copyInto(restored.buffer)
                }
            }
        }
        fun snapshot(): Map<String, Any?> = mapOf(
            "slot" to slot, "dmaAddress" to dmaAddress, "blockSize" to blockSizeWord, "blockCount" to blockCount,
            "argument" to argument, "command" to command, "response" to response.toList(), "hostControl" to hostControl,
            "control2" to control2, "clockControl" to clockControl, "normalStatus" to status(), "errorStatus" to errorStatus,
            "normalEnable" to normalEnable, "errorEnable" to errorEnable, "normalSignal" to normalSignal, "errorSignal" to errorSignal,
            "reset" to reset, "timeoutControl" to timeoutControl, "autoError" to autoError, "irqHigh" to irqHigh, "deadline" to deadline,
            "clockDeadline" to clockDeadline, "resetDeadline" to resetDeadline, "commandPending" to (pending?.phase == 0),
            "dataPending" to (pending?.plan?.direction != null && pending?.plan?.direction != SdDataDirection.NONE),
            "dmaPaused" to (pending?.dmaPaused ?: false), "dataDone" to pending?.done, "bufferCursor" to pending?.cursor,
            "bufferReady" to (pending?.ready ?: false), "fault" to fault, "card" to card?.snapshot()
        ) + (if (addressExtension) mapOf("addressHighBit" to addressHighBit, "effectiveDmaAddress" to effectiveAddress()) else emptyMap()) +
            (if (vendorPhy) mapOf("vendorPhyWords" to vendorPhyMap()) else emptyMap()) +
            (if (uhsPhy) mapOf("uhsPhyWords" to uhsPhyMap()) else emptyMap())
    }

    override fun saveSnapshot(): Map<String, Any?> {
        require(!closed) { "SD host closed" }
        return linkedMapOf<String, Any?>(
            "schema" to snapshotSchemaVersion,
            "componentId" to id,
            "kind" to "transaction",
            "scalarValues" to saveScalarValues(),
            "started" to started,
            "addressExtension" to addressExtension,
            "inputCanonicalSha256" to inputHash,
            "legacySnapshot" to legacy.saveSnapshot(),
            "initialClockDeadlines" to initialSlots.map { slotMap -> (slotMap["clockDeadline"] as? Number)?.toLong() },
            "commandCount" to commandCount,
            "completedCommands" to completedCommands,
            "transferCount" to transferCount,
            "transferredBytes" to transferredBytes,
            "mediaBytes" to mediaBytes,
            "failedTransactions" to failedTransactions,
            "slots" to slots.map { slotEntry -> slotEntry.saveSlotSnapshot() },
        ).also { map ->
            if (vendorPhy) map["vendorPhy"] = true
            if (uhsPhy) map["uhsPhy"] = true
        }
    }

    override fun restoreSnapshot(data: Map<String, Any?>) {
        require(!closed) { "SD host closed" }
        val expectedKeys = setOf(
            "schema", "componentId", "kind", "scalarValues", "started", "addressExtension",
            "inputCanonicalSha256", "legacySnapshot", "initialClockDeadlines",
            "commandCount", "completedCommands", "transferCount", "transferredBytes",
            "mediaBytes", "failedTransactions", "slots",
        ) + (if (vendorPhy) setOf("vendorPhy") else emptySet()) + (if (uhsPhy) setOf("uhsPhy") else emptySet())
        require(data.keys == expectedKeys) { "Unexpected SD transaction snapshot keys" }
        require(SdHostSnapshotSupport.parseIntegralInt(data["schema"], "schema") == snapshotSchemaVersion) {
            "Unsupported SD transaction snapshot schema"
        }
        require(data["componentId"] == id && data["kind"] == "transaction") { "Mismatched SD transaction snapshot identity" }
        val rawScalars = SdHostSnapshotSupport.validateScalarValues(data["scalarValues"], values, id)
        val savedStarted = data["started"] as? Boolean ?: throw IllegalArgumentException("Missing SD started flag")
        require(savedStarted == started) { "SD transaction host started state differs" }
        require((data["addressExtension"] as? Boolean) == addressExtension) { "SD addressExtension profile differs" }
        if (vendorPhy) require((data["vendorPhy"] as? Boolean) == true) { "SD vendorPhy profile differs" }
        if (uhsPhy) require((data["uhsPhy"] as? Boolean) == true) { "SD uhsPhy profile differs" }
        require(data["inputCanonicalSha256"] == inputHash) { "SD transaction inputCanonicalSha256 differs" }
        val parsedLegacy = legacy.parseSnapshot(data["legacySnapshot"])
        val rawInitialDeadlines = data["initialClockDeadlines"] as? List<*>
            ?: throw IllegalArgumentException("Invalid SD initialClockDeadlines")
        require(rawInitialDeadlines.size == 2) { "SD initialClockDeadlines requires 2 entries" }
        val parsedInitialDeadlines = (0..1).map { slotIndex ->
            val deadlineValue = rawInitialDeadlines[slotIndex]?.let { rawDeadline ->
                SdHostSnapshotSupport.parseIntegralLong(rawDeadline, "initialClockDeadlines.$slotIndex").also { parsed -> require(parsed >= 0L) }
            }
            if (!savedStarted || !slots[slotIndex].expectsInitialClockDeadline()) {
                require(deadlineValue == null) { "Unexpected initialClockDeadline for slot $slotIndex" }
            } else {
                require(deadlineValue != null && deadlineValue >= commandTicks) { "Missing initialClockDeadline for slot $slotIndex" }
            }
            deadlineValue
        }
        val nextCommandCount = SdHostSnapshotSupport.parseIntegralLong(data["commandCount"], "commandCount")
        val nextCompletedCommands = SdHostSnapshotSupport.parseIntegralLong(data["completedCommands"], "completedCommands")
        val nextTransferCount = SdHostSnapshotSupport.parseIntegralLong(data["transferCount"], "transferCount")
        val nextTransferredBytes = SdHostSnapshotSupport.parseIntegralLong(data["transferredBytes"], "transferredBytes")
        val nextMediaBytes = SdHostSnapshotSupport.parseIntegralLong(data["mediaBytes"], "mediaBytes")
        val nextFailedTransactions = SdHostSnapshotSupport.parseIntegralLong(data["failedTransactions"], "failedTransactions")
        require(
            nextCommandCount >= 0L &&
                nextCompletedCommands in 0L..nextCommandCount &&
                nextTransferCount in 0L..nextCompletedCommands &&
                nextTransferredBytes >= 0L &&
                nextMediaBytes in 0L..nextTransferredBytes &&
                nextMediaBytes % 512L == 0L &&
                nextFailedTransactions >= 0L
        ) { "Invalid SD transaction host counters" }
        if (!savedStarted) {
            require(nextCommandCount == 0L && nextTransferredBytes == 0L && nextFailedTransactions == 0L) {
                "Unstarted SD transaction host must have zero counters"
            }
        }
        val rawSlots = data["slots"] as? List<*> ?: throw IllegalArgumentException("Invalid SD slots list")
        require(rawSlots.size == 2) { "SD transaction snapshot requires 2 slots" }
        val parsedSlots = (0..1).map { slotIndex -> slots[slotIndex].parseSlotSnapshot(rawSlots[slotIndex], savedStarted) }
        val reconstructedInitialSlots = (0..1).map { slotIndex -> slots[slotIndex].buildInitialSnapshot(parsedInitialDeadlines[slotIndex]) }

        restoreScalarValues(rawScalars, SdHostSnapshotSupport.DESCRIPTOR)
        legacy.applyParsedSnapshot(parsedLegacy)
        initialSlots = reconstructedInitialSlots
        commandCount = nextCommandCount
        completedCommands = nextCompletedCommands
        transferCount = nextTransferCount
        transferredBytes = nextTransferredBytes
        mediaBytes = nextMediaBytes
        failedTransactions = nextFailedTransactions
        for (slotIndex in 0..1) {
            slots[slotIndex].applySlotSnapshot(parsedSlots[slotIndex])
        }
    }
}
