package com.github.nyabanban.fuji.emulator.components.hdmi

import com.github.nyabanban.fuji.emulator.api.*
import java.util.Base64

/**
 * Retained HDMI init bank [0xFFF7B000, 0xFFF7BA90) with 15 evidenced word runs,
 * 11 prohibited holes, sequential 0xFFF7B008 link status reads, and 4-phase
 * 0xFFF7B090..94 echo pairs. Physical owner, reset, hotplug, and timing remain UNKNOWN.
 */
class HdmiFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    companion object {
        const val PROFILE = "hdmi-init-retained-experimental-v1"
        const val WINDOW_SIZE = 2704
        const val LINK_OFFSET = 8L
        const val PAIR_FIRST_OFFSET = 144L
        const val PAIR_SECOND_OFFSET = 148L
        const val MAX_PAIRS = 64
        const val MAX_LINK_READS = 4096

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-hdmi",
            version = "0.0.1",
            settings = listOf(
                Setting("profile", ValueKind.TEXT, "disabled", choices = listOf("disabled", PROFILE), live = false),
                Setting("inputFile", ValueKind.TEXT, "", live = false),
                Setting("repeatLastLinkRead", ValueKind.BOOLEAN, false, live = false),
                Setting("repeatLastStatusPair", ValueKind.BOOLEAN, false, live = false),
            ),
        )

        private val INPUT_KEYS = setOf("schema", "profile", "initialFill", "pairs", "linkReads")
        private val SNAPSHOT_KEYS = setOf(
            "schema", "componentId", "scalars", "enabled",
            "initialFill", "wordsBase64", "pairs", "pairPosition", "linkReads", "linkPosition",
        )

        private data class WordRun(val first: Int, val last: Int, val read: Boolean, val write: Boolean)

        private val WORD_RUNS = listOf(
            WordRun(4, 4, read = true, write = true),
            WordRun(8, 8, read = true, write = false),
            WordRun(24, 24, read = true, write = true),
            WordRun(32, 32, read = true, write = true),
            WordRun(36, 44, read = false, write = true),
            WordRun(144, 148, read = true, write = true),
            WordRun(152, 156, read = true, write = true),
            WordRun(288, 288, read = false, write = true),
            WordRun(292, 304, read = true, write = true),
            WordRun(336, 444, read = true, write = true),
            WordRun(480, 504, read = false, write = true),
            WordRun(544, 600, read = false, write = true),
            WordRun(640, 764, read = false, write = true),
            WordRun(800, 1016, read = false, write = true),
            WordRun(2700, 2700, read = false, write = true),
        )

        private val READABLE_OFFSETS = BooleanArray(WINDOW_SIZE).apply {
            for (run in WORD_RUNS) if (run.read) for (off in run.first..run.last step 4) this[off] = true
        }
        private val WRITABLE_OFFSETS = BooleanArray(WINDOW_SIZE).apply {
            for (run in WORD_RUNS) if (run.write) for (off in run.first..run.last step 4) this[off] = true
        }
        private val MIRRORED_RETAINED_OFFSETS: IntArray = buildList {
            for (run in WORD_RUNS) {
                if (!run.read) continue
                for (off in run.first..run.last step 4) {
                    if (off != LINK_OFFSET.toInt() && off != PAIR_FIRST_OFFSET.toInt() && off != PAIR_SECOND_OFFSET.toInt()) {
                        add(off)
                    }
                }
            }
        }.toIntArray()
        private val MIRROR_SLOT_BY_OFFSET: IntArray = IntArray(WINDOW_SIZE) { -1 }.apply {
            this[LINK_OFFSET.toInt()] = 0
            MIRRORED_RETAINED_OFFSETS.forEachIndexed { idx, off -> this[off] = idx + 1 }
        }
        private val MIRROR_SLOTS: List<MmioMirrorSlotSpec> = buildList {
            add(MmioMirrorSlotSpec(LINK_OFFSET, 4))
            for (off in MIRRORED_RETAINED_OFFSETS) {
                add(MmioMirrorSlotSpec(off.toLong(), 4))
            }
        }

        private fun requireInteger(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "$field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "$field out of range: $converted" }
            return converted
        }

        private fun parsePairs(raw: Any?): List<List<Long>> {
            val list = raw as? List<*> ?: throw IllegalArgumentException("HDMI pairs requires list")
            require(list.size in 1..MAX_PAIRS) { "HDMI pairs size must be in 1..$MAX_PAIRS" }
            return list.mapIndexed { idx, entry ->
                val pair = entry as? List<*> ?: throw IllegalArgumentException("HDMI pairs[$idx] requires 2-word list")
                require(pair.size == 2) { "HDMI pairs[$idx] requires exactly 2 words" }
                listOf(
                    requireInteger(pair[0], "pairs[$idx][0]", 0L, 0xffffffffL),
                    requireInteger(pair[1], "pairs[$idx][1]", 0L, 0xffffffffL),
                )
            }
        }

        private fun parseLinkReads(raw: Any?): List<Long> {
            val list = raw as? List<*> ?: throw IllegalArgumentException("HDMI linkReads requires list")
            require(list.size in 1..MAX_LINK_READS) { "HDMI linkReads size must be in 1..$MAX_LINK_READS" }
            return list.mapIndexed { idx, entry ->
                requireInteger(entry, "linkReads[$idx]", 0L, 0xffffffffL)
            }
        }

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")

        private fun snapshotString(value: Any?, field: String): String =
            value as? String ?: throw IllegalArgumentException("Snapshot field $field requires string")
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-hdmi", context, config), StatefulComponent, NativeMmioMirrorPort {
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val active = profile == PROFILE
        private val repeatLastLinkRead = (config.getOrDefault("repeatLastLinkRead", false) as? Boolean)
            ?: throw IllegalArgumentException("soc-hdmi repeatLastLinkRead requires boolean")
        private val repeatLastStatusPair = (config.getOrDefault("repeatLastStatusPair", false) as? Boolean)
            ?: throw IllegalArgumentException("soc-hdmi repeatLastStatusPair requires boolean")
        private val initialFill: Int?
        private val words: ByteArray?
        private val pairs: List<List<Long>>?
        private var pairPosition = 0
        private val linkReads: List<Long>?
        private var linkPosition = 0
        private var mirrorHandle: MmioMirrorHandle? = null

        private fun readRetainedWord(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xff) or
                ((buf[off + 1].toInt() and 0xff) shl 8) or
                ((buf[off + 2].toInt() and 0xff) shl 16) or
                ((buf[off + 3].toInt() and 0xff) shl 24)

        private fun syncLinkMirrorSlot() {
            val h = mirrorHandle ?: return
            val list = linkReads
            if (repeatLastLinkRead && list != null && list.isNotEmpty() && linkPosition >= list.size) {
                h.writeSlot(0, list.last().toInt())
            } else {
                h.setSlotValid(0, false)
            }
        }

        private fun syncAllMirrorSlots() {
            val h = mirrorHandle ?: return
            val buf = words ?: return
            for (i in MIRRORED_RETAINED_OFFSETS.indices) {
                h.writeSlotRaw(i + 1, readRetainedWord(buf, MIRRORED_RETAINED_OFFSETS[i]))
            }
            val list = linkReads
            if (repeatLastLinkRead && list != null && list.isNotEmpty() && linkPosition >= list.size) {
                h.writeSlotRaw(0, list.last().toInt())
                h.setValidMask(h.allSlotsMask)
            } else {
                h.setValidMask(h.allSlotsMask and 1L.inv())
            }
        }

        override fun describeMmioMirrorWindows(): List<MmioMirrorWindowSpec> =
            if (!active) emptyList() else listOf(MmioMirrorWindowSpec(id, WINDOW_SIZE.toLong(), MIRROR_SLOTS))

        override fun attachMmioMirror(portName: String, handle: MmioMirrorHandle) {
            if (portName == id && active) {
                mirrorHandle = handle
                syncAllMirrorSlots()
            }
        }

        override fun detachMmioMirrors() {
            mirrorHandle?.invalidateAll()
            mirrorHandle = null
        }

        init {
            val file = config.getValue("inputFile") as String
            require(profile in listOf("disabled", PROFILE)) { "Unsupported soc-hdmi profile: $profile" }
            require(active == file.isNotBlank() && (active || file.isEmpty())) {
                "soc-hdmi profile/inputFile selection mismatch"
            }
            if (active) {
                val data = context.readData(file)
                require(
                    data.keys.all { it in INPUT_KEYS } &&
                        setOf("schema", "profile", "initialFill").all { it in data } &&
                        requireInteger(data["schema"], "schema", 1L, 1L) == 1L &&
                        data["profile"] == PROFILE
                ) { "invalid soc-hdmi input schema" }
                val fill = requireInteger(data["initialFill"], "initialFill", 0L, 255L).toInt()
                val parsedPairs = if ("pairs" in data) parsePairs(data["pairs"]) else null
                val parsedLinkReads = if ("linkReads" in data) {
                    require(parsedPairs != null) { "soc-hdmi linkReads requires pairs" }
                    parseLinkReads(data["linkReads"])
                } else null
                initialFill = fill
                words = ByteArray(WINDOW_SIZE) { fill.toByte() }
                pairs = parsedPairs
                linkReads = parsedLinkReads

                context.ports.register(id, id, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 72
                    override fun read(offset: Long, size: Int): Long {
                        check(!closed) { "soc-hdmi controller closed" }
                        require(size == 4 && offset >= 0L && offset < WINDOW_SIZE && offset % 4L == 0L) {
                            "soc-hdmi requires aligned 32-bit word within [0, $WINDOW_SIZE)"
                        }
                        val off = offset.toInt()
                        require(READABLE_OFFSETS[off]) { "soc-hdmi read offset refused: 0x${off.toString(16)}" }
                        if (offset == LINK_OFFSET) {
                            val list = linkReads ?: error("soc-hdmi linkReads not configured")
                            val idx = linkPosition
                            if (idx >= list.size) {
                                if (repeatLastLinkRead) {
                                    syncLinkMirrorSlot()
                                    return list.last()
                                }
                                error("soc-hdmi linkReads exhausted")
                            }
                            linkPosition = idx + 1
                            if (linkPosition >= list.size && repeatLastLinkRead) {
                                syncLinkMirrorSlot()
                            }
                            return list[idx]
                        }
                        if (offset == PAIR_FIRST_OFFSET || offset == PAIR_SECOND_OFFSET) {
                            return accessPair(offset, write = false, value = 0L)
                        }
                        val buf = checkNotNull(words)
                        return (buf[off].toLong() and 0xffL) or
                            ((buf[off + 1].toLong() and 0xffL) shl 8) or
                            ((buf[off + 2].toLong() and 0xffL) shl 16) or
                            ((buf[off + 3].toLong() and 0xffL) shl 24)
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        check(!closed) { "soc-hdmi controller closed" }
                        require(size == 4 && offset >= 0L && offset < WINDOW_SIZE && offset % 4L == 0L) {
                            "soc-hdmi requires aligned 32-bit word within [0, $WINDOW_SIZE)"
                        }
                        require(value in 0L..0xffffffffL) { "soc-hdmi write value outside u32" }
                        val off = offset.toInt()
                        require(WRITABLE_OFFSETS[off]) { "soc-hdmi write offset refused: 0x${off.toString(16)}" }
                        if (offset == PAIR_FIRST_OFFSET || offset == PAIR_SECOND_OFFSET) {
                            accessPair(offset, write = true, value = value)
                            return
                        }
                        val buf = checkNotNull(words)
                        buf[off] = (value and 0xffL).toByte()
                        buf[off + 1] = ((value ushr 8) and 0xffL).toByte()
                        buf[off + 2] = ((value ushr 16) and 0xffL).toByte()
                        buf[off + 3] = ((value ushr 24) and 0xffL).toByte()
                        val slot = MIRROR_SLOT_BY_OFFSET[off]
                        if (slot > 0) mirrorHandle?.writeSlot(slot, value.toInt())
                    }
                })
            } else {
                initialFill = null
                words = null
                pairs = null
                linkReads = null
            }
        }

        private fun accessPair(offset: Long, write: Boolean, value: Long): Long {
            val list = pairs ?: error("soc-hdmi status pairs not configured")
            val pairIndex = pairPosition / 4
            val pair = if (pairIndex < list.size) {
                list[pairIndex]
            } else if (repeatLastStatusPair) {
                list.last()
            } else {
                error("soc-hdmi status pairs exhausted")
            }
            val phase = pairPosition % 4
            val expectedIndex = phase % 2
            val expectedOffset = if (expectedIndex == 0) PAIR_FIRST_OFFSET else PAIR_SECOND_OFFSET
            val expectedWrite = phase >= 2
            val sample = pair[expectedIndex]
            require(offset == expectedOffset && write == expectedWrite && (!write || value == sample)) {
                "soc-hdmi status pair protocol violation at position=$pairPosition offset=0x${offset.toString(16)}"
            }
            if (pairPosition < Int.MAX_VALUE) {
                pairPosition++
            } else {
                // Keep modulo-4 phase intact if repeated past Int.MAX_VALUE
                pairPosition = list.size * 4
            }
            return sample
        }

        override fun start() {
            check(!closed) { "soc-hdmi controller closed" }
        }

        override fun close() {
            closed = true
        }

        override fun additionalMmioPorts(): List<String> = if (active) listOf(id) else emptyList()

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active,
            "initialFill" to initialFill,
            "pairs" to pairs,
            "pairPosition" to pairPosition,
            "linkReads" to linkReads,
            "linkPosition" to linkPosition,
            "basis" to "Evidenced 15 wordRuns & 11 prohibited holes in [0xfff7b000, 0xfff7ba90); retained fill, linkReads, and 4-phase pairs are HYPOTHESIS; physical owner/reset/hotplug/timing UNKNOWN",
        )

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-hdmi controller closed" }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to active,
                "initialFill" to initialFill,
                "wordsBase64" to words?.let { Base64.getEncoder().encodeToString(it) },
                "pairs" to pairs,
                "pairPosition" to pairPosition,
                "linkReads" to linkReads,
                "linkPosition" to linkPosition,
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-hdmi controller closed" }
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(requireInteger(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            val requiredKeys = setOf("profile", "inputFile")
            val allKeys = DESCRIPTOR.settings.map { it.key }.toSet()
            require(scalarsRaw.keys.all { it is String && it in allKeys } && requiredKeys.all { it in scalarsRaw.keys }) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val normalizedScalars = LinkedHashMap<Any?, Any?>()
            for ((k, v) in scalarsRaw) {
                val key = k as String
                if (key in requiredKeys || key in values) {
                    normalizedScalars[key] = v
                }
            }
            if ("repeatLastLinkRead" in values && "repeatLastLinkRead" !in normalizedScalars) {
                normalizedScalars["repeatLastLinkRead"] = false
            }
            if ("repeatLastStatusPair" in values && "repeatLastStatusPair" !in normalizedScalars) {
                normalizedScalars["repeatLastStatusPair"] = false
            }
            val previousValues = values
            val nextWords: ByteArray?
            val nextPairPosition: Int
            val nextLinkPosition: Int
            try {
                require(snapshotBoolean(data["enabled"], "enabled") == active) {
                    "Snapshot enabled flag differs for $id"
                }
                if (!active) {
                    restoreScalarValues(normalizedScalars, DESCRIPTOR)
                    require(data["initialFill"] == null && data["wordsBase64"] == null &&
                        data["pairs"] == null && data["linkReads"] == null) {
                        "Disabled soc-hdmi snapshot cannot carry active state"
                    }
                    require(requireInteger(data["pairPosition"], "pairPosition", 0L, 0L) == 0L &&
                        requireInteger(data["linkPosition"], "linkPosition", 0L, 0L) == 0L) {
                        "Disabled soc-hdmi snapshot cannot carry positions"
                    }
                    nextWords = null
                    nextPairPosition = 0
                    nextLinkPosition = 0
                } else {
                    val savedFill = requireInteger(data["initialFill"], "initialFill", 0L, 255L).toInt()
                    require(savedFill == initialFill) { "Snapshot initialFill differs from live configuration" }
                    val decoded = Base64.getDecoder().decode(snapshotString(data["wordsBase64"], "wordsBase64"))
                    require(decoded.size == WINDOW_SIZE) { "Snapshot wordsBase64 length mismatch for $id" }
                    val savedPairs = data["pairs"]?.let { parsePairs(it) }
                    val savedRepeatPair = if ("repeatLastStatusPair" in scalarsRaw) {
                        (scalarsRaw["repeatLastStatusPair"] as? Boolean)
                            ?: throw IllegalArgumentException("Snapshot scalars.repeatLastStatusPair requires boolean")
                    } else false
                    val maxPairPos = if (repeatLastStatusPair && savedPairs != null) {
                        Int.MAX_VALUE.toLong()
                    } else {
                        ((savedPairs?.size ?: 0) * 4).toLong()
                    }
                    val savedPairPos = requireInteger(
                        data["pairPosition"],
                        "pairPosition",
                        0L,
                        maxPairPos,
                    ).toInt()
                    val savedLinkReads = data["linkReads"]?.let {
                        require(savedPairs != null) { "Snapshot linkReads requires pairs" }
                        parseLinkReads(it)
                    }
                    val savedLinkPos = requireInteger(
                        data["linkPosition"],
                        "linkPosition",
                        0L,
                        (savedLinkReads?.size ?: 0).toLong(),
                    ).toInt()

                    val pairsChanged = savedPairs != pairs
                    if (pairsChanged) {
                        require(pairs != null) { "Snapshot pairs cannot be removed" }
                        if (savedPairs != null) {
                            require(
                                savedPairPos == savedPairs.size * 4 &&
                                    pairs.size > savedPairs.size &&
                                    pairs.take(savedPairs.size) == savedPairs
                            ) { "HDMI status promotion requires appending to an exhausted pair sequence" }
                        }
                    }
                    val linkChanged = savedLinkReads != linkReads
                    if (linkChanged) {
                        require(linkReads != null) { "Changing HDMI linkReads requires non-null target linkReads" }
                        val prefix = savedLinkReads.orEmpty()
                        require(
                            linkReads.size > prefix.size &&
                                linkReads.subList(0, prefix.size) == prefix
                        ) { "HDMI link promotion must append to the complete saved sequence" }
                    }
                    val savedRepeat = if ("repeatLastLinkRead" in scalarsRaw) {
                        (scalarsRaw["repeatLastLinkRead"] as? Boolean)
                            ?: throw IllegalArgumentException("Snapshot scalars.repeatLastLinkRead requires boolean")
                    } else false
                    val repeatUpgrade = repeatLastLinkRead && !savedRepeat
                    val repeatPairUpgrade = repeatLastStatusPair && !savedRepeatPair
                    require(!(savedRepeat && !repeatLastLinkRead)) {
                        "Cannot downgrade repeatLastLinkRead=true snapshot into repeatLastLinkRead=false"
                    }
                    require(!(savedRepeatPair && !repeatLastStatusPair)) {
                        "Cannot downgrade repeatLastStatusPair=true snapshot into repeatLastStatusPair=false"
                    }
                    val compatibleKeys = buildSet {
                        if (pairsChanged || linkChanged) add("inputFile")
                        if (repeatUpgrade) add("repeatLastLinkRead")
                        if (repeatPairUpgrade) add("repeatLastStatusPair")
                    }
                    restoreScalarValues(normalizedScalars, DESCRIPTOR, compatibleKeys)
                    nextWords = decoded
                    nextPairPosition = savedPairPos
                    nextLinkPosition = savedLinkPos
                }
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            if (nextWords != null) {
                nextWords.copyInto(checkNotNull(words))
                pairPosition = nextPairPosition
                linkPosition = nextLinkPosition
                syncAllMirrorSlots()
            }
        }
    }
}
