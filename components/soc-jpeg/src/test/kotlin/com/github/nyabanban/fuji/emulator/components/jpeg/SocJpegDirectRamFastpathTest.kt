package com.github.nyabanban.fuji.emulator.components.jpeg

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocJpegDirectRamFastpathTest {
    private class OffHeapDirectRam(
        private val base: Long,
        private val sizeBytes: Long,
        private val allowDirect: Boolean,
    ) : DirectGuestRamPort, AutoCloseable {
        private val arena = Arena.ofConfined()
        val seg: MemorySegment = arena.allocate(sizeBytes, 64L)
        var slowReads = 0
        var slowWrites = 0
        var directSlices = 0
        val directWrites = ArrayList<Pair<Long, Long>>()

        private fun checkBounds(address: Long, size: Long) {
            require(address >= base && size >= 0L && (address - base) + size <= sizeBytes) {
                "OOB addr=0x${address.toString(16)} size=$size"
            }
        }

        fun load(address: Long, bytes: ByteArray) {
            checkBounds(address, bytes.size.toLong())
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, address - base, bytes.size)
        }

        fun dump(address: Long, size: Int): ByteArray {
            checkBounds(address, size.toLong())
            val out = ByteArray(size)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address - base, out, 0, size)
            return out
        }

        override fun check(address: Long, size: Long, write: Boolean) = checkBounds(address, size)

        override fun read(address: Long, destination: ByteBuffer) {
            val len = destination.remaining()
            checkBounds(address, len.toLong())
            slowReads++
            val tmp = ByteArray(len)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address - base, tmp, 0, len)
            destination.put(tmp)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val len = source.remaining()
            checkBounds(address, len.toLong())
            slowWrites++
            val tmp = ByteArray(len)
            source.get(tmp)
            MemorySegment.copy(tmp, 0, seg, ValueLayout.JAVA_BYTE, address - base, len)
        }

        override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
            if (!allowDirect) return null
            checkBounds(address, size)
            directSlices++
            return seg.asSlice(address - base, size)
        }

        override fun notifyDirectWrite(address: Long, size: Long) {
            directWrites.add(address to size)
        }

        override fun close() = arena.close()
    }

    private fun createCodecHarness(dma: DmaMemory): Triple<StatefulComponent, TickAwareMmioDevice, TickAwareMmioDevice> {
        var tick = 1000L
        val map = LinkedHashMap<String, Any>()
        val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                map[name] = port
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = map[name]?.let { type.cast(it) }
            override fun removeOwner(owner: String) {}
        }
        ports.register("cpu", "cpu.memory", DmaMemory::class.java, dma)
        ports.register("soc-gic", "soc-gic.lines", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {}
        })
        val ctx = object : ComponentContext {
            override val directory = Path.of("unused-jpeg-direct")
            override val clock = EmulationClock { tick }
            override val events = object : EventChannel {
                override fun publish(event: ComponentEvent) {}
                override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
            }
            override val ports = ports
            override fun dataPath(relative: String) = directory.resolve(relative)
            override fun readData(relative: String): Map<String, Any?> = error("unused")
        }
        val comp = SocJpegFactory().create(
            ctx,
            mapOf("profile" to SocJpegFactory.CODEC_PROFILE, "completionDelayTicks" to 16L),
        ) as StatefulComponent
        comp.start()
        val dec = ports.find(SocJpegFactory.DECODER_V2_PORT_ID, MmioDevice::class.java) as TickAwareMmioDevice
        val enc = ports.find(SocJpegFactory.ENCODER_PORT_ID, MmioDevice::class.java) as TickAwareMmioDevice
        return Triple(comp, dec, enc)
    }

    private fun makeUyvyPattern(w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 2)
        for (y in 0 until h) {
            val row = y * w * 2
            var x = 0
            while (x < w) {
                val p = row + x * 2
                out[p] = (96 + ((x * 3 + y) and 0x3F)).toByte()
                out[p + 1] = (40 + ((x + y * 2) and 0xBF)).toByte()
                out[p + 2] = (112 + ((x + y * 3) and 0x3F)).toByte()
                out[p + 3] = (48 + ((x * 2 + y) and 0xAF)).toByte()
                x += 2
            }
        }
        return out
    }

    @Test
    fun directRamEncodeAndDecodeMatchDmaFallbackBitExactAndBenchmark() {
        val base = 0x8000_0000L
        val width = 1280
        val height = 720
        val uyvy = makeUyvyPattern(width, height)
        val srcUyvyAddr = base + 0x0000_0000L
        val dstJpegAddr = base + 0x0040_0000L
        val dstDecAddr = base + 0x0080_0000L
        val maxJpegCap = 2L * 1024L * 1024L
        val maxDecCap = 4L * 1024L * 1024L

        fun runOnce(ram: OffHeapDirectRam): Pair<ByteArray, ByteArray> {
            ram.load(srcUyvyAddr, uyvy)
            val (comp, dec, enc) = createCodecHarness(ram)
            try {
                // Encode UYVY -> JPEG (exact 0xFFFA0000 register map)
                enc.write(0x000L, 4, 0x100L) // soft reset
                enc.write(0x040L, 4, 0L) // 4:2:2 UYVY
                enc.write(0x044L, 4, (height.toLong() shl 16) or width.toLong())
                enc.write(0x048L, 4, 0x22L) // qscale
                enc.write(0x100L, 4, srcUyvyAddr)
                enc.write(0x10CL, 4, dstJpegAddr)
                enc.write(0x110L, 4, maxJpegCap)
                enc.write(0x11CL, 4, 1L) // ENC_MODE = 1
                enc.write(0x12CL, 4, (height.toLong() shl 16) or width.toLong())
                enc.write(0x130L, 4, (width * 2).toLong())
                enc.write(0x000L, 4, 1L) // CTRL_START
                enc.beforeInstruction(2000L)
                val encBytes = enc.read(0x094L, 4).toInt()
                assertTrue(encBytes > 128, "Expected non-empty JPEG bitstream, got $encBytes")
                val jpegOut = ram.dump(dstJpegAddr, encBytes)

                // Decode JPEG -> UYVY (exact 0xFFFA1000 register map)
                dec.write(0x000L, 4, 0x100L) // soft reset
                dec.write(0x100L, 4, dstDecAddr)
                dec.write(0x104L, 4, maxDecCap)
                dec.write(0x10CL, 4, dstJpegAddr)
                dec.write(0x110L, 4, encBytes.toLong())
                dec.write(0x11CL, 4, 0L) // decode mode
                dec.write(0x000L, 4, 1L) // CTRL_START
                dec.beforeInstruction(2000L)
                val decEndPtr = dec.read(0x108L, 4)
                val decBytes = (decEndPtr - dstDecAddr).toInt()
                assertEquals(width * height * 2, decBytes)
                val decOut = ram.dump(dstDecAddr, decBytes)
                return jpegOut to decOut
            } finally {
                comp.close()
            }
        }

        OffHeapDirectRam(base, 16L * 1024L * 1024L, allowDirect = false).use { slowRam ->
            OffHeapDirectRam(base, 16L * 1024L * 1024L, allowDirect = true).use { directRam ->
                val (slowJpeg, slowDec) = runOnce(slowRam)
                val (directJpeg, directDec) = runOnce(directRam)
                assertContentEquals(slowJpeg, directJpeg, "Direct RAM JPEG encode must match DmaMemory fallback bit-exact")
                assertContentEquals(slowDec, directDec, "Direct RAM JPEG decode must match DmaMemory fallback bit-exact")

                if (NativeCodecBridge.hasJpeg) {
                    assertEquals(0, directRam.slowReads, "Direct native JPEG path must execute zero slow DMA reads")
                    assertEquals(0, directRam.slowWrites, "Direct native JPEG path must execute zero slow DMA writes")
                    assertTrue(
                        directRam.directWrites.any { it.first == dstJpegAddr && it.second == directJpeg.size.toLong() },
                        "JPEG encode must notifyDirectWrite for encoded destination range",
                    )
                    assertTrue(
                        directRam.directWrites.any { it.first == dstDecAddr && it.second == directDec.size.toLong() },
                        "JPEG decode must notifyDirectWrite for decoded UYVY range",
                    )

                    val iters = 10
                    val tSlow0 = System.nanoTime()
                    repeat(iters) { runOnce(slowRam) }
                    val slowMs = (System.nanoTime() - tSlow0) / 1e6 / iters

                    val tDir0 = System.nanoTime()
                    repeat(iters) { runOnce(directRam) }
                    val dirMs = (System.nanoTime() - tDir0) / 1e6 / iters
                    println(
                        "[W12-JPEG-BENCH] ${width}x$height UYVY->JPEG->UYVY: " +
                            "DmaMemory=${"%.2f".format(slowMs)} ms/op -> DirectGuestRamPort=${"%.2f".format(dirMs)} ms/op " +
                            "(slowReads=${directRam.slowReads}, slowWrites=${directRam.slowWrites})",
                    )
                }
            }
        }
    }
}
