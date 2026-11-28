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
        val rectX: Int = 0,
        val rectY: Int = 0,
        val texWidth: Int = width,
        val texHeight: Int = height,
        val customUv: DoubleArray? = null,
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
        texSize: Int?,
        texAddr: Long?,
        uvMatrix: List<Int>?,
        textureFilter: Int,
        imagePaintActive: Boolean,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
    ): Draw? {
        if (!imagePaintActive) return null
        val explicitTexEnable = fragments.lastOrNull { (it.header ushr 16) == 0x140 && (it.header and 0x1000) != 0 }
        if (explicitTexEnable != null && (explicitTexEnable.data.singleOrNull()?.and(0xff) != 2)) return null
        if (fragments.any { it.header == 0x185f004 } && fragments.none { it.header == 0x155f004 }) return null

        val rect = vertices.lastOrNull { it.header == 0x22 }?.data ?: return null
        if (rect.size < 4) return null
        val rectX = rect[0]
        val rectY = rect[1]
        val width = rect[2]
        val height = rect[3]
        if (width !in 1..4096 || height !in 1..4096) return null
        if (rect.size >= 5 && rect[4] != -width) return null

        val storage = fragments.lastOrNull { it.header == 0x152f004 }?.data?.singleOrNull() ?: texStorage ?: return null
        val pitch = storage ushr 16
        val rows = storage and 65535
        if (pitch < width || rows < height || pitch !in 8..4096 || rows !in 8..4096 || ((pitch or rows) and 7) != 0) return null

        val sizeWord = fragments.lastOrNull { it.header == 0x158f004 }?.data?.singleOrNull() ?: texSize
        val texWidth: Int
        val texHeight: Int
        if (sizeWord != null) {
            texWidth = sizeWord ushr 16
            texHeight = sizeWord and 0xffff
            if (texWidth !in width..pitch || texHeight !in height..rows) return null
        } else {
            texWidth = width
            texHeight = height
        }

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
        val rxD = rectX.toDouble()
        val ryD = rectY.toDouble()
        val originVgX = m6 + m0 * rxD + m3 * ryD
        val originVgY = m7 + m1 * rxD + m4 * ryD
        val axisAligned = m1 == 0.0 && m3 == 0.0 && m0 > 0.0 && m4 < 0.0
        val left = originVgX
        val top = canvasHeight - originVgY
        val scaleX = if (axisAligned) m0 else kotlin.math.hypot(m0, m1)
        val scaleY = if (axisAligned) -m4 else kotlin.math.hypot(m3, m4)

        val customUv = if (rectX != 0 || rectY != 0 || texWidth != width || texHeight != height) {
            val uvWords = fragments.lastOrNull { it.header == 0x10c7046 }?.data ?: uvMatrix
            uvWords?.takeIf { it.size == 9 }?.let { words ->
                val decoded = DoubleArray(9)
                for (i in 0 until 9) {
                    decoded[i] = SolidCommandRects.decodeFloat24(words[i]) ?: return@let null
                }
                if (decoded[6] != 0.0 || decoded[7] != 0.0 || abs(decoded[8] - 1.0) > 1e-3) return@let null
                val halfW = 0.5 * width.toDouble()
                val halfH = 0.5 * height.toDouble()
                val centerVgX = originVgX + m0 * halfW + m3 * halfH
                val centerVgY = originVgY + m1 * halfW + m4 * halfH
                val centerU = texWidth * (decoded[0] * centerVgX + decoded[1] * centerVgY + decoded[2])
                val centerV = texHeight * (decoded[3] * centerVgX + decoded[4] * centerVgY + decoded[5])
                if (centerU in -0.5..(texWidth + 0.5) && centerV in -0.5..(texHeight + 0.5)) {
                    doubleArrayOf(decoded[0], decoded[1], decoded[2], decoded[3], decoded[4], decoded[5])
                } else {
                    null
                }
            }
        } else {
            null
        }

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
            rectX = rectX,
            rectY = rectY,
            texWidth = texWidth,
            texHeight = texHeight,
            customUv = customUv,
            canvasHeight = canvasHeight,
            isAxisAligned = axisAligned,
            linearFilter = (textureFilter and FILTER_LINEAR) != 0,
        )
    }

    private fun decodeSolidQuad0x22(
        vertices: List<Packet>,
        fragments: List<Packet>,
        streamState: SolidCommandRects.StreamState,
        solidColor: Int?,
        blendWord: Int,
        ropEnableWord: Int,
        canvasWidth: Int = WIDTH,
        canvasHeight: Int = HEIGHT,
    ): SolidCommandRects.Shape? {
        val rawColor = fragments.lastOrNull { it.header == 0x185f004 }?.data?.singleOrNull() ?: solidColor ?: return null
        val rect = vertices.lastOrNull { it.header == 0x22 }?.data ?: return null
        if (rect.size < 4) return null
        val rectX = rect[0]
        val rectY = rect[1]
        val width = rect[2]
        val height = rect[3]
        if (width !in 1..4096 || height !in 1..4096) return null
        if (rect.size >= 5 && rect[4] != -width) return null

        val drawTarget = fragments.lastOrNull { it.header == 0x1a4f004 }?.data?.singleOrNull()?.toUInt()?.toLong()
            ?: streamState.target
            ?: return null
        if (drawTarget != target) return null

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
        if (abs(m0 * m4 - m1 * m3) <= 1e-9) return null

        val xformEnable = fragments.lastOrNull { it.header == 0x1848004 }?.data?.singleOrNull() ?: 0
        val xformWords = if (xformEnable == 0x01000000) {
            fragments.lastOrNull { it.header == 0x180f01e }?.data?.takeIf { it.size == 4 }
        } else {
            null
        }
        val color = SolidCommandRects.applyDmpColorTransform(rawColor, xformWords)
        // DMP vgClear non-8x8 fallback sets 0x1a04004 = 0 (blender bypass -> unconditional SRC overwrite);
        // solid draws with 0x1a04004 = 0x10000 honor bit 3 of 0x1841004 (8 = SRC_OVER, 0 = SRC).
        val srcOver = (ropEnableWord and 0x10000) != 0 && (blendWord and 8) != 0

        val scissorWords = fragments.lastOrNull { it.header == 0xc0f00e }?.data ?: streamState.scissor
        val scissor = if (scissorWords != null && scissorWords.size == 2) {
            SolidCommandRects.parseScissor(scissorWords, canvasWidth, canvasHeight)
        } else {
            SolidCommandRects.Scissor(0, 0, canvasWidth, canvasHeight)
        }

        val rxD = rectX.toDouble()
        val ryD = rectY.toDouble()
        val w = width.toDouble()
        val h = height.toDouble()
        val ch = canvasHeight.toDouble()
        val originVgX = m6 + m0 * rxD + m3 * ryD
        val originVgY = m7 + m1 * rxD + m4 * ryD
        val c0 = SolidCommandRects.Point(originVgX, ch - originVgY)
        val c1 = SolidCommandRects.Point(originVgX + m0 * w, ch - (originVgY + m1 * w))
        val c2 = SolidCommandRects.Point(originVgX + m0 * w + m3 * h, ch - (originVgY + m1 * w + m4 * h))
        val c3 = SolidCommandRects.Point(originVgX + m3 * h, ch - (originVgY + m4 * h))
        if (m1 == 0.0 && m3 == 0.0) {
            val left = ceil(minOf(c0.x, c1.x) - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
            val right = ceil(maxOf(c0.x, c1.x) - 0.5).toInt().coerceIn(scissor.clipLeft, scissor.clipRight)
            val top = ceil(minOf(c0.y, c3.y) - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            val bottom = ceil(maxOf(c0.y, c3.y) - 0.5).toInt().coerceIn(scissor.clipTop, scissor.clipBottom)
            return SolidCommandRects.Rect(left, top, right, bottom, color, srcOver, canvasWidth)
        }
        return SolidCommandRects.PolygonFill(
            listOf(SolidCommandRects.Subpath(listOf(c0, c1, c2, c3), closed = true)),
            color,
            srcOver,
            scissor,
            canvasWidth,
        )
    }

    private fun paint(draw: Draw, texture: ByteArray, frame: ByteArray, canvasWidth: Int = WIDTH) {
        fun edge(position: Double) = ceil(position - 0.5).toInt()
        val pitchTiles = draw.pitch / 8
        val isSubRectOrOffset = draw.rectX != 0 || draw.rectY != 0 ||
            draw.texWidth != draw.width || draw.texHeight != draw.height || draw.customUv != null
        val exactCopy = !isSubRectOrOffset && draw.isAxisAligned && draw.scaleX == 1.0 && draw.scaleY == 1.0 &&
            draw.left == kotlin.math.floor(draw.left) && draw.top == kotlin.math.floor(draw.top)
        if (isSubRectOrOffset || !draw.isAxisAligned || (draw.linearFilter && !exactCopy)) {
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
        val rxD = draw.rectX.toDouble()
        val ryD = draw.rectY.toDouble()
        val originVgX = m6 + m0 * rxD + m3 * ryD
        val originVgY = m7 + m1 * rxD + m4 * ryD
        val w = draw.width.toDouble()
        val h = draw.height.toDouble()
        val ch = draw.canvasHeight.toDouble()
        val c0x = originVgX; val c0y = ch - originVgY
        val c1x = originVgX + m0 * w; val c1y = ch - (originVgY + m1 * w)
        val c2x = originVgX + m0 * w + m3 * h; val c2y = ch - (originVgY + m1 * w + m4 * h)
        val c3x = originVgX + m3 * h; val c3y = ch - (originVgY + m4 * h)
        val minSx = minOf(minOf(c0x, c1x), minOf(c2x, c3x))
        val maxSx = maxOf(maxOf(c0x, c1x), maxOf(c2x, c3x))
        val minSy = minOf(minOf(c0y, c1y), minOf(c2y, c3y))
        val maxSy = maxOf(maxOf(c0y, c1y), maxOf(c2y, c3y))
        val rowStart = maxOf(ceil(minSy - 0.5).toInt(), draw.scissor.clipTop)
        val rowEnd = minOf(ceil(maxSy - 0.5).toInt(), draw.scissor.clipBottom)
        val colStart = maxOf(ceil(minSx - 0.5).toInt(), draw.scissor.clipLeft)
        val colEnd = minOf(ceil(maxSx - 0.5).toInt(), draw.scissor.clipRight)
        if (rowStart >= rowEnd || colStart >= colEnd) return

        val texW = draw.texWidth
        val texH = draw.texHeight
        val sx0 = colStart + 0.5
        val sy0 = rowStart + 0.5
        val customUv = draw.customUv
        val u0: Double
        val v0: Double
        val duDx: Double
        val dvDx: Double
        val duDy: Double
        val dvDy: Double
        if (customUv != null) {
            val tw = texW.toDouble()
            val th = texH.toDouble()
            val yVg0 = ch - sy0
            duDx = tw * customUv[0]
            dvDx = th * customUv[3]
            duDy = -tw * customUv[1]
            dvDy = -th * customUv[4]
            u0 = tw * (customUv[0] * sx0 + customUv[1] * yVg0 + customUv[2])
            v0 = th * (customUv[3] * sx0 + customUv[4] * yVg0 + customUv[5])
        } else {
            val startDx = sx0 - originVgX
            val startDy = ch - sy0 - originVgY
            duDx = m4 * invDet
            dvDx = -m1 * invDet
            duDy = m3 / det
            dvDy = -m0 / det
            u0 = (m4 * startDx - m3 * startDy) / det
            v0 = (-m1 * startDx + m0 * startDy) / det
        }

        if (draw.linearFilter) {
            FilteredMortonBlit.paint(
                texture, frame, canvasWidth, texW, texH, pitchTiles,
                rowStart, rowEnd, colStart, colEnd,
                u0, v0, duDx, dvDx, duDy, dvDy,
            )
            return
        }
        val startDx = sx0 - originVgX
        for (row in rowStart until rowEnd) {
            var u: Double
            var v: Double
            if (customUv != null) {
                val rowStep = (row - rowStart).toDouble()
                u = u0 + rowStep * duDy
                v = v0 + rowStep * dvDy
            } else {
                val dy = (ch - (row + 0.5)) - originVgY
                u = (m4 * startDx - m3 * dy) * invDet
                v = (-m1 * startDx + m0 * dy) * invDet
            }
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
            var texSize: Int? = null
            var texAddr: Long? = null
            var uvMatrix: List<Int>? = null
            var textureFilter = 0
            var imagePaintActive = false
            var solidColor: Int? = null
            var blendWord = 0
            var ropEnableWord = 0x10000
            val extDraws = linkedMapOf<Int, Draw>()
            val extSolids = linkedMapOf<Int, SolidCommandRects.Shape>()
            for ((id, packets) in vertices) {
                val material = materials[materialKey(id)]
                val matrixPacket = packets.lastOrNull { it.header == 0x41f046 }
                val targetPacket = material?.lastOrNull { it.header == 0x1a4f004 }
                val scissorPacket = material?.lastOrNull { it.header == 0xc0f00e }
                val storagePacket = material?.lastOrNull { it.header == 0x152f004 }
                val texSizePacket = material?.lastOrNull { it.header == 0x158f004 }
                val texAddrPacket = material?.lastOrNull { it.header == 0x155f004 }
                val uvPacket = material?.lastOrNull { it.header == 0x10c7046 }
                if (matrixPacket != null || targetPacket != null || scissorPacket != null) {
                    streamState = SolidCommandRects.StreamState(
                        matrix = matrixPacket?.data?.takeIf { it.size == 9 }?.map { Float.fromBits(it).toDouble() } ?: streamState.matrix,
                        target = targetPacket?.data?.singleOrNull()?.toUInt()?.toLong() ?: streamState.target,
                        scissor = scissorPacket?.data ?: streamState.scissor,
                    )
                }
                if (storagePacket != null) texStorage = storagePacket.data.singleOrNull() ?: texStorage
                if (texSizePacket != null) {
                    texSize = texSizePacket.data.singleOrNull() ?: texSize
                } else if (storagePacket != null || texAddrPacket != null) {
                    texSize = null
                }
                if (texAddrPacket != null) texAddr = texAddrPacket.data.singleOrNull()?.toUInt()?.toLong() ?: texAddr
                if (uvPacket != null) {
                    uvMatrix = uvPacket.data
                } else if (matrixPacket != null || texAddrPacket != null) {
                    uvMatrix = null
                }
                if (material == null) continue
                material.lastOrNull { it.header == 0x185f004 }?.data?.singleOrNull()?.let { solidColor = it }
                material.lastOrNull { (it.header ushr 16) == 0x184 && (it.header and 0x1000) != 0 }?.data?.singleOrNull()?.let { blendWord = it }
                material.lastOrNull { (it.header ushr 16) == 0x1a0 && (it.header and 0x4000) != 0 }?.data?.singleOrNull()?.let { ropEnableWord = it }
                val texEnablePacket = material.lastOrNull { (it.header ushr 16) == 0x140 && (it.header and 0x1000) != 0 }
                imagePaintActive = when {
                    texEnablePacket != null -> (texEnablePacket.data.singleOrNull()?.and(0xff) == 2)
                    material.any { it.header == 0x155f004 || it.header == 0x152f004 || it.header == 0x158f004 || it.header == 0x10c7046 } -> true
                    material.any { it.header == 0x185f004 || it.header == 0x16cf3fc } -> false
                    else -> imagePaintActive
                }
                // The image texture unit retains its sampler across delta command groups (0x1531004 / 0x153f004).
                material.lastOrNull { (it.header ushr 16) == TEXTURE_FILTER_REG }?.data?.singleOrNull()?.let { textureFilter = it }
                if (id in candidates) {
                    if (imagePaintActive) {
                        decodeExtended(
                            packets, material, streamState, texStorage, texSize, texAddr, uvMatrix,
                            textureFilter, imagePaintActive = true, effWidth, effHeight,
                        )?.let { extDraws[id] = it }
                    } else {
                        decodeSolidQuad0x22(packets, material, streamState, solidColor, blendWord, ropEnableWord, effWidth, effHeight)
                            ?.let { extSolids[id] = it }
                    }
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
