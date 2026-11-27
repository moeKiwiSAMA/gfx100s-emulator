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
    private val awareImpactMask: LongArray
    private val windowScheduleMode = ByteArray(this.windows.size)
    private val windowDirtyMask = LongArray(this.windows.size)
    private val deviceNextTicks = LongArray(awareCount) { Long.MAX_VALUE }
    private val heapDevice = IntArray(awareCount) { it }
    private val deviceHeapPos = IntArray(awareCount) { it }
    private val dueIndices = IntArray(awareCount)
    private val heapScanStack = IntArray(awareCount)
    private var dirtyMask = 0L
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
    fun nextScheduledTickIfClean(): Long = if (allDirty || dirtyMask != 0L || discoveryDirty) 0L else nextScheduledTick

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
            dirtyMask = 0L
        } else {
            var mask = dirtyMask
            dirtyMask = 0L
            while (mask != 0L) {
                val dev = java.lang.Long.numberOfTrailingZeros(mask)
                mask = mask and (mask - 1L)
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
            SCHEDULE_SELF_MASK -> dirtyMask = dirtyMask or windowDirtyMask[idx]
            else -> {
                allDirty = true
                discoveryDirty = true
            }
        }
    }

    fun refreshNextScheduledTick(): Long {
        if (allDirty || dirtyMask != 0L || discoveryDirty) flushDirtySchedule()
        return nextScheduledTick
    }

    /**
     * Returns the earliest scheduled tick across all devices EXCEPT [excluded] (e.g. `soc-timer`
     * when C++ inline timer comparator IRQ auto-raise is active inside `fuji_dyn_run_cluster_fast`).
     */
    fun nextScheduledTickExcluding(excluded: TickAwareMmioDevice?): Long {
        if (excluded == null) return refreshNextScheduledTick()
        if (allDirty || dirtyMask != 0L || discoveryDirty) flushDirtySchedule()
        var best = discoveryNextTick
        for (i in 0 until awareCount) {
            if (tickAwareDevices[i] === excluded) continue
            val t = deviceNextTicks[i]
            if (t < best) best = t
        }
        return best
    }

    init {
        this.windows.forEach { require(it.base >= 0 && it.size > 0 && it.base <= 0x1_0000_0000L - it.size) }
        this.windows.zipWithNext().forEach { (a, b) -> require(a.base + a.size <= b.base) { "overlapping MMIO windows" } }
        val awareIndexByPort = java.util.IdentityHashMap<MmioDevice, Int>(awareCount * 2)
        for (i in 0 until awareCount) awareIndexByPort[tickAwareDevices[i]] = i
        val awareIdMask = HashMap<String, Long>()
        var dmaFamilyMask = 0L
        var shutterFamilyMask = 0L
        val impact = LongArray(awareCount) { -1L }
        if (awareCount <= 64) {
            for (w in this.windows) {
                val aIdx = awareIndexByPort[w.port]
                if (aIdx != null) {
                    val bit = 1L shl aIdx
                    awareIdMask[w.id] = (awareIdMask[w.id] ?: 0L) or bit
                    if (w.id in DMA_FAMILY_DEVICES) dmaFamilyMask = dmaFamilyMask or bit
                    if (w.id in SHUTTER_FAMILY_DEVICES) shutterFamilyMask = shutterFamilyMask or bit
                }
            }
            for (w in this.windows) {
                val aIdx = awareIndexByPort[w.port] ?: continue
                when {
                    w.id in SELF_CONTAINED_SCHEDULING_DEVICES -> impact[aIdx] = 1L shl aIdx
                    w.id in DMA_FAMILY_DEVICES -> impact[aIdx] = dmaFamilyMask
                    w.id in SHUTTER_FAMILY_DEVICES -> impact[aIdx] = shutterFamilyMask
                }
            }
        }
        awareImpactMask = impact
        for (i in this.windows.indices) {
            val w = this.windows[i]
            if (w.id in PURE_READ_NON_SCHEDULING_DEVICES) windowReadMutatesSchedule[i] = false
            when {
                w.port is RetainedMmioBank || w.id in PURE_NON_SCHEDULING_DEVICES ->
                    windowScheduleMode[i] = SCHEDULE_NONE
                awareCount <= 64 && w.id in SELF_CONTAINED_SCHEDULING_DEVICES -> {
                    val mask = awareIdMask[w.id] ?: 0L
                    if (mask == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMask[i] = mask
                    }
                }
                awareCount <= 64 && w.id in DMA_FAMILY_DEVICES -> {
                    if (dmaFamilyMask == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMask[i] = dmaFamilyMask
                    }
                }
                awareCount <= 64 && w.id in SHUTTER_FAMILY_DEVICES -> {
                    // CCDTG can return the CCDIF to live-view timing during a C000 write.
                    val mask = if (w.id == "soc-isp-cmd" && w.base == 0xfff6c000L) {
                        shutterFamilyMask or (awareIdMask["soc-isp"] ?: 0L)
                    } else shutterFamilyMask
                    if (mask == 0L) {
                        windowScheduleMode[i] = SCHEDULE_NONE
                    } else {
                        windowScheduleMode[i] = SCHEDULE_SELF_MASK
                        windowDirtyMask[i] = mask
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
        )
        private val PURE_READ_NON_SCHEDULING_DEVICES = setOf("soc-isp", "soc-gpio")
        private val SELF_CONTAINED_SCHEDULING_DEVICES = setOf(
            "soc-timer", "soc-adc", "soc-temperature", "soc-dram", "soc-method4",
            "soc-isp", "soc-vglib-init", "soc-i2c", "soc-jpeg", "soc-panel", "soc-configuration",
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
            require(at > now) { "scheduled MMIO deadline is not in the future" }
            if (!found || at < earliest) {
                earliest = at
                found = true
            }
        }
        if (discovery != null) {
            val at = discovery.nextDeadlinePrimitive()
            if (at != Long.MAX_VALUE) {
                require(at > now) { "scheduled discovery deadline is not in the future" }
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
        if (allDirty || dirtyMask != 0L || discoveryDirty) {
            flushDirtySchedule()
        }
        if (tick < nextScheduledTick) return

        val n = awareCount
        val aware = tickAwareDevices
        val ticks = deviceNextTicks
        if (n <= 64) {
            var dueMask = 0L
            if (n > 0 && ticks[heapDevice[0]] <= tick) {
                var sp = 0
                heapScanStack[sp++] = 0
                while (sp > 0) {
                    val pos = heapScanStack[--sp]
                    val dev = heapDevice[pos]
                    if (ticks[dev] <= tick) {
                        dueMask = dueMask or (1L shl dev)
                        val left = (pos shl 1) + 1
                        if (left < n) {
                            heapScanStack[sp++] = left
                            val right = left + 1
                            if (right < n) heapScanStack[sp++] = right
                        }
                    }
                }
            }
            var remainingDue = dueMask
            var refreshMask = dueMask
            var ranFullCrossDevice = false
            try {
                while (remainingDue != 0L) {
                    val dev = java.lang.Long.numberOfTrailingZeros(remainingDue)
                    remainingDue = remainingDue and (remainingDue - 1L)
                    aware[dev].beforeInstruction(tick)
                    val impact = awareImpactMask[dev]
                    if (impact == -1L) {
                        ranFullCrossDevice = true
                        for (j in (dev + 1) until n) {
                            if (aware[j].nextInterestedTick() <= tick) {
                                aware[j].beforeInstruction(tick)
                            }
                        }
                        break
                    }
                    refreshMask = refreshMask or impact
                    var laterPeers = if (dev < 63) (impact and (-1L shl (dev + 1)) and remainingDue.inv()) else 0L
                    while (laterPeers != 0L) {
                        val peer = java.lang.Long.numberOfTrailingZeros(laterPeers)
                        laterPeers = laterPeers and (laterPeers - 1L)
                        val peerNext = aware[peer].nextInterestedTick()
                        updateDeviceTick(peer, peerNext)
                        val peerBit = 1L shl peer
                        if (peerNext <= tick) {
                            remainingDue = remainingDue or peerBit
                            refreshMask = refreshMask or peerBit
                        } else {
                            refreshMask = refreshMask and peerBit.inv()
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
                while (refreshMask != 0L) {
                    val dev = java.lang.Long.numberOfTrailingZeros(refreshMask)
                    refreshMask = refreshMask and (refreshMask - 1L)
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
