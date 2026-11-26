package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.test.*
import org.junit.jupiter.api.Test

class LanguageSolidCommandTest {
    private val heapBase = 0x06fe6000L
    private val target = 0x06893000L
    private val heap = GraphicsMode2Workspace.parse(mapOf("base" to heapBase,"bytes" to 0xa00000))
    private val inputs = GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/language-page/heap-slices.bin.gz"))).use { it.readBytes() }
    private val frame = ByteArray(0x300000)
    private val memory = object : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address == target && size == frame.size.toLong() ||
                !write && address >= heapBase && address - heapBase <= inputs.size - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            if (address == target) destination.put(frame)
            else destination.put(inputs,(address - heapBase).toInt(),destination.remaining())
        }
        override fun write(address: Long, source: ByteBuffer) {
            require(address == target)
            source.get(frame)
        }
    }
    private val first = mapOf("address" to 0x06fe6040L, "countWord" to 1501L, "control" to 0x0100ffffL)
    private val second = first + mapOf("address" to 0x07066040L, "countWord" to 2619L)
    private fun pixel(column: Int, row: Int) = frame.slice(4 * (row * 1024 + column) until 4 * (row * 1024 + column + 1))
        .map { it.toInt() and 255 }

    @Test fun rawPairPaintsSelectedRowBeforeItsText() {
        val renderer = GraphicsCommandExecution(heap,target,true)
        val receipt = renderer.execute(memory,first,second)
        assertTrue(0x10030L in receipt["solidSequences"] as List<*>)
        assertEquals(listOf(255,180,180,180),pixel(300,178))
        assertEquals(listOf(255,180,180,180),pixel(1023,244))
        assertNotEquals(pixel(300,178),pixel(300,245))
        assertTrue((186 until 237).any { row -> (165 until 296).any { column -> pixel(column,row) == listOf(255,0,0,0) } })
        assertEquals(11,(receipt["sequences"] as List<*>).size)
        assertEquals(receipt,renderer.restore(receipt,first,second))
        assertFails { GraphicsCommandExecution(heap,target).restore(receipt,first,second) }
    }

    @Test fun openPathSkipsHighlightAndLegacyProfileKeepsPriorOutput() {
        inputs[(0x07107840L - heapBase + 4).toInt()] = 4
        val receipt = GraphicsCommandExecution(heap,target,true).execute(memory,first,second)
        assertFalse(0x10030L in receipt["solidSequences"] as List<*>)
        assertNotEquals(listOf(255,180,180,180),pixel(300,200))
        frame.fill(0)
        val legacy = GraphicsCommandExecution(heap,target).execute(memory,first,second)
        assertFalse("solidSequences" in legacy)
        assertEquals(listOf(0,0,0,0),pixel(300,200))
    }

    @Test fun extendedPairAcceptsSingleGeometryPaddingWordAfterFinalMarker() {
        val longFirst = first + ("countWord" to 1578L)
        val longSecond = second + ("countWord" to 2733L)
        val start = (first.getValue("address") - heapBase).toInt()
        val end = start + 1578 * 8
        val bindings = (second.getValue("address") - heapBase).toInt()
        val vertexPrefix = 151 * 4
        val materialPrefix = (2733 - 2619) * 8
        inputs.copyInto(inputs,start + vertexPrefix,start,start + 1501 * 8)
        inputs.fill(0,start,start + vertexPrefix)
        inputs.copyInto(inputs,bindings + materialPrefix,bindings,bindings + 2619 * 8)
        inputs.fill(0,bindings,bindings + materialPrefix)
        val words = ByteBuffer.wrap(inputs).order(ByteOrder.LITTLE_ENDIAN)
        words.putInt(start,8)
        words.putInt(end - 12,0x85f004)
        words.putInt(end - 8,0x20000)
        val renderer = GraphicsCommandExecution(heap,target,true)
        words.putInt(end - 4,1)
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,longFirst,longSecond) }
        words.putInt(end - 4,0)
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,longFirst,second) }
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,first,longSecond) }
        assertTrue(frame.all { it == 0.toByte() })
        val proof = renderer.execute(memory,longFirst,longSecond)
        assertEquals(proof,renderer.restore(proof,longFirst,longSecond))
        assertEquals(11,(proof["sequences"] as List<*>).size)
        val nextGeometry = longFirst + ("control" to 0xffffL)
        val nextMaterial = longSecond + ("control" to 0xffffL)
        val before = frame.copyOf()
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,nextGeometry,nextMaterial) }
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,nextGeometry,longSecond,true) }
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,nextGeometry,nextMaterial + ("countWord" to 2619L),true) }
        assertContentEquals(before,frame)
        val next = renderer.execute(memory,nextGeometry,nextMaterial,true)
        assertEquals(proof["sha256"],next["beforeSha256"])
        assertEquals(next,renderer.restore(next,nextGeometry,nextMaterial,true))
        assertFailsWith<IllegalArgumentException> { renderer.restore(next,nextGeometry,nextMaterial) }
    }

    @Test fun rgbaSolidExtendedModeRendersArgbSrcOverAndSrcColorsOverZeroTextures() {
        val vOffset = (first.getValue("address") - heapBase).toInt()
        val fOffset = (second.getValue("address") - heapBase).toInt()
        val vBuf = ByteBuffer.wrap(inputs).order(ByteOrder.LITTLE_ENDIAN)
        val fBuf = ByteBuffer.wrap(inputs).order(ByteOrder.LITTLE_ENDIAN)
        // Locate solid group 0x10030 in language-page stream and inspect its exact fragment/vertex bounds
        var pos = fOffset
        val fEnd = fOffset + 2619 * 8
        var solidFragStart = -1
        var solidFragEnd = -1
        while (pos + 8 <= fEnd) {
            if (fBuf.getInt(pos) == 0xa6f004 && fBuf.getInt(pos + 4) == 0x10030) {
                solidFragStart = pos
                pos += 8
            } else if (solidFragStart >= 0 && fBuf.getInt(pos) == 0xa6f004) {
                solidFragEnd = pos
                break
            } else {
                pos += 4
            }
        }
        assertTrue(solidFragStart >= 0 && solidFragEnd > solidFragStart)
        pos = vOffset
        val vEnd = vOffset + 1501 * 8
        var solidVertStart = -1
        var solidVertEnd = -1
        while (pos + 8 <= vEnd) {
            if (vBuf.getInt(pos) == 0x85f004 && vBuf.getInt(pos + 4) == 0x10030) {
                solidVertStart = pos
                pos += 8
            } else if (solidVertStart >= 0 && vBuf.getInt(pos) == 0x85f004) {
                solidVertEnd = pos
                break
            } else {
                pos += 4
            }
        }
        assertTrue(solidVertStart >= 0 && solidVertEnd > solidVertStart)
        val vertUnit = inputs.copyOfRange(solidVertStart + 8, solidVertEnd)
        val fragUnit = inputs.copyOfRange(solidFragStart + 8, solidFragEnd)
        // Build a stream with 60 solid groups (> 45) using colored 0xffdc5a00 (SRC) and 0x800080ff (SRC_OVER)
        val groupCount = 60
        val newV = ByteBuffer.allocate(groupCount * (8 + vertUnit.size) + 8).order(ByteOrder.LITTLE_ENDIAN)
        val newF = ByteBuffer.allocate(groupCount * (8 + fragUnit.size) + 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(groupCount) { g ->
            val vUnit = vertUnit.copyOf()
            val fUnit = fragUnit.copyOf()
            val fb = ByteBuffer.wrap(fUnit).order(ByteOrder.LITTLE_ENDIAN)
            var sawBlend = false
            for (i in 0 until fUnit.size - 4 step 4) {
                when (fb.getInt(i)) {
                    0x185f004 -> fb.putInt(i + 4, when (g) {
                        0 -> 0xffff4020.toInt()
                        1 -> 0x800080ff.toInt()
                        else -> 0x00000000
                    })
                    0x1841004 -> if (!sawBlend) {
                        sawBlend = true
                        fb.putInt(i + 4, if (g == 0) 0 else 8)
                    }
                }
            }
            newV.putInt(0x85f004).putInt(g).put(vUnit)
            newF.putInt(0xa6f004).putInt(g).put(fUnit)
        }
        newV.putInt(0x85f004).putInt(groupCount)
        newF.putInt(0xa6f004).putInt(groupCount)
        newV.array().copyInto(inputs, vOffset)
        newF.array().copyInto(inputs, fOffset)
        val gPair = mapOf("address" to first.getValue("address"), "countWord" to (newV.capacity() / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to second.getValue("address"), "countWord" to (newF.capacity() / 8).toLong(), "control" to 0xffffL)

        // Legacy non-extended solid renderer must reject (>45 rects, 0 draws, non-gray RGB)
        val legacy = GraphicsCommandExecution(heap, target, solidRects = true, extended = false)
        assertFailsWith<IllegalArgumentException> { legacy.execute(memory, gPair, mPair, true) }

        // Extended RGBA solid renderer succeeds with 0 texture draws and blends color accurately
        val ext = GraphicsCommandExecution(heap, target, solidRects = true, extended = true)
        val receipt = ext.execute(memory, gPair, mPair, true)
        assertEquals(0, (receipt["sequences"] as List<*>).size)
        assertEquals(groupCount, (receipt["solidSequences"] as List<*>).size)
        val px = pixel(300, 178) // alpha, B, G, R after blending 0x800080ff (ABGR) over 0xffff4020 (ABGR)
        assertEquals(255, px[0])
        assertTrue(px[1] in 120..135 && px[2] in 92..100 && px[3] in 140..150, "Unexpected blended ABGR bytes: $px")
        assertEquals(receipt, ext.restore(receipt, gPair, mPair, true))
    }

    @Test fun extendedModeRendersCubicMultiSubpathAndDeduplicatesStateReuseStroke() {
        val scratchOffset = 0x050000
        val scratchBase = heapBase + scratchOffset
        val dataBuf = ByteBuffer.wrap(inputs).order(ByteOrder.LITTLE_ENDIAN)

        // Path 1: rounded-top / cubic shape with an inner square hole
        // Subpath 1: MOVE(100, 200), LINE(200, 200), CUBIC(220, 200, 220, 300, 200, 300), LINE(100, 300), CLOSE
        // Subpath 2 (hole): MOVE(130, 230), LINE(170, 230), LINE(170, 270), LINE(130, 270), CLOSE
        val fillOps = byteArrayOf(2, 4, 12, 4, 0, 2, 4, 4, 4, 0)
        val fillCoords = floatArrayOf(
            100f, 200f,
            200f, 200f,
            220f, 200f, 220f, 300f, 200f, 300f,
            100f, 300f,
            130f, 230f,
            170f, 230f,
            170f, 270f,
            130f, 270f,
        )
        val fillOpsAddr = scratchBase
        fillOps.copyInto(inputs, scratchOffset)
        val fillCoordsOff = scratchOffset + 64
        val fillCoordsAddr = heapBase + fillCoordsOff
        fillCoords.forEachIndexed { i, f -> dataBuf.putFloat(fillCoordsOff + i * 4, f) }

        // Path 2 (stroke L-corner to test join deduplication): MOVE(300, 200), LINE(360, 200), LINE(360, 260)
        val strokeOps = byteArrayOf(2, 4, 4)
        val strokeCoords = floatArrayOf(300f, 200f, 360f, 200f, 360f, 260f)
        val strokeOpsOff = scratchOffset + 256
        val strokeOpsAddr = heapBase + strokeOpsOff
        strokeOps.copyInto(inputs, strokeOpsOff)
        val strokeCoordsOff = scratchOffset + 320
        val strokeCoordsAddr = heapBase + strokeCoordsOff
        strokeCoords.forEachIndexed { i, f -> dataBuf.putFloat(strokeCoordsOff + i * 4, f) }

        fun buildStream(includePredecessorFill: Boolean): Pair<ByteArray, ByteArray> {
            val v = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
            val m = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
            var gid = 0x10000
            if (includePredecessorFill) {
                v.putInt(0x85f004).putInt(gid)
                    .putInt(0x5ef00e).putInt(0x3f800000).putInt(0)
                    .putInt(0x63f004).putInt(fillOps.size)
                    .putInt(0x40f004).putInt(0xc001000)
                    .putInt(0x40f004).putInt(0xc001000)
                    .putInt(0x41f046)
                    .putFloat(1f).putFloat(0f).putFloat(0f)
                    .putFloat(0f).putFloat(1f).putFloat(0f)
                    .putFloat(0f).putFloat(0f).putFloat(1f)
                    .putInt(0x4f004).putInt(fillCoordsAddr.toInt())
                    .putInt(0x5f004).putInt(0xd0000000.toInt() or fillCoords.size)
                    .putInt(0x2f004).putInt(fillOpsAddr.toInt())
                    .putInt(0x3f004).putInt(0x10000000 or fillOps.size)
                    .putInt(0x653004).putInt(0x2100)
                    .putInt(0x66f004).putInt(0x6004)
                    .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
                    .putInt(0x6f004).putInt(1)
                m.putInt(0xa6f004).putInt(gid)
                    .putInt(0x1a61004).putInt(1)
                    .putInt(0x1a11004).putInt(0)
                    .putInt(0x1b01004).putInt(11)
                    .putInt(0x1b31004).putInt(0)
                    .putInt(0x1aa1004).putInt(0)
                    .putInt(0x1841004).putInt(0) // SRC
                    .putInt(0x1842004).putInt(0)
                    .putInt(0x1a02004).putInt(0)
                    .putInt(0x1a21004).putInt(0)
                    .putInt(0x1a4f004).putInt(target.toInt())
                    .putInt(0x1a51004).putInt(1)
                    .putInt(0x1842004).putInt(0)
                    .putInt(0x1a74004).putInt(0)
                    .putInt(0x11c8004).putInt(0)
                    .putInt(0x1401004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
                    .putInt(0x1001004).putInt(1)
                    .putInt(0x1151004).putInt(1)
                    .putInt(0x1a04004).putInt(0x10000)
                    .putInt(0x1848004).putInt(0)
                    .putInt(0x1841004).putInt(8)
                    .putInt(0x1401004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0x1402004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0x14df004).putInt(0)
                    .putInt(0x115c004).putInt(0)
                    .putInt(0x185f004).putInt(0xff2060e0.toInt())
                    .putInt(0x1863004).putInt(0x100)
                    .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
                    .putInt(0x101004).putInt(1)
                gid++
            }
            // (4,3) sync group matching real firmware cadence and keeping material stream 8-byte aligned
            v.putInt(0x85f004).putInt(gid)
                .putInt(0x5f004).putInt(0xd0000008.toInt())
                .putInt(0x3f004).putInt(0x10000005)
                .putInt(0x80f026).putInt(0x079e6000).putInt(0).putInt(0x200).putInt(0x2a01000).putInt(0x1f6a0)
                .putInt(0x6f004).putInt(1)
            m.putInt(0xa6f004).putInt(gid)
                .putInt(0x1a61004).putInt(1)
                .putInt(0xa0f02e).putInt(0x079e6000).putInt(0).putInt(0x200).putInt(0x2a01000).putInt(0x258).putInt(0x1040102)
                .putInt(0x101004).putInt(1)
            gid++
            // (10,15) VG_STROKE_PATH group reusing inherited matrix/target/scissor
            v.putInt(0x85f004).putInt(gid)
                .putInt(0x4f004).putInt(strokeCoordsAddr.toInt())
                .putInt(0x5f004).putInt(0xd0000000.toInt() or strokeCoords.size)
                .putInt(0x2f004).putInt(strokeOpsAddr.toInt())
                .putInt(0x3f004).putInt(0x10000000 or strokeOps.size)
                .putInt(0x5af01e).putInt(0).putInt(0).putFloat(4.0f).putFloat(10.0f)
                .putInt(0x654004).putInt(0x110000)
                .putInt(0x653004).putInt(0x2000)
                .putInt(0x66f004).putInt(0x6004)
                .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
                .putInt(0x6f004).putInt(1)
            m.putInt(0xa6f004).putInt(gid)
                .putInt(0x1a61004).putInt(1)
                .putInt(0x1a04004).putInt(0x10000)
                .putInt(0x1848004).putInt(0)
                .putInt(0x1841004).putInt(8) // SRC_OVER
                .putInt(0x1401004).putInt(0)
                .putInt(0x14df004).putInt(0)
                .putInt(0x14df004).putInt(0)
                .putInt(0x1402004).putInt(0)
                .putInt(0x14df004).putInt(0)
                .putInt(0x14df004).putInt(0)
                .putInt(0x115c004).putInt(0)
                .putInt(0x185f004).putInt(0x800000ff.toInt()) // 50% red (0xAABBGGRR)
                .putInt(0x1863004).putInt(0x100)
                .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
                .putInt(0x101004).putInt(1)
            gid++
            v.putInt(0x85f004).putInt(gid)
            m.putInt(0xa6f004).putInt(gid)
            require(m.position() % 8 == 0) { "Material stream length must be 8-byte aligned, was ${m.position()}" }
            val vLen = (v.position() + 7) and 7.inv()
            val vBytes = ByteArray(vLen).also { v.array().copyInto(it, 0, 0, v.position()) }
            val mBytes = v.let { m.array().copyOf(m.position()) }
            return vBytes to mBytes
        }

        val vOff = (first.getValue("address") - heapBase).toInt()
        val fOff = (second.getValue("address") - heapBase).toInt()
        val ext = GraphicsCommandExecution(heap, target, solidRects = true, extended = true)

        // (10,15) without predecessor state must fail closed (no guessed fallback)
        val (badV, badM) = buildStream(includePredecessorFill = false)
        badV.copyInto(inputs, vOff)
        badM.copyInto(inputs, fOff)
        val badG = mapOf("address" to first.getValue("address"), "countWord" to (badV.size / 8).toLong(), "control" to 0xffffL)
        val badMat = mapOf("address" to second.getValue("address"), "countWord" to (badM.size / 8).toLong(), "control" to 0xffffL)
        assertFailsWith<IllegalArgumentException> { ext.execute(memory, badG, badMat, true) }

        // With predecessor (13,34) fill: both cubic multi-subpath fill AND (10,15) stroke render cleanly
        val (goodV, goodM) = buildStream(includePredecessorFill = true)
        goodV.copyInto(inputs, vOff)
        goodM.copyInto(inputs, fOff)
        val gPair = mapOf("address" to first.getValue("address"), "countWord" to (goodV.size / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to second.getValue("address"), "countWord" to (goodM.size / 8).toLong(), "control" to 0xffffL)

        // Pre-fill frame background with opaque black around stroke corner so SRC_OVER blending is deterministic
        for (r in 490..590) for (c in 290..380) {
            val o = 4 * (r * 1024 + c)
            frame[o] = 0xff.toByte()
            frame[o + 1] = 0
            frame[o + 2] = 0
            frame[o + 3] = 0
        }

        val receipt = ext.execute(memory, gPair, mPair, true)
        assertEquals(listOf(0x10000L, 0x10002L), receipt["solidSequences"])
        assertEquals(receipt, ext.restore(receipt, gPair, mPair, true))

        // Screen Y = 768 - y: fill Y in [468..568], hole in [498..538] x [130..170]
        // 0xff2060e0 in 0xAABBGGRR -> [A=255, B=0x20, G=0x60, R=0xe0]
        assertEquals(listOf(255, 0x20, 0x60, 0xe0), pixel(110, 518)) // inside outer fill
        assertEquals(listOf(255, 0x20, 0x60, 0xe0), pixel(208, 518)) // inside right cubic bulge (x=208, mid y=250 -> ~215)
        assertEquals(listOf(0, 0, 0, 0), pixel(150, 518)) // inside inner hole -> unpainted
        assertEquals(listOf(0, 0, 0, 0), pixel(225, 518)) // outside cubic bulge -> unpainted

        // Stroke corner at (360, 768-200=568): overlapping horizontal + vertical segments must blend ONCE (red ~128, NEVER ~192)
        val cornerPx = pixel(360, 568)
        val midHorizPx = pixel(330, 568)
        val midVertPx = pixel(360, 538)
        assertEquals(midHorizPx, cornerPx)
        assertEquals(midVertPx, cornerPx)
        assertEquals(255, cornerPx[0])
        assertTrue(cornerPx[3] in 125..131, "Expected single-blended 50% red at corner join, got $cornerPx")
    }

    @Test fun extendedModeHandlesGroup0PrologueScissorClippingAndSyncGroups() {
        val localHeapBase = 0x06fe6000L
        val localHeapSize = 0x40000L
        val localTargetBase = 0x06893000L
        val localTargetSize = 1024L * 768 * 4
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to localHeapBase, "bytes" to 0xa00000L))
        val localInputs = ByteArray(localHeapSize.toInt())
        val localFrame = ByteArray(localTargetSize.toInt())
        val localMemory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address == localTargetBase && size == localTargetSize ||
                    !write && address >= localHeapBase && address - localHeapBase <= localInputs.size - size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == localTargetBase) destination.put(localFrame)
                else destination.put(localInputs, (address - localHeapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == localTargetBase)
                source.get(localFrame)
            }
        }
        fun localPixel(c: Int, r: Int): List<Int> {
            val o = 4 * (r * 1024 + c)
            return listOf(localFrame[o], localFrame[o + 1], localFrame[o + 2], localFrame[o + 3]).map { it.toInt() and 0xff }
        }
        val vAddr = localHeapBase + 0x1000L
        val mAddr = localHeapBase + 0x2000L
        val coordsAddr = localHeapBase + 0x3000L
        val opsAddr = localHeapBase + 0x3100L

        // Rectangle [0..700] x [0..700] in VG space -> screen [0..700) x [68..768), clipped by inclusive scissor [100..200] x [100..200]
        val fillCoords = floatArrayOf(0f, 0f, 700f, 0f, 700f, 700f, 0f, 700f)
        val fillOps = byteArrayOf(2, 4, 4, 4, 0)
        ByteBuffer.allocate(fillCoords.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            fillCoords.forEach(::putFloat)
        }.array().copyInto(localInputs, (coordsAddr - localHeapBase).toInt())
        fillOps.copyInto(localInputs, (opsAddr - localHeapBase).toInt())

        val v = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN)

        // Group 0: vertex group 0xffff, material group 0 with 8-packet prologue + rect clipped to inclusive [100..200] x [100..200]
        // Scissor inclusive [100..200] -> screen row in (768 - 201)..(768 - 100) = 567 until 668, col in 100 until 201
        v.putInt(0x85f004).putInt(0xffff)
            .putInt(0x5ef00e).putInt(0x3f800000).putInt(0)
            .putInt(0x63f004).putInt(fillOps.size)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x41f046).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putInt(0x4f004).putInt(coordsAddr.toInt())
            .putInt(0x5f004).putInt(0xd0000000.toInt() or fillCoords.size)
            .putInt(0x2f004).putInt(opsAddr.toInt())
            .putInt(0x3f004).putInt(0x10000000 or fillOps.size)
            .putInt(0x653004).putInt(0x2100)
            .putInt(0x66f004).putInt(0x6004)
            .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
            .putInt(0x6f004).putInt(1)
        m.putInt(0xa6f004).putInt(0)
            .putInt(0x14ee004).putInt(0).putInt(0x156e004).putInt(0).putInt(0x1a7b004).putInt(0).putInt(0x11cf004).putInt(0)
            .putInt(0x1471004).putInt(0).putInt(0x1571004).putInt(0).putInt(0x1a08004).putInt(0).putInt(0x1d21004).putInt(0)
            .putInt(0x1a11004).putInt(0)
            .putInt(0x1b01004).putInt(11)
            .putInt(0x1b31004).putInt(0)
            .putInt(0x1aa1004).putInt(0)
            .putInt(0x1841004).putInt(0) // SRC
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a02004).putInt(0)
            .putInt(0x1a21004).putInt(0)
            .putInt(0x1a4f004).putInt(localTargetBase.toInt())
            .putInt(0x1a51004).putInt(1)
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a74004).putInt(0)
            .putInt(0x11c8004).putInt(0)
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0xc0f00e).putInt((200 shl 16) or 100).putInt((200 shl 16) or 100)
            .putInt(0x1001004).putInt(1)
            .putInt(0x1151004).putInt(1)
            .putInt(0x1a04004).putInt(0x10000)
            .putInt(0x1848004).putInt(0)
            .putInt(0x1841004).putInt(8) // SRC_OVER
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x1402004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x115c004).putInt(0)
            .putInt(0x185f004).putInt(0xff112233.toInt())
            .putInt(0x1863004).putInt(0x100)
            .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
            .putInt(0x101004).putInt(1)
        v.putInt(0x85f004).putInt(1)
        m.putInt(0xa6f004).putInt(1)

        // Group 1: sync-only 4-packet group (5f004, 3f004, 80f026, 6f004) must be ignored cleanly
        v.putInt(0x5f004).putInt(1)
            .putInt(0x3f004).putInt(0)
            .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
            .putInt(0x6f004).putInt(1)
        m.putInt(0x1a61004).putInt(1)
            .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
            .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
            .putInt(0x101004).putInt(1)
        v.putInt(0x85f004).putInt(2)
        m.putInt(0xa6f004).putInt(2)

        require(m.position() % 8 == 0) { "Material stream length must be 8-byte aligned, was ${m.position()}" }
        val vBytes = v.array().copyOf((v.position() + 7) and 7.inv())
        val mBytes = m.array().copyOf(m.position())
        vBytes.copyInto(localInputs, (vAddr - localHeapBase).toInt())
        mBytes.copyInto(localInputs, (mAddr - localHeapBase).toInt())

        val ext = GraphicsCommandExecution(localHeap, localTargetBase, solidRects = true, extended = true)
        val gPair = mapOf("address" to vAddr, "countWord" to (vBytes.size / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to mAddr, "countWord" to (mBytes.size / 8).toLong(), "control" to 0xffffL)

        val receipt = ext.execute(localMemory, gPair, mPair, true)
        assertEquals(listOf(0xffffL), receipt["solidSequences"])
        assertEquals(receipt, ext.restore(receipt, gPair, mPair, true))

        assertEquals(listOf(255, 0x11, 0x22, 0x33), localPixel(100, 567))
        assertEquals(listOf(255, 0x11, 0x22, 0x33), localPixel(200, 667))
        assertEquals(listOf(0, 0, 0, 0), localPixel(99, 600))
        assertEquals(listOf(0, 0, 0, 0), localPixel(201, 600))
        assertEquals(listOf(0, 0, 0, 0), localPixel(150, 566))
        assertEquals(listOf(0, 0, 0, 0), localPixel(150, 668))
    }

    @Test fun parseScissorConvertsBottomUpOpenVgYToScreenRows() {
        // Full-screen 0x03ff0000 / 0x02ff0000 -> [0, 1024) x [0, 768)
        assertEquals(
            SolidCommandRects.Scissor(0, 0, 1024, 768),
            SolidCommandRects.parseScissor(listOf(0x03ff0000, 0x02ff0000)),
        )
        // GFX100S 2.13 Area Selection (scr=0x0423) base world map scissor: 0x03ef0066 / 0x02940108
        // x=[102..1007], OpenVG bottom-up y=[264..660] -> screen top-down y=[107..503] => [107, 504)
        assertEquals(
            SolidCommandRects.Scissor(102, 107, 1008, 504),
            SolidCommandRects.parseScissor(listOf(0x03ef0066, 0x02940108)),
        )
        // Inclusive [100..200] x [100..200] -> screen [100, 201) x [567, 668)
        assertEquals(
            SolidCommandRects.Scissor(100, 567, 201, 668),
            SolidCommandRects.parseScissor(listOf((200 shl 16) or 100, (200 shl 16) or 100)),
        )
        // Inverted empty vertical range (yMax < yMin) produces empty clip
        val empty = SolidCommandRects.parseScissor(listOf(0x03ff0000, (100 shl 16) or 200))
        assertEquals(empty.clipTop, empty.clipBottom)
    }

    @Test fun finalDrawBlendRegisterOverridesPrologueBlendForTransparentBoundsRect() {
        // Firmware emits 0x1841004=0 in the setup prologue right after a textured draw, then
        // writes 0x1841004=8 (SRC_OVER) immediately before the draw trigger (e.g. Area Setting left arrow).
        // A transparent 0x00000000 bounding rect with final 0x1841004=8 must preserve existing pixels.
        val localHeapBase = 0x06fe6000L
        val localTargetBase = 0x06893000L
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to localHeapBase, "bytes" to 0xa00000))
        val localInputs = ByteArray(0x100000)
        val localFrame = ByteArray(0x300000)
        val localMemory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require((address == localTargetBase && size == localFrame.size.toLong()) ||
                    (!write && address >= localHeapBase && address - localHeapBase <= localInputs.size - size))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == localTargetBase) destination.put(localFrame)
                else destination.put(localInputs, (address - localHeapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == localTargetBase)
                source.get(localFrame)
            }
        }
        fun localPixel(col: Int, row: Int) =
            localFrame.slice(4 * (row * 1024 + col) until 4 * (row * 1024 + col + 1)).map { it.toInt() and 255 }

        // Pre-fill framebuffer with opaque light-gray highlight bar (ABGR: ff, b4, b4, b4)
        for (i in localFrame.indices step 4) {
            localFrame[i] = 0xff.toByte()
            localFrame[i + 1] = 0xb4.toByte()
            localFrame[i + 2] = 0xb4.toByte()
            localFrame[i + 3] = 0xb4.toByte()
        }

        val opsAddr = localHeapBase + 0x040000L
        val coordsAddr = localHeapBase + 0x040100L
        val vAddr = localHeapBase + 0x000040L
        val mAddr = localHeapBase + 0x020040L
        val ops = byteArrayOf(2, 4, 4, 4, 0)
        val coords = floatArrayOf(720f, 226f, 742f, 226f, 742f, 274f, 720f, 274f)
        val dataBuf = ByteBuffer.wrap(localInputs).order(ByteOrder.LITTLE_ENDIAN)
        coords.forEachIndexed { idx, value -> dataBuf.putFloat((coordsAddr - localHeapBase).toInt() + idx * 4, value) }
        ops.copyInto(localInputs, (opsAddr - localHeapBase).toInt())

        val v = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        v.putInt(0x85f004).putInt(0x1003c)
            .putInt(0x5ef00e).putInt(0x3f800000).putInt(0)
            .putInt(0x63f004).putInt(ops.size)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x41f046).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putInt(0x4f004).putInt(coordsAddr.toInt())
            .putInt(0x5f004).putInt(0xd0000000.toInt() or coords.size)
            .putInt(0x2f004).putInt(opsAddr.toInt())
            .putInt(0x3f004).putInt(0x10000000 or ops.size)
            .putInt(0x653004).putInt(0x2100)
            .putInt(0x66f004).putInt(0x6004)
            .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
            .putInt(0x6f004).putInt(1)
            .putInt(0x85f004).putInt(0x1003d)
        m.putInt(0xa6f004).putInt(0x1003c)
            .putInt(0x1a61004).putInt(1)
            .putInt(0x1a11004).putInt(0)
            .putInt(0x1b01004).putInt(11)
            .putInt(0x1b31004).putInt(0)
            .putInt(0x1aa1004).putInt(0)
            .putInt(0x1841004).putInt(0) // stale prologue value
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a02004).putInt(0)
            .putInt(0x1a21004).putInt(0)
            .putInt(0x1a4f004).putInt(localTargetBase.toInt())
            .putInt(0x1a51004).putInt(1)
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a74004).putInt(0)
            .putInt(0x11c8004).putInt(0)
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
            .putInt(0x1001004).putInt(1)
            .putInt(0x1151004).putInt(1)
            .putInt(0x1a04004).putInt(0x10000)
            .putInt(0x1848004).putInt(0)
            .putInt(0x1841004).putInt(8) // authoritative draw blend = SRC_OVER
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x1402004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x115c004).putInt(0)
            .putInt(0x185f004).putInt(0x00000000) // transparent bounding rect
            .putInt(0x1863004).putInt(0x100)
            .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
            .putInt(0x101004).putInt(1)
            .putInt(0xa6f004).putInt(0x1003d)

        val vBytes = v.array().copyOf((v.position() + 7) and 7.inv())
        val mBytes = m.array().copyOf(m.position())
        vBytes.copyInto(localInputs, (vAddr - localHeapBase).toInt())
        mBytes.copyInto(localInputs, (mAddr - localHeapBase).toInt())

        val ext = GraphicsCommandExecution(localHeap, localTargetBase, solidRects = true, extended = true)
        val gPair = mapOf("address" to vAddr, "countWord" to (vBytes.size / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to mAddr, "countWord" to (mBytes.size / 8).toLong(), "control" to 0xffffL)
        ext.execute(localMemory, gPair, mPair, true)

        // Underlying light-gray highlight bar MUST remain intact (not overwritten with black 0x00000000).
        assertEquals(listOf(255, 0xb4, 0xb4, 0xb4), localPixel(730, 518))
    }

    @Test fun extendedModeRendersLinearGradientRectWith24BitPaintMatrixAndStrokeCapJoin() {
        val localHeapBase = 0x06fe6000L
        val localTargetBase = 0x06893000L
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to localHeapBase, "bytes" to 0xa00000))
        val localInputs = ByteArray(0x100000)
        val localFrame = ByteArray(0x300000)
        val localMemory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require((address == localTargetBase && size == localFrame.size.toLong()) ||
                    (!write && address >= localHeapBase && address - localHeapBase <= localInputs.size - size))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == localTargetBase) destination.put(localFrame)
                else destination.put(localInputs, (address - localHeapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == localTargetBase)
                source.get(localFrame)
            }
        }
        fun localPixel(col: Int, row: Int) =
            localFrame.slice(4 * (row * 1024 + col) until 4 * (row * 1024 + col + 1)).map { it.toInt() and 255 }

        // Pre-fill framebuffer with opaque black (simulating Q-Menu active tile outer frame 0x10064).
        for (i in localFrame.indices step 4) {
            localFrame[i] = 0xff.toByte()
            localFrame[i + 1] = 0
            localFrame[i + 2] = 0
            localFrame[i + 3] = 0
        }

        val opsAddr = localHeapBase + 0x040000L
        val coordsAddr = localHeapBase + 0x040100L
        val strokeOpsAddr = localHeapBase + 0x040200L
        val strokeCoordsAddr = localHeapBase + 0x040300L
        val vAddr = localHeapBase + 0x000040L
        val mAddr = localHeapBase + 0x020040L
        val ops = byteArrayOf(2, 4, 4, 4, 0)
        // Tile rect in OpenVG bottom-up coordinates: x=[260..500], y=[560..668] -> screen y=[100..208]
        val coords = floatArrayOf(260f, 560f, 500f, 560f, 500f, 668f, 260f, 668f)
        val strokeOps = byteArrayOf(2, 4)
        val strokeCoords = floatArrayOf(280f, 614f, 480f, 614f)
        val dataBuf = ByteBuffer.wrap(localInputs).order(ByteOrder.LITTLE_ENDIAN)
        coords.forEachIndexed { idx, value -> dataBuf.putFloat((coordsAddr - localHeapBase).toInt() + idx * 4, value) }
        ops.copyInto(localInputs, (opsAddr - localHeapBase).toInt())
        strokeCoords.forEachIndexed { idx, value -> dataBuf.putFloat((strokeCoordsAddr - localHeapBase).toInt() + idx * 4, value) }
        strokeOps.copyInto(localInputs, (strokeOpsAddr - localHeapBase).toInt())

        // Exact GFX100S Q-Menu active tile linear gradient (0x10068): silver ramp 0xffdcdcdc -> 0xffc1c1c1
        val v = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        v.putInt(0x85f004).putInt(0xffff)
            .putInt(0x41f046).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putInt(0x5ef00e).putInt(0x3f800000).putInt(0)
            .putInt(0x63f004).putInt(ops.size)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x40f004).putInt(0xc001000)
            .putInt(0x41f046).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
            .putInt(0x4f004).putInt(coordsAddr.toInt())
            .putInt(0x5f004).putInt(0xd0000000.toInt() or coords.size)
            .putInt(0x2f004).putInt(opsAddr.toInt())
            .putInt(0x3f004).putInt(0x10000000 or ops.size)
            .putInt(0x653004).putInt(0x2100)
            .putInt(0x66f004).putInt(0x6004)
            .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
            .putInt(0x6f004).putInt(1)
        // Follow with a state-reuse stroke using 0x654004 = 0x10000 (ROUND cap / MITER join, Q-Menu icon outline)
        v.putInt(0x85f004).putInt(0x10001)
            .putInt(0x4f004).putInt(strokeCoordsAddr.toInt())
            .putInt(0x5f004).putInt(0xd0000000.toInt() or strokeCoords.size)
            .putInt(0x2f004).putInt(strokeOpsAddr.toInt())
            .putInt(0x3f004).putInt(0x10000000 or strokeOps.size)
            .putInt(0x5af01e).putInt(0).putInt(0).putFloat(2.0f).putFloat(4.0f)
            .putInt(0x654004).putInt(0x10000)
            .putInt(0x653004).putInt(0x2000)
            .putInt(0x66f004).putInt(0x6004)
            .putInt(0x80f026).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0)
            .putInt(0x6f004).putInt(1)
            .putInt(0x85f004).putInt(0x10002)

        m.putInt(0xa6f004).putInt(0)
            // group0Prologue (8 packets)
            .putInt(0x14ee004).putInt(0)
            .putInt(0x156e004).putInt(0)
            .putInt(0x1a7b004).putInt(0)
            .putInt(0x11cf004).putInt(0)
            .putInt(0x1471004).putInt(0)
            .putInt(0x1571004).putInt(0)
            .putInt(0x1a08004).putInt(0)
            .putInt(0x1d21004).putInt(0)
            // gradientMaterial[1..40]
            .putInt(0x1a11004).putInt(0)
            .putInt(0x1b01004).putInt(11)
            .putInt(0x1b31004).putInt(0)
            .putInt(0x1aa1004).putInt(0)
            .putInt(0x1841004).putInt(0)
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a02004).putInt(0)
            .putInt(0x1a21004).putInt(0)
            .putInt(0x1a4f004).putInt(localTargetBase.toInt())
            .putInt(0x1a51004).putInt(1)
            .putInt(0x1842004).putInt(0)
            .putInt(0x1a74004).putInt(0)
            .putInt(0x11c8004).putInt(0)
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
            .putInt(0x1001004).putInt(1)
            .putInt(0x1151004).putInt(1)
            .putInt(0x1a04004).putInt(0x10000)
            .putInt(0x1848004).putInt(0)
            .putInt(0x1841004).putInt(0) // VG_BLEND_SRC replace
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x1402004).putInt(0x400)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x115c004).putInt(0x10000)
            // Exact captured 0x1037046 24-bit float inverse paint matrix from active tile 0x10068
            .putInt(0x1037046)
            .putInt(0xa12ce2).putInt(0).putInt(0x282ec7)
            .putInt(0).putInt(0x383783).putInt(0xc155e0)
            .putInt(0).putInt(0).putInt(0x3f0000)
            .putInt(0x168f016).putInt(0).putInt(0).putInt(0)
            .putInt(0x16df004).putInt(0x103f0000)
            .putInt(0x14e1004).putInt(0x20)
            .putInt(0x16bf004).putInt(0)
            .putInt(0x16cf3fc)
        for (i in 0 until 128) {
            val shade = 0xdc - ((0xdc - 0xc1) * i) / 127
            m.putInt((0xff shl 24) or (shade shl 16) or (shade shl 8) or shade)
        }
        m.putInt(0x16bf004).putInt(0x10000).putInt(0x16cf3fc)
        for (i in 0 until 128) m.putInt(if (i == 0) 0xffffff else 0)
        m.putInt(0x1863004).putInt(0x100)
            .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040002)
            .putInt(0x101004).putInt(1)
        // Stroke material for 0x10001 (opaque black stroke across center of active tile)
        m.putInt(0xa6f004).putInt(0x10001)
            .putInt(0x1a61004).putInt(1)
            .putInt(0x1a04004).putInt(0x10000)
            .putInt(0x1848004).putInt(0)
            .putInt(0x1841004).putInt(8)
            .putInt(0x1401004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x1402004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x14df004).putInt(0)
            .putInt(0x115c004).putInt(0)
            .putInt(0x185f004).putInt(0xff000000.toInt())
            .putInt(0x1863004).putInt(0x100)
            .putInt(0xa0f02e).putInt(0x07c3e000).putInt(0).putInt(0x200).putInt(0x9601000).putInt(0).putInt(0x1040102)
            .putInt(0x101004).putInt(1)
            .putInt(0xa6f004).putInt(0x10002)

        val vBytes = v.array().copyOf((v.position() + 7) and 7.inv())
        val mBytes = m.array().copyOf((m.position() + 7) and 7.inv())
        vBytes.copyInto(localInputs, (vAddr - localHeapBase).toInt())
        mBytes.copyInto(localInputs, (mAddr - localHeapBase).toInt())

        val ext = GraphicsCommandExecution(localHeap, localTargetBase, solidRects = true, extended = true)
        val gPair = mapOf("address" to vAddr, "countWord" to (vBytes.size / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to mAddr, "countWord" to (mBytes.size / 8).toLong(), "control" to 0xffffL)
        val receipt = ext.execute(localMemory, gPair, mPair, true)
        assertEquals(listOf(0xffffL, 0x10001L), receipt["solidSequences"])
        assertEquals(receipt, ext.restore(receipt, gPair, mPair, true))

        // Top of tile (row 102, yVg=665.5 -> t~1.0 -> lut[127]=0xffc1c1c1)
        val topPx = localPixel(380, 102)
        assertEquals(255, topPx[0])
        assertTrue(topPx[1] in 0xc0..0xc4 && topPx[2] == topPx[1] && topPx[3] == topPx[1], "Expected top silver gradient ~0xc1, got $topPx")
        // Bottom of tile (row 205, yVg=562.5 -> t~0.0 -> lut[0]=0xffdcdcdc)
        val botPx = localPixel(380, 205)
        assertEquals(255, botPx[0])
        assertTrue(botPx[1] in 0xd8..0xdc && botPx[2] == botPx[1] && botPx[3] == botPx[1], "Expected bottom silver gradient ~0xdc, got $botPx")
        // Stroke line across center (row 154, yVg=614) renders opaque black over the silver gradient
        assertEquals(listOf(255, 0, 0, 0), localPixel(380, 154))
        // Outside tile stays untouched black
        assertEquals(listOf(255, 0, 0, 0), localPixel(250, 150))
    }

    @Test fun extendedModeDecodesTextured0x22WithGroup0PrologueAndStateReuseWithoutFreezing() {
        // BUG-A1-1: Extended DMP OpenVG mode must decode textured 0x22 draws even when:
        // 1. Group 0 starts with the 8-packet group0Prologue (0x14ee004..0x1d21004)
        // 2. Subsequent 0x22 textured groups omit unchanged state packets (reusing target/scissor/texture)
        // 3. Non-extended strict mode still rejects non-standard inline draw packet lists.
        val localHeapBase = 0x06fe6000L
        val localTargetBase = 0x06893000L
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to localHeapBase, "bytes" to 0xa00000))
        val localInputs = ByteArray(0x100000)
        val localFrame = ByteArray(0x300000)
        val localMemory = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require((address == localTargetBase && size == localFrame.size.toLong()) ||
                    (!write && address >= localHeapBase && address - localHeapBase <= localInputs.size - size))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == localTargetBase) destination.put(localFrame)
                else destination.put(localInputs, (address - localHeapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == localTargetBase)
                source.get(localFrame)
            }
        }
        fun localPixel(col: Int, row: Int) =
            localFrame.slice(4 * (row * 1024 + col) until 4 * (row * 1024 + col + 1)).map { it.toInt() and 255 }

        // 8x8 opaque cyan Morton texture at heapBase + 0x40000 (ABGR = ff, ee, cc, 22)
        val texAddr = localHeapBase + 0x40000L
        val texOff = (texAddr - localHeapBase).toInt()
        for (i in 0 until 64) {
            localInputs[texOff + i * 4] = 0xff.toByte()
            localInputs[texOff + i * 4 + 1] = 0xee.toByte()
            localInputs[texOff + i * 4 + 2] = 0xcc.toByte()
            localInputs[texOff + i * 4 + 3] = 0x22.toByte()
        }

        val vAddr = localHeapBase + 0x000040L
        val mAddr = localHeapBase + 0x020040L
        val v = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        val m = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        // Group 0 (0xffff / 0): textured 0x22 draw at left=100, top=768-668=100, size=8x8, WITH group0Prologue
        v.putInt(0x85f004).putInt(0xffff)
            .putInt(0x41f046)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(-1f).putFloat(0f)
            .putFloat(100f).putFloat(668f).putFloat(1f)
            .putInt(0x22).putInt(0).putInt(0).putInt(8).putInt(8).putInt(-8)
        m.putInt(0xa6f004).putInt(0)
            .putInt(0x14ee004).putInt(0).putInt(0x156e004).putInt(0).putInt(0x1a7b004).putInt(0).putInt(0x11cf004).putInt(0)
            .putInt(0x1471004).putInt(0).putInt(0x1571004).putInt(0).putInt(0x1a08004).putInt(0).putInt(0x1d21004).putInt(0)
            .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
            .putInt(0x158f004).putInt((8 shl 16) or 8)
            .putInt(0x152f004).putInt((8 shl 16) or 8)
            .putInt(0x155f004).putInt(texAddr.toInt())
            .putInt(0x1a4f004).putInt(localTargetBase.toInt())
            .putInt(0x101004).putInt(1)

        // Group 1 (0x10001): state-reusing textured 0x22 draw at left=200, top=768-568=200, omitting target/scissor/texAddr/texStorage!
        v.putInt(0x85f004).putInt(0x10001)
            .putInt(0x41f046)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(-1f).putFloat(0f)
            .putFloat(200f).putFloat(568f).putFloat(1f)
            .putInt(0x22).putInt(0).putInt(0).putInt(8).putInt(8).putInt(-8)
        m.putInt(0xa6f004).putInt(0x10001)
            .putInt(0x158f004).putInt((8 shl 16) or 8)
            .putInt(0x101004).putInt(1)

        // Terminal marker
        v.putInt(0x85f004).putInt(0x10002)
        m.putInt(0xa6f004).putInt(0x10002)

        val vBytes = v.array().copyOf((v.position() + 7) and 7.inv())
        val mBytes = m.array().copyOf((m.position() + 7) and 7.inv())
        vBytes.copyInto(localInputs, (vAddr - localHeapBase).toInt())
        mBytes.copyInto(localInputs, (mAddr - localHeapBase).toInt())

        val gPair = mapOf("address" to vAddr, "countWord" to (vBytes.size / 8).toLong(), "control" to 0xffffL)
        val mPair = mapOf("address" to mAddr, "countWord" to (mBytes.size / 8).toLong(), "control" to 0xffffL)

        // Non-extended strict renderer must still reject
        val strict = GraphicsCommandExecution(localHeap, localTargetBase, solidRects = true, extended = false)
        assertFailsWith<IllegalArgumentException> { strict.execute(localMemory, gPair, mPair, true) }

        // Extended renderer succeeds cleanly, blitting both group-0 prologue and state-reused 0x22 draws
        val ext = GraphicsCommandExecution(localHeap, localTargetBase, solidRects = true, extended = true)
        val receipt = ext.execute(localMemory, gPair, mPair, true)
        assertEquals(listOf(0xffffL, 0x10001L), receipt["sequences"])
        assertEquals(receipt, ext.restore(receipt, gPair, mPair, true))
        assertEquals(listOf(0xff, 0xee, 0xcc, 0x22), localPixel(104, 104))
        assertEquals(listOf(0xff, 0xee, 0xcc, 0x22), localPixel(204, 204))
        assertEquals(listOf(0, 0, 0, 0), localPixel(150, 150))
    }
}
