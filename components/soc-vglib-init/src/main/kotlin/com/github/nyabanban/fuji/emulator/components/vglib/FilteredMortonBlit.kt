package com.github.nyabanban.fuji.emulator.components.vglib

import kotlin.math.abs
import kotlin.math.floor

/**
 * OpenVG filtered image sampling. Surface and texel coordinates both denote pixel centers
 * at n + 0.5. Blend premultiplied samples so transparent texels cannot introduce color fringes.
 *
 * Uses exact 2x2 premultiplied bilinear filtering for magnification/1:1 (footprint <= 1 texel),
 * and exact separable Q8 box/area footprint integration when minifying (> 1 texel per pixel)
 * so thin 1-texel vertical glyph strokes never alias or drop out under horizontal condensation.
 */
internal object FilteredMortonBlit {
    private fun xOffset(x: Int) = (x ushr 3) * 64 + (x and 1) + ((x and 2) shl 1) + ((x and 4) shl 2)
    private fun yOffset(y: Int, pitch: Int) = (y ushr 3) * pitch * 64 + ((y and 1) shl 1) +
        ((y and 2) shl 2) + ((y and 4) shl 3)

    private fun texelFixed8(x: Double, size: Int): Int {
        val scaled = (x - 0.5) * 256.0 + 0.5
        val last = (size - 1) * 256
        if (!(scaled > 0.0)) return 0
        if (scaled >= last.toDouble()) return last
        return scaled.toInt()
    }

    private fun clampQ8Bound(pos: Double, maxQ8: Int): Int {
        if (!(pos > 0.0)) return 0
        val scaled = pos * 256.0 + 0.5
        if (scaled >= maxQ8.toDouble()) return maxQ8
        return floor(scaled).toInt()
    }

    fun paint(
        texture: ByteArray, frame: ByteArray, canvasWidth: Int, width: Int, height: Int, pitch: Int,
        top: Int, bottom: Int, left: Int, right: Int,
        u0: Double, v0: Double, duDx: Double, dvDx: Double, duDy: Double, dvDy: Double,
        allowNative: Boolean = true,
    ) {
        if (width <= 0 || height <= 0 || pitch <= 0 || canvasWidth <= 0 || top >= bottom || left >= right) return
        if (allowNative && NativeRenderBridge.blitFilteredMorton(
                texture, frame, canvasWidth, width, height, pitch, top, bottom, left, right,
                u0, v0, duDx, dvDx, duDy, dvDy,
            )) return
        val spanU = abs(duDx) + abs(duDy)
        val spanV = abs(dvDx) + abs(dvDy)
        val minifyU = spanU > 1.0
        val minifyV = spanV > 1.0
        val useArea = minifyU || minifyV
        val halfU = if (minifyU && spanU.isFinite()) spanU * 0.5 else 0.5
        val halfV = if (minifyV && spanV.isFinite()) spanV * 0.5 else 0.5
        for (y in top until bottom) {
            var u = u0 + (y - top) * duDy
            var v = v0 + (y - top) * dvDy
            for (x in left until right) {
                if (u >= 0.0 && u < width && v >= 0.0 && v < height) {
                    val color = if (useArea) {
                        sampleArea(texture, width, height, pitch, u, v, minifyU, minifyV, halfU, halfV)
                    } else {
                        sampleBilinear(texture, width, height, pitch, u, v)
                    }
                    SolidCommandRects.paintPixel(frame, x, y, color, true, canvasWidth)
                }
                u += duDx
                v += dvDx
            }
        }
    }

    private fun sampleBilinear(texture: ByteArray, width: Int, height: Int, pitch: Int, u: Double, v: Double): Int {
        val fx = texelFixed8(u, width)
        val fy = texelFixed8(v, height)
        val x0 = xOffset(fx ushr 8)
        val x1 = xOffset(minOf((fx ushr 8) + 1, width - 1))
        val y0 = yOffset(fy ushr 8, pitch)
        val y1 = yOffset(minOf((fy ushr 8) + 1, height - 1), pitch)
        val p00 = (x0 + y0) * 4
        val p10 = (x1 + y0) * 4
        val p01 = (x0 + y1) * 4
        val p11 = (x1 + y1) * 4
        val wx = fx and 255
        val wy = fy and 255
        fun channel(offset: Int) = texture[offset].toInt() and 255
        val w00 = channel(p00) * (256 - wx) * (256 - wy)
        val w10 = channel(p10) * wx * (256 - wy)
        val w01 = channel(p01) * (256 - wx) * wy
        val w11 = channel(p11) * wx * wy
        val weight = w00 + w10 + w01 + w11
        val alpha = (weight + 32768) ushr 16
        if (alpha == 0) return 0
        fun component(i: Int): Int {
            val sum = channel(p00 + i).toLong() * w00 + channel(p10 + i).toLong() * w10 +
                channel(p01 + i).toLong() * w01 + channel(p11 + i).toLong() * w11
            return ((sum + weight / 2) / weight).toInt()
        }
        return (alpha shl 24) or (component(1) shl 16) or (component(2) shl 8) or component(3)
    }

    private fun sampleArea(
        texture: ByteArray, width: Int, height: Int, pitch: Int, u: Double, v: Double,
        minifyU: Boolean, minifyV: Boolean, halfU: Double, halfV: Double,
    ): Int {
        var loX = 0
        var hiX = 0
        var colOff0 = 0
        var colOff1 = 0
        var wx = 0
        val totalX: Int
        if (minifyU) {
            val maxX = width shl 8
            loX = clampQ8Bound(u - halfU, maxX)
            hiX = clampQ8Bound(u + halfU, maxX)
            if (hiX <= loX) {
                val tx = floor(u).toInt().coerceIn(0, width - 1)
                loX = tx shl 8
                hiX = (tx + 1) shl 8
            }
            totalX = hiX - loX
        } else {
            val fx = texelFixed8(u, width)
            val ix = fx ushr 8
            colOff0 = xOffset(ix) * 4
            colOff1 = xOffset(minOf(ix + 1, width - 1)) * 4
            wx = fx and 255
            totalX = 256
        }

        var loY = 0
        var hiY = 0
        var rowOff0 = 0
        var rowOff1 = 0
        var wy = 0
        val totalY: Int
        if (minifyV) {
            val maxY = height shl 8
            loY = clampQ8Bound(v - halfV, maxY)
            hiY = clampQ8Bound(v + halfV, maxY)
            if (hiY <= loY) {
                val ty = floor(v).toInt().coerceIn(0, height - 1)
                loY = ty shl 8
                hiY = (ty + 1) shl 8
            }
            totalY = hiY - loY
        } else {
            val fy = texelFixed8(v, height)
            val iy = fy ushr 8
            rowOff0 = yOffset(iy, pitch) * 4
            rowOff1 = yOffset(minOf(iy + 1, height - 1), pitch) * 4
            wy = fy and 255
            totalY = 256
        }

        var alphaSum = 0L
        var bSum = 0L
        var gSum = 0L
        var rSum = 0L

        fun accumRow(rowOff: Int, covY: Long) {
            if (minifyU) {
                val tx0 = loX ushr 8
                val tx1 = (hiX - 1) ushr 8
                for (tx in tx0..tx1) {
                    val cLo = maxOf(tx shl 8, loX)
                    val cHi = minOf((tx + 1) shl 8, hiX)
                    val covX = (cHi - cLo).toLong()
                    val p = rowOff + xOffset(tx) * 4
                    val a = (texture[p].toInt() and 255).toLong()
                    if (a != 0L) {
                        val aw = a * (covX * covY)
                        alphaSum += aw
                        bSum += (texture[p + 1].toInt() and 255).toLong() * aw
                        gSum += (texture[p + 2].toInt() and 255).toLong() * aw
                        rSum += (texture[p + 3].toInt() and 255).toLong() * aw
                    }
                }
            } else {
                val p0 = rowOff + colOff0
                val a0 = (texture[p0].toInt() and 255).toLong()
                if (a0 != 0L) {
                    val aw0 = a0 * ((256 - wx).toLong() * covY)
                    alphaSum += aw0
                    bSum += (texture[p0 + 1].toInt() and 255).toLong() * aw0
                    gSum += (texture[p0 + 2].toInt() and 255).toLong() * aw0
                    rSum += (texture[p0 + 3].toInt() and 255).toLong() * aw0
                }
                if (wx != 0) {
                    val p1 = rowOff + colOff1
                    val a1 = (texture[p1].toInt() and 255).toLong()
                    if (a1 != 0L) {
                        val aw1 = a1 * (wx.toLong() * covY)
                        alphaSum += aw1
                        bSum += (texture[p1 + 1].toInt() and 255).toLong() * aw1
                        gSum += (texture[p1 + 2].toInt() and 255).toLong() * aw1
                        rSum += (texture[p1 + 3].toInt() and 255).toLong() * aw1
                    }
                }
            }
        }

        if (minifyV) {
            val ty0 = loY ushr 8
            val ty1 = (hiY - 1) ushr 8
            for (ty in ty0..ty1) {
                val rLo = maxOf(ty shl 8, loY)
                val rHi = minOf((ty + 1) shl 8, hiY)
                accumRow(yOffset(ty, pitch) * 4, (rHi - rLo).toLong())
            }
        } else {
            accumRow(rowOff0, (256 - wy).toLong())
            if (wy != 0) {
                accumRow(rowOff1, wy.toLong())
            }
        }

        val totalArea = totalX.toLong() * totalY.toLong()
        val alpha = ((alphaSum + (totalArea ushr 1)) / totalArea).toInt()
        if (alpha == 0) return 0
        val halfA = alphaSum ushr 1
        val b = ((bSum + halfA) / alphaSum).toInt()
        val g = ((gSum + halfA) / alphaSum).toInt()
        val r = ((rSum + halfA) / alphaSum).toInt()
        return (alpha shl 24) or (b shl 16) or (g shl 8) or r
    }
}
