package com.github.nyabanban.fuji.emulator.components.timer

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

/**
 * Hardware-fidelity tests for SPX4 `soc-timer` (`0xFFF3C300`) per-channel write-mask (`bits[23:16]`)
 * and `Ch6` (display/backlight IRQ 63) vs `Ch7` (ThreadX 10 ms OS tick IRQ 64) state.
 *
 * Verifies the real-silicon finding from `boot.bin`, `kernel.bin`, and `section4.bin`:
 * - Writing `0x00800000` (mask bit 23 only) disables ONLY `Ch7` while keeping `Ch6` display timer alive.
 * - Writing blind `0x00FF0000` disables `Ch0..Ch6` alongside `Ch7`, stopping `Ch6` IRQ 63 and
 *   recording both `ch6DisableCount` and `blindMultiChannelDisableCount` plus a `display-timer-disabled` event.
 */
class TimerChannelWriteMaskTest {
    private class RecordingEdgeSink : EdgeInterruptSink {
        val irqs = ArrayList<Int>()
        override fun edgeState(irq: Int) = EdgeInterruptState(edge = true, high = false, pending = false)
        override fun setLine(irq: Int, high: Boolean) {
            if (high) irqs.add(irq)
        }
    }

    private class Context(val routesData: Map<String, Map<String, Any?>>) : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        val sink = RecordingEdgeSink()
        val publishedEvents = ArrayList<ComponentEvent>()
        var tick = 0L

        init {
            claims["soc-gic.lines"] = sink
        }

        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                claims.keys.removeIf { it != "soc-gic.lines" }
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {
                publishedEvents.add(event)
            }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = routesData.getValue(relative)
    }

    private fun routedRoutes(): Map<String, Map<String, Any?>> = mapOf(
        "timer6-7.json" to mapOf(
            "profile" to TimerFactory.ROUTED,
            "clock" to "one-tick-per-attempt",
            "routes" to listOf(
                mapOf("channel" to 6, "irq" to 63),
                mapOf("channel" to 7, "irq" to 64),
            ),
        ),
    )

    @Test
    fun ch7OnlyMask0x00800000PreservesCh6DisplayTimerWhileBlind0x00FF0000DisablesCh6AndIsObservable() {
        val ctx = Context(routedRoutes())
        val config = mapOf(
            "profile" to TimerFactory.ROUTED,
            "inputFile" to "timer6-7.json",
            "irqSink" to "soc-gic.lines",
        )
        val timer = TimerFactory().create(ctx, config) as TimerFactory.Timer
        val audit = ctx.claims.getValue("soc-timer.channels") as TimerChannelObservabilityPort

        // Program Ch6 (offset 0x40/0x44, IRQ 63) and Ch7 (offset 0x48/0x4C, IRQ 64)
        ctx.tick = 10L
        timer.write(0x40, 4, 9L)
        timer.write(0x44, 4, 9L)
        timer.write(0x48, 4, 19L)
        timer.write(0x4c, 4, 19L)
        // Enable Ch6 + Ch7 via bits[23:16] = 0xC0, bits[7:0] = 0xC0
        timer.write(0x00, 4, 0x00c0_00c0L)
        assertEquals(0xc0L, timer.read(0x00, 4))
        assertTrue(audit.channelAuditState().ch6DisplayTimerActive)
        assertTrue(audit.channelAuditState().ch7OsTickTimerActive)

        // Safe stock/boot_linux.S Ch7-only quiesce: write 0x00800000 (write-mask bit 23 only, enable bit 7 = 0)
        ctx.tick = 15L
        timer.write(0x00, 4, 0x0080_0000L)
        assertEquals(0x40L, timer.read(0x00, 4), "Ch6 must remain enabled when only Ch7 mask bit 23 is written")
        val afterSafe = audit.channelAuditState()
        assertTrue(afterSafe.ch6DisplayTimerActive)
        assertFalse(afterSafe.ch7OsTickTimerActive)
        assertEquals(0x80, afterSafe.lastControlWriteMask)
        assertEquals(0x00, afterSafe.lastControlWriteEnableBits)
        assertEquals(0L, afterSafe.ch6DisableCount)
        assertEquals(0L, afterSafe.blindMultiChannelDisableCount)
        assertEquals(1L, afterSafe.perChannelDisableCounts[7])
        assertTrue(ctx.publishedEvents.isEmpty(), "Safe Ch7-only disable must not emit display-timer-disabled")

        // Advance to tick 20 -> Ch6 (IRQ 63) still expires cleanly; Ch7 (IRQ 64) does NOT fire
        ctx.tick = 20L
        timer.beforeInstruction(20L)
        assertEquals(listOf(63), ctx.sink.irqs)

        // Re-enable Ch7 (0x00800080), then simulate buggy bootloader blind write 0x00FF0000!
        ctx.tick = 22L
        timer.write(0x00, 4, 0x0080_0080L)
        assertEquals(0xc0L, timer.read(0x00, 4))
        ctx.tick = 25L
        timer.write(0x00, 4, 0x00ff_0000L)
        assertEquals(0x00L, timer.read(0x00, 4), "Blind 0x00FF0000 stops all channels including Ch6")
        val afterBlind = audit.channelAuditState()
        assertFalse(afterBlind.ch6DisplayTimerActive)
        assertFalse(afterBlind.ch7OsTickTimerActive)
        assertEquals(0xff, afterBlind.lastControlWriteMask)
        assertEquals(0x00, afterBlind.lastControlWriteEnableBits)
        assertEquals(1L, afterBlind.ch6DisableCount)
        assertEquals(1L, afterBlind.blindMultiChannelDisableCount)
        assertEquals(1L, afterBlind.perChannelDisableCounts[6])
        assertEquals(2L, afterBlind.perChannelDisableCounts[7])
        assertEquals(1, ctx.publishedEvents.size)
        assertEquals("display-timer-disabled", ctx.publishedEvents.single().kind)

        // Advance to tick 40 -> Ch6 IRQ 63 no longer fires
        ctx.sink.irqs.clear()
        ctx.tick = 40L
        timer.beforeInstruction(40L)
        assertTrue(ctx.sink.irqs.isEmpty())

        // Snapshot round-trip preserves channelAudit and stays backward-compatible with legacy snapshots without it
        val snap = timer.saveSnapshot()
        assertTrue("channelAudit" in snap)
        val ctx2 = Context(routedRoutes()).apply { tick = 40L }
        val restored = TimerFactory().create(ctx2, config) as TimerFactory.Timer
        restored.restoreSnapshot(snap)
        val restoredAudit = (ctx2.claims.getValue("soc-timer.channels") as TimerChannelObservabilityPort).channelAuditState()
        assertEquals(afterBlind, restoredAudit)

        // Legacy snapshot without channelAudit key restores cleanly with zeroed audit counters
        val legacySnap = snap - "channelAudit"
        restored.restoreSnapshot(legacySnap)
        val legacyAudit = (ctx2.claims.getValue("soc-timer.channels") as TimerChannelObservabilityPort).channelAuditState()
        assertEquals(0L, legacyAudit.ch6DisableCount)
        assertEquals(0L, legacyAudit.blindMultiChannelDisableCount)
    }
}
