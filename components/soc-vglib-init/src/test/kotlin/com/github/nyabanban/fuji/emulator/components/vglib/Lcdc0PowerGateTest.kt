package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Lcdc0PowerGateTest {
    private class StubContext : ComponentContext {
        var currentTick = 100L
        val registry = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-lcdc0-powergate")
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(registry.putIfAbsent(name, port) == null) { "Duplicate port $name" }
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registry[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                registry.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = Unit
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = mapOf(
            "schema" to 1,
            "profile" to VglibInitFactory.PROFILE,
            "readConfidence" to "HYPOTHESIS",
            "transactions" to (0 until 18).map { i ->
                mapOf("offset" to (i * 4L), "readValue" to 0L, "writeValue" to 0L)
            },
        )
    }

    private fun createVglib(
        lcdc0ClockGated: Boolean,
        policy: String,
    ): Triple<StubContext, StatefulComponent, Pair<MmioDevice, MmioDevice>> {
        val ctx = StubContext()
        val comp = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "vglib.json",
                "lcdc0ClockGated" to lcdc0ClockGated,
                "unclockedAccessPolicy" to policy,
            ),
        ) as StatefulComponent
        comp.start()
        val lcdc0 = checkNotNull(ctx.ports.find(LcdcFunctional.port(0), MmioDevice::class.java))
        val lcdc1 = checkNotNull(ctx.ports.find(LcdcFunctional.port(1), MmioDevice::class.java))
        return Triple(ctx, comp, lcdc0 to lcdc1)
    }

    @Test
    fun abortPolicyRaisesExternalDataAbortOnUnclockedLcdc0Read044WhileRearLcdc1WorksNormally() {
        val (ctx, comp, banks) = createVglib(lcdc0ClockGated = true, policy = LcdcFunctional.POLICY_ABORT)
        val (lcdc0, lcdc1) = banks
        val powerPort = checkNotNull(ctx.ports.find(LcdcFunctional.LCDC0_POWER_PORT, LcdcPowerGatePort::class.java))

        // Rear LCD (LCDC1 at 0xFFFA9000) is always powered during Rear LCD boot
        lcdc1.write(0x000L, 4, 0L)
        assertEquals(1L, lcdc1.read(0x000L, 4) and 1L)
        lcdc1.write(0x044L, 4, 0x2000_0000L)
        assertEquals(0x2000_0000L, lcdc1.read(0x044L, 4))

        // EVF LCDC0 (0xFFF7A000) starts gated off -> reading 0xFFF7A044 triggers AXI External Data Abort!
        assertFalse(powerPort.lcdc0PowerState().powered)
        assertFalse(powerPort.lcdc0PowerState().clocked)
        val readAbort = assertFailsWith<PeripheralBusAbortException> { lcdc0.read(0x044L, 4) }
        assertEquals(0x044L, readAbort.offset)
        assertFalse(readAbort.write)
        assertEquals(0x008L, readAbort.dfsr)

        // Writing non-init register +0x044 while gated off also raises write External Data Abort (WnR=1 -> 0x808)
        val writeAbort = assertFailsWith<PeripheralBusAbortException> { lcdc0.write(0x044L, 4, 0x1234L) }
        assertEquals(0x044L, writeAbort.offset)
        assertTrue(writeAbort.write)
        assertEquals(0x808L, writeAbort.dfsr)
        assertEquals(2L, comp.state()["unclockedLcdc0AccessCount"])

        // Eye sensor / EVF power-up ungates LCDC0 -> +0x044 reads/writes succeed cleanly!
        powerPort.setLcdc0PowerClock(powered = true, clocked = true)
        assertTrue(powerPort.lcdc0PowerState().powered)
        assertTrue(powerPort.lcdc0PowerState().clocked)
        lcdc0.write(0x044L, 4, 0x3000_0000L)
        assertEquals(0x3000_0000L, lcdc0.read(0x044L, 4))
        assertEquals(2L, comp.state()["unclockedLcdc0AccessCount"])

        // Powering EVF back off re-engages the gate
        powerPort.setLcdc0PowerClock(powered = false, clocked = false)
        assertFailsWith<PeripheralBusAbortException> { lcdc0.read(0x044L, 4) }
        assertEquals(3L, comp.state()["unclockedLcdc0AccessCount"])
    }

    @Test
    fun warnAndRefusePoliciesAndFirmwareInitUngatingWorkAndRoundTripSnapshots() {
        // 1. Refuse policy blocks without mutating words, then firmware WRITE4(0) to +0x000 ungates LCDC0
        val (_, refuseComp, refuseBanks) = createVglib(lcdc0ClockGated = true, policy = LcdcFunctional.POLICY_REFUSE)
        val (refuseLcdc0, _) = refuseBanks
        val refuseErr = assertFailsWith<IllegalStateException> { refuseLcdc0.read(0x044L, 4) }
        assertFalse(refuseErr is PeripheralBusAbortException)
        assertEquals(1L, refuseComp.state()["unclockedLcdc0AccessCount"])
        assertEquals(false, refuseComp.state()["lcdc0Powered"])

        // Firmware EVF reset init (+0x000 = 0) ungates LCDC0
        refuseLcdc0.write(0x000L, 4, 0L)
        assertEquals(true, refuseComp.state()["lcdc0Powered"])
        assertEquals(true, refuseComp.state()["lcdc0Clocked"])
        assertEquals(1L, refuseLcdc0.read(0x000L, 4) and 1L)
        assertEquals(0L, refuseLcdc0.read(0x044L, 4))
        assertEquals(1L, refuseComp.state()["unclockedLcdc0AccessCount"])

        // 2. Warn policy logs and counts without throwing (relaxed mode behavior)
        val (_, warnComp, warnBanks) = createVglib(lcdc0ClockGated = true, policy = LcdcFunctional.POLICY_WARN)
        val (warnLcdc0, _) = warnBanks
        assertEquals(0L, warnLcdc0.read(0x044L, 4))
        assertEquals(1L, warnComp.state()["unclockedLcdc0AccessCount"])
        assertEquals(false, warnComp.state()["lcdc0Powered"])

        // Snapshot round-trip preserves powerGate state and unclocked access count
        val snap = warnComp.saveSnapshot()
        val (_, restoredComp, restoredBanks) = createVglib(lcdc0ClockGated = true, policy = LcdcFunctional.POLICY_WARN)
        restoredComp.restoreSnapshot(snap)
        assertEquals(1L, restoredComp.state()["unclockedLcdc0AccessCount"])
        assertEquals(false, restoredComp.state()["lcdc0Powered"])
        // +0x004 Run enable (bit 0 = 1) also ungates LCDC0
        restoredBanks.first.write(0x004L, 4, 1L)
        assertEquals(true, restoredComp.state()["lcdc0Powered"])
        assertEquals(true, restoredComp.state()["lcdc0Clocked"])
        assertEquals(1L, restoredComp.state()["unclockedLcdc0AccessCount"])
    }
}
