package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.CameraGravityVector
import com.github.nyabanban.fuji.emulator.api.I2cSlaveDevice
import kotlin.math.roundToInt

/**
 * Pure datasheet-faithful model of the **NXP / Freescale `MMA8452Q` / `MMA8652FC`** 3-axis 12-bit
 * digital I2C accelerometer (`SA0=0 -> 0x1C`, `SA0=1 -> 0x1D`, `WHO_AM_I = 0x2A`).
 *
 * Register map & wire conventions per NXP MMA8452Q Rev 8.1 datasheet:
 * - `0x00 STATUS`: `0xFF` (ZYXDR/ZYXOW ready) when `CTRL_REG1.ACTIVE == 1`, `0x00` in STANDBY.
 * - `0x01..0x06 OUT_X_MSB..OUT_Z_LSB`: 12-bit signed two's complement, **left-justified** big-endian
 *   (`MSB = (s12 >> 4) & 0xFF`, `LSB = (s12 << 4) & 0xF0`), scaled per `XYZ_DATA_CFG[1:0]`
 *   (`00 -> ±2g / 1024 LSB/g`, `01 -> ±4g / 512 LSB/g`, `10 -> ±8g / 256 LSB/g`).
 * - `0x0B SYSMOD`: `0x01` (WAKE) when active, `0x00` (STANDBY) otherwise.
 * - `0x0D WHO_AM_I`: `0x2A` (read-only).
 * - `0x0E..0x31`: Full configuration & user offset trim (`OFF_X/Y/Z` at `0x2F..0x31`, `2 mg/LSB`).
 * - Auto-incrementing read pointer with `CTRL_REG1.F_READ` (8-bit fast-read skip) & `0x06 -> 0x00`
 *   datasheet wrap.
 */
internal class Mma8452qDevice(
    override val address7: Int,
    private val sampleGravityG: () -> CameraGravityVector = { CameraGravityVector(1.0, 0.0, 0.0) },
) : I2cSlaveDevice {
    init {
        require(address7 == ADDRESS_SA0_LOW || address7 == ADDRESS_SA0_HIGH) {
            "MMA8452Q only supports 7-bit addresses 0x1C (SA0=0) or 0x1D (SA0=1), got 0x${address7.toString(16)}"
        }
    }

    private val regs = IntArray(REGISTER_COUNT)
    private var selected: Boolean = false
    private var expectPointer: Boolean = false
    private var pointer: Int = 0
    private val latchedSampleBytes = IntArray(6)
    private var sampleLatched: Boolean = false
    private var readCount: Long = 0L
    private var writeCount: Long = 0L

    init {
        resetRegisters()
    }

    private fun resetRegisters() {
        regs.fill(0)
        regs[REG_WHO_AM_I] = WHO_AM_I_MMA8452Q
        regs[REG_PL_CFG] = 0x80 // Datasheet reset value: DBCNTM=1, PL_EN=0
        selected = false
        expectPointer = false
        pointer = 0
        latchedSampleBytes.fill(0)
        sampleLatched = false
    }

    val active: Boolean get() = (regs[REG_CTRL_REG1] and CTRL1_ACTIVE) != 0
    val fastRead: Boolean get() = (regs[REG_CTRL_REG1] and CTRL1_F_READ) != 0
    val fullScaleRangeG: Int
        get() = when (regs[REG_XYZ_DATA_CFG] and 0x03) {
            0 -> 2
            1 -> 4
            else -> 8
        }

    fun lsbPerG(): Double = when (regs[REG_XYZ_DATA_CFG] and 0x03) {
        0 -> 1024.0
        1 -> 512.0
        else -> 256.0
    }

    /** Encodes current physical gravity + user offset trim into 12-bit signed `(rawX, rawY, rawZ)`. */
    fun currentRaw12(): Triple<Int, Int, Int> {
        val g = sampleGravityG()
        val scale = lsbPerG()
        // Datasheet Section 6.7: OFF_X/Y/Z are signed 8-bit values at 2 mg/LSB (0.002 g/LSB).
        val offX = regs[REG_OFF_X].toByte().toInt() * 0.002
        val offY = regs[REG_OFF_Y].toByte().toInt() * 0.002
        val offZ = regs[REG_OFF_Z].toByte().toInt() * 0.002
        val rx = ((g.xG + offX) * scale).roundToInt().coerceIn(-2048, 2047)
        val ry = ((g.yG + offY) * scale).roundToInt().coerceIn(-2048, 2047)
        val rz = ((g.zG + offZ) * scale).roundToInt().coerceIn(-2048, 2047)
        return Triple(rx, ry, rz)
    }

    private fun latchOutputSample() {
        val (rx, ry, rz) = if (active) currentRaw12() else Triple(0, 0, 0)
        packLeftJustified12(rx, latchedSampleBytes, 0)
        packLeftJustified12(ry, latchedSampleBytes, 2)
        packLeftJustified12(rz, latchedSampleBytes, 4)
        sampleLatched = true
    }

    override fun address(byte: Int): Boolean {
        require(byte in 0..255)
        if ((byte ushr 1) != address7) {
            stop()
            return false
        }
        selected = true
        val isRead = (byte and 1) != 0
        if (!isRead) {
            expectPointer = true
            sampleLatched = false
        } else {
            expectPointer = false
            if (pointer in REG_OUT_X_MSB..REG_OUT_Z_LSB) {
                latchOutputSample()
            }
        }
        return true
    }

    override fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected) { "MMA8452Q write outside selected address phase" }
        if (expectPointer) {
            require(byte in 0 until REGISTER_COUNT) {
                "MMA8452Q register pointer out of range: 0x${byte.toString(16)}"
            }
        }
    }

    override fun write(byte: Int) {
        checkWrite(byte)
        if (expectPointer) {
            pointer = byte
            expectPointer = false
            return
        }
        writeRegister(pointer, byte)
        writeCount++
        pointer = nextPointer(pointer, isWrite = true)
    }

    private fun writeRegister(reg: Int, value: Int) {
        val v = value and 0xff
        when (reg) {
            REG_STATUS,
            REG_OUT_X_MSB, REG_OUT_X_LSB,
            REG_OUT_Y_MSB, REG_OUT_Y_LSB,
            REG_OUT_Z_MSB, REG_OUT_Z_LSB,
            REG_SYSMOD, REG_INT_SOURCE, REG_WHO_AM_I,
            REG_PL_STATUS, REG_FF_MT_SRC, REG_TRANSIENT_SRC, REG_PULSE_SRC,
            -> Unit // Read-only status/data/ID registers ignore writes per datasheet
            REG_CTRL_REG2 -> {
                if ((v and CTRL2_RST) != 0) {
                    resetRegisters()
                    selected = true
                } else {
                    regs[reg] = v
                }
            }
            else -> {
                if (reg in 0 until REGISTER_COUNT) {
                    regs[reg] = v
                }
            }
        }
    }

    override fun peekRead(): Int {
        require(selected) { "MMA8452Q read outside selected address phase" }
        if (pointer in REG_OUT_X_MSB..REG_OUT_Z_LSB && !sampleLatched) {
            latchOutputSample()
        }
        return when (pointer) {
            REG_STATUS -> if (active) 0xFF else 0x00
            REG_OUT_X_MSB -> latchedSampleBytes[0]
            REG_OUT_X_LSB -> latchedSampleBytes[1]
            REG_OUT_Y_MSB -> latchedSampleBytes[2]
            REG_OUT_Y_LSB -> latchedSampleBytes[3]
            REG_OUT_Z_MSB -> latchedSampleBytes[4]
            REG_OUT_Z_LSB -> latchedSampleBytes[5]
            REG_SYSMOD -> if (active) 0x01 else 0x00
            REG_WHO_AM_I -> WHO_AM_I_MMA8452Q
            else -> if (pointer in 0 until REGISTER_COUNT) regs[pointer] else 0x00
        }
    }

    override fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "MMA8452Q read byte changed before completion" }
        readCount++
        pointer = nextPointer(pointer, isWrite = false)
        if (pointer == REG_STATUS) {
            sampleLatched = false
        }
    }

    private fun nextPointer(current: Int, isWrite: Boolean): Int {
        if (!isWrite && fastRead && current in REG_OUT_X_MSB..REG_OUT_Z_LSB) {
            return when (current) {
                REG_OUT_X_MSB -> REG_OUT_Y_MSB
                REG_OUT_Y_MSB -> REG_OUT_Z_MSB
                else -> REG_STATUS
            }
        }
        if (!isWrite && current == REG_OUT_Z_LSB) {
            return REG_STATUS
        }
        return if (current + 1 < REGISTER_COUNT) current + 1 else 0
    }

    override fun stop() {
        selected = false
        expectPointer = false
        sampleLatched = false
    }

    fun state(): Map<String, Any?> {
        val (rx, ry, rz) = currentRaw12()
        return linkedMapOf(
            "chip" to "NXP MMA8452Q",
            "address7" to address7,
            "whoAmI" to WHO_AM_I_MMA8452Q,
            "active" to active,
            "fullScaleG" to fullScaleRangeG,
            "ctrlReg1" to regs[REG_CTRL_REG1],
            "xyzDataCfg" to regs[REG_XYZ_DATA_CFG],
            "rawX12" to rx,
            "rawY12" to ry,
            "rawZ12" to rz,
            "readCount" to readCount,
            "writeCount" to writeCount,
        )
    }

    internal class ParsedSnapshot(
        val regs: IntArray,
        val selected: Boolean,
        val expectPointer: Boolean,
        val pointer: Int,
        val latchedSampleBytes: IntArray,
        val sampleLatched: Boolean,
        val readCount: Long,
        val writeCount: Long,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "address7" to address7,
        "regs" to regs.toList(),
        "selected" to selected,
        "expectPointer" to expectPointer,
        "pointer" to pointer,
        "latchedSampleBytes" to latchedSampleBytes.toList(),
        "sampleLatched" to sampleLatched,
        "readCount" to readCount,
        "writeCount" to writeCount,
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid MMA8452Q snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Invalid keys in MMA8452Q snapshot" }
        val savedAddr = snapInt(map["address7"], "accel.address7", 0x1C..0x1D)
        require(savedAddr == address7) { "MMA8452Q address mismatch: expected $address7, got $savedAddr" }
        val rawRegs = map["regs"] as? List<*> ?: throw IllegalArgumentException("Missing accel.regs")
        require(rawRegs.size == REGISTER_COUNT) { "Unexpected accel.regs size ${rawRegs.size}" }
        val parsedRegs = IntArray(REGISTER_COUNT) { i -> snapInt(rawRegs[i], "accel.regs[$i]", 0..255) }
        val savedSelected = map["selected"] as? Boolean ?: throw IllegalArgumentException("Invalid accel.selected")
        val savedExpect = map["expectPointer"] as? Boolean ?: throw IllegalArgumentException("Invalid accel.expectPointer")
        val savedPtr = snapInt(map["pointer"], "accel.pointer", 0 until REGISTER_COUNT)
        val rawSample = map["latchedSampleBytes"] as? List<*> ?: throw IllegalArgumentException("Missing accel.latchedSampleBytes")
        require(rawSample.size == 6) { "Unexpected accel.latchedSampleBytes size ${rawSample.size}" }
        val parsedSample = IntArray(6) { i -> snapInt(rawSample[i], "accel.latchedSampleBytes[$i]", 0..255) }
        val savedLatched = map["sampleLatched"] as? Boolean ?: throw IllegalArgumentException("Invalid accel.sampleLatched")
        val savedReads = snapLong(map["readCount"], "accel.readCount")
        val savedWrites = snapLong(map["writeCount"], "accel.writeCount")
        return ParsedSnapshot(
            regs = parsedRegs,
            selected = savedSelected,
            expectPointer = savedExpect,
            pointer = savedPtr,
            latchedSampleBytes = parsedSample,
            sampleLatched = savedLatched,
            readCount = savedReads,
            writeCount = savedWrites,
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        parsed.regs.copyInto(regs)
        regs[REG_WHO_AM_I] = WHO_AM_I_MMA8452Q
        selected = parsed.selected
        expectPointer = parsed.expectPointer
        pointer = parsed.pointer
        parsed.latchedSampleBytes.copyInto(latchedSampleBytes)
        sampleLatched = parsed.sampleLatched
        readCount = parsed.readCount
        writeCount = parsed.writeCount
    }

    fun reset() {
        resetRegisters()
        readCount = 0L
        writeCount = 0L
    }

    companion object {
        const val ADDRESS_SA0_LOW = 0x1C
        const val ADDRESS_SA0_HIGH = 0x1D
        const val WHO_AM_I_MMA8452Q = 0x2A
        const val REGISTER_COUNT = 0x32

        const val REG_STATUS = 0x00
        const val REG_OUT_X_MSB = 0x01
        const val REG_OUT_X_LSB = 0x02
        const val REG_OUT_Y_MSB = 0x03
        const val REG_OUT_Y_LSB = 0x04
        const val REG_OUT_Z_MSB = 0x05
        const val REG_OUT_Z_LSB = 0x06
        const val REG_SYSMOD = 0x0B
        const val REG_INT_SOURCE = 0x0C
        const val REG_WHO_AM_I = 0x0D
        const val REG_XYZ_DATA_CFG = 0x0E
        const val REG_HP_FILTER_CUTOFF = 0x0F
        const val REG_PL_STATUS = 0x10
        const val REG_PL_CFG = 0x11
        const val REG_FF_MT_SRC = 0x16
        const val REG_TRANSIENT_SRC = 0x1E
        const val REG_PULSE_SRC = 0x22
        const val REG_CTRL_REG1 = 0x2A
        const val REG_CTRL_REG2 = 0x2B
        const val REG_OFF_X = 0x2F
        const val REG_OFF_Y = 0x30
        const val REG_OFF_Z = 0x31

        private const val CTRL1_ACTIVE = 0x01
        private const val CTRL1_F_READ = 0x02
        private const val CTRL2_RST = 0x40

        private val SNAPSHOT_KEYS = setOf(
            "address7", "regs", "selected", "expectPointer", "pointer",
            "latchedSampleBytes", "sampleLatched", "readCount", "writeCount",
        )

        internal fun packLeftJustified12(raw12: Int, out: IntArray, offset: Int) {
            val clamped = raw12.coerceIn(-2048, 2047)
            val shifted = (clamped shl 4) and 0xFFFF
            out[offset] = (shifted ushr 8) and 0xFF
            out[offset + 1] = shifted and 0xF0
        }

        internal fun unpackLeftJustified12(msb: Int, lsb: Int): Int {
            val raw16 = ((msb and 0xFF) shl 8) or (lsb and 0xF0)
            // Arithmetic shift right by 4 preserves 12-bit two's complement sign
            return raw16.toShort().toInt() shr 4
        }

        private fun snapLong(raw: Any?, field: String): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "Expected integer for $field" }
            val v = (raw as Number).toLong()
            require(v >= 0L) { "Negative integer for $field" }
            return v
        }

        private fun snapInt(raw: Any?, field: String, range: IntRange): Int {
            val v = snapLong(raw, field)
            require(v in range.first.toLong()..range.last.toLong()) { "Out-of-range integer for $field: $v" }
            return v.toInt()
        }
    }
}
