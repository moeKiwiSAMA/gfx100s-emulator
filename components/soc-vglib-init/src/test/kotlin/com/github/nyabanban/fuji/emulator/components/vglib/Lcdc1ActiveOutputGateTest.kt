package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/**
 * Hardware-fidelity tests for real GFX100S 2.13 `LCDC1` (`0xFFFA9000`) layer-enable (`+0x008`)
 * and OSD0 control (`+0x030`) semantics verified from `section4.bin`:
 * - `+0x008` bit 16 (`0x00010000`): `OSD0` enable
 * - `+0x008` bit 12 (`0x00001000`): Active output/blend gate (clearing blanks Rear LCD to flat gray!)
 * - `+0x008` bit 8  (`0x00000100`): `PIC0` enable
 * - `+0x008` bit 4  (`0x00000010`): `PIC1` live camera video enable
 * - `+0x030 = 0x20080001`: transparent Live View mode (`0x00000000` reveals `PIC1`)
 * - `+0x030 = 0x1FC7F088`: opaque OSD0 backdrop mode (hides `PIC1`)
 */
class Lcdc1ActiveOutputGateTest {
    private val osdAddr = 0x0614_0000L
    private val pic1Addr = 0x0896_0000L
    private val osdSize = 1024 * 768 * 4
    private val pic1Stride = 2048
    private val pic1H = 576
    private val pic1Size = pic1Stride * pic1H

    private fun setupContext(): Triple<LcdcFunctionalTest.Context, ArgbFrameBuffer, Pair<ByteBuffer, ByteArray>> {
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val osdData = ByteBuffer.allocate(osdSize).order(ByteOrder.LITTLE_ENDIAN)
        val pic1Data = ByteArray(pic1Size)
        // Fill PIC1 with neutral white YYUV422 (Y0=0xFF, Y1=0xFF, U=0x80, V=0x80)
        for (i in 0 until pic1Size step 4) {
            pic1Data[i] = 0xff.toByte()
            pic1Data[i + 1] = 0xff.toByte()
            pic1Data[i + 2] = 0x80.toByte()
            pic1Data[i + 3] = 0x80.toByte()
        }
        // Floating card pixel at (0,0) = opaque red (0xFF0000FF) and at (100,200) = nudged near-black (0x040404FF)
        osdData.putInt(0, 0xff0000ff.toInt())
        osdData.putInt((200 * 1024 + 100) * 4, 0x040404ff)

        ctx.entries["display.main"] = main
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write)
                require((address == osdAddr && size == osdSize.toLong()) || (address == pic1Addr && size == pic1Size.toLong()))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    osdAddr -> destination.put(osdData.array(), 0, osdSize)
                    pic1Addr -> destination.put(pic1Data, 0, pic1Size)
                    else -> error("Unexpected DMA read at 0x${address.toString(16)}")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }
        return Triple(ctx, main, osdData to pic1Data)
    }

    private fun programGeometry(bank: MmioDevice) {
        bank.write(0x040, 4, 0L)
        bank.write(0x044, 4, osdAddr)
        bank.write(0x050, 4, 0x0001_0400L)
        bank.write(0x054, 4, 0x0300_0400L)
        bank.write(0x058, 4, 0L)
        bank.write(0x05c, 4, 0x0300_0400L)
        bank.write(0x060, 4, 0x1000_1000L)
        bank.write(0x160, 4, 0xffL)
        bank.write(0x164, 4, 0L)
        bank.write(0x168, 4, pic1Addr)
        bank.write(0x16c, 4, (pic1Stride / 8).toLong())
        bank.write(0x170, 4, 0x0300_0400L)
        bank.write(0x174, 4, 96L shl 16)
        bank.write(0x178, 4, 0x0240_0400L)
        bank.write(0x17c, 4, 0x1000_1000L)
    }

    @Test
    fun missingBit12BlanksRearLcdToGrayWhileBit12PreservedRendersTransparentAndOpaqueModes() {
        val (ctx, main, _) = setupContext()
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
        val layers = ctx.entries.getValue(LcdcFunctional.LCDC1_LAYERS_PORT) as LcdcLayerObservabilityPort
        val buf = IntArray(1024 * 768)
        programGeometry(bank1)

        // 1. Live View transparent floating overlay: 0x008 = 0x00011010 (OSD0 + bit12 + PIC1), 0x030 = 0x20080001
        bank1.write(0x008, 4, 0x0001_1010L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertEquals(0xffff0000.toInt(), buf[0], "Opaque red OSD0 pixel at (0,0)")
        assertEquals(0xff040404.toInt(), buf[200 * 1024 + 100], "Nudged near-black card pixel at (100,200)")
        assertEquals(0xffffffff.toInt(), buf[200 * 1024 + 500], "Transparent 0x00000000 OSD0 must reveal white PIC1 live view")
        val liveLayerState = layers.lcdc1LayerState()
        assertTrue(liveLayerState.activeOutputGate)
        assertTrue(liveLayerState.osd0Enabled)
        assertFalse(liveLayerState.pic0Enabled)
        assertTrue(liveLayerState.pic1Enabled)
        assertEquals("transparent-chroma-key", liveLayerState.osd0Mode)
        assertTrue(liveLayerState.outputActive)
        assertNull(liveLayerState.blankReason)
        assertEquals(0L, liveLayerState.activeGateBlankCount)

        // 2. Buggy bootloader write: 0x008 = 0x00010000 (CLEARS bit 12 active output gate!) + 0x030 = 0x1FC7F088
        // Real GFX100S LCDC1 blanks the panel to flat gray instead of displaying OSD0!
        bank1.write(0x008, 4, 0x0001_0000L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(2L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertTrue(buf.all { it == LcdcFirstScanout.BLANK_GRAY_ARGB }, "Missing bit 12 must blank Rear LCD to flat gray 0xFF808080")
        val grayState = layers.lcdc1LayerState()
        assertFalse(grayState.activeOutputGate)
        assertTrue(grayState.osd0Enabled)
        assertFalse(grayState.outputActive)
        assertEquals("active-output-gate-cleared", grayState.blankReason)
        assertEquals(1L, grayState.activeGateBlankCount)

        // 3. Fixed RMW takeover: 0x008 = 0x00011000 (OSD0 + bit 12 active gate, PIC0/PIC1 cleared) + 0x030 = 0x1FC7F088 (opaque OOBE dark charcoal)
        val oobeCharcoal = LcdcFirstScanout.decodeBgColor(LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        assertEquals(0xff212320.toInt(), oobeCharcoal, "0x1FC7F088 must decode to authentic 10-bit YCbCr dark charcoal")
        bank1.write(0x008, 4, 0x0001_1000L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(3L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertEquals(0xffff0000.toInt(), buf[0], "Restored bit 12 renders live opaque OSD0")
        assertEquals(oobeCharcoal, buf[200 * 1024 + 500], "Opaque OSD0 mode hides PIC1 and renders decoded 0x030 BGCOLOR")
        val opaqueState = layers.lcdc1LayerState()
        assertTrue(opaqueState.activeOutputGate)
        assertEquals("opaque", opaqueState.osd0Mode)
        assertTrue(opaqueState.outputActive)
        assertNull(opaqueState.blankReason)

        // 4. Opaque control 0x1FC7F088 hides PIC1 even if PIC1 bit 4 & PIC0 bit 8 remain set
        bank1.write(0x008, 4, 0x0001_1110L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(4L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertEquals(oobeCharcoal, buf[200 * 1024 + 500], "Opaque 0x1FC7F088 must NOT bleed white PIC1 through A=0 pixels")
        val allPlanes = layers.lcdc1LayerState()
        assertTrue(allPlanes.pic0Enabled)
        assertTrue(allPlanes.pic1Enabled)
        assertTrue(allPlanes.osd0Enabled)
        assertTrue(allPlanes.activeOutputGate)

        // 4b. BUG-21 FWUP scr=0x0601 white 10-bit YCbCr BGCOLOR (0x200803FC) renders pure white behind black OSD0 text!
        assertEquals(0xffffffff.toInt(), LcdcFirstScanout.decodeBgColor(LcdcFirstScanout.OSD0_FWUP_WHITE_BGCOLOR))
        assertEquals(0xff000000.toInt(), LcdcFirstScanout.decodeBgColor(LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL))
        bank1.write(0x008, 4, 0x0001_1000L) // PIC1 off, OSD0 + bit 12 on
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_FWUP_WHITE_BGCOLOR)
        bank1.write(0x600, 4, 1L)
        assertEquals(5L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertEquals(0xffff0000.toInt(), buf[0], "Opaque red OSD0 pixel at (0,0)")
        assertEquals(0xff040404.toInt(), buf[200 * 1024 + 100], "Dark OSD0 text must remain crisp over white BGCOLOR")
        assertEquals(0xffffffff.toInt(), buf[200 * 1024 + 500], "Transparent A=0 OSD0 pixel must reveal white 0x200803FC BGCOLOR")

        // 5. Playback "Info Off": 0x008 = 0x00010010 (PIC1 enabled, bit 12 cleared -> hides OSD0 overlay while PIC1 renders!)
        bank1.write(0x008, 4, 0x0001_0010L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_CHROMA_KEY_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(6L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertEquals(0xff000000.toInt(), buf[0], "OSD0 red pixel at (0,0) must be hidden when bit 12 is cleared (Playback Info Off)")
        assertEquals(0xffffffff.toInt(), buf[200 * 1024 + 500], "PIC1 photo must stay visible when bit 12 is cleared in 0x10010")
        assertTrue(layers.lcdc1LayerState().outputActive)

        // 6. Disabling ALL planes (0x008 = 0) blanks to pure black (distinct from gray missing-bit-12 trap)
        bank1.write(0x008, 4, 0L)
        bank1.write(0x600, 4, 1L)
        assertEquals(7L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertTrue(buf.all { it == LcdcFirstScanout.BLANK_BLACK_ARGB })
        assertEquals("planes-disabled", layers.lcdc1LayerState().blankReason)
    }

    @Test
    fun coldBootStrictActiveOutputGateCatchesBare0x00010000AndPreservesSnapshotCompatibility() {
        val (ctx, main, _) = setupContext()
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING to true,
            ),
        ) as StatefulComponent
        comp.start()

        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        val layers = ctx.entries.getValue(LcdcFunctional.LCDC1_LAYERS_PORT) as LcdcLayerObservabilityPort
        val buf = IntArray(1024 * 768)
        programGeometry(bank1)

        // Frame 0 cold-boot bare 0x00010000 (bit 12 == 0) blanks immediately to gray
        bank1.write(0x008, 4, 0x0001_0000L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        bank1.write(0x600, 4, 1L)
        assertEquals(1L, main.sequence)
        assertTrue(main.copyArgb(buf))
        assertTrue(buf.all { it == LcdcFirstScanout.BLANK_GRAY_ARGB })
        assertEquals(1L, layers.lcdc1LayerState().activeGateBlankCount)

        // Snapshot round-trip preserves valid cursor & settings
        val snap = comp.saveSnapshot()
        assertEquals(true, (snap["settings"] as Map<*, *>)[LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING])
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val resumed = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
                LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING to true,
            ),
        ) as StatefulComponent
        resumed.restoreSnapshot(snap)
        assertEquals(snap, resumed.saveSnapshot())

        // Legacy snapshot (without strictActiveOutputGate setting) upgrades cleanly into strictActiveOutputGate=true
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        val legacy = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 4L,
            ),
        ) as StatefulComponent
        val legacySnap = legacy.saveSnapshot()
        assertFalse(LcdcFirstScanout.STRICT_ACTIVE_GATE_SETTING in (legacySnap["settings"] as Map<*, *>))
        resumed.restoreSnapshot(legacySnap)
    }

    @Test
    fun lcdc1ColdBootClockGateAbortsPreLcdevfMmioReadUntilResetUngatesController() {
        val (ctx, _, _) = setupContext()
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                "lcdc1ClockGated" to true,
                "unclockedAccessPolicy" to LcdcFunctional.POLICY_ABORT,
            ),
        ) as StatefulComponent
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice

        // Cold-boot read of 0xFFFA9044 before lcdevf initializes LCDC1 raises AXI External Data Abort!
        val abort = assertFailsWith<PeripheralBusAbortException> { bank1.read(0x044L, 4) }
        assertEquals("soc-vglib-init.lcdc1", abort.peripheralId)
        assertEquals(0x044L, abort.offset)
        assertFalse(abort.write)
        assertEquals(0x008L, abort.dfsr)

        // Firmware reset write (+0x000 = 0) ungates LCDC1, after which +0x044 reads & writes succeed
        bank1.write(0x000L, 4, 0L)
        bank1.write(0x044L, 4, osdAddr)
        assertEquals(osdAddr, bank1.read(0x044L, 4))
        assertEquals(true, (comp.saveSnapshot()["settings"] as Map<*, *>)["lcdc1ClockGated"])

        // Re-gate via soc-vglib-init.lcdc1-power and verify 0xFFF49700 (mode-word, S4:0x012556BC) ungates LCDC1 too
        val lcdc1Power = ctx.entries.getValue(LcdcFunctional.LCDC1_POWER_PORT) as LcdcPowerGatePort
        lcdc1Power.setLcdc0PowerClock(false, false)
        assertFalse(lcdc1Power.lcdc0PowerState().clocked)
        assertFailsWith<PeripheralBusAbortException> { bank1.read(0x044L, 4) }

        val ctxMode = object : ComponentContext by ctx {
            override fun readData(relative: String): Map<String, Any?> = when (relative) {
                "mode" -> mapOf(
                    "schema" to 1,
                    "profile" to ModeWordInput.PROFILE,
                    "readConfidence" to "HYPOTHESIS",
                    "readSample" to 0x0000_0200L,
                    "sourcePreserveMask" to 0xffff_fcffL,
                )
                else -> ctx.readData(relative)
            }
        }
        ctx.entries.keys.removeIf { it.startsWith("soc-vglib-init.") }
        VglibInitFactory().create(
            ctxMode,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                "controlWordInputFile" to "mode",
                "lcdc1ClockGated" to true,
                "unclockedAccessPolicy" to LcdcFunctional.POLICY_ABORT,
            ),
        )
        val gatedLcdc1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        val modeWord = ctx.entries.getValue(ModeWordInput.PORT) as MmioDevice
        assertFailsWith<PeripheralBusAbortException> { gatedLcdc1.read(0x044L, 4) }
        val sample = modeWord.read(0L, 4)
        modeWord.write(0L, 4, sample and 0xffff_fcffL)
        assertEquals(0L, gatedLcdc1.read(0x044L, 4), "Writing 0xFFF49700 mode-word must ungate LCDC1")
    }
}
