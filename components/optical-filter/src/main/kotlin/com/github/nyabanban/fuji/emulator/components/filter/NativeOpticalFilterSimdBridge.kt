package com.github.nyabanban.fuji.emulator.components.filter

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
 * (`tools/native/isp-simd/fuji_isp_simd.c`) for heavy CPU spatial optical filter kernels:
 * - Black Mist diffusion (highlight extract + 2-pass parallel-channel SIMD separable box blur + bloom)
 * - UV protective filter (micro-softness blur + point-symmetric chromatic ghosting + veiling glare + tint)
 * - Star Cross-Screen (4/6/8-point specular diffraction streaks)
 *
 * Gracefully falls back to pure Kotlin when the native library is absent or disabled via
 * `-Dfuji.isp.simd=false` or `FUJI_ISP_SIMD=0`.
 */
object NativeOpticalFilterSimdBridge {
    private val log = Logger.getLogger(NativeOpticalFilterSimdBridge::class.java.name)

    const val ABI_VERSION = 1

    private val libraryArena = Arena.ofShared()
    private val lookup: SymbolLookup?
    private val blackMistHandle: MethodHandle?
    private val uvFilterHandle: MethodHandle?
    private val starFilterHandle: MethodHandle?

    val loadedLibraryPath: String?
    val abiVersion: Int
    val simdLevel: Int
    val defaultThreads: Int
    val backendName: String

    @Volatile
    internal var forceDisabled: Boolean = false

    val isAvailable: Boolean
        get() = !forceDisabled && blackMistHandle != null && uvFilterHandle != null && starFilterHandle != null

    init {
        val disabledByEnv = System.getenv("FUJI_ISP_SIMD")?.equals("0", ignoreCase = true) == true ||
            System.getenv("FUJI_ISP_SIMD")?.equals("false", ignoreCase = true) == true
        val disabledByProp = System.getProperty("fuji.isp.simd")?.equals("false", ignoreCase = true) == true

        var resolvedPath: String? = null
        var loadedLookup: SymbolLookup? = null
        var resolvedAbi = 0
        var resolvedLevel = 0
        var resolvedThreads = 1
        var resolvedBackend = "kotlin-fallback"
        var hBlackMist: MethodHandle? = null
        var hUv: MethodHandle? = null
        var hStar: MethodHandle? = null

        if (!disabledByEnv && !disabledByProp) {
            val candidate = discoverLibraryPath()
            if (candidate != null) {
                try {
                    val lib = SymbolLookup.libraryLookup(candidate, libraryArena)
                    val linker = Linker.nativeLinker()
                    val critical = Linker.Option.critical(true)

                    val hAbi = linker.downcallHandle(
                        lib.find("fuji_isp_simd_abi_version").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT),
                    )
                    resolvedAbi = (hAbi.invokeExact() as Int)
                    if (resolvedAbi == ABI_VERSION) {
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

                        hBlackMist = linker.downcallHandle(
                            lib.find("fuji_optics_black_mist_f32").orElseThrow(),
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, // red
                                ValueLayout.ADDRESS, // green
                                ValueLayout.ADDRESS, // blue
                                ValueLayout.ADDRESS, // hi_r
                                ValueLayout.ADDRESS, // hi_g
                                ValueLayout.ADDRESS, // hi_b
                                ValueLayout.ADDRESS, // tmp_r
                                ValueLayout.ADDRESS, // tmp_g
                                ValueLayout.ADDRESS, // tmp_b
                                ValueLayout.JAVA_INT, // width
                                ValueLayout.JAVA_INT, // height
                                ValueLayout.JAVA_FLOAT, // threshold
                                ValueLayout.JAVA_FLOAT, // strength
                                ValueLayout.JAVA_INT, // radius
                                ValueLayout.JAVA_INT, // threads
                            ),
                            critical,
                        )
                        hUv = linker.downcallHandle(
                            lib.find("fuji_optics_uv_filter_f32").orElseThrow(),
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, // red
                                ValueLayout.ADDRESS, // green
                                ValueLayout.ADDRESS, // blue
                                ValueLayout.ADDRESS, // scratch_r
                                ValueLayout.ADDRESS, // scratch_g
                                ValueLayout.ADDRESS, // scratch_b
                                ValueLayout.ADDRESS, // aux_r
                                ValueLayout.ADDRESS, // aux_g
                                ValueLayout.ADDRESS, // aux_b
                                ValueLayout.JAVA_INT, // width
                                ValueLayout.JAVA_INT, // height
                                ValueLayout.JAVA_FLOAT, // softness
                                ValueLayout.JAVA_FLOAT, // ghost_intensity
                                ValueLayout.JAVA_FLOAT, // veiling_glare
                                ValueLayout.JAVA_FLOAT, // tint_r
                                ValueLayout.JAVA_FLOAT, // tint_g
                                ValueLayout.JAVA_FLOAT, // tint_b
                                ValueLayout.JAVA_INT, // has_tint
                                ValueLayout.JAVA_INT, // threads
                            ),
                            critical,
                        )
                        hStar = linker.downcallHandle(
                            lib.find("fuji_optics_star_filter_f32").orElseThrow(),
                            FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, // red
                                ValueLayout.ADDRESS, // green
                                ValueLayout.ADDRESS, // blue
                                ValueLayout.ADDRESS, // acc_r
                                ValueLayout.ADDRESS, // acc_g
                                ValueLayout.ADDRESS, // acc_b
                                ValueLayout.JAVA_INT, // width
                                ValueLayout.JAVA_INT, // height
                                ValueLayout.JAVA_INT, // points
                                ValueLayout.JAVA_FLOAT, // strength
                                ValueLayout.JAVA_FLOAT, // threshold
                                ValueLayout.JAVA_FLOAT, // length_frac
                                ValueLayout.JAVA_DOUBLE, // angle_deg
                                ValueLayout.JAVA_INT, // threads
                            ),
                            critical,
                        )

                        resolvedLevel = (hLevel.invokeExact() as Int)
                        resolvedThreads = (hThreads.invokeExact() as Int)
                        val namePtr = hBackend.invokeExact() as MemorySegment
                        resolvedBackend = if (namePtr != MemorySegment.NULL) {
                            namePtr.reinterpret(64L).getString(0L)
                        } else {
                            "native-isp-simd"
                        }
                        resolvedPath = candidate.toAbsolutePath().toString()
                        loadedLookup = lib
                    } else {
                        log.warning("[FALLBACK][optics-simd] ABI version mismatch: expected=$ABI_VERSION actual=$resolvedAbi path=$candidate")
                    }
                } catch (t: Throwable) {
                    log.warning("[FALLBACK][optics-simd] Failed to load native optical filter SIMD library ($candidate): ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        loadedLibraryPath = resolvedPath
        lookup = loadedLookup
        abiVersion = resolvedAbi
        simdLevel = resolvedLevel
        defaultThreads = resolvedThreads
        backendName = resolvedBackend
        blackMistHandle = hBlackMist
        uvFilterHandle = hUv
        starFilterHandle = hStar
    }

    private fun discoverLibraryPath(): Path? {
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
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            if (dir != null) {
                candidates.add(File(dir, "runtime/native/$libName"))
                candidates.add(File(dir, ".tools/isp-simd/$buildSubdir/$libName"))
                candidates.add(File(dir, ".tools/isp-simd/$libName"))
                dir = dir.parentFile
            }
        }
        candidates.add(File("/opt/fuji-cache/isp-simd/$buildSubdir/$libName"))
        candidates.add(File("/opt/fuji-cache/isp-simd/$libName"))

        return candidates.firstOrNull { it.isFile }?.toPath()
    }

    fun applyBlackMist(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        hiR: FloatArray,
        hiG: FloatArray,
        hiB: FloatArray,
        tmpR: FloatArray,
        tmpG: FloatArray,
        tmpB: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        strength: Float,
        radius: Int,
        threads: Int = 0,
    ): Boolean {
        val h = blackMistHandle
        if (forceDisabled || h == null) return false
        return try {
            val rc = h.invokeExact(
                MemorySegment.ofArray(red),
                MemorySegment.ofArray(green),
                MemorySegment.ofArray(blue),
                MemorySegment.ofArray(hiR),
                MemorySegment.ofArray(hiG),
                MemorySegment.ofArray(hiB),
                MemorySegment.ofArray(tmpR),
                MemorySegment.ofArray(tmpG),
                MemorySegment.ofArray(tmpB),
                width,
                height,
                threshold,
                strength,
                radius,
                threads,
            ) as Int
            rc == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun applyUvFilter(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        scratchR: FloatArray,
        scratchG: FloatArray,
        scratchB: FloatArray,
        auxR: FloatArray,
        auxG: FloatArray,
        auxB: FloatArray,
        width: Int,
        height: Int,
        softness: Float,
        ghostIntensity: Float,
        veilingGlare: Float,
        tintR: Float,
        tintG: Float,
        tintB: Float,
        hasTint: Boolean,
        threads: Int = 0,
    ): Boolean {
        val h = uvFilterHandle
        if (forceDisabled || h == null) return false
        return try {
            val rc = h.invokeExact(
                MemorySegment.ofArray(red),
                MemorySegment.ofArray(green),
                MemorySegment.ofArray(blue),
                MemorySegment.ofArray(scratchR),
                MemorySegment.ofArray(scratchG),
                MemorySegment.ofArray(scratchB),
                MemorySegment.ofArray(auxR),
                MemorySegment.ofArray(auxG),
                MemorySegment.ofArray(auxB),
                width,
                height,
                softness,
                ghostIntensity,
                veilingGlare,
                tintR,
                tintG,
                tintB,
                if (hasTint) 1 else 0,
                threads,
            ) as Int
            rc == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun applyStarFilter(
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
        accR: FloatArray,
        accG: FloatArray,
        accB: FloatArray,
        width: Int,
        height: Int,
        points: Int,
        strength: Float,
        threshold: Float,
        lengthFrac: Float,
        angleDeg: Double,
        threads: Int = 0,
    ): Boolean {
        val h = starFilterHandle
        if (forceDisabled || h == null) return false
        return try {
            val rc = h.invokeExact(
                MemorySegment.ofArray(red),
                MemorySegment.ofArray(green),
                MemorySegment.ofArray(blue),
                MemorySegment.ofArray(accR),
                MemorySegment.ofArray(accG),
                MemorySegment.ofArray(accB),
                width,
                height,
                points,
                strength,
                threshold,
                lengthFrac,
                angleDeg,
                threads,
            ) as Int
            rc >= 0
        } catch (_: Throwable) {
            false
        }
    }
}
