package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JDK 25 Foreign Function & Memory (FFM) binding for OS-native high-resolution timer,
 * scheduler priority / anti-EcoQoS, and Linux Transparent Huge Pages (THP) helpers
 * exported by `libfuji_audio.so` (Linux) and `fuji_audio.dll` (Windows x86_64).
 *
 * - **Windows**: `CreateWaitableTimerExW(CREATE_WAITABLE_TIMER_HIGH_RESOLUTION)` +
 *   `timeBeginPeriod(1)` + `SetProcessInformation(ProcessPowerThrottling)` (disable EcoQoS
 *   & Win11 background timer throttling) + `SetThreadPriority(ABOVE_NORMAL/HIGHEST)` &
 *   `SetThreadInformation(ThreadPowerThrottling)` on `OwnerLoop` / audio threads.
 * - **Linux**: `clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)` + `ppoll`/`eventfd`
 *   interruptible hrtimer waiter + `prctl(PR_SET_TIMERSLACK, 1)` + `madvise(MADV_HUGEPAGE)`.
 *
 * Safe zero-throw degradation when the native library is absent (e.g. isolated unit tests).
 */
object NativeOsScheduler {
    private val log = LoggerFactory.getLogger(NativeOsScheduler::class.java)

    const val OS_ABI_VERSION = 1

    const val CAP_HIGHRES_TIMER = 1 shl 0
    const val CAP_TIMER_PERIOD_1MS = 1 shl 1
    const val CAP_ANTI_ECOQOS = 1 shl 2
    const val CAP_THREAD_PRIORITY = 1 shl 3
    const val CAP_MADV_HUGEPAGE = 1 shl 4

    enum class ThreadRole(val code: Int) {
        OWNER_LOOP(0),
        AUDIO_IO(1),
    }

    internal class Binding(
        val libraryPath: Path,
        @Suppress("unused") val arena: Arena,
        val capabilities: Int,
        val fnMonotonicNanos: MethodHandle,
        val fnTuneProcess: MethodHandle,
        val fnTuneCurrentThread: MethodHandle,
        val fnAcquireTimerPeriod: MethodHandle,
        val fnReleaseTimerPeriod: MethodHandle,
        val fnSleepNanos: MethodHandle,
        val fnWaiterCreate: MethodHandle,
        val fnWaiterReset: MethodHandle,
        val fnWaiterWaitNanos: MethodHandle,
        val fnWaiterWake: MethodHandle,
        val fnWaiterDestroy: MethodHandle,
        val fnAdviseHugepage: MethodHandle,
    )

    /**
     * Lock-free reference-counted handle around a native interruptible high-res waiter
     * (Win64 `CreateWaitableTimerExW(HIGH_RESOLUTION)` + `Event`; Linux `eventfd` + `ppoll`).
     * Safe for concurrent [wake] and [close] while another thread is blocked in [waitNanos].
     */
    class Waiter internal constructor(
        private val binding: Binding,
        private val rawHandle: MemorySegment,
    ) : AutoCloseable {
        // Bit 30 = closed flag; low 30 bits = active in-flight native calls + 1 (owner ref).
        private val state = AtomicInteger(1)

        private fun tryAcquire(): Boolean {
            while (true) {
                val cur = state.get()
                if ((cur and CLOSED_BIT) != 0 || (cur and REF_MASK) == 0) return false
                if (state.compareAndSet(cur, cur + 1)) return true
            }
        }

        private fun releaseRef() {
            val after = state.decrementAndGet()
            if ((after and REF_MASK) == 0) {
                try {
                    binding.fnWaiterDestroy.invokeExact(rawHandle)
                } catch (_: Throwable) {
                    // Best-effort disposal.
                }
            }
        }

        fun reset() {
            if (!tryAcquire()) return
            try {
                binding.fnWaiterReset.invokeExact(rawHandle)
            } catch (_: Throwable) {
                // Best-effort reset
            } finally {
                releaseRef()
            }
        }

        /**
         * Blocks until [durationNanos] elapses or [wake] is called.
         * @return `0` on normal timeout, `1` if woken early, `-1` on error or closed handle.
         */
        fun waitNanos(durationNanos: Long): Int {
            if (durationNanos <= 0L) return 0
            if (!tryAcquire()) return -1
            return try {
                binding.fnWaiterWaitNanos.invokeExact(rawHandle, durationNanos) as Int
            } catch (_: Throwable) {
                -1
            } finally {
                releaseRef()
            }
        }

        /** Immediately interrupts any thread blocked in [waitNanos]. */
        fun wake() {
            if (!tryAcquire()) return
            try {
                binding.fnWaiterWake.invokeExact(rawHandle)
            } catch (_: Throwable) {
                // Best-effort wake
            } finally {
                releaseRef()
            }
        }

        override fun close() {
            while (true) {
                val cur = state.get()
                if ((cur and CLOSED_BIT) != 0) return
                if (state.compareAndSet(cur, cur or CLOSED_BIT)) {
                    // Keep the owner reference until the wake has finished using rawHandle.
                    try {
                        binding.fnWaiterWake.invokeExact(rawHandle)
                    } catch (_: Throwable) {
                        // A pending timed wait can still complete on its own.
                    } finally {
                        releaseRef()
                    }
                    return
                }
            }
        }

        private companion object {
            const val CLOSED_BIT = 1 shl 30
            const val REF_MASK = CLOSED_BIT - 1
        }
    }

    private val bindingRef = AtomicReference<Binding?>(null)
    private val autoInitLock = Any()
    @Volatile private var autoInitAttempted = false

    val isAvailable: Boolean
        get() = ensureBinding(null) != null

    val libraryPath: Path?
        get() = ensureBinding(null)?.libraryPath

    val capabilities: Int
        get() = ensureBinding(null)?.capabilities ?: 0

    fun initialize(runtimeHome: Path? = null): Boolean = ensureBinding(runtimeHome) != null

    fun loadFrom(libraryPath: Path): Boolean {
        val normalized = libraryPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized)) return false
        synchronized(autoInitLock) {
            if (bindingRef.get()?.libraryPath == normalized) return true
            val loaded = tryBind(normalized) ?: return false
            bindingRef.set(loaded)
            autoInitAttempted = true
            return true
        }
    }

    private fun ensureBinding(runtimeHome: Path?): Binding? {
        val existing = bindingRef.get()
        if (existing != null) return existing
        if (runtimeHome == null && autoInitAttempted) return null
        synchronized(autoInitLock) {
            bindingRef.get()?.let { return it }
            val candidate = findDefaultLibrary(runtimeHome)
            if (runtimeHome == null) autoInitAttempted = true
            if (candidate == null) {
                if (runtimeHome != null) {
                    log.warn("[FALLBACK][OS-SCHED] OS-native timer/scheduler library not found under {}; falling back to JVM timers", runtimeHome)
                }
                return null
            }
            val loaded = tryBind(candidate) ?: return null
            bindingRef.set(loaded)
            autoInitAttempted = true
            log.info(
                "Loaded OS-native timer/scheduler helpers from {} (caps=0x{})",
                loaded.libraryPath,
                loaded.capabilities.toString(16),
            )
            return loaded
        }
    }

    private fun tryBind(path: Path): Binding? {
        val arena = Arena.ofAuto()
        return try {
            val lookup = SymbolLookup.libraryLookup(path, arena)
            val abiSym = lookup.find("fuji_os_abi_version").orElse(null)
            if (abiSym == null) {
                log.warn("[FALLBACK][OS-SCHED] Missing fuji_os_abi_version in {}; falling back to JVM timers", path)
                return null
            }
            val linker = Linker.nativeLinker()
            val criticalLeaf = Linker.Option.critical(false)
            val fnAbi = linker.downcallHandle(abiSym, FunctionDescriptor.of(ValueLayout.JAVA_INT), criticalLeaf)
            val abi = fnAbi.invokeExact() as Int
            if (abi != OS_ABI_VERSION) {
                log.warn("[FALLBACK][OS-SCHED] OS-native helper ABI mismatch in {}: expected {}, got {}; falling back to JVM timers", path, OS_ABI_VERSION, abi)
                return null
            }

            fun req(name: String): MemorySegment =
                lookup.find(name).orElseThrow { IllegalStateException("Missing symbol $name in $path") }

            val fnCaps = linker.downcallHandle(req("fuji_os_capabilities"), FunctionDescriptor.of(ValueLayout.JAVA_INT), criticalLeaf)
            val fnMono = linker.downcallHandle(req("fuji_os_monotonic_nanos"), FunctionDescriptor.of(ValueLayout.JAVA_LONG), criticalLeaf)
            val fnTuneProc = linker.downcallHandle(req("fuji_os_tune_process"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), criticalLeaf)
            val fnTuneThr = linker.downcallHandle(req("fuji_os_tune_current_thread"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), criticalLeaf)
            val fnAcqPeriod = linker.downcallHandle(req("fuji_os_acquire_timer_period"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), criticalLeaf)
            val fnRelPeriod = linker.downcallHandle(req("fuji_os_release_timer_period"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), criticalLeaf)
            // Blocking/allocating calls MUST NOT use critical(false) so JVM safepoints never stall.
            val fnSleep = linker.downcallHandle(req("fuji_os_sleep_nanos"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
            val fnWaiterCreate = linker.downcallHandle(req("fuji_os_waiter_create"), FunctionDescriptor.of(ValueLayout.ADDRESS))
            val fnWaiterReset = linker.downcallHandle(req("fuji_os_waiter_reset"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), criticalLeaf)
            val fnWaiterWait = linker.downcallHandle(req("fuji_os_waiter_wait_nanos"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))
            val fnWaiterWake = linker.downcallHandle(req("fuji_os_waiter_wake"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), criticalLeaf)
            val fnWaiterDestroy = linker.downcallHandle(req("fuji_os_waiter_destroy"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
            val fnAdviseHugepage = linker.downcallHandle(req("fuji_os_advise_hugepage"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), criticalLeaf)

            val caps = fnCaps.invokeExact() as Int
            Binding(
                libraryPath = path,
                arena = arena,
                capabilities = caps,
                fnMonotonicNanos = fnMono,
                fnTuneProcess = fnTuneProc,
                fnTuneCurrentThread = fnTuneThr,
                fnAcquireTimerPeriod = fnAcqPeriod,
                fnReleaseTimerPeriod = fnRelPeriod,
                fnSleepNanos = fnSleep,
                fnWaiterCreate = fnWaiterCreate,
                fnWaiterReset = fnWaiterReset,
                fnWaiterWaitNanos = fnWaiterWait,
                fnWaiterWake = fnWaiterWake,
                fnWaiterDestroy = fnWaiterDestroy,
                fnAdviseHugepage = fnAdviseHugepage,
            )
        } catch (error: Throwable) {
            log.warn("[FALLBACK][OS-SCHED] OS-native helper failed to load at {} ({}); falling back to JVM timers", path, error.message ?: error.javaClass.simpleName)
            null
        }
    }

    fun findDefaultLibrary(runtimeHome: Path? = null): Path? {
        val isWindows = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
        val fileName = if (isWindows) "fuji_audio.dll" else "libfuji_audio.so"
        val buildSubdir = if (isWindows) "build-win64" else "build-linux"

        val candidates = LinkedHashSet<Path>()
        fun addProp(raw: String?) {
            if (!raw.isNullOrBlank()) candidates.add(Path.of(raw.trim()))
        }
        addProp(System.getProperty("fuji.audio.library"))
        addProp(System.getenv("FUJI_AUDIO_LIBRARY"))
        if (isWindows) addProp(System.getenv("FUJI_WIN64_AUDIO_DLL"))

        fun addBase(base: Path?) {
            if (base == null) return
            val norm = base.toAbsolutePath().normalize()
            candidates.add(norm.resolve("runtime").resolve("native").resolve(fileName))
            candidates.add(norm.resolve("native").resolve(fileName))
            candidates.add(norm.resolve(".tools").resolve("audio-bridge").resolve(buildSubdir).resolve(fileName))
            candidates.add(norm.resolve(".tools").resolve("audio-bridge").resolve("build").resolve(fileName))
        }

        addBase(runtimeHome)
        val cwd = Path.of("").toAbsolutePath().normalize()
        addBase(cwd)
        addBase(cwd.parent)
        addBase(cwd.parent?.parent)

        if (!isWindows) {
            candidates.add(Path.of("/opt/fuji-cache/audio-bridge-linux").resolve(fileName))
            candidates.add(Path.of("/opt/fuji-cache/audio-bridge").resolve(fileName))
        }

        return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
    }

    fun monotonicNanos(): Long {
        val b = ensureBinding(null) ?: return System.nanoTime()
        return try {
            b.fnMonotonicNanos.invokeExact() as Long
        } catch (_: Throwable) {
            System.nanoTime()
        }
    }

    /** Applies process-wide high-performance tuning (anti-EcoQoS + Win11 timer res on Windows; 1 ns timerslack on Linux). */
    fun tuneProcess(enableHighPerf: Boolean = true): Int {
        val b = ensureBinding(null) ?: return 0
        return try {
            b.fnTuneProcess.invokeExact(if (enableHighPerf) 1 else 0) as Int
        } catch (_: Throwable) {
            0
        }
    }

    /** Tunes the calling OS thread (P-core / ABOVE_NORMAL on Windows; 1 ns timerslack on Linux). */
    fun tuneCurrentThread(role: ThreadRole): Int {
        val b = ensureBinding(null) ?: return 0
        return try {
            b.fnTuneCurrentThread.invokeExact(role.code) as Int
        } catch (_: Throwable) {
            0
        }
    }

    fun acquireTimerPeriod(periodMs: Int = 1): Boolean {
        if (periodMs !in 1..15) return false
        val b = ensureBinding(null) ?: return false
        return try {
            (b.fnAcquireTimerPeriod.invokeExact(periodMs) as Int) == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun releaseTimerPeriod(periodMs: Int = 1): Boolean {
        if (periodMs !in 1..15) return false
        val b = ensureBinding(null) ?: return false
        return try {
            (b.fnReleaseTimerPeriod.invokeExact(periodMs) as Int) == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sleeps for [durationNanos] using the OS-native high-resolution timer
     * (`clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME)` on Linux;
     * `CreateWaitableTimerExW(HIGH_RESOLUTION)` on Windows).
     *
     * @return `true` if handled natively, `false` if unavailable so caller can fall back to JVM park/sleep.
     */
    fun sleepNanos(durationNanos: Long): Boolean {
        if (durationNanos <= 0L) return true
        val b = ensureBinding(null) ?: return false
        return try {
            (b.fnSleepNanos.invokeExact(durationNanos) as Int) == 0
        } catch (_: Throwable) {
            false
        }
    }

    /** Creates an interruptible OS-native high-resolution waiter, or `null` if native library is unavailable. */
    fun createWaiter(): Waiter? {
        val b = ensureBinding(null) ?: return null
        return try {
            val handle = b.fnWaiterCreate.invokeExact() as MemorySegment
            if (handle == MemorySegment.NULL) null else Waiter(b, handle)
        } catch (_: Throwable) {
            null
        }
    }

    /** Advises Transparent Huge Pages (`madvise(MADV_HUGEPAGE)`) on Linux; safe no-op returning `false` if unavailable. */
    fun adviseHugepage(segment: MemorySegment): Boolean {
        val len = segment.byteSize()
        if (len <= 0L || segment == MemorySegment.NULL) return false
        val b = ensureBinding(null) ?: return false
        return try {
            (b.fnAdviseHugepage.invokeExact(segment, len) as Int) == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun statusSummary(): Map<String, Any?> {
        val b = ensureBinding(null)
        return linkedMapOf(
            "available" to (b != null),
            "library" to b?.libraryPath?.toString(),
            "abiVersion" to if (b != null) OS_ABI_VERSION else 0,
            "capabilities" to (b?.capabilities ?: 0),
        )
    }
}
