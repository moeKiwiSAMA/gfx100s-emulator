package com.github.nyabanban.fuji.emulator.api

import java.nio.file.Path

/**
 * Host-supplied path to a unified 64 MiB QSPI NOR Flash backing image (`nor-flash.bin`).
 *
 * Registered before `ComponentHost.load()` when `emulator.yaml` specifies `norFlashFile`,
 * allowing `flash-image` (`imageProfile: raw-nor-image-v1`) to read and persist the live
 * NOR Flash image at the user-visible release path (e.g. `flash/nor-flash.bin`)
 * without loosening `ComponentContext.dataPath()` sandbox containment.
 */
interface NorFlashBackingPort {
    val imagePath: Path

    companion object {
        const val PORT_NAME = "host.nor-flash"
    }
}

/**
 * Optional capability implemented by flash image ports whose full-image SHA-256 digest is lazily
 * cached behind a dirty flag. Periodic `relaxed`-mode `state()` snapshots can inspect
 * [cachedSha256OrNull] / [isSha256Dirty] without triggering a synchronous 64 MiB re-hash on the
 * emulator owner thread, while explicit [ReadOnlyImagePort.sha256], `saveSnapshot()`, and `strict`
 * mode always compute and cache the exact cryptographic digest.
 */
interface LazyDigestImagePort {
    val isSha256Dirty: Boolean

    /** Returns the cached SHA-256 hex digest if clean, or `null` when dirty/uncomputed. */
    fun cachedSha256OrNull(): String?
}
