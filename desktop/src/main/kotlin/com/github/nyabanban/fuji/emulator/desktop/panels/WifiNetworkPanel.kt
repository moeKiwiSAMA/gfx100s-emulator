package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import com.github.nyabanban.fuji.emulator.api.WifiNetworkMode
import com.github.nyabanban.fuji.emulator.api.WifiNetworkStatus
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.wifi.WifiNetworkValidation
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import java.util.Locale
import java.util.concurrent.CompletableFuture

data class WifiAdapterChoice(
    val id: String,
    val label: String,
    val virtualOrTap: Boolean = false,
) {
    companion object {
        val AUTO: WifiAdapterChoice
            get() = WifiAdapterChoice("", I18n.t("wifi.bridge.auto_adapter"))

        fun fromInterface(info: HostNetworkInterfaceInfo): WifiAdapterChoice =
            WifiAdapterChoice(
                id = info.name,
                label = info.summaryLabel(),
                virtualOrTap = info.virtualOrTap,
            )

        fun buildChoices(
            interfaces: List<HostNetworkInterfaceInfo>,
            configuredId: String = "",
        ): List<WifiAdapterChoice> {
            val list = ArrayList<WifiAdapterChoice>(interfaces.size + 2)
            list += AUTO
            interfaces.mapTo(list, ::fromInterface)
            if (configuredId.isNotBlank() && list.none { it.id.equals(configuredId, ignoreCase = true) }) {
                list += WifiAdapterChoice(configuredId, configuredId)
            }
            return list
        }
    }
}

data class WifiPanelSnapshot(
    val config: WifiNetworkConfig,
    val status: WifiNetworkStatus,
    val effectiveDnsServers: List<String>,
    val portForwards: List<String> = emptyList(),
    val isWindows: Boolean = false,
    val npcapInstalled: Boolean = false,
    val tapWindowsInstalled: Boolean = false,
    val tapAdapterName: String = "",
) {
    val activeEnabled: Boolean
        get() = config.enabled && status.enabled

    val activeSubnetCidr: String
        get() = if (config.mode == WifiNetworkMode.HOST_ONLY) config.hostOnlySubnetCidr else config.subnetCidr

    fun bridgeBackendStatusText(): String {
        val friendly = bridgeBackendFriendlyName(status.bridgeBackend)
        return I18n.t("wifi.bridge.backend_status", friendly, status.bridgeState)
    }

    fun bridgeHintText(): String = when {
        isWindows && npcapInstalled -> I18n.t("wifi.bridge.win_hint_npcap")
        isWindows && tapWindowsInstalled -> I18n.t("wifi.bridge.win_hint_tap", tapAdapterName.ifBlank { "TAP-Windows6" })
        isWindows -> I18n.t("wifi.bridge.win_hint_fallback")
        else -> I18n.t("wifi.bridge.unix_hint")
    }

    fun linkSummaryText(): String {
        val badge = when {
            !activeEnabled -> I18n.t("wifi.telemetry.link_disabled")
            status.linkUp -> I18n.t("wifi.telemetry.link_up")
            else -> I18n.t("wifi.telemetry.link_down")
        }
        val modeLabel = modeDisplayLabel(status.mode)
        val ssid = status.associatedSsid.ifBlank { config.ssid }.ifBlank { "-" }
        val mac = status.macAddress.ifBlank { config.macAddress }.ifBlank { "-" }
        return I18n.t("wifi.telemetry.summary_link", badge, modeLabel, ssid, mac)
    }

    fun addressingSummaryText(): String {
        val ip = status.guestIp.ifBlank { if (activeEnabled) config.guestIp else "-" }
        val gw = status.gatewayIp.ifBlank { if (activeEnabled) config.gatewayIp else "-" }
        val dns = effectiveDnsServers.ifEmpty { config.dnsServers }.joinToString(", ").ifBlank { "-" }
        return I18n.t("wifi.telemetry.summary_ip", ip, gw, dns)
    }

    fun trafficSummaryText(): String {
        val tx = "${status.txPackets} (${formatByteCount(status.txBytes)})"
        val rx = "${status.rxPackets} (${formatByteCount(status.rxBytes)})"
        return I18n.t(
            "wifi.telemetry.summary_traffic",
            status.activeTcpSessions.toString(),
            status.activeUdpSessions.toString(),
            tx,
            rx,
        )
    }

    companion object {
        val INITIAL: WifiPanelSnapshot = WifiPanelSnapshot(
            config = WifiNetworkConfig.DEFAULT,
            status = WifiNetworkStatus(
                mode = WifiNetworkMode.NAT,
                linkUp = false,
                associatedSsid = "",
                guestIp = WifiNetworkConfig.DEFAULT.guestIp,
                gatewayIp = WifiNetworkConfig.DEFAULT.gatewayIp,
                macAddress = "02:46:4A:10:00:00",
                bridgeInterface = "",
                bridgeState = "INACTIVE",
                activeTcpSessions = 0,
                activeUdpSessions = 0,
                txPackets = 0L,
                rxPackets = 0L,
                txBytes = 0L,
                rxBytes = 0L,
                lastError = null,
            ),
            effectiveDnsServers = WifiNetworkConfig.DEFAULT.dnsServers,
        )

        fun modeDisplayLabel(mode: WifiNetworkMode): String = when (mode) {
            WifiNetworkMode.NAT -> I18n.t("wifi.mode.nat")
            WifiNetworkMode.BRIDGE -> I18n.t("wifi.mode.bridge")
            WifiNetworkMode.HOST_ONLY -> I18n.t("wifi.mode.host_only")
        }

        fun bridgeBackendFriendlyName(raw: String): String = when (raw.trim().uppercase(Locale.ROOT)) {
            "", "NONE" -> "-"
            "NPCAP_L2" -> "Npcap L2"
            "WINDOWS_TAP_L2", "TAP_WINDOWS6_L2" -> "TAP-Windows6 L2"
            "WINPCAP_L2" -> "WinPcap L2"
            "LINUX_TAP_L2", "LINUX_TAP_OR_NIC_BOUND" -> "Linux TAP L2"
            "MACOS_PCAP_L2" -> "macOS BPF/pcap L2"
            "NIC_BOUND_FALLBACK", "NIC_BOUND_SOCKET" -> "NIC-Bound Fallback"
            "CUSTOM_L2" -> "Custom L2"
            else -> raw
        }

        fun formatByteCount(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            return when {
                b < 1024L -> "$b B"
                b < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KiB", b / 1024.0)
                else -> String.format(Locale.ROOT, "%.2f MiB", b / (1024.0 * 1024.0))
            }
        }

        fun fromMap(raw: Map<String, Any?>?): WifiPanelSnapshot {
            if (raw == null) return INITIAL
            val cfgMap = (raw["config"] as? Map<*, *>) ?: (raw["wifiNetwork"] as? Map<*, *>) ?: raw
            val stMap = (raw["status"] as? Map<*, *>) ?: (raw["wifiNetwork"] as? Map<*, *>) ?: raw
            val envMap = raw["bridgeEnvironment"] as? Map<*, *>
            val cfg = WifiNetworkConfig.fromMap(cfgMap)
            val st = WifiNetworkStatus.fromMap(stMap)
            val dns = WifiNetworkConfig.parseDnsList(
                raw["effectiveDnsServers"],
                st.dnsServers.ifEmpty { cfg.dnsServers },
            )
            val forwards = runCatching {
                WifiNetworkValidation.parseAndValidatePortForwards(
                    raw["portForwards"] ?: cfgMap["portForwards"] ?: cfgMap["wifiPortForwards"],
                )
            }.getOrDefault(emptyList())
            return WifiPanelSnapshot(
                config = cfg,
                status = st,
                effectiveDnsServers = dns,
                portForwards = forwards,
                isWindows = envMap?.get("isWindows") == true,
                npcapInstalled = envMap?.get("npcapInstalled") == true,
                tapWindowsInstalled = envMap?.get("tapWindowsInstalled") == true,
                tapAdapterName = (envMap?.get("tapAdapterName") as? String).orEmpty(),
            )
        }
    }
}

/**
 * Interactive Wi-Fi Network Mode (`NAT` / `BRIDGE` / `HOST_ONLY`), Host Bridge Adapter,
 * SSID/Subnet/DNS Configuration, and Live Telemetry Panel mounted in the Studio sidebar.
 */
class WifiNetworkPanel(
    private val fetchStatus: () -> Map<String, Any?>,
    private val fetchInterfaces: () -> List<HostNetworkInterfaceInfo>,
    private val applyPatchAsync: (Map<String, Any?>) -> CompletableFuture<Map<String, Any?>>,
    private val resetAsync: () -> CompletableFuture<Map<String, Any?>>,
    private val onStatus: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit = {},
) {
    val root: VBox = VBox(8.0).apply {
        padding = Insets(8.0)
        styleClass += "camera-studio"
    }

    private var syncing = false
    private var observedStatus = false
    private var interfaces: List<HostNetworkInterfaceInfo> = emptyList()
    private var lastSnapshot: WifiPanelSnapshot = WifiPanelSnapshot.INITIAL
    private var lastObservedSsid: String = ""
    private var lastObservedPassphrase: String = ""
    private var lastObservedSubnet: String = ""
    private var lastObservedDns: String = ""
    private var lastObservedForwards: String = ""
    private var lastObservedCustomBridge: String = ""

    private val enableCheck = CheckBox().apply {
        id = "wifi-enable-check"
        textProperty().bind(I18n.bind("wifi.mode.enable"))
        isSelected = true
    }

    private val modeCombo = ComboBox<WifiNetworkMode>().apply {
        id = "wifi-mode-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        items.setAll(WifiNetworkMode.entries)
        converter = object : StringConverter<WifiNetworkMode>() {
            override fun toString(item: WifiNetworkMode?): String =
                if (item == null) "" else WifiPanelSnapshot.modeDisplayLabel(item)
            override fun fromString(string: String?): WifiNetworkMode =
                WifiNetworkMode.entries.firstOrNull { WifiPanelSnapshot.modeDisplayLabel(it) == string }
                    ?: WifiNetworkMode.NAT
        }
        setCellFactory {
            object : ListCell<WifiNetworkMode>() {
                override fun updateItem(item: WifiNetworkMode?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else WifiPanelSnapshot.modeDisplayLabel(item)
                }
            }
        }
        value = WifiNetworkMode.NAT
    }

    private val refreshNicsBtn = Button().apply {
        id = "wifi-refresh-nics-btn"
        textProperty().bind(I18n.bind("wifi.action.refresh_nics"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
    }

    private val resetBtn = Button().apply {
        id = "wifi-reset-btn"
        textProperty().bind(I18n.bind("wifi.action.reset"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
    }

    private val bridgeCombo = ComboBox<WifiAdapterChoice>().apply {
        id = "wifi-bridge-combo"
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        converter = object : StringConverter<WifiAdapterChoice>() {
            override fun toString(item: WifiAdapterChoice?): String = item?.label.orEmpty()
            override fun fromString(string: String?): WifiAdapterChoice = WifiAdapterChoice.AUTO
        }
        setCellFactory {
            object : ListCell<WifiAdapterChoice>() {
                override fun updateItem(item: WifiAdapterChoice?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.label
                }
            }
        }
    }

    private val bridgeCustomField = TextField().apply {
        id = "wifi-bridge-custom-field"
        promptText = I18n.t("wifi.bridge.custom_prompt")
        maxWidth = Double.MAX_VALUE
    }

    private val bridgeStatusLabel = Label(I18n.t("wifi.telemetry.waiting")).apply {
        id = "wifi-bridge-status-label"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    private val bridgeHintLabel = Label().apply {
        id = "wifi-bridge-hint-label"
        styleClass += "studio-hint-text"
        isWrapText = true
    }

    private val ssidField = TextField(WifiNetworkConfig.DEFAULT.ssid).apply {
        id = "wifi-ssid-field"
        maxWidth = Double.MAX_VALUE
    }

    private val passphraseField = TextField("").apply {
        id = "wifi-passphrase-field"
        promptText = I18n.t("wifi.config.passphrase.prompt")
        maxWidth = Double.MAX_VALUE
    }

    private val subnetField = TextField(WifiNetworkConfig.DEFAULT.subnetCidr).apply {
        id = "wifi-subnet-field"
        maxWidth = Double.MAX_VALUE
    }

    private val dnsField = TextField(WifiNetworkConfig.DEFAULT.dnsServers.joinToString(", ")).apply {
        id = "wifi-dns-field"
        maxWidth = Double.MAX_VALUE
    }

    private val portForwardsField = TextField("").apply {
        id = "wifi-port-forwards-field"
        promptText = I18n.t("wifi.config.port_forwards.prompt")
        maxWidth = Double.MAX_VALUE
    }

    private val hostOnlyLoopbackCheck = CheckBox().apply {
        id = "wifi-hostonly-loopback-check"
        textProperty().bind(I18n.bind("wifi.config.hostonly_loopback"))
        isSelected = true
    }

    private val applySettingsBtn = Button().apply {
        id = "wifi-apply-btn"
        textProperty().bind(I18n.bind("wifi.action.apply_settings"))
        styleClass += "studio-chip-btn"
        minWidth = Region.USE_PREF_SIZE
    }

    private val linkTelemetryLabel = Label(I18n.t("wifi.telemetry.waiting")).apply {
        id = "wifi-telemetry-link"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    private val ipTelemetryLabel = Label("").apply {
        id = "wifi-telemetry-ip"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    private val trafficTelemetryLabel = Label("").apply {
        id = "wifi-telemetry-traffic"
        styleClass += "studio-control-status"
        isWrapText = true
    }

    private val errorTelemetryLabel = Label("").apply {
        id = "wifi-telemetry-error"
        styleClass += "studio-control-status"
        isWrapText = true
        isVisible = false
        isManaged = false
    }

    init {
        buildLayout()
        wireActions()
        refreshLocalizedDynamicText()
        I18n.onLocaleChange {
            if (Platform.isFxApplicationThread()) refreshLocalizedDynamicText()
            else Platform.runLater(::refreshLocalizedDynamicText)
        }
    }

    private fun buildLayout() {
        val modeCard = VBox(
            6.0,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("wifi.mode.section"))
                    styleClass += "studio-section-title"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                refreshNicsBtn,
                resetBtn,
            ).apply { alignment = Pos.CENTER_LEFT },
            FlowPane(10.0, 5.0, enableCheck),
            Label().apply {
                textProperty().bind(I18n.bind("wifi.mode.label"))
                styleClass += "studio-setting-label"
            },
            modeCombo,
        ).apply { styleClass += "studio-card" }

        val bridgeCard = VBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.bridge.section"))
                styleClass += "studio-section-title"
            },
            Label().apply {
                textProperty().bind(I18n.bind("wifi.bridge.adapter"))
                styleClass += "studio-setting-label"
            },
            bridgeCombo,
            bridgeCustomField,
            bridgeStatusLabel,
            bridgeHintLabel,
        ).apply { styleClass += "studio-card" }

        val configCard = VBox(
            6.0,
            HBox(
                6.0,
                Label().apply {
                    textProperty().bind(I18n.bind("wifi.config.section"))
                    styleClass += "studio-section-title"
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                applySettingsBtn,
            ).apply { alignment = Pos.CENTER_LEFT },
            Label().apply {
                textProperty().bind(I18n.bind("wifi.config.ssid"))
                styleClass += "studio-setting-label"
            },
            ssidField,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.config.passphrase"))
                styleClass += "studio-setting-label"
            },
            passphraseField,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.config.subnet"))
                styleClass += "studio-setting-label"
            },
            subnetField,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.config.dns"))
                styleClass += "studio-setting-label"
            },
            dnsField,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.config.port_forwards"))
                styleClass += "studio-setting-label"
            },
            portForwardsField,
            hostOnlyLoopbackCheck,
        ).apply { styleClass += "studio-card" }

        val telemetryCard = VBox(
            6.0,
            Label().apply {
                textProperty().bind(I18n.bind("wifi.telemetry.section"))
                styleClass += "studio-section-title"
            },
            linkTelemetryLabel,
            ipTelemetryLabel,
            trafficTelemetryLabel,
            errorTelemetryLabel,
        ).apply { styleClass += "studio-card" }

        root.children.addAll(modeCard, bridgeCard, configCard, telemetryCard)
    }

    private fun wireActions() {
        refreshNicsBtn.setOnAction { refreshInterfacesAsync() }

        resetBtn.setOnAction {
            resetAsync().whenComplete { updated, err ->
                Platform.runLater {
                    if (err != null) {
                        onError(err)
                    } else {
                        if (updated != null) observeStatusMap(updated, forceEditorSync = true)
                        onStatus(I18n.t("wifi.status.reset_done"))
                    }
                }
            }
        }

        enableCheck.setOnAction {
            if (syncing) return@setOnAction
            val enabled = enableCheck.isSelected
            sendPatch(
                mapOf("enabled" to enabled),
                I18n.t(if (enabled) "wifi.status.enabled" else "wifi.status.disabled"),
            )
        }

        modeCombo.setOnAction {
            if (syncing) return@setOnAction
            val chosen = modeCombo.value ?: return@setOnAction
            sendPatch(
                mapOf("mode" to chosen.name),
                I18n.t("wifi.status.mode_switched", WifiPanelSnapshot.modeDisplayLabel(chosen)),
                forceEditorSync = true,
            )
        }

        bridgeCombo.setOnAction {
            if (syncing) return@setOnAction
            val chosen = bridgeCombo.value ?: return@setOnAction
            syncing = true
            try {
                bridgeCustomField.text = chosen.id
                lastObservedCustomBridge = chosen.id
            } finally {
                syncing = false
            }
            sendPatch(
                mapOf("bridgeInterface" to chosen.id),
                I18n.t("wifi.status.bridge_selected", chosen.label),
            )
        }

        bridgeCustomField.setOnAction { applyFormSettings() }
        ssidField.setOnAction { applyFormSettings() }
        passphraseField.setOnAction { applyFormSettings() }
        subnetField.setOnAction { applyFormSettings() }
        dnsField.setOnAction { applyFormSettings() }
        portForwardsField.setOnAction { applyFormSettings() }

        hostOnlyLoopbackCheck.setOnAction {
            if (syncing) return@setOnAction
            applyFormSettings()
        }

        applySettingsBtn.setOnAction { applyFormSettings() }
    }

    private fun applyFormSettings() {
        if (syncing) return
        val patch = try {
            val ssid = ssidField.text.trim()
            require(ssid.isNotEmpty() && ssid.toByteArray(Charsets.UTF_8).size <= 32) {
                "SSID must be 1..32 UTF-8 bytes"
            }
            val parsedSubnet = WifiNetworkValidation.validateIpv4Cidr(subnetField.text, "subnetCidr")
            val parsedDns = WifiNetworkValidation.parseAndValidateDnsServers(dnsField.text, "dnsServers")
            val parsedForwards = WifiNetworkValidation.parseAndValidatePortForwards(portForwardsField.text, "portForwards")
            val bridgeIface = WifiNetworkValidation.normalizeBridgeInterfaceInput(bridgeCustomField.text)
            val mode = modeCombo.value ?: lastSnapshot.config.mode
            val subnetKey = if (mode == WifiNetworkMode.HOST_ONLY) "hostOnlySubnetCidr" else "subnetCidr"
            linkedMapOf<String, Any?>(
                "enabled" to enableCheck.isSelected,
                "mode" to mode.name,
                "ssid" to ssid,
                "passphrase" to passphraseField.text,
                "bridgeInterface" to bridgeIface,
                subnetKey to parsedSubnet.canonicalCidr,
                "dnsServers" to parsedDns,
                "portForwards" to parsedForwards,
                "hostOnlyLoopbackForward" to hostOnlyLoopbackCheck.isSelected,
            )
        } catch (err: Throwable) {
            onError(err)
            return
        }
        sendPatch(
            patch,
            I18n.t("wifi.status.settings_applied", patch["ssid"].toString(), WifiPanelSnapshot.modeDisplayLabel(modeCombo.value ?: WifiNetworkMode.NAT)),
            forceEditorSync = true,
        )
    }

    private fun sendPatch(
        patch: Map<String, Any?>,
        message: String,
        forceEditorSync: Boolean = false,
    ) {
        applyPatchAsync(patch).whenComplete { updated, err ->
            Platform.runLater {
                if (err != null) {
                    onError(err)
                } else {
                    if (updated != null) observeStatusMap(updated, forceEditorSync = forceEditorSync)
                    onStatus(message)
                }
            }
        }
    }

    fun refreshInterfacesAsync() {
        CompletableFuture.supplyAsync {
            val nics = fetchInterfaces()
            val st = fetchStatus()
            nics to st
        }.whenComplete { pair, err ->
            Platform.runLater {
                if (err != null) {
                    onError(err)
                } else if (pair != null) {
                    observeInterfaces(pair.first)
                    observeStatusMap(pair.second)
                }
            }
        }
    }

    fun observeInterfaces(discovered: List<HostNetworkInterfaceInfo>) {
        interfaces = discovered
        syncBridgeChoices(lastSnapshot.config.bridgeInterface)
    }

    fun observeStatusMap(raw: Map<String, Any?>?, forceEditorSync: Boolean = false) {
        observedStatus = true
        val snap = WifiPanelSnapshot.fromMap(raw)
        lastSnapshot = snap
        syncing = true
        try {
            enableCheck.isSelected = snap.config.enabled
            modeCombo.value = snap.config.mode
            hostOnlyLoopbackCheck.isSelected = snap.config.hostOnlyLoopbackForward
            syncBridgeChoices(snap.config.bridgeInterface)

            syncFieldIfUnedited(ssidField, snap.config.ssid, lastObservedSsid, forceEditorSync)
            lastObservedSsid = snap.config.ssid

            syncFieldIfUnedited(passphraseField, snap.config.passphrase, lastObservedPassphrase, forceEditorSync)
            lastObservedPassphrase = snap.config.passphrase

            syncFieldIfUnedited(subnetField, snap.activeSubnetCidr, lastObservedSubnet, forceEditorSync)
            lastObservedSubnet = snap.activeSubnetCidr

            val dnsText = snap.config.dnsServers.joinToString(", ")
            syncFieldIfUnedited(dnsField, dnsText, lastObservedDns, forceEditorSync)
            lastObservedDns = dnsText

            val forwardsText = snap.portForwards.joinToString(", ")
            syncFieldIfUnedited(portForwardsField, forwardsText, lastObservedForwards, forceEditorSync)
            lastObservedForwards = forwardsText

            syncFieldIfUnedited(bridgeCustomField, snap.config.bridgeInterface, lastObservedCustomBridge, forceEditorSync)
            lastObservedCustomBridge = snap.config.bridgeInterface
        } finally {
            syncing = false
        }
        renderTelemetryLabels(snap)
    }

    private fun syncFieldIfUnedited(field: TextField, nextValue: String, previousObserved: String, force: Boolean) {
        if (force || (!field.isFocused && (field.text == previousObserved || nextValue != previousObserved))) {
            field.text = nextValue
        }
    }

    private fun syncBridgeChoices(configuredId: String) {
        val wasSyncing = syncing
        syncing = true
        try {
            val choices = WifiAdapterChoice.buildChoices(interfaces, configuredId)
            bridgeCombo.items.setAll(choices)
            bridgeCombo.value = choices.firstOrNull { it.id.equals(configuredId, ignoreCase = true) }
                ?: choices.firstOrNull()
        } finally {
            syncing = wasSyncing
        }
    }

    private fun refreshLocalizedDynamicText() {
        syncing = true
        try {
            val curMode = modeCombo.value ?: WifiNetworkMode.NAT
            modeCombo.items.setAll(WifiNetworkMode.entries)
            modeCombo.value = curMode
            bridgeCustomField.promptText = I18n.t("wifi.bridge.custom_prompt")
            passphraseField.promptText = I18n.t("wifi.config.passphrase.prompt")
            portForwardsField.promptText = I18n.t("wifi.config.port_forwards.prompt")
            syncBridgeChoices(lastSnapshot.config.bridgeInterface)
        } finally {
            syncing = false
        }
        if (observedStatus) {
            renderTelemetryLabels(lastSnapshot)
        } else {
            bridgeStatusLabel.text = I18n.t("wifi.telemetry.waiting")
            bridgeHintLabel.text = lastSnapshot.bridgeHintText()
            linkTelemetryLabel.text = I18n.t("wifi.telemetry.waiting")
        }
    }

    private fun renderTelemetryLabels(snap: WifiPanelSnapshot) {
        bridgeStatusLabel.text = snap.bridgeBackendStatusText()
        bridgeHintLabel.text = snap.bridgeHintText()
        linkTelemetryLabel.text = snap.linkSummaryText()
        ipTelemetryLabel.text = snap.addressingSummaryText()
        trafficTelemetryLabel.text = snap.trafficSummaryText()
        val err = snap.status.lastError?.takeIf { it.isNotBlank() }
        if (err != null) {
            errorTelemetryLabel.text = I18n.t("wifi.telemetry.last_error", err)
            errorTelemetryLabel.isVisible = true
            errorTelemetryLabel.isManaged = true
        } else {
            errorTelemetryLabel.text = ""
            errorTelemetryLabel.isVisible = false
            errorTelemetryLabel.isManaged = false
        }
    }
}
