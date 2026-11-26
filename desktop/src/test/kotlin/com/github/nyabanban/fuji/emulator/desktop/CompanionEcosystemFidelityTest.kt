package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.cpu.ResetSchedule
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Cross-project companion ecosystem full-SoC hardware-fidelity suite.
 *
 * Boots the real compiled pure-ARM32 companion binaries inside a full 4-core GFX100S
 * [FirmwareSession] (`armv7-full-vectors-v1`, `soc-gic`, strict `LCDC1` `bit 12` active output
 * gate, retail clock-gated `LCDC0` `0xFFF7A000` & `UART0` `0xFFF3A000`, and `soc-timer`
 * `Ch6`/`Ch7` write-mask observability):
 * 1. `fuji-gfx100s-beatmania` (`beatmania_baremetal.img` @ `0x02800000`)
 * 2. `fuji-gfx100s-ddr` (`ddr_baremetal.img` @ `0x02800000`)
 * 3. `banban-injector` (`stage2.bin` @ `0x03BC0000`) chaining `fuji-gfx100s-theme`
 *    (`theme.bbp` @ `0x03B80000` + v1-ext `"GFX1"` `sugarlink.bbth` @ `0x2AB00000`) over live `PIC1`.
 *
 * Skips cleanly via JUnit `assumeTrue` when sibling companion repositories are absent (e.g.
 * isolated CI containers).
 */
class CompanionEcosystemFidelityTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))

    private fun nativeLibrary(): Path {
        val candidate = System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")
            ?: System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?: System.getenv("FUJI_NATIVE_LINUX_CACHE")
        return Path.of(checkNotNull(candidate) { "A Unicorn native library env var must be configured" })
    }

    private fun findSiblingRepo(envVar: String, repoName: String): Path? {
        val env = System.getenv(envVar)?.takeIf { it.isNotBlank() }?.let(Path::of)
        val candidates = listOfNotNull(
            env,
            project.parent?.resolve(repoName),
            project.parent?.parent?.resolve(repoName),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
    }

    private fun stageCompanionSessionHome(home: Path, enableGpio: Boolean = false): AddressCatalog {
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        Files.list(project.resolve("build/emulator/compoments")).use { paths ->
            paths.filter { it.toString().endsWith(".jar") }
                .forEach { Files.copy(it, compDir.resolve(it.fileName)) }
        }
        val catalog = AddressCatalog.load(project.resolve("catalog/compiled/catalog.json"))
        val meta = catalog.firmwareMetadata(mapOf("soc-vglib-init" to "lcdc-functional-experimental-v1"))
        Formats.atomicYaml(home.resolve("gfx100s-213.yaml"), meta.mapValues { requireNotNull(it.value) })

        Formats.atomicYaml(
            compDir.resolve("display/display.yaml"),
            mapOf("width" to 1024L, "height" to 768L, "evfWidth" to 1024L, "evfHeight" to 768L),
        )
        Formats.atomicYaml(
            compDir.resolve("soc-gic/soc-gic.yaml"),
            mapOf(
                "profile" to "gicv2-catalog-experimental-v1",
                "coreProfile" to DynarmicCluster.PROFILE,
            ),
        )
        Formats.atomicYaml(
            compDir.resolve("system-memory/system-memory.yaml"),
            mapOf(
                "profile" to "linux-contiguous-256m-experimental-v1",
                "additionalRamProfile" to AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
            ),
        )
        val vglibDir = compDir.resolve("soc-vglib-init")
        Files.createDirectories(vglibDir)
        Files.writeString(
            vglibDir.resolve("vglib-input.json"),
            Formats.jsonText(
                mapOf(
                    "schema" to 1,
                    "profile" to "vglib-full-lane-init-experimental-v1",
                    "readConfidence" to "HYPOTHESIS",
                    "transactions" to (0 until 18).map { i ->
                        mapOf("offset" to (i * 4L), "readValue" to 0L, "writeValue" to 0L)
                    },
                ),
            ),
        )
        Files.writeString(
            vglibDir.resolve("mode-word.json"),
            Formats.jsonText(
                mapOf(
                    "schema" to 1,
                    "profile" to "vglib-mode-word-experimental-v1",
                    "readConfidence" to "HYPOTHESIS",
                    "readSample" to 0x0000_0200L,
                    "sourcePreserveMask" to 0xffff_fcffL,
                ),
            ),
        )
        Formats.atomicYaml(
            vglibDir.resolve("soc-vglib-init.yaml"),
            mapOf(
                "profile" to "lcdc-functional-experimental-v1",
                "inputFile" to "vglib-input.json",
                "controlWordInputFile" to "mode-word.json",
                "firstScanout" to "lcdc1-pic-yuv-osd0-nearest-v1",
                "scanoutFrameLimit" to 32L,
                "continuousScanout" to true,
                "strictActiveOutputGate" to true,
                "lcdc0ClockGated" to true,
                "unclockedAccessPolicy" to "abort",
            ),
        )
        val timerDir = compDir.resolve("soc-timer")
        Files.createDirectories(timerDir)
        Files.copy(
            project.resolve("configurations/jvm/experiments/timer6-63-timer7-64.json"),
            timerDir.resolve("timer6-7.json"),
        )
        Formats.atomicYaml(
            timerDir.resolve("soc-timer.yaml"),
            mapOf(
                "profile" to "interval-edge-routing-experimental-v1",
                "inputFile" to "timer6-7.json",
                "irqSink" to "soc-gic.lines",
            ),
        )
        Formats.atomicYaml(
            compDir.resolve("soc-uart/soc-uart.yaml"),
            mapOf(
                "profile" to "pl011-tx-ready-experimental-v1",
                "initialFill" to 0L,
                "initiallyClocked" to false,
                "unclockedAccessPolicy" to "abort",
            ),
        )
        if (enableGpio) {
            // Active-LOW idle buttons (all bits HIGH), except Bank 10 bit 8 LOW (SD2 physically present).
            val gpioSettings = LinkedHashMap<String, Any>()
            for (bank in 0..15) {
                gpioSettings["input$bank"] = if (bank == 10) 0xfeffL else 0xffffL
            }
            Formats.atomicYaml(compDir.resolve("soc-gpio/soc-gpio.yaml"), gpioSettings)
        }
        return catalog
    }

    private fun companionRamPlan(home: Path, catalog: AddressCatalog, sourceProfile: String): AdditionalRamPlan {
        val baseProfile = "linux-contiguous-256m-experimental-v1"
        val baseRegions = com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
            Formats.read(home.resolve("gfx100s-213.yaml")),
        ).memoryProfiles.getValue(baseProfile)
        return AdditionalRamPlan(
            profile = AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
            sourceProfile = sourceProfile,
            sourceCommit = "0".repeat(40),
            catalogSha256 = catalog.artifactSha256,
            baseProfile = baseProfile,
            baseRegions = baseRegions,
            additionalRegions = listOf(
                MemoryRegion("companion-ddr-03bf3000", 0x03bf_3000L, 0x0120_d000L, 7),
                MemoryRegion("companion-gap-04e03000", 0x04e0_3000L, 0x0000_1000L, 3),
                MemoryRegion("companion-gap-05608000", 0x0560_8000L, 0x0029_7000L, 3),
                MemoryRegion("companion-lcdc1-fb-05901000", 0x0590_1000L, 0x006f_f000L, 3),
            ),
        )
    }

    private fun seedStockLiveViewAndTimers(host: ComponentHost, seedPic1: Boolean = false) {
        val gicd = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
        gicd.write(0xc0cL, 4, 0x8000_0000L) // IRQ 63 edge-triggered
        gicd.write(0xc10L, 4, 0x0000_0002L) // IRQ 64 edge-triggered
        val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
        lcdc1.write(0x008, 4, 0x0001_1010L)
        lcdc1.write(0x030, 4, 0x2008_0001L)
        lcdc1.write(0x040, 4, 0x0000_0000L)
        lcdc1.write(0x044, 4, 0x059e_d000L)
        lcdc1.write(0x050, 4, 0x0001_0400L)
        lcdc1.write(0x054, 4, 0x0300_0400L)
        lcdc1.write(0x058, 4, 0x0000_0000L)
        lcdc1.write(0x05c, 4, 0x0300_0400L)
        lcdc1.write(0x060, 4, 0x1000_1000L)
        if (seedPic1) {
            // Seed live-camera 1024x768 UYVY PIC1 backdrop at 0x05000000 (inside aux-work-buffer, stride 2048 B = 256 * 8).
            lcdc1.write(0x160, 4, 0x0000_00ffL)
            lcdc1.write(0x164, 4, 0x0000_0000L)
            lcdc1.write(0x168, 4, 0x0500_0000L)
            lcdc1.write(0x16c, 4, 0x0000_0100L)
            lcdc1.write(0x170, 4, 0x0300_0400L)
            lcdc1.write(0x174, 4, 0x0000_0000L)
            lcdc1.write(0x178, 4, 0x0300_0400L)
            lcdc1.write(0x17c, 4, 0x1000_1000L)
        }
        val timer = checkNotNull(host.ports.find("soc-timer", MmioDevice::class.java))
        timer.write(0x40, 4, 500_000L)
        timer.write(0x44, 4, 500_000L)
        timer.write(0x48, 4, 500_000L)
        timer.write(0x4c, 4, 500_000L)
        timer.write(0x00, 4, 0x00c0_00c0L)
    }

    private fun runBareMetalArcadeFidelityCheck(
        name: String,
        imagePath: Path,
        minNonBlackPixels: Int,
        minUniqueColors: Int,
    ) {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("companion-$name-fidelity-")
        try {
            val catalog = stageCompanionSessionHome(home)
            val fileName = imagePath.fileName.toString()
            Files.copy(imagePath, home.resolve(fileName))

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 120_000_000L,
                "enabledDevices" to listOf("soc-gic", "soc-timer", "soc-vglib-init", "soc-uart"),
                "cbarInput" to 0xffff_0000L,
                "clusterProfile" to DynarmicCluster.PROFILE,
                "clusterReset" to mapOf(
                    "profile" to ResetSchedule.PROFILE,
                    "releaseTicks" to listOf(0L, 10_000_000L, 10_000_000L, 10_000_000L),
                ),
                "mmuProfile" to "arm-short-descriptor-native-experimental-v1",
                "exceptionProfile" to "armv7-full-vectors-v1",
                "customRawImage" to mapOf(
                    "file" to fileName,
                    "loadAddress" to "0x02800000",
                    "entry" to "0x02800000",
                ),
            )
            validateLaunchConfiguration(config)
            val ramPlan = companionRamPlan(home, catalog, "$name-contiguous-ddr-v1")

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    seedStockLiveViewAndTimers(host)
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        session.selectAdditionalRam(ramPlan)

                        val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                        assertEquals(1024, display.width)
                        assertEquals(768, display.height)
                        val pixels = IntArray(1024 * 768)

                        var frameReached = false
                        var nonBlackCount = 0
                        var opaqueCount = 0
                        var uniqueColors = 0
                        var lastSeq = 0L
                        while (session.step()) {
                            val seq = display.sequence
                            if (seq > lastSeq) {
                                lastSeq = seq
                                if (display.copyArgb(pixels)) {
                                    var nb = 0
                                    var op = 0
                                    val colors = HashSet<Int>(512)
                                    for (px in pixels) {
                                        if ((px ushr 24) == 0xff) op++
                                        val rgb = px and 0x00ffffff
                                        if (rgb != 0) nb++
                                        if (colors.size < 256) colors.add(rgb)
                                    }
                                    nonBlackCount = nb
                                    opaqueCount = op
                                    uniqueColors = colors.size
                                    if (nb >= minNonBlackPixels && op == 1024 * 768 && colors.size >= minUniqueColors) {
                                        frameReached = true
                                        break
                                    }
                                }
                            }
                        }

                        val st = session.state()
                        assertNull(st["frontier"], "$name bare-metal binary hit unexpected frontier: $st")

                        // 1. LCDC1 real-silicon bit 12 active output gate & opaque OSD0 contract
                        val layers = checkNotNull(
                            host.ports.find("soc-vglib-init.lcdc1-layers", LcdcLayerObservabilityPort::class.java),
                        ).lcdc1LayerState()
                        assertEquals(0x0001_1000L, layers.reg008, "$name must preserve LCDC1 bit 12 + OSD0 and clear PIC0/PIC1")
                        assertEquals(0x1fc7_f088L, layers.reg030, "$name must set opaque LCDC1 OSD0 control 0x1FC7F088")
                        assertTrue(layers.activeOutputGate, "$name LCDC1 bit 12 active output gate must stay enabled")
                        assertTrue(layers.outputActive, "$name LCDC1 output must be active")
                        assertNull(layers.blankReason, "$name LCDC1 must never blank")
                        assertEquals(0L, layers.activeGateBlankCount, "$name must have ZERO bit-12 gray blanks")

                        // 2. Retail clock-gated peripherals (LCDC0 & UART0) must remain completely untouched
                        val lcdc0Power = checkNotNull(
                            host.ports.find("soc-vglib-init.lcdc0-power", LcdcPowerGatePort::class.java),
                        ).lcdc0PowerState()
                        assertEquals(0L, lcdc0Power.unclockedAccessCount, "$name must never touch retail-gated LCDC0 (0xFFF7A000)")
                        val uartPower = checkNotNull(
                            host.ports.find("soc-uart.clock-gate", UartClockGatePort::class.java),
                        ).clockGateState()
                        assertEquals(0L, uartPower.unclockedTxAttempts, "$name must never TX to retail-gated UART0 (0xFFF3A000)")
                        assertEquals(0L, uartPower.unclockedReadAttempts, "$name must never read retail-gated UART0 (0xFFF3A000)")

                        // 3. SPX4 Timer Ch6 display timer must remain untouched while Ch7 ticks
                        val timerAudit = checkNotNull(
                            host.ports.find("soc-timer.channels", TimerChannelObservabilityPort::class.java),
                        ).channelAuditState()
                        assertTrue(timerAudit.ch6DisplayTimerActive, "$name must keep SPX4 Ch6 display/backlight timer active")
                        assertTrue(timerAudit.ch7OsTickTimerActive, "$name must enable SPX4 Ch7 tick timer")
                        assertEquals(0L, timerAudit.ch6DisableCount, "$name must never disable Ch6")
                        assertEquals(0L, timerAudit.blindMultiChannelDisableCount, "$name must never issue blind multi-channel timer disable")

                        // 4. Live 1024x768 non-black opaque rendered frame on display.main
                        assertTrue(
                            frameReached,
                            "Expected rich 1024x768 $name frame on display.main " +
                                "(seq=$lastSeq, nonBlack=$nonBlackCount, opaque=$opaqueCount, colors=$uniqueColors, state=$st)",
                        )
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun beatmaniaBareMetalRenders1024x768DeckUnderFullSocFidelityGuards() {
        val repo = findSiblingRepo("FUJI_BEATMANIA_DIR", "fuji-gfx100s-beatmania")
        val img = repo?.resolve("build/arm/beatmania_baremetal.img")?.takeIf { Files.isRegularFile(it) }
        assumeTrue(img != null, "Sibling fuji-gfx100s-beatmania build/arm/beatmania_baremetal.img not present")
        runBareMetalArcadeFidelityCheck(
            name = "beatmania",
            imagePath = checkNotNull(img),
            minNonBlackPixels = 400_000,
            minUniqueColors = 16,
        )
    }

    @Test
    fun ddrBareMetalRenders1024x768HighwayUnderFullSocFidelityGuards() {
        val repo = findSiblingRepo("FUJI_DDR_DIR", "fuji-gfx100s-ddr")
        val img = repo?.resolve("build/arm/ddr_baremetal.img")?.takeIf { Files.isRegularFile(it) }
        assumeTrue(img != null, "Sibling fuji-gfx100s-ddr build/arm/ddr_baremetal.img not present")
        runBareMetalArcadeFidelityCheck(
            name = "ddr",
            imagePath = checkNotNull(img),
            minNonBlackPixels = 400_000,
            minUniqueColors = 16,
        )
    }

    @Test
    fun banbanStage2AndThemeBbpCompositeOverLivePic1UnderFullSocFidelityGuards() {
        val injectorRepo = findSiblingRepo("FUJI_BANBAN_INJECTOR_DIR", "banban-injector")
        val themeRepo = findSiblingRepo("FUJI_THEME_DIR", "fuji-gfx100s-theme")
        val stage2Bin = injectorRepo?.resolve("stage2/build/stage2.bin")?.takeIf { Files.isRegularFile(it) }
        val themeBbp = themeRepo?.let { dir ->
            listOf(dir.resolve("plugin/build/theme.bbp"), dir.resolve("build/theme.bbp"))
                .firstOrNull { Files.isRegularFile(it) }
        }
        val sugarlinkBbth = themeRepo?.resolve("themes/sugarlink.bbth")?.takeIf { Files.isRegularFile(it) }
        val sugarlinkBban = injectorRepo?.resolve("assets/themes/sugarlink.bban")?.takeIf { Files.isRegularFile(it) }
        assumeTrue(
            stage2Bin != null && themeBbp != null && sugarlinkBbth != null && sugarlinkBban != null,
            "Sibling banban-injector (stage2.bin + sugarlink.bban) and fuji-gfx100s-theme (theme.bbp + sugarlink.bbth) artifacts not present",
        )

        val lib = nativeLibrary()
        val home = Files.createTempDirectory("companion-stage2-theme-fidelity-")
        try {
            val catalog = stageCompanionSessionHome(home, enableGpio = true)
            Files.copy(checkNotNull(stage2Bin), home.resolve("stage2.bin"))

            val vfsBase = 0x1000_0000
            val vfsBundle = buildInMemoryVfsBundle(
                baseAddr = vfsBase,
                files = linkedMapOf(
                    "B:\\BANBAN\\BOOT.CFG" to "mode=plugins\nplugins=theme.bbp\nsplash_ms=0\nuart=0\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                    "B:\\BANBAN\\THEME.CFG" to "enabled=1\ntheme=sugarlink\nallow_hotkeys=1\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                    "B:\\BANBAN\\PLUGINS\\THEME.BBP" to Files.readAllBytes(checkNotNull(themeBbp)),
                    "B:\\BANBAN\\UI-THEMES\\SUGARLINK.BBTH" to Files.readAllBytes(checkNotNull(sugarlinkBbth)),
                    "B:\\BANBAN\\THEMES\\SUGARLINK.BBAN" to Files.readAllBytes(checkNotNull(sugarlinkBban)),
                ),
            )
            Files.write(home.resolve("vfs_bundle.bin"), vfsBundle)

            // Pure-ARM32 guest firmware-service harness placed in low RAM at 0x00010000:
            // implements stock FW_FS_OPEN/READ/CLOSE, FW_CRC32, FW_MMU_PROTECT, FW_TX_SLEEP_RAW,
            // and LCDEVF commit/state hooks in authentic guest ARM32 instructions (zero host PC/RAM tampering).
            val fwHarness = buildStage2FirmwareHarness(
                entryBase = 0x0001_0000,
                vfsBase = vfsBase,
                handleTableBase = 0x0001_8000,
                doneMarkerAddr = 0x0001_f140,
            )
            Files.write(home.resolve("fw_harness.bin"), fwHarness)

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 180_000_000L,
                "enabledDevices" to listOf("soc-gic", "soc-timer", "soc-vglib-init", "soc-uart", "soc-gpio"),
                "cbarInput" to 0xffff_0000L,
                "clusterProfile" to DynarmicCluster.PROFILE,
                "clusterReset" to mapOf(
                    "profile" to ResetSchedule.PROFILE,
                    "releaseTicks" to listOf(0L, 10_000_000L, 10_000_000L, 10_000_000L),
                ),
                "mmuProfile" to "arm-short-descriptor-native-experimental-v1",
                "exceptionProfile" to "armv7-full-vectors-v1",
                "customRawImage" to mapOf(
                    "file" to "stage2.bin",
                    "loadAddress" to "0x03bc0000",
                    "entry" to "0x00010000",
                    "sp" to "0x10800000",
                    "dtbFile" to "fw_harness.bin",
                    "dtbAddress" to "0x00010000",
                    "initrdFile" to "vfs_bundle.bin",
                    "initrdAddress" to "0x10000000",
                ),
            )
            validateLaunchConfiguration(config)
            val ramPlan = companionRamPlan(home, catalog, "stage2-theme-ddr-v1")

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    seedStockLiveViewAndTimers(host, seedPic1 = true)
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        session.selectAdditionalRam(ramPlan)

                        val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                        assertEquals(1024, display.width)
                        assertEquals(768, display.height)
                        val pixels = IntArray(1024 * 768)

                        val expectedPic1BackdropRgb = 0x7d95dd // UYVY 0x966E96A8 studio sky-blue PIC1 backdrop
                        var doneReached = false
                        var lastSeq = 0L
                        var maxDistinctColors = 0
                        var maxNonBackdropPixels = 0
                        var sawPic1BackdropThroughOsd0 = false
                        while (session.step()) {
                            val seq = display.sequence
                            if (seq > lastSeq) {
                                lastSeq = seq
                                if (display.copyArgb(pixels)) {
                                    // Center viewfinder (512, 384) or border (0, 0) exposes live PIC1 YUV422
                                    // backdrop through OSD0 chroma-key 0x00000000.
                                    val centerRgb = pixels[384 * 1024 + 512] and 0x00ffffff
                                    val cornerRgb = pixels[0] and 0x00ffffff
                                    if (centerRgb == expectedPic1BackdropRgb || cornerRgb == expectedPic1BackdropRgb) {
                                        sawPic1BackdropThroughOsd0 = true
                                    }
                                    val colors = HashSet<Int>(512)
                                    var nonBackdrop = 0
                                    for (px in pixels) {
                                        val rgb = px and 0x00ffffff
                                        if (colors.size < 256) colors.add(rgb)
                                        if (rgb != expectedPic1BackdropRgb) nonBackdrop++
                                    }
                                    if (colors.size > maxDistinctColors) maxDistinctColors = colors.size
                                    if (nonBackdrop > maxNonBackdropPixels) maxNonBackdropPixels = nonBackdrop
                                }
                            }
                            val marker = ByteBuffer.wrap(session.memory(0x0001_f140L, 8))
                                .order(ByteOrder.LITTLE_ENDIAN)
                            if (marker.getInt(0) == 0x444f_4e46) { // "DONF"
                                doneReached = true
                                break
                            }
                        }

                        val st = session.state()
                        assertNull(st["frontier"], "stage2.bin + theme.bbp hit unexpected frontier: $st")
                        assertTrue(doneReached, "stage2.bin + theme.bbp harness did not reach DONF completion: $st")

                        // 1. Verify Stage-2 alive marker @ 0x0001F0F0 ("BBNH" / "STG2")
                        val s2Mark = ByteBuffer.wrap(session.memory(0x0001_f0f0L, 16)).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0x484e_4242, s2Mark.getInt(0), "Missing Stage-2 BBNH magic at 0x0001F0F0")
                        assertEquals(0x5354_4732, s2Mark.getInt(12), "Missing Stage-2 STG2 magic at 0x0001F0FC")

                        // 2. Verify Stage-2 Boot Breadcrumb Struct @ 0x0001F020 ("BBBC" v1)
                        val bcBuf = ByteBuffer.wrap(session.memory(0x0001_f020L, 0xa8)).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0x4342_4242, bcBuf.getInt(0x00), "Missing BBBC breadcrumb magic at 0x0001F020")
                        assertEquals(1, bcBuf.getInt(0x04), "Unexpected BBBC breadcrumb version")
                        assertEquals(2, bcBuf.getInt(0x08), "Expected BANBAN_MODE_PLUGINS (2)")
                        assertEquals(1, bcBuf.getInt(0x1c), "Expected plugins_attempted == 1")
                        assertEquals(1, bcBuf.getInt(0x20), "Expected plugins_succeeded == 1")
                        assertEquals(0, bcBuf.getInt(0x24), "Expected last_rc == 0 (BANBAN_CONTINUE_STOCK)")
                        val doneBuf = ByteBuffer.wrap(session.memory(0x0001_f140L, 8)).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0, doneBuf.getInt(4), "Expected stage2_main() return code == 0")

                        // 3. Verify theme.bbp telemetry @ 0x0001F120 ("BBTH") + dual-CRC32 sugarlink.bbth + live hooks
                        //    (stage2.bin >= cb79bed installs both >32 MiB lcdevf hooks via low-DDR veneer @ 0x0001D000).
                        val thBuf = ByteBuffer.wrap(session.memory(0x0001_f120L, 16)).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(0x4854_4242, thBuf.getInt(0), "Missing theme.bbp BBTH magic at 0x0001F120")
                        val statusWord = thBuf.getInt(4)
                        assertEquals(1, (statusWord ushr 16) and 1, "Expected theme.bbp enabled == 1")
                        assertEquals(1, (statusWord ushr 8) and 1, "Expected sugarlink.bbth dual-CRC32 validated == 1")
                        val hookMask = thBuf.getInt(8)
                        assertEquals(0x3, hookMask, "Expected both lcdevf commit/state ARM32 RAM hooks installed via >32 MiB veneer (0x3)")
                        assertTrue(
                            thBuf.getInt(12) >= 2,
                            "Expected >=2 ARM32 theme.bbp rendered frames (initial + live hooked lcdevf_commit), got ${thBuf.getInt(12)}",
                        )

                        // 4. Verify LCDC1 preserved bit 12 active output gate + OSD0 + PIC1 live camera view
                        val layers = checkNotNull(
                            host.ports.find("soc-vglib-init.lcdc1-layers", LcdcLayerObservabilityPort::class.java),
                        ).lcdc1LayerState()
                        assertEquals(0x0001_1010L, layers.reg008, "stage2 + theme.bbp must preserve LCDC1 bit 12 + OSD0 + PIC1")
                        assertEquals(0x2008_0001L, layers.reg030, "stage2 + theme.bbp must preserve transparent chroma-key Live View OSD0")
                        assertTrue(layers.activeOutputGate, "LCDC1 bit 12 active output gate must stay enabled")
                        assertTrue(layers.outputActive, "LCDC1 output must be active")
                        assertNull(layers.blankReason, "LCDC1 must never blank")
                        assertEquals(0L, layers.activeGateBlankCount, "stage2 + theme.bbp must have ZERO bit-12 gray blanks")

                        // 5. Verify retail clock-gated LCDC0 & UART0 + SPX4 Timer Ch6 untouched
                        val lcdc0Power = checkNotNull(
                            host.ports.find("soc-vglib-init.lcdc0-power", LcdcPowerGatePort::class.java),
                        ).lcdc0PowerState()
                        assertEquals(0L, lcdc0Power.unclockedAccessCount, "stage2 + theme.bbp must never touch retail-gated LCDC0")
                        val uartPower = checkNotNull(
                            host.ports.find("soc-uart.clock-gate", UartClockGatePort::class.java),
                        ).clockGateState()
                        assertEquals(0L, uartPower.unclockedTxAttempts, "stage2 + theme.bbp must never TX to retail-gated UART0")
                        assertEquals(0L, uartPower.unclockedReadAttempts, "stage2 + theme.bbp must never read retail-gated UART0")
                        val timerAudit = checkNotNull(
                            host.ports.find("soc-timer.channels", TimerChannelObservabilityPort::class.java),
                        ).channelAuditState()
                        assertTrue(timerAudit.ch6DisplayTimerActive, "SPX4 Ch6 display timer must remain active")
                        assertEquals(0L, timerAudit.ch6DisableCount, "stage2 + theme.bbp must never disable Ch6")
                        assertEquals(0L, timerAudit.blindMultiChannelDisableCount, "stage2 + theme.bbp must never issue blind timer disable")

                        // 6. Verify live composited 1024x768 OSD0-over-PIC1 frames captured during stage2 + theme.bbp
                        //    AND live PIC1 backdrop shining through theme.bbp's transparent viewfinder after hooked commit.
                        assertTrue(display.sequence >= 2L, "Expected >=2 LCDC1 scanout commits on display.main, got ${display.sequence}")
                        assertTrue(sawPic1BackdropThroughOsd0, "Expected live PIC1 YUV422 backdrop shining through transparent OSD0")
                        assertTrue(
                            maxDistinctColors >= 12 && maxNonBackdropPixels >= 50_000,
                            "Expected stage2 + theme.bbp HUD/toast composited over live PIC1 " +
                                "(maxColors=$maxDistinctColors, maxOverlayPixels=$maxNonBackdropPixels, backdrop=0x${expectedPic1BackdropRgb.toString(16)})",
                        )
                        assertTrue(display.copyArgb(pixels), "Expected live post-hook composited frame on display.main")
                        val postExitCenterRgb = pixels[384 * 1024 + 512] and 0x00ffffff
                        assertEquals(
                            expectedPic1BackdropRgb,
                            postExitCenterRgb,
                            "Expected live PIC1 backdrop preserved in transparent center viewfinder after hooked lcdevf_commit",
                        )
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    private fun buildInMemoryVfsBundle(baseAddr: Int, files: LinkedHashMap<String, ByteArray>): ByteArray {
        val headerSize = 4 + files.size * 16
        val nameBytes = files.keys.map { (it.uppercase() + "\u0000").toByteArray(StandardCharsets.US_ASCII) }
        var cursor = headerSize
        val nameOffsets = IntArray(files.size)
        for (i in nameBytes.indices) {
            nameOffsets[i] = cursor
            cursor = (cursor + nameBytes[i].size + 3) and 3.inv()
        }
        val dataList = files.values.toList()
        val dataOffsets = IntArray(files.size)
        for (i in dataList.indices) {
            cursor = (cursor + 15) and 15.inv()
            dataOffsets[i] = cursor
            cursor += dataList[i].size
        }
        val total = (cursor + 15) and 15.inv()
        val out = ByteArray(total)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, files.size)
        for (i in dataList.indices) {
            val off = 4 + i * 16
            bb.putInt(off + 0, baseAddr + nameOffsets[i])
            bb.putInt(off + 4, baseAddr + dataOffsets[i])
            bb.putInt(off + 8, dataList[i].size)
            bb.putInt(off + 12, 0)
            System.arraycopy(nameBytes[i], 0, out, nameOffsets[i], nameBytes[i].size)
            System.arraycopy(dataList[i], 0, out, dataOffsets[i], dataList[i].size)
        }
        return out
    }

    /**
     * Minimal self-contained pure-ARM32 assembler for the guest firmware-service harness
     * (zero external assembler dependency).
     */
    private class Arm32Asm(private val baseAddr: Int) {
        private val words = ArrayList<Int>(256)
        private val labels = HashMap<String, Int>()
        private val branchFixups = ArrayList<Triple<Int, Int, String>>() // (wordIdx, cond, label)
        private val litFixups = ArrayList<Triple<Int, Int, Int>>() // (wordIdx, rd, value)
        private val labelLitFixups = ArrayList<Triple<Int, Int, String>>() // (wordIdx, rd, label)

        fun pc(): Int = baseAddr + words.size * 4
        fun label(name: String) {
            labels[name] = pc()
        }

        fun emit(word: Int) {
            words.add(word)
        }

        fun nop() = emit(0xe1a0_0000.toInt())
        fun wfi() = emit(0xe320_f003.toInt())
        fun bx(rm: Int) = emit(0xe12f_ff10.toInt() or rm)
        fun blx(rm: Int) = emit(0xe12f_ff30.toInt() or rm)
        fun push(mask: Int) = emit(0xe92d_0000.toInt() or mask)
        fun pop(mask: Int) = emit(0xe8bd_0000.toInt() or mask)
        fun movImm(rd: Int, imm8: Int, cond: Int = 0xe) = emit((cond shl 28) or 0x03a0_0000 or (rd shl 12) or (imm8 and 0xff))
        fun movReg(rd: Int, rm: Int, cond: Int = 0xe) = emit((cond shl 28) or 0x01a0_0000 or (rd shl 12) or rm)
        fun mvnReg(rd: Int, rm: Int) = emit(0xe1e0_0000.toInt() or (rd shl 12) or rm)
        fun addImm(rd: Int, rn: Int, imm8: Int) = emit(0xe280_0000.toInt() or (rn shl 16) or (rd shl 12) or (imm8 and 0xff))
        fun addReg(rd: Int, rn: Int, rm: Int, lsl: Int = 0) =
            emit(0xe080_0000.toInt() or (rn shl 16) or (rd shl 12) or (lsl shl 7) or rm)
        fun subImm(rd: Int, rn: Int, imm8: Int, setFlags: Boolean = false, cond: Int = 0xe) =
            emit((cond shl 28) or (if (setFlags) 0x0250_0000 else 0x0240_0000) or (rn shl 16) or (rd shl 12) or (imm8 and 0xff))
        fun subReg(rd: Int, rn: Int, rm: Int) = emit(0xe040_0000.toInt() or (rn shl 16) or (rd shl 12) or rm)
        fun cmpImm(rn: Int, imm8: Int) = emit(0xe350_0000.toInt() or (rn shl 16) or (imm8 and 0xff))
        fun cmpReg(rn: Int, rm: Int) = emit(0xe150_0000.toInt() or (rn shl 16) or rm)
        fun eorReg(rd: Int, rn: Int, rm: Int, cond: Int = 0xe) =
            emit((cond shl 28) or 0x0020_0000 or (rn shl 16) or (rd shl 12) or rm)
        fun lsrs1(rd: Int, rm: Int) = emit(0xe1b0_00a0.toInt() or (rd shl 12) or rm)
        fun ldrOff(rd: Int, rn: Int, off: Int = 0) = emit(0xe590_0000.toInt() or (rn shl 16) or (rd shl 12) or off)
        fun ldrPost(rd: Int, rn: Int, off: Int) = emit(0xe490_0000.toInt() or (rn shl 16) or (rd shl 12) or off)
        fun strOff(rd: Int, rn: Int, off: Int = 0) = emit(0xe580_0000.toInt() or (rn shl 16) or (rd shl 12) or off)
        fun strPost(rd: Int, rn: Int, off: Int) = emit(0xe480_0000.toInt() or (rn shl 16) or (rd shl 12) or off)
        fun ldrbPost1(rd: Int, rn: Int) = emit(0xe4d0_0001.toInt() or (rn shl 16) or (rd shl 12))
        fun strbPost1(rd: Int, rn: Int) = emit(0xe4c0_0001.toInt() or (rn shl 16) or (rd shl 12))

        fun b(label: String, cond: Int = 0xe) {
            branchFixups.add(Triple(words.size, cond, label))
            emit(0)
        }

        fun ldrLit(rd: Int, value: Int) {
            litFixups.add(Triple(words.size, rd, value))
            emit(0)
        }

        fun ldrLabel(rd: Int, label: String) {
            labelLitFixups.add(Triple(words.size, rd, label))
            emit(0)
        }

        fun assemble(): ByteArray {
            val pool = LinkedHashMap<Int, Int>()
            for ((idx, rd, value) in litFixups) {
                val litAddr = pool.getOrPut(value) {
                    val addr = pc()
                    emit(value)
                    addr
                }
                val insnAddr = baseAddr + idx * 4
                val off = litAddr - (insnAddr + 8)
                require(off in 0..4095) { "Literal pool offset out of range: $off" }
                words[idx] = 0xe59f_0000.toInt() or (rd shl 12) or off
            }
            for ((idx, rd, labelName) in labelLitFixups) {
                val target = requireNotNull(labels[labelName]) { "Unknown label $labelName" }
                val litAddr = pool.getOrPut(target) {
                    val addr = pc()
                    emit(target)
                    addr
                }
                val insnAddr = baseAddr + idx * 4
                val off = litAddr - (insnAddr + 8)
                require(off in 0..4095) { "Label literal offset out of range: $off" }
                words[idx] = 0xe59f_0000.toInt() or (rd shl 12) or off
            }
            for ((idx, cond, labelName) in branchFixups) {
                val target = requireNotNull(labels[labelName]) { "Unknown branch label $labelName" }
                val insnAddr = baseAddr + idx * 4
                val rel = (target - (insnAddr + 8)) shr 2
                words[idx] = (cond shl 28) or 0x0a00_0000 or (rel and 0x00ff_ffff)
            }
            val out = ByteArray(words.size * 4)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            for (i in words.indices) bb.putInt(i * 4, words[i])
            return out
        }
    }

    private fun buildStage2FirmwareHarness(
        entryBase: Int,
        vfsBase: Int,
        handleTableBase: Int,
        doneMarkerAddr: Int,
    ): ByteArray {
        val a = Arm32Asm(entryBase)
        val condEq = 0x0
        val condNe = 0x1
        val condCs = 0x2
        val condHi = 0x8
        val condLs = 0x9

        // 0. Park secondary Cortex-A7 cores (MPIDR[1:0] != 0) cleanly in WFI, then set Core 0 SVC stack.
        a.emit(0xee10_0fb0.toInt()) // mrc p15, 0, r0, c0, c0, 5 (MPIDR)
        a.emit(0xe310_0003.toInt()) // tst r0, #3
        a.b("park_loop", condNe)
        a.ldrLit(13, 0x1080_0000)

        // 1. Entry point: fill PIC1 1024x768 UYVY live backdrop at 0x05000000 with studio sky-blue UYVY.
        a.ldrLit(0, 0x0500_0000)
        a.ldrLit(1, 0x966e_96a8.toInt()) // U=0xA8, Y0=0x96, V=0x6E, Y1=0x96 -> sky-blue RGB
        a.ldrLit(2, 1024 * 768 / 2)
        a.label("pic1_fill")
        a.strPost(1, 0, 4)
        a.subImm(2, 2, 1, setFlags = true)
        a.b("pic1_fill", condNe)

        // 2. Seed stock FW drive table (0x019D6F9C: [0, 1]) & UI screen ID (0x01CC6424: 0x0100 Live Shoot HUD).
        a.ldrLit(0, 0x019d_6f9c)
        a.movImm(1, 0)
        a.strOff(1, 0, 0)
        a.movImm(1, 1)
        a.strOff(1, 0, 4)
        a.ldrLit(0, 0x01cc_6424)
        a.ldrLit(1, 0x0000_0100)
        a.strOff(1, 0, 0)

        // Initialize next_fd = 1 at handleTableBase and clear completion marker.
        a.ldrLit(0, handleTableBase)
        a.movImm(1, 1)
        a.strOff(1, 0, 0)
        a.ldrLit(0, doneMarkerAddr)
        a.movImm(1, 0)
        a.strOff(1, 0, 0)
        a.strOff(1, 0, 4)

        // 3. Install pure-ARM32 firmware service trampolines at verified GFX100S 2.13 VAs:
        //    FW_TX_SLEEP_RAW_VA (0x0004DE7C) & FW_MMU_PROTECT_VA (0x012B93F4) -> mov r0, #0; bx lr
        a.ldrLit(1, 0xe3a0_0000.toInt()) // mov r0, #0
        a.ldrLit(2, 0xe12f_ff1e.toInt()) // bx lr
        for (va in intArrayOf(0x0004_de7c, 0x012b_93f4)) {
            a.ldrLit(0, va)
            a.strOff(1, 0, 0)
            a.strOff(2, 0, 4)
        }
        //    FW213_LCDEVF_COMMIT_FN_VA (0x0126C4E0) & STATE_FN_VA (0x01275464) -> nop; bx lr
        a.ldrLit(1, 0xe1a0_0000.toInt()) // nop (safe non-PC-relative prologue for install_ram_hook)
        for (va in intArrayOf(0x0126_c4e0, 0x0127_5464)) {
            a.ldrLit(0, va)
            a.strOff(1, 0, 0)
            a.strOff(2, 0, 4)
        }
        //    FW_FS_OPEN/READ/CLOSE & FW_CRC32 -> ldr pc, [pc, #-4]; .word <stub>
        a.ldrLit(1, 0xe51f_f004.toInt()) // ldr pc, [pc, #-4]
        for ((va, stubLabel) in listOf(
            0x012b_30b4 to "stub_fs_open",
            0x012b_3190 to "stub_fs_read",
            0x012b_3320 to "stub_fs_close",
            0x012b_c2b0 to "stub_crc32",
        )) {
            a.ldrLit(0, va)
            a.ldrLabel(2, stubLabel)
            a.strOff(1, 0, 0)
            a.strOff(2, 0, 4)
        }

        // 4. Call real compiled ARM32 stage2.bin @ 0x03BC0000 (which chains real theme.bbp @ 0x03B80000).
        a.ldrLit(12, 0x03bc_0000)
        a.blx(12)
        a.ldrLit(1, doneMarkerAddr)
        a.strOff(0, 1, 4) // store stage2_main() return code

        // 5. Invoke the NOW-HOOKED FW213_LCDEVF_COMMIT_FN_VA (0x0126C4E0) to prove theme.bbp's installed
        //    ARM32 RAM hook executes cleanly and commits a second live-view HUD frame over PIC1.
        a.ldrLit(12, 0x0126_c4e0)
        a.blx(12)
        a.ldrLit(0, 0xfffa_9600.toInt())
        a.movImm(1, 1)
        a.strOff(1, 0, 0) // LCDC1 commit

        // Write completion sentinel "DONF" and park cleanly in WFI.
        a.ldrLit(0, doneMarkerAddr)
        a.ldrLit(1, 0x444f_4e46) // "DONF"
        a.strOff(1, 0, 0)
        a.label("park_loop")
        a.wfi()
        a.b("park_loop")

        // --- stub_fs_open(r0=cb, r1=path, r2=mode) -> fd ---
        a.label("stub_fs_open")
        a.push(0x4ff0) // r4..r11, lr
        a.movReg(4, 0) // r4 = cb
        a.movReg(5, 1) // r5 = path
        a.ldrLit(6, vfsBase)
        a.ldrPost(7, 6, 4) // r7 = count, r6 = &entries[0]
        a.cmpImm(7, 0)
        a.b("open_not_found", condEq)

        a.label("open_entry_loop")
        a.ldrOff(8, 6, 0) // r8 = entry.nameAddr
        a.movReg(9, 5)    // p1 = path
        a.movReg(10, 8)   // p2 = entry.name
        a.label("open_cmp_loop")
        a.ldrbPost1(0, 9)
        a.subImm(2, 0, 'a'.code)
        a.cmpImm(2, 25)
        a.subImm(0, 0, 32, cond = condLs)
        a.ldrbPost1(1, 10)
        a.cmpReg(0, 1)
        a.b("open_next_entry", condNe)
        a.cmpImm(0, 0)
        a.b("open_cmp_loop", condNe)

        // Match! Allocate handle fd in [1..15]
        a.ldrLit(11, handleTableBase)
        a.ldrOff(8, 11, 0) // fd
        a.addImm(9, 8, 1)
        a.cmpImm(9, 16)
        a.movImm(9, 1, cond = condCs)
        a.strOff(9, 11, 0)
        a.addReg(10, 11, 8, lsl = 4) // slot = handleTableBase + fd * 16
        a.ldrOff(0, 6, 4) // dataAddr
        a.ldrOff(1, 6, 8) // dataLen
        a.movImm(2, 0)    // pos = 0
        a.strOff(0, 10, 0)
        a.strOff(1, 10, 4)
        a.strOff(2, 10, 8)
        a.movImm(0, 0)
        a.movImm(1, 0)
        a.blx(4)          // cb(0, 0)
        a.movReg(0, 8)    // return fd
        a.pop(0x8ff0)     // r4..r11, pc

        a.label("open_next_entry")
        a.addImm(6, 6, 16)
        a.subImm(7, 7, 1, setFlags = true)
        a.b("open_entry_loop", condNe)

        a.label("open_not_found")
        a.movImm(0, 1)
        a.movImm(1, 0)
        a.blx(4)          // cb(1, 0)
        a.movImm(0, 0)    // return 0 (not found)
        a.pop(0x8ff0)

        // --- stub_fs_read(r0=cb, r1=fd, r2=size, r3=dst) ---
        a.label("stub_fs_read")
        a.push(0x4ff0) // r4..r11, lr
        a.movReg(4, 0) // cb
        a.movReg(5, 1) // fd
        a.movReg(6, 2) // size
        a.movReg(7, 3) // dst
        a.ldrLit(11, handleTableBase)
        a.addReg(10, 11, 5, lsl = 4) // slot
        a.ldrOff(8, 10, 0) // dataAddr
        a.ldrOff(9, 10, 4) // dataLen
        a.ldrOff(0, 10, 8) // pos
        a.cmpReg(0, 9)
        a.b("read_eof", condCs)
        a.subReg(1, 9, 0)  // rem = dataLen - pos
        a.cmpReg(6, 1)
        a.movReg(6, 1, cond = condHi) // n = min(size, rem)
        a.addReg(2, 8, 0)  // src = dataAddr + pos
        a.addReg(0, 0, 6)  // pos += n
        a.strOff(0, 10, 8)
        a.movReg(3, 6)     // count = n
        a.cmpImm(3, 0)
        a.b("read_copy_done", condEq)
        a.label("read_copy_loop")
        a.ldrbPost1(0, 2)
        a.strbPost1(0, 7)
        a.subImm(3, 3, 1, setFlags = true)
        a.b("read_copy_loop", condNe)
        a.label("read_copy_done")
        a.movImm(0, 0)
        a.movReg(1, 6)     // arg1 = bytes read
        a.blx(4)           // cb(0, n)
        a.movImm(0, 0)
        a.pop(0x8ff0)
        a.label("read_eof")
        a.movImm(0, 0)
        a.movImm(1, 0)
        a.blx(4)           // cb(0, 0)
        a.movImm(0, 0)
        a.pop(0x8ff0)

        // --- stub_fs_close(r0=cb, r1=fd) ---
        a.label("stub_fs_close")
        a.push(0x4010) // r4, lr
        a.movReg(4, 0)
        a.movImm(0, 0)
        a.movImm(1, 0)
        a.blx(4)
        a.movImm(0, 0)
        a.pop(0x8010) // r4, pc

        // --- stub_crc32(r0=init, r1=buf, r2=len) -> IEEE 802.3 CRC32 ---
        a.label("stub_crc32")
        a.mvnReg(0, 0)
        a.ldrLit(12, 0xedb8_8320.toInt())
        a.cmpImm(2, 0)
        a.b("crc_done", condEq)
        a.label("crc_byte_loop")
        a.ldrbPost1(3, 1)
        a.eorReg(0, 0, 3)
        a.movImm(3, 8)
        a.label("crc_bit_loop")
        a.lsrs1(0, 0)
        a.eorReg(0, 0, 12, cond = condCs)
        a.subImm(3, 3, 1, setFlags = true)
        a.b("crc_bit_loop", condNe)
        a.subImm(2, 2, 1, setFlags = true)
        a.b("crc_byte_loop", condNe)
        a.label("crc_done")
        a.mvnReg(0, 0)
        a.bx(14)

        return a.assemble()
    }
}
