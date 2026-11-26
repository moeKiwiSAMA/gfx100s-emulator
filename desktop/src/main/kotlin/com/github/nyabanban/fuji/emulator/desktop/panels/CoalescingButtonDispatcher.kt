package com.github.nyabanban.fuji.emulator.desktop.panels

import java.util.concurrent.CompletableFuture

/**
 * Bounded zero-queue coalescing dispatcher for interactive button mutations.
 *
 * When the emulation owner thread is busy inside a frame/slice, rapid UI button events
 * overwrite stale un-drained transitions for the same key instead of piling up serialized
 * tasks in `OwnerLoop.queue`. At most one drain task is ever in flight on the owner thread,
 * and disk persistence is requested only when a latching switch (e.g. `power`, `movieMode`)
 * is part of the batch.
 */
class CoalescingButtonDispatcher(
    private val submitOwner: (() -> Unit) -> CompletableFuture<Unit>,
    private val applyBatchOnOwner: (changes: Map<String, Boolean>, persistToDisk: Boolean) -> Map<String, Any?>,
    private val latchingKeys: Set<String> = PanelCatalog.latchingButtonKeys,
    private val onError: (Throwable) -> Unit = {},
) {
    private val lock = Any()
    private val pendingChanges = LinkedHashMap<String, Boolean>()
    private val pendingFutures = ArrayList<CompletableFuture<Map<String, Any?>>>()
    private var drainInFlight = false

    @Volatile var submittedCount: Long = 0L; private set
    @Volatile var appliedBatchCount: Long = 0L; private set
    @Volatile var coalescedOverwrites: Long = 0L; private set

    fun submit(key: String, down: Boolean): CompletableFuture<Map<String, Any?>> {
        val future = CompletableFuture<Map<String, Any?>>()
        val needSchedule: Boolean
        synchronized(lock) {
            submittedCount++
            if (pendingChanges.put(key, down) != null) {
                coalescedOverwrites++
            }
            pendingFutures.add(future)
            needSchedule = !drainInFlight
            if (needSchedule) {
                drainInFlight = true
            }
        }
        if (needSchedule) {
            scheduleDrain()
        }
        return future
    }

    private fun scheduleDrain() {
        val ownerFuture = runCatching {
            submitOwner { drainOnOwner() }
        }.getOrElse { err ->
            failAllPending(err)
            return
        }
        ownerFuture.whenComplete { _, err ->
            if (err != null) {
                failAllPending(err)
            }
        }
    }

    private fun drainOnOwner() {
        while (true) {
            val batch: Map<String, Boolean>
            val futures: List<CompletableFuture<Map<String, Any?>>>
            synchronized(lock) {
                if (pendingChanges.isEmpty()) {
                    drainInFlight = false
                    return
                }
                batch = LinkedHashMap(pendingChanges)
                futures = ArrayList(pendingFutures)
                pendingChanges.clear()
                pendingFutures.clear()
            }
            val persistToDisk = batch.keys.any { it in latchingKeys }
            try {
                val result = applyBatchOnOwner(batch, persistToDisk)
                appliedBatchCount++
                for (i in futures.indices) {
                    futures[i].complete(result)
                }
            } catch (error: Throwable) {
                for (i in futures.indices) {
                    futures[i].completeExceptionally(error)
                }
                onError(error)
            }
        }
    }

    private fun failAllPending(error: Throwable) {
        val futures: List<CompletableFuture<Map<String, Any?>>>
        synchronized(lock) {
            futures = ArrayList(pendingFutures)
            pendingChanges.clear()
            pendingFutures.clear()
            drainInFlight = false
        }
        for (i in futures.indices) {
            futures[i].completeExceptionally(error)
        }
        if (futures.isNotEmpty()) {
            onError(error)
        }
    }
}
