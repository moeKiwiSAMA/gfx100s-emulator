package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AdditionalRamPlanTest {
    private val base = listOf(MemoryRegion("loader", 0, 0x20000, 7), MemoryRegion("ram", 0x100000, 0x200000, 3))
    private val added = listOf(MemoryRegion("method4", 0x3bf3000, 0x200000, 3))
    private fun plan() = AdditionalRamPlan("flashload-request-buffer-experimental-v24",
        "gfx100s-method4-output-experimental-v25", "a".repeat(40), "b".repeat(64), base, added)

    @Test fun roundTripsTheExactReceiptAndDefensivelyCopiesBothLists() {
        val original = plan()
        val receipt = original.receipt()
        assertEquals(setOf("schema", "profile", "baseProfile", "sourceProfile", "sourceCommit", "catalogSha256",
            "baseRegions", "additionalRegions"), receipt.keys)
        assertEquals(1, receipt["schema"])
        assertEquals("finite-rw-nx-experimental-v1", receipt["profile"])
        assertEquals(original, AdditionalRamPlan.parse(receipt))
        val mutableBase = ArrayList(base)
        val mutableAdded = ArrayList(added)
        val copied = AdditionalRamPlan(original.baseProfile, original.sourceProfile, original.sourceCommit,
            original.catalogSha256, mutableBase, mutableAdded)
        mutableBase.clear()
        mutableAdded.clear()
        assertEquals(base, copied.baseRegions)
        assertEquals(added, copied.additionalRegions)
        assertFailsWith<UnsupportedOperationException> { (copied.baseRegions as MutableList).clear() }
    }

    @Test fun rejectsUnknownOrMissingFieldsNonIntegersAndInvalidBudgets() {
        val valid = plan().receipt()
        for (changed in listOf(
            valid + ("extra" to 1),
            valid - "catalogSha256",
            valid + ("schema" to 1.0),
            valid + ("schema" to "1"),
            valid + ("baseProfile" to 7),
            valid + ("sourceCommit" to "A".repeat(40)),
            valid + ("sourceCommit" to "a".repeat(39)),
            valid + ("catalogSha256" to "b".repeat(63)),
            valid + ("catalogSha256" to "B".repeat(64)),
            valid + ("profile" to "finite-rw-nx-experimental-v2"),
            valid + ("baseRegions" to (base + added)),
            valid + ("additionalRegions" to AdditionalRamPlan.rows(List(41) { MemoryRegion("r$it", 0x4000000L + it * 0x1000, 0x1000, 3) })),
            valid + ("additionalRegions" to listOf(MemoryRegion("method4", 0x3bf3000, 0x30000001, 3))),
            valid + ("additionalRegions" to listOf(MemoryRegion("method4", 0x3bf3000, 0x1000, 7))),
            valid + ("additionalRegions" to listOf(MemoryRegion("method4", 0x3bf3000, 0x1000, 3),
                MemoryRegion("method4", 0x3bf4000, 0x1000, 3)))
        )) assertFailsWith<IllegalArgumentException> { AdditionalRamPlan.parse(changed) }
    }

    @Test fun readsAtMost32KiBBeforeParsing(@TempDir temporary: Path) {
        val path = temporary.resolve("plan.json")
        Files.write(path, Formats.jsonBytes(plan().receipt()))
        assertEquals(plan(), AdditionalRamPlan.read(path))
        Files.write(path, ByteArray(32769) { ' '.code.toByte() })
        assertFailsWith<IllegalArgumentException> { AdditionalRamPlan.read(path) }
        Files.write(path, ByteArray(32768) { ' '.code.toByte() })
        assertFailsWith<IllegalArgumentException> { AdditionalRamPlan.read(path) }
    }

    @Test fun catalogDerivationKeepsEveryOldFieldAndRejectsChangedOrDeletedBaseRegions() {
        val catalog = AddressCatalog.bundled()
        val profiles = com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
            Formats.map(catalog.firmwareMetadata(emptyMap()))).memoryProfiles
        val old = "seven-row-table-buffer-experimental-v21"
        val source = profiles.entries.single { it.key == "xlog-all-buffers-experimental-v23" }
        val derived = AdditionalRamPlan.fromCatalog(catalog, old, source.key, "a".repeat(40))
        assertEquals(profiles.getValue(old), derived.baseRegions)
        val expected = source.value.filter { region -> derived.baseRegions.none { it.id == region.id } }
        assertEquals(expected, derived.additionalRegions)
        assertEquals(catalog.artifactSha256, derived.catalogSha256)
        val oldRegions = profiles.getValue(old)
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamPlan.fromCatalog(catalog, old, "missing-source", "a".repeat(40))
        }
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamPlan.fromCatalog(catalog, source.key, source.key, "a".repeat(40))
        }
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamPlan.fromCatalog(catalog, "missing", source.key, "a".repeat(40))
        }
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamPlan.fromCatalog(catalog, "flashload-request-buffer-experimental-v24",
                "method4-elfloader-exec-experimental-v26", "a".repeat(40))
        }
        val execDerived = AdditionalRamPlan.fromCatalog(catalog, "flashload-request-buffer-experimental-v24",
            "method4-elfloader-exec-experimental-v26", "a".repeat(40), AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        assertEquals(AdditionalRamRegions.EXEC_CAPABLE_PROFILE, execDerived.profile)
        assertEquals(7, execDerived.additionalRegions.single().permissions)
        assertEquals(execDerived, AdditionalRamPlan.parseJson(Formats.jsonText(execDerived.receipt(), false)))
    }

    @Test fun execCapableProfileAcceptsRwNxAndRwxWhileDefaultSignatureStaysStrictV1() {
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamRegions.validate(base, listOf(MemoryRegion("exec", 0x3bf3000, 0x1000, 7)))
        }
        assertFailsWith<IllegalArgumentException> {
            AdditionalRamPlan("flashload-request-buffer-experimental-v24", "method4-elfloader-exec-experimental-v26",
                "a".repeat(40), "b".repeat(64), base, listOf(MemoryRegion("exec", 0x3bf3000, 0x1000, 7)))
        }
        val mixed = AdditionalRamPlan("flashload-request-buffer-experimental-v24",
            "method4-elfloader-exec-experimental-v26", "a".repeat(40), "b".repeat(64), base,
            listOf(MemoryRegion("exec", 0x3bf3000, 0x1000, 7), MemoryRegion("data", 0x3bf4000, 0x1000, 3)),
            AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        assertEquals(AdditionalRamRegions.EXEC_CAPABLE_PROFILE, mixed.receipt()["profile"])
        assertEquals(mixed, AdditionalRamPlan.parse(mixed.receipt()))
        for (badPermissions in listOf(1, 2, 4, 5, 6)) {
            assertFailsWith<IllegalArgumentException> {
                AdditionalRamPlan("flashload-request-buffer-experimental-v24", "method4-elfloader-exec-experimental-v26",
                    "a".repeat(40), "b".repeat(64), base, listOf(MemoryRegion("bad", 0x3bf3000, 0x1000, badPermissions)),
                    AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
            }
        }
    }
}
