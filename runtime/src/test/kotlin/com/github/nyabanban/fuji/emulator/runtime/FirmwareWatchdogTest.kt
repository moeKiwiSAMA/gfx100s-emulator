package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareWatchdogTest {
    @Test fun announcesEachMilestoneExactlyOnceInOrder() {
        val telemetry = FirmwareTelemetry()
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        var now = 1_000_000_000L
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            clockNanos = { now },
            infoSink = infos::add,
            warnSink = warns::add,
        )

        telemetry.markSessionStarted(now)
        telemetry.attempts = 0L
        telemetry.virtualTick = 0L
        telemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
        watchdog.pollOnce(now)

        now += TimeUnit.MILLISECONDS.toNanos(250)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 2_000_000L
        telemetry.virtualTick = 2_000_000L
        telemetry.markStage(FirmwareStage.STAGE_2_KERNEL_RTOS)
        telemetry.markStage(FirmwareStage.STAGE_3_STORAGE_IO)
        watchdog.pollOnce(now)

        now += TimeUnit.MILLISECONDS.toNanos(750)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 10_000_000L
        telemetry.virtualTick = 10_000_000L
        telemetry.markStage(FirmwareStage.STAGE_4_DISPLAY_INIT)
        telemetry.publishedFrames = 1L
        telemetry.markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
        watchdog.pollOnce(now)

        now += TimeUnit.MILLISECONDS.toNanos(500)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 15_000_000L
        telemetry.virtualTick = 15_000_000L
        telemetry.publishedFrames = 2L
        telemetry.markStage(FirmwareStage.STAGE_6_INTERACTIVE_UI)
        watchdog.pollOnce(now)

        // Repeated polls with no new stages must not duplicate announcements
        now += TimeUnit.SECONDS.toNanos(2)
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)

        assertEquals(6, infos.size)
        FirmwareStage.entries.forEachIndexed { idx, stage ->
            assertTrue(infos[idx].contains("Milestone ${stage.label} reached"), "Missing ${stage.label}: ${infos[idx]}")
        }
        assertTrue(infos[4].contains("frames=1"))
        assertTrue(infos[5].contains("frames=2"))
        assertTrue(warns.isEmpty(), "Unexpected warnings: $warns")
    }

    @Test fun detectsOwnerHeartbeatStallWithStackTraceAndRateLimitsReports() {
        val telemetry = FirmwareTelemetry()
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        var now = 10_000_000_000L
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            ownerThreadProvider = { Thread.currentThread() },
            ownerHeartbeatTimeoutNanos = TimeUnit.SECONDS.toNanos(8),
            stageStallTimeoutNanos = TimeUnit.SECONDS.toNanos(30),
            stallReportCooldownNanos = TimeUnit.SECONDS.toNanos(15),
            clockNanos = { now },
            infoSink = infos::add,
            warnSink = warns::add,
        )

        telemetry.markSessionStarted(now)
        telemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
        telemetry.markStage(FirmwareStage.STAGE_2_KERNEL_RTOS)
        telemetry.attempts = 4_000_000L
        telemetry.virtualTick = 4_000_000L
        telemetry.recordButtonEvent("down=true", now)
        watchdog.pollOnce(now)

        // 7s without heartbeat: below 8s threshold -> no stall report
        now += TimeUnit.SECONDS.toNanos(7)
        watchdog.pollOnce(now)
        assertTrue(warns.isEmpty())

        // 9s without heartbeat: triggers OWNER HEARTBEAT STALL with stack trace
        now += TimeUnit.SECONDS.toNanos(2)
        watchdog.pollOnce(now)
        assertEquals(1, warns.size)
        val report = warns.single()
        assertTrue(report.contains("OWNER HEARTBEAT STALL"), report)
        assertTrue(report.contains("currentStage=STAGE_2_KERNEL_RTOS"), report)
        assertTrue(report.contains("attempts=4000000"), report)
        assertTrue(report.contains("lastButton=down=true"), report)
        assertTrue(report.contains("Owner thread '"), report)
        assertTrue(report.contains("at "), report)

        // Within 15s cooldown -> rate-limited, no spam
        now += TimeUnit.SECONDS.toNanos(10)
        watchdog.pollOnce(now)
        assertEquals(1, warns.size)

        // After 15s cooldown -> second report allowed
        now += TimeUnit.SECONDS.toNanos(6)
        watchdog.pollOnce(now)
        assertEquals(2, warns.size)
    }

    @Test fun detectsBootStageStallWithoutOwnerStackAndSuppressesWhenPausedOrAfterFirstFrame() {
        val telemetry = FirmwareTelemetry()
        val warns = mutableListOf<String>()
        var now = 5_000_000_000L
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            ownerThreadProvider = { Thread.currentThread() },
            ownerHeartbeatTimeoutNanos = TimeUnit.SECONDS.toNanos(8),
            stageStallTimeoutNanos = TimeUnit.SECONDS.toNanos(20),
            stallReportCooldownNanos = TimeUnit.SECONDS.toNanos(15),
            clockNanos = { now },
            infoSink = {},
            warnSink = warns::add,
        )

        telemetry.markSessionStarted(now)
        telemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
        watchdog.pollOnce(now)

        // Owner heartbeat stays fresh, but paused -> must suppress stage stall
        telemetry.paused = true
        now += TimeUnit.SECONDS.toNanos(25)
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)
        assertTrue(warns.isEmpty())

        // Unpause and let 21s elapse with alive owner heartbeat and no new stage
        telemetry.paused = false
        watchdog.pollOnce(now)
        now += TimeUnit.SECONDS.toNanos(21)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 50_000_000L
        watchdog.pollOnce(now)
        assertEquals(1, warns.size)
        assertTrue(warns.single().contains("STAGE PROGRESSION STALL"))
        assertFalse(warns.single().contains("Owner thread '"))

        // Once STAGE_5_FIRST_FRAME is reached, waiting on user UI must not trigger boot stage stall
        telemetry.publishedFrames = 1L
        telemetry.markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
        watchdog.pollOnce(now)
        now += TimeUnit.SECONDS.toNanos(60)
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)
        assertEquals(1, warns.size)
    }

    @Test fun daemonThreadStartsPollsAndStopsCleanlyWithoutLeakingThreads() {
        val telemetry = FirmwareTelemetry()
        val latch = CountDownLatch(1)
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            pollIntervalMs = 20L,
            infoSink = { if (it.contains("STAGE_1_BOOT_INIT")) latch.countDown() },
            warnSink = {},
        )
        telemetry.markSessionStarted()
        watchdog.start()
        try {
            telemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Watchdog daemon did not observe STAGE_1_BOOT_INIT in time")
        } finally {
            watchdog.close()
        }
        val aliveWatchdogs = Thread.getAllStackTraces().keys.filter { it.name == "fuji-watchdog" && it.isAlive }
        assertTrue(aliveWatchdogs.isEmpty(), "Expected no leaked fuji-watchdog threads, found: $aliveWatchdogs")
    }

    @Test fun appendsRelaxedSummaryToStallReportOnlyWhenProviderReturnsContent() {
        val telemetry = FirmwareTelemetry()
        val warns = mutableListOf<String>()
        var now = 20_000_000_000L
        val fallback = RelaxedPeripheralFallback(
            context = object : RelaxedPeripheralFallback.Context {
                override fun pc(): Long = 0x00401234L
                override fun tick(): Long = 77_000L
                override fun stage(): String = FirmwareStage.STAGE_2_KERNEL_RTOS.label
            },
            warnSink = {},
            infoSink = {},
        )
        fallback.read(0xfff9abcdL, 4, "unmapped")
        fallback.read(0xfff9abcdL, 4, "unmapped")

        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            ownerThreadProvider = { Thread.currentThread() },
            ownerHeartbeatTimeoutNanos = TimeUnit.SECONDS.toNanos(8),
            stageStallTimeoutNanos = TimeUnit.SECONDS.toNanos(20),
            stallReportCooldownNanos = TimeUnit.SECONDS.toNanos(15),
            clockNanos = { now },
            infoSink = {},
            warnSink = warns::add,
            summaryProvider = { fallback.formatSummary() },
        )

        telemetry.markSessionStarted(now)
        telemetry.markStage(FirmwareStage.STAGE_2_KERNEL_RTOS)
        watchdog.pollOnce(now)

        now += TimeUnit.SECONDS.toNanos(9)
        watchdog.pollOnce(now)
        assertEquals(1, warns.size)
        assertTrue(warns.single().contains("OWNER HEARTBEAT STALL"), warns.single())
        assertTrue(warns.single().contains("[RELAXED/SUMMARY] mmioUnique=1"), warns.single())
        assertTrue(warns.single().contains("addr=0xfff9abcd"), warns.single())
        assertTrue(warns.single().contains("hits=2"), warns.single())
    }

    @Test fun emitsPeriodicHealthyHeartbeatAtDebugOnlyWithoutClutteringInfoOrWarn() {
        val telemetry = FirmwareTelemetry()
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        var now = 5_000_000_000L
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            heartbeatLogIntervalNanos = TimeUnit.SECONDS.toNanos(10),
            clockNanos = { now },
            infoSink = infos::add,
            warnSink = warns::add,
            debugSink = debugs::add,
        )

        telemetry.markSessionStarted(now)
        telemetry.markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
        telemetry.attempts = 1_000_000L
        telemetry.virtualTick = 50_000_000L
        telemetry.publishedFrames = 10L
        watchdog.pollOnce(now)
        assertEquals(1, infos.size)
        assertTrue(debugs.isEmpty(), "First poll initializes heartbeat baseline without spamming debug")

        // 5s later (below 10s interval) -> still no heartbeat debug line
        now += TimeUnit.SECONDS.toNanos(5)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 5_000_000L
        watchdog.pollOnce(now)
        assertTrue(debugs.isEmpty())

        // 11s after baseline -> emits exactly one [WATCHDOG/HEARTBEAT] at DEBUG, zero extra INFO/WARN
        now += TimeUnit.SECONDS.toNanos(6)
        telemetry.ownerHeartbeatNanos = now
        telemetry.attempts = 12_000_000L
        telemetry.publishedFrames = 25L
        watchdog.pollOnce(now)
        assertEquals(1, infos.size, "Healthy heartbeat must not emit at INFO: $infos")
        assertTrue(warns.isEmpty(), "Healthy heartbeat must not emit at WARN: $warns")
        assertEquals(1, debugs.size)
        assertTrue(debugs.single().contains("[WATCHDOG/HEARTBEAT] stage=STAGE_5_FIRST_FRAME"), debugs.single())
        assertTrue(debugs.single().contains("frames=25"), debugs.single())
    }

    @Test fun resetsStallWindowAndReannouncesMilestonesAfterSubMcuColdResetWake() {
        val telemetry = FirmwareTelemetry()
        val infos = mutableListOf<String>()
        val warns = mutableListOf<String>()
        var now = 1_000_000_000L
        val watchdog = FirmwareWatchdog(
            telemetry = telemetry,
            ownerHeartbeatTimeoutNanos = TimeUnit.SECONDS.toNanos(8),
            stageStallTimeoutNanos = TimeUnit.SECONDS.toNanos(30),
            stallReportCooldownNanos = TimeUnit.SECONDS.toNanos(15),
            clockNanos = { now },
            infoSink = infos::add,
            warnSink = warns::add,
        )

        // Boot #1 reaches STAGE_6_INTERACTIVE_UI cleanly.
        telemetry.markSessionStarted(now)
        for (stage in FirmwareStage.entries) {
            telemetry.markStage(stage)
        }
        telemetry.attempts = 20_000_000L
        telemetry.virtualTick = 500_000_000L
        telemetry.publishedFrames = 42L
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)
        assertEquals(6, infos.size)
        assertTrue(warns.isEmpty())

        // Camera idles / sleeps for 275.3s, then Sub-MCU cold-resets for Boot #2.
        // Note: telemetry.resetForColdReset() clears stageMask/attempts/tick while startedAtNanos
        // may still hold Boot #1's timestamp until markSessionStarted() runs.
        now += TimeUnit.MILLISECONDS.toNanos(275_300L)
        telemetry.resetForColdReset()
        telemetry.recordButtonEvent("power=true", now)
        telemetry.ownerHeartbeatNanos = now

        // Case A: Even if fuji-watchdog polls BEFORE explicit resetForColdReset() and before
        // startedAtNanos updates, auto-detection must prevent any false STAGE PROGRESSION STALL!
        now += TimeUnit.SECONDS.toNanos(1)
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)
        assertTrue(warns.isEmpty(), "Expected zero false stall warnings on cold-reset wake: $warns")

        // Case B: Explicit resetForColdReset() (as called by Main.kt performSubMcuColdResetOnOwner)
        // keeps the window clean and Boot #2 announces STAGE_1..STAGE_6 milestones fresh with
        // Boot #2 elapsed time (not Boot #1's 275.3s!).
        watchdog.resetForColdReset(now)
        telemetry.markSessionStarted(now)
        now += TimeUnit.SECONDS.toNanos(1)
        telemetry.ownerHeartbeatNanos = now
        watchdog.pollOnce(now)
        assertTrue(warns.isEmpty(), "Expected zero false stall warnings after resetForColdReset(): $warns")

        for (stage in FirmwareStage.entries) {
            now += TimeUnit.MILLISECONDS.toNanos(500)
            telemetry.ownerHeartbeatNanos = now
            telemetry.attempts += 1_000_000L
            telemetry.virtualTick += 10_000_000L
            if (stage == FirmwareStage.STAGE_5_FIRST_FRAME) telemetry.publishedFrames = 1L
            telemetry.markStage(stage)
            watchdog.pollOnce(now)
        }
        assertEquals(12, infos.size, "Expected Boot #2 to re-announce all 6 milestones: $infos")
        FirmwareStage.entries.forEachIndexed { idx, stage ->
            assertTrue(
                infos[6 + idx].contains("Milestone ${stage.label} reached"),
                "Missing Boot #2 milestone ${stage.label}: ${infos[6 + idx]}",
            )
            assertFalse(
                infos[6 + idx].contains("275."),
                "Boot #2 milestone should report fresh elapsed time, not Boot #1's 275s: ${infos[6 + idx]}",
            )
        }
        assertTrue(warns.isEmpty(), "Expected zero warnings across clean Boot #2: $warns")
    }
}
