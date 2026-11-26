package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Lcdc0ContinuousIrqsTest {

    @Test
    fun lcdc0ContinuousIrqsFire112And113OnlyWhenEnabledAndNonPending() {
        val ctx = LcdcFunctionalTest.Context()
        val enabled = mutableSetOf<Int>()
        val edge = mutableSetOf<Int>()
        val highLines = mutableSetOf<Int>()
        val pending = mutableSetOf<Int>()
        val pulses = mutableListOf<Int>()
        ctx.entries[LcdcIrq118.SINK] = object : EnabledEdgeInterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                if (high) {
                    highLines += irq
                    pending += irq
                    pulses += irq
                } else {
                    highLines -= irq
                }
            }

            override fun edgeState(irq: Int) = EdgeInterruptState(
                edge = irq in edge,
                high = irq in highLines,
                pending = irq in pending,
            )

            override fun lineEnabled(irq: Int) = irq in enabled
        }

        val cfg = mapOf(
            "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank",
            LcdcIrq118.SETTING to 315_113_473L,
            LcdcIrq117.SETTING to 446_113_473L,
            LcdcIrq118.SYNC to 427_113_473L,
            "continuousScanout" to true,
        )
        val vglib = VglibInitFactory().create(ctx, cfg) as StatefulComponent
        vglib.start()

        // Verify interruptRoutes() advertises 112..113 alongside 117 & 118
        val routes = vglib.interruptRoutes()
        assertTrue(
            routes.any { it.sinkPort == LcdcIrq118.SINK && 112 in it.lines && 113 in it.lines },
            "Expected LCDC0 IRQ 112..113 route in $routes",
        )

        val lcdc0 = ctx.entries.getValue(LcdcFunctional.port(0)) as TickAwareMmioDevice

        // Before firmware enables 112/113, neither line should pulse.
        ctx.tick = LcdcIrq118.PERIOD
        lcdc0.beforeInstruction(ctx.tick)
        assertTrue(pulses.isEmpty(), "Disabled LCDC0 lines must not pulse, got $pulses")

        // Enable IRQ 112 (edge, Core 0) and IRQ 113 (edge, Core 1).
        enabled += listOf(112, 113)
        edge += listOf(112, 113)
        ctx.tick = LcdcIrq118.PERIOD * 2
        lcdc0.beforeInstruction(ctx.tick)
        assertEquals(listOf(112, 113), pulses)

        // While pending (not yet ACKed by guest), next tick must not double-pulse.
        ctx.tick = LcdcIrq118.PERIOD * 3
        lcdc0.beforeInstruction(ctx.tick)
        assertEquals(listOf(112, 113), pulses)

        // After guest ACKs IRQ 113 only, only 113 fires on the next frame.
        pending -= 113
        ctx.tick = LcdcIrq118.PERIOD * 4
        lcdc0.beforeInstruction(ctx.tick)
        assertEquals(listOf(112, 113, 113), pulses)

        @Suppress("UNCHECKED_CAST")
        val st = vglib.state()["lcdc0Irqs"] as Map<String, Any?>
        assertEquals(1L, st["firedCount112"])
        assertEquals(2L, st["firedCount113"])

        // Snapshot save & restore succeeds cleanly
        val snap = vglib.saveSnapshot()
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val restored = VglibInitFactory().create(ctx, cfg) as StatefulComponent
        restored.start()
        restored.restoreSnapshot(snap)
        vglib.close()
        restored.close()
    }
}
