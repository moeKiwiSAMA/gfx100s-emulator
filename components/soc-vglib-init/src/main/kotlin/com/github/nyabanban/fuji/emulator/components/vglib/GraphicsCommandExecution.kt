package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.abs
import kotlin.math.ceil

internal class GraphicsCommandExecution(private val heap: GraphicsMode2Workspace, private val target: Long,
                                       private val solidRects: Boolean = false, private val extended: Boolean = false,
                                       private val continuous: Boolean = false,
                                       private val skipAuditDigests: Boolean = continuous) {
    companion object {
        const val PROFILE = "axis-rect-morton-rgba-low-alpha-over-v1"
        private const val WIDTH = 1024
        private const val HEIGHT = 768
        private const val TEXTURE_FILTER_REG = 0x153
        private const val FILTER_LINEAR = 6
        private const val BYTES = WIDTH * HEIGHT * 4
        private val FAST_CONTINUOUS_SHA256 = "0".repeat(64)
        private val vertexHeaders = listOf(0x41f046,0x653004,0x3f004,0x5f004,0x63f004,0x5ef004,
            0x5ff004,0x40f004,0x80f026,0x6f004,0x9,0x22)
        private val fragmentHeaders = listOf(0x1a61004,0x1a11004,0x1b01004,0x1b31004,0xc0f00e,0x1001004,
            0x1151004,0x10c7046,0x158f004,0x1567004,0x152f004,0x1401004,0x14df004,0x14df004,0x1404004,
            0x155f004,0x1a04004,0x1848004,0x1841004,0x1401004,0x14df004,0x14df004,0x1402004,0x14df004,
            0x14df004,0x115c004,0x1aa1004,0x1841004,0x1863004,0x1842004,0x1a02004,0x1a21004,0x1a4f004,
            0x1a51004,0x1842004,0x1a74004,0x1158004,0x1531004,0x1431004,0xa0f02e,0x101004)
        private val fragmentConstants = mapOf(0x1a61004 to 1,0x1a11004 to 0,0x1b01004 to 11,0x1b31004 to 0,
            0x1001004 to 1,0x1151004 to 1,0x1567004 to 0x1b0000,0x1401004 to 2,0x14df004 to 0,
            0x1404004 to 0x210000,0x1a04004 to 0x10000,0x1848004 to 0,0x1402004 to 0,0x115c004 to 0x1000000,
            0x1aa1004 to 0,0x1863004 to 0x100,0x1842004 to 0,0x1a02004 to 0,0x1a21004 to 0,0x1a51004 to 1,
            0x1a74004 to 0,0x1158004 to 0x1000000,0x101004 to 1)
        private fun hash(bytes: ByteArray, length: Int = bytes.size): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(bytes, 0, length)
            return HexFormat.of().formatHex(md.digest())
        }
        private val MORTON_X = IntArray(8) { x -> (x and 1) or ((x and 2) shl 1) or ((x and 4) shl 2) }
        private val MORTON_Y = IntArray(8) { y -> ((y and 1) shl 1) or ((y and 2) shl 2) or ((y and 4) shl 3) }
        internal fun headersMatch(packets: List<Packet>, expected: List<Int>): Boolean {
            if (packets.size != expected.size) return false
            for (i in expected.indices) {
                if (packets[i].header != expected[i]) return false
            }
            return true
        }
    }

    private var frameScratch: ByteArray? = null
    private var textureScratch = ByteArray(0)

    init {
        require(target in 0..0x100000000L-BYTES && target and 3L == 0L)
        require(target + BYTES <= heap.base || heap.base + heap.bytes <= target)
    }

    internal data class Packet(val header: Int, val data: List<Int>)
    private data class Draw(
        val width: Int,
        val height: Int,
        val pitch: Int,
        val rows: Int,
        val texture: Long,
        val left: Double,
        val top: Double,
        val scaleX: Double,
        val scaleY: Double,
        val scissor: SolidCommandRects.Scissor = SolidCommandRects.FULL_SCISSOR,
        val m0: Double = scaleX,
        val m1: Double = 0.0,
        val m3: Double = 0.0,
        val m4: Double = -scaleY,
        val m6: Double = left,
        val m7: Double = HEIGHT - top,
        val canvasHeight: Int = HEIGHT,
        val isAxisAligned: Boolean = true,
        val linearFilter: Boolean,
    )

    private fun groups(bytes: ByteArray, marker: Int, terminalPadding: Boolean = false, maxGroups: Int = 128): Map<Int,List<Packet>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val result = linkedMapOf<Int,MutableList<Packet>>()
        var current: MutableList<Packet>? = null
        while (buffer.hasRemaining()) {
            val header = buffer.int
            if (terminalPadding && header == 0 && !buffer.hasRemaining() && (extended || current?.isEmpty() == true)) break
            val count = 1 + ((header and 0xfff) ushr 3)
            require(buffer.remaining() >= count * 4) { "Truncated graphics packet" }
            val data = List(count) { buffer.int }
            if (header == marker) {
                current = mutableListOf()
                require(result.size < maxGroups && result.put(data.single(),current) == null) { "Duplicate or excess graphics sequence" }
            } else current?.add(Packet(header,data))
        }
        return result
    }

    private fun range(address: Long, size: Int) {
        require(address and 3L == 0L && address in heap.base..heap.base + heap.bytes - size) { "Graphics input outside workspace" }
    }

    private fun read(memory: DmaMemory, address: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        readInto(memory, address, out, size)
        return out
    }

    private fun readInto(memory: DmaMemory, address: Long, dest: ByteArray, size: Int) {
        if (size <= 0) return
        if (memory is DirectGuestRamPort) {
            val direct = memory.sliceDirect(address, size.toLong(), write = false)
            if (direct != null) {
                MemorySegment.copy(direct, ValueLayout.JAVA_BYTE, 0L, dest, 0, size)
                return
            }
        }
        memory.check(address,size.toLong(),false)
        val buffer = ByteBuffer.wrap(dest,0,size)
        memory.read(address,buffer)
        check(!buffer.hasRemaining()) { "Short graphics command DMA read" }
    }

    private fun decode(
        vertices: List<Packet>,
        fragments: List<Packet>,
        areaPage: Boolean,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
    ): Draw {
        val vertex = if (vertices.size == vertexHeaders.size + 1 && vertices.first().header == 0x41f046) vertices.subList(1, vertices.size) else vertices
        require(headersMatch(vertex, vertexHeaders) && headersMatch(fragments, fragmentHeaders)) { "Unsupported inline draw packets" }
        require(fragments.all { fragmentConstants[it.header]?.let { value -> it.data.single() == value } ?: true }) {
            "Unexpected fragment constant in inline draw"
        }
        require(fragments[18].data.single() in listOf(0,2) && fragments[27].data.single() == 2) {
            "Unexpected fragment blend mode: f18=${fragments[18].data.single()} f27=${fragments[27].data.single()}"
        }
        // Direct fragment index lookups matching fragmentHeaders (zero HashMap allocation per draw)
        val scissorWords = fragments[4].data        // 0xc0f00e
        val scissor = if (extended) {
            SolidCommandRects.parseScissor(scissorWords, canvasWidth, canvasHeight)
        } else {
            require(scissorWords == listOf(0x03ff0000,0x02ff0000)) { "Unexpected non-extended scissor" }
            SolidCommandRects.FULL_SCISSOR
        }
        val viewportWord = ((canvasHeight shl 2) shl 16) or (canvasWidth shl 2)
        val vertexConstWords = vertex.subList(1,8).map { it.data.single() }
        val expectedVertexConsts = listOf(0x2100,5,0x80000005.toInt(),5,0x3f800000,0,viewportWord)
        val expectedLcdVertexConsts = listOf(0x2100,5,0x80000005.toInt(),5,0x3f800000,0,0xc001000)
        require(vertexConstWords == expectedVertexConsts || (extended && vertexConstWords == expectedLcdVertexConsts)) {
            "Unexpected vertex constants: ${vertexConstWords.map { "0x${it.toUInt().toString(16)}" }} (canvas=${canvasWidth}x$canvasHeight)"
        }
        val link = vertex[8].data
        val linkOk = (link.drop(1) == listOf(0,16,viewportWord,0) || (extended && link.drop(1) == listOf(0,16,0xc001000,0)))
        val matLink = fragments[39].data            // 0xa0f02e
        require(linkOk && matLink == link + 0x1000002) {
            "Unexpected vertex/material link: link=${link.map { "0x${it.toUInt().toString(16)}" }} mat=${matLink.map { "0x${it.toUInt().toString(16)}" }}"
        }
        require(vertex[9].data.single() == 1 && vertex[10].data == listOf(0x07090702,0)) {
            "Unexpected vertex tail"
        }
        val rect = vertex.last().data
        val width = rect[2]
        val height = rect[3]
        val areaLabel = areaPage && width == 140 && height == 34
        require((extended && width in 1..canvasWidth && height in 1..canvasHeight || areaLabel || width in 1..240 && height in 1..32) && rect == listOf(0,0,width,height,-width)) {
            "Unsupported draw rect ${width}x$height canvas=${canvasWidth}x$canvasHeight"
        }
        require(fragments[8].data.single() == ((width shl 16) or height)) { "Draw size mismatch" } // 0x158f004
        val storage = fragments[10].data.single()   // 0x152f004
        val pitch = storage ushr 16
        val rows = storage and 65535
        if (areaLabel) require(pitch == 144 && rows == 40) { "Area label storage mismatch" }
        else if (extended) require(pitch in width..canvasWidth && rows in height..canvasHeight && (pitch or rows) and 7 == 0) {
            "Extended draw storage mismatch pitch=$pitch rows=$rows canvas=${canvasWidth}x$canvasHeight"
        }
        else require(pitch in width..240 && rows in height..32 && (pitch or rows) and 7 == 0) { "Draw storage mismatch" }
        val drawTarget = fragments[32].data.single().toUInt().toLong() // 0x1a4f004
        require(drawTarget == target) {
            "Draw target 0x${drawTarget.toString(16)} != configured target 0x${target.toString(16)}"
        }
        val texture = fragments[15].data.single().toUInt().toLong()    // 0x155f004
        range(texture,pitch * rows * 4)
        val matrix = vertex.first().data.map { Float.fromBits(it).toDouble() }
        require(matrix.all(Double::isFinite) && listOf(1,2,3,5).all { matrix[it] == 0.0 } && matrix[8] == 1.0) {
            "Unsupported draw vertex matrix=$matrix"
        }
        val left = matrix[6]
        val top = canvasHeight - matrix[7]
        val scaleX = matrix[0]
        val scaleY = -matrix[4]
        require(scaleX > 0 && scaleY > 0) { "Invalid draw scale scaleX=$scaleX scaleY=$scaleY" }
        if (!extended) {
            require(left >= 0 && top >= 0 &&
                left + width * scaleX <= WIDTH + 0.001 && top + height * scaleY <= HEIGHT + 0.001) {
                "Non-extended draw rect out of bounds left=$left top=$top size=${width}x$height scale=${scaleX}x$scaleY"
            }
        }
        val inverse = fragments[7].data             // 0x10c7046
        val horizontal = 1.0 / (width * scaleX)
        val vertical = -1.0 / (height * scaleY)
        val expected = listOf(horizontal,0.0,-left * horizontal,0.0,vertical,-matrix[7] * vertical,0.0,0.0,1.0)
        require(inverse.zip(expected).all { (word, value) ->
            val real = if (word == 0) 0.0 else Math.scalb(1.0 + (word and 65535) / 65536.0, ((word ushr 16) and 127) - 63) *
                (if (word and 0x800000 == 0) 1 else -1)
            word ushr 24 == 0 && abs(real - value) <= 0.00003 * abs(value)
        }) { "Texture coordinates differ from axis-aligned quad" }
        val filter = fragments[37].data.single()
        require((filter == 0 || filter == FILTER_LINEAR) && fragments[38].data.single() == filter) { "Unexpected texture filter" }
        return Draw(width,height,pitch,rows,texture,left,top,scaleX,scaleY,scissor,linearFilter = filter == FILTER_LINEAR)
    }

    private fun decodeExtended(
        vertices: List<Packet>,
        fragments: List<Packet>,
        streamState: SolidCommandRects.StreamState,
        texStorage: Int?,
        texAddr: Long?,
        textureFilter: Int,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
    ): Draw? {
        val rect = vertices.lastOrNull { it.header == 0x22 }?.data ?: return null
        if (rect.size < 4) return null
        val width = rect[2]
        val height = rect[3]
        if (width !in 1..4096 || height !in 1..4096) return null

        val storage = fragments.lastOrNull { it.header == 0x152f004 }?.data?.singleOrNull() ?: texStorage ?: return null
        val pitch = storage ushr 16
        val rows = storage and 65535
        if (pitch < width || rows < height || pitch !in 8..4096 || rows !in 8..4096 || ((pitch or rows) and 7) != 0) return null

        val drawTarget = fragments.lastOrNull { it.header == 0x1a4f004 }?.data?.singleOrNull()?.toUInt()?.toLong()
            ?: streamState.target
            ?: return null
        if (drawTarget != target) return null

        val texture = fragments.lastOrNull { it.header == 0x155f004 }?.data?.singleOrNull()?.toUInt()?.toLong()
            ?: texAddr
            ?: return null
        val texBytes = pitch.toLong() * rows.toLong() * 4L
        if ((texture and 3L) != 0L || texBytes !in 1L..heap.bytes || texture < heap.base || texture > heap.base + heap.bytes - texBytes) {
            return null
        }

        val matrix = vertices.lastOrNull { it.header == 0x41f046 }?.data?.takeIf { it.size == 9 }
            ?.map { Float.fromBits(it).toDouble() }
            ?: streamState.matrix
            ?: return null
        if (matrix.size != 9 || !matrix.all(Double::isFinite) ||
            matrix[2] != 0.0 || matrix[5] != 0.0 || matrix[8] != 1.0
        ) {
            return null
        }
        val m0 = matrix[0]
        val m1 = matrix[1]
        val m3 = matrix[3]
        val m4 = matrix[4]
        val m6 = matrix[6]
        val m7 = matrix[7]
        val det = m0 * m4 - m1 * m3
        if (abs(det) <= 1e-9) return null
        val axisAligned = m1 == 0.0 && m3 == 0.0 && m0 > 0.0 && m4 < 0.0
        val left = m6
        val top = canvasHeight - m7
        val scaleX = if (axisAligned) m0 else kotlin.math.hypot(m0, m1)
        val scaleY = if (axisAligned) -m4 else kotlin.math.hypot(m3, m4)

        val scissorWords = fragments.lastOrNull { it.header == 0xc0f00e }?.data ?: streamState.scissor
        val scissor = if (scissorWords != null && scissorWords.size == 2) {
            SolidCommandRects.parseScissor(scissorWords, canvasWidth, canvasHeight)
        } else {
            SolidCommandRects.Scissor(0, 0, canvasWidth, canvasHeight)
        }
        return Draw(
            width = width,
            height = height,
            pitch = pitch,
            rows = rows,
            texture = texture,
            left = left,
            top = top,
            scaleX = scaleX,
            scaleY = scaleY,
            scissor = scissor,
            m0 = m0,
            m1 = m1,
            m3 = m3,
            m4 = m4,
            m6 = m6,
            m7 = m7,
            canvasHeight = canvasHeight,
            isAxisAligned = axisAligned,
            linearFilter = (textureFilter and FILTER_LINEAR) != 0,
        )
    }

    private fun paint(draw: Draw, texture: ByteArray, frame: ByteArray, canvasWidth: Int = WIDTH) {
        fun edge(position: Double) = ceil(position - 0.5).toInt()
        val pitchTiles = draw.pitch / 8
        val exactCopy = draw.isAxisAligned && draw.scaleX == 1.0 && draw.scaleY == 1.0 &&
            draw.left == kotlin.math.floor(draw.left) && draw.top == kotlin.math.floor(draw.top)
        if (!draw.isAxisAligned || (draw.linearFilter && !exactCopy)) {
            paintAffine(draw, texture, frame, canvasWidth, pitchTiles)
            return
        }
        val rowStart = maxOf(edge(draw.top), draw.scissor.clipTop)
        val rowEnd = minOf(edge(draw.top + draw.scaleY * draw.height), draw.scissor.clipBottom)
        val colStart = maxOf(edge(draw.left), draw.scissor.clipLeft)
        val colEnd = minOf(edge(draw.left + draw.scaleX * draw.width), draw.scissor.clipRight)
        val colCount = colEnd - colStart
        if (rowStart >= rowEnd || colCount <= 0) return
        if (NativeRenderBridge.blitMortonQuad(
                texture = texture,
                frame = frame,
                canvasWidth = canvasWidth,
                texWidth = draw.width,
                texHeight = draw.height,
                pitchTiles = pitchTiles,
                rowStart = rowStart,
                rowEnd = rowEnd,
                colStart = colStart,
                colEnd = colEnd,
                left = draw.left,
                top = draw.top,
                scaleX = draw.scaleX,
                scaleY = draw.scaleY,
            )
        ) {
            return
        }
        val maxTexX = draw.width - 1
        val maxTexY = draw.height - 1
        val colTileOffset = IntArray(colCount)
        val colMortonX = IntArray(colCount)
        for (i in 0 until colCount) {
            val column = colStart + i
            val texX = ((column + 0.5 - draw.left) / draw.scaleX).toInt().coerceIn(0, maxTexX)
            colTileOffset[i] = texX / 8
            colMortonX[i] = MORTON_X[texX and 7]
        }
        for (row in rowStart until rowEnd) {
            val texY = ((row + 0.5 - draw.top) / draw.scaleY).toInt().coerceIn(0, maxTexY)
            val tileRowBase = (texY / 8) * pitchTiles
            val mortonY = MORTON_Y[texY and 7]
            val dstRowOffset = row * canvasWidth + colStart
            for (i in 0 until colCount) {
                val source = ((tileRowBase + colTileOffset[i]) shl 8) + ((mortonY or colMortonX[i]) shl 2)
                val alpha = texture[source].toInt() and 255
                if (alpha == 0) continue
                val destination = (dstRowOffset + i) shl 2
                if (alpha == 255) {
                    frame[destination] = 255.toByte()
                    frame[destination + 1] = texture[source + 1]
                    frame[destination + 2] = texture[source + 2]
                    frame[destination + 3] = texture[source + 3]
                    continue
                }
                val oldAlpha = frame[destination].toInt() and 255
                if (oldAlpha == 0) {
                    frame[destination] = alpha.toByte()
                    frame[destination + 1] = texture[source + 1]
                    frame[destination + 2] = texture[source + 2]
                    frame[destination + 3] = texture[source + 3]
                    continue
                }
                val invAlpha = 255 - alpha
                val alphaWeight = alpha * 255
                val oldWeight = oldAlpha * invAlpha
                val coverage = alphaWeight + oldWeight
                val halfCoverage = coverage / 2
                val c1 = texture[source + 1].toInt() and 255
                val o1 = frame[destination + 1].toInt() and 255
                frame[destination + 1] = ((c1 * alphaWeight + o1 * oldWeight + halfCoverage) / coverage).toByte()
                val c2 = texture[source + 2].toInt() and 255
                val o2 = frame[destination + 2].toInt() and 255
                frame[destination + 2] = ((c2 * alphaWeight + o2 * oldWeight + halfCoverage) / coverage).toByte()
                val c3 = texture[source + 3].toInt() and 255
                val o3 = frame[destination + 3].toInt() and 255
                frame[destination + 3] = ((c3 * alphaWeight + o3 * oldWeight + halfCoverage) / coverage).toByte()
                frame[destination] = div255Rounded(coverage).toByte()
            }
        }
    }

    /**
     * General 2D affine Morton 8x8 tile blit supporting 90°/180°/270° portrait HUD rotation,
     * arbitrary tilted overlays, and H/V reflection (`BUG-V3-2-1`).
     */
    private fun paintAffine(draw: Draw, texture: ByteArray, frame: ByteArray, canvasWidth: Int, pitchTiles: Int) {
        val m0 = draw.m0
        val m1 = draw.m1
        val m3 = draw.m3
        val m4 = draw.m4
        val m6 = draw.m6
        val m7 = draw.m7
        val det = m0 * m4 - m1 * m3
        if (abs(det) <= 1e-9) return
        val invDet = 1.0 / det
        val w = draw.width.toDouble()
        val h = draw.height.toDouble()
        val ch = draw.canvasHeight.toDouble()
        val c0x = m6; val c0y = ch - m7
        val c1x = m6 + m0 * w; val c1y = ch - (m7 + m1 * w)
        val c2x = m6 + m0 * w + m3 * h; val c2y = ch - (m7 + m1 * w + m4 * h)
        val c3x = m6 + m3 * h; val c3y = ch - (m7 + m4 * h)
        val minSx = minOf(minOf(c0x, c1x), minOf(c2x, c3x))
        val maxSx = maxOf(maxOf(c0x, c1x), maxOf(c2x, c3x))
        val minSy = minOf(minOf(c0y, c1y), minOf(c2y, c3y))
        val maxSy = maxOf(maxOf(c0y, c1y), maxOf(c2y, c3y))
        val rowStart = maxOf(ceil(minSy - 0.5).toInt(), draw.scissor.clipTop)
        val rowEnd = minOf(ceil(maxSy - 0.5).toInt(), draw.scissor.clipBottom)
        val colStart = maxOf(ceil(minSx - 0.5).toInt(), draw.scissor.clipLeft)
        val colEnd = minOf(ceil(maxSx - 0.5).toInt(), draw.scissor.clipRight)
        if (rowStart >= rowEnd || colStart >= colEnd) return

        // Inverse affine step per +1 screen column (dx=+1, dy=0)
        val duDx = m4 * invDet
        val dvDx = -m1 * invDet
        val texW = draw.width
        val texH = draw.height
        val startDx = (colStart + 0.5) - m6

        if (draw.linearFilter) {
            val startDy = ch - (rowStart + 0.5) - m7
            FilteredMortonBlit.paint(
                texture, frame, canvasWidth, draw.width, draw.height, pitchTiles,
                rowStart, rowEnd, colStart, colEnd,
                (m4 * startDx - m3 * startDy) / det, (-m1 * startDx + m0 * startDy) / det,
                duDx, dvDx, m3 / det, -m0 / det,
            )
            return
        }
        for (row in rowStart until rowEnd) {
            val dy = (ch - (row + 0.5)) - m7
            var u = (m4 * startDx - m3 * dy) * invDet
            var v = (-m1 * startDx + m0 * dy) * invDet
            val dstRowOffset = row * canvasWidth
            for (col in colStart until colEnd) {
                val tx = kotlin.math.floor(u).toInt()
                val ty = kotlin.math.floor(v).toInt()
                u += duDx
                v += dvDx
                if (tx !in 0 until texW || ty !in 0 until texH) continue
                val source = (((ty ushr 3) * pitchTiles + (tx ushr 3)) shl 8) + ((MORTON_Y[ty and 7] or MORTON_X[tx and 7]) shl 2)
                val alpha = texture[source].toInt() and 255
                if (alpha == 0) continue
                val destination = (dstRowOffset + col) shl 2
                if (alpha == 255) {
                    frame[destination] = 255.toByte()
                    frame[destination + 1] = texture[source + 1]
                    frame[destination + 2] = texture[source + 2]
                    frame[destination + 3] = texture[source + 3]
                    continue
                }
                val oldAlpha = frame[destination].toInt() and 255
                if (oldAlpha == 0) {
                    frame[destination] = alpha.toByte()
                    frame[destination + 1] = texture[source + 1]
                    frame[destination + 2] = texture[source + 2]
                    frame[destination + 3] = texture[source + 3]
                    continue
                }
                val invAlpha = 255 - alpha
                val alphaWeight = alpha * 255
                val oldWeight = oldAlpha * invAlpha
                val coverage = alphaWeight + oldWeight
                val halfCoverage = coverage / 2
                val c1 = texture[source + 1].toInt() and 255
                val o1 = frame[destination + 1].toInt() and 255
                frame[destination + 1] = ((c1 * alphaWeight + o1 * oldWeight + halfCoverage) / coverage).toByte()
                val c2 = texture[source + 2].toInt() and 255
                val o2 = frame[destination + 2].toInt() and 255
                frame[destination + 2] = ((c2 * alphaWeight + o2 * oldWeight + halfCoverage) / coverage).toByte()
                val c3 = texture[source + 3].toInt() and 255
                val o3 = frame[destination + 3].toInt() and 255
                frame[destination + 3] = ((c3 * alphaWeight + o3 * oldWeight + halfCoverage) / coverage).toByte()
                frame[destination] = div255Rounded(coverage).toByte()
            }
        }
    }

    private fun descriptor(start: Map<String,Long>, maximum: Int): Pair<Long,Int> {
        val count = start.getValue("countWord")
        require(count in 1..maximum.toLong()) { "Graphics command descriptor countWord=$count exceeds 1..$maximum" }
        val size = count.toInt() * 8
        val address = start.getValue("address")
        range(address,size)
        return address to size
    }

    private fun descriptors(first: Map<String,Long>, second: Map<String,Long>, successor: Boolean): Pair<Pair<Long,Int>,Pair<Long,Int>> {
        val control = first["control"]
        if (extended) {
            require(control == second["control"] && (control == 0x0100ffffL || (successor && control == 0xffffL))) {
                "Unsupported command pair control or lengths"
            }
            return descriptor(first,131072) to descriptor(second,131072)
        }
        val longPair = first["countWord"] == 1578L && second["countWord"] == 2733L
        val shortPair = first["countWord"] == 1501L && second["countWord"] == 2619L
        if (successor && control == 0xffffL && second["control"] == control) {
            val counts = first.getValue("countWord") to second.getValue("countWord")
            when (counts) {
                1751L to 2992L, 1598L to 2764L, 1724L to 3411L ->
                    return descriptor(first,counts.first.toInt()) to descriptor(second,counts.second.toInt())
            }
        }
        require(control == second["control"] && (control == 0x0100ffffL ||
            successor && control == 0xffffL && (shortPair || longPair))) { "Unsupported command pair control or lengths" }
        return descriptor(first,if (longPair) 1578 else 1501) to descriptor(second,if (longPair) 2733 else 2619)
    }

    fun execute(
        memory: DmaMemory,
        first: Map<String,Long>,
        second: Map<String,Long>,
        successor: Boolean = false,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
    ): Map<String,Any> {
        val (geometryDescriptor,materialDescriptor) = descriptors(first,second,successor)
        val (geometryAddress,geometryBytes) = geometryDescriptor
        val (materialAddress,materialBytes) = materialDescriptor
        val geometry = read(memory,geometryAddress,geometryBytes)
        val bindings = read(memory,materialAddress,materialBytes)
        val datePage = first["countWord"] == 1724L
        val maxGroups = if (extended) 16384 else 128
        val vertices = groups(geometry,0x85f004,extended || datePage || first["countWord"] == 1578L,maxGroups)
        val materials = groups(bindings,0xa6f004,extended,maxGroups = maxGroups)
        var effWidth = canvasWidth
        var effHeight = canvasHeight
        if (extended && effWidth == WIDTH && effHeight == HEIGHT) {
            val exceedsLcd = materials.values.any { pkts ->
                pkts.any { p ->
                    p.header == 0xc0f00e && p.data.size == 2 &&
                        ((((p.data[0] ushr 16) and 0xffff) + 1 > WIDTH) || (((p.data[1] ushr 16) and 0xffff) + 1 > HEIGHT))
                }
            } || vertices.values.any { pkts ->
                pkts.any { p -> p.header == 0x40f004 && p.data.size == 1 && p.data[0] == 0x0f001400 }
            }
            if (exceedsLcd) {
                effWidth = 1280
                effHeight = 960
            }
        }
        val pairGroupZero = extended &&
            vertices.keys.firstOrNull() == 0xffff &&
            materials.keys.firstOrNull() == 0 &&
            0 !in vertices &&
            0xffff !in materials
        fun materialKey(vertexId: Int): Int = if (pairGroupZero && vertexId == 0xffff) 0 else vertexId
        val candidates = vertices.filterValues { packets -> packets.any { it.header == 0x22 } }
        require(candidates.size in drawCounts(datePage) &&
            materials.keys.filter { it in candidates || (pairGroupZero && it == 0 && 0xffff in candidates) } == candidates.keys.map(::materialKey)) {
            "Draw candidate/material mismatch candidates=${candidates.size}"
        }
        val areaPage = first["countWord"] == 1751L || first["countWord"] == 1598L
        val draws: Map<Int, Draw>
        val solids: Map<Int, SolidCommandRects.Shape>
        if (extended) {
            if (solidRects) {
                val matchedVertexIds = vertices.keys.filterTo(linkedSetOf()) { materialKey(it) in materials }
                val matchedMaterialIds = matchedVertexIds.map(::materialKey)
                require(materials.keys.filter { it in matchedMaterialIds } == matchedMaterialIds) {
                    "Solid vertex/material key order mismatch"
                }
            }
            var streamState = SolidCommandRects.StreamState()
            var texStorage: Int? = null
            var texAddr: Long? = null
            var textureFilter = 0
            val extDraws = linkedMapOf<Int, Draw>()
            val extSolids = linkedMapOf<Int, SolidCommandRects.Shape>()
            for ((id, packets) in vertices) {
                val material = materials[materialKey(id)]
                val matrixPacket = packets.lastOrNull { it.header == 0x41f046 }
                val targetPacket = material?.lastOrNull { it.header == 0x1a4f004 }
                val scissorPacket = material?.lastOrNull { it.header == 0xc0f00e }
                val storagePacket = material?.lastOrNull { it.header == 0x152f004 }
                val texAddrPacket = material?.lastOrNull { it.header == 0x155f004 }
                if (matrixPacket != null || targetPacket != null || scissorPacket != null) {
                    streamState = SolidCommandRects.StreamState(
                        matrix = matrixPacket?.data?.takeIf { it.size == 9 }?.map { Float.fromBits(it).toDouble() } ?: streamState.matrix,
                        target = targetPacket?.data?.singleOrNull()?.toUInt()?.toLong() ?: streamState.target,
                        scissor = scissorPacket?.data ?: streamState.scissor,
                    )
                }
                if (storagePacket != null) texStorage = storagePacket.data.singleOrNull() ?: texStorage
                if (texAddrPacket != null) texAddr = texAddrPacket.data.singleOrNull()?.toUInt()?.toLong() ?: texAddr
                if (material == null) continue
                // The image texture unit retains its sampler across delta command groups (0x1531004 / 0x153f004).
                material.lastOrNull { (it.header ushr 16) == TEXTURE_FILTER_REG }?.data?.singleOrNull()?.let { textureFilter = it }
                if (id in candidates) {
                    decodeExtended(packets, material, streamState, texStorage, texAddr, textureFilter, effWidth, effHeight)
                        ?.let { extDraws[id] = it }
                } else if (solidRects) {
                    SolidCommandRects.decodeShape(packets, material, target, true, streamState, effWidth, effHeight) { address, size ->
                        range(address, size)
                        read(memory, address, size)
                    }?.let { extSolids[id] = it }
                }
            }
            require(extSolids.size <= SolidCommandRects.EXTENDED_LIMIT) {
                "Extended solid count ${extSolids.size} exceeds limit ${SolidCommandRects.EXTENDED_LIMIT} (vertices=${vertices.size}, materials=${materials.size}, draws=${extDraws.size})"
            }
            draws = extDraws
            solids = extSolids
        } else {
            draws = candidates.mapValues { (id, packets) -> decode(packets, materials.getValue(materialKey(id)), areaPage, effWidth, effHeight) }
            solids = if (solidRects) {
                val matchedVertexIds = vertices.keys.filterTo(linkedSetOf()) { materialKey(it) in materials }
                val matchedMaterialIds = matchedVertexIds.map(::materialKey)
                require(materials.keys.filter { it in matchedMaterialIds } == matchedMaterialIds) {
                    "Solid vertex/material key order mismatch"
                }
                val streamState = SolidCommandRects.StreamState()
                buildMap {
                    for ((id, packets) in vertices) {
                        val material = materials[materialKey(id)]
                        if (material != null && id !in draws) {
                            SolidCommandRects.decodeShape(packets, material, target, false, streamState, effWidth, effHeight) { address, size ->
                                range(address, size)
                                read(memory, address, size)
                            }?.let { put(id, it) }
                        }
                    }
                }.also { require(it.size <= SolidCommandRects.LIMIT) }
            } else emptyMap()
        }
        val frameBytes = effWidth * effHeight * 4
        memory.check(target,frameBytes.toLong(),true)
        val frame = frameScratch?.takeIf { it.size >= frameBytes } ?: ByteArray(frameBytes).also { frameScratch = it }
        readInto(memory,target,frame,frameBytes)
        val before = if (skipAuditDigests) FAST_CONTINUOUS_SHA256 else hash(frame, frameBytes)
        for (id in vertices.keys) {
            solids[id]?.paint(frame)
            draws[id]?.let { draw ->
                val texBytes = draw.pitch * draw.rows * 4
                val tex = if (textureScratch.size >= texBytes) textureScratch else ByteArray(texBytes).also { textureScratch = it }
                readInto(memory,draw.texture,tex,texBytes)
                paint(draw,tex,frame,effWidth)
            }
        }
        val directDst = (memory as? DirectGuestRamPort)?.sliceDirect(target, frameBytes.toLong(), write = true)
        if (directDst != null) {
            MemorySegment.copy(frame, 0, directDst, ValueLayout.JAVA_BYTE, 0L, frameBytes)
            memory.notifyDirectWrite(target, frameBytes.toLong())
        } else {
            val buffer = ByteBuffer.wrap(frame, 0, frameBytes).asReadOnlyBuffer()
            memory.write(target,buffer)
            check(!buffer.hasRemaining()) { "Command framebuffer write incomplete" }
        }
        val afterSha = if (skipAuditDigests) FAST_CONTINUOUS_SHA256 else hash(frame, frameBytes)
        val geomSha = if (skipAuditDigests) FAST_CONTINUOUS_SHA256 else hash(geometry)
        val matSha = if (skipAuditDigests) FAST_CONTINUOUS_SHA256 else hash(bindings)
        return mapOf("target" to target, "sequences" to draws.keys.map { it.toUInt().toLong() },
            "geometryGroups" to vertices.size, "materialGroups" to materials.size, "beforeSha256" to before,
            "sha256" to afterSha, "geometrySha256" to geomSha, "materialSha256" to matSha,
            "geometryAddress" to geometryAddress, "geometryBytes" to geometryBytes, "materialAddress" to materialAddress, "materialBytes" to materialBytes) +
            (if (solidRects) mapOf("solidSequences" to solids.keys.map { it.toUInt().toLong() }) else emptyMap())
    }

    private fun drawCounts(datePage: Boolean) = if (extended) 0..2048 else if (datePage) 14..14 else 1..11

    fun restore(raw: Any?, first: Map<String,Long>, second: Map<String,Long>, successor: Boolean = false): Map<String,Any> {
        require(raw is Map<*,*> && raw.keys == setOf("target","sequences","geometryGroups","materialGroups",
            "beforeSha256","sha256","geometrySha256","materialSha256","geometryAddress","geometryBytes","materialAddress","materialBytes") +
            (if (solidRects) setOf("solidSequences") else emptySet()))
        fun number(value: Any?) = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Expected integer") }
        val sequences = raw["sequences"]
        require(number(raw["target"]) == target && sequences is List<*> && sequences.size in drawCounts(first["countWord"] == 1724L))
        val ids = sequences.map(::number)
        require(ids.distinct() == ids && ids.all { it in 0..0xffffffffL })
        val solidLimit = if (extended) SolidCommandRects.EXTENDED_LIMIT else SolidCommandRects.LIMIT
        val solidIds = if (solidRects) {
            val saved = raw["solidSequences"]
            require(saved is List<*> && saved.size <= solidLimit)
            saved.map(::number).also { list -> require(list.distinct() == list && list.all { it in 0..0xffffffffL && it !in ids }) }
        } else emptyList()
        val maxGroupCount = if (extended) 4096L else 128L
        val counts = listOf("geometryGroups","materialGroups").associateWith { key ->
            number(raw[key]).also { require(it in (ids.size + solidIds.size)..maxGroupCount) }.toInt()
        }
        val (geometryDescriptor,materialDescriptor) = descriptors(first,second,successor)
        val (geometryAddress,geometryBytes) = geometryDescriptor
        val (materialAddress,materialBytes) = materialDescriptor
        val descriptors = mapOf("geometryAddress" to geometryAddress, "geometryBytes" to geometryBytes,
            "materialAddress" to materialAddress, "materialBytes" to materialBytes)
        require(descriptors.all { (name, value) -> number(raw[name]) == value.toLong() })
        return mapOf("target" to target,"sequences" to ids) + counts + descriptors +
            (if (solidRects) mapOf("solidSequences" to solidIds) else emptyMap()) +
            listOf("beforeSha256","sha256","geometrySha256","materialSha256").associateWith { key ->
                (raw[key] as String).also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            }
    }
}
