package com.github.nyabanban.fuji.emulator.components.gic

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*

class GicSnapshotTest {
    private class TestContext : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        var tick = 100L
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) { claims.clear() }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun readData(relative: String): Map<String, Any?> = error("Unexpected readData: $relative")
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private class GicHarness(val cluster: Boolean, val singleCoreSgi: Boolean = false) : AutoCloseable {
        val context = TestContext()
        val factory = GicFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default } +
            mapOf(
                "coreProfile" to if (cluster) "four-context-functional-experimental-v1" else "",
                "sgiProfile" to if (!cluster && singleCoreSgi) "single-core-experimental-v1" else "",
            )
        val component = factory.create(context, config) as StatefulComponent
        val distributor = checkNotNull(context.ports.find("soc-gic.distributor", MmioDevice::class.java))
        val cpuInterface = checkNotNull(context.ports.find("soc-gic.interface", MmioDevice::class.java))
        val cpuPort = checkNotNull(context.ports.find("soc-gic.cpu", CpuInterruptPort::class.java))
        val corePort = context.ports.find("soc-gic.cores", CoreInterruptPort::class.java)
        val lineSink = checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) as EdgeInterruptSink

        fun select(coreIndex: Int) {
            if (corePort != null) corePort.selectCore(coreIndex) else require(coreIndex == 0)
        }

        override fun close() {
            component.close()
        }
    }

    private fun jsonRoundTrip(raw: Any?): Any? = when (raw) {
        null, is Boolean, is String -> raw
        is Byte -> raw.toInt()
        is Short -> raw.toInt()
        is Int -> raw
        is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else raw
        is Double -> raw
        is List<*> -> raw.map { element -> jsonRoundTrip(element) }
        is Map<*, *> -> jsonRoundTripMap(raw)
        else -> error("Unsupported JSON snapshot value type: ${raw::class.java}")
    }

    private fun jsonRoundTripMap(raw: Map<*, *>): Map<String, Any?> {
        val converted = LinkedHashMap<String, Any?>()
        for ((key, entryValue) in raw) {
            check(key is String) { "Non-string snapshot key: $key" }
            converted[key] = jsonRoundTrip(entryValue)
        }
        return converted
    }

    @Test
    fun singleCoreMidPreemptionAndTruncatedLogRoundTripAndContinueIdentically() {
        GicHarness(cluster = false, singleCoreSgi = true).use { primary ->
            primary.distributor.write(0, 4, 1)
            primary.cpuInterface.write(0, 4, 1)
            primary.cpuInterface.write(4, 4, 0xf0)
            primary.cpuInterface.write(8, 4, 2)

            primary.distributor.write(0x400 + 3, 1, 0x40)
            primary.distributor.write(0x400 + 44, 1, 0x20)
            primary.distributor.write(0x400 + 45, 1, 0x60)
            primary.distributor.write(0xc08, 4, 2L shl 24)
            primary.distributor.write(0x104, 4, (1L shl 12) or (1L shl 13))

            repeat(260) { round ->
                primary.context.tick = 200L + round * 2
                primary.lineSink.setLine(60, true)
                primary.context.tick = 201L + round * 2
                primary.lineSink.setLine(60, false)
            }
            primary.context.tick = 1000L
            primary.distributor.write(0xf00, 4, 0x0200_0003L)
            assertEquals(3L, primary.cpuInterface.read(0xc, 4))
            primary.context.tick = 1005L
            primary.lineSink.setLine(44, true)
            primary.lineSink.setLine(44, false)
            primary.lineSink.setLine(45, true)
            assertEquals(44, primary.cpuPort.choose())
            assertEquals(44L, primary.cpuInterface.read(0xc, 4))
            primary.lineSink.setLine(44, true)

            val roundTripped = jsonRoundTripMap(primary.component.saveSnapshot())

            GicHarness(cluster = false, singleCoreSgi = true).use { restored ->
                restored.context.tick = primary.context.tick
                restored.component.restoreSnapshot(roundTripped)
                assertEquals(primary.component.state(), restored.component.state())
                assertEquals(primary.cpuPort.choose(), restored.cpuPort.choose())
                assertEquals(primary.lineSink.edgeState(44), restored.lineSink.edgeState(44))
                assertEquals(primary.lineSink.edgeState(45), restored.lineSink.edgeState(45))
                assertTrue(restored.component.state()["eventLogTruncated"] as Boolean)

                for (harness in listOf(primary, restored)) {
                    harness.context.tick = 1020L
                    harness.lineSink.setLine(44, false)
                    harness.cpuInterface.write(0x10, 4, 44)
                    assertEquals(44, harness.cpuPort.choose())
                    assertEquals(44L, harness.cpuInterface.read(0xc, 4))
                    harness.cpuInterface.write(0x10, 4, 44)
                    assertNull(harness.cpuPort.choose())
                    harness.cpuInterface.write(0x10, 4, 3)
                    assertEquals(45, harness.cpuPort.choose())
                    assertEquals(45L, harness.cpuInterface.read(0xc, 4))
                    harness.lineSink.setLine(45, false)
                    harness.cpuInterface.write(0x10, 4, 45)
                    assertNull(harness.cpuPort.choose())
                }
                assertEquals(primary.component.state(), restored.component.state())
            }
        }
    }

    @Test
    fun clusterFourCoreBankedStateSgiSendersAndActiveTokensRoundTripAndRejectCorruptionAtomically() {
        GicHarness(cluster = true).use { primary ->
            primary.distributor.write(0, 4, 1)
            for (coreIndex in 0..3) {
                primary.select(coreIndex)
                primary.cpuInterface.write(0, 4, 1)
                primary.cpuInterface.write(4, 4, 0xe0)
                primary.cpuInterface.write(8, 4, coreIndex.toLong())
                primary.distributor.write(0x400 + 5, 1, (0x20 + coreIndex * 8).toLong())
                primary.distributor.write(0x400 + 20, 1, 0x50)
            }
            primary.select(0)
            primary.distributor.write(0xc14, 4, 2L)
            primary.distributor.write(0x108, 4, (1L shl 16) or (1L shl 26))
            primary.distributor.write(0x850, 1, 1L shl 2)
            primary.distributor.write(0x85a, 1, 1L shl 3)
            primary.distributor.write(0x450, 1, 0x30)
            primary.distributor.write(0x45a, 1, 0x40)

            primary.context.tick = 5_000_000_000L
            primary.select(0)
            primary.distributor.write(0xf00, 4, 0x0002_0005L)
            primary.select(3)
            primary.distributor.write(0xf00, 4, 0x0002_0005L)

            primary.select(1)
            assertEquals(5L, primary.cpuInterface.read(0xc, 4))
            assertNull(primary.cpuPort.choose())

            primary.select(2)
            primary.distributor.write(0x100, 4, 1L shl 20)
            primary.distributor.write(0x200, 4, 1L shl 20)
            primary.lineSink.setLine(80, true)
            primary.lineSink.setLine(80, false)
            assertEquals(80L, primary.cpuInterface.read(0xc, 4))

            primary.lineSink.setLine(90, true)
            primary.select(2)

            val saved = primary.component.saveSnapshot()
            val roundTripped = jsonRoundTripMap(saved)

            GicHarness(cluster = true).use { restored ->
                restored.context.tick = primary.context.tick
                restored.component.restoreSnapshot(roundTripped)
                assertEquals(primary.component.state(), restored.component.state())
                for (coreIndex in 0..3) {
                    primary.select(coreIndex)
                    restored.select(coreIndex)
                    assertEquals(primary.cpuPort.choose(), restored.cpuPort.choose())
                }
                primary.select(2)
                restored.select(2)

                val stateBeforeBadRestore = restored.component.state()
                @Suppress("UNCHECKED_CAST")
                val sharedMap = roundTripped["shared"] as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val coresList = roundTripped["cores"] as List<Map<String, Any?>>
                val badCases = listOf<Map<String, Any?>>(
                    roundTripped + mapOf("schema" to 2),
                    roundTripped + mapOf("componentId" to "soc-timer"),
                    roundTripped + mapOf("mode" to "single-core"),
                    roundTripped + mapOf("currentCore" to 4),
                    roundTripped + mapOf("currentCore" to 1.5),
                    roundTripped + mapOf("extraKey" to true),
                    roundTripped + mapOf("settings" to emptyMap<String, Any>()),
                    roundTripped + mapOf("shared" to (sharedMap + mapOf("active" to Base64.getEncoder().encodeToString(ByteArray(60))))),
                    roundTripped + mapOf("cores" to coresList.mapIndexed { index, entry ->
                        if (index == 0) entry + mapOf("localEnabled" to Base64.getEncoder().encodeToString(ByteArray(4))) else entry
                    }),
                    roundTripped + mapOf("sgiSenders" to List(4) { List(16) { 0 } }),
                )
                for (badPayload in badCases) {
                    assertFails { restored.component.restoreSnapshot(badPayload) }
                    assertEquals(stateBeforeBadRestore, restored.component.state())
                }

                for (harness in listOf(primary, restored)) {
                    harness.context.tick = 5_000_000_010L
                    harness.select(1)
                    assertFails { harness.cpuInterface.write(0x10, 4, 0xc05) }
                    harness.cpuInterface.write(0x10, 4, 5)
                    assertEquals(5, harness.cpuPort.choose())
                    assertEquals(0xc05L, harness.cpuInterface.read(0xc, 4))
                    harness.cpuInterface.write(0x10, 4, 0xc05)
                    assertNull(harness.cpuPort.choose())

                    harness.select(2)
                    assertEquals(null, harness.cpuPort.choose())
                    harness.cpuInterface.write(0x10, 4, 80)
                    assertEquals(20, harness.cpuPort.choose())
                    assertEquals(20L, harness.cpuInterface.read(0xc, 4))
                    harness.cpuInterface.write(0x10, 4, 20)

                    harness.select(3)
                    assertEquals(90, harness.cpuPort.choose())
                    assertEquals(90L, harness.cpuInterface.read(0xc, 4))
                    harness.lineSink.setLine(90, false)
                    harness.cpuInterface.write(0x10, 4, 90)
                    assertNull(harness.cpuPort.choose())
                }
                assertEquals(primary.component.state(), restored.component.state())
            }
        }
    }
}
