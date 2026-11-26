package com.github.nyabanban.fuji.emulator.desktop

/** Explicit finite diagnostic ceiling. It never changes guest clocks, PCs or device completion. */
object InstructionBudget {
    const val MAX=300_000_000L
    const val CONTINUATION_MAX=8_000_000_000L
    const val UNLIMITED=Long.MAX_VALUE
    const val UNLIMITED_TOKEN="unlimited"
    fun parse(value: Any?): Long {
        require(value is Byte||value is Short||value is Int||value is Long){"instructionBudget requires integer attempts"}
        return (value as Number).toLong().also{require(it in 1..MAX){"instructionBudget outside 1.."+MAX+" attempts"}}
    }
    fun parse(value: Any?, relaxed: Boolean): Long {
        if (!relaxed) return parse(value)
        if (value == null) return UNLIMITED
        if (value is String && value.equals(UNLIMITED_TOKEN, ignoreCase = true)) return UNLIMITED
        require(value is Byte||value is Short||value is Int||value is Long){"instructionBudget requires integer attempts or '$UNLIMITED_TOKEN' in relaxed mode"}
        val parsed = (value as Number).toLong()
        if (parsed == 0L) return UNLIMITED
        require(parsed in 1L until UNLIMITED) { "instructionBudget outside 0..${UNLIMITED - 1} attempts in relaxed mode" }
        return parsed
    }
    fun parseContinuation(value: Any?): Long {
        require(value is Byte||value is Short||value is Int||value is Long){"continuation budget requires integer attempts"}
        return (value as Number).toLong().also{require(it in 1..CONTINUATION_MAX){"continuation budget outside 1.."+CONTINUATION_MAX+" attempts"}}
    }
}
