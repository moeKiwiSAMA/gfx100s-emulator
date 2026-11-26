package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class RetainedDiagnosticTest {
    private val address = 0xfff4f01cL
    private val profile = "gfx100s-sd-finite-f01c-retained-diagnostic-v1"
    private class Context : DiscoveryContext {
        var selectedCore = 3
        override fun core() = selectedCore
        override fun tick() = 79690398L
        override fun pc() = 0x5af98L
        override fun lr() = 0x12919d0L
    }
    private fun runtime() = CatalogRuntime(AddressCatalog.bundled(), "discovery", Path.of("."),
        mapOf("discovery" to mapOf("profile" to profile)))
    private fun state(bus: DiscoveryBus) = (bus.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single { it["id"] == profile }
    private fun last(bus: DiscoveryBus) = (bus.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
    private fun expectRefusal(bus: DiscoveryBus, reason: String, action: () -> Unit) {
        val before = state(bus)
        assertFails { action() }
        assertEquals(reason, last(bus)["reason"])
        assertEquals(false, last(bus)["accepted"])
        assertEquals(before, state(bus))
    }

    @Test fun explicitProfileAddsOnlyOneWordAndDefaultAndStrictKeepTheOldRoutes() {
        val catalog = AddressCatalog.bundled()
        val old = checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(Context()))
        val selected = runtime()
        val bus = checkNotNull(selected.createBus(Context()))
        assertEquals(catalog.candidates.sortedBy { it.base }.map { it.id }, old.regions.map { it.id })
        assertEquals(old.regions.size + 1, bus.regions.size)
        assertEquals(old.regions.sumOf { it.length } + 4, bus.regions.sumOf { it.length })
        assertFalse(old.pageRanges().any { address in it.first until it.first + it.second })
        assertEquals(listOf(0xfff4f000L to 4096L), bus.pageRanges() - old.pageRanges().toSet())
        val query = mapOf("address" to "0xFFF4F01C", "width" to "4", "direction" to "write")
        assertEquals("UNASSIGNED", selected.query(query)["classification"])
        assertEquals(false, selected.query(query)["permitted"])
        assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to profile))).createBus(Context()))
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"successfulReadLimit" to 4097))) }
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"inputs" to mapOf(profile to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(Context()) }
    }

    @Test fun readBeforeWriteRefusesAndFirstGuestWriteHasUnknownPriorValueAndNoEvents() {
        val context = Context()
        val runtime = runtime()
        val bus = checkNotNull(runtime.createBus(context))
        val router = MmioRouter(emptyList(), bus)
        assertNull(state(bus)["initialValue"])
        assertNull(state(bus)["retainedValue"])
        expectRefusal(bus,"diagnostic-read-before-write") { router.read(address,4) }
        router.write(address,4,1)
        assertNull(last(bus)["before"])
        assertEquals(1L, last(bus)["after"])
        assertEquals("guest-write", last(bus)["valueSource"])
        val written = bus.snapshot()
        repeat(3) { router.beforeInstruction(79690399L + it); assertNull(router.nextDeadline(79690399L + it)) }
        assertEquals(written, bus.snapshot())
        context.selectedCore = 0
        assertEquals(1L, router.read(address,4))
        val observed = runtime.observation(bus)
        assertEquals(observed, runtime.observation(bus))
        val row = (observed["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
        assertEquals(0, row["core"])
        assertEquals("UNASSIGNED", row["classification"])
        assertEquals(false, row["catalogPermitted"])
        val routing = row["diagnosticRouting"] as Map<*, *>
        assertEquals("HYPOTHESIS", routing["confidence"])
        assertNull(routing["physicalAddress"])
        assertNull(routing["reset"])
        assertNull(routing["dataEffect"])
        assertEquals(false, state(bus)["automaticEvents"])
        assertEquals(79690398L, context.tick())
    }

    @Test fun fullPageTransportNeverGrantsAdjacentOrWrongWidthOrOtherWriteValues() {
        val bus = checkNotNull(runtime().createBus(Context()))
        val router = MmioRouter(emptyList(), bus)
        for (other in listOf(0xfff4f000L,address-4,address+4,0xfff4fffCL)) {
            expectRefusal(bus,"outside-selected-envelope") { router.read(other,4) }
            expectRefusal(bus,"outside-selected-envelope") { router.write(other,4,1) }
        }
        for (width in listOf(1,2,8)) {
            expectRefusal(bus,"unsupported-width") { router.read(address,width) }
            expectRefusal(bus,"unsupported-width") { router.write(address,width,1) }
        }
        expectRefusal(bus,"unaligned") { router.read(address+1,4) }
        expectRefusal(bus,"unaligned") { router.write(address+2,4,1) }
        for (value in listOf(0L,2L,0xffffffffL)) expectRefusal(bus,"diagnostic-write-value") { router.write(address,4,value) }
        expectRefusal(bus,"input-does-not-fit-width") { router.write(address,4,0x100000000L) }
        router.write(address,4,1)
        for (value in listOf(0L,1L,2L)) expectRefusal(bus,"diagnostic-repeated-write") { router.write(address,4,value) }
        assertEquals(1L, router.read(address,4))
        val before = state(bus)
        router.refusedNative(21,address,4,0)
        assertEquals("execute", last(bus)["direction"])
        assertEquals(false, last(bus)["accepted"])
        assertEquals(before, state(bus))
    }

    @Test fun exactly4096ReadsThenDiagnosticLimitRefusesWithoutChangingTheRetainedState() {
        val bus = checkNotNull(runtime().createBus(Context()))
        val router = MmioRouter(emptyList(), bus)
        router.write(address,4,1)
        repeat(4096) { assertEquals(1L,router.read(address,4)) }
        assertEquals(4096, state(bus)["successfulReads"])
        assertEquals(1L, state(bus)["retainedValue"])
        val before = state(bus)
        val failure = assertFails { router.read(address,4) }
        assertTrue(failure.message.orEmpty().contains("diagnostic-limit"))
        assertEquals("diagnostic-limit", last(bus)["reason"])
        assertNull(last(bus)["value"])
        assertNull(last(bus)["before"])
        assertEquals(before, state(bus))
        assertEquals(4098, (bus.snapshot()["accesses"] as List<*>).size)
        assertEquals(false, bus.snapshot()["truncated"])
    }

    @Test fun knownPortIllegalAccessAndJournalCapacityCannotFallThroughOrCommit() {
        val region = checkNotNull(runtime().createBus(Context())).regions.single { it.id == profile }
        fun bus(capacity: Int, known: List<AddressInterval> = emptyList()) = DiscoveryBus(listOf(region),known,
            DiscoveryJournal("TEST","TEST","a".repeat(64),"b".repeat(64),"c".repeat(64),capacity),Context())
        val known = bus(8,listOf(AddressInterval(address,address+4)))
        var calls = 0
        val port = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long { calls++; require(size==4); return 7 }
            override fun write(offset: Long, size: Int, value: Long) { calls++; error("known write refuses") }
        }
        val router = MmioRouter(listOf(MmioWindow("known",address,4,port)),known)
        expectRefusal(known,"known-device-refusal") { router.write(address,4,1) }
        expectRefusal(known,"known-device-refusal") { router.read(address,2) }
        assertEquals(7L,router.read(address,4))
        assertEquals(3,calls)
        expectRefusal(known,"known-port-not-active") { known.access(4,address,4,true,1) }
        val tiny = bus(1)
        expectRefusal(tiny,"diagnostic-read-before-write") { tiny.access(1,address,4,false) }
        expectRefusal(tiny,"log-capacity") { tiny.access(2,address,4,true,1) }
    }

    @Test fun catalogRefusesBroadOrCompletionLikeDiagnosticDeclarations() {
        val catalog = AddressCatalog.bundled()
        val original = catalog.diagnosticProfiles.single { it.id == profile }.data
        val invalid = listOf("width" to 2,"routeEndExclusive" to address+4096,"initialValue" to 0,
            "physicalAddress" to address,"reset" to 0,"dataEffect" to "ACK","automaticEvents" to true,
            // The shared schema now admits independently selected profiles up to2097152 reads;
            // the old selected profile still exhausts at 4097 in the behavioral test above.
            "repeatWrite" to "allow","writeValue" to 0,"successfulReadLimit" to 2097153,
            "confidence" to "DAT","routeBase" to 0xfffc4000L)
        for ((key,value) in invalid) assertFailsWith<IllegalArgumentException>(key) {
            CatalogCompiler.validate(catalog.data + mapOf("diagnosticProfiles" to listOf(original + mapOf(key to value))))
        }
    }
}
