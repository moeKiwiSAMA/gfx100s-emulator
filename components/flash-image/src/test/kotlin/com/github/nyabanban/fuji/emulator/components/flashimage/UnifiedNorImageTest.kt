package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*

class UnifiedNorImageTest {
    @TempDir
    lateinit var tempRoot: Path

    private class TestContext(
        override val directory: Path,
        override val relaxedMode: Boolean = true,
    ) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val clock = EmulationClock { 0L }
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { require(it.startsWith(directory)) }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null) { "Duplicate port $name" }
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
    }

    private fun createBlankNor(path: Path) {
        Files.createDirectories(path.parent)
        val block = ByteArray(1024 * 1024) { 0xFF.toByte() }
        Files.newOutputStream(path).use { out ->
            repeat(64) { out.write(block) }
        }
    }

    @Test
    fun unifiedNorSupportsFull64MiBReadEraseProgramAndPersistsAcrossReopen() {
        val norPath = tempRoot.resolve("flash/nor-flash.bin")
        createBlankNor(norPath)

        // Write a boot signature at 0x20000 and calibration signature at 0x03DC0000
        java.io.RandomAccessFile(norPath.toFile(), "rw").use { raf ->
            raf.seek(0x20000L)
            raf.write(byteArrayOf(0x12, 0x34, 0x56, 0x78))
            raf.seek(0x03DC0000L)
            raf.write(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        }

        val ctx = TestContext(tempRoot.resolve("comp-data"))
        // Register host.nor-flash port (NO firmware.update port!)
        ctx.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norPath
        }

        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "imageProfile" to UnifiedNorImage.PROFILE,
            "imageFile" to "ignored-when-host-port-present.bin",
            "writable" to true,
        )

        factory.create(ctx, cfg).use { comp ->
            assertNull(ctx.ports.find("firmware.update", UpdateDataPort::class.java))
            val ro = checkNotNull(ctx.ports.find("flash.image", ReadOnlyImagePort::class.java))
            val wr = checkNotNull(ctx.ports.find("flash.image.mutable", WritableImagePort::class.java))
            val pg = checkNotNull(ctx.ports.find("flash.image.program", ProgrammableImagePort::class.java))

            assertEquals(UnifiedNorImage.NOR_SIZE_BYTES, ro.length)
            assertEquals("unified-nor-flash", wr.snapshot().regionId)
            assertEquals(0L, wr.snapshot().offset)
            assertEquals(UnifiedNorImage.NOR_SIZE_BYTES, wr.snapshot().length)

            // Read firmware region @ 0x20000 and calibration region @ 0x03DC0000
            val bootBuf = ByteBuffer.allocate(4)
            ro.read(0x20000L, bootBuf)
            assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), bootBuf.array())

            val calBuf = ByteBuffer.allocate(4)
            ro.read(0x03DC0000L, calBuf)
            assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()), calBuf.array())

            // Program 256 bytes at firmware region 0x00100000 (currently 0xFF)
            val payload = ByteArray(256) { (it xor 0x5A).toByte() }
            pg.prepareProgram(0x00100000L, ByteBuffer.wrap(payload)).use { tx ->
                val receipt = tx.commit()
                assertEquals(0x00100000L, receipt.offset)
                assertEquals(256, receipt.size)
                assertTrue(receipt.changedBytes > 0L)
            }
            val verify = ByteBuffer.allocate(256)
            ro.read(0x00100000L, verify)
            assertContentEquals(payload, verify.array())

            // Real NOR 1-to-0 law: programming 0xFF back over non-0xFF without erasing MUST refuse
            assertFailsWith<IllegalArgumentException> {
                pg.prepareProgram(0x00100000L, ByteBuffer.wrap(ByteArray(256) { 0xFF.toByte() })).use { tx ->
                    tx.commit()
                }
            }

            // 4 KiB sector erase at 0x00100000 restores 0xFF and allows reprogramming
            wr.prepareErase(0x00100000L, 4096L).use { tx ->
                val receipt = tx.commit()
                assertEquals(4096L, receipt.size)
                assertTrue(receipt.changedBytes > 0L)
            }
            val erased = ByteBuffer.allocate(256)
            ro.read(0x00100000L, erased)
            assertTrue(erased.array().all { it == 0xFF.toByte() })

            // Reprogram with new pattern (also test calibration area 64 KiB erase + program)
            val newPayload = ByteArray(256) { (0xA0 + (it and 0x0F)).toByte() }
            pg.prepareProgram(0x00100000L, ByteBuffer.wrap(newPayload)).use { it.commit() }

            wr.prepareErase(0x03DC0000L, 65536L).use { it.commit() }
            val newCal = ByteArray(256) { 0x42.toByte() }
            pg.prepareProgram(0x03DC0000L, ByteBuffer.wrap(newCal)).use { it.commit() }

            val st = comp.state()
            assertEquals("raw-nor-image-v1", st["mode"])
            assertEquals(2L, (st["eraseCount"] as Number).toLong())
            assertEquals(3L, (st["programCount"] as Number).toLong())
        }

        // Reopen fresh instance and verify both firmware & calibration writes persisted to disk
        val ctx2 = TestContext(tempRoot.resolve("comp-data-2"))
        ctx2.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norPath
        }
        factory.create(ctx2, cfg).use {
            val ro2 = checkNotNull(ctx2.ports.find("flash.image", ReadOnlyImagePort::class.java))
            val b1 = ByteBuffer.allocate(256)
            ro2.read(0x00100000L, b1)
            assertContentEquals(ByteArray(256) { (0xA0 + (it and 0x0F)).toByte() }, b1.array())

            val b2 = ByteBuffer.allocate(256)
            ro2.read(0x03DC0000L, b2)
            assertContentEquals(ByteArray(256) { 0x42.toByte() }, b2.array())
        }
    }

    private fun sha256File(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return HexFormat.of().formatHex(md.digest())
    }

    @Test
    fun relaxedUnifiedNorDefers64MiBShaUntilExplicitQueryOrSnapshotWhileStrictStaysEager() {
        val norPath = tempRoot.resolve("flash/lazy-nor.bin")
        createBlankNor(norPath)

        // 1. Relaxed mode: zero 64 MiB SHA-256 at init, across 64 programPage + state() + snapshot() calls
        UnifiedNorImage(norPath, "lazy-nor.bin", writable = true, relaxedMode = true).use { nor ->
            assertEquals(0L, nor.fullShaComputeCount, "Relaxed init must not hash 64 MiB eagerly")
            assertTrue(nor.isSha256Dirty)
            assertNull(nor.cachedSha256OrNull())

            val payload = ByteArray(256) { (0x50 + (it and 0x0F)).toByte() }
            for (page in 0 until 64) {
                val receipt = nor.prepareProgram(0x0010_0000L + page * 256L, ByteBuffer.wrap(payload)).use { it.commit() }
                assertEquals(UnifiedNorImage.DEFERRED_SHA256, receipt.beforeSha256)
                assertEquals(UnifiedNorImage.DEFERRED_SHA256, receipt.afterSha256)
                assertEquals(UnifiedNorImage.DEFERRED_SHA256, receipt.payloadSha256)
                val snap = nor.snapshot()
                assertEquals(UnifiedNorImage.DEFERRED_SHA256, snap.currentSha256)
            }
            assertEquals(0L, nor.fullShaComputeCount, "Hot programPage/snapshot must never trigger 64 MiB SHA-256")
            assertTrue(nor.isSha256Dirty)

            // Explicit sha256 computes exact cryptographic digest ONCE and caches it
            val exactSha = nor.sha256
            assertEquals(1L, nor.fullShaComputeCount)
            assertFalse(nor.isSha256Dirty)
            assertEquals(exactSha, nor.cachedSha256OrNull())
            assertEquals(sha256File(norPath), exactSha)

            // Repeated queries when clean are O(1) cache hits
            repeat(16) {
                assertEquals(exactSha, nor.sha256)
                assertEquals(exactSha, nor.snapshot().currentSha256)
            }
            assertEquals(1L, nor.fullShaComputeCount)

            // Subsequent erase marks dirty without hashing
            nor.prepareErase(0x0010_0000L, 4096L).use { it.commit() }
            assertEquals(1L, nor.fullShaComputeCount)
            assertTrue(nor.isSha256Dirty)
            assertNull(nor.cachedSha256OrNull())

            // Re-query computes exact updated digest
            val afterEraseSha = nor.sha256
            assertEquals(2L, nor.fullShaComputeCount)
            assertNotEquals(exactSha, afterEraseSha)
            assertEquals(sha256File(norPath), afterEraseSha)
        }

        // 2. Factory integration: relaxed state() skips 64 MiB re-hash, saveSnapshot()/restoreSnapshot() enforces exact SHA
        val ctxRelaxed = TestContext(tempRoot.resolve("comp-relaxed"), relaxedMode = true)
        ctxRelaxed.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norPath
        }
        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "imageProfile" to UnifiedNorImage.PROFILE,
            "writable" to true,
        )
        factory.create(ctxRelaxed, cfg).use { comp ->
            val pg = checkNotNull(ctxRelaxed.ports.find("flash.image.program", ProgrammableImagePort::class.java))
            val ro = checkNotNull(ctxRelaxed.ports.find("flash.image", ReadOnlyImagePort::class.java))
            val lazyPort = assertIs<LazyDigestImagePort>(ro)

            assertTrue(lazyPort.isSha256Dirty)
            assertNull(comp.state()["sha256"])
            assertEquals(true, comp.state()["sha256Dirty"])

            pg.prepareProgram(0x0010_0000L, ByteBuffer.wrap(ByteArray(256) { 0x3C.toByte() })).use { it.commit() }
            assertNull(comp.state()["sha256"])

            // saveSnapshot() always computes exact SHA-256 and populates cache
            val snap = (comp as StatefulComponent).saveSnapshot()
            val expectedSha = sha256File(norPath)
            assertEquals(expectedSha, snap["sha256"])
            assertFalse(lazyPort.isSha256Dirty)
            assertEquals(expectedSha, comp.state()["sha256"])
            assertEquals(false, comp.state()["sha256Dirty"])

            // restoreSnapshot() validates exact SHA-256
            comp.restoreSnapshot(snap)
        }

        // 3. Strict mode: eager startup SHA, exact slice receipts, exact state()["sha256"]
        val ctxStrict = TestContext(tempRoot.resolve("comp-strict"), relaxedMode = false)
        ctxStrict.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norPath
        }
        factory.create(ctxStrict, cfg).use { comp ->
            val pg = checkNotNull(ctxStrict.ports.find("flash.image.program", ProgrammableImagePort::class.java))
            val wr = checkNotNull(ctxStrict.ports.find("flash.image.mutable", WritableImagePort::class.java))
            val initialSha = sha256File(norPath)
            assertEquals(initialSha, comp.state()["sha256"])
            assertEquals(initialSha, wr.snapshot().currentSha256)

            val receipt = pg.prepareProgram(0x0010_0100L, ByteBuffer.wrap(ByteArray(256) { 0x24.toByte() })).use { it.commit() }
            assertNotEquals(UnifiedNorImage.DEFERRED_SHA256, receipt.payloadSha256)
            assertNotEquals(receipt.beforeSha256, receipt.afterSha256)
            val updatedSha = sha256File(norPath)
            assertEquals(updatedSha, comp.state()["sha256"])
            assertEquals(updatedSha, wr.snapshot().currentSha256)
        }
    }

    @Test
    fun writableRegionLazyShaSkipsFullPartitionHashInRelaxedAndPreservesExactSnapshotContract() {
        val dir = tempRoot.resolve("wr-lazy")
        Files.createDirectories(dir)
        val offset = 0x03D8_0000L
        val seedBytes = ByteArray(512 * 1024) { 0xFF.toByte() }
        Files.write(dir.resolve("seed.img"), seedBytes)
        val seedSha = sha256File(dir.resolve("seed.img"))
        val identity = ImageRegionIdentity("cow", offset, seedBytes.size.toLong(), seedSha, "seed.img")
        val spec = mapOf<String, Any?>(
            "schema" to 1,
            "profile" to WritableRegion.PROGRAM_PROFILE,
            "regionId" to "cow",
            "file" to "copy.img",
            "mode" to "create",
            "sha256" to seedSha,
            "writableOffset" to offset,
            "writableLength" to seedBytes.size.toLong(),
            "programPageBytes" to 256,
            "programLaw" to WritableRegion.PROGRAM_LAW,
        )

        val relaxedCtx = TestContext(dir, relaxedMode = true)
        WritableRegion(relaxedCtx, spec, listOf(identity)) { at, dst ->
            dst.put(seedBytes, (at - offset).toInt(), dst.remaining())
        }.use { wr ->
            val pageData = ByteArray(256) { (0xA0 + (it and 0x0F)).toByte() }
            for (i in 0 until 32) {
                val r = wr.prepareProgram(offset + i * 256L, ByteBuffer.wrap(pageData)).use { it.commit() }
                assertEquals(WritableRegion.DEFERRED_SHA256, r.afterSha256)
                assertEquals(WritableRegion.DEFERRED_SHA256, wr.snapshot().currentSha256)
            }
            assertEquals(0L, wr.fullShaComputeCount, "Relaxed WritableRegion must not hash full 512 KiB partition during programPage/snapshot")
            assertTrue(wr.isShaDirty)

            val snap = wr.saveSnapshot()
            val expectedDiskSha = sha256File(dir.resolve("copy.img"))
            assertEquals(expectedDiskSha, snap["sha256"])
            assertEquals(1L, wr.fullShaComputeCount)
            assertFalse(wr.isShaDirty)
            assertEquals(expectedDiskSha, wr.snapshot().currentSha256)

            // Repeated saveSnapshot() when clean hits cache
            val snap2 = wr.saveSnapshot()
            assertEquals(expectedDiskSha, snap2["sha256"])
            assertEquals(1L, wr.fullShaComputeCount)
        }
    }

    @Test
    fun benchmarkRepeatedProgramPageAndStateLazyVsEager() {
        val norStrictPath = tempRoot.resolve("flash/bench-strict.bin")
        val norRelaxedPath = tempRoot.resolve("flash/bench-relaxed.bin")
        createBlankNor(norStrictPath)
        createBlankNor(norRelaxedPath)
        val factory = FlashImageFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "imageProfile" to UnifiedNorImage.PROFILE,
            "writable" to true,
        )
        val iterations = 16
        val pagePayload = ByteArray(256) { (it xor 0x33).toByte() }

        // Baseline (strict / eager state() full 64 MiB SHA-256 on every dirty state() query)
        val ctxStrict = TestContext(tempRoot.resolve("bench-strict-ctx"), relaxedMode = false)
        ctxStrict.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norStrictPath
        }
        val strictNanos = factory.create(ctxStrict, cfg).use { comp ->
            val pg = checkNotNull(ctxStrict.ports.find("flash.image.program", ProgrammableImagePort::class.java))
            val t0 = System.nanoTime()
            for (i in 0 until iterations) {
                pg.prepareProgram(0x0010_0000L + i * 256L, ByteBuffer.wrap(pagePayload)).use { it.commit() }
                assertNotNull(comp.state()["sha256"])
            }
            System.nanoTime() - t0
        }

        // Optimized (relaxed / lazy dirty-flagged SHA-256 skipping redundant 64 MiB hash in state())
        val ctxRelaxed = TestContext(tempRoot.resolve("bench-relaxed-ctx"), relaxedMode = true)
        ctxRelaxed.claims[NorFlashBackingPort.PORT_NAME] = object : NorFlashBackingPort {
            override val imagePath: Path = norRelaxedPath
        }
        val relaxedNanos = factory.create(ctxRelaxed, cfg).use { comp ->
            val pg = checkNotNull(ctxRelaxed.ports.find("flash.image.program", ProgrammableImagePort::class.java))
            val t0 = System.nanoTime()
            for (i in 0 until iterations) {
                pg.prepareProgram(0x0010_0000L + i * 256L, ByteBuffer.wrap(pagePayload)).use { it.commit() }
                assertEquals(true, comp.state()["sha256Dirty"])
            }
            System.nanoTime() - t0
        }

        val strictMs = strictNanos / 1e6
        val relaxedMs = relaxedNanos / 1e6
        val speedup = strictNanos.toDouble() / relaxedNanos.coerceAtLeast(1L).toDouble()
        println(
            "[W12-FLASH-LAZY-SHA BENCH] 64 MiB UnifiedNorImage ($iterations x programPage+state()): " +
                "eager(strict)=${"%.2f".format(strictMs)} ms vs lazy(relaxed)=${"%.2f".format(relaxedMs)} ms " +
                "(speedup=${"%.1f".format(speedup)}x)",
        )
        assertTrue(relaxedNanos < strictNanos, "Expected lazy relaxed programPage+state() to beat eager strict ($relaxedMs ms vs $strictMs ms)")
    }
}
