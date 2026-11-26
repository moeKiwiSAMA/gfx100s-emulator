package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.components.vglib.GraphicsCommandExecution.Packet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal object SolidCommandRects {
    const val PROFILE = "axis-rect-morton-opaque-gray-v1"
    const val RGBA_PROFILE = "axis-rect-morton-rgba-solid-alpha-v1"
    const val LIMIT = 45
    const val EXTENDED_LIMIT = 16384
    private const val WIDTH = 1024
    private const val HEIGHT = 768
    private const val CUBIC_TOLERANCE = 0.25
    private const val MAX_CUBIC_DEPTH = 8
    private val geometry = listOf(0x5ef00e,0x63f004,0x40f004,0x40f004,0x41f046,0x4f004,0x5f004,
        0x2f004,0x3f004,0x653004,0x66f004,0x80f026,0x6f004)
    private val material = listOf(0x1a61004,0x1a11004,0x1b01004,0x1b31004,0x1aa1004,0x1841004,0x1842004,
        0x1a02004,0x1a21004,0x1a4f004,0x1a51004,0x1842004,0x1a74004,0x11c8004,0x1401004,0x14df004,
        0x14df004,0xc0f00e,0x1001004,0x1151004,0x1a04004,0x1848004,0x1841004,0x1401004,0x14df004,
        0x14df004,0x1402004,0x14df004,0x14df004,0x115c004,0x185f004,0x1863004,0xa0f02e,0x101004)
    private val values = mapOf(0x1a61004 to 1,0x1a11004 to 0,0x1b01004 to 11,0x1b31004 to 0,
        0x1aa1004 to 0,0x1842004 to 0,0x1a02004 to 0,0x1a21004 to 0,0x1a51004 to 1,0x1a74004 to 0,
        0x11c8004 to 0,0x1401004 to 0,0x14df004 to 0,0x1001004 to 1,0x1a04004 to 0x10000,
        0x1402004 to 0,0x115c004 to 0,0x1863004 to 0x100,0x101004 to 1)
    private val gradientMaterial = listOf(0x1a61004,0x1a11004,0x1b01004,0x1b31004,0x1aa1004,0x1841004,0x1842004,
        0x1a02004,0x1a21004,0x1a4f004,0x1a51004,0x1842004,0x1a74004,0x11c8004,0x1401004,0x14df004,
        0x14df004,0xc0f00e,0x1001004,0x1151004,0x1a04004,0x1848004,0x1841004,0x1401004,0x14df004,
        0x14df004,0x1402004,0x14df004,0x14df004,0x115c004,0x1037046,0x168f016,0x16df004,0x14e1004,
        0x16bf004,0x16cf3fc,0x16bf004,0x16cf3fc,0x1863004,0xa0f02e,0x101004)
    private val gradientValues = mapOf(0x1a61004 to 1,0x1a11004 to 0,0x1b01004 to 11,0x1b31004 to 0,
        0x1aa1004 to 0,0x1842004 to 0,0x1a02004 to 0,0x1a21004 to 0,0x1a51004 to 1,0x1a74004 to 0,
        0x11c8004 to 0,0x1401004 to 0,0x14df004 to 0,0x1001004 to 1,0x1a04004 to 0x10000,
        0x1402004 to 0x400,0x115c004 to 0x10000,0x14e1004 to 0x20,0x1863004 to 0x100,0x101004 to 1)
    private val strokeGeometry = listOf(0x4f004,0x5f004,0x2f004,0x3f004,0x5af01e,0x654004,0x653004,
        0x66f004,0x80f026,0x6f004)
    private val fullStrokeGeometry = listOf(0x5ef00e,0x63f004,0x40f004,0x40f004,0x41f046,
        0x4f004,0x5f004,0x2f004,0x3f004,0x5af01e,0x654004,0x653004,0x66f004,0x80f026,0x6f004)
    private val strokeMaterial = listOf(0x1a61004,0x1a04004,0x1848004,0x1841004,0x1401004,0x14df004,
        0x14df004,0x1402004,0x14df004,0x14df004,0x115c004,0x185f004,0x1863004,0xa0f02e,0x101004)
    private val strokeMaterialValues = mapOf(0x1a61004 to 1,0x1a04004 to 0x10000,
        0x1401004 to 0,0x14df004 to 0,0x1402004 to 0,0x115c004 to 0,0x1863004 to 0x100,0x101004 to 1)
    private val fullScissor = listOf(0x03ff0000, 0x02ff0000)
    private val group0Prologue = listOf(0x14ee004, 0x156e004, 0x1a7b004, 0x11cf004, 0x1471004, 0x1571004, 0x1a08004, 0x1d21004)

    internal data class Scissor(val clipLeft: Int, val clipTop: Int, val clipRight: Int, val clipBottom: Int)
    internal val FULL_SCISSOR = Scissor(0, 0, WIDTH, HEIGHT)

    internal fun parseScissor(words: List<Int>, canvasWidth: Int = WIDTH, canvasHeight: Int = HEIGHT): Scissor {
        require(words.size == 2) { "0xc0f00e scissor requires 2 words" }
        val w0 = words[0]
        val w1 = words[1]
        val l = w0 and 0xffff
        val r = (w0 ushr 16) and 0xffff
        // 0xc0f00e Y bounds are in OpenVG bottom-up coordinates (same axis as matrix[7] / canvasHeight - y):
        // yMin (low16) is inclusive bottom Y from bottom edge; yMax (high16) is inclusive top Y from bottom edge.
        // Full-screen LCD 0x02ff0000 -> yMin=0, yMax=767 -> screen clipTop=0, clipBottom=768.
        // Full-screen EVF 0x03bf0000 -> yMin=0, yMax=959 -> screen clipTop=0, clipBottom=960.
        val yMin = w1 and 0xffff
        val yMax = (w1 ushr 16) and 0xffff
        val clipLeft = l.coerceIn(0, canvasWidth)
        val clipRight = if (r < l) clipLeft else (r + 1).coerceIn(0, canvasWidth).coerceAtLeast(clipLeft)
        val clipTop: Int
        val clipBottom: Int
        if (yMax < yMin) {
            clipTop = 0
            clipBottom = 0
        } else {
            clipTop = (canvasHeight - 1 - yMax).coerceIn(0, canvasHeight)
            clipBottom = (canvasHeight - yMin).coerceIn(0, canvasHeight).coerceAtLeast(clipTop)
        }
        return Scissor(clipLeft, clipTop, clipRight, clipBottom)
    }

    internal interface Shape {
        fun paint(frame: ByteArray)
    }

    internal data class StreamState(
        val matrix: List<Double>? = null,
        val target: Long? = null,
        val scissor: List<Int>? = null,
    )

    internal data class Point(val x: Double, val y: Double)
    internal data class Subpath(val points: List<Point>, val closed: Boolean)

    internal fun paintPixel(frame: ByteArray, column: Int, row: Int, color: Int, srcOver: Boolean, canvasWidth: Int = WIDTH) {
        val alpha = (color ushr 24) and 255
        if (srcOver && alpha == 0) return
        // Guest 0x185f004 packs 0xAABBGGRR (observed: 0xff0000e1 red [!], 0xff00bee1 amber /!\).
        val blue = (color ushr 16) and 255
        val green = (color ushr 8) and 255
        val red = color and 255
        val offset = 4 * (row * canvasWidth + column)
        if (!srcOver || alpha == 255) {
            frame[offset] = alpha.toByte()
            frame[offset + 1] = blue.toByte()
            frame[offset + 2] = green.toByte()
            frame[offset + 3] = red.toByte()
        } else {
            val oldAlpha = frame[offset].toInt() and 255
            val coverage = alpha * 255 + oldAlpha * (255 - alpha)
            frame[offset] = div255Rounded(coverage).toByte()
            frame[offset + 1] = ((blue * alpha * 255 + (frame[offset + 1].toInt() and 255) * oldAlpha * (255 - alpha) + coverage / 2) / coverage).toByte()
            frame[offset + 2] = ((green * alpha * 255 + (frame[offset + 2].toInt() and 255) * oldAlpha * (255 - alpha) + coverage / 2) / coverage).toByte()
            frame[offset + 3] = ((red * alpha * 255 + (frame[offset + 3].toInt() and 255) * oldAlpha * (255 - alpha) + coverage / 2) / coverage).toByte()
        }
    }

    data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val color: Int,
        val srcOver: Boolean = true,
        val canvasWidth: Int = WIDTH,
    ) : Shape {
        override fun paint(frame: ByteArray) {
            val alpha = (color ushr 24) and 255
            if (srcOver && alpha == 0) return
            if (left >= right || top >= bottom) return
            if (NativeRenderBridge.fillRectRgba(frame, canvasWidth, left, top, right, bottom, color, srcOver)) {
                return
            }
            for (row in top until bottom) for (column in left until right) {
                paintPixel(frame, column, row, color, srcOver, canvasWidth)
            }
        }
    }

    internal class LinearGradientRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        private val m3: Double,
        private val m4: Double,
        private val m5: Double,
        private val rampLut: IntArray,
        val srcOver: Boolean = true,
        val canvasWidth: Int = WIDTH,
        val canvasHeight: Int = HEIGHT,
    ) : Shape {
        override fun paint(frame: ByteArray) {
            paint(frame, allowNative = true)
        }

        internal fun paint(frame: ByteArray, allowNative: Boolean) {
            if (left >= right || top >= bottom || rampLut.isEmpty()) return
            if (allowNative && NativeRenderBridge.fillLinearGradientRectRgba(
                    frame = frame,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    m3 = m3,
                    m4 = m4,
                    m5 = m5,
                    rampLut = rampLut,
                    srcOver = srcOver,
                )
            ) {
                return
            }
            val maxIdx = rampLut.size - 1
            if (m3 == 0.0) {
                for (row in top until bottom) {
                    val yVg = canvasHeight - (row + 0.5)
                    val t = (m4 * yVg + m5).coerceIn(0.0, 1.0)
                    val color = rampLut[(t * maxIdx + 0.5).toInt().coerceIn(0, maxIdx)]
                    val alpha = (color ushr 24) and 255
                    if (srcOver && alpha == 0) continue
                    if (!allowNative || !NativeRenderBridge.fillRectRgba(frame, canvasWidth, left, row, right, row + 1, color, srcOver)) {
                        for (col in left until right) {
                            paintPixel(frame, col, row, color, srcOver, canvasWidth)
                        }
                    }
                }
            } else {
                for (row in top until bottom) {
                    val yVg = canvasHeight - (row + 0.5)
                    val rowBase = m4 * yVg + m5
                    for (col in left until right) {
                        val t = (m3 * (col + 0.5) + rowBase).coerceIn(0.0, 1.0)
                        val color = rampLut[(t * maxIdx + 0.5).toInt().coerceIn(0, maxIdx)]
                        paintPixel(frame, col, row, color, srcOver, canvasWidth)
                    }
                }
            }
        }
    }

    internal class LinearGradientPolygonFill(
        private val subpaths: List<Subpath>,
        private val m3: Double,
        private val m4: Double,
        private val m5: Double,
        private val rampLut: IntArray,
        private val srcOver: Boolean,
        private val scissor: Scissor = FULL_SCISSOR,
        private val canvasWidth: Int = WIDTH,
        private val canvasHeight: Int = HEIGHT,
    ) : Shape {
        override fun paint(frame: ByteArray) {
            if (scissor.clipLeft >= scissor.clipRight || scissor.clipTop >= scissor.clipBottom || rampLut.isEmpty()) return
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (subpath in subpaths) {
                for (pt in subpath.points) {
                    if (pt.x < minX) minX = pt.x
                    if (pt.x > maxX) maxX = pt.x
                    if (pt.y < minY) minY = pt.y
                    if (pt.y > maxY) maxY = pt.y
                }
            }
            if (!minX.isFinite() || minX >= scissor.clipRight || maxX < scissor.clipLeft || minY >= scissor.clipBottom || maxY < scissor.clipTop) return
            val top = ceil(minY - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            val bottom = ceil(maxY - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            if (top >= bottom) return
            val maxIdx = rampLut.size - 1
            val xs = ArrayList<Double>()
            for (row in top until bottom) {
                val yc = row + 0.5
                xs.clear()
                for (subpath in subpaths) {
                    val pts = subpath.points
                    if (pts.size < 2) continue
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[i]
                        val p1 = pts[i + 1]
                        if ((p0.y <= yc && p1.y > yc) || (p1.y <= yc && p0.y > yc)) {
                            xs.add(p0.x + (yc - p0.y) * (p1.x - p0.x) / (p1.y - p0.y))
                        }
                    }
                    val last = pts.last()
                    val first = pts.first()
                    if (last != first && ((last.y <= yc && first.y > yc) || (first.y <= yc && last.y > yc))) {
                        xs.add(last.x + (yc - last.y) * (first.x - last.x) / (first.y - last.y))
                    }
                }
                if (xs.size < 2) continue
                xs.sort()
                val yVg = canvasHeight - yc
                val rowBase = m4 * yVg + m5
                var k = 0
                while (k + 1 < xs.size) {
                    val startCol = ceil(xs[k] - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
                    val endCol = ceil(xs[k + 1] - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
                    if (m3 == 0.0) {
                        val t = rowBase.coerceIn(0.0, 1.0)
                        val color = rampLut[(t * maxIdx + 0.5).toInt().coerceIn(0, maxIdx)]
                        if (endCol - startCol >= 8 && NativeRenderBridge.fillRectRgba(frame, canvasWidth, startCol, row, endCol, row + 1, color, srcOver)) {
                            // Painted via native SIMD span
                        } else {
                            for (col in startCol until endCol) {
                                paintPixel(frame, col, row, color, srcOver, canvasWidth)
                            }
                        }
                    } else {
                        for (col in startCol until endCol) {
                            val t = (m3 * (col + 0.5) + rowBase).coerceIn(0.0, 1.0)
                            val color = rampLut[(t * maxIdx + 0.5).toInt().coerceIn(0, maxIdx)]
                            paintPixel(frame, col, row, color, srcOver, canvasWidth)
                        }
                    }
                    k += 2
                }
            }
        }
    }

    internal class PolygonFill(
        private val subpaths: List<Subpath>,
        private val color: Int,
        private val srcOver: Boolean,
        private val scissor: Scissor = FULL_SCISSOR,
        private val canvasWidth: Int = WIDTH,
    ) : Shape {
        override fun paint(frame: ByteArray) {
            if (scissor.clipLeft >= scissor.clipRight || scissor.clipTop >= scissor.clipBottom) return
            val alpha = (color ushr 24) and 255
            if (srcOver && alpha == 0) return
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (subpath in subpaths) {
                for (pt in subpath.points) {
                    if (pt.x < minX) minX = pt.x
                    if (pt.x > maxX) maxX = pt.x
                    if (pt.y < minY) minY = pt.y
                    if (pt.y > maxY) maxY = pt.y
                }
            }
            if (!minX.isFinite() || minX >= scissor.clipRight || maxX < scissor.clipLeft || minY >= scissor.clipBottom || maxY < scissor.clipTop) return
            val top = ceil(minY - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            val bottom = ceil(maxY - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            if (top >= bottom) return
            if (NativeRenderBridge.isAvailable) {
                var totalEdges = 0
                for (subpath in subpaths) {
                    val sz = subpath.points.size
                    if (sz >= 2) {
                        totalEdges += sz - 1
                        if (subpath.points[sz - 1] != subpath.points[0]) totalEdges++
                    }
                }
                if (totalEdges > 0) {
                    val ex0 = DoubleArray(totalEdges)
                    val ey0 = DoubleArray(totalEdges)
                    val ex1 = DoubleArray(totalEdges)
                    val ey1 = DoubleArray(totalEdges)
                    var n = 0
                    for (subpath in subpaths) {
                        val pts = subpath.points
                        if (pts.size < 2) continue
                        for (i in 0 until pts.size - 1) {
                            val p0 = pts[i]
                            val p1 = pts[i + 1]
                            if (p0.y != p1.y) {
                                ex0[n] = p0.x; ey0[n] = p0.y; ex1[n] = p1.x; ey1[n] = p1.y; n++
                            }
                        }
                        val last = pts[pts.size - 1]
                        val first = pts[0]
                        if (last != first && last.y != first.y) {
                            ex0[n] = last.x; ey0[n] = last.y; ex1[n] = first.x; ey1[n] = first.y; n++
                        }
                    }
                    if (n == 0 || NativeRenderBridge.fillPolygonRgba(
                            frame, canvasWidth, ex0, ey0, ex1, ey1, n,
                            top, bottom, scissor.clipLeft, scissor.clipRight, color, srcOver,
                        )
                    ) {
                        return
                    }
                }
            }
            val xs = ArrayList<Double>()
            for (row in top until bottom) {
                val yc = row + 0.5
                xs.clear()
                for (subpath in subpaths) {
                    val pts = subpath.points
                    if (pts.size < 2) continue
                    for (i in 0 until pts.size - 1) {
                        val p0 = pts[i]
                        val p1 = pts[i + 1]
                        if ((p0.y <= yc && p1.y > yc) || (p1.y <= yc && p0.y > yc)) {
                            xs.add(p0.x + (yc - p0.y) * (p1.x - p0.x) / (p1.y - p0.y))
                        }
                    }
                    val last = pts.last()
                    val first = pts.first()
                    if (last != first && ((last.y <= yc && first.y > yc) || (first.y <= yc && last.y > yc))) {
                        xs.add(last.x + (yc - last.y) * (first.x - last.x) / (first.y - last.y))
                    }
                }
                if (xs.size < 2) continue
                xs.sort()
                var k = 0
                while (k + 1 < xs.size) {
                    val startCol = ceil(xs[k] - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
                    val endCol = ceil(xs[k + 1] - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
                    if (endCol - startCol >= 8 && NativeRenderBridge.fillRectRgba(frame, canvasWidth, startCol, row, endCol, row + 1, color, srcOver)) {
                        // Painted via native SIMD span
                    } else {
                        for (col in startCol until endCol) {
                            paintPixel(frame, col, row, color, srcOver, canvasWidth)
                        }
                    }
                    k += 2
                }
            }
        }
    }

    internal class StrokePath(
        private val subpaths: List<Subpath>,
        private val rx: Double,
        private val ry: Double,
        private val color: Int,
        private val srcOver: Boolean,
        private val scissor: Scissor = FULL_SCISSOR,
        private val canvasWidth: Int = WIDTH,
    ) : Shape {
        override fun paint(frame: ByteArray) {
            if (scissor.clipLeft >= scissor.clipRight || scissor.clipTop >= scissor.clipBottom) return
            val alpha = (color ushr 24) and 255
            if (srcOver && alpha == 0) return
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            for (subpath in subpaths) {
                for (pt in subpath.points) {
                    if (pt.x < minX) minX = pt.x
                    if (pt.x > maxX) maxX = pt.x
                    if (pt.y < minY) minY = pt.y
                    if (pt.y > maxY) maxY = pt.y
                }
            }
            if (!minX.isFinite()) return
            val left = floor(minX - rx - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
            val right = ceil(maxX + rx - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
            val top = floor(minY - ry - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            val bottom = ceil(maxY + ry - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            if (left >= right || top >= bottom) return
            val boxW = right - left
            val boxH = bottom - top
            val covered = BooleanArray(boxW * boxH)
            val invRx = 1.0 / rx
            val invRy = 1.0 / ry

            fun stampSegment(p0: Point, p1: Point) {
                val segLeft = floor(minOf(p0.x, p1.x) - rx - 0.5).toInt().coerceIn(left, right)
                val segRight = ceil(maxOf(p0.x, p1.x) + rx - 0.5).toInt().coerceIn(left, right)
                val segTop = floor(minOf(p0.y, p1.y) - ry - 0.5).toInt().coerceIn(top, bottom)
                val segBottom = ceil(maxOf(p0.y, p1.y) + ry - 0.5).toInt().coerceIn(top, bottom)
                if (segLeft >= segRight || segTop >= segBottom) return
                val ax = p0.x * invRx
                val ay = p0.y * invRy
                val bx = p1.x * invRx
                val by = p1.y * invRy
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                for (row in segTop until segBottom) {
                    val py = (row + 0.5) * invRy
                    val rowOffset = (row - top) * boxW
                    for (col in segLeft until segRight) {
                        val idx = rowOffset + (col - left)
                        if (covered[idx]) continue
                        val px = (col + 0.5) * invRx
                        val t = if (len2 <= 1e-18) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                        val qx = ax + t * dx - px
                        val qy = ay + t * dy - py
                        if (qx * qx + qy * qy <= 1.0) covered[idx] = true
                    }
                }
            }

            for (subpath in subpaths) {
                val pts = subpath.points
                if (pts.isEmpty()) continue
                if (pts.size == 1) {
                    stampSegment(pts[0], pts[0])
                    continue
                }
                for (i in 0 until pts.size - 1) {
                    stampSegment(pts[i], pts[i + 1])
                }
                if (subpath.closed && pts.last() != pts.first()) {
                    stampSegment(pts.last(), pts.first())
                }
            }

            for (r in 0 until boxH) {
                val row = top + r
                val rowOffset = r * boxW
                for (c in 0 until boxW) {
                    if (covered[rowOffset + c]) {
                        paintPixel(frame, left + c, row, color, srcOver, canvasWidth)
                    }
                }
            }
        }
    }

    private fun decodeFloat24(word: Int): Double? {
        if ((word ushr 24) != 0) return null
        if (word == 0) return 0.0
        val value = Math.scalb(1.0 + (word and 0xffff) / 65536.0, ((word ushr 16) and 0x7f) - 63) *
            if ((word and 0x800000) == 0) 1.0 else -1.0
        return if (value.isFinite()) value else null
    }

    private fun isAxisAlignedPositive(matrix: List<Double>): Boolean =
        matrix.size == 9 && matrix.all { it.isFinite() } && listOf(1, 2, 3, 5).all { matrix[it] == 0.0 } &&
            matrix[8] == 1.0 && matrix[0] > 0.0 && matrix[4] > 0.0

    /**
     * Accepts any finite, non-degenerate 2D OpenVG affine matrix (including 90°/180°/270°
     * portrait HUD rotation and arbitrary ±θ Electronic Level tilt, `BUG-V3-2-1`).
     */
    private fun validMatrix(matrix: List<Double>): Boolean {
        if (matrix.size != 9 || !matrix.all { it.isFinite() }) return false
        if (matrix[2] != 0.0 || matrix[5] != 0.0 || matrix[8] != 1.0) return false
        val det = matrix[0] * matrix[4] - matrix[1] * matrix[3]
        return abs(det) > 1e-9
    }

    private fun validLink(link: List<Int>, linked: List<Int>, canvasWidth: Int = WIDTH): Boolean {
        val expectedWord = 0x0960_0000 or (canvasWidth shl 2)
        return (link.drop(1) == listOf(0, 0x200, expectedWord, 0) || link.drop(1) == listOf(0, 0x200, 0x9601000, 0)) &&
            linked.dropLast(1) == link && linked.last() in listOf(0x1040002, 0x1040102)
    }

    private fun expectedCoordCount(path: ByteArray): Int? {
        var total = 0
        for (b in path) {
            total += when (b.toInt() and 0xff) {
                0 -> 0
                2, 3, 4, 5 -> 2
                6, 7, 8, 9 -> 1
                10, 11 -> 4
                12, 13 -> 6
                else -> return null
            }
        }
        return total
    }

    private fun flattenCubic(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double,
        x3: Double, y3: Double,
        depth: Int,
        out: MutableList<Point>,
    ) {
        val e1x = abs(x1 - (2.0 * x0 + x3) / 3.0)
        val e1y = abs(y1 - (2.0 * y0 + y3) / 3.0)
        val e2x = abs(x2 - (x0 + 2.0 * x3) / 3.0)
        val e2y = abs(y2 - (y0 + 2.0 * y3) / 3.0)
        if (0.75 * maxOf(e1x, e1y, e2x, e2y) <= CUBIC_TOLERANCE || depth >= MAX_CUBIC_DEPTH) {
            out.add(Point(x3, y3))
            return
        }
        val x01 = (x0 + x1) * 0.5; val y01 = (y0 + y1) * 0.5
        val x12 = (x1 + x2) * 0.5; val y12 = (y1 + y2) * 0.5
        val x23 = (x2 + x3) * 0.5; val y23 = (y2 + y3) * 0.5
        val x012 = (x01 + x12) * 0.5; val y012 = (y01 + y12) * 0.5
        val x123 = (x12 + x23) * 0.5; val y123 = (y12 + y23) * 0.5
        val xm = (x012 + x123) * 0.5; val ym = (y012 + y123) * 0.5
        flattenCubic(x0, y0, x01, y01, x012, y012, xm, ym, depth + 1, out)
        flattenCubic(xm, ym, x123, y123, x23, y23, x3, y3, depth + 1, out)
    }

    private fun buildSubpaths(
        path: ByteArray,
        coordBytes: ByteArray,
        matrix: List<Double>,
        closeImplicitly: Boolean,
        canvasHeight: Int = HEIGHT,
    ): List<Subpath>? {
        val m0 = matrix[0]
        val m1 = matrix[1]
        val m3 = matrix[3]
        val m4 = matrix[4]
        val tx = matrix[6]
        val ty = matrix[7]
        val coords = ByteBuffer.wrap(coordBytes).order(ByteOrder.LITTLE_ENDIAN)
        fun toScreen(rawX: Double, rawY: Double): Point {
            val xVg = tx + m0 * rawX + m3 * rawY
            val yVg = ty + m1 * rawX + m4 * rawY
            return Point(xVg, canvasHeight - yVg)
        }
        fun nextScalar(): Double? {
            val v = coords.float.toDouble()
            return if (v.isFinite()) v else null
        }
        val subpaths = ArrayList<Subpath>()
        var current: MutableList<Point>? = null
        var rawCurX = 0.0
        var rawCurY = 0.0
        var rawStartX = 0.0
        var rawStartY = 0.0
        for (b in path) {
            val cmd = b.toInt() and 0xff
            val rel = (cmd and 1) != 0
            when (cmd) {
                0 -> {
                    val active = current
                    if (active != null && active.isNotEmpty()) {
                        subpaths.add(Subpath(active, closed = true))
                    }
                    current = null
                    rawCurX = rawStartX
                    rawCurY = rawStartY
                }
                2, 3 -> {
                    val prev = current
                    if (prev != null && prev.isNotEmpty()) {
                        subpaths.add(Subpath(prev, closed = closeImplicitly))
                    }
                    val dx = nextScalar() ?: return null
                    val dy = nextScalar() ?: return null
                    val nx = if (rel) rawCurX + dx else dx
                    val ny = if (rel) rawCurY + dy else dy
                    current = mutableListOf(toScreen(nx, ny))
                    rawCurX = nx; rawCurY = ny
                    rawStartX = nx; rawStartY = ny
                }
                4, 5 -> {
                    val dx = nextScalar() ?: return null
                    val dy = nextScalar() ?: return null
                    val nx = if (rel) rawCurX + dx else dx
                    val ny = if (rel) rawCurY + dy else dy
                    val active = current ?: mutableListOf(toScreen(rawCurX, rawCurY)).also {
                        rawStartX = rawCurX; rawStartY = rawCurY; current = it
                    }
                    active.add(toScreen(nx, ny))
                    rawCurX = nx; rawCurY = ny
                }
                6, 7 -> {
                    val dx = nextScalar() ?: return null
                    val nx = if (rel) rawCurX + dx else dx
                    val active = current ?: mutableListOf(toScreen(rawCurX, rawCurY)).also {
                        rawStartX = rawCurX; rawStartY = rawCurY; current = it
                    }
                    active.add(toScreen(nx, rawCurY))
                    rawCurX = nx
                }
                8, 9 -> {
                    val dy = nextScalar() ?: return null
                    val ny = if (rel) rawCurY + dy else dy
                    val active = current ?: mutableListOf(toScreen(rawCurX, rawCurY)).also {
                        rawStartX = rawCurX; rawStartY = rawCurY; current = it
                    }
                    active.add(toScreen(rawCurX, ny))
                    rawCurY = ny
                }
                10, 11 -> {
                    val c1x = nextScalar() ?: return null
                    val c1y = nextScalar() ?: return null
                    val ex = nextScalar() ?: return null
                    val ey = nextScalar() ?: return null
                    val qx = if (rel) rawCurX + c1x else c1x
                    val qy = if (rel) rawCurY + c1y else c1y
                    val nx = if (rel) rawCurX + ex else ex
                    val ny = if (rel) rawCurY + ey else ey
                    val p0 = toScreen(rawCurX, rawCurY)
                    val qp = toScreen(qx, qy)
                    val p3 = toScreen(nx, ny)
                    val cp1x = p0.x + (2.0 / 3.0) * (qp.x - p0.x)
                    val cp1y = p0.y + (2.0 / 3.0) * (qp.y - p0.y)
                    val cp2x = p3.x + (2.0 / 3.0) * (qp.x - p3.x)
                    val cp2y = p3.y + (2.0 / 3.0) * (qp.y - p3.y)
                    val active = current ?: mutableListOf(p0).also {
                        rawStartX = rawCurX; rawStartY = rawCurY; current = it
                    }
                    flattenCubic(p0.x, p0.y, cp1x, cp1y, cp2x, cp2y, p3.x, p3.y, 0, active)
                    rawCurX = nx; rawCurY = ny
                }
                12, 13 -> {
                    val x1 = nextScalar() ?: return null
                    val y1 = nextScalar() ?: return null
                    val x2 = nextScalar() ?: return null
                    val y2 = nextScalar() ?: return null
                    val x3 = nextScalar() ?: return null
                    val y3 = nextScalar() ?: return null
                    val rx1 = if (rel) rawCurX + x1 else x1
                    val ry1 = if (rel) rawCurY + y1 else y1
                    val rx2 = if (rel) rawCurX + x2 else x2
                    val ry2 = if (rel) rawCurY + y2 else y2
                    val rx3 = if (rel) rawCurX + x3 else x3
                    val ry3 = if (rel) rawCurY + y3 else y3
                    val p0 = toScreen(rawCurX, rawCurY)
                    val p1 = toScreen(rx1, ry1)
                    val p2 = toScreen(rx2, ry2)
                    val p3 = toScreen(rx3, ry3)
                    val active = current ?: mutableListOf(p0).also {
                        rawStartX = rawCurX; rawStartY = rawCurY; current = it
                    }
                    flattenCubic(p0.x, p0.y, p1.x, p1.y, p2.x, p2.y, p3.x, p3.y, 0, active)
                    rawCurX = rx3; rawCurY = ry3
                }
                else -> return null
            }
        }
        val trailing = current
        if (trailing != null && trailing.isNotEmpty()) {
            subpaths.add(Subpath(trailing, closed = closeImplicitly))
        }
        return subpaths
    }

    fun decode(vertex: List<Packet>, fragment: List<Packet>, target: Long, extended: Boolean = false, read: (Long,Int) -> ByteArray): Rect? {
        if (!GraphicsCommandExecution.headersMatch(vertex, geometry) || !GraphicsCommandExecution.headersMatch(fragment, material)) return null
        if (fragment.any { packet -> values[packet.header]?.let { packet.data.single() != it } == true }) return null
        val blendMode = fragment[5].data.single()
        if (blendMode !in listOf(0,8) || fragment[22].data.single() != 8 ||
            fragment[17].data != fullScissor) return null
        val color = fragment[30].data.single()
        if (!extended) {
            val gray = color and 255
            if (color != (0xff000000.toInt() or (gray * 0x010101))) return null
        }
        if (vertex[0].data != listOf(0x3f800000,0) || vertex[1].data.single() != 5 ||
            vertex[2].data.single() != 0xc001000 || vertex[3].data.single() != 0xc001000 ||
            vertex[6].data.single() != 0xd0000008.toInt() || vertex[8].data.single() != 0x10000005 ||
            vertex[9].data.single() != 0x2100 || vertex[10].data.single() != 0x6004 || vertex[12].data.single() != 1) return null
        if (!validLink(vertex[11].data, fragment[32].data)) return null
        require(fragment[9].data.single().toUInt().toLong() == target)
        val matrix = vertex[4].data.map { Float.fromBits(it).toDouble() }
        if (!isAxisAlignedPositive(matrix)) return null
        val path = read(vertex[7].data.single().toUInt().toLong(),5)
        if (!path.contentEquals(byteArrayOf(2,4,4,4,0))) return null
        val buffer = ByteBuffer.wrap(read(vertex[5].data.single().toUInt().toLong(),32)).order(ByteOrder.LITTLE_ENDIAN)
        val points = List(4) { buffer.float.toDouble() to buffer.float.toDouble() }
        if (points.any { !it.first.isFinite() || !it.second.isFinite() } ||
            points.toSet().size != 4 || points.map { it.first }.distinct().size != 2 ||
            points.map { it.second }.distinct().size != 2 || points.indices.any { index ->
                val next = points[(index + 1) % 4]
                points[index].first != next.first && points[index].second != next.second
            }) return null
        fun horizontal(value: Double) = ceil(matrix[6] + matrix[0] * value - 0.5).toInt().coerceIn(0,WIDTH)
        fun vertical(value: Double) = ceil(HEIGHT - matrix[7] - matrix[4] * value - 0.5).toInt().coerceIn(0,HEIGHT)
        return Rect(horizontal(points.minOf { it.first }),vertical(points.maxOf { it.second }),
            horizontal(points.maxOf { it.first }),vertical(points.minOf { it.second }),color,blendMode == 8)
    }

    private fun applyDmpColorTransform(color: Int, xform: List<Int>?): Int {
        if (xform == null || xform.size != 4) return color
        // DMP OpenVG 0x0180f01e: 4 words = [scaleRG, scaleBA, biasRG, biasBA] in signed Q7 (0x0080 = 1.0).
        // Identity [0x00800080, 0x00800080, 0, 0] is a zero-cost no-op.
        if (xform[0] == 0x00800080 && xform[1] == 0x00800080 && xform[2] == 0 && xform[3] == 0) return color
        fun s16(v: Int): Int = (v shl 16) shr 16
        val sR = s16(xform[0] and 0xffff)
        val sG = s16(xform[0] ushr 16)
        val sB = s16(xform[1] and 0xffff)
        val sA = s16(xform[1] ushr 16)
        val bR = s16(xform[2] and 0xffff)
        val bG = s16(xform[2] ushr 16)
        val bB = s16(xform[3] and 0xffff)
        val bA = s16(xform[3] ushr 16)
        val r = (((color and 0xff) * sR + 64) / 128 + bR).coerceIn(0, 255)
        val g = ((((color ushr 8) and 0xff) * sG + 64) / 128 + bG).coerceIn(0, 255)
        val b = ((((color ushr 16) and 0xff) * sB + 64) / 128 + bB).coerceIn(0, 255)
        val a = ((((color ushr 24) and 0xff) * sA + 64) / 128 + bA).coerceIn(0, 255)
        return (a shl 24) or (b shl 16) or (g shl 8) or r
    }

    private fun extractColorTransform(packets: List<Packet>): Pair<List<Packet>, List<Int>?> {
        val idx = packets.indexOfFirst { it.header == 0x180f01e }
        if (idx < 0) return packets to null
        val xform = packets[idx].data.takeIf { it.size == 4 } ?: return packets to null
        val stripped = ArrayList<Packet>(packets.size - 1)
        for (i in packets.indices) {
            if (i != idx) stripped.add(packets[i])
        }
        return stripped to xform
    }

    internal fun decodeShape(
        vertex: List<Packet>,
        fragment: List<Packet>,
        target: Long,
        extended: Boolean = false,
        streamState: StreamState? = null,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
        read: (Long, Int) -> ByteArray,
    ): Shape? {
        if (!extended) return decode(vertex, fragment, target, false, read)

        val (fragNoXform, colorTransform) = extractColorTransform(fragment)
        val normVertex = if ((vertex.size == geometry.size + 1 || vertex.size == fullStrokeGeometry.size + 1) && vertex.first().header == 0x41f046) {
            vertex.drop(1)
        } else {
            vertex
        }
        val normFragment = if (fragNoXform.size == material.size + 7 &&
            fragNoXform.take(8).map { it.header } == group0Prologue &&
            fragNoXform.drop(8).map { it.header } == material.drop(1)
        ) {
            listOf(Packet(0x1a61004, listOf(1))) + fragNoXform.drop(8)
        } else if (fragNoXform.size == gradientMaterial.size + 7 &&
            fragNoXform.take(8).map { it.header } == group0Prologue &&
            fragNoXform.drop(8).map { it.header } == gradientMaterial.drop(1)
        ) {
            listOf(Packet(0x1a61004, listOf(1))) + fragNoXform.drop(8)
        } else {
            fragNoXform
        }
        val isSolidMaterial = GraphicsCommandExecution.headersMatch(normFragment, material)
        val isGradientMaterial = !isSolidMaterial && GraphicsCommandExecution.headersMatch(normFragment, gradientMaterial)
        if (GraphicsCommandExecution.headersMatch(normVertex, geometry) && (isSolidMaterial || isGradientMaterial)) {
            val expectedValues = if (isGradientMaterial) gradientValues else values
            if (normFragment.any { packet -> expectedValues[packet.header]?.let { packet.data.single() != it } == true }) return null
            if (normFragment[19].data.single() !in listOf(0, 1)) return null // 0x1151004
            val xformEnableWord = normFragment[21].data.single() // 0x1848004
            if (xformEnableWord != 0 && !(xformEnableWord == 0x01000000 && colorTransform != null)) return null
            val preBlendMode = normFragment[5].data.single()
            val blendMode = normFragment[22].data.single()
            // DMP OpenVG 0x01841004: bit 3 = blend_enable (8 = SRC_OVER, 0 = SRC/replace), bits[2:1] = vg_blend_mode, bit 0 = mask_en
            if (preBlendMode !in listOf(0, 8) || blendMode !in listOf(0, 8)) return null
            val scissor = parseScissor(normFragment[17].data, canvasWidth, canvasHeight)
            val viewportWord = ((canvasHeight shl 2) shl 16) or (canvasWidth shl 2)
            val validViewport = setOf(0xc001000, viewportWord)
            if (normVertex[0].data != listOf(0x3f800000, 0) ||
                normVertex[2].data.single() !in validViewport || normVertex[3].data.single() !in validViewport ||
                normVertex[9].data.single() != 0x2100 || normVertex[10].data.single() != 0x6004 ||
                normVertex[12].data.single() != 1) return null
            val matLinkIdx = if (isGradientMaterial) 39 else 32
            if (!validLink(normVertex[11].data, normFragment[matLinkIdx].data, canvasWidth)) return null
            val shapeTarget = normFragment[9].data.single().toUInt().toLong()
            require(shapeTarget == target) {
                "Solid shape target 0x${shapeTarget.toString(16)} != configured target 0x${target.toString(16)}"
            }
            val pathLen = normVertex[1].data.single()
            if (pathLen < 1 || normVertex[8].data.single() != (0x10000000 or pathLen)) return null
            val coordWord = normVertex[6].data.single()
            val coordCount = coordWord and 0xffff
            if (coordWord != (0xd0000000.toInt() or coordCount)) return null
            val matrix = normVertex[4].data.map { Float.fromBits(it).toDouble() }
            if (!validMatrix(matrix)) return null
            val path = read(normVertex[7].data.single().toUInt().toLong(), pathLen)
            if (expectedCoordCount(path) != coordCount) return null
            val coordBytes = read(normVertex[5].data.single().toUInt().toLong(), coordCount * 4)
            var axisRectBounds: IntArray? = null
            if (isAxisAlignedPositive(matrix) && path.contentEquals(byteArrayOf(2, 4, 4, 4, 0)) && coordCount == 8) {
                val buffer = ByteBuffer.wrap(coordBytes).order(ByteOrder.LITTLE_ENDIAN)
                val points = List(4) { buffer.float.toDouble() to buffer.float.toDouble() }
                if (points.all { it.first.isFinite() && it.second.isFinite() } &&
                    points.toSet().size == 4 && points.map { it.first }.distinct().size == 2 &&
                    points.map { it.second }.distinct().size == 2 && points.indices.all { index ->
                        val next = points[(index + 1) % 4]
                        points[index].first == next.first || points[index].second == next.second
                    }) {
                    fun horizontal(value: Double) = ceil(matrix[6] + matrix[0] * value - 0.5).toInt()
                        .coerceIn(scissor.clipLeft, scissor.clipRight)
                    fun vertical(value: Double) = ceil(canvasHeight - matrix[7] - matrix[4] * value - 0.5).toInt()
                        .coerceIn(scissor.clipTop, scissor.clipBottom)
                    axisRectBounds = intArrayOf(
                        horizontal(points.minOf { it.first }),
                        vertical(points.maxOf { it.second }),
                        horizontal(points.maxOf { it.first }),
                        vertical(points.minOf { it.second }),
                    )
                }
            }
            if (isGradientMaterial) {
                // Linear gradient: 0x1037046 (9x f24 inverse paint matrix), 0x168f016 == [0,0,0], 0x16bf004 == 0 + 0x16cf3fc (128-entry color ramp LUT)
                if (normFragment[31].data != listOf(0, 0, 0)) return null
                if (normFragment[34].data.single() != 0) return null
                val gradMatrix = normFragment[30].data.map { decodeFloat24(it) ?: return null }
                if (gradMatrix.size != 9) return null
                val rampLut = normFragment[35].data.toIntArray()
                if (rampLut.isEmpty()) return null
                val m3 = gradMatrix[3]
                val m4 = gradMatrix[4]
                val m5 = gradMatrix[5]
                val srcOver = blendMode == 8
                if (axisRectBounds != null) {
                    return LinearGradientRect(
                        axisRectBounds[0], axisRectBounds[1], axisRectBounds[2], axisRectBounds[3],
                        m3, m4, m5, rampLut, srcOver, canvasWidth, canvasHeight,
                    )
                }
                val subpaths = buildSubpaths(path, coordBytes, matrix, closeImplicitly = true, canvasHeight = canvasHeight) ?: return null
                if (subpaths.isEmpty()) return null
                return LinearGradientPolygonFill(subpaths, m3, m4, m5, rampLut, srcOver, scissor, canvasWidth, canvasHeight)
            }
            val color = applyDmpColorTransform(normFragment[30].data.single(), colorTransform)
            if (axisRectBounds != null) {
                return Rect(axisRectBounds[0], axisRectBounds[1], axisRectBounds[2], axisRectBounds[3], color, blendMode == 8, canvasWidth)
            }
            val subpaths = buildSubpaths(path, coordBytes, matrix, closeImplicitly = true, canvasHeight = canvasHeight) ?: return null
            if (subpaths.isEmpty()) return null
            return PolygonFill(subpaths, color, blendMode == 8, scissor, canvasWidth)
        }

        // Full-state OpenVG StrokePath (AF focus box, Face/Eye AF tracking box, menu stroke outlines):
        // 15-packet fullStrokeGeometry (0x5ef00e..0x41f046 + 10 stroke packets) paired with full-state solid material
        // (with optional DMP OpenVG color transform 0x180f01e when 0x1848004 == 0x01000000).
        if (GraphicsCommandExecution.headersMatch(normVertex, fullStrokeGeometry) && isSolidMaterial) {
            if (normFragment.any { packet -> values[packet.header]?.let { packet.data.single() != it } == true }) return null
            if (normFragment[19].data.single() !in listOf(0, 1)) return null // 0x1151004
            val xformEnableWord = normFragment[21].data.single() // 0x1848004
            if (xformEnableWord != 0 && !(xformEnableWord == 0x01000000 && colorTransform != null)) return null
            val preBlendMode = normFragment[5].data.single()
            val blendMode = normFragment[22].data.single()
            if (preBlendMode !in listOf(0, 8) || blendMode !in listOf(0, 8)) return null
            val scissor = parseScissor(normFragment[17].data, canvasWidth, canvasHeight)
            val viewportWord = ((canvasHeight shl 2) shl 16) or (canvasWidth shl 2)
            val validViewport = setOf(0xc001000, viewportWord)
            if (normVertex[0].data != listOf(0x3f800000, 0) ||
                normVertex[2].data.single() !in validViewport || normVertex[3].data.single() !in validViewport) return null
            val shapeTarget = normFragment[9].data.single().toUInt().toLong()
            require(shapeTarget == target) {
                "Full-state stroke target 0x${shapeTarget.toString(16)} != configured target 0x${target.toString(16)}"
            }
            val matrix = normVertex[4].data.map { Float.fromBits(it).toDouble() }
            if (!validMatrix(matrix)) return null
            val capJoin = normVertex[10].data.single()
            if ((capJoin and 0x00330000.inv()) != 0 || normVertex[11].data.single() != 0x2000 ||
                normVertex[12].data.single() != 0x6004 || normVertex[14].data.single() != 1) return null
            if (normVertex[9].data.size != 4 || normVertex[9].data[0] != 0 || normVertex[9].data[1] != 0) return null
            val halfWidth = Float.fromBits(normVertex[9].data[2]).toDouble()
            val miterFactor = Float.fromBits(normVertex[9].data[3]).toDouble()
            if (!halfWidth.isFinite() || halfWidth <= 0.0 || !miterFactor.isFinite() || miterFactor < 0.0) return null
            if (!validLink(normVertex[13].data, normFragment[32].data, canvasWidth)) return null
            val pathWord = normVertex[8].data.single()
            val pathLen = pathWord and 0xffff
            if (pathLen < 1 || pathWord != (0x10000000 or pathLen) || normVertex[1].data.single() != pathLen) return null
            val coordWord = normVertex[6].data.single()
            val coordCount = coordWord and 0xffff
            if (coordWord != (0xd0000000.toInt() or coordCount)) return null
            val path = read(normVertex[7].data.single().toUInt().toLong(), pathLen)
            if (expectedCoordCount(path) != coordCount) return null
            val coordBytes = read(normVertex[5].data.single().toUInt().toLong(), coordCount * 4)
            val subpaths = buildSubpaths(path, coordBytes, matrix, closeImplicitly = false, canvasHeight = canvasHeight) ?: return null
            if (subpaths.isEmpty()) return null
            val color = applyDmpColorTransform(normFragment[30].data.single(), colorTransform)
            val strokeScaleX = kotlin.math.hypot(matrix[0], matrix[1])
            val strokeScaleY = kotlin.math.hypot(matrix[3], matrix[4])
            return StrokePath(subpaths, halfWidth * strokeScaleX, halfWidth * strokeScaleY, color, blendMode == 8, scissor, canvasWidth)
        }

        if (GraphicsCommandExecution.headersMatch(vertex, strokeGeometry) && GraphicsCommandExecution.headersMatch(fragNoXform, strokeMaterial)) {
            if (fragNoXform.any { packet -> strokeMaterialValues[packet.header]?.let { packet.data.single() != it } == true }) return null
            val xformEnableWord = fragNoXform[2].data.single() // 0x1848004
            if (xformEnableWord != 0 && !(xformEnableWord == 0x01000000 && colorTransform != null)) return null
            val blendMode = fragNoXform[3].data.single()
            if (blendMode !in listOf(0, 8)) return null
            // 0x654004 encodes stroke cap (bits 17:16) and join (bits 21:20) styles (e.g., 0x110000, 0x010000, 0x020000).
            val capJoin = vertex[5].data.single()
            if ((capJoin and 0x00330000.inv()) != 0 || vertex[6].data.single() != 0x2000 ||
                vertex[7].data.single() != 0x6004 || vertex[9].data.single() != 1) return null
            if (vertex[4].data.size != 4 || vertex[4].data[0] != 0 || vertex[4].data[1] != 0) return null
            val halfWidth = Float.fromBits(vertex[4].data[2]).toDouble()
            val miterFactor = Float.fromBits(vertex[4].data[3]).toDouble()
            if (!halfWidth.isFinite() || halfWidth <= 0.0 || !miterFactor.isFinite() || miterFactor < 0.0) return null
            if (!validLink(vertex[8].data, fragNoXform[13].data, canvasWidth)) return null
            require(streamState != null && streamState.target == target && streamState.scissor != null) {
                "State-reuse stroke group missing inherited target or scissor"
            }
            val scissor = parseScissor(streamState.scissor, canvasWidth, canvasHeight)
            val matrix = requireNotNull(streamState.matrix) { "State-reuse stroke group missing inherited matrix" }
            if (!validMatrix(matrix)) return null
            val pathWord = vertex[3].data.single()
            val pathLen = pathWord and 0xffff
            if (pathLen < 1 || pathWord != (0x10000000 or pathLen)) return null
            val coordWord = vertex[1].data.single()
            val coordCount = coordWord and 0xffff
            if (coordWord != (0xd0000000.toInt() or coordCount)) return null
            val path = read(vertex[2].data.single().toUInt().toLong(), pathLen)
            if (expectedCoordCount(path) != coordCount) return null
            val coordBytes = read(vertex[0].data.single().toUInt().toLong(), coordCount * 4)
            val subpaths = buildSubpaths(path, coordBytes, matrix, closeImplicitly = false, canvasHeight = canvasHeight) ?: return null
            if (subpaths.isEmpty()) return null
            val color = applyDmpColorTransform(fragNoXform[11].data.single(), colorTransform)
            val strokeScaleX = kotlin.math.hypot(matrix[0], matrix[1])
            val strokeScaleY = kotlin.math.hypot(matrix[3], matrix[4])
            return StrokePath(subpaths, halfWidth * strokeScaleX, halfWidth * strokeScaleY, color, blendMode == 8, scissor, canvasWidth)
        }

        if (TRACE_NULL_SHAPES) {
            val vh = vertex.map { "0x${it.header.toUInt().toString(16)}(${it.data.size})" }
            val fh = fragment.map { "0x${it.header.toUInt().toString(16)}(${it.data.size})" }
            val sig = "$vh|$fh"
            if (seenNullSigs.add(sig)) {
                val vPreview = vertex.joinToString(" ") { p -> "0x${p.header.toUInt().toString(16)}=${p.data.take(6).map { "0x${it.toUInt().toString(16)}" }}" }
                val fPreview = fragment.joinToString(" ") { p -> "0x${p.header.toUInt().toString(16)}=${p.data.take(4).map { "0x${it.toUInt().toString(16)}" }}" }
                println("[VGLIB-NULL-SHAPE #${seenNullSigs.size}] V($vPreview) F($fPreview)")
            }
        }
        return null
    }
    private val TRACE_NULL_SHAPES = System.getProperty("fuji.vglib.traceNullShapes") == "1" || System.getenv("FUJI_VGLIB_TRACE_NULL_SHAPES") == "1"
    private val seenNullSigs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
}
