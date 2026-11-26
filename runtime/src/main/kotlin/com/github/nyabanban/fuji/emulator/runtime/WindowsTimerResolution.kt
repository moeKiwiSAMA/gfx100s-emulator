package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raises the Windows multimedia timer resolution to 1 ms (`winmm.dll` `timeBeginPeriod(1)` /
 * `timeEndPeriod(1)`) via Java 25 FFM so `LockSupport.parkNanos` / `Thread.sleep` achieves ~1 ms
 * pacing instead of the default 15.625 ms Windows tick quantum.
 *
 * Safe zero-cost no-op on Linux/macOS and degrades gracefully if `winmm.dll` is unavailable.
 */
object WindowsTimerResolution {
    private val log = LoggerFactory.getLogger(WindowsTimerResolution::class.java)
    private const val TIMERR_NOERROR = 0

    class Handle internal constructor(
        val active: Boolean,
        val periodMs: Int,
        private val releaseAction: (() -> Unit)? = null,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!active) return
            if (!closed.compareAndSet(false, true)) return
            try {
                releaseAction?.invoke()
            } catch (error: Throwable) {
                log.warn("Failed to release Windows timer resolution (periodMs={})", periodMs, error)
            }
        }
    }

    private data class WinMmBinding(
        val arena: Arena,
        val timeBeginPeriod: MethodHandle,
        val timeEndPeriod: MethodHandle,
    )

    private val winmm: WinMmBinding? by lazy {
        if (!isWindowsOs(System.getProperty("os.name", ""))) {
            null
        } else {
            try {
                val arena = Arena.ofAuto()
                val lookup = SymbolLookup.libraryLookup("winmm.dll", arena)
                val linker = Linker.nativeLinker()
                val desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
                val beginSym = lookup.find("timeBeginPeriod").orElse(null)
                val endSym = lookup.find("timeEndPeriod").orElse(null)
                if (beginSym == null || endSym == null) {
                    log.warn("winmm.dll found but timeBeginPeriod/timeEndPeriod symbols missing")
                    null
                } else {
                    WinMmBinding(
                        arena = arena,
                        timeBeginPeriod = linker.downcallHandle(beginSym, desc),
                        timeEndPeriod = linker.downcallHandle(endSym, desc),
                    )
                }
            } catch (error: Throwable) {
                log.warn("Unable to bind winmm.dll timer resolution via FFM; falling back to OS default quantum", error)
                null
            }
        }
    }

    fun isWindowsOs(osName: String = System.getProperty("os.name", "")): Boolean =
        osName.lowercase(Locale.ROOT).contains("win")

    fun acquire(periodMs: Int = 1): Handle = acquireForOs(System.getProperty("os.name", ""), periodMs)

    internal fun acquireForOs(osName: String, periodMs: Int = 1): Handle {
        require(periodMs in 1..15) { "periodMs must be in 1..15" }
        if (!isWindowsOs(osName)) {
            return Handle(active = false, periodMs = 0)
        }
        if (NativeOsScheduler.isAvailable && NativeOsScheduler.acquireTimerPeriod(periodMs)) {
            log.debug("Windows multimedia timer resolution raised to {} ms via NativeOsScheduler", periodMs)
            return Handle(active = true, periodMs = periodMs) {
                if (!NativeOsScheduler.releaseTimerPeriod(periodMs)) {
                    log.warn("NativeOsScheduler releaseTimerPeriod({}) failed", periodMs)
                }
            }
        }
        val binding = winmm ?: return Handle(active = false, periodMs = 0)
        return try {
            val rc = binding.timeBeginPeriod.invokeExact(periodMs) as Int
            if (rc == TIMERR_NOERROR) {
                log.debug("Windows multimedia timer resolution raised to {} ms via winmm.dll", periodMs)
                Handle(active = true, periodMs = periodMs) {
                    val endRc = binding.timeEndPeriod.invokeExact(periodMs) as Int
                    if (endRc != TIMERR_NOERROR) {
                        log.warn("winmm.dll timeEndPeriod({}) returned {}", periodMs, endRc)
                    }
                }
            } else {
                log.warn("winmm.dll timeBeginPeriod({}) returned {}; continuing with default timer resolution", periodMs, rc)
                Handle(active = false, periodMs = 0)
            }
        } catch (error: Throwable) {
            log.warn("winmm.dll timeBeginPeriod({}) failed; continuing with default timer resolution", periodMs, error)
            Handle(active = false, periodMs = 0)
        }
    }
}
