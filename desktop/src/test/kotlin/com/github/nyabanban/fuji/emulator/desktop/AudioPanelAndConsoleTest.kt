package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioBackendPreference
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioDeviceCatalogSnapshot
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioDeviceEntry
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioInputMode
import com.github.nyabanban.fuji.emulator.desktop.panels.AudioIoStatusSnapshot
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioPanelAndConsoleTest {

    private class FakeAudioConsoleBackend(
        override val home: Path,
        override val telemetry: FirmwareTelemetry = FirmwareTelemetry(),
        override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry),
    ) : ConsoleBackend {
        private val paused = AtomicBoolean(false)
        val audioPatches = mutableListOf<Map<String, Any?>>()
        val currentStatus: MutableMap<String, Any?> = linkedMapOf(
            "enabled" to true,
            "muted" to false,
            "volume" to 0.8,
            "bufferMs" to 20,
            "sampleRateHz" to 48_000,
            "backendPreference" to "AUTO",
            "activeBackend" to "NATIVE_PIPEWIRE",
            "actualLatencyMs" to 18,
            "outputDeviceId" to "",
            "outputDeviceName" to "Built-in Analog Stereo",
            "outputPeakLeft" to 0.5,
            "outputPeakRight" to 0.25,
            "headphonePlugged" to false,
            "extMicPlugged" to false,
            "inputMode" to "DISABLED",
            "inputBackendMode" to "IDLE",
            "inputDeviceId" to "",
            "inputDeviceName" to "Default Input",
            "inputGainDb" to 0.0,
            "inputMuted" to false,
            "inputMonitor" to false,
            "inputPeakLeft" to 0.0,
            "inputPeakRight" to 0.0,
            "inputFramesCaptured" to 0L,
            "inputOverruns" to 0L,
            "inputUnderruns" to 0L,
            "codecRoute" to mapOf("activeSink" to "SPEAKER"),
            "i2sStats" to mapOf("totalTxFrames" to 12_345L),
            "rxStats" to mapOf(
                "totalRxFrames" to 2400L,
                "queuedRxFrames" to 128L,
                "rxOverruns" to 1L,
                "rxUnderruns" to 0L,
            ),
        )
        val devicesMap: Map<String, Any?> = mapOf(
            "nativeAvailable" to true,
            "nativeBackend" to "PipeWire",
            "outputs" to listOf(
                mapOf("id" to "out-hdmi-0", "name" to "HDMI Monitor Audio", "isDefault" to false, "backend" to "PipeWire"),
                mapOf("id" to "out-usb-dac", "name" to "USB Studio DAC", "isDefault" to true, "backend" to "PipeWire"),
            ),
            "inputs" to listOf(
                mapOf("id" to "in-usb-mic", "name" to "USB Condenser Mic", "isDefault" to true, "backend" to "PipeWire"),
            ),
            "loopbacks" to listOf(
                mapOf("id" to "loop-usb-dac", "name" to "Monitor of USB Studio DAC", "isDefault" to true, "backend" to "PipeWire"),
            ),
        )

        override fun isPaused(): Boolean = paused.get()
        override fun setPaused(paused: Boolean): Boolean = this.paused.getAndSet(paused) != paused
        override fun isRunning(): Boolean = true
        override fun requestBoot(): String = "Already running"
        override fun requestStop(): String = "Stopping"
        override fun listComponents(): List<String> = emptyList()
        override fun componentState(componentId: String): Map<String, Any?>? = null
        override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> = emptyMap()
        override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = null

        override fun audioStatus(): Map<String, Any?> = LinkedHashMap(currentStatus)
        override fun audioDevices(): Map<String, Any?> = devicesMap
        override fun patchAudio(changes: Map<String, Any?>): Map<String, Any?> {
            audioPatches += LinkedHashMap(changes)
            currentStatus.putAll(changes)
            if (changes.containsKey("bufferMs")) {
                currentStatus["actualLatencyMs"] = changes["bufferMs"]
            }
            if (changes.containsKey("backendPreference")) {
                val pref = changes["backendPreference"].toString()
                currentStatus["activeBackend"] = when (pref) {
                    "JAVASOUND" -> "JAVASOUND"
                    "NATIVE" -> "NATIVE_PIPEWIRE"
                    else -> "NATIVE_PIPEWIRE"
                }
            }
            if (changes.containsKey("inputMode")) {
                val mode = changes["inputMode"].toString()
                currentStatus["inputBackendMode"] = when (mode) {
                    "OUTPUT_LOOPBACK" -> "NATIVE_LOOPBACK"
                    "HOST_MIC" -> "NATIVE_CAPTURE"
                    "TEST_TONE" -> "TEST_TONE_440HZ"
                    else -> "IDLE"
                }
            }
            return LinkedHashMap(currentStatus)
        }
    }

    @Test
    fun deviceCatalogRoutesMicAndLoopbackTargetsCleanly() {
        val full = AudioDeviceCatalogSnapshot.fromMap(
            mapOf(
                "nativeAvailable" to true,
                "nativeBackend" to "WASAPI",
                "outputs" to listOf(
                    mapOf("id" to "spk-1", "name" to "Speakers", "isDefault" to true, "backend" to "WASAPI"),
                ),
                "inputs" to listOf(
                    mapOf("id" to "mic-1", "name" to "USB Mic", "isDefault" to true, "backend" to "WASAPI"),
                ),
                "loopbacks" to listOf(
                    mapOf("id" to "lb-1", "name" to "Speakers (Loopback)", "isDefault" to true, "backend" to "WASAPI"),
                ),
            ),
        )
        assertTrue(full.nativeAvailable)
        assertEquals("WASAPI", full.nativeBackend)
        assertEquals(listOf("", "spk-1"), full.outputChoices().map { it.id })
        assertEquals(listOf("", "mic-1"), full.inputChoicesFor(AudioInputMode.HOST_MIC).map { it.id })
        assertEquals(listOf("", "lb-1"), full.inputChoicesFor(AudioInputMode.OUTPUT_LOOPBACK).map { it.id })
        assertTrue(full.inputChoicesFor(AudioInputMode.DISABLED).isEmpty())
        assertTrue(full.inputChoicesFor(AudioInputMode.TEST_TONE).isEmpty())
        assertTrue(full.inputChoicesFor(AudioInputMode.SCENE_AUDIO).isEmpty())
        assertEquals("Speakers [Default · WASAPI]", full.outputs.single().displayLabel())
        assertEquals(AudioDeviceEntry.DEFAULT_OUTPUT.name, AudioDeviceEntry.DEFAULT_OUTPUT.displayLabel())

        // When explicit loopbacks list is empty, OUTPUT_LOOPBACK falls back to output devices so user can still pick a speaker target.
        val noExplicitLoopbacks = AudioDeviceCatalogSnapshot.fromMap(
            mapOf(
                "outputs" to listOf(mapOf("id" to "spk-2", "name" to "Headset Out")),
                "inputs" to emptyList<Any>(),
                "loopbacks" to emptyList<Any>(),
            ),
        )
        assertEquals(listOf("", "spk-2"), noExplicitLoopbacks.inputChoicesFor(AudioInputMode.OUTPUT_LOOPBACK).map { it.id })
    }

    @Test
    fun statusSnapshotParsesM10AndLegacyPayloadsAndClampsPatches() {
        val status = AudioIoStatusSnapshot.fromMap(
            mapOf(
                "enabled" to true,
                "muted" to false,
                "volume" to 0.75,
                "bufferMs" to 10,
                "sampleRateHz" to 48000,
                "backendPreference" to "NATIVE",
                "activeBackend" to "NATIVE_WASAPI",
                "actualLatencyMs" to 12,
                "outputDeviceId" to "spk-1",
                "outputDeviceName" to "Studio Monitors",
                "outputPeakLeft" to 1.0,
                "outputPeakRight" to 0.5,
                "headphonePlugged" to true,
                "extMicPlugged" to true,
                "inputMode" to "OUTPUT_LOOPBACK",
                "inputBackendMode" to "NATIVE_LOOPBACK",
                "inputDeviceId" to "lb-1",
                "inputDeviceName" to "Studio Monitors (Loopback)",
                "inputGainDb" to 6.0,
                "inputMuted" to false,
                "inputMonitor" to true,
                "inputPeakLeft" to 0.25,
                "inputPeakRight" to 0.0,
                "inputFramesCaptured" to 9600L,
                "inputOverruns" to 2L,
                "inputUnderruns" to 1L,
                "degradationReason" to "Exclusive mode busy; using shared WASAPI",
                "inputDegradationReason" to "Loopback silent while host idle",
                "codecRoute" to mapOf("activeSink" to "HEADPHONE"),
                "i2sStats" to mapOf("totalTxFrames" to 48000L),
                "rxStats" to mapOf(
                    "totalRxFrames" to 9500L,
                    "queuedRxFrames" to 100L,
                    "rxOverruns" to 2L,
                    "rxUnderruns" to 0L,
                ),
            ),
        )
        assertTrue(status.enabled)
        assertEquals(AudioBackendPreference.NATIVE, status.backendPreference)
        assertEquals(AudioInputMode.OUTPUT_LOOPBACK, status.inputMode)
        assertEquals(12, status.actualLatencyMs)
        assertEquals("HEADPHONE", status.wm8962ActiveSink)
        assertEquals(9500L, status.totalRxFrames)
        assertTrue(status.engineSummaryText().contains("NATIVE_WASAPI"))
        assertTrue(status.engineSummaryText().contains("48.0 kHz"))
        assertTrue(status.engineSummaryText().contains("Notice: Exclusive mode busy"))
        assertTrue(status.quickStripSummaryText().contains("Out 75%"))
        assertTrue(status.quickStripSummaryText().contains("Loopback"))
        assertTrue(status.inputTelemetryText().contains("I2S RX: 9500"))
        assertTrue(status.inputTelemetryText().contains("Input Notice: Loopback silent"))

        assertEquals("-∞ dB", AudioIoStatusSnapshot.formatPeakDb(0.0))
        assertEquals("0.0 dB", AudioIoStatusSnapshot.formatPeakDb(1.0))
        assertEquals("-6.0 dB", AudioIoStatusSnapshot.formatPeakDb(0.5))

        // Legacy payload without M10 fields falls back cleanly to backendMode / bufferMs.
        val legacy = AudioIoStatusSnapshot.fromMap(
            mapOf(
                "enabled" to false,
                "backendMode" to "JAVASOUND",
                "bufferMs" to 40,
            ),
        )
        assertEquals("JAVASOUND", legacy.activeBackend)
        assertEquals(40, legacy.actualLatencyMs)
        assertEquals(AudioInputMode.DISABLED, legacy.inputMode)

        // Patch builders clamp out-of-range numeric values cleanly.
        val outPatch = AudioIoStatusSnapshot.buildOutputPatch(
            enabled = true,
            muted = false,
            volume = 1.5,
            outputDeviceId = "dev-0",
            bufferMs = 2,
            backendPreference = AudioBackendPreference.JAVASOUND,
            headphonePlugged = true,
        )
        assertEquals(1.0, outPatch["volume"])
        assertEquals(10, outPatch["bufferMs"])
        assertEquals("JAVASOUND", outPatch["backendPreference"])
        assertEquals(true, outPatch["headphonePlugged"])

        val inPatch = AudioIoStatusSnapshot.buildInputPatch(
            inputMode = AudioInputMode.OUTPUT_LOOPBACK,
            inputDeviceId = "lb-0",
            inputGainDb = 48.0,
            inputMuted = true,
            inputMonitor = false,
            extMicPlugged = true,
        )
        assertEquals("OUTPUT_LOOPBACK", inPatch["inputMode"])
        assertEquals("lb-0", inPatch["inputDeviceId"])
        assertEquals(24.0, inPatch["inputGainDb"])
        assertEquals(true, inPatch["inputMuted"])
        assertEquals(false, inPatch["inputMonitor"])
        assertEquals(true, inPatch["extMicPlugged"])
    }

    @Test
    fun audioConsoleCommandsDispatchAllSubcommandsAndRejectInvalidArgs(@TempDir dir: Path) {
        val backend = FakeAudioConsoleBackend(dir)
        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = out,
            interactivePrompt = false,
            timeSupplier = { LocalTime.of(13, 30, 0) },
        )

        assertNotNull(console.registry.resolve("audio"))
        assertNotNull(console.registry.resolve("sound"))
        assertNotNull(console.registry.resolve("fuji:audio"))

        // Inspect status & devices
        assertTrue(console.executeLine("audio"))
        assertTrue(console.executeLine("audio status"))
        assertTrue(console.executeLine("sound devices"))

        // Output controls
        assertTrue(console.executeLine("audio out off"))
        assertTrue(console.executeLine("audio out on"))
        assertTrue(console.executeLine("audio out mute"))
        assertTrue(console.executeLine("audio out unmute"))
        assertTrue(console.executeLine("audio out vol 65"))
        assertTrue(console.executeLine("audio out device out-usb-dac"))
        assertTrue(console.executeLine("audio out device default"))

        // Input / Loopback / Gain / Mute / Monitor
        assertTrue(console.executeLine("audio in mic"))
        assertTrue(console.executeLine("audio in loopback"))
        assertTrue(console.executeLine("audio in-device loop-usb-dac"))
        assertTrue(console.executeLine("audio in-device default"))
        assertTrue(console.executeLine("audio in tone"))
        assertTrue(console.executeLine("audio in scene"))
        assertTrue(console.executeLine("audio in off"))
        assertTrue(console.executeLine("audio gain 6.5"))
        assertTrue(console.executeLine("audio gain -12"))
        assertTrue(console.executeLine("audio in-mute on"))
        assertTrue(console.executeLine("audio monitor on"))

        // Engine / Latency / 3.5mm GPIO jacks
        assertTrue(console.executeLine("audio latency 10"))
        assertTrue(console.executeLine("audio backend javasound"))
        assertTrue(console.executeLine("audio backend native"))
        assertTrue(console.executeLine("audio backend auto"))
        assertTrue(console.executeLine("audio hp on"))
        assertTrue(console.executeLine("audio hp off"))
        assertTrue(console.executeLine("audio mic-jack on"))
        assertTrue(console.executeLine("audio mic-jack off"))

        // Usage errors
        assertTrue(console.executeLine("audio out"))
        assertTrue(console.executeLine("audio out vol 150"))
        assertTrue(console.executeLine("audio in bogus"))
        assertTrue(console.executeLine("audio gain 40"))
        assertTrue(console.executeLine("audio latency 5"))
        assertTrue(console.executeLine("audio backend alsa"))
        assertTrue(console.executeLine("audio hp maybe"))
        assertTrue(console.executeLine("audio unknown-sub"))

        // Verify exact patches recorded
        assertTrue(backend.audioPatches.any { it["volume"] == 0.65 })
        assertTrue(backend.audioPatches.any { it["outputDeviceId"] == "out-usb-dac" })
        assertTrue(backend.audioPatches.any { it["outputDeviceId"] == "" })
        assertTrue(backend.audioPatches.any { it["inputMode"] == "HOST_MIC" })
        assertTrue(backend.audioPatches.any { it["inputMode"] == "OUTPUT_LOOPBACK" })
        assertTrue(backend.audioPatches.any { it["inputMode"] == "TEST_TONE" })
        assertTrue(backend.audioPatches.any { it["inputMode"] == "SCENE_AUDIO" })
        assertTrue(backend.audioPatches.any { it["inputMode"] == "DISABLED" })
        assertTrue(backend.audioPatches.any { it["inputDeviceId"] == "loop-usb-dac" })
        assertTrue(backend.audioPatches.any { it["inputDeviceId"] == "" })
        assertTrue(backend.audioPatches.any { it["inputGainDb"] == 6.5 })
        assertTrue(backend.audioPatches.any { it["inputGainDb"] == -12.0 })
        assertTrue(backend.audioPatches.any { it["inputMuted"] == true })
        assertTrue(backend.audioPatches.any { it["inputMonitor"] == true })
        assertTrue(backend.audioPatches.any { it["bufferMs"] == 10 })
        assertTrue(backend.audioPatches.any { it["backendPreference"] == "JAVASOUND" })
        assertTrue(backend.audioPatches.any { it["backendPreference"] == "NATIVE" })
        assertTrue(backend.audioPatches.any { it["backendPreference"] == "AUTO" })
        assertTrue(backend.audioPatches.any { it["headphonePlugged"] == true })
        assertTrue(backend.audioPatches.any { it["headphonePlugged"] == false })
        assertTrue(backend.audioPatches.any { it["extMicPlugged"] == true })
        assertTrue(backend.audioPatches.any { it["extMicPlugged"] == false })

        val text = out.toString(StandardCharsets.UTF_8)
        assertTrue(text.contains("Audio I/O: backend=NATIVE_PIPEWIRE (pref=AUTO)"), text)
        assertTrue(text.contains("Monitor of USB Studio DAC"), text)
        assertTrue(text.contains("Audio output volume -> 65%"), text)
        assertTrue(text.contains("Audio input mode -> OUTPUT_LOOPBACK (backend=NATIVE_LOOPBACK)"), text)
        assertTrue(text.contains("Audio latency -> target=10ms (actual=10ms)"), text)
        assertTrue(text.contains("Audio 3.5mm headphone jack (GPIO 8.4) -> PLUGGED"), text)
        assertTrue(text.contains("Audio 3.5mm external mic jack (GPIO 6.4) -> PLUGGED"), text)
        assertTrue(text.contains("Volume must be in 0..100."), text)
        assertTrue(text.contains("Input gain must be in -24..24 dB."), text)
        assertTrue(text.contains("Audio latency must be in 10..500 ms."), text)
        assertTrue(text.contains("Usage: /audio ["), text)

        // Zero third-party branding strings
        val lower = text.lowercase()
        assertFalse(lower.contains("spigot"), "Unexpected branding in output: $text")
        assertFalse(lower.contains("spark"), "Unexpected branding in output: $text")
    }
}
