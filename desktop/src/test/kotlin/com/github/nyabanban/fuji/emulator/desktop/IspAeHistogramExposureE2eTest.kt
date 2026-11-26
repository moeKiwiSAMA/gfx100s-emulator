package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live GXUP0004.DAT (Dynarmic V2) end-to-end verification for BUG-A3-2:
 * - Real-silicon 56 KiB ISP Stats SRAM (AE grids sel 0..3 / sel 6..9 / sel 10 + 256-bin Histogram sel 33..36)
 * - Authentic firmware P/A/S/M metering & LiveView Histogram rendering
 * - IMX461 SPI exposure registers (SHR/SVR/PGC/HCG/VMAX) & ISP digital gains modulating LiveView brightness
 */
class IspAeHistogramExposureE2eTest {
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

    private fun tapButton(rt: EmulatorRuntime, key: String, holdAttempts: Long, settleAttempts: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdAttempts)
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

    private fun captureMainMetrics(rt: EmulatorRuntime): Triple<Int, Double, IntArray> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = display.width
        val h = display.height
        val pixels = IntArray(w * h)
        display.copyArgb(pixels)
        var nonBlack = 0
        var lumaSum = 0.0
        var lumaCount = 0
        val x0 = w / 4
        val x1 = (w * 3) / 4
        val y0 = h / 4
        val y1 = (h * 3) / 4
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val p = pixels[row + x]
                if ((p and 0x00ffffff) != 0) nonBlack++
                if (x in x0 until x1 && y in y0 until y1) {
                    val r = (p ushr 16) and 0xff
                    val g = (p ushr 8) and 0xff
                    val b = p and 0xff
                    lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                    lumaCount++
                }
            }
        }
        Triple(nonBlack, if (lumaCount > 0) lumaSum / lumaCount else 0.0, pixels)
    }.join()

    private fun sampleIspTelemetry(rt: EmulatorRuntime, label: String): Map<String, Any?> {
        val scr = readU32(rt, 0x01CC6424L)
        val histBasePtr = readU32(rt, 0x032C06D8L)
        val fwHistBinSum = (0 until 256).sumOf { b -> readU32(rt, 0x02AB2B58L + b * 4L).toLong() and 0xFFFF_FFFFL }
        val (nonBlack, centerLuma, _) = captureMainMetrics(rt)
        val info = rt.owner.submit {
            val ispState = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
            val expPort = rt.host.ports.find("soc-isp-cmd.imx461.exposure", Imx461ExposurePort::class.java)
            val histogramPort = if (histBasePtr.toUInt() == 0xFFF17000u) "soc-isp.stats-1" else "soc-isp.stats-0"
            val histogram = rt.host.ports.find(histogramPort, MmioDevice::class.java)
            val surf0 = rt.host.ports.find("soc-isp.result-surface-v3", MmioDevice::class.java)
                ?: rt.host.ports.find("soc-isp.result-surface-v2", MmioDevice::class.java)
            var histNonZeroBins = 0
            var histTotal = 0L
            if (histogram != null) {
                for (bin in 0 until 256) {
                    val v = histogram.read((bin * 4).toLong(), 4)
                    if (v > 0L) {
                        histNonZeroBins++
                        histTotal += v
                    }
                }
            }
            val aeCell0 = surf0?.read(0x0000L, 4) ?: -1L
            val aeCell120 = surf0?.read((120 * 4).toLong(), 4) ?: -1L
            linkedMapOf<String, Any?>(
                "stats3a" to ispState["stats3a"],
                "irq106" to ispState["irq106"],
                "liveExposure" to ispState["liveExposure"],
                "imx461" to if (expPort != null) {
                    mapOf(
                        "hasExposureRegs" to expPort.hasExposureRegs,
                        "shr15" to expPort.shr15,
                        "svr12" to expPort.svr12,
                        "pgc12" to expPort.pgc12,
                        "pgc2" to expPort.pgcSecondary12,
                        "hcg" to expPort.hcgEnabled,
                        "vmax12" to expPort.vmax12,
                        "dgain8" to expPort.dgain8,
                    )
                } else {
                    null
                },
                "histNonZeroBins" to histNonZeroBins,
                "histTotal" to histTotal,
                "aeCell0" to aeCell0,
                "aeCell120" to aeCell120,
            )
        }.join()
        println(
            "[ISP-AE-E2E][$label] scr=0x${scr.toString(16)} nonBlack=$nonBlack centerLuma=${"%.2f".format(centerLuma)} " +
                "histPtr=0x${histBasePtr.toUInt().toString(16)} fwHistSum=$fwHistBinSum irq106=${info["irq106"]} " +
                "aeCell0=${info["aeCell0"]} aeCell120=${info["aeCell120"]} " +
                "histBins=${info["histNonZeroBins"]}/256 histTotal=${info["histTotal"]} " +
                "imx461=${info["imx461"]} liveExp=${info["liveExposure"]} stats3a=${info["stats3a"]}",
        )
        return info + mapOf("scr" to scr, "nonBlack" to nonBlack, "centerLuma" to centerLuma, "fwHistSum" to fwHistBinSum)
    }

    @Test
    fun liveFirmwareRunsAePhotometryHistogramAndManualExposureModulation(@TempDir tempRoot: Path) {
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

        val workRoot = tempRoot.resolve("isp-ae-release")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot -> OOBE (0x0421) -> STILL LiveView (0x0100)
            for (bootIter in 0 until 90) {
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
                stepBy(rt, 12_000_000L)
            }
            stepBy(rt, 40_000_000L)
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
                val (nb, _, _) = captureMainMetrics(rt)
                if (scr == 0x0100 && nb > 150_000) {
                    hudReached = true
                    break
                }
            }
            assertTrue(hudReached, "Expected STILL LiveView HUD 0x0100")

            // Let closed-loop AE settle completely in default P mode (bounded wait past initial bright boot ramp)
            stepBy(rt, 95_000_000L)
            for (settleIter in 0 until 12) {
                if (captureMainMetrics(rt).second <= 215.0) break
                stepBy(rt, 35_000_000L)
            }
            val modeP = sampleIspTelemetry(rt, "MODE_P")

            // Switch to A mode
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "A"))
            stepBy(rt, 50_000_000L)
            val modeA = sampleIspTelemetry(rt, "MODE_A")

            // Switch to S mode
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "S"))
            stepBy(rt, 50_000_000L)
            val modeS = sampleIspTelemetry(rt, "MODE_S")

            // Switch to M mode & let LiveView brightness settle completely before sweeping shutter via rearDialDetents
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "M"))
            stepBy(rt, 120_000_000L)
            val modeM0 = sampleIspTelemetry(rt, "MODE_M_INIT")

            patchComponent(rt, "soc-adc", mapOf("rearDialDetents" to -12L))
            stepBy(rt, 120_000_000L)
            val modeMSlow = sampleIspTelemetry(rt, "MODE_M_DIAL_MINUS12")

            patchComponent(rt, "soc-adc", mapOf("rearDialDetents" to 18L))
            stepBy(rt, 160_000_000L)
            val modeMFast = sampleIspTelemetry(rt, "MODE_M_DIAL_PLUS18")

            // Cycle DISP/BACK to 15-tile Info Display (renders LiveView Histogram from 0xFFF16000)
            tapButton(rt, "back", 5_000_000L, 45_000_000L)
            val disp1 = sampleIspTelemetry(rt, "DISP_CYCLE_1")
            tapButton(rt, "back", 5_000_000L, 55_000_000L)
            val disp2 = sampleIspTelemetry(rt, "DISP_CYCLE_2")

            // Core assertions on live Stats SRAM, closed-loop AE convergence, live firmware histogram, and M-mode exposure shift
            @Suppress("UNCHECKED_CAST")
            val statsA = modeA["stats3a"] as Map<String, Any?>
            assertEquals(true, statsA["nativeShadowBound"], "Expected live Dynarmic RO-MMIO Stats SRAM binding")
            assertTrue((modeP["aeCell120"] as Long) > 0L, "Expected non-zero AE photometry grid in P mode: $modeP")
            assertTrue((modeA["histNonZeroBins"] as Int) >= 50, "Expected multi-bin LiveView histogram in A mode: $modeA")
            val histogramTotal = modeA["histTotal"] as Long
            val gpuUpdates = (statsA["gpuStatsFramesUpdated"] as Number).toLong()
            if (gpuUpdates > 0 && gpuUpdates == statsA["histFramesUpdated"]) {
                // The default 4:3 still frame uses 768x576 of the 1024x576 optical raster.
                // The fused reduction samples one Bayer quad per four pixels on each axis.
                assertEquals((768L / 4) * (576L / 4), histogramTotal, "Expected the complete sampled Bayer histogram")
            } else {
                assertTrue(histogramTotal >= 200_000L, "Expected a full rendered LiveView histogram: $modeA")
            }
            assertEquals(histogramTotal, modeA["fwHistSum"], "Firmware RAM must contain the complete 256-bin histogram")

            // Verify P/A/S closed-loop AE is NOT pegged dark (max PGC=4030 / HCG=true / SHR=16)
            for ((name, sample) in listOf("P" to modeP, "A" to modeA, "S" to modeS)) {
                @Suppress("UNCHECKED_CAST")
                val imx = sample["imx461"] as Map<String, Any?>
                val pgc = (imx["pgc12"] as Number).toInt()
                val shr = (imx["shr15"] as Number).toInt()
                val hcg = imx["hcg"] as Boolean
                val luma = sample["centerLuma"] as Double
                assertTrue(pgc < 3500 && !hcg && shr > 100, "Mode $name AE must converge (not pegged dark): imx=$imx")
                assertTrue(luma in 60.0..220.0, "Mode $name LiveView luma must be well-exposed: luma=$luma")
            }

            // Verify M-mode shutter dial visibly modulates IMX461 SHR/PGC, AE photometry, and LiveView brightness
            @Suppress("UNCHECKED_CAST")
            val imxInit = modeM0["imx461"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val imxSlow = modeMSlow["imx461"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val imxBack = modeMFast["imx461"] as Map<String, Any?>
            val shrInit = (imxInit["shr15"] as Number).toInt()
            val shrSlow = (imxSlow["shr15"] as Number).toInt()
            val shrBack = (imxBack["shr15"] as Number).toInt()
            val pgcInit = (imxInit["pgc12"] as Number).toInt()
            val pgcSlow = (imxSlow["pgc12"] as Number).toInt()
            val pgcBack = (imxBack["pgc12"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val gainGInit = ((modeM0["liveExposure"] as Map<String, Any?>)["gainG1024"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val gainGSlow = ((modeMSlow["liveExposure"] as Map<String, Any?>)["gainG1024"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val gainGBack = ((modeMFast["liveExposure"] as Map<String, Any?>)["gainG1024"] as Number).toInt()
            @Suppress("UNCHECKED_CAST")
            val gainGS = ((modeS["liveExposure"] as Map<String, Any?>)["gainG1024"] as Number).toInt()
            val lumaInit = modeM0["centerLuma"] as Double
            val lumaSlow = modeMSlow["centerLuma"] as Double
            val lumaBack = modeMFast["centerLuma"] as Double
            val aeInit = (modeM0["aeCell120"] as Long)
            val aeSlow = (modeMSlow["aeCell120"] as Long)
            assertTrue(shrSlow < shrInit && shrBack > shrSlow, "Expected IMX461 SHR to decrease on +12 detents and recover on -18: init=$shrInit slow=$shrSlow back=$shrBack")
            assertTrue(
                (gainGSlow < gainGInit && gainGBack >= gainGSlow) || (pgcSlow != pgcInit && pgcBack != pgcSlow) || gainGS != gainGInit,
                "Expected ISP digital gain (0xFFF68108/0C) or IMX461 PGC to modulate across exposure modes: gainG=($gainGS,$gainGInit,$gainGSlow,$gainGBack) pgc=($pgcInit,$pgcSlow,$pgcBack)",
            )
            assertTrue(
                kotlin.math.abs(lumaSlow - lumaInit) >= 5.0 && lumaBack < lumaSlow - 5.0,
                "Expected visible reversible LiveView brightness shift in M mode: lumaInit=$lumaInit -> lumaSlow=$lumaSlow -> lumaBack=$lumaBack",
            )
            assertTrue(aeSlow > aeInit, "Expected AE photometry to rise with longer shutter: aeInit=$aeInit aeSlow=$aeSlow")

            // Verify P/A auto-exposure modes converge consistently, and brighter M_INIT visibly exceeds Mode S
            val lumaS = modeS["centerLuma"] as Double
            val lumaA = modeA["centerLuma"] as Double
            val lumaP = modeP["centerLuma"] as Double
            val aeS = modeS["aeCell120"] as Long
            @Suppress("UNCHECKED_CAST")
            val shrS = ((modeS["imx461"] as Map<String, Any?>)["shr15"] as Number).toInt()
            assertTrue(shrInit != shrS || gainGInit != gainGS, "Expected M_INIT exposure registers to differ from Mode S: shr=($shrInit,$shrS) gainG=($gainGInit,$gainGS)")
            assertTrue(aeInit > aeS, "Expected M_INIT AE photometry to exceed Mode S: aeS=$aeS aeInit=$aeInit")
            assertTrue(
                lumaInit - lumaS >= 8.0,
                "Expected visible LiveView brightness delta between Mode S (shr=$shrS) and Mode M_INIT (shr=$shrInit): S=$lumaS M=$lumaInit",
            )
            assertTrue(
                kotlin.math.abs(lumaP - lumaA) <= 55.0,
                "Expected auto-exposure modes P and A to converge to compatible target brightness: P=$lumaP A=$lumaA",
            )

            val relaxed = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join().orEmpty()
            println("[ISP-AE-E2E] RELAXED=$relaxed (S=${"%.2f".format(lumaS)} A=${"%.2f".format(lumaA)} P=${"%.2f".format(lumaP)} M=${"%.2f".format(lumaInit)}->${"%.2f".format(lumaSlow)}->${"%.2f".format(lumaBack)})")
            assertTrue(!relaxed.contains("[RELAXED/MMIO]"), "Expected zero [RELAXED/MMIO], got $relaxed")
            assertTrue((modeM0["nonBlack"] as Int) > 100_000 && (disp1["nonBlack"] as Int) > 100_000 && (disp2["nonBlack"] as Int) > 100_000)
        }
    }
}
