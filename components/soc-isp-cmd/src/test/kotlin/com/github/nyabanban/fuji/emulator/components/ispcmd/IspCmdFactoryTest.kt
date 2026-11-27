package com.github.nyabanban.fuji.emulator.components.ispcmd

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class IspCmdFactoryTest {
    private class TestContext : ComponentContext {
        var currentTick = 100L
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { currentTick }
        override fun readData(relative: String): Map<String, Any?> = emptyMap()
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(claims.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = claims[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        val publishedEvents = ArrayList<ComponentEvent>()
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) { publishedEvents.add(event) }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private class TestRam(private val base: Long = 0x028f2000L, size: Int = 0x2000) : DmaMemory {
        val bytes = ByteArray(size)
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && size > 0L && address + size <= base + bytes.size) {
                "RAM out of bounds: 0x${address.toString(16)}"
            }
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val count = destination.remaining()
            check(address, count.toLong(), false)
            destination.put(bytes, (address - base).toInt(), count)
        }
        override fun write(address: Long, source: ByteBuffer) {
            val count = source.remaining()
            check(address, count.toLong(), true)
            source.get(bytes, (address - base).toInt(), count)
        }
    }

    private val factory = IspCmdFactory()

    private fun defaultConfig(overrides: Map<String, Any> = emptyMap()): Map<String, Any> = mapOf(
        "profile" to IspCmdFactory.PROFILE,
        "c000InitialFill" to 0L,
        "initialFill" to 0L,
        "c000StopProfile" to "",
        "clearC204Start" to false,
        "channelIrqsEnabled" to false,
        "replyProfile" to "",
        "interleaved820001" to "",
        "memoryPort" to "cpu.memory",
    ) + overrides

    @Test
    fun disabledProfileRegistersNoPortsAndRejectsSubProfiles() {
        val ctx = TestContext()
        val comp = factory.create(ctx, defaultConfig(mapOf("profile" to "disabled")))
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertTrue(ctx.claims.isEmpty())
        assertEquals(false, comp.state()["enabled"])

        assertFailsWith<IllegalArgumentException> {
            factory.create(ctx, defaultConfig(mapOf("profile" to "disabled", "clearC204Start" to true)))
        }
        comp.close()
    }

    @Test
    fun c000RunsHolesAndStopBit0PollOnceAndRepeatable() {
        val ctx = TestContext()
        val comp = factory.create(
            ctx,
            defaultConfig(
                mapOf(
                    "c000InitialFill" to 0xa5L,
                    "c000StopProfile" to IspCmdFactory.C000_STOP_ONCE,
                ),
            ),
        )
        assertEquals(IspCmdFactory.ALL_PORTS, comp.additionalMmioPorts())
        val c000 = ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java)!!

        // Initial fill at +0 is 0xa5a5a5a5; write clearing bit 0 initiates REQUESTED stop cycle
        assertEquals(0xa5a5a5a5L, c000.read(0L, 4))
        c000.write(0L, 4, 0xa5a5a5a4L)
        assertEquals("REQUESTED", comp.state()["c000Phase"])
        // First poll sees bit0=0 and latches bit0=1 for the next read
        assertEquals(0xa5a5a5a4L, c000.read(0L, 4))
        assertEquals("ACKNOWLEDGED", comp.state()["c000Phase"])
        assertEquals(0xa5a5a5a5L, c000.read(0L, 4))

        // Once profile refuses a second stop-clear cycle
        assertFailsWith<IllegalStateException> {
            c000.write(0L, 4, 0xa5a5a5a4L)
        }

        // Write-only offset +324 rejects reads; read-only offset +40 rejects writes; hole +160 rejects both
        c000.write(324L, 4, 0x12345678L)
        assertFailsWith<IllegalArgumentException> { c000.read(324L, 4) }
        assertFailsWith<IllegalArgumentException> { c000.write(40L, 4, 1L) }
        assertFailsWith<IllegalArgumentException> { c000.read(160L, 4) }
        assertFailsWith<IllegalArgumentException> { c000.write(160L, 4, 1L) }
        // Still-exposure timing words 0x1c4..0x1d8 (452..472) are RW; 0x1c0 (448) is RO; 0x74 (116) & 0x90 (144) are RW; gaps 444/476 refuse
        for (off in listOf(116L, 144L, 452L, 456L, 460L, 464L, 468L, 472L)) {
            c000.write(off, 4, 0x1000L + off)
            assertEquals(0x1000L + off, c000.read(off, 4))
        }
        assertEquals(0xa5a5a5a5L, c000.read(448L, 4))
        assertFailsWith<IllegalArgumentException> { c000.write(448L, 4, 1L) }
        assertFailsWith<IllegalArgumentException> { c000.read(444L, 4) }
        assertFailsWith<IllegalArgumentException> { c000.write(444L, 4, 1L) }
        assertFailsWith<IllegalArgumentException> { c000.read(476L, 4) }
        assertFailsWith<IllegalArgumentException> { c000.write(476L, 4, 1L) }
        comp.close()

        // Repeatable allows multiple cycles
        val ctx2 = TestContext()
        val repeatable = factory.create(
            ctx2,
            defaultConfig(mapOf("c000StopProfile" to IspCmdFactory.C000_STOP_REPEATABLE)),
        )
        val dev2 = ctx2.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java)!!
        dev2.write(0L, 4, 0x11L)
        repeat(2) {
            dev2.write(0L, 4, 0x10L)
            assertEquals(0x10L, dev2.read(0L, 4))
            assertEquals(0x11L, dev2.read(0L, 4))
        }
        repeatable.close()
    }

    @Test
    fun programmedReplyReadsGuestDmaMemoryAndMatchesSnapshotRoundTrip() {
        val ctx = TestContext()
        val ram = TestRam()
        ctx.ports.register("ram", "cpu.memory", DmaMemory::class.java, ram)
        // Populate 255 bulk 0x81 command entries at 0x028f2940.. (0x104ff requires count=255), with selector 1 byte = 0x42
        val bulkOffset = 0x940
        for (i in 0 until 255) {
            ram.bytes[bulkOffset + i * 8 + 0] = 0x81.toByte()
            ram.bytes[bulkOffset + i * 8 + 1] = 0x00
            ram.bytes[bulkOffset + i * 8 + 2] = if (i == 10) 0x01.toByte() else 0x02.toByte()
            ram.bytes[bulkOffset + i * 8 + 3] = if (i == 10) 0x42.toByte() else 0x11.toByte()
        }

        // Single 0x82 header buffer at 0x028f2000
        fun setSingleSelector(sel: Int) {
            ram.bytes[0] = 0x82.toByte()
            ram.bytes[1] = 0x00
            ram.bytes[2] = sel.toByte()
        }

        val comp = factory.create(
            ctx,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_PROGRAMMED,
                    "interleaved820001" to IspCmdFactory.REPLY_ZERO_ONCE,
                ),
            ),
        ) as StatefulComponent
        val c204 = ctx.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val c20c = ctx.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val reply = ctx.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!
        val bank0 = ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[0], MmioDevice::class.java)!!
        val bank6 = ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[6], MmioDevice::class.java)!!

        // Config banks retain writes independently and enforce bounds
        bank0.write(0L, 4, 0xfeedfaceL)
        assertEquals(0xfeedfaceL, bank0.read(0L, 4))
        bank6.write(40L, 4, 0x1234L)
        assertEquals(0x1234L, bank6.read(40L, 4))
        assertFailsWith<IllegalArgumentException> { bank0.read(28L, 4) }

        // Drive initial zero-once 82 00 01 (8 words descending 0x1c..0x0) + 16 pairs of 82 00 40 / 82 00 41
        setSingleSelector(0x01)
        for (word in 0..7) {
            assertEquals(0L, reply.read((0x1c - word * 4).toLong(), 4))
        }
        repeat(16) {
            setSingleSelector(0x40)
            for (word in 0..7) {
                assertEquals(0L, reply.read((0x1c - word * 4).toLong(), 4))
            }
            setSingleSelector(0x41)
            for (word in 0..7) {
                assertEquals(0L, reply.read((0x1c - word * 4).toLong(), 4))
            }
        }
        setSingleSelector(0x01)

        // Submit initial bulk 0x81 cycle (first MODE MUST be 0x104ffL, advancing tick between observed writes/reads)
        ctx.currentTick++; c204.write(4L, 4, 0x104ffL)
        ctx.currentTick++; c20c.write(16L, 4, 0L)
        ctx.currentTick++; c20c.write(0L, 4, 0L)
        ctx.currentTick++; c20c.write(4L, 4, 0x028f2940L)
        ctx.currentTick++; c20c.write(8L, 4, 0L)
        ctx.currentTick++; c20c.write(12L, 4, 8L)
        ctx.currentTick++; c204.write(0L, 4, 1L)
        ctx.currentTick++; assertEquals(1L, c204.read(0L, 4))
        ctx.currentTick++; assertEquals(0L, c204.read(0L, 4)) // cleared bit0 on second read

        // Save/restore mid-sequence before 0x82 readback
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext().apply { currentTick = ctx.currentTick }
        ctx2.ports.register("ram", "cpu.memory", DmaMemory::class.java, ram)
        val restored = factory.create(
            ctx2,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_PROGRAMMED,
                    "interleaved820001" to IspCmdFactory.REPLY_ZERO_ONCE,
                ),
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)

        val rC204 = ctx2.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val rC20c = ctx2.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val rReply = ctx2.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!
        val rBank6 = ctx2.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[6], MmioDevice::class.java)!!
        assertEquals(0x1234L, rBank6.read(40L, 4))

        // Submit single 0x82 00 01 command
        ctx2.currentTick++; rC204.write(4L, 4, 0x30401L)
        ctx2.currentTick++; rC20c.write(16L, 4, 0L)
        ctx2.currentTick++; rC20c.write(0L, 4, 0L)
        ctx2.currentTick++; rC20c.write(4L, 4, 0x028f2000L)
        ctx2.currentTick++; rC20c.write(8L, 4, 0L)
        ctx2.currentTick++; rC20c.write(12L, 4, 8L)
        ctx2.currentTick++; rC204.write(0L, 4, 1L)
        ctx2.currentTick++; assertEquals(1L, rC204.read(0L, 4))
        ctx2.currentTick++; assertEquals(0L, rC204.read(0L, 4))

        // Read back learned byte 0x42 shifted by 16 at 0x1c, then 7 zeros at 0x18..0x0
        assertEquals(0x00420000L, rReply.read(0x1cL, 4))
        for (word in 1..7) {
            assertEquals(0L, rReply.read((0x1c - word * 4).toLong(), 4))
        }
        assertFailsWith<IllegalStateException> { rReply.read(0x1cL, 4) }

        comp.close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun snapshotPromotionAllowsStopOnceToRepeatableAndRejectsDowngrades() {
        val ctx = TestContext()
        val once = factory.create(
            ctx,
            defaultConfig(mapOf("c000StopProfile" to IspCmdFactory.C000_STOP_ONCE)),
        ) as StatefulComponent
        val snap = once.saveSnapshot()

        val ctx2 = TestContext()
        val repeatComp = factory.create(
            ctx2,
            defaultConfig(mapOf("c000StopProfile" to IspCmdFactory.C000_STOP_REPEATABLE)),
        ) as StatefulComponent
        repeatComp.restoreSnapshot(snap)

        val ctx3 = TestContext()
        val disabledStop = factory.create(ctx3, defaultConfig()) as StatefulComponent
        assertFailsWith<IllegalArgumentException> {
            disabledStop.restoreSnapshot(snap)
        }
        (once as HardwareComponent).close()
        (repeatComp as HardwareComponent).close()
        (disabledStop as HardwareComponent).close()
    }

    @Test
    fun statefulReplyTracksRepeated81WritesAnd82ReadsAcrossSnapshotRoundTrip() {
        val ctx = TestContext()
        val ram = TestRam()
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val comp = factory.create(
            ctx,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_STATEFUL,
                ),
            ),
        ) as StatefulComponent
        val c204 = ctx.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val c20c = ctx.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val reply = ctx.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!

        // Before any 0x81 write, 0x82 00 01 returns 0
        ram.bytes[0] = 0x82.toByte(); ram.bytes[1] = 0; ram.bytes[2] = 1
        assertEquals(0L, reply.read(0x1cL, 4))

        // Commit bulk 0x81 write setting selector 0x0001 = 0x92 and selector 0x004f = 0x08
        val bulkOff = 0x940
        ram.bytes[bulkOff] = 0x81.toByte(); ram.bytes[bulkOff + 1] = 0; ram.bytes[bulkOff + 2] = 1; ram.bytes[bulkOff + 3] = 0x92.toByte()
        ram.bytes[bulkOff + 8] = 0x81.toByte(); ram.bytes[bulkOff + 9] = 0; ram.bytes[bulkOff + 10] = 0x4f; ram.bytes[bulkOff + 11] = 0x08
        c204.write(4L, 4, 0x10402L)
        c20c.write(4L, 4, 0x028f2940L)
        c204.write(0L, 4, 1L)
        assertEquals(1L, c204.read(0L, 4))
        assertEquals(0L, c204.read(0L, 4))

        // Read back repeatedly (>2 cycles) without exhausting
        repeat(3) {
            assertEquals(0x00920000L, reply.read(0x1cL, 4))
            for (word in 1..7) assertEquals(0L, reply.read((0x1c - word * 4).toLong(), 4))
        }

        // Snapshot round-trip preserves learned registers and accepts subsequent updates
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        ctx2.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val restored = factory.create(
            ctx2,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_STATEFUL,
                ),
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val rReply = ctx2.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!
        assertEquals(0x00920000L, rReply.read(0x1cL, 4))
        ram.bytes[2] = 0x4f
        assertEquals(0x00080000L, rReply.read(0x1cL, 4))

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun imx461SiliconShadowEnforcesReadGateRegholdStagingTempReadoutAndSnapshotRoundTrip() {
        val ctx = TestContext()
        val ram = TestRam()
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val comp = factory.create(
            ctx,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON,
                ),
            ),
        ) as StatefulComponent
        val c204 = ctx.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val c20c = ctx.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val reply = ctx.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!

        fun issueWrites(vararg pairs: Pair<Int, Int>) {
            val bulkOff = 0x940
            pairs.forEachIndexed { idx, (sel, value) ->
                ram.bytes[bulkOff + idx * 8 + 0] = 0x81.toByte()
                ram.bytes[bulkOff + idx * 8 + 1] = ((sel ushr 8) and 0xFF).toByte()
                ram.bytes[bulkOff + idx * 8 + 2] = (sel and 0xFF).toByte()
                ram.bytes[bulkOff + idx * 8 + 3] = (value and 0xFF).toByte()
            }
            c204.write(4L, 4, 0x10400L or pairs.size.toLong())
            c20c.write(4L, 4, 0x028f2000L + bulkOff)
            c204.write(0L, 4, 1L)
            assertEquals(1L, c204.read(0L, 4))
            assertEquals(0L, c204.read(0L, 4))
        }

        fun readReg(sel: Int, ptrOffset: Int = 0x000): Int {
            ram.bytes[ptrOffset + 0] = 0x82.toByte()
            ram.bytes[ptrOffset + 1] = ((sel ushr 8) and 0xFF).toByte()
            ram.bytes[ptrOffset + 2] = (sel and 0xFF).toByte()
            c204.write(4L, 4, 0x30401L)
            c20c.write(4L, 4, 0x028f2000L + ptrOffset)
            c204.write(0L, 4, 1L)
            c204.read(0L, 4)
            val word0 = reply.read(0x1cL, 4)
            for (word in 1..7) assertEquals(0L, reply.read((0x1c - word * 4).toLong(), 4))
            return ((word0 ushr 16) and 0xFFL).toInt()
        }

        // 1. SPI read-gate (0x004F bit 3 = 0x08) closed by default -> reads return 0
        assertEquals(0, readReg(Imx461SpiShadow.REG_MDSEL))
        assertEquals(0, readReg(Imx461SpiShadow.REG_TEMP_LO))
        @Suppress("UNCHECKED_CAST")
        assertEquals(2L, (comp.state()["programmedReply"] as Map<String, Any?>)["gatedBlockedReads"])

        // 2. Open read-gate (0x004F = 0x08) -> real boot defaults & 16-bit LE sensor temp (0x00FA = 25.0 C * 10)
        issueWrites(Imx461SpiShadow.REG_READ_GATE to Imx461SpiShadow.READ_GATE_BIT)
        assertEquals(Imx461SpiShadow.DEFAULT_STBY, readReg(Imx461SpiShadow.REG_STBY))
        assertEquals(Imx461SpiShadow.DEFAULT_MDSEL, readReg(Imx461SpiShadow.REG_MDSEL))
        val tempLo = readReg(Imx461SpiShadow.REG_TEMP_LO, ptrOffset = 0x100)
        val tempHi = readReg(Imx461SpiShadow.REG_TEMP_HI, ptrOffset = 0x100)
        assertEquals(Imx461SpiShadow.DEFAULT_SENSOR_TEMP_RAW, (tempHi shl 8) or tempLo)

        // 3. REGHOLD (0x0002 bit 6 = 0x40) stages dynamic exposure/gain registers until cleared
        issueWrites(
            Imx461SpiShadow.REG_REGHOLD to Imx461SpiShadow.REGHOLD_BIT,
            Imx461SpiShadow.REG_PGC_LO to 0x34,
            Imx461SpiShadow.REG_PGC_HI to 0x02,
            Imx461SpiShadow.REG_DGAIN to 0x10,
            Imx461SpiShadow.REG_VMAX_LO to 0x80,
            Imx461SpiShadow.REG_VMAX_HI to 0x0B,
            Imx461SpiShadow.REG_HMAX_LO to 0x20,
            Imx461SpiShadow.REG_HMAX_HI to 0x05,
            Imx461SpiShadow.REG_SHR_0 to 0xAA,
            Imx461SpiShadow.REG_SHR_1 to 0xBB,
            Imx461SpiShadow.REG_SHR_2 to 0x01,
            Imx461SpiShadow.REG_STBY to 0x05, // non-group-hold applies immediately
            Imx461SpiShadow.REG_SLEEP_GATE to Imx461SpiShadow.SLEEP_AWAKE_BIT,
        )
        // Active registers still 0 while REGHOLD is asserted, but STBY & sleep gate applied immediately
        assertEquals(0x00, readReg(Imx461SpiShadow.REG_PGC_LO))
        assertEquals(0x00, readReg(Imx461SpiShadow.REG_SHR_0))
        assertEquals(0x05, readReg(Imx461SpiShadow.REG_STBY))
        assertEquals(0x04, readReg(Imx461SpiShadow.REG_SLEEP_GATE))
        @Suppress("UNCHECKED_CAST")
        val stagedState = comp.state()["programmedReply"] as Map<String, Any?>
        assertEquals(true, stagedState["regholdActive"])
        assertEquals(10, stagedState["stagedCount"])
        assertEquals(true, stagedState["streaming"])
        assertEquals(true, stagedState["sleepAwake"])

        // 4. Snapshot round-trip preserves staged registers before commit, then clearing REGHOLD commits atomically
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        ctx2.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val restored = factory.create(
            ctx2,
            defaultConfig(
                mapOf(
                    "clearC204Start" to true,
                    "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON,
                ),
            ),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val rC204 = ctx2.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val rC20c = ctx2.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val rReply = ctx2.ports.find(IspCmdFactory.PORT_REPLY, MmioDevice::class.java)!!

        // Clear REGHOLD on restored instance -> all 10 staged registers commit atomically
        ram.bytes[0x940] = 0x81.toByte(); ram.bytes[0x941] = 0; ram.bytes[0x942] = 0x02; ram.bytes[0x943] = 0x00
        rC204.write(4L, 4, 0x10401L)
        rC20c.write(4L, 4, 0x028f2940L)
        rC204.write(0L, 4, 1L)
        rC204.read(0L, 4)

        @Suppress("UNCHECKED_CAST")
        val committedState = (restored as HardwareComponent).state()["programmedReply"] as Map<String, Any?>
        assertEquals(false, committedState["regholdActive"])
        assertEquals(0, committedState["stagedCount"])
        assertEquals(0x0234, committedState["pgc16"])
        assertEquals(0x10, committedState["dgain"])
        assertEquals(0x0B80, committedState["vmax12"])
        assertEquals(0x0520, committedState["hmax15"])
        assertEquals(0x01BBAA, committedState["shr24"])

        // Read back committed SHR_0 via 0x82
        ram.bytes[0] = 0x82.toByte(); ram.bytes[1] = 0; ram.bytes[2] = Imx461SpiShadow.REG_SHR_0.toByte()
        assertEquals(0x00AA0000L, rReply.read(0x1cL, 4))

        // Switch the sensor to FHD 29.97p using the pulse period retained by CCDTG.
        val ccdtg = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java))
        val sensor = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        ccdtg.write(0x194, 4, 0x20A10)
        issueWrites(
            0x0002 to 0x00, 0x0001 to 0x92, 0x0004 to 0x03, 0x0016 to 0x88, 0x0017 to 0x03,
            0x001F to 0, 0x0020 to 0, 0x0023 to 0x02, 0x0024 to 0x05,
        )
        ctx.currentTick += 10_000L // Six SHR2 integration units have completed.
        assertEquals(1288, sensor.effectiveExposureState.movieFrameRows)
        assertEquals(1282, sensor.effectiveExposureState.movieShr15)
        assertEquals(39L, Imx461ExposurePort.computeEffectiveExposure(sensor).durationNanos)

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun ultrasonicPiezoConfigBank6UnblocksStopPollFiresEdgeIrq104AndRoundTripsSnapshot() {
        val ctx = TestContext()
        val gic = object : EnabledEdgeInterruptSink {
            var enabled104 = false
            var edgeState104 = EdgeInterruptState(edge = true, high = false, pending = false)
            val transitions = mutableListOf<Pair<Int, Boolean>>()
            override fun lineEnabled(irq: Int): Boolean = irq == IspCmdFactory.IRQ_PIEZO && enabled104
            override fun edgeState(irq: Int): EdgeInterruptState = edgeState104
            override fun setLine(irq: Int, high: Boolean) {
                transitions += irq to high
                if (irq == IspCmdFactory.IRQ_PIEZO) {
                    edgeState104 = edgeState104.copy(high = high, pending = edgeState104.pending || high)
                }
            }
        }
        ctx.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)

        val comp = factory.create(ctx, defaultConfig()) as StatefulComponent
        assertEquals(listOf(IspCmdFactory.IRQ_PIEZO), comp.interruptRoutes().flatMap { it.lines.toList() })
        comp.start()

        val bank6 = assertIs<TickAwareMmioDevice>(ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[6], MmioDevice::class.java))

        // Blocker #1: writing 0 to 0xFFF6C400 (+0x00) stops/resets piezo and reads back bit0=1 (IDLE)
        bank6.write(0x00L, 4, 0L)
        assertEquals(1L, bank6.read(0x00L, 4) and 1L)
        assertNull(bank6.nextDeadline())

        // Sweep config + pre-start W1C clear on +0x24 must NOT start a sweep
        bank6.write(0x0cL, 4, 1L)
        bank6.write(0x10L, 4, 0x04030201L)
        bank6.write(0x14L, 4, 0x1234L)
        bank6.write(0x24L, 4, 1L)
        assertNull(bank6.nextDeadline())
        assertEquals(1L, bank6.read(0x00L, 4) and 1L)

        // Blocker #2: writing 1 to 0xFFF6C404 (+0x04) starts sweep, clears +0x00 bit0 (BUSY), schedules completion
        ctx.currentTick = 1_000L
        bank6.write(0x04L, 4, 1L)
        assertEquals(0L, bank6.read(0x00L, 4) and 1L)
        val firstDue = 1_000L + UltrasonicPiezoController.SWEEP_DURATION_TICKS
        assertEquals(firstDue, bank6.nextDeadline())

        // Snapshot mid-sweep preserves running state and completionTick across restore
        val midSnap = comp.saveSnapshot()
        assertTrue("piezo" in midSnap)
        val ctx2 = TestContext().apply { currentTick = 1_000L }
        ctx2.ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, gic)
        val restored = factory.create(ctx2, defaultConfig()) as StatefulComponent
        restored.start()
        restored.restoreSnapshot(midSnap)
        val rBank6 = assertIs<TickAwareMmioDevice>(ctx2.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[6], MmioDevice::class.java))
        assertEquals(0L, rBank6.read(0x00L, 4) and 1L)
        assertEquals(firstDue, rBank6.nextDeadline())

        // If GIC IRQ 104 is not yet enabled at deadline, retry strictly in the future without dropping IRQ
        rBank6.beforeInstruction(firstDue)
        assertEquals(firstDue + UltrasonicPiezoController.RETRY_POLL_TICKS, rBank6.nextDeadline())
        assertTrue(gic.transitions.isEmpty())

        // Enable IRQ 104 in GIC -> next due tick completes sweep and pulses edge IRQ 104 (true -> false)
        gic.enabled104 = true
        val secondDue = firstDue + UltrasonicPiezoController.RETRY_POLL_TICKS
        rBank6.beforeInstruction(secondDue)
        assertNull(rBank6.nextDeadline())
        assertEquals(1L, rBank6.read(0x00L, 4) and 1L) // IDLE restored
        assertEquals(0L, rBank6.read(0x04L, 4) and 1L) // START cleared
        assertEquals(1L, rBank6.read(0x24L, 4) and 1L) // IRQ status latched
        assertEquals(listOf(IspCmdFactory.IRQ_PIEZO to true, IspCmdFactory.IRQ_PIEZO to false), gic.transitions)

        // Cleanup W1C on +0x24 clears bit0; +0x28 remains retained
        rBank6.write(0x24L, 4, 1L)
        assertEquals(0L, rBank6.read(0x24L, 4) and 1L)
        rBank6.write(0x28L, 4, 0xcafebabeL)
        assertEquals(0xcafebabeL, rBank6.read(0x28L, 4))

        // Mid-sweep stop via +0x00 = 0 cancels active sweep cleanly
        ctx2.currentTick = secondDue + 100L
        rBank6.write(0x04L, 4, 1L)
        assertNotNull(rBank6.nextDeadline())
        rBank6.write(0x00L, 4, 0L)
        assertNull(rBank6.nextDeadline())
        assertEquals(1L, rBank6.read(0x00L, 4) and 1L)
        assertEquals(0L, rBank6.read(0x04L, 4) and 1L)

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun channel2And3IrqsFireOnScheduleClearViaW1cAndRoundTripSnapshot() {
        val ctx = TestContext()
        val irqLog = mutableListOf<Pair<Int, Boolean>>()
        val irqSink = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqLog += irq to high
            }
        }
        ctx.ports.register("gic", "soc-gic.lines", InterruptSink::class.java, irqSink)

        val comp = factory.create(
            ctx,
            defaultConfig(mapOf("c000InitialFill" to 0xa5L, "channelIrqsEnabled" to true)),
        ) as StatefulComponent
        assertEquals(
            listOf(
                InterruptRoute("soc-gic.lines", IspCmdFactory.IRQ_PIEZO..IspCmdFactory.IRQ_PIEZO),
                InterruptRoute("soc-gic.lines", IspCmdFactory.CH2_IRQ..IspCmdFactory.CH3_IRQ),
            ),
            (comp as HardwareComponent).interruptRoutes(),
        )
        val c000 = ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java) as TickAwareMmioDevice

        // Before firmware writes start & mask, initial fill 0xA5 must NOT arm or trigger IRQs
        assertEquals(0L, c000.read(0x64L, 4))
        assertEquals(0L, c000.read(0xe4L, 4))
        assertNull(c000.nextDeadline())
        c000.beforeInstruction(10_000_000L)
        assertTrue(irqLog.isEmpty())

        // Arm channel 2 (0xFFF6C060 mask=0xd, 0xFFF6C040 bit0=1)
        c000.write(0x64L, 4, 0xfL)
        c000.write(0x60L, 4, 0xdL)
        c000.write(0x40L, 4, 1L)
        val due = checkNotNull(c000.nextDeadline())
        assertEquals(ctx.currentTick + IspCmdFactory.CHANNEL_IRQ_PERIOD_TICKS, due)

        // Advance to deadline -> sets status bit 0 and asserts GIC IRQ 97
        c000.beforeInstruction(due)
        assertEquals(1L, c000.read(0x64L, 4))
        assertEquals(listOf(97 to true), irqLog)

        // Snapshot while pending & restore preserves pending status and re-asserts line
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext().apply { currentTick = due }
        val irqLog2 = mutableListOf<Pair<Int, Boolean>>()
        ctx2.ports.register("gic", "soc-gic.lines", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqLog2 += irq to high
            }
        })
        val restored = factory.create(
            ctx2,
            defaultConfig(mapOf("c000InitialFill" to 0xa5L, "channelIrqsEnabled" to true)),
        ) as StatefulComponent
        restored.restoreSnapshot(snap)
        val rC000 = ctx2.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java) as TickAwareMmioDevice
        assertEquals(1L, rC000.read(0x64L, 4))
        assertTrue(irqLog2.contains(97 to true))

        // W1C 0xd clears status and deasserts GIC IRQ 97
        rC000.write(0x64L, 4, 0xdL)
        assertEquals(0L, rC000.read(0x64L, 4))
        assertEquals(97 to false, irqLog2.last())

        // Stop channel 2 (0xFFF6C040 bit0 1->0) while mask remains active drains ONE final in-flight frame then stops
        rC000.write(0x40L, 4, 0L)
        val drainDue = checkNotNull(rC000.nextDeadline())
        rC000.beforeInstruction(drainDue)
        assertEquals(1L, rC000.read(0x64L, 4))
        assertEquals(97 to true, irqLog2.last())
        rC000.write(0x64L, 4, 0xdL)
        assertEquals(97 to false, irqLog2.last())
        assertNull(rC000.nextDeadline())

        // Channel 3 with bit 1 only in mask (0xFFF6C0E0=0x2, 0xFFF6C0C0=1) -> status bit 1 & GIC IRQ 98
        rC000.write(0xe0L, 4, 0x2L)
        rC000.write(0xc0L, 4, 1L)
        val due3 = checkNotNull(rC000.nextDeadline())
        rC000.beforeInstruction(due3)
        assertEquals(2L, rC000.read(0xe4L, 4))
        assertEquals(98 to true, irqLog2.last())
        rC000.write(0xe4L, 4, 0x2L)
        assertEquals(0L, rC000.read(0xe4L, 4))
        assertEquals(98 to false, irqLog2.last())
        // Clearing mask disarms immediately
        rC000.write(0xe0L, 4, 0L)
        assertNull(rC000.nextDeadline())

        (comp as HardwareComponent).close()
        (restored as HardwareComponent).close()
    }

    @Test
    fun channelTwoFollowsMovieReadoutEdgesUntilMaskedOrReturnedToFreeRunning() {
        val ctx = TestContext()
        var recording = false
        ctx.ports.register("isp", CcdFrameClockPort.PORT_ID, CcdFrameClockPort::class.java,
            object : CcdFrameClockPort {
                override fun resumeLiveView() {}
                override val movieFrameClockActive get() = recording
            })
        val component = IspCmdFactory().create(ctx, defaultConfig(mapOf("channelIrqsEnabled" to true)))
        val regs = ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java) as TickAwareMmioDevice
        val input = requireNotNull(ctx.ports.find(CcdMovieFrameInput.PORT_ID, CcdMovieFrameInput::class.java))
        regs.write(0x60, 4, 0xd)
        regs.write(0x40, 4, 1)
        val standby = requireNotNull(regs.nextDeadline())
        recording = true
        ctx.currentTick = standby
        regs.beforeInstruction(standby)
        assertEquals(0L, regs.read(0x64, 4))

        for (period in listOf(1_801_800L, 2_252_250L, 2_160_000L)) {
            ctx.currentTick += period
            input.onFrameSync(ctx.currentTick)
            assertEquals(1L, regs.read(0x64, 4))
            regs.write(0x64, 4, 0xd)
        }

        regs.write(0x40, 4, 0)
        ctx.currentTick += 1L
        input.onFrameSync(ctx.currentTick)
        assertEquals(1L, regs.read(0x64, 4))
        regs.write(0x64, 4, 0xd)
        ctx.currentTick += 1L
        input.onFrameSync(ctx.currentTick)
        assertEquals(0L, regs.read(0x64, 4))
        assertNull(regs.nextDeadline())

        regs.write(0x40, 4, 1)
        regs.write(0x60, 4, 0)
        ctx.currentTick += 1L
        input.onFrameSync(ctx.currentTick)
        assertNull(regs.nextDeadline())
        assertEquals(0L, regs.read(0x64, 4))
        regs.write(0x60, 4, 0xd)
        recording = false
        regs.beforeInstruction(requireNotNull(regs.nextDeadline()))
        assertEquals(1L, regs.read(0x64, 4))
        component.close()
    }

    @Test
    fun configBanks0To5ClearActiveBitOnMode4StopCommit() {
        val ctx = TestContext()
        val comp = factory.create(ctx, defaultConfig())
        for (bankIdx in 0..5) {
            val bank = ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[bankIdx], MmioDevice::class.java)!!
            bank.write(0x04L, 4, 1L)
            assertEquals(1L, bank.read(0x04L, 4))
            // Active pulse mode (mode=1) commit keeps +0x04 bit 0 high
            bank.write(0x08L, 4, 1L shl 16)
            bank.write(0x00L, 4, 1L)
            assertEquals(1L, bank.read(0x04L, 4))
            // Stop mode (mode=4, DAT 0x02393f04 / 0x0250a32c) commit clears +0x04 bit 0
            bank.write(0x08L, 4, 4L shl 16)
            bank.write(0x00L, 4, 1L)
            assertEquals(0L, bank.read(0x04L, 4))
        }
        comp.close()
    }

    @Test
    fun stillReadoutDepthSurvivesLiveViewAndSnapshotUntilTheNextCapture() {
        val ctx = TestContext()
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, TestRam())
        val device = factory.create(
            ctx,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON)),
        )
        val state = device as StatefulComponent
        val timing = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java))
        val exposure = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        val legacy = state.saveSnapshot()
        try {
            timing.write(0x024, 4, 0x20010)
            assertFalse(exposure.liveViewTimingActive)
            timing.write(0x008, 4, 0)
            timing.write(0x024, 4, 0)
            assertTrue(exposure.liveViewTimingActive)
            timing.write(0x008, 4, 0xa)
            assertEquals(16, exposure.stillReadoutBitDepth)
            val single = state.saveSnapshot()
            timing.write(0x024, 4, 0x20010)
            timing.write(0x008, 4, 5)
            assertEquals(14, exposure.stillReadoutBitDepth)
            state.restoreSnapshot(single)
            assertEquals(16, exposure.stillReadoutBitDepth)
            state.restoreSnapshot(legacy)
            assertEquals(14, exposure.stillReadoutBitDepth)
        } finally {
            device.close()
        }
    }

    @Test
    fun imx461ExposurePortTracksShrSvrPgcHcgUnderSiliconShadowAndStatefulProfiles() {
        fun issueBulkWrites(c204: MmioDevice, c20c: MmioDevice, ram: TestRam, vararg pairs: Pair<Int, Int>) {
            val bulkOff = 0x940
            pairs.forEachIndexed { idx, (sel, value) ->
                ram.bytes[bulkOff + idx * 8 + 0] = 0x81.toByte()
                ram.bytes[bulkOff + idx * 8 + 1] = ((sel ushr 8) and 0xFF).toByte()
                ram.bytes[bulkOff + idx * 8 + 2] = (sel and 0xFF).toByte()
                ram.bytes[bulkOff + idx * 8 + 3] = (value and 0xFF).toByte()
            }
            c204.write(4L, 4, 0x10400L or pairs.size.toLong())
            c20c.write(4L, 4, 0x028f2000L + bulkOff)
            c204.write(0L, 4, 1L)
            assertEquals(1L, c204.read(0L, 4))
            assertEquals(0L, c204.read(0L, 4))
        }

        // 1. Silicon shadow profile: REGHOLD (0x0002 bit 6) stages SVR/SHR/PGC/PGC2/HCG until cleared
        val ctxShadow = TestContext()
        val ramShadow = TestRam()
        ctxShadow.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ramShadow)
        val compShadow = factory.create(
            ctxShadow,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON)),
        )
        val c204s = ctxShadow.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val c20cs = ctxShadow.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val expShadow = requireNotNull(ctxShadow.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        assertFalse(expShadow.hasExposureRegs)

        issueBulkWrites(
            c204s,
            c20cs,
            ramShadow,
            Imx461SpiShadow.REG_REGHOLD to Imx461SpiShadow.REGHOLD_BIT,
            Imx461SpiShadow.REG_VMAX_LO to 0xD0,
            Imx461SpiShadow.REG_VMAX_HI to 0x07, // VMAX = 2000
            Imx461SpiShadow.REG_SVR_LO to 0x02,
            Imx461SpiShadow.REG_SVR_HI to 0x00, // SVR = 2
            Imx461SpiShadow.REG_SHR_15_LO to 0xE8,
            Imx461SpiShadow.REG_SHR_15_HI to 0x03, // SHR = 1000
            Imx461SpiShadow.REG_HCG to 0x01,
            Imx461SpiShadow.REG_PGC_12_LO to 0x00,
            Imx461SpiShadow.REG_PGC_12_HI to 0x06, // PGC = 1536
            Imx461SpiShadow.REG_PGC2_LO to 0x10,
            Imx461SpiShadow.REG_PGC2_HI to 0x04, // PGC2 = 0x0410
        )
        // Still held under REGHOLD -> active exposure regs not yet committed
        assertEquals(0, expShadow.shr15)
        // Clear REGHOLD -> atomic commit
        issueBulkWrites(c204s, c20cs, ramShadow, Imx461SpiShadow.REG_REGHOLD to 0x00)
        assertTrue(expShadow.hasExposureRegs)
        assertEquals(2000, expShadow.vmax12)
        assertEquals(2, expShadow.svr12)
        assertEquals(1000, expShadow.shr15)
        assertEquals(1536, expShadow.pgc12)
        assertEquals(0x0410, expShadow.pgcSecondary12)
        assertTrue(expShadow.hcgEnabled)
        compShadow.close()

        // 2. Release stateful profile (`committed-81-stateful-v1`) also publishes Imx461ExposurePort
        val ctxStateful = TestContext()
        val ramStateful = TestRam()
        ctxStateful.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ramStateful)
        val compStateful = factory.create(
            ctxStateful,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_STATEFUL)),
        )
        val c204f = ctxStateful.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java)!!
        val c20cf = ctxStateful.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java)!!
        val expStateful = requireNotNull(ctxStateful.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        issueBulkWrites(
            c204f,
            c20cf,
            ramStateful,
            Imx461SpiShadow.REG_SHR_15_LO to 0x50,
            Imx461SpiShadow.REG_SHR_15_HI to 0x02,
            Imx461SpiShadow.REG_HCG to 0x01,
            Imx461SpiShadow.REG_PGC_12_LO to 0x80,
            Imx461SpiShadow.REG_PGC_12_HI to 0x05,
        )
        assertTrue(expStateful.hasExposureRegs)
        assertEquals(0x0250, expStateful.shr15)
        assertEquals(0x0580, expStateful.pgc12)
        assertTrue(expStateful.hcgEnabled)
        assertEquals(Imx461ShutterTiming(0x0250, 0, 0), expStateful.effectiveShutterTiming)

        // Transient out-of-frame SHR=1705 (Film Simulation 255-reg mode reload with SVR=0, VMAX=0)
        // must preserve raw shr15=1705 while holding effectiveShutterTiming at the last valid 0x0250.
        val beforeReload = Imx461ExposurePort.computeEffectiveExposure(expStateful)
        issueBulkWrites(
            c204f,
            c20cf,
            ramStateful,
            Imx461SpiShadow.REG_SVR_LO to 0x00,
            Imx461SpiShadow.REG_SVR_HI to 0x00,
            Imx461SpiShadow.REG_VMAX_LO to 0x00,
            Imx461SpiShadow.REG_VMAX_HI to 0x00,
            Imx461SpiShadow.REG_SHR_15_LO to 0xA9,
            Imx461SpiShadow.REG_SHR_15_HI to 0x06,
        )
        assertEquals(1705, expStateful.shr15)
        assertEquals(Imx461ShutterTiming(0x0250, 0, 0), expStateful.effectiveShutterTiming)
        assertEquals(beforeReload, Imx461ExposurePort.computeEffectiveExposure(expStateful))

        // Snapshot round-trip preserves latched timing across out-of-frame register state
        val snap = (compStateful as StatefulComponent).saveSnapshot()
        val ctxRestored = TestContext()
        ctxRestored.ports.register("cpu", "cpu.memory", DmaMemory::class.java, TestRam())
        val compRestored = factory.create(
            ctxRestored,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_STATEFUL)),
        )
        (compRestored as StatefulComponent).restoreSnapshot(snap)
        val expRestored = requireNotNull(ctxRestored.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        assertEquals(1705, expRestored.shr15)
        assertEquals(Imx461ShutterTiming(0x0250, 0, 0), expRestored.effectiveShutterTiming)
        compRestored.close()
        compStateful.close()
    }

    @Test
    fun tgActRegister0x184NeverEmitsCurtainReleaseAndNotifiesShutterPort() {
        val ctx = TestContext()
        val tgActTicks = ArrayList<Long>()
        val stubShutter = object : ShutterPort {
            override val enabled = true
            override val cam1High = false
            override val cam2High = false
            override val curtainHigh = false
            override fun onMotor1PwmChanged(running: Boolean, tick: Long) = Unit
            override fun onMotor2HBridgeChanged(pin7High: Boolean, pin8High: Boolean, tick: Long) = Unit
            override fun onTgActRising(tick: Long) { tgActTicks.add(tick) }
            override fun onMechanicalCurtainStroke(tick: Long) = Unit
            override fun nextDeadline(): Long? = null
            override fun advanceToTick(tick: Long): Boolean = false
            override fun telemetry() = ShutterTelemetry(
                mode = ShutterMode.ELECTRONIC,
                cam1High = false,
                cam2High = false,
                curtainHigh = false,
                motor1Running = false,
                motor2Running = false,
                motor2Direction = 0,
                cam1Cycles = 0L,
                cam2Cycles = 0L,
                curtainPulses = 0L,
                tgActCount = tgActTicks.size.toLong(),
                shutterCount = tgActTicks.size.toLong(),
                mechanicalCount = 0L,
                efcsCount = 0L,
                electronicCount = tgActTicks.size.toLong(),
                curtainTravelUs = 4000L,
                rollingReadoutUs = 125000L,
                readoutActive = true,
                lastActuationTick = tgActTicks.lastOrNull(),
                readoutCompleteTick = null,
            )
        }
        ctx.ports.register("shutter", ShutterPort.PORT_NAME, ShutterPort::class.java, stubShutter)

        val comp = factory.create(ctx, defaultConfig())
        val c000 = ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java)!!

        ctx.currentTick = 12345L
        c000.write(0x184L, 4, 1L)
        // Repeat 1->1 write must not re-fire
        ctx.currentTick = 12350L
        c000.write(0x184L, 4, 1L)
        // Clear then rise again
        ctx.currentTick = 12360L
        c000.write(0x184L, 4, 0L)
        ctx.currentTick = 12370L
        c000.write(0x184L, 4, 1L)

        assertEquals(listOf(12345L, 12370L), tgActTicks)
        assertTrue(
            ctx.publishedEvents.none { it.kind == "curtainRelease" },
            "BUG-A2-5: 0xFFF6C184 (CCDTG TG_ACT) must never emit curtainRelease",
        )
        assertEquals(listOf("tgAct", "tgAct"), ctx.publishedEvents.map { it.kind })
        comp.close()
    }

    @Test
    fun ccdtg0x194PeriodIncreaseBeforeShr2BurstDoesNotLatchMismatchedWhiteFlash() {
        val ctx = TestContext()
        val ram = TestRam()
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val comp = factory.create(
            ctx,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON)),
        )
        val c000 = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java))
        val c204 = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java))
        val c20c = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java))
        val exposure = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))

        fun spiWrite(vararg pairs: Pair<Int, Int>) {
            val ptr = 0x028f2100L
            val off0 = (ptr - 0x028f2000L).toInt()
            pairs.forEachIndexed { i, (sel, v) ->
                ram.bytes[off0 + i * 8] = 0x81.toByte()
                ram.bytes[off0 + i * 8 + 1] = ((sel ushr 8) and 0xFF).toByte()
                ram.bytes[off0 + i * 8 + 2] = (sel and 0xFF).toByte()
                ram.bytes[off0 + i * 8 + 3] = (v and 0xFF).toByte()
            }
            c20c.write(4L, 4, ptr)
            c204.write(4L, 4, 0x0001_0000L or pairs.size.toLong())
            c204.write(0L, 4, 1L)
        }

        // Steady FHD 59.94p (0x194 = 0x20508 -> 644 rows, SHR2 = 590 -> 54 rows integration = 348 ns)
        c000.write(0x194L, 4, 0x0002_0508L)
        spiWrite(
            0x0001 to 0x92, 0x0004 to 0x07,
            0x0016 to 0x04, 0x0017 to 0x01,
            0x001F to 0x00, 0x0020 to 0x00,
            0x0023 to (590 and 0xFF), 0x0024 to (590 ushr 8),
            0x002E to (2066 and 0xFF), 0x002F to (2066 ushr 8),
        )
        ctx.currentTick += 2_000_000L
        assertEquals(644, exposure.effectiveExposureState.movieFrameRows)
        assertEquals(348L, Imx461ExposurePort.computeEffectiveExposure(exposure).durationNanos)

        // Start-of-recording transition writes 0x194 = 0x20A10 (1288 rows) BEFORE SPI updates SHR2.
        // Must NOT pair (old SHR2=590, new rows=1288) into a 698-row (+3.7 EV) white flash!
        c000.write(0x194L, 4, 0x0002_0A10L)
        assertEquals(644, exposure.effectiveExposureState.movieFrameRows)
        assertEquals(348L, Imx461ExposurePort.computeEffectiveExposure(exposure).durationNanos)

        // Once firmware commits matching SHR2=1234 (54 rows integration), 1288 rows commits cleanly.
        spiWrite(0x0023 to (1234 and 0xFF), 0x0024 to (1234 ushr 8))
        ctx.currentTick += 2_000_000L
        assertEquals(1288, exposure.effectiveExposureState.movieFrameRows)
        assertEquals(1234, exposure.effectiveExposureState.movieShr15)
        assertEquals(348L, Imx461ExposurePort.computeEffectiveExposure(exposure).durationNanos)

        // Reverse ordering: SPI writes SHR2=1500 (out of 1288 frame) BEFORE 0x194 grows to 1548 rows.
        spiWrite(0x0023 to (1500 and 0xFF), 0x0024 to (1500 ushr 8))
        assertEquals(1234, exposure.effectiveExposureState.movieShr15)
        c000.write(0x194L, 4, 0x0002_0C18L) // 3096 / 2 = 1548 rows
        ctx.currentTick += 2_000_000L
        assertEquals(1548, exposure.effectiveExposureState.movieFrameRows)
        assertEquals(1500, exposure.effectiveExposureState.movieShr15)
        comp.close()
    }

    @Test
    fun stillShutterTimingTracksMechanicalEfcsAndElectronicShutterWithResetImmunityAndSnapshot() {
        val ctx = TestContext()
        val ram = TestRam()
        ctx.ports.register("cpu", "cpu.memory", DmaMemory::class.java, ram)
        val comp = factory.create(
            ctx,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON)),
        )
        val c000 = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C000, MmioDevice::class.java))
        val c204 = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C204, MmioDevice::class.java))
        val c20c = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_C20C, MmioDevice::class.java))
        val bank0 = requireNotNull(ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[0], MmioDevice::class.java))
        val bank1 = requireNotNull(ctx.ports.find(IspCmdFactory.CONFIG_BANK_PORTS[1], MmioDevice::class.java))
        val exposure = requireNotNull(ctx.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))

        fun spiWrite(vararg pairs: Pair<Int, Int>) {
            val ptr = 0x028f2100L
            val off0 = (ptr - 0x028f2000L).toInt()
            pairs.forEachIndexed { i, (sel, v) ->
                ram.bytes[off0 + i * 8] = 0x81.toByte()
                ram.bytes[off0 + i * 8 + 1] = ((sel ushr 8) and 0xFF).toByte()
                ram.bytes[off0 + i * 8 + 2] = (sel and 0xFF).toByte()
                ram.bytes[off0 + i * 8 + 3] = (v and 0xFF).toByte()
            }
            c20c.write(4L, 4, ptr)
            c204.write(4L, 4, 0x0001_0000L or pairs.size.toLong())
            c204.write(0L, 4, 1L)
        }

        // 1. Mechanical Shutter (MS): M-mode 1/160 s (450,000 ticks delta) ISO 100 in 14-bit 0x40
        c000.write(0x024L, 4, 0x20010L) // still capture active
        bank1.write(0x08L, 4, 0x0001_0000L) // Front curtain mode=1
        bank1.write(0x0CL, 4, 100_000L)
        bank1.write(0x00L, 4, 1L)
        bank0.write(0x08L, 4, 0x0001_0000L) // Rear curtain mode=1
        bank0.write(0x0CL, 4, 550_000L)
        bank0.write(0x00L, 4, 1L)
        // Zero-delay reset pulse (DAT S5 0x02508adc) must NOT wipe latched delays
        bank0.write(0x0CL, 4, 0L)
        bank0.write(0x00L, 4, 1L)
        spiWrite(
            0x0001 to 0x40,
            0x0012 to 0x00, 0x0013 to 0x00,
            0x0016 to 0x02, 0x0017 to 0x00, // dummy MS SHR=2
            0x002D to 0x00,
            0x002E to (2066 and 0xFF), 0x002F to (2066 ushr 8),
        )
        val msTiming = requireNotNull(exposure.stillShutterTiming)
        assertEquals(550_000L, msTiming.rearCurtainDelayTicks)
        assertEquals(100_000L, msTiming.frontCurtainDelayTicks)
        assertEquals(1563L, requireNotNull(Imx461ExposurePort.computeEffectiveStillExposure(exposure)).durationNanos)

        // Snapshot/restore preserves stillShutterTiming cleanly
        val snap = (comp as StatefulComponent).saveSnapshot()
        val ctx2 = TestContext()
        ctx2.ports.register("cpu", "cpu.memory", DmaMemory::class.java, TestRam())
        val restored = factory.create(
            ctx2,
            defaultConfig(mapOf("clearC204Start" to true, "replyProfile" to IspCmdFactory.REPLY_IMX461_SILICON)),
        )
        (restored as StatefulComponent).restoreSnapshot(snap)
        val exp2 = requireNotNull(ctx2.ports.find(IspCmdFactory.PORT_IMX461_EXPOSURE, Imx461ExposurePort::class.java))
        assertEquals(1563L, requireNotNull(Imx461ExposurePort.computeEffectiveStillExposure(exp2)).durationNanos)
        restored.close()

        // 2. Exiting still capture (0x024 bit 17 1->0) clears curtain latches cleanly
        c000.write(0x024L, 4, 0L)
        assertEquals(null, exposure.stillShutterTiming)

        // 3. Electronic Shutter (ES) 12-bit 0x80 at 1/160 s (SHR=4208)
        c000.write(0x024L, 4, 0x20010L)
        spiWrite(
            0x0001 to 0x80,
            0x0012 to 0x00, 0x0013 to 0x00,
            0x0016 to (4208 and 0xFF), 0x0017 to (4208 ushr 8),
            0x002E to (2066 and 0xFF), 0x002F to (2066 ushr 8),
        )
        assertEquals(1563L, requireNotNull(Imx461ExposurePort.computeEffectiveStillExposure(exposure)).durationNanos)
        comp.close()
    }
}
