package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.session.SessionBootLoader
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.FujiLogCapture
import com.github.nyabanban.fuji.emulator.runtime.FujiLogLevel
import com.github.nyabanban.fuji.emulator.runtime.NorFlashTool
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import javafx.application.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareGuidanceTest {
    private val repoRoot: Path = Path.of(System.getProperty("componentProjectRoot")).toAbsolutePath().normalize()

    @BeforeEach
    fun resetLocaleBefore() {
        I18n.onLocaleChanged = null
        I18n.resetToDefault()
    }

    @AfterEach
    fun resetLocaleAfter() {
        I18n.onLocaleChanged = null
        I18n.resetToDefault()
    }

    private fun buildSyntheticType6Dat(payload: ByteArray): ByteArray {
        val seg0Len = payload.size
        val out = ByteArray(FirmwareImage.DAT_HEADER_SIZE + seg0Len)
        for (i in 0 until seg0Len) {
            out[FirmwareImage.DAT_HEADER_SIZE + i] = (payload[i].toInt() xor 0xFF).toByte()
        }
        val checksum = FirmwareImage.computeSeg0Checksum(out, seg0Len).toInt()
        ByteBuffer.wrap(out, 0, FirmwareImage.DAT_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(FirmwareImage.DAT_HEADER_VERSION_OFFSET, FirmwareImage.DAT_HEADER_VERSION_EXPECTED)
            putInt(FirmwareImage.DAT_SEG0_CHECKSUM_OFFSET, checksum)
            putInt(FirmwareImage.DAT_SEG0_LENGTH_OFFSET, seg0Len)
        }
        return out
    }

    private fun assertCompleteCliGuidance(report: String, expectedRelativePath: String) {
        assertTrue(report.contains(expectedRelativePath), "Expected '$expectedRelativePath' in guidance:\n$report")
        assertTrue(
            report.contains(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL),
            "Expected official Fujifilm download URL in guidance:\n$report",
        )
        assertTrue(
            report.contains(FirmwareGuidance.EXPECTED_GXUP0004_SHA256),
            "Expected exact GXUP0004.DAT SHA-256 in guidance:\n$report",
        )
        assertTrue(
            report.contains("--flash-dat <path/to/GXUP0004.DAT>"),
            "Expected --flash-dat CLI tip in guidance:\n$report",
        )
        assertTrue(
            report.contains("flash/PUT_HERE"),
            "Expected flash/PUT_HERE reference in guidance:\n$report",
        )
        assertTrue(
            report.contains("INITIALIZE") && report.contains("bank-14"),
            "Expected factory-reset real-camera dump privacy note in guidance:\n$report",
        )
        assertTrue(
            report.contains("logs/latest.log"),
            "Expected logs/latest.log location in guidance:\n$report",
        )
        val urlsInReport = Regex("https?://\\S+").findAll(report).map { it.value }.toList()
        assertEquals(
            listOf(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL),
            urlsInReport.distinct(),
            "Guidance must reference only the official Fujifilm support URL:\n$report",
        )
    }

    @Test
    fun missingAndInvalidGxup0004DatProduceRichGuidanceWithExpectedAndActualSha256(@TempDir tempHome: Path) {
        val metaPath = repoRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml")
        val meta = FirmwareMetadata(metaPath)
        val log = LoggerFactory.getLogger(FirmwareGuidanceTest::class.java)
        val config = mapOf<String, Any>(
            "firmwareMetadata" to metaPath.toString(),
            "firmwareFile" to "runtime/firmware/GXUP0004.DAT",
        )

        // 1. Missing GXUP0004.DAT in SessionBootLoader.loadImages
        val missingErr = assertFailsWith<InvalidFirmwareArgumentException> {
            SessionBootLoader.loadImages(
                home = tempHome,
                config = config,
                meta = meta,
                isBootRom = false,
                bootRomBytes = null,
                allowCustom = false,
                rawImageSpec = null,
                log = log,
            )
        }
        assertEquals(FirmwareGuidance.Kind.MISSING_FIRMWARE_DAT, missingErr.issue.kind)
        assertEquals(
            tempHome.resolve("runtime/firmware/GXUP0004.DAT").toAbsolutePath().normalize(),
            missingErr.issue.primaryPath,
        )
        assertNull(missingErr.issue.actualSha256)
        assertCompleteCliGuidance(missingErr.message.orEmpty(), "runtime/firmware/GXUP0004.DAT")

        // 2. Corrupt / wrong-hash GXUP0004.DAT in SessionBootLoader.loadImages
        val corruptDat = tempHome.resolve("runtime/firmware/GXUP0004.DAT")
        Files.createDirectories(corruptDat.parent)
        val wrongBytes = "not-a-valid-gxup0004-dat-payload".toByteArray(StandardCharsets.UTF_8)
        Files.write(corruptDat, wrongBytes)
        val expectedWrongSha = NorFlashTool.sha256File(corruptDat)

        val invalidErr = assertFailsWith<InvalidFirmwareArgumentException> {
            SessionBootLoader.loadImages(
                home = tempHome,
                config = config,
                meta = meta,
                isBootRom = false,
                bootRomBytes = null,
                allowCustom = false,
                rawImageSpec = null,
                log = log,
            )
        }
        assertEquals(FirmwareGuidance.Kind.INVALID_FIRMWARE_DAT, invalidErr.issue.kind)
        assertEquals(expectedWrongSha, invalidErr.issue.actualSha256)
        val invalidReport = invalidErr.message.orEmpty()
        assertCompleteCliGuidance(invalidReport, "runtime/firmware/GXUP0004.DAT")
        assertTrue(invalidReport.contains(expectedWrongSha), "Expected actual SHA-256 in report:\n$invalidReport")
        assertTrue(invalidReport.contains("firmware"), "Expected 'firmware' keyword for compatibility:\n$invalidReport")
    }

    @Test
    fun missingAndInvalidBootRomProduceRichGuidanceInBootRomExperimentMode(@TempDir tempHome: Path) {
        val bootRomMetaPath = repoRoot.resolve("configurations/jvm/firmware/gfx100s-213-bootrom.yaml")
        val catalogRuntime = CatalogRuntime(AddressCatalog.bundled(), RelaxedPeripheralFallback.MODE, tempHome, emptyMap())
        val config = mapOf<String, Any>(
            "executionMode" to RelaxedPeripheralFallback.MODE,
            "firmwareMetadata" to bootRomMetaPath.toString(),
            "firmwareFile" to "runtime/firmware/GXUP0004.DAT",
            "bootRomFile" to "runtime/firmware/boot-rom.bin",
        )

        // 1. Missing runtime/firmware/boot-rom.bin
        val missingBootRom = assertFailsWith<InvalidFirmwareArgumentException> {
            SessionBootLoader.loadMetadataAndBootRom(
                home = tempHome,
                config = config,
                catalogRuntime = catalogRuntime,
                rawImageSpec = null,
            )
        }
        assertEquals(FirmwareGuidance.Kind.MISSING_BOOT_ROM, missingBootRom.issue.kind)
        val missingReport = missingBootRom.message.orEmpty()
        assertCompleteCliGuidance(missingReport, "runtime/firmware/boot-rom.bin")
        assertTrue(
            missingReport.contains(FirmwareGuidance.EXPECTED_BOOT_ROM_SHA256),
            "Expected mask BootROM SHA-256 in BootROM guidance:\n$missingReport",
        )

        // 2. Wrong SHA-256 32 KiB runtime/firmware/boot-rom.bin
        val bootRomFile = tempHome.resolve("runtime/firmware/boot-rom.bin")
        Files.createDirectories(bootRomFile.parent)
        Files.write(bootRomFile, ByteArray(32_768) { 0xA5.toByte() })
        val actualBootRomSha = NorFlashTool.sha256File(bootRomFile)

        val invalidBootRom = assertFailsWith<InvalidFirmwareArgumentException> {
            SessionBootLoader.loadMetadataAndBootRom(
                home = tempHome,
                config = config,
                catalogRuntime = catalogRuntime,
                rawImageSpec = null,
            )
        }
        assertEquals(FirmwareGuidance.Kind.INVALID_BOOT_ROM, invalidBootRom.issue.kind)
        assertEquals(actualBootRomSha, invalidBootRom.issue.actualSha256)
        val invalidReport = invalidBootRom.message.orEmpty()
        assertCompleteCliGuidance(invalidReport, "runtime/firmware/boot-rom.bin")
        assertTrue(invalidReport.contains(actualBootRomSha), "Expected actual BootROM SHA-256 in report:\n$invalidReport")
    }

    @Test
    fun missingNorFlashGuidanceAutoSeedAndCliExitCleanlyWithoutStdoutPollution(@TempDir tempHome: Path) {
        val releaseConfig = Formats.read(repoRoot.resolve("tools/release/emulator-release-linux.yaml"))
        val cfgPath = tempHome.resolve("configs/emulator.yaml")
        Files.createDirectories(cfgPath.parent)
        val minimalNorConfig = LinkedHashMap<String, Any>(releaseConfig).apply {
            put("firmwareMetadata", repoRoot.resolve("tools/release/emulator-release-linux.yaml").let {
                repoRoot.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString()
            })
        }
        Formats.atomicYaml(cfgPath, minimalNorConfig)

        // 1. CLI --inspect-flash with missing flash/nor-flash.bin -> exitCode 1, clean stderr guidance, empty stdout
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val prevOut = System.out
        val prevErr = System.err
        val exitCode = try {
            System.setOut(PrintStream(outBuf, true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(errBuf, true, StandardCharsets.UTF_8))
            executeMain(
                arrayOf("--home", tempHome.toString(), "--config", cfgPath.toString(), "--inspect-flash"),
                allowInteractiveGuiPrompt = false,
            )
        } finally {
            System.setOut(prevOut)
            System.setErr(prevErr)
        }
        assertEquals(1, exitCode, "Missing NOR flash on CLI must return exit code 1")
        assertEquals("", outBuf.toString(StandardCharsets.UTF_8).trim(), "JSON CLI stdout must remain empty on firmware error")
        val stderrText = errBuf.toString(StandardCharsets.UTF_8)
        assertCompleteCliGuidance(stderrText, "flash/nor-flash.bin")

        // 2. Auto-seed rejects unverified dropped GXUP0004.DAT when allowCustomFirmware=false
        val droppedLowercaseDat = tempHome.resolve("flash/gxup0004.dat")
        Files.createDirectories(droppedLowercaseDat.parent)
        val syntheticDat = buildSyntheticType6Dat(ByteArray(4096) { (it and 0xFF).toByte() })
        Files.write(droppedLowercaseDat, syntheticDat)
        val syntheticSha = NorFlashTool.sha256File(droppedLowercaseDat)
        assertEquals(droppedLowercaseDat.toAbsolutePath().normalize(), FirmwareGuidance.findDroppedDatCandidate(tempHome, minimalNorConfig))

        val liveNor = tempHome.resolve("flash/nor-flash.bin")
        val rejectedAutoSeed = assertFailsWith<MissingFirmwareException> {
            FirmwareGuidance.tryAutoSeedNorFromDroppedDat(tempHome, minimalNorConfig, liveNor)
        }
        assertEquals(FirmwareGuidance.Kind.INVALID_FIRMWARE_DAT, rejectedAutoSeed.issue.kind)
        assertEquals(syntheticSha, rejectedAutoSeed.issue.actualSha256)
        assertFalse(Files.exists(liveNor), "Unverified .DAT must not overwrite/create flash/nor-flash.bin without allowCustomFirmware")

        // 3. With allowCustomFirmware=true, dropped gxup0004.dat auto-seeds 64 MiB flash/nor-flash.bin cleanly
        val customAllowedConfig = minimalNorConfig + (CustomFirmwareConfig.ALLOW_CUSTOM_KEY to true)
        val flashRes = assertNotNull(
            FirmwareGuidance.tryAutoSeedNorFromDroppedDat(tempHome, customAllowedConfig, liveNor),
            "Expected auto-seed to flash dropped gxup0004.dat when allowCustomFirmware=true",
        )
        assertTrue(Files.isRegularFile(liveNor))
        assertEquals(NorFlashTool.NOR_SIZE_BYTES, Files.size(liveNor))
        assertEquals(4096L, flashRes.seg0Length)

        // 4. Unrelated exceptions must NOT be misclassified as missing firmware
        assertNull(FirmwareGuidance.diagnose(IllegalArgumentException("profiler requires boolean or map"), tempHome, minimalNorConfig))
        assertNull(FirmwareGuidance.diagnose(IllegalStateException("soc-gpio.inputs port unavailable"), tempHome, minimalNorConfig))
    }

    @Test
    fun allSixLocalesProvideCompleteFirmwareGuidanceKeysWithoutFallbackWarnings(@TempDir tempHome: Path) {
        val expectedKeys = listOf(
            "firmware.guide.title",
            "firmware.guide.headline.missing_dat",
            "firmware.guide.headline.invalid_dat",
            "firmware.guide.headline.missing_nor",
            "firmware.guide.headline.invalid_nor",
            "firmware.guide.headline.missing_bootrom",
            "firmware.guide.headline.invalid_bootrom",
            "firmware.guide.intro",
            "firmware.guide.steps",
            "firmware.guide.url.label",
            "firmware.guide.action.copy_url",
            "firmware.guide.action.open_url",
            "firmware.guide.action.select_dat",
            "firmware.guide.action.close",
            "firmware.guide.chooser.title",
            "firmware.guide.details.label",
            "firmware.guide.row.target",
            "firmware.guide.row.detail",
            "firmware.guide.row.expected_sha256",
            "firmware.guide.row.actual_sha256",
            "firmware.guide.row.expected_dat",
            "firmware.guide.row.expected_nor",
            "firmware.guide.row.expected_bootrom",
            "firmware.guide.row.cli_tip",
            "firmware.guide.row.log_file",
            "firmware.guide.row.dump_note",
            "firmware.guide.feedback.url_copied",
            "firmware.guide.feedback.url_opened",
            "firmware.guide.feedback.url_open_manual",
            "firmware.guide.feedback.importing",
            "firmware.guide.feedback.import_failed",
            "firmware.guide.status.imported",
            "firmware.guide.status.missing_short",
        )

        val issue = FirmwareGuidance.buildIssue(
            kind = FirmwareGuidance.Kind.INVALID_FIRMWARE_DAT,
            home = tempHome,
            config = mapOf(
                "firmwareFile" to "runtime/firmware/GXUP0004.DAT",
                "norFlashFile" to "flash/nor-flash.bin",
                "bootRomFile" to "bootrom/boot-rom.bin",
            ),
            actualSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            detailMessage = "firmware SHA-256 differs",
        )

        val titlesByLocale = linkedMapOf<String, String>()
        val capturedLogs = FujiLogCapture.capture(I18n::class.java) {
            for (code in I18n.supportedLanguageCodes()) {
                I18n.setLocale(code)
                assertEquals(code, I18n.localeCode())
                for (key in expectedKeys) {
                    val raw = assertNotNull(I18n.rawPattern(code, key), "Missing key '$key' in locale '$code'")
                    assertTrue(raw.isNotBlank(), "Blank translation for '$key' in locale '$code'")
                }
                titlesByLocale[code] = I18n.t("firmware.guide.title")
                val details = FirmwareGuidanceDialog.formatLocalizedDetails(issue)
                assertTrue(details.contains(FirmwareGuidance.EXPECTED_GXUP0004_SHA256), "Missing expected SHA-256 in $code details:\n$details")
                assertTrue(details.contains("0123456789abcdef"), "Missing actual SHA-256 in $code details:\n$details")
                assertTrue(details.contains("runtime/firmware/GXUP0004.DAT"), "Missing .DAT path in $code details:\n$details")
                assertTrue(details.contains("flash/nor-flash.bin"), "Missing NOR path in $code details:\n$details")
                assertTrue(details.contains("logs/latest.log"), "Missing log path in $code details:\n$details")
            }
        }

        assertEquals(6, titlesByLocale.size)
        assertNotEquals(titlesByLocale[I18n.CODE_EN], titlesByLocale[I18n.CODE_ZH_CN])
        assertNotEquals(titlesByLocale[I18n.CODE_EN], titlesByLocale[I18n.CODE_JA_JP])
        assertTrue(
            capturedLogs.none { it.level == FujiLogLevel.WARN && it.formattedMessage.contains("[FALLBACK][i18n]") },
            "Expected zero i18n fallback warnings across all 6 languages, got: $capturedLogs",
        )
    }

    @Test
    fun firmwareGuidanceDialogRendersAndSwitchesLiveAcrossAllSixLocales(@TempDir tempHome: Path) {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires JavaFX graphical toolkit")
        val issue = FirmwareGuidance.buildIssue(
            kind = FirmwareGuidance.Kind.MISSING_NOR_FLASH,
            home = tempHome,
            config = mapOf("norFlashFile" to "flash/nor-flash.bin"),
        )

        val task = FutureTask {
            Platform.setImplicitExit(false)
            I18n.resetToDefault()
            var closed = false
            val built = FirmwareGuidanceDialog.buildContent(
                initialIssue = issue,
                stageForDialogs = null,
                onImportDat = { p -> p },
                onCloseRequested = { closed = true },
            )
            try {
                assertEquals(FirmwareGuidance.OFFICIAL_DOWNLOAD_URL, built.urlField.text)
                assertTrue(built.selectDatButton.isVisible, "Select GXUP0004.DAT button must be visible when importer is provided")
                for (code in I18n.supportedLanguageCodes()) {
                    I18n.setLocale(code)
                    assertEquals(I18n.t("firmware.guide.headline.missing_nor"), built.headlineLabel.text)
                    assertEquals(I18n.t("firmware.guide.action.select_dat"), built.selectDatButton.text)
                    assertTrue(built.detailsArea.text.contains(FirmwareGuidance.EXPECTED_GXUP0004_SHA256))
                }
                built.copyUrlButton.fire()
                assertEquals(I18n.t("firmware.guide.feedback.url_copied"), built.feedbackLabel.text)
                built.closeButton.fire()
                assertTrue(closed, "Close button must invoke onCloseRequested")
            } finally {
                built.dispose()
                I18n.resetToDefault()
            }
            "ok"
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        assertEquals("ok", task.get(30, TimeUnit.SECONDS))
    }
}
