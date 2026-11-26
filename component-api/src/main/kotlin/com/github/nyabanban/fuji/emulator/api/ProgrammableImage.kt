package com.github.nyabanban.fuji.emulator.api

import java.nio.ByteBuffer

/** Optional capability, separate from erase and the ABI1 read-only/mutable interfaces. */
data class ImageProgramReceipt(
    val offset: Long, val size: Int, val changedBytes: Long, val payloadSha256: String,
    val beforeSha256: String, val afterSha256: String, val generation: Long,
)
interface ImageProgramTransaction : AutoCloseable {
    val offset: Long
    val size: Int
    /** Exactly one array commit; prepare is not a DMA, command or array completion. */
    fun commit(): ImageProgramReceipt
}
interface ProgrammableImagePort {
    /** Copies the remaining payload without changing its position. No borrowed host array survives. */
    fun prepareProgram(offset: Long, payload: ByteBuffer): ImageProgramTransaction
}
