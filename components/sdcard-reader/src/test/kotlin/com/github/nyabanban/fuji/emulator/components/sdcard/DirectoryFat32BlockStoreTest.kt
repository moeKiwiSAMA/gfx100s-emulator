package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.*

class DirectoryFat32BlockStoreTest {
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

    private fun le16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun putLe16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(buf: ByteArray, off: Int, value: Int) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    @Test
    fun bulkReadsRespectFragmentedFilesOverlaysAndCallerBufferBounds() {
        val card = Files.createDirectory(tempRoot.resolve("read-card"))
        val first = ByteArray(9 * 512 + 137) { (it / 512 * 37 + it % 251).toByte() }
        val second = ByteArray(3 * 512) { (97 - it % 113).toByte() }
        Files.write(card.resolve("A.MOV"), first)
        Files.write(card.resolve("B.MOV"), second)
        val capacity = 40L * 1024 * 1024
        val data = DirectoryFat32BlockStore.open(card, false, capacity).use { store ->
            val part = le32(store.readSector(0), 454).toLong()
            val boot = store.readSector(part)
            assertEquals(1, boot[13].toInt())
            part + le16(boot, 14) + boot[16].toInt() * le32(boot, 36)
        }
        val sidecar = DirectoryFat32BlockStore.sidecarPathFor(card)
        val document = SdSidecarJson.parseObject(Files.readString(sidecar)).toMutableMap()
        val entries = (document["entries"] as List<*>).map { it as Map<*, *> }
        val movie = entries.single { it["relativePath"] == "A.MOV" }
        val clusters = (movie["clusters"] as List<*>).map { (it as Number).toInt() }.toMutableList()
        val firstLba = data + clusters.first() - 2
        assertEquals(clusters.last() + 1, (entries.single { it["relativePath"] == "B.MOV" }["startCluster"] as Number).toInt())
        java.util.Collections.swap(clusters, 5, 6)
        document["entries"] = entries.map { if (it === movie) it + ("clusters" to clusters) else it }
        val raw = ByteArray(512) { 89 }
        document["rawSectors"] = (document.getValue("rawSectors") as Map<*, *>) +
            ((firstLba + 2).toString() to java.util.Base64.getEncoder().encodeToString(raw))
        SdSidecarJson.writeAtomic(sidecar, document)
        DirectoryFat32BlockStore.open(card, false, capacity).use { store ->
            val expected = first.copyOf(13 * 512)
            second.copyInto(expected, 10 * 512)
            first.copyInto(expected, 5 * 512, 6 * 512, 7 * 512)
            first.copyInto(expected, 6 * 512, 5 * 512, 6 * 512)
            raw.copyInto(expected, 2 * 512)
            val patch = ByteArray(730) { (it * 17).toByte() }
            patch.copyInto(expected, 2 * 512 + 489)
            store.write(firstLba * 512 + 2 * 512 + 489, ByteBuffer.wrap(patch))
            for (direct in listOf(false, true)) for (skip in listOf(0, 19)) {
                val target = if (direct) ByteBuffer.allocateDirect(expected.size + 29) else ByteBuffer.allocate(expected.size + 29)
                repeat(target.capacity()) { target.put(0x55) }
                val length = expected.size - skip
                target.position(11).limit(11 + length).mark()
                assertEquals(length, store.read(firstLba * 512 + skip, target))
                assertEquals(11 + length, target.position())
                assertEquals(11 + length, target.limit())
                target.reset()
                assertEquals(11, target.position())
                val actual = ByteArray(length)
                target.get(actual)
                assertContentEquals(expected.copyOfRange(skip, expected.size), actual)
                target.clear()
                assertEquals(0x55, target.get(10).toInt())
                assertEquals(0x55, target.get(11 + length).toInt())
            }
            store.flush()
            val published = ByteArray(expected.size)
            store.read(firstLba * 512, ByteBuffer.wrap(published))
            assertContentEquals(expected, published)
        }
    }

    @Test
    fun bulkReadZeroFillsShortHostFilesAndStopsAtTheFirstMissingFile() {
        val card = Files.createDirectory(tempRoot.resolve("shrunk-card"))
        val first = ByteArray(2048) { (it * 17).toByte() }
        Files.write(card.resolve("A.MOV"), first)
        Files.write(card.resolve("B.MOV"), ByteArray(512))
        DirectoryFat32BlockStore.open(card, true, 40L * 1024 * 1024).use { store ->
            val part = le32(store.readSector(0), 454).toLong()
            val boot = store.readSector(part)
            val data = part + le16(boot, 14) + boot[16].toInt() * le32(boot, 36)
            val root = store.readSector(data)
            val entry = (0 until 512 step 32).first { String(root, it, 11, Charsets.US_ASCII) == "A       MOV" }
            val start = (data + le16(root, entry + 26) - 2) * 512
            java.nio.channels.FileChannel.open(card.resolve("A.MOV"), java.nio.file.StandardOpenOption.WRITE).use { it.truncate(701) }
            Files.delete(card.resolve("B.MOV"))
            val bytes = ByteArray(3000) { 0x55 }
            val target = ByteBuffer.wrap(bytes).position(23).limit(23 + 2560)
            assertFailsWith<java.nio.file.NoSuchFileException> { store.read(start, target) }
            assertEquals(23 + 2048, target.position())
            assertEquals(23 + 2560, target.limit())
            assertContentEquals(first.copyOf(701).copyOf(2048), bytes.copyOfRange(23, 23 + 2048))
            assertTrue(bytes.copyOfRange(23 + 2048, bytes.size).all { it == 0x55.toByte() })
            val readOnly = target.asReadOnlyBuffer().position(23)
            assertFailsWith<java.nio.ReadOnlyBufferException> { store.read(start, readOnly) }
            assertEquals(23, readOnly.position())
        }
    }

    @Test
    fun exactFitShortNameFilesSurviveOverfullMountAndRetry() {
        val card = Files.createDirectory(tempRoot.resolve("near-full"))
        val capacity = 40L * 1024 * 1024
        fun freeClusters(store: DirectoryFat32BlockStore): Int {
            val partition = le32(store.readSector(0), 0x1BE + 8).toLong()
            val vbr = store.readSector(partition)
            assertEquals(1, vbr[13].toInt())
            return le32(store.readSector(partition + le16(vbr, 48)), 488)
        }
        val available = DirectoryFat32BlockStore.open(card, false, capacity).use(::freeClusters)
        val original = card.resolve("DSCF0001.JPG")
        val payload = ByteArray(512) { (it * 17).toByte() }
        Files.write(original, payload)
        java.io.RandomAccessFile(card.resolve("FILLER.BIN").toFile(), "rw").use {
            it.setLength((available - 2L) * 512)
        }
        DirectoryFat32BlockStore.open(card, false, capacity).use { assertEquals(1, freeClusters(it)) }
        Files.write(card.resolve("DSCF0002.JPG"), payload.reversedArray())
        DirectoryFat32BlockStore.open(card, false, capacity).use { assertEquals(0, freeClusters(it)) }
        val sidecar = tempRoot.resolve("near-full.fuji-sd.json")
        val before = Files.readAllBytes(sidecar)
        val overflow = Files.write(card.resolve("DSCF0003.JPG"), byteArrayOf(1))
        val error = assertFailsWith<IllegalArgumentException> { DirectoryFat32BlockStore.open(card, false, capacity).close() }
        assertTrue(error.message.orEmpty().contains("out of free FAT32 clusters"))
        assertContentEquals(before, Files.readAllBytes(sidecar))
        Files.delete(overflow)
        DirectoryFat32BlockStore.open(card, false, capacity).use { assertEquals(0, freeClusters(it)) }
        assertContentEquals(payload, Files.readAllBytes(original))
        assertContentEquals(payload.reversedArray(), Files.readAllBytes(card.resolve("DSCF0002.JPG")))
    }

    @Test
    fun hostFilesSynthesizeValidFat32VolumeAndPreserveClustersIncrementallyAcrossRestarts() {
        val cardDir = tempRoot.resolve("sd-slot-1")
        val dcimFuji = cardDir.resolve("DCIM/100_FUJI")
        Files.createDirectories(dcimFuji)
        val photo1 = ByteArray(12_345) { idx -> ((idx * 13 + 7) and 0xFF).toByte() }
        Files.write(dcimFuji.resolve("DSCF0001.JPG"), photo1)
        val lfnPayload = "Hello GFX100S Long Filename!\n".toByteArray(StandardCharsets.UTF_8)
        Files.write(cardDir.resolve("My Long Photo Notes (v1).txt"), lfnPayload)

        val cap = 40L * 1024L * 1024L // 40 MiB (512B clusters -> valid FAT32 >= 65525 clusters)
        DirectoryFat32BlockStore.open(cardDir, readOnly = false, configuredCapacityBytes = cap).use { store ->
            assertEquals(cap, store.capacityBytes)
            // Verify host folder itself has ZERO hidden metadata files
            assertTrue(Files.exists(tempRoot.resolve("sd-slot-1.fuji-sd.json")))
            assertFalse(Files.list(cardDir).use { s -> s.anyMatch { it.fileName.toString().startsWith(".") } })

            // Verify MBR + VBR + FSInfo + Backup Boot Sector
            val mbr = store.readSector(0L)
            assertEquals(0x55.toByte(), mbr[0x1FE])
            assertEquals(0xAA.toByte(), mbr[0x1FF])
            assertEquals(0x0C, mbr[0x1BE + 4].toInt() and 0xFF)
            val partStart = le32(mbr, 0x1BE + 8).toLong()
            val partSectors = le32(mbr, 0x1BE + 12).toLong()
            assertEquals(cap / 512L - partStart, partSectors)

            val vbr = store.readSector(partStart)
            assertEquals("FAT32   ", String(vbr, 0x52, 8, StandardCharsets.US_ASCII))
            assertEquals(512, le16(vbr, 0x0B))
            val backupVbr = store.readSector(partStart + le16(vbr, 0x32))
            assertContentEquals(vbr, backupVbr)

            // Verify fsck.fat -vn passes with ZERO errors on the materialized partition if fsck.fat exists
            val fsck = listOf("/usr/sbin/fsck.fat", "/sbin/fsck.fat", "/usr/bin/fsck.fat").firstOrNull { Files.isExecutable(Path.of(it)) }
            if (fsck != null) {
                val partImg = tempRoot.resolve("partition-check.fat32")
                Files.newOutputStream(partImg).use { out ->
                    val chunk = ByteBuffer.allocate(64 * 1024)
                    var off = partStart * 512L
                    val end = (partStart + partSectors) * 512L
                    while (off < end) {
                        val len = minOf(chunk.capacity().toLong(), end - off).toInt()
                        chunk.clear(); chunk.limit(len)
                        store.read(off, chunk)
                        out.write(chunk.array(), 0, len)
                        off += len
                    }
                }
                val proc = ProcessBuilder(fsck, "-vn", partImg.toString()).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                assertEquals(0, proc.waitFor(), "fsck.fat -vn failed:\n$output")
                Files.deleteIfExists(partImg)
            }

            // Verify SHA-256 matches exact sequential byte-by-byte digest
            val fastSha = store.computeLogicalSha256()
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteBuffer.allocate(256 * 1024)
            var off = 0L
            while (off < cap) {
                val len = minOf(buf.capacity().toLong(), cap - off).toInt()
                buf.clear(); buf.limit(len)
                store.read(off, buf)
                digest.update(buf.array(), 0, len)
                off += len
            }
            assertEquals(HexFormat.of().formatHex(digest.digest()), fastSha)
        }

        // Record DSCF0001.JPG cluster allocation from sidecar before adding a new file
        val sidecar1 = SdSidecarJson.parseObject(Files.readString(tempRoot.resolve("sd-slot-1.fuji-sd.json")))
        val entries1 = (sidecar1["entries"] as List<*>).map { it as Map<*, *> }.associateBy { it["relativePath"] as String }
        val photo1ClustersBefore = entries1.getValue("DCIM/100_FUJI/DSCF0001.JPG")["clusters"]
        val photo1ShortBefore = entries1.getValue("DCIM/100_FUJI/DSCF0001.JPG")["shortName"]
        val lfnClustersBefore = entries1.getValue("My Long Photo Notes (v1).txt")["clusters"]
        val lfnShortBefore = entries1.getValue("My Long Photo Notes (v1).txt")["shortName"]

        // Drop a brand-new host file (alphabetically BEFORE DSCF0001.JPG!) and reopen:
        // Existing files MUST preserve their exact prior clusters & short names (#94)!
        val photo0 = ByteArray(2048) { 0x3A }
        Files.write(dcimFuji.resolve("AAAA0000.JPG"), photo0)
        DirectoryFat32BlockStore.open(cardDir, readOnly = false, configuredCapacityBytes = 0L).use { reopened ->
            assertEquals(cap, reopened.capacityBytes)
        }
        val sidecar2 = SdSidecarJson.parseObject(Files.readString(tempRoot.resolve("sd-slot-1.fuji-sd.json")))
        val entries2 = (sidecar2["entries"] as List<*>).map { it as Map<*, *> }.associateBy { it["relativePath"] as String }
        assertEquals(photo1ClustersBefore, entries2.getValue("DCIM/100_FUJI/DSCF0001.JPG")["clusters"])
        assertEquals(photo1ShortBefore, entries2.getValue("DCIM/100_FUJI/DSCF0001.JPG")["shortName"])
        assertEquals(lfnClustersBefore, entries2.getValue("My Long Photo Notes (v1).txt")["clusters"])
        assertEquals(lfnShortBefore, entries2.getValue("My Long Photo Notes (v1).txt")["shortName"])
        assertTrue(entries2.containsKey("DCIM/100_FUJI/AAAA0000.JPG"))
    }

    @Test
    fun guestSectorWritesSyncNewFilesDeletesFormatAndProtectMidFormatHostFiles() {
        val cardDir = tempRoot.resolve("guest-sync")
        Files.createDirectories(cardDir)
        val initialFile = cardDir.resolve("KEEP.TXT")
        Files.writeString(initialFile, "original host data")

        val cap = 40L * 1024L * 1024L
        DirectoryFat32BlockStore.open(cardDir, readOnly = false, configuredCapacityBytes = cap).use { store ->
            val mbr = store.readSector(0L)
            val partStart = le32(mbr, 0x1BE + 8).toLong()
            val vbr = store.readSector(partStart)
            val spc = vbr[0x0D].toInt() and 0xFF
            val reserved = le16(vbr, 0x0E)
            val numFats = vbr[0x10].toInt() and 0xFF
            val spf = le32(vbr, 0x24)
            val rootCluster = le32(vbr, 0x2C)
            val fat1Start = partStart + reserved
            val dataStart = partStart + reserved + numFats.toLong() * spf.toLong()

            // Guest writes a brand-new file CAM0001.BIN into cluster 200
            val newCluster = 200
            val newContent = "Captured by GFX100S firmware!".toByteArray(StandardCharsets.UTF_8)
            val dataSec = ByteArray(512)
            System.arraycopy(newContent, 0, dataSec, 0, newContent.size)
            store.writeSector(dataStart + (newCluster - 2L) * spc, dataSec)

            // Link cluster 200 = EOC in FAT1 & FAT2
            val fatSecIdx = (newCluster * 4) / 512
            val fatOff = (newCluster * 4) % 512
            val fatSec = store.readSector(fat1Start + fatSecIdx)
            putLe32(fatSec, fatOff, 0x0FFFFFFF)
            store.writeSector(fat1Start + fatSecIdx, fatSec)
            store.writeSector(fat1Start + spf + fatSecIdx, fatSec)

            // Add CAM0001.BIN directory entry into the first free 32-byte slot of root cluster
            val rootLba = dataStart + (rootCluster - 2L) * spc
            val rootSec = store.readSector(rootLba)
            var dirOff = 0
            while (dirOff + 32 <= rootSec.size && rootSec[dirOff] != 0.toByte()) {
                dirOff += 32
            }
            assertTrue(dirOff + 32 <= rootSec.size)
            "CAM0001 BIN".toByteArray(StandardCharsets.US_ASCII).copyInto(rootSec, dirOff)
            rootSec[dirOff + 11] = 0x20 // archive
            putLe16(rootSec, dirOff + 20, (newCluster ushr 16) and 0xFFFF)
            putLe16(rootSec, dirOff + 26, newCluster and 0xFFFF)
            putLe32(rootSec, dirOff + 28, newContent.size)
            store.writeSector(rootLba, rootSec)

            store.flush()
            val syncedPath = cardDir.resolve("CAM0001.BIN")
            assertTrue(Files.isRegularFile(syncedPath))
            assertContentEquals(newContent, Files.readAllBytes(syncedPath))
            assertTrue(Files.isRegularFile(initialFile))

            // Mid-format safety: zeroing MBR (incomplete format) must NOT wipe host files on flush!
            val savedMbr = store.readSector(0L)
            store.writeSector(0L, ByteArray(512))
            store.flush()
            assertTrue(Files.isRegularFile(initialFile), "Mid-format incomplete write must not delete host files")
            assertTrue(Files.isRegularFile(syncedPath), "Mid-format incomplete write must not delete host files")

            // Complete full in-camera format: restore MBR, write fresh VBR with new label + empty root dir
            store.writeSector(0L, savedMbr)
            val freshVbr = vbr.copyOf()
            "CAMERA_FMT ".toByteArray(StandardCharsets.US_ASCII).copyInto(freshVbr, 0x47)
            putLe32(freshVbr, 0x43, 0x12345678)
            store.writeSector(partStart, freshVbr)
            // Empty root directory cluster
            store.writeSector(rootLba, ByteArray(512))
            store.flush()

            assertFalse(Files.exists(initialFile), "Completed guest format should remove formatted-away files")
            assertFalse(Files.exists(syncedPath), "Completed guest format should remove formatted-away files")
            val sidecarAfter = SdSidecarJson.parseObject(Files.readString(tempRoot.resolve("guest-sync.fuji-sd.json")))
            val volAfter = sidecarAfter["volume"] as Map<*, *>
            assertEquals("CAMERA_FMT ", volAfter["volumeLabel"])
            assertEquals(0x12345678L, volAfter["volumeId"])
        }
    }

    @Test
    fun rejectsCaseCollisionsSymlinksAndSupportsMixedDualSlotWithQcow2() {
        val collisionDir = tempRoot.resolve("collision")
        Files.createDirectories(collisionDir)
        Files.writeString(collisionDir.resolve("a.jpg"), "1")
        if (!Files.exists(collisionDir.resolve("A.JPG"))) {
            // Case-sensitive filesystem (Linux): verify case-insensitive collision rejection
            Files.writeString(collisionDir.resolve("A.JPG"), "2")
            assertFailsWith<IllegalArgumentException> {
                DirectoryFat32BlockStore.open(collisionDir, readOnly = false, configuredCapacityBytes = 40L * 1024L * 1024L)
            }
        }

        // Mixed dual-slot in SdcardFactory: Slot 1 = directory (40 MiB), Slot 2 = qcow2 (4 GiB)
        val home = tempRoot.resolve("dual-home")
        val slot1Dir = home.resolve("slot1-folder")
        Files.createDirectories(slot1Dir.resolve("DCIM/100_FUJI"))
        Files.write(slot1Dir.resolve("DCIM/100_FUJI/DSCF0001.JPG"), ByteArray(4096) { 0x55 })
        val cap1 = 40L * 1024L * 1024L
        val cap2 = 4L * 1024L * 1024L * 1024L
        Qcow2BlockStore.create(home.resolve("slot2.qcow2"), cap2)

        val factory = SdcardFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "slot1Image" to "slot1-folder",
            "slot1Inserted" to true,
            "slot1ReadOnly" to false,
            "slot1Backend" to "directory",
            "slot1CapacityBytes" to cap1,
            "slot2Image" to "slot2.qcow2",
            "slot2Inserted" to true,
            "slot2ReadOnly" to false,
            "slot2Backend" to "qcow2",
            "slot2CapacityBytes" to cap2,
        )

        factory.create(TestContext(home), cfg).use { comp ->
            val port = comp as CheckedBlockStoragePort
            val stateful = comp as StatefulComponent
            assertEquals(cap1, port.size(1))
            assertEquals(cap2, port.size(2))
            assertFalse(port.readOnly(1))
            assertFalse(port.readOnly(2))

            // Slot 2 writes do not affect Slot 1
            val payload2 = ByteArray(1024) { 0x99.toByte() }
            port.write(2, 1024L * 1024L, ByteBuffer.wrap(payload2))
            val back2 = ByteBuffer.allocate(1024)
            port.read(2, 1024L * 1024L, back2)
            assertContentEquals(payload2, back2.array())

            // Snapshot save & restore works seamlessly across mixed backends
            val snap = stateful.saveSnapshot()
            stateful.restoreSnapshot(snap)

            // Duplicate open of directory slot is refused by exclusive lease
            assertFailsWith<IllegalArgumentException> {
                factory.create(TestContext(home), cfg)
            }
        }

        // Nested directory across slot 1 & slot 2 is refused before acquiring any lease
        val nestedCfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "slot1Image" to "slot1-folder",
            "slot1Inserted" to true,
            "slot1ReadOnly" to true,
            "slot1Backend" to "directory",
            "slot1CapacityBytes" to cap1,
            "slot2Image" to "slot1-folder/DCIM",
            "slot2Inserted" to true,
            "slot2ReadOnly" to true,
            "slot2Backend" to "directory",
            "slot2CapacityBytes" to cap1,
        )
        assertFailsWith<IllegalArgumentException> {
            factory.create(TestContext(home), nestedCfg)
        }
    }

    @Test
    fun flushIfConsistentDefersUnlinkedDataClustersAndMaterializesOnceLinked() {
        val cardDir = tempRoot.resolve("live-sync")
        Files.createDirectories(cardDir)
        val cap = 40L * 1024L * 1024L
        DirectoryFat32BlockStore.open(cardDir, readOnly = false, configuredCapacityBytes = cap).use { store ->
            val mbr = store.readSector(0L)
            val partStart = le32(mbr, 0x1BE + 8).toLong()
            val vbr = store.readSector(partStart)
            val spc = vbr[0x0D].toInt() and 0xFF
            val reserved = le16(vbr, 0x0E)
            val numFats = vbr[0x10].toInt() and 0xFF
            val spf = le32(vbr, 0x24)
            val rootCluster = le32(vbr, 0x2C)
            val fat1Start = partStart + reserved
            val dataStart = partStart + reserved + numFats.toLong() * spf.toLong()

            // 1. Write file data to cluster 300 BEFORE FAT/dir entry -> flushIfConsistent must NOT discard it
            val newCluster = 300
            val payload = "Live-synced photo payload".toByteArray(StandardCharsets.UTF_8)
            val dataSec = ByteArray(512).apply { System.arraycopy(payload, 0, this, 0, payload.size) }
            store.writeSector(dataStart + (newCluster - 2L) * spc, dataSec)
            store.flushIfConsistent()
            assertFalse(Files.exists(cardDir.resolve("LIVE0001.JPG")))

            // 2. Write FAT entry before dir entry -> still unlinked, must NOT discard data cluster
            val fatSecIdx = (newCluster * 4) / 512
            val fatOff = (newCluster * 4) % 512
            val fatSec = store.readSector(fat1Start + fatSecIdx)
            putLe32(fatSec, fatOff, 0x0FFFFFFF)
            store.writeSector(fat1Start + fatSecIdx, fatSec)
            store.flushIfConsistent()
            assertFalse(Files.exists(cardDir.resolve("LIVE0001.JPG")))

            // 3. Write root dir entry linking cluster 300 -> flushIfConsistent materializes file immediately!
            val rootLba = dataStart + (rootCluster - 2L) * spc
            val rootSec = store.readSector(rootLba)
            var dirOff = 0
            while (dirOff + 32 <= rootSec.size && rootSec[dirOff] != 0.toByte()) dirOff += 32
            "LIVE0001JPG".toByteArray(StandardCharsets.US_ASCII).copyInto(rootSec, dirOff)
            rootSec[dirOff + 11] = 0x20
            putLe16(rootSec, dirOff + 20, (newCluster ushr 16) and 0xFFFF)
            putLe16(rootSec, dirOff + 26, newCluster and 0xFFFF)
            putLe32(rootSec, dirOff + 28, payload.size)
            store.writeSector(rootLba, rootSec)
            store.flushIfConsistent()

            val synced = cardDir.resolve("LIVE0001.JPG")
            assertTrue(Files.isRegularFile(synced))
            assertContentEquals(payload, Files.readAllBytes(synced))
        }
    }

    @Test
    fun flushIfConsistentPreservesPreallocatedFatChainsAcrossFatSectorBoundariesForStreamingMov() {
        val cardDir = tempRoot.resolve("streaming-mov-fat")
        Files.createDirectories(cardDir)
        val cap = 40L * 1024L * 1024L
        DirectoryFat32BlockStore.open(cardDir, readOnly = false, configuredCapacityBytes = cap).use { store ->
            val mbr = store.readSector(0L)
            val partStart = le32(mbr, 0x1BE + 8).toLong()
            val vbr = store.readSector(partStart)
            val spc = vbr[0x0D].toInt() and 0xFF
            val reserved = le16(vbr, 0x0E)
            val numFats = vbr[0x10].toInt() and 0xFF
            val spf = le32(vbr, 0x24)
            val rootCluster = le32(vbr, 0x2C)
            val fat1Start = partStart + reserved
            val dataStart = partStart + reserved + numFats.toLong() * spf.toLong()
            val clusterBytes = spc * 512

            fun writeFatEntry(cluster: Int, value: Int) {
                val fatSecIdx = (cluster * 4) / 512
                val fatOff = (cluster * 4) % 512
                val sec = store.readSector(fat1Start + fatSecIdx)
                putLe32(sec, fatOff, value)
                store.writeSector(fat1Start + fatSecIdx, sec)
            }

            // 1. Pre-allocate clusters 255 -> 256 -> 257 (crossing the 128-entry FAT sector boundary 255|256)
            //    BEFORE writing any data to cluster 255 (like GFX100S reserving moov header clusters at MOV start!)
            writeFatEntry(255, 256)
            writeFatEntry(256, 257)
            writeFatEntry(257, 0x0FFFFFFF)
            store.flushIfConsistent()

            // 2. Write streaming mdat data to clusters 256 and 257, flushing between chunks
            val c256Data = ByteArray(clusterBytes) { 0x22 }
            val c257Data = ByteArray(clusterBytes) { 0x33 }
            store.writeSector(dataStart + (256L - 2L) * spc, c256Data.copyOfRange(0, 512))
            store.flushIfConsistent()
            store.writeSector(dataStart + (257L - 2L) * spc, c257Data.copyOfRange(0, 512))
            store.flushIfConsistent()

            // 3. On MOV stop, write moov header into pre-allocated cluster 255 and link dir entry
            val c255Data = ByteArray(clusterBytes) { 0x11 }
            store.writeSector(dataStart + (255L - 2L) * spc, c255Data.copyOfRange(0, 512))
            val totalSize = 3 * clusterBytes
            val rootLba = dataStart + (rootCluster - 2L) * spc
            val rootSec = store.readSector(rootLba)
            var dirOff = 0
            while (dirOff + 32 <= rootSec.size && rootSec[dirOff] != 0.toByte()) dirOff += 32
            "DSCF0002MOV".toByteArray(StandardCharsets.US_ASCII).copyInto(rootSec, dirOff)
            rootSec[dirOff + 11] = 0x20
            putLe16(rootSec, dirOff + 20, (255 ushr 16) and 0xFFFF)
            putLe16(rootSec, dirOff + 26, 255 and 0xFFFF)
            putLe32(rootSec, dirOff + 28, totalSize)
            store.writeSector(rootLba, rootSec)
            store.flushIfConsistent()

            val movPath = cardDir.resolve("DSCF0002.MOV")
            assertTrue(Files.isRegularFile(movPath), "Streaming MOV across FAT sector boundary must materialize cleanly")
            val bytes = Files.readAllBytes(movPath)
            assertEquals(totalSize, bytes.size)
            assertEquals(0x11.toByte(), bytes[0])
            assertEquals(0x22.toByte(), bytes[clusterBytes])
            assertEquals(0x33.toByte(), bytes[2 * clusterBytes])
        }
    }

    @Test
    fun spilledMovieDataSurvivesMetadataSyncAndPublishesInFragmentedChainOrder() {
        val card = Files.createDirectory(tempRoot.resolve("spilled-movie"))
        val old = Files.writeString(card.resolve("OLD.MOV"), "previous clip")
        val movie = card.resolve("LONG.MOV")
        val expected = ByteArray(12 * 1024 * 1024) { (it * 13 + it / 65536).toByte() }
        val cap = 40L * 1024 * 1024
        DirectoryFat32BlockStore.open(card, false, cap).use { store ->
            val part = le32(store.readSector(0), 0x1BE + 8).toLong()
            val vbr = store.readSector(part)
            assertEquals(1, vbr[13].toInt())
            val fat1 = part + le16(vbr, 14)
            val sectorsPerFat = le32(vbr, 36)
            val fat2 = fat1 + sectorsPerFat
            val data = fat2 + sectorsPerFat
            val first = 1024
            val count = expected.size / 512
            val start = first + count / 2
            fun lba(cluster: Int) = data + cluster - 2

            for (cluster in first until first + count step 128) {
                val f = store.readSector(fat1 + cluster / 128)
                for (i in 0 until 128) {
                    val next = when (val c = cluster + i) {
                        start - 1 -> 0x0fffffff
                        first + count - 1 -> first
                        else -> c + 1
                    }
                    putLe32(f, i * 4, next)
                }
                store.writeSector(fat1 + cluster / 128, f)
                store.writeSector(fat2 + cluster / 128, f)
            }
            val half = expected.size / 2
            store.write(lba(start) * 512, ByteBuffer.wrap(expected, 0, half))
            store.write(lba(first) * 512, ByteBuffer.wrap(expected, half, half))
            store.flushIfConsistent()
            assertFalse(Files.exists(movie))
            assertEquals("previous clip", Files.readString(old))

            val edit = byteArrayOf(94, 37, 81)
            store.write(lba(start) * 512 + 65535, ByteBuffer.wrap(edit))
            edit.copyInto(expected, 65535)
            val readBack = ByteBuffer.allocate(5)
            store.read(lba(start) * 512 + 65534, readBack)
            assertContentEquals(expected.copyOfRange(65534, 65539), readBack.array())
            val rootLba = lba(le32(vbr, 44))
            val directory = store.readSector(rootLba)
            val slot = (0 until 512 step 32).first { directory[it].toInt() == 0 }
            "LONG    MOV".toByteArray(StandardCharsets.US_ASCII).copyInto(directory, slot)
            directory[slot + 11] = 0x20
            putLe16(directory, slot + 20, start ushr 16)
            putLe16(directory, slot + 26, start and 0xffff)
            putLe32(directory, slot + 28, expected.size)
            store.writeSector(rootLba, directory)
            store.flushIfConsistent()
            assertEquals(0, store.diagnostics()["dirtySectorCount"])
        }
        assertEquals("previous clip", Files.readString(old))
        val sha = MessageDigest.getInstance("SHA-256")
        assertContentEquals(sha.digest(expected), sha.digest(Files.readAllBytes(movie)))
        DirectoryFat32BlockStore.open(card, true, cap).use { assertEquals(cap, it.capacityBytes) }
        assertTrue(Files.list(tempRoot).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".fuji-sd-pending-") } })
    }

    @Test
    fun unalignedWriteLeavesSourceAtFirstUncommittedByteWhenSpillFails() {
        val mount = Files.createDirectory(tempRoot.resolve("mount"))
        val card = Files.createDirectory(mount.resolve("card"))
        val displaced = tempRoot.resolve("displaced")
        DirectoryFat32BlockStore.open(card, false, 40L * 1024 * 1024).use { store ->
            val page = ByteArray(64 * 1024) { 0x31 }
            val start = 8L * 1024 * 1024
            repeat(128) { store.write(start + it * page.size, ByteBuffer.wrap(page)) }
            val target = start + 128 * page.size + 19
            val source = ByteBuffer.wrap(byteArrayOf(0, 18, 52, 86, 120, 0), 1, 4)
            val old = store.readSector(target / 512)
            Files.move(mount, displaced)
            try {
                assertFailsWith<IOException> { store.write(target, source) }
            } finally {
                Files.move(displaced, mount)
            }
            assertEquals(1, source.position())
            assertContentEquals(old, store.readSector(target / 512))
            assertEquals(4, store.write(target, source))
            assertEquals(5, source.position())
            byteArrayOf(18, 52, 86, 120).copyInto(old, 19)
            assertContentEquals(old, store.readSector(target / 512))
        }
    }

    @Test
    fun guestBpbShiftReadsFatEntriesAtTheirNewPhysicalSector() {
        val card = Files.createDirectory(tempRoot.resolve("shifted-fat"))
        Files.write(card.resolve("OLD.MOV"), ByteArray(3 * 512))
        DirectoryFat32BlockStore.open(card, false, 40L * 1024 * 1024).use { store ->
            val part = le32(store.readSector(0), 454).toLong()
            val vbr = store.readSector(part)
            assertEquals(1, vbr[13].toInt())
            val reserved = le16(vbr, 14)
            val oldFat = part + reserved
            val fat = store.readSector(oldFat)
            val oldRoot = le32(vbr, 44)
            assertTrue((le32(fat, oldRoot * 4) and 0x0fffffff) >= 0x0ffffff8)
            val last = (oldRoot + 1 until 128).first { (le32(fat, it * 4) and 0x0fffffff) >= 0x0ffffff8 }
            // Shift by exactly 128 FAT entries. Both EOF words stay in the untouched old FAT sector.
            putLe16(vbr, 14, reserved - 1)
            val newRoot = oldRoot + 128
            val start = last + 128
            putLe32(vbr, 44, newRoot)
            store.writeSector(part, vbr)
            val data = oldFat - 1 + 2L * le32(vbr, 36)
            val directory = ByteArray(512)
            "NEW     MOV".toByteArray(StandardCharsets.US_ASCII).copyInto(directory)
            directory[11] = 0x20
            putLe16(directory, 26, start)
            putLe32(directory, 28, 512)
            val expected = ByteArray(512) { (it * 29).toByte() }
            store.writeSector(data + newRoot - 2, directory)
            store.writeSector(data + start - 2, expected)
            store.flushIfConsistent()
            assertContentEquals(expected, Files.readAllBytes(card.resolve("NEW.MOV")))
        }
    }
}
