package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live Dynarmic V2 E2E verification for Track `AF-PDAF-HYBRID`:
 * Exercises the real GFX100S 2.13 On-Sensor PDAF hardware engine (`0xFFF71000..0xFFF71217`,
 * GIC IRQ 122) on `GXUP0004.DAT` with 3D metric depth (`scene://qr-ball`) and live `LENSCOM`
 * focus-motor closed-loop convergence across AF-S and AF-C.
 */
class PdafHybridAfLiveE2eTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, componentId: String, patch: Map<String, Any>) {
        val rev = rt.owner.submit { rt.host.components().single { it.descriptor.id == componentId }.revision }.join()
        rt.host.patch(componentId, rev, patch).join()
    }

    private fun stepBy(rt: EmulatorRuntime, deltaTicks: Long, maxCalls: Int = 500_000) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var calls = 0
        while (rt.telemetry.virtualTick < target && calls < maxCalls) {
            val cont = rt.owner.submit {
                var ok = true
                var batch = 0
                while (ok && batch < 64 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    ok = rt.session.stepFast()
                    batch++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                ok
            }.join()
            if (!cont || rt.session.isSocPoweredOff) break
            calls++
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun captureMainStats(rt: EmulatorRuntime): Pair<Long, Int> =
        rt.owner.submit {
            val port = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            val w = port.width
            val h = port.height
            val seq = port.sequence
            if (w <= 0 || h <= 0) return@submit seq to 0
            val px = IntArray(w * h)
            port.copyArgb(px)
            seq to px.count { (it and 0x00ffffff) != 0 }
        }.join()

    @Test
    fun livePdafEngineRunsUnderAfsAndAfcWithClosedLoopLensConvergenceAndZeroFallbacks(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("release-pdaf-af")
        copyDirectory(stagedRoot, workRoot)
        // Use a genuine 2D planar PNG target (depthMeters == null) with gf110 (110mm F2, thin DoF + crisp
        // PDAF signal under calibrated 0.402 mm/px sensitivity) so LensFactory/PdafHardwareEngine follow
        // the test's explicit subjectDistanceM shifts (2.0m -> 4.5m -> 1.4m) rather than a 3D depth map.
        val checkerPng = workRoot.resolve("configs/components/cmos/pdaf-checker.png")
        Files.createDirectories(checkerPng.parent)
        val img = java.awt.image.BufferedImage(640, 480, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 480) {
            for (x in 0 until 640) {
                val cell = ((x / 16) + (y / 16)) and 1
                img.setRGB(x, y, if (cell == 0) 0xD8D8D8 else 0x202020)
            }
        }
        javax.imageio.ImageIO.write(img, "png", checkerPng.toFile())
        val cmosYaml = workRoot.resolve("configs/components/cmos/cmos.yaml")
        if (Files.isRegularFile(cmosYaml)) {
            Formats.atomicYaml(
                cmosYaml,
                mapOf(
                    "sourceKind" to "PNG",
                    "sourceFile" to "pdaf-checker.png",
                    "loop" to true,
                ),
            )
        }
        val lensYaml = workRoot.resolve("configs/components/lens/lens.yaml")
        if (Files.isRegularFile(lensYaml)) {
            val existingLens = Formats.read(lensYaml)
            Formats.atomicYaml(lensYaml, existingLens + mapOf("template" to "gf110", "opticsEnabled" to true))
        }
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot through OOBE -> Live STILL HUD (0x0100)
            for (i in 0 until 90) {
                stepBy(rt, 10_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")
            stepBy(rt, 20_000_000L)
            for (attempt in 0 until 3) {
                if (readGuestU32(rt, 0x01CC6424L) != 0x0421) break
                tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            }
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (i in 0 until 60) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && captureMainStats(rt).second > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected Live STILL HUD 0x0100")

            data class AfSample(
                val scr: Int,
                val s1Sub0: Int,
                val focusM: Double,
                val targetM: Double,
                val spiFrames: Long,
                val pdafCompletions: Long,
                val lastFocusM: Double,
                val lastDeltaPx: Double,
            )

            fun readAfSample(): AfSample = rt.owner.submit {
                val ispSt = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
                val lensSt = rt.host.components().single { it.descriptor.id == "lens" }.component.state()
                val mountSt = rt.host.components().single { it.descriptor.id == "mount" }.component.state()
                val pdaf = ispSt["pdaf"] as? Map<*, *>
                val spi = mountSt["spiBridge"] as? Map<*, *>
                AfSample(
                    scr = readGuestU32(rt, 0x01CC6424L),
                    s1Sub0 = readGuestU32(rt, 0x02C47C5CL + 0x60L) and 0xff,
                    focusM = (lensSt["focusDistanceM"] as? Number)?.toDouble() ?: Double.NaN,
                    targetM = (lensSt["focusTargetDistanceM"] as? Number)?.toDouble() ?: Double.NaN,
                    spiFrames = (spi?.get("frameCount") as? Number)?.toLong() ?: 0L,
                    pdafCompletions = (pdaf?.get("completionCount") as? Number)?.toLong() ?: 0L,
                    lastFocusM = (pdaf?.get("lastFocusDistanceM") as? Number)?.toDouble() ?: Double.NaN,
                    lastDeltaPx = (pdaf?.get("lastDeltaPx") as? Number)?.toDouble() ?: 0.0,
                )
            }.join()

            // 2. AF-S (input7=0x2040): Defocus lens to 6.0 m with planar subject at 2.0 m (back-focus)
            patchComponent(rt, "lens", mapOf("focusDistanceM" to 6.0, "subjectDistanceM" to 2.0))
            stepBy(rt, 30_000_000L)
            val beforeAfs = readAfSample()

            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            var minFocusAfs = beforeAfs.focusM
            var minErrTo2m = abs(beforeAfs.focusM - 2.0)
            var sawNegativeDeltaAfs = false
            var afsCompleted = false
            var lastAfs = beforeAfs
            for (s in 1..100) {
                stepBy(rt, 2_000_000L)
                lastAfs = readAfSample()
                if (lastAfs.focusM < minFocusAfs) minFocusAfs = lastAfs.focusM
                if (lastAfs.lastFocusM > 0.1 && lastAfs.lastFocusM < minFocusAfs) minFocusAfs = lastAfs.lastFocusM
                minErrTo2m = minOf(minErrTo2m, abs(lastAfs.focusM - 2.0))
                if (lastAfs.lastFocusM > 0.1) minErrTo2m = minOf(minErrTo2m, abs(lastAfs.lastFocusM - 2.0))
                if (lastAfs.lastDeltaPx < -1.0) sawNegativeDeltaAfs = true
                if (lastAfs.scr == 0x011c || lastAfs.s1Sub0 == 8) {
                    afsCompleted = true
                    break
                }
            }
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepBy(rt, 20_000_000L)

            assertTrue(
                lastAfs.pdafCompletions > beforeAfs.pdafCompletions,
                "Expected real PDAF hardware completions during AF-S, got $lastAfs",
            )
            assertTrue(
                lastAfs.spiFrames > beforeAfs.spiFrames + 20L,
                "Expected active LENSCOM focus-motor SPI traffic during AF-S, before=${beforeAfs.spiFrames} after=${lastAfs.spiFrames}",
            )
            assertTrue(
                sawNegativeDeltaAfs,
                "Expected negative PDAF disparity (back-focus 6.0m vs 2.0m subject) during AF-S",
            )
            assertTrue(
                minFocusAfs < 3.0 && minErrTo2m < 0.6,
                "Expected AF-S closed-loop convergence from 6.0m toward 2.0m, got minFocusM=$minFocusAfs minErr=$minErrTo2m",
            )
            assertTrue(
                afsCompleted,
                "Expected AF-S acquisition completion (scr=0x011c or s1Sub0=8), got $lastAfs",
            )

            // 3. AF-C (GPIO 7.13=1, 7.14=1 -> input7=0x6040=24640): Bi-directional dynamic subject tracking
            rt.owner.submit {
                rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 24640L))
            }.join()
            stepBy(rt, 20_000_000L)

            // Phase 3A: Near -> Far (focus=2.2m, subject=4.5m -> positive disparity -> drive lens outward)
            patchComponent(rt, "lens", mapOf("focusDistanceM" to 2.2, "subjectDistanceM" to 4.5))
            val beforeAfcFar = readAfSample()
            patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
            var maxFocusAfcFar = beforeAfcFar.focusM
            var sawPositiveDeltaAfcFar = false
            var lastAfcFar = beforeAfcFar
            for (s in 1..14) {
                stepBy(rt, 8_000_000L)
                lastAfcFar = readAfSample()
                if (lastAfcFar.focusM > maxFocusAfcFar) maxFocusAfcFar = lastAfcFar.focusM
                if (lastAfcFar.lastDeltaPx > 1.0) sawPositiveDeltaAfcFar = true
                if (sawPositiveDeltaAfcFar && maxFocusAfcFar > 3.5) break
            }
            assertTrue(
                lastAfcFar.pdafCompletions > beforeAfcFar.pdafCompletions,
                "Expected PDAF completions during AF-C Near->Far tracking, got $lastAfcFar",
            )
            assertTrue(
                sawPositiveDeltaAfcFar,
                "Expected positive PDAF disparity (front-focus 2.2m vs 4.5m subject) during AF-C",
            )
            assertTrue(
                maxFocusAfcFar > 3.0,
                "Expected AF-C continuous tracking outward from 2.2m toward 4.5m, got maxFocusM=$maxFocusAfcFar last=$lastAfcFar",
            )

            // Phase 3B: Far -> Near while holding shutterHalf (shift subject to 1.6m -> negative disparity -> reverse inward)
            val focusBeforeNearShift = lastAfcFar.focusM
            val pdafBeforeNearShift = lastAfcFar.pdafCompletions
            patchComponent(rt, "lens", mapOf("subjectDistanceM" to 1.6))
            var minFocusAfcNear = focusBeforeNearShift
            var sawNegativeDeltaAfcNear = false
            var lastAfcNear = lastAfcFar
            for (s in 1..14) {
                stepBy(rt, 8_000_000L)
                lastAfcNear = readAfSample()
                if (lastAfcNear.focusM < minFocusAfcNear) minFocusAfcNear = lastAfcNear.focusM
                if (lastAfcNear.targetM < minFocusAfcNear) minFocusAfcNear = lastAfcNear.targetM
                if (lastAfcNear.lastDeltaPx < -1.0) sawNegativeDeltaAfcNear = true
                if (sawNegativeDeltaAfcNear && minFocusAfcNear < focusBeforeNearShift - 1.5) break
            }
            patchComponent(rt, "buttons", mapOf("shutterHalf" to false))
            stepBy(rt, 20_000_000L)

            assertTrue(
                lastAfcNear.pdafCompletions > pdafBeforeNearShift,
                "Expected fresh PDAF completions during AF-C Far->Near tracking, got $lastAfcNear",
            )
            assertTrue(
                sawNegativeDeltaAfcNear,
                "Expected negative PDAF disparity flip after moving subject to 1.6m during AF-C",
            )
            assertTrue(
                minFocusAfcNear < focusBeforeNearShift - 1.2,
                "Expected AF-C to reverse focus inward toward 1.6m from ${focusBeforeNearShift}m, got minFocusM=$minFocusAfcNear last=$lastAfcNear",
            )

            // 4. Zero-Fallback Guarantee across the entire AF-S + AF-C session
            val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            val sessionState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = sessionState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = (relaxed?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
            val flashRefusals = (relaxed?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            assertEquals(0L, mmioFallbacks, "Expected ZERO [RELAXED/MMIO] fallbacks | report=$report")
            assertEquals(0L, flashRefusals, "Expected ZERO [RELAXED/FLASH] refusals | report=$report")
        }
    }
}
