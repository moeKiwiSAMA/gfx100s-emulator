package com.github.nyabanban.fuji.emulator.components.tmd2772

import com.github.nyabanban.fuji.emulator.api.I2cSlaveDevice

/**
 * Pure datasheet-faithful model of the **ams / TAOS `TMD27723`** Digital Proximity & Ambient Light
 * Sensor (`I2C1 @ 0x39`, `ID 0x12 = 0x39`, active-LOW open-drain `INT` on `GPIO(0, 8)`).
 *
 * Implements the complete `0x00..0x1F` register file and command-byte protocol per AMS TMD2772
 * datasheet & `GXUP0004.DAT` (`section4 @ 0x01212ed8..0x012139ec`):
 * - `0x80 | reg` (CMD=1, TYPE=00): repeated-byte protocol (pointer stays on `reg`).
 * - `0xA0 | reg` (CMD=1, TYPE=01): auto-increment protocol (pointer wraps modulo 32).
 * - `0xE5` (Clear Proximity INT), `0xE6` (Clear ALS INT), `0xE7` (Clear Proximity & ALS INT).
 * - `0x12 ID` = `0x39` (read-only TMD27723 silicon ID).
 * - `0x13 STATUS`: bit 5 = `PINT`, bit 4 = `AINT`, bit 1 = `PVALID`, bit 0 = `AVALID`.
 * - `0x18..0x19 PDATAL/PDATAH`: 10-bit little-endian proximity sample (`0x03FF` near, `0x0000` far,
 *   or explicit `proximityRaw` override `0..1023`).
 * - Active-LOW `INT` callback fires whenever `intAsserted` changes so `GPIO(0, 8)` authentically
 *   drives low on proximity threshold crossings and releases high after `0xE5`/`0xE7` clear.
 */
internal class Tmd2772Device(
    override val address7: Int = DEFAULT_ADDRESS7,
    initialNear: Boolean = false,
    initialProximityRaw: Int = -1,
    private val onIntChanged: (asserted: Boolean) -> Unit = {},
) : I2cSlaveDevice {
    init {
        require(address7 in 0x08..0x77) { "TMD27723 7-bit I2C address out of range: 0x${address7.toString(16)}" }
    }

    private val regs = IntArray(REGISTER_COUNT)
    private var near: Boolean = initialNear
    private var proximityRawOverride: Int = initialProximityRaw.coerceIn(-1, MAX_PROXIMITY_RAW)
    private var pendingUpdate: Boolean = true
    private var alsIntLatched: Boolean = false
    private var intAsserted: Boolean = false

    private var selected: Boolean = false
    private var expectCommand: Boolean = false
    private var pointer: Int = 0
    private var autoIncrement: Boolean = true
    private var readCount: Long = 0L
    private var writeCount: Long = 0L
    private var clearIntCount: Long = 0L

    init {
        resetRegisters()
    }

    private fun resetRegisters() {
        regs.fill(0)
        regs[REG_ATIME] = 0xFF
        regs[REG_PTIME] = 0xFF
        regs[REG_WTIME] = 0xFF
        // Default thresholds force an initial out-of-window evaluation until firmware writes PILT/PIHT
        regs[REG_PILTL] = 0xFF
        regs[REG_PILTH] = 0xFF
        regs[REG_PIHTL] = 0x00
        regs[REG_PIHTH] = 0x00
        regs[REG_ID] = CHIP_ID
        pendingUpdate = true
        alsIntLatched = false
        selected = false
        expectCommand = false
        pointer = 0
        autoIncrement = true
        recomputeInt(notify = false)
    }

    val isNear: Boolean get() = near
    val isIntAsserted: Boolean get() = intAsserted
    val proximityData: Int
        get() = if (proximityRawOverride >= 0) proximityRawOverride else if (near) NEAR_PDATA else FAR_PDATA
    val lowThreshold: Int get() = (regs[REG_PILTL] and 0xFF) or ((regs[REG_PILTH] and 0xFF) shl 8)
    val highThreshold: Int get() = (regs[REG_PIHTL] and 0xFF) or ((regs[REG_PIHTH] and 0xFF) shl 8)

    fun setNear(nextNear: Boolean) {
        val changed = near != nextNear
        near = nextNear
        if (changed) {
            pendingUpdate = true
        }
        recomputeInt(notify = true)
    }

    fun setProximityRawOverride(raw: Int) {
        val clamped = raw.coerceIn(-1, MAX_PROXIMITY_RAW)
        if (proximityRawOverride != clamped) {
            proximityRawOverride = clamped
            pendingUpdate = true
        }
        recomputeInt(notify = true)
    }

    fun syncIntOutput() {
        recomputeInt(notify = false)
        onIntChanged(intAsserted)
    }

    private fun isOutsideProximityWindow(): Boolean {
        val sample = proximityData
        return sample < lowThreshold || sample > highThreshold
    }

    private fun proximityIntActive(): Boolean = pendingUpdate || isOutsideProximityWindow()

    private fun currentStatusByte(): Int {
        val enable = regs[REG_ENABLE]
        val pon = (enable and ENABLE_PON) != 0
        val pValid = if (pon && (enable and ENABLE_PEN) != 0) STATUS_PVALID else 0
        val aValid = if (pon && (enable and ENABLE_AEN) != 0) STATUS_AVALID else 0
        val pInt = if (proximityIntActive()) STATUS_PINT else 0
        val aInt = if (alsIntLatched) STATUS_AINT else 0
        return pInt or aInt or pValid or aValid
    }

    private fun recomputeInt(notify: Boolean) {
        val enable = regs[REG_ENABLE]
        // Before firmware enables PIEN/PON, keep initial pendingUpdate INT active so boot Task 0x46 initializes cleanly
        val pienEnabled = (enable and ENABLE_PIEN) != 0 || pendingUpdate
        val aienEnabled = (enable and ENABLE_AIEN) != 0
        val next = (pienEnabled && proximityIntActive()) || (aienEnabled && alsIntLatched)
        val changed = next != intAsserted
        intAsserted = next
        if (notify && changed) {
            onIntChanged(intAsserted)
        }
    }

    override fun address(byte: Int): Boolean {
        require(byte in 0..255)
        if ((byte ushr 1) != address7) {
            stop()
            return false
        }
        selected = true
        val isRead = (byte and 1) != 0
        expectCommand = !isRead
        return true
    }

    override fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected) { "TMD27723 write outside selected address phase" }
        if (expectCommand) {
            require((byte and CMD_BIT) != 0) {
                "TMD27723 command byte requires bit 7 = 1, got 0x${byte.toString(16)}"
            }
            val type = (byte ushr 5) and 0x03
            require(type in setOf(0, 1, 3)) {
                "TMD27723 unsupported command type $type in 0x${byte.toString(16)}"
            }
            if (type == 3) {
                val sf = byte and 0x1F
                require(sf in setOf(SF_CLEAR_PROX_INT, SF_CLEAR_ALS_INT, SF_CLEAR_PROX_ALS_INT)) {
                    "TMD27723 unsupported special function 0x${byte.toString(16)}"
                }
            }
        }
    }

    override fun write(byte: Int) {
        checkWrite(byte)
        writeCount++
        if (expectCommand) {
            expectCommand = false
            val type = (byte ushr 5) and 0x03
            if (type == 3) {
                val sf = byte and 0x1F
                if (sf == SF_CLEAR_PROX_INT || sf == SF_CLEAR_PROX_ALS_INT) {
                    pendingUpdate = false
                    clearIntCount++
                }
                if (sf == SF_CLEAR_ALS_INT || sf == SF_CLEAR_PROX_ALS_INT) {
                    alsIntLatched = false
                }
                recomputeInt(notify = true)
            } else {
                autoIncrement = (type == 1)
                pointer = byte and 0x1F
            }
            return
        }
        writeRegister(pointer, byte)
        if (autoIncrement) {
            pointer = (pointer + 1) and 0x1F
        }
    }

    private fun writeRegister(reg: Int, value: Int) {
        val v = value and 0xFF
        when (reg) {
            REG_ID, REG_STATUS,
            REG_C0DATAL, REG_C0DATAH, REG_C1DATAL, REG_C1DATAH,
            REG_PDATAL, REG_PDATAH,
            -> Unit // Read-only ID/status/data registers
            else -> {
                regs[reg] = v
                if (reg == REG_ENABLE || reg in REG_PILTL..REG_PIHTH || reg == REG_PERS) {
                    recomputeInt(notify = true)
                }
            }
        }
    }

    override fun peekRead(): Int {
        require(selected) { "TMD27723 read outside selected address phase" }
        return when (pointer) {
            REG_ID -> CHIP_ID
            REG_STATUS -> currentStatusByte()
            REG_C0DATAL -> DEFAULT_ALS_C0 and 0xFF
            REG_C0DATAH -> (DEFAULT_ALS_C0 ushr 8) and 0xFF
            REG_C1DATAL -> DEFAULT_ALS_C1 and 0xFF
            REG_C1DATAH -> (DEFAULT_ALS_C1 ushr 8) and 0xFF
            REG_PDATAL -> proximityData and 0xFF
            REG_PDATAH -> (proximityData ushr 8) and 0xFF
            else -> regs[pointer] and 0xFF
        }
    }

    override fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "TMD27723 read byte changed before completion" }
        readCount++
        if (autoIncrement) {
            pointer = (pointer + 1) and 0x1F
        }
    }

    override fun stop() {
        selected = false
        expectCommand = false
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "chip" to "ams TMD27723",
        "address7" to address7,
        "chipId" to CHIP_ID,
        "eyeSensorNear" to near,
        "proximityData" to proximityData,
        "enable" to regs[REG_ENABLE],
        "status" to currentStatusByte(),
        "lowThreshold" to lowThreshold,
        "highThreshold" to highThreshold,
        "pendingUpdate" to pendingUpdate,
        "intAsserted" to intAsserted,
        "readCount" to readCount,
        "writeCount" to writeCount,
        "clearIntCount" to clearIntCount,
    )

    internal class ParsedSnapshot(
        val regs: IntArray,
        val near: Boolean,
        val proximityRawOverride: Int,
        val pendingUpdate: Boolean,
        val alsIntLatched: Boolean,
        val intAsserted: Boolean,
        val selected: Boolean,
        val expectCommand: Boolean,
        val pointer: Int,
        val autoIncrement: Boolean,
        val readCount: Long,
        val writeCount: Long,
        val clearIntCount: Long,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "address7" to address7,
        "regs" to regs.toList(),
        "near" to near,
        "proximityRawOverride" to proximityRawOverride,
        "pendingUpdate" to pendingUpdate,
        "alsIntLatched" to alsIntLatched,
        "intAsserted" to intAsserted,
        "selected" to selected,
        "expectCommand" to expectCommand,
        "pointer" to pointer,
        "autoIncrement" to autoIncrement,
        "readCount" to readCount,
        "writeCount" to writeCount,
        "clearIntCount" to clearIntCount,
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid TMD27723 snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected TMD27723 snapshot keys" }
        val savedAddr = snapInt(map["address7"], "tmd2772.address7", 0x08..0x77)
        require(savedAddr == address7) { "TMD27723 address mismatch: expected $address7, got $savedAddr" }
        val rawRegs = map["regs"] as? List<*> ?: throw IllegalArgumentException("Missing tmd2772.regs")
        require(rawRegs.size == REGISTER_COUNT) { "Unexpected tmd2772.regs size ${rawRegs.size}" }
        val parsedRegs = IntArray(REGISTER_COUNT) { i -> snapInt(rawRegs[i], "tmd2772.regs[$i]", 0..255) }
        return ParsedSnapshot(
            regs = parsedRegs,
            near = map["near"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.near"),
            proximityRawOverride = snapInt(map["proximityRawOverride"], "tmd2772.proximityRawOverride", -1..MAX_PROXIMITY_RAW),
            pendingUpdate = map["pendingUpdate"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.pendingUpdate"),
            alsIntLatched = map["alsIntLatched"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.alsIntLatched"),
            intAsserted = map["intAsserted"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.intAsserted"),
            selected = map["selected"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.selected"),
            expectCommand = map["expectCommand"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.expectCommand"),
            pointer = snapInt(map["pointer"], "tmd2772.pointer", 0 until REGISTER_COUNT),
            autoIncrement = map["autoIncrement"] as? Boolean ?: throw IllegalArgumentException("Invalid tmd2772.autoIncrement"),
            readCount = snapLong(map["readCount"], "tmd2772.readCount"),
            writeCount = snapLong(map["writeCount"], "tmd2772.writeCount"),
            clearIntCount = snapLong(map["clearIntCount"], "tmd2772.clearIntCount"),
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        parsed.regs.copyInto(regs)
        regs[REG_ID] = CHIP_ID
        near = parsed.near
        proximityRawOverride = parsed.proximityRawOverride
        pendingUpdate = parsed.pendingUpdate
        alsIntLatched = parsed.alsIntLatched
        intAsserted = parsed.intAsserted
        selected = parsed.selected
        expectCommand = parsed.expectCommand
        pointer = parsed.pointer
        autoIncrement = parsed.autoIncrement
        readCount = parsed.readCount
        writeCount = parsed.writeCount
        clearIntCount = parsed.clearIntCount
        onIntChanged(intAsserted)
    }

    companion object {
        const val DEFAULT_ADDRESS7 = 0x39
        const val CHIP_ID = 0x39
        const val REGISTER_COUNT = 32
        const val MAX_PROXIMITY_RAW = 1023
        const val NEAR_PDATA = 0x03FF
        const val FAR_PDATA = 0x0000
        private const val DEFAULT_ALS_C0 = 0x0100
        private const val DEFAULT_ALS_C1 = 0x0040

        const val REG_ENABLE = 0x00
        const val REG_ATIME = 0x01
        const val REG_PTIME = 0x02
        const val REG_WTIME = 0x03
        const val REG_PILTL = 0x08
        const val REG_PILTH = 0x09
        const val REG_PIHTL = 0x0A
        const val REG_PIHTH = 0x0B
        const val REG_PERS = 0x0C
        const val REG_CONFIG = 0x0D
        const val REG_PPULSE = 0x0E
        const val REG_CONTROL = 0x0F
        const val REG_ID = 0x12
        const val REG_STATUS = 0x13
        const val REG_C0DATAL = 0x14
        const val REG_C0DATAH = 0x15
        const val REG_C1DATAL = 0x16
        const val REG_C1DATAH = 0x17
        const val REG_PDATAL = 0x18
        const val REG_PDATAH = 0x19
        const val REG_POFFSET = 0x1E

        private const val CMD_BIT = 0x80
        private const val SF_CLEAR_PROX_INT = 0x05 // 0xE5
        private const val SF_CLEAR_ALS_INT = 0x06  // 0xE6
        private const val SF_CLEAR_PROX_ALS_INT = 0x07 // 0xE7

        private const val ENABLE_PON = 0x01
        private const val ENABLE_AEN = 0x02
        private const val ENABLE_PEN = 0x04
        private const val ENABLE_AIEN = 0x10
        private const val ENABLE_PIEN = 0x20

        private const val STATUS_AVALID = 0x01
        private const val STATUS_PVALID = 0x02
        private const val STATUS_AINT = 0x10
        private const val STATUS_PINT = 0x20

        private val SNAPSHOT_KEYS = setOf(
            "address7", "regs", "near", "proximityRawOverride",
            "pendingUpdate", "alsIntLatched", "intAsserted",
            "selected", "expectCommand", "pointer", "autoIncrement",
            "readCount", "writeCount", "clearIntCount",
        )

        private fun snapLong(raw: Any?, field: String): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val v = (raw as Number).toLong()
            require(v >= 0L) { "Negative integer for $field" }
            return v
        }

        private fun snapInt(raw: Any?, field: String, range: IntRange): Int {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val v = (raw as Number).toLong()
            require(v in range.first.toLong()..range.last.toLong()) { "Out-of-range integer for $field: $v" }
            return v.toInt()
        }
    }
}
