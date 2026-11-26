package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8 Full Camera Mode Switching, Capture/Recording & Deep Sub-Menu E2E suite.
 *
 * Covers the complete real GFX100S 2.13 user journey on the staged Linux release bundle:
 *  - Phase A: Cold boot -> full OOBE (Language CJK page -> Area -> Date/Time edit -> Start App) -> Live STILL HUD (0x0100).
 *  - Phase B: STILL Photo Capture (shutterHalf S1 -> shutterFull S2 -> JPEG encode & Slot 1 DCIM check).
 *  - Phase C: STILL Main Menu (0x0400) & deep sub-menu crawl across all 6 tabs (I.Q., AF/MF, Shooting, Flash, Movie,
 *             and all 7 Setup/Wrench sub-categories: User, Sound, Screen, Button/Dial, Power, Save Data, Connection).
 *  - Phase D: Playback (PLAY 0x0300) -> browse -> Playback Menu (PLAY -> MENU) & deep sub-menu crawl -> return to STILL (0x0100).
 *  - Phase E: STILL -> MOVIE lever switch (movieMode=true) -> MOVIE Menu & sub-menu crawl -> Movie Recording start/stop ->
 *             PLAY from MOVIE -> return to MOVIE & STILL (movieMode=false -> 0x0100).
 *
 * Modes:
 *  - Deterministic unit/config tests run unconditionally in `quickGate`.
 *  - Live E2E runs when `FUJI_RUN_M8_FULL_E2E=1`.
 *  - Exploratory discovery mode (`FUJI_M8_EXPLORE=1`) logs incremental `[RELAXED/MMIO]` hits per phase/sub-menu and
 *    continues across non-fatal assertions so one pass captures every unmodeled peripheral across the whole firmware.
 *  - Optional `FUJI_M8_DUMP_DIR=/path` exports PNG screenshots and `crawl-report.md`.
 */
class FullCameraModeAndSubmenuE2eTest {

    data class RelaxedMmioHit(
        val source: String,
        val op: String,
        val address: String,
        val size: Int,
        val value: String?,
        val hits: Long,
        val firstPc: String,
        val stage: String,
        val reason: String?,
    ) {
        val key: String get() = "$source:$op:$address"
    }

    data class RelaxedMmioDelta(
        val hit: RelaxedMmioHit,
        val deltaHits: Long,
        val isNew: Boolean,
    )

    data class PhaseLog(
        val phase: String,
        val scrHex: String,
        val bDsc: Int,
        val errnoHex: String,
        val status: String,
        val frontier: String?,
        val seq: Long,
        val diffPixels: Int,
        val decodeCount: Long,
        val encodeCount: Long,
        val sdDcimFiles: List<String>,
        val newMmio: List<RelaxedMmioDelta>,
        val note: String = "",
    )

    companion object {
        private val MMIO_LINE_REGEX = Regex(
            """^\s*mmio\[\d+\]\s+source=(\S+)\s+op=(READ|WRITE)\s+addr=(0x[0-9a-fA-F]+)\s+size=(\d+)(?:\s+val=(0x[0-9a-fA-F]+))?\s+hits=(\d+)\s+firstPc=(0x[0-9a-fA-F]+)\s+stage=(\S+)(?:\s+reason="([^"]*)")?.*$""",
        )

        fun parseRelaxedMmioEntries(summary: String?): Map<String, RelaxedMmioHit> {
            if (summary.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, RelaxedMmioHit>()
            for (line in summary.lineSequence()) {
                val m = MMIO_LINE_REGEX.matchEntire(line) ?: continue
                val hit = RelaxedMmioHit(
                    source = m.groupValues[1],
                    op = m.groupValues[2],
                    address = m.groupValues[3].lowercase(),
                    size = m.groupValues[4].toInt(),
                    value = m.groupValues[5].takeIf { it.isNotEmpty() }?.lowercase(),
                    hits = m.groupValues[6].toLong(),
                    firstPc = m.groupValues[7].lowercase(),
                    stage = m.groupValues[8],
                    reason = m.groupValues[9].takeIf { it.isNotEmpty() },
                )
                out[hit.key] = hit
            }
            return out
        }

        fun diffRelaxedMmio(
            before: Map<String, RelaxedMmioHit>,
            after: Map<String, RelaxedMmioHit>,
        ): List<RelaxedMmioDelta> = buildList {
            for ((key, now) in after) {
                val prev = before[key]
                val delta = now.hits - (prev?.hits ?: 0L)
                if (delta > 0L) {
                    add(RelaxedMmioDelta(hit = now, deltaHits = delta, isNew = prev == null))
                }
            }
        }.sortedWith(compareByDescending<RelaxedMmioDelta> { it.isNew }.thenByDescending { it.deltaHits })
    }

    @Test
    fun parsesAndDiffsRelaxedMmioSummaryDeterministically() {
        val beforeText = """
            [RELAXED/SUMMARY] mmioUnique=2 (reads=0, writes=5, deviceFallbacks=5, discoveryFallbacks=0, logTruncated=false) flashUnique=10 (refused=0, ops=10, logTruncated=false)
              mmio[1] source=device op=WRITE addr=0xfffa9600 size=4 val=0x10000005 hits=4 firstPc=0x0005af98 stage=STAGE_6_INTERACTIVE_UI reason="transaction outside declared RAM/permissions"
              mmio[2] source=device op=WRITE addr=0xfff49530 size=4 val=0x10000704 hits=1 firstPc=0x0005af98 stage=STAGE_6_INTERACTIVE_UI reason="FFF49530 setup pair sequence exhausted at position=3"
        """.trimIndent()
        val afterText = """
            [RELAXED/SUMMARY] mmioUnique=3 (reads=2, writes=7, deviceFallbacks=7, discoveryFallbacks=2, logTruncated=false) flashUnique=10 (refused=0, ops=10, logTruncated=false)
              mmio[1] source=device op=WRITE addr=0xfffa9600 size=4 val=0x10000005 hits=6 firstPc=0x0005af98 stage=STAGE_6_INTERACTIVE_UI reason="transaction outside declared RAM/permissions"
              mmio[2] source=discovery op=READ addr=0xfff91234 size=4 hits=2 firstPc=0x01234568 stage=STAGE_6_INTERACTIVE_UI reason="unmapped peripheral"
              mmio[3] source=device op=WRITE addr=0xfff49530 size=4 val=0x10000704 hits=1 firstPc=0x0005af98 stage=STAGE_6_INTERACTIVE_UI reason="FFF49530 setup pair sequence exhausted at position=3"
        """.trimIndent()

        val before = parseRelaxedMmioEntries(beforeText)
        val after = parseRelaxedMmioEntries(afterText)
        assertEquals(2, before.size)
        assertEquals(3, after.size)

        val deltas = diffRelaxedMmio(before, after)
        assertEquals(2, deltas.size)
        assertTrue(deltas[0].isNew)
        assertEquals("0xfff91234", deltas[0].hit.address)
        assertEquals("READ", deltas[0].hit.op)
        assertEquals(2L, deltas[0].deltaHits)
        assertEquals("0x01234568", deltas[0].hit.firstPc)

        assertTrue(!deltas[1].isNew)
        assertEquals("0xfffa9600", deltas[1].hit.address)
        assertEquals(2L, deltas[1].deltaHits)
    }

    @Test
    fun releaseConfigDeclaresAllButtonsAndDualDirectorySdSlots() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val buttonsYaml = repo.resolve("tools/release/component-configs/buttons/buttons.yaml")
        assertTrue(Files.isRegularFile(buttonsYaml), "Missing release buttons.yaml")
        val buttons = Formats.read(buttonsYaml)
        for (key in listOf("menu", "back", "up", "down", "left", "right", "shutterHalf", "shutterFull", "play", "movieMode", "power")) {
            assertTrue(key in buttons, "Release buttons.yaml missing required control key '$key'")
        }
        assertEquals(false, buttons["movieMode"], "Release buttons.yaml must boot in STILL mode (movieMode=false)")
        assertEquals(true, buttons["power"], "Release buttons.yaml must boot powered on (power=true)")
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst)
                }
            }
        }
    }

    private fun stepBy(
        rt: EmulatorRuntime,
        deltaAttempts: Long,
        maxDeltaVt: Long = Long.MAX_VALUE,
        maxWallMs: Long = Long.MAX_VALUE,
    ): Long {
        val deadlineMs = if (maxWallMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + maxWallMs
        return rt.owner.submit {
            val target = rt.session.checkpointAttempts() + deltaAttempts
            val targetVt = if (maxDeltaVt == Long.MAX_VALUE) Long.MAX_VALUE else rt.host.tick + maxDeltaVt
            var stepCount = 0
            while (rt.session.checkpointAttempts() < target && rt.host.tick < targetVt) {
                if ((stepCount and 63) == 0 && System.currentTimeMillis() >= deadlineMs) break
                if (!rt.session.stepFast()) break
                stepCount++
            }
            rt.session.checkpointAttempts()
        }.join()
    }

    private fun patchButtons(rt: EmulatorRuntime, patch: Map<String, Any>) {
        rt.owner.submit {
            val btn = rt.host.components().single { it.descriptor.id == "buttons" }
            rt.host.patch("buttons", btn.revision, patch).join()
        }.join()
    }

    private fun tapButton(
        rt: EmulatorRuntime,
        key: String,
        holdAttempts: Long = 4_000_000L,
        releaseAttempts: Long = 16_000_000L,
    ) {
        patchButtons(rt, mapOf(key to true))
        stepBy(rt, holdAttempts)
        patchButtons(rt, mapOf(key to false))
        stepBy(rt, releaseAttempts)
    }

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        Triple(display.sequence, display.width to display.height, pixels)
    }.join()

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readGuestU16(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.short.toInt() and 0xffff
    }.join()

    private fun readGuestU8(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(address, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00ffffff) != 0 }

    private fun countUniqueColors(pixels: IntArray): Int {
        val set = HashSet<Int>(256)
        var i = 0
        while (i < pixels.size) {
            set.add(pixels[i] and 0x00ffffff)
            if (set.size >= 128) break
            i += 17
        }
        return set.size
    }

    private fun countDiffPixels(a: IntArray, b: IntArray): Int {
        var diff = 0
        for (i in a.indices) {
            if (a[i] != b[i]) diff++
        }
        return diff
    }

    private fun listDcimFiles(workRoot: Path): List<String> {
        val dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcim)) return emptyList()
        return Files.walk(dcim).use { s ->
            s.filter { Files.isRegularFile(it) }
                .map { "${dcim.relativize(it)}(${Files.size(it)}B)" }
                .sorted()
                .toList()
        }
    }

    private fun jpegCounts(rt: EmulatorRuntime): Pair<Long, Long> = rt.owner.submit {
        val st = rt.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state()
        val dec = (st["decodeCount"] as? Number)?.toLong() ?: 0L
        val enc = (st["encodeCount"] as? Number)?.toLong() ?: 0L
        dec to enc
    }.join()

    private fun relaxedMmioMap(rt: EmulatorRuntime): Map<String, RelaxedMmioHit> =
        parseRelaxedMmioEntries(rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join())

    private fun dumpPng(dumpDir: Path?, name: String, dims: Pair<Int, Int>, pixels: IntArray) {
        if (dumpDir == null) return
        Files.createDirectories(dumpDir)
        val img = BufferedImage(dims.first, dims.second, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, pixels, 0, dims.first)
        ImageIO.write(img, "png", dumpDir.resolve(name).toFile())
    }

    private fun lcdcShort(rt: EmulatorRuntime): String = rt.owner.submit {
        val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", MmioDevice::class.java)
        if (lcdc == null) "lcdc=null" else {
            "lcdc(008=0x${lcdc.read(0x008, 4).toString(16)},030=0x${lcdc.read(0x030, 4).toString(16)},044=0x${lcdc.read(0x044, 4).toString(16)},168=0x${lcdc.read(0x168, 4).toString(16)})"
        }
    }.join()

    @Test
    fun fullCameraModesCaptureRecordingMenusAndSubmenusE2e(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_M8_FULL_E2E") == "1",
            "Set FUJI_RUN_M8_FULL_E2E=1 to run the full GFX100S mode-switching & sub-menu E2E crawl",
        )
        val exploreMode = System.getenv("FUJI_M8_EXPLORE") == "1"
        val dumpDir = System.getenv("FUJI_M8_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val enabledPhases = (System.getenv("FUJI_M8_PHASES")?.takeIf { it.isNotBlank() } ?: "A,B,C,D,E")
            .split(',').map { it.trim().uppercase() }.toSet()

        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("m8-release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml")) + mapOf(
            "telemetry" to mapOf("enabled" to false),
        )

        val logs = mutableListOf<PhaseLog>()
        val softFailures = mutableListOf<String>()
        var mmioBaseline: Map<String, RelaxedMmioHit> = emptyMap()
        var lastPixels: IntArray? = null
        val t0Ms = System.currentTimeMillis()
        fun trace(msg: String) {
            val line = "[+%6.1fs] %s".format((System.currentTimeMillis() - t0Ms) / 1000.0, msg)
            println(line)
            if (dumpDir != null) {
                Files.createDirectories(dumpDir)
                Files.writeString(
                    dumpDir.resolve("trace.log"),
                    line + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND,
                )
            }
        }

        fun checkOrRecord(cond: Boolean, msg: String) {
            if (!cond) {
                if (exploreMode) {
                    softFailures += msg
                    println("[M8/SOFT-FAIL] $msg")
                } else {
                    assertTrue(false, msg)
                }
            }
        }

        fun recordPhase(rt: EmulatorRuntime, name: String, pngName: String? = null, note: String = ""): PhaseLog {
            val st = rt.owner.submit { rt.session.state() }.join()
            val status = st["status"].toString()
            val frontier = st["frontier"]?.toString()
            val scr = readGuestU32(rt, 0x01CC6424L)
            val bDsc = readGuestU8(rt, 0x01CBFCA2L)
            val errno = readGuestU16(rt, 0x01CC0628L)
            val (seq, dims, px) = captureMain(rt)
            val diff = lastPixels?.let { countDiffPixels(it, px) } ?: countNonBlack(px)
            lastPixels = px
            if (pngName != null) dumpPng(dumpDir, pngName, dims, px)
            val (dec, enc) = jpegCounts(rt)
            val mmioNow = relaxedMmioMap(rt)
            val mmioDiff = diffRelaxedMmio(mmioBaseline, mmioNow)
            mmioBaseline = mmioNow
            val dcimFiles = listDcimFiles(workRoot)
            val entry = PhaseLog(
                phase = name,
                scrHex = "0x${scr.toString(16)}",
                bDsc = bDsc,
                errnoHex = "0x${errno.toString(16)}",
                status = status,
                frontier = frontier,
                seq = seq,
                diffPixels = diff,
                decodeCount = dec,
                encodeCount = enc,
                sdDcimFiles = dcimFiles,
                newMmio = mmioDiff,
                note = "$note ${lcdcShort(rt)} tick=${rt.telemetry.virtualTick}".trim(),
            )
            logs += entry
            val mmioBrief = if (mmioDiff.isEmpty()) "none" else mmioDiff.joinToString("; ") {
                val tag = if (it.isNew) "NEW" else "+${it.deltaHits}"
                "$tag ${it.hit.source}:${it.hit.op}@${it.hit.address}(val=${it.hit.value ?: "-"},pc=${it.hit.firstPc},reason=${it.hit.reason ?: "-"})"
            }
            trace(
                "[M8][$name] status=$status scr=${entry.scrHex} bDsc=$bDsc errno=${entry.errnoHex} seq=$seq diff=$diff jpeg(dec=$dec,enc=$enc) dcim=${dcimFiles.size} mmio=[$mmioBrief] ${entry.note}",
            )
            return entry
        }

        fun isRunning(rt: EmulatorRuntime): Boolean =
            rt.owner.submit { rt.session.state()["status"].toString() }.join() == "running"

        fun returnToStillHud(rt: EmulatorRuntime, maxBackTaps: Int = 6) {
            for (attempt in 0 until maxBackTaps) {
                if (!isRunning(rt)) break
                val scr = readGuestU32(rt, 0x01CC6424L)
                val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                // 0x0100 (UI_LIB_REC_START) and 0x0102 (UI_LIB_REC_MODE) are both live STILL shooting HUDs;
                // tapping DISP/BACK while already in STILL live view cycles DISP mode into 0x0506 (UI_LIB_LCD_OFF)!
                if (scr in listOf(0x0100, 0x0102) && bDsc == 0) break
                // 0x011F (117-point AF Focus Area grid) exits via MENU/OK, not BACK
                val key = if (scr == 0x011F) "menu" else "back"
                tapButton(rt, key, 4_000_000L, 18_000_000L)
            }
            // Wait past any transient black mode-switch blanking frame so HUD/liveview is painted
            for (chunk in 0 until 8) {
                if (!isRunning(rt)) break
                val (_, _, px) = captureMain(rt)
                if (countNonBlack(px) > 150_000) break
                stepBy(rt, 10_000_000L)
            }
        }

        fun enterSubmenuAndReturn(
            rt: EmulatorRuntime,
            name: String,
            pngName: String? = null,
            alsoProbeDown: Boolean = false,
            enterKey: String = "menu",
        ): Boolean {
            if (!isRunning(rt)) return false
            val beforeScr = readGuestU32(rt, 0x01CC6424L)
            val (_, _, beforePx) = captureMain(rt)
            tapButton(rt, enterKey, 5_000_000L, 28_000_000L)
            val afterScr = readGuestU32(rt, 0x01CC6424L)
            val (_, _, afterPx) = captureMain(rt)
            val delta = countDiffPixels(beforePx, afterPx)
            val entered = (afterScr != beforeScr) || delta > 15_000
            recordPhase(rt, name, pngName, note = "entered=$entered deltaFromBefore=$delta")
            if (entered && isRunning(rt)) {
                if (alsoProbeDown) tapButton(rt, "down", 4_000_000L, 14_000_000L)
                val exitKey = if (readGuestU32(rt, 0x01CC6424L) == 0x011F) "menu" else "back"
                tapButton(rt, exitKey, 5_000_000L, 22_000_000L)
            }
            return entered
        }

        fun openMenuFromCurrent(rt: EmulatorRuntime, expectedRange: IntRange = 0x0400..0x04FF): Boolean {
            val (_, _, basePx) = captureMain(rt)
            tapButton(rt, "menu", 6_000_000L, 25_000_000L)
            for (chunk in 0 until 14) {
                if (!isRunning(rt)) return false
                val scr = readGuestU32(rt, 0x01CC6424L)
                val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                val (_, _, px) = captureMain(rt)
                if (scr in expectedRange && bDsc == 0 && countDiffPixels(basePx, px) > 120_000) return true
                stepBy(rt, 10_000_000L)
            }
            return readGuestU32(rt, 0x01CC6424L) in expectedRange
        }

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // =========================================================================
            // PHASE A: Cold boot -> Full OOBE (Language -> Area -> Date/Time -> Start) -> STILL HUD (0x0100)
            // =========================================================================
            stepBy(rt, 620_000_000L)
            val bootLog = recordPhase(rt, "A1_OOBE_LANG", "01-oobe-lang.png")
            checkOrRecord(bootLog.status == "running" && bootLog.scrHex == "0x421", "Phase A1 expected running at 0x0421: $bootLog")

            // Select English (or scroll once to verify response, then up back to English for legible English menu screenshots)
            tapButton(rt, "down", 5_000_000L, 12_000_000L)
            tapButton(rt, "up", 5_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            val areaLog = recordPhase(rt, "A2_OOBE_AREA", "02-oobe-area.png")
            checkOrRecord(areaLog.scrHex == "0x423", "Phase A2 expected Area 0x0423: $areaLog")
            // Regression guard (Bug 2): verify upper half of the 0x0423 world map (North America & Northern Eurasia,
            // y in 160..255 above the old inverted-scissor y=264 cutoff) is visibly rendered!
            run {
                val (_, dims, px) = captureMain(rt)
                fun countBrightInBox(x0: Int, x1: Int, y0: Int, y1: Int): Int {
                    var cnt = 0
                    for (y in y0 until minOf(y1, dims.second)) {
                        val row = y * dims.first
                        for (x in x0 until minOf(x1, dims.first)) {
                            val r = (px[row + x] ushr 16) and 0xff
                            if (r > 35) cnt++
                        }
                    }
                    return cnt
                }
                val northAmericaUpper = countBrightInBox(120, 420, 160, 255)
                val eurasiaUpper = countBrightInBox(620, 950, 160, 255)
                println("[M8][A2_OOBE_AREA_MAP_UPPER] northAmericaUpper=$northAmericaUpper eurasiaUpper=$eurasiaUpper")
                checkOrRecord(
                    northAmericaUpper > 200 && eurasiaUpper > 200,
                    "Phase A2 expected intact upper half of world map (y=160..255), got northAmerica=$northAmericaUpper eurasia=$eurasiaUpper",
                )
            }

            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            val dtLog = recordPhase(rt, "A3_OOBE_DATETIME", "03-oobe-datetime.png")
            checkOrRecord(dtLog.scrHex == "0x422", "Phase A3 expected Date/Time 0x0422: $dtLog")

            // Edit Year +1, move Right to Month, Month +1, then confirm Date/Time
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "right", 4_000_000L, 12_000_000L)
            tapButton(rt, "up", 4_000_000L, 12_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            val appLog = recordPhase(rt, "A4_OOBE_START_APP", "04-oobe-start-app.png")
            checkOrRecord(appLog.scrHex == "0x482", "Phase A4 expected Start App 0x0482: $appLog")

            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            var hudReached = false
            for (iter in 0 until 28) {
                if (!isRunning(rt)) break
                val scr = readGuestU32(rt, 0x01CC6424L)
                val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                val (_, _, px) = captureMain(rt)
                if (scr == 0x0100 && bDsc == 0 && countNonBlack(px) > 150_000 && countUniqueColors(px) >= 40) {
                    hudReached = true
                    break
                }
                stepBy(rt, 8_000_000L)
            }
            stepBy(rt, 24_000_000L)
            val hudLog = recordPhase(rt, "A5_STILL_HUD", "05-still-hud.png")
            checkOrRecord(hudReached && hudLog.status == "running" && hudLog.errnoHex == "0x0", "Phase A5 expected Live STILL HUD 0x0100: $hudLog")

            // Regression guard (Bug 1 + #644 + BUG-16-DISP): verify calibrated 4.05M virtual-tick hold of DISP/BACK
            // cleanly cycles ALL 3 STILL LCD modes (Standard HUD -> Info Off -> Info Display 15-tile panel -> Standard HUD)
            // on a single tap with zero blackouts, and MENU enters 0x0400 and exits cleanly via DISP/BACK!
            if (isRunning(rt)) {
                fun stepVirtualTicks(deltaTicks: Long): Long {
                    val startTick = rt.telemetry.virtualTick
                    val targetTick = startTick + deltaTicks
                    while (rt.telemetry.virtualTick < targetTick && isRunning(rt)) {
                        rt.owner.submit {
                            var i = 0
                            while (i < 512 && rt.telemetry.virtualTick < targetTick) {
                                if (!rt.session.stepFast()) break
                                i++
                            }
                        }.join()
                    }
                    return rt.telemetry.virtualTick - startTick
                }
                val holdTicks = com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS
                // 1a. Tap DISP/BACK #1 on STILL Live View 0x0100 -> Standard HUD -> Info Off
                val (_, _, preDispPx) = captureMain(rt)
                patchButtons(rt, mapOf("back" to true))
                stepVirtualTicks(holdTicks)
                patchButtons(rt, mapOf("back" to false))
                stepVirtualTicks(35_000_000L)
                val (_, _, infoOffPx) = captureMain(rt)
                val dispDelta = countDiffPixels(preDispPx, infoOffPx)
                val dispLog = recordPhase(rt, "A6_STILL_DISP_CYCLE", "05b-still-disp-cycle.png", note = "dispDelta=$dispDelta")
                checkOrRecord(
                    dispLog.scrHex == "0x100" && dispLog.status == "running" && dispDelta > 2_000,
                    "Phase A6 expected calibrated DISP/BACK hold ($holdTicks vticks) to cycle STILL HUD overlay: delta=$dispDelta $dispLog",
                )

                // 1b. Tap DISP/BACK #2 -> Full-Screen Info Display (0x008=0x11000, 0x030=0x20080001; must NOT blank black!)
                patchButtons(rt, mapOf("back" to true))
                stepVirtualTicks(holdTicks)
                patchButtons(rt, mapOf("back" to false))
                stepVirtualTicks(45_000_000L)
                val (_, _, infoDispPx) = captureMain(rt)
                val infoDispNonBlack = countNonBlack(infoDispPx)
                val infoDispDelta = countDiffPixels(infoOffPx, infoDispPx)
                val infoDispLog = recordPhase(rt, "A6b_STILL_DISP_INFO_DISPLAY", "05b2-still-info-display.png", note = "nonBlack=$infoDispNonBlack delta=$infoDispDelta")
                checkOrRecord(
                    infoDispLog.scrHex == "0x100" && infoDispLog.status == "running" && infoDispNonBlack > 100_000 && infoDispDelta > 100_000,
                    "Phase A6b expected DISP/BACK Info Display (PIC1 off, OSD0 active) to render visibly non-black: nonBlack=$infoDispNonBlack delta=$infoDispDelta $infoDispLog",
                )

                // 1c. Tap DISP/BACK #3 -> returns to Standard Live View HUD
                patchButtons(rt, mapOf("back" to true))
                stepVirtualTicks(holdTicks)
                patchButtons(rt, mapOf("back" to false))
                stepVirtualTicks(75_000_000L)
                val (_, _, stdAgainPx) = captureMain(rt)
                val stdAgainDelta = countDiffPixels(infoDispPx, stdAgainPx)
                val stdAgainLog = recordPhase(rt, "A6c_STILL_DISP_BACK_TO_STD", "05b3-still-disp-std-return.png", note = "delta=$stdAgainDelta")
                checkOrRecord(
                    stdAgainLog.scrHex == "0x100" && stdAgainLog.status == "running" && stdAgainDelta > 100_000,
                    "Phase A6c expected DISP/BACK #3 to restore Standard Live View HUD: delta=$stdAgainDelta $stdAgainLog",
                )

                // 2. Tap MENU/OK (menu) on STILL Live View 0x0100 -> opens 0x0400, then DISP/BACK exits back to 0x0100
                patchButtons(rt, mapOf("menu" to true))
                stepVirtualTicks(holdTicks)
                patchButtons(rt, mapOf("menu" to false))
                var menuScr = readGuestU32(rt, 0x01CC6424L)
                for (c in 0 until 15) {
                    stepVirtualTicks(3_000_000L)
                    menuScr = readGuestU32(rt, 0x01CC6424L)
                    if (menuScr in 0x0400..0x04FF) break
                }
                val quickMenuLog = recordPhase(rt, "A7_STILL_MENU_CALIBRATED", "05c-still-menu-calibrated.png")
                checkOrRecord(
                    quickMenuLog.scrHex.startsWith("0x4") && quickMenuLog.status == "running",
                    "Phase A7 expected calibrated MENU hold ($holdTicks vticks) to open 0x0400: $quickMenuLog",
                )
                // Exit back to 0x0100 via DISP/BACK
                patchButtons(rt, mapOf("back" to true))
                stepVirtualTicks(holdTicks)
                patchButtons(rt, mapOf("back" to false))
                for (c in 0 until 15) {
                    stepVirtualTicks(3_000_000L)
                    if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
                }
                val backFromMenuLog = recordPhase(rt, "A8_MENU_EXIT_DISP_BACK", "05d-still-after-disp-exit.png")
                checkOrRecord(
                    backFromMenuLog.scrHex == "0x100" && backFromMenuLog.status == "running",
                    "Phase A8 expected calibrated DISP/BACK hold to exit MENU back to 0x0100: $backFromMenuLog",
                )
            }

            // =========================================================================
            // =========================================================================
            // PHASE B: STILL Photo Capture (physical S/C/M -> MF, S1 -> S2 -> 102MP JPEG -> Slot 1 SD)
            // =========================================================================
            if ("B" in enabledPhases && isRunning(rt)) {
                val dcimBefore = listDcimFiles(workRoot)
                val dcimBeforeSet = dcimBefore.toSet()
                val (_, encBefore) = jpegCounts(rt)
                // Flip physical S/C/M focus selector lever (GPIO 7.13=0, 7.14=1 -> MF, input7=0x4040=16448)
                // so S2 release is not gated by AF-S Focus Priority before AF convergence.
                rt.owner.submit {
                    rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 16448L))
                }.join()
                stepBy(rt, 16_000_000L)

                patchButtons(rt, mapOf("shutterHalf" to true))
                for (w in 0 until 5) {
                    if (!isRunning(rt)) break
                    stepBy(rt, 12_000_000L)
                    val s1Sub0 = readGuestU8(rt, 0x02C47C5CL + 0x60L)
                    val saclac0 = readGuestU16(rt, 0x02DA49ECL)
                    if (w >= 1 && (s1Sub0 >= 4 || (s1Sub0 == 0 && saclac0 == 4))) break
                }
                recordPhase(rt, "B1_SHUTTER_S1_HALF", "06-shutter-s1.png")

                patchButtons(rt, mapOf("shutterFull" to true))
                for (w in 0 until 3) {
                    if (!isRunning(rt)) break
                    stepBy(rt, 12_000_000L)
                }
                patchButtons(rt, mapOf("shutterFull" to false, "shutterHalf" to false))
                var restoredAfs = false
                for (chunk in 0 until 75) {
                    if (!isRunning(rt)) break
                    stepBy(rt, 12_000_000L, maxDeltaVt = 40_000_000L, maxWallMs = 10_000L)
                    val (_, encNow) = jpegCounts(rt)
                    val dcimNow = listDcimFiles(workRoot)
                    val scrNow = readGuestU32(rt, 0x01CC6424L)
                    val bDscNow = readGuestU8(rt, 0x01CBFCA2L)
                    if (!restoredAfs && encNow > encBefore && scrNow in listOf(0x0100, 0x0102) && bDscNow == 0) {
                        // Capture encode complete and camera returned to STILL live view: restore physical S/C/M lever to AF-S.
                        restoredAfs = true
                        rt.owner.submit {
                            rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 8256L))
                        }.join()
                    }
                    if (encNow > encBefore && dcimNow.size > dcimBefore.size && scrNow in listOf(0x0100, 0x0102) && bDscNow == 0) {
                        break
                    }
                }
                if (!restoredAfs) {
                    rt.owner.submit {
                        rt.host.components().single { it.descriptor.id == "soc-gpio" }.component.applySettings(mapOf("input7" to 8256L))
                    }.join()
                }
                val capLog = recordPhase(
                    rt,
                    "B2_STILL_CAPTURE_DONE",
                    "07-after-still-capture.png",
                    note = "dcimBefore=${dcimBefore.size} encBefore=$encBefore",
                )

                val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
                val newDcimPaths = if (Files.isDirectory(dcimRoot)) {
                    Files.walk(dcimRoot).use { s ->
                        s.filter { Files.isRegularFile(it) }
                            .filter { "${dcimRoot.relativize(it)}(${Files.size(it)}B)" !in dcimBeforeSet }
                            .sorted()
                            .toList()
                    }
                } else emptyList()

                dumpDir?.let { dir ->
                    Files.createDirectories(dir)
                    for (f in newDcimPaths) {
                        Files.copy(f, dir.resolve("sd-${f.fileName}"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }

                checkOrRecord(
                    capLog.status == "running" && capLog.encodeCount > encBefore && capLog.sdDcimFiles.size > dcimBefore.size && newDcimPaths.isNotEmpty(),
                    "Phase B2 expected STILL shutter capture to encode & write new JPEG to Slot 1 DCIM: $capLog",
                )

                // Verify the newly captured JPEG is a valid, EXIF-tagged, non-black, non-uniform, non-green optical image.
                newDcimPaths.firstOrNull()?.let { capturedJpeg ->
                    val fileSize = Files.size(capturedJpeg)
                    val prefix = Files.newInputStream(capturedJpeg).use { it.readNBytes(64) }
                    val hasSoi = prefix.size >= 2 && (prefix[0].toInt() and 0xFF) == 0xFF && (prefix[1].toInt() and 0xFF) == 0xD8
                    val hasExif = String(prefix, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Exif")
                    var imgW = 0
                    var imgH = 0
                    var nonBlackPx = 0
                    var uniqueColors = 0
                    var meanR = 0.0
                    var meanG = 0.0
                    var meanB = 0.0
                    var decodedOk = false
                    ImageIO.createImageInputStream(capturedJpeg.toFile())?.use { iis ->
                        val readers = ImageIO.getImageReaders(iis)
                        if (readers.hasNext()) {
                            val reader = readers.next()
                            try {
                                reader.input = iis
                                imgW = reader.getWidth(0)
                                imgH = reader.getHeight(0)
                                val sub = maxOf(1, imgW / 1024)
                                val param = reader.defaultReadParam.apply { setSourceSubsampling(sub, sub, 0, 0) }
                                val img = reader.read(0, param)
                                val sw = img.width
                                val sh = img.height
                                val px = img.getRGB(0, 0, sw, sh, null, 0, sw)
                                nonBlackPx = countNonBlack(px)
                                uniqueColors = countUniqueColors(px)
                                var sumR = 0L; var sumG = 0L; var sumB = 0L
                                for (argb in px) {
                                    sumR += (argb ushr 16) and 0xFF
                                    sumG += (argb ushr 8) and 0xFF
                                    sumB += argb and 0xFF
                                }
                                meanR = sumR.toDouble() / px.size
                                meanG = sumG.toDouble() / px.size
                                meanB = sumB.toDouble() / px.size
                                decodedOk = sw > 0 && sh > 0
                            } finally {
                                reader.dispose()
                            }
                        }
                    }
                    val notPureGreen = !(meanG > 140.0 && meanR < 40.0 && meanB < 40.0)
                    trace(
                        "[M8][B2_JPEG_VERIFY] file=${capturedJpeg.fileName} size=${fileSize}B dim=${imgW}x${imgH} " +
                            "soi=$hasSoi exif=$hasExif decoded=$decodedOk nonBlack=$nonBlackPx uniqueColors=$uniqueColors " +
                            "meanRGB=(%.1f,%.1f,%.1f)".format(meanR, meanG, meanB),
                    )
                    checkOrRecord(
                        fileSize > 50_000L && hasSoi && hasExif && decodedOk && imgW >= 1000 && imgH >= 750 &&
                            nonBlackPx > 10_000 && uniqueColors >= 32 && notPureGreen,
                        "Phase B2 captured JPEG optical/EXIF verification failed: file=${capturedJpeg.fileName} " +
                            "size=$fileSize dim=${imgW}x${imgH} soi=$hasSoi exif=$hasExif decoded=$decodedOk " +
                            "nonBlack=$nonBlackPx uniqueColors=$uniqueColors meanRGB=($meanR,$meanG,$meanB)",
                    )
                }

                returnToStillHud(rt, maxBackTaps = 3)
            }

            // =========================================================================
            // PHASE C: STILL Main Menu (0x0400) & Deep Sub-Menu Crawl (All 5 Tabs + All 7 Setup Categories)
            // =========================================================================
            if ("C" in enabledPhases && isRunning(rt)) {
                val opened = openMenuFromCurrent(rt)
                stepBy(rt, 6_000_000L)
                val menuOpen = recordPhase(rt, "C0_STILL_MENU_OPEN", "08-still-menu-iq.png", note = "opened=$opened")
                checkOrRecord(menuOpen.scrHex.startsWith("0x4") && menuOpen.diffPixels > 100_000, "Phase C0 expected rendered STILL menu 0x04xx: $menuOpen")

                // GFX100S 2.13 Menu Navigation Invariant (verified visually):
                // Opening MENU lands focus on the left tab sidebar at Tab 0 (I.Q.).
                //   - `right` enters the current tab's item list (row 0)
                //   - `menu` opens the highlighted item's sub-menu / popup
                //   - `back` closes the popup back to the item list
                //   - `left` returns focus from the item list to the left tab sidebar
                //   - `down` on the sidebar advances to the next tab (I.Q. -> AF/MF -> SHOOTING -> FLASH -> WRENCH)

                // Tab 0 (I.Q.): enter item list (`right`), open item 0 (`IMAGE SIZE`) via `menu`, return to sidebar (`left`)
                if (isRunning(rt)) {
                    tapButton(rt, "right", 5_000_000L, 16_000_000L)
                    enterSubmenuAndReturn(rt, "C1_TAB0_IQ_IMAGE_SIZE", "09-menu-iq-sub0.png", alsoProbeDown = true, enterKey = "menu")
                    tapButton(rt, "left", 5_000_000L, 18_000_000L)
                }

                // Walk Tabs 1..3 (AF/MF, SHOOTING, FLASH) cleanly on left tab sidebar
                val midTabs = listOf(
                    "C2_TAB1_AFMF" to "10-menu-afmf.png",
                    "C3_TAB2_SHOOTING" to "11-menu-shooting.png",
                    "C4_TAB3_FLASH" to "12-menu-flash.png",
                )
                for (pair in midTabs) {
                    val (tabName, png) = pair
                    if (!isRunning(rt)) break
                    tapButton(rt, "down", 5_000_000L, 20_000_000L)
                    recordPhase(rt, tabName, png)
                    // Probe row 1 sub-menu on AF/MF (AF MODE), SHOOTING (SAVE SELF-TIMER), FLASH (RED EYE REMOVAL)
                    tapButton(rt, "right", 5_000_000L, 18_000_000L)
                    tapButton(rt, "down", 4_000_000L, 14_000_000L)
                    enterSubmenuAndReturn(rt, "${tabName}_ITEM1_SUB", "13-${tabName.lowercase()}-sub.png", alsoProbeDown = false, enterKey = "menu")
                    tapButton(rt, "left", 5_000_000L, 18_000_000L)
                }

                // Step 1 tab down from Tab 3 (FLASH) -> Tab 4 (WRENCH / SET UP) directly on the left tab sidebar!
                if (isRunning(rt)) {
                    tapButton(rt, "down", 5_000_000L, 24_000_000L)
                    recordPhase(rt, "C5_TAB4_SETUP_WRENCH", "14-menu-setup.png")
                    tapButton(rt, "right", 5_000_000L, 22_000_000L)
                    val setupCategories = listOf(
                        "USER_SETTING",
                        "SOUND_SETUP",
                        "SCREEN_SETUP",
                        "BUTTON_DIAL",
                        "POWER_MGMT",
                        "SAVE_DATA",
                        "CONNECTION",
                    )
                    for ((idx, cat) in setupCategories.withIndex()) {
                        if (!isRunning(rt)) break
                        if (idx > 0) tapButton(rt, "down", 5_000_000L, 16_000_000L)
                        val (_, _, beforeCatPx) = captureMain(rt)
                        val beforeCatScr = readGuestU32(rt, 0x01CC6424L)
                        tapButton(rt, "menu", 5_000_000L, 28_000_000L)
                        val (_, _, catPx) = captureMain(rt)
                        val catScr = readGuestU32(rt, 0x01CC6424L)
                        val enteredCat = (catScr != beforeCatScr) || countDiffPixels(beforeCatPx, catPx) > 15_000
                        recordPhase(rt, "C5_SETUP_${idx}_$cat", "15-setup-$idx-${cat.lowercase()}.png", note = "enteredCat=$enteredCat")
                        if (enteredCat && isRunning(rt)) {
                            if (cat != "USER_SETTING") {
                                // Probe leaf item 0 inside category (skip USER_SETTING row 0 FORMAT/DATE/RESET dialogs)
                                enterSubmenuAndReturn(rt, "C5_SETUP_${idx}_${cat}_LEAF0", "15-setup-$idx-leaf0.png", enterKey = "menu")
                                tapButton(rt, "down", 4_000_000L, 14_000_000L)
                                enterSubmenuAndReturn(rt, "C5_SETUP_${idx}_${cat}_LEAF1", "15-setup-$idx-leaf1.png", enterKey = "menu")
                            }
                            if (cat == "BUTTON_DIAL") {
                                // Peek page 2 of BUTTON/DIAL SETTING (locates SHOOT WITHOUT LENS / SHOOT WITHOUT CARD)
                                for (stepDown in 0 until 7) {
                                    if (!isRunning(rt)) break
                                    tapButton(rt, "down", 3_000_000L, 10_000_000L)
                                }
                                recordPhase(rt, "C5_SETUP_BUTTON_DIAL_P2", "15-setup-3-button_dial-p2.png")
                            }
                            if (cat == "CONNECTION") {
                                // Probe rows 2..4 inside CONNECTION SETTING (Bluetooth, Wireless, PC Connection / USB DWC3!)
                                for (extraRow in 2..4) {
                                    if (!isRunning(rt)) break
                                    tapButton(rt, "down", 4_000_000L, 16_000_000L)
                                    enterSubmenuAndReturn(rt, "C5_SETUP_CONNECTION_LEAF$extraRow", "15-setup-conn-leaf$extraRow.png", enterKey = "menu")
                                }
                            }
                            // Return from category list to Setup root list
                            tapButton(rt, "back", 5_000_000L, 24_000_000L)
                        }
                    }
                }

                returnToStillHud(rt, maxBackTaps = 6)
                val backStill = recordPhase(rt, "C7_RETURN_STILL_HUD", "16-still-after-menu.png")
                checkOrRecord(backStill.scrHex in listOf("0x100", "0x102") && backStill.status == "running", "Phase C7 expected return to STILL 0x0100/0x0102: $backStill")
            }

            // =========================================================================
            // PHASE D: Playback (PLAY 0x0300) + Playback Menu (PLAY -> MENU 0x0403) & Sub-Menus + Return to STILL
            // =========================================================================
            if ("D" in enabledPhases && isRunning(rt)) {
                val (decBefore, _) = jpegCounts(rt)
                tapButton(rt, "play", 5_000_000L, 20_000_000L)
                for (chunk in 0 until 15) {
                    if (!isRunning(rt)) break
                    val scr = readGuestU32(rt, 0x01CC6424L)
                    val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                    if (scr != 0x0100 && scr != 0x0102 && scr != 0 && scr != 0x0506 && bDsc == 0) break
                    stepBy(rt, 15_000_000L)
                }
                stepBy(rt, 12_000_000L)
                val playLog = recordPhase(
                    rt,
                    "D1_PLAY_ENTER",
                    "17-play-photo.png",
                    note = "accept0=0x${readGuestU32(rt, 0x01CBEFC8L).toUInt().toString(16)}",
                )
                checkOrRecord(
                    playLog.status == "running" && playLog.scrHex != "0x100" && playLog.decodeCount > decBefore,
                    "Phase D1 expected Playback with decoded JPEG: $playLog",
                )

                // Open Playback Menu (PLAY -> MENU -> 0x0403 PLAY BACK MENU) and crawl items + sub-menus!
                if (isRunning(rt)) {
                    val openedPlayMenu = openMenuFromCurrent(rt)
                    stepBy(rt, 6_000_000L)
                    val playMenuLog = recordPhase(rt, "D3_PLAY_MENU_OPEN", "18-play-menu.png", note = "opened=$openedPlayMenu")
                    checkOrRecord(playMenuLog.scrHex.startsWith("0x4"), "Phase D3 expected Playback Menu 0x0403: $playMenuLog")

                    if (playMenuLog.scrHex.startsWith("0x4")) {
                        // Row 0: SWITCH SLOT (skip toggling slot away from 1); walk rows 2..6 (ERASE, SIMUL DEL, CROP, RESIZE, PROTECT)
                        tapButton(rt, "down", 4_000_000L, 14_000_000L) // row 1 (RAW CONVERSION, greyed for JPG)
                        val playItems = listOf("ERASE", "SIMUL_DEL", "CROP", "RESIZE", "PROTECT")
                        for ((i, label) in playItems.withIndex()) {
                            if (!isRunning(rt)) break
                            tapButton(rt, "down", 4_000_000L, 14_000_000L)
                            if (label != "ERASE") { // avoid erasing DSCF0001.JPG during crawl; enter ERASE menu & back out safely
                                enterSubmenuAndReturn(rt, "D3_PLAY_MENU_$label", "19-play-menu-$i-${label.lowercase()}.png")
                            } else {
                                enterSubmenuAndReturn(rt, "D3_PLAY_MENU_ERASE", "19-play-menu-$i-erase.png")
                            }
                        }
                        // Exit PLAY BACK MENU back to 0x0300
                        for (attempt in 0 until 4) {
                            if (!isRunning(rt) || readGuestU32(rt, 0x01CC6424L) == 0x0300) break
                            tapButton(rt, "back", 5_000_000L, 20_000_000L)
                        }
                    }
                }

                // Browse next/prev in playback
                if (isRunning(rt)) {
                    tapButton(rt, "right", 4_000_000L, 16_000_000L)
                    recordPhase(rt, "D2_PLAY_BROWSE_RIGHT")
                    tapButton(rt, "left", 4_000_000L, 32_000_000L)
                    recordPhase(rt, "D2_PLAY_BROWSE_LEFT")
                }

                // Exit PLAY back to STILL Live View (0x0100) — tap shutterHalf (standard GFX100S instant return to shooting)
                // or play if still in 0x0300, avoiding double-tapping while 0x0506 transition is in flight.
                if (isRunning(rt)) {
                    for (retry in 0 until 2) {
                        if (!isRunning(rt) || readGuestU32(rt, 0x01CC6424L) in listOf(0x0100, 0x0102)) break
                        val curScr = readGuestU32(rt, 0x01CC6424L)
                        val exitBtn = if (curScr == 0x0506) "shutterHalf" else "play"
                        tapButton(rt, exitBtn, 6_000_000L, 25_000_000L)
                        for (chunk in 0 until 12) {
                            if (!isRunning(rt)) break
                            val s = readGuestU32(rt, 0x01CC6424L)
                            val b = readGuestU8(rt, 0x01CBFCA2L)
                            if (s in listOf(0x0100, 0x0102) && b == 0) break
                            stepBy(rt, 12_000_000L)
                        }
                    }
                    // Wait past transient blanking frame so STILL HUD is fully painted
                    for (chunk in 0 until 8) {
                        if (!isRunning(rt)) break
                        val (_, _, px) = captureMain(rt)
                        if (countNonBlack(px) > 150_000) break
                        stepBy(rt, 10_000_000L)
                    }
                    val playRetLog = recordPhase(rt, "D5_PLAY_RETURN_STILL", "21-still-after-play.png")
                    checkOrRecord(
                        playRetLog.scrHex in listOf("0x100", "0x102") && playRetLog.status == "running",
                        "Phase D5 expected return from PLAY to STILL 0x0100/0x0102: $playRetLog",
                    )
                }
            }

            // =========================================================================
            // PHASE E: STILL -> MOVIE Switch + MOVIE Menu/Sub-Menus + Movie Recording + Return to STILL
            // =========================================================================
            if ("E" in enabledPhases && isRunning(rt)) {
                val (_, _, preMoviePx) = captureMain(rt)
                patchButtons(rt, mapOf("movieMode" to true))
                // Wait until MOVIE live-view + FHD 59.94 HUD is ACTUALLY rendered (STILL->MOVIE HUD bars differ by ~14,800px)
                var movieHudSettled = false
                for (chunk in 0 until 22) {
                    if (!isRunning(rt)) break
                    stepBy(rt, 15_000_000L)
                    val scr = readGuestU32(rt, 0x01CC6424L)
                    val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                    val (_, _, px) = captureMain(rt)
                    if (scr in listOf(0x0100, 0x0102) && bDsc == 0 && chunk >= 4 && countNonBlack(px) > 150_000 && countDiffPixels(preMoviePx, px) > 8_000) {
                        movieHudSettled = true
                        break
                    }
                }
                stepBy(rt, 15_000_000L)
                val movieHudLog = recordPhase(rt, "E1_MOVIE_HUD", "22-movie-hud.png", note = "settled=$movieHudSettled")
                checkOrRecord(movieHudLog.status == "running" && movieHudLog.bDsc == 0 && movieHudSettled, "Phase E1 expected settled MOVIE live view: $movieHudLog")

                // Open MOVIE Menu & crawl tabs/sub-menus (now that MOVIE liveview is settled & accepting keys!)
                if (isRunning(rt)) {
                    val openedMovieMenu = openMenuFromCurrent(rt)
                    stepBy(rt, 10_000_000L)
                    val movMenuLog = recordPhase(rt, "E2_MOVIE_MENU_OPEN", "23-movie-menu.png", note = "opened=$openedMovieMenu")
                    checkOrRecord(movMenuLog.scrHex.startsWith("0x4") && movMenuLog.diffPixels > 100_000, "Phase E2 expected MOVIE menu 0x04xx: $movMenuLog")

                    // Crawl Movie Menu items & tabs (MOVIE SETTING -> AF/MF -> AUDIO -> TIMECODE -> WRENCH)
                    if (isRunning(rt) && movMenuLog.scrHex.startsWith("0x4")) {
                        enterSubmenuAndReturn(rt, "E2_MOVIE_MENU_SUB0", "24-movie-menu-sub0.png", enterKey = "right")
                        tapButton(rt, "left", 5_000_000L, 18_000_000L)
                        for (t in 1..4) {
                            if (!isRunning(rt)) break
                            tapButton(rt, "down", 5_000_000L, 20_000_000L)
                            recordPhase(rt, "E2_MOVIE_MENU_TAB$t", "24-movie-menu-tab$t.png")
                        }
                        returnToStillHud(rt, maxBackTaps = 4)
                    }
                }

                // Start & Stop Movie Recording via Shutter in MOVIE mode
                if (isRunning(rt)) {
                    val dcimBeforeRec = listDcimFiles(workRoot)
                    patchButtons(rt, mapOf("shutterHalf" to true))
                    stepBy(rt, 6_000_000L)
                    patchButtons(rt, mapOf("shutterFull" to true))
                    stepBy(rt, 10_000_000L)
                    patchButtons(rt, mapOf("shutterFull" to false, "shutterHalf" to false))
                    for (chunk in 0 until 6) {
                        if (!isRunning(rt)) break
                        stepBy(rt, 15_000_000L)
                    }
                    val recStartLog = recordPhase(rt, "E3_MOVIE_REC_ACTIVE", "25-movie-rec-active.png")

                    // Stop Movie Recording via second Shutter press
                    if (isRunning(rt)) {
                        patchButtons(rt, mapOf("shutterHalf" to true, "shutterFull" to true))
                        stepBy(rt, 8_000_000L)
                        patchButtons(rt, mapOf("shutterFull" to false, "shutterHalf" to false))
                        for (chunk in 0 until 6) {
                            if (!isRunning(rt)) break
                            stepBy(rt, 15_000_000L)
                        }
                        val recStopLog = recordPhase(
                            rt,
                            "E4_MOVIE_REC_STOP",
                            "26-movie-rec-stop.png",
                            note = "dcimBefore=${dcimBeforeRec.size}",
                        )
                        checkOrRecord(
                            recStartLog.status == "running" && recStopLog.status == "running",
                            "Phase E3/E4 expected Movie REC start/stop to stay running: start=$recStartLog stop=$recStopLog",
                        )
                    }
                }

                // Switch MOVIE -> STILL (movieMode=false) and verify REAL return to STILL HUD (pixel diff from MOVIE HUD, not just scr==0x0100!)
                if (isRunning(rt)) {
                    val (_, _, preStillPx) = captureMain(rt)
                    patchButtons(rt, mapOf("movieMode" to false))
                    var stillReturned = false
                    for (chunk in 0 until 24) {
                        if (!isRunning(rt)) break
                        stepBy(rt, 15_000_000L)
                        val scr = readGuestU32(rt, 0x01CC6424L)
                        val bDsc = readGuestU8(rt, 0x01CBFCA2L)
                        val (_, _, px) = captureMain(rt)
                        if (scr in listOf(0x0100, 0x0102) && bDsc == 0 && chunk >= 4 && countNonBlack(px) > 150_000 && countDiffPixels(preStillPx, px) > 8_000) {
                            stillReturned = true
                            break
                        }
                    }
                    val finalStillLog = recordPhase(rt, "E5_RETURN_STILL_FROM_MOVIE", "27-still-final.png", note = "stillReturned=$stillReturned")
                    checkOrRecord(
                        finalStillLog.scrHex in listOf("0x100", "0x102") && finalStillLog.status == "running" && (exploreMode.not() || stillReturned),
                        "Phase E5 expected real return from MOVIE to STILL HUD 0x0100/0x0102: $finalStillLog",
                    )
                }
            }

            val fullSummary = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join().orEmpty()
            trace("========== [M8 CRAWL FULL RELAXED SUMMARY] ==========\n$fullSummary")
            val summaryDiscovery = Regex("discoveryFallbacks=(\\d+)").find(fullSummary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            dumpDir?.let { dir ->
                Files.createDirectories(dir)
                val md = buildString {
                    appendLine("# M8 Full Camera Mode & Sub-Menu Crawl Report")
                    appendLine()
                    appendLine("| Phase | Status | Scr | bDsc | Errno | Seq | Diff | JPEG(dec/enc) | DCIM Files | New/Delta MMIO |")
                    appendLine("|---|---|---|---:|---|---:|---:|---|---:|---|")
                    for (l in logs) {
                        val mmioStr = if (l.newMmio.isEmpty()) "-" else l.newMmio.joinToString("<br>") {
                            val tag = if (it.isNew) "NEW" else "+${it.deltaHits}"
                            "`$tag ${it.hit.source}:${it.hit.op} ${it.hit.address}` (val=${it.hit.value ?: "-"}, pc=${it.hit.firstPc}, ${it.hit.reason ?: ""})"
                        }
                        appendLine("| ${l.phase} | ${l.status} | ${l.scrHex} | ${l.bDsc} | ${l.errnoHex} | ${l.seq} | ${l.diffPixels} | ${l.decodeCount}/${l.encodeCount} | ${l.sdDcimFiles.size} | $mmioStr |")
                    }
                    appendLine()
                    if (softFailures.isNotEmpty()) {
                        appendLine("## Soft Failures (Explore Mode)")
                        for (f in softFailures) appendLine("- $f")
                        appendLine()
                    }
                    appendLine("## Full Relaxed Summary")
                    appendLine("```")
                    appendLine(fullSummary)
                    appendLine("```")
                }
                Files.writeString(dir.resolve("crawl-report.md"), md, Charsets.UTF_8)
            }
            if (!exploreMode) {
                assertEquals(0, summaryDiscovery, "Expected zero unmapped discovery MMIO fallbacks across full crawl:\n$fullSummary")
                assertTrue(softFailures.isEmpty(), "M8 E2E failures:\n${softFailures.joinToString("\n")}")
            }
        }
    }
}
