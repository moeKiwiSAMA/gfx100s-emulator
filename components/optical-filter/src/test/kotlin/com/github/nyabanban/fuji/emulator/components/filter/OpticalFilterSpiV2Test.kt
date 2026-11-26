package com.github.nyabanban.fuji.emulator.components.filter

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.ComponentContext
import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.CompositeOpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.EmulationClock
import com.github.nyabanban.fuji.emulator.api.EventChannel
import com.github.nyabanban.fuji.emulator.api.FilterParamType
import com.github.nyabanban.fuji.emulator.api.OpticalFilterContext
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElement
import com.github.nyabanban.fuji.emulator.api.OpticalFilterElementProvider
import com.github.nyabanban.fuji.emulator.api.OpticalFilterScratch
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort
import com.github.nyabanban.fuji.emulator.api.OpticalPupilMask
import com.github.nyabanban.fuji.emulator.api.PluginMetadata
import com.github.nyabanban.fuji.emulator.api.Ports
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpticalFilterSpiV2Test {
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

    private fun compileAndPackagePluginJar(
        jarFile: Path,
        className: String,
        javaSource: String,
        serviceInterface: String,
    ) {
        val srcRoot = Files.createTempDirectory("filter-plugin-src-")
        val outRoot = Files.createTempDirectory("filter-plugin-cls-")
        try {
            val relPath = className.replace('.', File.separatorChar) + ".java"
            val srcFile = srcRoot.resolve(relPath)
            Files.createDirectories(srcFile.parent)
            Files.writeString(srcFile, javaSource)

            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "System JavaCompiler unavailable" }
            val classpath = System.getProperty("java.class.path")
            val rc = compiler.run(
                null,
                null,
                null,
                "-classpath",
                classpath,
                "-d",
                outRoot.toString(),
                srcFile.toString(),
            )
            assertEquals(0, rc, "Dynamic plugin Java compilation failed for $className")

            Files.createDirectories(jarFile.parent)
            JarOutputStream(Files.newOutputStream(jarFile)).use { jos ->
                Files.walk(outRoot).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { cls ->
                        val entryName = outRoot.relativize(cls).toString().replace(File.separatorChar, '/')
                        jos.putNextEntry(JarEntry(entryName))
                        jos.write(Files.readAllBytes(cls))
                        jos.closeEntry()
                    }
                }
                jos.putNextEntry(JarEntry("META-INF/services/$serviceInterface"))
                jos.write("$className\n".toByteArray(Charsets.UTF_8))
                jos.closeEntry()
            }
        } finally {
            srcRoot.toFile().deleteRecursively()
            outRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `all six built-in filter providers expose SPI v2 metadata parameter schemas and Vulkan flags`() {
        val builtIns = listOf(
            NdFilterProvider(),
            UvFilterProvider(),
            StarFilterProvider(),
            BlackMistFilterProvider(),
            GndFilterProvider(),
            BokehMaskFilterProvider(),
        )
        for (provider in builtIns) {
            assertEquals(PluginMetadata.CURRENT_API_VERSION, provider.metadata.apiVersion)
            assertTrue(provider.metadata.description.isNotBlank(), "${provider.filterType} missing EN description")
            assertTrue(provider.metadata.descriptionZh.isNotBlank(), "${provider.filterType} missing ZH description")
            val schema = provider.parameterSchema()
            assertTrue(schema.isNotEmpty(), "${provider.filterType} must declare non-empty parameterSchema()")
            for (spec in schema) {
                assertTrue(spec.key.isNotBlank())
                assertTrue(spec.labelEn.isNotBlank())
                assertTrue(spec.labelZh.isNotBlank())
                val stateMap = spec.toStateMap()
                assertEquals(spec.key, stateMap["key"])
                assertEquals(spec.type.name, stateMap["type"])
            }
        }

        // Verify Vulkan builtin capability flags
        assertTrue(NdFilterElement(com.github.nyabanban.fuji.emulator.api.NdFilterPreset.ND8, 3.0).supportsVulkanBuiltin)
        assertTrue(StarFilterElement(true, 6, 0.8, 0.5, 15.0, 0.12).supportsVulkanBuiltin)
        assertTrue(BlackMistFilterElement(true, "1/4", 0.25, 0.35).supportsVulkanBuiltin)
        assertTrue(GndFilterElement(true, 2.0, 0.5, 0.25, 0.0).supportsVulkanBuiltin)
        assertTrue(BokehMaskFilterElement(BokehMaskShape.STAR_5, 0.0, 0.96).supportsVulkanBuiltin)
        assertFalse(BokehMaskFilterElement(BokehMaskShape.CUSTOM, 0.0, 0.96).supportsVulkanBuiltin)
        assertTrue(UvFilterElement(com.github.nyabanban.fuji.emulator.api.UvFilterPreset.CHEAP_POOR).supportsVulkanBuiltin)
    }

    @Test
    fun `dynamic third-party filter JAR is discovered via live rescanExternalPlugins and receives OpticalFilterContext`() {
        val filtersDir = tempDir.resolve("filters")
        Files.createDirectories(filtersDir)
        val (ctx, ports) = testContext(tempDir)
        val comp = OpticalFilterFactory().create(ctx, mapOf("filtersDir" to filtersDir.toString()))
        try {
            val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))
            @Suppress("UNCHECKED_CAST")
            val beforeTypes = comp.state()["availableFilterTypes"] as List<String>
            assertFalse("depth-fog" in beforeTypes)

            // Drop a freshly compiled third-party SPI v2 filter JAR into filters/ AFTER startup!
            val jarPath = filtersDir.resolve("depth-fog-plugin.jar")
            val javaSrc = """
                package com.example.plugins;
                import com.github.nyabanban.fuji.emulator.api.*;
                import java.util.*;

                public class DepthFogPluginProvider implements OpticalFilterElementProvider {
                    @Override public String getFilterType() { return "depth-fog"; }
                    @Override public String getDisplayName() { return "Depth Fog SPI v2"; }
                    @Override public PluginMetadata getMetadata() {
                        return new PluginMetadata(2, "2.1.0", "Example Optics", "Depth-aware atmospheric fog", "深度感知大气雾效");
                    }
                    @Override public List<FilterParamSpec> parameterSchema() {
                        List<FilterParamSpec> list = new ArrayList<>();
                        list.add(new FilterParamSpec(
                            "density", FilterParamType.NUMBER, "Fog Density", "雾气浓度",
                            "Exponential depth fog density", "指数深度雾浓度", 0.5, 0.0, 2.0, 0.05, "", Collections.emptyList()
                        ));
                        list.add(new FilterParamSpec(
                            "preserveNear", FilterParamType.BOOLEAN, "Protect Foreground", "保护前景",
                            "", "", Boolean.TRUE, null, null, null, "", Collections.emptyList()
                        ));
                        return list;
                    }
                    @Override public OpticalFilterElement create(Map<String, ?> config) {
                        Object rawDensity = config.get("density");
                        double density = (rawDensity instanceof Number) ? ((Number) rawDensity).doubleValue() : 0.5;
                        Object rawEn = config.get("enabled");
                        boolean enabled = (rawEn instanceof Boolean) ? (Boolean) rawEn : true;
                        return new DepthFogElement(enabled, density);
                    }
                    static class DepthFogElement implements OpticalFilterElement {
                        private final boolean enabled;
                        private final double density;
                        DepthFogElement(boolean enabled, double density) { this.enabled = enabled; this.density = density; }
                        @Override public String getId() { return "depth-fog"; }
                        @Override public String getDisplayName() { return "Depth Fog SPI v2"; }
                        @Override public boolean getEnabled() { return enabled; }
                        @Override public double transmittanceMultiplier() { return 1.0; }
                        @Override public boolean getHasSpatialEffect() { return enabled && density > 0.0; }
                        @Override public void applySpatialLinearRgb(float[] r, float[] g, float[] b, int w, int h, OpticalFilterScratch s) {
                            // v1 fallback: slight flat lift
                            for (int i = 0; i < w * h; i++) { r[i] += 0.01f; }
                        }
                        @Override public void applySpatialLinearRgb(float[] r, float[] g, float[] b, int w, int h, OpticalFilterScratch s, OpticalFilterContext ctx) {
                            float[] depth = ctx.getDepthMeters();
                            float fBoost = (float) (density * (ctx.getFNumber() / 4.0) + ctx.getFrameIndex() * 0.001);
                            for (int i = 0; i < w * h; i++) {
                                float d = (depth != null && i < depth.length) ? depth[i] : 1.0f;
                                float fog = Math.min(1.0f, fBoost * d * 0.1f);
                                r[i] += fog;
                                g[i] += fog * 0.5f;
                                b[i] += fog * 0.25f;
                            }
                        }
                        @Override public String summaryBadge() { return enabled ? "DepthFog(" + density + ")" : null; }
                        @Override public Map<String, Object> stateMap() {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("id", "depth-fog");
                            m.put("density", density);
                            return m;
                        }
                    }
                }
            """.trimIndent()
            compileAndPackagePluginJar(
                jarFile = jarPath,
                className = "com.example.plugins.DepthFogPluginProvider",
                javaSource = javaSrc,
                serviceInterface = OpticalFilterElementProvider::class.java.name,
            )

            // Trigger live rescan via setting (same path as UI button & CLI /filter rescan)
            comp.applySettings(mapOf("rescanPlugins" to 1L))
            @Suppress("UNCHECKED_CAST")
            val afterTypes = comp.state()["availableFilterTypes"] as List<String>
            assertTrue("depth-fog" in afterTypes, "Expected 'depth-fog' after live rescan: $afterTypes")
            assertEquals(1, comp.state()["pluginJarCount"])

            @Suppress("UNCHECKED_CAST")
            val providers = comp.state()["availableFilterProviders"] as List<Map<String, Any?>>
            val fogProvider = assertNotNull(providers.firstOrNull { it["type"] == "depth-fog" })
            assertEquals(true, fogProvider["loadedFromJar"])
            @Suppress("UNCHECKED_CAST")
            val meta = fogProvider["metadata"] as Map<String, Any?>
            assertEquals("2.1.0", meta["version"])
            assertEquals("Example Optics", meta["author"])
            @Suppress("UNCHECKED_CAST")
            val schema = fogProvider["parameterSchema"] as List<Map<String, Any?>>
            assertEquals(2, schema.size)
            assertEquals("density", schema[0]["key"])
            assertEquals("NUMBER", schema[0]["type"])

            // Activate the plugin slot and verify OpticalFilterContext reaches it
            val slotsJson = OpticalFilterSlotCodec.encode(
                listOf(OpticalFilterSlotSpec("depth-fog", true, linkedMapOf("density" to 1.0))),
            )
            comp.applySettings(mapOf("slots" to slotsJson))
            assertTrue(port.hasActiveSpatialEffects())
            assertFalse(port.activeElements().single().supportsVulkanBuiltin)

            val r = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f)
            val g = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f)
            val b = floatArrayOf(0.1f, 0.1f, 0.1f, 0.1f)
            val depth = floatArrayOf(1.0f, 1.0f, 8.0f, 8.0f) // near vs far pixels
            val ctxFrame = OpticalFilterContext(
                depthMeters = depth,
                fNumber = 8.0,
                focalLengthMm = 80.0,
                focusDistanceM = 2.0,
                rollDeg = 3.5,
                pitchDeg = -1.2,
                frameIndex = 5L,
            )
            port.applySpatialStack(r, g, b, 2, 2, OpticalFilterScratch(), ctxFrame)
            // Far pixel (depth=8.0m) must receive much stronger fog lift than near pixel (depth=1.0m)
            assertTrue(r[2] > r[0] + 0.5f, "Expected depth-dependent fog delta: near=${r[0]}, far=${r[2]}")
            assertTrue(g[2] > g[0] + 0.25f)
        } finally {
            comp.close()
        }
    }

    @Test
    fun `stacking multiple pupil masks composes into CompositeOpticalPupilMask multiplying weights`() {
        val customHalfMaskProvider = object : OpticalFilterElementProvider {
            override val filterType: String = "half-pupil"
            override val displayName: String = "Half Pupil Cutout"
            override fun create(config: Map<String, Any?>): OpticalFilterElement = object : OpticalFilterElement {
                override val id: String = "half-pupil"
                override val displayName: String = "Half Pupil Cutout"
                override val enabled: Boolean = (config["enabled"] as? Boolean) ?: true
                override fun transmittanceMultiplier(): Double = 1.0
                override val hasSpatialEffect: Boolean = false
                override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) = Unit
                override fun pupilMask(): OpticalPupilMask? = if (!enabled) null else object : OpticalPupilMask {
                    override val shape: BokehMaskShape = BokehMaskShape.CUSTOM
                    override val angleDeg: Double = 0.0
                    override val scale: Double = 1.0
                    override val active: Boolean = true
                    override val cacheKey: Long = 0x12345678L
                    override fun pupilWeight(ux: Double, uy: Double): Double = if (ux >= 0.0) 1.0 else 0.0
                }
                override fun summaryBadge(): String? = if (enabled) "HalfPupil" else null
                override fun stateMap(): Map<String, Any?> = mapOf("id" to id, "enabled" to enabled)
            }
        }

        val (ctx, ports) = testContext(tempDir)
        val comp = OpticalFilterFactory(extraProviders = listOf(customHalfMaskProvider)).create(ctx, emptyMap())
        try {
            val port = assertNotNull(ports.find(OpticalFilterStackPort.PORT_NAME, OpticalFilterStackPort::class.java))
            val slotsJson = OpticalFilterSlotCodec.encode(
                listOf(
                    OpticalFilterSlotSpec("bokeh-mask", true, linkedMapOf("shape" to "RING", "scale" to 0.96)),
                    OpticalFilterSlotSpec("half-pupil", true, emptyMap()),
                ),
            )
            comp.applySettings(mapOf("slots" to slotsJson))

            val mask = assertNotNull(port.activePupilMask())
            val composite = assertIs<CompositeOpticalPupilMask>(mask)
            assertEquals(BokehMaskShape.CUSTOM, composite.shape)
            assertEquals(2, composite.masks.size)
            assertNotEquals(0L, composite.cacheKey)
            // Center (0,0) is blocked by RING inner hole -> 0.0
            assertEquals(0.0, composite.pupilWeight(0.0, 0.0), 1e-4)
            // Right ring region (+0.72, 0.0) passes both RING and half-pupil (ux >= 0) -> > 0.8
            assertTrue(composite.pupilWeight(0.72, 0.0) > 0.8)
            // Left ring region (-0.72, 0.0) is blocked by half-pupil (ux < 0) -> 0.0
            assertEquals(0.0, composite.pupilWeight(-0.72, 0.0), 1e-4)
        } finally {
            comp.close()
        }
    }

    @Test
    fun `all six built-in filter providers expose 6-language i18n names descriptions parameter labels and English fallback`() {
        val requiredLocales = listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES")
        val builtIns = listOf(
            NdFilterProvider(),
            UvFilterProvider(),
            StarFilterProvider(),
            BlackMistFilterProvider(),
            GndFilterProvider(),
            BokehMaskFilterProvider(),
        )
        val cjkRegex = Regex("[\\u4E00-\\u9FFF]")

        for (provider in builtIns) {
            val meta = provider.metadata
            val enDesc = meta.localizedDescription("en")
            val enName = meta.localizedName(provider.displayName, "en")
            assertTrue(enDesc.isNotBlank(), "${provider.filterType} missing EN description")
            assertFalse(cjkRegex.containsMatchIn(enDesc), "${provider.filterType} EN description contains CJK: $enDesc")
            assertTrue(enName.isNotBlank(), "${provider.filterType} missing EN name")

            for (locale in requiredLocales) {
                val locDesc = meta.localizedDescription(locale)
                val locName = meta.localizedName(provider.displayName, locale)
                assertTrue(locDesc.isNotBlank(), "${provider.filterType} blank description for $locale")
                assertTrue(locName.isNotBlank(), "${provider.filterType} blank name for $locale")
                if (locale != "en") {
                    assertNotEquals(enDesc, locDesc, "${provider.filterType} $locale description must be translated (not raw EN)")
                }
            }
            // Unsupported locales fall back strictly to English
            assertEquals(enDesc, meta.localizedDescription("ko-KR"))
            assertEquals(enDesc, meta.localizedDescription("pt-BR"))
            assertEquals(enName, meta.localizedName(provider.displayName, "ko-KR"))

            // Parameter schema labels & choices must also cover all 6 locales + English fallback
            for (spec in provider.parameterSchema()) {
                val enLabel = spec.localizedLabel("en")
                assertTrue(enLabel.isNotBlank(), "${provider.filterType}.${spec.key} blank EN label")
                for (locale in requiredLocales) {
                    assertTrue(spec.localizedLabel(locale).isNotBlank(), "${provider.filterType}.${spec.key} blank label for $locale")
                }
                assertEquals(enLabel, spec.localizedLabel("ko-KR"))
                for (choice in spec.choices) {
                    val enChoice = choice.localizedLabel("en")
                    assertTrue(enChoice.isNotBlank())
                    for (locale in requiredLocales) {
                        assertTrue(choice.localizedLabel(locale).isNotBlank(), "${provider.filterType}.${spec.key}.${choice.value} blank for $locale")
                    }
                    assertEquals(enChoice, choice.localizedLabel("ko-KR"))
                }
            }
        }
    }

    @Test
    fun `external English-only filter JAR falls back cleanly to English under zh_CN ja_JP de_DE and unsupported locales`() {
        val filtersDir = tempDir.resolve("filters-en-only")
        Files.createDirectories(filtersDir)
        val (ctx, _) = testContext(tempDir)
        val comp = OpticalFilterFactory().create(ctx, mapOf("filtersDir" to filtersDir.toString()))
        try {
            val jarPath = filtersDir.resolve("english-only-filter.jar")
            val javaSrc = """
                package com.example.plugins;
                import com.github.nyabanban.fuji.emulator.api.*;
                import java.util.*;

                public class EnglishOnlyFilterProvider implements OpticalFilterElementProvider {
                    @Override public String getFilterType() { return "en-only-warm"; }
                    @Override public String getDisplayName() { return "English Only Warm Filter"; }
                    @Override public PluginMetadata getMetadata() {
                        return new PluginMetadata(2, "1.0.0", "Indie Lab", "Subtle 81A warming glass filter", "");
                    }
                    @Override public List<FilterParamSpec> parameterSchema() {
                        List<FilterParamSpec> list = new ArrayList<>();
                        list.add(new FilterParamSpec(
                            "warmth", FilterParamType.NUMBER, "Warmth", "",
                            "Kelvin shift strength", "", 0.3, 0.0, 1.0, 0.05, "", Collections.emptyList()
                        ));
                        return list;
                    }
                    @Override public OpticalFilterElement create(Map<String, ?> config) {
                        return new OpticalFilterElement() {
                            @Override public String getId() { return "en-only-warm"; }
                            @Override public String getDisplayName() { return "English Only Warm Filter"; }
                            @Override public boolean getEnabled() { return true; }
                            @Override public double transmittanceMultiplier() { return 1.0; }
                            @Override public boolean getHasSpatialEffect() { return false; }
                            @Override public void applySpatialLinearRgb(float[] r, float[] g, float[] b, int w, int h, OpticalFilterScratch s) {}
                            @Override public String summaryBadge() { return "Warm81A"; }
                            @Override public Map<String, Object> stateMap() { return Collections.singletonMap("id", "en-only-warm"); }
                        };
                    }
                }
            """.trimIndent()
            compileAndPackagePluginJar(
                jarFile = jarPath,
                className = "com.example.plugins.EnglishOnlyFilterProvider",
                javaSource = javaSrc,
                serviceInterface = OpticalFilterElementProvider::class.java.name,
            )

            comp.applySettings(mapOf("rescanPlugins" to 1L))
            @Suppress("UNCHECKED_CAST")
            val providers = comp.state()["availableFilterProviders"] as List<Map<String, Any?>>
            val entry = assertNotNull(providers.firstOrNull { it["type"] == "en-only-warm" })
            @Suppress("UNCHECKED_CAST")
            val meta = PluginMetadata.fromStateMap(entry["metadata"] as Map<String, Any?>)
            for (locale in listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES", "ko-KR")) {
                assertEquals("Subtle 81A warming glass filter", meta.localizedDescription(locale), "locale=$locale")
                assertEquals("English Only Warm Filter", meta.localizedName("English Only Warm Filter", locale), "locale=$locale")
            }
        } finally {
            comp.close()
        }
    }
}
