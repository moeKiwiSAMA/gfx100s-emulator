package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.TouchCoordinateMapper
import com.github.nyabanban.fuji.emulator.desktop.panels.TouchPointCoord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TouchCoordinateMapperTest {
    @Test
    fun mapsExactAspectViewportCornersAndCenter() {
        // 4:3 viewport matching 1024x768 content
        assertEquals(TouchPointCoord(0, 0), TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, 512.0, 384.0))
        assertEquals(TouchPointCoord(1023, 767), TouchCoordinateMapper.mapViewportToTouch(512.0, 384.0, 512.0, 384.0))
        assertEquals(TouchPointCoord(512, 384), TouchCoordinateMapper.mapViewportToTouch(256.0, 192.0, 512.0, 384.0))
    }

    @Test
    fun accountsForLetterboxingAndClampsOutOfBoundsCoordinates() {
        // Wide viewport 600x300 with 4:3 (1024x768) content -> drawWidth=400, drawHeight=300, offsetX=100
        assertEquals(TouchPointCoord(0, 0), TouchCoordinateMapper.mapViewportToTouch(50.0, -20.0, 600.0, 300.0))
        assertEquals(TouchPointCoord(0, 0), TouchCoordinateMapper.mapViewportToTouch(100.0, 0.0, 600.0, 300.0))
        assertEquals(TouchPointCoord(512, 384), TouchCoordinateMapper.mapViewportToTouch(300.0, 150.0, 600.0, 300.0))
        assertEquals(TouchPointCoord(1023, 767), TouchCoordinateMapper.mapViewportToTouch(500.0, 300.0, 600.0, 300.0))
        assertEquals(TouchPointCoord(1023, 767), TouchCoordinateMapper.mapViewportToTouch(590.0, 350.0, 600.0, 300.0))
    }

    @Test
    fun rejectsInvalidViewportOrTouchDimensions() {
        assertFailsWith<IllegalArgumentException> { TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, 0.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, 100.0, -1.0) }
        assertFailsWith<IllegalArgumentException> { TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, 100.0, 100.0, contentWidth = 0) }
        assertFailsWith<IllegalArgumentException> { TouchCoordinateMapper.mapViewportToTouch(0.0, 0.0, 100.0, 100.0, touchWidth = 0) }
    }
}
