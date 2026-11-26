package com.github.nyabanban.fuji.emulator.firmware

import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.*

class BootRomMetadataTest {
    private val projectRoot: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml")) }

    private fun stockRaw(): Map<String, Any> =
        Formats.read(projectRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml"))

    private fun bootRomRaw(): Map<String, Any> =
        Formats.read(projectRoot.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml"))

    @Test
    fun bootRomExperimentMetadataParsesAndRejectsMalformedVariants() {
        val meta = FirmwareMetadata(bootRomRaw())
        assertEquals(FirmwareMetadata.BOOT_ROM_EXPERIMENT, meta.bootMode)
        assertEquals(0xfff00000L, meta.entry)
        assertEquals(0xd3, meta.cpsr)
        val spec = assertNotNull(meta.bootRom)
        assertEquals(FirmwareMetadata.BOOT_ROM_ADDRESS, spec.address)
        assertEquals(FirmwareMetadata.BOOT_ROM_SIZE, spec.size)
        assertEquals(FirmwareMetadata.BOOT_ROM_SHA256, spec.sha256)
        assertTrue("boot-rom-cold-start-experimental-v1" in meta.memoryProfiles)

        val base = bootRomRaw()
        assertFailsWith<IllegalArgumentException> { FirmwareMetadata(base - "bootRom") }
        assertFailsWith<IllegalArgumentException> { FirmwareMetadata(base + ("entry" to "0x10000")) }
        assertFailsWith<IllegalArgumentException> { FirmwareMetadata(base + ("cpsr" to "0x10")) }
        assertFailsWith<IllegalArgumentException> {
            FirmwareMetadata(base + ("bootRom" to mapOf("address" to "0x0", "size" to "0x8000", "sha256" to spec.sha256)))
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareMetadata(base + ("bootRom" to mapOf("address" to "0xfff00000", "size" to "0x4000", "sha256" to spec.sha256)))
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareMetadata(base + ("bootRom" to mapOf("address" to "0xfff00000", "size" to "0x8000", "sha256" to "0".repeat(64))))
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareMetadata(stockRaw() + ("bootRom" to mapOf("address" to "0xfff00000", "size" to "0x8000", "sha256" to spec.sha256)))
        }
        assertFailsWith<IllegalArgumentException> {
            FirmwareMetadata(stockRaw() + ("entry" to "0xfff00000"))
        }
    }

    @Test
    fun bootRomImageLoadsPlainBytesWithoutXorInversionAndChecksExactSizeAndSha256() {
        val meta = FirmwareMetadata(bootRomRaw())
        assertFailsWith<IllegalArgumentException> { BootRomImage.load(Path.of("missing-bootrom.bin"), meta) }

        val tempDir = Files.createTempDirectory("bootrom-meta-test")
        try {
            val wrongSha = ByteArray(0x8000) { (it * 31 + 7).toByte() }
            val wrongPath = tempDir.resolve("wrong.bin")
            Files.write(wrongPath, wrongSha)
            assertFailsWith<IllegalArgumentException> { BootRomImage.load(wrongPath, meta) }

            val shortPath = tempDir.resolve("short.bin")
            Files.write(shortPath, wrongSha.copyOf(0x4000))
            assertFailsWith<IllegalArgumentException> { BootRomImage.load(shortPath, meta) }

            val dumpZip = projectRoot.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip")
            assumeTrue(Files.isRegularFile(dumpZip), "GFX100S_DUMP.zip required")
            val realBytes = ZipFile(dumpZip.toFile()).use { zip ->
                val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
                zip.getInputStream(entry).use { it.readNBytes(32768) }
            }
            val realPath = tempDir.resolve("boot-rom.bin")
            Files.write(realPath, realBytes)
            val loaded = BootRomImage.load(realPath, meta)
            assertContentEquals(realBytes, loaded)

            val inverted = ByteArray(realBytes.size) { realBytes[it].toInt().inv().toByte() }
            val invertedPath = tempDir.resolve("inverted.bin")
            Files.write(invertedPath, inverted)
            assertFailsWith<IllegalArgumentException> { BootRomImage.load(invertedPath, meta) }
            assertFailsWith<IllegalArgumentException> { BootRomImage.load(realPath, FirmwareMetadata(stockRaw())) }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
