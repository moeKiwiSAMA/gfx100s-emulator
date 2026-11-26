package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.SceneSource2D
import java.nio.file.Path

/**
 * Backward-compatible JPEG-specific entry point delegating to [StaticImageSceneSource].
 *
 * Retained so internal callers and unit tests constructing a JPEG [SceneSource2D] directly
 * remain source-compatible while sharing the unified cached static-image implementation.
 */
internal class JpegSceneSource private constructor(
    private val delegate: StaticImageSceneSource,
) : SceneSource2D by delegate {
    val exhausted: Boolean
        get() = delegate.exhausted

    companion object {
        fun validateExposure(exposure: Exposure) = StaticImageSceneSource.validateExposure(exposure)

        fun validateAndLoad(path: Path, relativePath: String, loop: Boolean): JpegSceneSource =
            JpegSceneSource(
                StaticImageSceneSource.validateAndLoad(
                    path = path,
                    relativePath = relativePath,
                    format = StaticImageSceneSource.Format.JPEG,
                    loop = loop,
                ),
            )
    }
}
