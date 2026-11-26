package com.github.nyabanban.fuji.emulator.runtime

internal class T79ImageConfigSetter {
    private data class Register(val offset: Int, val setter: Long, val mask: Long,
                                val clearSetter: Long = setter)

    companion object {
        const val PROFILE = "t79-second-config-setter-v1"
        private val enable = Register(0x020, 0x11b972c, 1, 0x11b9610)
        private val scalar = Register(0x054, 0x11b96f0, 0xfff)
        private val fields = buildList {
            add(Register(0x024, 0x11b9770, 0x0fff0fff, 0x11b9620))
            add(Register(0x028, 0x11b9788, 0xfff, 0x11b9630))
            repeat(4) { index ->
                add(Register(0x02c + index * 4, if (index == 0) 0x11b97bc else 0x11b97fc,
                    0x07ff03ff, 0x11b9640L + index * 0x10))
            }
            add(Register(0x03c, 0x11b982c, 3, 0x11b9680))
            add(Register(0x040, 0x11b985c, 0x00ffff7f, 0x11b9690))
            add(Register(0x044, 0x11b989c, 0x003f0f0f, 0x11b96a0))
            add(Register(0x048, 0x11b98d4, 0x00ff3f7f, 0x11b96b0))
            add(Register(0x04c, 0x11b9914, 0x003f0f0f, 0x11b96c0))
            add(Register(0x050, 0x11b994c, 0x00ff0f7f, 0x11b96d0))
        }
        private val remaining = buildList {
            add(Register(0x058, 0x11bd280, 0x01010101))
            repeat(6) { group ->
                val start = 0x09c + group * 16
                add(Register(start, 0x11b9dfc, 0x8003ffff))
                add(Register(start + 4, 0x11b9e18, 0x0fff0fff))
                add(Register(start + 8, 0x11b9e34, 0x0fff0fff))
                add(Register(start + 12, 0x11b9e60, 0x0f0f0fff))
            }
            add(Register(0x0fc, 0x11ba13c, 0x01ff01ff))
            add(Register(0x100, 0x11ba16c, 0x01ff01ff))
            add(Register(0x104, 0x11ba19c, 0x01ff01ff))
        }
        private val submit = Register(0x130, 0x11bab7c, 0x703ff)
        private val setters = listOf(enable) + fields + scalar + remaining + submit
        val offsets = setters.map { it.offset }
        val accessLimit = 1 + setters.size
        private const val ENABLE_READ = 0x11b9720L
        private const val CLEAR_READ = 0x11b9604L

        fun restore(raw: Map<*, *>): T79ImageConfigSetter {
            require(raw.keys == setOf("profile", "enabled", "writes") && raw["profile"] == PROFILE)
            val branch = raw["enabled"]
            require(branch == null || branch is Boolean)
            val writes = raw["writes"]
            require(writes is List<*> && writes.size <= setters.size && (writes.isEmpty() || branch != null))
            val result = T79ImageConfigSetter()
            result.enabled = branch
            for (word in writes) {
                require(word is Int || word is Long)
                require(result.store(setters[result.writeCount], (word as Number).toLong()))
            }
            return result
        }
    }

    private var enabled: Boolean? = null
    private var writeCount = 0
    private val words = setters.associate { it.offset to Image70000ConfigWords.INITIAL_WORD }.toMutableMap()
    val complete get() = writeCount == setters.size
    val accesses get() = writeCount + if (enabled == null) 0 else 1

    fun retainedWords(): Map<Int, Long> = words.toMap()

    fun access(offset: Int, width: Int, write: Boolean, value: Long, context: DiscoveryContext): Long? {
        if (width != 4 || context.core() != 1 || context.pc() != (if (write) 0x5af98L else 0x5afe0L)) return null
        val lr = context.lr()
        if (enabled == null) {
            if (write || offset != enable.offset || lr != ENABLE_READ && lr != CLEAR_READ) return null
            enabled = lr == ENABLE_READ
            return words.getValue(offset)
        }
        val register = setters.getOrNull(writeCount) ?: return null
        val caller = if (enabled == true) register.setter else register.clearSetter
        if (!write || offset != register.offset || lr != caller || !store(register, value)) return null
        return value
    }

    private fun store(register: Register, value: Long): Boolean {
        if (value and register.mask.inv() != 0L) return false
        if (register == enable && value != (if (enabled == true) 1L else 0L)) return false
        if (enabled == false && register in fields && value != 0L) return false
        if (register == submit && value ushr 16 > 5) return false
        words[register.offset] = value
        writeCount++
        return true
    }

    fun snapshot() = mapOf("profile" to PROFILE, "enabled" to enabled,
        "writes" to setters.take(writeCount).map { words.getValue(it.offset) })

    fun receipt() = mapOf("profile" to PROFILE, "confidence" to "HYPOTHESIS", "initialValue" to 0L,
        "enabled" to enabled, "writeCount" to writeCount, "complete" to complete,
        "writtenWords" to setters.take(writeCount).associate { it.offset.toString(16) to words.getValue(it.offset) })
}
