package com.github.nyabanban.fuji.emulator.components.isp

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-silicon regression tests for `P1-FIX-MF`:
 * - `BUG-V3-5-3`: MF Dual Display `LV Ch0` (`+0xD4[18:16]=3`, `0x10030000`) must emit standard packed
 *   `UYVY422` (`[U, Y0, V, Y1]`, neutral chroma `0x80` on achromatic scenes) for LCD `PIC0`, while
 *   `LV Ch0` Face/Eye-AF (`+0xD4[18:16]=4`, `0x10040000`) continues to emit `YYUV422` (`[Y0, Y1, Cb, Cr]`);
 *   and `LV Ch1` right `PIC1` inset (`3888x1470 -> 288x192, +0x1C=0x21434`) magnifies the center ROI (~5.35x).
 * - `BUG-V3-5-4`: MF Focus Check (`scr=0x0121`) progressive zoom levels (`1.0x < Z1 3.79x < Z3 5.35x < Z2 8.04x`)
 *   driven strictly by hardware `CCDIF 0xFFF6E000` (`+0x04/+0x14/+0x18`) + `LV Ch1` (`+0x0C/+0x18/+0x1C/+0x20`),
 *   with exact cache invalidation and 0-bit parity between native SIMD and JVM fallback.
 */
class MfDualDisplayAndFocusCheckZoomTest {

    /**
     * 1024x576 16-bit achromatic Bayer test pattern: dark gray background (`0x1800`) with a centered
     * bright white square (`0xE000`, `width=96, height=96`, `x in [464,560), y in [240,336)`).
     */
    private val centerSquareSensor = object : SensorPort {
        override fun expose(exposure: Exposure, destination: ByteBuffer): Int {
            val w = 1024
            val h = 576
            val bb = destination.order(ByteOrder.LITTLE_ENDIAN)
            for (y in 0 until h) {
                val inY = y in 240 until 336
                for (x in 0 until w) {
                    val v = if (inY && x in 464 until 560) 0xE000 else 0x1800
                    bb.putShort(v.toShort())
                }
            }
            return w * h * 2
        }
    }

    private fun countBrightUyvyWidthAtMidRow(buf: ByteArray, width: Int, height: Int, stride: Int): Int {
        val rowOff = (height / 2) * stride
        var bright = 0
        for (x in 0 until width) {
            val yVal = buf[rowOff + x * 2 + 1].toInt() and 0xFF
            if (yVal > 170) bright++
        }
        return bright
    }

    private fun meanChromaDeviationUyvy(buf: ByteArray, width: Int, height: Int, stride: Int): Double {
        var sum = 0L
        var count = 0L
        for (y in 8 until height - 8 step 4) {
            val rowOff = y * stride
            var x = 8
            while (x < width - 8) {
                val u = buf[rowOff + x * 2].toInt() and 0xFF
                val v = buf[rowOff + x * 2 + 2].toInt() and 0xFF
                sum += maxOf(abs(u - 128), abs(v - 128))
                count++
                x += 2
            }
        }
        return if (count == 0L) 0.0 else sum.toDouble() / count.toDouble()
    }

    private class ArrayDmaMemory(private val base: Long, val backing: ByteArray) : DmaMemory {
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && (address - base) + size <= backing.size.toLong()) {
                "Out of bounds DMA 0x${address.toString(16)} len=$size"
            }
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val off = (address - base).toInt()
            destination.put(backing, off, destination.remaining())
        }

        override fun write(address: Long, source: ByteBuffer) {
            val off = (address - base).toInt()
            source.get(backing, off, source.remaining())
        }
    }

    private fun newTestHost(tmp: Path, mem: DmaMemory, tick: Long): Pair<Ports, ComponentContext> {
        val claims = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        val ctx = object : ComponentContext {
            override val directory: Path = tmp
            override val clock = EmulationClock { tick }
            override fun readData(relative: String): Map<String, Any?> = emptyMap()
            override val ports: Ports = ports
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = tmp.resolve(relative)
        }
        ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) = Unit
        })
        ports.register("cmos", "cmos", SensorPort::class.java, centerSquareSensor)
        ports.register("cpu", "cpu.memory", DmaMemory::class.java, mem)
        return ports to ctx
    }

    private fun v3Config(): Map<String, Any> = mapOf(
        "profile" to SocIspFactory.PROFILE_V3,
        "initialFill" to 0L,
        "resultSurfaceFile" to "",
        "resultSurfaceSha256" to "",
        "autoFrameIrqs" to false,
        "slvsEcSilicon" to false,
    )

    @Test
    fun lvCh0SelectsUyvyForDualDisplayPic0AndYyuv422ForFaceAfAndMagnifiesPic1(@TempDir tmp: Path) {
        val base = 0x3C00_0000L
        val ram = ByteArray(16 * 1024 * 1024)
        val mem = ArrayDmaMemory(base, ram)
        val (ports, ctx) = newTestHost(tmp, mem, 100_000L)

        val isp = SocIspFactory().create(ctx, v3Config())
        isp.start()
        try {
            val lv0 = ports.find(SocIspFactory.PORT_LIVE_VIEW_0, MmioDevice::class.java)!!
            val lv1 = ports.find(SocIspFactory.PORT_LIVE_VIEW_1, MmioDevice::class.java)!!
            val b6e = ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java)!!

            // 1. Face/Eye-AF mode on LV Ch0: +0xD4 = 0x10040000 (mode 4 -> YYUV422 [Y0, Y1, Cb, Cr])
            val faceAddr = base + 0x10_0000L
            b6e.write(0x014L, 4, 0x0010_0516L)
            b6e.write(0x018L, 4, 0x0010_03eeL)
            lv0.write(0x00cL, 4, 0x03d6_0515L)
            lv0.write(0x018L, 4, 0x01df_027fL) // 640x480
            lv0.write(0x01cL, 4, 0x0002_1019L)
            lv0.write(0x020L, 4, 0x0002_1019L)
            lv0.write(0x0c4L, 4, faceAddr)
            lv0.write(0x0d0L, 4, 0x0500L) // 1280
            lv0.write(0x0d4L, 4, 0x1004_0000L) // mode 4 = YYUV422

            // Center pixel (320, 240) in YYUV422 has [Y0, Y1, Cb, Cr]: bytes 0,1 are bright Y (>170), bytes 2,3 are 0x80
            val faceCenterOff = (faceAddr - base).toInt() + 240 * 1280 + (320 / 2) * 4
            assertTrue((ram[faceCenterOff].toInt() and 0xFF) > 170, "YYUV422 byte 0 must be Y0")
            assertTrue((ram[faceCenterOff + 1].toInt() and 0xFF) > 170, "YYUV422 byte 1 must be Y1")
            assertTrue(abs((ram[faceCenterOff + 2].toInt() and 0xFF) - 128) <= 2, "YYUV422 byte 2 must be neutral Cb")
            assertTrue(abs((ram[faceCenterOff + 3].toInt() and 0xFF) - 128) <= 2, "YYUV422 byte 3 must be neutral Cr")

            // 2. MF Dual Display (exact GXUP0004.DAT registers):
            //    CCDIF 3888x1494; LV0 = PIC0 left (704x504, ctrl=0x10030000 -> UYVY422, 1.0x full frame)
            //    LV1 = PIC1 right (288x192, ctrl=0x10020000 -> UYVY422, ~5.35x magnified)
            val pic0Addr = base + 0x30_0000L
            val pic1Addr = base + 0x60_0000L
            b6e.write(0x004L, 4, 0L)
            b6e.write(0x014L, 4, 0x002c_0f30L)
            b6e.write(0x018L, 4, 0x0010_05d6L)

            lv0.write(0x00cL, 4, 0x05bd_0f2fL) // 3888x1470
            lv0.write(0x018L, 4, 0x01f7_02bfL) // 704x504
            lv0.write(0x01cL, 4, 0x0004_1702L)
            lv0.write(0x020L, 4, 0x0002_1703L)
            lv0.write(0x0c4L, 4, pic0Addr)
            lv0.write(0x0d0L, 4, 0x0a00L) // 2560
            lv0.write(0x0d4L, 4, 0x1003_0000L) // mode 3 = UYVY422!

            lv1.write(0x00cL, 4, 0x05bd_0f2fL) // 3888x1470
            lv1.write(0x018L, 4, 0x00bf_011fL) // 288x192
            lv1.write(0x01cL, 4, 0x0002_1434L) // 2 * 1.2627 * 288 / 3888 = 0.187 (~5.35x)
            lv1.write(0x020L, 4, 0x0001_143dL)
            lv1.write(0x0c4L, 4, pic1Addr)
            lv1.write(0x0d0L, 4, 0x0a00L)
            lv1.write(0x0d4L, 4, 0x1002_0000L) // mode 2 = UYVY422!

            // BUG-V3-5-3 Part A: PIC0 MUST be valid UYVY (neutral chroma, zero green striping!)
            val pic0Buf = ram.copyOfRange((pic0Addr - base).toInt(), (pic0Addr - base).toInt() + 504 * 2560)
            val pic0MeanChroma = meanChromaDeviationUyvy(pic0Buf, 704, 504, 2560)
            assertTrue(pic0MeanChroma < 2.0, "BUG-V3-5-3: Dual Display PIC0 must emit neutral UYVY422 (not swizzled YYUV422 green stripes), meanChromaDev=$pic0MeanChroma")
            val pic0SquareW = countBrightUyvyWidthAtMidRow(pic0Buf, 704, 504, 2560)
            // With BUG-V3-2-2 aspect-preserving cover framing (576 * 704/504 = 804px base crop),
            // 96/804 * 704 ~= 84 px at 1.0x full frame.
            assertTrue(pic0SquareW in 74..96, "Dual Display PIC0 must show 1.0x full frame (expected ~84px white square, got $pic0SquareW)")

            // BUG-V3-5-3 Part B: PIC1 MUST magnify center square (~5.35x -> 96 / (1024*0.187) * 288 ~= 144 px out of 288!)
            val pic1Buf = ram.copyOfRange((pic1Addr - base).toInt(), (pic1Addr - base).toInt() + 192 * 2560)
            val pic1SquareW = countBrightUyvyWidthAtMidRow(pic1Buf, 288, 192, 2560)
            val pic1Ratio = pic1SquareW.toDouble() / 288.0
            val pic0Ratio = pic0SquareW.toDouble() / 704.0
            assertTrue(
                pic1Ratio > pic0Ratio * 4.0,
                "BUG-V3-5-3: Dual Display PIC1 must magnify center ROI >4x vs PIC0 (pic1=$pic1SquareW/288, pic0=$pic0SquareW/704)",
            )
        } finally {
            isp.close()
        }
    }

    @Test
    fun focusCheckZoomScalesProgressivelyAcrossZ1Z3Z2WithSimdJvmParity(@TempDir tmp: Path) {
        val base = 0x3C00_0000L
        val ram = ByteArray(16 * 1024 * 1024)
        val mem = ArrayDmaMemory(base, ram)
        val (ports, ctx) = newTestHost(tmp, mem, 200_000L)

        val isp = SocIspFactory().create(ctx, v3Config())
        isp.start()
        try {
            val lv1 = ports.find(SocIspFactory.PORT_LIVE_VIEW_1, MmioDevice::class.java)!!
            val b6e = ports.find(SocIspFactory.PORT_BANK_6E000, MmioDevice::class.java)!!
            val outAddr = base + 0x20_0000L
            val stride = 2560

            fun renderAndMeasure(r04: Long, r14: Long, r18: Long, inSz: Long, hScale: Long, vScale: Long): Int {
                b6e.write(0x004L, 4, r04)
                b6e.write(0x014L, 4, r14)
                b6e.write(0x018L, 4, r18)
                lv1.write(0x00cL, 4, inSz)
                lv1.write(0x018L, 4, 0x02ff_03ffL) // 1024x768
                lv1.write(0x01cL, 4, hScale)
                lv1.write(0x020L, 4, vScale)
                lv1.write(0x0c4L, 4, outAddr)
                lv1.write(0x0d0L, 4, stride.toLong())
                lv1.write(0x0d4L, 4, 0x1005_0000L)
                val frame = ram.copyOfRange((outAddr - base).toInt(), (outAddr - base).toInt() + 768 * stride)
                assertTrue(meanChromaDeviationUyvy(frame, 1024, 768, stride) < 2.0, "Focus Check UYVY must preserve Bayer RGGB chroma neutrality")
                return countBrightUyvyWidthAtMidRow(frame, 1024, 768, stride)
            }

            // Exact GFX100S 2.13 register snapshots captured live in ProbeMfRegs:
            val w1x = renderAndMeasure(0L, 0x0010_0516L, 0x0010_03eeL, 0x03d6_0515L, 0x0000_1421L, 0x0000_1421L)
            val wZ1 = renderAndMeasure(0L, 0x03e2_078dL, 0x0010_058eL, 0x0510_078cL, 0x0001_102cL, 0x0001_102dL)
            val wZ3 = renderAndMeasure(1L, 0x0000_02eeL, 0x0000_02b0L, 0x028c_02edL, 0x0000_0b5eL, 0x0000_0b5eL)
            val wZ2 = renderAndMeasure(1L, 0x0000_02eeL, 0x0000_02b0L, 0x0231_02edL, 0x0000_0791L, 0x0000_0791L)

            // Expected spans of the 96px center square across 1024px output (with BUG-V3-2-2 4:3 cover base 768x576):
            // 1.0x ~= 128px, Z1 (~3.79x) ~= 485px, Z3 (~5.35x) ~= 685px, Z2 (~8.04x) ~= 1024px
            assertTrue(w1x in 118..138, "1.0x normal MF width expected ~128px, got $w1x")
            assertTrue(wZ1 > w1x * 3, "BUG-V3-5-4: Focus Check Z1 must zoom >3x vs 1.0x (w1x=$w1x, wZ1=$wZ1)")
            assertTrue(wZ3 > wZ1 + 80, "BUG-V3-5-4: Focus Check Z3 must zoom further than Z1 (wZ1=$wZ1, wZ3=$wZ3)")
            assertTrue(wZ2 > wZ3 + 150, "BUG-V3-5-4: Focus Check Z2 max zoom must zoom further than Z3 (wZ3=$wZ3, wZ2=$wZ2)")
        } finally {
            isp.close()
        }

        // Direct JVM vs Native SIMD bit-exact parity check on a zoomed 1024x768 crop!
        val engineSimd = LiveViewDmaEngine().apply { allowNativeSimd = true }
        val engineJvm = LiveViewDmaEngine().apply { allowNativeSimd = false }
        val dstSimd = ByteArray(768 * 2048)
        val dstJvm = ByteArray(768 * 2048)
        val zoom = LiveViewDmaEngine.CropZoom(fracW = 0.25)
        assertTrue(
            engineSimd.pumpLiveViewDma(
                tick = 1L,
                addr = base,
                strideLong = 2048L,
                sizeWord = 0x02ff_03ffL,
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = centerSquareSensor,
                memory = ArrayDmaMemory(base, dstSimd),
                lens = null,
                cropZoom = zoom,
            ),
        )
        assertTrue(
            engineJvm.pumpLiveViewDma(
                tick = 1L,
                addr = base,
                strideLong = 2048L,
                sizeWord = 0x02ff_03ffL,
                ctrl = LiveViewDmaEngine.ENABLE_BIT_28 or 0x50000L,
                sensor = centerSquareSensor,
                memory = ArrayDmaMemory(base, dstJvm),
                lens = null,
                cropZoom = zoom,
            ),
        )
        assertEquals(dstJvm.toList(), dstSimd.toList(), "Zoomed CropZoom must have 0-bit parity between Native SIMD and JVM fallback")
    }
}
