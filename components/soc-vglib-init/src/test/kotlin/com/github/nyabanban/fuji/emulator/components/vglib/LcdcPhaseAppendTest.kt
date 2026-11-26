package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LcdcPhaseAppendTest {
    private class Branch(ticks: String = "", first: Long = 10L) {
        val context = LcdcFunctionalTest.Context()
        var line = EdgeInterruptState(true, false, false)
        var enabled = true
        val pulses = mutableListOf<Boolean>()
        init {
            context.entries["soc-gic.lines"] = object : EnabledEdgeInterruptSink {
                override fun lineEnabled(irq: Int) = enabled
                override fun edgeState(irq: Int) = line
                override fun setLine(irq: Int, high: Boolean) { assertEquals(117, irq); pulses.add(high) }
            }
        }
        val component = VglibInitFactory().create(context, mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank", "irq117Tick" to first, "irq117FollowupTicks" to ticks)) as StatefulComponent
        val bank = context.entries[LcdcFunctional.port(1)] as ScheduledMmioDevice
        fun advance(tick: Long) { context.tick = tick; bank.beforeInstruction(tick) }
    }

    @Test fun appendRetainsSpentAndPendingPrefixAcrossRestores() {
        val old = Branch()
        old.advance(10)
        val legacy = old.component.saveSnapshot()
        assertEquals(mapOf("deadline" to 10L, "firedAt" to 10L), legacy["irq117"])
        old.advance(20)
        assertEquals(2, old.pulses.size)
        val branch = Branch("20,30")
        branch.context.tick = 19
        branch.component.restoreSnapshot(legacy)
        branch.enabled = false
        assertFails { branch.advance(20) }
        branch.enabled = true
        branch.advance(21)
        val saved = branch.component.saveSnapshot()
        assertEquals(mapOf("deadline" to 10L, "firedAt" to 10L, "followupFiredAt" to listOf(21L)), saved["irq117"])
        val extended = Branch("20,30,40")
        extended.context.tick = 22
        extended.component.restoreSnapshot(saved)
        assertEquals(30L, extended.bank.nextDeadline())
        val armed = extended.component.saveSnapshot()
        val state = armed["irq117"] as Map<*, *>
        for (history in listOf(emptyList<Long>(), listOf(19L), listOf(23L), listOf(21L, 21L))) {
            assertFails { extended.component.restoreSnapshot(armed + ("irq117" to state + ("followupFiredAt" to history))) }
            assertEquals(armed, extended.component.saveSnapshot())
        }
        assertFails { extended.advance(40) }
        extended.context.tick = 30
        extended.line = EdgeInterruptState(true, true, false)
        assertFails { extended.advance(30) }
        extended.line = EdgeInterruptState(true, false, false)
        extended.advance(30)
        extended.advance(40)
        extended.component.restoreSnapshot(extended.component.saveSnapshot())
        extended.advance(41)
        assertNull(extended.bank.nextDeadline())
        assertEquals(listOf(true, false, true, false), extended.pulses)
        for (changed in listOf("", "20", "20,31", "25,30")) {
            val other = Branch(changed)
            other.context.tick = 22
            assertFails { other.component.restoreSnapshot(saved) }
        }
        val retroactive = Branch("19")
        retroactive.context.tick = 19
        assertFails { retroactive.component.restoreSnapshot(legacy) }
    }

    @Test fun postponesOnlyAnUnspentSinglePulseAndChecksTheLineAtItsNewDeadline() {
        val original = Branch()
        val saved = original.component.saveSnapshot()
        val later = Branch(first = 20)
        later.context.tick = 9
        later.component.restoreSnapshot(saved)
        later.advance(10)
        assertTrue(later.pulses.isEmpty())
        val pending = later.component.saveSnapshot()
        later.component.restoreSnapshot(pending)
        later.enabled = false
        assertFails { later.advance(20) }
        assertEquals(pending, later.component.saveSnapshot())
        later.enabled = true
        later.advance(20)
        assertEquals(listOf(true, false), later.pulses)
        original.advance(10)
        assertFails { Branch(first = 20).component.restoreSnapshot(original.component.saveSnapshot()) }
        assertFails { Branch(first = 8).component.restoreSnapshot(saved) }
        assertFails { Branch("30", 20).component.restoreSnapshot(saved) }
        assertFails { later.component.restoreSnapshot(saved) }
    }

    @Test fun checksCapacityCanonicalTicksAndFirstSelection() {
        for (text in listOf("9", "10", "12,12", "13,12", "011", "11,", " 11", "+11", "4294967296",
            (11..43).joinToString(","))) assertFails { Branch(text) }
        assertFails { Branch("20", 0) }
        val bounded = Branch((11..42).joinToString(","))
        for (tick in 10L..42L) bounded.advance(tick)
        assertNull(bounded.bank.nextDeadline())
        assertEquals(66, bounded.pulses.size)
    }
}
