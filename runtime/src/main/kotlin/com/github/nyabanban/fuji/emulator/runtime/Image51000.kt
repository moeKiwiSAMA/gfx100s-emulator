package com.github.nyabanban.fuji.emulator.runtime

internal object Image51000 {
    const val TABLE = "diagnostic.image51000.table-tail"
    const val EMPTY = "diagnostic.image55000.empty-table"
    const val BASE = 0xfff51008L
    const val EMPTY_BASE = 0xfff55000L
    const val PAYLOAD_WORDS = 2707 - 2
    const val TABLE_LIMIT = PAYLOAD_WORDS + 1
    const val SECOND_PAYLOAD_WORDS = 4011 - 2
    const val SECOND_TABLE_LIMIT = SECOND_PAYLOAD_WORDS + 1
    const val TOTAL_TABLE_LIMIT = TABLE_LIMIT + SECOND_TABLE_LIMIT
    const val EMPTY_LIMIT = 2
    const val TOTAL_EMPTY_LIMIT = EMPTY_LIMIT * 2
    const val MAX_TABLE_WORDS = 0x1000 - 2
    const val MAX_EMPTY_WORDS = 0x1000

    fun tableWords(strategy: DiscoveryStrategy): Int = when (strategy) {
        DiscoveryStrategy.IMAGE_TABLE_PAIR -> TABLE_LIMIT
        DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE -> SECOND_TABLE_LIMIT
        DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> MAX_TABLE_WORDS
        else -> error("Unsupported image51000 strategy")
    }

    fun emptyWords(strategy: DiscoveryStrategy): Int = when (strategy) {
        DiscoveryStrategy.IMAGE_TABLE_PAIR, DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE -> EMPTY_LIMIT
        DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE -> MAX_EMPTY_WORDS
        else -> error("Unsupported image51000 strategy")
    }

    fun tableLimit(strategy: DiscoveryStrategy): Int = when (strategy) {
        DiscoveryStrategy.IMAGE_TABLE_PAIR -> TABLE_LIMIT
        DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE -> TOTAL_TABLE_LIMIT
        else -> error("Unsupported image51000 strategy")
    }

    fun emptyLimit(strategy: DiscoveryStrategy): Int = when (strategy) {
        DiscoveryStrategy.IMAGE_TABLE_PAIR -> EMPTY_LIMIT
        DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE -> TOTAL_EMPTY_LIMIT
        else -> error("Unsupported image51000 strategy")
    }

    fun expectedOffset(id: String, position: Int): Int = when (id) {
        TABLE -> (if (position < TABLE_LIMIT) position else position - TABLE_LIMIT) * 4
        EMPTY -> (position % EMPTY_LIMIT) * 4
        else -> error("Unknown image51000 region: $id")
    }

    fun regions(selection: Any): List<DiscoveryRegion> {
        val strategy = listOf(DiscoveryStrategy.IMAGE_TABLE_PAIR, DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE,
            DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE).single { it.id == selection }
        val twiceEvidence = if (strategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_TWICE ||
            strategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE) listOf(
            "T79 round-2 mode 0x6F (sensor mode 18): 0237F72C -> 02368258 -> 0237ED08 -> 021D5F20 selects 04E82000 (4011 entries, offsets 0/16) and empty second table",
            "HYPOTHESIS: accept a second packed table pair (4009 payload words + 011C25BC zero terminator at FFF54EAC, then 011C26AC/011C26BC 3FFF3FFF/0 at FFF55000..04)")
        else emptyList()
        val repeatableEvidence = if (strategy == DiscoveryStrategy.IMAGE_TABLE_PAIR_REPEATABLE) listOf(
            "DAT section4 011C24A8 (cmp r3, #0x1000; LRs 011C2538 payload, 011C25BC zero terminator) and 011C25C4 (cmp r3, #0x1000; LRs 011C2654 payload, 011C26D8 zero terminator, 011C26B0/011C26C0 empty sentinels)",
            "HYPOTHESIS: repeatable write-only packed table pair spanning FFF51008..FFF55000 (4094 words) and FFF55000..FFF59000 (4096 words)")
        else emptyList()
        fun region(id: String, base: Long, words: Int, source: String) =
            DiscoveryRegion(id, "section5-021f2d1c", base, words * 4, strategy,
                setOf(4), 4, true, readAllowed = IntArray(words * 4), evidence = listOf(
                    "on-68000 core1 tick 833112500: 0005AF98/011C2538 WRITE4 FFF51008=0014040F (reason 1)",
                    source, "HYPOTHESIS: one write-only packed table pair; the first two words remain under image50000") +
                    twiceEvidence + repeatableEvidence)
        return listOf(region(TABLE, BASE, tableWords(strategy),
            "011C24A8; descriptor 0328B16C: 04E8E000, 2707 entries, offsets 2/16; 011C25B8 appends zero"),
            region(EMPTY, EMPTY_BASE, emptyWords(strategy),
                "021F2D30 calls 011C25C4; descriptor 0328B178 count zero; 011C26AC/011C26BC emit 3FFF3FFF/0"))
    }

    fun accepts(id: String, position: Int, word: Long, context: DiscoveryContext): Boolean {
        if (context.core() != 1 || context.pc() != 0x5af98L) return false
        val lr = context.lr()
        return when {
            id == TABLE && position < PAYLOAD_WORDS -> lr == 0x11c2538L
            id == TABLE && position == PAYLOAD_WORDS -> lr == 0x11c25bcL && word == 0L
            id == TABLE && position - TABLE_LIMIT < SECOND_PAYLOAD_WORDS -> lr == 0x11c2538L
            id == TABLE && position - TABLE_LIMIT == SECOND_PAYLOAD_WORDS -> lr == 0x11c25bcL && word == 0L
            id == TABLE -> false
            position % EMPTY_LIMIT == 0 -> lr == 0x11c26b0L && word == 0x3fff3fffL
            else -> lr == 0x11c26c0L && word == 0L
        }
    }

    fun acceptsRepeatable(id: String, offset: Int, word: Long, context: DiscoveryContext): Boolean {
        if (context.core() != 1 || context.pc() != 0x5af98L) return false
        val lr = context.lr()
        return when (id) {
            TABLE -> lr == 0x11c2538L || (lr == 0x11c25bcL && word == 0L)
            EMPTY -> lr == 0x11c2654L || (lr == 0x11c26d8L && word == 0L) ||
                (offset == 0 && lr == 0x11c26b0L && word == 0x3fff3fffL) ||
                (offset == 4 && lr == 0x11c26c0L && word == 0L)
            else -> false
        }
    }
}
