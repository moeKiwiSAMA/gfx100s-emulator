package com.github.nyabanban.fuji.emulator.components.vglib

/**
 * Exact divisionless integer `/ 255` rounding helpers for alpha-blend hot paths.
 *
 * Uses the classic Blinn identity `((x + 128) + ((x + 128) ushr 8)) ushr 8`,
 * which is bit-identical to `(x + 127) / 255` for every `x` in `0..65025`
 * (the full range of `c * a` and `c1 * a + c2 * (255 - a)` for 8-bit channels).
 */
internal fun div255Rounded(x: Int): Int {
    val t = x + 128
    return (t + (t ushr 8)) ushr 8
}
