package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.SpiDmaFifo
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

internal enum class Spi2Dma(val channel: Int, val request: Long) {
    RX(5, 4), TX(6, 5);

    fun checkGeometry(index: Int, registers: LongArray, control: Long, muxRequest: Long) {
        require(index == channel && muxRequest == request && control == 0x10201011L &&
            registers[4] == SIZE - 1L && registers[9] == 0L) { "unsupported SPI2 DMA route/control/count" }
        require(registers[7] == (if (this == RX) 4L else 0L) &&
            registers[8] == (if (this == TX) 4L else 0L) &&
            select(registers[5], registers[6]) == this) { "unsupported SPI2 DMA byte geometry" }
        val address = ramAddress(registers)
        require(address in 0..0xffffffffL - SIZE) { "SPI2 DMA address wraps" }
    }

    fun checkRam(ram: DmaMemory, registers: LongArray) =
        ram.check(ramAddress(registers), SIZE.toLong(), this == RX)

    fun ready(fifo: SpiDmaFifo) = (if (this == RX) fifo.readableBytes() else fifo.writableBytes()) >= SIZE

    fun transfer(fifo: SpiDmaFifo, ram: DmaMemory, registers: LongArray, auditTransfers: Boolean = true): Map<String, Any> {
        checkRam(ram, registers)
        check(ready(fifo)) { "SPI2 DMA FIFO request withdrawn" }
        val address = ramAddress(registers)
        val data = ByteBuffer.allocate(SIZE)
        if (this == RX) fifo.read(data) else ram.read(address, data)
        check(!data.hasRemaining()) { "short SPI2 DMA source" }
        data.flip()
        if (this == RX) ram.write(address, data) else fifo.write(data)
        check(!data.hasRemaining()) { "short SPI2 DMA destination" }
        if (!auditTransfers) {
            val receipt = mapOf("spi2Fifo" to name, "request" to request, "sourceSha256" to "")
            return if (this == TX) receipt else receipt + ("destinationSha256" to "")
        }
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.array()))
        val receipt = mapOf("spi2Fifo" to name, "request" to request, "sourceSha256" to hash)
        if (this == TX) return receipt
        val verify = ByteBuffer.allocate(SIZE)
        ram.read(address, verify)
        check(!verify.hasRemaining() && verify.array().contentEquals(data.array())) { "SPI2 DMA RAM readback differs" }
        return receipt + ("destinationSha256" to hash)
    }

    private fun ramAddress(registers: LongArray) = registers[if (this == RX) 6 else 5]

    companion object {
        const val PROFILE = "spi2-four-byte-fifo-v1"
        const val PORT = "soc-spi.spi2.dma"
        const val DATA = 0xfff34008L
        const val SIZE = 4

        fun select(source: Long, destination: Long): Spi2Dma? = when {
            source == DATA && destination != DATA -> RX
            destination == DATA && source != DATA -> TX
            else -> null
        }
    }
}
