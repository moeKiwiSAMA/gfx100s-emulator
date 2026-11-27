package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies real-silicon GFX100S 2.13 `BUG-A3-6` color & brightness hardware paths in `LcdcFirstScanout`:
 * - Path A: SoC `LCDC1` 3×3 13-bit signed Q10 RGB matrix (`+0x4E8..+0x4FC`, `0x400 = 1.0`) + 2nd-stage RGB gain.
 * - Path B: External `SPI5` `PanelBacklightPort` (`soc-spi.panel-backlight`) Rear LCD & EVF brightness modulation.
 */
class LcdcColorMatrixAndBacklightTest {
    private val osdAddr = 0x0614_0000L
    private val osdSize = 1024 * 768 * 4

    private fun createRig(backlight: PanelBacklightState? = null): Triple<MmioDevice, ArgbFrameBuffer, MutableList<PanelBacklightState>> {
        val ctx = LcdcFunctionalTest.Context()
        val main = ArgbFrameBuffer(1024, 768)
        val osd = ByteBuffer.allocate(osdSize).order(ByteOrder.LITTLE_ENDIAN)
        // OSD0 pixel word in RAM is 0xRRGGBBAA LE -> Integer.rotateRight(word, 8) -> 0xAARRGGBB.
        fun putRgba(idx: Int, r: Int, g: Int, b: Int, a: Int = 255) {
            val word = (a and 0xFF) or ((b and 0xFF) shl 8) or ((g and 0xFF) shl 16) or ((r and 0xFF) shl 24)
            osd.putInt(idx * 4, word)
        }
        putRgba(0, 128, 128, 128)
        putRgba(1, 200, 80, 80)
        putRgba(2, 80, 200, 80)

        ctx.entries["display.main"] = main
        val blHolder = mutableListOf<PanelBacklightState>()
        if (backlight != null) {
            blHolder.add(backlight)
            ctx.entries[PanelBacklightPort.PORT_NAME] = object : PanelBacklightPort {
                override fun backlightState(): PanelBacklightState = blHolder.last()
            }
        }
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && address == osdAddr && size == osdSize.toLong())
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(osd.array(), 0, osdSize)
            }
            override fun write(address: Long, source: ByteBuffer) = error("Unexpected write")
        }
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 16L,
                "continuousScanout" to true,
            ),
        )
        comp.start()
        val bank1 = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        bank1.write(0x008, 4, 0x0001_1000L)
        bank1.write(0x030, 4, LcdcFirstScanout.OSD0_OOBE_OPAQUE_CONTROL)
        bank1.write(0x040, 4, 0L)
        bank1.write(0x044, 4, osdAddr)
        bank1.write(0x050, 4, 0x0001_0400L)
        bank1.write(0x054, 4, 0x0300_0400L)
        bank1.write(0x058, 4, 0L)
        bank1.write(0x05c, 4, 0x0300_0400L)
        bank1.write(0x060, 4, 0x1000_1000L)
        return Triple(bank1, main, blHolder)
    }

    @Test
    fun appliesLcdc1Q10ColorMatrixAndBypassesAtIdentity() {
        val (bank1, main, _) = createRig()
        val px = IntArray(1024 * 768)

        // Untouched matrix -> exact pass-through
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        assertEquals(0xFF808080.toInt(), px[0])
        assertEquals(0xFFC85050.toInt(), px[1])

        // Program live-captured GFX100S LCD COLOR +5 vivid saturation Q10 matrix (+0x4E8..+0x4FC)
        bank1.write(0x4E8, 4, 0x1F50_0415L) // R: [+1045, -176]
        bank1.write(0x4EC, 4, 0x0000_1FE4L) // R: [-28]
        bank1.write(0x4F0, 4, 0x0460_1FB2L) // G: [-78, +1120]
        bank1.write(0x4F4, 4, 0x0000_1FE3L) // G: [-29]
        bank1.write(0x4F8, 4, 0x1F51_1FB3L) // B: [-77, -175]
        bank1.write(0x4FC, 4, 0x0000_0433L) // B: [+1075]
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))

        // Red pixel (200,80,80): R=(1045*200-176*80-28*80+512)>>10 = 188, G=(-78*200+1120*80-29*80+512)>>10 = 70
        val redOut = px[1]
        val rOut = (redOut ushr 16) and 0xFF
        val gOut = (redOut ushr 8) and 0xFF
        val bOut = redOut and 0xFF
        assertEquals(188, rOut)
        assertEquals(70, gOut)
        assertEquals(55, bOut)
        assertTrue((rOut - gOut) > (200 - 80) - 5, "Expected vivid red-green separation")

        // Reset to exact Q10 identity (0x400 diagonal) -> exact pass-through restored
        bank1.write(0x4E8, 4, 0x0000_0400L)
        bank1.write(0x4EC, 4, 0L)
        bank1.write(0x4F0, 4, 0x0400_0000L)
        bank1.write(0x4F4, 4, 0L)
        bank1.write(0x4F8, 4, 0L)
        bank1.write(0x4FC, 4, 0x0000_0400L)
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        assertEquals(0xFF808080.toInt(), px[0])
        assertEquals(0xFFC85050.toInt(), px[1])
    }

    @Test
    fun modulatesRenderedLuminanceMonotonicallyWithSpi5PanelBacklightGain() {
        val nominal = PanelBacklightState(0x87, 0x81, 0, 0xFF, 0x24, 1.0f, 0x0E, 0xE0, 0x80, 0x80, 0x80, 1.0f, 0, 0)
        val (bank1, main, blHolder) = createRig(nominal)
        val px = IntArray(1024 * 768)

        // Nominal 1.0 -> exact 128
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val luma0 = (px[0] ushr 16) and 0xFF
        assertEquals(128, luma0)

        // Dim (-5, gain=0.31) -> ~40
        blHolder.add(nominal.copy(lcdDutyLow = 0x0A, lcdGain = 0.31f))
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val lumaMinus5 = (px[0] ushr 16) and 0xFF
        assertEquals(40, lumaMinus5)

        // Bright (+5, gain=1.43) -> ~183
        blHolder.add(nominal.copy(lcdDutyLow = 0xB0, lcdDutyBoost = 0x7B, lcdGain = 1.43f))
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val lumaPlus5 = (px[0] ushr 16) and 0xFF
        assertEquals(183, lumaPlus5)
        assertTrue(lumaMinus5 < luma0 && luma0 < lumaPlus5)

        // With live SPI5 Rear LCD panel active (lcdUpdates > 0) and factory LCD COLOR=0 gamut matrix,
        // physical BOE panel white-point calibration keeps neutral mid-gray neutral (no green tint).
        blHolder.add(nominal.copy(lcdUpdates = 1L))
        bank1.write(0x4E8, 4, 0x0057_0304L)
        bank1.write(0x4EC, 4, 0x0000_0010L)
        bank1.write(0x4F0, 4, 0x03A6_0027L)
        bank1.write(0x4F4, 4, 0x0000_000FL)
        bank1.write(0x4F8, 4, 0x0058_0027L)
        bank1.write(0x4FC, 4, 0x0000_02E0L)
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val gR = (px[0] ushr 16) and 0xFF
        val gG = (px[0] ushr 8) and 0xFF
        val gB = px[0] and 0xFF
        assertTrue(gR in 127..129 && gG in 127..129 && gB in 127..129, "Expected neutral gray (128,128,128), got ($gR,$gG,$gB)")

        assertEquals(0xFFC85050.toInt(), px[1], "Neutral BOE calibration must preserve saturated sRGB patches too")

        // Per-channel LCD COLOR ADJUSTMENT operates independently of the neutral panel calibration.
        bank1.write(0x500, 4, 0x0000_0440L)
        bank1.write(0x504, 4, 0x0400_0000L)
        bank1.write(0x508, 4, 0x0000_03C0L)
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val warmGray = px[0]
        assertTrue(((warmGray ushr 16) and 255) > ((warmGray ushr 8) and 255))
        assertTrue(((warmGray ushr 8) and 255) > (warmGray and 255))
        bank1.write(0x500, 4, 0x0000_0400L)
        bank1.write(0x508, 4, 0x0000_0400L)
        // User color and brightness settings still operate through the physical panel transfer.
        bank1.write(0x4E8, 4, 0x1F50_0415L)
        bank1.write(0x4EC, 4, 0x0000_1FE4L)
        bank1.write(0x4F0, 4, 0x0460_1FB2L)
        bank1.write(0x4F4, 4, 0x0000_1FE3L)
        bank1.write(0x4F8, 4, 0x1F51_1FB3L)
        bank1.write(0x4FC, 4, 0x0000_0433L)
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val vividRed = (px[1] ushr 16) and 255
        val vividGreen = (px[1] ushr 8) and 255
        assertTrue(vividRed - vividGreen > 120, "LCD COLOR +5 should increase red-green separation")
        blHolder.add(nominal.copy(lcdUpdates = 2L, lcdGain = 0.5f))
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        assertTrue(kotlin.math.abs(((px[1] ushr 16) and 255) * 2 - vividRed) <= 2)
        assertTrue(kotlin.math.abs(((px[1] ushr 8) and 255) * 2 - vividGreen) <= 2)

        // BUG-V3-8-5: Boot #2 post-OOBE neutral white-point matrix (firmware 0x0126c4e0:
        // R=[1024,0,1], G=[0,997,0], B=[0,1,1007], row sums [1025, 997, 1008]) must NOT
        // receive the Boot #1 BOE inverse multiplier [1024/875, 1024/988, 1024/863], which
        // previously caused a persistent -14% Green / uniform magenta tint on Boot #2.
        blHolder.add(nominal.copy(lcdUpdates = 20L))
        bank1.write(0x4E8, 4, 0x0000_0400L)
        bank1.write(0x4EC, 4, 0x0000_0001L)
        bank1.write(0x4F0, 4, 0x03E5_0000L)
        bank1.write(0x4F4, 4, 0x0000_0000L)
        bank1.write(0x4F8, 4, 0x0001_0000L)
        bank1.write(0x4FC, 4, 0x0000_03EFL)
        bank1.write(0x600, 4, 1L)
        assertTrue(main.copyArgb(px))
        val b2R = (px[0] ushr 16) and 0xFF
        val b2G = (px[0] ushr 8) and 0xFF
        val b2B = px[0] and 0xFF
        assertTrue(
            b2R in 126..130 && b2G in 123..128 && b2B in 124..128 && kotlin.math.abs(b2R - b2G) <= 5,
            "BUG-V3-8-5: Boot #2 neutral matrix must stay neutral without magenta tint, got ($b2R,$b2G,$b2B)",
        )
    }
}
