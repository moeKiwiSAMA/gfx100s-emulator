package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.panels.OrientationIbisState
import org.junit.jupiter.api.Test
import kotlin.test.*

class OrientationIbisPaneLogicTest {
    @Test
    fun orientationPresetsAndTelemetryParseI2cMma8452qAndBiscomIbisCleanly() {
        assertEquals(
            mapOf("accelEnabled" to true, "orientationPreset" to "portrait-cw", "rollDeg" to 90.0, "pitchDeg" to 0.0),
            OrientationIbisState.buildPresetPatch("portrait-cw"),
        )
        assertEquals(
            mapOf("accelEnabled" to true, "orientationPreset" to "portrait-ccw", "rollDeg" to -90.0, "pitchDeg" to 0.0),
            OrientationIbisState.buildPresetPatch("portrait-ccw"),
        )
        assertEquals(
            mapOf("accelEnabled" to true, "rollDeg" to 180.0, "pitchDeg" to -90.0, "shakeAmpDeg" to 5.0),
            OrientationIbisState.buildAttitudePatch(240.0, -120.0, 9.0),
        )

        val state = OrientationIbisState.fromSnapshot(
            mapOf(
                "components" to listOf(
                    mapOf(
                        "id" to "i2c-bus",
                        "config" to mapOf("accelEnabled" to true, "orientationPreset" to "portrait-cw"),
                        "state" to mapOf(
                            "accelEnabled" to true,
                            "orientationPreset" to "portrait-cw",
                            "rollDeg" to 90.0,
                            "pitchDeg" to 2.5,
                            "shakeAmpDeg" to 0.1,
                            "buses" to listOf(
                                mapOf(
                                    "index" to 1,
                                    "accel" to mapOf("active" to true, "rawX12" to 0, "rawY12" to -1024, "rawZ12" to 44),
                                ),
                            ),
                        ),
                    ),
                    mapOf(
                        "id" to "soc-spi",
                        "state" to mapOf(
                            "ibis" to mapOf(
                                "ibisModeName" to "CONTINUOUS",
                                "stageLocked" to false,
                                "stabilizationState" to 3,
                                "shiftXUm" to 3.76,
                                "shiftYUm" to -9.40,
                                "pixelShiftTargetX" to 0x8064,
                                "pixelShiftTargetY" to 0x7F06,
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(state.accelEnabled)
        assertTrue(state.bodyAccelActive)
        assertEquals(-1024, state.bodyRawY12)
        assertEquals("CONTINUOUS", state.ibisModeName)
        assertFalse(state.ibisStageLocked)

        val summary = state.telemetryLines().joinToString("\n")
        assertTrue(summary.contains("Portrait +90°"), summary)
        assertTrue(summary.contains("ACTIVE (I2C1 0x1C)"), summary)
        assertTrue(summary.contains("CONTINUOUS"), summary)
        assertTrue(summary.contains("0x8064"), summary)
    }

    @Test
    fun standaloneMma8452qComponentTakesPrecedenceOverLegacyI2cBus() {
        val state = OrientationIbisState.fromSnapshot(
            mapOf(
                "components" to listOf(
                    mapOf(
                        "id" to "i2c-bus",
                        "state" to mapOf("accelEnabled" to false, "orientationPreset" to "landscape", "rollDeg" to 0.0),
                    ),
                    mapOf(
                        "id" to "mma8452q",
                        "state" to mapOf(
                            "enabled" to true,
                            "accelEnabled" to true,
                            "orientationPreset" to "portrait-ccw",
                            "rollDeg" to -90.0,
                            "pitchDeg" to 15.0,
                            "bodyAccel" to mapOf("active" to true, "rawX12" to -989, "rawY12" to 0, "rawZ12" to -265),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(state.accelEnabled)
        assertTrue(state.bodyAccelActive)
        assertEquals("portrait-ccw", state.orientationPreset)
        assertEquals(-90.0, state.rollDeg)
        assertEquals(-989, state.bodyRawX12)
    }
}
