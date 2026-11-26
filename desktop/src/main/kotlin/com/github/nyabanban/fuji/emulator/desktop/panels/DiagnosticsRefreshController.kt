package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.runtime.Formats

/**
 * Throttles Diagnostics JSON tab refreshes and background audio status polling so opening the
 * `诊断` tab never stalls the ARM emulation owner thread or the JavaFX UI thread at 4 Hz.
 */
class DiagnosticsRefreshController(
    val autoRefreshIntervalNanos: Long = DEFAULT_AUTO_REFRESH_INTERVAL_NANOS,
    val audioIdleIntervalNanos: Long = DEFAULT_AUDIO_IDLE_INTERVAL_NANOS,
    val maxDiagnosticsChars: Int = DEFAULT_MAX_DIAGNOSTICS_CHARS,
) {
    init {
        require(autoRefreshIntervalNanos > 0L) { "autoRefreshIntervalNanos must be positive" }
        require(audioIdleIntervalNanos > 0L) { "audioIdleIntervalNanos must be positive" }
        require(maxDiagnosticsChars > 0) { "maxDiagnosticsChars must be positive" }
    }

    var autoRefreshEnabled: Boolean = true
    var hasRenderedDiagnostics: Boolean = false
        private set

    private var manualRefreshRequested: Boolean = false
    private var lastDiagnosticsFetchNanos: Long = Long.MIN_VALUE
    private var lastDisplayedDiagnosticsText: String? = null
    private var hasFetchedAudio: Boolean = false
    private var lastAudioFetchNanos: Long = Long.MIN_VALUE

    fun requestManualRefresh() {
        manualRefreshRequested = true
    }

    fun shouldFetchDiagnostics(tabVisible: Boolean, paused: Boolean, nowNanos: Long): Boolean {
        if (!tabVisible) return false
        if (manualRefreshRequested) return true
        if (!autoRefreshEnabled) return false
        if (paused && hasRenderedDiagnostics) return false
        if (lastDiagnosticsFetchNanos == Long.MIN_VALUE) return true
        return (nowNanos - lastDiagnosticsFetchNanos) >= autoRefreshIntervalNanos
    }

    fun markDiagnosticsFetchDispatched(nowNanos: Long) {
        manualRefreshRequested = false
        lastDiagnosticsFetchNanos = nowNanos
    }

    fun shouldFetchAudio(audioTabVisible: Boolean, nowNanos: Long): Boolean {
        if (audioTabVisible || !hasFetchedAudio) return true
        return (nowNanos - lastAudioFetchNanos) >= audioIdleIntervalNanos
    }

    fun markAudioFetchDispatched(nowNanos: Long) {
        hasFetchedAudio = true
        lastAudioFetchNanos = nowNanos
    }

    /** Formats snapshot JSON off the JavaFX UI thread and caps length to [maxDiagnosticsChars]. */
    fun formatDiagnosticsJson(snapshot: Map<String, Any?>): String =
        Formats.json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot).take(maxDiagnosticsChars)

    /** Returns true only when [formattedText] differs from the currently displayed text. */
    fun acceptFormattedIfChanged(formattedText: String): Boolean {
        hasRenderedDiagnostics = true
        if (formattedText == lastDisplayedDiagnosticsText) return false
        lastDisplayedDiagnosticsText = formattedText
        return true
    }

    companion object {
        const val DEFAULT_AUTO_REFRESH_INTERVAL_NANOS: Long = 2_000_000_000L
        const val DEFAULT_AUDIO_IDLE_INTERVAL_NANOS: Long = 1_000_000_000L
        const val DEFAULT_MAX_DIAGNOSTICS_CHARS: Int = 131_072

        /**
         * Selects the minimal component ID subset required for a periodic UI snapshot so hidden
         * tabs (`镜头光学` / IBIS `soc-spi` / `mma8452q`, floating `LensProfileWindow`, and
         * `soc-vglib-init`) are never polled unnecessarily on `emulation-owner` at 4 Hz.
         */
        fun selectUiStateComponentIds(
            baseIds: Set<String>,
            includeVglib: Boolean,
            includeLens: Boolean,
            includeOrientationIbis: Boolean,
        ): Set<String> {
            if (!includeVglib && !includeLens && !includeOrientationIbis) return baseIds
            val result = LinkedHashSet<String>(baseIds.size + 4)
            result.addAll(baseIds)
            if (includeVglib) result.add("soc-vglib-init")
            if (includeLens) result.add("lens")
            if (includeOrientationIbis) {
                result.add("soc-spi")
                result.add("mma8452q")
            }
            return result
        }
    }
}
