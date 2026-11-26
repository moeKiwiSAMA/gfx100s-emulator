package com.github.nyabanban.fuji.emulator.runtime

internal data class PromotedLegacySpec(
    val id: String,
    val portId: String,
    val deviceId: String,
    val base: Long,
    val length: Int,
    val policy: String,
    val widths: Set<Int>,
    val alignment: Int,
    val littleEndian: Boolean,
    val prohibited: List<AddressInterval>,
    val readAllowed: IntArray,
    val writeAllowed: IntArray,
    val evidence: List<String>,
    val extraConflictPorts: Set<String> = emptySet(),
) {
    val allPorts: Set<String> get() = setOf(portId) + extraConflictPorts
}

/**
 * Compatibility metadata for the 27 M6.1 + M6.2 + M6.3 candidates promoted to optional component MMIO ports
 * (M6.1: #5, #6, #7, #18, #23-#25, #29; M6.2: #8-#17, #19-#22; M6.3: #26-#28, #30-#31) plus dynamic discovery
 * regions promoted to `soc-isp-cmd` (`reply`, `config-bank-0..6`), `soc-dma` (`ch4/7/11-*`), and `soc-isp`
 * (`core`, `bank-50000`, `lut-51000/55000/59000/6d000`, `bank-68000/6e000`, `stats-0/1`, `live-view-0/1/2`).
 * Used ONLY when the corresponding optional component port is inactive AND configured via legacy
 * discovery settings, or when restoring historical snapshots.
 */
internal object PromotedLegacyCandidates {
    private data class WordRun(val first: Int, val last: Int, val read: Boolean, val write: Boolean)

    private fun fromRuns(
        id: String,
        portId: String,
        base: Long,
        length: Int,
        runs: List<WordRun>,
        evidence: List<String>,
        extraConflictPorts: Set<String> = emptySet(),
    ): PromotedLegacySpec {
        val holes = mutableListOf<AddressInterval>()
        val readAllowed = IntArray(length)
        val writeAllowed = IntArray(length)
        var cursor = 0
        for (run in runs) {
            if (cursor < run.first) {
                holes += AddressInterval(base + cursor, base + run.first)
            }
            for (offset in run.first..run.last step 4) {
                if (run.read) readAllowed[offset] = 4
                if (run.write) writeAllowed[offset] = 4
            }
            cursor = run.last + 4
        }
        if (cursor < length) {
            holes += AddressInterval(base + cursor, base + length)
        }
        return PromotedLegacySpec(
            id = id,
            portId = portId,
            deviceId = "instance.gfx100s.soc-system-control",
            base = base,
            length = length,
            policy = "refuse",
            widths = setOf(4),
            alignment = 4,
            littleEndian = true,
            prohibited = holes,
            readAllowed = readAllowed,
            writeAllowed = writeAllowed,
            evidence = evidence,
            extraConflictPorts = extraConflictPorts,
        )
    }

    const val LIVE_VIEW_CONFIG_WORDS_REGION = "gfx100s.fff72028.config-words.candidate"

    val all: List<PromotedLegacySpec> = listOf(
        // #5: status-a200
        fromRuns(
            id = "gfx100s.system-control.status-a200.candidate",
            portId = "soc-system-control.status-a200",
            base = 0xfff4a200L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = false)),
            evidence = listOf("evidence/refactor/lcd-current-path-and-controls-941-945/lcd-startup-dependencies-943-report.md"),
        ),
        // #6: hdmi.init-words
        fromRuns(
            id = HdmiStatusSamples.REGION,
            portId = "soc-hdmi",
            base = 0xfff7b000L,
            length = 2704,
            runs = listOf(
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
            ),
            evidence = listOf("evidence/refactor/hdmi-b020/README.md"),
        ),
        // #7: panel.init-words
        fromRuns(
            id = PanelInitOnce.REGION,
            portId = "soc-panel",
            base = PanelInitOnce.BASE,
            length = 24,
            runs = listOf(
                WordRun(0, 4, read = true, write = true),
                WordRun(8, 20, read = false, write = true),
            ),
            evidence = listOf("catalog/inventory.yaml"),
        ),
        // #18: fff49530.write-pair
        fromRuns(
            id = WRITE_49530_REGION,
            portId = "soc-system-control.fff49530-pair",
            base = 0xfff49530L,
            length = 8,
            runs = listOf(WordRun(0, 4, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section5.bin"),
        ),
        // #23: fff3a000.uart-bank
        fromRuns(
            id = "gfx100s.fff3a000.uart-bank.candidate",
            portId = "soc-uart.uart-bank",
            base = 0xfff3a000L,
            length = 48,
            runs = listOf(
                WordRun(0, 0, read = false, write = true),
                WordRun(24, 24, read = true, write = false),
                WordRun(36, 40, read = false, write = true),
                WordRun(44, 44, read = true, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #24: fff3a030.uart-config
        fromRuns(
            id = "gfx100s.fff3a030.uart-config.candidate",
            portId = "soc-uart.uart-config",
            base = 0xfff3a030L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #25: fff3a034.uart-tail
        fromRuns(
            id = UART_TAIL_REGION,
            portId = "soc-uart.uart-tail",
            base = 0xfff3a034L,
            length = 8,
            runs = listOf(WordRun(0, 4, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #29: fff49500.write-tail
        fromRuns(
            id = TAIL_49500_REGION,
            portId = "soc-system-control.fff49500-tail",
            base = 0xfff49500L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section5.bin"),
        ),
        // #8: fff7801c.poll
        fromRuns(
            id = POLL_7801C_REGION,
            portId = "soc-isp-setup.ctrl1-poll",
            base = 0xfff7801cL,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #9: fff4f008.setup
        fromRuns(
            id = "gfx100s.fff4f008.setup.candidate",
            portId = "soc-isp-setup.ctrl0-low",
            base = 0xfff4f008L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #22: fff4f00c.runtime-control
        fromRuns(
            id = "gfx100s.fff4f00c.runtime-control.candidate",
            portId = "soc-isp-setup.ctrl0-low",
            base = 0xfff4f00cL,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #10: fff4f010.setup-tail
        fromRuns(
            id = "gfx100s.fff4f010.setup-tail.candidate",
            portId = "soc-isp-setup.ctrl0-low",
            base = 0xfff4f010L,
            length = 8,
            runs = listOf(WordRun(0, 4, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #11: fff4f018.mode
        fromRuns(
            id = "gfx100s.fff4f018.mode.candidate",
            portId = "soc-isp-setup.ctrl0-low",
            base = 0xfff4f018L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #12: fff4f020.mode-setup
        fromRuns(
            id = "gfx100s.fff4f020.mode-setup.candidate",
            portId = "soc-isp-setup.ctrl0-high",
            base = 0xfff4f020L,
            length = 12,
            runs = listOf(
                WordRun(0, 0, read = false, write = true),
                WordRun(8, 8, read = false, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #14: fff78008.setup
        fromRuns(
            id = "gfx100s.fff78008.setup.candidate",
            portId = "soc-isp-setup.ctrl1-low",
            base = 0xfff78008L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #13: fff7800c.control
        fromRuns(
            id = "gfx100s.fff7800c.control.candidate",
            portId = "soc-isp-setup.ctrl1-low",
            base = 0xfff7800cL,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #15: fff78010.setup-tail
        fromRuns(
            id = "gfx100s.fff78010.setup-tail.candidate",
            portId = "soc-isp-setup.ctrl1-low",
            base = 0xfff78010L,
            length = 8,
            runs = listOf(WordRun(0, 4, read = false, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #16: fff78018.mode
        fromRuns(
            id = "gfx100s.fff78018.mode.candidate",
            portId = "soc-isp-setup.ctrl1-low",
            base = 0xfff78018L,
            length = 4,
            runs = listOf(WordRun(0, 0, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #17: fff78020.mode-setup
        fromRuns(
            id = "gfx100s.fff78020.mode-setup.candidate",
            portId = "soc-isp-setup.ctrl1-high",
            base = 0xfff78020L,
            length = 12,
            runs = listOf(
                WordRun(0, 0, read = false, write = true),
                WordRun(8, 8, read = false, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #19: fff6c000.words
        fromRuns(
            id = RETAINED_6C000_REGION,
            portId = "soc-isp-cmd.c000-words",
            base = 0xfff6c000L,
            length = 516,
            runs = listOf(
                WordRun(0, 36, read = true, write = true),
                WordRun(40, 40, read = true, write = false),
                WordRun(44, 48, read = true, write = true),
                WordRun(52, 52, read = true, write = false),
                WordRun(56, 56, read = true, write = true),
                WordRun(60, 60, read = true, write = false),
                WordRun(64, 100, read = true, write = true),
                WordRun(104, 116, read = true, write = false),
                WordRun(120, 140, read = true, write = true),
                WordRun(144, 144, read = true, write = false),
                WordRun(192, 228, read = true, write = true),
                WordRun(232, 244, read = true, write = false),
                WordRun(248, 268, read = true, write = true),
                WordRun(272, 272, read = true, write = false),
                WordRun(276, 276, read = true, write = true),
                WordRun(320, 320, read = true, write = true),
                WordRun(324, 324, read = false, write = true),
                WordRun(384, 432, read = true, write = true),
                WordRun(512, 512, read = true, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #20: fff6c204.words
        fromRuns(
            id = RETAINED_6C204_REGION,
            portId = "soc-isp-cmd.c204-control",
            base = 0xfff6c204L,
            length = 8,
            runs = listOf(WordRun(0, 4, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #21: fff6c20c.config-set
        fromRuns(
            id = RETAINED_6C20C_REGION,
            portId = "soc-isp-cmd.c20c-config",
            base = 0xfff6c20cL,
            length = 20,
            runs = listOf(WordRun(0, 16, read = true, write = true)),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #26: fff69000.config
        fromRuns(
            id = RETAINED_69000_REGION,
            portId = "soc-isp.config-69000",
            base = 0xfff69000L,
            length = 44,
            runs = listOf(
                WordRun(0, 0, read = true, write = true),
                WordRun(8, 12, read = true, write = true),
                WordRun(20, 40, read = true, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #27: fff69100.config-set
        fromRuns(
            id = RETAINED_69100_REGION,
            portId = "soc-isp.config-69100",
            base = 0xfff69100L,
            length = 340,
            runs = listOf(
                WordRun(0, 0, read = true, write = true),
                WordRun(4, 64, read = true, write = true),
                WordRun(68, 72, read = true, write = false),
                WordRun(80, 80, read = true, write = true),
                WordRun(256, 256, read = true, write = true),
                WordRun(260, 320, read = true, write = true),
                WordRun(324, 328, read = true, write = false),
                WordRun(336, 336, read = true, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #28: fff6b000.config
        fromRuns(
            id = RETAINED_6B000_REGION,
            portId = "soc-isp.config-6b000",
            base = 0xfff6b000L,
            length = 248,
            runs = listOf(
                WordRun(0, 20, read = true, write = true),
                WordRun(32, 44, read = true, write = true),
                WordRun(240, 244, read = true, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
        ),
        // #30: fff11060.result-surface
        fromRuns(
            id = IMAGE_RESULT_SURFACE,
            portId = "soc-isp.result-surface",
            base = 0xfff11060L,
            length = 6528,
            runs = listOf(
                WordRun(0, 1020, read = true, write = false),
                WordRun(1024, 2044, read = true, write = false),
                WordRun(2048, 3068, read = true, write = false),
                WordRun(3072, 4092, read = true, write = false),
                WordRun(4096, 4412, read = true, write = false),
                WordRun(6464, 6492, read = true, write = false),
                WordRun(6496, 6524, read = true, write = false),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section5.bin"),
        ),
        // #31: fff72028.config-words (spans live-view-0 and live-view-1)
        fromRuns(
            id = LIVE_VIEW_CONFIG_WORDS_REGION,
            portId = "soc-isp.live-view-0",
            base = 0xfff72028L,
            length = 4244,
            runs = listOf(
                WordRun(0, 0, read = false, write = true),
                WordRun(12, 16, read = false, write = true),
                WordRun(24, 144, read = false, write = true),
                WordRun(4096, 4096, read = false, write = true),
                WordRun(4108, 4112, read = false, write = true),
                WordRun(4120, 4240, read = false, write = true),
            ),
            evidence = listOf("firmware-data/gfx100s/2.13/decoded/section4.bin"),
            extraConflictPorts = setOf("soc-isp.live-view-1"),
        ),
    )

    val byId: Map<String, PromotedLegacySpec> = all.associateBy { it.id }
    val byPortId: Map<String, List<PromotedLegacySpec>> = buildMap<String, MutableList<PromotedLegacySpec>> {
        for (spec in all) {
            for (port in spec.allPorts) {
                getOrPut(port) { mutableListOf() }.add(spec)
            }
        }
    }

    const val ISP_REPLY_PORT = "soc-isp-cmd.reply"
    val imageConfigPortsByRegionId: Map<String, String> = (0..6).associate { bank ->
        val base = 0xfff6c280L + bank * 64
        "diagnostic.image-config.${base.toString(16)}" to "soc-isp-cmd.config-bank-$bank"
    }
    val channelPortsByRegionId: Map<String, String> = mapOf(
        "diagnostic.channel4.fff3c020" to "soc-dma.ch4-control",
        "diagnostic.channel4.fff3c140" to "soc-dma.ch4-params",
        "diagnostic.channel7.fff3c000" to "soc-dma.ch7-enable",
        "diagnostic.channel7.fff3c02c" to "soc-dma.ch7-control",
        "diagnostic.channel7.fff3c170" to "soc-dma.ch7-params",
        "diagnostic.channel11.fff3c03c" to "soc-dma.ch11-control",
        "diagnostic.channel11.fff3c1b0" to "soc-dma.ch11-params",
    )
    val channel4Ports: Set<String> = setOf("soc-dma.ch4-control", "soc-dma.ch4-params")
    val channel7Ports: Set<String> = setOf("soc-dma.ch7-enable", "soc-dma.ch7-control", "soc-dma.ch7-params")
    val channel11Ports: Set<String> = setOf("soc-dma.ch11-control", "soc-dma.ch11-params")

    const val ISP_CORE_PORT = "soc-isp.core"
    const val ISP_BANK_50000_PORT = "soc-isp.bank-50000"
    const val ISP_LUT_51000_PORT = "soc-isp.lut-51000"
    const val ISP_LUT_55000_PORT = "soc-isp.lut-55000"
    const val ISP_LUT_59000_PORT = "soc-isp.lut-59000"
    const val ISP_BANK_68000_PORT = "soc-isp.bank-68000"
    const val ISP_BANK_6E000_PORT = "soc-isp.bank-6e000"
    const val ISP_STATS_0_PORT = "soc-isp.stats-0"
    const val ISP_STATS_1_PORT = "soc-isp.stats-1"
    const val ISP_LUT_6D000_PORT = "soc-isp.lut-6d000"
    const val ISP_LIVE_VIEW_0_PORT = "soc-isp.live-view-0"
    const val ISP_LIVE_VIEW_1_PORT = "soc-isp.live-view-1"
    const val ISP_LIVE_VIEW_2_PORT = "soc-isp.live-view-2"

    val isp51000Ports: Set<String> = setOf(ISP_LUT_51000_PORT, ISP_LUT_55000_PORT)
    val ispResultStatsPorts: Set<String> = setOf(ISP_STATS_0_PORT, ISP_STATS_1_PORT)
    val ispLiveViewPorts: Set<String> = setOf(
        ISP_LUT_6D000_PORT,
        ISP_LIVE_VIEW_0_PORT,
        ISP_LIVE_VIEW_1_PORT,
        ISP_LIVE_VIEW_2_PORT,
    )
    val image50000WordIds: Set<String> = listOf(4..76, 144..432, 548..612, 672..980, 4096..4100)
        .flatMap { range -> range step 4 }
        .map { offset -> "diagnostic.image-word.${(0xfff50000L + offset).toString(16)}" }
        .toSet()

    val dynamicRegionIds: Set<String> = imageReplyIds + imageConfigPortsByRegionId.keys + channelPortsByRegionId.keys +
        image50000WordIds + setOf(
            IMAGE_50000_CONTROL_REGION,
            Image51000.TABLE,
            Image51000.EMPTY,
            IMAGE_59000_TABLES,
            Image68000.REGION,
            Image6e000.REGION,
            Image70000.REGION,
            ImageResultStats.REGION_BUF0,
            ImageResultStats.REGION_BUF1,
        ) + ImageLiveViewBanks.REGION_IDS

    fun activeDynamicRegionIds(activePorts: Set<String>): Set<String> = buildSet {
        if (ISP_REPLY_PORT in activePorts) addAll(imageReplyIds)
        for ((regionId, portId) in imageConfigPortsByRegionId) {
            if (portId in activePorts) add(regionId)
        }
        for ((regionId, portId) in channelPortsByRegionId) {
            if (portId in activePorts) add(regionId)
        }
        if (ISP_CORE_PORT in activePorts) add(Image70000.REGION)
        if (ISP_BANK_50000_PORT in activePorts) {
            add(IMAGE_50000_CONTROL_REGION)
            addAll(image50000WordIds)
        }
        if (ISP_LUT_51000_PORT in activePorts) add(Image51000.TABLE)
        if (ISP_LUT_55000_PORT in activePorts) add(Image51000.EMPTY)
        if (ISP_LUT_59000_PORT in activePorts) add(IMAGE_59000_TABLES)
        if (ISP_BANK_68000_PORT in activePorts) add(Image68000.REGION)
        if (ISP_BANK_6E000_PORT in activePorts) add(Image6e000.REGION)
        if ("soc-isp.result-surface-v2" in activePorts) add(IMAGE_RESULT_SURFACE)
        if (ISP_STATS_0_PORT in activePorts) add(ImageResultStats.REGION_BUF0)
        if (ISP_STATS_1_PORT in activePorts) add(ImageResultStats.REGION_BUF1)
        if (ISP_LUT_6D000_PORT in activePorts) add(ImageLiveViewBanks.REGION_6D000)
        if (ISP_LIVE_VIEW_0_PORT in activePorts) add(ImageLiveViewBanks.REGION_72000)
        if (ISP_LIVE_VIEW_1_PORT in activePorts) add(ImageLiveViewBanks.REGION_730C0)
        if (ISP_LIVE_VIEW_2_PORT in activePorts) add(ImageLiveViewBanks.REGION_74000)
    }

    fun isDynamicPortConfigured(portId: String, settings: Map<String, Any>): Boolean = when {
        portId == ISP_REPLY_PORT ->
            listOf("imageReply820001", "imageReply820040", "imageReply820041", ProgrammedImageReply.KEY).any { it in settings }
        portId in imageConfigPortsByRegionId.values -> "imageConfigPairs" in settings
        portId in channel4Ports -> "channel4Programming" in settings
        portId in channel7Ports -> "channel7Programming" in settings
        portId in channel11Ports -> "channel11Programming" in settings
        portId == ISP_CORE_PORT -> "image70000" in settings || ImageIrq106Status.KEY in settings
        portId == ISP_BANK_50000_PORT -> "image50000" in settings || "image50000Control" in settings
        portId in isp51000Ports -> "image51000" in settings
        portId == ISP_LUT_59000_PORT -> "image59000" in settings
        portId == ISP_BANK_68000_PORT -> "image68000" in settings
        portId == ISP_BANK_6E000_PORT -> "image6e000" in settings
        portId in ispResultStatsPorts -> ImageResultStats.KEY in settings
        portId in ispLiveViewPorts -> ImageLiveViewBanks.KEY in settings
        else -> false
    }

    fun dynamicPolicyForPort(portId: String, settings: Map<String, Any>): String? = when {
        portId == ISP_REPLY_PORT -> when {
            ProgrammedImageReply.KEY in settings -> DiscoveryStrategy.PROGRAMMED_REPLY.id
            "imageReply820040" in settings && "imageReply820001" in settings -> DiscoveryStrategy.INTERLEAVED_REPLY.id
            else -> DiscoveryStrategy.SCRIPT.id
        }
        portId in imageConfigPortsByRegionId.values -> DiscoveryStrategy.RETAINED.id
        portId == "soc-dma.ch4-control" ->
            if (settings["channel4Programming"] == DiscoveryStrategy.CHANNEL_CONTROL.id) DiscoveryStrategy.CHANNEL_CONTROL.id
            else DiscoveryStrategy.RETAINED.id
        portId == "soc-dma.ch11-control" ->
            if (settings["channel11Programming"] == DiscoveryStrategy.CHANNEL_CONTROL.id) DiscoveryStrategy.CHANNEL_CONTROL.id
            else DiscoveryStrategy.RETAINED.id
        portId in channel4Ports || portId in channel7Ports || portId in channel11Ports -> DiscoveryStrategy.RETAINED.id
        portId == ISP_CORE_PORT -> settings["image70000"]?.toString()
        portId == ISP_BANK_50000_PORT -> (settings["image50000Control"] ?: DiscoveryStrategy.RETAINED.id).toString()
        portId in isp51000Ports -> settings["image51000"]?.toString()
        portId == ISP_LUT_59000_PORT -> DiscoveryStrategy.RETAINED.id
        portId == ISP_BANK_68000_PORT -> settings["image68000"]?.toString()
        portId == ISP_BANK_6E000_PORT -> settings["image6e000"]?.toString()
        portId in ispResultStatsPorts -> DiscoveryStrategy.IMAGE.id
        portId in ispLiveViewPorts -> DiscoveryStrategy.IMAGE_LIVEVIEW_BANKS.id
        else -> null
    }
}
