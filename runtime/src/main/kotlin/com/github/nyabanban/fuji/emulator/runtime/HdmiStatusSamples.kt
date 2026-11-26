package com.github.nyabanban.fuji.emulator.runtime

internal class HdmiStatusSamples(raw: Any?, linkReads: Any? = null) {
    companion object {
        const val REGION = "gfx100s.hdmi.init-words.candidate"
        const val FIRST = 0xfff7b090L
        const val LINK = 0xfff7b008L
        const val MAX_LINK_READS = 4096
        fun contains(address: Long) = address == LINK || address in FIRST until FIRST + 8
        private fun word(value: Any?): Long {
            require(value is Long || value is Int)
            return (value as Number).toLong().also { require(it in 0..0xffffffffL) }
        }
    }

    private val pairs = (raw as? List<*>)?.also { require(it.size in 1..64) }?.map { pair ->
        require(pair is List<*> && pair.size == 2)
        pair.map(::word)
    } ?: throw IllegalArgumentException("HDMI status requires finite u32 pairs")
    private var position = 0
    private val linkSamples = linkReads?.let {
        require(it is List<*> && it.size in 1..MAX_LINK_READS)
        it.map(::word)
    }
    private var linkPosition = 0

    fun access(address: Long, write: Boolean, value: Long): Long? {
        if (address == LINK) {
            if (write) return null
            return linkSamples?.getOrNull(linkPosition)?.also { linkPosition++ }
        }
        val pair = pairs.getOrNull(position / 4) ?: return null
        val phase = position % 4
        val index = phase % 2
        val sample = pair[index]
        if (address != FIRST + 4 * index || write != (phase >= 2) || (write && value != sample)) return null
        position++
        return sample
    }

    fun state() = mapOf("pairs" to pairs, "position" to position, "confidence" to "HYPOTHESIS") +
        (linkSamples?.let { mapOf("linkReads" to it, "linkPosition" to linkPosition) }.orEmpty())

    fun restored(raw: Any?, promote: Boolean): HdmiStatusSamples =
        HdmiStatusSamples(pairs, linkSamples).also { it.restore(raw, promote) }

    fun restore(raw: Any?, promote: Boolean) {
        require(raw is Map<*, *> && raw["confidence"] == "HYPOTHESIS")
        val saved = HdmiStatusSamples(raw["pairs"], raw["linkReads"])
        require(raw.keys == saved.state().keys)
        val cursor = raw["position"]
        require(cursor is Int && cursor in 0..saved.pairs.size * 4)
        if (saved.pairs != pairs) {
            require(promote && cursor == saved.pairs.size * 4 && pairs.size > saved.pairs.size &&
                pairs.take(saved.pairs.size) == saved.pairs) {
                "HDMI status promotion requires appending to an exhausted pair sequence"
            }
        }
        if (saved.linkSamples != linkSamples) {
            require(promote && linkSamples != null) { "Changing HDMI link samples requires explicit promotion" }
            val prefix = saved.linkSamples.orEmpty()
            require(linkSamples.size > prefix.size && linkSamples.subList(0, prefix.size) == prefix) {
                "HDMI link promotion must append to the complete saved sequence"
            }
        }
        val nextLink = if (saved.linkSamples != null) {
            val next = raw["linkPosition"]
            require(next is Int && next in 0..saved.linkSamples.size)
            next
        } else 0
        position = cursor
        linkPosition = nextLink
    }
}
