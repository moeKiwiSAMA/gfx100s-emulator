package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.MmioDevice
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaxedPeripheralFallbackTest {
    private val gfxFlashLayout = RelaxedPeripheralFallback.FlashLayout(
        apertureBase = 0xf0000000L,
        apertureSize = 0x08000000L,
        registersBase = 0xfffd8000L,
        registersSize = 0x1000L,
        dmaRegistersBase = 0xfffe0000L,
        dmaRegistersSize = 0x10000L,
    )

    @Test
    fun relaxedModeRetainsUnmodeledRegistersWhilePreservingModeledDevices() {
        var modeledValue = 0x11223344L
        val modeled = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long {
                require(offset == 0L && size == 4) { "known device only accepts offset 0 width 4" }
                return modeledValue
            }
            override fun write(offset: Long, size: Int, value: Long) {
                require(offset == 0L && size == 4) { "known device only accepts offset 0 width 4" }
                modeledValue = value
            }
        }
        val fallback = RelaxedPeripheralFallback()
        val router = MmioRouter(
            listOf(MmioWindow("modeled.reg", 0xfff40000L, 0x10L, modeled)),
            discovery = null,
            relaxedFallback = fallback,
        )

        // Modeled register works normally.
        assertEquals(0x11223344L, router.read(0xfff40000L, 4))
        router.write(0xfff40000L, 4, 0x55667788L)
        assertEquals(0x55667788L, router.read(0xfff40000L, 4))

        // Unmodeled peripheral address returns 0 on first read, then retains written bytes without halting.
        assertEquals(0L, router.read(0xfff9abcdL, 4))
        router.write(0xfff9abcdL, 4, 0xdeadbeefL)
        assertEquals(0xdeadbeefL, router.read(0xfff9abcdL, 4))
        assertEquals(0xefL, router.read(0xfff9abcdL, 1))
        assertEquals(0xbeefL, router.read(0xfff9abcdL, 2))

        // Refused access inside a modeled window also falls back in relaxed mode instead of halting.
        router.write(0xfff40004L, 4, 0x12345678L)
        assertEquals(0x12345678L, router.read(0xfff40004L, 4))

        val summary = fallback.summary()
        assertEquals(5L, summary["reads"])
        assertEquals(2L, summary["writes"])
        assertEquals(2L, summary["deviceFallbacks"])
        assertFalse(summary["capacityTruncated"] as Boolean)
    }

    @Test
    fun emitsWarnOncePerSourceAddressDirectionWithContextAndCountsRepeatHits() {
        var pc = 0x00123456L
        var tick = 42_000L
        var stage = FirmwareStage.STAGE_4_DISPLAY_INIT.label
        val warns = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        val fallback = RelaxedPeripheralFallback(
            context = object : RelaxedPeripheralFallback.Context {
                override fun pc(): Long = pc
                override fun tick(): Long = tick
                override fun stage(): String = stage
            },
            warnSink = warns::add,
            infoSink = infos::add,
            debugSink = debugs::add,
        )
        val router = MmioRouter(
            listOf(
                MmioWindow(
                    "modeled.reg",
                    0xfff40000L,
                    0x10L,
                    object : MmioDevice {
                        override fun read(offset: Long, size: Int): Long = error("unsupported offset $offset")
                        override fun write(offset: Long, size: Int, value: Long) = error("unsupported offset $offset")
                    },
                ),
            ),
            discovery = null,
            relaxedFallback = fallback,
        )

        // Repeat unmapped READs to same address -> only 1 WARN line
        repeat(5) {
            router.read(0xfff91000L, 4)
            tick += 10L
        }
        assertEquals(1, warns.size)
        assertTrue(warns[0].contains("[RELAXED/MMIO]"), warns[0])
        assertTrue(warns[0].contains("source=unmapped op=READ addr=0xfff91000 size=4 pc=0x00123456 tick=42000 stage=STAGE_4_DISPLAY_INIT"), warns[0])

        // Same address but WRITE -> distinct (source, address, write) key -> emits 2nd WARN with value
        pc = 0x00123460L
        stage = FirmwareStage.STAGE_5_FIRST_FRAME.label
        router.write(0xfff91000L, 4, 0xcafebabeL)
        router.write(0xfff91000L, 4, 0x11111111L)
        assertEquals(2, warns.size)
        assertTrue(warns[1].contains("source=unmapped op=WRITE addr=0xfff91000 size=4 val=0xcafebabe pc=0x00123460"), warns[1])

        // Device refusal -> distinct source=device + reason
        pc = 0x00200010L
        router.read(0xfff40004L, 4)
        router.read(0xfff40004L, 4)
        assertEquals(3, warns.size)
        assertTrue(warns[2].contains("source=device op=READ addr=0xfff40004 size=4 pc=0x00200010"), warns[2])
        assertTrue(warns[2].contains("reason=\"unsupported offset 4\""), warns[2])
        assertTrue(infos.isEmpty())

        // Compact summary reports exact hit counts sorted by descending hits
        val report = fallback.formatSummary()
        assertTrue(report.contains("[RELAXED/SUMMARY] mmioUnique=3 (reads=7, writes=2, deviceFallbacks=2"), report)
        assertTrue(report.contains("mmio[1] source=unmapped op=READ addr=0xfff91000 size=4 hits=5 firstPc=0x00123456"), report)
        assertTrue(report.contains("mmio[2] source=unmapped op=WRITE addr=0xfff91000 size=4 val=0xcafebabe hits=2 firstPc=0x00123460"), report)
        assertTrue(report.contains("mmio[3] source=device op=READ addr=0xfff40004 size=4 hits=2 firstPc=0x00200010"), report)

        // logSummaryOnClose emits concise 1-line totals at WARN (since mmioUnique > 0) and itemized table at DEBUG, exactly once
        fallback.logSummaryOnClose()
        fallback.logSummaryOnClose()
        assertTrue(infos.isEmpty())
        assertEquals(4, warns.size)
        assertEquals(fallback.formatSummaryHeader(), warns.last())
        assertEquals(1, debugs.size)
        assertTrue(debugs.single().startsWith("[RELAXED/SUMMARY/DETAILS]\n"), debugs.single())
        assertTrue(debugs.single().contains("mmio[1] source=unmapped op=READ addr=0xfff91000"), debugs.single())
        assertTrue(debugs.single().contains("mmio[3] source=device op=READ addr=0xfff40004"), debugs.single())
    }

    @Test
    fun logsUnmodeledFlashRefusalsAndModeledFlashOpsDeduplicatedBySector() {
        var pc = 0x00300000L
        var tick = 99_000L
        val warns = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        val fallback = RelaxedPeripheralFallback(
            context = object : RelaxedPeripheralFallback.Context {
                override fun pc(): Long = pc
                override fun tick(): Long = tick
                override fun stage(): String = FirmwareStage.STAGE_3_STORAGE_IO.label
            },
            flashLayout = gfxFlashLayout,
            warnSink = warns::add,
            infoSink = infos::add,
            debugSink = debugs::add,
        )

        var refuseNextFlashOp = false
        val flashAperture = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long {
                throw IllegalArgumentException("read outside DAT-provided flash prefix or selected finite tail")
            }
            override fun write(offset: Long, size: Int, value: Long) {
                error("read-only flash aperture store refused")
            }
        }
        val flashRegs = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = 0L
            override fun write(offset: Long, size: Int, value: Long) {
                if (refuseNextFlashOp && offset in setOf(0x60L, 0x70L, 0x90L)) {
                    throw IllegalArgumentException("flash offset outside writable or modeled range")
                }
            }
        }
        val dmaRegs = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = 0L
            override fun write(offset: Long, size: Int, value: Long) {
                if ((offset and 0xfffL) == 0x28L && (value and 0x10000001L) == 0x10000001L) {
                    throw IllegalArgumentException("DMA flash read outside DAT")
                }
            }
        }
        val router = MmioRouter(
            listOf(
                MmioWindow("soc-flash", gfxFlashLayout.apertureBase, gfxFlashLayout.apertureSize, flashAperture),
                MmioWindow("soc-flash", gfxFlashLayout.registersBase, gfxFlashLayout.registersSize, flashRegs),
                MmioWindow("soc-dma", gfxFlashLayout.dmaRegistersBase, gfxFlashLayout.dmaRegistersSize, dmaRegs),
            ),
            discovery = null,
            relaxedFallback = fallback,
        )

        // 1. Aperture unmodeled reads within same 4 KiB sector (0x03600000..0x0360000c) -> deduplicated to 1 WARN
        router.read(0xf3600000L, 4)
        router.read(0xf3600004L, 4)
        router.read(0xf3600008L, 4)
        assertEquals(1, warns.size)
        assertTrue(warns[0].contains("[RELAXED/FLASH] op=aperture-read-refused flashOffset=0x03600000 sector=0x03600000 size=4 pc=0x00300000"), warns[0])

        // Different sector -> logs a 2nd WARN
        router.read(0xf3601000L, 4)
        assertEquals(2, warns.size)
        assertTrue(warns[1].contains("flashOffset=0x03601000 sector=0x03601000"), warns[1])

        // 2. Modeled soc-flash mapped-read, erase, program -> DEBUG [RELAXED/FLASH] (never INFO console spam)
        router.write(0xfffd8068L, 4, 0xf3500000L)
        router.write(0xfffd806cL, 4, 0x1000L)
        router.write(0xfffd8060L, 4, 1L)
        // Repeat mapped-read in same sector -> deduplicated
        router.write(0xfffd8068L, 4, 0xf3500200L)
        router.write(0xfffd8060L, 4, 1L)

        router.write(0xfffd8094L, 4, 0xf3500000L)
        router.write(0xfffd8090L, 4, 0x210b0001L)

        router.write(0xfffd8078L, 4, 0xf3500100L)
        router.write(0xfffd807cL, 4, 256L)
        router.write(0xfffd8070L, 4, 1L)

        assertTrue(infos.isEmpty(), "Normal modeled flash ops must not spam INFO console: $infos")
        assertEquals(3, debugs.size)
        assertTrue(debugs[0].contains("[RELAXED/FLASH] op=mapped-read flashOffset=0x03500000 sector=0x03500000 size=4096"), debugs[0])
        assertTrue(debugs[1].contains("[RELAXED/FLASH] op=erase flashOffset=0x03500000 sector=0x03500000 size=4096"), debugs[1])
        assertTrue(debugs[2].contains("[RELAXED/FLASH] op=program flashOffset=0x03500100 sector=0x03500000 size=256"), debugs[2])

        // 3. Refused soc-flash erase & DMA flash read -> WARN [RELAXED/FLASH]
        refuseNextFlashOp = true
        router.write(0xfffd8094L, 4, 0xf3900000L)
        router.write(0xfffd8090L, 4, 0xdc0b0001L)

        router.write(0xfffe0010L, 4, 0xfffL) // count - 1 => 4096
        router.write(0xfffe0014L, 4, 0xf3a00000L) // src in flash aperture
        router.write(0xfffe0018L, 4, 0x02000000L) // dst in RAM
        router.write(0xfffe0028L, 4, 0x10201011L) // DMA start

        assertEquals(4, warns.size)
        assertTrue(warns[2].contains("[RELAXED/FLASH] op=erase-refused flashOffset=0x03900000 sector=0x03900000 size=65536"), warns[2])
        assertTrue(warns[3].contains("[RELAXED/FLASH] op=dma-read-refused flashOffset=0x03a00000 sector=0x03a00000 size=4096"), warns[3])

        val report = fallback.formatSummary()
        assertTrue(report.contains("mmioUnique=0"), report)
        assertTrue(report.contains("flashUnique=7 (refused=6, ops=4"), report)
        assertTrue(report.contains("op=aperture-read-refused flashOffset=0x03600000 sector=0x03600000 size=4 hits=3"), report)
        assertTrue(report.contains("op=mapped-read flashOffset=0x03500000 sector=0x03500000 size=4096 hits=2"), report)

        // With flashRefused > 0, shutdown summary header emits at WARN
        fallback.logSummaryOnClose()
        assertEquals(5, warns.size)
        assertEquals(fallback.formatSummaryHeader(), warns.last())
        assertTrue(infos.isEmpty())
    }

    @Test
    fun cleanRunEmitsNormalFlashOpsAndRelaxedSummaryAtDebugOnly() {
        val warns = mutableListOf<String>()
        val infos = mutableListOf<String>()
        val debugs = mutableListOf<String>()
        val fallback = RelaxedPeripheralFallback(
            flashLayout = gfxFlashLayout,
            warnSink = warns::add,
            infoSink = infos::add,
            debugSink = debugs::add,
        )
        val flashRegs = object : MmioDevice {
            override fun read(offset: Long, size: Int): Long = 0L
            override fun write(offset: Long, size: Int, value: Long) {}
        }
        val router = MmioRouter(
            listOf(MmioWindow("soc-flash", gfxFlashLayout.registersBase, gfxFlashLayout.registersSize, flashRegs)),
            discovery = null,
            relaxedFallback = fallback,
        )

        router.write(0xfffd8068L, 4, 0xf3500000L)
        router.write(0xfffd806cL, 4, 0x1000L)
        router.write(0xfffd8060L, 4, 1L)

        fallback.logSummaryOnClose()
        fallback.logSummaryOnClose()

        assertTrue(warns.isEmpty(), "Clean run must emit zero WARN lines: $warns")
        assertTrue(infos.isEmpty(), "Clean run must emit zero INFO lines: $infos")
        assertEquals(3, debugs.size, "Expected normal flash op + summary header + details at DEBUG: $debugs")
        assertTrue(debugs[0].contains("[RELAXED/FLASH] op=mapped-read"), debugs[0])
        assertEquals(fallback.formatSummaryHeader(), debugs[1])
        assertTrue(debugs[2].startsWith("[RELAXED/SUMMARY/DETAILS]\n"), debugs[2])
    }

    @Test
    fun strictAndDiscoveryWithoutFallbackStillRefuseUnmodeledAddresses() {
        val router = MmioRouter(emptyList(), discovery = null, relaxedFallback = null)
        assertFailsWith<IllegalStateException> { router.read(0xfff90000L, 4) }
        assertFailsWith<IllegalStateException> { router.write(0xfff90000L, 4, 1L) }
    }

    @Test
    fun subtractIntervalsExcludesRamAndMappedRangesCleanly() {
        val free = RelaxedPeripheralFallback.subtractIntervals(
            0xff000000L,
            0x1_0000_0000L,
            listOf(
                0xfff00000L to 0xfff08000L, // Boot ROM
                0xfff2e000L to 0xfff30000L, // SRAM
                0xfff40000L to 0xfff41000L, // Existing MMIO page
            ),
        )
        assertEquals(
            listOf(
                0xff000000L to 0x00f00000L,
                0xfff08000L to 0x00026000L,
                0xfff30000L to 0x00010000L,
                0xfff41000L to 0x000bf000L,
            ),
            free,
        )
        assertTrue(CatalogRuntime.mode(mapOf("executionMode" to "relaxed")) == "relaxed")
        assertTrue(CatalogRuntime.mode(mapOf("executionMode" to "discovery")) == "discovery")
        assertTrue(CatalogRuntime.mode(mapOf("executionMode" to "strict")) == "strict")
    }

    @Test
    fun writesAllRelaxedFeedbackTagsIntoProductionLatestLogAtInfoAndWarnLevels(@TempDir logDir: Path) {
        val console = java.io.ByteArrayOutputStream()
        val captured = java.io.PrintStream(console, true, Charsets.UTF_8)
        val logContext = FujiLoggerContext(
            propertyOverrides = mapOf(LogArchiver.PROP_LOG_DIR to logDir.toString()),
            consoleStream = captured,
        )
        try {

            val telemetry = FirmwareTelemetry().apply {
                markStage(FirmwareStage.STAGE_5_FIRST_FRAME)
                virtualTick = 500_000L
                publishedFrames = 3L
            }
            val userLogger = UserActionLogger(
                enabled = true,
                logger = logContext.getLogger(UserActionLogger::class.java),
            )
            val fallback = RelaxedPeripheralFallback(
                context = object : RelaxedPeripheralFallback.Context {
                    override fun pc(): Long = 0x00112230L
                    override fun tick(): Long = telemetry.virtualTick
                    override fun stage(): String = FirmwareStage.STAGE_5_FIRST_FRAME.label
                },
                flashLayout = gfxFlashLayout,
                logger = logContext.getLogger(RelaxedPeripheralFallback::class.java),
            )
            var now = 10_000_000_000L
            val watchdog = FirmwareWatchdog(
                telemetry = telemetry,
                ownerHeartbeatTimeoutNanos = TimeUnit.SECONDS.toNanos(5),
                clockNanos = { now },
                logger = logContext.getLogger(FirmwareWatchdog::class.java),
                summaryProvider = { fallback.formatSummary() },
            )

            userLogger.logComponentPatch("buttons", mapOf("menu" to true), telemetry)
            fallback.read(0xfff9abcdL, 4, "unmapped")
            fallback.read(0xf3600000L, 4, "device", "read outside DAT-provided flash prefix")
            fallback.observeModeledWrite(0xfffd8068L, 4, 0xf3500000L)
            fallback.observeModeledWrite(0xfffd806cL, 4, 0x1000L)
            fallback.observeModeledWrite(0xfffd8060L, 4, 1L)
            telemetry.markSessionStarted(now)
            watchdog.pollOnce(now)
            now += TimeUnit.SECONDS.toNanos(6)
            watchdog.pollOnce(now)
            fallback.logSummaryOnClose()
            logContext.stop()

            val latest = Files.readString(logDir.resolve("latest.log"), Charsets.UTF_8)
            val consoleOut = console.toString(Charsets.UTF_8)
            assertTrue(latest.contains("DEBUG") && latest.contains("[USER/ACTION] button=menu pressed=true"), latest)
            assertTrue(latest.contains("DEBUG") && latest.contains("[RELAXED/FLASH] op=mapped-read flashOffset=0x03500000"), latest)
            assertTrue(latest.contains("WARN ") && latest.contains("[RELAXED/MMIO] source=unmapped op=READ addr=0xfff9abcd"), latest)
            assertTrue(latest.contains("WARN ") && latest.contains("[RELAXED/FLASH] op=aperture-read-refused flashOffset=0x03600000"), latest)
            assertTrue(latest.contains("OWNER HEARTBEAT STALL"), latest)
            assertTrue(latest.contains("WARN ") && latest.contains("[RELAXED/SUMMARY] mmioUnique=1"), latest)
            assertTrue(latest.contains("DEBUG") && latest.contains("[RELAXED/SUMMARY/DETAILS]") && latest.contains("mmio[1]"), latest)
            assertFalse(latest.contains('\r'))

            // Console (INFO threshold) shows WARN summary + warnings, without DEBUG user actions, normal [RELAXED/FLASH] ops, or itemized dump table
            assertTrue(consoleOut.contains("WARN]: [RELAXED/SUMMARY] mmioUnique=1"), consoleOut)
            assertFalse(consoleOut.contains("[USER/ACTION]"), consoleOut)
            assertFalse(consoleOut.contains("[RELAXED/FLASH] op=mapped-read"), consoleOut)
            assertFalse(consoleOut.contains("[RELAXED/SUMMARY/DETAILS]"), consoleOut)
        } finally {
            logContext.stop()
            captured.close()
        }
    }

    @Test
    fun discoveryJournalOverflowAllowedOnlyInRelaxedMode() {
        val sha = "0".repeat(64)
        val ctx = object : DiscoveryContext {
            override fun tick(): Long = 1L
            override fun core(): Int = 0
            override fun pc(): Long = 0x100L
            override fun lr(): Long = 0x104L
            override fun virtualAddress(): Long = 0xfff4f01cL
        }
        val strictJournal = DiscoveryJournal("run", "v1", sha, sha, sha, capacity = 2, allowOverflow = false)
        assertEquals(0, strictJournal.begin(1L, 0xfff4f01cL, 4, false, 0L, 0, ctx))
        assertEquals(1, strictJournal.begin(2L, 0xfff4f01cL, 4, false, 0L, 0, ctx))
        assertFailsWith<IllegalStateException> {
            strictJournal.begin(3L, 0xfff4f01cL, 4, false, 0L, 0, ctx)
        }
        assertFailsWith<IllegalStateException> {
            strictJournal.begin(4L, 0xfff4f01cL, 4, false, 0L, 0, ctx)
        }

        val relaxedJournal = DiscoveryJournal("run", "v1", sha, sha, sha, capacity = 2, allowOverflow = true)
        assertEquals(0, relaxedJournal.begin(1L, 0xfff4f01cL, 4, false, 0L, 0, ctx))
        assertEquals(1, relaxedJournal.begin(2L, 0xfff4f01cL, 4, false, 0L, 0, ctx))
        val overflowSlot = relaxedJournal.begin(3L, 0xfff4f01cL, 4, false, 0L, 0, ctx)
        assertTrue(overflowSlot < 0)
        relaxedJournal.complete(overflowSlot, 0L, 0L, 0L)
        assertEquals(2, relaxedJournal.count)
        assertEquals(3L, relaxedJournal.attempts)
    }
}
