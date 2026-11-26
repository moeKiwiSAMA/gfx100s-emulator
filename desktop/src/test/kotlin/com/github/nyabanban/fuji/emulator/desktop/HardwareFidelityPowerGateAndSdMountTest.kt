package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * End-to-end ComponentHost integration tests replicating real GFX100S SPX4 hardware traps:
 * 1. EVF LCDC0 (0xFFF7A000..0xFFF7AFFF) power/clock gate (AXI External Data Abort on 0xFFF7A044 while EVF off).
 * 2. Retail UART0 (0xFFF3A000) clock gate (tracking/aborting unclocked accesses until UARTCR.UARTEN=1).
 * 3. Realistic SD hot-insert GPIO 10.4/10.8 (CARD_DET / CARD2_DET) -> async mount delay window where
 *    early B:\ commands right on CARD2_DET == 0 fail until cardInsertReadyDelayTicks elapses.
 */
class HardwareFidelityPowerGateAndSdMountTest {
    private fun cidHex(seed: Int): String {
        val bytes = ByteArray(15) { ((it * 17 + seed) and 0xff).toByte() }
        var crc = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val feedback = ((b.toInt() ushr i) and 1) xor ((crc ushr 6) and 1)
            crc = (crc shl 1) and 127
            if (feedback != 0) crc = crc xor 9
        }
        return (bytes + ((crc shl 1) or 1).toByte()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test
    fun endToEndSdHotPlugGpioCard2DetToAsyncMountWindowAndPowerGatedLcdc0AndUart0(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val fixtures = IntegrationTest()
        val home = fixtures.fixture()
        try {
            // Prepare 2 MiB Slot 1 (A:\) & Slot 2 (B:\) raw images with distinct patterns
            val sdDir = home.resolve("compoments/sdcard-reader")
            Files.createDirectories(sdDir)
            val slot1Bytes = ByteArray(2 * 1024 * 1024) { ((it xor 0x11) and 0xff).toByte() }
            val slot2Bytes = ByteArray(2 * 1024 * 1024) { ((it xor 0xB2) and 0xff).toByte() }
            Files.write(sdDir.resolve("slot1.img"), slot1Bytes)
            Files.write(sdDir.resolve("slot2.img"), slot2Bytes)

            Formats.atomicYaml(
                sdDir.resolve("sdcard-reader.yaml"),
                mapOf(
                    "slot1Image" to "slot1.img",
                    "slot1Inserted" to true,
                    "slot1ReadOnly" to false,
                    "slot2Image" to "slot2.img",
                    "slot2Inserted" to true,
                    "slot2ReadOnly" to false,
                    "cardInsertReadyDelayTicks" to 100_000L,
                    "syncCardDetectGpio" to true,
                ),
            )

            // Configure soc-gpio with Bank 10 initially HIGH (cards undetected before start() sync)
            val gpioCfg = linkedMapOf<String, Any>(
                "profile" to "disabled",
                "directionButtonProfile" to "disabled",
                "shutterIrqProfile" to "disabled",
            )
            for (b in 0..15) gpioCfg["input$b"] = if (b == 10) 0xffffL else 0L
            Formats.atomicYaml(home.resolve("compoments/soc-gpio/soc-gpio.yaml"), gpioCfg)

            // Configure soc-sd-host v7 dual-slot
            val sdHostDir = home.resolve("compoments/soc-sd-host")
            Files.createDirectories(sdHostDir)
            val fieldsControl = listOf("word80", "control623e", "mixed6078", "control403e", "word60ec")
            val fieldsInit = listOf("capability6244", "present4024", "present6224", "signal6238", "block4006", "host6228", "host4028")
            val sdInput = mapOf(
                "schema" to 1,
                "profile" to "gfx100s-sd-host-address-extension-experimental-v7",
                "commandTicks" to 2,
                "blockTicks" to 3,
                "resetTicks" to 2,
                "addressHighBits" to mapOf("0" to 0, "1" to 0),
                "legacy" to mapOf(
                    "schema" to 1,
                    "profile" to "gfx100s-sd-host-phy-word-retention-experimental-v5",
                    "initialRegisters" to (0..1).associate { it.toString() to fieldsControl.associateWith { 0L } },
                    "clocks" to listOf("0:0", "0:1", "1:0", "1:1").associateWith {
                        mapOf("initialWord" to 5, "bit1Samples" to listOf(0, 1))
                    },
                    "initWords" to (0..1).associate {
                        it.toString() to fieldsInit.associateWith { name -> if (name == "host4028") 0x0f00L else 0L }
                    },
                ),
                "cards" to mapOf(
                    "0" to mapOf(
                        "basis" to "SYNTHETIC",
                        "capacityBytes" to slot1Bytes.size.toLong(),
                        "highCapacity" to false,
                        "readOnly" to false,
                        "rca" to 0x1111,
                        "cidHex" to cidHex(1),
                        "ocrVoltageWindow" to 0xff8000L,
                        "supportsHighSpeed" to true,
                    ),
                    "1" to mapOf(
                        "basis" to "SYNTHETIC",
                        "capacityBytes" to slot2Bytes.size.toLong(),
                        "highCapacity" to false,
                        "readOnly" to false,
                        "rca" to 0x2222,
                        "cidHex" to cidHex(2),
                        "ocrVoltageWindow" to 0xff8000L,
                        "supportsHighSpeed" to true,
                    ),
                ),
            )
            Files.writeString(sdHostDir.resolve("sd-dual-hotplug.json"), Formats.jsonText(sdInput))
            Formats.atomicYaml(
                sdHostDir.resolve("soc-sd-host.yaml"),
                mapOf(
                    "profile" to "gfx100s-sd-host-address-extension-experimental-v7",
                    "inputFile" to "sd-dual-hotplug.json",
                ),
            )

            // Configure soc-vglib-init with EVF LCDC0 power-gated ("abort")
            val vglibDir = home.resolve("compoments/soc-vglib-init")
            Files.createDirectories(vglibDir)
            Files.writeString(
                vglibDir.resolve("vglib-input.json"),
                Formats.jsonText(
                    mapOf(
                        "schema" to 1,
                        "profile" to "vglib-full-lane-init-experimental-v1",
                        "readConfidence" to "HYPOTHESIS",
                        "transactions" to (0 until 18).map { i ->
                            mapOf("offset" to (i * 4L), "readValue" to 0L, "writeValue" to 0L)
                        },
                    ),
                ),
            )
            Formats.atomicYaml(
                vglibDir.resolve("soc-vglib-init.yaml"),
                mapOf(
                    "profile" to "lcdc-functional-experimental-v1",
                    "inputFile" to "vglib-input.json",
                    "lcdc0ClockGated" to true,
                    "unclockedAccessPolicy" to "abort",
                ),
            )

            // Configure retail soc-uart with initiallyClocked=false, policy="abort"
            Formats.atomicYaml(
                home.resolve("compoments/soc-uart/soc-uart.yaml"),
                mapOf(
                    "profile" to "pl011-tx-ready-experimental-v1",
                    "initialFill" to 0L,
                    "initiallyClocked" to false,
                    "unclockedAccessPolicy" to "abort",
                ),
            )

            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-gic", "soc-gpio", "soc-sd-host", "soc-vglib-init", "soc-uart"),
            )

            EmulatorRuntime(home, config).use { runtime ->
                runtime.owner.submit {
                    // Provide DMA RAM for SD transfer verification
                    val dmaRam = ByteArray(65536)
                    runtime.host.ports.register("test", "cpu.memory", DmaMemory::class.java, object : DmaMemory {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(address >= 0 && size >= 0 && address + size <= dmaRam.size)
                        }
                        override fun read(address: Long, destination: ByteBuffer) {
                            destination.put(dmaRam, address.toInt(), destination.remaining())
                        }
                        override fun write(address: Long, source: ByteBuffer) {
                            source.get(dmaRam, address.toInt(), source.remaining())
                        }
                    })

                    // --- Part 1: SD Hot-Insert GPIO 10.4/10.8 -> Async Mount Delay Window ---
                    val gpio = checkNotNull(runtime.host.ports.find("soc-gpio", MmioDevice::class.java))
                    val hotPlug = checkNotNull(runtime.host.ports.find("sdcard-reader.hotplug", HotPlugCardControlPort::class.java))
                    val sdBank1 = checkNotNull(runtime.host.ports.find("soc-sd-host.transaction-address-1", MmioDevice::class.java))

                    // Bank 10 input register is at offset 10 * 64 + 5 * 4 = 660
                    val bank10InOffset = 10L * 64L + 5L * 4L

                    // Hot-eject Slot 2 (B:\) first -> GPIO 10.8 reads HIGH (1)
                    hotPlug.ejectCard(2)
                    assertTrue((gpio.read(bank10InOffset, 4) and (1L shl 8)) != 0L, "Ejected Slot 2 must read CARD2_DET=1")
                    assertEquals(0L, sdBank1.read(0x24L, 4) and (1L shl 16), "Ejected SDHCI present4024 bit16 must be 0")

                    // Hot-insert Slot 2 (B:\) at t = 10_000 with 100_000-tick mount delay
                    runtime.host.advanceTick(10_000L)
                    hotPlug.insertCard(2, readyDelayTicks = 100_000L)

                    // Immediately at t = 10_000: CARD2_DET (GPIO 10.8) == 0 (LOW), present4024 bit16 == 1,
                    // bit17 (CARD_STATE_STABLE) == 0 (still mounting!)
                    assertEquals(0L, gpio.read(bank10InOffset, 4) and (1L shl 8), "Hot-inserted Slot 2 must immediately assert CARD2_DET=0")
                    val mountingPresent = sdBank1.read(0x24L, 4)
                    assertTrue((mountingPresent and (1L shl 16)) != 0L, "present4024 CARD_INSERTED must be 1")
                    assertEquals(0L, mountingPresent and (1L shl 17), "present4024 CARD_STATE_STABLE must be 0 during mount delay")

                    // Power up SDHCI Slot 1 clock & bus, then attempt early B:\ CMD0 right on CARD2_DET == 0 -> MUST FAIL!
                    sdBank1.write(0x28L, 2, 0x0f00L)
                    sdBank1.write(0x2cL, 2, 0x0007L)
                    runtime.host.advanceTick(10_002L)
                    sdBank1.beforeInstruction(10_002L)
                    assertFailsWith<IllegalArgumentException> {
                        sdBank1.write(0x0cL, 4, 0L) // CMD0 while t=10_002 < readyAt=110_000
                    }

                    // Advance past mount delay (t = 110_000) -> CARD_STATE_STABLE == 1, full B:\ init + CMD17 DMA read succeeds!
                    var tick = 110_000L
                    runtime.host.advanceTick(tick)
                    assertTrue((sdBank1.read(0x24L, 4) and (1L shl 17)) != 0L, "present4024 CARD_STATE_STABLE must be 1 after mount delay")
                    assertTrue(hotPlug.isMediaReady(2))

                    fun step(delta: Long) {
                        tick += delta
                        runtime.host.advanceTick(tick)
                        sdBank1.beforeInstruction(tick)
                    }
                    fun issue(index: Int, argument: Long = 0, response: Int = 2, mode: Int = 0, hasData: Boolean = false) {
                        sdBank1.write(8, 2, argument and 0xffffL)
                        sdBank1.write(10, 2, (argument ushr 16) and 0xffffL)
                        sdBank1.write(12, 4, (((index shl 8) or response or (if (hasData) 0x20 else 0)).toLong() shl 16) or mode.toLong())
                    }
                    fun cmd(index: Int, argument: Long = 0, response: Int = 2) {
                        issue(index, argument, response)
                        step(2)
                        if (response == 3) step(3)
                    }
                    sdBank1.write(0x34, 2, 0xfb)
                    sdBank1.write(0x38, 2, 0xfb)
                    cmd(0, response = 0)
                    cmd(8, 0x1aa)
                    cmd(55)
                    cmd(41, 0xff8000L)
                    cmd(2, response = 1)
                    cmd(3)
                    cmd(9, 0x2222L shl 16, response = 1)
                    cmd(7, 0x2222L shl 16, response = 3)
                    sdBank1.write(0x30, 2, 0xfb)

                    sdBank1.write(4, 2, 512)
                    sdBank1.write(6, 2, 1)
                    sdBank1.write(0, 4, 0x0400)
                    issue(17, argument = 0, response = 2, mode = 0x11, hasData = true)
                    step(5)
                    assertContentEquals(slot2Bytes.copyOfRange(0, 512), dmaRam.copyOfRange(0x0400, 0x0600))

                    // --- Part 2: EVF LCDC0 (0xFFF7A044) Power/Clock Gate ---
                    val lcdc0 = checkNotNull(runtime.host.ports.find("soc-vglib-init.lcdc0-functional", MmioDevice::class.java))
                    val lcdc0Power = checkNotNull(runtime.host.ports.find("soc-vglib-init.lcdc0-power", LcdcPowerGatePort::class.java))
                    assertFailsWith<PeripheralBusAbortException> { lcdc0.read(0x044L, 4) }
                    lcdc0Power.setLcdc0PowerClock(true, true)
                    lcdc0.write(0x044L, 4, 0x4200_0000L)
                    assertEquals(0x4200_0000L, lcdc0.read(0x044L, 4))

                    // --- Part 3: Retail UART0 (0xFFF3A000) Clock Gate ---
                    val uartBank = checkNotNull(runtime.host.ports.find("soc-uart.uart-bank", MmioDevice::class.java))
                    val uartCr = checkNotNull(runtime.host.ports.find("soc-uart.uart-config", MmioDevice::class.java))
                    assertFailsWith<PeripheralBusAbortException> { uartBank.write(0x00L, 4, 'H'.code.toLong()) }
                    uartCr.write(0x00L, 4, 0x0301L) // UARTCR: UARTEN | TXE | RXE
                    uartBank.write(0x00L, 4, 'H'.code.toLong())
                    assertEquals(0x90L, uartBank.read(0x18L, 4))
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
