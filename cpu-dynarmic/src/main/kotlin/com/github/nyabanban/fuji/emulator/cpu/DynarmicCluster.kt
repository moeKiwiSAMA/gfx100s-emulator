package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * Four-core serialized scheduler over a native [Dynarmic] instance.
 *
 * Dynarmic keeps all four A32 JIT cores live in C++ simultaneously,
 * so switching cores requires zero context save/restore and zero JIT cache or TLB flush.
 * Preserves the exact 1024-attempt quantum, [ResetSchedule] release thresholds, and WFI/WFE/SEV semantics.
 */
class DynarmicCluster(
    private val dyn: Dynarmic,
    private val interrupts: CoreInterruptPort,
    private val reset: ResetSchedule? = null,
) {
    var current: Int = 0
        private set
    private var used: Long = 0L
    private val attempts = LongArray(4)
    private val firstTicks = LongArray(4) { -1L }
    private val waiting = arrayOfNulls<String>(4)
    private val event = BooleanArray(4)
    private var eventCount: Long = 0L
    private var nativeEventMaskDirty: Boolean = true
    private var lastSyncedEventMask: Int = -1
    private var lastBatchIrqPendingMask: Int = 0
    private val fastHasPendingForCore: MethodHandle? = try {
        MethodHandles.publicLookup()
            .findVirtual(interrupts.javaClass, "hasPendingForCore", MethodType.methodType(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .bindTo(interrupts)
    } catch (_: Throwable) {
        null
    }

    init {
        require(interrupts.coreCount == 4) { "DynarmicCluster requires 4-core interrupt controller" }
        require(dyn.coreCount() == 4) { "DynarmicCluster requires 4-core Dynarmic engine" }
        if (reset != null) {
            repeat(4) { core ->
                if (reset.held(core)) waiting[core] = "reset"
            }
        }
        interrupts.selectCore(0)
        dyn.selectCore(0)
    }

    fun remaining(): Long = 1024L - used

    fun needsSwitch(): Boolean = used >= 1024L || waiting[current] != null

    /**
     * Returns whether the native library supports multi-core round-robin batch stepping inside C++
     * (`fuji_dyn_run_cluster_fast`), and no held reset core can release within [maxTotalTicks].
     */
    fun canRunNativeBatch(maxTotalTicks: Long): Boolean {
        if (!dyn.supportsClusterFast() || maxTotalTicks <= 0L) return false
        if (waiting[current] != null) return false
        val totalEnd = attempts.sum() + maxTotalTicks
        for (c in 0..3) {
            if (waiting[c] == "reset") {
                val threshold = checkNotNull(reset).threshold(c)
                if (threshold <= totalEnd) return false
            }
        }
        return true
    }

    /**
     * Executes up to [maxTotalTicks] across runnable cores in round-robin 1024-tick quanta inside C++,
     * skipping WFI/WFE/reset cores without returning to Java between quanta.
     * Synchronizes all per-core attempt counters, WFI/WFE waiting states, and active core selection on return.
     */
    fun runNativeBatch(maxTotalTicks: Long, startVtick: Long): Dynarmic.ClusterResult {
        syncEventsToNative()
        var runnableMask = 0
        var irqPendingMask = 0
        var wfeWaitingMask = 0
        var wfiWaitingMask = 0
        for (c in 0..3) {
            val hasIrq = coreHasPendingIrq(c)
            if (hasIrq) irqPendingMask = irqPendingMask or (1 shl c)
            val w = waiting[c]
            val runnable = when (w) {
                null -> true
                "wfi" -> hasIrq
                "wfe" -> hasIrq || event[c]
                else -> false
            }
            if (runnable) {
                if (w == "wfe") {
                    event[c] = false
                    nativeEventMaskDirty = true
                }
                waiting[c] = null
                runnableMask = runnableMask or (1 shl c)
            } else if (w == "wfe") {
                wfeWaitingMask = wfeWaitingMask or (1 shl c)
            } else if (w == "wfi") {
                wfiWaitingMask = wfiWaitingMask or (1 shl c)
            }
        }
        syncEventsToNative()
        lastBatchIrqPendingMask = irqPendingMask
        val res = dyn.runClusterFast(
            maxTotalTicks,
            startVtick,
            runnableMask,
            irqPendingMask,
            current,
            used.toInt(),
            1024,
            wfeWaitingMask,
            wfiWaitingMask,
        )
        if (lastSyncedEventMask != 0 || res.wfeEnteredMask != 0 || res.sevMask != 0) {
            syncEventsFromNative()
        }
        var runningTotal = attempts.sum()
        for (c in 0..3) {
            val delta = res.coreExecuted[c]
            if (delta > 0L) {
                if (firstTicks[c] < 0L) firstTicks[c] = runningTotal + 1L
                attempts[c] += delta
                runningTotal += delta
                if (waiting[c] == "wfe" || waiting[c] == "wfi") waiting[c] = null
            }
            if ((res.wfiWokenMask and (1 shl c)) != 0 && (res.wfiEnteredMask and (1 shl c)) == 0) {
                if (waiting[c] == "wfi") waiting[c] = null
            }
            if ((res.wfiEnteredMask and (1 shl c)) != 0) {
                if (!coreHasPendingIrq(c)) waiting[c] = "wfi"
                eventCount++
            }
        }
        if (res.sevMask != 0) {
            if (!dyn.supportsLiveClusterIrq()) {
                // V1 bridge exits immediately on STOP_SEV without inline WFE wakeup:
                // set all cores' event registers and wake any core in wfeEnteredMask.
                event.fill(true)
                for (c in 0..3) {
                    if ((res.wfeEnteredMask and (1 shl c)) != 0) {
                        event[c] = false
                        waiting[c] = null
                        eventCount++
                    }
                }
                nativeEventMaskDirty = true
                syncEventsToNative()
            } else {
                // V2 bridge updates native wfe_event_mask and wakes WFE cores inline; syncEventsFromNative()
                // above already holds the exact post-batch ARMv7-A event register bits. Still apply any core
                // that entered WFE *after* an inline SEV in the same batch.
                for (c in 0..3) {
                    if ((res.wfeEnteredMask and (1 shl c)) != 0) {
                        if (!event[c] && !coreHasPendingIrq(c)) waiting[c] = "wfe"
                        eventCount++
                    } else if (waiting[c] == "wfe" && (res.wfiEnteredMask and (1 shl c)) == 0) {
                        waiting[c] = null
                    }
                }
            }
            eventCount++
        } else {
            for (c in 0..3) {
                if ((res.wfeEnteredMask and (1 shl c)) != 0) {
                    if (!event[c] && !coreHasPendingIrq(c)) waiting[c] = "wfe"
                    eventCount++
                }
            }
        }
        current = res.activeCore
        used = res.activeUsed.toLong().coerceIn(0L, 1024L)
        interrupts.selectCore(current)
        dyn.selectCore(current)
        return res
    }

    fun account(count: Long) {
        if (count <= 0L) return
        if (firstTicks[current] < 0L) firstTicks[current] = attempts.sum() + 1L
        // Dynarmic exits at basic-block boundaries and may slightly overshoot 1024;
        // clamp to 1024 so needsSwitch() always yields the quantum to other runnable cores.
        used = if (used + count >= 1024L) 1024L else used + count
        attempts[current] += count
    }

    /**
     * Returns how many ticks the active core can safely run continuously without any other core
     * becoming runnable.
     */
    fun fastBudget(maxAllowed: Long): Long {
        val rem = remaining()
        if (maxAllowed <= rem || waiting[current] != null) return minOf(maxAllowed, rem)
        var limit = maxAllowed
        val total = attempts.sum()
        val qEnd = total + rem
        for (other in 0..3) {
            if (other == current) continue
            when (waiting[other]) {
                null -> return rem
                "wfe" -> {
                    if (event[other]) return rem
                    if (coreHasPendingIrq(other)) return rem
                }
                "wfi" -> {
                    if (coreHasPendingIrq(other)) return rem
                }
                "reset" -> {
                    val threshold = checkNotNull(reset).threshold(other)
                    if (threshold <= qEnd) return rem
                    val extraQuanta = (threshold - qEnd + 1023L) / 1024L
                    val maxStep = rem + extraQuanta * 1024L
                    if (maxStep < limit) limit = maxStep
                }
                else -> return rem
            }
        }
        return limit
    }

    fun anyOtherCoreWoken(): Boolean {
        for (other in 0..3) {
            if (other == current) continue
            val w = waiting[other]
            if (w == "wfi" || w == "wfe") {
                if (coreHasPendingIrq(other)) return true
            }
        }
        return false
    }

    /**
     * Updates the tracked per-core IRQ bit for [activeCore] mid-batch (e.g., after an MMIO read/write
     * clears or raises an IRQ on [activeCore]) so subsequent [anyOtherCoreIrqGained] checks from another
     * core in the same batch do not mask a newly raised IRQ with a stale batch-entry bit.
     */
    fun updateBatchCoreIrq(activeCore: Int, hasPending: Boolean) {
        val bit = 1 shl activeCore
        lastBatchIrqPendingMask = if (hasPending) {
            lastBatchIrqPendingMask or bit
        } else {
            lastBatchIrqPendingMask and bit.inv()
        }
    }

    /**
     * Returns true if any core other than [activeCore] gained a GIC pending IRQ
     * since the active native cluster batch started (e.g., SGI or cross-core SPI raised mid-batch).
     */
    fun anyOtherCoreIrqGained(activeCore: Int): Boolean {
        for (other in 0..3) {
            if (other == activeCore) continue
            val hadIrq = (lastBatchIrqPendingMask and (1 shl other)) != 0
            if (!hadIrq && coreHasPendingIrq(other)) return true
        }
        return false
    }

    /** Returns true if at least one core other than [current] is immediately runnable. */
    fun hasAnotherRunnableCore(): Boolean {
        for (other in 0..3) {
            if (other == current) continue
            val w = waiting[other]
            if (w == null) return true
            if (w == "wfi" && coreHasPendingIrq(other)) return true
            if (w == "wfe" && (event[other] || coreHasPendingIrq(other))) return true
        }
        return false
    }

    private fun coreHasPendingIrq(core: Int): Boolean {
        val fast = fastHasPendingForCore
        if (fast != null) {
            return fast.invokeExact(core) as Boolean
        }
        interrupts.selectCore(core)
        return try {
            interrupts.hasPending()
        } finally {
            interrupts.selectCore(current)
        }
    }

    fun syncEventsToNative() {
        if (!nativeEventMaskDirty) return
        var mask = 0
        for (c in 0..3) if (event[c]) mask = mask or (1 shl c)
        if (mask != lastSyncedEventMask) {
            dyn.setWfeEventMask(mask)
            lastSyncedEventMask = mask
        }
        nativeEventMaskDirty = false
    }

    fun syncEventsFromNative() {
        val mask = dyn.getWfeEventMask()
        for (c in 0..3) event[c] = (mask and (1 shl c)) != 0
        lastSyncedEventMask = mask
        nativeEventMaskDirty = false
    }

    fun onWfi() {
        if (!interrupts.hasPending()) {
            waiting[current] = "wfi"
        }
        eventCount++
    }

    fun onWfe() {
        syncEventsFromNative()
        if (event[current]) {
            event[current] = false
            nativeEventMaskDirty = true
            syncEventsToNative()
        } else if (!interrupts.hasPending()) {
            waiting[current] = "wfe"
        }
        eventCount++
    }

    fun onSev() {
        event.fill(true)
        nativeEventMaskDirty = true
        syncEventsToNative()
        eventCount++
    }

    fun switch(): Boolean {
        if (lastSyncedEventMask != 0 || waiting[0] == "wfe" || waiting[1] == "wfe" || waiting[2] == "wfe" || waiting[3] == "wfe") {
            syncEventsFromNative()
        }
        for (delta in 1..4) {
            val next = (current + delta) % 4
            if (waiting[next] == "reset") {
                val tick = attempts.sum()
                val schedule = checkNotNull(reset)
                if (!schedule.release(next, tick)) continue
                waiting[next] = null
                eventCount++
            }
            interrupts.selectCore(next)
            val wake = interrupts.hasPending()
            if (waiting[next] != null && !wake && !(waiting[next] == "wfe" && event[next])) continue
            if (waiting[next] == "wfe") {
                event[next] = false
                nativeEventMaskDirty = true
                syncEventsToNative()
            }
            waiting[next] = null
            used = 0L
            current = next
            dyn.selectCore(next)
            return true
        }
        interrupts.selectCore(current)
        dyn.selectCore(current)
        return false
    }

    fun state(): Map<String, Any> {
        val cores = (0..3).map { i ->
            val regs = (0..12).associate { r -> "r$r" to dyn.getReg(i, r) } + mapOf(
                "sp" to dyn.getReg(i, 13),
                "lr" to dyn.getReg(i, 14),
                "pc" to dyn.getReg(i, 15),
                "cpsr" to dyn.getCpsr(i),
            )
            val cp15 = mapOf(
                "sctlr" to dyn.getCp15(i, 0, 1, 0, 0),
                "ttbr0" to dyn.getCp15(i, 0, 2, 0, 0),
                "ttbr1" to dyn.getCp15(i, 0, 2, 0, 1),
                "ttbcr" to dyn.getCp15(i, 0, 2, 0, 2),
                "dacr" to dyn.getCp15(i, 0, 3, 0, 0),
                "vbar" to dyn.getCp15(i, 0, 12, 0, 0),
            )
            mapOf(
                "core" to i,
                "attempts" to attempts[i],
                "waiting" to waiting[i],
                "registers" to regs,
                "cp15" to cp15,
            )
        }
        return mapOf(
            "profile" to PROFILE,
            "backend" to "dynarmic-a32-jit",
            "smpHostThreads" to dyn.isSmpHostThreadsEnabled(),
            "quantum" to 1024,
            "currentCore" to current,
            "cores" to cores,
            "eventCount" to eventCount,
            "resetSchedule" to ((reset?.state() ?: mapOf(
                "profile" to "all-at-reset-experimental-v1",
                "configuredReleaseTicks" to listOf(0, 0, 0, 0),
            )) + ("firstInstructionTicks" to firstTicks.map { if (it < 0L) null else it })),
        )
    }

    fun saveSnapshot(): ArmClusterSnapshot {
        syncEventsFromNative()
        val cores = (0..3).map { core ->
            val architectural = ArmCoreSnapshot.capture(dyn, core)
            linkedMapOf<String, Any?>(
                "core" to core,
                "attempts" to attempts[core],
                "firstInstructionTick" to firstTicks[core].takeIf { it >= 0L },
                "waiting" to waiting[core],
                "event" to event[core],
                "architectural" to architectural,
            )
        }
        val metadata = linkedMapOf<String, Any?>(
            "schema" to 1,
            "profile" to PROFILE,
            "quantum" to 1024,
            "currentCore" to current,
            "usedInQuantum" to used,
            "cores" to cores,
            "resetSchedule" to reset?.saveSnapshot(),
            "eventCount" to eventCount,
            "events" to emptyList<Map<String, Any>>(),
        )
        return ArmClusterSnapshot(metadata, emptyList())
    }

    fun restoreSnapshot(metadata: Map<*, *>) {
        require((metadata["schema"] as? Number)?.toInt() == 1) { "unsupported DynarmicCluster snapshot schema" }
        require(metadata["profile"] == PROFILE && (metadata["quantum"] as? Number)?.toInt() == 1024) {
            "DynarmicCluster snapshot profile or quantum differs"
        }
        val targetCurrent = (metadata["currentCore"] as? Number)?.toInt() ?: error("missing currentCore")
        val targetUsed = (metadata["usedInQuantum"] as? Number)?.toLong() ?: error("missing usedInQuantum")
        require(targetCurrent in 0..3 && targetUsed in 0..1024L) { "invalid currentCore or usedInQuantum" }
        val coreRows = metadata["cores"] as? List<*> ?: error("missing cores")
        require(coreRows.size == 4) { "expected 4 core snapshots" }
        val resetRaw = metadata["resetSchedule"]
        if (reset == null) require(resetRaw == null) { "snapshot includes resetSchedule for unscheduled cluster" }
        else reset.restoreSnapshot(resetRaw as? Map<*, *> ?: error("missing resetSchedule snapshot"))
        val nextEventCount = (metadata["eventCount"] as? Number)?.toLong() ?: 0L
        require(nextEventCount >= 0L) { "negative cluster eventCount" }

        val nextAttempts = LongArray(4)
        val nextFirstTicks = LongArray(4) { -1L }
        val nextWaiting = arrayOfNulls<String>(4)
        val nextEvent = BooleanArray(4)
        for (core in 0..3) {
            val row = coreRows[core] as? Map<*, *> ?: error("invalid core $core snapshot")
            require((row["core"] as? Number)?.toInt() == core) { "core index mismatch" }
            nextAttempts[core] = (row["attempts"] as Number).toLong().also { require(it >= 0L) }
            nextFirstTicks[core] = (row["firstInstructionTick"] as? Number)?.toLong()?.also { require(it >= 1L) } ?: -1L
            val wait = row["waiting"] as? String
            require(wait == null || wait in setOf("reset", "wfi", "wfe")) { "invalid waiting state: $wait" }
            nextWaiting[core] = wait
            nextEvent[core] = row["event"] as? Boolean ?: error("missing event flag for core $core")
            val architectural = row["architectural"] as? Map<*, *> ?: error("missing architectural state for core $core")
            ArmCoreSnapshot.restore(dyn, core, architectural)
        }
        current = targetCurrent
        used = targetUsed
        nextAttempts.copyInto(attempts)
        nextFirstTicks.copyInto(firstTicks)
        for (i in 0..3) {
            waiting[i] = nextWaiting[i]
            event[i] = nextEvent[i]
        }
        eventCount = nextEventCount
        nativeEventMaskDirty = true
        lastSyncedEventMask = -1
        syncEventsToNative()
        interrupts.selectCore(current)
        dyn.selectCore(current)
        dyn.flushBlocks()
        dyn.flushTlb()
    }

    companion object {
        const val PROFILE = "four-context-functional-experimental-v1"
    }
}
