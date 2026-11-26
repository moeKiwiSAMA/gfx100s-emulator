package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.MmioDevice

/**
 * Ordered write-once HYPOTHESIS setup pair [0xFFF49530, 0xFFF49538) and optional
 * tail [0xFFF49500, 0xFFF49504):
 * - pair pos 0: WRITE4 +0 = 0x10000704
 * - pair pos 1: WRITE4 +4 = 0x70
 * - pair pos 2 (requires tail49500): WRITE4 +4 = 0x71
 * - tail pos 0 (requires pair pos == 3): WRITE4 +0 = 3
 * Physical device identity, reset, and completion semantics remain UNKNOWN.
 */
internal class SetupSequence49530And49500(
    val pairEnabled: Boolean,
    val tailEnabled: Boolean,
    val repeatable: Boolean = false,
) {
    init {
        require(!tailEnabled || pairEnabled) { "tail49500 requires write49530" }
    }

    private var pairPosition: Int = 0
    private var tailPosition: Int = 0

    val pairDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 70
        override fun read(offset: Long, size: Int): Long =
            error("FFF49530 write-only setup pair has no readback law")

        override fun write(offset: Long, size: Int, value: Long) {
            require(pairEnabled) { "FFF49530 setup pair is disabled" }
            require(size == 4 && (offset == 0L || offset == 4L) && value in 0L..0xffffffffL) {
                "FFF49530 requires aligned WRITE4 at offset 0 or 4"
            }
            val maxPairPos = if (repeatable && tailEnabled) MAX_REPEATABLE_CYCLES * 3 else if (tailEnabled) 3 else 2
            if (pairPosition >= maxPairPos) {
                error("FFF49530 setup pair sequence exhausted at position=$pairPosition")
            }
            val phase = if (repeatable) pairPosition % 3 else pairPosition
            when (phase) {
                0 -> {
                    if (repeatable && pairPosition > 0) {
                        require(tailPosition * 3 == pairPosition) {
                            "FFF49530 repeatable cycle requires prior tail completion (pairPosition=$pairPosition, tailPosition=$tailPosition)"
                        }
                    }
                    require(offset == 0L && value == 0x10000704L) {
                        "FFF49530 step 0 requires offset 0 value 0x10000704"
                    }
                }
                1 -> require(offset == 4L && value == 0x70L) {
                    "FFF49530 step 1 requires offset 4 value 0x70"
                }
                2 -> {
                    require(tailEnabled) { "FFF49530 step 2 requires tail49500 enabled" }
                    require(offset == 4L && value == 0x71L) {
                        "FFF49530 step 2 requires offset 4 value 0x71"
                    }
                }
                else -> error("FFF49530 setup pair sequence exhausted at position=$pairPosition")
            }
            pairPosition++
        }
    }

    val tailDevice: MmioDevice = object : MmioDevice {
        override val clockOrder = 70
        override fun read(offset: Long, size: Int): Long =
            error("FFF49500 write-only setup tail has no readback law")

        override fun write(offset: Long, size: Int, value: Long) {
            require(tailEnabled && pairEnabled) { "FFF49500 setup tail is disabled" }
            require(size == 4 && offset == 0L && value in 0L..0xffffffffL) {
                "FFF49500 requires aligned WRITE4 at offset 0"
            }
            if (repeatable) {
                require(tailPosition < MAX_REPEATABLE_CYCLES && pairPosition == (tailPosition + 1) * 3 && value == 3L) {
                    "FFF49500 repeatable tail requires pairPosition=${(tailPosition + 1) * 3}, value=3 (got pairPosition=$pairPosition, tailPosition=$tailPosition)"
                }
                tailPosition++
            } else {
                require(pairPosition == 3 && tailPosition == 0 && value == 3L) {
                    "FFF49500 tail requires pairPosition=3, tailPosition=0, value=3"
                }
                tailPosition = 1
            }
        }
    }

    fun pairState(): Map<String, Any> = mapOf(
        "profile" to WRITE49530_PROFILE,
        "position" to pairPosition,
        "tailEnabled" to tailEnabled,
        "basis" to "DAT section5 0x02394E98/0x02394EA8/0x02394EF4 write sequence; HYPOTHESIS ordered write-once model",
    )

    fun tailState(): Map<String, Any> = mapOf(
        "profile" to TAIL49500_PROFILE,
        "position" to tailPosition,
        "basis" to "DAT section5 0x02394F04 write sequence; requires 0xFFF49534=0x71; HYPOTHESIS ordered write-once model",
    )

    fun savePairSnapshot(): Map<String, Any>? = if (!pairEnabled) null else linkedMapOf(
        "profile" to WRITE49530_PROFILE,
        "position" to pairPosition,
    )

    fun saveTailSnapshot(): Map<String, Any>? = if (!tailEnabled) null else linkedMapOf(
        "profile" to TAIL49500_PROFILE,
        "position" to tailPosition,
    )

    data class ValidatedSnapshot(val pairPosition: Int, val tailPosition: Int)

    fun validateSnapshots(rawPair: Any?, rawTail: Any?): ValidatedSnapshot {
        require((rawPair != null) == pairEnabled) { "Snapshot write49530State presence mismatch" }
        require((rawTail != null) == tailEnabled) { "Snapshot tail49500State presence mismatch" }
        val nextPair = if (rawPair == null) {
            0
        } else {
            val map = rawPair as? Map<*, *> ?: throw IllegalArgumentException("Snapshot write49530State requires map")
            require(map.keys == SNAPSHOT_KEYS) { "Unexpected write49530State snapshot keys" }
            require(map["profile"] == WRITE49530_PROFILE) { "Unsupported write49530State snapshot profile" }
            val rawPos = map["position"]
            require(rawPos is Byte || rawPos is Short || rawPos is Int || rawPos is Long) {
                "Snapshot write49530State position requires integer"
            }
            val pos = (rawPos as Number).toLong()
            val maxPos = if (repeatable && tailEnabled) MAX_REPEATABLE_CYCLES * 3L else if (tailEnabled) 3L else 2L
            require(pos in 0L..maxPos) { "Snapshot write49530State position out of range: $pos" }
            pos.toInt()
        }
        val nextTail = if (rawTail == null) {
            0
        } else {
            val map = rawTail as? Map<*, *> ?: throw IllegalArgumentException("Snapshot tail49500State requires map")
            require(map.keys == SNAPSHOT_KEYS) { "Unexpected tail49500State snapshot keys" }
            require(map["profile"] == TAIL49500_PROFILE) { "Unsupported tail49500State snapshot profile" }
            val rawPos = map["position"]
            require(rawPos is Byte || rawPos is Short || rawPos is Int || rawPos is Long) {
                "Snapshot tail49500State position requires integer"
            }
            val pos = (rawPos as Number).toLong()
            val maxTail = if (repeatable) MAX_REPEATABLE_CYCLES.toLong() else 1L
            require(pos in 0L..maxTail) { "Snapshot tail49500State position out of range: $pos" }
            if (pos > 0L) {
                require(nextPair >= (pos * 3L).toInt()) {
                    "Snapshot tail49500State position=$pos requires write49530State position>=${pos * 3L}"
                }
            }
            pos.toInt()
        }
        return ValidatedSnapshot(nextPair, nextTail)
    }

    fun applySnapshot(validated: ValidatedSnapshot) {
        pairPosition = validated.pairPosition
        tailPosition = validated.tailPosition
    }

    companion object {
        const val WRITE49530_PROFILE = "fff49530-write-pair-once-v1"
        const val TAIL49500_PROFILE = "fff49534-49500-write-tail-once-v1"
        const val WRITE49530_PORT = "soc-system-control.fff49530-pair"
        const val TAIL49500_PORT = "soc-system-control.fff49500-tail"
        const val MAX_REPEATABLE_CYCLES = 1024
        private val SNAPSHOT_KEYS = setOf("profile", "position")
    }
}
