package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Explicit opt-in ISP Timing-Generator auto-sync model (`isp-tg-auto-sync-v1`, `HYPOTHESIS`).
 *
 * Observes authentic guest Core 1 MMIO writes (`0xFFF6910C` -> IRQ 296, `0xFFF6C140` -> IRQ 102,
 * `0xFFF6C184` -> VSYNC TG IRQ 100 / ISP frame IRQ 106 / live-view IRQ 108) and GIC line enable
 * state, scheduling the corresponding SP-X4 ISP hardware interrupts without modifying any guest
 * CPU state or RAM. Strictly refused in `strict` mode.
 */
internal class IspAutoSync private constructor(
    val profile: String = PROFILE,
    private var irq296DueTick: Long? = null,
    private var irq102DueTick: Long? = null,
    private var tgRunning: Boolean = false,
    private var tgCycleCount: Int = 0,
    private var irq106DeliveredInTg: Int = 0,
    private var irq100DueTick: Long? = null,
    private var irq106DueTick: Long? = null,
    private var irq108DueTick: Long? = null,
    private var irq296Count: Long = 0L,
    private var irq102Count: Long = 0L,
    private var irq100Count: Long = 0L,
    private var irq106Count: Long = 0L,
    private var irq108Count: Long = 0L,
    private var restoreProbePending: Boolean = false,
) {
    constructor(profile: String = PROFILE) : this(profile = profile, restoreProbePending = false) {
        require(profile == PROFILE) { "Unknown $KEY selection: $profile" }
    }

    fun markRestoreProbeNeeded() {
        restoreProbePending = true
    }

    fun observeWrite(address: Long, value: Long, tick: Long) {
        when (address) {
            0xfff6910cL -> {
                if ((value and 2L) != 0L) {
                    irq296DueTick = tick + TRIGGER_DELAY_TICKS
                } else {
                    irq296DueTick = null
                }
            }
            0xfff6c140L -> {
                if ((value and 3L) != 0L) {
                    irq102DueTick = tick + TRIGGER_DELAY_TICKS
                } else {
                    irq102DueTick = null
                }
            }
            0xfff6c184L -> {
                if ((value and 1L) != 0L) {
                    tgRunning = true
                    tgCycleCount = 0
                    irq106DeliveredInTg = 0
                    irq100DueTick = tick + TG_START_DELAY_TICKS
                    irq106DueTick = null
                    irq108DueTick = null
                } else {
                    tgRunning = false
                    irq100DueTick = null
                    irq106DueTick = null
                    irq108DueTick = null
                }
            }
        }
    }

    fun hasRestoreProbePending(): Boolean = restoreProbePending

    fun nextDeadlinePrimitive(): Long {
        var earliest = Long.MAX_VALUE
        irq296DueTick?.let { if (it < earliest) earliest = it }
        irq102DueTick?.let { if (it < earliest) earliest = it }
        irq100DueTick?.let { if (it < earliest) earliest = it }
        irq106DueTick?.let { if (it < earliest) earliest = it }
        irq108DueTick?.let { if (it < earliest) earliest = it }
        return earliest
    }

    fun nextDeadline(): Long? = nextDeadlinePrimitive().takeIf { it != Long.MAX_VALUE }

    fun beforeInstruction(
        tick: Long,
        port: EnabledEdgeInterruptSink?,
        dmaMemory: DmaMemory?,
        isIrq296ArmedInBank: () -> Boolean,
        hasActiveIrq296Event: () -> Boolean,
        startIrq296Event: (() -> Unit) -> Unit,
        canPulseIrq106: () -> Boolean,
        isIrq106LineActive: () -> Boolean,
        pulseIrq106: (CoupledEdgeInterruptSink, Long) -> Unit,
    ) {
        if (port == null) return
        if (restoreProbePending) {
            restoreProbePending = false
            if (irq296DueTick == null && !hasActiveIrq296Event() && isIrq296ArmedInBank() && port.lineEnabled(296)) {
                irq296DueTick = tick + TRIGGER_DELAY_TICKS
            }
        }

        val due296 = irq296DueTick
        if (due296 != null && tick >= due296) {
            irq296DueTick = null
            if (!hasActiveIrq296Event() && isIrq296ArmedInBank() && port.lineEnabled(296)) {
                val st = port.edgeState(296)
                if (!st.high && !st.pending && !st.edge) {
                    startIrq296Event { port.setLine(296, false) }
                    port.setLine(296, true)
                    irq296Count++
                }
            }
        }

        val due102 = irq102DueTick
        if (due102 != null && tick >= due102) {
            irq102DueTick = null
            if (port.lineEnabled(102)) {
                val st = port.edgeState(102)
                if (!st.high && !st.pending && st.edge) {
                    port.setLine(102, true)
                    port.setLine(102, false)
                    irq102Count++
                }
            }
        }

        val due100 = irq100DueTick
        if (tgRunning && due100 != null && tick >= due100) {
            if (port.lineEnabled(100)) {
                val st = port.edgeState(100)
                if (!st.high && !st.pending && st.edge) {
                    port.setLine(100, true)
                    port.setLine(100, false)
                    irq100Count++
                }
            }
            tgCycleCount++
            irq106DueTick = tick + IRQ106_OFFSET_TICKS
            irq108DueTick = tick + IRQ108_OFFSET_TICKS
            irq100DueTick = if (tgCycleCount < MAX_TG_SYNC_CYCLES) tick + VSYNC_PERIOD_TICKS else null
        }

        val due106 = irq106DueTick
        if (tgRunning && due106 != null && tick >= due106) {
            irq106DueTick = null
            val coupled = port as? CoupledEdgeInterruptSink
            if (coupled != null && canPulseIrq106() && port.lineEnabled(106) && !isIrq106LineActive() &&
                isIrq106HandlerRegistered(dmaMemory)
            ) {
                val st = port.edgeState(106)
                if (!st.high && !st.pending && st.edge) {
                    val mask = if (irq106DeliveredInTg == 0) 1L else 0x41L
                    pulseIrq106(coupled, mask)
                    irq106DeliveredInTg++
                    irq106Count++
                }
            }
        }

        val due108 = irq108DueTick
        if (tgRunning && due108 != null && tick >= due108) {
            irq108DueTick = null
            if (port.lineEnabled(108)) {
                val st = port.edgeState(108)
                if (!st.high && !st.pending && st.edge) {
                    port.setLine(108, true)
                    port.setLine(108, false)
                    irq108Count++
                }
            }
        }
    }

    private fun isIrq106HandlerRegistered(dmaMemory: DmaMemory?): Boolean {
        if (dmaMemory == null) return true
        return try {
            dmaMemory.check(IRQ106_HANDLER_SLOT_ADDR, 4L, false)
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            dmaMemory.read(IRQ106_HANDLER_SLOT_ADDR, buf)
            buf.getInt(0) == IRQ106_HANDLER_EXPECTED
        } catch (_: RuntimeException) {
            false
        }
    }

    fun receipt(): Map<String, Any?> = linkedMapOf(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "tgRunning" to tgRunning,
        "tgCycleCount" to tgCycleCount,
        "irq296Count" to irq296Count,
        "irq102Count" to irq102Count,
        "irq100Count" to irq100Count,
        "irq106Count" to irq106Count,
        "irq108Count" to irq108Count,
    )

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "profile" to profile,
        "confidence" to "HYPOTHESIS",
        "irq296DueTick" to irq296DueTick,
        "irq102DueTick" to irq102DueTick,
        "tgRunning" to tgRunning,
        "tgCycleCount" to tgCycleCount,
        "irq106DeliveredInTg" to irq106DeliveredInTg,
        "irq100DueTick" to irq100DueTick,
        "irq106DueTick" to irq106DueTick,
        "irq108DueTick" to irq108DueTick,
        "irq296Count" to irq296Count,
        "irq102Count" to irq102Count,
        "irq100Count" to irq100Count,
        "irq106Count" to irq106Count,
        "irq108Count" to irq108Count,
    )

    companion object {
        const val KEY = "ispAutoSync"
        const val PROFILE = "isp-tg-auto-sync-v1"
        private const val TRIGGER_DELAY_TICKS = 4096L
        private const val TG_START_DELAY_TICKS = 8192L
        private const val IRQ106_OFFSET_TICKS = 250_000L
        private const val IRQ108_OFFSET_TICKS = 750_000L
        private const val VSYNC_PERIOD_TICKS = 2_000_000L
        private const val MAX_TG_SYNC_CYCLES = 12
        private const val IRQ106_HANDLER_SLOT_ADDR = 0x01871ad0L
        private const val IRQ106_HANDLER_EXPECTED = 0x011b7f14

        fun restore(raw: Any?, targetProfile: String): IspAutoSync {
            val map = raw as? Map<*, *> ?: throw IllegalArgumentException("Missing $KEY snapshot state")
            require(map["profile"] == targetProfile && map["confidence"] == "HYPOTHESIS") {
                "Invalid $KEY snapshot header"
            }
            fun optTick(k: String): Long? = map[k]?.let {
                require(it is Int || it is Long) { "Invalid $KEY $k" }
                (it as Number).toLong().also { v -> require(v >= 0L) }
            }
            fun reqLong(k: String): Long {
                val v = map[k]
                require(v is Int || v is Long) { "Invalid $KEY $k" }
                return (v as Number).toLong().also { require(it >= 0L) }
            }
            fun reqInt(k: String): Int = Math.toIntExact(reqLong(k))
            val tgRunning = map["tgRunning"] as? Boolean ?: throw IllegalArgumentException("Invalid $KEY tgRunning")
            return IspAutoSync(
                profile = targetProfile,
                irq296DueTick = optTick("irq296DueTick"),
                irq102DueTick = optTick("irq102DueTick"),
                tgRunning = tgRunning,
                tgCycleCount = reqInt("tgCycleCount"),
                irq106DeliveredInTg = reqInt("irq106DeliveredInTg"),
                irq100DueTick = optTick("irq100DueTick"),
                irq106DueTick = optTick("irq106DueTick"),
                irq108DueTick = optTick("irq108DueTick"),
                irq296Count = reqLong("irq296Count"),
                irq102Count = reqLong("irq102Count"),
                irq100Count = reqLong("irq100Count"),
                irq106Count = reqLong("irq106Count"),
                irq108Count = reqLong("irq108Count"),
                restoreProbePending = false,
            )
        }
    }
}
