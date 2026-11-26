package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.*

class F01cInventoryTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    @Test fun structuredCoordinatesKeepNullUnknownAndSoftwareZeroDistinct() {
        val c=CatalogCompiler.compile(root);val q=F01cInventoryQualification.record(c)
        assertNull(q["resetValue"]);assertNull(q["physicalAddress"]);assertEquals(0L,valueLong(q["softwareExitValue"]))
        val entries=rows(c.data["inventory"])
        for(change in listOf(mapOf("reset" to mapOf("value" to 0)),mapOf("physicalAddress" to "0xFFF4F01C"),mapOf("cpuRoutable" to true),mapOf("binding" to mapOf("active" to true,"componentId" to "soc-system-control","portId" to null)))) {
            val bad=AddressCatalog(c.data+("inventory" to entries.map {if(it["id"]==F01cInventoryQualification.ID)it+change else it}))
            assertFailsWith<IllegalStateException> {F01cInventoryQualification.record(bad)}
        }
    }
    @Test fun deletingOnlyOneInventoryEntryRestoresCompleteG13And32NarrowAbis() {
        val history = HistoricalCatalogFixture(root)
        val old = history.catalog(F01cInventoryQualification.G13)
        val c = history.catalog("8e17b732bef3030db622e413068aacfda8634f0e")
        val q=F01cInventoryQualification.delta(old,c)
        assertEquals(true,q["deletingOnlyNewInventoryRestoresCompleteG13"]);assertEquals(32,q["unchangedComponentDescriptorCount"])
        val altered=AddressCatalog(c.data+("legacyFirmwareMetadata" to (c.legacyFirmwareMetadata()+("entry" to 0))))
        assertFailsWith<IllegalStateException> {F01cInventoryQualification.delta(old,altered)}
    }
    @Test fun actualIdAndPropertyQueriesGrantNoWordOrPageAccessInAnyExistingProfile() {
        val history = HistoricalCatalogFixture(root)
        val frozen = history.catalog("8e17b732bef3030db622e413068aacfda8634f0e")
        val observed = F01cInventoryQualification.queries(history.catalog(F01cInventoryQualification.G13), frozen)
        assertEquals(frozen.memoryProfiles.size * 12, observed["physicalQueryCount"])

        val c = CatalogCompiler.compile(root)
        val inventory = rows(c.data.getValue("inventory")).filter { it["id"] != F01cInventoryQualification.ID }
        val baseline = AddressCatalog(c.data + ("inventory" to inventory))
        val q = F01cInventoryQualification.queries(baseline, c)
        assertEquals(false,q["componentBound"]);assertEquals(false,q["pageOrWordAccessGranted"])
        assertEquals(c.memoryProfiles.size*12,q["physicalQueryCount"])
    }
}
