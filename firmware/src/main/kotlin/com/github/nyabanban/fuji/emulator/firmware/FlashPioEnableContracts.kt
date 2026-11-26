package com.github.nyabanban.fuji.emulator.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Selected DAT instruction/data guards; functional chip state and timing remain explicit HYP. */
object FlashPioEnableContracts {
    const val PROFILE = "gfx100s-pio-enable-status-functional-experimental-v2"
    const val JEDEC_INPUT = 0x332220L
    const val TABLE_ADDRESS = 0x1812188L
    private const val APP_BASE = 0x1021000L
    private data class Span(val start: Long, val size: Int, val sha256: String)
    private val spans = listOf(
        Span(0x11ded40,56,"76d48ecf1eebfc4b50020d2e5d887c96eb1851d32f3f6954f29dbebb5ab16f1b"),
        Span(0x11ded78,608,"dc72cb2820f79739bfc873ab20c3a4b116b9cce3ab82a540015a66ba634cedb3"),
        Span(0x11df88c,248,"6c4663978bc93c5417793bb17d84465239ef8a1ce424e47d5a8a8e02c224a311"),
        Span(0x11df984,196,"49e55ef9105fc0180ff8b370dce9984a1d62b02ea6e275db094bbf5698c3658b"),
        Span(TABLE_ADDRESS,420,"99aa2098769ef8a4e8ab32d329eeafdc07bad2277c707d9beff18fd47a2a068d")
    )
    val wordAddresses: List<Long> get() = spans.flatMap { (it.start until it.start + it.size step 4).toList() }
    private fun hash(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun records(app: ByteArray): ByteArray {
        require(TABLE_ADDRESS - APP_BASE + 420 <= app.size) { "PIO table outside initialized image" }
        return app.copyOfRange((TABLE_ADDRESS - APP_BASE).toInt(), (TABLE_ADDRESS - APP_BASE + 420).toInt()).also {
            require(hash(it) == spans.last().sha256) { "PIO selected table differs" }
        }
    }
    fun observe(app: ByteArray): Map<String, Any> {
        fun word(pc: Long): Long {
            require(pc >= APP_BASE && pc % 4 == 0L && pc - APP_BASE + 4 <= app.size) { "PIO DAT word outside initialized image" }
            return ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN).getInt((pc - APP_BASE).toInt()).toLong() and 0xffffffffL
        }
        fun immediate(w: Long) = Integer.rotateRight((w and 255).toInt(), ((w ushr 8 and 15) * 2).toInt()).toLong() and 0xffffffffL
        fun half(w: Long) = (w and 0xfff) or (w ushr 4 and 0xf000)
        fun field(w: Long): Pair<Long, Long> { val low = w ushr 7 and 31; return low to ((w ushr 16 and 31) - low + 1) }
        for (s in spans) {
            require(s.start >= APP_BASE && s.start - APP_BASE + s.size <= app.size) { "PIO DAT span outside initialized image" }
            require(hash(app.copyOfRange((s.start - APP_BASE).toInt(), (s.start - APP_BASE + s.size).toInt())) == s.sha256) { "PIO DAT span differs at " + s.start.toString(16) }
        }
        val count = immediate(word(0x11ded78)) + 1
        val stride = immediate(word(0x11ded8c))
        val pointer = half(word(0x11ded94)) or (half(word(0x11ded98)) shl 16)
        val selector = field(word(0x11ded68))
        val busy = immediate(word(0x11def8c)); val enabled = immediate(word(0x11dfa34))
        val words = ByteBuffer.wrap(records(app)).order(ByteOrder.LITTLE_ENDIAN)
        val rows = (0 until count.toInt()).map { index -> List(7) { words.getInt(index * stride.toInt() + it * 4).toLong() and 0xffffffffL } }
        require(count == 15L && stride == 28L && pointer == 0x1870d28L && selector == (10L to 4L) && busy == 2L && enabled == 2L)
        require(rows[1] == listOf(6L,0L,0L,0L,0L,0L,1L) && rows[2] == listOf(5L,0L,0L,0L,1L,0L,1L) && rows[3].all { it == 0L })
        return linkedMapOf("profile" to PROFILE, "jedecInput" to JEDEC_INPUT, "operationCount" to count, "recordStride" to stride,
            "pointerAddress" to pointer, "tableAddress" to TABLE_ADDRESS, "tableBytes" to 420, "tableSha256" to spans.last().sha256,
            "selectorShift" to selector.first, "selectorWidth" to selector.second, "commandBusyMask" to busy, "receiveEnableMask" to enabled,
            "controllerPackingBit" to immediate(word(0x11dfa2c)), "packedReceiveShift" to (word(0x11dfa30) ushr 7 and 31),
            "criticalWords" to wordAddresses.size, "records" to rows,
            "basis" to "DAT four complete functions plus selected15x28-byte table; table matches actual post-stop SIM. Only selector0/1,06 TX-only and05 one-byte RX in bit30-clear mode qualified. Initial latch/timing HYP; no erase/program/physical geometry or writable-media claim.")
    }
    fun verify(image: FirmwareImage, state: Map<String, Any?>): Map<String, Any> {
        val meta = image.metadata
        require(meta.id == "gfx100s-213" && meta.sha256 == "ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12" &&
            state["profile"] == PROFILE && (state["jedec"] as? Number)?.toLong() == JEDEC_INPUT) { "PIO enable requires selected firmware and explicit qualified JEDEC input" }
        val windows = meta.devices.filter { it.id == "soc-flash" }
        require(windows.size == 2 && windows.single { it.port == "soc-flash.registers" }.let { it.address == 0xfffd8000L && it.size == 0x1000L && !it.optional } &&
            windows.single { it.port == "soc-flash.aperture" }.let { it.address == 0xf0000000L && it.size == 0x8000000L && !it.optional }) { "PIO enable exact flash metadata windows differ" }
        return observe(image.decode("section4"))
    }
}
