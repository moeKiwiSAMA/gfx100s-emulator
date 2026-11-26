package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*

class Qcow2BlockStoreTest {
    @TempDir
    lateinit var tempRoot: Path

    private class TestContext(override val directory: Path) : ComponentContext {
        val registeredPorts = linkedMapOf<String, Any>()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(type.isInstance(port) && registeredPorts.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) { registeredPorts.clear() }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { resolved ->
                require(resolved.startsWith(directory) && resolved != directory)
            }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Test
    fun createReadWriteCrossClusterSparseAndReopenAcrossCustomCapacities() {
        val smallImg = tempRoot.resolve("card-8m.qcow2")
        val cap8m = 8L * 1024L * 1024L
        Qcow2BlockStore.create(smallImg, cap8m)
        assertTrue(Files.size(smallImg) <= 256L * 1024L, "Fresh 8 MiB QCOW2 should be <= 256 KiB")

        val expectedRaw = ByteArray(cap8m.toInt())
        Qcow2BlockStore.open(smallImg, tempRoot, readOnly = false, expectedCapacityBytes = cap8m).use { store ->
            assertEquals(cap8m, store.capacityBytes)
            assertEquals(sha256(expectedRaw), store.computeLogicalSha256())

            // Writing zeros to an unallocated non-backed cluster keeps image maximally sparse
            val sizeBeforeZeroWrite = Files.size(smallImg)
            assertEquals(4096, store.write(128L * 1024L, ByteBuffer.wrap(ByteArray(4096))))
            assertEquals(sizeBeforeZeroWrite, Files.size(smallImg))

            // Cross-cluster unaligned write spanning cluster 0 -> cluster 1 -> cluster 2
            val pattern = ByteArray(70_000) { idx -> ((idx * 31 + 17) and 0xFF).toByte() }
            val startOff = 65_000L
            assertEquals(pattern.size, store.write(startOff, ByteBuffer.wrap(pattern)))
            System.arraycopy(pattern, 0, expectedRaw, startOff.toInt(), pattern.size)

            // Write near end of disk (triggers a second L2/higher cluster)
            val tailPattern = ByteArray(1536) { idx -> (0xA5 xor idx).toByte() }
            val tailOff = cap8m - 2048L
            assertEquals(tailPattern.size, store.write(tailOff, ByteBuffer.wrap(tailPattern)))
            System.arraycopy(tailPattern, 0, expectedRaw, tailOff.toInt(), tailPattern.size)

            // Read back across boundaries
            val readBack = ByteBuffer.allocate(pattern.size)
            assertEquals(pattern.size, store.read(startOff, readBack))
            assertContentEquals(pattern, readBack.array())
            assertEquals(sha256(expectedRaw), store.computeLogicalSha256())
        }

        // Reopen and confirm exact persistence & logical SHA-256
        Qcow2BlockStore.open(smallImg, tempRoot, readOnly = true, expectedCapacityBytes = cap8m).use { reopened ->
            assertEquals(sha256(expectedRaw), reopened.computeLogicalSha256())
            val full = ByteBuffer.allocate(cap8m.toInt())
            assertEquals(cap8m.toInt(), reopened.read(0L, full))
            assertContentEquals(expectedRaw, full.array())
        }

        // Large 64 GiB virtual QCOW2 stays tiny on disk and hashes quickly
        val largeImg = tempRoot.resolve("card-64g.qcow2")
        val cap64g = 64L * 1024L * 1024L * 1024L
        Qcow2BlockStore.create(largeImg, cap64g)
        assertTrue(Files.size(largeImg) <= 512L * 1024L)
        Qcow2BlockStore.open(largeImg, tempRoot, readOnly = false, expectedCapacityBytes = cap64g).use { big ->
            val payload = ByteArray(1024) { 0x5C }
            assertEquals(1024, big.write(cap64g - 4096L, ByteBuffer.wrap(payload)))
            val back = ByteBuffer.allocate(1024)
            assertEquals(1024, big.read(cap64g - 4096L, back))
            assertContentEquals(payload, back.array())
            assertEquals(64, big.computeLogicalSha256().length)
        }
    }

    @Test
    fun readOnlyRawAndQcow2BackingFilesSupportCowOverlayAndZeroMasking() {
        val cap = 2L * 1024L * 1024L
        val rawBase = tempRoot.resolve("base.img")
        val baseBytes = ByteArray(cap.toInt()) { idx -> ((idx / 512 + 0x11) and 0xFF).toByte() }
        Files.write(rawBase, baseBytes)

        // Layer 1: QCOW2 overlay on top of raw base
        val midOverlay = tempRoot.resolve("mid.qcow2")
        Qcow2BlockStore.create(midOverlay, cap, backingFileRelative = "base.img", backingFormat = "raw")
        Qcow2BlockStore.open(midOverlay, tempRoot, readOnly = false).use { mid ->
            // Reads fall through to raw backing file
            val sec0 = mid.readSector(0L)
            assertContentEquals(baseBytes.copyOfRange(0, 512), sec0)

            // Overwrite cluster 1 (offset 64 KiB) with custom data
            val patch = ByteArray(512) { 0xEE.toByte() }
            mid.writeSector(128L, patch)
            assertContentEquals(patch, mid.readSector(128L))

            // Mask entire cluster 2 (offset 128 KiB, 64 KiB long) with zeros -> uses zero flag without mutating base
            val sizeBeforeMask = Files.size(midOverlay)
            assertEquals(65536, mid.write(128L * 1024L, ByteBuffer.wrap(ByteArray(65536))))
            assertEquals(sizeBeforeMask, Files.size(midOverlay), "Zero-masking backing cluster should not allocate data cluster")
            assertContentEquals(ByteArray(512), mid.readSector(256L))
        }
        // Raw base MUST be byte-for-byte untouched
        assertContentEquals(baseBytes, Files.readAllBytes(rawBase))

        // Layer 2: QCOW2 overlay on top of mid.qcow2 (chained qcow2 -> qcow2 -> raw)
        val topOverlay = tempRoot.resolve("top.qcow2")
        Qcow2BlockStore.create(topOverlay, cap, backingFileRelative = "mid.qcow2", backingFormat = "qcow2")
        Qcow2BlockStore.open(topOverlay, tempRoot, readOnly = true).use { top ->
            assertContentEquals(baseBytes.copyOfRange(0, 512), top.readSector(0L))
            assertContentEquals(ByteArray(512) { 0xEE.toByte() }, top.readSector(128L))
            assertContentEquals(ByteArray(512), top.readSector(256L))
        }
    }

    @Test
    fun sdcardFactoryQcow2SlotSupportsSnapshotRollbackAndRejectsTampering() {
        val dirA = tempRoot.resolve("snap-a").also { Files.createDirectories(it) }
        val dirB = tempRoot.resolve("snap-b").also { Files.createDirectories(it) }
        val cap = 1024L * 1024L
        Qcow2BlockStore.create(dirA.resolve("slot1.qcow2"), cap)
        Qcow2BlockStore.create(dirB.resolve("slot1.qcow2"), cap)

        val factory = SdcardFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "slot1Image" to "slot1.qcow2",
            "slot1Inserted" to true,
            "slot1ReadOnly" to false,
            "slot1Backend" to "qcow2",
            "slot1CapacityBytes" to cap,
        )

        lateinit var snapT1: Map<String, Any?>
        val dataT1 = ByteArray(1024) { idx -> (idx xor 0x33).toByte() }
        val dataT2 = ByteArray(512) { 0x77 }
        factory.create(TestContext(dirA), cfg).use { compA ->
            val portA = compA as CheckedBlockStoragePort
            val statefulA = compA as StatefulComponent
            assertEquals(cap, portA.size(1))
            portA.write(1, 512L, ByteBuffer.wrap(dataT1))
            snapT1 = statefulA.saveSnapshot()

            // Mutate after snapshot, then roll back to T1
            portA.write(1, 65536L, ByteBuffer.wrap(dataT2))
            statefulA.restoreSnapshot(snapT1)
            val checkT1 = ByteBuffer.allocate(1024)
            portA.read(1, 512L, checkT1)
            assertContentEquals(dataT1, checkT1.array())
            val checkCleared = ByteBuffer.allocate(512)
            portA.read(1, 65536L, checkCleared)
            assertContentEquals(ByteArray(512), checkCleared.array())
        }

        // Restore T1 snapshot onto clean dirB QCOW2
        factory.create(TestContext(dirB), cfg).use { compB ->
            val portB = compB as CheckedBlockStoragePort
            val statefulB = compB as StatefulComponent
            statefulB.restoreSnapshot(snapT1)
            val readB = ByteBuffer.allocate(1024)
            portB.read(1, 512L, readB)
            assertContentEquals(dataT1, readB.array())
        }
    }

    @Test
    fun qemuImgInteropCheckWhenAvailable() {
        val qemuImg = listOf("/usr/bin/qemu-img", "/usr/local/bin/qemu-img").firstOrNull { Files.isExecutable(Path.of(it)) }
            ?: return
        val img = tempRoot.resolve("interop.qcow2")
        val cap = 4L * 1024L * 1024L
        Qcow2BlockStore.create(img, cap)
        Qcow2BlockStore.open(img, tempRoot, readOnly = false).use { store ->
            store.write(0L, ByteBuffer.wrap(ByteArray(1024) { 0x42 }))
            store.write(2L * 1024L * 1024L, ByteBuffer.wrap(ByteArray(70_000) { (it and 0xFF).toByte() }))
        }
        val checkProc = ProcessBuilder(qemuImg, "check", img.toString()).redirectErrorStream(true).start()
        val checkOut = checkProc.inputStream.bufferedReader().readText()
        assertEquals(0, checkProc.waitFor(), "qemu-img check failed:\n$checkOut")
    }
}
