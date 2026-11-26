package com.github.nyabanban.fuji.emulator.api

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Physical Neutral Density (ND) optical filter presets and radiometric conversion helpers.
 *
 * A neutral density filter mounted ahead of the lens attenuates linear incident radiance
 * uniformly across the visible spectrum by `2^(-ndStops) = 10^(-opticalDensity)` before
 * CMOS photon shot noise and ADC quantization.
 */
enum class NdFilterPreset(
    val id: String,
    val displayName: String,
    val stops: Double?,
) {
    OFF("OFF", "OFF (0 EV)", 0.0),
    ND2("ND2", "ND2 (1 EV)", 1.0),
    ND4("ND4", "ND4 (2 EV)", 2.0),
    ND8("ND8", "ND8 (3 EV)", 3.0),
    ND16("ND16", "ND16 (4 EV)", 4.0),
    ND32("ND32", "ND32 (5 EV)", 5.0),
    ND64("ND64", "ND64 (6 EV)", 6.0),
    ND128("ND128", "ND128 (7 EV)", 7.0),
    ND256("ND256", "ND256 (8 EV)", 8.0),
    ND512("ND512", "ND512 (9 EV)", 9.0),
    ND1000("ND1000", "ND1000 (10 EV)", 10.0),
    VARIABLE("VARIABLE", "Variable ND", null);

    val fixedStops: Double? = stops
}

object NdFilterSpec {
    const val MIN_STOPS: Double = 0.0
    const val MAX_STOPS: Double = 10.0
    const val STEP_STOPS: Double = 0.1
    private const val LOG10_2: Double = 0.3010299956639812

    val PRESET_IDS: List<String> = NdFilterPreset.entries.map { it.id }
    val ALL_PRESET_IDS: List<String> = PRESET_IDS

    val FIXED_PRESETS: List<NdFilterPreset> = NdFilterPreset.entries.filter { it.stops != null }

    fun clampStops(stops: Double): Double {
        require(stops.isFinite()) { "ndStops must be finite, got $stops" }
        require(stops in MIN_STOPS..MAX_STOPS) {
            "ndStops must be in $MIN_STOPS..$MAX_STOPS, got $stops"
        }
        return stops
    }

    /** Linear optical transmittance `2^(-stops)` in `(0.0, 1.0]`. */
    fun transmittance(stops: Double): Double {
        if (!stops.isFinite() || stops <= 0.0) return 1.0
        return 2.0.pow(-stops.coerceIn(MIN_STOPS, MAX_STOPS)).coerceIn(0.0, 1.0)
    }

    /** Transmittance expressed as a percentage in `(0.0, 100.0]`. */
    fun transmittancePercent(stops: Double): Double = transmittance(stops) * 100.0

    /** Filter factor `2^(stops)` (e.g. `8.0` for ND8 / 3 EV). */
    fun filterFactor(stops: Double): Double {
        if (!stops.isFinite() || stops <= 0.0) return 1.0
        return 2.0.pow(stops.coerceIn(MIN_STOPS, MAX_STOPS))
    }

    fun factor(stops: Double): Double = filterFactor(stops)

    fun formatFactor(stops: Double): String {
        val f = filterFactor(stops)
        return if (abs(f - f.roundToInt().toDouble()) <= 0.05) {
            "${f.roundToInt()}×"
        } else {
            String.format(Locale.ROOT, "%.1f×", f)
        }
    }

    /** Optical density `OD = stops * log10(2)` (e.g. `0.90` for ND8 / 3 EV). */
    fun opticalDensity(stops: Double): Double {
        if (!stops.isFinite() || stops <= 0.0) return 0.0
        return stops.coerceIn(MIN_STOPS, MAX_STOPS) * LOG10_2
    }

    /** Derives the canonical preset ID for [stops] (`OFF`/`ND2`..`ND1000` on integer stops, else `VARIABLE`). */
    fun presetForStops(stops: Double): NdFilterPreset {
        val clamped = stops.coerceIn(MIN_STOPS, MAX_STOPS)
        val nearest = clamped.roundToInt()
        if (abs(clamped - nearest.toDouble()) <= 1e-3 && nearest in 0..10) {
            return FIXED_PRESETS[nearest]
        }
        return NdFilterPreset.VARIABLE
    }

    /** Parses a preset label (`OFF`, `ND8`, `nd1000`, `ND1024`, `0`, `VARIABLE`, etc.), returning `null` if unknown. */
    fun parsePreset(raw: String?): NdFilterPreset? {
        val norm = raw?.trim()?.uppercase(Locale.ROOT)?.replace(" ", "")?.replace("-", "")?.replace("_", "")
            ?: return null
        if (norm.isEmpty()) return null
        if (norm == "NONE" || norm == "0" || norm == "ND0" || norm == "ND1") return NdFilterPreset.OFF
        if (norm == "ND1024") return NdFilterPreset.ND1000
        if (norm == "VAR" || norm == "VND" || norm == "VARIABLE") return NdFilterPreset.VARIABLE
        return NdFilterPreset.entries.firstOrNull { it.id == norm }
    }

    fun formatSummary(stops: Double): String = formatCompactBadge(stops)

    fun formatCompactBadge(stops: Double): String {
        val clamped = stops.coerceIn(MIN_STOPS, MAX_STOPS)
        val preset = presetForStops(clamped)
        return if (preset == NdFilterPreset.OFF) {
            "OFF (0 EV)"
        } else if (preset != NdFilterPreset.VARIABLE) {
            String.format(Locale.ROOT, "%s (-%.0f EV)", preset.id, clamped)
        } else {
            String.format(Locale.ROOT, "VND -%.1f EV", clamped)
        }
    }

    fun formatTransmittancePercent(stops: Double): String {
        val pct = transmittancePercent(stops)
        return when {
            pct >= 10.0 -> String.format(Locale.ROOT, "%.1f%%", pct)
            pct >= 1.0 -> String.format(Locale.ROOT, "%.2f%%", pct)
            else -> String.format(Locale.ROOT, "%.3f%%", pct)
        }
    }
}
