package com.github.nyabanban.fuji.emulator.components.bcm43438

import com.github.nyabanban.fuji.emulator.api.SdioCardPort
import com.github.nyabanban.fuji.emulator.api.SdioCommandResult
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal data class VirtualWifiApNeighbor(
    val ssid: String,
    val bssid: String,
    val channel: Int = 6,
    val rssi: Int = -42,
    val security: String = "WPA2", // "WPA2" or "OPEN"
    val passphrase: String = "fujiemu123",
)

/**
 * Datasheet-faithful Broadcom BCM43430A1 (BCM43438A1 Wi-Fi die) FullMAC SDIO v3.0 device.
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
 *   `WLC_SET_AP`, `WLC_SET_SSID`, `WLC_SCAN`, `WLC_SCAN_RESULTS`, `escan`, `cur_etheraddr`, `ver`,
 *   `bsscfg:ssid`, `wsec`, `wpa_auth`, `chanspec`, `clmload`, etc.), authentic Broadcom `WLC_E_*`
 *   event frames (`WLC_E_ESCAN_RESULT`, `WLC_E_SCAN_COMPLETE`, `WLC_E_AUTH`, `WLC_E_ASSOC`,
 *   `WLC_E_SET_SSID`, `WLC_E_LINK`), and `BDC` Ethernet frame TX/RX wired to [VirtualWifiPeer].
 *
 * Zero hardcoded camera SSID/name strings: faithfully reports whatever SSID/MAC/mode the guest
 * Wi-Fi driver (`dhd_threadx` + `hostapd` / `wpa_supplicant`) configures, and scans nearby RF
 * networks from [neighborsProvider].
 */
internal class Bcm43430WifiController(
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
        const val WLC_SET_INFRA = 20
        const val WLC_GET_INFRA = 19
        const val WLC_GET_AUTH = 21
        const val WLC_SET_AUTH = 22
        const val WLC_GET_BSSID = 23
        const val WLC_GET_SSID = 25
        const val WLC_SET_SSID = 26
        const val WLC_GET_CHANNEL = 29
        const val WLC_SET_CHANNEL = 30
        const val WLC_SCAN = 50
        const val WLC_SCAN_RESULTS = 51
        const val WLC_GET_PM = 85
        const val WLC_SET_PM = 86
        const val WLC_GET_AP = 117
        const val WLC_SET_AP = 118
        const val WLC_GET_WSEC = 133
        const val WLC_SET_WSEC = 134
        const val WLC_GET_WPA_AUTH = 164
        const val WLC_SET_WPA_AUTH = 165
        const val WLC_GET_VAR = 262
        const val WLC_SET_VAR = 263
        const val WLC_SET_WSEC_PMK = 268
        const val WLC_IOCTL_MAGIC = 0x14E46C77

        // Broadcom WLC_E_* Async Event types
        const val WLC_E_SET_SSID = 0
        const val WLC_E_AUTH = 3
        const val WLC_E_ASSOC = 7
        const val WLC_E_LINK = 16
        const val WLC_E_SCAN_COMPLETE = 26
        const val WLC_E_ESCAN_RESULT = 69
        const val WLC_E_STATUS_SUCCESS = 0
        const val WLC_E_STATUS_PARTIAL = 8
        const val WLC_EVENT_MSG_LINK = 0x0001
        const val WL_BSS_INFO_VERSION = 109
        const val WL_BSS_INFO_HEADER_SIZE = 124

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
    private var sdioIntStatus: Long = 0L
    private var sdioHostIntMask: Long = 0L
    private var toHostMailboxData: Long = 0L
    private var toSbMailboxData: Long = 0L
    private var armResetCtrl: Int = 1 // Starts in reset until guest downloads FW & releases reset
    private var armIoCtrl: Int = 0
    private var socramResetCtrl: Int = 0
    private var socramIoCtrl: Int = 0

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

    // FullMAC Wi-Fi MAC / SoftAP / STA state (zero hardcoded camera names!)
    private val initialMacBytes = initialMac.copyOf(6)
    private val macBytes = initialMac.copyOf(6)
    var mode: String = "OFF" // OFF | AP | STA
        private set
    var interfaceUp: Boolean = false
        private set
    var apEnabled: Boolean = false
        private set
    var ssid: String = ""
        private set
    var channel: Int = 1
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
    var associatedBssid: String = ""
        private set
    private val iovarStore = linkedMapOf<String, ByteArray>()

    // Function 2 SDPCM RX queue
    private var txSeqExpected: Int = 0
    private var rxSeq: Int = 0
    private val f2RxQueue = ArrayDeque<ByteArray>()
    private var f2PendingReadFrame: ByteArray? = null
    private var f2PendingReadOffset: Int = 0

    // Deterministic loopback + opt-in TCP bridge peer
    val virtualPeer = VirtualWifiPeer(
        peerNameProvider = peerNameProvider,
        rxEthernetSink = { ethFrame -> enqueueBdcRxEthernet(ethFrame) },
    )

    private val cisRom: ByteArray = buildCisRom()
    private val axiErom: IntArray = buildAxiErom()

    fun macAddressString(): String = Bcm43438BtController.formatMac6(macBytes)

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
        armResetCtrl = 1
        firmwareLoaded = false
        firmwareBytesWritten = 0L
        firmwareSha256 = ""
        clmLoaded = false
        clmBytesWritten = 0L
        interfaceUp = false
        apEnabled = false
        mode = "OFF"
        ssid = ""
        f2RxQueue.clear()
        f2PendingReadFrame = null
        f2PendingReadOffset = 0
        sdioIntStatus = 0L
        syncCardIrq()
    }

    override fun reset() {
        rca = 0
        selected = false
        ioEnable = 0
        intEnable = 0
        sdioIntStatus = 0L
        f2PendingReadFrame = null
        f2PendingReadOffset = 0
        syncCardIrq()
    }

    override fun isCardInterruptAsserted(): Boolean {
        if (!wifiPowerOn) return false
        val masterEn = (intEnable and 0x01) != 0
        val f2Pending = (f2RxQueue.isNotEmpty() || f2PendingReadFrame != null) && (intEnable and 0x04) != 0
        val f1Pending = ((sdioIntStatus and sdioHostIntMask) != 0L) && (intEnable and 0x02) != 0
        return masterEn && (f2Pending || f1Pending)
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
            // F1 ready immediately when enabled; F2 ready when enabled (and/or firmware launched)
            var rdy = ioEnable and 0x02
            if ((ioEnable and 0x04) != 0) rdy = rdy or 0x04
            rdy
        }
        0x04 -> intEnable
        0x05 -> {
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
            if (addr in 0x1000 until (0x1000 + cisRom.size)) {
                cisRom[addr - 0x1000].toInt() and 0xff
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
            SBSDIO_FUNC1_SLEEPCSR -> sleepCsr = v
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
            CHIPCOMMON_BASE + 0x004L -> 0x00000000L // capabilities
            CHIPCOMMON_BASE + 0x0FCL -> EROM_BASE
            SDIO_CORE_BASE + 0x020L -> {
                var st = sdioIntStatus
                if (f2RxQueue.isNotEmpty() || f2PendingReadFrame != null) st = st or I_HMB_FRAME_IND
                st
            }
            SDIO_CORE_BASE + 0x024L -> sdioHostIntMask
            SDIO_CORE_BASE + 0x044L -> 0L
            SDIO_CORE_BASE + 0x048L -> toSbMailboxData
            SDIO_CORE_BASE + 0x04CL -> toHostMailboxData
            SOCRAM_CORE_BASE + 0x000L -> 0x00000808L // 8 banks, rev 8
            SOCRAM_CORE_BASE + 0x040L -> 0x00000007L // 64 KiB per bank
            ARM_CM3_WRAP_BASE + 0x408L -> armIoCtrl.toLong() and 0xffff_ffffL
            ARM_CM3_WRAP_BASE + 0x800L -> armResetCtrl.toLong() and 0xffff_ffffL
            SOCRAM_WRAP_BASE + 0x408L -> socramIoCtrl.toLong() and 0xffff_ffffL
            SOCRAM_WRAP_BASE + 0x800L -> socramResetCtrl.toLong() and 0xffff_ffffL
            else -> 0L
        }
    }

    private fun writeBackplaneBytes(bpAddr: Long, data: ByteArray) {
        // SOCRAM firmware + NVRAM download (0x00000000..0x0007FFFF)
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
            SDIO_CORE_BASE + 0x020L -> sdioIntStatus = sdioIntStatus and word.inv() // W1C
            SDIO_CORE_BASE + 0x024L -> sdioHostIntMask = word
            SDIO_CORE_BASE + 0x048L -> toSbMailboxData = word
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
        val md = MessageDigest.getInstance("SHA-256")
        val hashLen = minOf(firmwareBytesWritten.toInt().coerceAtLeast(0), SOCRAM_SIZE)
        if (hashLen > 0) {
            md.update(socram, 0, hashLen)
            firmwareSha256 = Bcm43438BtController.bytesToHex(md.digest())
        }
        // Parse optional NVRAM macaddr=xx:xx:xx:xx:xx:xx at top of SOCRAM (standard Broadcom nvram trailer)
        parseNvramFromSocram()
    }

    private fun parseNvramFromSocram() {
        // Broadcom puts a 4-byte token count at end of SOCRAM preceded by NUL-separated key=value strings
        val tailStart = maxOf(0, SOCRAM_SIZE - 4096)
        val tailStr = String(socram, tailStart, SOCRAM_SIZE - tailStart, Charsets.ISO_8859_1)
        val idx = tailStr.indexOf("macaddr=")
        if (idx >= 0) {
            val end = tailStr.indexOf('\u0000', idx).let { if (it < 0) minOf(tailStr.length, idx + 25) else it }
            val macText = tailStr.substring(idx + "macaddr=".length, end).trim()
            runCatching {
                Bcm43438BtController.parseMac6(macText).copyInto(macBytes)
            }
        }
    }

    // ================= Function 2 SDPCM / CDC / IOVAR / BDC =================

    private fun handleF2Write(raw: ByteArray) {
        if (raw.size < 12) return
        val frameLen = u16Le(raw, 0)
        val frameLenInv = u16Le(raw, 2)
        if (frameLen < 12 || frameLen > raw.size) return
        if (((frameLen xor frameLenInv) and 0xffff) != 0xffff) return
        val channel = raw[5].toInt() and 0x0f
        val hdrLen = raw[7].toInt() and 0xff
        if (hdrLen < 12 || hdrLen > frameLen) return
        txSeqExpected = (raw[4].toInt() + 1) and 0xff

        val payload = raw.copyOfRange(hdrLen, frameLen)
        when (channel) {
            SDPCM_CONTROL_CHANNEL -> handleCdcControlFrame(payload)
            SDPCM_DATA_CHANNEL -> handleBdcDataFrame(payload)
        }
    }

    private fun handleCdcControlFrame(cdc: ByteArray) {
        if (cdc.size < 16) return
        val cmd = u32Le(cdc, 0)
        val dataLen = u32Le(cdc, 4)
        val flags = u32Le(cdc, 8)
        val reqId = (flags ushr CDC_F_ID_SHIFT) and 0xffff
        val isSet = (flags and CDC_F_SET) != 0
        val payload = if (cdc.size > 16) cdc.copyOfRange(16, minOf(cdc.size, 16 + maxOf(dataLen, 0))) else ByteArray(0)
        ioctlCount++

        var status = 0
        var replyPayload: ByteArray = payload.copyOf()

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
                mode = "OFF"
                replyPayload = ByteArray(0)
            }
            WLC_GET_INFRA -> replyPayload = u32Bytes(infra)
            WLC_SET_INFRA -> {
                if (payload.size >= 4) infra = u32Le(payload, 0)
            }
            WLC_GET_AUTH -> replyPayload = u32Bytes(auth)
            WLC_SET_AUTH -> {
                if (payload.size >= 4) auth = u32Le(payload, 0)
            }
            WLC_GET_BSSID -> replyPayload = if (!apEnabled && associatedBssid.isNotEmpty()) {
                Bcm43438BtController.parseMac6(associatedBssid)
            } else {
                macBytes.copyOf()
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
                        associateWithNeighborSsid(ssid)
                    } else if (apEnabled && ssid.isNotEmpty()) {
                        enqueueBrcmEvent(WLC_E_SET_SSID, WLC_E_STATUS_SUCCESS, macBytes)
                        enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, macBytes, flags = WLC_EVENT_MSG_LINK)
                    }
                }
            }
            WLC_SCAN -> {
                scanCount++
                replyPayload = ByteArray(0)
                enqueueBrcmEvent(WLC_E_SCAN_COMPLETE, WLC_E_STATUS_SUCCESS, macBytes)
            }
            WLC_SCAN_RESULTS -> {
                replyPayload = buildLegacyScanResults(neighborsProvider())
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
            WLC_GET_WPA_AUTH -> replyPayload = u32Bytes(wpaAuth)
            WLC_SET_WPA_AUTH -> {
                if (payload.size >= 4) wpaAuth = u32Le(payload, 0)
            }
            WLC_SET_WSEC_PMK -> Unit
            WLC_GET_VAR -> {
                val (name, argData) = parseIovar(payload)
                iovarCount++
                replyPayload = handleIovarGet(name, argData)
            }
            WLC_SET_VAR -> {
                val (name, argData) = parseIovar(payload)
                iovarCount++
                handleIovarSet(name, argData)
            }
            else -> {
                if (!isSet && replyPayload.isEmpty() && dataLen > 0) {
                    replyPayload = ByteArray(minOf(dataLen, 256))
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
    }

    private fun parseIovar(payload: ByteArray): Pair<String, ByteArray> {
        val nul = payload.indexOf(0)
        if (nul < 0) return String(payload, Charsets.US_ASCII) to ByteArray(0)
        val name = String(payload, 0, nul, Charsets.US_ASCII)
        val rest = if (nul + 1 < payload.size) payload.copyOfRange(nul + 1, payload.size) else ByteArray(0)
        return name to rest
    }

    private fun handleIovarGet(name: String, argData: ByteArray): ByteArray = when (name) {
        "cur_etheraddr" -> macBytes.copyOf()
        "ver" -> "wl0: BCM43430A1 FullMAC 7.45.98\n\u0000".toByteArray(Charsets.US_ASCII)
        "cap" -> "ap sta wme 802.11n bss_max=2\u0000".toByteArray(Charsets.US_ASCII)
        "clmload_status" -> u32Bytes(0)
        "country" -> countryCode.toByteArray(Charsets.US_ASCII).copyOf(4)
        "chanspec" -> u32Bytes(channel and 0xff)
        "wsec" -> u32Bytes(wsec)
        "wpa_auth" -> u32Bytes(wpaAuth)
        "bsscfg:cur_etheraddr" -> macBytes.copyOf()
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

    private fun handleIovarSet(name: String, argData: ByteArray) {
        iovarStore[name] = argData.copyOf()
        when (name) {
            "cur_etheraddr" -> if (argData.size >= 6) System.arraycopy(argData, 0, macBytes, 0, 6)
            "bsscfg:cur_etheraddr" -> if (argData.size >= 10) System.arraycopy(argData, 4, macBytes, 0, 6)
            "ssid" -> {
                if (argData.size >= 4) {
                    val n = minOf(u32Le(argData, 0).coerceAtLeast(0), minOf(32, argData.size - 4))
                    ssid = String(argData, 4, n, Charsets.UTF_8)
                }
            }
            "bsscfg:ssid" -> {
                // bsscfg_idx (4B) + wlc_ssid_t (len 4B + bytes 32B)
                if (argData.size >= 8) {
                    val n = minOf(u32Le(argData, 4).coerceAtLeast(0), minOf(32, argData.size - 8))
                    ssid = String(argData, 8, n, Charsets.UTF_8)
                }
            }
            "wsec" -> if (argData.size >= 4) wsec = u32Le(argData, 0)
            "bsscfg:wsec" -> if (argData.size >= 8) wsec = u32Le(argData, 4)
            "wpa_auth" -> if (argData.size >= 4) wpaAuth = u32Le(argData, 0)
            "bsscfg:wpa_auth" -> if (argData.size >= 8) wpaAuth = u32Le(argData, 4)
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
            "escan" -> {
                // wl_escan_params_t: version(4B), action(2B: 1=START, 2=ABORT), sync_id(2B), wl_scan_params_t...
                val action = if (argData.size >= 6) u16Le(argData, 4) else 1
                val syncId = if (argData.size >= 8) u16Le(argData, 6) else 1
                if (action == 1) {
                    runEscan(syncId)
                }
            }
            "PM" -> if (argData.size >= 4) pmMode = u32Le(argData, 0)
            "bus:txglomalign", "assoc_listen", "bcn_timeout", "assoc_retry_max", "allmulti", "mcast_list" -> Unit
        }
    }

    // ================= Broadcom 802.11 EScan / Legacy Scan & WLC_E_* Event Framing =================

    private fun associateWithNeighborSsid(targetSsid: String) {
        val match = neighborsProvider().firstOrNull { it.ssid == targetSsid } ?: return
        val bssidBytes = Bcm43438BtController.parseMac6(match.bssid)
        associatedBssid = match.bssid
        channel = match.channel
        interfaceUp = true
        mode = "STA"
        virtualPeer.setConnected(true)
        enqueueBrcmEvent(WLC_E_AUTH, WLC_E_STATUS_SUCCESS, bssidBytes)
        enqueueBrcmEvent(WLC_E_ASSOC, WLC_E_STATUS_SUCCESS, bssidBytes)
        enqueueBrcmEvent(WLC_E_SET_SSID, WLC_E_STATUS_SUCCESS, bssidBytes)
        enqueueBrcmEvent(WLC_E_LINK, WLC_E_STATUS_SUCCESS, bssidBytes, flags = WLC_EVENT_MSG_LINK)
    }

    private fun runEscan(syncId: Int) {
        scanCount++
        for (ap in neighborsProvider()) {
            val bssInfo = buildWlBssInfo(ap)
            val escanPayload = ByteArray(12 + bssInfo.size)
            putU32Le(escanPayload, 0, escanPayload.size) // buflen
            putU32Le(escanPayload, 4, 1) // version
            putU16Le(escanPayload, 8, syncId)
            putU16Le(escanPayload, 10, 1) // bss_count = 1
            System.arraycopy(bssInfo, 0, escanPayload, 12, bssInfo.size)
            enqueueBrcmEvent(
                eventType = WLC_E_ESCAN_RESULT,
                status = WLC_E_STATUS_PARTIAL,
                addr = Bcm43438BtController.parseMac6(ap.bssid),
                data = escanPayload,
            )
        }
        // Final EScan completion terminator (bss_count=0, status=SUCCESS) + WLC_E_SCAN_COMPLETE
        val donePayload = ByteArray(12)
        putU32Le(donePayload, 0, 12)
        putU32Le(donePayload, 4, 1)
        putU16Le(donePayload, 8, syncId)
        putU16Le(donePayload, 10, 0)
        enqueueBrcmEvent(WLC_E_ESCAN_RESULT, WLC_E_STATUS_SUCCESS, macBytes, data = donePayload)
        enqueueBrcmEvent(WLC_E_SCAN_COMPLETE, WLC_E_STATUS_SUCCESS, macBytes)
    }

    private fun buildLegacyScanResults(neighbors: List<VirtualWifiApNeighbor>): ByteArray {
        val entries = neighbors.map(::buildWlBssInfo)
        val totalLen = 12 + entries.sumOf { it.size }
        val out = ByteArray(totalLen)
        putU32Le(out, 0, totalLen) // buflen
        putU32Le(out, 4, WL_BSS_INFO_VERSION) // version
        putU32Le(out, 8, entries.size) // count
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
        // IE 0x00: SSID
        ies.write(0x00); ies.write(ssidBytes.size); ies.write(ssidBytes)
        // IE 0x01: Supported Rates (1, 2, 5.5, 11, 6, 9, 12, 18 Mbps)
        ies.write(byteArrayOf(0x01, 0x08, 0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24))
        // IE 0x03: DS Parameter Set (channel)
        ies.write(byteArrayOf(0x03, 0x01, (ap.channel and 0xff).toByte()))
        // IE 0x30: RSN IE (WPA2-PSK CCMP) when security == WPA2
        if (ap.security.equals("WPA2", ignoreCase = true)) {
            ies.write(
                byteArrayOf(
                    0x30, 0x14, // RSN tag, len 20
                    0x01, 0x00, // Version 1
                    0x00, 0x0F, 0xAC.toByte(), 0x04, // Group Cipher: CCMP
                    0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x04, // Pairwise Cipher: CCMP
                    0x01, 0x00, 0x00, 0x0F, 0xAC.toByte(), 0x02, // AKM: PSK
                    0x00, 0x00, // RSN Capabilities
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
        putU16Le(buf, 14, 100) // beacon_period
        putU16Le(buf, 16, capability)
        buf[18] = ssidBytes.size.toByte()
        System.arraycopy(ssidBytes, 0, buf, 19, ssidBytes.size)
        putU32Le(buf, 52, 8) // rateset.count
        byteArrayOf(0x82.toByte(), 0x84.toByte(), 0x8B.toByte(), 0x96.toByte(), 0x0C, 0x12, 0x18, 0x24)
            .copyInto(buf, 56)
        putU16Le(buf, 72, 0x1000 or (ap.channel and 0xff)) // chanspec: 2.4GHz 20MHz
        buf[76] = 1 // dtim_period
        putU16Le(buf, 78, ap.rssi and 0xffff) // RSSI (i16)
        buf[80] = (-90).toByte() // phy_noise
        buf[81] = 1 // n_cap
        buf[88] = (ap.channel and 0xff).toByte() // ctl_ch
        putU16Le(buf, 116, WL_BSS_INFO_HEADER_SIZE) // ie_offset
        putU32Le(buf, 120, ieBytes.size) // ie_length
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
        // bcm_event_t: ethhdr (14) + bcmeth_hdr_t (10) + wl_event_msg_t (48) + data
        val eventBytes = ByteArray(14 + 10 + 48 + data.size)
        // Ethernet header
        System.arraycopy(macBytes, 0, eventBytes, 0, 6)
        System.arraycopy(addr, 0, eventBytes, 6, 6)
        putU16Be(eventBytes, 12, 0x886C) // ETHER_TYPE_BRCM
        // bcmeth_hdr_t
        putU16Be(eventBytes, 14, 0x8001) // BCMILCP_SUBTYPE_VENDOR_LONG
        putU16Be(eventBytes, 16, 6 + 48 + data.size) // length
        eventBytes[18] = 0x00 // version
        eventBytes[19] = 0x00; eventBytes[20] = 0x10; eventBytes[21] = 0x18 // BRCM_OUI
        putU16Be(eventBytes, 22, 0x0001) // BCMILCP_BCM_SUBTYPE_EVENT
        // wl_event_msg_t (big-endian)
        putU16Be(eventBytes, 24, 2) // version = 2
        putU16Be(eventBytes, 26, flags)
        putU32Be(eventBytes, 28, eventType)
        putU32Be(eventBytes, 32, status)
        putU32Be(eventBytes, 36, 0) // reason
        putU32Be(eventBytes, 40, 0) // auth_type
        putU32Be(eventBytes, 44, data.size)
        System.arraycopy(addr, 0, eventBytes, 48, 6)
        val ifName = "wlan0".toByteArray(Charsets.US_ASCII)
        System.arraycopy(ifName, 0, eventBytes, 54, ifName.size)
        eventBytes[70] = 0 // ifidx
        eventBytes[71] = 0 // bsscfgidx
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, eventBytes, 72, data.size)
        }
        // Wrap in BDC 4-byte header on SDPCM Event Channel (1)
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
        virtualPeer.onCameraEthernetTx(ethFrame)
    }

    private fun enqueueBdcRxEthernet(ethFrame: ByteArray) {
        val bdc = ByteArray(4 + ethFrame.size)
        bdc[0] = 0x20 // BDC protocol version 2 (0x2 << 4)
        bdc[1] = 0x00
        bdc[2] = 0x00
        bdc[3] = 0x00 // data offset = 0
        System.arraycopy(ethFrame, 0, bdc, 4, ethFrame.size)
        enqueueSdpcmRx(SDPCM_DATA_CHANNEL, bdc)
    }

    private fun enqueueSdpcmRx(channel: Int, payload: ByteArray) {
        val hdrLen = 12
        val totalLen = hdrLen + payload.size
        val frame = ByteArray(totalLen)
        putU16Le(frame, 0, totalLen)
        putU16Le(frame, 2, totalLen.inv() and 0xffff)
        frame[4] = (rxSeq++ and 0xff).toByte()
        frame[5] = (channel and 0x0f).toByte()
        frame[6] = 0
        frame[7] = hdrLen.toByte()
        frame[8] = 0
        frame[9] = 32 // TX flow credit
        System.arraycopy(payload, 0, frame, hdrLen, payload.size)
        f2RxQueue.addLast(frame)
        sdioIntStatus = sdioIntStatus or I_HMB_FRAME_IND
        syncCardIrq()
    }

    private fun readF2Bytes(length: Int): ByteArray {
        val out = ByteArray(length)
        val active = f2PendingReadFrame ?: f2RxQueue.removeFirstOrNull()?.also {
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
        syncCardIrq()
        return out
    }

    private fun u32Bytes(v: Int): ByteArray = ByteArray(4).also { putU32Le(it, 0, v) }

    private fun buildCisRom(): ByteArray {
        val rom = ByteArray(0x100) { 0xFF.toByte() }
        // Common CIS @ 0x00 (F0 0x1000)
        val common = ByteArrayOutputStream()
        // CISTPL_MANFID (0x20): Broadcom 0x02D0, prodId
        common.write(0x20); common.write(0x04)
        common.write(BROADCOM_MANFID and 0xff); common.write((BROADCOM_MANFID ushr 8) and 0xff)
        common.write(sdioProdId and 0xff); common.write((sdioProdId ushr 8) and 0xff)
        // CISTPL_FUNCE (0x22) F0 max block size 512, max speed 50MHz
        common.write(0x22); common.write(0x04); common.write(0x00); common.write(0x00); common.write(0x02); common.write(0x5A)
        // CISTPL_VEND (0x80) MAC address tuple
        common.write(0x80); common.write(0x07); common.write(0x19)
        common.write(initialMacBytes)
        common.write(0xFF)
        val commonBytes = common.toByteArray()
        System.arraycopy(commonBytes, 0, rom, 0x00, commonBytes.size)

        // F1 CIS @ 0x40 (F0 0x1040)
        val f1Cis = byteArrayOf(0x22, 0x04, 0x01, 0x00, 0x02, 0x00, 0xFF.toByte())
        System.arraycopy(f1Cis, 0, rom, 0x40, f1Cis.size)
        // F2 CIS @ 0x60 (F0 0x1060)
        val f2Cis = byteArrayOf(0x22, 0x04, 0x01, 0x00, 0x02, 0x00, 0xFF.toByte())
        System.arraycopy(f2Cis, 0, rom, 0x60, f2Cis.size)
        return rom
    }

    private fun buildAxiErom(): IntArray {
        fun ciA(coreId: Int): Int = ((0x4BF shl 20) or ((coreId and 0xfff) shl 8) or 0x01)
        fun ciB(rev: Int): Int = ((rev and 0xff) shl 24) or (1 shl 14) or (1 shl 19) or 0x01
        fun addrDesc(base: Long, isWrapper: Boolean): Int =
            (base.toInt() and 0xfffff000.toInt()) or (if (isWrapper) (0x2 shl 6) else 0x0) or 0x05

        return intArrayOf(
            // ChipCommon (0x800)
            ciA(0x800), ciB(48), addrDesc(CHIPCOMMON_BASE, false), addrDesc(0x18100000L, true),
            // 802.11 MAC (0x812)
            ciA(0x812), ciB(43), addrDesc(D11_BASE, false), addrDesc(0x18101000L, true),
            // SDIO Device (0x829)
            ciA(0x829), ciB(22), addrDesc(SDIO_CORE_BASE, false), addrDesc(0x18102000L, true),
            // ARM Cortex-M3 (0x82A)
            ciA(0x82A), ciB(7), addrDesc(ARM_CM3_BASE, false), addrDesc(ARM_CM3_WRAP_BASE, true),
            // SOCRAM (0x80E)
            ciA(0x80E), ciB(24), addrDesc(SOCRAM_CORE_BASE, false), addrDesc(SOCRAM_WRAP_BASE, true),
            // EROM End Marker
            0x0000000F,
        )
    }

    fun state(): Map<String, Any?> = linkedMapOf(
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
        "ipAddress" to virtualPeer.observedCameraIp,
        "channel" to channel,
        "wsec" to wsec,
        "wpaAuth" to wpaAuth,
        "countryCode" to countryCode,
        "connectedClients" to if (virtualPeer.connected) 1 else 0,
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
        "discoverableNetworks" to neighborsProvider().map { ap ->
            mapOf("ssid" to ap.ssid, "bssid" to ap.bssid, "channel" to ap.channel, "rssi" to ap.rssi, "security" to ap.security)
        },
        "f2RxPendingFrames" to f2RxQueue.size,
        "virtualPeer" to virtualPeer.state(),
    )

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
        "channel" to channel,
        "wsec" to wsec,
        "wpaAuth" to wpaAuth,
        "countryCode" to countryCode,
        "ioctlCount" to ioctlCount,
        "iovarCount" to iovarCount,
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
        sdioIntStatus = (data["sdioIntStatus"] as? Number)?.toLong() ?: 0L
        sdioHostIntMask = (data["sdioHostIntMask"] as? Number)?.toLong() ?: 0L
        armResetCtrl = (data["armResetCtrl"] as? Number)?.toInt() ?: 1
        firmwareLoaded = data["firmwareLoaded"] as? Boolean ?: false
        firmwareBytesWritten = (data["firmwareBytesWritten"] as? Number)?.toLong() ?: 0L
        firmwareSha256 = data["firmwareSha256"] as? String ?: ""
        clmLoaded = data["clmLoaded"] as? Boolean ?: false
        clmBytesWritten = (data["clmBytesWritten"] as? Number)?.toLong() ?: 0L
        nextMac.copyInto(macBytes)
        mode = data["mode"] as? String ?: "OFF"
        interfaceUp = data["interfaceUp"] as? Boolean ?: false
        apEnabled = data["apEnabled"] as? Boolean ?: false
        ssid = data["ssid"] as? String ?: ""
        channel = (data["channel"] as? Number)?.toInt() ?: 1
        wsec = (data["wsec"] as? Number)?.toInt() ?: 0
        wpaAuth = (data["wpaAuth"] as? Number)?.toInt() ?: 0
        countryCode = data["countryCode"] as? String ?: "US"
        ioctlCount = (data["ioctlCount"] as? Number)?.toLong() ?: 0L
        iovarCount = (data["iovarCount"] as? Number)?.toLong() ?: 0L
        f2RxQueue.clear()
        f2PendingReadFrame = null
        f2PendingReadOffset = 0
        @Suppress("UNCHECKED_CAST")
        (data["virtualPeer"] as? Map<String, Any?>)?.let { virtualPeer.restoreSnapshot(it) }
        syncCardIrq()
    }

    override fun close() {
        virtualPeer.close()
    }
}
