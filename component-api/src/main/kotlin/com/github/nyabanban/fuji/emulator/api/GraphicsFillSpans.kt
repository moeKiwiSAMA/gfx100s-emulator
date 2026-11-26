package com.github.nyabanban.fuji.emulator.api

object GraphicsFillSpans {
    const val SETTING = "crossSurfaceFills"

    fun parse(raw: Any?, surfaces: List<LongRange>): List<LongRange> {
        require(raw is List<*> && raw.size in 1..15)
        fun address(value: Any?): Long {
            require(value is Int || value is Long)
            return (value as Number).toLong().also { require(it in 0..0x100000000L && it % 4 == 0L) }
        }
        return raw.map { row ->
            require(row is Map<*,*> && row.keys == setOf("start","end"))
            val start = address(row["start"])
            val end = address(row["end"])
            require(end-start in 4..0x300000L)
            val parts = surfaces.filter { it.first < end && start <= it.last }
            require(parts.size >= 2 && parts.sumOf { minOf(end,it.last+1) - maxOf(start,it.first) } == end-start) {
                "Fill span must cross contiguous additional surfaces"
            }
            start until end
        }.also { require(it.distinct().size == it.size) }
    }
}
