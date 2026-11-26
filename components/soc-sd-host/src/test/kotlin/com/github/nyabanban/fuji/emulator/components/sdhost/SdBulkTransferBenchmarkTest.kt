package com.github.nyabanban.fuji.emulator.components.sdhost

import com.github.nyabanban.fuji.emulator.api.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.test.*

class SdBulkTransferBenchmarkTest {
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

    private class CountingBulkMedia(
        private val backingBytes: Int,
        private val reportedCapacityBytes: Long = 4L * 1024L * 1024L * 1024L,
    ) : CheckedBlockStoragePort {
        val bytes = ByteArray(backingBytes)
        var readCalls = 0
        var writeCalls = 0
        var maxWriteCallBytes = 0
        var maxReadCallBytes = 0
        var flushCalls = 0
        override fun size(slot: Int) = if (slot == 1) reportedCapacityBytes else null
        override fun readOnly(slot: Int) = false
        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            require(slot == 1 && offset >= 0 && length >= 0 && offset <= reportedCapacityBytes - length)
        }
        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val n = target.remaining()
            checkAccess(slot, offset, n.toLong(), false)
            require(offset + n <= bytes.size) { "Read beyond test backing window" }
            target.put(bytes, offset.toInt(), n)
            readCalls++
            if (n > maxReadCallBytes) maxReadCallBytes = n
            return n
        }
        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            val n = source.remaining()
            checkAccess(slot, offset, n.toLong(), true)
            require(offset + n <= bytes.size) { "Write beyond test backing window" }
            source.get(bytes, offset.toInt(), n)
            writeCalls++
            if (n > maxWriteCallBytes) maxWriteCallBytes = n
            return n
        }
        override fun flush(slot: Int) { flushCalls++ }
    }

    private class BulkRam(sizeBytes: Int) : DmaMemory {
        val bytes = ByteArray(sizeBytes)
        var readCalls = 0
        var writeCalls = 0
        override fun check(address: Long, size: Long, write: Boolean) {
            require(address >= 0 && size >= 0 && address <= bytes.size - size)
        }
        override fun read(address: Long, destination: ByteBuffer) {
            val n = destination.remaining()
            check(address, n.toLong(), false)
            destination.put(bytes, address.toInt(), n)
            readCalls++
        }
        override fun write(address: Long, source: ByteBuffer) {
            val n = source.remaining()
            check(address, n.toLong(), true)
            source.get(bytes, address.toInt(), n)
            writeCalls++
        }
    }

    private class Context(val data: Map<String, Any?>) : ComponentContext {
        var tick = 0L
        val entries = mutableMapOf<String, Any>()
        override val directory = Path.of("unused-sd-bulk")
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
    fun uhsPhyV9MultiBlockCmd25AndCmd18CoalesceBulkSdmaAcross512KiBBoundaries() {
        val reportedCapacity = 4L * 1024L * 1024L * 1024L // 4 GiB legal SDHC CSD v2
        val backingWindow = 2 * 1024 * 1024 // 2 MiB backing array
        val data = v9Input(reportedCapacity)
        val ctx = Context(data)
        val media = CountingBulkMedia(backingWindow, reportedCapacity)
        val ram = BulkRam(2 * 1024 * 1024)
        ctx.entries["sdcard-reader"] = media
        ctx.entries["soc-gic.lines"] = object : InterruptSink {
            override fun setLine(irq: Int, high: Boolean) {}
        }
        val host = SdHostFactory().create(ctx, mapOf("profile" to SdHostFactory.UHS_PHY_PROFILE, "inputFile" to "input.yaml"))
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
        // SDHC card init
        cmd(0, resp = 0); step(2)
        cmd(8, 0x1aa); step(2)
        cmd(55); step(2)
        cmd(41, 0x40ff8000); step(2)
        cmd(2, resp = 1); step(2)
        cmd(3); step(2)
        cmd(9, 0x12340000, resp = 1); step(2)
        cmd(7, 0x12340000, resp = 3); step(5)
        bank.write(0x30, 2, 0xfb)

        // Fill 1 MiB (2048 blocks) in RAM at 0x000000 and write via CMD25 with 512 KiB SDMA boundary (bits 14:12 = 7)
        val transferBlocks = 2048
        val transferBytes = transferBlocks * 512
        for (i in 0 until transferBytes) {
            ram.bytes[i] = ((i * 29 + 13) and 0xFF).toByte()
        }

        bank.write(0x04, 2, (7 shl 12).toLong() or 512L) // 512 KiB SDMA boundary, 512B block
        bank.write(0x06, 2, transferBlocks.toLong())
        bank.write(0x00, 4, 0x000000L)
        cmd(25, arg = 100L, mode = 0x27, hasData = true) // DMA + blockCount + AutoCMD12 + multi + write

        // Advance past first 512 KiB boundary (1024 blocks * 3 ticks + cmd 2 ticks)
        step(2 + 1024L * 3L)
        assertEquals(0x8L, bank.read(0x30, 2) and 0x8L, "Must pause cleanly at 512 KiB SDMA boundary")
        assertEquals(0x80000L, bank.read(0x00, 4))
        assertEquals(1024L, bank.read(0x06, 2))
        bank.write(0x30, 2, 0x8)
        bank.write(0x00, 4, 0x80000L) // resume second 512 KiB half

        step(1025L * 3L)
        assertEquals(0x2L, bank.read(0x30, 2) and 0x2L, "CMD25 transfer complete expected")
        assertEquals(0L, bank.read(0x06, 2))
        assertEquals(1, media.flushCalls)
        assertTrue(media.writeCalls <= 8, "Expected bulk SDMA write coalescing (<= 8 calls for 2048 blocks), got ${media.writeCalls}")
        assertEquals(256 * 1024, media.maxWriteCallBytes, "Expected 256 KiB max bulk SDMA write chunk")
        assertContentEquals(
            ram.bytes.copyOfRange(0, transferBytes),
            media.bytes.copyOfRange(100 * 512, 100 * 512 + transferBytes),
        )

        // Now read back the full 1 MiB into RAM at 0x100000 via CMD18 multi-block SDMA
        bank.write(0x30, 2, 0xfb)
        bank.write(0x04, 2, (7 shl 12).toLong() or 512L)
        bank.write(0x06, 2, transferBlocks.toLong())
        bank.write(0x00, 4, 0x100000L)
        cmd(18, arg = 100L, mode = 0x37, hasData = true) // DMA + blockCount + AutoCMD12 + read + multi

        step(2 + 1024L * 3L)
        assertEquals(0x8L, bank.read(0x30, 2) and 0x8L)
        bank.write(0x30, 2, 0x8)
        bank.write(0x00, 4, 0x180000L)
        step(1025L * 3L)
        assertEquals(0x2L, bank.read(0x30, 2) and 0x2L)
        assertTrue(media.readCalls <= 8, "Expected bulk SDMA read coalescing (<= 8 calls for 2048 blocks), got ${media.readCalls}")
        assertEquals(256 * 1024, media.maxReadCallBytes)
        assertContentEquals(
            ram.bytes.copyOfRange(0, transferBytes),
            ram.bytes.copyOfRange(0x100000, 0x100000 + transferBytes),
        )

        // Benchmark 64 MiB multi-block CMD25 SDMA throughput on owner thread (128 x 512 KiB transfers)
        val benchBlocks = 1024 // 512 KiB per CMD25
        val iterations = 128   // 64 MiB total
        val startNs = System.nanoTime()
        for (i in 0 until iterations) {
            bank.write(0x30, 2, 0xfb)
            bank.write(0x04, 2, (7 shl 12).toLong() or 512L)
            bank.write(0x06, 2, benchBlocks.toLong())
            bank.write(0x00, 4, 0x000000L)
            cmd(25, arg = 0L, mode = 0x27, hasData = true)
            step(2 + (benchBlocks + 1L) * 3L)
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        val mbps = 64.0 / (elapsedMs / 1000.0)
        println("[W11-SDHOST-BENCH] 64 MiB CMD25 bulk SDMA (128x512KiB): %.2f ms (%.1f MB/s)".format(elapsedMs, mbps))
        host.close()
    }
}
