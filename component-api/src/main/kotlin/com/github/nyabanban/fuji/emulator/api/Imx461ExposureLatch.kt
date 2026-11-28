package com.github.nyabanban.fuji.emulator.api

/** IMX461 electronic-shutter timing triple (`SHR`, `SVR`, `VMAX`) as seen by the exposure model. */
data class Imx461ShutterTiming(val shr15: Int, val svr12: Int, val vmax12: Int)

/**
 * Atomic IMX461 exposure register tuple (`SHR`, `SVR`, `VMAX`, `PGC`, `HCG`, plus Movie-mode
 * `MDSEL`, `0x0004` mode flags, and `SHR2` `0x0023/0x0024`) applied together on a sensor frame boundary.
 */
data class Imx461ExposureState(
    val shr15: Int,
    val svr12: Int,
    val vmax12: Int,
    val pgc12: Int = 0,
    val hcgEnabled: Boolean = false,
    val mdsel8: Int = 0,
    val modeFlags04: Int = 0,
    val movieShr15: Int = 0,
    /** CCDTG frame length in SHR2 units, or zero before the timing generator is configured. */
    val movieFrameRows: Int = 0,
) {
    fun toShutterTiming(): Imx461ShutterTiming = Imx461ShutterTiming(shr15, svr12, vmax12)
}

/**
 * Atomic valid-exposure latch for the IMX461 exposure model.
 *
 * Observed live on GFX100S 2.13 (`GXUP0004.DAT`):
 * 1. When the FILM SIMULATION selection changes (or during AF mode transitions), firmware reloads
 *    the full 255-register IMX461 mode table and writes `SHR = 1705` (or `1728`) while `SVR = 0`
 *    and `VMAX = 0`, i.e. a shutter row outside the implicit 905-unit 60 fps frame. About 250 ms
 *    later it writes a normal AE value again (`SHR = 452..737`). Without this latch,
 *    [Imx461ExposurePort.computeEffectiveExposure] turns that out-of-frame row into a 1-unit
 *    integration time, live view goes pitch black for ~12 frames, and AE panics up to high ISO.
 * 2. Multi-register exposure/gain updates (`SHR`/`SVR`/`VMAX`/`PGC`/`HCG`) must commit atomically
 *    outside `REGHOLD` (`0x0002` bit 6) so half-updated pairs (e.g. `HCG` toggled before `PGC`
 *    resets, or `SVR` incremented before `SHR` adjusts) never emit transient multi-EV bright spikes.
 *
 * HYPOTHESIS (not DAT-verified): the sensor ignores out-of-frame shutter rows and holds its last
 * valid in-frame `(SHR, SVR, VMAX, PGC, HCG)` tuple until an in-frame write commits outside
 * `REGHOLD`. All legitimate in-frame shutter speeds (`1/4000 s` .. multi-frame `SVR` long
 * exposures) remain valid. With a guest clock, Movie shutter programs become visible after
 * their programmed integration time, unless a later SPI burst replaces them first.
 */
class Imx461ExposureLatch(private val clock: EmulationClock? = null) {
    private var previousReadout: Imx461ExposureState? = null
    private var readoutReadyAt = 0L

    /** Last valid in-frame exposure state, or `null` until firmware programs one. */
    var latchedState: Imx461ExposureState? = null
        private set

    /** Last in-frame shutter timing, or `null` until firmware programs one. */
    val latched: Imx461ShutterTiming?
        get() = latchedState?.toShutterTiming()

    /** Number of observed out-of-frame shutter programs that were held at [latchedState]. */
    var heldOutOfFrameCount: Long = 0L
        private set

    /**
     * Records the current register values after a complete SPI burst commit; latches them when
     * `regholdActive` is false and the shutter row falls inside the programmed frame period.
     */
    fun observe(
        shr15: Int,
        svr12: Int,
        vmax12: Int,
        pgc12: Int = 0,
        hcgEnabled: Boolean = false,
        regholdActive: Boolean = false,
        mdsel8: Int = 0,
        modeFlags04: Int = 0,
        movieShr15: Int = 0,
        movieFrameRows: Int = 0,
    ) {
        if (regholdActive) return
        if (Imx461ExposurePort.isShutterWithinFrame(shr15, svr12, vmax12, mdsel8, modeFlags04, movieShr15, movieFrameRows)) {
            val next = Imx461ExposureState(shr15, svr12, vmax12, pgc12, hcgEnabled, mdsel8, modeFlags04, movieShr15, movieFrameRows)
            if (next == latchedState) return
            val now = clock?.ticks()
            if (now != null) scheduleReadout(next, now)
            latchedState = next
        } else if (latchedState != null) {
            heldOutOfFrameCount++
        }
    }

    private fun unfinishedReadout(now: Long): Imx461ExposureState? =
        previousReadout?.takeIf { now < readoutReadyAt }

    private fun scheduleReadout(next: Imx461ExposureState, now: Long) {
        val programmed = latchedState
        val prior = unfinishedReadout(now)
        val timingUnchanged = programmed != null &&
            programmed.mdsel8 == next.mdsel8 &&
            programmed.modeFlags04 == next.modeFlags04 &&
            programmed.movieFrameRows == next.movieFrameRows &&
            programmed.shr15 == next.shr15 &&
            programmed.svr12 == next.svr12 &&
            programmed.vmax12 == next.vmax12
        if (timingUnchanged) {
            // When PGC/HCG updates in a separate SPI burst before SHR1 in Movie mode,
            // keep the completed exposure until readout settles so a split (old SHR1, new PGC/HCG)
            // pair is neither rendered directly nor frozen into previousReadout by the paired SHR1 write.
            if (prior != null ||
                !Imx461ExposurePort.isMovieShutterMode(next.mdsel8) || next.vmax12 !in 0..63 ||
                (programmed.pgc12 == next.pgc12 && programmed.hcgEnabled == next.hcgEnabled)
            ) {
                return
            }
            val frameRows = Imx461ExposurePort.movieBaseFramePeriodUnits(next.mdsel8, next.modeFlags04, next.movieFrameRows)
            val integration = ((next.svr12 + 1L) * frameRows - next.shr15).coerceIn(1L, frameRows)
            val delay = (integration * GUEST_TICKS_PER_SECOND + MOVIE_SHUTTER_UNITS_PER_SECOND - 1L) / MOVIE_SHUTTER_UNITS_PER_SECOND
            previousReadout = programmed
            readoutReadyAt = now + delay
            return
        }
        val completed = prior ?: programmed
        previousReadout = null
        if (completed == null || !Imx461ExposurePort.isMovieShutterMode(next.mdsel8) || next.vmax12 !in 0..63) return
        val frameRows = Imx461ExposurePort.movieBaseFramePeriodUnits(next.mdsel8, next.modeFlags04, next.movieFrameRows)
        val integration = ((next.svr12 + 1L) * frameRows - next.shr15).coerceAtLeast(1L)
        // MMIO can request an LV0 image immediately after a shutter write, even within a frame.
        // Keep the completed sample until this integration can supply a readout, without extending
        // an already-pending deadline so continuous per-frame AE updates can never starve readout.
        val delay = (integration * GUEST_TICKS_PER_SECOND + MOVIE_SHUTTER_UNITS_PER_SECOND - 1L) / MOVIE_SHUTTER_UNITS_PER_SECOND
        previousReadout = completed
        readoutReadyAt = if (prior != null) minOf(readoutReadyAt, now + delay) else now + delay
    }

    /** Returns the atomic exposure tuple the sensor integrates with for the given register values. */
    fun resolveState(
        shr15: Int,
        svr12: Int,
        vmax12: Int,
        pgc12: Int,
        hcgEnabled: Boolean,
        regholdActive: Boolean = false,
        mdsel8: Int = 0,
        modeFlags04: Int = 0,
        movieShr15: Int = 0,
        movieFrameRows: Int = 0,
    ): Imx461ExposureState {
        val prior = clock?.let { unfinishedReadout(it.ticks()) }
        if (prior != null) return prior
        if (!regholdActive &&
            Imx461ExposurePort.isShutterWithinFrame(shr15, svr12, vmax12, mdsel8, modeFlags04, movieShr15, movieFrameRows)
        ) {
            return Imx461ExposureState(shr15, svr12, vmax12, pgc12, hcgEnabled, mdsel8, modeFlags04, movieShr15, movieFrameRows)
        }
        return latchedState ?: Imx461ExposureState(shr15, svr12, vmax12, pgc12, hcgEnabled, mdsel8, modeFlags04, movieShr15, movieFrameRows)
    }

    /** Returns the shutter timing the sensor integrates with for the given register values. */
    fun resolve(shr15: Int, svr12: Int, vmax12: Int): Imx461ShutterTiming {
        if (Imx461ExposurePort.isShutterWithinFrame(shr15, svr12, vmax12)) {
            return Imx461ShutterTiming(shr15, svr12, vmax12)
        }
        return latched ?: Imx461ShutterTiming(shr15, svr12, vmax12)
    }

    fun reset() {
        latchedState = null
        previousReadout = null
        readoutReadyAt = 0L
        heldOutOfFrameCount = 0L
    }

    /** Snapshot payload, or `null` when nothing has been latched yet. */
    fun snapshot(): Map<String, Any?>? {
        val s = latchedState ?: return null
        val out = snapshotState(s)
        out["heldOutOfFrameCount"] = heldOutOfFrameCount
        val prior = previousReadout
        if (prior != null) {
            out["previousReadout"] = snapshotState(prior)
            out["readoutReadyAt"] = readoutReadyAt
        }
        return out
    }

    private fun snapshotState(s: Imx461ExposureState): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "shr15" to s.shr15.toLong(),
            "svr12" to s.svr12.toLong(),
            "vmax12" to s.vmax12.toLong(),
            "pgc12" to s.pgc12.toLong(),
            "hcgEnabled" to s.hcgEnabled,
        )
        if (s.mdsel8 != 0 || s.modeFlags04 != 0 || s.movieShr15 != 0) {
            out["mdsel8"] = s.mdsel8.toLong()
            out["modeFlags04"] = s.modeFlags04.toLong()
            out["movieShr15"] = s.movieShr15.toLong()
        }
        if (s.movieFrameRows > 0) out["movieFrameRows"] = s.movieFrameRows.toLong()
        return out
    }

    /** Restores a [snapshot] payload (`null` clears the latch). */
    fun restore(raw: Map<*, *>?) {
        if (raw == null) {
            reset()
            return
        }
        val held = raw["heldOutOfFrameCount"]
        require(held is Number && held.toLong() >= 0L) { "IMX461 exposure latch heldOutOfFrameCount invalid" }
        val previous = raw["previousReadout"]
        val deadline = when (val value = raw["readoutReadyAt"]) {
            null -> 0L
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("IMX461 readout deadline requires an integer")
        }
        require(("previousReadout" !in raw && "readoutReadyAt" !in raw) || (previous is Map<*, *> && deadline > 0L)) {
            "Invalid IMX461 in-flight readout"
        }
        val nextPrior = (previous as? Map<*, *>)?.let(::restoreState)
        val next = restoreState(raw.filterKeys { it != "heldOutOfFrameCount" && it != "previousReadout" && it != "readoutReadyAt" })
        latchedState = next
        previousReadout = nextPrior
        readoutReadyAt = deadline
        heldOutOfFrameCount = held.toLong()
    }

    private fun restoreState(raw: Map<*, *>): Imx461ExposureState {
        val keys = raw.keys - "movieFrameRows"
        require(keys == MOVIE_SNAPSHOT_KEYS || keys == SNAPSHOT_KEYS || keys == LEGACY_SNAPSHOT_KEYS) {
            "Unexpected IMX461 exposure latch snapshot keys: ${raw.keys}"
        }
        val shr = field(raw["shr15"], "shr15", 0x7FFF)
        val svr = field(raw["svr12"], "svr12", 0xFFF)
        val vmax = field(raw["vmax12"], "vmax12", 0xFFF)
        val pgc = if ("pgc12" in raw) field(raw["pgc12"], "pgc12", 0xFFF) else 0
        val hcg = if ("hcgEnabled" in raw) {
            raw["hcgEnabled"] as? Boolean ?: throw IllegalArgumentException("IMX461 exposure latch hcgEnabled requires boolean")
        } else {
            false
        }
        val mdsel = if ("mdsel8" in raw) field(raw["mdsel8"], "mdsel8", 0xFF) else 0
        val flags04 = if ("modeFlags04" in raw) field(raw["modeFlags04"], "modeFlags04", 0xFF) else 0
        val movieShr = if ("movieShr15" in raw) field(raw["movieShr15"], "movieShr15", 0x7FFF) else 0
        val rows = if ("movieFrameRows" in raw) field(raw["movieFrameRows"], "movieFrameRows", 0x7FFF) else 0
        require(Imx461ExposurePort.isShutterWithinFrame(shr, svr, vmax, mdsel, flags04, movieShr, rows)) {
            "IMX461 exposure latch snapshot holds an out-of-frame shutter timing"
        }
        return Imx461ExposureState(shr, svr, vmax, pgc, hcg, mdsel, flags04, movieShr, rows)
    }

    private companion object {
        const val GUEST_TICKS_PER_SECOND = 54_000_000L
        const val MOVIE_SHUTTER_UNITS_PER_SECOND = 38_650L
        val LEGACY_SNAPSHOT_KEYS = setOf("shr15", "svr12", "vmax12")
        val SNAPSHOT_KEYS = setOf("shr15", "svr12", "vmax12", "pgc12", "hcgEnabled")
        val MOVIE_SNAPSHOT_KEYS = SNAPSHOT_KEYS + setOf("mdsel8", "modeFlags04", "movieShr15")

        fun field(raw: Any?, name: String, max: Int): Int {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "IMX461 exposure latch $name requires integer" }
            val v = (raw as Number).toLong()
            require(v in 0L..max.toLong()) { "IMX461 exposure latch $name out of range: $v" }
            return v.toInt()
        }
    }
}
