package com.github.nyabanban.fuji.emulator.api

/**
 * Typed AXI External Data Abort raised when guest code accesses a power-gated or clock-gated
 * SoC peripheral under `"abort"` hardware-strict policy (e.g., `LCDC0` `0xFFF7A000..0xFFF7AFFF`
 * while EVF is off, or retail `UART0` `0xFFF3A000` while UART clock is gated).
 *
 * Subclasses [IllegalStateException] so existing MMIO refusal handlers remain compatible while
 * callers can inspect exact ARMv7 short-descriptor `DFSR` (`0x008` read / `0x808` write).
 */
class PeripheralBusAbortException(
    val peripheralId: String,
    val offset: Long,
    val write: Boolean,
    val dfsr: Long = if (write) 0x808L else 0x008L,
    message: String,
) : IllegalStateException(message)

/** Power/clock-gating snapshot for an LCD controller (e.g., EVF `LCDC0` at `0xFFF7A000`). */
data class LcdcPowerGateState(
    val instance: Int,
    val powered: Boolean,
    val clocked: Boolean,
    val policy: String,
    val unclockedAccessCount: Long,
    val lastUnclockedOffset: Long?,
    val lastUnclockedWrite: Boolean?,
)

/** Control and observation port for `LCDC0` (EVF) power/clock gating (`soc-vglib-init.lcdc0-power`). */
interface LcdcPowerGatePort {
    fun setLcdc0PowerClock(powered: Boolean, clocked: Boolean = powered)
    fun lcdc0PowerState(): LcdcPowerGateState
}

/** Clock-gating snapshot for retail `UART0` (`0xFFF3A000`). */
data class UartClockGateState(
    val clocked: Boolean,
    val initiallyClocked: Boolean,
    val policy: String,
    val unclockedTxAttempts: Long,
    val unclockedReadAttempts: Long,
)

/** Control and observation port for `UART0` clock gating (`soc-uart.clock-gate`). */
interface UartClockGatePort {
    fun setClocked(enabled: Boolean)
    fun clockGateState(): UartClockGateState
}

/** Per-slot hot-plug and async mount readiness status for SD slots (1 = Slot 0 `A:\`, 2 = Slot 1 `B:\`). */
data class SdSlotHotPlugStatus(
    val slot: Int,
    val cardDetectAsserted: Boolean,
    val mediaReady: Boolean,
    val insertedAtTick: Long?,
    val readyAtTick: Long?,
    val earlyAccessAttempts: Long,
)

/**
 * Runtime SD hot-insert/eject control port (`sdcard-reader.hotplug`).
 *
 * Models real GFX100S behavior where `CARD_DET` GPIO (`10.4` for Slot 1 / `slot0`, `10.8` for
 * Slot 2 / `slot1`, active-LOW) asserts immediately upon card insertion (`t = 0`) while card media
 * readiness / async firmware mount completes after `cardInsertReadyDelayTicks`.
 */
interface HotPlugCardControlPort {
    /**
     * Hot-insert the card in [slot] (1 or 2). Asserts active-LOW `CARD_DET` GPIO immediately at
     * current tick and arms media readiness at `now + delay` (negative [readyDelayTicks] uses the
     * configured `cardInsertReadyDelayTicks`).
     */
    fun insertCard(slot: Int, readyDelayTicks: Long = -1L): SdSlotHotPlugStatus

    /** Hot-eject the card in [slot] (1 or 2), de-asserting `CARD_DET` GPIO HIGH immediately. */
    fun ejectCard(slot: Int): SdSlotHotPlugStatus

    /** Query current hot-plug and mount-readiness state for [slot] (1 or 2). */
    fun slotHotPlugStatus(slot: Int): SdSlotHotPlugStatus

    /** True iff [slot] (1 or 2) is inserted AND current tick >= `readyAtTick`. */
    fun isMediaReady(slot: Int): Boolean
}

/**
 * Hardware-fidelity snapshot of `LCDC1` (`0xFFFA9000`) layer-enable (`+0x008`), OSD0 control
 * (`+0x030`), and active-output gate (`+0x008` bit 12 `0x00001000`) state.
 */
data class LcdcLayerOutputState(
    val reg008: Long,
    val reg030: Long,
    val activeOutputGate: Boolean,
    val osd0Enabled: Boolean,
    val pic0Enabled: Boolean,
    val pic1Enabled: Boolean,
    val osd0Mode: String,
    val outputActive: Boolean,
    val blankReason: String?,
    val activeGateBlankCount: Long,
)

/** Observation port for `LCDC1` layer-enable & active output gate state (`soc-vglib-init.lcdc1-layers`). */
interface LcdcLayerObservabilityPort {
    fun lcdc1LayerState(): LcdcLayerOutputState
}

/**
 * Hardware-fidelity audit snapshot of `soc-timer` (`0xFFF3C300`) per-channel write-mask (`bits[23:16]`)
 * and `Ch6` (display/backlight IRQ 63) vs `Ch7` (ThreadX 10 ms tick IRQ 64) state.
 */
data class TimerChannelAuditState(
    val enabledMask: Int,
    val ch6DisplayTimerActive: Boolean,
    val ch7OsTickTimerActive: Boolean,
    val lastControlWriteMask: Int,
    val lastControlWriteEnableBits: Int,
    val ch6DisableCount: Long,
    val blindMultiChannelDisableCount: Long,
    val perChannelDisableCounts: List<Long>,
)

/** Observation port for `soc-timer` per-channel write-mask & `Ch6` display timer state (`soc-timer.channels`). */
interface TimerChannelObservabilityPort {
    fun channelAuditState(): TimerChannelAuditState
}

/**
 * Real-silicon external display panel backlight/luminance state decoded from `SPI5` (`0xFFF37000`):
 * - Rear LCD (`CS GPIO(13,8)`, 16-bit packed 9-bit MIPI-DBI-C): Orise/BOE backlight PWM registers
 *   `0x00` offset `0x05` (`0xA3[0]` low-range duty), `0x20` (`0xA3[0]` boost mode), `0x25`
 *   (`0xA3[0]` high-boost duty), and standard MIPI DCS `0x51` (`WRDISBV`) / `0x53` (`WRCTRLD`).
 * - EVF OLED (`CS GPIO(13,10)`, 8-bit LSB-first Sony ECX33x): luminance `0x13`, emission duty
 *   `0x2C`, and RGB channel gains `0x2D..0x2F`.
 */
data class PanelBacklightState(
    val lcdDutyLow: Int,
    val lcdBoostMode: Int,
    val lcdDutyBoost: Int,
    val lcdWrdisbv: Int,
    val lcdWrctrld: Int,
    val lcdGain: Float,
    val evfLuminance: Int,
    val evfEmission: Int,
    val evfGainR: Int,
    val evfGainG: Int,
    val evfGainB: Int,
    val evfGain: Float,
    val lcdUpdates: Long,
    val evfUpdates: Long,
)

/** Observation port for `SPI5` external LCD/EVF panel backlight brightness (`soc-spi.panel-backlight`). */
interface PanelBacklightPort {
    companion object {
        const val PORT_NAME = "soc-spi.panel-backlight"
    }

    fun backlightState(): PanelBacklightState
}
