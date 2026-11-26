package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GpioButtonExportTest {
    @Test fun validCatalogWithoutTheBodyProfileDoesNotRequireBindingsOrRetainAStaleExport() {
        val catalog = CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val devices = rows(catalog.data["devices"]).map { device ->
            if(device["componentId"] != "soc-gpio") device else device + mapOf(
                "profiles" to listOf("disabled"),
                "ports" to rows(device["ports"]).map { port -> port + ("registers" to rows(port["registers"]).map { register ->
                    if(register["id"] == "reg.soc-gpio.main.o14") register + ("connections" to emptyList<Any>()) else register
                }) })
        }
        val data = catalog.data + ("devices" to devices)
        CatalogCompiler.validate(data)
        val unselected = AddressCatalog(data)
        val output = Files.createTempDirectory("gpio-catalog-export")
        try {
            val properties = output.resolve("gpio-buttons.properties")
            val expanded = output.resolve("gpio-buttons-v2.properties")
            Files.writeString(properties, "stale=true\n")
            Files.writeString(expanded, "stale=true\n")
            CatalogCompiler.generateGpioButtons(unselected, output)
            assertFalse(Files.exists(properties))
            assertFalse(Files.exists(expanded))
            CatalogCompiler.generateGpioButtons(catalog, output)
            assertEquals(listOf("profile=gfx100s-2.13-gpio-buttons-v1", "back=9,5,true", "shutterFull=0,1,true", "shutterHalf=0,0,true"), Files.readAllLines(properties))
            assertEquals(listOf("profile=gfx100s-2.13-gpio-buttons-v2", "back=9,5,true", "play=9,6,true", "shutterFull=0,1,true", "shutterHalf=0,0,true"), Files.readAllLines(expanded))
            val v5 = output.resolve("gpio-buttons-v5.properties")
            assertEquals(
                listOf(
                    "profile=gfx100s-2.13-gpio-buttons-v5",
                    "afOn=0,14,true",
                    "back=9,5,true",
                    "delete=6,2,true",
                    "drive=6,1,true",
                    "focusMode=7,13,true",
                    "movieMode=7,5,true",
                    "play=9,6,true",
                    "rearDialPress=0,13,true",
                    "shutterFull=0,1,true",
                    "shutterHalf=0,0,true",
                ),
                Files.readAllLines(v5),
            )
            val v6 = output.resolve("gpio-buttons-v6.properties")
            assertEquals(
                listOf(
                    "profile=gfx100s-2.13-gpio-buttons-v6",
                    "afOn=0,14,true",
                    "back=9,5,true",
                    "delete=6,2,true",
                    "drive=6,1,true",
                    "fn4=2,13,true",
                    "focusMode=7,13,true",
                    "focusStickPress=7,9,true",
                    "frontDialPress=2,2,true",
                    "movieMode=7,5,true",
                    "play=9,6,true",
                    "rearDialPress=0,13,true",
                    "shutterFull=0,1,true",
                    "shutterHalf=0,0,true",
                ),
                Files.readAllLines(v6),
            )
            val legacy = modify(catalog) { it + ("profiles" to listOf("disabled", "gfx100s-2.13-gpio-buttons-v1")) }
            CatalogCompiler.generateGpioButtons(legacy, output)
            assertTrue(Files.exists(properties))
            assertFalse(Files.exists(expanded))
            assertFalse(Files.exists(v5))
            assertFalse(Files.exists(v6))
        } finally { output.toFile().deleteRecursively() }
    }

    @Test fun expandedProfileRejectsMissingPlayAndInvalidOrOverlappingCoordinates() {
        val catalog = CatalogCompiler.compile(Path.of(System.getProperty("catalogRoot")))
        val output = Files.createTempDirectory("gpio-catalog-invalid")
        try {
            for(change in listOf<(List<Map<String, Any?>>) -> List<Map<String, Any?>>>(
                { bindings -> bindings.filter { it["buttonKey"] != "play" } },
                { bindings -> bindings.map { if(it["buttonKey"] == "play") it + ("pin" to 5) else it } },
                { bindings -> bindings.map { if(it["buttonKey"] == "play") it + ("bank" to 16) else it } },
                { bindings -> bindings.map { if(it["buttonKey"] == "play") it + ("buttonKey" to "power") else it } }
            )) {
                val invalid = modify(catalog) { device -> device + ("ports" to rows(device["ports"]).map { port ->
                    port + ("registers" to rows(port["registers"]).map { register ->
                        if(register["id"] == "reg.soc-gpio.main.o14") register + ("connections" to change(rows(register["connections"]))) else register
                    })
                }) }
                assertFailsWith<IllegalArgumentException> { CatalogCompiler.generateGpioButtons(invalid, output) }
                assertFalse(Files.exists(output.resolve("gpio-buttons-v2.properties")))
            }
        } finally { output.toFile().deleteRecursively() }
    }

    private fun modify(catalog: AddressCatalog, change: (Map<String, Any?>) -> Map<String, Any?>) =
        AddressCatalog(catalog.data + ("devices" to rows(catalog.data["devices"]).map {
            if(it["componentId"] == "soc-gpio") change(it) else it
        }))
}
