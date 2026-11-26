package com.github.nyabanban.fuji.emulator.components.lens.optics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Shared low-latency striped parallel worker pool for CPU optical simulation passes
 * (Brown-Conrady warp, thin-lens CoC bokeh, separable planar blur, lens flare, and CMOS Bayer
 * mosaicking).
 *
 * Uses daemon worker threads and runs stripe `0` directly on the calling thread so there is zero
 * wasted caller thread and zero shutdown blocking. Small workloads bypass the pool completely.
 */
object OpticalParallelExecutor {
    /**
     * Default worker stripe count bounded to avoid oversubscribing host cores and leave
     * dedicated headroom for Dynarmic ARM cores and the JavaFX UI thread on 4-8 core systems.
     */
    val defaultThreadCount: Int = run {
        val cpus = Runtime.getRuntime().availableProcessors()
        if (cpus <= 2) 1 else (cpus - 2).coerceIn(1, 6)
    }

    private val threadCounter = AtomicInteger(1)

    private val workerPool: ExecutorService? by lazy {
        val poolSize = (defaultThreadCount - 1).coerceAtLeast(0)
        if (poolSize <= 0) {
            null
        } else {
            Executors.newFixedThreadPool(poolSize) { runnable ->
                Thread(runnable, "fuji-optics-stripe-${threadCounter.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
        }
    }

    /**
     * Executes [task] over `[0, totalWork)` split into at most [threads] contiguous stripes.
     *
     * Every stripe boundary (except [totalWork] itself) is a multiple of [align] so Bayer CFA
     * row pairs (`align = 2`) stay aligned across stripes. Output ordering per element is strictly
     * independent, guaranteeing bit-exact parity with single-threaded execution.
     */
    fun parallelStripes(
        totalWork: Int,
        threads: Int = defaultThreadCount,
        minChunkSize: Int = 16,
        align: Int = 1,
        task: (start: Int, end: Int) -> Unit,
    ) {
        if (totalWork <= 0) return
        val safeAlign = align.coerceAtLeast(1)
        val effThreads = min(threads, defaultThreadCount).coerceAtLeast(1)
        val pool = if (effThreads > 1) workerPool else null
        if (pool == null || totalWork < minChunkSize * 2) {
            task(0, totalWork)
            return
        }

        val maxStripesByMinChunk = (totalWork / minChunkSize).coerceAtLeast(1)
        val numStripes = min(effThreads, maxStripesByMinChunk)
        if (numStripes <= 1) {
            task(0, totalWork)
            return
        }

        val rawChunk = (totalWork + numStripes - 1) / numStripes
        val alignedChunk = ((rawChunk + safeAlign - 1) / safeAlign) * safeAlign
        val actualStripes = ((totalWork + alignedChunk - 1) / alignedChunk).coerceAtLeast(1)
        if (actualStripes <= 1) {
            task(0, totalWork)
            return
        }

        val latch = CountDownLatch(actualStripes - 1)
        val firstError = AtomicReference<Throwable?>(null)

        for (s in 1 until actualStripes) {
            val start = s * alignedChunk
            val end = min(totalWork, start + alignedChunk)
            pool.execute {
                try {
                    if (firstError.get() == null) {
                        task(start, end)
                    }
                } catch (err: Throwable) {
                    firstError.compareAndSet(null, err)
                } finally {
                    latch.countDown()
                }
            }
        }

        try {
            task(0, min(totalWork, alignedChunk))
        } catch (err: Throwable) {
            firstError.compareAndSet(null, err)
        } finally {
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    break
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }

        val error = firstError.get()
        if (error != null) {
            throw error
        }
    }
}
