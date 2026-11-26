package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Standalone public-checkout stub: skips archived historical-blob tests when Git history blobs are absent. */
internal class HistoricalCatalogFixture(@Suppress("UNUSED_PARAMETER") private val root: Path) {
    fun bytes(revision: String, path: String): ByteArray {
        assumeTrue(false, "Historical Git blob fixture ($revision:$path) is omitted in standalone public checkouts.")
        error("Unreachable")
    }

    fun catalog(revision: String): AddressCatalog =
        CatalogIO.readCompiled(bytes(revision, "catalog/compiled/catalog.json"))
}
