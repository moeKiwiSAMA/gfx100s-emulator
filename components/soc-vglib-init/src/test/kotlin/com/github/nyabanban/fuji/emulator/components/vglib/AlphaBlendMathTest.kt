package com.github.nyabanban.fuji.emulator.components.vglib

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AlphaBlendMathTest {
    @Test fun div255RoundedMatchesExactDivisionForEntire8BitProductRange() {
        for (x in 0..65025) {
            assertEquals(
                (x + 127) / 255,
                div255Rounded(x),
                "Mismatch at x=$x",
            )
        }
    }

    @Test fun div255RoundedMatchesCompositeBlendEndpoints() {
        val sampleValues = intArrayOf(0, 1, 2, 127, 128, 254, 255)
        for (src in sampleValues) {
            for (dst in sampleValues) {
                for (alpha in 0..255) {
                    val inv = 255 - alpha
                    val num = src * alpha + dst * inv
                    assertEquals((num + 127) / 255, div255Rounded(num))
                    val coverage = alpha * 255 + dst * inv
                    assertEquals((coverage + 127) / 255, div255Rounded(coverage))
                }
            }
        }
    }
}
