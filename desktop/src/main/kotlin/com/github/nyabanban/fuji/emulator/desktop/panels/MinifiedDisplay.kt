package com.github.nyabanban.fuji.emulator.desktop.panels

import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.math.hypot

/** Prism samples four neighbouring texels even when a screen pixel covers a much larger LCD area. */
internal class MinifiedDisplay(private val view: ImageView) {
    private class Level(val width: Int, val height: Int) {
        val pixels: IntBuffer = ByteBuffer.allocateDirect(width * height * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asIntBuffer()
        val buffer = PixelBuffer(width, height, pixels, PixelFormat.getIntArgbPreInstance())
        val image = WritableImage(buffer)
    }

    private val levels = mutableListOf<Level>()
    private var source: PixelBuffer<IntBuffer>? = null
    private var original: WritableImage? = null
    private var queued = false
    private val window = view.sceneProperty().flatMap { it.windowProperty() }
    private val scaleX = window.flatMap { it.renderScaleXProperty().asObject() }
    private val scaleY = window.flatMap { it.renderScaleYProperty().asObject() }
    private val geometry = listOf(
        view.boundsInLocalProperty(), view.localToSceneTransformProperty(), view.viewportProperty(),
        view.fitWidthProperty(), view.fitHeightProperty(), scaleX, scaleY,
    )
    private val resized = ChangeListener<Any> { _, _, _ ->
        if (!queued) {
            queued = true
            Platform.runLater {
                queued = false
                refresh(newFrame = false)
            }
        }
    }

    init {
        geometry.forEach { it.addListener(resized) }
    }

    fun detach() {
        geometry.forEach { it.removeListener(resized) }
        source = null
        original = null
        levels.clear()
    }

    fun present(buffer: PixelBuffer<IntBuffer>, image: WritableImage) {
        if (source !== buffer) levels.clear()
        source = buffer
        original = image
        refresh(newFrame = true)
    }

    private fun refresh(newFrame: Boolean) {
        val buffer = source ?: return
        // Image coordinates also define the layout size of an unconstrained view and the units of a viewport.
        if (view.fitWidth <= 0 || view.fitHeight <= 0 || view.viewport != null) {
            if (view.image !== original) view.image = original
            return
        }
        val transform = view.localToSceneTransform
        val bounds = view.boundsInLocal
        val sx = scaleX.value ?: 1.0
        val sy = scaleY.value ?: 1.0
        val screenWidth = bounds.width * hypot(transform.mxx * sx, transform.myx * sy)
        val screenHeight = bounds.height * hypot(transform.mxy * sx, transform.myy * sy)
        var width = buffer.width
        var height = buffer.height
        var count = 0
        // Exact halves preserve the guest pixel grid and aspect ratio. Prism handles the remainder.
        while (screenWidth > 0 && screenHeight > 0 && width % 2 == 0 && height % 2 == 0 &&
            width / 2 >= screenWidth && height / 2 >= screenHeight
        ) {
            width /= 2
            height /= 2
            if (levels.size == count) levels += Level(width, height)
            count++
        }
        val display = levels.getOrNull(count - 1)?.image ?: original
        if (!newFrame && view.image === display) return
        var pixels = buffer.buffer
        for (i in 0 until count) {
            val level = levels[i]
            reduceArgbPre(pixels, level.pixels, level.width, level.height)
            pixels = level.pixels
        }
        if (count > 0) levels[count - 1].buffer.updateBuffer { null }
        if (view.image !== display) view.image = display
    }
}

internal fun reduceArgbPre(source: IntBuffer, target: IntBuffer, width: Int, height: Int) {
    val stride = width * 2
    for (y in 0 until height) {
        var top = y * 2 * stride
        var bottom = top + stride
        var dst = y * width
        repeat(width) {
            val a = source.get(top++)
            val b = source.get(top++)
            val c = source.get(bottom++)
            val d = source.get(bottom++)
            // Sum each channel separately, retaining the low bits to round the average once.
            val high = ((a ushr 2) and 0x3f3f3f3f) + ((b ushr 2) and 0x3f3f3f3f) +
                ((c ushr 2) and 0x3f3f3f3f) + ((d ushr 2) and 0x3f3f3f3f)
            val low = (a and 0x03030303) + (b and 0x03030303) + (c and 0x03030303) + (d and 0x03030303)
            target.put(dst++, high + (((low + 0x02020202) ushr 2) and 0x03030303))
        }
    }
}
