package com.github.nyabanban.fuji.emulator.components.dram

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class DramColdStartControllerTest {
    private class Registry : Ports {
        val ports = linkedMapOf<String, Pair<String, Any>>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            require(type.isInstance(port) && name !in ports)
            ports[name] = owner to port
        }

        override fun <T : Any> find(name: String, type: Class<T>): T? =
            ports[name]?.second?.let { type.cast(it) }

        override fun removeOwner(owner: String) {
            ports.entries.removeIf { it.value.first == owner }
        }
    }

    private class Context : ComponentContext {
        override val directory: Path = Path.of("unused-dram-coldstart-test").toAbsolutePath().normalize()
        override val ports = Registry()
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }

        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = error("factory-coldstart profile needs no inputFile")
    }

    private fun createColdStart(context: Context = Context()): Pair<Context, StatefulComponent> {
        val factory = DramFactory()
        val defaults = factory.descriptor.settings.associate { it.key to it.default }
        val component = assertIs<StatefulComponent>(
            factory.create(
                context,
                defaults + mapOf("profile" to DramFactory.FACTORY_COLDSTART_PROFILE, "inputFile" to ""),
            ),
        )
        component.start()
        return context to component
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripJson(raw: Map<String, Any?>): Map<String, Any?> {
        fun convert(v: Any?): Any? = when (v) {
            null, is Boolean, is String -> v
            is Byte, is Short, is Int, is Long -> {
                val n = (v as Number).toLong()
                if (n in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) n.toInt() else n
            }
            is List<*> -> v.map(::convert)
            is Map<*, *> -> linkedMapOf<String, Any?>().apply {
                for ((k, elem) in v) put(k.toString(), convert(elem))
            }
            else -> error("Unsupported snapshot value type: ${v::class.java.name}")
        }
        return convert(raw) as Map<String, Any?>
    }

    @Test
    fun factoryColdStartProfileExposesAllSevenPortsAndModelsFourStageInitFsm() {
        val (ctx, comp) = createColdStart()
        assertEquals(true, comp.state()["enabled"])
        assertEquals(DramColdStartController.ALL_PORTS, comp.additionalMmioPorts())

        val preInit = ctx.ports.find(DramColdStartController.PREINIT_PORT, MmioDevice::class.java)!!
        val commit = ctx.ports.find(DramColdStartController.COMMIT_PORT, MmioDevice::class.java)!!
        val ctl0 = ctx.ports.find(DramColdStartController.CTL0_PORT, MmioDevice::class.java)!!
        val phy0 = ctx.ports.find(DramColdStartController.PHY0_PORT, MmioDevice::class.java)!!
        val arbiter = ctx.ports.find(DramColdStartController.ARBITER_PORT, MmioDevice::class.java)!!
        val bridge = ctx.ports.find(DramColdStartController.BRIDGE_PORT, MmioDevice::class.java)!!
        val stage1Flag = ctx.ports.find(DramColdStartController.STAGE1_FLAG_PORT, MmioDevice::class.java)!!

        // 1. Pre-init 24 words + commit trigger (0xFF505200..0xFF50525C & 0xFF505384)
        preInit.write(0x00, 4, 0x1234_5678L)
        preInit.write(0x5c, 4, 0xdead_beefL)
        assertEquals(0x1234_5678L, preInit.read(0x00, 4))
        assertEquals(0xdead_beefL, preInit.read(0x5c, 4))
        commit.write(0, 4, 1L)
        assertEquals(0L, commit.read(0, 4))
        assertFailsWith<IllegalArgumentException> { commit.write(0, 4, 2L) }

        // 2. Stage-1 pre-DDR flag (0xFB000000)
        stage1Flag.write(0, 4, 1L)
        assertEquals(1L, stage1Flag.read(0, 4))

        // 3. CTL0 / PHY0 4-stage initialization FSM
        assertEquals(0L, ctl0.read(0x0c18, 4))
        assertEquals(0L, phy0.read(0x2670, 4))
        ctl0.write(0x0c18, 4, 0x4000_0000L) // Stage 0 -> 1
        assertEquals(1L, ctl0.read(0x0c18, 4))
        assertEquals(0x0003_0000L, phy0.read(0x2670, 4))
        assertEquals(0x0003_0003L, phy0.read(0x2674, 4))
        assertEquals(0x0003_0003L, phy0.read(0x2678, 4))

        ctl0.write(0x0040, 4, 0x3L) // Stage 1 -> 2
        assertEquals(0L, ctl0.read(0x0044, 4))

        phy0.write(0x20dc, 4, 0x0000_1005L)
        phy0.write(0x0000, 4, 0x1L) // Stage 2 -> 3
        assertEquals(0x0000_0001L, phy0.read(0x0110, 4))
        assertEquals(0x0001_0001L, phy0.read(0x244c, 4))
        assertEquals(0x0001_0001L, phy0.read(0x2620, 4))
        assertEquals(0x0000_0305L, phy0.read(0x20dc, 4))
        assertEquals(0x0000_0300L, phy0.read(0x22dc, 4))

        ctl0.write(0x0360, 4, 0xff00_c00dL) // Stage 3 -> 4 + ACK pending
        assertEquals(1L, ctl0.read(0x0364, 4))
        ctl0.write(0x0364, 4, 1L) // clear ACK
        assertEquals(0L, ctl0.read(0x0364, 4))

        // 4. Arbiter (392 offsets) & Bridge (8 offsets)
        arbiter.write(0x10, 4, 0x1111_2222L)
        arbiter.write(22L * 0x200L + 0x90L, 4, 0x3333_4444L)
        assertEquals(0x1111_2222L, arbiter.read(0x10, 4))
        assertEquals(0x3333_4444L, arbiter.read(22L * 0x200L + 0x90L, 4))

        bridge.write(0x0000, 4, 0xaa55_0001L)
        bridge.write(0x3004, 4, 0xaa55_0008L)
        assertEquals(0xaa55_0001L, bridge.read(0x0000, 4))
        assertEquals(0xaa55_0008L, bridge.read(0x3004, 4))

        // 5. Fail-closed on unlisted offsets, holes, and bad widths
        assertFailsWith<IllegalArgumentException> { ctl0.read(0x0010, 4) }
        assertFailsWith<IllegalArgumentException> { phy0.read(0x035c, 4) } // hole [0x035C..0x1FFF]
        assertFailsWith<IllegalArgumentException> { phy0.read(0x1ffc, 4) }
        assertFailsWith<IllegalArgumentException> { arbiter.read(0x0000, 4) }
        assertFailsWith<IllegalArgumentException> { bridge.read(0x0008, 4) }
        assertFailsWith<IllegalArgumentException> { preInit.read(0x00, 2) }
        assertFailsWith<IllegalArgumentException> { stage1Flag.read(4, 4) }

        val coldState = comp.state()["coldStart"] as Map<*, *>
        assertEquals(4, coldState["dramInitStage"])
        assertEquals(false, coldState["ctl0AckPending"])
        assertEquals(1L, coldState["commitCount"])
        assertEquals(2, coldState["preInitNonZeroCount"])
        comp.close()
    }

    @Test
    fun factoryColdStartSnapshotRoundTripAndAtomicRejection() {
        val (ctx1, comp1) = createColdStart()
        ctx1.ports.find(DramColdStartController.PREINIT_PORT, MmioDevice::class.java)!!.write(0x08, 4, 0xcafe_babeL)
        ctx1.ports.find(DramColdStartController.CTL0_PORT, MmioDevice::class.java)!!.apply {
            write(0x0c18, 4, 0x4000_0000L)
            write(0x0040, 4, 0x10L)
        }
        ctx1.ports.find(DramColdStartController.PHY0_PORT, MmioDevice::class.java)!!.write(0x0000, 4, 1L)
        ctx1.ports.find(DramColdStartController.CTL0_PORT, MmioDevice::class.java)!!.write(0x0360, 4, 0xff00_c00dL)
        ctx1.ports.find(DramColdStartController.STAGE1_FLAG_PORT, MmioDevice::class.java)!!.write(0, 4, 1L)

        val saved = roundTripJson(comp1.saveSnapshot())
        val (ctx2, comp2) = createColdStart()
        comp2.restoreSnapshot(saved)
        assertEquals(comp1.state(), comp2.state())
        assertEquals(1L, ctx2.ports.find(DramColdStartController.CTL0_PORT, MmioDevice::class.java)!!.read(0x0364, 4))
        assertEquals(0xcafe_babeL, ctx2.ports.find(DramColdStartController.PREINIT_PORT, MmioDevice::class.java)!!.read(0x08, 4))

        // Invalid snapshot mutation must reject atomically without mutating live state
        val baseline = comp2.state()
        val corrupted = LinkedHashMap(saved)
        @Suppress("UNCHECKED_CAST")
        val badCold = LinkedHashMap(corrupted["coldStart"] as Map<String, Any?>)
        badCold["initStage"] = 2 // conflicts with ctl0AckPending == true
        corrupted["coldStart"] = badCold
        assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(corrupted) }
        assertEquals(baseline, comp2.state())

        // Reject inputFile when factory-coldstart profile is selected
        val factory = DramFactory()
        assertFailsWith<IllegalArgumentException> {
            factory.create(Context(), mapOf("profile" to DramFactory.FACTORY_COLDSTART_PROFILE, "inputFile" to "unused.yaml"))
        }
        comp1.close()
        comp2.close()
    }

    @Test
    fun coldStartChannel0RunsStage1InitOnBank0ThenHandsOffCleanlyToRuntimeBankWithZeroExtraPorts() {
        val f = DramFixture()
        val cf = CommandFixture()
        val doc = linkedMapOf<String, Any?>(
            "schema" to 1,
            "profile" to DramFactory.SNAPSHOT_PROFILE,
            "firmwareSha256" to DramFactory.FIRMWARE,
            "programmingWords" to f.programming.map { (o, v) -> mapOf("offset" to o, "value" to v) },
            "configurationWords" to f.config.map { (o, v) -> mapOf("offset" to o, "value" to v) },
            "commandTableFiles" to (0..3).associate { it.toString() to "command-$it.yaml" },
            "banks" to linkedMapOf<String, Any?>(
                "0" to linkedMapOf<String, Any?>(
                    "responseTicks" to listOf(80, 80, 80, 80, 80),
                    "initialController7044" to 0x80,
                    "initialController7364" to 0x40,
                    "responses" to (0..4).associate { it.toString() to f.reply(it).toList() },
                    "commandPlans" to listOf(mapOf("mode" to 4, "responseTicks" to 80, "responseWord" to 0, "counterWord" to null)),
                    "snapshotResponse" to null,
                ),
                "1" to linkedMapOf<String, Any?>(
                    "responseTicks" to listOf(80, 80, 80, 80, 80),
                    "initialController7044" to 0x80,
                    "initialController7364" to 0x40,
                    "responses" to (0..4).associate { it.toString() to f.reply(it).toList() },
                    "commandPlans" to listOf(mapOf("mode" to 4, "responseTicks" to 80, "responseWord" to 0, "counterWord" to null)),
                    "snapshotResponse" to linkedMapOf<String, Any?>("responseTicks" to 80, "pendingStatusWord" to 0, "responseWord" to 1),
                ),
            ),
        )
        val cmdFiles = (0..3).associate { bank ->
            "command-$bank.yaml" to linkedMapOf<String, Any?>(
                "schema" to 1,
                "bank" to bank,
                "profile" to DramFactory.SNAPSHOT_PROFILE,
                "firmwareSha256" to DramFactory.FIRMWARE,
                "modes" to (0..4).associate { mode ->
                    mode.toString() to cf.tables(bank)[mode].map { (o, v) -> mapOf("offset" to o, "value" to v) }
                },
            )
        }
        fun makeCtx(): ComponentContext = object : ComponentContext {
            override val directory: Path = Path.of("unused-dram-ch0-test").toAbsolutePath().normalize()
            override val ports = Registry()
            override val clock = EmulationClock { 0L }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = if (relative == "input.yaml") doc else cmdFiles.getValue(relative)
        }

        val factory = DramFactory()
        val cfg = mapOf(
            "profile" to DramFactory.SNAPSHOT_PROFILE,
            "inputFile" to "input.yaml",
            "repeatableCommandModes" to true,
            "coldStartChannel0" to true,
        )
        val ctx1 = makeCtx()
        val comp1 = assertIs<StatefulComponent>(factory.create(ctx1, cfg))
        comp1.start()
        // Zero extra MMIO ports: DramContracts port set stays identical to standard banks 0 & 1!
        assertEquals(
            listOf("soc-dram.controller-0", "soc-dram.phy-0", "soc-dram.controller-1", "soc-dram.phy-1", "soc-dram.snapshot-ack-1"),
            comp1.additionalMmioPorts(),
        )

        val ctl0 = ctx1.ports.find("soc-dram.controller-0", MmioDevice::class.java)!!
        val phy0 = ctx1.ports.find("soc-dram.phy-0", MmioDevice::class.java)!!

        // Pre-init & commit on controller-0 (0x5200..0x525C, 0x5384)
        ctl0.write(0x5200, 4, 0x1122_3344L)
        assertEquals(0x1122_3344L, ctl0.read(0x5200, 4))
        ctl0.write(0x5384, 4, 1L)
        assertEquals(0L, ctl0.read(0x5384, 4))

        // Stage-1 4-stage init FSM on controller-0 (0x7000..) & phy-0 (0x2000..)
        ctl0.write(0x7c18, 4, 0x4000_0000L) // stage 0 -> 1
        assertEquals(1L, ctl0.read(0x7c18, 4))
        assertEquals(0x0003_0000L, phy0.read(0x4670, 4))
        ctl0.write(0x7040, 4, 0x3L) // stage 1 -> 2
        assertEquals(0L, ctl0.read(0x7044, 4))
        phy0.write(0x2000, 4, 0x1L) // stage 2 -> 3
        assertEquals(0x0000_0001L, phy0.read(0x2110, 4))
        ctl0.write(0x7360, 4, 0xff00_c00dL) // stage 3 -> 4 + ackPending=true
        assertEquals(1L, ctl0.read(0x7364, 4))

        // Snapshot mid-coldstart & restore into fresh component
        val midSnap = roundTripJson(comp1.saveSnapshot())
        val ctx2 = makeCtx()
        val comp2 = assertIs<StatefulComponent>(factory.create(ctx2, cfg))
        comp2.start()
        comp2.restoreSnapshot(midSnap)
        assertEquals(comp1.state(), comp2.state())

        // Clearing ACK completes coldStartChannel0 -> seamless handoff to runtime DramPairBank (initialController7044 = 0x80)!
        val ctl0Restored = ctx2.ports.find("soc-dram.controller-0", MmioDevice::class.java)!!
        ctl0Restored.write(0x7364, 4, 1L)
        assertEquals(0x80L, ctl0Restored.read(0x7044, 4), "Post-init read at 0x7044 must route to runtime DramPairBank")
        assertEquals(0x40L, ctl0Restored.read(0x7364, 4), "Post-init read at 0x7364 must route to runtime DramPairBank")

        comp1.close()
        comp2.close()
    }
}
