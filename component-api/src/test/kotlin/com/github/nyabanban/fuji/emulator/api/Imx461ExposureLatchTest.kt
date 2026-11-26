package com.github.nyabanban.fuji.emulator.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Imx461ExposureLatchTest {

    private class FakeExposurePort(
        override var hasExposureRegs: Boolean = true,
        override var shr15: Int = 0,
        override var svr12: Int = 0,
        override var pgc12: Int = 2066,
        override var pgcSecondary12: Int = 2066,
        override var hcgEnabled: Boolean = false,
        override var vmax12: Int = 0,
        override var dgain8: Int = 0,
        override var mdsel8: Int = 0,
        override var modeFlags04: Int = 0,
        override var movieShr15: Int = 0,
        var regholdActive: Boolean = false,
        private val latch: Imx461ExposureLatch = Imx461ExposureLatch(),
    ) : Imx461ExposurePort {
        fun commit(
            shr: Int = shr15,
            svr: Int = svr12,
            vmax: Int = vmax12,
            pgc: Int = pgc12,
            hcg: Boolean = hcgEnabled,
            reghold: Boolean = regholdActive,
            mdsel: Int = mdsel8,
            flags04: Int = modeFlags04,
            movieShr: Int = movieShr15,
        ) {
            shr15 = shr
            svr12 = svr
            vmax12 = vmax
            pgc12 = pgc
            hcgEnabled = hcg
            regholdActive = reghold
            mdsel8 = mdsel
            modeFlags04 = flags04
            movieShr15 = movieShr
            latch.observe(shr15, svr12, vmax12, pgc12, hcgEnabled, regholdActive, mdsel8, modeFlags04, movieShr15)
        }

        override val effectiveExposureState: Imx461ExposureState
            get() = latch.resolveState(shr15, svr12, vmax12, pgc12, hcgEnabled, regholdActive, mdsel8, modeFlags04, movieShr15)
    }

    @Test
    fun framePeriodAndInFrameBoundaryChecksHandleImplicitAndExplicitVmax() {
        assertEquals(905L, Imx461ExposurePort.framePeriodUnits(svr12 = 0, vmax12 = 0))
        assertEquals(2715L, Imx461ExposurePort.framePeriodUnits(svr12 = 2, vmax12 = 0))
        assertEquals(6000L, Imx461ExposurePort.framePeriodUnits(svr12 = 2, vmax12 = 2000))

        assertTrue(Imx461ExposurePort.isShutterWithinFrame(shr15 = 727, svr12 = 0, vmax12 = 0))
        assertTrue(Imx461ExposurePort.isShutterWithinFrame(shr15 = 900, svr12 = 0, vmax12 = 0)) // 1/4000 s
        assertTrue(Imx461ExposurePort.isShutterWithinFrame(shr15 = 904, svr12 = 0, vmax12 = 0))
        assertFalse(Imx461ExposurePort.isShutterWithinFrame(shr15 = 905, svr12 = 0, vmax12 = 0))
        assertFalse(Imx461ExposurePort.isShutterWithinFrame(shr15 = 1705, svr12 = 0, vmax12 = 0))
        assertTrue(Imx461ExposurePort.isShutterWithinFrame(shr15 = 1705, svr12 = 1, vmax12 = 0)) // 2-frame SVR long exposure
        assertTrue(Imx461ExposurePort.isShutterWithinFrame(shr15 = 1705, svr12 = 0, vmax12 = 2000))
        assertFalse(Imx461ExposurePort.isShutterWithinFrame(shr15 = 2000, svr12 = 0, vmax12 = 2000))
    }

    @Test
    fun outOfFrameModeReloadWriteHoldsLastValidTimingWhileLegitimateShutterSpeedsPassThrough() {
        val latch = Imx461ExposureLatch()
        val port = FakeExposurePort(latch = latch)

        // Normal 1/100 s LiveView AE timing (SHR=727, SVR=0, VMAX=0 -> intUnits=178)
        port.commit(shr = 727, svr = 0, vmax = 0)
        val before = Imx461ExposurePort.computeEffectiveExposure(port)
        assertEquals(Imx461ShutterTiming(727, 0, 0), port.effectiveShutterTiming)
        assertEquals(2543L, before.durationNanos)

        // Film Simulation switch mode-reload writes SHR=1705 with SVR=0, VMAX=0 (out of implicit 905 frame)
        port.commit(shr = 1705, svr = 0, vmax = 0)
        assertEquals(1705, port.shr15) // raw SPI register stays faithful
        assertEquals(Imx461ShutterTiming(727, 0, 0), port.effectiveShutterTiming)
        assertEquals(1L, latch.heldOutOfFrameCount)
        assertEquals(before, Imx461ExposurePort.computeEffectiveExposure(port))

        // Genuine 1/4000 s manual shutter (SHR=900, in-frame) updates immediately
        port.commit(shr = 900, svr = 0, vmax = 0)
        assertEquals(Imx461ShutterTiming(900, 0, 0), port.effectiveShutterTiming)
        val fast = Imx461ExposurePort.computeEffectiveExposure(port)
        assertTrue(fast.durationNanos < 100L, "1/4000 s manual shutter must pass through (got ${fast.durationNanos})")

        // Genuine multi-frame long exposure (SVR=4, SHR=1705 < 5*905=4525) updates immediately
        port.commit(shr = 1705, svr = 4, vmax = 0)
        assertEquals(Imx461ShutterTiming(1705, 4, 0), port.effectiveShutterTiming)
        val longExp = Imx461ExposurePort.computeEffectiveExposure(port)
        assertTrue(longExp.durationNanos > before.durationNanos * 5, "Multi-frame SVR long exposure must pass through (got ${longExp.durationNanos})")
    }

    @Test
    fun snapshotRoundTripAndValidation() {
        val latch = Imx461ExposureLatch()
        assertNull(latch.snapshot())
        latch.observe(727, 0, 0)
        latch.observe(1705, 0, 0)
        val snap = assertNotNull(latch.snapshot())

        val restored = Imx461ExposureLatch()
        restored.restore(snap)
        assertEquals(Imx461ShutterTiming(727, 0, 0), restored.latched)
        assertEquals(1L, restored.heldOutOfFrameCount)
        assertEquals(Imx461ShutterTiming(727, 0, 0), restored.resolve(1705, 0, 0))

        assertFailsWith<IllegalArgumentException> {
            Imx461ExposureLatch().restore(
                mapOf("shr15" to 1705L, "svr12" to 0L, "vmax12" to 0L, "heldOutOfFrameCount" to 0L),
            )
        }
    }

    @Test
    fun hcgGainStepMatchesLiveImx461DualNativeTransitionWithoutBrightnessCliffAndRespectsReghold() {
        val latch = Imx461ExposureLatch()
        val port = FakeExposurePort(latch = latch)

        // Live GFX100S 2.13 AE transition across HCG=1 -> HCG=0 (smooth 0.660x/step = -0.60 EV):
        //   PGC=3323 (HCG=1) -> PGC=2925 (HCG=1) -> PGC=2322 (HCG=1) -> PGC=3199 (HCG=0) -> PGC=2747 (HCG=0)
        port.commit(shr = 727, pgc = 2925, hcg = true)
        val isoBeforeHcg = Imx461ExposurePort.computeEffectiveExposure(port).iso
        port.commit(shr = 727, pgc = 2322, hcg = true)
        val isoAtHcg1 = Imx461ExposurePort.computeEffectiveExposure(port).iso
        port.commit(shr = 727, pgc = 3199, hcg = false)
        val isoAtHcg0 = Imx461ExposurePort.computeEffectiveExposure(port).iso
        port.commit(shr = 727, pgc = 2747, hcg = false)
        val isoAfterHcg0 = Imx461ExposurePort.computeEffectiveExposure(port).iso

        val stepInHcg1 = isoAtHcg1.toDouble() / isoBeforeHcg.toDouble()
        val stepAcrossHcg = isoAtHcg0.toDouble() / isoAtHcg1.toDouble()
        val stepInHcg0 = isoAfterHcg0.toDouble() / isoAtHcg0.toDouble()
        assertTrue(stepInHcg1 in 0.64..0.68, "Expected ~0.66x step inside HCG=1, got $stepInHcg1")
        assertTrue(stepAcrossHcg in 0.64..0.68, "Expected smooth ~0.66x step across HCG 1->0 without cliff, got $stepAcrossHcg")
        assertTrue(stepInHcg0 in 0.64..0.69, "Expected ~0.665x step inside HCG=0, got $stepInHcg0")

        // Atomic REGHOLD protection: toggling HCG=1 BEFORE resetting PGC=2322 (or SVR=1 before SHR)
        // must NOT leak a +1.6..+2 EV bright spike while REGHOLD is asserted.
        val steady = Imx461ExposurePort.computeEffectiveExposure(port)
        port.commit(shr = 727, svr = 1, pgc = 2747, hcg = true, reghold = true)
        assertEquals(steady, Imx461ExposurePort.computeEffectiveExposure(port))
        // Clearing REGHOLD commits the final paired (SVR=0, SHR=727, PGC=2322, HCG=1) cleanly
        port.commit(shr = 727, svr = 0, pgc = 2322, hcg = true, reghold = false)
        assertEquals(isoAtHcg1, Imx461ExposurePort.computeEffectiveExposure(port).iso)
    }

    @Test
    fun movieModeUsesShr2AndSvr2AgainstModeFramePeriodWhileIgnoringFrozenStillShr1() {
        val latch = Imx461ExposureLatch()
        val port = FakeExposurePort(latch = latch)

        // Live GFX100S 2.13 FHD 59.94p Movie mode (MDSEL=0x92, r04=0x07, period=644 ticks):
        // Firmware freezes 0x0016/17 (SHR1) at 260 and drives shutter via 0x0023/24 (SHR2).
        port.commit(shr = 260, svr = 0, vmax = 0, pgc = 2066, hcg = false, mdsel = 0x92, flags04 = 0x07, movieShr = 495)
        val expAt495 = Imx461ExposurePort.computeEffectiveExposure(port)
        // intUnits = 644 - 495 = 149 -> (149 / 155) * 1000 = 961 ns (~1/260 s), NOT 9214 ns from frozen shr=260!
        assertEquals(961L, expAt495.durationNanos)
        assertEquals(100, expAt495.iso)

        // Higher SHR2 = shorter exposure (e.g. live Movie AE ramp 495 -> 614)
        port.commit(shr = 260, svr = 0, vmax = 0, pgc = 2066, hcg = false, mdsel = 0x92, flags04 = 0x07, movieShr = 614)
        val expAt614 = Imx461ExposurePort.computeEffectiveExposure(port)
        assertEquals(194L, expAt614.durationNanos)
        assertTrue(expAt614.durationNanos < expAt495.durationNanos / 4)

        // Out-of-frame Movie SHR2 (>= 644 with SVR2=0) holds last valid Movie state
        port.commit(shr = 260, svr = 0, vmax = 0, pgc = 2066, hcg = false, mdsel = 0x92, flags04 = 0x07, movieShr = 900)
        assertEquals(expAt614, Imx461ExposurePort.computeEffectiveExposure(port))
        assertEquals(1L, latch.heldOutOfFrameCount)

        // Snapshot round-trip preserves Movie-mode fields
        val snap = assertNotNull(latch.snapshot())
        val restored = Imx461ExposureLatch()
        restored.restore(snap)
        assertEquals(Imx461ExposureState(260, 0, 0, 2066, false, 0x92, 0x07, 614), restored.latchedState)

        // 4K 29.97p Movie mode (MDSEL=0x91, period=1291 ticks, 155 ticks = 1/250 s)
        port.commit(shr = 260, svr = 0, vmax = 0, pgc = 2066, hcg = false, mdsel = 0x91, flags04 = 0x03, movieShr = 1136)
        assertEquals(1000L, Imx461ExposurePort.computeEffectiveExposure(port).durationNanos)

        // FHD 50p PAL Movie mode (MDSEL=0x92, r04=0x03, period=772 ticks)
        port.commit(shr = 260, svr = 0, vmax = 0, pgc = 2066, hcg = false, mdsel = 0x92, flags04 = 0x03, movieShr = 617)
        assertEquals(1000L, Imx461ExposurePort.computeEffectiveExposure(port).durationNanos)
    }
    @Test
    fun movieTimingUsesTheCcdtgFramePeriodAtSlowRecordingRates() {
        for ((mode, rows) in listOf(0x92 to 1288, 0x92 to 644, 0x91 to 1291, 0x91 to 1548)) {
            val latch = Imx461ExposureLatch()
            latch.observe(131, 0, 0, mdsel8 = mode, modeFlags04 = 3, movieShr15 = rows - 6, movieFrameRows = rows)
            val state = assertNotNull(latch.latchedState)
            assertEquals(rows, state.movieFrameRows)
            val port = object : Imx461ExposurePort by FakeExposurePort() {
                override val hasExposureRegs = true
                override val effectiveExposureState = state
            }
            assertEquals(39L, Imx461ExposurePort.computeEffectiveExposure(port).durationNanos)
            latch.observe(131, 0, 0, mdsel8 = mode, modeFlags04 = 3, movieShr15 = rows, movieFrameRows = rows)
            assertEquals(state, latch.latchedState)
            assertEquals(state, Imx461ExposureLatch().apply { restore(latch.snapshot()) }.latchedState)
        }
    }

    @Test
    fun movieIntegrationMustFinishBeforeAnExtendedReadoutBecomesVisible() {
        var tick = 100L
        val clock = EmulationClock { tick }
        val latch = Imx461ExposureLatch(clock)
        fun program(shutter: Int, frames: Int, gain: Int = 2260) =
            latch.observe(131, 0, frames, gain, true, false, 0x91, 3, shutter, 1291)
        fun output(source: Imx461ExposureLatch) =
            source.resolveState(131, 0, 4, 2600, true, false, 0x91, 3, 1200, 1291)

        program(1280, 0)
        program(1200, 4)
        val deadline = tick + ((5 * 1291L - 1200) * 54_000_000L + 38_649) / 38_650
        tick += 1_801_800
        assertEquals(1280, output(latch).movieShr15)
        program(1200, 4, 2600)
        val restored = Imx461ExposureLatch(clock).apply { restore(latch.snapshot()) }
        program(1277, 0)
        val shortDeadline = tick + ((1291L - 1277) * 54_000_000L + 38_649) / 38_650
        tick = shortDeadline - 1
        assertEquals(1280, latch.resolveState(131, 0, 0, 2260, true, false, 0x91, 3, 1277, 1291).movieShr15)
        tick++
        assertEquals(1277, latch.resolveState(131, 0, 0, 2260, true, false, 0x91, 3, 1277, 1291).movieShr15)
        tick = deadline - 1
        assertEquals(1280, output(restored).movieShr15)
        tick++
        assertEquals(1200, output(restored).movieShr15)
        assertEquals(2600, output(restored).pgc12)
    }

    @Test
    fun fhd5994ReadoutWaitsForTheSingleFrameShutterProgramWhenDmaRunsMidIntegration() {
        var tick = 2_030_866_658L
        val latch = Imx461ExposureLatch(EmulationClock { tick })
        fun program(shutter: Int) = latch.observe(260, 0, 0, 3318, true, false, 0x92, 7, shutter, 644)
        fun read() = latch.resolveState(260, 0, 0, 3318, true, false, 0x92, 7, 244, 644).movieShr15
        program(596)
        program(244)
        tick = 2_030_897_324L
        assertEquals(596, read())
        val readyAt = 2_030_866_658L + (400 * 54_000_000L + 38_649) / 38_650
        tick = readyAt - 1
        assertEquals(596, read())
        tick++
        assertEquals(244, read())
    }

    @Test
    fun rapidMovieAeShutterUpdatesNeverStarveReadoutDeadline() {
        var tick = 1_000_000L
        val latch = Imx461ExposureLatch(EmulationClock { tick })
        fun program(shutter: Int) = latch.observe(260, 0, 0, 2066, false, false, 0x92, 7, shutter, 644)

        // Start overexposed (SHR2=100 -> 544 rows integration -> ~760,052 ticks delay)
        program(100)
        val firstUpdateTick = tick
        program(180) // 464 rows -> ~648,280 ticks deadline
        val firstDeadline = firstUpdateTick + ((644L - 180L) * 54_000_000L + 38_649L) / 38_650L

        // Continuous AE stepping faster than the readout delay must NEVER push readoutReadyAt out forever.
        for (shr in listOf(260, 340, 420, 500)) {
            tick += 150_000L
            program(shr)
        }
        // By firstDeadline, the latest AE shutter MUST be visible (not starved at stale SHR2=100).
        tick = firstDeadline
        assertEquals(500, latch.resolveState(260, 0, 0, 2066, false, false, 0x92, 7, 500, 644).movieShr15)
    }

    @Test
    fun movieGainUpdateBeforePairedShr2NeverExposesOrFreezesBrightWhiteFlashTransient() {
        var tick = 5_000_000L
        val latch = Imx461ExposureLatch(EmulationClock { tick })

        // Steady state: long shutter (SHR2=244) with low ISO (PGC=2066, HCG=false)
        latch.observe(260, 0, 0, 2066, false, false, 0x92, 7, 244, 644)
        // Burst 1: firmware raises ISO (PGC=3300, HCG=true) BEFORE shortening SHR2 -> would be +3 EV white flash
        tick += 10_000L
        latch.observe(260, 0, 0, 3300, true, false, 0x92, 7, 244, 644)
        val midSplit = latch.resolveState(260, 0, 0, 3300, true, false, 0x92, 7, 244, 644)
        assertEquals(2066, midSplit.pgc12, "Split PGC/HCG update must not immediately expose bright transient")
        assertFalse(midSplit.hcgEnabled)

        // Burst 2: paired SHR2=600 arrives shortly after; previousReadout must remain clean pre-split state
        tick += 1_000L
        latch.observe(260, 0, 0, 3300, true, false, 0x92, 7, 600, 644)
        val duringShort = latch.resolveState(260, 0, 0, 3300, true, false, 0x92, 7, 600, 644)
        assertEquals(244, duringShort.movieShr15)
        assertEquals(2066, duringShort.pgc12, "Paired SHR2 must not freeze split bright PGC/HCG into previousReadout")
        assertFalse(duringShort.hcgEnabled)

        // After short integration finishes, paired (SHR2=600, PGC=3300, HCG=true) becomes visible cleanly
        tick += ((644L - 600L) * 54_000_000L + 38_649L) / 38_650L
        val settled = latch.resolveState(260, 0, 0, 3300, true, false, 0x92, 7, 600, 644)
        assertEquals(600, settled.movieShr15)
        assertEquals(3300, settled.pgc12)
        assertTrue(settled.hcgEnabled)

        // Pure gain-only change (manual ISO dial with no following SHR2) still settles within <= 1 frame
        tick += 1_000L
        latch.observe(260, 0, 0, 2470, false, false, 0x92, 7, 600, 644)
        assertEquals(3300, latch.resolveState(260, 0, 0, 2470, false, false, 0x92, 7, 600, 644).pgc12)
        tick += 1_000_000L
        assertEquals(2470, latch.resolveState(260, 0, 0, 2470, false, false, 0x92, 7, 600, 644).pgc12)
    }

    @Test
    fun computeEffectiveStillExposureDecodesMechanicalEfcsAndElectronicShuttersAcrossBitDepths() {
        fun portWith(timing: StillShutterHardwareTiming?) = object : Imx461ExposurePort {
            override val hasExposureRegs = true
            override val shr15 = timing?.shr15 ?: 0
            override val svr12 = timing?.svr12 ?: 0
            override val pgc12 = timing?.pgc12 ?: 2066
            override val pgcSecondary12 = pgc12
            override val hcgEnabled = timing?.hcgEnabled ?: false
            override val vmax12 = 0
            override val dgain8 = 0
            override val mdsel8 = timing?.mdsel8 ?: 0x40
            override val stillShutterTiming = timing
        }

        // 1. Mechanical Shutter (MS): 14-bit 0x40 at 1/640 s, 1/160 s, 1/40 s (72 MHz TGCLK ticks)
        val ms640 = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x40, shr15 = 2, svr12 = 0, pgc12 = 2066, hcgEnabled = false, frameLines = 8900, rearCurtainDelayTicks = 212_500L, frontCurtainDelayTicks = 100_000L)),
            ),
        )
        val ms160 = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x40, shr15 = 2, svr12 = 0, pgc12 = 2066, hcgEnabled = false, frameLines = 8900, rearCurtainDelayTicks = 550_000L, frontCurtainDelayTicks = 100_000L)),
            ),
        )
        val ms40 = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x40, shr15 = 2, svr12 = 0, pgc12 = 2066, hcgEnabled = false, frameLines = 8900, rearCurtainDelayTicks = 1_900_000L, frontCurtainDelayTicks = 100_000L)),
            ),
        )
        assertEquals(391L, ms640.durationNanos)
        assertEquals(1563L, ms160.durationNanos)
        assertEquals(6250L, ms40.durationNanos)
        assertEquals(100, ms640.iso)

        // MS 16-bit (0x00) multi-frame long exposure with HCG ISO boost
        val msLong = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x00, shr15 = 2, svr12 = 1, pgc12 = 2066, hcgEnabled = true, frameLines = 8900, rearCurtainDelayTicks = 500_000L, frontCurtainDelayTicks = 100_000L)),
            ),
        )
        assertTrue(msLong.durationNanos > 90_000L, "Multi-frame 16-bit MS exposure should include SVR*frameLines*hmax")
        assertEquals(300, msLong.iso)

        // 2. EFCS: Rear curtain only (bank 0) + electronic front curtain SHR=146 (~5.5 ms) + EEPROM -4.528 ms travel comp
        val frontTicks = 146L * 2L * 1352L // 394,784 ticks
        val efcs160Rear = frontTicks + 450_000L - Imx461ExposurePort.EFCS_REAR_CURTAIN_TRAVEL_TICKS
        val efcs160 = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x40, shr15 = 146, svr12 = 0, pgc12 = 2470, hcgEnabled = false, frameLines = 8900, rearCurtainDelayTicks = efcs160Rear, frontCurtainDelayTicks = null)),
            ),
        )
        assertEquals(1563L, efcs160.durationNanos)
        assertEquals(125, efcs160.iso)

        // 3. Electronic Shutter (ES): 12-bit 0x80 (hmax=930) at 1/160 s -> intLines=484 -> SHR=(8900-484)/2=4208
        val es160 = assertNotNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x80, shr15 = 4208, svr12 = 0, pgc12 = 2066, hcgEnabled = false, frameLines = 8900)),
            ),
        )
        assertEquals(1563L, es160.durationNanos)

        // Dummy MS constant SHR=2 with SVR=0 and no armed curtain banks must return null (fallback to LV latch)
        assertNull(
            Imx461ExposurePort.computeEffectiveStillExposure(
                portWith(StillShutterHardwareTiming(0x40, shr15 = 2, svr12 = 0, pgc12 = 2066, hcgEnabled = false, frameLines = 8900)),
            ),
        )
    }

}
