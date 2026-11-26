package com.github.nyabanban.fuji.emulator.components.lens.filmsim

import com.github.nyabanban.fuji.emulator.components.lens.optics.LinearRgbImage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FilmSim3dLutTest {

    @Test
    fun identityLutPreservesAllLatticeNodesAndIntermediateColors() {
        val id = FilmSim3dLut.identity()
        val node = IntArray(3)
        id.nodeRgb12(0, 0, 0, node)
        assertEquals(listOf(0, 0, 0), node.toList())
        id.nodeRgb12(15, 0, 0, node)
        assertEquals(listOf(4095, 0, 0), node.toList())
        id.nodeRgb12(0, 15, 0, node)
        assertEquals(listOf(0, 4095, 0), node.toList())
        id.nodeRgb12(0, 0, 15, node)
        assertEquals(listOf(0, 0, 4095), node.toList())
        id.nodeRgb12(15, 15, 15, node)
        assertEquals(listOf(4095, 4095, 4095), node.toList())

        val samples = listOf(
            Triple(0, 0, 0),
            Triple(255, 255, 255),
            Triple(128, 128, 128),
            Triple(210, 84, 39),
            Triple(17, 199, 244),
        )
        for (mode in LutInterpolationMode.entries) {
            for ((r, g, b) in samples) {
                val packed = id.applyRgb8(r, g, b, mode)
                assertEquals(0xFF, (packed ushr 24) and 0xFF)
                assertTrue(abs(((packed ushr 16) and 0xFF) - r) <= 1, "R drift for ($r,$g,$b) mode=$mode")
                assertTrue(abs(((packed ushr 8) and 0xFF) - g) <= 1, "G drift for ($r,$g,$b) mode=$mode")
                assertTrue(abs((packed and 0xFF) - b) <= 1, "B drift for ($r,$g,$b) mode=$mode")
            }
        }
    }

    @Test
    fun syntheticChannelSwapAndMidpointTrilinearVsTetrahedralAccuracy() {
        // Synthetic 3D LUT mapping (r, g, b) -> (b, r, g)
        val rgb12 = ShortArray(FilmSim3dLut.NODE_COUNT * 3)
        for (b in 0 until FilmSim3dLut.GRID_SIZE) {
            val bv = ((b * 4095 + 7) / 15).toShort()
            for (g in 0 until FilmSim3dLut.GRID_SIZE) {
                val gv = ((g * 4095 + 7) / 15).toShort()
                for (r in 0 until FilmSim3dLut.GRID_SIZE) {
                    val rv = ((r * 4095 + 7) / 15).toShort()
                    val dst = FilmSim3dLut.nodeIndex(r, g, b) * 3
                    rgb12[dst] = bv
                    rgb12[dst + 1] = rv
                    rgb12[dst + 2] = gv
                }
            }
        }
        val swap = FilmSim3dLut.fromRgb12("SwapBRG", rgb12)

        val outTri = FloatArray(3)
        val outTet = FloatArray(3)
        swap.sampleNormalized(0.25f, 0.50f, 0.75f, outTri, LutInterpolationMode.TRILINEAR)
        swap.sampleNormalized(0.25f, 0.50f, 0.75f, outTet, LutInterpolationMode.TETRAHEDRAL)

        for (out in listOf(outTri, outTet)) {
            assertTrue(abs(out[0] - 0.75f) < 0.002f, "Expected swapped R=0.75, got ${out[0]}")
            assertTrue(abs(out[1] - 0.25f) < 0.002f, "Expected swapped G=0.25, got ${out[1]}")
            assertTrue(abs(out[2] - 0.50f) < 0.002f, "Expected swapped B=0.50, got ${out[2]}")
        }

        // Hardware SRAM roundtrip (exact 32,768-byte layout)
        val sram = swap.toHardwareSramBytes()
        assertEquals(32_768, sram.size)
        val roundtrip = FilmSim3dLut.fromHardwareSramBytes("Roundtrip", sram)
        assertEquals(swap.applyRgb8(64, 128, 192), roundtrip.applyRgb8(64, 128, 192))
    }

    @Test
    fun argbInPlacePreservesAlphaAndLinearRgbAppliesSrgbTransform() {
        // Warm sepia-like synthetic LUT: R boosted, B reduced
        val rgb12 = ShortArray(FilmSim3dLut.NODE_COUNT * 3)
        for (b in 0 until 16) {
            for (g in 0 until 16) {
                for (r in 0 until 16) {
                    val y = ((0.3 * r + 0.59 * g + 0.11 * b) * 4095.0 / 15.0)
                    val dst = FilmSim3dLut.nodeIndex(r, g, b) * 3
                    rgb12[dst] = (y * 1.1).toInt().coerceIn(0, 4095).toShort()
                    rgb12[dst + 1] = (y * 0.95).toInt().coerceIn(0, 4095).toShort()
                    rgb12[dst + 2] = (y * 0.75).toInt().coerceIn(0, 4095).toShort()
                }
            }
        }
        val warm = FilmSim3dLut.fromRgb12("WarmMono", rgb12)

        val argb = intArrayOf(0x80_40_80_C0.toInt(), 0x00_FF_00_00, 0xFF_80_80_80.toInt())
        warm.applyArgbInPlace(argb, LutInterpolationMode.TETRAHEDRAL)
        assertEquals(0x80, (argb[0] ushr 24) and 0xFF, "Alpha must be preserved")
        assertEquals(0x00, (argb[1] ushr 24) and 0xFF, "Zero alpha must be preserved")
        assertEquals(0xFF, (argb[2] ushr 24) and 0xFF)
        val r2 = (argb[2] ushr 16) and 0xFF
        val g2 = (argb[2] ushr 8) and 0xFF
        val b2 = argb[2] and 0xFF
        assertTrue(r2 > g2 && g2 > b2, "Expected warm tint R>G>B, got ($r2,$g2,$b2)")

        val lin = LinearRgbImage.uniform(4, 4, 0.2f, 0.2f, 0.2f)
        val graded = warm.applyLinearRgb(lin, encodeSrgb = true)
        assertTrue(graded.red[0] > graded.green[0] && graded.green[0] > graded.blue[0])
    }

    @Test
    fun rejectsMalformedLutBuffersAndMissingFirmwareFile(@TempDir tmp: Path) {
        assertFailsWith<IllegalArgumentException> {
            FilmSim3dLut.fromRgb12("BadLen", ShortArray(10))
        }
        assertFailsWith<IllegalArgumentException> {
            val bad = ShortArray(FilmSim3dLut.NODE_COUNT * 3)
            bad[0] = 4096
            FilmSim3dLut.fromRgb12("OutOfRange", bad)
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareFilmSimLutLoader.loadFromDat(tmp.resolve("nonexistent.DAT"))
        }
        val tiny = tmp.resolve("tiny.DAT")
        Files.write(tiny, ByteArray(64))
        assertFailsWith<IllegalArgumentException> {
            FirmwareFilmSimLutLoader.loadFromDat(tiny)
        }
    }

    @Test
    fun realFirmwareLoadsAll19FilmSimulationsAndProducesDistinctAuthenticTransforms() {
        val datPath = resolveLocalFirmwareDat()
        assumeTrue(datPath != null, "Local GFX100S 2.13 GXUP0004.DAT not present; skipping real-firmware LUT test")

        val pack = FirmwareFilmSimLutLoader.loadFromDat(datPath!!)

        // 1. Verify the 4 raw hardware 3D LUT cubes from section4.bin (0x017F1C8C)
        assertEquals(4, pack.rawHardwareLuts.size, "Expected 4 hardware 3D LUTs in section4.bin")
        val hwClassicNeg = pack.rawHardwareLuts[1]
        val hwNostalgicNeg = pack.rawHardwareLuts[2]
        assertNotEquals(
            pack.rawHardwareLuts[0].applyRgb8(200, 60, 60),
            hwClassicNeg.applyRgb8(200, 60, 60),
            "Hardware LUT[1] (Classic Neg) must differ from identity LUT[0]",
        )
        assertNotEquals(
            hwClassicNeg.applyRgb8(60, 200, 60),
            hwNostalgicNeg.applyRgb8(60, 200, 60),
            "Hardware LUT[2] (Nostalgic Neg) must differ from LUT[1] (Classic Neg)",
        )

        // 2. Verify all 19 still Film Simulations + F-Log + HLG are present and valid 12-bit LUTs
        val still19 = pack.stillSimulations()
        assertEquals(19, still19.size, "Expected all 19 GFX100S still Film Simulations")
        assertEquals(21, pack.luts.size, "Expected 19 still + F-Log + HLG = 21 LUTs")

        assertEquals(1, pack[FilmSimulation.CLASSIC_NEG].hardwareBaseLutIndex)
        assertEquals(2, pack[FilmSimulation.NOSTALGIC_NEG].hardwareBaseLutIndex)

        // 3. Verify distinctness across all 19 still simulations on a multi-patch Macbeth-like test palette
        val patches = listOf(
            Triple(204, 68, 68),   // Red
            Triple(68, 196, 76),   // Green
            Triple(60, 92, 212),   // Blue
            Triple(216, 172, 64),  // Warm Yellow/Skin
            Triple(128, 128, 128), // Mid Gray
            Triple(48, 48, 48),    // Shadow Gray
        )
        val signatures = still19.mapValues { (_, lut) ->
            patches.map { (r, g, b) -> lut.applyRgb8(r, g, b, LutInterpolationMode.TETRAHEDRAL) }
        }
        assertEquals(
            19,
            signatures.values.toSet().size,
            "All 19 Film Simulations must produce unique multi-patch color signatures",
        )

        // 4. Verify physical/photographic signatures of real Fujifilm Bank 14 tables:
        // Velvia must have higher chroma saturation on saturated red/green than Classic Chrome & ETERNA
        fun chroma(argb: Int): Int {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            return maxOf(r, g, b) - minOf(r, g, b)
        }
        val velviaChroma = chroma(pack[FilmSimulation.VELVIA].applyRgb8(204, 68, 68))
        val proviaChroma = chroma(pack[FilmSimulation.PROVIA].applyRgb8(204, 68, 68))
        val classicChromeChroma = chroma(pack[FilmSimulation.CLASSIC_CHROME].applyRgb8(204, 68, 68))
        val bleachChroma = chroma(pack[FilmSimulation.ETERNA_BLEACH_BYPASS].applyRgb8(204, 68, 68))
        assertTrue(
            velviaChroma > proviaChroma && proviaChroma > classicChromeChroma && classicChromeChroma > bleachChroma,
            "Expected Velvia ($velviaChroma) > Provia ($proviaChroma) > Classic Chrome ($classicChromeChroma) > Bleach Bypass ($bleachChroma)",
        )

        // Monochrome & ACROS must produce strictly neutral grayscale (R == G == B)
        for (mono in listOf(
            FilmSimulation.MONOCHROME,
            FilmSimulation.MONOCHROME_YE,
            FilmSimulation.MONOCHROME_R,
            FilmSimulation.MONOCHROME_G,
            FilmSimulation.ACROS,
            FilmSimulation.ACROS_YE,
            FilmSimulation.ACROS_R,
            FilmSimulation.ACROS_G,
        )) {
            val px = pack[mono].applyRgb8(210, 90, 50)
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            assertEquals(r, g, "$mono must be neutral R==G")
            assertEquals(g, b, "$mono must be neutral G==B")
        }

        // Red filter must render red brighter than green filter on a red patch
        val redFilterOnRed = pack[FilmSimulation.ACROS_R].applyRgb8(220, 40, 40) and 0xFF
        val greenFilterOnRed = pack[FilmSimulation.ACROS_G].applyRgb8(220, 40, 40) and 0xFF
        assertTrue(
            redFilterOnRed > greenFilterOnRed + 30,
            "ACROS+R ($redFilterOnRed) should brighten red dramatically vs ACROS+G ($greenFilterOnRed)",
        )

        // Sepia must render mid-gray with authentic Bank 14 warm tint (R > G > B)
        val sepiaMid = pack[FilmSimulation.SEPIA].applyRgb8(128, 128, 128)
        val sr = (sepiaMid ushr 16) and 0xFF
        val sg = (sepiaMid ushr 8) and 0xFF
        val sb = sepiaMid and 0xFF
        assertTrue(sr > sg && sg > sb, "Sepia must produce warm tint R>G>B, got ($sr,$sg,$sb)")

        // F-Log must exhibit the lifted shadow black floor from section4 curve[9] (knot[0] = 1521/16383)
        val flogBlack = pack[FilmSimulation.F_LOG].applyRgb8(0, 0, 0) and 0xFF
        val proviaBlack = pack[FilmSimulation.PROVIA].applyRgb8(0, 0, 0) and 0xFF
        assertTrue(flogBlack > proviaBlack + 10, "F-Log black ($flogBlack) must be lifted above Provia ($proviaBlack)")
    }

    private fun resolveLocalFirmwareDat(): Path? {
        val envSpec = System.getProperty("fuji.firmwareFile")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_FIRMWARE_FILE")?.takeIf { it.isNotBlank() }
        if (envSpec != null) {
            val p = Path.of(envSpec)
            if (Files.isRegularFile(p)) return p
        }
        val rel = Path.of("firmware-data/gfx100s/2.13/GXUP0004.DAT")
        var dir: Path? = Path.of("").toAbsolutePath()
        repeat(5) {
            if (dir != null) {
                val cand = dir.resolve(rel)
                if (Files.isRegularFile(cand)) return cand
                val sibling = dir.resolve("fuji-emulator-gfx100s").resolve(rel)
                if (Files.isRegularFile(sibling)) return sibling
                dir = dir.parent
            }
        }
        return null
    }
}
