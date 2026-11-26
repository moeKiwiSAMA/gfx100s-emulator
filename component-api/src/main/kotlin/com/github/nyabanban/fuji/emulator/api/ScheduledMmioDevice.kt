package com.github.nyabanban.fuji.emulator.api

/** Owner-only, observational deadline query. Null means no known timed event, never ready/ACK. */
interface ScheduledMmioDevice : MmioDevice {
    fun nextDeadline(): Long?
    override fun readMayMutateSchedule(): Boolean = true
}

/** Optional scheduling hint for MmioRouter fast-path skipping while preserving exact IRQ sync. */
interface TickAwareMmioDevice : ScheduledMmioDevice {
    /** Earliest tick at which beforeInstruction() may have work (0L = immediate, Long.MAX_VALUE = idle). */
    fun nextInterestedTick(): Long
    /** True if beforeInstruction() must run on every tick even when idle (e.g., Timer.lastTick / Dram.clock). */
    fun requiresEveryTick(): Boolean = false
}
