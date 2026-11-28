package com.github.nyabanban.fuji.emulator.components.sdio

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure, chip-agnostic SDHCI v3.0 / F_SDH30 SDIO Host Controller (`soc-sdio`, channel 2 at `0xFFFCC000`,
 * `0x200` bytes; `GIC IRQ 224` host interrupt + `GIC IRQ 225` SDIO DAT1 card interrupt).
 *
 * Strictly zero Broadcom/Wi-Fi logic inside: models standard SDHCI v3.00 registers (`0x00..0xFF`),
 * Socionext F_SDH30 vendor UHS/I-O drive-strength & tuning registers (`0x100..0x1FF`, e.g. `+0x100`
 * exercised at `S4 0x011F5CF0..0x011F5D44`), PIO Data Port, SDMA, 32-bit ADMA2 descriptor table
 * scatter/gather, clock/reset state, and exposes [SdioBusPort] (`soc-sdio.bus`) for any external
 * SDIO card/function component (such as `bcm43438`) to attach.
 */
class SocSdioFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    companion object {
        const val PROFILE = "soc-sdio-sdhci3-experimental-v1"
        const val MMIO_PORT = "soc-sdio.sdhci"
        const val BUS_PORT = "soc-sdio.bus"
        const val WINDOW_SIZE = 0x200L
        const val HOST_GIC_IRQ = 224 // DAT section4 0x011F4240..0x011F4368: lid 0x60 -> GIC 224 (ch=2 ISR 0x011F5558)
        const val CARD_GIC_IRQ = 225 // DAT section4 0x011F4240..0x011F4368: lid 0x7F -> GIC 225 (ch=2 ISR 0x011F5764)

        // Normal Interrupt Status bits
        const val INT_CMD_COMPLETE = 0x0001
        const val INT_XFER_COMPLETE = 0x0002
        const val INT_DMA = 0x0008
        const val INT_BUF_WRITE_READY = 0x0010
        const val INT_BUF_READ_READY = 0x0020
        const val INT_CARD_INT = 0x0100
        const val INT_ERROR_SUMMARY = 0x8000

        // Error Interrupt Status bits
        const val ERR_CMD_TIMEOUT = 0x0001

        // Capabilities: SDMA (bit 22), High Speed (bit 21), ADMA2 (bit 19), 512B/2048B blk, 3.3V/3.0V/1.8V, 50MHz
        const val CAPABILITIES_0 = 0x25E832B2L
        const val CAPABILITIES_1 = 0x00000007L
        const val HOST_VERSION = 0x1002 // SDHCI Spec v3.00

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-sdio",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE), live = false),
                Setting("irqEnabled", ValueKind.BOOLEAN, true, live = false),
            ),
        )
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-sdio", context, config), StatefulComponent, SdioBusPort, TickAwareMmioDevice {

        private val profile = config.getValue("profile") as String
        private val enabled = profile == PROFILE
        private val irqEnabled = (config["irqEnabled"] as? Boolean) ?: true
        @Volatile
        private var closed = false

        private var attachedCard: SdioCardPort? = null
        private var cachedGic: InterruptSink? = null
        private var cachedMemory: DmaMemory? = null
        @Volatile
        private var ownerThread: Thread? = null
        @Volatile
        private var asyncIrqDirty: Boolean = false

        // Standard SDHCI register state
        private var sdmaSysAddr: Long = 0L
        private var blockSizeReg: Int = 0
        private var blockCountReg: Int = 0
        private var argumentReg: Long = 0L
        private var transferModeReg: Int = 0
        private var commandReg: Int = 0
        private val responseRegs = IntArray(4)
        private var hostControl1: Int = 0
        private var powerControl: Int = 0
        private var blockGapControl: Int = 0
        private var wakeupControl: Int = 0
        private var clockControl: Int = 0
        private var timeoutControl: Int = 0
        private var normalIntStatus: Int = 0
        private var errorIntStatus: Int = 0
        private var normalIntStatusEnable: Int = 0
        private var errorIntStatusEnable: Int = 0
        private var normalIntSignalEnable: Int = 0
        private var errorIntSignalEnable: Int = 0
        private var hostControl2: Int = 0
        private var admaSysAddrLo: Long = 0L
        private var admaSysAddrHi: Long = 0L
        // Socionext F_SDH30 vendor UHS-I / I-O pad control bank (+0x100..+0x1FF; +0x10C..+0x10F status RO)
        private val vendorRegs = ByteArray(0x100)
        @Volatile
        private var cardInterruptLine: Boolean = false

        // Active PIO buffer state
        private var pioReadBuf: ByteArray? = null
        private var pioReadPos: Int = 0
        private var pioWriteBuf: ByteArray? = null
        private var pioWritePos: Int = 0
        private var pioWriteCmdIndex: Int = 0
        private var pioWriteArg: Long = 0L

        // Telemetry counters
        private var readCount: Long = 0L
        private var writeCount: Long = 0L
        private var commandCount: Long = 0L
        private var dmaTransferCount: Long = 0L
        private var dmaBytesTransferred: Long = 0L

        override val clockOrder = 84

        init {
            require(profile in listOf("disabled", PROFILE)) { "Unsupported soc-sdio profile: $profile" }
            if (enabled) {
                context.ports.register(id, MMIO_PORT, MmioDevice::class.java, this)
                context.ports.register(id, BUS_PORT, SdioBusPort::class.java, this)
            }
        }

        override fun additionalMmioPorts(): List<String> = if (enabled) listOf(MMIO_PORT) else emptyList()

        override fun interruptRoutes(): List<InterruptRoute> =
            if (enabled && irqEnabled) listOf(InterruptRoute("soc-gic.lines", HOST_GIC_IRQ..CARD_GIC_IRQ)) else emptyList()

        override fun start() {
            check(!closed) { "soc-sdio closed" }
            ownerThread = Thread.currentThread()
            if (enabled && irqEnabled) {
                cachedGic = context.ports.find("soc-gic.lines", InterruptSink::class.java)
                syncIrqs()
            }
        }

        override fun close() {
            if (closed) return
            if (enabled && irqEnabled) {
                cachedGic?.setLine(HOST_GIC_IRQ, false)
                cachedGic?.setLine(CARD_GIC_IRQ, false)
            }
            attachedCard = null
            cachedGic = null
            cachedMemory = null
            ownerThread = null
            asyncIrqDirty = false
            closed = true
        }

        override fun nextDeadline(): Long? = if (enabled && asyncIrqDirty) 0L else null

        override fun nextInterestedTick(): Long = if (enabled && asyncIrqDirty) 0L else Long.MAX_VALUE

        override fun beforeInstruction(tick: Long) {
            if (asyncIrqDirty) {
                asyncIrqDirty = false
                syncIrqs()
            }
        }

        // ================= SdioBusPort =================

        override fun attachCard(card: SdioCardPort) {
            check(!closed) { "soc-sdio closed" }
            require(attachedCard == null || attachedCard === card) { "soc-sdio slot already occupied" }
            attachedCard = card
            cardInterruptLine = card.isCardInterruptAsserted()
            syncIrqs()
        }

        override fun detachCard(card: SdioCardPort) {
            if (attachedCard === card) {
                attachedCard = null
                cardInterruptLine = false
                syncIrqs()
            }
        }

        override fun setCardInterrupt(asserted: Boolean) {
            if (closed) return
            cardInterruptLine = asserted
            val owner = ownerThread
            if (owner == null || Thread.currentThread() === owner) {
                asyncIrqDirty = false
                syncIrqs()
            } else {
                asyncIrqDirty = true
            }
        }

        // ================= Interrupt Synchronization =================

        private fun effectiveNormalIntStatus(): Int {
            var st = normalIntStatus and 0x7EFF
            if (cardInterruptLine && (normalIntStatusEnable and INT_CARD_INT) != 0) {
                st = st or INT_CARD_INT
            }
            if ((errorIntStatus and errorIntStatusEnable) != 0) {
                st = st or INT_ERROR_SUMMARY
            }
            return st
        }

        private fun syncIrqs() {
            if (!enabled || !irqEnabled || closed) return
            val gic = cachedGic ?: context.ports.find("soc-gic.lines", InterruptSink::class.java)?.also { cachedGic = it }
                ?: return
            val effNormal = effectiveNormalIntStatus()
            val hostAsserted = ((effNormal and 0x00FF and normalIntSignalEnable) != 0) ||
                ((errorIntStatus and errorIntSignalEnable) != 0)
            val cardAsserted = ((effNormal and INT_CARD_INT) != 0) && ((normalIntSignalEnable and INT_CARD_INT) != 0)
            gic.setLine(HOST_GIC_IRQ, hostAsserted)
            gic.setLine(CARD_GIC_IRQ, cardAsserted)
        }

        // ================= MMIO Read / Write (1, 2, 4 bytes) =================

        override fun read(offset: Long, size: Int): Long {
            check(!closed) { "soc-sdio closed" }
            require(size in setOf(1, 2, 4) && offset >= 0L && offset + size <= WINDOW_SIZE) {
                "soc-sdio invalid read offset=0x${offset.toString(16)} size=$size"
            }
            readCount++
            val off = offset.toInt()
            if (off == 0x20) {
                return readPioData(size)
            }
            var result = 0L
            for (i in 0 until size) {
                val b = readRegByte(off + i).toLong() and 0xffL
                result = result or (b shl (i * 8))
            }
            return result
        }

        override fun write(offset: Long, size: Int, value: Long) {
            check(!closed) { "soc-sdio closed" }
            require(size in setOf(1, 2, 4) && offset >= 0L && offset + size <= WINDOW_SIZE) {
                "soc-sdio invalid write offset=0x${offset.toString(16)} size=$size"
            }
            writeCount++
            val off = offset.toInt()
            if (off == 0x20) {
                writePioData(size, value)
                return
            }
            var triggerCommand = false
            for (i in 0 until size) {
                val b = ((value ushr (i * 8)) and 0xffL).toInt()
                if (writeRegByte(off + i, b)) {
                    triggerCommand = true
                }
            }
            if (triggerCommand) {
                executePendingCommand()
            }
            syncIrqs()
        }

        private fun presentState(): Long {
            var ps = 0x01F0_0000L // CMD=1, DAT[3:0]=0xF
            if (attachedCard != null) {
                ps = ps or 0x000F_0000L // CARD_INSERTED | CARD_STABLE | CARD_DETECT | WRITE_PROTECT
            }
            if (pioWriteBuf != null) ps = ps or (1L shl 10) // BUFFER_WRITE_ENABLE
            if (pioReadBuf != null) ps = ps or (1L shl 11) // BUFFER_READ_ENABLE
            return ps
        }

        private fun readRegByte(off: Int): Int {
            if (off in 0x100..0x1FF) {
                return if (off in 0x10C..0x10F) 0 else (vendorRegs[off - 0x100].toInt() and 0xff)
            }
            val wordAligned = off and 0xFC
            val shift = (off and 3) * 8
            val word: Long = when (wordAligned) {
                0x00 -> sdmaSysAddr
                0x04 -> (blockSizeReg.toLong() and 0xffffL) or ((blockCountReg.toLong() and 0xffffL) shl 16)
                0x08 -> argumentReg
                0x0C -> (transferModeReg.toLong() and 0xffffL) or ((commandReg.toLong() and 0xffffL) shl 16)
                0x10 -> responseRegs[0].toLong() and 0xffff_ffffL
                0x14 -> responseRegs[1].toLong() and 0xffff_ffffL
                0x18 -> responseRegs[2].toLong() and 0xffff_ffffL
                0x1C -> responseRegs[3].toLong() and 0xffff_ffffL
                0x24 -> presentState()
                0x28 -> (hostControl1.toLong() and 0xffL) or
                    ((powerControl.toLong() and 0xffL) shl 8) or
                    ((blockGapControl.toLong() and 0xffL) shl 16) or
                    ((wakeupControl.toLong() and 0xffL) shl 24)
                0x2C -> {
                    val clk = if ((clockControl and 0x01) != 0) (clockControl or 0x02) else (clockControl and 0x02.inv())
                    (clk.toLong() and 0xffffL) or ((timeoutControl.toLong() and 0xffL) shl 16) // +0x2F SW Reset reads 0
                }
                0x30 -> (effectiveNormalIntStatus().toLong() and 0xffffL) or ((errorIntStatus.toLong() and 0xffffL) shl 16)
                0x34 -> (normalIntStatusEnable.toLong() and 0xffffL) or ((errorIntStatusEnable.toLong() and 0xffffL) shl 16)
                0x38 -> (normalIntSignalEnable.toLong() and 0xffffL) or ((errorIntSignalEnable.toLong() and 0xffffL) shl 16)
                0x3C -> (hostControl2.toLong() and 0xffffL) shl 16
                0x40 -> CAPABILITIES_0
                0x44 -> CAPABILITIES_1
                0x48 -> 0x0000_00FFL
                0x54 -> 0L // ADMA Error Status
                0x58 -> admaSysAddrLo
                0x5C -> admaSysAddrHi
                0xFC -> {
                    val slotInt = if (effectiveNormalIntStatus() != 0 || errorIntStatus != 0) 1L else 0L
                    slotInt or (HOST_VERSION.toLong() shl 16)
                }
                else -> 0L
            }
            return ((word ushr shift) and 0xffL).toInt()
        }

        private fun writeRegByte(off: Int, value: Int): Boolean {
            val v = value and 0xff
            when (off) {
                in 0x00..0x03 -> sdmaSysAddr = setByte32(sdmaSysAddr, off - 0x00, v)
                0x04 -> blockSizeReg = (blockSizeReg and 0xff00) or v
                0x05 -> blockSizeReg = (blockSizeReg and 0x00ff) or (v shl 8)
                0x06 -> blockCountReg = (blockCountReg and 0xff00) or v
                0x07 -> blockCountReg = (blockCountReg and 0x00ff) or (v shl 8)
                in 0x08..0x0B -> argumentReg = setByte32(argumentReg, off - 0x08, v)
                0x0C -> transferModeReg = (transferModeReg and 0xff00) or v
                0x0D -> transferModeReg = (transferModeReg and 0x00ff) or (v shl 8)
                0x0E -> commandReg = (commandReg and 0xff00) or v
                0x0F -> {
                    commandReg = (commandReg and 0x00ff) or (v shl 8)
                    return true // Writing high byte of COMMAND triggers command execution per SDHCI spec
                }
                0x28 -> hostControl1 = v
                0x29 -> powerControl = v
                0x2A -> blockGapControl = v
                0x2B -> wakeupControl = v
                0x2C -> {
                    clockControl = (clockControl and 0xff00) or (v and 0xFD)
                    if ((clockControl and 0x01) != 0) clockControl = clockControl or 0x02
                }
                0x2D -> clockControl = (clockControl and 0x00ff) or (v shl 8)
                0x2E -> timeoutControl = v and 0x0f
                0x2F -> handleSoftwareReset(v)
                0x30 -> normalIntStatus = normalIntStatus and (v.inv() and 0xff) // W1C
                0x31 -> normalIntStatus = normalIntStatus and ((v shl 8).inv() or 0x0100) // W1C (bit 8 CardInt is level-sensitive)
                0x32 -> errorIntStatus = errorIntStatus and (v.inv() and 0xff)
                0x33 -> errorIntStatus = errorIntStatus and (v shl 8).inv()
                0x34 -> normalIntStatusEnable = (normalIntStatusEnable and 0xff00) or v
                0x35 -> normalIntStatusEnable = (normalIntStatusEnable and 0x00ff) or (v shl 8)
                0x36 -> errorIntStatusEnable = (errorIntStatusEnable and 0xff00) or v
                0x37 -> errorIntStatusEnable = (errorIntStatusEnable and 0x00ff) or (v shl 8)
                0x38 -> normalIntSignalEnable = (normalIntSignalEnable and 0xff00) or v
                0x39 -> normalIntSignalEnable = (normalIntSignalEnable and 0x00ff) or (v shl 8)
                0x3A -> errorIntSignalEnable = (errorIntSignalEnable and 0xff00) or v
                0x3B -> errorIntSignalEnable = (errorIntSignalEnable and 0x00ff) or (v shl 8)
                0x3E -> hostControl2 = (hostControl2 and 0xff00) or v
                0x3F -> hostControl2 = (hostControl2 and 0x00ff) or (v shl 8)
                in 0x58..0x5B -> admaSysAddrLo = setByte32(admaSysAddrLo, off - 0x58, v)
                in 0x5C..0x5F -> admaSysAddrHi = setByte32(admaSysAddrHi, off - 0x5C, v)
                in 0x100..0x1FF -> {
                    if (off !in 0x10C..0x10F) {
                        vendorRegs[off - 0x100] = v.toByte()
                    }
                }
            }
            return false
        }

        private fun setByte32(word: Long, byteIndex: Int, value: Int): Long {
            val shift = byteIndex * 8
            val mask = (0xffL shl shift).inv()
            return (word and mask) or ((value.toLong() and 0xffL) shl shift)
        }

        private fun handleSoftwareReset(bits: Int) {
            if ((bits and 0x01) != 0) { // RESET_ALL
                sdmaSysAddr = 0L
                blockSizeReg = 0
                blockCountReg = 0
                argumentReg = 0L
                transferModeReg = 0
                commandReg = 0
                responseRegs.fill(0)
                hostControl1 = 0
                powerControl = 0
                blockGapControl = 0
                wakeupControl = 0
                clockControl = 0
                timeoutControl = 0
                normalIntStatus = 0
                errorIntStatus = 0
                hostControl2 = 0
                admaSysAddrLo = 0L
                admaSysAddrHi = 0L
                pioReadBuf = null
                pioWriteBuf = null
                pioReadPos = 0
                pioWritePos = 0
            }
            if ((bits and 0x02) != 0) { // RESET_CMD
                normalIntStatus = normalIntStatus and INT_CMD_COMPLETE.inv()
                errorIntStatus = errorIntStatus and ERR_CMD_TIMEOUT.inv()
            }
            if ((bits and 0x04) != 0) { // RESET_DAT
                pioReadBuf = null
                pioWriteBuf = null
                pioReadPos = 0
                pioWritePos = 0
                normalIntStatus = normalIntStatus and (INT_XFER_COMPLETE or INT_BUF_READ_READY or INT_BUF_WRITE_READY or INT_DMA).inv()
            }
        }

        // ================= Command & DMA/PIO Execution =================

        private fun executePendingCommand() {
            commandCount++
            val cmdIndex = (commandReg ushr 8) and 0x3f
            val dataPresent = (commandReg and 0x20) != 0
            val dmaEnabled = (transferModeReg and 0x01) != 0
            val isRead = (transferModeReg and 0x10) != 0
            val blkSize = blockSizeReg and 0x0fff
            val blkCount = if ((transferModeReg and 0x02) != 0 || blockCountReg > 0) blockCountReg else 1

            val card = attachedCard
            if (card == null) {
                if ((errorIntStatusEnable and ERR_CMD_TIMEOUT) != 0) {
                    errorIntStatus = errorIntStatus or ERR_CMD_TIMEOUT
                }
                return
            }

            val result = card.executeCommand(cmdIndex, argumentReg, blkSize, blkCount)
            if (!result.accepted) {
                if ((errorIntStatusEnable and ERR_CMD_TIMEOUT) != 0) {
                    errorIntStatus = errorIntStatus or ERR_CMD_TIMEOUT
                }
                return
            }
            for (i in 0 until 4) responseRegs[i] = result.response[i]
            cardInterruptLine = result.cardInterrupt
            if ((normalIntStatusEnable and INT_CMD_COMPLETE) != 0) {
                normalIntStatus = normalIntStatus or INT_CMD_COMPLETE
            }

            if (!dataPresent) return

            val dmaSelect = (hostControl1 ushr 3) and 0x03
            if (isRead) {
                val payload = result.dataOut ?: ByteArray(0)
                if (dmaEnabled && payload.isNotEmpty()) {
                    if (dmaSelect == 0x02) {
                        adma2WriteToGuestMemory(payload)
                    } else {
                        sdmaWriteToGuestMemory(payload)
                    }
                    if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                        normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                    }
                } else if (payload.isNotEmpty()) {
                    pioReadBuf = payload
                    pioReadPos = 0
                    if ((normalIntStatusEnable and INT_BUF_READ_READY) != 0) {
                        normalIntStatus = normalIntStatus or INT_BUF_READ_READY
                    }
                } else if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                    normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                }
            } else {
                val totalWriteBytes = result.expectDataInBytes.takeIf { it > 0 } ?: (blkSize * maxOf(1, blkCount))
                if (dmaEnabled && totalWriteBytes > 0) {
                    val payload = if (dmaSelect == 0x02) {
                        adma2ReadFromGuestMemory(totalWriteBytes)
                    } else {
                        sdmaReadFromGuestMemory(totalWriteBytes)
                    }
                    val writeRes = card.writeTransferData(cmdIndex, argumentReg, payload)
                    cardInterruptLine = writeRes.cardInterrupt
                    if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                        normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                    }
                } else if (totalWriteBytes > 0) {
                    pioWriteBuf = ByteArray(totalWriteBytes)
                    pioWritePos = 0
                    pioWriteCmdIndex = cmdIndex
                    pioWriteArg = argumentReg
                    if ((normalIntStatusEnable and INT_BUF_WRITE_READY) != 0) {
                        normalIntStatus = normalIntStatus or INT_BUF_WRITE_READY
                    }
                } else if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                    normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                }
            }
        }

        private fun dmaMemory(): DmaMemory =
            cachedMemory ?: context.ports.find("cpu.memory", DmaMemory::class.java)?.also { cachedMemory = it }
                ?: error("soc-sdio requires cpu.memory DmaMemory for DMA transfers")

        private fun sdmaWriteToGuestMemory(data: ByteArray) {
            val mem = dmaMemory()
            mem.write(sdmaSysAddr, ByteBuffer.wrap(data))
            sdmaSysAddr = (sdmaSysAddr + data.size.toLong()) and 0xffff_ffffL
            dmaTransferCount++
            dmaBytesTransferred += data.size.toLong()
        }

        private fun sdmaReadFromGuestMemory(length: Int): ByteArray {
            val mem = dmaMemory()
            val buf = ByteArray(length)
            mem.read(sdmaSysAddr, ByteBuffer.wrap(buf))
            sdmaSysAddr = (sdmaSysAddr + length.toLong()) and 0xffff_ffffL
            dmaTransferCount++
            dmaBytesTransferred += length.toLong()
            return buf
        }

        private fun adma2WriteToGuestMemory(data: ByteArray) {
            val mem = dmaMemory()
            var descAddr = admaSysAddrLo
            var dataOff = 0
            val descBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            var guard = 0
            while (dataOff < data.size && guard++ < 4096) {
                descBuf.clear()
                mem.read(descAddr, descBuf)
                descBuf.flip()
                val attr = descBuf.short.toInt() and 0xffff
                val rawLen = descBuf.short.toInt() and 0xffff
                val addr = descBuf.int.toLong() and 0xffff_ffffL
                val valid = (attr and 0x01) != 0
                val end = (attr and 0x02) != 0
                val act = (attr ushr 4) and 0x03
                if (!valid) break
                when (act) {
                    0x02 -> { // TRAN
                        val segLen = if (rawLen == 0) 65536 else rawLen
                        val take = minOf(segLen, data.size - dataOff)
                        mem.write(addr, ByteBuffer.wrap(data, dataOff, take))
                        dataOff += take
                        descAddr = (descAddr + 8L) and 0xffff_ffffL
                    }
                    0x03 -> descAddr = addr // LINK
                    else -> descAddr = (descAddr + 8L) and 0xffff_ffffL // NOP
                }
                if (end) break
            }
            dmaTransferCount++
            dmaBytesTransferred += dataOff.toLong()
        }

        private fun adma2ReadFromGuestMemory(length: Int): ByteArray {
            val mem = dmaMemory()
            val out = ByteArray(length)
            var descAddr = admaSysAddrLo
            var dataOff = 0
            val descBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            var guard = 0
            while (dataOff < length && guard++ < 4096) {
                descBuf.clear()
                mem.read(descAddr, descBuf)
                descBuf.flip()
                val attr = descBuf.short.toInt() and 0xffff
                val rawLen = descBuf.short.toInt() and 0xffff
                val addr = descBuf.int.toLong() and 0xffff_ffffL
                val valid = (attr and 0x01) != 0
                val end = (attr and 0x02) != 0
                val act = (attr ushr 4) and 0x03
                if (!valid) break
                when (act) {
                    0x02 -> { // TRAN
                        val segLen = if (rawLen == 0) 65536 else rawLen
                        val take = minOf(segLen, length - dataOff)
                        mem.read(addr, ByteBuffer.wrap(out, dataOff, take))
                        dataOff += take
                        descAddr = (descAddr + 8L) and 0xffff_ffffL
                    }
                    0x03 -> descAddr = addr // LINK
                    else -> descAddr = (descAddr + 8L) and 0xffff_ffffL // NOP
                }
                if (end) break
            }
            dmaTransferCount++
            dmaBytesTransferred += dataOff.toLong()
            return out
        }

        private fun readPioData(size: Int): Long {
            val buf = pioReadBuf ?: return 0L
            var out = 0L
            for (i in 0 until size) {
                val b = if (pioReadPos < buf.size) buf[pioReadPos++].toLong() and 0xffL else 0L
                out = out or (b shl (i * 8))
            }
            if (pioReadPos >= buf.size) {
                pioReadBuf = null
                pioReadPos = 0
                normalIntStatus = normalIntStatus and INT_BUF_READ_READY.inv()
                if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                    normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                }
                syncIrqs()
            }
            return out
        }

        private fun writePioData(size: Int, value: Long) {
            val buf = pioWriteBuf ?: return
            for (i in 0 until size) {
                if (pioWritePos < buf.size) {
                    buf[pioWritePos++] = ((value ushr (i * 8)) and 0xffL).toByte()
                }
            }
            if (pioWritePos >= buf.size) {
                val completed = buf
                pioWriteBuf = null
                pioWritePos = 0
                normalIntStatus = normalIntStatus and INT_BUF_WRITE_READY.inv()
                attachedCard?.let { card ->
                    val res = card.writeTransferData(pioWriteCmdIndex, pioWriteArg, completed)
                    cardInterruptLine = res.cardInterrupt
                }
                if ((normalIntStatusEnable and INT_XFER_COMPLETE) != 0) {
                    normalIntStatus = normalIntStatus or INT_XFER_COMPLETE
                }
                syncIrqs()
            }
        }

        override fun state(): Map<String, Any?> = super.state() + linkedMapOf(
            "enabled" to enabled,
            "ports" to additionalMmioPorts(),
            "cardAttached" to (attachedCard != null),
            "cardInterruptLine" to cardInterruptLine,
            "readCount" to readCount,
            "writeCount" to writeCount,
            "commandCount" to commandCount,
            "dmaTransferCount" to dmaTransferCount,
            "dmaBytesTransferred" to dmaBytesTransferred,
            "normalIntStatus" to effectiveNormalIntStatus(),
            "errorIntStatus" to errorIntStatus,
            "clockControl" to clockControl,
            "hostControl1" to hostControl1,
            "vendorIoCtrl100" to ((vendorRegs[0].toInt() and 0xff) or ((vendorRegs[1].toInt() and 0xff) shl 8)),
            "basis" to "DAT section4 0x011F43A4..0x011F43B0 & 0x011F5CF0..0x011F5D44 SDHC ch=2 base 0xFFFCC000 (0x200 B), lid 0x60->GIC 224 host IRQ, lid 0x7F->GIC 225 card IRQ; generic SDHCI v3.00 + F_SDH30 UHS/pad host controller with zero vendor Wi-Fi chip coupling",
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-sdio closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to enabled,
                "sdmaSysAddr" to sdmaSysAddr,
                "blockSizeReg" to blockSizeReg,
                "blockCountReg" to blockCountReg,
                "argumentReg" to argumentReg,
                "transferModeReg" to transferModeReg,
                "commandReg" to commandReg,
                "responseRegs" to responseRegs.toList(),
                "hostControl1" to hostControl1,
                "powerControl" to powerControl,
                "blockGapControl" to blockGapControl,
                "wakeupControl" to wakeupControl,
                "clockControl" to clockControl,
                "timeoutControl" to timeoutControl,
                "normalIntStatus" to normalIntStatus,
                "errorIntStatus" to errorIntStatus,
                "normalIntStatusEnable" to normalIntStatusEnable,
                "errorIntStatusEnable" to errorIntStatusEnable,
                "normalIntSignalEnable" to normalIntSignalEnable,
                "errorIntSignalEnable" to errorIntSignalEnable,
                "hostControl2" to hostControl2,
                "admaSysAddrLo" to admaSysAddrLo,
                "admaSysAddrHi" to admaSysAddrHi,
                "vendorRegsBase64" to java.util.Base64.getEncoder().encodeToString(vendorRegs),
                "cardInterruptLine" to cardInterruptLine,
                "readCount" to readCount,
                "writeCount" to writeCount,
                "commandCount" to commandCount,
                "dmaTransferCount" to dmaTransferCount,
                "dmaBytesTransferred" to dmaBytesTransferred,
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-sdio closed" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            require((data["enabled"] as? Boolean) == enabled) { "Snapshot enabled flag differs for $id" }
            val rawScalars = data["scalars"] as? Map<*, *>
                ?: throw IllegalArgumentException("Missing scalars in soc-sdio snapshot")
            restoreScalarValues(rawScalars, DESCRIPTOR)
            sdmaSysAddr = (data["sdmaSysAddr"] as? Number)?.toLong() ?: 0L
            blockSizeReg = (data["blockSizeReg"] as? Number)?.toInt() ?: 0
            blockCountReg = (data["blockCountReg"] as? Number)?.toInt() ?: 0
            argumentReg = (data["argumentReg"] as? Number)?.toLong() ?: 0L
            transferModeReg = (data["transferModeReg"] as? Number)?.toInt() ?: 0
            commandReg = (data["commandReg"] as? Number)?.toInt() ?: 0
            val resp = data["responseRegs"] as? List<*>
            if (resp != null && resp.size == 4) {
                for (i in 0 until 4) responseRegs[i] = (resp[i] as Number).toInt()
            }
            hostControl1 = (data["hostControl1"] as? Number)?.toInt() ?: 0
            powerControl = (data["powerControl"] as? Number)?.toInt() ?: 0
            blockGapControl = (data["blockGapControl"] as? Number)?.toInt() ?: 0
            wakeupControl = (data["wakeupControl"] as? Number)?.toInt() ?: 0
            clockControl = (data["clockControl"] as? Number)?.toInt() ?: 0
            timeoutControl = (data["timeoutControl"] as? Number)?.toInt() ?: 0
            normalIntStatus = (data["normalIntStatus"] as? Number)?.toInt() ?: 0
            errorIntStatus = (data["errorIntStatus"] as? Number)?.toInt() ?: 0
            normalIntStatusEnable = (data["normalIntStatusEnable"] as? Number)?.toInt() ?: 0
            errorIntStatusEnable = (data["errorIntStatusEnable"] as? Number)?.toInt() ?: 0
            normalIntSignalEnable = (data["normalIntSignalEnable"] as? Number)?.toInt() ?: 0
            errorIntSignalEnable = (data["errorIntSignalEnable"] as? Number)?.toInt() ?: 0
            hostControl2 = (data["hostControl2"] as? Number)?.toInt() ?: 0
            admaSysAddrLo = (data["admaSysAddrLo"] as? Number)?.toLong() ?: 0L
            admaSysAddrHi = (data["admaSysAddrHi"] as? Number)?.toLong() ?: 0L
            vendorRegs.fill(0)
            (data["vendorRegsBase64"] as? String)?.let { b64 ->
                val decoded = java.util.Base64.getDecoder().decode(b64)
                decoded.copyInto(vendorRegs, 0, 0, minOf(decoded.size, vendorRegs.size))
            }
            cardInterruptLine = data["cardInterruptLine"] as? Boolean ?: false
            readCount = (data["readCount"] as? Number)?.toLong() ?: 0L
            writeCount = (data["writeCount"] as? Number)?.toLong() ?: 0L
            commandCount = (data["commandCount"] as? Number)?.toLong() ?: 0L
            dmaTransferCount = (data["dmaTransferCount"] as? Number)?.toLong() ?: 0L
            dmaBytesTransferred = (data["dmaBytesTransferred"] as? Number)?.toLong() ?: 0L
            pioReadBuf = null
            pioWriteBuf = null
            pioReadPos = 0
            pioWritePos = 0
            syncIrqs()
        }
    }
}
