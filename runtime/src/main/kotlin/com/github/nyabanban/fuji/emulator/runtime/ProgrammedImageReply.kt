package com.github.nyabanban.fuji.emulator.runtime

import java.util.Base64

internal class ProgrammedImageReply(val profile: String = PROFILE) {
    init { require(profile in PROFILES) { "Unknown ProgrammedImageReply profile: $profile" } }
    private val repeatable: Boolean get() = profile == REPEATABLE_PROFILE
    private val events = ArrayList<Map<String, Any?>>()
    private var step = 0
    private var mode = 0L
    private var pointer = 0L
    private var commits = 0
    private var lastTick = -1L
    private var pendingByte: Int? = null
    private var pendingRead01 = false
    private var learned: Int? = null
    private var ready = false
    var words = 0
        private set
    var totalWords = 0
        private set

    fun observe(context: DiscoveryContext, address: Long, write: Boolean, value: Long, before: Long,
                refuse: (Int) -> Nothing) {
        if ((!repeatable && words == 8) || !relevant(address, write, value)) return
        val caller = if (write) writers[address] else 0x11b648cL
        if (context.core() != 1 || context.pc() != (if (write) 0x5af98L else 0x5afe0L) ||
            context.lr() != caller) refuse(DiscoveryJournal.POLICY)
        if (write && address == START && before != 0L) refuse(DiscoveryJournal.POLICY)
        try {
            val payload = if (write && address == START && step == 6)
                context.imageCommandBytes(pointer, count() * 8) else null
            accept(address, write, value, context.tick(), payload)
        } catch (_: IllegalArgumentException) {
            refuse(DiscoveryJournal.POLICY)
        }
    }

    private fun relevant(address: Long, write: Boolean, value: Long): Boolean {
        if (!write) return address == START && step in 7..8
        if (address !in writers) return false
        return events.isNotEmpty() || (repeatable && (commits > 0 || step > 0)) ||
            (address == MODE && (value == 0x104ffL || (repeatable && (value in 0x10401L..0x104ffL || value == 0x30401L))))
    }

    private fun count() = (mode and 255).toInt()

    private fun accept(address: Long, write: Boolean, value: Long, tick: Long, payload: ByteArray?) {
        val maxCommits = if (repeatable) MAX_REPEATABLE_COMMITS else 16
        require(!ready && tick >= 0 && lastTick < tick)
        require((repeatable || events.size < 16 * 9) && (payload == null || step == 6))
        require(address == order[step] && write == (step < 7))
        when (step) {
            0 -> {
                require(commits < maxCommits && (commits != 0 || repeatable || value == 0x104ffL))
                require(value in 0x10401L..0x104ffL || value == 0x30401L)
                mode = value
            }
            1, 2, 4 -> require(value == 0L)
            3 -> {
                require(value == BULK && mode != 0x30401L || value == SINGLE && count() == 1)
                pointer = value
            }
            5 -> require(value == 8L)
            6 -> {
                require(value == 1L && payload != null && payload.size == count() * 8)
                if (mode == 0x30401L) {
                    require(payload[0] == 0x82.toByte() && payload[1] == 0.toByte() &&
                        payload[2].toInt() in selectors)
                    val is01 = payload[2].toInt() == 1
                    require(!is01 || learned != null)
                    pendingRead01 = is01
                } else {
                    var candidate: Int? = null
                    for (offset in payload.indices step 8) {
                        require(payload[offset] == 0x81.toByte())
                        if (payload[offset+1] == 0.toByte() && payload[offset+2] == 1.toByte())
                            candidate = payload[offset+3].toInt() and 255
                    }
                    pendingByte = candidate
                }
                commits++
            }
            7 -> require(value == 1L)
            8 -> {
                require(value == 0L)
                pendingByte?.let { learned = it }
                pendingByte = null
                ready = pendingRead01
                pendingRead01 = false
            }
        }
        lastTick = tick
        if (!repeatable) {
            events.add(mapOf("address" to address, "write" to write, "value" to value, "tick" to tick,
                "payload" to payload?.let { Base64.getEncoder().encodeToString(it) }))
        }
        step = (step + 1) % order.size
    }

    fun read(refuse: (Int) -> Nothing): Long {
        if (!ready || words >= 8) refuse(DiscoveryJournal.MISSING)
        val out = if (words == 0) checkNotNull(learned).toLong() shl 16 else 0L
        words++
        totalWords++
        if (repeatable && words == 8) {
            words = 0
            ready = false
        }
        return out
    }

    fun snapshot(): Map<String, Any?> = if (!repeatable) {
        mapOf("events" to events.toList(), "words" to words)
    } else {
        linkedMapOf(
            "profile" to REPEATABLE_PROFILE,
            "step" to step,
            "mode" to mode,
            "pointer" to pointer,
            "commits" to commits,
            "lastTick" to lastTick,
            "pendingByte" to pendingByte,
            "pendingRead01" to pendingRead01,
            "learned" to learned,
            "ready" to ready,
            "words" to words,
            "totalWords" to totalWords,
        )
    }
    fun receipt() = mapOf("profile" to profile, "nextStep" to step, "commits" to commits,
        "completedByte" to learned, "responseReady" to ready, "words" to words) +
        (if (repeatable) mapOf("totalWords" to totalWords) else emptyMap())
    fun verifyBacking(word: (Long) -> Long) {
        if (repeatable || words == 8) return
        val last = events.associateBy { it.getValue("address") as Long }
        for ((address, event) in last) {
            val retained = if (event["write"] == true) event.getValue("value") as Long else 0L
            require(word(address) == retained) { "Command receipt differs from retained register ${address.toString(16)}" }
        }
    }

    companion object {
        const val KEY = "imageReply820001Readback"
        const val PROFILE = "committed-81-read-once-v1"
        const val REPEATABLE_PROFILE = "committed-81-read-repeatable-v1"
        val PROFILES = setOf(PROFILE, REPEATABLE_PROFILE)
        private const val MAX_REPEATABLE_COMMITS = 4096
        private const val MODE = 0xfff6c208L
        private const val START = 0xfff6c204L
        private const val SINGLE = 0x028f2000L
        private const val BULK = 0x028f2940L
        private val writers = linkedMapOf(MODE to 0x11b6500L, 0xfff6c21cL to 0x11b3988L,
            0xfff6c20cL to 0x11b399cL, 0xfff6c210L to 0x11b39acL, 0xfff6c214L to 0x11b39c0L,
            0xfff6c218L to 0x11b39d0L, START to 0x11b6464L)
        private val order = writers.keys.toList() + listOf(START, START)
        private val selectors = setOf(1, 0x40, 0x41)

        fun restore(raw: Map<*, *>, targetProfile: String = PROFILE, allowPromotion: Boolean = false): ProgrammedImageReply {
            require(targetProfile in PROFILES)
            fun number(value: Any?): Long {
                require(value is Int || value is Long)
                return (value as Number).toLong()
            }
            if (raw["profile"] == REPEATABLE_PROFILE) {
                require(targetProfile == REPEATABLE_PROFILE)
                val expectedKeys = setOf("profile", "step", "mode", "pointer", "commits", "lastTick",
                    "pendingByte", "pendingRead01", "learned", "ready", "words", "totalWords")
                require(raw.keys == expectedKeys)
                val result = ProgrammedImageReply(REPEATABLE_PROFILE)
                result.step = Math.toIntExact(number(raw["step"])).also { require(it in 0 until order.size) }
                result.mode = number(raw["mode"]).also { require(it >= 0L) }
                result.pointer = number(raw["pointer"]).also { require(it >= 0L) }
                result.commits = Math.toIntExact(number(raw["commits"])).also { require(it in 0..MAX_REPEATABLE_COMMITS) }
                result.lastTick = number(raw["lastTick"]).also { require(it >= -1L) }
                result.pendingByte = raw["pendingByte"]?.let { Math.toIntExact(number(it)).also { b -> require(b in 0..255) } }
                result.pendingRead01 = raw["pendingRead01"] as Boolean
                result.learned = raw["learned"]?.let { Math.toIntExact(number(it)).also { b -> require(b in 0..255) } }
                result.ready = raw["ready"] as Boolean
                result.words = Math.toIntExact(number(raw["words"])).also { require(it in 0..7 && (it == 0 || result.ready)) }
                result.totalWords = Math.toIntExact(number(raw["totalWords"])).also { require(it >= result.words) }
                return result
            }
            require(targetProfile == PROFILE || (allowPromotion && targetProfile == REPEATABLE_PROFILE))
            require(raw.keys == setOf("events", "words"))
            val history = raw["events"] as List<*>
            require(history.size <= 16 * 9)
            val legacy = ProgrammedImageReply(PROFILE)
            for (entry in history) {
                val row = entry as Map<*, *>
                require(row.keys == setOf("address", "write", "value", "tick", "payload"))
                val payload = row["payload"]?.let {
                    require(it is String && it.length <= 2720)
                    Base64.getDecoder().decode(it)
                }
                legacy.accept(number(row["address"]), row["write"] as Boolean, number(row["value"]),
                    number(row["tick"]), payload)
            }
            val words = number(raw["words"])
            require(words in 0..8 && (words == 0L || legacy.ready))
            legacy.words = words.toInt()
            legacy.totalWords = words.toInt()
            if (targetProfile == PROFILE) return legacy
            return ProgrammedImageReply(REPEATABLE_PROFILE).also { promoted ->
                promoted.step = legacy.step
                promoted.mode = legacy.mode
                promoted.pointer = legacy.pointer
                promoted.commits = legacy.commits
                promoted.lastTick = legacy.lastTick
                promoted.pendingByte = legacy.pendingByte
                promoted.pendingRead01 = legacy.pendingRead01
                promoted.learned = legacy.learned
                promoted.totalWords = legacy.totalWords
                if (legacy.words == 8) {
                    promoted.words = 0
                    promoted.ready = false
                } else {
                    promoted.words = legacy.words
                    promoted.ready = legacy.ready
                }
            }
        }
    }
}
