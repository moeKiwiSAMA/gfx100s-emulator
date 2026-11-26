package com.github.nyabanban.fuji.emulator.components.vglib

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

/**
 * Zero-copy JDK 25 Panama FFM binding to the optional native SIMD rendering pipeline
 * (`libfuji_render.so` on Linux x86_64, `fuji_render.dll` on Windows x86_64).
 *
 * Uses `Linker.Option.critical(true)` so leaf C SIMD kernels operate directly on JVM
 * `ByteArray`/`IntArray` heap segments with zero intermediate copies and zero allocations.
 * Falls back transparently to pure-Kotlin implementations whenever the native library
 * is unavailable or explicitly disabled via `-Dfuji.render.native=false` / `FUJI_RENDER_NATIVE=0`.
 */
internal object NativeRenderBridge {
    private const val EXPECTED_ABI_VERSION = 1

    private class Handles(
        val libraryPath: Path,
        @Suppress("unused") val arena: Arena,
        val simdLevel: Int,
        val backendName: String,
        val compositePic1Osd0: MethodHandle,
        val decodeRgba: MethodHandle,
        val fillU32: MethodHandle,
        val mortonTile8x8: MethodHandle,
        val blitMortonQuad: MethodHandle,
        val blitFilteredMorton: MethodHandle?,
        val fillRectRgba: MethodHandle,
        val fillPolygonRgba: MethodHandle,
        val fillLinearGradientRectRgba: MethodHandle?,
        val compositePlanesMatrix: MethodHandle?,
        val compositeLcdcToArgbPre: MethodHandle?,
    )

    @Volatile
    var lastLoadError: String? = null
        private set

    private val handles: Handles? by lazy { loadIfEnabled() }

    val isAvailable: Boolean
        get() = handles != null

    val backendName: String
        get() = handles?.backendName ?: "kotlin-scalar-fallback"

    val simdLevel: Int
        get() = handles?.simdLevel ?: 0

    val libraryPath: Path?
        get() = handles?.libraryPath

    val hasDirectArgbPreComposite: Boolean
        get() = handles?.compositeLcdcToArgbPre != null

    private fun isDisabledByConfig(): Boolean {
        val prop = System.getProperty("fuji.render.native")?.trim()?.lowercase()
        if (prop == "false" || prop == "0" || prop == "off") return true
        val env = System.getenv("FUJI_RENDER_NATIVE")?.trim()?.lowercase()
        return env == "false" || env == "0" || env == "off"
    }

    internal fun resolveLibraryPath(
        runtimeHome: Path? = null,
        isWindowsOverride: Boolean? = null,
        envProvider: (String) -> String? = System::getenv,
        propProvider: (String) -> String? = System::getProperty,
    ): Path? {
        val isWindows = isWindowsOverride ?: (propProvider("os.name") ?: "").lowercase().contains("win")
        val libName = if (isWindows) "fuji_render.dll" else "libfuji_render.so"
        val buildSubdir = if (isWindows) "build-win64" else "build-linux"
        val cacheSubdir = if (isWindows) "render-bridge-win64" else "render-bridge-linux"

        val candidates = LinkedHashSet<Path>(24)
        fun addIfPresent(raw: String?) {
            raw?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(Paths.get(it)) }
        }
        fun addSiblingOf(raw: String?) {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return
            runCatching {
                val p = Paths.get(trimmed).toAbsolutePath().normalize()
                val dir = if (Files.isDirectory(p)) p else p.parent
                if (dir != null) {
                    candidates.add(dir.resolve(libName))
                }
            }
        }
        fun addHomeCandidates(base: Path) {
            val normalized = base.toAbsolutePath().normalize()
            candidates.add(normalized.resolve("runtime/native/$libName"))
            candidates.add(normalized.resolve("native/$libName"))
            candidates.add(normalized.resolve(".tools/render-bridge/$buildSubdir/$libName"))
            candidates.add(normalized.resolve(".tools/render-bridge/build/$libName"))
            candidates.add(normalized.resolve("build/render-bridge/$libName"))
        }

        // 1. Explicit render-bridge overrides
        addIfPresent(propProvider("fuji.render.library"))
        addIfPresent(envProvider("FUJI_RENDER_LIBRARY"))
        if (isWindows) {
            addIfPresent(envProvider("FUJI_WIN64_RENDER_DLL"))
        }

        // 2. Explicit runtime home or fuji.home property (jpackage / launcher / release bundles)
        if (runtimeHome != null) {
            addHomeCandidates(runtimeHome)
        }
        propProvider("fuji.home")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            addHomeCandidates(Paths.get(it))
        }

        // 3. Sibling discovery next to libfuji_dynarmic / libfuji_audio in runtime/native/
        addSiblingOf(propProvider("fuji.dynarmic.library"))
        addSiblingOf(envProvider("FUJI_DYNARMIC_LIBRARY"))
        if (isWindows) addSiblingOf(envProvider("FUJI_WIN64_DYNARMIC_DLL"))
        addSiblingOf(propProvider("fuji.audio.library"))
        addSiblingOf(envProvider("FUJI_AUDIO_LIBRARY"))
        if (isWindows) addSiblingOf(envProvider("FUJI_WIN64_AUDIO_DLL"))

        // 4. Relative to jar location (staged release bundles: lib/*.jar, lib/app/*.jar, runtime/compoments/*.jar)
        runCatching {
            val loc = NativeRenderBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (loc != null) {
                var dir = Paths.get(loc).toAbsolutePath().normalize()
                if (Files.isRegularFile(dir)) dir = dir.parent
                repeat(5) {
                    if (dir != null) {
                        candidates.add(dir.resolve("runtime/native/$libName"))
                        candidates.add(dir.resolve("native/$libName"))
                        candidates.add(dir.resolve(".tools/render-bridge/$buildSubdir/$libName"))
                        dir = dir.parent
                    }
                }
            }
        }

        // 5. Walk up from current working directory (covers Gradle subproject test JVMs & dev worktrees)
        var cwd: Path? = Paths.get("").toAbsolutePath().normalize()
        repeat(5) {
            val current = cwd
            if (current != null) {
                addHomeCandidates(current)
                cwd = current.parent
            }
        }

        // 6. Pre-baked CI/container cache paths
        candidates.add(Paths.get("/opt/fuji-cache/render-bridge/$buildSubdir/$libName"))
        candidates.add(Paths.get("/opt/fuji-cache/$cacheSubdir/$libName"))
        candidates.add(Paths.get("/opt/fuji-cache/render-bridge/$libName"))

        return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
    }

    private fun loadIfEnabled(): Handles? {
        if (isDisabledByConfig()) return null
        val path = resolveLibraryPath()
        if (path == null) {
            lastLoadError = "Library not found in search paths (cwd=${Paths.get("").toAbsolutePath().normalize()})"
            return null
        }
        return try {
            val arena = Arena.ofShared()
            val lookup = SymbolLookup.libraryLookup(path.toAbsolutePath().normalize(), arena)
            val linker = Linker.nativeLinker()
            val critical = Linker.Option.critical(true)

            fun sym(name: String): MemorySegment =
                lookup.find(name).orElseThrow { UnsatisfiedLinkError("Missing symbol $name in $path") }

            val fnAbi = linker.downcallHandle(sym("fuji_render_abi_version"), FunctionDescriptor.of(ValueLayout.JAVA_INT))
            val abi = fnAbi.invokeExact() as Int
            if (abi != EXPECTED_ABI_VERSION) {
                lastLoadError = "ABI mismatch: expected $EXPECTED_ABI_VERSION, got $abi at $path"
                arena.close()
                return null
            }

            val fnSimd = linker.downcallHandle(sym("fuji_render_simd_level"), FunctionDescriptor.of(ValueLayout.JAVA_INT))
            val simd = fnSimd.invokeExact() as Int

            val fnName = linker.downcallHandle(sym("fuji_render_backend_name"), FunctionDescriptor.of(ValueLayout.ADDRESS))
            val namePtr = fnName.invokeExact() as MemorySegment
            val name = namePtr.reinterpret(128).getString(0)

            val compositeHandle = linker.downcallHandle(
                sym("fuji_render_composite_pic1_osd0"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // osd_bytes
                    ValueLayout.JAVA_INT, // osd_base_offset
                    ValueLayout.JAVA_INT, // osd_stride_bytes
                    ValueLayout.ADDRESS,  // pic1_bytes
                    ValueLayout.JAVA_INT, // pic1_x
                    ValueLayout.JAVA_INT, // pic1_y
                    ValueLayout.JAVA_INT, // pic1_width
                    ValueLayout.JAVA_INT, // pic1_height
                    ValueLayout.JAVA_INT, // pic1_stride_bytes
                    ValueLayout.JAVA_INT, // pic1_alpha
                    ValueLayout.JAVA_INT, // opaque_osd_backdrop
                    ValueLayout.JAVA_INT, // canvas_width
                    ValueLayout.JAVA_INT, // canvas_height
                    ValueLayout.ADDRESS,  // source_rows
                    ValueLayout.ADDRESS,  // source_cols
                    ValueLayout.JAVA_INT, // out_width
                    ValueLayout.JAVA_INT, // out_height
                    ValueLayout.ADDRESS,  // output_argb
                ),
                critical,
            )

            val decodeRgbaHandle = linker.downcallHandle(
                sym("fuji_render_decode_rgba"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // src_bytes
                    ValueLayout.JAVA_INT, // src_base_offset
                    ValueLayout.JAVA_INT, // src_stride_bytes
                    ValueLayout.ADDRESS,  // source_rows
                    ValueLayout.ADDRESS,  // source_cols
                    ValueLayout.JAVA_INT, // src_width
                    ValueLayout.JAVA_INT, // src_height
                    ValueLayout.JAVA_INT, // out_width
                    ValueLayout.JAVA_INT, // out_height
                    ValueLayout.ADDRESS,  // output_argb
                ),
                critical,
            )

            val fillU32Handle = linker.downcallHandle(
                sym("fuji_render_fill_u32"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // dst
                    ValueLayout.JAVA_INT, // byte_count
                    ValueLayout.JAVA_INT, // pattern_le
                ),
                critical,
            )

            val mortonTileHandle = linker.downcallHandle(
                sym("fuji_render_morton_tile_8x8"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // linear_src
                    ValueLayout.ADDRESS,  // morton_dst
                    ValueLayout.JAVA_INT, // width
                    ValueLayout.JAVA_INT, // height
                    ValueLayout.JAVA_INT, // src_stride_bytes
                ),
                critical,
            )

            val blitMortonHandle = linker.downcallHandle(
                sym("fuji_render_blit_morton_quad"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,     // texture
                    ValueLayout.ADDRESS,     // frame
                    ValueLayout.JAVA_INT,    // canvas_width
                    ValueLayout.JAVA_INT,    // tex_width
                    ValueLayout.JAVA_INT,    // tex_height
                    ValueLayout.JAVA_INT,    // pitch_tiles
                    ValueLayout.JAVA_INT,    // row_start
                    ValueLayout.JAVA_INT,    // row_end
                    ValueLayout.JAVA_INT,    // col_start
                    ValueLayout.JAVA_INT,    // col_end
                    ValueLayout.JAVA_DOUBLE, // left
                    ValueLayout.JAVA_DOUBLE, // top
                    ValueLayout.JAVA_DOUBLE, // scale_x
                    ValueLayout.JAVA_DOUBLE, // scale_y
                ),
                critical,
            )

            val filteredMortonHandle = lookup.find("fuji_render_blit_filtered_morton").map { symbol ->
                linker.downcallHandle(
                    symbol, FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                    ), critical,
                )
            }.orElse(null)

            val fillRectHandle = linker.downcallHandle(
                sym("fuji_render_fill_rect_rgba"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // frame
                    ValueLayout.JAVA_INT, // canvas_width
                    ValueLayout.JAVA_INT, // left
                    ValueLayout.JAVA_INT, // top
                    ValueLayout.JAVA_INT, // right
                    ValueLayout.JAVA_INT, // bottom
                    ValueLayout.JAVA_INT, // color_aabbggrr
                    ValueLayout.JAVA_INT, // src_over
                ),
                critical,
            )

            val fillPolygonHandle = linker.downcallHandle(
                sym("fuji_render_fill_polygon_rgba"),
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,  // frame
                    ValueLayout.JAVA_INT, // canvas_width
                    ValueLayout.ADDRESS,  // edge_x0
                    ValueLayout.ADDRESS,  // edge_y0
                    ValueLayout.ADDRESS,  // edge_x1
                    ValueLayout.ADDRESS,  // edge_y1
                    ValueLayout.JAVA_INT, // edge_count
                    ValueLayout.JAVA_INT, // top
                    ValueLayout.JAVA_INT, // bottom
                    ValueLayout.JAVA_INT, // clip_left
                    ValueLayout.JAVA_INT, // clip_right
                    ValueLayout.JAVA_INT, // color_aabbggrr
                    ValueLayout.JAVA_INT, // src_over
                ),
                critical,
            )

            val fillLinearGradientRectHandle = lookup.find("fuji_render_fill_linear_gradient_rect_rgba").map { s ->
                linker.downcallHandle(
                    s,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS,     // frame
                        ValueLayout.JAVA_INT,    // canvas_width
                        ValueLayout.JAVA_INT,    // canvas_height
                        ValueLayout.JAVA_INT,    // left
                        ValueLayout.JAVA_INT,    // top
                        ValueLayout.JAVA_INT,    // right
                        ValueLayout.JAVA_INT,    // bottom
                        ValueLayout.JAVA_DOUBLE, // m3
                        ValueLayout.JAVA_DOUBLE, // m4
                        ValueLayout.JAVA_DOUBLE, // m5
                        ValueLayout.ADDRESS,     // ramp_lut
                        ValueLayout.JAVA_INT,    // ramp_len
                        ValueLayout.JAVA_INT,    // src_over
                    ),
                    critical,
                )
            }.orElse(null)

            val compositePlanesMatrixHandle = lookup.find("fuji_render_composite_planes_matrix").map { s ->
                linker.downcallHandle(
                    s,
                    FunctionDescriptor.ofVoid(
                        ValueLayout.ADDRESS, // osd_bytes
                        ValueLayout.ADDRESS, // pic0_bytes
                        ValueLayout.ADDRESS, // pic1_bytes
                        ValueLayout.ADDRESS, // source_rows
                        ValueLayout.ADDRESS, // source_cols
                        ValueLayout.ADDRESS, // color_matrix_q10
                        ValueLayout.ADDRESS, // cfg
                        ValueLayout.ADDRESS, // output_argb
                    ),
                    critical,
                )
            }.orElse(null)

            val compositeLcdcToArgbPreHandle = lookup.find("fuji_render_composite_lcdc_to_argb_pre").map { s ->
                linker.downcallHandle(
                    s,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT, // returns 1 if all opaque, 0 if any translucent pixel premultiplied
                        ValueLayout.ADDRESS,  // osd_bytes
                        ValueLayout.ADDRESS,  // pic0_bytes
                        ValueLayout.ADDRESS,  // pic1_bytes
                        ValueLayout.ADDRESS,  // source_rows
                        ValueLayout.ADDRESS,  // source_cols
                        ValueLayout.ADDRESS,  // color_matrix_q10
                        ValueLayout.ADDRESS,  // cfg
                        ValueLayout.ADDRESS,  // output_argb_pre
                    ),
                    critical,
                )
            }.orElse(null)

            Handles(
                libraryPath = path,
                arena = arena,
                simdLevel = simd,
                backendName = name,
                compositePic1Osd0 = compositeHandle,
                decodeRgba = decodeRgbaHandle,
                fillU32 = fillU32Handle,
                mortonTile8x8 = mortonTileHandle,
                blitMortonQuad = blitMortonHandle,
                blitFilteredMorton = filteredMortonHandle,
                fillRectRgba = fillRectHandle,
                fillPolygonRgba = fillPolygonHandle,
                fillLinearGradientRectRgba = fillLinearGradientRectHandle,
                compositePlanesMatrix = compositePlanesMatrixHandle,
                compositeLcdcToArgbPre = compositeLcdcToArgbPreHandle,
            )
        } catch (t: Throwable) {
            lastLoadError = "${t.javaClass.simpleName}: ${t.message} (path=$path)"
            null
        }
    }

    fun compositePic1AndOsd0(
        osdBytes: ByteArray?,
        osdBaseOffset: Int,
        osdStrideBytes: Int,
        pic1Bytes: ByteArray?,
        pic1X: Int,
        pic1Y: Int,
        pic1Width: Int,
        pic1Height: Int,
        pic1StrideBytes: Int,
        pic1Alpha: Int,
        opaqueOsdBackdrop: Boolean,
        canvasWidth: Int,
        canvasHeight: Int,
        sourceRows: IntArray,
        sourceCols: IntArray,
        outWidth: Int,
        outHeight: Int,
        output: IntArray,
    ): Boolean {
        val h = handles ?: return false
        val osdSeg = if (osdBytes != null) MemorySegment.ofArray(osdBytes) else MemorySegment.NULL
        val pic1Seg = if (pic1Bytes != null) MemorySegment.ofArray(pic1Bytes) else MemorySegment.NULL
        val rowsSeg = if (sourceRows.isNotEmpty()) MemorySegment.ofArray(sourceRows) else MemorySegment.NULL
        val colsSeg = if (sourceCols.isNotEmpty()) MemorySegment.ofArray(sourceCols) else MemorySegment.NULL
        val outSeg = MemorySegment.ofArray(output)
        h.compositePic1Osd0.invokeExact(
            osdSeg,
            osdBaseOffset,
            osdStrideBytes,
            pic1Seg,
            pic1X,
            pic1Y,
            pic1Width,
            pic1Height,
            pic1StrideBytes,
            pic1Alpha,
            if (opaqueOsdBackdrop) 1 else 0,
            canvasWidth,
            canvasHeight,
            rowsSeg,
            colsSeg,
            outWidth,
            outHeight,
            outSeg,
        )
        return true
    }

    fun decodeRgba(
        srcBytes: ByteArray,
        srcBaseOffset: Int,
        srcStrideBytes: Int,
        sourceRows: IntArray,
        sourceCols: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        outWidth: Int,
        outHeight: Int,
        output: IntArray,
    ): Boolean {
        val h = handles ?: return false
        val srcSeg = MemorySegment.ofArray(srcBytes)
        val rowsSeg = if (sourceRows.isNotEmpty()) MemorySegment.ofArray(sourceRows) else MemorySegment.NULL
        val colsSeg = if (sourceCols.isNotEmpty()) MemorySegment.ofArray(sourceCols) else MemorySegment.NULL
        val outSeg = MemorySegment.ofArray(output)
        h.decodeRgba.invokeExact(
            srcSeg,
            srcBaseOffset,
            srcStrideBytes,
            rowsSeg,
            colsSeg,
            srcWidth,
            srcHeight,
            outWidth,
            outHeight,
            outSeg,
        )
        return true
    }

    fun fillU32(dst: ByteArray, byteCount: Int, patternLe: Int): Boolean {
        val h = handles ?: return false
        if (byteCount <= 0) return true
        h.fillU32.invokeExact(MemorySegment.ofArray(dst), byteCount, patternLe)
        return true
    }

    fun mortonTile8x8(
        linearSrc: ByteArray,
        mortonDst: ByteArray,
        width: Int,
        height: Int,
        srcStrideBytes: Int,
    ): Boolean {
        val h = handles ?: return false
        h.mortonTile8x8.invokeExact(
            MemorySegment.ofArray(linearSrc),
            MemorySegment.ofArray(mortonDst),
            width,
            height,
            srcStrideBytes,
        )
        return true
    }

    fun blitMortonQuad(
        texture: ByteArray,
        frame: ByteArray,
        canvasWidth: Int,
        texWidth: Int,
        texHeight: Int,
        pitchTiles: Int,
        rowStart: Int,
        rowEnd: Int,
        colStart: Int,
        colEnd: Int,
        left: Double,
        top: Double,
        scaleX: Double,
        scaleY: Double,
    ): Boolean {
        val h = handles ?: return false
        h.blitMortonQuad.invokeExact(
            MemorySegment.ofArray(texture),
            MemorySegment.ofArray(frame),
            canvasWidth,
            texWidth,
            texHeight,
            pitchTiles,
            rowStart,
            rowEnd,
            colStart,
            colEnd,
            left,
            top,
            scaleX,
            scaleY,
        )
        return true
    }

    fun blitFilteredMorton(
        texture: ByteArray, frame: ByteArray, canvasWidth: Int, width: Int, height: Int, pitch: Int,
        top: Int, bottom: Int, left: Int, right: Int,
        u0: Double, v0: Double, duDx: Double, dvDx: Double, duDy: Double, dvDy: Double,
    ): Boolean {
        val handle = handles?.blitFilteredMorton ?: return false
        handle.invokeExact(
            MemorySegment.ofArray(texture), MemorySegment.ofArray(frame), canvasWidth, width, height, pitch,
            top, bottom, left, right, u0, v0, duDx, dvDx, duDy, dvDy,
        )
        return true
    }

    fun fillRectRgba(
        frame: ByteArray,
        canvasWidth: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorAabbggrr: Int,
        srcOver: Boolean,
    ): Boolean {
        val h = handles ?: return false
        h.fillRectRgba.invokeExact(
            MemorySegment.ofArray(frame),
            canvasWidth,
            left,
            top,
            right,
            bottom,
            colorAabbggrr,
            if (srcOver) 1 else 0,
        )
        return true
    }

    fun fillPolygonRgba(
        frame: ByteArray,
        canvasWidth: Int,
        edgeX0: DoubleArray,
        edgeY0: DoubleArray,
        edgeX1: DoubleArray,
        edgeY1: DoubleArray,
        edgeCount: Int,
        top: Int,
        bottom: Int,
        clipLeft: Int,
        clipRight: Int,
        colorAabbggrr: Int,
        srcOver: Boolean,
    ): Boolean {
        val h = handles ?: return false
        if (edgeCount <= 0 || top >= bottom || clipLeft >= clipRight) return true
        h.fillPolygonRgba.invokeExact(
            MemorySegment.ofArray(frame),
            canvasWidth,
            MemorySegment.ofArray(edgeX0),
            MemorySegment.ofArray(edgeY0),
            MemorySegment.ofArray(edgeX1),
            MemorySegment.ofArray(edgeY1),
            edgeCount,
            top,
            bottom,
            clipLeft,
            clipRight,
            colorAabbggrr,
            if (srcOver) 1 else 0,
        )
        return true
    }

    fun fillLinearGradientRectRgba(
        frame: ByteArray,
        canvasWidth: Int,
        canvasHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        m3: Double,
        m4: Double,
        m5: Double,
        rampLut: IntArray,
        srcOver: Boolean,
    ): Boolean {
        val fn = handles?.fillLinearGradientRectRgba ?: return false
        if (left >= right || top >= bottom || rampLut.isEmpty()) return true
        fn.invokeExact(
            MemorySegment.ofArray(frame),
            canvasWidth,
            canvasHeight,
            left,
            top,
            right,
            bottom,
            m3,
            m4,
            m5,
            MemorySegment.ofArray(rampLut),
            rampLut.size,
            if (srcOver) 1 else 0,
        )
        return true
    }

    fun compositePlanesWithMatrix(
        osdBytes: ByteArray?,
        pic0Bytes: ByteArray?,
        pic1Bytes: ByteArray?,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        output: IntArray,
    ): Boolean = compositePlanesWithMatrixDirect(
        osdSegment = if (osdBytes != null) MemorySegment.ofArray(osdBytes) else MemorySegment.NULL,
        pic0Segment = if (pic0Bytes != null) MemorySegment.ofArray(pic0Bytes) else MemorySegment.NULL,
        pic1Segment = if (pic1Bytes != null) MemorySegment.ofArray(pic1Bytes) else MemorySegment.NULL,
        sourceRows = sourceRows,
        sourceCols = sourceCols,
        colorMatrixQ10 = colorMatrixQ10,
        cfg = cfg,
        output = output,
    )

    fun compositePlanesWithMatrixDirect(
        osdSegment: MemorySegment,
        pic0Segment: MemorySegment,
        pic1Segment: MemorySegment,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        output: IntArray,
    ): Boolean {
        val fn = handles?.compositePlanesMatrix ?: return false
        val rowsSeg = if (sourceRows.isNotEmpty()) MemorySegment.ofArray(sourceRows) else MemorySegment.NULL
        val colsSeg = if (sourceCols.isNotEmpty()) MemorySegment.ofArray(sourceCols) else MemorySegment.NULL
        val matSeg = if (colorMatrixQ10 != null && colorMatrixQ10.size >= 9) MemorySegment.ofArray(colorMatrixQ10) else MemorySegment.NULL
        fn.invokeExact(
            osdSegment,
            pic0Segment,
            pic1Segment,
            rowsSeg,
            colsSeg,
            matSeg,
            MemorySegment.ofArray(cfg),
            MemorySegment.ofArray(output),
        )
        return true
    }

    /**
     * Single-pass AVX-512/AVX2/SSE2 composite of PIC0 + PIC1 + OSD0 + 3x3 Q10 color matrix directly
     * into an off-heap `INT_ARGB_PRE` [MemorySegment] (e.g. a JavaFX `PixelBuffer` backing buffer).
     *
     * @return `1` if all output pixels were opaque (`alpha == 0xFF`), `0` if any translucent pixel
     *   was premultiplied in-register, or `-1` if the native kernel is unavailable.
     */
    fun compositeLcdcToArgbPre(
        osdBytes: ByteArray?,
        pic0Bytes: ByteArray?,
        pic1Bytes: ByteArray?,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        outputSegment: MemorySegment,
    ): Int {
        val fn = handles?.compositeLcdcToArgbPre ?: return -1
        val osdSeg = if (osdBytes != null) MemorySegment.ofArray(osdBytes) else MemorySegment.NULL
        val pic0Seg = if (pic0Bytes != null) MemorySegment.ofArray(pic0Bytes) else MemorySegment.NULL
        val pic1Seg = if (pic1Bytes != null) MemorySegment.ofArray(pic1Bytes) else MemorySegment.NULL
        val rowsSeg = if (sourceRows.isNotEmpty()) MemorySegment.ofArray(sourceRows) else MemorySegment.NULL
        val colsSeg = if (sourceCols.isNotEmpty()) MemorySegment.ofArray(sourceCols) else MemorySegment.NULL
        val matSeg = if (colorMatrixQ10 != null && colorMatrixQ10.size >= 9) MemorySegment.ofArray(colorMatrixQ10) else MemorySegment.NULL
        return fn.invokeExact(
            osdSeg,
            pic0Seg,
            pic1Seg,
            rowsSeg,
            colsSeg,
            matSeg,
            MemorySegment.ofArray(cfg),
            outputSegment,
        ) as Int
    }

    /**
     * Direct off-heap source + direct off-heap target overload of [compositeLcdcToArgbPre].
     */
    fun compositeLcdcToArgbPreDirect(
        osdSegment: MemorySegment,
        pic0Segment: MemorySegment,
        pic1Segment: MemorySegment,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        outputSegment: MemorySegment,
    ): Int {
        val fn = handles?.compositeLcdcToArgbPre ?: return -1
        val rowsSeg = if (sourceRows.isNotEmpty()) MemorySegment.ofArray(sourceRows) else MemorySegment.NULL
        val colsSeg = if (sourceCols.isNotEmpty()) MemorySegment.ofArray(sourceCols) else MemorySegment.NULL
        val matSeg = if (colorMatrixQ10 != null && colorMatrixQ10.size >= 9) MemorySegment.ofArray(colorMatrixQ10) else MemorySegment.NULL
        return fn.invokeExact(
            osdSegment,
            pic0Segment,
            pic1Segment,
            rowsSeg,
            colsSeg,
            matSeg,
            MemorySegment.ofArray(cfg),
            outputSegment,
        ) as Int
    }

    /**
     * Heap `IntArray` overload of [compositeLcdcToArgbPre].
     */
    fun compositeLcdcToArgbPre(
        osdBytes: ByteArray?,
        pic0Bytes: ByteArray?,
        pic1Bytes: ByteArray?,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        output: IntArray,
    ): Int = compositeLcdcToArgbPre(
        osdBytes = osdBytes,
        pic0Bytes = pic0Bytes,
        pic1Bytes = pic1Bytes,
        sourceRows = sourceRows,
        sourceCols = sourceCols,
        colorMatrixQ10 = colorMatrixQ10,
        cfg = cfg,
        outputSegment = MemorySegment.ofArray(output),
    )
}
