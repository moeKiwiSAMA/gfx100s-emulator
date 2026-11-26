package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

class Mma8452qAccelerometerTest {
    private class Harness(val tickProvider: () -> Long = { 0L }) {
        val registeredPorts = hashMapOf<String, Any>()
        private val releaseBusData = mapOf(
            "buses" to listOf(
                mapOf("index" to 0, "kind" to "empty"),
                mapOf("index" to 1, "kind" to LiveEyeSensor.KIND, "address" to LiveEyeSensor.ADDRESS7),
                mapOf("index" to 2, "kind" to VirtualTouchPanel.KIND, "address" to VirtualTouchPanel.ADDRESS7),
            ),
        )
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-mma8452q-test")
            override val clock = EmulationClock { tickProvider() }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    registeredPorts[name] = port
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
                override fun removeOwner(owner: String) {}
            }
            override fun dataPath(relative: String): Path = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = releaseBusData
        }
    }

    private fun writeReg(conn: I2cConnection, addr7: Int, reg: Int, value: Int) {
        assertTrue(conn.address(addr7 shl 1), "Expected ACK on write address 0x${addr7.toString(16)}")
        conn.write(reg)
        conn.write(value)
        conn.stop()
    }

    private fun readRegs(conn: I2cConnection, addr7: Int, startReg: Int, count: Int): IntArray {
        assertTrue(conn.address(addr7 shl 1), "Expected ACK on reg-select write 0x${addr7.toString(16)}")
        conn.write(startReg)
        assertTrue(conn.address((addr7 shl 1) or 1), "Expected ACK on repeated-start read 0x${addr7.toString(16)}")
        return IntArray(count) {
            val b = conn.peekRead()
            conn.consumeRead(b)
            b
        }.also { conn.stop() }
    }

    @Test
    fun mma8452qDatasheetRegistersInitSequenceScaleTrimAndMultiDeviceCoexistenceWork() {
        val h = Harness()
        val factory = I2cBusFactory()
        val comp = factory.create(
            h.context,
            mapOf(
                "profile" to "explicit-i2c-buses-experimental-v1",
                "inputFile" to "input.json",
                "accelEnabled" to true,
                "touchEnabled" to true,
            ),
        ) as StatefulComponent
        val bus1 = (h.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort).connect("soc-i2c.controller1")
        val bus2 = (h.registeredPorts.getValue("i2c-bus.bus2") as I2cBusPort).connect("soc-i2c.controller2")

        // 1) WHO_AM_I == 0x2A on BOTH I2C1 @ 0x1C (body) and I2C2 @ 0x1D (tilting LCD)
        assertEquals(0x2A, readRegs(bus1, 0x1C, 0x0D, 1)[0])
        assertEquals(0x2A, readRegs(bus2, 0x1D, 0x0D, 1)[0])

        // 2) Coexistence: Bus 1 still serves Eye Sensor @ 0x39 AND Bus 2 still serves Touch Panel @ 0x55
        assertEquals(0x39, readRegs(bus1, 0x39, 0xB2, 1)[0])
        assertTrue(bus2.address(0x55 shl 1))
        bus2.stop()
        assertFalse(bus1.address(0x1D shl 1)) // 0x1D lives on Bus 2, NOT Bus 1

        // 3) Standby returns STATUS=0x00 and zeroed output samples
        assertEquals(0x00, readRegs(bus1, 0x1C, 0x00, 1)[0])
        val standbyRaw = readRegs(bus1, 0x1C, 0x01, 6)
        assertTrue(standbyRaw.all { it == 0 })

        // 4) Run exact GFX100S DAT init sequence (0x0E=0x00 ±2g, 0x11=0x80, 0x2A=0x65 ACTIVE 50Hz LNOISE)
        writeReg(bus1, 0x1C, 0x0E, 0x00)
        writeReg(bus1, 0x1C, 0x11, 0x80)
        writeReg(bus1, 0x1C, 0x2A, 0x65)
        assertEquals(0xFF, readRegs(bus1, 0x1C, 0x00, 1)[0])
        assertEquals(0x01, readRegs(bus1, 0x1C, 0x0B, 1)[0]) // SYSMOD = WAKE

        fun readXyz12(conn: I2cConnection, addr7: Int): Triple<Int, Int, Int> {
            val b = readRegs(conn, addr7, 0x01, 6)
            return Triple(
                Mma8452qDevice.unpackLeftJustified12(b[0], b[1]),
                Mma8452qDevice.unpackLeftJustified12(b[2], b[3]),
                Mma8452qDevice.unpackLeftJustified12(b[4], b[5]),
            )
        }

        // 5) Verify all 4 GFX100S PCB-mounted orientation presets (±2g -> 1024 LSB/g, inverse of bank14[0x8a2b..0x8a33])
        assertEquals(Triple(0, -1024, 0), readXyz12(bus1, 0x1C)) // landscape: roll=0 -> sensor (0, -1g, 0) -> filt (+1g, 0, 0)

        comp.applySettings(mapOf("orientationPreset" to "portrait-cw"))
        assertEquals(Triple(1024, 0, 0), readXyz12(bus1, 0x1C)) // portrait-cw: roll=+90 -> sensor (+1g, 0, 0) -> filt (0, -1g, 0)

        comp.applySettings(mapOf("orientationPreset" to "inverted"))
        assertEquals(Triple(0, 1024, 0), readXyz12(bus1, 0x1C)) // inverted: roll=180 -> sensor (0, +1g, 0) -> filt (-1g, 0, 0)

        comp.applySettings(mapOf("orientationPreset" to "portrait-ccw"))
        assertEquals(Triple(-1024, 0, 0), readXyz12(bus1, 0x1C)) // portrait-ccw: roll=-90 -> sensor (-1g, 0, 0) -> filt (0, +1g, 0)

        // 6) Pitch +30° -> sz = -0.5g (-512 LSB, live GXUP0004.DAT levelsens +30.0°), sy = -0.866g (-887 LSB)
        comp.applySettings(mapOf("rollDeg" to 0.0, "pitchDeg" to 30.0))
        val (px, py, pz) = readXyz12(bus1, 0x1C)
        assertEquals(0, px)
        assertTrue(abs(py + 887) <= 2, "Expected sy ~-887, got $py")
        assertTrue(abs(pz + 512) <= 2, "Expected sz ~-512, got $pz")

        // 7) ±4g full-scale mode halves LSB/g (512 LSB/g) and OFF_Z trim adds 2 mg/LSB
        comp.applySettings(mapOf("orientationPreset" to "landscape"))
        writeReg(bus1, 0x1C, 0x0E, 0x01) // ±4g
        assertEquals(Triple(0, -512, 0), readXyz12(bus1, 0x1C))
        writeReg(bus1, 0x1C, 0x31, 50) // OFF_Z = +50 * 2mg = +0.10g -> +51 LSB at 512 LSB/g
        val (_, _, trimmedZ) = readXyz12(bus1, 0x1C)
        assertTrue(abs(trimmedZ - 51) <= 1, "Expected trimmedZ ~51, got $trimmedZ")

        // 8) CTRL_REG2.RST resets registers cleanly
        writeReg(bus1, 0x1C, 0x2B, 0x40)
        assertEquals(0x00, readRegs(bus1, 0x1C, 0x00, 1)[0]) // back in STANDBY
        assertEquals(0x2A, readRegs(bus1, 0x1C, 0x0D, 1)[0])

        // 9) Secondary tilting-LCD accel on Bus 2 uses bank14[0x89d6..0x89de] M_lcd = [[-1,0,0],[0,1,0],[0,0,-1]]
        writeReg(bus2, 0x1D, 0x2A, 0x65)
        comp.applySettings(mapOf("rollDeg" to 0.0, "pitchDeg" to 0.0, "lcdTiltDeg" to 0.0))
        assertEquals(Triple(-1024, 0, 0), readXyz12(bus2, 0x1D))
        comp.applySettings(mapOf("rollDeg" to 0.0, "pitchDeg" to 0.0, "lcdTiltDeg" to 45.0))
        val (lx, ly, lz) = readXyz12(bus2, 0x1D)
        assertTrue(abs(lx + 724) <= 2 && ly == 0 && abs(lz - 724) <= 2, "Expected 45° LCD tilt (~-724, 0, ~+724), got ($lx, $ly, $lz)")

        // 10) Snapshot round-trip preserves MMA8452Q state and orientation
        bus1.close()
        bus2.close()
        val snap = comp.saveSnapshot()
        val h2 = Harness()
        val comp2 = factory.create(
            h2.context,
            mapOf(
                "profile" to "explicit-i2c-buses-experimental-v1",
                "inputFile" to "input.json",
                "accelEnabled" to true,
                "touchEnabled" to true,
            ),
        ) as StatefulComponent
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())

        comp.close()
        comp2.close()
    }
}
