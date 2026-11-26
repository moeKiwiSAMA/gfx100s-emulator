package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class SdPhyHostTest {
    private fun input(profile: String = SdHostFactory.PHY_PROFILE, samples: List<Any?> = listOf(0, 1, null)):
        Map<String, Any?> = mapOf(
        "schema" to 1, "profile" to profile,
        "initialRegisters" to (0..1).associate { slot -> slot.toString() to linkedMapOf(
            "word80" to 0xa55a0005L + slot, "control623e" to 0x55aaL + slot,
            "mixed6078" to 0xa55a0005L + slot, "control403e" to 0xaa55L + slot,
            "word60ec" to 0xffffffffL - slot) },
        "clocks" to (0..3).associate { SdClockHost.key(it) to mapOf(
            "initialWord" to 0xa550L, "bit1Samples" to samples) },
        "initWords" to (0..1).associate { slot -> slot.toString() to
            SdInitHost.fields.mapIndexed { field, name ->
                name to if (field < 3) 0x80000000L + slot * 4096 + field else 0xa500L + slot * 256 + field
            }.toMap() })

    private class Context(var data: Map<String, Any?>) : ComponentContext {
        var tick = 10L
        var inputReads = 0
        val entries = mutableMapOf<String, Any>()
        override val directory = Path.of("unused-sd-phy")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(entries.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let { type.cast(it) }
            override fun removeOwner(owner: String) = error("unexpected removal")
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("no invented event/IRQ")
            override fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable = error("no subscription")
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> {
            assertEquals("input.yaml", relative)
            inputReads++
            return data
        }
    }
    private fun create(context: Context, profile: String = SdHostFactory.PHY_PROFILE, file: String = "input.yaml"):
        HardwareComponent {
        val factory = SdHostFactory()
        return factory.create(context, factory.descriptor.settings.associate { it.key to it.default } +
            mapOf("profile" to profile, "inputFile" to file))
    }
    private fun port(context: Context, name: String) = context.ports.find(name, MmioDevice::class.java)!!
    private fun mixed(context: Context, slot: Int) = port(context, SdControlHost.port(slot, 2))
    private fun clockState(host: HardwareComponent) = host.state()["clockState"] as Map<*, *>
    private fun controlState(host: HardwareComponent) = clockState(host)["controlState"] as Map<*, *>
    private fun register(host: HardwareComponent, slot: Int, field: String): Long =
        ((controlState(host)["registers"] as Map<*, *>)[slot.toString()] as Map<*, *>)[field] as Long
    private fun withoutProfileAndBasis(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.filterKeys { it != "profile" && it != "basis" }
            .mapValues { withoutProfileAndBasis(it.value) }
        is List<*> -> value.map { withoutProfileAndBasis(it) }
        else -> value
    }

    @Test fun v5KeepsRealProfileThroughAllTwentyEightPortsAndLegacyStateStaysExact() {
        val factory = SdHostFactory()
        val setting = factory.descriptor.settings.single { it.key == "profile" }
        assertEquals(SdHostFactory.PHY_PROFILE, setting.validate(SdHostFactory.PHY_PROFILE))
        assertFalse(setting.live)
        assertEquals("disabled", setting.default)
        val current = Context(input())
        val legacy = Context(input(SdHostFactory.INIT_PROFILE))
        val v5 = create(current)
        val v4 = create(legacy, SdHostFactory.INIT_PROFILE)
        v5.start(); v4.start()
        assertEquals(1, current.inputReads)
        assertEquals(1, legacy.inputReads)
        assertEquals(28, v5.additionalMmioPorts().size)
        assertEquals(v4.additionalMmioPorts(), v5.additionalMmioPorts())
        assertEquals((0..27).toSet(), current.entries.values.filterIsInstance<MmioDevice>().map { it.clockIndex }.toSet())
        assertTrue(current.entries.values.filterIsInstance<MmioDevice>().all { it.clockOrder == 85 })
        for (state in listOf(v5.state(), clockState(v5), controlState(v5))) {
            assertEquals(SdHostFactory.PHY_PROFILE, state["profile"])
            assertEquals(false, state["cardProtocolQualified"])
            assertTrue((state["basis"] as String).contains("HYP"))
        }
        assertEquals(SdHostFactory.INIT_PROFILE, v4.state()["profile"])
        assertEquals(SdHostFactory.CLOCK_PROFILE, clockState(v4)["profile"])
        assertEquals(SdHostFactory.CONTROL_PROFILE, controlState(v4)["profile"])
        assertEquals("DAT finite init accesses; explicit u32 inputs and u16 write retention HYP. SOURCE SDHCI/UHS-II field candidates only, not measured GFX values, contacts, power, IRQ, DMA or card protocol. clockState is the v3 projection of the same frozen input.", v4.state()["basis"])
        assertEquals("Finite explicit per-read bit1 samples and full-u16 stored-write retention HYP; no repeat-last/default-ready, physical stable-time, reset, IRQ, DMA or card protocol. controlState is the v2 projection of this same frozen input.", clockState(v4)["basis"])
        assertEquals("DAT finite software accesses only; explicit unmeasured inputs/full-width retention and mixed6078 low-lane preservation HYP. No ready/reset/IRQ/DMA/card protocol or block storage.", controlState(v4)["basis"])
        assertEquals("5d03770e6057f1e25979d2fd4c5097b9fcda33283de1b7610c47ef0b6063eb70", controlState(v4)["inputCanonicalSha256"])
        assertEquals("0780bcad3208a4385641ba2ddf359ace851082a9c0d389577e2ce0b0c5b3683e", clockState(v4)["inputCanonicalSha256"])
        assertEquals("11a7bc9d8da340175dea52508981183b6617b43fe487b8c52d3fb2548bf6a96d", v4.state()["inputCanonicalSha256"])
        assertEquals(withoutProfileAndBasis(v4.state()), withoutProfileAndBasis(v5.state()))
        for (context in listOf(current, legacy)) {
            mixed(context, 0).write(0, 2, 0xffff)
            assertEquals(0xa55affffL, mixed(context, 0).read(0, 4))
            port(context, SdClockHost.port(0)).write(0, 2, 0xa553)
            assertEquals(0xa551L, port(context, SdClockHost.port(0)).read(0, 2))
            port(context, SdInitHost.port(1, 5)).write(0, 2, 0x55aa)
        }
        assertEquals(withoutProfileAndBasis(v4.state()), withoutProfileAndBasis(v5.state()))
        assertTrue(v5.interruptRoutes().isEmpty())
    }

    @Test fun bothSlotsPreserveHighHalfwordAndReplaceAllThirtyTwoBitsIndependently() {
        val context = Context(input())
        context.entries["cpu.execution-location"] = ExecutionLocationSource { ExecutionLocation(2, 0x5af98) }
        val host = create(context)
        val seeds = listOf(0L, 0xffffffffL) + (0..31).map { 1L shl it }
        for (slot in 0..1) for (seed in seeds) {
            val other = register(host, 1 - slot, "mixed6078")
            val p = mixed(context, slot)
            p.write(0, 4, seed)
            assertEquals(seed, p.read(0, 4))
            for (halfword in listOf(0L, 0xffffL, 0xa55aL)) {
                p.write(0, 2, halfword)
                assertEquals((seed and 0xffff0000L) or halfword, p.read(0, 4))
                assertEquals(other, register(host, 1 - slot, "mixed6078"))
            }
        }
        val state = controlState(host)
        assertEquals(272L, state["writeCount"])
        assertEquals(272L, state["readCount"])
        assertEquals(544L, state["accessCount"])
        assertEquals(true, state["accessLogTruncated"])
        val accesses = state["accesses"] as List<*>
        assertEquals(256, accesses.size)
        assertTrue(accesses.all { val row = it as Map<*, *>; row["core"] == 2 && row["pcVirtual"] == 0x5af98L })
        assertTrue(accesses.any { val row = it as Map<*, *>; row["write"] == true && row["size"] == 4 })
        assertTrue(accesses.any { val row = it as Map<*, *>; row["write"] == true && row["size"] == 2 })
        assertEquals(0L, clockState(host)["clockAccessCount"])
        assertEquals(0L, host.state()["initAccessCount"])
    }

    @Test fun mixedWidthsOffsetsAndUnsignedValuesRejectWithoutAnyStateOrSampleChange() {
        val context = Context(input())
        val host = create(context)
        val before = host.state()
        for (slot in 0..1) {
            val p = mixed(context, slot)
            for (width in listOf(-1, 0, 1, 2, 3, 4, 8)) {
                if (width != 4) { assertFails { p.read(0, width) }; assertEquals(before, host.state()) }
                if (width != 2 && width != 4) { assertFails { p.write(0, width, 0) }; assertEquals(before, host.state()) }
            }
            for (offset in listOf(Long.MIN_VALUE, -4L, -1L, 1L, 2L, 4L, Long.MAX_VALUE)) {
                assertFails { p.read(offset, 4) }
                for (width in listOf(2, 4)) assertFails { p.write(offset, width, 0) }
                assertEquals(before, host.state())
            }
            for (width in listOf(2, 4)) for (value in listOf(-1L, if (width == 2) 65536L else 0x100000000L, Long.MAX_VALUE)) {
                assertFails { p.write(0, width, value) }
                assertEquals(before, host.state())
            }
        }
        context.tick = -1
        assertFails { mixed(context, 0).write(0, 4, 1) }
        assertFails { mixed(context, 1).read(0, 4) }
        assertEquals(before, host.state())
        context.tick = 10
        context.entries["cpu.execution-location"] = ExecutionLocationSource { error("location failed") }
        assertFails { mixed(context, 0).write(0, 4, 1) }
        assertFails { mixed(context, 1).write(0, 2, 1) }
        assertEquals(before, host.state())
    }

    @Test fun v4StillRefusesWordWritesWithNoMutation() {
        val context = Context(input(SdHostFactory.INIT_PROFILE))
        val host = create(context, SdHostFactory.INIT_PROFILE)
        val before = host.state()
        for (slot in 0..1) {
            val failure = assertFailsWith<IllegalArgumentException> { mixed(context, slot).write(0, 4, 0) }
            assertEquals("Unsupported SD control width/direction/offset", failure.message)
            assertEquals(before, host.state())
        }
        assertEquals(0L, controlState(host)["writeCount"])
    }

    @Test fun finiteClockMissingNullAndExhaustedSamplesStayExplicitUnderV5() {
        for (samples in listOf(emptyList(), listOf(null))) {
            val context = Context(input(samples = samples))
            val host = create(context)
            for (index in 0..3) {
                val before = host.state()
                assertFailsWith<IllegalArgumentException> { port(context, SdClockHost.port(index)).read(0, 2) }
                assertEquals(before, host.state())
            }
        }
        val context = Context(input(samples = listOf(0, 1)))
        val host = create(context)
        for (index in 0..3) {
            val p = port(context, SdClockHost.port(index))
            var before = host.state()
            assertFails { p.read(0, 4) }; assertEquals(before, host.state())
            assertFails { p.read(1, 2) }; assertEquals(before, host.state())
            p.write(0, 2, 0xffff)
            assertEquals(0xfffdL, p.read(0, 2))
            assertEquals(0xffffL, p.read(0, 2))
            before = host.state()
            assertFails { p.read(0, 2) }; assertEquals(before, host.state())
            mixed(context, index / 2).write(0, 4, 0)
            before = host.state()
            assertFails { p.read(0, 2) }; assertEquals(before, host.state())
        }
    }

    @Test fun exactV5RootAndAllExplicitInputSetsRequiredBeforePublishing() {
        val base = input()
        val invalid = base.keys.map { base - it }.toMutableList()
        invalid += base + ("extra" to 1)
        invalid += base + ("schema" to 1.0)
        for (profile in listOf(SdHostFactory.PROFILE, SdHostFactory.CONTROL_PROFILE,
            SdHostFactory.CLOCK_PROFILE, SdHostFactory.INIT_PROFILE, "bad", null)) invalid += base + ("profile" to profile)
        for (key in listOf("initialRegisters", "clocks", "initWords")) {
            invalid += base + (key to null)
            invalid += base + (key to emptyMap<String, Any>())
        }
        val registers = base["initialRegisters"] as Map<*, *>
        val row = registers["0"] as Map<*, *>
        for (value in listOf(null, -1L, 0x100000000L, 1.0, true, "0"))
            invalid += base + ("initialRegisters" to (registers + ("0" to (row + ("mixed6078" to value)))))
        for (data in invalid) {
            val context = Context(data)
            assertFails { create(context) }
            assertTrue(context.entries.isEmpty())
        }
        val context = Context(base)
        assertFails { create(context, file = "") }
        assertEquals(0, context.inputReads)
        assertTrue(context.entries.isEmpty())
        val oldContext = Context(base)
        assertFails { create(oldContext, SdHostFactory.INIT_PROFILE) }
        assertTrue(oldContext.entries.isEmpty())
    }

    @Test fun everyPortAliasRejectsBeforeAnyOtherPortIsPublishedAndDisabledNeedsNoInput() {
        val names = (0..1).flatMap { slot -> SdControlHost.fields.indices.map { SdControlHost.port(slot, it) } } +
            (0..3).map { SdClockHost.port(it) } +
            (0..1).flatMap { slot -> SdInitHost.fields.indices.map { SdInitHost.port(slot, it) } }
        for (name in names) {
            val context = Context(input())
            val canary = object : MmioDevice {
                override fun read(offset: Long, size: Int): Long = error("alias read")
                override fun write(offset: Long, size: Int, value: Long) = error("alias write")
            }
            context.entries[name] = canary
            assertFails { create(context) }
            assertEquals(1, context.entries.size)
            assertSame(canary, context.entries[name])
        }
        val context = Context(emptyMap())
        val disabled = create(context, "disabled", "")
        disabled.start()
        assertEquals(0, context.inputReads)
        assertTrue(context.entries.isEmpty())
        assertTrue(disabled.additionalMmioPorts().isEmpty())
        assertEquals(false, disabled.state()["enabled"])
    }

    @Test fun closingAllLayersAndRestartOnlySettingsNeverChangeRegistersOrConsumeSamples() {
        val context = Context(input())
        val host = create(context)
        host.start()
        host.applySettings(mapOf("profile" to SdHostFactory.PHY_PROFILE))
        val before = host.state()
        assertFails { host.applySettings(mapOf("profile" to SdHostFactory.INIT_PROFILE)) }
        assertFails { host.applySettings(mapOf("inputFile" to "other.yaml")) }
        assertEquals(before, host.state())
        host.close(); host.close()
        val closed = host.state()
        assertEquals(true, closed["closed"])
        assertEquals(true, clockState(host)["closed"])
        assertEquals(true, controlState(host)["closed"])
        assertFails { host.start() }
        assertFails { host.applySettings(mapOf("profile" to SdHostFactory.PHY_PROFILE)) }
        for (slot in 0..1) {
            assertFails { mixed(context, slot).read(0, 4) }
            assertFails { mixed(context, slot).write(0, 2, 0) }
            assertFails { mixed(context, slot).write(0, 4, 0) }
            assertFails { port(context, SdInitHost.port(slot, 0)).read(0, 4) }
            assertFails { port(context, SdClockHost.port(slot * 2)).read(0, 2) }
            assertEquals(closed, host.state())
        }
    }
}
