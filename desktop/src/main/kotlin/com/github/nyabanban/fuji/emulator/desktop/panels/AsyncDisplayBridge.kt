package com.github.nyabanban.fuji.emulator.desktop.panels

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.Ports
import javafx.scene.image.ImageView
import javafx.scene.image.PixelBuffer
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

internal object NativeDisplaySurfaceBridge {
    private val LOG = Logger.getLogger(NativeDisplaySurfaceBridge::class.java.name)
    private const val EXPECTED_ABI_VERSION = 1

    private class Handles(
        @Suppress("unused") val arena: Arena,
        val argbToPre: MethodHandle,
        val compositeLcdcToArgbPre: MethodHandle?,
        val backendName: String,
    )

    private val handles: Handles? by lazy { loadIfEnabled() }

    val isAvailable: Boolean get() = handles != null
    val hasDirectCompositeArgbPre: Boolean get() = handles?.compositeLcdcToArgbPre != null
    val backendName: String get() = handles?.backendName ?: "kotlin-scalar-fallback"

    private fun isDisabled(): Boolean {
        val prop = System.getProperty("fuji.render.native")?.trim()?.lowercase()
        if (prop == "false" || prop == "0" || prop == "off") return true
        val env = System.getenv("FUJI_RENDER_NATIVE")?.trim()?.lowercase()
        return env == "false" || env == "0" || env == "off"
    }

    private fun resolveLibraryPath(): Path? {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        val libName = if (isWindows) "fuji_render.dll" else "libfuji_render.so"
        val buildSubdir = if (isWindows) "build-win64" else "build-linux"
        val cacheSubdir = if (isWindows) "render-bridge-win64" else "render-bridge-linux"
        val candidates = ArrayList<Path>(16)
        System.getProperty("fuji.render.library")?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(Paths.get(it)) }
        System.getenv("FUJI_RENDER_LIBRARY")?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(Paths.get(it)) }
        runCatching {
            val loc = NativeDisplaySurfaceBridge::class.java.protectionDomain?.codeSource?.location?.toURI()
            if (loc != null) {
                var dir = Paths.get(loc).toAbsolutePath().normalize()
                if (Files.isRegularFile(dir)) dir = dir.parent
                repeat(4) {
                    if (dir != null) {
                        candidates.add(dir.resolve("runtime/native/$libName"))
                        candidates.add(dir.resolve(".tools/render-bridge/$buildSubdir/$libName"))
                        dir = dir.parent
                    }
                }
            }
        }
        var cwd: Path? = Paths.get("").toAbsolutePath().normalize()
        repeat(5) {
            val current = cwd
            if (current != null) {
                candidates.add(current.resolve("runtime/native/$libName"))
                candidates.add(current.resolve(".tools/render-bridge/$buildSubdir/$libName"))
                candidates.add(current.resolve("build/render-bridge/$libName"))
                cwd = current.parent
            }
        }
        candidates.add(Paths.get("/opt/fuji-cache/$cacheSubdir/$libName"))
        candidates.add(Paths.get("/opt/fuji-cache/render-bridge/$libName"))
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun loadIfEnabled(): Handles? {
        if (isDisabled()) return null
        val path = resolveLibraryPath()
        if (path == null) {
            LOG.warning("[FALLBACK][render] Native SIMD render bridge library not found; falling back to Kotlin scalar ARGB_PRE surface conversion")
            return null
        }
        return runCatching {
            val arena = Arena.ofShared()
            val lookup = SymbolLookup.libraryLookup(path.toAbsolutePath().normalize(), arena)
            val linker = Linker.nativeLinker()
            fun sym(name: String): MemorySegment =
                lookup.find(name).orElseThrow { UnsatisfiedLinkError("Missing symbol $name in $path") }
            val abi = linker.downcallHandle(sym("fuji_render_abi_version"), FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact() as Int
            if (abi != EXPECTED_ABI_VERSION) {
                arena.close()
                LOG.warning("[FALLBACK][render] Native render bridge ABI mismatch at $path (got $abi, expected $EXPECTED_ABI_VERSION); falling back to Kotlin scalar")
                return null
            }
            val namePtr = linker.downcallHandle(sym("fuji_render_backend_name"), FunctionDescriptor.of(ValueLayout.ADDRESS)).invokeExact() as MemorySegment
            val name = namePtr.reinterpret(128).getString(0)
            val fn = linker.downcallHandle(
                sym("fuji_render_argb_to_pre"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
                Linker.Option.critical(true),
            )
            val compositePreFn = lookup.find("fuji_render_composite_lcdc_to_argb_pre").map { s ->
                linker.downcallHandle(
                    s,
                    FunctionDescriptor.of(
                        ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, // osd_bytes
                        ValueLayout.ADDRESS, // pic0_bytes
                        ValueLayout.ADDRESS, // pic1_bytes
                        ValueLayout.ADDRESS, // source_rows
                        ValueLayout.ADDRESS, // source_cols
                        ValueLayout.ADDRESS, // color_matrix_q10
                        ValueLayout.ADDRESS, // cfg
                        ValueLayout.ADDRESS, // output_argb_pre
                    ),
                    Linker.Option.critical(true),
                )
            }.orElse(null)
            Handles(arena, fn, compositePreFn, name)
        }.onFailure { err ->
            LOG.warning("[FALLBACK][render] Failed to initialize native render bridge at $path (${err.message}); falling back to Kotlin scalar")
        }.getOrNull()
    }

    fun argbToPreDirect(srcArgb: IntArray, dstDirectSegment: MemorySegment, count: Int): Boolean {
        val h = handles ?: return false
        h.argbToPre.invokeExact(MemorySegment.ofArray(srcArgb), dstDirectSegment, count) as Int
        return true
    }

    /**
     * Single-pass fused LCDC1 -> `INT_ARGB_PRE` native SIMD composite directly into a JavaFX
     * `PixelBuffer` direct [MemorySegment]. Returns `1` if all opaque, `0` if any translucent
     * pixel was premultiplied, or `-1` if the native kernel is unavailable.
     */
    fun compositeLcdcToArgbPre(
        osdBytes: ByteArray?,
        pic0Bytes: ByteArray?,
        pic1Bytes: ByteArray?,
        sourceRows: IntArray,
        sourceCols: IntArray,
        colorMatrixQ10: IntArray?,
        cfg: IntArray,
        dstDirectSegment: MemorySegment,
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
            dstDirectSegment,
        ) as Int
    }
}

/**
 * Exact divisionless integer `(x + 127) / 255` helper (Blinn identity),
 * bit-identical for all `x` in `0..65025`.
 */
internal fun div255Rounded(x: Int): Int {
    val t = x + 128
    return (t + (t ushr 8)) ushr 8
}

internal fun isAllOpaqueArgb(pixels: IntArray, count: Int = pixels.size): Boolean {
    var acc = -1
    var i = 0
    val limit = count - 3
    while (i < limit) {
        acc = acc and pixels[i] and pixels[i + 1] and pixels[i + 2] and pixels[i + 3]
        i += 4
    }
    while (i < count) {
        acc = acc and pixels[i]
        i++
    }
    return (acc ushr 24) == 255
}

/**
 * Converts a straight `INT_ARGB` pixel to premultiplied `INT_ARGB_PRE` without integer division.
 * Opaque (`alpha == 255`) and fully transparent (`alpha == 0`) pixels take branch-minimal fast paths.
 */
internal fun argbToArgbPre(argb: Int): Int {
    val a = argb ushr 24
    if (a == 255) return argb
    if (a == 0) return 0
    val r = div255Rounded(((argb ushr 16) and 0xff) * a)
    val g = div255Rounded(((argb ushr 8) and 0xff) * a)
    val b = div255Rounded((argb and 0xff) * a)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Lock-free, allocation-free triple-buffered latest-frame overwrite bridge between the
 * single-writer emulation Owner thread and the single-reader JavaFX UI thread.
 *
 * - Producer (Owner thread) calls [publishAll] or [publishIfChanged] to copy only changed
 *   [DisplayPort] frames into a reusable producer-owned `IntArray`, then atomically swaps it
 *   into the mailbox slot. Unconsumed intermediate frames are overwritten without backpressure.
 * - Consumer (JavaFX thread) calls [consumeLatest] to atomically acquire the newest published
 *   frame in O(1) with zero locks and zero per-frame allocations.
 * - Completely passive with respect to guest execution and snapshots (`discovery` / `strict`
 *   determinism is unaffected).
 */
class AsyncDisplayBridge(portNames: Collection<String> = DEFAULT_PORTS) {
    companion object {
        const val MAX_PIXELS: Long = 16_777_216L
        val DEFAULT_PORTS: List<String> = listOf("display.main", "display.evf", "display.shoulder")
    }

    data class PortStats(
        val publishedFrames: Long,
        val overwrittenFrames: Long,
        val consumedFrames: Long,
        val lastPublishedSequence: Long,
        val lastConsumedSequence: Long,
    )

    private class FrameSlot {
        var width: Int = 0
        var height: Int = 0
        var sequence: Long = 0L
        var generation: Long = 0L
        var pixels: IntArray = IntArray(0)
        var directBytes: ByteBuffer? = null
        var directSegment: MemorySegment? = null
        var directPixelCount: Int = 0

        fun ensureCapacity(pixelCount: Int): IntArray {
            if (pixels.size != pixelCount) {
                pixels = IntArray(pixelCount)
            }
            return pixels
        }

        fun ensureDirectSegment(pixelCount: Int): MemorySegment {
            val existing = directSegment
            if (existing != null && directPixelCount == pixelCount) {
                return existing
            }
            val buf = ByteBuffer.allocateDirect(pixelCount * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            val seg = MemorySegment.ofBuffer(buf)
            directBytes = buf
            directSegment = seg
            directPixelCount = pixelCount
            return seg
        }
    }

    private class PortSlot {
        // Producer-only fields (Owner thread)
        private var producerBuf = FrameSlot()
        private var producerGeneration = 0L
        @Volatile private var lastPortSequence = 0L
        private var lastPortWidth = 0
        private var lastPortHeight = 0

        // Wait-free handoff between Producer and Consumer
        private val mailbox = AtomicReference(FrameSlot())
        @Volatile private var latestGeneration = 0L

        // Consumer-only fields (FX thread)
        private var consumerBuf = FrameSlot()
        @Volatile private var lastConsumedGeneration = 0L
        @Volatile private var lastConsumedSequence = 0L

        // Diagnostic counters
        private val publishedCounter = AtomicLong(0L)
        private val overwrittenCounter = AtomicLong(0L)
        private val consumedCounter = AtomicLong(0L)

        fun publishIfChanged(port: DisplayPort): Boolean {
            val seq = port.sequence
            if (seq <= 0L) return false
            val w = port.width
            val h = port.height
            if (seq == lastPortSequence && w == lastPortWidth && h == lastPortHeight) return false
            require(w > 0 && h > 0 && w.toLong() * h <= MAX_PIXELS) {
                "Invalid display dimensions ${w}x${h}"
            }
            val target = producerBuf.ensureCapacity(w * h)
            if (!port.copyArgb(target)) return false
            val nextGen = ++producerGeneration
            producerBuf.width = w
            producerBuf.height = h
            producerBuf.sequence = seq
            producerBuf.generation = nextGen
            val recycled = mailbox.getAndSet(producerBuf)
            val replacedUnread = recycled.generation > lastConsumedGeneration && recycled.generation > 0L
            producerBuf = recycled
            lastPortSequence = seq
            lastPortWidth = w
            lastPortHeight = h
            latestGeneration = nextGen
            publishedCounter.incrementAndGet()
            if (replacedUnread) overwrittenCounter.incrementAndGet()
            return true
        }

        fun consumeLatest(onFrame: (width: Int, height: Int, sequence: Long, pixels: IntArray) -> Unit): Boolean {
            val availableGen = latestGeneration
            if (availableGen <= lastConsumedGeneration) return false
            val next = mailbox.getAndSet(consumerBuf)
            consumerBuf = next
            if (next.generation <= lastConsumedGeneration || next.sequence <= 0L) {
                return false
            }
            lastConsumedGeneration = next.generation
            lastConsumedSequence = next.sequence
            consumedCounter.incrementAndGet()
            onFrame(next.width, next.height, next.sequence, next.pixels)
            return true
        }

        fun publishDirectArgbPre(
            width: Int,
            height: Int,
            sequence: Long,
            writer: (MemorySegment) -> Unit,
        ): Boolean {
            if (sequence <= 0L) return false
            if (sequence == lastPortSequence && width == lastPortWidth && height == lastPortHeight) return false
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) {
                "Invalid display dimensions ${width}x${height}"
            }
            val seg = producerBuf.ensureDirectSegment(width * height)
            writer(seg)
            val nextGen = ++producerGeneration
            producerBuf.width = width
            producerBuf.height = height
            producerBuf.sequence = sequence
            producerBuf.generation = nextGen
            val recycled = mailbox.getAndSet(producerBuf)
            val replacedUnread = recycled.generation > lastConsumedGeneration && recycled.generation > 0L
            producerBuf = recycled
            lastPortSequence = sequence
            lastPortWidth = width
            lastPortHeight = height
            latestGeneration = nextGen
            publishedCounter.incrementAndGet()
            if (replacedUnread) overwrittenCounter.incrementAndGet()
            return true
        }

        fun consumeLatestDirectArgbPre(
            onFrame: (width: Int, height: Int, sequence: Long, pixelsPre: MemorySegment) -> Unit,
        ): Boolean {
            val availableGen = latestGeneration
            if (availableGen <= lastConsumedGeneration) return false
            val next = mailbox.getAndSet(consumerBuf)
            consumerBuf = next
            val seg = next.directSegment
            if (next.generation <= lastConsumedGeneration || next.sequence <= 0L || seg == null) {
                return false
            }
            lastConsumedGeneration = next.generation
            lastConsumedSequence = next.sequence
            consumedCounter.incrementAndGet()
            onFrame(next.width, next.height, next.sequence, seg)
            return true
        }

        fun resetProducerSequence() {
            lastPortSequence = 0L
            lastPortWidth = 0
            lastPortHeight = 0
        }

        fun stats(): PortStats = PortStats(
            publishedFrames = publishedCounter.get(),
            overwrittenFrames = overwrittenCounter.get(),
            consumedFrames = consumedCounter.get(),
            lastPublishedSequence = lastPortSequence,
            lastConsumedSequence = lastConsumedSequence,
        )
    }

    private val slots: Map<String, PortSlot> = portNames.associateWith { PortSlot() }
    private val slotNames: Array<String> = slots.keys.toTypedArray()
    private val slotValues: Array<PortSlot> = slots.values.toTypedArray()
    // Producer-only (Owner thread) cached DisplayPort references to avoid per-slice registry lookups.
    private var cachedPortsRef: Ports? = null
    private val cachedDisplayPorts: Array<DisplayPort?> = arrayOfNulls(slotNames.size)
    private var allPortsCached: Boolean = false

    /** Owner thread: checks all registered display ports and publishes any newly sequenced frames. */
    fun publishAll(ports: Ports): Int {
        val portsChanged = cachedPortsRef !== ports
        if (portsChanged) {
            cachedPortsRef = ports
            allPortsCached = false
        }
        if (!allPortsCached) {
            var resolved = 0
            for (i in slotNames.indices) {
                var p = if (portsChanged) null else cachedDisplayPorts[i]
                if (p == null) {
                    p = ports.find(slotNames[i], DisplayPort::class.java)
                    if (p !== cachedDisplayPorts[i]) {
                        cachedDisplayPorts[i] = p
                        slotValues[i].resetProducerSequence()
                    }
                }
                if (p != null) resolved++
            }
            allPortsCached = (resolved == slotNames.size)
        }
        var published = 0
        for (i in slotNames.indices) {
            val port = cachedDisplayPorts[i] ?: continue
            try {
                if (slotValues[i].publishIfChanged(port)) published++
            } catch (_: IllegalStateException) {
                // Component closed concurrently (e.g. cold reset / hot-reload); drop stale handle and re-resolve once.
                cachedDisplayPorts[i] = null
                allPortsCached = false
                slotValues[i].resetProducerSequence()
                val fresh = ports.find(slotNames[i], DisplayPort::class.java)
                if (fresh != null && fresh !== port) {
                    cachedDisplayPorts[i] = fresh
                    runCatching {
                        if (slotValues[i].publishIfChanged(fresh)) published++
                    }.onFailure {
                        cachedDisplayPorts[i] = null
                        slotValues[i].resetProducerSequence()
                    }
                }
            }
        }
        return published
    }

    /** Owner thread: drops cached DisplayPort handles and resets sequence tracking after a SoC cold reset. */
    fun resetForColdReset() {
        cachedPortsRef = null
        cachedDisplayPorts.fill(null)
        allPortsCached = false
        for (slot in slotValues) {
            slot.resetProducerSequence()
        }
    }

    /** Owner thread: publishes a single named port if its sequence or dimensions changed. */
    fun publishIfChanged(portName: String, port: DisplayPort): Boolean =
        slots[portName]?.publishIfChanged(port) ?: false

    /**
     * Consumer (FX thread): acquires the latest frame for [portName] if newer than the last
     * consumed generation. The `pixels` array passed to [onFrame] is exclusively owned by the
     * consumer until the next [consumeLatest] call for the same port, so in-place conversion is safe.
     */
    fun consumeLatest(
        portName: String,
        onFrame: (width: Int, height: Int, sequence: Long, pixels: IntArray) -> Unit,
    ): Boolean = slots[portName]?.consumeLatest(onFrame) ?: false

    /**
     * Producer (Owner thread): renders premultiplied `INT_ARGB_PRE` pixels directly into a
     * reusable off-heap triple-buffer [MemorySegment] via [writer] with zero intermediate heap
     * `IntArray` allocation or copy, then publishes it atomically to the UI consumer.
     */
    fun publishDirectArgbPre(
        portName: String,
        width: Int,
        height: Int,
        sequence: Long,
        writer: (MemorySegment) -> Unit,
    ): Boolean = slots[portName]?.publishDirectArgbPre(width, height, sequence, writer) ?: false

    /**
     * Consumer (FX thread): acquires the newest off-heap `INT_ARGB_PRE` [MemorySegment] published
     * via [publishDirectArgbPre] in O(1) with zero locks and zero per-frame allocations.
     */
    fun consumeLatestDirectArgbPre(
        portName: String,
        onFrame: (width: Int, height: Int, sequence: Long, pixelsPre: MemorySegment) -> Unit,
    ): Boolean = slots[portName]?.consumeLatestDirectArgbPre(onFrame) ?: false

    fun stats(portName: String): PortStats? = slots[portName]?.stats()
}

/**
 * Reusable zero-copy JavaFX [PixelBuffer]`<IntBuffer>` (`INT_ARGB_PRE`) surface backing an [ImageView].
 *
 * Allocates a direct native-order [IntBuffer] and [WritableImage] once per resolution (supporting
 * dynamic switches such as `640x480`, `1024x768`, `304x240`), eliminating per-frame `IntArray`
 * copies and Prism straight-ARGB -> premultiplied-ARGB conversions.
 */
class PixelBufferDisplaySurface @JvmOverloads constructor(
    private var imageView: ImageView,
    private val filterMinification: Boolean = false,
) {
    private var minifier = if (filterMinification) MinifiedDisplay(imageView) else null
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var allocations: Int = 0
        private set

    private var directIntBuffer: IntBuffer? = null
    private var directSegment: MemorySegment? = null
    private var pixelBuffer: PixelBuffer<IntBuffer>? = null
    private var writableImage: WritableImage? = null

    /** Current backing [WritableImage] (null until the first frame is presented). */
    val image: WritableImage? get() = writableImage

    /**
     * Rebinds this reusable zero-copy surface to a new [ImageView] (for example after a Body Theme
     * hot-switch) without reallocating the backing buffer or dropping the most recent rendered frame.
     */
    fun rebindImageView(target: ImageView) {
        if (imageView !== target) {
            minifier?.detach()
            imageView = target
            minifier = if (filterMinification) MinifiedDisplay(target) else null
        }
        writableImage?.let { target.image = it }
        pixelBuffer?.let(::present)
    }

    /**
     * Updates the backing direct [IntBuffer] from straight `INT_ARGB` pixels and notifies Prism.
     *
     * @param mutateSourceInPlace when `true`, caller grants exclusive ownership of [argbPixels]
     *   (e.g. from [AsyncDisplayBridge.consumeLatest]), allowing zero-allocation in-place
     *   premultiplication followed by a single bulk `IntBuffer.put` copy.
     */
    fun updateFromArgb(
        frameWidth: Int,
        frameHeight: Int,
        argbPixels: IntArray,
        mutateSourceInPlace: Boolean = false,
    ) {
        require(frameWidth > 0 && frameHeight > 0 && frameWidth.toLong() * frameHeight <= AsyncDisplayBridge.MAX_PIXELS) {
            "Invalid frame dimensions ${frameWidth}x${frameHeight}"
        }
        val count = frameWidth * frameHeight
        require(argbPixels.size == count) {
            "Pixel count mismatch: expected $count, got ${argbPixels.size}"
        }
        val intBuf = ensureBuffer(frameWidth, frameHeight, count)
        val pb = requireNotNull(pixelBuffer)
        val seg = directSegment

        if (seg != null && NativeDisplaySurfaceBridge.argbToPreDirect(argbPixels, seg, count)) {
            present(pb)
            return
        }

        if (isAllOpaqueArgb(argbPixels, count)) {
            intBuf.put(0, argbPixels, 0, count)
        } else if (mutateSourceInPlace) {
            for (i in 0 until count) {
                val p = argbPixels[i]
                if ((p ushr 24) != 255) {
                    argbPixels[i] = argbToArgbPre(p)
                }
            }
            intBuf.put(0, argbPixels, 0, count)
        } else {
            for (i in 0 until count) {
                intBuf.put(i, argbToArgbPre(argbPixels[i]))
            }
        }
        present(pb)
    }

    /**
     * Updates the backing direct `INT_ARGB_PRE` [PixelBuffer] directly from an already-premultiplied
     * off-heap [MemorySegment] via a single native off-heap bulk copy — zero JVM `IntArray`
     * copies and zero `argbToPre` passes.
     */
    fun updateFromDirectArgbPre(
        frameWidth: Int,
        frameHeight: Int,
        srcPreSegment: MemorySegment,
    ) {
        require(frameWidth > 0 && frameHeight > 0 && frameWidth.toLong() * frameHeight <= AsyncDisplayBridge.MAX_PIXELS) {
            "Invalid frame dimensions ${frameWidth}x${frameHeight}"
        }
        val count = frameWidth * frameHeight
        val byteCount = count.toLong() * Int.SIZE_BYTES
        require(srcPreSegment.byteSize() >= byteCount) {
            "Direct ARGB_PRE segment too small: expected >= $byteCount bytes, got ${srcPreSegment.byteSize()}"
        }
        ensureBuffer(frameWidth, frameHeight, count)
        val dstSeg = requireNotNull(directSegment)
        val pb = requireNotNull(pixelBuffer)
        MemorySegment.copy(srcPreSegment, 0L, dstSeg, 0L, byteCount)
        present(pb)
    }

    /**
     * Zero-intermediate-buffer direct render into JavaFX [PixelBuffer]'s own off-heap backing
     * [MemorySegment] (`INT_ARGB_PRE`). The [renderer] callback writes premultiplied `INT_ARGB_PRE`
     * pixels directly into the segment (e.g. via `fuji_render_composite_lcdc_to_argb_pre`),
     * followed immediately by Prism dirty notification.
     */
    fun renderDirectArgbPre(
        frameWidth: Int,
        frameHeight: Int,
        renderer: (dstPreSegment: MemorySegment) -> Unit,
    ) {
        require(frameWidth > 0 && frameHeight > 0 && frameWidth.toLong() * frameHeight <= AsyncDisplayBridge.MAX_PIXELS) {
            "Invalid frame dimensions ${frameWidth}x${frameHeight}"
        }
        val count = frameWidth * frameHeight
        ensureBuffer(frameWidth, frameHeight, count)
        val dstSeg = requireNotNull(directSegment)
        val pb = requireNotNull(pixelBuffer)
        renderer(dstSeg)
        present(pb)
    }

    private fun present(source: PixelBuffer<IntBuffer>) {
        source.updateBuffer { null }
        writableImage?.let { minifier?.present(source, it) }
    }

    private fun ensureBuffer(frameWidth: Int, frameHeight: Int, count: Int): IntBuffer {
        val existing = directIntBuffer
        if (existing != null && width == frameWidth && height == frameHeight) {
            return existing
        }
        val directBytes = ByteBuffer.allocateDirect(count * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val intBuf = directBytes.asIntBuffer()
        val pb = PixelBuffer(frameWidth, frameHeight, intBuf, PixelFormat.getIntArgbPreInstance())
        val img = WritableImage(pb)
        directIntBuffer = intBuf
        directSegment = MemorySegment.ofBuffer(directBytes)
        pixelBuffer = pb
        writableImage = img
        width = frameWidth
        height = frameHeight
        allocations++
        imageView.image = img
        return intBuf
    }
}
