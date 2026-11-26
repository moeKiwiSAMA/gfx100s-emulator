package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.EmulatorRuntime
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ProgressBar
import javafx.scene.control.Slider
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.log10

enum class AudioBackendPreference(val id: String, val labelKey: String) {
    AUTO("AUTO", "audio.backend.auto"),
    NATIVE("NATIVE", "audio.backend.native"),
    JAVASOUND("JAVASOUND", "audio.backend.javasound"),
    ;

    val label: String get() = I18n.t(labelKey)
    val labelZh: String get() = label

    companion object {
        fun fromId(raw: String?): AudioBackendPreference =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

enum class AudioInputMode(val id: String, val labelKey: String) {
    DISABLED("DISABLED", "audio.input.disabled"),
    HOST_MIC("HOST_MIC", "audio.input.host_mic"),
    OUTPUT_LOOPBACK("OUTPUT_LOOPBACK", "audio.input.loopback"),
    TEST_TONE("TEST_TONE", "audio.input.test_tone"),
    SCENE_AUDIO("SCENE_AUDIO", "audio.input.scene_audio"),
    ;

    val label: String get() = I18n.t(labelKey)
    val labelZh: String get() = label

    companion object {
        fun fromId(raw: String?): AudioInputMode = when (raw?.trim()?.uppercase(Locale.ROOT)) {
            "HOST_MIC", "MIC" -> HOST_MIC
            "OUTPUT_LOOPBACK", "LOOPBACK" -> OUTPUT_LOOPBACK
            "TEST_TONE", "TONE" -> TEST_TONE
            "SCENE_AUDIO", "SCENE", "SCENE-AUDIO" -> SCENE_AUDIO
            else -> DISABLED
        }
    }
}

data class AudioDeviceEntry(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val backend: String = "",
) {
    fun displayLabel(): String = when {
        id.isEmpty() -> name
        isDefault && backend.isNotBlank() -> I18n.t("audio.device.badge.default_backend", name, backend)
        isDefault -> I18n.t("audio.device.badge.default", name)
        backend.isNotBlank() -> I18n.t("audio.device.badge.backend", name, backend)
        else -> name
    }

    companion object {
        val DEFAULT_OUTPUT: AudioDeviceEntry
            get() = AudioDeviceEntry("", I18n.t("audio.device.default_output"), isDefault = true)
        val DEFAULT_MIC: AudioDeviceEntry
            get() = AudioDeviceEntry("", I18n.t("audio.device.default_mic"), isDefault = true)
        val DEFAULT_LOOPBACK: AudioDeviceEntry
            get() = AudioDeviceEntry("", I18n.t("audio.device.default_loopback"), isDefault = true)
    }
}

data class AudioDeviceCatalogSnapshot(
    val outputs: List<AudioDeviceEntry>,
    val inputs: List<AudioDeviceEntry>,
    val loopbacks: List<AudioDeviceEntry>,
    val nativeAvailable: Boolean,
    val nativeBackend: String,
) {
    fun outputChoices(): List<AudioDeviceEntry> = listOf(AudioDeviceEntry.DEFAULT_OUTPUT) + outputs

    fun inputChoicesFor(mode: AudioInputMode): List<AudioDeviceEntry> = when (mode) {
        AudioInputMode.HOST_MIC -> listOf(AudioDeviceEntry.DEFAULT_MIC) + inputs
        AudioInputMode.OUTPUT_LOOPBACK -> {
            val targets = if (loopbacks.isNotEmpty()) loopbacks else outputs
            listOf(AudioDeviceEntry.DEFAULT_LOOPBACK) + targets
        }
        AudioInputMode.DISABLED, AudioInputMode.TEST_TONE, AudioInputMode.SCENE_AUDIO -> emptyList()
    }

    companion object {
        val EMPTY = AudioDeviceCatalogSnapshot(emptyList(), emptyList(), emptyList(), false, "none")

        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Map<String, Any?>?): AudioDeviceCatalogSnapshot {
            if (raw == null) return EMPTY
            fun parseList(key: String): List<AudioDeviceEntry> {
                val items = raw[key] as? List<*> ?: return emptyList()
                return items.mapNotNull { entry ->
                    val map = entry as? Map<*, *> ?: return@mapNotNull null
                    val id = map["id"]?.toString() ?: return@mapNotNull null
                    val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id
                    val isDefault = map["isDefault"] == true
                    val backend = map["backend"]?.toString().orEmpty()
                    AudioDeviceEntry(id = id, name = name, isDefault = isDefault, backend = backend)
                }
            }
            return AudioDeviceCatalogSnapshot(
                outputs = parseList("outputs"),
                inputs = parseList("inputs"),
                loopbacks = parseList("loopbacks"),
                nativeAvailable = raw["nativeAvailable"] == true,
                nativeBackend = raw["nativeBackend"]?.toString().orEmpty(),
            )
        }
    }
}

data class AudioIoStatusSnapshot(
    val enabled: Boolean,
    val muted: Boolean,
    val volume: Double,
    val bufferMs: Int,
    val sampleRateHz: Int,
    val backendPreference: AudioBackendPreference,
    val activeBackend: String,
    val actualLatencyMs: Int,
    val outputDeviceId: String,
    val outputDeviceName: String,
    val outputPeakLeft: Double,
    val outputPeakRight: Double,
    val headphonePlugged: Boolean,
    val extMicPlugged: Boolean,
    val inputMode: AudioInputMode,
    val inputBackendMode: String,
    val inputDeviceId: String,
    val inputDeviceName: String,
    val inputGainDb: Double,
    val inputMuted: Boolean,
    val inputMonitor: Boolean,
    val inputPeakLeft: Double,
    val inputPeakRight: Double,
    val inputFramesCaptured: Long,
    val inputOverruns: Long,
    val inputUnderruns: Long,
    val inputDegradationReason: String?,
    val degradationReason: String?,
    val wm8962ActiveSink: String,
    val totalTxFrames: Long,
    val totalRxFrames: Long,
    val queuedRxFrames: Long,
    val rxOverruns: Long,
    val rxUnderruns: Long,
) {
    fun engineSummaryText(): String {
        val khz = String.format(Locale.ROOT, "%.1f kHz", sampleRateHz / 1000.0)
        val base = I18n.t("audio.engine.summary", activeBackend, khz, actualLatencyMs, bufferMs, wm8962ActiveSink)
        return if (!degradationReason.isNullOrBlank()) "$base\n${I18n.t("audio.engine.degradation", degradationReason)}" else base
    }

    fun quickStripSummaryText(): String {
        val outState = when {
            !enabled -> I18n.t("audio.quick.out_off")
            muted -> I18n.t("audio.quick.out_muted")
            else -> I18n.t("audio.quick.out_vol", (volume * 100.0).toInt())
        }
        return "$outState \u00B7 ${inputMode.label} \u00B7 $activeBackend (${actualLatencyMs}ms)"
    }

    fun inputTelemetryText(): String {
        val base = I18n.t("audio.input.telemetry", inputBackendMode, inputFramesCaptured, totalRxFrames, queuedRxFrames, rxOverruns)
        return if (!inputDegradationReason.isNullOrBlank()) "$base\n${I18n.t("audio.input.degradation", inputDegradationReason)}" else base
    }

    companion object {
        val LATENCY_PRESETS_MS: List<Int> = listOf(10, 20, 40, 60)

        fun formatPeakDb(peak: Double): String {
            val p = peak.coerceIn(0.0, 1.0)
            if (p <= 1e-4) return "-\u221E dB"
            val db = 20.0 * log10(p)
            return String.format(Locale.ROOT, "%.1f dB", db)
        }

        fun buildOutputPatch(
            enabled: Boolean? = null,
            muted: Boolean? = null,
            volume: Double? = null,
            outputDeviceId: String? = null,
            bufferMs: Int? = null,
            backendPreference: AudioBackendPreference? = null,
            headphonePlugged: Boolean? = null,
        ): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            enabled?.let { out["enabled"] = it }
            muted?.let { out["muted"] = it }
            volume?.let { out["volume"] = it.coerceIn(0.0, 1.0) }
            outputDeviceId?.let { out["outputDeviceId"] = it }
            bufferMs?.let { out["bufferMs"] = it.coerceIn(10, 500) }
            backendPreference?.let { out["backendPreference"] = it.id }
            headphonePlugged?.let { out["headphonePlugged"] = it }
            return out
        }

        fun buildInputPatch(
            inputMode: AudioInputMode? = null,
            inputDeviceId: String? = null,
            inputGainDb: Double? = null,
            inputMuted: Boolean? = null,
            inputMonitor: Boolean? = null,
            extMicPlugged: Boolean? = null,
        ): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            inputMode?.let { out["inputMode"] = it.id }
            inputDeviceId?.let { out["inputDeviceId"] = it }
            inputGainDb?.let { out["inputGainDb"] = it.coerceIn(-24.0, 24.0) }
            inputMuted?.let { out["inputMuted"] = it }
            inputMonitor?.let { out["inputMonitor"] = it }
            extMicPlugged?.let { out["extMicPlugged"] = it }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(raw: Map<String, Any?>?): AudioIoStatusSnapshot {
            val map = raw.orEmpty()
            val codec = map["codecRoute"] as? Map<String, Any?>
            val i2s = map["i2sStats"] as? Map<String, Any?>
            val rx = map["rxStats"] as? Map<String, Any?>

            val bufferMs = (map["bufferMs"] as? Number)?.toInt() ?: 40
            val backendModeFallback = map["backendMode"]?.toString() ?: "DISABLED"
            val activeBackend = map["activeBackend"]?.toString()?.takeIf { it.isNotBlank() } ?: backendModeFallback

            return AudioIoStatusSnapshot(
                enabled = map["enabled"] == true,
                muted = map["muted"] == true,
                volume = ((map["volume"] as? Number)?.toDouble() ?: 0.8).coerceIn(0.0, 1.0),
                bufferMs = bufferMs,
                sampleRateHz = (map["sampleRateHz"] as? Number)?.toInt() ?: 48_000,
                backendPreference = AudioBackendPreference.fromId(map["backendPreference"]?.toString()),
                activeBackend = activeBackend,
                actualLatencyMs = (map["actualLatencyMs"] as? Number)?.toInt() ?: bufferMs,
                outputDeviceId = map["outputDeviceId"]?.toString().orEmpty(),
                outputDeviceName = map["outputDeviceName"]?.toString()?.takeIf { it.isNotBlank() } ?: I18n.t("audio.device.default_output"),
                outputPeakLeft = ((map["outputPeakLeft"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                outputPeakRight = ((map["outputPeakRight"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                headphonePlugged = map["headphonePlugged"] == true,
                extMicPlugged = map["extMicPlugged"] == true,
                inputMode = AudioInputMode.fromId(map["inputMode"]?.toString()),
                inputBackendMode = map["inputBackendMode"]?.toString()?.takeIf { it.isNotBlank() } ?: "DISABLED",
                inputDeviceId = map["inputDeviceId"]?.toString().orEmpty(),
                inputDeviceName = map["inputDeviceName"]?.toString()?.takeIf { it.isNotBlank() } ?: I18n.t("audio.device.default_generic"),
                inputGainDb = ((map["inputGainDb"] as? Number)?.toDouble() ?: 0.0).coerceIn(-24.0, 24.0),
                inputMuted = map["inputMuted"] == true,
                inputMonitor = map["inputMonitor"] == true,
                inputPeakLeft = ((map["inputPeakLeft"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                inputPeakRight = ((map["inputPeakRight"] as? Number)?.toDouble() ?: 0.0).coerceIn(0.0, 1.0),
                inputFramesCaptured = (map["inputFramesCaptured"] as? Number)?.toLong() ?: 0L,
                inputOverruns = (map["inputOverruns"] as? Number)?.toLong() ?: 0L,
                inputUnderruns = (map["inputUnderruns"] as? Number)?.toLong() ?: 0L,
                inputDegradationReason = map["inputDegradationReason"]?.toString()?.takeIf { it.isNotBlank() },
                degradationReason = map["degradationReason"]?.toString()?.takeIf { it.isNotBlank() },
                wm8962ActiveSink = codec?.get("activeSink")?.toString()?.takeIf { it.isNotBlank() } ?: "UNBOUND",
                totalTxFrames = (i2s?.get("totalTxFrames") as? Number)?.toLong() ?: (map["framesPlayed"] as? Number)?.toLong() ?: 0L,
                totalRxFrames = (rx?.get("totalRxFrames") as? Number)?.toLong() ?: 0L,
                queuedRxFrames = (rx?.get("queuedRxFrames") as? Number)?.toLong() ?: 0L,
                rxOverruns = (rx?.get("rxOverruns") as? Number)?.toLong() ?: 0L,
                rxUnderruns = (rx?.get("rxUnderruns") as? Number)?.toLong() ?: 0L,
            )
        }
    }
}

/**
 * Thread-safe runtime adapter between [AudioIoPanel] / [com.github.nyabanban.fuji.emulator.desktop.console.RuntimeConsoleBackend]
 * and [EmulatorRuntime.audioPlayback].
 */
class HostAudioRuntimeAdapter(
    private val runtime: EmulatorRuntime,
) {
    private val applyPatchMethod = runtime.audioPlayback.javaClass.methods.firstOrNull { m ->
        m.name == "applyPatch" && m.parameterCount == 1 && Map::class.java.isAssignableFrom(m.parameterTypes[0])
    }
    private val listDevicesMethod = runtime.audioPlayback.javaClass.methods.firstOrNull { m ->
        m.name == "listDevices" && m.parameterCount == 0
    }

    fun fetchStatus(): Map<String, Any?> = runtime.audioPlayback.status()

    @Suppress("UNCHECKED_CAST")
    fun fetchDevices(): Map<String, Any?> {
        val method = listDevicesMethod
        if (method != null) {
            return runCatching { method.invoke(runtime.audioPlayback) as? Map<String, Any?> }
                .getOrNull()
                ?: emptyMap()
        }
        return enumerateJavaSoundFallback()
    }

    @Suppress("UNCHECKED_CAST")
    fun applyPatch(changes: Map<String, Any?>): Map<String, Any?> {
        val needsOwner = "headphonePlugged" in changes || "extMicPlugged" in changes
        val exec: () -> Map<String, Any?> = {
            val method = applyPatchMethod
            if (method != null) {
                (method.invoke(runtime.audioPlayback, changes) as? Map<String, Any?>) ?: runtime.audioPlayback.status()
            } else {
                applyLegacyFallback(changes)
            }
        }
        return if (needsOwner) runtime.owner.submit(exec).join() else exec()
    }

    fun applyPatchAsync(changes: Map<String, Any?>): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.supplyAsync { applyPatch(changes) }

    private fun applyLegacyFallback(changes: Map<String, Any?>): Map<String, Any?> {
        val audio = runtime.audioPlayback
        (changes["volume"] as? Number)?.let { audio.setVolume(it.toDouble().coerceIn(0.0, 1.0)) }
        (changes["muted"] as? Boolean)?.let { audio.setMuted(it) }
        (changes["headphonePlugged"] as? Boolean)?.let { audio.setHeadphonePlugged(it) }
        (changes["extMicPlugged"] as? Boolean)?.let { audio.setExtMicPlugged(it) }
        (changes["enabled"] as? Boolean)?.let { audio.setEnabled(it) }
        return audio.status()
    }

    private fun enumerateJavaSoundFallback(): Map<String, Any?> {
        val outputs = ArrayList<Map<String, Any>>()
        val inputs = ArrayList<Map<String, Any>>()
        val loopbacks = ArrayList<Map<String, Any>>()
        val outLine = DataLine.Info(SourceDataLine::class.java, null)
        val inLine = DataLine.Info(TargetDataLine::class.java, null)
        val mixers = runCatching { AudioSystem.getMixerInfo() }.getOrDefault(emptyArray())
        val zhStereoMix = "\u7ACB\u4F53\u58F0\u6DF7\u97F3"
        for (info in mixers) {
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
            val name = info.name.orEmpty().ifBlank { "Mixer" }
            val lower = name.lowercase(Locale.ROOT)
            val isLoopbackName = "monitor of" in lower || "stereo mix" in lower || zhStereoMix in lower || "cable output" in lower || "loopback" in lower
            if (mixer.isLineSupported(outLine)) {
                outputs += linkedMapOf("id" to name, "name" to name, "isDefault" to outputs.isEmpty(), "backend" to "JAVASOUND")
            }
            if (mixer.isLineSupported(inLine)) {
                val entry = linkedMapOf<String, Any>("id" to name, "name" to name, "isDefault" to false, "backend" to "JAVASOUND")
                if (isLoopbackName) {
                    loopbacks += entry
                } else {
                    inputs += entry
                }
            }
        }
        return linkedMapOf(
            "outputs" to outputs,
            "inputs" to inputs,
            "loopbacks" to loopbacks,
            "nativeAvailable" to false,
            "nativeBackend" to "none",
        )
    }
}

/**
 * Full-duplex Audio I/O & System Loopback Configuration Panel mounted in the dedicated Audio I/O tab.
 */
class AudioIoPanel(
    private val fetchStatus: () -> Map<String, Any?>,
    private val fetchDevices: () -> Map<String, Any?>,
    private val applyPatchAsync: (Map<String, Any?>) -> CompletableFuture<Map<String, Any?>>,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    val root: VBox = VBox(8.0).apply {
        padding = Insets(8.0)
        styleClass += "camera-studio"
    }

    private var syncing = false
    private var observedStatus = false
    private var catalog: AudioDeviceCatalogSnapshot = AudioDeviceCatalogSnapshot.EMPTY
    private var lastStatus: AudioIoStatusSnapshot = AudioIoStatusSnapshot.fromMap(null)

    private val backendCombo = ComboBox<AudioBackendPreference>().apply {
        id = "audio-backend-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(AudioBackendPreference.entries)
        converter = object : StringConverter<AudioBackendPreference>() {
            override fun toString(item: AudioBackendPreference?): String = item?.label.orEmpty()
            override fun fromString(string: String?): AudioBackendPreference =
                AudioBackendPreference.entries.firstOrNull { it.label == string } ?: AudioBackendPreference.AUTO
        }
        setCellFactory {
            object : ListCell<AudioBackendPreference>() {
                override fun updateItem(item: AudioBackendPreference?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        value = AudioBackendPreference.AUTO
    }

    private val latencyCombo = ComboBox<Int>().apply {
        id = "audio-latency-combo"
        items.setAll(AudioIoStatusSnapshot.LATENCY_PRESETS_MS)
        converter = object : StringConverter<Int>() {
            override fun toString(item: Int?): String = if (item == null) "" else "${item} ms"
            override fun fromString(string: String?): Int = string?.removeSuffix("ms")?.trim()?.toIntOrNull() ?: 40
        }
        value = 40
    }

    private val refreshDevicesBtn = Button().apply {
        id = "audio-refresh-devices-btn"
        textProperty().bind(I18n.bind("audio.engine.refresh"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
    }

    private val engineStatusLabel = Label(I18n.t("audio.engine.waiting")).apply {
        id = "audio-engine-status"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    // Output controls
    private val outEnableCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.output.enable")) }
    private val outMuteCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.output.mute")) }
    private val headphoneJackCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.output.headphone_jack")) }
    private val outputDeviceCombo = ComboBox<AudioDeviceEntry>().apply {
        id = "audio-output-device-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        converter = deviceConverter()
        setCellFactory { deviceCell() }
    }
    private val volumeLabel = Label("80%").apply { styleClass += "studio-telemetry-val" }
    private val volumeSlider = Slider(0.0, 100.0, 80.0).apply {
        id = "audio-volume-slider"
        maxWidth = Double.MAX_VALUE
    }
    private val outPeakLeftBar = vuBar("audio-out-vu-l")
    private val outPeakRightBar = vuBar("audio-out-vu-r")
    private val outPeakText = Label("L: -\u221E dB | R: -\u221E dB").apply { styleClass += "studio-telemetry-val" }

    // Input & Loopback controls
    private val inputModeCombo = ComboBox<AudioInputMode>().apply {
        id = "audio-input-mode-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(AudioInputMode.entries)
        converter = object : StringConverter<AudioInputMode>() {
            override fun toString(item: AudioInputMode?): String = item?.label.orEmpty()
            override fun fromString(string: String?): AudioInputMode =
                AudioInputMode.entries.firstOrNull { it.label == string } ?: AudioInputMode.DISABLED
        }
        setCellFactory {
            object : ListCell<AudioInputMode>() {
                override fun updateItem(item: AudioInputMode?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        value = AudioInputMode.DISABLED
    }
    private val inputDeviceLabel = Label(I18n.t("audio.input.device")).apply { styleClass += "studio-setting-label" }
    private val inputDeviceCombo = ComboBox<AudioDeviceEntry>().apply {
        id = "audio-input-device-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        converter = deviceConverter()
        setCellFactory { deviceCell() }
    }
    private val gainLabel = Label("0.0 dB").apply { styleClass += "studio-telemetry-val" }
    private val gainSlider = Slider(-24.0, 24.0, 0.0).apply {
        id = "audio-gain-slider"
        maxWidth = Double.MAX_VALUE
    }
    private val inputMuteCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.input.mute")) }
    private val inputMonitorCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.input.monitor")) }
    private val extMicJackCheck = CheckBox().apply { textProperty().bind(I18n.bind("audio.input.ext_mic_jack")) }
    private val inPeakLeftBar = vuBar("audio-in-vu-l")
    private val inPeakRightBar = vuBar("audio-in-vu-r")
    private val inPeakText = Label("L: -\u221E dB | R: -\u221E dB").apply { styleClass += "studio-telemetry-val" }
    private val inputTelemetryLabel = Label(I18n.t("audio.input.telemetry.waiting")).apply {
        id = "audio-input-telemetry"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    init {
        buildLayout()
        wireActions()
        I18n.onLocaleChange { refreshLocalizedDynamicText() }
    }

    private fun deviceConverter() = object : StringConverter<AudioDeviceEntry>() {
        override fun toString(item: AudioDeviceEntry?): String = item?.displayLabel().orEmpty()
        override fun fromString(string: String?): AudioDeviceEntry = AudioDeviceEntry.DEFAULT_OUTPUT
    }

    private fun deviceCell() = object : ListCell<AudioDeviceEntry>() {
        override fun updateItem(item: AudioDeviceEntry?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.displayLabel()
        }
    }

    private fun vuBar(controlId: String): ProgressBar = ProgressBar(0.0).apply {
        id = controlId
        maxWidth = Double.MAX_VALUE
        prefHeight = 8.0
        styleClass += "studio-vu-bar"
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private fun vuSection(lBar: ProgressBar, rBar: ProgressBar, textLabel: Label): VBox = VBox(
        3.0,
        HBox(6.0, Label("L").apply { styleClass += "studio-hint-text"; minWidth = 12.0 }, lBar).apply { alignment = Pos.CENTER_LEFT },
        HBox(6.0, Label("R").apply { styleClass += "studio-hint-text"; minWidth = 12.0 }, rBar).apply { alignment = Pos.CENTER_LEFT },
        textLabel,
    )

    private fun buildLayout() {
        val engineCard = VBox(
            6.0,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("audio.engine.section"))
                    styleClass += "studio-section-title"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                refreshDevicesBtn,
            ).apply { alignment = Pos.CENTER_LEFT },
            backendCombo,
            HBox(
                8.0,
                Label().apply {
                    textProperty().bind(I18n.bind("audio.engine.target_latency"))
                    styleClass += "studio-setting-label"
                },
                latencyCombo,
            ).apply { alignment = Pos.CENTER_LEFT },
            engineStatusLabel,
        ).apply { styleClass += "studio-card" }

        val outputCard = VBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("audio.output.section"))
                styleClass += "studio-section-title"
            },
            FlowPane(10.0, 5.0, outEnableCheck, outMuteCheck, headphoneJackCheck),
            Label().apply {
                textProperty().bind(I18n.bind("audio.output.device"))
                styleClass += "studio-setting-label"
            },
            outputDeviceCombo,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("audio.output.volume"))
                    styleClass += "studio-setting-label"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                volumeLabel,
            ).apply { alignment = Pos.CENTER_LEFT },
            volumeSlider,
            vuSection(outPeakLeftBar, outPeakRightBar, outPeakText),
        ).apply { styleClass += "studio-card" }

        val inputCard = VBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("audio.input.section"))
                styleClass += "studio-section-title"
            },
            inputModeCombo,
            inputDeviceLabel,
            inputDeviceCombo,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("audio.input.gain"))
                    styleClass += "studio-setting-label"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                gainLabel,
            ).apply { alignment = Pos.CENTER_LEFT },
            gainSlider,
            FlowPane(10.0, 5.0, inputMuteCheck, inputMonitorCheck, extMicJackCheck),
            vuSection(inPeakLeftBar, inPeakRightBar, inPeakText),
            inputTelemetryLabel,
        ).apply { styleClass += "studio-card" }

        root.children.addAll(engineCard, outputCard, inputCard)
    }

    private fun refreshLocalizedDynamicText() {
        syncing = true
        try {
            val curPref = backendCombo.value
            backendCombo.items.setAll(AudioBackendPreference.entries)
            backendCombo.value = curPref

            val curMode = inputModeCombo.value ?: AudioInputMode.DISABLED
            inputModeCombo.items.setAll(AudioInputMode.entries)
            inputModeCombo.value = curMode

            val outChoices = catalog.outputChoices()
            outputDeviceCombo.items.setAll(outChoices)
            outputDeviceCombo.value = outChoices.firstOrNull { it.id == lastStatus.outputDeviceId } ?: outChoices.firstOrNull()
            updateInputDeviceChoices(curMode, lastStatus.inputDeviceId)
        } finally {
            syncing = false
        }
        if (observedStatus) {
            engineStatusLabel.text = lastStatus.engineSummaryText()
            inputTelemetryLabel.text = lastStatus.inputTelemetryText()
        } else {
            engineStatusLabel.text = I18n.t("audio.engine.waiting")
            inputTelemetryLabel.text = I18n.t("audio.input.telemetry.waiting")
        }
    }

    private fun wireActions() {
        refreshDevicesBtn.setOnAction { refreshDevicesAsync() }

        backendCombo.setOnAction {
            if (syncing) return@setOnAction
            val pref = backendCombo.value ?: return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(backendPreference = pref), I18n.t("audio.status.backend", pref.id))
        }
        latencyCombo.setOnAction {
            if (syncing) return@setOnAction
            val ms = latencyCombo.value ?: return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(bufferMs = ms), I18n.t("audio.status.latency", ms))
        }

        outEnableCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(enabled = outEnableCheck.isSelected), I18n.t("audio.status.out_enable", outEnableCheck.isSelected))
        }
        outMuteCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(muted = outMuteCheck.isSelected), I18n.t("audio.status.out_mute", outMuteCheck.isSelected))
        }
        headphoneJackCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(headphonePlugged = headphoneJackCheck.isSelected), I18n.t("audio.status.headphone", headphoneJackCheck.isSelected))
        }
        outputDeviceCombo.setOnAction {
            if (syncing) return@setOnAction
            val chosen = outputDeviceCombo.value ?: return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildOutputPatch(outputDeviceId = chosen.id), I18n.t("audio.status.out_device", chosen.name))
        }

        volumeSlider.valueProperty().addListener { _, _, v ->
            volumeLabel.text = "${v.toInt()}%"
        }
        volumeSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                sendPatch(AudioIoStatusSnapshot.buildOutputPatch(volume = volumeSlider.value / 100.0), I18n.t("audio.status.volume", volumeLabel.text))
            }
        }
        volumeSlider.setOnMouseReleased {
            if (!syncing) {
                sendPatch(AudioIoStatusSnapshot.buildOutputPatch(volume = volumeSlider.value / 100.0), I18n.t("audio.status.volume", volumeLabel.text))
            }
        }

        inputModeCombo.setOnAction {
            if (syncing) return@setOnAction
            val mode = inputModeCombo.value ?: return@setOnAction
            updateInputDeviceChoices(mode, selectedId = "")
            sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputMode = mode, inputDeviceId = ""), I18n.t("audio.status.in_mode", mode.label))
        }
        inputDeviceCombo.setOnAction {
            if (syncing) return@setOnAction
            val chosen = inputDeviceCombo.value ?: return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputDeviceId = chosen.id), I18n.t("audio.status.in_device", chosen.name))
        }

        gainSlider.valueProperty().addListener { _, _, v ->
            gainLabel.text = String.format(Locale.ROOT, "%+.1f dB", v.toDouble())
        }
        gainSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputGainDb = gainSlider.value), I18n.t("audio.status.in_gain", gainLabel.text))
            }
        }
        gainSlider.setOnMouseReleased {
            if (!syncing) {
                sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputGainDb = gainSlider.value), I18n.t("audio.status.in_gain", gainLabel.text))
            }
        }

        inputMuteCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputMuted = inputMuteCheck.isSelected), I18n.t("audio.status.in_mute", inputMuteCheck.isSelected))
        }
        inputMonitorCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildInputPatch(inputMonitor = inputMonitorCheck.isSelected), I18n.t("audio.status.in_monitor", inputMonitorCheck.isSelected))
        }
        extMicJackCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(AudioIoStatusSnapshot.buildInputPatch(extMicPlugged = extMicJackCheck.isSelected), I18n.t("audio.status.ext_mic", extMicJackCheck.isSelected))
        }
    }

    private fun sendPatch(patch: Map<String, Any>, message: String) {
        applyPatchAsync(patch).whenComplete { updated, err ->
            Platform.runLater {
                if (err != null) {
                    onError(err)
                } else {
                    if (updated != null) observeStatusMap(updated)
                    onStatus(message)
                }
            }
        }
    }

    fun refreshDevicesAsync() {
        CompletableFuture.supplyAsync { fetchDevices() }.whenComplete { raw, err ->
            Platform.runLater {
                if (err != null) {
                    onError(err)
                } else {
                    observeDevicesMap(raw)
                    observeStatusMap(fetchStatus())
                }
            }
        }
    }

    fun observeDevicesMap(raw: Map<String, Any?>?) {
        catalog = AudioDeviceCatalogSnapshot.fromMap(raw)
        syncing = true
        try {
            val outChoices = catalog.outputChoices()
            outputDeviceCombo.items.setAll(outChoices)
            outputDeviceCombo.value = outChoices.firstOrNull { it.id == lastStatus.outputDeviceId } ?: outChoices.firstOrNull()
            updateInputDeviceChoices(lastStatus.inputMode, lastStatus.inputDeviceId)
        } finally {
            syncing = false
        }
    }

    private fun updateInputDeviceChoices(mode: AudioInputMode, selectedId: String) {
        val choices = catalog.inputChoicesFor(mode)
        inputDeviceLabel.text = I18n.t(
            when (mode) {
                AudioInputMode.OUTPUT_LOOPBACK -> "audio.input.device.loopback"
                AudioInputMode.HOST_MIC -> "audio.input.device.mic"
                AudioInputMode.TEST_TONE -> "audio.input.device.tone"
                AudioInputMode.SCENE_AUDIO -> "audio.input.device.scene_audio"
                AudioInputMode.DISABLED -> "audio.input.device.disabled"
            },
        )
        inputDeviceCombo.items.setAll(choices)
        inputDeviceCombo.isDisable = choices.isEmpty()
        if (choices.isNotEmpty()) {
            inputDeviceCombo.value = choices.firstOrNull { it.id == selectedId } ?: choices.first()
        } else {
            inputDeviceCombo.value = null
        }
    }

    fun observeStatusMap(raw: Map<String, Any?>?) {
        val st = AudioIoStatusSnapshot.fromMap(raw)
        lastStatus = st
        observedStatus = true
        syncing = true
        try {
            if (backendCombo.value != st.backendPreference) backendCombo.value = st.backendPreference
            if (st.bufferMs in AudioIoStatusSnapshot.LATENCY_PRESETS_MS && latencyCombo.value != st.bufferMs) {
                latencyCombo.value = st.bufferMs
            }
            engineStatusLabel.text = st.engineSummaryText()

            outEnableCheck.isSelected = st.enabled
            outMuteCheck.isSelected = st.muted
            headphoneJackCheck.isSelected = st.headphonePlugged
            if (outputDeviceCombo.items.isNotEmpty()) {
                val match = outputDeviceCombo.items.firstOrNull { it.id == st.outputDeviceId }
                if (match != null && outputDeviceCombo.value != match) outputDeviceCombo.value = match
            }
            if (!volumeSlider.isValueChanging) {
                val pct = (st.volume * 100.0).coerceIn(0.0, 100.0)
                volumeSlider.value = pct
                volumeLabel.text = "${pct.toInt()}%"
            }
            outPeakLeftBar.progress = st.outputPeakLeft
            outPeakRightBar.progress = st.outputPeakRight
            outPeakText.text = "L: ${AudioIoStatusSnapshot.formatPeakDb(st.outputPeakLeft)} | R: ${AudioIoStatusSnapshot.formatPeakDb(st.outputPeakRight)}"

            val modeChanged = inputModeCombo.value != st.inputMode
            if (modeChanged) {
                inputModeCombo.value = st.inputMode
                updateInputDeviceChoices(st.inputMode, st.inputDeviceId)
            } else if (inputDeviceCombo.items.isNotEmpty()) {
                val match = inputDeviceCombo.items.firstOrNull { it.id == st.inputDeviceId }
                if (match != null && inputDeviceCombo.value != match) inputDeviceCombo.value = match
            }
            if (!gainSlider.isValueChanging) {
                gainSlider.value = st.inputGainDb
                gainLabel.text = String.format(Locale.ROOT, "%+.1f dB", st.inputGainDb)
            }
            inputMuteCheck.isSelected = st.inputMuted
            inputMonitorCheck.isSelected = st.inputMonitor
            extMicJackCheck.isSelected = st.extMicPlugged
            inPeakLeftBar.progress = st.inputPeakLeft
            inPeakRightBar.progress = st.inputPeakRight
            inPeakText.text = "L: ${AudioIoStatusSnapshot.formatPeakDb(st.inputPeakLeft)} | R: ${AudioIoStatusSnapshot.formatPeakDb(st.inputPeakRight)}"
            inputTelemetryLabel.text = st.inputTelemetryText()
        } finally {
            syncing = false
        }
    }
}

/**
 * Compact quick audio strip embedded inside [CameraStudio]'s right-hand sidebar.
 */
class QuickAudioStripPane(
    private val applyAudioPatch: ((Map<String, Any?>) -> CompletableFuture<Map<String, Any?>>)?,
    private val onOpenAudioPanel: (() -> Unit)? = null,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    private var syncing = false
    private var lastStatus: AudioIoStatusSnapshot? = null

    private val outEnableCheck = CheckBox().apply {
        id = "quick-audio-enable"
        textProperty().bind(I18n.bind("audio.quick.output"))
    }
    private val outMuteCheck = CheckBox().apply {
        id = "quick-audio-mute"
        textProperty().bind(I18n.bind("audio.quick.mute"))
    }
    private val inputModeCombo = ComboBox<AudioInputMode>().apply {
        id = "quick-audio-input-mode"
        maxWidth = Double.MAX_VALUE
        items.setAll(AudioInputMode.entries)
        converter = object : StringConverter<AudioInputMode>() {
            override fun toString(item: AudioInputMode?): String = item?.label.orEmpty()
            override fun fromString(string: String?): AudioInputMode =
                AudioInputMode.entries.firstOrNull { it.label == string } ?: AudioInputMode.DISABLED
        }
        setCellFactory {
            object : ListCell<AudioInputMode>() {
                override fun updateItem(item: AudioInputMode?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
        value = AudioInputMode.DISABLED
    }
    private val openPanelBtn = Button().apply {
        id = "quick-open-audio-panel-btn"
        textProperty().bind(I18n.bind("audio.quick.open_panel"))
        styleClass += "studio-chip-btn"
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("audio.quick.open_panel.tooltip")) }
        isVisible = onOpenAudioPanel != null
        isManaged = onOpenAudioPanel != null
        setOnAction { onOpenAudioPanel?.invoke() }
    }
    private val summaryLabel = Label(I18n.t("audio.quick.waiting")).apply {
        id = "quick-audio-summary"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    val root: VBox = VBox(
        6.0,
        HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("audio.quick.section"))
                styleClass += "studio-section-title"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            openPanelBtn,
        ).apply { alignment = Pos.CENTER_LEFT },
        HBox(10.0, outEnableCheck, outMuteCheck).apply { alignment = Pos.CENTER_LEFT },
        inputModeCombo,
        summaryLabel,
    ).apply {
        styleClass += "studio-card"
    }

    init {
        outEnableCheck.setOnAction {
            if (syncing) return@setOnAction
            send(AudioIoStatusSnapshot.buildOutputPatch(enabled = outEnableCheck.isSelected), I18n.t("audio.status.out_enable", outEnableCheck.isSelected))
        }
        outMuteCheck.setOnAction {
            if (syncing) return@setOnAction
            send(AudioIoStatusSnapshot.buildOutputPatch(muted = outMuteCheck.isSelected), I18n.t("audio.status.quick_mute", outMuteCheck.isSelected))
        }
        inputModeCombo.setOnAction {
            if (syncing) return@setOnAction
            val mode = inputModeCombo.value ?: return@setOnAction
            send(AudioIoStatusSnapshot.buildInputPatch(inputMode = mode), I18n.t("audio.status.quick_in", mode.label))
        }
        I18n.onLocaleChange { refreshLocalizedDynamicText() }
    }

    private fun refreshLocalizedDynamicText() {
        syncing = true
        try {
            val cur = inputModeCombo.value ?: AudioInputMode.DISABLED
            inputModeCombo.items.setAll(AudioInputMode.entries)
            inputModeCombo.value = cur
        } finally {
            syncing = false
        }
        summaryLabel.text = lastStatus?.quickStripSummaryText() ?: I18n.t("audio.quick.waiting")
    }

    private fun send(patch: Map<String, Any>, message: String) {
        val sender = applyAudioPatch ?: return
        sender(patch).whenComplete { updated, err ->
            Platform.runLater {
                if (err != null) onError(err) else {
                    if (updated != null) observeStatusMap(updated)
                    onStatus(message)
                }
            }
        }
    }

    fun observeStatusMap(raw: Map<String, Any?>?) {
        val st = AudioIoStatusSnapshot.fromMap(raw)
        lastStatus = st
        syncing = true
        try {
            outEnableCheck.isSelected = st.enabled
            outMuteCheck.isSelected = st.muted
            if (inputModeCombo.value != st.inputMode) inputModeCombo.value = st.inputMode
            summaryLabel.text = st.quickStripSummaryText()
        } finally {
            syncing = false
        }
    }
}
