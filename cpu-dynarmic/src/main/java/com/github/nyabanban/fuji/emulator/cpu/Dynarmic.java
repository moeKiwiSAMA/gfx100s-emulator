package com.github.nyabanban.fuji.emulator.cpu;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import static java.lang.foreign.ValueLayout.*;

/**
 * Java 25 FFM binding to the native Dynarmic ARMv7-A + VFPv4 + NEON -> x86-64/AVX2 Fastmem JIT bridge
 * (`libfuji_dynarmic.so` / `fuji_dynarmic.dll`, ABI v1, `tools/native/dynarmic-bridge/fuji_dynarmic.h`).
 *
 * Leaf downcalls use {@link Linker.Option#critical(boolean)} (`allowHeapAccess = false`) to eliminate
 * JVM thread-state transitions on hot register/CP15/IRQ/TLB queries. Guest RAM regions are allocated
 * off-heap via {@link Arena} and mapped directly into Dynarmic via `fuji_dyn_map_ram` so both host DMA
 * and JIT code share zero-copy flat host pointers.
 */
public final class Dynarmic implements ArmCpuBackend {
    public static final int EXPECTED_ABI_VERSION = 1;
    public static final int MAX_CORES = 4;

    public static final int PERM_READ = 1;
    public static final int PERM_WRITE = 2;
    public static final int PERM_EXEC = 4;
    public static final int PERM_ALL = PERM_READ | PERM_WRITE | PERM_EXEC;

    public static final int MODE_USR = 0x10;
    public static final int MODE_FIQ = 0x11;
    public static final int MODE_IRQ = 0x12;
    public static final int MODE_SVC = 0x13;
    public static final int MODE_ABT = 0x17;
    public static final int MODE_UND = 0x1B;
    public static final int MODE_SYS = 0x1F;

    public static final int BANKED_R8 = 8;
    public static final int BANKED_R9 = 9;
    public static final int BANKED_R10 = 10;
    public static final int BANKED_R11 = 11;
    public static final int BANKED_R12 = 12;
    public static final int BANKED_SP = 13;
    public static final int BANKED_LR = 14;
    public static final int BANKED_SPSR = 16;

    public static final int EXT_FPSCR = 64;
    public static final int EXT_FPEXC = 65;

    public static final int STOP_QUANTUM = 0;
    public static final int STOP_WFI = 1;
    public static final int STOP_WFE = 2;
    public static final int STOP_SEV = 3;
    public static final int STOP_MMIO_HALT = 4;
    public static final int STOP_SVC = 5;
    public static final int STOP_IRQ = 6;
    public static final int STOP_FAULT_DABT = 7;
    public static final int STOP_FAULT_PABT = 8;
    public static final int STOP_FAULT_UNDEF = 9;
    public static final int STOP_HALT = 10;
    public static final int STOP_TRACEPOINT = 11;
    public static final int STOP_ALL_WAITING = 12;

    public static final int AUTO_EXCP_SVC = 1 << 0;
    public static final int AUTO_EXCP_UNDEF = 1 << 1;
    public static final int AUTO_EXCP_PABT = 1 << 2;
    public static final int AUTO_EXCP_DABT = 1 << 3;
    public static final int AUTO_EXCP_IRQ = 1 << 4;
    public static final int AUTO_EXCP_ALL = AUTO_EXCP_SVC | AUTO_EXCP_UNDEF | AUTO_EXCP_PABT | AUTO_EXCP_DABT;

    /** Fixed 64-byte FujiDynRunResult struct offsets. */
    private static final long RES_SIZE = 64L;
    private static final long OFF_EXECUTED_TICKS = 0x00L;
    private static final long OFF_VIRTUAL_TICK = 0x08L;
    private static final long OFF_STOP_PC = 0x10L;
    private static final long OFF_STOP_CPSR = 0x14L;
    private static final long OFF_STOP_REASON = 0x18L;
    private static final long OFF_STOP_INSN = 0x1CL;
    private static final long OFF_FAULT_ADDR = 0x20L;
    private static final long OFF_FAULT_STATUS = 0x24L;
    private static final long OFF_SVC_IMM = 0x28L;
    private static final long OFF_TRANSLATED_FETCHES = 0x30L;
    private static final long OFF_FAST_MMIO_HITS = 0x38L;

    /** Fixed native struct sizes for soc-timer shadow (264 B), MMIO stats (32 B), live state (48 B), cluster (40 B / 120 B). */
    private static final long TIMER_SHADOW_SIZE = 264L;
    private static final long TIMER_CHANNELS_OFFSET = 8L;
    private static final long TIMER_CHANNEL_STRIDE = 32L;
    public static final long TIMER_IRQ_SHADOW_SIZE = 128L;
    private static final long MMIO_STATS_SIZE = 32L;
    private static final long LIVE_STATE_SIZE = 48L;
    private static final long LIVE_OFF_VTICK = 0x00L;
    private static final long LIVE_OFF_PC = 0x08L;
    private static final long LIVE_OFF_CPSR = 0x0CL;
    private static final long LIVE_OFF_CORE = 0x10L;
    private static final long LIVE_OFF_VALID_MASK = 0x14L;
    private static final long LIVE_OFF_CPSR_BY_CORE = 0x18L;
    private static final long CLUSTER_REQ_SIZE = 40L;
    private static final long CLUSTER_RES_SIZE = 120L;
    public static final long GIC_SHADOW_SIZE = 2464L;
    private static final int RO_BATCH_MAX = 64;

    private static final ValueLayout.OfInt LITTLE_INT = JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LITTLE_SHORT = JAVA_SHORT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LITTLE_LONG = JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final Object POSIX_SIGNAL_LOCK = new Object();
    private static final int LINUX_SIGBUS = 7;
    private static final int LINUX_SIGSEGV = 11;
    private static final long POSIX_SIGACTION_BUF_BYTES = 256L;

    /** Physical MMIO callback bridge. */
    public interface MmioHandler {
        long read(long physicalAddress, int size);
        void write(long physicalAddress, int size, long value);
    }

    /** Zero-allocation owner-thread view of the last {@link #runFast} call. */
    public static final class RunResult {
        public long executedTicks;
        public long virtualTick;
        public long stopPc;
        public long stopCpsr;
        public int stopReason;
        public long stopInsn;
        public long faultAddr;
        public long faultStatus;
        public int svcImm;
        public long translatedFetches;
        public long fastMmioHits;
    }

    /** Zero-allocation view of cumulative native C++ MMIO fastpath vs upcall counts. */
    public static final class MmioStats {
        public long timerFastHits;
        public long roMmioFastHits;
        public long upcallReads;
        public long upcallWrites;
    }

    /** Zero-allocation result of {@link #runClusterFast}. */
    public static final class ClusterResult {
        public long totalExecuted;
        public long virtualTick;
        public final long[] coreExecuted = new long[MAX_CORES];
        public int activeCore;
        public int activeUsed;
        public int stopReason;
        public long stopPc;
        public long stopCpsr;
        public long stopInsn;
        public long faultAddr;
        public long faultStatus;
        public int svcImm;
        public int wfiEnteredMask;
        public int wfeEnteredMask;
        public int sevMask;
        public long translatedFetches;
        public long fastMmioHits;
        public int switches;
        public int wfiWokenMask;
    }

    private static final class MappedRegion {
        final long base;
        final long size;
        final long end;
        final MemorySegment segment;

        MappedRegion(long base, long size, MemorySegment segment) {
            this.base = base;
            this.size = size;
            this.end = base + size;
            this.segment = segment;
        }
    }

    private final Arena arena = Arena.ofConfined();
    private Arena ramArena = Arena.ofConfined();
    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup symbols;
    private final MemorySegment engine;
    private final int coreCount;
    private final long cbarBase;
    private final MemorySegment runResultBuf = arena.allocate(RES_SIZE, 8);
    private final MemorySegment scratchInt = arena.allocate(64, 8);
    private final MemorySegment highDmaScratch = arena.allocate(64 * 1024, 8);
    private final MemorySegment timerShadowBuf = arena.allocate(TIMER_SHADOW_SIZE, 8);
    private final MemorySegment timerIrqShadowBuf = arena.allocate(TIMER_IRQ_SHADOW_SIZE, 8);
    private final MemorySegment mmioStatsBuf = arena.allocate(MMIO_STATS_SIZE, 8);
    private final MemorySegment liveStateBuf = arena.allocate(LIVE_STATE_SIZE, 8);
    private final MemorySegment roBatchAddrBuf = arena.allocate(RO_BATCH_MAX * 4L, 4);
    private final MemorySegment roBatchValBuf = arena.allocate(RO_BATCH_MAX * 4L, 4);
    private final MemorySegment clusterReqBuf = arena.allocate(CLUSTER_REQ_SIZE, 8);
    private final MemorySegment clusterResBuf = arena.allocate(CLUSTER_RES_SIZE, 8);
    private final MemorySegment gicShadowBuf = arena.allocate(GIC_SHADOW_SIZE, 8);
    private final ArrayList<MemorySegment> roRangeSegments = new ArrayList<>();
    private final RunResult lastResult = new RunResult();
    private final ClusterResult lastClusterResult = new ClusterResult();

    // Non-critical handles (may allocate, mutate engine structure, or invoke Java upcalls)
    private final MethodHandle destroy, resetCore;
    private final MethodHandle mapRam, unmapRam, mapMmio, unmapMmio, setMmioCallbacks;
    private final MethodHandle memRead, memWrite, runFast, setTracepoints;

    // Critical leaf handles (never call back into Java)
    private final MethodHandle invalidateRange, clearCache, flushTlb, translateFn;
    private final MethodHandle haltRun, setIrqPending;
    private final MethodHandle deliverIrq, deliverFiq, deliverSvc, deliverUndef, deliverPabt, deliverDabt;
    private final MethodHandle setAutoExceptions, setWfeEventMask, getWfeEventMask, setCntfrq, clearExclusive;
    private final MethodHandle getReg, setReg, getRegs, setRegs;
    private final MethodHandle getCpsr, setCpsr, getSpsr, setSpsr;
    private final MethodHandle getBankedReg, setBankedReg;
    private final MethodHandle getCp15, setCp15, getCp15_64, setCp15_64;
    private final MethodHandle getExtReg, setExtReg, getDreg, setDreg;
    private final MethodHandle getFpscr, setFpscr, getFpexc, setFpexc;
    private final MethodHandle getCurrentPc, getCurrentVtick;

    // Optional C++ native MMIO fastpath leaf handles (present in updated ABI v1 libraries)
    private final MethodHandle setTimerShadowFn, setTimerShadowPtrFn, clearTimerShadowFn;
    private final MethodHandle setRoMmioWordFn, setRoMmioBatchFn, removeRoMmioWordFn, clearRoMmioRangeFn, clearRoMmioFn;
    private final MethodHandle addRoMmioRangeFn, clearRoMmioRangesFn;
    private final MethodHandle setLiveStateBufFn, runClusterFastFn;
    private final MethodHandle setGicShadowPtrFn, clearGicShadowFn;
    private final MethodHandle setTimerIrqShadowPtrFn, clearTimerIrqShadowFn;
    private final MethodHandle getFastmemBaseFn, fastmemIsDualViewFn, getRamHostPtrFn;
    private final MethodHandle setVtickDividerFn;
    private final MethodHandle setSmpHostThreadsFn, getSmpHostThreadsFn;
    private final MethodHandle registerMmioMirrorFn, unregisterMmioMirrorFn, clearMmioMirrorsFn;
    private final MethodHandle getMmioStatsFn, resetMmioStatsFn;
    private final MethodHandle mapRam64Fn, unmapRam64Fn, memRead64Fn, memWrite64Fn, translate64Fn;
    private final boolean nativeMmioFastpathsSupported;
    private final boolean liveStateSupported;
    private final boolean roRangesSupported;
    private final boolean clusterFastSupported;
    private final boolean liveClusterIrqSupported;
    private final boolean gicShadowSupported;
    private final boolean timerIrqShadowSupported;
    private final boolean vtickDividerSupported;
    private final boolean smpHostThreadsSupported;
    private final boolean mmioMirrorsSupported;
    private final boolean highPhysRamSupported;
    private final long fastmemBase;
    private final boolean fastmemDualView;
    private final int nativeAbiVersion;
    private boolean sharedTimerPtrBound;
    private long sharedTimerBoundBase = -1L;

    private final boolean[] cachedIrqPending = new boolean[MAX_CORES];
    private final boolean[] cachedIrqValid = new boolean[MAX_CORES];

    private MappedRegion[] mappedRegions = new MappedRegion[0];
    private MappedRegion lastMapped;
    private final ArrayList<long[]> mappedHighRamRanges = new ArrayList<>();
    private final ArrayList<long[]> mappedMmioRanges = new ArrayList<>();
    private final ArrayList<String> fastmemFallbackRegions = new ArrayList<>();
    private final ArrayList<String> osZeroRamFallbacks = new ArrayList<>();
    private MmioHandler mmioHandler;
    private Throwable callbackFailure;
    private int activeCore = 0;
    private boolean running;
    private boolean closed;

    private static final ConcurrentHashMap<Path, SymbolLookup> LOADED_LIBRARIES = new ConcurrentHashMap<>();

    public Dynarmic(Path library) {
        this(library, MAX_CORES, 0xFFFF_0000L);
    }

    public Dynarmic(Path library, int numCores, long cbarBase) {
        if (ADDRESS.byteSize() != 8) throw new IllegalStateException("64-bit JVM required");
        if (numCores < 1 || numCores > MAX_CORES) throw new IllegalArgumentException("numCores out of 1..4: " + numCores);
        this.coreCount = numCores;
        this.cbarBase = cbarBase;
        try {
            Path absLib = library.toAbsolutePath().normalize();
            symbols = LOADED_LIBRARIES.computeIfAbsent(absLib, p -> SymbolLookup.libraryLookup(p, Arena.global()));
            var abiFn = bindLeaf("fuji_dyn_abi_version", FunctionDescriptor.of(JAVA_INT));
            int abi = (int) abiFn.invokeExact();
            if (abi != EXPECTED_ABI_VERSION) {
                throw new IllegalStateException("Unsupported Dynarmic bridge ABI " + abi + "; expected " + EXPECTED_ABI_VERSION);
            }
            this.nativeAbiVersion = abi;
            var createFn = bind("fuji_dyn_create", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT));
            getFastmemBaseFn = bindLeafOptional("fuji_dyn_get_fastmem_base", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            fastmemIsDualViewFn = bindLeafOptional("fuji_dyn_fastmem_is_dual_view", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getRamHostPtrFn = bindLeafOptional("fuji_dyn_get_ram_host_ptr", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            // DYN-V3 libraries export fuji_dyn_get_fastmem_base and install an async-signal-safe, silent
            // HotSpot-chaining POSIX SIGSEGV wrapper inside fuji_dyn_create; preserve POSIX signals only for V1/V2.
            if (getFastmemBaseFn != null) {
                engine = (MemorySegment) createFn.invokeExact(numCores, (int) cbarBase);
            } else {
                engine = invokeCreatePreservingPosixSignals(createFn, numCores, (int) cbarBase);
            }
            if (engine == null || engine.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("fuji_dyn_create returned NULL");
            }
            this.fastmemBase = getFastmemBaseFn != null ? (long) getFastmemBaseFn.invokeExact(engine) : 0L;
            this.fastmemDualView = this.fastmemBase != 0L && fastmemIsDualViewFn != null
                && ((int) fastmemIsDualViewFn.invokeExact(engine)) != 0;

            destroy = bind("fuji_dyn_destroy", FunctionDescriptor.ofVoid(ADDRESS));
            resetCore = bind("fuji_dyn_reset_core", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            mapRam = bind("fuji_dyn_map_ram", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT));
            unmapRam = bind("fuji_dyn_unmap_ram", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            mapMmio = bind("fuji_dyn_map_mmio", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            unmapMmio = bind("fuji_dyn_unmap_mmio", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            setMmioCallbacks = bind("fuji_dyn_set_mmio_callbacks", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            memRead = bind("fuji_dyn_mem_read", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            memWrite = bind("fuji_dyn_mem_write", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            runFast = bind("fuji_dyn_run_fast", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
            setTracepoints = bind("fuji_dyn_set_tracepoints", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));

            invalidateRange = bindLeaf("fuji_dyn_invalidate_range", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            clearCache = bindLeaf("fuji_dyn_clear_cache", FunctionDescriptor.ofVoid(ADDRESS));
            flushTlb = bindLeaf("fuji_dyn_flush_tlb", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            translateFn = bindLeaf("fuji_dyn_translate", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
            haltRun = bindLeaf("fuji_dyn_halt_run", FunctionDescriptor.ofVoid(ADDRESS));
            setIrqPending = bindLeaf("fuji_dyn_set_irq_pending", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            deliverIrq = bindLeaf("fuji_dyn_deliver_irq", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            deliverFiq = bindLeaf("fuji_dyn_deliver_fiq", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            deliverSvc = bindLeaf("fuji_dyn_deliver_svc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            deliverUndef = bindLeaf("fuji_dyn_deliver_undef", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            deliverPabt = bindLeaf("fuji_dyn_deliver_pabt", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            deliverDabt = bindLeaf("fuji_dyn_deliver_dabt", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            setAutoExceptions = bindLeaf("fuji_dyn_set_auto_exceptions", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            setWfeEventMask = bindLeaf("fuji_dyn_set_wfe_event_mask", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            getWfeEventMask = bindLeaf("fuji_dyn_get_wfe_event_mask", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            setCntfrq = bindLeaf("fuji_dyn_set_cntfrq", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            clearExclusive = bindLeaf("fuji_dyn_clear_exclusive", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));

            getReg = bindLeaf("fuji_dyn_get_reg", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            setReg = bindLeaf("fuji_dyn_set_reg", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            getRegs = bindLeaf("fuji_dyn_get_regs", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
            setRegs = bindLeaf("fuji_dyn_set_regs", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
            getCpsr = bindLeaf("fuji_dyn_get_cpsr", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setCpsr = bindLeaf("fuji_dyn_set_cpsr", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            getSpsr = bindLeaf("fuji_dyn_get_spsr", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setSpsr = bindLeaf("fuji_dyn_set_spsr", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            getBankedReg = bindLeaf("fuji_dyn_get_banked_reg", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            setBankedReg = bindLeaf("fuji_dyn_set_banked_reg", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));

            getCp15 = bindLeaf("fuji_dyn_get_cp15", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
            setCp15 = bindLeaf("fuji_dyn_set_cp15", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
            getCp15_64 = bindLeaf("fuji_dyn_get_cp15_64", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            setCp15_64 = bindLeaf("fuji_dyn_set_cp15_64", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));

            getExtReg = bindLeaf("fuji_dyn_get_ext_reg", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            setExtReg = bindLeaf("fuji_dyn_set_ext_reg", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
            getDreg = bindLeaf("fuji_dyn_get_dreg", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));
            setDreg = bindLeaf("fuji_dyn_set_dreg", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG));
            getFpscr = bindLeaf("fuji_dyn_get_fpscr", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setFpscr = bindLeaf("fuji_dyn_set_fpscr", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            getFpexc = bindLeaf("fuji_dyn_get_fpexc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setFpexc = bindLeaf("fuji_dyn_set_fpexc", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));

            getCurrentPc = bindLeaf("fuji_dyn_get_current_pc", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            getCurrentVtick = bindLeaf("fuji_dyn_get_current_vtick", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

            setTimerShadowFn = bindLeafOptional("fuji_dyn_set_timer_shadow", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
            setTimerShadowPtrFn = bindLeafOptional("fuji_dyn_set_timer_shadow_ptr", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
            clearTimerShadowFn = bindLeafOptional("fuji_dyn_clear_timer_shadow", FunctionDescriptor.ofVoid(ADDRESS));
            setRoMmioWordFn = bindLeafOptional("fuji_dyn_set_ro_mmio_word", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
            setRoMmioBatchFn = bindLeafOptional("fuji_dyn_set_ro_mmio_batch", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            removeRoMmioWordFn = bindLeafOptional("fuji_dyn_remove_ro_mmio_word", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            clearRoMmioRangeFn = bindLeafOptional("fuji_dyn_clear_ro_mmio_range", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT));
            clearRoMmioFn = bindLeafOptional("fuji_dyn_clear_ro_mmio", FunctionDescriptor.ofVoid(ADDRESS));
            addRoMmioRangeFn = bindLeafOptional("fuji_dyn_add_ro_mmio_range", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            clearRoMmioRangesFn = bindLeafOptional("fuji_dyn_clear_ro_mmio_ranges", FunctionDescriptor.ofVoid(ADDRESS));
            setLiveStateBufFn = bindLeafOptional("fuji_dyn_set_live_state_buf", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            runClusterFastFn = bindOptional("fuji_dyn_run_cluster_fast", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            setGicShadowPtrFn = bindLeafOptional("fuji_dyn_set_gic_shadow_ptr", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            clearGicShadowFn = bindLeafOptional("fuji_dyn_clear_gic_shadow", FunctionDescriptor.ofVoid(ADDRESS));
            setTimerIrqShadowPtrFn = bindLeafOptional("fuji_dyn_set_timer_irq_shadow_ptr", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            clearTimerIrqShadowFn = bindLeafOptional("fuji_dyn_clear_timer_irq_shadow", FunctionDescriptor.ofVoid(ADDRESS));
            setVtickDividerFn = bindLeafOptional("fuji_dyn_set_vtick_divider", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            setSmpHostThreadsFn = bindOptional("fuji_dyn_set_smp_host_threads", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
            getSmpHostThreadsFn = bindLeafOptional("fuji_dyn_get_smp_host_threads", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            registerMmioMirrorFn = bindLeafOptional("fuji_dyn_register_mmio_mirror", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            unregisterMmioMirrorFn = bindLeafOptional("fuji_dyn_unregister_mmio_mirror", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            clearMmioMirrorsFn = bindLeafOptional("fuji_dyn_clear_mmio_mirrors", FunctionDescriptor.ofVoid(ADDRESS));
            getMmioStatsFn = bindLeafOptional("fuji_dyn_get_mmio_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
            resetMmioStatsFn = bindLeafOptional("fuji_dyn_reset_mmio_stats", FunctionDescriptor.ofVoid(ADDRESS));
            mapRam64Fn = bindOptional("fuji_dyn_map_ram64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            unmapRam64Fn = bindOptional("fuji_dyn_unmap_ram64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
            memRead64Fn = bindOptional("fuji_dyn_mem_read64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
            memWrite64Fn = bindOptional("fuji_dyn_mem_write64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
            translate64Fn = bindLeafOptional("fuji_dyn_translate64", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
            nativeMmioFastpathsSupported = setTimerShadowFn != null && clearTimerShadowFn != null
                && setRoMmioWordFn != null && removeRoMmioWordFn != null
                && clearRoMmioRangeFn != null && clearRoMmioFn != null
                && getMmioStatsFn != null && resetMmioStatsFn != null;
            liveStateSupported = setLiveStateBufFn != null;
            roRangesSupported = addRoMmioRangeFn != null && clearRoMmioRangesFn != null;
            clusterFastSupported = runClusterFastFn != null;
            gicShadowSupported = setGicShadowPtrFn != null && clearGicShadowFn != null;
            timerIrqShadowSupported = setTimerIrqShadowPtrFn != null && clearTimerIrqShadowFn != null && setTimerShadowPtrFn != null && gicShadowSupported;
            vtickDividerSupported = setVtickDividerFn != null;
            smpHostThreadsSupported = setSmpHostThreadsFn != null && getSmpHostThreadsFn != null;
            mmioMirrorsSupported = registerMmioMirrorFn != null && unregisterMmioMirrorFn != null && clearMmioMirrorsFn != null;
            highPhysRamSupported = mapRam64Fn != null && unmapRam64Fn != null
                && memRead64Fn != null && memWrite64Fn != null && translate64Fn != null;
            // V1 fuji_dyn_run_cluster_fast re-applied stale batch-entry irq_pending_mask across quanta;
            // V2 (exporting fuji_dyn_set_gic_shadow_ptr) reads live per-core irq_pending each quantum.
            liveClusterIrqSupported = clusterFastSupported && gicShadowSupported;
            if (liveStateSupported) {
                setLiveStateBufFn.invokeExact(engine, liveStateBuf);
            }

            // Install unified MMIO FFM upcalls once
            var readDesc = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT);
            var writeDesc = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG);
            var readMh = MethodHandles.lookup().findVirtual(Dynarmic.class, "onMmioRead", readDesc.toMethodType()).bindTo(this);
            var writeMh = MethodHandles.lookup().findVirtual(Dynarmic.class, "onMmioWrite", writeDesc.toMethodType()).bindTo(this);
            var readStub = linker.upcallStub(readMh, readDesc, arena);
            var writeStub = linker.upcallStub(writeMh, writeDesc, arena);
            setMmioCallbacks.invokeExact(engine, readStub, writeStub, MemorySegment.NULL);
        } catch (Throwable e) {
            ramArena.close();
            arena.close();
            throw failure(e);
        }
    }

    private MethodHandle bind(String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(
            symbols.find(name).orElseThrow(() -> new IllegalStateException("missing Dynarmic symbol: " + name)),
            descriptor
        );
    }

    private MethodHandle bindLeaf(String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(
            symbols.find(name).orElseThrow(() -> new IllegalStateException("missing Dynarmic symbol: " + name)),
            descriptor,
            Linker.Option.critical(false)
        );
    }

    private MethodHandle bindOptional(String name, FunctionDescriptor descriptor) {
        return symbols.find(name)
            .map(sym -> linker.downcallHandle(sym, descriptor))
            .orElse(null);
    }

    private MethodHandle bindLeafOptional(String name, FunctionDescriptor descriptor) {
        return symbols.find(name)
            .map(sym -> linker.downcallHandle(sym, descriptor, Linker.Option.critical(false)))
            .orElse(null);
    }

    private static RuntimeException failure(Throwable e) {
        return e instanceof RuntimeException r ? r : new IllegalStateException(e);
    }

    private static void check(int result) {
        if (result != 0) throw new IllegalStateException("Dynarmic error " + result);
    }

    @Override
    public CpuEngineKind getEngineKind() {
        return CpuEngineKind.DYNARMIC;
    }

    public int coreCount() {
        return coreCount;
    }

    public long cbarBase() {
        return cbarBase;
    }

    public int activeCore() {
        if (running && liveStateSupported) {
            return liveStateBuf.get(LITTLE_INT, LIVE_OFF_CORE);
        }
        return activeCore;
    }

    public boolean supportsLiveState() {
        return liveStateSupported;
    }

    public int abiVersion() {
        return nativeAbiVersion;
    }

    public boolean isFastmemActive() {
        return fastmemBase != 0L;
    }

    public boolean isFastmemDualView() {
        return fastmemDualView;
    }

    public long fastmemBase() {
        return fastmemBase;
    }

    /** Returns human-readable descriptions of any runtime fallbacks that occurred on this Dynarmic instance. */
    public java.util.List<String> fallbackDiagnostics() {
        ArrayList<String> out = new ArrayList<>();
        if (fastmemBase == 0L) {
            out.add("Hardware Fastmem unavailable on this host/bridge; fell back to software page_table");
        } else if (!fastmemDualView) {
            out.add("Hardware Fastmem single-view mode active; host DMA routes through fuji_dyn_mem_{read,write}");
        }
        if (!fastmemFallbackRegions.isEmpty()) {
            int shown = Math.min(fastmemFallbackRegions.size(), 6);
            String sample = String.join(", ", fastmemFallbackRegions.subList(0, shown));
            if (fastmemFallbackRegions.size() > shown) {
                sample += " (+ " + (fastmemFallbackRegions.size() - shown) + " more)";
            }
            out.add("Fastmem fell back to OS-backed software page_table for " + fastmemFallbackRegions.size() + " region(s): " + sample);
        }
        if (!osZeroRamFallbacks.isEmpty()) {
            int shown = Math.min(osZeroRamFallbacks.size(), 4);
            String sample = String.join(", ", osZeroRamFallbacks.subList(0, shown));
            if (osZeroRamFallbacks.size() > shown) {
                sample += " (+ " + (osZeroRamFallbacks.size() - shown) + " more)";
            }
            out.add("Demand-paged OS zero RAM allocator fell back to eager JVM Arena.allocate for " + osZeroRamFallbacks.size() + " region(s): " + sample);
        }
        return java.util.Collections.unmodifiableList(out);
    }

    public boolean supportsTimerIrqShadow() {
        return timerIrqShadowSupported;
    }

    public boolean supportsVtickDivider() {
        return vtickDividerSupported;
    }

    public boolean supportsSmpHostThreads() {
        return smpHostThreadsSupported;
    }

    public boolean setSmpHostThreads(boolean enabled) {
        if (!smpHostThreadsSupported) return false;
        try {
            setSmpHostThreadsFn.invokeExact(engine, enabled ? 1 : 0);
            return ((int) getSmpHostThreadsFn.invokeExact(engine)) != 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean isSmpHostThreadsEnabled() {
        if (!smpHostThreadsSupported) return false;
        try {
            return ((int) getSmpHostThreadsFn.invokeExact(engine)) != 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setVtickDivider(int divider) {
        if (!vtickDividerSupported) return;
        int d = Math.max(1, divider);
        try {
            setVtickDividerFn.invokeExact(engine, d);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public MemorySegment timerShadowSegment() {
        return timerShadowBuf;
    }

    public MemorySegment timerIrqShadowSegment() {
        return timerIrqShadowBuf;
    }

    public void bindTimerIrqShadow() {
        if (!timerIrqShadowSupported) return;
        try {
            setTimerIrqShadowPtrFn.invokeExact(engine, timerShadowBuf, timerIrqShadowBuf);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearTimerIrqShadow() {
        if (!timerIrqShadowSupported) return;
        timerIrqShadowBuf.fill((byte) 0);
        try {
            clearTimerIrqShadowFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void selectCore(int core) {
        if (core < 0 || core >= coreCount) throw new IllegalArgumentException("Invalid core " + core);
        this.activeCore = core;
    }

    public boolean isRunning() {
        return running;
    }

    public void setMmioHandler(MmioHandler handler) {
        this.mmioHandler = handler;
    }

    public void setObserver(ArmCpuObserver observer) {
        if (observer == null) {
            this.mmioHandler = null;
        } else {
            this.mmioHandler = new MmioHandler() {
                @Override public long read(long physicalAddress, int size) { return observer.read(physicalAddress, size); }
                @Override public void write(long physicalAddress, int size, long value) { observer.write(physicalAddress, size, value); }
            };
        }
    }

    @SuppressWarnings("unused")
    private long onMmioRead(MemorySegment user, int physAddr, int size) {
        if (callbackFailure != null || mmioHandler == null) return 0L;
        try {
            long addr = Integer.toUnsignedLong(physAddr);
            if (size == 8) {
                long lo = mmioHandler.read(addr, 4) & 0xffff_ffffL;
                if (callbackFailure != null) return lo;
                long hi = mmioHandler.read((addr + 4L) & 0xffff_ffffL, 4) & 0xffff_ffffL;
                return lo | (hi << 32);
            }
            return mmioHandler.read(addr, size);
        } catch (Throwable e) {
            failCallback(e);
            return 0L;
        }
    }

    @SuppressWarnings("unused")
    private void onMmioWrite(MemorySegment user, int physAddr, int size, long value) {
        if (callbackFailure != null || mmioHandler == null) return;
        try {
            long addr = Integer.toUnsignedLong(physAddr);
            if (size == 8) {
                mmioHandler.write(addr, 4, value & 0xffff_ffffL);
                if (callbackFailure != null) return;
                mmioHandler.write((addr + 4L) & 0xffff_ffffL, 4, (value >>> 32) & 0xffff_ffffL);
                return;
            }
            mmioHandler.write(addr, size, value);
        } catch (Throwable e) {
            failCallback(e);
        }
    }

    private void failCallback(Throwable e) {
        if (callbackFailure == null) callbackFailure = e;
        try {
            haltRun.invokeExact(engine);
        } catch (Throwable ignored) {
            // Preserve original failure
        }
    }

    private boolean overlapsRegion(long address, long size) {
        long end = address + size;
        for (MappedRegion r : mappedRegions) {
            if (address < r.end && end > r.base) return true;
        }
        return false;
    }

    private void addRegion(long address, long size, MemorySegment segment) {
        MappedRegion next = new MappedRegion(address, size, segment);
        MappedRegion[] updated = new MappedRegion[mappedRegions.length + 1];
        int pos = 0;
        while (pos < mappedRegions.length && mappedRegions[pos].base < address) {
            updated[pos] = mappedRegions[pos];
            pos++;
        }
        updated[pos] = next;
        System.arraycopy(mappedRegions, pos, updated, pos + 1, mappedRegions.length - pos);
        mappedRegions = updated;
        lastMapped = next;
    }

    private void removeRegionRange(long address, long size) {
        if (size <= 0 || mappedRegions.length == 0) return;
        long end = address + size;
        ArrayList<MappedRegion> kept = new ArrayList<>(mappedRegions.length);
        for (MappedRegion r : mappedRegions) {
            if (end <= r.base || address >= r.end) {
                kept.add(r);
            } else {
                if (r.base < address) {
                    long head = address - r.base;
                    kept.add(new MappedRegion(r.base, head, r.segment.asSlice(0, head)));
                }
                if (end < r.end) {
                    long tailOffset = end - r.base;
                    long tailSize = r.end - end;
                    kept.add(new MappedRegion(end, tailSize, r.segment.asSlice(tailOffset, tailSize)));
                }
            }
        }
        mappedRegions = kept.toArray(new MappedRegion[0]);
        lastMapped = null;
    }

    private MappedRegion findRegionContaining(long address) {
        MappedRegion cached = lastMapped;
        if (cached != null && address >= cached.base && address < cached.end) return cached;
        MappedRegion[] regions = mappedRegions;
        int low = 0;
        int high = regions.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            MappedRegion r = regions[mid];
            if (address < r.base) high = mid - 1;
            else if (address >= r.end) low = mid + 1;
            else {
                lastMapped = r;
                return r;
            }
        }
        return null;
    }

    private MappedRegion findRegion(long address, long size) {
        MappedRegion r = findRegionContaining(address);
        if (r != null && size <= r.end - address && !r.segment.equals(MemorySegment.NULL)) return r;
        return null;
    }

    private MappedRegion[] mappedHighRegions = new MappedRegion[0];
    private MappedRegion lastMappedHigh;

    private void addHighRegion(long base, long size, MemorySegment segment) {
        MappedRegion[] prev = mappedHighRegions;
        MappedRegion[] next = new MappedRegion[prev.length + 1];
        int insert = 0;
        while (insert < prev.length && prev[insert].base < base) {
            next[insert] = prev[insert];
            insert++;
        }
        next[insert] = new MappedRegion(base, size, segment);
        System.arraycopy(prev, insert, next, insert + 1, prev.length - insert);
        mappedHighRegions = next;
        lastMappedHigh = next[insert];
    }

    private MappedRegion findHighRegion(long address, long size) {
        MappedRegion cached = lastMappedHigh;
        if (cached != null && address >= cached.base && size <= cached.end - address) {
            return cached.segment.equals(MemorySegment.NULL) ? null : cached;
        }
        MappedRegion[] regions = mappedHighRegions;
        int low = 0;
        int high = regions.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            MappedRegion r = regions[mid];
            if (address < r.base) high = mid - 1;
            else if (address >= r.end) low = mid + 1;
            else {
                lastMappedHigh = r;
                return (size <= r.end - address && !r.segment.equals(MemorySegment.NULL)) ? r : null;
            }
        }
        return null;
    }

    /**
     * Returns a bounded off-heap {@link MemorySegment} covering {@code [address, address + size)}
     * when the range lies inside a single host-RW-backed 32-bit or 40-bit guest RAM region
     * (dual-view fastmem or OS-backed RAM arena), or {@code null} when slow-path access is required.
     */
    public MemorySegment sliceDirectRam(long address, long size) {
        if (size <= 0L || address < 0L) {
            return null;
        }
        if (address >= 0x1_0000_0000L) {
            if (address >= (1L << 40) || size > (1L << 40) - address) return null;
            MappedRegion hr = findHighRegion(address, size);
            return hr != null ? hr.segment.asSlice(address - hr.base, size) : null;
        }
        if (size > 0x1_0000_0000L - address) {
            return null;
        }
        MappedRegion r = findRegion(address, size);
        return r != null ? r.segment.asSlice(address - r.base, size) : null;
    }

    private static final long DEMAND_PAGED_THRESHOLD_BYTES = 65536L;

    /**
     * Demand-paged zero-initialized OS virtual memory allocator (`mmap(MAP_ANONYMOUS)` on POSIX,
     * `VirtualAlloc(MEM_RESERVE|MEM_COMMIT)` on Windows) so large guest LPDDR4 pools consume zero
     * physical RSS until touched while preserving deterministic all-zero read semantics.
     */
    private static final class OsZeroRamAllocator {
        private static final boolean IS_WINDOWS;
        private static final boolean IS_MACOS;
        private static final boolean IS_LINUX;
        private static final long THP_MIN_BYTES = 2L * 1024L * 1024L;
        private static final int LINUX_MADV_HUGEPAGE = 14;
        private static final MethodHandle MMAP_FN;
        private static final MethodHandle MUNMAP_FN;
        private static final MethodHandle MADVISE_FN;
        private static final MethodHandle VIRTUAL_ALLOC_FN;
        private static final MethodHandle VIRTUAL_FREE_FN;

        static {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            IS_WINDOWS = os.contains("win");
            IS_MACOS = os.contains("mac") || os.contains("darwin");
            IS_LINUX = os.contains("linux");
            Linker nativeLinker = Linker.nativeLinker();
            MethodHandle mmap = null;
            MethodHandle munmap = null;
            MethodHandle madvise = null;
            MethodHandle valloc = null;
            MethodHandle vfree = null;
            try {
                if (IS_WINDOWS) {
                    SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                    valloc = kernel32.find("VirtualAlloc")
                        .map(s -> nativeLinker.downcallHandle(s, FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT)))
                        .orElse(null);
                    vfree = kernel32.find("VirtualFree")
                        .map(s -> nativeLinker.downcallHandle(s, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)))
                        .orElse(null);
                } else {
                    SymbolLookup stdlib = nativeLinker.defaultLookup();
                    mmap = stdlib.find("mmap")
                        .map(s -> nativeLinker.downcallHandle(s, FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)))
                        .orElse(null);
                    munmap = stdlib.find("munmap")
                        .map(s -> nativeLinker.downcallHandle(s, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)))
                        .orElse(null);
                    if (IS_LINUX) {
                        madvise = stdlib.find("madvise")
                            .map(s -> nativeLinker.downcallHandle(s, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)))
                            .orElse(null);
                    }
                }
            } catch (Throwable ignored) {
                mmap = null;
                munmap = null;
                madvise = null;
                valloc = null;
                vfree = null;
            }
            MMAP_FN = mmap;
            MUNMAP_FN = munmap;
            MADVISE_FN = madvise;
            VIRTUAL_ALLOC_FN = valloc;
            VIRTUAL_FREE_FN = vfree;
        }

        static MemorySegment allocate(Arena owner, long size, java.util.List<String> fallbackSink) {
            if (size >= DEMAND_PAGED_THRESHOLD_BYTES) {
                String failReason = null;
                try {
                    if (IS_WINDOWS && VIRTUAL_ALLOC_FN != null && VIRTUAL_FREE_FN != null) {
                        // MEM_COMMIT (0x1000) | MEM_RESERVE (0x2000), PAGE_READWRITE (0x04)
                        MemorySegment raw = (MemorySegment) VIRTUAL_ALLOC_FN.invokeExact(MemorySegment.NULL, size, 0x3000, 0x04);
                        if (raw.address() != 0L) {
                            return raw.reinterpret(size, owner, seg -> {
                                try {
                                    // MEM_RELEASE (0x8000)
                                    int ignored = (int) VIRTUAL_FREE_FN.invokeExact(seg, 0L, 0x8000);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                        failReason = "VirtualAlloc returned NULL";
                    } else if (!IS_WINDOWS && MMAP_FN != null && MUNMAP_FN != null) {
                        // PROT_READ | PROT_WRITE = 3; MAP_PRIVATE (0x02) | MAP_ANONYMOUS (0x20 Linux, 0x1000 macOS) | MAP_NORESERVE (0x4000 Linux)
                        int flags = 0x02 | (IS_MACOS ? 0x1000 : (0x20 | 0x4000));
                        MemorySegment raw = (MemorySegment) MMAP_FN.invokeExact(MemorySegment.NULL, size, 3, flags, -1, 0L);
                        if (raw.address() != -1L && raw.address() != 0L) {
                            if (IS_LINUX && MADVISE_FN != null && size >= THP_MIN_BYTES) {
                                try {
                                    int ignored = (int) MADVISE_FN.invokeExact(raw, size, LINUX_MADV_HUGEPAGE);
                                } catch (Throwable ignored) {
                                }
                            }
                            return raw.reinterpret(size, owner, seg -> {
                                try {
                                    int ignored = (int) MUNMAP_FN.invokeExact(seg, size);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                        failReason = "mmap returned MAP_FAILED";
                    } else {
                        failReason = IS_WINDOWS ? "kernel32 VirtualAlloc unavailable" : "libc mmap unavailable";
                    }
                } catch (Throwable err) {
                    failReason = err.getClass().getSimpleName() + ": " + err.getMessage();
                }
                if (fallbackSink != null && failReason != null) {
                    fallbackSink.add("size=" + size + "B (" + failReason + ")");
                }
            }
            MemorySegment backing = owner.allocate(size, 4096);
            backing.fill((byte) 0);
            return backing;
        }
    }

    @Override
    public void map(long address, long size, int permissions) {
        if (size <= 0 || address < 0 || address > 0x1_0000_0000L - size || (address & 0xfffL) != 0 || (size & 0xfffL) != 0) {
            throw new IllegalArgumentException("Page-aligned 32-bit RAM range required: base=0x" + Long.toHexString(address) + " size=" + size);
        }
        if (overlapsRegion(address, size)) {
            throw new IllegalArgumentException("RAM overlap at 0x" + Long.toHexString(address));
        }
        try {
            if (fastmemBase != 0L && getRamHostPtrFn != null) {
                int fmRc = (int) mapRam.invokeExact(engine, (int) address, (int) size, MemorySegment.NULL, permissions);
                if (fmRc == 0) {
                    MemorySegment hostRw = (MemorySegment) getRamHostPtrFn.invokeExact(engine, (int) address);
                    if (hostRw != null && !hostRw.equals(MemorySegment.NULL)) {
                        // Dual-view (Linux memfd_create / Win10+ MapViewOfFile3): hostRw is ALWAYS
                        // PROT_READ|PROT_WRITE and safe for direct Java DMA copy. Single-view fallback
                        // omits the direct segment so DMA routes through fuji_dyn_mem_{read,write}.
                        if (fastmemDualView) {
                            addRegion(address, size, hostRw.reinterpret(size));
                        } else {
                            addRegion(address, size, MemorySegment.NULL);
                        }
                        return;
                    }
                    // Defensive cleanup if a legacy bridge ever returned 0 without a host pointer.
                    int ignored = (int) unmapRam.invokeExact(engine, (int) address, (int) size);
                    fastmemFallbackRegions.add("0x" + Long.toHexString(address) + "(" + size + "B,nullHostPtr)");
                } else if (fmRc == -3) {
                    fastmemFallbackRegions.add("0x" + Long.toHexString(address) + "(" + size + "B)");
                } else {
                    check(fmRc);
                }
            }
            MemorySegment backing = OsZeroRamAllocator.allocate(ramArena, size, osZeroRamFallbacks);
            check((int) mapRam.invokeExact(engine, (int) address, (int) size, backing, permissions));
            addRegion(address, size, backing);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean supportsHighPhysRam() {
        return highPhysRamSupported;
    }

    /**
     * Maps a 40-bit high physical RAM region ({@code address >= 0x1_0000_0000L}, up to 1 TiB)
     * accessed by ARMv7-A 16 MiB Short-Descriptor Supersections (`L1[23:20]`/`L1[8:5]`).
     * Backed by demand-paged zero OS virtual memory (`MAP_NORESERVE` / `VirtualAlloc`) so untouched
     * pages consume 0 bytes of RSS.
     */
    public void mapHighRam(long physBase, long size, int permissions) {
        if (!highPhysRamSupported) {
            throw new UnsupportedOperationException("Native libfuji_dynarmic does not export fuji_dyn_map_ram64");
        }
        final long physLimit = 1L << 40;
        if (size <= 0 || physBase < 0x1_0000_0000L || physBase >= physLimit || size > (physLimit - physBase)
            || (physBase & 0xfffL) != 0 || (size & 0xfffL) != 0) {
            throw new IllegalArgumentException(
                "Page-aligned 40-bit high-PA RAM range required (>=4 GiB): base=0x" + Long.toHexString(physBase) + " size=" + size);
        }
        try {
            MemorySegment backing = OsZeroRamAllocator.allocate(ramArena, size, osZeroRamFallbacks);
            check((int) mapRam64Fn.invokeExact(engine, physBase, size, backing, permissions));
            mappedHighRamRanges.add(new long[] { physBase, size });
            addHighRegion(physBase, size, backing);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public byte[] readHighMemory(long physAddr, int size) {
        if (size < 0) throw new IllegalArgumentException("negative read size");
        byte[] out = new byte[size];
        readHighMemory(physAddr, ByteBuffer.wrap(out));
        return out;
    }

    public void readHighMemory(long physAddr, ByteBuffer destination) {
        transferHighMemory(physAddr, destination, false);
    }

    public void writeHighMemory(long physAddr, byte[] data) {
        writeHighMemory(physAddr, ByteBuffer.wrap(data));
    }

    public void writeHighMemory(long physAddr, ByteBuffer source) {
        transferHighMemory(physAddr, source, true);
    }

    /** Owner-thread DMA. Direct buffers cross FFM unchanged; heap rows/pages reuse one staging segment. */
    private void transferHighMemory(long address, ByteBuffer buffer, boolean write) {
        if (!highPhysRamSupported) {
            throw new UnsupportedOperationException("Native libfuji_dynarmic has no high physical RAM support");
        }
        int size = buffer.remaining();
        if (size == 0) return;
        if (!write && buffer.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        MemorySegment direct = sliceDirectRam(address, size);
        if (direct != null) {
            MemorySegment bytes = MemorySegment.ofBuffer(buffer);
            if (write) {
                MemorySegment.copy(bytes, 0, direct, 0, size);
            } else {
                MemorySegment.copy(direct, 0, bytes, 0, size);
            }
            buffer.position(buffer.limit());
            return;
        }
        MemorySegment bytes = MemorySegment.ofBuffer(buffer);
        try {
            if (bytes.isNative()) {
                transferNativeHighMemory(address, bytes, size, write);
            } else if (size <= highDmaScratch.byteSize() || fitsMappedHighRam(address, size)) {
                int offset = 0;
                while (offset < size) {
                    int chunk = (int) Math.min(highDmaScratch.byteSize(), size - offset);
                    transferHeapHighMemory(address + offset, bytes, offset, chunk, highDmaScratch, write);
                    offset += chunk;
                }
            } else {
                // A native whole-range check preserves all-or-nothing access for other mappings.
                try (Arena temporary = Arena.ofConfined()) {
                    transferHeapHighMemory(address, bytes, 0, size, temporary.allocate(size, 8), write);
                }
            }
            buffer.position(buffer.limit());
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    private boolean fitsMappedHighRam(long address, int size) {
        for (int i = 0; i < mappedHighRamRanges.size(); i++) {
            long[] range = mappedHighRamRanges.get(i);
            if (address >= range[0] && address - range[0] <= range[1] - size) return true;
        }
        return false;
    }

    private void transferHeapHighMemory(long address, MemorySegment bytes, int offset, int size, MemorySegment staging,
                                        boolean write) throws Throwable {
        if (write) MemorySegment.copy(bytes, offset, staging, 0, size);
        transferNativeHighMemory(address, staging, size, write);
        if (!write) MemorySegment.copy(staging, 0, bytes, offset, size);
    }

    private void transferNativeHighMemory(long address, MemorySegment segment, int size, boolean write)
            throws Throwable {
        int result = write
                ? (int) memWrite64Fn.invokeExact(engine, address, segment, size)
                : (int) memRead64Fn.invokeExact(engine, address, segment, size);
        check(result);
    }

    public long translate64(long va, int access) {
        if (!highPhysRamSupported) {
            return translate(va, access);
        }
        try {
            int rc = (int) translate64Fn.invokeExact(engine, activeCore, (int) va, access, scratchInt);
            if (rc != 0) {
                throw new IllegalStateException("Dynarmic MMU translate64 fault rc=" + rc + " va=0x" + Long.toHexString(va));
            }
            return scratchInt.get(LITTLE_LONG, 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void unmap(long address, long size) {
        try {
            check((int) unmapRam.invokeExact(engine, (int) address, (int) size));
            removeRegionRange(address, size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void mmio(long base, long size) {
        try {
            check((int) mapMmio.invokeExact(engine, (int) base, (int) size));
            mappedMmioRanges.add(new long[] { base, size });
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void unmapMmio(long base, long size) {
        try {
            check((int) unmapMmio.invokeExact(engine, (int) base, (int) size));
            mappedMmioRanges.removeIf(r -> r[0] == base && r[1] == size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void writeMemory(long address, byte[] bytes) {
        writeMemory(address, bytes, 0, bytes.length, true);
    }

    @Override
    public void writeMemory(long address, byte[] bytes, int offset, int size, boolean flush) {
        if (offset < 0 || size < 0 || offset > bytes.length - size) {
            throw new IllegalArgumentException("invalid write slice");
        }
        if (size == 0) return;
        try {
            MappedRegion single = findRegion(address, size);
            if (single != null) {
                MemorySegment.copy(bytes, offset, single.segment, JAVA_BYTE, address - single.base, size);
            } else {
                long curAddr = address;
                int curOff = offset;
                int rem = size;
                while (rem > 0) {
                    MappedRegion r = findRegionContaining(curAddr);
                    if (r != null && !r.segment.equals(MemorySegment.NULL)) {
                        int chunk = (int) Math.min(rem, r.end - curAddr);
                        MemorySegment.copy(bytes, curOff, r.segment, JAVA_BYTE, curAddr - r.base, chunk);
                        curAddr += chunk;
                        curOff += chunk;
                        rem -= chunk;
                    } else {
                        try (Arena tmp = Arena.ofConfined()) {
                            MemorySegment buf = tmp.allocate(rem, 8);
                            MemorySegment.copy(bytes, curOff, buf, JAVA_BYTE, 0L, rem);
                            check((int) memWrite.invokeExact(engine, (int) curAddr, buf, rem));
                        }
                        break;
                    }
                }
            }
            if (flush) invalidateRange.invokeExact(engine, (int) address, size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void writeMemory(long address, ByteBuffer source, boolean flush) {
        int size = source.remaining();
        if (source.hasArray()) {
            writeMemory(address, source.array(), source.arrayOffset() + source.position(), size, flush);
            source.position(source.position() + size);
            return;
        }
        if (size == 0) return;
        try {
            MappedRegion r = findRegion(address, size);
            if (r != null) {
                r.segment.asSlice(address - r.base, size).asByteBuffer().put(source);
            } else {
                byte[] copy = new byte[size];
                source.get(copy);
                writeMemory(address, copy, 0, size, flush);
                return;
            }
            if (flush) invalidateRange.invokeExact(engine, (int) address, size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public byte[] readMemory(long address, int size) {
        if (size < 0 || size > 16 * 1024 * 1024) throw new IllegalArgumentException("bounded memory read required");
        byte[] out = new byte[size];
        readMemoryInto(address, out, 0, size);
        return out;
    }

    @Override
    public void readMemoryInto(long address, byte[] dest, int destOffset, int size) {
        if (size < 0 || size > 16 * 1024 * 1024 || destOffset < 0 || destOffset > dest.length - size) {
            throw new IllegalArgumentException("bounded memory read required");
        }
        if (size == 0) return;
        try {
            MappedRegion single = findRegion(address, size);
            if (single != null) {
                MemorySegment.copy(single.segment, JAVA_BYTE, address - single.base, dest, destOffset, size);
                return;
            }
            long curAddr = address;
            int curOff = destOffset;
            int rem = size;
            while (rem > 0) {
                MappedRegion r = findRegionContaining(curAddr);
                if (r != null && !r.segment.equals(MemorySegment.NULL)) {
                    int chunk = (int) Math.min(rem, r.end - curAddr);
                    MemorySegment.copy(r.segment, JAVA_BYTE, curAddr - r.base, dest, curOff, chunk);
                    curAddr += chunk;
                    curOff += chunk;
                    rem -= chunk;
                } else {
                    try (Arena tmp = Arena.ofConfined()) {
                        MemorySegment buf = tmp.allocate(rem, 8);
                        check((int) memRead.invokeExact(engine, (int) curAddr, buf, rem));
                        MemorySegment.copy(buf, JAVA_BYTE, 0L, dest, curOff, rem);
                    }
                    break;
                }
            }
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void readMemoryInto(long address, ByteBuffer dest) {
        int size = dest.remaining();
        if (size < 0 || size > 16 * 1024 * 1024) throw new IllegalArgumentException("bounded memory read required");
        if (dest.hasArray()) {
            readMemoryInto(address, dest.array(), dest.arrayOffset() + dest.position(), size);
            dest.position(dest.position() + size);
            return;
        }
        if (size == 0) return;
        MappedRegion r = findRegion(address, size);
        if (r != null) {
            dest.put(r.segment.asSlice(address - r.base, size).asByteBuffer());
            return;
        }
        byte[] tmp = new byte[size];
        readMemoryInto(address, tmp, 0, size);
        dest.put(tmp);
    }

    @Override
    public void hashMemory(long address, long size, MessageDigest digest) {
        if (size < 0) throw new IllegalArgumentException("negative memory hash size");
        if (size == 0) return;
        MappedRegion r = findRegion(address, size);
        if (r != null) {
            digest.update(r.segment.asSlice(address - r.base, size).asByteBuffer());
            return;
        }
        byte[] buf = new byte[(int) Math.min(1048576L, Math.max(1L, size))];
        long offset = 0L;
        while (offset < size) {
            int chunk = (int) Math.min(buf.length, size - offset);
            readMemoryInto(address + offset, buf, 0, chunk);
            digest.update(buf, 0, chunk);
            offset += chunk;
        }
    }

    @Override
    public long physicalWord(long address) {
        if (address < 0 || address > 0xffff_fffcL || (address & 3) != 0) {
            throw new IllegalArgumentException("aligned u32 physical address required");
        }
        MappedRegion r = findRegion(address, 4L);
        if (r != null) return Integer.toUnsignedLong(r.segment.get(LITTLE_INT, address - r.base));
        try {
            check((int) memRead.invokeExact(engine, (int) address, scratchInt, 4));
            return Integer.toUnsignedLong(scratchInt.get(LITTLE_INT, 0));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void writePhysicalWord(long address, long value) {
        if (address < 0 || address > 0xffff_fffcL || (address & 3) != 0 || value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("aligned u32 physical word required");
        }
        MappedRegion r = findRegion(address, 4L);
        try {
            if (r != null) {
                r.segment.set(LITTLE_INT, address - r.base, (int) value);
            } else {
                scratchInt.set(LITTLE_INT, 0, (int) value);
                check((int) memWrite.invokeExact(engine, (int) address, scratchInt, 4));
            }
            invalidateRange.invokeExact(engine, (int) address, 4);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public long physicalHalfword(long address) {
        if (address < 0 || address > 0xffff_fffeL || (address & 1) != 0) {
            throw new IllegalArgumentException("aligned u16 physical address required");
        }
        MappedRegion r = findRegion(address, 2L);
        if (r != null) return Short.toUnsignedInt(r.segment.get(LITTLE_SHORT, address - r.base));
        try {
            check((int) memRead.invokeExact(engine, (int) address, scratchInt, 2));
            return Short.toUnsignedInt(scratchInt.get(LITTLE_SHORT, 0));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void writePhysicalHalfword(long address, long value) {
        if (address < 0 || address > 0xffff_fffeL || (address & 1) != 0 || value < 0 || value > 0xffffL) {
            throw new IllegalArgumentException("aligned u16 physical value required");
        }
        MappedRegion r = findRegion(address, 2L);
        try {
            if (r != null) {
                r.segment.set(LITTLE_SHORT, address - r.base, (short) value);
            } else {
                scratchInt.set(LITTLE_SHORT, 0, (short) value);
                check((int) memWrite.invokeExact(engine, (int) address, scratchInt, 2));
            }
            invalidateRange.invokeExact(engine, (int) address, 2);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public long physicalByte(long address) {
        if (address < 0 || address > 0xffff_ffffL) {
            throw new IllegalArgumentException("u32 physical byte address required");
        }
        MappedRegion r = findRegion(address, 1L);
        if (r != null) return Byte.toUnsignedInt(r.segment.get(JAVA_BYTE, address - r.base));
        try {
            check((int) memRead.invokeExact(engine, (int) address, scratchInt, 1));
            return Byte.toUnsignedInt(scratchInt.get(JAVA_BYTE, 0));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void writePhysicalByte(long address, long value) {
        if (address < 0 || address > 0xffff_ffffL || value < 0 || value > 0xffL) {
            throw new IllegalArgumentException("u8 physical value required");
        }
        MappedRegion r = findRegion(address, 1L);
        try {
            if (r != null) {
                r.segment.set(JAVA_BYTE, address - r.base, (byte) value);
            } else {
                scratchInt.set(JAVA_BYTE, 0, (byte) value);
                check((int) memWrite.invokeExact(engine, (int) address, scratchInt, 1));
            }
            invalidateRange.invokeExact(engine, (int) address, 1);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    // ---- Core Register / CP15 / Banked / VFP APIs ----

    public long getReg(int core, int reg) {
        try {
            return Integer.toUnsignedLong((int) getReg.invokeExact(engine, core, reg));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setReg(int core, int reg, long value) {
        try {
            setReg.invokeExact(engine, core, reg, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getCpsr(int core) {
        if (liveStateSupported && core >= 0 && core < MAX_CORES) {
            int valid = liveStateBuf.get(LITTLE_INT, LIVE_OFF_VALID_MASK);
            if ((valid & (1 << core)) != 0) {
                return Integer.toUnsignedLong(liveStateBuf.get(LITTLE_INT, LIVE_OFF_CPSR_BY_CORE + (core * 4L)));
            }
        }
        try {
            return Integer.toUnsignedLong((int) getCpsr.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setCpsr(int core, long value) {
        try {
            setCpsr.invokeExact(engine, core, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getSpsr(int core) {
        try {
            return Integer.toUnsignedLong((int) getSpsr.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setSpsr(int core, long value) {
        try {
            setSpsr.invokeExact(engine, core, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getBankedReg(int core, int mode, int reg) {
        try {
            return Integer.toUnsignedLong((int) getBankedReg.invokeExact(engine, core, mode, reg));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setBankedReg(int core, int mode, int reg, long value) {
        try {
            setBankedReg.invokeExact(engine, core, mode, reg, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getCp15(int core, int opc1, int crn, int crm, int opc2) {
        try {
            return Integer.toUnsignedLong((int) getCp15.invokeExact(engine, core, opc1, crn, crm, opc2));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setCp15(int core, int opc1, int crn, int crm, int opc2, long value) {
        try {
            setCp15.invokeExact(engine, core, opc1, crn, crm, opc2, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getCp15_64(int core, int opc1, int crm) {
        try {
            return (long) getCp15_64.invokeExact(engine, core, opc1, crm);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setCp15_64(int core, int opc1, int crm, long value) {
        try {
            setCp15_64.invokeExact(engine, core, opc1, crm, value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getDreg(int core, int dIndex) {
        try {
            return (long) getDreg.invokeExact(engine, core, dIndex);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setDreg(int core, int dIndex, long value) {
        try {
            setDreg.invokeExact(engine, core, dIndex, value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getExtReg(int core, int index) {
        try {
            return Integer.toUnsignedLong((int) getExtReg.invokeExact(engine, core, index));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setExtReg(int core, int index, long value) {
        try {
            setExtReg.invokeExact(engine, core, index, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getFpscr(int core) {
        try {
            return Integer.toUnsignedLong((int) getFpscr.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setFpscr(int core, long value) {
        try {
            setFpscr.invokeExact(engine, core, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long getFpexc(int core) {
        try {
            return Integer.toUnsignedLong((int) getFpexc.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setFpexc(int core, long value) {
        try {
            setFpexc.invokeExact(engine, core, (int) value);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    // ---- ArmCpuBackend Architectural Register & CP15 Mapping (operates on activeCore) ----

    @Override
    public long register(int register) {
        if (register >= ArmReg.R0 && register <= ArmReg.R0 + 12) return getReg(activeCore, register - ArmReg.R0);
        if (register == ArmReg.SP) return getReg(activeCore, 13);
        if (register == ArmReg.LR) return getReg(activeCore, 14);
        if (register == ArmReg.PC) return getReg(activeCore, 15);
        if (register == ArmReg.CPSR) return getCpsr(activeCore);
        if (register == FloatingPointDiagnostics.FPSCR) return getFpscr(activeCore);
        if (register == FloatingPointDiagnostics.FPEXC) return getFpexc(activeCore);
        if (register >= FloatingPointDiagnostics.D0 && register < FloatingPointDiagnostics.D0 + 32) {
            return getDreg(activeCore, register - FloatingPointDiagnostics.D0);
        }
        throw new IllegalArgumentException("Unsupported Dynarmic register id " + register);
    }

    @Override
    public void register(int register, long value) {
        if (register >= ArmReg.R0 && register <= ArmReg.R0 + 12) { setReg(activeCore, register - ArmReg.R0, value); return; }
        if (register == ArmReg.SP) { setReg(activeCore, 13, value); return; }
        if (register == ArmReg.LR) { setReg(activeCore, 14, value); return; }
        if (register == ArmReg.PC) { setReg(activeCore, 15, value); return; }
        if (register == ArmReg.CPSR) { setCpsr(activeCore, value); return; }
        if (register == FloatingPointDiagnostics.FPSCR) { setFpscr(activeCore, value); return; }
        if (register == FloatingPointDiagnostics.FPEXC) { setFpexc(activeCore, value); return; }
        if (register >= FloatingPointDiagnostics.D0 && register < FloatingPointDiagnostics.D0 + 32) {
            setDreg(activeCore, register - FloatingPointDiagnostics.D0, value);
            return;
        }
        throw new IllegalArgumentException("Unsupported Dynarmic register id " + register);
    }

    @Override
    public long readCp(int cp, int opc1, int crn, int crm, int opc2) {
        if (cp != 15) throw new IllegalArgumentException("Only CP15 supported in Dynarmic (got CP" + cp + ")");
        return getCp15(activeCore, opc1, crn, crm, opc2);
    }

    @Override
    public void writeCp(int cp, int opc1, int crn, int crm, int opc2, long value) {
        if (cp != 15) throw new IllegalArgumentException("Only CP15 supported in Dynarmic (got CP" + cp + ")");
        setCp15(activeCore, opc1, crn, crm, opc2, value);
    }

    // ---- Execution, Interrupts, Exceptions & Translation ----

    public RunResult runFast(int core, long maxTicks, long currentVirtualTick, boolean irqPending) {
        if (closed || callbackFailure != null) {
            throw new IllegalStateException("terminal Dynarmic session cannot resume");
        }
        if (maxTicks <= 0L) {
            throw new IllegalArgumentException("positive maxTicks required");
        }
        activeCore = core;
        cachedIrqPending[core] = irqPending;
        cachedIrqValid[core] = true;
        running = true;
        try {
            check((int) runFast.invokeExact(engine, core, maxTicks, currentVirtualTick, irqPending ? 1 : 0, runResultBuf));
        } catch (Throwable e) {
            throw failure(e);
        } finally {
            running = false;
        }
        lastResult.executedTicks = runResultBuf.get(LITTLE_LONG, OFF_EXECUTED_TICKS);
        lastResult.virtualTick = runResultBuf.get(LITTLE_LONG, OFF_VIRTUAL_TICK);
        lastResult.stopPc = Integer.toUnsignedLong(runResultBuf.get(LITTLE_INT, OFF_STOP_PC));
        lastResult.stopCpsr = Integer.toUnsignedLong(runResultBuf.get(LITTLE_INT, OFF_STOP_CPSR));
        int stopReason = runResultBuf.get(LITTLE_INT, OFF_STOP_REASON);
        lastResult.stopReason = stopReason;
        if (stopReason >= STOP_SVC) {
            lastResult.stopInsn = Integer.toUnsignedLong(runResultBuf.get(LITTLE_INT, OFF_STOP_INSN));
            lastResult.faultAddr = Integer.toUnsignedLong(runResultBuf.get(LITTLE_INT, OFF_FAULT_ADDR));
            lastResult.faultStatus = Integer.toUnsignedLong(runResultBuf.get(LITTLE_INT, OFF_FAULT_STATUS));
            lastResult.svcImm = runResultBuf.get(LITTLE_INT, OFF_SVC_IMM);
        } else {
            lastResult.stopInsn = 0L;
            lastResult.faultAddr = 0L;
            lastResult.faultStatus = 0L;
            lastResult.svcImm = 0;
        }
        lastResult.translatedFetches = runResultBuf.get(LITTLE_LONG, OFF_TRANSLATED_FETCHES);
        lastResult.fastMmioHits = runResultBuf.get(LITTLE_LONG, OFF_FAST_MMIO_HITS);
        return lastResult;
    }

    public boolean supportsClusterFast() {
        return clusterFastSupported;
    }

    /**
     * Returns true when the native bridge's {@code fuji_dyn_run_cluster_fast} reads live per-core
     * {@code irq_pending} on every quantum instead of overwriting with stale batch-entry masks.
     */
    public boolean supportsLiveClusterIrq() {
        return liveClusterIrqSupported;
    }

    public ClusterResult runClusterFast(
        long maxTotalTicks,
        long startVtick,
        int runnableMask,
        int irqPendingMask,
        int startCore,
        int startUsed,
        int quantum
    ) {
        return runClusterFast(maxTotalTicks, startVtick, runnableMask, irqPendingMask, startCore, startUsed, quantum, 0);
    }

    public ClusterResult runClusterFast(
        long maxTotalTicks,
        long startVtick,
        int runnableMask,
        int irqPendingMask,
        int startCore,
        int startUsed,
        int quantum,
        int wfeWaitingMask
    ) {
        return runClusterFast(maxTotalTicks, startVtick, runnableMask, irqPendingMask, startCore, startUsed, quantum, wfeWaitingMask, 0);
    }

    public ClusterResult runClusterFast(
        long maxTotalTicks,
        long startVtick,
        int runnableMask,
        int irqPendingMask,
        int startCore,
        int startUsed,
        int quantum,
        int wfeWaitingMask,
        int wfiWaitingMask
    ) {
        if (!clusterFastSupported) throw new UnsupportedOperationException("fuji_dyn_run_cluster_fast not supported by native library");
        if (closed || callbackFailure != null) throw new IllegalStateException("terminal Dynarmic session cannot resume");
        if (maxTotalTicks <= 0L) throw new IllegalArgumentException("positive maxTotalTicks required");
        clusterReqBuf.set(LITTLE_LONG, 0x00L, maxTotalTicks);
        clusterReqBuf.set(LITTLE_LONG, 0x08L, startVtick);
        clusterReqBuf.set(LITTLE_INT, 0x10L, runnableMask);
        clusterReqBuf.set(LITTLE_INT, 0x14L, irqPendingMask);
        clusterReqBuf.set(LITTLE_INT, 0x18L, startCore);
        clusterReqBuf.set(LITTLE_INT, 0x1CL, startUsed);
        clusterReqBuf.set(LITTLE_INT, 0x20L, quantum);
        clusterReqBuf.set(LITTLE_INT, 0x24L, (wfeWaitingMask & 0x0F) | ((wfiWaitingMask & 0x0F) << 4));
        for (int c = 0; c < coreCount; c++) {
            cachedIrqPending[c] = (irqPendingMask & (1 << c)) != 0;
            cachedIrqValid[c] = !gicShadowSupported;
        }
        running = true;
        try {
            check((int) runClusterFastFn.invokeExact(engine, clusterReqBuf, clusterResBuf));
        } catch (Throwable e) {
            throw failure(e);
        } finally {
            running = false;
        }
        ClusterResult r = lastClusterResult;
        r.totalExecuted = clusterResBuf.get(LITTLE_LONG, 0x00L);
        r.virtualTick = clusterResBuf.get(LITTLE_LONG, 0x08L);
        for (int c = 0; c < MAX_CORES; c++) {
            r.coreExecuted[c] = clusterResBuf.get(LITTLE_LONG, 0x10L + c * 8L);
        }
        r.activeCore = clusterResBuf.get(LITTLE_INT, 0x30L);
        r.activeUsed = clusterResBuf.get(LITTLE_INT, 0x34L);
        r.stopReason = clusterResBuf.get(LITTLE_INT, 0x38L);
        r.stopPc = Integer.toUnsignedLong(clusterResBuf.get(LITTLE_INT, 0x3CL));
        r.stopCpsr = Integer.toUnsignedLong(clusterResBuf.get(LITTLE_INT, 0x40L));
        r.stopInsn = Integer.toUnsignedLong(clusterResBuf.get(LITTLE_INT, 0x44L));
        r.faultAddr = Integer.toUnsignedLong(clusterResBuf.get(LITTLE_INT, 0x48L));
        r.faultStatus = Integer.toUnsignedLong(clusterResBuf.get(LITTLE_INT, 0x4CL));
        r.svcImm = clusterResBuf.get(LITTLE_INT, 0x50L);
        r.wfiEnteredMask = clusterResBuf.get(LITTLE_INT, 0x54L);
        r.wfeEnteredMask = clusterResBuf.get(LITTLE_INT, 0x58L);
        r.sevMask = clusterResBuf.get(LITTLE_INT, 0x5CL);
        r.translatedFetches = clusterResBuf.get(LITTLE_LONG, 0x60L);
        r.fastMmioHits = clusterResBuf.get(LITTLE_LONG, 0x68L);
        r.switches = clusterResBuf.get(LITTLE_INT, 0x70L);
        r.wfiWokenMask = clusterResBuf.get(LITTLE_INT, 0x74L);
        activeCore = r.activeCore;
        return r;
    }

    public void haltRun() {
        try {
            haltRun.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void stop() {
        haltRun();
    }

    public void setIrqPending(int core, boolean pending) {
        try {
            setIrqPending.invokeExact(engine, core, pending ? 1 : 0);
            if (core >= 0 && core < MAX_CORES) {
                cachedIrqPending[core] = pending;
                cachedIrqValid[core] = true;
            }
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setIrqPendingIfChanged(int core, boolean pending) {
        if (core >= 0 && core < MAX_CORES && cachedIrqValid[core] && cachedIrqPending[core] == pending) {
            return;
        }
        setIrqPending(core, pending);
    }

    // ---- Native C++ MMIO Read Fastpaths (soc-timer & RO/retained 32-bit registers) ----

    public boolean supportsNativeMmioFastpaths() {
        return nativeMmioFastpathsSupported;
    }

    public void setTimerShadow(
        long physBase,
        boolean active,
        long[] stored,
        long[] reload,
        long[] deadline,
        int[] enabled,
        int[] hasDeadline
    ) {
        if (!nativeMmioFastpathsSupported) return;
        if (stored.length < 8 || reload.length < 8 || deadline.length < 8 || enabled.length < 8 || hasDeadline.length < 8) {
            throw new IllegalArgumentException("8 timer channels required");
        }
        timerShadowBuf.set(LITTLE_INT, 0L, active ? 1 : 0);
        timerShadowBuf.set(LITTLE_INT, 4L, 0);
        for (int i = 0; i < 8; i++) {
            long off = TIMER_CHANNELS_OFFSET + i * TIMER_CHANNEL_STRIDE;
            timerShadowBuf.set(LITTLE_LONG, off, stored[i]);
            timerShadowBuf.set(LITTLE_LONG, off + 8L, reload[i]);
            timerShadowBuf.set(LITTLE_LONG, off + 16L, deadline[i]);
            timerShadowBuf.set(LITTLE_INT, off + 24L, enabled[i]);
            timerShadowBuf.set(LITTLE_INT, off + 28L, hasDeadline[i]);
        }
        try {
            if (setTimerShadowPtrFn != null) {
                if (!sharedTimerPtrBound || sharedTimerBoundBase != physBase) {
                    setTimerShadowPtrFn.invokeExact(engine, (int) physBase, timerShadowBuf);
                    sharedTimerPtrBound = true;
                    sharedTimerBoundBase = physBase;
                }
            } else {
                setTimerShadowFn.invokeExact(engine, (int) physBase, timerShadowBuf);
            }
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearTimerShadow() {
        if (!nativeMmioFastpathsSupported) return;
        sharedTimerPtrBound = false;
        sharedTimerBoundBase = -1L;
        try {
            clearTimerShadowFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean setRoMmioWord(long physAddr, long value) {
        if (!nativeMmioFastpathsSupported) return false;
        try {
            return ((int) setRoMmioWordFn.invokeExact(engine, (int) physAddr, (int) value)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public int setRoMmioBatch(int[] addrs, int[] vals, int count) {
        if (!nativeMmioFastpathsSupported || count <= 0) return 0;
        if (setRoMmioBatchFn == null) {
            int ok = 0;
            for (int i = 0; i < count; i++) {
                if (setRoMmioWord(Integer.toUnsignedLong(addrs[i]), Integer.toUnsignedLong(vals[i]))) ok++;
            }
            return ok;
        }
        int total = 0;
        int off = 0;
        try {
            while (off < count) {
                int chunk = Math.min(count - off, RO_BATCH_MAX);
                MemorySegment.copy(addrs, off, roBatchAddrBuf, LITTLE_INT, 0L, chunk);
                MemorySegment.copy(vals, off, roBatchValBuf, LITTLE_INT, 0L, chunk);
                int rc = (int) setRoMmioBatchFn.invokeExact(engine, roBatchAddrBuf, roBatchValBuf, chunk);
                if (rc > 0) total += rc;
                off += chunk;
            }
        } catch (Throwable e) {
            throw failure(e);
        }
        return total;
    }

    public boolean supportsRoMmioRanges() {
        return roRangesSupported;
    }

    public boolean addRoMmioZeroRange(long physBase, long size) {
        if (!roRangesSupported || size <= 0L) return false;
        try {
            return ((int) addRoMmioRangeFn.invokeExact(engine, (int) physBase, (int) size, MemorySegment.NULL)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean addRoMmioBufferRange(long physBase, byte[] data) {
        if (!roRangesSupported || data == null || data.length == 0) return false;
        try {
            MemorySegment seg = ramArena.allocate(data.length, 8);
            MemorySegment.copy(data, 0, seg, JAVA_BYTE, 0L, data.length);
            int rc = (int) addRoMmioRangeFn.invokeExact(engine, (int) physBase, data.length, seg);
            if (rc == 0) {
                roRangeSegments.add(seg);
                return true;
            }
            return false;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    /** Allocates a zeroed native buffer in `ramArena` suitable for live in-place RO-MMIO updates (e.g. ISP Stats SRAM). */
    public MemorySegment allocateRoMmioBuffer(int size) {
        if (!roRangesSupported || size <= 0) return null;
        MemorySegment seg = ramArena.allocate(size, 8);
        seg.fill((byte) 0);
        roRangeSegments.add(seg);
        return seg;
    }

    /** Maps a pre-allocated native segment (or slice) as a live RO-MMIO range without copying. */
    public boolean addRoMmioSegmentRange(long physBase, MemorySegment seg, int size) {
        if (!roRangesSupported || seg == null || size <= 0) return false;
        try {
            return ((int) addRoMmioRangeFn.invokeExact(engine, (int) physBase, size, seg)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearRoMmioRanges() {
        if (!roRangesSupported) return;
        try {
            clearRoMmioRangesFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void removeRoMmioWord(long physAddr) {
        if (!nativeMmioFastpathsSupported) return;
        try {
            removeRoMmioWordFn.invokeExact(engine, (int) physAddr);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearRoMmioRange(long physBase, long size) {
        if (!nativeMmioFastpathsSupported || size <= 0L) return;
        try {
            clearRoMmioRangeFn.invokeExact(engine, (int) physBase, (int) size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearRoMmio() {
        if (!nativeMmioFastpathsSupported) return;
        try {
            clearRoMmioFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean supportsMmioMirrors() {
        return mmioMirrorsSupported;
    }

    public boolean registerMmioMirror(long physBase, long windowSize, MemorySegment mirrorBlock) {
        if (!mmioMirrorsSupported || windowSize <= 0L || mirrorBlock == null || mirrorBlock.equals(MemorySegment.NULL)) {
            return false;
        }
        try {
            return ((int) registerMmioMirrorFn.invokeExact(engine, (int) physBase, (int) windowSize, mirrorBlock)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean unregisterMmioMirror(long physBase) {
        if (!mmioMirrorsSupported) return false;
        try {
            return ((int) unregisterMmioMirrorFn.invokeExact(engine, (int) physBase)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearMmioMirrors() {
        if (!mmioMirrorsSupported) return;
        try {
            clearMmioMirrorsFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void readMmioStats(MmioStats out) {
        if (!nativeMmioFastpathsSupported) {
            out.timerFastHits = 0L;
            out.roMmioFastHits = 0L;
            out.upcallReads = 0L;
            out.upcallWrites = 0L;
            return;
        }
        try {
            getMmioStatsFn.invokeExact(engine, mmioStatsBuf);
            out.timerFastHits = mmioStatsBuf.get(LITTLE_LONG, 0L);
            out.roMmioFastHits = mmioStatsBuf.get(LITTLE_LONG, 8L);
            out.upcallReads = mmioStatsBuf.get(LITTLE_LONG, 16L);
            out.upcallWrites = mmioStatsBuf.get(LITTLE_LONG, 24L);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean supportsGicShadow() {
        return gicShadowSupported;
    }

    public MemorySegment gicShadowSegment() {
        return gicShadowBuf;
    }

    public void bindGicShadow(long gicdBase, long giccBase) {
        if (!gicShadowSupported) return;
        try {
            setGicShadowPtrFn.invokeExact(engine, (int) gicdBase, (int) giccBase, gicShadowBuf);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setGicShadowPtr(long gicdBase, long giccBase, MemorySegment sharedShadow) {
        if (!gicShadowSupported) return;
        if (sharedShadow == null || sharedShadow.equals(MemorySegment.NULL)) {
            clearGicShadow();
            return;
        }
        if (sharedShadow.byteSize() < GIC_SHADOW_SIZE) {
            throw new IllegalArgumentException("sharedShadow too small: " + sharedShadow.byteSize() + " < " + GIC_SHADOW_SIZE);
        }
        try {
            setGicShadowPtrFn.invokeExact(engine, (int) gicdBase, (int) giccBase, sharedShadow);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearGicShadow() {
        if (!gicShadowSupported) return;
        gicShadowBuf.fill((byte) 0);
        try {
            clearGicShadowFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void resetMmioStats() {
        if (!nativeMmioFastpathsSupported) return;
        try {
            resetMmioStatsFn.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean deliverIrq(int core) {
        try {
            return ((int) deliverIrq.invokeExact(engine, core)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public boolean deliverFiq(int core) {
        try {
            return ((int) deliverFiq.invokeExact(engine, core)) == 0;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void deliverSvc(int core) {
        try {
            check((int) deliverSvc.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void deliverUndef(int core) {
        try {
            check((int) deliverUndef.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void deliverPabt(int core, long far, long fsr) {
        try {
            check((int) deliverPabt.invokeExact(engine, core, (int) far, (int) fsr));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void deliverDabt(int core, long far, long fsr) {
        try {
            check((int) deliverDabt.invokeExact(engine, core, (int) far, (int) fsr));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setAutoExceptions(int enableMask) {
        try {
            setAutoExceptions.invokeExact(engine, enableMask);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setWfeEventMask(int mask) {
        try {
            setWfeEventMask.invokeExact(engine, mask & 0x0f);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public int getWfeEventMask() {
        try {
            return ((int) getWfeEventMask.invokeExact(engine)) & 0x0f;
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setTracepoints(long[] pcs) {
        try {
            if (pcs == null || pcs.length == 0) {
                setTracepoints.invokeExact(engine, MemorySegment.NULL, 0);
                return;
            }
            try (Arena tmp = Arena.ofConfined()) {
                MemorySegment buf = tmp.allocate(pcs.length * 4L, 4);
                for (int i = 0; i < pcs.length; i++) buf.setAtIndex(LITTLE_INT, i, (int) pcs[i]);
                setTracepoints.invokeExact(engine, buf, pcs.length);
            }
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void setCntfrq(long freqHz) {
        try {
            setCntfrq.invokeExact(engine, (int) freqHz);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void clearExclusive(int core) {
        try {
            clearExclusive.invokeExact(engine, core);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void resetCore(int core) {
        try {
            resetCore.invokeExact(engine, core);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long currentPc(int core) {
        if (running && liveStateSupported && core == liveStateBuf.get(LITTLE_INT, LIVE_OFF_CORE)) {
            return Integer.toUnsignedLong(liveStateBuf.get(LITTLE_INT, LIVE_OFF_PC));
        }
        try {
            return Integer.toUnsignedLong((int) getCurrentPc.invokeExact(engine, core));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long currentVirtualTick() {
        if (running && liveStateSupported) {
            return liveStateBuf.get(LITTLE_LONG, LIVE_OFF_VTICK);
        }
        try {
            return (long) getCurrentVtick.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public long translate(int core, long virtualAddress, int access) {
        if (virtualAddress < 0 || virtualAddress > 0xffff_ffffL || (access != 1 && access != 2 && access != 4)) {
            throw new IllegalArgumentException("invalid translation request");
        }
        try {
            scratchInt.set(LITTLE_INT, 0, 0);
            int rc = (int) translateFn.invokeExact(engine, core, (int) virtualAddress, access, scratchInt);
            if (rc != 0) {
                throw new IllegalStateException(
                    "dynarmic-mmu-translation-refused status=" + rc + " virtual=" + Long.toHexString(virtualAddress) + " access=" + access
                );
            }
            return Integer.toUnsignedLong(scratchInt.get(LITTLE_INT, 0));
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public long translate(long virtualAddress, int access) {
        return translate(activeCore, virtualAddress, access);
    }

    public void flushTlb(int core) {
        try {
            flushTlb.invokeExact(engine, core);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void flushTlb() {
        flushTlb(activeCore);
    }

    @Override
    public void flushBlocks() {
        try {
            clearCache.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public void invalidateRange(long physBase, long size) {
        if (size <= 0L) return;
        try {
            invalidateRange.invokeExact(engine, (int) physBase, (int) size);
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    public Throwable callbackFailure() {
        return callbackFailure;
    }

    /**
     * `fuji_dynarmic` uses Dynarmic's software `page_table` (never `fastmem_pointer`), yet
     * `Dynarmic::A32::Jit` unconditionally installs Dynarmic's POSIX `SIGSEGV`/`SIGBUS` handler
     * in front of OpenJDK HotSpot's handler, causing benign recovered HotSpot implicit null-check
     * and safepoint signals to spam `Unhandled SIGSEGV at rip 0x...` to stderr. Preserve and
     * restore the JVM's Linux `SIGSEGV`/`SIGBUS` dispositions around `fuji_dyn_create`.
     */
    private MemorySegment invokeCreatePreservingPosixSignals(MethodHandle createFn, int numCores, int cbarBase) throws Throwable {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            return (MemorySegment) createFn.invokeExact(numCores, cbarBase);
        }
        var sigactionSymbol = linker.defaultLookup().find("sigaction");
        if (sigactionSymbol.isEmpty()) {
            return (MemorySegment) createFn.invokeExact(numCores, cbarBase);
        }
        MethodHandle sigaction = linker.downcallHandle(
            sigactionSymbol.get(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
        );
        synchronized (POSIX_SIGNAL_LOCK) {
            try (Arena sigArena = Arena.ofConfined()) {
                MemorySegment oldSegv = sigArena.allocate(POSIX_SIGACTION_BUF_BYTES, 8);
                MemorySegment oldBus = sigArena.allocate(POSIX_SIGACTION_BUF_BYTES, 8);
                boolean savedSegv = ((int) sigaction.invokeExact(LINUX_SIGSEGV, MemorySegment.NULL, oldSegv)) == 0;
                boolean savedBus = ((int) sigaction.invokeExact(LINUX_SIGBUS, MemorySegment.NULL, oldBus)) == 0;
                try {
                    return (MemorySegment) createFn.invokeExact(numCores, cbarBase);
                } finally {
                    if (savedSegv) {
                        int ignored = (int) sigaction.invokeExact(LINUX_SIGSEGV, oldSegv, MemorySegment.NULL);
                    }
                    if (savedBus) {
                        int ignored = (int) sigaction.invokeExact(LINUX_SIGBUS, oldBus, MemorySegment.NULL);
                    }
                }
            }
        }
    }

    /**
     * Resets all ARM architectural state, TLBs, JIT code cache, MMIO fastpaths, and guest RAM/MMIO
     * mappings on this engine so a Sub-MCU power-cycle cold-reset can reuse the existing JIT code
     * buffer without re-allocating a second Xbyak 64 MiB RWX region in the same JVM process.
     */
    public void resetForColdReset() {
        if (closed) throw new IllegalStateException("closed Dynarmic instance cannot be reset");
        mmioHandler = null;
        callbackFailure = null;
        activeCore = 0;
        running = false;
        clearTimerShadow();
        clearTimerIrqShadow();
        clearGicShadow();
        clearRoMmio();
        clearRoMmioRanges();
        clearMmioMirrors();
        if (liveStateSupported) {
            liveStateBuf.set(LITTLE_INT, LIVE_OFF_VALID_MASK, 0);
        }
        resetMmioStats();
        setTracepoints(null);
        setAutoExceptions(0);
        setWfeEventMask(0);
        setVtickDivider(1);
        setSmpHostThreads(false);
        try {
            for (MappedRegion r : mappedRegions) {
                check((int) unmapRam.invokeExact(engine, (int) r.base, (int) r.size));
            }
            mappedRegions = new MappedRegion[0];
            lastMapped = null;
            fastmemFallbackRegions.clear();
            osZeroRamFallbacks.clear();
            if (highPhysRamSupported) {
                for (long[] hr : mappedHighRamRanges) {
                    check((int) unmapRam64Fn.invokeExact(engine, hr[0], hr[1]));
                }
            }
            mappedHighRamRanges.clear();
            mappedHighRegions = new MappedRegion[0];
            lastMappedHigh = null;
            ramArena.close();
            ramArena = Arena.ofConfined();
            for (long[] mmio : mappedMmioRanges) {
                check((int) unmapMmio.invokeExact(engine, (int) mmio[0], (int) mmio[1]));
            }
            mappedMmioRanges.clear();
            for (int c = 0; c < coreCount; c++) {
                cachedIrqPending[c] = false;
                cachedIrqValid[c] = false;
                setIrqPending(c, false);
                resetCore(c);
                flushTlb(c);
                clearExclusive(c);
            }
            flushBlocks();
        } catch (Throwable e) {
            throw failure(e);
        }
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            destroy.invokeExact(engine);
        } catch (Throwable e) {
            throw failure(e);
        } finally {
            closed = true;
            ramArena.close();
            arena.close();
        }
    }
}
