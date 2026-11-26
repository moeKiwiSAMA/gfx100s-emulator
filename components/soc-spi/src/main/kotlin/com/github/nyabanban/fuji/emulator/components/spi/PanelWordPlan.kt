package com.github.nyabanban.fuji.emulator.components.spi

internal class PanelWordPlan(raw: Any?, maximumWords: Int = if (raw is Map<*, *>) 2048 else 256, private val fixedProfile: String? = null,
                            private val bitsPerWord: Int = 16, private val continuous: Boolean = false,
                            private val sharedBus: Boolean = false) {
    private val words: List<Map<String, Int>>
    private val fixedMisoHigh: Boolean?
    val exchangeLimit: Int
    val isContinuousFixedMiso: Boolean get() = continuous && fixedMisoHigh != null
    private var sharedPrefixCursor: Int = 0

    init {
        val prefix = if (raw is Map<*, *>) {
            val fields = setOf("prefix", "fixedMisoHigh", "exchangeLimit")
            require(raw.keys == if (fixedProfile == null) fields else fields + "profile")
            require(raw["profile"] == fixedProfile)
            fixedMisoHigh = raw["fixedMisoHigh"] as? Boolean ?: error("Missing fixed MISO level")
            val limit = raw["exchangeLimit"]
            require(limit is Int || limit is Long)
            val number = (limit as Number).toLong()
            require(number in 1..maximumWords)
            exchangeLimit = number.toInt()
            val rows = raw["prefix"]
            require(rows is List<*> && rows.size < exchangeLimit)
            rows
        } else {
            require(raw is List<*> && raw.size in 1..maximumWords) { "Expected 1..$maximumWords scripted SPI exchanges" }
            fixedMisoHigh = null
            exchangeLimit = raw.size
            raw
        }
        words = prefix.map { entry ->
            require(entry is Map<*, *> && entry.keys == setOf("tx", "rx"))
            fun word(key: String): Int {
                val value = entry[key]
                require(value is Int || value is Long)
                val parsed = (value as Number).toLong()
                require(parsed in 0L until (1L shl bitsPerWord))
                return parsed.toInt()
            }
            mapOf("tx" to word("tx"), "rx" to word("rx"))
        }
    }

    fun settings(): Any = if (fixedMisoHigh == null) words else
        mapOf("prefix" to words, "fixedMisoHigh" to fixedMisoHigh, "exchangeLimit" to exchangeLimit) +
            (fixedProfile?.let { mapOf("profile" to it) } ?: emptyMap())

    fun accepts(saved: PanelWordPlan): Boolean = if (saved.fixedMisoHigh == null) {
        words.take(saved.words.size) == saved.words
    } else {
        fixedMisoHigh == saved.fixedMisoHigh && words == saved.words && exchangeLimit >= saved.exchangeLimit
    }

    fun resetCursor() {
        sharedPrefixCursor = 0
    }

    fun syncCursorFrom(other: PanelWordPlan) {
        sharedPrefixCursor = other.sharedPrefixCursor
    }

    fun exchange(position: Long, bits: Int, tx: Int): Int {
        if (!(continuous && fixedMisoHigh != null)) {
            check(position in 0L until exchangeLimit.toLong()) { "Scripted SPI reply sequence exhausted" }
        }
        if (sharedBus && continuous && fixedMisoHigh != null) {
            require(bits in 1..32) { "Unsupported continuous SPI word width: $bits" }
            val mask = if (bits == 32) 0xffffffffL else (1L shl bits) - 1L
            require((tx.toLong() and 0xffffffffL) <= mask) { "SPI word exceeds $bits-bit width" }
            if (bits == bitsPerWord && words.isNotEmpty()) {
                val cursor = sharedPrefixCursor
                if (tx == words[cursor]["tx"]) {
                    val rx = words[cursor].getValue("rx")
                    sharedPrefixCursor = if (cursor + 1 < words.size) cursor + 1 else 0
                    return rx
                }
                if (cursor != 0 && tx == words[0]["tx"]) {
                    val rx = words[0].getValue("rx")
                    sharedPrefixCursor = if (words.size > 1) 1 else 0
                    return rx
                }
            }
            sharedPrefixCursor = 0
            return if (fixedMisoHigh == true) mask.toInt() else 0
        }
        if (continuous && fixedMisoHigh != null && position >= words.size) {
            require(bits in 1..32) { "Unsupported continuous SPI word width: $bits" }
            val mask = if (bits == 32) 0xffffffffL else (1L shl bits) - 1L
            require((tx.toLong() and 0xffffffffL) <= mask) { "SPI word exceeds $bits-bit width" }
            return if (fixedMisoHigh == true) mask.toInt() else 0
        }
        require(bits == bitsPerWord && tx in 0 until (1 shl bitsPerWord)) { "Expected $bitsPerWord-bit SPI word" }
        if (position >= words.size) return if (fixedMisoHigh == true) ((1 shl bitsPerWord) - 1) else 0
        val word = words[position.toInt()]
        require(tx == word["tx"]) { "Unexpected SPI word at exchange $position" }
        return word.getValue("rx")
    }
}
