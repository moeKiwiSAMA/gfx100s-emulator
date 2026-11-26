package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live GXUP0004.DAT (Dynarmic V2) end-to-end verification for BUG-A1-2:
 * Real-silicon LiveView Film Simulation (PROVIA / ACROS / VELVIA) and Tone Curve (H/S)
 * via ISP 0xFFF68300..14 (3x3 Q10 RGB CCM), 0xFFF68400..C4 (3x31-knot 14-bit Tone LUT),
 * and 0xFFF68500..14 (3x3 RGB->YCbCr matrix) over scene://depth-studio Macbeth ColorChecker.
 */
class IspFilmSimToneCurveE2eTest {
    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun overlayWorktreeStage(repo: Path, workRoot: Path) {
        val emuStage = repo.resolve("build/emulator")
        val compDir = emuStage.resolve("compoments")
        val dstCompDir = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDir)) {
            val expectedJars = Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
        val lensesDir = emuStage.resolve("lenses")
        if (Files.isDirectory(lensesDir)) {
            copyDirectory(lensesDir, workRoot.resolve("lenses"))
        }
        val emuYaml = repo.resolve("tools/release/emulator-release-linux.yaml")
        if (Files.isRegularFile(emuYaml)) {
            Files.copy(emuYaml, workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
        }
        val catalogJson = repo.resolve("catalog/compiled/catalog.json")
        if (Files.isRegularFile(catalogJson)) {
            Files.copy(catalogJson, workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        }
        val relMeta = repo.resolve("build/release-metadata/gfx100s-213-release.yaml")
        if (Files.isRegularFile(relMeta)) {
            val dst = workRoot.resolve("configs/firmware/gfx100s-213-release.yaml")
            Files.createDirectories(dst.parent)
            Files.copy(relMeta, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        val compConfigs = repo.resolve("tools/release/component-configs")
        if (Files.isDirectory(compConfigs)) {
            copyDirectory(compConfigs, workRoot.resolve("configs/components"))
        }
    }

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long): Long {
        val start = rt.owner.submit { (rt.session.state()["attempts"] as Number).toLong() }.join()
        val target = start + deltaAttempts
        var current = start
        while (current < target) {
            current = rt.owner.submit {
                var i = 0
                while (i < 2048) {
                    if (!rt.session.stepFast()) break
                    i++
                }
                (rt.session.state()["attempts"] as Number).toLong()
            }.join()
            val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            if (status != "running") break
        }
        return current
    }

    private fun patchComponent(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, settings).join()
        }.join()
    }

    private fun stepByVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var guard = 0
        while (rt.telemetry.virtualTick < target && guard < 10_000) {
            guard++
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 512 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long = 5_000_000L, settleAttempts: Long = 24_000_000L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepByVirtualTicks(rt, holdTicks)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleAttempts)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private data class ColorMetrics(
        val nonBlack: Int,
        val meanLuma: Double,
        val lumaStdDev: Double,
        val meanChroma: Double,
        val meanSat: Double,
    )

    private fun captureCenterColorMetrics(rt: EmulatorRuntime): ColorMetrics = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = display.width
        val h = display.height
        val pixels = IntArray(w * h)
        display.copyArgb(pixels)
        var nonBlack = 0
        val x0 = w / 4
        val x1 = (w * 3) / 4
        val y0 = h / 4
        val y1 = (h * 3) / 4
        var count = 0
        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var chromaSum = 0.0
        var satSum = 0.0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                if ((p and 0x00ffffff) != 0) nonBlack++
                if (x in x0 until x1 && y in y0 until y1) {
                    val r = (p ushr 16) and 0xff
                    val g = (p ushr 8) and 0xff
                    val b = p and 0xff
                    val luma = 0.299 * r + 0.587 * g + 0.114 * b
                    val mx = maxOf(r, maxOf(g, b))
                    val mn = minOf(r, minOf(g, b))
                    val chroma = (mx - mn).toDouble()
                    val sat = if (mx > 0) (chroma * 100.0) / mx else 0.0
                    lumaSum += luma
                    lumaSqSum += luma * luma
                    chromaSum += chroma
                    satSum += sat
                    count++
                }
            }
        }
        val meanLuma = if (count > 0) lumaSum / count else 0.0
        val variance = if (count > 0) ((lumaSqSum / count) - meanLuma * meanLuma).coerceAtLeast(0.0) else 0.0
        ColorMetrics(
            nonBlack = nonBlack,
            meanLuma = meanLuma,
            lumaStdDev = sqrt(variance),
            meanChroma = if (count > 0) chromaSum / count else 0.0,
            meanSat = if (count > 0) satSum / count else 0.0,
        )
    }.join()

    private fun readBank68Words(rt: EmulatorRuntime): List<Long> = rt.owner.submit {
        val isp = rt.host.components().single { it.descriptor.id == "soc-isp" }.component as StatefulComponent
        @Suppress("UNCHECKED_CAST")
        (isp.saveSnapshot()["bank68000Words"] as List<Number>).map { it.toLong() }
    }.join()

    private fun sampleState(rt: EmulatorRuntime, label: String, prevBank: List<Long>?): Pair<ColorMetrics, List<Long>> {
        val scr = readU32(rt, 0x01CC6424L)
        val metrics = captureCenterColorMetrics(rt)
        val cpState = rt.owner.submit {
            rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()["colorPipeline"]
        }.join()
        val bank = readBank68Words(rt)
        val changedCount = if (prevBank != null) bank.indices.count { bank[it] != prevBank[it] } else 0
        val diffSample = if (prevBank != null) {
            bank.indices.filter { bank[it] != prevBank[it] }.take(24).joinToString(",") { idx ->
                "$idx:0x${prevBank[idx].toString(16)}->0x${bank[idx].toString(16)}"
            }
        } else {
            ""
        }
        println(
            "[ISP-FILMSIM-E2E][$label] scr=0x${scr.toString(16)} nb=${metrics.nonBlack} " +
                "luma=${"%.2f".format(metrics.meanLuma)} std=${"%.2f".format(metrics.lumaStdDev)} " +
                "chroma=${"%.2f".format(metrics.meanChroma)} sat=${"%.2f".format(metrics.meanSat)} " +
                "cp=$cpState bank68Changed=$changedCount diff=[$diffSample]",
        )
        return metrics to bank
    }

    @Test
    fun liveFirmwareModulatesLiveViewColorMatrixAndToneCurveAcrossFilmSims(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires staged release at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("isp-filmsim-release")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)
        // Use vivid Macbeth ColorChecker optical scene (scene://depth-studio) for color saturation verification
        val cmosYaml = workRoot.resolve("configs/components/cmos/cmos.yaml")
        if (Files.isRegularFile(cmosYaml)) {
            Files.writeString(cmosYaml, Files.readString(cmosYaml).replace("scene://qr-ball", "scene://depth-studio"))
        }

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot -> OOBE -> STILL LiveView (0x0100)
            stepBy(rt, 620_000_000L)
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected Language OOBE 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            var hudReached = false
            for (iter in 0 until 45) {
                stepBy(rt, 6_000_000L)
                val scr = readU32(rt, 0x01CC6424L)
                val m = captureCenterColorMetrics(rt)
                if (scr == 0x0100 && m.nonBlack > 150_000) {
                    hudReached = true
                    break
                }
            }
            assertTrue(hudReached, "Expected STILL LiveView HUD 0x0100")

            // Let AE settle in default PROVIA (bounded wait until initial bright boot ramp settles below clipping)
            for (settleIter in 0 until 24) {
                stepBy(rt, 45_000_000L)
                val probe = captureCenterColorMetrics(rt)
                if (settleIter >= 2 && probe.meanLuma <= 200.0 && probe.meanSat >= 20.0) break
            }
            val (provia, bankProvia) = sampleState(rt, "PROVIA", null)

            // 2. Open I.Q. Menu -> Row 3 (FILM SIMULATION) -> Velvia/VIVID (index 1: down x1 from PROVIA)
            tapButton(rt, "menu", 6_000_000L, 40_000_000L)
            repeat(3) { tapButton(rt, "down", 5_000_000L, 24_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 30_000_000L)
            tapButton(rt, "down", 5_000_000L, 24_000_000L)
            tapButton(rt, "menu", 5_000_000L, 28_000_000L)
            tapButton(rt, "shutterHalf", 5_000_000L, 50_000_000L)
            for (settleIter in 0 until 20) {
                stepBy(rt, 35_000_000L)
                val probe = captureCenterColorMetrics(rt)
                if (settleIter >= 1 && probe.meanLuma <= 195.0) break
            }
            val (velvia, bankVelvia) = sampleState(rt, "VELVIA", bankProvia)

            // 3. Open I.Q. Menu -> FILM SIMULATION (cursor retained) -> wrap up x4 (Velvia->PROVIA->SEPIA->MONOCHROME->ACROS) -> STD
            tapButton(rt, "menu", 6_000_000L, 35_000_000L)
            tapButton(rt, "menu", 5_000_000L, 30_000_000L)
            repeat(4) { tapButton(rt, "up", 5_000_000L, 24_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 28_000_000L)
            tapButton(rt, "menu", 5_000_000L, 28_000_000L)
            tapButton(rt, "shutterHalf", 5_000_000L, 50_000_000L)
            stepBy(rt, 60_000_000L)
            val (acros, bankAcros) = sampleState(rt, "ACROS", bankVelvia)

            // 4. Open I.Q. Menu -> TONE CURVE (down x8 from FILM SIMULATION when MONOCHROMATIC COLOR is enabled under ACROS/MONOCHROME) -> H+4.0 / S+4.0 (0.5-step increments)
            tapButton(rt, "menu", 6_000_000L, 35_000_000L)
            repeat(8) { tapButton(rt, "down", 5_000_000L, 22_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 30_000_000L)
            repeat(8) { tapButton(rt, "up", 5_000_000L, 18_000_000L) }
            tapButton(rt, "right", 5_000_000L, 22_000_000L)
            repeat(8) { tapButton(rt, "up", 5_000_000L, 18_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 28_000_000L)
            tapButton(rt, "shutterHalf", 5_000_000L, 50_000_000L)
            stepBy(rt, 35_000_000L)
            val (toneHi, bankToneHi) = sampleState(rt, "TONE_H4_S4", bankAcros)

            val relaxed = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join().orEmpty()
            println("[ISP-FILMSIM-E2E] RELAXED=$relaxed provia=$provia velvia=$velvia acros=$acros toneHi=$toneHi")
            assertTrue(!relaxed.contains("[RELAXED/MMIO]"), "Expected zero [RELAXED/MMIO], got $relaxed")
            assertTrue(provia.nonBlack > 150_000 && velvia.nonBlack > 150_000 && acros.nonBlack > 150_000 && toneHi.nonBlack > 150_000)

            // 1. PROVIA renders vivid Macbeth ColorChecker chroma
            assertTrue(provia.meanSat >= 20.0 && provia.meanChroma >= 25.0, "Expected vivid PROVIA color saturation: $provia")
            // 2. VELVIA updates CCM/Tone LUT & boosts vivid saturation above PROVIA
            assertTrue(
                velvia.meanSat > provia.meanSat + 5.0 && velvia.meanChroma >= 25.0,
                "Expected VELVIA to boost saturation above PROVIA: provia=$provia velvia=$velvia",
            )
            assertTrue(bankVelvia != bankProvia, "Expected VELVIA to update ISP bank68000 registers vs PROVIA")
            // 3. ACROS/MONOCHROME engages real 0xFFF68010 bit 0 monochrome hardware mode -> true B&W (>85% chroma collapse; tiny residual allowed for colored HUD/AF overlay)
            val acrosMono = rt.owner.submit {
                @Suppress("UNCHECKED_CAST")
                val cp = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()["colorPipeline"] as? Map<String, Any?>
                cp?.get("monochromeMode") == true
            }.join()
            assertTrue(
                acrosMono && acros.meanChroma < 8.0 && acros.meanSat < 4.0 && acros.meanChroma < provia.meanChroma * 0.15,
                "Expected ACROS/MONOCHROME LiveView to render true monochrome (monochromeMode=$acrosMono, chroma/sat collapse): acros=$acros provia=$provia",
            )
            // 4. TONE CURVE H+4/S+4 updates 0xFFF68204..0xFFF68250 (words 11..30) & visibly modulates LiveView tone/contrast
            val toneCurveWordsChanged = (11..30).count { bankToneHi[it] != bankAcros[it] }
            assertTrue(
                toneCurveWordsChanged >= 16,
                "Expected TONE CURVE H+4/S+4 to reprogram 0xFFF68204..0xFFF68250 (changed=$toneCurveWordsChanged/20)",
            )
            assertTrue(
                kotlin.math.abs(toneHi.meanLuma - acros.meanLuma) >= 4.0 || kotlin.math.abs(toneHi.lumaStdDev - acros.lumaStdDev) >= 3.0,
                "Expected TONE CURVE H+4/S+4 to visibly modulate LiveView tone/contrast: acros=$acros toneHi=$toneHi",
            )
        }
    }
}
