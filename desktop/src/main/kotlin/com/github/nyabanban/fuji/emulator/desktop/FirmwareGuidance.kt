package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.LogArchiver
import com.github.nyabanban.fuji.emulator.runtime.NorFlashTool
import org.slf4j.Logger
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Marker interface for exceptions carrying structured [FirmwareGuidance.Issue] diagnostics
 * so CLI entrypoints, headless runners, and the JavaFX desktop UI can render friendly,
 * actionable setup instructions instead of raw stacktraces.
 */
sealed interface FirmwareGuidanceCarrier {
    val issue: FirmwareGuidance.Issue
}

/**
 * Thrown when required firmware or NOR Flash is missing or invalid in contexts whose contract
 * expects an [IllegalStateException] (such as [EmulatorRuntime] boot-plan initialization).
 */
class MissingFirmwareException(
    override val issue: FirmwareGuidance.Issue,
    cause: Throwable? = null,
) : IllegalStateException(issue.cliReport, cause), FirmwareGuidanceCarrier

/**
 * Thrown when required firmware, NOR Flash, or BootROM fails validation or is missing in contexts
 * whose contract expects an [IllegalArgumentException] (such as `SessionBootLoader`).
 */
class InvalidFirmwareArgumentException(
    override val issue: FirmwareGuidance.Issue,
    cause: Throwable? = null,
) : IllegalArgumentException(issue.cliReport, cause), FirmwareGuidanceCarrier

/**
 * Central missing/invalid firmware detector, auto-seeder, and human-friendly guidance formatter
 * shared across CLI, headless mode, and the JavaFX Desktop Studio.
 */
object FirmwareGuidance {
    const val OFFICIAL_DOWNLOAD_URL: String =
        "https://www.fujifilm-x.com/global/support/download/firmware/cameras/gfx100s/"
    const val EXPECTED_FIRMWARE_VERSION: String = "2.13"
    const val EXPECTED_DAT_FILENAME: String = "GXUP0004.DAT"
    const val EXPECTED_GXUP0004_SHA256: String =
        "ea997915ac5accc79699392b47909bb29506f66ec8d571e5804dd1fb0408fd12"
    const val EXPECTED_BOOT_ROM_SHA256: String = FirmwareMetadata.BOOT_ROM_SHA256
    const val EXPECTED_BOOT_ROM_SIZE_BYTES: Long = FirmwareMetadata.BOOT_ROM_SIZE
    const val EXPECTED_NOR_FLASH_SIZE_BYTES: Long = NorFlashTool.NOR_SIZE_BYTES
    const val DEFAULT_LOG_RELATIVE_PATH: String = "logs/${LogArchiver.LATEST_LOG_FILE}"

    private const val MAX_HASH_INSPECT_BYTES: Long = 128L * 1024L * 1024L

    enum class Kind {
        MISSING_FIRMWARE_DAT,
        INVALID_FIRMWARE_DAT,
        MISSING_NOR_FLASH,
        INVALID_NOR_FLASH,
        MISSING_BOOT_ROM,
        INVALID_BOOT_ROM,
    }

    data class Issue(
        val kind: Kind,
        val home: Path,
        val primaryPath: Path,
        val primaryRelativeLabel: String,
        val expectedDatPaths: List<Path>,
        val expectedNorPath: Path,
        val expectedBootRomPath: Path,
        val logFilePath: Path,
        val actualSha256: String?,
        val detailMessage: String,
        val cliReport: String,
    ) {
        fun headlineI18nKey(): String = when (kind) {
            Kind.MISSING_FIRMWARE_DAT -> "firmware.guide.headline.missing_dat"
            Kind.INVALID_FIRMWARE_DAT -> "firmware.guide.headline.invalid_dat"
            Kind.MISSING_NOR_FLASH -> "firmware.guide.headline.missing_nor"
            Kind.INVALID_NOR_FLASH -> "firmware.guide.headline.invalid_nor"
            Kind.MISSING_BOOT_ROM -> "firmware.guide.headline.missing_bootrom"
            Kind.INVALID_BOOT_ROM -> "firmware.guide.headline.invalid_bootrom"
        }
    }

    /**
     * Candidate directories under `home` checked for a user-dropped `GXUP0004.DAT` (case-insensitive)
     * when `flash/nor-flash.bin` and the factory NOR template are both absent.
     */
    fun candidateDatDropPaths(home: Path, config: Map<String, Any?> = emptyMap()): List<Path> {
        val normHome = home.toAbsolutePath().normalize()
        val result = LinkedHashSet<Path>()
        val configuredDat = (config["firmwareFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (configuredDat != null) {
            result.add(resolveAgainstHome(normHome, configuredDat))
        }
        val configuredNor = (config["norFlashFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val norDir = configuredNor?.let { resolveAgainstHome(normHome, it).parent } ?: normHome.resolve("flash")
        if (norDir != null) {
            result.add(norDir.resolve(EXPECTED_DAT_FILENAME).normalize())
        }
        result.add(normHome.resolve("runtime/firmware/$EXPECTED_DAT_FILENAME").normalize())
        result.add(normHome.resolve("firmware/$EXPECTED_DAT_FILENAME").normalize())
        result.add(normHome.resolve("firmware-data/gfx100s/2.13/$EXPECTED_DAT_FILENAME").normalize())
        result.add(normHome.resolve(EXPECTED_DAT_FILENAME).normalize())
        return result.toList()
    }

    /**
     * Locates `GXUP0004.DAT` (matching case-insensitively, e.g. `gxup0004.dat`) in any standard drop folder.
     */
    fun findDroppedDatCandidate(home: Path, config: Map<String, Any?> = emptyMap()): Path? {
        for (candidate in candidateDatDropPaths(home, config)) {
            if (Files.isRegularFile(candidate)) return candidate
            val parent = candidate.parent ?: continue
            if (!Files.isDirectory(parent)) continue
            val targetName = candidate.fileName?.toString() ?: EXPECTED_DAT_FILENAME
            val matched = runCatching {
                Files.list(parent).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && it.fileName.toString().equals(targetName, ignoreCase = true) }
                        .sorted(Comparator.comparing { it.fileName.toString() })
                        .findFirst()
                        .orElse(null)
                }
            }.getOrNull()
            if (matched != null) return matched.toAbsolutePath().normalize()
        }
        return null
    }

    /**
     * If `liveNor` does not exist yet and a user placed `GXUP0004.DAT` into `flash/`, `runtime/firmware/`,
     * `firmware/`, or `<home>/`, validates its SHA-256 (unless `allowCustomFirmware` is enabled) and
     * automatically initializes the 64 MiB `nor-flash.bin` image.
     */
    fun tryAutoSeedNorFromDroppedDat(
        home: Path,
        config: Map<String, Any?>,
        liveNor: Path,
        log: Logger? = null,
    ): NorFlashTool.FlashResult? {
        if (Files.isRegularFile(liveNor)) return null
        val foundDat = findDroppedDatCandidate(home, config) ?: return null
        val mode = CatalogRuntime.mode(config)
        val allowCustom = runCatching { CustomFirmwareConfig.allowCustomFirmware(config, mode) }.getOrDefault(false)
        val actualSha = computeSha256OrNull(foundDat)
        if (!allowCustom && (actualSha == null || !actualSha.equals(EXPECTED_GXUP0004_SHA256, ignoreCase = true))) {
            val detail = "firmware SHA-256 differs for discovered ${foundDat.fileName} " +
                "(actual=${actualSha ?: "unreadable"}, expected=$EXPECTED_GXUP0004_SHA256)"
            throw MissingFirmwareException(
                buildIssue(
                    kind = Kind.INVALID_FIRMWARE_DAT,
                    home = home,
                    config = config,
                    explicitTarget = foundDat,
                    actualSha256 = actualSha,
                    detailMessage = detail,
                ),
            )
        }
        return try {
            val res = NorFlashTool.flashDatIntoNor(foundDat, liveNor, preserveCalibration = true)
            log?.info(
                "[FIRMWARE] Auto-initialized 64 MiB QSPI NOR Flash {} from {} (datSha256={}, norSha256={})",
                liveNor,
                foundDat,
                actualSha ?: "custom",
                res.norSha256,
            )
            res
        } catch (err: Throwable) {
            if (err is FirmwareGuidanceCarrier) throw err
            throw MissingFirmwareException(
                buildIssue(
                    kind = Kind.INVALID_FIRMWARE_DAT,
                    home = home,
                    config = config,
                    explicitTarget = foundDat,
                    actualSha256 = actualSha,
                    detailMessage = err.message ?: "Invalid FUJIFILM .DAT firmware package",
                ),
                err,
            )
        }
    }

    /**
     * Imports and verifies a user-selected `GXUP0004.DAT` from the Desktop GUI:
     * - When `norFlashFile` is configured, flashes into `nor-flash.bin` (via [liveRuntime] if already active,
     *   or offline via [NorFlashTool.flashDatIntoNor] during pre-launch setup).
     * - Otherwise (in `.DAT` mode), validates via [FirmwareImage] and copies atomically to `firmwareFile`.
     */
    fun importDatFileForGui(
        selectedDatPath: Path,
        home: Path,
        config: Map<String, Any?>,
        liveRuntime: EmulatorRuntime? = null,
    ): Path {
        val normSelected = selectedDatPath.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normSelected)) {
            throw InvalidFirmwareArgumentException(
                buildIssue(
                    kind = Kind.MISSING_FIRMWARE_DAT,
                    home = home,
                    config = config,
                    explicitTarget = normSelected,
                    detailMessage = "Selected firmware file does not exist: $normSelected",
                ),
            )
        }
        val mode = CatalogRuntime.mode(config)
        val allowCustom = runCatching { CustomFirmwareConfig.allowCustomFirmware(config, mode) }.getOrDefault(false)
        val actualSha = computeSha256OrNull(normSelected)
        if (!allowCustom && (actualSha == null || !actualSha.equals(EXPECTED_GXUP0004_SHA256, ignoreCase = true))) {
            throw InvalidFirmwareArgumentException(
                buildIssue(
                    kind = Kind.INVALID_FIRMWARE_DAT,
                    home = home,
                    config = config,
                    explicitTarget = normSelected,
                    actualSha256 = actualSha,
                    detailMessage = "firmware SHA-256 differs (actual=${actualSha ?: "unreadable"}, expected=$EXPECTED_GXUP0004_SHA256)",
                ),
            )
        }
        @Suppress("UNCHECKED_CAST")
        val typedConfig = config.entries
            .mapNotNull { (k, v) -> if (v != null) k to v else null }
            .toMap()
        val norPath = NorFlashTool.resolveNorFlashPath(home, typedConfig)
        if (norPath != null) {
            try {
                if (liveRuntime != null) {
                    liveRuntime.flashDatIntoNorLive(normSelected, preserveCalibration = true)
                } else {
                    NorFlashTool.flashDatIntoNor(normSelected, norPath, preserveCalibration = true)
                }
                return norPath
            } catch (err: Throwable) {
                if (err is FirmwareGuidanceCarrier) throw err
                throw InvalidFirmwareArgumentException(
                    buildIssue(
                        kind = Kind.INVALID_FIRMWARE_DAT,
                        home = home,
                        config = config,
                        explicitTarget = normSelected,
                        actualSha256 = actualSha,
                        detailMessage = err.message ?: "Failed to flash .DAT into 64 MiB NOR Flash",
                    ),
                    err,
                )
            }
        }

        val targetDat = (config["firmwareFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(home, it) }
            ?: home.toAbsolutePath().normalize().resolve("runtime/firmware/$EXPECTED_DAT_FILENAME")
        val metaRel = (config["firmwareMetadata"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (metaRel != null) {
            val metaPath = resolveAgainstHome(home, metaRel)
            if (Files.isRegularFile(metaPath)) {
                try {
                    FirmwareImage(FirmwareMetadata(metaPath), normSelected, allowCustomFirmware = allowCustom)
                } catch (err: Throwable) {
                    throw InvalidFirmwareArgumentException(
                        buildIssue(
                            kind = Kind.INVALID_FIRMWARE_DAT,
                            home = home,
                            config = config,
                            explicitTarget = normSelected,
                            actualSha256 = actualSha,
                            detailMessage = err.message ?: "Invalid firmware .DAT image",
                        ),
                        err,
                    )
                }
            }
        }
        if (normSelected != targetDat) {
            targetDat.parent?.let { Files.createDirectories(it) }
            Files.copy(normSelected, targetDat, StandardCopyOption.REPLACE_EXISTING)
        }
        return targetDat
    }

    /**
     * Builds a structured [Issue] with a complete multi-line CLI/log guidance report.
     */
    fun buildIssue(
        kind: Kind,
        home: Path,
        config: Map<String, Any?> = emptyMap(),
        explicitTarget: Path? = null,
        actualSha256: String? = null,
        detailMessage: String = defaultDetailForKind(kind),
    ): Issue {
        val normHome = home.toAbsolutePath().normalize()
        val configuredDat = (config["firmwareFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }
        val expectedNor = (config["norFlashFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }
            ?: normHome.resolve("flash/nor-flash.bin").normalize()
        val expectedBootRom = (config["bootRomFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }
            ?: normHome.resolve("bootrom/boot-rom.bin").normalize()
        val expectedDatList = buildList {
            if (configuredDat != null) add(configuredDat)
            val flashDrop = (expectedNor.parent ?: normHome.resolve("flash")).resolve(EXPECTED_DAT_FILENAME).normalize()
            if (flashDrop !in this) add(flashDrop)
            val runtimeDrop = normHome.resolve("runtime/firmware/$EXPECTED_DAT_FILENAME").normalize()
            if (runtimeDrop !in this) add(runtimeDrop)
        }
        val primary = (explicitTarget ?: when (kind) {
            Kind.MISSING_FIRMWARE_DAT, Kind.INVALID_FIRMWARE_DAT ->
                configuredDat ?: normHome.resolve("runtime/firmware/$EXPECTED_DAT_FILENAME")
            Kind.MISSING_NOR_FLASH, Kind.INVALID_NOR_FLASH -> expectedNor
            Kind.MISSING_BOOT_ROM, Kind.INVALID_BOOT_ROM -> expectedBootRom
        }).toAbsolutePath().normalize()
        val primaryRel = relativizeDisplay(normHome, primary)
        val resolvedActualSha = actualSha256 ?: when (kind) {
            Kind.INVALID_FIRMWARE_DAT, Kind.INVALID_BOOT_ROM -> computeSha256OrNull(primary)
            else -> null
        }
        val logFile = normHome.resolve(DEFAULT_LOG_RELATIVE_PATH).normalize()
        val cliReport = formatCliReport(
            kind = kind,
            home = normHome,
            primaryPath = primary,
            primaryRel = primaryRel,
            expectedDatPaths = expectedDatList,
            expectedNorPath = expectedNor,
            expectedBootRomPath = expectedBootRom,
            logFilePath = logFile,
            actualSha256 = resolvedActualSha,
            detailMessage = detailMessage,
        )
        return Issue(
            kind = kind,
            home = normHome,
            primaryPath = primary,
            primaryRelativeLabel = primaryRel,
            expectedDatPaths = expectedDatList,
            expectedNorPath = expectedNor,
            expectedBootRomPath = expectedBootRom,
            logFilePath = logFile,
            actualSha256 = resolvedActualSha,
            detailMessage = detailMessage,
            cliReport = cliReport,
        )
    }

    /**
     * Extracts an existing [Issue] if [error] or any cause in its chain implements [FirmwareGuidanceCarrier].
     */
    fun extractIssue(error: Throwable?): Issue? {
        var cur = error
        var depth = 0
        while (cur != null && depth < 16) {
            if (cur is FirmwareGuidanceCarrier) return cur.issue
            val next = cur.cause
            if (next === cur) break
            cur = next
            depth++
        }
        return null
    }

    /**
     * Inspects an arbitrary [Throwable] thrown during CLI execution, runtime setup, or session boot
     * and returns a structured [Issue] when the failure is caused by a missing or invalid firmware
     * `.DAT`, `nor-flash.bin`, or `boot-rom.bin` artifact; returns `null` for unrelated errors.
     */
    fun diagnose(
        error: Throwable,
        home: Path,
        config: Map<String, Any?> = emptyMap(),
        explicitTarget: Path? = null,
    ): Issue? {
        extractIssue(error)?.let { return it }

        val normHome = home.toAbsolutePath().normalize()
        val configuredDat = (config["firmwareFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }
        val configuredNor = (config["norFlashFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }
        val configuredBootRom = (config["bootRomFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { resolveAgainstHome(normHome, it) }

        var cur: Throwable? = error
        var depth = 0
        while (cur != null && depth < 16) {
            if (cur !is CompletionException && cur !is ExecutionException) {
                val matched = classifySingleThrowable(
                    throwable = cur,
                    home = normHome,
                    config = config,
                    configuredDat = configuredDat,
                    configuredNor = configuredNor,
                    configuredBootRom = configuredBootRom,
                    explicitTarget = explicitTarget,
                )
                if (matched != null) return matched
            }
            val next = cur.cause
            if (next === cur) break
            cur = next
            depth++
        }
        return null
    }

    private fun classifySingleThrowable(
        throwable: Throwable,
        home: Path,
        config: Map<String, Any?>,
        configuredDat: Path?,
        configuredNor: Path?,
        configuredBootRom: Path?,
        explicitTarget: Path?,
    ): Issue? {
        val msg = throwable.message?.trim().orEmpty()
        if (throwable is NoSuchFileException || throwable is FileNotFoundException) {
            val rawFile = (throwable as? NoSuchFileException)?.file ?: msg.substringBefore(" (")
            val missingPath = rawFile.takeIf { it.isNotBlank() }?.let { runCatching { resolveAgainstHome(home, it) }.getOrNull() }
                ?: explicitTarget
            val lowerName = missingPath?.fileName?.toString()?.lowercase(Locale.ROOT).orEmpty()
            return when {
                missingPath != null && (missingPath == configuredBootRom || lowerName.contains("boot-rom") || lowerName.contains("bootrom")) ->
                    buildIssue(Kind.MISSING_BOOT_ROM, home, config, missingPath, detailMessage = "BootROM file not found: $missingPath")
                missingPath != null && (missingPath == configuredNor || lowerName == "nor-flash.bin" || lowerName.startsWith("nor-flash")) ->
                    buildIssue(Kind.MISSING_NOR_FLASH, home, config, missingPath, detailMessage = "64 MiB NOR Flash image not found: $missingPath")
                missingPath != null && (missingPath == configuredDat || missingPath == explicitTarget || lowerName.endsWith(".dat")) ->
                    buildIssue(Kind.MISSING_FIRMWARE_DAT, home, config, missingPath, detailMessage = "Firmware .DAT file not found: $missingPath")
                else -> null
            }
        }

        if (throwable is IllegalArgumentException) {
            return when {
                msg == "firmwareFile or norFlashFile must be selected" ->
                    buildIssue(Kind.MISSING_FIRMWARE_DAT, home, config, explicitTarget ?: configuredDat, detailMessage = msg)
                msg.startsWith("Firmware DAT file not found") || msg == "custom firmware DAT file missing" ->
                    buildIssue(Kind.MISSING_FIRMWARE_DAT, home, config, explicitTarget ?: configuredDat, detailMessage = msg)
                msg == "firmware size differs" ||
                    msg == "firmware SHA-256 differs" ||
                    msg.startsWith("decoded section differs") ||
                    msg == "DAT extent out of bounds" ||
                    msg.startsWith("Firmware DAT file too small") ||
                    msg.startsWith("Unsupported DAT header_version") ||
                    msg.startsWith("DAT seg0 length out of bounds") ||
                    msg.startsWith("DAT seg0 checksum mismatch") ||
                    msg.startsWith("custom firmware DAT ") -> {
                    val target = explicitTarget ?: configuredDat
                    buildIssue(
                        kind = Kind.INVALID_FIRMWARE_DAT,
                        home = home,
                        config = config,
                        explicitTarget = target,
                        actualSha256 = target?.let(::computeSha256OrNull),
                        detailMessage = msg,
                    )
                }
                msg.startsWith("NOR flash image not found") ||
                    msg.startsWith("Source NOR flash image not found") ||
                    msg.startsWith("Factory NOR flash template not found") ||
                    msg.startsWith("Direct-from-flash boot requires flash.image") ->
                    buildIssue(Kind.MISSING_NOR_FLASH, home, config, explicitTarget ?: configuredNor, detailMessage = msg)
                msg.startsWith("NOR flash image size mismatch") ||
                    msg.startsWith("NOR flash size mismatch") ||
                    msg.startsWith("Existing NOR flash size mismatch") ||
                    msg.startsWith("Factory NOR flash template size mismatch") ||
                    msg.startsWith("Imported NOR flash must be") ||
                    msg.startsWith("Incomplete boot.bin read from NOR Flash") ->
                    buildIssue(Kind.INVALID_NOR_FLASH, home, config, explicitTarget ?: configuredNor, detailMessage = msg)
                msg == "bootRom file missing" ->
                    buildIssue(Kind.MISSING_BOOT_ROM, home, config, explicitTarget ?: configuredBootRom, detailMessage = msg)
                msg == "bootRom size differs" || msg == "bootRom SHA-256 differs" -> {
                    val target = explicitTarget ?: configuredBootRom
                    buildIssue(
                        kind = Kind.INVALID_BOOT_ROM,
                        home = home,
                        config = config,
                        explicitTarget = target,
                        actualSha256 = target?.let(::computeSha256OrNull),
                        detailMessage = msg,
                    )
                }
                else -> null
            }
        }

        if (throwable is IllegalStateException) {
            if (msg.contains("Missing 64 MiB QSPI NOR Flash image")) {
                return buildIssue(Kind.MISSING_NOR_FLASH, home, config, explicitTarget ?: configuredNor, detailMessage = msg)
            }
        }
        return null
    }

    internal fun computeSha256OrNull(path: Path): String? = runCatching {
        if (!Files.isRegularFile(path)) return null
        val size = Files.size(path)
        if (size < 0L || size > MAX_HASH_INSPECT_BYTES) return null
        NorFlashTool.sha256File(path)
    }.getOrNull()

    internal fun relativizeDisplay(home: Path, target: Path): String {
        val normHome = home.toAbsolutePath().normalize()
        val normTarget = target.toAbsolutePath().normalize()
        return if (normTarget.startsWith(normHome) && normTarget != normHome) {
            normHome.relativize(normTarget).toString().replace('\\', '/')
        } else {
            normTarget.toString().replace('\\', '/')
        }
    }

    private fun resolveAgainstHome(home: Path, raw: String): Path {
        val p = Path.of(raw)
        return (if (p.isAbsolute) p else home.resolve(p)).toAbsolutePath().normalize()
    }

    private fun defaultDetailForKind(kind: Kind): String = when (kind) {
        Kind.MISSING_FIRMWARE_DAT -> "Missing FUJIFILM GFX100S firmware package ($EXPECTED_DAT_FILENAME)"
        Kind.INVALID_FIRMWARE_DAT -> "firmware SHA-256 or DAT header verification failed for $EXPECTED_DAT_FILENAME"
        Kind.MISSING_NOR_FLASH -> "Missing 64 MiB QSPI NOR Flash image (flash/nor-flash.bin)"
        Kind.INVALID_NOR_FLASH -> "Invalid or incomplete 64 MiB QSPI NOR Flash image (expected 67,108,864 bytes)"
        Kind.MISSING_BOOT_ROM -> "Missing requested 32 KiB SPX4 mask BootROM image (boot-rom.bin)"
        Kind.INVALID_BOOT_ROM -> "Invalid 32 KiB SPX4 mask BootROM image (size or SHA-256 verification failed)"
    }

    private fun headlineText(kind: Kind): String = when (kind) {
        Kind.MISSING_FIRMWARE_DAT -> "Missing Firmware Package (GXUP0004.DAT)"
        Kind.INVALID_FIRMWARE_DAT -> "Firmware Verification Failed (GXUP0004.DAT)"
        Kind.MISSING_NOR_FLASH -> "Missing 64 MiB NOR Flash Image (nor-flash.bin)"
        Kind.INVALID_NOR_FLASH -> "Invalid 64 MiB NOR Flash Image (nor-flash.bin)"
        Kind.MISSING_BOOT_ROM -> "Missing 32 KiB Mask BootROM Image (boot-rom.bin)"
        Kind.INVALID_BOOT_ROM -> "BootROM Verification Failed (boot-rom.bin)"
    }

    private fun formatCliReport(
        kind: Kind,
        home: Path,
        primaryPath: Path,
        primaryRel: String,
        expectedDatPaths: List<Path>,
        expectedNorPath: Path,
        expectedBootRomPath: Path,
        logFilePath: Path,
        actualSha256: String?,
        detailMessage: String,
    ): String {
        val datRelSummary = expectedDatPaths.joinToString(" | ") { "${relativizeDisplay(home, it)} ($it)" }
        val norRel = relativizeDisplay(home, expectedNorPath)
        val bootRomRel = relativizeDisplay(home, expectedBootRomPath)
        val altBootRom = home.resolve("runtime/firmware/boot-rom.bin").normalize()
        return buildString {
            appendLine("================================================================================")
            appendLine("FUJIFILM GFX100S Emulator — Firmware Setup Required: ${headlineText(kind)}")
            appendLine("================================================================================")
            appendLine("Reason        : $detailMessage")
            appendLine("Missing/Target: $primaryRel ($primaryPath)")
            appendLine("Expected .DAT : $EXPECTED_DAT_FILENAME (FUJIFILM GFX100S Firmware Ver. $EXPECTED_FIRMWARE_VERSION)")
            appendLine("Expected SHA256: $EXPECTED_GXUP0004_SHA256")
            if (actualSha256 != null) {
                appendLine("Actual SHA256 : $actualSha256")
            }
            if (kind == Kind.MISSING_BOOT_ROM || kind == Kind.INVALID_BOOT_ROM) {
                appendLine("BootROM SHA256: $EXPECTED_BOOT_ROM_SHA256 ($EXPECTED_BOOT_ROM_SIZE_BYTES bytes)")
            }
            appendLine()
            appendLine("Proprietary FUJIFILM camera firmware is not bundled with public/source distributions.")
            appendLine("How to set up official FUJIFILM GFX100S Firmware Ver. $EXPECTED_FIRMWARE_VERSION:")
            appendLine()
            appendLine("  1. Download Official Firmware Ver. $EXPECTED_FIRMWARE_VERSION ($EXPECTED_DAT_FILENAME):")
            appendLine("     $OFFICIAL_DOWNLOAD_URL")
            appendLine("     Verify SHA-256 == $EXPECTED_GXUP0004_SHA256")
            appendLine()
            appendLine("  2. Place or Flash Firmware into Expected Paths:")
            appendLine("     - Drop-in firmware paths : $datRelSummary")
            appendLine("     - 64 MiB QSPI NOR Flash  : $norRel ($expectedNorPath — see flash/PUT_HERE)")
            appendLine("     - CLI Flash / Override   : --flash-dat <path/to/$EXPECTED_DAT_FILENAME>")
            appendLine("                                (or --firmware <path/to/$EXPECTED_DAT_FILENAME> in .DAT mode;")
            appendLine("                                 pass --allow-custom-firmware for custom/patched .DAT images)")
            appendLine()
            appendLine("  3. Optional Real-Camera Dumps (boot-rom.bin / nor-flash.bin):")
            appendLine("     - Optional 32 KiB BootROM: $bootRomRel ($expectedBootRomPath) or runtime/firmware/boot-rom.bin ($altBootRom)")
            appendLine("       (Default Flash-Direct boot runs without boot-rom.bin; pass --boot-rom to opt in.)")
            appendLine("     - If importing a real-camera 64 MiB nor-flash.bin dump, run in-camera factory reset")
            appendLine("       (MENU -> SET UP -> USER SETTING -> RESET -> INITIALIZE) first, and NEVER share")
            appendLine("       personal bank-14 [0x03500000..0x03D40000) serial/MAC/calibration dumps publicly.")
            appendLine()
            appendLine("  4. Logs & Issue Reports:")
            appendLine("     Diagnostic log file: $DEFAULT_LOG_RELATIVE_PATH ($logFilePath)")
            append("================================================================================")
        }
    }
}
