package com.github.nyabanban.fuji.emulator.components.max17048

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Max17048FactoryTest {
    private class Harness(
        var pct: Double = 87.5,
        var mv: Int = 7800,
        var isCharging: Boolean = false,
        var extPower: Boolean = false,
        var isPresent: Boolean = true,
    ) : ComponentContext {
        override val directory: Path = Path.of("unused-max17048-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        val registered = linkedMapOf<String, Any>()
        var registeredSlave: I2cSlaveDevice? = null

        init {
            registered["battery"] = object : BatteryPort {
                override fun chargePercent(): Double = pct
                override fun voltageMillivolts(): Int = mv
                override fun present(): Boolean = isPresent
                override fun charging(): Boolean = isPresent && isCharging
                override fun externalPowerConnected(): Boolean = extPower || isCharging
            }
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
                registered[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun readWord16(dev: I2cSlaveDevice, reg: Int): Int {
        assertTrue(dev.address((0x36 shl 1) or 0))
        dev.write(reg)
        assertTrue(dev.address((0x36 shl 1) or 1))
        val hi = dev.peekRead(); dev.consumeRead(hi)
        val lo = dev.peekRead(); dev.consumeRead(lo)
        dev.stop()
        return (hi shl 8) or lo
    }

    private fun writeWord16(dev: I2cSlaveDevice, reg: Int, value16: Int) {
        assertTrue(dev.address((0x36 shl 1) or 0))
        dev.write(reg)
        dev.write((value16 ushr 8) and 0xFF)
        dev.write(value16 and 0xFF)
        dev.stop()
    }

    @Test
    fun reportsLiveBatteryVcellSocModelUnlockAndSnapshotEquivalence() {
        val h = Harness(pct = 87.5, mv = 7800)
        val factory = Max17048Factory()
        val comp = factory.create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()

        val slave = assertNotNull(h.registeredSlave)
        assertEquals(0x36, slave.address7)
        // 7800 mV * 6.4 LSB/mV (2S MAX17049) = 49920 = 0xC300
        assertEquals(0xC300, readWord16(slave, Max17048Device.REG_VCELL))
        // 87.5% -> 87 (0x57) . 128 (0x80) = 0x5780
        assertEquals(0x5780, readWord16(slave, Max17048Device.REG_SOC))
        assertEquals(0x0012, readWord16(slave, Max17048Device.REG_VERSION))
        assertEquals(0x9602, readWord16(slave, Max17048Device.REG_VRESET_ID))

        // OCV is locked until 0x3E = 0x4A57 ("JW")
        assertEquals(0xFFFF, readWord16(slave, Max17048Device.REG_OCV))
        writeWord16(slave, Max17048Device.REG_LOCK, 0x4A57)
        assertEquals(0xC300, readWord16(slave, Max17048Device.REG_OCV))

        // Write 64-byte model table at 0x40..0x7F and read back while unlocked
        assertTrue(slave.address((0x36 shl 1) or 0))
        slave.write(0x40)
        for (i in 0 until 64) slave.write((i * 3) and 0xFF)
        slave.stop()
        assertEquals(0x0003, readWord16(slave, 0x40))

        // Lock again -> OCV & model RAM masked to 0xFFFF
        writeWord16(slave, Max17048Device.REG_LOCK, 0x0000)
        assertEquals(0xFFFF, readWord16(slave, Max17048Device.REG_OCV))

        // Live battery change updates SOC/VCELL immediately
        h.pct = 20.0
        h.mv = 6800
        assertEquals(0x1400, readWord16(slave, Max17048Device.REG_SOC))

        val snap = comp.saveSnapshot()
        val h2 = Harness(pct = 20.0, mv = 6800)
        val comp2 = factory.create(h2, mapOf("enabled" to true)) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())

        comp.close()
        comp2.close()
        assertNull(h.registeredSlave)
    }

    @Test
    fun reportsSignedTwoComplementCrateForChargingFullExternalPowerAndDischarging() {
        val h = Harness(pct = 65.0, mv = 7950, isCharging = true, extPower = true)
        val comp = Max17048Factory().create(h, mapOf("enabled" to true)) as StatefulComponent
        comp.start()
        val slave = assertNotNull(h.registeredSlave)

        // Charging (+36.0 %/hr / 0.208 = +173 = 0x00AD > 0)
        val chargingCrate = readWord16(slave, Max17048Device.REG_CRATE).toShort().toInt()
        assertEquals(173, chargingCrate)
        assertTrue(chargingCrate > 0)

        // CV top-off at 100% while charging (+6.0 %/hr / 0.208 = +29 > 0)
        h.pct = 100.0
        val topOffCrate = readWord16(slave, Max17048Device.REG_CRATE).toShort().toInt()
        assertEquals(29, topOffCrate)

        // External USB power connected, not charging -> CRATE == 0
        h.isCharging = false
        h.extPower = true
        assertEquals(0, readWord16(slave, Max17048Device.REG_CRATE).toShort().toInt())

        // On battery alone (discharging -12.0 %/hr / 0.208 = -58 = 0xFFC6 < 0)
        h.extPower = false
        val dischargingRaw = readWord16(slave, Max17048Device.REG_CRATE)
        assertEquals(0xFFC6, dischargingRaw)
        assertEquals(-58, dischargingRaw.toShort().toInt())

        // Battery removed -> CRATE == 0
        h.isPresent = false
        assertEquals(0, readWord16(slave, Max17048Device.REG_CRATE))

        comp.close()
    }
}
