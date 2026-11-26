package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Standalone Broadcom BCM43438A1 (BCM43430A1 Wi-Fi FullMAC SDIO + BT 4.1 BLE H4 UART) hardware
 * combo IC component.
 *
 * Strictly adheres to datasheet-fidelity separation:
 * - Attaches to pure SoC UART (`soc-uart.uart1.serial` [UartSerialPort]) for Bluetooth H4 HCI;
 * - Attaches to pure SoC SDIO host (`soc-sdio.bus` [SdioBusPort]) for Wi-Fi SDIO FullMAC;
 * - Optionally observes `BT_REG_ON` / `WL_REG_ON` power pins via `soc-gpio.outputs` ([GpioOutputPort]);
 * - Never hardcodes camera names inside the chip: faithfully advertises/broadcasts whatever Local
 *   Name / AD payload / SSID / MAC the guest firmware programs, while external virtual peers
 *   (`VirtualBlePeer` / `VirtualWifiPeer`) identify themselves with configurable `deviceName`
 *   (default `"FujiEmu Host"`).
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
            version = "0.0.1",
            settings = listOf(
                Setting("enabled", ValueKind.BOOLEAN, false, live = false),
                Setting("btBdAddr", ValueKind.TEXT, DEFAULT_BT_BD_ADDR, live = false),
                Setting("wifiMacAddr", ValueKind.TEXT, DEFAULT_WIFI_MAC, live = false),
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
            ),
        )
    }

    private class Chip(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("bcm43438", context, config), StatefulComponent, WirelessControlPort {
        private val enabled = (config["enabled"] as? Boolean) ?: false
        private val btBdAddr = (config["btBdAddr"] as? String) ?: DEFAULT_BT_BD_ADDR
        private val wifiMacAddr = (config["wifiMacAddr"] as? String) ?: DEFAULT_WIFI_MAC
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
        private var closed = false

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
            initialMac = Bcm43438BtController.parseMac6(wifiMacAddr),
            sdioProdId = sdioProdId,
            cardIrqSink = { asserted -> attachedSdio?.setCardInterrupt(asserted) },
            peerNameProvider = { deviceName },
            neighborsProvider = { currentWifiNeighbors() },
            beforeBusActivity = { syncRegOnPins() },
        )

        init {
            if (enabled) {
                context.ports.register(id, CONTROL_PORT, WirelessControlPort::class.java, this)
            }
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

        override fun start() {
            check(!closed) { "bcm43438 closed" }
            if (!enabled) return
            gpioOutputs = context.ports.find("soc-gpio.outputs", GpioOutputPort::class.java)
            if (uartPortName.isNotBlank()) {
                attachedUart = context.ports.find(uartPortName, UartSerialPort::class.java)?.also { uart ->
                    uart.attachPeer(bt)
                }
            }
            if (sdioPortName.isNotBlank()) {
                attachedSdio = context.ports.find(sdioPortName, SdioBusPort::class.java)?.also { sdio ->
                    sdio.attachCard(wifi)
                }
            }
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
            }
        }

        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
            val updated = LinkedHashMap(values)
            for ((key, value) in settings) {
                val setting = DESCRIPTOR.settings.single { it.key == key }
                val validated = setting.validate(value)
                updated[key] = validated
                when (key) {
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
        }

        override fun wirelessStatus(): Map<String, Any?> = state()

        override fun applyWirelessAction(payload: Map<String, Any?>): Map<String, Any?> {
            check(!closed) { "bcm43438 closed" }
            val action = payload["action"] as? String
            when (action) {
                null -> {
                    val patch = linkedMapOf<String, Any>()
                    payload["deviceName"]?.let { patch["deviceName"] = it }
                    payload["blePeerConnected"]?.let { patch["blePeerConnected"] = it }
                    payload["wifiPeerConnected"]?.let { patch["wifiPeerConnected"] = it }
                    payload["ptpIpBridgeEnabled"]?.let { patch["ptpIpBridgeEnabled"] = it }
                    if (patch.isNotEmpty()) applySettings(patch)
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

        override fun state(): Map<String, Any?> {
            if (!enabled) {
                return super.state() + mapOf("enabled" to false)
            }
            syncRegOnPins()
            val btState = bt.state()
            val wifiState = wifi.state()
            return super.state() + linkedMapOf(
                "enabled" to true,
                "chip" to "BCM43438A1 / BCM43430A1",
                "deviceName" to deviceName,
                "uartAttached" to (attachedUart != null),
                "sdioAttached" to (attachedSdio != null),
                // Top-level quick telemetry fields matching contract
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
                "ipAddress" to wifi.virtualPeer.observedCameraIp,
                "connectedClients" to if (wifi.virtualPeer.connected) 1 else 0,
                "ptpIpReady" to wifi.virtualPeer.ptpIpReady,
                // Full structured sub-blocks
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
            require((data["enabled"] as? Boolean) == enabled) { "Snapshot enabled differs for $id" }
            val rawScalars = data["scalars"] as? Map<*, *>
                ?: throw IllegalArgumentException("Missing scalars in bcm43438 snapshot")
            restoreScalarValues(rawScalars, DESCRIPTOR)
            deviceName = (values["deviceName"] as? String)?.ifBlank { DEFAULT_DEVICE_NAME } ?: DEFAULT_DEVICE_NAME
            @Suppress("UNCHECKED_CAST")
            (data["bt"] as? Map<String, Any?>)?.let { bt.restoreSnapshot(it) }
            @Suppress("UNCHECKED_CAST")
            (data["wifi"] as? Map<String, Any?>)?.let { wifi.restoreSnapshot(it) }
        }
    }
}
