package com.github.nyabanban.fuji.emulator.components.tmd2772

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Tmd2772FactoryTest {
    private class Harness : ComponentContext {
        override val directory: Path = Path.of("unused-tmd2772-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        val registered = linkedMapOf<String, Any>()
        var registeredSlave: I2cSlaveDevice? = null
        val gpioPinHistory = mutableListOf<Triple<Int, Int, Boolean>>()

        init {
            registered["i2c-bus.bus1"] = object : I2cBusPort {
                override fun connect(owner: String): I2cConnection = error("unused")
                override fun registerSlave(device: I2cSlaveDevice) {
                    registeredSlave = device
                }
                override fun unregisterSlave(address7: Int, device: I2cSlaveDevice?) {
                    if (registeredSlave === device) registeredSlave = null
                }
            }
            registered["soc-gpio.inputs"] = GpioInputPort { bank, pin, high ->
                gpioPinHistory += Triple(bank, pin, high)
            }
        }

        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                registered[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun readReg(dev: I2cSlaveDevice, cmd: Int, count: Int): IntArray {
        assertTrue(dev.address((0x39 shl 1) or 0))
        dev.write(cmd)
        assertTrue(dev.address((0x39 shl 1) or 1))
        val out = IntArray(count)
        for (i in 0 until count) {
            val b = dev.peekRead()
            dev.consumeRead(b)
            out[i] = b
        }
        dev.stop()
        return out
    }

    private fun writeReg(dev: I2cSlaveDevice, cmd: Int, vararg data: Int) {
        assertTrue(dev.address((0x39 shl 1) or 0))
        dev.write(cmd)
        for (b in data) dev.write(b)
        dev.stop()
    }

    @Test
    fun coldBootInitHysteresisThresholdsAndActiveLowGpioIntMatchDatProtocol() {
        val h = Harness()
        val factory = Tmd2772Factory()
        val comp = factory.create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()

        val slave = assertNotNull(h.registeredSlave)
        assertEquals(0x39, slave.address7)
        // On boot, pendingUpdate=true pulls active-LOW GPIO(0, 8) LOW (false)
        assertEquals(Triple(0, 8, false), h.gpioPinHistory.last())

        // Firmware reads 0xB2 (ID) -> 0x39
        assertEquals(0x39, readReg(slave, 0xB2, 1)[0])

        // Firmware cold-boot init: 0xA8 (PILT=0x01B0, PIHT=0x0296, PERS=0x1F, CONFIG=0x00, PPULSE=0x0C, CONTROL=0x27)
        writeReg(slave, 0xA8, 0xB0, 0x01, 0x96, 0x02, 0x1F, 0x00, 0x0C, 0x27)
        writeReg(slave, 0xA0, 0x2F) // ENABLE = PIEN|WEN|PEN|AEN|PON

        // FAR state (PDATA=0x0000): firmware writes FAR window [0x0000, 0x0296] and clears INT via 0xE5
        val farData = readReg(slave, 0xB8, 2)
        assertEquals(0, farData[0] or (farData[1] shl 8))
        writeReg(slave, 0xA8, 0x00, 0x00, 0x96, 0x02)
        writeReg(slave, 0xE5)
        // INT de-asserted -> GPIO(0, 8) released HIGH (true), PINT cleared
        assertEquals(Triple(0, 8, true), h.gpioPinHistory.last())
        assertEquals(0x03, readReg(slave, 0xB3, 1)[0]) // PVALID|AVALID, PINT=0

        // Eye moves NEAR -> PDATA=0x03FF > 0x0296 -> PINT=1 & GPIO(0, 8) pulled LOW (false)
        val eyePort = assertNotNull(h.ports.find(EyeSensorPort.PORT_NAME, EyeSensorPort::class.java))
        eyePort.setNear(true)
        assertEquals(Triple(0, 8, false), h.gpioPinHistory.last())
        assertTrue((readReg(slave, 0xB3, 1)[0] and 0x20) != 0)
        val nearData = readReg(slave, 0xB8, 2)
        assertEquals(0x03FF, nearData[0] or (nearData[1] shl 8))

        // Firmware updates window to NEAR [0x01B0, 0xFFFF] & clears via 0xE5 -> GPIO(0, 8) released HIGH
        writeReg(slave, 0xA8, 0xB0, 0x01, 0xFF, 0xFF)
        writeReg(slave, 0xE5)
        assertEquals(Triple(0, 8, true), h.gpioPinHistory.last())

        // Snapshot round-trip
        val snap = comp.saveSnapshot()
        val h2 = Harness()
        val comp2 = factory.create(h2, mapOf("enabled" to true)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())
        comp.close()
        comp2.close()
        assertNull(h.registeredSlave)
    }
}
