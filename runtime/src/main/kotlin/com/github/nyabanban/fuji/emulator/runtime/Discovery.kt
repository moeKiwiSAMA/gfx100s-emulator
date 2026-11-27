package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.SensorPort
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Only these finite, explicitly configured input laws may answer an unknown access. */
enum class DiscoveryStrategy(val id: String) {
    REFUSE("refuse"), IMAGE("image-backed"), FIXED("fixed-input"), RETAINED("retained-bytes"), SCRIPT("scripted-read"),
    PANEL_ONCE("panel-init-once-v1"), PANEL_TWICE("panel-init-twice-v1"), POLL_7801C("fff7801c-write1-read0-once-v1"),
    SETUP_REPEAT_7801C("fff7801c-setup-repeat-v1"),
    SETUP_REPEATABLE_7801C("fff7801c-write1-read0-repeatable-v1"),
    IMAGE_50000_CONTROL("write0-read1-once-v1"),
    IMAGE_50000_CONTROL_TWICE("write0-read1-twice-v1"),
    IMAGE_50000_CONTROL_REPEATABLE("write0-read1-repeatable-v1"),
    IMAGE_70000("setup-polls-once-v1"),
    IMAGE_70000_CONFIG("setup-polls-config-once-v1"),
    IMAGE_70000_UPDATE("setup-polls-config-update-once-v1"),
    IMAGE_70000_DRIVER("setup-polls-config-driver-irq106-once-v1"),
    IMAGE_70000_GETTERS("setup-polls-config-driver-getters-twice-v1"),
    IMAGE_70000_TEARDOWN("setup-polls-config-driver-getters-twice-teardown-v1"),
    IMAGE_70000_REINIT("teardown-then-second-setup-config-v1"),
    IMAGE_70000_CONFIG_READS("second-setup-config-read-zero-once-v1"),
    IMAGE_70000_SETTER(T79ImageConfigSetter.PROFILE),
    IMAGE_70000_SECOND_UPDATE(T79ImageSecondUpdate.PROFILE),
    IMAGE_70000_SECOND_DRIVER_GETTERS(Image70000.SECOND_DRIVER_GETTERS_PROFILE),
    IMAGE_70000_STATEFUL_BANK(T79StatefulIspBank.PROFILE),
    IMAGE_6E_CONFIG("config-pairs-once-v1"), IMAGE_6E_STOP("config-pairs-stop-poll-once-v1"),
    IMAGE_6E_DISABLE("config-pairs-disable-bit7-once-v1"),
    IMAGE_6E_SECOND_CONFIG("config-pairs-disable-bit7-second-config-once-v1"),
    IMAGE_6E_REPEATABLE(Image6e000.REPEATABLE_PROFILE),
    IMAGE_68000("descriptor-setup-once-v1"),
    IMAGE_68000_TWICE("descriptor-setup-twice-v1"),
    IMAGE_68000_REPEATABLE("descriptor-setup-repeatable-v1"),
    IMAGE_RESULT_SELECTOR10("image-backed-selector10-zero-v1"),
    IMAGE_RESULT_SELECTOR10_REPEATABLE("image-backed-selector10-repeatable-v1"),
    CHANNEL_CONTROL("control-readback-after-write-v1"),
    INTERLEAVED_REPLY("scripted-pairs-with-one-820001-v1"),
    PROGRAMMED_REPLY("committed-81-read-once-v1"),
    PROGRAMMED_REPLY_REPEATABLE(ProgrammedImageReply.REPEATABLE_PROFILE),
    IMAGE_TABLE_PAIR("packed-table-pair-once-v1"),
    IMAGE_TABLE_PAIR_TWICE("packed-table-pair-twice-v1"),
    IMAGE_TABLE_PAIR_REPEATABLE("packed-table-pair-repeatable-v1"),
    IMAGE_LIVEVIEW_BANKS(ImageLiveViewBanks.PROFILE),
    IMAGE_SCALER_F000(ImageScalerF000.PROFILE),
    IMAGE_FACE_7C000(ImageFace7c000.IRQ130_PROFILE),
    BOOTROM_COLD_START(BootRomColdStart.PROFILE),
    IMAGE_SCALER_E000(ImageScalerE000.PROFILE),
    WRITE_49530("fff49530-write-pair-once-v1"),
    WRITE_49530_REPEATABLE("fff49530-write-pair-repeatable-v1"),
    TAIL_49500("fff49534-49500-write-tail-once-v1"),
    TAIL_49500_REPEATABLE("fff49534-49500-write-tail-repeatable-v1");
    companion object { fun parse(id: String) = entries.singleOrNull { it.id == id } ?: error("Unknown discovery strategy: " + id) }
    val interleavesReply get() = this == INTERLEAVED_REPLY || isProgrammedReply
    val isProgrammedReply get() = this == PROGRAMMED_REPLY || this == PROGRAMMED_REPLY_REPEATABLE
    val isImageResultSelector10 get() = this == IMAGE_RESULT_SELECTOR10 || this == IMAGE_RESULT_SELECTOR10_REPEATABLE
}
data class AddressInterval(val base: Long, val endExclusive: Long) {
    init { require(base >= 0 && base < endExclusive && endExclusive <= 0x1_0000_0000L) }
    fun overlaps(base: Long, end: Long) = this.base < end && base < endExclusive
}
/** No seed: one accepted guest write supplies the only readable value. No clock/event interface. */
data class RetainedWriteDiagnostic(val writeValue: Long, val successfulReadLimit: Int) {
    init { require(writeValue == 1L && successfulReadLimit in 1..2097152) }
}
data class DiscoveryRegion(
    val id: String, val deviceId: String, val base: Long, val length: Int,
    val strategy: DiscoveryStrategy, val widths: Set<Int>, val alignment: Int,
    val littleEndian: Boolean, val initial: ByteArray? = null, val fixedValue: Long? = null,
    val samples: List<Long?>? = null, val prohibited: List<AddressInterval> = emptyList(),
    val evidence: List<String> = emptyList(),
    val readAllowed: IntArray? = null, val writeAllowed: IntArray? = null,
    val retainedDiagnostic: RetainedWriteDiagnostic? = null,
    val selectionValues: List<Long>? = null,
    val clearC204Start: Boolean = false,
    val faceStatusReady: Boolean = false,
    val dspFa00StatusReady: Boolean = false,
) {
    val endExclusive = Math.addExact(base, length.toLong())
    init {
        require(id.isNotBlank() && deviceId.isNotBlank())
        require(length in 1..1048576 && base >= 0 && endExclusive <= 0x1_0000_0000L)
        require(widths.isNotEmpty() && widths.all { it in setOf(1,2,4) })
        require(alignment in setOf(1,2,4) && base % alignment == 0L)
        prohibited.forEach { require(it.base >= base && it.endExclusive <= endExclusive) }
        prohibited.sortedBy { it.base }.zipWithNext().forEach { (a,b) -> require(a.endExclusive <= b.base) }
        require(readAllowed == null || readAllowed.size == length)
        if (clearC204Start) require(id == RETAINED_6C204_REGION && base == 0xfff6c204L &&
            length == 8 && strategy == DiscoveryStrategy.RETAINED && widths == setOf(4) && littleEndian && initial != null)
        if (faceStatusReady) require(id == FACE_7C000_REGION && base == 0xfff7c000L &&
            length == 4096 && strategy == DiscoveryStrategy.RETAINED && widths == setOf(4) && alignment == 4 && littleEndian && initial?.size == 4096)
        if (dspFa00StatusReady) require(id == PLAY_DSP_FA00_REGION && base == 0xfa000000L &&
            length == 4096 && strategy == DiscoveryStrategy.RETAINED && widths == setOf(1, 2, 4) && alignment == 1 && littleEndian && initial?.size == 4096)
        require(writeAllowed == null || writeAllowed.size == length)
        require(selectionValues == null || (strategy == DiscoveryStrategy.FIXED && selectionValues.isNotEmpty() &&
            selectionValues.size <= 65536 && selectionValues.distinct().size == selectionValues.size && selectionValues.all { it in 0..0xffffffffL })) {
            "Fixed selection requires a nonempty distinct finite unsigned word list"
        }
        when(strategy) {
            DiscoveryStrategy.INTERLEAVED_REPLY, DiscoveryStrategy.PROGRAMMED_REPLY, DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE -> require(id == IMAGE_REPLY_PAIRS && base == 0xfff6c224L &&
                length == 32 && widths == setOf(4) && alignment == 4 && littleEndian && initial == null && fixedValue == null &&
                samples?.size == IMAGE_REPLY_TOTAL_WORDS + 8 && samples.withIndex().all { (index, sample) ->
                    sample == 0L || (index == IMAGE_REPLY_TOTAL_WORDS && sample == 0x00af0000L)
                })
            DiscoveryStrategy.CHANNEL_CONTROL -> require(channelControlAddresses[id] == base &&
                length == 4 && widths == setOf(4) && alignment == 4 && littleEndian && initial?.size == 4 &&
                fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE_RESULT_SELECTOR10, DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE -> {
                require(id == IMAGE_RESULT_SURFACE && base == 0xfff11060L && length == 0x1980 &&
                    widths == setOf(4) && alignment == 4 && littleEndian && initial?.size == length &&
                    fixedValue == null && samples == null)
                requireNotNull(initial)
                for (address in SELECTOR10_WORDS step 4) {
                    val offset = (address - base).toInt()
                    require(readAllowed?.get(offset) == 4 && writeAllowed?.get(offset) == 0 &&
                        prohibited.none { it.overlaps(address, address + 4) }) { "Dictionary must declare selector 10 READ4" }
                    require((offset until offset + 4).all { initial[it] == 0.toByte() }) { "Selector 10 requires an all-zero image slice" }
                }
            }
            DiscoveryStrategy.IMAGE_TABLE_PAIR, DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE,
            DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> require(
                ((id == Image51000.TABLE && base == Image51000.BASE && length == Image51000.tableWords(strategy) * 4) ||
                    (id == Image51000.EMPTY && base == Image51000.EMPTY_BASE && length == Image51000.emptyWords(strategy) * 4)) &&
                    widths == setOf(4) && alignment == 4 && littleEndian && initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS -> require(ImageLiveViewBanks.validRegion(this))
            DiscoveryStrategy.IMAGE_SCALER_F000 -> require(ImageScalerF000.validRegion(this))
            DiscoveryStrategy.IMAGE_FACE_7C000 -> require(ImageFace7c000.validRegion(this))
            DiscoveryStrategy.BOOTROM_COLD_START -> require(BootRomColdStart.validRegion(this))
            DiscoveryStrategy.IMAGE_SCALER_E000 -> require(ImageScalerE000.validRegion(this))
            DiscoveryStrategy.IMAGE_68000, DiscoveryStrategy.IMAGE_68000_TWICE,
            DiscoveryStrategy.IMAGE_68000_REPEATABLE -> require(id == Image68000.REGION && base == Image68000.BASE &&
                length == Image68000.SIZE && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE_6E_CONFIG, DiscoveryStrategy.IMAGE_6E_STOP, DiscoveryStrategy.IMAGE_6E_DISABLE,
            DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG, DiscoveryStrategy.IMAGE_6E_REPEATABLE -> require(id == Image6e000.REGION &&
                base == Image6e000.BASE && length == Image6e000.SIZE && widths == setOf(4) && alignment == 4 &&
                littleEndian && initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE_70000, DiscoveryStrategy.IMAGE_70000_CONFIG, DiscoveryStrategy.IMAGE_70000_UPDATE,
            DiscoveryStrategy.IMAGE_70000_DRIVER, DiscoveryStrategy.IMAGE_70000_GETTERS,
            DiscoveryStrategy.IMAGE_70000_TEARDOWN, DiscoveryStrategy.IMAGE_70000_REINIT,
            DiscoveryStrategy.IMAGE_70000_CONFIG_READS, DiscoveryStrategy.IMAGE_70000_SETTER,
            DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE,
            DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS,
            DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK -> require(id == Image70000.REGION && base == Image70000.BASE &&
                length == Image70000.SIZE && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE_50000_CONTROL, DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE,
            DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE -> require(id == IMAGE_50000_CONTROL_REGION && base == 0xfff50000L &&
                length == 4 && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.TAIL_49500, DiscoveryStrategy.TAIL_49500_REPEATABLE -> require(id == TAIL_49500_REGION && base == 0xfff49500L &&
                length == 4 && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.WRITE_49530, DiscoveryStrategy.WRITE_49530_REPEATABLE -> require(id == WRITE_49530_REGION && base == 0xfff49530L &&
                length == 8 && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.POLL_7801C, DiscoveryStrategy.SETUP_REPEAT_7801C,
            DiscoveryStrategy.SETUP_REPEATABLE_7801C -> require(id == POLL_7801C_REGION && base == 0xfff7801cL &&
                length == 4 && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.PANEL_ONCE, DiscoveryStrategy.PANEL_TWICE -> require(id == PanelInitOnce.REGION && base == PanelInitOnce.BASE &&
                length == 24 && widths == setOf(4) && alignment == 4 && littleEndian &&
                initial == null && fixedValue == null && samples == null)
            DiscoveryStrategy.IMAGE, DiscoveryStrategy.RETAINED -> require((initial?.size == length || (strategy == DiscoveryStrategy.RETAINED && retainedDiagnostic != null && initial == null)) && fixedValue == null && samples == null) { "Explicit exact bytes or write-first diagnostic required" }
            DiscoveryStrategy.FIXED -> require((fixedValue != null) != (selectionValues != null) && (fixedValue == null || fixedValue in 0..0xffffffffL) && initial == null && samples == null) { "Exactly one explicit fixed value or selection list required" }
            DiscoveryStrategy.SCRIPT -> require(samples != null && samples.size <= 65536 && samples.all { it == null || it in 0..0xffffffffL } && initial == null && fixedValue == null) { "Explicit finite script required" }
            DiscoveryStrategy.REFUSE -> require(initial == null && fixedValue == null && samples == null)
        }
        if (retainedDiagnostic != null) require(strategy == DiscoveryStrategy.RETAINED && initial == null && length == 4 && widths == setOf(4) && alignment == 4 && littleEndian)
    }
}

internal const val POLL_7801C_REGION = "gfx100s.fff7801c.poll.candidate"
internal const val IMAGE_50000_CONTROL_REGION = "diagnostic.image-control.fff50000"
internal const val WRITE_49530_REGION = "gfx100s.fff49530.write-pair.candidate"
internal const val TAIL_49500_REGION = "gfx100s.fff49500.write-tail.candidate"
internal const val IMAGE_RESULT_SURFACE = "gfx100s.fff11060.result-surface.candidate"
private val channelControlAddresses = mapOf(
    "diagnostic.channel4.fff3c020" to 0xfff3c020L,
    "diagnostic.channel11.fff3c03c" to 0xfff3c03cL
)
private const val IMAGE_RESULT_READ_LIMIT = 4 * 256 + 2 * 8
internal val SELECTOR10_WORDS = 0xfff12060L until 0xfff121a0L
private const val SELECTOR10_READ_LIMIT = 80
private fun DiscoveryRegion.imageDigest() = java.util.HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(checkNotNull(initial)))
internal const val UART_TAIL_REGION = "gfx100s.fff3a034.uart-tail.candidate"
internal const val RETAINED_6C000_REGION = "gfx100s.fff6c000.words.candidate"
internal const val RETAINED_6C204_REGION = "gfx100s.fff6c204.words.candidate"
internal const val C204_COMPLETION = "start-bit0-clear-after-read-v1"
internal const val RETAINED_6C20C_REGION = "gfx100s.fff6c20c.config-set.candidate"
internal const val RETAINED_6B000_REGION = "gfx100s.fff6b000.config.candidate"
internal const val RETAINED_69000_REGION = "gfx100s.fff69000.config.candidate"
internal const val RETAINED_69100_REGION = "gfx100s.fff69100.config-set.candidate"
internal const val FACE_7C000_REGION = "diagnostic.face.fff7c000"
internal const val FACE_7C000_READY_BITS = 0x01110111L
internal const val PLAY_DSP_FA00_REGION = "diagnostic.play-dsp.fa000000"
internal const val PLAY_DSP_AA10_REGION = "diagnostic.play-dsp.fff4aa10"
internal const val PLAY_DSP_FA00_READY_BIT = 0x00010000L
internal const val PLAY_DSP_FA00_IRQ_LINE = 145
internal const val PLAY_DSP_FA00_DELAY_TICKS = 64L
internal const val IMAGE_59000_TABLES = "diagnostic.image59000.tables"
internal val retainedConfigRegions = setOf(RETAINED_69100_REGION, RETAINED_69000_REGION, RETAINED_6B000_REGION, RETAINED_6C000_REGION, RETAINED_6C204_REGION, RETAINED_6C20C_REGION, IMAGE_59000_TABLES)

/** Context is read only on unknown accesses; no register snapshots or JSON in the access path. */
interface DiscoveryContext {
    fun image6eDisableMode(): Boolean = false
    fun inT79ImageTeardown(): Boolean = false
    fun core(): Int
    fun tick(): Long
    fun pc(): Long
    fun lr(): Long
    fun virtualAddress(): Long = -1L
    fun hasImageRequest82(selector: Int): Boolean = false
    fun imageCommandBytes(address: Long, length: Int): ByteArray? = null
    fun imageIrq106Active(): Boolean = false
    fun imageIrq106LineActive(): Boolean = imageIrq106Active()
    fun interruptSink(): InterruptSink? = null
    fun sensorPort(): SensorPort? = null
    fun facePreviewScaler(): com.github.nyabanban.fuji.emulator.api.FacePreviewScalerPort? = null
    fun dmaMemory(): DmaMemory? = null
    fun lensParameters(): LensParameters? = null
    fun exposurePort(): com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort? = null
}

internal const val IMAGE_REPLY_820001 = "diagnostic.image-reply-820001.zero-once-v1"
internal const val IMAGE_REPLY_820001_AF = "diagnostic.image-reply-820001.af-once-v1"
internal const val IMAGE_REPLY_820040 = "diagnostic.image-reply-820040.zero-once-v1"
internal const val IMAGE_REPLY_820041 = "diagnostic.image-reply-820041.zero-once-v1"
internal const val IMAGE_REPLY_PAIRS = "diagnostic.image-reply-820040.zero-alternating-16-pairs-v1"
internal const val IMAGE_REPLY_PAIR_WORDS = 16
internal const val IMAGE_REPLY_TOTAL_WORDS = 16 * IMAGE_REPLY_PAIR_WORDS
private val imageReplySelectors = mapOf(IMAGE_REPLY_820001 to 1, IMAGE_REPLY_820001_AF to 1,
    IMAGE_REPLY_820040 to 0x40, IMAGE_REPLY_820041 to 0x41)
internal val imageReplyIds = imageReplySelectors.keys + IMAGE_REPLY_PAIRS
private val imageReplyReturnAddresses = longArrayOf(0x11b669c, 0x11b667c, 0x11b665c, 0x11b663c,
    0x11b661c, 0x11b65fc, 0x11b65dc, 0x11b65b8)

private fun pairSelector(position: Int, repeatable: Boolean = false) = when {
    position >= 0 && (repeatable || position < IMAGE_REPLY_TOTAL_WORDS) ->
        if (position % IMAGE_REPLY_PAIR_WORDS < imageReplyReturnAddresses.size) 0x40 else 0x41
    else -> null
}

private fun validatePairCursor(row: Map<*, *>, limit: Int, singleWords: Int = 0,
    strategy: DiscoveryStrategy = DiscoveryStrategy.SCRIPT): Int {
    fun number(key: String): Long {
        val value = row[key]
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }
    val position = number("position")
    require(position in 0L..limit.toLong() && number("accessAttempts") == position + singleWords)
    require(number("base") == 0xfff6c224L && number("endExclusive") == 0xfff6c244L &&
        row["strategy"] == strategy.id &&
        row["retainedWritten"] == false && number("retainedValue") == 0L &&
        row["fixedSelected"] == false && number("selectedValue") == 0L &&
        listOf("mutatedBytesBase64", "scriptTailSelection").all { it in row && row[it] == null })
    return position.toInt()
}

/** Non-overwriting primitive record storage. Full capacity fails before touching model state. */
class DiscoveryJournal(
    val runId: String, val dictionaryVersion: String, val dictionarySha256: String,
    val firmwareSha256: String, val configurationSha256: String,
    val capacity: Int = 16384, val diskBudgetBytes: Long = 32L * 1024 * 1024,
    val allowOverflow: Boolean = false,
) {
    init {
        require(runId.isNotBlank() && dictionaryVersion.isNotBlank())
        require(listOf(dictionarySha256,firmwareSha256,configurationSha256).all { it.matches(Regex("[a-f0-9]{64}")) }) { "Explicit SHA-256 identities required" }
        require(capacity in 1..2228224); require(diskBudgetBytes in 4096..4831838208L)
    }
    // One extra record preserves the first capacity refusal. It can never run guest code again.
    private val sequence = LongArray(capacity + 1)
    private val address = LongArray(capacity + 1)
    private val virtualAddress = LongArray(capacity + 1)
    private val tick = LongArray(capacity + 1)
    private val pc = LongArray(capacity + 1)
    private val lr = LongArray(capacity + 1)
    private val value = LongArray(capacity + 1)
    private val before = LongArray(capacity + 1)
    private val after = LongArray(capacity + 1)
    private val size = IntArray(capacity + 1)
    private val core = IntArray(capacity + 1)
    private val region = IntArray(capacity + 1)
    private val flags = IntArray(capacity + 1)
    private val reason = IntArray(capacity + 1)
    private val nativeKind = IntArray(capacity + 1) { -1 }
    var count = 0; private set
    var attempts = 0L; private set
    private var sealed = false
    fun begin(seq: Long, physical: Long, width: Int, write: Boolean, input: Long, index: Int, context: DiscoveryContext, execute: Boolean = false, nativeAccessKind: Int = -1): Int {
        check(!sealed) { "Discovery journal is sealed after capacity refusal" }
        attempts++
        if (allowOverflow && count >= capacity) return OVERFLOW_SLOT
        val slot = count++
        sequence[slot] = seq; address[slot] = physical; size[slot] = width; region[slot] = index
        virtualAddress[slot] = context.virtualAddress(); core[slot] = context.core(); tick[slot] = context.tick(); pc[slot] = context.pc(); lr[slot] = context.lr()
        flags[slot] = (if (write) 1 else 0) or (if (execute) 8 else 0); value[slot] = input; nativeKind[slot] = nativeAccessKind
        if(slot == capacity) { reason[slot] = LOG_FULL; sealed = true; error("Discovery log capacity exhausted before access") }
        return slot
    }
    fun refuse(slot: Int, code: Int) { if (slot >= 0) reason[slot] = code }
    fun complete(slot: Int, result: Long, prior: Long?, next: Long) {
        if (slot < 0) return
        value[slot] = result; if (prior != null) before[slot] = prior; after[slot] = next; flags[slot] = flags[slot] or 6 or (if (prior != null) 16 else 0)
    }
    fun completeIrq106(slot: Int, result: Long, prior: Long, next: Long, repeatable: Boolean = false) {
        if (slot < 0) return
        complete(slot, result, prior, next)
        flags[slot] = flags[slot] or IRQ106_STATUS or (if (repeatable) IRQ106_REPEATABLE else 0)
    }
    fun metadata():Map<String,Any?> = linkedMapOf(
        "runId" to runId, "dictionaryVersion" to dictionaryVersion, "dictionarySha256" to dictionarySha256,
        "dictionaryDigestKind" to "compiled-payload-sha256",
        "firmwareSha256" to firmwareSha256, "configurationSha256" to configurationSha256,
        "attemptedCount" to attempts, "retainedCount" to count, "unretainedCount" to (attempts-count),
        "capacity" to capacity, "capacityRefusalSlot" to true, "truncated" to (attempts > count),
        "failurePolicy" to "stop-before-mutation", "diskBudgetBytes" to diskBudgetBytes)
    fun snapshot(regions: List<DiscoveryRegion>): Map<String, Any?> = snapshotFrom(regions, 0)
    /** Export-only suffix selection; never changes journal capacity, inputs or guest stopping. */
    fun boundedSnapshot(regions: List<DiscoveryRegion>, maximum: Int): Map<String, Any?> {
        require(maximum in 1..65536)
        val start = maxOf(0, count - maximum)
        return snapshotFrom(regions, start) + mapOf(
            "exportSelection" to "retained-suffix", "exportStartIndex" to start,
            "exportedCount" to count - start, "exportOmittedCount" to start, "exportTruncated" to (start != 0))
    }
    private fun snapshotFrom(regions: List<DiscoveryRegion>, start: Int): Map<String, Any?> = metadata()+mapOf(
        "accesses" to (start until count).map { i ->
            val r = regions.getOrNull(region[i]); val complete = flags[i] and 4 != 0; val write = flags[i] and 1 != 0
            val irq106 = flags[i] and IRQ106_STATUS != 0
            val irq106Repeatable = flags[i] and IRQ106_REPEATABLE != 0
            linkedMapOf<String, Any?>(
                "runId" to runId, "sequence" to sequence[i],
                "regionId" to r?.id, "deviceId" to r?.deviceId, "registerId" to null,
                "addressPhysical" to address[i], "addressVirtual" to virtualAddress[i].takeIf { it >= 0 },
                "direction" to if(flags[i] and 8 != 0) "execute" else if(write) "write" else "read", "nativeKind" to nativeKind[i].takeIf { it >= 0 }, "width" to size[i], "value" to value[i].takeIf { write || complete },
                "valueSource" to if(write) "guest-write" else if(irq106) (if (irq106Repeatable) ImageIrq106Status.REPEATABLE_PROFILE else ImageIrq106Status.PROFILE) else if(complete && r?.id==HdmiStatusSamples.REGION && HdmiStatusSamples.contains(address[i])) "scripted-read" else if(complete && r?.id==ImageLiveViewBanks.FFF72028_REGION && ImageLiveViewBanks.handlesInterior(address[i])) ImageLiveViewBanks.PROFILE else if(complete) r?.strategy?.id else null, "confidence" to if(complete) "HYPOTHESIS" else if(write) "SIM" else "UNKNOWN", "core" to core[i], "tick" to tick[i], "pc" to pc[i], "lr" to lr[i],
                "attempted" to true, "accepted" to (flags[i] and 2 != 0), "completed" to complete,
                "refused" to !complete, "reason" to reasons[reason[i]], "before" to before[i].takeIf { complete && flags[i] and 16 != 0 }, "after" to after[i].takeIf { complete },
                "evidence" to if (irq106) listOf("011B7E4C first read, 011B7F08 guest acknowledgement, 011B7C54 poll; HYPOTHESIS ${if (irq106Repeatable) ImageIrq106Status.REPEATABLE_PROFILE else ImageIrq106Status.PROFILE}") else if (r?.id == ImageLiveViewBanks.FFF72028_REGION && ImageLiveViewBanks.handlesInterior(address[i])) ImageLiveViewBanks.INTERIOR_EVIDENCE else r?.evidence.orEmpty(),
                "contextKind" to if (r?.strategy == DiscoveryStrategy.SCRIPT || r?.strategy?.interleavesReply == true) "sample-index" else "word-value")
        })
    /** Export occurs outside access callbacks. Failure is visible to caller; no partial final file. */
    fun export(path: Path, regions: List<DiscoveryRegion>, observation: Map<String,Any?> = snapshot(regions), maximumBytes: Long = diskBudgetBytes): Map<String, Any?> {
        require(maximumBytes >= 4096)
        val exportBudget = minOf(maximumBytes, diskBudgetBytes)
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, ".discovery-", ".tmp")
        var count=0L
        try {
            object:java.io.FilterOutputStream(Files.newOutputStream(temporary)) {
                override fun write(value:Int) {
                    require(count<exportBudget) { "Discovery export exceeds explicit disk budget" }
                    out.write(value);count++
                }
                override fun write(bytes:ByteArray,offset:Int,length:Int) {
                    require(length.toLong()<=exportBudget-count) { "Discovery export exceeds explicit disk budget" }
                    out.write(bytes,offset,length);count+=length
                }
            }.use { output->
                Formats.json.writerWithDefaultPrettyPrinter().without(com.fasterxml.jackson.core.JsonGenerator.Feature.AUTO_CLOSE_TARGET).writeValue(output,observation)
                output.write(10)
            }
            Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
        return mapOf("path" to path.toString(), "bytes" to count, "sha256" to sha256(path))
    }
    companion object {
        private const val OVERFLOW_SLOT = -1
        const val OUTSIDE=1; const val CROSSING=2; const val WIDTH=3; const val ALIGNMENT=4; const val PROHIBITED=5
        const val KNOWN=6; const val POLICY=7; const val MISSING=8; const val VALUE=9; const val LOG_FULL=10; const val CLOSED=11; const val ROUTER=12; const val DEVICE=13; const val NATIVE=14
        const val READ_BEFORE_WRITE=15; const val REPEATED_WRITE=16; const val DIAGNOSTIC_VALUE=17; const val DIAGNOSTIC_LIMIT=18
        private const val IRQ106_STATUS = 32
        private const val IRQ106_REPEATABLE = 64
        private val reasons = arrayOf<String?>(null,"outside-selected-envelope","cross-boundary","unsupported-width","unaligned","prohibited-hole","known-port-not-active","policy-refuse-or-read-only","finite-input-missing-or-exhausted","input-does-not-fit-width","log-capacity","closed","router-preflight-refusal","known-device-refusal","native-unmapped-or-protected","diagnostic-read-before-write","diagnostic-repeated-write","diagnostic-write-value","diagnostic-limit")
        fun diagnosticReason(code: Int): String? = reasons.getOrNull(code).takeIf { code >= READ_BEFORE_WRITE }
    }
}

class DiscoveryBus(
    regions: List<DiscoveryRegion>, known: List<AddressInterval>, val journal: DiscoveryJournal,
    private val context: DiscoveryContext,
    imageIrq106Selected: Boolean = false,
    imageIrq106Profile: String? = if (imageIrq106Selected) ImageIrq106Status.PROFILE else null,
    imageScalerProfile: String? = null,
    bootRomColdStartProfile: String? = null,
    imageScalerE000Profile: String? = null,
    ispAutoSyncProfile: String? = null,
    private val promotedRegions: Set<String> = emptySet(),
    private val relaxedContinuous: Boolean = false,
) : AutoCloseable {
    private val regionDefinitions = regions.sortedBy { it.base }.map { it.copy(initial=it.initial?.clone(), samples=it.samples?.let { values -> java.util.Collections.unmodifiableList(ArrayList(values)) }, prohibited=it.prohibited.toList(), readAllowed=it.readAllowed?.clone(), writeAllowed=it.writeAllowed?.clone(), selectionValues=it.selectionValues?.let { values -> java.util.Collections.unmodifiableList(ArrayList(values)) }) }.toMutableList()
    val regions: List<DiscoveryRegion> = java.util.Collections.unmodifiableList(regionDefinitions)
    internal val known = known.sortedBy { it.base }
    private val knownStarts = this.known.map {it.base}.toLongArray()
    private val knownEnds = this.known.map {it.endExclusive}.toLongArray()
    private fun touchesKnown(base:Long,end:Long):Boolean {
        var index=java.util.Arrays.binarySearch(knownStarts,base)
        if(index>=0)return true
        index=-index-1
        return (index>0 && knownEnds[index-1]>base) || (index<knownStarts.size && knownStarts[index]<end)
    }
    private val bytes = this.regions.map { it.initial?.clone() }
    private var c000StopReply: C000StopReply? = null
    internal fun selectC000StopReply(profile: String = C000StopReply.PROFILE) {
        val region = regions.single { it.id == RETAINED_6C000_REGION }
        require(region.base == 0xfff6c000L && region.length == 0x204 && region.strategy == DiscoveryStrategy.RETAINED &&
            region.widths == setOf(4) && region.alignment == 4 && region.littleEndian &&
            region.readAllowed?.get(0) == 4 && region.writeAllowed?.get(0) == 4 &&
            region.prohibited.none { it.overlaps(region.base, region.base + 4) } && !touchesKnown(region.base, region.base + 4))
        check(c000StopReply == null && journal.count == 0)
        c000StopReply = C000StopReply(profile)
    }
    private fun completionState(region: DiscoveryRegion): Map<String, String> = when {
        region.clearC204Start -> mapOf("completion" to C204_COMPLETION)
        region.id == RETAINED_6C000_REGION -> c000StopReply?.state().orEmpty()
        else -> emptyMap()
    }
    private var acknowledgeImageEvent: (() -> Unit)? = null
    private val configuredIrq106Profile = imageIrq106Profile ?: if (imageIrq106Selected) ImageIrq106Status.PROFILE else null
    private var irq106State = configuredIrq106Profile?.let(ImageIrq106Status::initial)
    val hasImageIrq106Status: Boolean get() = irq106State != null
    val isImageIrq106Repeatable: Boolean get() = irq106State?.repeatable == true

    @JvmOverloads
    fun pulseImageIrq106(port: CoupledEdgeInterruptSink, statusMask: Long = 1L) {
        check(!closed && !accessRefused)
        val current = checkNotNull(irq106State) { "IRQ106 status profile is not selected" }
        val latched = current.latch(statusMask)
        val index = regions.indexOfFirst { it.id == Image70000.REGION }
        require(positions[index] >= Image70000.limit(DiscoveryStrategy.IMAGE_70000_DRIVER)) {
            "IRQ106 requires completed driver registration and mask writes"
        }
        port.pulseWithDevice(106, 2) { irq106State = latched }
    }

    fun startImageEvent(statusBit: Boolean, acknowledge: () -> Unit) {
        check(!closed && !accessRefused && acknowledgeImageEvent == null)
        val index = regions.indexOfFirst { it.id == RETAINED_69100_REGION }
        require(index >= 0 && regions[index].base == 0xfff69100L)
        val bank = checkNotNull(bytes[index])
        require(bank[12].toInt() and 2 != 0 && bank[8].toInt() and 2 == 0)
        if (statusBit) bank[8] = (bank[8].toInt() or 2).toByte()
        acknowledgeImageEvent = acknowledge
    }
    private val positions = IntArray(this.regions.size)
    private var t79Setter: T79ImageConfigSetter? = null
    private var t79SecondUpdate: T79ImageSecondUpdate? = null
    private var t79StatefulBank: T79StatefulIspBank? = null
    private var image6eBank: Image6e000Bank? = Image6e000Bank().takeIf {
        this.regions.any { it.strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE }
    }
    private var selector10Reads = 0
    private var reply01Position = 0
    private var programmedReply = this.regions.firstOrNull { it.strategy.isProgrammedReply }?.let {
        ProgrammedImageReply(if (it.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE) ProgrammedImageReply.REPEATABLE_PROFILE else ProgrammedImageReply.PROFILE)
    }
    private var liveViewBanks = ImageLiveViewBanks().takeIf { this.regions.any { it.strategy == DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS } }
    private val hasBank1ResultStats = this.regions.any { it.id == ImageResultStats.REGION_BANK1_FULL }
    private val configuredScalerProfile = if (this.regions.any { it.strategy == DiscoveryStrategy.IMAGE_SCALER_F000 }) {
        imageScalerProfile ?: ImageScalerF000.PROFILE
    } else null
    private var scalerF000 = configuredScalerProfile?.let { ImageScalerF000(it) }
    private var face7c000 = if (this.regions.any { it.strategy == DiscoveryStrategy.IMAGE_FACE_7C000 }) {
        ImageFace7c000(ImageFace7c000.IRQ130_PROFILE)
    } else null
    private val configuredBootRomProfile = if (this.regions.any { it.strategy == DiscoveryStrategy.BOOTROM_COLD_START }) {
        val ids = this.regions.filter { it.strategy == DiscoveryStrategy.BOOTROM_COLD_START }.map { it.id }.toSet()
        bootRomColdStartProfile ?: when (ids) {
            BootRomColdStart.RELEASE_COLDSTART_REGION_IDS -> BootRomColdStart.RELEASE_COLDSTART_PROFILE
            BootRomColdStart.QSPI_KERNEL_REGION_IDS -> BootRomColdStart.QSPI_KERNEL_PROFILE
            BootRomColdStart.STAGE1_DDR_REGION_IDS -> BootRomColdStart.STAGE1_DDR_PROFILE
            else -> BootRomColdStart.PROFILE
        }
    } else null
    private var bootRomColdStart = configuredBootRomProfile?.let { BootRomColdStart(it) }
    val hasBootRomStage1DdrInit: Boolean get() = bootRomColdStart?.profile in BootRomColdStart.STAGE1_DDR_PROFILES
    private val configuredScalerE000Profile = if (this.regions.any { it.strategy == DiscoveryStrategy.IMAGE_SCALER_E000 }) {
        imageScalerE000Profile ?: ImageScalerE000.PROFILE
    } else null
    private var scalerE000 = configuredScalerE000Profile?.let { ImageScalerE000(it) }
    private val configuredIspAutoSyncProfile = ispAutoSyncProfile
    private var ispAutoSync = configuredIspAutoSyncProfile?.let { IspAutoSync(it) }
    private var playDspPendingDueTick: Long? = null
    private var playDspIrqActive: Boolean = false
    private val tableIndex = this.regions.indexOfFirst { it.id == Image51000.TABLE }
    private val emptyIndex = this.regions.indexOfFirst { it.id == Image51000.EMPTY }
    private val pairIndex = this.regions.indexOfFirst {
        it.strategy == DiscoveryStrategy.WRITE_49530 || it.strategy == DiscoveryStrategy.WRITE_49530_REPEATABLE
    }
    private val tailIndex = this.regions.indexOfFirst {
        it.strategy == DiscoveryStrategy.TAIL_49500 || it.strategy == DiscoveryStrategy.TAIL_49500_REPEATABLE
    }
    private var hdmiStatus: HdmiStatusSamples? = null
    internal fun selectHdmiStatus(raw: Any, linkReads: Any?) {
        val region = regions.single { it.id == HdmiStatusSamples.REGION }
        require(region.strategy == DiscoveryStrategy.RETAINED && region.widths == setOf(4) && region.alignment == 4)
        for (address in listOf(HdmiStatusSamples.FIRST, HdmiStatusSamples.FIRST + 4)) {
            val offset = Math.toIntExact(address - region.base)
            require(region.prohibited.none { it.overlaps(address, address + 4) } &&
                region.readAllowed?.get(offset) == 4 && region.writeAllowed?.get(offset) == 4)
        }
        check(hdmiStatus == null && journal.count == 0)
        if (linkReads != null) {
            val offset = Math.toIntExact(HdmiStatusSamples.LINK - region.base)
            require(region.readAllowed?.get(offset) == 4 && region.writeAllowed?.get(offset) == 0)
            require(region.prohibited.none { it.overlaps(HdmiStatusSamples.LINK, HdmiStatusSamples.LINK + 4) })
        }
        hdmiStatus = HdmiStatusSamples(raw, linkReads)
    }
    private val additionalReads = IntArray(this.regions.size)
    private val retainedWritten = BooleanArray(this.regions.size)
    private val retainedValues = LongArray(this.regions.size)
    private var audioCompletion: Map<String, Any>? = null
    private var repeatLimit = 0
    private val repeatWritePositions = ArrayList<Int>()
    private val accessAttempts = LongArray(this.regions.size)
    private val fixedSelected = BooleanArray(this.regions.size)
    private val selectedValues = LongArray(this.regions.size)
    private data class ScriptTailSelection(val initialSamples: List<Long?>, val sampleIndex: Int, val journalIndex: Int)
    private val scriptTailSelections = arrayOfNulls<ScriptTailSelection>(this.regions.size)
    private var accessRefused = false
    private var closed = false
    private val retained69100RegionIndex: Int = this.regions.indexOfFirst { it.id == RETAINED_69100_REGION }
    private val image70000RegionIndex: Int = this.regions.indexOfFirst { it.id == Image70000.REGION }
    private val assertScalerF000Irq: () -> Unit = {
        checkNotNull(context.interruptSink()) { "ImageScalerF000 IRQ 133 requires InterruptSink" }
            .setLine(ImageScalerF000.IRQ_LINE, true)
    }
    private val assertScalerE000Irq: () -> Unit = {
        checkNotNull(context.interruptSink()) { "ImageScalerE000 IRQ 132 requires InterruptSink" }
            .setLine(ImageScalerE000.IRQ_LINE, true)
    }
    private val assertFace7c000Irq: () -> Unit = {
        checkNotNull(context.interruptSink()) { "ImageFace7c000 IRQ 130 requires InterruptSink" }
            .setLine(ImageFace7c000.IRQ_LINE, true)
    }
    private val ispAutoSyncIsIrq296ArmedInBank: () -> Boolean = {
        val idx69100 = retained69100RegionIndex
        val bank = if (idx69100 >= 0) bytes[idx69100] else null
        bank != null && bank.size > 12 && (bank[12].toInt() and 2) != 0 && (bank[8].toInt() and 2) == 0
    }
    private val ispAutoSyncHasActiveIrq296Event: () -> Boolean = { acknowledgeImageEvent != null }
    private val ispAutoSyncStartIrq296Event: (() -> Unit) -> Unit = { ack -> startImageEvent(true, ack) }
    private val ispAutoSyncCanPulseIrq106: () -> Boolean = {
        val idx70000 = image70000RegionIndex
        irq106State != null && idx70000 >= 0 &&
            positions[idx70000] >= Image70000.limit(DiscoveryStrategy.IMAGE_70000_DRIVER)
    }
    private val ispAutoSyncIsIrq106LineActive: () -> Boolean = { context.imageIrq106LineActive() }
    private val ispAutoSyncPulseIrq106: (com.github.nyabanban.fuji.emulator.api.CoupledEdgeInterruptSink, Long) -> Unit =
        { coupled, mask -> pulseImageIrq106(coupled, mask) }
    init {
        require(this.regions.map { it.id }.distinct().size == this.regions.size)
        if (configuredIrq106Profile != null) {
            val region = this.regions.single { it.id == Image70000.REGION }
            val address = ImageIrq106Status.ADDRESS
            val offset = (address - region.base).toInt()
            require(Image70000.includesDriver(region.strategy) && region.widths == setOf(4) &&
                region.prohibited.none { it.overlaps(address, address + 4) } && !touchesKnown(address, address + 4) &&
                (region.readAllowed?.get(offset) ?: 4) and 4 != 0 && (region.writeAllowed?.get(offset) ?: 4) and 4 != 0) {
                "IRQ106 status requires the qualified image70000 driver and its read/write status word"
            }
        }
        require((tableIndex >= 0) == (emptyIndex >= 0))
        val liveViewCount = this.regions.count { it.strategy == DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS }
        require(liveViewCount == 0 || liveViewCount == ImageLiveViewBanks.REGION_IDS.size) { "Incomplete ImageLiveViewBanks region set" }
        val bootRomRegions = this.regions.filter { it.strategy == DiscoveryStrategy.BOOTROM_COLD_START }
        if (bootRomRegions.isNotEmpty()) {
            val expectedIds = BootRomColdStart.regionIdsFor(requireNotNull(configuredBootRomProfile))
            require(bootRomRegions.map { it.id }.toSet() == expectedIds) { "Incomplete BootRomColdStart region set" }
        }
        if (tableIndex >= 0) require(this.regions[tableIndex].strategy == this.regions[emptyIndex].strategy)
        require(tailIndex < 0 || pairIndex >= 0) { "FFF49500 tail requires the FFF49530 write pair" }
        this.known.zipWithNext().forEach { (a,b) -> require(a.endExclusive<=b.base) { "Known intervals overlap" } }
        require(this.regions.sumOf { it.length.toLong() } <= 4194304)
        this.regions.zipWithNext().forEach { (a,b) -> require(a.endExclusive <= b.base) { "Discovery owners overlap" } }
    }
    fun pageRanges(): List<Pair<Long,Long>> = regions.map { (it.base and -4096L) to (((it.endExclusive + 4095) and -4096L) - (it.base and -4096L)) }
    private fun find(address: Long): Int {
        var low=0; var high=regions.lastIndex
        while(low<=high) { val mid=(low+high) ushr 1; val r=regions[mid]; if(address<r.base)high=mid-1 else if(address>=r.endExclusive)low=mid+1 else return mid }
        return -1
    }
    private fun selectionIndex(regionId: String): Int {
        val index = regions.indexOfFirst { it.id == regionId }
        require(index >= 0 && regions[index].selectionValues != null) { "Region does not declare selectable fixed input: " + regionId }
        return index
    }
    private fun selectionState(index: Int): Map<String,Any?> = mapOf(
        "selectionValues" to regions[index].selectionValues,
        "selectedValue" to selectedValues[index].takeIf { fixedSelected[index] },
        "selectionState" to if (fixedSelected[index]) "selected" else "unselected",
        "accessAttempted" to (accessAttempts[index] != 0L), "attemptedAccesses" to accessAttempts[index])
    /** Owner-thread observation only; does not materialize access records or consume input. */
    fun fixedInputSelection(regionId: String): Map<String,Any?> = mapOf("id" to regionId) + selectionState(selectionIndex(regionId))
    /** Called by the session owner at a safe point, once, before any attempted access. */
    fun selectFixedInput(regionId: String, value: Long): Map<String,Any?> {
        val index = selectionIndex(regionId)
        check(!closed) { "Discovery bus is closed" }
        require(value in checkNotNull(regions[index].selectionValues)) { "Fixed input value was not declared" }
        check(!fixedSelected[index]) { "Fixed input already selected" }
        check(accessAttempts[index] == 0L) { "Fixed input region already accessed" }
        selectedValues[index] = value
        fixedSelected[index] = true
        return fixedInputSelection(regionId)
    }
    private fun scriptIndex(regionId: String): Int {
        val index = regions.indexOfFirst { it.id == regionId }
        require(index >= 0 && regions[index].strategy == DiscoveryStrategy.SCRIPT) { "Region is not a scripted input: " + regionId }
        return index
    }
    internal fun scriptTailSelected(regionId: String): Boolean =
        regions.indexOfFirst { it.id == regionId }.let { it >= 0 && scriptTailSelections[it] != null }
    private fun isA300(region: DiscoveryRegion) =
        region.base == 0xfff4a300L && region.length == 4 && region.widths == setOf(4) && region.strategy == DiscoveryStrategy.SCRIPT

    fun appendA300Samples(id: String, expectedIndex: Int, samples: List<Long>): Map<String, Any?> {
        val index = scriptIndex(id)
        val region = regions[index]
        require(isA300(region))
        check(!closed && !accessRefused && scriptTailSelections[index] != null)
        require(expectedIndex == positions[index]) { "A300 cursor differs from requested index" }
        val previous = checkNotNull(region.samples)
        require(samples.size in (previous.size + 1)..MAX_A300_SAMPLES && samples.take(previous.size) == previous) {
            "A300 append requires the entire saved prefix and a bounded new tail"
        }
        require(samples.all { it in 0..0xffffffffL })
        regionDefinitions[index] = region.copy(samples = java.util.Collections.unmodifiableList(ArrayList(samples)))
        return scriptInputSelection(id) + mapOf("previousLength" to previous.size, "confidence" to "HYPOTHESIS")
    }
    private fun f01cIndex(regionId: String): Int {
        val index = find(0xfff4f01cL)
        require(index >= 0 && regions[index].id == regionId && regions[index].base == 0xfff4f01cL &&
            regions[index].retainedDiagnostic != null) { "Expected selected F01C retained diagnostic" }
        return index
    }
    internal fun extendF01cReads(regionId: String, extra: Int): Map<String, Any?> {
        val index = f01cIndex(regionId)
        val diagnostic = checkNotNull(regions[index].retainedDiagnostic)
        require(extra in 1..MAX_EXTRA_F01C_READS) { "Invalid finite F01C read extension" }
        val previousExtra = additionalReads[index]
        check(audioCompletion == null) { "F01C completion already selected" }
        val previousLimit = diagnostic.successfulReadLimit + previousExtra
        check(!closed && !accessRefused && retainedWritten[index] && (previousExtra == 0 || positions[index] == previousLimit)) {
            "F01C extension requires a runnable written diagnostic and a consumed previous extension"
        }
        val totalExtra = previousExtra + extra
        require(totalExtra <= MAX_TOTAL_EXTRA_F01C_READS) { "Cumulative F01C read extension exceeds limit" }
        additionalReads[index] = totalExtra
        return mapOf("id" to regionId, "confidence" to "HYPOTHESIS", "base" to regions[index].base,
            "width" to regions[index].length, "writeValue" to diagnostic.writeValue, "retainedValue" to retainedValues[index],
            "successfulReads" to positions[index], "previousSuccessfulReadLimit" to previousLimit,
            "additionalReads" to extra, "successfulReadLimit" to diagnostic.successfulReadLimit + totalExtra)
    }
    private fun audioReceipt(index: Int, reads: Int, limit: Int, attempts: Long, tick: Long): Map<String, Any> {
        val region = regions[index]
        require(region.length == 4 && region.widths == setOf(4) && region.retainedDiagnostic?.writeValue == 1L)
        require(reads in 1 until limit && attempts > 0 && tick >= attempts)
        return mapOf("id" to region.id, "confidence" to "HYPOTHESIS", "base" to region.base, "width" to 4,
            "before" to 1, "after" to 0, "completions" to 1, "guestWrites" to 1,
            "successfulReads" to reads, "successfulReadLimit" to limit, "attempts" to attempts, "tick" to tick)
    }
    internal fun completeAudioF01c(regionId: String, attempts: Long, tick: Long): Map<String, Any> {
        val index = f01cIndex(regionId)
        check(!closed && !accessRefused && audioCompletion == null && retainedWritten[index] && retainedValues[index] == 1L)
        check(accessAttempts[index] == positions[index].toLong() + 1)
        val limit = checkNotNull(regions[index].retainedDiagnostic).successfulReadLimit + additionalReads[index]
        val receipt = audioReceipt(index, positions[index], limit, attempts, tick)
        audioCompletion = receipt
        retainedValues[index] = 0
        return receipt.toMap()
    }
    internal fun selectF01cRepeats(regionId: String, limit: Int): Map<String, Any> {
        val index = f01cIndex(regionId)
        require(limit in 1..MAX_F01C_REPEATS)
        check(!closed && !accessRefused && audioCompletion?.get("id") == regionId && repeatLimit == 0)
        val readLimit = checkNotNull(regions[index].retainedDiagnostic).successfulReadLimit + additionalReads[index]
        require(limit <= readLimit - positions[index]) { "F01C repeats exceed remaining read budget" }
        repeatLimit = limit
        return repeatReceipt()
    }

    private fun repeatReceipt() = mapOf("limit" to repeatLimit, "writeAtSuccessfulReads" to repeatWritePositions.toList(),
        "completion" to "first-read-clears", "confidence" to "HYPOTHESIS")

    internal fun scriptInputSelection(regionId: String): Map<String,Any?> {
        val index = scriptIndex(regionId)
        val selection = scriptTailSelections[index]
        val samples = java.util.Collections.unmodifiableList(checkNotNull(regions[index].samples).map { checkNotNull(it) })
        val initialSamples = selection?.initialSamples?.let { values ->
            java.util.Collections.unmodifiableList(values.map { checkNotNull(it) })
        } ?: samples
        return mapOf("id" to regionId, "samples" to samples,
            "initialSamples" to initialSamples,
            "sampleIndex" to positions[index], "revision" to if (selection == null) 0 else 1,
            "tailSelected" to (selection != null), "attemptedAccesses" to accessAttempts[index],
            "selectedAtSampleIndex" to selection?.sampleIndex, "selectedAtJournalIndex" to selection?.journalIndex,
            "accessRefused" to accessRefused, "closed" to closed)
    }
    internal fun selectScriptInputTail(regionId: String, expectedSampleIndex: Int, samples: List<Long>): Map<String,Any?> {
        require(regionId !in imageReplyIds)
        val index = scriptIndex(regionId)
        check(!closed) { "Discovery bus is closed" }
        check(!accessRefused) { "Discovery access was already refused" }
        check(scriptTailSelections[index] == null) { "Script tail already selected" }
        require(expectedSampleIndex == positions[index]) { "Script sample index differs from the held gate" }
        require(samples.size in 1..64 && (samples as List<*>).all { it is Long && it in 0..0xffffffffL }) { "Script tail requires 1..64 exact u32 samples" }
        val initial = checkNotNull(regions[index].samples)
        require(samples.size > positions[index] && samples.take(positions[index]) == initial.take(positions[index])) { "Script tail must preserve consumed samples and leave an unread sample" }
        val selected = regions[index].copy(samples = java.util.Collections.unmodifiableList(ArrayList(samples)))
        val receipt = ScriptTailSelection(initial, positions[index], journal.count)
        regionDefinitions[index] = selected
        scriptTailSelections[index] = receipt
        return scriptInputSelection(regionId)
    }
    private fun recordAttempt(address: Long, width: Int, index: Int) {
        if (index >= 0) accessAttempts[index]++
        if (width <= 0 || address < 0 || address >= 0x1_0000_0000L) return
        val end = address + width.toLong()
        if (index >= 0 && end <= regions[index].endExclusive) return
        // Cross-boundary refusals also count for every candidate they touch.
        var next = index + 1
        if (index < 0) {
            var high = regions.size
            while (next < high) { val mid = (next + high) ushr 1; if (regions[mid].base < address) next = mid + 1 else high = mid }
        }
        while (next < regions.size && regions[next].base < end) accessAttempts[next++]++
    }
    /** Record a terminal router/native refusal without reading, consuming or modifying any model. */
    fun refused(sequence: Long, address: Long, width: Int, write: Boolean, value: Long = 0,
                reason: Int, execute: Boolean = false, nativeKind: Int = -1) {
        require(!write || !execute)
        accessRefused = true
        val index=find(address)
        recordAttempt(address,width,index)
        val slot=journal.begin(sequence,address,width,write,value,index,context,execute,nativeKind)
        journal.refuse(slot,reason)
    }
    fun access(sequence: Long, address: Long, width: Int, write: Boolean, value: Long = 0): Long {
        val index=find(address)
        recordAttempt(address,width,index)
        val slot=try { journal.begin(sequence,address,width,write,value,index,context) }
            catch(error:Throwable) { accessRefused=true; throw error }
        fun refuse(reason: Int): Nothing { accessRefused=true; journal.refuse(slot,reason); error("Discovery access refused (reason " + reason + (DiscoveryJournal.diagnosticReason(reason)?.let { ": " + it } ?: "") + ")") }
        if(closed)refuse(DiscoveryJournal.CLOSED)
        if(index<0)refuse(DiscoveryJournal.OUTSIDE)
        val r=regions[index]
        if(width !in r.widths)refuse(DiscoveryJournal.WIDTH)
        if(address % r.alignment != 0L || address % width != 0L)refuse(DiscoveryJournal.ALIGNMENT)
        if(address<0 || address>0x1_0000_0000L-width || address+width>r.endExclusive)refuse(DiscoveryJournal.CROSSING)
        val end=address+width
        val liveViewInterior = r.id == ImageLiveViewBanks.FFF72028_REGION && liveViewBanks != null && ImageLiveViewBanks.handlesInterior(address)
        val bank0ResultInterior = r.id == IMAGE_RESULT_SURFACE && hasBank1ResultStats && !write && address in 0xfff121a0L until 0xfff129a0L
        if(!liveViewInterior && !bank0ResultInterior && r.prohibited.any { it.overlaps(address,end) })refuse(DiscoveryJournal.PROHIBITED)
        if(touchesKnown(address,end))refuse(DiscoveryJournal.KNOWN)
        val mask=if(width==4)0xffffffffL else (1L shl (width*8))-1
        if(write && (value<0 || value and mask != value))refuse(DiscoveryJournal.VALUE)
        val offset=(address-r.base).toInt()
        if (liveViewInterior) {
            val (sample, prior, next) = liveViewBanks!!.access(address, write, value) ?: refuse(DiscoveryJournal.PROHIBITED)
            journal.complete(slot, sample, prior, next)
            return sample
        }
        val allowed=if(write)r.writeAllowed else r.readAllowed
        if(!bank0ResultInterior && allowed != null && allowed[offset] and width == 0)refuse(DiscoveryJournal.WIDTH)
        val irq106 = irq106State
        val status = irq106?.phase
        if (r.strategy == DiscoveryStrategy.IMAGE_70000_SETTER && t79Setter == null) refuse(DiscoveryJournal.POLICY)
        if ((r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
                r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
                r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK) &&
            (t79Setter == null || !t79Setter!!.complete || t79SecondUpdate == null)) refuse(DiscoveryJournal.POLICY)
        if (r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK && !t79SecondUpdate!!.complete) refuse(DiscoveryJournal.POLICY)
        if (address == ImageIrq106Status.ADDRESS && r.id == Image70000.REGION &&
            irq106 != null && status != ImageIrq106Status.IDLE &&
            !(status == ImageIrq106Status.FINISHED &&
                (Image70000.inSecondSetup(r.strategy, positions[index]) ||
                    Image70000.inStatefulBank(r.strategy, positions[index])))) {
            if (accessRefused) refuse(DiscoveryJournal.POLICY)
            val next = irq106.advance(write, value, context) ?: refuse(DiscoveryJournal.POLICY)
            irq106State = next
            val sample = if (write) value else irq106.activeValue
            journal.completeIrq106(slot, sample, irq106.activeValue, next.activeValue, irq106.repeatable)
            return sample
        }
        if (r.id == HdmiStatusSamples.REGION && HdmiStatusSamples.contains(address)) {
            val selected = hdmiStatus ?: refuse(DiscoveryJournal.POLICY)
            val sample = selected.access(address, write, value) ?: refuse(DiscoveryJournal.MISSING)
            journal.complete(slot, sample, null, sample)
            return sample
        }
        fun readBytes(): Long { var result=0L; val data=checkNotNull(bytes[index]); repeat(width) { n -> val shift=if(r.littleEndian)n*8 else (width-1-n)*8; result=result or ((data[offset+n].toLong() and 255) shl shift) }; return result }
        val before: Long?
        val after: Long
        val result: Long
        when(r.strategy) {
            DiscoveryStrategy.IMAGE_TABLE_PAIR, DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE -> {
                val position = positions[index]
                val limit = if (r.id == Image51000.TABLE) Image51000.tableLimit(r.strategy) else Image51000.emptyLimit(r.strategy)
                if (position >= limit) refuse(DiscoveryJournal.MISSING)
                val expectedOffset = Image51000.expectedOffset(r.id, position)
                val pairedReady = when {
                    r.id == Image51000.TABLE && position >= Image51000.TABLE_LIMIT -> positions[emptyIndex] == Image51000.EMPTY_LIMIT
                    r.id == Image51000.EMPTY && position < Image51000.EMPTY_LIMIT -> positions[tableIndex] == Image51000.TABLE_LIMIT
                    r.id == Image51000.EMPTY -> positions[tableIndex] == Image51000.TOTAL_TABLE_LIMIT
                    else -> true
                }
                if (!write || offset != expectedOffset || !pairedReady ||
                    !Image51000.accepts(r.id, position, value, context)) refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> {
                if (!write || !Image51000.acceptsRepeatable(r.id, offset, value, context)) refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS -> {
                val (sample, prior, next) = checkNotNull(liveViewBanks).access(address, write, value) ?: refuse(DiscoveryJournal.PROHIBITED)
                if (write && address == 0xfff730d4L && (value and 0x10000000L) != 0L) {
                    liveViewBanks!!.pumpLiveViewDma(context.tick(), context.sensorPort(), context.dmaMemory(), context.lensParameters())
                }
                before = prior
                result = sample
                after = next
            }
            DiscoveryStrategy.IMAGE_SCALER_F000 -> {
                val (sample, prior, next) = checkNotNull(scalerF000).access(address, write, value, context.tick()) {
                    checkNotNull(context.interruptSink()) { "ImageScalerF000 IRQ 133 requires InterruptSink" }
                        .setLine(ImageScalerF000.IRQ_LINE, false)
                } ?: refuse(DiscoveryJournal.PROHIBITED)
                before = prior
                result = sample
                after = next
            }
            DiscoveryStrategy.IMAGE_FACE_7C000 -> {
                val (sample, prior, next) = checkNotNull(face7c000).access(
                    address = address,
                    write = write,
                    value = value,
                    tick = context.tick(),
                    onDeassertIrq130 = {
                        checkNotNull(context.interruptSink()) { "ImageFace7c000 IRQ 130 requires InterruptSink" }
                            .setLine(ImageFace7c000.IRQ_LINE, false)
                    },
                    onAssertIrq130 = assertFace7c000Irq,
                ) ?: refuse(DiscoveryJournal.PROHIBITED)
                before = prior
                result = sample
                after = next
            }
            DiscoveryStrategy.BOOTROM_COLD_START -> {
                val (sample, prior, next) = checkNotNull(bootRomColdStart).access(address, write, value)
                    ?: refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                before = prior
                result = sample
                after = next
            }
            DiscoveryStrategy.IMAGE_SCALER_E000 -> {
                val (sample, prior, next) = checkNotNull(scalerE000).access(address, write, value, context.tick()) {
                    checkNotNull(context.interruptSink()) { "ImageScalerE000 IRQ 132 requires InterruptSink" }
                        .setLine(ImageScalerE000.IRQ_LINE, false)
                } ?: refuse(DiscoveryJournal.PROHIBITED)
                before = prior
                result = sample
                after = next
            }
            DiscoveryStrategy.IMAGE_68000, DiscoveryStrategy.IMAGE_68000_TWICE -> {
                val position = positions[index]
                if (position >= Image68000.limit(r.strategy)) refuse(DiscoveryJournal.MISSING)
                if (!write || !Image68000.accepts(position, offset, context)) refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.IMAGE_68000_REPEATABLE -> {
                if (!write || !Image68000.acceptsRepeatable(offset, context)) refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.IMAGE_6E_CONFIG, DiscoveryStrategy.IMAGE_6E_STOP, DiscoveryStrategy.IMAGE_6E_DISABLE,
            DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG -> {
                val position = positions[index]
                if (position >= Image6e000.limit(r.strategy)) refuse(DiscoveryJournal.MISSING)
                result = Image6e000.access(r.strategy, position, offset, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_6E_REPEATABLE -> {
                val bank = checkNotNull(image6eBank)
                result = bank.access(offset, width, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_70000, DiscoveryStrategy.IMAGE_70000_CONFIG, DiscoveryStrategy.IMAGE_70000_UPDATE,
            DiscoveryStrategy.IMAGE_70000_DRIVER, DiscoveryStrategy.IMAGE_70000_GETTERS,
            DiscoveryStrategy.IMAGE_70000_TEARDOWN, DiscoveryStrategy.IMAGE_70000_REINIT,
            DiscoveryStrategy.IMAGE_70000_CONFIG_READS -> {
                val position = positions[index]
                if (position >= Image70000.limit(r.strategy)) refuse(DiscoveryJournal.MISSING)
                if (Image70000.inSecondSetup(r.strategy, position) &&
                    (status != null && status != ImageIrq106Status.FINISHED || context.imageIrq106LineActive())) refuse(DiscoveryJournal.POLICY)
                result = Image70000.access(r.strategy, position, offset, write, value, context, retainedValues[index])
                    ?: refuse(DiscoveryJournal.POLICY)
                if (Image70000.includesDriver(r.strategy) && position == Image70000.driverControlPosition) {
                    retainedWritten[index] = true
                    retainedValues[index] = result and 0xffff0000
                }
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_70000_SETTER -> {
                val setter = checkNotNull(t79Setter)
                if (setter.complete) refuse(DiscoveryJournal.MISSING)
                result = setter.access(offset, width, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE -> {
                val update = checkNotNull(t79SecondUpdate)
                if (update.complete) refuse(DiscoveryJournal.MISSING)
                result = update.access(offset, width, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS -> {
                val position = positions[index]
                val update = checkNotNull(t79SecondUpdate)
                if (position >= Image70000.limit(r.strategy)) refuse(DiscoveryJournal.MISSING)
                result = if (position < Image70000.secondDriverStart) {
                    if (update.complete) refuse(DiscoveryJournal.POLICY)
                    update.access(offset, width, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                } else {
                    Image70000.secondDriverOrGetterAccess(position, offset, write, value, context, retainedValues[index], update)
                        ?: refuse(DiscoveryJournal.POLICY)
                }
                if (position == Image70000.secondDriverControlPosition) {
                    retainedWritten[index] = true
                    retainedValues[index] = result and 0xffff0000
                }
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK -> {
                val position = positions[index]
                val update = checkNotNull(t79SecondUpdate)
                if (position < Image70000.statefulStart) {
                    result = Image70000.secondDriverOrGetterAccess(position, offset, write, value, context, retainedValues[index], update)
                        ?: refuse(DiscoveryJournal.POLICY)
                    if (position == Image70000.secondDriverControlPosition) {
                        retainedWritten[index] = true
                        retainedValues[index] = result and 0xffff0000
                    }
                    positions[index]++
                    if (positions[index] == Image70000.statefulStart) {
                        t79StatefulBank = T79StatefulIspBank.seed(checkNotNull(t79Setter), update, retainedValues[index])
                    }
                } else {
                    val bank = t79StatefulBank ?: refuse(DiscoveryJournal.POLICY)
                    result = bank.access(offset, width, write, value, context) ?: refuse(DiscoveryJournal.POLICY)
                    if (write && offset == 0x2e4) {
                        retainedWritten[index] = true
                        retainedValues[index] = result and 0xffff0000
                    }
                    positions[index]++
                }
                before = null
                after = result
            }
            DiscoveryStrategy.IMAGE_50000_CONTROL, DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE,
            DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE -> {
                val position = positions[index]
                if (r.strategy != DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE) {
                    val limit = if (r.strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE) 4 else 2
                    if (position >= limit) refuse(DiscoveryJournal.MISSING)
                }
                if (context.core() != 1 || write != (position % 2 == 0)) refuse(DiscoveryJournal.POLICY)
                if (write) {
                    if (context.pc() != 0x5af98L || context.lr() != 0x11c0f78L) refuse(DiscoveryJournal.POLICY)
                    if (value != 0L) refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                    result = value
                } else {
                    if (context.pc() != 0x5afe0L || context.lr() != 0x11c0f88L) refuse(DiscoveryJournal.POLICY)
                    result = 1L
                }
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.WRITE_49530, DiscoveryStrategy.WRITE_49530_REPEATABLE -> {
                val position = positions[index]
                val repeatable = r.strategy == DiscoveryStrategy.WRITE_49530_REPEATABLE
                if (!repeatable && position >= 3) refuse(DiscoveryJournal.POLICY)
                val phase = position % 3
                if (repeatable && phase == 0 && tailIndex >= 0 && positions[tailIndex] != position / 3) {
                    refuse(DiscoveryJournal.POLICY)
                }
                if (!write || offset != (if (phase == 0) 0 else 4)) refuse(DiscoveryJournal.POLICY)
                val (expectedLr, expectedWord) = when (phase) {
                    0 -> 0x02394e9cL to 0x10000704L
                    1 -> 0x02394eacL to 0x70L
                    2 -> if (tailIndex >= 0) 0x02394ef8L to 0x71L else refuse(DiscoveryJournal.POLICY)
                    else -> refuse(DiscoveryJournal.POLICY)
                }
                if (context.pc() != 0x5af98L || context.lr() != expectedLr || value != expectedWord)
                    refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.TAIL_49500, DiscoveryStrategy.TAIL_49500_REPEATABLE -> {
                val position = positions[index]
                val repeatable = r.strategy == DiscoveryStrategy.TAIL_49500_REPEATABLE
                val expectedPairPos = (position + 1) * 3
                if (!write || pairIndex < 0 || (!repeatable && position != 0) || positions[pairIndex] != expectedPairPos) {
                    refuse(DiscoveryJournal.POLICY)
                }
                if (context.pc() != 0x5af98L || context.lr() != 0x02394f08L || value != 3L)
                    refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                positions[index]++
                before = null
                result = value
                after = value
            }
            DiscoveryStrategy.POLL_7801C, DiscoveryStrategy.SETUP_REPEAT_7801C,
            DiscoveryStrategy.SETUP_REPEATABLE_7801C -> {
                val position = positions[index]
                if (position >= 2) {
                    if (r.strategy == DiscoveryStrategy.POLL_7801C) refuse(DiscoveryJournal.MISSING)
                    if (r.strategy == DiscoveryStrategy.SETUP_REPEAT_7801C) {
                        if (position >= 4) refuse(DiscoveryJournal.MISSING)
                        val expectedPc = if (position == 2) 0x5af98L else 0x5afe0L
                        val expectedLr = if (position == 2) 0x11e1748L else 0x11e1750L
                        if (context.pc() != expectedPc || context.lr() != expectedLr) refuse(DiscoveryJournal.POLICY)
                    }
                }
                if (position % 2 == 0) {
                    if (!write) refuse(DiscoveryJournal.READ_BEFORE_WRITE)
                    if (value != 1L) refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                    result = value
                } else {
                    if (write) refuse(DiscoveryJournal.REPEATED_WRITE)
                    result = 0L
                }
                positions[index]++
                before = null
                after = result
            }
            DiscoveryStrategy.PANEL_ONCE, DiscoveryStrategy.PANEL_TWICE -> {
                val position = positions[index]
                result = PanelInitOnce.access(position, offset, write, value, retainedValues[index], r.strategy == DiscoveryStrategy.PANEL_TWICE)
                    ?: refuse(DiscoveryJournal.MISSING)
                before = null
                after = result
                if (position % PanelInitOnce.CYCLE_LENGTH == 1) retainedValues[index] = value
                positions[index]++
            }
            DiscoveryStrategy.REFUSE -> refuse(DiscoveryJournal.POLICY)
            DiscoveryStrategy.IMAGE, DiscoveryStrategy.IMAGE_RESULT_SELECTOR10,
            DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE -> {
                if (write) refuse(DiscoveryJournal.POLICY)
                if (r.id == IMAGE_RESULT_SURFACE && address in SELECTOR10_WORDS) {
                    if (!r.strategy.isImageResultSelector10) refuse(DiscoveryJournal.PROHIBITED)
                    if (r.strategy == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10 && selector10Reads >= SELECTOR10_READ_LIMIT)
                        refuse(DiscoveryJournal.DIAGNOSTIC_LIMIT)
                    selector10Reads++
                } else if (r.id == IMAGE_RESULT_SURFACE) {
                    if (r.strategy != DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE &&
                        positions[index] >= IMAGE_RESULT_READ_LIMIT) refuse(DiscoveryJournal.DIAGNOSTIC_LIMIT)
                    positions[index]++
                }
                result=readBytes(); before=result; after=result
            }
            DiscoveryStrategy.FIXED -> { if(write)refuse(DiscoveryJournal.POLICY); result=r.fixedValue ?: selectedValues[index].takeIf { fixedSelected[index] } ?: refuse(DiscoveryJournal.MISSING); if(result and mask != result)refuse(DiscoveryJournal.VALUE); before=result; after=result }
            DiscoveryStrategy.SCRIPT, DiscoveryStrategy.INTERLEAVED_REPLY, DiscoveryStrategy.PROGRAMMED_REPLY, DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE -> {
                if(write)refuse(DiscoveryJournal.POLICY)
                val interleaved = r.strategy.interleavesReply
                val reading01 = interleaved && context.hasImageRequest82(1)
                val learned = programmedReply.takeIf { reading01 && reply01Position == 8 }
                val position = if (reading01) learned?.words ?: reply01Position else positions[index]
                val repeatablePairs = r.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE
                if (r.id in imageReplyIds) {
                    if ((reading01 && positions[index] % 8 != 0) ||
                        (interleaved && !reading01 && (reply01Position in 1..7 || programmedReply?.words in 1..7)))
                        refuse(DiscoveryJournal.POLICY)
                    val paired = r.id == IMAGE_REPLY_PAIRS && !reading01
                    val selector = if (paired) pairSelector(position, repeatablePairs) else if (reading01) 1 else imageReplySelectors[r.id]
                    if (selector == null) refuse(DiscoveryJournal.MISSING)
                    val word = if (paired) position % imageReplyReturnAddresses.size else position
                    val expectedReturn = imageReplyReturnAddresses.getOrNull(word) ?: refuse(DiscoveryJournal.MISSING)
                    if (address != 0xfff6c240L - word * 4 || context.core() != 1 ||
                        context.pc() != 0x5afe0L || context.lr() != expectedReturn ||
                        !context.hasImageRequest82(selector)) refuse(DiscoveryJournal.POLICY)
                }
                val sampleIndex = when {
                    learned != null -> IMAGE_REPLY_TOTAL_WORDS + 8 + learned.totalWords
                    reading01 -> IMAGE_REPLY_TOTAL_WORDS + position
                    else -> position
                }
                before=sampleIndex.toLong()
                val lookupIndex = if (learned == null && !reading01 && repeatablePairs && sampleIndex >= IMAGE_REPLY_TOTAL_WORDS) {
                    sampleIndex % IMAGE_REPLY_PAIR_WORDS
                } else sampleIndex
                result=learned?.read(::refuse) ?: r.samples?.getOrNull(lookupIndex) ?: refuse(DiscoveryJournal.MISSING)
                if(result and mask != result)refuse(DiscoveryJournal.VALUE)
                after=before+1
                if (!reading01) positions[index]++ else if (learned == null) reply01Position++
            }
            DiscoveryStrategy.RETAINED, DiscoveryStrategy.CHANNEL_CONTROL -> {
                if (r.strategy == DiscoveryStrategy.CHANNEL_CONTROL && !write && (accessRefused || accessAttempts[index] == 1L))
                    refuse(DiscoveryJournal.READ_BEFORE_WRITE)
                val diagnostic = r.retainedDiagnostic
                if (diagnostic == null) {
                    before=readBytes(); result=if(write)value else before
                    if (programmedReply != null && reply01Position == 8 && (r.id == RETAINED_6C204_REGION || r.id == RETAINED_6C20C_REGION)) {
                        programmedReply!!.observe(context, address, write, result, before, ::refuse)
                    }
                    after = when {
                        address == 0xfff6c000L && c000StopReply != null -> c000StopReply!!.access(context, write, before, value, ::refuse)
                        !write && offset == 0 && r.clearC204Start -> result and 1L.inv()
                        else -> result
                    }
                    if (after != result) checkNotNull(bytes[index])[0] = after.toByte()
                    if (address == 0xfff69108L && write && acknowledgeImageEvent != null) {
                        val bank = checkNotNull(bytes[index])
                        bank[offset] = (before and (value and 2L).inv()).toByte()
                        acknowledgeImageEvent!!.invoke()
                        acknowledgeImageEvent = null
                        journal.complete(slot, value, before, before and (value and 2L).inv())
                        return value
                    }
                    if(write) {
                        val data=checkNotNull(bytes[index])
                        repeat(width) { n -> val shift=if(r.littleEndian)n*8 else (width-1-n)*8; data[offset+n]=(value ushr shift).toByte() }
                        ispAutoSync?.observeWrite(address, value, context.tick())
                        if (r.dspFa00StatusReady && address == 0xfa000000L && width == 4) {
                            val readyIdle = (value and 1L.inv()) or PLAY_DSP_FA00_READY_BIT
                            repeat(4) { n -> data[n] = (readyIdle ushr (n * 8)).toByte() }
                            if ((value and 1L) != 0L) {
                                playDspPendingDueTick = context.tick() + PLAY_DSP_FA00_DELAY_TICKS
                            }
                        }
                        if (r.id == PLAY_DSP_AA10_REGION && address == 0xfff4aa10L && width == 4 &&
                            (value and 8L) != 0L && playDspIrqActive) {
                            playDspIrqActive = false
                            checkNotNull(context.interruptSink()) { "playDspFa00 IRQ 145 requires InterruptSink" }
                                .setLine(PLAY_DSP_FA00_IRQ_LINE, false)
                        }
                        if (r.faceStatusReady) {
                            fun writeLe32(off: Int, word: Long) {
                                repeat(4) { n -> data[off + n] = (word ushr (n * 8)).toByte() }
                            }
                            fun readLe32(off: Int): Long {
                                var w = 0L
                                repeat(4) { n -> w = w or ((data[off + n].toLong() and 0xffL) shl (n * 8)) }
                                return w
                            }
                            when (address) {
                                0xfff7c000L -> {
                                    // DAT s5:0x025a9874..0x025a98a4 writes 0 to 0xfff7c000 (Face-Detection
                                    // control/reset) during LiveView/EVF teardown and polls until != 0.
                                    writeLe32(0x000, value or 1L)
                                }
                                0xfff7c708L -> {
                                    val updated = if (value == 0L) FACE_7C000_READY_BITS else readLe32(0x700) and value.inv()
                                    writeLe32(0x700, updated)
                                }
                                0xfff7c738L -> {
                                    if ((value and 1L) != 0L) {
                                        writeLe32(0x700, FACE_7C000_READY_BITS)
                                    } else {
                                        data[0x734] = (data[0x734].toInt() or 1).toByte()
                                    }
                                }
                            }
                        }
                    }
                } else if (relaxedContinuous && r.base == 0xfff4f01cL) {
                    if (write) {
                        before = retainedValues[index].takeIf { retainedWritten[index] }
                        retainedValues[index] = value
                        retainedWritten[index] = true
                        result = value
                        after = value
                    } else {
                        before = retainedValues[index].takeIf { retainedWritten[index] } ?: 0L
                        retainedValues[index] = 0L
                        result = 0L
                        after = 0L
                        positions[index]++
                    }
                } else if (write) {
                    val repeat = retainedWritten[index]
                    if (repeat && (r.base != 0xfff4f01cL || repeatWritePositions.size >= repeatLimit || retainedValues[index] != 0L))
                        refuse(DiscoveryJournal.REPEATED_WRITE)
                    if (value != diagnostic.writeValue) refuse(DiscoveryJournal.DIAGNOSTIC_VALUE)
                    if (repeat && positions[index] >= diagnostic.successfulReadLimit + additionalReads[index])
                        refuse(DiscoveryJournal.DIAGNOSTIC_LIMIT)
                    before=if (repeat) retainedValues[index] else null; result=value; after=value
                    if (repeat) repeatWritePositions.add(positions[index])
                    retainedValues[index]=value; retainedWritten[index]=true
                } else {
                    if (!retainedWritten[index]) refuse(DiscoveryJournal.READ_BEFORE_WRITE)
                    if (positions[index] >= diagnostic.successfulReadLimit + additionalReads[index]) refuse(DiscoveryJournal.DIAGNOSTIC_LIMIT)
                    before=retainedValues[index]
                    if (repeatLimit != 0 && r.base == 0xfff4f01cL) retainedValues[index] = 0L
                    result=retainedValues[index]; after=result
                    positions[index]++
                }
            }
        }
        journal.complete(slot,result,before,after)
        return result
    }
    private fun reply01State(region: DiscoveryRegion): Map<String, Any> =
        if (region.strategy.interleavesReply)
            mapOf("reply01Selection" to if (region.samples!![IMAGE_REPLY_TOTAL_WORDS] == 0L) IMAGE_REPLY_820001 else IMAGE_REPLY_820001_AF,
                "reply01Position" to reply01Position)
        else emptyMap()
    private fun regionState():Map<String,Any?> = mapOf("closed" to closed, "regions" to regions.mapIndexed { i,r ->
        mapOf("id" to r.id,"deviceId" to r.deviceId,"base" to r.base,"endExclusive" to r.endExclusive,"strategy" to r.strategy.id,
            "sampleIndex" to positions[i],"bytesSha256" to bytes[i]?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } },
            "initialSha256" to r.initial?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } },"confidence" to "HYPOTHESIS") +
            (r.retainedDiagnostic?.let { d -> mapOf("initialValue" to null, "retainedValue" to retainedValues[i].takeIf { retainedWritten[i] },
                "successfulReads" to positions[i], "successfulReadLimit" to d.successfulReadLimit + additionalReads[i], "repeatWrite" to
                    if (repeatLimit != 0 && r.base == 0xfff4f01cL) "finite-first-read-clears" else "refuse", "automaticEvents" to false) } ?: emptyMap()) +
            completionState(r) + reply01State(r) +
            (if (r.strategy == DiscoveryStrategy.IMAGE_70000_SETTER ||
                    r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
                    r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
                    r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK)
                mapOf("t79Setter" to t79Setter?.receipt()) else emptyMap()) +
            (if (r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
                    r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
                    r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK)
                mapOf("t79SecondUpdate" to t79SecondUpdate?.receipt()) else emptyMap()) +
            (if (r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK && t79StatefulBank != null)
                mapOf("t79StatefulBank" to t79StatefulBank?.receipt()) else emptyMap()) +
            (if (r.strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE)
                mapOf("image6eBank" to image6eBank?.receipt()) else emptyMap()) +
            (if (r.strategy.isProgrammedReply) mapOf("programmedReply" to programmedReply!!.receipt()) else emptyMap()) +
            (if (r.id == ImageLiveViewBanks.PRIMARY_REGION && liveViewBanks != null) mapOf(ImageLiveViewBanks.KEY to liveViewBanks!!.receipt()) else emptyMap()) +
            (if (r.id == ImageScalerF000.REGION && scalerF000 != null) mapOf(ImageScalerF000.KEY to scalerF000!!.receipt()) else emptyMap()) +
            (if (r.id == ImageFace7c000.REGION && face7c000 != null) mapOf(ImageFace7c000.KEY to face7c000!!.receipt()) else emptyMap()) +
            (if (r.id == BootRomColdStart.PRIMARY_REGION && bootRomColdStart != null) mapOf(BootRomColdStart.KEY to bootRomColdStart!!.receipt()) else emptyMap()) +
            (if (r.id == ImageScalerE000.REGION && scalerE000 != null) mapOf(ImageScalerE000.KEY to scalerE000!!.receipt()) else emptyMap()) +
            (if (r.strategy == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10) mapOf("selector10Reads" to selector10Reads,
                "selector10ReadLimit" to SELECTOR10_READ_LIMIT)
            else if (r.strategy == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE) mapOf("selector10Reads" to selector10Reads)
            else emptyMap()) +
            (if (r.id == IMAGE_REPLY_PAIRS) mapOf("sampleLimit" to IMAGE_REPLY_TOTAL_WORDS,
                "completedPairs" to positions[i] / IMAGE_REPLY_PAIR_WORDS,
                "nextSelector" to pairSelector(positions[i], r.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE),
                "nextWordIndex" to pairSelector(positions[i], r.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE)?.let { positions[i] % imageReplyReturnAddresses.size }) else emptyMap()) +
            (if (r.selectionValues != null) selectionState(i) else emptyMap()) +
            (if (scriptTailSelections[i] != null) mapOf("scriptInputSelection" to scriptInputSelection(r.id)) else emptyMap()) })
    private fun hdmiState(): Map<String,Any?> = hdmiStatus?.let { mapOf("hdmiStatus" to it.state()) }.orEmpty()
    private fun audioState() = audioCompletion?.let { mapOf("audioF01cCompletion" to it.toMap()) }.orEmpty() +
        if (repeatLimit != 0) mapOf("audioF01cRepeats" to repeatReceipt()) else emptyMap()
    fun irq106State() = irq106State?.let { mapOf(ImageIrq106Status.KEY to it.receipt()) }.orEmpty()
    private fun ispAutoSyncState(): Map<String, Any?> = ispAutoSync?.let { mapOf(IspAutoSync.KEY to it.receipt()) }.orEmpty()
    fun nextDeadlinePrimitive(): Long {
        var earliest = Long.MAX_VALUE
        scalerF000?.nextDeadlinePrimitive()?.let { if (it < earliest) earliest = it }
        face7c000?.nextDeadlinePrimitive()?.let { if (it < earliest) earliest = it }
        scalerE000?.nextDeadlinePrimitive()?.let { if (it < earliest) earliest = it }
        playDspPendingDueTick?.let { if (it < earliest) earliest = it }
        ispAutoSync?.nextDeadlinePrimitive()?.let { if (it < earliest) earliest = it }
        return earliest
    }
    fun nextInterestedTick(): Long {
        if (ispAutoSync?.hasRestoreProbePending() == true) return 0L
        return nextDeadlinePrimitive()
    }
    fun nextDeadline(): Long? = nextDeadlinePrimitive().takeIf { it != Long.MAX_VALUE }
    fun pumpLiveViewDma(tick: Long): Boolean {
        val banks = liveViewBanks ?: return false
        return banks.pumpLiveViewDma(tick, context.sensorPort(), context.dmaMemory(), context.lensParameters())
    }
    fun beforeInstruction(tick: Long) {
        scalerF000?.let { s ->
            if (tick >= s.nextDeadlinePrimitive()) {
                s.beforeInstruction(
                    tick, context.dmaMemory(), skipUnmappedDma = relaxedContinuous,
                    scaler = context.facePreviewScaler(), assertIrq133 = assertScalerF000Irq,
                )
            }
        }
        face7c000?.let { f ->
            if (tick >= f.nextDeadlinePrimitive()) {
                f.beforeInstruction(tick, context.dmaMemory(), skipUnmappedDma = relaxedContinuous, assertIrq130 = assertFace7c000Irq)
            }
        }
        scalerE000?.let { s ->
            if (tick >= s.nextDeadlinePrimitive()) {
                s.beforeInstruction(
                    tick = tick,
                    dmaMemory = context.dmaMemory(),
                    sensor = context.sensorPort(),
                    lens = context.lensParameters(),
                    exposurePort = context.exposurePort(),
                    assertIrq132 = assertScalerE000Irq,
                )
            }
        }
        val dspDue = playDspPendingDueTick
        if (dspDue != null && tick >= dspDue) {
            playDspPendingDueTick = null
            playDspIrqActive = true
            checkNotNull(context.interruptSink()) { "playDspFa00 IRQ 145 requires InterruptSink" }
                .setLine(PLAY_DSP_FA00_IRQ_LINE, true)
        }
        ispAutoSync?.let { sync ->
            if (sync.hasRestoreProbePending() || tick >= sync.nextDeadlinePrimitive()) {
                sync.beforeInstruction(
                    tick = tick,
                    port = context.interruptSink() as? com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink,
                    dmaMemory = context.dmaMemory(),
                    isIrq296ArmedInBank = ispAutoSyncIsIrq296ArmedInBank,
                    hasActiveIrq296Event = ispAutoSyncHasActiveIrq296Event,
                    startIrq296Event = ispAutoSyncStartIrq296Event,
                    canPulseIrq106 = ispAutoSyncCanPulseIrq106,
                    isIrq106LineActive = ispAutoSyncIsIrq106LineActive,
                    pulseIrq106 = ispAutoSyncPulseIrq106,
                )
            }
        }
    }
    fun snapshot(): Map<String,Any?> = journal.snapshot(regions)+regionState()+hdmiState()+audioState()+irq106State()+ispAutoSyncState()
    fun boundedSnapshot(maximum: Int): Map<String,Any?> = journal.boundedSnapshot(regions, maximum)+regionState()+hdmiState()+audioState()+irq106State()+ispAutoSyncState()
    /** Verification only; process images, not this summary, retain the complete journal and input state. */
    fun checkpointState():Map<String,Any?> = journal.metadata()+regionState()+irq106State()+ispAutoSyncState()+mapOf("accessRecordsOmitted" to true)
    fun saveSnapshot(): Map<String, Any?> {
        check(acknowledgeImageEvent == null) { "Finish the image IRQ acknowledgement before saving" }
        check(!closed) { "Discovery bus is closed" }
        check(!accessRefused) { "Refused discovery bus cannot be snapshotted as runnable state" }
        val encoder = java.util.Base64.getEncoder()
        val savedRegions = linkedMapOf<String, Map<String, Any?>>()
        regions.forEachIndexed { i, r ->
            val mutableBacking = r.strategy == DiscoveryStrategy.RETAINED || r.strategy == DiscoveryStrategy.CHANNEL_CONTROL
            val mutatedBytes = if (mutableBacking && r.retainedDiagnostic == null && bytes[i] != null && !bytes[i]!!.contentEquals(r.initial)) {
                encoder.encodeToString(bytes[i])
            } else null
            val tail = scriptTailSelections[i]?.let { sel ->
                linkedMapOf<String, Any?>(
                    "samples" to checkNotNull(r.samples).map { checkNotNull(it) },
                    "initialSamples" to sel.initialSamples.map { checkNotNull(it) },
                    "sampleIndex" to sel.sampleIndex,
                    "journalIndex" to sel.journalIndex,
                )
            }
            savedRegions[r.id] = linkedMapOf(
                "base" to r.base,
                "endExclusive" to r.endExclusive,
                "strategy" to r.strategy.id,
                "position" to positions[i],
                "retainedWritten" to retainedWritten[i],
                "retainedValue" to retainedValues[i],
                "accessAttempts" to accessAttempts[i],
                "fixedSelected" to fixedSelected[i],
                "selectedValue" to selectedValues[i],
                "mutatedBytesBase64" to mutatedBytes,
                "scriptTailSelection" to tail,
            ) + (if (r.id == IMAGE_RESULT_SURFACE) mapOf("imageSha256" to r.imageDigest()) else emptyMap()) +
                (if (r.strategy.isImageResultSelector10) mapOf("selector10Reads" to selector10Reads) else emptyMap()) +
                completionState(r) + reply01State(r) +
                (if (r.strategy == DiscoveryStrategy.IMAGE_70000_SETTER ||
                        r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
                        r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
                        r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK)
                    mapOf("t79Setter" to checkNotNull(t79Setter) {
                        "T79 setter requires promotion from the completed configuration reads"
                    }.snapshot()) else emptyMap()) +
                (if (r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE ||
                        r.strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS ||
                        r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK)
                    mapOf("t79SecondUpdate" to checkNotNull(t79SecondUpdate) {
                        "T79 second update requires promotion from the completed config setter"
                    }.snapshot()) else emptyMap()) +
                (if (r.strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK && positions[i] >= Image70000.statefulStart)
                    mapOf("t79StatefulBank" to checkNotNull(t79StatefulBank) {
                        "T79 stateful ISP bank requires promotion from completed round-2 driver/getters"
                    }.snapshot()) else emptyMap()) +
                (if (r.strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE)
                    mapOf("image6eBank" to checkNotNull(image6eBank).snapshot()) else emptyMap()) +
                (if (r.strategy.isProgrammedReply) mapOf("programmedReply" to programmedReply!!.snapshot()) else emptyMap()) +
                (if (r.id == ImageLiveViewBanks.PRIMARY_REGION && liveViewBanks != null) mapOf(ImageLiveViewBanks.KEY to liveViewBanks!!.snapshot()) else emptyMap()) +
                (if (r.id == ImageScalerF000.REGION && scalerF000 != null) mapOf(ImageScalerF000.KEY to scalerF000!!.snapshot()) else emptyMap()) +
                (if (r.id == ImageFace7c000.REGION && face7c000 != null) mapOf(ImageFace7c000.KEY to face7c000!!.snapshot()) else emptyMap()) +
                (if (r.id == BootRomColdStart.PRIMARY_REGION && bootRomColdStart != null) mapOf(BootRomColdStart.KEY to bootRomColdStart!!.snapshot()) else emptyMap()) +
                (if (r.id == ImageScalerE000.REGION && scalerE000 != null) mapOf(ImageScalerE000.KEY to scalerE000!!.snapshot()) else emptyMap()) +
                (if (r.id == PLAY_DSP_FA00_REGION && (playDspPendingDueTick != null || playDspIrqActive)) mapOf(
                    "playDspPendingDueTick" to playDspPendingDueTick,
                    "playDspIrqActive" to playDspIrqActive,
                ) else emptyMap()) +
                if (r.id in retainedConfigRegions) mapOf("initialFill" to (r.initial!!.first().toInt() and 255)) else emptyMap()
        }
        val selected = additionalReads.indexOfFirst { it != 0 }
        val baseSchema = if (repeatLimit != 0) 5 else if (audioCompletion != null) 4 else if (hdmiStatus != null) 3 else if (selected < 0) 1 else 2
        return linkedMapOf(
            "schema" to if (hasImageIrq106Status) 6 else baseSchema,
            "regions" to savedRegions,
        ) + hdmiState() + audioState() + irq106State() +
            (ispAutoSync?.let { mapOf(IspAutoSync.KEY to it.snapshot()) }.orEmpty()) +
            (if (hasImageIrq106Status) mapOf("baseSchema" to baseSchema) else emptyMap()) +
            if (selected < 0) emptyMap() else mapOf("f01cExtension" to mapOf(
            "regionId" to regions[selected].id, "initialLimit" to checkNotNull(regions[selected].retainedDiagnostic).successfulReadLimit,
            "additionalReads" to additionalReads[selected]))
    }
    fun restoreSnapshot(data: Map<*, *>, allowPromotedRegions: Boolean = false) {
        prepareSnapshotRestore(data, allowPromotedRegions).invoke()
    }
    fun prepareSnapshotRestore(data: Map<*, *>, allowPromotedRegions: Boolean = false): () -> Unit {
        check(!closed && !accessRefused && journal.count == 0) { "Discovery snapshot restore requires a clean open bus" }
        val commit = ArrayList<() -> Unit>()
        val hasSavedIrq106 = data["schema"] == 6
        require(hasSavedIrq106 == (ImageIrq106Status.KEY in data) && hasSavedIrq106 == ("baseSchema" in data))
        val promotedIrq106 = hasSavedIrq106 && configuredIrq106Profile == null &&
            allowPromotedRegions && Image70000.REGION in promotedRegions
        val restoredIrq106 = if (hasSavedIrq106) {
            if (promotedIrq106) {
                ImageIrq106Status.parseState(data[ImageIrq106Status.KEY])
                null
            } else {
                val targetProfile = requireNotNull(configuredIrq106Profile) { "Snapshot requires the IRQ106 status profile" }
                val saved = ImageIrq106Status.parseState(data[ImageIrq106Status.KEY])
                when {
                    saved.profile == targetProfile -> saved
                    allowPromotedRegions && saved.profile == ImageIrq106Status.PROFILE &&
                        targetProfile == ImageIrq106Status.REPEATABLE_PROFILE &&
                        (saved.phase == ImageIrq106Status.IDLE || saved.phase == ImageIrq106Status.FINISHED) ->
                        ImageIrq106Status.State(
                            profile = ImageIrq106Status.REPEATABLE_PROFILE,
                            phase = saved.phase,
                            mask = 0L,
                            completedCycles = if (saved.phase == ImageIrq106Status.FINISHED) 1 else 0,
                        )
                    else -> throw IllegalArgumentException(
                        "IRQ106 status profile mismatch: saved=${saved.profile}/${saved.phase}, target=$targetProfile"
                    )
                }
            }
        } else {
            require(!hasImageIrq106Status || allowPromotedRegions) { "IRQ106 status requires explicit promotion" }
            configuredIrq106Profile?.let(ImageIrq106Status::initial)
        }
        val schema = if (hasSavedIrq106) data["baseSchema"] else data["schema"]
        require(schema in listOf(1, 2, 3, 4, 5)) { "Unsupported DiscoveryBus snapshot schema" }
        val hasCompletion = schema == 4 || schema == 5
        require(hasCompletion == ("audioF01cCompletion" in data))
        require((schema == 5) == ("audioF01cRepeats" in data))
        val completion = if (hasCompletion) data["audioF01cCompletion"] as Map<*, *> else null
        val extension = data["f01cExtension"] as? Map<*, *>
        require(("f01cExtension" in data) == (extension != null) && (hasCompletion || schema == 3 || (schema == 2) == (extension != null)))
        require(hasCompletion || (schema == 3) == ("hdmiStatus" in data))
        if ("hdmiStatus" in data) {
            val activeHdmi = hdmiStatus
            if (activeHdmi != null) {
                val restored = activeHdmi.restored(data["hdmiStatus"], allowPromotedRegions)
                commit.add { hdmiStatus = restored }
            } else {
                require(allowPromotedRegions && HdmiStatusSamples.REGION in promotedRegions) {
                    "HDMI status requires active soc-hdmi promotion or legacy hdmiStatus configuration"
                }
            }
        }
        else require(hdmiStatus == null || allowPromotedRegions) { "HDMI status requires explicit promotion" }
        val selected = extension?.let { f01cIndex(it["regionId"] as String) } ?: -1
        val extra = extension?.let {
            require(it["initialLimit"] == regions[selected].retainedDiagnostic!!.successfulReadLimit)
            (it["additionalReads"] as Int).also { count -> require(count in 1..MAX_TOTAL_EXTRA_F01C_READS) }
        } ?: 0
        val savedRegions = data["regions"] as? Map<*, *> ?: error("Missing DiscoveryBus regions map")
        for (id in channelControlAddresses.keys) {
            if ((savedRegions[id] as? Map<*, *>)?.get("strategy") == DiscoveryStrategy.CHANNEL_CONTROL.id)
                require((allowPromotedRegions && id in promotedRegions) ||
                    regions.any { it.id == id && it.strategy == DiscoveryStrategy.CHANNEL_CONTROL }) { "Snapshot requires $id readback" }
        }
        if (regions.any { it.strategy.isImageResultSelector10 })
            require(IMAGE_RESULT_SURFACE in savedRegions) { "Selector 10 restore requires the original image result state" }
        if (RETAINED_6C000_REGION !in savedRegions) c000StopReply?.let {
            commit.add(it.prepareRestore(emptyMap<String, Any>(), 0, allowPromotedRegions))
        }
        if (hasSavedIrq106) require(Image70000.REGION in savedRegions)
        for (id in retainedConfigRegions.filter { it in savedRegions }) {
            val region = regions.singleOrNull { it.id == id }
            if (region == null) {
                require(allowPromotedRegions && id in promotedRegions) {
                    "Promoted legacy region $id has active discovery state without active component port or legacy input"
                }
                continue
            }
            val row = savedRegions[id] as Map<*, *>
            if (id != RETAINED_6C000_REGION || c000StopReply == null) {
                require("completionPhase" !in row)
                if ("completion" in row) require(region.clearC204Start && row["completion"] == C204_COMPLETION)
                else require(!region.clearC204Start || allowPromotedRegions)
            }
            require(row["strategy"] == DiscoveryStrategy.RETAINED.id && row["initialFill"] == (region.initial!!.first().toInt() and 255)) {
                "Retained seed differs from snapshot for $id"
            }
        }
        val savedTail = TAIL_49500_REGION in savedRegions
        if (savedTail) require(WRITE_49530_REGION in savedRegions)
        val write49530Strategy = regions.singleOrNull { it.id == WRITE_49530_REGION }?.strategy
        val tail49500Strategy = regions.singleOrNull { it.id == TAIL_49500_REGION }?.strategy
        val savedWrite49530 = savedRegions[WRITE_49530_REGION] as? Map<*, *>
        val savedTail49500 = savedRegions[TAIL_49500_REGION] as? Map<*, *>
        val promoteWrite49530 = allowPromotedRegions && write49530Strategy == DiscoveryStrategy.WRITE_49530_REPEATABLE &&
            savedWrite49530?.get("strategy") == DiscoveryStrategy.WRITE_49530.id
        val promoteTail49500 = allowPromotedRegions && tail49500Strategy == DiscoveryStrategy.TAIL_49500_REPEATABLE &&
            savedTail49500?.get("strategy") == DiscoveryStrategy.TAIL_49500.id
        val pollStrategy = regions.singleOrNull { it.id == POLL_7801C_REGION }?.strategy
        val savedPoll = savedRegions[POLL_7801C_REGION] as? Map<*, *>
        val controlStrategy = regions.singleOrNull { it.id == IMAGE_50000_CONTROL_REGION }?.strategy
        val savedControl = savedRegions[IMAGE_50000_CONTROL_REGION] as? Map<*, *>
        val promoteControl = allowPromotedRegions && (
            (controlStrategy == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE &&
                savedControl?.get("strategy") == DiscoveryStrategy.IMAGE_50000_CONTROL.id &&
                savedControl["position"].toString() == "2") ||
            (controlStrategy == DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE &&
                savedControl?.get("strategy") == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE.id &&
                savedControl["position"].toString() == "4"))
        if (controlStrategy == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE ||
            controlStrategy == DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE) requireNotNull(savedControl)
        val promotePoll = allowPromotedRegions && (
            (pollStrategy == DiscoveryStrategy.SETUP_REPEAT_7801C &&
                savedPoll?.get("strategy") == DiscoveryStrategy.POLL_7801C.id && savedPoll["position"].toString() == "2") ||
            (pollStrategy == DiscoveryStrategy.SETUP_REPEATABLE_7801C && (
                (savedPoll?.get("strategy") == DiscoveryStrategy.POLL_7801C.id && savedPoll["position"].toString() == "2") ||
                (savedPoll?.get("strategy") == DiscoveryStrategy.SETUP_REPEAT_7801C.id && savedPoll["position"].toString() == "4"))))
        val imageStrategy = regions.singleOrNull { it.id == Image70000.REGION }?.strategy
        val savedImage = savedRegions[Image70000.REGION] as? Map<*, *>
        if (Image70000.includesDriver(imageStrategy)) requireNotNull(savedImage) {
            "Image70000 driver restore requires the saved setup and configuration cursor"
        }
        val imagePredecessor = if (allowPromotedRegions) when (imageStrategy) {
            DiscoveryStrategy.IMAGE_70000_CONFIG -> DiscoveryStrategy.IMAGE_70000
            DiscoveryStrategy.IMAGE_70000_UPDATE -> DiscoveryStrategy.IMAGE_70000_CONFIG.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_DRIVER -> DiscoveryStrategy.IMAGE_70000_UPDATE.takeIf {
                savedImage?.get("position").toString() == Image70000.driverStart.toString()
            }
            DiscoveryStrategy.IMAGE_70000_GETTERS -> DiscoveryStrategy.IMAGE_70000_DRIVER
            DiscoveryStrategy.IMAGE_70000_TEARDOWN -> DiscoveryStrategy.IMAGE_70000_GETTERS.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_REINIT -> DiscoveryStrategy.IMAGE_70000_TEARDOWN.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_CONFIG_READS -> DiscoveryStrategy.IMAGE_70000_REINIT.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_SETTER -> DiscoveryStrategy.IMAGE_70000_CONFIG_READS.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE -> DiscoveryStrategy.IMAGE_70000_SETTER.takeIf {
                savedImage?.get("position").toString() == Image70000.limit(it).toString()
            }
            DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS -> when (savedImage?.get("strategy")) {
                DiscoveryStrategy.IMAGE_70000_SETTER.id -> DiscoveryStrategy.IMAGE_70000_SETTER.takeIf {
                    savedImage["position"].toString() == Image70000.limit(it).toString()
                }
                DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE.id -> DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE.takeIf {
                    savedImage["position"].toString() == Image70000.limit(it).toString()
                }
                else -> null
            }
            DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK -> DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS.takeIf {
                val rawPos = savedImage?.get("position")
                (rawPos is Int || rawPos is Long) &&
                    (rawPos as Number).toLong() in Image70000.secondDriverStart.toLong()..Image70000.statefulStart.toLong()
            }
            else -> null
        }?.takeIf { savedImage?.get("strategy") == it.id } else null
        val config6eStrategy = regions.singleOrNull { it.id == Image6e000.REGION }?.strategy
        val saved6e = savedRegions[Image6e000.REGION] as? Map<*, *>
        if (config6eStrategy == DiscoveryStrategy.IMAGE_6E_DISABLE ||
            config6eStrategy == DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG ||
            config6eStrategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE) requireNotNull(saved6e) {
            "Image6e000 restore requires the saved configuration cursor"
        }
        val promote6e = allowPromotedRegions && when (config6eStrategy) {
            DiscoveryStrategy.IMAGE_6E_STOP -> saved6e?.get("strategy") == DiscoveryStrategy.IMAGE_6E_CONFIG.id
            DiscoveryStrategy.IMAGE_6E_DISABLE -> saved6e?.get("strategy") in
                listOf(DiscoveryStrategy.IMAGE_6E_CONFIG.id, DiscoveryStrategy.IMAGE_6E_STOP.id) &&
                saved6e?.get("position").toString() == Image6e000.limit(DiscoveryStrategy.IMAGE_6E_CONFIG).toString()
            DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG -> saved6e?.get("strategy") == DiscoveryStrategy.IMAGE_6E_DISABLE.id &&
                saved6e["position"].toString() == Image6e000.limit(DiscoveryStrategy.IMAGE_6E_DISABLE).toString()
            DiscoveryStrategy.IMAGE_6E_REPEATABLE -> saved6e?.get("strategy") == DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG.id &&
                (saved6e["position"] is Int || saved6e["position"] is Long) &&
                (saved6e["position"] as Number).toLong() in
                    Image6e000.limit(DiscoveryStrategy.IMAGE_6E_DISABLE).toLong()..Image6e000.limit(DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG).toLong()
            else -> false
        }
        val image68000Strategy = regions.singleOrNull { it.id == Image68000.REGION }?.strategy
        val saved68000 = savedRegions[Image68000.REGION] as? Map<*, *>
        if (image68000Strategy == DiscoveryStrategy.IMAGE_68000_TWICE ||
            image68000Strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE) requireNotNull(saved68000) {
            "Image68000 restore requires the saved predecessor cursor"
        }
        val promote68000 = allowPromotedRegions && (
            (image68000Strategy == DiscoveryStrategy.IMAGE_68000_TWICE &&
                saved68000?.get("strategy") == DiscoveryStrategy.IMAGE_68000.id &&
                saved68000["position"].toString() == Image68000.limit.toString()) ||
            (image68000Strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE &&
                saved68000?.get("strategy") == DiscoveryStrategy.IMAGE_68000_TWICE.id &&
                saved68000["position"].toString() == Image68000.limit(DiscoveryStrategy.IMAGE_68000_TWICE).toString()))
        val tablePairStrategy = regions.singleOrNull { it.id == Image51000.TABLE }?.strategy
        val savedTable = savedRegions[Image51000.TABLE] as? Map<*, *>
        val savedEmpty = savedRegions[Image51000.EMPTY] as? Map<*, *>
        require((Image51000.TABLE in savedRegions) == (Image51000.EMPTY in savedRegions))
        if (tablePairStrategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE ||
            tablePairStrategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE) {
            require(savedTable != null && savedEmpty != null) {
                "Image51000 restore requires the saved predecessor table pair cursors"
            }
        }
        val promoteTablePairTwice = allowPromotedRegions && tablePairStrategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE &&
            savedTable?.get("strategy") == DiscoveryStrategy.IMAGE_TABLE_PAIR.id &&
            savedEmpty?.get("strategy") == DiscoveryStrategy.IMAGE_TABLE_PAIR.id &&
            savedTable["position"].toString() == Image51000.TABLE_LIMIT.toString() &&
            savedEmpty["position"].toString() == Image51000.EMPTY_LIMIT.toString()
        val promoteTablePairRepeatable = allowPromotedRegions && tablePairStrategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE &&
            savedTable?.get("strategy") == DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE.id &&
            savedEmpty?.get("strategy") == DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE.id &&
            savedTable["position"].toString() == Image51000.TOTAL_TABLE_LIMIT.toString() &&
            savedEmpty["position"].toString() == Image51000.TOTAL_EMPTY_LIMIT.toString()
        val promoteTablePair = promoteTablePairTwice || promoteTablePairRepeatable
        val promotedStepLoopRegions = setOf(
            WRITE_49530_REGION,
            TAIL_49500_REGION,
            POLL_7801C_REGION,
            Image51000.TABLE,
            Image51000.EMPTY,
            Image68000.REGION,
            Image6e000.REGION,
            Image70000.REGION,
            IMAGE_50000_CONTROL_REGION,
        )
        for ((id, strategy) in mapOf(Image51000.TABLE to tablePairStrategy,
            Image51000.EMPTY to tablePairStrategy, Image68000.REGION to image68000Strategy,
            Image6e000.REGION to config6eStrategy, Image70000.REGION to imageStrategy,
            IMAGE_50000_CONTROL_REGION to controlStrategy, POLL_7801C_REGION to pollStrategy,
            WRITE_49530_REGION to write49530Strategy, TAIL_49500_REGION to tail49500Strategy)) {
            if (id !in savedRegions) continue
            if (id in promotedStepLoopRegions && regions.none { it.id == id }) {
                require(allowPromotedRegions && id in promotedRegions) { "Snapshot requires selected $id profile" }
                continue
            }
            requireNotNull(strategy) { "Snapshot requires selected $id profile" }
            require(regions.any { it.id == id && it.strategy == strategy }) { "Snapshot requires selected $id profile" }
            val row = savedRegions[id] as? Map<*, *> ?: error("Missing $id state")
            fun word(key: String): Long {
                val value = row[key]
                require(value is Int || value is Long) { "$id $key requires an integer" }
                return (value as Number).toLong()
            }
            val position = word("position")
            val limit = when (id) {
                Image51000.TABLE -> when {
                    promoteTablePairTwice -> Image51000.TABLE_LIMIT
                    promoteTablePairRepeatable -> Image51000.TOTAL_TABLE_LIMIT
                    strategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> Int.MAX_VALUE
                    else -> Image51000.tableLimit(strategy)
                }.toLong()
                Image51000.EMPTY -> when {
                    promoteTablePairTwice -> Image51000.EMPTY_LIMIT
                    promoteTablePairRepeatable -> Image51000.TOTAL_EMPTY_LIMIT
                    strategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> Int.MAX_VALUE
                    else -> Image51000.emptyLimit(strategy)
                }.toLong()
                Image68000.REGION -> when {
                    promote68000 && strategy == DiscoveryStrategy.IMAGE_68000_TWICE -> Image68000.limit(DiscoveryStrategy.IMAGE_68000)
                    promote68000 -> Image68000.limit(DiscoveryStrategy.IMAGE_68000_TWICE)
                    strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE -> Int.MAX_VALUE
                    else -> Image68000.limit(strategy)
                }.toLong()
                Image6e000.REGION -> when {
                    !promote6e && strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE -> Int.MAX_VALUE
                    !promote6e -> Image6e000.limit(strategy)
                    strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE -> Image6e000.limit(DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG)
                    strategy == DiscoveryStrategy.IMAGE_6E_SECOND_CONFIG -> Image6e000.limit(DiscoveryStrategy.IMAGE_6E_DISABLE)
                    else -> Image6e000.limit(DiscoveryStrategy.IMAGE_6E_CONFIG)
                }.toLong()
                Image70000.REGION -> when {
                    imagePredecessor != null -> Image70000.limit(imagePredecessor)
                    strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK -> Int.MAX_VALUE
                    else -> Image70000.limit(strategy)
                }.toLong()
                IMAGE_50000_CONTROL_REGION -> when {
                    strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_REPEATABLE -> if (promoteControl) 4L else Int.MAX_VALUE.toLong()
                    strategy == DiscoveryStrategy.IMAGE_50000_CONTROL_TWICE -> 4L
                    else -> 2L
                }
                TAIL_49500_REGION -> if (strategy == DiscoveryStrategy.TAIL_49500_REPEATABLE && !promoteTail49500) Int.MAX_VALUE.toLong() else 1L
                WRITE_49530_REGION -> when {
                    strategy == DiscoveryStrategy.WRITE_49530_REPEATABLE && !promoteWrite49530 -> Int.MAX_VALUE.toLong()
                    savedTail -> 3L
                    else -> 2L
                }
                else -> when (strategy) {
                    DiscoveryStrategy.SETUP_REPEATABLE_7801C -> if (promotePoll) 4L else Int.MAX_VALUE.toLong()
                    DiscoveryStrategy.SETUP_REPEAT_7801C -> 4L
                    else -> 2L
                }
            }
            if (id == TAIL_49500_REGION && position > 0L) {
                val pair = savedRegions[WRITE_49530_REGION] as Map<*, *>
                val pairPos = (pair["position"] as Number).toLong()
                require(if (strategy == DiscoveryStrategy.TAIL_49500_REPEATABLE) pairPos in (position * 3L)..(position * 3L + 3L) else pairPos == 3L)
            }
            if (id == Image51000.EMPTY && (strategy != DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE || promoteTablePairRepeatable)) {
                val table = savedRegions[Image51000.TABLE] as Map<*, *>
                val rawTablePos = table["position"]
                require(rawTablePos is Int || rawTablePos is Long) { "${Image51000.TABLE} position requires an integer" }
                val tablePos = (rawTablePos as Number).toLong()
                val effectiveTableLimit = if (promoteTablePairRepeatable) Image51000.TOTAL_TABLE_LIMIT.toLong()
                    else Image51000.tableLimit(strategy).toLong()
                when {
                    position == 0L -> require(tablePos <= Image51000.TABLE_LIMIT.toLong())
                    position < Image51000.EMPTY_LIMIT.toLong() -> require(tablePos == Image51000.TABLE_LIMIT.toLong())
                    position == Image51000.EMPTY_LIMIT.toLong() -> require(tablePos in Image51000.TABLE_LIMIT.toLong()..effectiveTableLimit)
                    else -> require(tablePos == Image51000.TOTAL_TABLE_LIMIT.toLong())
                }
            }
            if (id == Image6e000.REGION) {
                val rowHas6eBank = row["strategy"] == DiscoveryStrategy.IMAGE_6E_REPEATABLE.id
                require(("image6eBank" in row) == rowHas6eBank)
                if (strategy == DiscoveryStrategy.IMAGE_6E_REPEATABLE) {
                    val bank = if (promote6e) Image6e000Bank()
                        else Image6e000Bank.restore(requireNotNull(row["image6eBank"] as? Map<*, *>) {
                            "Restore Image6e000 repeatable bank or explicitly promote second-config"
                        })
                    commit.add { image6eBank = bank }
                }
            }
            val hasDriverControl = id == Image70000.REGION && Image70000.includesDriver(strategy) &&
                position > Image70000.driverControlPosition
            val retained = word("retainedValue")
            require(row["retainedWritten"] == hasDriverControl &&
                (if (hasDriverControl) retained and 0xffff0000 == retained else retained == 0L))
            val irq106 = restoredIrq106.takeIf { id == Image70000.REGION }
            val status = irq106?.phase
            if (id == Image70000.REGION) {
                val rowHasSetter = row["strategy"] == DiscoveryStrategy.IMAGE_70000_SETTER.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK.id
                val rowHasSecondUpdate = row["strategy"] == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK.id
                val rowHasStatefulBank = row["strategy"] == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK.id &&
                    position >= Image70000.statefulStart.toLong()
                require(("t79Setter" in row) == rowHasSetter)
                require(("t79SecondUpdate" in row) == rowHasSecondUpdate)
                require(("t79StatefulBank" in row) == rowHasStatefulBank)
                if (strategy == DiscoveryStrategy.IMAGE_70000_SETTER) {
                    require(status == ImageIrq106Status.FINISHED) { "T79 setter requires the finished IRQ106 state" }
                    val setter = if (imagePredecessor != null) T79ImageConfigSetter()
                        else T79ImageConfigSetter.restore(requireNotNull(row["t79Setter"] as? Map<*, *>) {
                            "Restore the T79 setter state or explicitly promote its completed 42-read predecessor"
                        })
                    require(position == (Image70000.limit(DiscoveryStrategy.IMAGE_70000_CONFIG_READS) + setter.accesses).toLong())
                    commit.add { t79Setter = setter }
                }
                if (strategy == DiscoveryStrategy.IMAGE_70000_SECOND_UPDATE) {
                    require(status == ImageIrq106Status.FINISHED) { "T79 second update requires the finished IRQ106 state" }
                    val setter = T79ImageConfigSetter.restore(requireNotNull(row["t79Setter"] as? Map<*, *>) {
                        "T79 second update requires the saved completed T79 setter state"
                    })
                    require(setter.complete) { "T79 second update requires completed T79 setter state" }
                    val update = if (imagePredecessor != null) T79ImageSecondUpdate()
                        else T79ImageSecondUpdate.restore(requireNotNull(row["t79SecondUpdate"] as? Map<*, *>) {
                            "Restore the T79 second update state or explicitly promote its completed setter predecessor"
                        })
                    require(position == (Image70000.limit(DiscoveryStrategy.IMAGE_70000_SETTER) + update.accesses).toLong())
                    commit.add {
                        t79Setter = setter
                        t79SecondUpdate = update
                    }
                }
                if (strategy == DiscoveryStrategy.IMAGE_70000_SECOND_DRIVER_GETTERS) {
                    require(status == ImageIrq106Status.FINISHED) {
                        "T79 second driver/getters requires the finished IRQ106 state"
                    }
                    val setter = T79ImageConfigSetter.restore(requireNotNull(row["t79Setter"] as? Map<*, *>) {
                        "T79 second driver/getters requires the saved completed T79 setter state"
                    })
                    require(setter.complete) { "T79 second driver/getters requires completed T79 setter state" }
                    val update = if (imagePredecessor == DiscoveryStrategy.IMAGE_70000_SETTER) T79ImageSecondUpdate()
                        else T79ImageSecondUpdate.restore(requireNotNull(row["t79SecondUpdate"] as? Map<*, *>) {
                            "Restore the T79 second update state or explicitly promote its completed setter or update predecessor"
                        })
                    val setterLimit = Image70000.limit(DiscoveryStrategy.IMAGE_70000_SETTER).toLong()
                    val driverStart = Image70000.secondDriverStart.toLong()
                    if (position < driverStart) {
                        require(position == setterLimit + update.accesses) {
                            "T79 second update cursor differs from saved write count"
                        }
                    } else {
                        require(update.complete) {
                            "T79 second driver/getters requires completed 65-write second update state"
                        }
                    }
                    commit.add {
                        t79Setter = setter
                        t79SecondUpdate = update
                    }
                }
                if (strategy == DiscoveryStrategy.IMAGE_70000_STATEFUL_BANK) {
                    require(status == ImageIrq106Status.FINISHED ||
                        (irq106?.repeatable == true && irq106.completedCycles >= 1)) {
                        "T79 stateful ISP bank requires the finished IRQ106 state"
                    }
                    val setter = T79ImageConfigSetter.restore(requireNotNull(row["t79Setter"] as? Map<*, *>) {
                        "T79 stateful ISP bank requires the saved completed T79 setter state"
                    })
                    require(setter.complete) { "T79 stateful ISP bank requires completed T79 setter state" }
                    val update = T79ImageSecondUpdate.restore(requireNotNull(row["t79SecondUpdate"] as? Map<*, *>) {
                        "T79 stateful ISP bank requires the saved completed T79 second update state"
                    })
                    require(update.complete) { "T79 stateful ISP bank requires completed 65-write second update state" }
                    require(position >= Image70000.secondDriverStart.toLong()) {
                        "T79 stateful ISP bank requires cursor >= ${Image70000.secondDriverStart}"
                    }
                    val bank = when {
                        position < Image70000.statefulStart.toLong() -> null
                        imagePredecessor != null -> T79StatefulIspBank.seed(setter, update, retained)
                        else -> T79StatefulIspBank.restore(requireNotNull(row["t79StatefulBank"] as? Map<*, *>) {
                            "T79 stateful ISP bank snapshot requires saved bank state"
                        }).also { restored ->
                            require(retained == restored.controlHighHalf()) {
                                "T79 stateful ISP bank retainedValue differs from bank 0x2e4 high half"
                            }
                        }
                    }
                    commit.add {
                        t79Setter = setter
                        t79SecondUpdate = update
                        t79StatefulBank = bank
                    }
                }
            }
            if (id == Image70000.REGION && Image70000.includesSecondSetup(strategy) && status != null &&
                (imagePredecessor != null || position > Image70000.limit(DiscoveryStrategy.IMAGE_70000_TEARDOWN)))
                require(status == ImageIrq106Status.FINISHED)
            if (status != null && status != ImageIrq106Status.IDLE)
                require(position >= Image70000.limit(DiscoveryStrategy.IMAGE_70000_DRIVER))
            require(position in 0L..limit && word("accessAttempts") == position + (irq106?.totalAccesses ?: 0) &&
                (row["strategy"] == strategy.id || (id == IMAGE_50000_CONTROL_REGION && promoteControl) ||
                    (id == POLL_7801C_REGION && promotePoll) ||
                    (id == WRITE_49530_REGION && promoteWrite49530) || (id == TAIL_49500_REGION && promoteTail49500) ||
                    (id == Image70000.REGION && imagePredecessor != null) ||
                    (id == Image6e000.REGION && promote6e) || (id == Image68000.REGION && promote68000) ||
                    ((id == Image51000.TABLE || id == Image51000.EMPTY) && promoteTablePair)) &&
                row["fixedSelected"] == false && word("selectedValue") == 0L &&
                listOf("mutatedBytesBase64", "scriptTailSelection").all { it in row && row[it] == null })
        }
        require(selected < 0 || regions[selected].id in savedRegions)
        val completed = completion?.let { f01cIndex(it["id"] as String) } ?: -1
        require(completed < 0 || regions[completed].id in savedRegions)
        val activeIds = regions.map { it.id }.toSet()
        require(IMAGE_RESULT_SURFACE !in savedRegions || IMAGE_RESULT_SURFACE in activeIds ||
            (allowPromotedRegions && IMAGE_RESULT_SURFACE in promotedRegions))
        val savedReplies = imageReplyIds.filter { it in savedRegions }
        require(savedReplies.size <= 1)
        val pairPrefix = if (IMAGE_REPLY_PAIRS in activeIds) {
            val previous = savedReplies.singleOrNull()
            require(previous == IMAGE_REPLY_PAIRS || (allowPromotedRegions && previous == IMAGE_REPLY_820041))
            val row = savedRegions[previous] as Map<*, *>
            val target = regions.single { it.id == IMAGE_REPLY_PAIRS }
            if (previous == IMAGE_REPLY_820041) {
                require(target.strategy == DiscoveryStrategy.SCRIPT)
                require(validatePairCursor(row, 8) == 8)
                row + mapOf("position" to IMAGE_REPLY_PAIR_WORDS, "accessAttempts" to IMAGE_REPLY_PAIR_WORDS.toLong())
            } else {
                val savedProgrammed = row["strategy"] == DiscoveryStrategy.PROGRAMMED_REPLY.id ||
                    row["strategy"] == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE.id
                val savedStrategy = when (row["strategy"]) {
                    DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE.id -> DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE
                    DiscoveryStrategy.PROGRAMMED_REPLY.id -> DiscoveryStrategy.PROGRAMMED_REPLY
                    else -> DiscoveryStrategy.INTERLEAVED_REPLY
                }
                val hasSingle = row["strategy"] == DiscoveryStrategy.INTERLEAVED_REPLY.id || savedProgrammed
                require(("programmedReply" in row) == savedProgrammed)
                require(("reply01Selection" in row) == hasSingle && ("reply01Position" in row) == hasSingle)
                if (hasSingle) {
                    require(target.strategy.interleavesReply &&
                        row["reply01Selection"] == reply01State(target)["reply01Selection"])
                    val raw = row["reply01Position"]
                    require(raw is Int || raw is Long)
                    val words = Math.toIntExact((raw as Number).toLong())
                    require(words in 0..8)
                    val targetProfile = if (target.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE)
                        ProgrammedImageReply.REPEATABLE_PROFILE else ProgrammedImageReply.PROFILE
                    val restoredModel = if (savedProgrammed) {
                        require(target.strategy.isProgrammedReply)
                        require(savedStrategy == target.strategy ||
                            (allowPromotedRegions && savedStrategy == DiscoveryStrategy.PROGRAMMED_REPLY &&
                                target.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE))
                        ProgrammedImageReply.restore(row["programmedReply"] as Map<*, *>, targetProfile, allowPromotedRegions).also { model ->
                            model.verifyBacking { address ->
                                val bank = regions[find(address)]
                                val state = savedRegions[bank.id] as Map<*, *>
                                val contents = (state["mutatedBytesBase64"] as? String)?.let(java.util.Base64.getDecoder()::decode)
                                    ?: checkNotNull(bank.initial)
                                java.nio.ByteBuffer.wrap(contents).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                    .getInt((address - bank.base).toInt()).toLong() and 0xffffffffL
                            }
                        }
                    } else if (target.strategy.isProgrammedReply) {
                        require(allowPromotedRegions && words == 8)
                        ProgrammedImageReply(targetProfile)
                    } else null
                    val pairLimit = if (savedStrategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE) Int.MAX_VALUE else IMAGE_REPLY_TOTAL_WORDS
                    val pairWords = validatePairCursor(row, pairLimit, words + (restoredModel?.totalWords ?: 0), savedStrategy)
                    require(words !in 1..7 || pairWords % 8 == 0)
                    require(restoredModel?.words !in 1..7 || pairWords % 8 == 0)
                    require(restoredModel == null || restoredModel.totalWords == 0 || words == 8)
                    commit.add { reply01Position = words; programmedReply = restoredModel }
                } else {
                    validatePairCursor(row, IMAGE_REPLY_TOTAL_WORDS)
                    require(!target.strategy.isProgrammedReply)
                    require(target.strategy == DiscoveryStrategy.SCRIPT || allowPromotedRegions)
                    commit.add { reply01Position = 0 }
                }
                null
            }
        } else null
        var promotedReplyPredecessor: String? = null
        savedReplies.singleOrNull()?.let { previous ->
            if (previous !in activeIds) {
                if (allowPromotedRegions && previous in promotedRegions) {
                    return@let
                }
                val next = when (previous) {
                    IMAGE_REPLY_820001_AF -> IMAGE_REPLY_820040
                    IMAGE_REPLY_820040 -> IMAGE_REPLY_820041
                    IMAGE_REPLY_820041 -> IMAGE_REPLY_PAIRS
                    else -> null
                }
                require(allowPromotedRegions && next in activeIds)
                val row = savedRegions[previous] as Map<*, *>
                require(row["strategy"] == DiscoveryStrategy.SCRIPT.id &&
                    row["position"].toString() == "8" && row["accessAttempts"].toString() == "8" &&
                    row["scriptTailSelection"] == null)
                promotedReplyPredecessor = previous
            }
        }
        val savedLiveView = ImageLiveViewBanks.REGION_IDS.count { it in savedRegions }
        require(savedLiveView == 0 || savedLiveView == ImageLiveViewBanks.REGION_IDS.size) {
            "Partial ImageLiveViewBanks snapshot state"
        }
        if (savedLiveView > 0) {
            val primaryRow = savedRegions[ImageLiveViewBanks.PRIMARY_REGION] as? Map<*, *>
                ?: error("Missing ${ImageLiveViewBanks.PRIMARY_REGION} state")
            val restoredBanks = ImageLiveViewBanks.restore(primaryRow[ImageLiveViewBanks.KEY])
            for (id in ImageLiveViewBanks.REGION_IDS) {
                val row = savedRegions[id] as? Map<*, *> ?: error("Missing $id state")
                require(row["strategy"] == DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS.id &&
                    row["position"].toString() == "0" &&
                    row["retainedWritten"] == false && row["retainedValue"].toString() == "0" &&
                    row["fixedSelected"] == false && row["selectedValue"].toString() == "0" &&
                    listOf("mutatedBytesBase64", "scriptTailSelection").all { it in row && row[it] == null } &&
                    (id == ImageLiveViewBanks.PRIMARY_REGION) == (ImageLiveViewBanks.KEY in row)) {
                    "Invalid ImageLiveViewBanks snapshot metadata for $id"
                }
            }
            if (liveViewBanks != null) {
                commit.add { liveViewBanks = restoredBanks }
            } else {
                require(allowPromotedRegions && ImageLiveViewBanks.REGION_IDS.all { it in promotedRegions }) {
                    "Snapshot requires selected imageLiveViewBanks profile"
                }
            }
        } else if (liveViewBanks != null) {
            require(allowPromotedRegions) { "ImageLiveViewBanks requires explicit promotion" }
            commit.add { liveViewBanks = ImageLiveViewBanks() }
        }
        val savedScaler = savedRegions[ImageScalerF000.REGION] as? Map<*, *>
        if (savedScaler != null) {
            val targetScalerProfile = requireNotNull(configuredScalerProfile) { "Snapshot requires selected imageScalerF000 profile" }
            val restoredScaler = ImageScalerF000.restore(savedScaler[ImageScalerF000.KEY], targetScalerProfile, allowPromotedRegions)
            require(savedScaler["strategy"] == DiscoveryStrategy.IMAGE_SCALER_F000.id &&
                savedScaler["position"].toString() == "0" &&
                savedScaler["retainedWritten"] == false && savedScaler["retainedValue"].toString() == "0" &&
                savedScaler["fixedSelected"] == false && savedScaler["selectedValue"].toString() == "0" &&
                listOf("mutatedBytesBase64", "scriptTailSelection").all { it in savedScaler && savedScaler[it] == null } &&
                ImageScalerF000.KEY in savedScaler) {
                "Invalid ImageScalerF000 snapshot metadata"
            }
            commit.add {
                if (allowPromotedRegions && restoredScaler.profile == ImageScalerF000.IRQ133_PROFILE) {
                    restoredScaler.armPendingKickOnRestore(context.tick())
                }
                scalerF000 = restoredScaler
            }
        } else if (scalerF000 != null) {
            require(allowPromotedRegions) { "ImageScalerF000 requires explicit promotion" }
            val targetScalerProfile = requireNotNull(configuredScalerProfile)
            commit.add { scalerF000 = ImageScalerF000(targetScalerProfile) }
        }
        val savedFace = savedRegions[ImageFace7c000.REGION] as? Map<*, *>
        if (savedFace != null && savedFace["strategy"] == DiscoveryStrategy.IMAGE_FACE_7C000.id) {
            require(face7c000 != null) { "Snapshot requires selected imageFace7c000 face-detect-irq123-v1 profile" }
            val restoredFace = ImageFace7c000.restore(savedFace[ImageFace7c000.KEY])
            require(
                savedFace["position"].toString() == "0" &&
                    savedFace["retainedWritten"] == false && savedFace["retainedValue"].toString() == "0" &&
                    savedFace["fixedSelected"] == false && savedFace["selectedValue"].toString() == "0" &&
                    listOf("mutatedBytesBase64", "scriptTailSelection").all { it in savedFace && savedFace[it] == null } &&
                    ImageFace7c000.KEY in savedFace,
            ) {
                "Invalid ImageFace7c000 snapshot metadata"
            }
            commit.add { face7c000 = restoredFace }
        } else if (face7c000 != null) {
            require(allowPromotedRegions) { "ImageFace7c000 requires explicit promotion" }
            commit.add { face7c000 = ImageFace7c000(ImageFace7c000.IRQ130_PROFILE) }
        }
        val savedBootRomIds = BootRomColdStart.ALL_REGION_IDS.filter { it in savedRegions }.toSet()
        require(
            savedBootRomIds.isEmpty() ||
                savedBootRomIds == BootRomColdStart.REGION_IDS ||
                savedBootRomIds == BootRomColdStart.STAGE1_DDR_REGION_IDS ||
                savedBootRomIds == BootRomColdStart.QSPI_KERNEL_REGION_IDS ||
                savedBootRomIds == BootRomColdStart.RELEASE_COLDSTART_REGION_IDS,
        ) {
            "Partial BootRomColdStart snapshot state"
        }
        if (savedBootRomIds.isNotEmpty()) {
            val primaryRow = savedRegions[BootRomColdStart.PRIMARY_REGION] as? Map<*, *>
                ?: error("Missing ${BootRomColdStart.PRIMARY_REGION} state")
            val targetBootRomProfile = requireNotNull(configuredBootRomProfile) {
                "Snapshot requires selected bootRomColdStart profile"
            }
            val restoredBootRom = BootRomColdStart.restore(
                primaryRow[BootRomColdStart.KEY],
                targetProfile = targetBootRomProfile,
                allowPromotion = allowPromotedRegions,
            )
            for (id in savedBootRomIds) {
                val row = savedRegions[id] as? Map<*, *> ?: error("Missing $id state")
                require(row["strategy"] == DiscoveryStrategy.BOOTROM_COLD_START.id &&
                    row["position"].toString() == "0" &&
                    row["retainedWritten"] == false && row["retainedValue"].toString() == "0" &&
                    row["fixedSelected"] == false && row["selectedValue"].toString() == "0" &&
                    listOf("mutatedBytesBase64", "scriptTailSelection").all { it in row && row[it] == null } &&
                    (id == BootRomColdStart.PRIMARY_REGION) == (BootRomColdStart.KEY in row)) {
                    "Invalid BootRomColdStart snapshot metadata for $id"
                }
            }
            commit.add { bootRomColdStart = restoredBootRom }
        } else if (bootRomColdStart != null) {
            require(allowPromotedRegions) { "BootRomColdStart requires explicit promotion" }
            val targetBootRomProfile = requireNotNull(configuredBootRomProfile)
            commit.add { bootRomColdStart = BootRomColdStart(targetBootRomProfile) }
        }
        val savedScalerE000 = savedRegions[ImageScalerE000.REGION] as? Map<*, *>
        if (savedScalerE000 != null) {
            val targetScalerE000Profile = requireNotNull(configuredScalerE000Profile) { "Snapshot requires selected imageScalerE000 profile" }
            val restoredScalerE000 = ImageScalerE000.restore(savedScalerE000[ImageScalerE000.KEY], targetScalerE000Profile, allowPromotedRegions)
            require(savedScalerE000["strategy"] == DiscoveryStrategy.IMAGE_SCALER_E000.id &&
                savedScalerE000["position"].toString() == "0" &&
                savedScalerE000["retainedWritten"] == false && savedScalerE000["retainedValue"].toString() == "0" &&
                savedScalerE000["fixedSelected"] == false && savedScalerE000["selectedValue"].toString() == "0" &&
                listOf("mutatedBytesBase64", "scriptTailSelection").all { it in savedScalerE000 && savedScalerE000[it] == null } &&
                ImageScalerE000.KEY in savedScalerE000) {
                "Invalid ImageScalerE000 snapshot metadata"
            }
            commit.add {
                if (allowPromotedRegions && restoredScalerE000.profile == ImageScalerE000.IRQ132_PROFILE) {
                    restoredScalerE000.armPendingKickOnRestore(context.tick())
                }
                scalerE000 = restoredScalerE000
            }
        } else if (scalerE000 != null) {
            require(allowPromotedRegions) { "ImageScalerE000 requires explicit promotion" }
            val targetScalerE000Profile = requireNotNull(configuredScalerE000Profile)
            commit.add { scalerE000 = ImageScalerE000(targetScalerE000Profile) }
        }
        if (IspAutoSync.KEY in data) {
            val targetAutoSync = requireNotNull(configuredIspAutoSyncProfile) {
                "Snapshot requires selected ${IspAutoSync.KEY} profile"
            }
            val restoredAutoSync = IspAutoSync.restore(data[IspAutoSync.KEY], targetAutoSync)
            commit.add { ispAutoSync = restoredAutoSync }
        } else if (configuredIspAutoSyncProfile != null) {
            require(allowPromotedRegions) { "${IspAutoSync.KEY} requires explicit promotion" }
            commit.add {
                ispAutoSync = IspAutoSync(configuredIspAutoSyncProfile).apply { markRestoreProbeNeeded() }
            }
        }
        if (!allowPromotedRegions) {
            val savedKeys = savedRegions.keys.map { it.toString() }.toSet()
            require(activeIds.all { it in savedKeys }) {
                "Discovery region set differs without explicit promotion allowance"
            }
            val extraSaved = savedKeys - activeIds
            for (extraId in extraSaved) {
                val row = savedRegions[extraId] as? Map<*, *>
                val untouchedRefuse = extraId in PromotedLegacyCandidates.byId &&
                    extraId !in promotedRegions &&
                    row != null &&
                    row["strategy"] == DiscoveryStrategy.REFUSE.id &&
                    listOf("position", "accessAttempts", "retainedValue", "selectedValue").all {
                        row[it] == 0 || row[it] == 0L
                    } &&
                    row["retainedWritten"] == false &&
                    row["fixedSelected"] == false &&
                    row["mutatedBytesBase64"] == null &&
                    row["scriptTailSelection"] == null
                require(untouchedRefuse) {
                    "Discovery region set differs without explicit promotion allowance"
                }
            }
        } else {
            val extraSaved = savedRegions.keys.map { it.toString() }.toSet() - activeIds
            for (extraId in extraSaved) {
                if (extraId in promotedRegions || extraId == promotedReplyPredecessor) continue
                if (extraId in PromotedLegacyCandidates.dynamicRegionIds) {
                    error("Promoted legacy region $extraId has active discovery state without active component port or legacy input")
                }
                if (extraId !in PromotedLegacyCandidates.byId) continue
                val row = savedRegions[extraId] as? Map<*, *>
                val untouchedRefuse = row != null &&
                    row["strategy"] == DiscoveryStrategy.REFUSE.id &&
                    listOf("position", "accessAttempts", "retainedValue", "selectedValue").all {
                        row[it] == 0 || row[it] == 0L
                    } &&
                    row["retainedWritten"] == false &&
                    row["fixedSelected"] == false &&
                    row["mutatedBytesBase64"] == null &&
                    row["scriptTailSelection"] == null
                require(untouchedRefuse) {
                    "Promoted legacy region $extraId has active discovery state without active component port or legacy input"
                }
            }
        }
        val decoder = java.util.Base64.getDecoder()
        regions.forEachIndexed { i, r ->
            val row = (savedRegions[r.id] as? Map<*, *>) ?: pairPrefix.takeIf { r.id == IMAGE_REPLY_PAIRS } ?: run {
                require(allowPromotedRegions) { "Missing DiscoveryBus region state: ${r.id}" }
                return@forEachIndexed
            }
            val narrowedUartTail = allowPromotedRegions && r.id == UART_TAIL_REGION &&
                r.base == 0xfff3a034L && r.endExclusive == 0xfff3a03cL &&
                (row["endExclusive"] as Number).toLong() == r.endExclusive + 4L
            val expectedEnd = when {
                narrowedUartTail -> r.endExclusive + 4L
                r.id == Image51000.TABLE && promoteTablePairTwice ->
                    Image51000.BASE + Image51000.TABLE_LIMIT * 4L
                r.id == Image51000.TABLE && promoteTablePairRepeatable ->
                    Image51000.BASE + Image51000.SECOND_TABLE_LIMIT * 4L
                r.id == Image51000.EMPTY && promoteTablePairRepeatable ->
                    Image51000.EMPTY_BASE + Image51000.EMPTY_LIMIT * 4L
                else -> r.endExclusive
            }
            require((row["base"] as Number).toLong() == r.base && (row["endExclusive"] as Number).toLong() == expectedEnd) {
                "Discovery region bounds changed for ${r.id}"
            }
            val promotePanel = allowPromotedRegions && r.strategy == DiscoveryStrategy.PANEL_TWICE &&
                row["strategy"] == DiscoveryStrategy.PANEL_ONCE.id && row["position"].toString() == PanelInitOnce.CYCLE_LENGTH.toString()
            val promoteSelector10 = allowPromotedRegions && (
                (r.strategy == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10 &&
                    row["strategy"] == DiscoveryStrategy.IMAGE.id) ||
                (r.strategy == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE &&
                    (row["strategy"] == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10.id ||
                        row["strategy"] == DiscoveryStrategy.IMAGE.id)))
            val promoteChannel = allowPromotedRegions && r.strategy == DiscoveryStrategy.CHANNEL_CONTROL &&
                row["strategy"] == DiscoveryStrategy.RETAINED.id
            val promoteFace = allowPromotedRegions && r.strategy == DiscoveryStrategy.IMAGE_FACE_7C000 &&
                row["strategy"] == DiscoveryStrategy.RETAINED.id
            val promoteReply = allowPromotedRegions && ((r.strategy == DiscoveryStrategy.INTERLEAVED_REPLY &&
                row["strategy"] == DiscoveryStrategy.SCRIPT.id) || (r.strategy == DiscoveryStrategy.PROGRAMMED_REPLY &&
                row["strategy"] == DiscoveryStrategy.INTERLEAVED_REPLY.id) || (r.strategy == DiscoveryStrategy.PROGRAMMED_REPLY_REPEATABLE &&
                (row["strategy"] == DiscoveryStrategy.INTERLEAVED_REPLY.id || row["strategy"] == DiscoveryStrategy.PROGRAMMED_REPLY.id)))
            if (row["strategy"] != r.strategy.id && !promoteReply && !(r.id == IMAGE_50000_CONTROL_REGION && promoteControl) &&
                !(r.id == POLL_7801C_REGION && promotePoll) &&
                !(r.id == WRITE_49530_REGION && promoteWrite49530) &&
                !(r.id == TAIL_49500_REGION && promoteTail49500) &&
                !(r.id == Image70000.REGION && imagePredecessor != null) && !(r.id == Image6e000.REGION && promote6e) &&
                !(r.id == Image68000.REGION && promote68000) &&
                !((r.id == Image51000.TABLE || r.id == Image51000.EMPTY) && promoteTablePair) &&
                !promotePanel && !promoteSelector10 && !promoteChannel && !promoteFace) {
                val counters = listOf("position", "accessAttempts", "retainedValue", "selectedValue")
                require(allowPromotedRegions && row["strategy"] == DiscoveryStrategy.REFUSE.id &&
                    ((r.strategy == DiscoveryStrategy.RETAINED && r.initial != null && r.retainedDiagnostic == null) ||
                        r.strategy == DiscoveryStrategy.PANEL_ONCE) &&
                    counters.all { row[it] == 0 || row[it] == 0L } &&
                    row["retainedWritten"] == false && row["fixedSelected"] == false &&
                    listOf("mutatedBytesBase64", "scriptTailSelection").all { it in row && row[it] == null }) {
                    "Discovery region strategy changed for ${r.id}"
                }
            }
            val pos = (row["position"] as Number).toString().toInt()
            if (r.id == PLAY_DSP_FA00_REGION) {
                val savedDue = row["playDspPendingDueTick"]?.let {
                    require(it is Int || it is Long) { "Invalid playDspPendingDueTick" }
                    (it as Number).toLong()
                }
                val savedActive = row["playDspIrqActive"]?.let {
                    it as? Boolean ?: throw IllegalArgumentException("Invalid playDspIrqActive")
                } ?: false
                commit.add {
                    playDspPendingDueTick = savedDue
                    playDspIrqActive = savedActive
                }
            }
            if (r.id == IMAGE_RESULT_SURFACE) {
                val savedSelector10 = row["strategy"] == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10.id ||
                    row["strategy"] == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE.id
                val savedRepeatable = row["strategy"] == DiscoveryStrategy.IMAGE_RESULT_SELECTOR10_REPEATABLE.id
                require(("selector10Reads" in row) == savedSelector10)
                if (r.strategy.isImageResultSelector10) {
                    val reads = if (savedSelector10) {
                        val count = row["selector10Reads"]
                        require(count is Int || count is Long)
                        Math.toIntExact((count as Number).toLong())
                    } else 0
                    val maxSelector10Reads = if (savedRepeatable) Int.MAX_VALUE else SELECTOR10_READ_LIMIT
                    require(reads in 0..maxSelector10Reads && pos.toLong() + reads == (row["accessAttempts"] as Number).toLong())
                    commit.add { selector10Reads = reads }
                }
                val maxResultReads = if (savedRepeatable) Int.MAX_VALUE else IMAGE_RESULT_READ_LIMIT
                require(row["imageSha256"] == r.imageDigest() && row["mutatedBytesBase64"] == null &&
                    pos in 0..maxResultReads && pos <= (row["accessAttempts"] as Number).toLong()) {
                    "Image result surface identity or read count differs"
                }
            }
            require(pos >= 0) { "Negative discovery sample position for ${r.id}" }
            if (r.id in imageReplyIds && r.id != IMAGE_REPLY_PAIRS) require(row["scriptTailSelection"] == null &&
                pos.toLong() == (row["accessAttempts"] as Number).toLong())
            if (r.strategy == DiscoveryStrategy.PANEL_ONCE || r.strategy == DiscoveryStrategy.PANEL_TWICE) {
                require(PanelInitOnce.validState(pos, (row["retainedValue"] as Number).toString().toLong(), r.strategy == DiscoveryStrategy.PANEL_TWICE) &&
                    row["accessAttempts"].toString().toLong() == pos.toLong() && row["retainedWritten"] == false &&
                    row["mutatedBytesBase64"] == null && row["scriptTailSelection"] == null &&
                    row["fixedSelected"] == false && row["selectedValue"].toString().toLong() == 0L)
            }
            val restoredExtra = if (i == selected) extra else 0
            r.retainedDiagnostic?.let { diagnostic ->
                val written = row["retainedWritten"] as Boolean
                val value = (row["retainedValue"] as Number).toString().toLong()
                var expectedValue = if (written && i != completed) diagnostic.writeValue else 0L
                if (i == completed) {
                    val proof = checkNotNull(completion)
                    val reads = proof["successfulReads"].toString().toInt()
                    val receipt = audioReceipt(i, reads, diagnostic.successfulReadLimit + restoredExtra,
                        proof["attempts"].toString().toLong(), proof["tick"].toString().toLong())
                    val repeats = if (schema == 5) {
                        val saved = data["audioF01cRepeats"] as Map<*, *>
                        require(saved.keys == setOf("limit","writeAtSuccessfulReads","completion","confidence") &&
                            saved["completion"] == "first-read-clears" && saved["confidence"] == "HYPOTHESIS")
                        val limit = saved["limit"] as Int
                        require(limit in 1..MAX_F01C_REPEATS && limit <= diagnostic.successfulReadLimit + restoredExtra - reads)
                        val starts = saved["writeAtSuccessfulReads"] as List<*>
                        require(starts.size <= limit)
                        val offsets = starts.map { it as Int }
                        require(offsets.all { it in reads..pos && it < diagnostic.successfulReadLimit + restoredExtra } &&
                            offsets.zipWithNext().all { (previous,next) -> previous < next })
                        if (offsets.lastOrNull() == pos) expectedValue = diagnostic.writeValue
                        limit to offsets
                    } else 0 to emptyList<Int>()
                    require(written && reads <= pos && row["accessAttempts"].toString().toLong() == pos.toLong() + 1 + repeats.second.size &&
                        value == expectedValue && pos <= diagnostic.successfulReadLimit + restoredExtra)
                    require(proof.keys == receipt.keys && receipt.all { (key, expected) ->
                        val actual = proof[key]
                        if (expected is Number) (actual is Int || actual is Long) && actual.toString() == expected.toString()
                        else actual == expected
                    })
                    commit.add {
                        audioCompletion = receipt
                        repeatLimit = repeats.first
                        repeatWritePositions.clear()
                        repeatWritePositions.addAll(repeats.second)
                    }
                }
                require(value == expectedValue &&
                    (written || (pos == 0 && restoredExtra == 0)) && pos <= diagnostic.successfulReadLimit + restoredExtra) {
                    "Invalid retained diagnostic snapshot for ${r.id}"
                }
            }
            val fSelected = row["fixedSelected"] as Boolean
            val fValue = (row["selectedValue"] as Number).toLong()
            if (fSelected) {
                require(r.selectionValues != null && fValue in r.selectionValues) { "Invalid restored fixed selection for ${r.id}" }
            }
            val tailRaw = row["scriptTailSelection"] as? Map<*, *>
            if (tailRaw != null) {
                require(r.strategy == DiscoveryStrategy.SCRIPT) { "Script tail restored onto non-script region ${r.id}" }
                fun words(key: String, limit: Int): List<Long> {
                    val raw = tailRaw[key] as List<*>
                    require(raw.size in 1..limit)
                    return raw.map {
                        require(it is Long || it is Int)
                        (it as Number).toLong().also { value -> require(value in 0..0xffffffffL) }
                    }
                }
                val restoredSamples = words("samples", if (isA300(r)) MAX_A300_SAMPLES else 64)
                val restoredInitial = words("initialSamples", 64)
                val selectedAt = tailRaw["sampleIndex"] as Int
                require(selectedAt in 0..minOf(pos, restoredInitial.size) && pos <= restoredSamples.size &&
                    restoredInitial.take(selectedAt) == restoredSamples.take(selectedAt)) { "Invalid restored script cursor or prefix for ${r.id}" }
                val definition = r.copy(samples = java.util.Collections.unmodifiableList(ArrayList(restoredSamples)))
                val selection = ScriptTailSelection(
                    initialSamples = restoredInitial,
                    sampleIndex = selectedAt,
                    journalIndex = 0,
                )
                commit.add {
                    regionDefinitions[i] = definition
                    scriptTailSelections[i] = selection
                }
            } else if (r.strategy == DiscoveryStrategy.SCRIPT) {
                require(pos <= (r.samples?.size ?: 0)) { "Restored script position exceeds declared samples for ${r.id}" }
            }
            val mutated = row["mutatedBytesBase64"] as? String
            val decoded = mutated?.let(decoder::decode) ?: r.initial
            val destination = bytes[i]
            if (r.id == RETAINED_6C000_REGION) c000StopReply?.let {
                commit.add(it.prepareRestore(row, checkNotNull(decoded).first(), allowPromotedRegions))
            }
            if (decoded != null && !promoteFace) {
                if (narrowedUartTail && destination != null && decoded.size == destination.size + 4) {
                    require((destination.size until decoded.size).all { decoded[it] == 0.toByte() }) {
                        "Narrowed UART tail snapshot has non-zero trailing bytes for ${r.id}"
                    }
                    commit.add { decoded.copyInto(destination, endIndex = destination.size) }
                } else {
                    require(destination != null && decoded.size == destination.size) { "Restored discovery byte size mismatch for ${r.id}" }
                    commit.add { decoded.copyInto(destination) }
                }
            }
            val written = row["retainedWritten"] as Boolean
            val retained = (row["retainedValue"] as Number).toLong()
            val attempts = (row["accessAttempts"] as Number).toLong().also { require(it >= 0L) }
            if (r.strategy == DiscoveryStrategy.CHANNEL_CONTROL) {
                require(pos == 0 && !written && retained == 0L && !fSelected && fValue == 0L &&
                    (mutated == null || attempts > 0)) { "Invalid control backing or history for ${r.id}" }
            }
            commit.add {
                positions[i] = pos
                additionalReads[i] = restoredExtra
                retainedWritten[i] = written
                retainedValues[i] = retained
                accessAttempts[i] = attempts
                fixedSelected[i] = fSelected
                selectedValues[i] = fValue
            }
        }
        return {
            commit.forEach { it() }
            if (completed < 0) {
                audioCompletion = null
                repeatLimit = 0
                repeatWritePositions.clear()
            }
            irq106State = restoredIrq106
        }
    }
    override fun close() { closed=true }
    companion object {
        const val MAX_A300_SAMPLES = 2048
        const val MAX_F01C_REPEATS = 16
        const val MAX_EXTRA_F01C_READS = 2_097_152
        private const val MAX_TOTAL_EXTRA_F01C_READS = 16_777_216
    }
}
