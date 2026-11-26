package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LcdcFrameSyncFollowupTest {
    private class Branch(
        syncStart: Long = 100L,
        resumeTick: Long = 0L,
        followups: String = "",
        irq118Tick: Long = 42L,
    ) {
        val context = LcdcFunctionalTest.Context()
        var line = EdgeInterruptState(true, false, false)
        val pulses = mutableListOf<Boolean>()
        init {
            context.entries["soc-gic.lines"] = object : EdgeInterruptSink {
                override fun edgeState(irq: Int) = line
                override fun setLine(irq: Int, high: Boolean) {
                    assertEquals(118, irq)
                    pulses.add(high)
                    if (high) line = line.copy(pending = true)
                }
            }
        }
        val component = VglibInitFactory().create(
            context,
            buildMap<String, Any> {
                put("profile", VglibInitFactory.FUNCTIONAL_PROFILE)
                put("inputFile", "bank")
                if (irq118Tick > 0) put("irq118Tick", irq118Tick)
                if (syncStart > 0) put("frameSyncStartTick", syncStart)
                if (resumeTick > 0) put("frameSyncResumeTick", resumeTick)
                if (followups.isNotEmpty()) put("frameSyncFollowupTicks", followups)
            },
        ) as StatefulComponent
        val bank = context.entries[LcdcFunctional.port(1)] as ScheduledMmioDevice
        fun advance(tick: Long) {
            context.tick = tick
            bank.beforeInstruction(tick)
        }
        fun exhaustPeriodic(syncStart: Long, resumeTick: Long = 0L) {
            advance(42L)
            repeat(LcdcIrq118.LIMIT) { index -> advance(syncStart + index * LcdcIrq118.PERIOD) }
            if (resumeTick > 0) {
                repeat(LcdcIrq118.LIMIT) { index -> advance(resumeTick + index * LcdcIrq118.PERIOD) }
            }
        }
    }

    @Test fun restores32EdgeSnapshotWithFollowupsAndExtendsMonotonically() {
        val old = Branch(syncStart = 100L)
        old.exhaustPeriodic(100L)
        assertNull(old.bank.nextDeadline())
        old.context.tick = 65_000_000L
        val legacy32 = old.component.saveSnapshot()
        val legacySync = legacy32["frameSync"] as Map<*, *>
        assertEquals(setOf("firstTick", "periodTicks", "capacity", "confidence", "edgeTicks"), legacySync.keys)
        assertEquals(32, legacySync["capacity"])
        assertFalse(LcdcIrq118.FOLLOWUPS in (legacy32["settings"] as Map<*, *>))

        val extended = Branch(syncStart = 100L, followups = "70000000,70000500,80000000")
        extended.context.tick = 65_000_000L
        extended.component.restoreSnapshot(legacy32)
        assertEquals(70_000_000L, extended.bank.nextDeadline())
        val armed = extended.component.saveSnapshot()
        val armedSync = armed["frameSync"] as Map<*, *>
        assertEquals(35, armedSync["capacity"])
        assertEquals(32, (armedSync["edgeTicks"] as List<*>).size)
        assertEquals(emptyList<Long>(), armedSync["followupFiredAt"])

        // Rejects missed interval when jumping past the next follow-up.
        assertFailsWith<IllegalArgumentException> { extended.advance(70_000_500L) }
        // Rejects non-edge or high line, but allows already-pending line (GIC coalesces).
        extended.line = EdgeInterruptState(false, false, false)
        assertFailsWith<IllegalArgumentException> { extended.advance(70_000_000L) }
        extended.line = EdgeInterruptState(true, true, false)
        assertFailsWith<IllegalArgumentException> { extended.advance(70_000_000L) }
        extended.line = EdgeInterruptState(true, false, true)
        extended.advance(70_000_100L)
        assertEquals(70_000_500L, extended.bank.nextDeadline())

        val mid = extended.component.saveSnapshot()
        val midSync = mid["frameSync"] as Map<*, *>
        assertEquals(32, (midSync["edgeTicks"] as List<*>).size)
        assertEquals(listOf(70_000_100L), midSync["followupFiredAt"])

        // Monotonic prefix extension across restore.
        val longer = Branch(syncStart = 100L, followups = "70000000,70000500,80000000,85000000")
        longer.context.tick = 70_000_200L
        longer.component.restoreSnapshot(mid)
        assertEquals(70_000_500L, longer.bank.nextDeadline())

        // Follow-up interval is NOT restricted to due + PERIOD (arbitrary spacing up to next follow-up).
        longer.advance(75_000_000L)
        assertEquals(80_000_000L, longer.bank.nextDeadline())
        longer.advance(80_000_000L)
        assertEquals(85_000_000L, longer.bank.nextDeadline())
        longer.advance(90_000_000L)
        assertNull(longer.bank.nextDeadline())
        assertEquals(listOf(true, false, true, false, true, false), longer.pulses)

        val spent = longer.component.saveSnapshot()
        assertEquals(listOf(70_000_100L, 75_000_000L, 80_000_000L, 90_000_000L),
            (spent["frameSync"] as Map<*, *>)["followupFiredAt"])
        longer.component.restoreSnapshot(spent)
        longer.advance(95_000_000L)
        assertEquals(6, longer.pulses.size)

        // Rejects non-prefix follow-up schedules.
        for (changed in listOf("", "70000000", "70000000,70000501,80000000", "70000001,70000500,80000000")) {
            val other = Branch(syncStart = 100L, followups = changed)
            other.context.tick = 70_000_200L
            assertFailsWith<IllegalArgumentException> { other.component.restoreSnapshot(mid) }
        }
        // Rejects inserting frameSyncResumeTick after frameSyncFollowupTicks was already selected.
        val insertedResume = Branch(syncStart = 100L, resumeTick = 64_000_100L, followups = "130000000")
        insertedResume.context.tick = 70_000_200L
        assertFailsWith<IllegalArgumentException> { insertedResume.component.restoreSnapshot(mid) }
    }

    @Test fun restores64EdgeSnapshotWithFollowupsAndRejectsCorruptOrPastFollowups() {
        val resume = 773_664_282L
        val old = Branch(syncStart = 100L, resumeTick = resume)
        old.exhaustPeriodic(100L, resume)
        assertNull(old.bank.nextDeadline())
        old.context.tick = 868_174_783L
        val snap64 = old.component.saveSnapshot()
        val sync64 = snap64["frameSync"] as Map<*, *>
        assertEquals(64, sync64["capacity"])
        assertEquals(64, (sync64["edgeTicks"] as List<*>).size)
        assertFalse("followupFiredAt" in sync64)

        // Past or equal follow-up ticks must be rejected on restore.
        for (past in listOf("868174783", "850000000,870000000")) {
            val bad = Branch(syncStart = 100L, resumeTick = resume, followups = past)
            bad.context.tick = 868_174_783L
            assertFailsWith<IllegalArgumentException> { bad.component.restoreSnapshot(snap64) }
        }

        val live = Branch(syncStart = 100L, resumeTick = resume, followups = "870000000,872000000")
        live.context.tick = 868_174_783L
        live.component.restoreSnapshot(snap64)
        assertEquals(870_000_000L, live.bank.nextDeadline())

        val armed = live.component.saveSnapshot()
        val settings = armed["settings"] as Map<*, *>
        val schedule = armed["frameSync"] as Map<*, *>
        assertEquals(66, schedule["capacity"])
        assertEquals(64, (schedule["edgeTicks"] as List<*>).size)
        assertEquals(emptyList<Long>(), schedule["followupFiredAt"])

        for (corrupt in listOf(
            armed + ("settings" to settings - LcdcIrq118.FOLLOWUPS),
            armed + ("frameSync" to schedule - "followupFiredAt"),
            armed + ("frameSync" to schedule + ("capacity" to 64)),
            armed + ("frameSync" to schedule + ("followupFiredAt" to listOf(870_000_000L))),
            armed + ("frameSync" to schedule + ("followupFiredAt" to listOf(868_000_000L))),
            armed + ("frameSync" to schedule + ("edgeTicks" to (schedule["edgeTicks"] as List<*>).dropLast(1)) +
                ("followupFiredAt" to listOf(870_000_000L))),
        )) {
            assertFailsWith<IllegalArgumentException> { live.component.restoreSnapshot(corrupt) }
            assertEquals(armed, live.component.saveSnapshot())
        }

        live.advance(870_000_000L)
        assertEquals(872_000_000L, live.bank.nextDeadline())
        live.advance(876_500_000L)
        assertNull(live.bank.nextDeadline())
        assertEquals(listOf(true, false, true, false), live.pulses)
        val spent = live.component.saveSnapshot()
        assertEquals(listOf(870_000_000L, 876_500_000L), (spent["frameSync"] as Map<*, *>)["followupFiredAt"])
        live.component.restoreSnapshot(spent)
        live.advance(880_000_000L)
        assertEquals(4, live.pulses.size)
    }

    @Test fun enforcesCanonicalTicksPeriodicFloorAndFollowupCapacity() {
        for (bad in listOf(
            "64000099", "64000100,64000100", "64000101,64000100",
            "064000100", "64000100,", " 64000100", "+64000100", "4294967296",
            (0..LcdcIrq118.FOLLOWUP_LIMIT).joinToString(",") { (64_000_100L + it).toString() },
        )) {
            assertFailsWith<IllegalArgumentException> { Branch(syncStart = 100L, followups = bad) }
        }
        // Requires syncStart > 0.
        assertFailsWith<IllegalArgumentException> { Branch(syncStart = 0L, followups = "64000100") }
        // With resumeTick > 0, floor is resumeTick + 32 * PERIOD.
        val resume = 773_664_282L
        val resumeFloor = resume + LcdcIrq118.LIMIT * LcdcIrq118.PERIOD
        assertFailsWith<IllegalArgumentException> {
            Branch(syncStart = 100L, resumeTick = resume, followups = (resumeFloor - 1).toString())
        }
        // Accepts full FOLLOWUP_LIMIT follow-ups right at the floor and fires all 64 periodic + follow-up edges.
        val allFollowups = (0 until LcdcIrq118.FOLLOWUP_LIMIT).map { resumeFloor + it * 1000L }
        val bounded = Branch(syncStart = 100L, resumeTick = resume, followups = allFollowups.joinToString(","))
        bounded.exhaustPeriodic(100L, resume)
        for (tick in allFollowups) {
            assertEquals(tick, bounded.bank.nextDeadline())
            bounded.advance(tick)
        }
        assertNull(bounded.bank.nextDeadline())
        assertEquals((1 + 64 + LcdcIrq118.FOLLOWUP_LIMIT) * 2, bounded.pulses.size)
    }
}
