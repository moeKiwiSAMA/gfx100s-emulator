package com.github.nyabanban.fuji.emulator.components.ispcmd

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import java.nio.ByteBuffer
import java.util.Base64

/**
 * Stateful command-queue & DMA payload interpreter for 0xFFF6C204..0xFFF6C244:
 * observes MODE (0xFFF6C208), burst config (0xFFF6C21C, 0xFFF6C20C..0xFFF6C218),
 * START (0xFFF6C204), reads guest RAM at 0x028F2000 / 0x028F2940 via DmaMemory,
 * and serves the programmed 0x82 00 01 reply word at 0xFFF6C240..0xFFF6C224.
 */
internal class ProgrammedCommandReply {
    private val events = ArrayList<Map<String, Any?>>()
    private var step = 0
    private var mode = 0L
    private var pointer = 0L
    private var commits = 0
    private var pendingByte: Int? = null
    private var pendingRead01 = false
    private var learned: Int? = null
    private var ready = false
    var words = 0
        private set

    fun observe(
        address: Long,
        write: Boolean,
        value: Long,
        before: Long,
        tick: Long,
        memoryProvider: () -> DmaMemory,
    ) {
        if (words == 8 || !relevant(address, write, value)) return
        if (write && address == START && before != 0L) {
            error("soc-isp-cmd START write requires prior value 0")
        }
        val payload = if (write && address == START && step == 6) {
            val size = count() * 8
            val mem = memoryProvider()
            mem.check(pointer, size.toLong(), false)
            ByteArray(size).also { bytes ->
                val buf = ByteBuffer.wrap(bytes)
                mem.read(pointer, buf)
                check(!buf.hasRemaining()) { "soc-isp-cmd DmaMemory short read at 0x${pointer.toString(16)}" }
            }
        } else null
        accept(address, write, value, tick, payload)
    }

    private fun relevant(address: Long, write: Boolean, value: Long): Boolean {
        if (!write) return address == START && step in 7..8
        if (address !in WRITER_ADDRESSES) return false
        return events.isNotEmpty() || (address == MODE && value == 0x104ffL)
    }

    private fun count(): Int = (mode and 255).toInt()

    private fun accept(address: Long, write: Boolean, value: Long, tick: Long, payload: ByteArray?) {
        require(!ready && tick >= 0L && (events.lastOrNull()?.get("tick") as? Long ?: -1L) < tick) {
            "soc-isp-cmd programmed reply state/tick violation"
        }
        require(events.size < 16 * 9 && (payload == null || step == 6)) {
            "soc-isp-cmd programmed reply event limit/payload violation"
        }
        require(address == ORDER[step] && write == (step < 7)) {
            "soc-isp-cmd programmed reply unexpected step=$step address=0x${address.toString(16)} write=$write"
        }
        when (step) {
            0 -> {
                require(commits < 16 && (commits != 0 || value == 0x104ffL)) {
                    "soc-isp-cmd programmed reply invalid initial mode/commit count"
                }
                require(value in 0x10401L..0x104ffL || value == 0x30401L) {
                    "soc-isp-cmd programmed reply unsupported mode 0x${value.toString(16)}"
                }
                mode = value
            }
            1, 2, 4 -> require(value == 0L) { "soc-isp-cmd expected zero at step=$step" }
            3 -> {
                require((value == BULK && mode != 0x30401L) || (value == SINGLE && count() == 1)) {
                    "soc-isp-cmd invalid command buffer pointer 0x${value.toString(16)}"
                }
                pointer = value
            }
            5 -> require(value == 8L) { "soc-isp-cmd expected stride/size 8 at step 5" }
            6 -> {
                require(value == 1L && payload != null && payload.size == count() * 8) {
                    "soc-isp-cmd invalid START payload"
                }
                if (mode == 0x30401L) {
                    require(payload[0] == 0x82.toByte() && payload[1] == 0.toByte() && payload[2].toInt() in SELECTORS) {
                        "soc-isp-cmd invalid 0x82 command header"
                    }
                    val is01 = payload[2].toInt() == 1
                    require(!is01 || learned != null) {
                        "soc-isp-cmd 82 00 01 requested before 0x81 value was learned"
                    }
                    pendingRead01 = is01
                } else {
                    var candidate: Int? = null
                    for (offset in payload.indices step 8) {
                        require(payload[offset] == 0x81.toByte()) {
                            "soc-isp-cmd bulk command entry missing 0x81 opcode"
                        }
                        if (payload[offset + 1] == 0.toByte() && payload[offset + 2] == 1.toByte()) {
                            candidate = payload[offset + 3].toInt() and 255
                        }
                    }
                    pendingByte = candidate
                }
                commits++
            }
            7 -> require(value == 1L) { "soc-isp-cmd START poll step 7 expected 1" }
            8 -> {
                require(value == 0L) { "soc-isp-cmd START poll step 8 expected 0" }
                pendingByte?.let { learned = it }
                pendingByte = null
                ready = pendingRead01
                pendingRead01 = false
            }
        }
        events.add(
            linkedMapOf(
                "address" to address,
                "write" to write,
                "value" to value,
                "tick" to tick,
                "payload" to payload?.let { Base64.getEncoder().encodeToString(it) },
            ),
        )
        step = (step + 1) % ORDER.size
    }

    fun read(): Long {
        check(ready && words < 8) { "soc-isp-cmd programmed reply not ready or exhausted" }
        return if (words++ == 0) checkNotNull(learned).toLong() shl 16 else 0L
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "events" to events.map { LinkedHashMap(it) },
        "words" to words,
    )

    fun receipt(): Map<String, Any?> = linkedMapOf(
        "profile" to PROFILE,
        "nextStep" to step,
        "commits" to commits,
        "completedByte" to learned,
        "responseReady" to ready,
        "words" to words,
    )

    fun verifyBacking(wordAt: (Long) -> Long) {
        if (words == 8) return
        val last = events.associateBy { it.getValue("address") as Long }
        for ((address, event) in last) {
            val retained = if (event["write"] == true) event.getValue("value") as Long else 0L
            require(wordAt(address) == retained) {
                "soc-isp-cmd command receipt differs from retained register 0x${address.toString(16)}"
            }
        }
    }

    companion object {
        const val PROFILE = "committed-81-read-once-v1"
        const val SINGLE = 0x028f2000L
        const val BULK = 0x028f2940L
        private const val START = 0xfff6c204L
        private const val MODE = 0xfff6c208L
        private val WRITER_ADDRESSES = setOf(
            MODE, 0xfff6c21cL, 0xfff6c20cL, 0xfff6c210L, 0xfff6c214L, 0xfff6c218L, START,
        )
        private val ORDER = listOf(
            MODE, 0xfff6c21cL, 0xfff6c20cL, 0xfff6c210L, 0xfff6c214L, 0xfff6c218L, START, START, START,
        )
        private val SELECTORS = setOf(1, 0x40, 0x41)

        fun restore(raw: Map<*, *>): ProgrammedCommandReply {
            require(raw.keys == setOf("events", "words")) { "Unexpected programmedReply snapshot keys" }
            val history = raw["events"] as? List<*> ?: throw IllegalArgumentException("programmedReply events requires list")
            require(history.size <= 16 * 9) { "programmedReply events exceed limit" }
            fun number(value: Any?): Long {
                require(value is Byte || value is Short || value is Int || value is Long) {
                    "programmedReply numeric field requires integer"
                }
                return (value as Number).toLong()
            }
            val result = ProgrammedCommandReply()
            for (entry in history) {
                val row = entry as? Map<*, *> ?: throw IllegalArgumentException("programmedReply event requires map")
                require(row.keys == setOf("address", "write", "value", "tick", "payload")) {
                    "Unexpected programmedReply event keys"
                }
                val payload = row["payload"]?.let {
                    require(it is String && it.length <= 2720) { "programmedReply payload invalid" }
                    Base64.getDecoder().decode(it)
                }
                result.accept(
                    number(row["address"]),
                    row["write"] as? Boolean ?: throw IllegalArgumentException("programmedReply write requires boolean"),
                    number(row["value"]),
                    number(row["tick"]),
                    payload,
                )
            }
            val words = number(raw["words"])
            require(words in 0..8 && (words == 0L || result.ready)) {
                "programmedReply words out of range or ready mismatch"
            }
            result.words = words.toInt()
            return result
        }
    }
}
