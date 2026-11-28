package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

class Bcm43438FactoryTest {
    private class FakeUartSerialPort : UartSerialPort {
        var peer: UartSerialPeer? = null
        val rxQueue = ArrayDeque<Byte>()
        override fun attachPeer(peer: UartSerialPeer) { this.peer = peer }
        override fun detachPeer(peer: UartSerialPeer) { if (this.peer === peer) this.peer = null }
        override fun pushRxBytes(bytes: ByteArray) { bytes.forEach(rxQueue::addLast) }
        override fun rxPendingBytes(): Int = rxQueue.size
        fun sendHostBytes(vararg bytes: Int) {
            for (b in bytes) peer?.onHostTx((b and 0xff).toByte())
        }
        fun drainRx(): List<Int> {
            val out = mutableListOf<Int>()
            while (rxQueue.isNotEmpty()) out += rxQueue.removeFirst().toInt() and 0xff
            return out
        }
    }

    private class FakeSdioBusPort : SdioBusPort {
        var card: SdioCardPort? = null
        var cardIrq: Boolean = false
        override fun attachCard(card: SdioCardPort) { this.card = card }
        override fun detachCard(card: SdioCardPort) { if (this.card === card) this.card = null }
        override fun setCardInterrupt(asserted: Boolean) { cardIrq = asserted }
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
        buf.put(0) // channel 0 = CDC Control
        buf.put(0)
        buf.put(12) // hdrlen
        buf.put(0)
        buf.put(0)
        buf.putShort(0)
        buf.putInt(cmd)
        buf.putInt(payload.size)
        buf.putInt((reqId shl 16) or (if (isSet) 2 else 0))
        buf.putInt(0)
        buf.put(payload)
        return buf.array()
    }

    @Test
    fun bcm43438BlePatchRamAdvertisingVirtualPeerAndSnapshotRoundTrip() {
        val ctx = TestContext()
        val uart = FakeUartSerialPort()
        val sdio = FakeSdioBusPort()
        ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = uart
        ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio

        val cfg = mapOf(
            "enabled" to true,
            "btBdAddr" to "02:46:4A:10:00:01",
            "wifiMacAddr" to "02:46:4A:10:00:00",
            "deviceName" to "FujiEmu Host",
        )
        val comp = Bcm43438Factory().create(ctx, cfg) as StatefulComponent
        comp.start()
        val ctrl = ctx.ports.find(Bcm43438Factory.CONTROL_PORT, WirelessControlPort::class.java)!!

        // 1. Authentic Boot-ROM PatchRAM flow: Reset -> Baud -> Minidriver -> Write_RAM -> Launch_RAM
        uart.sendHostBytes(0x01, 0x03, 0x0c, 0x00)
        assertEquals(listOf(0x04, 0x0e, 0x04, 0x01, 0x03, 0x0c, 0x00), uart.drainRx())
        uart.sendHostBytes(0x01, 0x18, 0xfc, 0x06, 0x00, 0x00, 0x60, 0xe3, 0x16, 0x00) // 1,500,000 bps
        assertEquals(listOf(0x04, 0x0e, 0x04, 0x01, 0x18, 0xfc, 0x00), uart.drainRx())
        uart.sendHostBytes(0x01, 0x2e, 0xfc, 0x00) // Download_Minidriver
        uart.drainRx()
        uart.sendHostBytes(0x01, 0x4c, 0xfc, 0x08, 0x00, 0x00, 0x20, 0x00, 0xde, 0xad, 0xbe, 0xef) // Write_RAM
        uart.drainRx()
        uart.sendHostBytes(0x01, 0x4e, 0xfc, 0x04, 0xff, 0xff, 0xff, 0xff) // Launch_RAM
        uart.drainRx()

        assertEquals(true, comp.state()["patchRamLoaded"])

        // 2. Read_BD_ADDR & LE Advertising with "FujiEmu Host" AD payload (guest-configured, never hardcoded!)
        uart.sendHostBytes(0x01, 0x09, 0x10, 0x00)
        assertEquals(listOf(0x04, 0x0e, 0x0a, 0x01, 0x09, 0x10, 0x00, 0x01, 0x00, 0x10, 0x4a, 0x46, 0x02), uart.drainRx())

        val nameBytes = "FujiEmu Host".toByteArray(Charsets.UTF_8)
        val scanRespParams = ByteArray(32)
        scanRespParams[0] = (2 + nameBytes.size).toByte()
        scanRespParams[1] = (1 + nameBytes.size).toByte()
        scanRespParams[2] = 0x09 // Complete Local Name
        System.arraycopy(nameBytes, 0, scanRespParams, 3, nameBytes.size)
        uart.sendHostBytes(0x01, 0x09, 0x20, 32, *scanRespParams.map { it.toInt() and 0xff }.toIntArray())
        uart.drainRx()
        uart.sendHostBytes(0x01, 0x0a, 0x20, 0x01, 0x01) // LE_Set_Advertise_Enable(1)
        uart.drainRx()

        assertEquals(true, comp.state()["advertising"])
        assertEquals("FujiEmu Host", comp.state()["advertisedName"])

        // 3. VirtualBlePeer connect -> emits LE Connection Complete, reads GAP 0x2A00 Device Name over ACL ATT
        ctrl.applyWirelessAction(mapOf("action" to "ble.connect"))
        val connEvt = uart.drainRx()
        assertEquals(0x04, connEvt[0])
        assertEquals(0x3E, connEvt[1]) // HCI LE Meta Event
        assertEquals(0x01, connEvt[3]) // LE Connection Complete
        assertEquals(true, comp.state()["connected"])
        assertEquals(false, comp.state()["advertising"])

        ctrl.applyWirelessAction(mapOf("action" to "ble.readGapDeviceName"))
        val attReqPkt = uart.drainRx()
        assertEquals(0x02, attReqPkt[0]) // H4 ACL Data
        assertEquals(0x08, attReqPkt[9]) // ATT_READ_BY_TYPE_REQ

        // Guest replies with ATT_READ_BY_TYPE_RSP carrying "FujiEmu Host"
        val attRsp = byteArrayOf(0x09, (2 + nameBytes.size).toByte(), 0x03, 0x00) + nameBytes
        val l2capLen = attRsp.size
        val aclLen = 4 + l2capLen
        val hostAcl = byteArrayOf(
            0x02, 0x40, 0x20, (aclLen and 0xff).toByte(), ((aclLen ushr 8) and 0xff).toByte(),
            (l2capLen and 0xff).toByte(), ((l2capLen ushr 8) and 0xff).toByte(), 0x04, 0x00,
        ) + attRsp
        uart.sendHostBytes(*hostAcl.map { it.toInt() and 0xff }.toIntArray())
        val nocp = uart.drainRx()
        assertEquals(0x13, nocp[1]) // Number of Completed Packets
        val btMap = comp.state()["bluetooth"] as Map<*, *>
        val peerMap = btMap["virtualPeer"] as Map<*, *>
        assertEquals("FujiEmu Host", peerMap["readGapDeviceName"])

        // 4. Snapshot round-trip
        val snap = comp.saveSnapshot()
        val ctx2 = TestContext()
        ctx2.claims[Bcm43438Factory.DEFAULT_UART_PORT] = FakeUartSerialPort()
        ctx2.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = FakeSdioBusPort()
        val comp2 = Bcm43438Factory().create(ctx2, cfg) as StatefulComponent
        comp2.start()
        comp2.restoreSnapshot(snap)
        assertEquals(comp.state()["bdAddr"], comp2.state()["bdAddr"])
        assertEquals(comp.state()["patchRamLoaded"], comp2.state()["patchRamLoaded"])
        assertEquals(comp.state()["connected"], comp2.state()["connected"])
        comp.close()
        comp2.close()
    }

    @Test
    fun bcm43430WifiSdioEnumBackplaneFirmwareDownloadAndSdpcmSoftApFlow() {
        val ctx = TestContext()
        val uart = FakeUartSerialPort()
        val sdio = FakeSdioBusPort()
        ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = uart
        ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio
        val comp = Bcm43438Factory().create(
            ctx,
            mapOf("enabled" to true, "deviceName" to "FujiEmu Host"),
        ) as StatefulComponent
        comp.start()
        val card = sdio.card!!

        // 1. SDIO Enumeration: CMD0 -> CMD5 -> CMD3 -> CMD7
        assertTrue(card.executeCommand(0, 0).accepted)
        val r4 = card.executeCommand(5, 0x00300000L)
        assertTrue(r4.accepted)
        assertEquals(0xB0FF8000L.toInt(), r4.response[0])
        val r6 = card.executeCommand(3, 0)
        assertEquals(0x0001, (r6.response[0] ushr 16) and 0xffff)
        assertTrue(card.executeCommand(7, 0x00010000L).accepted)

        // 2. Read F0 CIS MANFID (0x02D0:0x43E2) via CMD52
        val manfLo = card.executeCommand(52, cmd52Arg(false, 0, 0x1002)).response[0] and 0xff
        val manfHi = card.executeCommand(52, cmd52Arg(false, 0, 0x1003)).response[0] and 0xff
        val prodLo = card.executeCommand(52, cmd52Arg(false, 0, 0x1004)).response[0] and 0xff
        val prodHi = card.executeCommand(52, cmd52Arg(false, 0, 0x1005)).response[0] and 0xff
        assertEquals(0x02D0, (manfHi shl 8) or manfLo)
        assertEquals(0x43E2, (prodHi shl 8) or prodLo)

        // Enable F1 & F2 + IntEn
        card.executeCommand(52, cmd52Arg(true, 0, 0x02, 0x06))
        assertEquals(0x06, card.executeCommand(52, cmd52Arg(false, 0, 0x03)).response[0] and 0xff)
        card.executeCommand(52, cmd52Arg(true, 0, 0x04, 0x07))

        // 3. F1 Backplane: ChipCommon ChipID = 0xA9A6 (BCM43430A1)
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000E, 0x10)) // Request HT clock
        assertEquals(0xC0, card.executeCommand(52, cmd52Arg(false, 1, 0x1000E)).response[0] and 0xC0)
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000C, 0x18))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000B, 0x00))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000A, 0x00))
        val chipIdBytes = card.executeCommand(53, cmd53Arg(false, 1, false, true, 0x0000, 4)).dataOut!!
        val chipId = ByteBuffer.wrap(chipIdBytes).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0xA9A6, chipId and 0xffff)

        // 4. Download firmware to SOCRAM 0x00000000 & release ARM CM3 reset at 0x18103800
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000C, 0x00))
        val fakeFw = ByteArray(128) { i -> (i xor 0x5A).toByte() }
        card.writeTransferData(53, cmd53Arg(true, 1, false, true, 0x0000, fakeFw.size), fakeFw)
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000C, 0x18))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000B, 0x10))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000A, 0x38))
        card.writeTransferData(53, cmd53Arg(true, 1, false, true, 0x0000, 4), byteArrayOf(0, 0, 0, 0))
        assertEquals(true, comp.state()["firmwareLoaded"])

        // 5. F2 SDPCM CDC: WLC_SET_AP(1) -> WLC_SET_SSID("FujiEmu Host") -> WLC_UP -> WLC_GET_VAR("ver")
        val apSet = buildSdpcmCdc(Bcm43430WifiController.WLC_SET_AP, 1, true, byteArrayOf(1, 0, 0, 0), seq = 0)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, apSet.size), apSet)
        assertTrue(sdio.cardIrq)
        card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64)) // drain reply

        val ssidBytes = "FujiEmu Host".toByteArray(Charsets.UTF_8)
        val ssidStruct = ByteArray(36)
        ssidStruct[0] = ssidBytes.size.toByte()
        System.arraycopy(ssidBytes, 0, ssidStruct, 4, ssidBytes.size)
        val ssidSet = buildSdpcmCdc(Bcm43430WifiController.WLC_SET_SSID, 2, true, ssidStruct, seq = 1)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, ssidSet.size), ssidSet)
        card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64))

        val upSet = buildSdpcmCdc(Bcm43430WifiController.WLC_UP, 3, true, ByteArray(0), seq = 2)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, upSet.size), upSet)
        // Drain pending AP WLC_E_SET_SSID / WLC_E_LINK events + WLC_UP reply
        while (card.isCardInterruptAsserted()) {
            card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256))
        }

        assertEquals("AP", comp.state()["mode"])
        assertEquals("FujiEmu Host", comp.state()["ssid"])

        val verReq = buildSdpcmCdc(Bcm43430WifiController.WLC_GET_VAR, 4, false, "ver\u0000".toByteArray(Charsets.US_ASCII), seq = 3)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, verReq.size), verReq)
        val verResp = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64)).dataOut!!
        assertTrue(String(verResp, 28, verResp.size - 28, Charsets.US_ASCII).contains("BCM43430A1"))

        // 6. Wi-Fi 802.11 EScan & Legacy Scan discovers "FujiEmu Host" + "FujiEmu-AP-2.4G"
        val escanParams = ByteArray(12)
        escanParams[0] = 1 // version
        escanParams[4] = 1 // action = START
        escanParams[6] = 0x2A // sync_id = 0x002A
        val escanReq = buildSdpcmCdc(
            Bcm43430WifiController.WLC_SET_VAR, 5, true,
            "escan\u0000".toByteArray(Charsets.US_ASCII) + escanParams, seq = 4,
        )
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, escanReq.size), escanReq)
        // Drain CDC reply + EScan events (2 APs + terminator + SCAN_COMPLETE)
        val escanFrames = mutableListOf<ByteArray>()
        repeat(6) {
            val f = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 512)).dataOut!!
            if (f.any { it.toInt() != 0 }) escanFrames += f
        }
        val combinedAscii = escanFrames.joinToString("\n") { String(it, Charsets.ISO_8859_1) }
        assertTrue(combinedAscii.contains("FujiEmu Host"), "Expected EScan to discover 'FujiEmu Host'")
        assertTrue(combinedAscii.contains("FujiEmu-AP-2.4G"), "Expected EScan to discover 'FujiEmu-AP-2.4G'")

        // Legacy WLC_SCAN_RESULTS also returns both discovered networks
        val legacyScanReq = buildSdpcmCdc(Bcm43430WifiController.WLC_SCAN_RESULTS, 6, false, ByteArray(0), seq = 5)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, legacyScanReq.size), legacyScanReq)
        val legacyScanResp = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 512)).dataOut!!
        assertTrue(String(legacyScanResp, Charsets.ISO_8859_1).contains("FujiEmu Host"))

        // STA association to discovered "FujiEmu Host" AP
        val apOff = buildSdpcmCdc(Bcm43430WifiController.WLC_SET_AP, 7, true, byteArrayOf(0, 0, 0, 0), seq = 6)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, apOff.size), apOff)
        card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64))
        val staJoin = buildSdpcmCdc(Bcm43430WifiController.WLC_SET_SSID, 8, true, ssidStruct, seq = 7)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, staJoin.size), staJoin)
        assertEquals("STA", comp.state()["mode"])
        assertEquals("FujiEmu Host", comp.state()["ssid"])
        assertEquals(1, comp.state()["connectedClients"])

        comp.close()
    }

    @Test
    fun bcm43430AxiEromSocramBandChanspecsEscanAbortAndSlidingCreditWindow() {
        val ctx = TestContext()
        val uart = FakeUartSerialPort()
        val sdio = FakeSdioBusPort()
        ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = uart
        ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio
        val comp = Bcm43438Factory().create(
            ctx,
            mapOf("enabled" to true, "deviceName" to "FujiEmu Host"),
        ) as StatefulComponent
        comp.start()
        val card = sdio.card!!

        // 1. SLEEPCSR KSO (bit 0) asserts DEVON (bit 1)
        card.executeCommand(52, cmd52Arg(true, 1, Bcm43430WifiController.SBSDIO_FUNC1_SLEEPCSR, 0x01))
        val sleepCsr = card.executeCommand(52, cmd52Arg(false, 1, Bcm43430WifiController.SBSDIO_FUNC1_SLEEPCSR)).response[0] and 0xff
        assertEquals(0x03, sleepCsr)

        // 2. AXI EROM at 0x1810F000 advertises nsp=1 (bits 13:9 = 1) for all 5 cores so Broadcom ai_scan finds all cores
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000C, 0x18))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000B, 0x10))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000A, 0xF0))
        val eromBytes = card.executeCommand(53, cmd53Arg(false, 1, false, true, 0x0000, 84)).dataOut!!
        val eromBuf = ByteBuffer.wrap(eromBytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(5) { coreIdx ->
            val ciA = eromBuf.getInt(coreIdx * 16)
            val ciB = eromBuf.getInt(coreIdx * 16 + 4)
            assertEquals(0x01, ciA and 0x0f)
            assertEquals(1, (ciB ushr 9) and 0x1f, "Expected nsp=1 in EROM CIB for core #$coreIdx")
            assertEquals(1, (ciB ushr 19) and 0x1f, "Expected nsw=1 in EROM CIB for core #$coreIdx")
        }

        // 3. SOCRAM core (0x18004000) reports 8 banks * 64 KiB = 512 KiB (SRCI_SRNB=8 at +0x00, BANKINFO=7 at +0x40)
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000C, 0x18))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000B, 0x00))
        card.executeCommand(52, cmd52Arg(true, 1, 0x1000A, 0x40))
        val coreInfo = ByteBuffer.wrap(
            card.executeCommand(53, cmd53Arg(false, 1, false, true, 0x0000, 4)).dataOut!!,
        ).order(ByteOrder.LITTLE_ENDIAN).int
        val bankInfo = ByteBuffer.wrap(
            card.executeCommand(53, cmd53Arg(false, 1, false, true, 0x0040, 4)).dataOut!!,
        ).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(8, (coreInfo ushr 4) and 0x0f)
        assertEquals(7, bankInfo and 0x7f)

        // 4. Country JP -> WLC_GET_BANDLIST (2G=2) + chanspecs (13 channels: 0x1001..0x100d) + >40 IOCTLs advancing tx_max
        val setJp = buildSdpcmCdc(
            Bcm43430WifiController.WLC_SET_VAR, 1, true,
            "country\u0000JP\u0000\u0000".toByteArray(Charsets.US_ASCII), seq = 0,
        )
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, setJp.size), setJp)
        card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64))

        val bandListReq = buildSdpcmCdc(Bcm43430WifiController.WLC_GET_BANDLIST, 2, false, ByteArray(12), seq = 1)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, bandListReq.size), bandListReq)
        val bandListResp = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64)).dataOut!!
        val bandBuf = ByteBuffer.wrap(bandListResp.copyOfRange(28, 40)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, bandBuf.getInt(0))
        assertEquals(2, bandBuf.getInt(4))

        val chanspecsReq = buildSdpcmCdc(
            Bcm43430WifiController.WLC_GET_VAR, 3, false,
            "chanspecs\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64), seq = 2,
        )
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, chanspecsReq.size), chanspecsReq)
        val chanspecsResp = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256)).dataOut!!
        val csBuf = ByteBuffer.wrap(chanspecsResp.copyOfRange(28, 88)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(13, csBuf.getInt(0))
        assertEquals(0x1001, csBuf.getInt(4))
        assertEquals(0x100d, csBuf.getInt(52))

        // Issue 45 consecutive per_chan_info GET_VARs (exceeding initial 32-slot window) and verify frame[9] advances
        for (i in 0 until 45) {
            val txSeq = (3 + i) and 0xff
            val req = buildSdpcmCdc(
                Bcm43430WifiController.WLC_GET_VAR, 10 + i, false,
                "per_chan_info\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x01, 0x10, 0x00, 0x00),
                seq = txSeq,
            )
            card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, req.size), req)
            val rx = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 64)).dataOut!!
            val expectedWindow = ((txSeq + 1 + 32) and 0xff)
            assertEquals(expectedWindow, rx[9].toInt() and 0xff, "Expected sliding SDPCM tx_max window at iter $i")
        }

        // 5. EScan action=0 (ABORT) emits WLC_E_ESCAN_RESULT + WLC_E_SCAN_COMPLETE with WLC_E_STATUS_ABORT (4)
        val abortParams = ByteArray(12)
        abortParams[0] = 1
        abortParams[4] = 0 // WL_SCAN_ACTION_ABORT
        abortParams[6] = 0x55
        val abortReq = buildSdpcmCdc(
            Bcm43430WifiController.WLC_SET_VAR, 99, true,
            "escan\u0000".toByteArray(Charsets.US_ASCII) + abortParams, seq = 48,
        )
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, abortReq.size), abortReq)
        val abortEvt1 = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256)).dataOut!!
        val abortEvt2 = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256)).dataOut!!
        val abortCdcReply = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 128)).dataOut!!
        assertTrue(abortCdcReply.isNotEmpty())
        // SDPCM(12) + BDC(4) + Eth+BCM_ETH(24) -> wl_event_msg_t: event_type at +28 (offset 44), status at +32 (offset 48)
        assertEquals(Bcm43430WifiController.WLC_E_ESCAN_RESULT, ByteBuffer.wrap(abortEvt1, 16 + 28, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(Bcm43430WifiController.WLC_E_STATUS_ABORT, ByteBuffer.wrap(abortEvt1, 16 + 32, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(Bcm43430WifiController.WLC_E_SCAN_COMPLETE, ByteBuffer.wrap(abortEvt2, 16 + 28, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(Bcm43430WifiController.WLC_E_STATUS_ABORT, ByteBuffer.wrap(abortEvt2, 16 + 32, 4).order(ByteOrder.BIG_ENDIAN).int)

        // 6. 63-byte SDPCM frame padded inside a 64-byte F2 CMD53 block must not throw IndexOutOfBounds at offset 63
        val oddLenIovarPayload = ByteArray(35) // 12 (SDPCM) + 16 (CDC) + 35 = 63 bytes total
        val verPrefix = "ver\u0000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(verPrefix, 0, oddLenIovarPayload, 0, verPrefix.size)
        val unpadded63 = buildSdpcmCdc(Bcm43430WifiController.WLC_GET_VAR, 100, false, oddLenIovarPayload, seq = 49)
        assertEquals(63, unpadded63.size)
        val padded64 = unpadded63.copyOf(64) // 1 trailing zero byte at index 63
        val write64Res = card.writeTransferData(53, cmd53Arg(true, 2, true, false, 0, 1), padded64)
        assertTrue(write64Res.accepted)
        val verReply = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 128)).dataOut!!
        assertTrue(verReply.isNotEmpty())

        // 7. STA "join" IOVAR associates with matching neighbor SSID and emits join/link events
        val joinArg = ByteArray(4 + 32 + 20)
        val targetSsidBytes = "FujiEmu Host".toByteArray(Charsets.UTF_8)
        ByteBuffer.wrap(joinArg).order(ByteOrder.LITTLE_ENDIAN).putInt(0, targetSsidBytes.size)
        System.arraycopy(targetSsidBytes, 0, joinArg, 4, targetSsidBytes.size)
        val joinReq = buildSdpcmCdc(
            Bcm43430WifiController.WLC_SET_VAR, 101, true,
            "join\u0000".toByteArray(Charsets.US_ASCII) + joinArg, seq = 50,
        )
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, joinReq.size), joinReq)
        val wifiAfterJoin = comp.state()["wifi"] as Map<*, *>
        assertEquals("FujiEmu Host", wifiAfterJoin["ssid"])
        assertEquals("02:46:4A:48:00:01", wifiAfterJoin["associatedBssid"])

        comp.close()
    }

    private fun buildSdpcmBdcData(ethFrame: ByteArray, seq: Int = 0): ByteArray {
        val bdcLen = 4 + ethFrame.size
        val totalLen = 12 + bdcLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(totalLen.toShort())
        buf.putShort((totalLen.inv() and 0xffff).toShort())
        buf.put(seq.toByte())
        buf.put(2) // channel 2 = SDPCM_DATA_CHANNEL
        buf.put(0)
        buf.put(12)
        buf.putInt(0)
        buf.put(0x20) // BDC v2
        buf.put(0)
        buf.put(0)
        buf.put(0)
        buf.put(ethFrame)
        return buf.array()
    }

    @Test
    fun bcm43430UnassociatedBssidDisassocAssocInfoAndWpa2FourWayEapolFlow() {
        val ctx = TestContext()
        val uart = FakeUartSerialPort()
        val sdio = FakeSdioBusPort()
        ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = uart
        ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio
        val comp = Bcm43438Factory().create(
            ctx,
            mapOf("enabled" to true, "deviceName" to "FujiEmu Host", "wifiMacAddr" to "02:46:4A:10:00:00"),
        ) as StatefulComponent
        comp.start()
        val card = sdio.card!!
        card.executeCommand(52, cmd52Arg(true, 0, 0x02, 0x06))
        card.executeCommand(52, cmd52Arg(true, 0, 0x04, 0x07))

        var seq = 0
        fun sendCdc(cmd: Int, isSet: Boolean, payload: ByteArray): ByteArray {
            val frame = buildSdpcmCdc(cmd, 200 + seq, isSet, payload, seq = seq and 0xff)
            seq++
            card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, frame.size), frame)
            return card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 512)).dataOut!!
        }

        // 1. Unassociated STA WLC_GET_BSSID (23) MUST return 00:00:00:00:00:00 so WWD does not enter DISASSOCIATING (0x20)
        sendCdc(Bcm43430WifiController.WLC_UP, true, ByteArray(0))
        val unassocBssidReply = sendCdc(Bcm43430WifiController.WLC_GET_BSSID, false, ByteArray(6))
        val unassocBssid = unassocBssidReply.copyOfRange(28, 34)
        assertTrue(unassocBssid.all { it.toInt() == 0 }, "Expected all-zero BSSID when STA is unassociated")

        // 2. WLC_DISASSOC (52) always emits WLC_E_DISASSOC_IND + WLC_E_DISASSOC + WLC_E_LINK after CDC reply
        sendCdc(Bcm43430WifiController.WLC_DISASSOC, true, ByteArray(12))
        val disassocEvents = mutableListOf<Int>()
        while (card.isCardInterruptAsserted()) {
            val rx = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256)).dataOut!!
            if ((rx[5].toInt() and 0x0f) == Bcm43430WifiController.SDPCM_EVENT_CHANNEL) {
                disassocEvents += ByteBuffer.wrap(rx, 16 + 28, 4).order(ByteOrder.BIG_ENDIAN).int
            }
        }
        assertEquals(
            listOf(
                Bcm43430WifiController.WLC_E_DISASSOC_IND,
                Bcm43430WifiController.WLC_E_DISASSOC,
                Bcm43430WifiController.WLC_E_LINK,
            ),
            disassocEvents,
        )

        // 3. Configure WPA2-PSK (RSN IE + wpa_auth=0x80 + wsec=4) and join "FujiEmu Host"
        val rsnIe = byteArrayOf(
            0x30, 0x14, 0x01, 0x00,
            0x00, 0x0F, 0xAC.toByte(), 0x04,
            0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x04,
            0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x02,
            0x00, 0x00,
        )
        sendCdc(Bcm43430WifiController.WLC_SET_VAR, true, "wpaie\u0000".toByteArray(Charsets.US_ASCII) + rsnIe)
        sendCdc(Bcm43430WifiController.WLC_SET_VAR, true, "wpa_auth\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0, 0, 0))
        sendCdc(Bcm43430WifiController.WLC_SET_VAR, true, "wsec\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0, 0))

        val joinArg = ByteArray(70)
        val ssidBytes = "FujiEmu Host".toByteArray(Charsets.UTF_8)
        ByteBuffer.wrap(joinArg).order(ByteOrder.LITTLE_ENDIAN).putInt(0, ssidBytes.size)
        System.arraycopy(ssidBytes, 0, joinArg, 4, ssidBytes.size)
        sendCdc(Bcm43430WifiController.WLC_SET_VAR, true, "join\u0000".toByteArray(Charsets.US_ASCII) + joinArg)
        while (card.isCardInterruptAsserted()) {
            card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256))
        }

        // 4. Verify assoc_info + assoc_req_ies + assoc_resp_ies + WLC_GET_BSS_INFO
        val assocInfoReply = sendCdc(
            Bcm43430WifiController.WLC_GET_VAR, false,
            "assoc_info\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64),
        )
        val assocInfoBuf = ByteBuffer.wrap(assocInfoReply.copyOfRange(28, 40)).order(ByteOrder.LITTLE_ENDIAN)
        val reqLen = assocInfoBuf.getInt(0)
        val respLen = assocInfoBuf.getInt(4)
        val flags = assocInfoBuf.getInt(8)
        assertTrue(reqLen > 4 + rsnIe.size, "Expected assoc_info.req_len to include SSID + Rates + RSN IE (got $reqLen)")
        assertTrue(respLen > 6, "Expected assoc_info.resp_len > 6")
        assertEquals(0, flags)

        val assocReqReply = sendCdc(
            Bcm43430WifiController.WLC_GET_VAR, false,
            "assoc_req_ies\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(128),
        )
        val reqIes = assocReqReply.copyOfRange(28, 28 + (reqLen - 4))
        assertTrue(
            reqIes.takeLast(rsnIe.size).toByteArray().contentEquals(rsnIe),
            "Expected assoc_req_ies to contain exact configured RSN IE",
        )

        val bssInfoReply = sendCdc(Bcm43430WifiController.WLC_GET_BSS_INFO, false, ByteArray(256))
        assertTrue(bssInfoReply.size > 152)

        // 5. WLC_SET_PM (86) triggers EAPOL-Key Msg 1/4 after WPA_ASSOCIATED
        sendCdc(Bcm43430WifiController.WLC_SET_PM, true, byteArrayOf(0, 0, 0, 0))
        val msg1Sdpcm = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 256)).dataOut!!
        assertEquals(Bcm43430WifiController.SDPCM_DATA_CHANNEL, msg1Sdpcm[5].toInt() and 0x0f)
        val msg1Eth = msg1Sdpcm.copyOfRange(16, u16LeTest(msg1Sdpcm, 0))
        assertEquals(0x888E, ((msg1Eth[12].toInt() and 0xff) shl 8) or (msg1Eth[13].toInt() and 0xff))
        assertEquals(0x008A, ((msg1Eth[19].toInt() and 0xff) shl 8) or (msg1Eth[20].toInt() and 0xff))
        val apMac = msg1Eth.copyOfRange(6, 12)
        val staMac = msg1Eth.copyOfRange(0, 6)
        val aNonce = msg1Eth.copyOfRange(31, 63)

        // 6. Send EAPOL-Key Msg 2/4 signed with passphrase "12345678" -> receive Msg 3/4 -> send Msg 4/4
        val sNonce = ByteArray(32) { i -> (0x33 + i).toByte() }
        val pmk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(javax.crypto.spec.PBEKeySpec("12345678".toCharArray(), ssidBytes, 4096, 256)).encoded
        val ptkData = ByteArray(76)
        // apMac=02:46:4A:48:00:01 > staMac=02:46:4A:10:00:00
        System.arraycopy(staMac, 0, ptkData, 0, 6)
        System.arraycopy(apMac, 0, ptkData, 6, 6)
        // sNonce[0]=0x33 < aNonce[0]=0xA2
        System.arraycopy(sNonce, 0, ptkData, 12, 32)
        System.arraycopy(aNonce, 0, ptkData, 44, 32)
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply {
            init(javax.crypto.spec.SecretKeySpec(pmk, "HmacSHA1"))
        }
        val label = "Pairwise key expansion\u0000".toByteArray(Charsets.US_ASCII)
        val ptkBlock0 = mac.doFinal(label + ptkData + byteArrayOf(0))
        val kck = ptkBlock0.copyOfRange(0, 16)

        val msg2BodyLen = 95 + rsnIe.size
        val msg2Eth = ByteArray(14 + 4 + msg2BodyLen)
        System.arraycopy(apMac, 0, msg2Eth, 0, 6)
        System.arraycopy(staMac, 0, msg2Eth, 6, 6)
        msg2Eth[12] = 0x88.toByte(); msg2Eth[13] = 0x8E.toByte()
        msg2Eth[14] = 1; msg2Eth[15] = 3
        msg2Eth[16] = ((msg2BodyLen ushr 8) and 0xff).toByte(); msg2Eth[17] = (msg2BodyLen and 0xff).toByte()
        msg2Eth[18] = 2
        msg2Eth[19] = 0x01; msg2Eth[20] = 0x0A // KeyMIC | Pairwise | HMAC-SHA1-128
        msg2Eth[30] = 1 // replayCounter = 1
        System.arraycopy(sNonce, 0, msg2Eth, 31, 32)
        msg2Eth[111] = 0; msg2Eth[112] = rsnIe.size.toByte()
        System.arraycopy(rsnIe, 0, msg2Eth, 113, rsnIe.size)
        val hmacKck = javax.crypto.Mac.getInstance("HmacSHA1").apply {
            init(javax.crypto.spec.SecretKeySpec(kck, "HmacSHA1"))
        }
        val msg2Mic = hmacKck.doFinal(msg2Eth.copyOfRange(14, msg2Eth.size)).copyOfRange(0, 16)
        System.arraycopy(msg2Mic, 0, msg2Eth, 95, 16)

        val msg2Frame = buildSdpcmBdcData(msg2Eth, seq = (seq++) and 0xff)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, msg2Frame.size), msg2Frame)

        val msg3Sdpcm = card.executeCommand(53, cmd53Arg(false, 2, false, false, 0, 512)).dataOut!!
        val msg3Eth = msg3Sdpcm.copyOfRange(16, u16LeTest(msg3Sdpcm, 0))
        assertEquals(0x13CA, ((msg3Eth[19].toInt() and 0xff) shl 8) or (msg3Eth[20].toInt() and 0xff))

        val msg4Eth = ByteArray(14 + 4 + 95)
        System.arraycopy(apMac, 0, msg4Eth, 0, 6)
        System.arraycopy(staMac, 0, msg4Eth, 6, 6)
        msg4Eth[12] = 0x88.toByte(); msg4Eth[13] = 0x8E.toByte()
        msg4Eth[14] = 1; msg4Eth[15] = 3
        msg4Eth[17] = 95
        msg4Eth[18] = 2
        msg4Eth[19] = 0x03; msg4Eth[20] = 0x0A // Secure | KeyMIC | Pairwise | HMAC-SHA1-128
        msg4Eth[30] = 2
        val msg4Mic = hmacKck.doFinal(msg4Eth.copyOfRange(14, msg4Eth.size)).copyOfRange(0, 16)
        System.arraycopy(msg4Mic, 0, msg4Eth, 95, 16)
        val msg4Frame = buildSdpcmBdcData(msg4Eth, seq = (seq++) and 0xff)
        card.writeTransferData(53, cmd53Arg(true, 2, false, false, 0, msg4Frame.size), msg4Frame)

        val wifiState = comp.state()["wifi"] as Map<*, *>
        assertEquals(true, wifiState["wpa2FourWayComplete"])
        assertEquals(1L, (wifiState["wpa2HandshakeCount"] as Number).toLong())
        comp.close()
    }

    private fun u16LeTest(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8)

    @Test
    fun bcm43438BleAndClassicScanDiscoversFujiEmuHostNeighbor() {
        val ctx = TestContext()
        val uart = FakeUartSerialPort()
        val sdio = FakeSdioBusPort()
        ctx.claims[Bcm43438Factory.DEFAULT_UART_PORT] = uart
        ctx.claims[Bcm43438Factory.DEFAULT_SDIO_PORT] = sdio
        val comp = Bcm43438Factory().create(
            ctx,
            mapOf("enabled" to true, "deviceName" to "FujiEmu Host"),
        ) as StatefulComponent
        comp.start()

        // 1. LE_Set_Scan_Enable(1) -> Command Complete + LE Advertising Report (0x3E/0x02) carrying "FujiEmu Host"
        uart.sendHostBytes(0x01, 0x0c, 0x20, 0x02, 0x01, 0x00)
        val rx = uart.drainRx()
        assertEquals(listOf(0x04, 0x0e, 0x04, 0x01, 0x0c, 0x20, 0x00), rx.take(7))
        val report = rx.drop(7)
        assertEquals(0x04, report[0])
        assertEquals(0x3E, report[1]) // LE Meta Event
        assertEquals(0x02, report[3]) // LE Advertising Report
        val adLen = report[13]
        val adBytes = ByteArray(adLen) { i -> report[14 + i].toByte() }
        assertEquals("FujiEmu Host", Bcm43438BtController.parseAdLocalName(adBytes))

        // 2. Classic HCI_Inquiry (0x0401) -> Command Status + Extended Inquiry Result (0x2F) + Inquiry Complete (0x01)
        uart.sendHostBytes(0x01, 0x01, 0x04, 0x05, 0x33, 0x8b, 0x9e, 0x04, 0x00)
        val inqRx = uart.drainRx()
        assertEquals(0x0F, inqRx[1]) // Command Status
        assertEquals(0x2F, inqRx[8]) // Extended Inquiry Result
        val eirBytes = ByteArray(40) { i -> inqRx[7 + 18 + i].toByte() }
        assertEquals("FujiEmu Host", Bcm43438BtController.parseAdLocalName(eirBytes))

        comp.close()
    }
}
