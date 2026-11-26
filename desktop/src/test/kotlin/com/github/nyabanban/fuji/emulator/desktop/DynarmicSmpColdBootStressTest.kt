package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Opt-in consecutive `smpHostThreads=true` cold-boot stress harness (W14 SMP early-boot DABT).
 *
 * Enabled only when `FUJI_SMP_STRESS_RUNS` is set. Each run cold-boots a pristine copy of the staged
 * Linux release with 4 host-thread SMP cores and stops at `STAGE_6_INTERACTIVE_UI` (or at
 * `FUJI_SMP_STRESS_MAX_VTICK` when set). Guest data aborts are counted from the relaxed DABT log line
 * emitted by [FirmwareSession]; the test fails when any run aborts or misses the target.
 */
class DynarmicSmpColdBootStressTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val target = dst.resolve(src.relativize(p).toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    @Test
    fun consecutiveSmpHostThreadsColdBoots(@TempDir tempRoot: Path) {
        val runs = System.getenv("FUJI_SMP_STRESS_RUNS")?.toIntOrNull()
        assumeTrue(runs != null && runs > 0, "Set FUJI_SMP_STRESS_RUNS to enable the SMP cold-boot stress harness")
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stageRoot = System.getenv("FUJI_SMP_STRESS_STAGE_ROOT")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        val dynLib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(dynLib != null && Files.isRegularFile(dynLib), "libfuji_dynarmic not built")
        assumeTrue(Files.isRegularFile(stageRoot.resolve("configs/emulator.yaml")), "staged release missing: $stageRoot")
        val maxVtick = System.getenv("FUJI_SMP_STRESS_MAX_VTICK")?.toLongOrNull()
        val maxAttempts = System.getenv("FUJI_SMP_STRESS_MAX_ATTEMPTS")?.toLongOrNull() ?: 4_000_000_000L

        val dabtLog = DabtCounter()
        var failures = 0
        for (run in 1..checkNotNull(runs)) {
            val workHome = tempRoot.resolve("run-$run")
            copyDirectory(stageRoot, workHome)
            val cfg = LinkedHashMap<String, Any>(Formats.read(workHome.resolve("configs/emulator.yaml")))
            cfg["executionMode"] = RelaxedPeripheralFallback.MODE
            cfg["cpuEngine"] = "dynarmic"
            cfg["smpHostThreads"] = true
            cfg["dynarmicLibrary"] = checkNotNull(dynLib).toAbsolutePath().toString()
            cfg["instructionBudget"] = "unlimited"
            val before = dabtLog.count()
            val t0 = System.nanoTime()
            var stage = "NONE"
            var vtick = 0L
            var smp = false
            EmulatorRuntime(workHome, cfg).use { runtime ->
                runtime.owner.submit { runtime.session.initialize() }.join()
                runtime.owner.submit {
                    val tel = runtime.session.telemetry
                    var more = true
                    while (more && tel.attempts < maxAttempts &&
                        (tel.stageMask and FirmwareStage.STAGE_6_INTERACTIVE_UI.bit) == 0 &&
                        (maxVtick == null || tel.virtualTick < maxVtick)
                    ) {
                        more = runtime.session.stepAtMost(65536L)
                    }
                    stage = FirmwareStage.highestReached(tel.stageMask)?.label ?: "NONE"
                    vtick = tel.virtualTick
                    smp = tel.smpHostThreadsEnabled
                    System.getenv("FUJI_SMP_STRESS_DUMP")?.takeIf { run == 1 && it.contains(':') }?.split(',')?.forEach { spec ->
                        val parts = spec.split(':')
                        val (a, n) = parts.take(2).map { it.removePrefix("0x").toLong(16) }
                        val bytes = if (parts.size > 2) {
                            val out = java.io.ByteArrayOutputStream()
                            var off = 0L
                            while (off < n) {
                                val len = minOf(0x1000L, n - off).toInt()
                                out.write(runCatching { runtime.session.memory(a + off, len) }.getOrElse { ByteArray(len) })
                                off += len
                            }
                            out.toByteArray()
                        } else {
                            runtime.session.memory(a, n.toInt())
                        }
                        if (parts.size > 2) {
                            Files.write(Path.of(parts[2]), bytes)
                        } else {
                            println("SMP_STRESS_DUMP 0x${a.toString(16)} " + bytes.joinToString("") { "%02x".format(it) })
                        }
                    }
                }.join()
            }
            val dabts = dabtLog.count() - before
            val reached = stage == FirmwareStage.STAGE_6_INTERACTIVE_UI.label || (maxVtick != null && vtick >= maxVtick)
            if (dabts != 0 || !reached || !smp) failures++
            println(
                "SMP_STRESS run=$run/$runs smp=$smp stage=$stage vtick=$vtick dabts=$dabts " +
                    "wallSec=${"%.1f".format((System.nanoTime() - t0) / 1e9)} ok=${dabts == 0 && reached && smp}",
            )
            workHome.toFile().deleteRecursively()
        }
        dabtLog.close()
        println("SMP_STRESS_SUMMARY runs=$runs failures=$failures")
        assertEquals(0, failures, "SMP cold-boot stress failures")
    }

    /** Counts `[RELAXED/DABT]` lines written to stdout/stderr by the session logger. */
    private class DabtCounter : AutoCloseable {
        private val origOut = System.out
        private val origErr = System.err
        @Volatile private var hits = 0

        private inner class Tee(private val base: java.io.PrintStream) : java.io.OutputStream() {
            private val line = StringBuilder()
            override fun write(b: Int) {
                base.write(b)
                if (b == '\n'.code) {
                    if (line.contains("[RELAXED/DABT]")) hits++
                    line.setLength(0)
                } else if (line.length < 4096) {
                    line.append(b.toChar())
                }
            }
            override fun flush() = base.flush()
        }

        init {
            System.setOut(java.io.PrintStream(Tee(origOut), true))
            System.setErr(java.io.PrintStream(Tee(origErr), true))
        }

        fun count(): Int = hits

        override fun close() {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }
}
