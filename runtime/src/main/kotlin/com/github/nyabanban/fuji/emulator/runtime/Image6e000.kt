package com.github.nyabanban.fuji.emulator.runtime

internal object Image6e000 {
    const val REGION = "diagnostic.image6e000.021f3440"
    const val REPEATABLE_PROFILE = "config-pairs-repeatable-v1"
    const val BASE = 0xfff6e000L
    const val SIZE = 0x70
    val repeatableOffsets: Set<Int> = setOf(0x000, 0x008, 0x014, 0x018, 0x020, 0x024, 0x068, 0x06c)
    private data class Step(val offset: Int, val lr: Long, val word: Long?, val write: Boolean = true)
    private val config = listOf(
        Step(0x14, 0x11e3b48, 0x002c0f30),
        Step(0x18, 0x11e3b68, 0x001003ee),
        Step(0x20, 0x11e3c54, 0x00000f50),
        Step(0x24, 0x11e3c74, 0x001003da),
    )
    private val configAndStop = config + listOf(
        Step(0x68, 0x11e33cc, 0),
        Step(0x6c, 0x11e3378, 1),
        Step(0, 0x11e33f0, 0, write = false),
        Step(0, 0x11e33fc, 0),
        Step(0, 0x11e3354, 1, write = false),
    )
    private const val CONTROL_008_SEED = 0L
    private val configAndDisable = config + listOf(
        Step(8, 0x11e37dc, CONTROL_008_SEED, write = false),
        Step(8, 0x11e3828, CONTROL_008_SEED and 0x80L.inv()),
    )
    private val secondConfig = configAndDisable + config.map { it.copy(word = null) }

    private fun steps(strategy: DiscoveryStrategy) = when (strategy) {
        DiscoveryStrategy.IMAGE_6E_CONFIG -> config
        DiscoveryStrategy.IMAGE_6E_STOP -> configAndStop
        DiscoveryStrategy.IMAGE_6E_DISABLE -> configAndDisable
        DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG -> secondConfig
        else -> error("Unsupported image6e000 strategy")
    }

    fun limit(strategy: DiscoveryStrategy) = steps(strategy).size

    internal fun migrationSteps(strategy: DiscoveryStrategy): List<Pair<Int, Long?>> =
        steps(strategy).map { it.offset to (if (it.write) it.word else null) }

    fun region(selection: Any): DiscoveryRegion {
        val strategy = listOf(DiscoveryStrategy.IMAGE_6E_CONFIG, DiscoveryStrategy.IMAGE_6E_STOP,
            DiscoveryStrategy.IMAGE_6E_DISABLE, DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG,
            DiscoveryStrategy.IMAGE_6E_REPEATABLE).single { it.id == selection }
        val offsets = if (strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE) repeatableOffsets
            else steps(strategy).map { it.offset }.toSet()
        val holes = buildList {
            var start = 0
            for (offset in offsets.sorted()) {
                if (start < offset) add(AddressInterval(BASE + start, BASE + offset))
                start = offset + 4
            }
            if (start < SIZE) add(AddressInterval(BASE + start, BASE + SIZE))
        }
        return DiscoveryRegion(REGION, "section5-021f332c", BASE, SIZE, strategy, setOf(4), 4, true,
            prohibited = holes, evidence = listOf(
                "on-7019c tick 832805429 core1, saved mask 001C7FEC, arguments at 0328ACD0 and 0328ACD8",
                "021F3440 and 021F3484 call 011E3AD4 and 011E3BE0. HYPOTHESIS accepts the four packed words once") +
                when (strategy) {
                    DiscoveryStrategy.IMAGE_6E_DISABLE, DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG,
                    DiscoveryStrategy.IMAGE_6E_REPEATABLE -> listOf(
                        "on-6c000-stop tick 833364096, T79 1548: 0235E364 -> 0237E48C -> 021F2A20, 021F12E8 calls 011E37B0 with mode 0",
                        "HYPOTHESIS: the four configuration writes leave 008 unseeded; use zero once, preserve every bit except bit 7 on writeback")
                    DiscoveryStrategy.IMAGE_6E_STOP -> listOf(
                        "011E3F00..011E3F10 disables, acknowledges, clears bit 0 and polls. HYPOTHESIS reads 0 then 1",
                        "011E3E54..011E3E68 registers handler 011E337C through selector 83, row 015A8EDC word0=105, callback slot 01C65B3C")
                    else -> emptyList()
                } + (if (strategy == DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG || strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE) listOf(
                    "on-t79-setter-near/pre-read cursor 6, arguments at 0328ACD0/D8; DAT 011E3AEC..3B64 and 011E3BF8..3C70",
                    "HYPOTHESIS: accept a second ordered pair of writes from each setter within its checked parameter ranges")
                else emptyList()) + if (strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE) listOf(
                    "T79/T80 core1 repeat passes over known Image6e000 offsets {000,008,014,018,020,024,068,06c}",
                    "HYPOTHESIS: repeatable stateful 32-bit bank preserving prohibited holes; 014/020 enforce hi<=0x3fff & lo in 1..0x1000, 018/024 enforce hi<=0x3fff & lo in 1..0x4000, 000 reads value|1")
                else emptyList())
    }

    internal fun validConfigWord(offset: Int, value: Long): Boolean {
        val maxLow = if (offset == 0x14 || offset == 0x20) 0x1000L else 0x4000L
        return value in 0L..0xffffffffL && value ushr 16 <= 0x3fffL && (value and 0xffffL) in 1L..maxLow
    }

    fun access(strategy: DiscoveryStrategy, position: Int, offset: Int, write: Boolean, value: Long, context: DiscoveryContext): Long? {
        val step = steps(strategy).getOrNull(position) ?: return null
        val pc = if (write) 0x5af98L else 0x5afe0L
        if (offset != step.offset || write != step.write || context.core() != 1 || context.pc() != pc ||
            context.lr() != step.lr || (offset == 8 && !context.image6eDisableMode())) return null
        step.word?.let { return it.takeIf { !write || value == it } }
        return value.takeIf { validConfigWord(offset, it) }
    }
}

internal class Image6e000Bank private constructor(
    private val words: MutableMap<Int, Long>,
) {
    constructor() : this(Image6e000.repeatableOffsets.associateWith { 0L }.toMutableMap())

    companion object {
        private val configOffsets = setOf(0x014, 0x018, 0x020, 0x024)

        fun restore(raw: Map<*, *>): Image6e000Bank {
            require(raw.keys == setOf("profile", "words") && raw["profile"] == Image6e000.REPEATABLE_PROFILE) {
                "Invalid Image6e000 repeatable bank snapshot header"
            }
            val rawWords = raw["words"] as? Map<*, *> ?: error("Missing Image6e000 repeatable bank words")
            val bank = Image6e000.repeatableOffsets.associateWith { 0L }.toMutableMap()
            for ((rawKey, rawValue) in rawWords) {
                require(rawKey is String && rawKey.matches(Regex("[0-9a-f]+"))) {
                    "Invalid Image6e000 bank offset key: $rawKey"
                }
                val offset = rawKey.toIntOrNull(16) ?: error("Invalid hex offset: $rawKey")
                require(offset.toString(16) == rawKey && offset in Image6e000.repeatableOffsets) {
                    "Unsupported or non-canonical Image6e000 bank offset: $rawKey"
                }
                require(rawValue is Int || rawValue is Long) {
                    "Image6e000 bank word at $rawKey requires an integer"
                }
                val word = (rawValue as Number).toLong()
                require(word in 0L..0xffffffffL) {
                    "Image6e000 bank word out of u32 range at $rawKey: $word"
                }
                if (offset in configOffsets && word != 0L) {
                    require(Image6e000.validConfigWord(offset, word)) {
                        "Image6e000 config word out of DAT bounds at $rawKey: $word"
                    }
                }
                bank[offset] = word
            }
            return Image6e000Bank(bank)
        }
    }

    fun access(offset: Int, width: Int, write: Boolean, value: Long, context: DiscoveryContext): Long? {
        if (width != 4 || offset % 4 != 0 || offset !in Image6e000.repeatableOffsets) return null
        if (context.core() != 1 || context.pc() != (if (write) 0x5af98L else 0x5afe0L)) return null
        if (write && (value < 0L || value > 0xffffffffL)) return null
        return when (offset) {
            0x014, 0x018, 0x020, 0x024 -> if (write) {
                if (!Image6e000.validConfigWord(offset, value)) null
                else {
                    words[offset] = value
                    value
                }
            } else {
                words.getValue(offset)
            }
            0x000 -> if (write) {
                words[0x000] = value
                value
            } else {
                words.getValue(0x000) or 1L
            }
            else -> if (write) {
                words[offset] = value
                value
            } else {
                words.getValue(offset)
            }
        }
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "profile" to Image6e000.REPEATABLE_PROFILE,
        "words" to Image6e000.repeatableOffsets.sorted().associate { it.toString(16) to words.getValue(it) },
    )

    fun receipt(): Map<String, Any> = mapOf(
        "profile" to Image6e000.REPEATABLE_PROFILE,
        "confidence" to "HYPOTHESIS",
        "words" to Image6e000.repeatableOffsets.sorted().associate { it.toString(16) to words.getValue(it) },
    )
}
