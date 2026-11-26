package com.github.nyabanban.fuji.emulator.catalog

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ProfileWriteWidthsTest {
    private val root = Path.of(System.getProperty("catalogRoot"))
    private val profile = "gfx100s-sd-host-phy-word-retention-experimental-v5"
    private val prior = "gfx100s-sd-host-init-registers-experimental-v4"
    private val registerId = "reg.soc-sd-host.mixed6078.o0"
    private val portId = "definition.soc-sd-host.mixed6078"
    private fun catalog() = CatalogCompiler.compile(root)
    @Suppress("UNCHECKED_CAST")
    private fun mutable(data: Map<String, Any?>): MutableMap<String, Any?> =
        CatalogIO.json.readValue(CatalogIO.canonical(data), MutableMap::class.java) as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun sd(data: Map<String, Any?>) = rows(data["devices"]).single { it["componentId"] == "soc-sd-host" } as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun port(data: Map<String, Any?>) = rows(sd(data)["ports"]).single { it["id"] == portId } as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun register(data: Map<String, Any?>) = rows(port(data)["registers"]).single() as MutableMap<String, Any?>

    @Test fun exactOwnerProfileSelectsBothSlotsWithoutRelaxingBaseOrOtherAccesses() {
        val c = catalog()
        val overrides = rows(c.data["devices"]).flatMap { rows(it["ports"]) }.flatMap { rows(it["registers"]) }.filter { "writeWidthsByProfile" in it }
        assertEquals(listOf(registerId), overrides.map { it["id"] })
        for (base in listOf(0xfffc6078L, 0xfffca078L)) {
            for (binding in listOf(emptyMap(), mapOf("soc-sd-host" to prior), mapOf("soc-sd-host" to "disabled"), mapOf("soc-sd-host" to "unrecognized"), mapOf("battery" to profile))) {
                val q = c.query("cpu-physical", base, 4, "write", componentProfiles = binding)
                assertFalse(q.permitted)
                assertEquals(listOf(2), q.details["declaredWriteWidths"])
                assertEquals(listOf(2), q.details["effectiveWriteWidths"])
            }
            val binding = mapOf("soc-sd-host" to profile)
            for (width in listOf(2, 4)) {
                val q = c.query("cpu-physical", base, width, "write", componentProfiles = binding)
                assertTrue(q.permitted)
                assertEquals(profile, q.details["componentProfile"])
                assertEquals(listOf(2), q.register!!.writeWidths)
                assertEquals(listOf(2, 4), q.details["writeWidths"])
                assertEquals(listOf(2, 4), q.details["effectiveWriteWidths"])
            }
            assertFalse(c.query("cpu-physical", base, 1, "write", componentProfiles = binding).permitted)
            assertFalse(c.query("cpu-physical", base + 2, 2, "write", componentProfiles = binding).permitted)
            assertFalse(c.query("cpu-physical", base + 2, 4, "write", componentProfiles = binding).permitted)
            assertTrue(c.query("cpu-physical", base, 4, "read", componentProfiles = binding).permitted)
            assertFalse(c.query("cpu-physical", base, 2, "read", componentProfiles = binding).permitted)
            assertFalse(c.query("cpu-physical", base, 4, "execute", componentProfiles = binding).permitted)
        }
        assertFalse(c.query("cpu-physical", 0xfffc6060, 4, "write", componentProfiles = mapOf("soc-sd-host" to profile)).permitted)
    }

    @Test fun entityPropertyAndAddressQueriesExposeOneImmutableResolution() {
        val c = catalog()
        val before = CatalogIO.canonical(c.data)
        for (selected in listOf<String?>(null, prior, profile)) {
            val binding = selected?.let { mapOf("soc-sd-host" to it) }.orEmpty()
            val expected = if (selected == profile) listOf(2, 4) else listOf(2)
            val entity = assertNotNull(c.byId(registerId, binding))
            assertEquals("soc-sd-host", entity["componentId"])
            assertEquals(selected, entity["componentProfile"])
            assertEquals(listOf(2), entity["writeWidths"])
            assertEquals(listOf(2), entity["declaredWriteWidths"])
            assertEquals(expected, entity["effectiveWriteWidths"])
            for (key in listOf("writeWidths", "declaredWriteWidths", "effectiveWriteWidths", "writeWidthsByProfile")) {
                val property = c.property(registerId, key, binding)
                assertEquals(entity[key], property["value"])
                assertEquals(expected, property["effectiveWriteWidths"])
                assertEquals(selected, property["componentProfile"])
                assertEquals("soc-sd-host", property["componentId"])
                assertNotNull(property["provenance"])
            }
            assertEquals(if (selected == profile) "HYPOTHESIS" else "TEST", obj(c.property(registerId, "effectiveWriteWidths", binding)["provenance"])["confidence"])
            assertEquals("TEST", obj(c.property(registerId, "declaredWriteWidths", binding)["provenance"])["confidence"])
            val port = assertNotNull(c.byId(portId, binding))
            assertEquals(entity, rows(port["registers"]).single())
            val device = assertNotNull(c.byId("device.soc-sd-host", binding))
            assertEquals(port, rows(device["ports"]).single { it["id"] == portId })
            val field = assertNotNull(c.byId(rows(entity["fields"]).single().text("id"), binding))
            assertEquals(selected, field["componentProfile"])
            assertEquals("soc-sd-host", field["componentId"])
            val q = c.query("cpu-physical", 0xfffc6078, 4, "write", componentProfiles = binding)
            assertEquals(entity["declaredWriteWidths"], q.details["declaredWriteWidths"])
            assertEquals(entity["effectiveWriteWidths"], q.details["effectiveWriteWidths"])
            @Suppress("UNCHECKED_CAST") val writable = entity as MutableMap<String, Any?>
            assertFailsWith<UnsupportedOperationException> { writable["effectiveWriteWidths"] = listOf(1) }
        }
        assertContentEquals(before, CatalogIO.canonical(c.data))
        val register = c.query("cpu-physical", 0xfffc6078, 4).register!!
        @Suppress("UNCHECKED_CAST") val widths = register.effectiveWriteWidths(profile) as MutableList<Int>
        assertFailsWith<UnsupportedOperationException> { widths.clear() }
        @Suppress("UNCHECKED_CAST") val overrides = register.writeWidthsByProfile as MutableMap<String, List<Int>>
        assertFailsWith<UnsupportedOperationException> { overrides.clear() }
    }

    @Test fun fieldAccessInheritsOwnerRegisterAndKeepsItsOwnInitialAndDefinitionSources() {
        val c = catalog()
        val original = assertNotNull(c.byId(registerId))
        val rawField = rows(original["fields"]).single()
        val fieldId = rawField.text("id")
        val before = CatalogIO.canonical(c.data)
        for (selected in listOf<String?>(null, prior, profile)) {
            val binding = selected?.let { mapOf("soc-sd-host" to it) }.orEmpty()
            val expected = if (selected == profile) listOf(2, 4) else listOf(2)
            val register = assertNotNull(c.byId(registerId, binding))
            val field = assertNotNull(c.byId(fieldId, binding))
            assertEquals(registerId, field["registerId"])
            assertEquals(true, field["accessInheritedFromRegister"])
            assertEquals("inherits-register", field["access"])
            assertEquals(listOf(4), field["readWidths"])
            assertEquals(listOf(2), field["writeWidths"])
            assertEquals(listOf(2), field["declaredWriteWidths"])
            assertEquals(expected, field["effectiveWriteWidths"])
            for (key in listOf("readWidths", "writeWidths", "declaredWriteWidths", "effectiveWriteWidths", "writeWidthsByProfile")) {
                val inherited = c.property(fieldId, key, binding)
                val owner = c.property(registerId, key, binding)
                assertEquals(owner["value"], inherited["value"])
                assertEquals(owner["provenance"], inherited["provenance"])
                assertEquals(registerId, inherited["provenanceEntityId"])
                assertEquals("DECLARED", inherited["status"])
                assertEquals(selected, inherited["componentProfile"])
                assertEquals(inherited["provenance"], obj(field["inheritedAccessProvenance"])[key])
            }
            assertEquals(if (selected == profile) "HYPOTHESIS" else "TEST", obj(c.property(fieldId, "effectiveWriteWidths", binding)["provenance"])["confidence"])
            for (key in listOf("initial", "definition", "provenance")) {
                assertEquals(rawField[key], field[key])
                assertEquals(rawField[key], c.property(fieldId, key, binding)["value"])
                assertFalse(c.property(fieldId, key, binding).containsKey("provenanceEntityId"))
            }
            assertEquals(obj(rawField["provenance"])["initial"], c.property(fieldId, "initial", binding)["provenance"])
            for (address in listOf(0xfffc6078L, 0xfffca078L)) {
                val q = c.query("cpu-physical", address, 4, "write", componentProfiles = binding)
                assertEquals(fieldId, q.register!!.fields.single().id)
                assertEquals(field["declaredWriteWidths"], q.details["declaredWriteWidths"])
                assertEquals(field["effectiveWriteWidths"], q.details["effectiveWriteWidths"])
                assertEquals(selected == profile, q.permitted)
                val window = assertNotNull(c.byId(q.window!!.id, binding))
                assertEquals(field, rows(rows(window["registers"]).single()["fields"]).single())
                assertFalse(q.window.data.containsKey("registers"))
            }
            assertEquals(field, rows(register["fields"]).single())
        }
        assertContentEquals(before, CatalogIO.canonical(c.data))
    }

    @Test fun profileContractChangesOnlySdDescriptorAndPreservesEveryWindow() {
        val c = catalog()
        val base = mutable(c.data)
        sd(base).remove("profiles")
        register(base).remove("writeWidthsByProfile")
        val previous = AddressCatalog(base)
        for (component in c.componentIds.filter { it != "soc-sd-host" }) {
            assertContentEquals(CatalogIO.canonical(previous.componentDescriptor(component)), CatalogIO.canonical(c.componentDescriptor(component)), component)
            assertEquals(rows(base["devices"]).single {it["componentId"]==component}["profiles"],c.componentDescriptor(component)["profiles"])
        }
        val descriptor = c.componentDescriptor("soc-sd-host")
        assertEquals(sd(mutable(c.data))["profiles"], descriptor["profiles"])
        assertNotEquals(previous.componentDescriptor("soc-sd-host"), descriptor)
        val mixed = rows(rows(descriptor["definitions"]).single { it["id"] == portId }["registers"]).single()
        assertEquals(listOf(2), mixed["writeWidths"])
        assertEquals(mapOf(
            profile to listOf(2, 4),
            "gfx100s-sd-host-card-transactions-experimental-v6" to listOf(2, 4),
            "gfx100s-sd-host-address-extension-experimental-v7" to listOf(2, 4),
            "gfx100s-sd-host-vendor-phy-experimental-v8" to listOf(2, 4),
            "gfx100s-sd-host-uhs-phy-experimental-v9" to listOf(2, 4),
        ), mixed["writeWidthsByProfile"])
        assertContentEquals(CatalogIO.canonical(previous.data["windows"]), CatalogIO.canonical(c.data["windows"]))
        assertContentEquals(CatalogIO.canonical(previous.legacyFirmwareMetadata()), CatalogIO.canonical(c.legacyFirmwareMetadata()))
        assertFalse(descriptor.containsKey("artifactSha256"))
    }

    @Test fun compilerRejectsMalformedOverridesAtTheirOwnValidationBoundary() {
        val original = catalog().data
        fun rejects(message: String, edit: (MutableMap<String, Any?>) -> Unit) {
            val data = mutable(original)
            edit(data)
            val error = assertFailsWith<IllegalArgumentException> { CatalogCompiler.validate(data) }
            assertContains(error.message.orEmpty(), message)
        }
        for (bad in listOf(null, emptyMap<String, Any?>(), emptyList<Any>(), mapOf("" to listOf(2))))
            rejects("writeWidthsByProfile must be a non-empty", { register(it)["writeWidthsByProfile"] = bad })
        rejects("unknown owning-device profile") { register(it)["writeWidthsByProfile"] = mapOf("not-declared" to listOf(2, 4)) }
        for (bad in listOf(null, 4, "4"))
            rejects("widths must be an array") { register(it)["writeWidthsByProfile"] = mapOf(profile to bad) }
        for (bad in listOf(emptyList<Any>(), listOf(2, 4, 1, 2), listOf("2"), listOf(2.0), listOf(null), listOf(true)))
            rejects("requires 1..3 integer widths") { register(it)["writeWidthsByProfile"] = mapOf(profile to bad) }
        rejects("duplicate writeWidthsByProfile width") { register(it)["writeWidthsByProfile"] = mapOf(profile to listOf(2, 2L)) }
        for (bad in listOf(listOf(0), listOf(3), listOf(8)))
            rejects("invalid writeWidthsByProfile width") { register(it)["writeWidthsByProfile"] = mapOf(profile to bad) }
        rejects("register alignment invalid") { port(it)["size"] = 8; register(it)["offset"] = 2 }
        rejects("window alignment invalid") {
            @Suppress("UNCHECKED_CAST") val window = rows(it["windows"]).single { w -> w["portId"] == "soc-sd-host.mixed6078-0" } as MutableMap<String, Any?>
            window["base"] = valueLong(window["base"]) + 2
        }
        rejects("unknown owning-device profile") { sd(it).remove("profiles") }
        for (bad in listOf(null, "profile")) rejects("profiles must be an array") { sd(it)["profiles"] = bad }
        for (bad in listOf(emptyList<String>(), listOf(""), listOf("bad profile"))) rejects("1..64 non-empty valid IDs") { sd(it)["profiles"] = bad }
        rejects("duplicate device profile") { sd(it)["profiles"] = listOf(profile, profile) }
    }

    @Test fun closedSchemaAndSourceParserRejectMalformedAndDuplicateProfileMaps() {
        val schema = CatalogIO.readYaml(root.resolve("catalog/schema/register.schema.json"))
        val original = register(mutable(catalog().data))
        CatalogSchema.validate(original, schema)
        for (bad in listOf(null, emptyMap<String, Any?>(), mapOf("" to listOf(2)), mapOf(profile to emptyList<Any>()), mapOf(profile to listOf(2, 2)), mapOf(profile to listOf(3)), mapOf(profile to listOf(2.0)))) {
            assertFailsWith<IllegalArgumentException> { CatalogSchema.validate(original + mapOf("writeWidthsByProfile" to bad), schema) }
        }
        val source = Files.createTempFile("duplicate-profile-", ".yaml")
        try {
            Files.writeString(source, "{\"writeWidthsByProfile\":{\"" + profile + "\":[2],\"" + profile + "\":[4]}}\n")
            assertFails { CatalogIO.readYaml(source) }
        } finally { Files.delete(source) }
    }
}
