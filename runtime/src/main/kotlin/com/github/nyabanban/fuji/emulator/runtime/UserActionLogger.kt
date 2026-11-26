package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Concise `[USER/ACTION]` forensic trail logger used ONLY in user-facing `relaxed` execution mode.
 *
 * Emits at `DEBUG` level so interactive terminal output stays calm (`CONSOLE` threshold `INFO`)
 * while `logs/latest.log` (`FILE` threshold `DEBUG`) captures the exact causal input chain
 * (buttons, latched switches, eye sensor, component settings, pause/resume) leading up to any
 * unmodeled MMIO/Flash hit.
 *
 * Disabled (`enabled = false`) in `discovery` and `strict` modes.
 */
class UserActionLogger(
    private val enabled: Boolean,
    private val logger: Logger = LoggerFactory.getLogger(UserActionLogger::class.java),
    private val infoSink: ((String) -> Unit)? = null,
    private val telemetryPublisher: TelemetrySessionPublisher? = null,
) {
    fun logComponentPatch(componentId: String, changes: Map<String, Any?>, telemetry: FirmwareTelemetry) {
        if (!enabled || changes.isEmpty()) return
        val stage = FirmwareStage.highestReached(telemetry.stageMask)?.label ?: "NONE"
        val tick = telemetry.virtualTick
        val frame = telemetry.publishedFrames
        val line = when {
            componentId == "buttons" && changes.size == 1 -> {
                val entry = changes.entries.single()
                "[USER/ACTION] button=${entry.key} pressed=${entry.value} stage=$stage tick=$tick frame=$frame"
            }
            componentId == "buttons" -> {
                val joined = changes.entries.joinToString(",") { "${it.key}=${it.value}" }
                "[USER/ACTION] buttons=$joined stage=$stage tick=$tick frame=$frame"
            }
            (componentId == "i2c-bus" || componentId == "tmd2772") && changes.size == 1 && changes.containsKey("eyeSensorNear") -> {
                "[USER/ACTION] eyeSensorNear=${changes["eyeSensorNear"]} stage=$stage tick=$tick frame=$frame"
            }
            (componentId == "i2c-bus" || componentId == "rear-touch-ic") && (changes.containsKey("touchPressed") || changes.containsKey("touchX") || changes.containsKey("touchY")) -> {
                "[USER/ACTION] touch pressed=${changes["touchPressed"]} x=${changes["touchX"]} y=${changes["touchY"]} stage=$stage tick=$tick frame=$frame"
            }
            else -> {
                val joined = changes.entries.joinToString(",") { "${it.key}=${it.value}" }
                "[USER/ACTION] component=$componentId changes=$joined stage=$stage tick=$tick frame=$frame"
            }
        }
        emitInfo(line)
        telemetryPublisher?.recordUserAction(
            action = if (componentId == "buttons") "button" else "component_patch",
            componentId = componentId,
            changes = changes,
            stage = stage,
            tick = tick,
            frame = frame,
            message = line,
        )
    }

    fun logPauseState(paused: Boolean, telemetry: FirmwareTelemetry) {
        if (!enabled) return
        val stage = FirmwareStage.highestReached(telemetry.stageMask)?.label ?: "NONE"
        val tick = telemetry.virtualTick
        val frame = telemetry.publishedFrames
        val action = if (paused) "pause" else "resume"
        val line = "[USER/ACTION] action=$action stage=$stage tick=$tick frame=$frame"
        emitInfo(line)
        telemetryPublisher?.recordUserAction(
            action = action,
            componentId = null,
            changes = emptyMap(),
            stage = stage,
            tick = tick,
            frame = frame,
            message = line,
        )
    }

    fun logViewMode(pressed: Boolean, telemetry: FirmwareTelemetry) {
        if (!enabled) return
        val stage = FirmwareStage.highestReached(telemetry.stageMask)?.label ?: "NONE"
        val tick = telemetry.virtualTick
        val frame = telemetry.publishedFrames
        val line = "[USER/ACTION] viewMode pressed=$pressed gpio=7.6 stage=$stage tick=$tick frame=$frame"
        emitInfo(line)
        telemetryPublisher?.recordUserAction(
            action = "view_mode",
            componentId = "soc-gpio",
            changes = mapOf("pressed" to pressed, "bank" to 7, "pin" to 6),
            stage = stage,
            tick = tick,
            frame = frame,
            message = line,
        )
    }

    private fun emitInfo(message: String) {
        val sink = infoSink
        if (sink != null) sink(message) else logger.debug(message)
    }
}
