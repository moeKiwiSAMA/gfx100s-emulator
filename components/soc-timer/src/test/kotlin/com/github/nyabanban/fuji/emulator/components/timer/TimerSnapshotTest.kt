package com.github.nyabanban.fuji.emulator.components.timer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class TimerSnapshotTest {
    private class RecordingEdgeSink : EdgeInterruptSink {
        val pulses = ArrayList<Pair<Int, Boolean>>()
        var pendingFlag = false
        override fun edgeState(irq: Int) = EdgeInterruptState(edge = true, high = false, pending = pendingFlag)
        override fun setLine(irq: Int, high: Boolean) {
            pulses.add(irq to high)
        }
    }

    private class TimerTestContext(val routesData: Map<String, Map<String, Any?>>) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        val sink = RecordingEdgeSink()
        var tick = 0L
        var core = 1
        var pcVirtual = 0x0123_4568L
        var withLocation = true

        init {
            claims["soc-gic.lines"] = sink
        }

        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? {
                if (name == "cpu.execution-location") {
                    return if (withLocation) type.cast(ExecutionLocationSource { ExecutionLocation(core, pcVirtual) }) else null
                }
                return claims[name]?.let(type::cast)
            }
            override fun removeOwner(owner: String) {
                claims.keys.removeIf { key -> key != "soc-gic.lines" }
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> =
            routesData[relative] ?: error("Unexpected timer data file: $relative")
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

    private fun routeMap(vararg pairs: Pair<Int, Int>, clock: String = "one-tick-per-attempt"): Map<String, Any?> = mapOf(
        "profile" to TimerFactory.ROUTED,
        "clock" to clock,
        "routes" to pairs.map { (channel, irq) -> mapOf("channel" to channel, "irq" to irq) },
    )

    @Test
    fun routedTimerMidCountdownCoalescedExpiriesAndTruncatedLogsRoundTripAndContinueIdentically() {
        val files = mapOf("routes.yaml" to routeMap(0 to 56, 2 to 59, 7 to 63))
        val factory = TimerFactory()
        val config = mapOf("profile" to TimerFactory.ROUTED, "inputFile" to "routes.yaml", "irqSink" to "soc-gic.lines")

        val primaryCtx = TimerTestContext(files)
        val primary = factory.create(primaryCtx, config) as TimerFactory.Timer

        primaryCtx.tick = 10L
        primary.write(0x10, 4, 9L)
        primary.write(0x14, 4, 4L)
        primary.write(0x20, 4, 25L)
        primary.write(0x24, 4, 19L)
        primary.write(0x48, 4, 100L)
        primary.write(0x4c, 4, 50L)
        primary.write(0x00, 4, (1L shl 16) or 1L or (1L shl 18) or (1L shl 2) or (1L shl 23) or (1L shl 7))

        repeat(270) { step ->
            val nextTick = 20L + step * 5L
            primaryCtx.tick = nextTick
            primaryCtx.core = step % 4
            primaryCtx.pcVirtual = 0x0100_0000L + step * 4L
            primary.beforeInstruction(nextTick)
            primary.read(0x10, 4)
            primary.read(0x20, 4)
        }

        primaryCtx.tick = 1372L
        primaryCtx.withLocation = false
        primary.beforeInstruction(1372L)
        primary.write(0x00, 4, 1L shl 23)
        assertEquals(false, ((primary.state()["counters"] as List<*>)[7] as Map<*, *>)["enabled"])

        val saved = primary.saveSnapshot()
        val roundTripped = jsonRoundTripMap(saved)

        val restoredCtx = TimerTestContext(files)
        restoredCtx.tick = primaryCtx.tick
        restoredCtx.withLocation = false
        val restored = factory.create(restoredCtx, config) as TimerFactory.Timer
        restored.restoreSnapshot(roundTripped)

        assertEquals(primary.state(), restored.state())
        assertEquals(primary.nextDeadline(), restored.nextDeadline())
        assertTrue(restored.state()["eventLogTruncated"] as Boolean)
        assertTrue(restored.state()["accessLogTruncated"] as Boolean)

        primaryCtx.sink.pulses.clear()
        restoredCtx.sink.pulses.clear()
        for ((ctx, timer) in listOf(primaryCtx to primary, restoredCtx to restored)) {
            ctx.withLocation = true
            ctx.core = 2
            ctx.pcVirtual = 0x03c0_1000L
            for (stepTick in listOf(1374L, 1375L, 1380L, 1393L)) {
                ctx.tick = stepTick
                timer.beforeInstruction(stepTick)
                timer.read(0x10, 4)
                timer.read(0x20, 4)
                timer.read(0x48, 4)
            }
            ctx.tick = 1395L
            timer.write(0x00, 4, (1L shl 16) or (1L shl 18))
            assertNull(timer.nextDeadline())
        }
        assertEquals(primaryCtx.sink.pulses, restoredCtx.sink.pulses)
        assertEquals(primary.nextDeadline(), restored.nextDeadline())
        assertEquals(primary.state(), restored.state())
    }

    @Test
    fun strictSnapshotCompatibleRouteFileAdditionAndAtomicMalformedRejection() {
        val factory = TimerFactory()
        val strictConfig = mapOf("profile" to "strict", "inputFile" to "", "irqSink" to "soc-gic.lines")
        val strictCtx1 = TimerTestContext(emptyMap())
        val strict1 = factory.create(strictCtx1, strictConfig) as TimerFactory.Timer
        strictCtx1.tick = 50L
        strict1.write(0x18, 4, 12L)
        strict1.write(0x1c, 4, 9L)
        strict1.write(0x00, 4, (1L shl 17) or (1L shl 1))
        strictCtx1.tick = 55L

        val strictSnap = jsonRoundTripMap(strict1.saveSnapshot())
        val strictCtx2 = TimerTestContext(emptyMap())
        strictCtx2.tick = 55L
        val strict2 = factory.create(strictCtx2, strictConfig) as TimerFactory.Timer
        strict2.restoreSnapshot(strictSnap)
        assertEquals(strict1.state(), strict2.state())
        assertEquals(strict1.nextDeadline(), strict2.nextDeadline())

        val files = mapOf(
            "routes-v1.yaml" to routeMap(0 to 56),
            "routes-v2.yaml" to routeMap(0 to 56, 1 to 58),
        )
        val routedCtx1 = TimerTestContext(files)
        val routed1 = factory.create(
            routedCtx1,
            mapOf("profile" to TimerFactory.ROUTED, "inputFile" to "routes-v1.yaml", "irqSink" to "soc-gic.lines"),
        ) as TimerFactory.Timer
        routedCtx1.tick = 100L
        routed1.beforeInstruction(100L)
        routed1.write(0x10, 4, 4L)
        routed1.write(0x14, 4, 4L)
        routed1.write(0x00, 4, (1L shl 16) or 1L)

        val routedSnap = jsonRoundTripMap(routed1.saveSnapshot())
        val routedCtx2 = TimerTestContext(files)
        routedCtx2.tick = 100L
        val routed2 = factory.create(
            routedCtx2,
            mapOf("profile" to TimerFactory.ROUTED, "inputFile" to "routes-v2.yaml", "irqSink" to "soc-gic.lines"),
        ) as TimerFactory.Timer
        routed2.restoreSnapshot(routedSnap)
        assertEquals(105L, routed2.nextDeadline())

        routedCtx2.tick = 102L
        routed2.beforeInstruction(102L)
        routed2.write(0x18, 4, 1L)
        routed2.write(0x1c, 4, 3L)
        routed2.write(0x00, 4, (1L shl 17) or (1L shl 1))
        assertEquals(104L, routed2.nextDeadline())
        routedCtx2.tick = 105L
        routed2.beforeInstruction(105L)
        assertEquals(listOf(56 to true, 56 to false, 58 to true, 58 to false), routedCtx2.sink.pulses)

        val stateBeforeBad = routed2.state()
        val deadlineBeforeBad = routed2.nextDeadline()
        assertFails { strict2.restoreSnapshot(routedSnap) }
        assertFails { routed2.restoreSnapshot(strictSnap) }

        @Suppress("UNCHECKED_CAST")
        val countersList = routedSnap["counters"] as List<Map<String, Any?>>
        val badPayloads = listOf<Map<String, Any?>>(
            routedSnap + mapOf("schema" to 99),
            routedSnap + mapOf("componentId" to "soc-gic"),
            routedSnap + mapOf("clockName" to "functional-device-ticks-with-idle"),
            routedSnap + mapOf("lastTick" to -1),
            routedSnap + mapOf("lastTick" to 2.5),
            routedSnap + mapOf("eventCount" to -1),
            routedSnap + mapOf("counters" to countersList.take(7)),
            routedSnap + mapOf("counters" to countersList.mapIndexed { index, entry ->
                if (index == 0) entry + mapOf("deadline" to null) else entry
            }),
            routedSnap + mapOf("counters" to countersList.mapIndexed { index, entry ->
                if (index == 1) entry + mapOf("deadline" to 200) else entry
            }),
            routedSnap + mapOf("counters" to countersList.mapIndexed { index, entry ->
                if (index == 0) entry + mapOf("reload" to 0x1_0000_0000L) else entry
            }),
        )
        for (badPayload in badPayloads) {
            assertFails { routed2.restoreSnapshot(badPayload) }
            assertEquals(stateBeforeBad, routed2.state())
            assertEquals(deadlineBeforeBad, routed2.nextDeadline())
        }
    }
}
