package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.*
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class WifiNetworkingModesTest {

    private class FakeSdioBusPort : SdioBusPort {
        var card: SdioCardPort? = null
        @Volatile
        var cardIrq: Boolean = false
        override fun attachCard(card: SdioCardPort) { this.card = card }
        override fun detachCard(card: SdioCardPort) { if (this.card === card) this.card = null }
        override fun setCardInterrupt(asserted: Boolean) { cardIrq = asserted }
    }

    private class FakeUartSerialPort : UartSerialPort {
        override fun attachPeer(peer: UartSerialPeer) {}
        override fun detachPeer(peer: UartSerialPeer) {}
        override fun pushRxBytes(bytes: ByteArray) {}
        override fun rxPendingBytes(): Int = 0
    }

    private class TestContext : ComponentContext {
        val claims = linkedMapOf<String, Any>()
        override val directory: Path = Path.of(".").toAbsolutePath()
        override val clock = EmulationClock { 0L }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                claims[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? =
                claims[name]?.takeIf { type.isInstance(it) }?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String): Path = directory.resolve(relative)
    }

    private fun cmd52Arg(write: Boolean, func: Int, addr: Int, data: Int = 0): Long =
        ((if (write) 1L else 0L) shl 31) or
            ((func.toLong() and 7L) shl 28) or
            ((addr.toLong() and 0x1ffffL) shl 9) or
            (data.toLong() and 0xffL)

    private fun cmd53Arg(write: Boolean, func: Int, blockMode: Boolean, inc: Boolean, addr: Int, count: Int): Long =
        ((if (write) 1L else 0L) shl 31) or
            ((func.toLong() and 7L) shl 28) or
            ((if (blockMode) 1L else 0L) shl 27) or
            ((if (inc) 1L else 0L) shl 26) or
            ((addr.toLong() and 0x1ffffL) shl 9) or
            (count.toLong() and 0x1ffL)

    private fun buildSdpcmCdc(cmd: Int, reqId: Int, isSet: Boolean, payload: ByteArray, seq: Int = 0): ByteArray {
        val cdcLen = 16 + payload.size
        val totalLen = 12 + cdcLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(totalLen.toShort())
        buf.putShort((totalLen.inv() and 0xffff).toShort())
        buf.put(seq.toByte())
        buf.put(0) // Control channel
        buf.put(0)
        buf.put(12)
        buf.putInt(0)
        buf.putInt(cmd)
        buf.putInt(payload.size)
        buf.putInt((reqId shl 16) or (if (isSet) 2 else 0))
        buf.putInt(0)
        buf.put(payload)
        return buf.array()
    }

    private fun wrapSdpcmBdcData(ethFrame: ByteArray, seq: Int = 0): ByteArray {
        val bdcLen = 4 + ethFrame.size
        val totalLen = 12 + bdcLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(totalLen.toShort())
        buf.putShort((totalLen.inv() and 0xffff).toShort())
        buf.put(seq.toByte())
        buf.put(2) // Channel 2 = BDC Data
        buf.put(0)
        buf.put(12)
        buf.putInt(0)
        // BDC 4-byte header
        buf.put(0x20)
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(ethFrame)
        return buf.array()
    }

    private data class DecodedSdpcmFrame(val channel: Int, val payload: ByteArray)

    private fun readNextSdpcmFrame(card: SdioCardPort, timeoutMs: Long = 3000L): DecodedSdpcmFrame? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (card.isCardInterruptAsserted()) {
                val raw = card.executeCommand(
                    cmdIndex = 53,
                    argument = cmd53Arg(write = false, func = 2, blockMode = true, inc = false, addr = 0, count = 4),
                    blockSize = 512,
                    blockCount = 4,
                ).dataOut ?: return null
                val len = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
                val lenInv = (raw[2].toInt() and 0xff) or ((raw[3].toInt() and 0xff) shl 8)
                if (len >= 12 && ((len xor lenInv) and 0xffff) == 0xffff && len <= raw.size) {
                    val ch = raw[5].toInt() and 0x0f
                    val hdr = raw[7].toInt() and 0xff
                    return DecodedSdpcmFrame(ch, raw.copyOfRange(hdr, len))
                }
            }
            Thread.sleep(5)
        }
        return null
    }

    private fun readNextRxEthernet(card: SdioCardPort, timeoutMs: Long = 3000L): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val f = readNextSdpcmFrame(card, 100L)
            if (f != null && f.channel == Bcm43430WifiController.SDPCM_DATA_CHANNEL && f.payload.size > 4) {
                val dataOff = (f.payload[3].toInt() and 0xff) * 4
                if (4 + dataOff < f.payload.size) {
                    return f.payload.copyOfRange(4 + dataOff, f.payload.size)
                }
            }
        }
        return null
    }

    private fun drainPendingFrames(card: SdioCardPort): List<DecodedSdpcmFrame> {
        val out = mutableListOf<DecodedSdpcmFrame>()
        while (card.isCardInterruptAsserted()) {
            val f = readNextSdpcmFrame(card, 50L) ?: break
            out += f
        }
        return out
    }

    private fun sendBdcEthOverSdio(card: SdioCardPort, eth: ByteArray, seq: Int = 1) {
        val wrapped = wrapSdpcmBdcData(eth, seq)
        if (wrapped.size <= 512) {
            val cntField = if (wrapped.size == 512) 0 else wrapped.size
            card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, cntField), wrapped)
        } else {
            val blocks = (wrapped.size + 511) / 512
            val padded = wrapped.copyOf(blocks * 512)
            card.writeTransferData(53, cmd53Arg(true, 2, true, false, 0, blocks), padded)
        }
    }

    private fun buildIpv4UdpEth(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val udp = ByteArray(udpLen)
        WifiNetworkEngine.putU16Be(udp, 0, srcPort)
        WifiNetworkEngine.putU16Be(udp, 2, dstPort)
        WifiNetworkEngine.putU16Be(udp, 4, udpLen)
        System.arraycopy(payload, 0, udp, 8, payload.size)
        WifiNetworkEngine.putU16Be(udp, 6, WifiNetworkEngine.l4Checksum(srcIp, dstIp, WifiNetworkEngine.IP_PROTO_UDP, udp))
        return buildIpv4Eth(srcMac, dstMac, srcIp, dstIp, WifiNetworkEngine.IP_PROTO_UDP, udp)
    }

    private fun buildIpv4TcpEth(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
    ): ByteArray {
        val tcp = ByteArray(20 + payload.size)
        WifiNetworkEngine.putU16Be(tcp, 0, srcPort)
        WifiNetworkEngine.putU16Be(tcp, 2, dstPort)
        WifiNetworkEngine.putU32Be(tcp, 4, seq.toInt())
        WifiNetworkEngine.putU32Be(tcp, 8, ack.toInt())
        tcp[12] = 0x50 // hdrLen = 20B
        tcp[13] = (flags and 0x3f).toByte()
        WifiNetworkEngine.putU16Be(tcp, 14, 65535)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, tcp, 20, payload.size)
        WifiNetworkEngine.putU16Be(tcp, 16, WifiNetworkEngine.l4Checksum(srcIp, dstIp, WifiNetworkEngine.IP_PROTO_TCP, tcp))
        return buildIpv4Eth(srcMac, dstMac, srcIp, dstIp, WifiNetworkEngine.IP_PROTO_TCP, tcp)
    }

    private fun buildIpv4IcmpEchoEth(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        id: Int,
        seq: Int,
        data: ByteArray = "ping".toByteArray(StandardCharsets.US_ASCII),
    ): ByteArray {
        val icmp = ByteArray(8 + data.size)
        icmp[0] = 8 // Echo Request
        icmp[1] = 0
        WifiNetworkEngine.putU16Be(icmp, 4, id)
        WifiNetworkEngine.putU16Be(icmp, 6, seq)
        System.arraycopy(data, 0, icmp, 8, data.size)
        WifiNetworkEngine.putU16Be(icmp, 2, WifiNetworkEngine.ipChecksum(icmp, 0, icmp.size))
        return buildIpv4Eth(srcMac, dstMac, srcIp, dstIp, WifiNetworkEngine.IP_PROTO_ICMP, icmp)
    }

    private fun buildIpv4Eth(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        proto: Int,
        l4: ByteArray,
    ): ByteArray {
        val ipLen = 20 + l4.size
        val eth = ByteArray(14 + ipLen)
        System.arraycopy(dstMac, 0, eth, 0, 6)
        System.arraycopy(srcMac, 0, eth, 6, 6)
        WifiNetworkEngine.putU16Be(eth, 12, WifiNetworkEngine.ETHER_TYPE_IPV4)
        eth[14] = 0x45
        WifiNetworkEngine.putU16Be(eth, 16, ipLen)
        WifiNetworkEngine.putU16Be(eth, 18, 0x1234)
        WifiNetworkEngine.putU16Be(eth, 20, 0x4000)
        eth[22] = 64
        eth[23] = proto.toByte()
        System.arraycopy(srcIp, 0, eth, 26, 4)
        System.arraycopy(dstIp, 0, eth, 30, 4)
        WifiNetworkEngine.putU16Be(eth, 24, WifiNetworkEngine.ipChecksum(eth, 14, 20))
        System.arraycopy(l4, 0, eth, 34, l4.size)
        return eth
    }

    private fun buildArpRequest(srcMac: ByteArray, srcIp: ByteArray, targetIp: ByteArray): ByteArray {
        val arp = ByteArray(42)
        ByteArray(6) { 0xFF.toByte() }.copyInto(arp, 0)
        srcMac.copyInto(arp, 6)
        WifiNetworkEngine.putU16Be(arp, 12, WifiNetworkEngine.ETHER_TYPE_ARP)
        WifiNetworkEngine.putU16Be(arp, 14, 1)
        WifiNetworkEngine.putU16Be(arp, 16, WifiNetworkEngine.ETHER_TYPE_IPV4)
        arp[18] = 6
        arp[19] = 4
        WifiNetworkEngine.putU16Be(arp, 20, 1)
        srcMac.copyInto(arp, 22)
        srcIp.copyInto(arp, 28)
        targetIp.copyInto(arp, 38)
        return arp
    }

    private fun buildDhcpPacket(guestMac: ByteArray, xid: Int, msgType: Int, requestedIp: ByteArray? = null): ByteArray {
        val bootp = ByteArrayOutputStream(300)
        val hdr = ByteArray(240)
        hdr[0] = 1 // BOOTREQUEST
        hdr[1] = 1
        hdr[2] = 6
        WifiNetworkEngine.putU32Be(hdr, 4, xid)
        WifiNetworkEngine.putU16Be(hdr, 10, 0x8000) // Broadcast flag
        guestMac.copyInto(hdr, 28)
        WifiNetworkEngine.putU32Be(hdr, 236, 0x63825363)
        bootp.write(hdr)
        bootp.write(byteArrayOf(53, 1, msgType.toByte()))
        if (requestedIp != null) {
            bootp.write(byteArrayOf(50, 4))
            bootp.write(requestedIp)
        }
        bootp.write(255)
        return buildIpv4UdpEth(
            srcMac = guestMac,
            dstMac = ByteArray(6) { 0xFF.toByte() },
            srcIp = ByteArray(4),
            srcPort = 68,
            dstIp = ByteArray(4) { 0xFF.toByte() },
            dstPort = 67,
            payload = bootp.toByteArray(),
        )
    }

    private fun buildDnsQuery(txId: Int, domain: String): ByteArray {
        val out = ByteArrayOutputStream()
        val hdr = ByteArray(12)
        WifiNetworkEngine.putU16Be(hdr, 0, txId)
        WifiNetworkEngine.putU16Be(hdr, 2, 0x0100) // RD=1
        WifiNetworkEngine.putU16Be(hdr, 4, 1)      // QDCOUNT=1
        out.write(hdr)
        for (label in domain.split('.')) {
            val b = label.toByteArray(StandardCharsets.US_ASCII)
            out.write(b.size)
            out.write(b)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1)) // QTYPE=A, QCLASS=IN
        return out.toByteArray()
    }

    private fun bringUpSdioAndJoinAp(card: SdioCardPort, targetSsid: String): List<Int> {
        card.executeCommand(0, 0)
        card.executeCommand(5, 0x00300000L)
        card.executeCommand(3, 0)
        card.executeCommand(7, 0x00010000L)
        // Enable F1 & F2 + IntEn (master + F1 + F2)
        card.executeCommand(52, cmd52Arg(true, 0, 0x02, 0x06))
        card.executeCommand(52, cmd52Arg(true, 0, 0x04, 0x07))

        // WLC_UP
        val up = buildSdpcmCdc(Bcm43430WifiController.WLC_UP, 1, true, ByteArray(0), seq = 0)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, up.size), up)
        drainPendingFrames(card)

        // WLC_SET_SSID(targetSsid) -> STA join
        val ssidBytes = targetSsid.toByteArray(StandardCharsets.UTF_8)
        val ssidStruct = ByteArray(36)
        ssidStruct[0] = ssidBytes.size.toByte()
        System.arraycopy(ssidBytes, 0, ssidStruct, 4, ssidBytes.size)
        val setSsid = buildSdpcmCdc(Bcm43430WifiController.WLC_SET_SSID, 2, true, ssidStruct, seq = 1)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, setSsid.size), setSsid)

        val eventTypes = mutableListOf<Int>()
        for (f in drainPendingFrames(card)) {
            if (f.channel == Bcm43430WifiController.SDPCM_EVENT_CHANNEL && f.payload.size >= 4 + 72) {
                val ethOff = 4
                val etherType = WifiNetworkEngine.u16Be(f.payload, ethOff + 12)
                if (etherType == 0x886C) {
                    val evtType = WifiNetworkEngine.u32Be(f.payload, ethOff + 28).toInt()
                    eventTypes += evtType
                }
            }
        }
        return eventTypes
    }

    @Test
    fun natModeFullSdioDhcpArpDnsRealHttpGetPostAndUdpEcho() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { httpServer ->
            DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { udpEchoServer ->
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { localDnsServer ->
                    val receivedHttpRequests = CopyOnWriteArrayList<String>()
                    val serverRunning = AtomicReference(true)

                    // Real HTTP/1.1 Server on loopback handling both GET and POST
                    Thread.ofVirtual().start {
                        while (serverRunning.get()) {
                            val client = runCatching { httpServer.accept() }.getOrNull() ?: break
                            Thread.ofVirtual().start {
                                client.use { sock ->
                                    sock.soTimeout = 3000
                                    val inp = sock.getInputStream()
                                    val rawBuf = ByteArrayOutputStream()
                                    val tmp = ByteArray(1024)
                                    var headerEnd = -1
                                    var contentLength = 0
                                    while (true) {
                                        val n = inp.read(tmp)
                                        if (n <= 0) break
                                        rawBuf.write(tmp, 0, n)
                                        val cur = rawBuf.toByteArray()
                                        if (headerEnd < 0) {
                                            val str = String(cur, StandardCharsets.ISO_8859_1)
                                            val idx = str.indexOf("\r\n\r\n")
                                            if (idx >= 0) {
                                                headerEnd = idx + 4
                                                val clLine = str.substring(0, idx).lineSequence()
                                                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                                contentLength = clLine?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                                            }
                                        }
                                        if (headerEnd >= 0 && cur.size >= headerEnd + contentLength) break
                                    }
                                    val reqText = String(rawBuf.toByteArray(), StandardCharsets.UTF_8)
                                    receivedHttpRequests += reqText
                                    val isPost = reqText.startsWith("POST ")
                                    val body = if (isPost) """{"ok":true,"method":"POST"}""" else "Hello from real host HTTP!"
                                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                                    val resp = (
                                        "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: text/plain; charset=utf-8\r\n" +
                                            "Content-Length: ${bodyBytes.size}\r\n" +
                                            "Connection: close\r\n\r\n"
                                        ).toByteArray(StandardCharsets.US_ASCII) + bodyBytes
                                    sock.getOutputStream().write(resp)
                                    sock.getOutputStream().flush()
                                }
                            }
                        }
                    }

                    // Real UDP Echo Server on loopback
                    Thread.ofVirtual().start {
                        val buf = ByteArray(1024)
                        while (serverRunning.get() && !udpEchoServer.isClosed) {
                            try {
                                val pkt = DatagramPacket(buf, buf.size)
                                udpEchoServer.receive(pkt)
                                val reply = ("ECHO:" + String(buf, 0, pkt.length, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)
                                udpEchoServer.send(DatagramPacket(reply, reply.size, pkt.socketAddress))
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                    // Real Wire-Level UDP DNS Server on loopback ephemeral port -> resolves "camera-cloud.test" to 127.0.0.1
                    Thread.ofVirtual().start {
                        val buf = ByteArray(512)
                        while (serverRunning.get() && !localDnsServer.isClosed) {
                            try {
                                val pkt = DatagramPacket(buf, buf.size)
                                localDnsServer.receive(pkt)
                                val q = buf.copyOfRange(0, pkt.length)
                                val resp = ByteArrayOutputStream()
                                val hdr = q.copyOfRange(0, 12)
                                hdr[2] = 0x81.toByte()
                                hdr[3] = 0x80.toByte()
                                hdr[6] = 0; hdr[7] = 1 // ANCOUNT = 1
                                resp.write(hdr)
                                resp.write(q.copyOfRange(12, q.size))
                                // RR: ptr 0xC00C, TYPE A, CLASS IN, TTL 60, RDLEN 4, 127.0.0.1
                                resp.write(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 1))
                                val out = resp.toByteArray()
                                localDnsServer.send(DatagramPacket(out, out.size, pkt.socketAddress))
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                    val sdio = FakeSdioBusPort()
                    val guestMac = Bcm43438BtController.parseMac6("02:11:22:33:44:55")
                    val natCfg = WifiNetworkConfig(
                        enabled = true,
                        mode = WifiNetworkMode.NAT,
                        ssid = "GFX100S-WiFi",
                        macAddress = "02:11:22:33:44:55",
                        subnetCidr = "192.168.42.0/24",
                        gatewayIp = "192.168.42.1",
                        guestIp = "192.168.42.100",
                        dnsServers = listOf("127.0.0.1:${localDnsServer.localPort}"),
                    )
                    Bcm43430WifiController(
                        initialMac = guestMac,
                        cardIrqSink = { sdio.setCardInterrupt(it) },
                        peerNameProvider = { "FujiEmu Host" },
                        initialNetworkConfig = natCfg,
                    ).use { wifi ->
                        sdio.attachCard(wifi)

                        // 1. Scan & Associate via SDIO F2 CDC
                        val events = bringUpSdioAndJoinAp(wifi, "GFX100S-WiFi")
                        assertTrue(Bcm43430WifiController.WLC_E_JOIN in events)
                        assertTrue(Bcm43430WifiController.WLC_E_AUTH in events)
                        assertTrue(Bcm43430WifiController.WLC_E_ASSOC in events)
                        assertTrue(Bcm43430WifiController.WLC_E_SET_SSID in events)
                        assertTrue(Bcm43430WifiController.WLC_E_LINK in events)
                        assertTrue(wifi.getNetworkStatus().linkUp)

                        // 2. DHCP DISCOVER -> OFFER -> REQUEST -> ACK over SDIO F2 BDC
                        sendBdcEthOverSdio(wifi, buildDhcpPacket(guestMac, 0xCAFE1234.toInt(), msgType = 1))
                        val dhcpOfferEth = assertNotNull(readNextRxEthernet(wifi))
                        assertEquals(192, dhcpOfferEth[42 + 16].toInt() and 0xff)
                        assertEquals(100, dhcpOfferEth[42 + 19].toInt() and 0xff) // yiaddr = 192.168.42.100

                        val guestIp = byteArrayOf(192.toByte(), 168.toByte(), 42, 100)
                        val gwIp = byteArrayOf(192.toByte(), 168.toByte(), 42, 1)
                        sendBdcEthOverSdio(wifi, buildDhcpPacket(guestMac, 0xCAFE1234.toInt(), msgType = 3, requestedIp = guestIp))
                        val dhcpAckEth = assertNotNull(readNextRxEthernet(wifi))
                        assertEquals(100, dhcpAckEth[42 + 19].toInt() and 0xff)
                        assertEquals("192.168.42.100", wifi.getNetworkStatus().guestIp)

                        // 3. ARP Request for gateway 192.168.42.1 -> ARP Reply
                        sendBdcEthOverSdio(wifi, buildArpRequest(guestMac, guestIp, gwIp))
                        val arpReply = assertNotNull(readNextRxEthernet(wifi))
                        assertEquals(WifiNetworkEngine.ETHER_TYPE_ARP, WifiNetworkEngine.u16Be(arpReply, 12))
                        assertEquals(2, WifiNetworkEngine.u16Be(arpReply, 20))
                        val gwMac = arpReply.copyOfRange(22, 28)
                        assertContentEquals(WifiNetworkEngine.GATEWAY_MAC, gwMac)

                        // 4. ICMP Echo Request to gateway -> Echo Reply
                        sendBdcEthOverSdio(wifi, buildIpv4IcmpEchoEth(guestMac, gwMac, guestIp, gwIp, 0x42, 1))
                        val icmpReply = assertNotNull(readNextRxEthernet(wifi))
                        assertEquals(WifiNetworkEngine.IP_PROTO_ICMP, icmpReply[23].toInt() and 0xff)
                        assertEquals(0, icmpReply[34].toInt() and 0xff) // Echo Reply

                        // 5. Real UDP DNS query -> forwarded to localDnsServer -> resolves camera-cloud.test -> 127.0.0.1
                        val dnsQuery = buildDnsQuery(0x7788, "camera-cloud.test")
                        sendBdcEthOverSdio(wifi, buildIpv4UdpEth(guestMac, gwMac, guestIp, 53000, gwIp, 53, dnsQuery))
                        val dnsRespEth = assertNotNull(readNextRxEthernet(wifi))
                        val dnsUdpLen = WifiNetworkEngine.u16Be(dnsRespEth, 38)
                        val dnsResp = dnsRespEth.copyOfRange(42, 34 + dnsUdpLen)
                        assertEquals(0x7788, WifiNetworkEngine.u16Be(dnsResp, 0))
                        assertEquals(1, WifiNetworkEngine.u16Be(dnsResp, 6)) // ANCOUNT = 1
                        assertContentEquals(byteArrayOf(127, 0, 0, 1), dnsResp.copyOfRange(dnsResp.size - 4, dnsResp.size))

                        // 6. Real UDP Echo through NAT
                        val loopbackIp = byteArrayOf(127, 0, 0, 1)
                        sendBdcEthOverSdio(
                            wifi,
                            buildIpv4UdpEth(
                                guestMac, gwMac, guestIp, 41000,
                                loopbackIp, udpEchoServer.localPort,
                                "fuji-nat-udp".toByteArray(StandardCharsets.UTF_8),
                            ),
                        )
                        val udpReplyEth = assertNotNull(readNextRxEthernet(wifi))
                        val udpReplyLen = WifiNetworkEngine.u16Be(udpReplyEth, 38)
                        val udpPayload = String(udpReplyEth, 42, udpReplyLen - 8, StandardCharsets.UTF_8)
                        assertEquals("ECHO:fuji-nat-udp", udpPayload)

                        // 7. Real TCP HTTP GET & POST through NAT to real host ServerSocket
                        fun readNextTcpForGuestPort(guestPort: Int, timeoutMs: Long = 3000L): ByteArray? {
                            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                            while (System.nanoTime() < deadline) {
                                val seg = readNextRxEthernet(wifi, 100L) ?: continue
                                if (seg.size >= 54 && (seg[23].toInt() and 0xff) == WifiNetworkEngine.IP_PROTO_TCP) {
                                    val ihl = (seg[14].toInt() and 0x0f) * 4
                                    val dstP = WifiNetworkEngine.u16Be(seg, 14 + ihl + 2)
                                    if (dstP == guestPort) return seg
                                }
                            }
                            return null
                        }

                        fun executeGuestHttpOverNat(guestPort: Int, rawHttpRequest: String): String {
                            var guestSeq = 10_000L
                            // SYN
                            sendBdcEthOverSdio(
                                wifi,
                                buildIpv4TcpEth(guestMac, gwMac, guestIp, guestPort, loopbackIp, httpServer.localPort, guestSeq, 0L, 0x02),
                            )
                            val synAck = assertNotNull(readNextTcpForGuestPort(guestPort), "Expected TCP SYN-ACK")
                            val synIhl = (synAck[14].toInt() and 0x0f) * 4
                            val synTcpOff = 14 + synIhl
                            val serverSeq = WifiNetworkEngine.u32Be(synAck, synTcpOff + 4)
                            val synFlags = synAck[synTcpOff + 13].toInt() and 0x3f
                            assertEquals(0x12, synFlags) // SYN | ACK
                            guestSeq++
                            var guestAck = (serverSeq + 1L) and 0xffff_ffffL

                            // ACK + PSH|ACK carrying HTTP request
                            sendBdcEthOverSdio(
                                wifi,
                                buildIpv4TcpEth(guestMac, gwMac, guestIp, guestPort, loopbackIp, httpServer.localPort, guestSeq, guestAck, 0x10),
                            )
                            val reqBytes = rawHttpRequest.toByteArray(StandardCharsets.UTF_8)
                            sendBdcEthOverSdio(
                                wifi,
                                buildIpv4TcpEth(guestMac, gwMac, guestIp, guestPort, loopbackIp, httpServer.localPort, guestSeq, guestAck, 0x18, reqBytes),
                            )
                            guestSeq += reqBytes.size

                            val respAcc = ByteArrayOutputStream()
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                            while (System.nanoTime() < deadline) {
                                val seg = readNextTcpForGuestPort(guestPort, 250L) ?: break
                                val ihl = (seg[14].toInt() and 0x0f) * 4
                                val tcpOff = 14 + ihl
                                val dataOff = ((seg[tcpOff + 12].toInt() ushr 4) and 0x0f) * 4
                                val flags = seg[tcpOff + 13].toInt() and 0x3f
                                val ipTotalLen = WifiNetworkEngine.u16Be(seg, 16)
                                val dataStart = tcpOff + dataOff
                                val dataEnd = 14 + ipTotalLen
                                if (dataEnd > dataStart) {
                                    respAcc.write(seg, dataStart, dataEnd - dataStart)
                                    guestAck = (guestAck + (dataEnd - dataStart)) and 0xffff_ffffL
                                }
                                if ((flags and 0x01) != 0) { // FIN
                                    guestAck = (guestAck + 1L) and 0xffff_ffffL
                                    // Send FIN|ACK to close cleanly
                                    sendBdcEthOverSdio(
                                        wifi,
                                        buildIpv4TcpEth(guestMac, gwMac, guestIp, guestPort, loopbackIp, httpServer.localPort, guestSeq, guestAck, 0x11),
                                    )
                                    break
                                }
                            }
                            return String(respAcc.toByteArray(), StandardCharsets.UTF_8)
                        }

                        val getResp = executeGuestHttpOverNat(
                            50101,
                            "GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
                        )
                        assertTrue(getResp.startsWith("HTTP/1.1 200 OK"), "Unexpected GET response: $getResp")
                        assertTrue(getResp.endsWith("Hello from real host HTTP!"))

                        val postBody = """{"camera":"GFX100S","mode":"NAT"}"""
                        val postResp = executeGuestHttpOverNat(
                            50102,
                            "POST /api/upload HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: ${postBody.length}\r\nConnection: close\r\n\r\n$postBody",
                        )
                        assertTrue(postResp.startsWith("HTTP/1.1 200 OK"), "Unexpected POST response: $postResp")
                        assertTrue(postResp.contains(""""method":"POST""""))
                        assertEquals(2, receivedHttpRequests.size)
                        assertTrue(receivedHttpRequests[1].contains(postBody))

                        val st = wifi.getNetworkStatus()
                        assertTrue(st.txPackets > 5 && st.rxPackets > 5)
                        assertTrue(st.txBytes > 200 && st.rxBytes > 200)
                    }
                    serverRunning.set(false)
                }
            }
        }
    }

    @Test
    fun hostOnlyModeForwardsGatewayToLoopbackAndStrictlyBlocksExternalWanWithIcmpUnreachable() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { hostService ->
            Thread.ofVirtual().start {
                runCatching {
                    hostService.accept().use { sock ->
                        val out = "PTPIP-HOST-OK".toByteArray(StandardCharsets.US_ASCII)
                        sock.getOutputStream().write(out)
                        sock.getOutputStream().flush()
                    }
                }
            }

            val sdio = FakeSdioBusPort()
            val guestMac = Bcm43438BtController.parseMac6("02:11:22:33:44:66")
            val hostOnlyCfg = WifiNetworkConfig(
                enabled = true,
                mode = WifiNetworkMode.HOST_ONLY,
                ssid = "GFX100S-HostOnly",
                macAddress = "02:11:22:33:44:66",
                hostOnlyLoopbackForward = true,
                hostOnlySubnetCidr = "192.168.56.0/24",
            )
            Bcm43430WifiController(
                initialMac = guestMac,
                cardIrqSink = { sdio.setCardInterrupt(it) },
                peerNameProvider = { "FujiEmu Host" },
                initialNetworkConfig = hostOnlyCfg,
            ).use { wifi ->
                sdio.attachCard(wifi)
                bringUpSdioAndJoinAp(wifi, "GFX100S-HostOnly")

                // 1. DHCP assigns 192.168.56.100 & gateway 192.168.56.1
                sendBdcEthOverSdio(wifi, buildDhcpPacket(guestMac, 0x56560001, msgType = 1))
                val offer = assertNotNull(readNextRxEthernet(wifi))
                assertEquals(56, offer[42 + 18].toInt() and 0xff)
                assertEquals(100, offer[42 + 19].toInt() and 0xff)

                val guestIp = byteArrayOf(192.toByte(), 168.toByte(), 56, 100)
                val hostGwIp = byteArrayOf(192.toByte(), 168.toByte(), 56, 1)
                val gwMac = WifiNetworkEngine.GATEWAY_MAC

                // 2. Host-Only TCP to 192.168.56.1:<port> forwards to host 127.0.0.1:<port>
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(guestMac, gwMac, guestIp, 52000, hostGwIp, hostService.localPort, 1000L, 0L, 0x02),
                )
                val synAck = assertNotNull(readNextRxEthernet(wifi))
                assertEquals(0x12, synAck[47].toInt() and 0x3f) // SYN|ACK!
                val dataSeg = assertNotNull(readNextRxEthernet(wifi))
                val text = String(dataSeg, 54, WifiNetworkEngine.u16Be(dataSeg, 16) - 40, StandardCharsets.US_ASCII)
                assertEquals("PTPIP-HOST-OK", text)
                drainPendingFrames(wifi)

                // 3. External WAN TCP 1.1.1.1:80 is STRICTLY BLOCKED with ICMP Destination Unreachable (Type 3)!
                val extWebIp = byteArrayOf(1, 1, 1, 1)
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(guestMac, gwMac, guestIp, 52001, extWebIp, 80, 2000L, 0L, 0x02),
                )
                val icmpBlockTcp = assertNotNull(readNextRxEthernet(wifi))
                assertEquals(WifiNetworkEngine.IP_PROTO_ICMP, icmpBlockTcp[23].toInt() and 0xff)
                assertEquals(3, icmpBlockTcp[34].toInt() and 0xff) // ICMP Destination Unreachable!

                // 4. External WAN UDP 8.8.8.8:53 is STRICTLY BLOCKED with ICMP Destination Unreachable (Type 3)!
                val extDnsIp = byteArrayOf(8, 8, 8, 8)
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4UdpEth(guestMac, gwMac, guestIp, 53001, extDnsIp, 53, buildDnsQuery(0x1122, "example.com")),
                )
                val icmpBlockUdp = assertNotNull(readNextRxEthernet(wifi))
                assertEquals(WifiNetworkEngine.IP_PROTO_ICMP, icmpBlockUdp[23].toInt() and 0xff)
                assertEquals(3, icmpBlockUdp[34].toInt() and 0xff) // ICMP Destination Unreachable!
                assertEquals(2L, wifi.networkEngine.blockedExternalPackets.get())
            }
        }
    }

    @Test
    fun bridgeModeL2FrameRoundTripWindowsDnsAndAdapterMatchingFallbackAndLiveModeSwitching() {
        // 1. Verify Windows ipconfig /all (English + Simplified Chinese) & Linux resolv.conf DNS parsing
        val sampleWinIpconfig = """
            Windows IP Configuration

            Wireless LAN adapter Wi-Fi:
               Connection-specific DNS Suffix  . : lan
               IPv4 Address. . . . . . . . . . . : 192.168.1.50
               DNS Servers . . . . . . . . . . . : 223.5.5.5
                                                   119.29.29.29

            无线局域网适配器 WLAN 2:
               DNS 服务器  . . . . . . . . . . . : 114.114.114.114
                                                   8.8.4.4
        """.trimIndent()
        assertEquals(
            listOf("223.5.5.5", "119.29.29.29", "114.114.114.114", "8.8.4.4"),
            HostDnsResolver.parseWindowsIpconfigAll(sampleWinIpconfig),
        )
        assertEquals(
            listOf("1.1.1.1", "9.9.9.9"),
            HostDnsResolver.parseResolvConf("# comment\nnameserver 127.0.0.53\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n"),
        )

        // 2. Verify Windows Npcap \Device\NPF_{GUID} matching & OpenVPN TAP-Windows6 Registry GUID extraction
        val winNic = HostNetworkInterfaceInfo(
            name = "wlan0",
            displayName = "Intel(R) Wi-Fi 6 AX200 160MHz",
            ipv4Addresses = listOf("192.168.1.50"),
            macAddress = "AA:BB:CC:DD:EE:FF",
            up = true,
            loopback = false,
        )
        val pcapDevs = listOf(
            PcapFfmBridgeTransport.Companion.PcapDeviceEntry("\\Device\\NPF_Loopback", "Adapter for loopback traffic capture"),
            PcapFfmBridgeTransport.Companion.PcapDeviceEntry(
                "\\Device\\NPF_{12345678-ABCD-4321-9999-001122334455}",
                "Intel(R) Wi-Fi 6 AX200 160MHz",
            ),
        )
        assertEquals(
            "\\Device\\NPF_{12345678-ABCD-4321-9999-001122334455}",
            PcapFfmBridgeTransport.selectPcapDeviceName("Wi-Fi 6 AX200", winNic, pcapDevs, isWin = true),
        )

        val sampleTapReg = """
            HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}\0005
                ComponentId    REG_SZ    tap0901
                DriverDesc     REG_SZ    TAP-Windows Adapter V9
                NetCfgInstanceId    REG_SZ    {87654321-DCBA-1234-8888-AABBCCDDEEFF}
        """.trimIndent()
        assertEquals(
            "{87654321-DCBA-1234-8888-AABBCCDDEEFF}",
            WindowsTapBridgeTransport.parseWindowsRegistryTapGuid(sampleTapReg),
        )

        // 3. Verify Windows graceful fallback to NIC_BOUND_FALLBACK when Npcap/TAP-Windows6 are not installed
        val fallbackBridge = BridgeTransportFactory.createBridgeTransport(
            config = WifiNetworkConfig(mode = WifiNetworkMode.BRIDGE, bridgeInterface = "Wi-Fi 6 AX200"),
            guestMacProvider = { ByteArray(6) },
            rxFrameSink = {},
            osName = "Windows 11",
            hostInterfaces = listOf(
                HostNetworkInterfaceInfo(
                    name = "wlan0",
                    displayName = "Intel(R) Wi-Fi 6 AX200 160MHz",
                    ipv4Addresses = listOf("127.0.0.1"),
                    macAddress = "AA:BB:CC:DD:EE:FF",
                    up = true,
                    loopback = false,
                ),
            ),
        )
        assertEquals("NIC_BOUND_FALLBACK", fallbackBridge.backendId)
        assertTrue(fallbackBridge.stateDescription.contains("Install Npcap for raw L2"))
        assertTrue(fallbackBridge.isOperational)
        fallbackBridge.close()

        // Non-existent interface reports NO_INTERFACE without throwing
        val missingBridge = BridgeTransportFactory.createBridgeTransport(
            config = WifiNetworkConfig(mode = WifiNetworkMode.BRIDGE, bridgeInterface = "nonexistent_adapter_xyz"),
            guestMacProvider = { ByteArray(6) },
            rxFrameSink = {},
            osName = "Windows 11",
            hostInterfaces = listOf(winNic),
        )
        assertFalse(missingBridge.isOperational)
        assertEquals("NO_INTERFACE", missingBridge.stateDescription)

        // 4. Verify real Bridge L2 Ethernet frame round-trip over UDP_L2 + live mode switching (NAT -> HOST_ONLY -> BRIDGE -> NAT)
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { externalLanSwitch ->
            externalLanSwitch.soTimeout = 2000
            val ctx = TestContext()
            val sdio = FakeSdioBusPort()
            ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = FakeUartSerialPort()
            ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio

            // Start with enabled=false (--wifi-disabled) to verify bcm43438.control is STILL registered and live-toggleable!
            val comp = Bcm43438Factory().create(ctx, mapOf("enabled" to false)) as StatefulComponent
            comp.start()
            val ctrl = assertNotNull(ctx.ports.find(Bcm43438Factory.CONTROL_PORT, WirelessControlPort::class.java))
            assertFalse(ctrl.getNetworkStatus().linkUp)

            // Enable and start in NAT
            val stNat = ctrl.reconfigureNetwork(WifiNetworkConfig(enabled = true, mode = WifiNetworkMode.NAT, ssid = "SwitchTest-AP"))
            assertEquals(WifiNetworkMode.NAT, stNat.mode)
            val card = assertNotNull(sdio.card)
            bringUpSdioAndJoinAp(card, "SwitchTest-AP")
            assertTrue(ctrl.getNetworkStatus().linkUp)

            // Live switch: NAT -> HOST_ONLY
            val stHostOnly = ctrl.reconfigureNetwork(
                ctrl.getNetworkConfig().copy(mode = WifiNetworkMode.HOST_ONLY, hostOnlySubnetCidr = "192.168.56.0/24"),
            )
            assertEquals(WifiNetworkMode.HOST_ONLY, stHostOnly.mode)
            assertEquals("192.168.56.1", stHostOnly.gatewayIp)
            assertEquals("192.168.56.100", stHostOnly.guestIp)

            // Live switch: HOST_ONLY -> BRIDGE (real L2 UDP tunnel to externalLanSwitch)
            val stBridge = ctrl.reconfigureNetwork(
                ctrl.getNetworkConfig().copy(
                    mode = WifiNetworkMode.BRIDGE,
                    bridgeInterface = "udp://127.0.0.1:${externalLanSwitch.localPort}",
                ),
            )
            assertEquals(WifiNetworkMode.BRIDGE, stBridge.mode)
            assertEquals("UDP_L2", stBridge.bridgeBackend)
            assertTrue(stBridge.linkUp)

            // Guest transmits raw L2 Ethernet frame -> arrives byte-exact on externalLanSwitch!
            val guestMac = Bcm43438BtController.parseMac6(ctrl.getNetworkConfig().macAddress)
            val lanPeerMac = Bcm43438BtController.parseMac6("02:AA:BB:CC:DD:EE")
            val rawOutEth = ByteArray(60)
            lanPeerMac.copyInto(rawOutEth, 0)
            guestMac.copyInto(rawOutEth, 6)
            WifiNetworkEngine.putU16Be(rawOutEth, 12, 0x88B5) // IEEE 802.1 Local Experimental EtherType
            "L2-BRIDGE-TX-PAYLOAD".toByteArray(StandardCharsets.US_ASCII).copyInto(rawOutEth, 14)
            sendBdcEthOverSdio(card, rawOutEth)

            val rxBuf = ByteArray(256)
            val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
            externalLanSwitch.receive(rxPkt)
            assertContentEquals(rawOutEth, rxBuf.copyOfRange(0, rxPkt.length))

            // External LAN peer sends raw L2 Ethernet frame back -> arrives byte-exact in guest SDIO F2 BDC!
            val rawInEth = ByteArray(60)
            guestMac.copyInto(rawInEth, 0)
            lanPeerMac.copyInto(rawInEth, 6)
            WifiNetworkEngine.putU16Be(rawInEth, 12, 0x88B5)
            "L2-BRIDGE-RX-PAYLOAD".toByteArray(StandardCharsets.US_ASCII).copyInto(rawInEth, 14)
            externalLanSwitch.send(DatagramPacket(rawInEth, rawInEth.size, rxPkt.socketAddress))

            val guestReceivedEth = assertNotNull(readNextRxEthernet(card))
            assertContentEquals(rawInEth, guestReceivedEth)

            // Host NIC enumeration returns valid non-null list without throwing
            val hostNics = ctrl.listHostNetworkInterfaces(includeDown = true, includeLoopback = true)
            assertTrue(hostNics.isNotEmpty())

            // Live switch back: BRIDGE -> NAT
            val stBackToNat = ctrl.reconfigureNetwork(ctrl.getNetworkConfig().copy(mode = WifiNetworkMode.NAT))
            assertEquals(WifiNetworkMode.NAT, stBackToNat.mode)
            assertEquals("192.168.42.1", stBackToNat.gatewayIp)

            comp.close()
        }
    }

    @Test
    fun multiPortForwardingSsh22AndRdp3389WorksInBothNatAndHostOnlyModes() {
        for (mode in listOf(WifiNetworkMode.NAT, WifiNetworkMode.HOST_ONLY)) {
            val sdio = FakeSdioBusPort()
            val guestMac = Bcm43438BtController.parseMac6("02:11:22:33:44:77")
            val cfg = WifiNetworkConfig(
                enabled = true,
                mode = mode,
                ssid = "GFX100S-PortFwd",
                macAddress = "02:11:22:33:44:77",
                // Bind two ephemeral host ports forwarding to guest SSH :22 and RDP :3389 simultaneously!
                portForwards = listOf("0:22", "0:3389"),
            )
            Bcm43430WifiController(
                initialMac = guestMac,
                cardIrqSink = { sdio.setCardInterrupt(it) },
                peerNameProvider = { "FujiEmu Host" },
                initialNetworkConfig = cfg,
            ).use { wifi ->
                sdio.attachCard(wifi)
                bringUpSdioAndJoinAp(wifi, "GFX100S-PortFwd")

                val st = wifi.getNetworkStatus()
                assertEquals(2, st.portForwards.size, "Expected 2 active port-forwards in mode=$mode, got ${st.portForwards}")
                val sshRule = WifiPortForwardRule.parse(st.portForwards[0])
                val rdpRule = WifiPortForwardRule.parse(st.portForwards[1])
                assertEquals(22, sshRule.guestPort)
                assertEquals(3389, rdpRule.guestPort)
                assertTrue(sshRule.hostPort > 0 && rdpRule.hostPort > 0)

                val guestIp = WifiNetworkEngine.parseIpv4Bytes(st.guestIp)
                val gwIp = WifiNetworkEngine.parseIpv4Bytes(st.gatewayIp)
                val gwMac = WifiNetworkEngine.GATEWAY_MAC

                // 1. Inbound SSH (:22) — server-first SSH-2.0 banner + command round-trip
                val hostSshBannerRead = AtomicReference<String>("")
                val hostSshClientDone = CountDownLatch(1)
                Thread.ofVirtual().start {
                    java.net.Socket("127.0.0.1", sshRule.hostPort).use { sock ->
                        sock.soTimeout = 4000
                        val reader = sock.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        hostSshBannerRead.set(reader.readLine() ?: "")
                        sock.getOutputStream().write("SSH-2.0-OpenSSH_HostClient\r\n".toByteArray(StandardCharsets.US_ASCII))
                        sock.getOutputStream().flush()
                    }
                    hostSshClientDone.countDown()
                }

                val syn22 = assertNotNull(readNextRxEthernet(wifi), "Expected inbound TCP SYN to guest :22 in $mode")
                assertEquals(22, WifiNetworkEngine.u16Be(syn22, 36))
                val vSrcPort22 = WifiNetworkEngine.u16Be(syn22, 34)
                val hostIss22 = WifiNetworkEngine.u32Be(syn22, 38)
                assertEquals(0x02, syn22[47].toInt() and 0x3f) // SYN

                // Guest sshd sends SYN|ACK, receives ACK, then immediately sends SSH-2.0 server banner
                var guestSeq22 = 7000L
                var guestAck22 = (hostIss22 + 1L) and 0xffff_ffffL
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(guestMac, gwMac, guestIp, 22, gwIp, vSrcPort22, guestSeq22, guestAck22, 0x12),
                )
                guestSeq22++
                val ack22 = assertNotNull(readNextRxEthernet(wifi), "Expected handshake ACK on :22")
                assertEquals(0x10, ack22[47].toInt() and 0x3f)

                val serverBanner = "SSH-2.0-OpenSSH_for_Windows_9.5_FujiGFX100S\r\n".toByteArray(StandardCharsets.US_ASCII)
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(guestMac, gwMac, guestIp, 22, gwIp, vSrcPort22, guestSeq22, guestAck22, 0x18, serverBanner),
                )
                guestSeq22 += serverBanner.size

                // Drain ACK + PSH|ACK carrying host client's SSH banner
                val clientPayload = ByteArrayOutputStream()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < deadline && clientPayload.size() < 20) {
                    val seg = readNextRxEthernet(wifi, 200L) ?: continue
                    val ihl = (seg[14].toInt() and 0x0f) * 4
                    val tcpOff = 14 + ihl
                    val dOff = ((seg[tcpOff + 12].toInt() ushr 4) and 0x0f) * 4
                    val ipEnd = 14 + WifiNetworkEngine.u16Be(seg, 16)
                    if (ipEnd > tcpOff + dOff) {
                        clientPayload.write(seg, tcpOff + dOff, ipEnd - (tcpOff + dOff))
                    }
                }
                assertTrue(hostSshClientDone.await(3, TimeUnit.SECONDS))
                assertEquals("SSH-2.0-OpenSSH_for_Windows_9.5_FujiGFX100S", hostSshBannerRead.get())
                assertEquals("SSH-2.0-OpenSSH_HostClient\r\n", String(clientPayload.toByteArray(), StandardCharsets.US_ASCII))
                drainPendingFrames(wifi)

                // 2. Simultaneous Inbound RDP (:3389) — client-first X.224 Connection Request -> Confirm
                val rdpReplyRef = AtomicReference<String>("")
                val rdpDone = CountDownLatch(1)
                Thread.ofVirtual().start {
                    java.net.Socket("127.0.0.1", rdpRule.hostPort).use { sock ->
                        sock.soTimeout = 4000
                        sock.getOutputStream().write("RDP_X224_CONNECT".toByteArray(StandardCharsets.US_ASCII))
                        sock.getOutputStream().flush()
                        val b = ByteArray(64)
                        val n = sock.getInputStream().read(b)
                        if (n > 0) rdpReplyRef.set(String(b, 0, n, StandardCharsets.US_ASCII))
                    }
                    rdpDone.countDown()
                }

                val syn3389 = assertNotNull(readNextRxEthernet(wifi), "Expected inbound TCP SYN to guest :3389 in $mode")
                assertEquals(3389, WifiNetworkEngine.u16Be(syn3389, 36))
                val vSrcPort3389 = WifiNetworkEngine.u16Be(syn3389, 34)
                val hostIss3389 = WifiNetworkEngine.u32Be(syn3389, 38)

                var guestSeq3389 = 9000L
                val guestAck3389 = (hostIss3389 + 1L) and 0xffff_ffffL
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(guestMac, gwMac, guestIp, 3389, gwIp, vSrcPort3389, guestSeq3389, guestAck3389, 0x12),
                )
                guestSeq3389++

                val rdpInBuf = ByteArrayOutputStream()
                val rdpDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < rdpDeadline && rdpInBuf.size() < 16) {
                    val seg = readNextRxEthernet(wifi, 200L) ?: continue
                    val ihl = (seg[14].toInt() and 0x0f) * 4
                    val tcpOff = 14 + ihl
                    val dOff = ((seg[tcpOff + 12].toInt() ushr 4) and 0x0f) * 4
                    val ipEnd = 14 + WifiNetworkEngine.u16Be(seg, 16)
                    if (ipEnd > tcpOff + dOff) {
                        rdpInBuf.write(seg, tcpOff + dOff, ipEnd - (tcpOff + dOff))
                    }
                }
                assertEquals("RDP_X224_CONNECT", String(rdpInBuf.toByteArray(), StandardCharsets.US_ASCII))

                val rdpCc = "RDP_X224_CONFIRM".toByteArray(StandardCharsets.US_ASCII)
                sendBdcEthOverSdio(
                    wifi,
                    buildIpv4TcpEth(
                        guestMac, gwMac, guestIp, 3389, gwIp, vSrcPort3389,
                        guestSeq3389, (guestAck3389 + rdpInBuf.size()) and 0xffff_ffffL,
                        0x18, rdpCc,
                    ),
                )
                assertTrue(rdpDone.await(3, TimeUnit.SECONDS))
                assertEquals("RDP_X224_CONFIRM", rdpReplyRef.get())
            }
        }
    }
}
