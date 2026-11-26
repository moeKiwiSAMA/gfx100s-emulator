package com.github.nyabanban.fuji.emulator.runtime

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserActionLoggerTest {
    @Test
    fun logsButtonsSwitchesEyeSensorSettingsAndPauseInRelaxedMode() {
        val telemetry = FirmwareTelemetry().apply {
            markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
            virtualTick = 123_456L
            publishedFrames = 7L
        }
        val lines = mutableListOf<String>()
        val logger = UserActionLogger(enabled = true, infoSink = lines::add)

        logger.logComponentPatch("buttons", mapOf("menu" to true), telemetry)
        logger.logComponentPatch("buttons", linkedMapOf("up" to false, "down" to false), telemetry)
        logger.logComponentPatch("buttons", mapOf("power" to true), telemetry)
        logger.logComponentPatch("i2c-bus", mapOf("eyeSensorNear" to true), telemetry)
        logger.logComponentPatch("sdcard-reader", mapOf("slot1Inserted" to true), telemetry)
        logger.logPauseState(true, telemetry)
        logger.logPauseState(false, telemetry)

        assertEquals(
            listOf(
                "[USER/ACTION] button=menu pressed=true stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] buttons=up=false,down=false stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] button=power pressed=true stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] eyeSensorNear=true stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] component=sdcard-reader changes=slot1Inserted=true stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] action=pause stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
                "[USER/ACTION] action=resume stage=STAGE_5_FIRST_FRAME tick=123456 frame=7",
            ),
            lines,
        )
    }

    @Test
    fun emitsNothingWhenDisabledInStrictOrDiscoveryMode() {
        val telemetry = FirmwareTelemetry().apply {
            markStage(FirmwareStage.STAGE_1_BOOT_INIT)
            virtualTick = 500L
        }
        val lines = mutableListOf<String>()
        val logger = UserActionLogger(enabled = false, infoSink = lines::add)

        logger.logComponentPatch("buttons", mapOf("menu" to true), telemetry)
        logger.logComponentPatch("i2c-bus", mapOf("eyeSensorNear" to true), telemetry)
        logger.logPauseState(true, telemetry)
        logger.logPauseState(false, telemetry)

        assertTrue(lines.isEmpty(), "Expected no [USER/ACTION] logs when disabled, got: $lines")
    }

    @Test
    fun emitsUserActionsAtDebugSoFileCapturesThemWhileDefaultInfoConsoleStaysQuiet(@TempDir logDir: Path) {
        val console = ByteArrayOutputStream()
        val captured = PrintStream(console, true, Charsets.UTF_8)
        val logContext = FujiLoggerContext(
            propertyOverrides = mapOf(LogArchiver.PROP_LOG_DIR to logDir.toString()),
            consoleStream = captured,
        )
        try {
            val telemetry = FirmwareTelemetry().apply {
                markStage(FirmwareStage.STAGE_6_INTERACTIVE_UI)
                virtualTick = 42_000L
                publishedFrames = 12L
            }
            val userLogger = UserActionLogger(
                enabled = true,
                logger = logContext.getLogger(UserActionLogger::class.java),
            )
            userLogger.logComponentPatch("buttons", mapOf("menu" to true), telemetry)
            logContext.stop()

            val consoleOut = console.toString(Charsets.UTF_8)
            val fileOut = Files.readString(logDir.resolve("latest.log"), Charsets.UTF_8)
            assertFalse(consoleOut.contains("[USER/ACTION]"), "Console at INFO must not print DEBUG user actions: $consoleOut")
            assertTrue(fileOut.contains("DEBUG") && fileOut.contains("[USER/ACTION] button=menu pressed=true"), fileOut)
        } finally {
            logContext.stop()
            captured.close()
        }
    }
}
