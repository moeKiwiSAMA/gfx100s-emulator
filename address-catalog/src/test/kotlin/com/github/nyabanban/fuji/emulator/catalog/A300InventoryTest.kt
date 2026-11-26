package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class A300InventoryTest {
    private val root = Path.of(System.getProperty("catalogRoot"))
    @Test fun unknownReadCannotBecomeZeroOrActiveBinding() {
        val current = CatalogCompiler.compile(root)
        val record = A300InventoryQualification.record(current)
        assertNull(record["returnValue"]); assertNull(record["resetValue"])
        assertEquals(0L, valueLong(obj(record["softwareConsumer"])["ordinaryReturnValue"]))
        val entries = rows(current.data["inventory"])
        for (change in listOf(mapOf("returnValue" to 0), mapOf("resetValue" to 0), mapOf("physicalAddress" to "0xFFF4A300"), mapOf("cpuRoutable" to true), mapOf("binding" to mapOf("active" to true, "componentId" to "soc-system-control", "portId" to null)))) {
            val bad = AddressCatalog(current.data + ("inventory" to entries.map { if (it["id"] == A300InventoryQualification.ID) it + change else it }))
            assertFailsWith<IllegalStateException> { A300InventoryQualification.record(bad) }
        }
    }
    @Test fun onlyTheNewPassiveRowMayDifferFromCurrentBaseline() {
        val history = HistoricalCatalogFixture(root)
        val old = history.catalog(A300InventoryQualification.BASE)
        val current = history.catalog("f5ac85268b1fab064b46fdd3c6b2ee72c7b087a3")
        val result = A300InventoryQualification.delta(old, current)
        assertEquals(true, result["completeBaselineRestored"])
        assertEquals(32, result["unchangedComponentDescriptorCount"])
        val changed = AddressCatalog(current.data + ("legacyFirmwareMetadata" to (current.legacyFirmwareMetadata() + ("entry" to 0))))
        assertFailsWith<IllegalStateException> { A300InventoryQualification.delta(old, changed) }
    }
}
