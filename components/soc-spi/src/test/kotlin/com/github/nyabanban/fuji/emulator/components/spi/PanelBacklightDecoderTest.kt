package com.github.nyabanban.fuji.emulator.components.spi

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PanelBacklightDecoderTest {
    private class FakeGpioOutputs : GpioOutputPort {
        private val levels = HashMap<Int, Boolean>()
        private val seqs = HashMap<Int, Long>()
        private val listeners = HashMap<Int, MutableList<(Boolean, Long) -> Unit>>()

        fun setLevel(port: Int, pin: Int, high: Boolean) {
            val key = (port shl 4) or pin
            val prev = levels[key] ?: true
            levels[key] = high
            if (prev != high) {
                val seq = (seqs[key] ?: 0L) + 1L
                seqs[key] = seq
                listeners[key]?.toList()?.forEach { it(high, seq) }
            }
        }

        override fun outputLevel(port: Int, pin: Int): Boolean = levels[(port shl 4) or pin] ?: true
        override fun outputSequence(port: Int, pin: Int): Long = seqs[(port shl 4) or pin] ?: 0L
        override fun addOutputListener(port: Int, pin: Int, listener: (Boolean, Long) -> Unit): AutoCloseable {
            val key = (port shl 4) or pin
            listeners.getOrPut(key) { ArrayList() }.add(listener)
            return AutoCloseable { listeners[key]?.remove(listener) }
        }
    }

    private fun setupSpi5(gpio: FakeGpioOutputs): Triple<StatefulComponent, ScheduledMmioDevice, PanelBacklightPort> {
        fun setupWithFirstTx(firstTx: Int): Triple<StatefulComponent, ScheduledMmioDevice, PanelBacklightPort> {
            val registered = linkedMapOf<String, Any>("soc-gpio.outputs" to gpio)
            var currentTick = 0L
            val ctx = object : ComponentContext {
                override val directory: Path = Path.of("unused-panel-backlight-test")
                override val clock = EmulationClock { currentTick }
                override val ports = object : Ports {
                    override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                        require(type.isInstance(port) && registered.putIfAbsent(name, port) == null)
                    }
                    override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name]?.let(type::cast)
                    override fun removeOwner(owner: String) { registered.clear() }
                }
                override val events = object : EventChannel {
                    override fun publish(event: ComponentEvent) = Unit
                    override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
                }
                override fun dataPath(relative: String): Path = directory.resolve(relative)
                override fun readData(relative: String): Map<String, Any?> = mapOf(
                    "profile" to "spi-additional-ports-experimental-v1",
                    "controllers" to listOf(
                        mapOf(
                            "index" to 5,
                            "peerPort" to "",
                            "panel16Replies" to mapOf(
                                "prefix" to emptyList<Any>(),
                                "fixedMisoHigh" to true,
                                "exchangeLimit" to 2048,
                            ),
                        ),
                    ),
                )
            }
            val comp = SpiFactory().create(
                ctx,
                mapOf(
                    "profile" to "spi-fifo-no-peer-experimental-v1",
                    "peerPort" to "",
                    "portsFile" to "ports.json",
                    "continuousFixedMiso" to true,
                ),
            )
            comp.start()
            val spi5 = assertIs<ScheduledMmioDevice>(registered["soc-spi.spi5"])
            val bl = assertIs<PanelBacklightPort>(registered[PanelBacklightPort.PORT_NAME])
            return Triple(assertIs<StatefulComponent>(comp), spi5, bl)
        }
        return setupWithFirstTx(0x0041)
    }

    private fun setupSpi5Evf(gpio: FakeGpioOutputs): Triple<StatefulComponent, ScheduledMmioDevice, PanelBacklightPort> {
        val registered = linkedMapOf<String, Any>("soc-gpio.outputs" to gpio)
        var currentTick = 0L
        val ctx = object : ComponentContext {
            override val directory: Path = Path.of("unused-panel-backlight-test")
            override val clock = EmulationClock { currentTick }
            override val ports = object : Ports {
                override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                    require(type.isInstance(port) && registered.putIfAbsent(name, port) == null)
                }
                override fun <T : Any> find(name: String, type: Class<T>): T? = registered[name]?.let(type::cast)
                override fun removeOwner(owner: String) { registered.clear() }
            }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) = Unit
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override fun dataPath(relative: String): Path = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = mapOf(
                "profile" to "spi-additional-ports-experimental-v1",
                "controllers" to listOf(
                    mapOf(
                        "index" to 5,
                        "peerPort" to "",
                        "panel16Replies" to mapOf(
                            "prefix" to emptyList<Any>(),
                            "fixedMisoHigh" to true,
                            "exchangeLimit" to 2048,
                        ),
                    ),
                ),
            )
        }
        val comp = SpiFactory().create(
            ctx,
            mapOf(
                "profile" to "spi-fifo-no-peer-experimental-v1",
                "peerPort" to "",
                "portsFile" to "ports.json",
                "continuousFixedMiso" to true,
            ),
        )
        comp.start()
        val spi5 = assertIs<ScheduledMmioDevice>(registered["soc-spi.spi5"])
        val bl = assertIs<PanelBacklightPort>(registered[PanelBacklightPort.PORT_NAME])
        return Triple(assertIs<StatefulComponent>(comp), spi5, bl)
    }

    private fun shiftWords(
        spi5: ScheduledMmioDevice,
        gpio: FakeGpioOutputs,
        csPin: Int,
        bits: Int,
        words: IntArray,
        tickBox: LongArray,
    ) {
        spi5.write(0, 4, (bits - 1).toLong())
        spi5.write(0x10, 4, 2L)
        spi5.write(4, 4, 2L)
        gpio.setLevel(13, csPin, false)
        for (w in words) {
            spi5.write(8, 4, w.toLong())
            tickBox[0] += bits * 2L + 4L
            spi5.beforeInstruction(tickBox[0])
            spi5.read(8, 4) // drain RX FIFO
        }
        gpio.setLevel(13, csPin, true)
    }

    @Test
    fun decodesLiveCapturedRearLcdBrightnessMinus5ZeroPlus1Plus5AndWrdisbv() {
        val gpio = FakeGpioOutputs()
        val (comp, spi5, bl) = setupSpi5(gpio)
        val t = longArrayOf(0L)

        // Default nominal brightness = 0 -> exact 1.0f
        assertEquals(1.0f, bl.backlightState().lcdGain)

        // Live captured -5 sequence: 0x00=0x05 -> 0xA3=[0x0A,0x25], 0x00=0x25 -> 0xA3=[0x00,0x5E]
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0041, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c2, 0xa4a0), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0049, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c0, 0x2bc0), t)
        val stMinus5 = bl.backlightState()
        assertEquals(0x0A, stMinus5.lcdDutyLow)
        assertEquals(0x00, stMinus5.lcdDutyBoost)
        assertTrue(stMinus5.lcdGain < 0.5f, "Expected -5 to clearly dim LCD, got ${stMinus5.lcdGain}")

        // Live captured 0 sequence: 0x00=0x05 -> 0xA3=[0x87,0x25], 0x00=0x25 -> 0xA3=[0x00,0x5E]
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0041, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51e1, 0xe4a0), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0049, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c0, 0x2bc0), t)
        val stZero = bl.backlightState()
        assertEquals(0x87, stZero.lcdDutyLow)
        assertEquals(1.0f, stZero.lcdGain)

        // Live captured +1 sequence: 0x00=0x05 -> 0xA3=[0xB0,0x25], 0x00=0x25 -> 0xA3=[0x16,0x59]
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0041, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51ec, 0x24a0), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0049, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c5, 0xab20), t)
        val stPlus1 = bl.backlightState()
        assertEquals(0xB0, stPlus1.lcdDutyLow)
        assertEquals(0x16, stPlus1.lcdDutyBoost)

        // Live captured +5 sequence: 0x00=0x25 -> 0xA3=[0x7B,0x59]
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0049, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51de, 0xeb20), t)
        val stPlus5 = bl.backlightState()
        assertEquals(0x7B, stPlus5.lcdDutyBoost)
        assertTrue(stMinus5.lcdGain < stZero.lcdGain && stZero.lcdGain < stPlus1.lcdGain && stPlus1.lcdGain < stPlus5.lcdGain)

        // MIPI DCS 0x51 WRDISBV = 0x80 dims proportionally, 0x00 turns backlight off
        // 9-bit: C:0x51 (0_01010001), D:0x80 (1_10000000) -> 0010_1000_1110_0000 0000_0000_0000_0000 = 0x28E0 0x0000
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x28E0, 0x0000), t)
        assertEquals(0x80, bl.backlightState().lcdWrdisbv)
        assertTrue(bl.backlightState().lcdGain < stPlus5.lcdGain)

        // Snapshot round-trip preserves decoded backlight state
        val snap = comp.saveSnapshot()
        val gpio2 = FakeGpioOutputs()
        val (comp2, _, bl2) = setupSpi5(gpio2)
        comp2.restoreSnapshot(snap)
        assertEquals(bl.backlightState(), bl2.backlightState())
    }

    @Test
    fun decodesLiveCapturedEvfOledLsbFirstLuminanceAndEmission() {
        val gpio = FakeGpioOutputs()
        val (_, spi5, bl) = setupSpi5Evf(gpio)
        val t = longArrayOf(0L)
        // Consume 1-word 16-bit prefix before switching SPI5 to 8-bit EVF transfers
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0000), t)

        // EVF -5: reg 0x13 (c8) = 0x05 (a0), reg 0x2C (34) = 0xD2 (4b)
        shiftWords(spi5, gpio, 10, 8, intArrayOf(0xc8, 0xa0), t)
        shiftWords(spi5, gpio, 10, 8, intArrayOf(0x34, 0x4b), t)
        val evfMinus5 = bl.backlightState()
        assertEquals(0x05, evfMinus5.evfLuminance)
        assertEquals(0xD2, evfMinus5.evfEmission)
        assertTrue(evfMinus5.evfGain < 0.85f)

        // EVF 0: reg 0x13 (c8) = 0x0E (70), reg 0x2C (34) = 0xE0 (07)
        shiftWords(spi5, gpio, 10, 8, intArrayOf(0xc8, 0x70), t)
        shiftWords(spi5, gpio, 10, 8, intArrayOf(0x34, 0x07), t)
        val evfZero = bl.backlightState()
        assertEquals(0x0E, evfZero.evfLuminance)
        assertEquals(0xE0, evfZero.evfEmission)
        assertEquals(1.0f, evfZero.evfGain)

        // EVF +5: reg 0x13 (c8) = 0x4D (b2)
        shiftWords(spi5, gpio, 10, 8, intArrayOf(0xc8, 0xb2), t)
        val evfPlus5 = bl.backlightState()
        assertEquals(0x4D, evfPlus5.evfLuminance)
        assertTrue(evfMinus5.evfGain < evfZero.evfGain && evfZero.evfGain < evfPlus5.evfGain)
    }

    @Test
    fun selfSynchronizesFullLiveCapturedLcdAndEvfStreamsWithoutGpioCsToggling() {
        val gpio = FakeGpioOutputs()
        val (_, spi5, bl) = setupSpi5(gpio)
        val t = longArrayOf(0L)
        fun sendContinuous(bits: Int, words: IntArray) {
            spi5.write(0, 4, (bits - 1).toLong())
            spi5.write(0x10, 4, 2L)
            spi5.write(4, 4, 2L)
            for (w in words) {
                spi5.write(8, 4, w.toLong())
                t[0] += bits * 2L + 4L
                spi5.beforeInstruction(t[0])
                spi5.read(8, 4)
            }
        }
        // Full 38-word Rear LCD +5 burst captured live on GXUP0004.DAT (no CS toggling!)
        sendContinuous(
            16,
            intArrayOf(
                0x0040, 0x0000, 0x7fd4, 0x62f0, 0x1800, 0x0060, 0x0000, 0x7fd4, 0x62e0,
                0x0040, 0x0000, 0x51ec, 0x2200, 0x0041, 0x4000, 0x51ec, 0x24a0, 0x0044, 0x0000,
                0x51c5, 0x6010, 0x0800, 0x0048, 0x0000, 0x51ff, 0xffe0, 0x0048, 0xc000, 0x51d8, 0x0000,
                0x0049, 0x4000, 0x51de, 0xeb20, 0x0040, 0x0000, 0x7dc0, 0x4000,
            ),
        )
        assertEquals(0xB0, bl.backlightState().lcdDutyLow)
        assertEquals(0xFF, bl.backlightState().lcdBoostMode)
        assertEquals(0x7B, bl.backlightState().lcdDutyBoost)
        assertTrue(bl.backlightState().lcdGain > 1.35f)

        // Full 18-byte EVF -5 burst captured live on GXUP0004.DAT (no CS toggling!)
        sendContinuous(
            8,
            intArrayOf(
                0x60, 0xa0, 0x08, 0x88, 0xa0, 0xc8, 0xa0, 0xd4, 0x00, 0x34, 0x4b,
                0xb4, 0x01, 0x01, 0x01, 0xc2, 0xaa, 0x80,
            ),
        )
        assertEquals(0x05, bl.backlightState().evfLuminance)
        assertEquals(0xD2, bl.backlightState().evfEmission)
        assertEquals(0x80, bl.backlightState().evfGainR)
        assertTrue(bl.backlightState().evfGain < 0.80f)
    }

    @Test
    fun ignoresPreDisponPowerOnInitDuty0x04SoOobeAndFwupKeepNominalBacklight() {
        val gpio = FakeGpioOutputs()
        val (_, spi5, bl) = setupSpi5(gpio)
        val t = longArrayOf(0L)

        // Exact live-captured Rear LCD power-on init commands emitted during OOBE (0x0421) and FWUP (0x0601):
        // WRDISBV=0xFF, WRCTRLD=0x24, 0x00=0x05 -> 0xA3=[0x04,0x25], 0x00=0x25 -> 0xA3=[0x00,0x5E], SLPOUT, DISPON.
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x28ff, 0xc000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x29c9, 0x0000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0041, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c1, 0x24a0), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0049, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c0, 0x2bc0), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0880), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x1480), t)

        val stInit = bl.backlightState()
        assertEquals(PanelBacklightDecoder.DEFAULT_LCD_DUTY_LOW, stInit.lcdDutyLow)
        assertEquals(1.0f, stInit.lcdGain)
        assertTrue(stInit.lcdUpdates > 0L)

        // Subsequent real user LCD BRIGHTNESS -5 (0x0A) still dims normally.
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0041, 0x4000), t)
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x51c2, 0xa4a0), t)
        assertEquals(0x0A, bl.backlightState().lcdDutyLow)
        assertTrue(bl.backlightState().lcdGain < 0.5f)
    }

    @Test
    fun tickGapDuringCsLowEvfBankDumpNeverSplitsBurstOrZeroesEvfGain() {
        val gpio = FakeGpioOutputs()
        val (_, spi5, bl) = setupSpi5Evf(gpio)
        val t = longArrayOf(0L)
        // Consume 1-word 16-bit prefix before switching SPI5 to 8-bit EVF transfers
        shiftWords(spi5, gpio, 8, 16, intArrayOf(0x0000), t)

        // Build a real-silicon 131-byte Sony ECX33x register bank dump starting at reg 0x00
        // with reg 0x13 = 0x46 (bitrev=0x62) and reg 0x2C = 0xE0 (bitrev=0x07), and embedded 0x00 runs.
        val dump = IntArray(131) { 0x00 }
        dump[0] = 0x00 // startReg = 0x00
        dump[1 + 0x00] = 0xC9 // reg 0x00 = 0x93
        dump[1 + 0x13] = 0x62 // reg 0x13 = 0x46 (70)
        dump[1 + 0x2C] = 0x07 // reg 0x2C = 0xE0 (224)
        dump[1 + 0x2D] = 0x01 // reg 0x2D = 0x80
        dump[1 + 0x2E] = 0x01 // reg 0x2E = 0x80
        dump[1 + 0x2F] = 0x01 // reg 0x2F = 0x80

        spi5.write(0, 4, 7L)
        spi5.write(0x10, 4, 2L)
        spi5.write(4, 4, 2L)
        gpio.setLevel(13, 10, false) // CS LOW
        for (i in dump.indices) {
            if (i == 10 || i == 21 || i == 60) {
                // Simulate > 40,000-tick IRQ preemption right before a 0x00 byte while CS stays LOW!
                t[0] += 80_000L
            }
            spi5.write(8, 4, dump[i].toLong())
            t[0] += 20L
            spi5.beforeInstruction(t[0])
            spi5.read(8, 4)
        }
        gpio.setLevel(13, 10, true) // CS HIGH commits full 131-byte transaction

        val st = bl.backlightState()
        assertEquals(0x46, st.evfLuminance, "Mid-burst IRQ preemption must not corrupt EVF luminance to 0")
        assertEquals(0xE0, st.evfEmission, "Mid-burst IRQ preemption must not corrupt EVF emission to 0")
        assertTrue(st.evfGain > 1.5f, "EVF gain must stay illuminated (>1.5x), got ${st.evfGain}")
    }
}
