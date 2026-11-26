package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class A300ZeroDiagnosticTest {
    private val catalog = AddressCatalog.bundled()
    private val declaration = catalog.singleReadDiagnosticProfiles.single { it.id == "gfx100s-sd-finite-a300-zero-diagnostic-v1" }
    private val address = declaration.base
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 0L
        override fun pc() = 0L
        override fun lr() = 0L
    }
    private fun runtime(profile: String = declaration.id, mode: String = "discovery", capacity: Int = 64) =
        CatalogRuntime(catalog, mode, Path.of("."), mapOf("discovery" to mapOf("profile" to profile, "logCapacity" to capacity)))
    private fun state(bus: DiscoveryBus) = (bus.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).single { it["id"] == declaration.id }
    private fun last(bus: DiscoveryBus) = (bus.snapshot()["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).last()
    private fun refusal(bus: DiscoveryBus, reason: String, action: () -> Unit) {
        val before = state(bus)
        assertFails { action() }
        assertEquals(reason, last(bus)["reason"])
        assertEquals(false, last(bus)["accepted"])
        assertEquals(before, state(bus))
    }
    @Test fun oneZeroIsOptInAndOnlyAddsOneScriptToUnchangedParent() {
        val selected = runtime()
        val bus = checkNotNull(selected.createBus(context))
        val parent = checkNotNull(runtime(declaration.parentProfile).createBus(context))
        assertEquals(parent.snapshot()["regions"], (bus.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).filter { it["id"] != declaration.id })
        val region = bus.regions.single { it.id == declaration.id }
        assertEquals(DiscoveryStrategy.SCRIPT, region.strategy)
        assertEquals(listOf(0L), region.samples)
        assertEquals(setOf(4), region.widths)
        assertNull(region.initial); assertNull(region.fixedValue); assertNull(region.retainedDiagnostic)
        val sharedPage=(address and -4096L) to 4096L
        assertEquals(sharedPage.first,catalog.windows.single { it.id=="gfx100s.soc-system-control.status-a200" }.base and -4096L)
        assertTrue(sharedPage !in parent.pageRanges())
        assertTrue(parent.known.any { (it.base and -4096L) == sharedPage.first })
        assertEquals((parent.pageRanges()+sharedPage).sortedBy { it.first },bus.pageRanges())
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        assertEquals(1, state(bus)["sampleIndex"])
        assertEquals("HYPOTHESIS", last(bus)["confidence"])
        assertEquals("scripted-read", last(bus)["valueSource"])
        refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) }
        val observation = selected.observation(bus)
        assertTrue((observation["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).all {
            it["diagnosticRouting"] == declaration.data && it["classification"] == "UNASSIGNED" && it["catalogPermitted"] == false
        })
        val before = bus.snapshot()
        router.beforeInstruction(1)
        assertNull(router.nextDeadline(1)); assertEquals(before, bus.snapshot())
        for (profile in catalog.diagnosticProfiles.map { it.id } + "gfx100s-sd-finite-v1") {
            val old = checkNotNull(runtime(profile).createBus(context))
            assertFails { MmioRouter(emptyList(), old).read(address, 4) }
            assertEquals("outside-selected-envelope", last(old)["reason"])
        }
        assertNull(runtime(mode = "strict").createBus(context))
        assertFails { CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to
            mapOf("profile" to declaration.id, "inputs" to mapOf(declaration.id to mapOf("strategy" to "scripted-read", "samples" to listOf(1)))))).createBus(context) }
    }
    @Test fun illegalAccessAndExecuteNeverConsumeTheOnlySample() {
        val bus = checkNotNull(runtime().createBus(context)); val router = MmioRouter(emptyList(), bus)
        refusal(bus, "policy-refuse-or-read-only") { router.write(address, 4, 0) }
        for (width in listOf(1, 2, 8)) {
            refusal(bus, "unsupported-width") { router.read(address, width) }
            refusal(bus, "unsupported-width") { router.write(address, width, 0) }
        }
        for (offset in listOf(1L, 2L, 3L)) refusal(bus, "unaligned") { router.read(address + offset, 4) }
        for (other in listOf(address - 4, address - 1, address + 4, address + 4092)) {
            refusal(bus, "outside-selected-envelope") { router.read(other, 4) }
        }
        val before = state(bus)
        for (kind in listOf(21, 24)) {
            router.refusedNative(kind, address, 4, 0)
            assertEquals("execute", last(bus)["direction"])
            assertEquals("native-unmapped-or-protected", last(bus)["reason"])
            assertEquals(false, last(bus)["accepted"])
        }
        assertEquals(before, state(bus)); assertEquals(0L, router.read(address, 4))
    }
    @Test fun logCapacityAndKnownIllegalAccessHavePriorityWithoutConsumingSample() {
        val bus = checkNotNull(runtime(capacity = 1).createBus(context))
        val router = MmioRouter(emptyList(), bus)
        refusal(bus, "unsupported-width") { router.read(address, 1) }
        refusal(bus, "log-capacity") { router.read(address, 4) }
        assertEquals(0, state(bus)["sampleIndex"])
        val knownBus = checkNotNull(runtime().createBus(context))
        var calls = 0
        val port = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long { calls++; error("TEST known denied") }
            override fun write(offset: Long, size: Int, value: Long) { calls++; error("TEST known denied") }
        }
        val known = MmioRouter(listOf(MmioWindow("TEST-known", address, 4, port)), knownBus)
        refusal(knownBus, "known-device-refusal") { known.read(address, 4) }
        refusal(knownBus, "known-device-refusal") { known.write(address, 4, 0) }
        refusal(knownBus, "router-preflight-refusal") { known.read(address - 2, 4) }
        refusal(knownBus, "router-preflight-refusal") { known.read(address + 2, 4) }
        assertEquals(2, calls); assertEquals(0, state(knownBus)["sampleIndex"])
        assertEquals(0L, MmioRouter(emptyList(), knownBus).read(address, 4))
    }
}
