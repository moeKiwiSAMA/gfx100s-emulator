package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for GFX100S real-silicon display fixes:
 * - BUG-03: LCDC1 dual-panel routing (1024x768 Rear LCD <-> 1280x960 EVF OLED) + 1280x960 VGLIB fill/copy/OSD0 canvas.
 * - BUG-06: LCDC1 PIC1 non-4:3 aspect-ratio output clip extent (+0x170) validation across all 7 IMAGE SIZE modes.
 * - BUG-09: Unwritten zero-filled UYVY DRAM pillarbox/letterbox margins render neutral black instead of BT.601 green.
 */
class EvfPic1PillarboxFixTest {
    private val lcdOsdAddr = 0x0614_0000L
    private val evfOsdAddr = 0x0689_3000L
    private val pic1Addr = 0x0896_0000L
    private val lcdOsdBytes = 1024 * 768 * 4
    private val evfOsdBytes = 1280 * 960 * 4
    private val pic1MaxBytes = 1280 * 960 * 2

    private fun setupDualDisplay(): Triple<LcdcFunctionalTest.Context, Pair<ArgbFrameBuffer, ArgbFrameBuffer>, Triple<ByteBuffer, ByteBuffer, ByteArray>> {
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val evf = ArgbFrameBuffer(1280, 960)
        val lcdOsd = ByteBuffer.allocate(lcdOsdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val evfOsd = ByteBuffer.allocate(evfOsdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val pic1 = ByteArray(pic1MaxBytes)
        // Neutral white YYUV422 ([Y0, Y1, Cb, Cr]) across PIC1
        for (i in 0 until pic1MaxBytes step 4) {
            pic1[i] = 0xff.toByte()
            pic1[i + 1] = 0xff.toByte()
            pic1[i + 2] = 0x80.toByte()
            pic1[i + 3] = 0x80.toByte()
        }
        // LCD OSD0: opaque red at (0,0)
        lcdOsd.putInt(0, 0xff0000ff.toInt())
        // EVF OSD0: opaque blue at (0,0) and opaque green at (1279,959)
        evfOsd.putInt(0, 0x0000ffff)
        evfOsd.putInt((959 * 1280 + 1279) * 4, 0x00ff00ff)

        ctx.entries["display.main"] = main
        ctx.entries["display.evf"] = evf
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                val valid = (address == lcdOsdAddr && size <= lcdOsdBytes.toLong()) ||
                    (address == evfOsdAddr && size <= evfOsdBytes.toLong()) ||
                    (address == pic1Addr && size <= pic1MaxBytes.toLong())
                require(valid) { "Unexpected DMA check 0x${address.toString(16)} size=$size" }
            }

            override fun read(address: Long, destination: ByteBuffer) {
                val len = destination.remaining()
                when (address) {
                    lcdOsdAddr -> destination.put(lcdOsd.array(), 0, len)
                    evfOsdAddr -> destination.put(evfOsd.array(), 0, len)
                    pic1Addr -> destination.put(pic1, 0, len)
                    else -> error("Unexpected DMA read 0x${address.toString(16)}")
                }
            }

            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }
        return Triple(ctx, main to evf, Triple(lcdOsd, evfOsd, pic1))
    }

    private fun programLcdc1Frame(
        bank: MmioDevice,
        width: Int,
        height: Int,
        osdAddr: Long,
        pic1ExtWord: Long = ((height.toLong() shl 16) or width.toLong()),
        pic1PosWord: Long = 0L,
        pic1SizeWord: Long = ((height.toLong() shl 16) or width.toLong()),
        pic1StrideBytes: Int = width * 2,
    ) {
        val hActiveWord = if (width == 1280) 0x0010_0500L else 0x0003_0400L
        bank.write(0x014, 4, hActiveWord)
        bank.write(0x008, 4, 0x0001_1010L)
        bank.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank.write(0x040, 4, 0L)
        bank.write(0x044, 4, osdAddr)
        bank.write(0x050, 4, 0x0001_0000L or width.toLong())
        bank.write(0x054, 4, (height.toLong() shl 16) or width.toLong())
        bank.write(0x058, 4, 0L)
        bank.write(0x05c, 4, (height.toLong() shl 16) or width.toLong())
        bank.write(0x060, 4, 0x1000_1000L)
        bank.write(0x160, 4, 0xffL)
        bank.write(0x164, 4, 0L)
        bank.write(0x168, 4, pic1Addr)
        bank.write(0x16c, 4, (pic1StrideBytes / 8).toLong())
        bank.write(0x170, 4, pic1ExtWord)
        bank.write(0x174, 4, pic1PosWord)
        bank.write(0x178, 4, pic1SizeWord)
        bank.write(0x17c, 4, 0x1000_1000L)
        bank.write(0x600, 4, 1L)
    }

    @Test
    fun lcdc1RoutesEvf1280x960AndLcd1024x768CleanlyAndRoundTripsSnapshot() {
        val (ctx, displays, _) = setupDualDisplay()
        val (main, evf) = displays
        val config = mapOf(
            "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
            "inputFile" to "bank",
            LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
            LcdcFirstScanout.LIMIT_SETTING to 8L,
            LcdcFirstScanout.EVF_SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
            LcdcFirstScanout.EVF_LIMIT_SETTING to 8L,
            "continuousScanout" to true,
        )
        val comp = VglibInitFactory().create(ctx, config) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice

        // 1. Rear LCD 1024x768 active
        programLcdc1Frame(bank1, 1024, 768, lcdOsdAddr)
        assertEquals(1L, main.sequence)
        assertEquals(0L, evf.sequence)
        val lcdPixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(lcdPixels))
        assertEquals(0xffff0000.toInt(), lcdPixels[0])
        assertEquals(0xffffffff.toInt(), lcdPixels[10 * 1024 + 10])
        assertEquals(true, (comp.state()["scanout"] as Map<*, *>)["outputActive"])
        assertEquals(false, (comp.state()["evfScanoutState"] as Map<*, *>)["outputActive"])

        // 2. Eye sensor near -> firmware reconfigures LCDC1 to EVF 1280x960 (+0x014 low16=0x0500)
        programLcdc1Frame(bank1, 1280, 960, evfOsdAddr)
        assertEquals(1L, evf.sequence)
        assertEquals(2L, main.sequence, "Rear LCD must be blanked to black when routing to EVF")
        assertTrue(main.copyArgb(lcdPixels))
        assertTrue(lcdPixels.all { it == LcdcFirstScanout.BLANK_BLACK_ARGB })
        val evfPixels = IntArray(1280 * 960)
        assertTrue(evf.copyArgb(evfPixels))
        assertEquals(0xff0000ff.toInt(), evfPixels[0], "EVF OSD0 pixel (0,0) must be opaque blue")
        assertEquals(0xff00ff00.toInt(), evfPixels[959 * 1280 + 1279], "EVF OSD0 pixel (1279,959) must be opaque green")
        assertEquals(0xffffffff.toInt(), evfPixels[10 * 1280 + 10], "EVF transparent OSD0 reveals white PIC1")
        assertEquals(false, (comp.state()["scanout"] as Map<*, *>)["outputActive"])
        assertEquals(true, (comp.state()["evfScanoutState"] as Map<*, *>)["outputActive"])

        // Snapshot save/restore while EVF has published frames via LCDC1 must succeed
        val snap = comp.saveSnapshot()
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val restored = VglibInitFactory().create(ctx, config) as StatefulComponent
        restored.restoreSnapshot(snap)
        assertEquals(snap, restored.saveSnapshot())

        // 3. Eye sensor away -> firmware switches LCDC1 back to Rear LCD 1024x768 (+0x014 low16=0x0400)
        val restoredBank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        programLcdc1Frame(restoredBank1, 1024, 768, lcdOsdAddr)
        assertEquals(3L, main.sequence)
        assertEquals(2L, evf.sequence, "EVF must be blanked to black when switching back to LCD")
        assertTrue(evf.copyArgb(evfPixels))
        assertTrue(evfPixels.all { it == LcdcFirstScanout.BLANK_BLACK_ARGB })
        assertTrue(main.copyArgb(lcdPixels))
        assertEquals(0xffff0000.toInt(), lcdPixels[0])
        assertEquals(true, (restored.state()["scanout"] as Map<*, *>)["outputActive"])
        assertEquals(false, (restored.state()["evfScanoutState"] as Map<*, *>)["outputActive"])
    }

    @Test
    fun continuousGraphicsFillCopyAndSolidRectSupport1280x960EvfCanvas() {
        val ramBase = 0x059e_d000L
        val evfTarget = 0x0689_3000L
        val evfBytes = 1280 * 960 * 4
        val copyDest = 0x059e_d000L
        val buffers = mapOf(
            evfTarget to ByteArray(evfBytes),
            copyDest to ByteArray(evfBytes),
        )
        val mem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                val buf = buffers[address]
                require(buf != null && size in 1..buf.size.toLong()) { "Bad DMA 0x${address.toString(16)}+$size" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val buf = buffers.getValue(address)
                destination.put(buf, 0, destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                val buf = buffers.getValue(address)
                source.get(buf, 0, source.remaining())
            }
        }

        // 1. Continuous GraphicsFillExecution accepts EVF 1280x960x4 (0x4B0000 bytes)
        val fillExec = GraphicsFillExecution(
            mapOf(
                "profile" to GraphicsFillExecution.PROFILE,
                "submissionLimit" to 1,
                "surfaceBase" to evfTarget,
                "surfaceBytes" to 0x30_0000L,
            ),
            continuous = true,
        )
        val fillReceipt = fillExec.execute(
            mem,
            longArrayOf(evfTarget, evfTarget + evfBytes, 0x1122_3344L, 0L),
            GraphicsFillExecution.CONTROL,
        )
        assertEquals(evfBytes.toLong(), (fillReceipt["bytes"] as Number).toLong())
        assertEquals(0x44.toByte(), buffers.getValue(evfTarget)[evfBytes - 4])
        assertEquals(0x11.toByte(), buffers.getValue(evfTarget)[evfBytes - 1])

        // 2. Continuous GraphicsCopyExecution Mode 1 accepts EVF 1280x960 copy
        val copyExec = GraphicsCopyExecution(
            mapOf(
                "profile" to GraphicsCopyExecution.PROFILE,
                "submissionLimit" to 1,
                "sourceBase" to evfTarget,
                "destinationBase" to copyDest,
                "surfaceBytes" to (1024 * 768 * 4),
            ),
            continuous = true,
        )
        val copyWords = longArrayOf(
            evfTarget,
            copyDest,
            (960L shl 16) or 1280L,
            (960L shl 16) or 1280L,
            1L,
            1L,
            0L,
            0L,
            0L,
        )
        val copyReceipt = copyExec.execute(mem, copyWords)
        assertEquals(evfBytes.toLong(), (copyReceipt["bytes"] as Number).toLong())
        assertTrue(buffers.getValue(evfTarget).contentEquals(buffers.getValue(copyDest)))

        // 3. SolidCommandRects EVF 1280x960 scissor + Rect stride painting
        val evfScissor = SolidCommandRects.parseScissor(listOf(0x04ff_0000, 0x03bf_0000), 1280, 960)
        assertEquals(SolidCommandRects.Scissor(0, 0, 1280, 960), evfScissor)
        val frame = ByteArray(evfBytes)
        SolidCommandRects.Rect(1200, 900, 1280, 960, 0xff00_ff00.toInt(), srcOver = true, canvasWidth = 1280).paint(frame)
        val lastPixelOffset = 4 * (959 * 1280 + 1279)
        assertEquals(0xff.toByte(), frame[lastPixelOffset])
        assertEquals(0x00.toByte(), frame[lastPixelOffset + 1])
        assertEquals(0xff.toByte(), frame[lastPixelOffset + 2])
        assertEquals(0x00.toByte(), frame[lastPixelOffset + 3])
        assertEquals(0.toByte(), frame[0], "Unpainted top-left pixel must remain zero")
        assertTrue(ramBase == copyDest)

        // 4. SolidCommandRects.decodeShape accepts EVF 1280x960 stride link word 0x09601400 (BUG-14)
        val pathAddr = 0x06fe_6000L
        val coordAddr = 0x06fe_6100L
        val pathBytes = byteArrayOf(2, 4, 4, 4, 0)
        val coordBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(1100f); putFloat(10f)
            putFloat(1260f); putFloat(10f)
            putFloat(1260f); putFloat(50f)
            putFloat(1100f); putFloat(50f)
        }.array()
        val evfLink = listOf(0x07c3_e000, 0, 0x200, 0x0960_1400, 0)
        val evfViewport = ((960 shl 2) shl 16) or (1280 shl 2) // 0x0f001400
        val matrixWords = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f).map { it.toRawBits() }
        val vertexPackets = listOf(
            GraphicsCommandExecution.Packet(0x5ef00e, listOf(0x3f800000, 0)),
            GraphicsCommandExecution.Packet(0x63f004, listOf(5)),
            GraphicsCommandExecution.Packet(0x40f004, listOf(evfViewport)),
            GraphicsCommandExecution.Packet(0x40f004, listOf(evfViewport)),
            GraphicsCommandExecution.Packet(0x41f046, matrixWords),
            GraphicsCommandExecution.Packet(0x4f004, listOf(coordAddr.toInt())),
            GraphicsCommandExecution.Packet(0x5f004, listOf(0xd000_0008.toInt())),
            GraphicsCommandExecution.Packet(0x2f004, listOf(pathAddr.toInt())),
            GraphicsCommandExecution.Packet(0x3f004, listOf(0x1000_0005)),
            GraphicsCommandExecution.Packet(0x653004, listOf(0x2100)),
            GraphicsCommandExecution.Packet(0x66f004, listOf(0x6004)),
            GraphicsCommandExecution.Packet(0x80f026, evfLink),
            GraphicsCommandExecution.Packet(0x6f004, listOf(1)),
        )
        val fragmentPackets = listOf(
            GraphicsCommandExecution.Packet(0x1a61004, listOf(1)),
            GraphicsCommandExecution.Packet(0x1a11004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1b01004, listOf(11)),
            GraphicsCommandExecution.Packet(0x1b31004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1aa1004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1841004, listOf(8)),
            GraphicsCommandExecution.Packet(0x1842004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1a02004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1a21004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1a4f004, listOf(evfTarget.toInt())),
            GraphicsCommandExecution.Packet(0x1a51004, listOf(1)),
            GraphicsCommandExecution.Packet(0x1842004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1a74004, listOf(0)),
            GraphicsCommandExecution.Packet(0x11c8004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1401004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0xc0f00e, listOf(0x04ff_0000, 0x03bf_0000)),
            GraphicsCommandExecution.Packet(0x1001004, listOf(1)),
            GraphicsCommandExecution.Packet(0x1151004, listOf(1)),
            GraphicsCommandExecution.Packet(0x1a04004, listOf(0x10000)),
            GraphicsCommandExecution.Packet(0x1848004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1841004, listOf(8)),
            GraphicsCommandExecution.Packet(0x1401004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0x1402004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0x14df004, listOf(0)),
            GraphicsCommandExecution.Packet(0x115c004, listOf(0)),
            GraphicsCommandExecution.Packet(0x185f004, listOf(0xff00_00ff.toInt())),
            GraphicsCommandExecution.Packet(0x1863004, listOf(0x100)),
            GraphicsCommandExecution.Packet(0xa0f02e, evfLink + 0x1040102),
            GraphicsCommandExecution.Packet(0x101004, listOf(1)),
        )
        val decodedEvfShape = SolidCommandRects.decodeShape(
            vertex = vertexPackets,
            fragment = fragmentPackets,
            target = evfTarget,
            extended = true,
            canvasWidth = 1280,
            canvasHeight = 960,
        ) { addr, size ->
            when (addr) {
                pathAddr -> pathBytes.copyOf(size)
                coordAddr -> coordBuf.copyOf(size)
                else -> error("Unexpected address 0x${addr.toString(16)}")
            }
        }
        assertTrue(decodedEvfShape is SolidCommandRects.Rect, "EVF 1280x960 solid rect with 0x09601400 link must decode")
        val evfRectFrame = ByteArray(evfBytes)
        decodedEvfShape.paint(evfRectFrame)
        val paintedOff = 4 * (930 * 1280 + 1150) // y=960-30=930, x=1150 (in right 256px beyond 1024!)
        assertEquals(0xff.toByte(), evfRectFrame[paintedOff], "EVF solid rect must paint beyond x=1024 at 1280 stride")
        assertEquals(0xff.toByte(), evfRectFrame[paintedOff + 3], "EVF solid rect red channel must match")
    }

    @Test
    fun lcdc1Pic1AcceptsAllSevenNon4To3AspectExtentsAndRejectsOutOfBounds() {
        val (ctx, displays, _) = setupDualDisplay()
        val (main, _) = displays
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 16L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        val pixels = IntArray(1024 * 768)

        data class AspectCase(
            val name: String,
            val extWord: Long,
            val posWord: Long,
            val sizeWord: Long,
            val strideBytes: Int,
            val expectedX: Int,
            val expectedY: Int,
            val expectedW: Int,
            val expectedH: Int,
        )

        val cases = listOf(
            AspectCase("4:3", 0x0300_0400L, 0x0000_0000L, 0x0300_0400L, 2048, 0, 0, 1024, 768),
            AspectCase("3:2", 0x0300_0400L, 0x002b_0000L, 0x02aa_0400L, 2048, 0, 43, 1024, 682),
            AspectCase("16:9", 0x0300_0400L, 0x0060_0000L, 0x0240_0400L, 2048, 0, 96, 1024, 576),
            AspectCase("1:1", 0x0300_0380L, 0x0000_0080L, 0x0300_0300L, 1536, 128, 0, 768, 768),
            AspectCase("65:24", 0x0300_0400L, 0x00c3_0000L, 0x017a_0400L, 2048, 0, 195, 1024, 378),
            AspectCase("5:4", 0x0300_03e0L, 0x0000_0020L, 0x0300_03c0L, 1920, 32, 0, 960, 768),
            AspectCase("7:6", 0x0300_03c0L, 0x0000_0040L, 0x0300_0380L, 1792, 64, 0, 896, 768),
        )

        cases.forEachIndexed { idx, c ->
            programLcdc1Frame(
                bank1, 1024, 768, lcdOsdAddr,
                pic1ExtWord = c.extWord,
                pic1PosWord = c.posWord,
                pic1SizeWord = c.sizeWord,
                pic1StrideBytes = c.strideBytes,
            )
            assertEquals((idx + 1).toLong(), main.sequence, "Aspect ${c.name} must publish")
            assertTrue(main.copyArgb(pixels))
            val cx = c.expectedX + c.expectedW / 2
            val cy = c.expectedY + c.expectedH / 2
            assertEquals(0xffffffff.toInt(), pixels[cy * 1024 + cx], "Aspect ${c.name} center must show white PIC1")
            if (c.expectedX > 0) {
                assertEquals(0xff000000.toInt(), pixels[cy * 1024 + (c.expectedX - 1)], "Aspect ${c.name} left pillarbox must be black")
                assertEquals(0xff000000.toInt(), pixels[cy * 1024 + (c.expectedX + c.expectedW)], "Aspect ${c.name} right pillarbox must be black")
            }
            if (c.expectedY > 0) {
                assertEquals(0xff000000.toInt(), pixels[(c.expectedY - 1) * 1024 + cx], "Aspect ${c.name} top letterbox must be black")
                assertEquals(0xff000000.toInt(), pixels[(c.expectedY + c.expectedH) * 1024 + cx], "Aspect ${c.name} bottom letterbox must be black")
            }
        }

        // Invalid extent smaller than pic1Width (700 < 768) must still fail
        assertFailsWith<IllegalArgumentException> {
            programLcdc1Frame(
                bank1, 1024, 768, lcdOsdAddr,
                pic1ExtWord = 0x0300_02bcL, // extW = 700 < pic1W = 768
                pic1PosWord = 0x0000_0080L,
                pic1SizeWord = 0x0300_0300L,
                pic1StrideBytes = 1536,
            )
        }
        // Invalid extent wider than canvasWidth (1024) must still fail
        assertFailsWith<IllegalArgumentException> {
            programLcdc1Frame(
                bank1, 1024, 768, lcdOsdAddr,
                pic1ExtWord = 0x0300_0500L, // extW = 1280 > 1024
                pic1PosWord = 0x0000_0000L,
                pic1SizeWord = 0x0300_0400L,
                pic1StrideBytes = 2048,
            )
        }
        // No pixel is visible if the image starts on the edge, and clipping cannot license a short source row.
        for ((pos, stride) in listOf(0x0000_0400L to 2048, 0x0300_0000L to 2048, 0x0000_0080L to 1792)) {
            assertFailsWith<IllegalArgumentException> {
                programLcdc1Frame(bank1, 1024, 768, lcdOsdAddr,
                    pic1ExtWord = 0x0300_0400L, pic1PosWord = pos, pic1SizeWord = 0x0300_0400L, pic1StrideBytes = stride)
            }
        }
    }

    @Test
    fun lcdc1CompositesDualDisplayPic0AndPic1SideBySideUnderOsd0() {
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val lcdOsd = ByteBuffer.allocate(lcdOsdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val pic0Addr = 0x0800_0000L
        val pic0Stride = 2560 // 0x140 * 8 (exact GFX100S MF Dual Display stride)
        val pic0Bytes = ByteArray(504 * pic0Stride)
        val pic1Bytes = ByteArray(192 * pic0Stride)
        // PIC0 = neutral gray (Y=0x80, U=0x80, V=0x80)
        for (i in 0 until pic0Bytes.size step 4) {
            pic0Bytes[i] = 0x80.toByte()
            pic0Bytes[i + 1] = 0x80.toByte()
            pic0Bytes[i + 2] = 0x80.toByte()
            pic0Bytes[i + 3] = 0x80.toByte()
        }
        // PIC1 = bright white YYUV422 (Y0=0xFF, Y1=0xFF, U=0x80, V=0x80)
        for (i in 0 until pic1Bytes.size step 4) {
            pic1Bytes[i] = 0xff.toByte()
            pic1Bytes[i + 1] = 0xff.toByte()
            pic1Bytes[i + 2] = 0x80.toByte()
            pic1Bytes[i + 3] = 0x80.toByte()
        }
        // OSD0: opaque red dot at (10, 10)
        lcdOsd.putInt((10 * 1024 + 10) * 4, 0xff0000ff.toInt())

        ctx.entries["display.main"] = main
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                val ok = (address == lcdOsdAddr && size <= lcdOsdBytes.toLong()) ||
                    (address == pic0Addr && size <= pic0Bytes.size.toLong()) ||
                    (address == pic1Addr && size <= pic1Bytes.size.toLong())
                require(ok) { "Unexpected DMA check 0x${address.toString(16)} size=$size" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val len = destination.remaining()
                when (address) {
                    lcdOsdAddr -> destination.put(lcdOsd.array(), 0, len)
                    pic0Addr -> destination.put(pic0Bytes, 0, len)
                    pic1Addr -> destination.put(pic1Bytes, 0, len)
                    else -> error("Unexpected DMA read 0x${address.toString(16)}")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 8L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice

        // Exact GFX100S 2.13 MF Dual Display register state (BUG-A3-4):
        // +0x008 = 0x11110 (OSD0 | ACTIVE_GATE | PIC0 | PIC1)
        // PIC0: (56, 132) 672x504, stride=2560 (0x140), extent=0x030003f0
        // PIC1: (736, 288) 256x192, stride=2560 (0x140), extent=0x03000120 (1024 - 736 = 288!)
        bank1.write(0x014, 4, 0x0003_0400L)
        bank1.write(0x008, 4, 0x0001_1110L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank1.write(0x040, 4, 0L)
        bank1.write(0x044, 4, lcdOsdAddr)
        bank1.write(0x050, 4, 0x0001_0400L)
        bank1.write(0x054, 4, 0x0300_0400L)
        bank1.write(0x058, 4, 0L)
        bank1.write(0x05c, 4, 0x0300_0400L)
        bank1.write(0x060, 4, 0x1000_1000L)
        bank1.write(0x130, 4, 0L)
        bank1.write(0x134, 4, pic0Addr)
        bank1.write(0x138, 4, 0x140L)
        bank1.write(0x13c, 4, 0x0300_03f0L)
        bank1.write(0x140, 4, 0x0084_0038L)
        bank1.write(0x144, 4, 0x01f8_02a0L)
        bank1.write(0x148, 4, 0x1000_1000L)
        bank1.write(0x160, 4, 0xffL)
        bank1.write(0x164, 4, 0L)
        bank1.write(0x168, 4, pic1Addr)
        bank1.write(0x16c, 4, 0x140L)
        bank1.write(0x170, 4, 0x0300_0120L)
        bank1.write(0x174, 4, 0x0120_02e0L)
        bank1.write(0x178, 4, 0x00c0_0100L)
        bank1.write(0x17c, 4, 0x1000_1000L)
        bank1.write(0x600, 4, 0x1000_0005L)

        assertEquals(1L, main.sequence)
        val pixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(pixels))
        // OSD0 red dot at (10, 10)
        assertEquals(0xffff0000.toInt(), pixels[10 * 1024 + 10], "OSD0 must composite over backdrop")
        // Black margin outside both panes
        assertEquals(0xff000000.toInt(), pixels[384 * 1024 + 20], "Left margin outside PIC0 must be black")
        assertEquals(0xff000000.toInt(), pixels[384 * 1024 + 732], "8px gap between PIC0 (728) and PIC1 (736) must be black")
        // Left PIC0 main LiveView pane (56..728, 132..636) -> gray (~0x80)
        val pic0Px = pixels[384 * 1024 + 400]
        assertTrue(((pic0Px ushr 16) and 0xff) in 0x7e..0x82 && ((pic0Px ushr 8) and 0xff) in 0x7e..0x82, "Left PIC0 pane must render gray UYVY (got 0x${pic0Px.toString(16)})")
        // Right PIC1 magnified inset pane (736..992, 288..480) -> white (0xFF)
        assertEquals(0xffffffff.toInt(), pixels[384 * 1024 + 864], "Right PIC1 magnified inset pane must render white UYVY")
    }

    @Test
    fun lcdc1CompositesPic0OnlyMultiExpReviewUnderOsd0() {
        // BUG-A2-3: Multiple Exposure Frame 1 Review (scr=0x141) enables PIC0-only (+0x008=0x11100, PIC1 off)
        // with PIC0 at 0x3946a000 (1024x768, stride=3200 / reg 0x190) behind transparent OSD0.
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val lcdOsd = ByteBuffer.allocate(lcdOsdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val pic0Addr = 0x3946_a000L
        val pic0Stride = 3200 // 0x190 * 8
        val pic0Bytes = ByteArray(768 * pic0Stride)
        for (y in 0 until 768) {
            val rowOff = y * pic0Stride
            for (x in 0 until 1024 step 2) {
                val p = rowOff + x * 2
                pic0Bytes[p] = 0xc8.toByte()
                pic0Bytes[p + 1] = 0xc8.toByte()
                pic0Bytes[p + 2] = 0x80.toByte()
                pic0Bytes[p + 3] = 0x80.toByte()
            }
        }
        // OSD0 bottom bar badge at (100, 720) = opaque white, rest transparent
        lcdOsd.putInt((720 * 1024 + 100) * 4, 0xffffffff.toInt())

        ctx.entries["display.main"] = main
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                val ok = (address == lcdOsdAddr && size <= lcdOsdBytes.toLong()) ||
                    (address == pic0Addr && size <= pic0Bytes.size.toLong())
                require(ok) { "Unexpected DMA check 0x${address.toString(16)} size=$size" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val len = destination.remaining()
                when (address) {
                    lcdOsdAddr -> destination.put(lcdOsd.array(), 0, len)
                    pic0Addr -> destination.put(pic0Bytes, 0, len)
                    else -> error("Unexpected DMA read 0x${address.toString(16)}")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice

        bank1.write(0x014, 4, 0x0003_0400L)
        bank1.write(0x008, 4, 0x0001_1100L) // OSD0 | bit12 | PIC0 (PIC1 disabled!)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank1.write(0x040, 4, 0L)
        bank1.write(0x044, 4, lcdOsdAddr)
        bank1.write(0x050, 4, 0x0001_0400L)
        bank1.write(0x054, 4, 0x0300_0400L)
        bank1.write(0x058, 4, 0L)
        bank1.write(0x05c, 4, 0x0300_0400L)
        bank1.write(0x060, 4, 0x1000_1000L)
        bank1.write(0x130, 4, 0L)
        bank1.write(0x134, 4, pic0Addr)
        bank1.write(0x138, 4, 0x190L)
        bank1.write(0x13c, 4, 0x0300_0400L)
        bank1.write(0x140, 4, 0L)
        bank1.write(0x144, 4, 0x0300_0400L)
        bank1.write(0x148, 4, 0x1000_1000L)
        bank1.write(0x600, 4, 0x1000_0005L)

        assertEquals(1L, main.sequence)
        val pixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(pixels))
        val centerPx = pixels[384 * 1024 + 512]
        assertTrue(((centerPx ushr 16) and 0xff) in 0xc4..0xcc, "PIC0-only center must show bright gray photo (got 0x${centerPx.toString(16)})")
        assertEquals(0xffffffff.toInt(), pixels[720 * 1024 + 100], "OSD0 prompt badge must composite over PIC0")
    }

    @Test
    fun unwrittenZeroUyvyPillarboxRendersNeutralBlackWhileValidColorsPreserved() {
        // Direct BT.601 UYVY helper checks
        val zeroMacroPixel = byteArrayOf(0, 0, 0, 0)
        assertEquals(0x000000, LcdcFirstScanout.yuv422UyvyToRgb(zeroMacroPixel, 0, false), "Unwritten zero UYVY even pixel must be pure black")
        assertEquals(0x000000, LcdcFirstScanout.yuv422UyvyToRgb(zeroMacroPixel, 0, true), "Unwritten zero UYVY odd pixel must be pure black")

        val neutralBlack = byteArrayOf(0x80.toByte(), 0x00, 0x80.toByte(), 0x00)
        assertEquals(0x000000, LcdcFirstScanout.yuv422UyvyToRgb(neutralBlack, 0, false))

        val neutralWhite = byteArrayOf(0x80.toByte(), 0xff.toByte(), 0x80.toByte(), 0xff.toByte())
        assertEquals(0xffffff, LcdcFirstScanout.yuv422UyvyToRgb(neutralWhite, 0, false))

        val brightGreen = byteArrayOf(0x00, 0x95.toByte(), 0x00, 0x95.toByte())
        val greenRgb = LcdcFirstScanout.yuv422UyvyToRgb(brightGreen, 0, false)
        assertTrue(((greenRgb ushr 8) and 0xff) > 200 && ((greenRgb ushr 16) and 0xff) == 0 && (greenRgb and 0xff) == 0,
            "Valid BT.601 green must still decode green (got 0x${greenRgb.toString(16)})")

        // End-to-end 1024x768 Playback buffer with unwritten zero-filled 128px left/right pillarbox + white 768x768 center
        val (ctx, displays, buffers) = setupDualDisplay()
        val (main, _) = displays
        val (_, _, pic1) = buffers
        pic1.fill(0)
        for (y in 0 until 768) {
            val rowBase = y * 2048
            for (x in 128 until 896 step 2) {
                val p = rowBase + x * 2
                pic1[p] = 0xff.toByte()
                pic1[p + 1] = 0xff.toByte()
                pic1[p + 2] = 0x80.toByte()
                pic1[p + 3] = 0x80.toByte()
            }
        }
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        programLcdc1Frame(bank1, 1024, 768, lcdOsdAddr)

        val pixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(pixels))
        // Check left margin (x=10..120, y=384) and right margin (x=900..1010, y=384) are pure black (zero green!)
        for (x in listOf(10, 64, 127, 896, 960, 1023)) {
            assertEquals(0xff000000.toInt(), pixels[384 * 1024 + x], "Pillarbox pixel at x=$x must be neutral black")
        }
        assertEquals(0xffffffff.toInt(), pixels[384 * 1024 + 512], "Center 1:1 playback image must stay bright white")
        assertFalse(pixels.any { ((it ushr 8) and 0xff) > 0 && ((it ushr 16) and 0xff) == 0 && (it and 0xff) == 0 },
            "No green pillarbox artifacts allowed anywhere on screen")
    }

    @Test
    fun firmwareMilbeaut80800000And00008080PillarboxFillWordsRenderPureBlackInKotlinAndSimd() {
        // GFX100S 2.13 section5@0x021cdea0..0x021cdeb0 -> section6@0x0436d200 writes 0x80800000 LE
        // ([00 00 80 80]) or 0x00008080 LE ([80 80 00 00]) across non-4:3 Playback margins (BUG-A5-5).
        val yyuvBlack = byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x80.toByte())
        val uvyyBlack = byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x00, 0x00)
        for ((name, word) in listOf("0x80800000" to yyuvBlack, "0x00008080" to uvyyBlack)) {
            assertEquals(0x000000, LcdcFirstScanout.yuv422UyvyToRgb(word, 0, false), "$name even pixel must be pure black")
            assertEquals(0x000000, LcdcFirstScanout.yuv422UyvyToRgb(word, 0, true), "$name odd pixel must be pure black")
        }

        // Also verify real-silicon YYUV422 50%-blended film-strip sprocket word 0x7e7f3e41 ([41 3e 7f 7e])
        // decodes to neutral dark gray (not olive green!) in LcdcFirstScanout.yuv422YyuvToRgb.
        val filmStripGray = byteArrayOf(0x41, 0x3e, 0x7f, 0x7e)
        val grayEven = LcdcFirstScanout.yuv422YyuvToRgb(filmStripGray, 0, false)
        val grayR = (grayEven ushr 16) and 0xff
        val grayG = (grayEven ushr 8) and 0xff
        val grayB = grayEven and 0xff
        assertTrue(grayR in 60..68 && grayG in 60..68 && grayB in 60..68, "0x7e7f3e41 must decode neutral dark gray, got ($grayR,$grayG,$grayB)")

        // End-to-end 1024x768 1:1 Playback buffer with 0x80800000 left margin, white center, 0x00008080 right margin
        val (ctx, displays, buffers) = setupDualDisplay()
        val (main, _) = displays
        val (_, _, pic1) = buffers
        for (y in 0 until 768) {
            val rowBase = y * 2048
            for (x in 0 until 128 step 2) {
                val p = rowBase + x * 2
                pic1[p] = 0x00; pic1[p + 1] = 0x00; pic1[p + 2] = 0x80.toByte(); pic1[p + 3] = 0x80.toByte()
            }
            for (x in 128 until 896 step 2) {
                val p = rowBase + x * 2
                pic1[p] = 0xff.toByte(); pic1[p + 1] = 0xff.toByte(); pic1[p + 2] = 0x80.toByte(); pic1[p + 3] = 0x80.toByte()
            }
            for (x in 896 until 1024 step 2) {
                val p = rowBase + x * 2
                pic1[p] = 0x80.toByte(); pic1[p + 1] = 0x80.toByte(); pic1[p + 2] = 0x00; pic1[p + 3] = 0x00
            }
        }
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        programLcdc1Frame(bank1, 1024, 768, lcdOsdAddr)

        val pixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(pixels))
        for (x in listOf(0, 1, 10, 64, 126, 127, 896, 897, 960, 1022, 1023)) {
            assertEquals(0xff000000.toInt(), pixels[384 * 1024 + x], "BUG-A5-5 margin at x=$x must be pure black")
        }
        assertEquals(0xffffffff.toInt(), pixels[384 * 1024 + 512], "Center 1:1 playback photo must stay white")
        for (y in 1 until 768) {
            for (x in 0 until 128) {
                assertEquals(0xff000000.toInt(), pixels[y * 1024 + x], "Left 0x80800000 margin ($x,$y) must be pure black")
            }
            for (x in 896 until 1024) {
                assertEquals(0xff000000.toInt(), pixels[y * 1024 + x], "Right 0x00008080 margin ($x,$y) must be pure black")
            }
        }
        assertFalse(pixels.any { ((it ushr 8) and 0xff) > 0 && ((it ushr 16) and 0xff) == 0 && (it and 0xff) == 0 },
            "No green vertical-stripe artifacts allowed anywhere in 1:1 playback frame")
    }

    @Test
    fun lcdc1Pic1SupportsHardwareQ12ScalingDuringPlaybackMultiFrameTransitions() {
        // BUG-V3-8-8: Live GFX100S 2.13 Playback 9/100-frame grid transition programs LCDC1 PIC1
        // with non-unit Q12 scale (+0x17C=0x0F00_0F0F), source extent (+0x170=0x02D0_0404 = 1028x720),
        // stride (+0x16C=0x108 -> 2112 B), and scaled output window (+0x178=0x0300_0444 = 1092x768)
        // clipped cleanly to the 1024x768 LCD canvas with zero MMIO fallback warnings.
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val lcdOsd = ByteArray(lcdOsdBytes)
        val pic1Addr = 0x2b3a_2000L
        val srcW = 1028
        val srcH = 720
        val srcStride = 2112 // 0x108 * 8
        val pic1Src = ByteArray(srcH * srcStride)
        // Fill background with Milbeaut 0x80800000 ([00 00 80 80]) black fill and paint a white
        // thumbnail tile in source region x=100..260, y=100..220 (maps to dst x~106..276, y~106..234).
        for (y in 0 until srcH) {
            val rowOff = y * srcStride
            for (x in 0 until srcW step 2) {
                val p = rowOff + x * 2
                if (x in 100 until 260 && y in 100 until 220) {
                    pic1Src[p] = 0xff.toByte(); pic1Src[p + 1] = 0xff.toByte()
                    pic1Src[p + 2] = 0x80.toByte(); pic1Src[p + 3] = 0x80.toByte()
                } else {
                    pic1Src[p] = 0x00; pic1Src[p + 1] = 0x00
                    pic1Src[p + 2] = 0x80.toByte(); pic1Src[p + 3] = 0x80.toByte()
                }
            }
        }
        val expectedDmaBytes = (srcH - 1).toLong() * srcStride + srcW.toLong() * 2L
        ctx.entries["display.main"] = main
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                val ok = (address == lcdOsdAddr && size == lcdOsdBytes.toLong()) ||
                    (address == pic1Addr && size == expectedDmaBytes)
                require(ok) { "Unexpected DMA check 0x${address.toString(16)} size=$size (expected $expectedDmaBytes)" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                val len = destination.remaining()
                when (address) {
                    lcdOsdAddr -> destination.put(lcdOsd, 0, len)
                    pic1Addr -> destination.put(pic1Src, 0, len)
                    else -> error("Unexpected DMA read 0x${address.toString(16)}")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        bank1.write(0x014, 4, 0x0003_0400L)
        bank1.write(0x008, 4, 0x0001_1010L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank1.write(0x040, 4, 0L)
        bank1.write(0x044, 4, lcdOsdAddr)
        bank1.write(0x050, 4, 0x0001_0400L)
        bank1.write(0x054, 4, 0x0300_0400L)
        bank1.write(0x058, 4, 0L)
        bank1.write(0x05c, 4, 0x0300_0400L)
        bank1.write(0x060, 4, 0x1000_1000L)
        bank1.write(0x160, 4, 0xffL)
        bank1.write(0x164, 4, 0L)
        bank1.write(0x168, 4, pic1Addr)
        bank1.write(0x16c, 4, 0x108L)
        bank1.write(0x170, 4, 0x02d0_0404L)
        bank1.write(0x174, 4, 0L)
        bank1.write(0x178, 4, 0x0300_0444L)
        bank1.write(0x17c, 4, 0x0f00_0f0fL)
        bank1.write(0x600, 4, 0x1000_0005L)

        assertEquals(1L, main.sequence, "Q12-scaled PIC1 frame must publish cleanly")
        val pixels = IntArray(1024 * 768)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff000000.toInt(), pixels[10 * 1024 + 10], "Scaled Milbeaut 0x80800000 background must stay pure black")
        assertEquals(0xffffffff.toInt(), pixels[160 * 1024 + 180], "Scaled white thumbnail interior must stay white")
        assertFalse(
            pixels.any { ((it ushr 8) and 0xff) > ((it ushr 16) and 0xff) + 20 },
            "Zero green-tint artifacts allowed in Q12-scaled Playback PIC1 frame",
        )

        // Invalid zero Q12 scale must still be rejected
        bank1.write(0x17c, 4, 0L)
        assertFailsWith<IllegalArgumentException> {
            bank1.write(0x600, 4, 0x1000_0005L)
        }
    }
}
