package com.github.nyabanban.fuji.emulator.components.hdmi

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class HdmiFactoryTest {
    private class TestContext(private val data: Map<String, Any?> = emptyMap()) : ComponentContext {
        var tick = 0L
        val claims = linkedMapOf<String, Any>()
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
            override fun publish(event: ComponentEvent) { error("No HDMI event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = data
    }

    private fun create(
        enabled: Boolean = true,
        fill: Int = 0,
        pairs: List<List<Long>>? = null,
        linkReads: List<Long>? = null,
    ): Pair<TestContext, StatefulComponent> {
        val data = buildMap<String, Any?> {
            put("schema", 1)
            put("profile", HdmiFactory.PROFILE)
            put("initialFill", fill)
            if (pairs != null) put("pairs", pairs)
            if (linkReads != null) put("linkReads", linkReads)
        }
        val ctx = TestContext(data)
        val factory = HdmiFactory()
        val config = if (enabled) {
            mapOf("profile" to HdmiFactory.PROFILE, "inputFile" to "hdmi.yaml")
        } else {
            mapOf("profile" to "disabled", "inputFile" to "")
        }
        val comp = factory.create(ctx, config) as StatefulComponent
        comp.start()
        return ctx to comp
    }

    private fun port(ctx: TestContext): MmioDevice =
        assertNotNull(ctx.ports.find("soc-hdmi", MmioDevice::class.java))

    @Test
    fun disabledDefaultPublishesNoPortAndRejectsInputFile() {
        val (ctx, comp) = create(enabled = false)
        assertNull(ctx.ports.find("soc-hdmi", MmioDevice::class.java))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertEquals(false, comp.state()["enabled"])

        assertFails {
            HdmiFactory().create(TestContext(), mapOf("profile" to "disabled", "inputFile" to "hdmi.yaml"))
        }
        assertFails {
            HdmiFactory().create(TestContext(), mapOf("profile" to HdmiFactory.PROFILE, "inputFile" to ""))
        }
    }

    @Test
    fun enforcesAll15WordRuns11ProhibitedHolesWidthsAndAlignment() {
        val (ctx, comp) = create(fill = 0xa4)
        val dev = port(ctx)
        assertEquals(listOf("soc-hdmi"), comp.additionalMmioPorts())
        assertEquals(72, dev.clockOrder)

        // 0x20 (hdmi-b020) preserves high bits on RMW
        val beforeB020 = dev.read(0x20, 4)
        assertEquals(0xa4a4a4a4L, beforeB020)
        dev.write(0x20, 4, beforeB020 or 1L)
        assertEquals(0xa4a4a4a5L, dev.read(0x20, 4))

        // RW runs: 0x04, 0x18, 0x20, 0x98..0x9c, 0x124..0x130, 0x150..0x1bc
        for (off in listOf(0x04L, 0x18L, 0x98L, 0x9cL, 0x124L, 0x130L, 0x150L, 0x1bcL)) {
            assertEquals(0xa4a4a4a4L, dev.read(off, 4))
            dev.write(off, 4, 0x12340000L + off)
            assertEquals(0x12340000L + off, dev.read(off, 4))
        }

        // Write-only runs: 0x24..0x2c, 0x120, 0x1e0..0x1f8, 0x220..0x258, 0x280..0x2fc, 0x320..0x3f8, 0xa8c
        for (off in listOf(0x24L, 0x2cL, 0x120L, 0x1e0L, 0x1f8L, 0x220L, 0x258L, 0x280L, 0x2fcL, 0x320L, 0x3f8L, 0xa8cL)) {
            assertFails { dev.read(off, 4) }
            dev.write(off, 4, 200L + off)
        }

        // All 11 prohibited holes refuse both read and write
        val holes = listOf(
            0x000L, 0x00cL, 0x014L, 0x01cL, 0x030L, 0x08cL, 0x0a0L, 0x11cL,
            0x134L, 0x14cL, 0x1c0L, 0x1dcL, 0x1fcL, 0x21cL, 0x25cL, 0x27cL,
            0x300L, 0x31cL, 0x3fcL, 0xa88L,
        )
        for (hole in holes) {
            assertFails { dev.read(hole, 4) }
            assertFails { dev.write(hole, 4, 0L) }
        }

        // Unconfigured link/pairs refuse
        assertFails { dev.read(0x08, 4) }
        assertFails { dev.write(0x08, 4, 0L) }
        assertFails { dev.read(0x90, 4) }
        assertFails { dev.write(0x90, 4, 0L) }

        // Width, alignment, bounds refusals
        for ((off, sz) in listOf(0x20L to 1, 0x20L to 2, 0x20L to 8, 0x22L to 4, -4L to 4, 2704L to 4)) {
            assertFails { dev.read(off, sz) }
            assertFails { dev.write(off, sz, 0L) }
        }
    }

    @Test
    fun linkReadsAndFourPhasePairsFollowExactProtocol() {
        val pairs = listOf(listOf(1L, 2L), listOf(0x80000004L, 0x80L))
        val linkWords = listOf(0L, 0x18L)
        val (ctx, _) = create(fill = 0, pairs = pairs, linkReads = linkWords)
        val dev = port(ctx)

        // Link status is read-only sequential
        assertFails { dev.write(0x08, 4, 0L) }
        assertEquals(0L, dev.read(0x08, 4))
        assertEquals(0x18L, dev.read(0x08, 4))
        assertFails { dev.read(0x08, 4) }

        // 4-phase pairs: R(+90), R(+94), W(+90), W(+94)
        assertFails { dev.read(0x94, 4) } // out of order
        assertFails { dev.write(0x90, 4, 1L) } // write before read
        assertEquals(1L, dev.read(0x90, 4))
        assertEquals(2L, dev.read(0x94, 4))
        assertFails { dev.write(0x90, 4, 99L) } // wrong echo value
        dev.write(0x90, 4, 1L)
        dev.write(0x94, 4, 2L)

        assertEquals(0x80000004L, dev.read(0x90, 4))
        assertEquals(0x80L, dev.read(0x94, 4))
        dev.write(0x90, 4, 0x80000004L)
        dev.write(0x94, 4, 0x80L)
        assertFails { dev.read(0x90, 4) } // exhausted
    }

    @Test
    fun snapshotRoundTripPromotionAndAtomicInvalidRejection() {
        val pairs1 = listOf(listOf(1L, 2L))
        val link1 = listOf(4L, 8L)
        val (ctx1, comp1) = create(fill = 0x55, pairs = pairs1, linkReads = link1)
        val dev1 = port(ctx1)
        dev1.write(0x20, 4, 0x11223344L)
        assertEquals(1L, dev1.read(0x90, 4))
        assertEquals(2L, dev1.read(0x94, 4))
        dev1.write(0x90, 4, 1L)
        dev1.write(0x94, 4, 2L)
        assertEquals(4L, dev1.read(0x08, 4))

        val snap = comp1.saveSnapshot()

        // Exact restore
        val (ctx2, comp2) = create(fill = 0x55, pairs = pairs1, linkReads = link1)
        comp2.restoreSnapshot(snap)
        assertEquals(comp1.state(), comp2.state())
        assertEquals(0x11223344L, port(ctx2).read(0x20, 4))
        assertEquals(8L, port(ctx2).read(0x08, 4))
        assertFails { port(ctx2).read(0x90, 4) }

        // Append-only promotion when pairs exhausted
        val pairs2 = pairs1 + listOf(listOf(3L, 4L))
        val link2 = link1 + listOf(16L)
        val (ctx3, comp3) = create(fill = 0x55, pairs = pairs2, linkReads = link2)
        comp3.restoreSnapshot(snap)
        assertEquals(3L, port(ctx3).read(0x90, 4))
        assertEquals(8L, port(ctx3).read(0x08, 4))
        assertEquals(16L, port(ctx3).read(0x08, 4))

        // Invalid snapshots reject atomically without mutating live state
        val beforeState = comp2.state()
        val badFill = snap + ("initialFill" to 0)
        val badSchema = snap + ("schema" to 2)
        val badWordsLen = snap + ("wordsBase64" to "AAAA")
        val badPos = snap + ("pairPosition" to 99)
        for (bad in listOf(badFill, badSchema, badWordsLen, badPos)) {
            assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(bad) }
            assertEquals(beforeState, comp2.state())
        }

        // Disabled round-trip
        val (_, disabled1) = create(enabled = false)
        val (_, disabled2) = create(enabled = false)
        disabled2.restoreSnapshot(disabled1.saveSnapshot())
        assertEquals(disabled1.state(), disabled2.state())
    }

    @Test
    fun repeatLastLinkReadRepeatsFinalSampleAndUpgradesFromFalseSnapshot() {
        val linkWords = listOf(4L, 0x18L)
        val data = mapOf<String, Any?>(
            "schema" to 1,
            "profile" to HdmiFactory.PROFILE,
            "initialFill" to 0,
            "pairs" to listOf(listOf(1L, 2L)),
            "linkReads" to linkWords,
        )
        fun createWithRepeat(repeat: Boolean): Pair<TestContext, StatefulComponent> {
            val ctx = TestContext(data)
            val comp = HdmiFactory().create(
                ctx,
                mapOf(
                    "profile" to HdmiFactory.PROFILE,
                    "inputFile" to "hdmi.yaml",
                    "repeatLastLinkRead" to repeat,
                ),
            ) as StatefulComponent
            comp.start()
            return ctx to comp
        }

        // Non-repeating exhausts after 2 reads
        val (ctxStrict, strictComp) = createWithRepeat(false)
        assertEquals(4L, port(ctxStrict).read(0x08, 4))
        assertEquals(0x18L, port(ctxStrict).read(0x08, 4))
        assertFails { port(ctxStrict).read(0x08, 4) }
        val exhaustedSnap = strictComp.saveSnapshot()

        // Upgrade false -> true repeats last sample indefinitely
        val (ctxRep, repComp) = createWithRepeat(true)
        repComp.restoreSnapshot(exhaustedSnap)
        repeat(5) {
            assertEquals(0x18L, port(ctxRep).read(0x08, 4))
        }

        // Round-trip on repeatLastLinkRead=true preserves cursor and repeats cleanly
        val repSnap = repComp.saveSnapshot()
        val (ctxRep2, repComp2) = createWithRepeat(true)
        repComp2.restoreSnapshot(repSnap)
        assertEquals(0x18L, port(ctxRep2).read(0x08, 4))

        // Downgrade repeatLastLinkRead=true -> false must fail when cursor exceeded sample count
        val (_, strict2) = createWithRepeat(false)
        assertFailsWith<IllegalArgumentException> { strict2.restoreSnapshot(repSnap) }
    }

    @Test
    fun repeatLastStatusPairRepeatsFinalFourPhaseEchoAndUpgradesFromFalseSnapshot() {
        val data = mapOf<String, Any?>(
            "schema" to 1,
            "profile" to HdmiFactory.PROFILE,
            "initialFill" to 0,
            "pairs" to listOf(listOf(0x11L, 0x22L), listOf(0x0L, 0x0L)),
            "linkReads" to listOf(0L),
        )
        fun createWithRepeat(repeatPair: Boolean): Pair<TestContext, StatefulComponent> {
            val ctx = TestContext(data)
            val comp = HdmiFactory().create(
                ctx,
                mapOf(
                    "profile" to HdmiFactory.PROFILE,
                    "inputFile" to "hdmi.yaml",
                    "repeatLastStatusPair" to repeatPair,
                ),
            ) as StatefulComponent
            comp.start()
            return ctx to comp
        }

        val (ctxStrict, strictComp) = createWithRepeat(false)
        val devStrict = port(ctxStrict)
        assertEquals(0x11L, devStrict.read(0x90, 4))
        assertEquals(0x22L, devStrict.read(0x94, 4))
        devStrict.write(0x90, 4, 0x11L)
        devStrict.write(0x94, 4, 0x22L)
        assertEquals(0L, devStrict.read(0x90, 4))
        assertEquals(0L, devStrict.read(0x94, 4))
        devStrict.write(0x90, 4, 0L)
        devStrict.write(0x94, 4, 0L)
        assertFails { devStrict.read(0x90, 4) }
        val exhaustedSnap = strictComp.saveSnapshot()

        // Upgrade false -> true repeats final pair [0, 0] indefinitely across 4-phase echo protocol
        val (ctxRep, repComp) = createWithRepeat(true)
        repComp.restoreSnapshot(exhaustedSnap)
        val devRep = port(ctxRep)
        repeat(5) {
            assertEquals(0L, devRep.read(0x90, 4))
            assertEquals(0L, devRep.read(0x94, 4))
            devRep.write(0x90, 4, 0L)
            devRep.write(0x94, 4, 0L)
        }
        // Still rejects protocol violations even while repeating
        assertFails { devRep.read(0x94, 4) }

        val repSnap = repComp.saveSnapshot()
        val (ctxRep2, repComp2) = createWithRepeat(true)
        repComp2.restoreSnapshot(repSnap)
        assertEquals(0L, port(ctxRep2).read(0x90, 4))

        // Downgrade true -> false past pair count must fail
        val (_, strict2) = createWithRepeat(false)
        assertFailsWith<IllegalArgumentException> { strict2.restoreSnapshot(repSnap) }
    }
}
