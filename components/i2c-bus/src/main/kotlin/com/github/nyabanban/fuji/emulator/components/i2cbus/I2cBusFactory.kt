package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.*
import kotlin.math.cos
import kotlin.math.sin

/** Explicit empty wire or bounded synthetic register peer; no camera endpoint identity/ACK is inferred. */
class I2cBusFactory : ComponentFactory {
    companion object {
        const val ORIENTATION_PORT_NAME = "i2c-bus.orientation"
        private const val NOMINAL_TICKS_PER_SECOND = 54_000_000.0
        val ORIENTATION_PRESETS = listOf("landscape", "portrait-cw", "portrait-ccw", "inverted", "custom")

        private val DESCRIPTOR=ComponentDescriptor("i2c-bus","0.0.1",settings=listOf(
            Setting("profile",ValueKind.TEXT,"disabled",choices=listOf("disabled","explicit-i2c-buses-experimental-v1"),live=false),
            Setting("inputFile",ValueKind.TEXT,"",live=false),
            Setting("eyeSensorNear",ValueKind.BOOLEAN,false,live=true,description="Simulated eye-sensor proximity (true=near/EVF, false=far/LCD)"),
            Setting("touchEnabled",ValueKind.BOOLEAN,false,live=true,description="Enable virtual rear touchscreen peer on I2C2 @ 0x55"),
            Setting("touchPressed",ValueKind.BOOLEAN,false,live=true,description="Virtual rear touchscreen finger-0 contact state"),
            Setting("touchX",ValueKind.INTEGER,0L,0.0,VirtualTouchPanel.MAX_COORD.toDouble(),live=true,description="Virtual rear touchscreen X coordinate (u16 LE)"),
            Setting("touchY",ValueKind.INTEGER,0L,0.0,VirtualTouchPanel.MAX_COORD.toDouble(),live=true,description="Virtual rear touchscreen Y coordinate (u16 LE)"),
            Setting("accelEnabled",ValueKind.BOOLEAN,false,live=true,description="Enable NXP MMA8452Q 3-axis accelerometers on I2C1 @ 0x1C (body) and I2C2 @ 0x1D (tilting LCD)"),
            Setting("orientationPreset",ValueKind.TEXT,"landscape",choices=ORIENTATION_PRESETS,live=true,description="Quick camera orientation preset (landscape/portrait-cw/portrait-ccw/inverted/custom)"),
            Setting("rollDeg",ValueKind.DECIMAL,0.0,-180.0,180.0,live=true,description="Camera roll angle in degrees (0=landscape, +90=portrait CW, -90=portrait CCW, 180=inverted)"),
            Setting("pitchDeg",ValueKind.DECIMAL,0.0,-90.0,90.0,live=true,description="Camera pitch angle in degrees (-90=down, 0=level, +90=up)"),
            Setting("shakeAmpDeg",ValueKind.DECIMAL,0.0,0.0,5.0,live=true,description="Simulated handheld micro-shake amplitude in degrees (for 5-axis IBIS testing)"),
            Setting("lcdTiltDeg",ValueKind.DECIMAL,0.0,-45.0,90.0,live=true,description="Secondary rear tilting-LCD angle relative to body in degrees (I2C2 @ 0x1D)")))
        private val LEGACY_SCALAR_KEYS = setOf("profile", "inputFile")
        private val LEGACY_EYE_SCALAR_KEYS = setOf("profile", "inputFile", "eyeSensorNear")
        private val ORIENTATION_SCALAR_KEYS = setOf(
            "accelEnabled", "orientationPreset", "rollDeg", "pitchDeg", "shakeAmpDeg", "lcdTiltDeg",
        )

        /**
         * Maps camera attitude (`rollDeg`, `pitchDeg`) into GFX100S PCB-mounted `MMA8452Q` / `biscom bank=7`
         * sensor axes `(sx, sy, sz)` verified against `GXUP0004.DAT` `levelsens` (`0x01216c40..0x01217df8`)
         * and `bank14[0x8a2b..0x8a33]` mounting matrix `M = [[0,-1,0],[-1,0,0],[0,0,1]]`
         * (`filtX = -sy`, `filtY = -sx`, `filtZ = +sz`):
         * - Landscape (`roll=0°, pitch=0°`) -> sensor `(0, -1g, 0)` -> `filt=(+1g, 0, 0)` -> `roll=0.0°`
         * - Portrait CW (`roll=+90°, pitch=0°`) -> sensor `(+1g, 0, 0)` -> `filt=(0, -1g, 0)` -> `roll=90.0°`
         * - Inverted (`roll=180°, pitch=0°`) -> sensor `(0, +1g, 0)` -> `filt=(-1g, 0, 0)` -> `roll=180.0°`
         * - Portrait CCW (`roll=-90°, pitch=0°`) -> sensor `(-1g, 0, 0)` -> `filt=(0, +1g, 0)` -> `roll=270.0°`
         */
        internal fun attitudeToGravityG(rollDeg: Double, pitchDeg: Double): CameraGravityVector {
            val rollRad = Math.toRadians(rollDeg)
            val pitchRad = Math.toRadians(pitchDeg)
            val cosPitch = cos(pitchRad)
            val filtX = cosPitch * cos(rollRad)
            val filtY = -cosPitch * sin(rollRad)
            // Verified live on GXUP0004.DAT levelsens: positive pitchDeg (+25° nose-up) -> filtZ = -sin(pitch) -> pitch = 25.0°
            val filtZ = -sin(pitchRad)
            // Inverse of bank14[0x8a2b..0x8a33] mounting matrix M = [[0,-1,0],[-1,0,0],[0,0,1]] (M^-1 = M)
            return CameraGravityVector(xG = -filtY, yG = -filtX, zG = filtZ)
        }

        internal fun lcdAttitudeToGravityG(rollDeg: Double, pitchDeg: Double): CameraGravityVector {
            val rollRad = Math.toRadians(rollDeg)
            val pitchRad = Math.toRadians(pitchDeg)
            val cosPitch = cos(pitchRad)
            val filtX = cosPitch * cos(rollRad)
            val filtY = -cosPitch * sin(rollRad)
            val filtZ = -sin(pitchRad)
            // Inverse of tilting-LCD MMA8452Q (I2C2 @ 0x1D) bank14[0x89d6..0x89de] mounting matrix M_lcd = [[-1,0,0],[0,1,0],[0,0,-1]]
            return CameraGravityVector(xG = -filtX, yG = filtY, zG = -filtZ)
        }

        internal fun presetAngles(preset: String): Pair<Double, Double>? = when (preset) {
            "landscape" -> 0.0 to 0.0
            "portrait-cw" -> 90.0 to 0.0
            "portrait-ccw" -> -90.0 to 0.0
            "inverted" -> 180.0 to 0.0
            else -> null
        }

        internal fun handShakeDeltaAtTick(shakeAmpDeg: Double, tick: Long): Triple<Double, Double, Double> {
            if (shakeAmpDeg <= 1e-6) return Triple(0.0, 0.0, 0.0)
            val tSec = tick.toDouble() / NOMINAL_TICKS_PER_SECOND
            // Deterministic physiological handheld tremor spectrum (~2.7 Hz roll, ~3.4 Hz pitch, ~2.1 Hz yaw)
            val rollD = shakeAmpDeg * 0.45 * sin(2.0 * Math.PI * 2.7 * tSec)
            val pitchD = shakeAmpDeg * sin(2.0 * Math.PI * 3.4 * tSec + 0.7)
            val yawD = shakeAmpDeg * 0.85 * sin(2.0 * Math.PI * 2.1 * tSec + 1.9)
            return Triple(rollD, pitchD, yawD)
        }
        private fun normalizeConfig(config: Map<String, Any>): Map<String, Any> {
            val defaults = DESCRIPTOR.settings.associate { setting -> setting.key to setting.default }
            return (defaults + config).mapValues { (key, value) ->
                DESCRIPTOR.settings.firstOrNull { setting -> setting.key == key }?.validate(value) ?: value
            }
        }
        private fun snapshotLong(value: Any?,field: String,min: Long,max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Expected integer for $field" }
            val number=(value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $field")
            require(number in min..max) { "Out-of-range integer for $field" }
            return number
        }
        private fun snapshotInt(value: Any?,field: String,min: Int,max: Int): Int =
            snapshotLong(value,field,min.toLong(),max.toLong()).toInt()
    }
    override val descriptor=DESCRIPTOR
    override fun create(context: ComponentContext,config: Map<String,Any>): HardwareComponent=Buses(context,normalizeConfig(config))
    private class Buses(context: ComponentContext,config: Map<String,Any>) : ScalarComponent("i2c-bus",context,config),StatefulComponent,CameraOrientationPort {
        private val profile=config.getValue("profile") as String
        private val enabled=profile!="disabled"
        private val buses=ArrayList<Bus>()
        private var closed=false

        private val externalOrientation: CameraOrientationControlPort?
            get() = context.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java) as? CameraOrientationControlPort
        private val externalEyeSensor: EyeSensorPort?
            get() = context.ports.find(EyeSensorPort.PORT_NAME, EyeSensorPort::class.java)
        private val externalTouch: RearTouchPort?
            get() = context.ports.find(RearTouchPort.PORT_NAME, RearTouchPort::class.java)

        override val accelEnabled: Boolean get() = externalOrientation?.accelEnabled ?: (values.getValue("accelEnabled") as Boolean)
        override val orientationPreset: String get() = externalOrientation?.orientationPreset ?: (values.getValue("orientationPreset") as String)
        override val rollDeg: Double get() = externalOrientation?.rollDeg ?: (values.getValue("rollDeg") as Number).toDouble()
        override val pitchDeg: Double get() = externalOrientation?.pitchDeg ?: (values.getValue("pitchDeg") as Number).toDouble()
        override val shakeAmpDeg: Double get() = externalOrientation?.shakeAmpDeg ?: (values.getValue("shakeAmpDeg") as Number).toDouble()
        override val lcdTiltDeg: Double get() = externalOrientation?.lcdTiltDeg ?: (values.getValue("lcdTiltDeg") as Number).toDouble()

        override fun handShakeDeltaDeg(tick: Long): Triple<Double, Double, Double> =
            externalOrientation?.handShakeDeltaDeg(tick) ?: handShakeDeltaAtTick(shakeAmpDeg, tick)

        override fun bodyGravityG(tick: Long): CameraGravityVector =
            externalOrientation?.bodyGravityG(tick) ?: run {
                val (dRoll, dPitch, _) = handShakeDeltaAtTick(shakeAmpDeg, tick)
                attitudeToGravityG(rollDeg + dRoll, (pitchDeg + dPitch).coerceIn(-90.0, 90.0))
            }

        override fun lcdGravityG(tick: Long): CameraGravityVector =
            externalOrientation?.lcdGravityG(tick) ?: run {
                val (dRoll, dPitch, _) = handShakeDeltaAtTick(shakeAmpDeg, tick)
                lcdAttitudeToGravityG(rollDeg + dRoll, (pitchDeg + lcdTiltDeg + dPitch).coerceIn(-90.0, 90.0))
            }

        init {
            val initPreset = values.getValue("orientationPreset") as String
            if ("rollDeg" !in config && "pitchDeg" !in config) {
                presetAngles(initPreset)?.let { (r, p) ->
                    values = values + mapOf("rollDeg" to r, "pitchDeg" to p)
                }
            }
            val file=config.getValue("inputFile") as String
            val initialEyeNear=config.getValue("eyeSensorNear") as Boolean
            val initialTouchEnabled=config.getValue("touchEnabled") as Boolean
            val initialTouchPressed=config.getValue("touchPressed") as Boolean
            val initialTouchX=(config.getValue("touchX") as Number).toInt()
            val initialTouchY=(config.getValue("touchY") as Number).toInt()
            val initialAccelEnabled=config.getValue("accelEnabled") as Boolean
            val irqSinkProvider: () -> InterruptSink? = { context.ports.find("soc-gic.lines", InterruptSink::class.java) }
            val gpioOutputProvider: () -> GpioOutputPort? = { context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java) }
            val bodyGravitySupplier: () -> CameraGravityVector = { bodyGravityG(context.clock.ticks()) }
            val lcdGravitySupplier: () -> CameraGravityVector = { lcdGravityG(context.clock.ticks()) }
            context.ports.register(id, ORIENTATION_PORT_NAME, CameraOrientationPort::class.java, this)
            require(enabled==file.isNotBlank())
            if(enabled) {
                val data=context.readData(file);require(data.keys==setOf("buses"));val rows=data["buses"];require(rows is List<*> && rows.size in 1..3)
                val indexes=HashSet<Int>()
                rows.forEach { raw->
                    require(raw is Map<*,*>);val index=integer(raw["index"],2);require(indexes.add(index)) { "duplicate I2C bus index" }
                    val kind=raw["kind"];require(kind in listOf("empty","register", EyeTransfers.KIND, LiveEyeSensor.KIND, VirtualTouchPanel.KIND))
                    val cells=IntArray(256) { -1 };var address: Int?=null
                    var eye: EyeTransfers? = null
                    var liveEye: LiveEyeSensor? = null
                    var liveTouch: VirtualTouchPanel? = null
                    var accel: Mma8452qDevice? = null
                    if (kind == EyeTransfers.KIND) {
                        require(raw.keys == setOf("index","kind","address","transfers"))
                        address = integer(raw["address"], 0x77)
                        require(index == 1 && address == 0x39)
                        eye = EyeTransfers(raw["transfers"])
                    } else if (kind == LiveEyeSensor.KIND) {
                        require(raw.keys == setOf("index","kind","address"))
                        address = integer(raw["address"], 0x77)
                        require(index == 1 && address == LiveEyeSensor.ADDRESS7)
                        liveEye = LiveEyeSensor(initialEyeNear)
                    } else if (kind == VirtualTouchPanel.KIND) {
                        require(raw.keys == setOf("index","kind","address"))
                        address = integer(raw["address"], 0x77)
                        require(index == 2 && address == VirtualTouchPanel.ADDRESS7)
                        liveTouch = VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).apply {
                            if (initialTouchPressed || initialTouchX != 0 || initialTouchY != 0) {
                                setSingleTouch(initialTouchPressed, initialTouchX, initialTouchY)
                            }
                        }
                    } else if(kind=="empty") {
                        require(raw.keys==setOf("index","kind"))
                        if(index == 1 && initialEyeNear) liveEye = LiveEyeSensor(true)
                        if(index == 2 && initialTouchEnabled) {
                            liveTouch = VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).apply {
                                if (initialTouchPressed || initialTouchX != 0 || initialTouchY != 0) {
                                    setSingleTouch(initialTouchPressed, initialTouchX, initialTouchY)
                                }
                            }
                        }
                    } else {
                        require(raw.keys==setOf("index","kind","address","registers"));address=integer(raw["address"],0x77);require(address>=8)
                        val registers=raw["registers"];require(registers is Map<*,*> && registers.size in 1..128)
                        registers.forEach { (key,value)->require(key is String && key.matches(Regex("0|[1-9][0-9]{0,2}")));val at=key.toInt();require(at<=255);cells[at]=integer(value,255) }
                    }
                    val accelSupplier = when (index) {
                        1 -> bodyGravitySupplier
                        2 -> lcdGravitySupplier
                        else -> null
                    }
                    if (initialAccelEnabled && eye == null && kind != "register") {
                        if (index == 1) accel = Mma8452qDevice(Mma8452qDevice.ADDRESS_SA0_LOW, bodyGravitySupplier)
                        if (index == 2) accel = Mma8452qDevice(Mma8452qDevice.ADDRESS_SA0_HIGH, lcdGravitySupplier)
                    }
                    val bus=Bus(index,kind as String,address,cells,eye,liveEye,liveTouch,accel,irqSinkProvider,gpioOutputProvider,accelSupplier);buses.add(bus);context.ports.register(id,"$id.bus$index",I2cBusPort::class.java,bus)
                }
            }
        }
        override fun start() {
            check(!closed) { "i2c-bus closed" }
            buses.forEach { it.attachTouchResetListener() }
        }
        override fun interruptRoutes(): List<InterruptRoute> =
            if (externalTouch == null && enabled && ((values["touchEnabled"] as? Boolean) == true || buses.any { it.hasConfiguredTouch() })) {
                listOf(InterruptRoute("soc-gic.lines", VirtualTouchPanel.IRQ_LINE..VirtualTouchPanel.IRQ_LINE))
            } else {
                emptyList()
            }
        override fun applySettings(settings: Map<String, Any>) {
            val expanded = LinkedHashMap<String, Any>()
            val preset = settings["orientationPreset"] as? String
            if (preset != null && "rollDeg" !in settings && "pitchDeg" !in settings) {
                presetAngles(preset)?.let { (r, p) ->
                    expanded["rollDeg"] = r
                    expanded["pitchDeg"] = p
                }
            }
            expanded.putAll(settings)
            if (("rollDeg" in settings || "pitchDeg" in settings) && "orientationPreset" !in settings) {
                val r = (expanded["rollDeg"] as? Number)?.toDouble() ?: rollDeg
                val p = (expanded["pitchDeg"] as? Number)?.toDouble() ?: pitchDeg
                val inferred = when {
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r) < 0.5 -> "landscape"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r - 90.0) < 0.5 -> "portrait-cw"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(r + 90.0) < 0.5 -> "portrait-ccw"
                    kotlin.math.abs(p) < 0.5 && kotlin.math.abs(kotlin.math.abs(r) - 180.0) < 0.5 -> "inverted"
                    else -> "custom"
                }
                expanded["orientationPreset"] = inferred
            }
            super.applySettings(expanded)
            if ("eyeSensorNear" in expanded) {
                val near = expanded.getValue("eyeSensorNear") as Boolean
                val extEye = externalEyeSensor
                if (extEye != null) {
                    extEye.setNear(near)
                } else {
                    buses.firstOrNull { it.supportsLiveEye() }?.setLiveEyeNear(near)
                }
            }
            if ("touchEnabled" in expanded || "touchPressed" in expanded || "touchX" in expanded || "touchY" in expanded) {
                val touchEnabled = values.getValue("touchEnabled") as Boolean
                val touchPressed = values.getValue("touchPressed") as Boolean
                val touchX = (values.getValue("touchX") as Number).toInt()
                val touchY = (values.getValue("touchY") as Number).toInt()
                val extTouch = externalTouch
                if (extTouch != null) {
                    extTouch.updateSingleTouch(touchEnabled, touchPressed, touchX, touchY)
                } else {
                    buses.firstOrNull { it.supportsLiveTouch() }?.updateLiveTouch(touchEnabled, touchPressed, touchX, touchY)
                }
            }
            val orientationUpdates = expanded.filterKeys { it in ORIENTATION_SCALAR_KEYS }
            if (orientationUpdates.isNotEmpty()) {
                val extOrient = externalOrientation
                if (extOrient != null) {
                    extOrient.applyOrientationPatch(orientationUpdates)
                } else if ("accelEnabled" in expanded) {
                    val accelOn = values.getValue("accelEnabled") as Boolean
                    buses.forEach { if (it.supportsAccel()) it.setAccelEnabled(accelOn) }
                }
            }
        }
        private fun integer(v: Any?,max: Int): Int { require(v is Byte || v is Short || v is Int || v is Long);val n=(v as Number).toLong();require(n in 0..max.toLong());return n.toInt() }
        private class Bus(
            val index: Int,
            private val kind: String,
            private val address7: Int?,
            private val cells: IntArray,
            private val eye: EyeTransfers?,
            initialLiveEye: LiveEyeSensor? = null,
            initialLiveTouch: VirtualTouchPanel? = null,
            initialAccel: Mma8452qDevice? = null,
            private val irqSinkProvider: (() -> InterruptSink?)? = null,
            private val gpioOutputProvider: (() -> GpioOutputPort?)? = null,
            private val accelGravitySupplier: (() -> CameraGravityVector)? = null,
        ) : I2cBusPort {
            private val initial=cells.copyOf();private var owner: String?=null;private var selected=false;private var pointer: Int?=null;private var expectPointer=false
            private val externalSlaves = linkedMapOf<Int, I2cSlaveDevice>()
            private var liveEye: LiveEyeSensor? = initialLiveEye
            private var liveTouch: VirtualTouchPanel? = initialLiveTouch
            private var accel: Mma8452qDevice? = initialAccel
            override fun registerSlave(device: I2cSlaveDevice) {
                require(device.address7 in 0x08..0x77) {
                    "External I2C slave address out of 7-bit range: 0x${device.address7.toString(16)}"
                }
                externalSlaves[device.address7] = device
            }
            override fun unregisterSlave(address7: Int, device: I2cSlaveDevice?) {
                val existing = externalSlaves[address7] ?: return
                if (device == null || existing === device) {
                    existing.stop()
                    externalSlaves.remove(address7)
                }
            }
            fun supportsLiveEye(): Boolean = eye == null && index == 1 && (kind == "empty" || kind == LiveEyeSensor.KIND)
            fun supportsLiveTouch(): Boolean = eye == null && index == 2 && (kind == "empty" || kind == VirtualTouchPanel.KIND)
            fun hasConfiguredTouch(): Boolean = kind == VirtualTouchPanel.KIND
            fun attachTouchResetListener() { liveTouch?.attachResetListener() }
            fun detachTouchResetListener() { liveTouch?.detachResetListener() }
            fun supportsAccel(): Boolean = eye == null && kind != "register" && (index == 1 || index == 2)
            fun accelAddress7(): Int = if (index == 1) Mma8452qDevice.ADDRESS_SA0_LOW else Mma8452qDevice.ADDRESS_SA0_HIGH
            fun setAccelEnabled(enabled: Boolean) {
                if (!supportsAccel()) return
                if (!enabled) {
                    accel?.stop()
                    accel = null
                } else if (accel == null) {
                    val supplier = accelGravitySupplier ?: { CameraGravityVector(1.0, 0.0, 0.0) }
                    accel = Mma8452qDevice(accelAddress7(), supplier)
                }
            }
            fun setLiveEyeNear(near: Boolean) {
                if (!supportsLiveEye()) return
                val active = liveEye
                if (active == null) {
                    liveEye = LiveEyeSensor(near)
                } else {
                    active.setNear(near)
                }
            }
            fun updateLiveTouch(enabled: Boolean, pressed: Boolean, x: Int, y: Int) {
                if (!supportsLiveTouch()) return
                if (!enabled && kind == "empty") {
                    liveTouch?.detachResetListener()
                    liveTouch?.releaseAndClearIrq()
                    liveTouch = null
                    return
                }
                val active = liveTouch ?: VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).also {
                    liveTouch = it
                    it.attachResetListener()
                }
                if (!enabled) {
                    active.releaseAndClearIrq()
                } else {
                    active.setSingleTouch(pressed, x, y)
                }
            }
            override fun connect(owner: String): I2cConnection {
                if (eye != null) return eye.connect(owner)
                require(owner.isNotBlank());check(this.owner==null) { "I2C bus already owned" };this.owner=owner
                return object: I2cConnection {
                    private var closed=false
                    private var activeSlave: Int = SLAVE_NONE
                    private var activeExternal: I2cSlaveDevice? = null
                    private fun live() { check(!closed && this@Bus.owner==owner) { "closed I2C connection" } }
                    private fun byte(value: Int) { require(value in 0..255) }
                    override fun address(byte: Int): Boolean {
                        live(); byte(byte)
                        val addr7 = byte ushr 1
                        val ext = externalSlaves[addr7]
                        if (ext != null) {
                            if (activeExternal !== ext) activeExternal?.stop()
                            accel?.stop(); liveEye?.stop(); liveTouch?.stop(); selected = false; expectPointer = false
                            val ack = ext.address(byte)
                            activeExternal = if (ack) ext else null
                            activeSlave = if (ack) SLAVE_EXTERNAL else SLAVE_NONE
                            return ack
                        }
                        activeExternal?.stop()
                        activeExternal = null
                        val activeAccel = accel
                        if (activeAccel != null && addr7 == activeAccel.address7) {
                            liveEye?.stop(); liveTouch?.stop(); selected = false; expectPointer = false
                            val ack = activeAccel.address(byte)
                            activeSlave = if (ack) SLAVE_ACCEL else SLAVE_NONE
                            return ack
                        }
                        val activeEye = liveEye
                        if (activeEye != null && addr7 == LiveEyeSensor.ADDRESS7) {
                            accel?.stop(); liveTouch?.stop(); selected = false; expectPointer = false
                            val ack = activeEye.address(byte)
                            activeSlave = if (ack) SLAVE_EYE else SLAVE_NONE
                            return ack
                        }
                        val activeTouch = liveTouch
                        if (activeTouch != null && addr7 == VirtualTouchPanel.ADDRESS7) {
                            accel?.stop(); liveEye?.stop(); selected = false; expectPointer = false
                            val ack = activeTouch.address(byte)
                            activeSlave = if (ack) SLAVE_TOUCH else SLAVE_NONE
                            return ack
                        }
                        accel?.stop(); liveEye?.stop(); liveTouch?.stop()
                        selected=kind=="register" && address7==addr7;expectPointer=selected && byte and 1==0
                        activeSlave = if (selected) SLAVE_REGISTER else SLAVE_NONE
                        return selected
                    }
                    override fun checkWrite(byte: Int) {
                        live(); byte(byte)
                        when (activeSlave) {
                            SLAVE_EXTERNAL -> { checkNotNull(activeExternal).checkWrite(byte); return }
                            SLAVE_ACCEL -> { checkNotNull(accel).checkWrite(byte); return }
                            SLAVE_EYE -> { checkNotNull(liveEye).checkWrite(byte); return }
                            SLAVE_TOUCH -> { checkNotNull(liveTouch).checkWrite(byte); return }
                        }
                        val cell=if(expectPointer) byte else pointer;require(selected && kind=="register" && cell!=null && cells[cell]>=0) { "I2C write/pointer to undeclared peer register" }
                    }
                    override fun write(byte: Int) {
                        live(); byte(byte)
                        when (activeSlave) {
                            SLAVE_EXTERNAL -> { checkNotNull(activeExternal).write(byte); return }
                            SLAVE_ACCEL -> { checkNotNull(accel).write(byte); return }
                            SLAVE_EYE -> { checkNotNull(liveEye).write(byte); return }
                            SLAVE_TOUCH -> { checkNotNull(liveTouch).write(byte); return }
                        }
                        checkWrite(byte);if(expectPointer) {pointer=byte;expectPointer=false} else {val p=checkNotNull(pointer);cells[p]=byte;pointer=(p+1) and 255}
                    }
                    override fun peekRead(): Int {
                        live()
                        when (activeSlave) {
                            SLAVE_EXTERNAL -> return checkNotNull(activeExternal).peekRead()
                            SLAVE_ACCEL -> return checkNotNull(accel).peekRead()
                            SLAVE_EYE -> return checkNotNull(liveEye).peekRead()
                            SLAVE_TOUCH -> return checkNotNull(liveTouch).peekRead()
                        }
                        val p=pointer;require(selected && kind=="register" && p!=null && cells[p]>=0) { "I2C read from undeclared peer register" };return cells[p]
                    }
                    override fun consumeRead(expected: Int) {
                        live()
                        when (activeSlave) {
                            SLAVE_EXTERNAL -> { checkNotNull(activeExternal).consumeRead(expected); return }
                            SLAVE_ACCEL -> { checkNotNull(accel).consumeRead(expected); return }
                            SLAVE_EYE -> { checkNotNull(liveEye).consumeRead(expected); return }
                            SLAVE_TOUCH -> { checkNotNull(liveTouch).consumeRead(expected); return }
                        }
                        require(peekRead()==expected) { "I2C peer read changed before completion" };pointer=(checkNotNull(pointer)+1) and 255
                    }
                    override fun stop() {
                        live()
                        activeExternal?.stop()
                        activeExternal = null
                        accel?.stop()
                        liveEye?.stop()
                        liveTouch?.stop()
                        activeSlave = SLAVE_NONE
                        selected=false;expectPointer=false
                    }
                    override fun close() { if(!closed) {stop();closed=true;this@Bus.owner=null} }
                }
            }
            fun state()=if (eye != null) saveSnapshot() + mapOf("owner" to eye.owner,"confidence" to "HYPOTHESIS") else mapOf("index" to index,"kind" to kind,"address7" to address7,"registers" to (0..255).filter {cells[it]>=0}.associate {it.toString() to cells[it]},
                "initialRegisters" to (0..255).filter {initial[it]>=0}.associate {it.toString() to initial[it]},"selected" to selected,"pointer" to pointer,"expectPointer" to expectPointer,"owner" to owner) +
                (liveEye?.let { mapOf("liveEye" to it.state()) } ?: emptyMap()) +
                (liveTouch?.let { mapOf("liveTouch" to it.state()) } ?: emptyMap()) +
                (accel?.let { mapOf("accel" to it.state()) } ?: emptyMap()) +
                (if (externalSlaves.isNotEmpty()) mapOf("externalSlaves" to externalSlaves.keys.sorted()) else emptyMap())

            internal class ParsedSnapshot(
                val nextCells: IntArray,
                val nextSelected: Boolean,
                val nextPointer: Int?,
                val nextExpectPointer: Boolean,
                val eyeCursor: Int = 0,
                val parsedLiveEye: LiveEyeSensor.ParsedSnapshot? = null,
                val parsedLiveTouch: VirtualTouchPanel.ParsedSnapshot? = null,
                val parsedAccel: Mma8452qDevice.ParsedSnapshot? = null,
            )

            fun saveSnapshot(): Map<String,Any?> = if (eye != null) mapOf(
                "index" to index, "kind" to kind, "address7" to address7,
                "transfers" to eye.transfers, "cursor" to eye.cursor
            ) else linkedMapOf<String, Any?>(
                "index" to index,
                "kind" to kind,
                "address7" to address7,
                "selected" to selected,
                "pointer" to pointer,
                "expectPointer" to expectPointer,
                "registers" to (0..255).filter { offset -> cells[offset]>=0 }.associate { offset -> offset.toString() to cells[offset] },
            ).apply {
                liveEye?.let { put("liveEye", it.saveSnapshot()) }
                liveTouch?.let { put("liveTouch", it.saveSnapshot()) }
                accel?.let { put("accel", it.saveSnapshot()) }
            }

            fun parseSnapshot(row: Map<*,*>,strictRegisters: Boolean): ParsedSnapshot {
                if (row["kind"] == EyeTransfers.KIND) {
                    require(eye != null && row.keys == setOf("index","kind","address7","transfers","cursor"))
                    require(snapshotInt(row["index"],"index",1,1) == index && snapshotInt(row["address7"],"address7",57,57) == address7)
                    val saved = EyeTransfers(row["transfers"])
                    require(eye.transfers.take(saved.transfers.size) == saved.transfers) { "Eye sensor transfer input differs" }
                    return ParsedSnapshot(initial.copyOf(),false,null,false,snapshotInt(row["cursor"],"cursor",0,saved.size))
                }
                val expectedKeys=setOf("index","kind","address7","selected","pointer","expectPointer","registers")
                val hasLiveEye = "liveEye" in row
                val hasLiveTouch = "liveTouch" in row
                val hasAccel = "accel" in row
                val allowedKeys = expectedKeys +
                    (if (hasLiveEye && supportsLiveEye()) setOf("liveEye") else emptySet()) +
                    (if (hasLiveTouch && supportsLiveTouch()) setOf("liveTouch") else emptySet()) +
                    (if (hasAccel && supportsAccel()) setOf("accel") else emptySet())
                require(row.keys == allowedKeys) { "Invalid keys in I2C bus $index snapshot" }
                require(snapshotInt(row["index"],"bus.index",0,2)==index) { "I2C bus index mismatch" }
                val savedKind=row["kind"] as? String ?: throw IllegalArgumentException("Invalid kind for I2C bus $index")
                require(savedKind in setOf("empty","register", LiveEyeSensor.KIND, VirtualTouchPanel.KIND)) { "Unknown I2C bus kind: $savedKind" }
                val savedAddress7=row["address7"]?.let { addr -> snapshotInt(addr,"bus[$index].address7",8,0x77) }
                val nextSelected=row["selected"] as? Boolean ?: throw IllegalArgumentException("Invalid selected boolean for bus $index")
                val nextPointer=row["pointer"]?.let { ptr -> snapshotInt(ptr,"bus[$index].pointer",0,255) }
                val nextExpectPointer=row["expectPointer"] as? Boolean ?: throw IllegalArgumentException("Invalid expectPointer boolean for bus $index")
                val rawRegisters=row["registers"] as? Map<*,*> ?: throw IllegalArgumentException("Missing registers map for bus $index")
                val parsedAccel = if (hasAccel) Mma8452qDevice(accelAddress7()).parseSnapshot(row["accel"]) else null
                if(savedKind=="empty") {
                    require(savedAddress7==null && !nextSelected && nextPointer==null && !nextExpectPointer && rawRegisters.isEmpty()) {
                        "Empty I2C bus $index snapshot contains active register or selection state"
                    }
                    if(strictRegisters) require(kind=="empty") { "I2C bus $index kind differs from live configuration" }
                    val parsedLive = if (hasLiveEye) LiveEyeSensor().parseSnapshot(row["liveEye"]) else null
                    val parsedTouch = if (hasLiveTouch) VirtualTouchPanel().parseSnapshot(row["liveTouch"]) else null
                    return ParsedSnapshot(initial.copyOf(),false,null,false,parsedLiveEye=parsedLive,parsedLiveTouch=parsedTouch,parsedAccel=parsedAccel)
                }
                if (savedKind == LiveEyeSensor.KIND) {
                    require(kind == LiveEyeSensor.KIND && savedAddress7 == LiveEyeSensor.ADDRESS7 && hasLiveEye && !hasLiveTouch && !nextSelected && nextPointer == null && !nextExpectPointer && rawRegisters.isEmpty()) {
                        "Invalid eye-sensor-live-v1 I2C bus $index snapshot"
                    }
                    val parsedLive = LiveEyeSensor().parseSnapshot(row["liveEye"])
                    return ParsedSnapshot(initial.copyOf(),false,null,false,parsedLiveEye=parsedLive,parsedAccel=parsedAccel)
                }
                if (savedKind == VirtualTouchPanel.KIND) {
                    require(kind == VirtualTouchPanel.KIND && savedAddress7 == VirtualTouchPanel.ADDRESS7 && hasLiveTouch && !hasLiveEye && !nextSelected && nextPointer == null && !nextExpectPointer && rawRegisters.isEmpty()) {
                        "Invalid touch-panel-live-v1 I2C bus $index snapshot"
                    }
                    val parsedTouch = VirtualTouchPanel().parseSnapshot(row["liveTouch"])
                    return ParsedSnapshot(initial.copyOf(),false,null,false,parsedLiveTouch=parsedTouch,parsedAccel=parsedAccel)
                }
                require(savedAddress7!=null) { "Register I2C bus $index requires address7" }
                require(kind=="register" && address7==savedAddress7) { "Incompatible I2C bus $index kind or address7 in snapshot" }
                require(!nextExpectPointer || nextSelected) { "I2C bus $index expectPointer requires selected=true" }
                require(rawRegisters.size in 1..128) { "I2C bus $index requires 1..128 register entries" }
                val nextCells=initial.copyOf()
                val seenOffsets=HashSet<Int>()
                for((rawKey,rawValue) in rawRegisters) {
                    val keyText=rawKey as? String ?: rawKey?.toString() ?: throw IllegalArgumentException("Null register key on bus $index")
                    require(keyText.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Invalid register key $keyText on bus $index" }
                    val offset=keyText.toInt()
                    require(offset in 0..255 && seenOffsets.add(offset)) { "Duplicate or out-of-range register offset $offset on bus $index" }
                    require(initial[offset]>=0) { "Undeclared register offset $offset on I2C bus $index" }
                    nextCells[offset]=snapshotInt(rawValue,"bus[$index].registers[$offset]",0,255)
                }
                if(strictRegisters) {
                    val declaredOffsets=(0..255).filter { offset -> initial[offset]>=0 }.toSet()
                    require(seenOffsets==declaredOffsets) { "Register offset set differs for I2C bus $index" }
                }
                return ParsedSnapshot(nextCells,nextSelected,nextPointer,nextExpectPointer)
            }

            fun applySnapshot(parsed: ParsedSnapshot) {
                eye?.cursor = parsed.eyeCursor
                val parsedLive = parsed.parsedLiveEye
                if (parsedLive != null) {
                    val target = liveEye ?: LiveEyeSensor().also { liveEye = it }
                    target.applySnapshot(parsedLive)
                } else if (kind == LiveEyeSensor.KIND) {
                    liveEye = LiveEyeSensor(false)
                } else {
                    liveEye = null
                }
                val parsedTouch = parsed.parsedLiveTouch
                if (parsedTouch != null) {
                    val target = liveTouch ?: VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).also {
                        liveTouch = it
                        it.attachResetListener()
                    }
                    target.applySnapshot(parsedTouch)
                } else if (kind == VirtualTouchPanel.KIND) {
                    liveTouch?.detachResetListener()
                    liveTouch = VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).also { it.attachResetListener() }
                } else {
                    liveTouch?.detachResetListener()
                    liveTouch?.releaseAndClearIrq()
                    liveTouch = null
                }
                val parsedAccel = parsed.parsedAccel
                if (parsedAccel != null) {
                    val supplier = accelGravitySupplier ?: { CameraGravityVector(1.0, 0.0, 0.0) }
                    val target = accel ?: Mma8452qDevice(accelAddress7(), supplier).also { accel = it }
                    target.applySnapshot(parsedAccel)
                } else {
                    accel?.stop()
                    accel = null
                }
                parsed.nextCells.copyInto(cells)
                selected=parsed.nextSelected
                pointer=parsed.nextPointer
                expectPointer=parsed.nextExpectPointer
            }

            fun reset() {
                eye?.cursor = 0
                liveEye = if (kind == LiveEyeSensor.KIND) LiveEyeSensor(false) else null
                liveTouch?.detachResetListener()
                liveTouch?.releaseAndClearIrq()
                liveTouch = if (kind == VirtualTouchPanel.KIND) VirtualTouchPanel(irqSinkProvider, gpioOutputProvider).also { it.attachResetListener() } else null
                accel?.stop()
                accel = null
                initial.copyInto(cells)
                selected=false
                pointer=null
                expectPointer=false
            }

            companion object {
                private const val SLAVE_NONE = 0
                private const val SLAVE_REGISTER = 1
                private const val SLAVE_EYE = 2
                private const val SLAVE_TOUCH = 3
                private const val SLAVE_ACCEL = 4
                private const val SLAVE_EXTERNAL = 5
            }
        }
        override fun close() {
            buses.forEach { it.detachTouchResetListener() }
            closed=true
        }
        override fun saveSnapshot(): Map<String,Any?> {
            check(!closed) { "i2c-bus closed" }
            val allScalars = saveScalarValues()
            val hasNonDefaultOrientation = accelEnabled ||
                orientationPreset != "landscape" ||
                rollDeg != 0.0 ||
                pitchDeg != 0.0 ||
                shakeAmpDeg != 0.0 ||
                lcdTiltDeg != 0.0
            val savedScalars = if (hasNonDefaultOrientation) {
                allScalars
            } else {
                allScalars - ORIENTATION_SCALAR_KEYS
            }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to savedScalars,
                "enabled" to enabled,
                "buses" to buses.sortedBy { bus -> bus.index }.map { bus -> bus.saveSnapshot() },
            )
        }
        override fun restoreSnapshot(data: Map<String,Any?>) {
            check(!closed) { "i2c-bus closed" }
            val expectedKeys=setOf("schema","componentId","scalars","enabled","buses")
            require(data.keys==expectedKeys) { "Unexpected or missing keys in i2c-bus snapshot" }
            require(snapshotInt(data["schema"],"schema",1,1)==snapshotSchemaVersion) { "Unsupported i2c-bus snapshot schema" }
            require(data["componentId"]==id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw=data["scalars"] as? Map<*,*> ?: throw IllegalArgumentException("Missing scalar settings map for $id")
            val fullScalarKeys = DESCRIPTOR.settings.map { setting -> setting.key }.toSet()
            require(
                scalarsRaw.keys.containsAll(LEGACY_SCALAR_KEYS) &&
                    fullScalarKeys.containsAll(scalarsRaw.keys),
            ) { "Scalar setting keys differ for $id" }
            val savedProfile=scalarsRaw["profile"] as? String ?: throw IllegalArgumentException("Invalid profile in i2c-bus snapshot")
            require(savedProfile in listOf("disabled","explicit-i2c-buses-experimental-v1")) { "Unknown i2c-bus profile in snapshot" }
            val savedEnabled=data["enabled"] as? Boolean ?: throw IllegalArgumentException("Missing enabled boolean in i2c-bus snapshot")
            require(savedEnabled==(savedProfile!="disabled")) { "i2c-bus enabled flag disagrees with profile in snapshot" }
            val rawBuses=data["buses"] as? List<*> ?: throw IllegalArgumentException("Missing buses list in i2c-bus snapshot")
            fun fillLegacyScalarDefaults() {
                val extras = linkedMapOf<String, Any>()
                if ("eyeSensorNear" !in scalarsRaw) extras["eyeSensorNear"] = false
                if ("touchEnabled" !in scalarsRaw) extras["touchEnabled"] = false
                if ("touchPressed" !in scalarsRaw) extras["touchPressed"] = false
                if ("touchX" !in scalarsRaw) extras["touchX"] = 0L
                if ("touchY" !in scalarsRaw) extras["touchY"] = 0L
                if ("accelEnabled" !in scalarsRaw) extras["accelEnabled"] = false
                if ("orientationPreset" !in scalarsRaw) extras["orientationPreset"] = "landscape"
                if ("rollDeg" !in scalarsRaw) extras["rollDeg"] = 0.0
                if ("pitchDeg" !in scalarsRaw) extras["pitchDeg"] = 0.0
                if ("shakeAmpDeg" !in scalarsRaw) extras["shakeAmpDeg"] = 0.0
                if ("lcdTiltDeg" !in scalarsRaw) extras["lcdTiltDeg"] = 0.0
                if (extras.isNotEmpty()) values = values + extras
            }
            if(!savedEnabled) {
                require(rawBuses.isEmpty()) { "Disabled i2c-bus snapshot contains bus entries" }
                restoreScalarValues(scalarsRaw,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile"))
                fillLegacyScalarDefaults()
                buses.forEach { bus -> bus.reset() }
                return
            }
            require(enabled) { "Cannot restore enabled i2c-bus snapshot into disabled component" }
            require(rawBuses.size in 1..3) { "i2c-bus snapshot requires 1..3 buses" }
            val strictConfig=scalarsRaw["profile"]==profile && scalarsRaw["inputFile"]==values["inputFile"]
            val liveByIndex=buses.associateBy { bus -> bus.index }
            val seenIndexes=HashSet<Int>()
            val parsedByIndex=HashMap<Int,Bus.ParsedSnapshot>()
            for((listPos,rawItem) in rawBuses.withIndex()) {
                val row=rawItem as? Map<*,*> ?: throw IllegalArgumentException("Invalid bus snapshot at position $listPos")
                val busIndex=snapshotInt(row["index"],"buses[$listPos].index",0,2)
                require(seenIndexes.add(busIndex)) { "Duplicate bus index $busIndex in i2c-bus snapshot" }
                val liveBus=liveByIndex[busIndex]
                    ?: throw IllegalArgumentException("Snapshot I2C bus $busIndex missing in live configuration")
                parsedByIndex[busIndex]=liveBus.parseSnapshot(row,strictRegisters=strictConfig)
            }
            if(strictConfig) {
                require(seenIndexes==liveByIndex.keys) { "I2C bus index set differs from live configuration" }
            }
            restoreScalarValues(scalarsRaw,DESCRIPTOR,compatibleNonLiveKeys=setOf("profile","inputFile"))
            fillLegacyScalarDefaults()
            buses.forEach { bus ->
                val parsed=parsedByIndex[bus.index]
                if(parsed!=null) bus.applySnapshot(parsed) else bus.reset()
            }
        }
        override fun state(): Map<String,Any?> = super.state()+mapOf(
            "touchEnabled" to (externalTouch?.touchEnabled ?: (values.getValue("touchEnabled") as Boolean)),
            "buses" to buses.map {it.state()},
            "basis" to "Explicit empty bus NACK or declared synthetic register cells. Exclusive owner lease; no physical endpoint, electrical waveform or original ACK identity claim",
        )
    }
}
