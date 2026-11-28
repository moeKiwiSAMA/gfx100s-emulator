package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.Slider
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

data class BatteryChargingStatusSnapshot(
    val present: Boolean = true,
    val chargePercent: Double = 100.0,
    val voltageMillivolts: Int = 7400,
    val charging: Boolean = false,
    val externalPowerConnected: Boolean = false,
    val cratePercentPerHour: Double? = null,
) {
    fun summaryText(): String {
        val stateBadge = when {
            !present -> I18n.t("battery.panel.state_removed")
            charging -> I18n.t("battery.panel.state_charging")
            else -> I18n.t("battery.panel.state_discharging")
        }
        val pctStr = "${chargePercent.roundToInt()}%"
        val voltsStr = String.format(Locale.ROOT, "%.2f V (%d mV)", voltageMillivolts / 1000.0, voltageMillivolts)
        val vbusStr = I18n.t(if (externalPowerConnected) "battery.panel.vbus_on" else "battery.panel.vbus_off")
        val base = I18n.t("battery.panel.summary", stateBadge, pctStr, voltsStr, vbusStr)
        val rate = cratePercentPerHour
        return if (rate != null && rate != 0.0) {
            "$base · ${String.format(Locale.ROOT, "%+.1f%%/h", rate)}"
        } else {
            base
        }
    }

    companion object {
        val INITIAL = BatteryChargingStatusSnapshot()

        fun fromStatusMap(raw: Map<String, Any?>?): BatteryChargingStatusSnapshot {
            if (raw == null) return INITIAL
            val present = (raw["present"] as? Boolean) ?: true
            val pct = (((raw["chargePercent"] as? Number) ?: (raw["batteryPercent"] as? Number))?.toDouble() ?: 100.0)
                .coerceIn(0.0, 100.0)
            val mv = ((raw["voltageMillivolts"] as? Number)?.toInt() ?: 7400).coerceIn(0, 12_000)
            val charging = (raw["charging"] as? Boolean) ?: false
            val ext = (raw["externalPowerConnected"] as? Boolean)
                ?: (raw["usbVbusConnected"] as? Boolean)
                ?: charging
            val crate = ((raw["cratePercentPerHour"] as? Number) ?: (raw["chargeRatePercentPerHour"] as? Number))?.toDouble()
            return BatteryChargingStatusSnapshot(
                present = present,
                chargePercent = pct,
                voltageMillivolts = mv,
                charging = charging,
                externalPowerConnected = ext,
                cratePercentPerHour = crate,
            )
        }

        @Suppress("UNCHECKED_CAST")
        fun fromHostSnapshot(snapshot: Map<String, Any?>): BatteryChargingStatusSnapshot? {
            val components = (snapshot["components"] as? List<Map<String, Any?>>) ?: return null
            val bat = components.firstOrNull { it["id"] == "battery" } ?: return null
            val state = bat["state"] as? Map<String, Any?>
            val config = bat["config"] as? Map<String, Any?>
            val merged = LinkedHashMap<String, Any?>()
            if (config != null) merged.putAll(config)
            if (state != null) merged.putAll(state)
            return fromStatusMap(merged)
        }
    }
}

/**
 * Compact NP-W235 Battery & USB-C Charging control card mounted on the Studio Controls sidebar.
 */
class BatteryChargingPane(
    private val applyPowerPatch: ((Map<String, Any?>) -> CompletableFuture<Map<String, Any?>>)? = null,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    private var syncing = false
    private var observedPowerRuntimeStatus = false
    private var lastStatus: BatteryChargingStatusSnapshot = BatteryChargingStatusSnapshot.INITIAL

    private val chargingCheck = CheckBox().apply {
        id = "battery-charging-check"
        textProperty().bind(I18n.bind("battery.panel.charging"))
    }

    private val usbVbusCheck = CheckBox().apply {
        id = "battery-usb-vbus-check"
        textProperty().bind(I18n.bind("battery.panel.usb_vbus"))
    }

    private val presentCheck = CheckBox().apply {
        id = "battery-present-check"
        textProperty().bind(I18n.bind("battery.panel.present"))
        isSelected = true
    }

    private val percentValueLabel = Label("100%").apply {
        id = "battery-percent-label"
        styleClass += "studio-telemetry-val"
    }

    private val percentSlider = Slider(0.0, 100.0, 100.0).apply {
        id = "battery-percent-slider"
        maxWidth = Double.MAX_VALUE
        blockIncrement = 5.0
    }

    private val summaryLabel = Label(I18n.t("battery.panel.waiting")).apply {
        id = "battery-status-summary"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    val root: VBox = VBox(
        6.0,
        Label().apply {
            textProperty().bind(I18n.bind("battery.panel.section"))
            styleClass += "studio-section-title"
        },
        FlowPane(10.0, 5.0, chargingCheck, usbVbusCheck, presentCheck),
        HBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("battery.panel.level"))
                styleClass += "studio-setting-label"
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            percentValueLabel,
        ).apply { alignment = Pos.CENTER_LEFT },
        percentSlider,
        summaryLabel,
    ).apply {
        id = "battery-charging-card"
        styleClass += "studio-card"
    }

    init {
        chargingCheck.setOnAction {
            if (syncing) return@setOnAction
            val on = chargingCheck.isSelected
            send(
                mapOf("charging" to on, "externalPowerConnected" to if (on) true else usbVbusCheck.isSelected),
                I18n.t(if (on) "battery.panel.status.charging_on" else "battery.panel.status.charging_off"),
            )
        }
        usbVbusCheck.setOnAction {
            if (syncing) return@setOnAction
            val on = usbVbusCheck.isSelected
            val patch = if (!on) {
                mapOf("externalPowerConnected" to false, "charging" to false)
            } else {
                mapOf("externalPowerConnected" to true)
            }
            send(
                patch,
                I18n.t(if (on) "battery.panel.status.vbus_on" else "battery.panel.status.vbus_off"),
            )
        }
        presentCheck.setOnAction {
            if (syncing) return@setOnAction
            val present = presentCheck.isSelected
            send(
                mapOf("present" to present),
                I18n.t(if (present) "battery.panel.status.present_on" else "battery.panel.status.present_off"),
            )
        }
        percentSlider.valueProperty().addListener { _, _, v ->
            percentValueLabel.text = "${v.toDouble().roundToInt()}%"
        }
        percentSlider.valueChangingProperty().addListener { _, wasChanging, changing ->
            if (!syncing && wasChanging && !changing) {
                val pct = percentSlider.value.roundToInt().toDouble()
                send(mapOf("chargePercent" to pct), I18n.t("battery.panel.status.percent", "${pct.roundToInt()}%"))
            }
        }
        percentSlider.setOnMouseReleased {
            if (!syncing) {
                val pct = percentSlider.value.roundToInt().toDouble()
                send(mapOf("chargePercent" to pct), I18n.t("battery.panel.status.percent", "${pct.roundToInt()}%"))
            }
        }
        I18n.onLocaleChange {
            val refresh = { summaryLabel.text = lastStatus.summaryText() }
            if (Platform.isFxApplicationThread()) refresh() else Platform.runLater(refresh)
        }
    }

    private fun send(patch: Map<String, Any?>, message: String) {
        val sender = applyPowerPatch ?: return
        sender(patch).whenComplete { updated, err ->
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

    fun observeStatusMap(raw: Map<String, Any?>?) {
        if (raw == null) return
        observedPowerRuntimeStatus = true
        applySnapshot(BatteryChargingStatusSnapshot.fromStatusMap(raw))
    }

    fun observeSnapshot(snapshot: Map<String, Any?>) {
        if (observedPowerRuntimeStatus) return
        val snap = BatteryChargingStatusSnapshot.fromHostSnapshot(snapshot) ?: return
        applySnapshot(snap)
    }

    private fun applySnapshot(st: BatteryChargingStatusSnapshot) {
        lastStatus = st
        syncing = true
        try {
            chargingCheck.isSelected = st.charging
            usbVbusCheck.isSelected = st.externalPowerConnected
            presentCheck.isSelected = st.present
            if (!percentSlider.isValueChanging) {
                percentSlider.value = st.chargePercent
                percentValueLabel.text = "${st.chargePercent.roundToInt()}%"
            }
            summaryLabel.text = st.summaryText()
        } finally {
            syncing = false
        }
    }
}
