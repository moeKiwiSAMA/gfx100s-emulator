package com.github.nyabanban.fuji.emulator.runtime

internal class C000StopReply(val profile: String = PROFILE) {
    init { require(profile in PROFILES) { "Unknown C000 stop reply profile: $profile" } }
    enum class Phase { IDLE, REQUESTED, ACKNOWLEDGED, CANCELLED }
    var phase = Phase.IDLE
        private set

    fun access(context: DiscoveryContext, write: Boolean, before: Long, value: Long, refuse: (Int) -> Nothing): Long {
        if (write) {
            if (context.core() == 1 && context.pc() == 0x5af98L && context.lr() == 0x11b34f8L) {
                if (value != (before and 1L.inv())) refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                if (phase != Phase.IDLE && !(profile == REPEATABLE_PROFILE && phase == Phase.ACKNOWLEDGED)) {
                    refuse(DiscoveryJournal.DIAGNOSTIC_LIMIT)
                }
                phase = Phase.REQUESTED
            } else if (phase == Phase.REQUESTED) {
                phase = Phase.CANCELLED
            }
            return value
        }
        if (phase == Phase.REQUESTED && context.core() == 1 && context.pc() == 0x5afe0L && context.lr() == 0x11b3508L) {
            phase = Phase.ACKNOWLEDGED
            return before or 1L
        }
        return before
    }

    fun state() = mapOf("completion" to profile, "completionPhase" to phase.name)

    fun prepareRestore(row: Map<*, *>, lowByte: Byte, promote: Boolean): () -> Unit {
        val restored = if ("completion" in row) {
            val savedProfile = row["completion"] as? String ?: throw IllegalArgumentException("Invalid C000 completion profile")
            require(savedProfile == profile || (promote && savedProfile == PROFILE && profile == REPEATABLE_PROFILE)) {
                "Incompatible C000 completion profile: saved=$savedProfile target=$profile"
            }
            Phase.valueOf(row["completionPhase"] as String).also {
                val minimum = when (it) { Phase.IDLE -> 0; Phase.REQUESTED -> 1; else -> 2 }
                require((row["accessAttempts"] as Number).toLong() >= minimum)
                require(it != Phase.REQUESTED || lowByte.toInt() and 1 == 0)
            }
        } else {
            require(promote && "completionPhase" !in row) { "C000 stop reply requires explicit promotion" }
            Phase.IDLE
        }
        return { phase = restored }
    }

    companion object {
        const val PROFILE = "stop-bit0-set-after-poll-once-v1"
        const val REPEATABLE_PROFILE = "stop-bit0-set-after-poll-repeatable-v1"
        val PROFILES = setOf(PROFILE, REPEATABLE_PROFILE)
    }
}
