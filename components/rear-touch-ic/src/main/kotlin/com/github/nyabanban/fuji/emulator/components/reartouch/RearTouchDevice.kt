package com.github.nyabanban.fuji.emulator.components.reartouch

import com.github.nyabanban.fuji.emulator.api.EdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.GpioOutputPort
import com.github.nyabanban.fuji.emulator.api.I2cSlaveDevice
import com.github.nyabanban.fuji.emulator.api.InterruptSink

/**
 * Standalone rear capacitive multi-touch controller IC (`I2C2 @ 0x55`, `touchPanel` task `T91`).
 *
 * Implements the `DAT`-verified wire protocol (`section4 @ 0x0124d81c..0x0124e188`):
 * - Reg `0x00..0x0F` (1 B R/W): device configuration registers (incl. `0x03` device control).
 * - Reg `0x10` (Read 2 B): `[touchCount (0..2), statusFlags (0x00; bits 0x18 clear)]`.
 * - Reg `0x11` (Two sequential 7 B Reads): Finger #0 then Finger #1, each
 *   `[flags_id (bit 7 = active, bits 3:0 = finger ID), xLo, xHi, yLo, yHi, 0x00, 0x00]`.
 * - Reg `0x12` (Read 1 B): free-running incrementing 8-bit alive counter (checked ~64 ms apart
 *   after `0xF6 <- 0x12`; unchanged value triggers firmware hard reset via `GPIO(7, 2)`).
 * - Reg `0x20` (Read 10 B): IC/firmware info block.
 * - Reg `0x21` (Read 3 B): auxiliary status/info block.
 * - Reg `0x30` (Write 14 B) / `0x31` (Read 14 B): extended panel configuration & readback.
 * - Reg `0xF0..0xF5`: vendor registers.
 * - Reg `0xF6` (Write 1..32 B / Read 1 B): control / mode / read-trigger command register.
 *
 * Observes active-HIGH reset `GPIO(resetGpioBank, resetGpioPin)` (default `GPIO(7, 2)`:
 * `1 = RESET/OFF` NACKing `0x55`, `0 = ACTIVE/RUN` pulsing boot-ready GIC IRQ `153` on `1 -> 0`)
 * and pulses GIC IRQ `irqLine` (default `153` / `EXTINT #2`) on touch state changes.
 */
internal class RearTouchDevice(
    override val address7: Int = DEFAULT_ADDRESS7,
    val irqLine: Int = DEFAULT_IRQ_LINE,
    val resetGpioBank: Int = DEFAULT_RESET_GPIO_BANK,
    val resetGpioPin: Int = DEFAULT_RESET_GPIO_PIN,
    private val irqSinkProvider: (() -> InterruptSink?)? = null,
    private val gpioOutputProvider: (() -> GpioOutputPort?)? = null,
) : I2cSlaveDevice {
    init {
        require(address7 in 0x08..0x77) { "Rear touch IC 7-bit I2C address out of range: 0x${address7.toString(16)}" }
        require(irqLine in 32..1019) { "Rear touch IC GIC IRQ line out of range: $irqLine" }
        require(resetGpioBank in 0..15 && resetGpioPin in 0..15) {
            "Rear touch IC reset GPIO out of range: bank$resetGpioBank.pin$resetGpioPin"
        }
    }

    companion object {
        const val DEFAULT_ADDRESS7 = 0x55
        const val DEFAULT_IRQ_LINE = 153
        const val DEFAULT_RESET_GPIO_BANK = 7
        const val DEFAULT_RESET_GPIO_PIN = 2

        const val REG_DEVICE_CONTROL = 0x03
        const val REG_CONFIG_MIN = 0x00
        const val REG_CONFIG_MAX = 0x0F
        const val REG_HEADER = 0x10
        const val REG_FINGER_FIFO = 0x11
        const val REG_ALIVE = 0x12
        const val REG_FW_INFO = 0x20
        const val REG_SUB_INFO = 0x21
        const val REG_EXT_CONFIG = 0x30
        const val REG_EXT_READBACK = 0x31
        const val REG_EXT_MIN = 0x30
        const val REG_EXT_MAX = 0x3F
        const val REG_VENDOR_MIN = 0xF0
        const val REG_VENDOR_MAX = 0xF5
        const val REG_COMMAND = 0xF6

        const val MAX_COORD = 0xFFFF
        private const val MAX_COMMAND_BYTES = 32
        private const val CONFIG_REG_COUNT = 16
        private const val FINGER_RECORD_BYTES = 7
        private const val FW_INFO_BYTES = 10
        private const val SUB_INFO_BYTES = 3
        private const val EXT_REG_BYTES = 14
        private const val READ_BUF_BYTES = EXT_REG_BYTES
        private val FW_INFO_DEFAULT = intArrayOf(0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        private val SUB_INFO_DEFAULT = intArrayOf(0x01, 0x00, 0x00)
        private val VALID_REGISTERS = (REG_CONFIG_MIN..REG_CONFIG_MAX).toSet() +
            (REG_EXT_MIN..REG_EXT_MAX).toSet() +
            (REG_VENDOR_MIN..REG_VENDOR_MAX).toSet() +
            setOf(REG_HEADER, REG_FINGER_FIFO, REG_ALIVE, REG_FW_INFO, REG_SUB_INFO, REG_COMMAND)

        private val SNAPSHOT_KEYS = setOf(
            "finger0", "finger1", "latchedCount", "latchedFinger0", "latchedFinger1",
            "fifoIndex", "aliveStatus", "lastCommand", "lastCommandLen", "lastCommandPayload",
            "inReset", "lastResetSeq", "resetCount", "irqAsserted", "irqPulseCount", "updateCount",
            "selected", "expectRead", "reg", "writeCount", "readBuf", "readLen", "readPos",
            "configRegs", "extReg30",
        )
        private val FINGER_KEYS = setOf("fingerId", "pressed", "x", "y")

        private fun snapInt(raw: Any?, field: String, range: IntRange): Int {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val value = (raw as Number).toLong()
            require(value in range.first.toLong()..range.last.toLong()) { "Out-of-range integer for $field" }
            return value.toInt()
        }

        private fun snapLong(raw: Any?, field: String, min: Long = 0L): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val value = (raw as Number).toLong()
            require(value >= min) { "Out-of-range integer for $field" }
            return value
        }

        private fun snapBool(raw: Any?, field: String): Boolean =
            raw as? Boolean ?: throw IllegalArgumentException("Expected boolean for $field")

        private fun snapByteList(raw: Any?, field: String, expectedSize: Int): IntArray {
            val list = raw as? List<*> ?: throw IllegalArgumentException("Expected list for $field")
            require(list.size == expectedSize) { "Unexpected list length for $field" }
            return IntArray(expectedSize) { index -> snapInt(list[index], "$field[$index]", 0..255) }
        }

        private fun snapFinger(raw: Any?, field: String): TouchPoint {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Expected map for $field")
            require(map.keys == FINGER_KEYS) { "Invalid keys for $field" }
            return TouchPoint(
                fingerId = snapInt(map["fingerId"], "$field.fingerId", 0..15),
                pressed = snapBool(map["pressed"], "$field.pressed"),
                x = snapInt(map["x"], "$field.x", 0..MAX_COORD),
                y = snapInt(map["y"], "$field.y", 0..MAX_COORD),
            )
        }

        internal fun encodeFingerRecord(point: TouchPoint, target: IntArray, offset: Int = 0) {
            require(offset >= 0 && offset + FINGER_RECORD_BYTES <= target.size)
            target[offset] = if (point.pressed) (0x80 or (point.fingerId and 0x0F)) else 0x00
            target[offset + 1] = point.x and 0xFF
            target[offset + 2] = (point.x ushr 8) and 0xFF
            target[offset + 3] = point.y and 0xFF
            target[offset + 4] = (point.y ushr 8) and 0xFF
            target[offset + 5] = 0x00
            target[offset + 6] = 0x00
        }
    }

    internal data class TouchPoint(
        val fingerId: Int,
        val pressed: Boolean,
        val x: Int,
        val y: Int,
    ) {
        init {
            require(fingerId in 0..15) { "fingerId out of range: $fingerId" }
            require(x in 0..MAX_COORD) { "touch X out of range: $x" }
            require(y in 0..MAX_COORD) { "touch Y out of range: $y" }
        }

        fun toMap(): Map<String, Any> = linkedMapOf(
            "fingerId" to fingerId,
            "pressed" to pressed,
            "x" to x,
            "y" to y,
        )
    }

    internal class ParsedSnapshot(
        val finger0: TouchPoint,
        val finger1: TouchPoint,
        val latchedCount: Int,
        val latchedFinger0: TouchPoint,
        val latchedFinger1: TouchPoint,
        val fifoIndex: Int,
        val aliveStatus: Int,
        val lastCommand: Int,
        val lastCommandLen: Int,
        val lastCommandPayload: IntArray,
        val inReset: Boolean,
        val lastResetSeq: Long,
        val resetCount: Long,
        val irqAsserted: Boolean,
        val irqPulseCount: Long,
        val updateCount: Long,
        val selected: Boolean,
        val expectRead: Boolean,
        val reg: Int?,
        val writeCount: Int,
        val readBuf: IntArray,
        val readLen: Int,
        val readPos: Int,
        val configRegs: IntArray,
        val extReg30: IntArray,
    )

    private var finger0 = TouchPoint(fingerId = 1, pressed = false, x = 0, y = 0)
    private var finger1 = TouchPoint(fingerId = 2, pressed = false, x = 0, y = 0)
    private var latchedCount = 0
    private var latchedFinger0 = finger0
    private var latchedFinger1 = finger1
    private var fifoIndex = 0

    private val configRegs = IntArray(CONFIG_REG_COUNT)
    private val extReg30 = IntArray(EXT_REG_BYTES)
    private var aliveStatus = 0x00
    private var lastCommand = 0x00
    private var lastCommandLen = 0
    private val lastCommandPayload = IntArray(MAX_COMMAND_BYTES)

    private var inReset = false
    private var lastResetSeq = -1L
    private var resetCount = 0L
    private var resetListenerSub: AutoCloseable? = null

    private var irqAsserted = false
    private var irqPulseCount = 0L
    private var updateCount = 0L

    private var selected = false
    private var expectRead = false
    private var reg: Int? = null
    private var writeCount = 0
    private val readBuf = IntArray(READ_BUF_BYTES)
    private var readLen = 0
    private var readPos = 0

    fun attachResetListener() {
        if (resetListenerSub != null) return
        val gpio = gpioOutputProvider?.invoke() ?: return
        syncResetPin()
        resetListenerSub = gpio.addOutputListener(resetGpioBank, resetGpioPin) { _, _ ->
            syncResetPin()
        }
    }

    fun detachResetListener() {
        resetListenerSub?.close()
        resetListenerSub = null
    }

    fun setSingleTouch(pressed: Boolean, x: Int, y: Int) {
        require(x in 0..MAX_COORD) { "touchX out of range: $x" }
        require(y in 0..MAX_COORD) { "touchY out of range: $y" }
        syncResetPin()
        finger0 = TouchPoint(fingerId = 1, pressed = pressed, x = x, y = y)
        finger1 = TouchPoint(fingerId = 2, pressed = false, x = 0, y = 0)
        updateCount++
        if (!inReset) {
            signalIrq()
        }
    }

    fun setTwoTouch(
        pressed0: Boolean,
        x0: Int,
        y0: Int,
        pressed1: Boolean,
        x1: Int,
        y1: Int,
    ) {
        syncResetPin()
        finger0 = TouchPoint(fingerId = 1, pressed = pressed0, x = x0, y = y0)
        finger1 = TouchPoint(fingerId = 2, pressed = pressed1, x = x1, y = y1)
        updateCount++
        if (!inReset) {
            signalIrq()
        }
    }

    fun releaseAndClearIrq() {
        finger0 = TouchPoint(fingerId = 1, pressed = false, x = finger0.x, y = finger0.y)
        finger1 = TouchPoint(fingerId = 2, pressed = false, x = 0, y = 0)
        deassertLevelIrq()
    }

    private fun activeTouchCount(): Int =
        (if (finger0.pressed) 1 else 0) + (if (finger1.pressed) 1 else 0)

    fun syncResetPin() {
        val gpio = gpioOutputProvider?.invoke() ?: return
        val seq = gpio.outputSequence(resetGpioBank, resetGpioPin)
        if (seq == lastResetSeq) return
        // DAT s4:0x0124d81c / 0x0124d918: GPIO(7, 2) is ACTIVE-HIGH reset/sleep (1 = RESET/OFF, 0 = ACTIVE/RUN).
        val level = gpio.outputLevel(resetGpioBank, resetGpioPin)
        val wasInitialized = lastResetSeq >= 0L
        lastResetSeq = seq
        if (level) {
            if (!inReset && (wasInitialized || seq > 0L)) {
                inReset = true
                resetCount++
                applyHardwareReset()
            }
        } else {
            val wasInReset = inReset
            inReset = false
            if (wasInReset) {
                // DAT s4:0x0124d940: releasing reset (1 -> 0) pulses boot-ready GIC IRQ 153 within 20 ms.
                signalIrq()
            }
        }
    }

    private fun applyHardwareReset() {
        finger0 = TouchPoint(fingerId = 1, pressed = false, x = 0, y = 0)
        finger1 = TouchPoint(fingerId = 2, pressed = false, x = 0, y = 0)
        latchedCount = 0
        latchedFinger0 = finger0
        latchedFinger1 = finger1
        fifoIndex = 0
        lastCommand = 0
        lastCommandLen = 0
        lastCommandPayload.fill(0)
        stop()
        deassertLevelIrq()
    }

    private fun signalIrq() {
        val sink = irqSinkProvider?.invoke() ?: return
        if (sink is EdgeInterruptSink && sink.edgeState(irqLine).edge) {
            sink.setLine(irqLine, false)
            sink.setLine(irqLine, true)
            sink.setLine(irqLine, false)
            irqAsserted = false
        } else {
            sink.setLine(irqLine, true)
            irqAsserted = true
        }
        irqPulseCount++
    }

    private fun deassertLevelIrq() {
        if (!irqAsserted) return
        irqSinkProvider?.invoke()?.setLine(irqLine, false)
        irqAsserted = false
    }

    override fun address(byte: Int): Boolean {
        require(byte in 0..255)
        syncResetPin()
        if (inReset || (byte ushr 1) != address7) {
            stop()
            return false
        }
        return if ((byte and 1) == 0) {
            selected = true
            expectRead = false
            reg = null
            writeCount = 0
            readLen = 0
            readPos = 0
            true
        } else if (selected && readPos < readLen) {
            expectRead = true
            true
        } else {
            stop()
            false
        }
    }

    override fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected && !expectRead) { "Rear touch IC write outside selected write phase" }
        when (val activeReg = reg) {
            null -> require(byte in VALID_REGISTERS) { "Unsupported rear touch IC register 0x${byte.toString(16)}" }
            in REG_CONFIG_MIN..REG_CONFIG_MAX -> require(writeCount < 1) {
                "Rear touch IC 0x${activeReg.toString(16)} config write overflow"
            }
            in REG_EXT_MIN..REG_EXT_MAX, in REG_VENDOR_MIN..REG_VENDOR_MAX -> require(writeCount < MAX_COMMAND_BYTES) {
                "Rear touch IC 0x${activeReg.toString(16)} config write overflow"
            }
            REG_COMMAND -> require(writeCount < MAX_COMMAND_BYTES) { "Rear touch IC 0xF6 command write overflow" }
            else -> throw IllegalArgumentException("Unexpected extra write byte for rear touch IC register 0x${activeReg.toString(16)}")
        }
    }

    override fun write(byte: Int) {
        checkWrite(byte)
        if (reg == null) {
            reg = byte
            writeCount = 0
            when (byte) {
                in REG_CONFIG_MIN..REG_CONFIG_MAX -> {
                    readBuf[0] = configRegs[byte] and 0xFF
                    readLen = 1
                    readPos = 0
                }
                REG_FW_INFO -> {
                    FW_INFO_DEFAULT.copyInto(readBuf, 0, 0, FW_INFO_BYTES)
                    readLen = FW_INFO_BYTES
                    readPos = 0
                }
                REG_SUB_INFO -> {
                    SUB_INFO_DEFAULT.copyInto(readBuf, 0, 0, SUB_INFO_BYTES)
                    readLen = SUB_INFO_BYTES
                    readPos = 0
                }
                REG_EXT_CONFIG, REG_EXT_READBACK -> {
                    extReg30.copyInto(readBuf, 0, 0, EXT_REG_BYTES)
                    readLen = EXT_REG_BYTES
                    readPos = 0
                }
                in REG_EXT_MIN..REG_EXT_MAX, in REG_VENDOR_MIN..REG_VENDOR_MAX -> {
                    readBuf[0] = 0x00
                    readLen = 1
                    readPos = 0
                }
                REG_HEADER -> {
                    latchedFinger0 = finger0
                    latchedFinger1 = finger1
                    latchedCount = activeTouchCount()
                    fifoIndex = 0
                    readBuf[0] = latchedCount
                    readBuf[1] = 0x00 // status flags: bits 0x18 clear (valid packet)
                    readLen = 2
                    readPos = 0
                }
                REG_FINGER_FIFO -> {
                    val slot = if (fifoIndex == 0) latchedFinger0 else latchedFinger1
                    encodeFingerRecord(slot, readBuf, 0)
                    fifoIndex = if (fifoIndex == 0) 1 else 0
                    readLen = FINGER_RECORD_BYTES
                    readPos = 0
                }
                REG_ALIVE -> {
                    // DAT s4:0x0124e12c..0x0124e188: firmware polls 0x12 twice ~64 ms apart and requires
                    // old_alive != new_alive; otherwise it declares the touch IC hung and hard-resets GPIO(7,2).
                    aliveStatus = (aliveStatus + 1) and 0xFF
                    readBuf[0] = aliveStatus
                    readLen = 1
                    readPos = 0
                }
                REG_COMMAND -> {
                    readBuf[0] = lastCommand and 0xFF
                    readLen = 1
                    readPos = 0
                }
            }
        } else if (reg in REG_CONFIG_MIN..REG_CONFIG_MAX) {
            configRegs[reg!!] = byte and 0xFF
            writeCount++
            readLen = 0
            readPos = 0
        } else if (reg in REG_EXT_MIN..REG_EXT_MAX || reg in REG_VENDOR_MIN..REG_VENDOR_MAX) {
            if (reg == REG_EXT_CONFIG && writeCount < EXT_REG_BYTES) {
                extReg30[writeCount] = byte and 0xFF
            }
            writeCount++
            readLen = 0
            readPos = 0
        } else {
            if (writeCount == 0) {
                lastCommand = byte
            }
            lastCommandPayload[writeCount++] = byte
            lastCommandLen = writeCount
            readLen = 0
            readPos = 0
        }
    }

    override fun peekRead(): Int {
        require(selected && expectRead && readPos < readLen) { "Rear touch IC read outside available read buffer" }
        return readBuf[readPos]
    }

    override fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "Rear touch IC read byte changed before completion" }
        readPos++
        if (readPos == readLen) {
            if ((reg == REG_FINGER_FIFO && fifoIndex == 0) || (reg == REG_HEADER && latchedCount == 0)) {
                deassertLevelIrq()
            }
        }
    }

    override fun stop() {
        selected = false
        expectRead = false
        reg = null
        writeCount = 0
        readLen = 0
        readPos = 0
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "chip" to "GFX100S Rear Capacitive Touch IC",
        "address7" to address7,
        "irqLine" to irqLine,
        "resetGpio" to "bank$resetGpioBank.pin$resetGpioPin",
        "inReset" to inReset,
        "resetCount" to resetCount,
        "touchCount" to activeTouchCount(),
        "finger0" to finger0.toMap(),
        "finger1" to finger1.toMap(),
        "aliveStatus" to aliveStatus,
        "lastCommand" to lastCommand,
        "irqAsserted" to irqAsserted,
        "irqPulseCount" to irqPulseCount,
        "updateCount" to updateCount,
        "selected" to selected,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "finger0" to finger0.toMap(),
        "finger1" to finger1.toMap(),
        "latchedCount" to latchedCount,
        "latchedFinger0" to latchedFinger0.toMap(),
        "latchedFinger1" to latchedFinger1.toMap(),
        "fifoIndex" to fifoIndex,
        "aliveStatus" to aliveStatus,
        "lastCommand" to lastCommand,
        "lastCommandLen" to lastCommandLen,
        "lastCommandPayload" to lastCommandPayload.toList(),
        "inReset" to inReset,
        "lastResetSeq" to lastResetSeq,
        "resetCount" to resetCount,
        "irqAsserted" to irqAsserted,
        "irqPulseCount" to irqPulseCount,
        "updateCount" to updateCount,
        "selected" to selected,
        "expectRead" to expectRead,
        "reg" to reg,
        "writeCount" to writeCount,
        "readBuf" to readBuf.toList(),
        "readLen" to readLen,
        "readPos" to readPos,
        "configRegs" to configRegs.toList(),
        "extReg30" to extReg30.toList(),
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid rear-touch-ic device snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Invalid keys in rear-touch-ic device snapshot" }
        val nextF0 = snapFinger(map["finger0"], "device.finger0")
        val nextF1 = snapFinger(map["finger1"], "device.finger1")
        val nextLatchedCount = snapInt(map["latchedCount"], "device.latchedCount", 0..2)
        val nextLatchedF0 = snapFinger(map["latchedFinger0"], "device.latchedFinger0")
        val nextLatchedF1 = snapFinger(map["latchedFinger1"], "device.latchedFinger1")
        val nextFifoIndex = snapInt(map["fifoIndex"], "device.fifoIndex", 0..1)
        val nextAlive = snapInt(map["aliveStatus"], "device.aliveStatus", 0..255)
        val nextLastCmd = snapInt(map["lastCommand"], "device.lastCommand", 0..255)
        val nextLastCmdLen = snapInt(map["lastCommandLen"], "device.lastCommandLen", 0..MAX_COMMAND_BYTES)
        val nextLastCmdPayload = snapByteList(map["lastCommandPayload"], "device.lastCommandPayload", MAX_COMMAND_BYTES)
        val nextInReset = snapBool(map["inReset"], "device.inReset")
        val nextLastResetSeq = snapLong(map["lastResetSeq"], "device.lastResetSeq", min = -1L)
        val nextResetCount = snapLong(map["resetCount"], "device.resetCount", min = 0L)
        val nextIrqAsserted = snapBool(map["irqAsserted"], "device.irqAsserted")
        val nextIrqPulseCount = snapLong(map["irqPulseCount"], "device.irqPulseCount", min = 0L)
        val nextUpdateCount = snapLong(map["updateCount"], "device.updateCount", min = 0L)
        val nextSelected = snapBool(map["selected"], "device.selected")
        val nextExpectRead = snapBool(map["expectRead"], "device.expectRead")
        val nextReg = map["reg"]?.let { value ->
            snapInt(value, "device.reg", 0..255).also { require(it in VALID_REGISTERS) { "Invalid device.reg" } }
        }
        val nextWriteCount = snapInt(map["writeCount"], "device.writeCount", 0..MAX_COMMAND_BYTES)
        val nextReadBuf = snapByteList(map["readBuf"], "device.readBuf", READ_BUF_BYTES)
        val nextReadLen = snapInt(map["readLen"], "device.readLen", 0..READ_BUF_BYTES)
        val nextReadPos = snapInt(map["readPos"], "device.readPos", 0..nextReadLen)
        val nextConfigRegs = snapByteList(map["configRegs"], "device.configRegs", CONFIG_REG_COUNT)
        val nextExtReg30 = snapByteList(map["extReg30"], "device.extReg30", EXT_REG_BYTES)
        require(!nextExpectRead || nextSelected) { "rear-touch-ic expectRead requires selected" }
        return ParsedSnapshot(
            finger0 = nextF0,
            finger1 = nextF1,
            latchedCount = nextLatchedCount,
            latchedFinger0 = nextLatchedF0,
            latchedFinger1 = nextLatchedF1,
            fifoIndex = nextFifoIndex,
            aliveStatus = nextAlive,
            lastCommand = nextLastCmd,
            lastCommandLen = nextLastCmdLen,
            lastCommandPayload = nextLastCmdPayload,
            inReset = nextInReset,
            lastResetSeq = nextLastResetSeq,
            resetCount = nextResetCount,
            irqAsserted = nextIrqAsserted,
            irqPulseCount = nextIrqPulseCount,
            updateCount = nextUpdateCount,
            selected = nextSelected,
            expectRead = nextExpectRead,
            reg = nextReg,
            writeCount = nextWriteCount,
            readBuf = nextReadBuf,
            readLen = nextReadLen,
            readPos = nextReadPos,
            configRegs = nextConfigRegs,
            extReg30 = nextExtReg30,
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        finger0 = parsed.finger0
        finger1 = parsed.finger1
        latchedCount = parsed.latchedCount
        latchedFinger0 = parsed.latchedFinger0
        latchedFinger1 = parsed.latchedFinger1
        fifoIndex = parsed.fifoIndex
        parsed.configRegs.copyInto(configRegs)
        parsed.extReg30.copyInto(extReg30)
        aliveStatus = parsed.aliveStatus
        lastCommand = parsed.lastCommand
        lastCommandLen = parsed.lastCommandLen
        parsed.lastCommandPayload.copyInto(lastCommandPayload)
        inReset = parsed.inReset
        lastResetSeq = parsed.lastResetSeq
        resetCount = parsed.resetCount
        irqAsserted = parsed.irqAsserted
        irqPulseCount = parsed.irqPulseCount
        updateCount = parsed.updateCount
        selected = parsed.selected
        expectRead = parsed.expectRead
        reg = parsed.reg
        writeCount = parsed.writeCount
        parsed.readBuf.copyInto(readBuf)
        readLen = parsed.readLen
        readPos = parsed.readPos
    }
}
