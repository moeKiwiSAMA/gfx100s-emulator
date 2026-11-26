package com.github.nyabanban.fuji.emulator.components.max17048

import com.github.nyabanban.fuji.emulator.api.BatteryPort
import com.github.nyabanban.fuji.emulator.api.I2cSlaveDevice
import kotlin.math.roundToInt

/**
 * Pure datasheet-faithful model of the **Maxim Integrated (ADI) `MAX17048` / `MAX17049`**
 * ModelGauge Li-Ion Battery Fuel Gauge (`I2C1 @ 0x36`, 16-bit big-endian registers).
 *
 * Implements the full MAX17048/49 register map & GFX100S 2.13 `GXUP0004.DAT` sequence
 * (`section4 @ 0x01215780..0x01215E94`):
 * - `0x02 VCELL` (RO, 16b BE): `156.25 µV/LSB` (`MAX17049` 2S NP-W235) or `78.125 µV/LSB` (`MAX17048` 1S).
 * - `0x04 SOC` (RO, 16b BE): `1/256 %/LSB` (high byte = integer `%`, low byte = fractional `1/256 %`).
 * - `0x06 MODE`, `0x08 VERSION` (`0x0012`), `0x0A HIBRT` (`0x8030`), `0x0C CONFIG` (`0x971C`).
 * - `0x0E OCV` (accessible when unlocked via `0x3E = 0x4A57`).
 * - `0x14 VALRT` (`0x00FF`), `0x16 CRATE`, `0x18 VRESET/ID` (`0x9602`), `0x1A STATUS` (`0x0100`).
 * - `0x3E..0x3F` Model Access Lock/Unlock (`0x4A57` `"JW"` unlocks `0x40..0x7F` 64B model RAM,
 *   `0x80..0x9F` 32B OCV/config RAM, and `0x0E OCV`; `0x0000` locks).
 * - `0xFE CMD` (`0x5400` triggers Power-On Reset).
 */
internal class Max17048Device(
    override val address7: Int = DEFAULT_ADDRESS7,
    private val cells: Int = DEFAULT_CELLS,
    private val versionId: Int = DEFAULT_VERSION_ID,
    private val batteryProvider: () -> BatteryPort? = { null },
) : I2cSlaveDevice {
    init {
        require(address7 in 0x08..0x77) { "MAX17048 7-bit I2C address out of range: 0x${address7.toString(16)}" }
        require(cells in 1..2) { "MAX17048/49 supports 1 (MAX17048) or 2 (MAX17049) cells, got $cells" }
    }

    private var modeReg: Int = 0x0000
    private var hibrtReg: Int = DEFAULT_HIBRT
    private var configReg: Int = DEFAULT_CONFIG
    private var ocvOverride: Int = -1
    private var valrtReg: Int = DEFAULT_VALRT
    private var vresetReg: Int = DEFAULT_VRESET_HI
    private var statusReg: Int = DEFAULT_STATUS
    private var lockWord: Int = 0x0000
    private val modelRam = IntArray(MODEL_RAM_SIZE) // 0x40..0x9F (64B model + 32B OCV/cfg)

    private var selected: Boolean = false
    private var expectPointer: Boolean = false
    private var pointer: Int = 0
    private var writeByteIndex: Int = 0
    private var pendingHiByte: Int = 0
    private var readWordCache: Int = 0
    private var readWordAddr: Int = -1
    private var readCount: Long = 0L
    private var writeCount: Long = 0L
    private var porCount: Long = 0L
    private var modelUnlockCount: Long = 0L

    val modelUnlocked: Boolean get() = lockWord == UNLOCK_MAGIC

    private fun resetRegisters() {
        modeReg = 0x0000
        hibrtReg = DEFAULT_HIBRT
        configReg = DEFAULT_CONFIG
        ocvOverride = -1
        valrtReg = DEFAULT_VALRT
        vresetReg = DEFAULT_VRESET_HI
        statusReg = DEFAULT_STATUS
        lockWord = 0x0000
        modelRam.fill(0)
        readWordAddr = -1
    }

    fun currentVcellWord(): Int {
        val batt = batteryProvider()
        val mv = if (batt != null && batt.present()) batt.voltageMillivolts() else if (cells == 2) 7600 else 3800
        // MAX17048 (1S): 78.125 µV/LSB = 12.8 LSB/mV; MAX17049 (2S): 156.25 µV/LSB = 6.4 LSB/mV
        val lsbPerMv = if (cells == 2) 6.4 else 12.8
        return (mv * lsbPerMv).roundToInt().coerceIn(0, 0xFFFF)
    }

    fun currentSocWord(): Int {
        val batt = batteryProvider()
        val pct = if (batt != null && batt.present()) batt.chargePercent() else 80.0
        return (pct * 256.0).roundToInt().coerceIn(0, 0xFFFF)
    }

    private fun readWord(evenAddr: Int): Int = when (evenAddr) {
        REG_VCELL -> currentVcellWord()
        REG_SOC -> currentSocWord()
        REG_MODE -> modeReg and 0xFFFF
        REG_VERSION -> versionId and 0xFFFF
        REG_HIBRT -> hibrtReg and 0xFFFF
        REG_CONFIG -> configReg and 0xFFFF
        REG_OCV -> if (modelUnlocked) (if (ocvOverride >= 0) ocvOverride else currentVcellWord()) else 0xFFFF
        REG_VALRT -> valrtReg and 0xFFFF
        REG_CRATE -> 0x0000
        REG_VRESET_ID -> ((vresetReg and 0xFF) shl 8) or CHIP_ID_BYTE
        REG_STATUS -> statusReg and 0xFFFF
        REG_LOCK -> lockWord and 0xFFFF
        in MODEL_RAM_START until (MODEL_RAM_START + MODEL_RAM_SIZE) -> {
            if (!modelUnlocked) {
                0xFFFF
            } else {
                val off = evenAddr - MODEL_RAM_START
                ((modelRam[off] and 0xFF) shl 8) or (modelRam[(off + 1).coerceAtMost(MODEL_RAM_SIZE - 1)] and 0xFF)
            }
        }
        else -> 0x0000
    }

    private fun writeWord(evenAddr: Int, value16: Int) {
        val v = value16 and 0xFFFF
        when (evenAddr) {
            REG_MODE -> modeReg = v
            REG_HIBRT -> hibrtReg = v
            REG_CONFIG -> configReg = v
            REG_OCV -> if (modelUnlocked) ocvOverride = v
            REG_VALRT -> valrtReg = v
            REG_VRESET_ID -> vresetReg = (v ushr 8) and 0xFF
            REG_STATUS -> statusReg = statusReg and v
            REG_LOCK -> {
                val wasUnlocked = modelUnlocked
                lockWord = v
                if (!wasUnlocked && modelUnlocked) modelUnlockCount++
            }
            REG_CMD -> {
                if (v == CMD_POR) {
                    resetRegisters()
                    porCount++
                }
            }
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
        expectPointer = !isRead
        writeByteIndex = 0
        readWordAddr = -1
        return true
    }

    override fun checkWrite(byte: Int) {
        require(byte in 0..255)
        require(selected) { "MAX17048 write outside selected address phase" }
    }

    override fun write(byte: Int) {
        checkWrite(byte)
        if (expectPointer) {
            pointer = byte and 0xFF
            expectPointer = false
            writeByteIndex = 0
            return
        }
        writeCount++
        val target = (pointer + writeByteIndex) and 0xFF
        if (target in MODEL_RAM_START until (MODEL_RAM_START + MODEL_RAM_SIZE)) {
            if (modelUnlocked) {
                modelRam[target - MODEL_RAM_START] = byte and 0xFF
            }
            writeByteIndex++
            return
        }
        if ((writeByteIndex and 1) == 0) {
            pendingHiByte = byte and 0xFF
        } else {
            val wordAddr = (target - 1) and 0xFE
            writeWord(wordAddr, (pendingHiByte shl 8) or (byte and 0xFF))
        }
        writeByteIndex++
    }

    override fun peekRead(): Int {
        require(selected) { "MAX17048 read outside selected address phase" }
        val p = pointer and 0xFF
        if (p in MODEL_RAM_START until (MODEL_RAM_START + MODEL_RAM_SIZE)) {
            return if (modelUnlocked) modelRam[p - MODEL_RAM_START] and 0xFF else 0xFF
        }
        val even = p and 0xFE
        if (readWordAddr != even) {
            readWordCache = readWord(even)
            readWordAddr = even
        }
        return if ((p and 1) == 0) (readWordCache ushr 8) and 0xFF else readWordCache and 0xFF
    }

    override fun consumeRead(expected: Int) {
        require(peekRead() == expected) { "MAX17048 read byte changed before completion" }
        readCount++
        pointer = (pointer + 1) and 0xFF
        if ((pointer and 1) == 0) {
            readWordAddr = -1
        }
    }

    override fun stop() {
        selected = false
        expectPointer = false
        writeByteIndex = 0
        readWordAddr = -1
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "chip" to if (cells == 2) "Maxim MAX17049" else "Maxim MAX17048",
        "address7" to address7,
        "cells" to cells,
        "version" to versionId,
        "vcellWord" to currentVcellWord(),
        "socWord" to currentSocWord(),
        "config" to configReg,
        "hibrt" to hibrtReg,
        "status" to statusReg,
        "modelUnlocked" to modelUnlocked,
        "modelUnlockCount" to modelUnlockCount,
        "porCount" to porCount,
        "readCount" to readCount,
        "writeCount" to writeCount,
    )

    internal class ParsedSnapshot(
        val modeReg: Int,
        val hibrtReg: Int,
        val configReg: Int,
        val ocvOverride: Int,
        val valrtReg: Int,
        val vresetReg: Int,
        val statusReg: Int,
        val lockWord: Int,
        val modelRam: IntArray,
        val selected: Boolean,
        val expectPointer: Boolean,
        val pointer: Int,
        val writeByteIndex: Int,
        val pendingHiByte: Int,
        val readCount: Long,
        val writeCount: Long,
        val porCount: Long,
        val modelUnlockCount: Long,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "address7" to address7,
        "modeReg" to modeReg,
        "hibrtReg" to hibrtReg,
        "configReg" to configReg,
        "ocvOverride" to ocvOverride,
        "valrtReg" to valrtReg,
        "vresetReg" to vresetReg,
        "statusReg" to statusReg,
        "lockWord" to lockWord,
        "modelRam" to modelRam.toList(),
        "selected" to selected,
        "expectPointer" to expectPointer,
        "pointer" to pointer,
        "writeByteIndex" to writeByteIndex,
        "pendingHiByte" to pendingHiByte,
        "readCount" to readCount,
        "writeCount" to writeCount,
        "porCount" to porCount,
        "modelUnlockCount" to modelUnlockCount,
    )

    fun parseSnapshot(raw: Any?): ParsedSnapshot {
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid MAX17048 snapshot")
        require(map.keys == SNAPSHOT_KEYS) { "Unexpected MAX17048 snapshot keys" }
        require(snapInt(map["address7"], "max17048.address7", 0x08..0x77) == address7) { "MAX17048 address mismatch" }
        val rawRam = map["modelRam"] as? List<*> ?: throw IllegalArgumentException("Missing max17048.modelRam")
        require(rawRam.size == MODEL_RAM_SIZE) { "Unexpected max17048.modelRam size" }
        return ParsedSnapshot(
            modeReg = snapInt(map["modeReg"], "max17048.modeReg", 0..0xFFFF),
            hibrtReg = snapInt(map["hibrtReg"], "max17048.hibrtReg", 0..0xFFFF),
            configReg = snapInt(map["configReg"], "max17048.configReg", 0..0xFFFF),
            ocvOverride = snapInt(map["ocvOverride"], "max17048.ocvOverride", -1..0xFFFF),
            valrtReg = snapInt(map["valrtReg"], "max17048.valrtReg", 0..0xFFFF),
            vresetReg = snapInt(map["vresetReg"], "max17048.vresetReg", 0..0xFF),
            statusReg = snapInt(map["statusReg"], "max17048.statusReg", 0..0xFFFF),
            lockWord = snapInt(map["lockWord"], "max17048.lockWord", 0..0xFFFF),
            modelRam = IntArray(MODEL_RAM_SIZE) { i -> snapInt(rawRam[i], "max17048.modelRam[$i]", 0..0xFF) },
            selected = map["selected"] as? Boolean ?: throw IllegalArgumentException("Invalid max17048.selected"),
            expectPointer = map["expectPointer"] as? Boolean ?: throw IllegalArgumentException("Invalid max17048.expectPointer"),
            pointer = snapInt(map["pointer"], "max17048.pointer", 0..0xFF),
            writeByteIndex = snapInt(map["writeByteIndex"], "max17048.writeByteIndex", 0..1024),
            pendingHiByte = snapInt(map["pendingHiByte"], "max17048.pendingHiByte", 0..0xFF),
            readCount = snapLong(map["readCount"], "max17048.readCount"),
            writeCount = snapLong(map["writeCount"], "max17048.writeCount"),
            porCount = snapLong(map["porCount"], "max17048.porCount"),
            modelUnlockCount = snapLong(map["modelUnlockCount"], "max17048.modelUnlockCount"),
        )
    }

    fun applySnapshot(parsed: ParsedSnapshot) {
        modeReg = parsed.modeReg
        hibrtReg = parsed.hibrtReg
        configReg = parsed.configReg
        ocvOverride = parsed.ocvOverride
        valrtReg = parsed.valrtReg
        vresetReg = parsed.vresetReg
        statusReg = parsed.statusReg
        lockWord = parsed.lockWord
        parsed.modelRam.copyInto(modelRam)
        selected = parsed.selected
        expectPointer = parsed.expectPointer
        pointer = parsed.pointer
        writeByteIndex = parsed.writeByteIndex
        pendingHiByte = parsed.pendingHiByte
        readWordAddr = -1
        readCount = parsed.readCount
        writeCount = parsed.writeCount
        porCount = parsed.porCount
        modelUnlockCount = parsed.modelUnlockCount
    }

    companion object {
        const val DEFAULT_ADDRESS7 = 0x36
        const val DEFAULT_CELLS = 2
        const val DEFAULT_VERSION_ID = 0x0012
        const val UNLOCK_MAGIC = 0x4A57 // "JW"
        const val CMD_POR = 0x5400

        const val REG_VCELL = 0x02
        const val REG_SOC = 0x04
        const val REG_MODE = 0x06
        const val REG_VERSION = 0x08
        const val REG_HIBRT = 0x0A
        const val REG_CONFIG = 0x0C
        const val REG_OCV = 0x0E
        const val REG_VALRT = 0x14
        const val REG_CRATE = 0x16
        const val REG_VRESET_ID = 0x18
        const val REG_STATUS = 0x1A
        const val REG_LOCK = 0x3E
        const val MODEL_RAM_START = 0x40
        const val MODEL_RAM_SIZE = 96 // 0x40..0x7F (64B) + 0x80..0x9F (32B)
        const val REG_CMD = 0xFE

        private const val DEFAULT_HIBRT = 0x8030
        private const val DEFAULT_CONFIG = 0x971C
        private const val DEFAULT_VALRT = 0x00FF
        private const val DEFAULT_VRESET_HI = 0x96
        private const val CHIP_ID_BYTE = 0x02
        private const val DEFAULT_STATUS = 0x0100

        private val SNAPSHOT_KEYS = setOf(
            "address7", "modeReg", "hibrtReg", "configReg", "ocvOverride",
            "valrtReg", "vresetReg", "statusReg", "lockWord", "modelRam",
            "selected", "expectPointer", "pointer", "writeByteIndex", "pendingHiByte",
            "readCount", "writeCount", "porCount", "modelUnlockCount",
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
