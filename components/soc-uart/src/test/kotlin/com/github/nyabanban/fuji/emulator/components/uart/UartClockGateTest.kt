package com.github.nyabanban.fuji.emulator.components.uart

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class UartClockGateTest {
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
            override fun publish(event: ComponentEvent) = Unit
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun createUart(
        profile: String = UartFactory.PL011_TX_READY_PROFILE,
        initiallyClocked: Boolean = false,
        policy: String = UartFactory.POLICY_ALLOW_WARN,
    ): Triple<TestContext, StatefulComponent, Triple<MmioDevice, MmioDevice, UartClockGatePort>> {
        val ctx = TestContext()
        val comp = UartFactory().create(
            ctx,
            mapOf(
                "profile" to profile,
                "initialFill" to 0L,
                "initiallyClocked" to initiallyClocked,
                "unclockedAccessPolicy" to policy,
            ),
        ) as StatefulComponent
        comp.start()
        val bank = checkNotNull(ctx.ports.find(UartFactory.PORT_BANK, MmioDevice::class.java))
        val cfg = checkNotNull(ctx.ports.find(UartFactory.PORT_CONFIG, MmioDevice::class.java))
        val gate = checkNotNull(ctx.ports.find(UartFactory.PORT_CLOCK_GATE, UartClockGatePort::class.java))
        return Triple(ctx, comp, Triple(bank, cfg, gate))
    }

    @Test
    fun retailUnclockedUartTracksAttemptsUnderAllowWarnAndUngatesOnUartcrBit0() {
        val (_, comp, handles) = createUart(initiallyClocked = false, policy = UartFactory.POLICY_ALLOW_WARN)
        val (bank, uartCr, gate) = handles

        assertFalse(gate.clockGateState().clocked)
        // Unclocked bootloader/plugin touches UARTFR (+0x18) and UARTDR (+0x00) before enabling UARTCR.UARTEN
        assertEquals(UartFactory.PL011_FR_EMPTY, bank.read(0x18L, 4))
        bank.write(0x00L, 4, 'X'.code.toLong())
        assertEquals(1L, gate.clockGateState().unclockedReadAttempts)
        assertEquals(1L, gate.clockGateState().unclockedTxAttempts)
        assertEquals(1L, comp.state()["unclockedReadAttempts"])
        assertEquals(1L, comp.state()["unclockedTxAttempts"])

        // Enabling UARTCR (0xFFF3A030) bit 0 (UARTEN | TXE | RXE = 0x301) ungates UART0 cleanly
        uartCr.write(0x00L, 4, 0x0301L)
        assertTrue(gate.clockGateState().clocked)
        assertEquals(UartFactory.PL011_FR_EMPTY, bank.read(0x18L, 4))
        for (c in "OK\n") bank.write(0x00L, 4, c.code.toLong())
        // No further unclocked attempts recorded once enabled!
        assertEquals(1L, gate.clockGateState().unclockedReadAttempts)
        assertEquals(1L, gate.clockGateState().unclockedTxAttempts)
        assertEquals("XOK\n", comp.state()["txText"])

        // Snapshot round-trip preserves clocked state and unclocked attempt counters
        val snap = comp.saveSnapshot()
        val (_, restored, restoredHandles) = createUart(initiallyClocked = false, policy = UartFactory.POLICY_ALLOW_WARN)
        restored.restoreSnapshot(snap)
        assertTrue(restoredHandles.third.clockGateState().clocked)
        assertEquals(1L, restoredHandles.third.clockGateState().unclockedTxAttempts)
        assertEquals(1L, restoredHandles.third.clockGateState().unclockedReadAttempts)
    }

    @Test
    fun abortPolicyRaisesExternalDataAbortUntilClockedViaUartcrOrEarlyconPort() {
        val (_, comp, handles) = createUart(initiallyClocked = false, policy = UartFactory.POLICY_ABORT)
        val (bank, uartCr, gate) = handles

        // Unclocked read & TX write throw AXI External Data Abort without recording TX bytes
        val readAbort = assertFailsWith<PeripheralBusAbortException> { bank.read(0x18L, 4) }
        assertEquals(0x18L, readAbort.offset)
        assertFalse(readAbort.write)
        assertEquals(0x008L, readAbort.dfsr)

        val txAbort = assertFailsWith<PeripheralBusAbortException> { bank.write(0x00L, 4, 'A'.code.toLong()) }
        assertEquals(0x00L, txAbort.offset)
        assertTrue(txAbort.write)
        assertEquals(0x808L, txAbort.dfsr)
        assertEquals(0L, comp.state()["txBytesTotal"])
        assertEquals(1L, gate.clockGateState().unclockedReadAttempts)
        assertEquals(1L, gate.clockGateState().unclockedTxAttempts)

        // Explicit clock enable (e.g. Linux earlycon / fuji-spx4-uart driver) ungates cleanly
        gate.setClocked(true)
        assertEquals(UartFactory.PL011_FR_EMPTY, bank.read(0x18L, 4))
        for (c in "Linux earlycon\n") bank.write(0x00L, 4, c.code.toLong())
        assertEquals("Linux earlycon\n", comp.state()["txText"])

        // Disabling UARTCR (bit 0 = 0) re-gates UART0 when initiallyClocked=false
        uartCr.write(0x00L, 4, 0L)
        assertFalse(gate.clockGateState().clocked)
        assertFailsWith<PeripheralBusAbortException> { bank.write(0x00L, 4, 'Z'.code.toLong()) }
        assertEquals(2L, gate.clockGateState().unclockedTxAttempts)
    }
}
