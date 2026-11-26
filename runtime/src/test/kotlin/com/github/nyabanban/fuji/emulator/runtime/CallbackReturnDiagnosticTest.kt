package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class CallbackReturnDiagnosticTest {
    private val profile = "gfx100s-sd-finite-callback-return-diagnostic-v1"
    private val address = 0xfff4f01cL
    private val context = object : DiscoveryContext {
        override fun core() = 3
        override fun tick() = 85527252L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x12919e0L
    }
    private fun runtime(capacity: Int = 16) = CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),
        mapOf("discovery" to mapOf("profile" to profile,"logCapacity" to capacity)))

    @Test fun newSelectionBindsOnlyItsCompleteDeclarationAndCannotOverrideTheFiniteLaw() {
        val catalog = AddressCatalog.bundled()
        val selected = runtime()
        val bus = checkNotNull(selected.createBus(context))
        val ordinary = checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(context))
        assertEquals(ordinary.regions.map { it.id },bus.regions.filter { it.id != profile }.map { it.id })
        assertEquals(listOf(0xfff4f000L to 4096L),bus.pageRanges() - ordinary.pageRanges().toSet())
        val declaration = catalog.diagnosticProfiles.single { it.id == profile }
        val previous = catalog.diagnosticProfiles.single { it.id == "gfx100s-sd-finite-core0-successor-diagnostic-v1" }
        assertEquals(previous.data + mapOf("id" to profile,"successfulReadLimit" to 262144,
            "evidence" to listOf("evidence/refactor/callback-diagnostic-model-fresh-35/README.md")),declaration.data)
        val region = bus.regions.single { it.id == profile }
        assertEquals(RetainedWriteDiagnostic(1,262144),region.retainedDiagnostic)
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
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"successfulReadLimit" to 262145))) }
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"inputs" to mapOf(profile to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(context) }
    }

    @Test fun exactly262144SuccessfulReadsThenRefusesWithoutConsumption() {
        val bus = checkNotNull(runtime(262146).createBus(context))
        val router = MmioRouter(emptyList(),bus)
        router.write(address,4,1)
        repeat(262144) { assertEquals(1L,router.read(address,4)) }
        val failure = assertFails { router.read(address,4) }
        assertTrue(failure.message.orEmpty().contains("diagnostic-limit"))
        // Materialize only after the primitive hot loop; this is bounded TEST evidence.
        val snapshot = bus.snapshot()
        val rows = snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        val region = (snapshot["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single { it["id"] == profile }
        assertEquals(262146,rows.size)
        assertEquals("diagnostic-limit",rows.last()["reason"])
        assertEquals(false,rows.last()["accepted"])
        assertNull(rows.last()["value"])
        assertNull(rows.last()["before"])
        assertEquals(262144,region["successfulReads"])
        assertEquals(262144,region["successfulReadLimit"])
        assertEquals(1L,region["retainedValue"])
        assertEquals(false,snapshot["truncated"])
    }
}
