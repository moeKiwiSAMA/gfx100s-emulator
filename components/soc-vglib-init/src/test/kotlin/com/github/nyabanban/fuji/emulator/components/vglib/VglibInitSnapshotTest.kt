package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class VglibInitSnapshotTest {
    private fun snapshotOffsets(): List<Long> =
        listOf(8L, 0x30L, 0x14L, 0x20L) +
            (0x40L..0x68L step 4) +
            (0x130L..0x1c0L step 4) +
            (0x1c8L..0x320L step 4) +
            listOf(0x600L)

    private inner class VglibTestContext : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        var tick = 0L
        var core = 0
        var pcVirtual = 0x0005_afe0L
        var withLocation = true

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
                claims.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("Unexpected event in vglib")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = when (relative) {
            "bank" -> mapOf(
                "schema" to 1,
                "profile" to VglibInitFactory.PROFILE,
                "readConfidence" to "HYPOTHESIS",
                "transactions" to List(18) { index ->
                    mapOf(
                        "offset" to (index * 4),
                        "readValue" to (0x8000_0000L + index),
                        "writeValue" to (0x1000L + index),
                    )
                },
            )
            "mode" -> mapOf(
                "schema" to 1,
                "profile" to ModeWordInput.PROFILE,
                "readConfidence" to "HYPOTHESIS",
                "readSample" to 0xa5a5_ffffL,
                "sourcePreserveMask" to 0xffff_fcffL,
            )
            "snapshot" -> mapOf(
                "schema" to 1,
                "profile" to LcdcSnapshotInput.PROFILE,
                "readConfidence" to "HYPOTHESIS",
                "reads" to snapshotOffsets().mapIndexed { index, offset ->
                    mapOf("offset" to offset, "value" to (0x4000_0000L + index))
                },
            )
            "unready" -> mapOf(
                "schema" to 1,
                "profile" to LcdcSnapshotInput.UNREADY_PROFILE,
                "readConfidence" to "HYPOTHESIS",
                "reads" to snapshotOffsets().mapIndexed { index, offset ->
                    mapOf("offset" to offset, "value" to index.toLong())
                },
                "controlReadLimit" to 4,
                "controlInitialValue" to 0,
                "controlWriteValue" to 0,
                "controlReadValue" to 0,
            )
            else -> error("Unexpected relative data path: $relative")
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
    fun functionalProfileSequenceModeWordAndBothBanksRoundTripAndContinueIdentically() {
        val factory = VglibInitFactory()
        val config = mapOf(
            "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank",
            "controlWordInputFile" to "mode",
            "lcdcSnapshotInputFile" to "",
        )
        val primaryCtx = VglibTestContext()
        val primary = factory.create(primaryCtx, config) as StatefulComponent
        val primaryRegs = checkNotNull(primaryCtx.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
        val primaryMode = checkNotNull(primaryCtx.ports.find(ModeWordInput.PORT, MmioDevice::class.java))
        val primaryBank0 = checkNotNull(primaryCtx.ports.find(LcdcFunctional.port(0), MmioDevice::class.java))
        val primaryBank1 = checkNotNull(primaryCtx.ports.find(LcdcFunctional.port(1), MmioDevice::class.java))

        for (pairIndex in 0 until 7) {
            primaryCtx.tick = 10L + pairIndex * 2L
            primaryCtx.core = pairIndex % 4
            assertEquals(0x8000_0000L + pairIndex, primaryRegs.read(pairIndex * 4L, 4))
            primaryCtx.tick = 11L + pairIndex * 2L
            primaryRegs.write(pairIndex * 4L, 4, 0x1000L + pairIndex)
        }
        primaryCtx.tick = 30L
        assertEquals(0xa5a5_ffffL, primaryMode.read(0L, 4))

        primaryCtx.tick = 40L
        primaryBank0.write(0x10, 4, 0xdead_beefL)
        assertEquals(0xdead_beefL, primaryBank0.read(0x10, 4))

        primaryCtx.tick = 50L
        primaryCtx.withLocation = false
        assertEquals(0L, primaryBank1.read(0L, 4))
        primaryCtx.withLocation = true
        primaryBank1.write(0L, 4, 0L)
        repeat(4) { pollRound ->
            primaryCtx.tick = 60L + pollRound
            assertEquals(1L, primaryBank1.read(0L, 4))
        }
        primaryBank1.write(4L, 4, 1L)

        val saved = primary.saveSnapshot()
        val roundTripped = jsonRoundTripMap(saved)

        val restoredCtx = VglibTestContext()
        val restored = factory.create(restoredCtx, config) as StatefulComponent
        val restoredRegs = checkNotNull(restoredCtx.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
        val restoredMode = checkNotNull(restoredCtx.ports.find(ModeWordInput.PORT, MmioDevice::class.java))
        val restoredBank0 = checkNotNull(restoredCtx.ports.find(LcdcFunctional.port(0), MmioDevice::class.java))
        val restoredBank1 = checkNotNull(restoredCtx.ports.find(LcdcFunctional.port(1), MmioDevice::class.java))

        restored.restoreSnapshot(roundTripped)
        assertEquals(primary.state(), restored.state())

        for ((ctx, regs, modePort, bank0, bank1) in listOf(
             Quintuple(primaryCtx, primaryRegs, primaryMode, primaryBank0, primaryBank1),
            Quintuple(restoredCtx, restoredRegs, restoredMode, restoredBank0, restoredBank1),
        )) {
            ctx.tick = 100L
            ctx.core = 3
            ctx.pcVirtual = 0x0126_45c0L
            modePort.write(0L, 4, 0xa5a5_fcffL)
            assertFails { modePort.read(0L, 4) }
            for (pairIndex in 7 until 18) {
                ctx.tick = 200L + pairIndex * 2L
                assertEquals(0x8000_0000L + pairIndex, regs.read(pairIndex * 4L, 4))
                ctx.tick = 201L + pairIndex * 2L
                regs.write(pairIndex * 4L, 4, 0x1000L + pairIndex)
            }
            assertFails { regs.read(0L, 4) }
            assertEquals(0xdead_beefL, bank0.read(0x10, 4))
            assertEquals(1L, bank1.read(0L, 4))
            assertEquals(1L, bank1.read(4L, 4))
        }
        assertEquals(primary.state(), restored.state())
    }

    private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

    @Test
    fun unreadyControlCompatibleFunctionalUpgradeAndAtomicMalformedRejection() {
        val factory = VglibInitFactory()
        val unreadyConfig = mapOf(
            "profile" to VglibInitFactory.UNREADY_PROFILE,
            "inputFile" to "bank",
            "controlWordInputFile" to "",
            "lcdcSnapshotInputFile" to "unready",
        )
        val ctx1 = VglibTestContext()
        val comp1 = factory.create(ctx1, unreadyConfig) as StatefulComponent
        val lcd1 = checkNotNull(ctx1.ports.find(LcdcSnapshotInput.UNREADY_PORT, MmioDevice::class.java))
        snapshotOffsets().forEachIndexed { index, offset ->
            ctx1.tick = index.toLong()
            assertEquals(index.toLong(), lcd1.read(offset, 4))
        }
        ctx1.tick = 500L
        lcd1.write(0L, 4, 0L)
        ctx1.tick = 501L
        assertEquals(0L, lcd1.read(0L, 4))

        val unreadySnap = jsonRoundTripMap(comp1.saveSnapshot())
        val ctx2 = VglibTestContext()
        val comp2 = factory.create(ctx2, unreadyConfig) as StatefulComponent
        val lcd2 = checkNotNull(ctx2.ports.find(LcdcSnapshotInput.UNREADY_PORT, MmioDevice::class.java))
        comp2.restoreSnapshot(unreadySnap)
        assertEquals(comp1.state(), comp2.state())

        repeat(3) { pollIndex ->
            for ((ctx, port) in listOf(ctx1 to lcd1, ctx2 to lcd2)) {
                ctx.tick = 600L + pollIndex
                assertEquals(0L, port.read(0L, 4))
            }
        }
        assertFails { lcd1.read(0L, 4) }
        assertFails { lcd2.read(0L, 4) }
        assertEquals(comp1.state(), comp2.state())

        val initCtx = VglibTestContext()
        val initComp = factory.create(
            initCtx,
            mapOf("profile" to VglibInitFactory.PROFILE, "inputFile" to "bank", "controlWordInputFile" to "", "lcdcSnapshotInputFile" to ""),
        ) as StatefulComponent
        val initRegs = checkNotNull(initCtx.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
        initCtx.tick = 15L
        assertEquals(0x8000_0000L, initRegs.read(0L, 4))
        initRegs.write(0L, 4, 0x1000L)
        val initSnap = jsonRoundTripMap(initComp.saveSnapshot())

        val upgradedCtx = VglibTestContext()
        val upgraded = factory.create(
            upgradedCtx,
            mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE, "inputFile" to "bank", "controlWordInputFile" to "", "lcdcSnapshotInputFile" to ""),
        ) as StatefulComponent
        upgraded.restoreSnapshot(initSnap)
        assertEquals(2, upgraded.state()["transactionCount"])
        val upgradedRegs = checkNotNull(upgradedCtx.ports.find("soc-vglib-init.registers", MmioDevice::class.java))
        assertEquals(0x8000_0001L, upgradedRegs.read(4L, 4))

        val stateBeforeBad = comp2.state()
        @Suppress("UNCHECKED_CAST")
        val sequenceMap = unreadySnap["sequence"] as Map<String, Any?>
        val badPayloads = listOf<Map<String, Any?>>(
            unreadySnap + mapOf("schema" to 0),
            unreadySnap + mapOf("componentId" to "soc-method4"),
            unreadySnap + mapOf("active" to false),
            unreadySnap + mapOf("sequence" to (sequenceMap + mapOf("position" to 37))),
            unreadySnap + mapOf("sequence" to (sequenceMap + mapOf("position" to 2.25))),
            unreadySnap + mapOf("modeWord" to mapOf("sample" to 0, "preserveMask" to 0, "position" to 0, "transactions" to emptyList<Any>())),
            initSnap,
        )
        for (badPayload in badPayloads) {
            assertFails { comp2.restoreSnapshot(badPayload) }
            assertEquals(stateBeforeBad, comp2.state())
        }
        assertFails { initComp.restoreSnapshot(unreadySnap) }
    }
}
