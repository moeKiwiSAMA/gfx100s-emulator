package com.github.nyabanban.fuji.emulator.desktop.panels

import kotlin.math.roundToInt

/**
 * Native 16-bit touch coordinate sent to the virtual `I2C2 @ 0x55` touchscreen controller.
 */
data class TouchPointCoord(val x: Int, val y: Int)

/**
 * Pure viewport-to-touch coordinate mapper for the rear LCD (`rear-lcd`).
 *
 * Accounts for JavaFX `ImageView(isPreserveRatio = true)` letterboxing inside the display
 * hotspot rectangle and clamps pointer coordinates cleanly into `[0, touchWidth - 1]` x
 * `[0, touchHeight - 1]`.
 */
object TouchCoordinateMapper {
    const val DEFAULT_TOUCH_WIDTH: Int = 1024
    const val DEFAULT_TOUCH_HEIGHT: Int = 768

    fun mapViewportToTouch(
        localX: Double,
        localY: Double,
        viewportWidth: Double,
        viewportHeight: Double,
        contentWidth: Int = DEFAULT_TOUCH_WIDTH,
        contentHeight: Int = DEFAULT_TOUCH_HEIGHT,
        touchWidth: Int = DEFAULT_TOUCH_WIDTH,
        touchHeight: Int = DEFAULT_TOUCH_HEIGHT,
    ): TouchPointCoord {
        require(viewportWidth > 0.0 && viewportHeight > 0.0 && viewportWidth.isFinite() && viewportHeight.isFinite()) {
            "Viewport dimensions must be positive finite values"
        }
        require(contentWidth > 0 && contentHeight > 0) { "Content dimensions must be positive" }
        require(touchWidth in 1..65536 && touchHeight in 1..65536) { "Touch dimensions out of range" }
        val scale = minOf(viewportWidth / contentWidth.toDouble(), viewportHeight / contentHeight.toDouble())
        val drawWidth = contentWidth.toDouble() * scale
        val drawHeight = contentHeight.toDouble() * scale
        val offsetX = (viewportWidth - drawWidth) * 0.5
        val offsetY = (viewportHeight - drawHeight) * 0.5
        val u = if (localX.isFinite()) ((localX - offsetX) / drawWidth).coerceIn(0.0, 1.0) else 0.0
        val v = if (localY.isFinite()) ((localY - offsetY) / drawHeight).coerceIn(0.0, 1.0) else 0.0
        val x = (u * (touchWidth - 1)).roundToInt().coerceIn(0, touchWidth - 1)
        val y = (v * (touchHeight - 1)).roundToInt().coerceIn(0, touchHeight - 1)
        return TouchPointCoord(x, y)
    }
}
