package com.github.nyabanban.fuji.emulator.components.bcm43438

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic loopback Ethernet / ARP / IPv4 / ICMP / PTP-IP peer for the emulated BCM43430A1
 * Wi-Fi interface, plus optional opt-in loopback-only host TCP bridge (`127.0.0.1:55740..55742`)
 * strictly disabled by default so `strict` and `discovery` modes open zero host sockets.
 */
internal class VirtualWifiPeer(
    private val peerNameProvider: () -> String,
    private val rxEthernetSink: (ByteArray) -> Unit,
) : AutoCloseable {

    companion object {
        val PEER_MAC = byteArrayOf(0x02, 0x46, 0x4A, 0xFE.toByte(), 0x00, 0x02)
        val PEER_IP = byteArrayOf(192.toByte(), 168.toByte(), 0, 2)
        const val ETHER_TYPE_IPV4 = 0x0800
        const val ETHER_TYPE_ARP = 0x0806
        val PTP_IP_PORTS = setOf(55740, 55741, 55742)

        fun formatIpv4(bytes: ByteArray, offset: Int = 0): String =
            "${bytes[offset].toInt() and 0xff}.${bytes[offset + 1].toInt() and 0xff}.${bytes[offset + 2].toInt() and 0xff}.${bytes[offset + 3].toInt() and 0xff}"

        /** Build a minimal Fujifilm PTP-IP Init Command Request (type 0x0001) carrying [friendlyName]. */
        fun buildPtpIpInitCommandRequest(friendlyName: String): ByteArray {
            val nameUtf16 = friendlyName.toByteArray(Charsets.UTF_16LE)
            val totalLen = 8 + 16 + nameUtf16.size + 2 + 4
            val out = ByteArray(totalLen)
            putU32Le(out, 0, totalLen)
            putU32Le(out, 4, 0x00000001) // PTPIP_INIT_COMMAND_REQUEST
            // Deterministic 16-byte initiator GUID
            for (i in 0 until 16) out[8 + i] = (0x46 + i).toByte()
            System.arraycopy(nameUtf16, 0, out, 24, nameUtf16.size)
            // UTF-16LE NUL terminator already 0
            putU32Le(out, 24 + nameUtf16.size + 2, 0x00010000) // protocol version 1.0
            return out
        }

        private fun putU16Be(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v ushr 8) and 0xff).toByte()
            buf[off + 1] = (v and 0xff).toByte()
        }

        private fun putU32Le(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v ushr 8) and 0xff).toByte()
            buf[off + 2] = ((v ushr 16) and 0xff).toByte()
            buf[off + 3] = ((v ushr 24) and 0xff).toByte()
        }

        private fun u16Be(buf: ByteArray, off: Int): Int =
            ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

        private fun ipChecksum(buf: ByteArray, off: Int, len: Int): Int {
            var sum = 0
            var i = 0
            while (i + 1 < len) {
                sum += u16Be(buf, off + i)
                i += 2
            }
            if (i < len) sum += (buf[off + i].toInt() and 0xff) shl 8
            while ((sum ushr 16) != 0) sum = (sum and 0xffff) + (sum ushr 16)
            return sum.inv() and 0xffff
        }
    }

    var connected: Boolean = false
        private set
    var observedCameraIp: String = ""
        private set
    var ptpIpReady: Boolean = false
        private set
    var txFrameCount: Long = 0L
        private set
    var rxFrameCount: Long = 0L
        private set
    var arpReplyCount: Long = 0L
        private set
    var icmpReplyCount: Long = 0L
        private set
    var ptpIpExchangeCount: Long = 0L
        private set
    var hostBridgeEnabled: Boolean = false
        private set
    var hostBridgeBoundPorts: List<Int> = emptyList()
        private set

    private val bridgeSockets = mutableListOf<ServerSocket>()
    private val closed = AtomicBoolean(false)

    fun setConnected(isConnected: Boolean) {
        connected = isConnected
    }

    fun setHostTcpBridge(enabled: Boolean) {
        if (hostBridgeEnabled == enabled) return
        if (!enabled) {
            closeBridgeSockets()
            hostBridgeEnabled = false
            return
        }
        check(!closed.get()) { "VirtualWifiPeer closed" }
        val bound = mutableListOf<Int>()
        try {
            val loopback = InetAddress.getLoopbackAddress()
            for (port in PTP_IP_PORTS) {
                val ss = ServerSocket(port, 4, loopback)
                ss.soTimeout = 100
                bridgeSockets += ss
                bound += ss.localPort
            }
            hostBridgeBoundPorts = bound.toList()
            hostBridgeEnabled = true
        } catch (e: Exception) {
            closeBridgeSockets()
            hostBridgeEnabled = false
            throw IllegalStateException("Failed to bind loopback PTP-IP bridge ports 55740..55742: ${e.message}", e)
        }
    }

    private fun closeBridgeSockets() {
        for (ss in bridgeSockets) {
            runCatching { ss.close() }
        }
        bridgeSockets.clear()
        hostBridgeBoundPorts = emptyList()
    }

    /** Process an outgoing Ethernet II frame transmitted by the camera over BCM43430 F2 BDC. */
    fun onCameraEthernetTx(frame: ByteArray) {
        if (frame.size < 14) return
        txFrameCount++
        val etherType = u16Be(frame, 12)
        when (etherType) {
            ETHER_TYPE_ARP -> handleArp(frame)
            ETHER_TYPE_IPV4 -> handleIpv4(frame)
        }
    }

    private fun handleArp(frame: ByteArray) {
        if (frame.size < 42) return
        val op = u16Be(frame, 20)
        // Record sender protocol address (camera IP)
        val senderIp = formatIpv4(frame, 28)
        if (senderIp != "0.0.0.0") observedCameraIp = senderIp
        if (op == 1 && connected) { // ARP Request
            val targetIpMatch = (0 until 4).all { frame[38 + it] == PEER_IP[it] }
            if (targetIpMatch) {
                val reply = ByteArray(42)
                // Ethernet header: dst = camera src MAC, src = PEER_MAC, type = ARP
                System.arraycopy(frame, 6, reply, 0, 6)
                System.arraycopy(PEER_MAC, 0, reply, 6, 6)
                putU16Be(reply, 12, ETHER_TYPE_ARP)
                // ARP header
                putU16Be(reply, 14, 1) // Ethernet
                putU16Be(reply, 16, ETHER_TYPE_IPV4)
                reply[18] = 6
                reply[19] = 4
                putU16Be(reply, 20, 2) // ARP Reply
                System.arraycopy(PEER_MAC, 0, reply, 22, 6)
                System.arraycopy(PEER_IP, 0, reply, 28, 4)
                System.arraycopy(frame, 22, reply, 32, 6)
                System.arraycopy(frame, 28, reply, 38, 4)
                arpReplyCount++
                rxFrameCount++
                rxEthernetSink(reply)
            }
        }
    }

    private fun handleIpv4(frame: ByteArray) {
        if (frame.size < 34) return
        val ihl = (frame[14].toInt() and 0x0f) * 4
        if (ihl < 20 || 14 + ihl > frame.size) return
        val proto = frame[23].toInt() and 0xff
        val srcIp = formatIpv4(frame, 26)
        if (srcIp != "0.0.0.0") observedCameraIp = srcIp
        val l4Off = 14 + ihl

        when (proto) {
            1 -> { // ICMP
                if (connected && l4Off + 8 <= frame.size && (frame[l4Off].toInt() and 0xff) == 8) {
                    val reply = frame.copyOf()
                    // Swap Ethernet MACs
                    System.arraycopy(frame, 6, reply, 0, 6)
                    System.arraycopy(PEER_MAC, 0, reply, 6, 6)
                    // Swap IPv4 src/dst
                    System.arraycopy(frame, 30, reply, 26, 4)
                    System.arraycopy(frame, 26, reply, 30, 4)
                    putU16Be(reply, 24, 0)
                    putU16Be(reply, 24, ipChecksum(reply, 14, ihl))
                    // ICMP Echo Reply (type 0)
                    reply[l4Off] = 0
                    putU16Be(reply, l4Off + 2, 0)
                    putU16Be(reply, l4Off + 2, ipChecksum(reply, l4Off, reply.size - l4Off))
                    icmpReplyCount++
                    rxFrameCount++
                    rxEthernetSink(reply)
                }
            }
            6 -> { // TCP
                if (l4Off + 20 <= frame.size) {
                    val srcPort = u16Be(frame, l4Off)
                    val dstPort = u16Be(frame, l4Off + 2)
                    if (srcPort in PTP_IP_PORTS || dstPort in PTP_IP_PORTS) {
                        ptpIpReady = true
                        ptpIpExchangeCount++
                    }
                }
            }
        }
    }

    /** Inject a deterministic ARP probe from VirtualWifiPeer toward the camera SoftAP. */
    fun injectPeerArpProbe(cameraMac: ByteArray, cameraIp: ByteArray = byteArrayOf(192.toByte(), 168.toByte(), 0, 1)) {
        val arp = ByteArray(42)
        System.arraycopy(cameraMac, 0, arp, 0, 6)
        System.arraycopy(PEER_MAC, 0, arp, 6, 6)
        putU16Be(arp, 12, ETHER_TYPE_ARP)
        putU16Be(arp, 14, 1)
        putU16Be(arp, 16, ETHER_TYPE_IPV4)
        arp[18] = 6
        arp[19] = 4
        putU16Be(arp, 20, 1) // ARP Request
        System.arraycopy(PEER_MAC, 0, arp, 22, 6)
        System.arraycopy(PEER_IP, 0, arp, 28, 4)
        System.arraycopy(cameraIp, 0, arp, 38, 4)
        rxFrameCount++
        rxEthernetSink(arp)
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "name" to peerNameProvider(),
        "macAddress" to Bcm43438BtController.formatMac6(PEER_MAC),
        "ipAddress" to formatIpv4(PEER_IP),
        "connected" to connected,
        "observedCameraIp" to observedCameraIp,
        "ptpIpReady" to ptpIpReady,
        "txFrameCount" to txFrameCount,
        "rxFrameCount" to rxFrameCount,
        "arpReplyCount" to arpReplyCount,
        "icmpReplyCount" to icmpReplyCount,
        "ptpIpExchangeCount" to ptpIpExchangeCount,
        "ptpIpBridgeEnabled" to hostBridgeEnabled,
        "ptpIpBridgeBoundPorts" to hostBridgeBoundPorts,
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "connected" to connected,
        "observedCameraIp" to observedCameraIp,
        "ptpIpReady" to ptpIpReady,
        "txFrameCount" to txFrameCount,
        "rxFrameCount" to rxFrameCount,
        "arpReplyCount" to arpReplyCount,
        "icmpReplyCount" to icmpReplyCount,
        "ptpIpExchangeCount" to ptpIpExchangeCount,
    )

    fun restoreSnapshot(data: Map<String, Any?>) {
        connected = data["connected"] as? Boolean ?: false
        observedCameraIp = data["observedCameraIp"] as? String ?: ""
        ptpIpReady = data["ptpIpReady"] as? Boolean ?: false
        txFrameCount = (data["txFrameCount"] as? Number)?.toLong() ?: 0L
        rxFrameCount = (data["rxFrameCount"] as? Number)?.toLong() ?: 0L
        arpReplyCount = (data["arpReplyCount"] as? Number)?.toLong() ?: 0L
        icmpReplyCount = (data["icmpReplyCount"] as? Number)?.toLong() ?: 0L
        ptpIpExchangeCount = (data["ptpIpExchangeCount"] as? Number)?.toLong() ?: 0L
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeBridgeSockets()
        }
    }
}
