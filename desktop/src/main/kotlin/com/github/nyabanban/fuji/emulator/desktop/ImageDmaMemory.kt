package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Firmware 2.13 still-processing DDR pools overlap the ARM flash / peripheral aperture.
 * S5 0x02733308 and 0x02733338 describe the two scratch transfers. The first starts in
 * shared DDR below 0xf0000000. S5 0x02733450 describes the separate 102 MP frame pool.
 * Route only the overlapping extents on the imaging bus. The CPU keeps its flash and MMIO map.
 */
internal class ImageDmaMemory(
    private val ram: DirectGuestRamPort,
    private val requireOwner: () -> Unit,
) : DirectGuestRamPort, AutoCloseable {
    private class Window(val base: Long, val end: Long) {
        var arena: Arena? = null
        var segment: MemorySegment? = null
        var storage: ByteBuffer? = null

        fun buffer(): ByteBuffer = storage ?: run {
            val allocatedArena = Arena.ofConfined()
            val allocatedSegment = allocatedArena.allocate(end - base, 64L)
            val allocatedBuffer = allocatedSegment.asByteBuffer()
            arena = allocatedArena
            segment = allocatedSegment
            storage = allocatedBuffer
            allocatedBuffer
        }

        fun directSegment(): MemorySegment {
            if (segment == null) buffer()
            return checkNotNull(segment)
        }

        fun replace(newArena: Arena?, newSegment: MemorySegment?, newBuffer: ByteBuffer?) {
            val previous = arena
            arena = newArena
            segment = newSegment
            storage = newBuffer
            previous?.close()
        }

        fun close() = replace(null, null, null)
    }

    private val windows = arrayOf(
        Window(0xf000_0000L, 0xf084_2c00L),
        Window(0xf100_0000L, 0xf284_2c00L),
        Window(0xf300_0000L, 0xff8f_7440L),
    )

    override fun close() {
        for (window in windows) window.close()
    }

    private fun crossesCpuLimit(address: Long, size: Long): Boolean =
        address in 0 until CPU_LIMIT && size > CPU_LIMIT - address

    private fun requiresRouting(address: Long, size: Long): Boolean =
        (address < windows.last().end && address + size > windows.first().base) || crossesCpuLimit(address, size)

    private inline fun eachPart(address: Long, size: Long, action: (Window?, Long, Long) -> Unit) {
        require(address >= 0 && size > 0 && address <= Long.MAX_VALUE - size)
        val end = address + size
        var pos = address
        for (window in windows) {
            if (pos >= end || window.end <= pos || window.base >= end) continue
            if (pos < window.base) {
                action(null, pos, window.base - pos)
                pos = window.base
            }
            val count = minOf(end, window.end) - pos
            action(window, pos, count)
            pos += count
        }
        if (pos < CPU_LIMIT && end > CPU_LIMIT) {
            action(null, pos, CPU_LIMIT - pos)
            pos = CPU_LIMIT
        }
        if (pos < end) action(null, pos, end - pos)
    }

    /** Stores only initialized windows. CPU RAM and flash retain their own snapshot payloads. */
    fun saveWindows(ramDir: Path): Map<String, String> {
        requireOwner()
        return buildMap {
            for (window in windows) {
                val data = window.storage?.asReadOnlyBuffer() ?: continue
                val id = window.base.toString(16)
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(data.duplicate())
                FileChannel.open(ramDir.resolve("image-dma-$id.bin"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                    while (data.hasRemaining()) channel.write(data)
                }
                put(id, HexFormat.of().formatHex(digest.digest()))
            }
        }
    }

    fun prepareWindowRestore(ramDir: Path, hashes: Map<*, *>): () -> Unit {
        requireOwner()
        require(hashes.keys.all { id -> windows.any { it.base.toString(16) == id } }) { "Unknown imaging DMA window" }
        val restored = ArrayList<Triple<Arena, MemorySegment, ByteBuffer>?>(windows.size)
        try {
            for (window in windows) {
                val id = window.base.toString(16)
                if (id !in hashes) {
                    restored += null
                    continue
                }
                val size = window.end - window.base
                val file = ramDir.resolve("image-dma-$id.bin")
                val hash = hashes[id] as? String ?: error("Missing SHA-256 for imaging DMA window $id")
                EmulatorSnapshot.verifyRegularFileSha256(file, size, hash)
                val arena = Arena.ofConfined()
                try {
                    val segment = arena.allocate(size, 64L)
                    val buffer = segment.asByteBuffer()
                    FileChannel.open(file).use { channel ->
                        while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated imaging DMA snapshot" }
                    }
                    buffer.clear()
                    restored += Triple(arena, segment, buffer)
                } catch (t: Throwable) {
                    arena.close()
                    throw t
                }
            }
        } catch (t: Throwable) {
            for (entry in restored) entry?.first?.close()
            throw t
        }
        return {
            for (index in windows.indices) {
                val entry = restored[index]
                windows[index].replace(entry?.first, entry?.second, entry?.third)
            }
        }
    }

    override fun check(address: Long, size: Long, write: Boolean) {
        if (!requiresRouting(address, size)) return ram.check(address, size, write)
        requireOwner()
        eachPart(address, size) { window, start, count -> if (window == null) ram.check(start, count, write) }
    }

    override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
        if (!requiresRouting(address, size)) return ram.sliceDirect(address, size, write)
        check(address, size, write)
        if (crossesCpuLimit(address, size)) return null
        val window = windows.firstOrNull { address >= it.base && address + size <= it.end }
        if (window != null) return window.directSegment().asSlice(address - window.base, size)
        if (windows.none { address < it.end && address + size > it.base }) return ram.sliceDirect(address, size, write)
        return null
    }

    override fun notifyDirectWrite(address: Long, size: Long) {
        if (!requiresRouting(address, size)) return ram.notifyDirectWrite(address, size)
        check(address, size, true)
        eachPart(address, size) { window, start, count -> if (window == null) ram.notifyDirectWrite(start, count) }
    }

    override fun read(address: Long, destination: ByteBuffer) = transfer(address, destination, false)
    override fun write(address: Long, source: ByteBuffer) = transfer(address, source, true)

    private fun transfer(address: Long, buffer: ByteBuffer, write: Boolean) {
        val size = buffer.remaining().toLong()
        if (!requiresRouting(address, size)) {
            if (write) ram.write(address, buffer) else ram.read(address, buffer)
            return
        }
        check(address, size, write)
        eachPart(address, size) { window, start, count ->
            val n = count.toInt()
            val part = buffer.slice(buffer.position(), n)
            if (window == null) {
                if (write) ram.write(start, part) else ram.read(start, part)
            } else {
                val storage = window.buffer()
                val index = (start - window.base).toInt()
                if (write) storage.put(index, part, 0, n) else part.put(0, storage, index, n)
            }
            buffer.position(buffer.position() + n)
        }
    }

    private companion object {
        const val CPU_LIMIT = 0x1_0000_0000L
    }
}
