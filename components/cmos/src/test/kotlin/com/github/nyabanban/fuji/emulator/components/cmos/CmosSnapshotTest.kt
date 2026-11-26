package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CmosSnapshotTest {
    private class CmosTestHarness(private val rootDirectory: Path) {
        lateinit var sensorPort: SensorPort
        val context = object : ComponentContext {
            override val directory: Path = rootDirectory
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    assertEquals("cmos", owner)
                    assertEquals("cmos", name)
                    assertEquals<Class<*>>(SensorPort::class.java, type)
                    sensorPort = port as SensorPort
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = error("unexpected cmos event")
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("unexpected subscribe")
            }
            override fun dataPath(relative: String): Path {
                val resolved = rootDirectory.resolve(relative).normalize()
                require(resolved.startsWith(rootDirectory) && resolved != rootDirectory) { "path outside directory" }
                return resolved
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTripMap(map: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTripValue(map) as Map<String, Any?>

    private fun simulateJsonRoundTripValue(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int -> (value as Number).toInt()
        is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else value
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTripValue(element) }
        is Map<*, *> -> value.entries.associate { entry -> (entry.key as String) to simulateJsonRoundTripValue(entry.value) }
        else -> error("Unsupported non-JSON value: ${value::class.java.name}")
    }

    private fun withTemporaryDirectory(action: (Path) -> Unit) {
        val tempDirectory = Files.createTempDirectory("cmos-snapshot-test-")
        try {
            action(tempDirectory)
        } finally {
            Files.walk(tempDirectory)
                .sorted(Comparator.reverseOrder())
                .forEach { target -> Files.deleteIfExists(target) }
        }
    }

    @Test
    fun roundTripPreservesValidatedSourceSettingsAndRejectsMissingFilesAtomically() = withTemporaryDirectory { tempDirectory ->
        Files.writeString(tempDirectory.resolve("sample.raf"), "stub")
        Files.writeString(tempDirectory.resolve("next.h264"), "stub")
        val factory = CmosFactory()
        val defaultConfig = mapOf("sourceKind" to "JPEG", "sourceFile" to "", "loop" to false)

        val harnessOne = CmosTestHarness(tempDirectory)
        val cmosOne = factory.create(harnessOne.context, defaultConfig) as StatefulComponent
        val change = mapOf("sourceKind" to "RAF", "sourceFile" to "sample.raf", "loop" to true)
        cmosOne.validateSettings(change)
        cmosOne.applySettings(change)

        val snapshot = simulateJsonRoundTripMap(cmosOne.saveSnapshot())

        val harnessTwo = CmosTestHarness(tempDirectory)
        val cmosTwo = factory.create(harnessTwo.context, defaultConfig) as StatefulComponent
        cmosTwo.restoreSnapshot(snapshot)
        assertEquals(cmosOne.state(), cmosTwo.state())

        val baselineState = cmosTwo.state()
        @Suppress("UNCHECKED_CAST")
        val validScalars = snapshot["scalarValues"] as Map<String, Any?>
        val invalidCandidates = listOf<Map<String, Any?>>(
            snapshot + ("schema" to 2),
            snapshot + ("componentId" to "lens"),
            snapshot + ("scalarValues" to (validScalars + ("sourceFile" to "missing.raf") + ("loop" to false))),
            snapshot + ("scalarValues" to (validScalars + ("sourceKind" to "PNG"))),
            snapshot + ("scalarValues" to (validScalars + ("sourceKind" to "UNKNOWN_KIND"))),
            snapshot + ("scalarValues" to (validScalars + ("loop" to "true"))),
        )
        for (candidate in invalidCandidates) {
            assertFails { cmosTwo.restoreSnapshot(candidate) }
            assertEquals(baselineState, cmosTwo.state())
        }

        val nextChange = mapOf("sourceKind" to "H264", "sourceFile" to "next.h264", "loop" to false)
        cmosOne.validateSettings(nextChange)
        cmosOne.applySettings(nextChange)
        cmosTwo.validateSettings(nextChange)
        cmosTwo.applySettings(nextChange)
        assertEquals(cmosOne.state(), cmosTwo.state())
        assertEquals(cmosOne.saveSnapshot(), cmosTwo.saveSnapshot())
    }
}
