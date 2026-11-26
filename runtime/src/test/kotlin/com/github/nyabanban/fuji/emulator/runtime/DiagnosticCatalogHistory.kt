package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.HistoricalCatalogFixture
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun historicalDiagnosticCatalog(revision: String): Map<String, Any?> {
    val root = Path.of(System.getProperty("componentProjectRoot"))
    return HistoricalCatalogFixture(root).catalog(revision).data
}

internal fun AddressCatalog.atCompletionQualification(): Map<String, Any?> {
    val historical = historicalDiagnosticCatalog("c6469c84c7e14713986e07546d6a668260a41968")
    for (key in listOf("diagnosticProfiles", "singleReadDiagnosticProfiles")) {
        assertEquals(historical.getValue(key), data.getValue(key), key)
    }
    return historical
}

internal fun assertSharedA300Page(parent: DiscoveryBus, selected: DiscoveryBus) {
    val page = 0xfff4a000L to 4096L
    assertTrue(page !in parent.pageRanges())
    assertTrue(parent.known.any { (it.base and -4096L) == page.first })
    assertEquals((parent.pageRanges() + page).sortedBy { it.first }, selected.pageRanges())
}
