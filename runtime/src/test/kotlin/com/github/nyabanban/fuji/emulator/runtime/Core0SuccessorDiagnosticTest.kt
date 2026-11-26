package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogCompiler
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Core0SuccessorDiagnosticTest {
    private val address = 0xfff4f01cL
    private val profile = "gfx100s-sd-finite-core0-successor-diagnostic-v1"
    private val oldProfile = "gfx100s-sd-finite-f01c-retained-diagnostic-v1"
    private val context = object : DiscoveryContext {
        override fun core() = 3
        override fun tick() = 81027252L
        override fun pc() = 0x5af98L
        override fun lr() = 0x12919d0L
    }
    private fun runtime(id: String = profile, capacity: Int = 16384) = CatalogRuntime(AddressCatalog.bundled(),"discovery",Path.of("."),
        mapOf("discovery" to mapOf("profile" to id,"logCapacity" to capacity)))
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

    @Test fun independentlySelectedProfilesBindCompleteDeclarationsAndKeepDefaultAndStrict() {
        val catalog = AddressCatalog.bundled()
        val ordinary = checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(context))
        val unselected = setOf(POLL_7801C_REGION, WRITE_49530_REGION, TAIL_49500_REGION, IMAGE_RESULT_SURFACE) + retainedConfigRegions
        assertEquals(catalog.candidates.filterNot { it.id in unselected }.sortedBy { it.base }.map { it.id },ordinary.regions.map { it.id })
        for ((id,limit) in listOf(oldProfile to 4096,profile to 131072)) {
            val selected = runtime(id)
            val bus = checkNotNull(selected.createBus(context))
            assertEquals(ordinary.regions.map { it.id },bus.regions.filter { it.id != id }.map { it.id })
            assertEquals(ordinary.regions.sumOf { it.length } + 4,bus.regions.sumOf { it.length })
            val declaration = catalog.diagnosticProfiles.single { it.id == id }
            val region = bus.regions.single { it.id == id }
            assertEquals(RetainedWriteDiagnostic(1,limit),region.retainedDiagnostic)
            assertEquals(declaration.base,region.base)
            assertEquals(declaration.endExclusive,region.endExclusive)
            assertEquals(setOf(declaration.width),region.widths)
            assertEquals(declaration.evidence,region.evidence)
            assertEquals(declaration.inventoryId,region.deviceId)
            assertNull(region.initial)
            val router = MmioRouter(emptyList(),bus)
            router.write(address,4,1)
            assertEquals(1L,router.read(address,4))
            val rows = selected.observation(bus)["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
            assertTrue(rows.all { it["diagnosticRouting"] == declaration.data })
            assertNull(CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to id))).createBus(context))
        }
        assertFails { MmioRouter(emptyList(),ordinary).read(address,4) }
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"successfulReadLimit" to 1))) }
        assertFails { CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to profile,"inputs" to mapOf(profile to mapOf("strategy" to "fixed-input","value" to 0))))).createBus(context) }
    }

    @Test fun newProfileKeepsWriteFirstOneExactAccessAndNoEventLaws() {
        val selected = runtime()
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
        router.beforeInstruction(81027253L)
        assertNull(router.nextDeadline(81027253L))
        assertEquals(before,bus.snapshot())
        assertEquals(selected.observation(bus),selected.observation(bus))
        assertEquals(81027252L,context.tick())
    }

    @Test fun newProfileAllowsExactly131072ReadsThenRefusesWithoutConsumption() {
        val bus = checkNotNull(runtime(capacity=131074).createBus(context))
        val router = MmioRouter(emptyList(),bus)
        router.write(address,4,1)
        // Keep the bounded hot path primitive; materialize the large journal exactly once.
        repeat(131072) { assertEquals(1L,router.read(address,4)) }
        val failure = assertFails { router.read(address,4) }
        assertTrue(failure.message.orEmpty().contains("diagnostic-limit"))
        val snapshot = bus.snapshot()
        val rows = snapshot["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        assertEquals(131074,rows.size)
        assertEquals("diagnostic-limit",rows.last()["reason"])
        assertNull(rows.last()["value"])
        assertNull(rows.last()["before"])
        assertEquals(false,rows.last()["accepted"])
        assertEquals(131072,state(snapshot)["successfulReads"])
        assertEquals(131072,state(snapshot)["successfulReadLimit"])
        assertEquals(1L,state(snapshot)["retainedValue"])
        assertEquals(false,snapshot["truncated"])
    }

    @Test fun newProfileKeepsKnownPortPriorityAndPreAccessCapacityRefusal() {
        val region = checkNotNull(runtime().createBus(context)).regions.single { it.id == profile }
        fun bus(capacity: Int, known: List<AddressInterval> = emptyList()) = DiscoveryBus(listOf(region),known,
            DiscoveryJournal("TEST","TEST","a".repeat(64),"b".repeat(64),"c".repeat(64),capacity),context)
        val known = bus(8,listOf(AddressInterval(address,address+4)))
        var calls = 0
        val port = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long { calls++; require(size == 4); return 7 }
            override fun write(offset: Long, size: Int, value: Long) { calls++; error("known write refuses") }
        }
        val router = MmioRouter(listOf(MmioWindow("known",address,4,port)),known)
        refusal(known,"known-device-refusal") { router.write(address,4,1) }
        refusal(known,"known-device-refusal") { router.read(address,2) }
        assertEquals(7L,router.read(address,4))
        assertEquals(3,calls)
        refusal(known,"known-port-not-active") { known.access(4,address,4,true,1) }
        val unwritten = bus(1)
        refusal(unwritten,"diagnostic-read-before-write") { unwritten.access(1,address,4,false) }
        refusal(unwritten,"log-capacity") { unwritten.access(2,address,4,true,1) }
        assertNull(state(unwritten)["retainedValue"])
        val written = bus(2)
        written.access(1,address,4,true,1)
        assertEquals(1L,written.access(2,address,4,false))
        refusal(written,"log-capacity") { written.access(3,address,4,false) }
        assertEquals(1,state(written)["successfulReads"])
    }

    @Test fun expandedSchemaAndPrimitiveStillRefuseOutsideTheirFiniteBounds() {
        val catalog = AddressCatalog.bundled()
        val declarations = catalog.diagnosticProfiles.map { it.data }
        val declaration = declarations.single { it["id"] == profile }
        for (limit in listOf(0,-1,2097153)) {
            assertFailsWith<IllegalArgumentException> { RetainedWriteDiagnostic(1,limit) }
            assertFailsWith<IllegalArgumentException> {
                CatalogCompiler.validate(catalog.data + mapOf("diagnosticProfiles" to listOf(declaration + mapOf("successfulReadLimit" to limit))))
            }
        }
        for (limit in listOf(1,4096,131072,131073,262144,262145,327680,327681,409600,409601,540672,540673,544768,544769,589824,589825,598016,598017,663552,663553,696320,696321,697344,697345,1048576,1048577,2097152)) assertEquals(limit,RetainedWriteDiagnostic(1,limit).successfulReadLimit)
        assertFailsWith<IllegalArgumentException> { RetainedWriteDiagnostic(0,131072) }
        assertEquals("At most fourteen independently selected single-word diagnostics are supported",assertFailsWith<IllegalArgumentException> {
            CatalogCompiler.validate(catalog.data + mapOf("diagnosticProfiles" to declarations + (declaration + mapOf("id" to "fifteenth-diagnostic"))))
        }.message)
        assertFailsWith<IllegalArgumentException> {
            CatalogCompiler.validate(catalog.data + mapOf("diagnosticProfiles" to listOf(declaration,declaration)))
        }
    }
}
