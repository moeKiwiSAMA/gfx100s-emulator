package com.github.nyabanban.fuji.emulator.components.dram

internal class DramPhyReplay(program: List<Pair<Int, Long>>, configuration: List<Pair<Int, Long>>,
                             val pollWords: List<Long>, val repeatable: Boolean = false) {
    companion object {
        const val PROFILE = "dat-replay-once-v1"
        const val REPEATABLE_PROFILE = "dat-replay-repeatable-v1"
        val PROFILES = setOf(PROFILE, REPEATABLE_PROFILE)
        val pollCounts = setOf(4, 24)
        val setup = listOf(0x2020 to 4L, 0x4660 to 0x03000004L, 0x46dc to 0x20050108L,
            0x2000 to 0xb01L, 0x4610 to 0x04000300L, 0x227c to 0x01000000L)
        val calibration = listOf(0x4660 to 0x07ff0000L, 0x4664 to 0x7ffL, 0x4480 to 0x7ff00L,
            0x4484 to 0x07ff07ffL, 0x4488 to 0x07ff07ffL, 0x448c to 0x7ffL)
        private val calibrationOffsets = calibration.map { it.first }.toSet()
        val request = listOf(0x710c to 1L, 0x7114 to 0L, 0x7c40 to 0x80000000L,
            0x7c14 to 0L, 0x7080 to 0x100L)
    }

    private data class Access(val controller: Boolean, val read: Boolean, val offset: Int,
                              val value: Long, val savedMask: Long = 0, val external: Boolean = false)
    private val steps = buildList {
        fun writes(controller: Boolean, rows: List<Pair<Int, Long>>) {
            for ((offset, value) in rows) add(Access(controller, false, offset, value))
        }
        writes(false, program)
        writes(true, configuration.take(24))
        writes(false, setup)
        val programmed = program.toMap() + setup
        for ((offset, mask) in calibration) {
            val word = programmed.getValue(offset)
            add(Access(false, true, offset, word))
            add(Access(false, false, offset, word and mask.inv(), mask))
        }
        add(Access(true, false, 0x7040, 0x100))
        add(Access(true, true, 0x7044, 0, 0xffffffffL))
        writes(true, request)
        if (pollWords.isNotEmpty()) {
            val status = pollWords.first()
            add(Access(true, true, 0x7090, status))
            if (status and 0xff != 1L) return@buildList
            add(Access(true, false, 0x7c80, 2))
            add(Access(false, false, 0, 0, external = true))
            add(Access(true, false, 0x7c18, 0x40000000))
            for ((index, offset) in listOf(0x4670, 0x4674, 0x4678).withIndex()) {
                val value = pollWords[index + 1]
                add(Access(false, true, offset, value))
                val mask = if (index == 0) 0x30000L else 0x30003L
                if (value and mask != mask) return@buildList
            }
            if (pollWords.size == 24) addFinalization()
        }
    }

    private fun MutableList<Access>.addFinalization() {
        fun write(offset: Int, value: Long) { add(Access(true, false, offset, value)) }
        fun poll(controller: Boolean, index: Int, offset: Int, mask: Long, expected: Long): Boolean {
            val sample = pollWords[index]
            add(Access(controller, true, offset, sample))
            return sample and mask == expected
        }
        write(0x7c50, 1)
        if (!poll(true, 4, 0x7c18, 0x40000001, 1)) return
        write(0x7080, 0x200)
        if (!poll(true, 5, 0x7090, 1, 0)) return
        write(0x7c50, 0)
        if (!poll(false, 6, 0x2110, 1, 1)) return
        for ((index, offset) in listOf(0x40dc, 0x42dc, 0x40ec, 0x42ec, 0x40f8, 0x42f8).withIndex()) {
            val mask = when (index / 2) { 0 -> 0x1300L; 1 -> 0x180L; else -> 0xff0000L }
            if (!poll(false, 7 + index, offset, mask, if (index < 2) 0x300 else 0)) return
        }
        val calibrationOffsets = listOf(0x4138, 0x413c, 0x4140, 0x4144, 0x4338, 0x433c, 0x4340, 0x4344, 0x4148, 0x4348)
        for ((index, offset) in calibrationOffsets.withIndex()) {
            val sample = pollWords[13 + index]
            add(Access(false, true, offset, sample))
            if (sample and 0x7ff > 0x600 || (index < 8 && (sample ushr 16) and 0x7ff > 0x600)) break
        }
        write(0x7360, 0xff00c00d)
        if (!poll(true, 23, 0x7364, 1, 1)) return
        write(0x7364, 1)
        write(0x7c20, 0x40000520)
        write(0x7c40, 0)
        write(0x7084, 0x02010200)
    }
    var cursor = 0
        private set

    fun restore(position: Int) {
        require(position in 0..steps.size)
        cursor = position
    }

    val capacity get() = steps.size
    fun allowsCommandAt(position: Int = cursor): Boolean =
        position == steps.size && steps.last().offset == 0x7084
    fun isCalibrationOffset(offset: Int): Boolean = offset in calibrationOffsets
    fun resetForNextCycle() {
        require(repeatable && allowsCommandAt()) { "DRAM post-ACK replay cycle reset requires repeatable finished replay" }
        cursor = 0
    }

    private fun word(step: Access, bank: DramPairBank): Long =
        if (step.savedMask == 0L) step.value
        else step.value or (bank.retainedWord(step.controller, step.offset) and step.savedMask)

    fun observation(bank: DramPairBank): Map<String, Any> {
        val phy = linkedMapOf<String, Long>()
        val controller = linkedMapOf<String, Long>()
        var reads = 0
        var externalWrites = 0
        for (index in 0 until cursor) {
            val step = steps[index]
            if (step.read) reads++
            else if (step.external) externalWrites++
            else (if (step.controller) controller else phy)[step.offset.toString(16)] = word(step, bank)
        }
        return mapOf("cursor" to cursor, "capacity" to capacity, "writes" to cursor - reads, "reads" to reads,
            "phyWrites" to phy, "controllerWrites" to controller, "externalWrites" to externalWrites)
    }

    fun access(bank: DramPairBank, controller: Boolean, offset: Int, size: Int, written: Long?, tick: Long,
               external: Boolean = false): Long {
        bank.preflightClock(tick)
        val step = checkNotNull(steps.getOrNull(cursor)) { "DRAM post-ACK replay exhausted at index $cursor" }
        require(size == 4 && external == step.external && controller == step.controller && offset == step.offset && (written == null) == step.read) {
            "Unexpected DRAM post-ACK replay access at index $cursor"
        }
        val value = word(step, bank)
        require(written == null || written == value) { "DRAM post-ACK replay word differs at index $cursor" }
        if (cursor == 0 && (!repeatable || bank.retainedWord(false, 0x2000) == 0xb01L)) {
            bank.clearPhyControl(size, value, tick)
        }
        cursor++
        return value
    }
}
