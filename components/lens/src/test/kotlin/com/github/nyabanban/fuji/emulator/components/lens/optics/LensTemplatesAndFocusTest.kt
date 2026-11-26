package com.github.nyabanban.fuji.emulator.components.lens.optics

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.components.lens.LensFactory
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.math.abs
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class LensTemplatesAndFocusTest {
    private class Harness {
        lateinit var lensPort: LensPort
        val context = object : ComponentContext {
            override val directory: Path = Path.of("unused-lens-template-test")
            override val clock = EmulationClock { 0L }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    lensPort = port as LensPort
                }

                override fun <T : Any> find(name: String, type: Class<T>): T? = null
                override fun removeOwner(owner: String) {}
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
            }

            override fun dataPath(relative: String): Path = error("unused")
        }
    }

    private fun readSample16(buf: ByteBuffer, width: Int, x: Int, y: Int): Int {
        val bb = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        return bb.getShort((y * width + x) * 2).toInt() and 0xFFFF
    }

    @Test
    fun allTenTemplatesPopulateDefaultsRespectOverridesAndExposeTelemetry() {
        val expectedIds = listOf(
            "gf45-100",
            "gf20-35",
            "gf32-64",
            "gf63",
            "gf110",
            "gf50",
            "adapter-ef-gfx",
            "adapter-m42-dumb",
            "adapter-hasselblad-v",
            "custom",
        )
        assertEquals(expectedIds, LensTemplateCatalog.ids)

        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(h.context, mapOf("template" to "gf45-100", "opticsEnabled" to true)) as StatefulComponent
        assertEquals("GF45-100mmF4 R LM OIS WR", h.lensPort.parameters().model)
        assertEquals(45.0, h.lensPort.parameters().focalLengthMm)
        assertEquals(4.0, h.lensPort.parameters().aperture)
        assertTrue((h.lensPort as OpticalSceneFilter).enabled)
        assertTrue((h.lensPort as ElectronicLensPort).electronicContacts)
        assertTrue((h.lensPort as ElectronicLensPort).oisEnabled)

        val st = lens.state()
        assertEquals("gf45-100", st["template"])
        assertEquals("GF 45-100mm F4 R LM OIS WR", st["templateDisplayName"])
        assertEquals("GF", st["mountType"])
        assertEquals(45.0, st["minFocalLengthMm"])
        assertEquals(100.0, st["maxFocalLengthMm"])
        assertEquals(4.0, st["minAperture"])
        assertEquals(32.0, st["maxAperture"])
        assertEquals(0.65, st["minFocusDistanceM"])
        assertTrue((st["equivalent35mmFocalMm"] as Double) in 34.0..37.0)
        assertTrue((st["horizontalFovDeg"] as Double) in 45.0..60.0)
        assertEquals(true, st["inFocus"])
        assertTrue((st["dofNearM"] as Double) < 2.5)
        assertTrue((st["dofFarM"] as Double) > 2.5)
        assertEquals(0L, st["framesFiltered"])
        @Suppress("UNCHECKED_CAST")
        val templates = st["availableTemplates"] as List<Map<String, Any>>
        assertEquals(10, templates.size)

        // Switching template with explicit overrides preserves overrides in the same applySettings call
        lens.applySettings(mapOf("template" to "gf20-35", "focalLengthMm" to 28.0, "aperture" to 8.0))
        assertEquals("GF20-35mmF4 R WR", h.lensPort.parameters().model)
        assertEquals(28.0, h.lensPort.parameters().focalLengthMm)
        assertEquals(8.0, h.lensPort.parameters().aperture)
        assertEquals(0.35, (h.lensPort as ElectronicLensPort).minFocusDistanceM)

        // Dumb mechanical adapter disables electronicContacts and switches default focusMode to MF
        lens.applySettings(mapOf("template" to "adapter-m42-dumb"))
        assertFalse((h.lensPort as ElectronicLensPort).electronicContacts)
        assertEquals("MF", (h.lensPort as ElectronicLensPort).focusMode)
    }

    @Test
    fun planarDefocusBlurBlurs2dSceneWhenOutOfFocusAndStaysSharpWhenInFocus() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf110",
                "opticsEnabled" to true,
                "aperture" to 2.0,
                "subjectDistanceM" to 2.0,
                "focusDistanceM" to 2.0,
                "vignettingCos4" to 0.0,
                "vignettingMechanical" to 0.0,
            ),
        ) as StatefulComponent
        val filter = h.lensPort as OpticalSceneFilter
        val geom = SensorGeometry(128, 72, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val stripes = FloatArray(count) { i -> if ((i % geom.width) % 4 < 2) 0.9f else 0.05f }

        val sharpBuf = ByteBuffer.allocate(count * 2)
        val expInFocus = Exposure(0L, 1000L, 100, h.lensPort.parameters())
        filter.filterLinearRgbToBayer(geom.width, geom.height, stripes, stripes, stripes, null, expInFocus, geom, sharpBuf, 0L)

        // Defocus lens to 12m while 2D subject sits at 2.0m
        lens.applySettings(mapOf("focusDistanceM" to 12.0))
        val blurBuf = ByteBuffer.allocate(count * 2)
        val expDefocused = Exposure(0L, 1000L, 100, h.lensPort.parameters())
        filter.filterLinearRgbToBayer(geom.width, geom.height, stripes, stripes, stripes, null, expDefocused, geom, blurBuf, 1L)

        fun rowContrast(buf: ByteBuffer): Double {
            var sum = 0.0
            val y = 36
            for (x in 24 until 104 step 2) {
                sum += abs(readSample16(buf, geom.width, x, y) - readSample16(buf, geom.width, x + 2, y))
            }
            return sum
        }

        val sharpC = rowContrast(sharpBuf)
        val blurC = rowContrast(blurBuf)
        assertTrue(
            sharpC > blurC * 2.0,
            "2D planar defocus must significantly reduce high-frequency contrast (sharp=$sharpC, blur=$blurC)",
        )
        assertEquals(false, lens.state()["inFocus"])
        assertEquals(2L, lens.state()["framesFiltered"])
    }

    @Test
    fun adapted35mmImageCircleDarkensGfxCornersWhileMediumFormat6x6CoversCleanly() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "adapter-ef-gfx",
                "opticsEnabled" to true,
                "vignettingCos4" to 0.0,
                "vignettingMechanical" to 0.0,
            ),
        ) as StatefulComponent
        val filter = h.lensPort as OpticalSceneFilter
        val geom = SensorGeometry(128, 72, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val uniform = FloatArray(count) { 0.7f }
        val exp = Exposure(0L, 1000L, 100, h.lensPort.parameters())

        val efBuf = ByteBuffer.allocate(count * 2)
        filter.filterLinearRgbToBayer(geom.width, geom.height, uniform, uniform, uniform, null, exp, geom, efBuf, 0L)
        val efCenter = readSample16(efBuf, geom.width, 64, 36)
        val efCorner = readSample16(efBuf, geom.width, 2, 2)
        assertTrue(efCenter > 20_000, "EF->GFX center must stay bright ($efCenter)")
        assertEquals(0, efCorner, "EF 43.3mm image circle must fall off to black in extreme GFX corner ($efCorner)")

        // Hasselblad V 6x6 (79mm image circle) covers GFX 44x33 corners with zero image-circle cutoff
        lens.applySettings(
            mapOf(
                "template" to "adapter-hasselblad-v",
                "vignettingCos4" to 0.0,
                "vignettingMechanical" to 0.0,
            ),
        )
        val hassyBuf = ByteBuffer.allocate(count * 2)
        val hassyExp = Exposure(0L, 1000L, 100, h.lensPort.parameters())
        filter.filterLinearRgbToBayer(geom.width, geom.height, uniform, uniform, uniform, null, hassyExp, geom, hassyBuf, 1L)
        val hassyCenter = readSample16(hassyBuf, geom.width, 64, 36)
        val hassyCorner = readSample16(hassyBuf, geom.width, 2, 2)
        assertTrue(hassyCorner > hassyCenter * 0.90, "6x6 79mm image circle must cover GFX corners ($hassyCorner vs $hassyCenter)")
    }

    @Test
    fun autoFocusUsesCenterDepthOrSubjectDistanceClampsToMfdAndRespectsMfAndElectronicContacts() {
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf45-100",
                "opticsEnabled" to true,
                "focusMotorInstant" to true,
                "focusDistanceM" to 10.0,
                "subjectDistanceM" to 3.2,
            ),
        ) as StatefulComponent
        val filter = h.lensPort as OpticalSceneFilter
        val ctrl = h.lensPort as LensControlPort

        // 1) 2D scene AF via afTrigger increment focuses to subjectDistanceM (3.2m)
        lens.applySettings(mapOf("afTrigger" to 1L))
        assertEquals(3.2, h.lensPort.parameters().focusDistanceM, 1e-6)
        assertEquals(true, lens.state()["inFocus"])

        // 2) Subject closer than MFD (0.65m for gf45-100) clamps cleanly to MFD
        lens.applySettings(mapOf("subjectDistanceM" to 0.2, "afTrigger" to 2L))
        assertEquals(0.65, h.lensPort.parameters().focusDistanceM, 1e-6)

        // 3) Depth-buffered frame: AF samples center AF-zone median depth (4.5m) instead of background (15.0m)
        val geom = SensorGeometry(64, 48, 16, BayerPattern.RGGB, 3.76)
        val count = geom.width * geom.height
        val plane = FloatArray(count) { 0.5f }
        val depth = FloatArray(count) { i ->
            val x = i % geom.width
            val y = i / geom.width
            if (x in 16..48 && y in 12..36) 4.5f else 15.0f
        }
        filter.filterLinearRgbToBayer(
            geom.width,
            geom.height,
            plane,
            plane,
            plane,
            depth,
            Exposure(0L, 1000L, 100, h.lensPort.parameters()),
            geom,
            ByteBuffer.allocate(count * 2),
            0L,
        )
        assertEquals(4.5, ctrl.triggerAutoFocus(), 1e-6)
        assertEquals(4.5, h.lensPort.parameters().focusDistanceM, 1e-6)

        // 4) MF mode refuses AF drive
        lens.applySettings(mapOf("focusMode" to "MF", "focusDistanceM" to 8.0, "afTrigger" to 3L))
        assertEquals(8.0, h.lensPort.parameters().focusDistanceM, 1e-6)
        assertEquals(8.0, ctrl.triggerAutoFocus(), 1e-6)

        // 5) Dumb mechanical adapter (electronicContacts = false) refuses AF even if focusMode = AF-S
        lens.applySettings(mapOf("template" to "adapter-m42-dumb", "focusMode" to "AF-S", "focusDistanceM" to 6.0, "afTrigger" to 4L))
        assertEquals(6.0, h.lensPort.parameters().focusDistanceM, 1e-6)

        // 6) Snapshot round-trip preserves template and new settings
        val snap = lens.saveSnapshot()
        val h2 = Harness()
        val lens2 = factory.create(h2.context, emptyMap()) as StatefulComponent
        lens2.restoreSnapshot(snap)
        assertEquals("adapter-m42-dumb", lens2.state()["template"])
        assertEquals(6.0, h2.lensPort.parameters().focusDistanceM, 1e-6)
        assertFalse((h2.lensPort as ElectronicLensPort).electronicContacts)
    }

    @Test
    fun modularLensAndAdapterJarsDiscoverCleanlyWithUniqueSerialsAndRealWorldSpecs(@TempDir tempRoot: Path) {
        // Locate all 12 built modular JARs from `<repo>/lenses/*/build/libs/*.jar`
        val repoRoot = Path.of("").toAbsolutePath().normalize().let { cwd ->
            if (Files.isDirectory(cwd.resolve("lenses"))) cwd else cwd.parent.parent
        }
        val lensesRoot = repoRoot.resolve("lenses")
        val stagedLenses = Files.createDirectories(tempRoot.resolve("lenses"))
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
            assertTrue(Files.isRegularFile(builtJar), "Expected built modular JAR at $builtJar")
            Files.copy(builtJar, stagedLenses.resolve(jarName), StandardCopyOption.REPLACE_EXISTING)
        }

        val registry = LensModuleLoader.discover(tempRoot.resolve("compoments/lens"))
        try {
            assertEquals(12, registry.jarCount)
            assertEquals(9, registry.lenses.size, "Expected 9 discoverable LensModule JARs")
            assertEquals(3, registry.adapters.size, "Expected 3 discoverable MountAdapterModule JARs")

            // Every electronic lens must expose a unique 10-byte LENSCOM serial (prevents EEPROM cache collision!)
            val electronicSerials = registry.lenses.mapNotNull { m ->
                (m.controlContract as? LensControlContract.GenericSimulated)?.identitySpec?.serialAscii
            }
            assertEquals(7, electronicSerials.size)
            assertEquals(electronicSerials.size, electronicSerials.toSet().size, "Duplicate LENSCOM serial detected: $electronicSerials")
            assertTrue(electronicSerials.all { it.length == 10 })

            // BUG-A1-3: All 6 native GF modular JARs set LENSCOM hdr[101] bit 0 (LMO/LEQ = 0x01, bits 3/4 clear);
            // adapted electronic lenses keep capabilityFlags1 = 0x00 and manual lenses stay MANUAL.
            val nativeGfModules = registry.lenses.filter { it.mountType == "GF" }
            assertEquals(6, nativeGfModules.size, "Expected 6 native GF modular JARs")
            for (gf in nativeGfModules) {
                val id = (gf.controlContract as LensControlContract.GenericSimulated).identitySpec
                assertEquals(0x01, id.capabilityFlags1, "Native GF lens ${gf.id} must set capabilityFlags1 = 0x01 (LMO/LEQ)")
                assertEquals(0, id.capabilityFlags1 and 0x18, "Native GF lens ${gf.id} must keep hdr[101] bits 3/4 clear")
            }
            val efModule = registry.findLens("canon-ef85-f14l")!!
            assertEquals(
                0x00,
                (efModule.controlContract as LensControlContract.GenericSimulated).identitySpec.capabilityFlags1,
                "Adapted Canon EF lens must keep capabilityFlags1 = 0x00",
            )
            // Built-in fallback LensTemplateCatalog must mirror the exact same native-GF vs adapted/custom split
            for (tpl in LensTemplateCatalog.all) {
                val id = tpl.toDefaultIdentitySpec()
                val expectedCap1 = if (tpl.mountType == "GF") 0x01 else 0x00
                assertEquals(expectedCap1, id.capabilityFlags1, "Template ${tpl.id} capabilityFlags1 mismatch")
            }

            // Verify real-world lens & adapter pairing through LensFactory
            val factory = LensFactory()
            val h = Harness()
            val lens = factory.create(
                h.context,
                mapOf(
                    "lensesDir" to stagedLenses.toString(),
                    "template" to "gf45-100",
                    "opticsEnabled" to true,
                ),
            ) as StatefulComponent
            try {
                val modular = h.lensPort as ModularLensPort
                assertEquals(true, lens.state()["loadedFromJar"])
                assertEquals("GENERIC_SIMULATED", lens.state()["communicationMode"])
                assertEquals("GF04510001", modular.lenscomIdentity?.serialAscii)
                assertEquals(0x01, modular.lenscomIdentity?.capabilityFlags1)
                // Zoom MFD interpolates 0.65m @ 45mm -> 0.82m @ 100mm
                assertEquals(0.65, (h.lensPort as ElectronicLensPort).minFocusDistanceM, 1e-4)
                lens.applySettings(mapOf("focalLengthMm" to 100.0))
                assertEquals(0.82, (h.lensPort as ElectronicLensPort).minFocusDistanceM, 1e-4)
                @Suppress("UNCHECKED_CAST")
                val stops = lens.state()["apertureStops"] as List<Double>
                assertEquals(4.0, stops.first(), 1e-3)
                assertEquals(32.0, stops.last(), 1e-3)
                assertTrue(stops.size >= 19, "Expected 1/3-stop aperture progression from f/4..f/32: $stops")
                assertTrue((lens.state()["hyperfocalM"] as Double) > 5.0)

                // Explicit adapter + adapted lens pairing (Fringer EF-GFX Pro + Canon EF 85/1.4L IS USM)
                lens.applySettings(mapOf("adapterId" to "adapter-fringer-ef-gfx", "template" to "canon-ef85-f14l"))
                assertEquals("GENERIC_SIMULATED", modular.communicationMode.name)
                assertEquals("adapter-fringer-ef-gfx", modular.activeAdapterId)
                assertEquals("canon-ef85-f14l", modular.activeLensId)
                assertEquals(true, (h.lensPort as ElectronicLensPort).electronicContacts)
                assertEquals(true, (h.lensPort as ElectronicLensPort).oisEnabled)
                assertEquals(0.85, (h.lensPort as ElectronicLensPort).minFocusDistanceM, 1e-4)
                assertEquals(1.4, (h.lensPort as ElectronicLensPort).minAperture, 1e-4)
                assertEquals("EF08501401", modular.lenscomIdentity?.serialAscii)
                assertEquals(0x00, modular.lenscomIdentity?.capabilityFlags1)

                // Mechanical adapter + Hasselblad V Zeiss Planar 80/2.8 -> strictly MANUAL, 5 blades, 79mm circle
                lens.applySettings(mapOf("adapterId" to "adapter-hasselblad-v-gfx", "template" to "zeiss-planar-80-f28"))
                assertEquals(LensCommunicationMode.MANUAL, modular.communicationMode)
                assertEquals(LensControlContract.Manual, modular.controlContract)
                assertNull(modular.lenscomIdentity)
                assertFalse((h.lensPort as ElectronicLensPort).electronicContacts)
                assertEquals("MF", (h.lensPort as ElectronicLensPort).focusMode)
                assertEquals(5L, lens.state()["apertureBladeCount"])
                assertEquals(79.0, lens.state()["imageCircleMm"])
            } finally {
                lens.close()
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun focusMotorSlewDifferentiatesFastLmFromSlowDcCorelessOverVirtualClock() {
        var nowTick = 0L
        fun makeLens(templateId: String): Pair<StatefulComponent, LensPort> {
            lateinit var registeredPort: LensPort
            val ctx = object : ComponentContext {
                override val directory: Path = Path.of("unused-motor-test")
                override val clock = EmulationClock { nowTick }
                override val ports = object : Ports {
                    override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                        registeredPort = port as LensPort
                    }
                    override fun <T : Any> find(name: String, type: Class<T>): T? = null
                    override fun removeOwner(owner: String) {}
                }
                override val events = object : EventChannel {
                    override fun publish(event: ComponentEvent) {}
                    override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = AutoCloseable {}
                }
                override fun dataPath(relative: String): Path = error("unused")
            }
            val comp = LensFactory().create(
                ctx,
                mapOf(
                    "template" to templateId,
                    "focusMotorInstant" to false,
                    "focusDistanceM" to 10.0,
                    "subjectDistanceM" to 1.0,
                ),
            ) as StatefulComponent
            return comp to registeredPort
        }

        val (gf50, port50) = makeLens("gf50")
        val (gf45, port45) = makeLens("gf45-100")
        val (gf63, port63) = makeLens("gf63")
        try {
            // Trigger AF toward 1.0m simultaneously at tick 0
            (port50 as LensControlPort).triggerAutoFocus()
            (port45 as LensControlPort).triggerAutoFocus()
            (port63 as LensControlPort).triggerAutoFocus()

            assertEquals(true, gf50.state()["focusMotorActive"])
            assertEquals(true, gf63.state()["focusMotorActive"])

            // Advance 0.12s (12_000_000 ticks @ 100M ticks/s): gf50 (0.22s) should be much closer to 1.0m than gf63 (0.78s)!
            nowTick += 12_000_000L
            val d50 = port50.parameters().focusDistanceM
            val d45 = port45.parameters().focusDistanceM
            val d63 = port63.parameters().focusDistanceM
            assertTrue(d50 < d45 && d45 < d63, "Expected LM_FAST (gf50=$d50) < LM (gf45-100=$d45) < DC_CORELESS (gf63=$d63)")

            // Advance another 0.90s -> all 3 lenses finish settling exactly at 1.0m
            nowTick += 90_000_000L
            assertEquals(1.0, port50.parameters().focusDistanceM, 1e-4)
            assertEquals(1.0, port45.parameters().focusDistanceM, 1e-4)
            assertEquals(1.0, port63.parameters().focusDistanceM, 1e-4)
            assertEquals(false, gf50.state()["focusMotorActive"])
            assertEquals(false, gf63.state()["focusMotorActive"])
        } finally {
            gf50.close(); gf45.close(); gf63.close()
        }
    }

    @Test
    fun strictThreeWayModeMutualExclusionAndFailFastRealFirmwareValidation(@TempDir tempRoot: Path) {
        val fwDir = Files.createDirectories(tempRoot.resolve("firmware-data/lenses"))
        val factory = LensFactory()
        val h = Harness()
        val lens = factory.create(
            h.context,
            mapOf(
                "template" to "gf63",
                "lensFirmwareDir" to fwDir.toString(),
            ),
        ) as StatefulComponent
        try {
            val modular = h.lensPort as ModularLensPort
            assertEquals(LensCommunicationMode.GENERIC_SIMULATED, modular.communicationMode)

            // 1) Cross-mode contradictions rejected atomically
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "MANUAL", "electronicContacts" to true))
            }
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "MANUAL", "focusMode" to "AF-S"))
            }
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "MANUAL", "realFirmwareFile" to "GFUP0099.DAT"))
            }
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "GENERIC_SIMULATED", "realFirmwareFile" to "GFUP0099.DAT"))
            }
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "GENERIC_SIMULATED", "electronicContacts" to false))
            }
            assertEquals(LensCommunicationMode.GENERIC_SIMULATED, modular.communicationMode)

            // 2) REAL_FIRMWARE with missing file MUST fail fast with IllegalArgumentException (HTTP 400, ZERO silent downgrade!)
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "REAL_FIRMWARE", "realFirmwareFile" to "MISSING.DAT"))
            }
            assertEquals(LensCommunicationMode.GENERIC_SIMULATED, modular.communicationMode)

            // 3) REAL_FIRMWARE with corrupt magic MUST fail fast
            Files.write(fwDir.resolve("CORRUPT.DAT"), ByteArray(0x400) { 0x11 })
            assertFailsWith<IllegalArgumentException> {
                lens.applySettings(mapOf("communicationMode" to "REAL_FIRMWARE", "realFirmwareFile" to "CORRUPT.DAT"))
            }
            assertEquals(LensCommunicationMode.GENERIC_SIMULATED, modular.communicationMode)

            // 4) Valid synthetic Fujifilm Type-4 ~byte DAT image passes SHA-256 verification and enters REAL_FIRMWARE
            val plainPayload = ByteArray(512) { (it * 17 + 3).toByte() }
            val expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plainPayload))
            val datBytes = ByteArray(0x274 + plainPayload.size)
            ByteBuffer.wrap(datBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0, 4) // Type-4 magic
                putInt(0x1C, 1) // major
                putInt(0x20, 20) // minor
            }
            "GF63F28".toByteArray(Charsets.US_ASCII).copyInto(datBytes, 0x04)
            for (i in plainPayload.indices) {
                datBytes[0x274 + i] = (plainPayload[i].toInt() xor 0xFF).toByte()
            }
            Files.write(fwDir.resolve("GFUP0099.DAT"), datBytes)

            lens.applySettings(
                mapOf(
                    "communicationMode" to "REAL_FIRMWARE",
                    "realFirmwareFile" to "GFUP0099.DAT",
                    "realFirmwareFormat" to LensControlContract.RealFirmware.FORMAT_FUJI_TYPE4_DAT,
                    "realFirmwareSha256" to expectedSha,
                ),
            )
            assertEquals(LensCommunicationMode.REAL_FIRMWARE, modular.communicationMode)
            assertEquals(expectedSha, lens.state()["realFirmwareVerifiedSha256"])
            assertContentEquals(plainPayload, modular.realFirmwarePayload)

            // 5) Valid Fringer adapter .bin image passes verification
            val fringerBin = ByteArray(1024) { (it xor 0x5A).toByte() }
            val fringerSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fringerBin))
            Files.write(fwDir.resolve("FRINGER_EF_GFX.BIN"), fringerBin)
            lens.applySettings(
                mapOf(
                    "communicationMode" to "REAL_FIRMWARE",
                    "realFirmwareFile" to "FRINGER_EF_GFX.BIN",
                    "realFirmwareFormat" to LensControlContract.RealFirmware.FORMAT_FRINGER_BIN,
                    "realFirmwareSha256" to fringerSha,
                ),
            )
            assertEquals(LensCommunicationMode.REAL_FIRMWARE, modular.communicationMode)
            assertContentEquals(fringerBin, modular.realFirmwarePayload)

            // 6) Explicit switch to MANUAL clears firmware & contacts atomically
            lens.applySettings(
                mapOf(
                    "communicationMode" to "MANUAL",
                    "realFirmwareFile" to "",
                    "electronicContacts" to false,
                    "focusMode" to "MF",
                ),
            )
            assertEquals(LensCommunicationMode.MANUAL, modular.communicationMode)
            assertNull(modular.realFirmwarePayload)
            assertFalse((h.lensPort as ElectronicLensPort).electronicContacts)
        } finally {
            lens.close()
        }
    }

    @Test
    fun coldBootWithFullSchemaValidatedConfigPreservesTemplateSpecsAndManualMode(@TempDir tempRoot: Path) {
        // Stage all 12 built modular JARs from `<repo>/lenses/*/build/libs/*.jar`
        val repoRoot = Path.of("").toAbsolutePath().normalize().let { cwd ->
            if (Files.isDirectory(cwd.resolve("lenses"))) cwd else cwd.parent.parent
        }
        val lensesRoot = repoRoot.resolve("lenses")
        val stagedLenses = Files.createDirectories(tempRoot.resolve("lenses"))
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
            assertTrue(Files.isRegularFile(builtJar), "Expected built modular JAR at $builtJar")
            Files.copy(builtJar, stagedLenses.resolve(jarName), StandardCopyOption.REPLACE_EXISTING)
        }

        val factory = LensFactory()
        val schemaDefaults = factory.descriptor.settings.associate { it.key to it.default }
        fun simulateComponentLoaderValidate(supplied: Map<String, Any>): Map<String, Any> =
            schemaDefaults + supplied

        // 1) Mechanical dumb adapter + Takumar 50/1.4 cold boot MUST NOT crash with MANUAL+electronicContacts=true
        val hManual = Harness()
        val manualValidated = simulateComponentLoaderValidate(
            mapOf(
                "lensesDir" to stagedLenses.toString(),
                "template" to "takumar-50-f14",
                "adapterId" to "adapter-m42-gfx",
                "opticsEnabled" to true,
                "focusMotorInstant" to false,
            ),
        )
        assertEquals(true, manualValidated["electronicContacts"]) // proves validate() injected descriptor default
        val manualLens = factory.create(hManual.context, manualValidated) as StatefulComponent
        try {
            val st = manualLens.state()
            val modular = hManual.lensPort as ModularLensPort
            assertEquals(true, st["loadedFromJar"])
            assertEquals(LensCommunicationMode.MANUAL, modular.communicationMode)
            assertEquals(LensControlContract.Manual, modular.controlContract)
            assertEquals(false, st["electronicContacts"])
            assertEquals("MF", st["focusMode"])
            assertEquals(FocusMotorTypes.MANUAL, st["focusMotorType"])
            assertEquals(0.0, st["focusSweepSeconds"] as Double, 1e-6)
            assertNotEquals("synthetic-research-lens", st["model"])
            assertEquals(50.0, st["focalLengthMm"] as Double, 1e-4)
            assertEquals(1.4, st["aperture"] as Double, 1e-4)
            assertEquals(44.5, st["imageCircleMm"] as Double, 1e-4)
            assertEquals(-0.035, st["distortionK1"] as Double, 1e-4)
            assertEquals(2.8, st["vignettingCos4"] as Double, 1e-4)
            assertEquals(6L, st["apertureBladeCount"])

            // ComponentLoader rollback path (`applySettings(old)` with full schema-validated map) also stays clean
            manualLens.applySettings(manualValidated)
            assertEquals(LensCommunicationMode.MANUAL, modular.communicationMode)
            assertEquals(false, manualLens.state()["electronicContacts"])
            assertEquals(50.0, manualLens.state()["focalLengthMm"] as Double, 1e-4)
        } finally {
            manualLens.close()
        }

        // 2) Native GF45-100 cold boot preserves full optical profile (not wiped to synthetic-research-lens 63mm f/2.8)
        val hGf = Harness()
        val gfLens = factory.create(
            hGf.context,
            simulateComponentLoaderValidate(
                mapOf(
                    "lensesDir" to stagedLenses.toString(),
                    "template" to "gf45-100",
                    "opticsEnabled" to true,
                    "focusMotorInstant" to false,
                ),
            ),
        ) as StatefulComponent
        try {
            val st = gfLens.state()
            assertEquals(true, st["loadedFromJar"])
            assertEquals("GENERIC_SIMULATED", st["communicationMode"])
            assertTrue((st["model"] as String).contains("GF45-100"), "Unexpected model=${st["model"]}")
            assertEquals(45.0, st["focalLengthMm"] as Double, 1e-4)
            assertEquals(4.0, st["aperture"] as Double, 1e-4)
            assertEquals(FocusMotorTypes.LM, st["focusMotorType"])
            assertEquals(0.35, st["focusSweepSeconds"] as Double, 1e-4)
            assertEquals(56.0, st["imageCircleMm"] as Double, 1e-4)
            assertEquals(-0.025, st["distortionK1"] as Double, 1e-4)
            assertEquals(1.8, st["vignettingCos4"] as Double, 1e-4)
        } finally {
            gfLens.close()
        }

        // 3) Electronic Fringer EF-GFX + Canon EF 85/1.4L IS USM cold boot preserves 85mm f/1.4 & 43.3mm FF circle
        val hFringer = Harness()
        val fringerLens = factory.create(
            hFringer.context,
            simulateComponentLoaderValidate(
                mapOf(
                    "lensesDir" to stagedLenses.toString(),
                    "template" to "canon-ef85-f14l",
                    "adapterId" to "adapter-fringer-ef-gfx",
                    "opticsEnabled" to true,
                    "focusMotorInstant" to false,
                ),
            ),
        ) as StatefulComponent
        try {
            val st = fringerLens.state()
            assertEquals(true, st["loadedFromJar"])
            assertEquals("GENERIC_SIMULATED", st["communicationMode"])
            assertEquals(true, st["electronicContacts"])
            assertTrue((st["model"] as String).contains("85"), "Unexpected model=${st["model"]}")
            assertEquals(85.0, st["focalLengthMm"] as Double, 1e-4)
            assertEquals(1.4, st["aperture"] as Double, 1e-4)
            assertEquals(FocusMotorTypes.USM, st["focusMotorType"])
            assertEquals(0.34, st["focusSweepSeconds"] as Double, 1e-4)
            assertEquals(43.3, st["imageCircleMm"] as Double, 1e-4)
            assertEquals(0.018, st["distortionK1"] as Double, 1e-4)
            assertEquals(2.2, st["vignettingCos4"] as Double, 1e-4)
        } finally {
            fringerLens.close()
        }

        // 4) Explicit user overrides in lens.yaml still take precedence over template defaults
        val hOverride = Harness()
        val overrideLens = factory.create(
            hOverride.context,
            simulateComponentLoaderValidate(
                mapOf(
                    "lensesDir" to stagedLenses.toString(),
                    "template" to "gf45-100",
                    "focalLengthMm" to 80.0,
                    "aperture" to 8.0,
                    "opticsEnabled" to true,
                ),
            ),
        ) as StatefulComponent
        try {
            val st = overrideLens.state()
            assertTrue((st["model"] as String).contains("GF45-100"))
            assertEquals(80.0, st["focalLengthMm"] as Double, 1e-4)
            assertEquals(8.0, st["aperture"] as Double, 1e-4)
            assertTrue((st["vignettingCos4"] as Double) > 0.0)
        } finally {
            overrideLens.close()
        }

        // 5) Direct explicit contradiction on partial map MUST still fail fast
        assertFailsWith<IllegalArgumentException> {
            factory.create(
                Harness().context,
                mapOf(
                    "lensesDir" to stagedLenses.toString(),
                    "template" to "takumar-50-f14",
                    "adapterId" to "adapter-m42-gfx",
                    "electronicContacts" to true,
                ),
            )
        }
    }

    @Test
    fun filterRadianceFramePopulatesSceneDepthPortInsteadOfFallingBackToSubjectDistanceSlider() {
        val h = Harness()
        val comp = LensFactory().create(
            h.context,
            mapOf(
                "opticsEnabled" to true,
                "subjectDistanceM" to 6.0,
                "focusDistanceM" to 4.5,
            ),
        )
        try {
            val filter = comp as OpticalSceneFilter
            val depthPort = comp as SceneDepthPort
            // Before any 3D frame arrives, SceneDepthPort falls back to subjectDistanceM slider (6.0 m)
            assertEquals(6.0, depthPort.sampleRegionDepthMeters(0.55, 0.30, 0.75, 0.58), 1e-3)

            // Feed a 3D LinearRadianceFrame via filterRadianceFrame() (the exact SceneSource3D path):
            // center/left backdrop = 4.50 m, right portrait region (x >= 0.56) = 1.92 m
            val w = 128
            val hPx = 72
            val n = w * hPx
            val depth = FloatArray(n) { i ->
                val nx = (i % w).toDouble() / w.toDouble()
                if (nx >= 0.56) 1.92f else 4.50f
            }
            val frame = LinearRadianceFrame(
                width = w,
                height = hPx,
                red = FloatArray(n) { 0.4f },
                green = FloatArray(n) { 0.4f },
                blue = FloatArray(n) { 0.4f },
                depthMeters = depth,
                sequence = 1L,
            )
            val geom = SensorGeometry(w, hPx, 16, BayerPattern.RGGB, 3.76)
            val exp = Exposure(0L, 1_000_000L, 100, LensParameters("gf45-100", 45.0, 4.0, 4.5, 0.9, 0.0))
            filter.filterRadianceFrame(frame, exp, geom, ByteBuffer.allocate(n * 2), 0L)

            // SceneDepthPort MUST now return true 3D per-ROI depth (1.92 m right, 4.50 m center), NOT 6.0 m slider!
            assertEquals(1.92, depthPort.sampleRegionDepthMeters(0.60, 0.30, 0.76, 0.58), 0.02)
            assertEquals(4.50, depthPort.sampleRegionDepthMeters(0.35, 0.30, 0.50, 0.58), 0.02)
        } finally {
            comp.close()
        }
    }
}
