package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.*
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.cpu.ResetSchedule
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * End-to-end hardware-fidelity tests for GFX100S 2.13 stock boot handoff, `LCDC1` (`0xFFFA9000`)
 * `bit 12` active output gate + `0x20080001`/`0x1FC7F088` compositing, `soc-timer` (`0xFFF3C300`)
 * `Ch7`-only vs blind `0x00FF0000` `Ch6` display-timer preservation, ARMv7 MMU `XN` enforcement,
 * and booting the real `BANBANINJ` Linux takeover trampoline + Alpine `zImage` when sibling
 * `banban-injector` artifacts are present.
 */
class StockBootAndBanbanAlpineFidelityTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))

    private fun words(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it.toInt()) }
        }.array()

    private fun nativeLibrary(): Path {
        val candidate = System.getenv("FUJI_PREFETCH_ABORT_LIBRARY")
            ?: System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?: System.getenv("FUJI_NATIVE_LINUX_CACHE")
        return Path.of(checkNotNull(candidate) { "A Unicorn native library env var must be configured" })
    }

    private fun stageBase(home: Path, strictActiveGate: Boolean = true, fourCoreGic: Boolean = true) {
        val compDir = home.resolve("compoments")
        Files.createDirectories(compDir)
        Files.list(project.resolve("build/emulator/compoments")).use { paths ->
            paths.filter { it.toString().endsWith(".jar") }
                .forEach { Files.copy(it, compDir.resolve(it.fileName)) }
        }
        val catalog = AddressCatalog.load(project.resolve("catalog/compiled/catalog.json"))
        val meta = catalog.firmwareMetadata(mapOf("soc-vglib-init" to "lcdc-functional-experimental-v1"))
        Formats.atomicYaml(home.resolve("gfx100s-213.yaml"), meta.mapValues { requireNotNull(it.value) })
        if (fourCoreGic) {
            Formats.atomicYaml(
                compDir.resolve("soc-gic/soc-gic.yaml"),
                mapOf(
                    "profile" to "gicv2-catalog-experimental-v1",
                    "coreProfile" to DynarmicCluster.PROFILE,
                ),
            )
        }
        Formats.atomicYaml(
            compDir.resolve("system-memory/system-memory.yaml"),
            mapOf("profile" to "linux-contiguous-256m-experimental-v1"),
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
                "scanoutFrameLimit" to 4L,
                "continuousScanout" to true,
                "strictActiveOutputGate" to strictActiveGate,
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
            mapOf("profile" to "pl011-tx-ready-experimental-v1", "initialFill" to 0L, "logTx" to true),
        )
    }

    @Test
    fun guestSessionEnforcesLcdc1Bit12GrayBlankVsLiveOpaqueAndTimerCh7OnlyPreservesCh6() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("stock-boot-fidelity-")
        try {
            stageBase(home)
            val entryAddr = 0x0001_c000L
            val fbPa = 0x1400_0000L
            // ARM32 guest @ 0x0001C000:
            //   1. Program LCDC1 geometry + fb_pa = 0x14000000 and draw opaque Yellow pixel (0xFFFF00FF) at (0,0)
            //   2. Enable timer Ch6 + Ch7 (0x00C000C0) then quiesce ONLY Ch7 (0x00800000) -> Ch6 stays active!
            //   3. First commit with BUGGY bare 0x00010000 (bit 12 == 0!) + 0x1FC7F088 -> emulator MUST blank to gray!
            //   4. Pause at PC = entry + 0x98 (check gray blank), OR execute full sequence and verify activeGateBlankCount == 1
            //      followed by FIXED RMW 0x00011000 (bit 12 == 1!) + 0x1FC7F088 -> emulator renders live Yellow pixel!
            val code = words(
                // r0 = LCDC1 (0xFFFA9000), r5 = fb_pa (0x14000000)
                0xe309_0000L, // 0x00: MOVW r0, #0x9000
                0xe34f_0ffaL, // 0x04: MOVT r0, #0xFFFA
                0xe300_5000L, // 0x08: MOVW r5, #0x0000
                0xe341_5400L, // 0x0C: MOVT r5, #0x1400 (0x14000000)
                // Write opaque Yellow (LE 0xFFFF00FF) to [fb_pa] (0..15 px so center-nearest 640x480 hits it too)
                0xe300_10ffL, // 0x10: MOVW r1, #0x00FF
                0xe34f_1fffL, // 0x14: MOVT r1, #0xFFFF
                0xe585_1000L, // 0x18: STR r1, [r5, #0]
                0xe585_1004L, // 0x1C: STR r1, [r5, #4]
                0xe585_1008L, // 0x20: STR r1, [r5, #8]
                // Program LCDC1 OSD0 geometry & fb_pa
                0xe3a0_1000L, // 0x24: MOV r1, #0
                0xe580_1040L, // 0x28: STR r1, [r0, #0x40]
                0xe580_5044L, // 0x2C: STR r5, [r0, #0x44]
                0xe300_1400L, // 0x30: MOVW r1, #0x0400
                0xe340_1001L, // 0x34: MOVT r1, #0x0001
                0xe580_1050L, // 0x38: STR r1, [r0, #0x50]
                0xe340_1300L, // 0x3C: MOVT r1, #0x0300 (0x03000400)
                0xe580_1054L, // 0x40: STR r1, [r0, #0x54]
                0xe580_105cL, // 0x44: STR r1, [r0, #0x5C]
                0xe3a0_1000L, // 0x48: MOV r1, #0
                0xe580_1058L, // 0x4C: STR r1, [r0, #0x58]
                0xe301_1000L, // 0x50: MOVW r1, #0x1000
                0xe341_1000L, // 0x54: MOVT r1, #0x1000
                0xe580_1060L, // 0x58: STR r1, [r0, #0x60]
                // Set opaque 0x030 = 0x1FC7F088
                0xe30f_1088L, // 0x5C: MOVW r1, #0xF088
                0xe341_1fc7L, // 0x60: MOVT r1, #0x1FC7
                0xe580_1030L, // 0x64: STR r1, [r0, #0x30]
                // Commit #1 (BUGGY): 0x008 = 0x00010000 (missing bit 12!) -> gray blank!
                0xe3a0_1801L, // 0x68: MOV r1, #0x00010000
                0xe580_1008L, // 0x6C: STR r1, [r0, #0x008]
                0xe3a0_2001L, // 0x70: MOV r2, #1
                0xe580_2600L, // 0x74: STR r2, [r0, #0x600]
                // Commit #2 (FIXED): RMW 0x008 |= 0x00001000 (0x00011000) -> live Yellow pixel!
                0xe590_1008L, // 0x78: LDR r1, [r0, #0x008]
                0xe381_1a01L, // 0x7C: ORR r1, r1, #0x00001000
                0xe580_1008L, // 0x80: STR r1, [r0, #0x008]
                0xe580_2600L, // 0x84: STR r2, [r0, #0x600]
                // Timer 0xFFF3C300: load Ch6/Ch7 counters, enable Ch6+Ch7 (0x00C000C0), then safe Ch7-only disable (0x00800000)
                0xe30c_3300L, // 0x88: MOVW r3, #0xC300
                0xe34f_3ff3L, // 0x8C: MOVT r3, #0xFFF3
                0xe3a0_4e10L, // 0x90: MOV r4, #0x100
                0xe583_4040L, // 0x94: STR r4, [r3, #0x40] (Ch6 value)
                0xe583_4044L, // 0x98: STR r4, [r3, #0x44] (Ch6 reload)
                0xe583_4048L, // 0x9C: STR r4, [r3, #0x48] (Ch7 value)
                0xe583_404cL, // 0xA0: STR r4, [r3, #0x4C] (Ch7 reload)
                0xe300_40c0L, // 0xA4: MOVW r4, #0x00C0
                0xe340_40c0L, // 0xA8: MOVT r4, #0x00C0
                0xe583_4000L, // 0xAC: STR r4, [r3]
                0xe3a0_4502L, // 0xB0: MOV r4, #0x00800000 (Ch7-only!)
                0xe583_4000L, // 0xB4: STR r4, [r3]
                0xeaff_fffeL, // 0xB8: B .
            )
            Files.write(home.resolve("guest-fidelity.bin"), code)

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 80,
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
                    "file" to "guest-fidelity.bin",
                    "loadAddress" to "0x0001c000",
                    "entry" to "0x0001c000",
                ),
            )
            validateLaunchConfiguration(config)

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Execute to budget
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "Unexpected frontier: $st")
                        val regs = checkNotNull(session.registerSnapshot())
                        assertEquals(entryAddr + 0xb8L, regs["pc"], "Guest must reach halt loop")

                        // LCDC1 observability: commit #1 caught missing bit 12 (activeGateBlankCount == 1),
                        // commit #2 restored bit 12 (outputActive == true, pixel (0,0) == Yellow ARGB 0xFFFFFF00)
                        val layers = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-layers", LcdcLayerObservabilityPort::class.java)).lcdc1LayerState()
                        assertEquals(0x0001_1000L, layers.reg008)
                        assertEquals(0x1fc7_f088L, layers.reg030)
                        assertTrue(layers.activeOutputGate)
                        assertTrue(layers.outputActive)
                        assertNull(layers.blankReason)
                        assertEquals(1L, layers.activeGateBlankCount, "Buggy bare 0x00010000 commit must have been caught")

                        val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                        assertEquals(2L, display.sequence)
                        val pixels = IntArray(display.width * display.height)
                        assertTrue(display.copyArgb(pixels))
                        assertEquals(0xffffff00.toInt(), pixels[0], "Live opaque Yellow pixel at (0,0) after bit 12 restored")

                        // Timer observability: safe 0x00800000 disabled ONLY Ch7 while preserving Ch6 display timer!
                        val timerAudit = checkNotNull(host.ports.find("soc-timer.channels", TimerChannelObservabilityPort::class.java)).channelAuditState()
                        assertTrue(timerAudit.ch6DisplayTimerActive, "Ch6 display/backlight timer must stay active")
                        assertFalse(timerAudit.ch7OsTickTimerActive, "Ch7 ThreadX tick timer must be disabled")
                        assertEquals(0x80, timerAudit.lastControlWriteMask)
                        assertEquals(0L, timerAudit.ch6DisableCount)
                        assertEquals(0L, timerAudit.blindMultiChannelDisableCount)
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    private fun findBanbanInjectorDir(): Path? {
        val env = System.getenv("FUJI_BANBAN_INJECTOR_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val candidates = listOfNotNull(
            env,
            project.parent?.resolve("banban-injector"),
            project.parent?.parent?.resolve("banban-injector"),
        )
        return candidates.firstOrNull { dir ->
            Files.isRegularFile(dir.resolve("stage2/build/BANBANINJ")) &&
                Files.isRegularFile(dir.resolve("alpine/build/out/zImage")) &&
                Files.isRegularFile(dir.resolve("alpine/build/out/gfx100s.dtb"))
        }
    }

    private fun extractTrampolineFromBanbanInj(banbanInj: ByteArray): ByteArray {
        val hdr = ByteBuffer.wrap(banbanInj).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x4a4e_4242, hdr.getInt(0), "BANBANINJ magic mismatch")
        val headerSize = hdr.getShort(6).toInt() and 0xffff
        val payload = banbanInj.copyOfRange(headerSize, banbanInj.size)
        // Exact ARM32 prologue of linux_takeover_trampoline_start (boot_linux.S):
        //   mov r4, r0; cpsid if, #0x13; [cpsie a;] movw sp, #0xFC00; movt sp, #0x0001
        val sigs = listOf(
            words(0xe1a0_4000L, 0xf10e_00d3L, 0xf108_0100L, 0xe30f_dc00L, 0xe340_d001L),
            words(0xe1a0_4000L, 0xf10e_00d3L, 0xe30f_dc00L, 0xe340_d001L),
        )
        var found = -1
        for (sig in sigs) {
            outer@ for (i in 0..(payload.size - sig.size) step 4) {
                for (j in sig.indices) {
                    if (payload[i + j] != sig[j]) continue@outer
                }
                found = i
                break
            }
            if (found >= 0) break
        }
        assertTrue(found >= 0, "Could not locate linux_takeover_trampoline_start inside BANBANINJ")
        val len = minOf(0x400, payload.size - found)
        return payload.copyOfRange(found, found + len)
    }

    @Test
    fun realBanbanInjTrampolineAndAlpineZImageBootCleanlyOnStrictLcdc1Bit12AndMmuXnModel() {
        val injectorDir = findBanbanInjectorDir()
        assumeTrue(injectorDir != null, "Sibling banban-injector build artifacts not present in this environment")
        val dir = checkNotNull(injectorDir)
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("banban-alpine-strict-")
        try {
            stageBase(home, strictActiveGate = true)
            val banbanInj = Files.readAllBytes(dir.resolve("stage2/build/BANBANINJ"))
            val zImagePath = dir.resolve("alpine/build/out/zImage")
            val dtbPath = dir.resolve("alpine/build/out/gfx100s.dtb")
            val trampoline = extractTrampolineFromBanbanInj(banbanInj)
            val zImageSize = Files.size(zImagePath)
            val dtbSize = Files.size(dtbPath)

            // Assemble low-DDR image @ 0x0001C000..0x0001F400:
            //   0x0001C000: real BANBANINJ linux_takeover_trampoline (exact compiled bytes!)
            //   0x0001F200: banban_linux_boot_params_t (44 bytes: in-place zImage @ 0x02800000, DTB @ 0x03000000, fb @ 0x059ED000)
            //   0x0001F280 / 0x0001F2C0 / 0x0001F300: parked[1..3] = 1 (CPU1..3 already parked)
            val lowDdr = ByteArray(0x3400)
            trampoline.copyInto(lowDdr, 0)
            val fbPa = 0x1400_0000L
            val params = ByteBuffer.wrap(lowDdr, 0x3200, 44).order(ByteOrder.LITTLE_ENDIAN)
            params.putInt(0x0280_0000) // zimage_src
            params.putInt(0x0280_0000) // zimage_dst
            params.putInt(zImageSize.toInt())
            params.putInt(0x0300_0000) // dtb_src
            params.putInt(0x0300_0000) // dtb_dst
            params.putInt(dtbSize.toInt())
            params.putInt(0)           // initrd_src
            params.putInt(0)           // initrd_dst
            params.putInt(0)           // initrd_size
            params.putInt(fbPa.toInt())
            params.putInt(-1)          // mach_type = 0xFFFFFFFF (DT)
            // Mark CPU1..3 parked flags = 1 at 0x0001F280, 0x0001F2C0, 0x0001F300
            ByteBuffer.wrap(lowDdr).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x3280, 1)
                putInt(0x32c0, 1)
                putInt(0x3300, 1)
            }
            Files.write(home.resolve("trampoline-lowddr.bin"), lowDdr)
            Files.copy(zImagePath, home.resolve("zImage"))
            Files.copy(dtbPath, home.resolve("gfx100s.dtb"))

            // First place zImage at 0x02800000 via initrdFile slot (pure physical copy!), DTB at 0x03000000,
            // and boot the real BANBANINJ trampoline at 0x0001C000 with r0 = 0x0001F200!
            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 1_200_000,
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
                    "file" to "trampoline-lowddr.bin",
                    "loadAddress" to "0x0001c000",
                    "entry" to "0x0001c000",
                    "r0" to "0x0001f200",
                    "dtbFile" to "gfx100s.dtb",
                    "dtbAddress" to "0x03000000",
                    "initrdFile" to "zImage",
                    "initrdAddress" to "0x02800000",
                ),
            )
            validateLaunchConfiguration(config)

            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    // Seed stock RTOS live state before BANBANINJ takeover:
                    //   LCDC1 in live view (0x008 = 0x00011010, 0x030 = 0x20080001)
                    //   GIC IRQ 63 (Ch6) & IRQ 64 (Ch7) configured edge-triggered (matching kernel.bin)
                    //   soc-timer with BOTH Ch6 (display) and Ch7 (ThreadX tick) enabled!
                    val gicd = checkNotNull(host.ports.find("soc-gic.distributor", MmioDevice::class.java))
                    gicd.write(0xc0cL, 4, 0x8000_0000L) // GICD_ICFGR3: IRQ 63 edge-triggered
                    gicd.write(0xc10L, 4, 0x0000_0002L) // GICD_ICFGR4: IRQ 64 edge-triggered
                    val lcdc1 = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java))
                    lcdc1.write(0x008, 4, 0x0001_1010L)
                    lcdc1.write(0x030, 4, 0x2008_0001L)
                    val timer = checkNotNull(host.ports.find("soc-timer", MmioDevice::class.java))
                    timer.write(0x40, 4, 500_000L)
                    timer.write(0x44, 4, 500_000L)
                    timer.write(0x48, 4, 500_000L)
                    timer.write(0x4c, 4, 500_000L)
                    timer.write(0x00, 4, 0x00c0_00c0L)

                    FirmwareSession(host, home, config).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Execute trampoline + early zImage decompression!
                        }
                        val st = session.state()
                        assertNull(st["frontier"], "Real BANBANINJ + Alpine zImage hit unexpected frontier: $st")

                        // Verify LCDC1 preserved bit 12 active output gate and never triggered a gray blank!
                        val layers = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-layers", LcdcLayerObservabilityPort::class.java)).lcdc1LayerState()
                        assertEquals(0x0001_1000L, layers.reg008, "boot_linux.S RMW must preserve bit 12 + OSD0 while clearing PIC0/PIC1")
                        assertEquals(0x1fc7_f088L, layers.reg030, "boot_linux.S must switch OSD0 to opaque 0x1FC7F088")
                        assertTrue(layers.activeOutputGate)
                        assertTrue(layers.outputActive)
                        assertNull(layers.blankReason)
                        assertEquals(0L, layers.activeGateBlankCount, "Updated BANBANINJ must have ZERO bit-12 gray blanks")

                        // Verify visual breadcrumbs Square 0 (Yellow), Square 1 (Cyan), Square 2 (Green) on LCDC1!
                        val fbWords = ByteBuffer.wrap(session.memory(fbPa, 4096 * 32)).order(ByteOrder.LITTLE_ENDIAN)
                        fun fbPixel(x: Int, y: Int): Int = fbWords.getInt((y * 1024 + x) * 4)
                        assertEquals(0xffff_00ff.toInt(), fbPixel(16, 16), "Breadcrumb Square 0 (Yellow: SMP parked)")
                        assertEquals(0x00ff_ffff, fbPixel(48, 16), "Breadcrumb Square 1 (Cyan: DCCISW + MMU off)")
                        assertEquals(0x00ff_00ff, fbPixel(80, 16), "Breadcrumb Square 2 (Green: jumping to Alpine zImage)")

                        val display = checkNotNull(host.ports.find("display.main", DisplayPort::class.java))
                        assertTrue(display.sequence >= 4L, "Expected >= 4 LCDC1 commits (clear + 3 breadcrumbs)")
                        val pixels = IntArray(display.width * display.height)
                        assertTrue(display.copyArgb(pixels))
                        // Top-left (0,0) on 640x480 scaled DisplayPort maps inside Square 0 (Yellow ARGB 0xFFFFFF00)
                        assertEquals(0xffff_ff00.toInt(), pixels[0], "DisplayPort must show live Yellow breadcrumb (not gray blank)")

                        // Verify soc-timer Ch7-only quiesce preserved Ch6 display/backlight timer!
                        val timerAudit = checkNotNull(host.ports.find("soc-timer.channels", TimerChannelObservabilityPort::class.java)).channelAuditState()
                        assertTrue(timerAudit.ch6DisplayTimerActive, "BANBANINJ Ch7-only 0x00800000 must preserve Ch6 display timer")
                        assertFalse(timerAudit.ch7OsTickTimerActive, "BANBANINJ must disable Ch7 ThreadX tick")
                        assertEquals(0x80, timerAudit.lastControlWriteMask)
                        assertEquals(0L, timerAudit.ch6DisableCount)
                        assertEquals(0L, timerAudit.blindMultiChannelDisableCount)

                        // Verify CPU successfully entered Alpine zImage region (>= 0x02800000)!
                        val regs = checkNotNull(session.registerSnapshot())
                        val pc = regs["pc"] as Long
                        assertTrue(pc >= 0x0200_0000L, "CPU must have branched into Alpine zImage (pc=0x${pc.toString(16)})")
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun coldBootLcdc1ClockGateCatchesPreLcdevf0xFFFA9044ReadAndRecoversAfterModeWord0xFFF49700Ungate() {
        val lib = nativeLibrary()
        val home = Files.createTempDirectory("lcdc1-coldboot-gate-")
        try {
            stageBase(home, strictActiveGate = true, fourCoreGic = false)
            val vglibDir = home.resolve("compoments/soc-vglib-init")
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
                    "lcdc1ClockGated" to true,
                    "unclockedAccessPolicy" to "abort",
                ),
            )

            // 1. Buggy cold-boot guest (like stage0 reading 0xFFFA9044 BEFORE lcdevf 0x13DC ungates LCDC1!):
            val buggyCode = words(
                0xe309_0044L, // MOVW r0, #0x9044
                0xe34f_0ffaL, // MOVT r0, #0xFFFA (0xFFFA9044)
                0xe590_1000L, // LDR r1, [r0] -> MUST ABORT while LCDC1 clock is gated!
                0xeaff_fffeL, // B .
            )
            Files.write(home.resolve("coldboot-buggy.bin"), buggyCode)
            val buggyConfig = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to "gfx100s-213.yaml",
                "nativeLibrary" to lib.toAbsolutePath().toString(),
                "instructionBudget" to 32,
                "enabledDevices" to listOf("soc-gic", "soc-vglib-init"),
                "customRawImage" to mapOf(
                    "file" to "coldboot-buggy.bin",
                    "loadAddress" to "0x0001c000",
                    "entry" to "0x0001c000",
                ),
            )
            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, buggyConfig).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Must halt at frontier on unclocked 0xFFFA9044 read!
                        }
                        val st = session.state()
                        val frontier = st["frontier"]
                        val status = st["status"]?.toString().orEmpty()
                        assertNotNull(frontier, "Expected cold-boot LCDC1 clock-gate frontier, got state=$st")
                        assertTrue(
                            status.contains("0xfffa9000", ignoreCase = true) || status.contains("AXI External Data Abort"),
                            "Expected LCDC1 cold-boot clock-gate abort status, got status=$status frontier=$frontier",
                        )
                        val frontierMap = frontier as Map<*, *>
                        assertEquals(0xfffa_9044L, (frontierMap["addressPhysical"] as Number).toLong())
                        val lcdc1Power = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-power", LcdcPowerGatePort::class.java)).lcdc0PowerState()
                        assertFalse(lcdc1Power.clocked)
                        assertEquals(1L, lcdc1Power.unclockedAccessCount)
                        assertEquals(0x044L, lcdc1Power.lastUnclockedOffset)
                    }
                }.join()
            }

            // 2. Clean lcdevf 0x13DC bring-up: read/mask/write 0xFFF49700 (S4:0x012556BC) ungates LCDC1,
            //    after which reading 0xFFFA9044 succeeds cleanly with zero unclocked accesses!
            val cleanCode = words(
                0xe309_2700L, // MOVW r2, #0x9700
                0xe34f_2ff4L, // MOVT r2, #0xFFF4 (0xFFF49700 mode-word)
                0xe592_3000L, // LDR r3, [r2]
                0xe3c3_3c03L, // BIC r3, r3, #0x300 (preserveMask = 0xFFFFFCFF)
                0xe582_3000L, // STR r3, [r2] -> ungates LCDC1!
                0xe309_0044L, // MOVW r0, #0x9044
                0xe34f_0ffaL, // MOVT r0, #0xFFFA (0xFFFA9044)
                0xe590_1000L, // LDR r1, [r0] -> succeeds!
                0xeaff_fffeL, // B .
            )
            Files.write(home.resolve("coldboot-clean.bin"), cleanCode)
            val cleanConfig = buggyConfig + (
                "customRawImage" to mapOf(
                    "file" to "coldboot-clean.bin",
                    "loadAddress" to "0x0001c000",
                    "entry" to "0x0001c000",
                )
            )
            IntegrationTest().host(home) { owner, host ->
                owner.submit {
                    FirmwareSession(host, home, cleanConfig).use { session ->
                        session.initialize()
                        while (session.step()) {
                            // Runs cleanly to halt loop
                        }
                        assertNull(session.state()["frontier"])
                        val lcdc1Power = checkNotNull(host.ports.find("soc-vglib-init.lcdc1-power", LcdcPowerGatePort::class.java)).lcdc0PowerState()
                        assertTrue(lcdc1Power.clocked)
                        assertEquals(0L, lcdc1Power.unclockedAccessCount)
                    }
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
