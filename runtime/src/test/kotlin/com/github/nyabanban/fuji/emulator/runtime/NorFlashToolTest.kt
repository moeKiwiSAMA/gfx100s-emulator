package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NorFlashToolTest {
    @TempDir
    lateinit var tempRoot: Path

    private fun writeBlankNor(path: Path) {
        Files.createDirectories(path.parent)
        val mb = ByteArray(1024 * 1024) { 0xFF.toByte() }
        Files.newOutputStream(path).use { out ->
            repeat(64) { out.write(mb) }
        }
    }

    private fun buildSyntheticDat(path: Path, payloadSize: Int = 0x60000, seed: Int = 0x33): ByteArray {
        val decoded = ByteArray(payloadSize) { idx -> ((idx * 17 + seed) and 0xFF).toByte() }
        // Place ARM branch-like vector at boot.bin offset 0x20000
        decoded[0x20000] = 0x1E
        decoded[0x20001] = 0x00
        decoded[0x20002] = 0x00
        decoded[0x20003] = 0xEA.toByte()

        val raw = ByteArray(0x274 + payloadSize)
        val hdr = ByteBuffer.wrap(raw, 0, 0x274).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(0x000, 6) // Type-6 Fuji DAT
        hdr.putInt(0x24C, payloadSize)
        var sum = 0L
        for (i in 0 until payloadSize) {
            val dec = decoded[i].toInt() and 0xFF
            sum = (sum + dec) and 0xFFFFFFFFL
            raw[0x274 + i] = (dec xor 0xFF).toByte()
        }
        hdr.putInt(0x20C, (sum.inv() and 0xFFFFFFFFL).toInt())
        Files.write(path, raw)
        return decoded
    }

    @Test
    fun flashDatExportImportAndRestorePreserveCalibrationAndValidateChecksums() {
        val factoryPath = tempRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin")
        val liveNorPath = tempRoot.resolve("flash/nor-flash.bin")
        writeBlankNor(factoryPath)

        // Seed distinct calibration marker in bank-14 (0x03500000) and user settings in 0x03DC0000
        RandomAccessFile(factoryPath.toFile(), "rw").use { raf ->
            raf.seek(0x03500000L)
            raf.write("FACTORY-CAL-V1".toByteArray())
        }
        val cfg = mapOf(
            "executionMode" to RelaxedPeripheralFallback.MODE,
            "norFlashFile" to "flash/nor-flash.bin",
            "factoryFlashFile" to "runtime/templates/factory-flash/nor-flash-2.13.bin",
        )

        // 1. ensureLiveNorExists seeds live nor-flash.bin from factory template
        assertTrue(NorFlashTool.ensureLiveNorExists(tempRoot, cfg))
        assertTrue(Files.isRegularFile(liveNorPath))
        assertEquals(NorFlashTool.NOR_SIZE_BYTES, Files.size(liveNorPath))
        // Second call is a no-op
        assertFalse(NorFlashTool.ensureLiveNorExists(tempRoot, cfg))

        // Mutate user settings inside [0x02FE0000..0x04000000) to simulate OOBE settings
        RandomAccessFile(liveNorPath.toFile(), "rw").use { raf ->
            raf.seek(0x03DE0000L)
            raf.write("USER-OOBE-SETTINGS".toByteArray())
        }

        // 2. Flash synthetic .DAT with preserveCalibration = true
        val datPath = tempRoot.resolve("custom.DAT")
        val expectedDecoded = buildSyntheticDat(datPath)
        val flashRes = NorFlashTool.flashDatIntoNor(datPath, liveNorPath, preserveCalibration = true)
        assertTrue(flashRes.preservedCalibration)
        assertEquals(expectedDecoded.size.toLong(), flashRes.seg0Length)

        // Verify firmware bytes written accurately AND calibration/user settings preserved
        RandomAccessFile(liveNorPath.toFile(), "r").use { raf ->
            val bootPrefix = ByteArray(4)
            raf.seek(0x20000L)
            raf.readFully(bootPrefix)
            assertContentEquals(byteArrayOf(0x1E, 0x00, 0x00, 0xEA.toByte()), bootPrefix)

            val factoryCal = ByteArray(14)
            raf.seek(0x03500000L)
            raf.readFully(factoryCal)
            assertEquals("FACTORY-CAL-V1", String(factoryCal))

            val userSettings = ByteArray(18)
            raf.seek(0x03DE0000L)
            raf.readFully(userSettings)
            assertEquals("USER-OOBE-SETTINGS", String(userSettings))
        }

        // 3. Inspect reports stage1Present & calibrationPopulated
        val inspected = NorFlashTool.inspectNor(liveNorPath)
        assertTrue(inspected.stage1Present)
        assertTrue(inspected.calibrationPopulated)
        assertEquals(flashRes.norSha256, inspected.sha256)

        // 4. Export -> Wipe calibration via preserveCalibration=false -> Import back
        val exportedPath = tempRoot.resolve("backup.bin")
        val expRes = NorFlashTool.exportNor(liveNorPath, exportedPath)
        assertEquals(flashRes.norSha256, expRes.sha256)

        NorFlashTool.flashDatIntoNor(datPath, liveNorPath, preserveCalibration = false)
        assertFalse(NorFlashTool.inspectNor(liveNorPath).calibrationPopulated)

        val impRes = NorFlashTool.importNor(exportedPath, liveNorPath)
        assertEquals(expRes.sha256, impRes.sha256)
        assertTrue(NorFlashTool.inspectNor(liveNorPath).calibrationPopulated)

        // 5. Restore factory restores exact factory SHA-256
        val restoreRes = NorFlashTool.restoreFactoryNor(factoryPath, liveNorPath)
        assertEquals(NorFlashTool.sha256File(factoryPath), restoreRes.sha256)
        assertEquals(NorFlashTool.sha256File(factoryPath), NorFlashTool.sha256File(liveNorPath))
    }
}
