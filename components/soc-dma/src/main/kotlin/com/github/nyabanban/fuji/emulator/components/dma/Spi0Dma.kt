package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.SpiDmaFifo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * SPI0 (lenscom, 0xFFF32008) FIFO DMA route verified from section4.bin DMA descriptor table
 * 0x015A7140 (SPI0 rx_id=1, tx_id=0) & 0x015A7FE4 (id=0: ch=2, req=0; id=1: ch=3, req=1).
 * Supports both 4-byte Frame32 words and variable-length Bulk Mode transfers (131B cmd 0x014/0x015,
 * 2051B cmd 0x0C0 Optical EEPROM) streamed in <=4-byte FIFO beats.
 */
internal enum class Spi0Dma(val channel: Int, val request: Long) {
    RX(2, 0), TX(3, 1);

    fun totalBytes(registers: LongArray): Int = (registers[4] + 1L).toInt()

    fun checkGeometry(index: Int, registers: LongArray, control: Long, muxRequest: Long) {
        require(index == channel && muxRequest == request && control == 0x10201011L &&
            registers[4] in 0L until MAX_SIZE.toLong() && registers[9] == 0L) { "unsupported SPI0 DMA route/control/count" }
        require(registers[7] == (if (this == RX) 4L else 0L) &&
            registers[8] == (if (this == TX) 4L else 0L) &&
            select(registers[5], registers[6]) == this) { "unsupported SPI0 DMA byte geometry" }
        val size = totalBytes(registers).toLong()
        val address = ramAddress(registers)
        require(address in 0..0xffffffffL - size) { "SPI0 DMA address wraps" }
    }

    fun checkRam(ram: DmaMemory, registers: LongArray) =
        ram.check(ramAddress(registers), totalBytes(registers).toLong(), this == RX)

    fun beatBytes(fifo: SpiDmaFifo, remaining: Int): Int {
        if (remaining <= 0) return 0
        return if (this == RX) {
            val avail = fifo.readableBytes()
            val threshold = minOf(SIZE, remaining)
            if (avail >= threshold) minOf(remaining, avail) else 0
        } else {
            val avail = fifo.writableBytes()
            val want = minOf(SIZE, remaining)
            if (avail >= want && fifo.readableBytes() <= SIZE) want else 0
        }
    }

    fun ready(fifo: SpiDmaFifo, remaining: Int = SIZE) = beatBytes(fifo, remaining) > 0

    fun transferChunk(fifo: SpiDmaFifo, ram: DmaMemory, registers: LongArray, offset: Long, chunk: Int) {
        val address = ramAddress(registers) + offset
        ram.check(address, chunk.toLong(), this == RX)
        val data = ByteBuffer.allocate(chunk)
        if (this == RX) fifo.read(data) else ram.read(address, data)
        check(!data.hasRemaining()) { "short SPI0 DMA source" }
        data.flip()
        if (this == RX) ram.write(address, data) else fifo.write(data)
        check(!data.hasRemaining()) { "short SPI0 DMA destination" }
    }

    fun finishReceipt(ram: DmaMemory, registers: LongArray, auditTransfers: Boolean = true): Map<String, Any> {
        val hash = if (auditTransfers) {
            val size = totalBytes(registers)
            val address = ramAddress(registers)
            val full = ByteBuffer.allocate(size)
            ram.read(address, full)
            check(!full.hasRemaining()) { "short SPI0 DMA RAM readback" }
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(full.array()))
        } else {
            ""
        }
        val receipt = mapOf("spi0Fifo" to name, "request" to request, "sourceSha256" to hash)
        if (this == TX) return receipt
        return receipt + ("destinationSha256" to hash)
    }

    private fun ramAddress(registers: LongArray) = registers[if (this == RX) 6 else 5]

    companion object {
        const val PROFILE = "spi0-four-byte-fifo-v1"
        const val PORT = "soc-spi.spi0.dma"
        const val DATA = 0xfff32008L
        const val SIZE = 4
        const val MAX_SIZE = 4096

        fun select(source: Long, destination: Long): Spi0Dma? = when {
            source == DATA && destination != DATA -> RX
            destination == DATA && source != DATA -> TX
            else -> null
        }
    }
}
