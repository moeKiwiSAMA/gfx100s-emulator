package com.github.nyabanban.fuji.emulator.components.ispsetup

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class IspSetupFactoryTest {
    private class TestContext : ComponentContext {
        var currentTick = 10L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { currentTick }
        override fun readData(relative: String): Map<String, Any?> = emptyMap()
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
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private val factory = IspSetupFactory()

    private fun config(profile: String = IspSetupFactory.PROFILE, pollProfile: String = ""): Map<String, Any> =
        mapOf("profile" to profile, "initialFill" to 0L, "poll7801cProfile" to pollProfile)

    @Test
    fun disabledProfileRegistersNoPortsAndRejectsPollProfile() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config(profile = "disabled"))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertTrue(ctx.claims.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            factory.create(ctx, config(profile = "disabled", pollProfile = IspSetupFactory.POLL_ONCE))
        }
        comp.close()
    }

    @Test
    fun ctrlBanksHandleAlignedWordsRejectOffset4HoleAndLeaveF01cUnmapped() {
        val ctx = TestContext()
        val comp = factory.create(ctx, config())
        assertEquals(IspSetupFactory.ALL_PORTS, comp.additionalMmioPorts())

        val c0Low = ctx.ports.find(IspSetupFactory.PORT_CTRL0_LOW, MmioDevice::class.java)!!
        val c0High = ctx.ports.find(IspSetupFactory.PORT_CTRL0_HIGH, MmioDevice::class.java)!!
        val c1Low = ctx.ports.find(IspSetupFactory.PORT_CTRL1_LOW, MmioDevice::class.java)!!
        val c1High = ctx.ports.find(IspSetupFactory.PORT_CTRL1_HIGH, MmioDevice::class.java)!!

        // +0x00, +0x08, +0x0c are write-only; +0x04 and +0x10 are read-write
        c0Low.write(0L, 4, 0x1000L)
        c0Low.write(8L, 4, 0x1002L)
        c0Low.write(12L, 4, 0x1003L)
        assertFailsWith<IllegalArgumentException> { c0Low.read(0L, 4) }
        assertFailsWith<IllegalArgumentException> { c0Low.read(8L, 4) }
        assertFailsWith<IllegalArgumentException> { c0Low.read(12L, 4) }

        for (off in listOf(4L, 16L)) {
            val expected = 0x1000L + off
            c0Low.write(off, 4, expected)
            c1Low.write(off, 4, expected + 0x2000L)
            assertEquals(expected, c0Low.read(off, 4))
            assertEquals(expected + 0x2000L, c1Low.read(off, 4))
        }
        // Out of ctrl0-low bounds (+20 would be 0xfff4f01c INV-SYS-F01C-001!)
        assertFailsWith<IllegalArgumentException> { c0Low.read(20L, 4) }
        assertFailsWith<IllegalArgumentException> { c0Low.write(20L, 4, 1L) }

        // High banks are write-only at offsets 0 and 8, with offset 4 hole refused
        c0High.write(0L, 4, 0xaaaaL)
        c0High.write(8L, 4, 0xbbbbL)
        c1High.write(0L, 4, 0xccccL)
        c1High.write(8L, 4, 0xddddL)
        assertFailsWith<IllegalArgumentException> { c0High.read(0L, 4) }
        assertFailsWith<IllegalStateException> { c0High.write(4L, 4, 1L) }
        assertFailsWith<IllegalArgumentException> { c1High.read(8L, 4) }
        assertFailsWith<IllegalStateException> { c1High.write(4L, 4, 1L) }
        comp.close()
    }

    @Test
    fun poll7801cEnforcesOrderAndSupportsOnceToRepeatSnapshotPromotion() {
        val ctx = TestContext()
        val once = factory.create(ctx, config(pollProfile = IspSetupFactory.POLL_ONCE)) as StatefulComponent
        val poll = ctx.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!

        // Cannot read before write 1
        assertFailsWith<IllegalArgumentException> { poll.read(0L, 4) }
        assertFailsWith<IllegalArgumentException> { poll.write(0L, 4, 2L) }

        poll.write(0L, 4, 1L)
        assertEquals(0L, poll.read(0L, 4))
        // Once cycle spent
        assertFailsWith<IllegalArgumentException> { poll.write(0L, 4, 1L) }

        val snap = once.saveSnapshot()
        val ctx2 = TestContext()
        val repeat = factory.create(ctx2, config(pollProfile = IspSetupFactory.POLL_REPEAT)) as StatefulComponent
        repeat.restoreSnapshot(snap)
        val poll2 = ctx2.ports.find(IspSetupFactory.PORT_CTRL1_POLL, MmioDevice::class.java)!!
        poll2.write(0L, 4, 1L)
        assertEquals(0L, poll2.read(0L, 4))
        assertFailsWith<IllegalArgumentException> { poll2.write(0L, 4, 1L) }

        // Downgrade or disabled poll refuses snapshot
        val ctx3 = TestContext()
        val disabledPoll = factory.create(ctx3, config(pollProfile = "")) as StatefulComponent
        assertFailsWith<IllegalArgumentException> { disabledPoll.restoreSnapshot(snap) }

        (once as HardwareComponent).close()
        (repeat as HardwareComponent).close()
        (disabledPoll as HardwareComponent).close()
    }
}
