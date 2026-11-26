package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.AudioCodecControlPort
import com.github.nyabanban.fuji.emulator.api.AudioOutputRoute
import com.github.nyabanban.fuji.emulator.api.AudioSinkKind
import com.github.nyabanban.fuji.emulator.api.SpiPeer
import java.util.TreeMap
import kotlin.math.pow

/**
 * Opt-in Wolfson / Cirrus Logic WM8962 audio codec SPI4 peer (`soc-spi.wm8962`).
 *
 * Implements the 2-word 16-bit SPI4 control protocol observed in GFX100S 2.13 (`section4.bin`
 * `0x01291EF8..0x01294308`):
 * - Word 0: bit 15 = R/W flag (`0` = write, `1` = read), bits 14..0 = 15-bit register/RAM address.
 * - Word 1: 16-bit write data (MISO = `0`) or 16-bit read response (`R15 (0x000F)` defaults to `0x6243`).
 * - Writing `R15 (0x000F)` performs software reset of volatile registers and DSP2 RAM.
 * - Stores core registers (`0x0000..0x04FF`), DSP2 Program RAM (`0x2000..0x29FF`),
 *   DSP2 Data/Coefficient RAM (`0x3000..0x3FFF`), and Bank 14 calibration space (`0x4200..0x46FF`).
 */
internal class Wm8962SpiPeer : SpiPeer, AudioCodecControlPort {
    companion object {
        const val PEER_PORT = "soc-spi.wm8962"
        const val CODEC_PORT = "soc-spi.wm8962.codec"

        const val REG_HPOUTL_VOL = 0x0002
        const val REG_HPOUTR_VOL = 0x0003
        const val REG_ADC_DAC_CTRL_1 = 0x0005
        const val REG_CLOCKING_2 = 0x0008
        const val REG_AUDIO_IF_1 = 0x0009
        const val REG_SOFTWARE_RESET = 0x000F
        const val REG_PWR_MGMT_1 = 0x0019
        const val REG_PWR_MGMT_2 = 0x001A
        const val REG_SPKOUTL_VOL = 0x0028
        const val REG_CLASS_D_CTRL_1 = 0x0031
        const val REG_CLASS_D_CTRL_2 = 0x0033
        // WM8962 R72 (0x0048) = CHARGE_PUMP_1 (bit 0 = CP_ENA); verified in section4.bin @ 0x012925b0
        // and live SPI4 boot sequence (R0x048=0x0001 vs R0x052=0x0004).
        const val REG_CHARGE_PUMP_1 = 0x0048
        const val REG_DSP2_PWR_MGMT = 0x0300

        const val DEVICE_ID = 0x6243
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

        private val SNAPSHOT_KEYS = setOf(
            "expectingValue", "latchedAddress", "latchedRead",
            "wordCount", "writeCount", "readCount", "resetCount",
            "registers",
        )

        /**
         * Convert WM8962 7-bit analog output volume code (`0x00..0x7F`) to linear gain (`0.0..1.0`).
         * Per WM8962 datasheet: `0x00..0x2F` = mute; `0x30..0x7F` = -57 dB to +6 dB in 1 dB steps
         * (`0x79` = 0 dB). Normalized cleanly to `0.0..1.0` with `0x79` -> `1.0f` and clamped at `1.0f`.
         */
        internal fun volumeCodeToLinearGain(rawRegValue: Int): Float {
            val code = rawRegValue and VOL_MASK
            if (code <= VOL_MUTE_MAX) return 0.0f
            val db = (code - VOL_ZERO_DB).toDouble()
            val linear = 10.0.pow(db / 20.0).toFloat()
            return linear.coerceIn(0.0f, 1.0f)
        }

        private fun parseIntegralLong(value: Any?, field: String): Long = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            else -> throw IllegalArgumentException("$field requires integer")
        }

        private fun parseIntegralInt(value: Any?, field: String): Int =
            parseIntegralLong(value, field).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    }

    private var expectingValue = false
    private var latchedAddress = 0
    private var latchedRead = false
    private var wordCount = 0L
    private var writeCount = 0L
    private var readCount = 0L
    private var resetCount = 0L
    private val registers = TreeMap<Int, Int>()

    fun isInitial(): Boolean =
        !expectingValue && latchedAddress == 0 && !latchedRead &&
            wordCount == 0L && writeCount == 0L && readCount == 0L && resetCount == 0L &&
            registers.isEmpty()

    override fun exchange(bits: Int, tx: Int, tick: Long): Int {
        require(bits == 16) { "WM8962 SPI4 peer requires 16-bit transfers, got $bits" }
        require(tx in 0..0xFFFF) { "WM8962 SPI4 word outside 16-bit range: $tx" }
        return exchange16(tx)
    }

    @Synchronized
    override fun exchange16(mosi: Int): Int {
        val word = mosi and 0xFFFF
        wordCount++
        if (!expectingValue) {
            latchedRead = (word and 0x8000) != 0
            latchedAddress = word and 0x7FFF
            expectingValue = true
            return 0
        }
        expectingValue = false
        val addr = latchedAddress
        val isRead = latchedRead
        latchedAddress = 0
        latchedRead = false
        return if (isRead) {
            readCount++
            readRegister(addr)
        } else {
            writeCount++
            writeRegister(addr, word)
            0
        }
    }

    private fun writeRegister(address: Int, value: Int) {
        val maskedAddr = address and 0x7FFF
        val maskedVal = value and 0xFFFF
        if (maskedAddr == REG_SOFTWARE_RESET) {
            registers.clear()
            resetCount++
            return
        }
        if (maskedVal == 0) {
            registers.remove(maskedAddr)
        } else {
            registers[maskedAddr] = maskedVal
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
        // WM8962 R768 (0x0300) DSP2_POWER_MANAGEMENT: bit 0 = DSP2_ENA.
        // Firmware writes 0x0009 during initial microcode download and 0x0007 (0x01292714) for active runtime DSP2.
        val dsp2Enabled = (dsp2Pwr and 0x0001) != 0

        // GFX100S uses mono Class-D speaker on SPKOUTL (R26 bit4 + R49 bit6)
        val spkEnabled = (pwr2 and 0x0010) != 0 && (classD1 and 0x0040) != 0
        // Stereo ground-referenced headphone on HPOUTL/R (R26 bits 6:5 + R72 (0x0048) bit0 CP_ENA)
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
        return linkedMapOf(
            "chipId" to DEVICE_ID,
            "expectingValue" to expectingValue,
            "latchedAddress" to latchedAddress,
            "latchedRead" to latchedRead,
            "wordCount" to wordCount,
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
        )
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> {
        val regMap = linkedMapOf<String, Int>()
        for ((k, v) in registers) {
            regMap[k.toString(16)] = v
        }
        return linkedMapOf(
            "expectingValue" to expectingValue,
            "latchedAddress" to latchedAddress,
            "latchedRead" to latchedRead,
            "wordCount" to wordCount,
            "writeCount" to writeCount,
            "readCount" to readCount,
            "resetCount" to resetCount,
            "registers" to regMap,
        )
    }

    data class ParsedState(
        val expectingValue: Boolean,
        val latchedAddress: Int,
        val latchedRead: Boolean,
        val wordCount: Long,
        val writeCount: Long,
        val readCount: Long,
        val resetCount: Long,
        val registers: Map<Int, Int>,
    )

    fun parse(raw: Any?): ParsedState {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid wm8962 peer snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected wm8962 peer snapshot keys" }
        val exp = map["expectingValue"] as? Boolean ?: throw IllegalArgumentException("wm8962 expectingValue requires boolean")
        val addr = parseIntegralInt(map["latchedAddress"], "latchedAddress")
        require(addr in 0..0x7FFF) { "Out-of-range wm8962 latchedAddress: $addr" }
        val isRead = map["latchedRead"] as? Boolean ?: throw IllegalArgumentException("wm8962 latchedRead requires boolean")
        val words = parseIntegralLong(map["wordCount"], "wordCount")
        val writes = parseIntegralLong(map["writeCount"], "writeCount")
        val reads = parseIntegralLong(map["readCount"], "readCount")
        val resets = parseIntegralLong(map["resetCount"], "resetCount")
        require(words >= 0L && writes >= 0L && reads >= 0L && resets >= 0L) { "Negative wm8962 counters" }
        require(writes + reads <= words) { "wm8962 write/read counts exceed wordCount" }
        val rawRegs = map["registers"] as? Map<*, *> ?: throw IllegalArgumentException("wm8962 registers requires map")
        require(rawRegs.size <= MAX_REGISTER_ENTRIES) { "wm8962 registers exceed maximum size" }
        val parsedRegs = TreeMap<Int, Int>()
        for ((k, v) in rawRegs) {
            require(k is String && k.isNotEmpty() && k.length <= 4) { "Invalid wm8962 register key: $k" }
            val regAddr = k.toIntOrNull(16) ?: throw IllegalArgumentException("Non-hex wm8962 register key: $k")
            require(regAddr in 0..0x7FFF && regAddr != REG_SOFTWARE_RESET) { "Invalid wm8962 register address: $k" }
            val regVal = parseIntegralInt(v, "registers[$k]")
            require(regVal in 1..0xFFFF) { "Out-of-range wm8962 register value for $k: $regVal" }
            parsedRegs[regAddr] = regVal
        }
        return ParsedState(exp, addr, isRead, words, writes, reads, resets, parsedRegs)
    }

    @Synchronized
    fun restore(parsed: ParsedState?) {
        registers.clear()
        if (parsed == null) {
            expectingValue = false
            latchedAddress = 0
            latchedRead = false
            wordCount = 0L
            writeCount = 0L
            readCount = 0L
            resetCount = 0L
        } else {
            expectingValue = parsed.expectingValue
            latchedAddress = parsed.latchedAddress
            latchedRead = parsed.latchedRead
            wordCount = parsed.wordCount
            writeCount = parsed.writeCount
            readCount = parsed.readCount
            resetCount = parsed.resetCount
            registers.putAll(parsed.registers)
        }
    }
}
