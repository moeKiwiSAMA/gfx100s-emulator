package com.github.nyabanban.fuji.emulator.components.wm8962

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Wm8962FactoryTest {
    private class Harness : ComponentContext {
        override val directory: Path = Path.of("unused-wm8962-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        val registered = linkedMapOf<String, Any>()
        var registeredSlave: I2cSlaveDevice? = null

        init {
            registered["i2c-bus.bus1"] = object : I2cBusPort {
                override fun connect(owner: String): I2cConnection = error("unused")
                override fun registerSlave(device: I2cSlaveDevice) { registeredSlave = device }
                override fun unregisterSlave(address7: Int, device: I2cSlaveDevice?) {
                    if (registeredSlave === device) registeredSlave = null
                }
            }
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

    private fun i2cRead16(dev: I2cSlaveDevice, reg16: Int): Int {
        assertTrue(dev.address((0x1A shl 1) or 0))
        dev.write((reg16 ushr 8) and 0xFF)
        dev.write(reg16 and 0xFF)
        assertTrue(dev.address((0x1A shl 1) or 1))
        val hi = dev.peekRead(); dev.consumeRead(hi)
        val lo = dev.peekRead(); dev.consumeRead(lo)
        dev.stop()
        return (hi shl 8) or lo
    }

    private fun i2cWrite16(dev: I2cSlaveDevice, reg16: Int, value16: Int) {
        assertTrue(dev.address((0x1A shl 1) or 0))
        dev.write((reg16 ushr 8) and 0xFF)
        dev.write(reg16 and 0xFF)
        dev.write((value16 ushr 8) and 0xFF)
        dev.write(value16 and 0xFF)
        dev.stop()
    }

    @Test
    fun i2cAndSpi4ShareSingleRegisterFileAndExposeLiveAudioCodecRouting() {
        val h = Harness()
        val factory = Wm8962Factory()
        val comp = factory.create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()

        val slave = assertNotNull(h.registeredSlave)
        assertEquals(0x1A, slave.address7)
        val spi = assertNotNull(h.ports.find(Wm8962CodecDevice.SPI_PORT_NAME, SpiPeer::class.java))
        val codec = assertNotNull(h.ports.find(Wm8962CodecDevice.CODEC_PORT_NAME, AudioCodecControlPort::class.java))

        // Device ID 0x6243 on both I2C (0x0000 & 0x000F) and SPI4 (0x000F)
        assertEquals(0x6243, i2cRead16(slave, 0x0000))
        assertEquals(0x6243, i2cRead16(slave, 0x000F))
        assertEquals(0, spi.exchange(16, 0x800F, 1L))
        assertEquals(0x6243, spi.exchange(16, 0, 2L))

        // Enable headphone output via I2C 16-bit write and verify live AudioCodecControlPort + SPI4 readback
        i2cWrite16(slave, Wm8962CodecDevice.REG_PWR_MGMT_2, 0x0060)
        i2cWrite16(slave, Wm8962CodecDevice.REG_CHARGE_PUMP_1, 0x0001)
        i2cWrite16(slave, Wm8962CodecDevice.REG_HPOUTL_VOL, 0x0179)
        i2cWrite16(slave, Wm8962CodecDevice.REG_HPOUTR_VOL, 0x0179)
        val route = codec.outputRoute()
        assertEquals(AudioSinkKind.HEADPHONE_STEREO, route.activeSink)
        assertTrue(route.headphoneEnabled)
        assertEquals(1.0f, route.effectiveLeftGain, 0.001f)

        // Write DSP2 program RAM & ADC power via SPI4 and verify via I2C
        assertEquals(0, spi.exchange(16, 0x2000, 3L))
        assertEquals(0, spi.exchange(16, 0xBEEF, 4L))
        assertEquals(0xBEEF, i2cRead16(slave, 0x2000))
        assertEquals(1, codec.dsp2ProgramWriteCount())

        // Snapshot round-trip
        val snap = comp.saveSnapshot()
        val h2 = Harness()
        val comp2 = factory.create(h2, mapOf("enabled" to true)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())

        // Software reset clears volatile registers while keeping ID 0x6243
        i2cWrite16(slave, Wm8962CodecDevice.REG_SOFTWARE_RESET, 0x6243)
        assertEquals(1L, codec.resetCount())
        assertEquals(0, codec.readRegister(0x2000))
        assertEquals(0x6243, i2cRead16(slave, 0x000F))

        comp.close()
        comp2.close()
        assertNull(h.registeredSlave)
    }
}
