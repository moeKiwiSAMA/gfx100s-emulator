package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SdLiveSettingsTest {
    private class LiveTestContext(override val directory: Path) : ComponentContext {
        var currentTick = 100L
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
    fun descriptorMarksInsertionAndReadOnlySettingsLiveAndOtherSettingsRestartOnly() {
        val descriptor = SdcardFactory().descriptor
        val byKey = descriptor.settings.associateBy { it.key }
        for (liveKey in listOf("slot1Inserted", "slot2Inserted", "slot1ReadOnly", "slot2ReadOnly")) {
            assertTrue(byKey.getValue(liveKey).live, "$liveKey must be live=true")
        }
        for (restartKey in listOf(
            "slot1Image", "slot2Image", "slot1Backend", "slot2Backend",
            "slot1CapacityBytes", "slot2CapacityBytes", "slot1BackingFile", "slot2BackingFile",
            "cardInsertReadyDelayTicks", "syncCardDetectGpio", "asyncIo",
        )) {
            assertFalse(byKey.getValue(restartKey).live, "$restartKey must remain live=false")
        }
    }

    @Test
    fun liveInsertedAndReadOnlyToggleWorksAcrossRawQcow2AndDirectoryWithGpioAndEvents(@TempDir tempRoot: Path) {
        val rawPath = tempRoot.resolve("slot1.img")
        Files.write(rawPath, ByteArray(4096) { 0x11 })
        val dirPath = tempRoot.resolve("slot2-dir")
        Files.createDirectories(dirPath)

        val gpioLevels = mutableMapOf<Pair<Int, Int>, Boolean>()
        val ctx = LiveTestContext(tempRoot)
        ctx.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { bank, pin, high ->
            gpioLevels[bank to pin] = high
        })

        val comp = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "slot1.img",
                "slot1Inserted" to true,
                "slot1ReadOnly" to true,
                "slot1Backend" to "raw",
                "slot2Image" to "slot2-dir",
                "slot2Inserted" to false,
                "slot2ReadOnly" to false,
                "slot2Backend" to "directory",
                "slot2CapacityBytes" to 64L * 1024L * 1024L,
                "syncCardDetectGpio" to true,
            ),
        )
        comp.start()
        try {
            val storage = checkNotNull(ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as? CheckedBlockStoragePort)
            // Initial boot: slot 1 inserted (GPIO 10.4 LOW) & read-only; slot 2 empty (GPIO 10.8 HIGH)
            assertEquals(false, gpioLevels[10 to 4])
            assertEquals(true, gpioLevels[10 to 8])
            assertTrue(storage.readOnly(1))
            assertNull(storage.size(2))
            assertFailsWith<IllegalArgumentException> { storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x22 })) }

            // Live unlock slot 1 (readOnly: true -> false)
            comp.applySettings(mapOf("slot1ReadOnly" to false))
            assertFalse(storage.readOnly(1))
            assertEquals(512, storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x33 })))
            val readBack = ByteBuffer.allocate(512)
            assertEquals(512, storage.read(1, 0L, readBack))
            assertContentEquals(ByteArray(512) { 0x33 }, readBack.array())
            assertTrue(ctx.eventsLog.any { it.kind == "write-protect" && it.values["slot"] == 1 && it.values["readOnly"] == false })

            // Live lock slot 1 again (readOnly: false -> true)
            comp.applySettings(mapOf("slot1ReadOnly" to true))
            assertTrue(storage.readOnly(1))
            assertFailsWith<IllegalArgumentException> { storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x44 })) }

            // Live insert slot 2 (which was booted empty!) -> opens directory store, drives GPIO 10.8 LOW
            comp.applySettings(mapOf("slot2Inserted" to true))
            assertEquals(false, gpioLevels[10 to 8])
            assertEquals(64L * 1024L * 1024L, storage.size(2))
            assertFalse(storage.readOnly(2))
            assertTrue(ctx.eventsLog.any { it.kind == "hotplug" && it.values["slot"] == 2 && it.values["action"] == "insert" })

            // Live lock & unlock slot 2 directory store
            comp.applySettings(mapOf("slot2ReadOnly" to true))
            assertTrue(storage.readOnly(2))
            assertFailsWith<IllegalArgumentException> { storage.checkAccess(2, 0L, 512L, true) }
            comp.applySettings(mapOf("slot2ReadOnly" to false))
            assertFalse(storage.readOnly(2))
            storage.checkAccess(2, 0L, 512L, true)

            // Live eject slot 1 -> drives GPIO 10.4 HIGH, size becomes null, snapshot stays valid
            comp.applySettings(mapOf("slot1Inserted" to false))
            assertEquals(true, gpioLevels[10 to 4])
            assertNull(storage.size(1))
            assertTrue(ctx.eventsLog.any { it.kind == "hotplug" && it.values["slot"] == 1 && it.values["action"] == "eject" })
            val snap = (comp as StatefulComponent).saveSnapshot()
            @Suppress("UNCHECKED_CAST")
            val snapSlot1 = (snap["slots"] as List<Map<String, Any?>>)[0]
            assertEquals(false, snapSlot1["inserted"])
            assertNull(snapSlot1["sizeBytes"])

            // Restart-only settings still reject live edits atomically
            val beforeState = comp.state()
            assertFailsWith<IllegalArgumentException> { comp.validateSettings(mapOf("slot1Image" to "other.img")) }
            assertEquals(beforeState, comp.state())
        } finally {
            comp.close()
        }
    }

    @Test
    fun liveReadOnlyToggleWorksOnQcow2Backend(@TempDir tempRoot: Path) {
        val qcow2Path = tempRoot.resolve("slot1.qcow2")
        Qcow2BlockStore.create(qcow2Path, 2L * 1024L * 1024L)
        val ctx = LiveTestContext(tempRoot)
        val comp = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "slot1.qcow2",
                "slot1Inserted" to true,
                "slot1ReadOnly" to true,
                "slot1Backend" to "qcow2",
                "slot2Inserted" to false,
            ),
        )
        comp.start()
        try {
            val storage = checkNotNull(ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as? CheckedBlockStoragePort)
            assertTrue(storage.readOnly(1))
            assertFailsWith<IllegalArgumentException> { storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x55 })) }

            comp.applySettings(mapOf("slot1ReadOnly" to false))
            assertFalse(storage.readOnly(1))
            assertEquals(512, storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x55 })))
            val buf = ByteBuffer.allocate(512)
            assertEquals(512, storage.read(1, 0L, buf))
            assertContentEquals(ByteArray(512) { 0x55 }, buf.array())

            comp.applySettings(mapOf("slot1ReadOnly" to true))
            assertTrue(storage.readOnly(1))
            assertFailsWith<IllegalArgumentException> { storage.write(1, 0L, ByteBuffer.wrap(ByteArray(512) { 0x66 })) }
        } finally {
            comp.close()
        }
    }

    @Test
    fun liveInsertAutoInitializesMissingDirectoryAndQcow2Targets(@TempDir dir: Path) {
        val gpioLevels = mutableMapOf<Pair<Int, Int>, Boolean>()
        val ctx = LiveTestContext(dir)
        ctx.ports.register("soc-gpio", "soc-gpio.inputs", GpioInputPort::class.java, GpioInputPort { bank, pin, high ->
            gpioLevels[bank to pin] = high
        })
        val comp = SdcardFactory().create(
            ctx,
            mapOf(
                "slot1Image" to "slot1-lazy-dir",
                "slot1Inserted" to false,
                "slot1ReadOnly" to false,
                "slot1Backend" to "directory",
                "slot1CapacityBytes" to 64L * 1024L * 1024L,
                "slot2Image" to "slot2-lazy.qcow2",
                "slot2Inserted" to false,
                "slot2ReadOnly" to false,
                "slot2Backend" to "qcow2",
                "slot2CapacityBytes" to 2L * 1024L * 1024L,
                "syncCardDetectGpio" to true,
            ),
        )
        comp.start()
        try {
            assertFalse(Files.exists(dir.resolve("slot1-lazy-dir")))
            assertFalse(Files.exists(dir.resolve("slot2-lazy.qcow2")))

            comp.applySettings(mapOf("slot1Inserted" to true, "slot2Inserted" to true))

            assertTrue(Files.isDirectory(dir.resolve("slot1-lazy-dir")))
            assertTrue(Files.isRegularFile(dir.resolve("slot2-lazy.qcow2")))
            val storage = checkNotNull(ctx.ports.find("sdcard-reader", BlockStoragePort::class.java) as? CheckedBlockStoragePort)
            assertEquals(64L * 1024L * 1024L, storage.size(1))
            assertEquals(2L * 1024L * 1024L, storage.size(2))
            assertEquals(false, gpioLevels[SdcardFactory.GPIO_CARD_DET_BANK to SdcardFactory.SLOT1_CARD_DET_PIN])
            assertEquals(false, gpioLevels[SdcardFactory.GPIO_CARD_DET_BANK to SdcardFactory.SLOT2_CARD_DET_PIN])
        } finally {
            comp.close()
        }
    }
}
