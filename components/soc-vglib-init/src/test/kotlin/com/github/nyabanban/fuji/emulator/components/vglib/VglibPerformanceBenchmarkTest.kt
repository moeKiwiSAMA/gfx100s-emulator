package com.github.nyabanban.fuji.emulator.components.vglib

import com.github.nyabanban.fuji.emulator.api.ArgbFrameBuffer
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.GraphicsMode2Workspace
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("perf")
class VglibPerformanceBenchmarkTest {
    private fun medianNanos(samples: List<Long>): Long = samples.sorted()[samples.size / 2]

    private fun sha256Ints(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asIntBuffer().put(pixels)
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
    }

    @Test
    fun benchmarkArea5_lcdcScanoutAndVglibRenderers() {
        // 5a: LcdcFirstScanout PIC_YUV_OSD0 (1024x768 RGBA OSD0 + 1024x576 UYVY PIC1 -> 640x480 ARGB)
        val osd0Addr = 0x81230000L
        val osd0Size = 4096 * 768
        val pic1Addr = 0x82000000L
        val pic1Stride = 2048
        val pic1H = 576
        val pic1Size = pic1Stride * pic1H

        val osd0Data = ByteBuffer.allocate(osd0Size).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until 768) {
            for (x in 0 until 1024) {
                val alpha = if ((x + y) and 1 == 0) 0xff else 0x80
                val r = (x * 255 / 1023) and 0xff
                val g = (y * 255 / 767) and 0xff
                val b = ((x xor y) and 0xff)
                val word = (r shl 24) or (g shl 16) or (b shl 8) or alpha
                osd0Data.putInt(y * 4096 + x * 4, word)
            }
        }
        val pic1Data = ByteArray(pic1Size)
        for (i in 0 until pic1Size step 4) {
            pic1Data[i] = 0x70
            pic1Data[i + 1] = ((i * 13) and 0xff).toByte()
            pic1Data[i + 2] = 0x90.toByte()
            pic1Data[i + 3] = ((i * 7 + 40) and 0xff).toByte()
        }

        val ctx = LcdcFunctionalTest.Context()
        val display = ArgbFrameBuffer(640, 480)
        ctx.entries["display.main"] = display
        ctx.entries["cpu.memory"] = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(!write && ((address == osd0Addr && size == osd0Size.toLong()) || (address == pic1Addr && size == pic1Size.toLong())))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                when (address) {
                    osd0Addr -> destination.put(osd0Data.array(), 0, osd0Size)
                    pic1Addr -> destination.put(pic1Data, 0, pic1Size)
                    else -> error("unexpected address $address")
                }
            }
            override fun write(address: Long, source: ByteBuffer) = error("unexpected write")
        }

        val lcdc = VglibInitFactory().create(
            ctx,
            mapOf(
                "profile" to VglibInitFactory.FUNCTIONAL_PROFILE,
                "inputFile" to "bank",
                LcdcFirstScanout.SETTING to LcdcFirstScanout.PIC_YUV_OSD0,
                LcdcFirstScanout.LIMIT_SETTING to 256L,
            ),
        )
        val bank = ctx.entries.getValue(LcdcFunctional.port(1)) as MmioDevice
        bank.write(0x008, 4, 0x10010L)
        bank.write(0x030, 4, 0x20080001L)
        bank.write(0x040, 4, 0L)
        bank.write(0x044, 4, osd0Addr)
        bank.write(0x050, 4, 0x10400L)
        bank.write(0x054, 4, 0x03000400L)
        bank.write(0x058, 4, 0L)
        bank.write(0x05c, 4, 0x03000400L)
        bank.write(0x060, 4, 0x10001000L)
        bank.write(0x160, 4, 0xffL)
        bank.write(0x164, 4, 0L)
        bank.write(0x168, 4, pic1Addr)
        bank.write(0x16c, 4, (pic1Stride / 8).toLong())
        bank.write(0x170, 4, 0x03000400L)
        bank.write(0x174, 4, 96L shl 16)
        bank.write(0x178, 4, 0x02400400L)
        bank.write(0x17c, 4, 0x10001000L)

        repeat(10) { bank.write(0x600, 4, 1L) }
        val lcdcSamples = List(50) {
            val s = System.nanoTime()
            bank.write(0x600, 4, 1L)
            System.nanoTime() - s
        }
        val pixels = IntArray(640 * 480)
        assertTrue(display.copyArgb(pixels))
        val lcdcSha = sha256Ints(pixels)
        lcdc.close()

        // 5b: GraphicsCommandExecution (LanguageSolidCommandTest workload)
        val heapBase = 0x06fe6000L
        val target = 0x06893000L
        val heap = GraphicsMode2Workspace.parse(mapOf("base" to heapBase, "bytes" to 0xa00000))
        val inputs = GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/language-page/heap-slices.bin.gz"))).use { it.readBytes() }
        val frame = ByteArray(0x300000)
        val cmdMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(address == target && size == frame.size.toLong() ||
                    !write && address >= heapBase && address - heapBase <= inputs.size - size)
            }
            override fun read(address: Long, destination: ByteBuffer) {
                if (address == target) destination.put(frame)
                else destination.put(inputs, (address - heapBase).toInt(), destination.remaining())
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == target)
                source.get(frame)
            }
        }
        val first = mapOf("address" to 0x06fe6040L, "countWord" to 1501L, "control" to 0x0100ffffL)
        val second = first + mapOf("address" to 0x07066040L, "countWord" to 2619L)
        val cmdExec = GraphicsCommandExecution(heap, target, true)
        lateinit var cmdSha: String
        repeat(3) {
            frame.fill(0)
            cmdExec.execute(cmdMem, first, second)
        }
        val cmdSamples = List(15) {
            frame.fill(0)
            val s = System.nanoTime()
            val receipt = cmdExec.execute(cmdMem, first, second)
            val elapsed = System.nanoTime() - s
            cmdSha = receipt["sha256"] as String
            elapsed
        }

        // 5c: GraphicsCopyExecution Mode 1 (3 MiB linear) & Mode 2 (3 MiB Morton 8x8)
        val copySource = 0xffd00000L
        val copyDest = 0x400000L
        val mode2Source = 0x800000L
        val mode2Dest = 0xc00000L
        val copyBytes = 0x300000
        val copyConfig = mapOf(
            "profile" to GraphicsCopyExecution.PROFILE,
            "sourceBase" to copySource,
            "destinationBase" to copyDest,
            "surfaceBytes" to copyBytes,
            "submissionLimit" to 2,
            "mode2Morton8x8" to mapOf("sourceBase" to mode2Source, "destinationBase" to mode2Dest),
            GraphicsMode2Workspace.SETTING to mapOf("base" to mode2Source, "bytes" to 0x800000L),
            GraphicsCopyExecution.TILE_PROFILE_SETTING to GraphicsCopyExecution.ALIGNED_8X8_TILES,
        )
        val copyExec = GraphicsCopyExecution(copyConfig)
        val copyIn = ByteArray(copyBytes) { (it xor (it ushr 9) xor (it ushr 17)).toByte() }
        val copyOut = ByteArray(copyBytes)
        val copyMem = object : DmaMemory {
            override fun check(address: Long, size: Long, write: Boolean) {
                require(size == copyBytes.toLong() && (address == copySource || address == copyDest || address == mode2Source || address == mode2Dest))
            }
            override fun read(address: Long, destination: ByteBuffer) {
                destination.put(if (address == copySource || address == mode2Source) copyIn else copyOut)
            }
            override fun write(address: Long, source: ByteBuffer) {
                require(address == copyDest || address == mode2Dest)
                source.get(copyOut)
            }
        }
        val mode1Words = longArrayOf(copySource, copyDest, 0x03000400, 0x03000400, 1, 1, 0, 0, 0)
        val mode2Words = longArrayOf(mode2Source, mode2Dest, 0x03000400, 0x03000400, 2, 1, 0, 0, 0)

        lateinit var copy1Sha: String
        repeat(3) { copyExec.execute(copyMem, mode1Words) }
        val copy1Samples = List(15) {
            val s = System.nanoTime()
            val r = copyExec.execute(copyMem, mode1Words)
            val elapsed = System.nanoTime() - s
            copy1Sha = r["sha256"] as String
            elapsed
        }

        lateinit var copy2Sha: String
        repeat(3) { copyExec.execute(copyMem, mode2Words) }
        val copy2Samples = List(15) {
            val s = System.nanoTime()
            val r = copyExec.execute(copyMem, mode2Words)
            val elapsed = System.nanoTime() - s
            copy2Sha = r["sha256"] as String
            elapsed
        }

        // 5d: GraphicsFillExecution (3 MiB solid fill)
        val fillExec = GraphicsFillExecution(
            mapOf(
                "profile" to GraphicsFillExecution.PROFILE,
                "submissionLimit" to 2,
                "surfaceBase" to copyDest,
                "surfaceBytes" to copyBytes,
            ),
        )
        val fillWords = longArrayOf(copyDest, copyDest + copyBytes, 0xff112233L)
        lateinit var fillSha: String
        repeat(3) { fillExec.execute(copyMem, fillWords, GraphicsFillExecution.CONTROL) }
        val fillSamples = List(15) {
            val s = System.nanoTime()
            val r = fillExec.execute(copyMem, fillWords, GraphicsFillExecution.CONTROL)
            val elapsed = System.nanoTime() - s
            fillSha = r["sha256"] as String
            elapsed
        }

        assertEquals(64, lcdcSha.length)
        assertEquals(64, cmdSha.length)
        assertEquals(64, copy1Sha.length)
        assertEquals(64, copy2Sha.length)
        assertEquals(64, fillSha.length)

        println(
            "M5P_BENCHMARK {\"area\":\"lcdc_vglib\",\"lcdcMedianMs\":${medianNanos(lcdcSamples) / 1e6}," +
                "\"lcdcSha256\":\"$lcdcSha\",\"cmdMedianMs\":${medianNanos(cmdSamples) / 1e6}," +
                "\"cmdSha256\":\"$cmdSha\",\"copyMode1MedianMs\":${medianNanos(copy1Samples) / 1e6}," +
                "\"copyMode1Sha256\":\"$copy1Sha\",\"copyMode2MedianMs\":${medianNanos(copy2Samples) / 1e6}," +
                "\"copyMode2Sha256\":\"$copy2Sha\",\"fillMedianMs\":${medianNanos(fillSamples) / 1e6}," +
                "\"fillSha256\":\"$fillSha\"}",
        )
    }
}
