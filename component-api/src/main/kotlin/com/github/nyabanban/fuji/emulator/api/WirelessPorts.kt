package com.github.nyabanban.fuji.emulator.api

import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure serial peer attached to a SoC UART channel (e.g. ARM PL011 TXD/RXD/RTS/CTS).
 *
 * Kept strictly chip-agnostic: the UART controller forwards raw host TX bytes and line
 * configuration updates without knowing which external IC is wired to the pins.
 */
interface UartSerialPeer {
    /** Called on the owner thread when the guest transmits a byte on TXD. */
    fun onHostTx(byte: Byte)

    /** Called when the guest programs baud rate divisors (IBRD/FBRD) or line control. */
    fun onLineConfigChanged(ibrd: Int, fbrd: Int, lcrH: Long, uartCr: Long) {}
}

/**
 * Pure serial bus port exposed by a SoC UART channel (e.g. `soc-uart.uart1.serial`).
 *
 * External chip components (such as `bcm43438`) attach to this port in `start()` to exchange
 * raw serial bytes over the hardware UART FIFOs and trigger real UART RX interrupts.
 */
interface UartSerialPort {
    fun attachPeer(peer: UartSerialPeer)
    fun detachPeer(peer: UartSerialPeer)
    fun pushRxBytes(bytes: ByteArray)
    fun rxPendingBytes(): Int
}

/**
 * Result of executing an SDIO bus command (CMD0/CMD3/CMD5/CMD7/CMD52/CMD53) against an attached
 * SDIO card/function device.
 */
data class SdioCommandResult(
    val response: IntArray,
    val accepted: Boolean = true,
    val dataOut: ByteArray? = null,
    val expectDataInBytes: Int = 0,
    val cardInterrupt: Boolean = false,
) {
    init {
        require(response.size == 4) { "SDIO command response must contain 4 u32 words" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SdioCommandResult) return false
        return response.contentEquals(other.response) &&
            accepted == other.accepted &&
            ((dataOut == null && other.dataOut == null) || (dataOut != null && other.dataOut != null && dataOut.contentEquals(other.dataOut))) &&
            expectDataInBytes == other.expectDataInBytes &&
            cardInterrupt == other.cardInterrupt
    }

    override fun hashCode(): Int {
        var result = response.contentHashCode()
        result = 31 * result + accepted.hashCode()
        result = 31 * result + (dataOut?.contentHashCode() ?: 0)
        result = 31 * result + expectDataInBytes
        result = 31 * result + cardInterrupt.hashCode()
        return result
    }
}

/**
 * Pure SDIO v3.0 card/device interface attached to a SoC SDIO host slot.
 *
 * Kept strictly chip-agnostic: the SDIO host controller issues standard SDIO commands and
 * block/byte transfers without baking any vendor chip knowledge inside the host controller.
 */
interface SdioCardPort {
    /** Hard/soft card reset (CMD0 or host reset). */
    fun reset()

    /** Execute a standard SDIO command (CMD0, CMD3, CMD5, CMD7, CMD52, CMD53, etc.). */
    fun executeCommand(cmdIndex: Int, argument: Long, blockSize: Int = 0, blockCount: Int = 0): SdioCommandResult

    /** Deliver host-to-card write payload for a CMD53 write transaction. */
    fun writeTransferData(cmdIndex: Int, argument: Long, data: ByteArray): SdioCommandResult

    /** True while the attached SDIO card is asserting its SDIO Card Interrupt (DAT1 low). */
    fun isCardInterruptAsserted(): Boolean
}

/**
 * Pure SDIO host bus port exposed by a SoC SDIO host controller (e.g. `soc-sdio.bus`).
 *
 * External SDIO chip components attach themselves during `start()` and may raise SDIO card
 * interrupts asynchronously when RX frames or events become ready.
 */
interface SdioBusPort {
    fun attachCard(card: SdioCardPort)
    fun detachCard(card: SdioCardPort)
    fun setCardInterrupt(asserted: Boolean)
}

/**
 * Operating mode for the emulated BCM43430/BCM43438 Wi-Fi networking engine.
 */
enum class WifiNetworkMode {
    NAT,
    BRIDGE,
    HOST_ONLY;

    val cliValue: String
        get() = when (this) {
            NAT -> "nat"
            BRIDGE -> "bridge"
            HOST_ONLY -> "hostonly"
        }

    companion object {
        fun parse(raw: String?): WifiNetworkMode {
            val norm = raw?.trim()?.uppercase(Locale.ROOT)?.replace('-', '_') ?: return NAT
            return when (norm) {
                "", "NAT" -> NAT
                "BRIDGE", "BRIDGED" -> BRIDGE
                "HOST_ONLY", "HOSTONLY", "LOCAL" -> HOST_ONLY
                else -> throw IllegalArgumentException(
                    "Unsupported Wi-Fi network mode '$raw' (expected: nat, bridge, hostonly)",
                )
            }
        }
    }
}

/**
 * Parsed host-to-guest port forward rule (e.g. `"2222:22"`, `"3389:3389"`, `"127.0.0.1:2222:22"`,
 * `"0.0.0.0:2222:22"`, `"tcp:2222:22"`, or `"udp:5353:5353"`).
 */
data class WifiPortForwardRule(
    val protocol: String = "tcp",
    val bindHost: String = "127.0.0.1",
    val hostPort: Int,
    val guestPort: Int,
) {
    init {
        require(protocol in setOf("tcp", "udp")) { "Unsupported port-forward protocol '$protocol' (expected tcp or udp)" }
        require(hostPort in 0..65535) { "Invalid host port $hostPort in port-forward rule" }
        require(guestPort in 1..65535) { "Invalid guest port $guestPort in port-forward rule" }
    }

    fun toSpecString(): String = buildString {
        if (protocol != "tcp") append(protocol).append(':')
        if (bindHost != "127.0.0.1" && bindHost.isNotBlank()) append(bindHost).append(':')
        append(hostPort).append(':').append(guestPort)
    }

    companion object {
        fun parse(rawSpec: String): WifiPortForwardRule {
            var rest = rawSpec.trim()
            require(rest.isNotEmpty()) { "Empty port-forward rule" }
            var proto = "tcp"
            if (rest.startsWith("tcp:", ignoreCase = true)) {
                proto = "tcp"
                rest = rest.substring(4)
            } else if (rest.startsWith("udp:", ignoreCase = true)) {
                proto = "udp"
                rest = rest.substring(4)
            }
            val parts = rest.split(':')
            return when (parts.size) {
                2 -> WifiPortForwardRule(
                    protocol = proto,
                    bindHost = "127.0.0.1",
                    hostPort = parts[0].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid hostPort in port-forward '$rawSpec'"),
                    guestPort = parts[1].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid guestPort in port-forward '$rawSpec'"),
                )
                3 -> WifiPortForwardRule(
                    protocol = proto,
                    bindHost = parts[0].trim().ifEmpty { "127.0.0.1" },
                    hostPort = parts[1].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid hostPort in port-forward '$rawSpec'"),
                    guestPort = parts[2].trim().toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid guestPort in port-forward '$rawSpec'"),
                )
                else -> throw IllegalArgumentException(
                    "Invalid port-forward rule '$rawSpec' (expected [tcp:|udp:][bindIp:]hostPort:guestPort, e.g. 2222:22)",
                )
            }
        }

        fun parseList(raw: Any?): List<WifiPortForwardRule> = normalizeSpecs(raw).map(::parse)

        fun normalizeSpecs(raw: Any?): List<String> = when (raw) {
            null -> emptyList()
            is Collection<*> -> raw.flatMap { elem ->
                elem?.toString()?.split(',', ';', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            }
            is String -> raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}

/**
 * Immutable configuration for the emulated BCM43430/BCM43438 Wi-Fi network stack.
 */
data class WifiNetworkConfig(
    val enabled: Boolean = true,
    val mode: WifiNetworkMode = WifiNetworkMode.NAT,
    val ssid: String = "GFX100S-WiFi",
    val passphrase: String = "",
    val macAddress: String = "02:11:22:33:44:55",
    val subnetCidr: String = "192.168.42.0/24",
    val gatewayIp: String = "192.168.42.1",
    val guestIp: String = "192.168.42.100",
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val bridgeInterface: String = "",
    val hostOnlyLoopbackForward: Boolean = true,
    val hostOnlySubnetCidr: String = "192.168.56.0/24",
    val portForwards: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "enabled" to enabled,
        "mode" to mode.name,
        "ssid" to ssid,
        "passphrase" to passphrase,
        "macAddress" to macAddress,
        "subnetCidr" to subnetCidr,
        "gatewayIp" to gatewayIp,
        "guestIp" to guestIp,
        "dnsServers" to dnsServers.toList(),
        "bridgeInterface" to bridgeInterface,
        "hostOnlyLoopbackForward" to hostOnlyLoopbackForward,
        "hostOnlySubnetCidr" to hostOnlySubnetCidr,
        "portForwards" to portForwards.toList(),
    )

    /**
     * Converts this configuration into live scalar `bcm43438` component settings suitable for
     * `ControlClient.patch("bcm43438", revision, patch)` / `bcm43438.yaml` persistence.
     */
    fun toComponentSettingsPatch(): Map<String, Any> = linkedMapOf(
        "wifiNetworkEnabled" to enabled,
        "wifiNetworkMode" to mode.name,
        "wifiSsid" to ssid,
        "wifiPassphrase" to passphrase,
        "wifiMacAddr" to macAddress,
        "wifiSubnetCidr" to subnetCidr,
        "wifiGatewayIp" to gatewayIp,
        "wifiGuestIp" to guestIp,
        "wifiDnsServers" to dnsServers.joinToString(","),
        "wifiBridgeInterface" to bridgeInterface,
        "wifiHostOnlyLoopbackForward" to hostOnlyLoopbackForward,
        "wifiHostOnlySubnetCidr" to hostOnlySubnetCidr,
        "wifiPortForwards" to portForwards.joinToString(","),
    )

    companion object {
        val DEFAULT = WifiNetworkConfig()

        fun parseDnsList(raw: Any?, default: List<String> = DEFAULT.dnsServers): List<String> = when (raw) {
            is Collection<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            else -> default
        }.ifEmpty { default }

        fun fromComponentState(state: Map<*, *>, fallbackMac: String = DEFAULT.macAddress): WifiNetworkConfig {
            val nested = (state["wifiNetwork"] as? Map<*, *>)
                ?: (state["wifi"] as? Map<*, *>)?.get("networkConfig") as? Map<*, *>
                ?: state["wifiNetworkConfig"] as? Map<*, *>
            if (nested != null) return fromMap(nested, WifiNetworkConfig(macAddress = fallbackMac))
            return WifiNetworkConfig(
                enabled = (state["wifiNetworkEnabled"] as? Boolean) ?: DEFAULT.enabled,
                mode = WifiNetworkMode.parse(state["wifiNetworkMode"]?.toString()),
                ssid = (state["wifiSsid"] as? String)?.ifBlank { DEFAULT.ssid } ?: DEFAULT.ssid,
                passphrase = (state["wifiPassphrase"] as? String) ?: DEFAULT.passphrase,
                macAddress = (state["wifiMacAddr"] as? String)?.ifBlank { fallbackMac }
                    ?: (state["macAddress"] as? String)?.ifBlank { fallbackMac }
                    ?: fallbackMac,
                subnetCidr = (state["wifiSubnetCidr"] as? String)?.ifBlank { DEFAULT.subnetCidr } ?: DEFAULT.subnetCidr,
                gatewayIp = (state["wifiGatewayIp"] as? String)?.ifBlank { DEFAULT.gatewayIp } ?: DEFAULT.gatewayIp,
                guestIp = (state["wifiGuestIp"] as? String)?.ifBlank { DEFAULT.guestIp } ?: DEFAULT.guestIp,
                dnsServers = parseDnsList(state["wifiDnsServers"], DEFAULT.dnsServers),
                bridgeInterface = (state["wifiBridgeInterface"] as? String)?.trim() ?: DEFAULT.bridgeInterface,
                hostOnlyLoopbackForward = (state["wifiHostOnlyLoopbackForward"] as? Boolean) ?: DEFAULT.hostOnlyLoopbackForward,
                hostOnlySubnetCidr = (state["wifiHostOnlySubnetCidr"] as? String)?.ifBlank { DEFAULT.hostOnlySubnetCidr }
                    ?: DEFAULT.hostOnlySubnetCidr,
                portForwards = WifiPortForwardRule.normalizeSpecs(state["wifiPortForwards"] ?: state["portForwards"]),
            )
        }

        fun fromMap(map: Map<*, *>, base: WifiNetworkConfig = DEFAULT): WifiNetworkConfig = WifiNetworkConfig(
            enabled = (map["enabled"] as? Boolean) ?: (map["wifiNetworkEnabled"] as? Boolean) ?: base.enabled,
            mode = (map["mode"] ?: map["wifiNetworkMode"])?.toString()?.let(WifiNetworkMode::parse) ?: base.mode,
            ssid = ((map["ssid"] ?: map["wifiSsid"]) as? String) ?: base.ssid,
            passphrase = ((map["passphrase"] ?: map["wifiPassphrase"]) as? String) ?: base.passphrase,
            macAddress = ((map["macAddress"] ?: map["wifiMacAddr"]) as? String)?.ifBlank { base.macAddress } ?: base.macAddress,
            subnetCidr = ((map["subnetCidr"] ?: map["wifiSubnetCidr"]) as? String)?.ifBlank { base.subnetCidr } ?: base.subnetCidr,
            gatewayIp = ((map["gatewayIp"] ?: map["wifiGatewayIp"]) as? String)?.ifBlank { base.gatewayIp } ?: base.gatewayIp,
            guestIp = ((map["guestIp"] ?: map["wifiGuestIp"]) as? String)?.ifBlank { base.guestIp } ?: base.guestIp,
            dnsServers = if (map.containsKey("dnsServers") || map.containsKey("wifiDnsServers")) {
                parseDnsList(map["dnsServers"] ?: map["wifiDnsServers"], base.dnsServers)
            } else {
                base.dnsServers
            },
            bridgeInterface = ((map["bridgeInterface"] ?: map["wifiBridgeInterface"]) as? String)?.trim() ?: base.bridgeInterface,
            hostOnlyLoopbackForward = (map["hostOnlyLoopbackForward"] as? Boolean)
                ?: (map["wifiHostOnlyLoopbackForward"] as? Boolean)
                ?: base.hostOnlyLoopbackForward,
            hostOnlySubnetCidr = ((map["hostOnlySubnetCidr"] ?: map["wifiHostOnlySubnetCidr"]) as? String)
                ?.ifBlank { base.hostOnlySubnetCidr }
                ?: base.hostOnlySubnetCidr,
            portForwards = if (map.containsKey("portForwards") || map.containsKey("wifiPortForwards")) {
                WifiPortForwardRule.normalizeSpecs(map["portForwards"] ?: map["wifiPortForwards"])
            } else {
                base.portForwards
            },
        )
    }
}

/**
 * Live status snapshot of the emulated BCM43430/BCM43438 Wi-Fi network stack.
 */
data class WifiNetworkStatus(
    val mode: WifiNetworkMode,
    val linkUp: Boolean,
    val associatedSsid: String,
    val guestIp: String,
    val gatewayIp: String,
    val macAddress: String,
    val bridgeInterface: String,
    val bridgeState: String,
    val activeTcpSessions: Int,
    val activeUdpSessions: Int,
    val txPackets: Long,
    val rxPackets: Long,
    val txBytes: Long,
    val rxBytes: Long,
    val lastError: String?,
    val enabled: Boolean = true,
    val bridgeBackend: String = "",
    val dnsServers: List<String> = emptyList(),
    val portForwards: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "enabled" to enabled,
        "mode" to mode.name,
        "linkUp" to linkUp,
        "associatedSsid" to associatedSsid,
        "guestIp" to guestIp,
        "gatewayIp" to gatewayIp,
        "macAddress" to macAddress,
        "bridgeInterface" to bridgeInterface,
        "bridgeBackend" to bridgeBackend,
        "bridgeState" to bridgeState,
        "dnsServers" to dnsServers,
        "portForwards" to portForwards,
        "activeTcpSessions" to activeTcpSessions,
        "activeUdpSessions" to activeUdpSessions,
        "txPackets" to txPackets,
        "rxPackets" to rxPackets,
        "txBytes" to txBytes,
        "rxBytes" to rxBytes,
        "lastError" to lastError,
    )

    companion object {
        fun fromMap(raw: Map<*, *>): WifiNetworkStatus {
            val map = (raw["wifiNetwork"] as? Map<*, *>)
                ?: ((raw["wifi"] as? Map<*, *>)?.get("networkStatus") as? Map<*, *>)
                ?: (raw["wifiNetworkStatus"] as? Map<*, *>)
                ?: raw
            return WifiNetworkStatus(
                mode = WifiNetworkMode.parse(map["mode"]?.toString()),
                linkUp = (map["linkUp"] as? Boolean) ?: false,
                associatedSsid = (map["associatedSsid"] as? String) ?: (map["ssid"] as? String) ?: "",
                guestIp = (map["guestIp"] as? String) ?: (map["ipAddress"] as? String) ?: "",
                gatewayIp = (map["gatewayIp"] as? String) ?: "",
                macAddress = (map["macAddress"] as? String) ?: "",
                bridgeInterface = (map["bridgeInterface"] as? String) ?: "",
                bridgeState = (map["bridgeState"] as? String) ?: "DISABLED",
                activeTcpSessions = (map["activeTcpSessions"] as? Number)?.toInt() ?: 0,
                activeUdpSessions = (map["activeUdpSessions"] as? Number)?.toInt() ?: 0,
                txPackets = (map["txPackets"] as? Number)?.toLong() ?: 0L,
                rxPackets = (map["rxPackets"] as? Number)?.toLong() ?: 0L,
                txBytes = (map["txBytes"] as? Number)?.toLong() ?: 0L,
                rxBytes = (map["rxBytes"] as? Number)?.toLong() ?: 0L,
                lastError = map["lastError"] as? String,
                enabled = (map["enabled"] as? Boolean) ?: true,
                bridgeBackend = (map["bridgeBackend"] as? String) ?: "",
                dnsServers = WifiNetworkConfig.parseDnsList(map["dnsServers"], emptyList()),
                portForwards = WifiPortForwardRule.normalizeSpecs(map["portForwards"] ?: map["wifiPortForwards"]),
            )
        }
    }
}

/**
 * Cross-platform host NIC descriptor discovered via [NetworkInterface.getNetworkInterfaces]
 * (Windows 10/11, Linux, macOS) for Bridge mode adapter selection.
 */
data class HostNetworkInterfaceInfo(
    val name: String,
    val displayName: String,
    val ipv4Addresses: List<String>,
    val macAddress: String,
    val up: Boolean,
    val loopback: Boolean,
    val virtualOrTap: Boolean = false,
) {
    fun summaryLabel(): String {
        val label = if (displayName.isNotBlank() && !displayName.equals(name, ignoreCase = true)) {
            "$displayName ($name)"
        } else {
            name
        }
        val ipPart = ipv4Addresses.firstOrNull()?.let { " — $it" } ?: ""
        val macPart = macAddress.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
        return "$label$ipPart$macPart"
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "displayName" to displayName,
        "ipv4Addresses" to ipv4Addresses,
        "macAddress" to macAddress,
        "up" to up,
        "loopback" to loopback,
        "virtualOrTap" to virtualOrTap,
        "summary" to summaryLabel(),
    )

    companion object {
        fun discoverAll(includeDown: Boolean = false, includeLoopback: Boolean = false): List<HostNetworkInterfaceInfo> {
            val out = mutableListOf<HostNetworkInterfaceInfo>()
            val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptyList()
            while (enumeration.hasMoreElements()) {
                val nif = enumeration.nextElement() ?: continue
                val isLoop = runCatching { nif.isLoopback }.getOrDefault(false)
                if (!includeLoopback && isLoop) continue
                val isUp = runCatching { nif.isUp }.getOrDefault(false)
                if (!includeDown && !isUp) continue
                val hw = runCatching { nif.hardwareAddress }.getOrNull()
                val mac = if (hw != null && hw.size >= 6) {
                    (0 until 6).joinToString(":") { "%02X".format(hw[it].toInt() and 0xff) }
                } else {
                    ""
                }
                val ipv4s = runCatching {
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress || includeLoopback }
                        .map { it.hostAddress }
                }.getOrDefault(emptyList())
                val name = nif.name ?: ""
                val disp = nif.displayName?.takeIf { it.isNotBlank() } ?: name
                val lower = "$name $disp".lowercase(Locale.ROOT)
                val isTap = nif.isVirtual ||
                    lower.contains("tap") ||
                    lower.contains("tun") ||
                    lower.contains("npcap") ||
                    lower.contains("wintun") ||
                    lower.startsWith("br") ||
                    lower.startsWith("virbr")
                out += HostNetworkInterfaceInfo(
                    name = name,
                    displayName = disp,
                    ipv4Addresses = ipv4s,
                    macAddress = mac,
                    up = isUp,
                    loopback = isLoop,
                    virtualOrTap = isTap,
                )
            }
            return out.sortedWith(
                compareByDescending<HostNetworkInterfaceInfo> { it.up }
                    .thenByDescending { it.ipv4Addresses.isNotEmpty() }
                    .thenBy { it.name },
            )
        }
    }
}

/**
 * Cross-platform host DNS server auto-discovery supporting Windows (`ipconfig /all` + PowerShell),
 * Linux (`/etc/resolv.conf`), and macOS (`scutil --dns` / `/etc/resolv.conf`).
 */
object HostDnsResolver {
    private val IPV4_REGEX = Regex("""\b((?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3})\b""")

    fun parseResolvConf(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore('#').substringBefore(';').trim() }
            .filter { it.startsWith("nameserver", ignoreCase = true) }
            .mapNotNull { line ->
                line.split(Regex("\\s+")).getOrNull(1)?.takeIf { isValidPublicOrLanIpv4(it) }
            }
            .distinct()
            .toList()

    /**
     * Parses localized or English Windows `ipconfig /all` output for IPv4 DNS Servers
     * (handles multi-line DNS server lists under both English `"DNS Servers"` and Chinese `"DNS 服务器"`).
     */
    fun parseWindowsIpconfigAll(text: String): List<String> {
        val servers = LinkedHashSet<String>()
        var inDnsContinuation = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) {
                inDnsContinuation = false
                continue
            }
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx >= 0 && !trimmed[0].isDigit()) {
                val key = trimmed.substring(0, colonIdx).lowercase(Locale.ROOT)
                inDnsContinuation = key.contains("dns servers") ||
                    key.contains("dns 服务器") ||
                    key.contains("dns-server") ||
                    (key.contains("dns") && !key.contains("suffix") && !key.contains("后缀"))
                if (inDnsContinuation) {
                    val rhs = trimmed.substring(colonIdx + 1)
                    IPV4_REGEX.findAll(rhs).forEach { m ->
                        val ip = m.groupValues[1]
                        if (isValidPublicOrLanIpv4(ip)) servers += ip
                    }
                }
            } else if (inDnsContinuation && (line.startsWith("   ") || line.startsWith("\t"))) {
                IPV4_REGEX.findAll(trimmed).forEach { m ->
                    val ip = m.groupValues[1]
                    if (isValidPublicOrLanIpv4(ip)) servers += ip
                }
            } else {
                inDnsContinuation = false
            }
        }
        return servers.toList()
    }

    fun isValidPublicOrLanIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) return false
        if (octets[0] == 169 && octets[1] == 254) return false
        return true
    }

    fun resolveEffectiveDnsServers(
        configured: List<String>,
        osName: String = System.getProperty("os.name", ""),
    ): List<String> {
        val result = LinkedHashSet<String>()
        configured.map { it.trim() }.filter { isValidPublicOrLanIpv4(it) }.forEach(result::add)
        val lowerOs = osName.lowercase(Locale.ROOT)
        if (lowerOs.contains("win")) {
            runCommandCapture(listOf("ipconfig", "/all"), 2500L)?.let { out ->
                parseWindowsIpconfigAll(out).forEach(result::add)
            }
            if (result.isEmpty()) {
                runCommandCapture(
                    listOf("powershell", "-NoProfile", "-Command", "(Get-DnsClientServerAddress -AddressFamily IPv4).ServerAddresses"),
                    2500L,
                )?.lineSequence()?.map { it.trim() }?.filter { isValidPublicOrLanIpv4(it) }?.forEach(result::add)
            }
        } else {
            val resolv = Path.of("/etc/resolv.conf")
            if (Files.isRegularFile(resolv)) {
                runCatching { Files.readString(resolv) }.getOrNull()?.let { text ->
                    parseResolvConf(text).forEach(result::add)
                }
            }
        }
        if (result.isEmpty()) {
            result += "8.8.8.8"
            result += "1.1.1.1"
        }
        return result.toList()
    }

    private fun runCommandCapture(cmd: List<String>, timeoutMs: Long): String? = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return null
        }
        proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
    }.getOrNull()
}

/**
 * Host-side control and inspection port exposed by wireless chip / peer components for the
 * Desktop UI, Console CLI, loopback Control API (`/api/wireless`), and deterministic E2E tests.
 */
interface WirelessControlPort {
    fun wirelessStatus(): Map<String, Any?>
    fun applyWirelessAction(payload: Map<String, Any?>): Map<String, Any?>

    /** Returns the active [WifiNetworkConfig] of the emulated Wi-Fi adapter. */
    fun getNetworkConfig(): WifiNetworkConfig = WifiNetworkConfig.fromComponentState(wirelessStatus())

    /** Returns the live [WifiNetworkStatus] of the emulated Wi-Fi adapter. */
    fun getNetworkStatus(): WifiNetworkStatus = WifiNetworkStatus.fromMap(wirelessStatus())

    /** Live-reconfigures the Wi-Fi network mode/parameters (`NAT` / `BRIDGE` / `HOST_ONLY`). */
    fun reconfigureNetwork(config: WifiNetworkConfig): WifiNetworkStatus {
        val updated = applyWirelessAction(mapOf("action" to "wifi.reconfigure") + config.toMap())
        return WifiNetworkStatus.fromMap(updated)
    }

    /** Resets Wi-Fi network configuration to [WifiNetworkConfig.DEFAULT]. */
    fun resetNetworkConfig(): WifiNetworkStatus = reconfigureNetwork(WifiNetworkConfig.DEFAULT)

    /** Enumerates host network interfaces suitable for Bridge mode on Windows/Linux/macOS. */
    fun listHostNetworkInterfaces(includeDown: Boolean = false, includeLoopback: Boolean = false): List<HostNetworkInterfaceInfo> =
        HostNetworkInterfaceInfo.discoverAll(includeDown = includeDown, includeLoopback = includeLoopback)
}
