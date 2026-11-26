package com.github.nyabanban.fuji.emulator.components.memory

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class MemorySnapshotTest {
    private class TestContext : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>(
            "board.memory-layouts" to object : MemoryLayoutSource {
                override fun regions(profile: String): List<MemoryRegion> = listOf(
                    MemoryRegion("ram-low", 0x0000_0000L, 0x0002_0000L, 7),
                    MemoryRegion("ram-work", 0x0100_0000L, 0x0010_0000L, 3),
                )
            },
        )
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { error("No memory event is qualified") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private class Harness(
        profile: String = "flashload-request-buffer-experimental-v24",
        spanPolicy: String = "contiguous-compatible-v1",
        additionalRamProfile: String = AdditionalRamRegions.PROFILE,
    ) {
        val context = TestContext()
        val factory = MemoryFactory()
        val config: Map<String, Any> = factory.descriptor.settings.associate { setting -> setting.key to setting.default } + mapOf(
            "profile" to profile,
            "spanPolicy" to spanPolicy,
            "additionalRamProfile" to additionalRamProfile,
        )
        val component = factory.create(context, config) as StatefulComponent
        val layout: ExpandableMemoryLayout =
            context.ports.find("system-memory.layout", MemoryLayout::class.java) as ExpandableMemoryLayout
    }

    @Suppress("UNCHECKED_CAST")
    private fun simulateJsonRoundTrip(value: Any?): Any? = when (value) {
        null, is Boolean, is String -> value
        is Byte, is Short, is Int, is Long -> {
            val numeric = (value as Number).toLong()
            if (numeric in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) numeric.toInt() else numeric
        }
        is Float, is Double -> (value as Number).toDouble()
        is List<*> -> value.map { element -> simulateJsonRoundTrip(element) }
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>()
            for ((rawKey, rawElement) in value) {
                check(rawKey is String) { "JSON object keys must be strings" }
                copy[rawKey] = simulateJsonRoundTrip(rawElement)
            }
            copy
        }
        else -> error("Unsupported non-JSON value type: ${value::class.java.name}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripMap(snapshot: Map<String, Any?>): Map<String, Any?> =
        simulateJsonRoundTrip(snapshot) as Map<String, Any?>

    @Test
    fun configRecordPagesRemainAnExplicitRestartOnlyProfile() {
        val setting = MemoryFactory().descriptor.settings.single { it.key == "profile" }
        val profile = "kernel-config-record-pages-experimental-v15"
        assertEquals(false, setting.live)
        assertEquals("loader-low128-experimental-v1", setting.default)
        assertEquals(profile, setting.validate(profile))
        assertFailsWith<IllegalArgumentException> { setting.validate("kernel-config-record-pages-experimental-v16") }
    }

    @Test
    fun extensionPublishesOnlyAfterBackingSucceedsAndRoundTripsTheUnion() {
        val harness = Harness()
        val old = MemoryRegion("old", 0x4000000, 0x1000, 3)
        val next = MemoryRegion("next", 0x5000000, 0x1000, 3)
        harness.layout.selectAdditionalRegions(listOf(old))
        val before = harness.component.saveSnapshot()
        for (regions in listOf(listOf(old), listOf(next), listOf(old.copy(size = 8192), next))) {
            assertFailsWith<IllegalArgumentException> { harness.layout.extendAdditionalRegions(regions) { fail("mapped invalid plan") } }
        }
        assertFailsWith<IllegalStateException> {
            harness.layout.extendAdditionalRegions(listOf(next, old)) { error("allocation failed") }
        }
        assertEquals(before, harness.component.saveSnapshot())
        harness.layout.extendAdditionalRegions(listOf(next, old)) {}
        val restored = Harness()
        restored.component.restoreSnapshot(roundTripMap(harness.component.saveSnapshot()))
        assertEquals(listOf(old, next), restored.layout.additionalRegions())
        assertEquals(harness.layout.regions(), restored.layout.regions())
    }

    @Test
    fun t75ResultPagesExtendTwelveRegionSnapshotAndPreserveTheGap() {
        val original = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        val earlier = List(11) { index -> MemoryRegion("old-$index", 0x10000000L + index * 4096L, 4096, 3) }
        val old = earlier + MemoryRegion("result-copy-2c546000", 0x2c546000, 8192, 3)
        original.layout.selectAdditionalRegions(old)
        val resumed = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        resumed.component.restoreSnapshot(roundTripMap(original.component.saveSnapshot()))
        val planes = MemoryRegion("t75-result-planes", 0x2c54e000, 8192, 3)
        val detail = MemoryRegion("t75-result-detail", 0x2c550000, 4096, 3)
        val complete = old + planes + detail
        resumed.layout.extendAdditionalRegions(complete) {}
        val ring = MemoryRegion("t80-liveview-ring-8a9c8000", 0x8a9c8000L, 0x358000L, 3)
        resumed.layout.extendAdditionalRegions(complete + ring) {}
        val overflow = complete + ring + List(26) { index ->
            MemoryRegion("overflow-$index", 0x2c551000L + index * 4096L, 4096, 3)
        }
        assertFailsWith<IllegalArgumentException> {
            resumed.layout.extendAdditionalRegions(overflow) {
                fail("published beyond forty regions")
            }
        }
        val restored = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        restored.component.restoreSnapshot(roundTripMap(resumed.component.saveSnapshot()))
        assertEquals(complete + ring, restored.layout.additionalRegions())
        restored.layout.check(0x2c54fce0, 256, 3)
        restored.layout.check(0x2c5508e0, 1, 3)
        restored.layout.check(0x8a9c8000L, 0x358000L, 3)
        for (address in listOf(0x2c548000L, 0x2c54dfff, 0x2c551000, 0x8ad20000L)) {
            assertFailsWith<IllegalArgumentException> { restored.layout.check(address, 1, 1) }
        }
        assertFailsWith<IllegalArgumentException> { restored.layout.check(planes.address, 1, 4) }
    }

    @Test
    fun smallPageExtensionsRestoreWithTheirOriginalPermissions() {
        val source = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        val initial = List(36) { index -> MemoryRegion("selected-$index", 0x11000000L + 4096L * index, 4096, 3) }
        source.layout.selectAdditionalRegions(initial)
        val page = MemoryRegion("low-page", 0x10000000L, 8192, 3)
        for (bad in listOf(page.copy(permissions = 7), page.copy(size = 12288))) {
            assertFailsWith<IllegalArgumentException> {
                source.layout.extendAdditionalRegions(listOf(bad) + initial) { fail("mapped invalid page") }
            }
            assertEquals(initial, source.layout.additionalRegions())
        }
        source.layout.extendAdditionalRegions(initial + page) {}
        val restored = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        restored.component.restoreSnapshot(roundTripMap(source.component.saveSnapshot()))
        assertEquals(listOf(page) + initial, restored.layout.additionalRegions())
        restored.layout.check(page.address, page.size, 3)
        assertFailsWith<IllegalArgumentException> { restored.layout.check(page.address, page.size, 7) }
    }

    @Test
    fun snapshotRoundTripRestoresSelectedRegionsOntoFreshAndPreSelectedInstancesAcrossT1ToT2() {
        val extraRegions = listOf(
            MemoryRegion("extra-high", 0x8000_0000L, 0x0000_4000L, 3),
            MemoryRegion("extra-contig", 0x0110_0000L, 0x0000_2000L, 3),
        )
        val continuous = Harness(additionalRamProfile = AdditionalRamRegions.PROFILE)
        continuous.layout.selectAdditionalRegions(extraRegions)
        val savedAtT1 = roundTripMap(continuous.component.saveSnapshot())
        val stateAtT1 = continuous.component.state()

        val freshTarget = Harness(additionalRamProfile = AdditionalRamRegions.PROFILE)
        freshTarget.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, freshTarget.component.state())
        assertEquals(continuous.layout.additionalRegions(), freshTarget.layout.additionalRegions())
        assertEquals(continuous.layout.regions(), freshTarget.layout.regions())

        val preSelectedTarget = Harness(additionalRamProfile = AdditionalRamRegions.PROFILE)
        preSelectedTarget.layout.selectAdditionalRegions(extraRegions)
        preSelectedTarget.component.restoreSnapshot(savedAtT1)
        assertEquals(stateAtT1, preSelectedTarget.component.state())

        for (candidate in listOf(continuous, freshTarget, preSelectedTarget)) {
            candidate.layout.check(0x010f_f000L, 0x2000L, 3)
            candidate.layout.check(0x8000_0000L, 0x4000L, 3)
            assertFailsWith<IllegalArgumentException> { candidate.layout.check(0x8000_0000L, 0x4000L, 7) }
            assertFailsWith<IllegalStateException> {
                candidate.layout.selectAdditionalRegions(listOf(MemoryRegion("another", 0x9000_0000L, 0x1000L, 3)))
            }
        }
    }

    @Test
    fun compatibleAdditionalRamProfileUpgradesSucceedForBothSelectedAndUnselectedSnapshots() {
        val rwSaved = Harness(additionalRamProfile = AdditionalRamRegions.PROFILE)
        rwSaved.layout.selectAdditionalRegions(listOf(MemoryRegion("rw-tail", 0x4000_0000L, 0x2000L, 3)))
        val rwSnapshot = roundTripMap(rwSaved.component.saveSnapshot())

        val upgradedFromSelected = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        upgradedFromSelected.component.restoreSnapshot(rwSnapshot)
        assertEquals(AdditionalRamRegions.EXEC_CAPABLE_PROFILE, upgradedFromSelected.component.state()["additionalRamProfile"])
        assertEquals(listOf(MemoryRegion("rw-tail", 0x4000_0000L, 0x2000L, 3)), upgradedFromSelected.layout.additionalRegions())
        upgradedFromSelected.layout.check(0x4000_0000L, 0x2000L, 3)
        assertFailsWith<IllegalArgumentException> { upgradedFromSelected.layout.check(0x4000_0000L, 0x2000L, 7) }

        val disabledSaved = Harness(additionalRamProfile = "")
        val disabledSnapshot = roundTripMap(disabledSaved.component.saveSnapshot())
        val upgradedFromDisabled = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        upgradedFromDisabled.component.restoreSnapshot(disabledSnapshot)
        assertTrue(upgradedFromDisabled.layout.additionalRegions().isEmpty())
        val execRegion = MemoryRegion("exec-tail", 0x5000_0000L, 0x4000L, 7)
        upgradedFromDisabled.layout.selectAdditionalRegions(listOf(execRegion))
        upgradedFromDisabled.layout.check(0x5000_0000L, 0x4000L, 7)

        val execSnapshot = roundTripMap(upgradedFromDisabled.component.saveSnapshot())
        val restoredExec = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        restoredExec.component.restoreSnapshot(execSnapshot)
        assertEquals(upgradedFromDisabled.component.state(), restoredExec.component.state())
        restoredExec.layout.check(0x5000_0000L, 0x4000L, 7)
    }

    @Test
    fun invalidDowngradesMismatchesAndWrongOwnerRejectAtomically() {
        val execHarness = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        execHarness.layout.selectAdditionalRegions(listOf(MemoryRegion("rwx-ext", 0x6000_0000L, 0x1000L, 7)))
        val execSnapshot = roundTripMap(execHarness.component.saveSnapshot())

        val rwOnlyTarget = Harness(additionalRamProfile = AdditionalRamRegions.PROFILE)
        val rwOnlyBaseline = rwOnlyTarget.component.state()
        assertFailsWith<IllegalArgumentException> { rwOnlyTarget.component.restoreSnapshot(execSnapshot) }
        assertEquals(rwOnlyBaseline, rwOnlyTarget.component.state())

        val forgedUpgrade = LinkedHashMap(execSnapshot).apply {
            @Suppress("UNCHECKED_CAST")
            val scalars = LinkedHashMap(get("scalars") as Map<String, Any?>)
            scalars["additionalRamProfile"] = AdditionalRamRegions.PROFILE
            put("scalars", scalars)
        }
        val execTarget = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        val execTargetBaseline = execTarget.component.state()
        assertFailsWith<IllegalArgumentException> { execTarget.component.restoreSnapshot(forgedUpgrade) }
        assertEquals(execTargetBaseline, execTarget.component.state())

        val differentSpan = Harness(
            spanPolicy = "single-region-v1",
            additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
        )
        val beforeSpan = differentSpan.component.state()
        assertFailsWith<IllegalArgumentException> { differentSpan.component.restoreSnapshot(execSnapshot) }
        assertEquals(beforeSpan, differentSpan.component.state())

        val preSelectedMismatch = Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
        preSelectedMismatch.layout.selectAdditionalRegions(listOf(MemoryRegion("other-ext", 0x7000_0000L, 0x1000L, 7)))
        val mismatchBaseline = preSelectedMismatch.component.state()
        assertFailsWith<IllegalArgumentException> { preSelectedMismatch.component.restoreSnapshot(execSnapshot) }
        assertEquals(mismatchBaseline, preSelectedMismatch.component.state())

        val unselectedSnapshot = roundTripMap(Harness(additionalRamProfile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE).component.saveSnapshot())
        assertFailsWith<IllegalArgumentException> { preSelectedMismatch.component.restoreSnapshot(unselectedSnapshot) }
        assertEquals(mismatchBaseline, preSelectedMismatch.component.state())

        val workerFailure = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                execTarget.component.saveSnapshot()
            } catch (thrown: Throwable) {
                workerFailure.set(thrown)
            }
        }
        worker.start()
        worker.join()
        assertTrue(workerFailure.get() is IllegalStateException)
    }
}
