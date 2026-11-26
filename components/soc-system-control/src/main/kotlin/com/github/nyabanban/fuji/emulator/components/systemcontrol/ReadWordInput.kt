package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.MmioDevice

/**
 * Frozen read-only `0xFFF48004` AVS silicon speed-bin fuse word (`DAT` `section4.bin 0x0120E0D4`).
 *
 * Firmware masks `0xFFF48004 & 0x1F` to obtain a 5-bit silicon speed bin (`0..31`) that indexes
 * two 32-byte PMIC voltage LUTs (`0x0181258C` -> I2C ch 12 reg `0x00`; `0x0181256C` -> I2C ch 13
 * reg `0x26`). Bins `0..7` fall back to the maximum-voltage safe corner (`50` / `140`).
 */
internal class ReadWordInput private constructor(private val value: Long) : MmioDevice {
    override val clockOrder = 70
    override fun read(offset: Long, size: Int): Long {
        require(offset == 0L && size == 4) { "FFF48004 input requires its exact aligned read-only word" }
        return value
    }
    override fun write(offset: Long, size: Int, value: Long): Unit = error("FFF48004 input is read-only")
    fun state(): Map<String, Any> = linkedMapOf(
        "profile" to PROFILE,
        "value" to value,
        "avs" to decodeAvs(value).toStateMap(),
        "basis" to "DAT section4 0x0120E0D4 decodes 0xFFF48004[4:0] as 5-bit AVS silicon speed bin indexing PMIC LUTs 0x0181258C/0x0181256C; supplied u32 word is explicit HYPOTHESIS with no read side effects",
    )
    fun saveSnapshot(): Map<String, Any> = linkedMapOf("profile" to PROFILE, "value" to value)
    fun validateSnapshot(raw: Any?) {
        val section = raw as? Map<*, *> ?: throw IllegalArgumentException("Snapshot readWordFff48004 requires map")
        require(section.keys == SNAPSHOT_KEYS) { "Unexpected readWordFff48004 snapshot keys" }
        require(section["profile"] == PROFILE) { "Unsupported readWordFff48004 snapshot profile" }
        val rawWord = section["value"]
        require(rawWord is Byte || rawWord is Short || rawWord is Int || rawWord is Long) {
            "Snapshot readWordFff48004 value requires integer"
        }
        val converted = (rawWord as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot readWordFff48004 value requires integer")
        require(converted == value) { "Snapshot readWordFff48004 value differs from live configuration" }
    }

    internal data class AvsDecode(
        val rawWord: Long,
        val speedBin: Int,
        val rail0LutCode: Int,
        val rail2LutCode: Int,
        val corner: String,
        val usesSafeFallback: Boolean,
    ) {
        fun toStateMap(): Map<String, Any> = linkedMapOf(
            "speedBin" to speedBin,
            "binMask" to AVS_BIN_MASK,
            "rail0LutCode" to rail0LutCode,
            "rail0PmicTarget" to "i2c-ch12:reg0x00",
            "rail0LutAddress" to AVS_RAIL0_LUT_ADDR,
            "rail2LutCode" to rail2LutCode,
            "rail2PmicTarget" to "i2c-ch13:reg0x26",
            "rail2LutAddress" to AVS_RAIL2_LUT_ADDR,
            "corner" to corner,
            "usesSafeFallback" to usesSafeFallback,
            "nominalSiliconBin" to NOMINAL_SILICON_BIN,
            "confidence" to "DAT",
        )
    }

    companion object {
        const val PROFILE = "read-word-fff48004-experimental-v1"
        const val PORT = "soc-system-control.read-word-fff48004"
        const val AVS_BIN_MASK = 0x1FL
        const val NOMINAL_SILICON_BIN = 16L
        const val AVS_RAIL0_LUT_ADDR = 0x0181_258CL
        const val AVS_RAIL2_LUT_ADDR = 0x0181_256CL

        /** `section4.bin @ 0x0181258C` (`DAT`, 32 bytes): PMIC rail 0 (I2C ch 12 reg `0x00`) AVS codes. */
        val AVS_RAIL0_LUT: IntArray = intArrayOf(
            50, 50, 50, 50, 50, 50, 50, 50,
            33, 34, 34, 35, 36, 37, 38, 38,
            39, 40, 41, 42, 42, 43, 44, 45,
            45, 47, 47, 48, 49, 50, 50, 50,
        )

        /** `section4.bin @ 0x0181256C` (`DAT`, 32 bytes): PMIC rail 2 (I2C ch 13 reg `0x26`) AVS codes. */
        val AVS_RAIL2_LUT: IntArray = intArrayOf(
            140, 140, 140, 140, 140, 140, 140, 140,
            128, 129, 130, 130, 131, 131, 132, 132,
            133, 133, 134, 134, 135, 135, 136, 136,
            137, 137, 138, 138, 139, 139, 140, 140,
        )

        private val SNAPSHOT_KEYS = setOf("profile", "value")

        fun decodeAvs(word: Long): AvsDecode {
            require(word in 0L..0xffffffffL) { "FFF48004 AVS word exceeds u32: $word" }
            val bin = (word and AVS_BIN_MASK).toInt()
            val corner = when (bin) {
                in 0..7 -> "unbinned-safe-max"
                in 8..13 -> "fast"
                in 14..21 -> "nominal"
                in 22..28 -> "slow"
                else -> "slow-max"
            }
            return AvsDecode(
                rawWord = word,
                speedBin = bin,
                rail0LutCode = AVS_RAIL0_LUT[bin],
                rail2LutCode = AVS_RAIL2_LUT[bin],
                corner = corner,
                usesSafeFallback = bin < 8,
            )
        }

        fun load(context: ComponentContext, file: String): ReadWordInput? {
            if (file.isEmpty()) return null
            require(file.isNotBlank()) { "FFF48004 input file cannot be whitespace" }
            val data = context.readData(file)
            require(data.keys == setOf("schema", "profile", "value") && data["schema"] == 1 && data["profile"] == PROFILE) { "invalid FFF48004 input schema" }
            val raw = data["value"]
            require(raw is Byte || raw is Short || raw is Int || raw is Long) { "FFF48004 input requires an unsigned 32-bit integer" }
            val value = (raw as Number).toLong()
            require(value in 0..0xffffffffL) { "FFF48004 input exceeds u32" }
            return ReadWordInput(value)
        }
    }
}
