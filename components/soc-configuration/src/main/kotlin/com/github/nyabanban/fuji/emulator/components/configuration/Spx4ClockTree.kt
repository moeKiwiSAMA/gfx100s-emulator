package com.github.nyabanban.fuji.emulator.components.configuration

/**
 * Decoded SPX4 13-PLL and 54-divider (`clk[0..53]`) hardware clock tree metadata.
 *
 * Provenance summary (BootROM & LPDDR4 clock-tree analysis):
 * - `DAT` (`boot.bin 0xA9F0` == `section4.bin 0x015AC5F0`, `0x015AC950`): 13 hardware PLLs
 *   (`0xFF701000..0xFF70C000`, `0xFFFAE000`) aligned 1-to-1 with `BaseParameterState.groups`,
 *   feeding 52 PLL-backed dividers (`clk[0..51]` across 20 configuration groups) plus 2 direct
 *   non-PLL divider/control words (`clk[52]` at `0xFFF4860C`, `clk[53]` at `0xFFF48604`).
 * - `DAT` (`kernel.bin 0x0005A9B8`): Exact `54,000,000 Hz` (`54.00 MHz`) hardware timer base.
 * - `DAT` (`section4.bin 0x015ACD28..0x015ACD44`): 8-step binary peripheral table (`48 MHz .. 375 kHz`).
 * - `DAT` / `FILE` (`bank-14` `0x401`/`0x429`, `0x405`/`0x42D`): Real-hardware `PLL10` (EVF/LCDC0)
 *   and `PLL12` (Rear LCD/LCDC1) `(p0, p1, div)` programming tuples.
 * - `UNKNOWN`: Exact Cortex-A7 CPU MHz and Socionext PLL VCO multiplier formula `f_VCO(p0, p1)`.
 */
object Spx4ClockTree {
    const val PLL_COUNT: Int = 13
    const val PLL_BACKED_DIVIDER_COUNT: Int = 52
    const val TOTAL_DIVIDER_COUNT: Int = 54
    const val TIMER_REFERENCE_HZ: Long = 54_000_000L

    val PERIPHERAL_STEP_TABLE_HZ: List<Long> = listOf(
        48_000_000L,
        24_000_000L,
        12_000_000L,
        6_000_000L,
        3_000_000L,
        1_500_000L,
        750_000L,
        375_000L,
    )

    data class PllDescriptor(
        val pllIndex: Int,
        val label: String,
        val groupIndex: Int,
        val mmioBase: Long,
        val firstClockId: Int,
        val lastClockId: Int,
        val role: String,
        val observedOutputs: Map<String, String>,
        val observedBank14Pairs: List<Map<String, Long>> = emptyList(),
        val confidence: String = "DAT",
    ) {
        val dividerCount: Int
            get() = lastClockId - firstClockId + 1

        fun toStateMap(): Map<String, Any> = linkedMapOf<String, Any>(
            "pllIndex" to pllIndex,
            "label" to label,
            "groupIndex" to groupIndex,
            "mmioBase" to mmioBase,
            "firstClockId" to firstClockId,
            "lastClockId" to lastClockId,
            "dividerCount" to dividerCount,
            "role" to role,
            "observedOutputs" to observedOutputs,
            "confidence" to confidence,
        ).apply {
            if (observedBank14Pairs.isNotEmpty()) {
                put("observedBank14Pairs", observedBank14Pairs)
            }
        }
    }

    data class DividerDescriptor(
        val clockId: Int,
        val pllIndex: Int?,
        val groupIndex: Int?,
        val mmioAddress: Long,
        val label: String,
        val confidence: String,
    ) {
        fun toStateMap(): Map<String, Any?> = linkedMapOf(
            "clockId" to clockId,
            "pllIndex" to pllIndex,
            "groupIndex" to groupIndex,
            "mmioAddress" to mmioAddress,
            "label" to label,
            "confidence" to confidence,
        )
    }

    val PLLS: List<PllDescriptor> = listOf(
        PllDescriptor(
            pllIndex = 0,
            label = "PLL0 (SYS / QSPI / Primary Subsystems)",
            groupIndex = 0,
            mmioBase = 0xFF70_1000L,
            firstClockId = 0,
            lastClockId = 26,
            role = "Core/System/QSPI and primary subsystem dividers (7 sub-groups)",
            observedOutputs = linkedMapOf(
                "clk[0]" to "SYS main clock divider (0xFF701040, boot.bin writes 0x101)",
                "clk[2]" to "QSPI NOR flash clock divider (0xFF701048, boot.bin writes 0x105/0x103)",
            ),
        ),
        PllDescriptor(
            pllIndex = 1,
            label = "PLL1 (System / Bus Group 1)",
            groupIndex = 7,
            mmioBase = 0xFF70_2000L,
            firstClockId = 27,
            lastClockId = 31,
            role = "System, bus, and peripheral sub-clocks (2 sub-groups)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 2,
            label = "PLL2 (Domain 0x3D Group 2)",
            groupIndex = 9,
            mmioBase = 0xFF70_3000L,
            firstClockId = 32,
            lastClockId = 32,
            role = "Domain 0x3D clock source (0x015AA61C)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 3,
            label = "PLL3 (SD / UHS Storage)",
            groupIndex = 10,
            mmioBase = 0xFF70_4000L,
            firstClockId = 33,
            lastClockId = 34,
            role = "Domain 0x3D SD/UHS storage clock dividers (div steps 5/10/20/40/80)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 4,
            label = "PLL4 (Master Domain Group 4)",
            groupIndex = 11,
            mmioBase = 0xFF70_7000L,
            firstClockId = 35,
            lastClockId = 35,
            role = "Master 71-domain clock manager output (0x015AAC40)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 5,
            label = "PLL5 (Master Domain Group 5)",
            groupIndex = 12,
            mmioBase = 0xFF70_8000L,
            firstClockId = 36,
            lastClockId = 36,
            role = "Master 71-domain clock manager output",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 6,
            label = "PLL6 (ISP / Imaging Pipeline)",
            groupIndex = 13,
            mmioBase = 0xFF70_C000L,
            firstClockId = 37,
            lastClockId = 40,
            role = "Master clock manager ISP / imaging pipeline group",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 7,
            label = "PLL7 (Master Domain Group 7)",
            groupIndex = 14,
            mmioBase = 0xFF70_A000L,
            firstClockId = 41,
            lastClockId = 43,
            role = "Master 71-domain clock manager group 7",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 8,
            label = "PLL8 (Master Domain Group 8)",
            groupIndex = 15,
            mmioBase = 0xFF70_B000L,
            firstClockId = 44,
            lastClockId = 45,
            role = "Master 71-domain clock manager group 8",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 9,
            label = "PLL9 (Domain 0x42 Primary)",
            groupIndex = 16,
            mmioBase = 0xFF70_6000L,
            firstClockId = 46,
            lastClockId = 46,
            role = "Domain 0x42 clock source paired with PLL11 (0x015AA34C)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 10,
            label = "PLL10 (EVF / LCDC0)",
            groupIndex = 17,
            mmioBase = 0xFF70_9000L,
            firstClockId = 47,
            lastClockId = 49,
            role = "EVF / LCDC0 pixel clock generator (0x01255474)",
            observedOutputs = linkedMapOf(
                "clk[47]" to "EVF / LCDC0 pixel clock divider (0xFF709040)",
            ),
            observedBank14Pairs = listOf(
                linkedMapOf("mode" to 0L, "p0" to 0x42L, "p1" to 0x06L, "div" to 0x15L),
                linkedMapOf("mode" to 1L, "p0" to 0x42L, "p1" to 0x06L, "div" to 0x08L),
            ),
        ),
        PllDescriptor(
            pllIndex = 11,
            label = "PLL11 (Domain 0x42 Secondary)",
            groupIndex = 18,
            mmioBase = 0xFF70_5000L,
            firstClockId = 50,
            lastClockId = 50,
            role = "Domain 0x42 12-mode clock source (0x00420001..0x0042000C)",
            observedOutputs = emptyMap(),
        ),
        PllDescriptor(
            pllIndex = 12,
            label = "PLL12 (Rear LCD / LCDC1)",
            groupIndex = 19,
            mmioBase = 0xFFFA_E000L,
            firstClockId = 51,
            lastClockId = 51,
            role = "Rear LCD / LCDC1 pixel clock generator (0x01255514)",
            observedOutputs = linkedMapOf(
                "clk[51]" to "Rear LCD / LCDC1 pixel clock divider (0xFFFAE040)",
            ),
            observedBank14Pairs = listOf(
                linkedMapOf("mode" to 0L, "p0" to 0x0AL, "p1" to 0x06L, "div" to 0x00L),
                linkedMapOf("mode" to 1L, "p0" to 0x07L, "p1" to 0x06L, "div" to 0x01L),
            ),
        ),
    )

    private val GROUP_SPECS: List<Triple<Int, Long, Int>> = listOf(
        // (pllIndex, groupBase, wordCount) matching ConfigurationFactory.counts (sum = 52)
        Triple(0, 0xFF70_1000L, 4), // group 0: clk[0..3]
        Triple(0, 0xFF70_1200L, 3), // group 1: clk[4..6]
        Triple(0, 0xFF70_1400L, 4), // group 2: clk[7..10]
        Triple(0, 0xFF70_1600L, 4), // group 3: clk[11..14]
        Triple(0, 0xFF70_1800L, 4), // group 4: clk[15..18]
        Triple(0, 0xFF70_1A00L, 4), // group 5: clk[19..22]
        Triple(0, 0xFF70_1C00L, 4), // group 6: clk[23..26]
        Triple(1, 0xFF70_2000L, 4), // group 7: clk[27..30]
        Triple(1, 0xFF70_2200L, 1), // group 8: clk[31]
        Triple(2, 0xFF70_3000L, 1), // group 9: clk[32]
        Triple(3, 0xFF70_4000L, 2), // group 10: clk[33..34]
        Triple(4, 0xFF70_7000L, 1), // group 11: clk[35]
        Triple(5, 0xFF70_8000L, 1), // group 12: clk[36]
        Triple(6, 0xFF70_C000L, 4), // group 13: clk[37..40]
        Triple(7, 0xFF70_A000L, 3), // group 14: clk[41..43]
        Triple(8, 0xFF70_B000L, 2), // group 15: clk[44..45]
        Triple(9, 0xFF70_6000L, 1), // group 16: clk[46]
        Triple(10, 0xFF70_9000L, 3), // group 17: clk[47..49]
        Triple(11, 0xFF70_5000L, 1), // group 18: clk[50]
        Triple(12, 0xFFFA_E000L, 1), // group 19: clk[51]
    )

    private val NAMED_DIVIDERS: Map<Int, String> = mapOf(
        0 to "SYS",
        2 to "QSPI",
        47 to "EVF/LCDC0",
        51 to "Rear LCD/LCDC1",
    )

    val DIVIDERS: List<DividerDescriptor> = buildList {
        var clockId = 0
        GROUP_SPECS.forEachIndexed { groupIndex, (pllIndex, groupBase, wordCount) ->
            for (slot in 0 until wordCount) {
                val id = clockId++
                val known = NAMED_DIVIDERS[id]
                add(
                    DividerDescriptor(
                        clockId = id,
                        pllIndex = pllIndex,
                        groupIndex = groupIndex,
                        mmioAddress = groupBase + 0x40L + slot * 4L,
                        label = known?.let { "clk[$id] ($it)" } ?: "clk[$id] (PLL$pllIndex group$groupIndex.$slot)",
                        confidence = "DAT",
                    ),
                )
            }
        }
        add(
            DividerDescriptor(
                clockId = 52,
                pllIndex = null,
                groupIndex = null,
                mmioAddress = 0xFFF4_860CL,
                label = "clk[52] (Direct non-PLL divider soc-selection@0xFFF4860C)",
                confidence = "DAT",
            ),
        )
        add(
            DividerDescriptor(
                clockId = 53,
                pllIndex = null,
                groupIndex = null,
                mmioAddress = 0xFFF4_8604L,
                label = "clk[53] (Direct non-PLL divider soc-selection@0xFFF48604)",
                confidence = "DAT",
            ),
        )
    }

    private val pllByGroup: Map<Int, PllDescriptor> = PLLS.associateBy { it.groupIndex }

    fun pllForGroup(groupIndex: Int): PllDescriptor? = pllByGroup[groupIndex]

    fun divider(clockId: Int): DividerDescriptor {
        require(clockId in DIVIDERS.indices) { "Clock divider ID out of range: $clockId" }
        return DIVIDERS[clockId]
    }

    fun toStateMap(): Map<String, Any> = linkedMapOf(
        "pllCount" to PLL_COUNT,
        "pllBackedDividerCount" to PLL_BACKED_DIVIDER_COUNT,
        "totalDividerCount" to TOTAL_DIVIDER_COUNT,
        "timerReferenceHz" to TIMER_REFERENCE_HZ,
        "peripheralStepTableHz" to PERIPHERAL_STEP_TABLE_HZ,
        "keyDomains" to linkedMapOf(
            "clk[0]" to "SYS",
            "clk[2]" to "QSPI",
            "PLL10" to "EVF/LCDC0 (0xFF709000, clk[47])",
            "PLL12" to "Rear LCD/LCDC1 (0xFFFAE000, clk[51])",
        ),
        "plls" to PLLS.map { it.toStateMap() },
        "dividers" to DIVIDERS.map { it.toStateMap() },
        "topologyConfidence" to "DAT",
        "cpuMhzAndVcoFormula" to "UNKNOWN",
    )
}
