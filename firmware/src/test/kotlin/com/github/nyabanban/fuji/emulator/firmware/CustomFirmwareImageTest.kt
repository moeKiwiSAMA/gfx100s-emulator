package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*

class CustomFirmwareImageTest {
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun buildType6Dat(bootPlain: ByteArray, headerVersion: Int = 6, corruptChecksum: Boolean = false): ByteArray {
        val seg0Len = 0x20000 + bootPlain.size
        val dat = ByteArray(FirmwareImage.DAT_HEADER_SIZE + seg0Len)
        // Fill seg0 raw with 0xFF (so decoded plain is 0x00)
        for (i in FirmwareImage.DAT_HEADER_SIZE until dat.size) {
            dat[i] = 0xff.toByte()
        }
        // Write bootPlain XOR 0xFF at +0x20274 (seg0 + 0x20000)
        for (i in bootPlain.indices) {
            dat[0x20274 + i] = bootPlain[i].toInt().inv().toByte()
        }
        val hdr = ByteBuffer.wrap(dat, 0, FirmwareImage.DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(FirmwareImage.DAT_HEADER_VERSION_OFFSET, headerVersion)
        hdr.putInt(FirmwareImage.DAT_SEG0_LENGTH_OFFSET, seg0Len)
        val checksum = FirmwareImage.computeSeg0Checksum(dat, seg0Len)
        hdr.putInt(FirmwareImage.DAT_SEG0_CHECKSUM_OFFSET, (if (corruptChecksum) checksum xor 1L else checksum).toInt())
        return dat
    }

    private fun metadataFor(stockDat: ByteArray, stockBoot: ByteArray): FirmwareMetadata = FirmwareMetadata(
        mapOf(
            "schema" to 1,
            "id" to "custom-dat-test",
            "version" to "TEST",
            "size" to stockDat.size,
            "sha256" to sha256(stockDat),
            "entry" to 0,
            "cpsr" to 0xd3,
            "loaderMemoryBytes" to 0x20000,
            "bootMode" to "public-synthetic-guest",
            "sections" to listOf(
                mapOf(
                    "name" to "boot",
                    "address" to 0,
                    "size" to stockBoot.size,
                    "offset" to 0x20274,
                    "encoding" to "raw",
                    "sha256" to sha256(stockBoot),
                ),
            ),
            "devices" to emptyList<Any>(),
            "evidence" to listOf("TEST"),
        ),
    )

    @Test
    fun defaultStrictHashRejectsPatchedDatWhileOptInCustomValidatesContainerAndChecksum(@TempDir tempDir: Path) {
        val stockBoot = ByteArray(64) { 0x11.toByte() }
        val stockDat = buildType6Dat(stockBoot)
        val meta = metadataFor(stockDat, stockBoot)

        val patchedBoot = stockBoot.copyOf().also { it[0] = 0x42.toByte() }
        val patchedDat = buildType6Dat(patchedBoot)
        val patchedPath = tempDir.resolve("GXUP0004_BANBAN.DAT")
        Files.write(patchedPath, patchedDat)

        // 1. Default allowCustomFirmware=false strictly refuses even 1-byte patch with valid checksum
        val defaultErr = assertFailsWith<IllegalArgumentException> {
            FirmwareImage(meta, patchedPath)
        }
        assertTrue(defaultErr.message.orEmpty().contains("firmware SHA-256 differs"), defaultErr.toString())

        // 2. allowCustomFirmware=true validates header_version==6, seg0 bounds, and ~sum checksum, logs [CUSTOM-FW], and decodes patched bytes
        val logger = Logger.getLogger(FirmwareImage::class.java.name)
        val logs = mutableListOf<String>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs.add(record.message) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            val custom = FirmwareImage(meta, patchedPath, allowCustomFirmware = true)
            assertTrue(custom.customFirmwareLoaded)
            assertEquals(sha256(patchedDat), custom.actualSha256)
            assertEquals(sha256(patchedDat), custom.inputPort().sha256)
            assertNotEquals(meta.sha256, custom.actualSha256)
            assertContentEquals(patchedBoot, custom.decode("boot"))
            val inspect = custom.inspect()
            assertEquals(true, inspect["customFirmware"])
            assertEquals(sha256(patchedDat), inspect["sha256"])
            assertTrue(logs.any { it.contains("[CUSTOM-FW]") && it.contains(custom.actualSha256) }, "Expected [CUSTOM-FW] log: $logs")
        } finally {
            logger.removeHandler(handler)
        }

        // 3. allowCustomFirmware=true still strictly rejects corrupt checksum or wrong header_version
        val badChecksumPath = tempDir.resolve("bad-checksum.dat")
        Files.write(badChecksumPath, buildType6Dat(patchedBoot, corruptChecksum = true))
        val chkErr = assertFailsWith<IllegalArgumentException> {
            FirmwareImage(meta, badChecksumPath, allowCustomFirmware = true)
        }
        assertTrue(chkErr.message.orEmpty().contains("checksum differs"), chkErr.toString())

        val badVerPath = tempDir.resolve("bad-ver.dat")
        Files.write(badVerPath, buildType6Dat(patchedBoot, headerVersion = 5))
        val verErr = assertFailsWith<IllegalArgumentException> {
            FirmwareImage(meta, badVerPath, allowCustomFirmware = true)
        }
        assertTrue(verErr.message.orEmpty().contains("header_version differs"), verErr.toString())
    }
}
