package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpticalFilterFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun testContext(): Pair<ComponentContext, Ports> {
        val map = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
            override fun removeOwner(owner: String) = Unit
        }
        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock: EmulationClock = EmulationClock { 0L }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports: Ports = ports
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        return ctx to ports
    }

    @Test
    fun `nd presets and variable stops compute exact 2^-stops transmittance with zero spatial overhead`() {
        val (ctx, ports) = testContext()
        val comp = OpticalFilterFactory().create(ctx, emptyMap())
        val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))

        assertEquals(1.0, port.totalTransmittance(), 1e-9)
        assertFalse(port.hasActiveSpatialEffects())
        assertNull(port.activePupilMask())

        // Test every fixed preset ND2..ND1000
        for (preset in NdFilterPreset.entries) {
            val fixed = preset.fixedStops ?: continue
            comp.applySettings(mapOf("ndPreset" to preset.id))
            val expected = 2.0.pow(-fixed)
            assertEquals(expected, port.totalTransmittance(), 1e-6, "Preset ${preset.id}")
            assertFalse(port.hasActiveSpatialEffects(), "Uniform ND must have zero spatial pass")
            assertEquals(fixed, (comp.state()["ndStops"] as Number).toDouble(), 1e-6)
        }

        // Continuous Variable ND
        comp.applySettings(mapOf("ndStops" to 2.5))
        assertEquals(2.0.pow(-2.5), port.totalTransmittance(), 1e-6)
        assertEquals("VARIABLE", comp.state()["ndPreset"])

        // Master bypass overrides stack cleanly
        comp.applySettings(mapOf("enabled" to false))
        assertEquals(1.0, port.totalTransmittance(), 1e-9)
        assertEquals("BYPASSED", comp.state()["activeSummary"])
    }

    @Test
    fun `bokeh mask STAR_5 has exact 5-fold tip-valley geometry`() {
        val mask = BokehMaskFilterElement(BokehMaskShape.STAR_5, angleDeg = 0.0, scale = 0.96)
        assertTrue(mask.active)
        assertFalse(mask.hasSpatialEffect)

        // Probe radius r = 0.65 (well between inner valley 0.367 and outer tip 0.96)
        val probeR = 0.65
        for (k in 0 until 5) {
            val tipTheta = -PI * 0.5 + k * (2.0 * PI / 5.0)
            val tipW = mask.pupilWeight(probeR * cos(tipTheta), probeR * sin(tipTheta))
            assertTrue(tipW > 0.9, "Star tip #$k should be open (got $tipW)")

            val valleyTheta = tipTheta + PI / 5.0
            val valleyW = mask.pupilWeight(probeR * cos(valleyTheta), probeR * sin(valleyTheta))
            assertTrue(valleyW < 0.05, "Star valley #$k should be opaque (got $valleyW)")
        }
    }

    @Test
    fun `spatial filters star mist gnd execute in linear domain and stack cleanly with snapshot round-trip`() {
        val (ctx, ports) = testContext()
        val comp = OpticalFilterFactory().create(ctx, emptyMap()) as StatefulComponent
        val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))

        // Stack ND8 + STAR_5 bokeh + 6pt star + Black Mist 1/4 + GND 2 EV
        comp.applySettings(
            mapOf(
                "ndPreset" to "ND8",
                "bokehMask" to "STAR_5",
                "starEnabled" to true,
                "starPoints" to 6L,
                "starStrength" to 1.0,
                "mistEnabled" to true,
                "mistPreset" to "1/4",
                "gndEnabled" to true,
                "gndStops" to 2.0,
                "gndHorizon" to 0.5,
            ),
        )
        assertEquals(0.125, port.totalTransmittance(), 1e-6)
        assertTrue(port.hasActiveSpatialEffects())
        assertEquals(BokehMaskShape.STAR_5, port.activePupilMask()?.shape)

        val w = 64
        val h = 64
        val r = FloatArray(w * h) { 0.4f }
        val g = FloatArray(w * h) { 0.4f }
        val b = FloatArray(w * h) { 0.4f }
        // Place bright specular pinpoint in lower half
        val cx = 32
        val cy = 48
        r[cy * w + cx] = 4.0f
        g[cy * w + cx] = 4.0f
        b[cy * w + cx] = 4.0f

        val scratch = OpticalFilterScratch()
        port.applySpatialStack(r, g, b, w, h, scratch)

        // Top row (above GND horizon) must be significantly darker than bottom row (below GND horizon)
        val topLuma = r[2 * w + 10]
        val botLuma = r[60 * w + 10]
        assertTrue(topLuma < botLuma * 0.45f, "GND should attenuate upper frame: top=$topLuma bot=$botLuma")

        // Neighbor pixels around bright pinpoint should receive star/mist halo energy > base 0.4
        val nearHalo = r[cy * w + cx + 3]
        assertTrue(nearHalo > 0.42f, "Expected star/mist energy near highlight: got $nearHalo")

        // Snapshot save -> reset -> restore round-trip
        val snap = comp.saveSnapshot()
        comp.applySettings(mapOf("ndPreset" to "OFF", "bokehMask" to "OFF", "starEnabled" to false, "mistEnabled" to false, "gndEnabled" to false))
        assertEquals(1.0, port.totalTransmittance(), 1e-6)
        assertNull(port.activePupilMask())
        assertFalse(port.hasActiveSpatialEffects())

        comp.restoreSnapshot(snap)
        assertEquals(0.125, port.totalTransmittance(), 1e-6)
        assertEquals(BokehMaskShape.STAR_5, port.activePupilMask()?.shape)
        assertTrue(port.hasActiveSpatialEffects())
    }

    @Test
    fun bokehMaskFilterElementGeometryIsUprightAndCrispAcrossAllShapes() {
        // HEART: upright (top-center cleft < upper lobes at uy < 0; bottom tip > bottom flanks at uy > 0)
        val heart = BokehMaskFilterElement(shape = BokehMaskShape.HEART, angleDeg = 0.0, scale = 1.0)
        val cleft = heart.pupilWeight(0.0, -0.78)
        val leftLobe = heart.pupilWeight(-0.44, -0.68)
        val rightLobe = heart.pupilWeight(0.44, -0.68)
        val bottomTip = heart.pupilWeight(0.0, 0.74)
        val flankL = heart.pupilWeight(-0.38, 0.74)
        val flankR = heart.pupilWeight(0.38, 0.74)
        assertTrue(leftLobe > 0.9 && rightLobe > 0.9 && cleft < 0.2, "HEART top cleft must be dark below lobes (cleft=$cleft, L=$leftLobe, R=$rightLobe)")
        assertTrue(bottomTip > 0.7 && flankL < 0.1 && flankR < 0.1, "HEART bottom tip must be sharp (tip=$bottomTip, fL=$flankL, fR=$flankR)")

        // STAR_5: 5 sharp peaks vs 5 deep valleys (ratio >= 2.0x, tip #0 pointing UP at uy = -0.64)
        val star = BokehMaskFilterElement(shape = BokehMaskShape.STAR_5, angleDeg = 0.0, scale = 1.0)
        for (k in 0 until 5) {
            val tipA = -Math.PI * 0.5 + k * (2.0 * Math.PI / 5.0)
            val valA = tipA + Math.PI / 5.0
            val tipW = star.pupilWeight(0.64 * kotlin.math.cos(tipA), 0.64 * kotlin.math.sin(tipA))
            val valW = star.pupilWeight(0.64 * kotlin.math.cos(valA), 0.64 * kotlin.math.sin(valA))
            assertTrue(tipW > 0.9 && valW < 0.1 && tipW >= valW * 2.0 + 0.5, "STAR_5 peak #$k=$tipW vs valley=$valW")
        }

        // HEXAGON: 6 sharp corners at r=0.92 vs flat side exterior at r=0.92
        val hex = BokehMaskFilterElement(shape = BokehMaskShape.HEXAGON, angleDeg = 0.0, scale = 1.0)
        assertTrue(hex.pupilWeight(0.0, -0.92) > 0.85, "HEXAGON top corner at r=0.92 must be inside")
        assertTrue(hex.pupilWeight(0.92, 0.0) < 0.15, "HEXAGON right flat-edge exterior at r=0.92 must be outside")

        // RING: dark center (< 0.15) vs bright outer ring (> 0.9)
        val ring = BokehMaskFilterElement(shape = BokehMaskShape.RING, angleDeg = 0.0, scale = 1.0)
        assertTrue(ring.pupilWeight(0.0, 0.0) < 0.15, "RING center must be dark (<0.15)")
        assertTrue(ring.pupilWeight(0.75, 0.0) > 0.9, "RING outer annulus must be bright (>0.9)")
    }
}
