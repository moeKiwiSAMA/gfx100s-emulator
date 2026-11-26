package com.github.nyabanban.fuji.emulator.components.gpio

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ShutterExtIrqTest {
    private class TestContext(
        withButtons: Boolean = true,
        withGic: Boolean = true,
    ) : ComponentContext {
        val pressedKeys = hashSetOf<String>()
        val registeredPorts = hashMapOf<String, Any>()
        val irqLog = ArrayList<Pair<Int, Boolean>>()
        val listeners = ArrayList<(ComponentEvent) -> Unit>()
        var currentTick = 0L

        override val directory: Path = Path.of("unused-shutter-extirq-test")
        override val clock = EmulationClock { currentTick }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {
                for (listener in listeners.toList()) {
                    listener(event)
                }
            }

            override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable {
                listeners.add(listener)
                return AutoCloseable { listeners.remove(listener) }
            }
        }

        init {
            if (withButtons) {
                registeredPorts["buttons"] = object : ButtonPort {
                    override fun pressed(key: String): Boolean = key in pressedKeys
                }
            }
            if (withGic) {
                registeredPorts["soc-gic.lines"] = object : InterruptSink {
                    override fun setLine(irq: Int, high: Boolean) {
                        irqLog.add(irq to high)
                    }
                }
            }
        }

        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }

            override fun <T : Any> find(name: String, type: Class<T>): T? =
                registeredPorts[name]?.let(type::cast)

            override fun removeOwner(owner: String) = error("not used")
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)

        fun setButtons(vararg changes: Pair<String, Boolean>) {
            val map = linkedMapOf<String, Any?>()
            for ((key, pressed) in changes) {
                if (pressed) pressedKeys.add(key) else pressedKeys.remove(key)
                map[key] = pressed
            }
            events.publish(ComponentEvent("buttons", "switches", currentTick, map))
        }
    }

    private fun createGpio(
        context: TestContext,
        profile: String = "gfx100s-2.13-gpio-buttons-v3",
        shutterIrqProfile: String = GpioFactory.SHUTTER_IRQ_PROFILE,
    ): StatefulComponent {
        val factory = GpioFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        return assertIs<StatefulComponent>(
            factory.create(
                context,
                defaults + mapOf(
                    "profile" to profile,
                    "shutterIrqProfile" to shutterIrqProfile,
                ),
            ),
        )
    }

    @Test
    fun lensExchangeHoldsDetectUntilDeadlineAndSurvivesSnapshot() {
        val ctx = TestContext()
        ctx.registeredPorts["mount"] = object : MountPort {
            override fun attachedLens(): LensParameters? = null
        }
        val gpio = assertIs<GpioFactory.Gpio>(createGpio(ctx))
        gpio.start()
        gpio.setInputLevel(0, GpioFactory.LENS_DET_PIN, false)
        val connected = gpio.saveSnapshot()
        gpio.breakForLensExchange()
        val deadline = requireNotNull(gpio.nextDeadline())
        assertTrue(gpio.replacingLens)
        assertEquals(162 to true, ctx.irqLog.last())
        val pending = gpio.saveSnapshot()

        gpio.beforeInstruction(deadline - 1)
        assertTrue(gpio.replacingLens)
        gpio.beforeInstruction(deadline)
        assertEquals(162 to false, ctx.irqLog.last())
        assertFalse(gpio.replacingLens)
        gpio.restoreSnapshot(pending)
        assertEquals(deadline, gpio.nextDeadline())
        assertEquals(162 to true, ctx.irqLog.last())

        gpio.setInputLevel(0, GpioFactory.LENS_DET_PIN, true)
        gpio.beforeInstruction(deadline + 1)
        assertTrue(gpio.read(0x14, 4) and (1L shl GpioFactory.LENS_DET_PIN) != 0L)
        assertFalse(gpio.replacingLens)
        gpio.restoreSnapshot(connected)
        assertEquals(162 to false, ctx.irqLog.last())
        assertFalse(gpio.replacingLens)
        gpio.close()
    }

    @Test
    fun disabledProfileDeclaresNoInterruptRoutesAndNeverDrivesGicLines() {
        val ctx = TestContext()
        val gpio = createGpio(ctx, shutterIrqProfile = "disabled")
        assertTrue(gpio.interruptRoutes().isEmpty())
        gpio.start()
        ctx.setButtons("shutterHalf" to true, "shutterFull" to true)
        ctx.setButtons("shutterHalf" to false, "shutterFull" to false)
        assertTrue(ctx.irqLog.isEmpty())
        assertFalse("shutterIrqBinding" in gpio.state())
        gpio.close()
    }

    @Test
    fun shutterExtIrqProfileRoutesGic167And168AlongsideActiveLowBank0Input() {
        val ctx = TestContext()
        val gpio = createGpio(ctx)
        assertEquals(
            listOf(InterruptRoute("soc-gic.lines", GpioFactory.SHUTTER_HALF_IRQ..GpioFactory.SHUTTER_FULL_IRQ)),
            gpio.interruptRoutes(),
        )
        gpio.start()
        val mmio = gpio as MmioDevice

        // Released: active-low Bank 0 Pin 0 (S1) and Pin 1 (S2) read HIGH (bits 0..1 = 0b11)
        assertEquals(0x3L, mmio.read(0x14, 4) and 0x3L)
        assertTrue(ctx.irqLog.isEmpty())

        // Unrelated buttons do not drive S1/S2 IRQs
        ctx.setButtons("menu" to true, "play" to true, "back" to true)
        assertTrue(ctx.irqLog.isEmpty())

        // S1 press -> GIC IRQ 167 HIGH, Bank 0 Pin 0 LOW
        ctx.currentTick = 100L
        ctx.setButtons("shutterHalf" to true)
        assertEquals(listOf(167 to true), ctx.irqLog)
        assertEquals(0x2L, mmio.read(0x14, 4) and 0x3L)

        // S2 press -> GIC IRQ 168 HIGH, Bank 0 Pins 0 & 1 LOW
        ctx.currentTick = 200L
        ctx.setButtons("shutterFull" to true)
        assertEquals(listOf(167 to true, 168 to true), ctx.irqLog)
        assertEquals(0x0L, mmio.read(0x14, 4) and 0x3L)

        // Redundant switch event while held must not re-drive lines
        ctx.setButtons("shutterHalf" to true, "shutterFull" to true)
        assertEquals(2, ctx.irqLog.size)

        // Release S2 then S1 -> GIC lines deassert and GPIO pins return HIGH
        ctx.currentTick = 300L
        ctx.setButtons("shutterFull" to false)
        assertEquals(listOf(167 to true, 168 to true, 168 to false), ctx.irqLog)
        assertEquals(0x2L, mmio.read(0x14, 4) and 0x3L)

        ctx.currentTick = 400L
        ctx.setButtons("shutterHalf" to false)
        assertEquals(listOf(167 to true, 168 to true, 168 to false, 167 to false), ctx.irqLog)
        assertEquals(0x3L, mmio.read(0x14, 4) and 0x3L)

        // Closing while held deasserts both lines and unsubscribes
        ctx.setButtons("shutterHalf" to true, "shutterFull" to true)
        ctx.irqLog.clear()
        gpio.close()
        assertEquals(listOf(167 to false, 168 to false), ctx.irqLog)
        assertTrue(ctx.listeners.isEmpty())
    }

    @Test
    fun shutterExtIrqValidationAndRestartOnlyEnforcement() {
        // Requires a GPIO button profile
        val noButtonProfile = assertFailsWith<IllegalArgumentException> {
            createGpio(TestContext(), profile = "disabled", shutterIrqProfile = GpioFactory.SHUTTER_IRQ_PROFILE)
        }
        assertEquals("Shutter IRQ profile requires a GPIO button profile", noButtonProfile.message)

        // Requires soc-gic.lines at start()
        val missingGic = createGpio(TestContext(withGic = false))
        val gicErr = assertFailsWith<IllegalStateException> { missingGic.start() }
        assertEquals("Shutter IRQ profile requires soc-gic.lines", gicErr.message)
        missingGic.close()

        // Restart-only setting cannot be patched live
        val ctx = TestContext()
        val gpio = createGpio(ctx)
        gpio.start()
        assertFailsWith<IllegalArgumentException> {
            gpio.applySettings(mapOf("shutterIrqProfile" to "disabled"))
        }
        gpio.close()
    }

    @Test
    fun legacySnapshotUpgradesCleanlyAndRefusesDowngrade() {
        // Build a legacy snapshot that has no shutterIrqProfile key in scalars
        val disabledCtx = TestContext()
        val disabledGpio = createGpio(disabledCtx, shutterIrqProfile = "disabled")
        disabledGpio.start()
        val snapWithKey = disabledGpio.saveSnapshot()
        @Suppress("UNCHECKED_CAST")
        val legacyScalars = (snapWithKey["scalars"] as Map<String, Any?>) - "shutterIrqProfile"
        val legacySnap = snapWithKey + ("scalars" to legacyScalars)

        // Upgrading legacy snapshot into shutter-extirq-v1 succeeds and syncs pressed state
        val upgradedCtx = TestContext()
        upgradedCtx.pressedKeys.add("shutterHalf")
        val upgradedGpio = createGpio(upgradedCtx, shutterIrqProfile = GpioFactory.SHUTTER_IRQ_PROFILE)
        upgradedGpio.start()
        assertEquals(listOf(167 to true), upgradedCtx.irqLog)
        upgradedGpio.restoreSnapshot(legacySnap)
        assertEquals(GpioFactory.SHUTTER_IRQ_PROFILE, upgradedGpio.state()["shutterIrqProfile"])
        val activeSnap = upgradedGpio.saveSnapshot()

        // Downgrading an active shutter-extirq-v1 snapshot into disabled is strictly refused
        assertFailsWith<IllegalArgumentException> {
            disabledGpio.restoreSnapshot(activeSnap)
        }

        disabledGpio.close()
        upgradedGpio.close()
    }

    private fun createFocalPlaneGpio(
        context: TestContext,
        shutterMechanismProfile: String = GpioFactory.FOCAL_PLANE_SHUTTER_PROFILE,
    ): StatefulComponent {
        val factory = GpioFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        return assertIs<StatefulComponent>(
            factory.create(
                context,
                defaults + mapOf(
                    "profile" to "gfx100s-2.13-gpio-buttons-v4",
                    "shutterIrqProfile" to GpioFactory.SHUTTER_IRQ_PROFILE,
                    "shutterMechanismProfile" to shutterMechanismProfile,
                ),
            ),
        )
    }

    @Test
    fun focalPlaneShutterMotorsAndCurtainDrivePhotoInterruptersAndExtInts() {
        val ctx = TestContext()
        val gpio = createFocalPlaneGpio(ctx)
        val routes = gpio.interruptRoutes().flatMap { it.lines.toList() }.toSet()
        assertEquals(setOf(154, 156, 161, 167, 168, 170, 172, 177), routes)
        gpio.start()
        val mmio = gpio as TickAwareMmioDevice
        assertNull(mmio.nextDeadline())
        assertEquals(Long.MAX_VALUE, mmio.nextInterestedTick())

        // Initial cam1 GPIO(0,3)=0, cam2 GPIO(0,5)=0, curtain GPIO(0,10)=0
        assertEquals(0L, (mmio.read(0x14, 4) ushr 3) and 1L)
        assertEquals(0L, (mmio.read(0x14, 4) ushr 5) and 1L)
        assertEquals(0L, (mmio.read(0x14, 4) ushr 10) and 1L)

        // Motor 1 ON: Bank 1 reg 3 (+0x4C) bits[1:0] = 1 (DAT 0x2509064 -> 0x11da858 PWM alt-func)
        ctx.currentTick = 1_000L
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0001L)
        assertEquals(1_000L + GpioFactory.CAM_TRAVEL_TICKS, mmio.nextDeadline())
        ctx.currentTick = 1_000L + GpioFactory.CAM_TRAVEL_TICKS
        mmio.beforeInstruction(ctx.currentTick)
        assertEquals(1L, (mmio.read(0x14, 4) ushr 3) and 1L)
        assertTrue((154 to true) in ctx.irqLog)

        // Motor 1 STOP: Bank 1 reg 3 bits[1:0] = 0 -> cam stays latched HIGH, deadline cleared
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0000L)
        assertNull(mmio.nextDeadline())
        assertEquals(1L, (mmio.read(0x14, 4) ushr 3) and 1L)

        // Motor 1 ON again -> cam1 toggles HIGH->LOW -> IRQ 154 LOW, IRQ 170 HIGH
        ctx.irqLog.clear()
        ctx.currentTick += 500L
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0001L)
        ctx.currentTick += GpioFactory.CAM_TRAVEL_TICKS
        mmio.beforeInstruction(ctx.currentTick)
        assertEquals(0L, (mmio.read(0x14, 4) ushr 3) and 1L)
        assertEquals(listOf(154 to false, 170 to true), ctx.irqLog)
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0000L)

        // Motor 2 ON: Bank 7 reg 0 (+0x1C0) pins 7..8 differential (0,1) -> GPIO(0,5) toggles + IRQ 156
        ctx.irqLog.clear()
        mmio.write(7 * 64L, 4, (0x0180L shl 16) or 0x0100L)
        assertNotNull(mmio.nextDeadline())
        ctx.currentTick += GpioFactory.CAM_TRAVEL_TICKS
        mmio.beforeInstruction(ctx.currentTick)
        assertEquals(1L, (mmio.read(0x14, 4) ushr 5) and 1L)
        assertTrue((156 to true) in ctx.irqLog)
        // Brake Motor 2 (0,0) -> deadline cleared
        mmio.write(7 * 64L, 4, (0x0180L shl 16) or 0x0000L)
        assertNull(mmio.nextDeadline())

        // Curtain release event from soc-isp-cmd -> 3 optical gate pulses on GPIO(0,10) & IRQ 161/177
        ctx.irqLog.clear()
        ctx.events.publish(ComponentEvent("soc-isp-cmd", "curtainRelease", ctx.currentTick, mapOf("offset" to 0x184)))
        var highEdges = 0
        var lowEdges = 0
        repeat(GpioFactory.CURTAIN_PULSE_EDGES) {
            val dl = assertNotNull(mmio.nextDeadline())
            ctx.currentTick = dl
            mmio.beforeInstruction(ctx.currentTick)
        }
        assertNull(mmio.nextDeadline())
        for ((irq, high) in ctx.irqLog) {
            if (irq == 161 && high) highEdges++
            if (irq == 177 && high) lowEdges++
        }
        assertEquals(3, highEdges)
        assertEquals(3, lowEdges)

        // Snapshot round-trip & downgrade refusal
        val snap = gpio.saveSnapshot()
        val restoredCtx = TestContext()
        val restored = createFocalPlaneGpio(restoredCtx)
        restored.start()
        restored.restoreSnapshot(snap)
        assertEquals(gpio.saveSnapshot(), restored.saveSnapshot())

        val disabled = createFocalPlaneGpio(TestContext(), shutterMechanismProfile = "disabled")
        disabled.start()
        assertFailsWith<IllegalArgumentException> { disabled.restoreSnapshot(snap) }

        gpio.close()
        restored.close()
        disabled.close()
    }

    @Test
    fun externalShutterPortDefersSixShutterIrqsAndDelegatesMotorsAndBank0Readback() {
        val ctx = TestContext()
        var m1Running = false
        var m2Pin7 = false
        var m2Pin8 = false
        var c1 = false
        var c2 = false
        var cur = false
        var deadline: Long? = null
        val external = object : ShutterPort {
            override val enabled = true
            override val cam1High get() = c1
            override val cam2High get() = c2
            override val curtainHigh get() = cur
            override fun onMotor1PwmChanged(running: Boolean, tick: Long) {
                m1Running = running
                deadline = if (running) tick + 100L else null
            }
            override fun onMotor2HBridgeChanged(pin7High: Boolean, pin8High: Boolean, tick: Long) {
                m2Pin7 = pin7High
                m2Pin8 = pin8High
                deadline = if (pin7High != pin8High) tick + 200L else null
            }
            override fun onTgActRising(tick: Long) = Unit
            override fun onMechanicalCurtainStroke(tick: Long) = Unit
            override fun nextDeadline(): Long? = deadline
            override fun advanceToTick(tick: Long): Boolean {
                val dl = deadline ?: return false
                if (tick < dl) return false
                deadline = null
                if (m1Running) c1 = !c1
                if (m2Pin7 != m2Pin8) { c2 = !c2; cur = !cur }
                return true
            }
            override fun telemetry() = ShutterTelemetry(
                mode = ShutterMode.MECHANICAL,
                cam1High = c1,
                cam2High = c2,
                curtainHigh = cur,
                motor1Running = m1Running,
                motor2Running = m2Pin7 != m2Pin8,
                motor2Direction = if (m2Pin7 && !m2Pin8) 1 else if (!m2Pin7 && m2Pin8) -1 else 0,
                cam1Cycles = if (c1) 1L else 0L,
                cam2Cycles = if (c2) 1L else 0L,
                curtainPulses = if (cur) 1L else 0L,
                tgActCount = 0L,
                shutterCount = 1L,
                mechanicalCount = 1L,
                efcsCount = 0L,
                electronicCount = 0L,
                curtainTravelUs = 4000L,
                rollingReadoutUs = 125000L,
                readoutActive = false,
                lastActuationTick = null,
                readoutCompleteTick = null,
            )
        }
        ctx.ports.register("shutter", ShutterPort.PORT_NAME, ShutterPort::class.java, external)

        val gpio = createFocalPlaneGpio(ctx, shutterMechanismProfile = "disabled")
        assertEquals(
            setOf(167, 168),
            gpio.interruptRoutes().flatMap { it.lines.toList() }.toSet(),
            "soc-gpio must defer shutter mechanism IRQs to standalone shutter component",
        )
        gpio.start()
        val mmio = gpio as GpioFactory.Gpio
        val epochBefore = mmio.inputEpoch()

        // Drive Motor 1 PWM -> delegates to ShutterPort and advances deadline + bumps inputEpoch
        ctx.currentTick = 500L
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0001L)
        assertTrue(m1Running)
        assertEquals(600L, mmio.nextDeadline())
        ctx.currentTick = 600L
        mmio.beforeInstruction(600L)
        assertEquals(1L, (mmio.read(0x14, 4) ushr 3) and 1L)
        assertTrue(mmio.inputEpoch() > epochBefore)

        // Drive Motor 2 H-bridge -> delegates to ShutterPort and reflects cam2/curtain on Bank 0
        mmio.write(64L + 12L, 4, (0x0003L shl 16) or 0x0000L)
        mmio.write(7 * 64L, 4, (0x0180L shl 16) or 0x0080L)
        assertTrue(m2Pin7 && !m2Pin8)
        assertEquals(800L, mmio.nextDeadline())
        ctx.currentTick = 800L
        mmio.beforeInstruction(800L)
        assertEquals(1L, (mmio.read(0x14, 4) ushr 5) and 1L)
        assertEquals(1L, (mmio.read(0x14, 4) ushr 10) and 1L)

        @Suppress("UNCHECKED_CAST")
        val mechState = assertIs<Map<String, Any?>>(gpio.state()["shutterMechanism"])
        assertEquals("shutter", mechState["delegatedTo"])
        gpio.close()
    }
}
