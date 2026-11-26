package com.github.nyabanban.fuji.emulator.components.shutter

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ShutterFactoryTest {
    private class TestContext(withGic: Boolean = true) : ComponentContext {
        var currentTick = 0L
        val registeredPorts = linkedMapOf<String, Any>()
        val irqLog = ArrayList<Pair<Int, Boolean>>()
        val eventsLog = ArrayList<ComponentEvent>()

        override val directory: Path = Path.of("unused-shutter-test")
        override val clock = EmulationClock { currentTick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {
                eventsLog.add(event)
            }

            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? =
                registeredPorts[name]?.let(type::cast)

            override fun removeOwner(owner: String) = Unit
        }

        init {
            if (withGic) {
                registeredPorts["soc-gic.lines"] = object : InterruptSink {
                    override fun setLine(irq: Int, high: Boolean) {
                        irqLog.add(irq to high)
                    }
                }
            }
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun createShutter(
        ctx: TestContext,
        overrides: Map<String, Any> = emptyMap(),
    ): StatefulComponent {
        val factory = ShutterFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        return assertIs<StatefulComponent>(factory.create(ctx, defaults + overrides))
    }

    @Test
    fun disabledByDefaultRegistersNoPortAndNoInterruptRoutes() {
        val ctx = TestContext()
        val comp = createShutter(ctx)
        assertNull(ctx.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java))
        assertTrue(comp.interruptRoutes().isEmpty())
        comp.start()
        assertEquals(false, comp.state()["enabled"])
        assertEquals("IDLE", comp.state()["mode"])
        comp.close()
    }

    @Test
    fun electronicShutterTgActNeverFabricatesCurtainPulsesOrMotor2() {
        val ctx = TestContext()
        val comp = createShutter(ctx, mapOf("enabled" to true))
        val port = assertNotNull(ctx.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java))
        assertEquals(
            setOf(154, 156, 161, 170, 172, 177),
            comp.interruptRoutes().flatMap { it.lines.toList() }.toSet(),
        )
        comp.start()

        // Pure Electronic Shutter (ES): CCDTG TG_ACT fires with Motor 2 completely idle
        ctx.currentTick = 10_000L
        port.onTgActRising(ctx.currentTick)

        assertNull(port.nextDeadline(), "TG_ACT alone must never schedule curtain optical-gate deadlines (BUG-A2-5)")
        assertTrue(ctx.irqLog.isEmpty(), "TG_ACT alone must never fire EXTINT #10 (IRQ 161/177)")
        val t = port.telemetry()
        assertEquals(ShutterMode.ELECTRONIC, t.mode)
        assertEquals(0L, t.curtainPulses, "Electronic Shutter must have zero curtainPulses (BUG-A2-5)")
        assertEquals(0L, t.cam2Cycles, "Electronic Shutter must have zero cam2Cycles")
        assertEquals(1L, t.tgActCount)
        assertEquals(1L, t.electronicCount)
        assertEquals(0L, t.mechanicalCount)
        assertEquals(0L, t.efcsCount)
        assertEquals(1L, t.shutterCount)
        assertTrue(t.readoutActive)

        // Advance past IMX461 rolling readout duration (125 ms = 6,750,000 ticks @ 54 MHz)
        ctx.currentTick += 125_000L * ShutterMechanism.TICKS_PER_US
        assertFalse(port.advanceToTick(ctx.currentTick))
        assertFalse(port.telemetry().readoutActive)
        comp.close()
    }

    @Test
    fun mechanicalAndEfcsModesDriveMotorsCurtainOpticalGateAndSnapshots() {
        val ctx = TestContext()
        val comp = createShutter(ctx, mapOf("enabled" to true, "curtainTravelUs" to 4_200L))
        val port = assertNotNull(ctx.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java))
        comp.start()

        // 1. Motor 1 PWM ON -> CAM1 toggles + IRQ 154/170
        ctx.currentTick = 1_000L
        port.onMotor1PwmChanged(true, ctx.currentTick)
        val m1Dl = assertNotNull(port.nextDeadline())
        ctx.currentTick = m1Dl
        assertTrue(port.advanceToTick(ctx.currentTick))
        assertTrue(port.cam1High)
        assertTrue((154 to true) in ctx.irqLog)
        port.onMotor1PwmChanged(false, ctx.currentTick)

        // 2. EFCS capture: 1 Motor 2 stroke -> CAM2 + 3 curtain pulses + TG_ACT -> EFCS mode
        ctx.irqLog.clear()
        ctx.currentTick += 1_000L
        port.onMotor2HBridgeChanged(pin7High = true, pin8High = false, tick = ctx.currentTick)
        while (true) {
            val dl = port.nextDeadline() ?: break
            ctx.currentTick = dl
            port.advanceToTick(ctx.currentTick)
            if (port.telemetry().cam2Cycles >= 1L && port.telemetry().motor2Running) {
                port.onMotor2HBridgeChanged(pin7High = false, pin8High = false, tick = ctx.currentTick)
            }
        }
        assertEquals(1L, port.telemetry().cam2Cycles)
        assertEquals(3L, port.telemetry().curtainPulses)
        assertTrue(ctx.irqLog.count { it == (161 to true) } == 3)
        assertTrue(ctx.irqLog.count { it == (177 to true) } == 3)

        port.onTgActRising(ctx.currentTick)
        assertEquals(ShutterMode.EFCS, port.telemetry().mode)
        assertEquals(1L, port.telemetry().efcsCount)
        assertEquals(1L, port.telemetry().shutterCount)

        // 3. Mechanical Shutter (MS): 2 Motor 2 strokes (front + rear) -> MECHANICAL mode
        ctx.currentTick += 10_000L
        port.onMotor2HBridgeChanged(pin7High = true, pin8High = false, tick = ctx.currentTick)
        port.onMotor2HBridgeChanged(pin7High = false, pin8High = true, tick = ctx.currentTick + 5_000L)
        port.onMotor2HBridgeChanged(pin7High = false, pin8High = false, tick = ctx.currentTick + 10_000L)
        port.onTgActRising(ctx.currentTick + 15_000L)
        assertEquals(ShutterMode.MECHANICAL, port.telemetry().mode)
        assertEquals(1L, port.telemetry().mechanicalCount)
        assertEquals(2L, port.telemetry().shutterCount)

        // 4. Snapshot round-trip
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext().apply { currentTick = ctx.currentTick }
        val restored = createShutter(ctx2, mapOf("enabled" to true, "curtainTravelUs" to 4_200L))
        restored.start()
        restored.restoreSnapshot(snap)
        assertEquals(comp.saveSnapshot(), restored.saveSnapshot())
        assertEquals(4_200L, restored.state()["curtainTravelUs"])

        // Live setting update
        restored.applySettings(mapOf("rollingReadoutUs" to 80_000L))
        assertEquals(80_000L, restored.state()["rollingReadoutUs"])

        // Closing deasserts all IRQ lines cleanly
        ctx.irqLog.clear()
        comp.close()
        restored.close()
    }
}
