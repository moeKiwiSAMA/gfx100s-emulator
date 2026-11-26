package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog
import com.github.nyabanban.fuji.emulator.desktop.panels.ShoulderLcdRenderer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShoulderLcdRendererTest {
    @Test fun toneMapProducesAuthenticReflectiveUnlitAndCoolWhiteBacklitPalettes() {
        // Simulated DotLCD binary frame containing both WHITE-bg and BLACK-bg regions
        val raw = intArrayOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF808080.toInt(),
            0xFFFFFFFF.toInt(),
        )
        val unlit = IntArray(raw.size)
        val backlit = IntArray(raw.size)

        ShoulderLcdRenderer.toneMap(raw, unlit, backlightOn = false)
        assertEquals(ShoulderLcdRenderer.REFLECTIVE_LIGHT_ARGB, unlit[0])
        assertEquals(ShoulderLcdRenderer.REFLECTIVE_DARK_ARGB, unlit[1])
        assertEquals(ShoulderLcdRenderer.REFLECTIVE_LIGHT_ARGB, unlit[3])
        // Reflective unlit display must never emit pure self-luminous white
        assertTrue(unlit.none { it == 0xFFFFFFFF.toInt() }, "Unlit reflective shoulder LCD must not emit pure #FFFFFF white")
        // Intermediate gray must fall strictly between dark and light channels
        val midG = (unlit[2] ushr 8) and 0xFF
        val darkG = (ShoulderLcdRenderer.REFLECTIVE_DARK_ARGB ushr 8) and 0xFF
        val lightG = (ShoulderLcdRenderer.REFLECTIVE_LIGHT_ARGB ushr 8) and 0xFF
        assertTrue(midG in (darkG + 1) until lightG, "Expected intermediate green channel in ($darkG..$lightG), got $midG")

        // Unlit reflective palette must achieve high WCAG contrast (>= 10:1) for black-background readability
        fun relLuma(argb: Int): Double {
            fun ch(v: Int): Double {
                val s = (v and 0xFF) / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(argb ushr 16) + 0.7152 * ch(argb ushr 8) + 0.0722 * ch(argb)
        }
        val contrastRatio = (relLuma(ShoulderLcdRenderer.REFLECTIVE_LIGHT_ARGB) + 0.05) /
            (relLuma(ShoulderLcdRenderer.REFLECTIVE_DARK_ARGB) + 0.05)
        assertTrue(contrastRatio >= 10.0, "Expected crisp reflective MIP contrast >= 10:1, got $contrastRatio")

        ShoulderLcdRenderer.toneMap(raw, backlit, backlightOn = true)
        assertEquals(ShoulderLcdRenderer.BACKLIT_LIGHT_ARGB, backlit[0])
        assertEquals(ShoulderLcdRenderer.BACKLIT_DARK_ARGB, backlit[1])
        assertEquals(ShoulderLcdRenderer.BACKLIT_LIGHT_ARGB, backlit[3])
        // Backlit light pixel must be visibly brighter & cooler than reflective unlit light pixel
        val unlitLuma = (unlit[0] ushr 8) and 0xFF
        val backlitLuma = (backlit[0] ushr 8) and 0xFF
        assertTrue(backlitLuma > unlitLuma + 25, "Expected backlit luminance ($backlitLuma) > reflective ($unlitLuma) + 25")
    }

    @Test fun extractBacklightStateDefaultsOffAndTracksShoulderDisplayOrGpioBank8Pin9() {
        assertFalse(ShoulderLcdRenderer.extractBacklightState(emptyMap()))
        assertFalse(
            ShoulderLcdRenderer.extractBacklightState(
                mapOf("shoulder-display" to mapOf("state" to mapOf("backlightOn" to false))),
            ),
        )
        assertTrue(
            ShoulderLcdRenderer.extractBacklightState(
                mapOf("shoulder-display" to mapOf("state" to mapOf("backlightOn" to true))),
            ),
        )

        // Fallback directly from soc-gpio Bank 8 Pin 9 (0xFFF41200 bit 9 = 0x200)
        val regsOff = List(16) { listOf(0L, 0L) }
        assertFalse(
            ShoulderLcdRenderer.extractBacklightState(
                mapOf("soc-gpio" to mapOf("state" to mapOf("registers" to regsOff))),
            ),
        )
        val regsOtherPin = List(16) { bank -> if (bank == 8) listOf(0x0100L, 0L) else listOf(0L, 0L) }
        assertFalse(
            ShoulderLcdRenderer.extractBacklightState(
                mapOf("soc-gpio" to mapOf("state" to mapOf("registers" to regsOtherPin))),
            ),
        )
        val regsBank8Pin9High = List(16) { bank -> if (bank == 8) listOf(0x0200L, 0L) else listOf(0L, 0L) }
        assertTrue(
            ShoulderLcdRenderer.extractBacklightState(
                mapOf("soc-gpio" to mapOf("state" to mapOf("registers" to regsBank8Pin9High))),
            ),
        )
    }

    @Test fun rearSubBacklightCatalogControlWiresToSocAdcLightButtonMomentary() {
        val controls = PanelCatalog.load().flatMap { it.controls }.associateBy { it.id }
        val light = controls.getValue("rear-sub-backlight")
        assertEquals("rear", light.viewId)
        assertEquals("soc-adc", light.componentId)
        assertEquals("lightButton", light.settingKey)
        assertEquals("momentary", light.interaction)
        assertEquals("host-state-only", light.bindingStatus)
        assertEquals("UNKNOWN", light.firmwareBinding)
    }
}
