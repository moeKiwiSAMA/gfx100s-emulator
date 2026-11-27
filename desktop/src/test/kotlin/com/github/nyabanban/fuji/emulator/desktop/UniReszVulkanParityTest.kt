package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

class UniReszVulkanParityTest {
    @Test
    fun stockFacePreviewMatchesCpuForBoth422OrdersAndSignalsIrqAfterTheWrite(@TempDir root: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val modules = Files.createDirectories(root.resolve("compoments"))
        Files.copy(repo.resolve("components/lens/build/libs/lens-0.3.1.jar"), modules.resolve("lens-0.3.1.jar"))
        val config = Files.createDirectories(modules.resolve("lens")).resolve("lens.yaml")
        Files.copy(repo.resolve("tools/release/component-configs/lens/lens.yaml"), config)
        var gpuAvailable = false
        IntegrationTest().host(root) { owner, host ->
            owner.submit {
                host.components().single().component.applySettings(mapOf("opticsBackend" to "vulkan", "opticsEnabled" to true))
                val gpu = checkNotNull(host.ports.find("lens.uniresz", FacePreviewScalerPort::class.java))
                if (!gpu.supportsFacePreviewResize) return@submit
                gpuAvailable = true
                Arena.ofConfined().use { arena ->
                    var src = 0x8999_c000L
                    var dst = 0x8a9d_ac00L
                    var source = arena.allocate(1024L * 576 * 2)
                    val targetBacking = arena.allocate(640L * 360 * 2 + 16)
                    targetBacking.fill(0x59)
                    val target = targetBacking.asSlice(0, 640L * 360 * 2)
                    val input = Random(133).nextBytes(source.byteSize().toInt())
                    byteArrayOf(0, 0, 0, 0, 0, 0, -128, -128, -128, -128, 0, 0).copyInto(input)
                    source.copyFrom(MemorySegment.ofArray(input))
                    var committed = false
                    var blockedDestination = false
                    var cpuReads = 0
                    var cpuWrites = 0
                    var sliceRequests = 0
                    val dma = object : DirectGuestRamPort {
                        override fun check(address: Long, size: Long, write: Boolean) {
                            require(!write || !blockedDestination)
                            require(if (write) address == dst && size == target.byteSize() else address == src && size == source.byteSize())
                        }
                        override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
                            check(address, size, write)
                            sliceRequests++
                            // The desktop adapter exposes only contiguous, low guest RAM directly.
                            if (address + size > 0x1_0000_0000L) return null
                            return if (write) target else source
                        }
                        override fun read(address: Long, destination: ByteBuffer) {
                            check(address, destination.remaining().toLong(), false)
                            cpuReads++
                            destination.put(source.asByteBuffer())
                        }
                        override fun write(address: Long, source: ByteBuffer) {
                            check(address, source.remaining().toLong(), true)
                            cpuWrites++
                            target.asByteBuffer().put(source)
                        }
                        override fun notifyDirectWrite(address: Long, size: Long) { check(address, size, true); committed = true }
                    }
                    val legacyDma = object : DmaMemory by dma {}
                    var memory: DmaMemory = dma
                    var expectDirect = false
                    var active: FacePreviewScalerPort? = null
                    var irqAsserted = false
                    var tick = 0L
                    val context = object : DiscoveryContext {
                        override fun core() = 1
                        override fun tick() = tick
                        override fun pc() = 0x5af98L
                        override fun lr() = 0x11fdbbcL
                        override fun dmaMemory() = memory
                        override fun facePreviewScaler() = active
                        override fun interruptSink() = object : InterruptSink {
                            override fun setLine(irq: Int, high: Boolean) {
                                assertEquals(133, irq)
                                if (high) {
                                    assertEquals(expectDirect, committed)
                                    assertEquals(if (expectDirect || blockedDestination) 0 else 1, cpuWrites)
                                }
                                irqAsserted = high
                            }
                        }
                    }
                    val bus = checkNotNull(CatalogRuntime(AddressCatalog.bundled(), "discovery", root, mapOf(
                        "discovery" to mapOf("imageScalerF000" to "fff7f000-irq133-completion-v1"),
                    )).createBus(context))
                    var seq = 0L
                    fun reg(offset: Int, value: Long) { bus.access(++seq, 0xfff7f000L + offset, 4, true, value) }
                    reg(8, 0x100)
                    reg(0x0c, (575L shl 16) + 1023)
                    reg(0x14, (575L shl 16) + 1023)
                    reg(0x18, (359L shl 16) + 639)
                    reg(0xc8, 2048)
                    reg(0xd4, 1280)
                    fun render(port: FacePreviewScalerPort?, direct: Boolean = false): ByteArray {
                        reg(0xc0, src ushr 32)
                        reg(0xc4, src and 0xffff_ffffL)
                        reg(0xcc, dst ushr 32)
                        reg(0xd0, dst and 0xffff_ffffL)
                        expectDirect = direct
                        cpuReads = 0
                        cpuWrites = 0
                        sliceRequests = 0
                        active = port
                        committed = false
                        target.fill(0x2f)
                        reg(4, 1)
                        bus.beforeInstruction(tick + 63)
                        assertFalse(irqAsserted)
                        assertEquals(0x2f, target.get(ValueLayout.JAVA_BYTE, 0).toInt())
                        tick += 64
                        bus.beforeInstruction(tick)
                        assertTrue(irqAsserted)
                        reg(0xe4, 1)
                        return target.toArray(ValueLayout.JAVA_BYTE)
                    }
                    for (order in listOf(0x88L, 0L)) {
                        reg(0xd8, order)
                        assertArrayEquals(render(null), render(gpu, direct = true))
                        assertEquals(0, cpuReads)
                        assertArrayEquals(input, source.toArray(ValueLayout.JAVA_BYTE))
                    }
                    // F0C8=2560 in the PIC1 face-analysis DMA. Its last 512 padding bytes fall outside the DMA span.
                    val packed = source
                    val paddedSource = arena.allocate(575L * 2560 + 2048)
                    source = paddedSource
                    val padded = Random(2560).nextBytes(source.byteSize().toInt())
                    for (y in 0 until 576) input.copyInto(padded, y * 2560, y * 2048, (y + 1) * 2048)
                    source.copyFrom(MemorySegment.ofArray(padded))
                    for (order in listOf(0L, 0x88L)) {
                        reg(0xd8, order)
                        reg(0xc8, 2560)
                        val image = render(null)
                        assertArrayEquals(image, render(gpu, direct = true))
                        assertEquals(0, cpuReads)
                        assertArrayEquals(padded, source.toArray(ValueLayout.JAVA_BYTE))
                        source = packed
                        reg(0xc8, 2048)
                        assertArrayEquals(image, render(null))
                        source = paddedSource
                    }
                    assertArrayEquals(ByteArray(16) { 0x59 }, targetBacking.asSlice(target.byteSize()).toArray(ValueLayout.JAVA_BYTE))
                    reg(0xc8, 2560)
                    val expected = render(null)
                    memory = legacyDma
                    assertArrayEquals(expected, render(gpu))
                    assertEquals(0, sliceRequests)
                    memory = dma
                    for ((sourceAddress, targetAddress) in listOf(
                        0x1_0000_0000L to 0x2_0000_0000L,
                        0xffff_f800L to 0x8a9d_ac00L,
                        0x8999_c000L to 0xffff_f800L,
                    )) {
                        src = sourceAddress
                        dst = targetAddress
                        assertArrayEquals(expected, render(gpu))
                        assertEquals(1, cpuReads)
                    }
                    // Reject the entire crossing destination before requesting either view or reading the source.
                    blockedDestination = true
                    assertArrayEquals(ByteArray(target.byteSize().toInt()) { 0x2f }, render(gpu))
                    assertEquals(0, cpuReads)
                    assertEquals(0, sliceRequests)
                }
            }.join()
        }
        assumeTrue(gpuAvailable, "Vulkan unavailable")
    }
}
