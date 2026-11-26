package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.CameraPose3D
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.components.filter.BlackMistFilterElement
import com.github.nyabanban.fuji.emulator.components.filter.StarFilterElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Scene3DHdrPolishAndFilterResponseTest {
    private val hdrPresets = listOf(
        OpticalScenePreset.BOKEH_CITY,
        OpticalScenePreset.DEPTH_STUDIO,
        OpticalScenePreset.QR_BALL,
        OpticalScenePreset.STAR_BOKEH,
    )

    private fun luminance(r: Float, g: Float, b: Float): Double =
        0.2126 * r + 0.7152 * g + 0.0722 * b

    @Test
    fun allFourHdr3dScenesHaveTrueHdrHighlightsBalancedExposureAndValidMetricDepth() {
        for (preset in hdrPresets) {
            ProceduralOpticsSceneSource(preset = preset, width = 640, height = 360, loop = true).use { src ->
                val frame = src.latestLinearRgbFrame()
                val depth = assertNotNull(frame.depthMeters, "${preset.uri} must expose depthMeters")
                val n = frame.width * frame.height

                var maxCh = 0f
                var hdrPixels = 0
                var sumLum = 0.0
                var sumSdrLum = 0.0
                var sdrCount = 0

                for (i in 0 until n) {
                    val r = frame.red[i]
                    val g = frame.green[i]
                    val b = frame.blue[i]
                    assertTrue(r.isFinite() && g.isFinite() && b.isFinite(), "${preset.uri} non-finite pixel at $i")
                    assertTrue(r >= 0f && g >= 0f && b >= 0f, "${preset.uri} negative pixel at $i")
                    assertTrue(depth[i].isFinite() && depth[i] >= 0.05f, "${preset.uri} invalid depth ${depth[i]}")

                    val peak = maxOf(r, g, b)
                    if (peak > maxCh) maxCh = peak
                    if (peak > 3.0f) {
                        hdrPixels++
                    } else {
                        sumSdrLum += luminance(r, g, b)
                        sdrCount++
                    }
                    sumLum += luminance(r.coerceAtMost(1.0f), g.coerceAtMost(1.0f), b.coerceAtMost(1.0f))
                }

                val meanClippedLum = sumLum / n
                val meanSdrLum = if (sdrCount > 0) sumSdrLum / sdrCount else 0.0

                assertTrue(
                    maxCh in 3.5f..65.0f,
                    "${preset.uri} must contain true HDR specular highlights in [3.5, 65.0], got maxCh=$maxCh",
                )
                assertTrue(
                    hdrPixels >= 60,
                    "${preset.uri} should have substantial HDR light emitters (>3.0f), got $hdrPixels px",
                )
                // Calibrated exposure band: neither murky/crushed dark (<0.035) nor blown out (>0.45)
                assertTrue(
                    meanClippedLum in 0.045..0.42,
                    "${preset.uri} mean clipped luminance should be balanced (0.045..0.42), got $meanClippedLum (sdrMean=$meanSdrLum)",
                )

                val distinctDepths = ProceduralOpticsSceneSource.distinctDepthPlanes(frame)
                when (preset) {
                    OpticalScenePreset.BOKEH_CITY, OpticalScenePreset.DEPTH_STUDIO -> {
                        assertEquals(
                            setOf(
                                ProceduralOpticsSceneSource.FOREGROUND_DEPTH_M,
                                ProceduralOpticsSceneSource.MIDGROUND_DEPTH_M,
                                ProceduralOpticsSceneSource.BACKGROUND_DEPTH_M,
                            ),
                            distinctDepths,
                            "${preset.uri} must preserve exact 3 metric depth planes",
                        )
                    }
                    OpticalScenePreset.QR_BALL -> {
                        assertTrue(distinctDepths.size > 32, "qr-ball must preserve continuous 3D ray-traced depth")
                        assertTrue(distinctDepths.min() in 0.75f..0.95f)
                        assertTrue(distinctDepths.max() >= 5.45f)
                    }
                    OpticalScenePreset.STAR_BOKEH -> {
                        assertTrue(distinctDepths.size > 16, "star-bokeh must preserve multi-depth fairy lights")
                        assertTrue(distinctDepths.min() in 1.25f..1.45f)
                        assertTrue(distinctDepths.max() >= 8.5f)
                        // Foreground focus card pixels (1.35 m) must remain calibrated below 0.75 linear
                        // so twilight Multi-AE does not clip the foreground medallion.
                        var maxFgLum = 0.0
                        for (i in 0 until n) {
                            if (depth[i] < 1.5f) {
                                maxFgLum = maxOf(maxFgLum, luminance(frame.red[i], frame.green[i], frame.blue[i]))
                            }
                        }
                        assertTrue(
                            maxFgLum in 0.50..0.75,
                            "star-bokeh foreground card peak luminance should stay calibrated in 0.50..0.75, got $maxFgLum",
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun starAndBlackMistFiltersProduceClearMeasurableResponseOnAllFourHdr3dScenes() {
        val starFilter = StarFilterElement(
            enabled = true,
            points = 6,
            strength = 1.0,
            threshold = 0.85,
            angleDeg = 15.0,
            length = 0.14,
        )
        val blackMist = BlackMistFilterElement(
            enabled = true,
            preset = "1/4",
            strength = 0.65,
            threshold = 0.45,
        )
        val scratch = OpticalFilterScratch()

        for (preset in hdrPresets) {
            ProceduralOpticsSceneSource(preset = preset, width = 320, height = 180, loop = true).use { src ->
                val base = src.latestLinearRgbFrame()
                val n = base.width * base.height

                // 1. Star Filter diffraction spike response strictly outside the HDR light cores
                val sr = base.red.copyOf()
                val sg = base.green.copyOf()
                val sb = base.blue.copyOf()
                starFilter.applySpatialLinearRgb(sr, sg, sb, base.width, base.height, scratch)

                var starSpikePixels = 0
                var starAddedEnergy = 0.0
                for (i in 0 until n) {
                    val basePeak = maxOf(base.red[i], base.green[i], base.blue[i])
                    if (basePeak > 1.2f) continue
                    val delta = (sr[i] - base.red[i]) + (sg[i] - base.green[i]) + (sb[i] - base.blue[i])
                    if (delta > 0.04f) {
                        starSpikePixels++
                        starAddedEnergy += delta
                    }
                }
                assertTrue(
                    starSpikePixels >= 80 && starAddedEnergy > 15.0,
                    "${preset.uri} must show clear StarFilter starburst spikes outside cores (pixels=$starSpikePixels, energy=$starAddedEnergy)",
                )

                // 2. Black Mist highlight halation bloom response around HDR emitters (beyond uniform shadow lift)
                val mr = base.red.copyOf()
                val mg = base.green.copyOf()
                val mb = base.blue.copyOf()
                blackMist.applySpatialLinearRgb(mr, mg, mb, base.width, base.height, scratch)

                val uniformLift3Ch = 3.0 * (0.65 * 0.008)
                var mistHaloPixels = 0
                var mistExcessBloom = 0.0
                for (i in 0 until n) {
                    val basePeak = maxOf(base.red[i], base.green[i], base.blue[i])
                    if (basePeak > 1.2f) continue
                    val delta = ((mr[i] - base.red[i]) + (mg[i] - base.green[i]) + (mb[i] - base.blue[i])) - uniformLift3Ch
                    if (delta > 0.06) {
                        mistHaloPixels++
                        mistExcessBloom += delta
                    }
                }
                assertTrue(
                    mistHaloPixels >= 150 && mistExcessBloom > 25.0,
                    "${preset.uri} must show clear BlackMist halation bloom around HDR lights (pixels=$mistHaloPixels, bloom=$mistExcessBloom)",
                )
            }
        }
    }

    @Test
    fun qrBallHdrPracticalLampsStayStrictlyOutsideQrPosterAndSphereAndRespondTo6DofPose() {
        val w = 640
        val h = 360
        ProceduralOpticsSceneSource(preset = OpticalScenePreset.QR_BALL, width = w, height = h, loop = true).use { src ->
            val identity = src.renderForPose(CameraPose3D.IDENTITY)
            val depth = assertNotNull(identity.depthMeters)

            // 1. HDR practical lamps exist on the 5.5 m back wall in the upper/side margins
            var wallLampPx = 0
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    val peak = maxOf(identity.red[i], identity.green[i], identity.blue[i])
                    if (peak > 3.0f) {
                        assertTrue(
                            depth[i] >= 4.0f,
                            "qr-ball HDR practical lamp at ($x,$y) must sit on distant wall/ceiling, got depth=${depth[i]}",
                        )
                        wallLampPx++
                    }
                }
            }
            assertTrue(wallLampPx in 80..6000, "Expected 6 well-placed HDR studio lamps in qr-ball, got $wallLampPx px")

            // 2. QR poster region must stay strictly SDR (<= 1.0) with crisp dark & bright modules
            val qrYStart = (h * 160) / 576
            val qrYEnd = (h * 380) / 576
            val qrXStart = (w * 570) / 1024
            val qrXEnd = (w * 810) / 1024
            for (y in qrYStart until qrYEnd) {
                val row = y * w
                for (x in qrXStart until qrXEnd) {
                    val i = row + x
                    assertTrue(
                        maxOf(identity.red[i], identity.green[i], identity.blue[i]) <= 1.0f,
                        "HDR lamp must never intrude on QR poster at ($x,$y)",
                    )
                }
            }

            // 3. Pitching up +18 deg reveals overhead ceiling HDR track spots too
            val pitchedUp = src.renderForPose(CameraPose3D(pitchDeg = 18.0))
            var pitchedHdrPx = 0
            for (i in 0 until w * h) {
                if (maxOf(pitchedUp.red[i], pitchedUp.green[i], pitchedUp.blue[i]) > 3.0f) {
                    pitchedHdrPx++
                }
            }
            assertTrue(
                pitchedHdrPx >= 40,
                "Expected overhead ceiling/upper-wall HDR studio spots visible when pitched +18 deg (got $pitchedHdrPx px)",
            )
        }
    }
}
