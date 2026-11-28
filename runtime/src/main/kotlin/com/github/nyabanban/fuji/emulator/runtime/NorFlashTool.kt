package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Verified host-side manager for the GFX100S 64 MiB (`0x0400_0000` bytes) QSPI NOR Flash image
 * (`nor-flash.bin`).
 *
 * Layout:
 * - `[0x0000_0000, 0x02FE_0000)`: firmware region (Stage-1 IPL `bank-00`, `boot.bin` @ `0x20000`,
 *   `kernel.bin`, `section4`, `section5`, plus `0xFF` erased gap after `seg0`).
 * - `[0x02FE_0000, 0x0400_0000)`: persistent calibration & settings region (lens cache `part12`,
 *   `bank-13`, `bank-14` factory calibration/MAC/battery tables, `BKUP` settings banks).
 */
object NorFlashTool {
    private val LOG = LoggerFactory.getLogger(NorFlashTool::class.java)
    private val HEX = HexFormat.of()

    const val NOR_SIZE_BYTES = 64L * 1024L * 1024L // 67,108,864 bytes
    const val FIRMWARE_REGION_LIMIT = 0x02FE_0000L // 49,938,432 bytes (start of part12/calibration banks)
    const val BOOT_BIN_OFFSET = 0x20000L
    const val BOOT_BIN_SIZE = 0x20000L

    private const val DAT_HEADER_SIZE = 0x274
    private const val DAT_VERSION_OFFSET = 0x000
    private const val DAT_VERSION_EXPECTED = 6
    private const val DAT_SEG0_CHECKSUM_OFFSET = 0x20C
    private const val DAT_SEG0_LENGTH_OFFSET = 0x24C

    data class NorInspect(
        val path: Path,
        val sizeBytes: Long,
        val sha256: String,
        val bootBinSha256: String,
        val calibrationPopulated: Boolean,
        val stage1Present: Boolean,
    ) {
        fun toMap(): Map<String, Any> = linkedMapOf(
            "path" to path.toString(),
            "sizeBytes" to sizeBytes,
            "sha256" to sha256,
            "bootBinSha256" to bootBinSha256,
            "calibrationPopulated" to calibrationPopulated,
            "stage1Present" to stage1Present,
        )
    }

    data class FlashResult(
        val datPath: Path,
        val norPath: Path,
        val seg0Length: Long,
        val seg0Checksum: Long,
        val preservedCalibration: Boolean,
        val norSha256: String,
        val bootBinSha256: String,
    ) {
        fun toMap(): Map<String, Any> = linkedMapOf(
            "datPath" to datPath.toString(),
            "norPath" to norPath.toString(),
            "seg0Length" to seg0Length,
            "seg0Checksum" to seg0Checksum,
            "preservedCalibration" to preservedCalibration,
            "norSha256" to norSha256,
            "bootBinSha256" to bootBinSha256,
        )
    }

    fun resolveNorFlashPath(home: Path, config: Map<String, Any>): Path? =
        (config["norFlashFile"] as? String)?.takeIf { it.isNotBlank() }?.let {
            home.resolve(it).toAbsolutePath().normalize()
        }

    fun resolveFactoryFlashPath(home: Path, config: Map<String, Any>): Path? =
        (config["factoryFlashFile"] as? String)?.takeIf { it.isNotBlank() }?.let {
            home.resolve(it).toAbsolutePath().normalize()
        }

    /**
     * Ensures the live writable `norFlashFile` exists in `relaxed` mode by seeding it from
     * `factoryFlashFile` if absent. Never overwrites an existing live `nor-flash.bin`.
     */
    fun ensureLiveNorExists(home: Path, config: Map<String, Any>): Boolean {
        if (CatalogRuntime.mode(config) != RelaxedPeripheralFallback.MODE) return false
        val livePath = resolveNorFlashPath(home, config) ?: return false
        if (Files.exists(livePath, NOFOLLOW_LINKS)) return false
        val factoryPath = resolveFactoryFlashPath(home, config) ?: return false
        if (!Files.isRegularFile(factoryPath, NOFOLLOW_LINKS)) return false
        require(Files.size(factoryPath) == NOR_SIZE_BYTES) {
            "Factory NOR flash template size mismatch (${Files.size(factoryPath)} != $NOR_SIZE_BYTES): $factoryPath"
        }
        Files.createDirectories(livePath.parent)
        copyAtomic(factoryPath, livePath)
        clearCompanionSubMcuState(home, config)
        LOG.info("Initialized live 64 MiB NOR Flash {} from stock factory template {}", livePath, factoryPath)
        return true
    }

    /**
     * Deletes companion Sub-MCU retained NVRAM (`power-peer/submcu-nvram.{json,bin}`) and RTC
     * (`rtc/rtc-state.json`) state files when seeding or restoring a fresh factory NOR image, so
     * `RTC_HDR` (`bLanguageSet`/`bDatetimeSet`) never stays out of sync with an erased `BlockA`.
     */
    fun clearCompanionSubMcuState(home: Path, config: Map<String, Any>) {
        val roots = linkedSetOf<Path>()
        (config["components"] as? String)?.takeIf { it.isNotBlank() }?.let {
            roots.add(home.resolve(it).toAbsolutePath().normalize())
        }
        (config["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }?.let {
            roots.add(home.resolve(it).toAbsolutePath().normalize())
        }
        val relTargets = listOf(
            "power-peer/submcu-nvram.json",
            "power-peer/submcu-nvram.bin",
            "rtc/rtc-state.json",
        )
        for (root in roots) {
            for (rel in relTargets) {
                runCatching { Files.deleteIfExists(root.resolve(rel)) }
            }
        }
    }

    /**
     * Flashes a FUJIFILM type-6 `.DAT` firmware package (stock or custom, e.g. `GXUP0004.DAT`)
     * directly into the 64 MiB `nor-flash.bin` image, XOR-decoding `seg0` into `[0x0, seg0Length)`,
     * filling `[seg0Length, 0x02FE0000)` with `0xFF`, and preserving `[0x02FE0000, 0x04000000)`
     * calibration/settings when `preserveCalibration` is true and `norPath` already exists.
     */
    fun flashDatIntoNor(
        datPath: Path,
        norPath: Path,
        preserveCalibration: Boolean = true,
    ): FlashResult {
        require(Files.isRegularFile(datPath)) { "Firmware DAT file not found: $datPath" }
        val datBytes = Files.readAllBytes(datPath)
        require(datBytes.size > DAT_HEADER_SIZE) { "Firmware DAT file too small: ${datBytes.size} bytes" }
        val hdr = ByteBuffer.wrap(datBytes, 0, DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val version = hdr.getInt(DAT_VERSION_OFFSET)
        require(version == DAT_VERSION_EXPECTED) {
            "Unsupported DAT header_version=$version (expected $DAT_VERSION_EXPECTED): $datPath"
        }
        val seg0Length = hdr.getInt(DAT_SEG0_LENGTH_OFFSET).toLong() and 0xFFFF_FFFFL
        require(seg0Length in 1L..FIRMWARE_REGION_LIMIT && DAT_HEADER_SIZE + seg0Length <= datBytes.size.toLong()) {
            "DAT seg0 length out of bounds: 0x${seg0Length.toString(16)} (file=${datBytes.size})"
        }
        val storedChecksum = hdr.getInt(DAT_SEG0_CHECKSUM_OFFSET).toLong() and 0xFFFF_FFFFL
        var sum = 0L
        val seg0Int = seg0Length.toInt()
        for (i in 0 until seg0Int) {
            sum = (sum + ((datBytes[DAT_HEADER_SIZE + i].toInt() xor 0xFF) and 0xFF)) and 0xFFFF_FFFFL
        }
        val computedChecksum = sum.inv() and 0xFFFF_FFFFL
        require(storedChecksum == computedChecksum) {
            "DAT seg0 checksum mismatch: stored=0x${storedChecksum.toString(16)} computed=0x${computedChecksum.toString(16)}"
        }

        val norBytes = ByteArray(NOR_SIZE_BYTES.toInt()) { 0xFF.toByte() }
        val keptCalibration = preserveCalibration && Files.isRegularFile(norPath, NOFOLLOW_LINKS)
        if (keptCalibration) {
            require(Files.size(norPath) == NOR_SIZE_BYTES) {
                "Existing NOR flash size mismatch (${Files.size(norPath)} != $NOR_SIZE_BYTES): $norPath"
            }
            val calStart = FIRMWARE_REGION_LIMIT.toInt()
            val calLen = norBytes.size - calStart
            FileChannel.open(norPath, READ).use { ch ->
                val dst = ByteBuffer.wrap(norBytes, calStart, calLen)
                var pos = FIRMWARE_REGION_LIMIT
                while (dst.hasRemaining()) {
                    val n = ch.read(dst, pos)
                    check(n > 0) { "Short read preserving calibration region from $norPath" }
                    pos += n
                }
            }
        }

        // Write XOR-inverted seg0 directly into [0x0000_0000, seg0Length)
        for (i in 0 until seg0Int) {
            norBytes[i] = (datBytes[DAT_HEADER_SIZE + i].toInt() xor 0xFF).toByte()
        }

        writeAtomic(norPath, norBytes)
        val fullSha = sha256(norBytes, 0, norBytes.size)
        val bootSha = sha256(norBytes, BOOT_BIN_OFFSET.toInt(), BOOT_BIN_SIZE.toInt())
        return FlashResult(
            datPath = datPath.toAbsolutePath().normalize(),
            norPath = norPath.toAbsolutePath().normalize(),
            seg0Length = seg0Length,
            seg0Checksum = storedChecksum,
            preservedCalibration = keptCalibration,
            norSha256 = fullSha,
            bootBinSha256 = bootSha,
        )
    }

    fun exportNor(norPath: Path, destinationPath: Path): NorInspect {
        require(Files.isRegularFile(norPath, NOFOLLOW_LINKS)) { "NOR flash image not found: $norPath" }
        require(Files.size(norPath) == NOR_SIZE_BYTES) {
            "NOR flash size mismatch (${Files.size(norPath)} != $NOR_SIZE_BYTES): $norPath"
        }
        val dest = destinationPath.toAbsolutePath().normalize()
        copyAtomic(norPath, dest)
        return inspectNor(dest)
    }

    fun importNor(sourcePath: Path, norPath: Path): NorInspect {
        require(Files.isRegularFile(sourcePath)) { "Source NOR flash image not found: $sourcePath" }
        val size = Files.size(sourcePath)
        require(size == NOR_SIZE_BYTES) {
            "Imported NOR flash must be exactly $NOR_SIZE_BYTES bytes (64 MiB), got $size: $sourcePath"
        }
        val dest = norPath.toAbsolutePath().normalize()
        copyAtomic(sourcePath, dest)
        return inspectNor(dest)
    }

    fun restoreFactoryNor(factoryPath: Path, norPath: Path): NorInspect {
        require(Files.isRegularFile(factoryPath, NOFOLLOW_LINKS)) { "Factory NOR flash template not found: $factoryPath" }
        require(Files.size(factoryPath) == NOR_SIZE_BYTES) {
            "Factory NOR flash template size mismatch (${Files.size(factoryPath)} != $NOR_SIZE_BYTES): $factoryPath"
        }
        val dest = norPath.toAbsolutePath().normalize()
        copyAtomic(factoryPath, dest)
        return inspectNor(dest)
    }

    fun inspectNor(norPath: Path): NorInspect {
        require(Files.isRegularFile(norPath, NOFOLLOW_LINKS)) { "NOR flash image not found: $norPath" }
        val size = Files.size(norPath)
        require(size == NOR_SIZE_BYTES) {
            "NOR flash image size mismatch ($size != $NOR_SIZE_BYTES): $norPath"
        }
        val bytes = Files.readAllBytes(norPath)
        val fullSha = sha256(bytes, 0, bytes.size)
        val bootSha = sha256(bytes, BOOT_BIN_OFFSET.toInt(), BOOT_BIN_SIZE.toInt())
        // Stage-1 IPL starts at 0x0800 (non-0xFF A32 code); bank-14 starts at 0x0350_0000.
        var stage1NonFf = false
        for (i in 0x0800 until 0x0840) {
            if (bytes[i] != 0xFF.toByte()) {
                stage1NonFf = true
                break
            }
        }
        var calPopulated = false
        for (i in 0x0350_0000 until 0x0350_1000) {
            if (bytes[i] != 0xFF.toByte() && bytes[i] != 0x00.toByte()) {
                calPopulated = true
                break
            }
        }
        return NorInspect(
            path = norPath.toAbsolutePath().normalize(),
            sizeBytes = size,
            sha256 = fullSha,
            bootBinSha256 = bootSha,
            calibrationPopulated = calPopulated,
            stage1Present = stage1NonFf,
        )
    }

    fun sha256File(path: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(256 * 1024)
        Files.newInputStream(path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return HEX.formatHex(md.digest())
    }

    private fun writeAtomic(target: Path, bytes: ByteArray) {
        val norm = target.toAbsolutePath().normalize()
        norm.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(norm.parent, ".nor-flash-", ".tmp")
        try {
            FileChannel.open(tmp, WRITE, TRUNCATE_EXISTING).use { ch ->
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) {
                    check(ch.write(buf) > 0) { "Short write to $tmp" }
                }
                ch.force(true)
            }
            moveReplace(tmp, norm)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw error
        }
    }

    private fun copyAtomic(source: Path, target: Path) {
        val norm = target.toAbsolutePath().normalize()
        norm.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(norm.parent, ".nor-flash-copy-", ".tmp")
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            moveReplace(tmp, norm)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw error
        }
    }

    private fun moveReplace(src: Path, dst: Path) {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(bytes: ByteArray, offset: Int, length: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, offset, length)
        return HEX.formatHex(md.digest())
    }
}
