package com.github.nyabanban.fuji.emulator.api

import java.lang.foreign.MemorySegment

/** Optional packed 422 scaler on an already active compute device. Source is 1024×576 UYVY
 * and destination is tightly packed 640×360. False leaves destination untouched for CPU fallback. */
interface FacePreviewScalerPort {
    val supportsFacePreviewResize: Boolean
    fun resizeFacePreview(source: MemorySegment, destination: MemorySegment, yyuv: Boolean): Boolean
    /** [sourceStrideBytes] counts bytes, with padding excluded after the last active row. */
    fun resizeFacePreview(source: MemorySegment, sourceStrideBytes: Int, destination: MemorySegment, yyuv: Boolean): Boolean =
        sourceStrideBytes == 2048 && resizeFacePreview(source, destination, yyuv)
}
