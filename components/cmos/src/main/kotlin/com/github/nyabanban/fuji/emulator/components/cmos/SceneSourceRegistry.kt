package com.github.nyabanban.fuji.emulator.components.cmos

import com.github.nyabanban.fuji.emulator.api.SceneSource
import com.github.nyabanban.fuji.emulator.api.SceneSourceCapability
import com.github.nyabanban.fuji.emulator.api.SceneSourceProvider
import com.github.nyabanban.fuji.emulator.api.SceneSourceRequest
import java.net.URI
import java.nio.file.Files
import java.util.ServiceLoader

/**
 * Registry mapping CMOS `sourceKind` tokens to pluggable [SceneSourceProvider] backends.
 *
 * Built-in providers supply immediate static-image decoding (`JPEG`, `PNG`) and explicit
 * `NOT_IMPLEMENTED` / `UNSUPPORTED` extension points for planned M-Optics P1–P3 inputs
 * (`H264`, `RAF`, `VIDEO_FILE`, `RTMP_RTSP`, `WEBCAM`, `SHARED_TEXTURE`). External providers
 * registered via constructor injection or [ServiceLoader] override matching kinds cleanly.
 */
class SceneSourceRegistry private constructor(
    private val providersByKind: Map<String, SceneSourceProvider>,
) {
    constructor(providers: List<SceneSourceProvider>) : this(buildProviderMap(providers))

    /** Ordered set of all `sourceKind` values registered in this registry. */
    fun registeredKinds(): Set<String> = providersByKind.keys

    /** Returns the [SceneSourceProvider] for [kind], or `null` if unregistered. */
    fun findProvider(kind: String): SceneSourceProvider? = providersByKind[kind]

    /** Returns the [SceneSourceProvider] for [kind], throwing if unknown. */
    fun providerFor(kind: String): SceneSourceProvider =
        providersByKind[kind] ?: throw IllegalArgumentException("Unsupported CMOS sourceKind: $kind")

    /** Returns a new registry with [provider] registered (overriding any previous provider for its kinds). */
    fun withProvider(provider: SceneSourceProvider): SceneSourceRegistry {
        require(provider.supportedKinds.isNotEmpty()) {
            "SceneSourceProvider ${provider::class.java.name} must declare at least one supportedKind"
        }
        val next = LinkedHashMap(providersByKind)
        for (kind in provider.supportedKinds) {
            require(kind.isNotBlank()) { "SceneSourceProvider kind must not be blank" }
            next[kind] = provider
        }
        return SceneSourceRegistry(next.toMap())
    }

    /** Validates [request] against the registered provider for [SceneSourceRequest.kind]. */
    fun validate(request: SceneSourceRequest) {
        providerFor(request.kind).validate(request)
    }

    /** Opens a [SceneSource] if the provider is [SceneSourceCapability.READY], or returns `null` after validating. */
    fun openIfReady(request: SceneSourceRequest): SceneSource? {
        val provider = providerFor(request.kind)
        if (request.sourceRef.isEmpty()) return null
        provider.validate(request)
        return if (provider.capability == SceneSourceCapability.READY) provider.open(request) else null
    }

    /** Opens a [SceneSource] for [request], throwing [UnsupportedOperationException] if the backend is not ready. */
    fun open(request: SceneSourceRequest): SceneSource {
        val provider = providerFor(request.kind)
        provider.validate(request)
        return provider.open(request)
    }

    companion object {
        private fun buildProviderMap(providers: List<SceneSourceProvider>): Map<String, SceneSourceProvider> {
            val map = LinkedHashMap<String, SceneSourceProvider>()
            for (provider in providers) {
                require(provider.supportedKinds.isNotEmpty()) {
                    "SceneSourceProvider ${provider::class.java.name} must declare at least one supportedKind"
                }
                for (kind in provider.supportedKinds) {
                    require(kind.isNotBlank()) { "SceneSourceProvider kind must not be blank" }
                    map[kind] = provider
                }
            }
            return map.toMap()
        }

        /**
         * Builds the default CMOS [SceneSourceRegistry] combining built-in providers with any
         * [ServiceLoader]-discovered [SceneSourceProvider] plugins on [classLoader].
         */
        fun defaultRegistry(
            classLoader: ClassLoader = SceneSourceRegistry::class.java.classLoader,
        ): SceneSourceRegistry {
            val builtIns: List<SceneSourceProvider> = listOf(
                PlannedSceneSourceProvider(
                    kind = "H264",
                    capability = SceneSourceCapability.NOT_IMPLEMENTED,
                    validationMode = DescriptorValidationMode.LOCAL_FILE,
                    detail = "H264 raw elementary stream decoder not attached (use ffmpeg-pipe or VIDEO_FILE)",
                ),
                PlannedSceneSourceProvider(
                    kind = "RAF",
                    capability = SceneSourceCapability.NOT_IMPLEMENTED,
                    validationMode = DescriptorValidationMode.LOCAL_FILE,
                    detail = "RAF mosaic decoder not attached (calibrated mosaic validation pending)",
                ),
                StaticImageSceneSourceProvider,
                ProceduralOpticsSceneSourceProvider(),
                ScreenCaptureSceneSourceProvider(),
                FfmpegPipeSceneSourceProvider(),
                StreamIngestSceneSourceProvider(),
                PlannedSceneSourceProvider(
                    kind = "SHARED_TEXTURE",
                    capability = SceneSourceCapability.UNSUPPORTED,
                    validationMode = DescriptorValidationMode.DEVICE_OR_CHANNEL,
                    detail = "Shared GPU texture backend unavailable (M-Optics P3 Spout/DXGI/DMA-BUF roadmap)",
                ),
            )
            val discovered = ServiceLoader.load(SceneSourceProvider::class.java, classLoader).toList()
            return SceneSourceRegistry(builtIns + discovered)
        }
    }
}

/** Built-in static image provider supporting `JPEG` and `PNG` via standard `javax.imageio.ImageIO`. */
internal object StaticImageSceneSourceProvider : SceneSourceProvider {
    override val supportedKinds: Set<String> = linkedSetOf("JPEG", "PNG")
    override val capability: SceneSourceCapability = SceneSourceCapability.READY

    override fun pipelineStatus(kind: String): String = when (kind) {
        "JPEG" -> "jpeg-radiance-readout"
        "PNG" -> "png-radiance-readout"
        else -> throw IllegalArgumentException("Unsupported static image kind: $kind")
    }

    override fun validate(request: SceneSourceRequest) {
        open(request).close()
    }

    override fun open(request: SceneSourceRequest): SceneSource {
        val format = when (request.kind) {
            "JPEG" -> StaticImageSceneSource.Format.JPEG
            "PNG" -> StaticImageSceneSource.Format.PNG
            else -> throw IllegalArgumentException("Unsupported static image kind: ${request.kind}")
        }
        val path = request.resolveDataPath(request.sourceRef)
        return StaticImageSceneSource.validateAndLoad(path, request.sourceRef, format, request.loop)
    }
}

internal enum class DescriptorValidationMode {
    LOCAL_FILE,
    STREAM_URI,
    DEVICE_OR_CHANNEL,
}

/**
 * Documented extension-point provider for planned M-Optics P1–P3 input backends.
 *
 * Validates user-supplied descriptors cleanly while explicitly refusing [open] with
 * `NOT_IMPLEMENTED` or `UNSUPPORTED` — never fabricating placeholder sensor bytes.
 */
internal class PlannedSceneSourceProvider(
    private val kind: String,
    override val capability: SceneSourceCapability,
    private val validationMode: DescriptorValidationMode,
    private val detail: String,
) : SceneSourceProvider {
    init {
        require(capability != SceneSourceCapability.READY) {
            "PlannedSceneSourceProvider cannot declare READY capability for $kind"
        }
    }

    override val supportedKinds: Set<String> = setOf(kind)

    override fun pipelineStatus(kind: String): String =
        "${capability.name}: $detail"

    override fun validate(request: SceneSourceRequest) {
        require(request.kind == kind) { "Provider for $kind cannot validate ${request.kind}" }
        val ref = request.sourceRef
        require(ref.isNotEmpty()) { "CMOS sourceFile must not be empty for $kind" }
        when (validationMode) {
            DescriptorValidationMode.LOCAL_FILE -> {
                val path = request.resolveDataPath(ref)
                require(Files.isRegularFile(path)) { "CMOS source missing" }
            }
            DescriptorValidationMode.STREAM_URI -> {
                validateStreamUri(ref)
            }
            DescriptorValidationMode.DEVICE_OR_CHANNEL -> {
                require(ref.isNotBlank() && ref.none { it.isISOControl() }) {
                    "Invalid $kind source identifier"
                }
            }
        }
    }

    override fun open(request: SceneSourceRequest): SceneSource {
        throw UnsupportedOperationException("CMOS sourceKind $kind is ${capability.name}: $detail")
    }

    companion object {
        private val STREAM_SCHEMES = setOf("rtmp", "rtmps", "rtsp", "rtsps")

        private fun validateStreamUri(raw: String) {
            require(raw.none { it.isISOControl() || it.isWhitespace() }) {
                "Invalid RTMP/RTSP URI: contains control or whitespace characters"
            }
            val parsed = try {
                URI(raw)
            } catch (err: Exception) {
                throw IllegalArgumentException("Invalid RTMP/RTSP URI: $raw", err)
            }
            val scheme = parsed.scheme?.lowercase()
            require(scheme in STREAM_SCHEMES) {
                "RTMP_RTSP sourceFile must use rtmp://, rtmps://, rtsp://, or rtsps:// scheme"
            }
            require(!parsed.host.isNullOrBlank()) {
                "RTMP_RTSP sourceFile URI must include a non-empty host"
            }
        }
    }
}
