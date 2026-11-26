package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.DiagnosticsRefreshController
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsRefreshControllerTest {
    @Test
    fun throttlesDiagnosticsToTwoSecondsHonorsManualRefreshAndSkipsWhenHiddenOrPaused() {
        val controller = DiagnosticsRefreshController()
        var now = 10_000_000_000L

        // Hidden tab never triggers heavy diagnostics snapshot
        assertFalse(controller.shouldFetchDiagnostics(tabVisible = false, paused = false, nowNanos = now))

        // First visible tick fetches immediately
        assertTrue(controller.shouldFetchDiagnostics(tabVisible = true, paused = false, nowNanos = now))
        controller.markDiagnosticsFetchDispatched(now)

        // Next 7 ticks at 250ms cadence (< 2,000ms) are throttled
        repeat(7) {
            now += 250_000_000L
            assertFalse(controller.shouldFetchDiagnostics(tabVisible = true, paused = false, nowNanos = now))
        }

        // At 2,000ms boundary, auto-refresh fires again
        now += 250_000_000L
        assertTrue(controller.shouldFetchDiagnostics(tabVisible = true, paused = false, nowNanos = now))
        controller.markDiagnosticsFetchDispatched(now)
        assertTrue(controller.acceptFormattedIfChanged("""{"tick":1}"""))

        // While paused after initial render, auto-refresh stays quiet even past 2s
        now += 5_000_000_000L
        assertFalse(controller.shouldFetchDiagnostics(tabVisible = true, paused = true, nowNanos = now))

        // Explicit manual refresh ("立即刷新") still works when paused or when auto-refresh is disabled
        controller.autoRefreshEnabled = false
        controller.requestManualRefresh()
        assertTrue(controller.shouldFetchDiagnostics(tabVisible = true, paused = true, nowNanos = now))
        controller.markDiagnosticsFetchDispatched(now)
        assertFalse(controller.shouldFetchDiagnostics(tabVisible = true, paused = false, nowNanos = now))
    }

    @Test
    fun deduplicatesUnchangedJsonAndCapsFormattedDiagnosticsLength() {
        val controller = DiagnosticsRefreshController(maxDiagnosticsChars = 64)
        val snapshot = mapOf("tick" to 42L, "payload" to "x".repeat(200))
        val formatted = controller.formatDiagnosticsJson(snapshot)
        assertEquals(64, formatted.length)
        assertTrue(controller.acceptFormattedIfChanged(formatted))
        assertFalse(controller.acceptFormattedIfChanged(formatted), "Identical JSON must not trigger redundant TextArea re-layout")
        assertTrue(controller.acceptFormattedIfChanged(formatted.dropLast(1) + "!"))
    }

    @Test
    fun throttlesAudioStatusPollingToOneSecondWhenAudioTabIsClosed() {
        val controller = DiagnosticsRefreshController()
        var now = 1_000_000_000L

        // Initial fetch always succeeds even when Audio tab is closed
        assertTrue(controller.shouldFetchAudio(audioTabVisible = false, nowNanos = now))
        controller.markAudioFetchDispatched(now)

        // 250ms, 500ms, 750ms skipped while Audio tab is closed
        repeat(3) {
            now += 250_000_000L
            assertFalse(controller.shouldFetchAudio(audioTabVisible = false, nowNanos = now))
        }

        // When Audio tab is open, fetches immediately on every tick
        assertTrue(controller.shouldFetchAudio(audioTabVisible = true, nowNanos = now))

        // At 1,000ms elapsed, fetches again even with Audio tab closed
        now += 250_000_000L
        assertTrue(controller.shouldFetchAudio(audioTabVisible = false, nowNanos = now))
    }
}
