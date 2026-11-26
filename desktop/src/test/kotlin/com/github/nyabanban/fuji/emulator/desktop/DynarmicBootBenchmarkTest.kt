package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.cpu.CpuEngineKind
import com.github.nyabanban.fuji.emulator.desktop.e2e.E2eHarness
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates `cpuEngine` (`unicorn` | `dynarmic` | `auto`) configuration, CLI overrides,
 * `tools/release/examples/dynarmic-turbo.yaml`, and live GFX100S 2.13 cold-boot throughput
 * comparing Dynarmic ARMv7-A JIT against Unicorn Fast-Step v2.
 */
class DynarmicBootBenchmarkTest {

    private fun repoRoot(): Path = Path.of(System.getProperty("componentProjectRoot"))

    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dst.resolve(src.relativize(p))
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    val name = p.fileName.toString()
                    if (name.endsWith(".DAT") || name.endsWith(".jar") || name.endsWith(".so") || name.endsWith(".so.2")) {
                        try { Files.createLink(target, p) } catch (_: Throwable) { Files.copy(p, target) }
                    } else {
                        Files.copy(p, target)
                    }
                }
            }
        }
    }

    @Test
    fun cpuEngineConfigValidationAndDynarmicTurboExamplePass(@TempDir tempRoot: Path) {
        // Removed unicorn engine must be rejected in all modes
        assertThrows(IllegalArgumentException::class.java) {
            CpuEngineConfig.validate(mapOf("cpuEngine" to "unicorn"), "strict")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CpuEngineConfig.validate(mapOf("cpuEngine" to "unicorn"), RelaxedPeripheralFallback.MODE)
        }
        // Strict, discovery, and relaxed all validate dynarmic & auto
        assertEquals(CpuEngineKind.DYNARMIC, CpuEngineConfig.validate(mapOf("cpuEngine" to "dynarmic"), "strict"))
        assertEquals(CpuEngineKind.AUTO, CpuEngineConfig.validate(mapOf("cpuEngine" to "auto"), "discovery"))
        assertEquals(
            CpuEngineKind.DYNARMIC,
            CpuEngineConfig.validate(mapOf("cpuEngine" to "dynarmic"), RelaxedPeripheralFallback.MODE),
        )
        // Strict/discovery must still reject smpHostThreads=true
        assertThrows(IllegalArgumentException::class.java) {
            CpuEngineConfig.validate(mapOf("cpuEngine" to "dynarmic", "smpHostThreads" to true), "strict")
        }

        // CLI overrides wire --cpu-engine and --dynarmic-library
        val cliCfg = CustomFirmwareConfig.applyCliOverrides(
            mapOf("executionMode" to "relaxed"),
            arrayOf("--cpu-engine", "dynarmic", "--dynarmic-library", "runtime/native/libfuji_dynarmic.so"),
        )
        assertEquals("dynarmic", cliCfg[CpuEngineConfig.CPU_ENGINE_KEY])
        assertEquals("runtime/native/libfuji_dynarmic.so", cliCfg[CpuEngineConfig.DYNARMIC_LIBRARY_KEY])

        // Example YAML validates cleanly against launch configuration envelope
        val examplePath = repoRoot().resolve("tools/release/examples/dynarmic-turbo.yaml")
        assertTrue(Files.isRegularFile(examplePath), "Missing dynarmic-turbo.yaml example")
        val exampleCfg = Formats.read(examplePath)
        validateLaunchConfiguration(exampleCfg)
        assertEquals("dynarmic", exampleCfg[CpuEngineConfig.CPU_ENGINE_KEY])
        assertEquals(RelaxedPeripheralFallback.MODE, exampleCfg["executionMode"])
    }

    private data class BootResult(
        val backend: String,
        val status: String,
        val attempts: Long,
        val virtualTick: Long,
        val mmioReads: Long,
        val mmioWrites: Long,
        val fastTimerHits: Long,
        val fastRoHits: Long,
        val stages: String,
        val publishedFrames: Long,
        val initMs: Double,
        val guestMs: Double,
        val pc: Long,
    ) {
        val mips: Double get() = if (guestMs > 0.0) attempts / (guestMs * 1e3) else 0.0
    }

    private fun runRelaxedBoot(
        srcHome: Path,
        workHome: Path,
        cfgRelativePath: String,
        unicornLib: Path,
        dynarmicLib: Path?,
        engineName: String,
        budget: Long,
        stepSize: Long,
    ): BootResult {
        copyDirectory(srcHome, workHome)
        val cfgPath = workHome.resolve(cfgRelativePath)
        val cfg = LinkedHashMap<String, Any>(Formats.read(cfgPath))
        cfg["executionMode"] = RelaxedPeripheralFallback.MODE
        cfg["nativeLibrary"] = unicornLib.toAbsolutePath().toString()
        cfg["cpuEngine"] = engineName
        if (dynarmicLib != null) {
            cfg["dynarmicLibrary"] = dynarmicLib.toAbsolutePath().toString()
        }
        cfg["instructionBudget"] = budget

        val t0 = System.nanoTime()
        var initMs = 0.0
        var guestMs = 0.0
        lateinit var state: Map<String, Any?>
        var stages = ""
        var frames = 0L
        var vtick = 0L
        var lastPc = 0L
        var fastTimerHits = 0L
        var fastRoHits = 0L
        EmulatorRuntime(workHome, cfg).use { runtime ->
            runtime.owner.submit { runtime.session.initialize() }.join()
            val tInit = System.nanoTime()
            initMs = (tInit - t0) / 1e6
            runtime.owner.submit {
                var more = true
                while (more) {
                    more = runtime.session.stepAtMost(stepSize)
                }
                state = runtime.session.stateWithoutDiscovery()
                stages = FirmwareStage.highestReached(runtime.session.telemetry.stageMask)?.label ?: "NONE"
                frames = runtime.session.telemetry.publishedFrames
                vtick = runtime.session.telemetry.virtualTick
                lastPc = runtime.session.telemetry.currentPc
                runtime.session.dynarmicFastMmioStats()?.let {
                    fastTimerHits = it.timerFastHits
                    fastRoHits = it.roMmioFastHits
                }
            }.join()
            guestMs = (System.nanoTime() - tInit) / 1e6
        }
        return BootResult(
            backend = state["backend"].toString(),
            status = state["status"].toString(),
            attempts = (state["attempts"] as Number).toLong(),
            virtualTick = vtick,
            mmioReads = (state["mmioReads"] as Number).toLong(),
            mmioWrites = (state["mmioWrites"] as Number).toLong(),
            fastTimerHits = fastTimerHits,
            fastRoHits = fastRoHits,
            stages = stages,
            publishedFrames = frames,
            initMs = initMs,
            guestMs = guestMs,
            pc = lastPc,
        )
    }

    @Test
    fun coldBootDynarmicVsUnicornThroughput(@TempDir tempRoot: Path) {
        val repo = repoRoot()
        val dynLib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(dynLib != null && Files.isRegularFile(dynLib), "Skipping live Dynarmic boot benchmark: libfuji_dynarmic not built")

        val unicornLib = listOfNotNull(
            repo.resolve(".tools/unicorn-arm-fast-v2/build/libunicorn.so.2").takeIf { Files.isRegularFile(it) },
            System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { Files.isRegularFile(it) },
            System.getenv("FUJI_UNICORN_LIBRARY")?.takeIf { it.isNotBlank() }?.let(Path::of)?.takeIf { Files.isRegularFile(it) },
        ).firstOrNull()
        val stagedReleaseRoot = repo.resolve("build/release-stage/linux-x86_64")
        val stagedConfig = stagedReleaseRoot.resolve("configs/emulator.yaml")
        assumeTrue(
            unicornLib != null && Files.isRegularFile(stagedConfig) && ((Files.isRegularFile(stagedReleaseRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedReleaseRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedReleaseRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Skipping live Dynarmic boot benchmark: requires :stageReleaseLinux output and Unicorn fast-v2 library",
        )

        val budget = 50_000_000L
        val unicornRes = runRelaxedBoot(
            srcHome = stagedReleaseRoot,
            workHome = tempRoot.resolve("boot-unicorn-50m"),
            cfgRelativePath = "configs/emulator.yaml",
            unicornLib = checkNotNull(unicornLib),
            dynarmicLib = null,
            engineName = "unicorn",
            budget = budget,
            stepSize = 65536L,
        )
        val dynRes = runRelaxedBoot(
            srcHome = stagedReleaseRoot,
            workHome = tempRoot.resolve("boot-dynarmic-50m"),
            cfgRelativePath = "configs/emulator.yaml",
            unicornLib = checkNotNull(unicornLib),
            dynarmicLib = dynLib,
            engineName = "dynarmic",
            budget = budget,
            stepSize = 65536L,
        )

        val speedup = if (dynRes.guestMs > 0.0) unicornRes.guestMs / dynRes.guestMs else 0.0
        println(
            "DYNARMIC_BOOT_BENCH_50M " +
                "unicornBackend=${unicornRes.backend} unicornStatus=${unicornRes.status} unicornAttempts=${unicornRes.attempts} " +
                "unicornInitMs=${"%.1f".format(unicornRes.initMs)} unicornGuestMs=${"%.1f".format(unicornRes.guestMs)} " +
                "unicornMips=${"%.3f".format(unicornRes.mips)} unicornMmio=${unicornRes.mmioReads}/${unicornRes.mmioWrites} " +
                "unicornStage=${unicornRes.stages} | " +
                "dynBackend=${dynRes.backend} dynStatus=${dynRes.status} dynAttempts=${dynRes.attempts} " +
                "dynInitMs=${"%.1f".format(dynRes.initMs)} dynGuestMs=${"%.1f".format(dynRes.guestMs)} " +
                "dynMips=${"%.3f".format(dynRes.mips)} dynMmio=${dynRes.mmioReads}/${dynRes.mmioWrites} " +
                "dynFastMmio=timer:${dynRes.fastTimerHits}/ro:${dynRes.fastRoHits} " +
                "dynStage=${dynRes.stages} dynPc=0x${dynRes.pc.toString(16)} | " +
                "speedup=${"%.2f".format(speedup)}x",
        )

        assertEquals("dynarmic-a32-ffm-jvm25", dynRes.backend)
        assertEquals("instruction-limit", dynRes.status, "Dynarmic stopped unexpectedly: $dynRes")
        assertTrue(dynRes.attempts >= budget, "Dynarmic did not reach 50M attempts: ${dynRes.attempts}")
        assertTrue(dynRes.mmioReads > 0L && dynRes.mmioWrites > 0L, "Expected real MMIO traffic on Dynarmic")
        assertNotEquals("NONE", dynRes.stages)
    }

    @Test
    fun fullColdBootToOobeFirstFrameOnDynarmic(@TempDir tempRoot: Path) {
        val repo = repoRoot()
        val dynLib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(dynLib != null && Files.isRegularFile(dynLib), "Skipping full Dynarmic cold-boot test: libfuji_dynarmic not built")

        val stagedReleaseRoot = repo.resolve("build/release-stage/linux-x86_64")
        val stagedConfig = stagedReleaseRoot.resolve("configs/emulator.yaml")
        assumeTrue(
            Files.isRegularFile(stagedConfig) && ((Files.isRegularFile(stagedReleaseRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedReleaseRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedReleaseRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Skipping full Dynarmic cold-boot test: requires :stageReleaseLinux output",
        )

        val workHome = tempRoot.resolve("dyn-full-boot")
        copyDirectory(stagedReleaseRoot, workHome)
        val cfg = LinkedHashMap<String, Any>(Formats.read(workHome.resolve("configs/emulator.yaml")))
        cfg["executionMode"] = RelaxedPeripheralFallback.MODE
        cfg["cpuEngine"] = "dynarmic"
        cfg["dynarmicLibrary"] = checkNotNull(dynLib).toAbsolutePath().toString()
        cfg["instructionBudget"] = "unlimited"

        val maxAttempts = 700_000_000L
        val targetFrames = 3L
        val t0 = System.nanoTime()
        var initMs = 0.0
        var guestMs = 0.0
        var lastReportedAttempts = 0L
        lateinit var state: Map<String, Any?>
        var stages = ""
        var frames = 0L
        var vtick = 0L
        var totalAttempts = 0L
        var fastTimerHits = 0L
        var fastRoHits = 0L
        EmulatorRuntime(workHome, cfg).use { runtime ->
            runtime.owner.submit { runtime.session.initialize() }.join()
            val tInit = System.nanoTime()
            initMs = (tInit - t0) / 1e6
            runtime.owner.submit {
                var more = true
                while (more && runtime.session.telemetry.attempts < maxAttempts && runtime.session.telemetry.publishedFrames < targetFrames) {
                    more = runtime.session.stepAtMost(65536L)
                    val cur = runtime.session.telemetry.attempts
                    if (cur - lastReportedAttempts >= 50_000_000L) {
                        lastReportedAttempts = cur
                        val elapsedSec = (System.nanoTime() - tInit) / 1e9
                        val curStage = FirmwareStage.highestReached(runtime.session.telemetry.stageMask)?.label ?: "NONE"
                        println(
                            "DYN_FULL_PROGRESS attempts=$cur vtick=${runtime.session.telemetry.virtualTick} " +
                                "wallSec=${"%.2f".format(elapsedSec)} mips=${"%.2f".format(cur / (elapsedSec * 1e6))} " +
                                "frames=${runtime.session.telemetry.publishedFrames} stage=$curStage " +
                                "core=${runtime.session.telemetry.currentCore} pc=0x${runtime.session.telemetry.currentPc.toString(16)}",
                        )
                    }
                }
                state = runtime.session.stateWithoutDiscovery()
                stages = FirmwareStage.highestReached(runtime.session.telemetry.stageMask)?.label ?: "NONE"
                frames = runtime.session.telemetry.publishedFrames
                vtick = runtime.session.telemetry.virtualTick
                totalAttempts = runtime.session.telemetry.attempts
                runtime.session.dynarmicFastMmioStats()?.let {
                    fastTimerHits = it.timerFastHits
                    fastRoHits = it.roMmioFastHits
                }
            }.join()
            guestMs = (System.nanoTime() - tInit) / 1e6
        }

        val mips = if (guestMs > 0.0) totalAttempts / (guestMs * 1e3) else 0.0
        println(
            "DYN_FULL_BOOT_RESULT backend=${state["backend"]} status=${state["status"]} " +
                "attempts=$totalAttempts vtick=$vtick initMs=${"%.1f".format(initMs)} guestMs=${"%.1f".format(guestMs)} " +
                "mips=${"%.3f".format(mips)} mmio=${state["mmioReads"]}/${state["mmioWrites"]} " +
                "fastMmio=timer:$fastTimerHits/ro:$fastRoHits " +
                "irqs=${state["irqEntryCount"]} stage=$stages frames=$frames " +
                "regs=${state["registers"]} frontier=${state["frontier"]}",
        )
        @Suppress("UNCHECKED_CAST")
        val cl = state["cluster"] as? Map<String, Any?>
        if (cl != null) {
            @Suppress("UNCHECKED_CAST")
            val cores = cl["cores"] as? List<Map<String, Any?>>
            cores?.forEach { c ->
                val regs = c["registers"] as? Map<*, *>
                val cp15 = c["cp15"] as? Map<*, *>
                println(
                    "  DYN_CORE[${c["core"]}] attempts=${c["attempts"]} waiting=${c["waiting"]} " +
                        "pc=0x${((regs?.get("pc") as? Number)?.toLong() ?: 0L).toString(16)} " +
                        "cpsr=0x${((regs?.get("cpsr") as? Number)?.toLong() ?: 0L).toString(16)} " +
                        "sctlr=0x${((cp15?.get("sctlr") as? Number)?.toLong() ?: 0L).toString(16)}",
                )
            }
        }
        assertEquals("dynarmic-a32-ffm-jvm25", state["backend"])
        assertEquals("running", state["status"], "Dynarmic full boot stopped prematurely: frontier=${state["frontier"]}")
        assertTrue(frames >= 1L, "Expected Dynarmic full cold boot to reach STAGE_5_FIRST_FRAME (>=1 LCD frame), got frames=$frames stage=$stages")
    }
}
