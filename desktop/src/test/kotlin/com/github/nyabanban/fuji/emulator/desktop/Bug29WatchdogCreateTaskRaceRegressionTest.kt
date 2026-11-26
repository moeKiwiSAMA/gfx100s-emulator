package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedFlashPersistence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression test for BUG-29 (`#1158` / `#1163`):
 *
 * 1. Real-silicon 4-core SMP CPU-to-APB-timer clock divider (`vtickDiv=4` for 4-core `relaxed`
 *    Dynarmic sessions, modeling 4 parallel Cortex-A7 cores rather than 4x serialized slowdown)
 *    eliminates the RTOS 30 ms stack-canary watchdog (`0x012C9CBC`) vs `create_task` (`0x012B5BE8`)
 *    race during concurrent 4-core L1+L2 D-cache Clean/Invalidate (`0x00059344..0x000593B8`) at
 *    `STAGE_3_STORAGE_IO` (`flashUnique=96, ops=159`), which previously halted with
 *    `*(0x01BE0138) == -64`, `pc = 0x012C9D14`, and peer cores trapped at `0x012B5950`.
 * 2. Release launch configs enable `realTimePacer` (`1.0x` @ `54,000,000` ticks/s) so interactive
 *    OOBE / menu WFI idle stays at real wall-clock speed instead of racing at ~12x and hitting the
 *    2-minute `AUTO POWER OFF` blackout after ~10 seconds during first-boot Language Selection.
 */
class Bug29WatchdogCreateTaskRaceRegressionTest {
    private fun copyDirectory(src: Path, dst: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { path ->
                val rel = src.relativize(path)
                val target = dst.resolve(rel)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    val name = path.fileName.toString()
                    if (name.endsWith(".DAT") || name.endsWith(".jar") || name.endsWith(".so") || name.endsWith(".so.2")) {
                        try { Files.createLink(target, path) } catch (_: Throwable) {
                            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    } else {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun readGuestU32(rt: EmulatorRuntime, addr: Long): Int =
        rt.owner.submit {
            val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            mem.read(addr, buf)
            buf.getInt(0)
        }.join()

    private fun stepUntilScreenOrTicks(rt: EmulatorRuntime, targetScr: Int, maxTicks: Long) {
        val deadline = rt.telemetry.virtualTick + maxTicks
        var guard = 0
        while (rt.telemetry.virtualTick < deadline && guard < 20_000) {
            val ok = rt.owner.submit {
                var cont = true
                var i = 0
                while (cont && i < 64 && rt.telemetry.virtualTick < deadline) {
                    cont = rt.session.stepFast()
                    i++
                }
                cont
            }.join()
            if (!ok) break
            if (rt.telemetry.publishedFrames >= 1L && readGuestU32(rt, 0x01CC6424L) == targetScr) {
                break
            }
            guard++
        }
    }

    @Test
    fun releaseConfigsEnableRealTimePacerForInteractiveOobe() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        for (rel in listOf(
            "tools/release/emulator-release-linux.yaml",
            "tools/release/emulator-release-windows.yaml",
            "tools/release/examples/emulator-relaxed-default.yaml",
        )) {
            val cfg = Formats.read(repo.resolve(rel))
            validateLaunchConfiguration(cfg)
            @Suppress("UNCHECKED_CAST")
            val pacer = cfg["realTimePacer"] as? Map<String, Any?>
            assertTrue(pacer != null, "$rel must configure realTimePacer")
            assertEquals(true, pacer["enabled"], "$rel realTimePacer.enabled")
            assertEquals(1.0, (pacer["targetRatio"] as Number).toDouble(), "$rel realTimePacer.targetRatio")
            assertEquals(54_000_000L, (pacer["ticksPerRealSecond"] as Number).toLong(), "$rel realTimePacer.ticksPerRealSecond")
        }
    }

    @Test
    fun boot2AfterAbruptBoot1ClearsStage3WithoutStackWatchdogPanicAcrossSdConfigs(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("bug29-work")
        copyDirectory(stagedRoot, workRoot)

        // Boot #1: cold boot to OOBE Language Selection (0x0421) so COW flash & submcu-nvram.json are written,
        // then close abruptly without clean power=false shutdown (reproducing user closing after OOBE blackout).
        val cfg1 = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        RelaxedFlashPersistence.prepare(workRoot, cfg1)
        EmulatorRuntime(workRoot, cfg1, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepUntilScreenOrTicks(rt, 0x0421, 300_000_000L)
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Boot #1 must reach OOBE Language Selection 0x0421")
            assertNotEquals(-64, readGuestU32(rt, 0x01BE0138L), "Boot #1 must not hit stack-watchdog panic (-64)")
        }

        // Boot #2 matrix: test the exact previously-fatal slot1-only configuration (slot1=true, slot2=false)
        // plus both-SD and no-SD. All three MUST clear STAGE_3_STORAGE_IO with zero stack-watchdog panic (-64)
        // and reach OOBE Language Selection (0x0421) with >=1 rendered LCD frame.
        val cases = listOf(
            Triple("slot1-only", true, false),
            Triple("both-sd", true, true),
            Triple("no-sd", false, false),
        )
        for ((label, s1, s2) in cases) {
            val cfg2 = Formats.read(workRoot.resolve("configs/emulator.yaml"))
            RelaxedFlashPersistence.prepare(workRoot, cfg2)
            EmulatorRuntime(workRoot, cfg2, telemetryDisabled = true).use { rt ->
                rt.owner.submit {
                    val sd = rt.host.components().single { it.descriptor.id == "sdcard-reader" }
                    rt.host.patch("sdcard-reader", sd.revision, mapOf("slot1Inserted" to s1, "slot2Inserted" to s2)).join()
                    rt.session.initialize()
                }.join()

                stepUntilScreenOrTicks(rt, 0x0421, 300_000_000L)
                val err = readGuestU32(rt, 0x01BE0138L)
                val scr = readGuestU32(rt, 0x01CC6424L)
                val wd = readGuestU32(rt, 0x0181B600L)
                val mask = rt.telemetry.stageMask
                val frames = rt.telemetry.publishedFrames
                val stage = FirmwareStage.highestReached(mask)?.label ?: "NONE"
                println(
                    "[BUG29-REGRESSION][$label] scr=0x${scr.toString(16)} err=$err wd=0x${wd.toString(16)} " +
                        "stage=$stage frames=$frames vtick=${rt.telemetry.virtualTick} attempts=${rt.telemetry.attempts}",
                )
                assertNotEquals(-64, err, "[$label] Hit BUG-29 stack-canary watchdog panic (-64 / 0x012C9D14)")
                assertEquals(0x0421, scr, "[$label] Expected Boot #2 to reach OOBE 0x0421, got stage=$stage")
                assertTrue(frames >= 1L, "[$label] Expected >= 1 rendered frame on Boot #2")
                assertTrue(
                    wd in 1 until 0x22,
                    "[$label] Expected 4-core SMP clock scaling to finish OOBE boot well before watchdog sweeps tid=0x22 (got wd=0x${wd.toString(16)})",
                )
            }
        }
    }
}
