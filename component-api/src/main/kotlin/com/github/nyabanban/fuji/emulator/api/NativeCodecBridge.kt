package com.github.nyabanban.fuji.emulator.api

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Shared JDK 25 Panama FFM binding to the native codec accelerator library
 * (`libfuji_codec.so` on Linux x86_64, `fuji_codec.dll` on Windows x86_64).
 *
 * Provides:
 * - Multi-threaded C Fujifilm LSRAW Bayer 2x2 Golomb-Rice strip encoder/decoder (bit-exact with Kotlin).
 * - Direct native JPEG YUV422 / BGR24 codec backed by dynamically loaded `libjpeg-turbo`
 *   (`libjpeg.so.8` v80 / `libturbojpeg.so.0` / `turbojpeg.dll`) with zero RGB round-trips.
 *
 * Falls back transparently to pure-Kotlin/JVM execution with a one-shot `WARN [FALLBACK][codec]`
 * log when the native library or dynamic JPEG backend is unavailable or disabled via
 * `-Dfuji.codec.native=false` / `FUJI_CODEC_NATIVE=0`.
 */
object NativeCodecBridge {
    private const val EXPECTED_ABI_VERSION = 1

    /** Capability flag: multi-threaded C LSRAW strip codec available. */
    const val CAP_LSRAW_MT = 1 shl 0

    /** Capability flag: dynamic TurboJPEG (`libturbojpeg`) available. */
    const val CAP_JPEG_TURBO = 1 shl 1

    /** Capability flag: dynamic `libjpeg` v80 (`libjpeg.so.8` / `libjpeg-8.dll`) available. */
    const val CAP_JPEG_LIBJPEG8 = 1 shl 2

    /** Capability flag: x86_64 SSE2 available. */
    const val CAP_SIMD_SSE2 = 1 shl 3

    /** Capability flag: runtime AVX2 detected. */
    const val CAP_SIMD_AVX2 = 1 shl 4

    /** Capability flag: dynamic `libjpeg` v62 (`libjpeg.so.62` / `libjpeg-62.dll`) available. */
    const val CAP_JPEG_LIBJPEG62 = 1 shl 5

    /** Capability flag: built-in zero-dependency pure-C baseline JPEG encoder available. */
    const val CAP_JPEG_BUILTIN = 1 shl 6

    /** Combined capability mask for native JPEG decode backends (TurboJPEG, libjpeg v80, or libjpeg v62). */
    const val CAP_JPEG_DECODE_ANY = CAP_JPEG_TURBO or CAP_JPEG_LIBJPEG8 or CAP_JPEG_LIBJPEG62

    /** Combined capability mask for any native JPEG backend (TurboJPEG, libjpeg v80/v62, or built-in C encoder). */
    const val CAP_JPEG_ANY = CAP_JPEG_DECODE_ANY or CAP_JPEG_BUILTIN

    /** Negative error codes returned by native codec functions. */
    const val ERR_INVALID_ARGS = -1
    const val ERR_OVERFLOW = -2
    const val ERR_CODEC = -3
    const val ERR_NO_BACKEND = -4

    /** Probed JPEG decode geometry returned by [jpegProbeHeader]. */
    data class JpegProbe(
        val srcWidth: Int,
        val srcHeight: Int,
        val outWidth: Int,
        val outHeight: Int,
        val strideBytes: Int,
        val totalBytes: Int,
        val is420: Boolean,
    )

    /** Result of native JPEG encoding ([jpegEncodeUyvy] or [jpegEncodeBgr24]). */
    sealed interface JpegEncodeOutcome {
        data class Success(val jpegBytes: ByteArray) : JpegEncodeOutcome
        data class Overflow(val actualBytes: Int) : JpegEncodeOutcome
        data class Error(val code: Int) : JpegEncodeOutcome
    }

    /** Result of direct off-heap [MemorySegment] JPEG encoding ([jpegEncodeUyvySegmentDirect]). */
    sealed interface JpegEncodeSegmentOutcome {
        data class DirectWritten(val encodedBytes: Int) : JpegEncodeSegmentOutcome
        data class HeapBytes(val jpegBytes: ByteArray) : JpegEncodeSegmentOutcome
        data class Overflow(val actualBytes: Int) : JpegEncodeSegmentOutcome
        data class Error(val code: Int) : JpegEncodeSegmentOutcome
    }

    /** Result of native JPEG decoding ([jpegDecodeToUyvy]). */
    sealed interface JpegDecodeOutcome {
        data class Success(
            val uyvyBytes: ByteArray,
            val outWidth: Int,
            val outHeight: Int,
            val strideBytes: Int,
            val is420: Boolean,
        ) : JpegDecodeOutcome

        data class Error(val code: Int) : JpegDecodeOutcome
    }

    /** Result of direct off-heap [MemorySegment] JPEG decoding ([jpegDecodeToUyvySegment]). */
    sealed interface JpegDecodeDirectOutcome {
        data class Success(
            val bytesWritten: Int,
            val outWidth: Int,
            val outHeight: Int,
            val strideBytes: Int,
            val is420: Boolean,
        ) : JpegDecodeDirectOutcome

        data class Error(val code: Int) : JpegDecodeDirectOutcome
    }

    private class Handles(
        val libraryPath: Path,
        @Suppress("unused") val arena: Arena,
        val capabilities: Int,
        val defaultThreads: Int,
        val backendName: String,
        val codecFree: MethodHandle,
        val lsrawEncodeBayer: MethodHandle,
        val lsrawDecodeBayer: MethodHandle,
        val jpegProbeHeader: MethodHandle,
        val jpegDecodeToUyvy: MethodHandle,
        val jpegDecodeToYyuv: MethodHandle?,
        val jpegEncodeUyvyAlloc: MethodHandle,
        val jpegEncodeYyuvAlloc: MethodHandle?,
        val jpegEncodeBgr24Alloc: MethodHandle,
        val jpegEncodeScaledBgr24: MethodHandle,
    )

    private val log: Logger = Logger.getLogger(NativeCodecBridge::class.java.name)
    private val lsrawFallbackWarned = AtomicBoolean(false)
    private val jpegFallbackWarned = AtomicBoolean(false)

    @Volatile
    var lastLoadError: String? = null
        private set

    private val handles: Handles? by lazy { loadIfEnabled() }

    /** Whether `libfuji_codec` loaded successfully. */
    val isAvailable: Boolean
        get() = handles != null

    /** Whether multi-threaded native C LSRAW encoding/decoding is available. */
    val hasLsraw: Boolean
        get() = ((handles?.capabilities ?: 0) and CAP_LSRAW_MT) != 0

    /** Whether native `libjpeg-turbo` / `libjpeg.so.8` / `libjpeg.so.62` JPEG acceleration is available. */
    val hasJpeg: Boolean
        get() = ((handles?.capabilities ?: 0) and CAP_JPEG_ANY) != 0

    /** Bitmask of [CAP_LSRAW_MT], [CAP_JPEG_TURBO], [CAP_JPEG_LIBJPEG8], [CAP_JPEG_LIBJPEG62], [CAP_SIMD_SSE2], [CAP_SIMD_AVX2]. */
    val capabilities: Int
        get() = handles?.capabilities ?: 0

    /** Default worker thread count reported by the native codec library. */
    val defaultThreads: Int
        get() = handles?.defaultThreads ?: 1

    /** Human-readable backend identifier (e.g. `native-mt-avx2+libjpeg8-yuv422`). */
    val backendName: String
        get() = handles?.backendName ?: "jvm-fallback"

    /** Resolved path of the loaded native library, or `null` if unavailable. */
    val libraryPath: Path?
        get() = handles?.libraryPath

    /** Logs a one-shot `WARN [FALLBACK][codec]` when LSRAW falls back to pure Kotlin. */
    fun warnLsrawFallbackOnce(reason: String? = null) {
        if (lsrawFallbackWarned.compareAndSet(false, true)) {
            val detail = reason ?: lastLoadError ?: "native codec disabled"
            log.warning("[FALLBACK][codec] Native LSRAW bridge unavailable ($detail); using Kotlin LsrawCodecEngine fallback")
        }
    }

    /** Logs a one-shot `WARN [FALLBACK][codec]` when JPEG falls back to JVM ImageIO. */
    fun warnJpegFallbackOnce(reason: String? = null) {
        if (jpegFallbackWarned.compareAndSet(false, true)) {
            val detail = reason ?: if (handles != null) {
                "libjpeg/libturbojpeg not present on host (backend=${handles?.backendName})"
            } else {
                lastLoadError ?: "native codec disabled"
            }
            log.warning("[FALLBACK][codec] Native JPEG bridge unavailable ($detail); using optimized JVM ImageIO fallback")
        }
    }

    private fun isDisabledByConfig(): Boolean {
        val prop = System.getProperty("fuji.codec.native")?.trim()?.lowercase()
        if (prop == "false" || prop == "0" || prop == "off") return true
        val env = System.getenv("FUJI_CODEC_NATIVE")?.trim()?.lowercase()
        return env == "false" || env == "0" || env == "off"
    }

    /** Resolves `libfuji_codec.so` / `fuji_codec.dll` across explicit overrides, sibling native dirs, and `.tools`. */
    fun resolveLibraryPath(
        isWindowsOverride: Boolean? = null,
        envProvider: (String) -> String? = System::getenv,
        propProvider: (String) -> String? = System::getProperty,
    ): Path? {
        val isWindows = isWindowsOverride ?: (propProvider("os.name") ?: "").lowercase().contains("win")
        val libName = if (isWindows) "fuji_codec.dll" else "libfuji_codec.so"
        val buildSubdir = if (isWindows) "build-win64" else "build-linux"
        val cacheSubdir = if (isWindows) "codec-bridge-win64" else "codec-bridge-linux"

        val candidates = LinkedHashSet<Path>(24)
        fun addIfPresent(raw: String?) {
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(Paths.get(it)) }
        }
        fun addSiblingOf(raw: String?) {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            runCatching {
                val p = Paths.get(trimmed).toAbsolutePath().normalize()
                val dir = if (Files.isDirectory(p)) p else p.parent
                if (dir != null) candidates.add(dir.resolve(libName))
            }
        }
        fun addHomeCandidates(base: Path) {
            val normalized = base.toAbsolutePath().normalize()
            candidates.add(normalized.resolve("runtime/native/$libName"))
            candidates.add(normalized.resolve("native/$libName"))
            candidates.add(normalized.resolve(".tools/codec-bridge/$buildSubdir/$libName"))
            candidates.add(normalized.resolve(".tools/codec-bridge/build/$libName"))
            candidates.add(normalized.resolve("build/codec-bridge/$libName"))
        }

        addIfPresent(propProvider("fuji.codec.library"))
        addIfPresent(envProvider("FUJI_CODEC_LIBRARY"))
        if (isWindows) addIfPresent(envProvider("FUJI_WIN64_CODEC_DLL"))

        propProvider("fuji.home")?.trim()?.takeIf { it.isNotEmpty() }?.let { addHomeCandidates(Paths.get(it)) }

        addSiblingOf(propProvider("fuji.render.library"))
        addSiblingOf(envProvider("FUJI_RENDER_LIBRARY"))
        if (isWindows) addSiblingOf(envProvider("FUJI_WIN64_RENDER_DLL"))
        addSiblingOf(propProvider("fuji.dynarmic.library"))
        addSiblingOf(envProvider("FUJI_DYNARMIC_LIBRARY"))
        addSiblingOf(envProvider("FUJI_AUDIO_LIBRARY"))
        addSiblingOf(envProvider("FUJI_UNICORN_LIBRARY"))

        runCatching {
            val loc = NativeCodecBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (loc != null) {
                var dir = Paths.get(loc).toAbsolutePath().normalize()
                if (Files.isRegularFile(dir)) dir = dir.parent
                repeat(6) {
                    if (dir != null) {
                        candidates.add(dir.resolve("runtime/native/$libName"))
                        candidates.add(dir.resolve("native/$libName"))
                        candidates.add(dir.resolve(".tools/codec-bridge/$buildSubdir/$libName"))
                        dir = dir.parent
                    }
                }
            }
        }

        var cwd: Path? = Paths.get("").toAbsolutePath().normalize()
        repeat(6) {
            val current = cwd
            if (current != null) {
                addHomeCandidates(current)
                cwd = current.parent
            }
        }

        candidates.add(Paths.get("/opt/fuji-cache/codec-bridge/$buildSubdir/$libName"))
        candidates.add(Paths.get("/opt/fuji-cache/$cacheSubdir/$libName"))
        candidates.add(Paths.get("/opt/fuji-cache/codec-bridge/$libName"))

        return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
    }

    private fun loadIfEnabled(): Handles? {
        if (isDisabledByConfig()) {
            lastLoadError = "Disabled via fuji.codec.native / FUJI_CODEC_NATIVE"
            return null
        }
        val path = resolveLibraryPath()
        if (path == null) {
            lastLoadError = "Library not found in search paths (cwd=${Paths.get("").toAbsolutePath().normalize()})"
            return null
        }
        return try {
            val arena = Arena.ofShared()
            val lookup = SymbolLookup.libraryLookup(path.toAbsolutePath().normalize(), arena)
            val linker = Linker.nativeLinker()

            val abiSym = lookup.find("fuji_codec_abi_version").orElse(null)
            if (abiSym == null) {
                lastLoadError = "Missing fuji_codec_abi_version in $path"
                arena.close()
                return null
            }
            val abi = linker.downcallHandle(abiSym, FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact() as Int
            if (abi != EXPECTED_ABI_VERSION) {
                lastLoadError = "ABI mismatch: expected $EXPECTED_ABI_VERSION, got $abi at $path"
                arena.close()
                return null
            }

            val caps = linker.downcallHandle(
                lookup.find("fuji_codec_capabilities").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
            ).invokeExact() as Int
            val threads = linker.downcallHandle(
                lookup.find("fuji_codec_default_threads").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
            ).invokeExact() as Int
            val namePtr = linker.downcallHandle(
                lookup.find("fuji_codec_backend_name").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS),
            ).invokeExact() as MemorySegment
            val name = namePtr.reinterpret(128).getString(0)

            val codecFree = linker.downcallHandle(
                lookup.find("fuji_codec_free").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
            )
            val lsrawEncode = linker.downcallHandle(
                lookup.find("fuji_lsraw_encode_bayer").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // src_raw_le16
                    ValueLayout.JAVA_INT, // src_stride_bytes
                    ValueLayout.JAVA_INT, // active_width
                    ValueLayout.JAVA_INT, // active_height
                    ValueLayout.JAVA_INT, // blocks_x
                    ValueLayout.JAVA_INT, // blocks_y
                    ValueLayout.JAVA_INT, // strip_pixels
                    ValueLayout.JAVA_INT, // raw_bits
                    ValueLayout.JAVA_INT, // lossy_compressed
                    ValueLayout.JAVA_INT, // max_cap_bytes
                    ValueLayout.JAVA_INT, // max_threads
                    ValueLayout.ADDRESS,  // dst_stream
                    ValueLayout.JAVA_INT, // dst_capacity
                    ValueLayout.ADDRESS,  // out_required_bytes
                ),
            )
            val lsrawDecode = linker.downcallHandle(
                lookup.find("fuji_lsraw_decode_bayer").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // src_stream
                    ValueLayout.JAVA_INT, // src_stream_len
                    ValueLayout.JAVA_INT, // dst_stride_bytes
                    ValueLayout.JAVA_INT, // max_threads
                    ValueLayout.ADDRESS,  // dst_raw_le16
                    ValueLayout.JAVA_INT, // dst_capacity
                    ValueLayout.ADDRESS,  // out_meta
                ),
            )
            val jpegProbe = linker.downcallHandle(
                lookup.find("fuji_jpeg_probe_header").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // src_jpeg
                    ValueLayout.JAVA_INT, // src_jpeg_len
                    ValueLayout.JAVA_INT, // downscale_shift
                    ValueLayout.JAVA_INT, // crop_enabled
                    ValueLayout.JAVA_INT, // crop_x0
                    ValueLayout.JAVA_INT, // crop_x1
                    ValueLayout.JAVA_INT, // crop_y0
                    ValueLayout.JAVA_INT, // crop_y1
                    ValueLayout.ADDRESS,  // out_meta
                ),
            )
            val jpegDecodeDesc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,  // src_jpeg
                ValueLayout.JAVA_INT, // src_jpeg_len
                ValueLayout.JAVA_INT, // downscale_shift
                ValueLayout.JAVA_INT, // crop_enabled
                ValueLayout.JAVA_INT, // crop_x0
                ValueLayout.JAVA_INT, // crop_x1
                ValueLayout.JAVA_INT, // crop_y0
                ValueLayout.JAVA_INT, // crop_y1
                ValueLayout.ADDRESS,  // dst_422
                ValueLayout.JAVA_INT, // dst_capacity
                ValueLayout.ADDRESS,  // out_meta
            )
            val jpegDecode = linker.downcallHandle(lookup.find("fuji_jpeg_decode_to_uyvy").orElseThrow(), jpegDecodeDesc)
            val jpegDecodeYyuv = lookup.find("fuji_jpeg_decode_to_yyuv").map {
                linker.downcallHandle(it, jpegDecodeDesc)
            }.orElse(null)
            val jpegEnc422Desc = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,  // src_422
                ValueLayout.JAVA_INT, // width
                ValueLayout.JAVA_INT, // height
                ValueLayout.JAVA_INT, // src_stride_bytes
                ValueLayout.JAVA_INT, // quality
                ValueLayout.JAVA_INT, // max_bytes
                ValueLayout.ADDRESS,  // out_buf (uint8_t**)
                ValueLayout.ADDRESS,  // out_actual_size (int32_t*)
            )
            val jpegEncUyvy = linker.downcallHandle(lookup.find("fuji_jpeg_encode_uyvy_alloc").orElseThrow(), jpegEnc422Desc)
            val jpegEncYyuv = lookup.find("fuji_jpeg_encode_yyuv_alloc").map {
                linker.downcallHandle(it, jpegEnc422Desc)
            }.orElse(null)
            val jpegEncBgr = linker.downcallHandle(
                lookup.find("fuji_jpeg_encode_bgr24_alloc").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // src_bgr24
                    ValueLayout.JAVA_INT, // width
                    ValueLayout.JAVA_INT, // height
                    ValueLayout.JAVA_INT, // src_stride_bytes
                    ValueLayout.JAVA_INT, // quality
                    ValueLayout.JAVA_INT, // max_bytes
                    ValueLayout.ADDRESS,  // out_buf (uint8_t**)
                    ValueLayout.ADDRESS,  // out_actual_size (int32_t*)
                ),
            )
            val jpegEncScaledBgr = linker.downcallHandle(
                lookup.find("fuji_codec_jpeg_encode_scaled_bgr24").orElseThrow(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,  // src_bgr
                    ValueLayout.JAVA_INT, // src_w
                    ValueLayout.JAVA_INT, // src_h
                    ValueLayout.JAVA_INT, // crop_x
                    ValueLayout.JAVA_INT, // crop_y
                    ValueLayout.JAVA_INT, // crop_w
                    ValueLayout.JAVA_INT, // crop_h
                    ValueLayout.JAVA_INT, // dst_w
                    ValueLayout.JAVA_INT, // dst_h
                    ValueLayout.JAVA_INT, // quality
                    ValueLayout.ADDRESS,  // dst_jpeg
                    ValueLayout.JAVA_INT, // dst_cap
                    ValueLayout.ADDRESS,  // out_len
                ),
            )

            Handles(
                libraryPath = path,
                arena = arena,
                capabilities = caps,
                defaultThreads = threads,
                backendName = name,
                codecFree = codecFree,
                lsrawEncodeBayer = lsrawEncode,
                lsrawDecodeBayer = lsrawDecode,
                jpegProbeHeader = jpegProbe,
                jpegDecodeToUyvy = jpegDecode,
                jpegDecodeToYyuv = jpegDecodeYyuv,
                jpegEncodeUyvyAlloc = jpegEncUyvy,
                jpegEncodeYyuvAlloc = jpegEncYyuv,
                jpegEncodeBgr24Alloc = jpegEncBgr,
                jpegEncodeScaledBgr24 = jpegEncScaledBgr,
            )
        } catch (t: Throwable) {
            lastLoadError = "${t.javaClass.simpleName}: ${t.message} (path=$path)"
            null
        }
    }

    /**
     * Multi-threaded native C LSRAW Bayer encoder operating directly on off-heap [MemorySegment] buffers.
     * Returns encoded byte count (>0) or negative `ERR_*`. On [ERR_OVERFLOW], writes exact required bytes to
     * [outRequiredBytes] if non-null.
     */
    fun lsrawEncodeBayerSegment(
        srcRawLe16: MemorySegment,
        srcStrideBytes: Int,
        activeWidth: Int,
        activeHeight: Int,
        blocksX: Int,
        blocksY: Int,
        stripPixels: Int,
        rawBits: Int,
        lossyCompressed: Boolean,
        maxCapBytes: Int,
        maxThreads: Int,
        dstStream: MemorySegment,
        dstCapacity: Int,
        outRequiredBytes: MemorySegment = MemorySegment.NULL,
    ): Int {
        val h = handles ?: return ERR_NO_BACKEND
        return h.lsrawEncodeBayer.invokeExact(
            srcRawLe16,
            srcStrideBytes,
            activeWidth,
            activeHeight,
            blocksX,
            blocksY,
            stripPixels,
            rawBits,
            if (lossyCompressed) 1 else 0,
            maxCapBytes,
            maxThreads,
            dstStream,
            dstCapacity,
            outRequiredBytes,
        ) as Int
    }

    /**
     * Multi-threaded native C LSRAW Bayer decoder operating directly on off-heap [MemorySegment] buffers.
     * Returns decoded byte count (>0) or negative `ERR_*`.
     */
    fun lsrawDecodeBayerSegment(
        srcStream: MemorySegment,
        srcStreamLen: Int,
        dstStrideBytes: Int,
        maxThreads: Int,
        dstRawLe16: MemorySegment,
        dstCapacity: Int,
        outMeta: MemorySegment = MemorySegment.NULL,
    ): Int {
        val h = handles ?: return ERR_NO_BACKEND
        return h.lsrawDecodeBayer.invokeExact(
            srcStream,
            srcStreamLen,
            dstStrideBytes,
            maxThreads,
            dstRawLe16,
            dstCapacity,
            outMeta,
        ) as Int
    }

    /**
     * Probes JPEG header and Milbeaut MemYc decode geometry directly from an off-heap
     * [MemorySegment] (e.g. [DirectGuestRamPort.sliceDirect]) with zero input copy.
     */
    /**
     * Lock-free per-thread 64-byte native scratch for zero-copy direct-RAM JPEG probe/decode/encode
     * metadata out-parameters (eliminates per-frame `Arena.ofConfined()` allocation churn).
     * Layout: `[0..28)` 7x int32 meta, `[32..40)` outBufPtr address, `[40..44)` outActualPtr int32.
     */
    private val directCallScratch: ThreadLocal<MemorySegment> = ThreadLocal.withInitial {
        Arena.ofAuto().allocate(64L, 8L)
    }

    fun jpegProbeHeaderSegment(
        srcJpeg: MemorySegment,
        srcJpegLen: Int,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropX0: Int,
        cropX1: Int,
        cropY0: Int,
        cropY1: Int,
    ): Pair<Int, JpegProbe?> {
        val h = handles ?: return ERR_NO_BACKEND to null
        if ((h.capabilities and CAP_JPEG_DECODE_ANY) == 0) {
            return ERR_NO_BACKEND to null
        }
        val metaSeg = directCallScratch.get()
        val rc = h.jpegProbeHeader.invokeExact(
            srcJpeg,
            srcJpegLen,
            downscaleShift,
            if (cropEnabled) 1 else 0,
            cropX0,
            cropX1,
            cropY0,
            cropY1,
            metaSeg,
        ) as Int
        if (rc != 0) return rc to null
        return 0 to JpegProbe(
            srcWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 0L),
            srcHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 1L),
            outWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 2L),
            outHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 3L),
            strideBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 4L),
            totalBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 5L),
            is420 = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 6L) != 0,
        )
    }

    /**
     * Decodes a JPEG bitstream directly from an off-heap [srcJpeg] [MemorySegment] into an
     * off-heap [dstUyvy] [MemorySegment] via native `libjpeg-turbo` with zero intermediate heap
     * buffers.
     */
    @JvmOverloads
    fun jpegDecodeToUyvySegment(
        srcJpeg: MemorySegment,
        srcJpegLen: Int,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropX0: Int,
        cropX1: Int,
        cropY0: Int,
        cropY1: Int,
        dstUyvy: MemorySegment,
        dstCapacity: Int,
        yyuv422: Boolean = false,
    ): JpegDecodeDirectOutcome {
        val h = handles ?: return JpegDecodeDirectOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_DECODE_ANY) == 0) {
            return JpegDecodeDirectOutcome.Error(ERR_NO_BACKEND)
        }
        val fn = if (yyuv422) (h.jpegDecodeToYyuv ?: return JpegDecodeDirectOutcome.Error(ERR_NO_BACKEND)) else h.jpegDecodeToUyvy
        val metaSeg = directCallScratch.get()
        val rc = fn.invokeExact(
            srcJpeg,
            srcJpegLen,
            downscaleShift,
            if (cropEnabled) 1 else 0,
            cropX0,
            cropX1,
            cropY0,
            cropY1,
            dstUyvy,
            dstCapacity,
            metaSeg,
        ) as Int
        if (rc <= 0) return JpegDecodeDirectOutcome.Error(rc)
        return JpegDecodeDirectOutcome.Success(
            bytesWritten = rc,
            outWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 0L),
            outHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 1L),
            strideBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 2L),
            is420 = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 4L) != 0,
        )
    }

    /**
     * Encodes packed 4:2:2 directly from an off-heap [srcUyvy] [MemorySegment] via native
     * `libjpeg-turbo` with zero source copy. When [directDstWriter] returns `true`, the native
     * JPEG buffer is copied directly into guest RAM without allocating an intermediate `ByteArray`.
     */
    fun jpegEncodeUyvySegmentDirect(
        srcUyvy: MemorySegment,
        width: Int,
        height: Int,
        srcStrideBytes: Int,
        quality: Int,
        maxBytes: Long,
        yyuv422: Boolean = false,
        directDstWriter: (nativeJpeg: MemorySegment, actualBytes: Int) -> Boolean,
    ): JpegEncodeSegmentOutcome {
        val h = handles ?: return JpegEncodeSegmentOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_ANY) == 0) {
            return JpegEncodeSegmentOutcome.Error(ERR_NO_BACKEND)
        }
        val fn = if (yyuv422) (h.jpegEncodeYyuvAlloc ?: return JpegEncodeSegmentOutcome.Error(ERR_NO_BACKEND)) else h.jpegEncodeUyvyAlloc
        val cappedMax = if (maxBytes <= 0L) 0 else maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val scratch = directCallScratch.get()
        val outBufPtr = scratch.asSlice(32L, ValueLayout.ADDRESS.byteSize())
        val outActualPtr = scratch.asSlice(40L, ValueLayout.JAVA_INT.byteSize())
        outBufPtr.set(ValueLayout.ADDRESS, 0L, MemorySegment.NULL)
        outActualPtr.set(ValueLayout.JAVA_INT, 0L, 0)
        val rc = fn.invokeExact(
            srcUyvy,
            width,
            height,
            srcStrideBytes,
            quality,
            cappedMax,
            outBufPtr,
            outActualPtr,
        ) as Int
        val nativePtr = outBufPtr.get(ValueLayout.ADDRESS, 0L)
        return try {
            when {
                rc > 0 && nativePtr != MemorySegment.NULL -> {
                    val nativeSlice = nativePtr.reinterpret(rc.toLong())
                    if (directDstWriter(nativeSlice, rc)) {
                        JpegEncodeSegmentOutcome.DirectWritten(rc)
                    } else {
                        val out = ByteArray(rc)
                        MemorySegment.copy(nativeSlice, ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
                        JpegEncodeSegmentOutcome.HeapBytes(out)
                    }
                }
                rc == ERR_OVERFLOW -> JpegEncodeSegmentOutcome.Overflow(outActualPtr.get(ValueLayout.JAVA_INT, 0L))
                else -> JpegEncodeSegmentOutcome.Error(rc)
            }
        } finally {
            if (nativePtr != MemorySegment.NULL) {
                h.codecFree.invokeExact(nativePtr)
            }
        }
    }

    /**
     * Probes JPEG header and Milbeaut MemYc decode geometry via native `libjpeg-turbo`.
     * Returns `null` if native JPEG backend is unavailable, or [Result] containing [JpegProbe] / error code.
     */
    fun jpegProbeHeader(
        jpegBytes: ByteArray,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropX0: Int,
        cropX1: Int,
        cropY0: Int,
        cropY1: Int,
    ): Pair<Int, JpegProbe?> {
        val h = handles ?: return ERR_NO_BACKEND to null
        if ((h.capabilities and CAP_JPEG_DECODE_ANY) == 0) {
            return ERR_NO_BACKEND to null
        }
        Arena.ofConfined().use { arena ->
            val srcSeg = arena.allocate(jpegBytes.size.toLong())
            MemorySegment.copy(jpegBytes, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, jpegBytes.size)
            val metaSeg = arena.allocate(ValueLayout.JAVA_INT, 7L)
            val rc = h.jpegProbeHeader.invokeExact(
                srcSeg,
                jpegBytes.size,
                downscaleShift,
                if (cropEnabled) 1 else 0,
                cropX0,
                cropX1,
                cropY0,
                cropY1,
                metaSeg,
            ) as Int
            if (rc != 0) return rc to null
            return 0 to JpegProbe(
                srcWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 0L),
                srcHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 1L),
                outWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 2L),
                outHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 3L),
                strideBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 4L),
                totalBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 5L),
                is420 = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 6L) != 0,
            )
        }
    }

    /**
     * Decodes a JPEG bitstream directly into packed aligned UYVY422 using native `libjpeg-turbo`.
     */
    @JvmOverloads
    fun jpegDecodeToUyvy(
        jpegBytes: ByteArray,
        downscaleShift: Int,
        cropEnabled: Boolean,
        cropX0: Int,
        cropX1: Int,
        cropY0: Int,
        cropY1: Int,
        expectedTotalBytes: Int,
        yyuv422: Boolean = false,
    ): JpegDecodeOutcome {
        val h = handles ?: return JpegDecodeOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_DECODE_ANY) == 0) {
            return JpegDecodeOutcome.Error(ERR_NO_BACKEND)
        }
        val fn = if (yyuv422) (h.jpegDecodeToYyuv ?: return JpegDecodeOutcome.Error(ERR_NO_BACKEND)) else h.jpegDecodeToUyvy
        Arena.ofConfined().use { arena ->
            val srcSeg = arena.allocate(jpegBytes.size.toLong())
            MemorySegment.copy(jpegBytes, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, jpegBytes.size)
            val dstSeg = arena.allocate(expectedTotalBytes.toLong())
            val metaSeg = arena.allocate(ValueLayout.JAVA_INT, 5L)
            val rc = fn.invokeExact(
                srcSeg,
                jpegBytes.size,
                downscaleShift,
                if (cropEnabled) 1 else 0,
                cropX0,
                cropX1,
                cropY0,
                cropY1,
                dstSeg,
                expectedTotalBytes,
                metaSeg,
            ) as Int
            if (rc <= 0) return JpegDecodeOutcome.Error(rc)
            val out = ByteArray(rc)
            MemorySegment.copy(dstSeg, ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
            return JpegDecodeOutcome.Success(
                uyvyBytes = out,
                outWidth = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 0L),
                outHeight = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 1L),
                strideBytes = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 2L),
                is420 = metaSeg.getAtIndex(ValueLayout.JAVA_INT, 4L) != 0,
            )
        }
    }

    /**
     * Encodes packed 4:2:2 (MemYc) directly to a 4:2:2 JPEG bitstream via native `libjpeg-turbo`
     * with zero RGB round-trip and exact Milbeaut black-fill sanitization.
     */
    @JvmOverloads
    fun jpegEncodeUyvy(
        uyvyBytes: ByteArray,
        width: Int,
        height: Int,
        srcStrideBytes: Int,
        quality: Int,
        maxBytes: Long,
        yyuv422: Boolean = false,
    ): JpegEncodeOutcome {
        val h = handles ?: return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_ANY) == 0) {
            return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        }
        val fn = if (yyuv422) (h.jpegEncodeYyuvAlloc ?: return JpegEncodeOutcome.Error(ERR_NO_BACKEND)) else h.jpegEncodeUyvyAlloc
        val cappedMax = if (maxBytes <= 0L) 0 else maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val copyLen = minOf(uyvyBytes.size.toLong(), (height - 1).toLong() * srcStrideBytes.toLong() + width.toLong() * 2L).toInt()
        Arena.ofConfined().use { arena ->
            val srcSeg = arena.allocate(copyLen.toLong())
            MemorySegment.copy(uyvyBytes, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, copyLen)
            val outBufPtr = arena.allocate(ValueLayout.ADDRESS)
            val outActualPtr = arena.allocate(ValueLayout.JAVA_INT)
            val rc = fn.invokeExact(
                srcSeg,
                width,
                height,
                srcStrideBytes,
                quality,
                cappedMax,
                outBufPtr,
                outActualPtr,
            ) as Int
            val nativePtr = outBufPtr.get(ValueLayout.ADDRESS, 0L)
            return try {
                when {
                    rc > 0 && nativePtr != MemorySegment.NULL -> {
                        val out = ByteArray(rc)
                        MemorySegment.copy(nativePtr.reinterpret(rc.toLong()), ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
                        JpegEncodeOutcome.Success(out)
                    }
                    rc == ERR_OVERFLOW -> JpegEncodeOutcome.Overflow(outActualPtr.get(ValueLayout.JAVA_INT, 0L))
                    else -> JpegEncodeOutcome.Error(rc)
                }
            } finally {
                if (nativePtr != MemorySegment.NULL) {
                    h.codecFree.invokeExact(nativePtr)
                }
            }
        }
    }

    /**
     * Encodes packed BGR24 pixels directly to a JPEG bitstream via native `libjpeg-turbo`.
     */
    fun jpegEncodeBgr24(
        bgr24Bytes: ByteArray,
        width: Int,
        height: Int,
        srcStrideBytes: Int,
        quality: Int,
        maxBytes: Long,
    ): JpegEncodeOutcome {
        val h = handles ?: return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_ANY) == 0) {
            return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        }
        val cappedMax = if (maxBytes <= 0L) 0 else maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val copyLen = minOf(bgr24Bytes.size.toLong(), (height - 1).toLong() * srcStrideBytes.toLong() + width.toLong() * 3L).toInt()
        Arena.ofConfined().use { arena ->
            val srcSeg = arena.allocate(copyLen.toLong())
            MemorySegment.copy(bgr24Bytes, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, copyLen)
            val outBufPtr = arena.allocate(ValueLayout.ADDRESS)
            val outActualPtr = arena.allocate(ValueLayout.JAVA_INT)
            val rc = h.jpegEncodeBgr24Alloc.invokeExact(
                srcSeg,
                width,
                height,
                srcStrideBytes,
                quality,
                cappedMax,
                outBufPtr,
                outActualPtr,
            ) as Int
            val nativePtr = outBufPtr.get(ValueLayout.ADDRESS, 0L)
            return try {
                when {
                    rc > 0 && nativePtr != MemorySegment.NULL -> {
                        val out = ByteArray(rc)
                        MemorySegment.copy(nativePtr.reinterpret(rc.toLong()), ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
                        JpegEncodeOutcome.Success(out)
                    }
                    rc == ERR_OVERFLOW -> JpegEncodeOutcome.Overflow(outActualPtr.get(ValueLayout.JAVA_INT, 0L))
                    else -> JpegEncodeOutcome.Error(rc)
                }
            } finally {
                if (nativePtr != MemorySegment.NULL) {
                    h.codecFree.invokeExact(nativePtr)
                }
            }
        }
    }

    private class ScaledBgrScratch {
        private var srcCap: Long = 0L
        private var srcSeg: MemorySegment = MemorySegment.NULL
        private var dstCap: Long = 0L
        private var dstSeg: MemorySegment = MemorySegment.NULL
        val outLenSeg: MemorySegment = Arena.ofAuto().allocate(ValueLayout.JAVA_INT, 1L)

        fun ensureSrc(neededBytes: Long): MemorySegment {
            if (neededBytes > srcCap) {
                val alloc = maxOf(neededBytes, 2L * 1024L * 1024L)
                srcSeg = Arena.ofAuto().allocate(alloc, 16L)
                srcCap = alloc
            }
            return srcSeg
        }

        fun ensureDst(neededBytes: Long): MemorySegment {
            if (neededBytes > dstCap) {
                val alloc = maxOf(neededBytes, 4L * 1024L * 1024L)
                dstSeg = Arena.ofAuto().allocate(alloc, 16L)
                dstCap = alloc
            }
            return dstSeg
        }

        val dstCapacity: Int
            get() = dstCap.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private val scaledBgrScratch: ThreadLocal<ScaledBgrScratch> = ThreadLocal.withInitial {
        ScaledBgrScratch()
    }

    /**
     * Scales a cropped BGR24 source frame in L2-hot native row strips and encodes directly to JPEG
     * using persistent per-thread FFM scratch buffers (zero per-call `Arena.ofConfined()` and zero
     * full-frame intermediate `dstBgr` allocation).
     */
    fun jpegEncodeScaledBgr24(
        srcBgr: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        dstWidth: Int,
        dstHeight: Int,
        quality: Int,
        maxBytes: Long,
    ): JpegEncodeOutcome {
        val h = handles ?: return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        if ((h.capabilities and CAP_JPEG_ANY) == 0) {
            return JpegEncodeOutcome.Error(ERR_NO_BACKEND)
        }
        if (srcWidth <= 0 || srcHeight <= 0 || cropWidth <= 0 || cropHeight <= 0 ||
            cropX < 0 || cropY < 0 || cropX + cropWidth > srcWidth || cropY + cropHeight > srcHeight ||
            dstWidth <= 0 || dstHeight <= 0
        ) {
            return JpegEncodeOutcome.Error(ERR_INVALID_ARGS)
        }
        val srcBytesNeeded = srcWidth.toLong() * srcHeight.toLong() * 3L
        if (srcBytesNeeded > srcBgr.size.toLong() || srcBytesNeeded > Int.MAX_VALUE.toLong()) {
            return JpegEncodeOutcome.Error(ERR_INVALID_ARGS)
        }

        val scratch = scaledBgrScratch.get()
        val srcSeg = scratch.ensureSrc(srcBytesNeeded)
        MemorySegment.copy(srcBgr, 0, srcSeg, ValueLayout.JAVA_BYTE, 0L, srcBytesNeeded.toInt())

        val estDst = (dstWidth.toLong() * dstHeight.toLong() / 3L).coerceIn(1024L * 1024L, 64L * 1024L * 1024L)
        val initialDst = if (maxBytes in 1L..64L * 1024L * 1024L) minOf(estDst, maxBytes) else estDst
        var dstSeg = scratch.ensureDst(initialDst)
        var dstCap = scratch.dstCapacity
        val outLenSeg = scratch.outLenSeg
        outLenSeg.set(ValueLayout.JAVA_INT, 0L, 0)

        var rc = h.jpegEncodeScaledBgr24.invokeExact(
            srcSeg,
            srcWidth,
            srcHeight,
            cropX,
            cropY,
            cropWidth,
            cropHeight,
            dstWidth,
            dstHeight,
            quality,
            dstSeg,
            dstCap,
            outLenSeg,
        ) as Int

        if (rc == ERR_OVERFLOW) {
            val actual = outLenSeg.get(ValueLayout.JAVA_INT, 0L)
            if (maxBytes > 0L && actual.toLong() > maxBytes) {
                return JpegEncodeOutcome.Overflow(actual)
            }
            if (actual > dstCap) {
                dstSeg = scratch.ensureDst(actual.toLong() + 65536L)
                dstCap = scratch.dstCapacity
                outLenSeg.set(ValueLayout.JAVA_INT, 0L, 0)
                rc = h.jpegEncodeScaledBgr24.invokeExact(
                    srcSeg,
                    srcWidth,
                    srcHeight,
                    cropX,
                    cropY,
                    cropWidth,
                    cropHeight,
                    dstWidth,
                    dstHeight,
                    quality,
                    dstSeg,
                    dstCap,
                    outLenSeg,
                ) as Int
            }
        }

        return when {
            rc > 0 -> {
                if (maxBytes > 0L && rc.toLong() > maxBytes) {
                    JpegEncodeOutcome.Overflow(rc)
                } else {
                    val out = ByteArray(rc)
                    MemorySegment.copy(dstSeg, ValueLayout.JAVA_BYTE, 0L, out, 0, rc)
                    JpegEncodeOutcome.Success(out)
                }
            }
            rc == ERR_OVERFLOW -> JpegEncodeOutcome.Overflow(outLenSeg.get(ValueLayout.JAVA_INT, 0L))
            else -> JpegEncodeOutcome.Error(rc)
        }
    }
}
