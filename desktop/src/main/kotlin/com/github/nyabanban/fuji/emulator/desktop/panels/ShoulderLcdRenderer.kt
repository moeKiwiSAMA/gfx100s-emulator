package com.github.nyabanban.fuji.emulator.desktop.panels

/**
 * Renders raw binary `display.shoulder` (`0xFFFFFFFF` / `0xFF000000`) frames with an
 * authentic Sharp Memory-in-Pixel reflective monochrome LCD appearance when unlit
 * (`backlightOn = false`, default on boot) and a crisp cool-white LED backlit LCD
 * appearance when the real firmware activates the shoulder backlight (`backlightOn = true`).
 *
 * Raw [com.github.nyabanban.fuji.emulator.api.DisplayPort] pixels in `shoulder-display`
 * remain untouched (`0xFFFFFFFF` / `0xFF000000`) so deterministic snapshot round-trips
 * and DotLCD verification tests stay bit-exact.
 */
object ShoulderLcdRenderer {
    /** Crisp matte ambient silver-sage reflective Sharp MIP LCD reflector (unlit default, ~11.6:1 contrast). */
    const val REFLECTIVE_LIGHT_ARGB: Int = 0xFFCCD6CE.toInt()

    /** Deep obsidian liquid-crystal ink on unlit reflective monochrome Sharp MIP LCD (legible in black-bg & white-bg). */
    const val REFLECTIVE_DARK_ARGB: Int = 0xFF101513.toInt()

    /** Bright cool-white LED backlight illumination when firmware backlight GPIO(8,9) is HIGH. */
    const val BACKLIT_LIGHT_ARGB: Int = 0xFFF0F7FF.toInt()

    /** Deep high-contrast liquid-crystal ink when firmware backlight GPIO(8,9) is HIGH. */
    const val BACKLIT_DARK_ARGB: Int = 0xFF0A0F15.toInt()

    const val BACKLIT_STYLE_CLASS: String = "studio-shoulder-backlit"
    const val BASE_STYLE_CLASS: String = "studio-shoulder-lcd"

    /**
     * Tone-maps raw shoulder ARGB pixels into [output] using either the unlit reflective
     * monochrome palette (`backlightOn = false`) or the illuminated LED backlit palette
     * (`backlightOn = true`). Supports both binary (`0xFFFFFFFF`/`0xFF000000`) and
     * arbitrary grayscale inputs with zero heap allocations.
     */
    fun toneMap(
        rawPixels: IntArray,
        output: IntArray,
        backlightOn: Boolean,
        count: Int = rawPixels.size,
    ) {
        require(count in 0..rawPixels.size && count <= output.size) {
            "Invalid shoulder pixel count $count (raw=${rawPixels.size}, out=${output.size})"
        }
        val light = if (backlightOn) BACKLIT_LIGHT_ARGB else REFLECTIVE_LIGHT_ARGB
        val dark = if (backlightOn) BACKLIT_DARK_ARGB else REFLECTIVE_DARK_ARGB
        val lightR = (light ushr 16) and 0xFF
        val lightG = (light ushr 8) and 0xFF
        val lightB = light and 0xFF
        val darkR = (dark ushr 16) and 0xFF
        val darkG = (dark ushr 8) and 0xFF
        val darkB = dark and 0xFF
        val diffR = lightR - darkR
        val diffG = lightG - darkG
        val diffB = lightB - darkB

        for (i in 0 until count) {
            val px = rawPixels[i]
            output[i] = when (px) {
                0xFFFFFFFF.toInt() -> light
                0xFF000000.toInt() -> dark
                else -> {
                    val r = (px ushr 16) and 0xFF
                    val g = (px ushr 8) and 0xFF
                    val b = px and 0xFF
                    val luma = (r * 77 + g * 150 + b * 29) ushr 8
                    val outR = darkR + div255Rounded(diffR * luma)
                    val outG = darkG + div255Rounded(diffG * luma)
                    val outB = darkB + div255Rounded(diffB * luma)
                    (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }

    /**
     * Extracts live firmware-driven shoulder backlight state (`backlightOn`) from a
     * runtime state snapshot. Reads `shoulder-display.state.backlightOn` (backed by
     * DAT-verified `soc-gpio` Bank 8 Pin 9, `0xFFF41200`, active-HIGH), falling back
     * directly to `soc-gpio.state.registers[8][0]` bit 9 if present.
     * Always defaults to `false` (unlit reflective LCD) on boot / when absent.
     */
    fun extractBacklightState(componentsById: Map<Any?, Map<String, Any?>>): Boolean {
        val shoulderState = componentsById["shoulder-display"]?.get("state") as? Map<*, *>
        val direct = shoulderState?.get("backlightOn") as? Boolean
        if (direct != null) return direct
        val gpioState = componentsById["soc-gpio"]?.get("state") as? Map<*, *>
        val registers = gpioState?.get("registers") as? List<*>
        val bank8 = registers?.getOrNull(8) as? List<*>
        val outputData = (bank8?.getOrNull(0) as? Number)?.toLong() ?: return false
        return ((outputData ushr 9) and 1L) != 0L
    }
}
