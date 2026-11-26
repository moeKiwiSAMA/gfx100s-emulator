package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.ActiveViewfinder
import com.github.nyabanban.fuji.emulator.desktop.panels.ViewfinderStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvfViewfinderControllerTest {
    @Test
    fun derivePrioritizesLiveLcdcOutputActiveThenFrameSequencesAndEyeSensor() {
        val waiting = ViewfinderStatus.derive(
            eyeSensorNear = false,
            lcdSequence = 0L,
            evfSequence = 0L,
            lcdOutputActive = null,
            evfOutputActive = null,
            lastUpdatedPort = null,
        )
        assertEquals(ActiveViewfinder.WAITING, waiting.active)
        assertEquals("Waiting for firmware frames", waiting.summaryText())

        val waitingNear = ViewfinderStatus.derive(
            eyeSensorNear = true,
            lcdSequence = 0L,
            evfSequence = 0L,
            lcdOutputActive = false,
            evfOutputActive = false,
            lastUpdatedPort = null,
        )
        assertEquals(ActiveViewfinder.WAITING, waitingNear.active)
        assertTrue(waitingNear.summaryText().contains("Eye Sensor Near"))

        val lcdOnly = ViewfinderStatus.derive(
            eyeSensorNear = false,
            lcdSequence = 4L,
            evfSequence = 2L,
            lcdOutputActive = true,
            evfOutputActive = false,
            lastUpdatedPort = "display.evf",
        )
        assertEquals(ActiveViewfinder.LCD, lcdOnly.active)
        assertEquals("LCD (LCDC1)", lcdOnly.summaryText())

        val evfOnly = ViewfinderStatus.derive(
            eyeSensorNear = true,
            lcdSequence = 5L,
            evfSequence = 3L,
            lcdOutputActive = false,
            evfOutputActive = true,
            lastUpdatedPort = "display.main",
        )
        assertEquals(ActiveViewfinder.EVF, evfOnly.active)
        assertEquals("EVF (LCDC0) · Eye Sensor Near", evfOnly.summaryText())

        val bothFar = ViewfinderStatus.derive(
            eyeSensorNear = false,
            lcdSequence = 5L,
            evfSequence = 3L,
            lcdOutputActive = true,
            evfOutputActive = true,
            lastUpdatedPort = "display.main",
        )
        assertEquals(ActiveViewfinder.BOTH, bothFar.active)

        val bothNearPrefersEvf = ViewfinderStatus.derive(
            eyeSensorNear = true,
            lcdSequence = 5L,
            evfSequence = 3L,
            lcdOutputActive = true,
            evfOutputActive = true,
            lastUpdatedPort = "display.main",
        )
        assertEquals(ActiveViewfinder.EVF, bothNearPrefersEvf.active)

        val fallbackLastUpdatedEvf = ViewfinderStatus.derive(
            eyeSensorNear = false,
            lcdSequence = 2L,
            evfSequence = 2L,
            lcdOutputActive = null,
            evfOutputActive = null,
            lastUpdatedPort = "display.evf",
        )
        assertEquals(ActiveViewfinder.EVF, fallbackLastUpdatedEvf.active)

        val map = evfOnly.toMap()
        assertEquals("EVF", map["active"])
        assertEquals(true, map["eyeSensorNear"])
        assertEquals(5L, map["lcdSequence"])
        assertEquals(3L, map["evfSequence"])
        assertEquals(false, map["lcdOutputActive"])
        assertEquals(true, map["evfOutputActive"])
    }
}
