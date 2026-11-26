package com.github.nyabanban.fuji.emulator.runtime

enum class ImageIrq106Status(val accesses: Int, val bit0: Long) {
    IDLE(0, 0), LATCHED(0, 1), READ(1, 1), ACKNOWLEDGED(2, 0), FINISHED(3, 0);

    fun next(write: Boolean, value: Long, context: DiscoveryContext): ImageIrq106Status? =
        next(write, value, context, 1L, repeatable = false)

    internal fun next(
        write: Boolean,
        value: Long,
        context: DiscoveryContext,
        expectedMask: Long,
        repeatable: Boolean,
    ): ImageIrq106Status? {
        val irqActive = if (repeatable) context.imageIrq106LineActive() else context.imageIrq106Active()
        if (context.core() != 1 || !irqActive) return null
        val pc = context.pc()
        val lr = context.lr()
        return when {
            this == LATCHED && !write && pc == 0x5afe0L && lr == 0x11b7e50L -> READ
            this == READ && write && value == expectedMask && pc == 0x5af98L && lr == 0x11b7f0cL -> ACKNOWLEDGED
            this == ACKNOWLEDGED && !write && pc == 0x5afe0L && lr == 0x11b7c58L -> FINISHED
            else -> null
        }
    }

    fun receipt() = mapOf("profile" to PROFILE, "confidence" to "HYPOTHESIS", "phase" to name, "bit0" to bit0)

    data class State(
        val profile: String,
        val phase: ImageIrq106Status,
        val mask: Long = if (phase == LATCHED || phase == READ) 1L else 0L,
        val completedCycles: Int = if (phase == FINISHED) 1 else 0,
    ) {
        val repeatable: Boolean get() = profile == REPEATABLE_PROFILE
        val totalAccesses: Int get() = when (phase) {
            IDLE -> 0
            LATCHED, READ, ACKNOWLEDGED -> completedCycles * 3 + phase.accesses
            FINISHED -> completedCycles * 3
        }
        val activeValue: Long get() = if (repeatable) mask else phase.bit0

        init {
            require(profile in PROFILES) { "Unknown IRQ106 profile: $profile" }
            if (!repeatable) {
                require(mask == phase.bit0) { "Invalid $PROFILE mask" }
                require(completedCycles == if (phase == FINISHED) 1 else 0) { "Invalid $PROFILE cycle count" }
            } else {
                when (phase) {
                    IDLE -> require(mask == 0L && completedCycles == 0) { "IDLE requires mask=0 and completedCycles=0" }
                    LATCHED, READ -> require(mask in 1L..VALID_MASK && completedCycles >= 0) {
                        "Active IRQ106 phase requires 10-bit mask"
                    }
                    ACKNOWLEDGED -> require(mask == 0L && completedCycles >= 0) {
                        "ACKNOWLEDGED requires mask=0"
                    }
                    FINISHED -> require(mask == 0L && completedCycles >= 1) {
                        "FINISHED requires mask=0 and completedCycles>=1"
                    }
                }
            }
        }

        fun canPulse(statusMask: Long): Boolean {
            if (statusMask !in 1L..VALID_MASK) return false
            return if (repeatable) {
                phase == IDLE || phase == FINISHED
            } else {
                phase == IDLE && statusMask == 1L
            }
        }

        fun latch(statusMask: Long): State {
            require(statusMask in 1L..VALID_MASK) { "IRQ106 status mask must be in 0x1..0x3FF" }
            require(repeatable || statusMask == 1L) { "$PROFILE requires status mask 1" }
            check(phase == IDLE || (repeatable && phase == FINISHED)) {
                "IRQ106 cannot latch in phase $phase for profile $profile"
            }
            return copy(phase = LATCHED, mask = statusMask)
        }

        fun advance(write: Boolean, value: Long, context: DiscoveryContext): State? {
            val expectedMask = if (repeatable) mask else 1L
            val nextPhase = phase.next(write, value, context, expectedMask, repeatable) ?: return null
            return when (nextPhase) {
                READ -> copy(phase = READ)
                ACKNOWLEDGED -> copy(phase = ACKNOWLEDGED, mask = 0L)
                FINISHED -> copy(phase = FINISHED, mask = 0L, completedCycles = completedCycles + 1)
                else -> null
            }
        }

        fun receipt(): Map<String, Any> = if (!repeatable) {
            phase.receipt()
        } else {
            mapOf(
                "profile" to REPEATABLE_PROFILE,
                "confidence" to "HYPOTHESIS",
                "phase" to phase.name,
                "mask" to mask,
                "completedCycles" to completedCycles,
            )
        }
    }

    companion object {
        const val KEY = "imageIrq106Status"
        const val PROFILE = "bit0-ack-once-v1"
        const val REPEATABLE_PROFILE = "repeatable-w1c-v1"
        const val ADDRESS = 0xfff7000cL
        const val VALID_MASK = 0x3ffL
        val PROFILES = setOf(PROFILE, REPEATABLE_PROFILE)

        fun initial(profile: String): State = State(profile, IDLE, 0L, 0)

        fun parse(raw: Any?): ImageIrq106Status {
            val state = parseState(raw)
            require(!state.repeatable) { "Expected $PROFILE IRQ106 status" }
            return state.phase
        }

        fun parseState(raw: Any?): State {
            require(raw is Map<*, *>) { "Invalid IRQ106 status" }
            val profile = raw["profile"]
            val phase = requireNotNull(entries.find { it.name == raw["phase"] }) { "Invalid IRQ106 phase" }
            require(raw["confidence"] == "HYPOTHESIS") { "Invalid IRQ106 confidence" }
            return when (profile) {
                PROFILE -> {
                    require(raw.keys == setOf("profile", "confidence", "phase", "bit0")) { "Invalid $PROFILE keys" }
                    require(raw["bit0"] == phase.bit0 || raw["bit0"] == phase.bit0.toInt()) { "Invalid $PROFILE bit0" }
                    State(PROFILE, phase, phase.bit0, if (phase == FINISHED) 1 else 0)
                }
                REPEATABLE_PROFILE -> {
                    require(raw.keys == setOf("profile", "confidence", "phase", "mask", "completedCycles")) {
                        "Invalid $REPEATABLE_PROFILE keys"
                    }
                    val rawMask = raw["mask"]
                    val rawCycles = raw["completedCycles"]
                    require(rawMask is Int || rawMask is Long) { "Invalid $REPEATABLE_PROFILE mask" }
                    require(rawCycles is Int || rawCycles is Long) { "Invalid $REPEATABLE_PROFILE completedCycles" }
                    val mask = (rawMask as Number).toLong()
                    val cycles = Math.toIntExact((rawCycles as Number).toLong())
                    State(REPEATABLE_PROFILE, phase, mask, cycles)
                }
                else -> throw IllegalArgumentException("Unknown IRQ106 profile: $profile")
            }
        }
    }
}
