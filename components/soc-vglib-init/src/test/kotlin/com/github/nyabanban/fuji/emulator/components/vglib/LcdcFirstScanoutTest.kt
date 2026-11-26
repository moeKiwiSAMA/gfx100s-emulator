package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Test
import kotlin.test.*

class LcdcFirstScanoutTest {
    private val context = LcdcFunctionalTest.Context()
    private val main = ArgbFrameBuffer(640, 480)
    private val base = 0x81230000L
    private val config = mapOf("profile" to VglibInitFactory.FUNCTIONAL_PROFILE, "inputFile" to "bank")
    private var checks = 0
    private var reads = 0
    private var shortRead = false
    private val ram = object : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            checks++
            require(!write && address == base && size == 614400L) { "Outside test RAM" }
        }
        override fun read(address: Long, destination: ByteBuffer) {
            assertEquals(base, address)
            assertEquals(614400, destination.remaining())
            reads++
            if (shortRead) destination.put(0) else destination.put(ByteArray(614400) { if (it % 2 == 0) 0xe0.toByte() else 7 })
        }
        override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
    }

    init {
        context.entries["display.main"] = main
        context.entries["cpu.memory"] = ram
    }

    private fun create(selected: Boolean = true) = VglibInitFactory().create(context,
        config + (LcdcFirstScanout.SETTING to if (selected) LcdcFirstScanout.PROFILE else "disabled"))
    private fun bank(instance: Int = 1) = context.entries.getValue(LcdcFunctional.port(instance)) as MmioDevice

    private fun bounded(limit: Long, profile: String = LcdcFirstScanout.PROFILE): StatefulComponent {
        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        return VglibInitFactory().create(context, config + mapOf(LcdcFirstScanout.SETTING to profile,
            LcdcFirstScanout.LIMIT_SETTING to limit)) as StatefulComponent
    }

    private val geometry = mapOf(0x50L to 0x10400L, 0x54L to 0x03000400L, 0x58L to 0L, 0x5cL to 0x03000400L)
    private fun pair(device: MmioDevice = bank(), address: Long = base) {
        device.write(0x40, 4, 0)
        device.write(0x44, 4, address)
    }
    private fun setGeometry(device: MmioDevice = bank()) = geometry.forEach { (offset, value) -> device.write(offset, 4, value) }

    private fun rgbaRam(): ByteBuffer {
        val data = ByteBuffer.allocate(4096 * 768).order(ByteOrder.LITTLE_ENDIAN)
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                checks++
                require(!write && address == base && size == data.capacity().toLong()) { "Outside RGBA RAM" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                assertEquals(base, address)
                assertEquals(data.capacity(), destination.remaining())
                reads++
                destination.put(data.array(), 0, if (shortRead) data.capacity() - 1 else data.capacity())
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }
        return data
    }

    @Test fun rgbaRestoresPreparedSlotReadsFullStrideAndScalesCornersToPanel() {
        val data = rgbaRam()
        val selected = bounded(2, LcdcFirstScanout.RGBA)
        data.putInt(0, 0xff0000ff.toInt())
        data.putInt(4092, 0x00ff00ff)
        data.putInt(767 * 4096, 0x0000ffff)
        data.putInt(data.capacity() - 4, 0x12345678)
        data.putInt(2 * 4096 + 2 * 4, 0xaabbcc00.toInt())
        pair(bank(0))
        setGeometry(bank(0))
        bank(0).write(0x60, 4, 0x10001000)
        pair()
        bank().write(0x48, 4, 0)
        bank().write(0x4c, 4, base + 4096)
        setGeometry()
        assertEquals(0, checks)
        val prepared = selected.saveSnapshot()
        val resumed = bounded(2, LcdcFirstScanout.RGBA)
        resumed.restoreSnapshot(prepared)
        bank().write(0x60, 4, 0x10001000)
        bank().write(0x60, 4, 0x10001000)
        assertEquals(1, reads)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x78123456, 0x00aabbcc),
            listOf(pixels[0], pixels[639], pixels[479 * 640], pixels.last(), pixels[641]))
        val first = resumed.saveSnapshot()
        val display = ArgbFrameBuffer(640, 480)
        display.restoreSnapshot(main.saveSnapshot())
        context.entries["display.main"] = display
        val next = bounded(2, LcdcFirstScanout.RGBA)
        next.restoreSnapshot(first)
        repeat(2) { pair(); bank().write(0x60, 4, 0x10001000) }
        assertEquals(2, reads)
        assertEquals(2L, display.sequence)
        assertEquals(2, (next.saveSnapshot()["scanout"] as Map<*, *>)["publishedFrames"])
    }

    @Test fun rgbaRejectsUnsupportedLayoutAndIncompleteRamAtomically() {
        rgbaRam()
        val selected = bounded(2, LcdcFirstScanout.RGBA)
        pair()
        bank().write(0x60, 4, 0x10001000)
        assertEquals(0, checks)
        setGeometry()
        for ((offset, invalid) in listOf(0x50L to 0x01010400L, 0x50L to 0x10401L, 0x54L to 0x02ff0400L,
                0x58L to 1L, 0x5cL to 0x030003ffL, 0x60L to 0x10001001L)) {
            if (offset != 0x60L) bank().write(offset, 4, invalid)
            val before = selected.saveSnapshot()
            assertFailsWith<IllegalArgumentException> { bank().write(0x60, 4, if (offset == 0x60L) invalid else 0x10001000) }
            assertEquals(before, selected.saveSnapshot())
            if (offset != 0x60L) bank().write(offset, 4, geometry.getValue(offset))
        }
        assertEquals(0, checks)
        for (address in listOf(0xfff00000L, base + 2)) {
            bank().write(0x40, 4, 0)
            assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, address) }
        }
        pair(address = base + 4)
        assertFailsWith<IllegalArgumentException> { bank().write(0x60, 4, 0x10001000) }
        assertEquals(0, reads)
        pair()
        shortRead = true
        val before = selected.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { bank().write(0x60, 4, 0x10001000) }
        assertEquals(before, selected.saveSnapshot())
        assertEquals(0L, main.sequence)
    }

    @Test fun rgb565SnapshotPromotesWithoutReplayingAlreadyPresentedSlot() {
        val old = bounded(2)
        setGeometry()
        pair()
        assertEquals(1L, main.sequence)
        val snapshot = old.saveSnapshot()
        val promoted = bounded(2, LcdcFirstScanout.RGBA)
        rgbaRam()
        promoted.restoreSnapshot(snapshot)
        bank().write(0x60, 4, 0x10001000)
        assertEquals(1L, main.sequence)
        pair()
        bank().write(0x60, 4, 0x10001000)
        assertEquals(2L, main.sequence)
        assertFailsWith<IllegalArgumentException> { bounded(2).restoreSnapshot(promoted.saveSnapshot()) }
    }

    @Test fun restoresPendingSecondPairFromLegacyFirstFrameAndStopsAtSavedLimit() {
        val legacy = create() as StatefulComponent
        bank().write(0x40, 4, 0)
        bank().write(0x44, 4, base)
        val first = legacy.saveSnapshot()
        assertFalse(LcdcFirstScanout.LIMIT_SETTING in first["settings"] as Map<*, *>)
        val selected = bounded(2)
        selected.restoreSnapshot(first)
        bank().write(0x44, 4, base)
        repeat(2) {
            bank().write(0x40, 4, 0)
            bank().write(0x44, 4, 0)
            bank().write(0x40, 4, 0)
            assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, base + 2) }
        }
        assertEquals(1, reads)
        assertEquals(1L, main.sequence)
        val pending = selected.saveSnapshot()
        assertEquals(mapOf<String, Any?>("upper" to 0L, "publishedAddress" to base, "publishedFrames" to 1), pending["scanout"])
        assertFailsWith<IllegalArgumentException> { bounded(3).restoreSnapshot(pending) }
        val restored = bounded(2)
        restored.restoreSnapshot(pending)
        repeat(2) {
            shortRead = true
            assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, base) }
            assertEquals(pending, restored.saveSnapshot())
        }
        assertEquals(1L, main.sequence)
        shortRead = false
        bank().write(0x44, 4, base)
        val spent = restored.saveSnapshot()
        assertEquals(mapOf<String, Any?>("upper" to null, "publishedAddress" to base, "publishedFrames" to 2), spent["scanout"])
        val exhausted = bounded(2)
        exhausted.restoreSnapshot(spent)
        bank().write(0x40, 4, 0)
        bank().write(0x44, 4, base)
        assertEquals(4, reads)
        assertEquals(2L, main.sequence)
    }

    @Test fun limitRequiresExplicitScanoutAndRejectsInvalidCounts() {
        for (limit in listOf(0L, 257L)) assertFailsWith<IllegalArgumentException> { bounded(limit) }
        assertFailsWith<IllegalArgumentException> {
            VglibInitFactory().create(context, config + (LcdcFirstScanout.LIMIT_SETTING to 2L))
        }
        val frames = bounded(256)
        val fresh = frames.saveSnapshot()
        for (count in listOf(-1, 1, 257)) {
            assertFailsWith<IllegalArgumentException> {
                frames.restoreSnapshot(fresh + ("scanout" to mapOf("upper" to null, "publishedAddress" to null, "publishedFrames" to count)))
            }
            assertEquals(fresh, frames.saveSnapshot())
        }
    }

    @Test fun omittedPairOtherBankOtherSlotAndDisabledPublishNothing() {
        val legacy = create(false) as StatefulComponent
        bank().write(0x40, 4, 0)
        bank().write(0x44, 4, base)
        val snapshot = legacy.saveSnapshot()
        assertFalse("scanout" in snapshot)
        assertFalse(LcdcFirstScanout.SETTING in snapshot.getValue("settings") as Map<*, *>)
        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val selected = create() as StatefulComponent
        selected.restoreSnapshot(snapshot)
        bank().read(0x40, 4)
        bank().write(0x44, 4, base)
        bank(0).write(0x40, 4, 0)
        bank(0).write(0x44, 4, base)
        bank().write(0x40, 4, 0)
        bank().write(0x48, 4, 0)
        bank().write(0x4c, 4, base)
        bank().write(0x44, 4, base)
        bank().write(0x40, 4, 0)
        bank().write(0x44, 4, 0)
        assertEquals(0, checks)
        assertEquals(0L, main.sequence)
    }

    @Test fun completeFreshPairSurvivesCheckpointAndPublishesExactlyOnce() {
        val component = create()
        component.start()
        val snapshots = component as StatefulComponent
        bank().write(0x40, 4, 0xfffffffcL)
        val pending = snapshots.saveSnapshot()
        bank().write(0, 4, 0)
        snapshots.restoreSnapshot(pending)
        assertEquals(0, reads)
        bank().write(0x44, 4, base)
        val spent = snapshots.saveSnapshot()
        assertEquals(mapOf("upper" to null, "publishedAddress" to base), spent["scanout"])
        snapshots.restoreSnapshot(spent)
        bank().write(0x40, 4, 0)
        bank().write(0x44, 4, base)
        assertEquals(1, checks)
        assertEquals(1, reads)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertTrue(pixels.all { it == 0xff00ff00.toInt() })
    }

    @Test fun refusesOverflowMissingPortsBadGeometryAndIncompleteBytesBeforeCommit() {
        val component = create()
        val snapshots = component as StatefulComponent
        for ((upper, low) in listOf(1L to base, 0L to 0xfffffff0L, 0L to base + 1, 0L to base + 2)) {
            bank().write(0x40, 4, upper)
            val before = snapshots.saveSnapshot()
            assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, low) }
            assertEquals(before, snapshots.saveSnapshot())
        }
        assertEquals(1, checks)
        assertEquals(0, reads)
        context.entries.remove("cpu.memory")
        assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, base) }
        context.entries.remove("display.main")
        assertFailsWith<IllegalArgumentException> { component.start() }
        context.entries["display.main"] = ArgbFrameBuffer(1, 1)
        assertFailsWith<IllegalArgumentException> { component.start() }
        context.entries["display.main"] = main
        context.entries["cpu.memory"] = ram
        shortRead = true
        val before = snapshots.saveSnapshot()
        assertFailsWith<IllegalArgumentException> { bank().write(0x44, 4, base) }
        assertEquals(before, snapshots.saveSnapshot())
        assertEquals(0L, main.sequence)
        assertEquals(1, reads)
    }

    @Test fun picYuvOsd0CompositesYuvPic1AndStraightAlphaOsd0AndRejectsUnmapped() {
        val osd0Addr = base
        val osd0Size = 4096 * 768
        val pic1Addr = 0x8999c000L
        val pic1Stride = 2048
        val pic1H = 576
        val pic1Size = pic1Stride * pic1H
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        val pic1Data = ByteArray(pic1Size)
        var failPic1Check = false
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                checks++
                require(!write) { "Unexpected write" }
                when (address) {
                    osd0Addr -> require(size == osd0Size.toLong())
                    pic1Addr -> {
                        require(!failPic1Check) { "Unmapped PIC1 RAM" }
                        require(size == pic1Size.toLong())
                    }
                    else -> error("Unexpected DMA check at $address")
                }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                reads++
                when (address) {
                    osd0Addr -> destination.put(osd0Data.array(), 0, osd0Size)
                    pic1Addr -> destination.put(pic1Data, 0, pic1Size)
                    else -> error("Unexpected DMA read at $address")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        // Fill PIC1 with neutral white Y=255, U=128, V=128 (UYVY: U=128, Y0=255, V=128, Y1=255)
        for (i in 0 until pic1Size step 4) {
            pic1Data[i] = 0x80.toByte()
            pic1Data[i + 1] = 0xff.toByte()
            pic1Data[i + 2] = 0x80.toByte()
            pic1Data[i + 3] = 0xff.toByte()
        }
        // OSD0 top-left (y=0, x=0): opaque red LE RRGGBBAA = 0xFF0000FF; center (y=384, x=512): 50% green 0x00FF0080
        osd0Data.putInt(0, 0xff0000ff.toInt())
        osd0Data.putInt(384 * 4096 + 512 * 4, 0x00ff0080)

        val comp = bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        val bank = bank()
        bank.write(0x008, 4, 0x10010) // OSD0 + PIC1 enabled
        bank.write(0x030, 4, 0x20080001L)
        pair(bank, osd0Addr)
        setGeometry(bank)
        bank.write(0x060, 4, 0x10001000L)
        bank.write(0x160, 4, 0xff)
        bank.write(0x164, 4, 0)
        bank.write(0x168, 4, pic1Addr)
        bank.write(0x16c, 4, (pic1Stride / 8).toLong())
        bank.write(0x170, 4, 0x03000400) // outExtent w=1024, h=768
        bank.write(0x174, 4, (96L shl 16)) // y=96, x=0
        bank.write(0x178, 4, 0x02400400) // size w=1024, h=576
        bank.write(0x17c, 4, 0x10001000)
        // Commit on +0x600 with bit 0 set
        bank.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xffff0000.toInt(), pixels[0]) // opaque red OSD0 over black letterbox
        assertEquals(0xff000000.toInt(), pixels[1]) // letterbox outside PIC1 (y=0 < 60)
        assertEquals(0xffffffff.toInt(), pixels[60 * 640 + 1]) // white PIC1 inside letterbox (y=60)
        val center = pixels[240 * 640 + 320] // center blends ~50% green over white PIC1
        val cr = (center ushr 16) and 0xff
        val cg = (center ushr 8) and 0xff
        assertTrue(cr in 120..135 && cg == 255, "Unexpected alpha-blended pixel: 0x${center.toString(16)}")

        // PIC1 disabled (OSD0 only)
        bank.write(0x008, 4, 0x10000)
        bank.write(0x600, 4, 1L)
        assertEquals(2L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff000000.toInt(), pixels[60 * 640 + 1])

        // Fail-closed if PIC1 DMA check fails
        bank.write(0x008, 4, 0x10010)
        failPic1Check = true
        assertFailsWith<IllegalArgumentException> { bank.write(0x600, 4, 1L) }
        assertEquals(2L, main.sequence)

        // Upgrade restore compatibility from RGBA/PROFILE to PIC_YUV_OSD0
        val legacySnap = bounded(4, LcdcFirstScanout.RGBA).saveSnapshot()
        bounded(4, LcdcFirstScanout.PIC_YUV_OSD0).restoreSnapshot(legacySnap)
    }

    @Test fun picYuvOsd0RoundTripsSnapshotWithPublishedFrames() {
        val osd0Addr = base
        val osd0Size = 4096 * 768
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        osd0Data.putInt(0, 0x00ff00ff) // opaque green LE RRGGBBAA
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osd0Addr && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd0Data.array(), 0, osd0Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val first = bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        val b = bank()
        b.write(0x008, 4, 0x10000)
        b.write(0x030, 4, 0x20080001L)
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)
        b.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        val snap1 = first.saveSnapshot()
        assertEquals(1, (snap1["scanout"] as Map<*, *>)["publishedFrames"])

        // Tampering publishedFrames above writeCounts[0x600/4] must be rejected on restore
        val inflatedFrames = (snap1["scanout"] as Map<*, *>) + ("publishedFrames" to 2)
        assertFailsWith<IllegalArgumentException> {
            bounded(4, LcdcFirstScanout.PIC_YUV_OSD0).restoreSnapshot(snap1 + ("scanout" to inflatedFrames))
        }

        // Round-trip restore and publish frame 2
        val resumed = bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        resumed.restoreSnapshot(snap1)
        osd0Data.putInt(0, 0x0000ffff) // opaque blue
        bank().write(0x600, 4, 1L)
        assertEquals(2L, main.sequence)
        val snap2 = resumed.saveSnapshot()
        assertEquals(2, (snap2["scanout"] as Map<*, *>)["publishedFrames"])
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff0000ff.toInt(), pixels[0])
    }

    @Test fun picYuvOsd0SupportsPic1OnlyAboveOsd0CeilingAndMissingWriteSymmetry() {
        val pic1Addr = 0xffe00000L // above 0xFFD00000L (OSD0 ceiling)
        val pic1Stride = 2048
        val pic1H = 576
        val pic1Size = pic1Stride * pic1H
        val pic1Data = ByteArray(pic1Size)
        for (i in 0 until pic1Size step 4) {
            pic1Data[i] = 0x80.toByte()
            pic1Data[i + 1] = 0xff.toByte()
            pic1Data[i + 2] = 0x80.toByte()
            pic1Data[i + 3] = 0xff.toByte()
        }
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == pic1Addr && size == pic1Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(pic1Data, 0, pic1Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val comp = bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        val b = bank()
        b.write(0x008, 4, 0x10) // PIC1 enabled, OSD0 disabled
        b.write(0x160, 4, 0xff)
        b.write(0x164, 4, 0)
        b.write(0x168, 4, pic1Addr)
        b.write(0x170, 4, 0x03000400)
        b.write(0x174, 4, 96L shl 16)
        b.write(0x178, 4, 0x02400400)
        // Missing 0x16c / 0x17c writes must skip silently without throwing (symmetric with OSD0)
        b.write(0x600, 4, 1L)
        assertEquals(0L, main.sequence)
        b.write(0x16c, 4, (pic1Stride / 8).toLong())
        b.write(0x600, 4, 1L)
        assertEquals(0L, main.sequence)

        // Now write 0x17c and publish PIC1-only frame above 0xFFD00000L
        b.write(0x17c, 4, 0x10001000)
        b.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff000000.toInt(), pixels[0])
        assertEquals(0xffffffff.toInt(), pixels[60 * 640 + 1])

        // Snapshot save/restore must accept publishedAddress = 0xFFE00000L under PIC_YUV_OSD0
        val saved = comp.saveSnapshot()
        assertEquals(pic1Addr, (saved["scanout"] as Map<*, *>)["publishedAddress"])
        val restored = bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        restored.restoreSnapshot(saved)
        assertEquals(saved, restored.saveSnapshot())
    }

    @Test fun evfScanoutRoutesLcdc0ToDisplayEvfIndependentlyAndRestoresLegacyV1Snapshots() {
        val evf = ArgbFrameBuffer(640, 480)
        context.entries["display.evf"] = evf
        val evfAddr = 0x82000000L
        val mainAddr = base
        val osd0Size = 4096 * 768
        val evfOsd = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        val mainOsd = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        // EVF pixel (0,0): opaque blue LE RRGGBBAA = 0x0000FFFF; Main pixel (0,0): opaque red LE RRGGBBAA = 0xFF0000FF
        evfOsd.putInt(0, 0x0000ffff)
        mainOsd.putInt(0, 0xff0000ff.toInt())
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && (address == evfAddr || address == mainAddr) && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    evfAddr -> destination.put(evfOsd.array(), 0, osd0Size)
                    mainAddr -> destination.put(mainOsd.array(), 0, osd0Size)
                    else -> error("Unexpected address $address")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val comp = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                LcdcFirstScanout.EVF_SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.EVF_LIMIT_SETTING to 4L,
            ),
        ) as StatefulComponent
        comp.start()

        val bank0 = bank(0)
        val bank1 = bank(1)
        // Drive bank0 (LCDC0 -> display.evf)
        bank0.write(0x008, 4, 0x10000)
        bank0.write(0x030, 4, 0x20080001L)
        pair(bank0, evfAddr)
        setGeometry(bank0)
        bank0.write(0x060, 4, 0x10001000L)
        bank0.write(0x600, 4, 1L)
        assertEquals(1L, evf.sequence)
        assertEquals(0L, main.sequence)
        val evfPixels = IntArray(640 * 480)
        assertTrue(evf.copyArgb(evfPixels))
        assertEquals(0xff0000ff.toInt(), evfPixels[0])

        // Drive bank1 (LCDC1 -> display.main)
        bank1.write(0x008, 4, 0x10000)
        bank1.write(0x030, 4, 0x20080001L)
        pair(bank1, mainAddr)
        setGeometry(bank1)
        bank1.write(0x060, 4, 0x10001000L)
        bank1.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        assertEquals(1L, evf.sequence)
        val mainPixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(mainPixels))
        assertEquals(0xffff0000.toInt(), mainPixels[0])

        // Snapshot round-trip preserves evfScanout state
        val savedWithEvf = comp.saveSnapshot()
        assertEquals(evfAddr, (savedWithEvf["evfScanout"] as Map<*, *>)["publishedAddress"])
        assertEquals(1, (savedWithEvf["evfScanout"] as Map<*, *>)["publishedFrames"])

        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val resumed = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                LcdcFirstScanout.EVF_SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.EVF_LIMIT_SETTING to 4L,
            ),
        ) as StatefulComponent
        resumed.restoreSnapshot(savedWithEvf)
        assertEquals(savedWithEvf, resumed.saveSnapshot())

        // Legacy snapshot without evfScanout settings/key restores cleanly with fresh evfScanout cursor
        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val legacy = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
            ),
        ) as StatefulComponent
        val legacySnap = legacy.saveSnapshot()
        assertFalse("evfScanout" in legacySnap)
        resumed.restoreSnapshot(legacySnap)
        val stateAfterLegacy = resumed.saveSnapshot()
        assertEquals(0, (stateAfterLegacy["evfScanout"] as Map<*, *>)["publishedFrames"])
        assertNull((stateAfterLegacy["evfScanout"] as Map<*, *>)["publishedAddress"])
    }

    @Test fun picYuvOsd0AcceptsLiveAlphaControlWordAndSkipsZeroControl() {
        val osd0Addr = 0x0614_0000L
        val osd0Size = 1024 * 768 * 4
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        osd0Data.putInt(0, 0x00ff00ff) // opaque green at top-left
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osd0Addr && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd0Data.array(), 0, osd0Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        bounded(4, LcdcFirstScanout.PIC_YUV_OSD0)
        val b = bank()
        b.write(0x008, 4, 0x11000L) // OSD0 enabled
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)

        // Control word 0x030 == 0 skips publication
        b.write(0x030, 4, 0L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(0L, main.sequence)

        // Live GFX100S OSD0 alpha control word (0x1FC7F088, bit0=0) publishes cleanly
        b.write(0x030, 4, 0x1fc7f088L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff00ff00.toInt(), pixels[0])
    }

    @Test fun continuousScanoutBlanksToBlackWhenDisplayPlanesDisabledAndRestoresOnReenable() {
        val osd0Addr = 0x0614_0000L
        val osd0Size = 1024 * 768 * 4
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        osd0Data.putInt(0, 0x00ff00ff) // opaque green at top-left
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osd0Addr && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd0Data.array(), 0, osd0Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val component = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        )
        component.start()
        val b = bank()
        b.write(0x008, 4, 0x10000L)
        b.write(0x030, 4, 0x20080001L) // pre-display blank control word before first visible frame
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(0L, main.sequence)

        b.write(0x008, 4, 0x11000L) // OSD0 enabled
        b.write(0x030, 4, 0x1fc7f088L) // live visible OSD0 control word
        b.write(0x600, 4, 0x10000005L)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff00ff00.toInt(), pixels[0])

        // Firmware disables both OSD0 and PIC1 planes -> continuous scanout publishes clean black frame
        b.write(0x008, 4, 0x00000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(2L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertTrue(pixels.all { it == 0xff000000.toInt() })

        // Re-enabling OSD0 restores normal live scanout
        b.write(0x008, 4, 0x11000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(3L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff00ff00.toInt(), pixels[0])

        // Firmware power-off shutdown writes OSD0 blank control word 0x20080001 -> publishes clean black frame
        b.write(0x030, 4, 0x20080001L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(4L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertTrue(pixels.all { it == 0xff000000.toInt() })

        // Restoring live OSD0 control word restores live scanout
        b.write(0x030, 4, 0x1fc7f088L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(5L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff00ff00.toInt(), pixels[0])
    }

    @Test fun native1024x768DisplayPublishes1To1ZeroResamplingForPicYuvOsd0AndRgba() {
        val nativeMain = object : DisplayPort {
            override val width = 1024
            override val height = 768
            private val buf = IntArray(width * height)
            override var sequence = 0L; private set
            override fun presentArgb(pixels: IntArray, width: Int, height: Int) {
                require(width == this.width && height == this.height && pixels.size == buf.size)
                pixels.copyInto(buf)
                sequence++
            }
            override fun copyArgb(target: IntArray): Boolean {
                if (sequence == 0L) return false
                buf.copyInto(target)
                return true
            }
        }
        val osd0Addr = 0x0614_0000L
        val osd0Size = 1024 * 768 * 4
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        // Put distinct 1px markers at exact 1024x768 coordinates (would be shifted/dropped under 640x480 resampling!)
        osd0Data.putInt(0, 0xff0000ff.toInt()) // (0,0) red
        osd0Data.putInt((1 * 1024 + 1) * 4, 0x00ff00ff) // (1,1) green
        osd0Data.putInt((767 * 1024 + 1023) * 4, 0x0000ffff) // (1023,767) blue
        context.entries["display.main"] = nativeMain
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osd0Addr && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd0Data.array(), 0, osd0Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val component = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        )
        component.start()
        val b = bank()
        b.write(0x008, 4, 0x11000L)
        b.write(0x030, 4, 0x1fc7f088L)
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)
        b.write(0x600, 4, 0x10000005L)

        assertEquals(1L, nativeMain.sequence)
        val out = IntArray(1024 * 768)
        assertTrue(nativeMain.copyArgb(out))
        assertEquals(0xffff0000.toInt(), out[0])
        assertEquals(0xff00ff00.toInt(), out[1 * 1024 + 1])
        assertEquals(0xff0000ff.toInt(), out[767 * 1024 + 1023])
    }

    @Test fun continuousScanoutCompositesOsd0OverPic1WithLiveViewControlWord20080001() =
        checkLetterboxedPicture(576)

    @Test fun moviePlaybackClipsFullHeightPictureWindowAtCanvasEdge() =
        checkLetterboxedPicture(768)

    private fun checkLetterboxedPicture(requestedHeight: Int) {
        val osd0Addr = 0x0614_0000L
        val osd0Size = 1024 * 768 * 4
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        osd0Data.putInt(0, 0xff0000ff.toInt()) // opaque red HUD pixel at (0,0); rest transparent

        val pic1Addr = 0x8996_0000L
        val pic1Stride = 2048
        val pic1H = minOf(requestedHeight, 768 - 96)
        val pic1Size = pic1Stride * pic1H
        val pic1Data = ByteArray(pic1Size)
        for (i in 0 until 576 * pic1Stride step 4) {
            pic1Data[i] = 0x80.toByte()
            pic1Data[i + 1] = 0xff.toByte()
            pic1Data[i + 2] = 0x80.toByte()
            pic1Data[i + 3] = 0xff.toByte()
        }

        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                require((address == osd0Addr && size == osd0Size.toLong()) || (address == pic1Addr && size == pic1Size.toLong()))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    osd0Addr -> destination.put(osd0Data.array(), 0, osd0Size)
                    pic1Addr -> destination.put(pic1Data, 0, pic1Size)
                    else -> error("Unexpected DMA read at 0x${address.toString(16)}")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        val component = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        )
        component.start()
        val b = bank()
        // Live GFX100S shooting HUD: both PIC1 (bit4) and OSD0 (bit16) enabled, with 0x030 = 0x20080001
        b.write(0x008, 4, 0x11010L)
        b.write(0x030, 4, 0x20080001L)
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)
        b.write(0x160, 4, 0xffL)
        b.write(0x164, 4, 0L)
        b.write(0x168, 4, pic1Addr)
        b.write(0x16c, 4, (pic1Stride / 8).toLong())
        b.write(0x170, 4, 0x03000400L)
        b.write(0x174, 4, 96L shl 16)
        b.write(0x178, 4, (requestedHeight.toLong() shl 16) or 1024L)
        b.write(0x17c, 4, 0x10001000L)
        b.write(0x600, 4, 0x10000005L)

        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xffff0000.toInt(), pixels[0]) // red OSD0 HUD over letterbox
        assertEquals(0xffffffff.toInt(), pixels[60 * 640 + 1]) // white PIC1 live view beneath transparent OSD0

        assertEquals(0xff000000.toInt(), pixels[479 * 640 + 1]) // bottom letterbox below video

        // When PIC1 is disabled while OSD0 + bit 12 + 0x030 = 0x20080001 remain active (e.g. STILL DISP/BACK
        // Info Display mode), OSD0 must render over black backdrop instead of being falsely blanked!
        b.write(0x008, 4, 0x11000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(2L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xffff0000.toInt(), pixels[0]) // red OSD0 Info Display pixel stays visible
        assertEquals(0xff000000.toInt(), pixels[60 * 640 + 1]) // PIC1 disabled -> black backdrop beneath transparent OSD0

        // Clearing bit 12 active output gate (stock firmware power-off/mute) still blanks to pure black
        b.write(0x008, 4, 0x10000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(3L, main.sequence)
        assertTrue(main.copyArgb(pixels))
        assertTrue(pixels.all { it == 0xff000000.toInt() })
    }

    @Test fun continuousScanoutRunStopControlBlanksInactiveEvfOrLcdAndTracksOutputActive() {
        val evf = ArgbFrameBuffer(1024, 768)
        val nativeMain = ArgbFrameBuffer(1024, 768)
        context.entries["display.main"] = nativeMain
        context.entries["display.evf"] = evf
        val mainAddr = base
        val evfAddr = 0x82000000L
        val osd0Size = 1024 * 768 * 4
        val mainOsd = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0xff0000ff.toInt()) // opaque red
        }
        val evfOsd = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x0000ffff) // opaque blue
        }
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && (address == mainAddr || address == evfAddr) && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    mainAddr -> destination.put(mainOsd.array(), 0, osd0Size)
                    evfAddr -> destination.put(evfOsd.array(), 0, osd0Size)
                    else -> error("Unexpected address $address")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val comp = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                LcdcFirstScanout.EVF_SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.EVF_LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        comp.start()

        val lcdc0 = bank(0)
        val lcdc1 = bank(1)
        val buf = IntArray(1024 * 768)

        // 1. Start LCDC1 (Rear LCD) -> outputActive = true, EVF still false
        lcdc1.write(0x004, 4, 1L)
        lcdc1.write(0x008, 4, 0x11000L)
        lcdc1.write(0x030, 4, 0x1fc7f088L)
        pair(lcdc1, mainAddr)
        setGeometry(lcdc1)
        lcdc1.write(0x060, 4, 0x10001000L)
        lcdc1.write(0x600, 4, 1L)
        assertEquals(1L, nativeMain.sequence)
        assertTrue(nativeMain.copyArgb(buf))
        assertEquals(0xffff0000.toInt(), buf[0])
        assertEquals(true, (comp.state()["scanout"] as Map<*, *>)["outputActive"])
        assertEquals(false, (comp.state()["evfScanoutState"] as Map<*, *>)["outputActive"])

        // 2. Firmware mutes LCDC1 via +0x004 Run/Stop 1 -> 0 and activates LCDC0 (EVF)
        lcdc1.write(0x004, 4, 0L)
        assertEquals(2L, nativeMain.sequence)
        assertTrue(nativeMain.copyArgb(buf))
        assertTrue(buf.all { it == 0xff000000.toInt() })
        assertEquals(false, (comp.state()["scanout"] as Map<*, *>)["outputActive"])

        lcdc0.write(0x004, 4, 1L)
        lcdc0.write(0x008, 4, 0x11000L)
        lcdc0.write(0x030, 4, 0x1fc7f088L)
        pair(lcdc0, evfAddr)
        setGeometry(lcdc0)
        lcdc0.write(0x060, 4, 0x10001000L)
        lcdc0.write(0x600, 4, 1L)
        assertEquals(1L, evf.sequence)
        assertTrue(evf.copyArgb(buf))
        assertEquals(0xff0000ff.toInt(), buf[0])
        assertEquals(true, (comp.state()["evfScanoutState"] as Map<*, *>)["outputActive"])

        // 3. Firmware mutes LCDC0 via +0x004 1 -> 0 -> EVF blanks to black, outputActive = false
        lcdc0.write(0x004, 4, 0L)
        assertEquals(2L, evf.sequence)
        assertTrue(evf.copyArgb(buf))
        assertTrue(buf.all { it == 0xff000000.toInt() })
        assertEquals(false, (comp.state()["evfScanoutState"] as Map<*, *>)["outputActive"])

        // 4. Snapshot format stays 100% unchanged (no outputActive key persisted)
        val snap = comp.saveSnapshot()
        assertFalse("outputActive" in (snap["scanout"] as Map<*, *>))
        assertFalse("outputActive" in (snap["evfScanout"] as Map<*, *>))
    }

    @Test fun liveChromaKey0x20080001WithOsd0EnabledPublishesWithoutFalseBlanking() {
        val osd0Addr = 0x0614_0000L
        val osd0Size = 1024 * 768 * 4
        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        osd0Data.putInt(0, 0x112233ff) // opaque OSD pixel
        context.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osd0Addr && size == osd0Size.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd0Data.array(), 0, osd0Size)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }

        // Snapshot saved with continuousScanout=false upgrades cleanly to continuousScanout=true
        val nonCont = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to false,
            ),
        ) as StatefulComponent
        nonCont.start()
        val saved = nonCont.saveSnapshot()

        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val cont = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        cont.start()
        cont.restoreSnapshot(saved)

        // Shooting HUD / PLAY mode: OSD0 enabled (0x11000) + chroma-key 0x20080001 (S4:0x0126BB44)
        // must publish live OSD0 without triggering false blanking.
        val b = bank()
        b.write(0x008, 4, 0x11000L)
        b.write(0x030, 4, 0x20080001L)
        pair(b, osd0Addr)
        setGeometry(b)
        b.write(0x060, 4, 0x10001000L)
        b.write(0x600, 4, 0x10000005L)
        assertEquals(1L, main.sequence)
        val pixels = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixels))
        assertEquals(0xff112233.toInt(), pixels[0])

        // Continuous scanout publishes past frameLimit (4) and restores snapshots cleanly.
        repeat(5) {
            b.write(0x600, 4, 0x10000005L)
        }
        assertEquals(6L, main.sequence)
        val beyondLimitSnap = cont.saveSnapshot()
        assertEquals(6, (beyondLimitSnap["scanout"] as Map<*, *>)["publishedFrames"])

        context.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val resumed = VglibInitFactory().create(
            context,
            config + mapOf(
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                "continuousScanout" to true,
            ),
        ) as StatefulComponent
        resumed.start()
        resumed.restoreSnapshot(beyondLimitSnap)
        bank().write(0x600, 4, 0x10000005L)
        assertEquals(7L, main.sequence)

        // When PIC1 points to a transient unmapped DDR address (e.g. during S1/S2 or STILL<->MOVIE switch),
        // continuousScanout skips only the unmapped PIC1 layer without failing and still presents live OSD0.
        val b2 = bank()
        b2.write(0x008, 4, 0x11010L) // OSD0 + ACTIVE_GATE + PIC1
        b2.write(0x160, 4, 0xFFL)
        b2.write(0x164, 4, 0L)
        b2.write(0x168, 4, 0x50000000L) // outside mapped 0x20000000..0x20300000 OSD0 buffer
        b2.write(0x16c, 4, 256L) // 2048 B stride
        b2.write(0x170, 4, (768L shl 16) or 1024L)
        b2.write(0x174, 4, 0L)
        b2.write(0x178, 4, (768L shl 16) or 1024L)
        b2.write(0x17c, 4, 0x10001000L)
        b2.write(0x600, 4, 0x10000005L)
        assertEquals(8L, main.sequence)
        val pixelsAfter = IntArray(640 * 480)
        assertTrue(main.copyArgb(pixelsAfter))
        assertEquals(0xff112233.toInt(), pixelsAfter[0])
    }

    @Test fun continuousScanoutFiresEnabledIrq118AndIrq117BeforeFixedFirstBootDeadline() {
        val ctx = LcdcFunctionalTest.Context()
        val enabled = mutableSetOf<Int>()
        val pulses = mutableListOf<Int>()
        ctx.entries["soc-gic.lines"] = object : EnabledEdgeInterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                if (high) pulses += irq
            }
            override fun edgeState(irq: Int) = EdgeInterruptState(edge = true, high = false, pending = false)
            override fun lineEnabled(irq: Int) = irq in enabled
        }
        val cfg = config + mapOf(
            LcdcIrq118.SETTING to 315_113_473L,
            LcdcIrq117.SETTING to 446_113_473L,
            LcdcIrq118.SYNC to 427_113_473L,
            "continuousScanout" to true,
        )
        val vglib = VglibInitFactory().create(ctx, cfg) as StatefulComponent
        vglib.start()
        val lcdc1 = ctx.entries.getValue(LcdcFunctional.port(1)) as TickAwareMmioDevice

        // Before guest enables IRQ 118 or 117, neither fires even after polling period passes.
        ctx.tick = LcdcIrq118.PERIOD
        lcdc1.beforeInstruction(ctx.tick)
        assertTrue(pulses.isEmpty(), "Expected no pulses before GIC enables IRQ 118/117, got $pulses")

        // On a faster second boot (persisted NVRAM), guest enables IRQ 118 & 117 well before Boot #1's fixed ticks.
        enabled += 118
        ctx.tick = LcdcIrq118.PERIOD * 2
        lcdc1.beforeInstruction(ctx.tick)
        assertEquals(listOf(118), pulses)

        enabled += 117
        ctx.tick = LcdcIrq118.PERIOD * 3
        lcdc1.beforeInstruction(ctx.tick)
        assertTrue(117 in pulses, "Expected IRQ 117 to fire dynamically before 446M deadline when enabled in continuousScanout mode, got $pulses")

        // Snapshot save & restore with early continuous firing tick (< fixed Boot #1 deadline) succeeds cleanly.
        val snap = vglib.saveSnapshot()
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val restored = VglibInitFactory().create(ctx, cfg) as StatefulComponent
        restored.start()
        restored.restoreSnapshot(snap)
        vglib.close()
        restored.close()
    }
}
