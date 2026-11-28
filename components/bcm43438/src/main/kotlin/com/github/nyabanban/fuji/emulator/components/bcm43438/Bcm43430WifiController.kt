package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.HostNetworkInterfaceInfo
import com.github.nyabanban.fuji.emulator.api.SdioCardPort
import com.github.nyabanban.fuji.emulator.api.SdioCommandResult
import com.github.nyabanban.fuji.emulator.api.WifiNetworkConfig
import com.github.nyabanban.fuji.emulator.api.WifiNetworkStatus
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VirtualWifiApNeighbor(
    val ssid: String,
    val bssid: String,
    val channel: Int = 6,
    val rssi: Int = -42,
    val security: String = "WPA2", // "WPA2" or "OPEN"
    val passphrase: String = "fujiemu123",
)

/**
 * Datasheet-faithful Broadcom BCM43430A1 (BCM43438A1 Wi-Fi die) FullMAC SDIO v3.0 device
 * integrated with full-featured `NAT` / `BRIDGE` / `HOST_ONLY` real networking ([WifiNetworkEngine]).
 *
 * Implements:
 * - SDIO CMD0, CMD5 (OCR `0xB0FF8000` / 2 I/O functions), CMD3 (RCA `0x0001`), CMD7, CMD52, CMD53;
 * - Function 0 CCCR (`0x00..0x1F`), FBR1 (`0x100..0x1FF`), FBR2 (`0x200..0x2FF`), and Broadcom CIS
 *   (`CISTPL_MANFID 0x02D0:0x43E2` or configured `sdioProdId`);
 * - Function 1 Silicon Backplane sliding window (`SBSDIO_FUNC1_SBADDRLOW/MID/HIGH`, `CHIPCLKCSR`),
 *   ChipCommon (`0x18000000`, `chipId=0xA9A6` rev 1), AXI EROM (`0x1810F000`), SDIO Device Core
 *   (`0x18002000`), ARM CM3 Wrapper (`0x18103000`), and 512 KiB SOCRAM (`0x00000000..0x0007FFFF`)
 *   genuinely receiving firmware + NVRAM downloads;
 * - Function 2 `SDPCM` framing + `CDC` IOCTL/IOVAR dispatcher (`WLC_GET_MAGIC`, `WLC_UP`, `WLC_DOWN`,
 *   `WLC_SET_AP`, `WLC_SET_SSID`, `WLC_SCAN`, `WLC_SCAN_RESULTS`, `WLC_DISASSOC`, `WLC_GET_RSSI`,
 *   `WLC_GET_RATE`, `escan`, `cur_etheraddr`, `ver`, `bsscfg:ssid`, `wsec`, `wpa_auth`, `chanspec`,
 *   `clmload`, etc.), authentic Broadcom `WLC_E_*` event frames (`WLC_E_ESCAN_RESULT`,
 *   `WLC_E_SCAN_COMPLETE`, `WLC_E_JOIN`, `WLC_E_AUTH`, `WLC_E_ASSOC`, `WLC_E_SET_SSID`, `WLC_E_LINK`),
 *   and bidirectional `BDC` Ethernet II frame delivery over SDIO F2 wired to both [WifiNetworkEngine]
 *   (`NAT`, `BRIDGE`, `HOST_ONLY`) and [VirtualWifiPeer].
 */
class Bcm43430WifiController(
    initialMac: ByteArray,
    private val sdioProdId: Int = DEFAULT_SDIO_PROD_ID,
    private val cardIrqSink: (Boolean) -> Unit,
    private val peerNameProvider: () -> String,
    private val neighborsProvider: () -> List<VirtualWifiApNeighbor> = {
        listOf(
            VirtualWifiApNeighbor(ssid = peerNameProvider(), bssid = "02:46:4A:48:00:01", channel = 6, rssi = -42, security = "WPA2"),
            VirtualWifiApNeighbor(ssid = "FujiEmu-AP-2.4G", bssid = "02:46:4A:48:00:02", channel = 11, rssi = -54, security = "OPEN", passphrase = ""),
        )
    },
    private val beforeBusActivity: () -> Unit = {},
    initialNetworkConfig: WifiNetworkConfig = WifiNetworkConfig(
        macAddress = Bcm43438BtController.formatMac6(initialMac),
    ),
    osNameProvider: () -> String = { System.getProperty("os.name", "") },
    hostInterfacesProvider: () -> List<HostNetworkInterfaceInfo> = {
        HostNetworkInterfaceInfo.discoverAll(includeDown = true, includeLoopback = false)
    },
    customBridgeFactory: ((WifiNetworkConfig, (ByteArray) -> Unit) -> BridgeTransport?)? = null,
) : SdioCardPort, AutoCloseable {

    companion object {
        const val BROADCOM_MANFID = 0x02D0
        const val DEFAULT_SDIO_PROD_ID = 0x43E2
        const val CHIP_ID_43430_A1 = 0x1001A9A6L // AXI chipType=1, rev=1 (A1), id=0xA9A6 (43430)
        const val SOCRAM_SIZE = 512 * 1024

        // Backplane bases
        const val CHIPCOMMON_BASE = 0x18000000L
        const val D11_BASE = 0x18001000L
        const val SDIO_CORE_BASE = 0x18002000L
        const val ARM_CM3_BASE = 0x18003000L
        const val SOCRAM_CORE_BASE = 0x18004000L
        const val CHIPCOMMON_WRAP_BASE = 0x18100000L
        const val D11_WRAP_BASE = 0x18101000L
        const val SDIO_WRAP_BASE = 0x18102000L
        const val ARM_CM3_WRAP_BASE = 0x18103000L
        const val SOCRAM_WRAP_BASE = 0x18104000L
        const val EROM_BASE = 0x1810F000L

        // Function 1 SBSDIO registers
        const val SBSDIO_FUNC1_SBADDRLOW = 0x1000A
        const val SBSDIO_FUNC1_SBADDRMID = 0x1000B
        const val SBSDIO_FUNC1_SBADDRHIGH = 0x1000C
        const val SBSDIO_FUNC1_FRAMECTRL = 0x1000D
        const val SBSDIO_FUNC1_CHIPCLKCSR = 0x1000E
        const val SBSDIO_FUNC1_SDIOPULLUP = 0x1000F
        const val SBSDIO_FUNC1_WAKEUPCTRL = 0x1001E
        const val SBSDIO_FUNC1_SLEEPCSR = 0x1001F

        // SDIO core interrupt bits
        const val I_HMB_SW_MASK = 0x000000F0L
        const val I_HMB_FRAME_IND = 0x00000040L

        // SDPCM channels
        const val SDPCM_CONTROL_CHANNEL = 0
        const val SDPCM_EVENT_CHANNEL = 1
        const val SDPCM_DATA_CHANNEL = 2

        // Broadcom WLC IOCTLs
        const val WLC_GET_MAGIC = 0
        const val WLC_GET_VERSION = 1
        const val WLC_UP = 2
        const val WLC_DOWN = 3
        const val WLC_GET_RATE = 12
        const val WLC_GET_INFRA = 19
        const val WLC_SET_INFRA = 20
        const val WLC_GET_AUTH = 21
        const val WLC_SET_AUTH = 22
        const val WLC_GET_BSSID = 23
        const val WLC_SET_BSSID = 24
        const val WLC_GET_SSID = 25
        const val WLC_SET_SSID = 26
        const val WLC_GET_CHANNEL = 29
        const val WLC_SET_CHANNEL = 30
        const val WLC_SCAN = 50
        const val WLC_SCAN_RESULTS = 51
        const val WLC_DISASSOC = 52
        const val WLC_GET_DTIMPRD = 77
        const val WLC_GET_PM = 85
        const val WLC_SET_PM = 86
        const val WLC_GET_REVINFO = 98
        const val WLC_GET_AP = 117
        const val WLC_SET_AP = 118
        const val WLC_GET_RSSI = 127
        const val WLC_GET_WSEC = 133
        const val WLC_SET_WSEC = 134
        const val WLC_GET_BSS_INFO = 136
        const val WLC_GET_BANDLIST = 140
        const val WLC_GET_BAND = 141
        const val WLC_GET_ASSOCLIST = 159
        const val WLC_GET_WPA_AUTH = 164
        const val WLC_SET_WPA_AUTH = 165
        const val WLC_SET_SCAN_CHANNEL_TIME = 185
        const val WLC_SET_SCAN_UNASSOC_TIME = 187
        const val WLC_GET_VALID_CHANNELS = 217
        const val WLC_SET_SCAN_PASSIVE_TIME = 258
        const val WLC_GET_VAR = 262
        const val WLC_SET_VAR = 263
        const val WLC_SET_WSEC_PMK = 268
        const val WLC_IOCTL_MAGIC = 0x14E46C77

        // Broadcom WLC_E_* Async Event types
        const val WLC_E_SET_SSID = 0
        const val WLC_E_JOIN = 1
        const val WLC_E_AUTH = 3
        const val WLC_E_DEAUTH = 5
        const val WLC_E_DEAUTH_IND = 6
        const val WLC_E_ASSOC = 7
        const val WLC_E_DISASSOC = 11
        const val WLC_E_DISASSOC_IND = 12
        const val WLC_E_LINK = 16
        const val WLC_E_SCAN_COMPLETE = 26
        const val WLC_E_ESCAN_RESULT = 69
        const val ETHER_TYPE_EAPOL = 0x888E
        const val WLC_E_STATUS_SUCCESS = 0
        const val WLC_E_STATUS_FAIL = 1
        const val WLC_E_STATUS_ABORT = 4
        const val WLC_E_STATUS_PARTIAL = 8
        const val WLC_EVENT_MSG_LINK = 0x0001
        const val WL_BSS_INFO_VERSION = 109
        const val WL_BSS_INFO_HEADER_SIZE = 124

        const val CONFIGURED_AP_BSSID = "02:46:4A:48:00:10"

        const val CDC_F_SET = 0x00000002
        const val CDC_F_REPLY = 0x00000000
        const val CDC_F_ID_SHIFT = 16

        private fun putU16Be(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v ushr 8) and 0xff).toByte()
            buf[off + 1] = (v and 0xff).toByte()
        }

        private fun putU32Be(buf: ByteArray, off: Int, v: Int) {
            buf[off] = ((v ushr 24) and 0xff).toByte()
            buf[off + 1] = ((v ushr 16) and 0xff).toByte()
            buf[off + 2] = ((v ushr 8) and 0xff).toByte()
            buf[off + 3] = (v and 0xff).toByte()
        }

        private fun r5(dataByte: Int): IntArray = intArrayOf(0x00001000 or (dataByte and 0xff), 0, 0, 0)

        private fun u16Le(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8)

        private fun u32Le(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xff) or
                ((buf[off + 1].toInt() and 0xff) shl 8) or
                ((buf[off + 2].toInt() and 0xff) shl 16) or
                ((buf[off + 3].toInt() and 0xff) shl 24)

        private fun putU16Le(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v ushr 8) and 0xff).toByte()
        }

        private fun putU32Le(buf: ByteArray, off: Int, v: Int) {
            buf[off] = (v and 0xff).toByte()
            buf[off + 1] = ((v ushr 8) and 0xff).toByte()
            buf[off + 2] = ((v ushr 16) and 0xff).toByte()
            buf[off + 3] = ((v ushr 24) and 0xff).toByte()
        }
    }

    // Power & SDIO bus state
    @Volatile
    var wifiPowerOn: Boolean = true
    var rca: Int = 0
        private set
    var selected: Boolean = false
        private set
    var sdioCmdCount: Long = 0L
        private set
    var cmd52Count: Long = 0L
        private set
    var cmd53Count: Long = 0L
        private set

    // Function 0 CCCR/FBR state
    private var ioEnable: Int = 0
    @Volatile
    private var intEnable: Int = 0
    private var busIfCtrl: Int = 0x02 // 4-bit capable
    private var highSpeedCtrl: Int = 0x01 // SHS=1
    private val funcBlockSize = intArrayOf(64, 64, 512)

    // Function 1 Silicon Backplane state
    private var sbAddrLow: Int = 0
    private var sbAddrMid: Int = 0
    private var sbAddrHigh: Int = 0x18 // default ChipCommon 0x18000000
    private var frameCtrl: Int = 0
    private var chipClkCsr: Int = 0
    private var sdioPullUp: Int = 0
    private var wakeupCtrl: Int = 0
    private var sleepCsr: Int = 0
    private var f0SepIntCtl: Int = 0
    @Volatile
    private var sdioIntStatus: Long = 0L
    @Volatile
    private var sdioHostIntMask: Long = 0L
    private var toHostMailboxData: Long = 0L
    private var toSbMailboxData: Long = 0L
    private var ccResetCtrl: Int = 0
    private var ccIoCtrl: Int = 1
    private var d11ResetCtrl: Int = 0
    private var d11IoCtrl: Int = 1
    private var sdioResetCtrl: Int = 0
    private var sdioIoCtrl: Int = 1
    private var armResetCtrl: Int = 1 // Starts in reset until guest downloads FW & releases reset
    private var armIoCtrl: Int = 0
    private var socramResetCtrl: Int = 0
    private var socramIoCtrl: Int = 1
    private var socramBankIdx: Int = 0

    // Genuine 512 KiB SOCRAM receiving guest firmware + NVRAM
    private val socram = ByteArray(SOCRAM_SIZE)
    var firmwareLoaded: Boolean = false
        private set
    var firmwareBytesWritten: Long = 0L
        private set
    var firmwareSha256: String = ""
        private set
    var clmLoaded: Boolean = false
        private set
    var clmBytesWritten: Long = 0L
        private set

    // FullMAC Wi-Fi MAC / SoftAP / STA state
    private val initialMacBytes = initialMac.copyOf(6)
    private val macBytes = initialMac.copyOf(6)
    @Volatile
    var mode: String = "OFF" // OFF | AP | STA
        private set
    @Volatile
    var interfaceUp: Boolean = false
        private set
    @Volatile
    var apEnabled: Boolean = false
        private set
    @Volatile
    var ssid: String = ""
        private set
    var channel: Int = 6
        private set
    var rssiDbm: Int = -38
        private set
    var wsec: Int = 0
        private set
    var wpaAuth: Int = 0
        private set
    var infra: Int = 1
        private set
    var auth: Int = 0
        private set
    var pmMode: Int = 0
        private set
    var countryCode: String = "US"
        private set
    var ioctlCount: Long = 0L
        private set
    var iovarCount: Long = 0L
        private set
    var scanCount: Long = 0L
        private set
    @Volatile
    var associatedBssid: String = ""
        private set
    @Volatile
    var wpa2FourWayComplete: Boolean = false
        private set
    @Volatile
    var wpa2HandshakeCount: Long = 0L
        private set
    private var activeWpa2Session: Wpa2EapolSession? = null
    private val iovarStore = linkedMapOf<String, ByteArray>()

    private class Wpa2EapolSession(
        val apMac: ByteArray,
        val staMac: ByteArray,
        val ssid: String,
        val passphrase: String,
        val aNonce: ByteArray,
    ) {
        var msg1Sent: Boolean = false
        var replayCounter: Long = 1L
        var kck: ByteArray = ByteArray(0)
        var kek: ByteArray = ByteArray(0)
        val gtk: ByteArray = ByteArray(16) { i -> (0x42 + i * 7).toByte() }
    }

    // Thread-safe Function 2 SDPCM RX queue (supports async RX from NAT/Bridge sockets & sync owner-thread IOCTLs)
    private val f2Lock = Any()
    private var txSeqExpected: Int = 0
    private var rxSeq: Int = 0
    private val f2RxQueue = ArrayDeque<ByteArray>()
    private var f2PendingReadFrame: ByteArray? = null
    private var f2PendingReadOffset: Int = 0

    // Deterministic loopback + opt-in PTP-IP peer (retained for stock camera SoftAP compatibility)
    internal val virtualPeer = VirtualWifiPeer(
        peerNameProvider = peerNameProvider,
        rxEthernetSink = { ethFrame -> enqueueBdcRxEthernet(ethFrame) },
    )

    // Real NAT / BRIDGE / HOST_ONLY networking engine
    internal val networkEngine: WifiNetworkEngine

    private var cisRom: ByteArray
    private val axiErom: IntArray = buildAxiErom()

    init {
        runCatching { Bcm43438BtController.parseMac6(initialNetworkConfig.macAddress).copyInto(macBytes) }
        cisRom = buildCisRom(macBytes)
        networkEngine = WifiNetworkEngine(
            initialConfig = initialNetworkConfig.copy(macAddress = macAddressString()),
            guestMacProvider = { synchronized(f2Lock) { macBytes.copyOf() } },
            linkUpProvider = {
                wifiPowerOn && (interfaceUp || associatedBssid.isNotEmpty() || apEnabled)
            },
            associatedSsidProvider = {
                ssid.ifBlank { networkEngineConfigSsid() }
            },
            rxEthernetSink = { ethFrame -> enqueueBdcRxEthernet(ethFrame) },
            osNameProvider = osNameProvider,
            hostInterfacesProvider = hostInterfacesProvider,
            customBridgeFactory = customBridgeFactory,
        )
    }

    private fun networkEngineConfigSsid(): String =
        if (associatedBssid.isNotEmpty() || apEnabled) networkEngine.config.ssid else ""

    fun macAddressString(): String = synchronized(f2Lock) { Bcm43438BtController.formatMac6(macBytes) }

    fun getNetworkConfig(): WifiNetworkConfig = networkEngine.config.copy(macAddress = macAddressString())

    fun getNetworkStatus(): WifiNetworkStatus = networkEngine.status(macAddressString())

    /**
     * Live-reconfigures the Wi-Fi network mode (`NAT`, `BRIDGE`, `HOST_ONLY`), SSID, subnet, DNS,
     * MAC address, and bridge adapter without requiring an emulator restart.
     */
    fun reconfigureNetwork(config: WifiNetworkConfig) {
        val parsedMac = runCatching { Bcm43438BtController.parseMac6(config.macAddress) }.getOrNull()
        if (parsedMac != null) {
            synchronized(f2Lock) {
                parsedMac.copyInto(macBytes)
                cisRom = buildCisRom(macBytes)
            }
        }
        val normalized = config.copy(macAddress = macAddressString())
        val wasEnabled = networkEngine.config.enabled
        networkEngine.reconfigure(normalized)
        if (!normalized.enabled && wasEnabled && associatedBssid.isNotEmpty()) {
            val prevBssid = runCatching { Bcm43438BtController.parseMac6(associatedBssid) }.getOrDefault(macBytes.copyOf())
            associatedBssid = ""
            enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, prevBssid, flags = 0)
        }
    }

    private fun effectiveNeighbors(): List<VirtualWifiApNeighbor> {
        val cfg = networkEngine.config
        val list = mutableListOf<VirtualWifiApNeighbor>()
        if (cfg.enabled && cfg.ssid.isNotBlank()) {
            list += VirtualWifiApNeighbor(
                ssid = cfg.ssid,
                bssid = CONFIGURED_AP_BSSID,
                channel = 6,
                rssi = -38,
                security = if (cfg.passphrase.isEmpty()) "OPEN" else "WPA2",
                passphrase = cfg.passphrase,
            )
        }
        for (ap in neighborsProvider()) {
            if (list.none { it.ssid == ap.ssid }) {
                list += ap
            }
        }
        return list
    }

    fun setPowerPin(high: Boolean) {
        if (wifiPowerOn == high) return
        wifiPowerOn = high
        if (!high) {
            hardResetChip()
        }
    }

    private fun hardResetChip() {
        reset()
        ioEnable = 0
        intEnable = 0
        chipClkCsr = 0
        sleepCsr = 0
        f0SepIntCtl = 0
        ccResetCtrl = 0
        ccIoCtrl = 1
        d11ResetCtrl = 0
        d11IoCtrl = 1
        sdioResetCtrl = 0
        sdioIoCtrl = 1
        armResetCtrl = 1
        armIoCtrl = 0
        socramResetCtrl = 0
        socramIoCtrl = 1
        socramBankIdx = 0
        firmwareLoaded = false
        firmwareBytesWritten = 0L
        firmwareSha256 = ""
        clmLoaded = false
        clmBytesWritten = 0L
        interfaceUp = false
        apEnabled = false
        mode = "OFF"
        ssid = ""
        associatedBssid = ""
        synchronized(f2Lock) {
            f2RxQueue.clear()
            f2PendingReadFrame = null
            f2PendingReadOffset = 0
            sdioIntStatus = 0L
        }
        syncCardIrq()
    }

    override fun reset() {
        rca = 0
        selected = false
        ioEnable = 0
        intEnable = 0
        synchronized(f2Lock) {
            txSeqExpected = 0
            rxSeq = 0
            sdioIntStatus = 0L
            f2RxQueue.clear()
            f2PendingReadFrame = null
            f2PendingReadOffset = 0
        }
        syncCardIrq()
    }

    override fun isCardInterruptAsserted(): Boolean {
        if (!wifiPowerOn) return false
        val masterEn = (intEnable and 0x01) != 0
        if (!masterEn) return false
        return synchronized(f2Lock) {
            val f2Pending = (f2RxQueue.isNotEmpty() || f2PendingReadFrame != null) && (intEnable and 0x04) != 0
            val f1Pending = ((sdioIntStatus and sdioHostIntMask) != 0L) && (intEnable and 0x02) != 0
            f2Pending || f1Pending
        }
    }

    private fun syncCardIrq() {
        cardIrqSink(isCardInterruptAsserted())
    }

    override fun executeCommand(cmdIndex: Int, argument: Long, blockSize: Int, blockCount: Int): SdioCommandResult {
        beforeBusActivity()
        if (!wifiPowerOn) {
            return SdioCommandResult(IntArray(4), accepted = false)
        }
        sdioCmdCount++
        val arg = argument and 0xffff_ffffL
        return when (cmdIndex) {
            0 -> { // CMD0 GO_IDLE_STATE
                reset()
                SdioCommandResult(IntArray(4), accepted = true)
            }
            5 -> { // CMD5 IO_SEND_OP_COND -> R4 (ready=1, nFuncs=2, mem=0, OCR=0x00FF8000)
                val r4 = 0xB0FF8000L.toInt()
                SdioCommandResult(intArrayOf(r4, 0, 0, 0), accepted = true)
            }
            3 -> { // CMD3 SEND_RELATIVE_ADDR -> R6 (RCA = 0x0001)
                rca = 0x0001
                val r6 = (rca shl 16) or 0x0500
                SdioCommandResult(intArrayOf(r6, 0, 0, 0), accepted = true)
            }
            7 -> { // CMD7 SELECT_DESELECT_CARD
                val targetRca = ((arg ushr 16) and 0xffffL).toInt()
                selected = (targetRca != 0 && targetRca == rca)
                SdioCommandResult(intArrayOf(0x00001E00, 0, 0, 0), accepted = true)
            }
            52 -> handleCmd52(arg)
            53 -> handleCmd53Command(arg, blockSize, blockCount)
            else -> SdioCommandResult(IntArray(4), accepted = false)
        }
    }

    private fun handleCmd52(arg: Long): SdioCommandResult {
        cmd52Count++
        val write = ((arg ushr 31) and 1L) != 0L
        val func = ((arg ushr 28) and 7L).toInt()
        val raw = ((arg ushr 27) and 1L) != 0L
        val addr = ((arg ushr 9) and 0x1ffffL).toInt()
        val writeData = (arg and 0xffL).toInt()

        val readBack = if (write) {
            writeFunctionByte(func, addr, writeData)
            if (raw) readFunctionByte(func, addr) else writeData
        } else {
            readFunctionByte(func, addr)
        }
        syncCardIrq()
        return SdioCommandResult(
            response = r5(readBack),
            accepted = true,
            cardInterrupt = isCardInterruptAsserted(),
        )
    }

    private fun handleCmd53Command(arg: Long, hostBlockSize: Int, hostBlockCount: Int): SdioCommandResult {
        cmd53Count++
        val write = ((arg ushr 31) and 1L) != 0L
        val func = ((arg ushr 28) and 7L).toInt()
        val blockMode = ((arg ushr 27) and 1L) != 0L
        val opCodeInc = ((arg ushr 26) and 1L) != 0L
        val addr = ((arg ushr 9) and 0x1ffffL).toInt()
        val rawCount = (arg and 0x1ffL).toInt()
        val count = if (rawCount == 0) 512 else rawCount

        val totalBytes = if (blockMode) {
            val blkSz = if (hostBlockSize > 0) hostBlockSize else funcBlockSize.getOrElse(func) { 512 }
            val blkCnt = if (hostBlockCount > 0) hostBlockCount else count
            blkSz * blkCnt
        } else {
            count
        }

        if (write) {
            return SdioCommandResult(
                response = r5(0),
                accepted = true,
                expectDataInBytes = totalBytes,
                cardInterrupt = isCardInterruptAsserted(),
            )
        }

        val dataOut = readFunctionBlock(func, addr, totalBytes, opCodeInc)
        syncCardIrq()
        return SdioCommandResult(
            response = r5(0),
            accepted = true,
            dataOut = dataOut,
            cardInterrupt = isCardInterruptAsserted(),
        )
    }

    override fun writeTransferData(cmdIndex: Int, argument: Long, data: ByteArray): SdioCommandResult {
        if (!wifiPowerOn || cmdIndex != 53) {
            return SdioCommandResult(IntArray(4), accepted = false)
        }
        val arg = argument and 0xffff_ffffL
        val func = ((arg ushr 28) and 7L).toInt()
        val opCodeInc = ((arg ushr 26) and 1L) != 0L
        val addr = ((arg ushr 9) and 0x1ffffL).toInt()
        writeFunctionBlock(func, addr, data, opCodeInc)
        syncCardIrq()
        return SdioCommandResult(
            response = r5(0),
            accepted = true,
            cardInterrupt = isCardInterruptAsserted(),
        )
    }

    // ================= Function 0 / 1 / 2 Register & Memory Spaces =================

    private fun readFunctionByte(func: Int, addr: Int): Int = when (func) {
        0 -> readF0Byte(addr)
        1 -> readF1Byte(addr)
        2 -> {
            val b = readF2Bytes(1)
            b[0].toInt() and 0xff
        }
        else -> 0
    }

    private fun writeFunctionByte(func: Int, addr: Int, value: Int) {
        when (func) {
            0 -> writeF0Byte(addr, value)
            1 -> writeF1Byte(addr, value)
            2 -> handleF2Write(byteArrayOf(value.toByte()))
        }
    }

    private fun readFunctionBlock(func: Int, addr: Int, length: Int, inc: Boolean): ByteArray = when (func) {
        0 -> ByteArray(length) { i -> readF0Byte(if (inc) addr + i else addr).toByte() }
        1 -> readF1Block(addr, length, inc)
        2 -> readF2Bytes(length)
        else -> ByteArray(length)
    }

    private fun writeFunctionBlock(func: Int, addr: Int, data: ByteArray, inc: Boolean) {
        when (func) {
            0 -> for (i in data.indices) writeF0Byte(if (inc) addr + i else addr, data[i].toInt() and 0xff)
            1 -> writeF1Block(addr, data, inc)
            2 -> handleF2Write(data)
        }
    }

    private fun readF0Byte(addr: Int): Int = when (addr) {
        0x00 -> 0x43 // CCCR 3.0 / SDIO 3.0
        0x01 -> 0x03 // SD Spec 3.0
        0x02 -> ioEnable
        0x03 -> {
            var rdy = ioEnable and 0x02
            if ((ioEnable and 0x04) != 0) rdy = rdy or 0x04
            rdy
        }
        0x04 -> intEnable
        0x05 -> synchronized(f2Lock) {
            var pend = 0
            if ((sdioIntStatus and sdioHostIntMask) != 0L) pend = pend or 0x02
            if (f2RxQueue.isNotEmpty() || f2PendingReadFrame != null) pend = pend or 0x04
            pend
        }
        0x07 -> busIfCtrl
        0x08 -> 0x17 // Card capability: SMB | SRW | SBS | SDC
        0x09 -> 0x00 // Common CIS ptr = 0x001000
        0x0A -> 0x10
        0x0B -> 0x00
        0x10 -> funcBlockSize[0] and 0xff
        0x11 -> (funcBlockSize[0] ushr 8) and 0xff
        0x13 -> highSpeedCtrl
        0xF0 -> f0SepIntCtl
        // FBR1 (0x100..0x1FF)
        0x100 -> 0x00
        0x109 -> 0x40 // F1 CIS ptr = 0x001040
        0x10A -> 0x10
        0x10B -> 0x00
        0x110 -> funcBlockSize[1] and 0xff
        0x111 -> (funcBlockSize[1] ushr 8) and 0xff
        // FBR2 (0x200..0x2FF)
        0x200 -> 0x00
        0x209 -> 0x60 // F2 CIS ptr = 0x001060
        0x20A -> 0x10
        0x20B -> 0x00
        0x210 -> funcBlockSize[2] and 0xff
        0x211 -> (funcBlockSize[2] ushr 8) and 0xff
        else -> {
            val rom = cisRom
            if (addr in 0x1000 until (0x1000 + rom.size)) {
                rom[addr - 0x1000].toInt() and 0xff
            } else 0
        }
    }

    private fun writeF0Byte(addr: Int, value: Int) {
        val v = value and 0xff
        when (addr) {
            0x02 -> ioEnable = v and 0x06
            0x04 -> intEnable = v and 0x07
            0x06 -> if ((v and 0x08) != 0) reset()
            0x07 -> busIfCtrl = v
            0x10 -> funcBlockSize[0] = (funcBlockSize[0] and 0xff00) or v
            0x11 -> funcBlockSize[0] = (funcBlockSize[0] and 0x00ff) or (v shl 8)
            0x13 -> highSpeedCtrl = (highSpeedCtrl and 0x01) or (v and 0x02)
            0xF0 -> f0SepIntCtl = v
            0x110 -> funcBlockSize[1] = (funcBlockSize[1] and 0xff00) or v
            0x111 -> funcBlockSize[1] = (funcBlockSize[1] and 0x00ff) or (v shl 8)
            0x210 -> funcBlockSize[2] = (funcBlockSize[2] and 0xff00) or v
            0x211 -> funcBlockSize[2] = (funcBlockSize[2] and 0x00ff) or (v shl 8)
        }
    }

    private fun backplaneBase(): Long =
        ((sbAddrHigh.toLong() and 0xffL) shl 24) or
            ((sbAddrMid.toLong() and 0xffL) shl 16) or
            ((sbAddrLow.toLong() and 0xffL) shl 8)

    private fun readF1Byte(addr: Int): Int = when (addr) {
        SBSDIO_FUNC1_SBADDRLOW -> sbAddrLow
        SBSDIO_FUNC1_SBADDRMID -> sbAddrMid
        SBSDIO_FUNC1_SBADDRHIGH -> sbAddrHigh
        SBSDIO_FUNC1_FRAMECTRL -> frameCtrl
        SBSDIO_FUNC1_CHIPCLKCSR -> chipClkCsr
        SBSDIO_FUNC1_SDIOPULLUP -> sdioPullUp
        SBSDIO_FUNC1_WAKEUPCTRL -> wakeupCtrl
        SBSDIO_FUNC1_SLEEPCSR -> sleepCsr
        else -> {
            val bpAddr = backplaneBase() + (addr and 0x7fff).toLong()
            val word = readBackplaneWord(bpAddr and 0xffff_fffcL)
            val shift = ((bpAddr and 3L).toInt()) * 8
            ((word ushr shift) and 0xffL).toInt()
        }
    }

    private fun writeF1Byte(addr: Int, value: Int) {
        val v = value and 0xff
        when (addr) {
            SBSDIO_FUNC1_SBADDRLOW -> sbAddrLow = v
            SBSDIO_FUNC1_SBADDRMID -> sbAddrMid = v
            SBSDIO_FUNC1_SBADDRHIGH -> sbAddrHigh = v
            SBSDIO_FUNC1_FRAMECTRL -> frameCtrl = v
            SBSDIO_FUNC1_CHIPCLKCSR -> {
                var next = v and 0x3F
                if ((v and 0x09) != 0) next = next or 0x40 // ALP_AVAIL
                if ((v and 0x12) != 0) next = next or 0xC0 // ALP_AVAIL | HT_AVAIL
                chipClkCsr = next
            }
            SBSDIO_FUNC1_SDIOPULLUP -> sdioPullUp = v
            SBSDIO_FUNC1_WAKEUPCTRL -> wakeupCtrl = v
            SBSDIO_FUNC1_SLEEPCSR -> {
                // SBSDIO_SLPCSR_KSO (bit 0) immediately asserts SBSDIO_SLPCSR_DEVON (bit 1)
                sleepCsr = if ((v and 0x01) != 0) (v or 0x02) else (v and 0x02.inv())
            }
            else -> {
                val bpAddr = backplaneBase() + (addr and 0x7fff).toLong()
                writeBackplaneBytes(bpAddr, byteArrayOf(v.toByte()))
            }
        }
    }

    private fun readF1Block(addr: Int, length: Int, inc: Boolean): ByteArray {
        if (addr >= 0x10000) {
            return ByteArray(length) { i -> readF1Byte(if (inc) addr + i else addr).toByte() }
        }
        val base = backplaneBase() + (addr and 0x7fff).toLong()
        if (base in 0L until SOCRAM_SIZE.toLong() && base + length <= SOCRAM_SIZE.toLong()) {
            return socram.copyOfRange(base.toInt(), base.toInt() + length)
        }
        val out = ByteArray(length)
        var i = 0
        while (i < length) {
            val cur = if (inc) base + i else base
            if (i + 4 <= length && (cur and 3L) == 0L) {
                putU32Le(out, i, readBackplaneWord(cur).toInt())
                i += 4
            } else {
                val word = readBackplaneWord(cur and 0xffff_fffcL)
                val shift = ((cur and 3L).toInt()) * 8
                out[i] = ((word ushr shift) and 0xffL).toByte()
                i++
            }
        }
        return out
    }

    private fun writeF1Block(addr: Int, data: ByteArray, inc: Boolean) {
        if (addr >= 0x10000) {
            for (i in data.indices) writeF1Byte(if (inc) addr + i else addr, data[i].toInt() and 0xff)
            return
        }
        val base = backplaneBase() + (addr and 0x7fff).toLong()
        writeBackplaneBytes(base, data)
    }

    private fun readBackplaneWord(bpAddr: Long): Long {
        if (bpAddr in 0L until SOCRAM_SIZE.toLong() - 3L) {
            return u32Le(socram, bpAddr.toInt()).toLong() and 0xffff_ffffL
        }
        if (bpAddr in EROM_BASE until (EROM_BASE + axiErom.size * 4L)) {
            return axiErom[((bpAddr - EROM_BASE) / 4L).toInt()].toLong() and 0xffff_ffffL
        }
        return when (bpAddr) {
            CHIPCOMMON_BASE + 0x000L -> CHIP_ID_43430_A1
            CHIPCOMMON_BASE + 0x004L -> 0x00000000L
            CHIPCOMMON_BASE + 0x0FCL -> EROM_BASE
            SDIO_CORE_BASE + 0x020L -> synchronized(f2Lock) {
                var st = sdioIntStatus
                if (f2RxQueue.isNotEmpty() || f2PendingReadFrame != null) st = st or I_HMB_FRAME_IND
                st
            }
            SDIO_CORE_BASE + 0x024L -> sdioHostIntMask
            SDIO_CORE_BASE + 0x044L -> 0L
            SDIO_CORE_BASE + 0x048L -> toSbMailboxData
            SDIO_CORE_BASE + 0x04CL -> toHostMailboxData
            SOCRAM_CORE_BASE + 0x000L -> 0x00000088L // SRCI_SRNB = 8 banks (bits 7:4), core type 8 -> 8 * 64 KiB = 512 KiB
            SOCRAM_CORE_BASE + 0x010L -> socramBankIdx.toLong() and 0xffff_ffffL
            SOCRAM_CORE_BASE + 0x040L -> 0x00000007L // BANKINFO_SZMASK = 7 -> (7 + 1) * 8 KiB = 64 KiB per bank
            SOCRAM_CORE_BASE + 0x044L -> 0x00000000L // BANKPDA = 0 (all banks active)
            CHIPCOMMON_WRAP_BASE + 0x408L -> ccIoCtrl.toLong() and 0xffff_ffffL
            CHIPCOMMON_WRAP_BASE + 0x800L -> ccResetCtrl.toLong() and 0xffff_ffffL
            D11_WRAP_BASE + 0x408L -> d11IoCtrl.toLong() and 0xffff_ffffL
            D11_WRAP_BASE + 0x800L -> d11ResetCtrl.toLong() and 0xffff_ffffL
            SDIO_WRAP_BASE + 0x408L -> sdioIoCtrl.toLong() and 0xffff_ffffL
            SDIO_WRAP_BASE + 0x800L -> sdioResetCtrl.toLong() and 0xffff_ffffL
            ARM_CM3_WRAP_BASE + 0x408L -> armIoCtrl.toLong() and 0xffff_ffffL
            ARM_CM3_WRAP_BASE + 0x800L -> armResetCtrl.toLong() and 0xffff_ffffL
            SOCRAM_WRAP_BASE + 0x408L -> socramIoCtrl.toLong() and 0xffff_ffffL
            SOCRAM_WRAP_BASE + 0x800L -> socramResetCtrl.toLong() and 0xffff_ffffL
            else -> 0L
        }
    }

    private fun writeBackplaneBytes(bpAddr: Long, data: ByteArray) {
        if (bpAddr in 0L until SOCRAM_SIZE.toLong()) {
            val start = bpAddr.toInt()
            val copyLen = minOf(data.size, SOCRAM_SIZE - start)
            System.arraycopy(data, 0, socram, start, copyLen)
            firmwareBytesWritten += copyLen.toLong()
            return
        }
        if (data.size < 4) return
        val word = u32Le(data, 0).toLong() and 0xffff_ffffL
        when (bpAddr) {
            SDIO_CORE_BASE + 0x020L -> synchronized(f2Lock) { sdioIntStatus = sdioIntStatus and word.inv() }
            SDIO_CORE_BASE + 0x024L -> sdioHostIntMask = word
            SDIO_CORE_BASE + 0x048L -> toSbMailboxData = word
            SOCRAM_CORE_BASE + 0x010L -> socramBankIdx = word.toInt()
            CHIPCOMMON_WRAP_BASE + 0x408L -> ccIoCtrl = word.toInt()
            CHIPCOMMON_WRAP_BASE + 0x800L -> ccResetCtrl = word.toInt() and 0x01
            D11_WRAP_BASE + 0x408L -> d11IoCtrl = word.toInt()
            D11_WRAP_BASE + 0x800L -> d11ResetCtrl = word.toInt() and 0x01
            SDIO_WRAP_BASE + 0x408L -> sdioIoCtrl = word.toInt()
            SDIO_WRAP_BASE + 0x800L -> sdioResetCtrl = word.toInt() and 0x01
            ARM_CM3_WRAP_BASE + 0x408L -> armIoCtrl = word.toInt()
            ARM_CM3_WRAP_BASE + 0x800L -> {
                val prev = armResetCtrl
                armResetCtrl = word.toInt() and 0x01
                if (prev == 1 && armResetCtrl == 0) {
                    onArmCm3ResetReleased()
                } else if (armResetCtrl == 1) {
                    firmwareLoaded = false
                }
            }
            SOCRAM_WRAP_BASE + 0x408L -> socramIoCtrl = word.toInt()
            SOCRAM_WRAP_BASE + 0x800L -> socramResetCtrl = word.toInt() and 0x01
        }
    }

    private fun onArmCm3ResetReleased() {
        firmwareLoaded = true
        synchronized(f2Lock) {
            txSeqExpected = 0
            rxSeq = 0
        }
        val md = MessageDigest.getInstance("SHA-256")
        val hashLen = minOf(firmwareBytesWritten.toInt().coerceAtLeast(0), SOCRAM_SIZE)
        if (hashLen > 0) {
            md.update(socram, 0, hashLen)
            firmwareSha256 = Bcm43438BtController.bytesToHex(md.digest())
        }
        parseNvramFromSocram()
    }

    private fun parseNvramFromSocram() {
        val tailStart = maxOf(0, SOCRAM_SIZE - 4096)
        val tailStr = String(socram, tailStart, SOCRAM_SIZE - tailStart, Charsets.ISO_8859_1)
        val idx = tailStr.indexOf("macaddr=")
        if (idx >= 0) {
            val end = tailStr.indexOf('\u0000', idx).let { if (it < 0) minOf(tailStr.length, idx + 25) else it }
            val macText = tailStr.substring(idx + "macaddr=".length, end).trim()
            runCatching {
                val parsed = Bcm43438BtController.parseMac6(macText)
                synchronized(f2Lock) { parsed.copyInto(macBytes) }
            }
        }
    }

    // ================= Function 2 SDPCM / CDC / IOVAR / BDC =================

    private fun handleF2Write(raw: ByteArray) {
        var off = 0
        while (off + 12 <= raw.size) {
            val frameLen = u16Le(raw, off)
            val frameLenInv = u16Le(raw, off + 2)
            if (frameLen == 0 && frameLenInv == 0) break // Block-mode zero padding
            if (frameLen < 12 || off + frameLen > raw.size) break
            if (((frameLen xor frameLenInv) and 0xffff) != 0xffff) break
            val channel = raw[off + 5].toInt() and 0x0f
            val hdrLen = raw[off + 7].toInt() and 0xff
            if (hdrLen < 12 || hdrLen > frameLen) break
            synchronized(f2Lock) {
                txSeqExpected = (raw[off + 4].toInt() + 1) and 0xff
            }

            val payload = raw.copyOfRange(off + hdrLen, off + frameLen)
            when (channel) {
                SDPCM_CONTROL_CHANNEL -> handleCdcControlFrame(payload)
                SDPCM_DATA_CHANNEL -> handleBdcDataFrame(payload)
            }
            off += frameLen
            // Align next glommed sub-frame if 4-byte zero-padded
            val aligned = (off + 3) and 3.inv()
            if (aligned > off && aligned + 12 <= raw.size) {
                val padZero = (off until aligned).all { raw[it].toInt() == 0 }
                if (padZero) off = aligned
            }
        }
    }

    private fun handleCdcControlFrame(cdc: ByteArray) {
        if (cdc.size < 16) return
        val cmd = u32Le(cdc, 0)
        val dataLen = u32Le(cdc, 4)
        val flags = u32Le(cdc, 8)
        val reqId = (flags ushr CDC_F_ID_SHIFT) and 0xffff
        val isSet = (flags and CDC_F_SET) != 0
        val payloadEnd = (16L + dataLen.toLong().coerceAtLeast(0L)).coerceAtMost(cdc.size.toLong()).toInt()
        val payload = if (cdc.size > 16) cdc.copyOfRange(16, payloadEnd) else ByteArray(0)
        ioctlCount++

        var status = 0
        var replyPayload: ByteArray = payload.copyOf()
        val postReplyActions = mutableListOf<() -> Unit>()

        when (cmd) {
            WLC_GET_MAGIC -> replyPayload = u32Bytes(WLC_IOCTL_MAGIC)
            WLC_GET_VERSION -> replyPayload = u32Bytes(2)
            WLC_UP -> {
                interfaceUp = true
                mode = if (apEnabled) "AP" else "STA"
                replyPayload = ByteArray(0)
            }
            WLC_DOWN -> {
                interfaceUp = false
                associatedBssid = ""
                wpa2FourWayComplete = false
                activeWpa2Session = null
                mode = "OFF"
                replyPayload = ByteArray(0)
            }
            WLC_GET_RATE -> replyPayload = u32Bytes(108) // 54.0 Mbps (in 500 kbps units)
            WLC_GET_RSSI -> replyPayload = u32Bytes(if (associatedBssid.isNotEmpty() || apEnabled) rssiDbm else -90)
            WLC_GET_INFRA -> replyPayload = u32Bytes(infra)
            WLC_SET_INFRA -> {
                if (payload.size >= 4) infra = u32Le(payload, 0)
            }
            WLC_GET_AUTH -> replyPayload = u32Bytes(auth)
            WLC_SET_AUTH -> {
                if (payload.size >= 4) auth = u32Le(payload, 0)
            }
            WLC_GET_BSSID -> replyPayload = when {
                apEnabled -> synchronized(f2Lock) { macBytes.copyOf() }
                associatedBssid.isNotEmpty() -> Bcm43438BtController.parseMac6(associatedBssid)
                else -> ByteArray(6) // 00:00:00:00:00:00 when STA is unassociated so WWD does not enter DISASSOCIATING (0x20)
            }
            WLC_SET_BSSID -> {
                if (payload.size >= 6) {
                    val bssid = payload.copyOfRange(0, 6)
                    associatedBssid = if (bssid.all { it.toInt() == 0 }) "" else Bcm43438BtController.formatMac6(bssid)
                }
            }
            WLC_GET_SSID -> {
                val out = ByteArray(36)
                val enc = ssid.toByteArray(Charsets.UTF_8)
                val n = minOf(enc.size, 32)
                putU32Le(out, 0, n)
                System.arraycopy(enc, 0, out, 4, n)
                replyPayload = out
            }
            WLC_SET_SSID -> {
                if (payload.size >= 4) {
                    val n = minOf(u32Le(payload, 0).coerceAtLeast(0), minOf(32, payload.size - 4))
                    ssid = String(payload, 4, n, Charsets.UTF_8)
                    if (interfaceUp) mode = if (apEnabled) "AP" else "STA"
                    if (!apEnabled && ssid.isNotEmpty()) {
                        if (!prepareStaAssociation(ssid, postReplyActions)) {
                            status = -1
                        }
                    } else if (apEnabled && ssid.isNotEmpty()) {
                        val curMac = synchronized(f2Lock) { macBytes.copyOf() }
                        postReplyActions += {
                            enqueueBrcmEvent(WLC_E_SET_SSID, WLC_E_STATUS_SUCCESS, curMac)
                            enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, curMac, flags = WLC_EVENT_MSG_LINK)
                        }
                    } else if (ssid.isEmpty() && associatedBssid.isNotEmpty()) {
                        disassociateSta(postReplyActions)
                    }
                }
            }
            WLC_DISASSOC -> {
                disassociateSta(postReplyActions, emitAlways = true)
                replyPayload = ByteArray(0)
            }
            WLC_GET_DTIMPRD -> replyPayload = u32Bytes(1)
            WLC_GET_BSS_INFO -> {
                val neighbors = effectiveNeighbors()
                val currentAp = neighbors.firstOrNull {
                    (associatedBssid.isNotEmpty() && it.bssid.equals(associatedBssid, ignoreCase = true)) ||
                        (ssid.isNotEmpty() && it.ssid == ssid)
                } ?: neighbors.firstOrNull()
                if (currentAp != null) {
                    val bssInfo = buildWlBssInfo(currentAp)
                    val out = ByteArray(maxOf(4 + bssInfo.size, dataLen.coerceAtMost(2048)))
                    putU32Le(out, 0, 4 + bssInfo.size)
                    System.arraycopy(bssInfo, 0, out, 4, bssInfo.size)
                    replyPayload = out
                } else {
                    replyPayload = ByteArray(minOf(maxOf(dataLen, 132), 2048))
                }
            }
            WLC_SCAN -> {
                scanCount++
                replyPayload = ByteArray(0)
                val curMac = synchronized(f2Lock) { macBytes.copyOf() }
                enqueueBrcmEvent(WLC_E_SCAN_COMPLETE, WLC_E_STATUS_SUCCESS, curMac)
            }
            WLC_SCAN_RESULTS -> {
                replyPayload = buildLegacyScanResults(effectiveNeighbors())
            }
            WLC_GET_CHANNEL -> {
                val out = ByteArray(12)
                putU32Le(out, 0, channel)
                putU32Le(out, 4, channel)
                replyPayload = out
            }
            WLC_SET_CHANNEL -> {
                if (payload.size >= 4) channel = u32Le(payload, 0)
            }
            WLC_GET_PM -> replyPayload = u32Bytes(pmMode)
            WLC_SET_PM -> {
                if (payload.size >= 4) pmMode = u32Le(payload, 0)
                enqueuePendingWpa2Msg1(postReplyActions)
            }
            WLC_GET_REVINFO -> {
                val out = ByteArray(maxOf(68, dataLen.coerceAtMost(256)))
                putU32Le(out, 0, 0x14E4) // vendorid = Broadcom
                putU32Le(out, 4, sdioProdId) // deviceid = 0x43E2
                putU32Le(out, 8, 0x22050000) // radiorev
                putU32Le(out, 12, 1) // chiprev = A1
                putU32Le(out, 16, 43) // corerev = D11 rev 43
                putU32Le(out, 20, 0x058E) // boardid
                putU32Le(out, 24, 0x14E4) // boardvendor
                putU32Le(out, 28, 0x1101) // boardrev
                putU32Le(out, 32, 0x072D6200) // driverrev = 7.45.98
                putU32Le(out, 36, 0x038001A2) // ucoderev
                putU32Le(out, 40, 3) // bus = SDIO
                putU32Le(out, 44, 0xA9A6) // chipnum = 43430
                putU32Le(out, 48, 4) // phytype = N-PHY
                putU32Le(out, 52, 17) // phyrev
                putU32Le(out, 56, 12) // anarev
                putU32Le(out, 60, 0) // chippkg
                putU32Le(out, 64, 1) // nvramrev
                replyPayload = out
            }
            WLC_GET_AP -> replyPayload = u32Bytes(if (apEnabled) 1 else 0)
            WLC_SET_AP -> {
                if (payload.size >= 4) {
                    apEnabled = u32Le(payload, 0) != 0
                    if (interfaceUp) mode = if (apEnabled) "AP" else "STA"
                }
            }
            WLC_GET_WSEC -> replyPayload = u32Bytes(wsec)
            WLC_SET_WSEC -> {
                if (payload.size >= 4) wsec = u32Le(payload, 0)
            }
            WLC_GET_BANDLIST -> {
                val out = ByteArray(maxOf(12, dataLen.coerceAtMost(64)))
                putU32Le(out, 0, 1) // 1 active band on BCM43430A1 (single-band 2.4 GHz)
                putU32Le(out, 4, 2) // WLC_BAND_2G = 2
                putU32Le(out, 8, 0)
                replyPayload = out
            }
            WLC_GET_BAND -> replyPayload = u32Bytes(2) // WLC_BAND_2G = 2
            WLC_GET_VALID_CHANNELS -> {
                val maxCh = if (countryCode.equals("US", ignoreCase = true)) 11 else 13
                val out = ByteArray(maxOf(4 + maxCh * 4, dataLen.coerceAtMost(512)))
                putU32Le(out, 0, maxCh)
                for (i in 0 until maxCh) {
                    putU32Le(out, 4 + i * 4, i + 1)
                }
                replyPayload = out
            }
            WLC_SET_SCAN_CHANNEL_TIME, WLC_SET_SCAN_UNASSOC_TIME, WLC_SET_SCAN_PASSIVE_TIME -> {
                replyPayload = ByteArray(0)
            }
            WLC_GET_WPA_AUTH -> replyPayload = u32Bytes(wpaAuth)
            WLC_SET_WPA_AUTH -> {
                if (payload.size >= 4) {
                    wpaAuth = u32Le(payload, 0)
                    if (wpaAuth == 0) {
                        iovarStore.remove("wpaie")
                        iovarStore.remove("bsscfg:wpaie")
                    }
                }
            }
            WLC_GET_ASSOCLIST -> {
                val out = ByteArray(4 + 6)
                if (virtualPeer.connected || associatedBssid.isNotEmpty()) {
                    putU32Le(out, 0, 1)
                    val peerMac = if (associatedBssid.isNotEmpty()) {
                        Bcm43438BtController.parseMac6(associatedBssid)
                    } else {
                        VirtualWifiPeer.PEER_MAC
                    }
                    System.arraycopy(peerMac, 0, out, 4, 6)
                }
                replyPayload = out
            }
            WLC_SET_WSEC_PMK -> Unit
            WLC_GET_VAR -> {
                val (name, argData) = parseIovar(payload)
                iovarCount++
                replyPayload = handleIovarGet(name, argData, dataLen)
            }
            WLC_SET_VAR -> {
                val (name, argData) = parseIovar(payload)
                iovarCount++
                handleIovarSet(name, argData, postReplyActions)
            }
            else -> {
                if (!isSet && replyPayload.isEmpty() && dataLen > 0) {
                    replyPayload = ByteArray(minOf(dataLen, 2048))
                }
            }
        }

        val cdcReply = ByteArray(16 + replyPayload.size)
        putU32Le(cdcReply, 0, cmd)
        putU32Le(cdcReply, 4, replyPayload.size)
        putU32Le(cdcReply, 8, (reqId shl CDC_F_ID_SHIFT) or CDC_F_REPLY)
        putU32Le(cdcReply, 12, status)
        System.arraycopy(replyPayload, 0, cdcReply, 16, replyPayload.size)
        enqueueSdpcmRx(SDPCM_CONTROL_CHANNEL, cdcReply)
        postReplyActions.forEach { it() }
    }

    private fun parseIovar(payload: ByteArray): Pair<String, ByteArray> {
        val nul = payload.indexOf(0)
        if (nul < 0) return String(payload, Charsets.US_ASCII) to ByteArray(0)
        val name = String(payload, 0, nul, Charsets.US_ASCII)
        val rest = if (nul + 1 < payload.size) payload.copyOfRange(nul + 1, payload.size) else ByteArray(0)
        return name to rest
    }

    private fun handleIovarGet(name: String, argData: ByteArray, requestedLen: Int = 0): ByteArray = when (name) {
        "cur_etheraddr" -> synchronized(f2Lock) { macBytes.copyOf() }
        "perm_etheraddr" -> initialMacBytes.copyOf()
        "ver" -> "wl0: BCM43430A1 FullMAC 7.45.98\n\u0000".toByteArray(Charsets.US_ASCII)
        "cap" -> "ap sta wme 802.11n bss_max=2\u0000".toByteArray(Charsets.US_ASCII)
        "clmload_status" -> u32Bytes(0)
        "country" -> countryCode.toByteArray(Charsets.US_ASCII).copyOf(maxOf(4, argData.size.coerceAtMost(16)))
        "nmode" -> u32Bytes(1)
        "mimo_bw_cap" -> u32Bytes(0)
        "chanspec" -> u32Bytes(0x1000 or (channel and 0xff))
        "chanspecs" -> {
            // D11AC 2.4 GHz 20 MHz chanspec list: wl_uint32_list_t { uint32 count; uint32 element[count]; }
            // Band 2G (0x0000) | BW 20MHz (0x1000) | channel (1..maxCh) -> 0x1001..0x100d
            val maxCh = if (countryCode.equals("US", ignoreCase = true)) 11 else 13
            val outLen = maxOf(4 + maxCh * 4, argData.size, requestedLen.coerceAtMost(1024))
            val out = ByteArray(outLen)
            putU32Le(out, 0, maxCh)
            for (i in 0 until maxCh) {
                putU32Le(out, 4 + i * 4, 0x1000 or (i + 1))
            }
            out
        }
        "per_chan_info" -> u32Bytes(0) // Active 2.4 GHz channel (neither WL_CHAN_PASSIVE=0x08 nor WL_CHAN_RADAR=0x20)
        "rssi" -> u32Bytes(if (associatedBssid.isNotEmpty() || apEnabled) rssiDbm else -90)
        "wsec", "bsscfg:wsec" -> u32Bytes(wsec)
        "wpa_auth", "bsscfg:wpa_auth" -> u32Bytes(wpaAuth)
        "assoc_info" -> {
            val out = ByteArray(maxOf(28, requestedLen.coerceAtMost(512)))
            if (associatedBssid.isNotEmpty()) {
                val reqIes = buildAssocReqIes()
                val respIes = buildAssocRespIes()
                putU32Le(out, 0, 4 + reqIes.size) // 4-byte AssocReq fixed fields (capability + listen_interval) + IEs
                putU32Le(out, 4, 6 + respIes.size) // 6-byte AssocResp fixed fields (capability + status + aid) + IEs
                putU32Le(out, 8, 0) // flags = 0 (Association Request, not Reassociation)
            }
            out
        }
        "assoc_req_ies" -> {
            val reqIes = buildAssocReqIes()
            val out = ByteArray(maxOf(reqIes.size, requestedLen.coerceAtMost(512)))
            System.arraycopy(reqIes, 0, out, 0, reqIes.size)
            out
        }
        "assoc_resp_ies" -> {
            val respIes = buildAssocRespIes()
            val out = ByteArray(maxOf(respIes.size, requestedLen.coerceAtMost(512)))
            System.arraycopy(respIes, 0, out, 0, respIes.size)
            out
        }
        "bsscfg:cur_etheraddr" -> synchronized(f2Lock) { macBytes.copyOf() }
        "bsscfg:ssid" -> {
            val out = ByteArray(36)
            val enc = ssid.toByteArray(Charsets.UTF_8)
            val n = minOf(enc.size, 32)
            putU32Le(out, 0, n)
            System.arraycopy(enc, 0, out, 4, n)
            out
        }
        else -> iovarStore[name]?.copyOf() ?: ByteArray(maxOf(4, argData.size))
    }

    private fun handleIovarSet(
        name: String,
        argData: ByteArray,
        postReplyActions: MutableList<() -> Unit> = mutableListOf(),
    ) {
        iovarStore[name] = argData.copyOf()
        when (name) {
            "cur_etheraddr" -> if (argData.size >= 6) synchronized(f2Lock) { System.arraycopy(argData, 0, macBytes, 0, 6) }
            "bsscfg:cur_etheraddr" -> if (argData.size >= 10) synchronized(f2Lock) { System.arraycopy(argData, 4, macBytes, 0, 6) }
            "ssid" -> {
                if (argData.size >= 4) {
                    val n = minOf(u32Le(argData, 0).coerceAtLeast(0), minOf(32, argData.size - 4))
                    ssid = String(argData, 4, n, Charsets.UTF_8)
                    if (!apEnabled && ssid.isNotEmpty()) prepareStaAssociation(ssid, postReplyActions)
                }
            }
            "bsscfg:ssid" -> {
                if (argData.size >= 8) {
                    val n = minOf(u32Le(argData, 4).coerceAtLeast(0), minOf(32, argData.size - 8))
                    ssid = String(argData, 8, n, Charsets.UTF_8)
                }
            }
            "join", "extjoin", "bsscfg:join", "bsscfg:extjoin" -> {
                val baseOff = if (name.startsWith("bsscfg:")) 4 else 0
                if (argData.size >= baseOff + 4) {
                    val n = minOf(u32Le(argData, baseOff).coerceAtLeast(0), minOf(32, argData.size - baseOff - 4))
                    ssid = String(argData, baseOff + 4, n, Charsets.UTF_8)
                    if (interfaceUp) mode = if (apEnabled) "AP" else "STA"
                    if (!apEnabled && ssid.isNotEmpty()) {
                        prepareStaAssociation(ssid, postReplyActions)
                    } else if (ssid.isEmpty() && associatedBssid.isNotEmpty()) {
                        disassociateSta(postReplyActions)
                    }
                }
            }
            "wsec" -> if (argData.size >= 4) wsec = u32Le(argData, 0)
            "bsscfg:wsec" -> if (argData.size >= 8) wsec = u32Le(argData, 4)
            "wpa_auth" -> if (argData.size >= 4) {
                wpaAuth = u32Le(argData, 0)
                if (wpaAuth == 0) {
                    iovarStore.remove("wpaie")
                    iovarStore.remove("bsscfg:wpaie")
                }
            }
            "bsscfg:wpa_auth" -> if (argData.size >= 8) {
                wpaAuth = u32Le(argData, 4)
                if (wpaAuth == 0) {
                    iovarStore.remove("wpaie")
                    iovarStore.remove("bsscfg:wpaie")
                }
            }
            "chanspec" -> if (argData.size >= 2) channel = u16Le(argData, 0) and 0xff
            "apsta" -> if (argData.size >= 4 && u32Le(argData, 0) != 0) apEnabled = true
            "bss", "bsscfg:bss" -> {
                val valOff = if (name.startsWith("bsscfg:")) 4 else 0
                if (argData.size >= valOff + 4) {
                    val enable = u32Le(argData, valOff) != 0
                    interfaceUp = enable
                    mode = if (!enable) "OFF" else if (apEnabled) "AP" else "STA"
                }
            }
            "country" -> if (argData.size >= 2) countryCode = String(argData, 0, 2, Charsets.US_ASCII)
            "clmload" -> {
                clmBytesWritten += argData.size.toLong()
                clmLoaded = true
            }
            "escan", "bsscfg:escan", "p2p_scan" -> {
                val baseOff = if (name == "escan") 0 else 4
                val action = if (argData.size >= baseOff + 6) u16Le(argData, baseOff + 4) else 1
                val syncId = if (argData.size >= baseOff + 8) u16Le(argData, baseOff + 6) else 1
                if (action == 1 || action == 2) {
                    runEscan(syncId)
                } else {
                    abortEscan(syncId)
                }
            }
            "scanabort", "escanabort" -> abortEscan(0x1234)
            "PM" -> {
                if (argData.size >= 4) pmMode = u32Le(argData, 0)
                enqueuePendingWpa2Msg1(postReplyActions)
            }
            "event_msgs", "bus:txglomalign", "assoc_listen", "bcn_timeout", "assoc_retry_max", "allmulti", "mcast_list" -> Unit
        }
    }

    private fun enqueuePendingWpa2Msg1(postReplyActions: MutableList<() -> Unit>) {
        val wpa2 = activeWpa2Session ?: return
        if (!wpa2.msg1Sent && associatedBssid.isNotEmpty()) {
            wpa2.msg1Sent = true
            postReplyActions += {
                enqueueBdcRxEthernet(buildEapolKeyMsg1(wpa2))
            }
        }
    }

    private fun buildAssocReqIes(): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8).let { if (it.size > 32) it.copyOf(32) else it }
        val ies = ByteArrayOutputStream()
        ies.write(0x00)
        ies.write(ssidBytes.size)
        ies.write(ssidBytes)
        ies.write(byteArrayOf(0x01, 0x08, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24))
        if (activeWpa2Session != null || (wpaAuth and 0xC6) != 0) {
            val storedWpaIe = iovarStore["wpaie"] ?: iovarStore["bsscfg:wpaie"]
            val wpaIeBytes = when {
                storedWpaIe != null && storedWpaIe.size >= 2 &&
                    ((storedWpaIe[0].toInt() and 0xff) == 0x30 || (storedWpaIe[0].toInt() and 0xff) == 0xDD) -> storedWpaIe
                storedWpaIe != null && storedWpaIe.size >= 6 &&
                    ((storedWpaIe[4].toInt() and 0xff) == 0x30 || (storedWpaIe[4].toInt() and 0xff) == 0xDD) ->
                    storedWpaIe.copyOfRange(4, storedWpaIe.size)
                else -> buildRsnIeBytes()
            }
            ies.write(wpaIeBytes)
        }
        return ies.toByteArray()
    }

    private fun buildAssocRespIes(): ByteArray =
        byteArrayOf(0x01, 0x08, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24)

    // ================= Broadcom 802.11 EScan / Legacy Scan & WLC_E_* Event Framing =================

    private fun prepareStaAssociation(
        targetSsid: String,
        postReplyActions: MutableList<() -> Unit>,
    ): Boolean {
        val neighbors = effectiveNeighbors()
        val match = neighbors.firstOrNull { it.ssid == targetSsid }
        if (match == null && !networkEngine.config.enabled) {
            val curMac = synchronized(f2Lock) { macBytes.copyOf() }
            postReplyActions += {
                enqueueBrcmEvent(WLC_E_SET_SSID, WLC_E_STATUS_FAIL, curMac)
            }
            return false
        }
        val effectiveBssid = match?.bssid ?: CONFIGURED_AP_BSSID
        val bssidBytes = Bcm43438BtController.parseMac6(effectiveBssid)
        associatedBssid = effectiveBssid
        channel = match?.channel ?: 6
        rssiDbm = match?.rssi ?: -40
        interfaceUp = true
        mode = "STA"
        virtualPeer.setConnected(true)
        val requiresWpa2 = (wpaAuth and 0xC6) != 0 ||
            (match != null && match.security.equals("WPA2", ignoreCase = true) && wsec != 0)
        wpa2FourWayComplete = !requiresWpa2
        val staMac = synchronized(f2Lock) { macBytes.copyOf() }
        val wpa2Session = if (requiresWpa2) {
            val aNonce = ByteArray(32) { i -> (0xA5 xor (i * 13 + 7)).toByte() }
            Wpa2EapolSession(
                apMac = bssidBytes.copyOf(),
                staMac = staMac,
                ssid = targetSsid,
                passphrase = match?.passphrase?.ifEmpty { "fujiemu123" } ?: "fujiemu123",
                aNonce = aNonce,
            )
        } else {
            null
        }
        activeWpa2Session = wpa2Session
        postReplyActions += {
            enqueueBrcmEvent(WLC_E_JOIN, WLC_E_STATUS_SUCCESS, bssidBytes)
            enqueueBrcmEvent(WLC_E_AUTH, WLC_E_STATUS_SUCCESS, bssidBytes)
            enqueueBrcmEvent(WLC_E_ASSOC, WLC_E_STATUS_SUCCESS, bssidBytes)
            enqueueBrcmEvent(WLC_E_SET_SSID, WLC_E_STATUS_SUCCESS, bssidBytes)
            enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, bssidBytes, flags = WLC_EVENT_MSG_LINK)
        }
        return true
    }

    private fun disassociateSta(
        postReplyActions: MutableList<() -> Unit>? = null,
        emitAlways: Boolean = false,
    ) {
        val prev = associatedBssid
        associatedBssid = ""
        wpa2FourWayComplete = false
        activeWpa2Session = null
        virtualPeer.setConnected(false)
        if (prev.isNotEmpty() || emitAlways) {
            val bssidBytes = if (prev.isNotEmpty()) {
                runCatching { Bcm43438BtController.parseMac6(prev) }
                    .getOrElse { synchronized(f2Lock) { macBytes.copyOf() } }
            } else {
                synchronized(f2Lock) { macBytes.copyOf() }
            }
            val emit = {
                enqueueBrcmEvent(WLC_E_DISASSOC_IND, WLC_E_STATUS_SUCCESS, bssidBytes)
                enqueueBrcmEvent(WLC_E_DISASSOC, WLC_E_STATUS_SUCCESS, bssidBytes)
                enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, bssidBytes, flags = 0)
            }
            if (postReplyActions != null) postReplyActions += emit else emit()
        }
    }

    private fun runEscan(syncId: Int) {
        scanCount++
        for (ap in effectiveNeighbors()) {
            val bssInfo = buildWlBssInfo(ap)
            val escanPayload = ByteArray(12 + bssInfo.size)
            putU32Le(escanPayload, 0, escanPayload.size)
            putU32Le(escanPayload, 4, 1)
            putU16Le(escanPayload, 8, syncId)
            putU16Le(escanPayload, 10, 1)
            System.arraycopy(bssInfo, 0, escanPayload, 12, bssInfo.size)
            enqueueBrcmEvent(
                eventType = WLC_E_ESCAN_RESULT,
                status = WLC_E_STATUS_PARTIAL,
                addr = Bcm43438BtController.parseMac6(ap.bssid),
                data = escanPayload,
            )
        }
        val curMac = synchronized(f2Lock) { macBytes.copyOf() }
        val donePayload = ByteArray(12)
        putU32Le(donePayload, 0, 12)
        putU32Le(donePayload, 4, 1)
        putU16Le(donePayload, 8, syncId)
        putU16Le(donePayload, 10, 0)
        enqueueBrcmEvent(WLC_E_ESCAN_RESULT, WLC_E_STATUS_SUCCESS, curMac, data = donePayload)
        enqueueBrcmEvent(WLC_E_SCAN_COMPLETE, WLC_E_STATUS_SUCCESS, curMac)
    }

    private fun abortEscan(syncId: Int) {
        val curMac = synchronized(f2Lock) { macBytes.copyOf() }
        val abortPayload = ByteArray(12)
        putU32Le(abortPayload, 0, 12)
        putU32Le(abortPayload, 4, 1)
        putU16Le(abortPayload, 8, syncId)
        putU16Le(abortPayload, 10, 0)
        enqueueBrcmEvent(WLC_E_ESCAN_RESULT, WLC_E_STATUS_ABORT, curMac, data = abortPayload)
        enqueueBrcmEvent(WLC_E_SCAN_COMPLETE, WLC_E_STATUS_ABORT, curMac)
    }

    private fun buildLegacyScanResults(neighbors: List<VirtualWifiApNeighbor>): ByteArray {
        val entries = neighbors.map(::buildWlBssInfo)
        val totalLen = 12 + entries.sumOf { it.size }
        val out = ByteArray(totalLen)
        putU32Le(out, 0, totalLen)
        putU32Le(out, 4, WL_BSS_INFO_VERSION)
        putU32Le(out, 8, entries.size)
        var off = 12
        for (entry in entries) {
            System.arraycopy(entry, 0, out, off, entry.size)
            off += entry.size
        }
        return out
    }

    private fun buildWlBssInfo(ap: VirtualWifiApNeighbor): ByteArray {
        val ssidBytes = ap.ssid.toByteArray(Charsets.UTF_8).let { if (it.size > 32) it.copyOf(32) else it }
        val ies = ByteArrayOutputStream()
        ies.write(0x00); ies.write(ssidBytes.size); ies.write(ssidBytes)
        ies.write(byteArrayOf(0x01, 0x08, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24))
        ies.write(byteArrayOf(0x03, 0x01, (ap.channel and 0xff).toByte()))
        ies.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0x00, 0x00)) // TIM IE (DTIM period = 1)
        if (ap.security.equals("WPA2", ignoreCase = true)) {
            ies.write(
                byteArrayOf(
                    0x30, 0x14,
                    0x01, 0x00,
                    0x00, 0x0F, 0xAC.toByte(), 0x04,
                    0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x04,
                    0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x02,
                    0x00, 0x00,
                ),
            )
        }
        val ieBytes = ies.toByteArray()
        val totalLen = ((WL_BSS_INFO_HEADER_SIZE + ieBytes.size + 3) / 4) * 4
        val buf = ByteArray(totalLen)
        val bssidBytes = Bcm43438BtController.parseMac6(ap.bssid)
        val capability = if (ap.security.equals("WPA2", ignoreCase = true)) 0x0411 else 0x0401
        putU32Le(buf, 0, WL_BSS_INFO_VERSION)
        putU32Le(buf, 4, totalLen)
        System.arraycopy(bssidBytes, 0, buf, 8, 6)
        putU16Le(buf, 14, 100)
        putU16Le(buf, 16, capability)
        buf[18] = ssidBytes.size.toByte()
        System.arraycopy(ssidBytes, 0, buf, 19, ssidBytes.size)
        putU32Le(buf, 52, 8)
        byteArrayOf(0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24)
            .copyInto(buf, 56)
        putU16Le(buf, 72, 0x1000 or (ap.channel and 0xff))
        buf[76] = 1
        putU16Le(buf, 78, ap.rssi and 0xffff)
        buf[80] = (-90).toByte()
        buf[81] = 1
        buf[88] = (ap.channel and 0xff).toByte()
        putU16Le(buf, 116, WL_BSS_INFO_HEADER_SIZE)
        putU32Le(buf, 120, ieBytes.size)
        System.arraycopy(ieBytes, 0, buf, WL_BSS_INFO_HEADER_SIZE, ieBytes.size)
        return buf
    }

    private fun enqueueBrcmEvent(
        eventType: Int,
        status: Int,
        addr: ByteArray,
        flags: Int = 0,
        data: ByteArray = ByteArray(0),
    ) {
        val curMac = synchronized(f2Lock) { macBytes.copyOf() }
        val eventBytes = ByteArray(14 + 10 + 48 + data.size)
        System.arraycopy(curMac, 0, eventBytes, 0, 6)
        System.arraycopy(addr, 0, eventBytes, 6, 6)
        putU16Be(eventBytes, 12, 0x886C) // ETHER_TYPE_BRCM
        putU16Be(eventBytes, 14, 0x8001)
        putU16Be(eventBytes, 16, 6 + 48 + data.size)
        eventBytes[18] = 0x00
        eventBytes[19] = 0x00; eventBytes[20] = 0x10; eventBytes[21] = 0x18
        putU16Be(eventBytes, 22, 0x0001)
        putU16Be(eventBytes, 24, 2)
        putU16Be(eventBytes, 26, flags)
        putU32Be(eventBytes, 28, eventType)
        putU32Be(eventBytes, 32, status)
        putU32Be(eventBytes, 36, 0)
        putU32Be(eventBytes, 40, 0)
        putU32Be(eventBytes, 44, data.size)
        System.arraycopy(addr, 0, eventBytes, 48, 6)
        val ifName = "wlan0".toByteArray(Charsets.US_ASCII)
        System.arraycopy(ifName, 0, eventBytes, 54, ifName.size)
        eventBytes[70] = 0
        eventBytes[71] = 0
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, eventBytes, 72, data.size)
        }
        val bdc = ByteArray(4 + eventBytes.size)
        bdc[0] = 0x20
        System.arraycopy(eventBytes, 0, bdc, 4, eventBytes.size)
        enqueueSdpcmRx(SDPCM_EVENT_CHANNEL, bdc)
    }

    private fun handleBdcDataFrame(bdc: ByteArray) {
        if (bdc.size < 4) return
        val dataOffset = (bdc[3].toInt() and 0xff) * 4
        val ethStart = 4 + dataOffset
        if (ethStart >= bdc.size) return
        val ethFrame = bdc.copyOfRange(ethStart, bdc.size)
        if (ethFrame.size >= 18 && WifiNetworkEngine.u16Be(ethFrame, 12) == ETHER_TYPE_EAPOL) {
            handleGuestEapolTx(ethFrame)
            return
        }
        // Maintain stock SoftAP / PTP-IP peer telemetry while routing Ethernet II frames through WifiNetworkEngine
        if (apEnabled || !networkEngine.config.enabled) {
            virtualPeer.onCameraEthernetTx(ethFrame)
        } else {
            // Still update PTP-IP port observation if camera uses PTP-IP in STA mode
            if (ethFrame.size >= 54 && WifiNetworkEngine.u16Be(ethFrame, 12) == VirtualWifiPeer.ETHER_TYPE_IPV4) {
                virtualPeer.observePassiveFrame(ethFrame)
            }
        }
        if (networkEngine.config.enabled) {
            networkEngine.onGuestEthernetTx(ethFrame)
        }
    }

    // ================= IEEE 802.11i WPA2-PSK (RSN CCMP) 4-Way EAPOL Authenticator =================

    private fun buildRsnIeBytes(): ByteArray = byteArrayOf(
        0x30, 0x14,
        0x01, 0x00,
        0x00, 0x0F, 0xAC.toByte(), 0x04,
        0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x04,
        0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x02,
        0x00, 0x00,
    )

    private fun buildEapolKeyMsg1(session: Wpa2EapolSession): ByteArray {
        val frame = ByteArray(14 + 4 + 95)
        System.arraycopy(session.staMac, 0, frame, 0, 6)
        System.arraycopy(session.apMac, 0, frame, 6, 6)
        putU16Be(frame, 12, ETHER_TYPE_EAPOL)
        frame[14] = 2 // 802.1X-2004
        frame[15] = 3 // EAPOL-Key
        putU16Be(frame, 16, 95)
        frame[18] = 2 // RSN Key Descriptor
        putU16Be(frame, 19, 0x008A) // HMAC-SHA1-128 + Pairwise + KeyAck
        putU16Be(frame, 21, 16) // CCMP key length
        putU32Be(frame, 23, (session.replayCounter ushr 32).toInt())
        putU32Be(frame, 27, session.replayCounter.toInt())
        System.arraycopy(session.aNonce, 0, frame, 31, 32)
        putU16Be(frame, 111, 0)
        return frame
    }

    private fun handleGuestEapolTx(ethFrame: ByteArray) {
        val session = activeWpa2Session ?: return
        if (ethFrame.size < 14 + 4 + 95) return
        if ((ethFrame[15].toInt() and 0xff) != 3) return
        val bodyLen = WifiNetworkEngine.u16Be(ethFrame, 16)
        if (18 + bodyLen > ethFrame.size) return
        val keyInfo = WifiNetworkEngine.u16Be(ethFrame, 19)
        val keyDataLen = WifiNetworkEngine.u16Be(ethFrame, 111)
        val isPairwise = (keyInfo and 0x0008) != 0
        val hasMic = (keyInfo and 0x0100) != 0
        val isSecure = (keyInfo and 0x0200) != 0

        if (isPairwise && hasMic && !isSecure && keyDataLen > 0) {
            // EAPOL-Key Message 2/4
            val sNonce = ethFrame.copyOfRange(31, 63)
            val eapolCopy = ethFrame.copyOfRange(14, 18 + bodyLen)
            val rxMic = eapolCopy.copyOfRange(81, 97)
            for (i in 81 until 97) eapolCopy[i] = 0

            val candidatePassphrases = if (session.ssid == peerNameProvider()) {
                listOf(session.passphrase, "12345678", "88888888", "00000000", "password").distinct()
            } else {
                listOf(session.passphrase)
            }
            var matchedKck: ByteArray? = null
            var matchedKek: ByteArray? = null
            for (candidate in candidatePassphrases) {
                val pmk = deriveWpa2Pmk(candidate, session.ssid)
                val ptk = deriveWpa2Ptk(pmk, session.apMac, session.staMac, session.aNonce, sNonce)
                val kck = ptk.copyOfRange(0, 16)
                val expectedMic = hmacSha1(kck, eapolCopy).copyOfRange(0, 16)
                if (rxMic.contentEquals(expectedMic)) {
                    matchedKck = kck
                    matchedKek = ptk.copyOfRange(16, 32)
                    break
                }
            }
            if (matchedKck == null || matchedKek == null) {
                // Wrong WPA2 passphrase -> deauthenticate STA cleanly as real AP does
                enqueueBrcmEvent(WLC_E_DEAUTH_IND, WLC_E_STATUS_SUCCESS, session.apMac)
                disassociateSta(emitAlways = true)
                return
            }
            session.kck = matchedKck
            session.kek = matchedKek
            session.replayCounter += 1L
            enqueueBdcRxEthernet(buildEapolKeyMsg3(session))
        } else if (isPairwise && hasMic && isSecure) {
            // EAPOL-Key Message 4/4
            wpa2FourWayComplete = true
            wpa2HandshakeCount++
        }
    }

    private fun buildEapolKeyMsg3(session: Wpa2EapolSession): ByteArray {
        val rsnIe = buildRsnIeBytes()
        val gtkKde = ByteArray(6 + 2 + session.gtk.size)
        gtkKde[0] = 0xDD.toByte()
        gtkKde[1] = (4 + 2 + session.gtk.size).toByte()
        gtkKde[2] = 0x00; gtkKde[3] = 0x0F; gtkKde[4] = 0xAC.toByte(); gtkKde[5] = 0x01
        gtkKde[6] = 0x01 // KeyID = 1, Tx = 0
        gtkKde[7] = 0x00
        System.arraycopy(session.gtk, 0, gtkKde, 8, session.gtk.size)

        val plainRaw = rsnIe + gtkKde
        val padLen = (8 - (plainRaw.size % 8)) % 8
        val paddedPlain = ByteArray(plainRaw.size + padLen)
        System.arraycopy(plainRaw, 0, paddedPlain, 0, plainRaw.size)
        if (padLen > 0) {
            paddedPlain[plainRaw.size] = 0xDD.toByte()
        }
        val wrapped = aesKeyWrap(session.kek, paddedPlain)

        val bodyLen = 95 + wrapped.size
        val frame = ByteArray(14 + 4 + bodyLen)
        System.arraycopy(session.staMac, 0, frame, 0, 6)
        System.arraycopy(session.apMac, 0, frame, 6, 6)
        putU16Be(frame, 12, ETHER_TYPE_EAPOL)
        frame[14] = 2 // 802.1X-2004
        frame[15] = 3 // EAPOL-Key
        putU16Be(frame, 16, bodyLen)
        frame[18] = 2 // RSN Key Descriptor
        // KeyInfo = HMAC-SHA1-128 (2) | Pairwise (0x08) | Install (0x40) | Ack (0x80) | MIC (0x100) | Secure (0x200) | EncKeyData (0x1000)
        putU16Be(frame, 19, 0x13CA)
        putU16Be(frame, 21, 16)
        putU32Be(frame, 23, (session.replayCounter ushr 32).toInt())
        putU32Be(frame, 27, session.replayCounter.toInt())
        System.arraycopy(session.aNonce, 0, frame, 31, 32)
        // RSC = 0, KeyID = 0, MIC = 0 initially
        putU16Be(frame, 111, wrapped.size)
        System.arraycopy(wrapped, 0, frame, 113, wrapped.size)

        val mic = hmacSha1(session.kck, frame.copyOfRange(14, frame.size))
        System.arraycopy(mic, 0, frame, 14 + 81, 16)
        return frame
    }

    private fun deriveWpa2Pmk(passphrase: String, ssidStr: String): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), ssidStr.toByteArray(Charsets.UTF_8), 4096, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun deriveWpa2Ptk(
        pmk: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
        aNonce: ByteArray,
        sNonce: ByteArray,
    ): ByteArray {
        fun lexLess(a: ByteArray, b: ByteArray): Boolean {
            for (i in a.indices) {
                val ai = a[i].toInt() and 0xff
                val bi = b[i].toInt() and 0xff
                if (ai != bi) return ai < bi
            }
            return false
        }
        val data = ByteArray(6 + 6 + 32 + 32)
        val (minMac, maxMac) = if (lexLess(apMac, staMac)) apMac to staMac else staMac to apMac
        val (minNonce, maxNonce) = if (lexLess(aNonce, sNonce)) aNonce to sNonce else sNonce to aNonce
        System.arraycopy(minMac, 0, data, 0, 6)
        System.arraycopy(maxMac, 0, data, 6, 6)
        System.arraycopy(minNonce, 0, data, 12, 32)
        System.arraycopy(maxNonce, 0, data, 44, 32)

        val label = "Pairwise key expansion\u0000".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        var counter = 0
        while (out.size() < 48) {
            val input = ByteArray(label.size + data.size + 1)
            System.arraycopy(label, 0, input, 0, label.size)
            System.arraycopy(data, 0, input, label.size, data.size)
            input[input.size - 1] = counter.toByte()
            out.write(hmacSha1(pmk, input))
            counter++
        }
        return out.toByteArray().copyOf(48)
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun aesKeyWrap(kek: ByteArray, plain: ByteArray): ByteArray {
        require(plain.size >= 16 && plain.size % 8 == 0)
        val n = plain.size / 8
        val a = ByteArray(8) { 0xA6.toByte() }
        val r = Array(n) { i -> plain.copyOfRange(i * 8, (i + 1) * 8) }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"))
        val block = ByteArray(16)
        for (j in 0..5) {
            for (i in 0 until n) {
                System.arraycopy(a, 0, block, 0, 8)
                System.arraycopy(r[i], 0, block, 8, 8)
                val b = cipher.doFinal(block)
                val t = (n * j + (i + 1)).toLong()
                System.arraycopy(b, 0, a, 0, 8)
                for (k in 0..7) {
                    a[7 - k] = (a[7 - k].toInt() xor ((t ushr (k * 8)) and 0xffL).toInt()).toByte()
                }
                System.arraycopy(b, 8, r[i], 0, 8)
            }
        }
        val out = ByteArray((n + 1) * 8)
        System.arraycopy(a, 0, out, 0, 8)
        for (i in 0 until n) {
            System.arraycopy(r[i], 0, out, (i + 1) * 8, 8)
        }
        return out
    }

    internal fun enqueueBdcRxEthernet(ethFrame: ByteArray) {
        val bdc = ByteArray(4 + ethFrame.size)
        bdc[0] = 0x20 // BDC protocol version 2
        bdc[1] = 0x00
        bdc[2] = 0x00
        bdc[3] = 0x00
        System.arraycopy(ethFrame, 0, bdc, 4, ethFrame.size)
        enqueueSdpcmRx(SDPCM_DATA_CHANNEL, bdc)
    }

    private fun enqueueSdpcmRx(channel: Int, payload: ByteArray) {
        val hdrLen = 12
        val totalLen = hdrLen + payload.size
        val frame = ByteArray(totalLen)
        putU16Le(frame, 0, totalLen)
        putU16Le(frame, 2, totalLen.inv() and 0xffff)
        synchronized(f2Lock) {
            frame[4] = (rxSeq++ and 0xff).toByte()
            frame[5] = (channel and 0x0f).toByte()
            frame[6] = 0
            frame[7] = hdrLen.toByte()
            frame[8] = 0
            frame[9] = ((txSeqExpected + 32) and 0xff).toByte()
            System.arraycopy(payload, 0, frame, hdrLen, payload.size)
            f2RxQueue.addLast(frame)
            sdioIntStatus = sdioIntStatus or I_HMB_FRAME_IND
        }
        syncCardIrq()
    }

    private fun readF2Bytes(length: Int): ByteArray {
        val out = ByteArray(length)
        synchronized(f2Lock) {
            val active = f2PendingReadFrame ?: f2RxQueue.removeFirstOrNull()?.also {
                if (it.size >= 10) {
                    it[9] = ((txSeqExpected + 32) and 0xff).toByte()
                }
                f2PendingReadFrame = it
                f2PendingReadOffset = 0
            }
            if (active != null) {
                val avail = active.size - f2PendingReadOffset
                val take = minOf(avail, length)
                System.arraycopy(active, f2PendingReadOffset, out, 0, take)
                f2PendingReadOffset += take
                if (f2PendingReadOffset >= active.size) {
                    f2PendingReadFrame = null
                    f2PendingReadOffset = 0
                }
            }
            if (f2PendingReadFrame == null && f2RxQueue.isEmpty()) {
                sdioIntStatus = sdioIntStatus and I_HMB_FRAME_IND.inv()
            }
        }
        syncCardIrq()
        return out
    }

    private fun u32Bytes(v: Int): ByteArray = ByteArray(4).also { putU32Le(it, 0, v) }

    private fun buildCisRom(mac: ByteArray): ByteArray {
        val rom = ByteArray(0x100) { 0xFF.toByte() }
        val common = ByteArrayOutputStream()
        common.write(0x20); common.write(0x04)
        common.write(BROADCOM_MANFID and 0xff); common.write((BROADCOM_MANFID ushr 8) and 0xff)
        common.write(sdioProdId and 0xff); common.write((sdioProdId ushr 8) and 0xff)
        common.write(0x22); common.write(0x04); common.write(0x00); common.write(0x00); common.write(0x02); common.write(0x5A)
        common.write(0x80); common.write(0x07); common.write(0x19)
        common.write(mac)
        common.write(0xFF)
        val commonBytes = common.toByteArray()
        System.arraycopy(commonBytes, 0, rom, 0x00, commonBytes.size)

        val f1Cis = byteArrayOf(0x22, 0x04, 0x01, 0x00, 0x02, 0x00, 0xFF.toByte())
        System.arraycopy(f1Cis, 0, rom, 0x40, f1Cis.size)
        val f2Cis = byteArrayOf(0x22, 0x04, 0x01, 0x00, 0x02, 0x00, 0xFF.toByte())
        System.arraycopy(f2Cis, 0, rom, 0x60, f2Cis.size)
        return rom
    }

    private fun buildAxiErom(): IntArray {
        fun ciA(coreId: Int): Int = ((0x4BF shl 20) or ((coreId and 0xfff) shl 8) or 0x01)
        // CIB: rev[31:24], nsw=1 (bit 19), nmw=0 (bits 18:14), nsp=1 (bit 9), nmp=0 (bits 8:4), ER_CI=0x01
        fun ciB(rev: Int): Int = ((rev and 0xff) shl 24) or (1 shl 19) or (1 shl 9) or 0x01
        fun addrDesc(base: Long, isWrapper: Boolean): Int =
            (base.toInt() and 0xfffff000.toInt()) or (if (isWrapper) (0x2 shl 6) else 0x0) or 0x05

        return intArrayOf(
            ciA(0x800), ciB(48), addrDesc(CHIPCOMMON_BASE, false), addrDesc(CHIPCOMMON_WRAP_BASE, true),
            ciA(0x812), ciB(43), addrDesc(D11_BASE, false), addrDesc(D11_WRAP_BASE, true),
            ciA(0x829), ciB(22), addrDesc(SDIO_CORE_BASE, false), addrDesc(SDIO_WRAP_BASE, true),
            ciA(0x82A), ciB(7), addrDesc(ARM_CM3_BASE, false), addrDesc(ARM_CM3_WRAP_BASE, true),
            ciA(0x80E), ciB(24), addrDesc(SOCRAM_CORE_BASE, false), addrDesc(SOCRAM_WRAP_BASE, true),
            0x0000000F,
        )
    }

    fun state(): Map<String, Any?> {
        val netStatus = getNetworkStatus()
        val netConfig = getNetworkConfig()
        val pendingF2 = synchronized(f2Lock) { f2RxQueue.size }
        val effectiveIp = virtualPeer.observedCameraIp.ifEmpty { netStatus.guestIp }
        return linkedMapOf(
            "wifiPowerOn" to wifiPowerOn,
            "firmwareLoaded" to firmwareLoaded,
            "firmwareBytesWritten" to firmwareBytesWritten,
            "firmwareSha256" to firmwareSha256,
            "clmLoaded" to clmLoaded,
            "clmBytesWritten" to clmBytesWritten,
            "mode" to mode,
            "interfaceUp" to interfaceUp,
            "apEnabled" to apEnabled,
            "ssid" to ssid,
            "macAddress" to macAddressString(),
            "ipAddress" to effectiveIp,
            "channel" to channel,
            "rssi" to rssiDbm,
            "wsec" to wsec,
            "wpaAuth" to wpaAuth,
            "countryCode" to countryCode,
            "connectedClients" to if (virtualPeer.connected || netStatus.linkUp) 1 else 0,
            "ptpIpReady" to virtualPeer.ptpIpReady,
            "sdioProdId" to "0x%04X".format(sdioProdId),
            "sdioRca" to rca,
            "sdioSelected" to selected,
            "sdioCmdCount" to sdioCmdCount,
            "cmd52Count" to cmd52Count,
            "cmd53Count" to cmd53Count,
            "ioctlCount" to ioctlCount,
            "iovarCount" to iovarCount,
            "scanCount" to scanCount,
            "associatedBssid" to associatedBssid,
            "wpa2FourWayComplete" to wpa2FourWayComplete,
            "wpa2HandshakeCount" to wpa2HandshakeCount,
            "discoverableNetworks" to effectiveNeighbors().map { ap ->
                mapOf("ssid" to ap.ssid, "bssid" to ap.bssid, "channel" to ap.channel, "rssi" to ap.rssi, "security" to ap.security)
            },
            "f2RxPendingFrames" to pendingF2,
            "virtualPeer" to virtualPeer.state(),
            "networkConfig" to netConfig.toMap(),
            "networkStatus" to netStatus.toMap(),
        )
    }

    fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
        "wifiPowerOn" to wifiPowerOn,
        "rca" to rca,
        "selected" to selected,
        "sdioCmdCount" to sdioCmdCount,
        "cmd52Count" to cmd52Count,
        "cmd53Count" to cmd53Count,
        "ioEnable" to ioEnable,
        "intEnable" to intEnable,
        "busIfCtrl" to busIfCtrl,
        "highSpeedCtrl" to highSpeedCtrl,
        "funcBlockSize" to funcBlockSize.toList(),
        "sbAddrLow" to sbAddrLow,
        "sbAddrMid" to sbAddrMid,
        "sbAddrHigh" to sbAddrHigh,
        "chipClkCsr" to chipClkCsr,
        "sdioIntStatus" to sdioIntStatus,
        "sdioHostIntMask" to sdioHostIntMask,
        "armResetCtrl" to armResetCtrl,
        "firmwareLoaded" to firmwareLoaded,
        "firmwareBytesWritten" to firmwareBytesWritten,
        "firmwareSha256" to firmwareSha256,
        "clmLoaded" to clmLoaded,
        "clmBytesWritten" to clmBytesWritten,
        "macAddress" to macAddressString(),
        "mode" to mode,
        "interfaceUp" to interfaceUp,
        "apEnabled" to apEnabled,
        "ssid" to ssid,
        "associatedBssid" to associatedBssid,
        "channel" to channel,
        "wsec" to wsec,
        "wpaAuth" to wpaAuth,
        "countryCode" to countryCode,
        "ioctlCount" to ioctlCount,
        "iovarCount" to iovarCount,
        "txSeqExpected" to synchronized(f2Lock) { txSeqExpected },
        "rxSeq" to synchronized(f2Lock) { rxSeq },
        "virtualPeer" to virtualPeer.saveSnapshot(),
    )

    fun restoreSnapshot(data: Map<String, Any?>) {
        val nextMac = Bcm43438BtController.parseMac6(data["macAddress"] as? String ?: Bcm43438BtController.formatMac6(initialMacBytes))
        wifiPowerOn = data["wifiPowerOn"] as? Boolean ?: true
        rca = (data["rca"] as? Number)?.toInt() ?: 0
        selected = data["selected"] as? Boolean ?: false
        sdioCmdCount = (data["sdioCmdCount"] as? Number)?.toLong() ?: 0L
        cmd52Count = (data["cmd52Count"] as? Number)?.toLong() ?: 0L
        cmd53Count = (data["cmd53Count"] as? Number)?.toLong() ?: 0L
        ioEnable = (data["ioEnable"] as? Number)?.toInt() ?: 0
        intEnable = (data["intEnable"] as? Number)?.toInt() ?: 0
        busIfCtrl = (data["busIfCtrl"] as? Number)?.toInt() ?: 0x02
        highSpeedCtrl = (data["highSpeedCtrl"] as? Number)?.toInt() ?: 0x01
        val bs = data["funcBlockSize"] as? List<*>
        if (bs != null && bs.size == 3) {
            for (i in 0..2) funcBlockSize[i] = (bs[i] as Number).toInt()
        }
        sbAddrLow = (data["sbAddrLow"] as? Number)?.toInt() ?: 0
        sbAddrMid = (data["sbAddrMid"] as? Number)?.toInt() ?: 0
        sbAddrHigh = (data["sbAddrHigh"] as? Number)?.toInt() ?: 0x18
        chipClkCsr = (data["chipClkCsr"] as? Number)?.toInt() ?: 0
        armResetCtrl = (data["armResetCtrl"] as? Number)?.toInt() ?: 1
        firmwareLoaded = data["firmwareLoaded"] as? Boolean ?: false
        firmwareBytesWritten = (data["firmwareBytesWritten"] as? Number)?.toLong() ?: 0L
        firmwareSha256 = data["firmwareSha256"] as? String ?: ""
        clmLoaded = data["clmLoaded"] as? Boolean ?: false
        clmBytesWritten = (data["clmBytesWritten"] as? Number)?.toLong() ?: 0L
        synchronized(f2Lock) {
            nextMac.copyInto(macBytes)
            cisRom = buildCisRom(macBytes)
            sdioIntStatus = (data["sdioIntStatus"] as? Number)?.toLong() ?: 0L
            sdioHostIntMask = (data["sdioHostIntMask"] as? Number)?.toLong() ?: 0L
            txSeqExpected = ((data["txSeqExpected"] as? Number)?.toInt() ?: 0) and 0xff
            rxSeq = ((data["rxSeq"] as? Number)?.toInt() ?: 0) and 0xff
            f2RxQueue.clear()
            f2PendingReadFrame = null
            f2PendingReadOffset = 0
        }
        mode = data["mode"] as? String ?: "OFF"
        interfaceUp = data["interfaceUp"] as? Boolean ?: false
        apEnabled = data["apEnabled"] as? Boolean ?: false
        ssid = data["ssid"] as? String ?: ""
        associatedBssid = data["associatedBssid"] as? String ?: ""
        channel = (data["channel"] as? Number)?.toInt() ?: 6
        wsec = (data["wsec"] as? Number)?.toInt() ?: 0
        wpaAuth = (data["wpaAuth"] as? Number)?.toInt() ?: 0
        countryCode = data["countryCode"] as? String ?: "US"
        ioctlCount = (data["ioctlCount"] as? Number)?.toLong() ?: 0L
        iovarCount = (data["iovarCount"] as? Number)?.toLong() ?: 0L
        @Suppress("UNCHECKED_CAST")
        (data["virtualPeer"] as? Map<String, Any?>)?.let { virtualPeer.restoreSnapshot(it) }
        syncCardIrq()
    }

    override fun close() {
        networkEngine.close()
        virtualPeer.close()
    }
}
