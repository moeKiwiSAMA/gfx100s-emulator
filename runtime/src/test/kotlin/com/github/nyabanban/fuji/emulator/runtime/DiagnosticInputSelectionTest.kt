package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class DiagnosticInputSelectionTest {
    private val catalog = AddressCatalog.bundled()
    private val declaration = catalog.singleReadDiagnosticProfiles.single { it.id == "gfx100s-sd-finite-a300-zero-diagnostic-v1" }
    private val address = declaration.base
    private val context = object : DiscoveryContext {
        override fun core() = 0
        override fun tick() = 99L
        override fun pc() = 0x5afe0L
        override fun lr() = 0x11f1c78L
    }
    private fun input(samples: Any? = listOf(0, 0)): Map<String, Any?> =
        mapOf("strategy" to "scripted-read", "confidence" to "HYPOTHESIS", "samples" to samples)
    private fun config(inputs: Any?, profile: String = declaration.id): Map<String, Any> =
        mapOf("discovery" to mapOf("profile" to profile, "logCapacity" to 256, "diagnosticInputs" to inputs))
    private fun runtime(samples: Any? = listOf(0, 0), mode: String = "discovery") =
        CatalogRuntime(catalog, mode, Path.of("."), config(mapOf(declaration.id to input(samples))))
    private fun defaultRuntime() = CatalogRuntime(catalog, "discovery", Path.of("."),
        mapOf("discovery" to mapOf("profile" to declaration.id, "logCapacity" to 256)))
    private fun region(bus: DiscoveryBus, id: String = declaration.id) =
        (bus.snapshot().getValue("regions") as List<*>).map { it as Map<*, *> }.single { it["id"] == id }
    private fun accesses(observation: Map<String, Any?>) =
        (observation.getValue("accesses") as List<*>).map { it as Map<*, *> }
    private fun refusal(bus: DiscoveryBus, reason: String, action: () -> Unit) {
        val before = region(bus)
        assertFails { action() }
        assertEquals(before, region(bus))
        val access = accesses(bus.snapshot()).last()
        assertEquals(reason, access["reason"])
        assertEquals(false, access["accepted"])
    }

    @Test fun explicitTwoSamplesAreConsumedExactlyAndRoutingDistinguishesTheCatalogPrefix() {
        for (samples in listOf(listOf(0L, 0L), listOf(0L, 1L))) {
            val runtime = runtime(samples)
            val bus = checkNotNull(runtime.createBus(context))
            val router = MmioRouter(emptyList(), bus)
            val selection = mapOf(declaration.id to input(samples))
            val initial = runtime.observation(bus)
            assertEquals(selection, initial["diagnosticInputs"])
            assertEquals(selection, runtime.boundedObservation(bus, 1)["diagnosticInputs"])
            assertTrue(accesses(initial).isEmpty())
            assertEquals(0, region(bus)["sampleIndex"])
            samples.forEachIndexed { index, value ->
                assertEquals(value, router.read(address, 4))
                assertEquals(index + 1, region(bus)["sampleIndex"])
            }
            repeat(2) { refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) } }
            val observation = runtime.observation(bus)
            assertEquals(selection, observation["diagnosticInputs"])
            for (access in accesses(observation)) {
                val routing = access["diagnosticRouting"] as Map<*, *>
                assertEquals<Map<*, *>>(declaration.data - "samples", routing - "samples" - "declaredSamples")
                assertEquals(listOf(0L), routing["declaredSamples"])
                assertEquals(samples, routing["samples"])
                assertEquals("refuse", routing["exhaustion"])
                assertEquals(false, access["catalogPermitted"])
            }
            assertEquals(listOf(0L), declaration.samples)
            assertEquals(declaration.data, catalog.singleReadDiagnosticProfiles.single { it.id == declaration.id }.data)
        }
    }

    @Test fun omittedSelectionKeepsTheSingleZeroAndOriginalObservationStructure() {
        val runtime = defaultRuntime()
        val bus = checkNotNull(runtime.createBus(context))
        val beforeQuery = runtime.observation(bus)
        runtime.diagnosticInputSelection(bus, declaration.id)
        assertEquals(beforeQuery, runtime.observation(bus))
        assertFalse(runtime.observation(bus).containsKey("diagnosticInputs"))
        assertFalse(runtime.observation(bus).containsKey("diagnosticInputSelections"))
        assertFalse(region(bus).containsKey("scriptInputSelection"))
        assertFalse(runtime.boundedObservation(bus, 1).containsKey("diagnosticInputs"))
        assertEquals(declaration.samples, bus.regions.single { it.id == declaration.id }.samples)
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) }
        assertTrue(accesses(runtime.observation(bus)).all { it["diagnosticRouting"] == declaration.data })
        val empty = CatalogRuntime(catalog, "discovery", Path.of("."), config(emptyMap<String, Any>()))
        val emptyBus = checkNotNull(empty.createBus(context))
        assertEquals(emptyMap<String, Any>(), empty.observation(emptyBus)["diagnosticInputs"])
        assertEquals(declaration.samples, emptyBus.regions.single { it.id == declaration.id }.samples)
        assertEquals(0L, MmioRouter(emptyList(), emptyBus).read(address, 4))
        assertTrue(accesses(empty.observation(emptyBus)).all { it["diagnosticRouting"] == declaration.data })
    }

    private fun assertTailRejected(runtime: CatalogRuntime, bus: DiscoveryBus, action: () -> Unit) {
        val before = runtime.observation(bus)
        val selection = runtime.diagnosticInputSelection(bus, declaration.id)
        val definitions = bus.regions.toList()
        assertFails { action() }
        assertEquals(before, runtime.observation(bus))
        assertEquals(selection, runtime.diagnosticInputSelection(bus, declaration.id))
        assertEquals(definitions, bus.regions)
    }

    @Test fun ownerGateSelectsOneFiniteTailWithoutRewritingPriorAccessesOrParentLayout() {
        for (value in listOf(0L, 1L)) {
            val runtime = defaultRuntime()
            val bus = checkNotNull(runtime.createBus(context))
            val router = MmioRouter(emptyList(), bus)
            val definitions = bus.regions.toList()
            val pages = bus.pageRanges()
            assertEquals(0L, router.read(address, 4))
            val previousAccesses = accesses(runtime.observation(bus))
            val gate = runtime.diagnosticInputSelection(bus, declaration.id)
            assertEquals(1, assertIs<Int>(gate["sampleIndex"]))
            assertEquals(listOf(0L), gate["samples"])
            assertTrue((gate["samples"] as List<*>).all { it is Long })
            assertEquals(false, assertIs<Boolean>(gate["tailSelected"]))
            assertEquals(1L, assertIs<Long>(gate["attemptedAccesses"]))
            val samples = mutableListOf(0L, value)
            val selected = runtime.selectDiagnosticInputTail(bus, declaration.id, 1, samples)
            samples.clear()
            assertEquals(1, selected["sampleIndex"])
            assertEquals(1L, selected["attemptedAccesses"])
            assertEquals(true, selected["tailSelected"])
            assertEquals(listOf(0L, value), selected["samples"])
            assertEquals(listOf(0L), selected["declaredSamples"])
            assertEquals(listOf(0L), selected["configuredSamples"])
            assertEquals(listOf(0L), selected["initialSamples"])
            assertEquals(1, selected["selectedAtSampleIndex"])
            assertEquals(1, selected["selectedAtJournalIndex"])
            assertEquals(previousAccesses, accesses(runtime.observation(bus)))
            assertEquals(mapOf(declaration.id to input(listOf(0L, value))), runtime.observation(bus)["diagnosticInputs"])
            assertEquals(mapOf(declaration.id to selected), runtime.observation(bus)["diagnosticInputSelections"])
            assertEquals(pages, bus.pageRanges())
            assertEquals(definitions, bus.regions.map { current ->
                if (current.id == declaration.id) current.copy(samples = listOf(0L)) else current
            })
            assertEquals(value, router.read(address, 4))
            assertTailRejected(runtime, bus) {
                runtime.selectDiagnosticInputTail(bus, declaration.id, 2, listOf(0L, value, 0L))
            }
            val observation = runtime.observation(bus)
            assertEquals(previousAccesses.single(), accesses(observation).first())
            val routing = accesses(observation).last()["diagnosticRouting"] as Map<*, *>
            assertEquals(listOf(0L), routing["declaredSamples"])
            assertEquals(listOf(0L, value), routing["samples"])
            assertEquals(accesses(observation).last(), accesses(runtime.boundedObservation(bus, 1)).single())
            repeat(2) {
                assertFailsWith<IllegalStateException> { router.read(address, 4) }
                assertEquals("finite-input-missing-or-exhausted", accesses(bus.snapshot()).last()["reason"])
                assertEquals(2, runtime.diagnosticInputSelection(bus, declaration.id)["sampleIndex"])
            }
        }
    }

    @Test fun invalidTailRequestsAreAtomicAndDoNotUseTheSingleSelection() {
        val runtime = defaultRuntime()
        val bus = checkNotNull(runtime.createBus(context))
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        for (cursor in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            assertTailRejected(runtime, bus) {
                runtime.selectDiagnosticInputTail(bus, declaration.id, cursor, listOf(0L, 1L))
            }
        }
        val invalid = listOf(
            emptyList<Any?>(), listOf(0L), List(65) { 0L }, listOf(1L, 0L),
            listOf(0L, -1L), listOf(0L, 0x1_0000_0000L),
            listOf(0L, null), listOf(0L, 1.0), listOf(0L, "1")
        )
        for (samples in invalid) {
            assertTailRejected(runtime, bus) {
                @Suppress("UNCHECKED_CAST")
                runtime.selectDiagnosticInputTail(bus, declaration.id, 1, samples as List<Long>)
            }
        }
        runtime.selectDiagnosticInputTail(bus, declaration.id, 1, listOf(0L, 1L))
        assertEquals(1L, router.read(address, 4))
    }

    @Test fun tailSelectionPreservesConsumedConfiguredPrefixAndFreezesTheNewTail() {
        val runtime = runtime(listOf(0L, 1L, 9L))
        val bus = checkNotNull(runtime.createBus(context))
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        assertEquals(1L, router.read(address, 4))
        val history = accesses(runtime.observation(bus))
        assertTailRejected(runtime, bus) {
            runtime.selectDiagnosticInputTail(bus, declaration.id, 2, listOf(0L, 0L, 2L))
        }
        val samples = MutableList(64) { if (it == 0) 0L else if (it == 1) 1L else 0xffffffffL }
        val selected = runtime.selectDiagnosticInputTail(bus, declaration.id, 2, samples)
        samples[2] = 7L
        assertEquals(listOf(0L, 1L, 9L), selected["configuredSamples"])
        assertEquals(listOf(0L, 1L, 9L), selected["initialSamples"])
        assertEquals(history, accesses(runtime.observation(bus)))
        repeat(62) { assertEquals(0xffffffffL, router.read(address, 4)) }
        assertEquals(64, runtime.diagnosticInputSelection(bus, declaration.id)["sampleIndex"])
        assertEquals(history, accesses(runtime.observation(bus)).take(2))
        assertFailsWith<IllegalStateException> { router.read(address, 4) }
        assertEquals("finite-input-missing-or-exhausted", accesses(bus.snapshot()).last()["reason"])
    }

    @Test fun tailSelectionRequiresTheOwningRuntimeAndSelectedSingleReadProfile() {
        val runtime = defaultRuntime()
        val bus = checkNotNull(runtime.createBus(context))
        val otherRuntime = defaultRuntime()
        val foreignBus = checkNotNull(otherRuntime.createBus(context))
        for (id in listOf("unknown", declaration.parentProfile, catalog.singleReadDiagnosticProfiles.first { it.id != declaration.id }.id)) {
            assertTailRejected(runtime, bus) {
                runtime.selectDiagnosticInputTail(bus, id, 0, listOf(0L, 1L))
            }
            assertFailsWith<IllegalArgumentException> { runtime.diagnosticInputSelection(bus, id) }
        }
        assertTailRejected(otherRuntime, foreignBus) {
            runtime.selectDiagnosticInputTail(foreignBus, declaration.id, 0, listOf(0L, 1L))
        }
        val strict = this.runtime(mode = "strict")
        val retained = CatalogRuntime(catalog, "discovery", Path.of("."),
            mapOf("discovery" to mapOf("profile" to declaration.parentProfile)))
        for (ineligible in listOf(strict, retained)) {
            assertTailRejected(runtime, bus) {
                ineligible.selectDiagnosticInputTail(bus, declaration.id, 0, listOf(0L, 1L))
            }
        }
        assertEquals(0L, MmioRouter(emptyList(), bus).read(address, 4))
        runtime.selectDiagnosticInputTail(bus, declaration.id, 1, listOf(0L, 1L))
    }

    @Test fun refusedAccessesAndClosedBusesCannotBeReopenedByTailSelection() {
        for (failure in listOf("exhausted", "width", "write", "hole", "native", "closed", "capacity")) {
            val runtime = CatalogRuntime(catalog, "discovery", Path.of("."),
                mapOf("discovery" to mapOf("profile" to declaration.id, "logCapacity" to if (failure == "capacity") 1 else 256)))
            val bus = checkNotNull(runtime.createBus(context))
            val router = MmioRouter(emptyList(), bus)
            assertEquals(0L, router.read(address, 4))
            when (failure) {
                "closed" -> bus.close()
                "native" -> router.refusedNative(21, address, 4, 0)
                else -> assertFailsWith<IllegalStateException> {
                    when (failure) {
                        "width" -> router.read(address, 2)
                        "write" -> router.write(address, 4, 0)
                        "hole" -> router.read(address + 4, 4)
                        else -> router.read(address, 4)
                    }
                }
            }
            assertTailRejected(runtime, bus) {
                runtime.selectDiagnosticInputTail(bus, declaration.id, 1, listOf(0L, 1L))
            }
            assertEquals(false, runtime.diagnosticInputSelection(bus, declaration.id)["tailSelected"])
        }
    }

    @Test fun strictCreatesNoBusAndReadOnlyQueriesAndObservationsNeverConsume() {
        val strict = runtime(mode = "strict")
        assertNull(strict.createBus(context))
        assertEquals(false, strict.query(mapOf("address" to address.toString(), "width" to "4", "direction" to "read"))["permitted"])
        val runtime = runtime(listOf(0, 1))
        val bus = checkNotNull(runtime.createBus(context))
        val before = bus.snapshot()
        for (direction in listOf("read", "write", "execute")) {
            val query = mapOf("address" to address.toString(), "width" to "4", "direction" to direction)
            assertEquals(defaultRuntime().query(query), runtime.query(query))
            runtime.observation(bus)
            runtime.boundedObservation(bus, 1)
        }
        assertEquals(before, bus.snapshot())
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        runtime.query(mapOf("address" to address.toString(), "direction" to "read"))
        assertEquals(1L, router.read(address, 4))
    }

    @Test fun illegalWidthsDirectionsAlignmentAndNeighborHolesNeverConsumeSelectedSamples() {
        val runtime = runtime(listOf(0, 1))
        val bus = checkNotNull(runtime.createBus(context))
        val router = MmioRouter(emptyList(), bus)
        for (width in listOf(1, 2, 8)) {
            refusal(bus, "unsupported-width") { router.read(address, width) }
            refusal(bus, "unsupported-width") { router.write(address, width, 0) }
        }
        refusal(bus, "policy-refuse-or-read-only") { router.write(address, 4, 0) }
        for (offset in listOf(1L, 2L, 3L)) refusal(bus, "unaligned") { router.read(address + offset, 4) }
        for (other in listOf(address - 4, address - 1, address + 4, address + 4092)) {
            refusal(bus, "outside-selected-envelope") { router.read(other, 4) }
        }
        val before = region(bus)
        for (kind in listOf(21, 24)) {
            router.refusedNative(kind, address, 4, 0)
            assertEquals("execute", accesses(bus.snapshot()).last()["direction"])
            assertEquals(false, accesses(bus.snapshot()).last()["accepted"])
        }
        assertEquals(before, region(bus))
        assertEquals(0L, router.read(address, 4))
        assertEquals(1L, router.read(address, 4))
        refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) }
    }

    @Test fun knownDeviceRefusalsCannotFallBackToDiagnosticSamples() {
        val bus = checkNotNull(runtime().createBus(context))
        var called = 0
        val device = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long { called++; error("TEST known denied") }
            override fun write(offset: Long, size: Int, value: Long) { called++; error("TEST known denied") }
        }
        val router = MmioRouter(listOf(MmioWindow("TEST-known", address, 4, device)), bus)
        refusal(bus, "known-device-refusal") { router.read(address, 4) }
        refusal(bus, "known-device-refusal") { router.write(address, 4, 0) }
        assertEquals(2, called)
        assertEquals(0, region(bus)["sampleIndex"])
    }

    @Test fun oneAnd64SamplesAcceptExactU32BoundsWithoutRepeatLast() {
        for (samples in listOf(listOf(0L), List(64) { if (it == 0) 0L else 0xffffffffL })) {
            val bus = checkNotNull(runtime(samples).createBus(context))
            val router = MmioRouter(emptyList(), bus)
            samples.forEach { value -> assertEquals(value, router.read(address, 4)) }
            refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) }
            assertEquals(samples.size, region(bus)["sampleIndex"])
        }
    }

    @Test fun prefixNullTypeBoundsAndUnsupportedSelectionKeysAreRejected() {
        val invalidSamples = listOf(
            null, emptyList<Any>(), List(65) { 0 }, listOf(1, 0), listOf(0, null),
            listOf(0, -1), listOf(0, 0x1_0000_0000L), listOf(0, Long.MAX_VALUE),
            listOf(0.0, 0), listOf(0, 1.0), listOf("0", 0), listOf(0, "0x1"),
            listOf(0, true), listOf(0, listOf(1)), 0, "0,0"
        )
        for (samples in invalidSamples) for (mode in listOf("discovery", "strict")) {
            assertFailsWith<IllegalArgumentException> { runtime(samples, mode) }
        }
        val valid = input()
        val invalidInputs = listOf(
            null, "scripted-read", valid - "samples", valid - "strategy", valid - "confidence",
            valid + ("samples" to null), valid + ("strategy" to "retained-bytes"),
            valid + ("confidence" to "DAT"), valid + ("repeatLast" to true),
            valid + ("routeBase" to address + 4), valid + ("width" to 8),
            valid + ("parentProfile" to "changed"), valid + ("successfulReadLimit" to 64)
        )
        for (input in invalidInputs) {
            assertFailsWith<IllegalArgumentException> {
                CatalogRuntime(catalog, "discovery", Path.of("."), config(mapOf(declaration.id to input)))
            }
        }
        for (inputs in listOf(null, emptyList<Any>(), "missing-map")) {
            assertFailsWith<IllegalArgumentException> { CatalogRuntime(catalog, "discovery", Path.of("."), config(inputs)) }
        }
    }

    @Test fun onlyTheCurrentlySelectedSingleReadIdCanReceiveAnExplicitSequence() {
        val other = catalog.singleReadDiagnosticProfiles.first { it.id != declaration.id }
        for (id in listOf("unknown-profile", other.id, declaration.parentProfile, catalog.candidates.first().id)) {
            assertFailsWith<IllegalArgumentException> {
                CatalogRuntime(catalog, "discovery", Path.of("."), config(mapOf(id to input())))
            }
        }
        for (profile in listOf("gfx100s-sd-finite-v1", declaration.parentProfile, other.id)) {
            assertFailsWith<IllegalArgumentException> {
                CatalogRuntime(catalog, "discovery", Path.of("."), config(mapOf(declaration.id to input()), profile))
            }
        }
        for (profile in listOf("gfx100s-sd-finite-v1", declaration.parentProfile)) {
            assertFailsWith<IllegalArgumentException> {
                CatalogRuntime(catalog, "discovery", Path.of("."), config(emptyMap<String, Any>(), profile))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "discovery", Path.of("."), config(mapOf(declaration.id to input(), other.id to input())))
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogRuntime(catalog, "discovery", Path.of("."), mapOf("discovery" to
                mapOf("diagnosticInputs" to mapOf(declaration.id to input()))))
        }
        for (selected in catalog.singleReadDiagnosticProfiles) {
            val runtime = CatalogRuntime(catalog, "discovery", Path.of("."), config(mapOf(selected.id to input(listOf(0, 1))), selected.id))
            assertEquals(listOf(0L, 1L), checkNotNull(runtime.createBus(context)).regions.single { it.id == selected.id }.samples)
        }
    }

    @Test fun sourceConfigMutationsCannotChangeFrozenSelectionOrItsReceipt() {
        val samples = mutableListOf<Any>(0, 1)
        val inputRow = mutableMapOf<String, Any>("strategy" to "scripted-read", "confidence" to "HYPOTHESIS", "samples" to samples)
        val inputs = mutableMapOf<String, Any>(declaration.id to inputRow)
        val discovery = mutableMapOf<String, Any>("profile" to declaration.id, "diagnosticInputs" to inputs)
        val config = mutableMapOf<String, Any>("discovery" to discovery)
        val runtime = CatalogRuntime(catalog, "discovery", Path.of("."), config)
        samples[0] = 7
        samples.clear()
        inputRow["confidence"] = "DAT"
        inputs.clear()
        discovery["profile"] = declaration.parentProfile
        config.clear()
        val bus = checkNotNull(runtime.createBus(context))
        assertEquals(mapOf(declaration.id to input(listOf(0L, 1L))), runtime.observation(bus)["diagnosticInputs"])
        val router = MmioRouter(emptyList(), bus)
        assertEquals(0L, router.read(address, 4))
        assertEquals(1L, router.read(address, 4))
        refusal(bus, "finite-input-missing-or-exhausted") { router.read(address, 4) }
    }

    @Test fun selectedSequencePreservesParentRetainedGeometryPermissionsAndReadContract() {
        val selected = runtime()
        val baseline = defaultRuntime()
        val selectedBus = checkNotNull(selected.createBus(context))
        val baselineBus = checkNotNull(baseline.createBus(context))
        assertEquals(baselineBus.pageRanges(), selectedBus.pageRanges())
        val expectedScript = baselineBus.regions.single { it.id == declaration.id }
        val selectedScript = selectedBus.regions.single { it.id == declaration.id }
        assertEquals(expectedScript, selectedScript.copy(samples = expectedScript.samples))
        assertEquals((baselineBus.snapshot()["regions"] as List<*>).filter { (it as Map<*, *>)["id"] != declaration.id },
            (selectedBus.snapshot()["regions"] as List<*>).filter { (it as Map<*, *>)["id"] != declaration.id })
        val parent = catalog.diagnosticProfiles.single { it.id == declaration.parentProfile }
        assertEquals(baselineBus.regions.single { it.id == parent.id }.retainedDiagnostic,
            selectedBus.regions.single { it.id == parent.id }.retainedDiagnostic)
        for (bus in listOf(baselineBus, selectedBus)) {
            val router = MmioRouter(emptyList(), bus)
            assertFails { router.read(parent.base, parent.width) }
            assertFails { router.write(parent.base, parent.width, 0) }
            router.write(parent.base, parent.width, parent.writeValue)
            assertEquals(parent.writeValue, router.read(parent.base, parent.width))
            assertFails { router.write(parent.base, parent.width, parent.writeValue) }
        }
        assertEquals(region(baselineBus, parent.id), region(selectedBus, parent.id))
        assertTrue(accesses(selected.observation(selectedBus)).all { it["diagnosticRouting"] == parent.data })
    }
}
