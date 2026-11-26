package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.runtime.PromotedIspMigrationSupport
import java.util.Base64

internal object PromotedDiscoverySnapshotMigration {
    val PROMOTED_OPTIONAL_COMPONENTS: Set<String> = setOf(
        "soc-hdmi",
        "soc-panel",
        "soc-uart",
        "soc-isp-cmd",
        "soc-isp-setup",
        "soc-isp",
    )

    private const val STATUS_A200_REGION = "gfx100s.system-control.status-a200.candidate"
    private const val WRITE_49530_REGION = "gfx100s.fff49530.write-pair.candidate"
    private const val TAIL_49500_REGION = "gfx100s.fff49500.write-tail.candidate"
    private const val HDMI_REGION = "gfx100s.hdmi.init-words.candidate"
    private const val PANEL_REGION = "gfx100s.panel.init-words.candidate"
    private const val UART_BANK_REGION = "gfx100s.fff3a000.uart-bank.candidate"
    private const val UART_CONFIG_REGION = "gfx100s.fff3a030.uart-config.candidate"
    private const val UART_TAIL_REGION = "gfx100s.fff3a034.uart-tail.candidate"

    private const val WRITE49530_PROFILE = "fff49530-write-pair-once-v1"
    private const val TAIL49500_PROFILE = "fff49534-49500-write-tail-once-v1"
    private const val HDMI_WINDOW_SIZE = 2704
    private const val UART_WORD_COUNT = 8

    private fun asStringMap(value: Any?, field: String): Map<String, Any?> {
        val raw = value as? Map<*, *> ?: throw IllegalArgumentException("$field requires map")
        require(raw.keys.all { it is String }) { "$field requires string keys" }
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun number(value: Any?, field: String): Long {
        require(value is Byte || value is Short || value is Int || value is Long) {
            "$field requires integer"
        }
        return (value as Number).toLong()
    }

    private fun leWord(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun requireCommonRegionFields(
        row: Map<String, Any?>,
        regionId: String,
        expectedBase: Long,
        expectedEnds: Set<Long>,
        allowMutatedBytes: Boolean,
        allowPosition: Boolean,
        allowRetainedValue: Boolean,
        allowFixedSelected: Boolean,
    ) {
        require(number(row["base"], "$regionId.base") == expectedBase) {
            "Unexpected discovery base for $regionId"
        }
        val end = number(row["endExclusive"], "$regionId.endExclusive")
        require(end in expectedEnds) { "Unexpected discovery endExclusive for $regionId: $end" }
        if (!allowPosition) {
            require(number(row["position"], "$regionId.position") == 0L) {
                "Unexpected non-zero position for $regionId"
            }
        }
        require(row["retainedWritten"] == false) { "Unexpected retainedWritten for $regionId" }
        if (!allowRetainedValue) {
            require(number(row["retainedValue"], "$regionId.retainedValue") == 0L) {
                "Unexpected non-zero retainedValue for $regionId"
            }
        }
        if (!allowFixedSelected) {
            require(row["fixedSelected"] == false && number(row["selectedValue"], "$regionId.selectedValue") == 0L) {
                "Unexpected fixed selection for $regionId"
            }
        }
        if (!allowMutatedBytes) {
            require(row["mutatedBytesBase64"] == null) { "Unexpected mutatedBytesBase64 for $regionId" }
        }
        require(row["scriptTailSelection"] == null) { "Unexpected scriptTailSelection for $regionId" }
    }

    fun migrateSystemControl(
        payload: Map<String, Any?>,
        liveSnapshot: Map<String, Any?>,
        savedDiscovery: Map<*, *>?,
    ): Map<String, Any?> {
        val liveA200 = liveSnapshot["statusA200Fff4a200"]?.let { asStringMap(it, "live statusA200Fff4a200") }
        val livePair = liveSnapshot["write49530State"]?.let { asStringMap(it, "live write49530State") }
        val liveTail = liveSnapshot["tail49500State"]?.let { asStringMap(it, "live tail49500State") }
        if (liveA200 == null && livePair == null && liveTail == null) {
            return payload
        }
        val liveScalars = asStringMap(liveSnapshot["scalars"], "live soc-system-control scalars")
        val savedScalars = asStringMap(payload["scalars"], "saved soc-system-control scalars")
        val regions = savedDiscovery?.get("regions")?.let { asStringMap(it, "discovery regions") }

        val nextScalars = LinkedHashMap<String, Any?>(savedScalars)
        val nextPayload = LinkedHashMap<String, Any?>(payload)

        if (liveA200 != null && payload["statusA200Fff4a200"] == null) {
            val savedA200File = savedScalars["statusA200InputFile"]
            if (savedA200File == null || savedA200File == "") {
                regions?.get(STATUS_A200_REGION)?.let { rawRow ->
                    val row = asStringMap(rawRow, STATUS_A200_REGION)
                    requireCommonRegionFields(
                        row = row,
                        regionId = STATUS_A200_REGION,
                        expectedBase = 0xfff4a200L,
                        expectedEnds = setOf(0xfff4a204L),
                        allowMutatedBytes = false,
                        allowPosition = false,
                        allowRetainedValue = false,
                        allowFixedSelected = true,
                    )
                    when (val strategy = row["strategy"] as? String) {
                        "refuse" -> {
                            require(
                                number(row["accessAttempts"], "$STATUS_A200_REGION.accessAttempts") == 0L &&
                                    row["fixedSelected"] == false &&
                                    number(row["selectedValue"], "$STATUS_A200_REGION.selectedValue") == 0L,
                            ) { "Refused $STATUS_A200_REGION has non-zero accesses" }
                        }
                        "fixed-input" -> {
                            val liveValue = number(liveA200["value"], "live statusA200 value")
                            if (row["fixedSelected"] == true) {
                                val savedValue = number(row["selectedValue"], "$STATUS_A200_REGION.selectedValue")
                                require(savedValue == liveValue) {
                                    "Promoted status-a200 value ($savedValue) differs from live soc-system-control ($liveValue)"
                                }
                            } else {
                                require(number(row["selectedValue"], "$STATUS_A200_REGION.selectedValue") == 0L)
                            }
                        }
                        else -> error("Cannot migrate $STATUS_A200_REGION strategy '$strategy' to soc-system-control")
                    }
                }
                nextScalars["statusA200InputFile"] = liveScalars["statusA200InputFile"]
                nextPayload["statusA200Fff4a200"] = liveA200
            }
        } else if ("statusA200Fff4a200" !in nextPayload) {
            nextScalars.putIfAbsent("statusA200InputFile", "")
            nextPayload["statusA200Fff4a200"] = null
        }

        if (livePair != null && payload["write49530State"] == null) {
            val savedWrite49530 = savedScalars["write49530"]
            if (savedWrite49530 == null || savedWrite49530 == false) {
                val pairPos = migrateSequencePosition(
                    rawRow = regions?.get(WRITE_49530_REGION),
                    regionId = WRITE_49530_REGION,
                    expectedBase = 0xfff49530L,
                    expectedEnd = 0xfff49538L,
                    expectedStrategy = WRITE49530_PROFILE,
                )
                nextScalars["write49530"] = true
                nextPayload["write49530State"] = linkedMapOf(
                    "profile" to WRITE49530_PROFILE,
                    "position" to pairPos,
                )
            }
        } else if ("write49530State" !in nextPayload) {
            nextScalars.putIfAbsent("write49530", false)
            nextPayload["write49530State"] = null
        }

        if (liveTail != null && payload["tail49500State"] == null) {
            val savedTail49500 = savedScalars["tail49500"]
            if (savedTail49500 == null || savedTail49500 == false) {
                val tailPos = migrateSequencePosition(
                    rawRow = regions?.get(TAIL_49500_REGION),
                    regionId = TAIL_49500_REGION,
                    expectedBase = 0xfff49500L,
                    expectedEnd = 0xfff49504L,
                    expectedStrategy = TAIL49500_PROFILE,
                )
                nextScalars["tail49500"] = true
                nextPayload["tail49500State"] = linkedMapOf(
                    "profile" to TAIL49500_PROFILE,
                    "position" to tailPos,
                )
            }
        } else if ("tail49500State" !in nextPayload) {
            nextScalars.putIfAbsent("tail49500", false)
            nextPayload["tail49500State"] = null
        }

        nextPayload["scalars"] = nextScalars
        return nextPayload
    }

    private fun migrateSequencePosition(
        rawRow: Any?,
        regionId: String,
        expectedBase: Long,
        expectedEnd: Long,
        expectedStrategy: String,
    ): Int {
        if (rawRow == null) return 0
        val row = asStringMap(rawRow, regionId)
        requireCommonRegionFields(
            row = row,
            regionId = regionId,
            expectedBase = expectedBase,
            expectedEnds = setOf(expectedEnd),
            allowMutatedBytes = false,
            allowPosition = true,
            allowRetainedValue = false,
            allowFixedSelected = false,
        )
        return when (val strategy = row["strategy"] as? String) {
            "refuse" -> {
                require(
                    number(row["position"], "$regionId.position") == 0L &&
                        number(row["accessAttempts"], "$regionId.accessAttempts") == 0L,
                ) { "Refused $regionId has non-zero position or accesses" }
                0
            }
            expectedStrategy -> {
                val pos = number(row["position"], "$regionId.position")
                val attempts = number(row["accessAttempts"], "$regionId.accessAttempts")
                require(attempts == pos && pos in 0L..Int.MAX_VALUE.toLong()) {
                    "Unexpected $regionId cursor state: position=$pos accessAttempts=$attempts"
                }
                pos.toInt()
            }
            else -> error("Cannot migrate $regionId strategy '$strategy' to soc-system-control")
        }
    }

    fun migrateOptionalComponent(
        componentId: String,
        liveSnapshot: Map<String, Any?>,
        savedDiscovery: Map<*, *>,
    ): Map<String, Any?> = when (componentId) {
        "soc-hdmi" -> migrateHdmi(liveSnapshot, savedDiscovery)
        "soc-panel" -> migratePanel(liveSnapshot, savedDiscovery)
        "soc-uart" -> migrateUart(liveSnapshot, savedDiscovery)
        "soc-isp-cmd" -> migrateIspCmd(liveSnapshot, savedDiscovery)
        "soc-isp-setup" -> migrateIspSetup(liveSnapshot, savedDiscovery)
        "soc-isp" -> migrateIsp(liveSnapshot, savedDiscovery)
        else -> error("Unsupported promoted optional component: $componentId")
    }

    fun migrateDma(
        payload: Map<String, Any?>,
        liveSnapshot: Map<String, Any?>,
        savedDiscovery: Map<*, *>?,
    ): Map<String, Any?> {
        val liveProgramming = liveSnapshot["channelProgramming"]?.let { asStringMap(it, "live channelProgramming") }
            ?: return payload
        if ("channelProgramming" in payload) {
            return payload
        }
        val liveScalars = asStringMap(liveSnapshot["scalars"], "live soc-dma scalars")
        val savedScalars = asStringMap(payload["scalars"], "saved soc-dma scalars")
        val nextScalars = LinkedHashMap<String, Any?>(savedScalars)
        val regions = savedDiscovery?.get("regions")?.let { asStringMap(it, "discovery regions") }.orEmpty()

        fun decodeU32Region(regionId: String, expectedBase: Long, allowedStrategies: Set<String>): Pair<String?, Long?> {
            val raw = regions[regionId] ?: return null to null
            val row = asStringMap(raw, regionId)
            requireCommonRegionFields(
                row = row,
                regionId = regionId,
                expectedBase = expectedBase,
                expectedEnds = setOf(expectedBase + 4L),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            val strategy = row["strategy"] as? String
            require(strategy in allowedStrategies) {
                "Cannot migrate $regionId strategy '$strategy' to soc-dma"
            }
            val mutated = row["mutatedBytesBase64"] ?: return strategy to null
            require(mutated is String) { "$regionId.mutatedBytesBase64 requires string" }
            val bytes = Base64.getDecoder().decode(mutated)
            require(bytes.size == 4) { "$regionId mutatedBytesBase64 size mismatch: ${bytes.size}" }
            return strategy to leWord(bytes, 0)
        }

        fun decodeParamsRegion(regionId: String, expectedBase: Long): List<Long> {
            val raw = regions[regionId] ?: return listOf(0L, 0L, 0L, 0L)
            val row = asStringMap(raw, regionId)
            requireCommonRegionFields(
                row = row,
                regionId = regionId,
                expectedBase = expectedBase,
                expectedEnds = setOf(expectedBase + 16L),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            require(row["strategy"] == "retained-bytes") {
                "Cannot migrate $regionId strategy '${row["strategy"]}' to soc-dma"
            }
            val mutated = row["mutatedBytesBase64"] ?: return listOf(0L, 0L, 0L, 0L)
            require(mutated is String) { "$regionId.mutatedBytesBase64 requires string" }
            val bytes = Base64.getDecoder().decode(mutated)
            require(bytes.size == 16) { "$regionId mutatedBytesBase64 size mismatch: ${bytes.size}" }
            return List(4) { i -> leWord(bytes, i * 4) }
        }

        val (ch4Strategy, ch4Word) = decodeU32Region(
            "diagnostic.channel4.fff3c020",
            0xfff3c020L,
            setOf("retained-bytes", "control-readback-after-write-v1"),
        )
        val ch4Params = decodeParamsRegion("diagnostic.channel4.fff3c140", 0xfff3c140L)
        val (_, ch7Enable) = decodeU32Region("diagnostic.channel7.fff3c000", 0xfff3c000L, setOf("retained-bytes"))
        val (_, ch7Control) = decodeU32Region("diagnostic.channel7.fff3c02c", 0xfff3c02cL, setOf("retained-bytes"))
        val ch7Params = decodeParamsRegion("diagnostic.channel7.fff3c170", 0xfff3c170L)
        val (ch11Strategy, ch11Word) = decodeU32Region(
            "diagnostic.channel11.fff3c03c",
            0xfff3c03cL,
            setOf("retained-bytes", "control-readback-after-write-v1"),
        )
        val ch11Params = decodeParamsRegion("diagnostic.channel11.fff3c1b0", 0xfff3c1b0L)

        fun channelScalar(key: String, savedControlStrategy: String?): String? {
            val live = liveScalars[key] as? String ?: return null
            return when (savedControlStrategy) {
                "control-readback-after-write-v1" -> "control-readback-after-write-v1"
                "retained-bytes" -> "write-words-v1"
                null -> live
                else -> error("Unsupported saved channel strategy $savedControlStrategy")
            }
        }
        channelScalar("channel4Programming", ch4Strategy)?.let { nextScalars["channel4Programming"] = it }
        (liveScalars["channel7Programming"] as? String)?.let { nextScalars["channel7Programming"] = it }
        channelScalar("channel11Programming", ch11Strategy)?.let { nextScalars["channel11Programming"] = it }

        val nextProgramming = linkedMapOf<String, Any?>(
            "ch4Control" to (ch4Word ?: liveProgramming["ch4Control"]),
            "ch4ControlWritten" to (ch4Word != null),
            "ch4Params" to ch4Params,
            "ch7Enable" to (ch7Enable ?: liveProgramming["ch7Enable"]),
            "ch7Control" to (ch7Control ?: liveProgramming["ch7Control"]),
            "ch7Params" to ch7Params,
            "ch11Control" to (ch11Word ?: liveProgramming["ch11Control"]),
            "ch11ControlWritten" to (ch11Word != null),
            "ch11Params" to ch11Params,
        )
        return LinkedHashMap<String, Any?>(payload).apply {
            put("scalars", nextScalars)
            put("channelProgramming", nextProgramming)
        }
    }

    private fun migrateHdmi(liveSnapshot: Map<String, Any?>, savedDiscovery: Map<*, *>): Map<String, Any?> {
        val regions = asStringMap(savedDiscovery["regions"], "discovery regions")
        val hdmiRow = regions[HDMI_REGION]?.let { asStringMap(it, HDMI_REGION) }
        val hdmiStatus = if ("hdmiStatus" in savedDiscovery) {
            asStringMap(savedDiscovery["hdmiStatus"], "discovery hdmiStatus")
        } else null
        if (hdmiStatus != null) {
            require(hdmiStatus["confidence"] == "HYPOTHESIS") { "Invalid discovery hdmiStatus confidence" }
            val hasLink = "linkReads" in hdmiStatus || "linkPosition" in hdmiStatus
            val expectedKeys = if (hasLink) {
                setOf("pairs", "position", "confidence", "linkReads", "linkPosition")
            } else {
                setOf("pairs", "position", "confidence")
            }
            require(hdmiStatus.keys == expectedKeys) { "Unexpected discovery hdmiStatus keys" }
            require(hdmiRow != null) { "Discovery hdmiStatus requires $HDMI_REGION state" }
        }
        val wordsBase64 = if (hdmiRow == null) {
            liveSnapshot["wordsBase64"]
        } else {
            requireCommonRegionFields(
                row = hdmiRow,
                regionId = HDMI_REGION,
                expectedBase = 0xfff7b000L,
                expectedEnds = setOf(0xfff7b000L + HDMI_WINDOW_SIZE),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            when (val strategy = hdmiRow["strategy"] as? String) {
                "refuse" -> {
                    require(
                        hdmiStatus == null &&
                            number(hdmiRow["accessAttempts"], "$HDMI_REGION.accessAttempts") == 0L &&
                            hdmiRow["mutatedBytesBase64"] == null,
                    ) { "Refused $HDMI_REGION has active HDMI state" }
                    liveSnapshot["wordsBase64"]
                }
                "retained-bytes" -> {
                    val mutated = hdmiRow["mutatedBytesBase64"]
                    if (mutated == null) {
                        liveSnapshot["wordsBase64"]
                    } else {
                        require(mutated is String) { "$HDMI_REGION.mutatedBytesBase64 requires string" }
                        val decoded = Base64.getDecoder().decode(mutated)
                        require(decoded.size == HDMI_WINDOW_SIZE) {
                            "HDMI mutatedBytesBase64 size mismatch: ${decoded.size}"
                        }
                        mutated
                    }
                }
                else -> error("Cannot migrate $HDMI_REGION strategy '$strategy' to soc-hdmi")
            }
        }
        return LinkedHashMap<String, Any?>(liveSnapshot).apply {
            put("wordsBase64", wordsBase64)
            put("pairs", hdmiStatus?.get("pairs"))
            put("pairPosition", hdmiStatus?.get("position") ?: 0)
            put("linkReads", hdmiStatus?.get("linkReads"))
            put("linkPosition", hdmiStatus?.get("linkPosition") ?: 0)
        }
    }

    private fun migratePanel(liveSnapshot: Map<String, Any?>, savedDiscovery: Map<*, *>): Map<String, Any?> {
        val regions = asStringMap(savedDiscovery["regions"], "discovery regions")
        val panelRow = regions[PANEL_REGION]?.let { asStringMap(it, PANEL_REGION) }
            ?: return LinkedHashMap(liveSnapshot)
        requireCommonRegionFields(
            row = panelRow,
            regionId = PANEL_REGION,
            expectedBase = 0xfffab000L,
            expectedEnds = setOf(0xfffab018L),
            allowMutatedBytes = false,
            allowPosition = true,
            allowRetainedValue = true,
            allowFixedSelected = false,
        )
        return when (val strategy = panelRow["strategy"] as? String) {
            "refuse" -> {
                require(
                    number(panelRow["position"], "$PANEL_REGION.position") == 0L &&
                        number(panelRow["accessAttempts"], "$PANEL_REGION.accessAttempts") == 0L &&
                        number(panelRow["retainedValue"], "$PANEL_REGION.retainedValue") == 0L,
                ) { "Refused $PANEL_REGION has non-zero state" }
                LinkedHashMap(liveSnapshot)
            }
            "panel-init-once-v1", "panel-init-twice-v1" -> {
                val pos = number(panelRow["position"], "$PANEL_REGION.position")
                val attempts = number(panelRow["accessAttempts"], "$PANEL_REGION.accessAttempts")
                require(attempts == pos) {
                    "Panel discovery accessAttempts ($attempts) differs from position ($pos)"
                }
                val control = number(panelRow["retainedValue"], "$PANEL_REGION.retainedValue")
                LinkedHashMap<String, Any?>(liveSnapshot).apply {
                    put("scalars", linkedMapOf("profile" to strategy))
                    put("position", pos)
                    put("control", control)
                }
            }
            else -> error("Cannot migrate $PANEL_REGION strategy '$strategy' to soc-panel")
        }
    }

    private fun migrateUart(liveSnapshot: Map<String, Any?>, savedDiscovery: Map<*, *>): Map<String, Any?> {
        val regions = asStringMap(savedDiscovery["regions"], "discovery regions")
        val rawWords = liveSnapshot["words"] as? List<*> ?: error("Missing live soc-uart words")
        require(rawWords.size == UART_WORD_COUNT) { "Unexpected live soc-uart word count: ${rawWords.size}" }
        val words = LongArray(UART_WORD_COUNT) { index -> number(rawWords[index], "live soc-uart words[$index]") }

        fun migrateUartRegion(
            regionId: String,
            expectedBase: Long,
            expectedEnds: Set<Long>,
            expectedByteSize: Int,
            slotOffsets: List<Pair<Int, Int>>,
        ) {
            val row = regions[regionId]?.let { asStringMap(it, regionId) } ?: return
            requireCommonRegionFields(
                row = row,
                regionId = regionId,
                expectedBase = expectedBase,
                expectedEnds = expectedEnds,
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            when (val strategy = row["strategy"] as? String) {
                "refuse" -> {
                    require(
                        number(row["accessAttempts"], "$regionId.accessAttempts") == 0L &&
                            row["mutatedBytesBase64"] == null,
                    ) { "Refused $regionId has active accesses or mutated bytes" }
                }
                "retained-bytes" -> {
                    val mutated = row["mutatedBytesBase64"] ?: return
                    require(mutated is String) { "$regionId.mutatedBytesBase64 requires string" }
                    val decoded = Base64.getDecoder().decode(mutated)
                    val end = number(row["endExclusive"], "$regionId.endExclusive")
                    val expectedDecodedSize = (end - expectedBase).toInt()
                    require(decoded.size == expectedDecodedSize) {
                        "UART mutatedBytesBase64 size mismatch for $regionId: ${decoded.size} != $expectedDecodedSize"
                    }
                    if (decoded.size > expectedByteSize) {
                        require((expectedByteSize until decoded.size).all { decoded[it] == 0.toByte() }) {
                            "Narrowed UART tail snapshot has non-zero trailing bytes for $regionId"
                        }
                    }
                    for ((slot, offset) in slotOffsets) {
                        words[slot] = leWord(decoded, offset)
                    }
                }
                else -> error("Cannot migrate $regionId strategy '$strategy' to soc-uart")
            }
        }

        migrateUartRegion(
            regionId = UART_BANK_REGION,
            expectedBase = 0xfff3a000L,
            expectedEnds = setOf(0xfff3a030L),
            expectedByteSize = 48,
            slotOffsets = listOf(0 to 0x00, 1 to 0x18, 2 to 0x24, 3 to 0x28, 4 to 0x2c),
        )
        migrateUartRegion(
            regionId = UART_CONFIG_REGION,
            expectedBase = 0xfff3a030L,
            expectedEnds = setOf(0xfff3a034L),
            expectedByteSize = 4,
            slotOffsets = listOf(5 to 0x00),
        )
        migrateUartRegion(
            regionId = UART_TAIL_REGION,
            expectedBase = 0xfff3a034L,
            expectedEnds = setOf(0xfff3a03cL, 0xfff3a040L),
            expectedByteSize = 8,
            slotOffsets = listOf(6 to 0x00, 7 to 0x04),
        )

        return LinkedHashMap<String, Any?>(liveSnapshot).apply {
            put("words", words.toList())
            put("readCount", 0L)
            put("writeCount", 0L)
        }
    }

    private fun migrateIspCmd(liveSnapshot: Map<String, Any?>, savedDiscovery: Map<*, *>): Map<String, Any?> {
        val regions = asStringMap(savedDiscovery["regions"], "discovery regions")
        val liveScalars = asStringMap(liveSnapshot["scalars"], "live soc-isp-cmd scalars")
        val nextScalars = LinkedHashMap<String, Any?>(liveScalars)

        fun decodeRetainedBytes(
            regionId: String,
            expectedBase: Long,
            expectedSize: Int,
            liveInitialFill: Long?,
            defaultFill: Int = 0,
        ): Pair<Map<String, Any?>?, ByteArray?> {
            val row = regions[regionId]?.let { asStringMap(it, regionId) } ?: return null to null
            requireCommonRegionFields(
                row = row,
                regionId = regionId,
                expectedBase = expectedBase,
                expectedEnds = setOf(expectedBase + expectedSize),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            return when (val strategy = row["strategy"] as? String) {
                "refuse" -> {
                    require(
                        number(row["accessAttempts"], "$regionId.accessAttempts") == 0L &&
                            row["mutatedBytesBase64"] == null &&
                            "completion" !in row &&
                            "completionPhase" !in row,
                    ) { "Refused $regionId has active state" }
                    row to null
                }
                "retained-bytes" -> {
                    val rowFill = row["initialFill"]?.let { number(it, "$regionId.initialFill") }
                    if (liveInitialFill != null && rowFill != null) {
                        require(rowFill == liveInitialFill) {
                            "$regionId initialFill $rowFill differs from live $liveInitialFill"
                        }
                    }
                    val fillByte = (rowFill?.toInt() ?: liveInitialFill?.toInt() ?: defaultFill)
                    require(fillByte in 0..255) { "$regionId initialFill out of range: $fillByte" }
                    val mutated = row["mutatedBytesBase64"]
                    val bytes = if (mutated == null) {
                        ByteArray(expectedSize) { fillByte.toByte() }
                    } else {
                        require(mutated is String) { "$regionId.mutatedBytesBase64 requires string" }
                        Base64.getDecoder().decode(mutated).also {
                            require(it.size == expectedSize) {
                                "$regionId mutatedBytesBase64 size mismatch: ${it.size} != $expectedSize"
                            }
                        }
                    }
                    row to bytes
                }
                else -> error("Cannot migrate $regionId strategy '$strategy' to soc-isp-cmd")
            }
        }

        val c000LiveFill = number(liveScalars["c000InitialFill"], "live c000InitialFill")
        val (c000Row, c000Bytes) = decodeRetainedBytes(
            regionId = "gfx100s.fff6c000.words.candidate",
            expectedBase = 0xfff6c000L,
            expectedSize = 516,
            liveInitialFill = c000LiveFill,
        )
        val c000Base64 = c000Bytes?.let { Base64.getEncoder().encodeToString(it) } ?: liveSnapshot["c000WordsBase64"]
        val c000Completion = c000Row?.get("completion") as? String
        val c000Phase = c000Row?.get("completionPhase") as? String ?: "IDLE"
        if (c000Row != null) {
            if (c000Completion != null) {
                require(c000Completion in setOf("stop-bit0-set-after-poll-once-v1", "stop-bit0-set-after-poll-repeatable-v1")) {
                    "Unsupported fff6c000 completion: $c000Completion"
                }
                nextScalars["c000StopProfile"] = c000Completion
            } else {
                require("completionPhase" !in c000Row) { "Unexpected completionPhase without completion" }
                nextScalars["c000StopProfile"] = ""
            }
        } else {
            nextScalars["c000StopProfile"] = ""
        }

        val commonLiveFill = number(liveScalars["initialFill"], "live initialFill")
        val (c204Row, c204Bytes) = decodeRetainedBytes(
            regionId = "gfx100s.fff6c204.words.candidate",
            expectedBase = 0xfff6c204L,
            expectedSize = 8,
            liveInitialFill = commonLiveFill,
        )
        val c204Words = if (c204Bytes != null) {
            List(2) { i -> leWord(c204Bytes, i * 4) }
        } else {
            liveSnapshot["c204Words"]
        }
        if (c204Row != null && c204Row["strategy"] == "retained-bytes") {
            val c204Completion = c204Row["completion"]
            require(c204Completion == null || c204Completion == "start-bit0-clear-after-read-v1") {
                "Unsupported fff6c204 completion: $c204Completion"
            }
            nextScalars["clearC204Start"] = (c204Completion != null)
        } else {
            nextScalars["clearC204Start"] = false
        }

        val (_, c20cBytes) = decodeRetainedBytes(
            regionId = "gfx100s.fff6c20c.config-set.candidate",
            expectedBase = 0xfff6c20cL,
            expectedSize = 20,
            liveInitialFill = commonLiveFill,
        )
        val c20cWords = if (c20cBytes != null) {
            List(5) { i -> leWord(c20cBytes, i * 4) }
        } else {
            liveSnapshot["c20cWords"]
        }

        @Suppress("UNCHECKED_CAST")
        val liveBanks = (liveSnapshot["configBankWords"] as List<List<Any?>>)
        val configBankWords = (0..6).map { bank ->
            val base = 0xfff6c280L + bank * 64
            val size = if (bank == 6) 44 else 28
            val regionId = "diagnostic.image-config.${base.toString(16)}"
            val (_, bytes) = decodeRetainedBytes(regionId, base, size, liveInitialFill = null, defaultFill = 0)
            if (bytes != null) {
                List(size / 4) { i -> leWord(bytes, i * 4) }
            } else {
                liveBanks[bank]
            }
        }

        val replyRegionIds = listOf(
            "diagnostic.image-reply-820001.zero-once-v1",
            "diagnostic.image-reply-820001.af-once-v1",
            "diagnostic.image-reply-820040.zero-once-v1",
            "diagnostic.image-reply-820041.zero-once-v1",
            "diagnostic.image-reply-820040.zero-alternating-16-pairs-v1",
        )
        val presentReplies = replyRegionIds.filter { it in regions }
        require(presentReplies.size <= 1) { "Multiple image-reply regions in snapshot: $presentReplies" }
        var pairPosition = 0L
        var reply01Position = 0L
        var programmedReply: Any? = null
        if (presentReplies.isEmpty()) {
            nextScalars["replyProfile"] = ""
            nextScalars["interleaved820001"] = ""
        } else {
            val replyId = presentReplies.single()
            val row = asStringMap(regions[replyId], replyId)
            requireCommonRegionFields(
                row = row,
                regionId = replyId,
                expectedBase = 0xfff6c224L,
                expectedEnds = setOf(0xfff6c244L),
                allowMutatedBytes = false,
                allowPosition = true,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            val pos = number(row["position"], "$replyId.position")
            val attempts = number(row["accessAttempts"], "$replyId.accessAttempts")
            when (val strategy = row["strategy"] as? String) {
                "committed-81-read-once-v1" -> {
                    require(replyId == "diagnostic.image-reply-820040.zero-alternating-16-pairs-v1") {
                        "Unexpected committed reply region $replyId"
                    }
                    val sel01 = when (row["reply01Selection"]) {
                        "diagnostic.image-reply-820001.zero-once-v1" -> "zero-once-v1"
                        "diagnostic.image-reply-820001.af-once-v1" -> "af-once-v1"
                        else -> error("Unsupported reply01Selection: ${row["reply01Selection"]}")
                    }
                    val pos01 = number(row["reply01Position"], "$replyId.reply01Position")
                    val prog = asStringMap(row["programmedReply"], "$replyId.programmedReply")
                    val progWords = number(prog["words"], "$replyId.programmedReply.words")
                    require(attempts == pos + pos01 + progWords) {
                        "Image reply accessAttempts ($attempts) mismatch"
                    }
                    nextScalars["replyProfile"] = "committed-81-read-once-v1"
                    nextScalars["interleaved820001"] = sel01
                    pairPosition = pos
                    reply01Position = pos01
                    programmedReply = prog
                }
                "scripted-pairs-with-one-820001-v1" -> {
                    require(replyId == "diagnostic.image-reply-820040.zero-alternating-16-pairs-v1") {
                        "Unexpected interleaved reply region $replyId"
                    }
                    val sel01 = when (row["reply01Selection"]) {
                        "diagnostic.image-reply-820001.zero-once-v1" -> "zero-once-v1"
                        "diagnostic.image-reply-820001.af-once-v1" -> "af-once-v1"
                        else -> error("Unsupported reply01Selection: ${row["reply01Selection"]}")
                    }
                    val pos01 = number(row["reply01Position"], "$replyId.reply01Position")
                    require(attempts == pos + pos01) {
                        "Interleaved reply accessAttempts ($attempts) mismatch"
                    }
                    nextScalars["replyProfile"] = "zero-alternating-16-pairs-v1"
                    nextScalars["interleaved820001"] = sel01
                    pairPosition = pos
                    reply01Position = pos01
                }
                "scripted-read" -> {
                    require(attempts == pos) { "Scripted reply $replyId accessAttempts ($attempts) != position ($pos)" }
                    val mappedProfile = when (replyId) {
                        "diagnostic.image-reply-820040.zero-alternating-16-pairs-v1" -> "zero-alternating-16-pairs-v1"
                        "diagnostic.image-reply-820040.zero-once-v1" -> "zero-820040-once-v1"
                        "diagnostic.image-reply-820041.zero-once-v1" -> "zero-820041-once-v1"
                        "diagnostic.image-reply-820001.zero-once-v1" -> "zero-once-v1"
                        "diagnostic.image-reply-820001.af-once-v1" -> "af-once-v1"
                        else -> error("Unsupported scripted reply region: $replyId")
                    }
                    nextScalars["replyProfile"] = mappedProfile
                    nextScalars["interleaved820001"] = ""
                    pairPosition = pos
                }
                else -> error("Cannot migrate $replyId strategy '$strategy' to soc-isp-cmd")
            }
        }

        return LinkedHashMap<String, Any?>(liveSnapshot).apply {
            put("scalars", nextScalars)
            put("c000WordsBase64", c000Base64)
            put("c000Phase", c000Phase)
            put("c204Words", c204Words)
            put("c20cWords", c20cWords)
            put("configBankWords", configBankWords)
            put("pairPosition", pairPosition)
            put("reply01Position", reply01Position)
            put("programmedReply", programmedReply)
        }
    }

    private fun migrateIspSetup(liveSnapshot: Map<String, Any?>, savedDiscovery: Map<*, *>): Map<String, Any?> {
        val regions = asStringMap(savedDiscovery["regions"], "discovery regions")
        val liveScalars = asStringMap(liveSnapshot["scalars"], "live soc-isp-setup scalars")
        val nextScalars = LinkedHashMap<String, Any?>(liveScalars)

        @Suppress("UNCHECKED_CAST")
        val ctrl0Low = (liveSnapshot["ctrl0LowWords"] as List<Any?>).map { number(it, "live ctrl0Low") }.toLongArray()
        @Suppress("UNCHECKED_CAST")
        val ctrl0High = (liveSnapshot["ctrl0HighWords"] as List<Any?>).map { number(it, "live ctrl0High") }.toLongArray()
        @Suppress("UNCHECKED_CAST")
        val ctrl1Low = (liveSnapshot["ctrl1LowWords"] as List<Any?>).map { number(it, "live ctrl1Low") }.toLongArray()
        @Suppress("UNCHECKED_CAST")
        val ctrl1High = (liveSnapshot["ctrl1HighWords"] as List<Any?>).map { number(it, "live ctrl1High") }.toLongArray()

        fun decodeSetupRegion(
            regionId: String,
            expectedBase: Long,
            expectedSize: Int,
            wordTargets: List<Pair<Int, Int>>,
            dest: LongArray,
            requireZeroHoleOffset: Int? = null,
        ) {
            val row = regions[regionId]?.let { asStringMap(it, regionId) } ?: return
            requireCommonRegionFields(
                row = row,
                regionId = regionId,
                expectedBase = expectedBase,
                expectedEnds = setOf(expectedBase + expectedSize),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            when (val strategy = row["strategy"] as? String) {
                "refuse" -> {
                    require(
                        number(row["accessAttempts"], "$regionId.accessAttempts") == 0L &&
                            row["mutatedBytesBase64"] == null,
                    ) { "Refused $regionId has active state" }
                }
                "retained-bytes" -> {
                    val mutated = row["mutatedBytesBase64"] ?: return
                    require(mutated is String) { "$regionId.mutatedBytesBase64 requires string" }
                    val decoded = Base64.getDecoder().decode(mutated)
                    require(decoded.size == expectedSize) {
                        "$regionId mutatedBytesBase64 size mismatch: ${decoded.size} != $expectedSize"
                    }
                    if (requireZeroHoleOffset != null) {
                        require(leWord(decoded, requireZeroHoleOffset) == 0L) {
                            "Non-zero reserved gap in $regionId at offset $requireZeroHoleOffset"
                        }
                    }
                    for ((slot, offset) in wordTargets) {
                        dest[slot] = leWord(decoded, offset)
                    }
                }
                else -> error("Cannot migrate $regionId strategy '$strategy' to soc-isp-setup")
            }
        }

        decodeSetupRegion("gfx100s.fff4f008.setup.candidate", 0xfff4f008L, 4, listOf(0 to 0), ctrl0Low)
        decodeSetupRegion("gfx100s.fff4f00c.runtime-control.candidate", 0xfff4f00cL, 4, listOf(1 to 0), ctrl0Low)
        decodeSetupRegion("gfx100s.fff4f010.setup-tail.candidate", 0xfff4f010L, 8, listOf(2 to 0, 3 to 4), ctrl0Low)
        decodeSetupRegion("gfx100s.fff4f018.mode.candidate", 0xfff4f018L, 4, listOf(4 to 0), ctrl0Low)
        decodeSetupRegion(
            "gfx100s.fff4f020.mode-setup.candidate",
            0xfff4f020L,
            12,
            listOf(0 to 0, 1 to 8),
            ctrl0High,
            requireZeroHoleOffset = 4,
        )
        decodeSetupRegion("gfx100s.fff78008.setup.candidate", 0xfff78008L, 4, listOf(0 to 0), ctrl1Low)
        decodeSetupRegion("gfx100s.fff7800c.control.candidate", 0xfff7800cL, 4, listOf(1 to 0), ctrl1Low)
        decodeSetupRegion("gfx100s.fff78010.setup-tail.candidate", 0xfff78010L, 8, listOf(2 to 0, 3 to 4), ctrl1Low)
        decodeSetupRegion("gfx100s.fff78018.mode.candidate", 0xfff78018L, 4, listOf(4 to 0), ctrl1Low)
        decodeSetupRegion(
            "gfx100s.fff78020.mode-setup.candidate",
            0xfff78020L,
            12,
            listOf(0 to 0, 1 to 8),
            ctrl1High,
            requireZeroHoleOffset = 4,
        )

        var pollPosition = 0L
        val pollRegionId = "gfx100s.fff7801c.poll.candidate"
        val pollRow = regions[pollRegionId]?.let { asStringMap(it, pollRegionId) }
        if (pollRow == null) {
            nextScalars["poll7801cProfile"] = ""
        } else {
            requireCommonRegionFields(
                row = pollRow,
                regionId = pollRegionId,
                expectedBase = 0xfff7801cL,
                expectedEnds = setOf(0xfff78020L),
                allowMutatedBytes = false,
                allowPosition = true,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            when (val strategy = pollRow["strategy"] as? String) {
                "refuse" -> {
                    require(
                        number(pollRow["position"], "$pollRegionId.position") == 0L &&
                            number(pollRow["accessAttempts"], "$pollRegionId.accessAttempts") == 0L,
                    ) { "Refused $pollRegionId has active poll state" }
                    nextScalars["poll7801cProfile"] = ""
                }
                "fff7801c-write1-read0-once-v1", "fff7801c-setup-repeat-v1" -> {
                    val limit = if (strategy == "fff7801c-setup-repeat-v1") 4L else 2L
                    val pos = number(pollRow["position"], "$pollRegionId.position")
                    val attempts = number(pollRow["accessAttempts"], "$pollRegionId.accessAttempts")
                    require(pos in 0L..limit && attempts == pos) {
                        "Unexpected $pollRegionId poll cursor: pos=$pos attempts=$attempts"
                    }
                    nextScalars["poll7801cProfile"] = strategy
                    pollPosition = pos
                }
                else -> error("Cannot migrate $pollRegionId strategy '$strategy' to soc-isp-setup")
            }
        }

        return LinkedHashMap<String, Any?>(liveSnapshot).apply {
            put("scalars", nextScalars)
            put("ctrl0LowWords", ctrl0Low.toList())
            put("ctrl0HighWords", ctrl0High.toList())
            put("ctrl1LowWords", ctrl1Low.toList())
            put("ctrl1HighWords", ctrl1High.toList())
            put("pollPosition", pollPosition)
        }
    }

    private fun rangeSteps(first: Int, lastInclusive: Int): List<Int> =
        (first..lastInclusive step 4).toList()

    private val ISP_CORE_70000_LEGACY_OFFSETS: List<Int> =
        rangeSteps(0x000, 0x104) + rangeSteps(0x124, 0x2d0) + listOf(0x2e4, 0x2e8)

    private val ISP_CORE_70000_OFFSETS: List<Int> =
        rangeSteps(0x000, 0x2e8)

    private val ISP_CORE_70000_INDEX: Map<Int, Int> =
        ISP_CORE_70000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    private val ISP_BANK_50000_OFFSETS: List<Int> = (
        listOf(0x000) +
            rangeSteps(4, 76) +
            rangeSteps(144, 432) +
            listOf(0x248) +
            rangeSteps(548, 612) +
            rangeSteps(672, 980) +
            rangeSteps(4096, 4100)
        ).distinct().sorted()

    private val ISP_CONFIG_69000_OFFSETS: List<Int> =
        listOf(0x00) + rangeSteps(0x08, 0x0c) + rangeSteps(0x14, 0x28)

    private val ISP_CONFIG_69100_OFFSETS: List<Int> = (
        rangeSteps(0x000, 0x048) +
            listOf(0x050) +
            rangeSteps(0x100, 0x148) +
            listOf(0x150)
        ).sorted()

    private val ISP_CONFIG_6B000_OFFSETS: List<Int> =
        rangeSteps(0x00, 0x14) + rangeSteps(0x20, 0x2c) + rangeSteps(0xf0, 0xf4)

    private val ISP_BANK_6E000_LEGACY_OFFSETS: List<Int> =
        listOf(0x000, 0x008, 0x014, 0x018, 0x020, 0x024, 0x068, 0x06c)

    private val ISP_BANK_6E000_OFFSETS: List<Int> =
        (rangeSteps(0x000, 0x024) + rangeSteps(0x040, 0x060) + listOf(0x068, 0x06c, 0x070)).sorted()

    private val ISP_BANK_6E000_INDEX: Map<Int, Int> =
        ISP_BANK_6E000_OFFSETS.withIndex().associate { (i, o) -> o to i }

    private val ISP_LIVE_VIEW_CH_OFFSETS: List<Int> = (
        rangeSteps(0x00, 0x30) +
            rangeSteps(0x34, 0x38) +
            rangeSteps(0x40, 0xb8) +
            rangeSteps(0xc0, 0xe4) +
            rangeSteps(0x100, 0x108) +
            rangeSteps(0x110, 0x12c)
        ).distinct().sorted()

    private val ISP_LIVE_VIEW_2_OFFSETS: List<Int> = rangeSteps(0x00, 0x2c)

    private fun copyWordList(raw: Any?, field: String, expectedSize: Int): LongArray {
        val list = raw as? List<*> ?: throw IllegalArgumentException("$field requires list")
        require(list.size == expectedSize) { "$field size mismatch: ${list.size} != $expectedSize" }
        return LongArray(expectedSize) { i -> number(list[i], "$field[$i]") }
    }

    private fun decodeIspRetainedBytes(row: Map<String, Any?>, regionId: String, size: Int): ByteArray {
        val rawBase64 = row["mutatedBytesBase64"] as? String
        if (rawBase64 != null) {
            val decoded = Base64.getDecoder().decode(rawBase64)
            require(decoded.size == size) { "Migrated $regionId bytes size mismatch: ${decoded.size} != $size" }
            return decoded
        }
        val fillByte = ((row["initialFill"] as? Number)?.toInt() ?: 0) and 0xff
        return ByteArray(size) { fillByte.toByte() }
    }

    private fun decodeConfigCandidate(
        regions: Map<String, Any?>,
        regionId: String,
        base: Long,
        size: Int,
        offsets: List<Int>,
        outWords: LongArray,
    ) {
        val raw = regions[regionId] ?: return
        val row = asStringMap(raw, regionId)
        requireCommonRegionFields(
            row = row,
            regionId = regionId,
            expectedBase = base,
            expectedEnds = setOf(base + size),
            allowMutatedBytes = true,
            allowPosition = false,
            allowRetainedValue = false,
            allowFixedSelected = false,
        )
        val strategy = row["strategy"] as? String
        if (strategy == "refuse") {
            require(row["mutatedBytesBase64"] == null) { "Refused $regionId carries mutated bytes" }
            return
        }
        require(strategy == "retained-bytes") { "Unsupported $regionId strategy: $strategy" }
        val bytes = decodeIspRetainedBytes(row, regionId, size)
        offsets.forEachIndexed { idx, off ->
            outWords[idx] = leWord(bytes, off)
        }
    }

    private fun migrateIsp(
        liveSnapshot: Map<String, Any?>,
        savedDiscovery: Map<*, *>,
    ): Map<String, Any?> {
        require(liveSnapshot["enabled"] == true) {
            "Live soc-isp component must be enabled to migrate M6.3 discovery regions"
        }
        val regions = asStringMap(savedDiscovery["regions"], "discovery.regions")
        val coreWords = copyWordList(liveSnapshot["coreWords"], "coreWords", ISP_CORE_70000_OFFSETS.size)
        var irq106Mask = number(liveSnapshot["irq106Mask"], "irq106Mask")
        var irq106CompletedCycles = number(liveSnapshot["irq106CompletedCycles"], "irq106CompletedCycles")
        val bank50000Words = copyWordList(liveSnapshot["bank50000Words"], "bank50000Words", ISP_BANK_50000_OFFSETS.size)
        val config69000Words = copyWordList(liveSnapshot["config69000Words"], "config69000Words", ISP_CONFIG_69000_OFFSETS.size)
        val config69100Words = copyWordList(liveSnapshot["config69100Words"], "config69100Words", ISP_CONFIG_69100_OFFSETS.size)
        val config6b000Words = copyWordList(liveSnapshot["config6b000Words"], "config6b000Words", ISP_CONFIG_6B000_OFFSETS.size)
        val bank6e000Words = copyWordList(liveSnapshot["bank6e000Words"], "bank6e000Words", ISP_BANK_6E000_OFFSETS.size)
        val liveView0Words = copyWordList(liveSnapshot["liveView0Words"], "liveView0Words", ISP_LIVE_VIEW_CH_OFFSETS.size)
        val liveView1Words = copyWordList(liveSnapshot["liveView1Words"], "liveView1Words", ISP_LIVE_VIEW_CH_OFFSETS.size)
        val liveView2Words = copyWordList(liveSnapshot["liveView2Words"], "liveView2Words", ISP_LIVE_VIEW_2_OFFSETS.size)
        var lut59000Base64 = liveSnapshot["lut59000Base64"] as? String
        var lut6d000Base64 = liveSnapshot["lut6d000Base64"] as? String

        val rawImage70 = regions["diagnostic.image70000.011b811c"]
        if (rawImage70 != null) {
            val row70 = asStringMap(rawImage70, "diagnostic.image70000.011b811c")
            val migrated70 = PromotedIspMigrationSupport.extractCore70000(
                row70,
                savedDiscovery["imageIrq106Status"],
                ISP_CORE_70000_LEGACY_OFFSETS,
            )
            migrated70.coreWords.forEachIndexed { idx, value ->
                coreWords[ISP_CORE_70000_INDEX.getValue(ISP_CORE_70000_LEGACY_OFFSETS[idx])] = value
            }
            irq106Mask = migrated70.irq106Mask
            irq106CompletedCycles = migrated70.irq106CompletedCycles
        }

        ISP_BANK_50000_OFFSETS.forEachIndexed { idx, offset ->
            if (offset == 0x000) return@forEachIndexed
            val id = "diagnostic.image-word.${(0xfff50000L + offset).toString(16)}"
            val rawWord = regions[id] ?: return@forEachIndexed
            val row = asStringMap(rawWord, id)
            requireCommonRegionFields(
                row = row,
                regionId = id,
                expectedBase = 0xfff50000L + offset,
                expectedEnds = setOf(0xfff50004L + offset),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            require(row["strategy"] == "retained-bytes") { "Unsupported $id strategy: ${row["strategy"]}" }
            val bytes = decodeIspRetainedBytes(row, id, 4)
            bank50000Words[idx] = leWord(bytes, 0)
        }

        regions["diagnostic.image59000.tables"]?.let { raw59 ->
            val row = asStringMap(raw59, "diagnostic.image59000.tables")
            requireCommonRegionFields(
                row = row,
                regionId = "diagnostic.image59000.tables",
                expectedBase = 0xfff59000L,
                expectedEnds = setOf(0xfff5bc00L),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            require(row["strategy"] == "retained-bytes") {
                "Unsupported diagnostic.image59000.tables strategy: ${row["strategy"]}"
            }
            val bytes = decodeIspRetainedBytes(row, "diagnostic.image59000.tables", 0x2c00)
            lut59000Base64 = if (bytes.any { it != 0.toByte() }) Base64.getEncoder().encodeToString(bytes) else null
        }

        decodeConfigCandidate(regions, "gfx100s.fff69000.config.candidate", 0xfff69000L, 44, ISP_CONFIG_69000_OFFSETS, config69000Words)
        decodeConfigCandidate(regions, "gfx100s.fff69100.config-set.candidate", 0xfff69100L, 340, ISP_CONFIG_69100_OFFSETS, config69100Words)
        decodeConfigCandidate(regions, "gfx100s.fff6b000.config.candidate", 0xfff6b000L, 248, ISP_CONFIG_6B000_OFFSETS, config6b000Words)

        regions["diagnostic.image6e000.021f3440"]?.let { raw6e ->
            val row6e = asStringMap(raw6e, "diagnostic.image6e000.021f3440")
            val migrated6e = PromotedIspMigrationSupport.extractBank6e000(row6e, ISP_BANK_6E000_LEGACY_OFFSETS)
            migrated6e.forEachIndexed { idx, value ->
                bank6e000Words[ISP_BANK_6E000_INDEX.getValue(ISP_BANK_6E000_LEGACY_OFFSETS[idx])] = value
            }
        }

        regions["gfx100s.fff11060.result-surface.candidate"]?.let { rawRes ->
            val row = asStringMap(rawRes, "gfx100s.fff11060.result-surface.candidate")
            requireCommonRegionFields(
                row = row,
                regionId = "gfx100s.fff11060.result-surface.candidate",
                expectedBase = 0xfff11060L,
                expectedEnds = setOf(0xfff129e0L),
                allowMutatedBytes = false,
                allowPosition = true,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            if (row["strategy"] != "refuse") {
                require(row["imageSha256"] == liveSnapshot["resultSurfaceSha256"]) {
                    "Legacy result surface imageSha256 differs from live soc-isp resultSurfaceSha256"
                }
            }
        }

        val ch0Index = ISP_LIVE_VIEW_CH_OFFSETS.withIndex().associate { (i, o) -> o to i }
        val ch2Index = ISP_LIVE_VIEW_2_OFFSETS.withIndex().associate { (i, o) -> o to i }

        regions["gfx100s.fff72028.config-words.candidate"]?.let { rawLvCfg ->
            val row = asStringMap(rawLvCfg, "gfx100s.fff72028.config-words.candidate")
            requireCommonRegionFields(
                row = row,
                regionId = "gfx100s.fff72028.config-words.candidate",
                expectedBase = 0xfff72028L,
                expectedEnds = setOf(0xfff72028L + 4244L),
                allowMutatedBytes = true,
                allowPosition = false,
                allowRetainedValue = false,
                allowFixedSelected = false,
            )
            when (val strategy = row["strategy"] as? String) {
                "refuse" -> {
                    require(
                        number(row["accessAttempts"], "gfx100s.fff72028.config-words.candidate.accessAttempts") == 0L &&
                            row["mutatedBytesBase64"] == null,
                    ) { "Refused gfx100s.fff72028.config-words.candidate has active state" }
                }
                "retained-bytes" -> {
                    val bytes = decodeIspRetainedBytes(row, "gfx100s.fff72028.config-words.candidate", 4244)
                    val writeOnlyChannelOffsets = listOf(0x28) + rangeSteps(0x34, 0x38) + rangeSteps(0x40, 0xb8)
                    for (off in writeOnlyChannelOffsets) {
                        val slot = ch0Index.getValue(off)
                        liveView0Words[slot] = leWord(bytes, off - 0x28)
                        liveView1Words[slot] = leWord(bytes, 0x1000 + (off - 0x28))
                    }
                }
                else -> error("Unsupported gfx100s.fff72028.config-words.candidate strategy: $strategy")
            }
        }

        val liveViewPrimary = regions["diagnostic.image-liveview.fff72000"]
        if (liveViewPrimary != null) {
            val rowPrimary = asStringMap(liveViewPrimary, "diagnostic.image-liveview.fff72000")
            val migratedLv = PromotedIspMigrationSupport.extractLiveView(rowPrimary["imageLiveViewBanks"])
            lut6d000Base64 = migratedLv.lut6dBase64
            for ((addr, word) in migratedLv.registersByAddress) {
                when {
                    addr in 0xfff72000L until 0xfff72130L -> liveView0Words[ch0Index.getValue((addr - 0xfff72000L).toInt())] = word
                    addr in 0xfff73000L until 0xfff73130L -> liveView1Words[ch0Index.getValue((addr - 0xfff73000L).toInt())] = word
                    addr in 0xfff74000L until 0xfff74030L -> liveView2Words[ch2Index.getValue((addr - 0xfff74000L).toInt())] = word
                }
            }
        }

        return LinkedHashMap<String, Any?>(liveSnapshot).apply {
            put("coreWords", coreWords.toList())
            put("irq106Mask", irq106Mask)
            put("irq106CompletedCycles", irq106CompletedCycles)
            put("bank50000Words", bank50000Words.toList())
            put("lut59000Base64", lut59000Base64)
            put("config69000Words", config69000Words.toList())
            put("config69100Words", config69100Words.toList())
            put("config6b000Words", config6b000Words.toList())
            put("lut6d000Base64", lut6d000Base64)
            put("bank6e000Words", bank6e000Words.toList())
            put("liveView0Words", liveView0Words.toList())
            put("liveView1Words", liveView1Words.toList())
            put("liveView2Words", liveView2Words.toList())
        }
    }
}
