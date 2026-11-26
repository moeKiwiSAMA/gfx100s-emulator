package com.github.nyabanban.fuji.emulator.runtime

internal object Image68000 {
    const val BASE = 0xfff68000L
    const val SIZE = 0xd04
    const val REGION = "diagnostic.image68000.023681d8"
    private val offsets = buildList {
        fun block(offset: Int, count: Int) {
            repeat(count) { index -> add(offset + 4 * index) }
        }
        block(0x000, 5)
        block(0x108, 2)
        block(0x110, 3)
        block(0x200, 28)
        block(0x300, 6)
        block(0x400, 53)
        block(0x500, 7)
        block(0x534, 2)
        block(0x600, 28)
        block(0x53c, 6)
        block(0x554, 6)
        block(0xc00, 41)
        block(0xa00, 60)
        block(0xa00, 12)
        block(0x700, 2)
        block(0x708, 1)
        block(0x70c, 6)
        block(0x800, 23)
        block(0x900, 21)
        add(0xd00)
    }.toIntArray()
    val limit get() = offsets.size
    val allowedOffsets: Set<Int> = offsets.toSet()

    fun limit(strategy: DiscoveryStrategy): Int = when (strategy) {
        DiscoveryStrategy.IMAGE_68000 -> offsets.size
        DiscoveryStrategy.IMAGE_68000_TWICE -> offsets.size * 2
        else -> error("Unsupported image68000 strategy")
    }

    fun region(selection: Any): DiscoveryRegion {
        val strategy = listOf(DiscoveryStrategy.IMAGE_68000, DiscoveryStrategy.IMAGE_68000_TWICE,
            DiscoveryStrategy.IMAGE_68000_REPEATABLE).single { it.id == selection }
        val writes = IntArray(SIZE)
        offsets.forEach { writes[it] = 4 }
        val holes = if (strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE) {
            (listOf(-4) + allowedOffsets.sorted() + SIZE).zipWithNext().mapNotNull { (left, right) ->
                if (left + 4 < right) AddressInterval(BASE + left + 4, BASE + right) else null
            }
        } else emptyList()
        return DiscoveryRegion(REGION, "section5-023681d8", BASE, SIZE, strategy,
            setOf(4), 4, true, prohibited = holes, readAllowed = IntArray(SIZE), writeAllowed = writes, evidence = listOf(
                "on-70134 tick 832915575 core1 PC 0005AF98 LR 011CB8A8, mask 0003FBDF index 0",
                "021F517C descriptor dispatch, sources 0299F458 and 0299EE94; 021F5410 stack setup via 011D0C88",
                "HYPOTHESIS: accept this ordered 312-word configuration and one D00 setup write; payloads are guest u32 words") +
                (if (strategy == DiscoveryStrategy.IMAGE_68000_TWICE || strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE) listOf(
                    "T79 round-2 0237C224..2C calls 023681D8 -> 021F517C -> 011CB8A8 a second time",
                    "HYPOTHESIS: accept the same ordered 313-word write sequence a second time (limit 626)")
                else emptyList()) +
                if (strategy == DiscoveryStrategy.IMAGE_68000_REPEATABLE) listOf(
                    "T79/T80 core1 repeat passes via 021F517C -> 011CB8A8",
                    "HYPOTHESIS: accept repeatable 4-byte aligned writes to the known Image68000 offset set while keeping all other offsets prohibited")
                else emptyList())
    }

    fun accepts(position: Int, offset: Int, context: DiscoveryContext): Boolean {
        return offsets[position % offsets.size] == offset && context.core() == 1 &&
            context.pc() == 0x5af98L && context.lr() == 0x11cb8a8L
    }

    fun acceptsRepeatable(offset: Int, context: DiscoveryContext): Boolean {
        return offset in allowedOffsets && context.core() == 1 &&
            context.pc() == 0x5af98L && context.lr() == 0x11cb8a8L
    }
}
