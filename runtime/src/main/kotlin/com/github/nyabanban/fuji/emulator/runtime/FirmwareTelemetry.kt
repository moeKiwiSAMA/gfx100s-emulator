package com.github.nyabanban.fuji.emulator.runtime

/**
 * Coarse firmware lifecycle milestones observed passively without modifying guest behavior.
 */
enum class FirmwareStage(val bit: Int, val label: String) {
    STAGE_1_BOOT_INIT(1 shl 0, "STAGE_1_BOOT_INIT"),
    STAGE_2_KERNEL_RTOS(1 shl 1, "STAGE_2_KERNEL_RTOS"),
    STAGE_3_STORAGE_IO(1 shl 2, "STAGE_3_STORAGE_IO"),
    STAGE_4_DISPLAY_INIT(1 shl 3, "STAGE_4_DISPLAY_INIT"),
    STAGE_5_FIRST_FRAME(1 shl 4, "STAGE_5_FIRST_FRAME"),
    STAGE_6_INTERACTIVE_UI(1 shl 5, "STAGE_6_INTERACTIVE_UI"),
    ;

    companion object {
        val MMIO_STAGE_MASK: Int =
            STAGE_2_KERNEL_RTOS.bit or STAGE_3_STORAGE_IO.bit or STAGE_4_DISPLAY_INIT.bit

        fun highestReached(mask: Int): FirmwareStage? {
            var highest: FirmwareStage? = null
            for (stage in entries) {
                if ((mask and stage.bit) != 0) highest = stage
            }
            return highest
        }

        fun formatReached(mask: Int): String {
            if (mask == 0) return "none"
            val sb = StringBuilder()
            for (stage in entries) {
                if ((mask and stage.bit) != 0) {
                    if (sb.isNotEmpty()) sb.append(", ")
                    sb.append(stage.label)
                }
            }
            return sb.toString()
        }
    }
}

/**
 * Lock-free volatile telemetry published by the emulation owner thread and read passively
 * by [FirmwareWatchdog] and [AsyncPerfProfiler]. All hot-path updates touch primitive
 * `@Volatile` fields or fixed-size primitive arrays with zero heap allocations and zero
 * lock contention.
 */
class FirmwareTelemetry {
    companion object {
        const val PHASE_IDLE: Int = 0
        const val PHASE_CPU_NATIVE: Int = 1
        const val PHASE_MMIO_UPCALL: Int = 2
        const val PHASE_WFI_JUMP: Int = 3
        const val PHASE_HOST_OTHER: Int = 4

        /** Power-of-two capacity for zero-allocation 4 KiB MMIO page hit aggregation. */
        const val MMIO_PAGE_TABLE_SIZE: Int = 256
        private const val MMIO_PAGE_TABLE_MASK: Int = MMIO_PAGE_TABLE_SIZE - 1

        fun phaseName(phase: Int): String = when (phase) {
            PHASE_IDLE -> "IDLE"
            PHASE_CPU_NATIVE -> "CPU_NATIVE"
            PHASE_MMIO_UPCALL -> "MMIO_UPCALL"
            PHASE_WFI_JUMP -> "WFI_JUMP"
            PHASE_HOST_OTHER -> "HOST_OTHER"
            else -> "UNKNOWN"
        }
    }

    /** Immutable snapshot of the 4 KiB MMIO page hit table taken without locking the owner thread. */
    class MmioPageTableSnapshot(
        val pageKeys: LongArray,
        val reads: LongArray,
        val writes: LongArray,
        val nanos: LongArray,
        val overflowHits: Long,
    )

    @Volatile var stageMask: Int = 0
        private set
    @Volatile var attempts: Long = 0L
    @Volatile var virtualTick: Long = 0L
    @Volatile var publishedFrames: Long = 0L
    @Volatile var firstFrameSequence: Long = 0L
    @Volatile var ownerHeartbeatNanos: Long = 0L
    @Volatile var lastButtonEvent: String = ""
    @Volatile var lastButtonEventNanos: Long = 0L
    @Volatile var buttonEventsCount: Long = 0L
    @Volatile var status: String = "idle"
    @Volatile var running: Boolean = false
    @Volatile var paused: Boolean = false
    @Volatile var startedAtNanos: Long = 0L
    @Volatile var smpHostThreadsEnabled: Boolean = false

    // Lock-free slice-level performance telemetry (single-writer: owner thread; read-only: profiler).
    // Disabled by default in discovery/strict modes for zero overhead and zero determinism impact.
    @Volatile var perfProfilingEnabled: Boolean = false
    @Volatile var currentPc: Long = 0L
    @Volatile var currentCore: Int = 0
    @Volatile var currentPhase: Int = PHASE_IDLE
    @Volatile var cpuNativeNanos: Long = 0L
    @Volatile var mmioUpcallNanos: Long = 0L
    @Volatile var wfiJumpNanos: Long = 0L
    @Volatile var wfiJumpCount: Long = 0L
    @Volatile var wfiSkippedTicks: Long = 0L
    @Volatile var sliceCount: Long = 0L
    @Volatile var sliceExecutedInstructions: Long = 0L
    @Volatile var mmioCalls: Long = 0L
    @Volatile var mmioReadCalls: Long = 0L
    @Volatile var mmioWriteCalls: Long = 0L
    @Volatile var mmioPageOverflowHits: Long = 0L

    // Fixed-size open-addressed 4 KiB MMIO page counter table (owner-thread writer only, zero allocation).
    private val mmioPageKeys = LongArray(MMIO_PAGE_TABLE_SIZE) { -1L }
    private val mmioPageReads = LongArray(MMIO_PAGE_TABLE_SIZE)
    private val mmioPageWrites = LongArray(MMIO_PAGE_TABLE_SIZE)
    private val mmioPageNanos = LongArray(MMIO_PAGE_TABLE_SIZE)

    /**
     * Records a single MMIO upcall in the owner thread with zero heap allocation.
     * Caller should guard with [perfProfilingEnabled].
     */
    fun recordMmioAccess(physicalAddress: Long, isWrite: Boolean, elapsedNanos: Long) {
        val dt = if (elapsedNanos > 0L) elapsedNanos else 0L
        mmioUpcallNanos += dt
        mmioCalls += 1L
        if (isWrite) {
            mmioWriteCalls += 1L
        } else {
            mmioReadCalls += 1L
        }
        val pageIndex = physicalAddress ushr 12
        var slot = ((pageIndex * 0x9E3779B97F4A7C15UL.toLong()) ushr 56).toInt() and MMIO_PAGE_TABLE_MASK
        var probes = 0
        while (probes < MMIO_PAGE_TABLE_SIZE) {
            val existing = mmioPageKeys[slot]
            if (existing == pageIndex) {
                if (isWrite) mmioPageWrites[slot] += 1L else mmioPageReads[slot] += 1L
                mmioPageNanos[slot] += dt
                return
            }
            if (existing < 0L) {
                mmioPageKeys[slot] = pageIndex
                if (isWrite) mmioPageWrites[slot] = 1L else mmioPageReads[slot] = 1L
                mmioPageNanos[slot] = dt
                return
            }
            slot = (slot + 1) and MMIO_PAGE_TABLE_MASK
            probes++
        }
        mmioPageOverflowHits += 1L
    }

    /** Captures a consistent-enough lock-free copy of the 4 KiB MMIO page hit table for profiler delta math. */
    fun snapshotMmioPages(): MmioPageTableSnapshot {
        val keys = mmioPageKeys.copyOf()
        val reads = mmioPageReads.copyOf()
        val writes = mmioPageWrites.copyOf()
        val nanos = mmioPageNanos.copyOf()
        return MmioPageTableSnapshot(keys, reads, writes, nanos, mmioPageOverflowHits)
    }

    fun markStage(stage: FirmwareStage) {
        val current = stageMask
        if ((current and stage.bit) == 0) {
            stageMask = current or stage.bit
        }
    }

    fun markStageMask(bits: Int) {
        val current = stageMask
        if ((current and bits) != bits) {
            stageMask = current or bits
        }
    }

    fun resetForColdReset() {
        stageMask = 0
        attempts = 0L
        virtualTick = 0L
        publishedFrames = 0L
        firstFrameSequence = 0L
        currentPc = 0L
        currentCore = 0
    }

    fun recordButtonEvent(description: String, nowNanos: Long = System.nanoTime()) {
        lastButtonEvent = description
        lastButtonEventNanos = nowNanos
        buttonEventsCount += 1L
    }

    fun markSessionStarted(nowNanos: Long = System.nanoTime()) {
        startedAtNanos = nowNanos
        ownerHeartbeatNanos = nowNanos
        running = true
        paused = false
        status = "running"
    }

    fun markSessionStopped(finalStatus: String, nowNanos: Long = System.nanoTime()) {
        ownerHeartbeatNanos = nowNanos
        running = false
        paused = false
        status = finalStatus
    }
}
