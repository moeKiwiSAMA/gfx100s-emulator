package com.github.nyabanban.fuji.emulator.desktop.wifi

import com.github.nyabanban.fuji.emulator.api.HostDnsResolver
import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import com.github.nyabanban.fuji.emulator.api.WifiNetworkMode
import com.github.nyabanban.fuji.emulator.api.WifiNetworkStatus
import com.github.nyabanban.fuji.emulator.api.WirelessControlPort
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * Parsed command-line overrides for the emulated BCM43438/BCM43430 Wi-Fi network subsystem.
 *
 * Supported flags:
 * - `--wifi-mode=nat|bridge|hostonly` (or `--wifi-mode <mode>`)
 * - `--wifi-ssid=<ssid>` (or `--wifi-ssid <ssid>`)
 * - `--wifi-passphrase=<pass>` (or `--wifi-passphrase <pass>`)
 * - `--wifi-bridge-iface=<iface>` (or `--wifi-bridge-iface <iface>`)
 * - `--wifi-subnet=<cidr>` (or `--wifi-subnet <cidr>`)
 * - `--wifi-dns=<ip1,ip2>` (or `--wifi-dns <ip1,ip2>`)
 * - `--wifi-disabled` / `--wifi-enabled`
 */
data class WifiLaunchCliOverrides(
    val enabled: Boolean? = null,
    val mode: WifiNetworkMode? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
    val bridgeInterface: String? = null,
    val subnetCidr: String? = null,
    val dnsServers: List<String>? = null,
    val hostOnlyLoopbackForward: Boolean? = null,
    val portForwards: List<String>? = null,
) {
    fun hasOverrides(): Boolean =
        enabled != null ||
            mode != null ||
            ssid != null ||
            passphrase != null ||
            bridgeInterface != null ||
            subnetCidr != null ||
            dnsServers != null ||
            hostOnlyLoopbackForward != null ||
            portForwards != null

    fun applyTo(base: WifiNetworkConfig): WifiNetworkConfig {
        val nextMode = mode ?: base.mode
        val resolvedSubnet = subnetCidr?.let { WifiNetworkValidation.validateIpv4Cidr(it, "wifi-subnet") }
        val nextSubnetCidr = if (resolvedSubnet != null && nextMode != WifiNetworkMode.HOST_ONLY) {
            resolvedSubnet.canonicalCidr
        } else {
            base.subnetCidr
        }
        val nextHostOnlyCidr = if (resolvedSubnet != null && nextMode == WifiNetworkMode.HOST_ONLY) {
            resolvedSubnet.canonicalCidr
        } else {
            base.hostOnlySubnetCidr
        }
        val derivedAddresses = WifiNetworkValidation.validateIpv4Cidr(
            if (nextMode == WifiNetworkMode.HOST_ONLY) nextHostOnlyCidr else nextSubnetCidr,
        )
        val shouldDeriveIp = resolvedSubnet != null || (mode != null && mode != base.mode)
        return base.copy(
            enabled = enabled ?: base.enabled,
            mode = nextMode,
            ssid = ssid ?: base.ssid,
            passphrase = passphrase ?: base.passphrase,
            bridgeInterface = bridgeInterface ?: base.bridgeInterface,
            subnetCidr = nextSubnetCidr,
            hostOnlySubnetCidr = nextHostOnlyCidr,
            gatewayIp = if (shouldDeriveIp) derivedAddresses.gatewayIp else base.gatewayIp,
            guestIp = if (shouldDeriveIp) derivedAddresses.defaultGuestIp else base.guestIp,
            dnsServers = dnsServers ?: base.dnsServers,
            hostOnlyLoopbackForward = hostOnlyLoopbackForward ?: base.hostOnlyLoopbackForward,
        )
    }
}

object WifiNetworkValidation {
    const val CONFIG_KEY = "wifi"
    const val DEFAULT_FUJI_WIFI_MAC = "02:46:4A:10:00:00"

    private val VALID_CONFIG_KEYS = setOf(
        "enabled",
        "mode",
        "ssid",
        "passphrase",
        "macAddress",
        "subnetCidr",
        "gatewayIp",
        "guestIp",
        "dnsServers",
        "bridgeInterface",
        "hostOnlyLoopbackForward",
        "hostOnlySubnetCidr",
        "portForwards",
    )

    data class ParsedSubnet(
        val canonicalCidr: String,
        val networkAddress: String,
        val prefixLength: Int,
        val gatewayIp: String,
        val defaultGuestIp: String,
        val netmask: String,
    )

    fun validateIpv4(raw: String, fieldName: String = "ip"): String {
        val trimmed = raw.trim()
        val parts = trimmed.split('.')
        require(parts.size == 4) { "$fieldName must be a valid IPv4 address (A.B.C.D), got '$raw'" }
        val octets = parts.map { part ->
            val v = part.toIntOrNull()
            require(v != null && v in 0..255 && part == v.toString()) {
                "$fieldName must be a valid IPv4 address (0..255 octets), got '$raw'"
            }
            v
        }
        return octets.joinToString(".")
    }

    fun validateIpv4Cidr(raw: String, fieldName: String = "subnetCidr"): ParsedSubnet {
        val trimmed = raw.trim()
        val slashIdx = trimmed.indexOf('/')
        require(slashIdx > 0 && slashIdx < trimmed.lastIndex) {
            "$fieldName must be IPv4 CIDR notation (e.g. 192.168.42.0/24), got '$raw'"
        }
        val ipStr = validateIpv4(trimmed.substring(0, slashIdx), fieldName)
        val prefix = trimmed.substring(slashIdx + 1).toIntOrNull()
        require(prefix != null && prefix in 8..30) {
            "$fieldName prefix length must be within 8..30, got '$raw'"
        }
        val octets = ipStr.split('.').map { it.toInt() }
        val rawInt = ((octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()) and 0xffffffffL
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val netInt = rawInt and mask
        val hostCount = 1L shl (32 - prefix)
        val gatewayOffset = 1L
        val guestOffset = if (hostCount > 100L) 100L else 2L.coerceAtMost(hostCount - 2L)
        return ParsedSubnet(
            canonicalCidr = "${intToIpv4(netInt)}/$prefix",
            networkAddress = intToIpv4(netInt),
            prefixLength = prefix,
            gatewayIp = intToIpv4(netInt + gatewayOffset),
            defaultGuestIp = intToIpv4(netInt + guestOffset),
            netmask = intToIpv4(mask),
        )
    }

    fun parseAndValidateDnsServers(raw: Any?, fieldName: String = "dnsServers"): List<String> {
        val parsed = when (raw) {
            is Collection<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException("$fieldName requires a comma-separated string or list of IPv4 addresses")
        }
        require(parsed.isNotEmpty()) { "$fieldName requires at least one IPv4 DNS server" }
        return parsed.map { validateIpv4(it, fieldName) }.distinct()
    }

    fun parseAndValidatePortForwards(raw: Any?, fieldName: String = "portForwards"): List<String> {
        if (raw == null) return emptyList()
        val items = when (raw) {
            is Collection<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> raw.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw IllegalArgumentException("$fieldName requires a comma-separated string or list of hostPort:guestPort rules")
        }
        return items.map { spec ->
            val clean = spec.substringBefore('/').trim()
            val protoSuffix = if ('/' in spec) {
                val proto = spec.substringAfter('/').trim().lowercase(Locale.ROOT)
                require(proto == "tcp" || proto == "udp") { "$fieldName protocol must be tcp or udp in '$spec'" }
                "/$proto"
            } else {
                ""
            }
            val parts = clean.split(':')
            when (parts.size) {
                2 -> {
                    val hp = parts[0].toIntOrNull()
                    val gp = parts[1].toIntOrNull()
                    require(hp != null && hp in 1..65535 && gp != null && gp in 1..65535) {
                        "$fieldName ports must be within 1..65535 (expected hostPort:guestPort), got '$spec'"
                    }
                    "$hp:$gp$protoSuffix"
                }
                3 -> {
                    val bindIp = if (parts[0] == "0.0.0.0" || parts[0] == "*") "0.0.0.0" else validateIpv4(parts[0], fieldName)
                    val hp = parts[1].toIntOrNull()
                    val gp = parts[2].toIntOrNull()
                    require(hp != null && hp in 1..65535 && gp != null && gp in 1..65535) {
                        "$fieldName ports must be within 1..65535 (expected bindIp:hostPort:guestPort), got '$spec'"
                    }
                    "$bindIp:$hp:$gp$protoSuffix"
                }
                else -> throw IllegalArgumentException("$fieldName entry '$spec' must be hostPort:guestPort (e.g. 3389:3389)")
            }
        }.distinct()
    }

    fun normalizeBridgeInterfaceInput(raw: String): String {
        val trimmed = raw.trim()
        return if (
            trimmed.equals("auto", ignoreCase = true) ||
            trimmed.equals("default", ignoreCase = true) ||
            trimmed.equals("none", ignoreCase = true)
        ) {
            ""
        } else {
            trimmed
        }
    }

    fun validateConfigValue(raw: Any?) {
        if (raw == null) return
        when (raw) {
            is Boolean -> Unit
            is Map<*, *> -> {
                require(raw.keys.all { it is String && it in VALID_CONFIG_KEYS }) {
                    "invalid wifi config keys: ${raw.keys - VALID_CONFIG_KEYS}"
                }
                raw["enabled"]?.let { require(it is Boolean) { "wifi.enabled requires boolean" } }
                raw["mode"]?.let {
                    require(it is String) { "wifi.mode requires string" }
                    WifiNetworkMode.parse(it)
                }
                raw["ssid"]?.let {
                    require(it is String && it.trim().isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 32) {
                        "wifi.ssid must be a 1..32 byte UTF-8 string"
                    }
                }
                raw["passphrase"]?.let { require(it is String) { "wifi.passphrase requires string" } }
                raw["macAddress"]?.let { require(it is String && it.isNotBlank()) { "wifi.macAddress requires nonblank string" } }
                raw["subnetCidr"]?.let {
                    require(it is String) { "wifi.subnetCidr requires string" }
                    validateIpv4Cidr(it, "wifi.subnetCidr")
                }
                raw["gatewayIp"]?.let {
                    require(it is String) { "wifi.gatewayIp requires string" }
                    validateIpv4(it, "wifi.gatewayIp")
                }
                raw["guestIp"]?.let {
                    require(it is String) { "wifi.guestIp requires string" }
                    validateIpv4(it, "wifi.guestIp")
                }
                raw["dnsServers"]?.let { parseAndValidateDnsServers(it, "wifi.dnsServers") }
                raw["bridgeInterface"]?.let { require(it is String) { "wifi.bridgeInterface requires string" } }
                raw["hostOnlyLoopbackForward"]?.let { require(it is Boolean) { "wifi.hostOnlyLoopbackForward requires boolean" } }
                raw["hostOnlySubnetCidr"]?.let {
                    require(it is String) { "wifi.hostOnlySubnetCidr requires string" }
                    validateIpv4Cidr(it, "wifi.hostOnlySubnetCidr")
                }
                raw["portForwards"]?.let { parseAndValidatePortForwards(it, "wifi.portForwards") }
            }
            else -> throw IllegalArgumentException("wifi config requires boolean or map")
        }
    }

    fun resolveFromEmulatorYaml(config: Map<String, Any?>, base: WifiNetworkConfig): WifiNetworkConfig {
        val raw = config[CONFIG_KEY] ?: return base
        validateConfigValue(raw)
        return when (raw) {
            is Boolean -> base.copy(enabled = raw)
            is Map<*, *> -> applyPatchToConfig(base, raw)
            else -> base
        }
    }

    fun applyPatchToConfig(base: WifiNetworkConfig, patch: Map<*, *>): WifiNetworkConfig {
        val nextEnabled = (patch["enabled"] as? Boolean)
            ?: (patch["wifiNetworkEnabled"] as? Boolean)
            ?: base.enabled
        val rawMode = (patch["mode"] ?: patch["wifiNetworkMode"])?.toString()
        val nextMode = if (rawMode != null) {
            require(rawMode.isNotBlank()) { "Wi-Fi mode cannot be blank (expected: nat, bridge, hostonly)" }
            WifiNetworkMode.parse(rawMode)
        } else {
            base.mode
        }
        val nextSsid = ((patch["ssid"] ?: patch["wifiSsid"]) as? String)?.let { rawSsid ->
            val trimmed = rawSsid.trim()
            require(trimmed.isNotEmpty() && trimmed.toByteArray(Charsets.UTF_8).size <= 32) {
                "Wi-Fi SSID must be 1..32 UTF-8 bytes"
            }
            trimmed
        } ?: base.ssid
        val nextPassphrase = ((patch["passphrase"] ?: patch["wifiPassphrase"]) as? String) ?: base.passphrase
        val nextMac = ((patch["macAddress"] ?: patch["wifiMacAddr"]) as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: base.macAddress
        val rawBridgeIface = (patch["bridgeInterface"] ?: patch["wifiBridgeInterface"]) as? String
        val nextBridgeIface = if (rawBridgeIface != null) {
            normalizeBridgeInterfaceInput(rawBridgeIface)
        } else {
            base.bridgeInterface
        }
        val rawSubnet = (patch["subnetCidr"] ?: patch["wifiSubnetCidr"]) as? String
        val rawHostOnlySubnet = (patch["hostOnlySubnetCidr"] ?: patch["wifiHostOnlySubnetCidr"]) as? String
        val parsedNatSubnet = rawSubnet?.takeIf { it.isNotBlank() }?.let { validateIpv4Cidr(it, "subnetCidr") }
        val parsedHostOnlySubnet = rawHostOnlySubnet?.takeIf { it.isNotBlank() }?.let { validateIpv4Cidr(it, "hostOnlySubnetCidr") }
        val nextSubnetCidr = parsedNatSubnet?.canonicalCidr ?: base.subnetCidr
        val nextHostOnlySubnetCidr = if (parsedHostOnlySubnet != null) {
            parsedHostOnlySubnet.canonicalCidr
        } else if (parsedNatSubnet != null && nextMode == WifiNetworkMode.HOST_ONLY && rawHostOnlySubnet == null) {
            parsedNatSubnet.canonicalCidr
        } else {
            base.hostOnlySubnetCidr
        }
        val activeSubnet = validateIpv4Cidr(
            if (nextMode == WifiNetworkMode.HOST_ONLY) nextHostOnlySubnetCidr else nextSubnetCidr,
        )
        val explicitGateway = ((patch["gatewayIp"] ?: patch["wifiGatewayIp"]) as? String)?.takeIf { it.isNotBlank() }
            ?.let { validateIpv4(it, "gatewayIp") }
        val explicitGuest = ((patch["guestIp"] ?: patch["wifiGuestIp"]) as? String)?.takeIf { it.isNotBlank() }
            ?.let { validateIpv4(it, "guestIp") }
        val subnetOrModeChanged = parsedNatSubnet != null || parsedHostOnlySubnet != null || nextMode != base.mode
        val nextGateway = explicitGateway ?: if (subnetOrModeChanged) activeSubnet.gatewayIp else base.gatewayIp
        val nextGuest = explicitGuest ?: if (subnetOrModeChanged) activeSubnet.defaultGuestIp else base.guestIp
        val hasDnsPatch = patch.containsKey("dnsServers") || patch.containsKey("wifiDnsServers")
        val nextDns = if (hasDnsPatch) {
            parseAndValidateDnsServers(patch["dnsServers"] ?: patch["wifiDnsServers"], "dnsServers")
        } else {
            base.dnsServers
        }
        val nextLoopbackForward = (patch["hostOnlyLoopbackForward"] as? Boolean)
            ?: (patch["wifiHostOnlyLoopbackForward"] as? Boolean)
            ?: base.hostOnlyLoopbackForward

        return WifiNetworkConfig(
            enabled = nextEnabled,
            mode = nextMode,
            ssid = nextSsid,
            passphrase = nextPassphrase,
            macAddress = nextMac,
            subnetCidr = nextSubnetCidr,
            gatewayIp = nextGateway,
            guestIp = nextGuest,
            dnsServers = nextDns,
            bridgeInterface = nextBridgeIface,
            hostOnlyLoopbackForward = nextLoopbackForward,
            hostOnlySubnetCidr = nextHostOnlySubnetCidr,
        )
    }

    private fun intToIpv4(value: Long): String =
        "${(value ushr 24) and 0xff}.${(value ushr 16) and 0xff}.${(value ushr 8) and 0xff}.${value and 0xff}"
}

fun parseWifiLaunchArgs(args: Array<String>): WifiLaunchCliOverrides {
    var enabled: Boolean? = null
    var mode: WifiNetworkMode? = null
    var ssid: String? = null
    var passphrase: String? = null
    var bridgeInterface: String? = null
    var subnetCidr: String? = null
    var dnsServers: List<String>? = null
    var hostOnlyLoopbackForward: Boolean? = null
    var portForwards: MutableList<String>? = null

    fun requireNextValue(index: Int, flag: String): String {
        require(index + 1 < args.size) { "$flag requires a value" }
        return args[index + 1]
    }

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--wifi-disabled" -> {
                enabled = false
                i++
            }
            arg == "--wifi-enabled" || arg == "--wifi" -> {
                enabled = true
                i++
            }
            arg.startsWith("--wifi-mode=") -> {
                val raw = arg.substringAfter('=').trim()
                require(raw.isNotEmpty()) { "--wifi-mode requires nat, bridge, or hostonly" }
                mode = WifiNetworkMode.parse(raw)
                i++
            }
            arg == "--wifi-mode" -> {
                val raw = requireNextValue(i, "--wifi-mode").trim()
                require(raw.isNotEmpty()) { "--wifi-mode requires nat, bridge, or hostonly" }
                mode = WifiNetworkMode.parse(raw)
                i += 2
            }
            arg.startsWith("--wifi-ssid=") -> {
                val raw = arg.substringAfter('=').trim()
                require(raw.isNotEmpty() && raw.toByteArray(Charsets.UTF_8).size <= 32) {
                    "--wifi-ssid must be 1..32 UTF-8 bytes"
                }
                ssid = raw
                i++
            }
            arg == "--wifi-ssid" -> {
                val raw = requireNextValue(i, "--wifi-ssid").trim()
                require(raw.isNotEmpty() && raw.toByteArray(Charsets.UTF_8).size <= 32) {
                    "--wifi-ssid must be 1..32 UTF-8 bytes"
                }
                ssid = raw
                i += 2
            }
            arg.startsWith("--wifi-passphrase=") -> {
                passphrase = arg.substringAfter('=')
                i++
            }
            arg == "--wifi-passphrase" -> {
                passphrase = requireNextValue(i, "--wifi-passphrase")
                i += 2
            }
            arg.startsWith("--wifi-bridge-iface=") -> {
                bridgeInterface = WifiNetworkValidation.normalizeBridgeInterfaceInput(arg.substringAfter('='))
                i++
            }
            arg == "--wifi-bridge-iface" -> {
                bridgeInterface = WifiNetworkValidation.normalizeBridgeInterfaceInput(requireNextValue(i, "--wifi-bridge-iface"))
                i += 2
            }
            arg.startsWith("--wifi-subnet=") -> {
                subnetCidr = WifiNetworkValidation.validateIpv4Cidr(arg.substringAfter('='), "--wifi-subnet").canonicalCidr
                i++
            }
            arg == "--wifi-subnet" -> {
                subnetCidr = WifiNetworkValidation.validateIpv4Cidr(requireNextValue(i, "--wifi-subnet"), "--wifi-subnet").canonicalCidr
                i += 2
            }
            arg.startsWith("--wifi-dns=") -> {
                dnsServers = WifiNetworkValidation.parseAndValidateDnsServers(arg.substringAfter('='), "--wifi-dns")
                i++
            }
            arg == "--wifi-dns" -> {
                dnsServers = WifiNetworkValidation.parseAndValidateDnsServers(requireNextValue(i, "--wifi-dns"), "--wifi-dns")
                i += 2
            }
            arg.startsWith("--wifi-hostonly-loopback=") -> {
                hostOnlyLoopbackForward = arg.substringAfter('=').trim().toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("--wifi-hostonly-loopback requires true or false")
                i++
            }
            arg == "--wifi-no-hostonly-loopback" -> {
                hostOnlyLoopbackForward = false
                i++
            }
            arg.startsWith("--wifi-forward=") -> {
                val parsed = WifiNetworkValidation.parseAndValidatePortForwards(arg.substringAfter('='), "--wifi-forward")
                val list = portForwards ?: mutableListOf<String>().also { portForwards = it }
                parsed.filterNot { it in list }.forEach(list::add)
                i++
            }
            arg == "--wifi-forward" -> {
                val parsed = WifiNetworkValidation.parseAndValidatePortForwards(requireNextValue(i, "--wifi-forward"), "--wifi-forward")
                val list = portForwards ?: mutableListOf<String>().also { portForwards = it }
                parsed.filterNot { it in list }.forEach(list::add)
                i += 2
            }
            arg == "--wifi-no-forward" -> {
                portForwards = mutableListOf()
                i++
            }
            else -> i++
        }
    }

    return WifiLaunchCliOverrides(
        enabled = enabled,
        mode = mode,
        ssid = ssid,
        passphrase = passphrase,
        bridgeInterface = bridgeInterface,
        subnetCidr = subnetCidr,
        dnsServers = dnsServers,
        hostOnlyLoopbackForward = hostOnlyLoopbackForward,
        portForwards = portForwards?.toList(),
    )
}

/**
 * Cross-platform host NIC filtering, Windows 10/11 adapter matching, and L2 Bridge capability probing.
 */
object HostNetworkCatalog {
    private val WINDOWS_NOISE_PATTERNS = listOf(
        "wan miniport",
        "lightweight filter",
        "qos packet scheduler",
        "wfp ",
        "teredo",
        "isatap",
        "6to4",
        "kernel debug",
        "wi-fi direct virtual",
        "microsoft hosted network",
        "ras async",
    )

    data class BridgeEnvironmentProbe(
        val osFamily: String,
        val isWindows: Boolean,
        val npcapInstalled: Boolean,
        val winpcapInstalled: Boolean,
        val tapWindowsInstalled: Boolean,
        val linuxTunAvailable: Boolean,
        val preferredBackend: String,
        val tapAdapterName: String = "",
    ) {
        fun toMap(): Map<String, Any> = linkedMapOf(
            "osFamily" to osFamily,
            "isWindows" to isWindows,
            "npcapInstalled" to npcapInstalled,
            "winpcapInstalled" to winpcapInstalled,
            "tapWindowsInstalled" to tapWindowsInstalled,
            "linuxTunAvailable" to linuxTunAvailable,
            "preferredBackend" to preferredBackend,
            "tapAdapterName" to tapAdapterName,
        )
    }

    fun filterBridgeCandidates(interfaces: List<HostNetworkInterfaceInfo>): List<HostNetworkInterfaceInfo> =
        interfaces.filter { info ->
            if (info.loopback || !info.up) return@filter false
            val combined = "${info.name} ${info.displayName}".lowercase(Locale.ROOT)
            val isExplicitTap = combined.contains("tap-windows") ||
                combined.contains("openvpn") ||
                combined.contains("npcap") ||
                combined.contains("wintun") ||
                info.name.startsWith("tap", ignoreCase = true) ||
                info.name.startsWith("br", ignoreCase = true)
            if (isExplicitTap) return@filter true
            WINDOWS_NOISE_PATTERNS.none { noise -> combined.contains(noise) }
        }

    fun isWindowsTapAdapter(info: HostNetworkInterfaceInfo): Boolean {
        val combined = "${info.name} ${info.displayName}".lowercase(Locale.ROOT)
        return combined.contains("tap-windows") ||
            combined.contains("openvpn tap") ||
            combined.contains("tap adapter") ||
            combined.contains("wintun")
    }

    /**
     * Resolves a user-provided bridge interface identifier (`eth0`, Windows friendly `DisplayName`,
     * NPF GUID `{...}`, MAC `XX:XX:XX:XX:XX:XX`, or IPv4 address) against discovered host NICs.
     */
    fun resolveAdapter(
        query: String,
        interfaces: List<HostNetworkInterfaceInfo>,
    ): HostNetworkInterfaceInfo? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null
        interfaces.firstOrNull { it.name == trimmed }?.let { return it }
        interfaces.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it }
        interfaces.firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }?.let { return it }
        interfaces.firstOrNull { it.summaryLabel().equals(trimmed, ignoreCase = true) }?.let { return it }

        val normMac = trimmed.uppercase(Locale.ROOT).replace('-', ':')
        if (normMac.length == 17 && normMac.count { it == ':' } == 5) {
            interfaces.firstOrNull { it.macAddress.equals(normMac, ignoreCase = true) }?.let { return it }
        }
        interfaces.firstOrNull { trimmed in it.ipv4Addresses }?.let { return it }

        val guidMatch = Regex("""\{[0-9A-Fa-f-]{36}\}""").find(trimmed)?.value
        if (guidMatch != null) {
            interfaces.firstOrNull {
                it.name.contains(guidMatch, ignoreCase = true) ||
                    it.displayName.contains(guidMatch, ignoreCase = true)
            }?.let { return it }
        }

        val partialMatches = interfaces.filter {
            it.displayName.contains(trimmed, ignoreCase = true) ||
                it.name.contains(trimmed, ignoreCase = true)
        }
        return partialMatches.singleOrNull()
    }

    fun detectBridgeEnvironment(
        osName: String = System.getProperty("os.name", ""),
        env: Map<String, String> = System.getenv(),
        interfaces: List<HostNetworkInterfaceInfo> = emptyList(),
        fileExists: (String) -> Boolean = { rawPath -> runCatching { Files.exists(Path.of(rawPath)) }.getOrDefault(false) },
    ): BridgeEnvironmentProbe {
        val lowerOs = osName.lowercase(Locale.ROOT)
        if (lowerOs.contains("win")) {
            val sysRoot = (env["SystemRoot"] ?: env["WINDIR"] ?: "C:\\Windows").trimEnd('\\', '/')
            val npcap = fileExists("$sysRoot\\System32\\Npcap\\wpcap.dll") ||
                fileExists("$sysRoot\\System32\\Npcap\\Packet.dll")
            val winpcap = fileExists("$sysRoot\\System32\\wpcap.dll")
            val tapNic = interfaces.firstOrNull(::isWindowsTapAdapter)
            val tapInstalled = tapNic != null
            val preferred = when {
                npcap -> "NPCAP_L2"
                tapInstalled -> "TAP_WINDOWS6_L2"
                winpcap -> "WINPCAP_L2"
                else -> "NIC_BOUND_SOCKET"
            }
            return BridgeEnvironmentProbe(
                osFamily = "windows",
                isWindows = true,
                npcapInstalled = npcap,
                winpcapInstalled = winpcap,
                tapWindowsInstalled = tapInstalled,
                linuxTunAvailable = false,
                preferredBackend = preferred,
                tapAdapterName = tapNic?.displayName.orEmpty(),
            )
        }
        if (lowerOs.contains("mac") || lowerOs.contains("darwin")) {
            return BridgeEnvironmentProbe(
                osFamily = "macos",
                isWindows = false,
                npcapInstalled = false,
                winpcapInstalled = false,
                tapWindowsInstalled = false,
                linuxTunAvailable = false,
                preferredBackend = "NIC_BOUND_SOCKET",
            )
        }
        val tunAvailable = fileExists("/dev/net/tun")
        return BridgeEnvironmentProbe(
            osFamily = "linux",
            isWindows = false,
            npcapInstalled = false,
            winpcapInstalled = false,
            tapWindowsInstalled = false,
            linuxTunAvailable = tunAvailable,
            preferredBackend = if (tunAvailable) "LINUX_TAP_OR_NIC_BOUND" else "NIC_BOUND_SOCKET",
        )
    }
}

/**
 * Atomic YAML persistence store for Desktop Wi-Fi network settings (`configs/wifi-network.yaml`).
 *
 * Kept separate from `profile.yaml` (`UserProfileTest` enforces exact 5 UI/theme keys) and from
 * immutable component baseline YAMLs (`ReleaseLaunchConfigTest`), while surviving emulator restarts.
 */
object WifiConfigStore {
    private val log = LoggerFactory.getLogger(WifiConfigStore::class.java)
    const val SCHEMA_VERSION = 1
    const val FILE_NAME = "wifi-network.yaml"

    fun defaultPath(home: Path, runtimeConfig: Map<String, Any?> = emptyMap()): Path {
        val configsDir = home.resolve("configs")
        if (Files.isDirectory(configsDir)) {
            return configsDir.resolve(FILE_NAME).toAbsolutePath().normalize()
        }
        val configDir = home.resolve("config")
        if (Files.isDirectory(configDir)) {
            return configDir.resolve(FILE_NAME).toAbsolutePath().normalize()
        }
        val compCfgRel = (runtimeConfig["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }
        if (compCfgRel != null) {
            val compCfgParent = home.resolve(compCfgRel).parent
            if (compCfgParent != null) {
                return compCfgParent.resolve(FILE_NAME).toAbsolutePath().normalize()
            }
        }
        return configsDir.resolve(FILE_NAME).toAbsolutePath().normalize()
    }

    fun loadOrNull(path: Path, base: WifiNetworkConfig): WifiNetworkConfig? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val raw = Formats.readData(path)
            WifiNetworkValidation.applyPatchToConfig(base, raw)
        }.onFailure { err ->
            log.warn("[FALLBACK][wifi] Failed to parse persisted Wi-Fi settings at {}: {}", path, err.message)
        }.getOrNull()
    }

    fun loadPortForwardsOrNull(path: Path): List<String>? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val raw = Formats.readData(path)
            if (raw.containsKey("portForwards") || raw.containsKey("wifiPortForwards")) {
                WifiNetworkValidation.parseAndValidatePortForwards(
                    raw["portForwards"] ?: raw["wifiPortForwards"],
                    "portForwards",
                )
            } else {
                null
            }
        }.getOrNull()
    }

    fun save(path: Path, config: WifiNetworkConfig, portForwards: List<String> = emptyList()) {
        val payload = linkedMapOf<String, Any>(
            "schema" to SCHEMA_VERSION,
            "enabled" to config.enabled,
            "mode" to config.mode.name,
            "ssid" to config.ssid,
            "passphrase" to config.passphrase,
            "macAddress" to config.macAddress,
            "subnetCidr" to config.subnetCidr,
            "gatewayIp" to config.gatewayIp,
            "guestIp" to config.guestIp,
            "dnsServers" to config.dnsServers.toList(),
            "bridgeInterface" to config.bridgeInterface,
            "hostOnlyLoopbackForward" to config.hostOnlyLoopbackForward,
            "hostOnlySubnetCidr" to config.hostOnlySubnetCidr,
            "portForwards" to portForwards.toList(),
        )
        Formats.atomicYaml(path, payload)
    }
}

/**
 * Host-side coordinator for Wi-Fi network configuration, persistence (`configs/wifi-network.yaml`),
 * cross-platform NIC/DNS discovery, and live synchronization with `bcm43438.control` ([WirelessControlPort]).
 */
class HostWifiRuntime(
    val home: Path,
    private val runtimeConfig: Map<String, Any?>,
    private val owner: OwnerLoop,
    private val hostProvider: () -> ComponentHost,
    cliOverrides: WifiLaunchCliOverrides = WifiLaunchCliOverrides(),
    val storePath: Path = WifiConfigStore.defaultPath(home, runtimeConfig),
    private val persistChanges: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(HostWifiRuntime::class.java)
    private val lock = Any()

    @Volatile
    private var hasExplicitConfiguration: Boolean =
        runtimeConfig.containsKey(WifiNetworkValidation.CONFIG_KEY) ||
            Files.isRegularFile(storePath) ||
            cliOverrides.hasOverrides()

    @Volatile
    private var currentPortForwards: List<String> = run {
        val fromYaml = ((runtimeConfig[WifiNetworkValidation.CONFIG_KEY] as? Map<*, *>)?.get("portForwards"))
            ?.let { WifiNetworkValidation.parseAndValidatePortForwards(it, "wifi.portForwards") }
            ?: emptyList()
        val fromStore = WifiConfigStore.loadPortForwardsOrNull(storePath) ?: fromYaml
        cliOverrides.portForwards ?: fromStore
    }

    @Volatile
    private var currentConfig: WifiNetworkConfig = run {
        val seed = WifiNetworkConfig.DEFAULT.copy(macAddress = WifiNetworkValidation.DEFAULT_FUJI_WIFI_MAC)
        val fromEmuYaml = WifiNetworkValidation.resolveFromEmulatorYaml(runtimeConfig, seed)
        val fromStore = WifiConfigStore.loadOrNull(storePath, fromEmuYaml) ?: fromEmuYaml
        val resolved = cliOverrides.applyTo(fromStore)
        if (cliOverrides.hasOverrides() && persistChanges) {
            runCatching { WifiConfigStore.save(storePath, resolved, currentPortForwards) }
                .onFailure { err -> log.warn("Unable to persist CLI Wi-Fi overrides to {}: {}", storePath, err.message) }
        }
        resolved
    }

    fun configSnapshot(): WifiNetworkConfig = currentConfig

    fun portForwardsSnapshot(): List<String> = currentPortForwards

    /**
     * Synchronizes [currentConfig] into `bcm43438` right after `host.load()`, cold reset, or live edit.
     * Must be invoked on [owner].
     */
    fun syncToHardwareOnOwner() {
        owner.requireOwner()
        val host = hostProvider()
        val bcmLoaded = host.components().singleOrNull { it.descriptor.id == "bcm43438" }
        val port = host.ports.find("bcm43438.control", WirelessControlPort::class.java)
        if (bcmLoaded != null) {
            val bcmEnabled = (bcmLoaded.config["enabled"] as? Boolean) == true
            if (!hasExplicitConfiguration) {
                if (port != null) {
                    runCatching { port.getNetworkConfig() }.getOrNull()?.let { hwCfg ->
                        currentConfig = hwCfg
                    }
                } else {
                    val hwMac = (bcmLoaded.config["wifiMacAddr"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    if (hwMac != null && hwMac != currentConfig.macAddress) {
                        currentConfig = currentConfig.copy(macAddress = hwMac)
                    }
                }
                if (!bcmEnabled) return
            }
            val hwMac = (bcmLoaded.config["wifiMacAddr"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (hwMac != null && currentConfig.macAddress == WifiNetworkValidation.DEFAULT_FUJI_WIFI_MAC && hwMac != currentConfig.macAddress) {
                currentConfig = currentConfig.copy(macAddress = hwMac)
            }
            val liveKeys = bcmLoaded.descriptor.settings.asSequence().filter { it.live }.map { it.key }.toSet()
            val fullPatch = LinkedHashMap<String, Any>(currentConfig.toComponentSettingsPatch())
            fullPatch["wifiPortForwards"] = currentPortForwards.joinToString(",")
            if (hasExplicitConfiguration && currentConfig.enabled && "enabled" in liveKeys && !bcmEnabled) {
                fullPatch["enabled"] = true
            }
            val candidatePatch = fullPatch.filterKeys { it in liveKeys }
            if (candidatePatch.isNotEmpty()) {
                runCatching {
                    host.patchLatestDirect("bcm43438", candidatePatch, persistToDisk = false)
                }.onFailure { err ->
                    log.debug("Skipping bcm43438 scalar settings sync: {}", err.message)
                }
            }
        }
        if (port != null && hasExplicitConfiguration) {
            val interfaces = runCatching { port.listHostNetworkInterfaces() }.getOrDefault(emptyList())
            val effectiveCfg = resolveEffectivePortConfig(currentConfig, interfaces)
            val actionPayload = linkedMapOf<String, Any?>(
                "action" to "wifi.reconfigure",
                "portForwards" to currentPortForwards,
                "wifiPortForwards" to currentPortForwards.joinToString(","),
            ) + effectiveCfg.toMap()
            runCatching { port.applyWirelessAction(actionPayload) }
                .onFailure {
                    runCatching { port.reconfigureNetwork(effectiveCfg) }
                        .onFailure { err ->
                            log.debug("bcm43438.control reconfigureNetwork deferred or unsupported: {}", err.message)
                        }
                }
        }
    }

    /**
     * Observes and persists Wi-Fi config changes arriving through HTTP `POST /api/wireless`
     * (`action = "wifi.reconfigure"` or `"wifi.reset"`) so HTTP, Console, and Desktop UI stay 100% aligned.
     */
    fun observeExternalWirelessAction(payload: Map<String, Any?>) {
        val action = (payload["action"] as? String)?.trim()?.lowercase(Locale.ROOT) ?: return
        synchronized(lock) {
            when (action) {
                "wifi.reconfigure" -> {
                    hasExplicitConfiguration = true
                    val updated = WifiNetworkValidation.applyPatchToConfig(currentConfig, payload)
                    if (payload.containsKey("portForwards") || payload.containsKey("wifiPortForwards")) {
                        currentPortForwards = WifiNetworkValidation.parseAndValidatePortForwards(
                            payload["portForwards"] ?: payload["wifiPortForwards"],
                            "portForwards",
                        )
                    }
                    currentConfig = updated
                    if (persistChanges) {
                        runCatching { WifiConfigStore.save(storePath, updated, currentPortForwards) }
                    }
                }
                "wifi.reset" -> {
                    hasExplicitConfiguration = true
                    val resetCfg = defaultResetConfig()
                    currentConfig = resetCfg
                    currentPortForwards = emptyList()
                    if (persistChanges) {
                        runCatching { WifiConfigStore.save(storePath, resetCfg, emptyList()) }
                    }
                }
            }
        }
    }

    fun listHostInterfaces(includeDown: Boolean = false, includeLoopback: Boolean = false): List<HostNetworkInterfaceInfo> {
        val raw = runCatching {
            owner.submit {
                val port = hostProvider().ports.find("bcm43438.control", WirelessControlPort::class.java)
                port?.listHostNetworkInterfaces(includeDown = includeDown, includeLoopback = includeLoopback)
            }.join()
        }.getOrNull() ?: HostNetworkInterfaceInfo.discoverAll(includeDown = includeDown, includeLoopback = includeLoopback)
        return if (!includeDown && !includeLoopback) {
            HostNetworkCatalog.filterBridgeCandidates(raw)
        } else {
            raw
        }
    }

    fun statusMap(): Map<String, Any?> {
        val cfg = currentConfig
        val interfaces = HostNetworkCatalog.filterBridgeCandidates(
            HostNetworkInterfaceInfo.discoverAll(includeDown = false, includeLoopback = false),
        )
        val bridgeEnv = HostNetworkCatalog.detectBridgeEnvironment(interfaces = interfaces)
        val matchedBridgeNic = cfg.bridgeInterface.takeIf { it.isNotBlank() }?.let { query ->
            HostNetworkCatalog.resolveAdapter(query, interfaces)
        }

        val hwRaw = runCatching {
            owner.submit {
                val port = hostProvider().ports.find("bcm43438.control", WirelessControlPort::class.java)
                val bcmEnabled = hostProvider().components().singleOrNull { it.descriptor.id == "bcm43438" }
                    ?.config?.get("enabled") as? Boolean ?: false
                val rawMap = port?.wirelessStatus()
                val typedStatus = port?.let { p ->
                    val netSub = rawMap?.get("wifiNetwork") as? Map<*, *>
                    if (netSub != null) WifiNetworkStatus.fromMap(netSub)
                    else if (
                        (rawMap?.get("wifi") as? Map<*, *>)?.containsKey("networkStatus") == true ||
                        rawMap?.containsKey("wifiNetworkStatus") == true ||
                        rawMap?.containsKey("guestIp") == true
                    ) {
                        runCatching { p.getNetworkStatus() }.getOrNull()
                    } else {
                        null
                    }
                }
                Quadruple(port != null, bcmEnabled, rawMap, typedStatus)
            }.join()
        }.getOrElse { Quadruple(false, false, null, null) }

        val (portAvailable, componentEnabled, rawWirelessMap, portTypedStatus) = hwRaw

        val effectiveDns = HostDnsResolver.resolveEffectiveDnsServers(cfg.dnsServers)
        val liveStatus = if (portTypedStatus != null) {
            portTypedStatus.copy(
                dnsServers = portTypedStatus.dnsServers.ifEmpty { effectiveDns },
                bridgeBackend = portTypedStatus.bridgeBackend.ifBlank {
                    if (portTypedStatus.mode == WifiNetworkMode.BRIDGE) bridgeEnv.preferredBackend else "NONE"
                },
            )
        } else {
            val wifiSub = rawWirelessMap?.get("wifi") as? Map<*, *>
            val joined = (wifiSub?.get("associated") as? Boolean)
                ?: (rawWirelessMap?.get("wifiPeerConnected") as? Boolean)
                ?: false
            val assocSsid = (wifiSub?.get("ssid") as? String)?.takeIf { it.isNotBlank() }
                ?: if (cfg.enabled && joined) cfg.ssid else ""
            val fallbackBridgeBackend = if (cfg.mode == WifiNetworkMode.BRIDGE) bridgeEnv.preferredBackend else "NONE"
            val fallbackBridgeState = when {
                !cfg.enabled -> "DISABLED"
                cfg.mode != WifiNetworkMode.BRIDGE -> "INACTIVE"
                cfg.bridgeInterface.isNotBlank() && matchedBridgeNic == null -> "ADAPTER_NOT_FOUND"
                portAvailable -> "READY (${bridgeEnv.preferredBackend})"
                else -> "STANDBY"
            }
            val activeGuestIp = if (!cfg.enabled) "" else cfg.guestIp
            val activeGatewayIp = if (!cfg.enabled) "" else cfg.gatewayIp
            WifiNetworkStatus(
                mode = cfg.mode,
                linkUp = cfg.enabled && joined,
                associatedSsid = if (cfg.enabled) assocSsid else "",
                guestIp = activeGuestIp,
                gatewayIp = activeGatewayIp,
                macAddress = (wifiSub?.get("macAddr") as? String)?.takeIf { it.isNotBlank() } ?: cfg.macAddress,
                bridgeInterface = matchedBridgeNic?.name ?: cfg.bridgeInterface,
                bridgeState = fallbackBridgeState,
                activeTcpSessions = ((wifiSub?.get("activeTcpSessions") as? Number)?.toInt()) ?: 0,
                activeUdpSessions = ((wifiSub?.get("activeUdpSessions") as? Number)?.toInt()) ?: 0,
                txPackets = ((wifiSub?.get("txFrames") as? Number)?.toLong()) ?: 0L,
                rxPackets = ((wifiSub?.get("rxFrames") as? Number)?.toLong()) ?: 0L,
                txBytes = ((wifiSub?.get("txBytes") as? Number)?.toLong()) ?: 0L,
                rxBytes = ((wifiSub?.get("rxBytes") as? Number)?.toLong()) ?: 0L,
                lastError = if (cfg.mode == WifiNetworkMode.BRIDGE && cfg.bridgeInterface.isNotBlank() && matchedBridgeNic == null) {
                    "Configured bridge interface '${cfg.bridgeInterface}' not found among active host adapters"
                } else {
                    null
                },
                enabled = cfg.enabled,
                bridgeBackend = fallbackBridgeBackend,
                dnsServers = effectiveDns,
            )
        }

        val forwards = currentPortForwards
        return linkedMapOf(
            "componentEnabled" to componentEnabled,
            "controlPortAvailable" to portAvailable,
            "persistedPath" to storePath.toString(),
            "config" to (cfg.toMap() + ("portForwards" to forwards)),
            "portForwards" to forwards,
            "status" to liveStatus.toMap(),
            "bridgeEnvironment" to bridgeEnv.toMap(),
            "matchedBridgeAdapter" to matchedBridgeNic?.toMap(),
            "effectiveDnsServers" to (liveStatus.dnsServers.ifEmpty { effectiveDns }),
        )
    }

    fun reconfigure(patch: Map<String, Any?>, persist: Boolean = persistChanges): Map<String, Any?> {
        synchronized(lock) {
            hasExplicitConfiguration = true
            val next = WifiNetworkValidation.applyPatchToConfig(currentConfig, patch)
            if (patch.containsKey("portForwards") || patch.containsKey("wifiPortForwards")) {
                currentPortForwards = WifiNetworkValidation.parseAndValidatePortForwards(
                    patch["portForwards"] ?: patch["wifiPortForwards"],
                    "portForwards",
                )
            }
            currentConfig = next
            if (persist) {
                WifiConfigStore.save(storePath, next, currentPortForwards)
            }
            owner.submit {
                syncToHardwareOnOwner()
            }.join()
        }
        return statusMap()
    }

    fun reconfigureAsync(patch: Map<String, Any?>, persist: Boolean = persistChanges): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.supplyAsync { reconfigure(patch, persist = persist) }

    fun reset(persist: Boolean = persistChanges): Map<String, Any?> {
        synchronized(lock) {
            hasExplicitConfiguration = true
            val resetCfg = defaultResetConfig()
            currentConfig = resetCfg
            currentPortForwards = emptyList()
            if (persist) {
                WifiConfigStore.save(storePath, resetCfg, emptyList())
            }
            owner.submit {
                val port = hostProvider().ports.find("bcm43438.control", WirelessControlPort::class.java)
                if (port != null) {
                    runCatching { port.resetNetworkConfig() }
                }
                syncToHardwareOnOwner()
            }.join()
        }
        return statusMap()
    }

    fun resetAsync(persist: Boolean = persistChanges): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.supplyAsync { reset(persist = persist) }

    private fun defaultResetConfig(): WifiNetworkConfig {
        val hwMac = runCatching {
            owner.submit {
                hostProvider().components().singleOrNull { it.descriptor.id == "bcm43438" }
                    ?.config?.get("wifiMacAddr") as? String
            }.join()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: WifiNetworkValidation.DEFAULT_FUJI_WIFI_MAC
        return WifiNetworkConfig.DEFAULT.copy(macAddress = hwMac)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun resolveEffectivePortConfig(
        cfg: WifiNetworkConfig,
        interfaces: List<HostNetworkInterfaceInfo>,
    ): WifiNetworkConfig {
        if (cfg.bridgeInterface.isBlank()) return cfg
        val matched = HostNetworkCatalog.resolveAdapter(cfg.bridgeInterface, interfaces)
        return if (matched != null && matched.name.isNotBlank()) {
            cfg.copy(bridgeInterface = matched.name)
        } else {
            cfg
        }
    }
}
