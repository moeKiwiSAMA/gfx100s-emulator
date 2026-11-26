package com.github.nyabanban.fuji.emulator.api

/** Owner-thread register endpoint, acquired once at assembly; never a JSON/event hot path. */
interface PowerRegisterPort { fun connect(owner: String): PowerRegisterConnection }
/** A read is sampled once at the SPI header. Writes commit only after transport checksum validation. */
interface PowerRegisterConnection : AutoCloseable {
    /** Frozen unique 7-bit register claims; caller owns the returned array. */
    fun registers(): IntArray
    fun read(register: Int, tick: Long): Int
    /** Must reject without changing any endpoint state. */
    fun checkWrite(register: Int, value: Int, tick: Long)
    /** Revalidate and atomically commit one complete checksum-qualified request. */
    fun write(register: Int, value: Int, tick: Long)
}
