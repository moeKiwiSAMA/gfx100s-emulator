package com.github.nyabanban.fuji.emulator.runtime

internal class T79StatefulIspBank private constructor(
    private val words: MutableMap<Int, Long>,
) {
    companion object {
        const val PROFILE = "t79-stateful-isp-bank-v1"
        private const val READY_STATUS = 0x000
        private const val IRQ_STATUS = 0x00c
        private const val CONTROL = 0x2e4
        private const val POLL_STATUS = 0x2e8
        private const val POLL_READY_WORD = 0x10000L
        private const val IRQ_ACK_LR = 0x11b7f0cL

        val allowedOffsets: Set<Int> get() = Image70000.statefulAllowedOffsets

        fun seed(
            setter: T79ImageConfigSetter,
            secondUpdate: T79ImageSecondUpdate,
            retainedControl: Long,
        ): T79StatefulIspBank {
            require(setter.complete && secondUpdate.complete) {
                "Stateful ISP bank seeding requires completed setter and second-update state"
            }
            require(retainedControl and 0xffff0000L == retainedControl) {
                "Retained control high half must have zero low half"
            }
            val bank = HashMap<Int, Long>()
            for (offset in allowedOffsets) {
                bank[offset] = 0L
            }
            for ((offset, value) in setter.retainedWords()) {
                bank[offset] = value
            }
            for (offset in T79ImageSecondUpdate.offsets) {
                bank[offset] = checkNotNull(secondUpdate.retainedWord(offset))
            }
            bank[READY_STATUS] = 1L
            bank[0x004] = 0x03ff03ffL
            bank[0x008] = 0x03ff03dfL
            bank[IRQ_STATUS] = 0L
            bank[0x01c] = 0x66480L
            bank[CONTROL] = 0x120L or retainedControl
            bank[POLL_STATUS] = POLL_READY_WORD
            return T79StatefulIspBank(bank)
        }

        fun restore(raw: Map<*, *>): T79StatefulIspBank {
            require(raw.keys == setOf("profile", "words") && raw["profile"] == PROFILE) {
                "Invalid T79 stateful ISP bank snapshot header"
            }
            val rawWords = raw["words"] as? Map<*, *> ?: error("Missing T79 stateful ISP bank words")
            val bank = HashMap<Int, Long>()
            for (offset in allowedOffsets) {
                bank[offset] = 0L
            }
            bank[POLL_STATUS] = POLL_READY_WORD
            for ((rawKey, rawValue) in rawWords) {
                require(rawKey is String && rawKey.matches(Regex("[0-9a-f]+"))) {
                    "Invalid T79 stateful ISP bank offset key: $rawKey"
                }
                val offset = rawKey.toIntOrNull(16) ?: error("Invalid hex offset: $rawKey")
                require(offset.toString(16) == rawKey && offset in allowedOffsets) {
                    "Unsupported or non-canonical T79 stateful ISP bank offset: $rawKey"
                }
                require(rawValue is Int || rawValue is Long) {
                    "T79 stateful ISP bank word at $rawKey requires an integer"
                }
                val word = (rawValue as Number).toLong()
                require(word in 0L..0xffffffffL) {
                    "T79 stateful ISP bank word out of u32 range at $rawKey: $word"
                }
                if (offset == POLL_STATUS) {
                    require(word == POLL_READY_WORD) { "Read-only 0x2e8 must remain 0x10000" }
                }
                if (offset == IRQ_STATUS) {
                    require(word == 0L) { "Quiet IRQ status 0x00c must remain 0" }
                }
                bank[offset] = word
            }
            return T79StatefulIspBank(bank)
        }
    }

    fun word(offset: Int): Long {
        require(offset in allowedOffsets) { "Offset 0x${offset.toString(16)} is not in allowed stateful ISP offsets" }
        return when (offset) {
            READY_STATUS -> words.getOrDefault(READY_STATUS, 1L) or 1L
            POLL_STATUS -> POLL_READY_WORD
            IRQ_STATUS -> 0L
            else -> words.getOrDefault(offset, 0L)
        }
    }

    fun controlHighHalf(): Long = words.getOrDefault(CONTROL, 0L) and 0xffff0000L

    fun access(offset: Int, width: Int, write: Boolean, value: Long, context: DiscoveryContext): Long? {
        if (width != 4 || offset % 4 != 0 || offset !in allowedOffsets) return null
        if (context.core() != 1 || context.pc() != (if (write) 0x5af98L else 0x5afe0L)) return null
        if (write && (value < 0L || value > 0xffffffffL)) return null
        return when (offset) {
            READY_STATUS -> if (write) {
                words[READY_STATUS] = value
                value
            } else {
                words.getOrDefault(READY_STATUS, 1L) or 1L
            }
            POLL_STATUS -> if (write) null else POLL_READY_WORD
            IRQ_STATUS -> if (write) {
                if (context.lr() != IRQ_ACK_LR || (value != 0L && value != 1L)) null else value
            } else {
                0L
            }
            else -> if (write) {
                words[offset] = value
                value
            } else {
                words.getOrDefault(offset, 0L)
            }
        }
    }

    fun snapshot(): Map<String, Any> = mapOf(
        "profile" to PROFILE,
        "words" to allowedOffsets.sorted().associate { it.toString(16) to words.getOrDefault(it, 0L) },
    )

    fun receipt(): Map<String, Any> = mapOf(
        "profile" to PROFILE,
        "confidence" to "HYPOTHESIS",
        "wordCount" to allowedOffsets.size,
        "words" to allowedOffsets.sorted().associate { it.toString(16) to words.getOrDefault(it, 0L) },
    )
}
