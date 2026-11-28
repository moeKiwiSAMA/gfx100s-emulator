package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.*
import java.util.concurrent.CopyOnWriteArrayList

/** Reject two producers overwriting one line, including aliases of the same sink object. */
fun validateInterruptRoutes(components: List<HardwareComponent>, ports: Ports) {
    val claims = java.util.IdentityHashMap<InterruptSink, MutableMap<Int, String>>()
    components.forEach { component -> component.interruptRoutes().forEach { route ->
        require(!route.lines.isEmpty() && route.lines.first >= 0 && route.lines.last < 512) { "invalid IRQ route" }
        val sink = ports.find(route.sinkPort, InterruptSink::class.java) ?: error("IRQ sink missing: ${route.sinkPort}")
        val lines = claims.getOrPut(sink) { HashMap() }
        route.lines.forEach { line -> require(lines.putIfAbsent(line, component.id) == null) { "IRQ line collision: ${route.sinkPort}/$line" } }
    } }
}

class PortRegistry(private val owner: OwnerLoop) : Ports {
    private data class Entry(val owner: String, val type: Class<*>, val port: Any)
    private val entries = HashMap<String, Entry>()
    override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
        this.owner.requireOwner()
        require(type.isInstance(port) && name !in entries) { "invalid or duplicate port $name" }
        entries[name] = Entry(owner, type, port)
    }
    override fun <T : Any> find(name: String, type: Class<T>): T? {
        owner.requireOwner()
        val entry = entries[name] ?: return null
        require(entry.type == type) { "port type mismatch for $name" }
        return type.cast(entry.port)
    }
    override fun removeOwner(owner: String) { this.owner.requireOwner(); entries.entries.removeIf { it.value.owner == owner } }
}
class DirectEvents(private val owner: OwnerLoop) : EventChannel {
    private val listeners = CopyOnWriteArrayList<(ComponentEvent) -> Unit>()
    private val nonIrqListeners = CopyOnWriteArrayList<(ComponentEvent) -> Unit>()
    private val irqLineListeners = CopyOnWriteArrayList<IrqLineListener>()
    @Volatile
    private var irqLineSnapshot: Array<IrqLineListener> = emptyArray()
    private val log = org.slf4j.LoggerFactory.getLogger(DirectEvents::class.java)
    private fun rebuildIrqLineSnapshot() {
        irqLineSnapshot = irqLineListeners.toTypedArray()
    }
    override fun publish(event: ComponentEvent) {
        owner.requireOwner()
        if (listeners.isNotEmpty()) {
            listeners.forEach { listener ->
                try { listener(event) }
                catch (error: Exception) { listeners -= listener; log.error("Event observer removed after failure", error) }
            }
        }
        if (nonIrqListeners.isNotEmpty() && event.kind != "irq-line") {
            nonIrqListeners.forEach { listener ->
                try { listener(event) }
                catch (error: Exception) { nonIrqListeners -= listener; log.error("Event observer removed after failure", error) }
            }
        }
    }
    override fun publishIrqLine(source: String, tick: Long, line: Int, high: Boolean) {
        owner.requireOwner()
        val fast = irqLineSnapshot
        for (i in fast.indices) {
            val listener = fast[i]
            try { listener.onIrqLine(tick, line, high) }
            catch (error: Exception) {
                irqLineListeners -= listener
                rebuildIrqLineSnapshot()
                log.error("IRQ line observer removed after failure", error)
            }
        }
        if (listeners.isNotEmpty()) {
            val event = ComponentEvent(source, "irq-line", tick, java.util.Map.of("operation", "line", "tick", tick, "line", line, "high", high))
            listeners.forEach { listener ->
                try { listener(event) }
                catch (error: Exception) { listeners -= listener; log.error("Event observer removed after failure", error) }
            }
        }
    }
    override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    /** Subscribe to all events except high-frequency `irq-line` edge publications. */
    fun subscribeNonIrqLine(listener: (ComponentEvent) -> Unit): AutoCloseable {
        nonIrqListeners += listener
        return AutoCloseable { nonIrqListeners -= listener }
    }
    /** Subscribe to zero-allocation primitive `irq-line` edge notifications. */
    fun subscribeIrqLine(listener: IrqLineListener): AutoCloseable {
        irqLineListeners += listener
        rebuildIrqLineSnapshot()
        return AutoCloseable {
            irqLineListeners -= listener
            rebuildIrqLineSnapshot()
        }
    }
}

data class MmioWindow(val id: String, val base: Long, val size: Long, val port: MmioDevice)
/** Sorted exact windows. Unknowns and boundary-crossing accesses always refuse. */
class MmioRouter(
    windows: List<MmioWindow>,
    private val discovery: DiscoveryBus? = null,
    private val relaxedFallback: RelaxedPeripheralFallback? = null,
) {
    private var accessSequence = 0L
    fun accessSequenceCount(): Long = accessSequence
    fun restoreAccessSequence(value: Long) {
        require(value >= 0L) { "Negative MmioRouter access sequence" }
        accessSequence = value
    }
    val windows = windows.sortedBy { it.base }
    private val windowBases = LongArray(this.windows.size) { this.windows[it].base }
    private val windowEnds = LongArray(this.windows.size) { this.windows[it].base + this.windows[it].size }
    private val windowPorts = Array(this.windows.size) { this.windows[it].port }
    private val windowReadMutatesSchedule = BooleanArray(this.windows.size) { this.windows[it].port.readMayMutateSchedule() }
    private val highPageSingleWindow = IntArray(4096) { -1 }
    private val highPageFirstIdx = IntArray(4096)
    private val highPageLastIdx = IntArray(4096)
    private var lastWindowIdx = 0
    private val clocked = this.windows.map { it.port }.let { ports ->
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MmioDevice, Boolean>())
        ports.filter { seen.add(it) && (it is ScheduledMmioDevice || overridesBeforeInstruction(it)) }
            .sortedWith(compareBy<MmioDevice> { it.clockOrder }.thenBy { it.clockIndex })
    }
    private val scheduledDevices: Array<ScheduledMmioDevice> = clocked.filterIsInstance<ScheduledMmioDevice>().toTypedArray()
    private val alwaysTickDevices: Array<MmioDevice> = clocked.filter {
        it !is TickAwareMmioDevice || it.requiresEveryTick()
    }.toTypedArray()
    private val tickAwareDevices: Array<TickAwareMmioDevice> = clocked.filterIsInstance<TickAwareMmioDevice>()
        .filter { !it.requiresEveryTick() }.toTypedArray()
    private val awareCount = tickAwareDevices.size
    private val awareImpactMaskLo: LongArray
    private val awareImpactMaskHi: LongArray
    private val awareImpactFull: BooleanArray
    private val asyncPollDeviceIndices: IntArray
    private val windowScheduleMode = ByteArray(this.windows.size)
    private val windowDirtyMaskLo = LongArray(this.windows.size)
    private val windowDirtyMaskHi = LongArray(this.windows.size)
    private val deviceNextTicks = LongArray(awareCount) { Long.MAX_VALUE }
    private val heapDevice = IntArray(awareCount) { it }
    private val deviceHeapPos = IntArray(awareCount) { it }
    private val dueIndices = IntArray(awareCount)
    private val heapScanStack = IntArray(awareCount)
    private var dirtyMaskLo = 0L
    private var dirtyMaskHi = 0L
    private var allDirty = true
    private var discoveryDirty = true
    private var discoveryNextTick = Long.MAX_VALUE
    private var nextScheduledTick = 0L

    private fun overridesBeforeInstruction(port: MmioDevice): Boolean = try {
        if (port is RetainedMmioBank) return false
        val m = port.javaClass.getMethod("beforeInstruction", Long::class.javaPrimitiveType)
        val owner = m.declaringClass
        if (owner === MmioDevice::class.java || owner === RetainedMmioBank::class.java) return false
        val bytes = owner.classLoader?.getResourceAsStream(owner.name.replace('.', '/') + ".class")?.use { it.readAllBytes() }
            ?: return true
        val cf = java.lang.classfile.ClassFile.of().parse(bytes)
        val method = cf.methods().firstOrNull {
            it.methodName().equalsString("beforeInstruction") && it.methodType().equalsString("(J)V")
        } ?: return true
        val code = method.code().orElse(null) ?: return true
        val insns = code.elementList().filterIsInstance<java.lang.classfile.Instruction>()
        if (insns.size == 1 && insns[0].opcode() == java.lang.classfile.Opcode.RETURN) return false
        if (insns.size == 4 &&
            insns[2] is java.lang.classfile.instruction.InvokeInstruction &&
            insns[3].opcode() == java.lang.classfile.Opcode.RETURN
        ) {
            val invoke = insns[2] as java.lang.classfile.instruction.InvokeInstruction
            val targetOwner = invoke.owner().asInternalName()
            if (invoke.name().equalsString("beforeInstruction") && (
                    targetOwner == "com/github/nyabanban/fuji/emulator/api/MmioDevice" ||
                        targetOwner == "com/github/nyabanban/fuji/emulator/api/MmioDevice\$DefaultImpls" ||
                        targetOwner == "com/github/nyabanban/fuji/emulator/api/RetainedMmioBank" ||
                        targetOwner == "com/github/nyabanban/fuji/emulator/api/RetainedMmioBank\$DefaultImpls" ||
                        targetOwner == "com/github/nyabanban/fuji/emulator/api/ScheduledMmioDevice" ||
                        targetOwner == "com/github/nyabanban/fuji/emulator/api/TickAwareMmioDevice"
                    )
            ) return false
        }
        true
    } catch (_: Throwable) {
        true
    }

    fun invalidateSchedule() {
        allDirty = true
        discoveryDirty = true
    }
    fun hasAlwaysTickDevices(): Boolean = alwaysTickDevices.isNotEmpty()
    fun isScheduleDirty(): Boolean = allDirty || dirtyMaskLo != 0L || dirtyMaskHi != 0L || discoveryDirty
    fun nextScheduledTickIfClean(): Long = if (allDirty || dirtyMaskLo != 0L || dirtyMaskHi != 0L || discoveryDirty) 0L else nextScheduledTick

    private fun siftUp(startPos: Int, dev: Int, tick: Long) {
        var pos = startPos
        val heap = heapDevice
        val devPos = deviceHeapPos
        val ticks = deviceNextTicks
        while (pos > 0) {
            val parentPos = (pos - 1) ushr 1
            val parentDev = heap[parentPos]
            if (tick >= ticks[parentDev]) break
            heap[pos] = parentDev
            devPos[parentDev] = pos
            pos = parentPos
        }
        heap[pos] = dev
        devPos[dev] = pos
    }

    private fun siftDown(startPos: Int, dev: Int, tick: Long) {
        var pos = startPos
        val n = awareCount
        val half = n ushr 1
        val heap = heapDevice
        val devPos = deviceHeapPos
        val ticks = deviceNextTicks
        while (pos < half) {
            var childPos = (pos shl 1) + 1
            var childDev = heap[childPos]
            var childTick = ticks[childDev]
            val rightPos = childPos + 1
            if (rightPos < n) {
                val rightDev = heap[rightPos]
                val rightTick = ticks[rightDev]
                if (rightTick < childTick) {
                    childPos = rightPos
                    childDev = rightDev
                    childTick = rightTick
                }
            }
            if (tick <= childTick) break
            heap[pos] = childDev
            devPos[childDev] = pos
            pos = childPos
        }
        heap[pos] = dev
        devPos[dev] = pos
    }

    private fun updateDeviceTick(dev: Int, newTick: Long) {
        val oldTick = deviceNextTicks[dev]
        if (newTick == oldTick) return
        deviceNextTicks[dev] = newTick
        val pos = deviceHeapPos[dev]
        if (newTick < oldTick) siftUp(pos, dev, newTick) else siftDown(pos, dev, newTick)
    }

    private fun flushDirtySchedule() {
        val aware = tickAwareDevices
        if (allDirty) {
            for (i in 0 until awareCount) {
                updateDeviceTick(i, aware[i].nextInterestedTick())
            }
            allDirty = false
            dirtyMaskLo = 0L
            dirtyMaskHi = 0L
        } else {
            var maskLo = dirtyMaskLo
            var maskHi = dirtyMaskHi
            dirtyMaskLo = 0L
            dirtyMaskHi = 0L
            while (maskLo != 0L) {
                val dev = java.lang.Long.numberOfTrailingZeros(maskLo)
                maskLo = maskLo and (maskLo - 1L)
                updateDeviceTick(dev, aware[dev].nextInterestedTick())
            }
            while (maskHi != 0L) {
                val dev = 64 + java.lang.Long.numberOfTrailingZeros(maskHi)
                maskHi = maskHi and (maskHi - 1L)
                updateDeviceTick(dev, aware[dev].nextInterestedTick())
            }
        }
        if (discoveryDirty) {
            discoveryNextTick = discovery?.nextInterestedTick() ?: Long.MAX_VALUE
            discoveryDirty = false
        }
        val heapMin = if (awareCount > 0) deviceNextTicks[heapDevice[0]] else Long.MAX_VALUE
        nextScheduledTick = if (discoveryNextTick < heapMin) discoveryNextTick else heapMin
    }

    private fun markWindowDirty(idx: Int) {
        when (windowScheduleMode[idx]) {
            SCHEDULE_NONE -> Unit
            SCHEDULE_SELF_MASK -> {
                dirtyMaskLo = dirtyMaskLo or windowDirtyMaskLo[idx]
                dirtyMaskHi = dirtyMaskHi or windowDirtyMaskHi[idx]
            }
            else -> {
                allDirty = true
                discoveryDirty = true
            }
        }
    }

    private fun pollAsyncDevices() {
        val indices = asyncPollDeviceIndices
        if (indices.isEmpty()) return
        val aware = tickAwareDevices
        val ticks = deviceNextTicks
        var changed = false
        for (i in indices.indices) {
            val dev = indices[i]
            val t = aware[dev].nextInterestedTick()
            if (t != ticks[dev]) {
                updateDeviceTick(dev, t)
                changed = true
            }
        }
        if (changed) {
            val heapMin = if (awareCount > 0) ticks[heapDevice[0]] else Long.MAX_VALUE
            nextScheduledTick = if (discoveryNextTick < heapMin) discoveryNextTick else heapMin
        }
    }

    fun refreshNextScheduledTick(): Long {
        if (allDirty || dirtyMaskLo != 0L || dirtyMaskHi != 0L || discoveryDirty) flushDirtySchedule()
        pollAsyncDevices()
        return nextScheduledTick
    }

    /**
     * Returns the earliest scheduled tick across all devices EXCEPT [excluded] (e.g. `soc-timer`
     * when C++ inline timer comparator IRQ auto-raise is active inside `fuji_dyn_run_cluster_fast`).
     */
    fun nextScheduledTickExcluding(excluded: TickAwareMmioDevice?): Long {
        if (excluded == null) return refreshNextScheduledTick()
        if (allDirty || dirtyMaskLo != 0L || dirtyMaskHi != 0L || discoveryDirty) flushDirtySchedule()
        pollAsyncDevices()
        var best = discoveryNextTick
        val n = awareCount
        if (n > 0) {
            val root = heapDevice[0]
            if (tickAwareDevices[root] !== excluded) {
                val t = deviceNextTicks[root]
                if (t < best) best = t
            } else {
                if (n > 1) {
                    val left = deviceNextTicks[heapDevice[1]]
                    if (left < best) best = left
                }
                if (n > 2) {
                    val right = deviceNextTicks[heapDevice[2]]
                    if (right < best) best = right
                }
            }
        }
        return best
    }

    init {
        this.windows.forEach { require(it.base >= 0 && it.size > 0 && it.base <= 0x1_0000_0000L - it.size) }
        this.windows.zipWithNext().forEach { (a, b) -> require(a.base + a.size <= b.base) { "overlapping MMIO windows" } }
        val awareIndexByPort = java.util.IdentityHashMap<MmioDevice, Int>(awareCount * 2)
        for (i in 0 until awareCount) awareIndexByPort[tickAwareDevices[i]] = i
        val awareIdMaskLo = HashMap<String, Long>()
        val awareIdMaskHi = HashMap<String, Long>()
        var dmaFamilyMaskLo = 0L
        var dmaFamilyMaskHi = 0L
        var shutterFamilyMaskLo = 0L
        var shutterFamilyMaskHi = 0L
        val impactLo = LongArray(awareCount)
        val impactHi = LongArray(awareCount)
        val impactFull = BooleanArray(awareCount) { true }
        val asyncIndices = ArrayList<Int>(2)
        if (awareCount <= 128) {
            for (w in this.windows) {
                val aIdx = awareIndexByPort[w.port]
                if (aIdx != null) {
                    if (w.id in ASYNC_POLL_DEVICES && aIdx !in asyncIndices) asyncIndices += aIdx
                    if (aIdx < 64) {
                        val bit = 1L shl aIdx
                        awareIdMaskLo[w.id] = (awareIdMaskLo[w.id] ?: 0L) or bit
                        if (w.id in DMA_FAMILY_DEVICES) dmaFamilyMaskLo = dmaFamilyMaskLo or bit
                        if (w.id in SHUTTER_FAMILY_DEVICES) shutterFamilyMaskLo = shutterFamilyMaskLo or bit
                    } else {
                        val bit = 1L shl (aIdx - 64)
                        awareIdMaskHi[w.id] = (awareIdMaskHi[w.id] ?: 0L) or bit
                        if (w.id in DMA_FAMILY_DEVICES) dmaFamilyMaskHi = dmaFamilyMaskHi or bit
                        if (w.id in SHUTTER_FAMILY_DEVICES) shutterFamilyMaskHi = shutterFamilyMaskHi or bit
                    }
                }
            }
            for (w in this.windows) {
                val aIdx = awareIndexByPort[w.port] ?: continue
                when {
                    w.id in SELF_CONTAINED_SCHEDULING_DEVICES -> {
                        impactFull[aIdx] = false
                        impactLo[aIdx] = awareIdMaskLo[w.id] ?: 0L
                        impactHi[aIdx] = awareIdMaskHi[w.id] ?: 0L
                    }
                    w.id in DMA_FAMILY_DEVICES -> {
                        impactFull[aIdx] = false
                        impactLo[aIdx] = dmaFamilyMaskLo
                        impactHi[aIdx] = dmaFamilyMaskHi
                    }
                    w.id in SHUTTER_FAMILY_DEVICES -> {
                        impactFull[aIdx] = false
                        impactLo[aIdx] = shutterFamilyMaskLo
                        impactHi[aIdx] = shutterFamilyMaskHi
                    }
                }
            }
        }
        awareImpactMaskLo = impactLo
        awareImpactMaskHi = impactHi
        awareImpactFull = impactFull
        asyncPollDeviceIndices = asyncIndices.toIntArray()
        val maliIdx = this.windows.firstOrNull { it.id == "soc-isp" && it.base == 0xFFE0_0000L }
            ?.let { awareIndexByPort[it.port] }
        val maliBitLo = if (maliIdx != null && maliIdx < 64) (1L shl maliIdx) else 0L
        val maliBitHi = if (maliIdx != null && maliIdx >= 64) (1L shl (maliIdx - 64)) else 0L
        for (i in this.windows.indices) {
            val w = this.windows[i]
            if (w.id in PURE_READ_NON_SCHEDULING_DEVICES) windowReadMutatesSchedule[i] = false
            when {
                w.port is RetainedMmioBank || w.id in PURE_NON_SCHEDULING_DEVICES ->
                    windowScheduleMode[i] = SCHEDULE_NONE
                awareCount <= 128 && w.id in SELF_CONTAINED_PER_PORT_DEVICES -> {
                    val selfIdx = awareIndexByPort[w.port]
                    if (selfIdx == null) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        if (selfIdx < 64) windowDirtyMaskLo[i] = 1L shl selfIdx
                        else windowDirtyMaskHi[i] = 1L shl (selfIdx - 64)
                    }
                }
                awareCount <= 128 && w.id == "soc-isp" -> {
                    val selfIdx = awareIndexByPort[w.port]
                    val maskLo: Long
                    val maskHi: Long
                    if (w.base == 0xFFE0_0000L) {
                        maskLo = if (selfIdx != null && selfIdx < 64) (1L shl selfIdx) else 0L
                        maskHi = if (selfIdx != null && selfIdx >= 64) (1L shl (selfIdx - 64)) else 0L
                    } else {
                        maskLo = (awareIdMaskLo[w.id] ?: 0L) and maliBitLo.inv()
                        maskHi = (awareIdMaskHi[w.id] ?: 0L) and maliBitHi.inv()
                    }
                    if (maskLo == 0L && maskHi == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMaskLo[i] = maskLo
                        windowDirtyMaskHi[i] = maskHi
                    }
                }
                awareCount <= 128 && w.id in SELF_CONTAINED_SCHEDULING_DEVICES -> {
                    val maskLo = awareIdMaskLo[w.id] ?: 0L
                    val maskHi = awareIdMaskHi[w.id] ?: 0L
                    if (maskLo == 0L && maskHi == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMaskLo[i] = maskLo
                        windowDirtyMaskHi[i] = maskHi
                    }
                }
                awareCount <= 128 && w.id in DMA_FAMILY_DEVICES -> {
                    if (dmaFamilyMaskLo == 0L && dmaFamilyMaskHi == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMaskLo[i] = dmaFamilyMaskLo
                        windowDirtyMaskHi[i] = dmaFamilyMaskHi
                    }
                }
                awareCount <= 128 && w.id in SHUTTER_FAMILY_DEVICES -> {
                    // CCDTG can return the CCDIF to live-view timing during a C000 write.
                    val maskLo = if (w.id == "soc-isp-cmd" && w.base == 0xfff6c000L) {
                        shutterFamilyMaskLo or ((awareIdMaskLo["soc-isp"] ?: 0L) and maliBitLo.inv())
                    } else shutterFamilyMaskLo
                    val maskHi = if (w.id == "soc-isp-cmd" && w.base == 0xfff6c000L) {
                        shutterFamilyMaskHi or ((awareIdMaskHi["soc-isp"] ?: 0L) and maliBitHi.inv())
                    } else shutterFamilyMaskHi
                    if (maskLo == 0L && maskHi == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMaskLo[i] = maskLo
                        windowDirtyMaskHi[i] = maskHi
                    }
                }
                else -> windowScheduleMode[i] = SCHEDULE_ALL
            }
        }
        for (i in this.windows.indices) {
            val base = windowBases[i]
            val end = windowEnds[i]
            if (end <= HIGH_MMIO_BASE || base >= HIGH_MMIO_END) continue
            val clampedBase = maxOf(base, HIGH_MMIO_BASE)
            val clampedEnd = minOf(end, HIGH_MMIO_END)
            val firstPage = ((clampedBase - HIGH_MMIO_BASE) ushr 12).toInt()
            val lastPage = (((clampedEnd - 1L) - HIGH_MMIO_BASE) ushr 12).toInt()
            for (p in firstPage..lastPage) {
                val cur = highPageSingleWindow[p]
                if (cur == -1) {
                    highPageSingleWindow[p] = i
                    highPageFirstIdx[p] = i
                    highPageLastIdx[p] = i
                } else {
                    highPageSingleWindow[p] = -2
                    highPageLastIdx[p] = i
                }
            }
        }
    }
    private fun findIndex(address: Long, size: Int): Int {
        require(size == 1 || size == 2 || size == 4 || size == 8)
        if (address >= HIGH_MMIO_BASE && address <= HIGH_MMIO_END - size) {
            val endAddr = address + size
            val page = ((address - HIGH_MMIO_BASE) ushr 12).toInt()
            val single = highPageSingleWindow[page]
            if (single >= 0) {
                val wBase = windowBases[single]
                val wEnd = windowEnds[single]
                if (address >= wBase && address < wEnd) {
                    require(endAddr <= wEnd) { "cross-window MMIO" }
                    return single
                }
                if (((endAddr - 1L) ushr 12) == (address ushr 12)) {
                    require(address >= wEnd || endAddr <= wBase) { "cross-window MMIO" }
                    return -1
                }
            } else if (single == -1) {
                if (((endAddr - 1L) ushr 12) == (address ushr 12)) {
                    return -1
                }
            } else if (((endAddr - 1L) ushr 12) == (address ushr 12)) {
                var i = highPageFirstIdx[page]
                val last = highPageLastIdx[page]
                while (i <= last) {
                    val wBase = windowBases[i]
                    if (address < wBase) {
                        require(endAddr <= wBase) { "cross-window MMIO" }
                        return -1
                    }
                    val wEnd = windowEnds[i]
                    if (address < wEnd) {
                        require(endAddr <= wEnd) { "cross-window MMIO" }
                        return i
                    }
                    i++
                }
                return -1
            }
        }
        val n = windowBases.size
        if (n > 0) {
            val cached = lastWindowIdx
            if (address >= windowBases[cached] && address < windowEnds[cached]) {
                require(size.toLong() <= windowEnds[cached] - address) { "cross-window MMIO" }
                return cached
            }
        }
        var low = 0; var high = n - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val base = windowBases[mid]
            val end = windowEnds[mid]
            when {
                address < base -> high = mid - 1
                address >= end -> low = mid + 1
                else -> {
                    require(size.toLong() <= end - address) { "cross-window MMIO" }
                    lastWindowIdx = mid
                    return mid
                }
            }
        }
        // A start in a hole must not straddle into the next known window.
        require(address >= 0 && address <= 0x1_0000_0000L - size) { "MMIO address overflow" }
        require(low >= n || address + size <= windowBases[low]) { "cross-window MMIO" }
        return -1
    }
    fun read(address: Long, size: Int): Long {
        val sequence = ++accessSequence
        val idx = try { findIndex(address,size) } catch(error:Throwable) {
            invalidateSchedule()
            if (relaxedFallback != null) return relaxedFallback.read(address, size, "router", error.message)
            discovery?.refused(sequence,address,size,false,reason=DiscoveryJournal.ROUTER);throw error
        }
        if (idx >= 0) {
            if (windowReadMutatesSchedule[idx]) markWindowDirty(idx)
            return try { windowPorts[idx].read(address-windowBases[idx],size) } catch(error:Throwable) {
                invalidateSchedule()
                if (error is PeripheralBusAbortException) {
                    discovery?.refused(sequence,address,size,false,reason=DiscoveryJournal.DEVICE)
                    throw error
                }
                if (relaxedFallback != null) return relaxedFallback.read(address, size, "device", error.message)
                discovery?.refused(sequence,address,size,false,reason=DiscoveryJournal.DEVICE);throw error
            }
        }
        discoveryDirty = true
        if (discovery != null) {
            return try {
                discovery.access(sequence,address,size,false)
            } catch (error: Throwable) {
                invalidateSchedule()
                if (relaxedFallback != null) relaxedFallback.read(address, size, "discovery", error.message)
                else throw error
            }
        }
        if (relaxedFallback != null) return relaxedFallback.read(address, size, "unmapped")
        error("unmapped MMIO 0x" + address.toString(16) + " size " + size)
    }
    /** Native pages are only transport. Preserve precise windows/holes in find(). */
    fun pageRanges(): List<Pair<Long, Long>> {
        val ranges = ArrayList<Pair<Long, Long>>()
        val transport = windows.map { (it.base and -4096L) to (((it.base + it.size + 4095) and -4096L) - (it.base and -4096L)) } + discovery?.pageRanges().orEmpty()
        transport.sortedBy { it.first }.forEach { (base,size) ->
            val end = base + size
            val previous = ranges.lastOrNull()
            if (previous != null && base <= previous.second) ranges[ranges.lastIndex] = previous.first to maxOf(previous.second, end)
            else ranges.add(base to end)
        }
        return ranges.map { it.first to it.second - it.first }
    }
    fun write(address: Long, size: Int, value: Long) {
        val sequence = ++accessSequence
        val idx = try { findIndex(address,size) } catch(error:Throwable) {
            invalidateSchedule()
            if (relaxedFallback != null) { relaxedFallback.write(address, size, value, "router", error.message); return }
            discovery?.refused(sequence,address,size,true,value,DiscoveryJournal.ROUTER);throw error
        }
        if (idx >= 0) {
            markWindowDirty(idx)
            try {
                windowPorts[idx].write(address - windowBases[idx], size, value)
                relaxedFallback?.observeModeledWrite(address, size, value)
            } catch (error: Throwable) {
                invalidateSchedule()
                if (error is PeripheralBusAbortException) {
                    discovery?.refused(sequence,address,size,true,value,DiscoveryJournal.DEVICE)
                    throw error
                }
                if (relaxedFallback != null) { relaxedFallback.write(address, size, value, "device", error.message); return }
                discovery?.refused(sequence,address,size,true,value,DiscoveryJournal.DEVICE);throw error
            }
        } else {
            discoveryDirty = true
            if (discovery != null) try {
                discovery.access(sequence,address,size,true,value)
            } catch (error: Throwable) {
                invalidateSchedule()
                if (relaxedFallback != null) relaxedFallback.write(address, size, value, "discovery", error.message)
                else throw error
            }
            else if (relaxedFallback != null) relaxedFallback.write(address, size, value, "unmapped")
            else error("unmapped MMIO 0x" + address.toString(16) + " size " + size)
        }
    }
    /** Uses the same sequence as routed MMIO; never attempts a discovery model access. */
    fun refusedNative(kind:Int,address:Long,size:Int,value:Long) {
        invalidateSchedule()
        val direction=nativeDirection(kind)
        discovery?.refused(++accessSequence,address,size,direction=="write",value,DiscoveryJournal.NATIVE,direction=="execute",kind)
    }
    companion object {
        private const val HIGH_MMIO_BASE = 0xFF00_0000L
        private const val HIGH_MMIO_END = 0x1_0000_0000L
        private const val SCHEDULE_NONE: Byte = 0
        private const val SCHEDULE_SELF_MASK: Byte = 1
        private const val SCHEDULE_ALL: Byte = 2
        private val PURE_NON_SCHEDULING_DEVICES = setOf(
            "soc-gic", "soc-selection", "soc-system-control", "soc-keyed-io", "soc-hdmi", "soc-uart",
            "soc-isp-setup",
        )
        private val PURE_READ_NON_SCHEDULING_DEVICES = setOf("soc-isp", "soc-gpio")
        private val ASYNC_POLL_DEVICES = setOf("soc-sdio")
        private val SELF_CONTAINED_PER_PORT_DEVICES = setOf(
            "soc-jpeg", "soc-dram", "soc-configuration",
        )
        private val SELF_CONTAINED_SCHEDULING_DEVICES = setOf(
            "soc-timer", "soc-adc", "soc-temperature", "soc-dram", "soc-method4",
            "soc-isp", "soc-vglib-init", "soc-i2c", "soc-jpeg", "soc-panel", "soc-configuration",
            "soc-sdio",
        )
        private val DMA_FAMILY_DEVICES = setOf(
            "soc-dma", "soc-spi", "soc-flash", "soc-sd-host", "soc-serial-engine",
        )
        private val SHUTTER_FAMILY_DEVICES = setOf(
            "soc-gpio", "soc-isp-cmd",
        )
        // Native invalid-memory kind codes (16=READ through 24=FETCH_PROT).
        fun nativeDirection(kind:Int):String=when(kind) {19,23->"read";20,22->"write";21,24->"execute";else->error("Unexpected native memory fault kind")}
    }
    /** Query every distinct port without changing device state; stale events must not be silently skipped. */
    fun nextDeadline(now: Long): Long? {
        require(now >= 0)
        var earliest = Long.MAX_VALUE
        var found = false
        val scheduled = scheduledDevices
        for (i in scheduled.indices) {
            val at = scheduled[i].nextDeadline() ?: continue
            require(at > now) { "scheduled MMIO deadline is not in the future (dev=${scheduled[i].javaClass.simpleName} at=$at now=$now)" }
            if (!found || at < earliest) {
                earliest = at
                found = true
            }
        }
        if (discovery != null) {
            val at = discovery.nextDeadlinePrimitive()
            if (at != Long.MAX_VALUE) {
                require(at > now) { "scheduled discovery deadline is not in the future (at=$at now=$now)" }
                if (!found || at < earliest) {
                    earliest = at
                    found = true
                }
            }
        }
        return if (found) earliest else null
    }
    fun beforeInstruction(tick: Long) {
        if (alwaysTickDevices.isNotEmpty()) {
            try {
                for (i in clocked.indices) {
                    val dev = clocked[i]
                    if (dev !is TickAwareMmioDevice || dev.requiresEveryTick() || dev.nextInterestedTick() <= tick) {
                        dev.beforeInstruction(tick)
                    }
                }
                if (discovery != null && discovery.nextInterestedTick() <= tick) {
                    discovery.beforeInstruction(tick)
                }
            } finally {
                allDirty = true
                discoveryDirty = true
            }
            return
        }
        if (allDirty || dirtyMaskLo != 0L || dirtyMaskHi != 0L || discoveryDirty) {
            flushDirtySchedule()
        }
        pollAsyncDevices()
        if (tick < nextScheduledTick) return

        val n = awareCount
        val aware = tickAwareDevices
        val ticks = deviceNextTicks
        if (n <= 128) {
            var dueMaskLo = 0L
            var dueMaskHi = 0L
            if (n > 0 && ticks[heapDevice[0]] <= tick) {
                var sp = 0
                heapScanStack[sp++] = 0
                while (sp > 0) {
                    val pos = heapScanStack[--sp]
                    val dev = heapDevice[pos]
                    if (ticks[dev] <= tick) {
                        if (dev < 64) dueMaskLo = dueMaskLo or (1L shl dev)
                        else dueMaskHi = dueMaskHi or (1L shl (dev - 64))
                        val left = (pos shl 1) + 1
                        if (left < n) {
                            heapScanStack[sp++] = left
                            val right = left + 1
                            if (right < n) heapScanStack[sp++] = right
                        }
                    }
                }
            }
            var remainingDueLo = dueMaskLo
            var remainingDueHi = dueMaskHi
            var refreshMaskLo = dueMaskLo
            var refreshMaskHi = dueMaskHi
            var ranFullCrossDevice = false
            try {
                while (remainingDueLo != 0L || remainingDueHi != 0L) {
                    val dev = if (remainingDueLo != 0L) {
                        val b = java.lang.Long.numberOfTrailingZeros(remainingDueLo)
                        remainingDueLo = remainingDueLo and (remainingDueLo - 1L)
                        b
                    } else {
                        val b = java.lang.Long.numberOfTrailingZeros(remainingDueHi)
                        remainingDueHi = remainingDueHi and (remainingDueHi - 1L)
                        64 + b
                    }
                    aware[dev].beforeInstruction(tick)
                    if (awareImpactFull[dev]) {
                        ranFullCrossDevice = true
                        for (j in (dev + 1) until n) {
                            if (aware[j].nextInterestedTick() <= tick) {
                                aware[j].beforeInstruction(tick)
                            }
                        }
                        break
                    }
                    val impactLo = awareImpactMaskLo[dev]
                    val impactHi = awareImpactMaskHi[dev]
                    refreshMaskLo = refreshMaskLo or impactLo
                    refreshMaskHi = refreshMaskHi or impactHi
                    if (dev < 64) {
                        var laterLo = if (dev < 63) (impactLo and (-1L shl (dev + 1)) and remainingDueLo.inv()) else 0L
                        while (laterLo != 0L) {
                            val peer = java.lang.Long.numberOfTrailingZeros(laterLo)
                            laterLo = laterLo and (laterLo - 1L)
                            val peerNext = aware[peer].nextInterestedTick()
                            updateDeviceTick(peer, peerNext)
                            val peerBit = 1L shl peer
                            if (peerNext <= tick) {
                                remainingDueLo = remainingDueLo or peerBit
                                refreshMaskLo = refreshMaskLo or peerBit
                            } else {
                                refreshMaskLo = refreshMaskLo and peerBit.inv()
                            }
                        }
                        var laterHi = impactHi and remainingDueHi.inv()
                        while (laterHi != 0L) {
                            val peerBitIdx = java.lang.Long.numberOfTrailingZeros(laterHi)
                            laterHi = laterHi and (laterHi - 1L)
                            val peer = 64 + peerBitIdx
                            val peerNext = aware[peer].nextInterestedTick()
                            updateDeviceTick(peer, peerNext)
                            val peerBit = 1L shl peerBitIdx
                            if (peerNext <= tick) {
                                remainingDueHi = remainingDueHi or peerBit
                                refreshMaskHi = refreshMaskHi or peerBit
                            } else {
                                refreshMaskHi = refreshMaskHi and peerBit.inv()
                            }
                        }
                    } else {
                        val bitIdx = dev - 64
                        var laterHi = if (bitIdx < 63) (impactHi and (-1L shl (bitIdx + 1)) and remainingDueHi.inv()) else 0L
                        while (laterHi != 0L) {
                            val peerBitIdx = java.lang.Long.numberOfTrailingZeros(laterHi)
                            laterHi = laterHi and (laterHi - 1L)
                            val peer = 64 + peerBitIdx
                            val peerNext = aware[peer].nextInterestedTick()
                            updateDeviceTick(peer, peerNext)
                            val peerBit = 1L shl peerBitIdx
                            if (peerNext <= tick) {
                                remainingDueHi = remainingDueHi or peerBit
                                refreshMaskHi = refreshMaskHi or peerBit
                            } else {
                                refreshMaskHi = refreshMaskHi and peerBit.inv()
                            }
                        }
                    }
                }
                if (discovery != null) {
                    if (ranFullCrossDevice) discoveryNextTick = discovery.nextInterestedTick()
                    if (discoveryNextTick <= tick) {
                        discovery.beforeInstruction(tick)
                        discoveryNextTick = discovery.nextInterestedTick()
                    }
                }
            } catch (t: Throwable) {
                allDirty = true
                discoveryDirty = true
                throw t
            }
            if (ranFullCrossDevice) {
                allDirty = true
                flushDirtySchedule()
            } else {
                while (refreshMaskLo != 0L) {
                    val dev = java.lang.Long.numberOfTrailingZeros(refreshMaskLo)
                    refreshMaskLo = refreshMaskLo and (refreshMaskLo - 1L)
                    updateDeviceTick(dev, aware[dev].nextInterestedTick())
                }
                while (refreshMaskHi != 0L) {
                    val dev = 64 + java.lang.Long.numberOfTrailingZeros(refreshMaskHi)
                    refreshMaskHi = refreshMaskHi and (refreshMaskHi - 1L)
                    updateDeviceTick(dev, aware[dev].nextInterestedTick())
                }
                val heapMin = if (n > 0) ticks[heapDevice[0]] else Long.MAX_VALUE
                nextScheduledTick = if (discoveryNextTick < heapMin) discoveryNextTick else heapMin
            }
            return
        }

        // Fallback for >64 tick-aware devices
        var dueCount = 0
        if (n > 0 && ticks[heapDevice[0]] <= tick) {
            var sp = 0
            heapScanStack[sp++] = 0
            while (sp > 0) {
                val pos = heapScanStack[--sp]
                val dev = heapDevice[pos]
                if (ticks[dev] <= tick) {
                    dueIndices[dueCount++] = dev
                    val left = (pos shl 1) + 1
                    if (left < n) {
                        heapScanStack[sp++] = left
                        val right = left + 1
                        if (right < n) heapScanStack[sp++] = right
                    }
                }
            }
            if (dueCount > 1) {
                for (i in 1 until dueCount) {
                    val key = dueIndices[i]
                    var j = i - 1
                    while (j >= 0 && dueIndices[j] > key) {
                        dueIndices[j + 1] = dueIndices[j]
                        j--
                    }
                    dueIndices[j + 1] = key
                }
            }
        }
        try {
            if (dueCount > 0) {
                for (j in dueIndices[0] until n) {
                    if (aware[j].nextInterestedTick() <= tick) aware[j].beforeInstruction(tick)
                }
            }
            if (discovery != null) {
                discoveryNextTick = discovery.nextInterestedTick()
                if (discoveryNextTick <= tick) {
                    discovery.beforeInstruction(tick)
                    discoveryNextTick = discovery.nextInterestedTick()
                }
            }
        } catch (t: Throwable) {
            allDirty = true
            discoveryDirty = true
            throw t
        }
        allDirty = true
        flushDirtySchedule()
    }
}
