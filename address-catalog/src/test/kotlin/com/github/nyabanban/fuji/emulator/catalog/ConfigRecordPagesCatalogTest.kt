package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import kotlin.test.*

class ConfigRecordPagesCatalogTest {
    private val root=Path.of(System.getProperty("catalogRoot"))
    @Test fun deletingOnlyNewProfileRestoresAllG12FactsAndEveryNarrowDescriptor() {
        val history = HistoricalCatalogFixture(root)
        val baseline = history.catalog(ConfigRecordPagesCatalogQualification.BASELINE)
        val report = ConfigRecordPagesCatalogQualification.verifyCatalog(baseline, history.catalog("11d4cf6ca13b843c5028f05d5bbca9079b3b6279"))
        assertEquals(true,report["deletingOnlyNewProfileRestoresCompleteG12"])
        assertEquals(32,report["unchangedComponentDescriptorCount"])
        assertTrue((report["leafDifferenceCount"] as Int)>0)
    }
    @Test fun selectedProfileGrantsOnlyThreeDataPagesIncludingExplicitHypothesisPadding() {
        val c=CatalogCompiler.compile(root)
        val queries=ConfigRecordPagesCatalogQualification.queries(c)
        assertEquals(21,queries.size)
        val regions=c.memoryProfiles.getValue(ConfigRecordPagesCatalogQualification.PROFILE)
        c.verifyStockMemory(ConfigRecordPagesCatalogQualification.PROFILE,regions)
        for(key in listOf("size","permissions")) {
            val changed=regions.map {if(it["id"]==ConfigRecordPagesCatalogQualification.REGION)it+(key to if(key=="size")0x4000 else 7)else it}
            assertFailsWith<IllegalArgumentException> {c.verifyStockMemory(ConfigRecordPagesCatalogQualification.PROFILE,changed)}
        }
    }
    @Test fun actualGradleFactoryAddsOnlyOneRestartChoiceAndStillRefusesUnknownValues(@TempDir jars: Path) {
        val history = HistoricalCatalogFixture(root)
        val archive = "2aaff96835844a3cfd5233941c4d8b8daeee6cdd"
        val memoryBytes = history.bytes(archive,
            "evidence/refactor/cache-clean-export-original-fresh-41/study/discovery-cap8-v5-gpio110-cache-clean-export/compoments/system-memory-0.0.1.jar")
        val apiBytes = history.bytes(archive,
            "evidence/refactor/bank13-read500-original-fresh-18/study/discovery-cap8-v5-gpio110-bank13-read500-lcdinit/lib/component-api-0.0.1.jar")
        assertEquals("09297a62b1db7def20ee46622b478cb06930ba28dd1a1be8b67e65005094e974", CatalogIO.sha256(memoryBytes))
        assertEquals("bc97d3ebc1050e75465df28484341482fa7ac830cd43ecf8ea7e8b82188a33f7", CatalogIO.sha256(apiBytes))
        val memoryJar = Files.write(jars.resolve("system-memory.jar"), memoryBytes)
        val apiJar = Files.write(jars.resolve("component-api.jar"), apiBytes)
        val path = "components/system-memory/src/main/kotlin/com/github/nyabanban/fuji/emulator/components/memory/MemoryFactory.kt"
        val base = ConfigRecordPagesCatalogQualification.BASELINE
        val report = ConfigRecordPagesCatalogQualification.factoryParameter(
            history.bytes("11d4cf6ca13b843c5028f05d5bbca9079b3b6279", path).toString(Charsets.UTF_8), history.bytes(base, path),
            memoryJar, apiJar, history.catalog(base),
        )
        assertEquals(ConfigRecordPagesCatalogQualification.PROFILE,report["onlyNewChoice"])
        assertEquals(true,report["unknownChoiceRejected"])
    }
}
