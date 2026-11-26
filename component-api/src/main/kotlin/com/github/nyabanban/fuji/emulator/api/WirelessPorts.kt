package com.github.nyabanban.fuji.emulator.api

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
 * Host-side control and inspection port exposed by wireless chip / peer components for the
 * loopback Control API (`/api/wireless`) and deterministic E2E tests.
 */
interface WirelessControlPort {
    fun wirelessStatus(): Map<String, Any?>
    fun applyWirelessAction(payload: Map<String, Any?>): Map<String, Any?>
}
