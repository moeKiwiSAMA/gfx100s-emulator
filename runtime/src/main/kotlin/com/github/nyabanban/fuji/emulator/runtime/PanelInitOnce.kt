package com.github.nyabanban.fuji.emulator.runtime

internal object PanelInitOnce {
    const val REGION = "gfx100s.panel.init-words.candidate"
    const val BASE = 0xfffab000L
    const val MASK = 0x001f0fffL
    const val CYCLE_LENGTH = 11
    private fun controls(released: Long) = setOf(released, released and 0xfefffbe0, released and 0xfefff41f,
        released and 0xfefff000, (released and 0xffe0ffff) or 0x01000000)
    private val firstControls = controls(MASK)
    private val secondControls = firstControls.flatMap { controls(it or MASK) }.toSet()

    fun access(position: Int, offset: Int, write: Boolean, value: Long, control: Long, twice: Boolean): Long? {
        if (position !in 0 until CYCLE_LENGTH * (if (twice) 2 else 1)) return null
        val initial = if (position < CYCLE_LENGTH) 0L else control or MASK
        return when (val phase = position % CYCLE_LENGTH) {
            0 -> initial.takeIf { !write && offset == 4 }
            1 -> value.takeIf { write && offset == 4 && value in controls(initial or MASK) }
            2, 3 -> value.takeIf { write && offset == phase * 4 }
            4 -> value.takeIf { write && offset == 16 && value <= 0xffffff }
            5 -> value.takeIf { write && offset == 20 && value <= 0xffff }
            6 -> 0L.takeIf { write && offset == 0 && value == 0L }
            7, 8 -> (phase - 7).toLong().takeIf { !write && offset == 0 }
            9 -> control.takeIf { !write && offset == 4 }
            10 -> value.takeIf { write && offset == 4 && value == (control or MASK) }
            else -> null
        }
    }

    fun validState(position: Int, control: Long, twice: Boolean) = when (position) {
        in 0..1 -> control == 0L
        in 2..CYCLE_LENGTH -> control in firstControls
        CYCLE_LENGTH + 1 -> twice && control in firstControls
        in CYCLE_LENGTH + 2..2 * CYCLE_LENGTH -> twice && control in secondControls
        else -> false
    }
}
