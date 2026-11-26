package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncPerfProfilerDesktopTest {
    @Test
    fun launchConfigValidationAcceptsOptionalProfilerAndRejectsInvalidValues() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val base = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))

        // Default release config omits profiler -> valid
        validateLaunchConfiguration(base)

        // Boolean and bounded map forms are accepted
        validateLaunchConfiguration(base + ("profiler" to true))
        validateLaunchConfiguration(base + ("profiler" to false))
        validateLaunchConfiguration(base + ("profiler" to mapOf("enabled" to true, "intervalMs" to 25)))

        // Invalid keys or out-of-range intervals are rejected
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("profiler" to mapOf("enabled" to true, "unknown" to 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("profiler" to mapOf("enabled" to true, "intervalMs" to 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("profiler" to "yes"))
        }

        // realTimePacer is accepted in relaxed mode (boolean or map), refused in discovery/strict
        validateLaunchConfiguration(base + ("realTimePacer" to true))
        validateLaunchConfiguration(base + ("realTimePacer" to false))
        validateLaunchConfiguration(base + ("realTimePacer" to mapOf("enabled" to true, "targetRatio" to 1.0, "ticksPerRealSecond" to 54_000_000)))
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("realTimePacer" to mapOf("enabled" to true, "targetRatio" to 0.01)))
        }
        val discoveryBase = base + mapOf("executionMode" to "discovery", "instructionBudget" to 1000)
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(discoveryBase + ("realTimePacer" to true))
        }
    }

    @Test
    fun emulatorRuntimeWiresProfilerIntoControlServerAndClosesCleanly(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val fixtures = IntegrationTest()
        val home = fixtures.fixture()
        try {
            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer"),
            )
            EmulatorRuntime(home, config).use { runtime ->
                assertFalse(runtime.profiler.isRunning(), "Profiler must be OFF by default")
                assertFalse(runtime.telemetry.perfProfilingEnabled, "Hot-path profiling must be OFF by default")
                runtime.serve()
                val port = runtime.server!!.address.port
                val http = HttpClient.newHttpClient()

                val startResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/profiler/start?intervalMs=10"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, startResp.statusCode(), startResp.body())
                assertTrue(runtime.profiler.isRunning())
                assertTrue(runtime.telemetry.perfProfilingEnabled)

                Thread.sleep(35L)

                val textResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/profiler/report?format=text"))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, textResp.statusCode())
                assertTrue(textResp.body().contains("[FUJI-PROFILER SUMMARY]"))

                val stopResp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/profiler/stop"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, stopResp.statusCode())
                assertFalse(runtime.profiler.isRunning())
                assertFalse(runtime.telemetry.perfProfilingEnabled)
            }
            val aliveProfilers = Thread.getAllStackTraces().keys.filter { it.name == "fuji-profiler" && it.isAlive }
            assertTrue(aliveProfilers.isEmpty(), "Expected zero leaked fuji-profiler threads")
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    @Tag("perf")
    fun profileColdBootOobeIdleAndInteractiveUi(@TempDir tempRoot: Path) {
        assumeTrue(
            java.lang.Boolean.getBoolean("fuji.perf.runProfile"),
            "Opt-in perf benchmark; pass -Dfuji.perf.runProfile=true to execute",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRelease = listOf(
            repo.resolve("build/release-stage/linux-x86_64"),
            repo.resolve("build/release-stage/linux-x86_64/runtime"),
        ).firstOrNull { dir ->
            (Files.isRegularFile(dir.resolve("configs/emulator.yaml")) || Files.isRegularFile(dir.resolve("config/emulator.yaml"))) &&
                ((Files.isRegularFile(dir.resolve("flash/nor-flash.bin")) || Files.isRegularFile(dir.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(dir.resolve("runtime/firmware/GXUP0004.DAT")) || Files.isRegularFile(dir.resolve("firmware/GXUP0004.DAT")))
        }
        assumeTrue(
            stagedRelease != null,
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )
        val stagedRoot = checkNotNull(stagedRelease)
        fun resolveEmulatorYaml(dir: Path): Path =
            listOf(dir.resolve("configs/emulator.yaml"), dir.resolve("config/emulator.yaml"))
                .first { Files.isRegularFile(it) }

        fun copyDir(src: Path, dst: Path) {
            Files.walk(src).use { stream ->
                stream.forEach { p ->
                    val target = dst.resolve(src.relativize(p))
                    if (Files.isDirectory(p)) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.copy(p, target)
                    }
                }
            }
        }

        fun stepAttempts(rt: EmulatorRuntime, deltaAttempts: Long) {
            val start = rt.owner.submit { (rt.session.state()["attempts"] as Number).toLong() }.join()
            val target = start + deltaAttempts
            var cur = start
            while (cur < target) {
                cur = rt.owner.submit {
                    var i = 0
                    while (i < 4096) {
                        if (!rt.session.stepFast()) break
                        i++
                    }
                    rt.telemetry.attempts
                }.join()
                if (!rt.telemetry.running && rt.telemetry.status != "running") break
            }
        }

        fun stepWallSeconds(rt: EmulatorRuntime, seconds: Double) {
            val deadline = System.nanoTime() + (seconds * 1e9).toLong()
            while (System.nanoTime() < deadline) {
                val keep = rt.owner.submit {
                    var i = 0
                    var ok = true
                    while (ok && i < 2048) {
                        ok = rt.session.stepFast()
                        i++
                    }
                    ok
                }.join()
                if (!keep) break
            }
        }

        fun patchButton(rt: EmulatorRuntime, key: String, down: Boolean) {
            rt.owner.submit {
                val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                rt.host.patch("buttons", btn.revision, mapOf(key to down)).join()
            }.join()
        }

        // 0. Overhead & bit-exact determinism check (0 -> 20M cold boot: profiler OFF vs ON)
        val detOffHome = tempRoot.resolve("det-off")
        val detOnHome = tempRoot.resolve("det-on")
        copyDir(stagedRoot, detOffHome)
        copyDir(stagedRoot, detOnHome)
        // Pin external inputs in the instruction-for-instruction comparison.
        // The subsequent interactive profiling run uses the normal release settings.
        for (home in listOf(detOffHome, detOnHome)) {
            val rtc = home.resolve("configs/components/rtc/rtc.yaml")
            Formats.atomicYaml(rtc, Formats.read(rtc) + ("clockMode" to "calibrated-54mhz"))
        }
        val deterministicConfig = Formats.read(resolveEmulatorYaml(detOffHome)) + mapOf(
            "instructionBudget" to 20_000_000L,
            "smpHostThreads" to false,
        )
        var offMs = 0.0
        var onMs = 0.0
        lateinit var offState: Map<String, Any?>
        lateinit var onState: Map<String, Any?>
        EmulatorRuntime(detOffHome, deterministicConfig).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            val t0 = System.nanoTime()
            rt.owner.submit { while (rt.session.stepFast()) Unit; offState = rt.session.stateWithoutDiscovery() }.join()
            offMs = (System.nanoTime() - t0) / 1e6
        }
        EmulatorRuntime(detOnHome, deterministicConfig).use { rt ->
            rt.telemetry.markSessionStarted()
            rt.owner.submit { rt.session.initialize() }.join()
            rt.profiler.start(20L)
            val t0 = System.nanoTime()
            rt.owner.submit { while (rt.session.stepFast()) Unit; onState = rt.session.stateWithoutDiscovery() }.join()
            onMs = (System.nanoTime() - t0) / 1e6
            rt.profiler.stop()
        }
        assertEquals(offState["attempts"], onState["attempts"], "Profiler must not alter attempts")
        assertEquals(offState["mmioReads"], onState["mmioReads"], "Profiler must not alter mmioReads")
        assertEquals(offState["mmioWrites"], onState["mmioWrites"], "Profiler must not alter mmioWrites")
        assertEquals(offState["registers"], onState["registers"], "Profiler must not alter final registers")
        println(
            "PERF_OVERHEAD_20M offMs=${"%.1f".format(offMs)} offMips=${"%.3f".format(20_000.0 / offMs)} " +
                "onMs=${"%.1f".format(onMs)} onMips=${"%.3f".format(20_000.0 / onMs)} " +
                "overheadPct=${"%.2f".format(((onMs - offMs) / offMs) * 100.0)}%",
        )

        // 1-3. Full Cold Boot -> OOBE Idle -> Interactive UI profiling run
        val workHome = tempRoot.resolve("full-profile")
        copyDir(stagedRoot, workHome)
        val cfg = Formats.read(resolveEmulatorYaml(workHome))
        EmulatorRuntime(workHome, cfg).use { rt ->
            rt.telemetry.markSessionStarted()
            rt.owner.submit { rt.session.initialize() }.join()

            // Phase 1: Cold Boot to OOBE first frame (~560M attempts)
            rt.profiler.start(20L)
            stepAttempts(rt, 560_000_000L)
            val bootReport = rt.profiler.stop()
            val bootText = rt.profiler.formatTextReport()
            val bootMeta = rt.owner.submit {
                val timer = rt.session.componentState("soc-timer")
                val rtc = rt.session.componentState("rtc")
                val disp = rt.host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
                val st = rt.session.stateWithoutDiscovery()
                mapOf("displaySeq" to disp, "attempts" to st["attempts"], "idleClock" to st["idleClock"], "timer" to timer?.get("counters"), "rtc" to (rtc?.get("registers") as? Map<*, *>)?.get("seconds"))
            }.join()
            println("=== PHASE_1_COLD_BOOT_REPORT ===\n$bootText")
            println("PHASE_1_META=${Formats.jsonText(bootMeta, false)}")
            println("PHASE_1_JSON=${Formats.jsonText(bootReport, false)}")

            // Phase 2: OOBE Idle Steady State (3.0s wall clock)
            rt.profiler.start(20L)
            stepWallSeconds(rt, 3.0)
            val idleReport = rt.profiler.stop()
            val idleText = rt.profiler.formatTextReport()
            val idleMeta = rt.owner.submit {
                val timer = rt.session.componentState("soc-timer")
                val rtc = rt.session.componentState("rtc")
                val disp = rt.host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
                val st = rt.session.stateWithoutDiscovery()
                mapOf("displaySeq" to disp, "attempts" to st["attempts"], "idleClock" to st["idleClock"], "timer" to timer?.get("counters"), "rtc" to (rtc?.get("registers") as? Map<*, *>)?.get("seconds"))
            }.join()
            println("=== PHASE_2_OOBE_IDLE_REPORT ===\n$idleText")
            println("PHASE_2_META=${Formats.jsonText(idleMeta, false)}")
            println("PHASE_2_JSON=${Formats.jsonText(idleReport, false)}")

            // Phase 2b: OOBE Idle with RealTimePacer ON (3.0s wall clock -> target RTR ~= 1.00x)
            val pacer = com.github.nyabanban.fuji.emulator.runtime.RealTimePacer()
            pacer.reanchor(rt.telemetry.virtualTick)
            rt.profiler.start(20L)
            val pacedDeadline = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < pacedDeadline) {
                val keep = rt.owner.submit {
                    var i = 0
                    var ok = true
                    val sliceStart = System.nanoTime()
                    while (ok && i < 64) {
                        ok = rt.session.stepFast()
                        i++
                        if (System.nanoTime() - sliceStart >= 4_000_000L) break
                    }
                    ok
                }.join()
                if (!keep) break
                pacer.pace(rt.telemetry.virtualTick) { System.nanoTime() >= pacedDeadline }
            }
            val pacedReport = rt.profiler.stop()
            val pacedText = rt.profiler.formatTextReport()
            val pacedMeta = rt.owner.submit {
                val rtc = rt.session.componentState("rtc")
                val st = rt.session.stateWithoutDiscovery()
                mapOf("attempts" to st["attempts"], "idleClock" to st["idleClock"], "rtc" to (rtc?.get("registers") as? Map<*, *>)?.get("seconds"), "pacer" to pacer.stats())
            }.join()
            println("=== PHASE_2B_PACED_IDLE_REPORT ===\n$pacedText")
            println("PHASE_2B_META=${Formats.jsonText(pacedMeta, false)}")
            println("PHASE_2B_JSON=${Formats.jsonText(pacedReport, false)}")

            // Phase 3: Interactive UI (DOWN + MENU/OK)
            rt.profiler.start(20L)
            patchButton(rt, "down", true)
            stepAttempts(rt, 35_000_000L)
            patchButton(rt, "down", false)
            stepAttempts(rt, 25_000_000L)
            patchButton(rt, "menu", true)
            stepAttempts(rt, 35_000_000L)
            patchButton(rt, "menu", false)
            stepAttempts(rt, 35_000_000L)
            val uiReport = rt.profiler.stop()
            val uiText = rt.profiler.formatTextReport()
            val uiMeta = rt.owner.submit {
                val timer = rt.session.componentState("soc-timer")
                val rtc = rt.session.componentState("rtc")
                val disp = rt.host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
                val st = rt.session.stateWithoutDiscovery()
                mapOf("displaySeq" to disp, "attempts" to st["attempts"], "idleClock" to st["idleClock"], "timer" to timer?.get("counters"), "rtc" to (rtc?.get("registers") as? Map<*, *>)?.get("seconds"))
            }.join()
            println("=== PHASE_3_INTERACTIVE_UI_REPORT ===\n$uiText")
            println("PHASE_3_META=${Formats.jsonText(uiMeta, false)}")
            println("PHASE_3_JSON=${Formats.jsonText(uiReport, false)}")
        }
    }
}
