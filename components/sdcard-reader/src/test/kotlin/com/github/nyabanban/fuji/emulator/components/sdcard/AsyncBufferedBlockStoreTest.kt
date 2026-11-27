package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Random
import kotlin.test.*

class AsyncBufferedBlockStoreTest {
    @TempDir
    lateinit var tempRoot: Path

    private class TestContext(override val directory: Path) : ComponentContext {
        var currentTick = 0L
        val registry = linkedMapOf<String, Any>()
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(registry.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registry[name]?.let(type::cast)
            override fun removeOwner(owner: String) { registry.clear() }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun createZeroImage(path: Path, sizeBytes: Long) {
        Files.createDirectories(path.parent)
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { ch ->
            ch.write(ByteBuffer.wrap(byteArrayOf(0)), sizeBytes - 1L)
        }
    }

    @Test
    fun readAheadDuringWritebackPreservesNewerSectorAfterDrain() {
        val image = tempRoot.resolve("prefetch-during-write.img")
        createZeroImage(image, 16L * SECTOR_BYTES)
        val raw = RawBlockStore(image, readOnly = false)
        lateinit var store: AsyncBufferedBlockStore
        val backend = object : SdBlockStore by raw {
            override fun write(offset: Long, source: ByteBuffer): Int {
                // Reenter the read path before the backing image has received sector 1.
                // The read of sector 0 also prefetches the still-old sector 1.
                assertContentEquals(ByteArray(SECTOR_BYTES), store.readSector(0))
                return raw.write(offset, source)
            }
        }
        val expected = ByteArray(SECTOR_BYTES) { 0x5a }
        store = AsyncBufferedBlockStore(backend, slot = 1)
        store.use {
            it.writeSector(1, expected)
            it.flush()
            assertContentEquals(expected, raw.readSector(1))
            assertContentEquals(expected, it.readSector(1))
        }
    }

    @Test
    fun interleavedSequentialRandomAndUnalignedReadAfterWriteRemains100PercentCoherent() {
        val img = tempRoot.resolve("coherence.img")
        val sizeBytes = 2 * 1024 * 1024 // 2 MiB
        val expected = ByteArray(sizeBytes) { ((it * 31 + 17) and 0xFF).toByte() }
        Files.write(img, expected)

        val raw = RawBlockStore(img, readOnly = false)
        // Small 64 KiB dirty cap + 32 KiB read-ahead forces frequent concurrent flushes, in-flight hits, and backpressure
        AsyncBufferedBlockStore(raw, slot = 1, maxDirtyBytes = 64 * 1024, readAheadBytes = 32 * 1024, maxCoalesceBytes = 16 * 1024).use { store ->
            val rng = Random(20261125L)

            // 1. Sequential 512B writes interleaved with immediate read-after-write checks
            for (lba in 0 until 256) {
                val sec = ByteArray(512) { ((lba * 7 + it * 3) and 0xFF).toByte() }
                System.arraycopy(sec, 0, expected, lba * 512, 512)
                assertEquals(512, store.write(lba * 512L, ByteBuffer.wrap(sec)))
                val got = store.readSector(lba.toLong())
                assertContentEquals(sec, got, "Immediate sequential RAW mismatch at lba=$lba")
            }

            // 2. 800 random aligned + unaligned overwrites interleaved with random reads (including in-flight overwrites!)
            for (iter in 0 until 800) {
                val len = if (iter % 3 == 0) 512 * (1 + rng.nextInt(8)) else 1 + rng.nextInt(3000)
                val off = rng.nextInt(sizeBytes - len)
                val payload = ByteArray(len) { ((iter * 13 + it * 5) and 0xFF).toByte() }
                System.arraycopy(payload, 0, expected, off, len)
                assertEquals(len, store.write(off.toLong(), ByteBuffer.wrap(payload)))

                // Verify immediate readback of written region
                val verify = ByteArray(len)
                assertEquals(len, store.read(off.toLong(), ByteBuffer.wrap(verify)))
                assertContentEquals(payload, verify, "Immediate random RAW mismatch at iter=$iter off=$off len=$len")

                // Also read another random window to exercise read-ahead + dirty overlay
                val probeLen = 1 + rng.nextInt(8192)
                val probeOff = rng.nextInt(sizeBytes - probeLen)
                val probe = ByteArray(probeLen)
                assertEquals(probeLen, store.read(probeOff.toLong(), ByteBuffer.wrap(probe)))
                assertContentEquals(
                    expected.copyOfRange(probeOff, probeOff + probeLen),
                    probe,
                    "Random probe mismatch at iter=$iter off=$probeOff len=$probeLen",
                )
            }

            val diag = store.diagnostics()
            assertEquals(true, diag["asyncIo"])
            assertTrue((diag["cacheCoalescedWrites"] as Long) > 0L)
            assertTrue((diag["cacheReadHits"] as Long) > 0L)
        }

        // After close(), disk image must match expected byte-for-byte
        val onDisk = Files.readAllBytes(img)
        assertContentEquals(expected, onDisk, "On-disk image must match expected byte-for-byte after close()")
    }

    @Test
    fun hundredMegabyteBurstWriteUnderBoundedCacheFlushesDurablyOnImmediateClose() {
        val img = tempRoot.resolve("burst-100mb.img")
        val totalBytes = 100L * 1024L * 1024L // 100 MiB (simulating 102MP RAW/Movie burst)
        createZeroImage(img, totalBytes)

        val chunkBytes = 64 * 1024 // 64 KiB chunks
        val chunks = (totalBytes / chunkBytes).toInt()
        val buf = ByteArray(chunkBytes)

        // Use a tight 4 MiB dirty cap so a 100 MiB burst exercises backpressure 25x without OOM
        val raw = RawBlockStore(img, readOnly = false)
        var coalescedWrites = 0L
        var bytesFlushed = 0L
        AsyncBufferedBlockStore(raw, slot = 1, maxDirtyBytes = 4 * 1024 * 1024, readAheadBytes = 256 * 1024).use { store ->
            for (c in 0 until chunks) {
                // Stamp unique deterministic pattern per 512B sector
                for (s in 0 until chunkBytes / 512) {
                    val lba = c * (chunkBytes / 512) + s
                    val base = s * 512
                    buf[base] = (lba and 0xFF).toByte()
                    buf[base + 1] = ((lba ushr 8) and 0xFF).toByte()
                    buf[base + 2] = ((lba ushr 16) and 0xFF).toByte()
                    buf[base + 3] = 0xA5.toByte()
                    buf[base + 511] = ((lba xor 0x5A) and 0xFF).toByte()
                }
                assertEquals(chunkBytes, store.write(c.toLong() * chunkBytes, ByteBuffer.wrap(buf)))
            }
            val diagBeforeClose = store.diagnostics()
            coalescedWrites = diagBeforeClose["cacheCoalescedWrites"] as Long
            bytesFlushed = diagBeforeClose["cacheBytesFlushed"] as Long
        }
        assertTrue(coalescedWrites > 0L, "Background writer should have coalesced writes during 100 MiB burst")
        assertTrue(bytesFlushed > 0L)

        // Immediately after close(), verify EVERY single 512B sector of all 100 MiB on host disk!
        FileChannel.open(img, StandardOpenOption.READ).use { ch ->
            val readBuf = ByteArray(chunkBytes)
            val bb = ByteBuffer.wrap(readBuf)
            for (c in 0 until chunks) {
                bb.clear()
                var done = 0
                while (bb.hasRemaining()) {
                    val n = ch.read(bb, c.toLong() * chunkBytes + done)
                    assertTrue(n > 0)
                    done += n
                }
                for (s in 0 until chunkBytes / 512) {
                    val lba = c * (chunkBytes / 512) + s
                    val base = s * 512
                    assertEquals((lba and 0xFF).toByte(), readBuf[base], "Disk byte 0 mismatch at lba=$lba")
                    assertEquals(((lba ushr 8) and 0xFF).toByte(), readBuf[base + 1], "Disk byte 1 mismatch at lba=$lba")
                    assertEquals(((lba ushr 16) and 0xFF).toByte(), readBuf[base + 2], "Disk byte 2 mismatch at lba=$lba")
                    assertEquals(0xA5.toByte(), readBuf[base + 3], "Disk magic mismatch at lba=$lba")
                    assertEquals(((lba xor 0x5A) and 0xFF).toByte(), readBuf[base + 511], "Disk tail mismatch at lba=$lba")
                }
            }
        }
    }

    @Test
    fun ejectReinsertFlushSafetyAndStrictAsyncIoFalseFallbackWorkCleanly() {
        val rawPath = tempRoot.resolve("hotplug-raw.img")
        createZeroImage(rawPath, 256L * 1024L)
        val ctx = TestContext(tempRoot)

        // 1. asyncIo=true: write -> ejectCard() must synchronously flush before returning
        val compAsync = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "hotplug-raw.img",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot2Inserted" to false,
                "asyncIo" to true,
            ),
        )
        compAsync.start()
        try {
            val storage = ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as CheckedBlockStoragePort
            val hotPlug = ctx.ports.find(SdcardFactory.PORT_HOTPLUG, HotPlugCardControlPort::class.java)!!
            val payload = ByteArray(16 * 512) { ((it * 19 + 3) and 0xFF).toByte() }
            assertEquals(payload.size, storage.write(1, 4096L, ByteBuffer.wrap(payload)))

            // Eject must flush synchronously to disk before card disappears
            hotPlug.ejectCard(1)
            val diskAfterEject = Files.readAllBytes(rawPath)
            assertContentEquals(payload, diskAfterEject.copyOfRange(4096, 4096 + payload.size))

            // Re-insert and verify coherent read-back + second write + live readOnly=true flush
            hotPlug.insertCard(1, readyDelayTicks = 0L)
            val readBack = ByteArray(payload.size)
            assertEquals(payload.size, storage.read(1, 4096L, ByteBuffer.wrap(readBack)))
            assertContentEquals(payload, readBack)

            val payload2 = ByteArray(4096) { 0x6C }
            assertEquals(4096, storage.write(1, 65536L, ByteBuffer.wrap(payload2)))
            compAsync.applySettings(mapOf("slot1ReadOnly" to true))
            val diskAfterRo = Files.readAllBytes(rawPath)
            assertContentEquals(payload2, diskAfterRo.copyOfRange(65536, 65536 + 4096))
        } finally {
            compAsync.close()
        }

        // 2. asyncIo=false strict fallback: synchronous writes with zero background cache diagnostics
        val strictCtx = TestContext(tempRoot)
        val compStrict = SdcardFactory().create(
            strictCtx,
            mapOf(
                "slot1Image" to "hotplug-raw.img",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot2Inserted" to false,
                "asyncIo" to false,
            ),
        )
        compStrict.start()
        try {
            val storage = strictCtx.ports.find("sdcard-reader", BlockStoragePort::class.java) as CheckedBlockStoragePort
            assertEquals(false, compStrict.state()["asyncIo"])
            val diag = compStrict.state()["slot1Diagnostics"] as Map<*, *>
            assertFalse(diag.containsKey("asyncIo"), "Strict asyncIo=false should bypass AsyncBufferedBlockStore")
            val syncPayload = ByteArray(512) { 0x3E }
            assertEquals(512, storage.write(1, 0L, ByteBuffer.wrap(syncPayload)))
            // Immediately visible on disk without explicit flush because asyncIo=false
            assertContentEquals(syncPayload, Files.readAllBytes(rawPath).copyOfRange(0, 512))
        } finally {
            compStrict.close()
        }
    }

    @Test
    fun benchmark64MiBSectorWritesSyncVsAsyncCoalescedThroughput() {
        val totalBytes = 64L * 1024L * 1024L // 64 MiB
        val sectorCount = (totalBytes / 512L).toInt()
        val syncImg = tempRoot.resolve("bench-sync.img")
        val asyncImg = tempRoot.resolve("bench-async.img")
        createZeroImage(syncImg, totalBytes)
        createZeroImage(asyncImg, totalBytes)

        val sectorData = ByteArray(512) { ((it * 7 + 1) and 0xFF).toByte() }
        val sectorBuf = ByteBuffer.wrap(sectorData)

        // Baseline: synchronous 512B sector writes (asyncIo=false)
        val syncStartNs = System.nanoTime()
        RawBlockStore(syncImg, readOnly = false).use { syncStore ->
            for (s in 0 until sectorCount) {
                sectorBuf.clear()
                syncStore.write(s.toLong() * 512L, sectorBuf)
            }
            syncStore.flush()
        }
        val syncElapsedMs = (System.nanoTime() - syncStartNs) / 1_000_000.0
        val syncMbps = 64.0 / (syncElapsedMs / 1000.0)

        // Optimized: AsyncBufferedBlockStore 512B sector writes (coalesced 1 MiB background writes)
        var asyncOwnerMs: Double
        var asyncTotalWithFlushMs: Double
        var coalescedWrites: Long
        val asyncStartNs = System.nanoTime()
        AsyncBufferedBlockStore(RawBlockStore(asyncImg, readOnly = false), slot = 1).use { asyncStore ->
            for (s in 0 until sectorCount) {
                sectorBuf.clear()
                asyncStore.write(s.toLong() * 512L, sectorBuf)
            }
            asyncOwnerMs = (System.nanoTime() - asyncStartNs) / 1_000_000.0
            asyncStore.flush()
            coalescedWrites = asyncStore.diagnostics()["cacheCoalescedWrites"] as Long
        }
        asyncTotalWithFlushMs = (System.nanoTime() - asyncStartNs) / 1_000_000.0
        val asyncOwnerMbps = 64.0 / (asyncOwnerMs / 1000.0)
        val asyncTotalMbps = 64.0 / (asyncTotalWithFlushMs / 1000.0)

        println(
            (
                "[W11-SD-BENCH] 64 MiB 512B-sector writes | " +
                    "SYNC: %.1f ms (%.1f MB/s) | " +
                    "ASYNC owner-thread: %.1f ms (%.1f MB/s) | " +
                    "ASYNC+flush: %.1f ms (%.1f MB/s) | coalescedWrites=%d (from %d sectors)"
                ).format(
                syncElapsedMs, syncMbps, asyncOwnerMs, asyncOwnerMbps,
                asyncTotalWithFlushMs, asyncTotalMbps, coalescedWrites, sectorCount,
            ),
        )
        assertTrue(coalescedWrites < sectorCount / 16L, "Expected strong write coalescing: $coalescedWrites vs $sectorCount")
        // Verify tail sector landed durably on disk
        FileChannel.open(asyncImg, StandardOpenOption.READ).use { ch ->
            val tail = ByteBuffer.allocate(512)
            assertEquals(512, ch.read(tail, totalBytes - 512L))
            assertContentEquals(sectorData, tail.array())
        }
    }

    @Test
    fun directoryBackendAsyncFlushIfConsistentMaterializesFileDurablyOnClose() {
        val dir = tempRoot.resolve("dir-async")
        Files.createDirectories(dir)
        val cap = 40L * 1024L * 1024L
        val ctx = TestContext(tempRoot)
        val comp = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "dir-async",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot1Backend" to "directory",
                "slot1CapacityBytes" to cap,
                "slot2Inserted" to false,
                "asyncIo" to true,
            ),
        )
        comp.start()
        val storage = ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as CheckedBlockStoragePort
        val mbr = ByteArray(512).also { storage.read(1, 0L, ByteBuffer.wrap(it)) }
        fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        fun putLe16(b: ByteArray, o: Int, v: Int) { b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte() }
        fun putLe32(b: ByteArray, o: Int, v: Int) {
            b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
            b[o + 2] = ((v ushr 16) and 0xFF).toByte(); b[o + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        val partStart = le32(mbr, 0x1BE + 8).toLong()
        val vbr = ByteArray(512).also { storage.read(1, partStart * 512L, ByteBuffer.wrap(it)) }
        val spc = vbr[0x0D].toInt() and 0xFF
        val reserved = le16(vbr, 0x0E)
        val numFats = vbr[0x10].toInt() and 0xFF
        val spf = le32(vbr, 0x24)
        val rootCluster = le32(vbr, 0x2C)
        val fat1Start = partStart + reserved
        val dataStart = partStart + reserved + numFats.toLong() * spf.toLong()

        val cluster = 250
        val payload = "Async FAT32 Directory Durability Test!".toByteArray(StandardCharsets.UTF_8)
        val dataSec = ByteArray(512).also { System.arraycopy(payload, 0, it, 0, payload.size) }
        storage.write(1, (dataStart + (cluster - 2L) * spc) * 512L, ByteBuffer.wrap(dataSec))

        val fatSecIdx = (cluster * 4) / 512
        val fatOff = (cluster * 4) % 512
        val fatSec = ByteArray(512).also { storage.read(1, (fat1Start + fatSecIdx) * 512L, ByteBuffer.wrap(it)) }
        putLe32(fatSec, fatOff, 0x0FFFFFFF)
        storage.write(1, (fat1Start + fatSecIdx) * 512L, ByteBuffer.wrap(fatSec))
        storage.write(1, (fat1Start + spf + fatSecIdx) * 512L, ByteBuffer.wrap(fatSec))

        val rootLba = dataStart + (rootCluster - 2L) * spc
        val rootSec = ByteArray(512).also { storage.read(1, rootLba * 512L, ByteBuffer.wrap(it)) }
        var off = 0
        while (off + 32 <= rootSec.size && rootSec[off] != 0.toByte()) off += 32
        "ASYNC001TXT".toByteArray(StandardCharsets.US_ASCII).copyInto(rootSec, off)
        rootSec[off + 11] = 0x20
        putLe16(rootSec, off + 20, (cluster ushr 16) and 0xFFFF)
        putLe16(rootSec, off + 26, cluster and 0xFFFF)
        putLe32(rootSec, off + 28, payload.size)
        storage.write(1, rootLba * 512L, ByteBuffer.wrap(rootSec))
        storage.flush(1)
        comp.close()

        val materialized = dir.resolve("ASYNC001.TXT")
        assertTrue(Files.isRegularFile(materialized), "Async directory backend must materialize file durably on close()")
        assertContentEquals(payload, Files.readAllBytes(materialized))
    }
}
