package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.*

class Wm8962SpiPeerTest {
    private class TestContext(private val portsData: Map<String, Any?>) : ComponentContext {
        var currentTick = 0L
        val registeredPorts = linkedMapOf<String, Any>()
        override val directory: Path = Path.of("unused-wm8962-test")
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(type.isInstance(port) && registeredPorts.putIfAbsent(name, port) == null) {
                    "Duplicate or mistyped port $name"
                }
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registeredPorts[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                registeredPorts.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) = Unit
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
        override fun readData(relative: String): Map<String, Any?> = portsData
    }

    private fun createSpi4WithWm8962(): Triple<TestContext, StatefulComponent, ScheduledMmioDevice> {
        val portsDoc = mapOf(
            "profile" to "spi-additional-ports-experimental-v1",
            "controllers" to listOf(
                mapOf("index" to 4, "peerPort" to Wm8962SpiPeer.PEER_PORT),
            ),
        )
        val ctx = TestContext(portsDoc)
        val factory = SpiFactory()
        val comp = factory.create(
            ctx,
            mapOf(
                "profile" to "spi-fifo-no-peer-experimental-v1",
                "peerPort" to "",
                "portsFile" to "ports.json",
                "continuousFixedMiso" to false,
            ),
        )
        comp.start()
        val spi4 = assertIs<ScheduledMmioDevice>(ctx.ports.find("soc-spi.spi4", MmioDevice::class.java))
        // Configure SPI4 identically to GFX100S 2.13 DAT (16-bit word = control 0x000F, divider 28, master enable 0x000A)
        spi4.write(0x00L, 4, 0x000FL)
        spi4.write(0x10L, 4, 28L)
        spi4.write(0x04L, 4, 0x000AL)
        return Triple(ctx, assertIs<StatefulComponent>(comp), spi4)
    }

    private fun exchangeWord(ctx: TestContext, spi4: ScheduledMmioDevice, word: Int): Int {
        spi4.write(0x08L, 4, (word and 0xFFFF).toLong())
        val deadline = checkNotNull(spi4.nextDeadline())
        ctx.currentTick = deadline
        spi4.beforeInstruction(deadline)
        return spi4.read(0x08L, 4).toInt()
    }

    private fun writeCodecReg(ctx: TestContext, spi4: ScheduledMmioDevice, reg: Int, value: Int) {
        assertEquals(0, exchangeWord(ctx, spi4, reg and 0x7FFF))
        assertEquals(0, exchangeWord(ctx, spi4, value and 0xFFFF))
    }

    private fun readCodecReg(ctx: TestContext, spi4: ScheduledMmioDevice, reg: Int): Int {
        assertEquals(0, exchangeWord(ctx, spi4, 0x8000 or (reg and 0x7FFF)))
        return exchangeWord(ctx, spi4, 0x0000)
    }

    @Test
    fun spi4Wm8962HandlesHeaderInitFullDsp2DownloadBank14CalibrationAndReadback() {
        val (ctx, comp, spi4) = createSpi4WithWm8962()
        val codec = assertIs<AudioCodecControlPort>(ctx.ports.find(Wm8962SpiPeer.CODEC_PORT, AudioCodecControlPort::class.java))

        // Chip ID readback before reset
        assertEquals(Wm8962SpiPeer.DEVICE_ID, readCodecReg(ctx, spi4, Wm8962SpiPeer.REG_SOFTWARE_RESET))

        // Stage 1: Exact 12-entry GFX100S 2.13 header table (section4 @ 0x016DC8A0)
        val stage1Header = listOf(
            0x000F to 0x6243,
            0x007F to 0x0000,
            0x0008 to 0x01C4,
            0x001C to 0x001C,
            0x0019 to 0x01C0,
            0x0008 to 0x09C4,
            0x0081 to 0x0000,
            0x001B to 0x0010,
            0x0038 to 0x050A,
            0x0008 to 0x09E4,
            0x00FD to 0x0002, // delay sentinel accepted cleanly
            0x0300 to 0x0009,
        )
        for ((reg, value) in stage1Header) {
            writeCodecReg(ctx, spi4, reg, value)
        }
        assertEquals(1L, codec.resetCount())
        assertEquals(0x09E4, readCodecReg(ctx, spi4, Wm8962SpiPeer.REG_CLOCKING_2))
        assertEquals(0x0009, readCodecReg(ctx, spi4, Wm8962SpiPeer.REG_DSP2_PWR_MGMT))
        assertTrue(codec.outputRoute().sysclkEnabled)
        assertTrue(codec.outputRoute().dsp2Enabled)

        // Runtime DSP2 enable sequence (section4 @ 0x01292714 writes R768 = 0x0007) keeps dsp2Enabled true
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_DSP2_PWR_MGMT, 0x0007)
        assertTrue(codec.outputRoute().dsp2Enabled)

        // Stage 2: Full 4,133-entry DSP2 microcode + coefficient download (8,266 SPI4 words)
        writeCodecReg(ctx, spi4, 0x0408, 0x0125)
        writeCodecReg(ctx, spi4, 0x0409, 0x0053)
        writeCodecReg(ctx, spi4, 0x040A, 0x006C)
        for (addr in 0x2000..0x29FF) { // 2,560 Program RAM words
            writeCodecReg(ctx, spi4, addr, if (addr == 0x21AC) 0x000F else ((addr and 0xFF) + 1))
        }
        for (i in 0 until 1570) { // 1,570 Data/Coefficient RAM words
            writeCodecReg(ctx, spi4, 0x3000 + i, (i and 0x7FFF) + 1)
        }
        assertEquals(2560, codec.dsp2ProgramWriteCount())
        assertEquals(1570, codec.dsp2DataWriteCount())
        assertEquals(0x000F, codec.readRegister(0x21AC))

        // Stage 3: Bank 14 calibration writes (0x4200..0x462A)
        writeCodecReg(ctx, spi4, 0x4200, 0x1234)
        writeCodecReg(ctx, spi4, 0x462A, 0x5678)
        assertEquals(2, codec.dsp2CalibrationWriteCount())
        assertEquals(0x1234, readCodecReg(ctx, spi4, 0x4200))
        assertEquals(0x5678, readCodecReg(ctx, spi4, 0x462A))

        // Software reset via R15 clears volatile & DSP2 RAM while keeping chip ID 0x6243
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_SOFTWARE_RESET, 0x6243)
        assertEquals(2L, codec.resetCount())
        assertEquals(0, codec.dsp2ProgramWriteCount())
        assertEquals(0, codec.dsp2DataWriteCount())
        assertEquals(0, codec.readRegister(Wm8962SpiPeer.REG_CLOCKING_2))
        assertEquals(Wm8962SpiPeer.DEVICE_ID, readCodecReg(ctx, spi4, Wm8962SpiPeer.REG_SOFTWARE_RESET))

        (comp as HardwareComponent).close()
    }

    @Test
    fun wm8962RoutesSpeakerHeadphoneAndDacMuteAndRoundTripsSnapshotsAtomically() {
        val (ctx, comp, spi4) = createSpi4WithWm8962()
        val codec = assertIs<AudioCodecControlPort>(ctx.ports.find(Wm8962SpiPeer.CODEC_PORT, AudioCodecControlPort::class.java))

        // Initially muted
        assertEquals(AudioSinkKind.MUTED, codec.outputRoute().activeSink)
        assertEquals(0.0f, codec.outputRoute().effectiveLeftGain)

        // Enable mono Class-D speaker (SPKOUTL: R26 bit4 + R49 bit6 + R40 = 0x79 0dB)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_PWR_MGMT_2, 0x0010)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_CLASS_D_CTRL_1, 0x0040)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_SPKOUTL_VOL, 0x0179) // VU + 0 dB
        val spkRoute = codec.outputRoute()
        assertEquals(AudioSinkKind.SPEAKER_MONO, spkRoute.activeSink)
        assertTrue(spkRoute.speakerEnabled)
        assertFalse(spkRoute.headphoneEnabled)
        assertEquals(1.0f, spkRoute.effectiveLeftGain, 0.001f)
        assertEquals(1.0f, spkRoute.effectiveRightGain, 0.001f)

        // Switch to stereo headphone (mute speaker, enable HPOUTL/R + Charge Pump R72=0x0048 + asymmetric L/R vol)
        // Also verify R82 (0x0052) = 0x0004 (written by real firmware) does not interfere with R72 CP_ENA
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_PWR_MGMT_2, 0x01E0)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_CLASS_D_CTRL_1, 0x0010)
        writeCodecReg(ctx, spi4, 0x0052, 0x0004)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_CHARGE_PUMP_1, 0x0001)
        assertEquals(0x0048, Wm8962SpiPeer.REG_CHARGE_PUMP_1)
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_HPOUTL_VOL, 0x0179) // 0 dB -> 1.0
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_HPOUTR_VOL, 0x0173) // -6 dB -> ~0.501
        val hpRoute = codec.outputRoute()
        assertEquals(AudioSinkKind.HEADPHONE_STEREO, hpRoute.activeSink)
        assertTrue(hpRoute.headphoneEnabled)
        assertEquals(1.0f, hpRoute.effectiveLeftGain, 0.001f)
        assertEquals(0.501f, hpRoute.effectiveRightGain, 0.01f)

        // DAC soft mute (R5 bit3) forces MUTED regardless of analog enables
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_ADC_DAC_CTRL_1, 0x0008)
        assertEquals(AudioSinkKind.MUTED, codec.outputRoute().activeSink)
        assertEquals(0.0f, codec.outputRoute().effectiveLeftGain)

        // Unmute and save snapshot
        writeCodecReg(ctx, spi4, Wm8962SpiPeer.REG_ADC_DAC_CTRL_1, 0x0000)
        writeCodecReg(ctx, spi4, 0x2010, 0xBEEF)
        val saved = comp.saveSnapshot()
        assertTrue("wm8962Peer" in saved)

        // Restore into fresh instance and verify full state parity
        val (ctx2, comp2, _) = createSpi4WithWm8962()
        comp2.restoreSnapshot(saved)
        val codec2 = assertIs<AudioCodecControlPort>(ctx2.ports.find(Wm8962SpiPeer.CODEC_PORT, AudioCodecControlPort::class.java))
        assertEquals(AudioSinkKind.HEADPHONE_STEREO, codec2.outputRoute().activeSink)
        assertEquals(0xBEEF, codec2.readRegister(0x2010))
        assertEquals(1.0f, codec2.outputRoute().effectiveLeftGain, 0.001f)
        assertEquals(0.501f, codec2.outputRoute().effectiveRightGain, 0.01f)

        // Corrupted wm8962Peer snapshot is rejected atomically without mutating live registers
        val badSnap = saved.toMutableMap().apply {
            val badPeer = (saved["wm8962Peer"] as Map<*, *>).toMutableMap()
            badPeer["latchedAddress"] = 0x10000 // out of 15-bit range
            put("wm8962Peer", badPeer)
        }
        assertFailsWith<IllegalArgumentException> { comp2.restoreSnapshot(badSnap) }
        assertEquals(0xBEEF, codec2.readRegister(0x2010))

        (comp as HardwareComponent).close()
        (comp2 as HardwareComponent).close()
    }

    @Test
    fun wm8962RejectsNon16BitTransfers() {
        val peer = Wm8962SpiPeer()
        assertFailsWith<IllegalArgumentException> { peer.exchange(8, 0x55, 100L) }
    }
}
