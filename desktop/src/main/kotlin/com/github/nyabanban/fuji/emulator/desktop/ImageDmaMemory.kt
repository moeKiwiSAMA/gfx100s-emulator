package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DirectGuestRamPort
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Firmware 2.13 still-processing DDR workspaces overlap the ARM QSPI aperture on the image bus.
 * S5 0x02733308/0x02733338 describe the two transfer extents. The low part of the first
 * workspace is ordinary shared DDR up to 0xefffffff. Only its tail and the second workspace
 * require a DMA-only backing. ARM instruction fetches and flash MMIO stay on their own bus.
 */
internal class ImageDmaMemory(
    private val ram: DirectGuestRamPort,
    private val requireOwner: () -> Unit,
) : DirectGuestRamPort {
    private class Window(val base: Long, val end: Long) {
        var storage: ByteBuffer? = null
        fun buffer(): ByteBuffer = storage ?: ByteBuffer.allocateDirect((end - base).toInt()).also { storage = it }
    }

    private val windows = arrayOf(Window(0xf000_0000L, 0xf084_2c00L), Window(0xf100_0000L, 0xf284_2c00L))
    private fun intersects(address: Long, size: Long): Boolean =
        address < windows.last().end && address + size > windows.first().base

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
        val restored = windows.map { window ->
            val id = window.base.toString(16)
            if (id !in hashes) return@map null
            val size = window.end - window.base
            val file = ramDir.resolve("image-dma-$id.bin")
            val hash = hashes[id] as? String ?: error("Missing SHA-256 for imaging DMA window $id")
            EmulatorSnapshot.verifyRegularFileSha256(file, size, hash)
            ByteBuffer.allocateDirect(size.toInt()).also { buffer ->
                FileChannel.open(file).use { channel ->
                    while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated imaging DMA snapshot" }
                }
                buffer.clear()
            }
        }
        return { for (index in windows.indices) windows[index].storage = restored[index] }
    }

    override fun check(address: Long, size: Long, write: Boolean) {
        if (!intersects(address, size)) return ram.check(address, size, write)
        requireOwner()
        eachPart(address, size) { window, start, count -> if (window == null) ram.check(start, count, write) }
    }

    override fun sliceDirect(address: Long, size: Long, write: Boolean): MemorySegment? {
        if (!intersects(address, size)) return ram.sliceDirect(address, size, write)
        check(address, size, write)
        val window = windows.firstOrNull { address >= it.base && address + size <= it.end }
        if (window != null) return MemorySegment.ofBuffer(window.buffer()).asSlice(address - window.base, size)
        if (windows.none { address < it.end && address + size > it.base }) return ram.sliceDirect(address, size, write)
        return null
    }

    override fun notifyDirectWrite(address: Long, size: Long) {
        if (!intersects(address, size)) return ram.notifyDirectWrite(address, size)
        check(address, size, true)
        eachPart(address, size) { window, start, count -> if (window == null) ram.notifyDirectWrite(start, count) }
    }

    override fun read(address: Long, destination: ByteBuffer) = transfer(address, destination, false)
    override fun write(address: Long, source: ByteBuffer) = transfer(address, source, true)

    private fun transfer(address: Long, buffer: ByteBuffer, write: Boolean) {
        val size = buffer.remaining().toLong()
        if (!intersects(address, size)) {
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
}
