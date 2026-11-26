package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RelaxedFlashPersistenceTest {
    @TempDir
    lateinit var tempRoot: Path

    @Test
    fun ensureBlankSdImagesInitializesRawQcow2AndDirectoryBackendsInRelaxedModeOnly() {
        val home = tempRoot.resolve("home")
        val readerDir = home.resolve("compoments/sdcard-reader")
        val sdHostDir = home.resolve("compoments/soc-sd-host")
        Files.createDirectories(readerDir)
        Files.createDirectories(sdHostDir)

        val capSlot1 = 64L * 1024L * 1024L
        val capSlot2 = 128L * 1024L * 1024L
        Formats.atomicYaml(sdHostDir.resolve("soc-sd-host.yaml"), mapOf("inputFile" to "sd-card-v7.json"))
        Formats.writeJson(
            sdHostDir.resolve("sd-card-v7.json"),
            mapOf(
                "cards" to mapOf(
                    "0" to mapOf("capacityBytes" to capSlot1),
                    "1" to mapOf("capacityBytes" to capSlot2),
                ),
            ),
        )

        // Slot 1 = qcow2, Slot 2 = directory
        Formats.atomicYaml(
            readerDir.resolve("sdcard-reader.yaml"),
            mapOf(
                "slot1Inserted" to true,
                "slot1Image" to "slot1.qcow2",
                "slot1Backend" to "qcow2",
                "slot1ReadOnly" to false,
                "slot2Inserted" to true,
                "slot2Image" to "slot2-dir",
                "slot2Backend" to "directory",
                "slot2CapacityBytes" to capSlot2,
                "slot2ReadOnly" to false,
            ),
        )

        // Must NOT touch anything in strict or discovery modes
        assertEquals(0, RelaxedFlashPersistence.ensureBlankSdImages(home, mapOf("executionMode" to "discovery", "components" to "compoments")))
        assertEquals(0, RelaxedFlashPersistence.ensureBlankSdImages(home, mapOf("executionMode" to "strict", "components" to "compoments")))
        assertFalse(Files.exists(readerDir.resolve("slot1.qcow2")))
        assertFalse(Files.exists(readerDir.resolve("slot2-dir")))

        // In relaxed mode, creates empty QCOW2 v3 for slot 1 and empty directory for slot 2
        assertEquals(2, RelaxedFlashPersistence.ensureBlankSdImages(home, mapOf("executionMode" to "relaxed", "components" to "compoments")))
        val qcow2 = readerDir.resolve("slot1.qcow2")
        assertTrue(Files.isRegularFile(qcow2))
        val hdr = ByteBuffer.wrap(Files.readAllBytes(qcow2), 0, 104).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x514649FB, hdr.getInt(0x00))
        assertEquals(3, hdr.getInt(0x04))
        assertEquals(16, hdr.getInt(0x14))
        assertEquals(capSlot1, hdr.getLong(0x18))
        assertEquals(4, hdr.getInt(0x60))
        assertEquals(104, hdr.getInt(0x64))
        assertTrue(Files.isDirectory(readerDir.resolve("slot2-dir")))

        // Re-running is idempotent (0 newly created)
        assertEquals(0, RelaxedFlashPersistence.ensureBlankSdImages(home, mapOf("executionMode" to "relaxed", "components" to "compoments")))

        // Also test raw backend with explicit slot1CapacityBytes override
        val rawHome = tempRoot.resolve("raw-home")
        val rawReaderDir = rawHome.resolve("compoments/sdcard-reader")
        Files.createDirectories(rawReaderDir)
        Formats.atomicYaml(
            rawReaderDir.resolve("sdcard-reader.yaml"),
            mapOf(
                "slot1Inserted" to true,
                "slot1Image" to "slot1.img",
                "slot1Backend" to "raw",
                "slot1CapacityBytes" to 4L * 1024L * 1024L,
                "slot2Inserted" to false,
            ),
        )
        assertEquals(1, RelaxedFlashPersistence.ensureBlankSdImages(rawHome, mapOf("executionMode" to "relaxed", "components" to "compoments")))
        assertEquals(4L * 1024L * 1024L, Files.size(rawReaderDir.resolve("slot1.img")))
    }

    @Test
    fun resetButtonsForBootRestoresPowerAndClearsStaleMomentaryKeysInRelaxedModeOnly() {
        val home = tempRoot.resolve("btn-home")
        val buttonsDir = home.resolve("compoments/buttons")
        Files.createDirectories(buttonsDir)
        val buttonsYaml = buttonsDir.resolve("buttons.yaml")
        Formats.atomicYaml(
            buttonsYaml,
            mapOf("menu" to true, "down" to true, "power" to false, "play" to false),
        )

        // Must NEVER modify buttons.yaml in discovery or strict modes
        assertFalse(RelaxedFlashPersistence.resetButtonsForBoot(home, mapOf("executionMode" to "discovery", "components" to "compoments")))
        assertFalse(RelaxedFlashPersistence.resetButtonsForBoot(home, mapOf("executionMode" to "strict", "components" to "compoments")))
        assertEquals(false, Formats.read(buttonsYaml)["power"])
        assertEquals(true, Formats.read(buttonsYaml)["menu"])

        // In relaxed mode, resets momentary keys to false and restores power=true
        assertTrue(RelaxedFlashPersistence.resetButtonsForBoot(home, mapOf("executionMode" to "relaxed", "components" to "compoments")))
        val after = Formats.read(buttonsYaml)
        assertEquals(true, after["power"])
        assertEquals(false, after["menu"])
        assertEquals(false, after["down"])
        assertEquals(false, after["play"])

        // Second call is idempotent
        assertFalse(RelaxedFlashPersistence.resetButtonsForBoot(home, mapOf("executionMode" to "relaxed", "components" to "compoments")))
    }

    @Test
    fun resetButtonsForBootHonorsBootHoldOptInConfigAndSidecar() {
        val home = tempRoot.resolve("btn-hold-home")
        val buttonsDir = home.resolve("compoments/buttons")
        Files.createDirectories(buttonsDir)
        val buttonsYaml = buttonsDir.resolve("buttons.yaml")
        Formats.atomicYaml(
            buttonsYaml,
            mapOf("back" to false, "menu" to true, "drive" to true, "afOn" to true, "power" to false, "movieMode" to true, "focusMode" to true),
        )

        // Config list opt-in sets `back: true`, ignores unknown key, resets stale `menu`/`drive`/`afOn`, restores `power`, preserves `movieMode` & `focusMode`
        val cfgList = mapOf(
            "executionMode" to "relaxed",
            "components" to "compoments",
            "bootHoldButtons" to listOf("back", "nonExistentKey"),
        )
        assertTrue(RelaxedFlashPersistence.resetButtonsForBoot(home, cfgList))
        val afterList = Formats.read(buttonsYaml)
        assertEquals(true, afterList["back"])
        assertEquals(false, afterList["menu"])
        assertEquals(false, afterList["drive"])
        assertEquals(false, afterList["afOn"])
        assertEquals(true, afterList["power"])
        assertEquals(true, afterList["movieMode"])
        assertEquals(true, afterList["focusMode"])
        assertFalse("nonExistentKey" in afterList)

        // Idempotent when already in requested boot-hold state
        assertFalse(RelaxedFlashPersistence.resetButtonsForBoot(home, cfgList))

        // Sidecar buttons/boot-hold.yaml opt-in holds `menu` and releases `back`
        Formats.atomicYaml(buttonsDir.resolve("boot-hold.yaml"), mapOf("holdButtons" to "menu"))
        val cfgDefault = mapOf("executionMode" to "relaxed", "components" to "compoments")
        assertTrue(RelaxedFlashPersistence.resetButtonsForBoot(home, cfgDefault))
        val afterSidecar = Formats.read(buttonsYaml)
        assertEquals(false, afterSidecar["back"])
        assertEquals(true, afterSidecar["menu"])
        assertEquals(true, afterSidecar["power"])
    }
}
