package com.github.nyabanban.fuji.emulator.runtime

/**
 * Single-source-of-truth helpers used by `desktop` snapshot migration when lifting
 * legacy M6.3 discovery regions (`Image70000`, `ImageIrq106Status`, `Image6e000`,
 * `ImageLiveViewBanks`) into `soc-isp` component state.
 */
object PromotedIspMigrationSupport {
    data class MigratedCore70000(
        val coreWords: List<Long>,
        val irq106Mask: Long,
        val irq106CompletedCycles: Long,
    )

    data class MigratedLiveView(
        val lut6dBase64: String?,
        val registersByAddress: Map<Long, Long>,
    )

    fun extractCore70000(
        regionRow: Map<*, *>,
        irq106Raw: Any?,
        expectedOffsets: List<Int>,
    ): MigratedCore70000 {
        val allowed = Image70000.statefulAllowedOffsets
        require(expectedOffsets.toSet() == allowed) {
            "soc-isp core offsets diverge from Image70000.statefulAllowedOffsets"
        }
        val irq106 = irq106Raw?.let(ImageIrq106Status::parseState)
        val strategyId = regionRow["strategy"] as? String ?: error("Missing Image70000 strategy")
        val strategy = DiscoveryStrategy.parse(strategyId)
        val pos = (regionRow["position"] as? Number)?.toInt() ?: error("Missing Image70000 position")
        require(pos >= 0) { "Negative Image70000 position: $pos" }
        val retainedWritten = regionRow["retainedWritten"] as? Boolean ?: false
        val retainedValue = (regionRow["retainedValue"] as? Number)?.toLong() ?: 0L

        val words = HashMap<Int, Long>()
        for (offset in expectedOffsets) words[offset] = 0L
        words[0x2e8] = 0x10000L

        val rawBank = regionRow["t79StatefulBank"] as? Map<*, *>
        if (rawBank != null) {
            val restored = T79StatefulIspBank.restore(rawBank)
            require(retainedValue == restored.controlHighHalf()) {
                "T79 stateful ISP bank retainedValue differs from bank 0x2e4 high half"
            }
            @Suppress("UNCHECKED_CAST")
            val hexWords = restored.snapshot().getValue("words") as Map<String, Long>
            for ((hex, value) in hexWords) {
                words[hex.toInt(16)] = value
            }
        } else {
            val steps = Image70000.migrationSteps(strategy)
            val replayCount = minOf(pos, steps.size)
            for (i in 0 until replayCount) {
                val (off, word) = steps[i]
                if (word != null) words[off] = word
            }
            val setter = (regionRow["t79Setter"] as? Map<*, *>)?.let(T79ImageConfigSetter::restore)
            if (setter != null) {
                for ((off, word) in setter.retainedWords()) {
                    words[off] = word
                }
            }
            val secondUpdate = (regionRow["t79SecondUpdate"] as? Map<*, *>)?.let(T79ImageSecondUpdate::restore)
            if (secondUpdate != null) {
                for (off in T79ImageSecondUpdate.offsets) {
                    secondUpdate.retainedWord(off)?.let { words[off] = it }
                }
            }
            if (pos >= Image70000.secondDriverStart && setter?.complete == true && secondUpdate?.complete == true) {
                words[0x000] = 1L
                words[0x004] = 0x03ff03ffL
                words[0x008] = 0x03ff03dfL
                words[0x00c] = 0L
                words[0x01c] = 0x66480L
                words[0x2e4] = 0x120L or retainedValue
            } else if (retainedWritten) {
                words[0x2e4] = retainedValue
            }
        }

        return MigratedCore70000(
            coreWords = expectedOffsets.map { words.getValue(it) },
            irq106Mask = irq106?.mask ?: 0L,
            irq106CompletedCycles = (irq106?.completedCycles ?: 0).toLong(),
        )
    }

    fun extractBank6e000(regionRow: Map<*, *>, expectedOffsets: List<Int>): List<Long> {
        val rawBank = regionRow["image6eBank"] as? Map<*, *>
        if (rawBank != null) {
            val restored = Image6e000Bank.restore(rawBank)
            @Suppress("UNCHECKED_CAST")
            val hexWords = restored.snapshot().getValue("words") as Map<String, Long>
            return expectedOffsets.map { hexWords.getValue(it.toString(16)) }
        }
        val strategyId = regionRow["strategy"] as? String ?: error("Missing Image6e000 strategy")
        val strategy = DiscoveryStrategy.parse(strategyId)
        val pos = (regionRow["position"] as? Number)?.toInt() ?: error("Missing Image6e000 position")
        require(pos >= 0) { "Negative Image6e000 position: $pos" }
        val words = expectedOffsets.associateWith { 0L }.toMutableMap()
        val steps = Image6e000.migrationSteps(strategy)
        val replayCount = minOf(pos, steps.size)
        for (i in 0 until replayCount) {
            val (off, word) = steps[i]
            if (word != null) words[off] = word
        }
        return expectedOffsets.map { words.getValue(it) }
    }

    fun extractLiveView(raw: Any?): MigratedLiveView {
        val banks = ImageLiveViewBanks.restore(raw)
        val snap = banks.snapshot()
        val lut6dBase64 = snap["lut6dBase64"] as? String
        val registers = HashMap<Long, Long>()
        @Suppress("UNCHECKED_CAST")
        val rawRegs = snap["registers"] as? Map<String, Long> ?: emptyMap()
        for ((hex, word) in rawRegs) {
            val addr = java.lang.Long.decode(hex)
            registers[addr] = word
        }
        return MigratedLiveView(lut6dBase64 = lut6dBase64, registersByAddress = registers)
    }
}
