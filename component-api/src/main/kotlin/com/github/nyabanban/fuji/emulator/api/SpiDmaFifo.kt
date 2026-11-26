package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer

interface SpiDmaFifo {
    fun readableBytes(): Int
    fun read(destination: ByteBuffer)
    fun writableBytes(): Int
    fun write(source: ByteBuffer)
}
