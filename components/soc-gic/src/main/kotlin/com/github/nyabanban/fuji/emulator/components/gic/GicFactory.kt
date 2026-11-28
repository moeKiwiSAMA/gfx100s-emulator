package com.github.nyabanban.fuji.emulator.components.gic

import com.github.nyabanban.fuji.emulator.api.*
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder

/**
 * Shared C-ABI layout (`2,464 B = 0x9A0`, 8-byte aligned) matching `dynarmic::a32_sys::GicV2ShadowState`.
 */
object GicV2ShadowLayout {
    const val SIZE = 0x9A0L
    const val OFF_ACTIVE = 0x000L
    const val OFF_CORE_COUNT = 0x004L
    const val OFF_DIST_CTRL = 0x008L
    const val OFF_DIRTY_SPI_MASK = 0x00CL
    const val OFF_NATIVE_SEQ = 0x010L
    const val OFF_FAST_HITS = 0x018L
    const val OFF_SHARED_ENABLED = 0x020L
    const val OFF_SHARED_PENDING = 0x060L
    const val OFF_SHARED_LEVELS = 0x0A0L
    const val OFF_SHARED_ACTIVE = 0x0E0L
    const val OFF_SHARED_EDGE = 0x120L
    const val OFF_TARGET_CORE_BITS = 0x160L
    const val OFF_SHARED_PRIORITY = 0x260L
    const val OFF_SHARED_TARGETS = 0x440L
    const val OFF_CORES = 0x620L
    const val CORE_STRIDE = 0xE0L

    const val CORE_OFF_IRQ_PENDING = 0x00L
    const val CORE_OFF_CPU_CTRL = 0x04L
    const val CORE_OFF_PMR = 0x08L
    const val CORE_OFF_BPR = 0x0CL
    const val CORE_OFF_LOCAL_ENABLED = 0x10L
    const val CORE_OFF_LOCAL_PENDING = 0x14L
    const val CORE_OFF_LOCAL_LEVELS = 0x18L
    const val CORE_OFF_LOCAL_ACTIVE = 0x1CL
    const val CORE_OFF_LOCAL_EDGE = 0x20L
    const val CORE_OFF_ACTIVE_STACK_SIZE = 0x24L
    const val CORE_OFF_ACTIVE_STACK = 0x28L
    const val CORE_OFF_LOCAL_PRIORITY = 0xA8L
    const val CORE_OFF_SGI_SENDERS = 0xC8L
    const val CORE_OFF_DIRTY_FLAG = 0xD8L

    const val DIRTY_SPI_PEND_ACT = 1
    const val DIRTY_SPI_PRIO = 2
    const val DIRTY_SPI_ENABLE = 4
    const val DIRTY_SPI_TARGETS = 8
    const val DIRTY_SPI_EDGE = 16
    const val MAX_NATIVE_STACK = 32

    internal val LITTLE_INT: ValueLayout.OfInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)
    internal val LITTLE_LONG: ValueLayout.OfLong = ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN)
}

/** Single CPU bounded GICv2 subset. SGI requires separate HYP-015 opt-in; security/FIQ refuse. */
class GicFactory : ComponentFactory {
    companion object {
        val DESCRIPTOR = ComponentDescriptor("soc-gic", "0.0.1", settings = listOf(
            Setting("profile", ValueKind.TEXT, "gicv2-catalog-experimental-v1", choices = listOf("gicv2-catalog-experimental-v1"), live = false),
            Setting("sgiProfile", ValueKind.TEXT, "", choices = listOf("","single-core-experimental-v1"), live = false),
            Setting("coreProfile",ValueKind.TEXT,"",choices=listOf("","four-context-functional-experimental-v1"),live=false)))
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = if(config["coreProfile"]=="four-context-functional-experimental-v1") ClusterGic(context,config) else Gic(context,config)
    private class Bools(
        private val shared: BooleanArray,
        private val sharedBits: LongArray,
        private val core: Int,
        private val state: Shared,
        private val mode: Byte,
        initial: (Int)->Boolean={false},
    ) {
        companion object {
            const val MODE_TARGETED: Byte = 0
            const val MODE_LEVEL: Byte = 1
            const val MODE_ACTIVE: Byte = 2
        }
        private val local=BooleanArray(32,initial)
        var localBits: Int = 0
            private set
        init {
            var m = 0
            for (i in 0..31) if (local[i]) m = m or (1 shl i)
            localBits = m
        }
        operator fun get(i: Int)=if(i<32) local[i] else shared[i-32]
        operator fun set(i: Int,value: Boolean) {
            if(get(i)==value)return
            if(i<32) {
                local[i]=value
                localBits = if (value) localBits or (1 shl i) else localBits and (1 shl i).inv()
                state.invalidateCore(core)
            } else {
                val idx = i - 32
                shared[idx]=value
                val w = i ushr 6
                val bit = 1L shl (i and 63)
                sharedBits[w] = if (value) sharedBits[w] or bit else sharedBits[w] and bit.inv()
                when (mode) {
                    MODE_LEVEL -> {
                        if (!state.edge[idx]) state.invalidateMask(state.targets[idx])
                    }
                    MODE_ACTIVE -> {
                        state.invalidateMask(state.targets[idx] or (1 shl core))
                    }
                    else -> {
                        state.invalidateMask(state.targets[idx])
                    }
                }
            }
            state.bumpShadowVersion()
        }
        fun toList()=List(512) { get(it) }
        fun copyLocal(): BooleanArray = local.copyOf()
        fun restoreLocal(source: BooleanArray) {
            source.copyInto(local)
            var m = 0
            for (i in 0..31) if (local[i]) m = m or (1 shl i)
            localBits = m
        }
        fun restoreLocalBits(bits: Int) {
            if (localBits == bits) return
            var diff = localBits xor bits
            localBits = bits
            while (diff != 0) {
                val bit = Integer.numberOfTrailingZeros(diff)
                diff = diff and (diff - 1)
                local[bit] = (bits and (1 shl bit)) != 0
            }
        }
    }
    private class Ints(
        private val shared: IntArray,
        private val core: Int,
        private val state: Shared,
        private val isTargets: Boolean,
        initial: Int=0,
    ) {
        private val local=IntArray(32) { initial }
        operator fun get(i: Int)=if(i<32) local[i] else shared[i-32]
        operator fun set(i: Int,value: Int) {
            if(i<32) {
                if (local[i] == value) return
                local[i]=value
                state.invalidateCore(core)
            } else {
                val idx = i - 32
                val old = shared[idx]
                if (old == value) return
                shared[idx]=value
                if (isTargets) {
                    state.setTargetBits(i, value)
                    state.invalidateMask(old or value)
                } else {
                    if (state.active[idx]) {
                        state.changed()
                    } else if (state.enabled[idx] && (state.pending[idx] || (state.levels[idx] && !state.edge[idx]))) {
                        state.invalidateMask(state.targets[idx])
                    }
                }
            }
            state.bumpShadowVersion()
        }
        fun toList()=List(512) { get(it) }
        fun copyLocal(): IntArray = local.copyOf()
        fun restoreLocal(source: IntArray) { source.copyInto(local) }
        fun localArray(): IntArray = local
    }
    private class EventRing {
        companion object {
            const val KIND_LINE: Byte = 0
            const val KIND_ACK: Byte = 1
            const val KIND_ACK_SGI: Byte = 2
            const val KIND_EOI: Byte = 3
            const val KIND_EOI_SGI: Byte = 4
            const val KIND_SGI_SINGLE: Byte = 5
            const val KIND_SGI_CLUSTER: Byte = 6
        }
        private val cap = 512
        private val kinds = ByteArray(cap)
        private val ticks = LongArray(cap)
        private val lines = IntArray(cap)
        private val arg1 = IntArray(cap)
        private val arg2 = IntArray(cap)
        private val flag = BooleanArray(cap)
        private var head = 0
        var size = 0
            private set
        fun clear() { head = 0; size = 0 }
        private fun nextSlot(): Int = if (size < cap) {
            val slot = (head + size) and 511
            size++
            slot
        } else {
            val slot = head
            head = (head + 1) and 511
            slot
        }
        fun addLine(tick: Long, line: Int, high: Boolean) {
            val s = nextSlot()
            kinds[s] = KIND_LINE; ticks[s] = tick; lines[s] = line; flag[s] = high
        }
        fun addAck(tick: Long, line: Int) {
            val s = nextSlot()
            kinds[s] = KIND_ACK; ticks[s] = tick; lines[s] = line
        }
        fun addAckSgi(tick: Long, line: Int, sender: Int, token: Int) {
            val s = nextSlot()
            kinds[s] = KIND_ACK_SGI; ticks[s] = tick; lines[s] = line; arg1[s] = sender; arg2[s] = token
        }
        fun addEoi(tick: Long, line: Int) {
            val s = nextSlot()
            kinds[s] = KIND_EOI; ticks[s] = tick; lines[s] = line
        }
        fun addEoiSgi(tick: Long, line: Int, token: Int) {
            val s = nextSlot()
            kinds[s] = KIND_EOI_SGI; ticks[s] = tick; lines[s] = line; arg2[s] = token
        }
        fun addSgiSingle(tick: Long, line: Int, targetFilter: Int, targetList: Int, deliveredCpu0: Boolean) {
            val s = nextSlot()
            kinds[s] = KIND_SGI_SINGLE; ticks[s] = tick; lines[s] = line
            arg1[s] = targetFilter; arg2[s] = targetList; flag[s] = deliveredCpu0
        }
        fun addSgiCluster(tick: Long, line: Int, sender: Int, targetMask: Int) {
            val s = nextSlot()
            kinds[s] = KIND_SGI_CLUSTER; ticks[s] = tick; lines[s] = line
            arg1[s] = sender; arg2[s] = targetMask
        }
        fun toList(): List<Map<String, Any>> {
            val out = ArrayList<Map<String, Any>>(size)
            for (i in 0 until size) {
                val s = (head + i) and 511
                val map: Map<String, Any> = when (kinds[s]) {
                    KIND_LINE -> linkedMapOf("operation" to "line", "tick" to ticks[s], "line" to lines[s], "high" to flag[s])
                    KIND_ACK -> linkedMapOf("operation" to "acknowledge", "tick" to ticks[s], "line" to lines[s])
                    KIND_ACK_SGI -> linkedMapOf("operation" to "acknowledge", "tick" to ticks[s], "line" to lines[s], "sender" to arg1[s], "token" to arg2[s])
                    KIND_EOI -> linkedMapOf("operation" to "eoi", "tick" to ticks[s], "line" to lines[s].toLong())
                    KIND_EOI_SGI -> linkedMapOf("operation" to "eoi", "tick" to ticks[s], "line" to lines[s].toLong(), "token" to arg2[s].toLong())
                    KIND_SGI_SINGLE -> linkedMapOf(
                        "operation" to "sgi", "tick" to ticks[s], "line" to lines[s],
                        "targetFilter" to arg1[s], "targetList" to arg2[s].toLong(),
                        "deliveredCpu0" to flag[s], "modeledCpus" to 1,
                    )
                    KIND_SGI_CLUSTER -> {
                        val mask = arg2[s]
                        val targets = ArrayList<Int>(4)
                        for (c in 0..3) if (mask and (1 shl c) != 0) targets.add(c)
                        linkedMapOf("operation" to "sgi", "tick" to ticks[s], "line" to lines[s], "sender" to arg1[s], "targets" to targets)
                    }
                    else -> error("Unknown GIC event kind ${kinds[s]}")
                }
                out.add(map)
            }
            return out
        }
        fun restore(entries: List<Map<String, Any>>) {
            clear()
            for (entry in entries) {
                val tick = (entry["tick"] as Number).toLong()
                when (entry["operation"]) {
                    "line" -> addLine(tick, (entry["line"] as Number).toInt(), entry["high"] as Boolean)
                    "acknowledge" -> if ("token" in entry) {
                        addAckSgi(tick, (entry["line"] as Number).toInt(), (entry["sender"] as Number).toInt(), (entry["token"] as Number).toInt())
                    } else {
                        addAck(tick, (entry["line"] as Number).toInt())
                    }
                    "eoi" -> if ("token" in entry) {
                        addEoiSgi(tick, (entry["line"] as Number).toInt(), (entry["token"] as Number).toInt())
                    } else {
                        addEoi(tick, (entry["line"] as Number).toInt())
                    }
                    "sgi" -> if ("targets" in entry) {
                        var mask = 0
                        @Suppress("UNCHECKED_CAST")
                        for (c in entry["targets"] as List<Int>) mask = mask or (1 shl c)
                        addSgiCluster(tick, (entry["line"] as Number).toInt(), (entry["sender"] as Number).toInt(), mask)
                    } else {
                        addSgiSingle(
                            tick,
                            (entry["line"] as Number).toInt(),
                            (entry["targetFilter"] as Number).toInt(),
                            (entry["targetList"] as Number).toInt(),
                            entry["deliveredCpu0"] as Boolean,
                        )
                    }
                    else -> error("Unknown GIC event operation ${entry["operation"]}")
                }
            }
        }
    }
    private class Shared {
        val shadowVersionCell = LongArray(1) { 1L }
        fun bumpShadowVersion() { shadowVersionCell[0]++ }
        var boundShadow: MemorySegment? = null
        var lastPulledNativeSeq: Long = 0L
        var control=0
            set(value) { if(field!=value) { field=value; changed(); bumpShadowVersion() } }
        // Owner-only, fixed four-view invalidation; null is a cached result too.
        val choiceValid=BooleanArray(4) { false }
        fun changed() { choiceValid.fill(false) }
        fun invalidateCore(core: Int) { choiceValid[core] = false }
        fun invalidateMask(mask: Int) {
            if ((mask and 1) != 0) choiceValid[0] = false
            if ((mask and 2) != 0) choiceValid[1] = false
            if ((mask and 4) != 0) choiceValid[2] = false
            if ((mask and 8) != 0) choiceValid[3] = false
        }
        fun syncSharedBitsFromNative(seg: MemorySegment, baseOffset: Long, bitsDest: LongArray, boolDest: BooleanArray) {
            for (w in 0..7) {
                var newWord = seg.get(GicV2ShadowLayout.LITTLE_LONG, baseOffset + (w shl 3).toLong())
                if (w == 0) newWord = newWord and -0x1_0000_0000L
                val oldWord = bitsDest[w]
                if (newWord == oldWord) continue
                bitsDest[w] = newWord
                var diff = newWord xor oldWord
                while (diff != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(diff)
                    diff = diff and (diff - 1L)
                    val irq = (w shl 6) or bit
                    if (irq >= 32) {
                        boolDest[irq - 32] = (newWord and (1L shl bit)) != 0L
                    }
                }
            }
        }
        fun writeSharedToNative(seg: MemorySegment, coreCount: Int) {
            seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_CORE_COUNT, coreCount)
            seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIST_CTRL, control)
            for (w in 0..7) {
                val off = (w shl 3).toLong()
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_ENABLED + off, enabledBits[w])
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_PENDING + off, pendingBits[w])
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_LEVELS + off, levelBits[w])
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_ACTIVE + off, activeBits[w])
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_SHARED_EDGE + off, edgeBits[w])
            }
            for (c in 0..3) {
                val arr = targetCoreBits[c]
                val cBase = GicV2ShadowLayout.OFF_TARGET_CORE_BITS + (c * 64).toLong()
                for (w in 0..7) {
                    seg.set(GicV2ShadowLayout.LITTLE_LONG, cBase + (w shl 3).toLong(), arr[w])
                }
            }
            for (i in 0 until 480) {
                seg.set(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + i.toLong(), priority[i].toByte())
                seg.set(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_TARGETS + i.toLong(), targets[i].toByte())
            }
            seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK, 0)
        }
        val enabled=BooleanArray(480);val pending=BooleanArray(480);val levels=BooleanArray(480);val active=BooleanArray(480);val edge=BooleanArray(480)
        val enabledBits=LongArray(8);val pendingBits=LongArray(8);val levelBits=LongArray(8);val activeBits=LongArray(8);val edgeBits=LongArray(8)
        val priority=IntArray(480);val targets=IntArray(480) { 1 }
        val targetCoreBits=Array(4) { core ->
            LongArray(8) { w ->
                if (core == 0) {
                    if (w == 0) -0x1_0000_0000L else -1L
                } else 0L
            }
        }
        fun setTargetBits(irq: Int, mask: Int) {
            val w = irq ushr 6
            val bit = 1L shl (irq and 63)
            val inv = bit.inv()
            for (c in 0..3) {
                val arr = targetCoreBits[c]
                arr[w] = if ((mask and (1 shl c)) != 0) arr[w] or bit else arr[w] and inv
            }
        }
        private fun rebuildBits(source: BooleanArray, dest: LongArray) {
            dest.fill(0L)
            for (idx in 0 until 480) {
                if (source[idx]) {
                    val irq = idx + 32
                    dest[irq ushr 6] = dest[irq ushr 6] or (1L shl (irq and 63))
                }
            }
        }
        fun saveState(): Map<String, Any> = linkedMapOf(
            "distributorControl" to control,
            "enabled" to GicSnapshotCodec.encodeBits(enabled),
            "pending" to GicSnapshotCodec.encodeBits(pending),
            "levels" to GicSnapshotCodec.encodeBits(levels),
            "active" to GicSnapshotCodec.encodeBits(active),
            "edge" to GicSnapshotCodec.encodeBits(edge),
            "priorities" to GicSnapshotCodec.encodeUnsignedBytes(priority),
            "targets" to GicSnapshotCodec.encodeUnsignedBytes(targets),
        )
        fun applyState(parsed: GicSnapshotCodec.ParsedShared) {
            control = parsed.distributorControl
            parsed.enabled.copyInto(enabled)
            parsed.pending.copyInto(pending)
            parsed.levels.copyInto(levels)
            parsed.active.copyInto(active)
            parsed.edge.copyInto(edge)
            parsed.priorities.copyInto(priority)
            parsed.targets.copyInto(targets)
            rebuildBits(enabled, enabledBits)
            rebuildBits(pending, pendingBits)
            rebuildBits(levels, levelBits)
            rebuildBits(active, activeBits)
            rebuildBits(edge, edgeBits)
            for (c in 0..3) targetCoreBits[c].fill(0L)
            for (idx in 0 until 480) {
                val mask = targets[idx]
                if (mask != 0) {
                    val irq = idx + 32
                    val w = irq ushr 6
                    val bit = 1L shl (irq and 63)
                    for (c in 0..3) if ((mask and (1 shl c)) != 0) targetCoreBits[c][w] = targetCoreBits[c][w] or bit
                }
            }
            changed()
            bumpShadowVersion()
        }
    }
    private class Gic(
        context: ComponentContext,
        config: Map<String, Any>,
        private val shared: Shared = Shared(),
        private val core: Int = 0,
        private val coreCount: Int = 1,
        register: Boolean = true,
        private val clusterPullHook: (() -> Unit)? = null,
    ) : ScalarComponent("soc-gic", context, config), CoupledEdgeInterruptSink, CpuInterruptPort, StatefulComponent {
        private var control: Int get()=shared.control; set(value){shared.control=value}
        private var cpuControl = 0
            set(value) { if(field!=value) { field=value; shared.invalidateCore(core); shared.bumpShadowVersion() } }
        private var pmr = 0
            set(value) { if(field!=value) { field=value; shared.invalidateCore(core); shared.bumpShadowVersion() } }
        private var bpr = 0
            set(value) { if(field!=value) { field=value; shared.invalidateCore(core); shared.bumpShadowVersion() } }
        val enabled=Bools(shared.enabled,shared.enabledBits,core,shared,Bools.MODE_TARGETED) { it<16 }
        val pending=Bools(shared.pending,shared.pendingBits,core,shared,Bools.MODE_TARGETED)
        val levels=Bools(shared.levels,shared.levelBits,core,shared,Bools.MODE_LEVEL)
        val active=Bools(shared.active,shared.activeBits,core,shared,Bools.MODE_ACTIVE)
        val edge=Bools(shared.edge,shared.edgeBits,core,shared,Bools.MODE_TARGETED)
        val priority=Ints(shared.priority,core,shared,false)
        val targets=Ints(shared.targets,core,shared,true,1 shl core)
        private var stackData = IntArray(32)
        private var stackSize = 0
        fun currentStackSize(): Int = stackSize
        fun stackIsNotEmpty(): Boolean = stackSize > 0
        fun stackLast(): Int = stackData[stackSize - 1]
        fun stackPush(v: Int) {
            if (stackSize == stackData.size) stackData = stackData.copyOf(stackSize * 2)
            stackData[stackSize++] = v
            shared.bumpShadowVersion()
        }
        fun stackPop(): Int {
            val popped = stackData[--stackSize]
            shared.bumpShadowVersion()
            return popped
        }
        private fun stackToList(): List<Int> {
            val out = ArrayList<Int>(stackSize)
            for (i in 0 until stackSize) out.add(stackData[i])
            return out
        }
        fun writeCoreToNative(seg: MemorySegment, sgiSendersRow: IntArray?) {
            val cBase = GicV2ShadowLayout.OFF_CORES + core.toLong() * GicV2ShadowLayout.CORE_STRIDE
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_IRQ_PENDING, if (chooseIntRaw() >= 0) 1 else 0)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_CPU_CTRL, cpuControl)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_PMR, pmr)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_BPR, bpr)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_ENABLED, enabled.localBits)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_PENDING, pending.localBits)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_LEVELS, levels.localBits)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_ACTIVE, active.localBits)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_EDGE, edge.localBits)
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK_SIZE, stackSize)
            for (i in 0 until stackSize) {
                seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK + (i shl 2).toLong(), stackData[i])
            }
            val localPrio = priority.localArray()
            for (i in 0 until 32) {
                seg.set(ValueLayout.JAVA_BYTE, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_PRIORITY + i.toLong(), localPrio[i].toByte())
            }
            for (i in 0 until 16) {
                val mask = sgiSendersRow?.get(i) ?: 0
                seg.set(ValueLayout.JAVA_BYTE, cBase + GicV2ShadowLayout.CORE_OFF_SGI_SENDERS + i.toLong(), mask.toByte())
            }
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG, 0)
        }
        fun pullCoreFromNativeIfDirty(seg: MemorySegment, sgiSendersRow: IntArray?): Boolean {
            val cBase = GicV2ShadowLayout.OFF_CORES + core.toLong() * GicV2ShadowLayout.CORE_STRIDE
            if (seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG) == 0) return false
            cpuControl = seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_CPU_CTRL)
            pmr = seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_PMR)
            bpr = seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_BPR)
            enabled.restoreLocalBits(seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_ENABLED))
            pending.restoreLocalBits(seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_PENDING))
            levels.restoreLocalBits(seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_LEVELS))
            active.restoreLocalBits(seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_ACTIVE))
            edge.restoreLocalBits(seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_EDGE))
            val newSize = seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK_SIZE)
                .coerceIn(0, GicV2ShadowLayout.MAX_NATIVE_STACK)
            if (newSize > stackData.size) stackData = IntArray(maxOf(32, newSize))
            for (i in 0 until newSize) {
                stackData[i] = seg.get(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_ACTIVE_STACK + (i shl 2).toLong())
            }
            stackSize = newSize
            val localPrio = priority.localArray()
            for (i in 0 until 32) {
                localPrio[i] = seg.get(ValueLayout.JAVA_BYTE, cBase + GicV2ShadowLayout.CORE_OFF_LOCAL_PRIORITY + i.toLong()).toInt() and 0xFF
            }
            if (sgiSendersRow != null) {
                for (i in 0 until 16) {
                    sgiSendersRow[i] = seg.get(ValueLayout.JAVA_BYTE, cBase + GicV2ShadowLayout.CORE_OFF_SGI_SENDERS + i.toLong()).toInt() and 0xFF
                }
            }
            seg.set(GicV2ShadowLayout.LITTLE_INT, cBase + GicV2ShadowLayout.CORE_OFF_DIRTY_FLAG, 0)
            shared.invalidateCore(core)
            return true
        }
        fun shadowVersionCell(): LongArray = shared.shadowVersionCell
        fun bindNativeShadow(segment: MemorySegment?) {
            val prev = shared.boundShadow
            if (prev != null && prev !== segment) {
                pullFromNativeShadowIfDirty()
                prev.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 0)
            }
            shared.boundShadow = segment
            shared.lastPulledNativeSeq = 0L
            if (segment != null) {
                require(segment.byteSize() >= GicV2ShadowLayout.SIZE) { "GIC shadow segment too small" }
                segment.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 0L)
                segment.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_FAST_HITS, 0L)
                syncToNativeShadow()
            }
        }
        fun pullFromNativeShadowIfDirty(): Boolean {
            val seg = shared.boundShadow ?: return false
            val seq = seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ)
            if (seq == shared.lastPulledNativeSeq) return false
            return pullFromNativeShadowSlow(seg, seq)
        }
        private fun pullFromNativeShadowSlow(seg: MemorySegment, seq: Long): Boolean {
            if (clusterPullHook != null) {
                clusterPullHook.invoke()
                return true
            }
            val dirtySpi = seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK)
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_PEND_ACT) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_PENDING, shared.pendingBits, shared.pending)
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_ACTIVE, shared.activeBits, shared.active)
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_ENABLE) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_ENABLED, shared.enabledBits, shared.enabled)
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_PRIO) != 0) {
                for (i in 0 until 480) {
                    shared.priority[i] = seg.get(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + i.toLong()).toInt() and 0xFF
                }
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_TARGETS) != 0) {
                for (i in 0 until 480) {
                    val t = seg.get(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_TARGETS + i.toLong()).toInt() and 0xFF
                    if (shared.targets[i] != t) {
                        shared.targets[i] = t
                        shared.setTargetBits(i + 32, t)
                    }
                }
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_EDGE) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_EDGE, shared.edgeBits, shared.edge)
                shared.changed()
            }
            if (dirtySpi != 0) seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK, 0)
            val savedVer = shared.shadowVersionCell[0]
            pullCoreFromNativeIfDirty(seg, null)
            shared.shadowVersionCell[0] = savedVer
            shared.lastPulledNativeSeq = seq
            return true
        }
        fun syncToNativeShadow() {
            val seg = shared.boundShadow ?: return
            pullFromNativeShadowIfDirty()
            if (stackSize > GicV2ShadowLayout.MAX_NATIVE_STACK) {
                seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 0)
                return
            }
            shared.writeSharedToNative(seg, coreCount)
            writeCoreToNative(seg, null)
            seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 1)
        }
        override fun close() {
            bindNativeShadow(null)
        }
        private var cachedChoiceInt: Int = -1
        private val events = EventRing(); private var eventCount = 0L
        fun recordLine(tick: Long, line: Int, high: Boolean) { events.addLine(tick, line, high); eventCount++ }
        fun recordAck(tick: Long, line: Int) { events.addAck(tick, line); eventCount++ }
        fun recordAckSgi(tick: Long, line: Int, sender: Int, token: Int) { events.addAckSgi(tick, line, sender, token); eventCount++ }
        fun recordEoi(tick: Long, line: Int) { events.addEoi(tick, line); eventCount++ }
        fun recordEoiSgi(tick: Long, line: Int, token: Int) { events.addEoiSgi(tick, line, token); eventCount++ }
        fun recordSgiSingle(tick: Long, line: Int, targetFilter: Int, targetList: Int, deliveredCpu0: Boolean) {
            events.addSgiSingle(tick, line, targetFilter, targetList, deliveredCpu0); eventCount++
        }
        fun recordSgiCluster(tick: Long, line: Int, sender: Int, targetMask: Int) {
            events.addSgiCluster(tick, line, sender, targetMask); eventCount++
        }
        private fun isPending(i: Int) = pending[i] || (levels[i] && !edge[i])
        init { if(register) {
            context.ports.register(id, "$id.lines", InterruptSink::class.java, this); context.ports.register(id, "$id.cpu", CpuInterruptPort::class.java, this)
            context.ports.register(id, "$id.distributor", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int) = distributor(offset, size, false, 0)
                override fun write(offset: Long, size: Int, value: Long) { distributor(offset, size, true, value) }
            })
            context.ports.register(id, "$id.interface", MmioDevice::class.java, object : MmioDevice {
                override fun read(offset: Long, size: Int) = cpu(offset, size, false, 0)
                override fun write(offset: Long, size: Int, value: Long) { cpu(offset, size, true, value) }
            })
        } }
        override fun edgeState(irq: Int): EdgeInterruptState { pullFromNativeShadowIfDirty(); require(irq in 32..511);return EdgeInterruptState(edge[irq],levels[irq],pending[irq]) }
        override fun edgeStateBits(irq: Int): Int {
            pullFromNativeShadowIfDirty()
            require(irq in 32..511)
            val idx = irq - 32
            return (if (shared.edge[idx]) 1 else 0) or (if (shared.levels[idx]) 2 else 0) or (if (shared.pending[idx]) 4 else 0)
        }
        override fun lineEnabled(irq: Int): Boolean { pullFromNativeShadowIfDirty(); require(irq in 32..511); return enabled[irq] }
        override fun setLine(irq: Int, high: Boolean) {
            pullFromNativeShadowIfDirty()
            require(irq in 32..511)
            if (levels[irq] == high) return
            if (edge[irq] && high) pending[irq] = true
            levels[irq] = high
            publishLine(irq, high, context.clock.ticks())
        }
        override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) {
            pullFromNativeShadowIfDirty()
            require(irq in 32..511 && targetMask in 1 until (1 shl coreCount))
            require(control == 1 && enabled[irq] && edge[irq] && targets[irq] == targetMask)
            require(!levels[irq] && !pending[irq] && !active[irq])
            val tick = context.clock.ticks()
            latchDevice()
            pending[irq] = true
            publishLine(irq, true, tick)
            publishLine(irq, false, tick)
        }
        private fun publishLine(irq: Int, high: Boolean, tick: Long) {
            recordLine(tick, irq, high)
            context.events.publishIrqLine(id, tick, irq, high)
        }
        internal fun chooseIntRaw(): Int {
            if (!shared.choiceValid[core]) {
                cachedChoiceInt = scanChoiceInt()
                shared.choiceValid[core] = true
            }
            return cachedChoiceInt
        }
        fun chooseInt(): Int {
            pullFromNativeShadowIfDirty()
            return chooseIntRaw()
        }
        override fun hasPending(): Boolean = chooseInt() >= 0
        override fun chooseOrNegative(): Int = chooseInt()
        override fun choose(): Int? {
            val c = chooseInt()
            return if (c >= 0) c else null
        }
        private fun scanChoiceInt(): Int {
            if (control == 0 || (cpuControl and 1) == 0) return -1
            val mask = (255 shl (bpr + 1)) and 255
            var running = 256
            val sData = stackData
            val sLen = stackSize
            for (idx in 0 until sLen) {
                val p = priority[sData[idx] and 1023] and mask
                if (p < running) running = p
            }
            var chosen = -1
            var bestPriority = 256
            val coreTargetBits = shared.targetCoreBits[core]
            for (w in 0..7) {
                val en: Long
                val pend: Long
                val act: Long
                val tgt: Long
                if (w == 0) {
                    en = (enabled.localBits.toLong() and 0xffffffffL) or shared.enabledBits[0]
                    val pendRaw = (pending.localBits.toLong() and 0xffffffffL) or shared.pendingBits[0]
                    val lvlRaw = (levels.localBits.toLong() and 0xffffffffL) or shared.levelBits[0]
                    val edgeRaw = (edge.localBits.toLong() and 0xffffffffL) or shared.edgeBits[0]
                    pend = pendRaw or (lvlRaw and edgeRaw.inv())
                    act = (active.localBits.toLong() and 0xffffffffL) or shared.activeBits[0]
                    tgt = 0xffffffffL or coreTargetBits[0]
                } else {
                    en = shared.enabledBits[w]
                    pend = shared.pendingBits[w] or (shared.levelBits[w] and shared.edgeBits[w].inv())
                    act = shared.activeBits[w]
                    tgt = coreTargetBits[w]
                }
                var eligible = en and pend and act.inv() and tgt
                while (eligible != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(eligible)
                    eligible = eligible and (eligible - 1L)
                    val irq = (w shl 6) or bit
                    val prio = priority[irq]
                    if (prio < pmr && (prio and mask) < running && prio < bestPriority) {
                        bestPriority = prio
                        chosen = irq
                    }
                }
            }
            return chosen
        }
        private fun validate(offset: Long, size: Int, value: Long) { require(offset >= 0 && offset < 4096 && (size == 1 || size == 2 || size == 4) && offset % size == 0L && value >= 0 && value < (1L shl (8 * size))) }
        fun cpu(offset: Long, size: Int, write: Boolean, value: Long): Long {
            pullFromNativeShadowIfDirty()
            validate(offset, size, value); require(size == 4)
            when (offset.toInt()) {
                0,4,8 -> {
                    val mask = when (offset.toInt()) { 0 -> if (coreCount > 1) 5 else 1; 4 -> 255; else -> 7 }
                    if (write) { require(value and mask.toLong().inv() == 0L); when (offset.toInt()) { 0 -> cpuControl = value.toInt(); 4 -> pmr = value.toInt(); else -> bpr = value.toInt() } }
                    return when (offset.toInt()) { 0 -> cpuControl.toLong(); 4 -> pmr.toLong(); else -> bpr.toLong() }
                }
                0xc,0x18 -> {
                    require(!write); val selected = chooseInt(); if (selected < 0) return 1023
                    if (offset == 0xcL) { pending[selected] = false; active[selected] = true; stackPush(selected); recordAck(context.clock.ticks(), selected) }
                    return selected.toLong()
                }
                0x10 -> { require(write && stackSize > 0 && value == stackData[stackSize - 1].toLong()) { "GIC EOI does not match active stack" }; active[stackPop()] = false; recordEoi(context.clock.ticks(), value.toInt()); return 0 }
            }
            error("unmodeled GICC register")
        }
        fun distributor(offset: Long, size: Int, write: Boolean, value: Long): Long {
            pullFromNativeShadowIfDirty()
            validate(offset, size, value); val o = offset.toInt()
            if (o == 0 && size == 4) { if (write) { require(value and 1L.inv() == 0L); control = value.toInt() }; return control.toLong() }
            if (o == 4 && size == 4 && !write) return (15 or ((coreCount-1) shl 5)).toLong()
            if (o == 0xf00 && size == 4 && write && values.getValue("sgiProfile") == "single-core-experimental-v1") {
                val filter = ((value ushr 24) and 3).toInt()
                require(value and 0x03ff000fL.inv() == 0L && filter != 3) { "unsupported SGI filter/security/reserved bits" }
                val selected = filter == 2 || (filter == 0 && value and 0x10000 != 0L)
                val line = (value and 15).toInt()
                if(selected) pending[line] = true
                recordSgiSingle(context.clock.ticks(), line, filter, ((value ushr 16) and 255).toInt(), selected)
                return 0
            }
            if (o in 0x400..<0x600 || o in 0x800..<0xa00) {
                val isTargets = o >= 0x800
                val first = o - if (isTargets) 0x800 else 0x400
                require(first + size <= 512)
                val array = if (isTargets) targets else priority
                if (write) {
                    val fieldMask = if (isTargets) ((1 shl coreCount) - 1).toLong() else 255L
                    for (i in 0 until size) {
                        if (!isTargets || first + i >= 32) array[first + i] = ((value ushr (i * 8)) and fieldMask).toInt()
                    }
                }
                var out = 0L
                for (i in 0 until size) out = out or (array[first + i].toLong() shl (i * 8))
                return out
            }
            require(size == 4)
            if (o in 0xc00..<0xc80) {
                val first = (o - 0xc00) / 4 * 16
                var current = 0L
                for (bit in 0..15) if (edge[first + bit]) current = current or (2L shl (bit * 2))
                if (write) {
                    require(value and 0x55555555L == 0L) { "unsupported GIC configuration reserved bits" }
                    // An exact writeback changes no interrupt configuration or ownership.
                    if (value != current) {
                        var anyActive = false
                        for (i in first until first + 16) if (active[i]) { anyActive = true; break }
                        if (anyActive) {
                            // Only changed, disabled and quiet SPI fields qualify beside an unchanged active IRQ.
                            // Preflight the whole word before changing any field or active ownership.
                            for (field in 0..15) {
                                val irq = first + field
                                require(
                                    (value xor current) and (2L shl (field * 2)) == 0L ||
                                        (irq >= 32 && !active[irq] && !enabled[irq] && !pending[irq] && !levels[irq]),
                                ) { "GIC active configuration change unqualified" }
                            }
                        }
                        for (bit in 0..15) edge[first + bit] = value and (2L shl (bit * 2)) != 0L
                    }
                }
                return if (write) value else current
            }
            val start = o and 0xf80
            if ((start == 0x100 || start == 0x180 || start == 0x200 || start == 0x280 || start == 0x300 || start == 0x380) && o < start + 64) {
                val first = (o - start) shl 3
                val array = when (start) { 0x100,0x180 -> enabled; 0x200,0x280 -> pending; else -> active }
                if (write) {
                    require(start != 0x300 && start != 0x380)
                    val setVal = start == 0x100 || start == 0x200
                    var bits = value.toInt()
                    if (first == 0) bits = bits and -0x10000
                    while (bits != 0) {
                        val bit = Integer.numberOfTrailingZeros(bits)
                        bits = bits and (bits - 1)
                        array[first + bit] = setVal
                    }
                }
                if (first == 0) {
                    val word = when (start) {
                        0x100, 0x180 -> enabled.localBits
                        0x200, 0x280 -> pending.localBits or (levels.localBits and edge.localBits.inv())
                        else -> active.localBits
                    }
                    return word.toLong() and 0xffffffffL
                }
                val w = first ushr 6
                val sh = first and 32
                val word64 = when (start) {
                    0x100, 0x180 -> shared.enabledBits[w]
                    0x200, 0x280 -> shared.pendingBits[w] or (shared.levelBits[w] and shared.edgeBits[w].inv())
                    else -> shared.activeBits[w]
                }
                return (word64 ushr sh) and 0xffffffffL
            }
            error("unmodeled GICD register/SGI/security")
        }
        override fun state(): Map<String, Any?> {
            pullFromNativeShadowIfDirty()
            return super.state() + mapOf("control" to control, "cpuControl" to cpuControl, "priorityMask" to pmr, "binaryPoint" to bpr,
            "enabled" to (0..511).filter { enabled[it] }, "pending" to (0..511).filter(::isPending), "highLines" to (0..511).filter { levels[it] }, "active" to stackToList(), "eligible" to choose(),
            "priorities" to priority.toList(), "targets" to targets.toList(), "edgeLines" to (0..511).filter { edge[it] }, "events" to events.toList(), "eventCount" to eventCount, "eventLogTruncated" to (eventCount > events.size), "securityGroups" to false, "sgiGeneration" to (coreCount>1 || values.getValue("sgiProfile") != ""), "fiq" to false, "coreCount" to coreCount)
        }
        fun saveCoreState(): Map<String, Any> = linkedMapOf(
            "cpuControl" to cpuControl,
            "priorityMask" to pmr,
            "binaryPoint" to bpr,
            "localEnabled" to GicSnapshotCodec.encodeBits(enabled.copyLocal()),
            "localPending" to GicSnapshotCodec.encodeBits(pending.copyLocal()),
            "localLevels" to GicSnapshotCodec.encodeBits(levels.copyLocal()),
            "localActive" to GicSnapshotCodec.encodeBits(active.copyLocal()),
            "localEdge" to GicSnapshotCodec.encodeBits(edge.copyLocal()),
            "localPriorities" to GicSnapshotCodec.encodeUnsignedBytes(priority.copyLocal()),
            "localTargets" to GicSnapshotCodec.encodeUnsignedBytes(targets.copyLocal()),
            "activeStack" to stackToList(),
            "eventCount" to eventCount,
            "events" to events.toList(),
        )
        fun applyCoreState(parsed: GicSnapshotCodec.ParsedCore) {
            cpuControl = parsed.cpuControl
            pmr = parsed.priorityMask
            bpr = parsed.binaryPoint
            enabled.restoreLocal(parsed.localEnabled)
            pending.restoreLocal(parsed.localPending)
            levels.restoreLocal(parsed.localLevels)
            active.restoreLocal(parsed.localActive)
            edge.restoreLocal(parsed.localEdge)
            priority.restoreLocal(parsed.localPriorities)
            targets.restoreLocal(parsed.localTargets)
            val nextStack = parsed.activeStack
            if (nextStack.size > stackData.size) stackData = IntArray(maxOf(32, nextStack.size))
            for (i in nextStack.indices) stackData[i] = nextStack[i]
            stackSize = nextStack.size
            events.restore(parsed.events)
            eventCount = parsed.eventCount
            cachedChoiceInt = -1
        }
        override fun saveSnapshot(): Map<String, Any?> {
            pullFromNativeShadowIfDirty()
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "mode" to "single-core",
                "settings" to saveScalarValues(),
                "shared" to shared.saveState(),
                "core" to saveCoreState(),
            )
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(data.keys == setOf("schema", "componentId", "mode", "settings", "shared", "core")) {
                "Unexpected keys in single-core GIC snapshot"
            }
            require(GicSnapshotCodec.requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported GIC snapshot schema" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data["mode"] == "single-core") { "GIC topology mode mismatch" }
            val settingsMap = GicSnapshotCodec.requireMap(data["settings"], "settings")
            require(settingsMap.keys.containsAll(values.keys)) { "Missing scalar settings in GIC snapshot" }
            val singleCoreSgi = values["sgiProfile"] == "single-core-experimental-v1"
            val parsedShared = GicSnapshotCodec.parseShared(data["shared"], 1)
            val parsedCore = GicSnapshotCodec.parseCore(data["core"], 0, 1, singleCoreSgi)
            GicSnapshotCodec.validateSharedActiveAgainstStacks(parsedShared.active, listOf(parsedCore))
            restoreScalarValues(settingsMap, DESCRIPTOR)
            shared.applyState(parsedShared)
            applyCoreState(parsedCore)
            shared.changed()
            shared.bumpShadowVersion()
            shared.boundShadow?.let { seg ->
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 0L)
                shared.lastPulledNativeSeq = 0L
                syncToNativeShadow()
            }
        }
    }
    class ClusterGic(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("soc-gic",context,config),CoupledEdgeInterruptSink,CoreInterruptPort,StatefulComponent {
        override val coreCount=4
        private var current=0
        private val shared=Shared()
        private val pullHook: () -> Unit = { pullFromNativeShadowIfDirty() }
        private val views=Array(4) { Gic(context,config,shared,it,4,false,pullHook) }
        private val senders=Array(4) { IntArray(16) }
        fun shadowVersionCell(): LongArray = shared.shadowVersionCell
        fun bindNativeShadow(segment: MemorySegment?) {
            val prev = shared.boundShadow
            if (prev != null && prev !== segment) {
                pullFromNativeShadowIfDirty()
                prev.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 0)
            }
            shared.boundShadow = segment
            shared.lastPulledNativeSeq = 0L
            if (segment != null) {
                require(segment.byteSize() >= GicV2ShadowLayout.SIZE) { "GIC shadow segment too small" }
                segment.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 0L)
                segment.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_FAST_HITS, 0L)
                syncToNativeShadow()
            }
        }
        fun pullFromNativeShadowIfDirty(): Boolean {
            val seg = shared.boundShadow ?: return false
            val seq = seg.get(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ)
            if (seq == shared.lastPulledNativeSeq) return false
            return pullFromNativeShadowSlow(seg, seq)
        }
        private fun pullFromNativeShadowSlow(seg: MemorySegment, seq: Long): Boolean {
            val dirtySpi = seg.get(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK)
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_PEND_ACT) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_PENDING, shared.pendingBits, shared.pending)
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_ACTIVE, shared.activeBits, shared.active)
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_ENABLE) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_ENABLED, shared.enabledBits, shared.enabled)
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_PRIO) != 0) {
                for (i in 0 until 480) {
                    shared.priority[i] = seg.get(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_PRIORITY + i.toLong()).toInt() and 0xFF
                }
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_TARGETS) != 0) {
                for (i in 0 until 480) {
                    val t = seg.get(ValueLayout.JAVA_BYTE, GicV2ShadowLayout.OFF_SHARED_TARGETS + i.toLong()).toInt() and 0xFF
                    if (shared.targets[i] != t) {
                        shared.targets[i] = t
                        shared.setTargetBits(i + 32, t)
                    }
                }
                shared.changed()
            }
            if ((dirtySpi and GicV2ShadowLayout.DIRTY_SPI_EDGE) != 0) {
                shared.syncSharedBitsFromNative(seg, GicV2ShadowLayout.OFF_SHARED_EDGE, shared.edgeBits, shared.edge)
                shared.changed()
            }
            if (dirtySpi != 0) seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_DIRTY_SPI_MASK, 0)
            val savedVer = shared.shadowVersionCell[0]
            for (c in 0..3) {
                views[c].pullCoreFromNativeIfDirty(seg, senders[c])
            }
            shared.shadowVersionCell[0] = savedVer
            shared.lastPulledNativeSeq = seq
            return true
        }
        fun syncToNativeShadow() {
            val seg = shared.boundShadow ?: return
            pullFromNativeShadowIfDirty()
            for (c in 0..3) {
                if (views[c].currentStackSize() > GicV2ShadowLayout.MAX_NATIVE_STACK) {
                    seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 0)
                    return
                }
            }
            shared.writeSharedToNative(seg, 4)
            for (c in 0..3) {
                views[c].writeCoreToNative(seg, senders[c])
            }
            seg.set(GicV2ShadowLayout.LITTLE_INT, GicV2ShadowLayout.OFF_ACTIVE, 1)
        }
        override fun close() {
            bindNativeShadow(null)
        }
        init {
            require(config.getValue("sgiProfile")=="") { "four-core GIC conflicts with single-core SGIR profile" }
            context.ports.register(id,"$id.lines",InterruptSink::class.java,this)
            context.ports.register(id,"$id.cpu",CpuInterruptPort::class.java,this)
            context.ports.register(id,"$id.cores",CoreInterruptPort::class.java,this)
            context.ports.register(id,"$id.distributor",MmioDevice::class.java,object : MmioDevice {
                override fun read(offset: Long,size: Int)=distributor(offset,size,false,0)
                override fun write(offset: Long,size: Int,value: Long) { distributor(offset,size,true,value) }
            })
            context.ports.register(id,"$id.interface",MmioDevice::class.java,object : MmioDevice {
                override fun read(offset: Long,size: Int)=cpu(offset,size,false,0)
                override fun write(offset: Long,size: Int,value: Long) { cpu(offset,size,true,value) }
            })
        }
        override fun selectCore(core: Int) { require(core in 0..3); current=core }
        override fun choose()=views[current].choose()
        override fun hasPending(): Boolean = views[current].hasPending()
        override fun chooseOrNegative(): Int = views[current].chooseInt()
        fun hasPendingForCore(coreIndex: Int): Boolean = views[coreIndex].hasPending()
        override fun edgeState(irq: Int)=views[current].edgeState(irq)
        override fun edgeStateBits(irq: Int): Int = views[current].edgeStateBits(irq)
        override fun lineEnabled(irq: Int)=views[current].lineEnabled(irq)
        override fun setLine(irq: Int,high: Boolean)=views[current].setLine(irq,high)
        override fun pulseWithDevice(irq: Int, targetMask: Int, latchDevice: () -> Unit) =
            views[current].pulseWithDevice(irq, targetMask, latchDevice)
        private fun distributor(offset: Long,size: Int,write: Boolean,value: Long): Long {
            pullFromNativeShadowIfDirty()
            if(offset==0xf00L && write) {
                val filter=((value ushr 24) and 3).toInt()
                require(size==4 && value>=0 && value and 0x03ff000fL.inv()==0L && filter!=3) { "unsupported cluster SGI bits/width/filter" }
                val line=(value and 15).toInt()
                var targetMask = 0
                for (c in 0..3) {
                    if ((filter==0 && value and (1L shl (16+c))!=0L) || (filter==1 && c!=current) || (filter==2 && c==current)) {
                        senders[c][line]=senders[c][line] or (1 shl current)
                        views[c].pending[line]=true
                        targetMask = targetMask or (1 shl c)
                    }
                }
                shared.bumpShadowVersion()
                views[current].recordSgiCluster(context.clock.ticks(), line, current, targetMask)
                return 0
            }
            if (offset in 0xf10L..0xf1cL && (offset and 3L) == 0L) {
                require(size == 4 && value in 0L..0xffffffffL) { "unsupported GICD_CPENDSGIR width/value" }
                val baseSgi = ((offset - 0xf10L).toInt() / 4) * 4
                if (write) {
                    for (b in 0..3) {
                        val clearMask = ((value ushr (b * 8)) and 0xffL).toInt()
                        if (clearMask != 0) {
                            val sgi = baseSgi + b
                            senders[current][sgi] = senders[current][sgi] and clearMask.inv()
                            views[current].pending[sgi] = senders[current][sgi] != 0
                        }
                    }
                    shared.bumpShadowVersion()
                    return 0
                }
                var packed = 0L
                for (b in 0..3) {
                    packed = packed or ((senders[current][baseSgi + b].toLong() and 0xffL) shl (b * 8))
                }
                return packed
            }
            return views[current].distributor(offset,size,write,value)
        }
        private fun cpu(offset: Long,size: Int,write: Boolean,value: Long): Long {
            pullFromNativeShadowIfDirty()
            val view=views[current]
            if(offset==0xcL && size==4 && !write) {
                val line=view.chooseIntRaw()
                if(line>=0 && line<16) {
                    val sources=senders[current][line]; require(sources!=0) { "SGI pending without declared source" }
                    val source=Integer.numberOfTrailingZeros(sources); senders[current][line]=sources and (1 shl source).inv()
                    view.pending[line]=senders[current][line]!=0; view.active[line]=true
                    val token=line or (source shl 10); view.stackPush(token)
                    view.recordAckSgi(context.clock.ticks(), line, source, token); return token.toLong()
                }
            }
            if(offset==0x10L && write && value and 1023<16L) {
                require(size==4 && value>=0 && view.stackIsNotEmpty() && value==view.stackLast().toLong()) { "SGI EOI must match full source token" }
                view.stackPop(); view.active[(value and 1023).toInt()]=false
                view.recordEoiSgi(context.clock.ticks(), (value and 1023).toInt(), value.toInt()); return 0
            }
            return view.cpu(offset,size,write,value)
        }
        override fun state(): Map<String, Any?> {
            pullFromNativeShadowIfDirty()
            return super.state()+mapOf("coreCount" to 4,"currentCore" to current,"sgiGeneration" to true,"cores" to views.map { it.state() },"sgiSenders" to senders.map { it.toList() },
                "basis" to "Experimental four CPU topology: shared SPI, banked private IRQ/CPU interfaces, full SGI source tokens; no security/FIQ")
        }
        override fun saveSnapshot(): Map<String, Any?> {
            pullFromNativeShadowIfDirty()
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "mode" to "cluster",
                "settings" to saveScalarValues(),
                "currentCore" to current,
                "shared" to shared.saveState(),
                "cores" to views.map { view -> view.saveCoreState() },
                "sgiSenders" to senders.map { row -> row.toList() },
            )
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(data.keys == setOf("schema", "componentId", "mode", "settings", "currentCore", "shared", "cores", "sgiSenders")) {
                "Unexpected keys in cluster GIC snapshot"
            }
            require(GicSnapshotCodec.requireInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported GIC snapshot schema" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require(data["mode"] == "cluster") { "GIC topology mode mismatch" }
            val settingsMap = GicSnapshotCodec.requireMap(data["settings"], "settings")
            require(settingsMap.keys.containsAll(values.keys)) { "Missing scalar settings in GIC snapshot" }
            val nextCore = GicSnapshotCodec.requireInt(data["currentCore"], "currentCore", 0..3)
            val parsedShared = GicSnapshotCodec.parseShared(data["shared"], 4)
            val rawCores = data["cores"] as? List<*> ?: throw IllegalArgumentException("Missing cluster GIC cores list")
            require(rawCores.size == 4) { "Cluster GIC requires exactly 4 core entries" }
            val parsedCores = rawCores.mapIndexed { coreIndex, rawCore ->
                GicSnapshotCodec.parseCore(rawCore, coreIndex, 4, false)
            }
            GicSnapshotCodec.validateSharedActiveAgainstStacks(parsedShared.active, parsedCores)
            val rawSenders = data["sgiSenders"] as? List<*> ?: throw IllegalArgumentException("Missing cluster GIC sgiSenders")
            require(rawSenders.size == 4) { "Cluster GIC requires 4 sgiSenders rows" }
            val parsedSenders = Array(4) { coreIndex ->
                val row = rawSenders[coreIndex] as? List<*> ?: throw IllegalArgumentException("Invalid sgiSenders[$coreIndex]")
                require(row.size == 16) { "Cluster GIC requires 16 SGI sender masks per core" }
                IntArray(16) { sgiLine ->
                    val mask = GicSnapshotCodec.requireInt(row[sgiLine], "sgiSenders[$coreIndex][$sgiLine]", 0..15)
                    require((mask != 0) == parsedCores[coreIndex].localPending[sgiLine]) {
                        "Cluster GIC SGI pending bit disagrees with sgiSenders[$coreIndex][$sgiLine]"
                    }
                    mask
                }
            }
            restoreScalarValues(settingsMap, DESCRIPTOR)
            current = nextCore
            shared.applyState(parsedShared)
            views.forEachIndexed { coreIndex, view -> view.applyCoreState(parsedCores[coreIndex]) }
            parsedSenders.forEachIndexed { coreIndex, row -> row.copyInto(senders[coreIndex]) }
            shared.changed()
            shared.bumpShadowVersion()
            shared.boundShadow?.let { seg ->
                seg.set(GicV2ShadowLayout.LITTLE_LONG, GicV2ShadowLayout.OFF_NATIVE_SEQ, 0L)
                shared.lastPulledNativeSeq = 0L
                syncToNativeShadow()
            }
        }
    }
}
