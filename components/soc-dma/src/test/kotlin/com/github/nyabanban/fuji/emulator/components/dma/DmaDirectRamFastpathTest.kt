package com.github.nyabanban.fuji.emulator.components.dma

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.test.*

/**
 * Verifies W11-DMA-DIRECT-RAM:
 * 1. `auditTransfers=false` skips SHA-256 & full readback while preserving 100% authentic DMA data,
 *    timing, register updates, IRQ signaling, and snapshot round-trip compatibility.
 * 2. Decoupled [DirectGuestRamPort] zero-copy `MemorySegment.copy` path for bulk RAM-to-RAM,
 *    fixed-pattern fills, overlapping copies, and 8,760-descriptor HDMAC LLI scatter-gather chains
 *    (with coalesced JIT cache invalidation notifications).
 * 3. Bit-exact parity between strict audited path (`auditTransfers=true`, slow chunked `DmaMemory`)
 *    and zero-copy fastpath (`auditTransfers=false`, `DirectGuestRamPort`), plus A/B throughput benchmark.
 */
class DmaDirectRamFastpathTest {
    @TempDir
    lateinit var temporary: Path

    private class Registry : Ports {
        private data class Entry(val type: Class<*>, val port: Any)
        private val data = mutableMapOf<String, Entry>()
        override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
            require(type.isInstance(port) && name !in data)
            data[name] = Entry(type, port)
        }
        override fun <T : Any> find(name: String, type: Class<T>): T? {
            val e = data[name] ?: return null
            require(e.type == type)
            return type.cast(e.port)
        }
        override fun removeOwner(owner: String) {}
    }

    private class TestContext(override val directory: Path) : ComponentContext {
        var tick = 0L
        override val clock = EmulationClock { tick }
        override val ports = Registry()
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun readData(relative: String): Map<String, Any?> = error("No data file required")
        override fun dataPath(relative: String) = directory.resolve(relative)
    }

    private class OffHeapDirectRam(
        val base: Long = 0x0200_0000L,
        val sizeBytes: Long,
        private val exposeDirect: Boolean = true,
    ) : DirectGuestRamPort, AutoCloseable {
        private val arena = Arena.ofConfined()
        val seg: MemorySegment = arena.allocate(sizeBytes, 4096L)
        private val leInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN)

        var slowReads = 0L
        var slowWrites = 0L
        var directSlices = 0L
        var invalidateCalls = 0L
        var invalidatedBytes = 0L
        val invalidatedRanges = mutableListOf<Pair<Long, Long>>()

        fun putU32(addr: Long, value: Long) {
            seg.set(leInt, addr - base, value.toInt())
        }

        fun toByteArray(addr: Long, len: Int): ByteArray {
            val out = ByteArray(len)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, addr - base, out, 0, len)
            return out
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= base && size >= 0L && address - base <= sizeBytes && size <= sizeBytes - (address - base)) {
                "Direct RAM bounds rejected addr=0x${address.toString(16)} size=$size"
            }
        }

        override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
            check(address, size, write)
            if (!exposeDirect) return null
            directSlices++
            return seg.asSlice(address - base, size)
        }

        override fun notifyDirectWrite(address: Long, size: Long) {
            invalidateCalls++
            invalidatedBytes += size
            invalidatedRanges += (address to size)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            check(address, n.toLong(), false)
            slowReads++
            if (destination.hasArray()) {
                MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address - base, destination.array(), destination.arrayOffset() + destination.position(), n)
                destination.position(destination.position() + n)
            } else {
                destination.put(seg.asSlice(address - base, n.toLong()).asByteBuffer())
            }
        }

        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining()
            check(address, n.toLong(), true)
            slowWrites++
            if (source.hasArray()) {
                MemorySegment.copy(source.array(), source.arrayOffset() + source.position(), seg, ValueLayout.JAVA_BYTE, address - base, n)
                source.position(source.position() + n)
            } else {
                seg.asSlice(address - base, n.toLong()).asByteBuffer().put(source)
            }
        }

        override fun close() {
            arena.close()
        }
    }

    private data class Harness(
        val ctx: TestContext,
        val ram: OffHeapDirectRam,
        val component: StatefulComponent,
        val dma: ScheduledMmioDevice,
        val mux: MmioDevice,
        val irqs: MutableMap<Int, Boolean>,
    )

    private fun createHarness(ram: OffHeapDirectRam, auditTransfers: Boolean): Harness {
        val ctx = TestContext(temporary)
        val irqs = mutableMapOf<Int, Boolean>()
        ctx.ports.register("test", "cpu.memory", DmaMemory::class.java, ram)
        ctx.ports.register("test", "test.irq", InterruptSink::class.java, object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {
                irqs[irq] = high
            }
        })
        val factory = DmaFactory()
        val cfg = factory.descriptor.settings.associate { it.key to it.default } + mapOf(
            "interruptPort" to "test.irq",
            "softwareProfile" to "software-request44-experimental-v1",
            "auditTransfers" to auditTransfers,
        )
        val comp = factory.create(ctx, cfg) as StatefulComponent
        comp.start()
        val dma = ctx.ports.find("soc-dma.registers", MmioDevice::class.java) as ScheduledMmioDevice
        val mux = checkNotNull(ctx.ports.find("soc-dma.mux", MmioDevice::class.java))
        return Harness(ctx, ram, comp, dma, mux, irqs)
    }

    private fun writeLliDesc(ram: OffHeapDirectRam, descAddr: Long, countBytes: Int, src: Long, dst: Long, srcGeom: Long, dstGeom: Long, nextWord: Long) {
        ram.putU32(descAddr + 0x00L, (countBytes - 1).toLong())
        ram.putU32(descAddr + 0x04L, src)
        ram.putU32(descAddr + 0x08L, dst)
        ram.putU32(descAddr + 0x0CL, srcGeom)
        ram.putU32(descAddr + 0x10L, dstGeom)
        ram.putU32(descAddr + 0x14L, nextWord)
    }

    private fun runRamToRam(h: Harness, src: Long, dst: Long, size: Int, fixed: Boolean = false, widthLog2: Int = 2) {
        h.dma.write(0x1010L, 4, (size - 1).toLong())
        h.dma.write(0x1014L, 4, src)
        h.dma.write(0x1018L, 4, dst)
        val srcGeom = (widthLog2.toLong() shl 16) or (if (fixed) 4L else 0L)
        h.dma.write(0x101CL, 4, srcGeom)
        h.dma.write(0x1020L, 4, widthLog2.toLong() shl 16)
        h.dma.write(0x1024L, 4, 0L)
        h.ctx.tick += 10L
        h.dma.write(0x1028L, 4, 0x1000_0001L)
        val due = assertNotNull(h.dma.nextDeadline())
        h.ctx.tick = due
        h.dma.beforeInstruction(due)
        // Ack completion IRQ
        h.dma.write(0x1030L, 4, 1L)
    }

    @Test
    fun auditedVsUnauditedDirectRamParityAndSnapshotRoundTrip() {
        val ramSize = 4L * 1024L * 1024L
        OffHeapDirectRam(sizeBytes = ramSize, exposeDirect = true).use { fastRam ->
            OffHeapDirectRam(sizeBytes = ramSize, exposeDirect = false).use { strictRam ->
                val srcAddr = fastRam.base + 0x0001_0000L
                val dstAddr = fastRam.base + 0x0010_0000L
                val fillSrc = fastRam.base + 0x0000_2000L
                val fillDst = fastRam.base + 0x0020_0000L
                val copySize = 256 * 1024
                val fillSize = 64 * 1024

                // Deterministic pattern in both RAMs
                val pattern = ByteArray(copySize) { i -> ((i * 73 + 19) and 0xFF).toByte() }
                MemorySegment.copy(pattern, 0, fastRam.seg, ValueLayout.JAVA_BYTE, srcAddr - fastRam.base, copySize)
                MemorySegment.copy(pattern, 0, strictRam.seg, ValueLayout.JAVA_BYTE, srcAddr - strictRam.base, copySize)
                for (i in 0 until 8) {
                    val b = (0xA0 + i).toByte()
                    fastRam.seg.set(ValueLayout.JAVA_BYTE, (fillSrc - fastRam.base) + i, b)
                    strictRam.seg.set(ValueLayout.JAVA_BYTE, (fillSrc - strictRam.base) + i, b)
                }

                val fast = createHarness(fastRam, auditTransfers = false)
                val strict = createHarness(strictRam, auditTransfers = true)

                // 1. Bulk RAM-to-RAM copy
                runRamToRam(fast, srcAddr, dstAddr, copySize, fixed = false)
                runRamToRam(strict, srcAddr, dstAddr, copySize, fixed = false)

                assertContentEquals(strictRam.toByteArray(dstAddr, copySize), fastRam.toByteArray(dstAddr, copySize))
                assertEquals(0L, fastRam.slowReads, "Direct fastpath must bypass slow DmaMemory.read")
                assertEquals(0L, fastRam.slowWrites, "Direct fastpath must bypass slow DmaMemory.write")
                assertEquals(1L, fastRam.invalidateCalls, "Direct copy must invoke notifyDirectWrite once")
                assertEquals(dstAddr to copySize.toLong(), fastRam.invalidatedRanges.single())
                assertTrue(strictRam.slowReads >= 8L, "Strict audited path must read + readback")

                val fastEv = (fast.component.state()["events"] as List<*>).single() as Map<*, *>
                val strictEv = (strict.component.state()["events"] as List<*>).single() as Map<*, *>
                assertEquals("", fastEv["sourceSha256"], "Unaudited transfer skips SHA-256")
                assertEquals("", fastEv["destinationSha256"], "Unaudited transfer skips SHA-256")
                assertEquals(64, (strictEv["sourceSha256"] as String).length, "Strict transfer computes 64-hex SHA-256")
                assertEquals(strictEv["sourceSha256"], strictEv["destinationSha256"])

                // 2. Fixed 8-byte pattern fill (widthLog2 = 3 -> 8 bytes)
                runRamToRam(fast, fillSrc, fillDst, fillSize, fixed = true, widthLog2 = 3)
                runRamToRam(strict, fillSrc, fillDst, fillSize, fixed = true, widthLog2 = 3)
                assertContentEquals(strictRam.toByteArray(fillDst, fillSize), fastRam.toByteArray(fillDst, fillSize))
                assertEquals(0L, fastRam.slowWrites)
                assertEquals(2L, fastRam.invalidateCalls)

                // 3. Snapshot round-trip in unaudited mode preserves empty SHA-256 events cleanly
                val snap = fast.component.saveSnapshot()
                OffHeapDirectRam(sizeBytes = ramSize, exposeDirect = true).use { restoredRam ->
                    val restored = createHarness(restoredRam, auditTransfers = false)
                    restored.component.restoreSnapshot(snap)
                    assertEquals(fast.component.state(), restored.component.state())
                    restored.component.close()
                }

                fast.component.close()
                strict.component.close()
            }
        }
    }

    @Test
    fun benchmark16MiBRamToRamAnd8760LliScatterGatherSpeedup() {
        // 48 MiB off-heap guest RAM: 16 MiB src + 16 MiB dst + LLI descriptor table (8760 * 32B = 280 KiB)
        val ramBytes = 48L * 1024L * 1024L
        val bulkSize = 16 * 1024 * 1024
        val lliRows = 8760
        val rowBytes = 1024 // 8,760 rows * 1 KiB = ~8.55 MiB strided scatter-gather (same descriptor count as 102MP RAW!)
        val rowStride = 1536

        OffHeapDirectRam(sizeBytes = ramBytes, exposeDirect = true).use { fastRam ->
            OffHeapDirectRam(sizeBytes = ramBytes, exposeDirect = false).use { strictRam ->
                val srcBase = fastRam.base + 0x0010_0000L
                val dstBase = fastRam.base + 0x0120_0000L
                val descBase = fastRam.base + 0x0240_0000L

                // Seed deterministic bytes across 16 MiB source
                val seedPage = ByteArray(4096) { i -> ((i * 31 + 7) and 0xFF).toByte() }
                for (off in 0 until bulkSize step 4096) {
                    MemorySegment.copy(seedPage, 0, fastRam.seg, ValueLayout.JAVA_BYTE, (srcBase - fastRam.base) + off, 4096)
                    MemorySegment.copy(seedPage, 0, strictRam.seg, ValueLayout.JAVA_BYTE, (srcBase - strictRam.base) + off, 4096)
                }

                // Populate 8,760 6-word LLI descriptors (32-byte spaced, identical to GFX100S 102MP RAW conversion!)
                for (row in 0 until lliRows) {
                    val dAddr = descBase + row * 32L
                    val nextWord = if (row + 1 < lliRows) ((descBase + (row + 1) * 32L) or 1L) else (descBase + (row + 1) * 32L)
                    val sAddr = srcBase + row.toLong() * rowStride
                    val dstAddr = dstBase + row.toLong() * rowBytes
                    writeLliDesc(fastRam, dAddr, rowBytes, sAddr, dstAddr, 0x30F00L, 0x30F00L, nextWord)
                    writeLliDesc(strictRam, dAddr, rowBytes, sAddr, dstAddr, 0x30F00L, 0x30F00L, nextWord)
                }

                val fast = createHarness(fastRam, auditTransfers = false)
                val strict = createHarness(strictRam, auditTransfers = true)

                // Warm up JIT
                runRamToRam(fast, srcBase, dstBase, 65536)
                runRamToRam(strict, srcBase, dstBase, 65536)

                // A/B Benchmark 1: 16 MiB Bulk RAM-to-RAM DMA
                val tStrictBulk0 = System.nanoTime()
                runRamToRam(strict, srcBase, dstBase, bulkSize)
                val strictBulkMs = (System.nanoTime() - tStrictBulk0) / 1_000_000.0

                val tFastBulk0 = System.nanoTime()
                runRamToRam(fast, srcBase, dstBase, bulkSize)
                val fastBulkMs = (System.nanoTime() - tFastBulk0) / 1_000_000.0

                assertContentEquals(strictRam.toByteArray(dstBase, 4096), fastRam.toByteArray(dstBase, 4096))
                assertContentEquals(
                    strictRam.toByteArray(dstBase + bulkSize - 4096, 4096),
                    fastRam.toByteArray(dstBase + bulkSize - 4096, 4096),
                )

                // A/B Benchmark 2: 8,760-descriptor HDMAC LLI scatter-gather chain
                fun runLli(h: Harness): Double {
                    h.mux.write(0L, 4, 0x0100_0000L or (44L shl 8))
                    h.dma.write(0x1010L, 4, 0L)
                    h.dma.write(0x1014L, 4, srcBase)
                    h.dma.write(0x1018L, 4, dstBase)
                    h.dma.write(0x101CL, 4, 0x30F00L)
                    h.dma.write(0x1020L, 4, 0x30F00L)
                    h.dma.write(0x1024L, 4, descBase or 1L)
                    h.ctx.tick += 10L
                    h.dma.write(0x1028L, 4, 0x1110_0001L)
                    val due = assertNotNull(h.dma.nextDeadline())
                    h.ctx.tick = due
                    val t0 = System.nanoTime()
                    h.dma.beforeInstruction(due)
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                    h.dma.write(0x1030L, 4, 1L)
                    return elapsedMs
                }

                fastRam.invalidateCalls = 0L
                val strictLliMs = runLli(strict)
                val fastLliMs = runLli(fast)

                // Verify bit-exact output on first, middle, and final LLI rows + single coalesced JIT invalidation
                for (checkRow in listOf(0, lliRows / 2, lliRows - 1)) {
                    val off = dstBase + checkRow.toLong() * rowBytes
                    assertContentEquals(
                        strictRam.toByteArray(off, rowBytes),
                        fastRam.toByteArray(off, rowBytes),
                        "LLI row $checkRow mismatch",
                    )
                }
                assertEquals(1L, fastRam.invalidateCalls, "8,760 contiguous LLI rows must coalesce to 1 JIT invalidation")
                assertEquals(0L, fastRam.slowReads, "Direct LLI fastpath must use zero slow reads")
                assertEquals(0L, fastRam.slowWrites, "Direct LLI fastpath must use zero slow writes")

                val bulkMb = bulkSize / (1024.0 * 1024.0)
                val lliMb = (lliRows.toLong() * rowBytes) / (1024.0 * 1024.0)
                println(
                    String.format(
                        "[W11-DMA-BENCH] 16 MiB Bulk RAM-to-RAM: strict(SHA256+readback)=%.2f ms (%.1f MB/s) -> direct(zero-copy)=%.2f ms (%.1f MB/s) [%.1fx speedup]",
                        strictBulkMs, bulkMb / (strictBulkMs / 1000.0),
                        fastBulkMs, bulkMb / (fastBulkMs / 1000.0),
                        strictBulkMs / fastBulkMs.coerceAtLeast(0.01),
                    ),
                )
                println(
                    String.format(
                        "[W11-DMA-BENCH] 8,760-LLI Scatter-Gather (%.2f MiB): strict(SHA256)=%.2f ms (%.1f MB/s) -> direct(zero-copy, 1 coalesced inv)=%.2f ms (%.1f MB/s) [%.1fx speedup]",
                        lliMb,
                        strictLliMs, lliMb / (strictLliMs / 1000.0),
                        fastLliMs, lliMb / (fastLliMs / 1000.0),
                        strictLliMs / fastLliMs.coerceAtLeast(0.01),
                    ),
                )

                fast.component.close()
                strict.component.close()
            }
        }
    }
}
