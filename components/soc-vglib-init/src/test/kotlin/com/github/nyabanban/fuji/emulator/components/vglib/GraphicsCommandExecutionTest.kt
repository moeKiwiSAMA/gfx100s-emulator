package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*
import org.junit.jupiter.api.Test

class GraphicsCommandExecutionTest {
    private val heap = GraphicsMode2Workspace.parse(mapOf("base" to 0x400000, "bytes" to 0x10000))
    private val target = 0x100000L
    private val renderer = GraphicsCommandExecution(heap,target)
    private fun stream(hex: String): ByteArray {
        val values = hex.trim().split(Regex("\\s+")).map { it.toLong(16).toInt() }.toMutableList()
        if (values.size % 2 != 0) values.addAll(listOf(9,0,0))
        return ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()
    }
    private val vertices = stream("""
        85f004 57 41f046 3f800000 0 0 0 bf800000 0 0 44400000 3f800000
        653004 2100 3f004 5 5f004 80000005 63f004 5 5ef004 3f800000 5ff004 0
        40f004 c001000 80f026 60000 0 10 c001000 0 6f004 1 9 07090702 0 22 0 0 8 8 fffffff8
        85f004 58
    """)
    private val fragments = stream("""
        a6f004 57 1a61004 1 1a11004 0 1b01004 b 1b31004 0 c0f00e 03ff0000 02ff0000
        1001004 1 1151004 1 10c7046 3c0000 0 0 0 bc0000 458000 0 0 3f0000
        158f004 80008 1567004 1b0000 152f004 80008 1401004 2 14df004 0 14df004 0
        1404004 210000 155f004 403000 1a04004 10000 1848004 0 1841004 0 1401004 2
        14df004 0 14df004 0 1402004 0 14df004 0 14df004 0 115c004 1000000 1aa1004 0
        1841004 2 1863004 100 1842004 0 1a02004 0 1a21004 0 1a4f004 100000 1a51004 1
        1842004 0 1a74004 0 1158004 1000000 1531004 6 1431004 6 a0f02e 60000 0 10 c001000 0 1000002
        101004 1 a6f004 58
    """)
    private val texture = ByteArray(256)
    private val frame = ByteArray(0x300000)
    private val regions = mutableMapOf(heap.base to vertices, heap.base+0x1000 to fragments, heap.base+0x3000 to texture, target to frame)
    private var writes = 0
    private val memory = object : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(regions.getValue(address).size.toLong() == size && (!write || address == target))
        }
        override fun read(address: Long, destination: ByteBuffer) { destination.put(regions.getValue(address)) }
        override fun write(address: Long, source: ByteBuffer) { source.get(regions.getValue(address)); writes++ }
    }
    private val first = mapOf("address" to heap.base,"countWord" to vertices.size / 8L,"control" to 0x0100ffffL)
    private val second = first + mapOf("address" to heap.base+0x1000,"countWord" to fragments.size / 8L)

    @Test fun samplesMortonWordAndBlendsOverActualPixel() {
        byteArrayOf(128.toByte(),200.toByte(),120,40).copyInto(texture,8)
        val pixel = 4 * 1024
        byteArrayOf(255.toByte(),0,20,80).copyInto(frame,pixel)
        val receipt = renderer.execute(memory,first,second)
        assertContentEquals(byteArrayOf(255.toByte(),100,70,60),frame.copyOfRange(pixel,pixel+4))
        assertEquals(4,frame.count { it != 0.toByte() })
        assertEquals(listOf(87L),receipt["sequences"])
        assertNotEquals(receipt["beforeSha256"],receipt["sha256"])
        assertEquals(receipt,renderer.restore(receipt,first,second))
        assertFails { renderer.restore(receipt + ("target" to heap.base),first,second) }
        assertFails { renderer.restore(receipt + ("sequences" to listOf(1L,1L)),first,second) }
        assertFails { renderer.restore(receipt,first,second + ("address" to heap.base)) }
        assertEquals(1,writes)
    }

    @Test fun samplerSelectsPointOrLinearForScaledAndRotatedImages() {
        val gpu = GraphicsCommandExecution(heap, target, extended = true)
        val geometry = ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN)
        val material = ByteBuffer.wrap(fragments).order(ByteOrder.LITTLE_ENDIAN)
        fun parameter(buffer: ByteBuffer, header: Int): Int =
            (0 until buffer.capacity() step 4).single { buffer.getInt(it) == header } + 4
        val matrix = parameter(geometry, 0x41f046)
        val filter = parameter(material, 0x1531004)
        val otherUnitFilter = parameter(material, 0x1431004)
        // Red beside transparent blue across the first Morton row.
        byteArrayOf(-1, 0, 0, -1, 0, -1, 0, 0).copyInto(texture)
        for (rotated in listOf(false, true)) {
            val transform = if (rotated) floatArrayOf(0f, -2f, 0f, -2f, 0f, 0f, 16f, 768f, 1f)
                else floatArrayOf(2f, 0f, 0f, 0f, -2f, 0f, 0f, 768f, 1f)
            transform.forEachIndexed { index, value -> geometry.putFloat(matrix + 4 * index, value) }
            val pixel = if (rotated) 4 * (1024 + 15) else 4
            for ((mode, alpha) in listOf(0 to 255, 6 to 191)) {
                frame.fill(0)
                material.putInt(filter, mode)
                material.putInt(otherUnitFilter, mode)
                gpu.execute(memory, first, second)
                assertContentEquals(byteArrayOf(alpha.toByte(), 0, 0, -1), frame.copyOfRange(pixel, pixel + 4))
            }
        }
    }

    @Test fun rejectsTruncationTextureAndTargetEscapesBeforeFramebufferWrites() {
        assertFails { renderer.execute(memory,first + ("address" to heap.base-4),second) }
        assertFails { renderer.execute(memory,first,second + ("countWord" to 2620L)) }
        val buffer = ByteBuffer.wrap(fragments).order(ByteOrder.LITTLE_ENDIAN)
        for ((header,invalid) in listOf(0x155f004 to 0x40ff80,0x1a4f004 to 0x100004,0x1567004 to 0,0x10c7046 to 0,0xa6f004 to 0)) {
            val index = (0 until fragments.size step 4).first { buffer.getInt(it) == header } + 4
            val original = buffer.getInt(index)
            buffer.putInt(index,invalid)
            assertFails { renderer.execute(memory,first,second) }
            buffer.putInt(index,original)
        }
        assertEquals(0,writes)
    }

    @Test fun areaSuccessorUsesTheVisibleLabelAndExactPaddedTexture() {
        fun fields(bytes: ByteArray, header: Int, vararg values: Int) {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val offset = (0 until bytes.size step 4).single { buffer.getInt(it) == header }
            values.forEachIndexed { index, value -> buffer.putInt(offset + 4 + 4 * index,value) }
        }
        fields(vertices,0x22,0,0,140,34,-140)
        fields(vertices,0x41f046,0x3fcccccd,0,0,0,0xbfcf0f0f.toInt(),0,0x43dd8000,0x43aa8000,0x3f800000)
        fields(fragments,0x10c7046,0x372492,0,0xbffa49,0,0xb929e4,0x418ccd,0,0,0x3f0000)
        fields(fragments,0x158f004,0x8c0022)
        fields(fragments,0x155f004,0x40a000)
        val geometry = first + mapOf("countWord" to 1751L,"control" to 0xffffL)
        val material = geometry + mapOf("countWord" to 2992L,"address" to heap.base + 0x4000)
        regions[heap.base] = ByteArray(1751 * 8).also { vertices.copyInto(it,it.size - vertices.size) }
        val bindings = ByteArray(2992 * 8).also { fragments.copyInto(it,it.size - fragments.size) }
        regions[material.getValue("address")] = bindings
        regions[0x40a000] = ByteArray(144 * 40 * 4) { -1 }
        for (storage in listOf(0x880028,0x900030,0x980028)) {
            fields(bindings,0x152f004,storage)
            assertFailsWith<IllegalArgumentException> { renderer.execute(memory,geometry,material,true) }
        }
        fields(bindings,0x152f004,0x900028)
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,geometry,material) }
        assertFailsWith<IllegalArgumentException> { renderer.execute(memory,geometry,material + ("countWord" to 2991L),true) }
        assertEquals(0,writes)
        val receipt = renderer.execute(memory,geometry,material,true)
        assertEquals(224 * 55 * 4,frame.count { it == (-1).toByte() })
        assertEquals(receipt,renderer.restore(receipt,geometry,material,true))
    }

    @Test fun dateSuccessorValidatesFourteenPairedTexturesOnExecutionAndRestore() {
        fun repeatDraw(bytes: ByteArray, marker: Int, size: Int): ByteArray {
            val packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val end = (8 until bytes.size step 4).single { packet.getInt(it) == marker }
            val output = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            output.position(size - 14 * end - 8)
            repeat(14) { id ->
                output.putInt(marker).putInt(id).put(bytes,8,end - 8)
            }
            return output.putInt(marker).putInt(14).array()
        }
        val geometry = first + mapOf("countWord" to 1724L,"control" to 0xffffL)
        val material = geometry + mapOf("countWord" to 3411L,"address" to heap.base + 0x4000)
        val geometryStream = repeatDraw(vertices,0x85f004,1724 * 8)
        regions[heap.base] = geometryStream
        val textureAddress = heap.base + 0xc000
        val fragmentWords = ByteBuffer.wrap(fragments).order(ByteOrder.LITTLE_ENDIAN)
        val pointer = (0 until fragments.size step 4).single { fragmentWords.getInt(it) == 0x155f004 } + 4
        fragmentWords.putInt(pointer,textureAddress.toInt())
        texture.fill(-1)
        regions[textureAddress] = texture
        regions[material.getValue("address")] = repeatDraw(fragments,0xa6f004,3411 * 8)
        assertFails { renderer.execute(memory,geometry,material) }
        assertFails { renderer.execute(memory,geometry,material + ("countWord" to 3410L),true) }
        val receipt = renderer.execute(memory,geometry,material,true)
        assertEquals((0L..13L).toList(),receipt["sequences"])
        assertEquals(8 * 8 * 4,frame.count { it == (-1).toByte() })
        assertEquals(receipt,renderer.restore(receipt,geometry,material,true))
        assertFails { renderer.restore(receipt + ("sequences" to (0L..12L).toList()),geometry,material,true) }
        assertEquals(1,writes)
    }

    @Test fun extendedCommandExecutionAcceptsMonochromaticColorDescriptorAbove32768Words() {
        // BUG-V3-3-4: MONOCHROMATIC COLOR (scr=0x015d) submits countWord=32799 (262,392 bytes), exceeding the old 32768 cap.
        val bigHeap = GraphicsMode2Workspace.parse(mapOf("base" to 0x7000000, "bytes" to 0x200000))
        val bigRenderer = GraphicsCommandExecution(bigHeap, target, solidRects = true, extended = true, continuous = true)
        val countWord = 32799L
        val geomAddr = bigHeap.base
        val matAddr = bigHeap.base + 0x80000L
        val texAddr = bigHeap.base + 0x100000L
        val geomStream = ByteArray(countWord.toInt() * 8)
        vertices.copyInto(geomStream, 0)
        val matStream = ByteArray(countWord.toInt() * 8)
        val patchedFragments = fragments.copyOf()
        val fragBuf = ByteBuffer.wrap(patchedFragments).order(ByteOrder.LITTLE_ENDIAN)
        val texPtrOff = (0 until patchedFragments.size step 4).single { fragBuf.getInt(it) == 0x155f004 } + 4
        fragBuf.putInt(texPtrOff, texAddr.toInt())
        patchedFragments.copyInto(matStream, 0)
        val bigTex = ByteArray(256) { -1 }
        val bigFrame = ByteArray(0x300000)
        val bigRegions = mutableMapOf(
            geomAddr to geomStream,
            matAddr to matStream,
            texAddr to bigTex,
            target to bigFrame,
        )
        val bigMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(bigRegions.getValue(address).size.toLong() == size && (!write || address == target))
            }
            override fun read(address: Long, destination: ByteBuffer) { destination.put(bigRegions.getValue(address)) }
            override fun write(address: Long, source: ByteBuffer) { source.get(bigRegions.getValue(address)) }
        }
        val firstBig = mapOf("offset" to 0x10d0L, "address" to geomAddr, "countWord" to countWord, "control" to 0x0100ffffL, "tick" to 1L, "pcVirtual" to 0x0140233cL, "core" to 0L)
        val secondBig = firstBig + mapOf("offset" to 0x10e0L, "address" to matAddr, "countWord" to countWord)
        val receipt = bigRenderer.execute(bigMem, firstBig, secondBig, successor = false)
        assertEquals(listOf(0x57L), receipt["sequences"])
        assertEquals(8 * 8 * 4, bigFrame.count { it == (-1).toByte() })

        // Descriptors above the 131072-word extended ceiling must still fail with an informative message
        val err = assertFailsWith<IllegalArgumentException> {
            bigRenderer.execute(bigMem, firstBig + ("countWord" to 131073L), secondBig, successor = false)
        }
        assertTrue(err.message.orEmpty().contains("131073"), "Expected informative error message, got: ${err.message}")
    }

    @Test fun skipAuditDigestsMatchesStrictPixelsAndEliminatesFourSha256Overhead() {
        val fastZeroSha = "0".repeat(64)
        byteArrayOf(128.toByte(), 200.toByte(), 120, 40).copyInto(texture, 8)
        val pixel = 4 * 1024
        val strictFrame = ByteArray(0x300000)
        val relaxedFrame = ByteArray(0x300000)
        byteArrayOf(255.toByte(), 0, 20, 80).copyInto(strictFrame, pixel)
        byteArrayOf(255.toByte(), 0, 20, 80).copyInto(relaxedFrame, pixel)

        fun memFor(fb: ByteArray): DmaMemory {
            val map = mapOf(heap.base to vertices, heap.base + 0x1000 to fragments, heap.base + 0x3000 to texture, target to fb)
            return object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(map.getValue(address).size.toLong() == size && (!write || address == target))
                }
                override fun read(address: Long, destination: ByteBuffer) { destination.put(map.getValue(address)) }
                override fun write(address: Long, source: ByteBuffer) { source.get(map.getValue(address)) }
            }
        }

        val strictExec = GraphicsCommandExecution(heap, target, continuous = false, skipAuditDigests = false)
        val relaxedExec = GraphicsCommandExecution(heap, target, continuous = false, skipAuditDigests = true)

        val strictReceipt = strictExec.execute(memFor(strictFrame), first, second)
        val relaxedReceipt = relaxedExec.execute(memFor(relaxedFrame), first, second)

        // Bit-exact framebuffer parity
        assertContentEquals(strictFrame, relaxedFrame, "Relaxed non-continuous command execution must match strict framebuffer bit-for-bit")
        assertEquals(strictReceipt["sequences"], relaxedReceipt["sequences"])

        // Strict computes all 4 real SHA-256 digests; relaxed skips all 4 with zero-hash sentinel
        for (k in listOf("beforeSha256", "sha256", "geometrySha256", "materialSha256")) {
            assertNotEquals(fastZeroSha, strictReceipt[k], "Strict mode must compute real $k")
            assertEquals(fastZeroSha, relaxedReceipt[k], "Relaxed mode must skip $k")
        }
        assertEquals(strictReceipt, strictExec.restore(strictReceipt, first, second))
        assertEquals(relaxedReceipt, relaxedExec.restore(relaxedReceipt, first, second))

        // A/B benchmark: strict (4x SHA-256 over 3 MiB before+after + geom + mat) vs relaxed (skipAuditDigests=true)
        val benchStrictMem = memFor(strictFrame)
        val benchRelaxedMem = memFor(relaxedFrame)
        repeat(3) {
            strictExec.execute(benchStrictMem, first, second)
            relaxedExec.execute(benchRelaxedMem, first, second)
        }
        val strictMs = List(9) {
            val s = System.nanoTime(); strictExec.execute(benchStrictMem, first, second); System.nanoTime() - s
        }.sorted()[4] / 1e6
        val relaxedMs = List(9) {
            val s = System.nanoTime(); relaxedExec.execute(benchRelaxedMem, first, second); System.nanoTime() - s
        }.sorted()[4] / 1e6
        println(
            "W11_VGLIB_SHA256_GATE_AB strict4xShaMs=${"%.3f".format(strictMs)} " +
                "relaxedNoShaMs=${"%.3f".format(relaxedMs)} speedup=${"%.2fx".format(strictMs / relaxedMs)}",
        )
        assertTrue(relaxedMs < strictMs, "Expected skipAuditDigests=true ($relaxedMs ms) to be faster than 4x SHA-256 ($strictMs ms)")
    }

    private fun mortonTileLinear(linear: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        val stride = width * 4
        var off = 0
        for (top in 0 until height step 8) {
            for (left in 0 until width step 8) {
                val base = top * stride + (left shl 2)
                for (m in 0 until 64) {
                    val mc = (m and 1) + ((m ushr 1) and 2) + ((m ushr 2) and 4)
                    val mr = ((m ushr 1) and 1) + ((m ushr 2) and 2) + ((m ushr 3) and 4)
                    val s = base + mr * stride + (mc shl 2)
                    out[off] = linear[s]
                    out[off + 1] = linear[s + 1]
                    out[off + 2] = linear[s + 2]
                    out[off + 3] = linear[s + 3]
                    off += 4
                }
            }
        }
        return out
    }

    @Test fun filteredMortonGuardsOutOfRangeAndNanUvInKotlinAndNative() {
        val tex = ByteArray(256) { if (it % 4 == 0) (-1).toByte() else 0x40 }
        val initFrame = ByteArray(16 * 8 * 4) { 0x5a }
        val badCoords = listOf(-1e20, 1e20, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN)
        for (c in badCoords) {
            val fKot = initFrame.copyOf()
            val fNat = initFrame.copyOf()
            for (allowNative in listOf(false, true)) {
                val dst = if (allowNative) fNat else fKot
                FilteredMortonBlit.paint(tex, dst, 16, 8, 8, 1, 0, 8, 0, 16, c, 0.5, 0.5, 0.0, 0.0, 1.0, allowNative)
                FilteredMortonBlit.paint(tex, dst, 16, 8, 8, 1, 0, 8, 0, 16, 0.5, c, 0.5, 0.0, 0.0, 1.0, allowNative)
                FilteredMortonBlit.paint(tex, dst, 16, 8, 8, 1, 0, 8, 0, 16, c, c, 2.33, 0.0, 0.0, 1.0, allowNative)
                FilteredMortonBlit.paint(tex, dst, 16, 8, 8, 1, 0, 8, 0, 16, c, 0.5, 0.5, 0.25, -0.25, 0.5, allowNative)
            }
            assertContentEquals(initFrame, fKot, "Kotlin mutated frame on bad coord=$c")
            assertContentEquals(initFrame, fNat, "Native mutated frame on bad coord=$c")
        }
    }

    @Test fun filteredMorton043xMinificationPreservesThinStrokesAndMatchesNativeBitExact() {
        val w = 32
        val h = 16
        val linear = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                if (x % 3 == 1 && y in 2..13) {
                    // 1-texel-wide vertical white glyph stem
                    linear[o] = (-1).toByte()
                    linear[o + 1] = (-1).toByte()
                    linear[o + 2] = (-1).toByte()
                    linear[o + 3] = (-1).toByte()
                } else {
                    // Transparent blue background: must never bleed color fringe
                    linear[o] = 0
                    linear[o + 1] = (-1).toByte()
                    linear[o + 2] = 0
                    linear[o + 3] = 0
                }
            }
        }
        val morton = mortonTileLinear(linear, w, h)
        val canvasW = 32
        val dstW = 13 // ~32 * 0.43
        val duDx = 1.0 / 0.43

        // 1. Axis-aligned 0.43x horizontal minification over opaque dark-gray background
        val bg = ByteArray(canvasW * 24 * 4)
        for (i in bg.indices step 4) {
            bg[i] = (-1).toByte(); bg[i + 1] = 25; bg[i + 2] = 25; bg[i + 3] = 25
        }
        val fKot = bg.copyOf()
        val fNat = bg.copyOf()
        FilteredMortonBlit.paint(morton, fKot, canvasW, w, h, w / 8, 0, h, 0, dstW, 0.5 * duDx, 0.5, duDx, 0.0, 0.0, 1.0, allowNative = false)
        FilteredMortonBlit.paint(morton, fNat, canvasW, w, h, w / 8, 0, h, 0, dstW, 0.5 * duDx, 0.5, duDx, 0.0, 0.0, 1.0, allowNative = true)
        assertContentEquals(fKot, fNat, "0.43x horizontal minification must match bit-for-bit between Kotlin and native C")

        // Verify every interior destination column over the stem array stayed visibly lit with neutral gray (no dropout, no blue fringe)
        for (x in 0 until dstW) {
            val o = (8 * canvasW + x) * 4
            val b = fKot[o + 1].toInt() and 0xff
            val g = fKot[o + 2].toInt() and 0xff
            val r = fKot[o + 3].toInt() and 0xff
            assertTrue(r >= 55, "Thin vertical stroke dropped out at dst col=$x (r=$r)")
            assertEquals(r, g, "Color fringe detected at dst col=$x")
            assertEquals(r, b, "Blue color fringe detected from transparent neighbor at dst col=$x")
        }

        // 2. 90-degree rotated 0.43x minification + 2D minification parity
        val fRotKot = bg.copyOf()
        val fRotNat = bg.copyOf()
        FilteredMortonBlit.paint(morton, fRotKot, canvasW, w, h, w / 8, 0, dstW, 0, h, 0.5 * duDx, 0.5, 0.0, 1.0, duDx, 0.0, allowNative = false)
        FilteredMortonBlit.paint(morton, fRotNat, canvasW, w, h, w / 8, 0, dstW, 0, h, 0.5 * duDx, 0.5, 0.0, 1.0, duDx, 0.0, allowNative = true)
        assertContentEquals(fRotKot, fRotNat, "90-deg rotated 0.43x minification must match bit-for-bit between Kotlin and native C")

        val f2dKot = bg.copyOf()
        val f2dNat = bg.copyOf()
        FilteredMortonBlit.paint(morton, f2dKot, canvasW, w, h, w / 8, 0, 7, 0, dstW, 0.5 * duDx, 1.15, duDx, 0.0, 0.0, 2.3, allowNative = false)
        FilteredMortonBlit.paint(morton, f2dNat, canvasW, w, h, w / 8, 0, 7, 0, dstW, 0.5 * duDx, 1.15, duDx, 0.0, 0.0, 2.3, allowNative = true)
        assertContentEquals(f2dKot, f2dNat, "2D minification must match bit-for-bit between Kotlin and native C")
    }

    @Test fun oobeLanguageGlyph82x32In96x32MortonStorageClipsPaddingAndMatchesNativeBitExact() {
        val w = 82
        val h = 32
        val pitch = 96
        val linear = ByteArray(pitch * h * 4)
        for (y in 0 until h) {
            for (x in 0 until pitch) {
                val o = (y * pitch + x) * 4
                if (x >= w) {
                    // Opaque magenta sentinel in storage padding cols 82..95
                    linear[o] = (-1).toByte()
                    linear[o + 1] = (-1).toByte()
                    linear[o + 2] = 0
                    linear[o + 3] = (-1).toByte()
                } else if (x in 1..80 && (((x % 8 == 1 || x == 80) && y in 4..27) || (y == 16 && x in 2..79))) {
                    // OOBE language glyph light-gray ink in x=1..80
                    linear[o] = (-1).toByte()
                    linear[o + 1] = 220.toByte()
                    linear[o + 2] = 220.toByte()
                    linear[o + 3] = 220.toByte()
                }
            }
        }
        val morton = mortonTileLinear(linear, pitch, h)
        val canvasW = 320
        val scaleX = 1.5975609756
        val scaleY = 1.59375
        val left = 165
        val right = 296 // 131 px wide
        val top = 10
        val bottom = 61 // 51 px tall
        val u0 = (left + 0.5 - 165.0) / scaleX
        val v0 = (top + 0.5 - 10.0) / scaleY

        val fKot = ByteArray(canvasW * 72 * 4)
        val fNat = ByteArray(canvasW * 72 * 4)
        FilteredMortonBlit.paint(morton, fKot, canvasW, w, h, pitch / 8, top, bottom, left, right, u0, v0, 1.0 / scaleX, 0.0, 0.0, 1.0 / scaleY, allowNative = false)
        FilteredMortonBlit.paint(morton, fNat, canvasW, w, h, pitch / 8, top, bottom, left, right, u0, v0, 1.0 / scaleX, 0.0, 0.0, 1.0 / scaleY, allowNative = true)
        assertContentEquals(fKot, fNat, "OOBE 82x32 in 96x32 Morton blit must match bit-for-bit between Kotlin and native C")

        // Assert zero magenta padding bleed across all rendered pixels and valid right-bound glyph ink
        var litPixels = 0
        for (y in top until bottom) {
            for (x in left until right) {
                val o = (y * canvasW + x) * 4
                val a = fKot[o].toInt() and 0xff
                if (a > 0) {
                    litPixels++
                    assertEquals(220, fKot[o + 1].toInt() and 0xff, "Blue mismatch / padding bleed at ($x,$y)")
                    assertEquals(220, fKot[o + 2].toInt() and 0xff, "Green mismatch / padding bleed at ($x,$y)")
                    assertEquals(220, fKot[o + 3].toInt() and 0xff, "Red mismatch / padding bleed at ($x,$y)")
                }
            }
        }
        assertTrue(litPixels > 500, "Expected glyph strokes to paint >500 pixels, got $litPixels")
        assertTrue((fKot[((top + 25) * canvasW + (left + 128)) * 4].toInt() and 0xff) >= 200, "Expected right-edge x=80 stroke intact")
    }

    @Test fun extendedModeHonorsFullWordFilterReset0x153f004AndLinearBitmaskModes() {
        val gpu = GraphicsCommandExecution(heap, target, extended = true)
        val geometry = ByteBuffer.wrap(vertices).order(ByteOrder.LITTLE_ENDIAN)
        val material = ByteBuffer.wrap(fragments).order(ByteOrder.LITTLE_ENDIAN)
        fun headerOffset(buffer: ByteBuffer, header: Int): Int =
            (0 until buffer.capacity() step 4).single { buffer.getInt(it) == header }
        val matrixOff = headerOffset(geometry, 0x41f046) + 4
        val filterHdrOff = headerOffset(material, 0x1531004)
        val transform = floatArrayOf(2f, 0f, 0f, 0f, -2f, 0f, 0f, 768f, 1f)
        transform.forEachIndexed { idx, value -> geometry.putFloat(matrixOff + 4 * idx, value) }
        byteArrayOf(-1, 0, 0, -1, 0, -1, 0, 0).copyInto(texture)

        // Full-word 0x153f004 = 0 resets to point sampling (alpha=255 at x=1)
        frame.fill(0)
        material.putInt(filterHdrOff, 0x153f004)
        material.putInt(filterHdrOff + 4, 0)
        gpu.execute(memory, first, second)
        assertContentEquals(byteArrayOf(-1, 0, 0, -1), frame.copyOfRange(4, 8))

        // DMP linear bitmask modes 6, 7, 0x26 all enable filtered sampling (alpha=191 at x=1)
        material.putInt(filterHdrOff, 0x1531004)
        for (mode in listOf(6, 7, 0x26)) {
            frame.fill(0)
            material.putInt(filterHdrOff + 4, mode)
            gpu.execute(memory, first, second)
            assertContentEquals(byteArrayOf(191.toByte(), 0, 0, -1), frame.copyOfRange(4, 8))
        }
    }

    private fun f32Bits(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

    private fun encodeFloat24(value: Double): Int {
        val bits = java.lang.Float.floatToRawIntBits(value.toFloat())
        val sign = (bits ushr 31) and 1
        val exp8 = (bits ushr 23) and 0xff
        if (exp8 <= 64) return 0
        val exp7 = exp8 - 64
        if (exp7 >= 0x7f) return (sign shl 23) or 0x7f0000
        val mant16 = (bits ushr 7) and 0xffff
        val round = (bits ushr 6) and 1
        return (sign shl 23) or (((exp7 shl 16) or mant16) + round)
    }

    @Test fun solidVgClear0x22AfterTexturedDrawRendersSolidColorNotStaleTexture() {
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to 0x400000, "bytes" to 0x20000))
        val texAddr = localHeap.base + 0x6000
        val vAddr = localHeap.base
        val mAddr = localHeap.base + 0x2000
        // Fill 8x8 Morton texture with opaque cyan [A=255, B=255, G=200, R=0]
        val cyanTex = ByteArray(256)
        for (i in cyanTex.indices step 4) {
            cyanTex[i] = (-1).toByte()
            cyanTex[i + 1] = (-1).toByte()
            cyanTex[i + 2] = 200.toByte()
            cyanTex[i + 3] = 0
        }

        // Group 0x57: Textured 8x8 0x22 draw at screen [0..8) x [0..8)
        // Group 0x58: DMP vgClear non-8x8 fallback 0x22 = [3, 760, 6, 5, -6] (w=6 <= stale pitch=8!),
        //   0x41f046 = identity [1,0,0, 0,+1,0, 0,0,1], 0x1401004=0, 0x1a04004=0, 0x185f004=0x802060e0
        //   -> OpenVG y in [760..765) => screen rows [3..8), cols [3..9): overlaps [3..8)x[3..8) of cyan texture!
        // Group 0x59: DMP vgClear non-8x8 fallback 0x22 = [20, 740, 13, 9, -13] (w=13 > stale pitch=8!),
        //   0x1401004=0, 0x1a04004=0, 0x185f004=0xff118833 -> screen rows [19..28), cols [20..33)
        val vBuf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        val mBuf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

        // Append Group 0x57 from base vertices/fragments (without trailing 0x58 marker)
        vBuf.put(vertices, 0, vertices.size - 8)
        val patchedFrag57 = fragments.copyOf(fragments.size - 8)
        ByteBuffer.wrap(patchedFrag57).order(ByteOrder.LITTLE_ENDIAN).let { fb ->
            val off = (0 until patchedFrag57.size step 4).single { fb.getInt(it) == 0x155f004 } + 4
            fb.putInt(off, texAddr.toInt())
        }
        mBuf.put(patchedFrag57)

        // Group 0x58: solid vgClear 0x22 (6x5 at x=3, yVg=760)
        vBuf.putInt(0x85f004).putInt(0x58)
            .putInt(0x41f046)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(1f).putFloat(0f)
            .putFloat(0f).putFloat(0f).putFloat(1f)
            .putInt(0x653004).putInt(0x100)
            .putInt(0x40f004).putInt(0x03000400)
            .putInt(0x9).putInt(0x07090702).putInt(0)
            .putInt(0x22).putInt(3).putInt(760).putInt(6).putInt(5).putInt(-6)
            .putInt(0x6f004).putInt(1)
        mBuf.putInt(0xa6f004).putInt(0x58)
            .putInt(0x115c004).putInt(0)
            .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
            .putInt(0x1a4f004).putInt(target.toInt())
            .putInt(0x1401004).putInt(0)
            .putInt(0x185f004).putInt(0x802060e0.toInt())
            .putInt(0x1841004).putInt(8)
            .putInt(0x1848004).putInt(0)
            .putInt(0x1a04004).putInt(0)
            .putInt(0x101004).putInt(1)

        // Group 0x59: solid vgClear 0x22 (13x9 at x=20, yVg=740, width > stale pitch 8)
        vBuf.putInt(0x85f004).putInt(0x59)
            .putInt(0x9).putInt(0x07090702).putInt(0)
            .putInt(0x22).putInt(20).putInt(740).putInt(13).putInt(9).putInt(-13)
            .putInt(0x6f004).putInt(1)
        mBuf.putInt(0xa6f004).putInt(0x59)
            .putInt(0x1401004).putInt(0)
            .putInt(0x185f004).putInt(0xff118833.toInt())
            .putInt(0x1a04004).putInt(0)
            .putInt(0x101004).putInt(1)

        // Terminal marker 0x5a
        vBuf.putInt(0x85f004).putInt(0x5a)
        mBuf.putInt(0xa6f004).putInt(0x5a)
        while (vBuf.position() % 8 != 0) vBuf.putInt(0)
        while (mBuf.position() % 8 != 0) mBuf.putInt(0)
        val vBytes = vBuf.array().copyOf(vBuf.position())
        val mBytes = mBuf.array().copyOf(mBuf.position())

        for (solidEnabled in listOf(true, false)) {
            val fb = ByteArray(0x300000)
            val map = mapOf(vAddr to vBytes, mAddr to mBytes, texAddr to cyanTex, target to fb)
            val mem = object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(map.getValue(address).size.toLong() == size && (!write || address == target))
                }
                override fun read(address: Long, destination: ByteBuffer) { destination.put(map.getValue(address)) }
                override fun write(address: Long, source: ByteBuffer) { source.get(map.getValue(address)) }
            }
            val gDesc = mapOf("address" to vAddr, "countWord" to vBytes.size / 8L, "control" to 0x0100ffffL)
            val mDesc = mapOf("address" to mAddr, "countWord" to mBytes.size / 8L, "control" to 0x0100ffffL)
            val exec = GraphicsCommandExecution(localHeap, target, solidRects = solidEnabled, extended = true)
            val receipt = exec.execute(mem, gDesc, mDesc)
            assertEquals(listOf(0x57L), receipt["sequences"])
            if (solidEnabled) {
                assertEquals(listOf(0x58L, 0x59L), receipt["solidSequences"])
            }
            assertEquals(receipt, exec.restore(receipt, gDesc, mDesc))

            fun px(col: Int, row: Int): List<Int> {
                val o = (row * 1024 + col) * 4
                return listOf(fb[o].toInt() and 0xff, fb[o + 1].toInt() and 0xff, fb[o + 2].toInt() and 0xff, fb[o + 3].toInt() and 0xff)
            }
            // Non-overlapped cyan texture region stays cyan
            assertEquals(listOf(255, 255, 200, 0), px(0, 0))
            assertEquals(listOf(255, 255, 200, 0), px(2, 7))
            assertEquals(listOf(255, 255, 200, 0), px(7, 2))
            // Group 0x58 clear box [3..8] x [3..7] unconditionally overwrites with 0x802060e0 (NOT stale cyan texture)
            for (r in 3..7) {
                for (c in 3..8) {
                    assertEquals(listOf(0x80, 0x20, 0x60, 0xe0), px(c, r), "Mismatch in 6x5 vgClear at ($c,$r)")
                }
            }
            // Bottom-left [0..7] x [760..767] must remain untouched (no upside-down stale texture ghost at (0, 768))
            assertEquals(listOf(0, 0, 0, 0), px(0, 767))
            assertEquals(listOf(0, 0, 0, 0), px(3, 765))
            // Group 0x59 clear box [20..32] x [19..27] renders exact solid 0xff118833 despite w=13 > stale pitch=8
            for (r in 19..27) {
                for (c in 20..32) {
                    assertEquals(listOf(255, 0x11, 0x88, 0x33), px(c, r), "Mismatch in 13x9 vgClear at ($c,$r)")
                }
            }
            assertEquals(listOf(0, 0, 0, 0), px(19, 22))
            assertEquals(listOf(0, 0, 0, 0), px(33, 22))
        }

        // Also verify that a non-image 0x1401004=0 0x22 group without 0x185f004 never blits stale texture
        val vNoColor = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        val mNoColor = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        vNoColor.put(vertices, 0, vertices.size - 8)
            .putInt(0x85f004).putInt(0x58)
            .putInt(0x41f046)
            .putFloat(1f).putFloat(0f).putFloat(0f)
            .putFloat(0f).putFloat(-1f).putFloat(0f)
            .putFloat(40f).putFloat(728f).putFloat(1f)
            .putInt(0x22).putInt(0).putInt(0).putInt(8).putInt(8).putInt(-8)
            .putInt(0x85f004).putInt(0x59)
        mNoColor.put(patchedFrag57)
            .putInt(0xa6f004).putInt(0x58)
            .putInt(0x1401004).putInt(0)
            .putInt(0x101004).putInt(1)
            .putInt(0xa6f004).putInt(0x59)
        while (vNoColor.position() % 8 != 0) vNoColor.putInt(0)
        while (mNoColor.position() % 8 != 0) mNoColor.putInt(0)
        val vNcBytes = vNoColor.array().copyOf(vNoColor.position())
        val mNcBytes = mNoColor.array().copyOf(mNoColor.position())
        val fbNc = ByteArray(0x300000)
        val mapNc = mapOf(vAddr to vNcBytes, mAddr to mNcBytes, texAddr to cyanTex, target to fbNc)
        val memNc = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(mapNc.getValue(address).size.toLong() == size && (!write || address == target))
            }
            override fun read(address: Long, destination: ByteBuffer) { destination.put(mapNc.getValue(address)) }
            override fun write(address: Long, source: ByteBuffer) { source.get(mapNc.getValue(address)) }
        }
        val gNc = mapOf("address" to vAddr, "countWord" to vNcBytes.size / 8L, "control" to 0x0100ffffL)
        val mNc = mapOf("address" to mAddr, "countWord" to mNcBytes.size / 8L, "control" to 0x0100ffffL)
        val receiptNc = GraphicsCommandExecution(localHeap, target, solidRects = true, extended = true).execute(memNc, gNc, mNc)
        assertEquals(listOf(0x57L), receiptNc["sequences"])
        assertEquals(0, fbNc[(42 * 1024 + 42) * 4].toInt() and 0xff, "Stale texture must not render when 0x1401004 != 2")
    }

    @Test fun subRect0x22BlitHonorsNonZeroRectOffsetAndSubImageUvInPointAndLinearModes() {
        val localHeap = GraphicsMode2Workspace.parse(mapOf("base" to 0x400000, "bytes" to 0x20000))
        val vAddr = localHeap.base
        val mAddr = localHeap.base + 0x2000
        val texAddr = localHeap.base + 0x6000
        val fullW = 32
        val fullH = 16
        val srcX = 11
        val srcY = 5
        val subW = 10
        val subH = 6

        fun expectedTexel(tx: Int, ty: Int): List<Int> =
            listOf(255, (tx * 9 + 7) and 0xff, (ty * 15 + 11) and 0xff, (tx * 5 + ty * 3 + 19) and 0xff)

        val linearTex = ByteArray(fullW * fullH * 4)
        for (ty in 0 until fullH) {
            for (tx in 0 until fullW) {
                val o = (ty * fullW + tx) * 4
                if (tx in srcX until (srcX + subW) && ty in srcY until (srcY + subH)) {
                    val c = expectedTexel(tx, ty)
                    linearTex[o] = c[0].toByte()
                    linearTex[o + 1] = c[1].toByte()
                    linearTex[o + 2] = c[2].toByte()
                    linearTex[o + 3] = c[3].toByte()
                } else {
                    // Opaque magenta sentinel outside the [11..20] x [5..10] sub-rectangle
                    linearTex[o] = (-1).toByte()
                    linearTex[o + 1] = (-1).toByte()
                    linearTex[o + 2] = 0
                    linearTex[o + 3] = (-1).toByte()
                }
            }
        }
        val mortonTex = mortonTileLinear(linearTex, fullW, fullH)

        // 1. DMP 0x01379cbc sub-rect encoding:
        //    0x22 = [srcX, srcY, subW, subH, -subW], 0x41f046 = [1,0,0, 0,+1,0, dx-srcX, dyVg-srcY, 1],
        //    0x158f004 = (fullW<<16)|fullH, 0x152f004 = (fullW<<16)|fullH,
        //    0x10c7046 = [1/fullW, 0, (srcX-dx)/fullW, 0, +1/fullH, (srcY-dyVg)/fullH, 0, 0, 1]
        val dmpDx = 43
        val dmpDyVg = 700 // yVg in [700..706) -> screen rows [62..68) (row 67 => yVg=700.5 => ty=srcY=5)
        // 2. Direct top-down [dx, dy, subW, subH, -subW] encoding with top-down matrix [1,0,0, 0,-1,0, 0,768,1]
        //    and sub-image UV offset inside 0x10c7046 -> screen cols [120..130), rows [34..40)
        val topDx = 120
        val topDy = 34

        for (filterMode in listOf(0, 6)) {
            val vBuf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
            val mBuf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)

            // Group 0x60: DMP bottom-up sub-rect blit
            vBuf.putInt(0x85f004).putInt(0x60)
                .putInt(0x41f046)
                .putInt(f32Bits(1f)).putInt(0).putInt(0)
                .putInt(0).putInt(f32Bits(1f)).putInt(0)
                .putInt(f32Bits((dmpDx - srcX).toFloat())).putInt(f32Bits((dmpDyVg - srcY).toFloat())).putInt(f32Bits(1f))
                .putInt(0x9).putInt(0x07090702).putInt(0)
                .putInt(0x22).putInt(srcX).putInt(srcY).putInt(subW).putInt(subH).putInt(-subW)
                .putInt(0x6f004).putInt(1)
            mBuf.putInt(0xa6f004).putInt(0x60)
                .putInt(0xc0f00e).putInt(0x03ff0000).putInt(0x02ff0000)
                .putInt(0x10c7046)
                .putInt(encodeFloat24(1.0 / fullW)).putInt(0).putInt(encodeFloat24((srcX - dmpDx).toDouble() / fullW))
                .putInt(0).putInt(encodeFloat24(1.0 / fullH)).putInt(encodeFloat24((srcY - dmpDyVg).toDouble() / fullH))
                .putInt(0).putInt(0).putInt(encodeFloat24(1.0))
                .putInt(0x158f004).putInt((fullW shl 16) or fullH)
                .putInt(0x152f004).putInt((fullW shl 16) or fullH)
                .putInt(0x1401004).putInt(2)
                .putInt(0x155f004).putInt(texAddr.toInt())
                .putInt(0x1a4f004).putInt(target.toInt())
                .putInt(0x1531004).putInt(filterMode)
                .putInt(0x1431004).putInt(filterMode)
                .putInt(0x101004).putInt(1)

            // Group 0x61: Top-down [dx, dy, subW, subH, -subW] sub-rect blit reusing bound texture
            vBuf.putInt(0x85f004).putInt(0x61)
                .putInt(0x41f046)
                .putInt(f32Bits(1f)).putInt(0).putInt(0)
                .putInt(0).putInt(f32Bits(-1f)).putInt(0)
                .putInt(0).putInt(f32Bits(768f)).putInt(f32Bits(1f))
                .putInt(0x9).putInt(0x07090702).putInt(0)
                .putInt(0x22).putInt(topDx).putInt(topDy).putInt(subW).putInt(subH).putInt(-subW)
                .putInt(0x6f004).putInt(1)
            mBuf.putInt(0xa6f004).putInt(0x61)
                .putInt(0x10c7046)
                .putInt(encodeFloat24(1.0 / fullW)).putInt(0).putInt(encodeFloat24((srcX - topDx).toDouble() / fullW))
                .putInt(0).putInt(encodeFloat24(-1.0 / fullH)).putInt(encodeFloat24((srcY - topDy + 768).toDouble() / fullH))
                .putInt(0).putInt(0).putInt(encodeFloat24(1.0))
                .putInt(0x101004).putInt(1)

            vBuf.putInt(0x85f004).putInt(0x62)
            mBuf.putInt(0xa6f004).putInt(0x62)
            while (vBuf.position() % 8 != 0) vBuf.putInt(0)
            while (mBuf.position() % 8 != 0) mBuf.putInt(0)
            val vBytes = vBuf.array().copyOf(vBuf.position())
            val mBytes = mBuf.array().copyOf(mBuf.position())

            val fb = ByteArray(0x300000)
            val map = mapOf(vAddr to vBytes, mAddr to mBytes, texAddr to mortonTex, target to fb)
            val mem = object : DmaMemory {
                override fun check(address: Long, size: Long, write: Boolean) {
                    require(map.getValue(address).size.toLong() == size && (!write || address == target))
                }
                override fun read(address: Long, destination: ByteBuffer) { destination.put(map.getValue(address)) }
                override fun write(address: Long, source: ByteBuffer) { source.get(map.getValue(address)) }
            }
            val gDesc = mapOf("address" to vAddr, "countWord" to vBytes.size / 8L, "control" to 0x0100ffffL)
            val mDesc = mapOf("address" to mAddr, "countWord" to mBytes.size / 8L, "control" to 0x0100ffffL)
            val exec = GraphicsCommandExecution(localHeap, target, solidRects = true, extended = true)
            val receipt = exec.execute(mem, gDesc, mDesc)
            assertEquals(listOf(0x60L, 0x61L), receipt["sequences"])

            fun px(col: Int, row: Int): List<Int> {
                val o = (row * 1024 + col) * 4
                return listOf(fb[o].toInt() and 0xff, fb[o + 1].toInt() and 0xff, fb[o + 2].toInt() and 0xff, fb[o + 3].toInt() and 0xff)
            }

            // Verify Group 0x60: exact sub-rect texels at [43..52] x [62..67], zero magenta bleed
            for (r in 62 until 68) {
                for (c in dmpDx until (dmpDx + subW)) {
                    val tx = srcX + (c - dmpDx)
                    val ty = srcY + (67 - r)
                    assertEquals(expectedTexel(tx, ty), px(c, r), "DMP sub-rect mismatch at ($c,$r) filter=$filterMode")
                }
            }
            // Verify Group 0x61: exact sub-rect texels at [120..129] x [34..39], zero magenta bleed
            for (r in topDy until (topDy + subH)) {
                for (c in topDx until (topDx + subW)) {
                    val tx = srcX + (c - topDx)
                    val ty = srcY + (r - topDy)
                    assertEquals(expectedTexel(tx, ty), px(c, r), "Top-down sub-rect mismatch at ($c,$r) filter=$filterMode")
                }
            }
            // Verify total lit pixel count equals exact 2 * (10 * 6) = 120 pixels (nothing painted at (0,0) or outside sub-rects)
            var litCount = 0
            for (i in fb.indices step 4) {
                if (fb[i] != 0.toByte()) litCount++
            }
            assertEquals(2 * subW * subH, litCount, "Unexpected painted pixel count for filter=$filterMode")
        }
    }
}
