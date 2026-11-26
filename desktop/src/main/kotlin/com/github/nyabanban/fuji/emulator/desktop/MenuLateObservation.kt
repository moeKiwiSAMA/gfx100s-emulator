package com.github.nyabanban.fuji.emulator.desktop

/** Passive, bounded input observations after a successfully accepted LCD UNREADY write. */
internal class MenuLateObservation private constructor() {
    companion object {
        const val KEY = "menuLateObservation"
        const val PROFILE = "menu-after-lcd-unready-write-v1"
        const val LCD_PROFILE = "lcdc-instance1-unready-experimental-v1"
        const val PER_POINT_CORE_LIMIT = 32
        private const val STRIDE = 21
        private val points = longArrayOf(0x123f108,0x12cb0e0,0x123de7c,0x11f5d48,0x11dacd8,0x120bd88,
            0x123c234,0x123caf4,0x123c3e4,0x123d250,0x21fd360,0x21fd370,
            0x12cacf0,0x12cad68,0x12cad6c,0x21f85a8,0x20acb94,0x20acba4)
        fun select(raw: Any?): MenuLateObservation? {
            if (raw == null) return null
            require(raw == PROFILE) { "unknown MENU late observation profile" }
            return MenuLateObservation()
        }
    }

    /** R0..R12, SP, LR, PC, CPSR; no guest write, memory access, time or scheduling capability. */
    fun interface Registers { fun read(index: Int): Long }
    private val observed = LongArray(points.size * 4)
    private val rows = LongArray(points.size * 4 * PER_POINT_CORE_LIMIT * STRIDE)
    private var started = false
    private var startAttempt = 0L
    private var startTick = 0L
    private var startCore = 0
    private var startPc = 0L

    fun validateConfiguration(selectedPoints: Set<Long>, cluster: Boolean, nativeMmu: Boolean, lcdProfile: Any?) {
        require(cluster && nativeMmu) { "MENU late observation requires explicit cluster and native MMU" }
        require(lcdProfile == LCD_PROFILE) { "MENU late observation requires the explicit LCD UNREADY input" }
        require(points.all { it in selectedPoints }) { "MENU late observation requires all existing input scan trace points" }
    }

    /** Called only after router.write returns successfully; never called for a refused transaction. */
    fun acceptedWrite(address: Long, size: Int, value: Long, attempt: Long, tick: Long, core: Int, pc: Long) {
        if (started || address != 0xfffa9000L || size != 4 || value != 0L) return
        started = true
        startAttempt = attempt
        startTick = tick
        startCore = core
        startPc = pc
    }

    /** Called within the existing validated trace-point hook; no allocation or component-state polling. */
    fun instruction(attempt: Long, tick: Long, core: Int, pc: Long, registers: Registers) {
        if (!started) return
        val point = points.indexOf(pc)
        if (point < 0) return
        val slot = point * 4 + core
        val count = observed[slot]
        observed[slot] = Math.addExact(count, 1L)
        if (count >= PER_POINT_CORE_LIMIT) return
        val base = (slot * PER_POINT_CORE_LIMIT + count.toInt()) * STRIDE
        rows[base] = attempt; rows[base + 1] = tick; rows[base + 2] = pc; rows[base + 3] = core.toLong()
        for (index in 0..16) rows[base + 4 + index] = registers.read(index)
    }

    fun state(): Map<String, Any?> {
        val retainedRows = ArrayList<Map<String, Long>>()
        observed.indices.forEach { slot ->
            repeat(minOf(observed[slot], PER_POINT_CORE_LIMIT.toLong()).toInt()) { index ->
                val base = (slot * PER_POINT_CORE_LIMIT + index) * STRIDE
                retainedRows += linkedMapOf("attempt" to rows[base], "tick" to rows[base + 1], "address" to rows[base + 2], "core" to rows[base + 3],
                    "sp" to rows[base + 17], "lr" to rows[base + 18], "pc" to rows[base + 19], "cpsr" to rows[base + 20]) +
                    (0..12).associate { "r$it" to rows[base + 4 + it] }
            }
        }
        val counts = observed.indices.map { slot ->
            val count = observed[slot]; val retained = minOf(count, PER_POINT_CORE_LIMIT.toLong())
            mapOf("address" to points[slot / 4], "core" to slot % 4, "observed" to count, "retained" to retained, "truncated" to (count > retained))
        }
        return mapOf("profile" to PROFILE, "phase" to "before-instruction", "started" to started,
            "trigger" to if (!started) null else mapOf("attempt" to startAttempt,"tick" to startTick,"core" to startCore,"pcVirtual" to startPc,
                "physicalAddress" to 0xfffa9000L,"size" to 4,"value" to 0L),
            "tracePoints" to points.toList(), "perPointCoreLimit" to PER_POINT_CORE_LIMIT,
            "maximumRetained" to points.size * 4 * PER_POINT_CORE_LIMIT, "observed" to observed.sum(), "retained" to retainedRows.size,
            "truncated" to observed.any { it > PER_POINT_CORE_LIMIT }, "counts" to counts,
            "entries" to retainedRows.sortedWith(compareBy({ it.getValue("attempt") }, { it.getValue("tick") })),
            "scope" to "Passive first32 per point/core after successful FFFA9000 WRITE4(0), only with explicit LCD UNREADY hypothesis. Original early trace history remains separate. Function entries/arguments are not proof of completed scanning, MENU delivery or frame submission.")
    }
}
