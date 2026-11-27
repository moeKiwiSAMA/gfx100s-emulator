package com.github.nyabanban.fuji.emulator.components.wm8962

import com.github.nyabanban.fuji.emulator.api.AudioRouteHistory
import com.github.nyabanban.fuji.emulator.api.CameraTimedAudioCodec
import com.github.nyabanban.fuji.emulator.api.AudioOutputRoute
import com.github.nyabanban.fuji.emulator.api.AudioSinkKind
import com.github.nyabanban.fuji.emulator.api.I2cSlaveDevice
import com.github.nyabanban.fuji.emulator.api.SpiPeer
import java.util.TreeMap
import kotlin.math.pow

/**
 * Pure datasheet-faithful model of the **Wolfson / Cirrus Logic `WM8962`** Ultra-Low-Power Audio
 * Codec supporting BOTH its authentic control interfaces over one shared 16-bit register & DSP2
 * RAM file:
 *
 * 1. **2-Wire I2C (`0x1A` on `I2C1`)**: 16-bit big-endian register address (`addrHi`, `addrLo`) +
 *    16-bit big-endian register data reads/writes with auto-increment (`0x0000` & `0x000F` return
 *    Device ID **`0x6243`**).
 * 2. **4-Wire SPI (`SPI4` `wm8962.spi`)**: GFX100S 2.13 `GXUP0004.DAT` (`section4 0x01291EF8..0x01294308`)
 *    2-word 16-bit protocol (`bit15` = R/W, `bits[14:0]` = 15-bit reg/DSP2 RAM address; Word 1 = 16-bit data).
 * 3. **`AudioCodecControlPort` (`wm8962.codec`)**: live speaker, headphone, DAC mute, SYSCLK, DSP2,
 *    and ADC/microphone input routing telemetry for host audio playback/capture.
 */
internal class Wm8962CodecDevice(
    override val address7: Int = DEFAULT_ADDRESS7,
    private val clock: () -> Long = { 0L },
) : I2cSlaveDevice, SpiPeer, CameraTimedAudioCodec {
    init {
        require(address7 in 0x08..0x77) { "WM8962 7-bit I2C address out of range: 0x${address7.toString(16)}" }
    }

    // Shared 16-bit register & DSP2 RAM map
    private val registers = TreeMap<Int, Int>()
    private val routes = AudioRouteHistory(outputRoute())

    @Synchronized
    override fun outputRouteAt(tick: Long) = routes.at(tick)

    // 4-wire SPI4 state
    private var spiExpectingValue = false
    private var spiLatchedAddress = 0
    private var spiLatchedRead = false
    private var spiWordCount = 0L
    private var writeCount = 0L
    private var readCount = 0L
    private var resetCount = 0L

    // 2-wire I2C state (16-bit BE address + 16-bit BE data)
    private var i2cSelected = false
    private var i2cPhase = I2C_PHASE_ADDR_HI
    private var i2cAddr = 0
    private var i2cPendingHi = 0
    private var i2cReadLoPending = false
    private var i2cReadWordCache = 0
    private var i2cReadCount = 0L
    private var i2cWriteCount = 0L

    @Synchronized
    override fun exchange(bits: Int, tx: Int, tick: Long): Int {
        require(bits == 16) { "WM8962 SPI4 peer requires 16-bit transfers, got $bits" }
        require(tx in 0..0xFFFF) { "WM8962 SPI4 word outside 16-bit range: $tx" }
        return exchangeAt(tx, tick)
    }

    @Synchronized
    override fun exchange16(mosi: Int): Int = exchangeAt(mosi, clock())

    @Synchronized
    private fun exchangeAt(mosi: Int, tick: Long): Int {
        val word = mosi and 0xFFFF
        spiWordCount++
        if (!spiExpectingValue) {
            spiLatchedRead = (word and 0x8000) != 0
            spiLatchedAddress = word and 0x7FFF
            spiExpectingValue = true
            return 0
        }
        spiExpectingValue = false
        val addr = spiLatchedAddress
        val isRead = spiLatchedRead
        spiLatchedAddress = 0
        spiLatchedRead = false
        return if (isRead) {
            readCount++
            readRegister(addr)
        } else {
            writeCount++
            writeRegister(addr, word, tick)
            0
        }
    }

    @Synchronized
    override fun address(byte: Int): Boolean {
        require(byte in 0..255)
        if ((byte ushr 1) != address7) {
            stop()
            return false
        }
        i2cSelected = true
        val isRead = (byte and 1) != 0
        if (!isRead) {
            i2cPhase = I2C_PHASE_ADDR_HI
        } else {
            i2cReadLoPending = false
        }
        return true
    }

    @Synchronized
    override fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(i2cSelected) { "WM8962 I2C write outside selected address phase" }
    }

    @Synchronized
    override fun write(byte: Int) {
        checkWrite(byte)
        val b = byte and 0xFF
        when (i2cPhase) {
            I2C_PHASE_ADDR_HI -> {
                i2cPendingHi = b
                i2cPhase = I2C_PHASE_ADDR_LO
            }
            I2C_PHASE_ADDR_LO -> {
                i2cAddr = ((i2cPendingHi shl 8) or b) and 0x7FFF
                i2cPhase = I2C_PHASE_DATA_HI
            }
            I2C_PHASE_DATA_HI -> {
                i2cPendingHi = b
                i2cPhase = I2C_PHASE_DATA_LO
            }
            I2C_PHASE_DATA_LO -> {
                val value16 = (i2cPendingHi shl 8) or b
                writeRegister(i2cAddr, value16, clock())
                writeCount++
                i2cWriteCount++
                i2cAddr = (i2cAddr + 1) and 0x7FFF
                i2cPhase = I2C_PHASE_DATA_HI
            }
        }
    }

    @Synchronized
    override fun peekRead(): Int {
        require(i2cSelected) { "WM8962 I2C read outside selected address phase" }
        if (!i2cReadLoPending) {
            i2cReadWordCache = readRegisterI2c(i2cAddr)
            return (i2cReadWordCache ushr 8) and 0xFF
        }
        return i2cReadWordCache and 0xFF
    }

    @Synchronized
    override fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "WM8962 I2C read byte changed before completion" }
        if (!i2cReadLoPending) {
            i2cReadLoPending = true
        } else {
            i2cReadLoPending = false
            readCount++
            i2cReadCount++
            i2cAddr = (i2cAddr + 1) and 0x7FFF
        }
    }

    @Synchronized
    override fun stop() {
        i2cSelected = false
        i2cPhase = I2C_PHASE_ADDR_HI
        i2cReadLoPending = false
    }

    private fun readRegisterI2c(address: Int): Int {
        val maskedAddr = address and 0x7FFF
        // WM8962 returns Device ID 0x6243 on R15 (0x000F) and R0 (0x0000) when untouched
        if (maskedAddr == REG_SOFTWARE_RESET || (maskedAddr == REG_SW_RESET_ALIAS && maskedAddr !in registers)) {
            return DEVICE_ID
        }
        return registers[maskedAddr] ?: 0
    }

    private fun writeRegister(address: Int, value: Int, tick: Long) {
        val maskedAddr = address and 0x7FFF
        val maskedVal = value and 0xFFFF
        if (maskedAddr == REG_SOFTWARE_RESET) {
            registers.clear()
            resetCount++
        } else if (maskedVal == 0) {
            registers.remove(maskedAddr)
        } else {
            registers[maskedAddr] = maskedVal
        }
        when (maskedAddr) {
            REG_SOFTWARE_RESET, REG_HPOUTL_VOL, REG_HPOUTR_VOL, REG_SPKOUTL_VOL, REG_ADC_DAC_CTRL_1,
            REG_PWR_MGMT_2, REG_CLASS_D_CTRL_1, REG_CHARGE_PUMP_1, REG_CLOCKING_2, REG_DSP2_PWR_MGMT ->
                routes.record(tick, outputRoute())
        }
    }

    @Synchronized
    override fun readRegister(address: Int): Int {
        val maskedAddr = address and 0x7FFF
        if (maskedAddr == REG_SOFTWARE_RESET) return DEVICE_ID
        return registers[maskedAddr] ?: 0
    }

    @Synchronized
    override fun resetCount(): Long = resetCount

    @Synchronized
    override fun dsp2ProgramWriteCount(): Int =
        registers.subMap(DSP2_PROG_START, true, DSP2_PROG_END, true).size

    @Synchronized
    override fun dsp2DataWriteCount(): Int =
        registers.subMap(DSP2_DATA_START, true, DSP2_DATA_END, true).size

    @Synchronized
    override fun dsp2CalibrationWriteCount(): Int =
        registers.subMap(DSP2_CALIB_START, true, DSP2_CALIB_END, true).size

    @Synchronized
    override fun outputRoute(): AudioOutputRoute {
        val pwr2 = registers[REG_PWR_MGMT_2] ?: 0
        val dacCtrl1 = registers[REG_ADC_DAC_CTRL_1] ?: 0
        val clk2 = registers[REG_CLOCKING_2] ?: 0
        val dsp2Pwr = registers[REG_DSP2_PWR_MGMT] ?: 0
        val classD1 = registers[REG_CLASS_D_CTRL_1] ?: 0
        val chargePump1 = registers[REG_CHARGE_PUMP_1] ?: 0
        val spkVolReg = registers[REG_SPKOUTL_VOL] ?: 0
        val hpLVolReg = registers[REG_HPOUTL_VOL] ?: 0
        val hpRVolReg = registers[REG_HPOUTR_VOL] ?: 0

        val dacMuted = (dacCtrl1 and 0x0008) != 0
        val sysclkEnabled = (clk2 and 0x0020) != 0
        val dsp2Enabled = (dsp2Pwr and 0x0001) != 0
        val spkEnabled = (pwr2 and 0x0010) != 0 && (classD1 and 0x0040) != 0
        val hpEnabled = (pwr2 and 0x0060) != 0 && (chargePump1 and 0x0001) != 0

        val spkGain = if (spkEnabled && !dacMuted) volumeCodeToLinearGain(spkVolReg) else 0.0f
        val hpLeftGain = if (hpEnabled && (pwr2 and 0x0040) != 0 && !dacMuted) volumeCodeToLinearGain(hpLVolReg) else 0.0f
        val hpRightGain = if (hpEnabled && (pwr2 and 0x0020) != 0 && !dacMuted) volumeCodeToLinearGain(hpRVolReg) else 0.0f

        val activeSink = when {
            dacMuted || (!spkEnabled && !hpEnabled) -> AudioSinkKind.MUTED
            spkEnabled && hpEnabled -> AudioSinkKind.BOTH
            hpEnabled -> AudioSinkKind.HEADPHONE_STEREO
            else -> AudioSinkKind.SPEAKER_MONO
        }
        val (leftGain, rightGain) = when (activeSink) {
            AudioSinkKind.MUTED -> 0.0f to 0.0f
            AudioSinkKind.SPEAKER_MONO -> spkGain to spkGain
            AudioSinkKind.HEADPHONE_STEREO -> hpLeftGain to hpRightGain
            AudioSinkKind.BOTH -> maxOf(spkGain, hpLeftGain) to maxOf(spkGain, hpRightGain)
        }
        return AudioOutputRoute(
            speakerEnabled = spkEnabled,
            speakerVolume = spkVolReg and VOL_MASK,
            headphoneEnabled = hpEnabled,
            headphoneVolumeLeft = hpLVolReg and VOL_MASK,
            headphoneVolumeRight = hpRVolReg and VOL_MASK,
            dacMuted = dacMuted,
            sysclkEnabled = sysclkEnabled,
            dsp2Enabled = dsp2Enabled,
            effectiveLeftGain = leftGain,
            effectiveRightGain = rightGain,
            activeSink = activeSink,
        )
    }

    @Synchronized
    fun state(): Map<String, Any?> {
        val route = outputRoute()
        val pwr1 = registers[REG_PWR_MGMT_1] ?: 0
        val adcLVol = registers[REG_ADCL_VOL] ?: 0
        val adcRVol = registers[REG_ADCR_VOL] ?: 0
        return linkedMapOf(
            "chip" to "Wolfson WM8962",
            "address7" to address7,
            "chipId" to DEVICE_ID,
            "spiWordCount" to spiWordCount,
            "i2cReadCount" to i2cReadCount,
            "i2cWriteCount" to i2cWriteCount,
            "writeCount" to writeCount,
            "readCount" to readCount,
            "resetCount" to resetCount,
            "registerCount" to registers.size,
            "dsp2ProgramWords" to dsp2ProgramWriteCount(),
            "dsp2DataWords" to dsp2DataWriteCount(),
            "dsp2CalibrationWords" to dsp2CalibrationWriteCount(),
            "activeSink" to route.activeSink.name,
            "speakerEnabled" to route.speakerEnabled,
            "speakerVolume" to route.speakerVolume,
            "headphoneEnabled" to route.headphoneEnabled,
            "headphoneVolumeLeft" to route.headphoneVolumeLeft,
            "headphoneVolumeRight" to route.headphoneVolumeRight,
            "dacMuted" to route.dacMuted,
            "sysclkEnabled" to route.sysclkEnabled,
            "dsp2Enabled" to route.dsp2Enabled,
            "adcLeftEnabled" to ((pwr1 and 0x0008) != 0),
            "adcRightEnabled" to ((pwr1 and 0x0004) != 0),
            "inLeftEnabled" to ((pwr1 and 0x0020) != 0),
            "inRightEnabled" to ((pwr1 and 0x0010) != 0),
            "adcVolumeLeft" to (adcLVol and 0xFF),
            "adcVolumeRight" to (adcRVol and 0xFF),
        )
    }

    internal class ParsedSnapshot(
        val spiExpectingValue: Boolean,
        val spiLatchedAddress: Int,
        val spiLatchedRead: Boolean,
        val spiWordCount: Long,
        val writeCount: Long,
        val readCount: Long,
        val resetCount: Long,
        val i2cSelected: Boolean,
        val i2cPhase: Int,
        val i2cAddr: Int,
        val i2cPendingHi: Int,
        val i2cReadLoPending: Boolean,
        val i2cReadCount: Long,
        val i2cWriteCount: Long,
        val registers: Map<Int, Int>,
    )

    @Synchronized
    fun saveSnapshot(): Map<String, Any?> {
        val regMap = linkedMapOf<String, Int>()
        for ((k, v) in registers) regMap[k.toString(16)] = v
        return linkedMapOf(
            "address7" to address7,
            "spiExpectingValue" to spiExpectingValue,
            "spiLatchedAddress" to spiLatchedAddress,
            "spiLatchedRead" to spiLatchedRead,
            "spiWordCount" to spiWordCount,
            "writeCount" to writeCount,
            "readCount" to readCount,
            "resetCount" to resetCount,
            "i2cSelected" to i2cSelected,
            "i2cPhase" to i2cPhase,
            "i2cAddr" to i2cAddr,
            "i2cPendingHi" to i2cPendingHi,
            "i2cReadLoPending" to i2cReadLoPending,
            "i2cReadCount" to i2cReadCount,
            "i2cWriteCount" to i2cWriteCount,
            "registers" to regMap,
        )
    }

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid WM8962 snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected WM8962 snapshot keys" }
        require(snapInt(map["address7"], "wm8962.address7", 0x08..0x77) == address7) { "WM8962 address mismatch" }
        val rawRegs = map["registers"] as? Map<*, *> ?: throw IllegalArgumentException("Missing wm8962.registers")
        require(rawRegs.size <= MAX_REGISTER_ENTRIES) { "wm8962.registers too large" }
        val parsedRegs = TreeMap<Int, Int>()
        for ((k, v) in rawRegs) {
            require(k is String && k.isNotEmpty() && k.length <= 4) { "Invalid wm8962 register key: $k" }
            val regAddr = k.toIntOrNull(16) ?: throw IllegalArgumentException("Non-hex wm8962 register key: $k")
            require(regAddr in 0..0x7FFF && regAddr != REG_SOFTWARE_RESET) { "Invalid wm8962 register address: $k" }
            parsedRegs[regAddr] = snapInt(v, "wm8962.registers[$k]", 1..0xFFFF)
        }
        return ParsedSnapshot(
            spiExpectingValue = map["spiExpectingValue"] as? Boolean ?: throw IllegalArgumentException("Invalid spiExpectingValue"),
            spiLatchedAddress = snapInt(map["spiLatchedAddress"], "spiLatchedAddress", 0..0x7FFF),
            spiLatchedRead = map["spiLatchedRead"] as? Boolean ?: throw IllegalArgumentException("Invalid spiLatchedRead"),
            spiWordCount = snapLong(map["spiWordCount"], "spiWordCount"),
            writeCount = snapLong(map["writeCount"], "writeCount"),
            readCount = snapLong(map["readCount"], "readCount"),
            resetCount = snapLong(map["resetCount"], "resetCount"),
            i2cSelected = map["i2cSelected"] as? Boolean ?: throw IllegalArgumentException("Invalid i2cSelected"),
            i2cPhase = snapInt(map["i2cPhase"], "i2cPhase", 0..3),
            i2cAddr = snapInt(map["i2cAddr"], "i2cAddr", 0..0x7FFF),
            i2cPendingHi = snapInt(map["i2cPendingHi"], "i2cPendingHi", 0..0xFF),
            i2cReadLoPending = map["i2cReadLoPending"] as? Boolean ?: throw IllegalArgumentException("Invalid i2cReadLoPending"),
            i2cReadCount = snapLong(map["i2cReadCount"], "i2cReadCount"),
            i2cWriteCount = snapLong(map["i2cWriteCount"], "i2cWriteCount"),
            registers = parsedRegs,
        )
    }

    @Synchronized
    fun applySnapshot(parsed: ParsedSnapshot) {
        spiExpectingValue = parsed.spiExpectingValue
        spiLatchedAddress = parsed.spiLatchedAddress
        spiLatchedRead = parsed.spiLatchedRead
        spiWordCount = parsed.spiWordCount
        writeCount = parsed.writeCount
        readCount = parsed.readCount
        resetCount = parsed.resetCount
        i2cSelected = parsed.i2cSelected
        i2cPhase = parsed.i2cPhase
        i2cAddr = parsed.i2cAddr
        i2cPendingHi = parsed.i2cPendingHi
        i2cReadLoPending = parsed.i2cReadLoPending
        i2cReadWordCache = 0
        i2cReadCount = parsed.i2cReadCount
        i2cWriteCount = parsed.i2cWriteCount
        registers.clear()
        registers.putAll(parsed.registers)
        routes.reset(outputRoute())
    }

    companion object {
        const val DEFAULT_ADDRESS7 = 0x1A
        const val DEVICE_ID = 0x6243
        const val SPI_PORT_NAME = "wm8962.spi"
        const val CODEC_PORT_NAME = "wm8962.codec"

        const val REG_SW_RESET_ALIAS = 0x0000
        const val REG_HPOUTL_VOL = 0x0002
        const val REG_HPOUTR_VOL = 0x0003
        const val REG_ADC_DAC_CTRL_1 = 0x0005
        const val REG_CLOCKING_2 = 0x0008
        const val REG_SOFTWARE_RESET = 0x000F
        const val REG_ADCL_VOL = 0x0015
        const val REG_ADCR_VOL = 0x0016
        const val REG_PWR_MGMT_1 = 0x0019
        const val REG_PWR_MGMT_2 = 0x001A
        const val REG_SPKOUTL_VOL = 0x0028
        const val REG_CLASS_D_CTRL_1 = 0x0031
        const val REG_CHARGE_PUMP_1 = 0x0048
        const val REG_DSP2_PWR_MGMT = 0x0300

        const val DSP2_PROG_START = 0x2000
        const val DSP2_PROG_END = 0x29FF
        const val DSP2_DATA_START = 0x3000
        const val DSP2_DATA_END = 0x3FFF
        const val DSP2_CALIB_START = 0x4200
        const val DSP2_CALIB_END = 0x46FF

        private const val VOL_MUTE_MAX = 0x2F
        private const val VOL_ZERO_DB = 0x79
        private const val VOL_MASK = 0x7F
        private const val MAX_REGISTER_ENTRIES = 16384

        private const val I2C_PHASE_ADDR_HI = 0
        private const val I2C_PHASE_ADDR_LO = 1
        private const val I2C_PHASE_DATA_HI = 2
        private const val I2C_PHASE_DATA_LO = 3

        private val SNAPSHOT_KEYS = setOf(
            "address7", "spiExpectingValue", "spiLatchedAddress", "spiLatchedRead",
            "spiWordCount", "writeCount", "readCount", "resetCount",
            "i2cSelected", "i2cPhase", "i2cAddr", "i2cPendingHi", "i2cReadLoPending",
            "i2cReadCount", "i2cWriteCount", "registers",
        )

        internal fun volumeCodeToLinearGain(rawRegValue: Int): Float {
            val code = rawRegValue and VOL_MASK
            if (code <= VOL_MUTE_MAX) return 0.0f
            val db = (code - VOL_ZERO_DB).toDouble()
            return 10.0.pow(db / 20.0).toFloat().coerceIn(0.0f, 1.0f)
        }

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
