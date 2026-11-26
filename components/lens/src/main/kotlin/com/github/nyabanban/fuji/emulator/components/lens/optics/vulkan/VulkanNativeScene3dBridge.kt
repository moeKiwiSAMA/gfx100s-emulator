package com.github.nyabanban.fuji.emulator.components.lens.optics.vulkan

import com.github.nyabanban.fuji.emulator.api.GpuScene3dRenderSpec
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path

/**
 * JDK 25 FFM companion bridge to the `fuji_vk_scene3d_*` exports inside
 * `libfuji_vulkan_optics.so` / `fuji_vulkan_optics.dll`.
 *
 * Kept in its own dedicated source file so it binds cleanly alongside
 * [VulkanNativeOpticsBridge] with zero static Vulkan linkage and zero per-frame allocations.
 */
class VulkanNativeScene3dBridge private constructor(
    private val libraryArena: Arena,
    private val getSpirvWordsFn: MethodHandle,
    private val uploadMeshFn: MethodHandle,
    private val uploadStageFn: MethodHandle,
    private val renderMilkFrogFn: MethodHandle,
    private val uploadQrMatrixFn: MethodHandle,
    private val renderQrBallFn: MethodHandle,
    private val uploadMonaLisaFn: MethodHandle,
    private val renderMonaLisaFn: MethodHandle,
    private val uploadLayeredBaseFn: MethodHandle,
    private val renderLayeredFn: MethodHandle,
    private val readbackSceneFn: MethodHandle,
) : AutoCloseable {

    private val stagingLock = Any()
    private val fixedArena: Arena = Arena.ofShared()
    private val milkFrogParamSeg: MemorySegment = fixedArena.allocate(MILKFROG_PARAMS_BYTES, 16L)
    private val qrBallParamSeg: MemorySegment = fixedArena.allocate(QRBALL_PARAMS_BYTES, 16L)
    private val monaLisaParamSeg: MemorySegment = fixedArena.allocate(MONALISA_PARAMS_BYTES, 16L)
    private val layeredParamSeg: MemorySegment = fixedArena.allocate(LAYERED_PARAMS_BYTES, 16L)
    private val qrBitsSeg: MemorySegment = fixedArena.allocate(32L * 4L, 16L)
    private val outMsSeg: MemorySegment = fixedArena.allocate(ValueLayout.JAVA_DOUBLE, 8L)

    private var uploadArena: Arena? = null
    private var uploadSeg: MemorySegment = MemorySegment.NULL
    private var auxArena: Arena? = null
    private var auxSeg: MemorySegment = MemorySegment.NULL
    private var gridArena: Arena? = null
    private var gridSeg: MemorySegment = MemorySegment.NULL
    private var readbackArena: Arena? = null
    private var readbackSeg: MemorySegment = MemorySegment.NULL

    private fun ensureUploadSeg(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (uploadSeg != MemorySegment.NULL && uploadSeg.byteSize() >= needed) return uploadSeg
        uploadArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        uploadArena = arena
        return arena.allocate(needed, 16L).also { uploadSeg = it }
    }

    private fun ensureAuxSeg(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (auxSeg != MemorySegment.NULL && auxSeg.byteSize() >= needed) return auxSeg
        auxArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        auxArena = arena
        return arena.allocate(needed, 16L).also { auxSeg = it }
    }

    private fun ensureGridSeg(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (gridSeg != MemorySegment.NULL && gridSeg.byteSize() >= needed) return gridSeg
        gridArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        gridArena = arena
        return arena.allocate(needed, 16L).also { gridSeg = it }
    }

    private fun ensureReadbackSeg(bytes: Long): MemorySegment {
        val needed = bytes.coerceAtLeast(64L)
        if (readbackSeg != MemorySegment.NULL && readbackSeg.byteSize() >= needed) return readbackSeg
        readbackArena?.let { if (it.scope().isAlive) it.close() }
        val arena = Arena.ofShared()
        readbackArena = arena
        return arena.allocate(needed, 16L).also { readbackSeg = it }
    }

    fun getSpirvWords(shaderId: Int): IntArray = Arena.ofConfined().use { arena ->
        val pWords = arena.allocate(ValueLayout.ADDRESS)
        val pCount = arena.allocate(ValueLayout.JAVA_INT)
        val rc = getSpirvWordsFn.invokeExact(shaderId, pWords, pCount) as Int
        require(rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
            "fuji_vk_scene3d_get_spirv_words($shaderId) failed with rc=$rc"
        }
        val count = pCount.get(ValueLayout.JAVA_INT, 0L)
        val rawPtr = pWords.get(ValueLayout.ADDRESS, 0L).reinterpret(count.toLong() * 4L)
        IntArray(count).also { MemorySegment.copy(rawPtr, ValueLayout.JAVA_INT, 0L, it, 0, count) }
    }

    fun uploadMesh(
        ctx: MemorySegment,
        meshKey: Long,
        vertexData: FloatArray,
        vertexCount: Int,
        indices: IntArray,
        triCount: Int,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val vFloats = Math.multiplyExact(vertexCount, 12)
        val iCount = Math.multiplyExact(triCount, 3)
        val vSeg = ensureUploadSeg(vFloats.toLong() * 4L)
        val iSeg = ensureAuxSeg(iCount.toLong() * 4L)
        MemorySegment.copy(vertexData, 0, vSeg, ValueLayout.JAVA_FLOAT, 0L, vFloats)
        MemorySegment.copy(indices, 0, iSeg, ValueLayout.JAVA_INT, 0L, iCount)
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = uploadMeshFn.invokeExact(ctx, meshKey, vSeg, vertexCount, iSeg, triCount, outMsSeg) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun uploadStage(
        ctx: MemorySegment,
        stageKey: Long,
        stageRgbaDepth: FloatArray,
        width: Int,
        height: Int,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val floats = Math.multiplyExact(Math.multiplyExact(width, height), 4)
        val seg = ensureUploadSeg(floats.toLong() * 4L)
        MemorySegment.copy(stageRgbaDepth, 0, seg, ValueLayout.JAVA_FLOAT, 0L, floats)
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = uploadStageFn.invokeExact(ctx, stageKey, seg, width, height, outMsSeg) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderMilkFrog(
        ctx: MemorySegment,
        spec: GpuScene3dRenderSpec.MilkFrog,
        outDepthGrid: FloatArray?,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val tilesX = (spec.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (spec.height + TILE_SIZE - 1) / TILE_SIZE
        val gridLen = if (outDepthGrid != null) tilesX * tilesY else 0
        val gSeg = if (gridLen > 0) ensureGridSeg(gridLen.toLong() * 4L) else MemorySegment.NULL

        val s = milkFrogParamSeg
        s.set(ValueLayout.JAVA_INT, 0L, spec.width)
        s.set(ValueLayout.JAVA_INT, 4L, spec.height)
        s.set(ValueLayout.JAVA_INT, 8L, tilesX)
        s.set(ValueLayout.JAVA_INT, 12L, tilesY)
        s.set(ValueLayout.JAVA_INT, 16L, spec.vertexCount)
        s.set(ValueLayout.JAVA_INT, 20L, spec.triCount)
        s.set(ValueLayout.JAVA_INT, 24L, if (spec.hasPose) 1 else 0)
        s.set(ValueLayout.JAVA_INT, 28L, if (spec.hasTranslation) 1 else 0)
        s.set(ValueLayout.JAVA_FLOAT, 32L, spec.halfW)
        s.set(ValueLayout.JAVA_FLOAT, 36L, spec.halfH)
        s.set(ValueLayout.JAVA_FLOAT, 40L, spec.focal)
        s.set(ValueLayout.JAVA_FLOAT, 44L, spec.invFocalScale)
        s.set(ValueLayout.JAVA_FLOAT, 48L, spec.ox)
        s.set(ValueLayout.JAVA_FLOAT, 52L, spec.oy)
        s.set(ValueLayout.JAVA_FLOAT, 56L, spec.oz)
        s.set(ValueLayout.JAVA_FLOAT, 60L, spec.cR)
        s.set(ValueLayout.JAVA_FLOAT, 64L, spec.sR)
        s.set(ValueLayout.JAVA_FLOAT, 68L, spec.yawShiftPx)
        s.set(ValueLayout.JAVA_FLOAT, 72L, spec.pitchShiftPx)
        s.set(ValueLayout.JAVA_FLOAT, 76L, spec.posXM)
        s.set(ValueLayout.JAVA_FLOAT, 80L, spec.posYM)
        s.set(ValueLayout.JAVA_FLOAT, 84L, spec.posZM)
        s.set(ValueLayout.JAVA_FLOAT, 88L, spec.r00)
        s.set(ValueLayout.JAVA_FLOAT, 92L, spec.r01)
        s.set(ValueLayout.JAVA_FLOAT, 96L, spec.r02)
        s.set(ValueLayout.JAVA_FLOAT, 100L, spec.r10)
        s.set(ValueLayout.JAVA_FLOAT, 104L, spec.r11)
        s.set(ValueLayout.JAVA_FLOAT, 108L, spec.r12)
        s.set(ValueLayout.JAVA_FLOAT, 112L, spec.r20)
        s.set(ValueLayout.JAVA_FLOAT, 116L, spec.r21)
        s.set(ValueLayout.JAVA_FLOAT, 120L, spec.r22)
        s.set(ValueLayout.JAVA_FLOAT, 124L, spec.frogX)
        s.set(ValueLayout.JAVA_FLOAT, 128L, spec.frogY)
        s.set(ValueLayout.JAVA_FLOAT, 132L, spec.frogZ)
        s.set(ValueLayout.JAVA_FLOAT, 136L, spec.cLean)
        s.set(ValueLayout.JAVA_FLOAT, 140L, spec.sLean)
        s.set(ValueLayout.JAVA_FLOAT, 144L, spec.cRock)
        s.set(ValueLayout.JAVA_FLOAT, 148L, spec.sRock)
        s.set(ValueLayout.JAVA_FLOAT, 152L, spec.bellySquash)
        s.set(ValueLayout.JAVA_FLOAT, 156L, spec.handBounce)
        s.set(ValueLayout.JAVA_FLOAT, 160L, spec.kickPhase)
        s.set(ValueLayout.JAVA_FLOAT, 164L, spec.mouthVib)
        s.set(ValueLayout.JAVA_FLOAT, 168L, spec.arcPulse)
        s.set(ValueLayout.JAVA_INT, 172L, spec.orbCount.coerceIn(0, MAX_ORBS))
        s.set(ValueLayout.JAVA_FLOAT, 176L, spec.pedZ)
        s.set(ValueLayout.JAVA_FLOAT, 180L, spec.pedTopY)
        s.set(ValueLayout.JAVA_FLOAT, 184L, spec.pedBotY)
        s.set(ValueLayout.JAVA_FLOAT, 188L, spec.pedRad)
        val orbFloats = (spec.orbCount.coerceIn(0, MAX_ORBS) * 8).coerceAtMost(spec.orbs.size)
        if (orbFloats > 0) {
            MemorySegment.copy(spec.orbs, 0, s, ValueLayout.JAVA_FLOAT, 192L, orbFloats)
        }

        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = renderMilkFrogFn.invokeExact(ctx, spec.sceneKey, s, gSeg, gridLen, outMsSeg) as Int
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK && outDepthGrid != null && gridLen > 0) {
            MemorySegment.copy(gSeg, ValueLayout.JAVA_FLOAT, 0L, outDepthGrid, 0, gridLen.coerceAtMost(outDepthGrid.size))
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun uploadQrMatrix(ctx: MemorySegment, qrKey: Long, rowBits32: IntArray): Pair<Int, Double> = synchronized(stagingLock) {
        MemorySegment.copy(rowBits32, 0, qrBitsSeg, ValueLayout.JAVA_INT, 0L, 32.coerceAtMost(rowBits32.size))
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = uploadQrMatrixFn.invokeExact(ctx, qrKey, qrBitsSeg, outMsSeg) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderQrBall(
        ctx: MemorySegment,
        spec: GpuScene3dRenderSpec.QrBall,
        outDepthGrid: FloatArray?,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val tilesX = (spec.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (spec.height + TILE_SIZE - 1) / TILE_SIZE
        val gridLen = if (outDepthGrid != null) tilesX * tilesY else 0
        val gSeg = if (gridLen > 0) ensureGridSeg(gridLen.toLong() * 4L) else MemorySegment.NULL

        val s = qrBallParamSeg
        s.set(ValueLayout.JAVA_INT, 0L, spec.width)
        s.set(ValueLayout.JAVA_INT, 4L, spec.height)
        s.set(ValueLayout.JAVA_INT, 8L, tilesX)
        s.set(ValueLayout.JAVA_INT, 12L, tilesY)
        s.set(ValueLayout.JAVA_INT, 16L, spec.qrModules)
        s.set(ValueLayout.JAVA_INT, 20L, spec.quietZone)
        s.set(ValueLayout.JAVA_INT, 24L, spec.totalQrModules)
        s.set(ValueLayout.JAVA_FLOAT, 28L, spec.rotationTurns)
        s.set(ValueLayout.JAVA_FLOAT, 32L, spec.halfW)
        s.set(ValueLayout.JAVA_FLOAT, 36L, spec.halfH)
        s.set(ValueLayout.JAVA_FLOAT, 40L, spec.focal)
        s.set(ValueLayout.JAVA_FLOAT, 44L, spec.ox)
        s.set(ValueLayout.JAVA_FLOAT, 48L, spec.oy)
        s.set(ValueLayout.JAVA_FLOAT, 52L, spec.oz)
        s.set(ValueLayout.JAVA_FLOAT, 56L, spec.r00)
        s.set(ValueLayout.JAVA_FLOAT, 60L, spec.r01)
        s.set(ValueLayout.JAVA_FLOAT, 64L, spec.r02)
        s.set(ValueLayout.JAVA_FLOAT, 68L, spec.r10)
        s.set(ValueLayout.JAVA_FLOAT, 72L, spec.r11)
        s.set(ValueLayout.JAVA_FLOAT, 76L, spec.r12)
        s.set(ValueLayout.JAVA_FLOAT, 80L, spec.r20)
        s.set(ValueLayout.JAVA_FLOAT, 84L, spec.r21)
        s.set(ValueLayout.JAVA_FLOAT, 88L, spec.r22)
        s.set(ValueLayout.JAVA_FLOAT, 92L, spec.wallZ)

        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = renderQrBallFn.invokeExact(ctx, spec.sceneKey, s, gSeg, gridLen, outMsSeg) as Int
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK && outDepthGrid != null && gridLen > 0) {
            MemorySegment.copy(gSeg, ValueLayout.JAVA_FLOAT, 0L, outDepthGrid, 0, gridLen.coerceAtMost(outDepthGrid.size))
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun uploadMonaLisa(
        ctx: MemorySegment,
        portraitKey: Long,
        fgRgba: FloatArray,
        bgRgbDepthOffset: FloatArray,
        srcWidth: Int,
        srcHeight: Int,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val floats = Math.multiplyExact(Math.multiplyExact(srcWidth, srcHeight), 4)
        val fgSeg = ensureUploadSeg(floats.toLong() * 4L)
        val bgSeg = ensureAuxSeg(floats.toLong() * 4L)
        MemorySegment.copy(fgRgba, 0, fgSeg, ValueLayout.JAVA_FLOAT, 0L, floats)
        MemorySegment.copy(bgRgbDepthOffset, 0, bgSeg, ValueLayout.JAVA_FLOAT, 0L, floats)
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = uploadMonaLisaFn.invokeExact(ctx, portraitKey, fgSeg, bgSeg, srcWidth, srcHeight, outMsSeg) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderMonaLisa(
        ctx: MemorySegment,
        spec: GpuScene3dRenderSpec.MonaLisa,
        outDepthGrid: FloatArray?,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val tilesX = (spec.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (spec.height + TILE_SIZE - 1) / TILE_SIZE
        val gridLen = if (outDepthGrid != null) tilesX * tilesY else 0
        val gSeg = if (gridLen > 0) ensureGridSeg(gridLen.toLong() * 4L) else MemorySegment.NULL

        val s = monaLisaParamSeg
        s.set(ValueLayout.JAVA_INT, 0L, spec.width)
        s.set(ValueLayout.JAVA_INT, 4L, spec.height)
        s.set(ValueLayout.JAVA_INT, 8L, spec.srcWidth)
        s.set(ValueLayout.JAVA_INT, 12L, spec.srcHeight)
        s.set(ValueLayout.JAVA_INT, 16L, tilesX)
        s.set(ValueLayout.JAVA_INT, 20L, tilesY)
        s.set(ValueLayout.JAVA_INT, 24L, 0)
        s.set(ValueLayout.JAVA_INT, 28L, 0)
        s.set(ValueLayout.JAVA_FLOAT, 32L, spec.bgShiftPx)
        s.set(ValueLayout.JAVA_FLOAT, 36L, spec.bgScaleX)
        s.set(ValueLayout.JAVA_FLOAT, 40L, spec.bgScaleY)
        s.set(ValueLayout.JAVA_FLOAT, 44L, spec.invBgZoom)
        s.set(ValueLayout.JAVA_FLOAT, 48L, spec.bgCx)
        s.set(ValueLayout.JAVA_FLOAT, 52L, spec.bgCy)
        s.set(ValueLayout.JAVA_FLOAT, 56L, spec.dstPivotX)
        s.set(ValueLayout.JAVA_FLOAT, 60L, spec.dstPivotY)
        s.set(ValueLayout.JAVA_FLOAT, 64L, spec.srcPivotX)
        s.set(ValueLayout.JAVA_FLOAT, 68L, spec.srcPivotY)
        s.set(ValueLayout.JAVA_FLOAT, 72L, spec.stepXX)
        s.set(ValueLayout.JAVA_FLOAT, 76L, spec.stepXY)
        s.set(ValueLayout.JAVA_FLOAT, 80L, spec.stepYX)
        s.set(ValueLayout.JAVA_FLOAT, 84L, spec.stepYY)
        s.set(ValueLayout.JAVA_FLOAT, 88L, spec.subjectDepthM)
        s.set(ValueLayout.JAVA_FLOAT, 92L, spec.backdropDepthM)

        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = renderMonaLisaFn.invokeExact(ctx, spec.sceneKey, s, gSeg, gridLen, outMsSeg) as Int
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK && outDepthGrid != null && gridLen > 0) {
            MemorySegment.copy(gSeg, ValueLayout.JAVA_FLOAT, 0L, outDepthGrid, 0, gridLen.coerceAtMost(outDepthGrid.size))
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun uploadLayeredBase(
        ctx: MemorySegment,
        baseKey: Long,
        baseRgbaDepth: FloatArray,
        width: Int,
        height: Int,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val floats = Math.multiplyExact(Math.multiplyExact(width, height), 4)
        val seg = ensureUploadSeg(floats.toLong() * 4L)
        MemorySegment.copy(baseRgbaDepth, 0, seg, ValueLayout.JAVA_FLOAT, 0L, floats)
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = uploadLayeredBaseFn.invokeExact(ctx, baseKey, seg, width, height, outMsSeg) as Int
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun renderLayered(
        ctx: MemorySegment,
        spec: GpuScene3dRenderSpec.LayeredStudio,
        outDepthGrid: FloatArray?,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val tilesX = (spec.width + TILE_SIZE - 1) / TILE_SIZE
        val tilesY = (spec.height + TILE_SIZE - 1) / TILE_SIZE
        val gridLen = if (outDepthGrid != null) tilesX * tilesY else 0
        val gSeg = if (gridLen > 0) ensureGridSeg(gridLen.toLong() * 4L) else MemorySegment.NULL

        val s = layeredParamSeg
        s.set(ValueLayout.JAVA_INT, 0L, spec.width)
        s.set(ValueLayout.JAVA_INT, 4L, spec.height)
        s.set(ValueLayout.JAVA_INT, 8L, tilesX)
        s.set(ValueLayout.JAVA_INT, 12L, tilesY)
        s.set(ValueLayout.JAVA_INT, 16L, if (spec.hasPose) 1 else 0)
        s.set(ValueLayout.JAVA_INT, 20L, if (spec.hasTranslation) 1 else 0)
        s.set(ValueLayout.JAVA_INT, 24L, 0)
        s.set(ValueLayout.JAVA_INT, 28L, 0)
        s.set(ValueLayout.JAVA_FLOAT, 32L, spec.halfW)
        s.set(ValueLayout.JAVA_FLOAT, 36L, spec.halfH)
        s.set(ValueLayout.JAVA_FLOAT, 40L, spec.invFocalScale)
        s.set(ValueLayout.JAVA_FLOAT, 44L, spec.focal)
        s.set(ValueLayout.JAVA_FLOAT, 48L, spec.cosR)
        s.set(ValueLayout.JAVA_FLOAT, 52L, spec.sinR)
        s.set(ValueLayout.JAVA_FLOAT, 56L, spec.yawShiftPx)
        s.set(ValueLayout.JAVA_FLOAT, 60L, spec.pitchShiftPx)
        s.set(ValueLayout.JAVA_FLOAT, 64L, spec.posXM)
        s.set(ValueLayout.JAVA_FLOAT, 68L, spec.posYM)
        s.set(ValueLayout.JAVA_FLOAT, 72L, spec.posZM)
        s.set(ValueLayout.JAVA_FLOAT, 76L, spec.bgDepthM)

        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = renderLayeredFn.invokeExact(ctx, spec.sceneKey, s, gSeg, gridLen, outMsSeg) as Int
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK && outDepthGrid != null && gridLen > 0) {
            MemorySegment.copy(gSeg, ValueLayout.JAVA_FLOAT, 0L, outDepthGrid, 0, gridLen.coerceAtMost(outDepthGrid.size))
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    fun readbackScene(
        ctx: MemorySegment,
        outRgbaDepth: FloatArray,
        pixelCount: Int,
    ): Pair<Int, Double> = synchronized(stagingLock) {
        val floats = Math.multiplyExact(pixelCount, 4)
        val seg = ensureReadbackSeg(floats.toLong() * 4L)
        outMsSeg.set(ValueLayout.JAVA_DOUBLE, 0L, 0.0)
        val rc = readbackSceneFn.invokeExact(ctx, seg, pixelCount.toLong(), outMsSeg) as Int
        if (rc == VulkanNativeOpticsBridge.FUJI_VK_OK) {
            MemorySegment.copy(seg, ValueLayout.JAVA_FLOAT, 0L, outRgbaDepth, 0, floats)
        }
        rc to outMsSeg.get(ValueLayout.JAVA_DOUBLE, 0L)
    }

    override fun close() {
        synchronized(stagingLock) {
            uploadArena?.let { if (it.scope().isAlive) it.close() }
            auxArena?.let { if (it.scope().isAlive) it.close() }
            gridArena?.let { if (it.scope().isAlive) it.close() }
            readbackArena?.let { if (it.scope().isAlive) it.close() }
            if (fixedArena.scope().isAlive) fixedArena.close()
            if (libraryArena.scope().isAlive) libraryArena.close()
        }
    }

    companion object {
        const val TILE_SIZE = 16
        const val MAX_ORBS = 8
        const val SHADER_COUNT = 6
        private const val MILKFROG_PARAMS_BYTES = 448L
        private const val QRBALL_PARAMS_BYTES = 96L
        private const val MONALISA_PARAMS_BYTES = 96L
        private const val LAYERED_PARAMS_BYTES = 80L

        fun tryBind(libraryPath: Path): VulkanNativeScene3dBridge? {
            val arena = Arena.ofShared()
            return try {
                val lookup = SymbolLookup.libraryLookup(libraryPath, arena)
                val linker = Linker.nativeLinker()
                fun req(name: String, fd: FunctionDescriptor): MethodHandle {
                    val sym = lookup.find(name).orElseThrow {
                        UnsatisfiedLinkError("Missing Scene3D symbol '$name' in $libraryPath")
                    }
                    return linker.downcallHandle(sym, fd)
                }
                VulkanNativeScene3dBridge(
                    libraryArena = arena,
                    getSpirvWordsFn = req(
                        "fuji_vk_scene3d_get_spirv_words",
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    ),
                    uploadMeshFn = req(
                        "fuji_vk_scene3d_upload_mesh",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    uploadStageFn = req(
                        "fuji_vk_scene3d_upload_stage",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderMilkFrogFn = req(
                        "fuji_vk_scene3d_render_milk_frog",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    uploadQrMatrixFn = req(
                        "fuji_vk_scene3d_upload_qr_matrix",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ),
                    ),
                    renderQrBallFn = req(
                        "fuji_vk_scene3d_render_qr_ball",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    uploadMonaLisaFn = req(
                        "fuji_vk_scene3d_upload_mona_lisa",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderMonaLisaFn = req(
                        "fuji_vk_scene3d_render_mona_lisa",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    uploadLayeredBaseFn = req(
                        "fuji_vk_scene3d_upload_layered_base",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    renderLayeredFn = req(
                        "fuji_vk_scene3d_render_layered",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    readbackSceneFn = req(
                        "fuji_vk_scene3d_readback_scene",
                        FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                )
            } catch (err: Throwable) {
                java.util.logging.Logger.getLogger(VulkanNativeScene3dBridge::class.java.name)
                    .warning("[vulkan-scene3d] Failed to bind fuji_vk_scene3d_* in $libraryPath: ${err.javaClass.simpleName}: ${err.message}")
                if (arena.scope().isAlive) arena.close()
                null
            }
        }
    }
}
