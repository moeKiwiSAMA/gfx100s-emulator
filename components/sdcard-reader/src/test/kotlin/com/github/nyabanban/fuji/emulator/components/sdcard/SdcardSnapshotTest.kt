package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SdcardSnapshotTest {
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
            override fun removeOwner(owner: String) {
                registeredPorts.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("Unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path =
            directory.resolve(relative).normalize().also { resolved -> require(resolved.startsWith(directory) && resolved != directory) }
    }

    private fun createImage(directory: Path, fileName: String, sectorCount: Int, fillByte: Int): Path {
        Files.createDirectories(directory)
        val target = directory.resolve(fileName)
        val payload = ByteArray(sectorCount * 512) { index -> ((index + fillByte) and 0xff).toByte() }
        Files.write(target, payload)
        return target
    }

    private fun openReader(
        directory: Path,
        slot1Image: String = "slot1.img",
        slot1Inserted: Boolean = true,
        slot1ReadOnly: Boolean = false,
        slot2Image: String = "slot2.img",
        slot2Inserted: Boolean = false,
        slot2ReadOnly: Boolean = true,
    ): StatefulComponent {
        val factory = SdcardFactory()
        val settings = mapOf(
            "slot1Image" to slot1Image,
            "slot1Inserted" to slot1Inserted,
            "slot1ReadOnly" to slot1ReadOnly,
            "slot2Image" to slot2Image,
            "slot2Inserted" to slot2Inserted,
            "slot2ReadOnly" to slot2ReadOnly,
        )
        val component = factory.create(TestContext(directory), settings)
        component.start()
        return assertIs<StatefulComponent>(component)
    }

    private fun simulateJsonRoundTrip(snapshot: Map<String, Any?>): Map<String, Any?> {
        val jsonText = encodeJsonValue(snapshot)
        val reader = JsonReader(jsonText)
        val parsed = reader.parseValue()
        reader.expectEnd()
        @Suppress("UNCHECKED_CAST")
        return parsed as Map<String, Any?>
    }

    private fun encodeJsonValue(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Byte, is Short, is Int, is Long -> (value as Number).toLong().toString()
        is String -> {
            require(value.length <= 65536) { "JSON string exceeds 64 KiB limit" }
            buildString {
                append('"')
                for (character in value) {
                    when (character) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(character)
                    }
                }
                append('"')
            }
        }
        is List<*> -> value.joinToString(",", "[", "]") { element -> encodeJsonValue(element) }
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { entry ->
            val key = entry.key as? String ?: error("JSON object keys must be String")
            "${encodeJsonValue(key)}:${encodeJsonValue(entry.value)}"
        }
        else -> error("Unsupported non-JSON snapshot value: ${value::class.java.name}")
    }

    private class JsonReader(private val source: String) {
        private var position = 0
        private fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }
        fun expectEnd() {
            skipWhitespace()
            require(position == source.length)
        }
        fun parseValue(): Any? {
            skipWhitespace()
            return when (source[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }
        private fun parseObject(): Map<String, Any?> {
            position++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (source[position] == '}') {
                position++
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                require(source[position++] == ':')
                result[key] = parseValue()
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    '}' -> return result
                    else -> error("Malformed JSON object")
                }
            }
        }
        private fun parseArray(): List<Any?> {
            position++
            skipWhitespace()
            val result = ArrayList<Any?>()
            if (source[position] == ']') {
                position++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (source[position++]) {
                    ',' -> skipWhitespace()
                    ']' -> return result
                    else -> error("Malformed JSON array")
                }
            }
        }
        private fun parseString(): String {
            require(source[position++] == '"')
            val builder = StringBuilder()
            while (position < source.length) {
                val character = source[position++]
                if (character == '"') return builder.toString()
                if (character == '\\') {
                    when (val escaped = source[position++]) {
                        '"', '\\', '/' -> builder.append(escaped)
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        else -> error("Unsupported escape: $escaped")
                    }
                } else builder.append(character)
            }
            error("Unterminated string")
        }
        private fun parseBoolean(): Boolean = if (source.startsWith("true", position)) {
            position += 4
            true
        } else if (source.startsWith("false", position)) {
            position += 5
            false
        } else error("Invalid boolean")
        private fun parseNull(): Any? {
            require(source.startsWith("null", position))
            position += 4
            return null
        }
        private fun parseNumber(): Number {
            val start = position
            if (source[position] == '-') position++
            while (position < source.length && source[position].isDigit()) position++
            val numberValue = source.substring(start, position).toLong()
            return if (numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numberValue.toInt() else numberValue
        }
    }

    @Test
    fun writableAndReadOnlySlotsPreserveMetadataLeaseAndSectorEquivalenceAcrossJsonRestore() {
        val directDir = tempRoot.resolve("direct")
        val restoredDir = tempRoot.resolve("restored")
        createImage(directDir, "slot1.img", 8, 0x21)
        createImage(directDir, "slot2.img", 4, 0x73)
        createImage(restoredDir, "slot1.img", 8, 0x21)
        createImage(restoredDir, "slot2.img", 4, 0x73)

        val uninsertedDir = tempRoot.resolve("uninserted")
        Files.createDirectories(uninsertedDir)
        openReader(uninsertedDir, slot1Inserted = false, slot2Inserted = false).use { emptyReader ->
            val emptySnapshot = simulateJsonRoundTrip(emptyReader.saveSnapshot())
            val slotRows = emptySnapshot["slots"] as List<*>
            for (row in slotRows) {
                val slotMap = row as Map<*, *>
                assertEquals(false, slotMap["inserted"])
                assertNull(slotMap["sizeBytes"])
                assertNull(slotMap["sha256"])
            }
            emptyReader.restoreSnapshot(emptySnapshot)
        }

        openReader(
            directDir,
            slot1Inserted = true,
            slot1ReadOnly = false,
            slot2Inserted = true,
            slot2ReadOnly = true,
        ).use { directReader ->
            val directStorage = directReader as CheckedBlockStoragePort
            val writeAtT1 = ByteArray(600) { offset -> (0xa0 + (offset and 0x3f)).toByte() }
            assertEquals(600, directStorage.write(1, 500L, ByteBuffer.wrap(writeAtT1)))

            val snapshotAtT1 = simulateJsonRoundTrip(directReader.saveSnapshot())
            val mediaBytesAtT1 = Files.readAllBytes(directDir.resolve("slot1.img"))

            val writeAtT2 = ByteArray(512) { offset -> (0x44 xor (offset and 0xff)).toByte() }
            assertEquals(512, directStorage.write(1, 2048L, ByteBuffer.wrap(writeAtT2)))
            val directReadSlot1 = ByteBuffer.allocate(4096)
            val directReadSlot2 = ByteBuffer.allocate(2048)
            assertEquals(4096, directStorage.read(1, 0L, directReadSlot1))
            assertEquals(2048, directStorage.read(2, 0L, directReadSlot2))
            val directFinalState = directReader.state()

            openReader(
                restoredDir,
                slot1Inserted = true,
                slot1ReadOnly = false,
                slot2Inserted = true,
                slot2ReadOnly = true,
            ).use { restoredReader ->
                val restoredStorage = restoredReader as CheckedBlockStoragePort
                assertFailsWith<IllegalArgumentException> {
                    openReader(restoredDir, slot1Inserted = true, slot1ReadOnly = false)
                }
                restoredReader.restoreSnapshot(snapshotAtT1)
                assertFailsWith<IllegalArgumentException> {
                    openReader(restoredDir, slot1Inserted = true, slot1ReadOnly = false)
                }
                assertContentEquals(mediaBytesAtT1, Files.readAllBytes(restoredDir.resolve("slot1.img")))

                assertEquals(512, restoredStorage.write(1, 2048L, ByteBuffer.wrap(writeAtT2)))
                val restoredReadSlot1 = ByteBuffer.allocate(4096)
                val restoredReadSlot2 = ByteBuffer.allocate(2048)
                assertEquals(4096, restoredStorage.read(1, 0L, restoredReadSlot1))
                assertEquals(2048, restoredStorage.read(2, 0L, restoredReadSlot2))

                assertContentEquals(directReadSlot1.array(), restoredReadSlot1.array())
                assertContentEquals(directReadSlot2.array(), restoredReadSlot2.array())
                assertEquals(directFinalState, restoredReader.state())
            }

            directReader.restoreSnapshot(snapshotAtT1)
            assertContentEquals(mediaBytesAtT1, Files.readAllBytes(directDir.resolve("slot1.img")))
        }
    }

    @Test
    fun invalidSnapshotsAndCorruptedMediaRejectAtomicallyWithoutMutatingWritableSlot() {
        val sourceDir = tempRoot.resolve("atomic-source")
        val targetDir = tempRoot.resolve("atomic-target")
        createImage(sourceDir, "slot1.img", 4, 0x11)
        createImage(sourceDir, "slot2.img", 4, 0x22)
        createImage(targetDir, "slot1.img", 4, 0x11)
        createImage(targetDir, "slot2.img", 4, 0x22)

        lateinit var validSnapshot: Map<String, Any?>
        openReader(
            sourceDir,
            slot1Inserted = true,
            slot1ReadOnly = false,
            slot2Inserted = true,
            slot2ReadOnly = true,
        ).use { sourceReader ->
            val storage = sourceReader as CheckedBlockStoragePort
            storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x7e }))
            validSnapshot = simulateJsonRoundTrip(sourceReader.saveSnapshot())
        }

        openReader(
            targetDir,
            slot1Inserted = true,
            slot1ReadOnly = false,
            slot2Inserted = true,
            slot2ReadOnly = true,
        ).use { targetReader ->
            val initialTargetSlot1 = Files.readAllBytes(targetDir.resolve("slot1.img"))
            val baselineState = targetReader.state()
            @Suppress("UNCHECKED_CAST")
            val validSlots = validSnapshot["slots"] as List<Map<String, Any?>>
            val slot1Map = validSlots[0]
            val slot2Map = validSlots[1]

            val tamperedCandidates = listOf(
                validSnapshot - "slots",
                validSnapshot + ("extra" to 0),
                validSnapshot + ("schema" to 2),
                validSnapshot + ("componentId" to "other"),
                validSnapshot + ("slots" to listOf(
                    slot1Map + ("sizeBytes" to 4096L),
                    slot2Map,
                )),
                validSnapshot + ("slots" to listOf(
                    slot1Map + ("sha256" to "0".repeat(64)),
                    slot2Map,
                )),
                validSnapshot + ("slots" to listOf(
                    slot1Map + ("modifiedSectors" to mapOf("99" to Base64.getEncoder().encodeToString(ByteArray(512)))),
                    slot2Map,
                )),
                validSnapshot + ("slots" to listOf(
                    slot1Map + ("modifiedSectors" to mapOf("0" to Base64.getEncoder().encodeToString(ByteArray(16)))),
                    slot2Map,
                )),
                validSnapshot + ("slots" to listOf(
                    slot1Map,
                    slot2Map + ("sha256" to "f".repeat(64)),
                )),
                validSnapshot + ("slots" to listOf(
                    slot1Map + mapOf("inserted" to false, "sizeBytes" to null, "sha256" to null),
                    slot2Map,
                )),
            )

            for (candidate in tamperedCandidates) {
                assertFailsWith<IllegalArgumentException> {
                    targetReader.restoreSnapshot(candidate)
                }
                assertEquals(baselineState, targetReader.state())
                assertContentEquals(initialTargetSlot1, Files.readAllBytes(targetDir.resolve("slot1.img")))
            }

            Files.write(targetDir.resolve("slot2.img"), ByteArray(2048) { 0x33 })
            assertFailsWith<IllegalArgumentException> {
                targetReader.restoreSnapshot(validSnapshot)
            }
            assertContentEquals(initialTargetSlot1, Files.readAllBytes(targetDir.resolve("slot1.img")))
        }
    }
}
