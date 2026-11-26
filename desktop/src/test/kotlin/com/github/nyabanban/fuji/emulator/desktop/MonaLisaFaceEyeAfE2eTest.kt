package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonaLisaFaceEyeAfE2eTest {
    private val faceKey = "imageFace7c000"
    private val faceProfile = "face-detect-irq123-v1"
    private val faceRegion = "diagnostic.face.fff7c000"
    private val scalerRegion = "diagnostic.image-scaler.fff7f000"
    private val faceIrqLine = 123
    private val faceDelayTicks = 64L

    /** Compare the programmed LCDC window to the actual published pixels while the emulator is paused. */
    private fun visibleWindowCenterX(rt: EmulatorRuntime, pixels: IntArray): Int? {
        val lcd = requireNotNull(rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
        if ((lcd.read(0x1c4, 4) and 1) == 0L) return null
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val start = lcd.read(0x208, 4).toInt()
        val end = lcd.read(0x20c, 4).toInt()
        val left = start and 8191
        val top = start ushr 16 and 8191
        val right = end and 8191
        val bottom = end ushr 16 and 8191
        if (right <= left || bottom <= top || left < 1 || top < 1 ||
            right >= display.width - 1 || bottom >= display.height - 1) return null
        val paletteIndex = lcd.read(0x1f0, 4) and 3
        val palette = lcd.read(0x1d0 + paletteIndex * 4, 4)
        val isWhite = (palette and 1023) >= 960 && ((palette ushr 10) and 1023) == 512L &&
            ((palette ushr 20) and 1023) == 512L
        display.copyArgb(pixels)
        fun matchesPalette(x: Int, y: Int): Int {
            val p = pixels[y * display.width + x]
            val r = (p ushr 16) and 255
            val g = (p ushr 8) and 255
            val b = p and 255
            val matches = if (isWhite) minOf(r, g, b) > 210 && maxOf(r, g, b) - minOf(r, g, b) < 32
                else g > r + 45 && g > b + 45
            return if (matches) 1 else 0
        }
        var matches = 0
        var backgroundMatches = 0
        for (x in left..right) {
            matches += matchesPalette(x, top) + matchesPalette(x, bottom)
            backgroundMatches += matchesPalette(x, top - 1) + matchesPalette(x, bottom + 1)
        }
        for (y in top + 1 until bottom) {
            matches += matchesPalette(left, y) + matchesPalette(right, y)
            backgroundMatches += matchesPalette(left - 1, y) + matchesPalette(right + 1, y)
        }
        val perimeter = 2 * (right - left + bottom - top)
        return if (matches >= perimeter * 9 / 10 && backgroundMatches < perimeter / 2) (left + right) / 2 else null
    }

    private val halfSizeQ7 = intArrayOf(
        2048, 2580, 3244, 4096, 5201, 6553, 8192, 10240,
        13107, 16384, 20480, 25206, 32768, 40960, 54613,
    )

    private fun linearToSrgbByte(lin: Double): Int {
        val c = lin.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

    /**
     * Demosaics a 1024x576 16-bit RGGB Bayer sensor frame into the exact 640x480 letterboxed
     * `YYUV422` (`[Y0, Y1, Cb, Cr]`, stride 1280) buffer written by `ImageScalerF000` (`F0D8 = 0x88`)
     * into `0x8a9c8000` for `0xFFF7C000` hardware face detection and CPU Eye-AF.
     */
    private fun bayer16ToYyuv422Letterbox640x480(bayer: ByteBuffer, srcW: Int = 1024, srcH: Int = 576): ByteArray {
        val bb = bayer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val qw = srcW / 2
        val qh = srcH / 2
        val rPlane = IntArray(qw * qh)
        val gPlane = IntArray(qw * qh)
        val bPlane = IntArray(qw * qh)
        for (qy in 0 until qh) {
            val r0 = (qy * 2) * srcW
            val r1 = r0 + srcW
            val qRow = qy * qw
            for (qx in 0 until qw) {
                val x0 = qx * 2
                val rRaw = bb.getShort((r0 + x0) * 2).toInt() and 0xFFFF
                val grRaw = bb.getShort((r0 + x0 + 1) * 2).toInt() and 0xFFFF
                val gbRaw = bb.getShort((r1 + x0) * 2).toInt() and 0xFFFF
                val bRaw = bb.getShort((r1 + x0 + 1) * 2).toInt() and 0xFFFF
                rPlane[qRow + qx] = linearToSrgbByte(rRaw / 65535.0)
                gPlane[qRow + qx] = linearToSrgbByte(((grRaw + gbRaw) * 0.5) / 65535.0)
                bPlane[qRow + qx] = linearToSrgbByte(bRaw / 65535.0)
            }
        }

        val dstW = 640
        val dstH = 480
        val activeH = 360
        val topPad = 60
        val stride = dstW * 2
        val out = ByteArray(dstW * dstH * 2) { 0x80.toByte() }
        for (y in 0 until activeH) {
            val sy = ((y.toLong() * qh) / activeH).toInt().coerceIn(0, qh - 1)
            val srcRow = sy * qw
            val dstRow = (topPad + y) * stride
            var x = 0
            while (x < dstW) {
                val sx0 = ((x.toLong() * qw) / dstW).toInt().coerceIn(0, qw - 1)
                val sx1 = (((x + 1).toLong() * qw) / dstW).toInt().coerceIn(0, qw - 1)
                val i0 = srcRow + sx0
                val i1 = srcRow + sx1
                val r0 = rPlane[i0]; val g0 = gPlane[i0]; val b0 = bPlane[i0]
                val r1 = rPlane[i1]; val g1 = gPlane[i1]; val b1 = bPlane[i1]
                val y0 = ((66 * r0 + 129 * g0 + 25 * b0 + 128) shr 8) + 16
                val y1 = ((66 * r1 + 129 * g1 + 25 * b1 + 128) shr 8) + 16
                val rm = (r0 + r1) ushr 1
                val gm = (g0 + g1) ushr 1
                val bm = (b0 + b1) ushr 1
                val cb = ((-38 * rm - 74 * gm + 112 * bm + 128) shr 8) + 128
                val cr = ((112 * rm - 94 * gm - 18 * bm + 128) shr 8) + 128
                val off = dstRow + (x ushr 1) * 4
                out[off] = y0.coerceIn(0, 255).toByte()
                out[off + 1] = y1.coerceIn(0, 255).toByte()
                out[off + 2] = cb.coerceIn(0, 255).toByte()
                out[off + 3] = cr.coerceIn(0, 255).toByte()
                x += 2
            }
        }
        return out
    }

    private data class HwFaceResult(
        val faceCount: Int,
        val pass2RecordCount: Int,
        val centerX: Float,
        val centerY: Float,
        val halfSize: Float,
        val scaleIdx: Int,
        val rollIdx: Int,
        val pass2PassedThreshold: Boolean,
    )

    private fun runHwFace7c000OnYyuv(home: Path, yyuv640x480: ByteArray): HwFaceResult {
        val inputBase = 0x8a9c8000L
        val pass2Base = 0x0593d200L
        val pass2Buf = ByteArray(4096)
        val dma = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                val ok = (!write && address >= inputBase && address + size <= inputBase + yyuv640x480.size) ||
                    (write && address >= pass2Base && address + size <= pass2Base + pass2Buf.size)
                require(ok) { "Unmapped test DMA 0x${address.toString(16)} len=$size write=$write" }
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(yyuv640x480, (address - inputBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                source.get(pass2Buf, (address - pass2Base).toInt(), source.remaining())
            }
        }
        var tick = 1000L
        var irq130 = false
        val bus = checkNotNull(
            CatalogRuntime(
                AddressCatalog.bundled(),
                "discovery",
                home,
                mapOf("discovery" to mapOf(faceKey to faceProfile)),
            ).createBus(object : DiscoveryContext {
                override fun core() = 0
                override fun tick() = tick
                override fun pc() = 0x025a9f14L
                override fun lr() = 0x025a9e54L
                override fun dmaMemory() = dma
                override fun interruptSink() = object : InterruptSink {
                    override fun setLine(irq: Int, high: Boolean) {
                        if (irq == faceIrqLine) irq130 = high
                    }
                }
            }),
        )
        var seq = 1L
        bus.access(seq++, 0xfff7c040L, 4, true, (480L shl 16) or 640L)
        bus.access(seq++, 0xfff7c044L, 4, true, 640L)
        bus.access(seq++, 0xfff7c048L, 4, true, inputBase)
        bus.access(seq++, 0xfff7c320L, 4, true, 0x23456789L)
        bus.access(seq++, 0xfff7c324L, 4, true, 0x0fffff01L)
        bus.access(seq++, 0xfff7c5d0L, 4, true, 139L)
        for (s in 0 until 15) {
            val base = 0xfff7c330L + s * 0x14L
            bus.access(seq++, base + 8, 4, true, 0x400L)
            bus.access(seq++, base + 12, 4, true, 0L)
            bus.access(seq++, base + 16, 4, true, 0L)
        }
        // Pass 1
        bus.access(seq++, 0xfff7c738L, 4, true, 1L)
        bus.access(seq++, 0xfff7c008L, 4, true, 0L)
        bus.access(seq++, 0xfff7c004L, 4, true, 1L)
        tick += faceDelayTicks
        bus.beforeInstruction(tick)
        assertTrue(irq130, "Pass 1 must assert IRQ 130")
        val faceCount = (bus.access(seq++, 0xfff7c600L, 4, false) and 0x1FL).toInt()
        val pass2RecordCount = (bus.access(seq++, 0xfff7c608L, 4, false) and 0x3FFL).toInt()
        var cx = 0f
        var cy = 0f
        var half = 0f
        var scaleIdx = 0
        var rollIdx = 0
        var pass2Ok = false
        if (faceCount > 0) {
            val w0 = bus.access(seq++, 0xfff7c610L, 4, false)
            val tlX = bus.access(seq++, 0xfff7c614L, 4, false)
            val tlY = bus.access(seq++, 0xfff7c618L, 4, false)
            scaleIdx = (w0 and 0xFL).toInt()
            rollIdx = ((w0 ushr 4) and 0xFL).toInt()
            half = halfSizeQ7[scaleIdx] / 128.0f
            cx = tlX / 128.0f + half
            cy = tlY / 128.0f + half
            // W1C + Pass 2 candidate DMA
            bus.access(seq++, 0xfff7c734L, 4, true, 1L)
            bus.access(seq++, 0xfff7c738L, 4, true, 0L)
            bus.access(seq++, 0xfff7c6e0L, 4, true, pass2Base)
            bus.access(seq++, 0xfff7c6e4L, 4, true, pass2RecordCount.toLong())
            bus.access(seq++, 0xfff7c008L, 4, true, 4L)
            bus.access(seq++, 0xfff7c004L, 4, true, 1L)
            tick += faceDelayTicks
            bus.beforeInstruction(tick)
            val bb = ByteBuffer.wrap(pass2Buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
            val cw0 = bb.int.toLong() and 0xFFFF_FFFFL
            val cw1 = bb.int.toLong() and 0xFFFF_FFFFL
            val scoreA = (cw0 and 0x3FFFFL).toInt()
            val scoreB = (((cw0 ushr 18) and 0x3FFFL) or ((cw1 and 3L) shl 14)).toInt()
            pass2Ok = (1250L * scoreA + 2500L * scoreB >= 139L * 10000L)
        }
        return HwFaceResult(faceCount, pass2RecordCount, cx, cy, half, scaleIdx, rollIdx, pass2Ok)
    }

    @Test
    fun hardwareFaceEngineTracksRealMonaLisaAcrossMotionPhasesAndRejectsNonFaceScenes(@TempDir tempRoot: Path) {
        val exposure = Exposure(
            startTick = 0L,
            durationNanos = 1000L,
            iso = 100,
            lens = LensParameters("gf-63", 63.0, 2.8, 1.95, 0.9, 0.0),
        )
        val bayerBuf = ByteBuffer.allocate(1024 * 576 * 2)
        val phases = listOf(0.0, 0.25, 0.50, 0.75)
        val results = LinkedHashMap<Double, HwFaceResult>()

        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val hostRoot = tempRoot.resolve("host")
        val componentsDir = Files.createDirectories(hostRoot.resolve("compoments"))
        Files.copy(repo.resolve("components/cmos/build/libs/cmos-0.3.0.jar"), componentsDir.resolve("cmos-0.3.0.jar"))
        Files.copy(repo.resolve("components/lens/build/libs/lens-0.3.0.jar"), componentsDir.resolve("lens-0.3.0.jar"))
        val cmosDir = Files.createDirectories(componentsDir.resolve("cmos"))
        Files.copy(repo.resolve("tools/release/component-configs/cmos/cmos.yaml"), cmosDir.resolve("cmos.yaml"))
        val lensDir = Files.createDirectories(componentsDir.resolve("lens"))
        Files.copy(repo.resolve("tools/release/component-configs/lens/lens.yaml"), lensDir.resolve("lens.yaml"))

        IntegrationTest().host(hostRoot) { owner, host ->
            owner.submit {
                val cmos = host.components().single { it.descriptor.id == "cmos" }.component
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))

                for (phase in phases) {
                    host.patchLatestDirect(
                        "cmos",
                        mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?motion=off&phase=$phase", "loop" to true),
                        persistToDisk = false,
                    )
                    assertEquals("DIM_3D", cmos.state()["sceneDimension"])
                    bayerBuf.clear()
                    assertEquals(1024 * 576 * 2, sensor.expose(exposure, bayerBuf))
                    val yyuv = bayer16ToYyuv422Letterbox640x480(bayerBuf)
                    val res = runHwFace7c000OnYyuv(tempRoot.resolve("mona-$phase"), yyuv)
                    println("[MONA-HW-DET] phase=$phase -> faces=${res.faceCount} cx=${res.centerX} cy=${res.centerY} half=${res.halfSize} scale=${res.scaleIdx} pass2Ok=${res.pass2PassedThreshold}")
                    assertTrue(res.faceCount >= 1, "Expected >= 1 detected face on real Mona Lisa at phase=$phase")
                    assertTrue(res.pass2PassedThreshold, "Pass 2 candidate score must pass firmware threshold at phase=$phase")
                    assertTrue(res.centerY in 135f..295f, "Mona Lisa face cy=${res.centerY} out of portrait head band at phase=$phase")
                    results[phase] = res
                }

                // BUG-V3-5-7: Portrait +-90 deg orientation (mma8452q portrait-cw / portrait-ccw) on real Mona Lisa!
                var testRollDeg = 0.0
                host.ports.register(
                    "test-orient",
                    com.github.nyabanban.fuji.emulator.api.CameraOrientationPort.MMA8452Q_PORT_NAME,
                    com.github.nyabanban.fuji.emulator.api.CameraOrientationPort::class.java,
                    object : com.github.nyabanban.fuji.emulator.api.CameraOrientationPort {
                        override val orientationPreset: String
                            get() = if (testRollDeg > 45.0) "portrait-cw" else if (testRollDeg < -45.0) "portrait-ccw" else "landscape"
                        override val accelEnabled: Boolean = true
                        override val rollDeg: Double get() = testRollDeg
                        override val pitchDeg: Double = 0.0
                        override val shakeAmpDeg: Double = 0.0
                        override val lcdTiltDeg: Double = 0.0
                        override fun bodyGravityG(tick: Long) = com.github.nyabanban.fuji.emulator.api.CameraGravityVector(0.0, 1.0, 0.0)
                        override fun lcdGravityG(tick: Long) = com.github.nyabanban.fuji.emulator.api.CameraGravityVector(0.0, 1.0, 0.0)
                        override fun handShakeDeltaDeg(tick: Long): Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0)
                    },
                )
                for ((camRoll, expectedRollIdx, tag) in listOf(
                    Triple(90.0, 9, "portrait-cw"),
                    Triple(-90.0, 3, "portrait-ccw"),
                )) {
                    testRollDeg = camRoll
                    host.patchLatestDirect(
                        "cmos",
                        mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?motion=off&phase=0.0", "loop" to true),
                        persistToDisk = false,
                    )
                    bayerBuf.clear()
                    assertEquals(1024 * 576 * 2, sensor.expose(exposure, bayerBuf))
                    val yyuv = bayer16ToYyuv422Letterbox640x480(bayerBuf)
                    val res = runHwFace7c000OnYyuv(tempRoot.resolve("mona-$tag"), yyuv)
                    println("[MONA-HW-PORTRAIT] $tag (camRoll=$camRoll) -> faces=${res.faceCount} cx=${res.centerX} cy=${res.centerY} rollIdx=${res.rollIdx} pass2Ok=${res.pass2PassedThreshold}")
                    assertEquals(1, res.faceCount, "Expected 1 face on real Mona Lisa in $tag")
                    assertEquals(expectedRollIdx, res.rollIdx, "Expected rollIdx=$expectedRollIdx on real Mona Lisa in $tag (got ${res.rollIdx})")
                    assertTrue(res.pass2PassedThreshold, "Pass 2 candidate score must pass threshold in $tag")
                }
                testRollDeg = 0.0

                // Negative controls: ALL non-face built-in 3D scenes must report 0 false-positive faces
                for (uri in listOf(
                    "scene://qr-ball",
                    "scene://depth-studio",
                    "scene://bokeh-city",
                    "scene://resolution-chart",
                )) {
                    host.patchLatestDirect(
                        "cmos",
                        mapOf("sourceKind" to "optical-scene", "sourceFile" to uri, "loop" to true),
                        persistToDisk = false,
                    )
                    bayerBuf.clear()
                    assertEquals(1024 * 576 * 2, sensor.expose(exposure, bayerBuf))
                    val yyuv = bayer16ToYyuv422Letterbox640x480(bayerBuf)
                    val res = runHwFace7c000OnYyuv(tempRoot.resolve("neg-${uri.substringAfter("://")}"), yyuv)
                    println("[MONA-HW-NEG] preset=$uri -> faces=${res.faceCount}")
                    assertEquals(0, res.faceCount, "Non-face scene $uri must not produce false-positive faces")
                }
            }.join()
        }

        val centerRes = results.getValue(0.0)
        val rightRes = results.getValue(0.25)
        val leftRes = results.getValue(0.75)
        assertTrue(
            rightRes.centerX > centerRes.centerX + 80f,
            "Phase 0.25 (right pan) should shift detected face right: center=${centerRes.centerX} right=${rightRes.centerX}",
        )
        assertTrue(
            leftRes.centerX < centerRes.centerX - 80f,
            "Phase 0.75 (left pan) should shift detected face left: center=${centerRes.centerX} left=${leftRes.centerX}",
        )
        assertTrue(
            rightRes.centerX - leftRes.centerX > 180f,
            "Full horizontal sweep should exceed 180px on 640x480 face buffer (got ${rightRes.centerX - leftRes.centerX})",
        )
    }

    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target)
                }
            }
        }
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, b)
        b.getInt(0)
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val b = ByteBuffer.allocate(1)
        mem.read(addr, b)
        b.get(0).toInt() and 0xFF
    }.join()

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long) {
        rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            while (rt.session.checkpointAttempts() < target) {
                if (!rt.session.stepFast()) break
            }
        }.join()
    }

    private fun stepByVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        rt.owner.submit {
            val startTick = (rt.session.checkpointEarlyState()["tick"] as Number).toLong()
            val target = startTick + deltaTicks
            while ((rt.session.checkpointEarlyState()["tick"] as Number).toLong() < target) {
                if (!rt.session.stepFast()) break
            }
        }.join()
    }

    private fun patchBtn(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit {
            rt.host.patchLatestDirect("buttons", mapOf(key to down), persistToDisk = false)
        }.join()
    }

    private fun tapInstr(rt: EmulatorRuntime, key: String, hold: Long = 4_500_000L, settle: Long = 35_000_000L) {
        patchBtn(rt, key, true)
        stepByVirtualTicks(rt, hold)
        patchBtn(rt, key, false)
        stepBy(rt, settle)
    }

    private fun captureMainPng(rt: EmulatorRuntime, dir: Path?, name: String): Pair<Long, IntArray> {
        val (seq, dims, px) = rt.owner.submit {
            val dp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val buf = IntArray(dp.width * dp.height)
            dp.copyArgb(buf)
            Triple(dp.sequence, dp.width to dp.height, buf)
        }.join()
        if (dir != null) {
            Files.createDirectories(dir)
            val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, dims.first, dims.second, px, 0, dims.first)
            ImageIO.write(img, "png", dir.resolve(name).toFile())
        }
        return seq to px
    }

    @Test
    fun liveFirmwareFaceAndEyeAfTracksAnimatedMonaLisaOnGxup0004(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_RUN_MONA_LISA_LIVE") == "1")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        val workRoot = tempRoot.resolve("mona-live-root")
        copyDirectory(stagedRoot, workRoot)

        // Configure CMOS to 3D scene://mona-lisa; advance animation phases deterministically across the
        // portrait motion cycle below so software-rendered Vulkan frames never skip past firmware's
        // multi-frame face-tracker hysteresis due to host wall-clock jitter.
        val cmosYaml = workRoot.resolve("configs/components/cmos/cmos.yaml")
        Formats.atomicYaml(
            cmosYaml,
            mapOf(
                "sourceKind" to "optical-scene",
                "sourceFile" to "scene://mona-lisa?motion=off&phase=0.0",
                "loop" to true,
            ),
        )
        // Mount GF63mm portrait prime focused on Mona Lisa's sculpted 3D relief plane (z ~ 1.95m,
        // matching Method 1's LensParameters("gf-63", 63.0, 2.8, 1.95)) so shallow-DoF f/2.8 bokeh
        // does not defocus her facial features before shutter half-press.
        val lensYaml = workRoot.resolve("configs/components/lens/lens.yaml")
        if (Files.isRegularFile(lensYaml)) {
            Formats.atomicYaml(
                lensYaml,
                mapOf(
                    "template" to "gf63",
                    "focusDistanceM" to 1.95,
                    "opticsEnabled" to true,
                ),
            )
        }
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
            "realTimePacer" to mapOf("enabled" to false),
        )
        val outDir = System.getenv("FUJI_MONA_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE 0x0421")
            tapInstr(rt, "menu") // -> 0x0423 Area
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "down", 5_000_000L, 25_000_000L)
            tapInstr(rt, "menu") // -> 0x0422 Date/Time
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "right", 4_000_000L, 12_000_000L)
            tapInstr(rt, "up", 4_000_000L, 12_000_000L)
            tapInstr(rt, "menu") // -> 0x0482 Start App
            tapInstr(rt, "menu") // -> 0x0100 STILL
            for (i in 0 until 28) {
                if (readU32(rt, 0x01CC6424L) == 0x0100 && readU8(rt, 0x01CBFCA2L) == 0) break
                stepBy(rt, 8_000_000L)
            }
            stepBy(rt, 30_000_000L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL 0x0100")
            captureMainPng(rt, outDir, "01_live_before_face_af.png")

            // Enable FACE ON / EYE AUTO via rear Fn2 (soc-adc.fn2Button — GFX100S factory default Face/Eye toggle)
            // Hold >= CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS (4.05M @ 54 MHz) regardless of SMP vtickDiv=4.
            rt.owner.submit {
                rt.host.patchLatestDirect("soc-adc", mapOf("fn2Button" to true), persistToDisk = false)
            }.join()
            stepByVirtualTicks(rt, 4_500_000L)
            rt.owner.submit {
                rt.host.patchLatestDirect("soc-adc", mapOf("fn2Button" to false), persistToDisk = false)
            }.join()
            // Allow camera Auto-Exposure (AE) to converge down from boot SS 4" / high PGC into steady LiveView metering
            // (GF63mm f/2.8 is 1 EV brighter than GF45-100mm f/4.0, so allow up to 55 settle steps to reach meanY <= 195).
            for (aeStep in 0 until 55) {
                stepBy(rt, 45_000_000L)
                val (aeExp, aeFaces, aeMeanY) = rt.owner.submit {
                    val ispExp = rt.session.componentState("soc-isp")?.get("liveExposure")
                    val disc = rt.session.discoveryState()
                    @Suppress("UNCHECKED_CAST")
                    val regions = disc["regions"] as? List<Map<String, Any?>>
                    val fRec = regions?.firstOrNull { it["id"] == faceRegion }?.get(faceKey) as? Map<*, *>
                    val snap = rt.session.discoveryBusForTest()?.saveSnapshot()
                    @Suppress("UNCHECKED_CAST")
                    val snapRegions = snap?.get("regions") as? Map<String, Map<String, Any?>>
                    @Suppress("UNCHECKED_CAST")
                    val fRegs = (snapRegions?.get(faceRegion)?.get(faceKey) as? Map<*, *>)?.get("registers") as? Map<String, Number>
                    val inAddr = fRegs?.get("0xfff7c048")?.toLong() ?: 0L
                    var mY = -1
                    if (inAddr != 0L) {
                        val mem = rt.host.ports.find("cpu.memory", DmaMemory::class.java)
                        if (mem != null) {
                            val probe = ByteArray(640 * 240 * 2)
                            runCatching {
                                mem.read(inAddr + 60L * 1280L, ByteBuffer.wrap(probe))
                                var sY = 0L; var p = 0; var cnt = 0
                                while (p + 3 < probe.size) {
                                    sY += (probe[p].toInt() and 0xFF) + (probe[p + 1].toInt() and 0xFF)
                                    cnt += 2; p += 4
                                }
                                mY = (sY / maxOf(1, cnt)).toInt()
                            }
                        }
                    }
                    Triple(ispExp, (fRec?.get("lastFaceCount") as? Number)?.toInt() ?: 0, mY)
                }.join()
                println("[MONA-LIVE] ae-settle step=$aeStep meanY=$aeMeanY faces=$aeFaces liveExp=$aeExp")
                if (aeStep >= 3 && aeFaces > 0 && aeMeanY in 40..195) break
            }
            captureMainPng(rt, outDir, "02_after_fn2_face_on.png")

            // Keep GF63mm focused on Mona Lisa's 3D portrait plane (1.95m) after LENSCOM boot parking
            rt.owner.submit {
                rt.host.patchLatestDirect("lens", mapOf("focusDistanceM" to 1.95), persistToDisk = false)
            }.join()
            // Sample 8 deterministic poses with dwell on both right (0.12/0.35) and left (0.75/0.88) sides
            // so 0xFFF7C000 and firmware Face-Priority AE/AF ROI (0xFFF7005C) lock and track both X positions.
            val samplePhases = listOf(0.0, 0.12, 0.12, 0.35, 0.75, 0.75, 0.88, 0.88)
            val observedCentersX = mutableListOf<Float>()
            val observedFaceRoiX = mutableSetOf<Int>()
            val visibleWindowCenters = mutableSetOf<Int>()
            val windowPixels = IntArray(1024 * 768)
            var maxPass1 = 0L
            var maxPass2 = 0L
            for (sample in samplePhases.indices) {
                val phase = samplePhases[sample]
                rt.owner.submit {
                    rt.host.patchLatestDirect(
                        "cmos",
                        mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?motion=off&phase=$phase", "loop" to true),
                        persistToDisk = false,
                    )
                }.join()
                // Advance in 4 slices so firmware sees consecutive detections at each pose and updates 0xFFF7005C ROI.
                var sampleDetectedX: Float? = null
                var sampleDetectedY: Float? = null
                for (sub in 0 until 4) {
                    stepBy(rt, 18_000_000L)
                    val (fCnt, fCx, fCy, reg5cHex) = rt.owner.submit {
                        visibleWindowCenterX(rt, windowPixels)?.let(visibleWindowCenters::add)
                        val disc = rt.session.discoveryState()
                        @Suppress("UNCHECKED_CAST")
                        val regions = disc["regions"] as? List<Map<String, Any?>>
                        val fRec = regions?.firstOrNull { it["id"] == faceRegion }?.get(faceKey) as? Map<*, *>
                        val ispExp = rt.session.componentState("soc-isp")?.get("liveExposure") as? Map<*, *>
                        listOf(
                            (fRec?.get("lastFaceCount") as? Number)?.toInt() ?: 0,
                            (fRec?.get("lastFaceCenterX") as? Number)?.toFloat() ?: -1f,
                            (fRec?.get("lastFaceCenterY") as? Number)?.toFloat() ?: -1f,
                            ispExp?.get("reg5c")?.toString().orEmpty(),
                        )
                    }.join()
                    if ((fCnt as Int) > 0 && (fCx as Float) > 0f && sampleDetectedX == null) {
                        sampleDetectedX = fCx
                        sampleDetectedY = fCy as Float
                    }
                    val r5c = (reg5cHex as String).removePrefix("0x").toLongOrNull(16) ?: 0L
                    if (r5c != 0L && r5c != 0x23f008fL) {
                        observedFaceRoiX += ((r5c ushr 16) and 0xFFFL).toInt()
                    }
                }
                val (seq, _) = captureMainPng(rt, outDir, "03_track_sample_${sample}.png")

                val (faceReceipt, scalerReceipt, inputStats) = rt.owner.submit {
                    val disc = rt.session.discoveryState()
                    @Suppress("UNCHECKED_CAST")
                    val regions = disc["regions"] as? List<Map<String, Any?>>
                    val faceRow = regions?.firstOrNull { it["id"] == faceRegion }
                    val scalerRow = regions?.firstOrNull { it["id"] == scalerRegion }
                    val scalerERow = regions?.firstOrNull { it["id"] == "diagnostic.image-scaler.fff7e000" }
                    val snap = rt.session.discoveryBusForTest()?.saveSnapshot()
                    @Suppress("UNCHECKED_CAST")
                    val snapRegions = snap?.get("regions") as? Map<String, Map<String, Any?>>
                    val fSnap = snapRegions?.get(faceRegion)?.get(faceKey) as? Map<*, *>
                    @Suppress("UNCHECKED_CAST")
                    val fRegs = fSnap?.get("registers") as? Map<String, Number>
                    val inAddr = fRegs?.get("0xfff7c048")?.toLong() ?: 0L
                    var stat = "inAddr=0x${inAddr.toString(16)}"
                    if (inAddr != 0L) {
                        val mem = rt.host.ports.find("cpu.memory", DmaMemory::class.java)
                        if (mem != null) {
                            val probe = ByteArray(640 * 240 * 2)
                            runCatching {
                                mem.read(inAddr + 60L * 1280L, ByteBuffer.wrap(probe))
                                var nz = 0; var sumY = 0L; var sumCb = 0L; var sumCr = 0L; var pairs = 0
                                var p = 0
                                while (p + 3 < probe.size) {
                                    val b0 = probe[p].toInt() and 0xFF
                                    val b1 = probe[p + 1].toInt() and 0xFF
                                    val b2 = probe[p + 2].toInt() and 0xFF
                                    val b3 = probe[p + 3].toInt() and 0xFF
                                    if (b0 != 0 || b1 != 0 || b2 != 0 || b3 != 0) nz++
                                    sumY += (b0 + b1)
                                    sumCb += b2
                                    sumCr += b3
                                    pairs++
                                    p += 4
                                }
                                stat += " nzPairs=$nz/$pairs meanY=${sumY / maxOf(1, pairs * 2)} meanCb=${sumCb / maxOf(1, pairs)} meanCr=${sumCr / maxOf(1, pairs)}"
                            }.onFailure { stat += " err=${it.message}" }
                        }
                    }
                    val ispState = rt.session.componentState("soc-isp")
                    @Suppress("UNCHECKED_CAST")
                    val liveExp = ispState?.get("liveExposure") as? Map<String, Any?>
                    stat += " reg5c=${liveExp?.get("reg5c")} reg60=${liveExp?.get("reg60")} iso=${liveExp?.get("iso")}"
                    if (sample == 0 && outDir != null && inAddr != 0L) {
                        val mem = rt.host.ports.find("cpu.memory", DmaMemory::class.java)
                        if (mem != null) {
                            val full = ByteArray(640 * 480 * 2)
                            runCatching {
                                mem.read(inAddr, ByteBuffer.wrap(full))
                                val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
                                for (yy in 0 until 480) {
                                    val row = yy * 1280
                                    var xx = 0
                                    while (xx < 640) {
                                        val off = row + (xx ushr 1) * 4
                                        val y0 = full[off].toInt() and 0xFF
                                        val y1 = full[off + 1].toInt() and 0xFF
                                        val cb = (full[off + 2].toInt() and 0xFF) - 128
                                        val cr = (full[off + 3].toInt() and 0xFF) - 128
                                        fun yuvToRgb(yv: Int): Int {
                                            val r = (yv + (1.402 * cr)).toInt().coerceIn(0, 255)
                                            val g = (yv - (0.344136 * cb) - (0.714136 * cr)).toInt().coerceIn(0, 255)
                                            val b = (yv + (1.772 * cb)).toInt().coerceIn(0, 255)
                                            return (r shl 16) or (g shl 8) or b
                                        }
                                        img.setRGB(xx, yy, yuvToRgb(y0))
                                        img.setRGB(xx + 1, yy, yuvToRgb(y1))
                                        xx += 2
                                    }
                                }
                                Files.createDirectories(outDir)
                                ImageIO.write(img, "png", outDir.resolve("05_face_yyuv_buffer.png").toFile())
                            }
                        }
                    }
                    Triple(faceRow?.get(faceKey) as? Map<*, *>, scalerRow?.get("imageScalerF000") as? Map<*, *>, stat)
                }.join()
                val pass1 = (faceReceipt?.get("pass1Count") as? Number)?.toLong() ?: 0L
                val pass2 = (faceReceipt?.get("pass2Count") as? Number)?.toLong() ?: 0L
                val lastFaces = (faceReceipt?.get("lastFaceCount") as? Number)?.toInt() ?: 0
                val cx = sampleDetectedX ?: ((faceReceipt?.get("lastFaceCenterX") as? Number)?.toFloat() ?: -1f)
                val cy = sampleDetectedY ?: ((faceReceipt?.get("lastFaceCenterY") as? Number)?.toFloat() ?: -1f)
                maxPass1 = maxOf(maxPass1, pass1)
                maxPass2 = maxOf(maxPass2, pass2)
                if ((lastFaces > 0 || sampleDetectedX != null) && cx > 0f) observedCentersX += cx
                println(
                    "[MONA-LIVE] sample=$sample dispSeq=$seq pass1=$pass1 pass2=$pass2 " +
                        "faces=${if (sampleDetectedX != null) maxOf(1, lastFaces) else lastFaces} cx=${"%.1f".format(cx)} cy=${"%.1f".format(cy)} " +
                        "irqAsserted=${faceReceipt?.get("irqAsserted")} $inputStats scalerF=$scalerReceipt",
                )
            }

            // Half-press shutter to confirm AF locks cleanly on Mona Lisa with Face/Eye AF active
            patchBtn(rt, "shutterHalf", true)
            stepBy(rt, 45_000_000L)
            captureMainPng(rt, outDir, "04_shutter_half_af_lock.png")
            patchBtn(rt, "shutterHalf", false)
            stepBy(rt, 20_000_000L)

            val summary = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            println("[MONA-LIVE] FINAL: maxPass1=$maxPass1 maxPass2=$maxPass2 observedX=$observedCentersX faceRoiX=$observedFaceRoiX summary=$summary")
            assertTrue(maxPass1 > 0L, "Expected real 0xFFF7C000 Pass 1 hardware face detection runs (got $maxPass1)")
            assertTrue(maxPass2 > 0L, "Expected real 0xFFF7C000 Pass 2 candidate DMA runs (got $maxPass2)")
            assertTrue(observedCentersX.size >= 3, "Expected live face detections across animated samples: $observedCentersX")
            val spanX = (observedCentersX.maxOrNull() ?: 0f) - (observedCentersX.minOrNull() ?: 0f)
            assertTrue(spanX >= 60f, "Expected detected face box to track Mona Lisa horizontal motion (spanX=$spanX, xs=$observedCentersX)")
            assertTrue(observedFaceRoiX.size >= 2, "Expected firmware Face-Priority AE/AF ROI (0xFFF7005C) to track moving face: $observedFaceRoiX")
            assertTrue(visibleWindowCenters.any { it < 460 } && visibleWindowCenters.any { it > 565 },
                "Expected an LCDC tracking window on both sides of the LCD, got $visibleWindowCenters")
            assertTrue("mmioUnique=0" in summary, "Expected 0 unmodeled MMIO fallbacks: $summary")
            assertTrue("refused=0" in summary, "Expected 0 flash refusals: $summary")
        }
    }
}
