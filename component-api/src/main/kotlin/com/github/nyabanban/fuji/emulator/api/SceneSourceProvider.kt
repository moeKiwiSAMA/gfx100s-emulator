package com.github.nyabanban.fuji.emulator.api

import java.nio.file.Path

/**
 * Runtime availability state of a [SceneSourceProvider] backend (M-Optics P1–P3).
 *
 * - [READY]: Provider can validate and produce calibrated radiance samples on this host.
 * - [UNSUPPORTED]: Provider is a registered extension point whose host/OS/GPU capture
 *   backend is not attached or unavailable in the current environment.
 * - [NOT_IMPLEMENTED]: Provider is reserved for a planned M-Optics milestone decoder
 *   (e.g., FFmpeg video/RTMP/RTSP or RAF mosaic) and never fabricates placeholder samples.
 */
enum class SceneSourceCapability {
    READY,
    UNSUPPORTED,
    NOT_IMPLEMENTED,
}

/**
 * Validated input descriptor passed to a [SceneSourceProvider].
 *
 * @property kind Logical source kind token (e.g., `JPEG`, `PNG`, `VIDEO_FILE`, `RTMP_RTSP`, `WEBCAM`, `SHARED_TEXTURE`).
 * @property sourceRef User-configured relative path, stream URI, device identifier, or shared-texture channel name.
 * @property loop Whether finite media repeats continuously after its final frame.
 * @property resolveDataPath Resolves component-local relative paths safely inside the `cmos` data directory.
 */
data class SceneSourceRequest(
    val kind: String,
    val sourceRef: String,
    val loop: Boolean,
    val resolveDataPath: (String) -> Path,
)

/**
 * Pluggable backend contract for CMOS [SceneSource] input providers (M-Optics P1–P3).
 *
 * Providers may be built into `components/cmos` or discovered via [java.util.ServiceLoader].
 * Any provider whose [capability] is not [SceneSourceCapability.READY] must refuse [open]
 * without fabricating fake sensor samples.
 */
interface SceneSourceProvider {
    /** Source kind tokens handled by this provider (e.g., `setOf("JPEG", "PNG")`). */
    val supportedKinds: Set<String>

    /** Current readiness of this provider in the active runtime environment. */
    val capability: SceneSourceCapability

    /** Human-readable pipeline status reported in `cmos` state for [kind]. */
    fun pipelineStatus(kind: String): String

    /**
     * Validates [request] prior to persisting or applying settings.
     *
     * @throws IllegalArgumentException if [request] violates path, URI, header, or bounds rules.
     */
    fun validate(request: SceneSourceRequest)

    /**
     * Opens a live [SceneSource] for a validated [request].
     *
     * @throws UnsupportedOperationException when [capability] is not [SceneSourceCapability.READY].
     */
    fun open(request: SceneSourceRequest): SceneSource
}
