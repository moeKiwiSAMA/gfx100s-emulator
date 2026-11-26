package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.lens.LensFactory
import com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan.OpticalFilterStackTranslator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LensPluginSpiV2Test {
    @TempDir
    lateinit var tempDir: Path

    private fun testContext(dir: Path): Pair<ComponentContext, MutableMap<String, Any>> {
        val registered = linkedMapOf<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                registered[name] = port
            }
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name] as? T
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
        return ctx to registered
    }

    private fun compileAndPackageLensJar(jarFile: Path, className: String, javaSource: String) {
        val srcRoot = Files.createTempDirectory("lens-plugin-src-")
        val outRoot = Files.createTempDirectory("lens-plugin-cls-")
        try {
            val relPath = className.replace('.', File.separatorChar) + ".java"
            val srcFile = srcRoot.resolve(relPath)
            Files.createDirectories(srcFile.parent)
            Files.writeString(srcFile, javaSource)

            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "System JavaCompiler unavailable" }
            val rc = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                outRoot.toString(),
                srcFile.toString(),
            )
            assertEquals(0, rc, "Dynamic lens plugin compilation failed for $className")

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
                jos.putNextEntry(JarEntry("META-INF/services/${LensModule::class.java.name}"))
                jos.write("$className\n".toByteArray(Charsets.UTF_8))
                jos.closeEntry()
            }
        } finally {
            srcRoot.toFile().deleteRecursively()
            outRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `dynamic external specialty lens JAR supports rescan opticalSpecAt opticalHook and 128KiB eepromBytes`() {
        val lensesDir = tempDir.resolve("lenses")
        Files.createDirectories(lensesDir)
        val (ctx, ports) = testContext(tempDir)
        val comp = LensFactory().create(ctx, mapOf("lensesDir" to lensesDir.toString(), "opticsEnabled" to true))
        try {
            val modular = assertNotNull(ports["lens"] as? ModularLensPort)
            @Suppress("UNCHECKED_CAST")
            val beforeLenses = comp.state()["discoveredLensModules"] as List<Map<String, Any?>>
            assertFalse(beforeLenses.any { it["id"] == "gf24-70-var-ts" })

            // Compile and drop a real third-party SPI v2 LensModule JAR AFTER startup!
            val jarFile = lensesDir.resolve("gf24-70-var-ts.jar")
            val javaSrc = """
                package com.example.lenses;
                import com.github.nyabanban.fuji.emulator.api.*;
                import java.util.*;

                public class VarZoomTiltShiftLens implements LensModule {
                    private static final byte[] CUSTOM_EEPROM = new byte[131072];
                    static {
                        CUSTOM_EEPROM[0x000BE] = (byte) 0x55;
                        CUSTOM_EEPROM[0x000BF] = (byte) 0xAA;
                        CUSTOM_EEPROM[0x01000] = (byte) 0x7A;
                        CUSTOM_EEPROM[0x01001] = (byte) 0x42;
                    }
                    private final LensOpticalSpec baseSpec = new LensOpticalSpec(
                        "gf24-70-var-ts", "GF 24-70mm F2.8-4.0 TS", "GF", "GF24-70mmF2.8-4TS",
                        24.0, 24.0, 24.0, 70.0,
                        2.8, 2.8, 22.0, 0.35, 0.50, 2.5,
                        0.92, 0.0, -0.08, 0.0, 1.0, 1.0, 0.0, 0.0, 1.8, 0.0, 56.0,
                        true, false, "AF-S", "LM", 0.3,
                        0.0, 1.0, 0.95, 0.88, 9, 0.85, 0.0, 0.0, 0.0, "Dynamic zoom + Tilt-Shift"
                    );
                    private final LensControlContract contract = new LensControlContract.GenericSimulated(
                        new LenscomIdentitySpec(
                            "GF2470T", "GF24-70mmF2.8-4TS", "GL", "TS00000001",
                            1, 0, 1, 0, 1, 30, 0x30, 0x01,
                            false, true, false, true,
                            24.0, 70.0, 2.8, 22.0, 0, 0x7FFF, "LM", 0.3
                        )
                    );
                    private final LensOpticalHook hook = new LensOpticalHook() {
                        @Override public boolean warpUv(double nx, double ny, double f, double d, double ap, double[] outUv) {
                            // Horizontal anamorphic 1.33x squeeze
                            outUv[0] = nx * 1.33;
                            outUv[1] = ny;
                            return true;
                        }
                        @Override public double effectiveFocusDistanceAt(double nx, double ny, double baseFocusDistanceM) {
                            // Scheimpflug horizontal tilt
                            return Math.max(0.25, baseFocusDistanceM * (1.0 + 0.6 * nx));
                        }
                        @Override public long cacheKey() { return 0xCAFEBABEL; }
                    };

                    @Override public String getId() { return "gf24-70-var-ts"; }
                    @Override public String getDisplayName() { return "GF 24-70mm F2.8-4.0 TS"; }
                    @Override public String getMountType() { return "GF"; }
                    @Override public LensOpticalSpec getOpticalSpec() { return baseSpec; }
                    @Override public LensControlContract getControlContract() { return contract; }
                    @Override public PluginMetadata getMetadata() {
                        return new PluginMetadata(2, "2.3.0", "Specialty Optics Lab", "Variable zoom + TS hook", "变焦移轴测试镜头");
                    }
                    @Override public LensOpticalSpec opticalSpecAt(double focalLengthMm, double focusDistanceM, double aperture) {
                        double t = Math.max(0.0, Math.min(1.0, (focalLengthMm - 24.0) / 46.0));
                        double dynMinAp = 2.8 + 1.2 * t; // f/2.8 at 24mm -> f/4.0 at 70mm
                        double dynK1 = -0.08 + 0.13 * t; // -0.08 barrel -> +0.05 pincushion
                        double dynVig = 1.8 - 1.0 * t;   // 1.8 -> 0.8
                        return new LensOpticalSpec(
                            baseSpec.getId(), baseSpec.getDisplayName(), baseSpec.getMountType(), baseSpec.getModelName(),
                            baseSpec.getDefaultFocalLengthMm(), baseSpec.getReferenceFocalLengthMm(),
                            baseSpec.getMinFocalLengthMm(), baseSpec.getMaxFocalLengthMm(),
                            baseSpec.getDefaultAperture(), dynMinAp, baseSpec.getMaxAperture(),
                            baseSpec.getMinFocusDistanceM(), baseSpec.getTeleMinFocusDistanceM(), baseSpec.getDefaultFocusDistanceM(),
                            baseSpec.getTransmission(), baseSpec.getFocusBreathing(),
                            dynK1, 0.0, 1.0, 1.0, 0.0, 0.0, dynVig, 0.0, baseSpec.getImageCircleMm(),
                            true, false, "AF-S", "LM", 0.3,
                            0.0, 1.0, 0.95, 0.88, 9, 0.85, 0.0, 0.0, 0.0, baseSpec.getOpticalCharacterSummary()
                        );
                    }
                    @Override public LensOpticalHook opticalHook() { return hook; }
                    @Override public byte[] eepromBytes() { return CUSTOM_EEPROM; }
                }
            """.trimIndent()
            compileAndPackageLensJar(jarFile, "com.example.lenses.VarZoomTiltShiftLens", javaSrc)

            // Trigger live rescan via setting (same path as UI & CLI)
            comp.applySettings(mapOf("rescanPlugins" to 1L))
            @Suppress("UNCHECKED_CAST")
            val afterLenses = comp.state()["discoveredLensModules"] as List<Map<String, Any?>>
            val discovered = assertNotNull(afterLenses.firstOrNull { it["id"] == "gf24-70-var-ts" })
            assertEquals(true, discovered["loadedFromJar"])
            assertEquals(true, discovered["hasOpticalHook"])
            assertEquals(true, discovered["hasCustomEeprom"])
            @Suppress("UNCHECKED_CAST")
            val meta = discovered["metadata"] as Map<String, Any?>
            assertEquals("2.3.0", meta["version"])
            assertEquals("Specialty Optics Lab", meta["author"])

            // Select the rescanned specialty lens at 24mm f/2.8
            comp.applySettings(mapOf("template" to "gf24-70-var-ts"))
            assertEquals(2.8, (comp.state()["minAperture"] as Number).toDouble(), 1e-4)
            assertEquals(-0.08, (comp.state()["distortionK1"] as Number).toDouble(), 1e-4)
            assertEquals(true, comp.state()["hasOpticalHook"])
            assertEquals(true, comp.state()["hasCustomEeprom"])

            val customEeprom = assertNotNull(modular.customEepromBytes)
            assertEquals(LensModule.LENS_EEPROM_BYTES, customEeprom.size)
            assertEquals(0x7A.toByte(), customEeprom[0x01000])
            assertEquals(0x42.toByte(), customEeprom[0x01001])

            // Zoom to 70mm -> variable aperture dynamically shifts to f/4.0, distortion flips to +0.05!
            comp.applySettings(mapOf("focalLengthMm" to 70.0))
            assertEquals(4.0, (comp.state()["minAperture"] as Number).toDouble(), 1e-4)
            assertEquals(4.0, (comp.state()["aperture"] as Number).toDouble(), 1e-4)
            assertEquals(0.05, (comp.state()["distortionK1"] as Number).toDouble(), 1e-4)
            assertEquals(0.8, (comp.state()["vignettingCos4"] as Number).toDouble(), 1e-4)
        } finally {
            comp.close()
        }
    }

    @Test
    fun `CpuOpticalPipeline LensOpticalHook warpUv tilted focal plane and oval pupilWeight produce exact deltas`() {
        val w = 96
        val h = 64
        val pipeline = CpuOpticalPipeline(maxBokehRadiusPx = 10, parallelThreads = 1)
        val profile = OpticalLensProfile(
            focalLengthMm = 80.0,
            referenceFocalLengthMm = 80.0,
            fNumber = 1.4,
            focusDistanceM = 2.0,
        )

        // 1. High-frequency vertical stripe chart at uniform 2.0m depth for Tilt-Shift Scheimpflug & warpUv tests
        val srcR = FloatArray(w * h)
        val srcG = FloatArray(w * h)
        val srcB = FloatArray(w * h)
        val depth = FloatArray(w * h) { 2.0f }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val stripe = if ((x / 2) % 2 == 0) 1.0f else 0.05f
                val idx = y * w + x
                srcR[idx] = stripe
                srcG[idx] = stripe
                srcB[idx] = stripe
            }
        }
        val chart = LinearRgbImage(w, h, srcR, srcG, srcB)

        val baseline = pipeline.renderLinearRgb(chart, w, h, profile = profile, depthMeters = depth)

        // Tilt-Shift Scheimpflug hook: left side (nx < 0) stays focused at 2.0m, right side shifts focus to 0.7m (heavy defocus!)
        val tiltShiftHook = object : LensOpticalHook {
            override fun effectiveFocusDistanceAt(nx: Double, ny: Double, baseFocusDistanceM: Double): Double =
                if (nx < 0.0) baseFocusDistanceM else 0.70
            override fun cacheKey(): Long = 101L
        }
        val tilted = pipeline.renderLinearRgb(chart, w, h, profile = profile, depthMeters = depth, lensHook = tiltShiftHook)

        fun contrastInRegion(img: LinearRgbImage, xRange: IntRange): Double {
            var sumDiff = 0.0
            var count = 0
            for (y in 12 until h - 12) {
                for (x in xRange) {
                    sumDiff += abs(img.green[y * w + x + 1] - img.green[y * w + x])
                    count++
                }
            }
            return sumDiff / count
        }

        val leftContrast = contrastInRegion(tilted, 10..34)
        val rightContrast = contrastInRegion(tilted, 60..82)
        assertTrue(
            leftContrast > rightContrast * 2.0,
            "Tilt-Shift left side must remain sharp while right side blurs: left=$leftContrast, right=$rightContrast",
        )

        // 2. warpUv Anamorphic horizontal squeeze hook produces measurable geometric warp delta
        val anamorphicWarpHook = object : LensOpticalHook {
            override fun warpUv(nx: Double, ny: Double, focalLengthMm: Double, focusDistanceM: Double, aperture: Double, outUv: DoubleArray): Boolean {
                outUv[0] = nx * 1.5
                outUv[1] = ny
                return true
            }
            override fun cacheKey(): Long = 202L
        }
        val warped = pipeline.renderLinearRgb(chart, w, h, profile = profile, depthMeters = depth, lensHook = anamorphicWarpHook)
        val diffSum = (0 until w * h).sumOf { i -> abs(warped.green[i] - baseline.green[i]).toDouble() }
        assertTrue(diffSum > 100.0, "warpUv anamorphic hook must measurably transform pixels (diffSum=$diffSum)")

        // 3. pupilWeight Anamorphic vertical oval bokeh hook: single bright out-of-focus point light
        // With 110mm f/1.4 focused at 1.0m and subject at 8.0m on 160x120, CoC radius is ~8.5 px.
        val bw = 160
        val bh = 120
        val bokehProfile = OpticalLensProfile(
            focalLengthMm = 110.0,
            referenceFocalLengthMm = 110.0,
            fNumber = 1.4,
            focusDistanceM = 1.0,
        )
        val dotR = FloatArray(bw * bh)
        val dotG = FloatArray(bw * bh)
        val dotB = FloatArray(bw * bh)
        val farDepth = FloatArray(bw * bh) { 8.0f }
        val cx = bw / 2
        val cy = bh / 2
        dotR[cy * bw + cx] = 20.0f
        dotG[cy * bw + cx] = 20.0f
        dotB[cy * bw + cx] = 20.0f
        val dotImg = LinearRgbImage(bw, bh, dotR, dotG, dotB)

        val ovalHook = object : LensOpticalHook {
            override fun pupilWeight(ux: Double, uy: Double, nx: Double, ny: Double, aperture: Double): Double =
                if (hypot(ux * 2.5, uy) <= 1.0) 1.0 else 0.0
            override fun cacheKey(): Long = 303L
        }
        val ovalBokeh = pipeline.renderLinearRgb(dotImg, bw, bh, profile = bokehProfile, depthMeters = farDepth, lensHook = ovalHook)
        // Vertical spread at (cx, cy+5) stays illuminated while horizontal spread at (cx+5, cy) is cut off by 2.5x squeeze!
        val verticalSample = ovalBokeh.green[(cy + 5) * bw + cx]
        val horizontalSample = ovalBokeh.green[cy * bw + (cx + 5)]
        assertTrue(
            verticalSample > horizontalSample * 2.0f + 0.01f,
            "Anamorphic oval pupilWeight must elongate bokeh vertically: vert=$verticalSample, horiz=$horizontalSample",
        )
    }

    @Test
    fun `Vulkan translator rejects custom filters and scene filter safely routes custom pupil masks and hooks to CPU`() {
        val customSpatialElement = object : OpticalFilterElement {
            override val id: String = "custom-sepia"
            override val displayName: String = "Custom Sepia"
            override val enabled: Boolean = true
            override val hasSpatialEffect: Boolean = true
            // Default supportsVulkanBuiltin is false!
            override fun transmittanceMultiplier(): Double = 1.0
            override fun applySpatialLinearRgb(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) = Unit
            override fun summaryBadge(): String = "Sepia"
            override fun stateMap(): Map<String, Any?> = mapOf("id" to id)
        }
        val customMask = object : OpticalPupilMask {
            override val shape: BokehMaskShape = BokehMaskShape.CUSTOM
            override val angleDeg: Double = 0.0
            override val scale: Double = 1.0
            override val active: Boolean = true
            override val cacheKey: Long = 0x7777L
            override fun pupilWeight(ux: Double, uy: Double): Double = if (ux >= 0.0) 1.0 else 0.0
        }
        val customStack = object : OpticalFilterStackPort {
            override val revision: Long = 1L
            override val enabled: Boolean = true
            override fun activeElements(): List<OpticalFilterElement> = listOf(customSpatialElement)
            override fun totalTransmittance(): Double = 1.0
            override fun hasActiveSpatialEffects(): Boolean = true
            override fun activePupilMask(): OpticalPupilMask = customMask
            override fun applySpatialStack(red: FloatArray, green: FloatArray, blue: FloatArray, width: Int, height: Int, scratch: OpticalFilterScratch) = Unit
            override fun stackState(): Map<String, Any?> = emptyMap()
        }

        assertFalse(customSpatialElement.supportsVulkanBuiltin)
        assertFalse(OpticalFilterStackTranslator.canOffloadSpatial(customStack))
        val translated = OpticalFilterStackTranslator.translate(customStack, 64, 64)
        assertFalse(translated.canOffloadSpatialToGpu)

        // LensOpticalSceneFilter must never claim GPU spatial offload when custom mask or LensOpticalHook is active
        val filter = LensOpticalSceneFilter(enabled = true, opticalFilterStackProvider = { customStack })
        assertFalse(filter.handlesSpatialFilterStack(customStack))
        filter.update(
            enabled = true,
            lensOpticalHook = object : LensOpticalHook {
                override fun cacheKey(): Long = 42L
            },
        )
        assertFalse(filter.handlesSpatialFilterStack(customStack))
    }

    @Test
    fun `all nine built-in lenses and three adapters expose 6-language i18n descriptions and strict English fallback`() {
        val requiredLocales = listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES")
        val cjkRegex = Regex("[\\u4E00-\\u9FFF]")

        // 1. Verify built-in LensTemplateCatalog fallback summaries are clean English (zero CJK) + Chinese ZH
        for (tpl in LensTemplateCatalog.all) {
            assertTrue(tpl.opticalCharacterSummary.isNotBlank(), "Template ${tpl.id} missing EN summary")
            assertFalse(
                cjkRegex.containsMatchIn(tpl.opticalCharacterSummary),
                "Template ${tpl.id} English opticalCharacterSummary must not contain CJK: ${tpl.opticalCharacterSummary}",
            )
            assertTrue(tpl.opticalCharacterSummaryZh.isNotBlank(), "Template ${tpl.id} missing ZH summary")
        }

        // 2. Stage all 9 built-in lens JARs + 3 adapter JARs and verify 6-language metadata on every single module
        val cwd = Path.of("").toAbsolutePath()
        val repoRoot = if (Files.isDirectory(cwd.resolve("lenses"))) cwd else cwd.parent.parent
        val lensesRoot = repoRoot.resolve("lenses")
        val stagedDir = Files.createDirectories(tempDir.resolve("staged-i18n-lenses"))
        val expectedJarNames = listOf(
            "lens-gf45-100.jar",
            "lens-gf20-35.jar",
            "lens-gf32-64.jar",
            "lens-gf63.jar",
            "lens-gf110.jar",
            "lens-gf50.jar",
            "lens-canon-ef85-f14l.jar",
            "lens-takumar-50-f14.jar",
            "lens-zeiss-planar-80-f28.jar",
            "adapter-fringer-ef-gfx.jar",
            "adapter-m42-gfx.jar",
            "adapter-hasselblad-v-gfx.jar",
        )
        for (jarName in expectedJarNames) {
            val moduleName = jarName.removeSuffix(".jar")
            val builtJar = lensesRoot.resolve(moduleName).resolve("build/libs").resolve(jarName)
            assertTrue(Files.isRegularFile(builtJar), "Expected built JAR at $builtJar")
            Files.copy(builtJar, stagedDir.resolve(jarName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        val discovered = LensModuleLoader.discover(tempDir, stagedDir.toString())
        try {
            assertEquals(9, discovered.lenses.size, "Expected all 9 built-in LensModule JARs")
            assertEquals(3, discovered.adapters.size, "Expected all 3 built-in MountAdapterModule JARs")

            for (lens in discovered.lenses) {
                val enSpec = lens.opticalSpec.opticalCharacterSummary
                assertTrue(enSpec.isNotBlank(), "Lens ${lens.id} missing opticalCharacterSummary")
                assertFalse(cjkRegex.containsMatchIn(enSpec), "Lens ${lens.id} opticalCharacterSummary must be English: $enSpec")

                val meta = lens.metadata
                val enDesc = meta.localizedDescription("en")
                assertTrue(enDesc.isNotBlank(), "Lens ${lens.id} missing EN metadata description")
                assertFalse(cjkRegex.containsMatchIn(enDesc), "Lens ${lens.id} EN metadata contains CJK: $enDesc")
                for (locale in requiredLocales) {
                    val locDesc = meta.localizedDescription(locale)
                    assertTrue(locDesc.isNotBlank(), "Lens ${lens.id} blank description for $locale")
                    if (locale != "en") {
                        assertTrue(locDesc != enDesc, "Lens ${lens.id} $locale description must be translated: $locDesc")
                    }
                }
                assertEquals(enDesc, meta.localizedDescription("ko-KR"), "Lens ${lens.id} unsupported locale fallback")
                assertEquals(meta.localizedDescription("fr_FR"), meta.localizedDescription("fr-FR"))
                assertEquals(meta.localizedDescription("ja_JP"), meta.localizedDescription("ja"))
            }

            for (adapter in discovered.adapters) {
                val meta = adapter.metadata
                val enDesc = meta.localizedDescription("en")
                val enName = meta.localizedName(adapter.displayName, "en")
                assertTrue(enDesc.isNotBlank(), "Adapter ${adapter.id} missing EN description")
                assertFalse(cjkRegex.containsMatchIn(enDesc), "Adapter ${adapter.id} EN description contains CJK: $enDesc")
                for (locale in requiredLocales) {
                    val locDesc = meta.localizedDescription(locale)
                    val locName = meta.localizedName(adapter.displayName, locale)
                    assertTrue(locDesc.isNotBlank(), "Adapter ${adapter.id} blank description for $locale")
                    assertTrue(locName.isNotBlank(), "Adapter ${adapter.id} blank name for $locale")
                    if (locale != "en") {
                        assertTrue(locDesc != enDesc, "Adapter ${adapter.id} $locale description must be translated")
                    }
                }
                assertEquals(enDesc, meta.localizedDescription("ko-KR"), "Adapter ${adapter.id} unsupported locale fallback")
                assertEquals(enName, meta.localizedName(adapter.displayName, "ko-KR"))
            }
        } finally {
            discovered.close()
        }

        // 3. Verify LensFactory state exposes activeLensMetadata & activeAdapterMetadata with full 6-lang maps
        val (ctx, _) = testContext(tempDir)
        val comp = LensFactory().create(
            ctx,
            mapOf(
                "lensesDir" to stagedDir.toString(),
                "template" to "canon-ef85-f14l",
                "adapterId" to "adapter-fringer-ef-gfx",
            ),
        )
        try {
            @Suppress("UNCHECKED_CAST")
            val activeLensMeta = PluginMetadata.fromStateMap(comp.state()["activeLensMetadata"] as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val activeAdapterMeta = PluginMetadata.fromStateMap(comp.state()["activeAdapterMetadata"] as Map<String, Any?>)
            for (locale in requiredLocales) {
                assertTrue(activeLensMeta.localizedDescription(locale).isNotBlank())
                assertTrue(activeAdapterMeta.localizedDescription(locale).isNotBlank())
            }
            assertEquals(activeLensMeta.localizedDescription("en"), activeLensMeta.localizedDescription("ko-KR"))
            assertEquals(activeAdapterMeta.localizedDescription("en"), activeAdapterMeta.localizedDescription("ko-KR"))
        } finally {
            comp.close()
        }
    }

    @Test
    fun `external English-only lens JAR falls back cleanly to English under zh_CN ja_JP de_DE and ko-KR`() {
        val lensesDir = tempDir.resolve("lenses-en-only")
        Files.createDirectories(lensesDir)
        val (ctx, _) = testContext(tempDir)
        val comp = LensFactory().create(ctx, mapOf("lensesDir" to lensesDir.toString(), "opticsEnabled" to true))
        try {
            val jarFile = lensesDir.resolve("en-only-cinema-lens.jar")
            val javaSrc = """
                package com.example.lenses;
                import com.github.nyabanban.fuji.emulator.api.*;

                public class EnglishOnlyCinemaLens implements LensModule {
                    private final LensOpticalSpec spec = new LensOpticalSpec(
                        "en-only-cine-50", "Cine 50mm T1.5", "GF", "CINE50T15",
                        50.0, 50.0, 50.0, 50.0,
                        1.5, 1.5, 16.0, 0.45, 0.45, 2.5,
                        0.91, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.4, 0.0, 56.0,
                        false, false, "MF", "MANUAL", 0.0,
                        0.0, 1.0, 0.96, 0.90, 11, 0.95, 0.0, 0.0, 0.0,
                        "English-only cinema prime with circular 11-blade iris"
                    );
                    @Override public String getId() { return "en-only-cine-50"; }
                    @Override public String getDisplayName() { return "Cine 50mm T1.5"; }
                    @Override public String getMountType() { return "GF"; }
                    @Override public LensOpticalSpec getOpticalSpec() { return spec; }
                    @Override public LensControlContract getControlContract() { return LensControlContract.Manual.INSTANCE; }
                    @Override public PluginMetadata getMetadata() {
                        return new PluginMetadata(2, "1.0.0", "CineShop", "English-only cinema prime with circular 11-blade iris", "");
                    }
                }
            """.trimIndent()
            compileAndPackageLensJar(jarFile, "com.example.lenses.EnglishOnlyCinemaLens", javaSrc)

            comp.applySettings(mapOf("rescanPlugins" to 1L))
            comp.applySettings(mapOf("template" to "en-only-cine-50"))
            @Suppress("UNCHECKED_CAST")
            val activeMeta = PluginMetadata.fromStateMap(comp.state()["activeLensMetadata"] as Map<String, Any?>)
            for (locale in listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES", "ko-KR")) {
                assertEquals(
                    "English-only cinema prime with circular 11-blade iris",
                    activeMeta.localizedDescription(locale),
                    "Expected strict English fallback for locale=$locale",
                )
            }
        } finally {
            comp.close()
        }
    }
}
