package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.GpioOutputPort
import com.github.nyabanban.fuji.emulator.api.PanelBacklightPort
import com.github.nyabanban.fuji.emulator.api.PanelBacklightState
import kotlin.math.pow

/**
 * Real-silicon `SPI5` (`0xFFF37000`) panel IC brightness decoder verified against live GFX100S 2.13
 * `GXUP0004.DAT` transactions (`S4 0x01271154` Rear LCD, `S4 0x012730F8` EVF OLED):
 *
 * 1. **Rear LCD (`CS GPIO(13,8)`, 16-bit packed 9-bit MIPI-DBI-C `{D/C[8], byte[7:0]}`)**:
 *    Self-synchronizing 9-bit DBI-C parser (works both with explicit CS edges and when CS is driven
 *    in hardware alt-function mode):
 *    - Command `0x00`: selects command-page offset (`0x05`, `0x20`, `0x25`, ...).
 *    - Command `0xA3`: writes panel backlight PWM duty at the selected offset:
 *      - offset `0x05`: primary low/mid duty (`-5 -> 0x0A`, `0 -> 0x87`, `+1..+5 -> 0xB0`)
 *      - offset `0x20`: boost range mode (`-5..0 -> 0x81`, `+1..+5 -> 0xFF`)
 *      - offset `0x25`: high-brightness boost duty (`-5..0 -> 0x00`, `+1 -> 0x16`, `+5 -> 0x7B`)
 *    - Standard MIPI DCS `0x51` (`WRDISBV`, nominal `0xFF`) & `0x53` (`WRCTRLD`, nominal `0x24`).
 * 2. **EVF OLED (`CS GPIO(13,10)`, 8-bit LSB-first Sony ECX33x)**:
 *    Supports both CS-framed auto-increment register writes (`addr = bitrev8(tx[0])`,
 *    `regs[addr + i] = bitrev8(tx[1 + i])`) and self-synchronizing detection of the Sony ECX33x
 *    luminance/emission/RGB-gain commit block ending in latch `0x43=0x55, 0x44=0x01` (`c2 aa 80`):
 *    - `0x13`: luminance (`-5 -> 0x05`, `0 -> 0x0E`, `+1 -> 0x13`, `+5 -> 0x4D`)
 *    - `0x2C`: emission duty dimming (`-5 -> 0xD2`, `0..+5 -> 0xE0`)
 *    - `0x2D..0x2F`: R/G/B fine gains (nominal `0x80`)
 */
internal class PanelBacklightDecoder : PanelBacklightPort {
    companion object {
        const val LCD_CS_GPIO_PORT = 13
        const val LCD_CS_GPIO_PIN = 8
        const val EVF_CS_GPIO_PORT = 13
        const val EVF_CS_GPIO_PIN = 10

        const val DEFAULT_LCD_DUTY_LOW = 0x87 // 135 (LCD BRIGHTNESS = 0)
        const val MIN_USER_LCD_DUTY_LOW = 0x0A // 10 (LCD BRIGHTNESS = -5; 0x04 is power-on init before SLPOUT/DISPON)
        const val DEFAULT_LCD_BOOST_MODE = 0x81
        const val DEFAULT_LCD_DUTY_BOOST = 0x00
        const val DEFAULT_LCD_WRDISBV = 0xFF
        const val DEFAULT_LCD_WRCTRLD = 0x24

        const val DEFAULT_EVF_LUMINANCE = 0x0E // 14 (EVF BRIGHTNESS = 0)
        const val DEFAULT_EVF_EMISSION = 0xE0 // 224
        const val DEFAULT_EVF_RGB_GAIN = 0x80 // 128

        private const val TICK_GAP_FLUSH = 40_000L

        internal val BITREV8 = IntArray(256) { v ->
            var x = v
            var r = 0
            repeat(8) {
                r = (r shl 1) or (x and 1)
                x = x ushr 1
            }
            r
        }

        internal fun computeLcdGain(dutyLow: Int, dutyBoost: Int, wrdisbv: Int): Float {
            if (wrdisbv <= 0) return 0.0f
            val totalDuty = (dutyLow + dutyBoost).coerceAtLeast(1)
            if (totalDuty == DEFAULT_LCD_DUTY_LOW && wrdisbv == DEFAULT_LCD_WRDISBV) return 1.0f
            val ratio = (totalDuty.toDouble() / DEFAULT_LCD_DUTY_LOW.toDouble()) *
                (wrdisbv.toDouble() / DEFAULT_LCD_WRDISBV.toDouble())
            return ratio.pow(0.45).toFloat().coerceIn(0.15f, 1.65f)
        }

        internal fun computeEvfGain(luminance: Int, emission: Int): Float {
            if (luminance <= 0 || emission <= 0) return 0.0f
            if (luminance == DEFAULT_EVF_LUMINANCE && emission == DEFAULT_EVF_EMISSION) return 1.0f
            val ratio = (luminance.toDouble() / DEFAULT_EVF_LUMINANCE.toDouble()) *
                (emission.toDouble() / DEFAULT_EVF_EMISSION.toDouble())
            return ratio.pow(0.30).toFloat().coerceIn(0.25f, 1.65f)
        }
    }

    private var lcdDutyLow = DEFAULT_LCD_DUTY_LOW
    private var lcdBoostMode = DEFAULT_LCD_BOOST_MODE
    private var lcdDutyBoost = DEFAULT_LCD_DUTY_BOOST
    private var lcdWrdisbv = DEFAULT_LCD_WRDISBV
    private var lcdWrctrld = DEFAULT_LCD_WRCTRLD
    private var lcdPageOffset = 0
    private var lcdUpdates = 0L

    private var evfLuminance = DEFAULT_EVF_LUMINANCE
    private var evfEmission = DEFAULT_EVF_EMISSION
    private var evfGainR = DEFAULT_EVF_RGB_GAIN
    private var evfGainG = DEFAULT_EVF_RGB_GAIN
    private var evfGainB = DEFAULT_EVF_RGB_GAIN
    private var evfUpdates = 0L

    // Self-synchronizing 9-bit MIPI-DBI-C parser state (16-bit SPI5 words)
    private var lcdInData = false
    private var lcdCmd = 0
    private var lcdBitBuf = 0L
    private var lcdBitCount = 0
    private var lcdDataCount = 0
    private var lcdFirstData = 0
    private var lastLcdTick = -1L

    // EVF OLED 8-bit LSB-first buffer (supports both CS-framed writes and continuous stream latch)
    private val evfBytes = IntArray(256)
    private var evfByteLen = 0
    private val evfRecent = IntArray(16)
    private var evfRecentCount = 0
    private var lastEvfTick = -1L

    private var lcdCsSub: AutoCloseable? = null
    private var evfCsSub: AutoCloseable? = null

    @Volatile
    private var cachedState: PanelBacklightState = buildState()

    private fun buildState(): PanelBacklightState = PanelBacklightState(
        lcdDutyLow = lcdDutyLow,
        lcdBoostMode = lcdBoostMode,
        lcdDutyBoost = lcdDutyBoost,
        lcdWrdisbv = lcdWrdisbv,
        lcdWrctrld = lcdWrctrld,
        lcdGain = computeLcdGain(lcdDutyLow, lcdDutyBoost, lcdWrdisbv),
        evfLuminance = evfLuminance,
        evfEmission = evfEmission,
        evfGainR = evfGainR,
        evfGainG = evfGainG,
        evfGainB = evfGainB,
        evfGain = computeEvfGain(evfLuminance, evfEmission),
        lcdUpdates = lcdUpdates,
        evfUpdates = evfUpdates,
    )

    private fun refreshCachedState() {
        cachedState = buildState()
    }

    override fun backlightState(): PanelBacklightState = cachedState

    private var lcdCsLow = false
    private var evfCsLow = false

    fun attachGpio(port: GpioOutputPort?) {
        detachGpio()
        if (port != null) {
            lcdCsSub = port.addOutputListener(LCD_CS_GPIO_PORT, LCD_CS_GPIO_PIN) { high, _ ->
                if (high) {
                    lcdCsLow = false
                    finishLcdCommandIfAny()
                } else {
                    lcdCsLow = true
                    resetLcdCommand()
                }
            }
            evfCsSub = port.addOutputListener(EVF_CS_GPIO_PORT, EVF_CS_GPIO_PIN) { high, _ ->
                if (high) {
                    evfCsLow = false
                    flushEvfFrame()
                } else {
                    evfCsLow = true
                    evfByteLen = 0
                }
            }
        }
    }

    fun detachGpio() {
        lcdCsSub?.close()
        lcdCsSub = null
        evfCsSub?.close()
        evfCsSub = null
        lcdCsLow = false
        evfCsLow = false
    }

    private fun resetLcdCommand() {
        lcdInData = false
        lcdCmd = 0
        lcdBitBuf = 0L
        lcdBitCount = 0
        lcdDataCount = 0
        lcdFirstData = 0
    }

    private fun finishLcdCommandIfAny() {
        if (lcdInData && lcdDataCount > 0) {
            dispatchLcdCommand(lcdCmd, lcdFirstData)
        }
        resetLcdCommand()
    }

    fun onShift(bits: Int, word: Int, tick: Long) {
        if (bits == 16) {
            if (!lcdCsLow && lastLcdTick >= 0L && tick - lastLcdTick > TICK_GAP_FLUSH) {
                finishLcdCommandIfAny()
            }
            lastLcdTick = tick
            processLcdWord16(word and 0xFFFF)
        } else if (bits == 8) {
            if (!evfCsLow && lastEvfTick >= 0L && tick - lastEvfTick > TICK_GAP_FLUSH) {
                flushEvfFrame()
            }
            lastEvfTick = tick
            processEvfByte8(word and 0xFF)
        }
    }

    private fun processLcdWord16(w: Int) {
        if (!lcdInData) {
            if ((w and 0x8000) != 0) return // not a D/C=0 command word
            val cmd = (w ushr 7) and 0xFF
            if ((w and 0x0040) == 0) {
                // 0-data-byte command (e.g. 0x11 SLPOUT = 0x0880, 0x29 DISPON = 0x1480)
                return
            }
            lcdCmd = cmd
            lcdBitBuf = (w and 0x7F).toLong()
            lcdBitCount = 7
            lcdDataCount = 0
            lcdFirstData = 0
            lcdInData = true
            return
        }
        lcdBitBuf = (lcdBitBuf shl 16) or (w.toLong() and 0xFFFFL)
        lcdBitCount += 16
        var done = false
        while (lcdBitCount >= 9) {
            val shift = lcdBitCount - 9
            val tok = ((lcdBitBuf ushr shift) and 0x1FFL).toInt()
            lcdBitCount -= 9
            lcdBitBuf = lcdBitBuf and ((1L shl lcdBitCount) - 1L)
            if ((tok and 0x100) != 0) {
                if (lcdDataCount == 0) lcdFirstData = tok and 0xFF
                lcdDataCount++
            } else {
                done = true
                break
            }
        }
        if (!done && lcdBitCount > 0 && ((lcdBitBuf ushr (lcdBitCount - 1)) and 1L) == 0L) {
            // Remaining low bits begin with D/C=0 zero padding -> this DBI-C command is complete.
            done = true
        }
        if (done) {
            if (lcdDataCount > 0) {
                dispatchLcdCommand(lcdCmd, lcdFirstData)
            }
            resetLcdCommand()
        }
    }

    private fun dispatchLcdCommand(cmd: Int, d0: Int) {
        when (cmd) {
            0x00 -> lcdPageOffset = d0
            0xA3 -> {
                when (lcdPageOffset) {
                    0x05 -> {
                        // BOE Rear LCD power-on init writes 0xA3[0x05]=0x04 prior to SLPOUT/DISPON;
                        // OOBE (0x0421) and FWUP (0x0601) run at nominal 1.0x backlight without a
                        // subsequent 38-word brightness burst, whereas user LCD BRIGHTNESS -5..+5
                        // spans 0x0A..0xB0. Ignore pre-DISPON power-on init floor values (< 0x0A).
                        if (d0 >= MIN_USER_LCD_DUTY_LOW) {
                            lcdDutyLow = d0
                            lcdUpdates++
                            refreshCachedState()
                        }
                    }
                    0x20 -> {
                        lcdBoostMode = d0
                        refreshCachedState()
                    }
                    0x25 -> {
                        lcdDutyBoost = d0
                        lcdUpdates++
                        refreshCachedState()
                    }
                }
            }
            0x51 -> {
                lcdWrdisbv = d0
                lcdUpdates++
                refreshCachedState()
            }
            0x53 -> {
                lcdWrctrld = d0
                refreshCachedState()
            }
        }
    }

    private fun processEvfByte8(b: Int) {
        if (evfByteLen < evfBytes.size) {
            evfBytes[evfByteLen++] = b
        }
        // Sliding 16-byte window to detect Sony ECX33x commit sequence even if CS is hardware alt-func:
        // [0xC8 (reg 0x13), lum, 0xD4 (reg 0x2B), _, 0x34 (reg 0x2C), emit, 0xB4 (reg 0x2D), r, g, b, 0xC2, 0xAA, 0x80]
        if (evfRecentCount < evfRecent.size) {
            evfRecent[evfRecentCount++] = b
        } else {
            System.arraycopy(evfRecent, 1, evfRecent, 0, evfRecent.size - 1)
            evfRecent[evfRecent.size - 1] = b
        }
        if (evfRecentCount >= 13) {
            val base = evfRecentCount - 13
            if (evfRecent[base] == 0xC8 &&
                evfRecent[base + 2] == 0xD4 &&
                evfRecent[base + 4] == 0x34 &&
                evfRecent[base + 6] == 0xB4 &&
                evfRecent[base + 10] == 0xC2 &&
                evfRecent[base + 11] == 0xAA &&
                evfRecent[base + 12] == 0x80
            ) {
                evfLuminance = BITREV8[evfRecent[base + 1]]
                evfEmission = BITREV8[evfRecent[base + 5]]
                evfGainR = BITREV8[evfRecent[base + 7]]
                evfGainG = BITREV8[evfRecent[base + 8]]
                evfGainB = BITREV8[evfRecent[base + 9]]
                evfUpdates++
                evfByteLen = 0
                refreshCachedState()
            }
        }
    }

    private fun flushEvfFrame() {
        val count = evfByteLen
        evfByteLen = 0
        if (count < 2) return
        val startReg = BITREV8[evfBytes[0]]
        // Guard against stray multi-command concatenated streams without CS edges:
        // only decode as a single auto-increment transaction when short (<=5 bytes) OR full bank dump starting at 0x00.
        if (count > 5 && startReg != 0x00) return
        var updated = false
        for (i in 1 until count) {
            val reg = startReg + (i - 1)
            val value = BITREV8[evfBytes[i]]
            when (reg) {
                0x13 -> {
                    evfLuminance = value
                    updated = true
                }
                0x2C -> {
                    evfEmission = value
                    updated = true
                }
                0x2D -> {
                    evfGainR = value
                    updated = true
                }
                0x2E -> {
                    evfGainG = value
                    updated = true
                }
                0x2F -> {
                    evfGainB = value
                    updated = true
                }
            }
        }
        if (updated) {
            evfUpdates++
            refreshCachedState()
        }
    }

    fun isInitial(): Boolean =
        lcdDutyLow == DEFAULT_LCD_DUTY_LOW &&
            lcdBoostMode == DEFAULT_LCD_BOOST_MODE &&
            lcdDutyBoost == DEFAULT_LCD_DUTY_BOOST &&
            lcdWrdisbv == DEFAULT_LCD_WRDISBV &&
            lcdWrctrld == DEFAULT_LCD_WRCTRLD &&
            evfLuminance == DEFAULT_EVF_LUMINANCE &&
            evfEmission == DEFAULT_EVF_EMISSION &&
            evfGainR == DEFAULT_EVF_RGB_GAIN &&
            evfGainG == DEFAULT_EVF_RGB_GAIN &&
            evfGainB == DEFAULT_EVF_RGB_GAIN &&
            lcdUpdates == 0L &&
            evfUpdates == 0L

    fun stateMap(): Map<String, Any> = linkedMapOf(
        "lcdDutyLow" to lcdDutyLow,
        "lcdBoostMode" to lcdBoostMode,
        "lcdDutyBoost" to lcdDutyBoost,
        "lcdWrdisbv" to lcdWrdisbv,
        "lcdWrctrld" to lcdWrctrld,
        "lcdGain" to cachedState.lcdGain.toDouble(),
        "evfLuminance" to evfLuminance,
        "evfEmission" to evfEmission,
        "evfGainR" to evfGainR,
        "evfGainG" to evfGainG,
        "evfGainB" to evfGainB,
        "evfGain" to cachedState.evfGain.toDouble(),
        "lcdUpdates" to lcdUpdates,
        "evfUpdates" to evfUpdates,
    )

    data class Snapshot(
        val lcdDutyLow: Int,
        val lcdBoostMode: Int,
        val lcdDutyBoost: Int,
        val lcdWrdisbv: Int,
        val lcdWrctrld: Int,
        val lcdPageOffset: Int,
        val lcdUpdates: Long,
        val evfLuminance: Int,
        val evfEmission: Int,
        val evfGainR: Int,
        val evfGainG: Int,
        val evfGainB: Int,
        val evfUpdates: Long,
    )

    fun snapshot(): Map<String, Any> = linkedMapOf(
        "lcdDutyLow" to lcdDutyLow,
        "lcdBoostMode" to lcdBoostMode,
        "lcdDutyBoost" to lcdDutyBoost,
        "lcdWrdisbv" to lcdWrdisbv,
        "lcdWrctrld" to lcdWrctrld,
        "lcdPageOffset" to lcdPageOffset,
        "lcdUpdates" to lcdUpdates,
        "evfLuminance" to evfLuminance,
        "evfEmission" to evfEmission,
        "evfGainR" to evfGainR,
        "evfGainG" to evfGainG,
        "evfGainB" to evfGainB,
        "evfUpdates" to evfUpdates,
    )

    fun parse(raw: Any?): Snapshot {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("Invalid panelBacklight snapshot")
        fun u8(key: String): Int = ((m[key] as? Number)?.toInt()
            ?: throw IllegalArgumentException("Missing $key in panelBacklight snapshot")).also {
            require(it in 0..255) { "Out-of-range $key in panelBacklight snapshot" }
        }
        fun u64(key: String): Long = ((m[key] as? Number)?.toLong()
            ?: throw IllegalArgumentException("Missing $key in panelBacklight snapshot")).also {
            require(it >= 0L) { "Negative $key in panelBacklight snapshot" }
        }
        return Snapshot(
            lcdDutyLow = u8("lcdDutyLow"),
            lcdBoostMode = u8("lcdBoostMode"),
            lcdDutyBoost = u8("lcdDutyBoost"),
            lcdWrdisbv = u8("lcdWrdisbv"),
            lcdWrctrld = u8("lcdWrctrld"),
            lcdPageOffset = u8("lcdPageOffset"),
            lcdUpdates = u64("lcdUpdates"),
            evfLuminance = u8("evfLuminance"),
            evfEmission = u8("evfEmission"),
            evfGainR = u8("evfGainR"),
            evfGainG = u8("evfGainG"),
            evfGainB = u8("evfGainB"),
            evfUpdates = u64("evfUpdates"),
        )
    }

    fun restore(saved: Snapshot?) {
        resetLcdCommand()
        lcdCsLow = false
        evfCsLow = false
        evfByteLen = 0
        evfRecentCount = 0
        lastLcdTick = -1L
        lastEvfTick = -1L
        if (saved == null) {
            lcdDutyLow = DEFAULT_LCD_DUTY_LOW
            lcdBoostMode = DEFAULT_LCD_BOOST_MODE
            lcdDutyBoost = DEFAULT_LCD_DUTY_BOOST
            lcdWrdisbv = DEFAULT_LCD_WRDISBV
            lcdWrctrld = DEFAULT_LCD_WRCTRLD
            lcdPageOffset = 0
            lcdUpdates = 0L
            evfLuminance = DEFAULT_EVF_LUMINANCE
            evfEmission = DEFAULT_EVF_EMISSION
            evfGainR = DEFAULT_EVF_RGB_GAIN
            evfGainG = DEFAULT_EVF_RGB_GAIN
            evfGainB = DEFAULT_EVF_RGB_GAIN
            evfUpdates = 0L
        } else {
            lcdDutyLow = saved.lcdDutyLow
            lcdBoostMode = saved.lcdBoostMode
            lcdDutyBoost = saved.lcdDutyBoost
            lcdWrdisbv = saved.lcdWrdisbv
            lcdWrctrld = saved.lcdWrctrld
            lcdPageOffset = saved.lcdPageOffset
            lcdUpdates = saved.lcdUpdates
            evfLuminance = saved.evfLuminance
            evfEmission = saved.evfEmission
            evfGainR = saved.evfGainR
            evfGainG = saved.evfGainG
            evfGainB = saved.evfGainB
            evfUpdates = saved.evfUpdates
        }
        refreshCachedState()
    }
}
