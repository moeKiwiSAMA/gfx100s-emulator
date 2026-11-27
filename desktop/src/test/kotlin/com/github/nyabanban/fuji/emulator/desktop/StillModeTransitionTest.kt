package com.github.nyabanban.fuji.emulator.desktop

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillModeTransitionTest {
    @Test fun onlyLeavingTheQualifiedMovieScreenCanShowFeedback() {
        val transition = StillModeTransition()
        for (origin in listOf(null, 0x0000, 0x0100, 0x0102, 0x0307, 0x0400)) {
            transition.leftMovie(origin)
            transition.observe(0x0000)
            assertFalse(transition.active, "Unexpected transition for $origin")
        }
        for (destination in listOf(0x0100, 0x0102, 0x0300, 0x0400, null)) {
            transition.leftMovie(0x0208)
            assertTrue(transition.active)
            transition.observe(0x0208)
            transition.observe(0x0000)
            assertTrue(transition.active)
            transition.observe(destination)
            assertFalse(transition.active)
            transition.observe(0x0000)
            assertFalse(transition.active)
        }
        transition.leftMovie(0x0208)
        transition.clear()
        transition.observe(0x0208)
        assertFalse(transition.active)
    }
}
