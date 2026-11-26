package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class VirtualTouchPanelTest {
    private val activeProfile = "explicit-i2c-buses-experimental-v1"

    private class RecordingIrqSink(var edgeMode: Boolean) : EdgeInterruptSink {
        val transitions = mutableListOf<Pair<Int, Boolean>>()
        var high = false
        var pending = false

        override fun setLine(irq: Int, high: Boolean) {
            transitions += irq to high
            if (edgeMode && high && !this.high) pending = true
            this.high = high
        }

        override fun edgeState(irq: Int) = EdgeInterruptState(edge = edgeMode, high = high, pending = pending)
    }

    private class FakeGpioOutputs(var level72: Boolean = false, var seq72: Long = 0L) : GpioOutputPort {
        private val listeners = mutableListOf<(Boolean, Long) -> Unit>()

        override fun outputLevel(port: Int, pin: Int): Boolean =
            if (port == VirtualTouchPanel.RESET_GPIO_BANK && pin == VirtualTouchPanel.RESET_GPIO_PIN) level72 else false

        override fun outputSequence(port: Int, pin: Int): Long =
            if (port == VirtualTouchPanel.RESET_GPIO_BANK && pin == VirtualTouchPanel.RESET_GPIO_PIN) seq72 else 0L

        override fun addOutputListener(port: Int, pin: Int, listener: (Boolean, Long) -> Unit): AutoCloseable {
            if (port == VirtualTouchPanel.RESET_GPIO_BANK && pin == VirtualTouchPanel.RESET_GPIO_PIN) {
                listeners.add(listener)
                return AutoCloseable { listeners.remove(listener) }
            }
            return AutoCloseable {}
        }

        fun driveReset(level: Boolean) {
            if (level == level72) return
            level72 = level
            seq72++
            for (l in listeners.toList()) l(level72, seq72)
        }
    }

    private class TouchContext(
        val busData: Map<String, Any?>,
        irqSink: InterruptSink? = null,
        gpioOutputs: GpioOutputPort? = null,
    ) : ComponentContext {
        val registeredPorts = hashMapOf<String, Any>()
        override val directory: Path = Path.of("unused-touch-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("not used")
        }

        init {
            if (irqSink != null) registeredPorts["soc-gic.lines"] = irqSink
            if (gpioOutputs != null) registeredPorts["soc-gpio.outputs"] = gpioOutputs
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = busData
    }

    private fun emptyThreeBusData(): Map<String, Any?> = mapOf(
        "buses" to listOf(
            mapOf("index" to 0, "kind" to "empty"),
            mapOf("index" to 1, "kind" to "empty"),
            mapOf("index" to 2, "kind" to "empty"),
        ),
    )

    private fun explicitTouchBusData(): Map<String, Any?> = mapOf(
        "buses" to listOf(
            mapOf("index" to 2, "kind" to VirtualTouchPanel.KIND, "address" to VirtualTouchPanel.ADDRESS7),
        ),
    )

    private fun createBuses(context: TouchContext): StatefulComponent {
        val factory = I2cBusFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        return assertIs<StatefulComponent>(
            factory.create(context, defaults + mapOf("profile" to activeProfile, "inputFile" to "buses.json")),
        )
    }

    private fun readReg(conn: I2cConnection, reg: Int, length: Int): List<Int> {
        assertTrue(conn.address(VirtualTouchPanel.ADDRESS7 shl 1))
        conn.write(reg)
        assertTrue(conn.address((VirtualTouchPanel.ADDRESS7 shl 1) or 1))
        val out = ArrayList<Int>(length)
        repeat(length) {
            val b = conn.peekRead()
            conn.consumeRead(b)
            out += b
        }
        conn.stop()
        return out
    }

    private fun writeReg(conn: I2cConnection, reg: Int, vararg payload: Int) {
        assertTrue(conn.address(VirtualTouchPanel.ADDRESS7 shl 1))
        conn.write(reg)
        payload.forEach { conn.write(it) }
        conn.stop()
    }

    @Test
    fun disabledByDefaultKeepsBus2EmptyNackAndRestoresLegacySnapshotsCleanly() {
        val irq = RecordingIrqSink(edgeMode = true)
        val ctx = TouchContext(emptyThreeBusData(), irqSink = irq)
        val buses = createBuses(ctx)
        val conn2 = (ctx.registeredPorts.getValue("i2c-bus.bus2") as I2cBusPort).connect("soc-i2c.controller2")

        assertEquals(false, buses.state()["touchEnabled"])
        // Default OFF: bus 2 NACKs 0x55 and never touches IRQ 128
        assertFalse(conn2.address(VirtualTouchPanel.ADDRESS7 shl 1))
        assertTrue(irq.transitions.isEmpty())

        // Snapshot of disabled empty bus 2 must not contain liveTouch
        val snap = buses.saveSnapshot()
        @Suppress("UNCHECKED_CAST")
        val bus2Snap = (snap["buses"] as List<Map<String, Any?>>).single { it["index"] == 2 }
        assertFalse("liveTouch" in bus2Snap)

        // Restore legacy 2-key and 3-key scalars snapshots seamlessly
        @Suppress("UNCHECKED_CAST")
        val fullScalars = snap["scalars"] as Map<String, Any?>
        val legacy2 = snap + ("scalars" to fullScalars.filterKeys { it in setOf("profile", "inputFile") })
        val legacy3 = snap + ("scalars" to fullScalars.filterKeys { it in setOf("profile", "inputFile", "eyeSensorNear") })
        buses.restoreSnapshot(legacy2)
        assertEquals(false, buses.state()["touchEnabled"])
        assertEquals(0L, buses.state()["touchX"])
        buses.restoreSnapshot(legacy3)
        assertEquals(false, buses.state()["touchEnabled"])
        assertFalse(conn2.address(VirtualTouchPanel.ADDRESS7 shl 1))

        conn2.close()
        buses.close()
    }

    @Test
    fun threeTransferWireProtocolCoordsAliveCommandAndIrq128WorkEndToEnd() {
        val levelIrq = RecordingIrqSink(edgeMode = false)
        val ctx = TouchContext(emptyThreeBusData(), irqSink = levelIrq)
        val buses = createBuses(ctx)
        val conn = (ctx.registeredPorts.getValue("i2c-bus.bus2") as I2cBusPort).connect("soc-i2c.controller2")

        // Enable touch + press finger at (x=0x0321, y=0x0245)
        buses.applySettings(
            mapOf(
                "touchEnabled" to true,
                "touchPressed" to true,
                "touchX" to 0x0321L,
                "touchY" to 0x0245L,
            ),
        )
        assertTrue(levelIrq.high, "Level IRQ 128 must assert on touch down")
        assertEquals(listOf(VirtualTouchPanel.IRQ_LINE to true), levelIrq.transitions)

        // Unrelated address on bus 2 still NACKs
        assertFalse(conn.address(0x39 shl 1))

        // Step 1: Reg 0x10 -> 2B [count=1, flags=0x00 (bits 0x18 clear)]
        val header = readReg(conn, VirtualTouchPanel.REG_HEADER, 2)
        assertEquals(listOf(0x01, 0x00), header)
        assertEquals(0, header[1] and 0x18)

        // Step 2: First 0x11 -> 7B Finger #0 [0x81, xLo, xHi, yLo, yHi, 0, 0]
        val finger0 = readReg(conn, VirtualTouchPanel.REG_FINGER_FIFO, 7)
        assertEquals(listOf(0x81, 0x21, 0x03, 0x45, 0x02, 0x00, 0x00), finger0)

        // Step 3: Second 0x11 -> 7B Finger #1 (inactive zeros) & level IRQ 128 deasserts on FIFO drain
        val finger1 = readReg(conn, VirtualTouchPanel.REG_FINGER_FIFO, 7)
        assertEquals(listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00), finger1)
        assertFalse(levelIrq.high, "Level IRQ 128 must deassert after 3-transfer read completes")

        // Aux Reg 0x03 device control & 0xF4 vendor control write/readback (used by T91 boot init)
        writeReg(conn, VirtualTouchPanel.REG_DEVICE_CONTROL, 0x01)
        assertEquals(listOf(0x01), readReg(conn, VirtualTouchPanel.REG_DEVICE_CONTROL, 1))
        writeReg(conn, 0xF4, 0x2C)
        assertEquals(listOf(0x00), readReg(conn, 0xF4, 1))

        // Aux Reg 0x12 alive heartbeat byte increments on each read (DAT s4:0x0124e12c watchdog)
        assertEquals(listOf(0x01), readReg(conn, VirtualTouchPanel.REG_ALIVE, 1))
        assertEquals(listOf(0x02), readReg(conn, VirtualTouchPanel.REG_ALIVE, 1))

        // Aux Reg 0x20 (10B FW info), 0x21 (3B sub info), and 0x30 -> 0x31 (14B config/readback)
        assertEquals(10, readReg(conn, VirtualTouchPanel.REG_FW_INFO, 10).size)
        assertEquals(3, readReg(conn, VirtualTouchPanel.REG_SUB_INFO, 3).size)
        val extPayload = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 8, 6, 8, 6, 0x28)
        writeReg(conn, VirtualTouchPanel.REG_EXT_CONFIG, *extPayload)
        assertEquals(extPayload.toList(), readReg(conn, VirtualTouchPanel.REG_EXT_READBACK, 14))

        // Aux Reg 0xF6 command write & readback
        writeReg(conn, VirtualTouchPanel.REG_COMMAND, 0x5A)
        assertEquals(listOf(0x5A), readReg(conn, VirtualTouchPanel.REG_COMMAND, 1))
        writeReg(conn, VirtualTouchPanel.REG_COMMAND, 0x02, 0x01, 0x03)
        assertEquals(listOf(0x02), readReg(conn, VirtualTouchPanel.REG_COMMAND, 1))

        // Invalid register & extra write byte reject cleanly
        assertTrue(conn.address(VirtualTouchPanel.ADDRESS7 shl 1))
        assertFailsWith<IllegalArgumentException> { conn.checkWrite(0x99) }
        conn.write(VirtualTouchPanel.REG_HEADER)
        assertFailsWith<IllegalArgumentException> { conn.checkWrite(0x00) }
        conn.stop()

        // Release finger -> asserts IRQ 128 again; header reports count=0, finger0 bit7 cleared
        buses.applySettings(mapOf("touchPressed" to false))
        assertTrue(levelIrq.high)
        assertEquals(listOf(0x00, 0x00), readReg(conn, VirtualTouchPanel.REG_HEADER, 2))
        assertFalse(levelIrq.high)
        assertEquals(listOf(0x00, 0x21, 0x03, 0x45, 0x02, 0x00, 0x00), readReg(conn, VirtualTouchPanel.REG_FINGER_FIFO, 7))

        // Disabling touchEnabled on an empty bus cleanly reverts to NACK
        buses.applySettings(mapOf("touchEnabled" to false))
        assertFalse(conn.address(VirtualTouchPanel.ADDRESS7 shl 1))

        conn.close()
        buses.close()
    }

    @Test
    fun edgeIrq153ResetGpio72ActiveHighTwoFingerEncodingAndSnapshotRoundTrip() {
        assertEquals(153, VirtualTouchPanel.IRQ_LINE)
        val edgeIrq = RecordingIrqSink(edgeMode = true)
        val gpio = FakeGpioOutputs(level72 = false, seq72 = 1L)

        // Direct unit test for 2-finger multi-touch & GPIO 7.2 active-HIGH reset (1=RESET, 0=RUN)
        val panel = VirtualTouchPanel(irqSinkProvider = { edgeIrq }, gpioOutputProvider = { gpio })
        panel.attachResetListener()
        panel.setTwoTouch(true, 100, 200, true, 900, 600)
        assertTrue(edgeIrq.pending)
        assertFalse(edgeIrq.high, "Edge IRQ 153 pulse must return line low after latching pending")
        assertTrue(edgeIrq.transitions.all { it.first == 153 })

        // Verify 2-finger wire bytes
        assertTrue(panel.address(0xAA))
        panel.write(0x10)
        assertTrue(panel.address(0xAB))
        val h0 = panel.peekRead().also(panel::consumeRead)
        val h1 = panel.peekRead().also(panel::consumeRead)
        panel.stop()
        assertEquals(listOf(2, 0), listOf(h0, h1))

        assertTrue(panel.address(0xAA))
        panel.write(0x11)
        assertTrue(panel.address(0xAB))
        val f0 = List(7) { panel.peekRead().also(panel::consumeRead) }
        panel.stop()
        assertEquals(listOf(0x81, 100, 0, 200, 0, 0, 0), f0)

        assertTrue(panel.address(0xAA))
        panel.write(0x11)
        assertTrue(panel.address(0xAB))
        val f1 = List(7) { panel.peekRead().also(panel::consumeRead) }
        panel.stop()
        assertEquals(listOf(0x82, 900 and 0xFF, 900 ushr 8, 600 and 0xFF, 600 ushr 8, 0, 0), f1)

        // Drive GPIO 7.2 HIGH (active-HIGH reset, DAT s4:0x0124d81c) -> NACKs 0x55 and clears active touch
        edgeIrq.pending = false
        val pulsesBeforeReset = panel.state()["irqPulseCount"] as Long
        gpio.driveReset(true)
        assertFalse(panel.address(0xAA))
        assertEquals(true, panel.state()["inReset"])
        assertEquals(1L, panel.state()["resetCount"])
        assertEquals(0, panel.state()["touchCount"])

        // Release GPIO 7.2 LOW (DAT s4:0x0124d918) -> immediately pulses boot-ready GIC IRQ 153 & ACKs 0x55
        gpio.driveReset(false)
        assertEquals(false, panel.state()["inReset"])
        assertTrue(edgeIrq.pending, "Releasing active-HIGH reset (1 -> 0) must pulse boot-ready GIC IRQ 153")
        assertEquals(pulsesBeforeReset + 1L, panel.state()["irqPulseCount"])
        assertTrue(panel.address(0xAA))
        panel.write(0x10)
        assertTrue(panel.address(0xAB))
        assertEquals(0, panel.peekRead().also(panel::consumeRead))
        assertEquals(0, panel.peekRead().also(panel::consumeRead))
        panel.stop()
        panel.detachResetListener()

        // Explicit touch-panel-live-v1 bus declares GIC IRQ 153 route + snapshot round-trip + atomic refusal
        val ctx1 = TouchContext(explicitTouchBusData(), irqSink = edgeIrq, gpioOutputs = gpio)
        val buses1 = createBuses(ctx1)
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 153..153)), (buses1 as HardwareComponent).interruptRoutes())
        buses1.start()
        buses1.applySettings(mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to 512L, "touchY" to 384L))
        val snap = buses1.saveSnapshot()

        val ctx2 = TouchContext(explicitTouchBusData(), irqSink = edgeIrq, gpioOutputs = gpio)
        val buses2 = createBuses(ctx2)
        buses2.restoreSnapshot(snap)
        assertEquals(buses1.state(), buses2.state())

        @Suppress("UNCHECKED_CAST")
        val badRows = (snap["buses"] as List<Map<String, Any?>>).map { row ->
            row + ("liveTouch" to ((row["liveTouch"] as Map<String, Any?>) + ("readPos" to 99)))
        }
        val beforeBad = buses2.state()
        assertFailsWith<IllegalArgumentException> { buses2.restoreSnapshot(snap + ("buses" to badRows)) }
        assertEquals(beforeBad, buses2.state())

        buses1.close()
        buses2.close()
    }
}
