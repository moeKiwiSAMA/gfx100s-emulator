package com.github.nyabanban.fuji.emulator.components.systemcontrol

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class UsbDwc3ControllerTest {
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
            override fun publish(event: ComponentEvent) { error("No system-control event expected") }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = data
    }

    private fun createDwc3(vbusPresent: Boolean = false): Pair<TestContext, StatefulComponent> {
        val statusData = mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 1L)
        val ctx = TestContext(statusData)
        val factory = SystemControlFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "profile" to UsbDwc3Controller.PROFILE,
            "statusA200InputFile" to "status-a200.json",
            "usbVbusPresent" to vbusPresent,
        )
        val comp = factory.create(ctx, cfg) as StatefulComponent
        comp.start()
        return ctx to comp
    }

    @Test
    fun dwc3ResetCommandCompletionEndpointsAndUsb3PhyIndirectAccessWorkWithoutHanging() {
        val (ctx, comp) = createDwc3(vbusPresent = true)
        assertEquals(
            listOf(
                UsbDwc3Controller.STATUS_A200_V3_PORT,
                UsbDwc3Controller.USB_VBUS_V2_PORT,
                UsbDwc3Controller.DWC3_PORT,
                UsbDwc3Controller.PHY_PORT,
            ),
            comp.additionalMmioPorts(),
        )
        val dwc3 = assertNotNull(ctx.ports.find(UsbDwc3Controller.DWC3_PORT, MmioDevice::class.java))
        val phy = assertNotNull(ctx.ports.find(UsbDwc3Controller.PHY_PORT, MmioDevice::class.java))
        val vbus = assertNotNull(ctx.ports.find(UsbDwc3Controller.USB_VBUS_V2_PORT, MmioDevice::class.java))

        assertEquals(1L, vbus.read(0, 4))
        assertEquals(UsbDwc3Controller.GSNPSID_VALUE, dwc3.read(UsbDwc3Controller.OFF_GSNPSID, 4))
        assertFails { dwc3.write(UsbDwc3Controller.OFF_GSNPSID, 4, 0L) }
        assertFails { dwc3.write(UsbDwc3Controller.OFF_DSTS, 4, 0L) }

        // Initial DSTS has DEVCTRLHLT=1 (halted) because DCTL.RUN_STOP=0
        assertEquals(UsbDwc3Controller.DSTS_DEVCTRLHLT, dwc3.read(UsbDwc3Controller.OFF_DSTS, 4))

        // Firmware soft reset sequence (0x021A9C8C / 0x021AA3EC): set DCTL.CSFTRST and poll until bit 30 clears
        dwc3.write(UsbDwc3Controller.OFF_DCFG, 4, 0x00420004L)
        assertEquals(0x00420004L, dwc3.read(UsbDwc3Controller.OFF_DCFG, 4))
        dwc3.write(UsbDwc3Controller.OFF_DCTL, 4, UsbDwc3Controller.DCTL_CSFTRST or UsbDwc3Controller.DCTL_RUN_STOP)
        assertEquals(0L, dwc3.read(UsbDwc3Controller.OFF_DCTL, 4) and UsbDwc3Controller.DCTL_CSFTRST)
        assertEquals(0L, dwc3.read(UsbDwc3Controller.OFF_DCFG, 4)) // device registers reset cleanly

        // Set RUN_STOP -> DSTS.DEVCTRLHLT clears, USBLNKST=0 (!= 4), CONNECTSPD=0 (HS)
        dwc3.write(UsbDwc3Controller.OFF_DCTL, 4, UsbDwc3Controller.DCTL_RUN_STOP)
        assertEquals(0L, dwc3.read(UsbDwc3Controller.OFF_DSTS, 4))

        // Global command DGCMD self-clears CMDACT (bit 10)
        dwc3.write(UsbDwc3Controller.OFF_DGCMDPAR, 4, 0x12345678L)
        dwc3.write(UsbDwc3Controller.OFF_DGCMD, 4, UsbDwc3Controller.CMDACT_BIT or 0x7L)
        assertEquals(0x7L, dwc3.read(UsbDwc3Controller.OFF_DGCMD, 4))

        // Endpoint command DEPCMD[ep] self-clears CMDACT (bit 10) and assigns XferRscIdx for DEPSTRTXFER (cmd 6)
        val ep3Base = UsbDwc3Controller.OFF_DEPCMD_BASE + 3 * 0x10L
        dwc3.write(ep3Base + 0x0L, 4, 0x11111111L)
        dwc3.write(ep3Base + 0x4L, 4, 0x22222222L)
        dwc3.write(ep3Base + 0x8L, 4, 0x33333333L)
        dwc3.write(ep3Base + 0xCL, 4, 0x0506L) // CMDACT (0x400) | DEPSTRTXFER (6) | bit 8
        val depCmdReadback = dwc3.read(ep3Base + 0xCL, 4)
        assertEquals(0L, depCmdReadback and UsbDwc3Controller.CMDACT_BIT)
        assertEquals(4L, (depCmdReadback ushr 16) and 0x7FL) // ep3 -> XferRscIdx = 4

        // Non-start command (0xD08 = DEPENDXFER with rscIdx=4 at 0x021A91DC) preserves rscIdx and clears CMDACT
        dwc3.write(ep3Base + 0xCL, 4, 0x00040D08L)
        assertEquals(0x00040908L, dwc3.read(ep3Base + 0xCL, 4))

        // USB3 PHY indirect write/readback sequence matching 0x021A90B0..0x021A9148
        val regIndex = 5L
        val writeVal = 0xA7L
        val baseWord = (regIndex shl 8) or writeVal
        phy.write(UsbDwc3Controller.PHY_OFF_CTRL, 4, baseWord)
        phy.write(UsbDwc3Controller.PHY_OFF_CTRL, 4, baseWord or UsbDwc3Controller.PHY_WRITE_STROBE_BIT)
        phy.write(UsbDwc3Controller.PHY_OFF_CTRL, 4, baseWord)
        assertEquals(writeVal, phy.read(UsbDwc3Controller.PHY_OFF_READBACK, 4))
        phy.write(UsbDwc3Controller.PHY_OFF_MISC, 4, 0xCAFEBABEL)
        assertEquals(0xCAFEBABEL, phy.read(UsbDwc3Controller.PHY_OFF_MISC, 4))

        // Unmodeled holes and non-32-bit widths refuse
        assertFails { dwc3.read(0x000L, 4) }
        assertFails { dwc3.read(UsbDwc3Controller.OFF_DCTL, 2) }
        assertFails { phy.read(0x008L, 4) }
        assertFails { phy.write(0x000L, 1, 0L) }
    }

    @Test
    fun dwc3SnapshotRoundTripAtomicRejectionAndV1Promotion() {
        val (ctx1, comp1) = createDwc3(vbusPresent = true)
        val dwc3 = assertNotNull(ctx1.ports.find(UsbDwc3Controller.DWC3_PORT, MmioDevice::class.java))
        val phy = assertNotNull(ctx1.ports.find(UsbDwc3Controller.PHY_PORT, MmioDevice::class.java))
        dwc3.write(UsbDwc3Controller.OFF_GCTL, 4, 0x30D81000L)
        dwc3.write(UsbDwc3Controller.OFF_DCTL, 4, UsbDwc3Controller.DCTL_RUN_STOP)
        dwc3.write(UsbDwc3Controller.OFF_DEPCMD_BASE + 0xCL, 4, 0x0406L)
        phy.write(UsbDwc3Controller.PHY_OFF_CTRL, 4, (0x3L shl 8) or 0x55L)
        phy.write(UsbDwc3Controller.PHY_OFF_CTRL, 4, (0x3L shl 8) or 0x55L or UsbDwc3Controller.PHY_WRITE_STROBE_BIT)

        val snap = comp1.saveSnapshot()
        val (_, comp2) = createDwc3(vbusPresent = false)
        comp2.restoreSnapshot(snap)
        assertEquals(comp1.state(), comp2.state())

        // Atomic rejection if tampered with pending CSFTRST
        val before = comp2.state()
        @Suppress("UNCHECKED_CAST")
        val badDwc3 = (snap["usbDwc3State"] as Map<String, Any>) + ("dctl" to UsbDwc3Controller.DCTL_CSFTRST)
        assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(snap + ("usbDwc3State" to badDwc3)) }
        assertEquals(before, comp2.state())

        // Promotion from v1 (usb-vbus-a300) snapshot into v2 (usb-dwc3) resets DWC3 cleanly
        val statusData = mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 1L)
        val v1Factory = SystemControlFactory()
        val v1Cfg = v1Factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "profile" to UsbVbusA300Input.PROFILE,
            "statusA200InputFile" to "status-a200.json",
            "usbVbusPresent" to true,
        )
        val v1Comp = v1Factory.create(TestContext(statusData), v1Cfg) as StatefulComponent
        val v1Snap = v1Comp.saveSnapshot()
        comp2.restoreSnapshot(v1Snap)
        assertEquals(true, comp2.state()["usbVbusPresent"])
        @Suppress("UNCHECKED_CAST")
        val dwc3State = comp2.state()["usbDwc3State"] as Map<String, Any?>
        assertEquals(0L, dwc3State["gctl"])
        assertEquals(UsbDwc3Controller.DSTS_DEVCTRLHLT, dwc3State["dsts"])
    }

    @Test
    fun dwc3ProfileAllowsRepeatable49530And49500CyclesAcrossModeSwitches() {
        val statusData = mapOf("schema" to 1, "profile" to StatusA200Input.PROFILE, "value" to 1L)
        val ctx = TestContext(statusData)
        val factory = SystemControlFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "profile" to UsbDwc3Controller.PROFILE,
            "statusA200InputFile" to "status-a200.json",
            "write49530" to true,
            "tail49500" to true,
        )
        val comp = factory.create(ctx, cfg) as StatefulComponent
        val pair = assertNotNull(ctx.ports.find(SetupSequence49530And49500.WRITE49530_PORT, MmioDevice::class.java))
        val tail = assertNotNull(ctx.ports.find(SetupSequence49530And49500.TAIL49500_PORT, MmioDevice::class.java))

        // Execute 3 full STILL <-> MOVIE cycles (boot + STILL->MOVIE + MOVIE->STILL)
        repeat(3) {
            pair.write(0, 4, 0x10000704L)
            pair.write(4, 4, 0x70L)
            pair.write(4, 4, 0x71L)
            tail.write(0, 4, 3L)
        }
        val snap = comp.saveSnapshot()
        val restored = factory.create(TestContext(statusData), cfg) as StatefulComponent
        restored.restoreSnapshot(snap)
        assertEquals(comp.state(), restored.state())
    }
}
