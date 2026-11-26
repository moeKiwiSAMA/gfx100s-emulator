package com.github.nyabanban.fuji.emulator.api

/**
 * Execution stage inside the Vulkan Compute optical pipeline where a third-party plugin's
 * custom SPIR-V compute shader runs in-place on `DEVICE_LOCAL` VRAM (zero PCIe round-trips).
 */
enum class CustomSpirvStage(val nativeCode: Int) {
    /**
     * Runs in linear HDR radiance space BEFORE lens geometric warp and depth-of-field bokeh
     * (alongside front-of-lens optical filters). Best suited for [OpticalFilterElement] plugins.
     */
    PRE_OPTICS_FILTER(0),

    /**
     * Runs in linear HDR radiance space AFTER lens optical warp / bokeh and BEFORE CMOS
     * photon shot noise, read noise, and Bayer mosaic readout. Best suited for [LensOpticalHook]
     * specialty lens plugins (e.g. anamorphic streak flare, custom halation, chromatic post-effects).
     */
    POST_OPTICS_RGB(1),
}

/**
 * First-class Plugin SPI v1.0 specification for running a custom SPIR-V compute shader (`.spv`)
 * inside the emulator's Vulkan 1.1 Compute optical pipeline.
 *
 * ### Fixed `set = 0` Plugin Shader ABI
 * Every custom plugin compute shader MUST bind descriptors in `set = 0` as follows:
 * - `binding = 0`: `layout(std430, set = 0, binding = 0) readonly buffer SrcRgba { vec4 px[]; };`
 *   Linear HDR RGBA (`width * height` pixels; `.rgb` = linear scene/sensor radiance, `.w` = depth or alpha).
 * - `binding = 1`: `layout(std430, set = 0, binding = 1) writeonly buffer DstRgba { vec4 px[]; };`
 *   Linear HDR RGBA output (`width * height` pixels).
 * - `binding = 2`: `layout(std430, set = 0, binding = 2) readonly buffer DepthMeters { float depth[]; };`
 *   Per-pixel metric depth in meters (`width * height` floats; `0.0` when depth is unavailable).
 * - `binding = 3`: `layout(std140, set = 0, binding = 3) uniform PluginUbo { ... };`
 *   Standard 32-byte header followed by up to [MAX_UNIFORM_BYTES] (4096 B) of plugin-defined [uniformData]:
 *   - `uint width;` (offset 0)
 *   - `uint height;` (offset 4)
 *   - `float focusM;` (offset 8)
 *   - `float fNumber;` (offset 12)
 *   - `float focalMm;` (offset 16)
 *   - `uint frameIndex;` (offset 20)
 *   - `uint flags;` (offset 24; bit 0 = depth buffer valid)
 *   - `uint userBytes;` (offset 28; byte length of [uniformData])
 *   - `...` plugin uniform payload starting at byte offset 32 (up to 4096 B)
 * - Optional Push Constants: `layout(push_constant) uniform Push { ... };` up to [MAX_PUSH_CONSTANT_BYTES] (128 B),
 *   populated from [pushConstants].
 *
 * ### Runtime Safety & CPU Fallback Contract
 * - Incoming `.spv` bytes are strictly validated before Vulkan pipeline creation (magic `0x07230203`,
 *   size bounds, 4-byte alignment, and SPIR-V instruction framing) and compiled into a 32-entry
 *   LRU pipeline cache keyed by `SHA-256(spirvBytes) + entryPoint + pushConstantSize`.
 * - If validation/compilation fails or Vulkan is unavailable (`opticsBackend: cpu`), the runtime
 *   emits `WARN [FALLBACK][vulkan]` and falls back cleanly to the plugin's CPU implementation
 *   ([OpticalFilterElement.applySpatialLinearRgb] / [LensOpticalHook]) without crashing or
 *   poisoning the Vulkan context.
 */
data class CustomSpirvPassSpec(
    val shaderId: String,
    val spirvBytes: ByteArray,
    val entryPoint: String = "main",
    val workgroupSizeX: Int = 16,
    val workgroupSizeY: Int = 16,
    val uniformData: ByteArray = ByteArray(0),
    val pushConstants: ByteArray = ByteArray(0),
    val stage: CustomSpirvStage = CustomSpirvStage.PRE_OPTICS_FILTER,
    val cacheKey: Long = 0L,
) {
    init {
        require(shaderId.isNotBlank() && shaderId.length <= MAX_SHADER_ID_CHARS) {
            "shaderId must be non-blank and <= $MAX_SHADER_ID_CHARS chars"
        }
        require(spirvBytes.isNotEmpty() && spirvBytes.size <= MAX_SPIRV_BYTES) {
            "spirvBytes size must be in 1..$MAX_SPIRV_BYTES bytes, got ${spirvBytes.size}"
        }
        require(entryPoint.isNotBlank() && entryPoint.length <= MAX_ENTRY_POINT_CHARS) {
            "entryPoint must be non-blank and <= $MAX_ENTRY_POINT_CHARS chars"
        }
        require(workgroupSizeX in MIN_WORKGROUP_SIZE..MAX_WORKGROUP_SIZE) {
            "workgroupSizeX must be in $MIN_WORKGROUP_SIZE..$MAX_WORKGROUP_SIZE, got $workgroupSizeX"
        }
        require(workgroupSizeY in MIN_WORKGROUP_SIZE..MAX_WORKGROUP_SIZE) {
            "workgroupSizeY must be in $MIN_WORKGROUP_SIZE..$MAX_WORKGROUP_SIZE, got $workgroupSizeY"
        }
        require(uniformData.size <= MAX_UNIFORM_BYTES) {
            "uniformData size must be <= $MAX_UNIFORM_BYTES bytes, got ${uniformData.size}"
        }
        require(pushConstants.size <= MAX_PUSH_CONSTANT_BYTES) {
            "pushConstants size must be <= $MAX_PUSH_CONSTANT_BYTES bytes, got ${pushConstants.size}"
        }
    }

    /**
     * Quick structural header check (4-byte alignment, >= 20 bytes, little-endian SPIR-V magic `0x07230203`).
     * Full instruction framing and entry-point verification are also performed natively before Vulkan compilation.
     */
    fun hasValidSpirvHeader(): Boolean {
        if (spirvBytes.size < MIN_SPIRV_BYTES || (spirvBytes.size and 3) != 0) return false
        val magic = (spirvBytes[0].toInt() and 0xFF) or
            ((spirvBytes[1].toInt() and 0xFF) shl 8) or
            ((spirvBytes[2].toInt() and 0xFF) shl 16) or
            ((spirvBytes[3].toInt() and 0xFF) shl 24)
        return magic == SPIRV_MAGIC_LE
    }

    /**
     * Returns an O(1) cache invalidation key combining [cacheKey] (when non-zero) or a deterministic
     * 64-bit content digest over shader bytecode, stage, entry point, workgroup size, uniform data,
     * and push constants.
     */
    fun effectiveCacheKey(): Long {
        if (cacheKey != 0L) return cacheKey
        var h = 0x9E3779B97F4A7C15UL.toLong()
        h = mix64(h, shaderId.hashCode().toLong())
        h = mix64(h, entryPoint.hashCode().toLong())
        h = mix64(h, (stage.nativeCode.toLong() shl 32) or (workgroupSizeX.toLong() shl 16) or workgroupSizeY.toLong())
        h = mix64(h, spirvBytes.size.toLong())
        h = mix64(h, spirvBytes.contentHashCode().toLong())
        h = mix64(h, uniformData.size.toLong())
        h = mix64(h, uniformData.contentHashCode().toLong())
        h = mix64(h, pushConstants.size.toLong())
        h = mix64(h, pushConstants.contentHashCode().toLong())
        return if (h == 0L) 1L else h
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomSpirvPassSpec) return false
        return shaderId == other.shaderId &&
            spirvBytes.contentEquals(other.spirvBytes) &&
            entryPoint == other.entryPoint &&
            workgroupSizeX == other.workgroupSizeX &&
            workgroupSizeY == other.workgroupSizeY &&
            uniformData.contentEquals(other.uniformData) &&
            pushConstants.contentEquals(other.pushConstants) &&
            stage == other.stage &&
            cacheKey == other.cacheKey
    }

    override fun hashCode(): Int {
        var result = shaderId.hashCode()
        result = 31 * result + spirvBytes.contentHashCode()
        result = 31 * result + entryPoint.hashCode()
        result = 31 * result + workgroupSizeX
        result = 31 * result + workgroupSizeY
        result = 31 * result + uniformData.contentHashCode()
        result = 31 * result + pushConstants.contentHashCode()
        result = 31 * result + stage.hashCode()
        result = 31 * result + cacheKey.hashCode()
        return result
    }

    companion object {
        const val SPIRV_MAGIC_LE: Int = 0x07230203
        const val MIN_SPIRV_BYTES: Int = 20
        const val MAX_SPIRV_BYTES: Int = 256 * 1024
        const val STD_UBO_HEADER_BYTES: Int = 32
        const val MAX_UNIFORM_BYTES: Int = 4096
        const val MAX_PUSH_CONSTANT_BYTES: Int = 128
        const val MIN_WORKGROUP_SIZE: Int = 1
        const val MAX_WORKGROUP_SIZE: Int = 64
        const val MAX_SHADER_ID_CHARS: Int = 128
        const val MAX_ENTRY_POINT_CHARS: Int = 128

        /**
         * One-line helper for external plugin JARs to load an embedded `.spv` compute shader
         * resource (e.g. `shaders/my_filter.comp.spv`) from [classLoader].
         */
        @JvmStatic
        @JvmOverloads
        fun fromResource(
            classLoader: ClassLoader,
            resourcePath: String,
            shaderId: String = resourcePath.removePrefix("/"),
            entryPoint: String = "main",
            workgroupSizeX: Int = 16,
            workgroupSizeY: Int = 16,
            uniformData: ByteArray = ByteArray(0),
            pushConstants: ByteArray = ByteArray(0),
            stage: CustomSpirvStage = CustomSpirvStage.PRE_OPTICS_FILTER,
            cacheKey: Long = 0L,
        ): CustomSpirvPassSpec {
            val normalized = resourcePath.removePrefix("/")
            val stream = classLoader.getResourceAsStream(normalized)
                ?: throw IllegalArgumentException("SPIR-V resource not found on classpath: '$normalized'")
            val bytes = stream.use { it.readBytes() }
            return CustomSpirvPassSpec(
                shaderId = shaderId,
                spirvBytes = bytes,
                entryPoint = entryPoint,
                workgroupSizeX = workgroupSizeX,
                workgroupSizeY = workgroupSizeY,
                uniformData = uniformData,
                pushConstants = pushConstants,
                stage = stage,
                cacheKey = cacheKey,
            )
        }

        private fun mix64(acc: Long, value: Long): Long {
            val x = (acc xor value) * 0xBF58476D1CE4E5B9UL.toLong()
            return x xor (x ushr 31)
        }
    }
}
