package com.github.nyabanban.fuji.emulator.api

/** A pure logical-selector description. A selector is not a CPU physical address. */
data class PowerRegisterContract(
    val registerId: String,
    val selector: Int,
    val wordBits: Int,
    val readable: Boolean,
    val writable: Boolean,
)

/** Optional, side-effect-free assembly/query capability; never acquire a connection to describe it. */
interface DescribedPowerRegisterPort : PowerRegisterPort {
    fun registerContracts(): List<PowerRegisterContract>
}

/** Optional header sampling with consumption deferred until a complete validated transport frame. */
interface PreparedPowerRegisterConnection : PowerRegisterConnection {
    fun prepareRead(register: Int, tick: Long): PowerRegisterReadLease
}

/** Owner-thread lease. Preflight is pure; commit revalidates and consumes at most once. */
interface PowerRegisterReadLease {
    val value: Int
    fun checkCommit(tick: Long)
    fun commit(tick: Long)
    /** Idempotent cancellation, including after endpoint close; never consumes an input. */
    fun cancel()
}
