package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DeviceDependenciesTest {
    private val root = Path.of(System.getProperty("catalogRoot"))

    @Test fun generatedCatalogAndJarDescriptorsDeclareTheSameHardDependencies() {
        val compiled = root.resolve("catalog/compiled")
        val bytes = Files.readAllBytes(compiled.resolve("catalog.json"))
        assertContentEquals(CatalogIO.compiledBytes(CatalogCompiler.compile(root)), bytes)
        val catalog = CatalogIO.readCompiled(bytes)
        for (device in rows(catalog.data["devices"])) {
            val owner = device.text("componentId")
            val expected = if (owner == "soc-flash") listOf("flash-image") else emptyList()
            assertEquals(expected, device["depends"], owner)
            assertEquals(expected, catalog.componentDependencies(owner), owner)
            val descriptor = catalog.componentDescriptor(owner)
            assertContentEquals(CatalogIO.canonical(descriptor), Files.readAllBytes(compiled.resolve("components/$owner.json")))
            if (expected.isEmpty()) assertFalse("depends" in descriptor, owner)
            else assertEquals(expected, descriptor["depends"], owner)
        }
    }

    @Test fun historicalCompiledDeviceCanOmitDependsWhileCurrentSourceRequiresIt() {
        val catalog = CatalogCompiler.compile(root)
        val devices = rows(catalog.data["devices"]).map { it - "depends" }
        val old = CatalogIO.readCompiled(CatalogIO.compiledBytes(AddressCatalog(catalog.data + ("devices" to devices))))
        assertEquals(emptyList(), old.componentDependencies("soc-flash"))
        assertEquals(old.componentDescriptor("soc-flash"), catalog.componentDescriptor("soc-flash") - "depends")

        val schema = CatalogIO.readYaml(root.resolve("catalog/schema/device.schema.json"))
        val error = assertFailsWith<IllegalArgumentException> { CatalogSchema.validate(devices.first(), schema) }
        assertEquals("root: missing depends", error.message)
    }

    @Test fun invalidCompiledDependenciesAreRejected() {
        val catalog = CatalogCompiler.compile(root)
        fun reject(declared: Any?, reason: String) {
            val devices = rows(catalog.data["devices"]).map {
                if (it["componentId"] == "flash-image") it + ("depends" to declared) else it
            }
            val error = assertFailsWith<IllegalArgumentException> {
                CatalogCompiler.validate(catalog.data + ("devices" to devices))
            }
            assertEquals(reason, error.message)
        }
        reject(null, "flash-image depends must be an array")
        reject("soc-flash", "flash-image depends must be an array")
        reject(listOf(1), "flash-image depends must contain component IDs")
        reject(listOf("battery", "battery"), "flash-image has duplicate dependencies")
        reject(listOf("flash-image"), "Component dependency cycle: flash-image -> flash-image")
        reject(listOf("device.battery"), "Missing component dependency: flash-image -> device.battery")
        reject(listOf("soc-flash"), "Component dependency cycle: flash-image -> soc-flash -> flash-image")
    }

    @Test fun loadOrderSortsComponentsAndDependenciesAndEmitsSharedProvidersOnce() {
        val graph = mapOf(
            "standalone" to emptyList(),
            "beta" to listOf("provider"),
            "provider" to emptyList(),
            "app" to listOf("beta", "alpha"),
            "alpha" to listOf("provider"),
        )
        assertEquals(listOf("provider", "alpha", "beta", "app", "standalone"), componentLoadOrder(graph))
    }
}
