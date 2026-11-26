package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.EdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.GpioOutputPort
import com.github.nyabanban.fuji.emulator.api.InterruptSink

/**
 * Opt-in virtual rear touchscreen peer on `I2C2` at 7-bit address `0x55` (`touchPanel` task `T91`).
 *
 * Implements the `DAT`-verified 3-transfer multi-touch wire protocol:
 * - Reg `0x10` (Read `2 B`): `[touchCount (0..2), statusFlags (0x00; bits 0x18 clear)]`
 * - Reg `0x11` (Two sequential `7 B` Reads): Finger #0 then Finger #1, each
 *   `[flags_id (bit 7 = active, bits 3:0 = finger ID), xLo, xHi, yLo, yHi, 0x00, 0x00]`
 * - Reg `0x12` (Read `1 B`): free-running incrementing 8-bit alive counter (checked every ~64 ms after `0xF6 <- 0x12`)
 * - Reg `0x20` (Read `10 B`): IC/firmware info block
 * - Reg `0x21` (Read `3 B`): auxiliary status/info block
 * - Reg `0x30` (Write `14 B`) / `0x31` (Read `14 B`): extended panel configuration & readback
 * - Reg `0xF6` (Write `1..32 B` / Read `1 B`): control / mode / read-trigger command register
 *
 * Observes GPIO Bank `7`, Pin `2` (active-HIGH reset: `1 = RESET/OFF`, `0 = ACTIVE/RUN`) via
 * [GpioOutputPort] and pulses edge-triggered GIC IRQ `153` (`0x99`, `EXTINT #2` / `GPIO(0,2)`)
 * via [InterruptSink] on boot-ready reset release (`1 -> 0`) and touch state transitions.
 */
internal class VirtualTouchPanel(
    private val irqSinkProvider: (() -> InterruptSink?)? = null,
    private val gpioOutputProvider: (() -> GpioOutputPort?)? = null,
) {
    companion object {
        const val KIND = "touch-panel-live-v1"
        const val ADDRESS7 = 0x55
        const val IRQ_LINE = 153
        const val RESET_GPIO_BANK = 7
        const val RESET_GPIO_PIN = 2

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
        )
        private val OPTIONAL_SNAPSHOT_KEYS = setOf("configRegs", "extReg30")
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
        val configRegs: IntArray = IntArray(CONFIG_REG_COUNT),
        val extReg30: IntArray = IntArray(EXT_REG_BYTES),
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
        resetListenerSub = gpio.addOutputListener(RESET_GPIO_BANK, RESET_GPIO_PIN) { _, _ ->
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
        val seq = gpio.outputSequence(RESET_GPIO_BANK, RESET_GPIO_PIN)
        if (seq == lastResetSeq) return
        // DAT s4:0x0124d81c / 0x0124d918: GPIO(7, 2) is ACTIVE-HIGH reset/sleep (1 = RESET/OFF, 0 = ACTIVE/RUN).
        val level = gpio.outputLevel(RESET_GPIO_BANK, RESET_GPIO_PIN)
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
        if (sink is EdgeInterruptSink && sink.edgeState(IRQ_LINE).edge) {
            sink.setLine(IRQ_LINE, false)
            sink.setLine(IRQ_LINE, true)
            sink.setLine(IRQ_LINE, false)
            irqAsserted = false
        } else {
            sink.setLine(IRQ_LINE, true)
            irqAsserted = true
        }
        irqPulseCount++
    }

    private fun deassertLevelIrq() {
        if (!irqAsserted) return
        irqSinkProvider?.invoke()?.setLine(IRQ_LINE, false)
        irqAsserted = false
    }

    fun address(byte: Int): Boolean {
        require(byte in 0..255)
        syncResetPin()
        if (inReset || (byte ushr 1) != ADDRESS7) {
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

    fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected && !expectRead) { "Touch panel write outside selected write phase" }
        when (val activeReg = reg) {
            null -> require(byte in VALID_REGISTERS) { "Unsupported touch panel register 0x${byte.toString(16)}" }
            in REG_CONFIG_MIN..REG_CONFIG_MAX -> require(writeCount < 1) { "Touch panel 0x${activeReg.toString(16)} config write overflow" }
            in REG_EXT_MIN..REG_EXT_MAX, in REG_VENDOR_MIN..REG_VENDOR_MAX -> require(writeCount < MAX_COMMAND_BYTES) { "Touch panel 0x${activeReg.toString(16)} config write overflow" }
            REG_COMMAND -> require(writeCount < MAX_COMMAND_BYTES) { "Touch panel 0xF6 command write overflow" }
            else -> throw IllegalArgumentException("Unexpected extra write byte for touch panel register 0x${activeReg.toString(16)}")
        }
    }

    fun write(byte: Int) {
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

    fun peekRead(): Int {
        require(selected && expectRead && readPos < readLen) { "Touch panel read outside available read buffer" }
        return readBuf[readPos]
    }

    fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "Touch panel read byte changed before completion" }
        readPos++
        if (readPos == readLen) {
            if ((reg == REG_FINGER_FIFO && fifoIndex == 0) || (reg == REG_HEADER && latchedCount == 0)) {
                deassertLevelIrq()
            }
        }
    }

    fun stop() {
        selected = false
        expectRead = false
        reg = null
        writeCount = 0
        readLen = 0
        readPos = 0
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "address7" to ADDRESS7,
        "irqLine" to IRQ_LINE,
        "resetGpio" to "bank$RESET_GPIO_BANK.pin$RESET_GPIO_PIN",
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
        "confidence" to "HYPOTHESIS",
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
    ).apply {
        if (configRegs.any { it != 0 }) put("configRegs", configRegs.toList())
        if (extReg30.any { it != 0 }) put("extReg30", extReg30.toList())
    }

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid liveTouch snapshot")
        require(map.keys.containsAll(SNAPSHOT_KEYS) && (SNAPSHOT_KEYS + OPTIONAL_SNAPSHOT_KEYS).containsAll(map.keys)) {
            "Invalid keys in liveTouch snapshot"
        }
        val nextF0 = snapFinger(map["finger0"], "liveTouch.finger0")
        val nextF1 = snapFinger(map["finger1"], "liveTouch.finger1")
        val nextLatchedCount = snapInt(map["latchedCount"], "liveTouch.latchedCount", 0..2)
        val nextLatchedF0 = snapFinger(map["latchedFinger0"], "liveTouch.latchedFinger0")
        val nextLatchedF1 = snapFinger(map["latchedFinger1"], "liveTouch.latchedFinger1")
        val nextFifoIndex = snapInt(map["fifoIndex"], "liveTouch.fifoIndex", 0..1)
        val nextAlive = snapInt(map["aliveStatus"], "liveTouch.aliveStatus", 0..255)
        val nextLastCmd = snapInt(map["lastCommand"], "liveTouch.lastCommand", 0..255)
        val nextLastCmdLen = snapInt(map["lastCommandLen"], "liveTouch.lastCommandLen", 0..MAX_COMMAND_BYTES)
        val nextLastCmdPayload = snapByteList(map["lastCommandPayload"], "liveTouch.lastCommandPayload", MAX_COMMAND_BYTES)
        val nextInReset = snapBool(map["inReset"], "liveTouch.inReset")
        val nextLastResetSeq = snapLong(map["lastResetSeq"], "liveTouch.lastResetSeq", min = -1L)
        val nextResetCount = snapLong(map["resetCount"], "liveTouch.resetCount", min = 0L)
        val nextIrqAsserted = snapBool(map["irqAsserted"], "liveTouch.irqAsserted")
        val nextIrqPulseCount = snapLong(map["irqPulseCount"], "liveTouch.irqPulseCount", min = 0L)
        val nextUpdateCount = snapLong(map["updateCount"], "liveTouch.updateCount", min = 0L)
        val nextSelected = snapBool(map["selected"], "liveTouch.selected")
        val nextExpectRead = snapBool(map["expectRead"], "liveTouch.expectRead")
        val nextReg = map["reg"]?.let { value ->
            snapInt(value, "liveTouch.reg", 0..255).also { require(it in VALID_REGISTERS) { "Invalid liveTouch.reg" } }
        }
        val nextWriteCount = snapInt(map["writeCount"], "liveTouch.writeCount", 0..MAX_COMMAND_BYTES)
        val rawReadBufList = map["readBuf"] as? List<*> ?: throw IllegalArgumentException("Expected list for liveTouch.readBuf")
        require(rawReadBufList.size == FINGER_RECORD_BYTES || rawReadBufList.size == READ_BUF_BYTES) {
            "Unexpected list length for liveTouch.readBuf"
        }
        val nextReadBuf = IntArray(READ_BUF_BYTES).apply {
            for (i in rawReadBufList.indices) {
                this[i] = snapInt(rawReadBufList[i], "liveTouch.readBuf[$i]", 0..255)
            }
        }
        val nextReadLen = snapInt(map["readLen"], "liveTouch.readLen", 0..rawReadBufList.size)
        val nextReadPos = snapInt(map["readPos"], "liveTouch.readPos", 0..nextReadLen)
        val nextConfigRegs = if ("configRegs" in map) {
            snapByteList(map["configRegs"], "liveTouch.configRegs", CONFIG_REG_COUNT)
        } else {
            IntArray(CONFIG_REG_COUNT)
        }
        val nextExtReg30 = if ("extReg30" in map) {
            snapByteList(map["extReg30"], "liveTouch.extReg30", EXT_REG_BYTES)
        } else {
            IntArray(EXT_REG_BYTES)
        }
        require(!nextExpectRead || nextSelected) { "liveTouch expectRead requires selected" }
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
