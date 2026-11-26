package com.github.nyabanban.fuji.emulator.components.flashimage

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import kotlin.test.*

class FlashImageSnapshotTest {
    @TempDir
    lateinit var tempRoot: Path

    private val primaryBase = 0x3dc0000L
    private val secondaryBase = 0x3f00000L

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private class TestContext(
        override val directory: Path,
        private val files: Map<String, Map<String, Any?>>,
    ) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val clock = EmulationClock { 0L }
        override fun readData(relative: String): Map<String, Any?> = files.getValue(relative)
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { resolved -> require(resolved.startsWith(directory)) }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
    }

    private fun buildDatUpdatePort(payloadLength: Int = 4100): UpdateDataPort {
        val rawBytes = ByteArray(0x274 + payloadLength) { index -> ((index * 29 + 7) and 0xff).toByte() }
        ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 6).putInt(0x24c, payloadLength)
        val sha = sha256Hex(rawBytes)
        return object : UpdateDataPort {
            override val size = rawBytes.size.toLong()
            override val sha256 = sha
            override fun read(offset: Long, destination: ByteBuffer) {
                destination.put(rawBytes, offset.toInt(), destination.remaining())
            }
        }
    }

    private inner class MultiRegionInstance(
        val home: Path,
        bankBytes: ByteArray? = null,
        selectBank: Boolean = false,
    ) : AutoCloseable {
        val seedPrimary = ByteArray(8192) { index -> (0xa5 xor (index and 0x7f)).toByte() }
        val seedSecondary = ByteArray(8192) { index -> (0x3c xor (index and 0x3f)).toByte() }
        val component: StatefulComponent
        val readOnly: ReadOnlyImagePort
        val mutableSet: WritableImageSetPort
        val programmable: ProgrammableImagePort

        init {
            Files.createDirectories(home)
            Files.write(home.resolve("seed-a.img"), seedPrimary)
            Files.write(home.resolve("seed-b.img"), seedSecondary)
            val primarySha = sha256Hex(seedPrimary)
            val secondarySha = sha256Hex(seedSecondary)
            val bank = SupplementalRegions.BANK30
            val addition = if (bankBytes == null) emptyList() else {
                Files.write(home.resolve(bank.relativeFile), bankBytes)
                listOf(mapOf("id" to bank.id, "offset" to bank.offset, "length" to bank.length,
                    "file" to bank.relativeFile, "sha256" to bank.sha256))
            }
            val regionsJson = mapOf<String, Any?>(
                "schema" to 1,
                "profile" to SupplementalRegions.PROFILE,
                "regions" to listOf(
                    mapOf("id" to "primary", "offset" to primaryBase, "length" to 8192L, "file" to "seed-a.img", "sha256" to primarySha),
                    mapOf("id" to "secondary", "offset" to secondaryBase, "length" to 8192L, "file" to "seed-b.img", "sha256" to secondarySha),
                ) + addition,
            ) + if (selectBank) mapOf("restoreAppend" to SupplementalRegions.BANK30_SELECTION) else emptyMap()
            val mutableJson = mapOf<String, Any?>(
                "schema" to 1,
                "profile" to WritableRegions.PROFILE,
                "primaryRegionId" to "primary",
                "regions" to listOf(
                    mapOf(
                        "schema" to 1,
                        "profile" to WritableRegion.PROGRAM_PROFILE,
                        "regionId" to "primary",
                        "file" to "copy-a.img",
                        "mode" to "create",
                        "sha256" to primarySha,
                        "writableOffset" to primaryBase + 4096L,
                        "writableLength" to 4096L,
                        "programLaw" to WritableRegion.PROGRAM_LAW,
                        "programPageBytes" to 256L,
                    ),
                    mapOf(
                        "schema" to 1,
                        "profile" to WritableRegion.PROGRAM_PROFILE,
                        "regionId" to "secondary",
                        "file" to "copy-b.img",
                        "mode" to "create",
                        "sha256" to secondarySha,
                        "writableOffset" to secondaryBase,
                        "writableLength" to 8192L,
                        "programLaw" to WritableRegion.PROGRAM_LAW,
                        "programPageBytes" to 256L,
                    ),
                ),
            )
            val context = TestContext(home, mapOf("regions.json" to regionsJson, "mutable.json" to mutableJson))
            context.claims["test.update"] = buildDatUpdatePort()
            val factory = FlashImageFactory()
            val config = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
                "sourcePort" to "test.update",
                "tailProfile" to "dat-end-block-ff-experimental-v1",
                "regionsProfile" to SupplementalRegions.PROFILE,
                "regionsFile" to "regions.json",
                "mutationProfile" to WritableRegions.PROFILE,
                "mutationFile" to "mutable.json",
            )
            component = factory.create(context, config) as StatefulComponent
            readOnly = checkNotNull(context.ports.find("flash.image", ReadOnlyImagePort::class.java))
            mutableSet = context.ports.find("flash.image.mutable", WritableImagePort::class.java) as WritableImageSetPort
            programmable = checkNotNull(context.ports.find("flash.image.program", ProgrammableImagePort::class.java))
        }

        fun readRange(offset: Long, count: Int): ByteArray {
            val destination = ByteBuffer.allocate(count)
            readOnly.read(offset, destination)
            return destination.array()
        }

        override fun close() = component.close()
    }

    @Test
    fun explicitBank30RestorePreservesHistoryAndRequiresEveryInheritedIdentity() {
        val bank = SupplementalRegions.BANK30
        val archive = Path.of("../../firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(archive), "Hardware dump archive omitted in standalone public checkout")
        val bytes = ZipFile(archive.toFile()).use { zip ->
            val partition = zip.getInputStream(zip.getEntry("files/partitions/${bank.relativeFile}")).use { it.readAllBytes() }
            val layout = zip.getInputStream(zip.getEntry("files/flash-layout.bin")).use {
                it.skipNBytes(bank.offset)
                it.readNBytes(bank.length.toInt())
            }
            assertContentEquals(layout, partition)
            partition
        }
        MultiRegionInstance(tempRoot.resolve("old")).use { prior ->
            prior.mutableSet.prepareErase(secondaryBase, 4096).commit()
            val saved = simulateJsonRoundTrip(prior.component.saveSnapshot())
            assertFails { prior.readRange(bank.offset, 1) }
            MultiRegionInstance(tempRoot.resolve("explicit"), bytes, true).use { selected ->
                selected.component.restoreSnapshot(saved)
                assertContentEquals(bytes, selected.readRange(bank.offset, bytes.size))
                assertFails { selected.readRange(bank.offset + bank.length - 1, 2) }
                assertFails { selected.mutableSet.regionForWrite(bank.offset, 4096) }
                val promoted = simulateJsonRoundTrip(selected.component.saveSnapshot())
                assertEquals(saved["writableRegionSet"], promoted["writableRegionSet"])
                selected.component.restoreSnapshot(promoted)
                val rows = saved["supplementalRegions"] as List<*>
                for (wrong in listOf(rows.reversed(), rows.dropLast(1), rows + rows.last())) {
                    assertFails { selected.component.restoreSnapshot(saved + ("supplementalRegions" to wrong)) }
                    assertEquals(promoted, simulateJsonRoundTrip(selected.component.saveSnapshot()))
                }
                assertFails { prior.component.restoreSnapshot(promoted) }
            }
            MultiRegionInstance(tempRoot.resolve("unselected"), bytes).use { unselected ->
                assertFails { unselected.component.restoreSnapshot(saved) }
            }
            assertFailsWith<IllegalArgumentException> { MultiRegionInstance(tempRoot.resolve("missing"), selectBank = true) }
            bytes[0] = bytes[0].inc()
            assertFailsWith<IllegalArgumentException> { MultiRegionInstance(tempRoot.resolve("corrupt"), bytes, true) }
        }
    }

    private inner class SingleRegionInstance(val home: Path) : AutoCloseable {
        val seedBytes = ByteArray(16384) { index -> (0x6b xor (index and 0xff)).toByte() }
        val component: StatefulComponent
        val readOnly: ReadOnlyImagePort
        val mutable: WritableImagePort
        val programmable: ProgrammableImagePort

        init {
            Files.createDirectories(home)
            Files.write(home.resolve("seed.img"), seedBytes)
            val seedSha = sha256Hex(seedBytes)
            val regionsJson = mapOf<String, Any?>(
                "schema" to 1,
                "profile" to SupplementalRegions.PROFILE,
                "regions" to listOf(
                    mapOf("id" to "single", "offset" to primaryBase, "length" to 16384L, "file" to "seed.img", "sha256" to seedSha),
                ),
            )
            val mutableJson = mapOf<String, Any?>(
                "schema" to 1,
                "profile" to WritableRegion.PROGRAM_PROFILE,
                "regionId" to "single",
                "file" to "copy.img",
                "mode" to "create",
                "sha256" to seedSha,
                "writableOffset" to primaryBase + 4096L,
                "writableLength" to 8192L,
                "programLaw" to WritableRegion.PROGRAM_LAW,
                "programPageBytes" to 256L,
            )
            val context = TestContext(home, mapOf("regions.json" to regionsJson, "mutable.json" to mutableJson))
            context.claims["test.update"] = buildDatUpdatePort()
            val factory = FlashImageFactory()
            val config = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
                "sourcePort" to "test.update",
                "tailProfile" to "dat-end-block-ff-experimental-v1",
                "regionsProfile" to SupplementalRegions.PROFILE,
                "regionsFile" to "regions.json",
                "mutationProfile" to WritableRegion.PROGRAM_PROFILE,
                "mutationFile" to "mutable.json",
            )
            component = factory.create(context, config) as StatefulComponent
            readOnly = checkNotNull(context.ports.find("flash.image", ReadOnlyImagePort::class.java))
            mutable = checkNotNull(context.ports.find("flash.image.mutable", WritableImagePort::class.java))
            programmable = checkNotNull(context.ports.find("flash.image.program", ProgrammableImagePort::class.java))
        }

        override fun close() = component.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTrip(snapshot: Map<String, Any?>): Map<String, Any?> =
        roundTripNode(snapshot) as Map<String, Any?>

    private fun roundTripNode(node: Any?): Any? = when (node) {
        null, is Boolean, is String -> node
        is Byte, is Short, is Int, is Long -> {
            val value = (node as Number).toLong()
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        }
        is List<*> -> node.map { element -> roundTripNode(element) }
        is Map<*, *> -> {
            val copy = linkedMapOf<String, Any?>()
            for ((rawKey, rawValue) in node) {
                val key = rawKey as? String ?: fail("Snapshot map key must be String, got: $rawKey")
                copy[key] = roundTripNode(rawValue)
            }
            copy
        }
        else -> fail("Non-JSON snapshot value leaked: ${node::class.java.name}")
    }

    @Test
    fun multiRegionSetT1ToT2EraseAndProgramEquivalenceAndDiskSyncAfterJsonRoundTrip() {
        MultiRegionInstance(tempRoot.resolve("instance-a")).use { primary ->
            MultiRegionInstance(tempRoot.resolve("instance-b")).use { restored ->
                val initialSnapshot = primary.component.saveSnapshot()
                @Suppress("UNCHECKED_CAST")
                val initialRegions = ((initialSnapshot["writableRegionSet"] as Map<String, Any?>)["regions"] as List<Map<String, Any?>>)
                assertTrue(initialRegions.all { region -> region["writableSubrangeBase64"] == null })

                primary.mutableSet.prepareErase(primaryBase + 4096L, 4096L).commit()
                val t1Payload = ByteArray(64) { index -> (0x80 or index).toByte() }
                primary.programmable.prepareProgram(primaryBase + 4096L, ByteBuffer.wrap(t1Payload)).commit()

                val rawSnapshotT1 = primary.component.saveSnapshot()
                @Suppress("UNCHECKED_CAST")
                val t1Regions = ((rawSnapshotT1["writableRegionSet"] as Map<String, Any?>)["regions"] as List<Map<String, Any?>>)
                assertNotNull(t1Regions[0]["writableSubrangeBase64"])
                assertNull(t1Regions[1]["writableSubrangeBase64"])
                val roundTrippedT1 = simulateJsonRoundTrip(rawSnapshotT1)

                restored.mutableSet.prepareErase(secondaryBase, 4096L).commit()
                restored.programmable.prepareProgram(secondaryBase, ByteBuffer.wrap(byteArrayOf(0x11, 0x22, 0x33, 0x44))).commit()

                restored.component.restoreSnapshot(roundTrippedT1)
                assertEquals(primary.component.state(), restored.component.state())
                assertContentEquals(
                    Files.readAllBytes(primary.home.resolve("copy-a.img")),
                    Files.readAllBytes(restored.home.resolve("copy-a.img")),
                )
                assertContentEquals(
                    Files.readAllBytes(primary.home.resolve("copy-b.img")),
                    Files.readAllBytes(restored.home.resolve("copy-b.img")),
                )

                val secondaryPayload = ByteArray(128) { index -> (0xf0 - (index and 0x7f)).toByte() }
                val followUpPrimaryPayload = ByteArray(32) { index -> (0x20 + index).toByte() }
                for (instance in listOf(primary, restored)) {
                    instance.mutableSet.prepareErase(secondaryBase, 4096L).commit()
                    instance.programmable.prepareProgram(secondaryBase, ByteBuffer.wrap(secondaryPayload)).commit()
                    instance.programmable.prepareProgram(primaryBase + 4096L + 256L, ByteBuffer.wrap(followUpPrimaryPayload)).commit()
                    instance.mutableSet.prepareErase(primaryBase + 4096L, 4096L).commit()
                    instance.programmable.prepareProgram(primaryBase + 4096L, ByteBuffer.wrap(followUpPrimaryPayload)).commit()
                }

                assertEquals(primary.component.state(), restored.component.state())
                assertContentEquals(primary.readRange(0L, 8192), restored.readRange(0L, 8192))
                assertContentEquals(primary.readRange(primaryBase, 8192), restored.readRange(primaryBase, 8192))
                assertContentEquals(primary.readRange(secondaryBase, 8192), restored.readRange(secondaryBase, 8192))
                assertContentEquals(
                    Files.readAllBytes(primary.home.resolve("copy-a.img")),
                    Files.readAllBytes(restored.home.resolve("copy-a.img")),
                )
                assertContentEquals(
                    Files.readAllBytes(primary.home.resolve("copy-b.img")),
                    Files.readAllBytes(restored.home.resolve("copy-b.img")),
                )
                assertContentEquals(primary.seedPrimary, Files.readAllBytes(restored.home.resolve("seed-a.img")))
                assertContentEquals(primary.seedSecondary, Files.readAllBytes(restored.home.resolve("seed-b.img")))
            }
        }
    }

    @Test
    fun singleRegionInPlaceRollbackRestoresDiskAndSubsequentEraseAndProgram() {
        SingleRegionInstance(tempRoot.resolve("single-rollback")).use { instance ->
            instance.mutable.prepareErase(primaryBase + 4096L, 4096L).commit()
            instance.programmable.prepareProgram(
                primaryBase + 4096L,
                ByteBuffer.wrap(ByteArray(48) { index -> (0x40 + index).toByte() }),
            ).commit()

            val stateAtT1 = instance.component.state()
            val diskAtT1 = Files.readAllBytes(instance.home.resolve("copy.img"))
            val snapshotAtT1 = simulateJsonRoundTrip(instance.component.saveSnapshot())

            instance.mutable.prepareErase(primaryBase + 8192L, 4096L).commit()
            instance.programmable.prepareProgram(
                primaryBase + 8192L,
                ByteBuffer.wrap(ByteArray(32) { 0x12 }),
            ).commit()
            assertNotEquals(stateAtT1, instance.component.state())

            instance.component.restoreSnapshot(snapshotAtT1)
            assertEquals(stateAtT1, instance.component.state())
            assertContentEquals(diskAtT1, Files.readAllBytes(instance.home.resolve("copy.img")))

            instance.mutable.prepareErase(primaryBase + 8192L, 4096L).commit()
            instance.programmable.prepareProgram(
                primaryBase + 8192L,
                ByteBuffer.wrap(ByteArray(32) { 0x34 }),
            ).commit()
            val verifyBuffer = ByteBuffer.allocate(32)
            instance.readOnly.read(primaryBase + 8192L, verifyBuffer)
            assertContentEquals(ByteArray(32) { 0x34 }, verifyBuffer.array())
        }
    }

    @Test
    fun pendingLeasesAndMalformedMultiRegionPayloadsRejectAtomicallyWithoutDiskMutation() {
        MultiRegionInstance(tempRoot.resolve("source-valid")).use { source ->
            source.mutableSet.prepareErase(primaryBase + 4096L, 4096L).commit()
            source.programmable.prepareProgram(primaryBase + 4096L, ByteBuffer.wrap(byteArrayOf(0x55, 0x66, 0x77, 0x00))).commit()
            val validSnapshot = simulateJsonRoundTrip(source.component.saveSnapshot())

            val pendingErase = source.mutableSet.prepareErase(secondaryBase, 4096L)
            assertFails { source.component.saveSnapshot() }
            assertFails { source.component.restoreSnapshot(validSnapshot) }
            pendingErase.close()

            source.mutableSet.prepareErase(secondaryBase, 4096L).commit()
            val pendingProgram = source.programmable.prepareProgram(secondaryBase, ByteBuffer.wrap(byteArrayOf(0x12)))
            assertFails { source.component.saveSnapshot() }
            assertFails { source.component.restoreSnapshot(validSnapshot) }
            pendingProgram.close()

            MultiRegionInstance(tempRoot.resolve("target-atomic")).use { target ->
                val baselineState = target.component.state()
                val baselineCopyA = Files.readAllBytes(target.home.resolve("copy-a.img"))
                val baselineCopyB = Files.readAllBytes(target.home.resolve("copy-b.img"))

                @Suppress("UNCHECKED_CAST")
                val validSet = validSnapshot["writableRegionSet"] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val validRegions = validSet["regions"] as List<Map<String, Any?>>
                val badSecondRegionSha = validSet + ("regions" to listOf(
                    validRegions[0],
                    validRegions[1] + ("sha256" to "f".repeat(64)),
                ))
                val badSequence = validSet + ("sequence" to 99)

                val invalidCandidates = listOf(
                    validSnapshot + ("schema" to 2),
                    validSnapshot + ("componentId" to "soc-flash"),
                    validSnapshot + ("sha256" to "a".repeat(64)),
                    validSnapshot + ("writableRegionSet" to badSecondRegionSha),
                    validSnapshot + ("writableRegionSet" to badSequence),
                )
                for (badSnapshot in invalidCandidates) {
                    assertFails { target.component.restoreSnapshot(badSnapshot) }
                    assertEquals(baselineState, target.component.state())
                    assertContentEquals(baselineCopyA, Files.readAllBytes(target.home.resolve("copy-a.img")))
                    assertContentEquals(baselineCopyB, Files.readAllBytes(target.home.resolve("copy-b.img")))
                    assertFalse(Files.exists(target.home.resolve("copy-a.img.restore.tmp")))
                    assertFalse(Files.exists(target.home.resolve("copy-b.img.restore.tmp")))
                }
            }
        }
    }
}
