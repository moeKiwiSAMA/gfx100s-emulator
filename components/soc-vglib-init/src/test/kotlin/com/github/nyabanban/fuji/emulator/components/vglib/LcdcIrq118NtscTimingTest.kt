package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class LcdcIrq118NtscTimingTest {
    private class Harness(
        irq118Tick: Long = 100L,
        syncStart: Long = 200L,
        periodTicks: Long? = null,
        followups: String = "",
        continuous: Boolean = false,
    ) {
        val context = LcdcFunctionalTest.Context()
        var line = EdgeInterruptState(edge = true, high = false, pending = false)
        val firedTicks = mutableListOf<Long>()

        init {
            context.entries["soc-gic.lines"] = object : EdgeInterruptSink {
                override fun edgeState(irq: Int) = line
                override fun setLine(irq: Int, high: Boolean) {
                    assertEquals(LcdcIrq118.LINE, irq)
                    if (high) {
                        firedTicks.add(context.tick)
                        line = line.copy(pending = true)
                    }
                }
            }
        }

        val component = VglibInitFactory().create(
            context,
            buildMap<String, Any> {
                put("profile", VglibInitFactory.FUNCTIONAL_PROFILE)
                put("inputFile", "bank")
                put(LcdcIrq118.SETTING, irq118Tick)
                if (syncStart > 0L) put(LcdcIrq118.SYNC, syncStart)
                if (periodTicks != null) put(LcdcIrq118.PERIOD_SETTING, periodTicks)
                if (followups.isNotEmpty()) put(LcdcIrq118.FOLLOWUPS, followups)
                if (continuous) put("continuousScanout", true)
            },
        ) as StatefulComponent
        val bank = context.entries[LcdcFunctional.port(1)] as ScheduledMmioDevice

        fun advance(tick: Long) {
            context.tick = tick
            bank.beforeInstruction(tick)
        }
    }

    @Test
    fun ntsc5994HzConstantIsExactZeroRemainderAt54Mhz() {
        assertEquals(2_000_000L, LcdcIrq118.PERIOD)
        assertEquals(900_900L, LcdcIrq118.NTSC_59_94_HZ_PERIOD)
        assertEquals(1_801_800L, LcdcIrq118.NTSC_29_97_HZ_PERIOD)
        // Exact 54,000,000 Hz / (60,000 / 1,001 Hz) = 900,900 ticks with zero remainder
        assertEquals(54_000_000L * 1_001L, LcdcIrq118.NTSC_59_94_HZ_PERIOD * 60_000L)
        assertEquals(LcdcIrq118.NTSC_59_94_HZ_PERIOD * 2L, LcdcIrq118.NTSC_29_97_HZ_PERIOD)
    }

    @Test
    fun defaultPeriodPreservesLegacy2MillionTickBehaviorAndOmitsSettingFromStateAndSnapshot() {
        val defaultHarness = Harness(irq118Tick = 100L, syncStart = 200L)
        assertFalse(LcdcIrq118.PERIOD_SETTING in defaultHarness.component.state())
        val defaultSync = assertIs<Map<*, *>>(defaultHarness.component.state()["frameSync"])
        assertEquals(LcdcIrq118.PERIOD, defaultSync["periodTicks"])

        val explicitZero = Harness(irq118Tick = 100L, syncStart = 200L, periodTicks = 0L)
        assertFalse(LcdcIrq118.PERIOD_SETTING in explicitZero.component.state())

        defaultHarness.advance(100L)
        assertEquals(200L, defaultHarness.bank.nextDeadline())
        defaultHarness.advance(200L)
        assertEquals(200L + LcdcIrq118.PERIOD, defaultHarness.bank.nextDeadline())

        val snap = defaultHarness.component.saveSnapshot()
        val settings = assertIs<Map<*, *>>(snap["settings"])
        assertFalse(LcdcIrq118.PERIOD_SETTING in settings)

        explicitZero.context.tick = 200L
        explicitZero.component.restoreSnapshot(snap)
        assertEquals(defaultHarness.component.state(), explicitZero.component.state())
    }

    @Test
    fun optInNtsc5994HzCadenceDrivesFiniteFollowupsContinuousAndSnapshotRoundTrip() {
        val p = LcdcIrq118.NTSC_59_94_HZ_PERIOD
        val syncStart = 200L
        val followupFloor = syncStart + LcdcIrq118.LIMIT * p

        // Follow-up below 32 * 900,900 floor must fail closed
        assertFailsWith<IllegalArgumentException> {
            Harness(irq118Tick = 100L, syncStart = syncStart, periodTicks = p, followups = (followupFloor - 1L).toString())
        }

        // Finite schedule at exact 900,900-tick spacing
        val ntsc = Harness(
            irq118Tick = 100L,
            syncStart = syncStart,
            periodTicks = p,
            followups = "$followupFloor,${followupFloor + p}",
        )
        assertEquals(p, ntsc.component.state()[LcdcIrq118.PERIOD_SETTING])
        assertEquals(p, (ntsc.component.state()["frameSync"] as Map<*, *>)["periodTicks"])

        ntsc.advance(100L)
        repeat(LcdcIrq118.LIMIT) { i ->
            val expected = syncStart + i * p
            assertEquals(expected, ntsc.bank.nextDeadline())
            ntsc.advance(expected)
        }
        assertEquals(followupFloor, ntsc.bank.nextDeadline())

        val midSnap = ntsc.component.saveSnapshot()
        assertEquals(p, (midSnap["settings"] as Map<*, *>)[LcdcIrq118.PERIOD_SETTING])
        assertEquals(p, (midSnap["frameSync"] as Map<*, *>)["periodTicks"])

        // Restoring into mismatched default period must fail closed
        val legacy = Harness(irq118Tick = 100L, syncStart = syncStart)
        legacy.context.tick = syncStart + (LcdcIrq118.LIMIT - 1) * p
        assertFailsWith<IllegalArgumentException> { legacy.component.restoreSnapshot(midSnap) }

        // Restoring into matching NTSC period continues identically
        val restored = Harness(
            irq118Tick = 100L,
            syncStart = syncStart,
            periodTicks = p,
            followups = "$followupFloor,${followupFloor + p}",
        )
        restored.context.tick = syncStart + (LcdcIrq118.LIMIT - 1) * p
        restored.component.restoreSnapshot(midSnap)
        assertEquals(ntsc.component.state(), restored.component.state())
        restored.advance(followupFloor)
        assertEquals(followupFloor + p, restored.bank.nextDeadline())
        restored.advance(followupFloor + p)
        assertNull(restored.bank.nextDeadline())

        // Continuous mode advances by exact 900,900-tick intervals and stays phase-locked under observation jitter
        val cont = Harness(irq118Tick = 100L, syncStart = 200L, periodTicks = p, continuous = true)
        assertEquals(100L, cont.bank.nextDeadline())
        cont.advance(100L)
        assertEquals(100L + p, cont.bank.nextDeadline())
        cont.advance(100L + p)
        assertEquals(100L + 2L * p, cont.bank.nextDeadline())
        assertEquals(listOf(100L, 100L + p), cont.firedTicks)

        // Jittered observation (+350..+4,200 ticks per frame) must NOT accumulate phase drift over 120 frames
        for (frame in 2L..121L) {
            val scheduled = 100L + frame * p
            assertEquals(scheduled, cont.bank.nextDeadline(), "Frame $frame deadline drifted")
            val jitter = 350L + (frame * 7919L) % 3_850L
            cont.advance(scheduled + jitter)
        }
        assertEquals(100L + 122L * p, cont.bank.nextDeadline(), "120 jittered NTSC frames must have 0 ticks cumulative drift")
    }

    @Test
    fun rejectsPeriodSettingWithoutIrq118Tick() {
        val context = LcdcFunctionalTest.Context()
        assertFailsWith<IllegalArgumentException> {
            VglibInitFactory().create(
                context,
                mapOf(
                    "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                    "inputFile" to "bank",
                    LcdcIrq118.PERIOD_SETTING to LcdcIrq118.NTSC_59_94_HZ_PERIOD,
                ),
            )
        }
    }
}
