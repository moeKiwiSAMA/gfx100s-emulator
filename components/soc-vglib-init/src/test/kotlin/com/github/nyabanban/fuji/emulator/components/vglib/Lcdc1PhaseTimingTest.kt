package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.EdgeInterruptState
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.api.TickAwareMmioDevice
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class Lcdc1PhaseTimingTest {
    @Test
    fun rearPhaseUsesItsOwnConfiguredPeriodAndResumesFromOlderSnapshots() {
        val context = LcdcFunctionalTest.Context()
        val pulses = mutableListOf<Long>()
        context.entries[LcdcIrq117.SINK] = object : EnabledEdgeInterruptSink {
            override fun lineEnabled(irq: Int) = irq == LcdcIrq117.LINE
            override fun edgeState(irq: Int) = EdgeInterruptState(edge = true, high = false, pending = false)
            override fun setLine(irq: Int, high: Boolean) {
                if (high && irq == LcdcIrq117.LINE) pulses += context.tick
            }
        }
        val base = mapOf(
            "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank",
            LcdcIrq117.SETTING to 446_113_473L,
            "continuousScanout" to true,
        )
        fun checkPeriod(period: Long) {
            val rear = context.entries.getValue(LcdcFunctional.port(1)) as TickAwareMmioDevice
            val first = context.tick + period
            for (tick in listOf(first - 1, first, first + period - 1, first + period)) {
                context.tick = tick
                rear.beforeInstruction(tick)
            }
            assertEquals(listOf(first, first + period), pulses)
            pulses.clear()
        }
        val factory = VglibInitFactory()
        val legacy = factory.create(context, base) as StatefulComponent
        legacy.start()
        checkPeriod(LcdcIrq118.PERIOD)
        val snapshot = legacy.saveSnapshot()
        legacy.close()
        for (period in listOf(LcdcIrq118.NTSC_59_94_HZ_PERIOD, LcdcIrq118.NTSC_29_97_HZ_PERIOD)) {
            context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
            context.tick = 2 * LcdcIrq118.PERIOD
            val panel = factory.create(context, base + (LcdcIrq117.PERIOD_SETTING to period)) as StatefulComponent
            panel.start()
            panel.restoreSnapshot(snapshot)
            checkPeriod(period)
            panel.close()
        }
    }
}
