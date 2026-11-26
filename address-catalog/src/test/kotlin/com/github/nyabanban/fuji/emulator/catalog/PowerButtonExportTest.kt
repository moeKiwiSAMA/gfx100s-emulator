package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PowerButtonExportTest {
    @Test fun optionalPowerBindingExportsOnlyItsFiniteCoordinatesAndRemovesStaleOutput() {
        val catalog=CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val output=Files.createTempDirectory("power-button-export")
        try {
            CatalogCompiler.generatePowerButton(catalog,output)
            assertEquals(listOf("profile=gfx100s-2.13-power-switch-v1","power=0,10,true"),Files.readAllLines(output.resolve("power-button.properties")))
            val devices=rows(catalog.data["devices"]).map{it-"powerButtonBinding"}
            CatalogCompiler.generatePowerButton(AddressCatalog(catalog.data+("devices" to devices)),output)
            assertFalse(Files.exists(output.resolve("power-button.properties")))
        }finally{output.toFile().deleteRecursively()}
    }
}
