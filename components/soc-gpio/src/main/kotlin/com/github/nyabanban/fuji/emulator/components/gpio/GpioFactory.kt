package com.github.nyabanban.fuji.emulator.components.gpio

import com.github.nyabanban.fuji.emulator.api.*
import java.util.Properties

/** Wiring is DAT SOURCE; selected external levels are HYPOTHESIS, not physical reset evidence. */
class GpioFactory : ComponentFactory {
    companion object {
        const val SHUTTER_IRQ_PROFILE = "gfx100s-2.13-shutter-extirq-v1"
        const val GLOBAL_CONTROL_PROFILE = "gfx100s-2.13-gpio-global-fff41500-v1"
        const val GLOBAL_CONTROL_PORT = "soc-gpio.global-fff41500"
        const val FOCAL_PLANE_SHUTTER_PROFILE = "gfx100s-2.13-focal-plane-shutter-v1"
        const val SHUTTER_HALF_IRQ = 167
        const val SHUTTER_FULL_IRQ = 168
        // DAT section4 0x0176c744 / section5 0x024e5d78..0x024e6358 & 0x024e502c..0x024e5a94:
        // EXTINT #N maps 1:1 to GPIO Bank 0 Pin N (151+N rising/high, 167+N falling/low).
        const val SHUTTER_CAM1_HIGH_IRQ = 154 // EXTINT #3 rising/high: GPIO(0,3) logical[36]
        const val SHUTTER_CAM1_LOW_IRQ = 170  // EXTINT #3 falling/low: GPIO(0,3) logical[36]
        const val SHUTTER_CAM2_HIGH_IRQ = 156 // EXTINT #5 rising/high: GPIO(0,5) logical[37]
        const val SHUTTER_CAM2_LOW_IRQ = 172  // EXTINT #5 falling/low: GPIO(0,5) logical[37]
        const val SHUTTER_CURTAIN_HIGH_IRQ = 161 // EXTINT #10 rising/high: GPIO(0,10)
        const val SHUTTER_CURTAIN_LOW_IRQ = 177  // EXTINT #10 falling/low: GPIO(0,10)
        // DAT 0x01229B68 registers logical interrupt 0x81 (GIC 162) on the lens-detect contact.
        const val LENS_DET_PIN = 11
        const val LENS_DET_HIGH_IRQ = 162
        // A one-action physical swap holds the contact open for 100 ms on the 54 MHz virtual clock.
        private const val LENS_EXCHANGE_TICKS = 5_400_000L
        const val SHUTTER_CAM1_PIN = 3
        const val SHUTTER_CAM2_PIN = 5
        const val SHUTTER_CURTAIN_PIN = 10
        const val CAM_TRAVEL_TICKS = 100_000L
        const val CAM_CONTINUE_TICKS = 4_000_000L
        const val CURTAIN_EDGE_TICKS = 80_000L
        const val CURTAIN_PULSE_EDGES = 6 // 3 full pulses (0->1->0->1->0->1->0) across optical gate
        const val SD_CARD_DET_BANK = 10
        const val SD_SLOT0_CARD_DET_PIN = 4
        const val SD_SLOT1_CARD_DET_PIN = 8
        // DAT (section4.bin 0x01812FA0..0x01812FB4, bank14 0x3809=0x0C bit 2 swap):
        // Front Command Dial (Dial #2) is direct 2-pin GPIO quadrature:
        // phase = ((GPIO(1,2) << 1) | GPIO(1,3)) & 3.
        // CW (keyId 0xA8 / UI 0x0044): 0 -> 2 -> 3 -> 1 -> 0; CCW (keyId 0xA7 / UI 0x0043): reverse.
        // Firmware scans dial GPIO periodically; hold each Gray phase >= 4.05M ticks (~75 ms @ 54 MHz,
        // matching calibrated button debounce hold) so every detent registers 1:1 with zero aliasing.
        const val FRONT_DIAL_BANK = 1
        const val FRONT_DIAL_PIN_A = 2
        const val FRONT_DIAL_PIN_B = 3
        const val FRONT_DIAL_STEP_TICKS = 4_050_000L
        private val FRONT_DIAL_CW_CYCLE = intArrayOf(0, 2, 3, 1)
        // DAT-verified 3-way Focus Mode selector lever (S / C / M) on GPIO Bank 7 pins 13 & 14:
        // AF-S (Single): GPIO(7,13)=1, GPIO(7,14)=0 (0x2000)
        // AF-C (Cont.):  GPIO(7,13)=1, GPIO(7,14)=1 (0x6000)
        // MF (Manual):   GPIO(7,13)=0, GPIO(7,14)=1 (0x4000)
        const val FOCUS_LEVER_BANK = 7
        const val FOCUS_LEVER_PIN_S = 13
        const val FOCUS_LEVER_PIN_C = 14
        val FOCUS_MODE_CHOICES = listOf("default", "AF-S", "AF-C", "MF")

        private val DESCRIPTOR = ComponentDescriptor("soc-gpio", "0.0.1", settings = (0..15).map { bankIndex ->
            Setting("input$bankIndex", ValueKind.INTEGER, 0L, 0.0, 65535.0)
        } + Setting("frontDialDetents", ValueKind.INTEGER, 0L, -10000.0, 10000.0, live = true,
            description = "Cumulative Front Command Dial detent steps (+CW / -CCW; auto-steps GPIO(1,2)+(1,3) Gray quadrature per DAT 0x01812FB0).") +
            Setting("focusMode", ValueKind.TEXT, "default", choices = FOCUS_MODE_CHOICES, live = true,
                description = "3-way Focus Mode selector lever override on GPIO(7,13)+GPIO(7,14): AF-S=(1,0), AF-C=(1,1), MF=(0,1); default preserves input7 and buttons.focusMode.") +
            Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", "gfx100s-2.13-gpio-buttons-v1", "gfx100s-2.13-gpio-buttons-v2", "gfx100s-2.13-gpio-buttons-v3", "gfx100s-2.13-gpio-buttons-v4", "gfx100s-2.13-gpio-buttons-v5", "gfx100s-2.13-gpio-buttons-v6"), live = false,
            description = "Optional DAT SOURCE wiring for DISP/BACK and shutter switches. v2 adds PLAY. v3 adds DELETE and rear dial press. v4 adds STILL/MOVIE selector (movieMode). v5 adds DRIVE, AF-ON, and Focus Mode lever (focusMode). v6 adds Front Dial press, Fn4, and Focus Stick center press. External levels HYPOTHESIS.") +
            Setting("directionButtonProfile",ValueKind.TEXT,"disabled",choices=listOf("disabled","gfx100s-2.13-direction-input-v1"),live=false) +
            Setting("shutterIrqProfile", ValueKind.TEXT, "disabled", choices = listOf("disabled", SHUTTER_IRQ_PROFILE), live = false,
                description = "Optional DAT SOURCE external-interrupt routing for S1 (shutterHalf -> GIC IRQ 167) and S2 (shutterFull -> GIC IRQ 168).") +
            Setting("globalControlProfile", ValueKind.TEXT, "disabled", choices = listOf("disabled", GLOBAL_CONTROL_PROFILE), live = false,
                description = "Optional Boot ROM cold-start masked 16-bit global GPIO control register at 0xFFF41500 (soc-gpio.global-fff41500).") +
            Setting("shutterMechanismProfile", ValueKind.TEXT, "disabled", choices = listOf("disabled", FOCAL_PLANE_SHUTTER_PROFILE), live = false,
                description = "Optional DAT SOURCE focal-plane shutter actuator-to-photo-interrupter feedback: Motor 1 GPIO(1,0) PWM -> GPIO(0,3) EXTINT #3 (IRQ 154/170), Motor 2 GPIO(7,7..8) H-bridge -> GPIO(0,5) EXTINT #5 (IRQ 156/172), curtain release -> GPIO(0,10) EXTINT #10 (IRQ 161/177)."))

        private fun normalizeConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { it.key to it.default }
            return (defaults + config).mapValues { (key, value) ->
                DESCRIPTOR.settings.firstOrNull { it.key == key }?.validate(value) ?: value
            }
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Gpio(context, normalizeConfig(config))
    class Gpio(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("soc-gpio", context, config), StatefulComponent, TickAwareMmioDevice, GpioOutputPort, LensMountDetectPort {
        private val registers = Array(16) { LongArray(8) }
        private val changes = Array(16) { LongArray(16) }
        private val outputListeners = HashMap<Int, MutableList<(Boolean, Long) -> Unit>>()
        private var lensReconnectTick: Long? = null
        override val replacingLens: Boolean get() = lensReconnectTick != null
        override fun breakForLensExchange() {
            setInputLevel(0, LENS_DET_PIN, true)
            lensReconnectTick = context.clock.ticks() + LENS_EXCHANGE_TICKS
        }
        private val inputBanks = LongArray(16) { (values.getValue("input$it") as Number).toLong() }
        private val inputEpochCell = LongArray(1) { 1L }
        private var closed = false
        private data class Binding(val key: String, val bank: Int, val pin: Int, val activeLow: Boolean)
        private val profile = config.getValue("profile") as String
        private val bindings: Array<Binding> = when(profile) {
            "disabled" -> emptyArray()
            "gfx100s-2.13-gpio-buttons-v1", "gfx100s-2.13-gpio-buttons-v2", "gfx100s-2.13-gpio-buttons-v3", "gfx100s-2.13-gpio-buttons-v4", "gfx100s-2.13-gpio-buttons-v5", "gfx100s-2.13-gpio-buttons-v6" -> {
                val rank = profileRank(profile)
                val resource = if(rank == 1) "gpio-buttons.properties" else "gpio-buttons-v$rank.properties"
                val keys = buildList {
                    addAll(listOf("back", "shutterHalf", "shutterFull"))
                    if (rank >= 2) add("play")
                    if (rank >= 3) addAll(listOf("delete", "rearDialPress"))
                    if (rank >= 4) add("movieMode")
                    if (rank >= 5) addAll(listOf("drive", "afOn", "focusMode"))
                    if (rank >= 6) addAll(listOf("frontDialPress", "fn4", "focusStickPress"))
                }
                val data = Properties()
                checkNotNull(javaClass.getResourceAsStream("/META-INF/fuji/$resource")) { "GPIO button wiring resource missing" }.use(data::load)
                require(data.stringPropertyNames() == keys.toSet() + "profile" && data.getProperty("profile") == profile)
                keys.map { key ->
                    val fields = data.getProperty(key).split(',')
                    require(fields.size == 3 && fields[2] in setOf("true", "false"))
                    val bank = fields[0].toInt(); val pin = fields[1].toInt()
                    require(bank in 0..15 && pin in 0..15) { "GPIO button wiring coordinates out of range" }
                    Binding(key, bank, pin, fields[2].toBoolean())
                }.toTypedArray().also { require(it.map { b -> b.bank to b.pin }.distinct().size == it.size) }
            }
            else -> error("unsupported GPIO profile")
        }
        private var buttons: ButtonPort? = null
        private data class DirectionGate(val bank:Int,val pin:Int,val activeLow:Boolean,val keys:List<String>,val masks:Set<Int>)
        private val directionProfile=config.getValue("directionButtonProfile") as String
        private val directionGate=when(directionProfile) {
            "disabled"->null
            "gfx100s-2.13-direction-input-v1"->{
                val names=listOf("up","upRight","right","downRight","down","downLeft","left","upLeft")
                val expectedMasks=listOf(1,3,2,6,4,12,8,9)
                val data=Properties()
                checkNotNull(javaClass.getResourceAsStream("/META-INF/fuji/direction-input.properties")) { "Direction input binding resource missing" }.use(data::load)
                require(data.stringPropertyNames()==names.toSet()+setOf("profile","adcChannel","gpioBank","gpioPin","gpioActiveLow","buttonKeys") && data.getProperty("profile")==directionProfile)
                require(data.getProperty("adcChannel").toInt() in 0..11 && data.getProperty("gpioActiveLow") in setOf("true","false"))
                val keys=data.getProperty("buttonKeys").split(',').also {require(it==listOf("up","right","down","left"))}
                val masks=names.mapIndexed { index,name ->
                    val fields=data.getProperty(name).split(',');require(fields.size==3)
                    val mask=fields[0].toInt();val minimum=fields[1].toInt();val maximum=fields[2].toInt()
                    require(mask==expectedMasks[index] && minimum in 0..4095 && maximum in minimum..4095);mask
                }.toSet()
                DirectionGate(data.getProperty("gpioBank").toInt(),data.getProperty("gpioPin").toInt(),data.getProperty("gpioActiveLow").toBoolean(),keys,masks).also { gate ->
                    require(
                        gate.bank in 0..15 && gate.pin in 0..15 &&
                            bindings.none { binding ->
                                binding.bank == gate.bank && binding.pin == gate.pin &&
                                    !(binding.key == "focusStickPress" && binding.activeLow == gate.activeLow)
                            }
                    ) { "Direction GPIO coordinates invalid or overlap another button" }
                }
            }
            else->error("unsupported direction input profile")
        }
        private val sharedFocusStickGateBinding: Binding? = directionGate?.let { gate ->
            bindings.singleOrNull { it.key == "focusStickPress" && it.bank == gate.bank && it.pin == gate.pin }
        }
        private val bindingsByBank: Array<Array<Binding>> = Array(16) { bank ->
            bindings.filter { it.bank == bank && it !== sharedFocusStickGateBinding }.toTypedArray()
        }
        private val shutterIrqProfile = config.getValue("shutterIrqProfile") as String
        private val shutterIrqEnabled = when (shutterIrqProfile) {
            "disabled" -> false
            SHUTTER_IRQ_PROFILE -> {
                require(bindings.isNotEmpty()) { "Shutter IRQ profile requires a GPIO button profile" }
                true
            }
            else -> error("unsupported shutter IRQ profile")
        }
        private val globalControlProfile = config.getValue("globalControlProfile") as String
        private val globalControlEnabled = when (globalControlProfile) {
            "disabled" -> false
            GLOBAL_CONTROL_PROFILE -> true
            else -> error("unsupported GPIO globalControlProfile: $globalControlProfile")
        }
        private var globalControlWord = 0L
        private val globalControlDevice: MmioDevice? = if (globalControlEnabled) {
            object : MmioDevice {
                override fun read(offset: Long, size: Int): Long {
                    check(!closed) { "soc-gpio closed" }
                    require(offset == 0L && size == 4) { "GPIO global control 0xFFF41500 requires aligned 32-bit word" }
                    return globalControlWord
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    check(!closed) { "soc-gpio closed" }
                    require(offset == 0L && size == 4 && value in 0L..0xffff_ffffL) {
                        "GPIO global control 0xFFF41500 requires aligned 32-bit u32 write"
                    }
                    val mask = (value ushr 16) and 0xffffL
                    globalControlWord = (globalControlWord and mask.inv()) or (value and mask)
                }
            }
        } else null
        private val shutterMechanismProfile = config.getValue("shutterMechanismProfile") as String
        private val shutterMechanismEnabled = when (shutterMechanismProfile) {
            "disabled" -> false
            FOCAL_PLANE_SHUTTER_PROFILE -> true
            else -> error("unsupported shutter mechanism profile")
        }
        private var interruptSink: InterruptSink? = null
        private var attachedShutter: ShutterPort? = null
        private var switchSubscription: AutoCloseable? = null
        private fun resolveExternalShutter(): ShutterPort? =
            attachedShutter ?: context.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java)?.takeIf { it.enabled }
        private var shutterHalfLineHigh = false
        private var shutterFullLineHigh = false
        private var cam1High = ((inputBanks[0] ushr SHUTTER_CAM1_PIN) and 1L) != 0L
        private var cam2High = ((inputBanks[0] ushr SHUTTER_CAM2_PIN) and 1L) != 0L
        private var curtainHigh = ((inputBanks[0] ushr SHUTTER_CURTAIN_PIN) and 1L) != 0L
        private var motor1Running = false
        private var motor2Running = false
        private var motor1Deadline: Long? = null
        private var motor2Deadline: Long? = null
        private var curtainEdgesRemaining = 0
        private var curtainDeadline: Long? = null
        private var cam1Cycles = 0L
        private var cam2Cycles = 0L
        private var curtainPulses = 0L
        private var cam1HighIrq = false
        private var cam1LowIrq = false
        private var cam2HighIrq = false
        private var cam2LowIrq = false
        private var curtainHighIrq = false
        private var curtainLowIrq = false
        private var frontDialCycleIdx = 0
        private var frontDialPendingSteps = 0
        private var frontDialActive = false
        private var frontDialDeadline: Long? = null
        private var lastFrontDialDetents = (values["frontDialDetents"] as? Number)?.toLong() ?: 0L
        init {
            context.ports.register(id, id, MmioDevice::class.java, this)
            context.ports.register(id, "$id.outputs", GpioOutputPort::class.java, this)
            context.ports.register(id, "$id.inputs", GpioInputPort::class.java, this)
            globalControlDevice?.let { device ->
                context.ports.register(id, GLOBAL_CONTROL_PORT, MmioDevice::class.java, device)
            }
        }
        private fun frontDialGrayIndexOf(phase: Int): Int = FRONT_DIAL_CW_CYCLE.indexOf(phase).takeIf { it >= 0 } ?: 0
        private fun syncFrontDialFromConfig(previousDetents: Long) {
            val detents = (values["frontDialDetents"] as? Number)?.toLong() ?: 0L
            if (detents != previousDetents) {
                val delta = (detents - previousDetents).toInt()
                if (!frontDialActive) {
                    val bA = ((inputBanks[FRONT_DIAL_BANK] ushr FRONT_DIAL_PIN_A) and 1L).toInt()
                    val bB = ((inputBanks[FRONT_DIAL_BANK] ushr FRONT_DIAL_PIN_B) and 1L).toInt()
                    frontDialCycleIdx = frontDialGrayIndexOf((bA shl 1) or bB)
                    frontDialActive = true
                }
                frontDialPendingSteps += delta
                if (frontDialDeadline == null && frontDialPendingSteps != 0) {
                    val step = if (frontDialPendingSteps > 0) 1 else -1
                    frontDialCycleIdx = (frontDialCycleIdx + step + 4) and 3
                    frontDialPendingSteps -= step
                    inputEpochCell[0]++
                    frontDialDeadline = context.clock.ticks() + FRONT_DIAL_STEP_TICKS
                }
            }
            lastFrontDialDetents = detents
        }
        override fun additionalMmioPorts(): List<String> =
            if (globalControlEnabled) listOf(GLOBAL_CONTROL_PORT) else emptyList()
        override fun interruptRoutes(): List<InterruptRoute> = buildList {
            if (context.ports.find("mount", MountPort::class.java) != null)
                add(InterruptRoute("soc-gic.lines", LENS_DET_HIGH_IRQ..LENS_DET_HIGH_IRQ))
            if (shutterIrqEnabled) add(InterruptRoute("soc-gic.lines", SHUTTER_HALF_IRQ..SHUTTER_FULL_IRQ))
            if (shutterMechanismEnabled && resolveExternalShutter() == null) {
                add(InterruptRoute("soc-gic.lines", SHUTTER_CAM1_HIGH_IRQ..SHUTTER_CAM1_HIGH_IRQ))
                add(InterruptRoute("soc-gic.lines", SHUTTER_CAM2_HIGH_IRQ..SHUTTER_CAM2_HIGH_IRQ))
                add(InterruptRoute("soc-gic.lines", SHUTTER_CURTAIN_HIGH_IRQ..SHUTTER_CURTAIN_HIGH_IRQ))
                add(InterruptRoute("soc-gic.lines", SHUTTER_CAM1_LOW_IRQ..SHUTTER_CAM1_LOW_IRQ))
                add(InterruptRoute("soc-gic.lines", SHUTTER_CAM2_LOW_IRQ..SHUTTER_CAM2_LOW_IRQ))
                add(InterruptRoute("soc-gic.lines", SHUTTER_CURTAIN_LOW_IRQ..SHUTTER_CURTAIN_LOW_IRQ))
            }
        }
        override fun readMayMutateSchedule(): Boolean = false
        override fun nextDeadline(): Long? {
            if (closed) return null
            var earliest: Long? = frontDialDeadline
            val reconnect = lensReconnectTick
            if (reconnect != null && (earliest == null || reconnect < earliest)) earliest = reconnect
            val extShutter = resolveExternalShutter()
            if (extShutter != null) {
                val ds = extShutter.nextDeadline()
                if (ds != null && (earliest == null || ds < earliest)) earliest = ds
            } else if (shutterMechanismEnabled) {
                val d1 = motor1Deadline
                if (d1 != null && (earliest == null || d1 < earliest)) earliest = d1
                val d2 = motor2Deadline
                if (d2 != null && (earliest == null || d2 < earliest)) earliest = d2
                val dc = curtainDeadline
                if (dc != null && (earliest == null || dc < earliest)) earliest = dc
            }
            return earliest
        }
        override fun nextInterestedTick(): Long = nextDeadline() ?: Long.MAX_VALUE
        private fun driveIrq(irq: Int, current: Boolean, target: Boolean): Boolean {
            if (current == target) return current
            interruptSink?.setLine(irq, target)
            return target
        }
        private fun syncCam1IrqLines() {
            if (!shutterMechanismEnabled || closed) return
            cam1HighIrq = driveIrq(SHUTTER_CAM1_HIGH_IRQ, cam1HighIrq, cam1High)
            cam1LowIrq = driveIrq(SHUTTER_CAM1_LOW_IRQ, cam1LowIrq, !cam1High)
        }
        private fun syncCam2IrqLines() {
            if (!shutterMechanismEnabled || closed) return
            cam2HighIrq = driveIrq(SHUTTER_CAM2_HIGH_IRQ, cam2HighIrq, cam2High)
            cam2LowIrq = driveIrq(SHUTTER_CAM2_LOW_IRQ, cam2LowIrq, !cam2High)
        }
        private fun syncCurtainIrqLines() {
            if (!shutterMechanismEnabled || closed) return
            curtainHighIrq = driveIrq(SHUTTER_CURTAIN_HIGH_IRQ, curtainHighIrq, curtainHigh)
            curtainLowIrq = driveIrq(SHUTTER_CURTAIN_LOW_IRQ, curtainLowIrq, !curtainHigh)
        }
        override fun beforeInstruction(tick: Long) {
            if (closed) return
            val reconnect = lensReconnectTick
            if (reconnect != null && tick >= reconnect) setInputLevel(0, LENS_DET_PIN, false)
            val fd = frontDialDeadline
            if (fd != null && tick >= fd) {
                if (frontDialPendingSteps != 0) {
                    val step = if (frontDialPendingSteps > 0) 1 else -1
                    frontDialCycleIdx = (frontDialCycleIdx + step + 4) and 3
                    frontDialPendingSteps -= step
                    inputEpochCell[0]++
                    frontDialDeadline = tick + FRONT_DIAL_STEP_TICKS
                } else {
                    frontDialDeadline = null
                }
            }
            val extShutter = resolveExternalShutter()
            if (extShutter != null) {
                if (extShutter.advanceToTick(tick)) {
                    inputEpochCell[0]++
                }
                return
            }
            if (!shutterMechanismEnabled) return
            val d1 = motor1Deadline
            if (d1 != null && tick >= d1) {
                if (motor1Running) {
                    cam1High = !cam1High
                    cam1Cycles++
                    inputEpochCell[0]++
                    syncCam1IrqLines()
                    motor1Deadline = tick + CAM_CONTINUE_TICKS
                } else {
                    motor1Deadline = null
                }
            }
            val d2 = motor2Deadline
            if (d2 != null && tick >= d2) {
                if (motor2Running) {
                    cam2High = !cam2High
                    cam2Cycles++
                    inputEpochCell[0]++
                    syncCam2IrqLines()
                    motor2Deadline = tick + CAM_CONTINUE_TICKS
                } else {
                    motor2Deadline = null
                }
            }
            val dc = curtainDeadline
            if (dc != null && tick >= dc) {
                if (curtainEdgesRemaining > 0) {
                    curtainHigh = !curtainHigh
                    curtainEdgesRemaining--
                    if (curtainHigh) curtainPulses++
                    inputEpochCell[0]++
                    syncCurtainIrqLines()
                    curtainDeadline = if (curtainEdgesRemaining > 0) tick + CURTAIN_EDGE_TICKS else null
                } else {
                    curtainDeadline = null
                }
            }
        }
        private fun syncShutterIrqLines() {
            if (!shutterIrqEnabled || closed) return
            val sink = interruptSink ?: return
            val source = buttons ?: return
            val halfHigh = source.pressed("shutterHalf")
            val fullHigh = source.pressed("shutterFull")
            if (halfHigh != shutterHalfLineHigh) {
                shutterHalfLineHigh = halfHigh
                sink.setLine(SHUTTER_HALF_IRQ, halfHigh)
            }
            if (fullHigh != shutterFullLineHigh) {
                shutterFullLineHigh = fullHigh
                sink.setLine(SHUTTER_FULL_IRQ, fullHigh)
            }
        }
        override fun start() {
            check(!closed) { "soc-gpio closed" }
            if(bindings.isNotEmpty() || directionGate!=null) buttons = checkNotNull(context.ports.find("buttons", ButtonPort::class.java)) { if(directionGate!=null)"Direction GPIO profile requires buttons port" else "GPIO button profile requires buttons port" }
            attachedShutter = context.ports.find(ShutterPort.PORT_NAME, ShutterPort::class.java)?.takeIf { it.enabled }
            val localShutterEnabled = shutterMechanismEnabled && attachedShutter == null
            if (shutterIrqEnabled || localShutterEnabled) {
                interruptSink = checkNotNull(context.ports.find("soc-gic.lines", InterruptSink::class.java)) {
                    if (shutterIrqEnabled) "Shutter IRQ profile requires soc-gic.lines" else "Shutter mechanism profile requires soc-gic.lines"
                }
                if (shutterIrqEnabled) syncShutterIrqLines()
                switchSubscription = context.events.subscribe { event ->
                    if (closed) return@subscribe
                    if (shutterIrqEnabled && event.source == "buttons" && event.kind == "switches") {
                        syncShutterIrqLines()
                    } else if (localShutterEnabled && event.source == "soc-isp-cmd" && event.kind == "curtainRelease") {
                        curtainEdgesRemaining = CURTAIN_PULSE_EDGES
                        curtainDeadline = context.clock.ticks() + CURTAIN_EDGE_TICKS
                    }
                }
            }
        }
        override fun close() {
            switchSubscription?.close()
            switchSubscription = null
            if (shutterHalfLineHigh) {
                shutterHalfLineHigh = false
                interruptSink?.setLine(SHUTTER_HALF_IRQ, false)
            }
            if (shutterFullLineHigh) {
                shutterFullLineHigh = false
                interruptSink?.setLine(SHUTTER_FULL_IRQ, false)
            }
            cam1HighIrq = driveIrq(SHUTTER_CAM1_HIGH_IRQ, cam1HighIrq, false)
            cam1LowIrq = driveIrq(SHUTTER_CAM1_LOW_IRQ, cam1LowIrq, false)
            cam2HighIrq = driveIrq(SHUTTER_CAM2_HIGH_IRQ, cam2HighIrq, false)
            cam2LowIrq = driveIrq(SHUTTER_CAM2_LOW_IRQ, cam2LowIrq, false)
            curtainHighIrq = driveIrq(SHUTTER_CURTAIN_HIGH_IRQ, curtainHighIrq, false)
            curtainLowIrq = driveIrq(SHUTTER_CURTAIN_LOW_IRQ, curtainLowIrq, false)
            motor1Running = false
            motor2Running = false
            motor1Deadline = null
            motor2Deadline = null
            curtainEdgesRemaining = 0
            curtainDeadline = null
            frontDialPendingSteps = 0
            frontDialDeadline = null
            interruptSink = null
            attachedShutter = null
            closed = true
            buttons = null
        }
        override fun validateSettings(settings: Map<String, Any>) {
            require("profile" !in settings || settings["profile"] == profile) { "GPIO profile requires restart" }
            require("directionButtonProfile" !in settings || settings["directionButtonProfile"]==directionProfile) { "Direction GPIO profile requires restart" }
            require("shutterIrqProfile" !in settings || settings["shutterIrqProfile"] == shutterIrqProfile) { "Shutter IRQ profile requires restart" }
            require("globalControlProfile" !in settings || settings["globalControlProfile"] == globalControlProfile) { "GPIO globalControlProfile requires restart" }
            require("shutterMechanismProfile" !in settings || settings["shutterMechanismProfile"] == shutterMechanismProfile) { "Shutter mechanism profile requires restart" }
        }
        private fun syncInputBanks() {
            val previous = inputBanks[0]
            for (i in 0..15) inputBanks[i] = (values.getValue("input$i") as Number).toLong()
            val lensDetect = 1L shl LENS_DET_PIN
            if ((previous xor inputBanks[0]) and lensDetect != 0L && context.ports.find("mount", MountPort::class.java) != null) {
                context.ports.find("soc-gic.lines", InterruptSink::class.java)?.setLine(
                    LENS_DET_HIGH_IRQ, inputBanks[0] and lensDetect != 0L
                )
            }
            inputEpochCell[0]++
        }
        fun inputEpoch(): Long = inputEpochCell[0]
        fun inputEpochCell(): LongArray = inputEpochCell
        fun copyInputBankWords(out: IntArray) {
            for (bank in 0..15) {
                out[bank] = read((bank shl 6).toLong() + 0x14L, 4).toInt()
            }
        }
        fun copyAllRegisterWords(out: IntArray) {
            var idx = 0
            for (bank in 0..15) {
                val base = (bank shl 6).toLong()
                for (reg in 0..7) {
                    out[idx++] = if (reg == 5) read(base + 0x14L, 4).toInt() else registers[bank][reg].toInt()
                }
            }
        }
        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
            val prevDetents = lastFrontDialDetents
            super.applySettings(settings)
            syncInputBanks()
            syncFrontDialFromConfig(prevDetents)
        }
        private fun pin(port: Int,pin: Int) { require(port in 0..15 && pin in 0..15) { "GPIO output coordinates out of range" } }
        override fun outputLevel(port: Int,pin: Int): Boolean { pin(port,pin);return registers[port][0] and (1L shl pin)!=0L }
        override fun outputSequence(port: Int,pin: Int): Long { pin(port,pin);return changes[port][pin] }
        override fun addOutputListener(port: Int, pin: Int, listener: (Boolean, Long) -> Unit): AutoCloseable {
            pin(port, pin)
            val key = (port shl 4) or pin
            val list = outputListeners.getOrPut(key) { ArrayList() }
            list.add(listener)
            return AutoCloseable {
                outputListeners[key]?.let { listeners ->
                    listeners.remove(listener)
                    if (listeners.isEmpty()) outputListeners.remove(key)
                }
            }
        }
        override fun setInputLevel(port: Int, pin: Int, high: Boolean) {
            pin(port, pin)
            if (port == 0 && pin == LENS_DET_PIN) lensReconnectTick = null
            val current = inputBanks[port]
            val mask = 1L shl pin
            val updated = if (high) current or mask else current and mask.inv()
            if (updated != current) applySettings(mapOf("input$port" to updated))
        }
        override fun read(offset: Long, size: Int): Long {
            val o = offset.toInt()
            require(size == 4 && offset >= 0L && offset < 1024L && (o and 3) == 0 && (o and 63) < 32) { "unmodeled GPIO register/width" }
            val port = o ushr 6
            val register = (o and 63) ushr 2
            if(register != 5) return registers[port][register]
            var level = inputBanks[port]
            val bankBindings = bindingsByBank[port]
            if (bankBindings.isNotEmpty()) {
                val source = checkNotNull(buttons) { "GPIO button profile has not started" }
                for (binding in bankBindings) {
                    val pressed = source.pressed(binding.key)
                    val mask = 1L shl binding.pin
                    level = if (pressed != binding.activeLow) level or mask else level and mask.inv()
                }
            }
            val gate = directionGate
            if (gate != null && gate.bank == port) {
                val source = checkNotNull(buttons) { "Direction GPIO profile has not started" }
                var keyMask = 0
                val keys = gate.keys
                for (index in keys.indices) {
                    if (source.pressed(keys[index])) keyMask = keyMask or (1 shl index)
                }
                require(keyMask == 0 || keyMask in gate.masks) { "Conflicting direction buttons" }
                val stickPressed = sharedFocusStickGateBinding != null && source.pressed(sharedFocusStickGateBinding.key)
                val mask = 1L shl gate.pin
                level = if (((keyMask != 0) || stickPressed) != gate.activeLow) level or mask else level and mask.inv()
            }
            if (frontDialActive && port == FRONT_DIAL_BANK) {
                val gray = FRONT_DIAL_CW_CYCLE[frontDialCycleIdx]
                val maskA = 1L shl FRONT_DIAL_PIN_A
                val maskB = 1L shl FRONT_DIAL_PIN_B
                level = if ((gray and 2) != 0) level or maskA else level and maskA.inv()
                level = if ((gray and 1) != 0) level or maskB else level and maskB.inv()
            }
            if (port == FOCUS_LEVER_BANK) {
                level = applyFocusLeverOverride(level)
            }
            val extShutter = resolveExternalShutter()
            if (extShutter != null && port == 0) {
                val m1 = 1L shl SHUTTER_CAM1_PIN
                level = if (extShutter.cam1High) level or m1 else level and m1.inv()
                val m2 = 1L shl SHUTTER_CAM2_PIN
                level = if (extShutter.cam2High) level or m2 else level and m2.inv()
                val mc = 1L shl SHUTTER_CURTAIN_PIN
                level = if (extShutter.curtainHigh) level or mc else level and mc.inv()
            } else if (shutterMechanismEnabled && port == 0) {
                val m1 = 1L shl SHUTTER_CAM1_PIN
                level = if (cam1High) level or m1 else level and m1.inv()
                val m2 = 1L shl SHUTTER_CAM2_PIN
                level = if (cam2High) level or m2 else level and m2.inv()
                val mc = 1L shl SHUTTER_CURTAIN_PIN
                level = if (curtainHigh) level or mc else level and mc.inv()
            }
            return level
        }
        override fun write(offset: Long, size: Int, value: Long) {
            val o = offset.toInt()
            require(size == 4 && offset >= 0L && offset < 1024L && (o and 3) == 0 && (o and 63) < 32) { "unmodeled GPIO register/width" }
            val port = o ushr 6
            val register = (o and 63) ushr 2
            require(register != 5) { "GPIO input is read-only" }
            val word = value and 0xffffffffL; val mask = word ushr 16
            val old=registers[port][register]
            registers[port][register] = if (register >= 6) word else (old and mask.inv()) or (word and mask)
            if (register == 0) {
                val changed = old xor registers[port][0]
                if (changed != 0L) {
                    for (bit in 0..15) {
                        if (changed and (1L shl bit) != 0L) {
                            val nextSeq = ++changes[port][bit]
                            val listeners = outputListeners[(port shl 4) or bit]
                            if (!listeners.isNullOrEmpty()) {
                                val level = (registers[port][0] and (1L shl bit)) != 0L
                                for (listener in listeners.toList()) {
                                    listener(level, nextSeq)
                                }
                            }
                        }
                    }
                }
            }
            val extShutter = resolveExternalShutter()
            if (extShutter != null) {
                // DAT section5 0x02509064: Motor 1 runs when GPIO(1,0) alt-func (Bank 1 reg 3 bits[1:0]) == 1 (PWM)
                if (port == 1 && register == 3) {
                    extShutter.onMotor1PwmChanged((registers[1][3] and 0x3L) == 1L, context.clock.ticks())
                }
                // DAT section5 0x02509338: Motor 2 runs when H-bridge GPIO(7,7) != GPIO(7,8)
                if (port == 7 && register == 0) {
                    extShutter.onMotor2HBridgeChanged(outputLevel(7, 7), outputLevel(7, 8), context.clock.ticks())
                }
            } else if (shutterMechanismEnabled) {
                // DAT section5 0x02509064: Motor 1 runs when GPIO(1,0) alt-func (Bank 1 reg 3 bits[1:0]) == 1 (PWM)
                if (port == 1 && register == 3) {
                    val m1Now = (registers[1][3] and 0x3L) == 1L
                    if (m1Now && !motor1Running) {
                        motor1Running = true
                        motor1Deadline = context.clock.ticks() + CAM_TRAVEL_TICKS
                    } else if (!m1Now && motor1Running) {
                        motor1Running = false
                        motor1Deadline = null
                    }
                }
                // DAT section5 0x02509338: Motor 2 runs when H-bridge GPIO(7,7) != GPIO(7,8)
                if (port == 7 && register == 0) {
                    val m2Now = outputLevel(7, 7) != outputLevel(7, 8)
                    if (m2Now && !motor2Running) {
                        motor2Running = true
                        motor2Deadline = context.clock.ticks() + CAM_TRAVEL_TICKS
                    } else if (!m2Now && motor2Running) {
                        motor2Running = false
                        motor2Deadline = null
                    }
                }
            }
        }
        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-gpio closed" }
            val snap = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "registers" to registers.map { bankRegisters -> bankRegisters.toList() },
                "outputChanges" to changes.map { bankChanges -> bankChanges.toList() },
            )
            if (globalControlEnabled) {
                snap["globalControlFff41500"] = globalControlWord
            }
            if (shutterMechanismEnabled) {
                snap["shutterMechanism"] = linkedMapOf(
                    "cam1High" to cam1High,
                    "cam2High" to cam2High,
                    "curtainHigh" to curtainHigh,
                    "motor1Running" to motor1Running,
                    "motor2Running" to motor2Running,
                    "motor1Deadline" to motor1Deadline,
                    "motor2Deadline" to motor2Deadline,
                    "curtainEdgesRemaining" to curtainEdgesRemaining,
                    "curtainDeadline" to curtainDeadline,
                    "cam1Cycles" to cam1Cycles,
                    "cam2Cycles" to cam2Cycles,
                    "curtainPulses" to curtainPulses,
                )
            }
            if (frontDialActive || frontDialPendingSteps != 0 || frontDialDeadline != null || lastFrontDialDetents != 0L) {
                snap["frontDial"] = linkedMapOf(
                    "active" to frontDialActive,
                    "cycleIdx" to frontDialCycleIdx,
                    "pendingSteps" to frontDialPendingSteps,
                    "deadline" to frontDialDeadline,
                    "lastDetents" to lastFrontDialDetents,
                )
            }
            lensReconnectTick?.let { snap["lensReconnectTick"] = it }
            return snap
        }
        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-gpio closed" }
            val baseKeys = setOf("schema", "componentId", "scalars", "registers", "outputChanges")
            val hasGlobalField = "globalControlFff41500" in data
            val hasMechField = "shutterMechanism" in data
            val hasFrontDialField = "frontDial" in data
            val expectedKeys = LinkedHashSet(baseKeys).apply {
                if ("lensReconnectTick" in data) add("lensReconnectTick")
                if (hasGlobalField) {
                    require(globalControlEnabled) { "Unexpected globalControlFff41500 in soc-gpio snapshot" }
                    add("globalControlFff41500")
                }
                if (hasMechField) {
                    require(shutterMechanismEnabled) { "Unexpected shutterMechanism in soc-gpio snapshot" }
                    add("shutterMechanism")
                }
                if (hasFrontDialField) {
                    add("frontDial")
                }
            }
            require(data.keys == expectedKeys) { "Unexpected or missing keys in soc-gpio snapshot" }
            val nextLensReconnect = if ("lensReconnectTick" in data) snapshotLong(data["lensReconnectTick"], "lensReconnectTick", 0L, Long.MAX_VALUE) else null
            require(snapshotInt(data["schema"], "schema", 1, 1) == snapshotSchemaVersion) { "Unsupported soc-gpio snapshot schema" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = data["scalars"] as? Map<*, *> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            val allSettingKeys = DESCRIPTOR.settings.map { setting -> setting.key }.toSet()
            val optionalSettingKeys = setOf("shutterIrqProfile", "globalControlProfile", "shutterMechanismProfile", "frontDialDetents", "focusMode")
            val requiredSettingKeys = allSettingKeys - optionalSettingKeys
            require(scalarsRaw.keys.containsAll(requiredSettingKeys) && allSettingKeys.containsAll(scalarsRaw.keys)) {
                "Scalar setting keys differ for $id"
            }
            val normalizedScalars = LinkedHashMap<Any?, Any?>(scalarsRaw).apply {
                putIfAbsent("shutterIrqProfile", "disabled")
                putIfAbsent("globalControlProfile", "disabled")
                putIfAbsent("shutterMechanismProfile", "disabled")
                putIfAbsent("frontDialDetents", 0L)
                putIfAbsent("focusMode", "default")
            }
            val savedFocusMode = normalizedScalars["focusMode"] as? String
                ?: throw IllegalArgumentException("Invalid focusMode in soc-gpio snapshot")
            require(savedFocusMode in FOCUS_MODE_CHOICES) {
                "Unknown focusMode in soc-gpio snapshot: $savedFocusMode"
            }
            for (bankIndex in 0..15) {
                val settingKey = "input$bankIndex"
                snapshotLong(normalizedScalars[settingKey], "scalars.$settingKey", 0L, 65535L)
            }
            if (nextLensReconnect != null) {
                val input = snapshotLong(normalizedScalars["input0"], "scalars.input0", 0L, 65535L)
                require(input and (1L shl LENS_DET_PIN) != 0L) { "Pending lens exchange requires open contacts" }
            }
            val savedFrontDialDetents = snapshotLong(normalizedScalars["frontDialDetents"], "scalars.frontDialDetents", -10000L, 10000L)
            val savedProfile = normalizedScalars["profile"] as? String ?: throw IllegalArgumentException("Invalid profile in soc-gpio snapshot")
            require(profileRank(savedProfile) <= profileRank(profile)) { "Incompatible GPIO profile downgrade in snapshot" }
            val savedDirectionProfile = normalizedScalars["directionButtonProfile"] as? String
                ?: throw IllegalArgumentException("Invalid directionButtonProfile in soc-gpio snapshot")
            require(savedDirectionProfile in listOf("disabled", "gfx100s-2.13-direction-input-v1")) {
                "Unknown directionButtonProfile in soc-gpio snapshot"
            }
            require(savedDirectionProfile == "disabled" || savedDirectionProfile == directionProfile) {
                "Incompatible directionButtonProfile in soc-gpio snapshot"
            }
            val savedShutterIrqProfile = normalizedScalars["shutterIrqProfile"] as? String
                ?: throw IllegalArgumentException("Invalid shutterIrqProfile in soc-gpio snapshot")
            require(savedShutterIrqProfile in listOf("disabled", SHUTTER_IRQ_PROFILE)) {
                "Unknown shutterIrqProfile in soc-gpio snapshot"
            }
            require(savedShutterIrqProfile == "disabled" || savedShutterIrqProfile == shutterIrqProfile) {
                "Incompatible shutterIrqProfile in soc-gpio snapshot"
            }
            val savedGlobalProfile = normalizedScalars["globalControlProfile"] as? String
                ?: throw IllegalArgumentException("Invalid globalControlProfile in soc-gpio snapshot")
            require(savedGlobalProfile in listOf("disabled", GLOBAL_CONTROL_PROFILE)) {
                "Unknown globalControlProfile in soc-gpio snapshot"
            }
            require(savedGlobalProfile == "disabled" || savedGlobalProfile == globalControlProfile) {
                "Incompatible globalControlProfile in soc-gpio snapshot"
            }
            val nextGlobalWord = if (hasGlobalField) {
                snapshotLong(data["globalControlFff41500"], "globalControlFff41500", 0L, 0xffffL)
            } else {
                require(savedGlobalProfile == "disabled") { "Missing globalControlFff41500 for enabled globalControlProfile snapshot" }
                0L
            }
            val savedShutterMechanismProfile = normalizedScalars["shutterMechanismProfile"] as? String
                ?: throw IllegalArgumentException("Invalid shutterMechanismProfile in soc-gpio snapshot")
            require(savedShutterMechanismProfile in listOf("disabled", FOCAL_PLANE_SHUTTER_PROFILE)) {
                "Unknown shutterMechanismProfile in soc-gpio snapshot"
            }
            require(savedShutterMechanismProfile == "disabled" || savedShutterMechanismProfile == shutterMechanismProfile) {
                "Incompatible shutterMechanismProfile in soc-gpio snapshot"
            }
            val rawRegisters = data["registers"] as? List<*> ?: throw IllegalArgumentException("Missing registers matrix in soc-gpio snapshot")
            require(rawRegisters.size == 16) { "soc-gpio registers requires 16 bank rows" }
            val nextRegisters = Array(16) { bankIndex ->
                val row = rawRegisters[bankIndex] as? List<*>
                    ?: throw IllegalArgumentException("Invalid register row for bank $bankIndex")
                require(row.size == 8) { "soc-gpio bank $bankIndex requires 8 register slots" }
                LongArray(8) { registerIndex ->
                    val maxWord = when (registerIndex) {
                        in 0..4 -> 0xffffL
                        5 -> 0L
                        else -> 0xffffffffL
                    }
                    snapshotLong(row[registerIndex], "registers[$bankIndex][$registerIndex]", 0L, maxWord)
                }
            }
            val rawChanges = data["outputChanges"] as? List<*>
                ?: throw IllegalArgumentException("Missing outputChanges matrix in soc-gpio snapshot")
            require(rawChanges.size == 16) { "soc-gpio outputChanges requires 16 bank rows" }
            val nextChanges = Array(16) { bankIndex ->
                val row = rawChanges[bankIndex] as? List<*>
                    ?: throw IllegalArgumentException("Invalid outputChanges row for bank $bankIndex")
                require(row.size == 16) { "soc-gpio outputChanges bank $bankIndex requires 16 pin counters" }
                LongArray(16) { pinIndex ->
                    val changeCount = snapshotLong(row[pinIndex], "outputChanges[$bankIndex][$pinIndex]", 0L, Long.MAX_VALUE)
                    val outputBit = (nextRegisters[bankIndex][0] ushr pinIndex) and 1L
                    require((changeCount and 1L) == outputBit) {
                        "GPIO bank $bankIndex pin $pinIndex output sequence parity mismatches output level"
                    }
                    changeCount
                }
            }
            val mechRaw = data["shutterMechanism"]
            var nextCam1High = ((snapshotLong(normalizedScalars["input0"], "scalars.input0", 0L, 65535L) ushr SHUTTER_CAM1_PIN) and 1L) != 0L
            var nextCam2High = ((snapshotLong(normalizedScalars["input0"], "scalars.input0", 0L, 65535L) ushr SHUTTER_CAM2_PIN) and 1L) != 0L
            var nextCurtainHigh = ((snapshotLong(normalizedScalars["input0"], "scalars.input0", 0L, 65535L) ushr SHUTTER_CURTAIN_PIN) and 1L) != 0L
            var nextMotor1Running = false
            var nextMotor2Running = false
            var nextMotor1Deadline: Long? = null
            var nextMotor2Deadline: Long? = null
            var nextCurtainEdgesRemaining = 0
            var nextCurtainDeadline: Long? = null
            var nextCam1Cycles = 0L
            var nextCam2Cycles = 0L
            var nextCurtainPulses = 0L
            if (mechRaw != null) {
                val mechMap = mechRaw as? Map<*, *> ?: throw IllegalArgumentException("shutterMechanism snapshot must be a map")
                val mechKeys = setOf(
                    "cam1High", "cam2High", "curtainHigh", "motor1Running", "motor2Running",
                    "motor1Deadline", "motor2Deadline", "curtainEdgesRemaining", "curtainDeadline",
                    "cam1Cycles", "cam2Cycles", "curtainPulses",
                )
                require(mechMap.keys == mechKeys) { "Unexpected shutterMechanism snapshot keys" }
                nextCam1High = mechMap["cam1High"] as? Boolean ?: throw IllegalArgumentException("Invalid cam1High")
                nextCam2High = mechMap["cam2High"] as? Boolean ?: throw IllegalArgumentException("Invalid cam2High")
                nextCurtainHigh = mechMap["curtainHigh"] as? Boolean ?: throw IllegalArgumentException("Invalid curtainHigh")
                nextMotor1Running = mechMap["motor1Running"] as? Boolean ?: throw IllegalArgumentException("Invalid motor1Running")
                nextMotor2Running = mechMap["motor2Running"] as? Boolean ?: throw IllegalArgumentException("Invalid motor2Running")
                nextMotor1Deadline = mechMap["motor1Deadline"]?.let { snapshotLong(it, "shutterMechanism.motor1Deadline", 0L, Long.MAX_VALUE) }
                nextMotor2Deadline = mechMap["motor2Deadline"]?.let { snapshotLong(it, "shutterMechanism.motor2Deadline", 0L, Long.MAX_VALUE) }
                nextCurtainEdgesRemaining = snapshotInt(mechMap["curtainEdgesRemaining"], "shutterMechanism.curtainEdgesRemaining", 0, 64)
                nextCurtainDeadline = mechMap["curtainDeadline"]?.let { snapshotLong(it, "shutterMechanism.curtainDeadline", 0L, Long.MAX_VALUE) }
                nextCam1Cycles = snapshotLong(mechMap["cam1Cycles"], "shutterMechanism.cam1Cycles", 0L, Long.MAX_VALUE)
                nextCam2Cycles = snapshotLong(mechMap["cam2Cycles"], "shutterMechanism.cam2Cycles", 0L, Long.MAX_VALUE)
                nextCurtainPulses = snapshotLong(mechMap["curtainPulses"], "shutterMechanism.curtainPulses", 0L, Long.MAX_VALUE)
            }
            var nextFrontDialActive = false
            var nextFrontDialCycleIdx = 0
            var nextFrontDialPendingSteps = 0
            var nextFrontDialDeadline: Long? = null
            var nextLastFrontDialDetents = savedFrontDialDetents
            val frontDialRaw = data["frontDial"]
            if (frontDialRaw != null) {
                val fdMap = frontDialRaw as? Map<*, *> ?: throw IllegalArgumentException("frontDial snapshot must be a map")
                val fdKeys = setOf("active", "cycleIdx", "pendingSteps", "deadline", "lastDetents")
                require(fdMap.keys == fdKeys) { "Unexpected frontDial snapshot keys" }
                nextFrontDialActive = fdMap["active"] as? Boolean ?: throw IllegalArgumentException("Invalid frontDial.active")
                nextFrontDialCycleIdx = snapshotInt(fdMap["cycleIdx"], "frontDial.cycleIdx", 0, 3)
                nextFrontDialPendingSteps = snapshotInt(fdMap["pendingSteps"], "frontDial.pendingSteps", -20000, 20000)
                nextFrontDialDeadline = fdMap["deadline"]?.let { snapshotLong(it, "frontDial.deadline", 0L, Long.MAX_VALUE) }
                nextLastFrontDialDetents = snapshotLong(fdMap["lastDetents"], "frontDial.lastDetents", -10000L, 10000L)
            }
            restoreScalarValues(
                normalizedScalars,
                DESCRIPTOR,
                compatibleNonLiveKeys = setOf("profile", "directionButtonProfile", "shutterIrqProfile", "globalControlProfile", "shutterMechanismProfile"),
            )
            syncInputBanks()
            for (bankIndex in 0..15) {
                nextRegisters[bankIndex].copyInto(registers[bankIndex])
                nextChanges[bankIndex].copyInto(changes[bankIndex])
            }
            globalControlWord = nextGlobalWord
            cam1High = nextCam1High
            cam2High = nextCam2High
            curtainHigh = nextCurtainHigh
            motor1Running = nextMotor1Running
            motor2Running = nextMotor2Running
            motor1Deadline = nextMotor1Deadline
            motor2Deadline = nextMotor2Deadline
            curtainEdgesRemaining = nextCurtainEdgesRemaining
            curtainDeadline = nextCurtainDeadline
            cam1Cycles = nextCam1Cycles
            cam2Cycles = nextCam2Cycles
            curtainPulses = nextCurtainPulses
            lensReconnectTick = nextLensReconnect
            frontDialActive = nextFrontDialActive
            frontDialCycleIdx = nextFrontDialCycleIdx
            frontDialPendingSteps = nextFrontDialPendingSteps
            frontDialDeadline = nextFrontDialDeadline
            lastFrontDialDetents = nextLastFrontDialDetents
            syncShutterIrqLines()
            if (shutterMechanismEnabled && mechRaw != null) {
                syncCam1IrqLines()
                syncCam2IrqLines()
                syncCurtainIrqLines()
            }
        }
        private fun profileRank(name: String): Int = when (name) {
            "disabled" -> 0
            "gfx100s-2.13-gpio-buttons-v1" -> 1
            "gfx100s-2.13-gpio-buttons-v2" -> 2
            "gfx100s-2.13-gpio-buttons-v3" -> 3
            "gfx100s-2.13-gpio-buttons-v4" -> 4
            "gfx100s-2.13-gpio-buttons-v5" -> 5
            "gfx100s-2.13-gpio-buttons-v6" -> 6
            else -> throw IllegalArgumentException("Unknown GPIO profile: $name")
        }
        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?, field: String, min: Int, max: Int): Int =
            snapshotLong(value, field, min.toLong(), max.toLong()).toInt()
        private fun applyFocusLeverOverride(bank7Level: Long): Long {
            val maskS = 1L shl FOCUS_LEVER_PIN_S
            val maskC = 1L shl FOCUS_LEVER_PIN_C
            return when (values["focusMode"] as? String ?: "default") {
                "AF-S" -> (bank7Level or maskS) and maskC.inv()
                "AF-C" -> bank7Level or maskS or maskC
                "MF" -> (bank7Level and maskS.inv()) or maskC
                else -> bank7Level
            }
        }
        private fun effectiveFocusMode(): String {
            val configured = values["focusMode"] as? String ?: "default"
            if (configured != "default") return configured
            var bank7 = inputBanks[FOCUS_LEVER_BANK]
            val source = buttons
            if (source != null) {
                for (binding in bindingsByBank[FOCUS_LEVER_BANK]) {
                    if (binding.pin == FOCUS_LEVER_PIN_S || binding.pin == FOCUS_LEVER_PIN_C) {
                        val pressed = source.pressed(binding.key)
                        val mask = 1L shl binding.pin
                        bank7 = if (pressed != binding.activeLow) bank7 or mask else bank7 and mask.inv()
                    }
                }
            }
            val sHigh = ((bank7 ushr FOCUS_LEVER_PIN_S) and 1L) != 0L
            val cHigh = ((bank7 ushr FOCUS_LEVER_PIN_C) and 1L) != 0L
            return when {
                !sHigh -> "MF"
                cHigh -> "AF-C"
                else -> "AF-S"
            }
        }
        override fun state() = (super.state() - "globalControlProfile") + mapOf("effectiveFocusMode" to effectiveFocusMode(), "registers" to registers.map { it.toList() },"outputChanges" to changes.map { it.toList() }, "electricalBindings" to when(profile) {
            "disabled" -> "unqualified"
            "gfx100s-2.13-gpio-buttons-v6" -> "DAT SOURCE: Front Dial press, Fn4, Focus Stick center press, DRIVE, AF-ON, Focus Mode lever (focusMode), STILL/MOVIE selector (movieMode), DELETE, rear dial press, DISP/BACK, PLAY and S1/S2. Selected external levels HYPOTHESIS."
            "gfx100s-2.13-gpio-buttons-v5" -> "DAT SOURCE: DRIVE, AF-ON, Focus Mode lever (focusMode), STILL/MOVIE selector (movieMode), DELETE, rear dial press, DISP/BACK, PLAY and S1/S2. Selected external levels HYPOTHESIS."
            "gfx100s-2.13-gpio-buttons-v4" -> "DAT SOURCE: STILL/MOVIE selector (movieMode), DELETE, rear dial press, DISP/BACK, PLAY and S1/S2. Selected external levels HYPOTHESIS."
            "gfx100s-2.13-gpio-buttons-v3" -> "DAT SOURCE: DELETE, rear dial press, DISP/BACK, PLAY and S1/S2. Selected external levels HYPOTHESIS."
            "gfx100s-2.13-gpio-buttons-v2" -> "DAT SOURCE: DISP/BACK, PLAY and S1/S2; selected external levels HYPOTHESIS; physical reset and real-camera validation UNKNOWN"
            else -> "DAT SOURCE: DISP/BACK and S1/S2; physical reset and real-camera validation UNKNOWN"
        })+(directionGate?.let {mapOf("directionInputBinding" to mapOf("profile" to directionProfile,"bank" to it.bank,"pin" to it.pin,"activeLow" to it.activeLow,"bindingConfidence" to "SOURCE","externalLevelConfidence" to "HYPOTHESIS","scope" to "Direction gate only; normal ADC latching and firmware scanner timing retained; no PUSH or original direction-event claim"))}?:emptyMap())+(if (shutterIrqEnabled) mapOf("shutterIrqBinding" to mapOf("profile" to shutterIrqProfile, "shutterHalfIrq" to SHUTTER_HALF_IRQ, "shutterFullIrq" to SHUTTER_FULL_IRQ, "shutterHalfLineHigh" to shutterHalfLineHigh, "shutterFullLineHigh" to shutterFullLineHigh, "confidence" to "DAT")) else emptyMap())+(if (globalControlEnabled) mapOf("globalControlProfile" to globalControlProfile, "globalControlFff41500" to globalControlWord) else emptyMap())+(resolveExternalShutter()?.let { ext ->
            val t = ext.telemetry()
            mapOf("shutterMechanism" to mapOf("profile" to "delegated:shutter", "delegatedTo" to "shutter", "mode" to t.mode.name, "cam1High" to t.cam1High, "cam2High" to t.cam2High, "curtainHigh" to t.curtainHigh, "motor1Running" to t.motor1Running, "motor2Running" to t.motor2Running, "cam1Cycles" to t.cam1Cycles, "cam2Cycles" to t.cam2Cycles, "curtainPulses" to t.curtainPulses, "shutterCount" to t.shutterCount, "confidence" to "DAT"))
        } ?: if (shutterMechanismEnabled) mapOf("shutterMechanism" to mapOf("profile" to shutterMechanismProfile, "cam1High" to cam1High, "cam2High" to cam2High, "curtainHigh" to curtainHigh, "motor1Running" to motor1Running, "motor2Running" to motor2Running, "cam1Cycles" to cam1Cycles, "cam2Cycles" to cam2Cycles, "curtainPulses" to curtainPulses, "confidence" to "DAT")) else emptyMap())
    }
}
