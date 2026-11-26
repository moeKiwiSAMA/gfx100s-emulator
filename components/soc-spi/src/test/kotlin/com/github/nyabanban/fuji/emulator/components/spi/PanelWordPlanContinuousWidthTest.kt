package com.github.nyabanban.fuji.emulator.components.spi

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies EVF micro-OLED SPI variable word widths (e.g., 8-bit/24-bit commands)
 * in continuous fixed-MISO suffix mode without relaxing scripted prefix validation.
 */
class PanelWordPlanContinuousWidthTest {
    private fun rawPlan(fixedMisoHigh: Boolean) = mapOf(
        "prefix" to listOf(mapOf("tx" to 0x1234, "rx" to 0x5678)),
        "fixedMisoHigh" to fixedMisoHigh,
        "exchangeLimit" to 4,
    )

    @Test
    fun continuousFixedMisoAcceptsVariableSpiWordWidthsAfterScriptedPrefix() {
        val highPlan = PanelWordPlan(rawPlan(fixedMisoHigh = true), bitsPerWord = 16, continuous = true)
        assertEquals(0x5678, highPlan.exchange(0, 16, 0x1234))
        assertEquals(0xff, highPlan.exchange(1, 8, 0xaa))
        assertEquals(0xffff, highPlan.exchange(2, 16, 0xbeef))
        assertEquals(0x00ff_ffff, highPlan.exchange(10, 24, 0x12_3456))
        assertEquals(-1, highPlan.exchange(11, 32, 0x1234_5678))

        val lowPlan = PanelWordPlan(rawPlan(fixedMisoHigh = false), bitsPerWord = 16, continuous = true)
        assertEquals(0x5678, lowPlan.exchange(0, 16, 0x1234))
        assertEquals(0, lowPlan.exchange(1, 8, 0x55))
        assertEquals(0, lowPlan.exchange(5, 24, 0xab_cdef))

        // Prefix still requires exact 16-bit width & word match
        assertFailsWith<IllegalArgumentException> { highPlan.exchange(0, 8, 0x34) }
        // Out-of-range tx for the declared bit width is rejected
        assertFailsWith<IllegalArgumentException> { highPlan.exchange(2, 8, 0x1ff) }
        assertFailsWith<IllegalArgumentException> { highPlan.exchange(2, 0, 0) }
    }

    @Test
    fun nonContinuousFixedMisoStillEnforcesConfiguredBitWidth() {
        val strictPlan = PanelWordPlan(rawPlan(fixedMisoHigh = true), bitsPerWord = 16, continuous = false)
        assertEquals(0x5678, strictPlan.exchange(0, 16, 0x1234))
        assertEquals(0xffff, strictPlan.exchange(1, 16, 0xabcd))
        assertFailsWith<IllegalArgumentException> { strictPlan.exchange(2, 8, 0xaa) }
    }

    @Test
    fun sharedBusContinuousFixedMisoMultiplexesEvf8BitFromExchangeZeroAndRearLcdIdRead() {
        val spi5Raw = mapOf(
            "prefix" to listOf(
                mapOf("tx" to 0x1b40, "rx" to 0xa55a),
                mapOf("tx" to 0x0000, "rx" to 0x5a5a),
            ),
            "fixedMisoHigh" to false,
            "exchangeLimit" to 2048,
        )
        // Case 1 (BUG-V3-9-1): Cold-reset wake in persisted EVF-only mode sends 8-bit Sony ECX33x
        // OLED command (0x80) at exchange 0, then later Rear LCD probes ID (0x1B40, 0x0000).
        val evfFirstPlan = PanelWordPlan(spi5Raw, bitsPerWord = 16, continuous = true, sharedBus = true)
        assertEquals(0, evfFirstPlan.exchange(0, 8, 0x80))
        assertEquals(0, evfFirstPlan.exchange(1, 8, 0xc8))
        assertEquals(0xa55a, evfFirstPlan.exchange(2, 16, 0x1b40))
        assertEquals(0x5a5a, evfFirstPlan.exchange(3, 16, 0x0000))
        assertEquals(0, evfFirstPlan.exchange(4, 16, 0x0880))

        // Case 2: Normal / FWUP boot probes Rear LCD ID at exchange 0, 1 and re-probes after re-init.
        val lcdFirstPlan = PanelWordPlan(spi5Raw, bitsPerWord = 16, continuous = true, sharedBus = true)
        assertEquals(0xa55a, lcdFirstPlan.exchange(0, 16, 0x1b40))
        assertEquals(0x5a5a, lcdFirstPlan.exchange(1, 16, 0x0000))
        assertEquals(0, lcdFirstPlan.exchange(2, 16, 0x01c0))
        assertEquals(0xa55a, lcdFirstPlan.exchange(96, 16, 0x1b40))
        assertEquals(0x5a5a, lcdFirstPlan.exchange(97, 16, 0x0000))

        // Invalid bit widths / out-of-range TX words are still rejected.
        assertFailsWith<IllegalArgumentException> { evfFirstPlan.exchange(5, 8, 0x1ff) }
        assertFailsWith<IllegalArgumentException> { evfFirstPlan.exchange(5, 0, 0) }
    }
}
