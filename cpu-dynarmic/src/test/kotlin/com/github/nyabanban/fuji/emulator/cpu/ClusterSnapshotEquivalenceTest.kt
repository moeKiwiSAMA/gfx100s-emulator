package com.github.nyabanban.fuji.emulator.cpu

import com.github.nyabanban.fuji.emulator.api.CoreInterruptPort
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterSnapshotEquivalenceTest {
    private class TestInterrupts : CoreInterruptPort {
        override val coreCount = 4
        var selected = 0
        val pending = arrayOfNulls<Int>(4)
        override fun selectCore(core: Int) { require(core in 0..3); selected = core }
        override fun choose(): Int? = pending[selected]
    }

    @Test
    fun saveAndRestoreAcrossEnginesPreservesBankedModesCp15VfpExclusivesAndContinuation() {
        val lib = DynarmicTestHelper.requireDynarmicLib()
        // Small A32 loop at 0x0:
        // 0x00: add r0, r0, r1       (0xe0800001)
        // 0x04: str r0, [r2], #4     (0xe4820004)
        // 0x08: subs r3, r3, #1      (0xe2533001)
        // 0x0c: bne 0x00             (0x1afffffb)
        // 0x10: wfi                  (0xe320f003)
        val code = DynarmicTestHelper.words(0xe0800001, 0xe4820004, 0xe2533001, 0x1afffffb, 0xe320f003)
        val resetSpec = mapOf("profile" to ResetSchedule.PROFILE, "releaseTicks" to listOf(0L, 2L, 4L, 1000L))
        val exclSpec = mapOf("profile" to WordExclusive.BYTE_PROFILE, "reservationGranule" to 64)

        lateinit var savedCluster: ArmClusterSnapshot
        lateinit var savedExclusive: Map<String, Any?>
        lateinit var savedRamT1: ByteArray
        lateinit var directRamT2: ByteArray
        lateinit var directFinalSnap: ArmClusterSnapshot
        lateinit var directFinalExcl: Map<String, Any?>

        Dynarmic(lib, 4, 0xFFFF_0000L).use { dyn ->
            dyn.map(0, 0x4000, Dynarmic.PERM_ALL)
            dyn.writeMemory(0, code)
            val irqs = TestInterrupts()
            val reset = ResetSchedule.parse(resetSpec)
            val exclusive = WordExclusive.parse(exclSpec)
            val cluster = DynarmicCluster(dyn, irqs, reset = reset)

            val seedSnap = cluster.saveSnapshot()
            @Suppress("UNCHECKED_CAST")
            val mutableMeta = LinkedHashMap(seedSnap.metadata)
            @Suppress("UNCHECKED_CAST")
            val coreList = (mutableMeta["cores"] as List<Map<String, Any?>>).mapIndexed { core, row ->
                val r = LinkedHashMap(row)
                val arch = LinkedHashMap(r["architectural"] as Map<String, Any>)
                val unbanked = LinkedHashMap(arch["unbanked"] as Map<String, Long>)
                unbanked["r0"] = 100L * (core + 1)
                unbanked["r1"] = 7L + core
                unbanked["r2"] = 0x1000L + core * 0x100L
                unbanked["r3"] = 5L
                unbanked["pc"] = 0L
                unbanked["cpsr"] = 0x6000_01d3L
                arch["unbanked"] = unbanked

                @Suppress("UNCHECKED_CAST")
                val banks = LinkedHashMap(arch["banks"] as Map<String, Map<String, Long>>)
                banks["sys"] = mapOf("r8" to 0x800L + core, "r9" to 0x900L + core, "r10" to 0xa00L + core, "r11" to 0xb00L + core, "r12" to 0xc00L + core, "sp" to 0x2000L + core * 0x40, "lr" to 0x2100L + core * 0x40)
                banks["fiq"] = mapOf("r8" to 0x1800L + core, "r9" to 0x1900L + core, "r10" to 0x1a00L + core, "r11" to 0x1b00L + core, "r12" to 0x1c00L + core, "sp" to 0x2200L + core * 0x40, "lr" to 0x2300L + core * 0x40, "spsr" to 0x1000_01d0L + core)
                banks["irq"] = mapOf("sp" to 0x2400L + core * 0x40, "lr" to 0x2500L + core * 0x40, "spsr" to 0x2000_01d3L + core)
                banks["svc"] = mapOf("sp" to 0x2600L + core * 0x40, "lr" to 0x2700L + core * 0x40, "spsr" to 0x3000_01d0L + core)
                banks["abt"] = mapOf("sp" to 0x2800L + core * 0x40, "lr" to 0x2900L + core * 0x40, "spsr" to 0x4000_01d3L + core)
                banks["und"] = mapOf("sp" to 0x2a00L + core * 0x40, "lr" to 0x2b00L + core * 0x40, "spsr" to 0x5000_01d3L + core)
                arch["banks"] = banks

                @Suppress("UNCHECKED_CAST")
                val cp15 = LinkedHashMap(arch["cp15"] as Map<String, Long>)
                cp15["ttbr0"] = 0x0012_0000L + core * 0x4000L
                cp15["dacr"] = 0x5555_5555L
                cp15["vbar"] = 0x0004_0000L
                cp15["contextidr"] = 0x10L + core
                cp15["tpidrprw"] = 0x0006_c6ccL + core * 4
                arch["cp15"] = cp15

                @Suppress("UNCHECKED_CAST")
                val vfp = LinkedHashMap(arch["floatingPoint"] as Map<String, Any>)
                vfp["fpexc"] = 0x4000_0000L
                vfp["fpscr"] = (core.toLong() shl 28)
                vfp["dRegisters"] = (0..31).map { d -> (0x0102030405060700UL + (core * 32 + d).toULong()).toString(16).padStart(16, '0') }
                arch["floatingPoint"] = vfp

                r["architectural"] = arch
                r["waiting"] = if (core == 3) "reset" else null
                r
            }
            mutableMeta["cores"] = coreList
            mutableMeta["currentCore"] = 0
            mutableMeta["usedInQuantum"] = 0L
            mutableMeta["resetSchedule"] = mapOf(
                "profile" to ResetSchedule.PROFILE,
                "configuredReleaseTicks" to listOf(0L, 2L, 4L, 1000L),
                "actualReleaseTicks" to listOf(0L, null, null, null),
            )
            cluster.restoreSnapshot(mutableMeta)

            // Run T0 -> T1: execute core 0 to WFI, switch to core 1, execute 1 iteration, seed exclusive.
            assertEquals(Dynarmic.STOP_WFI, dyn.runFast(0, 64, 0L, false).stopReason)
            cluster.account(20)
            assertTrue(cluster.switch())
            assertEquals(1, cluster.current)
            dyn.runFast(1, 4, 20L, false)
            cluster.account(4)
            exclusive.written(0x1000L, 4L)

            // Capture T1 snapshot.
            savedCluster = cluster.saveSnapshot()
            savedExclusive = exclusive.saveSnapshot()
            savedRamT1 = dyn.readMemory(0, 0x4000)

            // Continue direct T1 -> T2.
            assertEquals(Dynarmic.STOP_WFI, dyn.runFast(1, 64, 24L, false).stopReason)
            cluster.account(16)
            assertTrue(cluster.switch())
            assertEquals(2, cluster.current)
            assertEquals(Dynarmic.STOP_WFI, dyn.runFast(2, 64, 40L, false).stopReason)
            cluster.account(20)
            exclusive.written(0x1100L, 4L)

            directRamT2 = dyn.readMemory(0, 0x4000)
            directFinalSnap = cluster.saveSnapshot()
            directFinalExcl = exclusive.saveSnapshot()
        }

        // Restore on a brand-new Dynarmic engine and verify bit-exact continuation.
        Dynarmic(lib, 4, 0xFFFF_0000L).use { freshDyn ->
            freshDyn.map(0, 0x4000, Dynarmic.PERM_ALL)
            freshDyn.writeMemory(0, savedRamT1)
            val freshIrqs = TestInterrupts()
            val freshReset = ResetSchedule.parse(resetSpec)
            val freshExclusive = WordExclusive.parse(exclSpec)
            val freshCluster = DynarmicCluster(freshDyn, freshIrqs, reset = freshReset)
            freshCluster.restoreSnapshot(savedCluster.metadata)
            freshExclusive.restoreSnapshot(savedExclusive)
            assertEquals(1, freshIrqs.selected)
            assertEquals(1, freshCluster.current)

            assertEquals(Dynarmic.STOP_WFI, freshDyn.runFast(1, 64, 24L, false).stopReason)
            freshCluster.account(16)
            assertTrue(freshCluster.switch())
            assertEquals(2, freshCluster.current)
            assertEquals(Dynarmic.STOP_WFI, freshDyn.runFast(2, 64, 40L, false).stopReason)
            freshCluster.account(20)
            freshExclusive.written(0x1100L, 4L)

            assertContentEquals(directRamT2, freshDyn.readMemory(0, 0x4000), "RAM T2 mismatch after restore")
            assertEquals(directFinalSnap.metadata, freshCluster.saveSnapshot().metadata, "Cluster metadata T2 mismatch")
            assertEquals(directFinalExcl, freshExclusive.saveSnapshot(), "WordExclusive T2 mismatch")
        }
    }
}
