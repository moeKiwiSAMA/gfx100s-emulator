package com.github.nyabanban.fuji.emulator.components.isp

import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.logging.Logger

/**
 * Zero-copy Panama FFM bridge to `libfuji_isp_simd.so` / `fuji_isp_simd.dll`
 * (`tools/native/isp-simd/fuji_isp_simd.c`).
 *
 * Accelerates the CPU ISP live-view and still-capture pipeline with multi-threaded
 * runtime-dispatched AVX-512 / AVX2+FMA / SSE2 / Scalar row-strip kernels:
 * - Bayer RGGB 8/16-bit demosaic + Q10 WB gain + 3x3 CCM
 * - 1D 4096-entry RGB Tone LUT + optional 16x16x16 3D FilmSim LUT (trilinear)
 * - Direct UYVY422 / YYUV422 / NV16 / NV12 output packing
 * - Fused 5-tap HPF Focus Peaking + single-pass 256-bin Y/R/G/B histogram + AF contrast
 *
 * Gracefully falls back when the native library is absent or disabled via
 * `-Dfuji.isp.simd=false` or `FUJI_ISP_SIMD=0`.
 */
object NativeIspSimdBridge {
    private val log = Logger.getLogger(NativeIspSimdBridge::class.java.name)

    const val ABI_VERSION = 1

    const val FLAG_HAS_CCM = 1 shl 0
    const val FLAG_HAS_YUV_MAT = 1 shl 1
    const val FLAG_MONO_MODE = 1 shl 2
    const val FLAG_PEAKING = 1 shl 3
    const val FLAG_HAS_LUT3D = 1 shl 4
    const val FLAG_YYUV422 = 1 shl 5
    const val FLAG_AF_CONTRAST = 1 shl 6

    private val libraryArena = Arena.ofShared()
    private val loadLock = Any()
    @Volatile private var lookup: SymbolLookup? = null
    @Volatile private var demosaicMtHandle: MethodHandle? = null
    @Volatile private var simdLevelHandle: MethodHandle? = null
    @Volatile private var defaultThreadsHandle: MethodHandle? = null
    @Volatile private var backendNameHandle: MethodHandle? = null

    @Volatile var loadedLibraryPath: String? = null
        private set
    @Volatile var lastLoadError: String? = null
        private set
    @Volatile var abiVersion: Int = 0
        private set
    @Volatile var simdLevel: Int = 0
        private set
    @Volatile var defaultThreads: Int = 1
        private set
    @Volatile var backendName: String = "kotlin-fallback"
        private set

    @Volatile
    internal var forceDisabled: Boolean = false

    val isAvailable: Boolean
        get() = !forceDisabled && demosaicMtHandle != null

    init {
        ensureLoaded(null)
    }

    /**
     * Attempts to load `libfuji_isp_simd.so` / `fuji_isp_simd.dll` if not yet loaded,
     * optionally probing ancestors of [hintDir] (e.g. component or emulator home directory).
     */
    fun ensureLoaded(hintDir: Path? = null): Boolean {
        if (demosaicMtHandle != null) return !forceDisabled
        val disabledByEnv = System.getenv("FUJI_ISP_SIMD")?.equals("0", ignoreCase = true) == true ||
            System.getenv("FUJI_ISP_SIMD")?.equals("false", ignoreCase = true) == true
        val disabledByProp = System.getProperty("fuji.isp.simd")?.equals("false", ignoreCase = true) == true
        if (disabledByEnv || disabledByProp) {
            lastLoadError = "Disabled via fuji.isp.simd / FUJI_ISP_SIMD"
            return false
        }
        synchronized(loadLock) {
            if (demosaicMtHandle != null) return !forceDisabled
            val candidate = discoverLibraryPath(hintDir)
            if (candidate == null) {
                lastLoadError = "libfuji_isp_simd not found in search paths"
                return false
            }
            try {
                val lib = SymbolLookup.libraryLookup(candidate, libraryArena)
                val linker = Linker.nativeLinker()
                val critical = Linker.Option.critical(true)

                val hAbi = linker.downcallHandle(
                    lib.find("fuji_isp_simd_abi_version").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT),
                )
                val resolvedAbi = (hAbi.invokeExact() as Int)
                if (resolvedAbi != ABI_VERSION) {
                    lastLoadError = "ABI version mismatch: expected=$ABI_VERSION actual=$resolvedAbi path=$candidate"
                    log.warning("[FALLBACK][isp-simd] $lastLoadError")
                    return false
                }
                val hLevel = linker.downcallHandle(
                    lib.find("fuji_isp_simd_level").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT),
                )
                val hThreads = linker.downcallHandle(
                    lib.find("fuji_isp_simd_default_threads").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT),
                )
                val hBackend = linker.downcallHandle(
                    lib.find("fuji_isp_simd_backend_name").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS),
                )
                val hDemosaic = linker.downcallHandle(
                    lib.find("fuji_isp_demosaic_yuv_mt").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, // raw
                        ValueLayout.ADDRESS, // y_or_uyvy_dst
                        ValueLayout.ADDRESS, // uv_dst
                        ValueLayout.ADDRESS, // src_x_map
                        ValueLayout.ADDRESS, // ccm
                        ValueLayout.ADDRESS, // lut_r
                        ValueLayout.ADDRESS, // lut_g
                        ValueLayout.ADDRESS, // lut_b
                        ValueLayout.ADDRESS, // lut3d_16
                        ValueLayout.ADDRESS, // yuv_mat
                        ValueLayout.ADDRESS, // hist_yrgb
                        ValueLayout.ADDRESS, // out_af_contrast
                        ValueLayout.ADDRESS, // cfg
                    ),
                    critical,
                )
                val resolvedLevel = (hLevel.invokeExact() as Int)
                val resolvedThreads = (hThreads.invokeExact() as Int)
                val namePtr = hBackend.invokeExact() as MemorySegment
                val resolvedBackend = if (namePtr != MemorySegment.NULL) {
                    namePtr.reinterpret(64L).getString(0L)
                } else {
                    "native-isp-simd"
                }
                loadedLibraryPath = candidate.toAbsolutePath().toString()
                lastLoadError = null
                lookup = lib
                abiVersion = resolvedAbi
                simdLevel = resolvedLevel
                defaultThreads = resolvedThreads
                backendName = resolvedBackend
                simdLevelHandle = hLevel
                defaultThreadsHandle = hThreads
                backendNameHandle = hBackend
                demosaicMtHandle = hDemosaic
                return !forceDisabled
            } catch (t: Throwable) {
                lastLoadError = "${t.javaClass.simpleName}: ${t.message} ($candidate)"
                log.warning("[FALLBACK][isp-simd] Failed to load native ISP SIMD library ($candidate): ${t.javaClass.simpleName}: ${t.message}")
                return false
            }
        }
    }

    private fun discoverLibraryPath(hintDir: Path? = null): Path? {
        val explicit = System.getProperty("fuji.isp.simd.library")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FUJI_ISP_SIMD_LIBRARY")?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            val f = File(explicit)
            if (f.isFile) return f.toPath()
        }

        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val libName = if (isWindows) "fuji_isp_simd.dll" else "libfuji_isp_simd.so"
        val buildSubdir = if (isWindows) "build-win64" else "build-linux"

        val candidates = ArrayList<File>()
        fun addWalk(start: File?, depth: Int) {
            var d = start?.absoluteFile
            repeat(depth) {
                if (d != null) {
                    candidates.add(File(d, "runtime/native/$libName"))
                    candidates.add(File(d, "native/$libName"))
                    candidates.add(File(d, ".tools/isp-simd/$buildSubdir/$libName"))
                    candidates.add(File(d, ".tools/isp-simd/$libName"))
                    d = d.parentFile
                }
            }
        }
        if (hintDir != null) {
            addWalk(hintDir.toFile(), 6)
        }
        System.getProperty("fuji.home")?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            addWalk(File(home), 2)
        }
        runCatching {
            val loc = NativeIspSimdBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (loc != null) {
                val f = File(loc)
                addWalk(if (f.isFile) f.parentFile else f, 5)
            }
        }
        addWalk(File(System.getProperty("user.dir") ?: "."), 5)
        candidates.add(File("/opt/fuji-cache/isp-simd/$buildSubdir/$libName"))
        candidates.add(File("/opt/fuji-cache/isp-simd/$libName"))

        return candidates.firstOrNull { it.isFile }?.toPath()
    }

    /**
     * Executes the multi-threaded AVX-512 / AVX2+FMA / SSE2 / Scalar ISP demosaic pipeline
     * directly on JVM primitive arrays via zero-copy `Linker.Option.critical(true)`.
     *
     * @return `>= 0` (number of focus-peaking stamped pixels) on success, `< 0` if unavailable or invalid.
     */
    fun demosaicYuvMt(
        raw: ByteArray,
        yOrUyvyDst: ByteArray,
        uvDst: ByteArray?,
        srcXMap: IntArray,
        ccm: IntArray?,
        lutR: IntArray,
        lutG: IntArray,
        lutB: IntArray,
        lut3d16: FloatArray? = null,
        yuvMat: IntArray? = null,
        histYrgb: IntArray? = null,
        outAfContrast: LongArray? = null,
        cfg: IntArray,
    ): Int = demosaicYuvMtDirect(
        raw = raw,
        yOrUyvyDst = MemorySegment.ofArray(yOrUyvyDst),
        uvDst = if (uvDst != null) MemorySegment.ofArray(uvDst) else MemorySegment.NULL,
        srcXMap = srcXMap,
        ccm = ccm,
        lutR = lutR,
        lutG = lutG,
        lutB = lutB,
        lut3d16 = lut3d16,
        yuvMat = yuvMat,
        histYrgb = histYrgb,
        outAfContrast = outAfContrast,
        cfg = cfg,
    )

    /**
     * Direct off-heap destination overload writing demosaiced YUV straight into guest RAM
     * [MemorySegment] slices (e.g. [com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort.sliceDirect])
     * with zero intermediate `ByteArray` copy.
     */
    fun demosaicYuvMtDirect(
        raw: ByteArray,
        yOrUyvyDst: MemorySegment,
        uvDst: MemorySegment,
        srcXMap: IntArray,
        ccm: IntArray?,
        lutR: IntArray,
        lutG: IntArray,
        lutB: IntArray,
        lut3d16: FloatArray? = null,
        yuvMat: IntArray? = null,
        histYrgb: IntArray? = null,
        outAfContrast: LongArray? = null,
        cfg: IntArray,
    ): Int {
        val h = demosaicMtHandle
        if (forceDisabled || h == null || cfg.size < 20) return -1
        return try {
            h.invokeExact(
                MemorySegment.ofArray(raw),
                yOrUyvyDst,
                uvDst,
                MemorySegment.ofArray(srcXMap),
                if (ccm != null) MemorySegment.ofArray(ccm) else MemorySegment.NULL,
                MemorySegment.ofArray(lutR),
                MemorySegment.ofArray(lutG),
                MemorySegment.ofArray(lutB),
                if (lut3d16 != null) MemorySegment.ofArray(lut3d16) else MemorySegment.NULL,
                if (yuvMat != null) MemorySegment.ofArray(yuvMat) else MemorySegment.NULL,
                if (histYrgb != null) MemorySegment.ofArray(histYrgb) else MemorySegment.NULL,
                if (outAfContrast != null && outAfContrast.isNotEmpty()) MemorySegment.ofArray(outAfContrast) else MemorySegment.NULL,
                MemorySegment.ofArray(cfg),
            ) as Int
        } catch (_: Throwable) {
            -1
        }
    }
}
