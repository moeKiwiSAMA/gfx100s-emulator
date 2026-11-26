package com.github.nyabanban.fuji.emulator.components.sdhost

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

class SdDirectRamFastpathTest {
    private fun v9Input(capacityBytes: Long): Map<String, Any?> {
        val bytes = ByteArray(15) { (it * 17 + 3).toByte() }
        var crc = 0
        for (b in bytes) for (i in 7 downTo 0) {
            val feedback = (b.toInt() ushr i and 1) xor (crc ushr 6 and 1)
            crc = crc shl 1 and 127
            if (feedback != 0) crc = crc xor 9
        }
        val cid = (bytes + ((crc shl 1) or 1).toByte()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return mapOf(
            "schema" to 1,
            "profile" to SdHostFactory.UHS_PHY_PROFILE,
            "commandTicks" to 2,
            "blockTicks" to 3,
            "resetTicks" to 2,
            "addressHighBits" to mapOf("0" to 0, "1" to 0),
            "vendorPhyWords" to (0..1).associate {
                it.toString() to SdTransactionHost.vendorPhyFields.associateWith { 0L }
            },
            "uhsPhyWords" to (0..1).associate {
                it.toString() to SdTransactionHost.uhsPhyFields.associateWith { 0L }
            },
            "legacy" to mapOf(
                "schema" to 1,
                "profile" to SdHostFactory.PHY_PROFILE,
                "initialRegisters" to (0..1).associate { it.toString() to SdControlHost.fields.associateWith { 0L } },
                "clocks" to (0..3).associate { SdClockHost.key(it) to mapOf("initialWord" to 5, "bit1Samples" to listOf(0, 1)) },
                "initWords" to (0..1).associate {
                    it.toString() to SdInitHost.fields.associateWith { name -> if (name == "host4028") 0x0f00L else 0L }
                },
            ),
            "cards" to mapOf(
                "0" to mapOf(
                    "basis" to "SYNTHETIC",
                    "capacityBytes" to capacityBytes,
                    "highCapacity" to true,
                    "readOnly" to false,
                    "rca" to 0x1234,
                    "cidHex" to cid,
                    "ocrVoltageWindow" to 0x00ff8000L,
                    "supportsHighSpeed" to true,
                ),
                "1" to null,
            ),
        )
    }

    private class CountingMedia(
        backingBytes: Int,
        private val reportedCapacityBytes: Long = 4L * 1024L * 1024L * 1024L,
    ) : CheckedBlockStoragePort {
        val bytes = ByteArray(backingBytes)
        var directBufferReadCalls = 0
        var directBufferWriteCalls = 0
        override fun size(slot: Int) = if (slot == 1) reportedCapacityBytes else null
        override fun readOnly(slot: Int) = false
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            require(slot == 1 && offset >= 0 && length >= 0 && offset <= reportedCapacityBytes - length)
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val n = target.remaining()
            if (target.isDirect) directBufferReadCalls++
            target.put(bytes, offset.toInt(), n)
            return n
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val n = source.remaining()
            if (source.isDirect) directBufferWriteCalls++
            source.get(bytes, offset.toInt(), n)
            return n
        }
        override fun flush(slot: Int) {}
    }

    private class OffHeapDirectRam(
        private val sizeBytes: Long,
        private val allowDirect: Boolean,
    ) : DirectGuestRamPort, AutoCloseable {
        private val arena = Arena.ofConfined()
        val seg: MemorySegment = arena.allocate(sizeBytes, 64L)
        var slowReads = 0
        var slowWrites = 0
        var directNotifiedBytes = 0L

        fun load(address: Long, bytes: ByteArray) {
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, address, bytes.size)
        }

        fun dump(address: Long, size: Int): ByteArray {
            val out = ByteArray(size)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address, out, 0, size)
            return out
        }

        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0L && size >= 0L && address + size <= sizeBytes)
        }

        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            check(address, n.toLong(), false)
            slowReads++
            val tmp = ByteArray(n)
            MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, address, tmp, 0, n)
            destination.put(tmp)
        }

        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining()
            check(address, n.toLong(), true)
            slowWrites++
            val tmp = ByteArray(n)
            source.get(tmp)
            MemorySegment.copy(tmp, 0, seg, ValueLayout.JAVA_BYTE, address, n)
        }

        override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
            if (!allowDirect) return null
            check(address, size, write)
            return seg.asSlice(address, size)
        }

        override fun notifyDirectWrite(address: Long, size: Long) {
            directNotifiedBytes += size
        }

        override fun close() = arena.close()
    }

    private class TestCtx(val data: Map<String, Any?>) : ComponentContext {
        var tick = 0L
        val entries = mutableMapOf<String, Any>()
        override val directory = Path.of("unused-sd-direct")
        override val clock = EmulationClock { tick }
        override val ports = object : Ports {
            override fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T) {
                check(entries.putIfAbsent(name, port) == null)
            }
            override fun <T : Any> find(name: String, type: Class<T>): T? = entries[name]?.let(type::cast)
            override fun removeOwner(owner: String) {}
        }
        override val events = object : EventChannel {
            override fun publish(event: ComponentEvent) {}
            override fun subscribe(listener: (ComponentEvent) -> Unit) = AutoCloseable {}
        }
        override fun dataPath(relative: String) = directory.resolve(relative)
        override fun readData(relative: String) = data
    }

    @Test
    fun bulkSdmaCmd25AndCmd18ZeroCopyMatchesDmaFallbackBitExactAndBenchmark() {
        val reportedCapacity = 4L * 1024L * 1024L * 1024L
        val transferBlocks = 2048 // 1 MiB across two 512 KiB SDMA boundaries
        val transferBytes = transferBlocks * 512
        val payload = ByteArray(transferBytes) { ((it * 31 + 19) and 0xFF).toByte() }
        val dstAddr = 0x10_0000L

        fun runPass(allowDirect: Boolean, iters: Int): Triple<ByteArray, OffHeapDirectRam, Pair<CountingMedia, Double>> {
            val ctx = TestCtx(v9Input(reportedCapacity))
            val media = CountingMedia(2 * 1024 * 1024, reportedCapacity)
            val ram = OffHeapDirectRam(4L * 1024L * 1024L, allowDirect)
            ram.load(0L, payload)
            ctx.entries["sdcard-reader"] = media
            ctx.entries["soc-gic.lines"] = object : InterruptSink {
                override fun setLine(irq: Int, high: Boolean) {}
            }
            val host = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "in.yaml"))
            host.start()
            ctx.entries["cpu.memory"] = ram
            val bank = ctx.entries[SdTransactionHost.portForProfile(0, SdHostFactory.UHS_PHY_PROFILE)] as MmioDevice

            fun step(ticks: Long) {
                ctx.tick += ticks
                bank.beforeInstruction(ctx.tick)
            }
            fun cmd(index: Int, arg: Long = 0L, resp: Int = 2, mode: Int = 0, hasData: Boolean = false) {
                bank.write(8, 2, arg and 0xFFFF)
                bank.write(10, 2, arg ushr 16)
                bank.write(12, 4, (((index shl 8) or resp or (if (hasData) 0x20 else 0)).toLong() shl 16) or mode.toLong())
            }

            step(2)
            bank.write(0x34, 2, 0xfb); bank.write(0x38, 2, 0xfb)
            cmd(0, resp = 0); step(2)
            cmd(8, 0x1aa); step(2)
            cmd(55); step(2)
            cmd(41, 0x40ff8000); step(2)
            cmd(2, resp = 1); step(2)
            cmd(3); step(2)
            cmd(9, 0x12340000, resp = 1); step(2)
            cmd(7, 0x12340000, resp = 3); step(5)
            bank.write(0x30, 2, 0xfb)

            fun doWriteAndRead() {
                // CMD25 write 1 MiB
                bank.write(0x04, 2, (7 shl 12).toLong() or 512L)
                bank.write(0x06, 2, transferBlocks.toLong())
                bank.write(0x00, 4, 0L)
                cmd(25, arg = 100L, mode = 0x27, hasData = true)
                step(2 + 1024L * 3L)
                bank.write(0x30, 2, 0x8)
                bank.write(0x00, 4, 0x80000L)
                step(1025L * 3L)
                assertEquals(0x2L, bank.read(0x30, 2) and 0x2L)
                bank.write(0x30, 2, 0xfb)

                // CMD18 read 1 MiB back to dstAddr
                bank.write(0x04, 2, (7 shl 12).toLong() or 512L)
                bank.write(0x06, 2, transferBlocks.toLong())
                bank.write(0x00, 4, dstAddr)
                cmd(18, arg = 100L, mode = 0x37, hasData = true)
                step(2 + 1024L * 3L)
                bank.write(0x30, 2, 0x8)
                bank.write(0x00, 4, dstAddr + 0x80000L)
                step(1025L * 3L)
                assertEquals(0x2L, bank.read(0x30, 2) and 0x2L)
                bank.write(0x30, 2, 0xfb)
            }

            doWriteAndRead()
            val t0 = System.nanoTime()
            repeat(iters) { doWriteAndRead() }
            val msPerOp = (System.nanoTime() - t0) / 1e6 / iters
            val readBack = ram.dump(dstAddr, transferBytes)
            host.close()
            return Triple(readBack, ram, media to msPerOp)
        }

        val iters = 15
        val (slowOut, slowRam, slowPair) = runPass(allowDirect = false, iters = iters)
        val (dirOut, dirRam, dirPair) = runPass(allowDirect = true, iters = iters)
        try {
            assertContentEquals(payload, slowOut)
            assertContentEquals(payload, dirOut, "DirectGuestRamPort bulk SDMA must match DmaMemory fallback bit-exact")
            assertEquals(0, dirRam.slowReads, "Direct bulk SDMA CMD25 must execute zero slow DMA reads")
            assertEquals(0, dirRam.slowWrites, "Direct bulk SDMA CMD18 must execute zero slow DMA writes")
            assertEquals(transferBytes.toLong() * (iters + 1), dirRam.directNotifiedBytes, "CMD18 must notifyDirectWrite for every transferred byte")
            assertTrue(dirPair.first.directBufferReadCalls > 0 && dirPair.first.directBufferWriteCalls > 0, "Media must receive direct ByteBuffers")
            println(
                "[W12-SDMA-BENCH] 1 MiB CMD25+CMD18 bulk SDMA: " +
                    "DmaMemory=${"%.3f".format(slowPair.second)} ms/op (slowR=${slowRam.slowReads}, slowW=${slowRam.slowWrites}) -> " +
                    "DirectGuestRamPort=${"%.3f".format(dirPair.second)} ms/op (slowR=${dirRam.slowReads}, slowW=${dirRam.slowWrites})",
            )
        } finally {
            slowRam.close()
            dirRam.close()
        }
    }
}
