package com.github.nyabanban.fuji.emulator.api

/** Optional disjoint private copies. Legacy snapshot() remains the explicitly named primary region. */
interface WritableImageSetPort : WritableImagePort {
    val primaryRegionId: String
    fun regionSnapshots(): List<WritableImageState>
    /** Exact whole writable range, never a nearest/default region; selection does not acquire a lease. */
    fun regionForWrite(offset: Long, size: Long): WritableImagePort
}
