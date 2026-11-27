package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.AudioCaptureStats
import com.github.nyabanban.fuji.emulator.api.AudioPcmChunk
import com.github.nyabanban.fuji.emulator.api.AudioPlaybackStats
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SceneAudioProvider
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.desktop.audio.AudioDeviceCatalog
import com.github.nyabanban.fuji.emulator.desktop.audio.AudioPlaybackConfig
import com.github.nyabanban.fuji.emulator.desktop.audio.HostAudioCapture
import com.github.nyabanban.fuji.emulator.desktop.audio.HostAudioPlayback
import com.github.nyabanban.fuji.emulator.desktop.audio.UnavailableNativeAudioBackend
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end verification of `scene://milk-frog`:
 * 1. Real-silicon `0xFFF7C000` (`ImageFace7c000` / `PixelFaceDetector`) Face & Eye-AF naturally
 *    detecting and tracking Milk Frog's warm creamy butter-yellow face & green-ring eyes across
 *    build-up, violent belly-laugh burst, and recovery animation phases with zero cheats.
 * 2. Real `CmosFactory` `SceneAudioProvider` port feeding `HostAudioPlayback` (`SCENE_AUDIO`)
 *    into the GFX100S `I2sAudioPort` RX microphone ring cleanly.
 */
class MilkFrogFaceEyeAfAndAudioTest {
    private val faceKey = "imageFace7c000"
    private val faceProfile = "face-detect-irq123-v1"
    private val faceIrqLine = 123
    private val faceDelayTicks = 64L
    private val halfSizeQ7 = intArrayOf(
        2048, 2580, 3244, 4096, 5201, 6553, 8192, 10240,
        13107, 16384, 20480, 25206, 32768, 40960, 54613,
    )

    private fun linearToSrgbByte(lin: Double): Int {
        val c = lin.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (s * 255.0).roundToInt().coerceIn(0, 255)
    }

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
        val centerX: Float,
        val centerY: Float,
        val halfSize: Float,
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
        var irq123 = false
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
                        if (irq == faceIrqLine) irq123 = high
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
        bus.access(seq++, 0xfff7c738L, 4, true, 1L)
        bus.access(seq++, 0xfff7c008L, 4, true, 0L)
        bus.access(seq++, 0xfff7c004L, 4, true, 1L)
        tick += faceDelayTicks
        bus.beforeInstruction(tick)
        assertTrue(irq123, "Pass 1 must assert face-detect IRQ 123")
        val faceCount = (bus.access(seq++, 0xfff7c600L, 4, false) and 0x1FL).toInt()
        val pass2RecordCount = (bus.access(seq++, 0xfff7c608L, 4, false) and 0x3FFL).toInt()
        var cx = 0f; var cy = 0f; var half = 0f; var pass2Ok = false
        if (faceCount > 0) {
            val w0 = bus.access(seq++, 0xfff7c610L, 4, false)
            val tlX = bus.access(seq++, 0xfff7c614L, 4, false)
            val tlY = bus.access(seq++, 0xfff7c618L, 4, false)
            val scaleIdx = (w0 and 0xFL).toInt()
            half = halfSizeQ7[scaleIdx] / 128.0f
            cx = tlX / 128.0f + half
            cy = tlY / 128.0f + half
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
        return HwFaceResult(faceCount, cx, cy, half, pass2Ok)
    }

    @Test
    fun hardwareFaceEngineLocksOntoMilkFrogAndSceneAudioFeedsI2sRx(@TempDir tempRoot: Path) {
        val exposure = Exposure(
            startTick = 0L,
            durationNanos = 1000L,
            iso = 100,
            lens = LensParameters("gf-63", 63.0, 2.8, 1.55, 0.9, 0.0),
        )
        val bayerBuf = ByteBuffer.allocate(1024 * 576 * 2)

        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val hostRoot = tempRoot.resolve("host")
        val componentsDir = Files.createDirectories(hostRoot.resolve("compoments"))
        Files.copy(repo.resolve("components/cmos/build/libs/cmos-0.3.1.jar"), componentsDir.resolve("cmos-0.3.1.jar"))
        Files.copy(repo.resolve("components/lens/build/libs/lens-0.3.1.jar"), componentsDir.resolve("lens-0.3.1.jar"))
        val cmosDir = Files.createDirectories(componentsDir.resolve("cmos"))
        Files.copy(repo.resolve("tools/release/component-configs/cmos/cmos.yaml"), cmosDir.resolve("cmos.yaml"))
        val lensDir = Files.createDirectories(componentsDir.resolve("lens"))
        Files.copy(repo.resolve("tools/release/component-configs/lens/lens.yaml"), lensDir.resolve("lens.yaml"))

        IntegrationTest().host(hostRoot) { owner, host ->
            owner.submit {
                val cmos = host.components().single { it.descriptor.id == "cmos" }.component
                val sensor = checkNotNull(host.ports.find("cmos", SensorPort::class.java))
                val sceneAudioPort = assertNotNull(
                    host.ports.find(SceneAudioProvider.PORT_NAME, SceneAudioProvider::class.java)
                        ?: (sensor as? SceneAudioProvider),
                    "CmosFactory must expose SceneAudioProvider capability on cmos port",
                )

                // 1. Real-silicon 0xFFF7C000 Face Detection locks onto Milk Frog across build-up, laugh burst, and recovery!
                for (phase in listOf(0.10, 0.45, 0.85)) {
                    host.patchLatestDirect(
                        "cmos",
                        mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog?motion=off&audio=on&phase=$phase", "loop" to true),
                        persistToDisk = false,
                    )
                    assertEquals("DIM_3D", cmos.state()["sceneDimension"])
                    assertEquals(true, cmos.state()["hasSceneAudio"])
                    assertTrue(sceneAudioPort.sceneAudioEnabled)

                    bayerBuf.clear()
                    assertEquals(1024 * 576 * 2, sensor.expose(exposure, bayerBuf))
                    val yyuv = bayer16ToYyuv422Letterbox640x480(bayerBuf)
                    val res = runHwFace7c000OnYyuv(tempRoot.resolve("frog-$phase"), yyuv)
                    println("[MILK-FROG-HW-DET] phase=$phase -> faces=${res.faceCount} cx=${res.centerX} cy=${res.centerY} half=${res.halfSize} pass2Ok=${res.pass2PassedThreshold}")
                    assertTrue(res.faceCount >= 1, "Real 0xFFF7C000 Face Detection must lock onto Milk Frog at phase=$phase")
                    assertTrue(res.pass2PassedThreshold, "Pass 2 candidate score must pass firmware threshold at phase=$phase")
                    assertTrue(res.centerX in 200f..440f, "Milk Frog detected face cx=${res.centerX} outside center hero band")
                    assertTrue(res.centerY in 120f..310f, "Milk Frog detected face cy=${res.centerY} outside head band")
                }

                // 2. End-to-end SceneAudioProvider -> HostAudioPlayback(SCENE_AUDIO) -> I2sAudioPort RX ring
                val rxQueue = ArrayDeque<Short>()
                val stubI2s = object : I2sAudioPort {
                    override fun pushTxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long) {}
                    override fun pullRxSamples(channel: Int, pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int, tick: Long): Int = 0
                    override fun drainPlaybackPcm(maxFrames: Int): AudioPcmChunk? = null
                    override fun playbackStats() = AudioPlaybackStats(0L, 0L, 0L, 0, 0L, 0, null)
                    @Synchronized
                    override fun pushRxSamples(pcmStereoInterleaved: ShortArray, frames: Int, sampleRateHz: Int, bitDepth: Int): Int {
                        for (i in 0 until frames * 2) rxQueue.addLast(pcmStereoInterleaved[i])
                        return frames
                    }
                    @Synchronized
                    override fun rxStats() = AudioCaptureStats(rxQueue.size / 2L, rxQueue.size / 2, 0L, 0L)
                }
                host.ports.register("test-i2s", "soc-isp-setup.i2s-audio", I2sAudioPort::class.java, stubI2s)
                val silentCatalog = AudioDeviceCatalog(
                    nativeBackend = UnavailableNativeAudioBackend,
                    javaSoundOutputsProvider = { emptyList() },
                    javaSoundInputsProvider = { emptyList<com.github.nyabanban.fuji.emulator.desktop.audio.AudioDeviceDescriptor>() to emptyList() },
                    parecLoopbackOpener = null,
                )
                HostAudioPlayback(
                    portsProvider = { host.ports },
                    initialConfig = AudioPlaybackConfig(enabled = false),
                    deviceCatalog = silentCatalog,
                ).use { audio ->
                    audio.bindPorts(host.ports)
                    audio.applyPatch(mapOf("inputMode" to "SCENE_AUDIO", "inputGainDb" to 0.0))
                    // Pull several 10 ms chunks across laugh syllable envelope
                    var maxAbsSample = 0
                    repeat(20) {
                        val n = audio.capture.captureOnce()
                        assertEquals(HostAudioCapture.CAPTURE_CHUNK_FRAMES, n)
                    }
                    for (s in rxQueue) {
                        val a = abs(s.toInt())
                        if (a > maxAbsSample) maxAbsSample = a
                    }
                    assertTrue(rxQueue.size >= 20 * HostAudioCapture.CAPTURE_CHUNK_FRAMES * 2)
                    assertTrue(maxAbsSample > 1000, "Expected live Milk Frog laugh PCM on I2S RX ring, maxAbs=$maxAbsSample")
                }
            }.join()
        }
    }
}
