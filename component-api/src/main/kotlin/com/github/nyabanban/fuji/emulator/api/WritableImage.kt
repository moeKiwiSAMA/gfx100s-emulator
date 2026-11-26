package com.github.nyabanban.fuji.emulator.api

/** Separate owner-thread mutable lease; the DAT prefix and immutable supplied-region identities never change. */
data class WritableImageState(
    val regionId: String, val offset: Long, val length: Long, val relativeFile: String,
    val writableOffset: Long, val writableLength: Long,
    val seedSha256: String, val currentSha256: String, val generation: Long,
    val pending: Boolean, val closed: Boolean, val failure: String?,
)
data class ImageEraseReceipt(
    val offset: Long, val size: Long, val changedBytes: Long, val beforeSha256: String,
    val afterSha256: String, val generation: Long,
)
interface ImageEraseTransaction : AutoCloseable {
    val offset: Long
    val size: Long
    /** Commit exactly once. Failure must stay observable; never return a success receipt after failed storage. */
    fun commit(): ImageEraseReceipt
}
interface WritableImagePort {
    fun snapshot(): WritableImageState
    /** Whole-range, ownership and storage preflight before controller busy/deadline changes. */
    fun prepareErase(offset: Long, size: Long): ImageEraseTransaction
}
