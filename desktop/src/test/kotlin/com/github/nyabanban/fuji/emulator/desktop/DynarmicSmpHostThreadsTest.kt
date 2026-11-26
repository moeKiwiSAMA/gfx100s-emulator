package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import com.github.nyabanban.fuji.emulator.cpu.CpuEngineKind
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynarmicSmpHostThreadsTest {

    private fun baseConfig(mode: String, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "schema" to 1,
            "components" to "runtime/compoments",
            "httpPort" to 0,
            "firmwareMetadata" to "configs/firmware/gfx100s-213-release.yaml",
            "firmwareFile" to "runtime/firmware/GXUP0004.DAT",
            "executionMode" to mode,
            "enabledDevices" to listOf("soc-gic"),
        )
        if (mode != RelaxedPeripheralFallback.MODE) {
            m["nativeLibrary"] = "libunicorn.so"
            m["instructionBudget"] = 1000
        } else {
            m["cpuEngine"] = "dynarmic"
            m["instructionBudget"] = "unlimited"
        }
        m.putAll(extra)
        return m
    }

    private fun withSmpProp(value: String?, block: () -> Unit) {
        val prev = System.getProperty(CpuEngineConfig.SMP_HOST_THREADS_PROPERTY)
        try {
            if (value == null) System.clearProperty(CpuEngineConfig.SMP_HOST_THREADS_PROPERTY)
            else System.setProperty(CpuEngineConfig.SMP_HOST_THREADS_PROPERTY, value)
            block()
        } finally {
            if (prev == null) System.clearProperty(CpuEngineConfig.SMP_HOST_THREADS_PROPERTY)
            else System.setProperty(CpuEngineConfig.SMP_HOST_THREADS_PROPERTY, prev)
        }
    }

    @Test
    fun relaxedDefaultsToSmpHostThreadsTrueAndRespectsExplicitFalseAndOverrides() {
        withSmpProp(null) {
            // 1. Relaxed without explicit key defaults to true
            val cfgDefault = baseConfig(RelaxedPeripheralFallback.MODE)
            validateLaunchConfiguration(cfgDefault)
            assertTrue(CpuEngineConfig.resolveSmpHostThreads(cfgDefault, RelaxedPeripheralFallback.MODE))

            // 2. Relaxed with explicit false forces false
            val cfgOff = baseConfig(RelaxedPeripheralFallback.MODE, mapOf("smpHostThreads" to false))
            validateLaunchConfiguration(cfgOff)
            assertFalse(CpuEngineConfig.resolveSmpHostThreads(cfgOff, RelaxedPeripheralFallback.MODE))

            // 3. CLI overrides (--smp-host-threads false, --smp-host-threads=false, --no-smp-host-threads)
            val cliBase = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "smpHostThreads" to true,
            )
            assertEquals(false, CustomFirmwareConfig.applyCliOverrides(cliBase, arrayOf("--smp-host-threads", "false"))["smpHostThreads"])
            assertEquals(false, CustomFirmwareConfig.applyCliOverrides(cliBase, arrayOf("--smp-host-threads=false"))["smpHostThreads"])
            assertEquals(false, CustomFirmwareConfig.applyCliOverrides(cliBase, arrayOf("--no-smp-host-threads"))["smpHostThreads"])
            assertEquals(true, CustomFirmwareConfig.applyCliOverrides(cliBase + ("smpHostThreads" to false), arrayOf("--smp-host-threads=true"))["smpHostThreads"])
        }

        // 4. System property overrides YAML true -> false in relaxed mode
        withSmpProp("false") {
            val cfgOn = baseConfig(RelaxedPeripheralFallback.MODE, mapOf("smpHostThreads" to true))
            validateLaunchConfiguration(cfgOn)
            assertFalse(CpuEngineConfig.resolveSmpHostThreads(cfgOn, RelaxedPeripheralFallback.MODE))
        }
    }

    @Test
    fun strictAndDiscoveryForceFalseAndRejectExplicitTrue() {
        withSmpProp(null) {
            for (mode in listOf("strict", "discovery")) {
                // Default omitted -> false and valid
                val cfgDefault = baseConfig(mode)
                validateLaunchConfiguration(cfgDefault)
                assertFalse(CpuEngineConfig.resolveSmpHostThreads(cfgDefault, mode), "$mode default must be false")

                // Explicit false -> valid
                val cfgFalse = baseConfig(mode, mapOf("smpHostThreads" to false))
                validateLaunchConfiguration(cfgFalse)
                assertFalse(CpuEngineConfig.resolveSmpHostThreads(cfgFalse, mode))

                // Explicit true -> REJECTED
                val cfgTrue = baseConfig(mode, mapOf("smpHostThreads" to true))
                assertFailsWith<IllegalArgumentException>("$mode must reject smpHostThreads=true") {
                    validateLaunchConfiguration(cfgTrue)
                }
                assertFailsWith<IllegalArgumentException>("CpuEngineConfig.validate must reject smpHostThreads=true in $mode") {
                    CpuEngineConfig.validate(cfgTrue, mode)
                }

                // Non-boolean value -> REJECTED
                val cfgBadType = baseConfig(mode, mapOf("smpHostThreads" to "yes"))
                assertFailsWith<IllegalArgumentException>("non-boolean smpHostThreads must fail") {
                    validateLaunchConfiguration(cfgBadType)
                }
            }
        }

        // System property true in strict/discovery -> REJECTED
        withSmpProp("true") {
            for (mode in listOf("strict", "discovery")) {
                assertFailsWith<IllegalArgumentException>("-Dfuji.smp.host.threads=true must fail in $mode") {
                    validateLaunchConfiguration(baseConfig(mode))
                }
            }
        }
    }

    @Test
    fun releaseYamlsEnableSmpHostThreadsByDefault() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        for (rel in listOf(
            "tools/release/emulator-release-linux.yaml",
            "tools/release/emulator-release-windows.yaml",
            "tools/release/examples/emulator-relaxed-default.yaml",
            "tools/release/examples/dynarmic-turbo.yaml",
        )) {
            val cfg = Formats.read(repo.resolve(rel))
            assertEquals(true, cfg["smpHostThreads"], "$rel should explicitly set smpHostThreads: true")
            validateLaunchConfiguration(cfg)
        }
    }

    @Test
    fun smpHostThreadsDifferentialOwnerMmioAndSnapshotRoundTrip() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val lib = CpuEngineConfig.findDynarmicLibrary(repo, emptyMap())
        assumeTrue(lib != null, "Dynarmic native library not built locally; skipping live SMP-MT test")

        // 4-core ARM32 program:
        // Each core runs 2,000 iterations of:
        //   LDREX/ADD/STREX on shared counter @ 0x00120000
        //   Every 16th iteration: STR + LDR to non-fastpath MMIO @ 0x40001000 + core*4
        // Then WFI!
        val codeBase = 0x0001_0000L
        val counterPa = 0x0012_0000L
        val mmioBase = 0x4000_0000L
        val iters = 2000
        val prog = intArrayOf(
            // r0 = counterPa, r1 = iters, r2 = myMmioAddr, r3 = coreTag, r6 = mismatchCount
            // loop (0x00):
            0xE1904F9F.toInt(), // ldrex r4, [r0]
            0xE2844001.toInt(), // add r4, r4, #1
            0xE1805F94.toInt(), // strex r5, r4, [r0]
            0xE3550000.toInt(), // cmp r5, #0
            0x1AFFFFFA, // bne loop (-24 -> 0x00)
            0xE311000F.toInt(), // tst r1, #15
            0x1A000004, // bne skip_mmio (+24 -> 0x30)
            0xE0837001.toInt(), // add r7, r3, r1
            0xE5827000.toInt(), // str r7, [r2]
            0xE5928000.toInt(), // ldr r8, [r2]
            0xE1580007.toInt(), // cmp r8, r7
            0x12866001, // addne r6, r6, #1
            // skip_mmio (0x30):
            0xE2511001.toInt(), // subs r1, r1, #1
            0x1AFFFFF1, // bne loop (-60 -> 0x00)
            0xE320F003.toInt(), // wfi
        )
        val progBytes = ByteBuffer.allocate(prog.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (w in prog) putInt(w)
        }.array()

        fun runFullWorkload(enableMt: Boolean, snapshotMidway: Boolean): Triple<Int, Int, Int> {
            val ownerThread = Thread.currentThread()
            val offThreadCalls = AtomicInteger(0)
            val mmioReads = AtomicInteger(0)
            val mmioWrites = AtomicInteger(0)
            val mmioRegs = IntArray(4)

            val fakeIrq = object : CoreInterruptPort {
                override val coreCount: Int = 4
                override fun selectCore(core: Int) {}
                override fun hasPending(): Boolean = false
                override fun choose(): Int? = null
            }

            Dynarmic(lib!!, 4, 0xFFFF_0000L).use { dyn ->
                assumeTrue(dyn.supportsSmpHostThreads(), "Native Dynarmic library lacks fuji_dyn_set_smp_host_threads")
                dyn.map(0x0000_0000L, 4L * 1024L * 1024L, 7)
                dyn.mmio(mmioBase, 0x10000L)
                dyn.writeMemory(codeBase, progBytes)
                dyn.writeMemory(counterPa, ByteArray(4))
                dyn.setSmpHostThreads(enableMt)
                assertEquals(enableMt, dyn.isSmpHostThreadsEnabled())

                val handler = object : Dynarmic.MmioHandler {
                    override fun read(physicalAddress: Long, size: Int): Long {
                        if (Thread.currentThread() !== ownerThread) offThreadCalls.incrementAndGet()
                        mmioReads.incrementAndGet()
                        val slot = (((physicalAddress - 0x4000_1000L) ushr 2) and 3L).toInt()
                        return Integer.toUnsignedLong(mmioRegs[slot])
                    }
                    override fun write(physicalAddress: Long, size: Int, value: Long) {
                        if (Thread.currentThread() !== ownerThread) offThreadCalls.incrementAndGet()
                        mmioWrites.incrementAndGet()
                        val slot = (((physicalAddress - 0x4000_1000L) ushr 2) and 3L).toInt()
                        mmioRegs[slot] = value.toInt()
                    }
                }
                dyn.setMmioHandler(handler)

                val cluster = DynarmicCluster(dyn, fakeIrq, null)
                assertEquals(enableMt, cluster.state()["smpHostThreads"])

                for (c in 0..3) {
                    dyn.resetCore(c)
                    dyn.setReg(c, 0, counterPa)
                    dyn.setReg(c, 1, iters.toLong())
                    dyn.setReg(c, 2, 0x4000_1000L + c * 4L)
                    dyn.setReg(c, 3, ((c + 1) shl 16).toLong())
                    dyn.setReg(c, 6, 0L)
                    dyn.setReg(c, 15, codeBase)
                }

                var vtick = 0L
                var runnable = 0xF
                var wfiMask = 0
                var didSnap = false
                var guard = 0
                while (runnable != 0 && guard < 1000) {
                    guard++
                    val res = dyn.runClusterFast(
                        if (snapshotMidway && !didSnap) 8000L else 500_000L,
                        vtick,
                        runnable,
                        0,
                        0,
                        0,
                        1024,
                        0,
                        wfiMask,
                    )
                    vtick = res.virtualTick
                    wfiMask = wfiMask or res.wfiEnteredMask
                    runnable = runnable and res.wfiEnteredMask.inv()

                    if (snapshotMidway && !didSnap && runnable != 0) {
                        didSnap = true
                        // Capture full cluster + RAM snapshot mid-flight under SMP-MT, reset engine, restore, and resume!
                        val snap = cluster.saveSnapshot()
                        val ramCopy = dyn.readMemory(0x0000_0000L, 4 * 1024 * 1024)
                        dyn.resetForColdReset()
                        assertFalse(dyn.isSmpHostThreadsEnabled(), "resetForColdReset must quiesce SMP-MT")
                        dyn.map(0x0000_0000L, 4L * 1024L * 1024L, 7)
                        dyn.mmio(mmioBase, 0x10000L)
                        dyn.writeMemory(0x0000_0000L, ramCopy)
                        dyn.setMmioHandler(handler)
                        cluster.restoreSnapshot(snap.metadata)
                        dyn.setSmpHostThreads(enableMt)
                        assertTrue(dyn.isSmpHostThreadsEnabled())
                    }
                }

                assertEquals(0, runnable, "All 4 cores must finish to WFI (mt=$enableMt)")
                assertEquals(0, offThreadCalls.get(), "ALL JVM MMIO upcalls MUST execute strictly on owner thread!")
                for (c in 0..3) {
                    assertEquals(0L, dyn.getReg(c, 6), "Core $c saw MMIO readback corruption")
                }
                val cntBytes = dyn.readMemory(counterPa, 4)
                val cnt = ByteBuffer.wrap(cntBytes).order(ByteOrder.LITTLE_ENDIAN).int
                return Triple(cnt, mmioReads.get(), mmioWrites.get())
            }
        }

        val (stCnt, stR, stW) = runFullWorkload(enableMt = false, snapshotMidway = false)
        val (mtCnt, mtR, mtW) = runFullWorkload(enableMt = true, snapshotMidway = false)
        val (snapCnt, snapR, snapW) = runFullWorkload(enableMt = true, snapshotMidway = true)

        assertEquals(4 * iters, stCnt, "ST counter mismatch")
        assertEquals(stCnt, mtCnt, "MT counter must match ST bit-exact")
        assertEquals(stCnt, snapCnt, "MT+snapshot counter must match ST bit-exact")
        assertEquals(stR, mtR, "MT MMIO read count must match ST")
        assertEquals(stW, mtW, "MT MMIO write count must match ST")
        assertEquals(stR, snapR, "MT+snapshot MMIO read count must match ST")
        assertEquals(stW, snapW, "MT+snapshot MMIO write count must match ST")
    }
}
