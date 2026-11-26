package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import kotlin.test.assertEquals

/** G12 is the published model containing all eleven selector declarations and the original 32 component ABIs. */
internal fun protocolQualificationCatalog(root: Path): AddressCatalog {
    val published = HistoricalCatalogFixture(root).catalog("b2516f9ef429b66226e90ab43f1fc7b98b4bf86b")
    val current = CatalogCompiler.compile(root)
    assertEquals(published.protocolCandidateSpaces.map { it.data }, current.protocolCandidateSpaces.map { it.data },
        "The active protocol spaces must preserve the complete qualified declarations")
    return published
}
