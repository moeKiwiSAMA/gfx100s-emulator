package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded retained-register fallback and diagnostic feedback recorder used ONLY in user-facing
 * `relaxed` execution mode.
 *
 * In `relaxed` mode, modeled hardware components still execute their full real behavior, while
 * unmodeled SoC peripheral accesses (or discovery/device refusals outside research mode) return
 * previously written bytes (or 0 on first read) instead of halting the emulator.
 *
 * Research modes (`discovery` and `strict`) NEVER construct or attach this fallback.
 */
class RelaxedPeripheralFallback(
    private val maxPages: Int = DEFAULT_MAX_PAGES,
    private val context: Context? = null,
    private val flashLayout: FlashLayout? = null,
    private val logger: Logger = LoggerFactory.getLogger(RelaxedPeripheralFallback::class.java),
    private val warnSink: ((String) -> Unit)? = null,
    private val infoSink: ((String) -> Unit)? = null,
    private val debugSink: ((String) -> Unit)? = null,
    private val telemetryPublisher: TelemetrySessionPublisher? = null,
) {
    interface Context {
        fun pc(): Long
        fun tick(): Long
        fun stage(): String
    }

    data class FlashLayout(
        val apertureBase: Long,
        val apertureSize: Long,
        val registersBase: Long,
        val registersSize: Long,
        val dmaRegistersBase: Long,
        val dmaRegistersSize: Long,
    ) {
        val apertureEnd: Long = apertureBase + apertureSize
        val registersEnd: Long = registersBase + registersSize
        val dmaRegistersEnd: Long = dmaRegistersBase + dmaRegistersSize

        fun inAperture(address: Long): Boolean = apertureSize > 0L && address >= apertureBase && address < apertureEnd
        fun inRegisters(address: Long): Boolean = registersSize > 0L && address >= registersBase && address < registersEnd
        fun inDmaRegisters(address: Long): Boolean = dmaRegistersSize > 0L && address >= dmaRegistersBase && address < dmaRegistersEnd
    }

    private class MmioEntry(
        val order: Int,
        val source: String,
        val address: Long,
        val write: Boolean,
        val firstSize: Int,
        val firstValue: Long,
        val firstPc: Long,
        val firstTick: Long,
        val firstStage: String,
        val firstReason: String?,
    ) {
        val hits = AtomicLong(1L)
    }

    private class FlashEntry(
        val order: Int,
        val op: String,
        val sector: Long,
        val firstOffset: Long,
        val firstSize: Long,
        val refused: Boolean,
        val firstPc: Long,
        val firstTick: Long,
        val firstStage: String,
        val firstReason: String?,
    ) {
        val hits = AtomicLong(1L)
    }

    private val pages = LinkedHashMap<Long, ByteArray>()
    private var lastPageBase: Long = -1L
    private var lastPageBytes: ByteArray? = null
    private var lastMmioKey: Long = -1L
    private var lastMmioEntry: MmioEntry? = null
    private val mmioEntries = ConcurrentHashMap<Long, MmioEntry>()
    private val flashEntries = ConcurrentHashMap<String, FlashEntry>()
    private val mmioOrder = AtomicInteger(0)
    private val flashOrder = AtomicInteger(0)
    private val summaryEmitted = AtomicBoolean(false)

    @Volatile private var readCount: Long = 0L
    @Volatile private var writeCount: Long = 0L
    @Volatile private var deviceFallbackCount: Long = 0L
    @Volatile private var discoveryFallbackCount: Long = 0L
    @Volatile private var flashRefusalCount: Long = 0L
    @Volatile private var flashOperationCount: Long = 0L
    @Volatile private var capacityTruncated: Boolean = false
    @Volatile private var mmioLogTruncated: Boolean = false
    @Volatile private var flashLogTruncated: Boolean = false
    @Volatile private var lastAddress: Long? = null

    // Lightweight shadow of soc-flash & soc-dma address/size registers for causal [RELAXED/FLASH] logs.
    private var flashReadAddr: Long = 0L
    private var flashReadSize: Long = 0L
    private var flashProgAddr: Long = 0L
    private var flashProgSize: Long = 0L
    private var flashEraseAddr: Long = 0L
    private val dmaCount = LongArray(16)
    private val dmaSrc = LongArray(16)
    private val dmaDst = LongArray(16)

    private fun lookupPage(pageBase: Long): ByteArray? {
        if (pageBase == lastPageBase) return lastPageBytes
        val p = pages[pageBase]
        if (p != null) {
            lastPageBase = pageBase
            lastPageBytes = p
        }
        return p
    }

    private fun lookupOrAllocatePage(pageBase: Long): ByteArray? {
        if (pageBase == lastPageBase) {
            val cached = lastPageBytes
            if (cached != null) return cached
        }
        val existing = pages[pageBase]
        if (existing != null) {
            lastPageBase = pageBase
            lastPageBytes = existing
            return existing
        }
        val created = allocatePage(pageBase) ?: return null
        lastPageBase = pageBase
        lastPageBytes = created
        return created
    }

    fun read(address: Long, size: Int, source: String = "unmapped", reason: String? = null): Long {
        require(size == 1 || size == 2 || size == 4 || size == 8) { "Unsupported relaxed MMIO read size $size" }
        readCount++
        recordFallback(source, address, false, size, 0L, reason)
        val off = (address and PAGE_OFFSET_MASK).toInt()
        if (off + size <= PAGE_SIZE) {
            val page = lookupPage(address and PAGE_MASK) ?: return 0L
            return when (size) {
                4 -> (page[off].toLong() and 0xffL) or
                    ((page[off + 1].toLong() and 0xffL) shl 8) or
                    ((page[off + 2].toLong() and 0xffL) shl 16) or
                    ((page[off + 3].toLong() and 0xffL) shl 24)
                2 -> (page[off].toLong() and 0xffL) or ((page[off + 1].toLong() and 0xffL) shl 8)
                1 -> page[off].toLong() and 0xffL
                else -> (page[off].toLong() and 0xffL) or
                    ((page[off + 1].toLong() and 0xffL) shl 8) or
                    ((page[off + 2].toLong() and 0xffL) shl 16) or
                    ((page[off + 3].toLong() and 0xffL) shl 24) or
                    ((page[off + 4].toLong() and 0xffL) shl 32) or
                    ((page[off + 5].toLong() and 0xffL) shl 40) or
                    ((page[off + 6].toLong() and 0xffL) shl 48) or
                    ((page[off + 7].toLong() and 0xffL) shl 56)
            }
        }
        var value = 0L
        for (i in 0 until size) {
            val byteAddr = address + i
            val page = lookupPage(byteAddr and PAGE_MASK)
            val b = if (page != null) page[(byteAddr and PAGE_OFFSET_MASK).toInt()].toLong() and 0xffL else 0L
            value = value or (b shl (i * 8))
        }
        return value
    }

    fun write(address: Long, size: Int, value: Long, source: String = "unmapped", reason: String? = null) {
        require(size == 1 || size == 2 || size == 4 || size == 8) { "Unsupported relaxed MMIO write size $size" }
        writeCount++
        recordFallback(source, address, true, size, value, reason)
        val off = (address and PAGE_OFFSET_MASK).toInt()
        if (off + size <= PAGE_SIZE) {
            val page = lookupOrAllocatePage(address and PAGE_MASK) ?: return
            when (size) {
                4 -> {
                    page[off] = (value and 0xffL).toByte()
                    page[off + 1] = ((value ushr 8) and 0xffL).toByte()
                    page[off + 2] = ((value ushr 16) and 0xffL).toByte()
                    page[off + 3] = ((value ushr 24) and 0xffL).toByte()
                }
                2 -> {
                    page[off] = (value and 0xffL).toByte()
                    page[off + 1] = ((value ushr 8) and 0xffL).toByte()
                }
                1 -> page[off] = (value and 0xffL).toByte()
                else -> {
                    for (i in 0 until 8) page[off + i] = ((value ushr (i * 8)) and 0xffL).toByte()
                }
            }
            return
        }
        for (i in 0 until size) {
            val byteAddr = address + i
            val page = lookupOrAllocatePage(byteAddr and PAGE_MASK) ?: continue
            page[(byteAddr and PAGE_OFFSET_MASK).toInt()] = ((value ushr (i * 8)) and 0xffL).toByte()
        }
    }

    /**
     * Passive observer called ONLY in `relaxed` mode after a modeled MMIO write succeeds.
     * Tracks flash/DMA address registers with zero allocation and logs deduplicated flash ops.
     */
    fun observeModeledWrite(address: Long, size: Int, value: Long) {
        val layout = flashLayout ?: return
        if (size != 4) return
        if (layout.inRegisters(address)) {
            when ((address - layout.registersBase).toInt()) {
                0x68 -> flashReadAddr = value
                0x6c -> flashReadSize = value
                0x78 -> flashProgAddr = value
                0x7c -> flashProgSize = value
                0x94 -> flashEraseAddr = value
                0x60 -> if (value == 1L) {
                    recordFlash(
                        op = "mapped-read",
                        flashOffset = toFlashOffset(layout, flashReadAddr),
                        size = flashReadSize,
                        refused = false,
                        reason = null,
                    )
                }
                0x70 -> if (value == 1L) {
                    recordFlash(
                        op = "program",
                        flashOffset = toFlashOffset(layout, flashProgAddr),
                        size = flashProgSize,
                        refused = false,
                        reason = null,
                    )
                }
                0x90 -> if ((value and 1L) != 0L) {
                    val cmd = value ushr 24
                    if (cmd == 0x21L || cmd == 0xdcL) {
                        val eraseSize = if (cmd == 0xdcL) 65536L else 4096L
                        recordFlash(
                            op = "erase",
                            flashOffset = toFlashOffset(layout, flashEraseAddr),
                            size = eraseSize,
                            refused = false,
                            reason = null,
                        )
                    }
                }
            }
            return
        }
        if (layout.inDmaRegisters(address)) {
            val rel = address - layout.dmaRegistersBase
            val ch = (rel ushr 12).toInt()
            if (ch in 0..15) {
                when ((rel and 0xfffL).toInt()) {
                    0x10 -> dmaCount[ch] = value
                    0x14 -> dmaSrc[ch] = value
                    0x18 -> dmaDst[ch] = value
                }
            }
        }
    }

    private fun allocatePage(pageBase: Long): ByteArray? {
        if (pages.size >= maxPages) {
            capacityTruncated = true
            return null
        }
        val created = ByteArray(PAGE_SIZE)
        pages[pageBase] = created
        return created
    }

    private fun recordFallback(
        source: String,
        address: Long,
        write: Boolean,
        size: Int,
        value: Long,
        reason: String?,
    ) {
        lastAddress = address
        when (source) {
            "device" -> deviceFallbackCount++
            "discovery" -> discoveryFallbackCount++
        }
        val layout = flashLayout
        if (layout != null && source == "device" && tryRecordFlashRefusal(layout, address, write, size, value, reason)) {
            return
        }
        val key = mmioKey(source, address, write)
        val cachedEntry = if (key == lastMmioKey) lastMmioEntry else null
        if (cachedEntry != null) {
            cachedEntry.hits.incrementAndGet()
            return
        }
        val existing = mmioEntries[key]
        if (existing != null) {
            lastMmioKey = key
            lastMmioEntry = existing
            existing.hits.incrementAndGet()
            return
        }
        if (mmioEntries.size >= MAX_LOGGED_ADDRESSES) {
            mmioLogTruncated = true
            return
        }
        val pc = context?.pc() ?: 0L
        val tick = context?.tick() ?: 0L
        val stage = context?.stage() ?: "UNKNOWN"
        val entry = MmioEntry(
            order = mmioOrder.incrementAndGet(),
            source = source,
            address = address,
            write = write,
            firstSize = size,
            firstValue = value,
            firstPc = pc,
            firstTick = tick,
            firstStage = stage,
            firstReason = reason,
        )
        val raced = mmioEntries.putIfAbsent(key, entry)
        if (raced != null) {
            raced.hits.incrementAndGet()
            return
        }
        emitWarn(formatMmioLine(entry))
        telemetryPublisher?.recordMmioFallback(
            source = entry.source,
            write = entry.write,
            address = entry.address,
            size = entry.firstSize,
            value = entry.firstValue,
            pc = entry.firstPc,
            tick = entry.firstTick,
            stage = entry.firstStage,
            reason = entry.firstReason,
        )
    }

    private fun tryRecordFlashRefusal(
        layout: FlashLayout,
        address: Long,
        write: Boolean,
        size: Int,
        value: Long,
        reason: String?,
    ): Boolean {
        if (layout.inAperture(address)) {
            recordFlash(
                op = if (write) "aperture-write-refused" else "aperture-read-refused",
                flashOffset = address - layout.apertureBase,
                size = size.toLong(),
                refused = true,
                reason = reason,
            )
            return true
        }
        if (write && size == 4 && layout.inRegisters(address)) {
            return when ((address - layout.registersBase).toInt()) {
                0x60 -> if (value == 1L) {
                    recordFlash("mapped-read-refused", toFlashOffset(layout, flashReadAddr), flashReadSize, true, reason)
                    true
                } else {
                    false
                }
                0x70 -> if (value == 1L) {
                    recordFlash("program-refused", toFlashOffset(layout, flashProgAddr), flashProgSize, true, reason)
                    true
                } else {
                    false
                }
                0x90 -> {
                    val cmd = value ushr 24
                    if ((value and 1L) != 0L && (cmd == 0x21L || cmd == 0xdcL)) {
                        val eraseSize = if (cmd == 0xdcL) 65536L else 4096L
                        recordFlash("erase-refused", toFlashOffset(layout, flashEraseAddr), eraseSize, true, reason)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        if (write && size == 4 && layout.inDmaRegisters(address)) {
            val rel = address - layout.dmaRegistersBase
            val ch = (rel ushr 12).toInt()
            val reg = (rel and 0xfffL).toInt()
            if (ch in 0..15 && reg == 0x28 && (value and 0x10000001L) == 0x10000001L) {
                val src = dmaSrc[ch]
                val dst = dmaDst[ch]
                val count = dmaCount[ch] + 1L
                if (layout.inAperture(src)) {
                    recordFlash("dma-read-refused", src - layout.apertureBase, count, true, reason)
                    return true
                }
                if (layout.inAperture(dst)) {
                    recordFlash("dma-write-refused", dst - layout.apertureBase, count, true, reason)
                    return true
                }
            }
        }
        return false
    }

    private fun recordFlash(
        op: String,
        flashOffset: Long,
        size: Long,
        refused: Boolean,
        reason: String?,
    ) {
        if (refused) flashRefusalCount++ else flashOperationCount++
        val sector = flashOffset and FLASH_SECTOR_MASK
        val key = "$op:$sector"
        val existing = flashEntries[key]
        if (existing != null) {
            existing.hits.incrementAndGet()
            return
        }
        if (flashEntries.size >= MAX_LOGGED_FLASH_ENTRIES) {
            flashLogTruncated = true
            return
        }
        val pc = context?.pc() ?: 0L
        val tick = context?.tick() ?: 0L
        val stage = context?.stage() ?: "UNKNOWN"
        val entry = FlashEntry(
            order = flashOrder.incrementAndGet(),
            op = op,
            sector = sector,
            firstOffset = flashOffset,
            firstSize = size,
            refused = refused,
            firstPc = pc,
            firstTick = tick,
            firstStage = stage,
            firstReason = reason,
        )
        val raced = flashEntries.putIfAbsent(key, entry)
        if (raced != null) {
            raced.hits.incrementAndGet()
            return
        }
        val line = formatFlashLine(entry)
        if (refused) emitWarn(line) else emitDebug(line)
        telemetryPublisher?.recordFlashEvent(
            op = entry.op,
            flashOffset = entry.firstOffset,
            sector = entry.sector,
            size = entry.firstSize,
            refused = entry.refused,
            pc = entry.firstPc,
            tick = entry.firstTick,
            stage = entry.firstStage,
            reason = entry.firstReason,
        )
    }

    /**
     * Thread-safe, pure read-only compact summary of deduplicated unmodeled MMIO & Flash hits.
     * Safe to invoke from [FirmwareWatchdog] or on session close without acquiring the owner lock.
     */
    /** Concise single-line `[RELAXED/SUMMARY]` totals header. */
    fun formatSummaryHeader(): String = StringBuilder()
        .append("[RELAXED/SUMMARY] ")
        .append("mmioUnique=").append(mmioEntries.size)
        .append(" (reads=").append(readCount)
        .append(", writes=").append(writeCount)
        .append(", deviceFallbacks=").append(deviceFallbackCount)
        .append(", discoveryFallbacks=").append(discoveryFallbackCount)
        .append(", logTruncated=").append(mmioLogTruncated)
        .append(") flashUnique=").append(flashEntries.size)
        .append(" (refused=").append(flashRefusalCount)
        .append(", ops=").append(flashOperationCount)
        .append(", logTruncated=").append(flashLogTruncated)
        .append(')')
        .toString()

    /** Itemized `mmio[1..N]` / `flash[1..N]` table (emitted at `DEBUG` on shutdown), or `null` if empty. */
    fun formatSummaryDetails(maxEntriesPerKind: Int = DEFAULT_SUMMARY_LIMIT): String? {
        val mmioSorted = mmioEntries.values.sortedWith(
            compareByDescending<MmioEntry> { it.hits.get() }.thenBy { it.order },
        )
        val flashSorted = flashEntries.values.sortedWith(
            compareByDescending<FlashEntry> { it.hits.get() }.thenBy { it.order },
        )
        if (mmioSorted.isEmpty() && flashSorted.isEmpty()) return null
        val sb = StringBuilder()
        val mmioShown = minOf(mmioSorted.size, maxEntriesPerKind)
        for (i in 0 until mmioShown) {
            val e = mmioSorted[i]
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  mmio[").append(i + 1).append("] ")
                .append("source=").append(e.source)
                .append(" op=").append(if (e.write) "WRITE" else "READ")
                .append(" addr=").append(hex32(e.address))
                .append(" size=").append(e.firstSize)
            if (e.write) {
                sb.append(" val=").append(hexValue(e.firstValue))
            }
            sb.append(" hits=").append(e.hits.get())
                .append(" firstPc=").append(hex32(e.firstPc))
                .append(" stage=").append(e.firstStage)
            if (!e.firstReason.isNullOrBlank()) {
                sb.append(" reason=\"").append(sanitizeReason(e.firstReason)).append('"')
            }
        }
        if (mmioSorted.size > mmioShown) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  ... (").append(mmioSorted.size - mmioShown).append(" more mmio entries omitted)")
        }

        val flashShown = minOf(flashSorted.size, maxEntriesPerKind)
        for (i in 0 until flashShown) {
            val e = flashSorted[i]
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  flash[").append(i + 1).append("] ")
                .append("op=").append(e.op)
                .append(" flashOffset=").append(hex32(e.firstOffset))
                .append(" sector=").append(hex32(e.sector))
                .append(" size=").append(e.firstSize)
                .append(" hits=").append(e.hits.get())
                .append(" firstPc=").append(hex32(e.firstPc))
                .append(" stage=").append(e.firstStage)
            if (!e.firstReason.isNullOrBlank()) {
                sb.append(" reason=\"").append(sanitizeReason(e.firstReason)).append('"')
            }
        }
        if (flashSorted.size > flashShown) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  ... (").append(flashSorted.size - flashShown).append(" more flash entries omitted)")
        }
        return sb.toString()
    }

    fun formatSummary(maxEntriesPerKind: Int = DEFAULT_SUMMARY_LIMIT): String {
        val header = formatSummaryHeader()
        val details = formatSummaryDetails(maxEntriesPerKind)
        return if (details == null) header else "$header\n$details"
    }

    /**
     * Emits `[RELAXED/SUMMARY]` at most once per session lifecycle:
     * - Clean runs (`mmioUnique == 0 && flashRefused == 0`): concise 1-line totals at `DEBUG`
     *   so normal sessions emit zero `[RELAXED/...]` noise on the `INFO` console.
     * - Runs with unmodeled MMIO or refused flash accesses (`mmioUnique > 0 || flashRefused > 0`):
     *   concise 1-line totals at `WARN` (visible on both console and `latest.log`).
     * - Itemized per-address `mmio[1..N]` / `flash[1..N]` table always at `DEBUG` (`latest.log` forensic trail).
     */
    fun logSummaryOnClose() {
        if (!summaryEmitted.compareAndSet(false, true)) return
        val header = formatSummaryHeader()
        if (mmioEntries.isEmpty() && flashRefusalCount == 0L) {
            emitDebug(header)
        } else {
            emitWarn(header)
        }
        formatSummaryDetails()?.let { details ->
            emitDebug("[RELAXED/SUMMARY/DETAILS]\n$details")
        }
    }

    fun summary(): Map<String, Any?> = linkedMapOf(
        "basis" to "HYPOTHESIS: relaxed-unmodeled-peripheral-fallback",
        "reads" to readCount,
        "writes" to writeCount,
        "deviceFallbacks" to deviceFallbackCount,
        "discoveryFallbacks" to discoveryFallbackCount,
        "retainedPages" to pages.size,
        "uniqueLoggedAddresses" to mmioEntries.size,
        "uniqueFlashEntries" to flashEntries.size,
        "flashRefusals" to flashRefusalCount,
        "flashOperations" to flashOperationCount,
        "capacityTruncated" to capacityTruncated,
        "lastAddress" to lastAddress?.let { hex32(it) },
    )

    private fun formatMmioLine(entry: MmioEntry): String {
        val sb = StringBuilder(140)
        sb.append("[RELAXED/MMIO] source=").append(entry.source)
            .append(" op=").append(if (entry.write) "WRITE" else "READ")
            .append(" addr=").append(hex32(entry.address))
            .append(" size=").append(entry.firstSize)
        if (entry.write) {
            sb.append(" val=").append(hexValue(entry.firstValue))
        }
        sb.append(" pc=").append(hex32(entry.firstPc))
            .append(" tick=").append(entry.firstTick)
            .append(" stage=").append(entry.firstStage)
        if (!entry.firstReason.isNullOrBlank()) {
            sb.append(" reason=\"").append(sanitizeReason(entry.firstReason)).append('"')
        }
        return sb.toString()
    }

    private fun formatFlashLine(entry: FlashEntry): String {
        val sb = StringBuilder(140)
        sb.append("[RELAXED/FLASH] op=").append(entry.op)
            .append(" flashOffset=").append(hex32(entry.firstOffset))
            .append(" sector=").append(hex32(entry.sector))
            .append(" size=").append(entry.firstSize)
            .append(" pc=").append(hex32(entry.firstPc))
            .append(" tick=").append(entry.firstTick)
            .append(" stage=").append(entry.firstStage)
        if (!entry.firstReason.isNullOrBlank()) {
            sb.append(" reason=\"").append(sanitizeReason(entry.firstReason)).append('"')
        }
        return sb.toString()
    }

    private fun emitWarn(message: String) {
        val sink = warnSink
        if (sink != null) sink(message) else logger.warn(message)
    }

    private fun emitInfo(message: String) {
        val sink = infoSink
        if (sink != null) sink(message) else logger.info(message)
    }

    private fun emitDebug(message: String) {
        val sink = debugSink
        if (sink != null) sink(message) else logger.debug(message)
    }

    private fun toFlashOffset(layout: FlashLayout, rawAddress: Long): Long =
        if (rawAddress >= layout.apertureBase) rawAddress - layout.apertureBase else rawAddress

    private fun mmioKey(source: String, address: Long, write: Boolean): Long {
        val sourceCode = when (source) {
            "unmapped" -> 0L
            "device" -> 1L
            "discovery" -> 2L
            "router" -> 3L
            else -> (source.hashCode().toLong() and 0x7fffffffL) + 4L
        }
        val writeBit = if (write) 1L shl 32 else 0L
        return (sourceCode shl 33) or writeBit or (address and 0xffffffffL)
    }

    companion object {
        const val MODE = "relaxed"
        const val PERIPHERAL_APERTURE_BASE = 0xff000000L
        const val PERIPHERAL_APERTURE_END = 0x1_0000_0000L
        const val PAGE_SIZE = 4096
        private const val PAGE_MASK = -4096L
        private const val PAGE_OFFSET_MASK = 4095L
        private const val FLASH_SECTOR_MASK = -4096L
        const val DEFAULT_MAX_PAGES = 256 // 1 MiB retained unmodeled register cap
        private const val MAX_LOGGED_ADDRESSES = 512
        private const val MAX_LOGGED_FLASH_ENTRIES = 512
        private const val DEFAULT_SUMMARY_LIMIT = 64

        internal fun hex32(value: Long): String = "0x%08x".format(value and 0xffffffffL)

        internal fun hexValue(value: Long): String = "0x" + java.lang.Long.toHexString(value)

        internal fun sanitizeReason(raw: String): String =
            raw.replace('\r', ' ').replace('\n', ' ').replace('"', '\'').trim()

        /** Subtract excluded [base, end) intervals (e.g. RAM/ROM) from [start, end). */
        fun subtractIntervals(start: Long, end: Long, excluded: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            require(start <= end)
            val sorted = excluded
                .map { (a, b) -> maxOf(start, a) to minOf(end, b) }
                .filter { (a, b) -> a < b }
                .sortedBy { it.first }
            val result = ArrayList<Pair<Long, Long>>()
            var cursor = start
            for ((exStart, exEnd) in sorted) {
                if (exStart > cursor) {
                    result.add(cursor to (exStart - cursor))
                }
                if (exEnd > cursor) {
                    cursor = exEnd
                }
            }
            if (cursor < end) {
                result.add(cursor to (end - cursor))
            }
            return result
        }
    }
}
