package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.TreeMap

/** Unpublished guest writes. Only the hottest pages consume payload space on the JVM heap. */
internal class PendingSdSectors(private val scratchDirectory: Path, private val cachePages: Int = 128) : AutoCloseable {
    init {
        require(cachePages > 0)
    }

    companion object {
        private const val SECTOR_BYTES = 512
        private const val SECTORS_PER_PAGE = 128
        private const val PAGE_BYTES = SECTORS_PER_PAGE * SECTOR_BYTES
    }

    internal class Selection {
        internal val masks = HashMap<Long, BitSet>()

        fun add(start: Long, end: Long) {
            var lba = start
            while (lba < end) {
                val page = lba / SECTORS_PER_PAGE
                val index = (lba % SECTORS_PER_PAGE).toInt()
                val count = minOf(end - lba, (SECTORS_PER_PAGE - index).toLong()).toInt()
                masks.getOrPut(page) { BitSet(SECTORS_PER_PAGE) }.set(index, index + count)
                lba += count
            }
        }
    }

    private class Page {
        val valid = BitSet(SECTORS_PER_PAGE)
        var offset = -1L
        var changed = false
    }

    private val pages = TreeMap<Long, Page>()
    private val cache = LinkedHashMap<Long, ByteArray>(cachePages, 0.75f, true)
    private val freeOffsets = ArrayDeque<Long>()
    private var nextOffset = 0L
    private var spill: FileChannel? = null
    var size = 0
        private set

    private fun backing(): FileChannel {
        spill?.let { return it }
        // The spill belongs to the mounted instance, outside the guest's mapped folder.
        val tmp = Files.createTempFile(scratchDirectory, ".fuji-sd-pending-", ".tmp")
        return try {
            FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
                .also { spill = it }
        } catch (failure: Throwable) {
            Files.deleteIfExists(tmp)
            throw failure
        }
    }

    private fun content(number: Long, page: Page): ByteArray {
        cache[number]?.let { return it }
        val bytes = if (cache.size < cachePages) ByteArray(PAGE_BYTES) else {
            val eldest = cache.entries.first()
            val evicted = pages.getValue(eldest.key)
            if (evicted.changed) {
                val channel = backing()
                val offset = if (evicted.offset >= 0) evicted.offset else freeOffsets.lastOrNull() ?: nextOffset
                val src = ByteBuffer.wrap(eldest.value)
                while (src.hasRemaining()) check(channel.write(src, offset + src.position()) > 0)
                if (evicted.offset < 0) {
                    if (freeOffsets.isEmpty()) nextOffset += PAGE_BYTES else freeOffsets.removeLast()
                    evicted.offset = offset
                }
                evicted.changed = false
            }
            cache.remove(eldest.key)
            eldest.value
        }
        if (page.offset >= 0) {
            val channel = checkNotNull(spill)
            val dst = ByteBuffer.wrap(bytes)
            while (dst.hasRemaining()) check(channel.read(dst, page.offset + dst.position()) > 0) { "Truncated pending SD page" }
        }
        // A fresh page has no valid bits. Every sector becomes visible only after all 512 bytes are written.
        cache[number] = bytes
        return bytes
    }

    fun containsKey(lba: Long): Boolean = pages[lba / SECTORS_PER_PAGE]?.valid?.get((lba % SECTORS_PER_PAGE).toInt()) == true

    fun read(lba: Long, sectorOffset: Int, target: ByteBuffer): Int {
        val number = lba / SECTORS_PER_PAGE
        val page = pages[number] ?: return 0
        val index = (lba % SECTORS_PER_PAGE).toInt()
        if (!page.valid[index]) return 0
        val count = minOf(target.remaining(), (page.valid.nextClearBit(index) - index) * SECTOR_BYTES - sectorOffset)
        target.put(content(number, page), index * SECTOR_BYTES + sectorOffset, count)
        return count
    }

    operator fun get(lba: Long): ByteArray? {
        if (!containsKey(lba)) return null
        return ByteArray(SECTOR_BYTES).also { read(lba, 0, ByteBuffer.wrap(it)) }
    }

    fun write(lba: Long, count: Int, src: ByteBuffer) {
        var next = lba
        var remaining = count
        while (remaining > 0) {
            val number = next / SECTORS_PER_PAGE
            val page = pages.getOrPut(number) { Page() }
            val index = (next % SECTORS_PER_PAGE).toInt()
            val n = minOf(remaining, SECTORS_PER_PAGE - index)
            src.get(content(number, page), index * SECTOR_BYTES, n * SECTOR_BYTES)
            val before = page.valid.cardinality()
            page.valid.set(index, index + n)
            size += page.valid.cardinality() - before
            page.changed = true
            next += n
            remaining -= n
        }
    }

    fun ceilingKey(lba: Long): Long? {
        val number = lba / SECTORS_PER_PAGE
        var entry = pages.ceilingEntry(number)
        while (entry != null) {
            val from = if (entry.key == number) (lba % SECTORS_PER_PAGE).toInt() else 0
            val bit = entry.value.valid.nextSetBit(from)
            if (bit >= 0) return entry.key * SECTORS_PER_PAGE + bit
            entry = pages.higherEntry(entry.key)
        }
        return null
    }

    fun forEachRange(action: (Long, Long) -> Unit) {
        for ((number, page) in pages) {
            val base = number * SECTORS_PER_PAGE
            var first = page.valid.nextSetBit(0)
            while (first >= 0) {
                val end = page.valid.nextClearBit(first)
                action(base + first, base + end)
                first = page.valid.nextSetBit(end)
            }
        }
    }

    fun retain(selection: Selection?) {
        if (selection == null) {
            close()
            return
        }
        val iterator = pages.entries.iterator()
        while (iterator.hasNext()) {
            val (number, page) = iterator.next()
            val before = page.valid.cardinality()
            val keep = selection.masks[number]
            if (keep == null) page.valid.clear() else page.valid.and(keep)
            size -= before - page.valid.cardinality()
            if (page.valid.isEmpty) {
                if (page.offset >= 0) freeOffsets.addLast(page.offset)
                cache.remove(number)
                iterator.remove()
            }
        }
        if (size == 0) close()
    }

    override fun close() {
        spill?.close()
        spill = null
        pages.clear()
        cache.clear()
        freeOffsets.clear()
        size = 0
        nextOffset = 0L
    }
}
