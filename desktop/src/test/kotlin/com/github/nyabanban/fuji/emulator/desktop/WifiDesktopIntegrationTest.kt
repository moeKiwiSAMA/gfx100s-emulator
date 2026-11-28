package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.HostDnsResolver
import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import com.github.nyabanban.fuji.emulator.api.WifiNetworkMode
import com.github.nyabanban.fuji.emulator.api.WirelessControlPort
import com.github.nyabanban.fuji.emulator.desktop.console.ConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.console.RuntimeConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.DiagnosticsRefreshController
import com.github.nyabanban.fuji.emulator.desktop.panels.WifiAdapterChoice
import com.github.nyabanban.fuji.emulator.desktop.panels.WifiPanelSnapshot
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import com.github.nyabanban.fuji.emulator.desktop.wifi.HostNetworkCatalog
import com.github.nyabanban.fuji.emulator.desktop.wifi.WifiConfigStore
import com.github.nyabanban.fuji.emulator.desktop.wifi.WifiNetworkValidation
import com.github.nyabanban.fuji.emulator.desktop.wifi.parseWifiLaunchArgs
import com.github.nyabanban.fuji.emulator.runtime.AsyncPerfProfiler
import com.github.nyabanban.fuji.emulator.runtime.FirmwareTelemetry
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiDesktopIntegrationTest {

    @Test
    fun cliWifiLaunchArgsParseAllModesAndRejectInvalidInputs() {
        val defaults = parseWifiLaunchArgs(emptyArray())
        assertFalse(defaults.hasOverrides())

        val bridgeCli = parseWifiLaunchArgs(
            arrayOf(
                "--wifi-mode=bridge",
                "--wifi-ssid=GFX-Studio-LAN",
                "--wifi-passphrase=camerapass",
                "--wifi-bridge-iface=Intel(R) Wi-Fi 6 AX201 160MHz",
                "--wifi-subnet=10.42.0.0/24",
                "--wifi-dns=1.1.1.1,9.9.9.9",
                "--wifi-forward=3389:3389",
                "--wifi-forward", "127.0.0.1:8080:80/tcp",
            ),
        )
        assertTrue(bridgeCli.hasOverrides())
        assertEquals(WifiNetworkMode.BRIDGE, bridgeCli.mode)
        assertEquals("GFX-Studio-LAN", bridgeCli.ssid)
        assertEquals("camerapass", bridgeCli.passphrase)
        assertEquals("Intel(R) Wi-Fi 6 AX201 160MHz", bridgeCli.bridgeInterface)
        assertEquals("10.42.0.0/24", bridgeCli.subnetCidr)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), bridgeCli.dnsServers)
        assertEquals(listOf("3389:3389", "127.0.0.1:8080:80/tcp"), bridgeCli.portForwards)

        val appliedBridge = bridgeCli.applyTo(WifiNetworkConfig.DEFAULT)
        assertEquals(WifiNetworkMode.BRIDGE, appliedBridge.mode)
        assertEquals("10.42.0.0/24", appliedBridge.subnetCidr)
        assertEquals("10.42.0.1", appliedBridge.gatewayIp)
        assertEquals("10.42.0.100", appliedBridge.guestIp)

        val hostOnlyCli = parseWifiLaunchArgs(
            arrayOf(
                "--wifi-mode", "hostonly",
                "--wifi-subnet", "192.168.88.0/24",
                "--wifi-bridge-iface", "auto",
                "--wifi-disabled",
                "--wifi-no-hostonly-loopback",
            ),
        )
        assertEquals(false, hostOnlyCli.enabled)
        assertEquals(WifiNetworkMode.HOST_ONLY, hostOnlyCli.mode)
        assertEquals("", hostOnlyCli.bridgeInterface)
        assertEquals(false, hostOnlyCli.hostOnlyLoopbackForward)

        val appliedHostOnly = hostOnlyCli.applyTo(WifiNetworkConfig.DEFAULT)
        assertFalse(appliedHostOnly.enabled)
        assertEquals(WifiNetworkMode.HOST_ONLY, appliedHostOnly.mode)
        assertEquals("192.168.88.0/24", appliedHostOnly.hostOnlySubnetCidr)
        assertEquals("192.168.88.1", appliedHostOnly.gatewayIp)
        assertEquals("192.168.88.100", appliedHostOnly.guestIp)
        assertFalse(appliedHostOnly.hostOnlyLoopbackForward)

        // Hyphenated / case-insensitive mode aliases
        assertEquals(WifiNetworkMode.HOST_ONLY, parseWifiLaunchArgs(arrayOf("--wifi-mode=host-only")).mode)
        assertEquals(WifiNetworkMode.NAT, parseWifiLaunchArgs(arrayOf("--wifi-mode=NAT")).mode)

        // Invalid flags fail fast
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-mode=invalid")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-mode=")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-mode")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-subnet=999.0.0.0/24")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-subnet=192.168.1.0/31")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-dns=not-an-ip")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-ssid=")) }
        assertThrows<IllegalArgumentException> { parseWifiLaunchArgs(arrayOf("--wifi-forward=99999:3389")) }
    }

    @Test
    fun emulatorYamlWifiBlockValidatesCleanly(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val baseConfig = mapOf<String, Any>(
            "schema" to 1,
            "components" to "compoments",
            "httpPort" to 0,
            "executionMode" to RelaxedPeripheralFallback.MODE,
            "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
            "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
            "instructionBudget" to 1000,
            "enabledDevices" to listOf("soc-timer"),
        )

        validateLaunchConfiguration(baseConfig + ("wifi" to false))
        validateLaunchConfiguration(
            baseConfig + (
                "wifi" to mapOf(
                    "enabled" to true,
                    "mode" to "bridge",
                    "ssid" to "FujiLAN",
                    "bridgeInterface" to "eth0",
                    "subnetCidr" to "192.168.50.0/24",
                    "dnsServers" to listOf("8.8.8.8", "1.1.1.1"),
                    "hostOnlyLoopbackForward" to true,
                )
            ),
        )

        assertThrows<IllegalArgumentException> {
            validateLaunchConfiguration(baseConfig + ("wifi" to mapOf("unknownKey" to 123)))
        }
        assertThrows<IllegalArgumentException> {
            validateLaunchConfiguration(baseConfig + ("wifi" to mapOf("mode" to "bogus-mode")))
        }
    }

    @Test
    fun wifiSettingsPersistAcrossEmulatorRuntimeRestartsWithoutTouchingUserProfile(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val home = IntegrationTest().fixture()
        try {
            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer"),
                "telemetry" to mapOf("enabled" to false),
            )

            val storePath = WifiConfigStore.defaultPath(home, config)

            // Boot #1: switch NAT -> BRIDGE + custom SSID, subnet, DNS, and Windows friendly adapter name
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt1 ->
                assertEquals(WifiNetworkMode.NAT, rt1.wifiRuntime.configSnapshot().mode)
                rt1.wifiRuntime.reconfigure(
                    mapOf(
                        "enabled" to true,
                        "mode" to "BRIDGE",
                        "ssid" to "GFX-WinBridge",
                        "passphrase" to "fuji2026",
                        "bridgeInterface" to "Intel(R) Wi-Fi 6 AX201 160MHz",
                        "subnetCidr" to "10.77.0.0/24",
                        "dnsServers" to listOf("223.5.5.5", "1.1.1.1"),
                    ),
                )
                assertTrue(Files.isRegularFile(storePath), "Expected persisted wifi-network.yaml at $storePath")
                // Verify profile.yaml schema keys remain 100% clean (exact 5 keys)
                val profilePath = UserProfileStore.defaultPath(home)
                if (Files.isRegularFile(profilePath)) {
                    val profileMap = Formats.read(profilePath)
                    assertEquals(setOf("schema", "language", "theme", "windows", "graphics"), profileMap.keys)
                }
            }

            // Boot #2: verify persisted BRIDGE mode & parameters survive restart
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt2 ->
                val restored = rt2.wifiRuntime.configSnapshot()
                assertTrue(restored.enabled)
                assertEquals(WifiNetworkMode.BRIDGE, restored.mode)
                assertEquals("GFX-WinBridge", restored.ssid)
                assertEquals("fuji2026", restored.passphrase)
                assertEquals("Intel(R) Wi-Fi 6 AX201 160MHz", restored.bridgeInterface)
                assertEquals("10.77.0.0/24", restored.subnetCidr)
                assertEquals("10.77.0.1", restored.gatewayIp)
                assertEquals("10.77.0.100", restored.guestIp)
                assertEquals(listOf("223.5.5.5", "1.1.1.1"), restored.dnsServers)

                // Switch to HOST_ONLY via RuntimeConsoleBackend + EmulatorConsole
                val out = ByteArrayOutputStream()
                val console = EmulatorConsole(
                    backend = RuntimeConsoleBackend(
                        runtime = rt2,
                        paused = AtomicBoolean(false),
                        stopRequested = AtomicBoolean(false),
                        onBootRequested = { "ok" },
                        onStopRequested = {},
                    ),
                    input = ByteArrayInputStream(ByteArray(0)),
                    output = out,
                    interactivePrompt = false,
                    timeSupplier = { LocalTime.of(12, 0, 0) },
                )
                assertTrue(console.executeLine("wifi mode hostonly"))
                assertTrue(console.executeLine("wifi subnet 192.168.99.0/24"))
                assertTrue(console.executeLine("wifi forward 3389:3389"))
                assertTrue(console.executeLine("wifi loopback off"))
                console.close()
            }

            // Boot #3: verify HOST_ONLY settings + portForwards survived restart, then reset to defaults
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt3 ->
                val restoredHostOnly = rt3.wifiRuntime.configSnapshot()
                assertEquals(WifiNetworkMode.HOST_ONLY, restoredHostOnly.mode)
                assertEquals("192.168.99.0/24", restoredHostOnly.hostOnlySubnetCidr)
                assertEquals("192.168.99.1", restoredHostOnly.gatewayIp)
                assertEquals("192.168.99.100", restoredHostOnly.guestIp)
                assertFalse(restoredHostOnly.hostOnlyLoopbackForward)
                assertEquals(listOf("3389:3389"), rt3.wifiRuntime.portForwardsSnapshot())

                rt3.wifiRuntime.reset()
            }

            // Boot #4: verify reset defaults persisted cleanly
            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt4 ->
                val resetRestored = rt4.wifiRuntime.configSnapshot()
                assertTrue(resetRestored.enabled)
                assertEquals(WifiNetworkMode.NAT, resetRestored.mode)
                assertEquals("GFX100S-WiFi", resetRestored.ssid)
                assertEquals("192.168.42.0/24", resetRestored.subnetCidr)
                assertEquals(listOf("8.8.8.8", "1.1.1.1"), resetRestored.dnsServers)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun windows10And11DnsParsingNicFilteringMatchingAndBridgeHintsWorkCrossPlatform() {
        val englishWin11Ipconfig = """
            Windows IP Configuration

               Host Name . . . . . . . . . . . . : STUDIO-WIN11
               Primary Dns Suffix  . . . . . . . :

            Wireless LAN adapter Wi-Fi:

               Connection-specific DNS Suffix  . : lan
               Description . . . . . . . . . . . : Intel(R) Wi-Fi 6 AX201 160MHz
               Physical Address. . . . . . . . . : A4-B1-C2-D3-E4-F5
               IPv4 Address. . . . . . . . . . . : 192.168.31.88(Preferred)
               Default Gateway . . . . . . . . . : 192.168.31.1
               DNS Servers . . . . . . . . . . . : fe80::1%14
                                                   192.168.31.1
                                                   8.8.8.8
                                                   127.0.0.1
               NetBIOS over Tcpip. . . . . . . . : Enabled
        """.trimIndent()
        assertEquals(listOf("192.168.31.1", "8.8.8.8"), HostDnsResolver.parseWindowsIpconfigAll(englishWin11Ipconfig))

        val chineseWin11Ipconfig = """
            Windows IP 配置

            无线局域网适配器 WLAN:

               连接特定的 DNS 后缀 . . . . . . . : localdomain
               描述. . . . . . . . . . . . . . . : Realtek 8852BE Wireless LAN WiFi 6 PCI-E NIC
               物理地址. . . . . . . . . . . . . : 10-20-30-40-50-60
               IPv4 地址 . . . . . . . . . . . . : 192.168.1.55(首选)
               默认网关. . . . . . . . . . . . . : 192.168.1.1
               DNS 服务器  . . . . . . . . . . . : 223.5.5.5
                                                   119.29.29.29
                                                   169.254.1.1
        """.trimIndent()
        assertEquals(listOf("223.5.5.5", "119.29.29.29"), HostDnsResolver.parseWindowsIpconfigAll(chineseWin11Ipconfig))

        val simulatedWindowsInterfaces = listOf(
            HostNetworkInterfaceInfo(
                name = "wlan0",
                displayName = "Intel(R) Wi-Fi 6 AX201 160MHz",
                ipv4Addresses = listOf("192.168.31.88"),
                macAddress = "A4:B1:C2:D3:E4:F5",
                up = true,
                loopback = false,
                virtualOrTap = false,
            ),
            HostNetworkInterfaceInfo(
                name = "eth1",
                displayName = "Realtek PCIe GbE Family Controller {4D36E972-E325-11CE-BFC1-08002BE10318}",
                ipv4Addresses = listOf("10.0.0.25"),
                macAddress = "AA:BB:CC:DD:EE:01",
                up = true,
                loopback = false,
                virtualOrTap = false,
            ),
            HostNetworkInterfaceInfo(
                name = "ethernet_32770",
                displayName = "TAP-Windows Adapter V9",
                ipv4Addresses = emptyList(),
                macAddress = "00:FF:12:34:56:78",
                up = true,
                loopback = false,
                virtualOrTap = true,
            ),
            HostNetworkInterfaceInfo(
                name = "wan_miniport_ip",
                displayName = "WAN Miniport (IP)",
                ipv4Addresses = emptyList(),
                macAddress = "",
                up = true,
                loopback = false,
                virtualOrTap = true,
            ),
            HostNetworkInterfaceInfo(
                name = "wfp_filter_0",
                displayName = "WFP Native MAC Layer LightWeight Filter-0000",
                ipv4Addresses = emptyList(),
                macAddress = "",
                up = true,
                loopback = false,
                virtualOrTap = true,
            ),
            HostNetworkInterfaceInfo(
                name = "lo",
                displayName = "Software Loopback Interface 1",
                ipv4Addresses = listOf("127.0.0.1"),
                macAddress = "",
                up = true,
                loopback = true,
                virtualOrTap = false,
            ),
        )

        val filtered = HostNetworkCatalog.filterBridgeCandidates(simulatedWindowsInterfaces)
        assertEquals(listOf("wlan0", "eth1", "ethernet_32770"), filtered.map { it.name })

        // Match by name, Windows DisplayName, substring, Windows dashed MAC, GUID, and IPv4
        assertEquals("wlan0", HostNetworkCatalog.resolveAdapter("wlan0", filtered)?.name)
        assertEquals("wlan0", HostNetworkCatalog.resolveAdapter("Intel(R) Wi-Fi 6 AX201 160MHz", filtered)?.name)
        assertEquals("wlan0", HostNetworkCatalog.resolveAdapter("AX201", filtered)?.name)
        assertEquals("eth1", HostNetworkCatalog.resolveAdapter("AA-BB-CC-DD-EE-01", filtered)?.name)
        assertEquals("eth1", HostNetworkCatalog.resolveAdapter("\\Device\\NPF_{4D36E972-E325-11CE-BFC1-08002BE10318}", filtered)?.name)
        assertEquals("wlan0", HostNetworkCatalog.resolveAdapter("192.168.31.88", filtered)?.name)
        assertEquals("ethernet_32770", HostNetworkCatalog.resolveAdapter("TAP-Windows", filtered)?.name)
        assertNull(HostNetworkCatalog.resolveAdapter("nonexistent-nic", filtered))

        // Probe Windows Bridge Backend capabilities (Npcap -> TAP-Windows6 -> Zero-driver NIC-bound fallback)
        val winNpcap = HostNetworkCatalog.detectBridgeEnvironment(
            osName = "Windows 11",
            env = mapOf("SystemRoot" to "C:\\Windows"),
            interfaces = filtered,
            fileExists = { it == "C:\\Windows\\System32\\Npcap\\wpcap.dll" },
        )
        assertTrue(winNpcap.isWindows)
        assertTrue(winNpcap.npcapInstalled)
        assertEquals("NPCAP_L2", winNpcap.preferredBackend)

        val winTap = HostNetworkCatalog.detectBridgeEnvironment(
            osName = "Windows 10",
            env = mapOf("SystemRoot" to "C:\\Windows"),
            interfaces = filtered,
            fileExists = { false },
        )
        assertTrue(winTap.tapWindowsInstalled)
        assertEquals("TAP_WINDOWS6_L2", winTap.preferredBackend)
        assertEquals("TAP-Windows Adapter V9", winTap.tapAdapterName)

        val winFallback = HostNetworkCatalog.detectBridgeEnvironment(
            osName = "Windows 11",
            env = mapOf("SystemRoot" to "C:\\Windows"),
            interfaces = filtered.filterNot(HostNetworkCatalog::isWindowsTapAdapter),
            fileExists = { false },
        )
        assertEquals("NIC_BOUND_SOCKET", winFallback.preferredBackend)

        val choices = WifiAdapterChoice.buildChoices(filtered, configuredId = "custom-eth9")
        assertEquals(listOf("", "wlan0", "eth1", "ethernet_32770", "custom-eth9"), choices.map { it.id })
    }

    @Test
    fun interactiveConsoleWifiCommandsAndAllSixLocalePanelSnapshotsRenderCleanly(@TempDir dir: Path) {
        var currentCfg = WifiNetworkConfig.DEFAULT.copy(macAddress = "02:46:4A:10:00:00")
        val fakeNics = listOf(
            HostNetworkInterfaceInfo(
                name = "wlan0",
                displayName = "Intel(R) Wi-Fi 6 AX201",
                ipv4Addresses = listOf("192.168.1.20"),
                macAddress = "A4:B1:C2:D3:E4:F5",
                up = true,
                loopback = false,
                virtualOrTap = false,
            ),
        )
        val backend = object : ConsoleBackend {
            override val home: Path = dir
            override val telemetry: FirmwareTelemetry = FirmwareTelemetry()
            override val profiler: AsyncPerfProfiler = AsyncPerfProfiler(telemetry)
            override fun isPaused(): Boolean = false
            override fun setPaused(paused: Boolean): Boolean = false
            override fun isRunning(): Boolean = true
            override fun requestBoot(): String = "ok"
            override fun requestStop(): String = "stop"
            override fun listComponents(): List<String> = emptyList()
            override fun componentState(componentId: String): Map<String, Any?>? = null
            override fun patchComponent(componentId: String, changes: Map<String, Any>): Map<String, Any?> = emptyMap()
            override fun captureDisplay(portName: String): ConsoleBackend.CapturedFrame? = null

            override fun wifiInterfaces(): List<HostNetworkInterfaceInfo> = fakeNics

            override fun wifiStatus(): Map<String, Any?> = linkedMapOf(
                "config" to currentCfg.toMap(),
                "status" to linkedMapOf(
                    "enabled" to currentCfg.enabled,
                    "mode" to currentCfg.mode.name,
                    "linkUp" to currentCfg.enabled,
                    "associatedSsid" to if (currentCfg.enabled) currentCfg.ssid else "",
                    "guestIp" to currentCfg.guestIp,
                    "gatewayIp" to currentCfg.gatewayIp,
                    "macAddress" to currentCfg.macAddress,
                    "bridgeInterface" to currentCfg.bridgeInterface,
                    "bridgeBackend" to if (currentCfg.mode == WifiNetworkMode.BRIDGE) "NIC_BOUND_FALLBACK" else "NONE",
                    "bridgeState" to if (currentCfg.mode == WifiNetworkMode.BRIDGE) "ACTIVE_NIC_BOUND" else "INACTIVE",
                    "dnsServers" to currentCfg.dnsServers,
                    "activeTcpSessions" to 2,
                    "activeUdpSessions" to 1,
                    "txPackets" to 48L,
                    "rxPackets" to 52L,
                    "txBytes" to 16_384L,
                    "rxBytes" to 65_536L,
                    "lastError" to null,
                ),
                "bridgeEnvironment" to mapOf(
                    "isWindows" to true,
                    "npcapInstalled" to false,
                    "tapWindowsInstalled" to false,
                ),
                "effectiveDnsServers" to currentCfg.dnsServers,
            )

            override fun patchWifi(changes: Map<String, Any?>): Map<String, Any?> {
                currentCfg = WifiNetworkValidation.applyPatchToConfig(currentCfg, changes)
                return wifiStatus()
            }

            override fun resetWifi(): Map<String, Any?> {
                currentCfg = WifiNetworkConfig.DEFAULT.copy(macAddress = "02:46:4A:10:00:00")
                return wifiStatus()
            }
        }

        val out = ByteArrayOutputStream()
        val console = EmulatorConsole(
            backend = backend,
            input = ByteArrayInputStream(ByteArray(0)),
            output = out,
            interactivePrompt = false,
            timeSupplier = { LocalTime.of(12, 34, 56) },
        )

        assertTrue(console.executeLine("wifi status"))
        assertTrue(console.executeLine("wifi interfaces"))
        assertTrue(console.executeLine("wifi mode bridge"))
        assertEquals(WifiNetworkMode.BRIDGE, currentCfg.mode)
        assertTrue(console.executeLine("wifi bridge Intel(R) Wi-Fi 6 AX201"))
        assertEquals("Intel(R) Wi-Fi 6 AX201", currentCfg.bridgeInterface)
        assertTrue(console.executeLine("wlan status"))
        assertTrue(console.executeLine("wifi ssid FujiWin11AP"))
        assertEquals("FujiWin11AP", currentCfg.ssid)
        assertTrue(console.executeLine("wifi pass secret99"))
        assertEquals("secret99", currentCfg.passphrase)
        assertTrue(console.executeLine("wifi subnet 172.16.50.0/24"))
        assertEquals("172.16.50.0/24", currentCfg.subnetCidr)
        assertEquals("172.16.50.1", currentCfg.gatewayIp)
        assertEquals("172.16.50.100", currentCfg.guestIp)
        assertTrue(console.executeLine("wifi dns 1.1.1.1,8.8.4.4"))
        assertEquals(listOf("1.1.1.1", "8.8.4.4"), currentCfg.dnsServers)
        assertTrue(console.executeLine("wifi off"))
        assertFalse(currentCfg.enabled)
        assertTrue(console.executeLine("wifi on"))
        assertTrue(currentCfg.enabled)
        assertTrue(console.executeLine("wifi mode bogus"))
        assertTrue(console.executeLine("wifi reset"))
        assertEquals(WifiNetworkMode.NAT, currentCfg.mode)
        assertEquals("GFX100S-WiFi", currentCfg.ssid)
        console.close()

        val logText = out.toString(StandardCharsets.UTF_8)
        assertTrue(logText.contains("Wi-Fi Network: state=ON | mode=NAT (nat) | link=UP"), logText)
        assertTrue(logText.contains("Host network interfaces (1):"), logText)
        assertTrue(logText.contains("Wi-Fi mode -> BRIDGE (bridge)"), logText)
        assertTrue(logText.contains("Windows tip: Install Npcap or OpenVPN TAP-Windows6"), logText)
        assertTrue(logText.contains("Unsupported Wi-Fi mode \"bogus\""), logText)
        assertTrue(logText.contains("Wi-Fi configuration reset to defaults"), logText)

        // Verify WifiPanelSnapshot renders cleanly in all 6 supported languages without formatting errors
        backend.patchWifi(mapOf("mode" to "BRIDGE", "bridgeInterface" to "wlan0"))
        val snap = WifiPanelSnapshot.fromMap(backend.wifiStatus())
        try {
            for (lang in I18n.supportedLanguageCodes()) {
                I18n.setLocale(lang)
                assertTrue(I18n.t("app.tab.wifi").isNotBlank())
                assertTrue(snap.bridgeBackendStatusText().contains("NIC-Bound Fallback"))
                assertTrue(snap.bridgeHintText().isNotBlank())
                assertTrue(snap.linkSummaryText().contains("GFX100S-WiFi"))
                assertTrue(snap.addressingSummaryText().contains("192.168.42.100"))
                assertTrue(snap.trafficSummaryText().contains("16.0 KiB"))
                assertTrue(snap.trafficSummaryText().contains("64.0 KiB"))
            }
        } finally {
            I18n.resetToDefault()
        }

        // Verify DiagnosticsRefreshController Wi-Fi polling throttle
        val refreshCtrl = DiagnosticsRefreshController(wifiIdleIntervalNanos = 1_000_000_000L)
        assertTrue(refreshCtrl.shouldFetchWifi(wifiTabVisible = false, nowNanos = 100L), "Initial fetch must run")
        refreshCtrl.markWifiFetchDispatched(100L)
        assertFalse(refreshCtrl.shouldFetchWifi(wifiTabVisible = false, nowNanos = 500_000_000L))
        assertTrue(refreshCtrl.shouldFetchWifi(wifiTabVisible = true, nowNanos = 500_000_000L), "Visible tab polls live")
        assertTrue(refreshCtrl.shouldFetchWifi(wifiTabVisible = false, nowNanos = 1_000_000_200L), "Idle interval elapsed")
        assertNotNull(snap)
    }

    @Test
    fun liveBcm43438ControlPortAndHttpApiStaySynchronizedWithDesktopWifiStore(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val home = IntegrationTest().fixture()
        try {
            val freeForwardPort = ServerSocket(0).use { it.localPort }
            val config = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer", "bcm43438"),
                "telemetry" to mapOf("enabled" to false),
            )

            EmulatorRuntime(home, config, telemetryEnv = emptyMap()).use { rt ->
                rt.serve()
                val port = rt.owner.submit {
                    rt.host.ports.find("bcm43438.control", WirelessControlPort::class.java)
                }.join()
                assertNotNull(port, "bcm43438.control WirelessControlPort must be registered even before Wi-Fi is enabled")

                // Reconfigure via Desktop HostWifiRuntime -> verify bcm43438 hardware component receives live config
                val stAfterNat = rt.wifiRuntime.reconfigure(
                    mapOf(
                        "enabled" to true,
                        "mode" to "NAT",
                        "ssid" to "FujiLiveNAT",
                        "subnetCidr" to "10.55.0.0/24",
                        "portForwards" to listOf("127.0.0.1:$freeForwardPort:3389"),
                    ),
                )
                assertEquals(true, stAfterNat["controlPortAvailable"])
                val hwCfgNat = rt.owner.submit { port.getNetworkConfig() }.join()
                assertEquals(WifiNetworkMode.NAT, hwCfgNat.mode)
                assertEquals("FujiLiveNAT", hwCfgNat.ssid)
                assertEquals("10.55.0.0/24", hwCfgNat.subnetCidr)

                // Reconfigure via HTTP POST /api/wireless -> verify both bcm43438 AND wifi-network.yaml update in lockstep
                val apiPort = requireNotNull(rt.server).address.port
                val http = HttpClient.newHttpClient()
                val reqBody = """{"action":"wifi.reconfigure","enabled":true,"mode":"HOST_ONLY","ssid":"FujiHttpHostOnly","hostOnlySubnetCidr":"192.168.66.0/24"}"""
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$apiPort/api/wireless"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, resp.statusCode(), resp.body())
                assertEquals(WifiNetworkMode.HOST_ONLY, rt.wifiRuntime.configSnapshot().mode)
                assertEquals("FujiHttpHostOnly", rt.wifiRuntime.configSnapshot().ssid)
                assertEquals("192.168.66.0/24", rt.wifiRuntime.configSnapshot().hostOnlySubnetCidr)

                val hwStatusHostOnly = rt.owner.submit { port.getNetworkStatus() }.join()
                assertEquals(WifiNetworkMode.HOST_ONLY, hwStatusHostOnly.mode)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
