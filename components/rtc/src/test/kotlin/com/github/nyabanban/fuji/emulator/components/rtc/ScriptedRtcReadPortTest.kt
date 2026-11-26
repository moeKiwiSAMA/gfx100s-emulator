package com.github.nyabanban.fuji.emulator.components.rtc

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class ScriptedRtcReadPortTest {
    private fun bank() = RtcBank(10, 100, 3, true, 5)
    private fun port(bank: RtcBank = bank(), low: IntArray = intArrayOf(0x1234, 0xabcd), high: IntArray = intArrayOf(0x5678)) =
        ScriptedRtcReadPort(bank, mapOf(29 to low, 30 to high))
    private fun connection(port: ScriptedRtcReadPort) = port.connect("test") as PreparedPowerRegisterConnection
    private fun next(port: ScriptedRtcReadPort, selector: Int) = (((port.state()["selectors"] as Map<*, *>)[selector.toString()] as Map<*, *>)["nextIndex"] as Int)

    @Test fun frozenInputHasExplicitCommitCancelAndExhaustionWithoutRepeatLast() {
        val low = intArrayOf(0x1234, 0xabcd); val p = port(low = low); low[0] = 0
        connection(p).use { c ->
            val first = c.prepareRead(29, 1); assertEquals(0x1234, first.value)
            val prepared = p.state(); repeat(5) { first.checkCommit(3); assertEquals(prepared, p.state()) }
            assertEquals(0, next(p, 29)); assertFails { c.read(29, 1) }; assertFails { c.prepareRead(30, 1) }
            first.cancel(); first.cancel(); assertEquals(0, next(p, 29)); assertEquals(1L, p.state()["cancelCount"])
            assertFails { first.commit(3) }
            val retry = c.prepareRead(29, 4); assertEquals(0x1234, retry.value); retry.commit(7)
            val after = p.state(); assertFails { retry.commit(7) }; retry.cancel(); assertEquals(after, p.state())
            assertEquals(1, next(p, 29)); val second = c.prepareRead(29, 8); assertEquals(0xabcd, second.value); second.commit(9)
            val exhausted = p.state(); assertFails { c.prepareRead(29, 10) }; assertEquals(exhausted, p.state())
            assertEquals(0, next(p, 30)); c.prepareRead(30, 10).also { assertEquals(0x5678, it.value); it.commit(11) }
            assertEquals(3, p.state()["commitCount"])
            val entries = p.state()["commits"] as List<*>
            assertEquals(mapOf("sequence" to 1, "selector" to 29, "inputIndex" to 0, "value" to 0x1234, "prepareTick" to 4L, "commitTick" to 7L, "confidence" to "HYPOTHESIS"), entries[0])
        }
    }
    @Test fun scriptsNeverInitializeOrReadBackStagedWritesAndFreshnessRemainsIndependent() {
        val b = bank(); val p = port(b)
        connection(p).use { c ->
            assertNull(b.state(0)["stageLow"]); assertNull(b.state(0)["stageHigh"])
            val r = c.prepareRead(29, 1); val before = b.state(1)
            assertFails { c.write(29, 999, 1) }; assertFails { c.checkWrite(30, 999, 1) }; assertEquals(before, b.state(1))
            r.cancel(); c.write(29, 999, 2); c.write(30, 888, 2)
            c.prepareRead(29, 3).also { assertEquals(0x1234, it.value); it.commit(4) }
            assertEquals(999, b.state(4)["stageLow"]); assertEquals(888, b.state(4)["stageHigh"])
            assertEquals(3, b.state(4)["freshHalves"]); assertEquals(0L, b.state(4)["loadCount"])
            c.write(39, 0x203, 5); assertEquals(0, b.state(5)["freshHalves"])
            assertEquals((888L shl 16) or 999L, b.state(10)["seconds"])
        }
    }
    @Test fun counterSampleIsFrozenAtHeaderAndDoesNotConsumeScriptedInputs() {
        val b = bank(); val p = port(b)
        connection(p).use { c ->
            val r = c.prepareRead(35, 10); assertEquals(101, r.value)
            assertEquals(102, c.read(35, 20)); assertEquals(101, r.value); r.commit(20)
            assertEquals(0, p.state()["commitCount"]); assertEquals(1L, p.state()["completedReads"])
            assertEquals(0, next(p, 29)); assertEquals(0, next(p, 30))
            val before = p.state(); assertFails { c.prepareRead(29, 19) }; assertEquals(before, p.state())
        }
    }
    @Test fun backwardsCommitStaleLeaseAndClosingNeverConsumeInput() {
        val p = port(); val c = connection(p); val r = c.prepareRead(29, 5); val before = p.state()
        assertFails { r.checkCommit(4) }; assertFails { r.commit(4) }; assertEquals(before, p.state())
        c.close(); assertEquals(0, next(p, 29)); assertFails { r.commit(5) }; r.cancel()
        val secondConnection = connection(p); val pending = secondConnection.prepareRead(29, 6); p.close(); p.close()
        assertFails { pending.commit(6) }; pending.cancel(); assertEquals(0, next(p, 29)); assertEquals(2L, p.state()["cancelCount"])
        assertFails { p.connect("new") }; secondConnection.close()
    }
    @Test fun invalidSelectorsAndInputsRefuseBeforeReservation() {
        for (bad in listOf(emptyMap<Int, IntArray>(), mapOf(29 to intArrayOf(1)), mapOf(29 to intArrayOf(), 30 to intArrayOf(1)),
            mapOf(29 to IntArray(65), 30 to intArrayOf(1)), mapOf(29 to intArrayOf(-1), 30 to intArrayOf(1)), mapOf(29 to intArrayOf(1), 30 to intArrayOf(65536))))
            assertFails { ScriptedRtcReadPort(bank(), bad) }
        val p = port(); connection(p).use { c ->
            val before = p.state(); for (selector in listOf(-1, 0, 31, 34, 37, 40, 128)) assertFails { c.prepareRead(selector, 0) }
            assertFails { c.prepareRead(29, -1) }; assertEquals(before, p.state())
        }
    }
    @Test fun maximumFiniteJournalIsCompleteAndBoundedByTheInputCapacity() {
        val p = port(low = IntArray(64) { it }, high = IntArray(64) { 65535 - it })
        connection(p).use { c ->
            var tick = 0L
            repeat(64) { i -> for (selector in 29..30) { c.prepareRead(selector, tick++).commit(tick++); assertEquals(i + 1, next(p, selector)) } }
            val state = p.state(); assertEquals(128, state["commitCount"]); assertEquals(128, state["commitCapacity"])
            assertEquals(false, state["commitsTruncated"]); assertEquals(128, (state["commits"] as List<*>).size)
            assertFails { c.prepareRead(29, tick) }; assertFails { c.prepareRead(30, tick) }; assertEquals(state, p.state())
        }
    }
    @Test fun logicalContractsArePureDefensiveAndKeepV1Unprepared() {
        val b = bank(); val before = b.state(0); val legacy = b.registerContracts()
        assertEquals(before, b.state(0)); assertEquals(listOf(29, 30, 35, 36, 39), legacy.map { it.selector })
        assertEquals(listOf(false, false, true, true, true), legacy.map { it.readable }); assertTrue(legacy.all { it.wordBits == 16 })
        b.connect("legacy").use { assertFalse(it is PreparedPowerRegisterConnection) }
        val p = port(); val state = p.state(); val contracts = p.registerContracts()
        assertEquals(state, p.state()); assertTrue(contracts.take(2).all { it.readable && it.writable })
        assertEquals(listOf("reg.rtc.stage-low", "reg.rtc.stage-high", "reg.rtc.counter-low", "reg.rtc.counter-high", "reg.rtc.control"), contracts.map { it.registerId })
    }
}
