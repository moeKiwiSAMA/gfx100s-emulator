package com.github.nyabanban.fuji.emulator.api

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Effective optical exposure parameters derived from live Sony IMX461 SPI registers.
 */
data class EffectiveSensorExposure(
    val durationNanos: Long,
    val iso: Int,
)

/**
 * Hardware still-capture shutter timing observed from IMX461 SPI (`MDSEL`, `SHR`, `SVR`, `PGC`, `HCG`)
 * combined with CCDTG pulse channels (`0xFFF6C280` Rear Curtain, `0xFFF6C2C0` Front Curtain, and
 * `0xFFF6C194` frame lines) on GFX100S 2.13 (`section5.bin` `0x02502b20` / `0x024fc280` / `0x024fc6a4`).
 */
data class StillShutterHardwareTiming(
    val mdsel8: Int,
    val shr15: Int,
    val svr12: Int,
    val pgc12: Int,
    val hcgEnabled: Boolean,
    val frameLines: Int,
    val rearCurtainDelayTicks: Long? = null,
    val frontCurtainDelayTicks: Long? = null,
    val modeFlags05: Int = 0,
)

/** Receives timing-generator mode changes that affect the next CCDIF vertical sync. */
fun interface CcdFrameClockPort {
    fun resumeLiveView()

    /** True while CCDIF is generating frame boundaries for an active recording session. */
    val movieFrameClockActive: Boolean get() = false

    /** Latest sensor frame-sync edge with a current input image while the movie encoder is running. */
    fun latestMovieFrameSyncTick(): Long? = null

    companion object {
        const val PORT_ID = "soc-isp.ccd-frame-clock"
    }
}

/** CCDTG channel 2 consumes the same physical frame boundary as the recording readout. */
fun interface CcdMovieFrameInput {
    fun onFrameSync(tick: Long)

    companion object {
        const val PORT_ID = "soc-isp-cmd.ccd-movie-frame"
    }
}

/**
 * Read-only port exposing live Sony IMX461 SPI exposure & analog-gain registers
 * (`0xFFF6C200..0xFFF6C240`, GFX100S 2.13 `section5.bin` `0x024FC280` / `0x024FC6A4` / `0x02500D80` / `0x02503544`).
 *
 * Registered by `soc-isp-cmd` at `soc-isp-cmd.imx461.exposure` and consumed by `soc-isp`,
 * `soc-jpeg`, and `ImageScalerE000` to close the real-silicon exposure & AE metering loop.
 */
interface Imx461ExposurePort {
    /** ADC precision of the most recent still readout, retained when live view resumes. */
    val stillReadoutBitDepth: Int get() = 14

    /** True when the timing generator explicitly selects live-view cadence. */
    val liveViewTimingActive: Boolean get() = false

    /** True once firmware has written at least one IMX461 SHR (`0x0016/17`) or PGC (`0x002E/2F`) register. */
    val hasExposureRegs: Boolean

    /** 15-bit shutter integration offset `SHR` (`0x0016` lo, `0x0017[6:0]` hi). */
    val shr15: Int

    /** 12-bit V-period extension / long-shutter frame count `SVR` (`0x0012` lo, `0x0013[3:0]` hi). */
    val svr12: Int

    /** 12-bit primary analog ISO gain `PGC` (`0x002E` lo, `0x002F[3:0]` hi). */
    val pgc12: Int

    /** 12-bit secondary analog gain (`0x0030` lo, `0x0031[3:0]` hi). */
    val pgcSecondary12: Int

    /** Dual Conversion Gain / High Conversion Gain enable (`0x002D` bit 0). */
    val hcgEnabled: Boolean

    /** 12-bit `VMAX` (`0x001F` lo, `0x0020[3:0]` hi). */
    val vmax12: Int

    /** 8-bit sensor digital gain `DGAIN` (`0x0018`). */
    val dgain8: Int

    /** 8-bit drive mode selector `MDSEL` (`0x0001`; e.g. `0xB1` still 60p LV, `0x92` FHD movie, `0x91` 4K movie). */
    val mdsel8: Int
        get() = 0

    /** 8-bit drive sub-mode flags (`0x0004`; distinguishes `MDSEL=0x92` 50p `0x03` vs 60p `0x07/0x0B`). */
    val modeFlags04: Int
        get() = 0

    /** 15-bit Movie-mode shutter integration offset `SHR2` (`0x0023` lo, `0x0024[6:0]` hi; DAT S5 `0x02503750` / `0x024FEC84`). */
    val movieShr15: Int
        get() = 0

    /**
     * Effective electronic-shutter & analog-gain state `(SHR, SVR, VMAX, PGC, HCG, MDSEL, SHR2)`
     * applied to sensor integration. Defaults to the raw register values; implementations backed by
     * [Imx461ExposureLatch] hold the last valid in-frame state atomically across transient
     * out-of-frame mode reloads and `REGHOLD` bursts.
     */
    val effectiveExposureState: Imx461ExposureState
        get() = Imx461ExposureState(shr15, svr12, vmax12, pgc12, hcgEnabled, mdsel8, modeFlags04, movieShr15)

    /** Effective electronic-shutter timing `(SHR, SVR, VMAX)` derived from [effectiveExposureState]. */
    val effectiveShutterTiming: Imx461ShutterTiming
        get() = effectiveExposureState.let { Imx461ShutterTiming(it.shr15, it.svr12, it.vmax12) }

    /**
     * Active hardware still-capture timing (Mechanical Shutter, EFCS, or Electronic Shutter),
     * or `null` when no still shutter timing has been programmed.
     */
    val stillShutterTiming: StillShutterHardwareTiming?
        get() = null

    companion object {
        const val PORT_ID = "soc-isp-cmd.imx461.exposure"
        const val NOMINAL_DURATION_NANOS = 1000L
        const val NOMINAL_ISO = 100

        /**
         * Sony IMX461 Dual Conversion Gain (`HCG`, register `0x002D` bit 0) linear gain multiplier.
         * Calibrated from three independent live GFX100S 2.13 (`GXUP0004.DAT`) AE HCG transitions
         * (`PGC 2322 HCG=1 -> 3199 HCG=0`, `2594 -> 2766`, `2815 -> 2938`), which yield `2.996x`,
         * `2.997x`, and `2.976x` (`3.0x` = `+1.585 EV`), eliminating the `-0.58 EV` brightness cliff
         * that previously occurred across ISO 400..500 under `2.0x`.
         */
        const val HCG_GAIN_MULTIPLIER = 3.0

        /**
         * Implicit 60 fps LiveView frame period in `SHR` units when `VMAX` is not programmed (`< 64`):
         * the MDSEL drive mode supplies `VMAX_16 = 905` (DAT S5 `0x02501DC0..0x02501E30`, live M-mode sweep).
         */
        const val IMPLICIT_FRAME_PERIOD_UNITS = 905L

        /** IMX461 Still drive modes (`MDSEL 0x0001`, DAT S5 `0x0283D778` T[0..3]). */
        const val MDSEL_STILL_16BIT = 0x00
        const val MDSEL_STILL_14BIT = 0x40
        const val MDSEL_STILL_12BIT = 0x80

        /**
         * 72 MHz CCDTG TGCLK ticks corresponding to the nominal `1/250 s` (`4.0 ms`) exposure
         * (`72_000_000 / 250 = 288_000`), matching LiveView `NOMINAL_DURATION_NANOS = 1000L`.
         */
        const val STILL_NOMINAL_TGCLK_TICKS = 288_000.0

        /**
         * GFX100S 2.13 EFCS rear-curtain mechanical travel pre-trigger compensation
         * (`EEPROM 0x12807 = -4528` = `-4.528 ms` at 72 MHz TGCLK).
         */
        const val EFCS_REAR_CURTAIN_TRAVEL_TICKS = 326_016L
        private const val EFCS_MIN_FRONT_CURTAIN_TICKS = 32_000L

        /** IMX461 Movie drive modes (`MDSEL 0x0001`, DAT S5 `0x028DFC4C` T[5..7] & T[12..14]). */
        const val MDSEL_MOVIE_4K_30P = 0x91
        const val MDSEL_MOVIE_FHD_60P = 0x92

        /**
         * Exact Movie-mode `SHR2` (`0x0023/0x0024`) frame periods (`timing[1] / 2`, DAT S5
         * `0x0283D778` + `0x024FB6DC` `lsl #3` + `0x02501DE8` `lsr #4`):
         * - `MDSEL 0x92` 59.98p (`T[12..13]`, `r04=0x07/0x0B`): `1288 / 2 = 644` ticks/frame (`~38.63 kHz` line clock)
         * - `MDSEL 0x92` 50.03p (`T[14]`, `r04=0x03`): `1544 / 2 = 772` ticks/frame (`~38.62 kHz` line clock)
         * - `MDSEL 0x91` 4K 29.98p (`T[5..7]`): `2582 / 2 = 1291` ticks/frame (`~38.70 kHz` line clock)
         * All three share the same `~38.65 kHz` line clock (`~155` ticks per `1/250 s` nominal exposure).
         */
        const val MOVIE_FHD_60P_FRAME_PERIOD_UNITS = 644L
        const val MOVIE_FHD_50P_FRAME_PERIOD_UNITS = 772L
        const val MOVIE_4K_30P_FRAME_PERIOD_UNITS = 1291L
        const val MOVIE_NOMINAL_INTEGRATION_UNITS = 155.0

        /** True when `MDSEL` selects a full-resolution Still drive mode (16-bit `0x00`, 14-bit `0x40`, 12-bit `0x80`). */
        fun isStillCaptureMode(mdsel8: Int): Boolean =
            mdsel8 == MDSEL_STILL_16BIT || mdsel8 == MDSEL_STILL_14BIT || mdsel8 == MDSEL_STILL_12BIT

        /** True when `MDSEL` selects a Movie drive mode that programs shutter via `SHR2` (`0x0023/24`) & `SVR2` (`0x001F/20`). */
        fun isMovieShutterMode(mdsel8: Int): Boolean =
            mdsel8 == MDSEL_MOVIE_FHD_60P || mdsel8 == MDSEL_MOVIE_4K_30P

        /** Single-frame `SHR2` period in ticks for a Movie `MDSEL` drive mode (`DAT` S5 `0x0283D778`). */
        fun movieBaseFramePeriodUnits(mdsel8: Int, modeFlags04: Int = 0, movieFrameRows: Int = 0): Long = when {
            movieFrameRows > 0 -> movieFrameRows.toLong()
            mdsel8 == MDSEL_MOVIE_4K_30P -> MOVIE_4K_30P_FRAME_PERIOD_UNITS
            mdsel8 == MDSEL_MOVIE_FHD_60P -> if (modeFlags04 == 0x03) MOVIE_FHD_50P_FRAME_PERIOD_UNITS else MOVIE_FHD_60P_FRAME_PERIOD_UNITS
            else -> IMPLICIT_FRAME_PERIOD_UNITS
        }

        /** Frame period in `SHR` units for the given `SVR` (long-shutter frame count) and `VMAX`. */
        fun framePeriodUnits(svr12: Int, vmax12: Int): Long {
            val frames = svr12.coerceAtLeast(0).toLong() + 1L
            return if (vmax12 >= 64) frames * vmax12.toLong() else frames * IMPLICIT_FRAME_PERIOD_UNITS
        }

        /** True when the active electronic-shutter row (`SHR` or Movie `SHR2`) falls inside the programmed frame period. */
        fun isShutterWithinFrame(
            shr15: Int,
            svr12: Int,
            vmax12: Int,
            mdsel8: Int = 0,
            modeFlags04: Int = 0,
            movieShr15: Int = 0,
            movieFrameRows: Int = 0,
        ): Boolean {
            if (isMovieShutterMode(mdsel8) && vmax12 < 64) {
                val frames = vmax12.coerceAtLeast(0).toLong() + 1L
                val period = frames * movieBaseFramePeriodUnits(mdsel8, modeFlags04, movieFrameRows)
                return movieShr15 >= 0 && movieShr15.toLong() < period
            }
            return shr15 >= 0 && shr15.toLong() < framePeriodUnits(svr12, vmax12)
        }

        /**
         * Converts live IMX461 `SHR`/`SVR`/`VMAX`/`PGC`/`HCG` (and Movie-mode `MDSEL`/`SHR2`)
         * shadow registers into calibrated emulator [EffectiveSensorExposure] (`durationNanos` in
         * `[1, 256000]`, `iso` in `[25, 25600]`).
         */
        fun computeEffectiveExposure(
            port: Imx461ExposurePort?,
            nominalDurationNanos: Long = NOMINAL_DURATION_NANOS,
            nominalIso: Int = NOMINAL_ISO,
        ): EffectiveSensorExposure {
            if (port == null || !port.hasExposureRegs) {
                return EffectiveSensorExposure(nominalDurationNanos, nominalIso)
            }
            var durationNanos = nominalDurationNanos
            val state = port.effectiveExposureState
            val svr = state.svr12.coerceAtLeast(0)
            val shr = state.shr15.coerceAtLeast(0)
            val vmax = state.vmax12
            if (isMovieShutterMode(state.mdsel8) && vmax < 64) {
                // DAT S5 0x02503750 / 0x024FEC84 / 0x024FB6DC / 0x0283D778:
                // In Movie modes (MDSEL 0x92 FHD 60p/50p, 0x91 4K 30p), firmware leaves 0x0016/17 frozen
                // and drives electronic shutter via 0x001F/20 (SVR2) and 0x0023/24 (SHR2) against the
                // mode's timing[1]/2 frame period (~38.65 kHz line clock -> ~155 ticks at 1/250 s).
                val basePeriod = movieBaseFramePeriodUnits(state.mdsel8, state.modeFlags04, state.movieFrameRows)
                val movieSvr = vmax.coerceAtLeast(0).toLong()
                val movieShr = state.movieShr15.coerceAtLeast(0).toLong()
                val vPeriod = maxOf((movieSvr + 1L) * basePeriod, movieShr + 1L)
                val intUnits = (vPeriod - movieShr).coerceAtLeast(1L)
                durationNanos = ((intUnits.toDouble() / MOVIE_NOMINAL_INTEGRATION_UNITS) * nominalDurationNanos.toDouble())
                    .roundToLong()
                    .coerceIn(1L, 256_000L)
            } else if (vmax >= 64) {
                val vPeriod = (svr.toLong() + 1L) * vmax.toLong()
                if (shr < vPeriod) {
                    val intLines = (vPeriod - shr.toLong()).coerceAtLeast(1L)
                    durationNanos = ((intLines.toDouble() / vmax.toDouble()) * nominalDurationNanos.toDouble())
                        .roundToLong()
                        .coerceIn(1L, 256_000L)
                }
            } else {
                // DAT S5 0x02501DC0..0x02501E30 & live IMX461 M-mode sweep:
                // In 60 fps LiveView (MDSEL sets implicit VMAX_16 = 905 ticks/frame):
                //   1/60s -> shr=603 (int=302), 1/80s -> shr=681 (int=224), 1/100s -> shr=726 (int=179),
                //   1/125s -> shr=762 (int=143), 1/250s -> shr=833 (int=72 nominal EV 11.1 studio/daylight at f/2.8 ISO 100),
                //   1/4000s -> shr=900 (int=5).
                val basePeriod16 = IMPLICIT_FRAME_PERIOD_UNITS
                val vPeriod16 = maxOf((svr.toLong() + 1L) * basePeriod16, shr.toLong() + 1L)
                val intUnits = (vPeriod16 - shr.toLong()).coerceAtLeast(1L)
                durationNanos = ((intUnits.toDouble() / 70.0) * nominalDurationNanos.toDouble())
                    .roundToLong()
                    .coerceIn(1L, 256_000L)
            }
            val iso = computeEffectiveIso(state.pgc12, state.hcgEnabled, nominalIso)
            return EffectiveSensorExposure(durationNanos, iso)
        }

        /**
         * Converts programmed still-capture hardware timing (CCDTG Rear/Front curtain pulse channels
         * `0xFFF6C280`/`0xFFF6C2C0` + IMX461 `MDSEL`/`SHR`/`SVR`/`PGC`/`HCG`) into calibrated
         * emulator [EffectiveSensorExposure] for Mechanical Shutter (MS), EFCS, and Electronic
         * Shutter (ES), independent of whether LiveView Exposure Preview is ON or OFF.
         *
         * Returns `null` if no still-capture hardware timing is active on [port].
         */
        fun computeEffectiveStillExposure(
            port: Imx461ExposurePort?,
            nominalDurationNanos: Long = NOMINAL_DURATION_NANOS,
            nominalIso: Int = NOMINAL_ISO,
        ): EffectiveSensorExposure? = computeEffectiveStillExposure(port, nominalDurationNanos, nominalIso, null)

        fun computeEffectiveStillExposure(
            port: Imx461ExposurePort?,
            nominalDurationNanos: Long,
            nominalIso: Int,
            mechanicalShutterTv16: Int?,
        ): EffectiveSensorExposure? {
            if (port == null || !port.hasExposureRegs) return null
            val timing = port.stillShutterTiming ?: return null
            val hmax = when (timing.mdsel8) {
                MDSEL_STILL_16BIT -> 2948L
                MDSEL_STILL_14BIT -> 1352L
                MDSEL_STILL_12BIT -> 930L
                else -> return null
            }
            val frameLines = when {
                timing.frameLines >= 500 -> timing.frameLines.toLong()
                timing.mdsel8 == MDSEL_STILL_12BIT && timing.modeFlags05 == 0x01 -> 6514L
                else -> 8900L
            }
            val svr = timing.svr12.coerceAtLeast(0).toLong()
            val shr = timing.shr15.coerceAtLeast(0).toLong()
            val rearDelay = timing.rearCurtainDelayTicks?.takeIf { it > 0L }
            val frontDelay = timing.frontCurtainDelayTicks?.takeIf { it > 0L }
            val exposureTicks = when {
                rearDelay != null && frontDelay != null -> {
                    // Mechanical Shutter (MS): Front curtain = config-bank-1, Rear curtain = config-bank-0.
                    val interval = svr * frameLines * hmax + rearDelay - frontDelay
                    // Pulses armed in different timing periods can have a negative difference
                    // even for a long exposure. The BIS capture command supplies its signed Tv.
                    if (interval <= 0L && mechanicalShutterTv16 != null) {
                        (250.0 * STILL_NOMINAL_TGCLK_TICKS * 2.0.pow(-mechanicalShutterTv16 / 16.0)).roundToLong()
                    } else interval.coerceAtLeast(1L)
                }
                rearDelay != null -> {
                    // EFCS: Electronic Front Curtain = IMX461 SHR (2-line units), Rear curtain = config-bank-0
                    // pre-triggered by EEPROM 0x12807 (-4.528 ms = -326,016 TGCLK ticks).
                    val frontTicks = shr * 2L * hmax
                    val travelComp = if (frontTicks >= EFCS_MIN_FRONT_CURTAIN_TICKS) EFCS_REAR_CURTAIN_TRAVEL_TICKS else 0L
                    (svr * frameLines * hmax + rearDelay + travelComp - frontTicks).coerceAtLeast(1L)
                }
                else -> {
                    // Electronic Shutter (ES): IMX461 SVR/SHR directly determine integration lines.
                    // Ignore the dummy MS constant SHR <= 2 when SVR == 0 and no curtain bank was armed.
                    if (svr == 0L && shr <= 2L) return null
                    val totalLines = (svr + 1L) * frameLines
                    if (shr * 2L >= totalLines) return null
                    val intLines = (totalLines - shr * 2L).coerceAtLeast(1L)
                    intLines * hmax
                }
            }
            val durationNanos = ((exposureTicks.toDouble() / STILL_NOMINAL_TGCLK_TICKS) * nominalDurationNanos.toDouble())
                .roundToLong()
                .coerceIn(1L, 256_000L)
            val iso = computeEffectiveIso(timing.pgc12, timing.hcgEnabled, nominalIso)
            return EffectiveSensorExposure(durationNanos, iso)
        }

        private fun computeEffectiveIso(pgc12: Int, hcgEnabled: Boolean, nominalIso: Int): Int {
            val pgc = pgc12.coerceIn(0, 4030)
            // Live IMX461 ISO 100 baseline PGC = 2066 (ISO 125 = 2470, ISO 160 = 2802 -> exact 2^(1/3) steps).
            val basePgcGain = 4096.0 / (4096.0 - 2066.0)
            val pgcGain = if (pgc > 0) 4096.0 / (4096.0 - pgc.toDouble()) else basePgcGain
            val hcgMult = if (hcgEnabled) HCG_GAIN_MULTIPLIER else 1.0
            val relGain = (pgcGain * hcgMult) / basePgcGain
            return (nominalIso.toDouble() * relGain).roundToInt().coerceIn(25, 25_600)
        }
    }
}
