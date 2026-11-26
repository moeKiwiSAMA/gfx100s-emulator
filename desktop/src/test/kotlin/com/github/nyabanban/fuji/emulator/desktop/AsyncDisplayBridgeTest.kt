package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.Ports
import com.github.nyabanban.fuji.emulator.desktop.panels.AsyncDisplayBridge
import com.github.nyabanban.fuji.emulator.desktop.panels.DiagnosticsRefreshController
import com.github.nyabanban.fuji.emulator.desktop.panels.NativeDisplaySurfaceBridge
import com.github.nyabanban.fuji.emulator.desktop.panels.PixelBufferDisplaySurface
import com.github.nyabanban.fuji.emulator.desktop.panels.argbToArgbPre
import com.github.nyabanban.fuji.emulator.desktop.panels.div255Rounded
import com.github.nyabanban.fuji.emulator.desktop.panels.isAllOpaqueArgb
import com.github.nyabanban.fuji.emulator.desktop.panels.reduceArgbPre
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import javafx.application.Platform
import javafx.scene.image.ImageView
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class AsyncDisplayBridgeTest {
    @Test fun diagnosticsControllerPrunesHiddenTabComponentsFromOwnerPolling() {
        val base = setOf("buttons", "soc-gpio", "mount", "cmos")
        val idle = DiagnosticsRefreshController.selectUiStateComponentIds(
            baseIds = base,
            includeVglib = false,
            includeLens = false,
            includeOrientationIbis = false,
        )
        assertSame(base, idle, "Hidden tabs must return exact precomputed base set without allocating")
        assertFalse("soc-spi" in idle)
        assertFalse("mma8452q" in idle)
        assertFalse("lens" in idle)
        assertFalse("soc-vglib-init" in idle)

        val floatingLensOnly = DiagnosticsRefreshController.selectUiStateComponentIds(
            baseIds = base,
            includeVglib = false,
            includeLens = true,
            includeOrientationIbis = false,
        )
        assertTrue("lens" in floatingLensOnly)
        assertFalse("soc-spi" in floatingLensOnly)
        assertFalse("mma8452q" in floatingLensOnly)

        val lensTabOpen = DiagnosticsRefreshController.selectUiStateComponentIds(
            baseIds = base,
            includeVglib = true,
            includeLens = true,
            includeOrientationIbis = true,
        )
        assertTrue("soc-vglib-init" in lensTabOpen)
        assertTrue("lens" in lensTabOpen)
        assertTrue("soc-spi" in lensTabOpen)
        assertTrue("mma8452q" in lensTabOpen)
    }

    @Test fun div255RoundedAndArgbPreMatchExactArithmetic() {
        for (x in 0..65025) {
            assertEquals((x + 127) / 255, div255Rounded(x), "Mismatch at x=$x")
        }
        assertEquals(0xFF123456.toInt(), argbToArgbPre(0xFF123456.toInt()))
        assertEquals(0x00000000, argbToArgbPre(0x00AABBCC))
        // alpha=128 (0x80), R=255, G=128, B=64
        val expectedR = (255 * 128 + 127) / 255
        val expectedG = (128 * 128 + 127) / 255
        val expectedB = (64 * 128 + 127) / 255
        assertEquals(
            (0x80 shl 24) or (expectedR shl 16) or (expectedG shl 8) or expectedB,
            argbToArgbPre(0x80FF8040.toInt()),
        )
    }

    @Test fun isAllOpaqueArgbHandlesUnrolledAndTailLengthsAccurately() {
        for (len in listOf(0, 1, 2, 3, 4, 5, 7, 8, 15, 16, 1024 * 768)) {
            val arr = IntArray(len) { 0xFF123456.toInt() xor (it and 0x00FFFFFF) }
            assertTrue(isAllOpaqueArgb(arr), "Expected opaque for len=$len")
            if (len > 0) {
                arr[0] = 0xFE123456.toInt()
                assertFalse(isAllOpaqueArgb(arr), "Expected non-opaque at 0 for len=$len")
                arr[0] = 0xFF123456.toInt()
                arr[len - 1] = 0x00123456
                assertFalse(isAllOpaqueArgb(arr), "Expected non-opaque at tail for len=$len")
            }
        }
    }

    @Test fun publishAllCachesResolvedPortsAndRebindsOnPortsChange() {
        val bridge = AsyncDisplayBridge(listOf("display.main", "display.evf"))
        val mainPort = ArgbFrameBuffer(4, 4)
        var evfPort: DisplayPort? = null
        var findCalls = 0
        fun makePorts(main: DisplayPort?, evfProvider: () -> DisplayPort?): Ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) = error("unused")
            override fun removeOwner(owner: String) = error("unused")
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> find(name: String, type: Class<T>): T? {
                findCalls++
                return when (name) {
                    "display.main" -> main as? T
                    "display.evf" -> evfProvider() as? T
                    else -> null
                }
            }
        }
        val ports1 = makePorts(mainPort) { evfPort }
        mainPort.presentArgb(IntArray(16) { 0xFF111111.toInt() }, 4, 4)
        assertEquals(1, bridge.publishAll(ports1))
        assertEquals(2, findCalls) // resolved main + missed evf

        // Subsequent calls on same Ports reuse cached mainPort (only missing evf is re-checked)
        val afterFirst = findCalls
        repeat(5) { assertEquals(0, bridge.publishAll(ports1)) }
        assertEquals(afterFirst + 5, findCalls)

        // Once evf registers, both slots are cached -> zero further Ports.find calls!
        val evf = ArgbFrameBuffer(4, 4)
        evfPort = evf
        evf.presentArgb(IntArray(16) { 0xFF222222.toInt() }, 4, 4)
        assertEquals(1, bridge.publishAll(ports1))
        val afterEvfCached = findCalls
        repeat(10) { assertEquals(0, bridge.publishAll(ports1)) }
        assertEquals(afterEvfCached, findCalls, "Expected zero Ports.find calls once all display ports are cached")

        // Switching Ports instance invalidates cache cleanly
        val main2 = ArgbFrameBuffer(4, 4)
        main2.presentArgb(IntArray(16) { 0xFF333333.toInt() }, 4, 4)
        val ports2 = makePorts(main2) { evf }
        // Different sequence/port still works; verify cache re-bound
        main2.presentArgb(IntArray(16) { 0xFF444444.toInt() }, 4, 4)
        assertEquals(1, bridge.publishAll(ports2))
        assertEquals(afterEvfCached + 2, findCalls)
    }

    @Test fun tripleBufferOverwritesUnreadFramesAndReusesArraysAcrossSameResolution() {
        val bridge = AsyncDisplayBridge(listOf("display.main"))
        val port = ArgbFrameBuffer(8, 6)
        assertFalse(bridge.publishIfChanged("display.main", port))
        assertFalse(bridge.consumeLatest("display.main") { _, _, _, _ -> fail("No frame published yet") })

        // Publish 3 frames before consumer polls -> intermediate frames overwritten cleanly
        val frame1 = IntArray(48) { 0xFF000001.toInt() + it }
        val frame2 = IntArray(48) { 0xFF000100.toInt() + it }
        val frame3 = IntArray(48) { 0xFF010000.toInt() + it }
        port.presentArgb(frame1, 8, 6)
        assertTrue(bridge.publishIfChanged("display.main", port))
        // Duplicate publish at same sequence must be a zero-copy no-op
        assertFalse(bridge.publishIfChanged("display.main", port))

        port.presentArgb(frame2, 8, 6)
        assertTrue(bridge.publishIfChanged("display.main", port))
        port.presentArgb(frame3, 8, 6)
        assertTrue(bridge.publishIfChanged("display.main", port))

        var consumedSeq = 0L
        var consumedArrayRef1: IntArray? = null
        assertTrue(
            bridge.consumeLatest("display.main") { w, h, seq, px ->
                assertEquals(8, w)
                assertEquals(6, h)
                consumedSeq = seq
                consumedArrayRef1 = px
                assertContentEquals(frame3, px)
            },
        )
        assertEquals(3L, consumedSeq)
        // Second consume without new publish must return false
        assertFalse(bridge.consumeLatest("display.main") { _, _, _, _ -> fail("No newer frame") })

        val stats = requireNotNull(bridge.stats("display.main"))
        assertEquals(3L, stats.publishedFrames)
        assertEquals(2L, stats.overwrittenFrames)
        assertEquals(1L, stats.consumedFrames)
        assertEquals(3L, stats.lastPublishedSequence)
        assertEquals(3L, stats.lastConsumedSequence)

        // Publish more frames and verify array reuse (triple buffer reuses the same 3 IntArray instances)
        val seenRefs = HashSet<Int>()
        seenRefs += System.identityHashCode(consumedArrayRef1)
        repeat(10) { step ->
            val next = IntArray(48) { 0xFF100000.toInt() + step * 100 + it }
            port.presentArgb(next, 8, 6)
            assertTrue(bridge.publishIfChanged("display.main", port))
            assertTrue(
                bridge.consumeLatest("display.main") { _, _, seq, px ->
                    assertEquals(4L + step, seq)
                    assertContentEquals(next, px)
                    seenRefs += System.identityHashCode(px)
                },
            )
        }
        assertTrue(seenRefs.size <= 3, "Expected at most 3 reused triple-buffer arrays, saw ${seenRefs.size}")
    }

    @Test fun bridgeHandlesDynamicResolutionSwitchesCleanly() {
        val bridge = AsyncDisplayBridge(listOf("display.main"))
        val p640 = ArgbFrameBuffer(64, 48)
        val p1024 = ArgbFrameBuffer(128, 96)
        val p304 = ArgbFrameBuffer(38, 30)

        p640.presentArgb(IntArray(64 * 48) { 0xFF112233.toInt() }, 64, 48)
        assertTrue(bridge.publishIfChanged("display.main", p640))
        assertTrue(
            bridge.consumeLatest("display.main") { w, h, seq, px ->
                assertEquals(64 to 48, w to h)
                assertEquals(1L, seq)
                assertEquals(0xFF112233.toInt(), px[0])
            },
        )

        // Even with identical sequence=1 on a different resolution DisplayPort, dimension change publishes!
        p1024.presentArgb(IntArray(128 * 96) { 0xFF445566.toInt() }, 128, 96)
        assertTrue(bridge.publishIfChanged("display.main", p1024))
        assertTrue(
            bridge.consumeLatest("display.main") { w, h, seq, px ->
                assertEquals(128 to 96, w to h)
                assertEquals(1L, seq)
                assertEquals(0xFF445566.toInt(), px.last())
            },
        )

        p304.presentArgb(IntArray(38 * 30) { 0xFF778899.toInt() }, 38, 30)
        assertTrue(bridge.publishIfChanged("display.main", p304))
        assertTrue(
            bridge.consumeLatest("display.main") { w, h, seq, px ->
                assertEquals(38 to 30, w to h)
                assertEquals(1L, seq)
                assertEquals(0xFF778899.toInt(), px[0])
            },
        )
    }

    @Test fun concurrentProducerConsumerHasZeroTearingAndMonotonicSequences() {
        val bridge = AsyncDisplayBridge(listOf("display.main"))
        val port = ArgbFrameBuffer(32, 24)
        val done = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val lastSeq = AtomicLong(0L)
        val errors = mutableListOf<Throwable>()

        val consumer = Thread {
            started.countDown()
            while (!done.get()) {
                try {
                    bridge.consumeLatest("display.main") { w, h, seq, px ->
                        assertEquals(32, w)
                        assertEquals(24, h)
                        val prev = lastSeq.getAndSet(seq)
                        assertTrue(seq > prev, "Non-monotonic seq $seq <= $prev")
                        val expectedColor = (0xFF shl 24) or (seq.toInt() and 0x00FFFFFF)
                        // Every pixel in the frame must belong to the exact same sequence (zero tearing!)
                        for (i in px.indices) {
                            assertEquals(expectedColor, px[i], "Torn pixel at $i for seq=$seq")
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                    break
                }
            }
        }
        consumer.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val scratch = IntArray(32 * 24)
        for (seq in 1..2000) {
            scratch.fill((0xFF shl 24) or (seq and 0x00FFFFFF))
            port.presentArgb(scratch, 32, 24)
            bridge.publishIfChanged("display.main", port)
        }
        done.set(true)
        consumer.join(5000)
        assertTrue(errors.isEmpty(), "Concurrent errors: $errors")
        // Final drain guarantees the newest frame (seq=2000) is visible
        bridge.consumeLatest("display.main") { _, _, seq, px ->
            lastSeq.set(seq)
            assertEquals((0xFF shl 24) or 2000, px[0])
        }
        assertEquals(2000L, lastSeq.get())
    }

    @Test fun directArgbPreTripleBufferIsWaitFreeTearFreeAndZeroAllocationInSteadyState() {
        val bridge = AsyncDisplayBridge(listOf("display.main", "display.evf"))
        val w = 64
        val h = 48
        val count = w * h
        val done = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val errors = mutableListOf<Throwable>()
        val lastSeq = AtomicLong(0L)

        val consumer = Thread {
            started.countDown()
            while (!done.get()) {
                try {
                    bridge.consumeLatestDirectArgbPre("display.evf") { fw, fh, seq, preSeg ->
                        assertEquals(w, fw)
                        assertEquals(h, fh)
                        val prev = lastSeq.getAndSet(seq)
                        assertTrue(seq > prev, "Non-monotonic direct seq $seq <= $prev")
                        val expectedColor = (0xFF shl 24) or (seq.toInt() and 0x00FFFFFF)
                        val ib = preSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
                        for (i in 0 until count) {
                            assertEquals(expectedColor, ib.get(i), "Torn direct pixel at $i for seq=$seq")
                        }
                    }
                } catch (t: Throwable) {
                    synchronized(errors) { errors += t }
                    break
                }
            }
        }
        consumer.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        for (seq in 1..1500) {
            val color = (0xFF shl 24) or (seq and 0x00FFFFFF)
            assertTrue(
                bridge.publishDirectArgbPre("display.evf", w, h, seq.toLong()) { dstSeg ->
                    val ib = dstSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
                    for (i in 0 until count) ib.put(i, color)
                },
            )
        }
        done.set(true)
        consumer.join(5000)
        assertTrue(errors.isEmpty(), "Concurrent direct errors: $errors")
        bridge.consumeLatestDirectArgbPre("display.evf") { _, _, seq, preSeg ->
            lastSeq.set(seq)
            assertEquals((0xFF shl 24) or 1500, preSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer().get(0))
        }
        assertEquals(1500L, lastSeq.get())
    }

    @Test fun nativeDisplaySurfaceBridgeMatchesKotlinArgbToPreBitForBit() {
        val requireNative = System.getenv("FUJI_REQUIRE_RENDER_NATIVE") == "1"
        if (!requireNative) {
            assumeTrue(
                NativeDisplaySurfaceBridge.isAvailable,
                "Native render bridge not present in this environment; pure-Kotlin fallback active",
            )
        }
        assertTrue(NativeDisplaySurfaceBridge.isAvailable, "Expected NativeDisplaySurfaceBridge to load libfuji_render")
        val count = 1280 * 960
        val directBytes = ByteBuffer.allocateDirect(count * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val intBuf = directBytes.asIntBuffer()
        val seg = MemorySegment.ofBuffer(directBytes)

        // 1. All-opaque EVF frame fastpath
        val opaque = IntArray(count) { i -> 0xFF000000.toInt() or ((i * 7) and 0x00FFFFFF) }
        assertTrue(NativeDisplaySurfaceBridge.argbToPreDirect(opaque, seg, count))
        val outOpaque = IntArray(count)
        intBuf.get(0, outOpaque)
        assertContentEquals(opaque, outOpaque, "Native opaque ARGB direct upload mismatch")

        // 2. Mixed opaque + translucent + transparent frame (non-multiple-of-8 tail included)
        val mixedCount = 128 * 32 + 5
        val mixedBytes = ByteBuffer.allocateDirect(mixedCount * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val mixedIntBuf = mixedBytes.asIntBuffer()
        val mixedSeg = MemorySegment.ofBuffer(mixedBytes)
        val mixed = IntArray(mixedCount) { i ->
            val a = when {
                i % 5 == 0 -> 0xFF
                i % 5 == 1 -> 0x00
                else -> (i * 19) and 0xFF
            }
            (a shl 24) or ((i * 13) and 0x00FFFFFF)
        }
        assertTrue(NativeDisplaySurfaceBridge.argbToPreDirect(mixed, mixedSeg, mixedCount))
        val outMixed = IntArray(mixedCount)
        mixedIntBuf.get(0, outMixed)
        for (i in 0 until mixedCount) {
            assertEquals(argbToArgbPre(mixed[i]), outMixed[i], "Native ARGB_PRE mismatch at pixel $i")
        }

        // Benchmark 1280x960 opaque upload: Kotlin (isAllOpaqueArgb + put) vs Native single-pass AVX2
        repeat(5) {
            if (isAllOpaqueArgb(opaque, count)) intBuf.put(0, opaque, 0, count)
            NativeDisplaySurfaceBridge.argbToPreDirect(opaque, seg, count)
        }
        val kotlinMedMs = List(15) {
            val s = System.nanoTime()
            if (isAllOpaqueArgb(opaque, count)) intBuf.put(0, opaque, 0, count)
            System.nanoTime() - s
        }.sorted()[7] / 1e6
        val nativeMedMs = List(15) {
            val s = System.nanoTime()
            NativeDisplaySurfaceBridge.argbToPreDirect(opaque, seg, count)
            System.nanoTime() - s
        }.sorted()[7] / 1e6
        println(
            "PERF2_SURFACE_UPLOAD_AB backend=${NativeDisplaySurfaceBridge.backendName} " +
                "kotlinMedianMs=${"%.3f".format(kotlinMedMs)} nativeMedianMs=${"%.3f".format(nativeMedMs)} " +
                "speedup=${"%.2fx".format(kotlinMedMs / nativeMedMs)}",
        )
    }

    @Test fun minificationAveragesEveryPremultipliedChannelWithoutBleedingAcrossBytes() {
        val width = 24
        val height = 12
        val random = kotlin.random.Random(107)
        val pixels = IntArray(width * height * 4) { argbToArgbPre(random.nextInt()) }
        val source = IntBuffer.wrap(pixels)
        val target = IntBuffer.allocate(width * height)
        reduceArgbPre(source, target, width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width * 2 + x) * 2
                val indices = intArrayOf(i, i + 1, i + width * 2, i + width * 2 + 1)
                var expected = 0
                for (shift in listOf(0, 8, 16, 24)) {
                    val sum = indices.sumOf { (pixels[it] ushr shift) and 255 }
                    expected = expected or (((sum + 2) / 4) shl shift)
                }
                assertEquals(expected, target.get(y * width + x))
            }
        }
    }

    @Test fun reducedLcdRebindsAndResizesWithoutWaitingForAnotherFrame() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        fun <T> onFx(operation: () -> T): T {
            val task = FutureTask(operation)
            try {
                Platform.startup(task)
            } catch (_: IllegalStateException) {
                Platform.runLater(task)
            }
            return task.get(30, TimeUnit.SECONDS)
        }

        val (surface, originalView) = onFx {
            val view = ImageView().apply { fitWidth = 2.0; fitHeight = 2.0 }
            val display = PixelBufferDisplaySurface(view, filterMinification = true)
            val checker = IntArray(8 * 8) { i -> if ((i / 8 + i) % 2 == 0) -1 else 0xff000000.toInt() }
            display.updateFromArgb(8, 8, checker)
            assertEquals(2.0, view.image.width)
            assertEquals(0xff808080.toInt(), view.image.pixelReader.getArgb(0, 0))
            assertEquals(-1, requireNotNull(display.image).pixelReader.getArgb(0, 0))
            view.rotate = 90.0
            view.fitWidth = 8.0
            view.fitHeight = 8.0
            display to view
        }
        onFx {
            assertSame(surface.image, originalView.image)
            val next = ImageView().apply { fitWidth = 4.0; fitHeight = 4.0 }
            surface.rebindImageView(next)
            assertEquals(4.0, next.image.width)
            assertEquals(0xff808080.toInt(), next.image.pixelReader.getArgb(3, 3))
            val intrinsicView = ImageView().apply { scaleX = 0.25; scaleY = 0.25 }
            surface.rebindImageView(intrinsicView)
            assertSame(surface.image, intrinsicView.image)
            next.viewport = javafx.geometry.Rectangle2D(2.0, 2.0, 4.0, 4.0)
            surface.rebindImageView(next)
            assertSame(surface.image, next.image)
            next.viewport = null
            surface.updateFromArgb(6, 4, IntArray(24) { -1 })
            assertSame(surface.image, next.image)
        }
    }

    @Test fun pixelBufferSurfaceSupportsDynamicResolutionsAndExactArgbPrePixels() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a graphical JavaFX session")
        val task = FutureTask {
            val view = ImageView()
            val surface = PixelBufferDisplaySurface(view)

            // 1. 640x480 opaque frame (zero per-frame reallocation)
            val f640 = IntArray(640 * 480) { 0xFF204060.toInt() }
            surface.updateFromArgb(640, 480, f640, mutateSourceInPlace = false)
            assertEquals(1, surface.allocations)
            assertEquals(640 to 480, surface.width to surface.height)
            val img1 = requireNotNull(surface.image)
            assertSame(img1, view.image)
            assertEquals(0xFF204060.toInt(), img1.pixelReader.getArgb(0, 0))
            assertEquals(0xFF204060.toInt(), img1.pixelReader.getArgb(639, 479))

            // Second frame at same resolution reuses the exact same WritableImage / PixelBuffer!
            val f640b = IntArray(640 * 480) { 0xFFABCDEF.toInt() }
            surface.updateFromArgb(640, 480, f640b, mutateSourceInPlace = true)
            assertEquals(1, surface.allocations)
            assertSame(img1, view.image)
            assertEquals(0xFFABCDEF.toInt(), img1.pixelReader.getArgb(100, 100))

            // 2. Dynamic switch to native 1024x768 with translucent pixel verification
            val f1024 = IntArray(1024 * 768) { 0xFF102030.toInt() }
            f1024[0] = 0x80FF8040.toInt() // semi-transparent
            f1024[1] = 0x00123456         // fully transparent
            val originalCopy = f1024.copyOf()
            surface.updateFromArgb(1024, 768, f1024, mutateSourceInPlace = false)
            // Caller's array must NOT be mutated when mutateSourceInPlace=false
            assertContentEquals(originalCopy, f1024)
            assertEquals(2, surface.allocations)
            val img2 = requireNotNull(surface.image)
            assertNotSame(img1, img2)
            assertEquals(1024, img2.width.toInt())
            assertEquals(768, img2.height.toInt())
            // pixelReader.getArgb un-premultiplies INT_ARGB_PRE back to straight ARGB (within +/-1 rounding)
            val read0 = img2.pixelReader.getArgb(0, 0)
            assertEquals(0x80, read0 ushr 24)
            assertTrue(kotlin.math.abs(((read0 ushr 16) and 0xFF) - 0xFF) <= 1)
            assertTrue(kotlin.math.abs(((read0 ushr 8) and 0xFF) - 0x80) <= 2)
            assertTrue(kotlin.math.abs((read0 and 0xFF) - 0x40) <= 2)
            assertEquals(0, img2.pixelReader.getArgb(1, 0) ushr 24)
            assertEquals(0xFF102030.toInt(), img2.pixelReader.getArgb(1023, 767))

            // 3. Dynamic switch to 304x240 (shoulder display)
            val f304 = IntArray(304 * 240) { 0xFFE0E0E0.toInt() }
            surface.updateFromArgb(304, 240, f304, mutateSourceInPlace = true)
            assertEquals(3, surface.allocations)
            assertEquals(304 to 240, surface.width to surface.height)
            assertEquals(0xFFE0E0E0.toInt(), requireNotNull(surface.image).pixelReader.getArgb(303, 239))

            // 4. Direct off-heap INT_ARGB_PRE updateFromDirectArgbPre + renderDirectArgbPre
            val directBridge = AsyncDisplayBridge(listOf("display.main", "display.evf"))
            val directSrcBytes = ByteBuffer.allocateDirect(1024 * 768 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            val directSrcInt = directSrcBytes.asIntBuffer()
            val directSrcSeg = MemorySegment.ofBuffer(directSrcBytes)
            for (i in 0 until 1024 * 768) {
                directSrcInt.put(i, 0xFF336699.toInt())
            }
            assertTrue(
                directBridge.publishDirectArgbPre("display.main", 1024, 768, 1L) { dstSeg ->
                    MemorySegment.copy(directSrcSeg, 0L, dstSeg, 0L, 1024L * 768L * Int.SIZE_BYTES)
                },
            )
            var consumedSeq = 0L
            assertTrue(
                directBridge.consumeLatestDirectArgbPre("display.main") { w, h, seq, preSeg ->
                    consumedSeq = seq
                    surface.updateFromDirectArgbPre(w, h, preSeg)
                },
            )
            assertEquals(1L, consumedSeq)
            assertEquals(4, surface.allocations)
            assertEquals(1024 to 768, surface.width to surface.height)
            assertEquals(0xFF336699.toInt(), requireNotNull(surface.image).pixelReader.getArgb(512, 384))

            // Second frame at same 1024x768 via renderDirectArgbPre reuses the exact PixelBuffer (allocations unchanged!)
            surface.renderDirectArgbPre(1024, 768) { dstSeg ->
                val buf = dstSeg.asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer()
                buf.put(0, 0xFF112233.toInt())
                buf.put(1024 * 768 - 1, 0xFF445566.toInt())
            }
            assertEquals(4, surface.allocations)
            assertEquals(0xFF112233.toInt(), requireNotNull(surface.image).pixelReader.getArgb(0, 0))
            assertEquals(0xFF445566.toInt(), requireNotNull(surface.image).pixelReader.getArgb(1023, 767))

            // Native single-pass compositeLcdcToArgbPre directly into 1600x1200 EVF PixelBuffer!
            if (NativeDisplaySurfaceBridge.hasDirectCompositeArgbPre) {
                val evfW = 1600
                val evfH = 1200
                val cfg = IntArray(20).apply {
                    this[14] = 0xFF212320.toInt() // opaque backdrop
                    this[15] = evfW
                    this[16] = evfH
                    this[17] = evfW
                    this[18] = evfH
                    this[19] = 0
                }
                val empty = IntArray(0)
                var opaqueFlag = -1
                surface.renderDirectArgbPre(evfW, evfH) { dstSeg ->
                    opaqueFlag = NativeDisplaySurfaceBridge.compositeLcdcToArgbPre(
                        osdBytes = null, pic0Bytes = null, pic1Bytes = null,
                        sourceRows = empty, sourceCols = empty, colorMatrixQ10 = null,
                        cfg = cfg, dstDirectSegment = dstSeg,
                    )
                }
                assertEquals(1, opaqueFlag)
                assertEquals(5, surface.allocations)
                assertEquals(evfW to evfH, surface.width to surface.height)
                assertEquals(0xFF212320.toInt(), requireNotNull(surface.image).pixelReader.getArgb(0, 0))
                assertEquals(0xFF212320.toInt(), requireNotNull(surface.image).pixelReader.getArgb(evfW - 1, evfH - 1))
            }
        }
        try {
            Platform.startup(task)
        } catch (_: IllegalStateException) {
            Platform.runLater(task)
        }
        task.get(30, TimeUnit.SECONDS)
    }
}
