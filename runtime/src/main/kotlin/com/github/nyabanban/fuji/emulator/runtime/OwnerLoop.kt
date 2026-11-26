package com.github.nyabanban.fuji.emulator.runtime

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A single owner for CPU, hardware and control mutations. Backpressure is explicit. */
class OwnerLoop(capacity: Int = 1024) : AutoCloseable {
    private val queue = ArrayBlockingQueue<Runnable>(capacity)
    @Volatile private var running = true
    @Volatile private var lastHeartbeatNanos = System.nanoTime()
    @Volatile var ownerThreadTuningFlags: Int = 0
        private set
    private val pendingCount = AtomicInteger(0)
    private val lock = Any()
    @Volatile var onExternalSubmit: (() -> Unit)? = null
    private val thread = Thread.ofPlatform().name("emulation-owner").start {
        ownerThreadTuningFlags = NativeOsScheduler.tuneCurrentThread(NativeOsScheduler.ThreadRole.OWNER_LOOP)
        while (running || queue.isNotEmpty()) {
            lastHeartbeatNanos = System.nanoTime()
            val task = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            pendingCount.decrementAndGet()
            task.run()
            lastHeartbeatNanos = System.nanoTime()
        }
    }
    val isRunning: Boolean get() = running
    val ownerThread: Thread get() = thread
    val heartbeatNanos: Long get() = lastHeartbeatNanos
    fun hasPending(): Boolean = pendingCount.get() > 0
    fun drainQueued() {
        requireOwner()
        while (true) {
            val task = queue.poll() ?: break
            pendingCount.decrementAndGet()
            task.run()
        }
    }
    fun <T> submit(action: () -> T): CompletableFuture<T> = submitInternal(action, notifyExternal = true)

    /**
     * Submits a stepping slice from the emulation runner loop without invoking [onExternalSubmit].
     * Prevents interactive [RealTimePacer] self-wake when the runner thread submits its own step batches.
     */
    fun <T> submitRunnerSlice(action: () -> T): CompletableFuture<T> = submitInternal(action, notifyExternal = false)

    private fun <T> submitInternal(action: () -> T, notifyExternal: Boolean): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        if (Thread.currentThread() === thread) {
            try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
        } else synchronized(lock) {
            if (!running || !queue.offer(Runnable {
                try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
            })) result.completeExceptionally(RejectedExecutionException("emulation control queue unavailable"))
            else {
                pendingCount.incrementAndGet()
                if (notifyExternal) onExternalSubmit?.invoke()
            }
        }
        return result
    }
    fun requireOwner() { check(Thread.currentThread() === thread) { "hardware access outside emulation owner" } }
    override fun close() {
        synchronized(lock) { running = false }
        if (Thread.currentThread() !== thread) thread.join()
    }
}
