package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.HostDnsResolver
import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import com.github.nyabanban.fuji.emulator.api.WifiNetworkMode
import com.github.nyabanban.fuji.emulator.api.WifiNetworkStatus
import com.github.nyabanban.fuji.emulator.api.WifiPortForwardRule
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Full-featured user-space L2/L3/L4 Wi-Fi networking engine for `BCM43430A1` (`bcm43438`),
 * supporting `NAT`, `BRIDGE` (raw L2 + universal `NIC_BOUND_FALLBACK`), and `HOST_ONLY` modes
 * across Windows 10/11, Linux, and macOS with zero idle host sockets.
 */
internal class WifiNetworkEngine(
    initialConfig: WifiNetworkConfig,
    private val guestMacProvider: () -> ByteArray,
    private val linkUpProvider: () -> Boolean,
    private val associatedSsidProvider: () -> String,
    private val rxEthernetSink: (ByteArray) -> Unit,
    private val osNameProvider: () -> String = { System.getProperty("os.name", "") },
    private val hostInterfacesProvider: () -> List<HostNetworkInterfaceInfo> = {
        HostNetworkInterfaceInfo.discoverAll(includeDown = true, includeLoopback = false)
    },
    private val customBridgeFactory: ((WifiNetworkConfig, (ByteArray) -> Unit) -> BridgeTransport?)? = null,
) : AutoCloseable {

    companion object {
        val GATEWAY_MAC = byteArrayOf(0x02, 0x46, 0x4A, 0xFE.toByte(), 0x00, 0x01)
        val BROADCAST_MAC = ByteArray(6) { 0xFF.toByte() }
        val ZERO_IP = ByteArray(4)
        val BROADCAST_IP = ByteArray(4) { 0xFF.toByte() }

        const val ETHER_TYPE_IPV4 = 0x0800
        const val ETHER_TYPE_ARP = 0x0806

        const val IP_PROTO_ICMP = 1
        const val IP_PROTO_TCP = 6
        const val IP_PROTO_UDP = 17

        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        private const val DHCP_MAGIC_COOKIE = 0x63825363
        private const val MAX_TCP_MSS = 1400
        private const val SESSION_IDLE_TIMEOUT_NS = 120_000_000_000L // 120s

        fun parseIpv4Bytes(text: String): ByteArray {
            val parts = text.trim().split('.')
            require(parts.size == 4) { "Invalid IPv4 address '$text'" }
            return ByteArray(4) { i ->
                val v = parts[i].toIntOrNull() ?: throw IllegalArgumentException("Invalid IPv4 octet in '$text'")
                require(v in 0..255) { "IPv4 octet out of range in '$text'" }
                v.toByte()
            }
        }

        fun formatIpv4(bytes: ByteArray, off: Int = 0): String =
            "${bytes[off].toInt() and 0xff}.${bytes[off + 1].toInt() and 0xff}.${bytes[off + 2].toInt() and 0xff}.${bytes[off + 3].toInt() and 0xff}"

        fun ipv4ToInt(bytes: ByteArray, off: Int = 0): Int =
            ((bytes[off].toInt() and 0xff) shl 24) or
                ((bytes[off + 1].toInt() and 0xff) shl 16) or
                ((bytes[off + 2].toInt() and 0xff) shl 8) or
                (bytes[off + 3].toInt() and 0xff)

        fun intToIpv4(v: Int): ByteArray = byteArrayOf(
            ((v ushr 24) and 0xff).toByte(),
            ((v ushr 16) and 0xff).toByte(),
            ((v ushr 8) and 0xff).toByte(),
            (v and 0xff).toByte(),
        )

        internal fun u16Be(buf: ByteArray, off: Int): Int =
            ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

        internal fun u32Be(buf: ByteArray, off: Int): Long =
            ((buf[off].toLong() and 0xffL) shl 24) or
                ((buf[off + 1].toLong() and 0xffL) shl 16) or
                ((buf[off + 2].toLong() and 0xffL) shl 8) or
                (buf[off + 3].toLong() and 0xffL)

        internal fun putU16Be(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v ushr 8) and 0xff).toByte()
            buf[off + 1] = (v and 0xff).toByte()
        }

        internal fun putU32Be(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v ushr 24) and 0xff).toByte()
            buf[off + 1] = ((v ushr 16) and 0xff).toByte()
            buf[off + 2] = ((v ushr 8) and 0xff).toByte()
            buf[off + 3] = (v and 0xff).toByte()
        }

        internal fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
            var sum = 0
            var i = 0
            while (i + 1 < len) {
                sum += u16Be(buf, off + i)
                i += 2
            }
            if (i < len) {
                sum += (buf[off + i].toInt() and 0xff) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xffff) + (sum ushr 16)
            }
            return sum.inv() and 0xffff
        }

        internal fun l4Checksum(srcIp: ByteArray, dstIp: ByteArray, proto: Int, l4: ByteArray, l4Off: Int = 0, l4Len: Int = l4.size - l4Off): Int {
            var sum = 0
            sum += u16Be(srcIp, 0)
            sum += u16Be(srcIp, 2)
            sum += u16Be(dstIp, 0)
            sum += u16Be(dstIp, 2)
            sum += proto and 0xff
            sum += l4Len and 0xffff
            var i = 0
            while (i + 1 < l4Len) {
                sum += u16Be(l4, l4Off + i)
                i += 2
            }
            if (i < l4Len) {
                sum += (l4[l4Off + i].toInt() and 0xff) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xffff) + (sum ushr 16)
            }
            val res = sum.inv() and 0xffff
            return if (proto == IP_PROTO_UDP && res == 0) 0xffff else res
        }
    }

    internal data class SubnetSpec(
        val networkInt: Int,
        val maskInt: Int,
        val prefixLen: Int,
        val gatewayBytes: ByteArray,
        val guestBytes: ByteArray,
        val broadcastBytes: ByteArray,
        val maskBytes: ByteArray,
    ) {
        val gatewayStr: String = formatIpv4(gatewayBytes)
        val guestStr: String = formatIpv4(guestBytes)

        fun contains(ipBytes: ByteArray, off: Int = 0): Boolean =
            (ipv4ToInt(ipBytes, off) and maskInt) == networkInt

        companion object {
            fun fromConfig(cfg: WifiNetworkConfig): SubnetSpec {
                val cidr = if (cfg.mode == WifiNetworkMode.HOST_ONLY) cfg.hostOnlySubnetCidr else cfg.subnetCidr
                val slash = cidr.indexOf('/')
                val baseStr = if (slash > 0) cidr.substring(0, slash).trim() else cidr.trim()
                val prefix = if (slash > 0) (cidr.substring(slash + 1).trim().toIntOrNull() ?: 24).coerceIn(8, 30) else 24
                val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
                val netInt = ipv4ToInt(parseIpv4Bytes(baseStr)) and mask
                val bcastInt = netInt or mask.inv()

                val defaultGwInt = netInt or 1
                val defaultGuestInt = netInt or 100

                val rawGw = runCatching { parseIpv4Bytes(cfg.gatewayIp) }.getOrNull()
                val gwInt = if (rawGw != null && (ipv4ToInt(rawGw) and mask) == netInt) {
                    ipv4ToInt(rawGw)
                } else {
                    defaultGwInt
                }

                val rawGuest = runCatching { parseIpv4Bytes(cfg.guestIp) }.getOrNull()
                val guestInt = if (rawGuest != null && (ipv4ToInt(rawGuest) and mask) == netInt && ipv4ToInt(rawGuest) != gwInt) {
                    ipv4ToInt(rawGuest)
                } else {
                    defaultGuestInt
                }

                return SubnetSpec(
                    networkInt = netInt,
                    maskInt = mask,
                    prefixLen = prefix,
                    gatewayBytes = intToIpv4(gwInt),
                    guestBytes = intToIpv4(guestInt),
                    broadcastBytes = intToIpv4(bcastInt),
                    maskBytes = intToIpv4(mask),
                )
            }
        }
    }

    private data class FlowKey(
        val guestIp: Int,
        val guestPort: Int,
        val dstIp: Int,
        val dstPort: Int,
    )

    private data class UdpFlowKey(
        val guestIp: Int,
        val guestPort: Int,
    )

    private inner class TcpNatSession(
        val key: FlowKey,
        val guestMac: ByteArray,
        val guestIpBytes: ByteArray,
        val dstIpBytes: ByteArray,
        initialGuestSeq: Long,
        val inbound: Boolean = false,
    ) : AutoCloseable {
        val lock = Any()
        var rcvNxt: Long = (initialGuestSeq + 1L) and 0xffff_ffffL
        var sndNxt: Long = (0x464A_0000L + (ipIdCounter.incrementAndGet().toLong() * 65537L)) and 0xffff_ffffL
        var synAckSent: Boolean = false
        var synAckReceived: Boolean = false
        var finSent: Boolean = false
        var guestFinReceived: Boolean = false
        val establishedLatch = CountDownLatch(1)
        @Volatile
        var socket: Socket? = null
        val closed = AtomicBoolean(false)
        @Volatile
        var lastActiveNanos: Long = System.nanoTime()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                establishedLatch.countDown()
                runCatching { socket?.close() }
                tcpSessions.remove(key, this)
            }
        }
    }

    private inner class InboundPortForwarder(
        val rule: WifiPortForwardRule,
    ) : AutoCloseable {
        val closed = AtomicBoolean(false)
        private var tcpServer: ServerSocket? = null
        private var udpServer: DatagramSocket? = null
        val boundHostPort: Int
        val activeSpec: String

        init {
            val bindAddr = InetAddress.getByName(rule.bindHost)
            if (rule.protocol == "tcp") {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddr, rule.hostPort), 32)
                }
                tcpServer = ss
                boundHostPort = ss.localPort
                activeSpec = rule.copy(hostPort = boundHostPort).toSpecString()
                startTcpAcceptLoop(ss)
            } else {
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1000
                    bind(InetSocketAddress(bindAddr, rule.hostPort))
                }
                udpServer = ds
                boundHostPort = ds.localPort
                activeSpec = rule.copy(hostPort = boundHostPort).toSpecString()
                startUdpForwardLoop(ds)
            }
        }

        private fun startTcpAcceptLoop(ss: ServerSocket) {
            Thread.ofVirtual().name("fuji-wifi-fwd-tcp-$boundHostPort->${rule.guestPort}").start {
                while (!closed.get() && !ss.isClosed) {
                    val client = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    if (closed.get() || !config.enabled) {
                        runCatching { client.close() }
                        continue
                    }
                    handleAcceptedInboundTcp(client, rule.guestPort)
                }
            }
        }

        private fun startUdpForwardLoop(ds: DatagramSocket) {
            Thread.ofVirtual().name("fuji-wifi-fwd-udp-$boundHostPort->${rule.guestPort}").start {
                val buf = ByteArray(65507)
                while (!closed.get() && !ds.isClosed) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        ds.receive(pkt)
                        if (!config.enabled) continue
                        val remote = InetSocketAddress(pkt.address, pkt.port)
                        val vPort = inboundUdpVirtualPorts.computeIfAbsent(remote) {
                            val allocated = 50000 + (inboundPortSeed.incrementAndGet() and 0x2fff)
                            inboundUdpReverse[allocated] = ds to remote
                            allocated
                        }
                        val sub = subnet
                        val guestIp = observedGuestIp.takeIf { it.isNotEmpty() }?.let { runCatching { parseIpv4Bytes(it) }.getOrNull() }
                            ?: sub.guestBytes
                        val gMac = observedGuestMac.takeIf { it.size == 6 } ?: guestMacProvider()
                        sendUdpFrame(
                            dstMac = gMac,
                            srcIp = sub.gatewayBytes,
                            srcPort = vPort,
                            dstIp = guestIp,
                            dstPort = rule.guestPort,
                            payload = buf.copyOfRange(0, pkt.length),
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal poll
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { tcpServer?.close() }
                runCatching { udpServer?.close() }
            }
        }
    }

    private inner class UdpNatSession(
        val key: UdpFlowKey,
        val guestMac: ByteArray,
        val guestIpBytes: ByteArray,
        val socket: DatagramSocket,
    ) : AutoCloseable {
        val closed = AtomicBoolean(false)
        // Maps host remote SocketAddress -> original guest destination (dstIpBytes, dstPort)
        val reverseMap = ConcurrentHashMap<InetSocketAddress, Pair<ByteArray, Int>>()
        @Volatile
        var lastActiveNanos: Long = System.nanoTime()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { socket.close() }
                udpSessions.remove(key, this)
            }
        }
    }

    private val configLock = Any()
    @Volatile
    var config: WifiNetworkConfig = initialConfig
        private set
    @Volatile
    private var subnet: SubnetSpec = SubnetSpec.fromConfig(initialConfig)
    @Volatile
    private var bridgeTransport: BridgeTransport? = null
    @Volatile
    private var observedGuestIp: String = ""
    @Volatile
    private var observedGuestMac: ByteArray = ByteArray(0)
    @Volatile
    private var lastError: String? = null

    private val closed = AtomicBoolean(false)
    private val ipIdCounter = AtomicInteger(1)
    private val inboundPortSeed = AtomicInteger(0)
    private val txPackets = AtomicLong(0L)
    private val rxPackets = AtomicLong(0L)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    val blockedExternalPackets = AtomicLong(0L)

    private val tcpSessions = ConcurrentHashMap<FlowKey, TcpNatSession>()
    private val udpSessions = ConcurrentHashMap<UdpFlowKey, UdpNatSession>()
    private val portForwarders = CopyOnWriteArrayList<InboundPortForwarder>()
    private val inboundUdpVirtualPorts = ConcurrentHashMap<InetSocketAddress, Int>()
    private val inboundUdpReverse = ConcurrentHashMap<Int, Pair<DatagramSocket, InetSocketAddress>>()

    init {
        applyConfigInternal(initialConfig)
    }

    fun reconfigure(nextConfig: WifiNetworkConfig) {
        check(!closed.get()) { "WifiNetworkEngine closed" }
        synchronized(configLock) {
            applyConfigInternal(nextConfig)
        }
    }

    private fun applyConfigInternal(nextConfig: WifiNetworkConfig) {
        tearDownActiveSessions()
        bridgeTransport?.let { runCatching { it.close() } }
        bridgeTransport = null
        lastError = null
        config = nextConfig
        subnet = SubnetSpec.fromConfig(nextConfig)
        observedGuestIp = ""

        if (nextConfig.enabled && nextConfig.mode == WifiNetworkMode.BRIDGE) {
            val transport = BridgeTransportFactory.createBridgeTransport(
                config = nextConfig,
                guestMacProvider = guestMacProvider,
                rxFrameSink = { rawFrame -> onBridgeEthernetRx(rawFrame) },
                osName = osNameProvider(),
                hostInterfaces = hostInterfacesProvider(),
                customTransportFactory = customBridgeFactory,
            )
            bridgeTransport = transport
            lastError = transport.lastError
        }

        // Bind configured host-to-guest port forwards (e.g. 2222:22, 3389:3389) in NAT / HOST_ONLY / NIC_BOUND_FALLBACK
        if (nextConfig.enabled && nextConfig.portForwards.isNotEmpty() && bridgeTransport?.isRawL2 != true) {
            for (rule in WifiPortForwardRule.parseList(nextConfig.portForwards)) {
                try {
                    portForwarders += InboundPortForwarder(rule)
                } catch (e: Exception) {
                    lastError = "Port forward ${rule.toSpecString()} failed: ${e.message}"
                }
            }
        }
    }

    fun status(macAddress: String): WifiNetworkStatus {
        gcIdleSessions()
        val cfg = config
        val sub = subnet
        val bt = bridgeTransport
        val effectiveGuestIp = when {
            !cfg.enabled -> ""
            observedGuestIp.isNotEmpty() -> observedGuestIp
            bt != null && bt.isRawL2 -> ""
            else -> sub.guestStr
        }
        val effectiveGatewayIp = when {
            !cfg.enabled -> ""
            bt != null && bt.isRawL2 -> ""
            else -> sub.gatewayStr
        }
        val bridgeIface = when {
            cfg.mode != WifiNetworkMode.BRIDGE -> cfg.bridgeInterface
            bt != null && bt.interfaceName.isNotBlank() -> bt.interfaceName
            else -> cfg.bridgeInterface
        }
        val bridgeBackend = when {
            !cfg.enabled -> ""
            cfg.mode == WifiNetworkMode.BRIDGE -> bt?.backendId ?: "NONE"
            else -> ""
        }
        val bridgeState = when {
            !cfg.enabled -> "DISABLED"
            cfg.mode == WifiNetworkMode.BRIDGE -> bt?.stateDescription ?: "DISCONNECTED"
            cfg.mode == WifiNetworkMode.NAT -> "NAT_ACTIVE"
            cfg.mode == WifiNetworkMode.HOST_ONLY -> "HOST_ONLY_ISOLATED"
            else -> "DISABLED"
        }
        val effectiveDns = when {
            !cfg.enabled -> emptyList()
            cfg.mode == WifiNetworkMode.HOST_ONLY -> listOf(sub.gatewayStr)
            else -> cfg.dnsServers
        }
        val activeForwards = if (portForwarders.isNotEmpty()) {
            portForwarders.map { it.activeSpec }
        } else {
            cfg.portForwards
        }
        return WifiNetworkStatus(
            mode = cfg.mode,
            linkUp = cfg.enabled && linkUpProvider() && (cfg.mode != WifiNetworkMode.BRIDGE || bt?.isOperational == true),
            associatedSsid = if (cfg.enabled) associatedSsidProvider() else "",
            guestIp = effectiveGuestIp,
            gatewayIp = effectiveGatewayIp,
            macAddress = macAddress,
            bridgeInterface = bridgeIface,
            bridgeState = bridgeState,
            activeTcpSessions = tcpSessions.size,
            activeUdpSessions = udpSessions.size,
            txPackets = txPackets.get(),
            rxPackets = rxPackets.get(),
            txBytes = txBytes.get(),
            rxBytes = rxBytes.get(),
            lastError = lastError ?: bt?.lastError,
            enabled = cfg.enabled,
            bridgeBackend = bridgeBackend,
            dnsServers = effectiveDns,
            portForwards = activeForwards,
        )
    }

    /** Entry point for every Ethernet II frame transmitted by the guest over SDIO F2 BDC. */
    fun onGuestEthernetTx(frame: ByteArray) {
        if (closed.get() || frame.size < 14) return
        val cfg = config
        if (!cfg.enabled) return

        txPackets.incrementAndGet()
        txBytes.addAndGet(frame.size.toLong())
        observedGuestMac = frame.copyOfRange(6, 12)

        val bt = bridgeTransport
        if (cfg.mode == WifiNetworkMode.BRIDGE) {
            if (bt == null || !bt.isOperational) {
                return
            }
            if (bt.isRawL2) {
                sniffGuestIpFromFrame(frame)
                if (!bt.sendEthernetFrame(frame)) {
                    lastError = bt.lastError ?: "Bridge L2 transmit failed"
                }
                return
            }
            // Otherwise bt is HostNicBoundBridgeTransport -> fall through to user-space stack bound to bt.boundLocalIpv4!
        }

        val etherType = u16Be(frame, 12)
        when (etherType) {
            ETHER_TYPE_ARP -> handleArp(frame, subnet)
            ETHER_TYPE_IPV4 -> handleIpv4(frame, cfg, subnet, bt?.boundLocalIpv4)
        }
    }

    private fun onBridgeEthernetRx(frame: ByteArray) {
        if (closed.get() || !config.enabled || frame.size < 14) return
        // Learn guest IP if a DHCP ACK arrives from external LAN router
        deliverToGuest(frame)
    }

    private fun deliverToGuest(frame: ByteArray) {
        if (closed.get() || !config.enabled) return
        rxPackets.incrementAndGet()
        rxBytes.addAndGet(frame.size.toLong())
        rxEthernetSink(frame)
    }

    private fun sniffGuestIpFromFrame(frame: ByteArray) {
        val etherType = u16Be(frame, 12)
        if (etherType == ETHER_TYPE_ARP && frame.size >= 42) {
            val ip = formatIpv4(frame, 28)
            if (ip != "0.0.0.0") observedGuestIp = ip
        } else if (etherType == ETHER_TYPE_IPV4 && frame.size >= 34) {
            val ip = formatIpv4(frame, 26)
            if (ip != "0.0.0.0") observedGuestIp = ip
        }
    }

    // ================= L2 ARP =================

    private fun handleArp(frame: ByteArray, sub: SubnetSpec) {
        if (frame.size < 42) return
        val op = u16Be(frame, 20)
        val senderIp = formatIpv4(frame, 28)
        if (senderIp != "0.0.0.0") observedGuestIp = senderIp
        if (op != 1) return // Only ARP Request

        val targetIp = frame.copyOfRange(38, 42)
        val isGatewayTarget = targetIp.contentEquals(sub.gatewayBytes)
        val isLegacyPeerTarget = targetIp.contentEquals(VirtualWifiPeer.PEER_IP)
        // Reply for gateway IP (and in NAT mode, proxy-ARP for external/virtual DNS IPs if guest sends unrouted ARP)
        val shouldReply = isGatewayTarget ||
            (config.mode != WifiNetworkMode.HOST_ONLY && isLegacyPeerTarget)
        if (!shouldReply) return

        val responderMac = if (isLegacyPeerTarget && !isGatewayTarget) VirtualWifiPeer.PEER_MAC else GATEWAY_MAC
        val reply = ByteArray(42)
        System.arraycopy(frame, 6, reply, 0, 6)
        System.arraycopy(responderMac, 0, reply, 6, 6)
        putU16Be(reply, 12, ETHER_TYPE_ARP)
        putU16Be(reply, 14, 1) // HTYPE Ethernet
        putU16Be(reply, 16, ETHER_TYPE_IPV4)
        reply[18] = 6
        reply[19] = 4
        putU16Be(reply, 20, 2) // ARP Reply
        System.arraycopy(responderMac, 0, reply, 22, 6)
        System.arraycopy(targetIp, 0, reply, 28, 4)
        System.arraycopy(frame, 22, reply, 32, 6)
        System.arraycopy(frame, 28, reply, 38, 4)
        deliverToGuest(reply)
    }

    // ================= L3 IPv4 & Strict Host-Only Policy =================

    private fun handleIpv4(
        frame: ByteArray,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
        boundLocalIpv4: Inet4Address?,
    ) {
        if (frame.size < 34) return
        val version = (frame[14].toInt() ushr 4) and 0x0f
        if (version != 4) return
        val ihl = (frame[14].toInt() and 0x0f) * 4
        if (ihl < 20 || 14 + ihl > frame.size) return
        val totalLen = u16Be(frame, 16)
        if (totalLen < ihl) return
        val ipEnd = minOf(frame.size, 14 + totalLen)
        val l4Off = 14 + ihl
        val l4Len = ipEnd - l4Off
        if (l4Len < 0) return

        val proto = frame[23].toInt() and 0xff
        val srcIp = frame.copyOfRange(26, 30)
        val dstIp = frame.copyOfRange(30, 34)
        val guestMac = frame.copyOfRange(6, 12)

        val srcIpStr = formatIpv4(srcIp)
        if (srcIpStr != "0.0.0.0") observedGuestIp = srcIpStr

        // DHCP client requests (UDP 68 -> 67) must always be processed before subnet checks (src=0.0.0.0, dst=255.255.255.255)
        if (proto == IP_PROTO_UDP && l4Len >= 8) {
            val dstPort = u16Be(frame, l4Off + 2)
            if (dstPort == 67) {
                handleDhcpPacket(guestMac, frame, l4Off + 8, l4Len - 8, cfg, sub)
                return
            }
        }

        val isBroadcast = dstIp.contentEquals(BROADCAST_IP) || dstIp.contentEquals(sub.broadcastBytes)
        val isMulticast = (dstIp[0].toInt() and 0xf0) == 0xe0
        if (isBroadcast || isMulticast) return

        // STRICT HOST_ONLY WAN ISOLATION: block all destinations outside the Host-Only subnet with ICMP Unreachable!
        if (cfg.mode == WifiNetworkMode.HOST_ONLY && !sub.contains(dstIp)) {
            blockedExternalPackets.incrementAndGet()
            val replyDstIp = if (srcIp.contentEquals(ZERO_IP)) sub.guestBytes else srcIp
            sendIcmpError(
                guestMac = guestMac,
                srcIp = sub.gatewayBytes,
                dstIp = replyDstIp,
                icmpType = 3, // Destination Unreachable
                icmpCode = 1, // Host Unreachable (strictly blocked by Host-Only policy)
                offendingIpPacket = frame.copyOfRange(14, minOf(ipEnd, 14 + ihl + 8)),
            )
            return
        }

        when (proto) {
            IP_PROTO_ICMP -> handleIcmp(guestMac, srcIp, dstIp, frame, l4Off, l4Len, cfg, sub, boundLocalIpv4)
            IP_PROTO_UDP -> handleUdp(guestMac, srcIp, dstIp, frame, l4Off, l4Len, cfg, sub, boundLocalIpv4)
            IP_PROTO_TCP -> handleTcp(guestMac, srcIp, dstIp, frame, l4Off, l4Len, cfg, sub, boundLocalIpv4)
        }
    }

    // ================= DHCPv4 Server (RFC 2131 / 2132) =================

    private fun handleDhcpPacket(
        guestMac: ByteArray,
        frame: ByteArray,
        bootpOff: Int,
        bootpLen: Int,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
    ) {
        if (bootpLen < 240) return
        val op = frame[bootpOff].toInt() and 0xff
        if (op != 1) return // BOOTREQUEST
        val cookie = u32Be(frame, bootpOff + 236).toInt()
        if (cookie != DHCP_MAGIC_COOKIE) return

        val xid = u32Be(frame, bootpOff + 4).toInt()
        val flags = u16Be(frame, bootpOff + 10)
        val ciaddr = frame.copyOfRange(bootpOff + 12, bootpOff + 16)
        val chaddr = frame.copyOfRange(bootpOff + 28, bootpOff + 44)

        var msgType = 0
        var requestedIp: ByteArray? = null
        var optIdx = bootpOff + 240
        val optEnd = bootpOff + bootpLen
        while (optIdx < optEnd) {
            val code = frame[optIdx].toInt() and 0xff
            if (code == 255) break
            if (code == 0) {
                optIdx++
                continue
            }
            if (optIdx + 1 >= optEnd) break
            val len = frame[optIdx + 1].toInt() and 0xff
            if (optIdx + 2 + len > optEnd) break
            when (code) {
                53 -> if (len >= 1) msgType = frame[optIdx + 2].toInt() and 0xff
                50 -> if (len == 4) requestedIp = frame.copyOfRange(optIdx + 2, optIdx + 6)
            }
            optIdx += 2 + len
        }

        val replyType = when (msgType) {
            1 -> 2 // DISCOVER -> OFFER
            3 -> { // REQUEST -> ACK (or NAK if wrong subnet)
                val req = requestedIp ?: ciaddr.takeIf { !it.contentEquals(ZERO_IP) }
                if (req != null && !sub.contains(req)) 6 else 5
            }
            8 -> 5 // INFORM -> ACK
            else -> return
        }

        val assignedIp = if (replyType == 6) ZERO_IP else sub.guestBytes
        if (replyType == 5) {
            observedGuestIp = sub.guestStr
        }

        val bootpOut = ByteArrayOutputStream(320)
        val fixed = ByteArray(240)
        fixed[0] = 2 // BOOTREPLY
        fixed[1] = 1 // Ethernet
        fixed[2] = 6 // HLEN
        fixed[3] = 0
        putU32Be(fixed, 4, xid)
        putU16Be(fixed, 8, 0)
        putU16Be(fixed, 10, flags)
        System.arraycopy(ciaddr, 0, fixed, 12, 4)
        if (msgType != 8) {
            System.arraycopy(assignedIp, 0, fixed, 16, 4) // yiaddr
        }
        System.arraycopy(sub.gatewayBytes, 0, fixed, 20, 4) // siaddr
        System.arraycopy(chaddr, 0, fixed, 28, 16)
        putU32Be(fixed, 236, DHCP_MAGIC_COOKIE)
        bootpOut.write(fixed)

        fun writeOpt(code: Int, data: ByteArray) {
            bootpOut.write(code and 0xff)
            bootpOut.write(data.size and 0xff)
            bootpOut.write(data)
        }
        fun writeOptU32(code: Int, value: Int) {
            val b = ByteArray(4)
            putU32Be(b, 0, value)
            writeOpt(code, b)
        }

        writeOpt(53, byteArrayOf(replyType.toByte()))
        writeOpt(54, sub.gatewayBytes)
        if (replyType != 6) {
            if (msgType != 8) {
                writeOptU32(51, 86400) // Lease 24h
                writeOptU32(58, 43200) // T1
                writeOptU32(59, 75600) // T2
            }
            writeOpt(1, sub.maskBytes)
            writeOpt(3, sub.gatewayBytes)
            writeOpt(28, sub.broadcastBytes)

            val dnsBytes = ByteArrayOutputStream()
            dnsBytes.write(sub.gatewayBytes)
            if (cfg.mode != WifiNetworkMode.HOST_ONLY) {
                for (dns in cfg.dnsServers) {
                    val hostOnlyPart = dns.substringBefore(':').trim()
                    if (HostDnsResolver.isValidPublicOrLanIpv4(hostOnlyPart)) {
                        runCatching { parseIpv4Bytes(hostOnlyPart) }.getOrNull()?.let(dnsBytes::write)
                    }
                }
            }
            writeOpt(6, dnsBytes.toByteArray())
            writeOpt(12, "gfx100s".toByteArray(StandardCharsets.US_ASCII))
        }
        bootpOut.write(255) // END
        while (bootpOut.size() < 300) bootpOut.write(0)

        val broadcastReply = ((flags and 0x8000) != 0) || ciaddr.contentEquals(ZERO_IP) || replyType == 6
        val dstMac = if (broadcastReply) BROADCAST_MAC else guestMac
        val dstIp = if (broadcastReply) BROADCAST_IP else assignedIp
        sendUdpFrame(
            dstMac = dstMac,
            srcIp = sub.gatewayBytes,
            srcPort = 67,
            dstIp = dstIp,
            dstPort = 68,
            payload = bootpOut.toByteArray(),
        )
    }

    // ================= ICMPv4 Engine =================

    private fun handleIcmp(
        guestMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        frame: ByteArray,
        l4Off: Int,
        l4Len: Int,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
        boundLocalIpv4: Inet4Address?,
    ) {
        if (l4Len < 8) return
        val type = frame[l4Off].toInt() and 0xff
        val code = frame[l4Off + 1].toInt() and 0xff
        if (type != 8 || code != 0) return // Only Echo Request

        val icmpPayload = frame.copyOfRange(l4Off, l4Off + l4Len)
        val isGatewayOrLocal = dstIp.contentEquals(sub.gatewayBytes) ||
            dstIp.contentEquals(VirtualWifiPeer.PEER_IP) ||
            dstIp.contentEquals(byteArrayOf(127, 0, 0, 1))
        if (isGatewayOrLocal || cfg.mode == WifiNetworkMode.HOST_ONLY) {
            if (isGatewayOrLocal || dstIp.contentEquals(sub.guestBytes)) {
                sendIcmpEchoReply(guestMac, srcIp = dstIp, dstIp = srcIp, requestIcmp = icmpPayload)
            } else {
                sendIcmpError(
                    guestMac = guestMac,
                    srcIp = sub.gatewayBytes,
                    dstIp = srcIp,
                    icmpType = 3,
                    icmpCode = 1,
                    offendingIpPacket = frame.copyOfRange(14, minOf(frame.size, l4Off + 8)),
                )
            }
            return
        }

        // NAT / NIC_BOUND_FALLBACK external ICMP Echo proxy
        val dstCopy = dstIp.copyOf()
        val srcCopy = srcIp.copyOf()
        val macCopy = guestMac.copyOf()
        Thread.ofVirtual().name("fuji-wifi-icmp-ping").start {
            val reachable = runCatching {
                val target = InetAddress.getByAddress(dstCopy)
                val nic = boundLocalIpv4?.let { java.net.NetworkInterface.getByInetAddress(it) }
                if (nic != null) target.isReachable(nic, 64, 1500) else target.isReachable(1500)
            }.getOrDefault(false)
            if (reachable) {
                sendIcmpEchoReply(macCopy, srcIp = dstCopy, dstIp = srcCopy, requestIcmp = icmpPayload)
            } else {
                sendIcmpError(
                    guestMac = macCopy,
                    srcIp = sub.gatewayBytes,
                    dstIp = srcCopy,
                    icmpType = 3,
                    icmpCode = 1,
                    offendingIpPacket = frame.copyOfRange(14, minOf(frame.size, l4Off + 8)),
                )
            }
        }
    }

    private fun sendIcmpEchoReply(guestMac: ByteArray, srcIp: ByteArray, dstIp: ByteArray, requestIcmp: ByteArray) {
        val reply = requestIcmp.copyOf()
        reply[0] = 0 // Echo Reply
        reply[1] = 0
        putU16Be(reply, 2, 0)
        putU16Be(reply, 2, ipChecksum(reply, 0, reply.size))
        val responderMac = if (srcIp.contentEquals(VirtualWifiPeer.PEER_IP)) VirtualWifiPeer.PEER_MAC else GATEWAY_MAC
        sendIpv4Frame(dstMac = guestMac, srcMac = responderMac, srcIp = srcIp, dstIp = dstIp, proto = IP_PROTO_ICMP, l4Bytes = reply)
    }

    private fun sendIcmpError(
        guestMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        icmpType: Int,
        icmpCode: Int,
        offendingIpPacket: ByteArray,
    ) {
        val quotedLen = minOf(offendingIpPacket.size, 28)
        val icmp = ByteArray(8 + quotedLen)
        icmp[0] = (icmpType and 0xff).toByte()
        icmp[1] = (icmpCode and 0xff).toByte()
        putU16Be(icmp, 2, 0)
        putU32Be(icmp, 4, 0)
        System.arraycopy(offendingIpPacket, 0, icmp, 8, quotedLen)
        putU16Be(icmp, 2, ipChecksum(icmp, 0, icmp.size))
        sendIpv4Frame(dstMac = guestMac, srcMac = GATEWAY_MAC, srcIp = srcIp, dstIp = dstIp, proto = IP_PROTO_ICMP, l4Bytes = icmp)
    }

    // ================= DNS Forwarder & Local Resolver (UDP 53) =================

    private data class ParsedDnsQuestion(
        val txId: Int,
        val flags: Int,
        val qname: String,
        val qtype: Int,
        val qclass: Int,
        val questionEndOffset: Int,
    )

    private fun parseDnsQuestion(dns: ByteArray): ParsedDnsQuestion? {
        if (dns.size < 12) return null
        val qdCount = u16Be(dns, 4)
        if (qdCount < 1) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xff
            pos++
            if (len == 0) break
            if ((len and 0xC0) != 0 || pos + len > dns.size) return null
            labels += String(dns, pos, len, StandardCharsets.US_ASCII)
            pos += len
        }
        if (pos + 4 > dns.size) return null
        val qtype = u16Be(dns, pos)
        val qclass = u16Be(dns, pos + 2)
        return ParsedDnsQuestion(
            txId = u16Be(dns, 0),
            flags = u16Be(dns, 2),
            qname = labels.joinToString(".").lowercase(Locale.ROOT),
            qtype = qtype,
            qclass = qclass,
            questionEndOffset = pos + 4,
        )
    }

    private fun buildDnsResponse(query: ByteArray, question: ParsedDnsQuestion, rcode: Int, ipv4Answers: List<ByteArray>): ByteArray {
        val qSlice = query.copyOfRange(12, question.questionEndOffset)
        val out = ByteArrayOutputStream(64 + ipv4Answers.size * 16)
        val hdr = ByteArray(12)
        putU16Be(hdr, 0, question.txId)
        val rd = question.flags and 0x0100
        putU16Be(hdr, 2, 0x8000 or rd or 0x0080 or (rcode and 0x0f))
        putU16Be(hdr, 4, 1) // QDCOUNT
        putU16Be(hdr, 6, ipv4Answers.size) // ANCOUNT
        putU16Be(hdr, 8, 0)
        putU16Be(hdr, 10, 0)
        out.write(hdr)
        out.write(qSlice)
        for (addr in ipv4Answers) {
            if (addr.size == 4) {
                val rr = ByteArray(16)
                putU16Be(rr, 0, 0xC00C) // Name pointer to offset 12
                putU16Be(rr, 2, 1)      // TYPE A
                putU16Be(rr, 4, 1)      // CLASS IN
                putU32Be(rr, 6, 300)    // TTL 300s
                putU16Be(rr, 10, 4)     // RDLENGTH 4
                System.arraycopy(addr, 0, rr, 12, 4)
                out.write(rr)
            } else if (addr.size == 16) {
                val rr = ByteArray(28)
                putU16Be(rr, 0, 0xC00C)
                putU16Be(rr, 2, 28)     // TYPE AAAA
                putU16Be(rr, 4, 1)      // CLASS IN
                putU32Be(rr, 6, 300)
                putU16Be(rr, 10, 16)
                System.arraycopy(addr, 0, rr, 12, 16)
                out.write(rr)
            }
        }
        return out.toByteArray()
    }

    private fun handleDnsUdpQuery(
        guestMac: ByteArray,
        guestIp: ByteArray,
        guestPort: Int,
        dnsServerDstIp: ByteArray,
        dnsPayload: ByteArray,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
        boundLocalIpv4: Inet4Address?,
    ) {
        val parsed = parseDnsQuestion(dnsPayload)
        if (parsed != null) {
            val localIp = when (parsed.qname) {
                "gateway", "gateway.local", "host.internal", "fujiemu.host", "localhost" -> sub.gatewayBytes
                "gfx100s", "gfx100s.local", "guest.local" -> sub.guestBytes
                else -> null
            }
            if (localIp != null) {
                val answers = if (parsed.qtype == 1 || parsed.qtype == 255) listOf(localIp) else emptyList()
                val resp = buildDnsResponse(dnsPayload, parsed, rcode = 0, ipv4Answers = answers)
                sendUdpFrame(guestMac, dnsServerDstIp, 53, guestIp, guestPort, resp)
                return
            }
            if (cfg.mode == WifiNetworkMode.HOST_ONLY) {
                // Strictly refuse external domain resolution in HOST_ONLY mode without opening any socket!
                val refused = buildDnsResponse(dnsPayload, parsed, rcode = 5, ipv4Answers = emptyList())
                sendUdpFrame(guestMac, dnsServerDstIp, 53, guestIp, guestPort, refused)
                return
            }
        } else if (cfg.mode == WifiNetworkMode.HOST_ONLY) {
            return
        }

        val macCopy = guestMac.copyOf()
        val gIpCopy = guestIp.copyOf()
        val sIpCopy = dnsServerDstIp.copyOf()
        val queryCopy = dnsPayload.copyOf()
        Thread.ofVirtual().name("fuji-wifi-dns-fwd").start {
            val upstreams = buildList<InetSocketAddress> {
                if (!sIpCopy.contentEquals(sub.gatewayBytes)) {
                    runCatching { add(InetSocketAddress(InetAddress.getByAddress(sIpCopy), 53)) }
                }
                for (entry in cfg.dnsServers) {
                    val trimmed = entry.trim()
                    if (':' in trimmed) {
                        val host = trimmed.substringBefore(':').trim()
                        val port = trimmed.substringAfter(':').trim().toIntOrNull() ?: 53
                        runCatching { add(InetSocketAddress(InetAddress.getByName(host), port)) }
                    } else if (HostDnsResolver.isValidPublicOrLanIpv4(trimmed)) {
                        runCatching { add(InetSocketAddress(InetAddress.getByName(trimmed), 53)) }
                    }
                }
                for (sysDns in HostDnsResolver.resolveEffectiveDnsServers(emptyList(), osNameProvider())) {
                    runCatching { add(InetSocketAddress(InetAddress.getByName(sysDns), 53)) }
                }
            }.distinct()

            var wireReply: ByteArray? = null
            for (upstream in upstreams.take(4)) {
                wireReply = tryForwardDnsDatagram(queryCopy, upstream, boundLocalIpv4)
                if (wireReply != null) break
            }

            if (wireReply == null && parsed != null) {
                // Resilient JVM resolver fallback (works seamlessly behind Windows VPNs, corporate proxies, and proxychains!)
                wireReply = trySynthesizeDnsViaSystemResolver(queryCopy, parsed)
            }

            if (wireReply != null) {
                sendUdpFrame(macCopy, sIpCopy, 53, gIpCopy, guestPort, wireReply)
            }
        }
    }

    private fun tryForwardDnsDatagram(
        query: ByteArray,
        upstream: InetSocketAddress,
        boundLocalIpv4: Inet4Address?,
    ): ByteArray? = runCatching {
        val bindAddr = if (boundLocalIpv4 != null && !upstream.address.isLoopbackAddress) {
            InetSocketAddress(boundLocalIpv4, 0)
        } else {
            InetSocketAddress(0)
        }
        DatagramSocket(bindAddr).use { sock ->
            sock.soTimeout = 900
            sock.send(DatagramPacket(query, query.size, upstream))
            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            sock.receive(resp)
            if (resp.length >= 12) buf.copyOfRange(0, resp.length) else null
        }
    }.getOrNull()

    private fun trySynthesizeDnsViaSystemResolver(query: ByteArray, parsed: ParsedDnsQuestion): ByteArray = try {
        val resolved = InetAddress.getAllByName(parsed.qname)
        val records = when (parsed.qtype) {
            1, 255 -> resolved.filterIsInstance<Inet4Address>().map { it.address }
            28 -> resolved.filter { it.address.size == 16 }.map { it.address }
            else -> emptyList()
        }
        buildDnsResponse(query, parsed, rcode = 0, ipv4Answers = records)
    } catch (_: Exception) {
        buildDnsResponse(query, parsed, rcode = 3, ipv4Answers = emptyList()) // NXDOMAIN
    }

    // ================= UDP NAT & Host-Only Loopback Relay =================

    private fun handleUdp(
        guestMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        frame: ByteArray,
        l4Off: Int,
        l4Len: Int,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
        boundLocalIpv4: Inet4Address?,
    ) {
        if (l4Len < 8) return
        val srcPort = u16Be(frame, l4Off)
        val dstPort = u16Be(frame, l4Off + 2)
        val udpLen = u16Be(frame, l4Off + 4)
        if (udpLen < 8 || udpLen > l4Len) return
        val payload = frame.copyOfRange(l4Off + 8, l4Off + udpLen)

        if (dstPort == 53) {
            handleDnsUdpQuery(guestMac, srcIp, srcPort, dstIp, payload, cfg, sub, boundLocalIpv4)
            return
        }

        // Reply path for inbound UDP port-forwards (guest -> virtual gateway port -> external host sender)
        if (dstIp.contentEquals(sub.gatewayBytes)) {
            inboundUdpReverse[dstPort]?.let { (fwdSock, remoteClient) ->
                runCatching { fwdSock.send(DatagramPacket(payload, payload.size, remoteClient)) }
                return
            }
        }

        if (cfg.mode == WifiNetworkMode.HOST_ONLY) {
            if (!dstIp.contentEquals(sub.gatewayBytes) || !cfg.hostOnlyLoopbackForward) {
                sendIcmpError(
                    guestMac = guestMac,
                    srcIp = sub.gatewayBytes,
                    dstIp = srcIp,
                    icmpType = 3,
                    icmpCode = 3, // Port Unreachable
                    offendingIpPacket = frame.copyOfRange(14, minOf(frame.size, l4Off + 8)),
                )
                return
            }
        }

        val targetHostAddr: InetAddress = if (dstIp.contentEquals(sub.gatewayBytes)) {
            InetAddress.getLoopbackAddress()
        } else {
            InetAddress.getByAddress(dstIp)
        }
        val targetSockAddr = InetSocketAddress(targetHostAddr, dstPort)
        val key = UdpFlowKey(ipv4ToInt(srcIp), srcPort)

        val session = udpSessions.computeIfAbsent(key) {
            val bindSockAddr = if (cfg.mode == WifiNetworkMode.HOST_ONLY || targetHostAddr.isLoopbackAddress) {
                InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
            } else if (boundLocalIpv4 != null) {
                InetSocketAddress(boundLocalIpv4, 0)
            } else {
                InetSocketAddress(0)
            }
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1000
                bind(bindSockAddr)
            }
            val created = UdpNatSession(key, guestMac.copyOf(), srcIp.copyOf(), sock)
            startUdpReader(created)
            created
        }

        session.lastActiveNanos = System.nanoTime()
        session.reverseMap[targetSockAddr] = dstIp.copyOf() to dstPort
        try {
            session.socket.send(DatagramPacket(payload, payload.size, targetSockAddr))
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    private fun startUdpReader(session: UdpNatSession) {
        Thread.ofVirtual().name("fuji-wifi-udp-nat-${session.key.guestPort}").start {
            val buf = ByteArray(65507)
            try {
                while (!closed.get() && !session.closed.get() && !session.socket.isClosed) {
                    if (System.nanoTime() - session.lastActiveNanos > SESSION_IDLE_TIMEOUT_NS) break
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        session.socket.receive(pkt)
                        session.lastActiveNanos = System.nanoTime()
                        val remote = InetSocketAddress(pkt.address, pkt.port)
                        val (origIp, origPort) = session.reverseMap[remote]
                            ?: ((pkt.address.address.takeIf { it.size == 4 } ?: subnet.gatewayBytes) to pkt.port)
                        val data = buf.copyOfRange(0, pkt.length)
                        sendUdpFrame(
                            dstMac = session.guestMac,
                            srcIp = origIp,
                            srcPort = origPort,
                            dstIp = session.guestIpBytes,
                            dstPort = session.key.guestPort,
                            payload = data,
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                        // Poll idle timeout
                    }
                }
            } catch (_: Exception) {
                // Socket closed
            } finally {
                session.close()
            }
        }
    }

    // ================= Full User-Space TCP NAT & Host-Only Loopback Engine =================

    private fun handleTcp(
        guestMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        frame: ByteArray,
        l4Off: Int,
        l4Len: Int,
        cfg: WifiNetworkConfig,
        sub: SubnetSpec,
        boundLocalIpv4: Inet4Address?,
    ) {
        if (l4Len < 20) return
        val srcPort = u16Be(frame, l4Off)
        val dstPort = u16Be(frame, l4Off + 2)
        val seq = u32Be(frame, l4Off + 4)
        val dataOff = ((frame[l4Off + 12].toInt() ushr 4) and 0x0f) * 4
        if (dataOff < 20 || dataOff > l4Len) return
        val flags = frame[l4Off + 13].toInt() and 0x3f
        val payload = if (l4Len > dataOff) frame.copyOfRange(l4Off + dataOff, l4Off + l4Len) else ByteArray(0)

        val key = FlowKey(ipv4ToInt(srcIp), srcPort, ipv4ToInt(dstIp), dstPort)

        if ((flags and TCP_RST) != 0) {
            tcpSessions.remove(key)?.close()
            return
        }

        if ((flags and TCP_SYN) != 0 && (flags and TCP_ACK) == 0) {
            if (cfg.mode == WifiNetworkMode.HOST_ONLY) {
                if (!dstIp.contentEquals(sub.gatewayBytes) || !cfg.hostOnlyLoopbackForward) {
                    sendTcpSegment(
                        dstMac = guestMac,
                        srcIp = dstIp,
                        srcPort = dstPort,
                        dstIp = srcIp,
                        dstPort = srcPort,
                        seq = 0L,
                        ack = (seq + 1L) and 0xffff_ffffL,
                        flags = TCP_RST or TCP_ACK,
                    )
                    return
                }
            }

            // Replace any stale half-open flow with the same 4-tuple
            tcpSessions.remove(key)?.close()
            val session = TcpNatSession(key, guestMac.copyOf(), srcIp.copyOf(), dstIp.copyOf(), seq)
            tcpSessions[key] = session

            val targetHostAddr: InetAddress = if (dstIp.contentEquals(sub.gatewayBytes)) {
                InetAddress.getLoopbackAddress()
            } else {
                InetAddress.getByAddress(dstIp)
            }
            val bindLocal = if (cfg.mode != WifiNetworkMode.HOST_ONLY && boundLocalIpv4 != null && !targetHostAddr.isLoopbackAddress) {
                boundLocalIpv4
            } else {
                null
            }
            startTcpConnectorAndReader(session, InetSocketAddress(targetHostAddr, dstPort), bindLocal)
            return
        }

        val session = tcpSessions[key]
        if (session == null) {
            if ((flags and TCP_FIN) != 0) {
                sendTcpSegment(
                    dstMac = guestMac,
                    srcIp = dstIp,
                    srcPort = dstPort,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    seq = u32Be(frame, l4Off + 8),
                    ack = (seq + 1L) and 0xffff_ffffL,
                    flags = TCP_ACK,
                )
            } else {
                sendTcpSegment(
                    dstMac = guestMac,
                    srcIp = dstIp,
                    srcPort = dstPort,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    seq = u32Be(frame, l4Off + 8),
                    ack = (seq + payload.size + (if ((flags and TCP_SYN) != 0) 1 else 0)) and 0xffff_ffffL,
                    flags = TCP_RST or TCP_ACK,
                )
            }
            return
        }

        session.lastActiveNanos = System.nanoTime()

        // Inbound host->guest TCP port-forward: complete 3-way handshake when guest replies with SYN|ACK
        if (session.inbound && !session.synAckReceived && (flags and (TCP_SYN or TCP_ACK)) == (TCP_SYN or TCP_ACK)) {
            val ackSeq: Long
            val ackNum: Long
            synchronized(session.lock) {
                session.rcvNxt = (seq + 1L) and 0xffff_ffffL
                session.synAckReceived = true
                ackSeq = session.sndNxt
                ackNum = session.rcvNxt
            }
            sendTcpSegment(
                dstMac = session.guestMac,
                srcIp = session.dstIpBytes,
                srcPort = session.key.dstPort,
                dstIp = session.guestIpBytes,
                dstPort = session.key.guestPort,
                seq = ackSeq,
                ack = ackNum,
                flags = TCP_ACK,
            )
            session.establishedLatch.countDown()
            if (payload.isEmpty()) return
        }

        var toWrite: ByteArray? = null
        var sendAck = false
        var sendFinAck = false
        var ackNumToSend = 0L
        var seqNumToSend = 0L
        var closeAfter = false

        synchronized(session.lock) {
            if (payload.isNotEmpty()) {
                val delta = ((seq - session.rcvNxt + 0x1_0000_0000L) and 0xffff_ffffL)
                if (delta == 0L) {
                    toWrite = payload
                    session.rcvNxt = (session.rcvNxt + payload.size) and 0xffff_ffffL
                    sendAck = true
                } else if (delta > 0x8000_0000L) {
                    // Partial or full retransmission: trim already-received prefix
                    val overlap = (0x1_0000_0000L - delta).toInt()
                    if (overlap < payload.size) {
                        val trimmed = payload.copyOfRange(overlap, payload.size)
                        toWrite = trimmed
                        session.rcvNxt = (session.rcvNxt + trimmed.size) and 0xffff_ffffL
                    }
                    sendAck = true
                } else {
                    // Out-of-order future segment: re-ACK current rcvNxt so guest retransmits cleanly
                    sendAck = true
                }
            }
            if ((flags and TCP_FIN) != 0 && !session.guestFinReceived) {
                session.guestFinReceived = true
                session.rcvNxt = (session.rcvNxt + 1L) and 0xffff_ffffL
                if (!session.finSent) {
                    session.finSent = true
                    sendFinAck = true
                    seqNumToSend = session.sndNxt
                    session.sndNxt = (session.sndNxt + 1L) and 0xffff_ffffL
                } else {
                    sendAck = true
                }
                closeAfter = true
            }
            if (sendAck && !sendFinAck) {
                seqNumToSend = session.sndNxt
            }
            ackNumToSend = session.rcvNxt
        }

        val pendingWrite = toWrite
        if (pendingWrite != null) {
            try {
                val out = session.socket?.getOutputStream()
                if (out != null) {
                    out.write(pendingWrite)
                    out.flush()
                }
            } catch (e: Exception) {
                lastError = e.message
                sendTcpSegment(
                    dstMac = session.guestMac,
                    srcIp = session.dstIpBytes,
                    srcPort = session.key.dstPort,
                    dstIp = session.guestIpBytes,
                    dstPort = session.key.guestPort,
                    seq = seqNumToSend,
                    ack = ackNumToSend,
                    flags = TCP_RST or TCP_ACK,
                )
                session.close()
                return
            }
        }

        if (sendFinAck) {
            runCatching { session.socket?.shutdownOutput() }
            sendTcpSegment(
                dstMac = session.guestMac,
                srcIp = session.dstIpBytes,
                srcPort = session.key.dstPort,
                dstIp = session.guestIpBytes,
                dstPort = session.key.guestPort,
                seq = seqNumToSend,
                ack = ackNumToSend,
                flags = TCP_FIN or TCP_ACK,
            )
        } else if (sendAck) {
            sendTcpSegment(
                dstMac = session.guestMac,
                srcIp = session.dstIpBytes,
                srcPort = session.key.dstPort,
                dstIp = session.guestIpBytes,
                dstPort = session.key.guestPort,
                seq = seqNumToSend,
                ack = ackNumToSend,
                flags = TCP_ACK,
            )
        }

        if (closeAfter) {
            session.close()
        }
    }

    private fun startTcpConnectorAndReader(
        session: TcpNatSession,
        target: InetSocketAddress,
        bindLocalIpv4: Inet4Address?,
    ) {
        Thread.ofVirtual().name("fuji-wifi-tcp-nat-${session.key.guestPort}->${target.port}").start {
            val sock = Socket()
            try {
                sock.reuseAddress = true
                sock.tcpNoDelay = true
                if (bindLocalIpv4 != null) {
                    sock.bind(InetSocketAddress(bindLocalIpv4, 0))
                }
                sock.connect(target, 5000)
                if (closed.get() || session.closed.get()) {
                    runCatching { sock.close() }
                    return@start
                }
                session.socket = sock

                val synSeq: Long
                val synAck: Long
                synchronized(session.lock) {
                    synSeq = session.sndNxt
                    synAck = session.rcvNxt
                    session.sndNxt = (session.sndNxt + 1L) and 0xffff_ffffL
                    session.synAckSent = true
                }
                // SYN|ACK with MSS=1460 option
                sendTcpSegment(
                    dstMac = session.guestMac,
                    srcIp = session.dstIpBytes,
                    srcPort = session.key.dstPort,
                    dstIp = session.guestIpBytes,
                    dstPort = session.key.guestPort,
                    seq = synSeq,
                    ack = synAck,
                    flags = TCP_SYN or TCP_ACK,
                    options = byteArrayOf(2, 4, ((MAX_TCP_MSS ushr 8) and 0xff).toByte(), (MAX_TCP_MSS and 0xff).toByte()),
                )

                val input = sock.getInputStream()
                val buf = ByteArray(MAX_TCP_MSS)
                while (!closed.get() && !session.closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    session.lastActiveNanos = System.nanoTime()
                    val chunk = buf.copyOfRange(0, n)
                    val segSeq: Long
                    val segAck: Long
                    synchronized(session.lock) {
                        segSeq = session.sndNxt
                        segAck = session.rcvNxt
                        session.sndNxt = (session.sndNxt + n.toLong()) and 0xffff_ffffL
                    }
                    sendTcpSegment(
                        dstMac = session.guestMac,
                        srcIp = session.dstIpBytes,
                        srcPort = session.key.dstPort,
                        dstIp = session.guestIpBytes,
                        dstPort = session.key.guestPort,
                        seq = segSeq,
                        ack = segAck,
                        flags = TCP_PSH or TCP_ACK,
                        payload = chunk,
                    )
                }

                // Host EOF -> send FIN|ACK if not already sent
                if (!closed.get() && !session.closed.get()) {
                    var doFin = false
                    var finSeq = 0L
                    var finAck = 0L
                    synchronized(session.lock) {
                        if (!session.finSent) {
                            session.finSent = true
                            doFin = true
                            finSeq = session.sndNxt
                            finAck = session.rcvNxt
                            session.sndNxt = (session.sndNxt + 1L) and 0xffff_ffffL
                        }
                    }
                    if (doFin) {
                        sendTcpSegment(
                            dstMac = session.guestMac,
                            srcIp = session.dstIpBytes,
                            srcPort = session.key.dstPort,
                            dstIp = session.guestIpBytes,
                            dstPort = session.key.guestPort,
                            seq = finSeq,
                            ack = finAck,
                            flags = TCP_FIN or TCP_ACK,
                        )
                    }
                }
            } catch (e: Exception) {
                if (!closed.get() && !session.closed.get()) {
                    val rstSeq: Long
                    val rstAck: Long
                    synchronized(session.lock) {
                        rstSeq = session.sndNxt
                        rstAck = session.rcvNxt
                    }
                    sendTcpSegment(
                        dstMac = session.guestMac,
                        srcIp = session.dstIpBytes,
                        srcPort = session.key.dstPort,
                        dstIp = session.guestIpBytes,
                        dstPort = session.key.guestPort,
                        seq = rstSeq,
                        ack = rstAck,
                        flags = TCP_RST or TCP_ACK,
                    )
                }
            } finally {
                session.close()
            }
        }
    }

    // ================= Frame Builders =================

    private fun sendUdpFrame(
        dstMac: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ) {
        val udpLen = 8 + payload.size
        val udp = ByteArray(udpLen)
        putU16Be(udp, 0, srcPort)
        putU16Be(udp, 2, dstPort)
        putU16Be(udp, 4, udpLen)
        putU16Be(udp, 6, 0)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        putU16Be(udp, 6, l4Checksum(srcIp, dstIp, IP_PROTO_UDP, udp))
        sendIpv4Frame(dstMac, GATEWAY_MAC, srcIp, dstIp, IP_PROTO_UDP, udp)
    }

    private fun sendTcpSegment(
        dstMac: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        options: ByteArray = ByteArray(0),
        payload: ByteArray = ByteArray(0),
    ) {
        val paddedOptLen = ((options.size + 3) / 4) * 4
        val hdrLen = 20 + paddedOptLen
        val tcp = ByteArray(hdrLen + payload.size)
        putU16Be(tcp, 0, srcPort)
        putU16Be(tcp, 2, dstPort)
        putU32Be(tcp, 4, seq.toInt())
        putU32Be(tcp, 8, ack.toInt())
        tcp[12] = (((hdrLen / 4) and 0x0f) shl 4).toByte()
        tcp[13] = (flags and 0x3f).toByte()
        putU16Be(tcp, 14, 65535) // Window
        putU16Be(tcp, 16, 0)     // Checksum
        putU16Be(tcp, 18, 0)     // Urgent pointer
        if (options.isNotEmpty()) {
            System.arraycopy(options, 0, tcp, 20, options.size)
        }
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, tcp, hdrLen, payload.size)
        }
        putU16Be(tcp, 16, l4Checksum(srcIp, dstIp, IP_PROTO_TCP, tcp))
        sendIpv4Frame(dstMac, GATEWAY_MAC, srcIp, dstIp, IP_PROTO_TCP, tcp)
    }

    private fun sendIpv4Frame(
        dstMac: ByteArray,
        srcMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        proto: Int,
        l4Bytes: ByteArray,
    ) {
        val ipLen = 20 + l4Bytes.size
        val eth = ByteArray(14 + ipLen)
        System.arraycopy(dstMac, 0, eth, 0, 6)
        System.arraycopy(srcMac, 0, eth, 6, 6)
        putU16Be(eth, 12, ETHER_TYPE_IPV4)
        eth[14] = 0x45
        eth[15] = 0x00
        putU16Be(eth, 16, ipLen)
        putU16Be(eth, 18, ipIdCounter.incrementAndGet() and 0xffff)
        putU16Be(eth, 20, 0x4000) // DF
        eth[22] = 64
        eth[23] = (proto and 0xff).toByte()
        putU16Be(eth, 24, 0)
        System.arraycopy(srcIp, 0, eth, 26, 4)
        System.arraycopy(dstIp, 0, eth, 30, 4)
        putU16Be(eth, 24, ipChecksum(eth, 14, 20))
        System.arraycopy(l4Bytes, 0, eth, 34, l4Bytes.size)
        deliverToGuest(eth)
    }

    private fun gcIdleSessions() {
        val now = System.nanoTime()
        for (s in tcpSessions.values) {
            if (now - s.lastActiveNanos > SESSION_IDLE_TIMEOUT_NS) s.close()
        }
        for (u in udpSessions.values) {
            if (now - u.lastActiveNanos > SESSION_IDLE_TIMEOUT_NS) u.close()
        }
    }

    private fun handleAcceptedInboundTcp(client: Socket, guestPort: Int) {
        val sub = subnet
        val targetGuestIp = observedGuestIp.takeIf { it.isNotEmpty() }
            ?.let { runCatching { parseIpv4Bytes(it) }.getOrNull() }
            ?: sub.guestBytes.copyOf()
        val targetGuestMac = observedGuestMac.takeIf { it.size == 6 }?.copyOf() ?: guestMacProvider()
        val virtualSrcPort = 40000 + (inboundPortSeed.incrementAndGet() and 0x3fff)
        val key = FlowKey(ipv4ToInt(targetGuestIp), guestPort, ipv4ToInt(sub.gatewayBytes), virtualSrcPort)

        tcpSessions.remove(key)?.close()
        val session = TcpNatSession(
            key = key,
            guestMac = targetGuestMac,
            guestIpBytes = targetGuestIp,
            dstIpBytes = sub.gatewayBytes.copyOf(),
            initialGuestSeq = 0L,
            inbound = true,
        )
        runCatching { client.tcpNoDelay = true }
        session.socket = client
        tcpSessions[key] = session

        val synSeq: Long
        synchronized(session.lock) {
            synSeq = session.sndNxt
            session.sndNxt = (session.sndNxt + 1L) and 0xffff_ffffL
        }
        // Inject TCP SYN into guest over SDIO F2 BDC
        sendTcpSegment(
            dstMac = session.guestMac,
            srcIp = session.dstIpBytes,
            srcPort = virtualSrcPort,
            dstIp = session.guestIpBytes,
            dstPort = guestPort,
            seq = synSeq,
            ack = 0L,
            flags = TCP_SYN,
            options = byteArrayOf(2, 4, ((MAX_TCP_MSS ushr 8) and 0xff).toByte(), (MAX_TCP_MSS and 0xff).toByte()),
        )

        Thread.ofVirtual().name("fuji-wifi-tcp-in-$virtualSrcPort->$guestPort").start {
            try {
                if (!session.establishedLatch.await(5000, TimeUnit.MILLISECONDS) || closed.get() || session.closed.get()) {
                    return@start
                }
                val input = client.getInputStream()
                val buf = ByteArray(MAX_TCP_MSS)
                while (!closed.get() && !session.closed.get()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    session.lastActiveNanos = System.nanoTime()
                    val chunk = buf.copyOfRange(0, n)
                    val segSeq: Long
                    val segAck: Long
                    synchronized(session.lock) {
                        segSeq = session.sndNxt
                        segAck = session.rcvNxt
                        session.sndNxt = (session.sndNxt + n.toLong()) and 0xffff_ffffL
                    }
                    sendTcpSegment(
                        dstMac = session.guestMac,
                        srcIp = session.dstIpBytes,
                        srcPort = session.key.dstPort,
                        dstIp = session.guestIpBytes,
                        dstPort = session.key.guestPort,
                        seq = segSeq,
                        ack = segAck,
                        flags = TCP_PSH or TCP_ACK,
                        payload = chunk,
                    )
                }

                if (!closed.get() && !session.closed.get()) {
                    var doFin = false
                    var finSeq = 0L
                    var finAck = 0L
                    synchronized(session.lock) {
                        if (!session.finSent) {
                            session.finSent = true
                            doFin = true
                            finSeq = session.sndNxt
                            finAck = session.rcvNxt
                            session.sndNxt = (session.sndNxt + 1L) and 0xffff_ffffL
                        }
                    }
                    if (doFin) {
                        sendTcpSegment(
                            dstMac = session.guestMac,
                            srcIp = session.dstIpBytes,
                            srcPort = session.key.dstPort,
                            dstIp = session.guestIpBytes,
                            dstPort = session.key.guestPort,
                            seq = finSeq,
                            ack = finAck,
                            flags = TCP_FIN or TCP_ACK,
                        )
                    }
                }
            } catch (_: Exception) {
                // Host client disconnected
            } finally {
                session.close()
            }
        }
    }

    private fun tearDownActiveSessions() {
        portForwarders.forEach { runCatching { it.close() } }
        portForwarders.clear()
        inboundUdpVirtualPorts.clear()
        inboundUdpReverse.clear()
        tcpSessions.values.toList().forEach { it.close() }
        udpSessions.values.toList().forEach { it.close() }
        tcpSessions.clear()
        udpSessions.clear()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            tearDownActiveSessions()
            bridgeTransport?.let { runCatching { it.close() } }
            bridgeTransport = null
        }
    }
}
