package com.github.nyabanban.fuji.emulator.api

/** Identity of an explicitly supplied immutable file at a logical image offset. Not physical hardware evidence. */
data class ImageRegionIdentity(val id: String, val offset: Long, val length: Long, val sha256: String, val relativeFile: String)

/** Optional typed discovery; old consumers still read through ReadOnlyImagePort. */
interface SupplementalImagePort : ReadOnlyImagePort {
    val supplementalRegions: List<ImageRegionIdentity>
}
