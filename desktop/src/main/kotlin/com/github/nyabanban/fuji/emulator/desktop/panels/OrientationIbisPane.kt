package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.control.Tooltip
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Pure immutable snapshot of GFX100S dual NXP `MMA8452Q` accelerometers (`i2c-bus`) and `biscom`
 * 5-axis IBIS / 16-shot 400MP Pixel Shift actuator (`soc-spi`) telemetry for [OrientationIbisPane]
 * and unit tests.
 */
data class OrientationIbisState(
    val accelEnabled: Boolean,
    val orientationPreset: String,
    val rollDeg: Double,
    val pitchDeg: Double,
    val shakeAmpDeg: Double,
    val lcdTiltDeg: Double,
    val bodyAccelActive: Boolean,
    val bodyRawX12: Int,
    val bodyRawY12: Int,
    val bodyRawZ12: Int,
    val ibisModeName: String,
    val ibisStageLocked: Boolean,
    val ibisStabilizationState: Int,
    val ibisShiftXUm: Double,
    val ibisShiftYUm: Double,
    val pixelShiftTargetX: Int,
    val pixelShiftTargetY: Int,
) {
    fun telemetryLines(): List<String> {
        val orientLabel = I18n.t(
            when (orientationPreset) {
                "portrait-cw" -> "orientation.preset.portrait_cw.full"
                "portrait-ccw" -> "orientation.preset.portrait_ccw.full"
                "inverted" -> "orientation.preset.inverted.full"
                "landscape" -> "orientation.preset.landscape.full"
                else -> "orientation.preset.custom.full"
            },
        )
        val accelBadge = if (!accelEnabled) "OFF" else if (bodyAccelActive) "ACTIVE (I2C1 0x1C)" else "STANDBY (I2C1 0x1C)"
        val lockStr = if (ibisStageLocked) "LOCKED" else "ACTIVE"
        return listOf(
            I18n.t("orientation.telemetry.attitude", orientLabel, rollDeg, pitchDeg),
            I18n.t("orientation.telemetry.accel", accelBadge, bodyRawX12, bodyRawY12, bodyRawZ12),
            I18n.t(
                "orientation.telemetry.ibis",
                ibisModeName,
                lockStr,
                ibisStabilizationState,
                ibisShiftXUm,
                ibisShiftYUm,
                pixelShiftTargetX and 0xffff,
                pixelShiftTargetY and 0xffff,
            ),
        )
    }

    companion object {
        fun buildPresetPatch(preset: String): Map<String, Any> {
            val (roll, pitch) = when (preset) {
                "portrait-cw" -> 90.0 to 0.0
                "portrait-ccw" -> -90.0 to 0.0
                "inverted" -> 180.0 to 0.0
                else -> 0.0 to 0.0
            }
            return linkedMapOf(
                "accelEnabled" to true,
                "orientationPreset" to preset,
                "rollDeg" to roll,
                "pitchDeg" to pitch,
            )
        }

        fun buildAttitudePatch(rollDeg: Double, pitchDeg: Double, shakeAmpDeg: Double? = null): Map<String, Any> {
            val out = linkedMapOf<String, Any>(
                "accelEnabled" to true,
                "rollDeg" to rollDeg.coerceIn(-180.0, 180.0),
                "pitchDeg" to pitchDeg.coerceIn(-90.0, 90.0),
            )
            if (shakeAmpDeg != null) {
                out["shakeAmpDeg"] = shakeAmpDeg.coerceIn(0.0, 5.0)
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        fun fromSnapshot(snapshot: Map<String, Any?>): OrientationIbisState {
            val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
            val mmaComp = components["mma8452q"]
            val i2cComp = components["i2c-bus"]
            val spiComp = components["soc-spi"]
            if (mmaComp != null) {
                val mmaState = mmaComp["state"] as? Map<String, Any?>
                val mmaConfig = mmaComp["config"] as? Map<String, Any?>
                val mmaEnabled = (mmaState?.get("enabled") as? Boolean) ?: (mmaConfig?.get("enabled") as? Boolean) ?: false
                if (mmaEnabled) {
                    return fromMmaMaps(mmaConfig, mmaState, spiComp?.get("state") as? Map<String, Any?>)
                }
            }
            return fromMaps(
                i2cConfig = i2cComp?.get("config") as? Map<String, Any?>,
                i2cState = i2cComp?.get("state") as? Map<String, Any?>,
                spiState = spiComp?.get("state") as? Map<String, Any?>,
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMmaMaps(
            mmaConfig: Map<String, Any?>?,
            mmaState: Map<String, Any?>?,
            spiState: Map<String, Any?>?,
        ): OrientationIbisState {
            fun getMma(key: String): Any? = mmaState?.get(key) ?: mmaConfig?.get(key)
            fun dbl(key: String, def: Double): Double = (getMma(key) as? Number)?.toDouble() ?: def
            fun bool(key: String, def: Boolean): Boolean = (getMma(key) as? Boolean) ?: def
            fun str(key: String, def: String): String = (getMma(key) as? String)?.takeIf { it.isNotBlank() } ?: def
            val bodyAccel = mmaState?.get("bodyAccel") as? Map<String, Any?>
            val ibisMap = spiState?.get("ibis") as? Map<String, Any?>
            return OrientationIbisState(
                accelEnabled = bool("accelEnabled", bool("enabled", false)),
                orientationPreset = str("orientationPreset", "landscape"),
                rollDeg = dbl("rollDeg", 0.0),
                pitchDeg = dbl("pitchDeg", 0.0),
                shakeAmpDeg = dbl("shakeAmpDeg", 0.0),
                lcdTiltDeg = dbl("lcdTiltDeg", 0.0),
                bodyAccelActive = (bodyAccel?.get("active") as? Boolean) ?: false,
                bodyRawX12 = (bodyAccel?.get("rawX12") as? Number)?.toInt() ?: 0,
                bodyRawY12 = (bodyAccel?.get("rawY12") as? Number)?.toInt() ?: 0,
                bodyRawZ12 = (bodyAccel?.get("rawZ12") as? Number)?.toInt() ?: 0,
                ibisModeName = (ibisMap?.get("ibisModeName") as? String) ?: "OFF",
                ibisStageLocked = (ibisMap?.get("stageLocked") as? Boolean) ?: true,
                ibisStabilizationState = (ibisMap?.get("stabilizationState") as? Number)?.toInt() ?: 0,
                ibisShiftXUm = (ibisMap?.get("shiftXUm") as? Number)?.toDouble() ?: 0.0,
                ibisShiftYUm = (ibisMap?.get("shiftYUm") as? Number)?.toDouble() ?: 0.0,
                pixelShiftTargetX = (ibisMap?.get("pixelShiftTargetX") as? Number)?.toInt() ?: 0x8000,
                pixelShiftTargetY = (ibisMap?.get("pixelShiftTargetY") as? Number)?.toInt() ?: 0x8000,
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMaps(
            i2cConfig: Map<String, Any?>?,
            i2cState: Map<String, Any?>?,
            spiState: Map<String, Any?>?,
        ): OrientationIbisState {
            fun getI2c(key: String): Any? = i2cState?.get(key) ?: i2cConfig?.get(key)
            fun dbl(key: String, def: Double): Double = (getI2c(key) as? Number)?.toDouble() ?: def
            fun bool(key: String, def: Boolean): Boolean = (getI2c(key) as? Boolean) ?: def
            fun str(key: String, def: String): String = (getI2c(key) as? String)?.takeIf { it.isNotBlank() } ?: def

            val buses = (i2cState?.get("buses") as? List<Map<String, Any?>>).orEmpty()
            val bus1Accel = buses.firstOrNull { (it["index"] as? Number)?.toInt() == 1 }?.get("accel") as? Map<String, Any?>
            val ibisMap = spiState?.get("ibis") as? Map<String, Any?>

            return OrientationIbisState(
                accelEnabled = bool("accelEnabled", false),
                orientationPreset = str("orientationPreset", "landscape"),
                rollDeg = dbl("rollDeg", 0.0),
                pitchDeg = dbl("pitchDeg", 0.0),
                shakeAmpDeg = dbl("shakeAmpDeg", 0.0),
                lcdTiltDeg = dbl("lcdTiltDeg", 0.0),
                bodyAccelActive = (bus1Accel?.get("active") as? Boolean) ?: false,
                bodyRawX12 = (bus1Accel?.get("rawX12") as? Number)?.toInt() ?: 0,
                bodyRawY12 = (bus1Accel?.get("rawY12") as? Number)?.toInt() ?: 0,
                bodyRawZ12 = (bus1Accel?.get("rawZ12") as? Number)?.toInt() ?: 0,
                ibisModeName = (ibisMap?.get("ibisModeName") as? String) ?: "OFF",
                ibisStageLocked = (ibisMap?.get("stageLocked") as? Boolean) ?: true,
                ibisStabilizationState = (ibisMap?.get("stabilizationState") as? Number)?.toInt() ?: 0,
                ibisShiftXUm = (ibisMap?.get("shiftXUm") as? Number)?.toDouble() ?: 0.0,
                ibisShiftYUm = (ibisMap?.get("shiftYUm") as? Number)?.toDouble() ?: 0.0,
                pixelShiftTargetX = (ibisMap?.get("pixelShiftTargetX") as? Number)?.toInt() ?: 0x8000,
                pixelShiftTargetY = (ibisMap?.get("pixelShiftTargetY") as? Number)?.toInt() ?: 0x8000,
            )
        }
    }
}

/**
 * Compact JavaFX Orientation (Portrait/Landscape/Electronic Level via NXP `MMA8452Q`) & 5-Axis IBIS
 * telemetry strip embedded inside the Studio Lens & Optics tab.
 */
class OrientationIbisPane(
    private val patchComponent: (String, Map<String, Any>) -> CompletableFuture<*>,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    val root: VBox = VBox(6.0).apply {
        padding = Insets(8.0)
        styleClass += "studio-card"
    }

    private var syncing = false
    private var lastState: OrientationIbisState? = null
    private var orientationComponentId: String = "i2c-bus"
    private val accelCheck = CheckBox().apply {
        id = "orientation-accel-enabled"
        textProperty().bind(I18n.bind("orientation.accel.enable"))
        tooltip = Tooltip().apply { textProperty().bind(I18n.bind("orientation.accel.tooltip")) }
    }
    private val ibisBadge = Label("IBIS: OFF").apply { styleClass += "studio-brand-badge" }

    private val rollLabel = Label("0.0\u00B0").apply { styleClass += "studio-telemetry-val" }
    private val rollSlider = Slider(-180.0, 180.0, 0.0).apply {
        id = "orientation-roll-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val pitchLabel = Label("0.0\u00B0").apply { styleClass += "studio-telemetry-val" }
    private val pitchSlider = Slider(-90.0, 90.0, 0.0).apply {
        id = "orientation-pitch-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val shakeLabel = Label("0.00\u00B0").apply { styleClass += "studio-telemetry-val" }
    private val shakeSlider = Slider(0.0, 2.5, 0.0).apply {
        id = "orientation-shake-slider"
        maxWidth = Double.MAX_VALUE
    }

    private val telemetryLabels = List(3) {
        Label(I18n.t("orientation.telemetry.waiting")).apply {
            styleClass += "studio-telemetry-val"
            isWrapText = true
        }
    }

    init {
        buildLayout()
        wireActions()
        I18n.onLocaleChange { refreshLocalizedTelemetry() }
    }

    private fun refreshLocalizedTelemetry() {
        val state = lastState
        if (state != null) {
            val lines = state.telemetryLines()
            telemetryLabels.forEachIndexed { idx, label -> label.text = lines.getOrElse(idx) { "" } }
        } else {
            val waiting = I18n.t("orientation.telemetry.waiting")
            telemetryLabels.forEach { it.text = waiting }
        }
    }

    private fun buildLayout() {
        val header = HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("orientation.section.title"))
                styleClass += "studio-section-title"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            ibisBadge,
        ).apply { alignment = Pos.CENTER_LEFT }

        val presetRow = FlowPane(5.0, 5.0).apply {
            listOf(
                "orientation.preset.landscape.btn" to "landscape",
                "orientation.preset.portrait_cw.btn" to "portrait-cw",
                "orientation.preset.portrait_ccw.btn" to "portrait-ccw",
                "orientation.preset.inverted.btn" to "inverted",
            ).forEach { (titleKey, preset) ->
                children += Button().apply {
                    id = "orientation-preset-$preset"
                    textProperty().bind(I18n.bind(titleKey))
                    styleClass += "studio-chip-btn"
                    setOnAction {
                        sendPatch(
                            orientationComponentId,
                            OrientationIbisState.buildPresetPatch(preset),
                            I18n.t("orientation.status.preset", I18n.t(titleKey)),
                        )
                    }
                }
            }
        }

        root.children.addAll(
            header,
            accelCheck,
            presetRow,
            sliderRow("orientation.slider.roll", rollLabel, rollSlider),
            sliderRow("orientation.slider.pitch", pitchLabel, pitchSlider),
            sliderRow("orientation.slider.shake", shakeLabel, shakeSlider),
            *telemetryLabels.toTypedArray(),
        )
    }

    private fun sliderRow(titleKey: String, valueLabel: Label, slider: Slider): VBox = VBox(
        2.0,
        HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind(titleKey))
                styleClass += "studio-setting-label"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            valueLabel,
        ).apply { alignment = Pos.CENTER_LEFT },
        slider,
    )

    private fun wireActions() {
        accelCheck.setOnAction {
            if (syncing) return@setOnAction
            sendPatch(
                orientationComponentId,
                mapOf("accelEnabled" to accelCheck.isSelected),
                I18n.t("orientation.status.accel", accelCheck.isSelected),
            )
        }
        rollSlider.valueProperty().addListener { _, _, v ->
            rollLabel.text = String.format(Locale.ROOT, "%.1f\u00B0", v.toDouble())
        }
        rollSlider.setOnMouseReleased {
            if (!syncing) {
                sendPatch(
                    orientationComponentId,
                    OrientationIbisState.buildAttitudePatch(rollSlider.value, pitchSlider.value),
                    I18n.t("orientation.status.roll", rollLabel.text),
                )
            }
        }
        pitchSlider.valueProperty().addListener { _, _, v ->
            pitchLabel.text = String.format(Locale.ROOT, "%.1f\u00B0", v.toDouble())
        }
        pitchSlider.setOnMouseReleased {
            if (!syncing) {
                sendPatch(
                    orientationComponentId,
                    OrientationIbisState.buildAttitudePatch(rollSlider.value, pitchSlider.value),
                    I18n.t("orientation.status.pitch", pitchLabel.text),
                )
            }
        }
        shakeSlider.valueProperty().addListener { _, _, v ->
            shakeLabel.text = String.format(Locale.ROOT, "%.2f\u00B0", v.toDouble())
        }
        shakeSlider.setOnMouseReleased {
            if (!syncing) {
                sendPatch(
                    orientationComponentId,
                    mapOf("shakeAmpDeg" to shakeSlider.value),
                    I18n.t("orientation.status.shake", shakeLabel.text),
                )
            }
        }
    }

    private fun sendPatch(componentId: String, changes: Map<String, Any>, message: String) {
        patchComponent(componentId, changes).whenComplete { _, err ->
            if (err != null) onError(err) else onStatus(message)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun observeSnapshot(snapshot: Map<String, Any?>) {
        val components = (snapshot["components"] as? List<Map<String, Any?>>).orEmpty().associateBy { it["id"] }
        val mma = components["mma8452q"]
        val mmaEnabled = ((mma?.get("state") as? Map<*, *>)?.get("enabled") as? Boolean)
            ?: ((mma?.get("config") as? Map<*, *>)?.get("enabled") as? Boolean)
            ?: false
        orientationComponentId = if (mmaEnabled) "mma8452q" else "i2c-bus"
        val state = OrientationIbisState.fromSnapshot(snapshot)
        lastState = state
        syncing = true
        try {
            accelCheck.isSelected = state.accelEnabled
            ibisBadge.text = "IBIS: ${state.ibisModeName}"
            if (!rollSlider.isValueChanging) {
                rollSlider.value = state.rollDeg.coerceIn(-180.0, 180.0)
                rollLabel.text = String.format(Locale.ROOT, "%.1f\u00B0", state.rollDeg)
            }
            if (!pitchSlider.isValueChanging) {
                pitchSlider.value = state.pitchDeg.coerceIn(-90.0, 90.0)
                pitchLabel.text = String.format(Locale.ROOT, "%.1f\u00B0", state.pitchDeg)
            }
            if (!shakeSlider.isValueChanging) {
                shakeSlider.value = state.shakeAmpDeg.coerceIn(0.0, 2.5)
                shakeLabel.text = String.format(Locale.ROOT, "%.2f\u00B0", state.shakeAmpDeg)
            }
            val lines = state.telemetryLines()
            telemetryLabels.forEachIndexed { idx, label -> label.text = lines.getOrElse(idx) { "" } }
        } finally {
            syncing = false
        }
    }
}
