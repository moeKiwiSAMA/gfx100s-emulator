package com.github.nyabanban.fuji.emulator.components.ispcmd

/**
 * Real-silicon IMX461 SPI register shadow model (`0xFFF6C200..0xFFF6C240`, opt-in replyProfile
 * `imx461-silicon-shadow-v1`) derived from GFX100S 2.13 `section5.bin` (`0x024F6A58..0x0250A4B0`,
 * `0x028DFC4C..0x028EC384`):
 *
 * - **Wire framing (`DAT`)**:
 *   - Write burst (`MODE[17:16] == 1`): `[0x81, reg_hi, reg_lo, val]` (8-byte DMA stride)
 *   - Read command (`MODE[17:16] == 3`): `[0x82, reg_hi, reg_lo]` -> reply byte in `0xFFF6C240[23:16]`
 * - **SPI Read-Gate (`0x004F bit 3 = 0x08`, `S5 0x024F718C`)**:
 *   - `[0x82, hi, lo]` returns the shadowed register byte ONLY while `reg[0x004F] & 0x08 != 0`;
 *     reads while the gate is closed return `0`.
 * - **Group-Hold (`REGHOLD`, `0x0002 bit 6 = 0x40`, `S5 0x024FCA68..B8`)**:
 *   - Writes to dynamic exposure/gain registers (`PGC 0x0012/0x0013`, `DGAIN 0x0018`,
 *     `VMAX 0x001F/0x0020`, `HMAX 0x0023/0x0024`, `SHR 0x0047..0x0049`) stage in [stagedRegisters]
 *     while `REGHOLD` is asserted and commit atomically when `REGHOLD` is cleared.
 * - **Real-Silicon Key Registers**:
 *   - `0x0000` (`STBY`: `bit 0` active/stream, boot default `0x04`)
 *   - `0x0001` (`MDSEL`: boot default `0xB1` from `T[23]`)
 *   - `0x0002` (`REGHOLD` `b6`, latch `b7`, control `b2`/`b0`)
 *   - `0x0012`/`0x0013` (`PGC` 16-bit LE analog gain)
 *   - `0x0018` (`DGAIN` 8-bit digital gain)
 *   - `0x001F`/`0x0020` (dynamic 12-bit `VMAX`)
 *   - `0x0023`/`0x0024` (dynamic 15-bit `HMAX`/`SVR`)
 *   - `0x0040`/`0x0041` (16-bit LE sensor temperature readout, `S5 0x02501878`, default `0x00FA` = 25.0 °C × 10)
 *   - `0x0047`/`0x0048`/`0x0049` (`SHR` 24-bit shutter exposure lines)
 *   - `0x004F` (SPI read-gate `bit 3 = 0x08`)
 *   - `0x019E` (sleep/standby gate `bit 2 = 0x04`, `S5 0x024FD938`/`0x024FDE1C`)
 */
internal class Imx461SpiShadow(
    private val temperatureRawProvider: () -> Int = { DEFAULT_SENSOR_TEMP_RAW },
) {
    private val activeRegisters = linkedMapOf<Int, Int>()
    private val stagedRegisters = linkedMapOf<Int, Int>()
    private var writeOps: Long = 0L
    private var readOps: Long = 0L
    private var gatedBlockedReads: Long = 0L

    init {
        resetDefaults()
    }

    private fun resetDefaults() {
        activeRegisters.clear()
        stagedRegisters.clear()
        activeRegisters[REG_STBY] = DEFAULT_STBY
        activeRegisters[REG_MDSEL] = DEFAULT_MDSEL
        activeRegisters[REG_REGHOLD] = 0x00
        activeRegisters[REG_PGC_LO] = 0x00
        activeRegisters[REG_PGC_HI] = 0x00
        activeRegisters[REG_DGAIN] = 0x00
        activeRegisters[REG_VMAX_LO] = 0x00
        activeRegisters[REG_VMAX_HI] = 0x00
        activeRegisters[REG_HMAX_LO] = 0x00
        activeRegisters[REG_HMAX_HI] = 0x00
        activeRegisters[REG_SHR_0] = 0x00
        activeRegisters[REG_SHR_1] = 0x00
        activeRegisters[REG_SHR_2] = 0x00
        activeRegisters[REG_READ_GATE] = 0x00
        activeRegisters[REG_SLEEP_GATE] = 0x00
    }

    val isRegholdActive: Boolean
        get() = ((activeRegisters[REG_REGHOLD] ?: 0) and REGHOLD_BIT) != 0

    val isReadGateOpen: Boolean
        get() = ((activeRegisters[REG_READ_GATE] ?: 0) and READ_GATE_BIT) != 0

    val isStreaming: Boolean
        get() = ((activeRegisters[REG_STBY] ?: 0) and 0x01) != 0

    val isSleepAwake: Boolean
        get() = ((activeRegisters[REG_SLEEP_GATE] ?: 0) and SLEEP_AWAKE_BIT) != 0

    /** Processes one `[0x81, hi, lo, val]` SPI register write. */
    fun writeRegister(selector: Int, rawValue: Int) {
        val sel = selector and 0xFFFF
        val v = rawValue and 0xFF
        writeOps++
        if (sel == REG_REGHOLD) {
            val wasHold = isRegholdActive
            activeRegisters[REG_REGHOLD] = v
            val nowHold = (v and REGHOLD_BIT) != 0
            if (wasHold && !nowHold && stagedRegisters.isNotEmpty()) {
                activeRegisters.putAll(stagedRegisters)
                stagedRegisters.clear()
            }
            return
        }
        if (isRegholdActive && sel in GROUP_HOLD_REGISTERS) {
            stagedRegisters[sel] = v
        } else {
            activeRegisters[sel] = v
        }
    }

    /**
     * Executes one `[0x82, hi, lo]` SPI register read subject to `0x004F bit 3 = 0x08` read-gate.
     * Returns the 8-bit register value (`0..0xFF`), or `0` if the SPI read-gate is closed.
     */
    fun readRegister(selector: Int): Int {
        val sel = selector and 0xFFFF
        readOps++
        if (!isReadGateOpen) {
            gatedBlockedReads++
            return 0
        }
        return when (sel) {
            REG_TEMP_LO -> activeRegisters[REG_TEMP_LO] ?: (temperatureRawProvider() and 0xFF)
            REG_TEMP_HI -> activeRegisters[REG_TEMP_HI] ?: ((temperatureRawProvider() ushr 8) and 0xFF)
            else -> activeRegisters[sel] ?: 0
        }
    }

    fun pgc16(): Int =
        (activeRegisters[REG_PGC_LO] ?: 0) or ((activeRegisters[REG_PGC_HI] ?: 0) shl 8)

    fun svr12(): Int =
        (activeRegisters[REG_SVR_LO] ?: 0) or (((activeRegisters[REG_SVR_HI] ?: 0) and 0x0F) shl 8)

    fun shr15(): Int =
        (activeRegisters[REG_SHR_15_LO] ?: 0) or (((activeRegisters[REG_SHR_15_HI] ?: 0) and 0x7F) shl 8)

    fun pgc12(): Int =
        (activeRegisters[REG_PGC_12_LO] ?: 0) or (((activeRegisters[REG_PGC_12_HI] ?: 0) and 0x0F) shl 8)

    fun pgcSecondary12(): Int =
        (activeRegisters[REG_PGC2_LO] ?: 0) or (((activeRegisters[REG_PGC2_HI] ?: 0) and 0x0F) shl 8)

    fun hcgEnabled(): Boolean = ((activeRegisters[REG_HCG] ?: 0) and 0x01) != 0

    fun dgain8(): Int = activeRegisters[REG_DGAIN] ?: 0

    fun mdsel8(): Int = activeRegisters[REG_MDSEL] ?: 0

    fun modeFlags04(): Int = activeRegisters[REG_MODE_FLAGS_04] ?: 0

    fun modeFlags05(): Int = activeRegisters[REG_MODE_FLAGS_05] ?: 0

    fun movieShr15(): Int = hmax15()

    fun hasExposureRegs(): Boolean =
        REG_SHR_15_LO in activeRegisters || REG_SHR_15_HI in activeRegisters ||
            REG_PGC_12_LO in activeRegisters || REG_PGC_12_HI in activeRegisters

    fun shr24(): Int =
        (activeRegisters[REG_SHR_0] ?: 0) or
            ((activeRegisters[REG_SHR_1] ?: 0) shl 8) or
            ((activeRegisters[REG_SHR_2] ?: 0) shl 16)

    fun vmax12(): Int =
        (activeRegisters[REG_VMAX_LO] ?: 0) or (((activeRegisters[REG_VMAX_HI] ?: 0) and 0x0F) shl 8)

    fun hmax15(): Int =
        (activeRegisters[REG_HMAX_LO] ?: 0) or (((activeRegisters[REG_HMAX_HI] ?: 0) and 0x7F) shl 8)

    fun tempRaw16(): Int {
        val lo = activeRegisters[REG_TEMP_LO] ?: (temperatureRawProvider() and 0xFF)
        val hi = activeRegisters[REG_TEMP_HI] ?: ((temperatureRawProvider() ushr 8) and 0xFF)
        return (hi shl 8) or lo
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "stby" to (activeRegisters[REG_STBY] ?: 0),
        "streaming" to isStreaming,
        "sleepGate" to (activeRegisters[REG_SLEEP_GATE] ?: 0),
        "sleepAwake" to isSleepAwake,
        "mdsel" to (activeRegisters[REG_MDSEL] ?: 0),
        "regholdActive" to isRegholdActive,
        "readGateOpen" to isReadGateOpen,
        "pgc16" to pgc16(),
        "dgain" to (activeRegisters[REG_DGAIN] ?: 0),
        "shr24" to shr24(),
        "vmax12" to vmax12(),
        "hmax15" to hmax15(),
        "tempRaw16" to tempRaw16(),
        "registerCount" to activeRegisters.size,
        "stagedCount" to stagedRegisters.size,
        "writeOps" to writeOps,
        "readOps" to readOps,
        "gatedBlockedReads" to gatedBlockedReads,
    )

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "registers" to activeRegisters.entries.associate { (k, v) -> k.toString() to v.toLong() },
        "staged" to stagedRegisters.entries.associate { (k, v) -> k.toString() to v.toLong() },
        "writeOps" to writeOps,
        "readOps" to readOps,
        "gatedBlockedReads" to gatedBlockedReads,
    )

    fun restore(raw: Map<*, *>) {
        require(raw.keys == SNAPSHOT_KEYS && raw["profile"] == PROFILE) {
            "Unexpected IMX461 shadow snapshot format"
        }
        val nextActive = decodeRegMap(raw["registers"], "imx461.registers")
        val nextStaged = decodeRegMap(raw["staged"], "imx461.staged")
        val nextW = reqLong(raw["writeOps"], "imx461.writeOps", 0L, Long.MAX_VALUE)
        val nextR = reqLong(raw["readOps"], "imx461.readOps", 0L, Long.MAX_VALUE)
        val nextBlocked = reqLong(raw["gatedBlockedReads"], "imx461.gatedBlockedReads", 0L, Long.MAX_VALUE)
        activeRegisters.clear()
        activeRegisters.putAll(nextActive)
        stagedRegisters.clear()
        stagedRegisters.putAll(nextStaged)
        writeOps = nextW
        readOps = nextR
        gatedBlockedReads = nextBlocked
    }

    companion object {
        const val PROFILE = "imx461-silicon-shadow-v1"

        const val REG_STBY = 0x0000
        const val REG_MDSEL = 0x0001
        const val REG_REGHOLD = 0x0002
        const val REG_MODE_FLAGS_04 = 0x0004
        const val REG_MODE_FLAGS_05 = 0x0005
        const val REG_PGC_LO = 0x0012
        const val REG_PGC_HI = 0x0013
        const val REG_SVR_LO = 0x0012
        const val REG_SVR_HI = 0x0013
        const val REG_SHR_15_LO = 0x0016
        const val REG_SHR_15_HI = 0x0017
        const val REG_DGAIN = 0x0018
        const val REG_VMAX_LO = 0x001F
        const val REG_VMAX_HI = 0x0020
        const val REG_HMAX_LO = 0x0023
        const val REG_HMAX_HI = 0x0024
        const val REG_HCG = 0x002D
        const val REG_PGC_12_LO = 0x002E
        const val REG_PGC_12_HI = 0x002F
        const val REG_PGC2_LO = 0x0030
        const val REG_PGC2_HI = 0x0031
        const val REG_TEMP_LO = 0x0040
        const val REG_TEMP_HI = 0x0041
        const val REG_SHR_0 = 0x0047
        const val REG_SHR_1 = 0x0048
        const val REG_SHR_2 = 0x0049
        const val REG_READ_GATE = 0x004F
        const val REG_SLEEP_GATE = 0x019E

        const val REGHOLD_BIT = 0x40
        const val READ_GATE_BIT = 0x08
        const val SLEEP_AWAKE_BIT = 0x04

        const val DEFAULT_STBY = 0x04
        const val DEFAULT_MDSEL = 0xB1 // Boot default template T[23]
        const val DEFAULT_SENSOR_TEMP_RAW = 0x00FA // 25.0 C * 10

        private val GROUP_HOLD_REGISTERS = setOf(
            REG_PGC_LO,
            REG_PGC_HI,
            REG_SHR_15_LO,
            REG_SHR_15_HI,
            REG_DGAIN,
            REG_VMAX_LO,
            REG_VMAX_HI,
            REG_HMAX_LO,
            REG_HMAX_HI,
            REG_HCG,
            REG_PGC_12_LO,
            REG_PGC_12_HI,
            REG_PGC2_LO,
            REG_PGC2_HI,
            REG_SHR_0,
            REG_SHR_1,
            REG_SHR_2,
        )

        private val SNAPSHOT_KEYS = setOf(
            "profile",
            "registers",
            "staged",
            "writeOps",
            "readOps",
            "gatedBlockedReads",
        )

        private fun reqLong(raw: Any?, field: String, min: Long, max: Long): Long {
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "$field requires integer" }
            val n = (raw as Number).toLong()
            require(n in min..max) { "$field out of range: $n" }
            return n
        }

        private fun decodeRegMap(raw: Any?, field: String): LinkedHashMap<Int, Int> {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("$field requires map")
            val out = LinkedHashMap<Int, Int>(map.size)
            for ((k, v) in map) {
                val sel = (k as? String)?.toIntOrNull()
                    ?: throw IllegalArgumentException("$field invalid selector key: $k")
                require(sel in 0..0xFFFF) { "$field selector out of range: $sel" }
                out[sel] = reqLong(v, "$field[$k]", 0L, 0xFFL).toInt()
            }
            return out
        }
    }
}
