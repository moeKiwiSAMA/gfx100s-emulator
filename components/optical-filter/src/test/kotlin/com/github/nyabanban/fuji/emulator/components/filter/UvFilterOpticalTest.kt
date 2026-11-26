package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.tools.ToolProvider
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UvFilterOpticalTest {
    @TempDir
    lateinit var tempDir: Path

    private fun testContext(dir: Path = tempDir): Pair<ComponentContext, Ports> {
        val map = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let(type::cast)
            override fun removeOwner(owner: String) = Unit
        }
        val ctx = object : ComponentContext {
            override val directory: Path = dir
            override val clock: EmulationClock = EmulationClock { 0L }
            override val events: EventChannel = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports: Ports = ports
            override fun dataPath(relative: String): Path = dir.resolve(relative)
        }
        return ctx to ports
    }

    @Test
    fun `uv presets demonstrate measurable physical differences in transmittance MTF veiling glare ghosting and tint`() {
        val (ctx, ports) = testContext()
        val comp = OpticalFilterFactory().create(ctx, emptyMap())
        val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))

        // 1. OFF has zero overhead and unity transmittance
        assertEquals(1.0, port.totalTransmittance(), 1e-9)
        assertFalse(port.hasActiveSpatialEffects())

        // 2. Transmittance hierarchy: OFF (1.0) > PRO_MRC (0.996) > STANDARD (0.965) > CHEAP_POOR (0.895)
        comp.applySettings(mapOf("uvPreset" to UvFilterPreset.PRO_MRC.id))
        val transPro = port.totalTransmittance()
        assertEquals(0.996, transPro, 1e-6)

        comp.applySettings(mapOf("uvPreset" to UvFilterPreset.STANDARD.id))
        val transStd = port.totalTransmittance()
        assertEquals(0.965, transStd, 1e-6)

        comp.applySettings(mapOf("uvPreset" to UvFilterPreset.CHEAP_POOR.id))
        val transCheap = port.totalTransmittance()
        assertEquals(0.895, transCheap, 1e-6)
        assertTrue(transPro > transStd && transStd > transCheap)

        val w = 64
        val h = 64
        val scratch = OpticalFilterScratch()

        // 3. MTF step-edge sharpness + veiling glare contrast washout (PRO_MRC vs CHEAP_POOR)
        fun runStepEdge(preset: UvFilterPreset): Triple<Float, Float, Float> {
            val r = FloatArray(w * h) { idx -> if (idx % w < w / 2) 0.05f else 1.20f }
            val g = r.copyOf()
            val b = r.copyOf()
            comp.applySettings(mapOf("uvPreset" to preset.id))
            port.applySpatialStack(r, g, b, w, h, scratch)
            val midY = h / 2
            val edgeGrad = abs(g[midY * w + (w / 2)] - g[midY * w + (w / 2 - 1)])
            val shadowLift = g[midY * w + 4]
            val highlight = g[midY * w + (w - 5)]
            return Triple(edgeGrad, shadowLift, highlight)
        }

        val (proGrad, proShadow, proHi) = runStepEdge(UvFilterPreset.PRO_MRC)
        val (cheapGrad, cheapShadow, cheapHi) = runStepEdge(UvFilterPreset.CHEAP_POOR)

        assertTrue(
            cheapGrad < proGrad * 0.85f,
            "Cheap UV must noticeably degrade MTF edge sharpness vs Pro MRC: cheapGrad=$cheapGrad proGrad=$proGrad",
        )
        assertTrue(
            cheapShadow > proShadow + 0.03f,
            "Cheap UV veiling glare must lift shadows vs Pro MRC: cheapShadow=$cheapShadow proShadow=$proShadow",
        )
        val proContrast = (proHi - proShadow) / (proHi + proShadow)
        val cheapContrast = (cheapHi - cheapShadow) / (cheapHi + cheapShadow)
        assertTrue(
            cheapContrast < proContrast - 0.08f,
            "Cheap UV must wash out contrast vs Pro MRC: cheap=$cheapContrast pro=$proContrast",
        )

        // 4. Point-symmetric double-reflection green/magenta ghost across optical center (-x, -y)
        fun runSpecularGhost(preset: UvFilterPreset): Triple<Float, Float, Float> {
            val r = FloatArray(w * h) { 0.02f }
            val g = FloatArray(w * h) { 0.02f }
            val b = FloatArray(w * h) { 0.02f }
            // Bright off-center pinpoint at (12, 14) -> inverted ghost at (51, 49)
            for (dy in -1..1) {
                for (dx in -1..1) {
                    val idx = (14 + dy) * w + (12 + dx)
                    r[idx] = 6.0f
                    g[idx] = 6.0f
                    b[idx] = 6.0f
                }
            }
            comp.applySettings(mapOf("uvPreset" to preset.id))
            port.applySpatialStack(r, g, b, w, h, scratch)
            val gx = w - 1 - 12
            val gy = h - 1 - 14
            val gIdx = gy * w + gx
            val bgIdx = 49 * w + 12 // dark control quadrant far from source and ghost
            return Triple(r[gIdx] - r[bgIdx], g[gIdx] - g[bgIdx], b[gIdx] - b[bgIdx])
        }

        val (proGhostR, proGhostG, proGhostB) = runSpecularGhost(UvFilterPreset.PRO_MRC)
        val (cheapGhostR, cheapGhostG, cheapGhostB) = runSpecularGhost(UvFilterPreset.CHEAP_POOR)

        assertTrue(proGhostG < 0.002f, "Pro MRC must have virtually zero internal ghosting: $proGhostG")
        assertTrue(
            cheapGhostG > 0.025f,
            "Cheap UV must produce a clear point-symmetric internal ghost at (-x,-y): R=$cheapGhostR G=$cheapGhostG B=$cheapGhostB",
        )
        assertTrue(
            cheapGhostG > cheapGhostB && cheapGhostG > cheapGhostR,
            "Uncoated soda-lime double-reflection ghost must peak in green channel: R=$cheapGhostR G=$cheapGhostG B=$cheapGhostB",
        )

        // 5. Soda-lime green-yellow chromatic cast on neutral flat field
        val flatR = FloatArray(w * h) { 0.5f }
        val flatG = FloatArray(w * h) { 0.5f }
        val flatB = FloatArray(w * h) { 0.5f }
        comp.applySettings(mapOf("uvPreset" to UvFilterPreset.CHEAP_POOR.id))
        port.applySpatialStack(flatR, flatG, flatB, w, h, scratch)
        val center = (h / 2) * w + (w / 2)
        assertTrue(
            flatG[center] > flatR[center] && flatR[center] > flatB[center],
            "Cheap UV must impart green-yellow soda-lime tint: R=${flatR[center]} G=${flatG[center]} B=${flatB[center]}",
        )

        // 6. Zero-allocation scratch array reuse check
        val refR = scratch.r
        val refAuxR = scratch.auxR
        port.applySpatialStack(flatR, flatG, flatB, w, h, scratch)
        assertTrue(scratch.r === refR && scratch.auxR === refAuxR, "OpticalFilterScratch must reuse arrays without re-allocating")
        comp.close()
    }

    @Test
    fun `ordered multi-slot rack stacks ND8 + ND64 = 9 EV supports reorder toggle and snapshot round-trip`() {
        val (ctx, ports) = testContext()
        val comp = OpticalFilterFactory().create(ctx, emptyMap()) as StatefulComponent
        val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))

        val slots = listOf(
            OpticalFilterSlotSpec("uv", true, linkedMapOf("preset" to "CHEAP_POOR")),
            OpticalFilterSlotSpec("nd", true, linkedMapOf("preset" to "ND8", "stops" to 3.0)),
            OpticalFilterSlotSpec("nd", true, linkedMapOf("preset" to "ND64", "stops" to 6.0)),
        )
        comp.applySettings(mapOf("slots" to OpticalFilterSlotCodec.encode(slots)))

        val expectedTrans = 0.895 * 2.0.pow(-9.0)
        assertEquals(expectedTrans, port.totalTransmittance(), 1e-6)
        assertEquals(9.0, (comp.state()["effectiveNdStops"] as Number).toDouble(), 1e-6)
        val expectedEvLoss = -ln(expectedTrans) / ln(2.0)
        assertEquals(expectedEvLoss, (comp.state()["totalEvLoss"] as Number).toDouble(), 1e-4)

        // Disable middle ND8 slot without removing it -> transmittance becomes 0.895 * 2^(-6)
        val toggled = listOf(slots[0], slots[1].copy(enabled = false), slots[2])
        comp.applySettings(mapOf("slots" to OpticalFilterSlotCodec.encode(toggled)))
        assertEquals(0.895 * 2.0.pow(-6.0), port.totalTransmittance(), 1e-6)
        assertEquals(6.0, (comp.state()["effectiveNdStops"] as Number).toDouble(), 1e-6)
        val stateSlots = comp.state()["slots"] as List<*>
        assertEquals(3, stateSlots.size, "Disabled slot must remain visible in ordered matte box slots")

        // Snapshot save -> clear -> restore round-trip preserves exact multi-slot rack
        val snap = comp.saveSnapshot()
        comp.applySettings(mapOf("slots" to ""))
        assertEquals(1.0, port.totalTransmittance(), 1e-9)
        assertEquals(0.0, (comp.state()["effectiveNdStops"] as Number).toDouble(), 1e-6)

        comp.restoreSnapshot(snap)
        assertEquals(0.895 * 2.0.pow(-6.0), port.totalTransmittance(), 1e-6)
        assertEquals(3, (comp.state()["slots"] as List<*>).size)
        comp.close()
    }

    @Test
    fun `OpticalFilterModuleLoader auto-discovers external plugin JAR and skips broken JAR with WARN fallback`() {
        val runDir = tempDir.resolve("run-root")
        val compDir = runDir.resolve("configs/components/optical-filter")
        val filtersDir = runDir.resolve("filters")
        Files.createDirectories(compDir)
        Files.createDirectories(filtersDir)

        // 1. Build a real external third-party plugin JAR implementing OpticalFilterElementProvider
        val pluginJar = filtersDir.resolve("custom-warm-filter.jar")
        compileExternalFilterPluginJar(pluginJar)

        // 2. Also plant a corrupt/unreadable JAR to prove resilience + WARN [FALLBACK][optical-filter-plugin]
        val brokenJar = filtersDir.resolve("corrupt-broken.jar")
        Files.writeString(brokenJar, "NOT A VALID ZIP JAR FILE", StandardCharsets.UTF_8)

        val logger = Logger.getLogger(OpticalFilterModuleLoader::class.java.name)
        val warnings = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.message ?: "")
                }
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val (ctx, ports) = testContext(compDir)
            val comp = OpticalFilterFactory().create(ctx, emptyMap())
            try {
                val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))
                val state = comp.state()
                val availableTypes = (state["availableFilterTypes"] as List<*>).map { it.toString() }
                val pluginTypes = (state["pluginFilterTypes"] as List<*>).map { it.toString() }

                assertTrue("custom-warm" in availableTypes, "External JAR filter must auto-register: $availableTypes")
                assertEquals(listOf("custom-warm"), pluginTypes)
                assertEquals(1, (state["pluginJarCount"] as Number).toInt())
                assertTrue(
                    warnings.any { it.contains("[FALLBACK][optical-filter-plugin]") && it.contains("corrupt-broken.jar") },
                    "Broken JAR must emit WARN [FALLBACK][optical-filter-plugin]: $warnings",
                )

                // Mount the external JAR plugin in a slot and verify live transmittance execution
                val slotJson = OpticalFilterSlotCodec.encode(
                    listOf(OpticalFilterSlotSpec("custom-warm", true, linkedMapOf("transmittance" to 0.75))),
                )
                comp.applySettings(mapOf("slots" to slotJson))
                assertEquals(0.75, port.totalTransmittance(), 1e-6)
                assertEquals("Warm JAR", comp.state()["activeSummary"])
            } finally {
                comp.close()
            }
        } finally {
            logger.removeHandler(handler)
        }
    }

    private fun compileExternalFilterPluginJar(jarOut: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("System Java compiler unavailable for external plugin JAR test")
        val work = tempDir.resolve("plugin-build")
        val srcDir = work.resolve("src/testplugin")
        val classesDir = work.resolve("classes")
        Files.createDirectories(srcDir)
        Files.createDirectories(classesDir)

        val javaSrc = srcDir.resolve("CustomWarmFilterProvider.java")
        Files.writeString(
            javaSrc,
            """
            package testplugin;

            import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement;
            import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider;
            import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch;
            import java.util.LinkedHashMap;
            import java.util.Map;

            public final class CustomWarmFilterProvider implements OpticalFilterElementProvider {
                @Override public String getFilterType() { return "custom-warm"; }
                @Override public String getDisplayName() { return "Custom Warm JAR Filter"; }

                @Override
                public OpticalFilterElement create(Map<String, ?> config) {
                    Object rawEn = config.get("enabled");
                    final boolean enabled = (rawEn instanceof Boolean) ? (Boolean) rawEn : false;
                    Object rawT = config.get("transmittance");
                    final double trans = (rawT instanceof Number) ? ((Number) rawT).doubleValue() : 0.80;
                    return new OpticalFilterElement() {
                        @Override public String getId() { return "custom-warm"; }
                        @Override public String getDisplayName() { return "Custom Warm JAR Filter"; }
                        @Override public boolean getEnabled() { return enabled; }
                        @Override public boolean getHasSpatialEffect() { return false; }
                        @Override public double transmittanceMultiplier() { return enabled ? trans : 1.0; }
                        @Override public void applySpatialLinearRgb(float[] r, float[] g, float[] b, int w, int h, OpticalFilterScratch s) {}
                        @Override public String summaryBadge() { return enabled ? "Warm JAR" : null; }
                        @Override public Map<String, Object> stateMap() {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", "custom-warm");
                            m.put("enabled", enabled);
                            m.put("transmittance", trans);
                            return m;
                        }
                    };
                }
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val apiClasspath = OpticalFilterElementProvider::class.java.protectionDomain.codeSource.location.toURI()
        val cp = File(apiClasspath).absolutePath
        val exitCode = compiler.run(
            null,
            null,
            null,
            "-cp",
            cp,
            "-d",
            classesDir.toString(),
            javaSrc.toString(),
        )
        assertEquals(0, exitCode, "Compiling external plugin Java source failed")

        JarOutputStream(Files.newOutputStream(jarOut)).use { jos ->
            Files.walk(classesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { file ->
                    val entryName = classesDir.relativize(file).toString().replace(File.separatorChar, '/')
                    jos.putNextEntry(JarEntry(entryName))
                    jos.write(Files.readAllBytes(file))
                    jos.closeEntry()
                }
            }
            jos.putNextEntry(JarEntry("META-INF/services/${OpticalFilterElementProvider::class.java.name}"))
            jos.write("testplugin.CustomWarmFilterProvider\n".toByteArray(StandardCharsets.UTF_8))
            jos.closeEntry()
        }
    }
}
