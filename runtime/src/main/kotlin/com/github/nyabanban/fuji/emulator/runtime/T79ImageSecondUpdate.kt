package com.github.nyabanban.fuji.emulator.runtime

internal class T79ImageSecondUpdate {
    internal data class Register(
        val offset: Int,
        val lr: Long,
        val mask: Long,
        val minHigh: Long = 0,
        val minMid: Long = 0,
        val minLow: Long = 0,
        val lowMask: Long = 0,
        val oddLow: Boolean = false,
    ) {
        init {
            require(minLow == 0L || lowMask != 0L)
        }

        fun accepts(value: Long): Boolean {
            if (value < 0 || value and mask.inv() != 0L) return false
            if (minHigh != 0L && (value ushr 16) < minHigh) return false
            if (minMid != 0L && ((value ushr 8) and 0xffL) < minMid) return false
            if (minLow != 0L && (value and lowMask) < minLow) return false
            if (oddLow && (value and 1L) == 0L) return false
            return true
        }
    }

    companion object {
        const val PROFILE = "t79-second-update-writes-v1"
        internal val registers = buildList {
            // 011ba294..011ba3b4: 134/138/13c
            add(Register(0x134, 0x11ba364, 0x0fff0fff))
            add(Register(0x138, 0x11ba394, 0x0fff0fff))
            add(Register(0x13c, 0x11ba3b4, 0x00ff00ff))
            // 011ba570..011ba628: 140/144 x10
            for (pair in 0 until 10) {
                add(Register(0x140 + pair * 8, 0x11ba604, 0x0fff0fff))
                add(Register(0x144 + pair * 8, 0x11ba628, 0x0fff0fff))
            }
            // 011b8c14..011b8d88: 05c/060/064
            add(Register(0x05c, 0x11b8d40, 0x0ffff1ff, minHigh = 8, minLow = 7, lowMask = 0x1ff))
            add(Register(0x060, 0x11b8d68, 0x0ffef1ff, minHigh = 2, minLow = 7, lowMask = 0x1ff, oddLow = true))
            add(Register(0x064, 0x11b8d88, 0x01ff01fe))
            // 011b8f84..011b90f8: 068/06c/070
            add(Register(0x068, 0x11b90b0, 0x0ffff1ff, minHigh = 2, minLow = 7, lowMask = 0x1ff))
            add(Register(0x06c, 0x11b90d8, 0x0ffef1ff, minHigh = 2, minLow = 7, lowMask = 0x1ff, oddLow = true))
            add(Register(0x070, 0x11b90f8, 0x01ff01fe))
            // 011bd6f0..011bd79c: 190/194
            add(Register(0x190, 0x11bd778, 0x0fff01ff))
            add(Register(0x194, 0x11bd79c, 0x0fff01ff))
            // 011b92f4..011b93e4: 074/078 x5
            for (pair in 0 until 5) {
                add(Register(0x074 + pair * 8, 0x11b93c8, 0x0fff0fff, minHigh = 8))
                add(Register(0x078 + pair * 8, 0x11b93e4, 0x0ffe0fff, minHigh = 2, oddLow = true))
            }
            // 011ba7ac..011ba8ec: 124/128/12c
            add(Register(0x124, 0x11ba8a0, 0x0ffff1ff, minHigh = 8))
            add(Register(0x128, 0x11ba8cc, 0x0ffff1ff, minHigh = 2))
            add(Register(0x12c, 0x11ba8ec, 0x01ff01ff))
            // 011bac8c..011bb0d8: 1b0/1ac, 1b8/1b4, 1c0/1bc
            add(Register(0x1b0, 0x11bad1c, 0x0fff0fff, minLow = 1, lowMask = 0xfff))
            add(Register(0x1ac, 0x11bad40, 0x3fff003f))
            add(Register(0x1b8, 0x11baee8, 0x0fff0fff, minLow = 1, lowMask = 0xfff))
            add(Register(0x1b4, 0x11baf0c, 0x3fff003f))
            add(Register(0x1c0, 0x11bb0b4, 0x0fff0fff, minLow = 1, lowMask = 0xfff))
            add(Register(0x1bc, 0x11bb0d8, 0x3fff003f))
            // 011bd8b4..011bdb80: 1c4/1c8, 1cc/1d0
            add(Register(0x1c4, 0x11bd958, 0x0fffffff, minMid = 1, minLow = 1, lowMask = 0xff))
            add(Register(0x1c8, 0x11bd97c, 0x3fff03ff))
            add(Register(0x1cc, 0x11bdb5c, 0x0fffffff, minMid = 1, minLow = 1, lowMask = 0xff))
            add(Register(0x1d0, 0x11bdb80, 0x3fff03ff))
            // 011bdcbc..011bdfb8: 1d4/1d8/1dc, 1e0/1e4/1e8
            add(Register(0x1d4, 0x11bdd54, 0x0fff00ff, minLow = 1, lowMask = 0xff))
            add(Register(0x1d8, 0x11bdd84, 0x3fff03ff))
            add(Register(0x1dc, 0x11bdd94, 0x000003ff))
            add(Register(0x1e0, 0x11bdf78, 0x0fff00ff, minLow = 1, lowMask = 0xff))
            add(Register(0x1e4, 0x11bdfa8, 0x3fff03ff))
            add(Register(0x1e8, 0x11bdfb8, 0x000003ff))
            // 011be104..011be1fc: 1ec/1f0/1f4
            add(Register(0x1ec, 0x11be1b4, 0x3fff0fff))
            add(Register(0x1f0, 0x11be1dc, 0x07ff01ff, minLow = 1, lowMask = 0x1ff))
            add(Register(0x1f4, 0x11be1fc, 0x07ff07ff, minLow = 1, lowMask = 0x7ff))
            // 011bc334..011bc404: 224/228
            add(Register(0x224, 0x11bc3e0, 0x0ffe0fff, oddLow = true))
            add(Register(0x228, 0x11bc404, 0x0fff0fff))
        }
        val offsets = registers.map { it.offset }
        val accessLimit = registers.size
        private val indexByOffset = registers.withIndex().associate { (index, reg) -> reg.offset to index }

        init {
            check(registers.size == 65 && offsets.distinct().size == registers.size)
        }

        fun restore(raw: Map<*, *>): T79ImageSecondUpdate {
            require(raw.keys == setOf("profile", "writes") && raw["profile"] == PROFILE)
            val writes = raw["writes"]
            require(writes is List<*> && writes.size <= registers.size)
            val result = T79ImageSecondUpdate()
            for (word in writes) {
                require(word is Int || word is Long)
                require(result.store(registers[result.writeCount], (word as Number).toLong()))
            }
            return result
        }
    }

    private var writeCount = 0
    private val words = LongArray(registers.size)
    val complete get() = writeCount == registers.size
    val accesses get() = writeCount

    fun retainedWord(offset: Int): Long? {
        val index = indexByOffset[offset] ?: return null
        return if (index < writeCount) words[index] else null
    }

    fun access(offset: Int, width: Int, write: Boolean, value: Long, context: DiscoveryContext): Long? {
        if (!write || width != 4 || context.core() != 1 || context.pc() != 0x5af98L) return null
        val register = registers.getOrNull(writeCount) ?: return null
        if (offset != register.offset || context.lr() != register.lr || !store(register, value)) return null
        return value
    }

    private fun store(register: Register, value: Long): Boolean {
        if (!register.accepts(value)) return false
        words[writeCount] = value
        writeCount++
        return true
    }

    fun snapshot() = mapOf(
        "profile" to PROFILE,
        "writes" to (0 until writeCount).map { words[it] },
    )

    fun receipt() = mapOf(
        "profile" to PROFILE,
        "confidence" to "HYPOTHESIS",
        "writeCount" to writeCount,
        "complete" to complete,
        "writtenWords" to (0 until writeCount).associate { registers[it].offset.toString(16) to words[it] },
    )
}
