package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class ParentReturnDiagnosticTest {
    private val profile = "gfx100s-sd-finite-parent-return-diagnostic-v1"
    private val address = 0xfff4f01cL
    private val context = object : DiscoveryContext {
        override fun core() = 3
        override fun tick() = 85527252L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x12919e0L
    }
    private fun runtime(capacity: Int = 16) = CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),
        mapOf("discovery" to mapOf("profile" to profile,"logCapacity" to capacity)))

    private fun state(snapshot: Map<String, Any?>) = (snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single { it["id"] == profile }
    private fun state(bus: DiscoveryBus) = state(bus.snapshot())
    private fun last(bus: DiscoveryBus) = (bus.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
    private fun refusal(bus: DiscoveryBus, reason: String, action: () -> Unit) {
        val before = state(bus)
        assertFails { action() }
        val after = bus.snapshot()
        val row = (after["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
        assertEquals(reason,row["reason"])
        assertEquals(false,row["accepted"])
        assertEquals(before,state(after))
    }

    @Test fun newSelectionBindsOnlyItsCompleteDeclarationAndCannotOverrideTheFiniteLaw() {
        val catalog = AddressCatalog.bundled()
        val selected = runtime()
        val bus = checkNotNull(selected.createBus(context))
        val ordinary = checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(context))
        assertEquals(ordinary.regions.map { it.id },bus.regions.filter { it.id != profile }.map { it.id })
        assertEquals(listOf(0xfff4f000L to 4096L),bus.pageRanges() - ordinary.pageRanges().toSet())
        val declaration = catalog.diagnosticProfiles.single { it.id == profile }
        val previous = catalog.diagnosticProfiles.single { it.id == "gfx100s-sd-finite-callback-return-diagnostic-v1" }
        assertEquals(previous.data + mapOf("id" to profile,"successfulReadLimit" to 327680,
            "evidence" to listOf("evidence/refactor/parent-return-model-fresh-38/README.md")),declaration.data)
        val region = bus.regions.single { it.id == profile }
        assertEquals(RetainedWriteDiagnostic(1,327680),region.retainedDiagnostic)
        assertEquals(declaration.evidence,region.evidence)
        assertNull(region.initial)
        val router = MmioRouter(emptyList(),bus)
        assertFails { router.read(address,4) }
        assertFails { router.write(address,4,0) }
        router.write(address,4,1)
        assertEquals(1L,router.read(address,4))
        assertFails { router.write(address,4,1) }
        val rows = selected.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertTrue(rows.all { it["diagnosticRouting"] == declaration.data })
        assertEquals(listOf("diagnostic-read-before-write","diagnostic-write-value",null,null,"diagnostic-repeated-write"),rows.map { it["reason"] })
        val before = bus.snapshot()
        router.beforeInstruction(85527253L)
        assertNull(router.nextDeadline(85527253L))
        assertEquals(before,bus.snapshot())
        assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to profile))).createBus(context))
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"successfulReadLimit" to 327681))) }
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"inputs" to mapOf(profile to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(context) }
    }

    @Test fun exactly327680SuccessfulReadsThenRefusesWithoutConsumption() {
        val bus = checkNotNull(runtime(327682).createBus(context))
        val router = MmioRouter(emptyList(),bus)
        router.write(address,4,1)
        repeat(327680) { assertEquals(1L,router.read(address,4)) }
        val failure = assertFails { router.read(address,4) }
        assertTrue(failure.message.orEmpty().contains("diagnostic-limit"))
        // Materialize only after the primitive hot loop; this is bounded TEST evidence.
        val snapshot = bus.snapshot()
        val rows = snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        val region = (snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single { it["id"] == profile }
        assertEquals(327682,rows.size)
        assertEquals("diagnostic-limit",rows.last()["reason"])
        assertEquals(false,rows.last()["accepted"])
        assertNull(rows.last()["value"])
        assertNull(rows.last()["before"])
        assertEquals(327680,region["successfulReads"])
        assertEquals(327680,region["successfulReadLimit"])
        assertEquals(1L,region["retainedValue"])
        assertEquals(false,snapshot["truncated"])
    }
    @Test fun newProfileKeepsWriteFirstOneExactAccessAndNoEventLaws() {
        val selected = runtime(64)
        val bus = checkNotNull(selected.createBus(context))
        val router = MmioRouter(emptyList(),bus)
        refusal(bus,"diagnostic-read-before-write") { router.read(address,4) }
        for (other in listOf(address-4,address+4,0xfff4f000L,0xfff4fffCL)) {
            refusal(bus,"outside-selected-envelope") { router.read(other,4) }
            refusal(bus,"outside-selected-envelope") { router.write(other,4,1) }
        }
        for (width in listOf(1,2,8)) {
            refusal(bus,"unsupported-width") { router.read(address,width) }
            refusal(bus,"unsupported-width") { router.write(address,width,1) }
        }
        refusal(bus,"unaligned") { router.read(address+1,4) }
        refusal(bus,"unaligned") { router.write(address+2,4,1) }
        for (value in listOf(0L,2L,0xffffffffL)) refusal(bus,"diagnostic-write-value") { router.write(address,4,value) }
        refusal(bus,"input-does-not-fit-width") { router.write(address,4,0x100000000L) }
        router.write(address,4,1)
        assertNull(last(bus)["before"])
        assertEquals(1L,last(bus)["after"])
        for (value in listOf(0L,1L,2L)) refusal(bus,"diagnostic-repeated-write") { router.write(address,4,value) }
        assertEquals(1L,router.read(address,4))
        val before = bus.snapshot()
        router.beforeInstruction(85527253L)
        assertNull(router.nextDeadline(85527253L))
        assertEquals(before,bus.snapshot())
        assertEquals(selected.observation(bus),selected.observation(bus))
        assertEquals(85527252L,context.tick())
    }

    @Test fun historicalScopeOnlyAcceptsTheCompleteSingleNewRow() {
        val profiles = Type3ReturnDiagnosticQualification.profilesBeforeType3Return(AddressCatalog.bundled().diagnosticProfiles.map { it.data })
        val expected = profiles.filter { it["id"] != profile }
        assertEquals(expected,ParentReturnDiagnosticQualification.profilesBeforeParentReturn(profiles))
        assertEquals(expected,ParentReturnDiagnosticQualification.profilesBeforeParentReturn(expected))
        val row = profiles.single { it["id"] == profile }
        // Invoke the scope check directly so an unrelated parser cannot mask its rejection.
        for ((key,value) in listOf("successfulReadLimit" to 327679,"evidence" to listOf("other-evidence"),
            "routeBase" to address+4,"routeEndExclusive" to address+8,"inventoryId" to "other-inventory")) {
            val changed = profiles.map { if (it["id"] == profile) it + mapOf(key to value) else it }
            val failure = assertFailsWith<IllegalArgumentException>(key) {
                ParentReturnDiagnosticQualification.profilesBeforeParentReturn(changed)
            }
            assertEquals("Complete parent-return declaration differs",failure.message)
        }
        val coordinatedChange = profiles.map {
            if (it["id"] in setOf(profile,"gfx100s-sd-finite-callback-return-diagnostic-v1"))
                it + mapOf("routeBase" to address+4,"routeEndExclusive" to address+8) else it
        }
        val coordinatedRefusal = assertFailsWith<IllegalArgumentException> {
            ParentReturnDiagnosticQualification.profilesBeforeParentReturn(coordinatedChange)
        }
        assertEquals("Complete parent-return declaration differs",coordinatedRefusal.message)
        for (changed in listOf(profiles+row,profiles.filter { it["id"] != "gfx100s-sd-finite-f01c-retained-diagnostic-v1" })) {
            val failure = assertFailsWith<IllegalArgumentException> { ParentReturnDiagnosticQualification.profilesBeforeParentReturn(changed) }
            assertEquals("Parent-return declaration set differs",failure.message)
        }
    }
}
