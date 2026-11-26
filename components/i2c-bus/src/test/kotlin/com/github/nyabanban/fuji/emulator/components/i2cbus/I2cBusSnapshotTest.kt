package com.github.nyabanban.fuji.emulator.components.i2cbus

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class I2cBusSnapshotTest {
    private val activeProfile = "explicit-i2c-buses-experimental-v1"

    private class BusContext(val busData: Map<String, Any?>) : ComponentContext {
        val registeredPorts = hashMapOf<String, Any>()
        override val directory: Path = Path.of("unused-i2c-bus-snapshot-test")
        override val clock = EmulationClock { 0L }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = error("unexpected event")
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(registeredPorts.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) = error("not used")
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = busData
    }

    private fun threeBusData(): Map<String, Any?> = linkedMapOf(
        "buses" to listOf(
            linkedMapOf(
                "index" to 0,
                "kind" to "register",
                "address" to 0x24,
                "registers" to linkedMapOf("0" to 0x11, "1" to 0x22, "255" to 0xfe),
            ),
            linkedMapOf(
                "index" to 1,
                "kind" to "register",
                "address" to 0x3a,
                "registers" to linkedMapOf("10" to 0x55, "11" to 0x66),
            ),
            linkedMapOf(
                "index" to 2,
                "kind" to "empty",
            ),
        ),
    )

    private fun createBuses(
        context: BusContext,
        selectedProfile: String = activeProfile,
        inputFile: String = "buses.json",
    ): StatefulComponent {
        val factory = I2cBusFactory()
        val defaults = factory.descriptor.settings.associate { setting -> setting.key to setting.default }
        return assertIs<StatefulComponent>(
            factory.create(context, defaults + mapOf("profile" to selectedProfile, "inputFile" to inputFile)),
        )
    }

    private fun simulateJsonRoundTrip(raw: Map<String, Any?>): Map<String, Any?> {
        fun convert(value: Any?): Any? = when (value) {
            null, is Boolean, is String -> value
            is Byte, is Short, is Int, is Long -> {
                val number = (value as Number).toLong()
                if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) number.toInt() else number
            }
            is List<*> -> value.map { element -> convert(element) }
            is Map<*, *> -> {
                val copy = linkedMapOf<String, Any?>()
                for ((mapKey, mapVal) in value) {
                    check(mapKey != null)
                    copy[mapKey.toString()] = convert(mapVal)
                }
                copy
            }
            else -> error("Unsupported non-JSON value: ${value::class.java.name}")
        }
        @Suppress("UNCHECKED_CAST")
        return convert(raw) as Map<String, Any?>
    }

    @Test
    fun threeBusesWirePointersMutatedRegistersAndActiveLeasesRoundTripEquivalence() {
        val directContext = BusContext(threeBusData())
        val direct = createBuses(directContext)
        val directConnections = (0..2).map { busIndex ->
            (directContext.registeredPorts.getValue("i2c-bus.bus$busIndex") as I2cBusPort)
                .connect("soc-i2c.controller$busIndex")
        }

        assertTrue(directConnections[0].address(0x24 shl 1))
        directConnections[0].write(255)
        directConnections[0].write(0xa5)
        assertTrue(directConnections[0].address((0x24 shl 1) or 1))

        assertTrue(directConnections[1].address(0x3a shl 1))
        assertFalse(directConnections[2].address(0x50 shl 1))

        val roundTrippedSnapshot = simulateJsonRoundTrip(direct.saveSnapshot())

        val restoredContext = BusContext(threeBusData())
        val restored = createBuses(restoredContext)
        val restoredConnections = (0..2).map { busIndex ->
            (restoredContext.registeredPorts.getValue("i2c-bus.bus$busIndex") as I2cBusPort)
                .connect("soc-i2c.controller$busIndex")
        }
        restored.restoreSnapshot(roundTrippedSnapshot)

        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        fun runPhaseTwo(connections: List<I2cConnection>): List<Int> {
            val outputs = ArrayList<Int>()
            val firstRead = connections[0].peekRead()
            connections[0].consumeRead(firstRead)
            outputs += firstRead
            val secondRead = connections[0].peekRead()
            connections[0].consumeRead(secondRead)
            outputs += secondRead
            assertTrue(connections[0].address(0x24 shl 1))
            connections[0].write(255)
            assertTrue(connections[0].address((0x24 shl 1) or 1))
            val wrappedRead = connections[0].peekRead()
            connections[0].consumeRead(wrappedRead)
            outputs += wrappedRead
            connections[0].stop()

            connections[1].write(10)
            connections[1].write(0x99)
            assertTrue(connections[1].address((0x3a shl 1) or 1))
            val bus1Next = connections[1].peekRead()
            connections[1].consumeRead(bus1Next)
            outputs += bus1Next
            connections[1].stop()

            outputs += if (connections[2].address(0x40 shl 1)) 1 else 0
            return outputs
        }

        val directOutputs = runPhaseTwo(directConnections)
        val restoredOutputs = runPhaseTwo(restoredConnections)
        assertEquals(listOf(0x11, 0x22, 0xa5, 0x66, 0), directOutputs)
        assertEquals(directOutputs, restoredOutputs)
        assertEquals(direct.state(), restored.state())
        assertEquals(direct.saveSnapshot(), restored.saveSnapshot())

        directConnections.forEach(I2cConnection::close)
        restoredConnections.forEach(I2cConnection::close)
        direct.close()
        restored.close()
    }

    @Test
    fun eyeTransfersPreserveInputAndMidReadCursor() {
        val sample = mapOf("write" to listOf(0xb8), "read" to listOf(0x34,0x12))
        val status = mapOf("write" to listOf(0xb3), "read" to listOf(0))
        fun rig(transfers: List<Map<String,List<Int>>>, index: Int = 1, address: Int = 57): Pair<BusContext,StatefulComponent> {
            val context = BusContext(mapOf("buses" to listOf(mapOf(
                "index" to index, "kind" to "eye-sensor-finite-v1", "address" to address, "transfers" to transfers
            ))))
            return context to createBuses(context,inputFile="eye.json")
        }
        val (context, component) = rig(listOf(sample))
        val empty = createBuses(BusContext(mapOf("buses" to listOf(mapOf("index" to 1,"kind" to "empty")))))
        component.restoreSnapshot(empty.saveSnapshot())
        val port = context.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort
        assertFailsWith<IllegalArgumentException> { port.connect("soc-i2c.controller0") }
        val owner = "soc-i2c.controller1"
        val wire = port.connect(owner)
        assertFalse(wire.address(0x74))
        assertFailsWith<IllegalArgumentException> { wire.address(0x73) }
        assertTrue(wire.address(0x72))
        assertFailsWith<IllegalArgumentException> { wire.checkWrite(0xb3) }
        wire.checkWrite(0xb8)
        wire.write(0xb8)
        assertTrue(wire.address(0x73))
        assertEquals(0x34,wire.peekRead())
        assertFailsWith<IllegalArgumentException> { wire.consumeRead(0x12) }
        wire.consumeRead(0x34)
        val snapshot = simulateJsonRoundTrip(component.saveSnapshot())
        val (restoredContext, restored) = rig(listOf(sample,status))
        restored.restoreSnapshot(snapshot)
        val next = (restoredContext.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort).connect(owner)
        for (connection in listOf(wire,next)) {
            assertEquals(0x12,connection.peekRead())
            connection.consumeRead(0x12)
            assertFailsWith<IllegalArgumentException> { connection.peekRead() }
            connection.stop()
        }
        assertFailsWith<IllegalArgumentException> { wire.address(0x72) }
        assertTrue(next.address(0x72))
        next.write(0xb3)
        assertFailsWith<IllegalArgumentException> { rig(listOf(status)).second.restoreSnapshot(snapshot) }
        for (bad in listOf(sample + ("read" to listOf(1)), sample + ("write" to listOf(0x18)),status + ("read" to listOf(256)))) {
            assertFailsWith<IllegalArgumentException> { rig(listOf(bad)) }
        }
        assertFailsWith<IllegalArgumentException> { rig(listOf(sample),index=0) }
        assertFailsWith<IllegalArgumentException> { rig(listOf(sample),address=58) }
        assertFailsWith<IllegalArgumentException> { rig(List(129){sample}) }
    }

    @Test
    fun compatibleBusExpansionAndAtomicInvalidSnapshotRejection() {
        val earlyData = linkedMapOf<String, Any?>(
            "buses" to listOf(
                linkedMapOf(
                    "index" to 0,
                    "kind" to "register",
                    "address" to 0x24,
                    "registers" to linkedMapOf("0" to 0x11),
                ),
                linkedMapOf(
                    "index" to 1,
                    "kind" to "empty",
                ),
            ),
        )
        val earlyContext = BusContext(earlyData)
        val earlyBuses = createBuses(earlyContext, inputFile = "early.json")
        (earlyContext.registeredPorts.getValue("i2c-bus.bus0") as I2cBusPort).connect("owner").use { conn ->
            assertTrue(conn.address(0x24 shl 1))
            conn.write(0)
            conn.write(0x7c)
        }
        val earlySnap = simulateJsonRoundTrip(earlyBuses.saveSnapshot())

        val expandedContext = BusContext(threeBusData())
        val expandedBuses = createBuses(expandedContext, inputFile = "expanded.json")
        expandedBuses.restoreSnapshot(earlySnap)

        val restoredBusStates = expandedBuses.state()["buses"] as List<*>
        assertEquals(0x7c, ((restoredBusStates[0] as Map<*, *>)["registers"] as Map<*, *>)["0"])
        assertEquals(0x22, ((restoredBusStates[0] as Map<*, *>)["registers"] as Map<*, *>)["1"])
        assertEquals(0x55, ((restoredBusStates[1] as Map<*, *>)["registers"] as Map<*, *>)["10"])

        val baselineState = expandedBuses.state()
        val baselineSnapshot = expandedBuses.saveSnapshot()

        fun baseValid(): MutableMap<String, Any?> = LinkedHashMap(simulateJsonRoundTrip(baselineSnapshot))

        val invalidMutations = listOf<(MutableMap<String, Any?>) -> Unit>(
            { snap -> snap["schema"] = 2 },
            { snap -> snap["componentId"] = "soc-i2c" },
            { snap ->
                val rows = (snap["buses"] as List<*>).map { row ->
                    LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                }.toMutableList()
                rows[0]["expectPointer"] = true
                rows[0]["selected"] = false
                snap["buses"] = rows
            },
            { snap ->
                val rows = (snap["buses"] as List<*>).map { row ->
                    LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                }.toMutableList()
                rows[0]["registers"] = mapOf("99" to 1)
                snap["buses"] = rows
            },
            { snap ->
                val rows = (snap["buses"] as List<*>).map { row ->
                    LinkedHashMap(row as Map<*, *>).mapKeys { entry -> entry.key.toString() }.toMutableMap()
                }.toMutableList()
                rows[2]["selected"] = true
                snap["buses"] = rows
            },
        )

        for (mutate in invalidMutations) {
            val candidate = baseValid()
            mutate(candidate)
            assertFailsWith<IllegalArgumentException> {
                expandedBuses.restoreSnapshot(candidate)
            }
            assertEquals(baselineState, expandedBuses.state())
            assertEquals(baselineSnapshot, expandedBuses.saveSnapshot())
        }

        earlyBuses.close()
        expandedBuses.close()
    }

    @Test
    fun liveEyeSensorTogglesOnExistingBus1ConnectionPreservesEmptyNacksAndRestoresLegacySnapshots() {
        val bus1EmptyData = mapOf(
            "buses" to listOf(
                mapOf("index" to 0, "kind" to "empty"),
                mapOf("index" to 1, "kind" to "empty"),
            ),
        )
        val ctx = BusContext(bus1EmptyData)
        val buses = createBuses(ctx)
        val conn = (ctx.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort).connect("soc-i2c.controller1")

        // Before eyeSensorNear is ever patched, bus 1 remains an inactive empty bus (NACKs 0x39 and 0x1c)
        assertFalse(conn.address(0x72))
        assertFalse(conn.address(0x38))
        val legacySnap = simulateJsonRoundTrip(buses.saveSnapshot())
        @Suppress("UNCHECKED_CAST")
        val legacyWithoutEyeKey = legacySnap + ("scalars" to ((legacySnap["scalars"] as Map<String, Any?>) - "eyeSensorNear"))

        // Live PATCH eyeSensorNear=true activates LiveEyeSensor through the existing I2cConnection
        buses.applySettings(mapOf("eyeSensorNear" to true))
        assertEquals(true, buses.state()["eyeSensorNear"])
        assertFalse(conn.address(0x38)) // unrelated 0x1c probe still NACKs
        assertTrue(conn.address(0x72))
        conn.write(0xb3)
        assertTrue(conn.address(0x73))
        assertEquals(0x20, conn.peekRead())
        conn.consumeRead(0x20)
        conn.stop()

        assertTrue(conn.address(0x72))
        conn.write(0xb8)
        assertTrue(conn.address(0x73))
        assertEquals(0xff, conn.peekRead())
        conn.consumeRead(0xff)
        assertEquals(0x03, conn.peekRead())
        conn.consumeRead(0x03)
        conn.stop()

        assertTrue(conn.address(0x72))
        for (b in listOf(0xa8, 0xb0, 0x01, 0xff, 0xff)) conn.write(b)
        conn.stop()

        assertTrue(conn.address(0x72))
        conn.write(0xe5)
        conn.stop()

        assertTrue(conn.address(0x72))
        conn.write(0xb3)
        assertTrue(conn.address(0x73))
        assertEquals(0x00, conn.peekRead())
        conn.consumeRead(0x00)
        conn.stop()

        // Snapshot round-trip preserves liveEye state and thresholds
        val activeSnap = simulateJsonRoundTrip(buses.saveSnapshot())
        val ctx2 = BusContext(bus1EmptyData)
        val buses2 = createBuses(ctx2)
        val conn2 = (ctx2.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort).connect("soc-i2c.controller1")
        buses2.restoreSnapshot(activeSnap)
        assertEquals(buses.state(), buses2.state())

        // Live PATCH eyeSensorNear=false triggers far sample 0x0000 on restored bus
        buses2.applySettings(mapOf("eyeSensorNear" to false))
        assertTrue(conn2.address(0x72))
        conn2.write(0xb3)
        assertTrue(conn2.address(0x73))
        assertEquals(0x20, conn2.peekRead())
        conn2.consumeRead(0x20)
        conn2.stop()

        assertTrue(conn2.address(0x72))
        conn2.write(0xb8)
        assertTrue(conn2.address(0x73))
        assertEquals(0x00, conn2.peekRead())
        conn2.consumeRead(0x00)
        assertEquals(0x00, conn2.peekRead())
        conn2.consumeRead(0x00)
        conn2.stop()

        // Malformed liveEye snapshot rejects atomically
        @Suppress("UNCHECKED_CAST")
        val badRows = (activeSnap["buses"] as List<Map<String, Any?>>).map { row ->
            if (row["index"] == 1) row + ("liveEye" to ((row["liveEye"] as Map<String, Any?>) + ("readPos" to 9))) else row
        }
        val stateBeforeBad = buses2.state()
        assertFailsWith<IllegalArgumentException> { buses2.restoreSnapshot(activeSnap + ("buses" to badRows)) }
        assertEquals(stateBeforeBad, buses2.state())

        // Legacy 2-key scalars snapshot restores cleanly and resets eyeSensorNear=false / empty NACK
        buses2.restoreSnapshot(legacyWithoutEyeKey)
        assertEquals(false, buses2.state()["eyeSensorNear"])
        assertFalse(conn2.address(0x72))

        conn.close()
        conn2.close()
        buses.close()
        buses2.close()
    }

    @Test
    fun liveEyeSensorReturnsChipId0x39AndAcceptsConfigWrites() {
        val bus1EmptyData = mapOf(
            "buses" to listOf(
                mapOf("index" to 0, "kind" to "empty"),
                mapOf("index" to 1, "kind" to "empty"),
            ),
        )
        val ctx = BusContext(bus1EmptyData)
        val buses = createBuses(ctx)
        val conn = (ctx.registeredPorts.getValue("i2c-bus.bus1") as I2cBusPort).connect("soc-i2c.controller1")

        buses.applySettings(mapOf("eyeSensorNear" to true))

        // Read APDS-9930 / TMD27723 ID register 0xB2 (0x80 | 0x20 | 0x12) -> 0x39
        assertTrue(conn.address(0x72))
        conn.write(0xb2)
        assertTrue(conn.address(0x73))
        assertEquals(0x39, conn.peekRead())
        conn.consumeRead(0x39)
        conn.stop()

        // Accept 1-byte sensor configuration writes (ENABLE/ATIME/PTIME/WTIME/PERS/PPULSE/CONTROL/POFFSET)
        for ((cmd, value) in listOf(0xa0 to 0x2d, 0xa1 to 0xff, 0xae to 0x08, 0xaf to 0x20, 0xbe to 0x00)) {
            assertTrue(conn.address(0x72))
            conn.write(cmd)
            conn.write(value)
            conn.stop()
        }

        // Pending PINT status and near proximity sample remain intact after config writes
        assertTrue(conn.address(0x72))
        conn.write(0xb3)
        assertTrue(conn.address(0x73))
        assertEquals(0x20, conn.peekRead())
        conn.consumeRead(0x20)
        conn.stop()

        assertTrue(conn.address(0x72))
        conn.write(0xb8)
        assertTrue(conn.address(0x73))
        assertEquals(0xff, conn.peekRead())
        conn.consumeRead(0xff)
        assertEquals(0x03, conn.peekRead())
        conn.consumeRead(0x03)
        conn.stop()

        conn.close()
        buses.close()
    }

    @Test
    fun externalSlaveRegistrationDispatchesBeforeBuiltInsAndUnregistersCleanly() {
        val ctx = BusContext(
            linkedMapOf(
                "buses" to listOf(
                    linkedMapOf("index" to 0, "kind" to "empty"),
                    linkedMapOf("index" to 1, "kind" to "empty"),
                ),
            ),
        )
        val buses = createBuses(ctx)
        val bus1 = assertIs<I2cBusPort>(ctx.registeredPorts.getValue("i2c-bus.bus1"))
        val written = mutableListOf<Int>()
        val stubSlave = object : I2cSlaveDevice {
            override val address7: Int = 0x36
            private var active = false
            override fun address(byte: Int): Boolean {
                active = (byte ushr 1) == address7
                return active
            }
            override fun checkWrite(byte: Int) { require(active) }
            override fun write(byte: Int) { checkWrite(byte); written += byte }
            override fun peekRead(): Int { require(active); return 0xAB }
            override fun consumeRead(expected: Int) { require(expected == 0xAB) }
            override fun stop() { active = false }
        }
        bus1.registerSlave(stubSlave)
        val conn = bus1.connect("soc-i2c.controller1")
        assertTrue(conn.address((0x36 shl 1) or 0))
        conn.write(0x04)
        assertTrue(conn.address((0x36 shl 1) or 1))
        assertEquals(0xAB, conn.peekRead())
        conn.consumeRead(0xAB)
        conn.stop()
        assertEquals(listOf(0x04), written)

        bus1.unregisterSlave(0x36, stubSlave)
        assertFalse(conn.address((0x36 shl 1) or 0))
        conn.close()
        buses.close()
    }

    @Test
    fun legacyTouchPatchesForwardToRegisteredRearTouchPortAndDeferInterruptRoute() {
        val ctx = BusContext(
            linkedMapOf(
                "buses" to listOf(
                    linkedMapOf("index" to 0, "kind" to "empty"),
                    linkedMapOf("index" to 1, "kind" to "empty"),
                    linkedMapOf("index" to 2, "kind" to "empty"),
                ),
            ),
        )
        val forwarded = mutableListOf<List<Any>>()
        var extEnabled = true
        ctx.ports.register(
            "rear-touch-ic",
            RearTouchPort.PORT_NAME,
            RearTouchPort::class.java,
            object : RearTouchPort {
                override val touchEnabled: Boolean get() = extEnabled
                override fun updateSingleTouch(enabled: Boolean, pressed: Boolean, x: Int, y: Int) {
                    extEnabled = enabled
                    forwarded += listOf(enabled, pressed, x, y)
                }
            },
        )
        val buses = createBuses(ctx)
        assertEquals(true, buses.state()["touchEnabled"])
        assertEquals(emptyList<InterruptRoute>(), buses.interruptRoutes(), "i2c-bus must defer IRQ 153 route when standalone rear-touch-ic owns it")

        buses.applySettings(mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to 400L, "touchY" to 300L))
        assertEquals(listOf(listOf<Any>(true, true, 400, 300)), forwarded)

        buses.applySettings(mapOf("touchEnabled" to false))
        assertEquals(false, buses.state()["touchEnabled"])
        assertEquals(listOf<Any>(true, true, 400, 300), forwarded[0])
        assertEquals(false, forwarded[1][0])
        buses.close()
    }
}
