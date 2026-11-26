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
 * Compact GPU-reduced ISP statistics snapshot read back from the single-dispatch
 * Vulkan 1.1 compute shader `isp_stats_reduce.comp` (`9,280 bytes` = `2,320 x u32`).
 */
data class GpuIspStatsSnapshot(
    val aeSumR: IntArray,
    val aeSumG1: IntArray,
    val aeSumB: IntArray,
    val aeSumG2: IntArray,
    val aeCount: IntArray,
    val histY: IntArray,
    val histR: IntArray,
    val histG: IntArray,
    val histB: IntArray,
    val awbGlobalSumR: Long,
    val awbGlobalSumG: Long,
    val awbGlobalSumB: Long,
    val awbGlobalCount: Long,
    val awbValidSumR: Long,
    val awbValidSumG: Long,
    val awbValidSumB: Long,
    val awbValidCount: Long,
    val cdafHorizTenengrad: Long,
    val cdafHorizLaplacian: Long,
    val cdafVertTenengrad: Long,
    val cdafVertLaplacian: Long,
    val cdafRoiMaxG14: Int,
    val cdafRoiSumG10: Long,
    val cdafRoiCount: Long,
    val totalHistPixels: Long,
    val gridCols: Int,
    val gridRows: Int,
    val stepQuad: Int,
    val gpuMs: Double,
    val usedGpu: Boolean,
)

/**
 * Panama FFM bridge to the Vulkan 1.1 GPU ISP Statistics Parallel Reduction shader
 * (`fuji_vk_stats_*` exported by `libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`).
 *
 * Computes 16x16 AE/AWB grid sums, 4x256-bin Y/R/G/B histograms, global + highlight-excluded
 * AWB sums, and AF ROI CDAF horizontal/vertical Tenengrad + Laplacian energy in a single
 * 16x16 workgroup compute dispatch with shared-memory local atomics, transferring only
 * `9,280 bytes` from GPU to CPU.
 *
 * Production LiveView uses hardware-GPU-first selection (`SELECT_DEFAULT`, never picks
 * software Lavapipe). Software Vulkan contexts can be created explicitly for verification
 * tests via [createContext]. Gracefully disables when the library or Vulkan ICD is absent
 * or opted out via `-Dfuji.vk.stats=false` / `FUJI_VK_STATS=0`.
 */
object VulkanIspStatsBridge {
    private val log = Logger.getLogger(VulkanIspStatsBridge::class.java.name)

    const val ABI_VERSION = 1

    const val FMT_BAYER_RGGB_16LE = 0
    const val FMT_BAYER_RGGB_8 = 1
    const val FMT_PACKED_RGBA8 = 2

    const val FLAG_CDAF = 1 shl 0
    const val FLAG_HIST = 1 shl 1
    const val FLAG_AE_GRID = 1 shl 2
    const val FLAG_ALL = FLAG_CDAF or FLAG_HIST or FLAG_AE_GRID

    const val SELECT_PREFER_DISCRETE = 1 shl 0
    const val SELECT_ALLOW_INTEGRATED = 1 shl 1
    const val SELECT_ALLOW_SOFTWARE = 1 shl 2
    const val SELECT_DEFAULT = SELECT_PREFER_DISCRETE or SELECT_ALLOW_INTEGRATED

    private const val PARAMS_BYTES = 96L
    private const val OUTPUT_WORDS = 2320
    private const val OUTPUT_BYTES = OUTPUT_WORDS * 4L

    private val libraryArena = Arena.ofShared()
    private val loadLock = Any()
    private val hwDispatchLock = Any()

    @Volatile private var hAbiVersion: MethodHandle? = null
    @Volatile private var hCreate: MethodHandle? = null
    @Volatile private var hDestroy: MethodHandle? = null
    @Volatile private var hDeviceName: MethodHandle? = null
    @Volatile private var hReduce: MethodHandle? = null
    @Volatile private var hReduceCpuRef: MethodHandle? = null

    @Volatile private var hwCtxPtr: MemorySegment? = null
    @Volatile private var hwInitAttempted: Boolean = false
    private var hwParamsSeg: MemorySegment? = null
    private var hwOutputSeg: MemorySegment? = null
    private var hwGpuMsSeg: MemorySegment? = null
    private var hwInputArena: Arena? = null
    private var hwInputSeg: MemorySegment? = null

    @Volatile var loadedLibraryPath: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var hardwareDeviceName: String? = null
        private set
    @Volatile internal var forceDisabled: Boolean = false

    val isNativeLibraryAvailable: Boolean
        get() = !forceDisabled && hReduceCpuRef != null

    val isHardwareGpuAvailable: Boolean
        get() {
            if (forceDisabled) return false
            if (!hwInitAttempted) ensureHardwareInitialized()
            return hwCtxPtr != null
        }

    init {
        ensureLoaded(null)
    }

    /** Explicit handle to a native `FujiVkStatsContext*` for test or dedicated-thread usage. */
    class StatsContext internal constructor(
        internal var ptr: MemorySegment,
        val deviceName: String,
    ) : AutoCloseable {
        val isOpen: Boolean
            get() = ptr != MemorySegment.NULL

        override fun close() {
            val p = ptr
            if (p != MemorySegment.NULL) {
                ptr = MemorySegment.NULL
                try {
                    hDestroy?.invokeExact(p)
                } catch (_: Throwable) {
                    // Best-effort cleanup
                }
            }
        }
    }

    fun ensureLoaded(hintDir: Path? = null): Boolean {
        if (hReduceCpuRef != null) return !forceDisabled
        val disabledByEnv = System.getenv("FUJI_VK_STATS")?.equals("0", ignoreCase = true) == true ||
            System.getenv("FUJI_VK_STATS")?.equals("false", ignoreCase = true) == true
        val disabledByProp = System.getProperty("fuji.vk.stats")?.equals("false", ignoreCase = true) == true
        if (disabledByEnv || disabledByProp) {
            lastError = "Disabled via fuji.vk.stats / FUJI_VK_STATS"
            return false
        }
        synchronized(loadLock) {
            if (hReduceCpuRef != null) return !forceDisabled
            val candidate = discoverLibraryPath(hintDir)
            if (candidate == null) {
                lastError = "libfuji_vulkan_optics not found in search paths"
                return false
            }
            try {
                val lib = SymbolLookup.libraryLookup(candidate, libraryArena)
                val linker = Linker.nativeLinker()

                val symAbi = lib.find("fuji_vk_stats_abi_version")
                if (symAbi.isEmpty) {
                    lastError = "fuji_vk_stats_abi_version symbol not exported by $candidate"
                    return false
                }
                val fAbi = linker.downcallHandle(symAbi.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT))
                val abi = fAbi.invokeExact() as Int
                if (abi != ABI_VERSION) {
                    lastError = "Stats ABI mismatch: expected=$ABI_VERSION actual=$abi path=$candidate"
                    log.warning("[FALLBACK][vulkan-stats] $lastError")
                    return false
                }

                val fCreate = linker.downcallHandle(
                    lib.find("fuji_vk_stats_create").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                    ),
                )
                val fDestroy = linker.downcallHandle(
                    lib.find("fuji_vk_stats_destroy").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                )
                val fDevName = linker.downcallHandle(
                    lib.find("fuji_vk_stats_device_name").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
                val fReduce = linker.downcallHandle(
                    lib.find("fuji_vk_stats_reduce").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )
                val fReduceCpu = linker.downcallHandle(
                    lib.find("fuji_vk_stats_reduce_cpu_ref").orElseThrow(),
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                    ),
                )

                hAbiVersion = fAbi
                hCreate = fCreate
                hDestroy = fDestroy
                hDeviceName = fDevName
                hReduce = fReduce
                hReduceCpuRef = fReduceCpu
                loadedLibraryPath = candidate.toAbsolutePath().toString()
                lastError = null
                return true
            } catch (t: Throwable) {
                lastError = "Failed to bind libfuji_vulkan_optics stats symbols ($candidate): ${t.message}"
                log.fine("[FALLBACK][vulkan-stats] $lastError")
                return false
            }
        }
    }

    private fun ensureHardwareInitialized() {
        if (hwInitAttempted) return
        synchronized(loadLock) {
            if (hwInitAttempted) return
            hwInitAttempted = true
            if (!ensureLoaded(null)) return
            val ctx = createContext(allowSoftware = false)
            if (ctx != null) {
                hwCtxPtr = ctx.ptr
                hardwareDeviceName = ctx.deviceName
                hwParamsSeg = libraryArena.allocate(PARAMS_BYTES, 8L)
                hwOutputSeg = libraryArena.allocate(OUTPUT_BYTES, 8L)
                hwGpuMsSeg = libraryArena.allocate(ValueLayout.JAVA_DOUBLE)
                log.info("[VULKAN-STATS] Hardware GPU ISP stats reduction active on ${ctx.deviceName}")
            }
        }
    }

    /**
     * Creates a dedicated Vulkan ISP stats context.
     *
     * @param allowSoftware When `false` (production default), only discrete/integrated hardware GPUs
     *                      are accepted. When `true` (tests), software ICDs such as Mesa Lavapipe are allowed.
     */
    fun createContext(allowSoftware: Boolean = false): StatsContext? {
        if (!ensureLoaded(null)) return null
        val fCreate = hCreate ?: return null
        val fDevName = hDeviceName ?: return null
        val flags = if (allowSoftware) (SELECT_DEFAULT or SELECT_ALLOW_SOFTWARE) else SELECT_DEFAULT
        return Arena.ofConfined().use { arena ->
            val outCtx = arena.allocate(ValueLayout.ADDRESS)
            val errBuf = arena.allocate(256L)
            val rc = fCreate.invokeExact(flags, outCtx, errBuf, 256L) as Int
            if (rc != 0) {
                lastError = errBuf.getString(0L)
                return@use null
            }
            val ptr = outCtx.get(ValueLayout.ADDRESS, 0L)
            if (ptr == MemorySegment.NULL) return@use null
            val namePtr = fDevName.invokeExact(ptr) as MemorySegment
            val devName = if (namePtr != MemorySegment.NULL) namePtr.reinterpret(256L).getString(0L) else "unknown"
            StatsContext(ptr, devName)
        }
    }

    /**
     * Executes ISP stats reduction on the singleton hardware Vulkan GPU context (never Lavapipe).
     * Returns `null` if no hardware Vulkan GPU is available or dispatch fails.
     */
    fun reduceHardware(
        input: ByteArray,
        width: Int,
        height: Int,
        inputFormat: Int,
        viewX: Int = 0,
        viewY: Int = 0,
        viewW: Int = width,
        viewH: Int = height,
        gridCols: Int = 16,
        gridRows: Int = 16,
        stepQuad: Int = if (viewW >= 512 && viewH >= 256) 2 else 1,
        awbHighlightThresh10: Int = 960,
        afRoiX0: Int = 0,
        afRoiY0: Int = 0,
        afRoiX1: Int = width,
        afRoiY1: Int = height,
        flags: Int = FLAG_ALL,
    ): GpuIspStatsSnapshot? {
        if (!isHardwareGpuAvailable) return null
        val ctxPtr = hwCtxPtr ?: return null
        val fReduce = hReduce ?: return null
        val paramsSeg = hwParamsSeg ?: return null
        val outSeg = hwOutputSeg ?: return null
        val msSeg = hwGpuMsSeg ?: return null
        synchronized(hwDispatchLock) {
            val needBytes = input.size.toLong()
            var inSeg = hwInputSeg
            if (inSeg == null || inSeg.byteSize() < needBytes) {
                hwInputArena?.close()
                val newArena = Arena.ofShared()
                inSeg = newArena.allocate(needBytes.coerceAtLeast(16L), 16L)
                hwInputArena = newArena
                hwInputSeg = inSeg
            }
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_BYTE, 0L, input.size)
            writeParams(
                paramsSeg, width, height, inputFormat, flags,
                viewX, viewY, viewW, viewH,
                gridCols, gridRows, stepQuad, awbHighlightThresh10,
                afRoiX0, afRoiY0, afRoiX1, afRoiY1,
            )
            val rc = fReduce.invokeExact(ctxPtr, inSeg, needBytes, paramsSeg, outSeg, msSeg) as Int
            if (rc != 0) return null
            val gpuMs = msSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
            return decodeOutput(outSeg, gridCols, gridRows, stepQuad, gpuMs, usedGpu = true)
        }
    }

    /** Executes ISP stats reduction on an explicit [StatsContext] (hardware or Lavapipe GPU). */
    fun reduceWithContext(
        ctx: StatsContext,
        input: ByteArray,
        width: Int,
        height: Int,
        inputFormat: Int,
        viewX: Int = 0,
        viewY: Int = 0,
        viewW: Int = width,
        viewH: Int = height,
        gridCols: Int = 16,
        gridRows: Int = 16,
        stepQuad: Int = if (viewW >= 512 && viewH >= 256) 2 else 1,
        awbHighlightThresh10: Int = 960,
        afRoiX0: Int = 0,
        afRoiY0: Int = 0,
        afRoiX1: Int = width,
        afRoiY1: Int = height,
        flags: Int = FLAG_ALL,
    ): GpuIspStatsSnapshot? {
        if (!ctx.isOpen) return null
        val fReduce = hReduce ?: return null
        return Arena.ofConfined().use { arena ->
            val inSeg = arena.allocate(input.size.toLong().coerceAtLeast(16L), 16L)
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_BYTE, 0L, input.size)
            val paramsSeg = arena.allocate(PARAMS_BYTES, 8L)
            val outSeg = arena.allocate(OUTPUT_BYTES, 8L)
            val msSeg = arena.allocate(ValueLayout.JAVA_DOUBLE)
            writeParams(
                paramsSeg, width, height, inputFormat, flags,
                viewX, viewY, viewW, viewH,
                gridCols, gridRows, stepQuad, awbHighlightThresh10,
                afRoiX0, afRoiY0, afRoiX1, afRoiY1,
            )
            val rc = fReduce.invokeExact(ctx.ptr, inSeg, input.size.toLong(), paramsSeg, outSeg, msSeg) as Int
            if (rc != 0) return@use null
            val gpuMs = msSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
            decodeOutput(outSeg, gridCols, gridRows, stepQuad, gpuMs, usedGpu = true)
        }
    }

    /** Executes the bit-exact native C++ CPU reference reducer (no Vulkan ICD required). */
    fun reduceCpuRef(
        input: ByteArray,
        width: Int,
        height: Int,
        inputFormat: Int,
        viewX: Int = 0,
        viewY: Int = 0,
        viewW: Int = width,
        viewH: Int = height,
        gridCols: Int = 16,
        gridRows: Int = 16,
        stepQuad: Int = if (viewW >= 512 && viewH >= 256) 2 else 1,
        awbHighlightThresh10: Int = 960,
        afRoiX0: Int = 0,
        afRoiY0: Int = 0,
        afRoiX1: Int = width,
        afRoiY1: Int = height,
        flags: Int = FLAG_ALL,
    ): GpuIspStatsSnapshot? {
        if (!ensureLoaded(null)) return null
        val fCpu = hReduceCpuRef ?: return null
        return Arena.ofConfined().use { arena ->
            val inSeg = arena.allocate(input.size.toLong().coerceAtLeast(16L), 16L)
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_BYTE, 0L, input.size)
            val paramsSeg = arena.allocate(PARAMS_BYTES, 8L)
            val outSeg = arena.allocate(OUTPUT_BYTES, 8L)
            writeParams(
                paramsSeg, width, height, inputFormat, flags,
                viewX, viewY, viewW, viewH,
                gridCols, gridRows, stepQuad, awbHighlightThresh10,
                afRoiX0, afRoiY0, afRoiX1, afRoiY1,
            )
            val rc = fCpu.invokeExact(inSeg, input.size.toLong(), paramsSeg, outSeg) as Int
            if (rc != 0) return@use null
            decodeOutput(outSeg, gridCols, gridRows, stepQuad, 0.0, usedGpu = false)
        }
    }

    private fun writeParams(
        seg: MemorySegment,
        width: Int,
        height: Int,
        inputFormat: Int,
        flags: Int,
        viewX: Int,
        viewY: Int,
        viewW: Int,
        viewH: Int,
        gridCols: Int,
        gridRows: Int,
        stepQuad: Int,
        awbHighlightThresh10: Int,
        afRoiX0: Int,
        afRoiY0: Int,
        afRoiX1: Int,
        afRoiY1: Int,
    ) {
        seg.fill(0.toByte())
        seg.setAtIndex(ValueLayout.JAVA_INT, 0, width)
        seg.setAtIndex(ValueLayout.JAVA_INT, 1, height)
        seg.setAtIndex(ValueLayout.JAVA_INT, 2, inputFormat)
        seg.setAtIndex(ValueLayout.JAVA_INT, 3, flags)
        seg.setAtIndex(ValueLayout.JAVA_INT, 4, viewX)
        seg.setAtIndex(ValueLayout.JAVA_INT, 5, viewY)
        seg.setAtIndex(ValueLayout.JAVA_INT, 6, viewW)
        seg.setAtIndex(ValueLayout.JAVA_INT, 7, viewH)
        seg.setAtIndex(ValueLayout.JAVA_INT, 8, gridCols.coerceIn(1, 16))
        seg.setAtIndex(ValueLayout.JAVA_INT, 9, gridRows.coerceIn(1, 16))
        seg.setAtIndex(ValueLayout.JAVA_INT, 10, stepQuad.coerceAtLeast(1))
        seg.setAtIndex(ValueLayout.JAVA_INT, 11, awbHighlightThresh10.coerceIn(1, 1024))
        seg.setAtIndex(ValueLayout.JAVA_INT, 12, afRoiX0)
        seg.setAtIndex(ValueLayout.JAVA_INT, 13, afRoiY0)
        seg.setAtIndex(ValueLayout.JAVA_INT, 14, afRoiX1)
        seg.setAtIndex(ValueLayout.JAVA_INT, 15, afRoiY1)
    }

    private fun decodeOutput(
        seg: MemorySegment,
        gridCols: Int,
        gridRows: Int,
        stepQuad: Int,
        gpuMs: Double,
        usedGpu: Boolean,
    ): GpuIspStatsSnapshot {
        val raw = IntArray(OUTPUT_WORDS)
        MemorySegment.copy(seg, ValueLayout.JAVA_INT, 0L, raw, 0, OUTPUT_WORDS)
        return decodeOutputWords(raw, gridCols, gridRows, stepQuad, gpuMs, usedGpu)
    }

    internal fun decodeOutputWords(
        raw: IntArray,
        gridCols: Int,
        gridRows: Int,
        stepQuad: Int,
        gpuMs: Double,
        usedGpu: Boolean,
    ): GpuIspStatsSnapshot {
        fun u32(idx: Int): Long = raw[idx].toLong() and 0xFFFF_FFFFL
        return GpuIspStatsSnapshot(
            aeSumR = raw.copyOfRange(0, 256),
            aeSumG1 = raw.copyOfRange(256, 512),
            aeSumB = raw.copyOfRange(512, 768),
            aeSumG2 = raw.copyOfRange(768, 1024),
            aeCount = raw.copyOfRange(1024, 1280),
            histY = raw.copyOfRange(1280, 1536),
            histR = raw.copyOfRange(1536, 1792),
            histG = raw.copyOfRange(1792, 2048),
            histB = raw.copyOfRange(2048, 2304),
            awbGlobalSumR = u32(2304),
            awbGlobalSumG = u32(2305),
            awbGlobalSumB = u32(2306),
            awbGlobalCount = u32(2307),
            awbValidSumR = u32(2308),
            awbValidSumG = u32(2309),
            awbValidSumB = u32(2310),
            awbValidCount = u32(2311),
            cdafHorizTenengrad = u32(2312),
            cdafHorizLaplacian = u32(2313),
            cdafVertTenengrad = u32(2314),
            cdafVertLaplacian = u32(2315),
            cdafRoiMaxG14 = raw[2316],
            cdafRoiSumG10 = u32(2317),
            cdafRoiCount = u32(2318),
            totalHistPixels = u32(2319),
            gridCols = gridCols.coerceIn(1, 16),
            gridRows = gridRows.coerceIn(1, 16),
            stepQuad = stepQuad.coerceAtLeast(1),
            gpuMs = gpuMs,
            usedGpu = usedGpu,
        )
    }

    private fun discoverLibraryPath(hintDir: Path?): Path? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isWin = osName.contains("win")
        val libName = if (isWin) "fuji_vulkan_optics.dll" else "libfuji_vulkan_optics.so"
        val buildSubdir = if (isWin) "build-win64" else "build-linux"

        val candidates = ArrayList<File>()
        System.getProperty("fuji.vulkan.optics.library")?.takeIf { it.isNotBlank() }?.let { candidates.add(File(it)) }
        System.getenv("FUJI_VULKAN_OPTICS_LIBRARY")?.takeIf { it.isNotBlank() }?.let { candidates.add(File(it)) }

        fun addWalk(start: File?, maxDepth: Int) {
            var cur = start?.absoluteFile
            var d = 0
            while (cur != null && d <= maxDepth) {
                candidates.add(File(cur, libName))
                candidates.add(File(cur, "native/$libName"))
                candidates.add(File(cur, "runtime/native/$libName"))
                candidates.add(File(cur, ".tools/vulkan-optics/$buildSubdir/$libName"))
                candidates.add(File(cur, "tools/native/vulkan-optics/$buildSubdir/$libName"))
                cur = cur.parentFile
                d++
            }
        }

        if (hintDir != null) addWalk(hintDir.toFile(), 4)
        runCatching {
            val loc = VulkanIspStatsBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (loc != null) {
                val f = File(loc)
                addWalk(if (f.isFile) f.parentFile else f, 5)
            }
        }
        addWalk(File(System.getProperty("user.dir") ?: "."), 5)
        candidates.add(File("/opt/fuji-cache/vulkan-optics/$buildSubdir/$libName"))
        candidates.add(File("/opt/fuji-cache/vulkan-optics/$libName"))

        return candidates.firstOrNull { it.isFile }?.toPath()
    }
}
