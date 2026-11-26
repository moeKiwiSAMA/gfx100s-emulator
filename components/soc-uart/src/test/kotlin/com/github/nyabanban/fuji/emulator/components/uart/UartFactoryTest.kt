package com.github.nyabanban.fuji.emulator.components.uart

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class UartFactoryTest {
    private class TestContext : ComponentContext {
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
            override fun publish(event: ComponentEvent) { error("No UART event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun create(enabled: Boolean = true, initialFill: Long = 0L): Pair<TestContext, StatefulComponent> {
        val ctx = TestContext()
        val profile = if (enabled) UartFactory.PROFILE else "disabled"
        val comp = UartFactory().create(ctx, mapOf("profile" to profile, "initialFill" to initialFill)) as StatefulComponent
        comp.start()
        return ctx to comp
    }

    private fun port(ctx: TestContext, name: String): MmioDevice =
        assertNotNull(ctx.ports.find(name, MmioDevice::class.java))

    @Test
    fun disabledDefaultPublishesNoPorts() {
        val (ctx, comp) = create(enabled = false)
        assertTrue(comp.additionalMmioPorts().isEmpty())
        assertNull(ctx.ports.find(UartFactory.PORT_BANK, MmioDevice::class.java))
        assertNull(ctx.ports.find(UartFactory.PORT_CONFIG, MmioDevice::class.java))
        assertNull(ctx.ports.find(UartFactory.PORT_TAIL, MmioDevice::class.java))
        assertEquals(false, comp.state()["enabled"])

        val (_, comp2) = create(enabled = false)
        comp2.restoreSnapshot(comp.saveSnapshot())
        assertEquals(comp.state(), comp2.state())
    }

    @Test
    fun enforcesExactRegisterDirectionsProhibitedHolesWidthsAndAlignment() {
        val (ctx, comp) = create(enabled = true, initialFill = 0x5aL)
        assertEquals(listOf(UartFactory.PORT_BANK, UartFactory.PORT_CONFIG, UartFactory.PORT_TAIL), comp.additionalMmioPorts())
        val bank = port(ctx, UartFactory.PORT_BANK)
        val config = port(ctx, UartFactory.PORT_CONFIG)
        val tail = port(ctx, UartFactory.PORT_TAIL)

        // Check initialFill
        assertEquals(0x5a5a5a5aL, bank.read(0x18, 4))
        assertEquals(0x5a5a5a5aL, bank.read(0x2c, 4))
        assertEquals(0x5a5a5a5aL, config.read(0x00, 4))
        assertEquals(0x5a5a5a5aL, tail.read(0x00, 4))
        assertEquals(0x5a5a5a5aL, tail.read(0x04, 4))

        // Write-only in bank: 0x00, 0x24, 0x28
        for (off in listOf(0x00L, 0x24L, 0x28L)) {
            assertFails { bank.read(off, 4) }
            bank.write(off, 4, 0x100L + off)
        }
        // Read-only in bank: 0x18
        assertFails { bank.write(0x18, 4, 1L) }

        // Read-write in bank (0x2c), config (0x00), tail (0x00, 0x04)
        bank.write(0x2c, 4, 0xdead002cL)
        assertEquals(0xdead002cL, bank.read(0x2c, 4))
        config.write(0x00, 4, 115200L)
        assertEquals(115200L, config.read(0x00, 4))
        tail.write(0x00, 4, 0x11112222L)
        tail.write(0x04, 4, 0x07d0L)
        assertEquals(0x11112222L, tail.read(0x00, 4))
        assertEquals(0x07d0L, tail.read(0x04, 4))

        // Prohibited holes in bank: 0x04..0x14, 0x1c..0x20
        for (hole in listOf(0x04L, 0x08L, 0x0cL, 0x10L, 0x14L, 0x1cL, 0x20L)) {
            assertFails { bank.read(hole, 4) }
            assertFails { bank.write(hole, 4, 0L) }
        }

        // Width, alignment, and out-of-bounds refusals
        for ((dev, off) in listOf(bank to 0x2cL, config to 0x00L, tail to 0x00L)) {
            for (width in listOf(1, 2, 8)) {
                assertFails { dev.read(off, width) }
                assertFails { dev.write(off, width, 0L) }
            }
            assertFails { dev.read(off + 2, 4) }
            assertFails { dev.write(off + 2, 4, 0L) }
        }
        assertFails { bank.read(48L, 4) }
        assertFails { config.read(4L, 4) }
        assertFails { tail.read(8L, 4) }
    }

    @Test
    fun snapshotRoundTripAndAtomicInvalidRejection() {
        val (ctx1, comp1) = create(enabled = true)
        port(ctx1, UartFactory.PORT_BANK).write(0x2c, 4, 0x12345678L)
        port(ctx1, UartFactory.PORT_CONFIG).write(0x00, 4, 0xabcdefffL)
        port(ctx1, UartFactory.PORT_TAIL).write(0x04, 4, 0x07d0L)
        val snap = comp1.saveSnapshot()

        val (ctx2, comp2) = create(enabled = true)
        comp2.restoreSnapshot(snap)
        assertEquals(comp1.state(), comp2.state())
        assertEquals(0x12345678L, port(ctx2, UartFactory.PORT_BANK).read(0x2c, 4))
        assertEquals(0xabcdefffL, port(ctx2, UartFactory.PORT_CONFIG).read(0x00, 4))
        assertEquals(0x07d0L, port(ctx2, UartFactory.PORT_TAIL).read(0x04, 4))

        val before = comp2.state()
        for (bad in listOf(
            snap + ("schema" to 9),
            snap + ("componentId" to "other"),
            snap + ("words" to listOf(0L)),
            snap + ("readCount" to -1L),
        )) {
            assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(bad) }
            assertEquals(before, comp2.state())
        }
    }

    @Test
    fun pl011TxReadyProfileReturnsTxfeRxfeAndAllowsSnapshotPromotion() {
        val ctx = TestContext()
        val comp = UartFactory().create(
            ctx,
            mapOf("profile" to UartFactory.PL011_TX_READY_PROFILE, "initialFill" to 0x5aL),
        ) as StatefulComponent
        comp.start()
        assertEquals(listOf(UartFactory.PORT_BANK, UartFactory.PORT_CONFIG, UartFactory.PORT_TAIL), comp.additionalMmioPorts())
        val bank = port(ctx, UartFactory.PORT_BANK)
        assertEquals(UartFactory.PL011_FR_EMPTY, bank.read(0x18, 4))
        assertFails { bank.write(0x18, 4, 0L) }
        bank.write(0x00, 4, 0x41L)
        assertEquals(UartFactory.PL011_FR_EMPTY, bank.read(0x18, 4))
        bank.write(0x2c, 4, 0x00000070L)
        assertEquals(0x00000070L, bank.read(0x2c, 4))

        val pl011Snap = comp.saveSnapshot()
        val (ctxSame, compSame) = TestContext().let { c ->
            val u = UartFactory().create(c, mapOf("profile" to UartFactory.PL011_TX_READY_PROFILE, "initialFill" to 0x5aL)) as StatefulComponent
            u.start()
            c to u
        }
        compSame.restoreSnapshot(pl011Snap)
        assertEquals(UartFactory.PL011_FR_EMPTY, port(ctxSame, UartFactory.PORT_BANK).read(0x18, 4))
        assertEquals(0x00000070L, port(ctxSame, UartFactory.PORT_BANK).read(0x2c, 4))

        // Promotion from disabled and from retained-uart-experimental-v1 into pl011-tx-ready-experimental-v1
        val (_, disabledComp) = create(enabled = false)
        val disabledSnap = disabledComp.saveSnapshot()
        compSame.restoreSnapshot(disabledSnap)
        assertEquals(UartFactory.PL011_FR_EMPTY, port(ctxSame, UartFactory.PORT_BANK).read(0x18, 4))

        val (ctxRet, retainedComp) = create(enabled = true)
        port(ctxRet, UartFactory.PORT_BANK).write(0x2c, 4, 0x00000060L)
        val retainedSnap = retainedComp.saveSnapshot()
        compSame.restoreSnapshot(retainedSnap)
        assertEquals(0x00000060L, port(ctxSame, UartFactory.PORT_BANK).read(0x2c, 4))
        assertEquals(UartFactory.PL011_FR_EMPTY, port(ctxSame, UartFactory.PORT_BANK).read(0x18, 4))

        // Retained profile must reject disabled and pl011-tx-ready snapshots
        assertFailsWith<IllegalArgumentException> { retainedComp.restoreSnapshot(disabledSnap) }
        assertFailsWith<IllegalArgumentException> { retainedComp.restoreSnapshot(pl011Snap) }
    }

    @Test
    fun uartDrWritesCaptureTxRingBufferWrapAroundSnapshotAndOptionalLineLogging() {
        val ctx = TestContext()
        val comp = UartFactory().create(
            ctx,
            mapOf("profile" to UartFactory.PL011_TX_READY_PROFILE, "initialFill" to 0L, "logTx" to true),
        ) as StatefulComponent
        comp.start()
        val bank = port(ctx, UartFactory.PORT_BANK)

        val loggedLines = mutableListOf<String>()
        val logger = java.util.logging.Logger.getLogger(UartFactory::class.java.name)
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                loggedLines.add(record.message)
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            val msg = "Booting Linux on GFX100S\r\nHello Alpine!\nPartial"
            for (b in msg.toByteArray(Charsets.UTF_8)) {
                bank.write(0x00, 4, (b.toInt() and 0xff).toLong())
            }
            val st = comp.state()
            assertEquals(msg.length.toLong(), st["txBytesTotal"])
            assertEquals(false, st["txBufferTruncated"])
            assertEquals(msg, st["txText"])
            assertEquals(
                listOf("[UART/TX] Booting Linux on GFX100S", "[UART/TX] Hello Alpine!"),
                loggedLines,
            )

            // Snapshot preserves TX ring bytes cleanly
            val snap = comp.saveSnapshot()
            val ctx2 = TestContext()
            val restored = UartFactory().create(
                ctx2,
                mapOf("profile" to UartFactory.PL011_TX_READY_PROFILE, "initialFill" to 0L, "logTx" to true),
            ) as StatefulComponent
            restored.start()
            restored.restoreSnapshot(snap)
            assertEquals(msg, restored.state()["txText"])
            assertEquals(msg.length.toLong(), restored.state()["txBytesTotal"])

            // Verify 64 KiB circular wrap retains newest bytes and marks truncated
            val bank2 = port(ctx2, UartFactory.PORT_BANK)
            val overflowTotal = UartFactory.TX_RING_CAPACITY + 16
            for (i in 0 until overflowTotal) {
                bank2.write(0x00, 4, ('A'.code + (i % 26)).toLong())
            }
            val st2 = restored.state()
            assertEquals(msg.length.toLong() + overflowTotal.toLong(), st2["txBytesTotal"])
            assertEquals(true, st2["txBufferTruncated"])
            val retained = st2["txText"] as String
            assertEquals(UartFactory.TX_RING_CAPACITY, retained.length)
            val expectedTail = String(CharArray(16) { ('A'.code + ((UartFactory.TX_RING_CAPACITY + it) % 26)).toChar() })
            assertTrue(retained.endsWith(expectedTail))

            // Closing flushes trailing partial line when logTx=true
            (comp as HardwareComponent).close()
            assertTrue(loggedLines.contains("[UART/TX] Partial"))
            (restored as HardwareComponent).close()
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun pl011MultiUartProfileExposesThreeChannelsFullPl011OffsetsAndSnapshotPromotion() {
        val ctx = TestContext()
        val comp = UartFactory().create(
            ctx,
            mapOf("profile" to UartFactory.PL011_MULTI_UART_PROFILE, "initialFill" to 0L),
        ) as StatefulComponent
        comp.start()
        assertEquals(
            listOf(UartFactory.PORT_UART0, UartFactory.PORT_UART1, UartFactory.PORT_UART2),
            comp.additionalMmioPorts(),
        )
        val u0 = port(ctx, UartFactory.PORT_UART0)
        val u1 = port(ctx, UartFactory.PORT_UART1)
        val u2 = port(ctx, UartFactory.PORT_UART2)

        for ((idx, ch) in listOf(u0, u1, u2).withIndex()) {
            assertEquals(UartFactory.PL011_FR_EMPTY, ch.read(0x18, 4))
            assertEquals(0L, ch.read(0x00, 4)) // RXFE=1 empty read
            assertEquals(0L, ch.read(0x3c, 4)) // RIS
            assertEquals(0L, ch.read(0x40, 4)) // MIS
            ch.write(0x24, 4, 0x10L + idx) // IBRD
            ch.write(0x28, 4, 0x20L + idx) // FBRD
            ch.write(0x2c, 4, 0x70L)       // LCR_H
            ch.write(0x30, 4, 0x301L)      // CR
            ch.write(0x34, 4, 0x12L)       // IFLS
            ch.write(0x38, 4, 0x50L)       // IMSC
            ch.write(0x44, 4, 0x7ffL)      // ICR (WO)
            ch.write(0x48, 4, 0x3L)        // DMACR
            assertEquals(0x10L + idx, ch.read(0x24, 4))
            assertEquals(0x3L, ch.read(0x48, 4))
            assertFails { ch.read(0x44, 4) }  // ICR is WO
            assertFails { ch.write(0x18, 4, 0L) } // FR is RO
            assertFails { ch.write(0x3c, 4, 0L) } // RIS is RO
            assertFails { ch.write(0x40, 4, 0L) } // MIS is RO
            assertFails { ch.read(0x04, 4) }  // unmodeled hole
            assertFails { ch.read(0x4c, 4) }  // out of bounds
        }

        // UART1 TX capture ring works identically to pl011-tx-ready-experimental-v1
        for (b in "BT-OK".toByteArray(Charsets.UTF_8)) {
            u1.write(0x00, 4, (b.toInt() and 0xff).toLong())
        }
        assertEquals("BT-OK", comp.state()["txText"])

        // Snapshot round-trip preserves all 3 channels
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        val comp2 = UartFactory().create(
            ctx2,
            mapOf("profile" to UartFactory.PL011_MULTI_UART_PROFILE, "initialFill" to 0L),
        ) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state(), comp2.state())
        assertEquals(0x12L, port(ctx2, UartFactory.PORT_UART2).read(0x24, 4))

        // Promotion from pl011-tx-ready-experimental-v1 into pl011-multi-uart-experimental-v1
        val ctxLegacy = TestContext()
        val legacy = UartFactory().create(
            ctxLegacy,
            mapOf("profile" to UartFactory.PL011_TX_READY_PROFILE, "initialFill" to 0L),
        ) as StatefulComponent
        legacy.start()
        port(ctxLegacy, UartFactory.PORT_BANK).write(0x2c, 4, 0x60L)
        val legacySnap = legacy.saveSnapshot()
        comp2.restoreSnapshot(legacySnap)
        assertEquals(0x60L, port(ctx2, UartFactory.PORT_UART1).read(0x2c, 4))
        assertEquals(UartFactory.PL011_FR_EMPTY, port(ctx2, UartFactory.PORT_UART0).read(0x18, 4))

        // Downgrade multi-UART snapshot into legacy profile must be rejected
        assertFailsWith<IllegalArgumentException> { legacy.restoreSnapshot(snap) }
    }

    @Test
    fun purePl011SerialPortExchangesBytesWithGenericPeerDrivesIrq258AndRestoresSnapshot() {
        val ctx = TestContext()
        var irq258Asserted = false
        ctx.claims["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                assertEquals(UartFactory.UART1_GIC_IRQ, irq)
                irq258Asserted = high
            }
        }
        val cfg = mapOf(
            "profile" to UartFactory.PL011_MULTI_UART_PROFILE,
            "initialFill" to 0L,
            "uart1IrqEnabled" to true,
        )
        val comp = UartFactory().create(ctx, cfg) as StatefulComponent
        assertEquals(listOf(InterruptRoute("soc-gic.lines", 258..258)), comp.interruptRoutes())
        comp.start()
        val u1 = port(ctx, UartFactory.PORT_UART1)
        val serial1 = ctx.ports.find(UartFactory.PORT_UART1_SERIAL, UartSerialPort::class.java)
            ?: error("Missing ${UartFactory.PORT_UART1_SERIAL}")

        val observedTx = mutableListOf<Int>()
        var lastIbrd = -1
        var lastFbrd = -1
        val echoPeer = object : UartSerialPeer {
            override fun onHostTx(byte: Byte) {
                val v = byte.toInt() and 0xff
                observedTx += v
                serial1.pushRxBytes(byteArrayOf((v xor 0x55).toByte()))
            }

            override fun onLineConfigChanged(ibrd: Int, fbrd: Int, lcrH: Long, uartCr: Long) {
                lastIbrd = ibrd
                lastFbrd = fbrd
            }
        }
        serial1.attachPeer(echoPeer)
        assertEquals(true, comp.state()["uart1PeerAttached"])

        // Baud/line-config notifications propagate cleanly to attached peer
        u1.write(0x24, 4, 0x01L)
        u1.write(0x28, 4, 0x20L)
        assertEquals(1, lastIbrd)
        assertEquals(0x20, lastFbrd)

        // Enable RX+RT interrupts (0x50); initially empty so IRQ 258 deasserted
        u1.write(0x38, 4, 0x50L)
        assertFalse(irq258Asserted)

        // Enable TXIM (0x20) -> TXRIS is set so IRQ 258 asserts, then masking TXIM deasserts
        u1.write(0x38, 4, 0x70L)
        assertTrue(irq258Asserted)
        u1.write(0x38, 4, 0x50L)
        assertFalse(irq258Asserted)

        // Host writes TX -> peer receives TX & pushes RX -> RXFE clears & IRQ 258 asserts
        u1.write(0x00, 4, 0x12L)
        u1.write(0x00, 4, 0x34L)
        assertEquals(listOf(0x12, 0x34), observedTx)
        assertTrue(irq258Asserted)
        assertEquals(UartFactory.PL011_FR_TXFE_ONLY, u1.read(0x18, 4))
        assertEquals(2, serial1.rxPendingBytes())

        // Snapshot round-trip preserves pending RX FIFO bytes
        val snap = comp.saveSnapshot()
        val rx = mutableListOf<Int>()
        while ((u1.read(0x18, 4) and 0x10L) == 0L) {
            rx.add((u1.read(0x00, 4) and 0xffL).toInt())
        }
        assertEquals(listOf(0x12 xor 0x55, 0x34 xor 0x55), rx)
        assertFalse(irq258Asserted)

        val ctx2 = TestContext()
        ctx2.claims["soc-gic.lines"] = ctx.claims["soc-gic.lines"]!!
        val comp2 = UartFactory().create(ctx2, cfg) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        val u1b = port(ctx2, UartFactory.PORT_UART1)
        val rx2 = mutableListOf<Int>()
        while ((u1b.read(0x18, 4) and 0x10L) == 0L) {
            rx2.add((u1b.read(0x00, 4) and 0xffL).toInt())
        }
        assertEquals(listOf(0x12 xor 0x55, 0x34 xor 0x55), rx2)

        serial1.detachPeer(echoPeer)
        comp.close()
        comp2.close()
    }
}
