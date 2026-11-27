package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Coherent async write-back + sequential read-ahead block cache wrapping any [SdBlockStore].
 *
 * - Writes enqueue 512-byte sectors into a bounded dirty-sector [TreeMap] (default 32 MiB) so
 *   the emulator owner thread never blocks on host disk I/O during 102MP RAW / Movie bursts.
 * - A dedicated daemon thread (`fuji-sd-io-writer-slotN`) drains dirty sectors in contiguous
 *   runs (up to 1 MiB per coalesced `delegate.write`) and executes deferred [flushIfConsistent]
 *   materialization outside the cache lock.
 * - Strict read-after-write coherence: reads always consult `dirtyMap` -> `inFlightMap` ->
 *   256 KiB sequential read-ahead cache -> `delegate` (guarded by a monotonic [cacheEpoch] to
 *   prevent stale read-ahead installation under concurrent flushes).
 * - Strict durability: [flush], [setReadOnly], [computeLogicalSha256], and [close] drain every
 *   dirty and in-flight sector synchronously and invoke `delegate.flush()` before returning.
 */
internal class AsyncBufferedBlockStore(
    private val delegate: SdBlockStore,
    private val slot: Int,
    private val maxDirtyBytes: Int = DEFAULT_MAX_DIRTY_BYTES,
    private val readAheadBytes: Int = DEFAULT_READ_AHEAD_BYTES,
    private val maxCoalesceBytes: Int = DEFAULT_MAX_COALESCE_BYTES,
) : SdBlockStore {
    companion object {
        const val DEFAULT_MAX_DIRTY_BYTES = 32 * 1024 * 1024 // 32 MiB bounded dirty cache
        const val DEFAULT_READ_AHEAD_BYTES = 256 * 1024      // 256 KiB sequential read-ahead
        const val DEFAULT_MAX_COALESCE_BYTES = 1024 * 1024   // 1 MiB max contiguous flush batch
        private const val COALESCE_WAIT_NANOS = 2_000_000L   // 2 ms write-combining window for small bursts
        private const val RANDOM_READ_PREFETCH_BYTES = 16 * 1024
        private val logger: Logger = Logger.getLogger(AsyncBufferedBlockStore::class.java.name)
    }

    init {
        require(maxDirtyBytes >= SECTOR_BYTES && maxDirtyBytes % SECTOR_BYTES == 0) {
            "maxDirtyBytes must be positive and 512-byte sector aligned"
        }
        require(readAheadBytes >= SECTOR_BYTES && readAheadBytes % SECTOR_BYTES == 0) {
            "readAheadBytes must be positive and 512-byte sector aligned"
        }
        require(maxCoalesceBytes >= SECTOR_BYTES && maxCoalesceBytes % SECTOR_BYTES == 0) {
            "maxCoalesceBytes must be positive and 512-byte sector aligned"
        }
    }

    private val maxDirtySectors: Int = maxDirtyBytes / SECTOR_BYTES
    private val maxCoalesceSectors: Int = maxCoalesceBytes / SECTOR_BYTES
    private val minCoalesceSectors: Int = minOf(maxCoalesceSectors, maxOf(1, minOf(512, maxDirtySectors / 4)))

    // Strict lock ordering: NEVER acquire delegateIoLock while holding cacheLock.
    private val delegateIoLock = ReentrantLock()
    private val cacheLock = ReentrantLock()
    private val workAvailable = cacheLock.newCondition()
    private val spaceAvailable = cacheLock.newCondition()
    private val drained = cacheLock.newCondition()

    private val dirtyMap = TreeMap<Long, ByteArray>()
    private val inFlightMap = HashMap<Long, ByteArray>()
    private var pendingConsistentFlush = false
    private var flushRequested = false
    private var writerBusy = false
    private var stopping = false
    private var closed = false
    private var asyncFailure: Throwable? = null
    private var cacheEpoch = 0L

    // Adaptive sequential read-ahead window (sector-aligned clean base bytes)
    private var readCacheStartLba = -1L
    private var readCacheSectorCount = 0
    private var readCacheBytes = ByteArray(0)
    private var lastReadMissEndLba = -1L

    // Diagnostics / telemetry counters (guarded by cacheLock)
    private var readHits = 0L
    private var readMisses = 0L
    private var coalescedWrites = 0L
    private var coalescedSectors = 0L
    private var bytesFlushed = 0L
    private var backpressureWaits = 0L
    private var syncFlushCount = 0L

    private val writerThread = Thread(::writerLoop, "fuji-sd-io-writer-slot$slot").apply {
        isDaemon = true
        start()
    }

    override val backendKind: String get() = delegate.backendKind
    override val capacityBytes: Long get() = delegate.capacityBytes
    override val readOnly: Boolean get() = delegate.readOnly
    override val isOpen: Boolean get() = !closed && delegate.isOpen

    private fun checkFailureLocked() {
        val failure = asyncFailure ?: return
        throw IllegalStateException("Async SD slot $slot background I/O failed: ${failure.message}", failure)
    }

    private fun writerLoop() {
        while (true) {
            var batchStartLba = -1L
            var batchBuffer: ByteArray? = null
            var batchSectorCount = 0
            var runConsistentFlush = false

            cacheLock.withLock {
                while (dirtyMap.isEmpty() && !pendingConsistentFlush && !stopping) {
                    writerBusy = false
                    flushRequested = false
                    if (inFlightMap.isEmpty()) {
                        drained.signalAll()
                    }
                    try {
                        workAvailable.await()
                    } catch (_: InterruptedException) {
                        if (stopping) break
                    }
                }
                // Brief write-combining window so bursts of 512B sectors coalesce into large sequential writes
                if (dirtyMap.isNotEmpty() && dirtyMap.size < minCoalesceSectors &&
                    !flushRequested && !pendingConsistentFlush && !stopping
                ) {
                    var remainingNanos = COALESCE_WAIT_NANOS
                    while (remainingNanos > 0L && dirtyMap.isNotEmpty() && dirtyMap.size < minCoalesceSectors &&
                        !flushRequested && !pendingConsistentFlush && !stopping
                    ) {
                        try {
                            remainingNanos = workAvailable.awaitNanos(remainingNanos)
                        } catch (_: InterruptedException) {
                            if (stopping) break
                        }
                    }
                }
                if (dirtyMap.isNotEmpty()) {
                    writerBusy = true
                    val first = dirtyMap.firstEntry()
                    val startLba = first.key
                    val runEntries = ArrayList<Pair<Long, ByteArray>>(minOf(dirtyMap.size, maxCoalesceSectors))
                    var expectedLba = startLba
                    while (runEntries.size < maxCoalesceSectors) {
                        val bytes = dirtyMap.remove(expectedLba) ?: break
                        inFlightMap[expectedLba] = bytes
                        runEntries.add(expectedLba to bytes)
                        expectedLba++
                    }
                    batchStartLba = startLba
                    batchSectorCount = runEntries.size
                    val assembled = ByteArray(batchSectorCount * SECTOR_BYTES)
                    for (i in 0 until batchSectorCount) {
                        System.arraycopy(runEntries[i].second, 0, assembled, i * SECTOR_BYTES, SECTOR_BYTES)
                    }
                    batchBuffer = assembled
                    spaceAvailable.signalAll()
                } else if (pendingConsistentFlush) {
                    writerBusy = true
                    pendingConsistentFlush = false
                    runConsistentFlush = true
                } else if (stopping) {
                    writerBusy = false
                    flushRequested = false
                    if (inFlightMap.isEmpty()) {
                        drained.signalAll()
                    }
                    return
                }
            }

            if (batchBuffer != null) {
                var failure: Throwable? = null
                try {
                    delegateIoLock.withLock {
                        if (delegate.isOpen && !delegate.readOnly) {
                            val written = delegate.write(batchStartLba * SECTOR_BYTES, ByteBuffer.wrap(batchBuffer))
                            check(written == batchBuffer.size) { "short async SD coalesced write ($written != ${batchBuffer.size})" }
                        }
                    }
                } catch (err: Throwable) {
                    failure = err
                    logger.log(Level.WARNING, "[SD-ASYNC-IO] Background write failed on slot $slot lba=$batchStartLba sectors=$batchSectorCount", err)
                }

                cacheLock.withLock {
                    for (i in 0 until batchSectorCount) {
                        inFlightMap.remove(batchStartLba + i)
                    }
                    cacheEpoch++
                    if (failure != null) {
                        if (asyncFailure == null) asyncFailure = failure
                    } else {
                        // A neighboring read may have prefetched these sectors before writeback.
                        // Publish the committed bytes while retiring the in-flight overlay.
                        val overlapStart = maxOf(batchStartLba, readCacheStartLba)
                        val overlapEnd = minOf(batchStartLba + batchSectorCount, readCacheStartLba + readCacheSectorCount)
                        if (overlapStart < overlapEnd) {
                            batchBuffer.copyInto(
                                readCacheBytes,
                                ((overlapStart - readCacheStartLba) * SECTOR_BYTES).toInt(),
                                ((overlapStart - batchStartLba) * SECTOR_BYTES).toInt(),
                                ((overlapEnd - batchStartLba) * SECTOR_BYTES).toInt(),
                            )
                        }
                        coalescedWrites++
                        coalescedSectors += batchSectorCount
                        bytesFlushed += batchBuffer.size.toLong()
                    }
                    if (dirtyMap.isEmpty() && !pendingConsistentFlush) {
                        writerBusy = false
                        drained.signalAll()
                    }
                    spaceAvailable.signalAll()
                }
            } else if (runConsistentFlush) {
                var failure: Throwable? = null
                try {
                    delegateIoLock.withLock {
                        if (delegate.isOpen && !delegate.readOnly) {
                            delegate.flushIfConsistent()
                        }
                    }
                } catch (err: Throwable) {
                    failure = err
                    logger.log(Level.WARNING, "[SD-ASYNC-IO] Background flushIfConsistent failed on slot $slot", err)
                }

                cacheLock.withLock {
                    if (failure != null && asyncFailure == null) {
                        asyncFailure = failure
                    }
                    if (dirtyMap.isEmpty() && !pendingConsistentFlush) {
                        writerBusy = false
                        drained.signalAll()
                    }
                }
            }
        }
    }

    private fun readCleanBaseSectorFromDelegate(lba: Long): ByteArray =
        delegateIoLock.withLock { delegate.readSector(lba) }

    private fun patchReadCacheLocked(lba: Long, sectorBytes: ByteArray) {
        val start = readCacheStartLba
        if (start >= 0L && lba >= start && lba < start + readCacheSectorCount) {
            val offset = ((lba - start) * SECTOR_BYTES).toInt()
            System.arraycopy(sectorBytes, 0, readCacheBytes, offset, SECTOR_BYTES)
        }
    }

    private fun readSectorCoherent(lba: Long, requestedBytesHint: Int): ByteArray {
        val out = ByteArray(SECTOR_BYTES)
        readIntoCoherent(lba * SECTOR_BYTES, ByteBuffer.wrap(out), requestedBytesHint)
        return out
    }

    private fun readIntoCoherent(offset: Long, target: ByteBuffer, requestedBytesHint: Int): Int {
        val total = target.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0

        var done = 0
        while (done < total) {
            val pos = offset + done
            val lba = pos / SECTOR_BYTES
            val inSec = (pos % SECTOR_BYTES).toInt()
            val chunk = minOf(total - done, SECTOR_BYTES - inSec)

            var served = false
            var epochBefore = 0L
            var fetchSectors = 0

            cacheLock.withLock {
                checkFailureLocked()
                val dirty = dirtyMap[lba]
                if (dirty != null) {
                    target.put(dirty, inSec, chunk)
                    readHits++
                    served = true
                } else {
                    val inFlight = inFlightMap[lba]
                    if (inFlight != null) {
                        target.put(inFlight, inSec, chunk)
                        readHits++
                        served = true
                    } else if (readCacheStartLba >= 0L && lba >= readCacheStartLba && lba < readCacheStartLba + readCacheSectorCount) {
                        // Serve as many contiguous clean cached sectors as possible in one shot
                        var span = chunk
                        var nextLba = lba + 1L
                        val cacheEnd = readCacheStartLba + readCacheSectorCount
                        while (done + span < total && nextLba < cacheEnd &&
                            !dirtyMap.containsKey(nextLba) && !inFlightMap.containsKey(nextLba)
                        ) {
                            val nextChunk = minOf(total - (done + span), SECTOR_BYTES)
                            span += nextChunk
                            nextLba++
                        }
                        val cacheOff = ((lba - readCacheStartLba) * SECTOR_BYTES).toInt() + inSec
                        target.put(readCacheBytes, cacheOff, span)
                        done += span
                        readHits++
                        return@withLock
                    } else {
                        readMisses++
                        epochBefore = cacheEpoch
                        val maxRemainingSectors = ((capacityBytes - lba * SECTOR_BYTES) / SECTOR_BYTES).coerceAtLeast(1L)
                        val isSequential = (lba == lastReadMissEndLba) ||
                            (readCacheStartLba >= 0L && lba == readCacheStartLba + readCacheSectorCount)
                        val desiredBytes = when {
                            isSequential || requestedBytesHint >= 4096 || (total - done) >= 4096 -> readAheadBytes
                            else -> RANDOM_READ_PREFETCH_BYTES
                        }
                        fetchSectors = minOf((desiredBytes / SECTOR_BYTES).toLong(), maxRemainingSectors).toInt().coerceAtLeast(1)
                    }
                }
            }

            if (served) {
                done += chunk
                continue
            }
            if (fetchSectors == 0) {
                // Multi-sector cached span was already consumed inside withLock
                continue
            }

            // Read-ahead fetch from delegate OUTSIDE cacheLock
            val fetchBytes = fetchSectors * SECTOR_BYTES
            val fetched = ByteArray(fetchBytes)
            delegateIoLock.withLock {
                val readCount = delegate.read(lba * SECTOR_BYTES, ByteBuffer.wrap(fetched))
                check(readCount == fetchBytes) { "short SD read-ahead ($readCount != $fetchBytes)" }
            }

            // Re-acquire cacheLock and verify epoch before installing read-ahead cache
            var retry = false
            cacheLock.withLock {
                checkFailureLocked()
                if (cacheEpoch == epochBefore) {
                    readCacheStartLba = lba
                    readCacheSectorCount = fetchSectors
                    readCacheBytes = fetched
                    lastReadMissEndLba = lba + fetchSectors
                    // Double-check dirty/inFlight (epoch unchanged so lba is clean)
                    target.put(fetched, inSec, chunk)
                } else {
                    // Concurrent write or flush completed during delegate.read; retry coherently
                    val latest = dirtyMap[lba] ?: inFlightMap[lba]
                    if (latest != null) {
                        target.put(latest, inSec, chunk)
                    } else {
                        retry = true
                    }
                }
            }
            if (!retry) {
                done += chunk
            }
        }
        return done
    }

    override fun read(offset: Long, target: ByteBuffer): Int =
        readIntoCoherent(offset, target, target.remaining())

    override fun readSector(sectorIndex: Long): ByteArray {
        checkRange(sectorIndex * SECTOR_BYTES, SECTOR_BYTES.toLong())
        return readSectorCoherent(sectorIndex, SECTOR_BYTES)
    }

    override fun write(offset: Long, source: ByteBuffer): Int {
        require(!readOnly) { "SD slot write-protected" }
        val total = source.remaining()
        checkRange(offset, total.toLong())
        if (total == 0) return 0

        var done = 0
        while (done < total) {
            val pos = offset + done
            val lba = pos / SECTOR_BYTES
            val inSec = (pos % SECTOR_BYTES).toInt()
            val chunk = minOf(total - done, SECTOR_BYTES - inSec)

            if (inSec == 0 && chunk == SECTOR_BYTES) {
                // Whole-sector fast path: batch consecutive whole sectors under a single cacheLock acquisition
                val remainingWhole = (total - done) / SECTOR_BYTES
                val batchMax = minOf(remainingWhole, maxOf(1, maxDirtySectors / 4)).coerceAtLeast(1)
                val staged = Array(batchMax) {
                    ByteArray(SECTOR_BYTES).also { sec -> source.get(sec, 0, SECTOR_BYTES) }
                }
                cacheLock.withLock {
                    checkFailureLocked()
                    while (dirtyMap.size + inFlightMap.size >= maxDirtySectors && !stopping) {
                        backpressureWaits++
                        workAvailable.signal()
                        spaceAvailable.await()
                        checkFailureLocked()
                    }
                    val wasEmpty = dirtyMap.isEmpty()
                    for (i in 0 until batchMax) {
                        val targetLba = lba + i
                        val sec = staged[i]
                        dirtyMap[targetLba] = sec
                        patchReadCacheLocked(targetLba, sec)
                    }
                    cacheEpoch++
                    if (wasEmpty || dirtyMap.size >= minCoalesceSectors) {
                        workAvailable.signal()
                    }
                }
                done += batchMax * SECTOR_BYTES
            } else {
                // Partial-sector read-modify-write
                val patchBytes = ByteArray(chunk)
                source.get(patchBytes, 0, chunk)
                var baseSector: ByteArray? = null
                while (true) {
                    var needDelegateRead = false
                    cacheLock.withLock {
                        checkFailureLocked()
                        while (dirtyMap.size + inFlightMap.size >= maxDirtySectors && !dirtyMap.containsKey(lba) && !stopping) {
                            backpressureWaits++
                            workAvailable.signal()
                            spaceAvailable.await()
                            checkFailureLocked()
                        }
                        val existing = dirtyMap[lba] ?: inFlightMap[lba] ?: readCachedSectorLocked(lba) ?: baseSector
                        if (existing != null) {
                            val updated = existing.copyOf()
                            System.arraycopy(patchBytes, 0, updated, inSec, chunk)
                            dirtyMap[lba] = updated
                            patchReadCacheLocked(lba, updated)
                            cacheEpoch++
                            workAvailable.signal()
                            return@withLock
                        } else {
                            needDelegateRead = true
                        }
                    }
                    if (!needDelegateRead) break
                    baseSector = readCleanBaseSectorFromDelegate(lba)
                }
                done += chunk
            }
        }
        return done
    }

    private fun readCachedSectorLocked(lba: Long): ByteArray? {
        val start = readCacheStartLba
        if (start < 0L || lba < start || lba >= start + readCacheSectorCount) return null
        val off = ((lba - start) * SECTOR_BYTES).toInt()
        return readCacheBytes.copyOfRange(off, off + SECTOR_BYTES)
    }

    override fun writeSector(sectorIndex: Long, sectorBytes: ByteArray) {
        require(!readOnly) { "SD slot write-protected" }
        require(sectorBytes.size == SECTOR_BYTES)
        checkRange(sectorIndex * SECTOR_BYTES, SECTOR_BYTES.toLong())
        val copy = sectorBytes.copyOf()
        cacheLock.withLock {
            checkFailureLocked()
            while (dirtyMap.size + inFlightMap.size >= maxDirtySectors && !dirtyMap.containsKey(sectorIndex) && !stopping) {
                backpressureWaits++
                workAvailable.signal()
                spaceAvailable.await()
                checkFailureLocked()
            }
            val wasEmpty = dirtyMap.isEmpty()
            dirtyMap[sectorIndex] = copy
            patchReadCacheLocked(sectorIndex, copy)
            cacheEpoch++
            if (wasEmpty || dirtyMap.size >= minCoalesceSectors) {
                workAvailable.signal()
            }
        }
    }

    override fun canWrite(): Boolean = delegateIoLock.withLock { delegate.canWrite() }

    override fun setReadOnly(newReadOnly: Boolean) {
        if (newReadOnly) {
            flush()
        }
        delegateIoLock.withLock {
            delegate.setReadOnly(newReadOnly)
        }
        cacheLock.withLock {
            readCacheStartLba = -1L
            readCacheSectorCount = 0
            cacheEpoch++
        }
    }

    override fun flush() {
        while (true) {
            cacheLock.withLock {
                checkFailureLocked()
                pendingConsistentFlush = false
                if (dirtyMap.isEmpty() && inFlightMap.isEmpty() && !writerBusy) {
                    syncFlushCount++
                    return@withLock
                }
                flushRequested = true
                workAvailable.signal()
                while ((dirtyMap.isNotEmpty() || inFlightMap.isNotEmpty() || writerBusy) && asyncFailure == null) {
                    drained.await()
                }
                flushRequested = false
                checkFailureLocked()
                syncFlushCount++
            }
            break
        }
        delegateIoLock.withLock {
            if (delegate.isOpen && !delegate.readOnly) {
                delegate.flush()
            }
        }
    }

    override fun flushIfConsistent() {
        if (readOnly) return
        cacheLock.withLock {
            checkFailureLocked()
            pendingConsistentFlush = true
            workAvailable.signal()
        }
    }

    override fun invalidateDigestCache() {
        delegateIoLock.withLock { delegate.invalidateDigestCache() }
    }

    override fun computeLogicalSha256(
        sectorOverrides: Map<Long, ByteArray>,
        baselineSectors: Map<Long, ByteArray>,
    ): String {
        flush()
        return delegateIoLock.withLock {
            delegate.computeLogicalSha256(sectorOverrides, baselineSectors)
        }
    }

    override fun diagnostics(): Map<String, Any?> {
        val base = delegateIoLock.withLock { delegate.diagnostics() }
        val asyncStats = cacheLock.withLock {
            linkedMapOf<String, Any?>(
                "asyncIo" to true,
                "cacheDirtySectors" to (dirtyMap.size + inFlightMap.size),
                "cacheMaxDirtyBytes" to maxDirtyBytes,
                "cacheReadAheadBytes" to readAheadBytes,
                "cacheReadHits" to readHits,
                "cacheReadMisses" to readMisses,
                "cacheCoalescedWrites" to coalescedWrites,
                "cacheCoalescedSectors" to coalescedSectors,
                "cacheBytesFlushed" to bytesFlushed,
                "cacheBackpressureWaits" to backpressureWaits,
                "cacheSyncFlushes" to syncFlushCount,
            )
        }
        return base + asyncStats
    }

    override fun close() {
        if (closed) return
        var closeFailure: Throwable? = null
        try {
            flush()
        } catch (err: Throwable) {
            closeFailure = err
        } finally {
            cacheLock.withLock {
                stopping = true
                workAvailable.signalAll()
            }
            runCatching { writerThread.join(10_000L) }
            delegateIoLock.withLock {
                runCatching { delegate.close() }.onFailure { if (closeFailure == null) closeFailure = it }
            }
            cacheLock.withLock {
                dirtyMap.clear()
                inFlightMap.clear()
                readCacheStartLba = -1L
                readCacheSectorCount = 0
                readCacheBytes = ByteArray(0)
                closed = true
                if (closeFailure == null) closeFailure = asyncFailure
            }
        }
        closeFailure?.let { throw it }
    }
}
