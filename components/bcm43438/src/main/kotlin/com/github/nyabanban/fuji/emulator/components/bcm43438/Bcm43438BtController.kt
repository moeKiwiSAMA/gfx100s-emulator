package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.UartSerialPeer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal data class VirtualBleNeighbor(
    val name: String,
    val bdAddr: String,
    val rssi: Int = -45,
    val connectable: Boolean = true,
    val classOfDevice: Int = 0x5A020C, // Smartphone
)

/**
 * Datasheet-faithful Broadcom BCM43438A1 Bluetooth 4.1 / BLE H4 HCI Controller + deterministic
 * [VirtualBlePeer] L2CAP/ATT/SMP bridge and RF neighbor scanner.
 *
 * Strictly zero hardcoded camera names: the controller faithfully stores and advertises whatever
 * Local Name, Advertising Data, Scan Response Data, and BD_ADDR the guest Bluetooth stack
 * (`AnWBTStack`) programs over real H4 HCI packets. When the guest initiates BLE scanning
 * (`0x200C`) or Classic Inquiry (`0x0401`), the controller reports nearby RF neighbors from
 * [neighborsProvider] using authentic BT 4.1 HCI events (`0x3E/0x02`, `0x2F`, `0x07`).
 */
internal class Bcm43438BtController(
    initialBdAddr: ByteArray,
    private val rxSink: (ByteArray) -> Unit,
    private val peerNameProvider: () -> String,
    private val neighborsProvider: () -> List<VirtualBleNeighbor> = {
        listOf(VirtualBleNeighbor(name = peerNameProvider(), bdAddr = formatMac6(VIRTUAL_PEER_BD_ADDR)))
    },
    private val beforeBusActivity: () -> Unit = {},
) : UartSerialPeer {

    companion object {
        const val H4_CMD = 0x01
        const val H4_ACL = 0x02
        const val H4_SCO = 0x03
        const val H4_EVT = 0x04

        const val L2CAP_CID_ATT = 0x0004
        const val L2CAP_CID_SIG = 0x0005
        const val L2CAP_CID_SMP = 0x0006

        const val VIRTUAL_CONN_HANDLE = 0x0040
        val VIRTUAL_PEER_BD_ADDR = byteArrayOf(0x02, 0x46, 0x4A, 0x42, 0x00, 0x01)

        internal fun parseMac6(text: String): ByteArray {
            val parts = text.trim().split(':', '-')
            require(parts.size == 6 && parts.all { it.length == 2 }) { "Invalid 6-byte MAC/BD_ADDR: $text" }
            return ByteArray(6) { idx ->
                parts[idx].toIntOrNull(16)?.toByte()
                    ?: throw IllegalArgumentException("Invalid hex byte in MAC/BD_ADDR: $text")
            }
        }

        internal fun formatMac6(bytes: ByteArray): String =
            bytes.joinToString(":") { b -> "%02X".format(b.toInt() and 0xff) }

        internal fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }

        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            if (clean.isEmpty()) return ByteArray(0)
            require(clean.length % 2 == 0) { "Odd hex length" }
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /** Parse Complete (0x09) or Shortened (0x08) Local Name from standard BT Core Spec AD bytes. */
        internal fun parseAdLocalName(ad: ByteArray): String? {
            var pos = 0
            var shortened: String? = null
            while (pos < ad.size) {
                val len = ad[pos].toInt() and 0xff
                if (len == 0) break
                if (pos + 1 + len > ad.size) break
                val type = ad[pos + 1].toInt() and 0xff
                val dataLen = len - 1
                if (dataLen > 0) {
                    when (type) {
                        0x09 -> return String(ad, pos + 2, dataLen, Charsets.UTF_8).trimEnd('\u0000')
                        0x08 -> if (shortened == null) {
                            shortened = String(ad, pos + 2, dataLen, Charsets.UTF_8).trimEnd('\u0000')
                        }
                    }
                }
                pos += 1 + len
            }
            return shortened
        }

        internal fun parseAdServiceUuids(ad: ByteArray): List<String> {
            val uuids = mutableListOf<String>()
            var pos = 0
            while (pos < ad.size) {
                val len = ad[pos].toInt() and 0xff
                if (len == 0) break
                if (pos + 1 + len > ad.size) break
                val type = ad[pos + 1].toInt() and 0xff
                val dataStart = pos + 2
                val dataLen = len - 1
                when (type) {
                    0x02, 0x03 -> { // 16-bit Service UUIDs
                        var i = 0
                        while (i + 2 <= dataLen) {
                            val u = (ad[dataStart + i].toInt() and 0xff) or
                                ((ad[dataStart + i + 1].toInt() and 0xff) shl 8)
                            uuids += "0x%04X".format(u)
                            i += 2
                        }
                    }
                    0x06, 0x07 -> { // 128-bit Service UUIDs (LSB first)
                        var i = 0
                        while (i + 16 <= dataLen) {
                            val rev = ByteArray(16) { k -> ad[dataStart + i + 15 - k] }
                            uuids += bytesToHex(rev)
                            i += 16
                        }
                    }
                }
                pos += 1 + len
            }
            return uuids
        }
    }

    // H4 packet framing state
    private val rxBuf = ByteArrayOutputStream(300)
    private var currentPktType = 0
    private var expectedLen = 0

    // Hardware power / UART / Boot-ROM / PatchRAM state
    var powerOn: Boolean = true
    var uartBaudRate: Int = 115200
        private set
    var uartIbrd: Int = 0
        private set
    var uartFbrd: Int = 0
        private set
    var minidriverDownloaded: Boolean = false
        private set
    var patchRamLoaded: Boolean = false
        private set
    var patchRamWriteCount: Long = 0L
        private set
    var patchRamBytesWritten: Long = 0L
        private set
    var patchRamLaunchAddr: Long = 0L
        private set
    private val patchRamDigest = MessageDigest.getInstance("SHA-256")
    var patchRamSha256: String = ""
        private set

    // BT 4.1 Controller state
    private val initialBdAddrBytes: ByteArray = initialBdAddr.copyOf(6)
    private val bdAddrBytes: ByteArray = initialBdAddr.copyOf(6)
    private val randomAddrBytes: ByteArray = ByteArray(6)
    var randomAddrSet: Boolean = false
        private set
    var localName: String = ""
        private set
    var eventMask: Long = 0x00001FFFFFFFFFFFL
        private set
    var leEventMask: Long = 0x000000000000001FL
        private set
    var leHostSupported: Boolean = false
        private set
    var scanEnable: Int = 0
        private set

    // LE Advertising & Scanning state
    var advertising: Boolean = false
        private set
    var advIntervalMin: Int = 0x0800
        private set
    var advIntervalMax: Int = 0x0800
        private set
    var advType: Int = 0x00
        private set
    var advOwnAddrType: Int = 0x00
        private set
    var advChannelMap: Int = 0x07
        private set
    var advData: ByteArray = ByteArray(0)
        private set
    var scanRespData: ByteArray = ByteArray(0)
        private set
    var advertisedName: String = ""
        private set
    var scanResponseName: String = ""
        private set
    var scanning: Boolean = false
        private set
    var scanReportCount: Long = 0L
        private set
    var inquiryReportCount: Long = 0L
        private set
    private val whiteList = mutableListOf<String>()

    // Deterministic PRNG state for HCI_LE_Rand
    private var randState: Long = 0x43438A1_01020304L

    // Telemetry counters
    var hciCmdCount: Long = 0L
        private set
    var hciAclTxCount: Long = 0L
        private set
    var hciAclRxCount: Long = 0L
        private set
    var lastOpcode: Int = 0
        private set
    private val recentOpcodes = ArrayDeque<String>(32)

    // Virtual BLE Peer (L2CAP/ATT/SMP) state
    var connected: Boolean = false
        private set
    var connectionHandle: Int = 0
        private set
    var peerBdAddr: String = formatMac6(VIRTUAL_PEER_BD_ADDR)
        private set
    var peerReadGapDeviceName: String? = null
        private set
    var peerPaired: Boolean = false
        private set
    var peerTimeSyncCount: Long = 0L
        private set
    var peerAttExchangeCount: Long = 0L
        private set
    var peerMtu: Int = 23
        private set

    fun bdAddrString(): String = formatMac6(bdAddrBytes)
    fun randomAddrString(): String? = if (randomAddrSet) formatMac6(randomAddrBytes) else null

    fun effectiveAdvertisedName(): String =
        advertisedName.ifBlank { scanResponseName.ifBlank { localName } }

    fun setPowerPin(high: Boolean) {
        if (powerOn == high) return
        powerOn = high
        if (!high) {
            hardResetChip()
        }
    }

    private fun hardResetChip() {
        rxBuf.reset()
        currentPktType = 0
        expectedLen = 0
        uartBaudRate = 115200
        minidriverDownloaded = false
        patchRamLoaded = false
        patchRamWriteCount = 0L
        patchRamBytesWritten = 0L
        patchRamLaunchAddr = 0L
        patchRamDigest.reset()
        patchRamSha256 = ""
        advertising = false
        scanning = false
        connected = false
        connectionHandle = 0
        peerPaired = false
        advData = ByteArray(0)
        scanRespData = ByteArray(0)
        advertisedName = ""
        scanResponseName = ""
        whiteList.clear()
    }

    override fun onLineConfigChanged(ibrd: Int, fbrd: Int, lcrH: Long, uartCr: Long) {
        uartIbrd = ibrd
        uartFbrd = fbrd
    }

    override fun onHostTx(byte: Byte) {
        beforeBusActivity()
        if (!powerOn) return
        val v = byte.toInt() and 0xff
        if (rxBuf.size() == 0) {
            when (v) {
                H4_CMD -> {
                    currentPktType = H4_CMD
                    expectedLen = 4
                    rxBuf.write(v)
                }
                H4_ACL -> {
                    currentPktType = H4_ACL
                    expectedLen = 5
                    rxBuf.write(v)
                }
                H4_SCO -> {
                    currentPktType = H4_SCO
                    expectedLen = 4
                    rxBuf.write(v)
                }
                else -> return // Ignore non-H4 framing noise
            }
            return
        }
        rxBuf.write(v)
        when (currentPktType) {
            H4_CMD -> if (rxBuf.size() == 4) {
                expectedLen = 4 + v
            }
            H4_ACL -> if (rxBuf.size() == 5) {
                val arr = rxBuf.toByteArray()
                val aclLen = (arr[3].toInt() and 0xff) or ((arr[4].toInt() and 0xff) shl 8)
                expectedLen = 5 + aclLen
            }
            H4_SCO -> if (rxBuf.size() == 4) {
                expectedLen = 4 + v
            }
        }
        if (rxBuf.size() >= expectedLen && expectedLen >= 4) {
            val pkt = rxBuf.toByteArray()
            val pktType = currentPktType
            rxBuf.reset()
            currentPktType = 0
            expectedLen = 0
            when (pktType) {
                H4_CMD -> handleHciCommand(pkt)
                H4_ACL -> handleHostAclData(pkt)
                H4_SCO -> Unit
            }
        }
    }

    private fun recordOpcode(opcode: Int) {
        lastOpcode = opcode
        val tag = "0x%04X".format(opcode)
        if (recentOpcodes.size >= 32) recentOpcodes.removeFirst()
        recentOpcodes.addLast(tag)
    }

    private fun commandComplete(opcode: Int, status: Int = 0x00, vararg params: Byte): ByteArray {
        val out = ByteArray(7 + params.size)
        out[0] = H4_EVT.toByte()
        out[1] = 0x0E // Command Complete
        out[2] = (4 + params.size).toByte()
        out[3] = 0x01 // Num_HCI_Command_Packets
        out[4] = (opcode and 0xff).toByte()
        out[5] = ((opcode ushr 8) and 0xff).toByte()
        out[6] = (status and 0xff).toByte()
        System.arraycopy(params, 0, out, 7, params.size)
        return out
    }

    private fun commandStatus(opcode: Int, status: Int = 0x00): ByteArray = byteArrayOf(
        H4_EVT.toByte(),
        0x0F, // Command Status
        0x04,
        (status and 0xff).toByte(),
        0x01,
        (opcode and 0xff).toByte(),
        ((opcode ushr 8) and 0xff).toByte(),
    )

    private fun handleHciCommand(pkt: ByteArray) {
        val opLo = pkt[1].toInt() and 0xff
        val opHi = pkt[2].toInt() and 0xff
        val opcode = (opHi shl 8) or opLo
        val paramLen = pkt[3].toInt() and 0xff
        val params = if (paramLen > 0 && pkt.size >= 4 + paramLen) {
            pkt.copyOfRange(4, 4 + paramLen)
        } else {
            ByteArray(0)
        }
        hciCmdCount++
        recordOpcode(opcode)

        when (opcode) {
            // ================= Link Control (OGF 0x01) =================
            0x0401 -> { // HCI_Inquiry -> Command Status + Extended_Inquiry_Result (0x2F) per RF neighbor + Inquiry_Complete (0x01)
                rxSink(commandStatus(opcode, 0x00))
                for (neighbor in neighborsProvider()) {
                    emitExtendedInquiryResult(neighbor)
                }
                rxSink(byteArrayOf(H4_EVT.toByte(), 0x01, 0x01, 0x00)) // Inquiry Complete (status=0)
                return
            }
            0x0402 -> { // HCI_Inquiry_Cancel
                rxSink(commandComplete(opcode))
                return
            }
            0x0406 -> { // HCI_Disconnect
                val handle = if (params.size >= 2) {
                    (params[0].toInt() and 0xff) or ((params[1].toInt() and 0x0f) shl 8)
                } else connectionHandle
                val reason = if (params.size >= 3) params[2].toInt() and 0xff else 0x13
                rxSink(commandStatus(opcode, 0x00))
                if (connected && (handle == connectionHandle || handle == VIRTUAL_CONN_HANDLE)) {
                    connected = false
                    connectionHandle = 0
                    emitDisconnectionComplete(handle, reason)
                }
                return
            }
            0x0419 -> { // HCI_Remote_Name_Request -> Command Status + Remote_Name_Request_Complete (0x07)
                rxSink(commandStatus(opcode, 0x00))
                if (params.size >= 6) {
                    val reqAddr = ByteArray(6) { i -> params[5 - i] }
                    val reqAddrStr = formatMac6(reqAddr)
                    val match = neighborsProvider().firstOrNull { it.bdAddr.equals(reqAddrStr, ignoreCase = true) }
                        ?: neighborsProvider().firstOrNull()
                    val remoteName = match?.name ?: peerNameProvider()
                    val evt = ByteArray(3 + 1 + 6 + 248)
                    evt[0] = H4_EVT.toByte()
                    evt[1] = 0x07 // Remote Name Request Complete
                    evt[2] = 255.toByte()
                    evt[3] = 0x00 // Status OK
                    for (i in 0 until 6) evt[4 + i] = params[i] // LSB-first BD_ADDR
                    val enc = remoteName.toByteArray(Charsets.UTF_8)
                    System.arraycopy(enc, 0, evt, 10, minOf(enc.size, 247))
                    rxSink(evt)
                }
                return
            }

            // ================= Controller & Baseband (OGF 0x03) =================
            0x0C01 -> { // HCI_Set_Event_Mask
                if (params.size >= 8) eventMask = readU64Le(params, 0)
                rxSink(commandComplete(opcode))
                return
            }
            0x0C03 -> { // HCI_Reset
                advertising = false
                scanning = false
                connected = false
                connectionHandle = 0
                advData = ByteArray(0)
                scanRespData = ByteArray(0)
                advertisedName = ""
                scanResponseName = ""
                whiteList.clear()
                rxSink(commandComplete(opcode))
                return
            }
            0x0C13 -> { // HCI_Write_Local_Name (248B NUL-terminated UTF-8)
                val nul = params.indexOf(0)
                val end = if (nul >= 0) nul else params.size
                localName = String(params, 0, end, Charsets.UTF_8)
                rxSink(commandComplete(opcode))
                return
            }
            0x0C14 -> { // HCI_Read_Local_Name (248B NUL-padded)
                val nameBuf = ByteArray(248)
                val encoded = localName.toByteArray(Charsets.UTF_8)
                val copyLen = minOf(encoded.size, 247)
                System.arraycopy(encoded, 0, nameBuf, 0, copyLen)
                rxSink(commandComplete(opcode, 0x00, *nameBuf))
                return
            }
            0x0C19 -> { // HCI_Read_Scan_Enable
                rxSink(commandComplete(opcode, 0x00, (scanEnable and 0xff).toByte()))
                return
            }
            0x0C1A -> { // HCI_Write_Scan_Enable
                if (params.isNotEmpty()) scanEnable = params[0].toInt() and 0xff
                rxSink(commandComplete(opcode))
                return
            }
            0x0C6C -> { // HCI_Read_LE_Host_Support
                rxSink(commandComplete(opcode, 0x00, if (leHostSupported) 1 else 0, 0))
                return
            }
            0x0C6D -> { // HCI_Write_LE_Host_Support
                if (params.isNotEmpty()) leHostSupported = (params[0].toInt() and 0xff) != 0
                rxSink(commandComplete(opcode))
                return
            }

            // ================= Informational Parameters (OGF 0x04) =================
            0x1001 -> { // HCI_Read_Local_Version_Information (BT 4.1, Broadcom 0x000F, BCM43438A1)
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        0x07, 0x00, 0x00, 0x07, 0x0F, 0x00, 0x00, 0x22,
                    ),
                )
                return
            }
            0x1002 -> { // HCI_Read_Local_Supported_Commands (64B bitmask)
                val mask = ByteArray(64) { 0xFF.toByte() }
                rxSink(commandComplete(opcode, 0x00, *mask))
                return
            }
            0x1003 -> { // HCI_Read_Local_Supported_Features (BT 4.1 + LE)
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        0xFF.toByte(), 0xFF.toByte(), 0x8F.toByte(), 0xFE.toByte(),
                        0xDB.toByte(), 0xFF.toByte(), 0x5B, 0x87.toByte(),
                    ),
                )
                return
            }
            0x1004 -> { // HCI_Read_Local_Extended_Features
                val page = if (params.isNotEmpty()) params[0] else 0
                val feat0: Byte = if (page.toInt() == 1 && leHostSupported) 0x02 else 0x01
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        page, 0x02, feat0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    ),
                )
                return
            }
            0x1005 -> { // HCI_Read_Buffer_Size (ACL 1021B x 8, SCO 64B x 4)
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        0xFD.toByte(), 0x03, 0x40, 0x08, 0x00, 0x04, 0x00,
                    ),
                )
                return
            }
            0x1009 -> { // HCI_Read_BD_ADDR (6B LSB-first on wire)
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        bdAddrBytes[5], bdAddrBytes[4], bdAddrBytes[3],
                        bdAddrBytes[2], bdAddrBytes[1], bdAddrBytes[0],
                    ),
                )
                return
            }
            0x1405 -> { // HCI_Read_RSSI (status, handle u16le, rssi i8 = -42 dBm)
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(commandComplete(opcode, 0x00, hLo, hHi, (-42).toByte()))
                return
            }

            // ================= LE Controller Commands (OGF 0x08) =================
            0x2001 -> { // LE_Set_Event_Mask
                if (params.size >= 8) leEventMask = readU64Le(params, 0)
                rxSink(commandComplete(opcode))
                return
            }
            0x2002 -> { // LE_Read_Buffer_Size (LE ACL 251B x 8)
                rxSink(commandComplete(opcode, 0x00, 0xFB.toByte(), 0x00, 0x08))
                return
            }
            0x2003 -> { // LE_Read_Local_Supported_Features (LE Encryption, Conn Param Req, Ext Reject, Slave-init Feat Exch, LE Ping)
                rxSink(commandComplete(opcode, 0x00, 0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                return
            }
            0x2005 -> { // LE_Set_Random_Address (6B LSB-first)
                if (params.size >= 6) {
                    for (i in 0 until 6) randomAddrBytes[5 - i] = params[i]
                    randomAddrSet = true
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x2006 -> { // LE_Set_Advertising_Parameters
                if (params.size >= 15) {
                    advIntervalMin = (params[0].toInt() and 0xff) or ((params[1].toInt() and 0xff) shl 8)
                    advIntervalMax = (params[2].toInt() and 0xff) or ((params[3].toInt() and 0xff) shl 8)
                    advType = params[4].toInt() and 0xff
                    advOwnAddrType = params[5].toInt() and 0xff
                    advChannelMap = params[13].toInt() and 0xff
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x2007 -> { // LE_Read_Advertising_Channel_Tx_Power (+4 dBm)
                rxSink(commandComplete(opcode, 0x00, 0x04))
                return
            }
            0x2008 -> { // LE_Set_Advertising_Data
                if (params.isNotEmpty()) {
                    val adLen = minOf(params[0].toInt() and 0xff, minOf(31, params.size - 1))
                    advData = params.copyOfRange(1, 1 + adLen)
                    advertisedName = parseAdLocalName(advData).orEmpty()
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x2009 -> { // LE_Set_Scan_Response_Data
                if (params.isNotEmpty()) {
                    val srLen = minOf(params[0].toInt() and 0xff, minOf(31, params.size - 1))
                    scanRespData = params.copyOfRange(1, 1 + srLen)
                    scanResponseName = parseAdLocalName(scanRespData).orEmpty()
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x200A -> { // LE_Set_Advertise_Enable
                if (params.isNotEmpty()) {
                    advertising = (params[0].toInt() and 0xff) != 0
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x200B -> { // LE_Set_Scan_Parameters
                rxSink(commandComplete(opcode))
                return
            }
            0x200C -> { // LE_Set_Scan_Enable -> when enabled, emit genuine LE Advertising Reports (0x3E/0x02) for RF neighbors
                if (params.isNotEmpty()) {
                    scanning = (params[0].toInt() and 0xff) != 0
                }
                rxSink(commandComplete(opcode))
                if (scanning) {
                    for (neighbor in neighborsProvider()) {
                        emitLeAdvertisingReport(neighbor)
                    }
                }
                return
            }
            0x200D -> { // LE_Create_Connection -> Command Status + LE Connection Complete (Role=Master 0x00)
                rxSink(commandStatus(opcode, 0x00))
                val targetAddr = if (params.size >= 12) {
                    val b = ByteArray(6) { i -> params[11 - i] }
                    formatMac6(b)
                } else {
                    neighborsProvider().firstOrNull()?.bdAddr ?: peerBdAddr
                }
                connectAsCentral(targetAddr)
                return
            }
            0x200E -> { // LE_Create_Connection_Cancel
                rxSink(commandComplete(opcode))
                return
            }
            0x200F -> { // LE_Read_White_List_Size
                rxSink(commandComplete(opcode, 0x00, 0x10))
                return
            }
            0x2010 -> { // LE_Clear_White_List
                whiteList.clear()
                rxSink(commandComplete(opcode))
                return
            }
            0x2011 -> { // LE_Add_Device_To_White_List
                if (params.size >= 7) {
                    val addr = ByteArray(6) { i -> params[6 - i] }
                    val key = "${params[0].toInt() and 0xff}:${formatMac6(addr)}"
                    if (key !in whiteList && whiteList.size < 16) whiteList += key
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x2012 -> { // LE_Remove_Device_From_White_List
                if (params.size >= 7) {
                    val addr = ByteArray(6) { i -> params[6 - i] }
                    whiteList.remove("${params[0].toInt() and 0xff}:${formatMac6(addr)}")
                }
                rxSink(commandComplete(opcode))
                return
            }
            0x2013 -> { // LE_Connection_Update
                rxSink(commandStatus(opcode, 0x00))
                if (connected && params.size >= 10) {
                    val h = (params[0].toInt() and 0xff) or ((params[1].toInt() and 0x0f) shl 8)
                    val intervalMax = (params[4].toInt() and 0xff) or ((params[5].toInt() and 0xff) shl 8)
                    val latency = (params[6].toInt() and 0xff) or ((params[7].toInt() and 0xff) shl 8)
                    val timeout = (params[8].toInt() and 0xff) or ((params[9].toInt() and 0xff) shl 8)
                    rxSink(
                        byteArrayOf(
                            H4_EVT.toByte(), 0x3E, 0x0A, 0x03, 0x00,
                            (h and 0xff).toByte(), ((h ushr 8) and 0xff).toByte(),
                            (intervalMax and 0xff).toByte(), ((intervalMax ushr 8) and 0xff).toByte(),
                            (latency and 0xff).toByte(), ((latency ushr 8) and 0xff).toByte(),
                            (timeout and 0xff).toByte(), ((timeout ushr 8) and 0xff).toByte(),
                        ),
                    )
                }
                return
            }
            0x2015 -> { // LE_Read_Channel_Map
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        hLo, hHi, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x1F,
                    ),
                )
                return
            }
            0x2016 -> { // LE_Read_Remote_Used_Features
                rxSink(commandStatus(opcode, 0x00))
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(
                    byteArrayOf(
                        H4_EVT.toByte(), 0x3E, 0x0C, 0x04, 0x00,
                        hLo, hHi, 0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    ),
                )
                return
            }
            0x2017 -> { // LE_Encrypt (AES-128 ECB, inputs & outputs MSB-reversed per BT Core Spec)
                val encrypted = if (params.size >= 32) {
                    val keyBe = ByteArray(16) { i -> params[15 - i] }
                    val plainBe = ByteArray(16) { i -> params[31 - i] }
                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBe, "AES"))
                    val outBe = cipher.doFinal(plainBe)
                    ByteArray(16) { i -> outBe[15 - i] }
                } else ByteArray(16)
                rxSink(commandComplete(opcode, 0x00, *encrypted))
                return
            }
            0x2018 -> { // LE_Rand (deterministic 8 bytes)
                randState = randState * 6364136223846793005L + 1442695040888963407L
                val out = ByteArray(8) { i -> ((randState ushr (i * 8)) and 0xffL).toByte() }
                rxSink(commandComplete(opcode, 0x00, *out))
                return
            }
            0x2019 -> { // LE_Start_Encryption -> Command Status + Encryption Change (0x08)
                rxSink(commandStatus(opcode, 0x00))
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(byteArrayOf(H4_EVT.toByte(), 0x08, 0x04, 0x00, hLo, hHi, 0x01))
                return
            }
            0x201A -> { // LE_Long_Term_Key_Request_Reply -> CC + Encryption Change (0x08)
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(commandComplete(opcode, 0x00, hLo, hHi))
                rxSink(byteArrayOf(H4_EVT.toByte(), 0x08, 0x04, 0x00, hLo, hHi, 0x01))
                return
            }
            0x201B -> { // LE_Long_Term_Key_Request_Negative_Reply
                val hLo = if (params.isNotEmpty()) params[0] else (connectionHandle and 0xff).toByte()
                val hHi = if (params.size >= 2) params[1] else ((connectionHandle ushr 8) and 0xff).toByte()
                rxSink(commandComplete(opcode, 0x00, hLo, hHi))
                return
            }
            0x201C -> { // LE_Read_Supported_States
                rxSink(
                    commandComplete(
                        opcode, 0x00,
                        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                        0x3F, 0x00, 0x00, 0x00,
                    ),
                )
                return
            }

            // ================= Broadcom Vendor-Specific (OGF 0x3F / 0xFCxx) =================
            0xFC01 -> { // Broadcom Write_BD_ADDR (6B LSB-first)
                if (params.size >= 6) {
                    for (i in 0 until 6) bdAddrBytes[5 - i] = params[i]
                }
                rxSink(commandComplete(opcode))
                return
            }
            0xFC18 -> { // Broadcom Update_UART_BaudRate ([0, 0, baud_u32le])
                if (params.size >= 6) {
                    val baud = (params[2].toInt() and 0xff) or
                        ((params[3].toInt() and 0xff) shl 8) or
                        ((params[4].toInt() and 0xff) shl 16) or
                        ((params[5].toInt() and 0xff) shl 24)
                    if (baud > 0) uartBaudRate = baud
                }
                rxSink(commandComplete(opcode))
                return
            }
            0xFC2E -> { // Broadcom Download_Minidriver
                minidriverDownloaded = true
                patchRamWriteCount = 0L
                patchRamBytesWritten = 0L
                patchRamDigest.reset()
                patchRamSha256 = ""
                rxSink(commandComplete(opcode))
                return
            }
            0xFC4C -> { // Broadcom Write_RAM ([addr_u32le, data...])
                if (params.size >= 4) {
                    val dataLen = params.size - 4
                    patchRamWriteCount++
                    patchRamBytesWritten += dataLen.toLong()
                    if (dataLen > 0) {
                        patchRamDigest.update(params, 4, dataLen)
                    }
                }
                rxSink(commandComplete(opcode))
                return
            }
            0xFC4E -> { // Broadcom Launch_RAM ([addr_u32le])
                if (params.size >= 4) {
                    patchRamLaunchAddr = ((params[0].toLong() and 0xffL) or
                        ((params[1].toLong() and 0xffL) shl 8) or
                        ((params[2].toLong() and 0xffL) shl 16) or
                        ((params[3].toLong() and 0xffL) shl 24))
                }
                patchRamLoaded = true
                patchRamSha256 = bytesToHex(patchRamDigest.clone().let { (it as MessageDigest).digest() })
                rxSink(commandComplete(opcode))
                return
            }

            else -> {
                // Remaining standard BT 4.1 / Broadcom vendor configuration commands return Command Complete OK
                rxSink(commandComplete(opcode))
            }
        }
    }

    // ================= RF Neighbor Discovery Events (LE Adv Report + Classic EIR) =================

    private fun buildNeighborAdPayload(neighbor: VirtualBleNeighbor): ByteArray {
        val nameBytes = neighbor.name.toByteArray(Charsets.UTF_8).let { if (it.size > 26) it.copyOf(26) else it }
        // AD Flags (02 01 06) + AD Complete Local Name (len, 0x09, nameBytes)
        val out = ByteArray(3 + 2 + nameBytes.size)
        out[0] = 0x02
        out[1] = 0x01
        out[2] = 0x06 // LE General Discoverable | BR/EDR Not Supported
        out[3] = (1 + nameBytes.size).toByte()
        out[4] = 0x09 // Complete Local Name
        System.arraycopy(nameBytes, 0, out, 5, nameBytes.size)
        return out
    }

    private fun emitLeAdvertisingReport(neighbor: VirtualBleNeighbor) {
        val mac = parseMac6(neighbor.bdAddr)
        val ad = buildNeighborAdPayload(neighbor)
        // HCI Event 0x3E (LE Meta), Subevent 0x02 (LE Advertising Report), Num_Reports=1
        // Event_Type (1), Addr_Type (1), Addr (6 LSB-first), Data_Len (1), Data (ad.size), RSSI (1)
        val paramLen = 2 + 1 + 1 + 6 + 1 + ad.size + 1
        val evt = ByteArray(3 + paramLen)
        evt[0] = H4_EVT.toByte()
        evt[1] = 0x3E
        evt[2] = paramLen.toByte()
        evt[3] = 0x02 // LE Advertising Report
        evt[4] = 0x01 // Num_Reports = 1
        evt[5] = if (neighbor.connectable) 0x00 else 0x03 // ADV_IND or ADV_NONCONN_IND
        evt[6] = 0x00 // Public Address
        for (i in 0 until 6) evt[7 + i] = mac[5 - i]
        evt[13] = ad.size.toByte()
        System.arraycopy(ad, 0, evt, 14, ad.size)
        evt[14 + ad.size] = neighbor.rssi.coerceIn(-127, 20).toByte()
        scanReportCount++
        rxSink(evt)
    }

    private fun emitExtendedInquiryResult(neighbor: VirtualBleNeighbor) {
        val mac = parseMac6(neighbor.bdAddr)
        val nameBytes = neighbor.name.toByteArray(Charsets.UTF_8).let { if (it.size > 200) it.copyOf(200) else it }
        // HCI Event 0x2F (Extended Inquiry Result): paramLen = 255
        // Num_Responses(1)=1, BD_ADDR(6), Page_Scan_Rep_Mode(1), Reserved(1), CoD(3), Clock_Offset(2), RSSI(1), EIR(240)
        val evt = ByteArray(3 + 255)
        evt[0] = H4_EVT.toByte()
        evt[1] = 0x2F
        evt[2] = 255.toByte()
        evt[3] = 0x01
        for (i in 0 until 6) evt[4 + i] = mac[5 - i]
        evt[10] = 0x01 // R1
        evt[11] = 0x00
        evt[12] = (neighbor.classOfDevice and 0xff).toByte()
        evt[13] = ((neighbor.classOfDevice ushr 8) and 0xff).toByte()
        evt[14] = ((neighbor.classOfDevice ushr 16) and 0xff).toByte()
        evt[15] = 0x00
        evt[16] = 0x00
        evt[17] = neighbor.rssi.coerceIn(-127, 20).toByte()
        // EIR Complete Local Name (0x09)
        evt[18] = (1 + nameBytes.size).toByte()
        evt[19] = 0x09
        System.arraycopy(nameBytes, 0, evt, 20, nameBytes.size)
        inquiryReportCount++
        rxSink(evt)
    }

    private fun connectAsCentral(targetMac: String) {
        peerBdAddr = formatMac6(parseMac6(targetMac))
        connected = true
        connectionHandle = VIRTUAL_CONN_HANDLE
        scanning = false
        val peerBytes = parseMac6(peerBdAddr)
        rxSink(
            byteArrayOf(
                H4_EVT.toByte(), 0x3E, 0x13, 0x01, 0x00,
                (connectionHandle and 0xff).toByte(), ((connectionHandle ushr 8) and 0xff).toByte(),
                0x00, // Role = Master (camera initiated LE_Create_Connection)
                0x00,
                peerBytes[5], peerBytes[4], peerBytes[3], peerBytes[2], peerBytes[1], peerBytes[0],
                0x18, 0x00, 0x00, 0x00, 0xC8.toByte(), 0x00, 0x00,
            ),
        )
    }

    // ================= Virtual BLE Peer (H4 ACL / L2CAP / ATT / SMP) =================

    fun connectVirtualPeer(peerMac: String? = null): Boolean {
        if (!powerOn) return false
        if (peerMac != null) {
            peerBdAddr = formatMac6(parseMac6(peerMac))
        }
        if (connected) return true
        connected = true
        connectionHandle = VIRTUAL_CONN_HANDLE
        // Per BT 4.1 Core Spec, accepting an LE connection stops connectable advertising
        advertising = false
        val peerBytes = parseMac6(peerBdAddr)
        // HCI LE Meta Event (0x3E), Subevent 0x01 (LE Connection Complete), Role=0x01 (Slave/Camera)
        val evt = byteArrayOf(
            H4_EVT.toByte(),
            0x3E,
            0x13, // param len = 19
            0x01, // subevent LE Connection Complete
            0x00, // status = Success
            (connectionHandle and 0xff).toByte(),
            ((connectionHandle ushr 8) and 0xff).toByte(),
            0x01, // Role = Slave (camera is peripheral)
            0x00, // Peer Address Type = Public
            peerBytes[5], peerBytes[4], peerBytes[3], peerBytes[2], peerBytes[1], peerBytes[0],
            0x18, 0x00, // Conn Interval = 30ms (24 * 1.25ms)
            0x00, 0x00, // Conn Latency = 0
            0xC8.toByte(), 0x00, // Supervision Timeout = 2000ms (200 * 10ms)
            0x00, // Master Clock Accuracy
        )
        rxSink(evt)
        return true
    }

    fun disconnectVirtualPeer(reason: Int = 0x13): Boolean {
        if (!connected) return false
        val handle = connectionHandle
        connected = false
        connectionHandle = 0
        emitDisconnectionComplete(handle, reason)
        return true
    }

    private fun emitDisconnectionComplete(handle: Int, reason: Int) {
        rxSink(
            byteArrayOf(
                H4_EVT.toByte(),
                0x05, // Disconnection Complete
                0x04,
                0x00, // status
                (handle and 0xff).toByte(),
                ((handle ushr 8) and 0xff).toByte(),
                (reason and 0xff).toByte(),
            ),
        )
    }

    /** Send ATT Read By Type Request (0x08) for GAP Device Name (UUID 0x2A00) from VirtualBlePeer to camera. */
    fun requestGapDeviceName(): Boolean {
        if (!connected) return false
        val attReq = byteArrayOf(
            0x08, // ATT_READ_BY_TYPE_REQ
            0x01, 0x00, // Start Handle = 0x0001
            0xFF.toByte(), 0xFF.toByte(), // End Handle = 0xFFFF
            0x00, 0x2A, // UUID 0x2A00 (GAP Device Name)
        )
        sendPeerL2capPacket(L2CAP_CID_ATT, attReq)
        return true
    }

    /** Send deterministic SMP Just-Works Pairing Request (0x01) from VirtualBlePeer to camera. */
    fun initiateVirtualPairing(): Boolean {
        if (!connected) return false
        val smpReq = byteArrayOf(
            0x01, // SMP Pairing Request
            0x03, // IO Cap: NoInputNoOutput
            0x00, // OOB data absent
            0x01, // AuthReq: Bonding
            0x10, // Max Encryption Key Size: 16
            0x01, // Initiator Key Distribution: EncKey
            0x01, // Responder Key Distribution: EncKey
        )
        sendPeerL2capPacket(L2CAP_CID_SMP, smpReq)
        peerPaired = true
        return true
    }

    /** Deliver Current Time Service (0x2A2B) / Fujifilm BLE time-sync write from VirtualBlePeer. */
    fun sendVirtualTimeSync(
        year: Int = 2026,
        month: Int = 11,
        day: Int = 24,
        hour: Int = 12,
        minute: Int = 0,
        second: Int = 0,
        attHandle: Int = 0x0012,
    ): Boolean {
        if (!connected) return false
        val attWrite = byteArrayOf(
            0x52, // ATT_WRITE_CMD
            (attHandle and 0xff).toByte(),
            ((attHandle ushr 8) and 0xff).toByte(),
            (year and 0xff).toByte(),
            ((year ushr 8) and 0xff).toByte(),
            (month and 0xff).toByte(),
            (day and 0xff).toByte(),
            (hour and 0xff).toByte(),
            (minute and 0xff).toByte(),
            (second and 0xff).toByte(),
        )
        sendPeerL2capPacket(L2CAP_CID_ATT, attWrite)
        peerTimeSyncCount++
        return true
    }

    private fun sendPeerL2capPacket(cid: Int, payload: ByteArray) {
        val l2capLen = payload.size
        val aclLen = 4 + l2capLen
        val handleFlags = (connectionHandle and 0x0FFF) or (0x02 shl 12) // PB=10 (first auto-flushable)
        val out = ByteArray(5 + aclLen)
        out[0] = H4_ACL.toByte()
        out[1] = (handleFlags and 0xff).toByte()
        out[2] = ((handleFlags ushr 8) and 0xff).toByte()
        out[3] = (aclLen and 0xff).toByte()
        out[4] = ((aclLen ushr 8) and 0xff).toByte()
        out[5] = (l2capLen and 0xff).toByte()
        out[6] = ((l2capLen ushr 8) and 0xff).toByte()
        out[7] = (cid and 0xff).toByte()
        out[8] = ((cid ushr 8) and 0xff).toByte()
        System.arraycopy(payload, 0, out, 9, payload.size)
        hciAclRxCount++
        rxSink(out)
    }

    private fun handleHostAclData(pkt: ByteArray) {
        if (pkt.size < 5) return
        val handle = (pkt[1].toInt() and 0xff) or ((pkt[2].toInt() and 0x0f) shl 8)
        val aclLen = (pkt[3].toInt() and 0xff) or ((pkt[4].toInt() and 0xff) shl 8)
        hciAclTxCount++

        // Immediately return Number of Completed Packets (0x13) so guest ACL buffer credits stay healthy
        rxSink(
            byteArrayOf(
                H4_EVT.toByte(),
                0x13, // Number of Completed Packets
                0x05,
                0x01, // Num Handles
                (handle and 0xff).toByte(),
                ((handle ushr 8) and 0xff).toByte(),
                0x01, 0x00, // Completed packets = 1
            ),
        )

        if (aclLen < 4 || pkt.size < 9) return
        val l2capLen = (pkt[5].toInt() and 0xff) or ((pkt[6].toInt() and 0xff) shl 8)
        val cid = (pkt[7].toInt() and 0xff) or ((pkt[8].toInt() and 0xff) shl 8)
        if (9 + l2capLen > pkt.size || l2capLen == 0) return
        val payload = pkt.copyOfRange(9, 9 + l2capLen)

        when (cid) {
            L2CAP_CID_ATT -> handleGuestAttPacket(payload)
            L2CAP_CID_SMP -> handleGuestSmpPacket(payload)
        }
    }

    private fun handleGuestAttPacket(att: ByteArray) {
        peerAttExchangeCount++
        val opcode = att[0].toInt() and 0xff
        when (opcode) {
            0x02 -> { // ATT_EXCHANGE_MTU_REQ -> reply with ATT_EXCHANGE_MTU_RSP (MTU=247)
                val reqMtu = if (att.size >= 3) (att[1].toInt() and 0xff) or ((att[2].toInt() and 0xff) shl 8) else 23
                peerMtu = maxOf(23, minOf(reqMtu, 247))
                sendPeerL2capPacket(
                    L2CAP_CID_ATT,
                    byteArrayOf(0x03, (peerMtu and 0xff).toByte(), ((peerMtu ushr 8) and 0xff).toByte()),
                )
            }
            0x03 -> { // ATT_EXCHANGE_MTU_RSP
                if (att.size >= 3) {
                    peerMtu = maxOf(23, (att[1].toInt() and 0xff) or ((att[2].toInt() and 0xff) shl 8))
                }
            }
            0x08 -> { // ATT_READ_BY_TYPE_REQ from camera -> serve peer's GAP 0x2A00 Device Name
                if (att.size >= 7 && (att[5].toInt() and 0xff) == 0x00 && (att[6].toInt() and 0xff) == 0x2A) {
                    val nameBytes = peerNameProvider().toByteArray(Charsets.UTF_8)
                    val resp = ByteArray(4 + nameBytes.size)
                    resp[0] = 0x09 // ATT_READ_BY_TYPE_RSP
                    resp[1] = (2 + nameBytes.size).toByte()
                    resp[2] = 0x03 // Handle 0x0003
                    resp[3] = 0x00
                    System.arraycopy(nameBytes, 0, resp, 4, nameBytes.size)
                    sendPeerL2capPacket(L2CAP_CID_ATT, resp)
                } else {
                    sendPeerL2capPacket(L2CAP_CID_ATT, byteArrayOf(0x01, 0x08, 0x01, 0x00, 0x0A)) // Attribute Not Found
                }
            }
            0x09 -> { // ATT_READ_BY_TYPE_RSP from camera -> extract GAP Device Name
                if (att.size >= 4) {
                    val itemLen = att[1].toInt() and 0xff
                    if (itemLen > 2 && att.size >= 2 + itemLen) {
                        peerReadGapDeviceName = String(att, 4, itemLen - 2, Charsets.UTF_8).trimEnd('\u0000')
                    }
                }
            }
            0x0A -> { // ATT_READ_REQ from camera -> return peer name
                val nameBytes = peerNameProvider().toByteArray(Charsets.UTF_8)
                val resp = ByteArray(1 + nameBytes.size)
                resp[0] = 0x0B // ATT_READ_RSP
                System.arraycopy(nameBytes, 0, resp, 1, nameBytes.size)
                sendPeerL2capPacket(L2CAP_CID_ATT, resp)
            }
            0x12 -> { // ATT_WRITE_REQ -> ATT_WRITE_RSP
                sendPeerL2capPacket(L2CAP_CID_ATT, byteArrayOf(0x13))
            }
            0x1D -> { // ATT_HANDLE_VALUE_IND -> ATT_HANDLE_VALUE_CFM
                sendPeerL2capPacket(L2CAP_CID_ATT, byteArrayOf(0x1E))
            }
        }
    }

    private fun handleGuestSmpPacket(smp: ByteArray) {
        val code = smp[0].toInt() and 0xff
        when (code) {
            0x01 -> { // SMP Pairing Request -> SMP Pairing Response (Just Works)
                sendPeerL2capPacket(L2CAP_CID_SMP, byteArrayOf(0x02, 0x03, 0x00, 0x01, 0x10, 0x01, 0x01))
                peerPaired = true
            }
            0x02 -> { // SMP Pairing Response -> send deterministic Pairing Confirm
                val confirm = ByteArray(17)
                confirm[0] = 0x03
                for (i in 1..16) confirm[i] = (0xA0 + i).toByte()
                sendPeerL2capPacket(L2CAP_CID_SMP, confirm)
            }
            0x03 -> { // SMP Pairing Confirm -> send deterministic Pairing Random
                val random = ByteArray(17)
                random[0] = 0x04
                for (i in 1..16) random[i] = (0x50 + i).toByte()
                sendPeerL2capPacket(L2CAP_CID_SMP, random)
                peerPaired = true
            }
        }
    }

    private fun readU64Le(buf: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((buf[offset + i].toLong() and 0xffL) shl (i * 8))
        }
        return v
    }

    fun state(): Map<String, Any?> = linkedMapOf(
        "powerOn" to powerOn,
        "uartBaudRate" to uartBaudRate,
        "uartIbrd" to uartIbrd,
        "uartFbrd" to uartFbrd,
        "minidriverDownloaded" to minidriverDownloaded,
        "patchRamLoaded" to patchRamLoaded,
        "patchRamWriteCount" to patchRamWriteCount,
        "patchRamBytesWritten" to patchRamBytesWritten,
        "patchRamLaunchAddr" to patchRamLaunchAddr,
        "patchRamSha256" to patchRamSha256,
        "bdAddr" to bdAddrString(),
        "randomAddr" to randomAddrString(),
        "localName" to localName,
        "advertising" to advertising,
        "advIntervalMin" to advIntervalMin,
        "advIntervalMax" to advIntervalMax,
        "advType" to advType,
        "advertisedName" to effectiveAdvertisedName(),
        "advertisedRawName" to advertisedName,
        "scanResponseName" to scanResponseName,
        "advertisingDataHex" to bytesToHex(advData),
        "scanResponseDataHex" to bytesToHex(scanRespData),
        "advertisedServiceUuids" to (parseAdServiceUuids(advData) + parseAdServiceUuids(scanRespData)).distinct(),
        "scanning" to scanning,
        "scanReportCount" to scanReportCount,
        "inquiryReportCount" to inquiryReportCount,
        "discoverableNeighbors" to neighborsProvider().map { n ->
            mapOf("name" to n.name, "bdAddr" to n.bdAddr, "rssi" to n.rssi, "connectable" to n.connectable)
        },
        "connected" to connected,
        "connectionHandle" to connectionHandle,
        "hciCmdCount" to hciCmdCount,
        "hciAclTxCount" to hciAclTxCount,
        "hciAclRxCount" to hciAclRxCount,
        "lastOpcode" to if (lastOpcode != 0) "0x%04X".format(lastOpcode) else null,
        "recentOpcodes" to recentOpcodes.toList(),
        "virtualPeer" to linkedMapOf(
            "name" to peerNameProvider(),
            "bdAddr" to peerBdAddr,
            "connected" to connected,
            "paired" to peerPaired,
            "mtu" to peerMtu,
            "readGapDeviceName" to peerReadGapDeviceName,
            "timeSyncCount" to peerTimeSyncCount,
            "attExchangeCount" to peerAttExchangeCount,
        ),
    )

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "powerOn" to powerOn,
        "uartBaudRate" to uartBaudRate,
        "uartIbrd" to uartIbrd,
        "uartFbrd" to uartFbrd,
        "minidriverDownloaded" to minidriverDownloaded,
        "patchRamLoaded" to patchRamLoaded,
        "patchRamWriteCount" to patchRamWriteCount,
        "patchRamBytesWritten" to patchRamBytesWritten,
        "patchRamLaunchAddr" to patchRamLaunchAddr,
        "patchRamSha256" to patchRamSha256,
        "bdAddr" to bdAddrString(),
        "randomAddrSet" to randomAddrSet,
        "randomAddr" to formatMac6(randomAddrBytes),
        "localName" to localName,
        "eventMask" to eventMask,
        "leEventMask" to leEventMask,
        "leHostSupported" to leHostSupported,
        "advertising" to advertising,
        "advIntervalMin" to advIntervalMin,
        "advIntervalMax" to advIntervalMax,
        "advType" to advType,
        "advOwnAddrType" to advOwnAddrType,
        "advChannelMap" to advChannelMap,
        "advDataBase64" to Base64.getEncoder().encodeToString(advData),
        "scanRespDataBase64" to Base64.getEncoder().encodeToString(scanRespData),
        "scanning" to scanning,
        "whiteList" to whiteList.toList(),
        "randState" to randState,
        "hciCmdCount" to hciCmdCount,
        "hciAclTxCount" to hciAclTxCount,
        "hciAclRxCount" to hciAclRxCount,
        "lastOpcode" to lastOpcode,
        "connected" to connected,
        "connectionHandle" to connectionHandle,
        "peerBdAddr" to peerBdAddr,
        "peerReadGapDeviceName" to peerReadGapDeviceName,
        "peerPaired" to peerPaired,
        "peerTimeSyncCount" to peerTimeSyncCount,
        "peerAttExchangeCount" to peerAttExchangeCount,
        "peerMtu" to peerMtu,
    )

    fun restoreSnapshot(data: Map<String, Any?>) {
        val nextBd = parseMac6(data["bdAddr"] as? String ?: formatMac6(initialBdAddrBytes))
        val nextRandAddr = parseMac6(data["randomAddr"] as? String ?: "00:00:00:00:00:00")
        val nextAdvData = Base64.getDecoder().decode(data["advDataBase64"] as? String ?: "")
        val nextScanResp = Base64.getDecoder().decode(data["scanRespDataBase64"] as? String ?: "")
        val nextWhiteList = (data["whiteList"] as? List<*>)?.map { it.toString() } ?: emptyList()

        rxBuf.reset()
        currentPktType = 0
        expectedLen = 0
        powerOn = data["powerOn"] as? Boolean ?: true
        uartBaudRate = (data["uartBaudRate"] as? Number)?.toInt() ?: 115200
        uartIbrd = (data["uartIbrd"] as? Number)?.toInt() ?: 0
        uartFbrd = (data["uartFbrd"] as? Number)?.toInt() ?: 0
        minidriverDownloaded = data["minidriverDownloaded"] as? Boolean ?: false
        patchRamLoaded = data["patchRamLoaded"] as? Boolean ?: false
        patchRamWriteCount = (data["patchRamWriteCount"] as? Number)?.toLong() ?: 0L
        patchRamBytesWritten = (data["patchRamBytesWritten"] as? Number)?.toLong() ?: 0L
        patchRamLaunchAddr = (data["patchRamLaunchAddr"] as? Number)?.toLong() ?: 0L
        patchRamSha256 = data["patchRamSha256"] as? String ?: ""
        nextBd.copyInto(bdAddrBytes)
        randomAddrSet = data["randomAddrSet"] as? Boolean ?: false
        nextRandAddr.copyInto(randomAddrBytes)
        localName = data["localName"] as? String ?: ""
        eventMask = (data["eventMask"] as? Number)?.toLong() ?: 0x00001FFFFFFFFFFFL
        leEventMask = (data["leEventMask"] as? Number)?.toLong() ?: 0x000000000000001FL
        leHostSupported = data["leHostSupported"] as? Boolean ?: false
        advertising = data["advertising"] as? Boolean ?: false
        advIntervalMin = (data["advIntervalMin"] as? Number)?.toInt() ?: 0x0800
        advIntervalMax = (data["advIntervalMax"] as? Number)?.toInt() ?: 0x0800
        advType = (data["advType"] as? Number)?.toInt() ?: 0
        advOwnAddrType = (data["advOwnAddrType"] as? Number)?.toInt() ?: 0
        advChannelMap = (data["advChannelMap"] as? Number)?.toInt() ?: 0x07
        advData = nextAdvData
        scanRespData = nextScanResp
        advertisedName = parseAdLocalName(advData).orEmpty()
        scanResponseName = parseAdLocalName(scanRespData).orEmpty()
        scanning = data["scanning"] as? Boolean ?: false
        whiteList.clear()
        whiteList.addAll(nextWhiteList)
        randState = (data["randState"] as? Number)?.toLong() ?: 0x43438A1_01020304L
        hciCmdCount = (data["hciCmdCount"] as? Number)?.toLong() ?: 0L
        hciAclTxCount = (data["hciAclTxCount"] as? Number)?.toLong() ?: 0L
        hciAclRxCount = (data["hciAclRxCount"] as? Number)?.toLong() ?: 0L
        lastOpcode = (data["lastOpcode"] as? Number)?.toInt() ?: 0
        recentOpcodes.clear()
        connected = data["connected"] as? Boolean ?: false
        connectionHandle = (data["connectionHandle"] as? Number)?.toInt() ?: 0
        peerBdAddr = data["peerBdAddr"] as? String ?: formatMac6(VIRTUAL_PEER_BD_ADDR)
        peerReadGapDeviceName = data["peerReadGapDeviceName"] as? String
        peerPaired = data["peerPaired"] as? Boolean ?: false
        peerTimeSyncCount = (data["peerTimeSyncCount"] as? Number)?.toLong() ?: 0L
        peerAttExchangeCount = (data["peerAttExchangeCount"] as? Number)?.toLong() ?: 0L
        peerMtu = (data["peerMtu"] as? Number)?.toInt() ?: 23
    }
}
