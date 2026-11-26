package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.SensorPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StarBokehSceneAndFilterIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `star-bokeh 3D scene has foreground focus target at 1_35m and bright distant HDR pinpoint lights`() {
        val preset = OpticalScenePreset.parse("scene://star-bokeh")
        assertEquals(OpticalScenePreset.STAR_BOKEH, preset)

        val src = ProceduralOpticsSceneSource(preset = preset, width = 320, height = 180, loop = true)
        src.use {
            val frame = it.latestLinearRgbFrame()
            val depth = assertNotNull(frame.depthMeters)
            // Center of lower-middle frame should hit foreground target at 1.35 m
            val centerDepth = depth[(frame.height * 0.62).toInt() * frame.width + frame.width / 2]
            assertEquals(1.35f, centerDepth, 0.05f, "Foreground target should sit at 1.35 m")

            // Count HDR pinpoint light pixels (> 2.0 linear radiance) at distant depths (>= 5.5 m)
            var hdrLightPx = 0
            for (i in frame.red.indices) {
                val maxCh = maxOf(frame.red[i], frame.green[i], frame.blue[i])
                if (maxCh > 2.0f && depth[i] >= 5.5f) hdrLightPx++
            }
            assertTrue(hdrLightPx in 40..4000, "Expected compact HDR pinpoint lights in background, got $hdrLightPx px")
        }
    }

    @Test
    fun `telephoto framing keeps the central focus target and separated distant lights visible`() {
        for ((width, height) in listOf(96 to 64, 320 to 180, 1024 to 576)) {
            ProceduralOpticsSceneSource(OpticalScenePreset.STAR_BOKEH, width, height, loop = true).use { source ->
                val frame = source.latestLinearRgbFrame()
                val depth = assertNotNull(frame.depthMeters)
                assertEquals(1.35f, depth[height / 2 * width + width / 2], 0.01f)

                // The GF110 on the firmware LCD shows approximately the central 30% of the source.
                val xs = (width * 0.36).toInt() until (width * 0.64).toInt()
                val ys = (height * 0.35).toInt() until (height * 0.65).toInt()
                var foregroundPixels = 0
                val quadrantsWithLights = mutableSetOf<Pair<Boolean, Boolean>>()
                for (y in ys) for (x in xs) {
                    val i = y * width + x
                    if (depth[i] < 1.5f) foregroundPixels++
                    if (depth[i] >= 5.5f && maxOf(frame.red[i], frame.green[i], frame.blue[i]) > 2f) {
                        quadrantsWithLights += (x < width / 2) to (y < height / 2)
                    }
                }
                val fraction = foregroundPixels.toDouble() / (xs.count() * ys.count())
                assertTrue(fraction in 0.15..0.45, "Foreground fraction at ${width}x$height: $fraction")
                assertEquals(4, quadrantsWithLights.size, "Missing distant lights at ${width}x$height")
            }
        }
    }

    @Test
    fun `direct cmos path applies OpticalFilterStackPort transmittance uniformly even with no lens optics`() {
        val map = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
            override fun removeOwner(owner: String) = Unit
        }
        var currentTrans = 1.0
        val stubStack = object : OpticalFilterStackPort {
            override val revision: Long = 1L
            override val enabled: Boolean = true
            override fun activeElements(): List<OpticalFilterElement> = emptyList()
            override fun totalTransmittance(): Double = currentTrans
            override fun hasActiveSpatialEffects(): Boolean = false
            override fun activePupilMask(): OpticalPupilMask? = null
            override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) {}
            override fun stackState(): Map<String, Any?> = emptyMap()
        }
        ports.register("optical-filter", OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java, stubStack)

        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock: EmulationClock = EmulationClock { 0L }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports: Ports = ports
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        val cmos = CmosFactory().create(
            ctx,
            mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true),
        ) as SensorPort

        val lens = LensParameters("Manual50", 50.0, 2.8, 2.5, 0.9, 0.0)
        val exp = Exposure(0L, 1000L, 100, lens)

        fun meanAdu(): Double {
            val buf = ByteBuffer.allocate(1024 * 576 * 2).order(ByteOrder.LITTLE_ENDIAN)
            cmos.expose(exp, buf)
            buf.flip()
            var sum = 0.0
            val count = 1024 * 576
            for (i in 0 until count) sum += (buf.short.toInt() and 0xFFFF)
            return sum / count
        }

        currentTrans = 1.0
        val offAdu = meanAdu()
        currentTrans = 0.25 // ND4 (-2 EV)
        val nd4Adu = meanAdu()
        val ratio = offAdu / nd4Adu
        assertTrue(ratio in 3.7..4.3, "Direct CMOS path must apply ND4 (4x) attenuation, got ratio=$ratio")
    }

    @Test
    fun `cmos skips cpu spatial filter pass when opticalFilter handles spatial stack on gpu`() {
        val map = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
            override fun removeOwner(owner: String) = Unit
        }
        var cpuSpatialCalls = 0
        val spatialStack = object : OpticalFilterStackPort {
            override val revision: Long = 1L
            override val enabled: Boolean = true
            override fun activeElements(): List<OpticalFilterElement> = emptyList()
            override fun totalTransmittance(): Double = 1.0
            override fun hasActiveSpatialEffects(): Boolean = true
            override fun activePupilMask(): OpticalPupilMask? = null
            override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) {
                cpuSpatialCalls++
            }
            override fun stackState(): Map<String, Any?> = emptyMap()
        }
        var gpuOffload = true
        val gpuLens = object : com.github.nyabanban.fuji.emulator.api.LensPort, com.github.nyabanban.fuji.emulator.api.OpticalSceneFilter {
            override fun parameters(): LensParameters = LensParameters("GF110", 110.0, 2.0, 1.35, 0.9, 0.0)
            override val enabled: Boolean = true
            override fun handlesSpatialFilterStack(filterStack: OpticalFilterStackPort?): Boolean = gpuOffload
            override fun filterLinearRgbToBayer(
                srcWidth: Int,
                srcHeight: Int,
                red: FloatArray,
                green: FloatArray,
                blue: FloatArray,
                depthMeters: FloatArray?,
                exposure: Exposure,
                geometry: com.github.nyabanban.fuji.emulator.api.SensorGeometry,
                destination: ByteBuffer,
                frameIndex: Long,
            ): Int {
                val n = geometry.width * geometry.height * 2
                destination.position(destination.position() + n)
                return n
            }
        }
        ports.register("optical-filter", OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java, spatialStack)
        ports.register("lens", "lens", com.github.nyabanban.fuji.emulator.api.LensPort::class.java, gpuLens)

        val ctx = object : ComponentContext {
            override val directory: Path = tempDir
            override val clock: EmulationClock = EmulationClock { 0L }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports: Ports = ports
            override fun dataPath(relative: String): Path = tempDir.resolve(relative)
        }
        val cmos = CmosFactory().create(
            ctx,
            mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://star-bokeh", "loop" to true),
        ) as SensorPort
        val exp = Exposure(0L, 1000L, 100, gpuLens.parameters())
        val buf = ByteBuffer.allocate(1024 * 576 * 2)

        // When handlesSpatialFilterStack == true (Vulkan Pass 0 active), CPU applySpatialStack MUST NOT run!
        gpuOffload = true
        buf.clear()
        cmos.expose(exp, buf)
        assertEquals(0, cpuSpatialCalls, "CMOS must skip CPU applySpatialStack when OpticalSceneFilter offloads Pass 0 to GPU")

        // When handlesSpatialFilterStack == false (CPU fallback or unsupported plugin), CPU applySpatialStack MUST run!
        gpuOffload = false
        buf.clear()
        cmos.expose(exp, buf)
        assertEquals(1, cpuSpatialCalls, "CMOS must run CPU applySpatialStack when OpticalSceneFilter does not offload spatial stack")
    }
}
