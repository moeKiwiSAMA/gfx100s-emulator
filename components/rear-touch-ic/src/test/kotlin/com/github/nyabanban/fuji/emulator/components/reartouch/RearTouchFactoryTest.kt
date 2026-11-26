package com.github.nyabanban.fuji.emulator.components.reartouch

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RearTouchFactoryTest {
    private class FakePorts : Ports {
        private val entries = linkedMapOf<String, Pair<Class<*>, Any>>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            entries[name] = type to port
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? {
            val found = entries[name] ?: return null
            return if (type.isAssignableFrom(found.first)) type.cast(found.second) else null
        }
        override fun removeOwner(owner: String) = Unit
    }

    private class FakeI2cBus : I2cBusPort {
        val slaves = linkedMapOf<Int, I2cSlaveDevice>()
        override fun registerSlave(device: I2cSlaveDevice) {
            slaves[device.address7] = device
        }
        override fun unregisterSlave(address7: Int, device: I2cSlaveDevice?) {
            val cur = slaves[address7] ?: return
            if (device == null || cur === device) slaves.remove(address7)
        }
        override fun connect(owner: String): I2cConnection = throw UnsupportedOperationException()
    }

    private class FakeGpioOutputs : GpioOutputPort {
        private val levels = HashMap<Pair<Int, Int>, Boolean>()
        private val seqs = HashMap<Pair<Int, Int>, Long>()
        private val listeners = HashMap<Pair<Int, Int>, MutableList<(Boolean, Long) -> Unit>>()

        fun drive(port: Int, pin: Int, high: Boolean) {
            val key = port to pin
            val nextSeq = (seqs[key] ?: 0L) + 1L
            levels[key] = high
            seqs[key] = nextSeq
            listeners[key]?.toList()?.forEach { it(high, nextSeq) }
        }

        override fun outputLevel(port: Int, pin: Int): Boolean = levels[port to pin] ?: false
        override fun outputSequence(port: Int, pin: Int): Long = seqs[port to pin] ?: 0L
        override fun addOutputListener(port: Int, pin: Int, listener: (Boolean, Long) -> Unit): AutoCloseable {
            val key = port to pin
            val list = listeners.getOrPut(key) { ArrayList() }
            list.add(listener)
            return AutoCloseable { listeners[key]?.remove(listener) }
        }
    }

    private class FakeEdgeGic : EdgeInterruptSink {
        val transitions = mutableListOf<Pair<Int, Boolean>>()
        var edgeMode: Boolean = true
        override fun setLine(irq: Int, high: Boolean) {
            transitions.add(irq to high)
        }
        override fun edgeState(irq: Int): EdgeInterruptState =
            EdgeInterruptState(edge = edgeMode, high = false, pending = false)
    }

    private fun context(ports: Ports): ComponentContext = object : ComponentContext {
        override val directory: java.nio.file.Path = java.nio.file.Path.of("unused-rear-touch-test")
        override val clock: EmulationClock = EmulationClock { 0L }
        override val events: EventChannel = object : EventChannel {
            override fun publish(event: ComponentEvent) = Unit
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports: Ports = ports
        override fun dataPath(relative: String): java.nio.file.Path = directory.resolve(relative)
    }

    private fun readReg(dev: I2cSlaveDevice, reg: Int, len: Int): IntArray {
        assertTrue(dev.address((dev.address7 shl 1) or 0))
        dev.write(reg)
        assertTrue(dev.address((dev.address7 shl 1) or 1))
        val out = IntArray(len) {
            val v = dev.peekRead()
            dev.consumeRead(v)
            v
        }
        dev.stop()
        return out
    }

    private fun writeReg(dev: I2cSlaveDevice, reg: Int, vararg data: Int) {
        assertTrue(dev.address((dev.address7 shl 1) or 0))
        dev.write(reg)
        for (b in data) dev.write(b)
        dev.stop()
    }

    @Test
    fun disabledByDefaultLeavesBusUntouchedAndDeclaresNoInterruptRoutes() {
        val ports = FakePorts()
        val bus = FakeI2cBus()
        ports.register("i2c-bus", "i2c-bus.bus2", I2cBusPort::class.java, bus)
        val factory = RearTouchFactory()
        val comp = factory.create(context(ports), emptyMap())
        comp.start()
        assertTrue(bus.slaves.isEmpty())
        assertTrue(comp.interruptRoutes().isEmpty())
        assertNull(ports.find(RearTouchPort.PORT_NAME, RearTouchPort::class.java))
        comp.close()
    }

    @Test
    fun enabledAttachesToBus2ImplementsFullProtocolResetGpioAndIrq153() {
        val ports = FakePorts()
        val bus = FakeI2cBus()
        val gpio = FakeGpioOutputs()
        val gic = FakeEdgeGic()
        ports.register("i2c-bus", "i2c-bus.bus2", I2cBusPort::class.java, bus)
        ports.register("soc-gpio", "soc-gpio.outputs", GpioOutputPort::class.java, gpio)
        ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)

        val comp = RearTouchFactory().create(
            context(ports),
            mapOf("enabled" to true, "touchEnabled" to true),
        ) as StatefulComponent
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 153..153)), comp.interruptRoutes())
        comp.start()

        val dev = bus.slaves[0x55]
        assertNotNull(dev, "Standalone rear-touch-ic must attach to I2C2 @ 0x55")
        val touchPort = ports.find(RearTouchPort.PORT_NAME, RearTouchPort::class.java)
        assertNotNull(touchPort)
        assertTrue(touchPort.touchEnabled)

        // Alive counter 0x12 increments on consecutive reads (prevents 64 ms firmware watchdog reset)
        val alive1 = readReg(dev, 0x12, 1)[0]
        val alive2 = readReg(dev, 0x12, 1)[0]
        assertNotEquals(alive1, alive2, "0x12 alive counter must increment on every read")
        assertEquals((alive1 + 1) and 0xFF, alive2)

        // FW info 0x20, sub info 0x21, config 0x03, ext config 0x30/0x31, command 0xF6
        assertEquals(10, readReg(dev, 0x20, 10).size)
        assertEquals(3, readReg(dev, 0x21, 3).size)
        writeReg(dev, 0x03, 0x5A)
        assertContentEquals(intArrayOf(0x5A), readReg(dev, 0x03, 1))
        val extPayload = IntArray(14) { it + 1 }
        writeReg(dev, 0x30, *extPayload)
        assertContentEquals(extPayload, readReg(dev, 0x31, 14))
        writeReg(dev, 0xF6, 0x12)
        assertContentEquals(intArrayOf(0x12), readReg(dev, 0xF6, 1))

        // Inject single touch via RearTouchPort -> pulses GIC IRQ 153 and returns 0x10 header + 0x11 FIFO
        gic.transitions.clear()
        touchPort.updateSingleTouch(enabled = true, pressed = true, x = 512, y = 384)
        assertTrue(gic.transitions.any { it.first == 153 && it.second }, "Touch update must pulse GIC IRQ 153")
        assertContentEquals(intArrayOf(1, 0), readReg(dev, 0x10, 2))
        val f0 = readReg(dev, 0x11, 7)
        assertEquals(0x81, f0[0])
        assertEquals(512, f0[1] or (f0[2] shl 8))
        assertEquals(384, f0[3] or (f0[4] shl 8))
        val f1 = readReg(dev, 0x11, 7)
        assertEquals(0x00, f1[0])

        // Active-HIGH GPIO(7, 2) reset NACKs 0x55 and clears touch; releasing 1 -> 0 pulses boot-ready IRQ 153
        gic.transitions.clear()
        gpio.drive(7, 2, true)
        assertFalse(dev.address(0x55 shl 1), "Device held in active-HIGH GPIO(7,2) reset must NACK 0x55")
        gpio.drive(7, 2, false)
        assertTrue(gic.transitions.any { it.first == 153 && it.second }, "Releasing GPIO(7,2) reset must pulse boot-ready IRQ 153")
        assertContentEquals(intArrayOf(0, 0), readReg(dev, 0x10, 2))

        // Snapshot save/restore round-trip preserves full register & alive counter state
        val snap = comp.saveSnapshot()
        writeReg(dev, 0x03, 0x00)
        readReg(dev, 0x12, 1)
        comp.restoreSnapshot(snap)
        assertContentEquals(intArrayOf(0x5A), readReg(dev, 0x03, 1))

        // Live touchEnabled=false detaches from I2C2 cleanly
        comp.applySettings(mapOf("touchEnabled" to false))
        assertNull(bus.slaves[0x55], "Setting touchEnabled=false must unregister slave from I2C2")
        assertFalse(touchPort.touchEnabled)

        comp.close()
    }
}
