package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.ceil
import kotlin.test.assertEquals

/**
 * Env-gated (`FUJI_RUN_AFON_PERF_BENCH=1`) live GFX100S 2.13 performance profiler & benchmark
 * measuring exact wall-time, RTR, LiveView FPS, step-stall p50/p95/max ms, and per-subsystem
 * hotspot breakdown while holding `AF-ON` (`buttons.afOn = true`) across `scene://qr-ball`
 * (Face/Eye AF OFF, AF-S & AF-C) and `scene://mona-lisa` (Face/Eye AF ON, AF-C).
 */
class AfOnHoldPerformanceBenchmarkTest {

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

    private data class CounterSnap(
        val wallNs: Long,
        val virtualTick: Long,
        val displaySeq: Long,
        val cmosExposed: Long,
        val lensFiltered: Long,
        val ispPumps: Long,
        val ispCacheHits: Long,
        val ispCacheMisses: Long,
        val ispGpuFused: Long,
        val ispAeUpdates: Long,
        val ispHistUpdates: Long,
        val pdafCompletions: Long,
        val facePass1: Long,
        val facePass2: Long,
    )

    private fun captureSnap(rt: EmulatorRuntime): CounterSnap = rt.owner.submit {
        val disp = rt.host.ports.find("display.main", DisplayPort::class.java)
        val cmos = rt.session.componentState("cmos").orEmpty()
        val lens = rt.session.componentState("lens").orEmpty()
        val isp = rt.session.componentState("soc-isp").orEmpty()
        val dma = isp["liveViewDma"] as? Map<*, *>
        val stats = isp["statsSram"] as? Map<*, *>
        val pdaf = isp["pdaf"] as? Map<*, *>
        val disc = rt.session.discoveryState()
        @Suppress("UNCHECKED_CAST")
        val regions = disc["regions"] as? List<Map<String, Any?>>
        val faceRec = regions?.firstOrNull { it["id"] == "diagnostic.face.fff7c000" }?.get("imageFace7c000") as? Map<*, *>
        CounterSnap(
            wallNs = System.nanoTime(),
            virtualTick = rt.telemetry.virtualTick,
            displaySeq = disp?.sequence ?: 0L,
            cmosExposed = (cmos["framesExposed"] as? Number)?.toLong() ?: 0L,
            lensFiltered = (lens["framesFiltered"] as? Number)?.toLong() ?: 0L,
            ispPumps = (dma?.get("framesWritten") as? Number)?.toLong() ?: 0L,
            ispCacheHits = (dma?.get("cacheHits") as? Number)?.toLong() ?: 0L,
            ispCacheMisses = (dma?.get("cacheMisses") as? Number)?.toLong() ?: 0L,
            ispGpuFused = (dma?.get("gpuFusedIspFrames") as? Number)?.toLong() ?: 0L,
            ispAeUpdates = (stats?.get("aeFramesUpdated") as? Number)?.toLong() ?: 0L,
            ispHistUpdates = (stats?.get("histFramesUpdated") as? Number)?.toLong() ?: 0L,
            pdafCompletions = (pdaf?.get("completionCount") as? Number)?.toLong() ?: 0L,
            facePass1 = (faceRec?.get("pass1Count") as? Number)?.toLong() ?: 0L,
            facePass2 = (faceRec?.get("pass2Count") as? Number)?.toLong() ?: 0L,
        )
    }.join()

    private fun profilePhase(
        rt: EmulatorRuntime,
        label: String,
        targetVirtualTicks: Long = 54_000_000L, // 1.0 virtual second @ 54 MHz
    ) {
        val stepStallsMs = ArrayList<Double>(4096)
        rt.profiler.start(intervalMs = 5L)
        val start = captureSnap(rt)
        val endTick = start.virtualTick + targetVirtualTicks
        var batches = 0
        while (rt.telemetry.virtualTick < endTick && batches < 200_000) {
            val cont = rt.owner.submit {
                var ok = true
                var i = 0
                while (ok && i < 16 && rt.telemetry.virtualTick < endTick && !rt.session.isSocPoweredOff) {
                    val t0 = System.nanoTime()
                    ok = rt.session.stepFast()
                    val dtMs = (System.nanoTime() - t0) / 1_000_000.0
                    stepStallsMs.add(dtMs)
                    i++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                ok
            }.join()
            if (!cont || rt.session.isSocPoweredOff) break
            batches++
        }
        val end = captureSnap(rt)
        rt.profiler.stop()
        val textReport = rt.profiler.formatTextReport()

        val wallSec = ((end.wallNs - start.wallNs).coerceAtLeast(1L)) / 1_000_000_000.0
        val vSec = (end.virtualTick - start.virtualTick) / 54_000_000.0
        val rtr = vSec / wallSec
        val dispDelta = end.displaySeq - start.displaySeq
        val fps = dispDelta / wallSec

        stepStallsMs.sort()
        fun pct(p: Double): Double {
            if (stepStallsMs.isEmpty()) return 0.0
            val idx = (ceil(stepStallsMs.size * p).toInt() - 1).coerceIn(0, stepStallsMs.lastIndex)
            return stepStallsMs[idx]
        }
        val stallsOver10ms = stepStallsMs.count { it >= 10.0 }
        val stallsOver25ms = stepStallsMs.count { it >= 25.0 }

        println("================================================================")
        println("[AFON-BENCH] PHASE=$label")
        println(
            "  wall=${"%.3f".format(wallSec)}s vSec=${"%.3f".format(vSec)}s RTR=${"%.3f".format(rtr)}x " +
                "LV_FPS=${"%.2f".format(fps)} (dispSeq=+$dispDelta) " +
                "stepMs(p50=${"%.3f".format(pct(0.50))}, p95=${"%.3f".format(pct(0.95))}, p99=${"%.3f".format(pct(0.99))}, max=${"%.2f".format(pct(1.0))}, >=10ms=$stallsOver10ms, >=25ms=$stallsOver25ms, n=${stepStallsMs.size})",
        )
        println(
            "  counts: cmosExposed=+${end.cmosExposed - start.cmosExposed} lensFiltered=+${end.lensFiltered - start.lensFiltered} " +
                "pdaf=+${end.pdafCompletions - start.pdafCompletions} face(p1=+${end.facePass1 - start.facePass1}, p2=+${end.facePass2 - start.facePass2})",
        )
        println(textReport)
        val diagSummary = rt.owner.submit {
            val isp = rt.session.componentState("soc-isp").orEmpty()
            val lens = rt.session.componentState("lens").orEmpty()
            val cmos = rt.session.componentState("cmos").orEmpty()
            "lens(diag=${lens["opticsBackendDiagnostics"]}, focusM=${lens["focusDistanceM"]}) " +
                "cmos(asyncOptics=${cmos["asyncOptics"]}) " +
                "isp(liveViewDma=${isp["liveViewDma"]})"
        }.join()
        println("  diag=$diagSummary")
        println("================================================================")
    }

    @Test
    fun benchmarkAfOnHoldAcrossQrBallAndMonaLisa(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_AFON_PERF_BENCH") == "1",
            "Set FUJI_RUN_AFON_PERF_BENCH=1 to run AF-ON hold live profiler benchmark",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = listOfNotNull(
            System.getenv("FUJI_STAGED_RELEASE_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) },
            repo.resolve("build/release-stage/linux-x86_64"),
        ).firstOrNull { Files.isDirectory(it) && Files.isRegularFile(it.resolve("configs/emulator.yaml")) }
        assumeTrue(stagedRoot != null, "Requires staged linux-x86_64 release directory")

        val workRoot = tempRoot.resolve("afon-bench-root")
        copyDirectory(stagedRoot!!, workRoot)
        // Always overlay the current worktree's freshly compiled component JARs and native bridges so
        // benchmarks measure this branch's exact code rather than stale pre-staged artifacts.
        val compDir = workRoot.resolve("runtime/compoments")
        require(Files.isDirectory(compDir)) { "Expected staged component directory at $compDir" }
        for (mod in listOf("cmos", "lens", "soc-isp")) {
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

            // Boot through OOBE -> STILL HUD 0x0100
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
            stepBy(rt, 30_000_000L)

            // Phase 1: IDLE LiveView on scene://qr-ball (Face AF OFF)
            profilePhase(rt, "1_QR_BALL_IDLE_FACE_OFF", targetVirtualTicks = 54_000_000L)

            // Phase 2: AF-S hold AF-ON on scene://qr-ball (defocus to 6.0m so real focus sweep runs)
            patchComp(rt, "lens", mapOf("focusDistanceM" to 6.0))
            stepBy(rt, 10_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to true))
            profilePhase(rt, "2_QR_BALL_AFS_HOLD_AFON_FACE_OFF", targetVirtualTicks = 54_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to false))
            stepBy(rt, 15_000_000L)

            // Phase 3: AF-C continuous hold AF-ON on scene://qr-ball (input7=24640 -> AF-C)
            patchComp(rt, "soc-gpio", mapOf("input7" to 24640L))
            patchComp(rt, "lens", mapOf("focusDistanceM" to 6.0))
            stepBy(rt, 15_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to true))
            profilePhase(rt, "3_QR_BALL_AFC_HOLD_AFON_FACE_OFF", targetVirtualTicks = 54_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to false))
            stepBy(rt, 15_000_000L)

            // Phase 4: Switch to animated scene://mona-lisa + enable Face/Eye AF (Fn2) + AF-C hold AF-ON
            patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://mona-lisa?period=14.0", "loop" to true))
            patchComp(rt, "soc-adc", mapOf("fn2Button" to true))
            stepBy(rt, 4_500_000L)
            patchComp(rt, "soc-adc", mapOf("fn2Button" to false))
            stepBy(rt, 45_000_000L)
            patchComp(rt, "lens", mapOf("focusDistanceM" to 5.0))
            stepBy(rt, 10_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to true))
            profilePhase(rt, "4_MONA_LISA_AFC_HOLD_AFON_FACE_ON", targetVirtualTicks = 54_000_000L)
            patchComp(rt, "buttons", mapOf("afOn" to false))
        }
    }
}
