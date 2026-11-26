package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SdHotPlugMountDelayTest {
    private class HotPlugContext(override val directory: Path) : ComponentContext {
        var currentTick = 0L
        val registry = linkedMapOf<String, Any>()
        val eventsLog = mutableListOf<ComponentEvent>()
        override val clock = EmulationClock { currentTick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                require(registry.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = registry[name]?.let(type::cast)
            override fun removeOwner(owner: String) {
                registry.clear()
            }
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {
                eventsLog += event
            }
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    @Test
    fun cardDetGpioAssertsImmediatelyWhileEarlyMediaOpenFailsUntilReadyDelayElapses(@TempDir tempRoot: Path) {
        val img1 = tempRoot.resolve("slot1.img")
        val img2 = tempRoot.resolve("slot2.img")
        val data1 = ByteArray(4096) { (it and 0xff).toByte() }
        val data2 = ByteArray(4096) { ((it xor 0x5a) and 0xff).toByte() }
        Files.write(img1, data1)
        Files.write(img2, data2)

        val gpioLevels = mutableMapOf<Pair<Int, Int>, Boolean>()
        val ctx = HotPlugContext(tempRoot)
        ctx.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { bank, pin, high ->
            gpioLevels[bank to pin] = high
        })

        val comp = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "slot1.img",
                "slot1Inserted" to true,
                "slot1ReadOnly" to false,
                "slot2Image" to "slot2.img",
                "slot2Inserted" to true,
                "slot2ReadOnly" to false,
                "cardInsertReadyDelayTicks" to 50_000L,
                "syncCardDetectGpio" to true,
            ),
        )
        comp.start()
        try {
            val storage = checkNotNull(ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as? CheckedBlockStoragePort)
            val hotPlug = checkNotNull(ctx.ports.find(SdcardFactory.PORT_HOTPLUG, HotPlugCardControlPort::class.java))

            // At t = 0: both CARD_DET GPIOs (10.4 & 10.8) are active-LOW (false = inserted),
            // while media is NOT ready yet until t >= 50_000!
            assertEquals(false, gpioLevels[10 to 4], "Slot 0 (GPIO 10.4) CARD_DET must assert LOW at t=0")
            assertEquals(false, gpioLevels[10 to 8], "Slot 1 (GPIO 10.8) CARD2_DET must assert LOW at t=0")
            assertFalse(hotPlug.isMediaReady(1))
            assertFalse(hotPlug.isMediaReady(2))

            // Early B:\ (slot 2) open right on CARD2_DET == 0 fails cleanly!
            val earlyBuf = ByteBuffer.allocate(512)
            assertFailsWith<IllegalStateException> { storage.checkAccess(2, 0, 512, false) }
            assertFailsWith<IllegalStateException> { storage.read(2, 0, earlyBuf) }
            assertEquals(2L, hotPlug.slotHotPlugStatus(2).earlyAccessAttempts)

            // Just before deadline (t = 49_999): still not mounted/ready
            ctx.currentTick = 49_999L
            assertFalse(hotPlug.isMediaReady(2))
            assertFailsWith<IllegalStateException> { storage.read(2, 0, earlyBuf) }
            assertEquals(3L, hotPlug.slotHotPlugStatus(2).earlyAccessAttempts)

            // At deadline (t = 50_000): mount completes, B:\ reads succeed bit-exact!
            ctx.currentTick = 50_000L
            assertTrue(hotPlug.isMediaReady(1))
            assertTrue(hotPlug.isMediaReady(2))
            earlyBuf.clear()
            assertEquals(512, storage.read(2, 0, earlyBuf))
            assertContentEquals(data2.copyOfRange(0, 512), earlyBuf.array())

            // Runtime eject de-asserts GPIO 10.8 HIGH immediately and refuses access
            val ejected = hotPlug.ejectCard(2)
            assertFalse(ejected.cardDetectAsserted)
            assertFalse(ejected.mediaReady)
            assertEquals(true, gpioLevels[10 to 8], "Ejecting Slot 1 must de-assert GPIO 10.8 HIGH")
            assertNull(storage.size(2))
            assertFailsWith<IllegalStateException> { storage.read(2, 0, ByteBuffer.allocate(512)) }

            // Runtime hot-insert at t = 100_000 with 25_000-tick mount delay
            ctx.currentTick = 100_000L
            val inserted = hotPlug.insertCard(2, readyDelayTicks = 25_000L)
            assertTrue(inserted.cardDetectAsserted)
            assertFalse(inserted.mediaReady)
            assertEquals(100_000L, inserted.insertedAtTick)
            assertEquals(125_000L, inserted.readyAtTick)
            assertEquals(false, gpioLevels[10 to 8], "Hot-insert must immediately assert GPIO 10.8 LOW at t=100_000")
            assertFailsWith<IllegalStateException> { storage.read(2, 0, ByteBuffer.allocate(512)) }

            // Advance past 125_000 -> B:\ readable again
            ctx.currentTick = 125_000L
            assertTrue(hotPlug.isMediaReady(2))
            val reread = ByteBuffer.allocate(512)
            assertEquals(512, storage.read(2, 0, reread))
            assertContentEquals(data2.copyOfRange(0, 512), reread.array())
        } finally {
            comp.close()
        }
    }
}
