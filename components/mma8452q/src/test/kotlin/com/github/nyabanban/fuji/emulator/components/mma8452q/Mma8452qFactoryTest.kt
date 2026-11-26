package com.github.nyabanban.fuji.emulator.components.mma8452q

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Mma8452qFactoryTest {
    private class Harness : ComponentContext {
        override val directory: Path = Path.of("unused-mma8452q-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        val registered = linkedMapOf<String, Any>()
        val bus1Slaves = linkedMapOf<Int, I2cSlaveDevice>()
        val bus2Slaves = linkedMapOf<Int, I2cSlaveDevice>()

        private fun bus(map: MutableMap<Int, I2cSlaveDevice>) = object : I2cBusPort {
            override fun connect(owner: String): I2cConnection = error("unused")
            override fun registerSlave(device: I2cSlaveDevice) { map[device.address7] = device }
            override fun unregisterSlave(address7: Int, device: I2cSlaveDevice?) { map.remove(address7) }
        }

        init {
            registered["i2c-bus.bus1"] = bus(bus1Slaves)
            registered["i2c-bus.bus2"] = bus(bus2Slaves)
        }

        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                registered["$name:${type.name}"] = port
                registered[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? =
                (registered["$name:${type.name}"] ?: registered[name])?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun readBurst(dev: I2cSlaveDevice, reg: Int, count: Int): IntArray {
        assertTrue(dev.address((dev.address7 shl 1) or 0))
        dev.write(reg)
        assertTrue(dev.address((dev.address7 shl 1) or 1))
        val out = IntArray(count)
        for (i in 0 until count) {
            val b = dev.peekRead()
            dev.consumeRead(b)
            out[i] = b
        }
        dev.stop()
        return out
    }

    private fun writeReg(dev: I2cSlaveDevice, reg: Int, value: Int) {
        assertTrue(dev.address((dev.address7 shl 1) or 0))
        dev.write(reg)
        dev.write(value)
        dev.stop()
    }

    @Test
    fun attachesDualSlavesOnBus1AndBus2AndTracksPortraitOrientationAndSnapshots() {
        val h = Harness()
        val factory = Mma8452qFactory()
        val comp = factory.create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()

        val body = assertNotNull(h.bus1Slaves[0x1C])
        val lcd = assertNotNull(h.bus2Slaves[0x1D])
        assertEquals(0x2A, readBurst(body, Mma8452qChip.REG_WHO_AM_I, 1)[0])
        assertEquals(0x2A, readBurst(lcd, Mma8452qChip.REG_WHO_AM_I, 1)[0])

        // Put body accel in ACTIVE mode (±2g) and verify Landscape (0,-1g,0) -> Portrait CW (+1g,0,0)
        writeReg(body, Mma8452qChip.REG_CTRL_REG1, 0x65)
        val land = readBurst(body, Mma8452qChip.REG_OUT_X_MSB, 6)
        assertEquals(0, Mma8452qChip.unpackLeftJustified12(land[0], land[1]))
        assertEquals(-1024, Mma8452qChip.unpackLeftJustified12(land[2], land[3]))

        comp.applySettings(mapOf("orientationPreset" to "portrait-cw"))
        val port = readBurst(body, Mma8452qChip.REG_OUT_X_MSB, 6)
        assertEquals(1024, Mma8452qChip.unpackLeftJustified12(port[0], port[1]))
        assertEquals(0, Mma8452qChip.unpackLeftJustified12(port[2], port[3]))

        val orientPort = assertNotNull(h.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java))
        assertEquals(90.0, orientPort.rollDeg)

        val snap = comp.saveSnapshot()
        val h2 = Harness()
        val comp2 = factory.create(h2, mapOf("enabled" to true)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())

        comp.close()
        comp2.close()
        assertTrue(h.bus1Slaves.isEmpty())
        assertTrue(h.bus2Slaves.isEmpty())
    }

    @Test
    fun lcdMountingMatrixMatchesBodyCameraFrameAtZeroTiltAndDivergesOnTilt() {
        val h = Harness()
        val factory = Mma8452qFactory()
        val comp = factory.create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()

        val body = assertNotNull(h.bus1Slaves[0x1C])
        val lcd = assertNotNull(h.bus2Slaves[0x1D])
        writeReg(body, Mma8452qChip.REG_CTRL_REG1, 0x65)
        writeReg(lcd, Mma8452qChip.REG_CTRL_REG1, 0x65)

        fun readXyz12(dev: I2cSlaveDevice): Triple<Int, Int, Int> {
            val b = readBurst(dev, Mma8452qChip.REG_OUT_X_MSB, 6)
            return Triple(
                Mma8452qChip.unpackLeftJustified12(b[0], b[1]),
                Mma8452qChip.unpackLeftJustified12(b[2], b[3]),
                Mma8452qChip.unpackLeftJustified12(b[4], b[5]),
            )
        }

        // Apply exact GXUP0004.DAT bank14 mounting matrices:
        // Body (bank14[0x8a2b..0x8a33]): out = (-ry, -rx, +rz)
        // LCD  (bank14[0x89d6..0x89de]): out = (-rx, +ry, -rz)
        fun bodyCameraFrame(raw: Triple<Int, Int, Int>) = Triple(-raw.second, -raw.first, raw.third)
        fun lcdCameraFrame(raw: Triple<Int, Int, Int>) = Triple(-raw.first, raw.second, -raw.third)

        // At lcdTiltDeg=0°, body and LCD must map to identical camera-frame gravity vectors across all attitudes
        for ((roll, pitch) in listOf(0.0 to 0.0, 90.0 to 0.0, -90.0 to 0.0, 180.0 to 0.0, 0.0 to 25.0, 35.0 to -20.0)) {
            comp.applySettings(mapOf("rollDeg" to roll, "pitchDeg" to pitch, "lcdTiltDeg" to 0.0))
            val bCam = bodyCameraFrame(readXyz12(body))
            val lCam = lcdCameraFrame(readXyz12(lcd))
            assertEquals(bCam, lCam, "Body and LCD camera-frame vectors must match at lcdTiltDeg=0° for roll=$roll pitch=$pitch")
        }

        // Verify exact raw LCD samples at landscape 0° and 45° tilt
        comp.applySettings(mapOf("rollDeg" to 0.0, "pitchDeg" to 0.0, "lcdTiltDeg" to 0.0))
        assertEquals(Triple(-1024, 0, 0), readXyz12(lcd))

        comp.applySettings(mapOf("rollDeg" to 0.0, "pitchDeg" to 0.0, "lcdTiltDeg" to 45.0))
        val (lx, ly, lz) = readXyz12(lcd)
        assertTrue(kotlin.math.abs(lx + 724) <= 2 && ly == 0 && kotlin.math.abs(lz - 724) <= 2,
            "Expected 45° tilted LCD raw (~-724, 0, ~+724), got ($lx, $ly, $lz)")

        comp.close()
    }
}
