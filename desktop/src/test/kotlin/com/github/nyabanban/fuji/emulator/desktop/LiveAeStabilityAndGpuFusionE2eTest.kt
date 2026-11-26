package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Env-gated (`FUJI_RUN_AE_STABILITY_LIVE=1`) live GFX100S 2.13 AE stability & LiveView GPU-ISP
 * verification across `scene://qr-ball`, `scene://depth-studio`, `scene://mona-lisa`, and
 * `scene://milk-frog` in P / Auto-ISO mode. Confirms zero sudden bright flash spikes, zero AE
 * hunting oscillation after settle, and smooth LiveView FPS.
 */
class LiveAeStabilityAndGpuFusionE2eTest {
    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            for (src in stream) {
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

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.getInt(0)
    }.join()

    private fun patchComp(rt: EmulatorRuntime, id: String, patch: Map<String, Any>) {
        rt.owner.submit {
            rt.host.patchLatestDirect(id, patch, persistToDisk = false)
        }.join()
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long, settleTicks: Long) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdTicks)
        patchComp(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleTicks)
    }

    private data class FrameSample(
        val stepIdx: Int,
        val meanY: Double,
        val aeMeanCode: Int,
        val iso: Int,
        val durationMs: Double,
        val displaySeq: Long,
        val gpuFusedFrames: Long,
        val aeUpdated: Long,
        val histUpdated: Long,
    )

    private fun sampleFrame(rt: EmulatorRuntime, stepIdx: Int): FrameSample = rt.owner.submit {
        val disp = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = disp.width
        val h = disp.height
        val px = IntArray(w * h)
        disp.copyArgb(px)
        val x0 = w / 4
        val x1 = (w * 3) / 4
        val y0 = h / 4
        val y1 = (h * 3) / 4
        var sumY = 0.0
        var cnt = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val p = px[row + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                sumY += 0.299 * r + 0.587 * g + 0.114 * b
                cnt++
            }
        }
        val isp = rt.session.componentState("soc-isp").orEmpty()
        val dma = isp["liveViewDma"] as? Map<*, *>
        val stats = isp["stats3a"] as? Map<*, *>
        val expPort = rt.host.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java)
        val effExp = Imx461ExposurePort.computeEffectiveExposure(expPort)
        FrameSample(
            stepIdx = stepIdx,
            meanY = if (cnt > 0) sumY / cnt else 0.0,
            aeMeanCode = (stats?.get("meanAeCode14") as? Number)?.toInt() ?: 0,
            iso = effExp.iso,
            durationMs = effExp.durationNanos / 1_000.0,
            displaySeq = disp.sequence,
            gpuFusedFrames = (dma?.get("gpuFusedIspFrames") as? Number)?.toLong() ?: 0L,
            aeUpdated = (stats?.get("aeFramesUpdated") as? Number)?.toLong() ?: 0L,
            histUpdated = (stats?.get("histFramesUpdated") as? Number)?.toLong() ?: 0L,
        )
    }.join()

    @Test
    fun liveAeStabilityAcrossAllFourScenes(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_AE_STABILITY_LIVE") == "1",
            "Set FUJI_RUN_AE_STABILITY_LIVE=1 to run live 4-scene AE stability verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = listOfNotNull(
            System.getenv("FUJI_STAGED_RELEASE_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
            repo.resolve("build/release-stage/linux-x86_64"),
        ).firstOrNull { Files.isDirectory(it) && Files.isRegularFile(it.resolve("configs/emulator.yaml")) }
        assumeTrue(stagedRoot != null, "Requires staged linux-x86_64 release directory")

        val workRoot = tempRoot.resolve("ae-stability-root")
        copyDirectory(stagedRoot!!, workRoot)
        val compDir = workRoot.resolve("runtime/compoments")
        require(Files.isDirectory(compDir)) { "Expected staged component directory at $compDir" }
        for (mod in listOf("cmos", "lens", "soc-isp", "soc-isp-cmd", "mount")) {
            val libsDir = repo.resolve("components/$mod/build/libs")
            val freshJar = Files.list(libsDir).use { s ->
                s.filter { it.fileName.toString().startsWith("$mod-") && it.fileName.toString().endsWith(".jar") }
                    .findFirst()
                    .orElse(null)
            }
            require(freshJar != null && Files.isRegularFile(freshJar)) { "Expected built jar for $mod in $libsDir" }
            Files.copy(freshJar, compDir.resolve(freshJar.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
        val nativeDir = workRoot.resolve("runtime/native")
        Files.createDirectories(nativeDir)
        for ((relSrc, dstName) in listOf(
            ".tools/vulkan-optics/build-linux/libfuji_vulkan_optics.so" to "libfuji_vulkan_optics.so",
            ".tools/isp-simd/build-linux/libfuji_isp_simd.so" to "libfuji_isp_simd.so",
        )) {
            val freshNative = repo.resolve(relSrc)
            if (Files.isRegularFile(freshNative)) {
                Files.copy(freshNative, nativeDir.resolve(dstName), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
            "realTimePacer" to mapOf("enabled" to false),
        )

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            for (i in 0 until 70) {
                stepBy(rt, 10_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected OOBE 0x0421")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (iter in 0 until 60) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100")

            val scenes = listOf(
                "scene://qr-ball",
                "scene://depth-studio",
                "scene://mona-lisa",
                "scene://milk-frog",
            )
            for (sceneUri in scenes) {
                patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to sceneUri, "loop" to true))
                // Settle window: 1.2s virtual time so firmware AE walks to target exposure
                stepBy(rt, 64_800_000L)

                val wallStart = System.nanoTime()
                val samples = ArrayList<FrameSample>(16)
                for (k in 0 until 15) {
                    stepBy(rt, 3_600_000L) // ~66.7ms virtual (~4 LV frames per sample)
                    samples.add(sampleFrame(rt, k))
                }
                val wallSec = ((System.nanoTime() - wallStart).coerceAtLeast(1L)) / 1e9
                val dispDelta = samples.last().displaySeq - samples.first().displaySeq
                val fps = dispDelta / wallSec
                val lumas = samples.map { it.meanY }
                val minY = lumas.min()
                val maxY = lumas.max()
                val spanY = maxY - minY
                var maxStepJump = 0.0
                for (i in 1 until lumas.size) {
                    maxStepJump = maxOf(maxStepJump, abs(lumas[i] - lumas[i - 1]))
                }
                val aeTotal = samples.last().aeUpdated
                val histTotal = samples.last().histUpdated
                val aeDelta = aeTotal - samples.first().aeUpdated
                val histDelta = histTotal - samples.first().histUpdated
                val fusedDelta = samples.last().gpuFusedFrames - samples.first().gpuFusedFrames
                println(
                    "[AE-STABILITY] scene=$sceneUri minY=${"%.1f".format(minY)} maxY=${"%.1f".format(maxY)} " +
                        "spanY=${"%.1f".format(spanY)} maxJump=${"%.1f".format(maxStepJump)} " +
                        "iso=${samples.last().iso} durMs=${"%.2f".format(samples.last().durationMs)} " +
                        "aeCode=${samples.last().aeMeanCode} LV_FPS=${"%.1f".format(fps)} " +
                        "aeTotal=$aeTotal(+${aeDelta}) histTotal=$histTotal(+${histDelta}) gpuFused=+$fusedDelta",
                )
                assertTrue(maxY < 235.0, "$sceneUri flashed super bright (maxY=$maxY)")
                assertTrue(minY > 15.0, "$sceneUri went unexpectedly dark/black (minY=$minY)")
                assertTrue(maxStepJump < 35.0, "$sceneUri had sudden brightness spike (maxJump=$maxStepJump, lumas=$lumas)")
                assertTrue(spanY < 45.0, "$sceneUri exhibited AE hunting oscillation (spanY=$spanY, lumas=$lumas)")
                assertTrue(aeTotal > 0L && histTotal > 0L, "$sceneUri must populate AE & Histogram stats")
            }
        }
    }
}
