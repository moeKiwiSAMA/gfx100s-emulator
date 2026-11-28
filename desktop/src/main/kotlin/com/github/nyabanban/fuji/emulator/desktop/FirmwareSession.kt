package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.IspInterruptPort
import com.github.nyabanban.fuji.emulator.api.LiveViewDmaPort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.cpu.ArmCpuBackend
import com.github.nyabanban.fuji.emulator.cpu.ArmReg
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.desktop.session.NativeMmioShadowCoordinator
import com.github.nyabanban.fuji.emulator.desktop.session.SessionBootLoader
import com.github.nyabanban.fuji.emulator.desktop.session.SessionSnapshotCodec
import com.github.nyabanban.fuji.emulator.desktop.session.SnapshotBudgetSelection
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.*
import java.nio.file.Path
import java.util.ArrayDeque

/** Explicit single-core RAM-loader research mode. Later board/MMU profiles must qualify independently. */
class FirmwareSession(
    private val host: ComponentHost,
    private val home: Path,
    private val config: Map<String, Any>,
    private val catalogRuntime: CatalogRuntime = CatalogRuntime.load(home, config),
    val telemetry: FirmwareTelemetry = FirmwareTelemetry(),
    private val telemetryPublisher: TelemetrySessionPublisher? = null,
    private val onSubMcuColdReset: (() -> Unit)? = null,
) : AutoCloseable {
    fun signalImageInterrupt(line: Int, withStatus: Boolean, statusMask: Long = 1L) {
        host.owner.requireOwner()
        val ispIrq = host.ports.find("soc-isp.interrupt-control", IspInterruptPort::class.java)
        if (ispIrq != null && (line == 106 || line == 296)) {
            if (line == 106) {
                check(!stepInProgress && !stopped) {
                    "IRQ106 status input requires a live session at an owner step boundary"
                }
            }
            ispIrq.signalInterrupt(line, withStatus, statusMask)
            idleRouter?.invalidateSchedule()
            return
        }
        if (line == 106 && (config["discovery"] as? Map<*, *>)?.containsKey("imageIrq106Status") == true) {
            check(!stepInProgress && !stopped && discoveryBus?.hasImageIrq106Status == true) {
                "IRQ106 status input requires a live session at an owner step boundary"
            }
        }
        deliverImageInterrupt(host, discoveryBus, line, withStatus, statusMask)
        idleRouter?.invalidateSchedule()
    }
    fun imageIrq106State(): Map<String, Any?> {
        host.owner.requireOwner()
        return host.ports.find("soc-isp.interrupt-control", IspInterruptPort::class.java)?.irq106State()
            ?: discoveryBus?.irq106State().orEmpty()
    }
    private val log=org.slf4j.LoggerFactory.getLogger(FirmwareSession::class.java)
    private var discoveryBus: DiscoveryBus? = null
    @Volatile private var relaxedFallback: RelaxedPeripheralFallback? = null

    /** Thread-safe read-only `[RELAXED/SUMMARY]` for [FirmwareWatchdog]; returns null outside `relaxed` mode. */
    fun relaxedFeedbackSummaryReport(): String? = relaxedFallback?.formatSummary()

    /** Emits `[RELAXED/SUMMARY]` once at the end of a `relaxed` session; no-op in `discovery`/`strict`. */
    fun logRelaxedSummaryIfNeeded() {
        relaxedFallback?.logSummaryOnClose()
    }
    private var mainDisplay: DisplayPort? = null
    private var ispLiveViewDma: LiveViewDmaPort? = null
    private var lastLcdcLiveViewPumpTick: Long = Long.MIN_VALUE
    private var stage2Ranges: LongArray = LongArray(0)
    private var stage3Ranges: LongArray = LongArray(0)
    private var stage4Ranges: LongArray = LongArray(0)
    @Volatile private var mmioPageLabels: Map<Long, String> = emptyMap()
    private var sliceMmioNanos: Long = 0L
    private var bootSramIspHandoffPending: Boolean = false

    /** Thread-safe lock-free 4 KiB MMIO page label lookup for [AsyncPerfProfiler]. */
    fun resolveMmioPageLabel(pageBase: Long): String? = mmioPageLabels[pageBase]
    /** One local stopped observation feeds both files; later state() calls still take a fresh view. */
    fun exportStoppedState(): Map<String,Any?> {
        host.owner.requireOwner(); check(stopped) { "Discovery export requires stopped session" }
        val started=System.nanoTime()
        val bus=discoveryBus
        val observation=bus?.let(catalogRuntime::observation)
        val observed=System.nanoTime()
        if(bus!=null)bus.journal.export(home.resolve("diagnostics/discovery-"+bus.journal.runId+".json"),bus.regions,checkNotNull(observation))
        val exported=System.nanoTime()
        return state(observation).also {
            log.debug("Firmware probe host stopped timing: observationNanos={}, discoveryExportNanos={}, remainingStateNanos={}",observed-started,exported-observed,System.nanoTime()-exported)
        }
    }
    /** Bounded bring-up export. Full legacy export stays available for its existing consumers. */
    fun exportBoundedStoppedState(maximumRecords: Int = 4096, maximumDiscoveryBytes: Long = 32L * 1024 * 1024): Map<String,Any?> {
        host.owner.requireOwner(); check(stopped)
        require(maximumRecords in 1..65536 && maximumDiscoveryBytes >= 4096)
        val bus = discoveryBus
        val observation = bus?.let { catalogRuntime.boundedObservation(it, maximumRecords) }
        if (bus != null) bus.journal.export(home.resolve("diagnostics/discovery-" + bus.journal.runId + ".json"),
            bus.regions, checkNotNull(observation), maximumDiscoveryBytes)
        val start = maxOf(transactions.observed - transactions.size, transactions.observed - maximumRecords)
        val rows = transactions.tail(start, maximumRecords)
        return state(observation, rows) + mapOf(
            "transactionsTruncated" to (start > 0),
            "transactionExport" to mapOf("selection" to "retained-suffix", "observed" to transactions.observed,
                "retained" to transactions.size, "exported" to rows.size, "omitted" to start),
            "capturePolicy" to mapOf("maximumRecords" to maximumRecords, "maximumDiscoveryBytes" to maximumDiscoveryBytes,
                "scope" to "Observation limits only; guest inputs, journal capacity and stop conditions unchanged"))
    }
    /** Physical RAM layout without materializing traces or hashing RAM. Not a restorable checkpoint. */
    internal fun stoppedRamRegions(): List<Map<String,Any>> {
        host.owner.requireOwner(); check(stopped)
        return checkNotNull(memoryLayout).regions().map { r ->
            mapOf("id" to r.id, "address" to r.address, "size" to r.size, "permissions" to r.permissions)
        }
    }
    internal fun componentState(id: String): Map<String, Any?>? {
        host.owner.requireOwner()
        return host.components().singleOrNull { it.descriptor.id == id }?.component?.state()
    }
    internal fun discoveryBusForTest(): DiscoveryBus? {
        host.owner.requireOwner()
        return discoveryBus
    }
    internal fun routerForTest(): MmioRouter? {
        host.owner.requireOwner()
        return idleRouter
    }
    internal fun selectDiscoveryFixedInput(id: String, value: Long): Map<String, Any?> {
        host.owner.requireOwner(); check(!stopped)
        return checkNotNull(discoveryBus).selectFixedInput(id, value)
    }
    internal fun discoveryFixedInputSelection(id: String): Map<String, Any?> {
        host.owner.requireOwner(); return checkNotNull(discoveryBus).fixedInputSelection(id)
    }
    internal fun diagnosticInputSelection(id: String): Map<String, Any?> {
        host.owner.requireOwner()
        return catalogRuntime.diagnosticInputSelection(checkNotNull(discoveryBus), id)
    }
    internal fun appendA300Samples(id: String, expectedIndex: Int, samples: List<Long>): Map<String, Any?> {
        host.owner.requireOwner()
        check(!stopped)
        diagnosticInputSelection(id)
        return checkNotNull(discoveryBus).appendA300Samples(id, expectedIndex, samples) +
            mapOf("attempts" to attempts, "virtualTick" to virtualTick)
    }
    internal fun selectDiagnosticInputTail(id: String, expectedSampleIndex: Int, samples: List<Long>): Map<String, Any?> {
        host.owner.requireOwner(); check(!stopped)
        return catalogRuntime.selectDiagnosticInputTail(checkNotNull(discoveryBus), id, expectedSampleIndex, samples)
    }
    fun discoveryState(): Map<String,Any?> { host.owner.requireOwner(); return discoveryBus?.let(catalogRuntime::observation) ?: mapOf("executionMode" to catalogRuntime.mode,"accesses" to emptyList<Any>()) }
    private var dynarmic: Dynarmic? = null
    private var pooledDynarmic: Dynarmic? = null
    private var imageDmaMemory: ImageDmaMemory? = null
    private var dynCluster: DynarmicCluster? = null
    private var cpuEngineSelection: CpuEngineConfig.Selection? = null
    private var dynTranslatedFetches = 0L
    private val dynShadow = NativeMmioShadowCoordinator()

    internal fun dynarmicFastMmioStats(): Dynarmic.MmioStats? {
        host.owner.requireOwner()
        val dyn = dynarmic ?: return null
        return dynShadow.readStats(dyn)
    }
    private var fastClusterBatchActive = false
    private val activeBackend: ArmCpuBackend? get() = dynarmic
    private fun currentCoreIndex(): Int =
        if (fastClusterBatchActive) (dynarmic?.activeCore() ?: 0)
        else (dynCluster?.current ?: 0)
    private fun hasCluster(): Boolean = dynCluster != null
    private var attempts = 0L
    private var virtualTick = 0L
    /**
     * Real-silicon 4-core SMP CPU-to-APB-timer clock divider (aggregate cluster instructions per 1 virtual
     * `54 MHz` timer tick). Strictly `1` in `strict`/`discovery` qualification modes; in `relaxed` 4-core
     * Dynarmic mode, `4` accounts for 4-core SMP parallelism (4 serialized cores running 1024-insn quanta
     * advance wall-clock `soc-timer` at 1x per-core rate rather than 4x slowed down), eliminating the artificial
     * 4-core D-cache flush (`0x00059344..0x000593B8`) vs 30 ms RTOS stack-canary watchdog (`0x012C9CBC`) race.
     */
    private var dynVtickDivider = 1
    private var idleRouter: MmioRouter? = null
    private val idleClock by lazy { IdleClock.parse(config["idleClock"]) }
    private var pc = 0L
    private var stopped = true
    private var reason = "awaiting firmware"
    private var frontier: Map<String, Any?>? = null
    private var firmwareSha: String? = null
    private var nativeSha: String? = null
    private var firmwareId: String? = null
    private var stockShutterProfile = false
    private var verifiedShutterCode = false
    private val stillModeTransition = StillModeTransition()
    private var bootMode: String? = null
    private var initialPc = 0L
    private var contracts: Map<String, Any?> = emptyMap()
    private var activeIds: Set<String> = emptySet()
    private val wordExclusive by lazy {
        config["wordExclusive"]?.let {raw->require(clusterProfile.isNotEmpty()) { "word exclusive requires explicit cluster" };require(raw is Map<*,*>);com.github.nyabanban.fuji.emulator.cpu.WordExclusive.parse(raw)}
    }
    private val mmuProfile by lazy {
        val value=config["mmuProfile"] ?: ""
        require(value is String && value in listOf("","arm-short-descriptor-native-experimental-v1")) { "unknown native MMU profile" }
        require(value.isEmpty() || clusterProfile.isNotEmpty()) { "native MMU requires explicit cluster" };value
    }
    private val prefetchAbortProfile by lazy {
        config["prefetchAbortProfile"]?.let {value->
            require(value=="arm32-native-prefetch-entry-experimental-v1") {"unknown native prefetch abort profile"}
            require(mmuProfile.isNotEmpty()&&clusterProfile.isNotEmpty()) {"native prefetch abort requires explicit native MMU and cluster"}
            value as String
        }
    }
    private val exceptionProfile by lazy {
        val value = config["exceptionProfile"] ?: ""
        require(value is String && value in listOf("", "armv7-full-vectors-v1")) {
            "unknown native exception profile"
        }
        value
    }
    private val allowCustomFirmware by lazy {
        CustomFirmwareConfig.allowCustomFirmware(config, catalogRuntime.mode)
    }
    private val customRawImageSpec by lazy {
        CustomFirmwareConfig.parseRawImage(config, catalogRuntime.mode)
    }
    private val customBootMode get() = allowCustomFirmware || customRawImageSpec != null
    private var nativeMpidr: Long? = null
    private val clusterProfile by lazy {
        val value=config["clusterProfile"] ?: ""
        require(value is String && value in listOf("",DynarmicCluster.PROFILE)) { "unknown cluster profile" };value
    }
    private var nativeCbar: Long? = null
    private val clusterReset by lazy {
        config["clusterReset"]?.let {raw->require(clusterProfile.isNotEmpty()) { "reset schedule requires explicit cluster" };com.github.nyabanban.fuji.emulator.cpu.ResetSchedule.parse(raw)}
    }
    private val cbarReads = ArrayDeque<Map<String, Any>>()
    private var cbarReadCount = 0L
    private val cbarInput by lazy {
        config["cbarInput"]?.let { raw -> com.github.nyabanban.fuji.emulator.firmware.number(raw).also {
            require(it in 0..0xffffffffL && it and 0xffff == 0L) { "CBAR input must be aligned to 64 KiB" }
        } }
    }
    private val undefinedExceptionProfile by lazy {
        val value=config["undefinedExceptionProfile"]?:""
        require(value is String && value in listOf("","arm-vfp-disabled-undefined-experimental-v1")) {"unknown Undefined exception profile"}
        require(value.isEmpty()||mmuProfile.isNotEmpty()) {"Undefined exception profile requires qualified native MMU/cluster"}
        value
    }
    private val undefinedEntries=ArrayDeque<Map<String,Any>>()
    private var undefinedCount=0L
    private var irqSubscription: AutoCloseable? = null
    private var irqCount = 0L
    private class IrqEntryRing {
        private val cap = 256
        private val lines = IntArray(cap)
        private val ticks = LongArray(cap)
        private val pcBefores = LongArray(cap)
        private val pcAfters = LongArray(cap)
        private val hasCore = BooleanArray(cap)
        private val cores = IntArray(cap)
        private val rawMaps = arrayOfNulls<Map<String, Any>>(cap)
        private var head = 0
        var size = 0
            private set
        fun clear() {
            head = 0
            size = 0
            rawMaps.fill(null)
        }
        private fun nextSlot(): Int = if (size < cap) {
            val s = (head + size) and 255
            size++
            s
        } else {
            val s = head
            head = (head + 1) and 255
            s
        }
        fun addFast(line: Int, tick: Long, pcBefore: Long, pcAfter: Long, withCore: Boolean, core: Int) {
            val s = nextSlot()
            rawMaps[s] = null
            lines[s] = line
            ticks[s] = tick
            pcBefores[s] = pcBefore
            pcAfters[s] = pcAfter
            hasCore[s] = withCore
            cores[s] = core
        }
        fun addRaw(entry: Map<String, Any>) {
            val s = nextSlot()
            rawMaps[s] = entry
        }
        fun toList(): List<Map<String, Any>> {
            val out = ArrayList<Map<String, Any>>(size)
            for (i in 0 until size) {
                val s = (head + i) and 255
                val raw = rawMaps[s]
                if (raw != null) {
                    out.add(raw)
                } else if (hasCore[s]) {
                    out.add(
                        linkedMapOf(
                            "line" to lines[s],
                            "tick" to ticks[s],
                            "pcBefore" to pcBefores[s],
                            "pcAfter" to pcAfters[s],
                            "core" to cores[s],
                        ),
                    )
                } else {
                    out.add(
                        linkedMapOf(
                            "line" to lines[s],
                            "tick" to ticks[s],
                            "pcBefore" to pcBefores[s],
                            "pcAfter" to pcAfters[s],
                        ),
                    )
                }
            }
            return out
        }
    }
    private val irqEntries = IrqEntryRing()
    private class IrqLineRing {
        private val cap = 512
        private val ticks = LongArray(cap)
        private val lines = IntArray(cap)
        private val highs = BooleanArray(cap)
        private val cpsrs = LongArray(cap)
        private val pcs = LongArray(cap)
        private val hasCore = BooleanArray(cap)
        private val cores = IntArray(cap)
        private var head = 0
        var size = 0
            private set
        fun clear() { head = 0; size = 0 }
        fun add(tick: Long, line: Int, high: Boolean, cpsr: Long, pcVirtual: Long, core: Int?) {
            val idx = if (size < cap) {
                val slot = (head + size) and 511
                size++
                slot
            } else {
                val slot = head
                head = (head + 1) and 511
                slot
            }
            ticks[idx] = tick
            lines[idx] = line
            highs[idx] = high
            cpsrs[idx] = cpsr
            pcs[idx] = pcVirtual
            if (core != null) {
                hasCore[idx] = true
                cores[idx] = core
            } else {
                hasCore[idx] = false
                cores[idx] = 0
            }
        }
        fun toList(): List<Map<String, Any?>> {
            val out = ArrayList<Map<String, Any?>>(size)
            for (i in 0 until size) {
                val idx = (head + i) and 511
                val entry = linkedMapOf<String, Any?>(
                    "operation" to "line",
                    "tick" to ticks[idx],
                    "line" to lines[idx],
                    "high" to highs[idx],
                    "cpsr" to cpsrs[idx],
                    "pcVirtual" to pcs[idx],
                )
                if (hasCore[idx]) entry["core"] = cores[idx]
                out.add(entry)
            }
            return out
        }
    }
    private val irqLines = IrqLineRing()
    private var irqLineCount = 0L
    private var memoryLayout: com.github.nyabanban.fuji.emulator.api.MemoryLayout? = null
    private var ramWriteRanges=RamWriteRanges(emptyList())
    private var cpuInterrupts: com.github.nyabanban.fuji.emulator.api.CpuInterruptPort? = null
    private var fastRunEndTick = 0L
    private var fastMultiQuantumActive = false
    private var fastInlineTimerActive = false
    private var fastAutoIrqActive = false
    private var stepInProgress=false
    private var additionalRamReceipt:Map<String,Any?>?=null
    private val tracePoints by lazy {
        val selected = config["tracePoints"] ?: emptyList<Any>()
        require(selected is List<*>) { "tracePoints requires a list" }
        require(selected.size <= 128)
        selected.map { com.github.nyabanban.fuji.emulator.firmware.number(checkNotNull(it)) }.also { points ->
            require(points.distinct().size == points.size && points.all { it in 0..0xfffffffcL && it % 4 == 0L }) { "invalid diagnostic trace points" }
        }.toSet()
    }
    private val tracePerPointLimit by lazy {
        config["tracePerPointLimit"]?.let {raw->require(raw is Byte||raw is Short||raw is Int||raw is Long){"tracePerPointLimit requires integer"};(raw as Number).toLong().also{require(it in 1..8){"tracePerPointLimit outside 1..8"}}.toInt()}
    }
    private val traceIndices by lazy {tracePoints.withIndex().associate{it.value to it.index}}
    private val kernelLateCall by lazy { KernelLateCallObservation.select(config[KernelLateCallObservation.KEY]) }
    private val menuLate by lazy { MenuLateObservation.select(config[MenuLateObservation.KEY]) }
    private val traceObserved by lazy {LongArray(tracePoints.size*4)}
    private val checkpoints = ArrayDeque<Map<String, Long>>()
    private var checkpointCount = 0L
    private val memoryWatches by lazy {
        val selected = config["memoryWatches"] ?: emptyList<Any>(); require(selected is List<*> && selected.size <= 18)
        selected.map { raw ->
            val row = Formats.map(raw); require(row.keys == setOf("address","size"))
            val address = com.github.nyabanban.fuji.emulator.firmware.number(row.getValue("address")); val size = com.github.nyabanban.fuji.emulator.firmware.number(row.getValue("size"))
            require(address >= 0 && size in 1..1048576 && address <= 0x1_0000_0000L - size) { "invalid diagnostic memory watch (one MiB maximum)" }
            address to size.toInt()
        }
    }
    private var reads = 0L
    private var writes = 0L
    private val transactions by lazy { MmioTrace(transactionLimit) }
    private val transactionLimit by lazy {
        val value=config["transactionLimit"] ?: 8192
        require(value is Byte || value is Short || value is Int || value is Long) { "transactionLimit requires integer" }
        (value as Number).toLong().also { require(it in 1..2228224) { "transactionLimit outside bounded range" } }.toInt()
    }
    private val relaxedMode = catalogRuntime.mode == RelaxedPeripheralFallback.MODE
    private val initialBudget = InstructionBudget.parse(config["instructionBudget"], relaxed = relaxedMode)
    private val unlimitedBudget = relaxedMode && initialBudget == InstructionBudget.UNLIMITED
    private var budget = initialBudget
    private var budgetSelection: SnapshotBudgetSelection? = null
    @Volatile private var socPoweredOff = false
    @Volatile private var socPoweredOffSinceNanos: Long? = null
    @Volatile private var lastSocPoweredOffWallSeconds: Long = 0L
    @Volatile private var socWakeCount = 0L
    private var powerHoldAsserted = false
    private var powerHoldDeasserted = false
    private var sawPowerSwitchOffDuringBoot = false
    private val standbyWakeState = SubMcuStandbyWakeState()
    val isSocPoweredOff: Boolean get() = socPoweredOff
    val hasPendingSocWake: Boolean get() = socPoweredOff && standbyWakeState.hasPendingWake
    val wakeCount: Long get() = socWakeCount
    @Volatile var lastStepAllCoresWfi: Boolean = false
        private set
    fun consumeLastSocPoweredOffWallSeconds(): Long {
        val elapsed = lastSocPoweredOffWallSeconds
        lastSocPoweredOffWallSeconds = 0L
        return elapsed
    }
    internal fun checkpointBudgetSelection():Map<String,Any?> {
        host.owner.requireOwner()
        return mapOf("initialLimit" to initialBudget,"effectiveLimit" to budget,
            "selectedLimit" to budgetSelection?.limit,"selectedAtAttempts" to budgetSelection?.attempts,
            "selectedAtTick" to budgetSelection?.tick)
    }
    internal fun selectCheckpointBudget(limit:Long):Map<String,Any?> {
        host.owner.requireOwner()
        check(activeBackend!=null && !stopped && attempts>0) { "Checkpoint budget requires an initialized running session after guest execution" }
        check(budgetSelection==null) { "Checkpoint budget already selected" }
        val validated=InstructionBudget.parseContinuation(limit)
        require(validated>=initialBudget && validated>attempts) { "Checkpoint budget must preserve the initial limit and exceed consumed attempts" }
        val selection=SnapshotBudgetSelection(validated,attempts,virtualTick)
        budget=validated
        budgetSelection=selection
        return checkpointBudgetSelection()
    }
    internal fun extendF01cReads(extra: Int): Map<String, Any?> {
        host.owner.requireOwner()
        check(activeBackend != null && !stopped && !stepInProgress && attempts > 0) { "Expected a running session between owner steps" }
        return catalogRuntime.extendF01cReads(checkNotNull(discoveryBus), extra) +
            mapOf("attempts" to attempts, "tick" to virtualTick)
    }
    internal fun completeAudioF01c(): Map<String, Any> {
        host.owner.requireOwner()
        check(activeBackend != null && !stopped && !stepInProgress && attempts > 0)
        return catalogRuntime.completeAudioF01c(checkNotNull(discoveryBus), attempts, virtualTick)
    }
    internal fun selectF01cRepeats(limit: Int): Map<String, Any> {
        host.owner.requireOwner()
        check(activeBackend != null && !stopped && !stepInProgress && attempts > 0)
        return catalogRuntime.selectF01cRepeats(checkNotNull(discoveryBus), limit)
    }
    internal fun additionalRamSelection():Map<String,Any?> {
        host.owner.requireOwner()
        val enabled=memoryLayout is com.github.nyabanban.fuji.emulator.api.ExpandableMemoryLayout &&
            catalogRuntime.additionalMemorySelection()["enabled"]==true
        return additionalRamReceipt ?: mapOf("enabled" to enabled,"selected" to false,"plan" to null,"attempts" to null,"tick" to null,
            "baseProfile" to memoryLayout?.profile,"baseRegions" to memoryLayout?.regions()?.let(AdditionalRamPlan::rows))
    }
    internal fun selectAdditionalRam(plan:AdditionalRamPlan):Map<String,Any?> {
        host.owner.requireOwner()
        check(activeBackend!=null&&!stopped&&!stepInProgress) {"Additional RAM requires a running session between completed owner steps"}
        check(additionalRamReceipt==null) {"Additional RAM already selected"}
        val layout=memoryLayout as? com.github.nyabanban.fuji.emulator.api.ExpandableMemoryLayout
            ?: error("Memory component has no finite additional RAM capability")
        val frozen=AdditionalRamPlan.parse(plan.receipt())
        val sessionProfile=catalogRuntime.additionalMemorySelection()["profile"] as? String ?: ""
        require(sessionProfile in com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.PROFILES &&
            (frozen.profile==sessionProfile || sessionProfile==com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.EXEC_CAPABLE_PROFILE)) {
            "Additional RAM plan exceeds the session capability"
        }
        require(layout.profile==frozen.baseProfile&&layout.regions()==frozen.baseRegions.sortedBy{it.address}) {"Additional RAM baseline differs from the frozen running layout"}
        val complete=layout.validateAdditionalRegions(frozen.additionalRegions)
        val hasFirmwareDat = (config["firmwareFile"] as? String)?.isNotBlank() == true
        val method4=if(!customBootMode && hasFirmwareDat && (frozen.sourceProfile in Method4OutputAdmission.PROFILES||
            frozen.additionalRegions.any{it.id==Method4OutputAdmission.REGION})) {
            val declaration=Formats.read(home.resolve(config.getValue("firmwareMetadata") as String))
            val firmwarePath=home.resolve(config.getValue("firmwareFile") as String)
            val dat=java.nio.file.Files.readAllBytes(firmwarePath)
            val image=FirmwareImage(FirmwareMetadata(declaration),firmwarePath)
            Method4OutputAdmission.additionalRam(frozen,catalogRuntime.catalog,layout.regions(),dat,image.decode("section4"))
        } else null
        catalogRuntime.validateAdditionalMemory(frozen.additionalRegions)
        val pages=checkNotNull(idleRouter).pageRanges()
        for(region in frozen.additionalRegions)require(pages.none{(address,size)->
            region.address<address+size&&address<region.address+region.size}) {"Additional RAM overlaps native MMIO transport"}
        val ranges=RamWriteRanges(complete)
        val receipt=mapOf("enabled" to true,"selected" to true,"plan" to frozen.receipt(),"attempts" to attempts,"tick" to virtualTick,
            "initialContent" to "HYPOTHESIS: explicit zero native backing; no existing bytes, CPU/MMU/IRQ state or time changed",
            "regionCount" to complete.size)+(method4?.let{mapOf("method4Output" to it)} ?: emptyMap())
        val backend=checkNotNull(activeBackend)
        AdditionalRamMapping.map(frozen.additionalRegions,backend::map,backend::unmap)
        layout.selectAdditionalRegions(frozen.additionalRegions)
        catalogRuntime.selectAdditionalMemory(frozen.additionalRegions)
        ramWriteRanges=ranges
        additionalRamReceipt=receipt
        return additionalRamSelection()
    }
    internal fun extendAdditionalRam(plan: AdditionalRamPlan): Map<String, Any?> {
        host.owner.requireOwner()
        check(activeBackend != null && !stopped && !stepInProgress) { "Additional RAM requires a running session between completed owner steps" }
        check(additionalRamReceipt != null) { "Extended additional RAM requires an existing selection" }
        val layout = memoryLayout as? com.github.nyabanban.fuji.emulator.api.ExpandableMemoryLayout
            ?: error("Memory component has no finite additional RAM capability")
        val frozen = AdditionalRamPlan.parse(plan.receipt())
        val sessionProfile = catalogRuntime.additionalMemorySelection()["profile"] as? String ?: ""
        require(
            sessionProfile in com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.PROFILES &&
                (frozen.profile == sessionProfile || sessionProfile == com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        ) { "Additional RAM plan exceeds the session capability" }
        val current = layout.additionalRegions()
        val baseRegions = frozen.baseRegions.sortedBy { it.address }
        require(layout.profile == frozen.baseProfile && layout.regions() == (baseRegions + current).sortedBy { it.address }) {
            "Extended additional RAM baseline differs from the frozen running layout"
        }
        require(frozen.additionalRegions.containsAll(current)) {
            "Extended additional RAM plan must preserve all existing additional regions"
        }
        val deltaRegions = frozen.additionalRegions - current.toSet()
        require(deltaRegions.isNotEmpty()) { "Extended additional RAM plan contains no new regions" }
        val complete = (baseRegions + frozen.additionalRegions).sortedBy { it.address }
        val hasFirmwareDat = (config["firmwareFile"] as? String)?.isNotBlank() == true
        val method4 = if (
            !customBootMode && hasFirmwareDat && (
                frozen.sourceProfile in Method4OutputAdmission.PROFILES ||
                frozen.additionalRegions.any { it.id == Method4OutputAdmission.REGION }
            )
        ) {
            val declaration = Formats.read(home.resolve(config.getValue("firmwareMetadata") as String))
            val firmwarePath = home.resolve(config.getValue("firmwareFile") as String)
            val dat = java.nio.file.Files.readAllBytes(firmwarePath)
            val image = FirmwareImage(FirmwareMetadata(declaration), firmwarePath)
            Method4OutputAdmission.additionalRam(frozen, catalogRuntime.catalog, baseRegions, dat, image.decode("section4"))
        } else null
        val pages = checkNotNull(idleRouter).pageRanges()
        for (region in deltaRegions) {
            require(pages.none { (address, size) ->
                region.address < address + size && address < region.address + region.size
            }) { "Extended additional RAM overlaps native MMIO transport" }
        }
        val ranges = RamWriteRanges(complete)
        val receipt = mapOf(
            "enabled" to true,
            "selected" to true,
            "plan" to frozen.receipt(),
            "attempts" to attempts,
            "tick" to virtualTick,
            "initialContent" to "HYPOTHESIS: explicit zero native backing; no existing bytes, CPU/MMU/IRQ state or time changed",
            "regionCount" to complete.size,
        ) + (method4?.let { mapOf("method4Output" to it) } ?: emptyMap())
        val extensionReceipt = receipt + ("deltaRegions" to AdditionalRamPlan.rows(deltaRegions))
        val backend = checkNotNull(activeBackend)
        catalogRuntime.extendAdditionalMemory(frozen.additionalRegions) {
            layout.extendAdditionalRegions(frozen.additionalRegions) {
                AdditionalRamMapping.map(deltaRegions, backend::map, backend::unmap)
            }
        }
        ramWriteRanges = ranges
        additionalRamReceipt = receipt
        return extensionReceipt
    }
    internal fun completedMethod4Pages(): Int? {
        host.owner.requireOwner()
        val item = host.components().singleOrNull { it.descriptor.id == "soc-method4" } ?: return null
        val snap = (item.component as? com.github.nyabanban.fuji.emulator.api.StatefulComponent)?.saveSnapshot() ?: return null
        val events = snap["events"] as? List<*> ?: return null
        for (raw in events.asReversed()) {
            val event = raw as Map<*, *>
            if (event["completedSha256"] !is String || (event["produced"] as Number).toLong() != Method4OutputAdmission.SIZE) continue
            val end = (event["destination"] as Number).toLong() + (event["outputSize"] as Number).toLong()
            if (end == Method4OutputAdmission.BASE + Method4OutputAdmission.SIZE) return (event["page"] as Number).toInt()
        }
        return null
    }
    fun initialize() {
        host.owner.requireOwner(); check(dynarmic == null)
        val engineSelection = CpuEngineConfig.resolve(home, config, catalogRuntime.mode)
        val allowCustom = allowCustomFirmware
        val rawImageSpec = customRawImageSpec
        val customMode = allowCustom || rawImageSpec != null
        val (meta, isBootRom, bootRomBytes) = SessionBootLoader.loadMetadataAndBootRom(
            home = home,
            config = config,
            catalogRuntime = catalogRuntime,
            rawImageSpec = rawImageSpec,
        )
        tracePoints // validate before native allocation
        tracePerPointLimit
        menuLate
        if(kernelLateCall!=null)require(clusterProfile.isNotEmpty()&&mmuProfile.isNotEmpty()) { "kernel late-call observation requires explicit cluster and native MMU" }
        memoryWatches
        transactionLimit
        cbarInput?.let { value ->
            require(meta.devices.single { it.port == "soc-gic.distributor" }.address == value + 0x1000 &&
                meta.devices.single { it.port == "soc-gic.interface" }.address == value + 0x2000) { "CBAR input differs from selected GIC metadata" }
        }
        clusterProfile
        if(idleClock!=null)require(clusterProfile.isNotEmpty()){ "idleClock requires explicit four-context cluster" }
        clusterReset
        mmuProfile
        prefetchAbortProfile
        exceptionProfile
        wordExclusive
        undefinedExceptionProfile
        val partitionPair=com.github.nyabanban.fuji.emulator.firmware.FlashPartitionPairContracts.selected(config)
        val secondaryRead=com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.selected(config)
        val writableSetSelected=com.github.nyabanban.fuji.emulator.firmware.FlashWritableSetContracts.selected(config)
        val flashImageForBoot = host.ports.find("flash.image", com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort::class.java)
        val artifacts = SessionBootLoader.loadImages(
            home = home,
            config = config,
            meta = meta,
            isBootRom = isBootRom,
            bootRomBytes = bootRomBytes,
            allowCustom = allowCustom,
            rawImageSpec = rawImageSpec,
            log = log,
            flashImage = flashImageForBoot,
        )
        val loadedRaw = artifacts.loadedRaw
        val image = artifacts.image
        val loader = artifacts.loader
        if (artifacts.customContracts.isNotEmpty()) {
            contracts = contracts + artifacts.customContracts
        }
        val flashState = host.components().singleOrNull { it.descriptor.id == "soc-flash" }?.component?.state()
        if (!customMode && image != null) {
        if (flashState?.get("profile") == com.github.nyabanban.fuji.emulator.firmware.FlashPioEnableContracts.PROFILE) contracts = contracts + ("flashPioEnable" to com.github.nyabanban.fuji.emulator.firmware.FlashPioEnableContracts.verify(image,checkNotNull(flashState)))
        val writableSetScope = config[com.github.nyabanban.fuji.emulator.firmware.FlashWritableSetContracts.KEY] as? String
        val partitionScope = config[com.github.nyabanban.fuji.emulator.firmware.FlashPartitionPairContracts.KEY] as? String
        if(writableSetSelected){
            val expectedPartitionScope = if (writableSetScope == com.github.nyabanban.fuji.emulator.firmware.FlashWritableSetContracts.PROFILE_OCTET) {
                com.github.nyabanban.fuji.emulator.firmware.FlashPartitionOctetContracts.PROFILE
            } else {
                com.github.nyabanban.fuji.emulator.firmware.FlashPartitionQuartetContracts.PROFILE
            }
            require(partitionPair && partitionScope == expectedPartitionScope && flashState?.get("profile") == com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.PROFILE) {
                "writable region-set requires qualified quartet/octet and program controller"
            }
        }
        if(partitionPair)require(flashState?.get("profile") in setOf(com.github.nyabanban.fuji.emulator.firmware.FlashEraseContracts.PROFILE,com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.PROFILE)){"partition-pair requires explicit erase controller profile"}
        if(flashState?.get("profile") in setOf(com.github.nyabanban.fuji.emulator.firmware.FlashEraseContracts.PROFILE,com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.PROFILE)) {
            val selectedFlash=checkNotNull(flashState)
            val imagePort=selectedFlash.getValue("imagePort").toString()
            val mutable=host.ports.find(imagePort+".mutable",com.github.nyabanban.fuji.emulator.api.WritableImagePort::class.java) ?: error("erase requires explicit mutable image peer before native allocation")
            val supplied=host.ports.find(imagePort,com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort::class.java) as? com.github.nyabanban.fuji.emulator.api.SupplementalImagePort ?: error("erase requires supplemental image peer")
            val imageState=host.components().single{it.descriptor.id=="flash-image"}.component.state()
            val isWritableSet=mutable is com.github.nyabanban.fuji.emulator.api.WritableImageSetPort
            require(isWritableSet==writableSetSelected){"writable region-set requires explicit firmware scope"}
            if(writableSetSelected)contracts=contracts+("flashWritableSet" to com.github.nyabanban.fuji.emulator.firmware.FlashWritableSetContracts.verify(image,mutable as com.github.nyabanban.fuji.emulator.api.WritableImageSetPort,imageState,supplied.supplementalRegions,writableSetScope ?: com.github.nyabanban.fuji.emulator.firmware.FlashWritableSetContracts.PROFILE))
            // The old storage contract remains a primary-domain ancestry check; actual multi state is separately verified above.
            val programImageState=if(writableSetSelected)imageState+mapOf("mutationProfile" to com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.IMAGE_PROFILE)else imageState
            if(selectedFlash["profile"]==com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.PROFILE){
                val dmaComponent=host.components().single{it.descriptor.id=="soc-dma"};val dmaState=dmaComponent.component.state()
                val routeFile=dmaState["destinationPeersFile"] as? String ?: error("program destination file missing")
                val routeData=Formats.readData(containedPath(dmaComponent.configFile.parent,routeFile))
                val program=host.ports.find(imagePort+".program",com.github.nyabanban.fuji.emulator.api.ProgrammableImagePort::class.java)
                val peers=listOf("soc-flash.tx","soc-serial-engine.tx-1").associateWith{host.ports.find(it,com.github.nyabanban.fuji.emulator.api.DmaDestinationPeer::class.java)}
                contracts=contracts+("flashProgram" to com.github.nyabanban.fuji.emulator.firmware.FlashProgramContracts.verify(image,selectedFlash,dmaState,programImageState,mutable.snapshot(),supplied.supplementalRegions,imageState.getValue("tailProfile").toString(),routeData,program,peers))
            }else contracts=contracts+("flashErase" to com.github.nyabanban.fuji.emulator.firmware.FlashEraseContracts.verify(image,checkNotNull(flashState),mutable.snapshot(),supplied.supplementalRegions,imageState.getValue("tailProfile").toString()))
            if(partitionPair) {
                when (partitionScope) {
                    com.github.nyabanban.fuji.emulator.firmware.FlashPartitionOctetContracts.PROFILE ->
                        contracts=contracts+("flashPartitionOctet" to com.github.nyabanban.fuji.emulator.firmware.FlashPartitionOctetContracts.verify(image,mutable.snapshot()))
                    com.github.nyabanban.fuji.emulator.firmware.FlashPartitionQuartetContracts.PROFILE ->
                        contracts=contracts+("flashPartitionQuartet" to com.github.nyabanban.fuji.emulator.firmware.FlashPartitionQuartetContracts.verify(image,mutable.snapshot()))
                    else ->
                        contracts=contracts+("flashPartitionPair" to com.github.nyabanban.fuji.emulator.firmware.FlashPartitionPairContracts.verify(image,mutable.snapshot()))
                }
            }
        }
        if(secondaryRead){
            val portName=flashState?.get("imagePort")?.toString()?:error("secondary flash controller missing")
            val source=host.ports.find(portName,com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort::class.java) as? com.github.nyabanban.fuji.emulator.api.SupplementalImagePort?:error("secondary flash explicit input port missing")
            val mutable=host.ports.find(portName+".mutable",com.github.nyabanban.fuji.emulator.api.WritableImagePort::class.java)
            contracts=contracts+("flashReadPair" to com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyInputs(image,source.supplementalRegions,mutable?.snapshot()))
        }
        }
        val selectedMemory = host.ports.find("system-memory.layout",com.github.nyabanban.fuji.emulator.api.MemoryLayout::class.java) ?: error("system-memory JAR missing")
        val memoryDefinition = meta.memoryProfiles[selectedMemory.profile] ?: error("memory profile absent from selected firmware metadata")
        var configMemoryPages:ConfigMemoryPagesAdmission.Result?=null
        var runtimeFlashStaging:RuntimeFlashStagingAdmission.Result?=null
        val segmentPlan = if (customMode || image == null) {
            null
        } else {
        fun verifiedV6()=com.github.nyabanban.fuji.emulator.firmware.ResetContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.ResetContracts.PROFILE),
            com.github.nyabanban.fuji.emulator.firmware.KernelContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.KernelContracts.PROFILE),
                com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.PROFILE))))
        fun verifiedV13()=com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskPairContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskPairContracts.PROFILE),
                com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskBufferContracts.PROFILE),
                com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.PROFILE),
                com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.L2Contracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.L2Contracts.PROFILE),
                        com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE),verifiedV6()))))))
        when(selectedMemory.profile) {
            com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(image,memoryDefinition)
            "boot-rom-cold-start-segments-experimental-v2"->com.github.nyabanban.fuji.emulator.firmware.KernelContracts.verify(
                image,
                memoryDefinition.filterNot { it.id.startsWith("boot-rom") },
                com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(
                    image,
                    memoryDefinition.filterNot { it.id.startsWith("boot-rom") || it.id.startsWith("stack-") },
                ),
            )
            "boot-rom-full-boot-v1"->com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(
                image,
                memoryDefinition.filter { it.id in setOf("loader", "kernel", "segment-1", "segment-2", "metadata-workspace", "segment-4", "segment-5", "staging") },
            )
            com.github.nyabanban.fuji.emulator.firmware.KernelContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.KernelContracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.PROFILE)))
            com.github.nyabanban.fuji.emulator.firmware.ResetContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.ResetContracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.KernelContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.KernelContracts.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.PROFILE))))
            com.github.nyabanban.fuji.emulator.firmware.PoolContracts.POOL_PROFILE->com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyPool(image,memoryDefinition,verifiedV6())
            com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE->com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,memoryDefinition,verifiedV6())
            com.github.nyabanban.fuji.emulator.firmware.L2Contracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.L2Contracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE),verifiedV6()))
            com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.L2Contracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.L2Contracts.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE),verifiedV6())))
            com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.L2Contracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.L2Contracts.PROFILE),
                        com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE),verifiedV6()))))
            com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskBufferContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskBufferContracts.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.AuxBufferContracts.PROFILE),
                com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashBufferContracts.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.L2Contracts.verify(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.L2Contracts.PROFILE),
                        com.github.nyabanban.fuji.emulator.firmware.PoolContracts.verifyTrace(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.PoolContracts.TRACE_PROFILE),verifiedV6())))))
            com.github.nyabanban.fuji.emulator.firmware.AuxiliaryTaskPairContracts.PROFILE->verifiedV13()
            com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE->com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,memoryDefinition,verifiedV13())
            ConfigMemoryPagesAdmission.PROFILE->ConfigMemoryPagesAdmission.verify(image,memoryDefinition,
                com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE),verifiedV13())).also {configMemoryPages=it}.legacyPlan
            RuntimeFlashStagingAdmission.PROFILE->RuntimeFlashStagingAdmission.verify(image,memoryDefinition,
                ConfigMemoryPagesAdmission.verify(image,meta.memoryProfiles.getValue(ConfigMemoryPagesAdmission.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE),verifiedV13())).also {configMemoryPages=it})
                .also {runtimeFlashStaging=it}.ancestor.legacyPlan
            PostLensObjectAdmission.PROFILE->PostLensObjectAdmission.verify(image,memoryDefinition,RuntimeFlashStagingAdmission.verify(image,meta.memoryProfiles.getValue(RuntimeFlashStagingAdmission.PROFILE),
                ConfigMemoryPagesAdmission.verify(image,meta.memoryProfiles.getValue(ConfigMemoryPagesAdmission.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE),verifiedV13())).also {configMemoryPages=it}))
                .also {runtimeFlashStaging=it}.ancestor.legacyPlan
            CalcScratchAdmission.PROFILE->CalcScratchAdmission.verify(image,memoryDefinition,PostLensObjectAdmission.verify(image,meta.memoryProfiles.getValue(PostLensObjectAdmission.PROFILE),RuntimeFlashStagingAdmission.verify(image,meta.memoryProfiles.getValue(RuntimeFlashStagingAdmission.PROFILE),
                ConfigMemoryPagesAdmission.verify(image,meta.memoryProfiles.getValue(ConfigMemoryPagesAdmission.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE),verifiedV13())).also {configMemoryPages=it}))
                ).also {runtimeFlashStaging=it}.ancestor.legacyPlan
            RawSegment2TailAdmission.PROFILE,FixedTableRow5Admission.PROFILE,SevenRowTableAdmission.PROFILE,XlogBufferAdmission.PROFILE,XlogBufferAdmission.ALL_PROFILE,FlashloadBufferAdmission.PROFILE,"linux-contiguous-256m-experimental-v1"->CalcScratchAdmission.verify(image,meta.memoryProfiles.getValue(CalcScratchAdmission.PROFILE),PostLensObjectAdmission.verify(image,meta.memoryProfiles.getValue(PostLensObjectAdmission.PROFILE),RuntimeFlashStagingAdmission.verify(image,meta.memoryProfiles.getValue(RuntimeFlashStagingAdmission.PROFILE),
                ConfigMemoryPagesAdmission.verify(image,meta.memoryProfiles.getValue(ConfigMemoryPagesAdmission.PROFILE),
                    com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.verifyMemory(image,meta.memoryProfiles.getValue(com.github.nyabanban.fuji.emulator.firmware.FlashReadPairContracts.PROFILE),verifiedV13())).also {configMemoryPages=it}))
                ).also {runtimeFlashStaging=it}.ancestor.legacyPlan
            else->null
        }
        }
        if(segmentPlan != null) contracts = contracts + ("segments" to (runtimeFlashStaging?.liveReport ?: configMemoryPages?.liveReport ?: segmentPlan.report))
        val selected = (config.getValue("enabledDevices") as List<*>).map { it as String }
        val allowStage2XipTrace = SessionBootLoader.isStage2XipTraceAllowed(
            isBootRom = isBootRom,
            selectedDevices = selected,
            executionMode = catalogRuntime.mode,
            config = config,
            relaxedMode = relaxedMode,
        )
        if(mmuProfile.isEmpty())tracePoints.forEach { point ->
            val inStage2Xip = allowStage2XipTrace && point >= SessionBootLoader.STAGE2_XIP_BASE &&
                4L <= (SessionBootLoader.STAGE2_XIP_BASE + SessionBootLoader.STAGE2_XIP_SIZE) - point
            require(inStage2Xip || memoryDefinition.any { r -> point >= r.address && 4 <= r.address + r.size - point && r.permissions and 4 != 0 }) { "trace point outside declared executable RAM" }
        }
        require(selected.distinct().size == selected.size && selected.all { id -> meta.devices.any { it.id == id } })
        if (!customMode && image != null) {
        host.components().singleOrNull{it.descriptor.id==VglibInitAdmission.ID}?.let{loaded->
            VglibInitAdmission.observe(catalogRuntime.catalog,meta,selected,loaded.component.state(),loaded.component.additionalMmioPorts(), kernel = { image.decodeReadOnly("kernel") }) {
                image.decodeReadOnly("section4")
            }?.let{contracts=contracts+("vglibInit" to it)}
        }
        }
        menuLate?.validateConfiguration(tracePoints,clusterProfile.isNotEmpty(),mmuProfile.isNotEmpty(),
            (host.components().singleOrNull{it.descriptor.id==VglibInitAdmission.ID}?.component?.state()?.get("lcdcSnapshot") as? Map<*,*>)?.get("profile"))
        if(idleClock!=null && "soc-timer" in selected) {
            val timer=host.components().single{it.descriptor.id=="soc-timer"}.component.state()
            val specification=timer["specification"] as? Map<*,*>
            require(specification?.get("clock")==IdleClock.TIMER_CLOCK) { "idleClock requires explicitly selected functional timer clock" }
        }
        val coreInterrupts=host.ports.find("soc-gic.cores",com.github.nyabanban.fuji.emulator.api.CoreInterruptPort::class.java)
        if(clusterProfile.isNotEmpty()) {
            require(cbarInput!=null && "soc-gic" in selected && coreInterrupts?.coreCount==4) { "cluster requires explicit CBAR and selected four-core interrupt profile" }
        }
        else require(coreInterrupts==null) { "four-core interrupt profile requires explicit cluster" }
        validateInterruptRoutes(host.components().filter { it.descriptor.id in selected }.map { it.component }, host.ports)
        val additional=host.components().filter {it.descriptor.id in selected}.associate {it.descriptor.id to it.component.additionalMmioPorts()}
        val activeWindows=meta.activatedDevices(selected.toSet(),additional)
        val mmioWindows=activeWindows.map { window ->
            MmioWindow(window.id, window.address, window.size, host.ports.find(window.port, MmioDevice::class.java) ?: error("MMIO port missing: ${window.port}"))
        }
        catalogRuntime.bindPorts(activeWindows.map { com.github.nyabanban.fuji.emulator.catalog.PortBinding(it.id,it.port,it.address,it.size) },selectedMemory.profile,memoryDefinition.map { mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions) },
            configuredComponentProfiles(host,activeWindows.map { it.id }.toSet()),
            additionalRamProfile=host.components().single{it.descriptor.id=="system-memory"}.component.state()["additionalRamProfile"] as? String ?: "")
        discoveryBus=catalogRuntime.createBus(object:DiscoveryContext {
            private var cachedIrqSink: com.github.nyabanban.fuji.emulator.api.InterruptSink? = null
            private var cachedIrqSinkResolved = false
            private var cachedSensor: com.github.nyabanban.fuji.emulator.api.SensorPort? = null
            private var cachedSensorResolved = false
            private var cachedFaceScaler: com.github.nyabanban.fuji.emulator.api.FacePreviewScalerPort? = null
            private var cachedFaceScalerResolved = false
            private var cachedDmaMem: DmaMemory? = null
            private var cachedMount: com.github.nyabanban.fuji.emulator.api.MountPort? = null
            private var cachedMountResolved = false
            private var cachedLens: com.github.nyabanban.fuji.emulator.api.LensPort? = null
            private var cachedLensResolved = false
            private var cachedExposure: com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort? = null
            private var cachedExposureResolved = false

            override fun core()=currentCoreIndex()
            override fun tick()=virtualTick
            override fun pc()=pc
            override fun lr()=activeBackend?.register(ArmReg.LR) ?: 0L
            override fun imageIrq106Active() = imageIrq106InFlight(host)
            override fun imageIrq106LineActive() = imageIrq106LineActive(host)
            override fun interruptSink(): com.github.nyabanban.fuji.emulator.api.InterruptSink? {
                if (!cachedIrqSinkResolved) {
                    cachedIrqSink = host.ports.find("soc-gic.lines", com.github.nyabanban.fuji.emulator.api.InterruptSink::class.java)
                    cachedIrqSinkResolved = true
                }
                return cachedIrqSink
            }
            override fun sensorPort(): com.github.nyabanban.fuji.emulator.api.SensorPort? {
                if (!cachedSensorResolved) {
                    cachedSensor = host.ports.find("cmos", com.github.nyabanban.fuji.emulator.api.SensorPort::class.java)
                    cachedSensorResolved = true
                }
                return cachedSensor
            }
            override fun facePreviewScaler(): com.github.nyabanban.fuji.emulator.api.FacePreviewScalerPort? {
                if (!cachedFaceScalerResolved) {
                    cachedFaceScaler = host.ports.find(
                        "lens.uniresz", com.github.nyabanban.fuji.emulator.api.FacePreviewScalerPort::class.java,
                    )
                    cachedFaceScalerResolved = true
                }
                return cachedFaceScaler
            }
            override fun dmaMemory(): DmaMemory? =
                cachedDmaMem ?: host.ports.find("cpu.memory", DmaMemory::class.java)?.also { cachedDmaMem = it }
            override fun lensParameters(): com.github.nyabanban.fuji.emulator.api.LensParameters? {
                if (!cachedMountResolved) {
                    cachedMount = host.ports.find("mount", com.github.nyabanban.fuji.emulator.api.MountPort::class.java)
                    cachedMountResolved = true
                }
                val fromMount = cachedMount?.attachedLens()
                if (fromMount != null) return fromMount
                if (!cachedLensResolved) {
                    cachedLens = host.ports.find("lens", com.github.nyabanban.fuji.emulator.api.LensPort::class.java)
                    cachedLensResolved = true
                }
                return cachedLens?.parameters()
            }
            override fun exposurePort(): com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort? {
                if (!cachedExposureResolved) {
                    cachedExposure = host.ports.find(
                        com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort.PORT_ID,
                        com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort::class.java,
                    )
                    cachedExposureResolved = true
                }
                return cachedExposure
            }
            override fun hasImageRequest82(selector: Int) = activeBackend?.readMemory(0x028f2000L, 3)
                ?.contentEquals(byteArrayOf(0x82.toByte(), 0, selector.toByte())) == true
            override fun imageCommandBytes(address: Long, length: Int): ByteArray? {
                require(length in 8..2040 && length % 8 == 0 &&
                    (address == 0x028f2940L || address == 0x028f2000L && length == 8))
                return activeBackend?.readMemory(address, length)
            }
            override fun inT79ImageTeardown() = activeBackend?.inT79ImageTeardown() == true
            override fun image6eDisableMode() = activeBackend?.register(ArmReg.R0 + 4) == 0L
        })
        relaxedFallback = if (catalogRuntime.mode == RelaxedPeripheralFallback.MODE) {
            val flashAperture = meta.devices.singleOrNull { it.port == "soc-flash.aperture" }
            val flashRegs = meta.devices.singleOrNull { it.port == "soc-flash.registers" }
            val dmaRegs = meta.devices.singleOrNull { it.port == "soc-dma.registers" }
            val flashLayout = if (flashAperture != null) {
                RelaxedPeripheralFallback.FlashLayout(
                    apertureBase = flashAperture.address,
                    apertureSize = flashAperture.size,
                    registersBase = flashRegs?.address ?: 0L,
                    registersSize = flashRegs?.size ?: 0L,
                    dmaRegistersBase = dmaRegs?.address ?: 0L,
                    dmaRegistersSize = dmaRegs?.size ?: 0L,
                )
            } else {
                null
            }
            RelaxedPeripheralFallback(
                context = object : RelaxedPeripheralFallback.Context {
                    override fun pc(): Long = pc
                    override fun tick(): Long = virtualTick
                    override fun stage(): String = FirmwareStage.highestReached(telemetry.stageMask)?.label ?: "NONE"
                },
                flashLayout = flashLayout,
                telemetryPublisher = telemetryPublisher,
            )
        } else {
            null
        }
        val router = MmioRouter(mmioWindows, discoveryBus, relaxedFallback)
        if (!customMode && image != null && loader != null) {
        val sdHost=host.components().singleOrNull{it.descriptor.id=="soc-sd-host"}?.component?.state().orEmpty()
        if(sdHost["enabled"]==true || additional["soc-sd-host"].orEmpty().isNotEmpty()){require("soc-sd-host" in selected);contracts=contracts+("sdHost" to com.github.nyabanban.fuji.emulator.firmware.SdHostContracts.verify(image,sdHost,additional["soc-sd-host"].orEmpty(),activeWindows))}
        if ("soc-configuration" in selected) {
            val state = host.components().single { it.descriptor.id == "soc-configuration" }.component.state()
            @Suppress("UNCHECKED_CAST") val counts = (state.getValue("groupCounts") as List<Number>).map { it.toInt() }
            contracts = contracts + ("configurationTable" to com.github.nyabanban.fuji.emulator.firmware.LoaderContracts.configuration(loader, meta.devices.filter { it.id == "soc-configuration" && !it.optional }, counts))
            if(state["baseParametersEnabled"]==true) {
                require(meta.id=="gfx100s-213") { "base-parameter metadata has no selected-DAT qualification" }
                val report=com.github.nyabanban.fuji.emulator.firmware.BaseParameterContracts.observe(image,meta.devices)
                @Suppress("UNCHECKED_CAST") val base=state["baseParameters"]as Map<String,Any?>
                require(base["groupIndices"]==com.github.nyabanban.fuji.emulator.firmware.BaseParameterContracts.groupIndices) { "base-parameter JAR group domains differ from DAT" }
                val expected=com.github.nyabanban.fuji.emulator.firmware.BaseParameterContracts.bases.indices.map {"soc-configuration.base-status$it"}
                require(additional["soc-configuration"]==expected && activeWindows.filter {it.id=="soc-configuration"&&it.optional}.size==13) { "base-parameter optional status claims differ" }
                contracts=contracts+("baseParameters" to report)
            }
            ConfigurationReconfigurationAdmission.qualify(image,state,additional["soc-configuration"].orEmpty(),activeWindows)?.let {
                contracts=contracts+(ConfigurationReconfigurationAdmission.KEY to it)
            }
        }
        if("soc-system-control" in selected && meta.id=="gfx100s-213") {
            val state=host.components().single { it.descriptor.id=="soc-system-control" }.component.state()
            @Suppress("UNCHECKED_CAST") val masksA=(state.getValue("masksA") as List<Number>).map { it.toLong() }
            @Suppress("UNCHECKED_CAST") val masksB=(state.getValue("masksB") as List<Number>).map { it.toLong() }
            contracts=contracts+("retainedBanks" to com.github.nyabanban.fuji.emulator.firmware.LoaderDevicesContracts.retainedBanks(loader,meta.devices.filter { it.id=="soc-system-control" },masksA,masksB))
        }
        if(clusterProfile.isNotEmpty() && meta.id=="gfx100s-213") contracts=contracts+mapOf("cluster" to com.github.nyabanban.fuji.emulator.firmware.ClusterContracts.observe(loader,meta.devices),
            "power" to com.github.nyabanban.fuji.emulator.firmware.PowerContracts.observe(loader,image.decodeReadOnly("section4")))
        if(meta.id=="gfx100s-213" && additional[VglibInitAdmission.ID].orEmpty().contains(VglibInitAdmission.UNREADY_PORT))
            contracts=contracts+("lcdcUnready" to com.github.nyabanban.fuji.emulator.firmware.LcdcUnreadyContracts.observe(image.decodeReadOnly("section4"),image.decodeReadOnly("kernel")))
        if("soc-adc" in selected && meta.id=="gfx100s-213") contracts=contracts+("adc" to com.github.nyabanban.fuji.emulator.firmware.AdcContracts.observe(loader,meta.devices,image.decodeReadOnly("section4"),image.decodeReadOnly("kernel")))
        if("soc-i2c" in selected && meta.id=="gfx100s-213") {
            val section=image.decodeReadOnly("section4")
            contracts=contracts+("i2c" to com.github.nyabanban.fuji.emulator.firmware.I2cContracts.observe(loader,meta.devices,section))
            if(com.github.nyabanban.fuji.emulator.firmware.I2cContracts.CONTROLLER2_PORT in additional["soc-i2c"].orEmpty())
                contracts=contracts+("i2cController2" to com.github.nyabanban.fuji.emulator.firmware.I2cContracts.observeController2(section,image.decodeReadOnly("kernel"),activeWindows))
        }
        if(additional["soc-spi"].orEmpty().isNotEmpty() && meta.id=="gfx100s-213") contracts=contracts+("kernel" to com.github.nyabanban.fuji.emulator.firmware.KernelContracts.observe(loader,image.decodeReadOnly("kernel"),meta.devices,image.decodeReadOnly("section4")))
        if(clusterReset!=null && meta.id=="gfx100s-213") contracts=contracts+("reset" to com.github.nyabanban.fuji.emulator.firmware.ResetContracts.observe(loader,image.decodeReadOnly("kernel")))
        if(mmuProfile.isNotEmpty() && meta.id=="gfx100s-213")contracts=contracts+("mmu" to com.github.nyabanban.fuji.emulator.firmware.MmuContracts.observe(image.decodeReadOnly("kernel")))
        if("soc-timer" in selected && meta.id=="gfx100s-213" && host.components().single{it.descriptor.id=="soc-timer"}.component.state()["profile"]=="interval-edge-routing-experimental-v1")
            contracts=contracts+("timer" to com.github.nyabanban.fuji.emulator.firmware.TimerContracts.observe(image))
        if("soc-timer" in selected && meta.id=="gfx100s-213") {
            val timer=host.components().single{it.descriptor.id=="soc-timer"}.component.state()
            if(timer["profile"]=="interval-edge-routing-experimental-v1") {
                @Suppress("UNCHECKED_CAST") val specification=timer["specification"]as Map<String,Any?>
                val routes=specification["routes"]as List<*>
                val six=routes.map{it as Map<*,*>}.singleOrNull{(it["channel"]as Number).toInt()==6}
                if(six!=null){require((six["irq"]as Number).toInt()==63){"timer6 route differs from selected DAT descriptor"};contracts=contracts+("timer6" to com.github.nyabanban.fuji.emulator.firmware.Timer6Contracts.observe(image))}
            }
        }
        if(wordExclusive?.profile in setOf(com.github.nyabanban.fuji.emulator.cpu.WordExclusive.HALFWORD_PROFILE,com.github.nyabanban.fuji.emulator.cpu.WordExclusive.BYTE_PROFILE) && meta.id=="gfx100s-213")
            contracts=contracts+("halfword" to com.github.nyabanban.fuji.emulator.firmware.MmuContracts.halfwords(image.decodeReadOnly("kernel")))
        if(wordExclusive?.profile==com.github.nyabanban.fuji.emulator.cpu.WordExclusive.BYTE_PROFILE && meta.id=="gfx100s-213")
            contracts=contracts+("byteExclusive" to com.github.nyabanban.fuji.emulator.firmware.ByteExclusiveContracts.observe(image.decodeReadOnly("section4")))
        if("soc-selection" in selected && meta.id=="gfx100s-213" && additional["soc-selection"].orEmpty().isNotEmpty()) {
            val report=com.github.nyabanban.fuji.emulator.firmware.SelectionContracts.observe(image)
            val state=host.components().single{it.descriptor.id=="soc-selection"}.component.state()
            require(state["masks"]==com.github.nyabanban.fuji.emulator.firmware.SelectionContracts.masks && state["encodings"]==com.github.nyabanban.fuji.emulator.firmware.SelectionContracts.encodings && state["eligible"]==com.github.nyabanban.fuji.emulator.firmware.SelectionContracts.eligible) { "selection JAR domains differ from selected DAT" }
            @Suppress("UNCHECKED_CAST") val windows=report["windows"] as List<Map<String,Any>>
            require(activeWindows.filter{it.id=="soc-selection"}.size==3)
            windows.forEach { w -> require(activeWindows.any{it.id=="soc-selection" && it.port==w["port"] && it.address==(w["address"] as Number).toLong() && it.size==4L}) { "selection metadata differs from DAT word address" } }
            contracts=contracts+("selectionSpecial" to report)
        }
        val sysCtrlAdditional = additional["soc-system-control"].orEmpty()
        val bootStrapPort = "soc-system-control.boot-strap"
        val statusA200Port = "soc-system-control.status-a200"
        val statusA200V2Port = "soc-system-control.status-a200-v2"
        val statusA200V3Port = "soc-system-control.status-a200-v3"
        val usbVbusA300Port = "soc-system-control.usb-vbus-a300"
        val usbVbusA300V2Port = "soc-system-control.usb-vbus-a300-v2"
        val usbDwc3Port = "soc-system-control.usb-dwc3"
        val usb3PhyPort = "soc-system-control.usb3-phy"
        val write49530Port = "soc-system-control.fff49530-pair"
        val tail49500Port = "soc-system-control.fff49500-tail"
        if (bootStrapPort in sysCtrlAdditional) {
            require(isBootRom) { "soc-system-control.boot-strap requires boot-rom-experiment" }
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == bootStrapPort && it.address == 0xfff4a000L && it.size == 4L } != null) {
                "boot-strap metadata differs from 0xfff4a000 aligned word"
            }
        }
        if (statusA200Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == statusA200Port && it.address == 0xfff4a200L && it.size == 4L } != null) {
                "status-a200 metadata differs from 0xfff4a200 aligned word"
            }
        }
        if (statusA200V2Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == statusA200V2Port && it.address == 0xfff4a200L && it.size == 4L } != null) {
                "status-a200-v2 metadata differs from 0xfff4a200 aligned word"
            }
        }
        if (statusA200V3Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == statusA200V3Port && it.address == 0xfff4a200L && it.size == 4L } != null) {
                "status-a200-v3 metadata differs from 0xfff4a200 aligned word"
            }
        }
        if (usbVbusA300Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == usbVbusA300Port && it.address == 0xfff4a300L && it.size == 4L } != null) {
                "usb-vbus-a300 metadata differs from 0xfff4a300 aligned word"
            }
        }
        if (usbVbusA300V2Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == usbVbusA300V2Port && it.address == 0xfff4a300L && it.size == 4L } != null) {
                "usb-vbus-a300-v2 metadata differs from 0xfff4a300 aligned word"
            }
        }
        if (usbDwc3Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == usbDwc3Port && it.address == 0xffb0c100L && it.size == 0x900L } != null) {
                "usb-dwc3 metadata differs from 0xffb0c100..0xffb0ca00 window"
            }
        }
        if (usb3PhyPort in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == usb3PhyPort && it.address == 0xffb23800L && it.size == 0x404L } != null) {
                "usb3-phy metadata differs from 0xffb23800..0xffb23c04 window"
            }
        }
        if (write49530Port in sysCtrlAdditional) {
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == write49530Port && it.address == 0xfff49530L && it.size == 8L } != null) {
                "fff49530-pair metadata differs from 0xfff49530..0xfff49538 pair"
            }
        }
        if (tail49500Port in sysCtrlAdditional) {
            require(write49530Port in sysCtrlAdditional) { "soc-system-control.fff49500-tail requires fff49530-pair" }
            require(activeWindows.singleOrNull { it.id == "soc-system-control" && it.optional && it.port == tail49500Port && it.address == 0xfff49500L && it.size == 4L } != null) {
                "fff49500-tail metadata differs from 0xfff49500 aligned word"
            }
        }
        val nonStrapSysCtrlAdditional = sysCtrlAdditional.filterNot {
            it in setOf(
                bootStrapPort,
                statusA200Port,
                statusA200V2Port,
                statusA200V3Port,
                usbVbusA300Port,
                usbVbusA300V2Port,
                usbDwc3Port,
                usb3PhyPort,
                write49530Port,
                tail49500Port,
            )
        }
        if(nonStrapSysCtrlAdditional.isNotEmpty() && meta.id=="gfx100s-213") {
            val report=com.github.nyabanban.fuji.emulator.firmware.SystemWordContracts.observe(image.decodeReadOnly("section4"),image.decodeReadOnly("kernel"))
            val port=com.github.nyabanban.fuji.emulator.firmware.SystemWordContracts.PORT
            require(nonStrapSysCtrlAdditional==listOf(port) && activeWindows.count{it.id=="soc-system-control" && it.optional && it.port==port}==1 &&
                activeWindows.any{it.id=="soc-system-control" && it.optional && it.port==port && it.address==com.github.nyabanban.fuji.emulator.firmware.SystemWordContracts.ADDRESS && it.size==4L}) { "FFF48004 metadata differs from selected DAT word" }
            contracts=contracts+("systemReadWord" to report)
        }
        if(meta.id=="gfx100s-213") {
            val peer=host.components().singleOrNull{it.descriptor.id=="power-peer"}?.component?.state().orEmpty()
            val retained=(peer["inputs"] as? Map<*,*>)?.get("retained_registers") as? List<*>
            val external16=(((peer["inputs"] as? Map<*,*>)?.get("inputs") as? Map<*,*>)?.get("16") as? Number)?.toLong()
            if(peer["enabled"]==true && external16!=null && external16!=0L)
                contracts=contracts+("powerBootFlags" to com.github.nyabanban.fuji.emulator.firmware.PowerBootFlagsContracts.observe(loader,image.decodeReadOnly("kernel"),image.decodeReadOnly("section4")))
            if(peer["enabled"]==true && retained.orEmpty().any{it is Number && it.toInt()==18})
                contracts=contracts+("powerRetained18" to com.github.nyabanban.fuji.emulator.firmware.PowerRetainedContracts.observe(image.decodeReadOnly("section4")))
        }
        val blockScope=com.github.nyabanban.fuji.emulator.firmware.PowerBlockContracts.selected(config)
        val bit8Selected=config.containsKey(Power2Bit8Admission.CONFIG_KEY)
        if(bit8Selected)require(config[Power2Bit8Admission.CONFIG_KEY]==Power2Bit8Admission.PROFILE && blockScope) {
            "Power2 bit8 requires the explicit profile and complete block contract"
        }
        val maskedPeer=host.components().singleOrNull{it.descriptor.id=="power-peer"}?.component?.state().orEmpty()
        val maskedInput=PowerFiniteReadAdmission.maskedInput(maskedPeer,catalogRuntime.boundCandidateProfile("power-peer"),catalogRuntime.boundCandidateInputs("power-peer"))
        require(!(blockScope||bit8Selected)||maskedInput!=null){"power block requires explicit masked peer"}
        if(maskedInput!=null) {
            val admission15=if(maskedPeer["profile"]==PowerFiniteReadAdmission.PROFILE)Power15MaskedWriteAdmission.qualify({image.decodeReadOnly("section4")},maskedPeer,maskedInput)else null
            val admission2526=if(maskedPeer["profile"]==PowerFiniteReadAdmission.PROFILE)Power2526RetainedAdmission.qualify({image.decodeReadOnly("section4")},maskedPeer,maskedInput,admission15)else null
            val preceding=admission2526?.legacyInput?:admission15?.legacyInput?:maskedInput
            val admission2=Power2Bit8Admission.qualify({image.decodeReadOnly("section4")},maskedPeer,maskedInput,bit8Selected,preceding)
            val input=admission2?.legacyInput?:preceding
            admission15?.let {contracts=contracts+(Power15MaskedWriteAdmission.KEY to it.contract)}
            admission2526?.let {contracts=contracts+(Power2526RetainedAdmission.KEY to it.contract)}
            admission2?.let {contracts=contracts+(Power2Bit8Admission.KEY to it.contract)}
            if(blockScope){
                val block=com.github.nyabanban.fuji.emulator.firmware.PowerBlockContracts.verify(image,input)
                contracts=contracts+("powerMaskedRegister2" to block.getValue("register2Contract"))+("powerBlockTransfer" to block.filterKeys{it!="register2Contract"})
            }else contracts=contracts+("powerMaskedRegister2" to com.github.nyabanban.fuji.emulator.firmware.PowerMaskedRegisterContracts.verify(image,input))
        }
        val rtc=host.components().singleOrNull{it.descriptor.id=="rtc"}?.component?.state().orEmpty()
        if(rtc["enabled"]==true) {
            val scriptedRtc=RtcStageReadAdmission.selected(meta.id,rtc,catalogRuntime.boundLogicalProfile("rtc"))
            val peer=host.components().single{it.descriptor.id=="power-peer"}.component.state()
            require(peer["enabled"]==true && peer["registerPort"]=="rtc.registers") { "RTC endpoint must be explicitly connected to power peer" }
            val rtcApp=image.decodeReadOnly("section4")
            val report=com.github.nyabanban.fuji.emulator.firmware.RtcContracts.observe(rtcApp)
            val claims=(peer["delegatedRegisters"]as? List<*>)?.map{(it as Number).toLong()}
            require(claims==report["registers"]) { "RTC endpoint register claims differ from selected DAT" }
            contracts=contracts+("rtc" to report)
            if(scriptedRtc)contracts=contracts+("rtcStageReadInput" to RtcStageReadAdmission.observe(rtcApp))
        }
        if(additional["soc-temperature"].orEmpty().isNotEmpty()) {
            require(meta.id==com.github.nyabanban.fuji.emulator.firmware.TemperatureContracts.FIRMWARE_ID) {"temperature profile is not qualified for selected firmware"}
            val report=com.github.nyabanban.fuji.emulator.firmware.TemperatureContracts.observe(image.decodeReadOnly("section4"))
            require(additional["soc-temperature"]==com.github.nyabanban.fuji.emulator.firmware.TemperatureContracts.ports) {"temperature claims differ from selected DAT"}
            @Suppress("UNCHECKED_CAST")
            val expected=report["windows"]as List<Map<String,Any>>
            require(activeWindows.filter{it.id=="soc-temperature"}.size==3 && expected.all{e->activeWindows.any{w->w.id=="soc-temperature"&&w.optional&&w.port==e["port"]&&w.address==e["address"]&&w.size==e["size"]}}) {"temperature metadata differs from DAT producers"}
            contracts=contracts+("temperature" to report)
        }
        val dram=host.components().singleOrNull{it.descriptor.id=="soc-dram"}?.component?.state().orEmpty()
        if(dram["enabled"]==true || additional["soc-dram"].orEmpty().isNotEmpty()) {
            contracts=contracts+("dram" to com.github.nyabanban.fuji.emulator.firmware.DramContracts.verify(meta,dram,additional["soc-dram"].orEmpty(),activeWindows){image.decodeReadOnly("section4")})
        }
        val serial=host.components().singleOrNull{it.descriptor.id=="soc-serial-engine"}?.component?.state().orEmpty()
        val dmaTransmit=host.components().singleOrNull{it.descriptor.id=="soc-dma"}?.component?.state().orEmpty()
        if(dmaTransmit.containsKey("transmitPeerPort")) {
            require(dmaTransmit["transmitPeerPort"] is String){"DMA transmit peer state type differs"}
            val peer=dmaTransmit["transmitPeerPort"]as String
            if(peer.isNotEmpty()) {
                require("soc-dma" in selected && "soc-serial-engine" in selected && serial["profile"]==com.github.nyabanban.fuji.emulator.firmware.SerialTransmitContracts.PROFILE){"DMA transmit requires selected serial v3"}
                val channels=com.github.nyabanban.fuji.emulator.firmware.SerialTransmitContracts.selected(serial)
                require(channels.any{com.github.nyabanban.fuji.emulator.firmware.SerialTransmitContracts.peerPort(it)==peer}){"DMA transmit peer is not a selected channel"}
                require(host.ports.find(peer,com.github.nyabanban.fuji.emulator.api.DmaDestinationPeer::class.java)!=null){"DMA transmit peer missing before native allocation"}
            }
        }

        if(serial["enabled"]==true || additional["soc-serial-engine"].orEmpty().isNotEmpty()) {
            contracts=contracts+("serialShared" to com.github.nyabanban.fuji.emulator.firmware.SerialSharedContracts.verify(meta,serial,additional["soc-serial-engine"].orEmpty(),activeWindows){image.decodeReadOnly("section4")})
        }
        }
        initializeDynarmic(
            engineSelection = engineSelection,
            artifacts = artifacts,
            selected = selected,
            customMode = customMode,
            selectedMemory = selectedMemory,
            memoryDefinition = memoryDefinition,
            configMemoryPages = configMemoryPages,
            runtimeFlashStagingInput = runtimeFlashStaging,
            segmentPlan = segmentPlan,
            coreInterrupts = coreInterrupts,
            activeWindows = activeWindows,
            router = router,
        )
    }
    private fun initializeDynarmic(
        engineSelection: CpuEngineConfig.Selection,
        artifacts: SessionBootLoader.LoadedArtifacts,
        selected: List<String>,
        customMode: Boolean,
        selectedMemory: com.github.nyabanban.fuji.emulator.api.MemoryLayout,
        memoryDefinition: List<com.github.nyabanban.fuji.emulator.api.MemoryRegion>,
        configMemoryPages: ConfigMemoryPagesAdmission.Result?,
        runtimeFlashStagingInput: RuntimeFlashStagingAdmission.Result?,
        segmentPlan: com.github.nyabanban.fuji.emulator.firmware.SegmentsContracts.VerifiedPlan?,
        coreInterrupts: com.github.nyabanban.fuji.emulator.api.CoreInterruptPort?,
        activeWindows: List<com.github.nyabanban.fuji.emulator.firmware.DeviceWindow>,
        router: MmioRouter,
    ) {
        val meta = artifacts.meta
        val isBootRom = artifacts.isBootRom
        val loadedRaw = artifacts.loadedRaw
        val image = artifacts.image
        val loader = artifacts.loader
        val dynLib = checkNotNull(engineSelection.dynarmicLibrary)
        val numCores = if (clusterProfile.isNotEmpty()) 4 else 1
        val targetCbar = cbarInput ?: 0xFFFF_0000L
        val dyn = pooledDynarmic?.let { reused ->
            pooledDynarmic = null
            if (reused.coreCount() == numCores && reused.cbarBase() == targetCbar) {
                reused
            } else {
                reused.close()
                Dynarmic(dynLib, numCores, targetCbar)
            }
        } ?: Dynarmic(dynLib, numCores, targetCbar)
        var runtimeFlashStaging = runtimeFlashStagingInput
        try {
            host.ports.register("cpu", "cpu.execution-location", com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource::class.java, object : com.github.nyabanban.fuji.emulator.api.ExecutionLocationSource {
                override fun current() = com.github.nyabanban.fuji.emulator.api.ExecutionLocation(currentCoreIndex(), pc)
                override fun currentCore(): Int = currentCoreIndex()
                override fun currentPcVirtual(): Long = pc
            })
            nativeCbar = dyn.getCp15(0, 4, 15, 0, 0)
            nativeMpidr = dyn.getCp15(0, 0, 0, 0, 5)
            host.ports.register("cpu", "board.memory-layouts", com.github.nyabanban.fuji.emulator.api.MemoryLayoutSource::class.java, object : com.github.nyabanban.fuji.emulator.api.MemoryLayoutSource {
                override fun regions(profile: String) = meta.memoryProfiles[profile] ?: error("memory profile absent from selected firmware metadata")
            })
            val layout = host.ports.find("system-memory.layout", com.github.nyabanban.fuji.emulator.api.MemoryLayout::class.java) ?: error("system-memory JAR missing")
            val sharedBootSramIspPage = isBootRom &&
                layout.regions().any { it.id == "boot-rom-stage1-sram" && it.address == 0xfff10000L && it.size == 0x2000L } &&
                router.windows.any { it.id == "soc-isp" && it.base < 0xfff12000L && (it.base + it.size) > 0xfff10000L }
            val nativeMmio = router.pageRanges()
            layout.regions().forEach { r ->
                val overlaps = nativeMmio.filter { (base, size) -> r.address < base + size && base < r.address + r.size }
                if (sharedBootSramIspPage && r.id == "boot-rom-stage1-sram") {
                    require(overlaps.all { (base, size) -> maxOf(r.address, base) >= 0xfff10000L && minOf(r.address + r.size, base + size) <= 0xfff12000L }) {
                        "RAM overlaps MMIO transport"
                    }
                } else {
                    require(overlaps.isEmpty()) { "RAM overlaps MMIO transport" }
                }
            }
            SessionBootLoader.validateBootMemoryRegions(layout, artifacts)
            memoryWatches.forEach { (address, size) -> layout.check(address, size.toLong(), 1) }
            if (!customMode && image != null && selectedMemory.profile in setOf(RawSegment2TailAdmission.PROFILE, FixedTableRow5Admission.PROFILE, SevenRowTableAdmission.PROFILE, XlogBufferAdmission.PROFILE, XlogBufferAdmission.ALL_PROFILE, FlashloadBufferAdmission.PROFILE)) {
                runtimeFlashStaging = RawSegment2TailAdmission.verify(image, meta.memoryProfiles.getValue(RawSegment2TailAdmission.PROFILE), checkNotNull(runtimeFlashStaging), layout)
                if (selectedMemory.profile in setOf(FixedTableRow5Admission.PROFILE, SevenRowTableAdmission.PROFILE, XlogBufferAdmission.PROFILE, XlogBufferAdmission.ALL_PROFILE, FlashloadBufferAdmission.PROFILE)) runtimeFlashStaging = FixedTableRow5Admission.verify(image, meta.memoryProfiles.getValue(FixedTableRow5Admission.PROFILE), checkNotNull(runtimeFlashStaging), layout)
                if (selectedMemory.profile in setOf(SevenRowTableAdmission.PROFILE, XlogBufferAdmission.PROFILE, XlogBufferAdmission.ALL_PROFILE, FlashloadBufferAdmission.PROFILE)) runtimeFlashStaging = SevenRowTableAdmission.verify(image, meta.memoryProfiles.getValue(SevenRowTableAdmission.PROFILE), checkNotNull(runtimeFlashStaging), layout)
                if (selectedMemory.profile == XlogBufferAdmission.PROFILE) runtimeFlashStaging = XlogBufferAdmission.verify(image, memoryDefinition, checkNotNull(runtimeFlashStaging), layout)
                if (selectedMemory.profile in setOf(XlogBufferAdmission.ALL_PROFILE, FlashloadBufferAdmission.PROFILE)) runtimeFlashStaging = XlogBufferAdmission.verifyAll(image, meta.memoryProfiles.getValue(XlogBufferAdmission.ALL_PROFILE), checkNotNull(runtimeFlashStaging), layout)
                if (selectedMemory.profile == FlashloadBufferAdmission.PROFILE) runtimeFlashStaging = FlashloadBufferAdmission.verify(image, memoryDefinition, checkNotNull(runtimeFlashStaging), layout)
                require(layout.regions() == memoryDefinition.sortedBy { it.address }) { "Complete selected table-buffer live layout differs" }
                contracts = contracts + ("segments" to checkNotNull(runtimeFlashStaging).liveReport)
            }
            (runtimeFlashStaging?.liveRegions ?: configMemoryPages?.liveRegions)?.let { require(layout.regions().sortedBy { r -> r.address } == it) { "Complete live RAM differs before native mapping" } }
            layout.regions().forEach { dyn.map(it.address, it.size, it.permissions) }
            memoryLayout = layout
            if (!customMode && image != null && loader != null && layout.regions().any { it.id == "metadata-workspace" } && meta.id == "gfx100s-213") {
                val aperture = meta.devices.single { it.port == "soc-flash.aperture" }
                val workspaceRegions = configMemoryPages?.legacyPlan?.regions
                    ?: when (selectedMemory.profile) {
                        "boot-rom-full-boot-v1" -> segmentPlan?.regions ?: layout.regions().filterNot { it.id.startsWith("boot-rom") }
                        "boot-rom-cold-start-segments-experimental-v2" -> layout.regions().filterNot { it.id.startsWith("boot-rom") }
                        else -> layout.regions()
                    }
                contracts = contracts + ("workspace" to com.github.nyabanban.fuji.emulator.firmware.LoaderContracts.workspace(image, loader, aperture.address, workspaceRegions, segmentPlan))
            }
            if (!customMode && image != null && loader != null && "soc-dma" in selected && meta.id == "gfx100s-213" && host.components().single { it.descriptor.id == "soc-dma" }.component.state()["softwareProfile"] == "software-request44-experimental-v1") {
                contracts = contracts + ("software" to com.github.nyabanban.fuji.emulator.firmware.SoftwareContracts.observe(image, loader, layout.regions()))
            }
            SessionBootLoader.writeInitialBootImages(dyn, artifacts)
            val (dynInitialPc, _) = SessionBootLoader.applyDynarmicResetVectors(dyn, numCores, artifacts)
            initialPc = dynInitialPc
            pc = initialPc
            dyn.setAutoExceptions(
                if (relaxedMode) {
                    Dynarmic.AUTO_EXCP_SVC or Dynarmic.AUTO_EXCP_UNDEF or Dynarmic.AUTO_EXCP_PABT
                } else {
                    Dynarmic.AUTO_EXCP_ALL
                },
            )
            if (tracePoints.isNotEmpty()) {
                dyn.setTracepoints(tracePoints.sorted().toLongArray())
            }
            if (image != null) {
                host.ports.register("cpu", "firmware.update", com.github.nyabanban.fuji.emulator.api.UpdateDataPort::class.java, image.inputPort())
            }
            host.ports.register("cpu", "board.layout", com.github.nyabanban.fuji.emulator.api.AddressLayout::class.java, object : com.github.nyabanban.fuji.emulator.api.AddressLayout {
                override fun window(port: String): com.github.nyabanban.fuji.emulator.api.AddressWindow {
                    val w = meta.devices.single { it.port == port }
                    return com.github.nyabanban.fuji.emulator.api.AddressWindow(w.port, w.address, w.size)
                }
            })
            var stage2XipRange: Pair<Long, Long>? = null
            if ("soc-flash" in selected && meta.id == "gfx100s-213") {
                val imagePort = host.components().single { it.descriptor.id == "soc-flash" }.component.state().getValue("imagePort") as String
                val flashImage = host.ports.find(imagePort, com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort::class.java) ?: error("flash image port missing")
                if (image != null) {
                    if (!customMode && loader != null) {
                        contracts = contracts + ("storage" to com.github.nyabanban.fuji.emulator.firmware.LoaderContracts.storage(image, flashImage))
                    } else {
                        require(flashImage.length == image.seg0Length) { "flash-image seg0 length differs from custom DAT" }
                    }
                    val flashAperture = meta.devices.single { it.port == "soc-flash.aperture" }
                    if (!customMode && loader != null && flashImage.experimentalTail != null) {
                        contracts = contracts + ("tail" to com.github.nyabanban.fuji.emulator.firmware.LoaderContracts.tail(image, loader, flashImage, flashAperture.address, layout.regions()))
                    }
                }
                stage2XipRange = SessionBootLoader.mapStage2XipIfNeeded(
                    backend = dyn,
                    meta = meta,
                    flashImage = flashImage,
                    enableStage2Xip = isBootRom && (relaxedMode || (catalogRuntime.mode == "discovery" && discoveryBus?.hasBootRomStage1DdrInit == true)),
                )
            }
            if (sharedBootSramIspPage) {
                val excluded = buildList {
                    stage2XipRange?.let { add(it.first to (it.first + it.second)) }
                    add(0xfff10000L to 0xfff12000L)
                }.sortedBy { it.first }
                router.pageRanges().forEach { (base, size) ->
                    var cur = base
                    val end = base + size
                    for ((exStart, exEnd) in excluded) {
                        if (exEnd <= cur || exStart >= end) continue
                        if (cur < exStart) dyn.mmio(cur, exStart - cur)
                        cur = maxOf(cur, exEnd)
                    }
                    if (cur < end) dyn.mmio(cur, end - cur)
                }
                if (relaxedFallback != null) {
                    val occupied = buildList {
                        layout.regions().forEach { add(it.address to (it.address + it.size)) }
                        router.pageRanges().forEach { (b, s) -> add(b to (b + s)) }
                        stage2XipRange?.let { (b, s) -> add(b to (b + s)) }
                    }
                    RelaxedPeripheralFallback.subtractIntervals(
                        RelaxedPeripheralFallback.PERIPHERAL_APERTURE_BASE,
                        RelaxedPeripheralFallback.PERIPHERAL_APERTURE_END,
                        occupied,
                    ).forEach { (base, size) -> dyn.mmio(base, size) }
                }
                bootSramIspHandoffPending = true
            } else {
                bootSramIspHandoffPending = false
                SessionBootLoader.mapMmioAndRelaxedAperture(dyn, layout, router, stage2XipRange, relaxedFallback)
            }
            var mappedHighRam: List<com.github.nyabanban.fuji.emulator.api.MemoryRegion> = emptyList()
            val cpuRam = object : com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort {
                private fun checkHighRam(address: Long, size: Long) {
                    val end = address + size
                    require(
                        size > 0L && end >= address &&
                            mappedHighRam.any { r -> address >= r.address && end <= r.address + r.size },
                    ) {
                        "transaction outside declared 40-bit high RAM: 0x${address.toString(16)} size=$size"
                    }
                }
                override fun check(address: Long, size: Long, write: Boolean) {
                    host.owner.requireOwner()
                    if (address >= 0x1_0000_0000L) {
                        checkHighRam(address, size)
                    } else {
                        layout.check(address, size, if (write) 2 else 1)
                    }
                }
                override fun sliceDirect(address: Long, size: Long, write: Boolean): java.lang.foreign.MemorySegment? {
                    check(address, size, write)
                    return dyn.sliceDirectRam(address, size)
                }
                override fun notifyDirectWrite(address: Long, size: Long) {
                    if (address < 0x1_0000_0000L && size > 0L) {
                        dyn.invalidateRange(address, size)
                    }
                }
                override fun read(address: Long, destination: java.nio.ByteBuffer) {
                    val size = destination.remaining()
                    check(address, size.toLong(), false)
                    if (address >= 0x1_0000_0000L) {
                        dyn.readHighMemory(address, destination)
                    } else {
                        dyn.readMemoryInto(address, destination)
                    }
                }
                override fun write(address: Long, source: java.nio.ByteBuffer) {
                    val size = source.remaining()
                    check(address, size.toLong(), true)
                    if (address >= 0x1_0000_0000L) {
                        dyn.writeHighMemory(address, source)
                    } else {
                        dyn.writeMemory(address, source, true)
                    }
                }
            }
            imageDmaMemory?.close()
            val dmaRam = if (RelaxedBootstrapRam.selected(config, relaxedMode)) {
                ImageDmaMemory(cpuRam, host.owner::requireOwner).also { imageDmaMemory = it }
            } else {
                imageDmaMemory = null
                cpuRam
            }
            host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, dmaRam)
            val interrupts = if ("soc-gic" in selected) host.ports.find("soc-gic.cpu", com.github.nyabanban.fuji.emulator.api.CpuInterruptPort::class.java) ?: error("GIC CPU port missing") else null
            cpuInterrupts = interrupts
            if (relaxedMode && "soc-dma" in selected) {
                host.components().singleOrNull { it.descriptor.id == "soc-dma" }?.component?.let { dmaComp ->
                    if (dmaComp.state()["auditTransfers"] == null) {
                        dmaComp.applySettings(mapOf("auditTransfers" to false))
                    }
                }
            }
            if (clusterProfile.isNotEmpty()) {
                dynCluster = DynarmicCluster(dyn, checkNotNull(coreInterrupts), clusterReset)
            }
            val dynIrqLineSub = host.events.subscribeIrqLine { tick, line, high ->
                val coreIdx = currentCoreIndex()
                irqLines.add(tick, line, high, dyn.getCpsr(coreIdx), pc, if (hasCluster()) coreIdx else null)
                irqLineCount++
            }
            val dynOtherSub = host.events.subscribeNonIrqLine { event ->
                if (event.kind == "configuration") {
                    idleRouter?.invalidateSchedule()
                    dynShadow.onHostEvent(dyn, event.source, event.kind)
                } else if (event.source == "buttons") {
                    idleRouter?.invalidateSchedule()
                    if (event.values["power"] == false) {
                        sawPowerSwitchOffDuringBoot = true
                    }
                    dynShadow.onHostEvent(dyn, event.source, event.kind)
                } else if (event.source == "cmos" || event.source == "tmd2772" ||
                    event.source == "i2c-bus" || event.source == "bcm43438" ||
                    event.source == "sdcard-reader" || event.source == "lens" || event.source == "mount"
                ) {
                    idleRouter?.invalidateSchedule()
                }
            }
            if (relaxedMode && onSubMcuColdReset != null) {
                val initButtons = host.ports.find("buttons", com.github.nyabanban.fuji.emulator.api.ButtonPort::class.java)
                if (initButtons != null && !initButtons.pressed("power")) {
                    sawPowerSwitchOffDuringBoot = true
                }
            }
            irqSubscription = AutoCloseable {
                dynIrqLineSub.close()
                dynOtherSub.close()
            }
            ispLiveViewDma = host.ports.find("soc-isp.live-view-dma", LiveViewDmaPort::class.java)
            ramWriteRanges = RamWriteRanges(layout.regions())
            dyn.setMmioHandler(object : Dynarmic.MmioHandler {
                override fun read(physicalAddress: Long, size: Int): Long {
                    val profiling = telemetry.perfProfilingEnabled
                    val mmioStart = if (profiling) System.nanoTime() else 0L
                    val prevPhase = if (profiling) telemetry.currentPhase else 0
                    if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_MMIO_UPCALL
                    try {
                        var tickedShortenedDeadline = false
                        if (dyn.isRunning) {
                            val fastTick = dyn.currentVirtualTick()
                            if (fastTick != virtualTick) {
                                virtualTick = fastTick
                                host.advanceTick(virtualTick)
                                val dueSched = if (fastInlineTimerActive) {
                                    router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                } else {
                                    router.refreshNextScheduledTick()
                                }
                                if (dueSched != Long.MAX_VALUE && dueSched <= virtualTick) {
                                    router.beforeInstruction(virtualTick)
                                    dynShadow.onScheduledDevicesTicked(dyn)
                                    if (dynShadow.timerIrqShadowEnabled) {
                                        val postOther = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                        dynShadow.updateNextOtherDeadline(postOther)
                                        if (fastInlineTimerActive && postOther != Long.MAX_VALUE && postOther < fastRunEndTick) {
                                            tickedShortenedDeadline = true
                                        }
                                    }
                                }
                            }
                            val activeCore = currentCoreIndex()
                            if (fastClusterBatchActive) {
                                coreInterrupts?.selectCore(activeCore)
                            }
                            pc = dyn.currentPc(activeCore)
                            if (profiling) {
                                telemetry.currentPc = pc
                                telemetry.currentCore = activeCore
                            }
                        }
                        val prevNextSched = if (fastInlineTimerActive) 0L else router.refreshNextScheduledTick()
                        val result = router.read(physicalAddress, size)
                        reads++
                        recordTransaction(false, physicalAddress, size, result)
                        observeMmioStage(physicalAddress)
                        dynShadow.onMmioRead(dyn, physicalAddress, size, result)
                        if (dyn.isRunning) {
                            val coreIdx = currentCoreIndex()
                            val deadlineShortened = if (fastInlineTimerActive) {
                                tickedShortenedDeadline || if (router.isScheduleDirty()) {
                                    val otherSched = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                    val earlierOther = dynShadow.updateNextOtherDeadline(otherSched)
                                    otherSched != Long.MAX_VALUE && (
                                        otherSched <= virtualTick ||
                                            (earlierOther && otherSched < fastRunEndTick)
                                        )
                                } else false
                            } else {
                                val nextSched = router.refreshNextScheduledTick()
                                val otherDeadlineShortened = if (dynShadow.timerIrqShadowEnabled) {
                                    val otherSched = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                    dynShadow.updateNextOtherDeadline(otherSched)
                                    otherSched != Long.MAX_VALUE && (otherSched - 1L) < fastRunEndTick
                                } else false
                                otherDeadlineShortened || (
                                    nextSched != Long.MAX_VALUE && (
                                        (nextSched - 1L) < fastRunEndTick || nextSched < prevNextSched
                                        )
                                    )
                            }
                            val fastIrq = dynShadow.coreHasPendingIrqFast(coreIdx)
                            val irqNow = if (fastIrq >= 0) fastIrq != 0 else (interrupts?.hasPending() == true)
                            dyn.setIrqPendingIfChanged(coreIdx, irqNow)
                            if (fastClusterBatchActive) dynCluster?.updateBatchCoreIrq(coreIdx, irqNow)
                            val irqUnmasked = !fastAutoIrqActive && irqNow && (dyn.getCpsr(coreIdx) and 0x80L) == 0L
                            if (irqUnmasked || deadlineShortened) {
                                dyn.haltRun()
                            }
                        }
                        return result
                    } catch (error: Throwable) {
                        fail(error.message ?: "MMIO refusal", access("read", physicalAddress, size, null))
                        throw error
                    } finally {
                        if (profiling) {
                            val dt = (System.nanoTime() - mmioStart).coerceAtLeast(0L)
                            sliceMmioNanos += dt
                            telemetry.recordMmioAccess(physicalAddress, false, dt)
                            telemetry.currentPhase = prevPhase
                        }
                    }
                }
                override fun write(physicalAddress: Long, size: Int, value: Long) {
                    val profiling = telemetry.perfProfilingEnabled
                    val mmioStart = if (profiling) System.nanoTime() else 0L
                    val prevPhase = if (profiling) telemetry.currentPhase else 0
                    if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_MMIO_UPCALL
                    try {
                        var tickedShortenedDeadline = false
                        if (dyn.isRunning) {
                            val fastTick = dyn.currentVirtualTick()
                            if (fastTick != virtualTick) {
                                virtualTick = fastTick
                                host.advanceTick(virtualTick)
                                val dueSched = if (fastInlineTimerActive) {
                                    router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                } else {
                                    router.refreshNextScheduledTick()
                                }
                                if (dueSched != Long.MAX_VALUE && dueSched <= virtualTick) {
                                    router.beforeInstruction(virtualTick)
                                    dynShadow.onScheduledDevicesTicked(dyn)
                                    if (dynShadow.timerIrqShadowEnabled) {
                                        val postOther = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                        dynShadow.updateNextOtherDeadline(postOther)
                                        if (fastInlineTimerActive && postOther != Long.MAX_VALUE && postOther < fastRunEndTick) {
                                            tickedShortenedDeadline = true
                                        }
                                    }
                                }
                            }
                            val activeCore = currentCoreIndex()
                            if (fastClusterBatchActive) {
                                coreInterrupts?.selectCore(activeCore)
                            }
                            pc = dyn.currentPc(activeCore)
                            if (profiling) {
                                telemetry.currentPc = pc
                                telemetry.currentCore = activeCore
                            }
                        }
                        if (physicalAddress == 0xFFFA9600L && size == 4 && (value and 1L) != 0L) {
                            pumpLiveViewDmaFromLcdcCommit()
                        }
                        val prevNextSched = if (fastInlineTimerActive) 0L else router.refreshNextScheduledTick()
                        router.write(physicalAddress, size, value)
                        writes++
                        recordTransaction(true, physicalAddress, size, value)
                        observeMmioStage(physicalAddress)
                        observePowerHoldWrite(physicalAddress, size, value)
                        dynShadow.onMmioWrite(dyn, physicalAddress, size)
                        if (dyn.isRunning) {
                            val coreIdx = currentCoreIndex()
                            val deadlineShortened = if (fastInlineTimerActive) {
                                tickedShortenedDeadline || if (router.isScheduleDirty()) {
                                    val otherSched = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                    val earlierOther = dynShadow.updateNextOtherDeadline(otherSched)
                                    val otherShort = otherSched != Long.MAX_VALUE && (
                                        otherSched <= virtualTick ||
                                            (earlierOther && otherSched < fastRunEndTick)
                                        )
                                    val immediateTimer = if (!otherShort && dynShadow.isTimerAddress(physicalAddress)) {
                                        val t = dynShadow.timerDevice?.nextInterestedTick() ?: Long.MAX_VALUE
                                        t <= virtualTick
                                    } else false
                                    otherShort || immediateTimer
                                } else false
                            } else {
                                val nextSched = router.refreshNextScheduledTick()
                                val otherDeadlineShortened = if (dynShadow.timerIrqShadowEnabled) {
                                    val otherSched = router.nextScheduledTickExcluding(dynShadow.timerDevice)
                                    dynShadow.updateNextOtherDeadline(otherSched)
                                    otherSched != Long.MAX_VALUE && (otherSched - 1L) < fastRunEndTick
                                } else false
                                otherDeadlineShortened || (
                                    nextSched != Long.MAX_VALUE && (
                                        (nextSched - 1L) < fastRunEndTick || nextSched < prevNextSched
                                        )
                                    )
                            }
                            val fastMask = if (fastClusterBatchActive) dynShadow.allCoresPendingIrqMaskFast() else -1
                            val irqNow = if (fastMask >= 0) {
                                (fastMask and (1 shl coreIdx)) != 0
                            } else {
                                val fastIrq = dynShadow.coreHasPendingIrqFast(coreIdx)
                                if (fastIrq >= 0) fastIrq != 0 else (interrupts?.hasPending() == true)
                            }
                            dyn.setIrqPendingIfChanged(coreIdx, irqNow)
                            if (fastClusterBatchActive) dynCluster?.updateBatchCoreIrq(coreIdx, irqNow)
                            val irqUnmasked = !fastAutoIrqActive && irqNow && (dyn.getCpsr(coreIdx) and 0x80L) == 0L
                            val otherCoreWoken = if (fastAutoIrqActive && fastClusterBatchActive) {
                                false
                            } else if (fastClusterBatchActive) {
                                if (fastMask >= 0) {
                                    dynCluster!!.anyOtherCoreIrqGainedFromMask(coreIdx, fastMask)
                                } else {
                                    dynCluster!!.anyOtherCoreIrqGained(coreIdx)
                                }
                            } else {
                                dynCluster != null && fastMultiQuantumActive && dynCluster!!.anyOtherCoreWoken()
                            }
                            if (irqUnmasked || deadlineShortened || otherCoreWoken || powerHoldDeasserted) {
                                dyn.haltRun()
                            }
                        }
                    } catch (error: Throwable) {
                        fail(error.message ?: "MMIO refusal", access("write", physicalAddress, size, value))
                        throw error
                    } finally {
                        if (profiling) {
                            val dt = (System.nanoTime() - mmioStart).coerceAtLeast(0L)
                            sliceMmioNanos += dt
                            telemetry.recordMmioAccess(physicalAddress, true, dt)
                            telemetry.currentPhase = prevPhase
                        }
                    }
                }
            })
            firmwareSha = artifacts.firmwareSha
            firmwareId = meta.id
            stockShutterProfile = !customMode && meta.id == "gfx100s-213" && meta.sha256 == catalogRuntime.catalog.firmwareSha256
            verifiedShutterCode = false
            stillModeTransition.clear()
            bootMode = artifacts.bootMode
            nativeSha = sha256(dynLib)
            stopped = false
            reason = "running"
            dynarmic = dyn
            cpuEngineSelection = engineSelection
            activeIds = selected.toSet()
            idleRouter = router
            if (unlimitedBudget) idleClock?.enableUnlimited()
            if (RelaxedBootstrapRam.selected(config, relaxedMode)) {
                selectAdditionalRam(RelaxedBootstrapRam.buildPlan(catalogRuntime.catalog, layout.profile, layout.regions()))
                if (dyn.supportsHighPhysRam()) {
                    RelaxedBootstrapRam.HIGH_PA_REGIONS.forEach { region ->
                        dyn.mapHighRam(region.address, region.size, region.permissions)
                    }
                    mappedHighRam = RelaxedBootstrapRam.HIGH_PA_REGIONS
                } else {
                    log.warn(
                        "[FALLBACK][DYNARMIC] Native library lacks 40-bit Supersection high-PA RAM support (fuji_dyn_map_ram64); 4K Movie and high-bank LPDDR4 pools (>4 GiB) will be unavailable",
                    )
                }
            }
            if (loadedRaw != null) {
                SessionBootLoader.loadRawImageAndConfigureRegisters(dyn, layout, loadedRaw)
            }
            mainDisplay = host.ports.find("display.main", DisplayPort::class.java)
            stage2Ranges = buildStageRanges(activeWindows, setOf("soc-gic", "soc-timer"))
            stage3Ranges = buildStageRanges(activeWindows, setOf("soc-flash", "soc-sd-host"))
            stage4Ranges = buildStageRanges(activeWindows, setOf("soc-vglib-init"))
            mmioPageLabels = buildMmioPageLabels(activeWindows)
            dynShadow.initialize(host, dyn, activeWindows, relaxedMode)
            dynCluster?.setFastPendingIrqMaskProvider(
                if (dynShadow.gicShadowEnabled) dynShadow::allCoresPendingIrqMaskFast else null,
            )
            fastAutoIrqActive = relaxedMode && dynShadow.gicShadowEnabled && tracePoints.isEmpty()
            if (fastAutoIrqActive) {
                dyn.setAutoExceptions(
                    Dynarmic.AUTO_EXCP_SVC or Dynarmic.AUTO_EXCP_UNDEF or Dynarmic.AUTO_EXCP_PABT or Dynarmic.AUTO_EXCP_IRQ,
                )
            }
            // Model 4-core Cortex-A7 SMP clock scaling (vtickDiv=7 for 4-core relaxed Dynarmic:
            // ~94.5 MHz/core when all 4 cores are active, ~378 MHz single-core vs 54 MHz APB timer).
            // Clears both BUG-29 Boot #2 create_task(0x22) watchdog race AND BUG-V3-9-2 boot-hold
            // DISP/BACK FWUP (0x0601/0x060a) across all SD configs while keeping normal boot DABT-free.
            dynVtickDivider = if (relaxedMode && numCores > 1 && dyn.supportsVtickDivider()) 7 else 1
            dyn.setVtickDivider(dynVtickDivider)
            val smpMtRequested = engineSelection.smpHostThreads && numCores > 1
            val smpMtEffective = if (smpMtRequested) {
                if (dyn.supportsSmpHostThreads()) {
                    dyn.setSmpHostThreads(true)
                } else {
                    log.warn(
                        "[FALLBACK][CPU-ENGINE] smpHostThreads=true requested, but native Dynarmic library lacks fuji_dyn_set_smp_host_threads; falling back to single-threaded round-robin",
                    )
                    false
                }
            } else {
                dyn.setSmpHostThreads(false)
                false
            }
            telemetry.smpHostThreadsEnabled = smpMtEffective
            log.info(
                "[CPU-ENGINE] Initialized Dynarmic ARMv7-A JIT backend: library={} cores={} requested={} abi={} smpHostThreads={} fastmem={}(dualView={}) gic={} cluster={} timerIrq={} vtickDiv={} nativeFastpaths(timer={},gpio={},config={},adc={},ispRanges={},mirrors={}({}slots))",
                dynLib, numCores, engineSelection.requested, dyn.abiVersion(), smpMtEffective,
                dyn.isFastmemActive(), dyn.isFastmemDualView(), dynShadow.gicShadowEnabled,
                dyn.supportsLiveClusterIrq(), dynShadow.timerIrqShadowEnabled, dynVtickDivider,
                dynShadow.timerShadowEnabled, dynShadow.gpioRoEnabled, dynShadow.configRoEnabled,
                dynShadow.adcRoEnabled, dynShadow.ispRoRangeCount,
                dynShadow.mmioMirrorWindowCount, dynShadow.mmioMirrorSlotCount,
            )
            for (diag in dyn.fallbackDiagnostics()) {
                log.warn("[FALLBACK][DYNARMIC] {}", diag)
            }
            if (relaxedMode) {
                val degraded = ArrayList<String>()
                if (!dynShadow.gicShadowEnabled) degraded.add("gicShadow=false")
                if (!dyn.supportsLiveClusterIrq()) degraded.add("liveClusterIrq=false")
                if (!dynShadow.timerIrqShadowEnabled) degraded.add("timerIrqShadow=false")
                if (!dynShadow.timerShadowEnabled) degraded.add("timerShadow=false")
                if (numCores > 1 && dynVtickDivider <= 1) degraded.add("vtickDiv=$dynVtickDivider")
                if (degraded.isNotEmpty()) {
                    log.warn("[FALLBACK][CPU-ENGINE] Dynarmic running with degraded fastpaths: {}", degraded.joinToString(", "))
                }
                for (loaded in host.components()) {
                    val bridgeClass = when (loaded.descriptor.id) {
                        "soc-vglib-init" -> "com.github.nyabanban.fuji.emulator.components.vglib.NativeRenderBridge"
                        "soc-isp" -> "com.github.nyabanban.fuji.emulator.components.isp.NativeIspSimdBridge"
                        else -> null
                    } ?: continue
                    runCatching {
                        val cls = Class.forName(bridgeClass, true, loaded.component.javaClass.classLoader)
                        val inst = cls.getField("INSTANCE").get(null)
                        val avail = cls.getMethod("isAvailable").invoke(inst) as Boolean
                        if (!avail) {
                            val err = cls.getMethod("getLastLoadError").invoke(inst)?.toString() ?: "disabled or unavailable"
                            log.warn("[FALLBACK][SIMD-RENDER] {} native SIMD bridge unavailable ({}); falling back to pure-Kotlin scalar renderer", loaded.descriptor.id, err)
                        }
                    }
                }
            }
            telemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
            publishTelemetry()
        } catch (error: Throwable) {
            dynCluster = null
            memoryLayout = null
            irqSubscription?.close()
            host.ports.removeOwner("cpu")
            dyn.close()
            throw error
        }
    }
    private fun buildMmioPageLabels(
        windows: List<com.github.nyabanban.fuji.emulator.firmware.DeviceWindow>,
    ): Map<Long, String> {
        val grouped = LinkedHashMap<Long, LinkedHashSet<String>>()
        for (w in windows) {
            if (w.size <= 0L) continue
            val firstPage = w.address and -4096L
            val lastPage = (w.address + w.size - 1L) and -4096L
            // Cap per-window expansion (e.g. huge Flash apertures) to keep label table tiny & O(1)
            if ((lastPage - firstPage) ushr 12 > 512L) {
                grouped.getOrPut(firstPage) { LinkedHashSet() }.add(w.id)
                continue
            }
            var page = firstPage
            while (page <= lastPage) {
                grouped.getOrPut(page) { LinkedHashSet() }.add(w.id)
                if (page == lastPage) break
                page += 4096L
            }
        }
        return grouped.mapValues { (_, ids) -> ids.joinToString("/") }
    }
    private fun buildStageRanges(
        windows: List<com.github.nyabanban.fuji.emulator.firmware.DeviceWindow>,
        ids: Set<String>,
    ): LongArray {
        val matched = windows.filter { it.id in ids }
        val result = LongArray(matched.size * 2)
        for (i in matched.indices) {
            result[i * 2] = matched[i].address
            result[i * 2 + 1] = matched[i].address + matched[i].size
        }
        return result
    }
    private fun inRanges(address: Long, ranges: LongArray): Boolean {
        var i = 0
        while (i < ranges.size) {
            if (address >= ranges[i] && address < ranges[i + 1]) return true
            i += 2
        }
        return false
    }
    private fun pumpLiveViewDmaFromLcdcCommit() {
        val liveDma = ispLiveViewDma
        if (liveDma != null) {
            // In relaxed interactive mode, firmware issues multiple 0xFFFA9600 LCDC commits per display frame
            // (200+ Hz); cap redundant LCDC-triggered re-pumps to 60 Hz (900,000 ticks @ 54 MHz) while leaving
            // real-silicon IRQ 107/108 and +0xD4 DMA triggers + discovery/strict determinism untouched.
            if (relaxedMode &&
                lastLcdcLiveViewPumpTick != Long.MIN_VALUE &&
                virtualTick >= lastLcdcLiveViewPumpTick &&
                (virtualTick - lastLcdcLiveViewPumpTick) < 900_000L
            ) {
                return
            }
            if (liveDma.pumpLiveViewDma(virtualTick)) {
                lastLcdcLiveViewPumpTick = virtualTick
                idleRouter?.invalidateSchedule()
            }
        } else {
            discoveryBus?.pumpLiveViewDma(virtualTick)
            idleRouter?.invalidateSchedule()
        }
    }
    private fun observeMmioStage(address: Long) {
        val mask = telemetry.stageMask
        if ((mask and FirmwareStage.MMIO_STAGE_MASK) == FirmwareStage.MMIO_STAGE_MASK) return
        if ((mask and FirmwareStage.STAGE_2_KERNEL_RTOS.bit) == 0 && inRanges(address, stage2Ranges)) {
            telemetry.markStage(FirmwareStage.STAGE_2_KERNEL_RTOS)
        } else if ((mask and FirmwareStage.STAGE_3_STORAGE_IO.bit) == 0 && inRanges(address, stage3Ranges)) {
            telemetry.markStage(FirmwareStage.STAGE_3_STORAGE_IO)
        } else if ((mask and FirmwareStage.STAGE_4_DISPLAY_INIT.bit) == 0 && inRanges(address, stage4Ranges)) {
            telemetry.markStage(FirmwareStage.STAGE_4_DISPLAY_INIT)
        }
    }
    private fun publishTelemetry() {
        telemetry.attempts = attempts
        telemetry.virtualTick = virtualTick
        telemetry.currentPc = pc
        telemetry.currentCore = currentCoreIndex()
        telemetry.status = reason
        telemetry.ownerHeartbeatNanos = System.nanoTime()
        val seq = mainDisplay?.sequence ?: 0L
        telemetry.publishedFrames = seq
        if (seq >= 1L) {
            telemetry.markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
            val firstSeq = telemetry.firstFrameSequence
            if (firstSeq == 0L) {
                telemetry.firstFrameSequence = seq
            } else if (seq > firstSeq) {
                telemetry.markStage(FirmwareStage.STAGE_6_INTERACTIVE_UI)
            }
        }
        if (stopped) {
            telemetry.running = false
        }
        if (telemetry.perfProfilingEnabled) {
            telemetry.currentPhase = FirmwareTelemetry.PHASE_IDLE
        }
    }
    private fun coreDetail(): Map<String,Any> = if (hasCluster()) mapOf("core" to currentCoreIndex()) else emptyMap()
    private fun access(kind: String, address: Long, size: Int, value: Long?) = mapOf("kind" to kind, "addressPhysical" to address, "size" to size, "value" to value, "pcVirtual" to pc, "tick" to virtualTick)+coreDetail()
    private fun recordTransaction(write: Boolean, address: Long, size: Int, value: Long) {
        transactions.record(write, address, size, value, pc, virtualTick, if (hasCluster()) currentCoreIndex() else -1)
    }
    private fun fail(message: String, detail: Map<String, Any?>) {
        if (!stopped) {
            kernelLateCall?.interrupted(KernelLateCallObservation.Reason.EXECUTION_STOP,virtualTick,currentCoreIndex(),pc)
            stopped = true; reason = message; frontier = detail+coreDetail()
        }
    }
    fun step(): Boolean = stepAtMost(4096)
    fun stepFast(): Boolean = stepAtMost(if (dynarmic != null) 65536L else 4096L)
    internal fun stepAtMost(maximumAttempts: Long): Boolean {
        host.owner.requireOwner()
        require(maximumAttempts in 1..65536)
        check(!stepInProgress) {"Recursive owner step"}
        if (socPoweredOff) {
            return stepWhileSocPoweredOff().also { publishTelemetry() }
        }
        stepInProgress=true
        try { return stepOwned(maximumAttempts) } finally { stepInProgress = false; publishTelemetry() }
    }
    private fun stepDynarmic(maximumAttempts: Long): Boolean {
        if (stopped) return false
        lastStepAllCoresWfi = false
        val dyn = checkNotNull(dynarmic)
        val dc = dynCluster
        val router = checkNotNull(idleRouter)
        val profiling = telemetry.perfProfilingEnabled
        if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
        // Flush any scheduled device deadline already reached (e.g., if a previous Dynarmic basic block
        // slightly overshot nextSched and entered WFI) BEFORE cluster switch or IdleClock.nextDeadline().
        var nextSched = router.refreshNextScheduledTick()
        var tickedAtCurrentTick = false
        if (nextSched != Long.MAX_VALUE && nextSched <= virtualTick) {
            try {
                router.beforeInstruction(virtualTick)
            } catch (error: Throwable) {
                fail(error.message ?: "device refusal", mapOf("pcVirtual" to pc))
                throw error
            }
            dynShadow.onScheduledDevicesTicked(dyn)
            nextSched = router.refreshNextScheduledTick()
            tickedAtCurrentTick = true
        }
        val didSwitch = dc?.needsSwitch() == true
        if (didSwitch && !dc.switch()) {
            val wfiStart = if (profiling) System.nanoTime() else 0L
            val beforeIdleTick = virtualTick
            if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_WFI_JUMP
            try {
                if (idleClock?.advance(virtualTick, attempts, router) { next -> host.advanceTick(next); virtualTick = next } == true) {
                    dynShadow.onIdleClockAdvanced(dyn)
                    lastStepAllCoresWfi = true
                    if (profiling) {
                        telemetry.wfiJumpNanos += (System.nanoTime() - wfiStart).coerceAtLeast(0L)
                        telemetry.wfiJumpCount += 1L
                        telemetry.wfiSkippedTicks += (virtualTick - beforeIdleTick).coerceAtLeast(0L)
                        telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
                    }
                    return true
                }
            } catch (error: Throwable) {
                if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
                fail(error.message ?: "idle device refusal", mapOf("pcVirtual" to dyn.getReg(dc.current, 15), "tick" to virtualTick, "instructionAttempts" to attempts))
                return false
            }
            if (profiling) telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
            fail("all-cores-waiting-without-modeled-progress", mapOf("pcVirtual" to dyn.getReg(dc.current, 15)))
            return false
        }
        val core = dc?.current ?: 0
        dyn.selectCore(core)
        if (didSwitch) {
            nextSched = router.refreshNextScheduledTick()
        }
        if (nextSched != Long.MAX_VALUE && nextSched <= virtualTick + 1L) {
            val fireTick = maxOf(virtualTick, nextSched)
            if (fireTick > virtualTick) {
                virtualTick = fireTick
                host.advanceTick(virtualTick)
                tickedAtCurrentTick = false
            }
            if (!tickedAtCurrentTick) {
                try {
                    router.beforeInstruction(virtualTick)
                } catch (error: Throwable) {
                    fail(error.message ?: "device refusal", mapOf("pcVirtual" to pc))
                    throw error
                }
                dynShadow.onScheduledDevicesTicked(dyn)
                nextSched = router.refreshNextScheduledTick()
            }
        }
        val useInlineTimer = dynShadow.timerIrqShadowEnabled && !router.hasAlwaysTickDevices()
        val effectiveDeadline = if (useInlineTimer) {
            router.nextScheduledTickExcluding(dynShadow.timerDevice)
        } else {
            nextSched
        }
        val vdiv = dynVtickDivider.toLong()
        val insnsBeforeDeadline = when {
            router.hasAlwaysTickDevices() -> 1L
            effectiveDeadline == Long.MAX_VALUE -> Long.MAX_VALUE
            effectiveDeadline <= virtualTick + 1L -> vdiv
            else -> {
                val dt = effectiveDeadline - virtualTick
                if (vdiv <= 1L || dt > Long.MAX_VALUE / vdiv) dt else dt * vdiv
            }
        }
        val maxByBudget = if (unlimitedBudget) {
            minOf(maximumAttempts, insnsBeforeDeadline)
        } else {
            minOf(maximumAttempts, (budget - attempts).coerceAtLeast(1L), insnsBeforeDeadline)
        }
        val useNativeBatch = dc != null &&
            dyn.supportsLiveState() &&
            dyn.supportsLiveClusterIrq() &&
            maxByBudget > 1L &&
            dc.canRunNativeBatch(maxByBudget) &&
            (dc.hasAnotherRunnableCore() || useInlineTimer)
        var pendingBefore = -1
        var irqDelivered = false
        if (!fastAutoIrqActive || !useNativeBatch) {
            pendingBefore = cpuInterrupts?.chooseOrNegative() ?: -1
            if (pendingBefore >= 0 && (dyn.getCpsr(core) and 0x80L) == 0L) {
                if (dyn.deliverIrq(core)) {
                    dyn.clearExclusive(core)
                    val newPc = dyn.getReg(core, 15)
                    irqEntries.addFast(pendingBefore, virtualTick, pc, newPc, hasCluster(), core)
                    irqCount++
                    pc = newPc
                    irqDelivered = true
                }
            }
        }
        val runBudget = if (useNativeBatch) {
            maxByBudget
        } else {
            val singleStepLimit = when {
                router.hasAlwaysTickDevices() -> 1L
                nextSched == Long.MAX_VALUE -> maxByBudget
                nextSched <= virtualTick + 1L -> vdiv
                else -> {
                    val dt = nextSched - virtualTick
                    val insns = if (vdiv <= 1L || dt > Long.MAX_VALUE / vdiv) dt else dt * vdiv
                    minOf(maxByBudget, insns)
                }
            }
            minOf(singleStepLimit, dc?.fastBudget(singleStepLimit) ?: singleStepLimit).coerceAtLeast(1L)
        }
        if (useNativeBatch && useInlineTimer) {
            dynShadow.prepareInlineTimerBeforeRun(effectiveDeadline, allowWfiFastForward = unlimitedBudget)
        } else {
            dynShadow.disableInlineTimerForSingleStep()
        }
        fastRunEndTick = virtualTick + ((runBudget + vdiv - 1L) / vdiv)
        fastMultiQuantumActive = dc != null && runBudget > dc.remaining()
        dynShadow.syncBeforeRun(dyn)
        val executed: Long
        val resVirtualTick: Long
        val resStopPc: Long
        val resStopReason: Int
        val resFaultAddr: Long
        val resFaultStatus: Long
        val resTranslatedFetches: Long
        val resFastMmioHits: Long
        val sliceStartNanos = if (profiling) {
            sliceMmioNanos = 0L
            telemetry.currentPc = pc
            telemetry.currentCore = core
            telemetry.currentPhase = FirmwareTelemetry.PHASE_CPU_NATIVE
            System.nanoTime()
        } else 0L
        if (useNativeBatch) {
            fastClusterBatchActive = true
            fastInlineTimerActive = useInlineTimer
            val cres = try {
                dc.runNativeBatch(runBudget, virtualTick)
            } finally {
                fastClusterBatchActive = false
                fastInlineTimerActive = false
                if (profiling) {
                    val totalNanos = (System.nanoTime() - sliceStartNanos).coerceAtLeast(0L)
                    telemetry.cpuNativeNanos += (totalNanos - sliceMmioNanos).coerceAtLeast(0L)
                    telemetry.sliceCount += 1L
                    telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
                }
                fastMultiQuantumActive = false
            }
            executed = cres.totalExecuted
            resVirtualTick = cres.virtualTick
            resStopPc = cres.stopPc
            resStopReason = cres.stopReason
            resFaultAddr = cres.faultAddr
            resFaultStatus = cres.faultStatus
            resTranslatedFetches = cres.translatedFetches
            resFastMmioHits = cres.fastMmioHits
        } else {
            dc?.syncEventsToNative()
            val irqPending = if (irqDelivered) cpuInterrupts?.hasPending() == true else pendingBefore >= 0
            val res = try {
                dyn.runFast(core, runBudget, virtualTick, irqPending)
            } finally {
                if (profiling) {
                    val totalNanos = (System.nanoTime() - sliceStartNanos).coerceAtLeast(0L)
                    telemetry.cpuNativeNanos += (totalNanos - sliceMmioNanos).coerceAtLeast(0L)
                    telemetry.sliceCount += 1L
                    telemetry.currentPhase = FirmwareTelemetry.PHASE_HOST_OTHER
                }
                fastMultiQuantumActive = false
            }
            executed = res.executedTicks
            resVirtualTick = res.virtualTick
            resStopPc = res.stopPc
            resStopReason = res.stopReason
            resFaultAddr = res.faultAddr
            resFaultStatus = res.faultStatus
            resTranslatedFetches = res.translatedFetches
            resFastMmioHits = res.fastMmioHits
        }
        dynShadow.fastMmioHits += resFastMmioHits
        if (resVirtualTick > virtualTick) {
            virtualTick = resVirtualTick
            host.advanceTick(virtualTick)
        }
        if (executed > 0L) {
            attempts += executed
            if (profiling) telemetry.sliceExecutedInstructions += executed
            pc = resStopPc
            dynTranslatedFetches += resTranslatedFetches
            if (!useNativeBatch) dc?.account(executed)
        } else {
            pc = resStopPc
        }
        if (bootSramIspHandoffPending && pc in 0L until 0x1000_0000L) {
            dyn.unmap(0xfff10000L, 0x2000L)
            dyn.mmio(0xfff10000L, 0x2000L)
            bootSramIspHandoffPending = false
            log.info("[BOOT-ROM] Handed off SPX4 shared SRAM 0xFFF10000..0xFFF12000 to soc-isp MMIO at pc=0x{}", pc.toString(16))
        }
        val stopCore = dc?.current ?: core
        if (!stopped && dyn.callbackFailure() == null) {
            when (resStopReason) {
                Dynarmic.STOP_QUANTUM,
                Dynarmic.STOP_MMIO_HALT,
                Dynarmic.STOP_HALT -> Unit
                Dynarmic.STOP_ALL_WAITING -> {
                    lastStepAllCoresWfi = true
                }
                Dynarmic.STOP_WFI -> {
                    if (!useNativeBatch) {
                        if (dc != null) {
                            dc.onWfi()
                        } else if (cpuInterrupts?.hasPending() != true) {
                            idleClock?.advance(virtualTick, attempts, router) { next -> host.advanceTick(next); virtualTick = next }
                        }
                    }
                }
                Dynarmic.STOP_WFE -> {
                    if (!useNativeBatch) dc?.onWfe()
                }
                Dynarmic.STOP_SEV -> {
                    if (!useNativeBatch) dc?.onSev()
                }
                Dynarmic.STOP_IRQ -> {
                    val pendingAfter = cpuInterrupts?.chooseOrNegative() ?: -1
                    if (pendingAfter >= 0 && (dyn.getCpsr(stopCore) and 0x80L) == 0L) {
                        if (dyn.deliverIrq(stopCore)) {
                            dyn.clearExclusive(stopCore)
                            val newPc = dyn.getReg(stopCore, 15)
                            irqEntries.addFast(pendingAfter, virtualTick, pc, newPc, hasCluster(), stopCore)
                            irqCount++
                            pc = newPc
                        }
                    }
                }
                Dynarmic.STOP_SVC -> {
                    dyn.deliverSvc(stopCore)
                    dyn.clearExclusive(stopCore)
                    pc = dyn.getReg(stopCore, 15)
                }
                Dynarmic.STOP_FAULT_UNDEF -> {
                    dyn.deliverUndef(stopCore)
                    dyn.clearExclusive(stopCore)
                    pc = dyn.getReg(stopCore, 15)
                }
                Dynarmic.STOP_FAULT_PABT -> {
                    dyn.deliverPabt(stopCore, resFaultAddr, resFaultStatus)
                    dyn.clearExclusive(stopCore)
                    pc = dyn.getReg(stopCore, 15)
                }
                Dynarmic.STOP_FAULT_DABT -> {
                    if (relaxedMode) {
                        val isWrite = (resFaultStatus and (1L shl 11)) != 0L
                        val paHex = runCatching {
                            "0x${dyn.translate(stopCore, resFaultAddr, if (isWrite) 2 else 1).toString(16)}"
                        }.getOrElse { "unmapped-or-untranslated" }
                        log.warn(
                            "[RELAXED/DABT] core={} tick={} pc=0x{} dfar=0x{} pa={} dfsr=0x{} write={}",
                            stopCore, virtualTick, pc.toString(16), resFaultAddr.toString(16), paHex, resFaultStatus.toString(16), isWrite,
                        )
                    }
                    dyn.deliverDabt(stopCore, resFaultAddr, resFaultStatus)
                    dyn.clearExclusive(stopCore)
                    pc = dyn.getReg(stopCore, 15)
                }
                Dynarmic.STOP_TRACEPOINT -> {
                    if (pc in tracePoints) {
                        val sampleLimit = tracePerPointLimit
                        val slot = if (sampleLimit == null) -1 else traceIndices.getValue(pc) * 4 + stopCore
                        val count = if (slot >= 0) {
                            traceObserved[slot] = Math.addExact(traceObserved[slot], 1)
                            traceObserved[slot]
                        } else 0L
                        if (sampleLimit == null || count <= sampleLimit) {
                            if (checkpoints.size == 256 && sampleLimit == null) checkpoints.removeFirst()
                            checkpoints.addLast(
                                mapOf(
                                    "tick" to virtualTick,
                                    "address" to pc,
                                    "sp" to dyn.getReg(stopCore, 13),
                                    "lr" to dyn.getReg(stopCore, 14),
                                    "cpsr" to dyn.getCpsr(stopCore),
                                ) + (0..12).associate { "r$it" to dyn.getReg(stopCore, it) } +
                                    (if (dc == null) emptyMap() else mapOf("core" to stopCore.toLong())),
                            )
                        }
                        checkpointCount++
                    }
                }
                else -> fail("dynarmic-stop-reason-$resStopReason", mapOf("pcVirtual" to pc))
            }
        }
        if (!stopped && dyn.callbackFailure() != null) {
            fail(dyn.callbackFailure()?.message ?: "callback failure", mapOf("pcVirtual" to pc))
        }
        if (!stopped && !unlimitedBudget && attempts >= budget) {
            stopped = true
            reason = "instruction-limit"
        }
        if (!stopped && relaxedMode && onSubMcuColdReset != null && !socPoweredOff && powerHoldDeasserted) {
            evaluateSubMcuPowerOffLatch()
        }
        return !stopped
    }
    private fun observePowerHoldWrite(physicalAddress: Long, size: Int, value: Long) {
        if (!relaxedMode || onSubMcuColdReset == null) return
        if (physicalAddress == 0xFFF4A400L && size == 4) {
            if (value != 0L) {
                powerHoldAsserted = true
                powerHoldDeasserted = false
            } else {
                powerHoldDeasserted = true
            }
        }
    }
    private fun evaluateSubMcuPowerOffLatch() {
        if (!relaxedMode || onSubMcuColdReset == null || !powerHoldDeasserted || socPoweredOff) return
        val peerState = host.components().singleOrNull { it.descriptor.id == "power-peer" }?.component?.state() ?: return
        val stored = peerState["stored"] as? Map<*, *>
        val storedSel11 = (stored?.get("17") as? Number)?.toInt()
        val events = (peerState["events"] as? List<*>).orEmpty()
        var sawPowerOffCmd = storedSel11 == 0x0800 || storedSel11 == 0x1F00
        var lastSel11 = storedSel11 ?: -1
        for (raw in events) {
            val row = raw as? Map<*, *> ?: continue
            val reg = (row["register"] as? Number)?.toInt() ?: continue
            if (reg == 17 && row["write"] == true) {
                val value = (row["value"] as? Number)?.toInt() ?: continue
                if (value == 0x0800 || value == 0x1F00) sawPowerOffCmd = true
                lastSel11 = value
            }
        }
        val buttons = host.ports.find("buttons", com.github.nyabanban.fuji.emulator.api.ButtonPort::class.java)
        val powerNow = buttons?.pressed("power") ?: false
        val shutterNow = buttons?.pressed("shutterHalf") ?: false
        val shutterFullNow = buttons?.pressed("shutterFull") ?: false
        val playNow = buttons?.pressed("play") ?: false
        val afOnNow = buttons?.pressed("afOn") ?: false
        if (!sawPowerOffCmd) {
            // Real-silicon early-boot power-off abort (BUG-31, e.g. PC=0x012CA224 when power switch is OFF
            // during early boot): firmware drops PMIC POWER_HOLD (0xFFF4A400: 1 -> 0) and spins `b .`
            // without writing 0x0800/0x1F00 to power-peer sel 0x11. The Sub-MCU PMIC cuts main-SoC power
            // unconditionally once POWER_HOLD drops 1 -> 0; if the user already toggled power OFF -> ON
            // before 0xFFF4A400=0 ran, immediately cold-reset cleanly instead of spinning forever.
            if (!powerHoldAsserted) return
            val immediateRestart = powerNow && sawPowerSwitchOffDuringBoot
            standbyWakeState.arm(
                power = powerNow,
                shutterHalf = shutterNow,
                shutterFull = shutterFullNow,
                play = playNow,
                afOn = afOnNow,
                immediateRestart = immediateRestart,
            )
            socPoweredOff = true
            log.info(
                "[SUB-MCU] Main SoC powered off on POWER_HOLD deassertion (0xFFF4A400 1->0, sel 0x11=0x{}, powerSwitch={}, sawPowerOffDuringBoot={}, immediateRestart={}); monitoring real-silicon wake inputs",
                if (lastSel11 >= 0) lastSel11.toString(16) else "none",
                powerNow,
                sawPowerSwitchOffDuringBoot,
                immediateRestart,
            )
            return
        }
        // Real-silicon power-peer sel 0x11 post-shutdown command:
        // - 0x1F00: full power-off (wakes on power switch false->true)
        // - 0x0002: AUTO POWER OFF standby wake-arm when power stayed ON throughout boot (`!sawPowerSwitchOffDuringBoot`),
        //   OR end-of-shutdown handoff when user toggled power OFF (`sawPowerSwitchOffDuringBoot`) and back ON during sensor cleaning
        // - 0x0001 / 0x0008: immediate reboot / firmware restart request
        val immediateRestart = lastSel11 == 0x0001 || lastSel11 == 0x0008 ||
            (powerNow && sawPowerSwitchOffDuringBoot)
        standbyWakeState.arm(
            power = powerNow,
            shutterHalf = shutterNow,
            shutterFull = shutterFullNow,
            play = playNow,
            afOn = afOnNow,
            immediateRestart = immediateRestart,
        )
        socPoweredOffSinceNanos = System.nanoTime()
        socPoweredOff = true
        log.info(
            "[SUB-MCU] Main SoC powered off (power-peer sel 0x11=0x{}, 0xFFF4A400=0, powerSwitch={}, shutterHalf={}, immediateRestart={}); monitoring real-silicon wake inputs",
            if (lastSel11 >= 0) lastSel11.toString(16) else "none",
            powerNow,
            shutterNow,
            immediateRestart,
        )
    }
    internal fun observeButtonEventWhilePoweredOff(changedValues: Map<String, Any?>) {
        host.owner.requireOwner()
        if (!socPoweredOff || stopped) return
        val buttons = host.ports.find("buttons", com.github.nyabanban.fuji.emulator.api.ButtonPort::class.java)
        val powerNow = (changedValues["power"] as? Boolean) ?: buttons?.pressed("power") ?: standbyWakeState.lastPower
        val shutterHalfNow = (changedValues["shutterHalf"] as? Boolean) ?: buttons?.pressed("shutterHalf") ?: standbyWakeState.lastShutterHalf
        val shutterFullNow = (changedValues["shutterFull"] as? Boolean) ?: buttons?.pressed("shutterFull") ?: standbyWakeState.lastShutterFull
        val playNow = (changedValues["play"] as? Boolean) ?: buttons?.pressed("play") ?: standbyWakeState.lastPlay
        val afOnNow = (changedValues["afOn"] as? Boolean) ?: buttons?.pressed("afOn") ?: standbyWakeState.lastAfOn
        standbyWakeState.observeButtonPatch(
            power = powerNow,
            shutterHalf = shutterHalfNow,
            shutterFull = shutterFullNow,
            play = playNow,
            afOn = afOnNow,
        )
    }
    private fun stepWhileSocPoweredOff(): Boolean {
        if (stopped) return false
        val buttons = host.ports.find("buttons", com.github.nyabanban.fuji.emulator.api.ButtonPort::class.java)
        val powerNow = buttons?.pressed("power") ?: false
        val shutterHalfNow = buttons?.pressed("shutterHalf") ?: false
        val shutterFullNow = buttons?.pressed("shutterFull") ?: false
        val playNow = buttons?.pressed("play") ?: false
        val afOnNow = buttons?.pressed("afOn") ?: false
        val wakeReason = standbyWakeState.pollWakeReason(
            power = powerNow,
            shutterHalf = shutterHalfNow,
            shutterFull = shutterFullNow,
            play = playNow,
            afOn = afOnNow,
        )
        if (wakeReason == null) return true
        val handler = onSubMcuColdReset ?: return true
        lastSocPoweredOffWallSeconds = socPoweredOffSinceNanos?.let { started ->
            ((System.nanoTime() - started).coerceAtLeast(0L)) / 1_000_000_000L
        } ?: 0L
        socPoweredOffSinceNanos = null
        socWakeCount++
        log.info("[SUB-MCU] Wake trigger ({}) -> cold-resetting SPX4 main SoC (wakeCount={})", wakeReason, socWakeCount)
        dynarmic?.let { activeDyn ->
            dynarmic = null
            activeDyn.resetForColdReset()
            pooledDynarmic?.close()
            pooledDynarmic = activeDyn
        }
        closeInternal(preservePooledDynarmic = true)
        resetStateForColdBoot()
        handler.invoke()
        initialize()
        return !stopped
    }
    private fun resetStateForColdBoot() {
        attempts = 0L
        virtualTick = 0L
        dynVtickDivider = 1
        pc = 0L
        stopped = true
        reason = "sub-mcu-cold-reset"
        frontier = null
        firmwareSha = null
        nativeSha = null
        firmwareId = null
        stockShutterProfile = false
        verifiedShutterCode = false
        stillModeTransition.clear()
        bootMode = null
        initialPc = 0L
        contracts = emptyMap()
        activeIds = emptySet()
        nativeMpidr = null
        nativeCbar = null
        cbarReads.clear()
        cbarReadCount = 0L
        undefinedEntries.clear()
        undefinedCount = 0L
        irqCount = 0L
        irqEntries.clear()
        irqLines.clear()
        irqLineCount = 0L
        ramWriteRanges = RamWriteRanges(emptyList())
        cpuInterrupts = null
        fastRunEndTick = 0L
        fastMultiQuantumActive = false
        fastClusterBatchActive = false
        fastInlineTimerActive = false
        fastAutoIrqActive = false
        additionalRamReceipt = null
        checkpoints.clear()
        checkpointCount = 0L
        reads = 0L
        writes = 0L
        dynTranslatedFetches = 0L
        budget = initialBudget
        budgetSelection = null
        mainDisplay = null
        ispLiveViewDma = null
        lastLcdcLiveViewPumpTick = Long.MIN_VALUE
        stage2Ranges = LongArray(0)
        stage3Ranges = LongArray(0)
        stage4Ranges = LongArray(0)
        mmioPageLabels = emptyMap()
        relaxedFallback = null
        discoveryBus = null
        socPoweredOff = false
        powerHoldAsserted = false
        powerHoldDeasserted = false
        sawPowerSwitchOffDuringBoot = false
        standbyWakeState.reset()
        clusterReset?.resetForColdReset()
        idleClock?.resetForColdReset()
        if (tracePoints.isNotEmpty()) traceObserved.fill(0L)
        telemetry.resetForColdReset()
    }
    private fun stepOwned(maximumAttempts: Long): Boolean {
        if (stopped) return false
        if (socPoweredOff) return stepWhileSocPoweredOff()
        return stepDynarmic(maximumAttempts)
    }
    internal data class CheckpointCursor(val attempts:Long,val tick:Long,val core:Int,val pc:Long,val mmioObserved:Long,val ram:List<Map<String,Any>>) {
        fun receipt():Map<String,Any> = linkedMapOf("attempts" to attempts,"tick" to tick,"core" to core,"pc" to pc,"mmioObserved" to mmioObserved,"ram" to ram)
    }
    /** Only called between completed step actions; never saves/restores CPU contexts or advances time. */
    internal fun checkpointCursor():CheckpointCursor {
        host.owner.requireOwner();check(!stopped)
        val registers=registerSnapshot();val fp=floatingPointSnapshot();val tick=virtualTick
        val ram=checkpointRam()
        check(registers==registerSnapshot() && fp==floatingPointSnapshot() && tick==virtualTick)
        return CheckpointCursor(attempts,virtualTick,currentCoreIndex(),checkNotNull(activeBackend).register(ArmReg.PC),transactions.observed,ram)
    }
    /** Passive pre-gate terminal/error evidence; no cursor, RAM hash or claim of a saved checkpoint. */
    internal fun checkpointEarlyState():Map<String,Any?> {
        host.owner.requireOwner()
        return mapOf("attempts" to attempts,"tick" to virtualTick,"status" to reason,"stopped" to stopped,
            "frontier" to frontier,"registerSnapshot" to registerSnapshot(),"completeBoot" to false)+
            (if(budgetSelection==null)emptyMap()else mapOf("executionBudget" to checkpointBudgetSelection()))+
            (additionalRamReceipt?.let{mapOf("additionalRam" to it)} ?: emptyMap())
    }
    internal fun checkpointCore():Int {host.owner.requireOwner();return currentCoreIndex()}
    internal fun checkpointAttempts():Long {host.owner.requireOwner();return attempts}
    internal fun checkpointScannerCounts():List<Map<String,Any>> {
        host.owner.requireOwner()
        val limit=checkNotNull(tracePerPointLimit) { "GPIO input gate requires per-point trace counts" }
        return listOf(0x123f108L,0x123de7cL).flatMap { address ->
            val index=checkNotNull(traceIndices[address]) { "GPIO input gate trace point missing" }
            (0..3).map { core ->
                val count=traceObserved[index*4+core]
                mapOf("address" to address,"core" to core,"count" to count,"retained" to minOf(count,limit.toLong()))
            }
        }
    }
    private fun checkpointRam():List<Map<String,Any>> = checkNotNull(memoryLayout).regions().map { r ->
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        checkNotNull(activeBackend).hashMemory(r.address,r.size,digest)
        linkedMapOf("id" to r.id,"address" to r.address,"size" to r.size,"permissions" to r.permissions,
            "sha256" to java.util.HexFormat.of().formatHex(digest.digest()))
    }
    internal fun displayStatus(): Map<String, Any?> {
        host.owner.requireOwner()
        return mapOf("status" to reason, "attempts" to attempts)
    }
    fun state(): Map<String, Any?> = state(null)
    internal fun stateWithoutDiscovery(): Map<String, Any?> = state(emptyMap(), emptyList(), true)
    private fun state(discoveryObservation: Map<String,Any?>?,transactionObservation:List<Map<String,Any?>>?=null,checkpointComparison:Boolean=false): Map<String, Any?> {
        host.owner.requireOwner()
        val backend = activeBackend
        val registers = backend?.let { b -> (0..3).associate { "r$it" to b.register(ArmReg.R0 + it) } + mapOf("sp" to b.register(ArmReg.SP), "lr" to b.register(ArmReg.LR), "pc" to b.register(ArmReg.PC), "cpsr" to b.register(ArmReg.CPSR)) }
        val modeLabel = if (bootMode == FirmwareMetadata.BOOT_ROM_EXPERIMENT) {
            if (!hasCluster()) "single-core boot-rom-experiment" else "four-context boot-rom-experiment"
        } else {
            if (!hasCluster()) "single-core RAM-loader experiment" else "four-context RAM-loader experiment"
        }
        val backendLabel = "dynarmic-a32-ffm-jvm25"
        return mapOf("backend" to backendLabel, "smpHostThreads" to (dynarmic?.isSmpHostThreadsEnabled() ?: false), "mode" to modeLabel, "attempts" to attempts,
            "status" to reason, "frontier" to frontier, "registers" to registers, "firmwareSha256" to firmwareSha, "nativeSha256" to nativeSha,
            "mmioReads" to reads, "mmioWrites" to writes, "transactions" to (transactionObservation ?: transactions.toList()),
            "firmwareId" to firmwareId, "contracts" to contracts,
            "cluster" to dynCluster?.state(),"nativeMpidr" to nativeMpidr,"nativeMmu" to null,"wordExclusive" to wordExclusive?.state(),
            "cbar" to mapOf("nativeValue" to nativeCbar,"input" to cbarInput,"readCount" to cbarReadCount,"reads" to cbarReads.toList(),"logTruncated" to (cbarReadCount > cbarReads.size),"basis" to "Native readback; optional explicit unmeasured A32 reset input. MMU/Thumb/EE refused"),
            "ram" to memoryLayout?.let { layout -> mapOf("profile" to layout.profile,"regions" to layout.regions().map { r -> mapOf("id" to r.id,"address" to r.address,"size" to r.size,"permissions" to r.permissions) },
                "workspace" to layout.regions().find { it.id == "metadata-workspace" }?.let { r ->
                    val bytes = checkNotNull(backend).readMemory(r.address,r.size.toInt())
                    fun hash(part: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(part).joinToString("") { "%02x".format(it) }
                    mapOf("address" to r.address,"size" to r.size,"sha256" to hash(bytes),"first1024Sha256" to hash(bytes.copyOfRange(0,1024)))
                }) },
            "tracePoints" to tracePoints.sorted(),"checkpoints" to checkpoints.toList(),"checkpointCount" to checkpointCount,"checkpointLogTruncated" to (checkpointCount > checkpoints.size),
            "diagnosticTraceSampling" to tracePerPointLimit?.let {limit->mapOf("mode" to "first-per-point-core","perPointLimit" to limit,"maximumRetained" to tracePoints.size*4*limit,
                "observed" to traceIndices.entries.flatMap{(address,index)->(0..3).mapNotNull{core->val count=traceObserved[index*4+core];if(count==0L)null else mapOf("address" to address,"core" to core,"count" to count,"retained" to minOf(count,limit.toLong()))}})},
            "memoryWatches" to if (backend == null) emptyList<Any>() else memoryWatches.map { (address,size) ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                backend.hashMemory(address, size.toLong(), digest)
                mapOf("address" to address,"size" to size,"sha256" to digest.digest().joinToString("") { "%02x".format(it) })
            },
            "devices" to host.components().filter { it.descriptor.id in activeIds }.associate { it.descriptor.id to it.component.state() },
            "peripherals" to host.components().filter { it.descriptor.id !in activeIds }.associate { it.descriptor.id to it.component.state() },
            "irqEntryCount" to irqCount, "irqEntries" to irqEntries.toList(), "irqLineCount" to irqLineCount, "irqLines" to irqLines.toList(),
            "transactionsTruncated" to (reads + writes > transactions.size),
            "transactionLimit" to transactionLimit,
            "completeBoot" to false, "bootRomExecuted" to (bootMode == FirmwareMetadata.BOOT_ROM_EXPERIMENT && attempts > 0L), "menuVerified" to false )+(if(bootMode == "flash-direct") mapOf("bootMode" to bootMode) else emptyMap())+(if(relaxedFallback==null)emptyMap()else mapOf("relaxedFallback" to relaxedFallback!!.summary(),"socPowerState" to (if(socPoweredOff)"powered-off" else "running"),"socWakeCount" to socWakeCount))+(if(budgetSelection==null)emptyMap()else mapOf("executionBudget" to checkpointBudgetSelection()))+(if(discoveryBus==null)emptyMap()else mapOf("executionMode" to catalogRuntime.mode,"discovery" to (discoveryObservation ?: discoveryState())))+(if(menuLate==null)emptyMap()else mapOf(MenuLateObservation.KEY to menuLate!!.state()))+(if(kernelLateCall==null)emptyMap()else mapOf(KernelLateCallObservation.KEY to kernelLateCall!!.state()))+if(idleClock==null)emptyMap()else mapOf("idleClock" to idleClock!!.state(virtualTick,attempts))+(if(undefinedExceptionProfile.isEmpty())emptyMap()else mapOf("undefinedException" to mapOf("profile" to undefinedExceptionProfile,"entryCount" to undefinedCount,"entries" to undefinedEntries.toList(),"logTruncated" to(undefinedCount>undefinedEntries.size),"basis" to "Opt-in A32 FP-transfer FPEXC-disabled Undefined entry only; guest handler enables/retries, no forced FPU state or guest-success writes")))
    }
    fun saveEmulatorSnapshot(directory: Path): Map<String, Any?> {
        host.owner.requireOwner()
        val backend = checkNotNull(activeBackend) { "Emulator snapshot requires an initialized session" }
        check(!stopped && !stepInProgress) { "Emulator snapshot requires a running session at an owner step boundary" }
        require(menuLate == null && kernelLateCall == null) {
            "EmulatorSnapshot v1 does not support active transient late-call probes"
        }
        val traceCounts = linkedMapOf<String, Long>()
        traceIndices.forEach { (address, index) ->
            for (coreIndex in 0..3) {
                val count = traceObserved[index * 4 + coreIndex]
                if (count != 0L) traceCounts["$address:$coreIndex"] = count
            }
        }
        val ctx = object : SessionSnapshotCodec.SaveContext {
            override val host = this@FirmwareSession.host
            override val backend = backend
            override val layout = checkNotNull(memoryLayout)
            override val router = checkNotNull(idleRouter)
            override val dynCluster = this@FirmwareSession.dynCluster
            override val clusterProfile = this@FirmwareSession.clusterProfile
            override val mmuProfile = this@FirmwareSession.mmuProfile
            override val prefetchAbortProfile = this@FirmwareSession.prefetchAbortProfile
            override val exceptionProfile = this@FirmwareSession.exceptionProfile
            override val wordExclusive = this@FirmwareSession.wordExclusive
            override val idleClock = this@FirmwareSession.idleClock
            override val activeIds = this@FirmwareSession.activeIds
            override val firmwareId = checkNotNull(this@FirmwareSession.firmwareId)
            override val firmwareSha = checkNotNull(this@FirmwareSession.firmwareSha)
            override val executionMode = catalogRuntime.mode
            override val additionalRamReceipt = this@FirmwareSession.additionalRamReceipt
            override val discoveryBus = this@FirmwareSession.discoveryBus
            override val attempts = this@FirmwareSession.attempts
            override val virtualTick = this@FirmwareSession.virtualTick
            override val pc = this@FirmwareSession.pc
            override val initialBudget = this@FirmwareSession.initialBudget
            override val effectiveBudget = this@FirmwareSession.budget
            override val budgetSelection = this@FirmwareSession.budgetSelection
            override val reads = this@FirmwareSession.reads
            override val writes = this@FirmwareSession.writes
            override val cbarInput = this@FirmwareSession.cbarInput
            override val cbarReadCount = this@FirmwareSession.cbarReadCount
            override val cbarReads = this@FirmwareSession.cbarReads.toList()
            override val undefinedExceptionProfile = this@FirmwareSession.undefinedExceptionProfile
            override val undefinedCount = this@FirmwareSession.undefinedCount
            override val undefinedEntries = this@FirmwareSession.undefinedEntries.toList()
            override val irqCount = this@FirmwareSession.irqCount
            override val irqEntries = this@FirmwareSession.irqEntries.toList()
            override val irqLineCount = this@FirmwareSession.irqLineCount
            override val irqLines = this@FirmwareSession.irqLines.toList()
            override val checkpointCount = this@FirmwareSession.checkpointCount
            override val checkpoints = this@FirmwareSession.checkpoints.toList()
            override val traceObservedMap = traceCounts
            override val transactions = this@FirmwareSession.transactions
        }
        return SessionSnapshotCodec.save(directory, ctx)
    }

    fun restoreEmulatorSnapshot(
        directory: Path,
        options: EmulatorSnapshot.RestoreOptions = EmulatorSnapshot.RestoreOptions(),
    ): Map<String, Any?> {
        host.owner.requireOwner()
        val backend = checkNotNull(activeBackend) { "Emulator snapshot restore requires an initialized session" }
        val preselectedRelaxedRam = RelaxedBootstrapRam.selected(config, relaxedMode) && additionalRamReceipt != null
        check(!stopped && !stepInProgress && attempts == 0L && virtualTick == 0L && (additionalRamReceipt == null || preselectedRelaxedRam)) {
            "Emulator snapshot restore requires a clean initialized session before guest execution"
        }
        require(menuLate == null && kernelLateCall == null) {
            "EmulatorSnapshot v1 does not support active transient late-call probes"
        }
        val ctx = object : SessionSnapshotCodec.RestoreContext {
            override val host = this@FirmwareSession.host
            override val backend = backend
            override val layout = checkNotNull(memoryLayout)
            override val router = checkNotNull(idleRouter)
            override val dynCluster = this@FirmwareSession.dynCluster
            override val clusterProfile = this@FirmwareSession.clusterProfile
            override val mmuProfile = this@FirmwareSession.mmuProfile
            override val prefetchAbortProfile = this@FirmwareSession.prefetchAbortProfile
            override val exceptionProfile = this@FirmwareSession.exceptionProfile
            override val wordExclusive = this@FirmwareSession.wordExclusive
            override val idleClock = this@FirmwareSession.idleClock
            override val activeIds = this@FirmwareSession.activeIds
            override val firmwareId = this@FirmwareSession.firmwareId
            override val firmwareSha = this@FirmwareSession.firmwareSha
            override val executionMode = catalogRuntime.mode
            override val discoveryBus = this@FirmwareSession.discoveryBus
            override val cbarInput = this@FirmwareSession.cbarInput
            override val undefinedExceptionProfile = this@FirmwareSession.undefinedExceptionProfile
            override val transactions = this@FirmwareSession.transactions
            override val cbarReads = this@FirmwareSession.cbarReads
            override val undefinedEntries = this@FirmwareSession.undefinedEntries
            override val checkpoints = this@FirmwareSession.checkpoints
            override val traceIndices = this@FirmwareSession.traceIndices
            override val traceObserved = this@FirmwareSession.traceObserved

            override fun setExecutionCounters(
                attempts: Long,
                virtualTick: Long,
                pc: Long,
                budget: Long,
                budgetSelection: SnapshotBudgetSelection?,
            ) {
                this@FirmwareSession.attempts = attempts
                this@FirmwareSession.virtualTick = virtualTick
                this@FirmwareSession.pc = pc
                this@FirmwareSession.budget = budget
                this@FirmwareSession.budgetSelection = budgetSelection
            }

            override fun selectAdditionalRamFromSnapshot(
                plan: AdditionalRamPlan,
                receiptAttempts: Long,
                receiptTick: Long,
            ) {
                if (additionalRamReceipt == null) {
                    selectAdditionalRam(plan)
                } else {
                    val existing = checkNotNull(memoryLayout).regions().associateBy { it.id }
                    for (region in plan.additionalRegions) {
                        val mapped = existing[region.id]
                            ?: error("Pre-selected additional RAM is missing snapshot region ${region.id}")
                        require(
                            mapped.address == region.address &&
                                mapped.size == region.size &&
                                mapped.permissions == region.permissions,
                        ) {
                            "Pre-selected additional RAM region differs from snapshot for ${region.id}"
                        }
                    }
                }
                additionalRamReceipt = checkNotNull(additionalRamReceipt) + mapOf(
                    "attempts" to receiptAttempts,
                    "tick" to receiptTick,
                )
            }

            override fun setMmioAndDiagnosticCounts(
                reads: Long,
                writes: Long,
                cbarReadCount: Long,
                undefinedCount: Long,
                irqCount: Long,
                irqLineCount: Long,
                checkpointCount: Long,
            ) {
                this@FirmwareSession.reads = reads
                this@FirmwareSession.writes = writes
                this@FirmwareSession.cbarReadCount = cbarReadCount
                this@FirmwareSession.undefinedCount = undefinedCount
                this@FirmwareSession.irqCount = irqCount
                this@FirmwareSession.irqLineCount = irqLineCount
                this@FirmwareSession.checkpointCount = checkpointCount
            }

            override fun clearIrqEntries() {
                irqEntries.clear()
            }

            override fun addIrqEntry(entry: Map<String, Any>) {
                irqEntries.addRaw(entry)
            }

            override fun clearIrqLines() {
                irqLines.clear()
            }

            override fun addIrqLine(tick: Long, line: Int, high: Boolean, cpsr: Long, pcVirtual: Long, core: Int?) {
                irqLines.add(tick, line, high, cpsr, pcVirtual, core)
            }

            override fun onPostBackendFlush() {
                dynarmic?.let { dyn ->
                    dyn.flushTlb()
                    dynShadow.refreshAll(dyn)
                    val irqPort = host.ports.find("soc-gic.cpu", com.github.nyabanban.fuji.emulator.api.CpuInterruptPort::class.java)
                    if (irqPort is com.github.nyabanban.fuji.emulator.api.CoreInterruptPort) {
                        val savedCore = currentCoreIndex()
                        for (c in 0 until dyn.coreCount()) {
                            irqPort.selectCore(c)
                            dyn.setIrqPending(c, irqPort.hasPending())
                        }
                        irqPort.selectCore(savedCore)
                    } else if (irqPort != null) {
                        dyn.setIrqPending(0, irqPort.hasPending())
                    }
                }
            }
        }
        return SessionSnapshotCodec.restore(directory, options, ctx)
    }

    /** Read-only current native context; no context switches, execution or memory reads. */
    fun registerSnapshot(): Map<String,Long>? {
        host.owner.requireOwner()
        val backend=activeBackend?:return null
        return (0..12).associate {"r$it" to backend.register(ArmReg.R0+it)}+mapOf("sp" to backend.register(ArmReg.SP),"lr" to backend.register(ArmReg.LR),"pc" to backend.register(ArmReg.PC),"cpsr" to backend.register(ArmReg.CPSR),"core" to currentCoreIndex().toLong())
    }
    /** Read-only current FPU access/control state; no context restores or guest execution. */
    fun floatingPointSnapshot():Map<String,Long>? {
        host.owner.requireOwner()
        val backend=activeBackend?:return null
        return com.github.nyabanban.fuji.emulator.cpu.FloatingPointDiagnostics.read(backend)+mapOf("core" to currentCoreIndex().toLong(),"pcVirtual" to backend.register(ArmReg.PC),"cpsr" to backend.register(ArmReg.CPSR))
    }
    private fun stillShutterScreen(): Int? {
        host.owner.requireOwner()
        val cpu = activeBackend ?: return null
        if (!stockShutterProfile || stopped) return null
        if (!verifiedShutterCode) {
            // Flash-direct uses a mutable NOR digest. Identify the *loaded* stock S4 and S5 readers.
            fun instructionPair(address: Long) = java.nio.ByteBuffer.wrap(cpu.readMemory(address, 8))
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).long.toULong()
            verifiedShutterCode = instructionPair(0x012E3270) == 0xEBFFEF14E5D50097uL &&
                instructionPair(0x02290F68) == 0xE34002A1E30B0F34uL
            if (!verifiedShutterCode) return null
        }
        return java.nio.ByteBuffer.wrap(cpu.readMemory(0x01CC6424L, 4))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
    }

    internal fun studioModeSwitchChanged(movie: Boolean) {
        host.owner.requireOwner()
        if (movie || socPoweredOff) stillModeTransition.clear() else stillModeTransition.leftMovie(stillShutterScreen())
    }

    internal fun studioSwitchingToStill(): Boolean {
        host.owner.requireOwner()
        if (stillModeTransition.active) {
            stillModeTransition.observe(if (socPoweredOff) null else stillShutterScreen())
        }
        return stillModeTransition.active
    }

    internal fun stillShutterBusy(): Boolean? = stillShutterScreen()?.let { it == 0x015A }

    /** Read-only S1 acceptance from the qualified 2.13 still-shooting state machine, between CPU slices. */
    internal fun stillShutterHalfReady(): Boolean? {
        val screen = stillShutterScreen() ?: return null
        val cpu = activeBackend ?: return null
        return when (screen) {
            0x011C, 0x011D -> {
                // S5 0x02290F68 copies the active 0x5D8-byte shooting profile here.
                // AF-S and AF-C priority occupy +0x97/+0x98 (S4 0x012E3270/0x012E328C).
                val releasePriority = cpu.readMemory(0x02A1BF34L + 0x97, 1)[0].toInt() == 0
                releasePriority || (screen == 0x011C && cpu.readMemory(0x02C47CBCL, 1)[0].toInt() == 8)
            }
            0x0100, 0x0102, 0x015A -> false
            else -> null
        }
    }

    fun memory(address: Long, size: Int): ByteArray { host.owner.requireOwner(); return checkNotNull(activeBackend).readMemory(address, size) }
    override fun close() = closeInternal(preservePooledDynarmic = false)

    /**
     * Safely suspends any active CPU backend, invokes [mutateAndReloadHost] (which closes
     * `ComponentHost`, updates `nor-flash.bin`, and reloads `ComponentHost`), and re-initializes
     * the CPU from the freshly flashed NOR image if a session was previously active.
     */
    fun coldResetWithHostMutation(mutateAndReloadHost: () -> Unit) {
        host.owner.requireOwner()
        check(!stepInProgress) { "Cannot re-flash NOR while an instruction step is in progress" }
        val wasLive = dynarmic != null
        if (wasLive) {
            dynarmic?.let { activeDyn ->
                dynarmic = null
                activeDyn.resetForColdReset()
                pooledDynarmic?.close()
                pooledDynarmic = activeDyn
            }
            closeInternal(preservePooledDynarmic = true)
            resetStateForColdBoot()
        }
        mutateAndReloadHost()
        if (wasLive) {
            initialize()
        }
    }

    private fun closeInternal(preservePooledDynarmic: Boolean) {
        host.owner.requireOwner()
        stopped = true
        relaxedFallback?.logSummaryOnClose()
        irqSubscription?.close()
        dynCluster = null
        host.ports.removeOwner("cpu")
        imageDmaMemory?.close(); imageDmaMemory = null
        dynShadow.reset()
        dynarmic?.close(); dynarmic = null
        if (!preservePooledDynarmic) {
            pooledDynarmic?.close()
            pooledDynarmic = null
        }
        memoryLayout = null; idleRouter = null
        discoveryBus?.close()
    }
}

/** Owner-validated settings, restricted to components with actual bound MMIO windows. */
internal fun ArmCpuBackend.inT79ImageTeardown(): Boolean =
    physicalByte(0x02d92ec8) == 0L && physicalWord(0x02d92ecc) == 0x024b8c38L &&
        physicalByte(0x02d93270) == 4L && physicalByte(0x02aaf063) == 1L &&
        physicalByte(0x02aaf0f8) == 1L

internal fun configuredComponentProfiles(host: ComponentHost, activeComponentIds: Set<String>): Map<String,String> {
    host.owner.requireOwner()
    val components=host.components()
    require(activeComponentIds.all { id -> components.any { it.descriptor.id==id } }) { "Active MMIO component is not loaded" }
    val profiles = components.filter { it.descriptor.id in activeComponentIds }.mapNotNull { component ->
        component.config["profile"]?.let { profile ->
            require(profile is String && profile.isNotBlank()) { "Component profile requires nonblank text: " + component.descriptor.id }
            component.descriptor.id to profile
        }
    }.toMap()
    return java.util.Collections.unmodifiableMap(LinkedHashMap(profiles))
}

/**
 * Tracks real-silicon Sub-MCU wake inputs while the main SPX4 SoC is powered off (`0xFFF4A400 = 0`).
 *
 * - Full power-off (`power == false`, e.g. `power-peer sel 0x11 = 0x1F00`):
 *   ONLY a `power` switch rising edge (`false -> true`) wakes the SoC; momentary buttons are ignored.
 * - Auto Power Off standby (`power == true`, e.g. `power-peer sel 0x11 = 0x0002`):
 *   Wakes on rising edge of `shutterHalf`, `shutterFull`, `play`, `afOn`, or a `power` OFF->ON toggle.
 * - Event-driven latching (`observeButtonPatch`) captures fast press+release clicks between 15 ms
 *   interactive standby polls so rapid taps or rapid power toggles are never dropped.
 */
internal class SubMcuStandbyWakeState {
    var lastPower: Boolean = false
        private set
    var lastShutterHalf: Boolean = false
        private set
    var lastShutterFull: Boolean = false
        private set
    var lastPlay: Boolean = false
        private set
    var lastAfOn: Boolean = false
        private set
    var immediateRestart: Boolean = false
        private set
    @Volatile
    var pendingWakeReason: String? = null
        private set

    val hasPendingWake: Boolean get() = pendingWakeReason != null

    fun arm(
        power: Boolean,
        shutterHalf: Boolean,
        shutterFull: Boolean,
        play: Boolean,
        afOn: Boolean,
        immediateRestart: Boolean,
    ) {
        lastPower = power
        lastShutterHalf = shutterHalf
        lastShutterFull = shutterFull
        lastPlay = play
        lastAfOn = afOn
        this.immediateRestart = immediateRestart
        pendingWakeReason = null
    }

    fun observeButtonPatch(
        power: Boolean,
        shutterHalf: Boolean,
        shutterFull: Boolean,
        play: Boolean,
        afOn: Boolean,
    ): String? {
        if (!power) {
            pendingWakeReason = null
        } else if (pendingWakeReason == null) {
            pendingWakeReason = detectRisingWake(power, shutterHalf, shutterFull, play, afOn)
        }
        lastPower = power
        lastShutterHalf = shutterHalf
        lastShutterFull = shutterFull
        lastPlay = play
        lastAfOn = afOn
        return pendingWakeReason
    }

    fun pollWakeReason(
        power: Boolean,
        shutterHalf: Boolean,
        shutterFull: Boolean,
        play: Boolean,
        afOn: Boolean,
    ): String? {
        val latched = pendingWakeReason
        pendingWakeReason = null
        val reason = when {
            latched != null && power -> latched
            immediateRestart && power -> "sub-mcu-immediate-restart"
            else -> detectRisingWake(power, shutterHalf, shutterFull, play, afOn)
        }
        lastPower = power
        lastShutterHalf = shutterHalf
        lastShutterFull = shutterFull
        lastPlay = play
        lastAfOn = afOn
        return reason
    }

    fun reset() {
        lastPower = false
        lastShutterHalf = false
        lastShutterFull = false
        lastPlay = false
        lastAfOn = false
        immediateRestart = false
        pendingWakeReason = null
    }

    private fun detectRisingWake(
        power: Boolean,
        shutterHalf: Boolean,
        shutterFull: Boolean,
        play: Boolean,
        afOn: Boolean,
    ): String? = when {
        !power -> null
        !lastPower -> "power-switch-rising-edge"
        shutterHalf && !lastShutterHalf -> "shutterHalf-rising-with-power-on"
        shutterFull && !lastShutterFull -> "shutterFull-rising-with-power-on"
        play && !lastPlay -> "play-rising-with-power-on"
        afOn && !lastAfOn -> "afOn-rising-with-power-on"
        else -> null
    }
}
