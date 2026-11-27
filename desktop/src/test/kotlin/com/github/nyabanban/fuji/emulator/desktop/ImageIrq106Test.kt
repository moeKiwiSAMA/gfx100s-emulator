package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ImageIrq106Test {
    @Test fun registeredEnabledQuietEdgeLatchesOnePendingInterrupt(@TempDir home: Path) {
        val components = Files.createDirectories(home.resolve("compoments"))
        val staged = Path.of(System.getProperty("componentProjectRoot"), "build/emulator/compoments")
        val jar = "soc-gic-0.3.1.jar"
        Files.copy(staged.resolve(jar), components.resolve(jar))
        val settings = Files.createDirectories(components.resolve("soc-gic")).resolve("soc-gic.yaml")
        Files.writeString(settings, "coreProfile: four-context-functional-experimental-v1\n")
        IntegrationTest().host(home) { owner, host ->
            owner.submit {
                val distributor = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
                val cpu = checkNotNull(host.ports.find("soc-gic.interface", MmioDevice::class.java))
                val lines = host.ports.find("soc-gic.lines", InterruptSink::class.java) as EnabledEdgeInterruptSink
                var handler = 0
                host.ports.register("cpu", "cpu.memory", DmaMemory::class.java, object : DmaMemory {
                    override fun read(address: Long, destination: ByteBuffer) {
                        assertEquals(0x01871ad0L, address)
                        assertEquals(4, destination.remaining())
                        destination.order(ByteOrder.LITTLE_ENDIAN).putInt(handler)
                    }
                    override fun write(address: Long, source: ByteBuffer) = error("Unexpected RAM write")
                })
                val changes = mutableListOf<Boolean>()
                host.events.subscribe { event ->
                    if (event.kind == "irq-line" && event.values["line"] == 106) changes += event.values["high"] as Boolean
                }.use {
                    FirmwareSession(host, home, mapOf("instructionBudget" to 1)).use { session ->
                        fun pulse() = session.signalImageInterrupt(106, false)
                        distributor.write(0, 4, 1)
                        cpu.write(0, 4, 1)
                        cpu.write(4, 4, 0xff)
                        assertFails { pulse() }
                        distributor.write(0x10c, 4, 1L shl 10)
                        assertFails { pulse() }
                        distributor.write(0xc18, 4, 2L shl 20)
                        assertFailsWith<IllegalArgumentException> { pulse() }.also { assertContains(it.message.orEmpty(), "handler") }
                        handler = 0x011b7f18
                        assertFails { pulse() }
                        handler = 0x011b7f14
                        assertFails { session.signalImageInterrupt(106, true) }
                        assertTrue(changes.isEmpty())
                        pulse()
                        assertEquals(EdgeInterruptState(true, false, true), lines.edgeState(106))
                        assertFails { pulse() }
                        assertEquals(106L, cpu.read(0x0c, 4))
                        assertFailsWith<IllegalArgumentException> { pulse() }.also { assertContains(it.message.orEmpty(), "active") }
                        cpu.write(0x10, 4, 106)
                        assertEquals(1023L, cpu.read(0x0c, 4))
                        assertEquals(listOf(true, false), changes)
                    }
                }
            }.join()
        }
    }
}
