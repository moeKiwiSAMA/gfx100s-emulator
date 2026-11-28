package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone Broadcom BCM43438A1 (BCM43430A1 Wi-Fi FullMAC SDIO + BT 4.1 BLE H4 UART) hardware
 * combo IC component with configurable real networking (`NAT` / `BRIDGE` / `HOST_ONLY`).
 *
 * Strictly adheres to datasheet-fidelity separation:
 * - Attaches to pure SoC UART (`soc-uart.uart1.serial` [UartSerialPort]) for Bluetooth H4 HCI;
 * - Attaches to pure SoC SDIO host (`soc-sdio.bus` [SdioBusPort]) for Wi-Fi SDIO FullMAC;
 * - Optionally observes `BT_REG_ON` / `WL_REG_ON` power pins via `soc-gpio.outputs` ([GpioOutputPort]);
 * - Always exposes `bcm43438.control` ([WirelessControlPort]) so Desktop UI, Console CLI, and HTTP
 *   `/api/wireless` can inspect or live-reconfigure Wi-Fi even when started with `--wifi-disabled`.
 */
class Bcm43438Factory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Chip(context, config)

    companion object {
        const val DEFAULT_BT_BD_ADDR = "02:46:4A:10:00:01"
        const val DEFAULT_WIFI_MAC = "02:46:4A:10:00:00"
        const val DEFAULT_DEVICE_NAME = "FujiEmu Host"
        const val DEFAULT_UART_PORT = "soc-uart.uart1.serial"
        const val DEFAULT_SDIO_PORT = "soc-sdio.bus"
        const val DEFAULT_BT_REG_ON_BANK = 12
        const val DEFAULT_BT_REG_ON_PIN = 3
        const val DEFAULT_WL_REG_ON_BANK = 12
        const val DEFAULT_WL_REG_ON_PIN = 0
        const val CONTROL_PORT = "bcm43438.control"

        private val DESCRIPTOR = ComponentDescriptor(
            id = "bcm43438",
            version = "0.0.2",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = true),
                Setting("btBdAddr", ValueKind.TEXT, DEFAULT_BT_BD_ADDR, live = false),
                Setting("wifiMacAddr", ValueKind.TEXT, DEFAULT_WIFI_MAC, live = true),
                Setting("sdioProdId", ValueKind.INTEGER, Bcm43430WifiController.DEFAULT_SDIO_PROD_ID.toLong(), minimum = 0.0, maximum = 65535.0, live = false),
                Setting("uartPort", ValueKind.TEXT, DEFAULT_UART_PORT, live = false),
                Setting("sdioPort", ValueKind.TEXT, DEFAULT_SDIO_PORT, live = false),
                Setting("btRegOnBank", ValueKind.INTEGER, DEFAULT_BT_REG_ON_BANK.toLong(), minimum = -1.0, maximum = 15.0, live = false),
                Setting("btRegOnPin", ValueKind.INTEGER, DEFAULT_BT_REG_ON_PIN.toLong(), minimum = -1.0, maximum = 15.0, live = false),
                Setting("wlRegOnBank", ValueKind.INTEGER, DEFAULT_WL_REG_ON_BANK.toLong(), minimum = -1.0, maximum = 15.0, live = false),
                Setting("wlRegOnPin", ValueKind.INTEGER, DEFAULT_WL_REG_ON_PIN.toLong(), minimum = -1.0, maximum = 15.0, live = false),
                Setting("deviceName", ValueKind.TEXT, DEFAULT_DEVICE_NAME, live = true),
                Setting("blePeerConnected", ValueKind.BOOLEAN, false, live = true),
                Setting("wifiPeerConnected", ValueKind.BOOLEAN, false, live = true),
                Setting("ptpIpBridgeEnabled", ValueKind.BOOLEAN, false, live = true),
                // Live Wi-Fi networking settings (NAT / BRIDGE / HOST_ONLY)
                Setting("wifiNetworkEnabled", ValueKind.BOOLEAN, true, live = true),
                Setting("wifiNetworkMode", ValueKind.TEXT, WifiNetworkMode.NAT.name, choices = listOf("NAT", "BRIDGE", "HOST_ONLY"), live = true),
                Setting("wifiSsid", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.ssid, live = true),
                Setting("wifiPassphrase", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.passphrase, live = true),
                Setting("wifiSubnetCidr", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.subnetCidr, live = true),
                Setting("wifiGatewayIp", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.gatewayIp, live = true),
                Setting("wifiGuestIp", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.guestIp, live = true),
                Setting("wifiDnsServers", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.dnsServers.joinToString(","), live = true),
                Setting("wifiBridgeInterface", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.bridgeInterface, live = true),
                Setting("wifiHostOnlyLoopbackForward", ValueKind.BOOLEAN, WifiNetworkConfig.DEFAULT.hostOnlyLoopbackForward, live = true),
                Setting("wifiHostOnlySubnetCidr", ValueKind.TEXT, WifiNetworkConfig.DEFAULT.hostOnlySubnetCidr, live = true),
                Setting("wifiPortForwards", ValueKind.TEXT, "", live = true),
            ),
        )

        private val WIFI_NETWORK_SCALAR_KEYS = setOf(
            "wifiNetworkEnabled",
            "wifiNetworkMode",
            "wifiSsid",
            "wifiPassphrase",
            "wifiMacAddr",
            "wifiSubnetCidr",
            "wifiGatewayIp",
            "wifiGuestIp",
            "wifiDnsServers",
            "wifiBridgeInterface",
            "wifiHostOnlyLoopbackForward",
            "wifiHostOnlySubnetCidr",
            "wifiPortForwards",
        )
    }

    private class Chip(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("bcm43438", context, config), StatefulComponent, WirelessControlPort {
        private var enabled = (config["enabled"] as? Boolean) ?: false
        private val btBdAddr = (config["btBdAddr"] as? String) ?: DEFAULT_BT_BD_ADDR
        private val initialWifiMac = (config["wifiMacAddr"] as? String) ?: DEFAULT_WIFI_MAC
        private val sdioProdId = ((config["sdioProdId"] as? Number)?.toInt()) ?: Bcm43430WifiController.DEFAULT_SDIO_PROD_ID
        private val uartPortName = (config["uartPort"] as? String) ?: DEFAULT_UART_PORT
        private val sdioPortName = (config["sdioPort"] as? String) ?: DEFAULT_SDIO_PORT
        private val btRegOnBank = ((config["btRegOnBank"] as? Number)?.toInt()) ?: DEFAULT_BT_REG_ON_BANK
        private val btRegOnPin = ((config["btRegOnPin"] as? Number)?.toInt()) ?: DEFAULT_BT_REG_ON_PIN
        private val wlRegOnBank = ((config["wlRegOnBank"] as? Number)?.toInt()) ?: DEFAULT_WL_REG_ON_BANK
        private val wlRegOnPin = ((config["wlRegOnPin"] as? Number)?.toInt()) ?: DEFAULT_WL_REG_ON_PIN

        private var deviceName: String = (config["deviceName"] as? String)?.ifBlank { DEFAULT_DEVICE_NAME } ?: DEFAULT_DEVICE_NAME
        private val extraBleNeighbors = mutableListOf<VirtualBleNeighbor>()
        private val extraWifiNeighbors = mutableListOf<VirtualWifiApNeighbor>()
        private var attachedUart: UartSerialPort? = null
        private var attachedSdio: SdioBusPort? = null
        private var gpioOutputs: GpioOutputPort? = null
        private var started = false
        private var closed = false

        private fun buildWifiConfigFromScalars(scalars: Map<String, Any>): WifiNetworkConfig {
            val mac = (scalars["wifiMacAddr"] as? String)?.ifBlank { initialWifiMac } ?: initialWifiMac
            return WifiNetworkConfig(
                enabled = enabled && ((scalars["wifiNetworkEnabled"] as? Boolean) ?: true),
                mode = WifiNetworkMode.parse(scalars["wifiNetworkMode"]?.toString()),
                ssid = (scalars["wifiSsid"] as? String)?.ifBlank { WifiNetworkConfig.DEFAULT.ssid } ?: WifiNetworkConfig.DEFAULT.ssid,
                passphrase = (scalars["wifiPassphrase"] as? String) ?: WifiNetworkConfig.DEFAULT.passphrase,
                macAddress = mac,
                subnetCidr = (scalars["wifiSubnetCidr"] as? String)?.ifBlank { WifiNetworkConfig.DEFAULT.subnetCidr }
                    ?: WifiNetworkConfig.DEFAULT.subnetCidr,
                gatewayIp = (scalars["wifiGatewayIp"] as? String)?.ifBlank { WifiNetworkConfig.DEFAULT.gatewayIp }
                    ?: WifiNetworkConfig.DEFAULT.gatewayIp,
                guestIp = (scalars["wifiGuestIp"] as? String)?.ifBlank { WifiNetworkConfig.DEFAULT.guestIp }
                    ?: WifiNetworkConfig.DEFAULT.guestIp,
                dnsServers = WifiNetworkConfig.parseDnsList(scalars["wifiDnsServers"], WifiNetworkConfig.DEFAULT.dnsServers),
                bridgeInterface = (scalars["wifiBridgeInterface"] as? String)?.trim() ?: "",
                hostOnlyLoopbackForward = (scalars["wifiHostOnlyLoopbackForward"] as? Boolean) ?: true,
                hostOnlySubnetCidr = (scalars["wifiHostOnlySubnetCidr"] as? String)?.ifBlank { WifiNetworkConfig.DEFAULT.hostOnlySubnetCidr }
                    ?: WifiNetworkConfig.DEFAULT.hostOnlySubnetCidr,
                portForwards = WifiPortForwardRule.normalizeSpecs(scalars["wifiPortForwards"]),
            )
        }

        private fun currentBleNeighbors(): List<VirtualBleNeighbor> = listOf(
            VirtualBleNeighbor(
                name = deviceName,
                bdAddr = Bcm43438BtController.formatMac6(Bcm43438BtController.VIRTUAL_PEER_BD_ADDR),
                rssi = -45,
                connectable = true,
            ),
        ) + extraBleNeighbors

        private fun currentWifiNeighbors(): List<VirtualWifiApNeighbor> = listOf(
            VirtualWifiApNeighbor(
                ssid = deviceName,
                bssid = "02:46:4A:48:00:01",
                channel = 6,
                rssi = -42,
                security = "WPA2",
                passphrase = "fujiemu123",
            ),
            VirtualWifiApNeighbor(
                ssid = "FujiEmu-AP-2.4G",
                bssid = "02:46:4A:48:00:02",
                channel = 11,
                rssi = -54,
                security = "OPEN",
                passphrase = "",
            ),
        ) + extraWifiNeighbors

        private val bt = Bcm43438BtController(
            initialBdAddr = Bcm43438BtController.parseMac6(btBdAddr),
            rxSink = { bytes -> attachedUart?.pushRxBytes(bytes) },
            peerNameProvider = { deviceName },
            neighborsProvider = { currentBleNeighbors() },
            beforeBusActivity = { syncRegOnPins() },
        )

        private val wifi = Bcm43430WifiController(
            initialMac = Bcm43438BtController.parseMac6(initialWifiMac),
            sdioProdId = sdioProdId,
            cardIrqSink = { asserted -> attachedSdio?.setCardInterrupt(asserted) },
            peerNameProvider = { deviceName },
            neighborsProvider = { currentWifiNeighbors() },
            beforeBusActivity = { syncRegOnPins() },
            initialNetworkConfig = buildWifiConfigFromScalars(values),
        )

        init {
            // Always register bcm43438.control so Desktop UI, Console CLI, and HTTP /api/wireless
            // can inspect or enable Wi-Fi live even when launched with --wifi-disabled / enabled=false.
            context.ports.register(id, CONTROL_PORT, WirelessControlPort::class.java, this)
        }

        private fun syncRegOnPins() {
            val gpio = gpioOutputs ?: return
            if (btRegOnBank in 0..15 && btRegOnPin in 0..15) {
                bt.setPowerPin(gpio.outputLevel(btRegOnBank, btRegOnPin))
            }
            if (wlRegOnBank in 0..15 && wlRegOnPin in 0..15) {
                wifi.setPowerPin(gpio.outputLevel(wlRegOnBank, wlRegOnPin))
            }
        }

        private fun attachHardwareBusesIfEnabled() {
            if (!started || closed) return
            if (enabled) {
                if (gpioOutputs == null) {
                    gpioOutputs = context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java)
                }
                if (attachedUart == null && uartPortName.isNotBlank()) {
                    attachedUart = context.ports.find(uartPortName, UartSerialPort::class.java)?.also { uart ->
                        uart.attachPeer(bt)
                    }
                }
                if (attachedSdio == null && sdioPortName.isNotBlank()) {
                    attachedSdio = context.ports.find(sdioPortName, SdioBusPort::class.java)?.also { sdio ->
                        sdio.attachCard(wifi)
                    }
                }
            } else {
                attachedUart?.detachPeer(bt)
                attachedSdio?.detachCard(wifi)
                attachedUart = null
                attachedSdio = null
            }
        }

        override fun start() {
            check(!closed) { "bcm43438 closed" }
            started = true
            if (!enabled) return
            attachHardwareBusesIfEnabled()
            if ((values["blePeerConnected"] as? Boolean) == true) {
                bt.connectVirtualPeer()
            }
            if ((values["wifiPeerConnected"] as? Boolean) == true) {
                wifi.virtualPeer.setConnected(true)
            }
            if ((values["ptpIpBridgeEnabled"] as? Boolean) == true) {
                wifi.virtualPeer.setHostTcpBridge(true)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            attachedUart?.detachPeer(bt)
            attachedSdio?.detachCard(wifi)
            attachedUart = null
            attachedSdio = null
            gpioOutputs = null
            wifi.close()
        }

        override fun validateSettings(settings: Map<String, Any>) {
            check(!closed) { "bcm43438 closed" }
            for ((key, value) in settings) {
                val setting = DESCRIPTOR.settings.singleOrNull { it.key == key }
                    ?: throw IllegalArgumentException("Unknown setting for bcm43438: $key")
                val validated = setting.validate(value)
                if (!setting.live && values[key] != validated) {
                    throw IllegalArgumentException("bcm43438 setting $key requires restart")
                }
                if (key == "wifiMacAddr") {
                    Bcm43438BtController.parseMac6(validated as String)
                }
                if (key == "wifiGatewayIp" || key == "wifiGuestIp") {
                    WifiNetworkEngine.parseIpv4Bytes(validated as String)
                }
                if (key == "wifiSubnetCidr" || key == "wifiHostOnlySubnetCidr") {
                    val base = (validated as String).substringBefore('/').trim()
                    WifiNetworkEngine.parseIpv4Bytes(base)
                }
                if (key == "wifiPortForwards") {
                    WifiPortForwardRule.parseList(validated)
                }
            }
        }

        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
            val updated = LinkedHashMap(values)
            var wifiConfigTouched = false
            for ((key, value) in settings) {
                val setting = DESCRIPTOR.settings.single { it.key == key }
                val validated = setting.validate(value)
                updated[key] = validated
                if (key in WIFI_NETWORK_SCALAR_KEYS || key == "enabled") {
                    wifiConfigTouched = true
                }
                when (key) {
                    "enabled" -> {
                        enabled = validated as Boolean
                        attachHardwareBusesIfEnabled()
                    }
                    "deviceName" -> deviceName = (validated as String).ifBlank { DEFAULT_DEVICE_NAME }
                    "blePeerConnected" -> if (enabled) {
                        if (validated as Boolean) bt.connectVirtualPeer() else bt.disconnectVirtualPeer()
                    }
                    "wifiPeerConnected" -> if (enabled) {
                        wifi.virtualPeer.setConnected(validated as Boolean)
                    }
                    "ptpIpBridgeEnabled" -> if (enabled) {
                        wifi.virtualPeer.setHostTcpBridge(validated as Boolean)
                    }
                }
            }
            values = updated
            if (wifiConfigTouched) {
                wifi.reconfigureNetwork(buildWifiConfigFromScalars(updated))
            }
        }

        override fun getNetworkConfig(): WifiNetworkConfig =
            buildWifiConfigFromScalars(values).copy(
                enabled = (values["wifiNetworkEnabled"] as? Boolean) ?: true,
                macAddress = wifi.macAddressString(),
            )

        override fun getNetworkStatus(): WifiNetworkStatus = wifi.getNetworkStatus()

        override fun reconfigureNetwork(config: WifiNetworkConfig): WifiNetworkStatus {
            check(!closed) { "bcm43438 closed" }
            if (config.enabled && !enabled) {
                applySettings(mapOf("enabled" to true) + config.toComponentSettingsPatch())
            } else {
                applySettings(config.toComponentSettingsPatch())
            }
            return wifi.getNetworkStatus()
        }

        override fun wirelessStatus(): Map<String, Any?> = state()

        override fun applyWirelessAction(payload: Map<String, Any?>): Map<String, Any?> {
            check(!closed) { "bcm43438 closed" }
            val action = payload["action"] as? String
            when (action) {
                null -> {
                    val patch = linkedMapOf<String, Any>()
                    for (s in DESCRIPTOR.settings) {
                        if (s.live && payload[s.key] != null) {
                            val raw = payload[s.key]!!
                            patch[s.key] = if (s.key == "wifiNetworkMode") {
                                WifiNetworkMode.parse(raw.toString()).name
                            } else {
                                raw
                            }
                        }
                    }
                    // Also accept WifiNetworkConfig field names directly on POST /api/wireless
                    payload["mode"]?.let { patch["wifiNetworkMode"] = WifiNetworkMode.parse(it.toString()).name }
                    payload["ssid"]?.let { if (it is String) patch["wifiSsid"] = it }
                    payload["passphrase"]?.let { if (it is String) patch["wifiPassphrase"] = it }
                    payload["macAddress"]?.let { if (it is String) patch["wifiMacAddr"] = it }
                    payload["subnetCidr"]?.let { if (it is String) patch["wifiSubnetCidr"] = it }
                    payload["gatewayIp"]?.let { if (it is String) patch["wifiGatewayIp"] = it }
                    payload["guestIp"]?.let { if (it is String) patch["wifiGuestIp"] = it }
                    payload["dnsServers"]?.let { patch["wifiDnsServers"] = WifiNetworkConfig.parseDnsList(it).joinToString(",") }
                    payload["bridgeInterface"]?.let { if (it is String) patch["wifiBridgeInterface"] = it }
                    payload["hostOnlyLoopbackForward"]?.let { if (it is Boolean) patch["wifiHostOnlyLoopbackForward"] = it }
                    payload["hostOnlySubnetCidr"]?.let { if (it is String) patch["wifiHostOnlySubnetCidr"] = it }
                    payload["portForwards"]?.let { patch["wifiPortForwards"] = WifiPortForwardRule.normalizeSpecs(it).joinToString(",") }
                    if (patch.isNotEmpty()) applySettings(patch)
                }
                "wifi.reconfigure" -> {
                    val next = WifiNetworkConfig.fromMap(payload, getNetworkConfig())
                    reconfigureNetwork(next)
                }
                "wifi.reset" -> {
                    reconfigureNetwork(WifiNetworkConfig.DEFAULT.copy(macAddress = initialWifiMac))
                }
                "wifi.status" -> return wirelessStatus()
                "wifi.hostInterfaces" -> {
                    val includeDown = (payload["includeDown"] as? Boolean) ?: false
                    val includeLoopback = (payload["includeLoopback"] as? Boolean) ?: false
                    return linkedMapOf(
                        "interfaces" to listHostNetworkInterfaces(includeDown, includeLoopback).map { it.toMap() },
                        "wifiNetwork" to buildMergedWifiNetworkMap(),
                    )
                }
                "ble.connect" -> {
                    val peerMac = payload["peerBdAddr"] as? String
                    bt.connectVirtualPeer(peerMac)
                    values = values + ("blePeerConnected" to bt.connected)
                }
                "ble.disconnect" -> {
                    bt.disconnectVirtualPeer()
                    values = values + ("blePeerConnected" to bt.connected)
                }
                "ble.readGapDeviceName" -> {
                    require(bt.connected) { "VirtualBlePeer must be connected to read GAP 0x2A00 Device Name" }
                    bt.requestGapDeviceName()
                }
                "ble.pair" -> {
                    require(bt.connected) { "VirtualBlePeer must be connected to initiate pairing" }
                    bt.initiateVirtualPairing()
                }
                "ble.timeSync" -> {
                    require(bt.connected) { "VirtualBlePeer must be connected to send time-sync" }
                    val year = (payload["year"] as? Number)?.toInt() ?: 2026
                    val month = (payload["month"] as? Number)?.toInt() ?: 11
                    val day = (payload["day"] as? Number)?.toInt() ?: 24
                    val hour = (payload["hour"] as? Number)?.toInt() ?: 12
                    val minute = (payload["minute"] as? Number)?.toInt() ?: 0
                    val second = (payload["second"] as? Number)?.toInt() ?: 0
                    bt.sendVirtualTimeSync(year, month, day, hour, minute, second)
                }
                "wifi.connectPeer" -> {
                    wifi.virtualPeer.setConnected(true)
                    values = values + ("wifiPeerConnected" to true)
                }
                "wifi.disconnectPeer" -> {
                    wifi.virtualPeer.setConnected(false)
                    values = values + ("wifiPeerConnected" to false)
                }
                "wifi.arpProbe" -> {
                    wifi.virtualPeer.setConnected(true)
                    wifi.virtualPeer.injectPeerArpProbe(Bcm43438BtController.parseMac6(wifi.macAddressString()))
                }
                "rf.addBleNeighbor" -> {
                    val name = (payload["name"] as? String)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("rf.addBleNeighbor requires non-blank name")
                    val mac = Bcm43438BtController.formatMac6(
                        Bcm43438BtController.parseMac6(payload["bdAddr"] as? String ?: "02:46:4A:42:00:%02X".format(extraBleNeighbors.size + 2)),
                    )
                    val rssi = (payload["rssi"] as? Number)?.toInt() ?: -50
                    extraBleNeighbors.removeAll { it.bdAddr.equals(mac, ignoreCase = true) }
                    extraBleNeighbors += VirtualBleNeighbor(name = name, bdAddr = mac, rssi = rssi)
                }
                "rf.addWifiNeighbor" -> {
                    val ssid = (payload["ssid"] as? String)?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("rf.addWifiNeighbor requires non-blank ssid")
                    val bssid = Bcm43438BtController.formatMac6(
                        Bcm43438BtController.parseMac6(payload["bssid"] as? String ?: "02:46:4A:48:00:%02X".format(extraWifiNeighbors.size + 3)),
                    )
                    val ch = ((payload["channel"] as? Number)?.toInt() ?: 1).coerceIn(1, 13)
                    val rssi = (payload["rssi"] as? Number)?.toInt() ?: -48
                    val sec = (payload["security"] as? String)?.uppercase() ?: "WPA2"
                    extraWifiNeighbors.removeAll { it.bssid.equals(bssid, ignoreCase = true) }
                    extraWifiNeighbors += VirtualWifiApNeighbor(ssid = ssid, bssid = bssid, channel = ch, rssi = rssi, security = sec)
                }
                else -> throw IllegalArgumentException("Unsupported wireless action: $action")
            }
            return wirelessStatus()
        }

        private fun buildMergedWifiNetworkMap(): Map<String, Any?> {
            val cfg = getNetworkConfig()
            val st = wifi.getNetworkStatus()
            val merged = LinkedHashMap<String, Any?>()
            merged.putAll(cfg.toMap())
            merged.putAll(st.toMap())
            merged["ssid"] = cfg.ssid
            merged["enabled"] = enabled && cfg.enabled
            return merged
        }

        override fun state(): Map<String, Any?> {
            val wifiNetMap = buildMergedWifiNetworkMap()
            if (!enabled) {
                return super.state() + linkedMapOf(
                    "enabled" to false,
                    "wifiNetwork" to wifiNetMap,
                    "hostInterfaces" to listHostNetworkInterfaces().map { it.toMap() },
                )
            }
            syncRegOnPins()
            val btState = bt.state()
            val wifiState = wifi.state()
            val netStatus = wifi.getNetworkStatus()
            return super.state() + linkedMapOf(
                "enabled" to true,
                "chip" to "BCM43438A1 / BCM43430A1",
                "deviceName" to deviceName,
                "uartAttached" to (attachedUart != null),
                "sdioAttached" to (attachedSdio != null),
                "powerOn" to bt.powerOn,
                "patchRamLoaded" to bt.patchRamLoaded,
                "advertising" to bt.advertising,
                "advertisedName" to bt.effectiveAdvertisedName(),
                "connected" to bt.connected,
                "bdAddr" to bt.bdAddrString(),
                "hciCmdCount" to bt.hciCmdCount,
                "wifiPowerOn" to wifi.wifiPowerOn,
                "firmwareLoaded" to wifi.firmwareLoaded,
                "mode" to wifi.mode,
                "ssid" to wifi.ssid,
                "macAddress" to wifi.macAddressString(),
                "ipAddress" to wifi.virtualPeer.observedCameraIp.ifEmpty { netStatus.guestIp },
                "connectedClients" to if (wifi.virtualPeer.connected || netStatus.linkUp) 1 else 0,
                "ptpIpReady" to wifi.virtualPeer.ptpIpReady,
                "wifiNetwork" to wifiNetMap,
                "bluetooth" to btState,
                "wifi" to wifiState,
            )
        }

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "bcm43438 closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to enabled,
                "bt" to bt.saveSnapshot(),
                "wifi" to wifi.saveSnapshot(),
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "bcm43438 closed" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val snapEnabled = (data["enabled"] as? Boolean) ?: enabled
            val rawScalars = data["scalars"] as? Map<*, *>
                ?: throw IllegalArgumentException("Missing scalars in bcm43438 snapshot")
            restoreScalarValues(rawScalars, DESCRIPTOR)
            enabled = snapEnabled
            attachHardwareBusesIfEnabled()
            deviceName = (values["deviceName"] as? String)?.ifBlank { DEFAULT_DEVICE_NAME } ?: DEFAULT_DEVICE_NAME
            wifi.reconfigureNetwork(buildWifiConfigFromScalars(values))
            @Suppress("UNCHECKED_CAST")
            (data["bt"] as? Map<String, Any?>)?.let { bt.restoreSnapshot(it) }
            @Suppress("UNCHECKED_CAST")
            (data["wifi"] as? Map<String, Any?>)?.let { wifi.restoreSnapshot(it) }
        }
    }
}
