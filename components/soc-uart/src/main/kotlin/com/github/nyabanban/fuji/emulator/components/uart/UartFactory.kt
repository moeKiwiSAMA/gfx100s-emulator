package com.github.nyabanban.fuji.emulator.components.uart

import com.github.nyabanban.fuji.emulator.api.*
import java.util.Base64
import java.util.logging.Logger

/**
 * Pure ARM PL011 UART controller peripheral:
 * - Legacy single-bank profiles (`retained-uart-experimental-v1`, `pl011-tx-ready-experimental-v1`)
 *   at `[0xFFF3A000, 0xFFF3A03C)` (`soc-uart.uart-bank`, `soc-uart.uart-config`, `soc-uart.uart-tail`).
 * - Multi-channel PL011 profile (`pl011-multi-uart-experimental-v1`):
 *   - `soc-uart.uart0` (`0xFFF39000`, `0x4C` B) + `soc-uart.uart0.serial` ([UartSerialPort])
 *   - `soc-uart.uart1` (`0xFFF3A000`, `0x4C` B, `GIC IRQ 258` when `uart1IrqEnabled=true`) + `soc-uart.uart1.serial` ([UartSerialPort])
 *   - `soc-uart.uart2` (`0xFFF3B000`, `0x4C` B) + `soc-uart.uart2.serial` ([UartSerialPort])
 *
 * Strictly chip-agnostic: models only PL011 FIFOs, baud/line-control registers, clock-gating, and
 * interrupt lines. External serial peripherals (such as `bcm43438`) attach via [UartSerialPort].
 */
class UartFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    companion object {
        const val PROFILE = "retained-uart-experimental-v1"
        const val PL011_TX_READY_PROFILE = "pl011-tx-ready-experimental-v1"
        const val PL011_MULTI_UART_PROFILE = "pl011-multi-uart-experimental-v1"
        const val PL011_FR_EMPTY = 0x90L // TXFE (bit 7) | RXFE (bit 4); BUSY=0
        const val PL011_FR_TXFE_ONLY = 0x80L // TXFE (bit 7) set, RXFE (bit 4) cleared; BUSY=0
        const val PL011_INT_RX = 0x10L // RXRIS (bit 4)
        const val PL011_INT_TX = 0x20L // TXRIS (bit 5)
        const val PL011_INT_RT = 0x40L // RTRIS (bit 6)
        const val UART1_GIC_IRQ = 258 // DAT section4 0x015A7D8C (ch=1, 4 slots @ 0x015A7D6C+ch*32): lid 0x2C..0x2F -> GIC IRQ 258..261 (ISR 0x011DB330)
        const val UARTCR_UARTEN_BIT = 1L
        const val POLICY_ALLOW_WARN = "allow-warn"
        const val POLICY_ABORT = "abort"
        val ACTIVE_PROFILES: Set<String> = setOf(PROFILE, PL011_TX_READY_PROFILE, PL011_MULTI_UART_PROFILE)
        const val PORT_BANK = "soc-uart.uart-bank"
        const val PORT_CONFIG = "soc-uart.uart-config"
        const val PORT_TAIL = "soc-uart.uart-tail"
        const val PORT_UART0 = "soc-uart.uart0"
        const val PORT_UART1 = "soc-uart.uart1"
        const val PORT_UART2 = "soc-uart.uart2"
        const val PORT_UART0_SERIAL = "soc-uart.uart0.serial"
        const val PORT_UART1_SERIAL = "soc-uart.uart1.serial"
        const val PORT_UART2_SERIAL = "soc-uart.uart2.serial"
        const val PORT_CLOCK_GATE = "soc-uart.clock-gate"
        const val WORD_COUNT = 8
        const val MULTI_CHANNEL_COUNT = 3
        const val MULTI_CHANNEL_WORD_COUNT = 12
        const val MULTI_CHANNEL_WINDOW_SIZE = 76L // 0x4C bytes (offsets 0x00..0x48)
        const val TX_RING_CAPACITY = 65536
        private const val TX_LOG_LINE_LIMIT = 1024
        private val LOGGER = Logger.getLogger(UartFactory::class.java.name)

        private val PORTS = listOf(PORT_BANK, PORT_CONFIG, PORT_TAIL)
        private val MULTI_PORTS = listOf(PORT_UART0, PORT_UART1, PORT_UART2)
        private val SERIAL_PORTS = listOf(PORT_UART0_SERIAL, PORT_UART1_SERIAL, PORT_UART2_SERIAL)

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-uart",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE, PL011_TX_READY_PROFILE, PL011_MULTI_UART_PROFILE), live = false),
                Setting("initialFill", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 255.0, live = false),
                Setting("logTx", ValueKind.BOOLEAN, false, live = false),
                Setting("initiallyClocked", ValueKind.BOOLEAN, true, live = false),
                Setting("unclockedAccessPolicy", ValueKind.TEXT, POLICY_ALLOW_WARN, choices = listOf(POLICY_ALLOW_WARN, POLICY_ABORT), live = false),
                Setting("uart1IrqEnabled", ValueKind.BOOLEAN, false, live = false),
            ),
        )

        private val LEGACY_SCALAR_KEYS = setOf("profile", "initialFill")
        private val OPTIONAL_SCALAR_KEYS = setOf("logTx", "initiallyClocked", "unclockedAccessPolicy", "uart1IrqEnabled")
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "enabled", "words", "readCount", "writeCount",
        )
        private val OPTIONAL_SNAPSHOT_KEYS = setOf(
            "txRingBase64", "txTotalBytes", "clocked", "unclockedTxAttempts", "unclockedReadAttempts", "channelWords",
            "uart1RxBase64",
        )

        private data class RegisterSlot(val index: Int, val canRead: Boolean, val canWrite: Boolean, val absOffset: Long)

        private val BANK_SLOTS = mapOf(
            0x00L to RegisterSlot(0, canRead = false, canWrite = true, absOffset = 0x00L),
            0x18L to RegisterSlot(1, canRead = true, canWrite = false, absOffset = 0x18L),
            0x24L to RegisterSlot(2, canRead = false, canWrite = true, absOffset = 0x24L),
            0x28L to RegisterSlot(3, canRead = false, canWrite = true, absOffset = 0x28L),
            0x2cL to RegisterSlot(4, canRead = true, canWrite = true, absOffset = 0x2cL),
        )

        // DAT section4 0x011DCC00..0x011DD480: full PL011 register offsets across channels 0..2 (0xFFF39/A/B000).
        private val MULTI_CHANNEL_SLOTS = mapOf(
            0x00L to RegisterSlot(0, canRead = true, canWrite = true, absOffset = 0x00L),   // UARTDR
            0x18L to RegisterSlot(1, canRead = true, canWrite = false, absOffset = 0x18L),  // UARTFR
            0x24L to RegisterSlot(2, canRead = true, canWrite = true, absOffset = 0x24L),   // UARTIBRD
            0x28L to RegisterSlot(3, canRead = true, canWrite = true, absOffset = 0x28L),   // UARTFBRD
            0x2cL to RegisterSlot(4, canRead = true, canWrite = true, absOffset = 0x2cL),   // UARTLCR_H
            0x30L to RegisterSlot(5, canRead = true, canWrite = true, absOffset = 0x30L),   // UARTCR
            0x34L to RegisterSlot(6, canRead = true, canWrite = true, absOffset = 0x34L),   // UARTIFLS
            0x38L to RegisterSlot(7, canRead = true, canWrite = true, absOffset = 0x38L),   // UARTIMSC
            0x3cL to RegisterSlot(8, canRead = true, canWrite = false, absOffset = 0x3cL),  // UARTRIS
            0x40L to RegisterSlot(9, canRead = true, canWrite = false, absOffset = 0x40L),  // UARTMIS
            0x44L to RegisterSlot(10, canRead = false, canWrite = true, absOffset = 0x44L), // UARTICR (W1C on RIS)
            0x48L to RegisterSlot(11, canRead = true, canWrite = true, absOffset = 0x48L),  // UARTDMACR
        )

        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "Snapshot field $field out of range" }
            return converted
        }

        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-uart", context, config), StatefulComponent, UartClockGatePort, NativeMmioMirrorPort {
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val active = profile in ACTIVE_PROFILES
        private val multiUart = profile == PL011_MULTI_UART_PROFILE
        private val pl011TxReady = profile == PL011_TX_READY_PROFILE || multiUart
        private val fillByte = (config.getValue("initialFill") as Number).toLong()
        private val logTx = (config["logTx"] as? Boolean) ?: false
        private val initiallyClocked = (config["initiallyClocked"] as? Boolean) ?: true
        private val unclockedAccessPolicy = (config["unclockedAccessPolicy"] as? String) ?: POLICY_ALLOW_WARN
        private val uart1IrqEnabled = (config["uart1IrqEnabled"] as? Boolean) ?: false
        private val channelRxFifos = Array(MULTI_CHANNEL_COUNT) { ArrayDeque<Byte>() }
        private val channelPeers = arrayOfNulls<UartSerialPeer>(MULTI_CHANNEL_COUNT)
        private var cachedGic: InterruptSink? = null
        private val words = LongArray(WORD_COUNT)
        private val channelWords = Array(MULTI_CHANNEL_COUNT) { LongArray(MULTI_CHANNEL_WORD_COUNT) }
        private var readCount = 0L
        private var writeCount = 0L
        private var clocked = initiallyClocked
        private var unclockedTxAttempts = 0L
        private var unclockedReadAttempts = 0L
        private var warnedUnclockedTx = false
        private var warnedUnclockedRead = false
        private val txRing = ByteArray(TX_RING_CAPACITY)
        private var txWritePos = 0
        private var txBuffered = 0
        private var txTotalBytes = 0L
        private val txLineBuf = java.io.ByteArrayOutputStream(128)
        private val channelMirrors = arrayOfNulls<MmioMirrorHandle>(MULTI_CHANNEL_COUNT)
        private var legacyBankMirror: MmioMirrorHandle? = null

        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> = when {
            !active -> emptyList()
            multiUart -> MULTI_PORTS.map { port ->
                MmioMirrorWindowSpec(
                    portName = port,
                    windowSize = MULTI_CHANNEL_WINDOW_SIZE,
                    slots = listOf(
                        // 0x00 (UARTDR) intentionally omitted: reads pop channelRxFifos
                        MmioMirrorSlotSpec(0x18L, 4), // UARTFR
                        MmioMirrorSlotSpec(0x24L, 4), // UARTIBRD
                        MmioMirrorSlotSpec(0x28L, 4), // UARTFBRD
                        MmioMirrorSlotSpec(0x2CL, 4), // UARTLCR_H
                        MmioMirrorSlotSpec(0x30L, 4), // UARTCR
                        MmioMirrorSlotSpec(0x34L, 4), // UARTIFLS
                        MmioMirrorSlotSpec(0x38L, 4), // UARTIMSC
                        MmioMirrorSlotSpec(0x3CL, 4), // UARTRIS
                        MmioMirrorSlotSpec(0x40L, 4), // UARTMIS
                        MmioMirrorSlotSpec(0x48L, 4), // UARTDMACR
                    ),
                )
            }
            else -> listOf(
                MmioMirrorWindowSpec(
                    portName = PORT_BANK,
                    windowSize = 48L,
                    slots = listOf(
                        MmioMirrorSlotSpec(0x18L, 4), // UARTFR
                        MmioMirrorSlotSpec(0x2CL, 4), // UARTLCR_H
                    ),
                ),
            )
        }

        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            if (!active) return
            if (multiUart) {
                val ch = MULTI_PORTS.indexOf(portName)
                if (ch >= 0) {
                    channelMirrors[ch] = handle
                    syncChannelMirror(ch)
                }
            } else if (portName == PORT_BANK) {
                legacyBankMirror = handle
                syncLegacyMirror()
            }
        }

        override fun detachMmioMirrors() {
            for (ch in 0 until MULTI_CHANNEL_COUNT) {
                channelMirrors[ch]?.invalidateAll()
                channelMirrors[ch] = null
            }
            legacyBankMirror?.invalidateAll()
            legacyBankMirror = null
        }

        private fun syncChannelMirror(channel: Int) {
            val h = channelMirrors[channel] ?: return
            if (channel == 1 && !clocked) {
                // Keep unclocked reads on Java so handleUnclockedRead / POLICY_ABORT stays 100% intact.
                h.invalidateAll()
                return
            }
            val regs = channelWords[channel]
            val fr = if (channelRxFifos[channel].isNotEmpty()) PL011_FR_TXFE_ONLY else PL011_FR_EMPTY
            val ris = effectiveRis(channel)
            val mis = ris and regs[7]
            h.writeSlotRaw(0, fr.toInt())
            h.writeSlotRaw(1, regs[2].toInt())
            h.writeSlotRaw(2, regs[3].toInt())
            h.writeSlotRaw(3, regs[4].toInt())
            h.writeSlotRaw(4, regs[5].toInt())
            h.writeSlotRaw(5, regs[6].toInt())
            h.writeSlotRaw(6, regs[7].toInt())
            h.writeSlotRaw(7, ris.toInt())
            h.writeSlotRaw(8, mis.toInt())
            h.writeSlotRaw(9, regs[11].toInt())
            h.setValidMask(h.allSlotsMask)
        }

        private fun syncLegacyMirror() {
            val h = legacyBankMirror ?: return
            if (!clocked) {
                h.invalidateAll()
                return
            }
            val fr = if (pl011TxReady) PL011_FR_EMPTY else words[1]
            h.writeSlotRaw(0, fr.toInt())
            h.writeSlotRaw(1, words[4].toInt())
            h.setValidMask(h.allSlotsMask)
        }

        private fun syncAllMirrors() {
            if (multiUart) {
                for (ch in 0 until MULTI_CHANNEL_COUNT) syncChannelMirror(ch)
            } else {
                syncLegacyMirror()
            }
        }

        private fun enqueueChannelRx(channel: Int, bytes: ByteArray) {
            if (bytes.isEmpty() || closed) return
            val fifo = channelRxFifos[channel]
            for (b in bytes) {
                fifo.addLast(b)
            }
            if (fifo.isNotEmpty()) {
                channelWords[channel][8] = channelWords[channel][8] or PL011_INT_RX or PL011_INT_RT
            }
            if (channel == 1) {
                syncUart1Irq()
            }
            syncChannelMirror(channel)
        }

        private fun effectiveRis(channel: Int): Long {
            var ris = channelWords[channel][8]
            val irqActive = multiUart && (channel == 1 && (uart1IrqEnabled || channelPeers[1] != null) || channelPeers[channel] != null)
            if (irqActive) {
                ris = ris or PL011_INT_TX
                if (channelRxFifos[channel].isNotEmpty()) {
                    ris = ris or PL011_INT_RX or PL011_INT_RT
                } else {
                    ris = ris and (PL011_INT_RX or PL011_INT_RT).inv()
                }
            }
            return ris
        }

        private fun syncUart1Irq() {
            if (!multiUart || !uart1IrqEnabled) return
            val ris = effectiveRis(1)
            val imsc = channelWords[1][7]
            val asserted = (ris and imsc) != 0L
            val gic = cachedGic ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also { cachedGic = it }
            gic?.setLine(UART1_GIC_IRQ, asserted)
        }

        private fun usesClockGateConfig(): Boolean =
            !initiallyClocked || unclockedAccessPolicy != POLICY_ALLOW_WARN

        private fun recordTxByte(b: Byte) {
            txRing[txWritePos] = b
            txWritePos = (txWritePos + 1) % TX_RING_CAPACITY
            if (txBuffered < TX_RING_CAPACITY) txBuffered++
            if (txTotalBytes < Long.MAX_VALUE) txTotalBytes++
            if (logTx) {
                val unsigned = b.toInt() and 0xff
                if (unsigned == '\n'.code) {
                    flushTxLogLine()
                } else if (unsigned != '\r'.code) {
                    txLineBuf.write(unsigned)
                    if (txLineBuf.size() >= TX_LOG_LINE_LIMIT) {
                        flushTxLogLine()
                    }
                }
            }
        }

        private fun flushTxLogLine() {
            if (txLineBuf.size() == 0) return
            val line = txLineBuf.toString(Charsets.UTF_8)
            txLineBuf.reset()
            LOGGER.info("[UART/TX] $line")
        }

        private fun txRingBytes(): ByteArray {
            if (txBuffered == 0) return ByteArray(0)
            val out = ByteArray(txBuffered)
            val start = (txWritePos - txBuffered + TX_RING_CAPACITY) % TX_RING_CAPACITY
            val first = minOf(txBuffered, TX_RING_CAPACITY - start)
            System.arraycopy(txRing, start, out, 0, first)
            if (first < txBuffered) {
                System.arraycopy(txRing, 0, out, first, txBuffered - first)
            }
            return out
        }

        private fun registerSerialPort(channel: Int) {
            context.ports.register(id, SERIAL_PORTS[channel], UartSerialPort::class.java, object : UartSerialPort {
                override fun attachPeer(peer: UartSerialPeer) {
                    check(!closed) { "soc-uart controller closed" }
                    require(channelPeers[channel] == null || channelPeers[channel] === peer) {
                        "UART$channel serial port already has an attached peer"
                    }
                    channelPeers[channel] = peer
                    val regs = channelWords[channel]
                    peer.onLineConfigChanged(
                        ibrd = (regs[2] and 0xffffL).toInt(),
                        fbrd = (regs[3] and 0x3fL).toInt(),
                        lcrH = regs[4],
                        uartCr = regs[5],
                    )
                    if (channel == 1) syncUart1Irq()
                    syncChannelMirror(channel)
                }

                override fun detachPeer(peer: UartSerialPeer) {
                    if (channelPeers[channel] === peer) {
                        channelPeers[channel] = null
                        syncChannelMirror(channel)
                    }
                }

                override fun pushRxBytes(bytes: ByteArray) {
                    enqueueChannelRx(channel, bytes)
                }

                override fun rxPendingBytes(): Int = channelRxFifos[channel].size
            })
        }

        init {
            require(profile in listOf("disabled", PROFILE, PL011_TX_READY_PROFILE, PL011_MULTI_UART_PROFILE)) { "Unsupported soc-uart profile: $profile" }
            require(fillByte in 0L..255L) { "soc-uart initialFill outside 0..255" }
            require(unclockedAccessPolicy in setOf(POLICY_ALLOW_WARN, POLICY_ABORT)) {
                "Unsupported soc-uart unclockedAccessPolicy: $unclockedAccessPolicy"
            }
            if (active) {
                val fillWord = fillByte or (fillByte shl 8) or (fillByte shl 16) or (fillByte shl 24)
                words.fill(fillWord)
                if (pl011TxReady) words[1] = PL011_FR_EMPTY
                if (multiUart) {
                    for (ch in 0 until MULTI_CHANNEL_COUNT) {
                        channelWords[ch].fill(fillWord)
                        channelWords[ch][0] = 0L // DR empty
                        channelWords[ch][1] = PL011_FR_EMPTY
                        channelWords[ch][8] = 0L // RIS
                        channelWords[ch][9] = 0L // MIS
                        channelWords[ch][10] = 0L // ICR
                        registerSerialPort(ch)
                    }
                    registerMultiChannelPort(PORT_UART0, 0)
                    registerMultiChannelPort(PORT_UART1, 1)
                    registerMultiChannelPort(PORT_UART2, 2)
                } else {
                    registerPort(PORT_BANK, 48L) { offset -> BANK_SLOTS[offset] }
                    registerPort(PORT_CONFIG, 4L) { offset ->
                        if (offset == 0L) RegisterSlot(5, canRead = true, canWrite = true, absOffset = 0x30L) else null
                    }
                    registerPort(PORT_TAIL, 8L) { offset ->
                        when (offset) {
                            0L -> RegisterSlot(6, canRead = true, canWrite = true, absOffset = 0x34L)
                            4L -> RegisterSlot(7, canRead = true, canWrite = true, absOffset = 0x38L)
                            else -> null
                        }
                    }
                }
                context.ports.register(id, PORT_CLOCK_GATE, UartClockGatePort::class.java, this)
            }
        }

        override fun setClocked(enabled: Boolean) {
            check(!closed) { "soc-uart controller closed" }
            clocked = enabled
            syncAllMirrors()
        }

        override fun clockGateState(): UartClockGateState = UartClockGateState(
            clocked = clocked,
            initiallyClocked = initiallyClocked,
            policy = unclockedAccessPolicy,
            unclockedTxAttempts = unclockedTxAttempts,
            unclockedReadAttempts = unclockedReadAttempts,
        )

        private fun handleUnclockedRead(slot: RegisterSlot) {
            if (clocked) return
            if (unclockedReadAttempts < Long.MAX_VALUE) unclockedReadAttempts++
            if (unclockedAccessPolicy == POLICY_ABORT) {
                throw PeripheralBusAbortException(
                    peripheralId = id,
                    offset = slot.absOffset,
                    write = false,
                    dfsr = 0x008L,
                    message = "AXI External Data Abort: UART0 (0xfff3a000) clock-gated read at +0x${slot.absOffset.toString(16)}",
                )
            }
            if (!warnedUnclockedRead) {
                warnedUnclockedRead = true
                LOGGER.warning("[UART0/CLOCK-GATE] Unclocked read at 0xfff3a000+0x${slot.absOffset.toString(16)} (UARTCR.UARTEN=0)")
            }
        }

        private fun handleUnclockedTxWrite(slot: RegisterSlot) {
            if (clocked) return
            if (unclockedTxAttempts < Long.MAX_VALUE) unclockedTxAttempts++
            if (unclockedAccessPolicy == POLICY_ABORT) {
                throw PeripheralBusAbortException(
                    peripheralId = id,
                    offset = slot.absOffset,
                    write = true,
                    dfsr = 0x808L,
                    message = "AXI External Data Abort: UART0 (0xfff3a000) clock-gated TX write at +0x${slot.absOffset.toString(16)}",
                )
            }
            if (!warnedUnclockedTx) {
                warnedUnclockedTx = true
                LOGGER.warning("[UART0/CLOCK-GATE] Unclocked TX write at 0xfff3a000+0x00 (UARTCR.UARTEN=0)")
            }
        }

        private fun registerPort(name: String, windowSize: Long, lookup: (Long) -> RegisterSlot?) {
            context.ports.register(id, name, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                private fun resolve(offset: Long, size: Int, write: Boolean): RegisterSlot {
                    check(!closed) { "soc-uart controller closed" }
                    require(size == 4 && offset >= 0L && offset < windowSize && offset % 4L == 0L) {
                        "$name requires aligned 32-bit word within [0, $windowSize)"
                    }
                    val slot = lookup(offset) ?: error("$name refused unknown offset 0x${offset.toString(16)}")
                    require(if (write) slot.canWrite else slot.canRead) {
                        "$name refused ${if (write) "write" else "read"} at offset 0x${offset.toString(16)}"
                    }
                    return slot
                }

                override fun read(offset: Long, size: Int): Long {
                    val slot = resolve(offset, size, write = false)
                    handleUnclockedRead(slot)
                    check(readCount < Long.MAX_VALUE) { "soc-uart readCount overflow" }
                    readCount++
                    return if (pl011TxReady && slot.index == 1) PL011_FR_EMPTY else words[slot.index]
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    val slot = resolve(offset, size, write = true)
                    require(value in 0L..0xffffffffL) { "$name write value outside u32" }
                    if (slot.index == 0) {
                        handleUnclockedTxWrite(slot)
                    }
                    check(writeCount < Long.MAX_VALUE) { "soc-uart writeCount overflow" }
                    words[slot.index] = value
                    writeCount++
                    if (slot.index == 5) {
                        if ((value and UARTCR_UARTEN_BIT) != 0L) {
                            clocked = true
                        } else if (!initiallyClocked) {
                            clocked = false
                        }
                    } else if (slot.index == 0) {
                        recordTxByte((value and 0xffL).toByte())
                    }
                    syncLegacyMirror()
                }
            })
        }

        private fun registerMultiChannelPort(name: String, channel: Int) {
            context.ports.register(id, name, MmioDevice::class.java, object : MmioDevice {
                override val clockOrder = 80
                private fun resolve(offset: Long, size: Int, write: Boolean): RegisterSlot {
                    check(!closed) { "soc-uart controller closed" }
                    require(size == 4 && offset >= 0L && offset < MULTI_CHANNEL_WINDOW_SIZE && offset % 4L == 0L) {
                        "$name requires aligned 32-bit word within [0, $MULTI_CHANNEL_WINDOW_SIZE)"
                    }
                    val slot = MULTI_CHANNEL_SLOTS[offset] ?: error("$name refused unknown offset 0x${offset.toString(16)}")
                    require(if (write) slot.canWrite else slot.canRead) {
                        "$name refused ${if (write) "write" else "read"} at offset 0x${offset.toString(16)}"
                    }
                    return slot
                }

                override fun read(offset: Long, size: Int): Long {
                    val slot = resolve(offset, size, write = false)
                    if (channel == 1) handleUnclockedRead(slot)
                    check(readCount < Long.MAX_VALUE) { "soc-uart readCount overflow" }
                    readCount++
                    val regs = channelWords[channel]
                    val fifo = channelRxFifos[channel]
                    return when (slot.index) {
                        0 -> if (fifo.isNotEmpty()) {
                            val b = fifo.removeFirst().toLong() and 0xffL
                            if (fifo.isEmpty()) {
                                regs[8] = regs[8] and (PL011_INT_RX or PL011_INT_RT).inv()
                            }
                            if (channel == 1) syncUart1Irq()
                            syncChannelMirror(channel)
                            b
                        } else {
                            0L // RXFE=1: empty RX FIFO reads 0
                        }
                        1 -> if (fifo.isNotEmpty()) PL011_FR_TXFE_ONLY else PL011_FR_EMPTY
                        8 -> effectiveRis(channel)
                        9 -> effectiveRis(channel) and regs[7] // UARTMIS = RIS & IMSC
                        else -> regs[slot.index]
                    }
                }

                override fun write(offset: Long, size: Int, value: Long) {
                    val slot = resolve(offset, size, write = true)
                    require(value in 0L..0xffffffffL) { "$name write value outside u32" }
                    if (channel == 1 && slot.index == 0) {
                        handleUnclockedTxWrite(slot)
                    }
                    check(writeCount < Long.MAX_VALUE) { "soc-uart writeCount overflow" }
                    val regs = channelWords[channel]
                    when (slot.index) {
                        10 -> { // UARTICR: write-1-to-clear on UARTRIS
                            regs[8] = regs[8] and value.inv()
                            regs[10] = 0L
                        }
                        else -> regs[slot.index] = value
                    }
                    if (channel == 1 && slot.index < WORD_COUNT) {
                        words[slot.index] = value
                    }
                    writeCount++
                    if (channel == 1 && slot.index == 5) {
                        if ((value and UARTCR_UARTEN_BIT) != 0L) {
                            clocked = true
                        } else if (!initiallyClocked) {
                            clocked = false
                        }
                    } else if (slot.index == 0) {
                        val txByte = (value and 0xffL).toByte()
                        if (channel == 1) recordTxByte(txByte)
                        channelPeers[channel]?.onHostTx(txByte)
                    }
                    if (slot.index in setOf(2, 3, 4, 5)) {
                        channelPeers[channel]?.onLineConfigChanged(
                            ibrd = (regs[2] and 0xffffL).toInt(),
                            fbrd = (regs[3] and 0x3fL).toInt(),
                            lcrH = regs[4],
                            uartCr = regs[5],
                        )
                    }
                    if (channel == 1 && uart1IrqEnabled && slot.index in setOf(0, 5, 7, 10)) {
                        syncUart1Irq()
                    }
                    syncChannelMirror(channel)
                }
            })
        }

        override fun interruptRoutes(): List<InterruptRoute> =
            if (multiUart && uart1IrqEnabled) listOf(InterruptRoute("soc-gic.lines", UART1_GIC_IRQ..UART1_GIC_IRQ)) else emptyList()

        override fun start() {
            check(!closed) { "soc-uart controller closed" }
            if (multiUart && uart1IrqEnabled) {
                cachedGic = context.ports.find("soc-gic.lines", InterruptSink::class.java)
                syncUart1Irq()
            }
        }

        override fun close() {
            if (!closed && logTx) flushTxLogLine()
            if (!closed && multiUart && uart1IrqEnabled) {
                cachedGic?.setLine(UART1_GIC_IRQ, false)
            }
            channelPeers.fill(null)
            cachedGic = null
            detachMmioMirrors()
            closed = true
        }

        override fun additionalMmioPorts(): List<String> = when {
            !active -> emptyList()
            multiUart -> MULTI_PORTS
            else -> PORTS
        }

        override fun state(): Map<String, Any?> {
            val txBytes = txRingBytes()
            val base = (super.state() - "uart1IrqEnabled") + mapOf(
                "enabled" to active,
                "ports" to additionalMmioPorts(),
                "words" to if (active) words.toList() else emptyList<Long>(),
                "readCount" to readCount,
                "writeCount" to writeCount,
                "txBytesTotal" to txTotalBytes,
                "txBufferTruncated" to (txTotalBytes > TX_RING_CAPACITY),
                "txText" to String(txBytes, Charsets.UTF_8),
                "txBytesBase64" to Base64.getEncoder().encodeToString(txBytes),
                "clocked" to clocked,
                "unclockedTxAttempts" to unclockedTxAttempts,
                "unclockedReadAttempts" to unclockedReadAttempts,
                "basis" to "DAT section4/native-uart-03 aligned 32-bit PL011 word offsets & RW/RO/WO directions; generic PL011 FIFO/IRQ/serial-port controller with zero external chip coupling",
            ) + (if (multiUart) mapOf("channelWords" to channelWords.map { it.toList() }) else emptyMap()) +
                (if (uart1IrqEnabled) mapOf("uart1IrqEnabled" to true, "uart1PeerAttached" to (channelPeers[1] != null), "uart1RxPendingBytes" to channelRxFifos[1].size) else emptyMap())
            return if (!usesClockGateConfig()) {
                base
            } else {
                base + mapOf(
                    "initiallyClocked" to initiallyClocked,
                    "unclockedAccessPolicy" to unclockedAccessPolicy,
                )
            }
        }

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-uart controller closed" }
            val savedScalars = saveScalarValues().let { raw ->
                var out = if (logTx) raw else raw - "logTx"
                if (initiallyClocked) out = out - "initiallyClocked"
                if (unclockedAccessPolicy == POLICY_ALLOW_WARN) out = out - "unclockedAccessPolicy"
                if (!uart1IrqEnabled) out = out - "uart1IrqEnabled"
                out
            }
            val out = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to savedScalars,
                "enabled" to active,
                "words" to if (active) words.toList() else emptyList<Long>(),
                "readCount" to readCount,
                "writeCount" to writeCount,
            )
            if (multiUart) {
                out["channelWords"] = channelWords.map { it.toList() }
            }
            if (txTotalBytes > 0L) {
                out["txTotalBytes"] = txTotalBytes
                out["txRingBase64"] = Base64.getEncoder().encodeToString(txRingBytes())
            }
            if (uart1IrqEnabled && channelRxFifos[1].isNotEmpty()) {
                out["uart1RxBase64"] = Base64.getEncoder().encodeToString(channelRxFifos[1].toByteArray())
            }
            if (usesClockGateConfig() || clocked != initiallyClocked || unclockedTxAttempts > 0L || unclockedReadAttempts > 0L) {
                out["clocked"] = clocked
                out["unclockedTxAttempts"] = unclockedTxAttempts
                out["unclockedReadAttempts"] = unclockedReadAttempts
            }
            return out
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-uart controller closed" }
            val strippedKeys = data.keys - OPTIONAL_SNAPSHOT_KEYS
            require(strippedKeys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotLong(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            val allSettingKeys = DESCRIPTOR.settings.map { it.key }.toSet()
            require(scalarsRaw.keys.all { it is String && it in allSettingKeys } && LEGACY_SCALAR_KEYS.all { it in scalarsRaw.keys }) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val savedLogTx = if ("logTx" in scalarsRaw) snapshotBoolean(scalarsRaw["logTx"], "scalars.logTx") else false
            require(savedLogTx == logTx || (logTx && "logTx" !in scalarsRaw)) {
                "Snapshot logTx differs for $id"
            }
            val savedInitiallyClocked = if ("initiallyClocked" in scalarsRaw) {
                snapshotBoolean(scalarsRaw["initiallyClocked"], "scalars.initiallyClocked")
            } else true
            require(savedInitiallyClocked == initiallyClocked || (!initiallyClocked && "initiallyClocked" !in scalarsRaw)) {
                "Snapshot initiallyClocked differs for $id"
            }
            val savedPolicy = if ("unclockedAccessPolicy" in scalarsRaw) {
                (scalarsRaw["unclockedAccessPolicy"] as? String)
                    ?: throw IllegalArgumentException("Snapshot scalars.unclockedAccessPolicy requires string")
            } else POLICY_ALLOW_WARN
            require(savedPolicy in setOf(POLICY_ALLOW_WARN, POLICY_ABORT)) {
                "Invalid unclockedAccessPolicy in snapshot for $id"
            }
            require(savedPolicy == unclockedAccessPolicy || (unclockedAccessPolicy != POLICY_ALLOW_WARN && "unclockedAccessPolicy" !in scalarsRaw)) {
                "Snapshot unclockedAccessPolicy differs for $id"
            }
            val savedUart1Irq = if ("uart1IrqEnabled" in scalarsRaw) {
                snapshotBoolean(scalarsRaw["uart1IrqEnabled"], "scalars.uart1IrqEnabled")
            } else false
            require(savedUart1Irq == uart1IrqEnabled || (uart1IrqEnabled && "uart1IrqEnabled" !in scalarsRaw)) {
                "Snapshot uart1IrqEnabled differs for $id"
            }
            val nextUart1RxBytes: ByteArray = if ("uart1RxBase64" in data) {
                val enc = data["uart1RxBase64"] as? String
                    ?: throw IllegalArgumentException("Snapshot field uart1RxBase64 requires string")
                try {
                    Base64.getDecoder().decode(enc)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid base64 in uart1RxBase64 for $id", e)
                }
            } else ByteArray(0)
            val previousValues = values
            val nextWords = LongArray(WORD_COUNT)
            val nextChannelWords = Array(MULTI_CHANNEL_COUNT) { LongArray(MULTI_CHANNEL_WORD_COUNT) }
            val nextReadCount: Long
            val nextWriteCount: Long
            val nextTxTotalBytes: Long
            val nextTxBytes: ByteArray
            val nextClocked: Boolean
            val nextUnclockedTx: Long
            val nextUnclockedRead: Long
            try {
                val savedProfile = scalarsRaw["profile"] as? String
                    ?: throw IllegalArgumentException("Snapshot scalars.profile requires string for $id")
                val savedEnabled = snapshotBoolean(data["enabled"], "enabled")
                val promoteToPl011 = (profile == PL011_TX_READY_PROFILE && savedProfile in setOf("disabled", PROFILE)) ||
                    (multiUart && savedProfile in setOf("disabled", PROFILE, PL011_TX_READY_PROFILE))
                val normalized = LinkedHashMap<String, Any?>()
                for ((k, v) in scalarsRaw) {
                    val key = k as String
                    if (key in LEGACY_SCALAR_KEYS || key in values) {
                        normalized[key] = v
                    }
                }
                for (optKey in OPTIONAL_SCALAR_KEYS) {
                    if (optKey in values && optKey !in normalized) {
                        normalized[optKey] = values[optKey]
                    }
                }
                val normalizedScalars = if (promoteToPl011) {
                    normalized + mapOf("profile" to profile, "initialFill" to fillByte)
                } else {
                    normalized
                }
                restoreScalarValues(normalizedScalars, DESCRIPTOR)
                require(savedEnabled == active || (promoteToPl011 && !savedEnabled)) {
                    "Snapshot enabled flag differs for $id"
                }
                val savedWords = snapshotList(data["words"], "words")
                nextReadCount = snapshotLong(data["readCount"], "readCount", 0L, Long.MAX_VALUE)
                nextWriteCount = snapshotLong(data["writeCount"], "writeCount", 0L, Long.MAX_VALUE)
                if ("txTotalBytes" in data || "txRingBase64" in data) {
                    require("txTotalBytes" in data && "txRingBase64" in data) {
                        "Incomplete UART TX ring snapshot fields for $id"
                    }
                    nextTxTotalBytes = snapshotLong(data["txTotalBytes"], "txTotalBytes", 0L, Long.MAX_VALUE)
                    val encoded = data["txRingBase64"] as? String
                        ?: throw IllegalArgumentException("Snapshot field txRingBase64 requires string")
                    nextTxBytes = try {
                        Base64.getDecoder().decode(encoded)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Invalid base64 in txRingBase64 for $id", e)
                    }
                    require(nextTxBytes.size <= TX_RING_CAPACITY && nextTxBytes.size.toLong() == minOf(nextTxTotalBytes, TX_RING_CAPACITY.toLong())) {
                        "Snapshot UART TX ring length mismatch for $id"
                    }
                } else {
                    nextTxTotalBytes = 0L
                    nextTxBytes = ByteArray(0)
                }
                val hasClockGateFields = "clocked" in data || "unclockedTxAttempts" in data || "unclockedReadAttempts" in data
                if (hasClockGateFields) {
                    require("clocked" in data && "unclockedTxAttempts" in data && "unclockedReadAttempts" in data) {
                        "Incomplete UART clock-gate snapshot fields for $id"
                    }
                    nextClocked = snapshotBoolean(data["clocked"], "clocked")
                    nextUnclockedTx = snapshotLong(data["unclockedTxAttempts"], "unclockedTxAttempts", 0L, Long.MAX_VALUE)
                    nextUnclockedRead = snapshotLong(data["unclockedReadAttempts"], "unclockedReadAttempts", 0L, Long.MAX_VALUE)
                } else {
                    nextClocked = initiallyClocked
                    nextUnclockedTx = 0L
                    nextUnclockedRead = 0L
                }
                if (!savedEnabled) {
                    require(savedWords.isEmpty() && nextReadCount == 0L && nextWriteCount == 0L && nextTxTotalBytes == 0L) {
                        "Disabled soc-uart snapshot cannot carry active state"
                    }
                    require("channelWords" !in data) { "Disabled soc-uart snapshot cannot carry channelWords" }
                    if (pl011TxReady) {
                        val fillWord = fillByte or (fillByte shl 8) or (fillByte shl 16) or (fillByte shl 24)
                        nextWords.fill(fillWord)
                        nextWords[1] = PL011_FR_EMPTY
                    }
                } else {
                    require(savedWords.size == WORD_COUNT) { "Snapshot words requires $WORD_COUNT entries" }
                    for (slot in 0 until WORD_COUNT) {
                        nextWords[slot] = snapshotLong(savedWords[slot], "words[$slot]", 0L, 0xffffffffL)
                    }
                    if (pl011TxReady) nextWords[1] = PL011_FR_EMPTY
                }
                if ("channelWords" in data) {
                    require(multiUart) { "Cannot restore multi-UART channelWords into profile=$profile" }
                    val rawChannels = snapshotList(data["channelWords"], "channelWords")
                    require(rawChannels.size == MULTI_CHANNEL_COUNT) { "Snapshot channelWords requires $MULTI_CHANNEL_COUNT channels" }
                    for (ch in 0 until MULTI_CHANNEL_COUNT) {
                        val row = snapshotList(rawChannels[ch], "channelWords[$ch]")
                        require(row.size == MULTI_CHANNEL_WORD_COUNT) { "Snapshot channelWords[$ch] requires $MULTI_CHANNEL_WORD_COUNT entries" }
                        for (slot in 0 until MULTI_CHANNEL_WORD_COUNT) {
                            nextChannelWords[ch][slot] = snapshotLong(row[slot], "channelWords[$ch][$slot]", 0L, 0xffffffffL)
                        }
                        nextChannelWords[ch][1] = PL011_FR_EMPTY
                    }
                } else if (multiUart) {
                    val fillWord = fillByte or (fillByte shl 8) or (fillByte shl 16) or (fillByte shl 24)
                    for (ch in 0 until MULTI_CHANNEL_COUNT) {
                        nextChannelWords[ch].fill(fillWord)
                        nextChannelWords[ch][0] = 0L
                        nextChannelWords[ch][1] = PL011_FR_EMPTY
                        nextChannelWords[ch][8] = 0L
                        nextChannelWords[ch][9] = 0L
                        nextChannelWords[ch][10] = 0L
                    }
                    for (slot in 0 until WORD_COUNT) {
                        nextChannelWords[1][slot] = nextWords[slot]
                    }
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            nextWords.copyInto(words)
            if (multiUart) {
                for (ch in 0 until MULTI_CHANNEL_COUNT) {
                    nextChannelWords[ch].copyInto(channelWords[ch])
                }
            }
            readCount = nextReadCount
            writeCount = nextWriteCount
            clocked = nextClocked
            unclockedTxAttempts = nextUnclockedTx
            unclockedReadAttempts = nextUnclockedRead
            warnedUnclockedTx = false
            warnedUnclockedRead = false
            txRing.fill(0)
            System.arraycopy(nextTxBytes, 0, txRing, 0, nextTxBytes.size)
            txBuffered = nextTxBytes.size
            txWritePos = nextTxBytes.size % TX_RING_CAPACITY
            txTotalBytes = nextTxTotalBytes
            txLineBuf.reset()
            for (ch in 0 until MULTI_CHANNEL_COUNT) channelRxFifos[ch].clear()
            for (b in nextUart1RxBytes) channelRxFifos[1].addLast(b)
            syncUart1Irq()
            syncAllMirrors()
        }
    }
}
