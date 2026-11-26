package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class SetupSequence49530And49500Test {
    private class TestContext : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No system-control event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun create(write49530: Boolean = true, tail49500: Boolean = false): Pair<TestContext, StatefulComponent> {
        val ctx = TestContext()
        val factory = SystemControlFactory()
        val config = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "write49530" to write49530,
            "tail49500" to tail49500,
        )
        val comp = factory.create(ctx, config) as StatefulComponent
        comp.start()
        return ctx to comp
    }

    @Test
    fun tail49500RequiresWrite49530AndRefusesInvalidOrderWidthsReadsAndValues() {
        assertFailsWith<IllegalArgumentException> { create(write49530 = false, tail49500 = true) }

        val (ctx, comp) = create(write49530 = true, tail49500 = false)
        assertEquals(listOf(SetupSequence49530And49500.WRITE49530_PORT), comp.additionalMmioPorts())
        val pair = assertNotNull(ctx.ports.find(SetupSequence49530And49500.WRITE49530_PORT, MmioDevice::class.java))
        assertNull(ctx.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java))

        // Invalid accesses leave state unchanged
        assertFails { pair.read(0, 4) }
        assertFails { pair.write(0, 2, 0x704L) }
        assertFails { pair.write(2, 4, 0x10000704L) }
        assertFails { pair.write(0, 4, 0L) }
        assertFails { pair.write(4, 4, 0x70L) }

        pair.write(0, 4, 0x10000704L)
        assertFails { pair.write(0, 4, 0x10000704L) }
        pair.write(4, 4, 0x70L)
        // Step 2 (0x71) refused while tail49500 disabled
        assertFails { pair.write(4, 4, 0x71L) }
    }

    @Test
    fun fullSequenceWithTailRoundTripsSnapshotsAtEveryStepAndRejectsCorruptedSnapshots() {
        val (ctx1, comp1) = create(write49530 = true, tail49500 = true)
        assertEquals(
            listOf(SetupSequence49530And49500.WRITE49530_PORT, SetupSequence49530And49500.TAIL49500_PORT),
            comp1.additionalMmioPorts(),
        )
        val pair1 = assertNotNull(ctx1.ports.find(SetupSequence49530And49500.WRITE49530_PORT, MmioDevice::class.java))
        val tail1 = assertNotNull(ctx1.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java))

        // Step 0 -> 1
        val snap0 = comp1.saveSnapshot()
        pair1.write(0, 4, 0x10000704L)
        val snap1 = comp1.saveSnapshot()
        // Step 1 -> 2
        pair1.write(4, 4, 0x70L)
        val snap2 = comp1.saveSnapshot()
        // Tail refused before step 2 -> 3
        assertFails { tail1.write(0, 4, 3L) }
        // Step 2 -> 3
        pair1.write(4, 4, 0x71L)
        val snap3 = comp1.saveSnapshot()
        // Tail invalid accesses refused
        assertFails { tail1.read(0, 4) }
        assertFails { tail1.write(0, 1, 3L) }
        assertFails { tail1.write(4, 4, 3L) }
        assertFails { tail1.write(0, 4, 2L) }
        // Tail step 0 -> 1
        tail1.write(0, 4, 3L)
        val snap4 = comp1.saveSnapshot()
        assertFails { tail1.write(0, 4, 3L) }

        // Restore at each boundary and verify continuation
        val (_, c0) = create(write49530 = true, tail49500 = true)
        c0.restoreSnapshot(snap0)
        assertEquals(0, (c0.state()["write49530State"] as Map<*, *>)["position"])

        val (ctxR2, c2) = create(write49530 = true, tail49500 = true)
        c2.restoreSnapshot(snap2)
        ctxR2.ports.find(SetupSequence49530And49500.WRITE49530_PORT, MmioDevice::class.java)!!.write(4, 4, 0x71L)
        ctxR2.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java)!!.write(0, 4, 3L)

        val (ctxR3, c3) = create(write49530 = true, tail49500 = true)
        c3.restoreSnapshot(snap3)
        ctxR3.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java)!!.write(0, 4, 3L)

        val (ctxR4, c4) = create(write49530 = true, tail49500 = true)
        c4.restoreSnapshot(snap4)
        assertFails { ctxR4.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java)!!.write(0, 4, 3L) }

        // Tampered snapshots (tail pos 1 with pair pos 2) reject atomically
        val before = c4.state()
        val tampered = snap4 + ("write49530State" to mapOf("profile" to SetupSequence49530And49500.WRITE49530_PROFILE, "position" to 2))
        assertFailsWith<IllegalArgumentException> { c4.restoreSnapshot(tampered) }
        assertEquals(before, c4.state())
    }
}
