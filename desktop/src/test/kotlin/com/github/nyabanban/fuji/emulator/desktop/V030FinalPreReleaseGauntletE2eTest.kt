package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.I2sAudioPort
import com.github.nyabanban.fuji.emulator.api.Imx461ExposurePort
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wave 14 Pre-`v0.3.0` Long-Session Combinatorial & Consistency Gauntlet (`W14-LONG-SESSION-COMBINATORIAL-GAUNTLET`).
 *
 * Opt-in gate: `FUJI_RUN_V030_GAUNTLET=1` (or `-Dfuji.runV030Gauntlet=1`).
 *
 * Exercises inside ONE continuous live `GXUP0004.DAT` Dynarmic session:
 * 1. Cold boot + full manual matrix permutations:
 *    - Mode dial `P -> A -> S -> M -> C1 -> P`
 *    - Shutter modes `MS -> EFCS -> ES`, Drive `S -> CL -> CH`, `14-bit` vs `16-bit` RAW+JPEG
 *    - Lenses & adapters (`gf45-100`, `gf63`, `gf110`, `adapter-ef-gfx`, `adapter-m42-dumb`)
 *    - Optical filters (`ND8`, `GND`, `Black Mist`, `Star`, `HEART`/`STAR_5`/`HEXAGON` bokeh, `UV`)
 *    - All 4 3D scenes (`qr-ball`, `mona-lisa`, `milk-frog`, `depth-studio`)
 * 2. Preview vs actual 102MP Still capture consistency:
 *    - FilmSim: PROVIA vs Velvia vs ACROS (monochrome!)
 *    - Optical Filter: ND8 ON vs OFF under manual exposure `M`
 *    - 35mm Format Mode / 35mm crop ON vs OFF
 *    - Focus distance / sharpness (`0.8 m` foreground star in-focus vs `10.0 m` defocused)
 * 3. Long Video Recording (`>= 3.5 vSec`) + `.MOV` container/codec/audio verification + Playback
 * 4. Long-session resilience: Sub-MCU power-off/standby -> wake -> post-wake still capture ->
 *    rapid dial/button/touch storm -> virtual clock & scanout timing alignment + zero-error gate.
 */
class V030FinalPreReleaseGauntletE2eTest {

    private data class ImageMetrics(
        val width: Int,
        val height: Int,
        val meanLuma: Double,
        val stdLuma: Double,
        val meanChroma: Double,
        val sharpness: Double,
        val nonBlack: Int,
    )

    private data class MovInspection(
        val path: Path,
        val sizeBytes: Long,
        val topBoxes: List<String>,
        val hasFtyp: Boolean,
        val hasMoov: Boolean,
        val hasMdat: Boolean,
        val hasVideoTrack: Boolean,
        val hasAudioTrack: Boolean,
        val videoCodecTag: String?,
        val audioCodecTag: String?,
    )

    private data class RafInspection(
        val path: Path,
        val sizeBytes: Long,
        val magicOk: Boolean,
    )

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            stream.forEach { src ->
                val rel = source.relativize(src)
                val dst = target.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun overlayWorktreeStage(repo: Path, workRoot: Path) {
        val compDir = repo.resolve("build/emulator/compoments")
        val dstCompDir = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDir)) {
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long, maxWallMs: Long = 90_000L) {
        val target = rt.telemetry.virtualTick + deltaTicks
        val deadline = System.nanoTime() + maxWallMs * 1_000_000L
        while (rt.telemetry.virtualTick < target && System.nanoTime() < deadline) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 1024 && rt.telemetry.virtualTick < target && !rt.session.isSocPoweredOff) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                rt.displayBridge.publishAll(rt.host.ports)
                !rt.session.isSocPoweredOff
            }.join()
            if (!keepGoing) break
        }
    }

    internal fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long, maxWallMs: Long = 90_000L) {
        stepVirtualTicks(rt, deltaMs * 54_000L, maxWallMs)
    }

    internal fun patchComp(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            rt.host.patchLatestDirect(id, settings, persistToDisk = false)
        }.join()
    }

    internal fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    internal fun tapButton(rt: EmulatorRuntime, key: String, holdMs: Long = 85L, settleMs: Long = 350L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComp(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun tapTouch(rt: EmulatorRuntime, targetId: String, x: Int, y: Int, holdMs: Long = 65L, settleMs: Long = 220L) {
        patchComp(rt, targetId, mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to x.toLong(), "touchY" to y.toLong()))
        stepVirtualMs(rt, holdMs)
        patchComp(rt, targetId, mapOf("touchEnabled" to true, "touchPressed" to false, "touchX" to x.toLong(), "touchY" to y.toLong()))
        stepVirtualMs(rt, settleMs)
    }

    internal fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    internal fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun readExposureRegs(rt: EmulatorRuntime): String = rt.owner.submit {
        val p = rt.host.ports.find(Imx461ExposurePort.PORT_ID, Imx461ExposurePort::class.java)
            ?: return@submit "imx=null"
        "shr=${p.shr15},svr=${p.svr12},vmax=${p.vmax12},pgc=${p.pgc12},hcg=${p.hcgEnabled}"
    }.join()

    private fun captureMainMetrics(
        rt: EmulatorRuntime,
        roiX0: Double = 0.18,
        roiY0: Double = 0.18,
        roiX1: Double = 0.82,
        roiY1: Double = 0.82,
    ): Pair<Long, ImageMetrics> = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val w = d.width
        val h = d.height
        val seq = d.sequence
        val px = IntArray(w * h)
        d.copyArgb(px)
        seq to analyzeArgb(px, w, h, roiX0, roiY0, roiX1, roiY1, stride = 2)
    }.join()

    private fun analyzeArgb(
        px: IntArray,
        w: Int,
        h: Int,
        roiX0: Double,
        roiY0: Double,
        roiX1: Double,
        roiY1: Double,
        stride: Int,
    ): ImageMetrics {
        val x0 = (w * roiX0).toInt().coerceIn(1, w - 2)
        val x1 = (w * roiX1).toInt().coerceIn(x0 + 1, w - 1)
        val y0 = (h * roiY0).toInt().coerceIn(1, h - 2)
        val y1 = (h * roiY1).toInt().coerceIn(y0 + 1, h - 1)
        var nonBlack = 0
        for (p in px) if ((p and 0x00ffffff) != 0) nonBlack++
        var lSum = 0.0
        var lSq = 0.0
        var cSum = 0.0
        var gradSum = 0.0
        var n = 0L
        var y = y0
        while (y < y1) {
            val rowOff = y * w
            var x = x0
            while (x < x1) {
                val p = px[rowOff + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                lSum += luma
                lSq += luma * luma
                cSum += (mx - mn).toDouble()
                val pr = px[rowOff + x + 1]
                val pd = px[rowOff + w + x]
                val lr = 0.299 * ((pr ushr 16) and 0xFF) + 0.587 * ((pr ushr 8) and 0xFF) + 0.114 * (pr and 0xFF)
                val ld = 0.299 * ((pd ushr 16) and 0xFF) + 0.587 * ((pd ushr 8) and 0xFF) + 0.114 * (pd and 0xFF)
                gradSum += abs(luma - lr) + abs(luma - ld)
                n++
                x += stride
            }
            y += stride
        }
        val meanL = if (n > 0) lSum / n else 0.0
        val stdL = if (n > 0) sqrt((lSq / n - meanL * meanL).coerceAtLeast(0.0)) else 0.0
        return ImageMetrics(
            width = w,
            height = h,
            meanLuma = meanL,
            stdLuma = stdL,
            meanChroma = if (n > 0) cSum / n else 0.0,
            sharpness = if (n > 0) gradSum / n else 0.0,
            nonBlack = nonBlack,
        )
    }

    private fun analyzeJpeg(
        path: Path,
        roiX0: Double = 0.18,
        roiY0: Double = 0.18,
        roiX1: Double = 0.82,
        roiY1: Double = 0.82,
    ): ImageMetrics {
        val img: BufferedImage = assertNotNull(ImageIO.read(path.toFile()), "Failed to decode JPEG ${path.fileName}")
        val w = img.width
        val h = img.height
        val x0 = (w * roiX0).toInt().coerceIn(2, w - 3)
        val x1 = (w * roiX1).toInt().coerceIn(x0 + 2, w - 2)
        val y0 = (h * roiY0).toInt().coerceIn(2, h - 3)
        val y1 = (h * roiY1).toInt().coerceIn(y0 + 2, h - 2)
        val stride = maxOf(4, w / 256)
        var lSum = 0.0
        var lSq = 0.0
        var cSum = 0.0
        var gradSum = 0.0
        var nonBlack = 0
        var n = 0L
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = img.getRGB(x, y)
                if ((p and 0x00ffffff) != 0) nonBlack++
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                lSum += luma
                lSq += luma * luma
                cSum += (mx - mn).toDouble()
                val pr = img.getRGB(x + 2, y)
                val pd = img.getRGB(x, y + 2)
                val lr = 0.299 * ((pr ushr 16) and 0xFF) + 0.587 * ((pr ushr 8) and 0xFF) + 0.114 * (pr and 0xFF)
                val ld = 0.299 * ((pd ushr 16) and 0xFF) + 0.587 * ((pd ushr 8) and 0xFF) + 0.114 * (pd and 0xFF)
                gradSum += abs(luma - lr) + abs(luma - ld)
                n++
                x += stride
            }
            y += stride
        }
        val meanL = if (n > 0) lSum / n else 0.0
        val stdL = if (n > 0) sqrt((lSq / n - meanL * meanL).coerceAtLeast(0.0)) else 0.0
        return ImageMetrics(
            width = w,
            height = h,
            meanLuma = meanL,
            stdLuma = stdL,
            meanChroma = if (n > 0) cSum / n else 0.0,
            sharpness = if (n > 0) gradSum / n else 0.0,
            nonBlack = nonBlack,
        )
    }

    internal fun listDcimFiles(workRoot: Path): Map<String, Path> {
        val dcimRoot = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcimRoot)) return emptyMap()
        val files = mutableListOf<Path>()
        fun visit(folder: Path) {
            Files.newDirectoryStream(folder).use { entries ->
                for (path in entries) {
                    // The FAT exporter atomically renames this private file after completing its writes.
                    if (path.fileName.toString().endsWith(".fuji-tmp")) continue
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) visit(path)
                    else if (Files.isRegularFile(path)) files.add(path)
                }
            }
        }
        visit(dcimRoot)
        return files.sorted().associateBy { dcimRoot.relativize(it).toString() }
    }

    private fun inspectRaf(path: Path): RafInspection {
        val head = Files.newInputStream(path).use { it.readNBytes(16) }
        val magic = String(head, Charsets.US_ASCII)
        return RafInspection(path, Files.size(path), magic.startsWith("FUJIFILMCCD-RAW"))
    }

    private fun inspectMov(path: Path): MovInspection {
        val bytes = Files.readAllBytes(path)
        val topBoxes = mutableListOf<String>()
        var off = 0
        while (off + 8 <= bytes.size) {
            val size32 = ((bytes[off].toInt() and 0xFF) shl 24) or
                ((bytes[off + 1].toInt() and 0xFF) shl 16) or
                ((bytes[off + 2].toInt() and 0xFF) shl 8) or
                (bytes[off + 3].toInt() and 0xFF)
            val tag = String(bytes, off + 4, 4, Charsets.US_ASCII)
            topBoxes.add(tag)
            val boxSize: Long = when (size32) {
                0 -> (bytes.size - off).toLong()
                1 -> {
                    if (off + 16 > bytes.size) break
                    ByteBuffer.wrap(bytes, off + 8, 8).order(ByteOrder.BIG_ENDIAN).long
                }
                else -> size32.toLong() and 0xFFFFFFFFL
            }
            if (boxSize < 8L || off + boxSize > bytes.size) break
            off += boxSize.toInt()
        }
        val ascii = String(bytes, Charsets.ISO_8859_1)
        val videoTags = listOf("hvc1", "hev1", "avc1", "avcC", "hvcC")
        val audioTags = listOf("lpcm", "sowt", "twos", "in24", "in32", "mp4a")
        val foundVideo = videoTags.firstOrNull { ascii.contains(it) }
        val foundAudio = audioTags.firstOrNull { ascii.contains(it) }
        return MovInspection(
            path = path,
            sizeBytes = bytes.size.toLong(),
            topBoxes = topBoxes,
            hasFtyp = "ftyp" in topBoxes,
            hasMoov = "moov" in topBoxes,
            hasMdat = "mdat" in topBoxes,
            hasVideoTrack = foundVideo != null,
            hasAudioTrack = foundAudio != null,
            videoCodecTag = foundVideo,
            audioCodecTag = foundAudio,
        )
    }

    internal fun returnToStillHud(rt: EmulatorRuntime, maxBackTaps: Int = 6) {
        for (i in 0 until maxBackTaps) {
            val scr = readU32(rt, 0x01CC6424L)
            val bDsc = readU8(rt, 0x01CBFCA2L)
            if (scr in listOf(0x0100, 0x0102) && bDsc == 0) return
            tapButton(rt, "back", 85L, 350L)
        }
    }

    internal fun ensureMenuOpen(rt: EmulatorRuntime): Boolean {
        for (i in 0 until 5) {
            val scr = readU32(rt, 0x01CC6424L)
            if (scr in 0x0400..0x04FF) return true
            if (scr !in listOf(0x0100, 0x0102)) tapButton(rt, "back", 85L, 300L)
            tapButton(rt, "menu", 90L, 420L)
            if (readU32(rt, 0x01CC6424L) in 0x0400..0x04FF) return true
        }
        println("[GAUNTLET][MENU_FAIL] scr=0x${readU32(rt, 0x01CC6424L).toString(16)} bDsc=${readU8(rt, 0x01CBFCA2L)} lensMgr=${readU8(rt, 0x01BE013CL)} mount=${compState(rt, "mount")["spiBridge"]}")
        return false
    }

    private fun assertHealthyCamera(rt: EmulatorRuntime, tag: String) {
        val st = rt.owner.submit { rt.session.state() }.join()
        assertEquals("running", st["status"].toString(), "[$tag] Session stopped/crashed: $st")
        val exc = readU32(rt, 0x0008_4AD4L)
        assertEquals(0, exc, "[$tag] Non-zero kernel exception counter 0x00084AD4=$exc")
        val scr = readU32(rt, 0x01CC6424L)
        assertNotEquals(0x0508, scr, "[$tag] Hit fatal camera error screen scr=0x0508 (TURN OFF THE CAMERA AND TURN ON AGAIN)")
        val jpegSt = compState(rt, "soc-jpeg")
        val outstanding = (jpegSt.getValue("encoderUnrecoveredErrorCount") as Number).toLong()
        assertEquals(0L, outstanding, "[$tag] Unrecovered JPEG encoding errors: $outstanding, " +
            "total=${jpegSt.getValue("encoderErrorCount")}, last error=${jpegSt["lastEncoderError"]}, " +
            "last recovery=${jpegSt["lastEncoderRecovery"]}")
    }

    private fun fireStillAndWait(
        rt: EmulatorRuntime,
        workRoot: Path,
        label: String,
        expectRaf: Boolean = false,
        holdFullMs: Long = 220L,
        maxWaitMs: Long = 12_000L,
        onHalfPress: () -> Unit = {},
    ): Map<String, Path> {
        val before = listDcimFiles(workRoot).keys
        patchComp(rt, "buttons", mapOf("shutterHalf" to true))
        stepVirtualMs(rt, 200L)
        onHalfPress()
        patchComp(rt, "buttons", mapOf("shutterFull" to true))
        stepVirtualMs(rt, holdFullMs)
        patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

        var added: Map<String, Path> = emptyMap()
        var elapsed = 0L
        while (elapsed < maxWaitMs) {
            stepVirtualMs(rt, 350L)
            elapsed += 350L
            added = listDcimFiles(workRoot).filterKeys { it !in before }
            val hasJpg = added.keys.any { it.endsWith(".JPG", ignoreCase = true) }
            val hasRaf = added.keys.any { it.endsWith(".RAF", ignoreCase = true) }
            val bDsc = readU8(rt, 0x01CBFCA2L)
            val scr = readU32(rt, 0x01CC6424L)
            if (hasJpg && (!expectRaf || hasRaf) && bDsc == 0 && scr in listOf(0x0100, 0x0102)) {
                val (_, m) = captureMainMetrics(rt)
                if (m.nonBlack > 120_000) break
            }
        }
        assertHealthyCamera(rt, "STILL:$label")
        println("[GAUNTLET][STILL][$label] files=${added.keys} scr=0x${readU32(rt, 0x01CC6424L).toString(16)} bDsc=${readU8(rt, 0x01CBFCA2L)} tick=${rt.telemetry.virtualTick}")
        return added
    }

    private fun settledPreview(rt: EmulatorRuntime): ImageMetrics {
        var (seq, previous) = captureMainMetrics(rt)
        var stable = 0
        repeat(24) {
            stepVirtualMs(rt, 250L)
            val (nextSeq, current) = captureMainMetrics(rt)
            if (nextSeq > seq && abs(current.meanLuma - previous.meanLuma) < 2.0 &&
                abs(current.stdLuma - previous.stdLuma) < 2.0
            ) stable++ else stable = 0
            if (stable >= 3) return current
            seq = nextSeq
            previous = current
        }
        error("Live preview exposure failed to settle within six virtual seconds: $previous")
    }

    private fun waitForStillLiveView(rt: EmulatorRuntime, tag: String) {
        for (i in 0 until 30) {
            val scr = readU32(rt, 0x01CC6424L)
            val (_, m) = captureMainMetrics(rt)
            if (scr in listOf(0x0100, 0x0102) && m.nonBlack > 140_000) return
            stepVirtualMs(rt, 180L)
        }
        val (_, m) = captureMainMetrics(rt)
        assertTrue(
            readU32(rt, 0x01CC6424L) in listOf(0x0100, 0x0102) && m.nonBlack > 140_000,
            "[$tag] Expected active STILL LiveView (scr=0x${readU32(rt, 0x01CC6424L).toString(16)} nonBlack=${m.nonBlack})",
        )
    }

    @Test
    fun preV030FullLongSessionCombinatorialGauntlet(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_V030_GAUNTLET") == "1" || System.getProperty("fuji.runV030Gauntlet") == "1",
            "Set FUJI_RUN_V030_GAUNTLET=1 to execute the full pre-v0.3.0 combinatorial & long-session gauntlet",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                (Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) ||
                    Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))),
            "Requires :stageReleaseLinux output at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("v030-gauntlet")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)
        Files.deleteIfExists(workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json"))
        val slot1Dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (Files.isDirectory(slot1Dcim)) {
            Files.walk(slot1Dcim).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }

        val config = LinkedHashMap(Formats.read(workRoot.resolve("configs/emulator.yaml"))).apply {
            put("realTimePacer", false)
        }
        val sessionWallStartNs = System.nanoTime()

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // =========================================================================
            // PHASE 1A: COLD BOOT + OOBE COMPLETION -> LIVE 1024x768 STILL HUD (0x0100)
            // =========================================================================
            stepVirtualTicks(rt, 620_000_000L)
            for (extra in 0 until 12) {
                if (readU32(rt, 0x01CC6424L) == 0x0421) break
                stepVirtualTicks(rt, 30_000_000L)
            }
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected OOBE Language screen 0x0421")
            tapButton(rt, "menu", 95L, 750L) // -> Area (0x0423)
            tapButton(rt, "down", 95L, 420L)
            tapButton(rt, "down", 95L, 420L)
            tapButton(rt, "menu", 95L, 750L) // -> Date/Time (0x0422)
            tapButton(rt, "up", 85L, 280L)
            tapButton(rt, "menu", 95L, 750L) // -> Start App (0x0482)
            tapButton(rt, "menu", 95L, 700L) // -> STILL HUD (0x0100)
            waitForStillLiveView(rt, "PHASE1_BOOT")
            assertHealthyCamera(rt, "PHASE1_BOOT")

            // Use MF lever for deterministic zero-delay shutter tests; switch back when testing AF
            patchComp(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true))
            stepVirtualMs(rt, 500L)

            val anomalies = mutableListOf<String>()
            fun checkPhase(cond: Boolean, msg: String) {
                if (!cond) {
                    anomalies.add(msg)
                    println("[GAUNTLET][ANOMALY] $msg")
                }
            }

            // =========================================================================
            // PHASE 1B: MODE DIAL MATRIX (P -> A -> S -> M -> C1 -> P)
            // =========================================================================
            for (mode in listOf("P", "A", "S", "M", "C1", "P")) {
                patchComp(rt, "soc-adc", mapOf("modeDial" to mode))
                stepVirtualMs(rt, 450L)
                val (seq, m) = captureMainMetrics(rt)
                assertHealthyCamera(rt, "MODE_DIAL_$mode")
                checkPhase(m.nonBlack > 140_000, "Mode dial $mode blacked out LiveView (nonBlack=${m.nonBlack})")
                println("[GAUNTLET][MODE_DIAL] mode=$mode seq=$seq luma=${"%.1f".format(m.meanLuma)} chroma=${"%.1f".format(m.meanChroma)} ${readExposureRegs(rt)}")
            }

            // =========================================================================
            // PHASE 1C: LENSES & ADAPTERS + OPTICAL FILTERS + ALL 4 3D SCENES
            // =========================================================================
            // Electronic lenses & electronic 35mm adapter hot-swap (dumb mechanical adapter removes KEY_LENS_DET
            // contacts like physically unmounting a GF lens while powered ON, so dumb-adapter check runs cleanly in Phase 4).
            for (lensTpl in listOf("gf45-100", "gf63", "gf110", "adapter-ef-gfx", "gf45-100")) {
                patchComp(rt, "lens", mapOf("template" to lensTpl))
                var settled = false
                var seq1 = 0L
                var seq2 = 0L
                var m1 = ImageMetrics(0, 0, 0.0, 0.0, 0.0, 0.0, 0)
                // Electronic <-> dumb mechanical adapter hot-swap triggers real LENS_DET GPIO transition
                // and lenscom re-init (can transiently blank LCD for ~0.5..1.5s on real GFX100S).
                for (waitStep in 1..8) {
                    stepVirtualMs(rt, 250L)
                    val (sA, mA) = captureMainMetrics(rt)
                    if (mA.nonBlack > 140_000) {
                        stepVirtualMs(rt, 200L)
                        val (sB, _) = captureMainMetrics(rt)
                        seq1 = sA
                        seq2 = sB
                        m1 = mA
                        if (sB > sA) {
                            settled = true
                            break
                        }
                    } else if (waitStep == 2 || waitStep == 8) {
                        val scrNow = readU32(rt, 0x01CC6424L)
                        val bDscNow = readU8(rt, 0x01CBFCA2L)
                        val lensMgr = readU8(rt, 0x01BE013CL)
                        println("[GAUNTLET][LENS_WAIT] template=$lensTpl step=$waitStep scr=0x${scrNow.toString(16)} bDsc=$bDscNow lensMgr=$lensMgr seq=$sA nb=${mA.nonBlack}")
                    }
                }
                assertHealthyCamera(rt, "LENS_$lensTpl")
                checkPhase(settled, "Lens $lensTpl did not settle LiveView within 2.0 vSec (seq=$seq1->$seq2 nb=${m1.nonBlack} scr=0x${readU32(rt, 0x01CC6424L).toString(16)} lensMgr=${readU8(rt, 0x01BE013CL)})")
                println("[GAUNTLET][LENS] template=$lensTpl settled=$settled seq=$seq1->$seq2 luma=${"%.1f".format(m1.meanLuma)} scr=0x${readU32(rt, 0x01CC6424L).toString(16)} lensMgr=${readU8(rt, 0x01BE013CL)}")
            }

            val filterSteps = listOf(
                "ND8" to mapOf("enabled" to true, "ndPreset" to "ND8"),
                "GND" to mapOf("ndPreset" to "OFF", "gndEnabled" to true, "gndStops" to 3.0),
                "MIST" to mapOf("gndEnabled" to false, "mistEnabled" to true, "mistPreset" to "1/2"),
                "STAR6" to mapOf("mistEnabled" to false, "starEnabled" to true, "starPoints" to 6L),
                "BOKEH_HEART" to mapOf("starEnabled" to false, "bokehMask" to "HEART"),
                "BOKEH_STAR5" to mapOf("bokehMask" to "STAR_5"),
                "BOKEH_HEX" to mapOf("bokehMask" to "HEXAGON"),
                "UV_POOR" to mapOf("bokehMask" to "OFF", "uvPreset" to "CHEAP_POOR"),
                "UV_PRO" to mapOf("uvPreset" to "PRO_MRC"),
                "CLEAR" to mapOf("uvPreset" to "OFF", "ndPreset" to "OFF", "gndEnabled" to false, "mistEnabled" to false, "starEnabled" to false, "bokehMask" to "OFF"),
            )
            for ((fname, fpatch) in filterSteps) {
                patchComp(rt, "optical-filter", fpatch)
                stepVirtualMs(rt, 320L)
                val (seq, m) = captureMainMetrics(rt)
                assertHealthyCamera(rt, "FILTER_$fname")
                checkPhase(m.nonBlack > 120_000, "Optical filter $fname blacked out LiveView (nb=${m.nonBlack})")
                println("[GAUNTLET][FILTER] $fname seq=$seq luma=${"%.1f".format(m.meanLuma)} chroma=${"%.1f".format(m.meanChroma)}")
            }

            for (scene in listOf("scene://qr-ball", "scene://mona-lisa", "scene://milk-frog", "scene://depth-studio")) {
                patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to scene, "loop" to true))
                stepVirtualMs(rt, 420L)
                val (seq1, m1) = captureMainMetrics(rt)
                stepVirtualMs(rt, 200L)
                val (seq2, _) = captureMainMetrics(rt)
                assertHealthyCamera(rt, "SCENE_$scene")
                checkPhase(seq2 > seq1 && m1.nonBlack > 140_000, "Scene $scene stalled LiveView ($seq1->$seq2, nb=${m1.nonBlack})")
                println("[GAUNTLET][SCENE] $scene seq=$seq1->$seq2 luma=${"%.1f".format(m1.meanLuma)} chroma=${"%.1f".format(m1.meanChroma)} scr=0x${readU32(rt, 0x01CC6424L).toString(16)} lensMgr=${readU8(rt, 0x01BE013CL)}")
            }
            println("[GAUNTLET][PRE_PHASE1D] scr=0x${readU32(rt, 0x01CC6424L).toString(16)} bDsc=${readU8(rt, 0x01CBFCA2L)} lensMgr=${readU8(rt, 0x01BE013CL)} mount=${compState(rt, "mount")["spiBridge"]}")

            // =========================================================================
            // PHASE 1D: SHUTTER MODES (EFCS -> ES -> MS) + RAW+JPEG (UNCOMP & LOSSLESS) + DRIVE (S -> CH -> CL)
            // =========================================================================
            // Switch IMAGE QUALITY -> FINE+RAW (I.Q. P1 R0 IMAGE SIZE -> DOWN 1 = R1 IMAGE QUALITY -> DOWN x3 from FINE = FINE+RAW)
            checkPhase(ensureMenuOpen(rt), "Phase 1D failed to open MENU for FINE+RAW")
            tapButton(rt, "down", 85L, 220L) // R1 IMAGE QUALITY
            tapButton(rt, "menu", 90L, 320L)
            repeat(3) { tapButton(rt, "down", 85L, 220L) } // NORMAL -> SUPER FINE+RAW -> FINE+RAW
            tapButton(rt, "menu", 90L, 320L)
            returnToStillHud(rt)

            // 1) Factory-default EFCS (SHUTTER TYPE Row 2) with FINE+RAW (Uncompressed 14-bit ~207 MB RAF)
            val shBeforeEfcs = compState(rt, "shutter")
            val efcsFiles = fireStillAndWait(rt, workRoot, "EFCS_RAW14", expectRaf = true, maxWaitMs = 20_000L)
            val shAfterEfcs = compState(rt, "shutter")
            val efcsJpg = efcsFiles.values.firstOrNull { it.toString().endsWith(".JPG", true) }
            val efcsRaf = efcsFiles.values.firstOrNull { it.toString().endsWith(".RAF", true) }
            checkPhase(efcsJpg != null && efcsRaf != null && inspectRaf(efcsRaf).magicOk, "EFCS FINE+RAW missing valid JPG/RAF: ${efcsFiles.keys}")
            val efcsCam1Delta = ((shAfterEfcs["cam1Cycles"] as? Number)?.toLong() ?: 0L) - ((shBeforeEfcs["cam1Cycles"] as? Number)?.toLong() ?: 0L)
            checkPhase(efcsCam1Delta >= 1L, "EFCS must actuate rear curtain Motor 1 (cam1Delta=$efcsCam1Delta)")
            println("[GAUNTLET][SHUTTER_EFCS] jpg=${efcsJpg?.let(Files::size)}B raf=${efcsRaf?.let(Files::size)}B cam1Delta=$efcsCam1Delta mode=${shAfterEfcs["mode"]}")

            // Switch RAW RECORDING -> LOSSLESS COMPRESSED (I.Q. R1 -> DOWN 1 = R2 RAW RECORDING -> DOWN 1 = LOSSLESS)
            // AND SHUTTER TYPE (Tab 2 R9): Row 2 (EF) -> UP 1 = Row 1 (ES)
            checkPhase(ensureMenuOpen(rt), "Phase 1D failed to open MENU for LOSSLESS+ES")
            tapButton(rt, "down", 85L, 220L) // R2 RAW RECORDING
            tapButton(rt, "menu", 90L, 320L)
            tapButton(rt, "down", 85L, 220L) // LOSSLESS COMPRESSED
            tapButton(rt, "menu", 90L, 320L)
            tapButton(rt, "left", 85L, 300L)
            tapButton(rt, "down", 85L, 250L)
            tapButton(rt, "down", 85L, 250L) // Tab 2 SHOOTING SETTING
            tapButton(rt, "right", 85L, 300L)
            repeat(9) { tapButton(rt, "down", 80L, 200L) } // R9 SHUTTER TYPE
            tapButton(rt, "right", 90L, 450L) // Enter SHUTTER TYPE (cursor on Row 2 EF)
            tapButton(rt, "up", 85L, 260L) // Row 1 ES (ELECTRONIC SHUTTER)
            tapButton(rt, "menu", 90L, 350L)
            returnToStillHud(rt)

            // 2) ES (Electronic Shutter) with LOSSLESS COMPRESSED RAW+JPEG — ZERO mechanical curtain/cam2 actuation!
            val shBeforeEs = compState(rt, "shutter")
            val esFiles = fireStillAndWait(rt, workRoot, "ES_LOSSLESS_RAW", expectRaf = true, maxWaitMs = 20_000L)
            val shAfterEs = compState(rt, "shutter")
            val esRaf = esFiles.values.firstOrNull { it.toString().endsWith(".RAF", true) }
            val esCurtainDelta = ((shAfterEs["curtainPulses"] as? Number)?.toLong() ?: 0L) - ((shBeforeEs["curtainPulses"] as? Number)?.toLong() ?: 0L)
            val esCam2Delta = ((shAfterEs["cam2Cycles"] as? Number)?.toLong() ?: 0L) - ((shBeforeEs["cam2Cycles"] as? Number)?.toLong() ?: 0L)
            checkPhase(esRaf != null && inspectRaf(esRaf).magicOk, "ES expected valid LOSSLESS RAF: ${esFiles.keys}")
            checkPhase(esCurtainDelta == 0L && esCam2Delta == 0L, "ES must have ZERO mechanical curtain/cam2 pulses (curtain=$esCurtainDelta cam2=$esCam2Delta)")
            println("[GAUNTLET][SHUTTER_ES] raf=${esRaf?.let(Files::size)}B curtainDelta=$esCurtainDelta cam2Delta=$esCam2Delta mode=${shAfterEs["mode"]}")

            // 3) Switch SHUTTER TYPE -> MS (Row 1 ES -> UP 1 = Row 0 MS)
            checkPhase(ensureMenuOpen(rt), "Phase 1D failed to open MENU for MS")
            tapButton(rt, "right", 90L, 450L) // remembers Tab 2 R9 SHUTTER TYPE
            tapButton(rt, "up", 85L, 260L) // Row 0 MS (MECHANICAL SHUTTER)
            tapButton(rt, "menu", 90L, 350L)
            returnToStillHud(rt)

            val shBeforeMs = compState(rt, "shutter")
            val msFiles = fireStillAndWait(rt, workRoot, "MS_LOSSLESS_RAW", expectRaf = true, maxWaitMs = 20_000L)
            val shAfterMs = compState(rt, "shutter")
            val msCurtainDelta = ((shAfterMs["curtainPulses"] as? Number)?.toLong() ?: 0L) - ((shBeforeMs["curtainPulses"] as? Number)?.toLong() ?: 0L)
            val msCam1Delta = ((shAfterMs["cam1Cycles"] as? Number)?.toLong() ?: 0L) - ((shBeforeMs["cam1Cycles"] as? Number)?.toLong() ?: 0L)
            val msCam2Delta = ((shAfterMs["cam2Cycles"] as? Number)?.toLong() ?: 0L) - ((shBeforeMs["cam2Cycles"] as? Number)?.toLong() ?: 0L)
            checkPhase(msCurtainDelta >= 1L && msCam1Delta >= 1L && msCam2Delta >= 1L, "MS must actuate Motor1+Motor2+Curtain (cam1=$msCam1Delta cam2=$msCam2Delta curtain=$msCurtainDelta)")
            println("[GAUNTLET][SHUTTER_MS] cam1Delta=$msCam1Delta cam2Delta=$msCam2Delta curtainDelta=$msCurtainDelta mode=${shAfterMs["mode"]} files=${msFiles.keys}")

            // Switch IMAGE QUALITY back to FINE JPEG-only (Tab 0 I.Q. -> R0 IMAGE SIZE -> DOWN 1 = R1 IMAGE QUALITY -> UP x3 = FINE)
            checkPhase(ensureMenuOpen(rt), "Phase 1D failed to open MENU to restore FINE")
            tapButton(rt, "left", 85L, 300L)
            tapButton(rt, "up", 85L, 250L)
            tapButton(rt, "up", 85L, 250L) // Tab 0 I.Q. (resets to P1 R0 IMAGE SIZE on tab switch!)
            tapButton(rt, "right", 85L, 300L)
            tapButton(rt, "down", 85L, 220L) // P1 R1 IMAGE QUALITY
            tapButton(rt, "menu", 90L, 320L)
            repeat(3) { tapButton(rt, "up", 85L, 220L) } // FINE+RAW -> SUPER FINE+RAW -> NORMAL -> FINE
            tapButton(rt, "menu", 90L, 320L)
            returnToStillHud(rt)

            // DRIVE: S -> CH -> CL burst test
            for ((drvName, downSteps) in listOf("CH" to 1, "CL" to 1)) {
                tapButton(rt, "drive", 90L, 400L)
                repeat(downSteps) { tapButton(rt, "down", 85L, 240L) }
                tapButton(rt, "menu", 90L, 400L)
                returnToStillHud(rt)
                val burstFiles = fireStillAndWait(rt, workRoot, "DRIVE_$drvName", expectRaf = false, holdFullMs = 750L)
                val burstJpgs = burstFiles.keys.count { it.endsWith(".JPG", true) }
                println("[GAUNTLET][DRIVE][$drvName] burstJpgs=$burstJpgs files=${burstFiles.keys}")
                checkPhase(burstJpgs >= 1, "DRIVE $drvName must capture >= 1 JPEG")
            }
            // Return DRIVE -> STILL IMAGE (S)
            tapButton(rt, "drive", 90L, 400L)
            repeat(2) { tapButton(rt, "up", 85L, 240L) }
            tapButton(rt, "menu", 90L, 400L)
            returnToStillHud(rt)

            // =========================================================================
            // PHASE 2: PREVIEW VS ACTUAL CAPTURE CONSISTENCY (PROVIA / Velvia / ACROS / ND8 / 35MM / FOCUS)
            // =========================================================================
            patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true))
            patchComp(rt, "lens", mapOf("template" to "gf45-100", "focalLengthMm" to 63.0, "aperture" to 4.0, "focusDistanceM" to 2.5))
            patchComp(rt, "optical-filter", mapOf("enabled" to true, "ndPreset" to "OFF", "uvPreset" to "OFF"))
            stepVirtualMs(rt, 600L)

            // 2A: PROVIA preview vs 102MP JPEG
            val lvProvia = settledPreview(rt)
            val proviaShot = fireStillAndWait(rt, workRoot, "CONSIST_PROVIA")
            val jpgProvia = proviaShot.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }
            println("[GAUNTLET][CONSIST][PROVIA] LV(luma=${"%.1f".format(lvProvia.meanLuma)},chroma=${"%.1f".format(lvProvia.meanChroma)}) vs JPG($jpgProvia)")
            checkPhase(jpgProvia != null && lvProvia.meanChroma > 5.0 && jpgProvia.meanChroma > 5.0, "PROVIA must be colorful in BOTH LV (${lvProvia.meanChroma}) and JPG (${jpgProvia?.meanChroma})")
            if (jpgProvia != null) {
                checkPhase(abs(lvProvia.meanLuma - jpgProvia.meanLuma) < 55.0, "PROVIA LV vs JPG luma mismatch: LV=${lvProvia.meanLuma} JPG=${jpgProvia.meanLuma}")
            }

            // Switch FilmSim -> Velvia (cursor in Tab 0 remembers R1 IMAGE QUALITY -> down 2 = R3 FILM SIMULATION -> menu -> down 1 = Velvia -> menu)
            checkPhase(ensureMenuOpen(rt), "Phase 2A failed to open MENU for Velvia")
            tapButton(rt, "down", 85L, 220L) // R2 RAW RECORDING
            tapButton(rt, "down", 85L, 220L) // R3 FILM SIMULATION
            tapButton(rt, "menu", 90L, 340L)
            tapButton(rt, "down", 85L, 260L) // idx=1 Velvia
            tapButton(rt, "menu", 90L, 340L)
            returnToStillHud(rt)
            stepVirtualMs(rt, 450L)

            val (_, lvVelvia) = captureMainMetrics(rt)
            val velviaShot = fireStillAndWait(rt, workRoot, "CONSIST_VELVIA")
            val jpgVelvia = velviaShot.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }
            println("[GAUNTLET][CONSIST][VELVIA] LV(luma=${"%.1f".format(lvVelvia.meanLuma)},chroma=${"%.1f".format(lvVelvia.meanChroma)}) vs JPG($jpgVelvia)")
            checkPhase(jpgVelvia != null && lvVelvia.meanChroma > 5.0 && jpgVelvia.meanChroma > 5.0, "Velvia must be colorful in BOTH LV and JPG")

            // Switch FilmSim -> ACROS STD (remembers R3 FILM SIMULATION -> menu -> idx=1 Velvia + down 9 = idx=10 ACROS -> menu -> menu STD)
            checkPhase(ensureMenuOpen(rt), "Phase 2A failed to open MENU for ACROS")
            tapButton(rt, "menu", 90L, 340L)
            repeat(9) { tapButton(rt, "down", 75L, 210L) }
            tapButton(rt, "menu", 90L, 320L)
            tapButton(rt, "menu", 90L, 350L)
            returnToStillHud(rt)
            stepVirtualMs(rt, 450L)

            val (_, lvAcros) = captureMainMetrics(rt, 0.30, 0.30, 0.70, 0.70)
            val acrosCp = compState(rt, "soc-isp")["colorPipeline"] as? Map<*, *>
            val acrosShot = fireStillAndWait(rt, workRoot, "CONSIST_ACROS")
            val jpgAcros = acrosShot.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }
            println("[GAUNTLET][CONSIST][ACROS] LV(luma=${"%.1f".format(lvAcros.meanLuma)},chroma=${"%.2f".format(lvAcros.meanChroma)},mono=${acrosCp?.get("monochromeMode")}) vs JPG($jpgAcros)")
            checkPhase(acrosCp?.get("monochromeMode") == true && lvAcros.meanChroma < 10.0, "ACROS LiveView must be monochrome (mono=${acrosCp?.get("monochromeMode")} chroma=${lvAcros.meanChroma})")
            checkPhase(jpgAcros != null && jpgAcros.meanChroma < 2.5, "ACROS captured 102MP JPEG must be monochrome (got chroma=${jpgAcros?.meanChroma})")

            // Restore PROVIA (from idx=10 ACROS -> up 10 = idx=0 PROVIA)
            checkPhase(ensureMenuOpen(rt), "Phase 2A failed to open MENU to restore PROVIA")
            tapButton(rt, "menu", 90L, 340L)
            repeat(10) { tapButton(rt, "up", 75L, 210L) }
            tapButton(rt, "menu", 90L, 350L)
            returnToStillHud(rt)
            stepVirtualMs(rt, 400L)

            // 2B: ND8 ON vs OFF in Manual mode (fixed exposure!)
            patchComp(rt, "soc-adc", mapOf("modeDial" to "M"))
            stepVirtualMs(rt, 500L)
            val (_, lvNdOff) = captureMainMetrics(rt)
            val shotNdOff = fireStillAndWait(rt, workRoot, "CONSIST_ND_OFF")
            val jpgNdOff = shotNdOff.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }

            patchComp(rt, "optical-filter", mapOf("enabled" to true, "ndPreset" to "ND8"))
            stepVirtualMs(rt, 500L)
            val (_, lvNd8) = captureMainMetrics(rt)
            val shotNd8 = fireStillAndWait(rt, workRoot, "CONSIST_ND8_ON")
            val jpgNd8 = shotNd8.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }
            println("[GAUNTLET][CONSIST][ND8] OFF: LV=${"%.1f".format(lvNdOff.meanLuma)} JPG=${jpgNdOff?.meanLuma} | ND8: LV=${"%.1f".format(lvNd8.meanLuma)} JPG=${jpgNd8?.meanLuma}")
            checkPhase(lvNd8.meanLuma < lvNdOff.meanLuma * 0.72, "ND8 in M mode must darken LiveView (${lvNdOff.meanLuma} -> ${lvNd8.meanLuma})")
            checkPhase(jpgNdOff != null && jpgNd8 != null && jpgNd8.meanLuma < jpgNdOff.meanLuma * 0.72, "ND8 in M mode must darken captured JPEG (${jpgNdOff?.meanLuma} -> ${jpgNd8?.meanLuma})")

            // Reset ND OFF & modeDial P
            patchComp(rt, "optical-filter", mapOf("ndPreset" to "OFF"))
            patchComp(rt, "soc-adc", mapOf("modeDial" to "P"))
            stepVirtualMs(rt, 500L)

            // 2C: 35mm Adapter framing consistency
            patchComp(rt, "lens", mapOf("template" to "adapter-ef-gfx"))
            stepVirtualMs(rt, 600L)
            val (_, lv35mm) = captureMainMetrics(rt)
            val shot35mm = fireStillAndWait(rt, workRoot, "CONSIST_35MM_ADAPTER")
            val jpg35mm = shot35mm.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it) }
            println("[GAUNTLET][CONSIST][35MM_ADAPTER] LV(luma=${"%.1f".format(lv35mm.meanLuma)}) JPG($jpg35mm)")

            // 2D: Focus Distance / Sharpness consistency on center 2.5m Macbeth+MTF target with GF 110mm F2
            patchComp(rt, "lens", mapOf("template" to "gf110", "aperture" to 2.0, "focusDistanceM" to 2.5))
            stepVirtualMs(rt, 600L)
            val (_, lvFocusIn) = captureMainMetrics(rt, 0.30, 0.30, 0.70, 0.70)
            val shotFocusIn = fireStillAndWait(rt, workRoot, "CONSIST_FOCUS_25M")
            val jpgFocusIn = shotFocusIn.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it, 0.30, 0.30, 0.70, 0.70) }

            patchComp(rt, "lens", mapOf("focusDistanceM" to 12.0))
            stepVirtualMs(rt, 600L)
            val (_, lvFocusOut) = captureMainMetrics(rt, 0.30, 0.30, 0.70, 0.70)
            val shotFocusOut = fireStillAndWait(rt, workRoot, "CONSIST_FOCUS_12M")
            val jpgFocusOut = shotFocusOut.values.firstOrNull { it.toString().endsWith(".JPG", true) }?.let { analyzeJpeg(it, 0.30, 0.30, 0.70, 0.70) }
            println("[GAUNTLET][CONSIST][FOCUS_25M_VS_12M] LV sharp: 2.5m=${"%.2f".format(lvFocusIn.sharpness)} vs 12m=${"%.2f".format(lvFocusOut.sharpness)} | JPG sharp: 2.5m=${jpgFocusIn?.sharpness} vs 12m=${jpgFocusOut?.sharpness}")
            checkPhase(lvFocusIn.sharpness > lvFocusOut.sharpness * 1.05, "LiveView center 2.5m target must be sharper at focus=2.5m than 12m (${lvFocusIn.sharpness} vs ${lvFocusOut.sharpness})")
            checkPhase(jpgFocusIn != null && jpgFocusOut != null && jpgFocusIn.sharpness > jpgFocusOut.sharpness * 1.05, "Captured JPEG center 2.5m target must be sharper at focus=2.5m than 12m (${jpgFocusIn?.sharpness} vs ${jpgFocusOut?.sharpness})")

            patchComp(rt, "lens", mapOf("template" to "gf45-100", "focalLengthMm" to 55.0, "aperture" to 4.0, "focusDistanceM" to 1.55))

            // =========================================================================
            // PHASE 3: LONG VIDEO RECORDING (>= 3.5 vSec) + .MOV / CODEC / AUDIO + PLAYBACK
            // =========================================================================
            patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://milk-frog", "loop" to true))
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 2_800L)
            val (seqPreMov, mPreMov) = captureMainMetrics(rt)
            assertHealthyCamera(rt, "MOVIE_READY")
            checkPhase(mPreMov.nonBlack > 140_000, "Movie LiveView blacked out before REC (nb=${mPreMov.nonBlack})")

            val dcimBeforeMov = listDcimFiles(workRoot).keys
            val recStartTick = rt.telemetry.virtualTick
            val i2sBefore = rt.owner.submit {
                rt.host.ports.find("audio.i2s", I2sAudioPort::class.java)?.playbackStats()?.toString() ?: "null"
            }.join()
            // Start Movie REC
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 220L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

            // Record for >= 4.0 virtual seconds (216M ticks) monitoring frame cadence & health!
            var sawRecScr = false
            var lastRecSeq = seqPreMov
            var recSeqAdvances = 0
            for (step in 1..16) {
                stepVirtualMs(rt, 250L)
                val scr = readU32(rt, 0x01CC6424L)
                if (scr == 0x0208) sawRecScr = true
                val (sNow, _) = captureMainMetrics(rt)
                if (sNow > lastRecSeq) {
                    recSeqAdvances++
                    lastRecSeq = sNow
                }
                assertHealthyCamera(rt, "MOVIE_REC_STEP_$step")
            }
            val recDurationVSec = (rt.telemetry.virtualTick - recStartTick) / 54_000_000.0

            // Stop Movie REC
            patchComp(rt, "buttons", mapOf("shutterHalf" to true))
            stepVirtualMs(rt, 160L)
            patchComp(rt, "buttons", mapOf("shutterFull" to true))
            stepVirtualMs(rt, 180L)
            patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))

            var movFile: Path? = null
            for (w in 0 until 20) {
                stepVirtualMs(rt, 350L)
                val newFiles = listDcimFiles(workRoot).filterKeys { it !in dcimBeforeMov }
                movFile = newFiles.values.firstOrNull { it.toString().endsWith(".MOV", true) }
                if (movFile != null && readU32(rt, 0x01CC6424L) != 0x0208) break
            }
            val i2sAfter = rt.owner.submit {
                rt.host.ports.find("audio.i2s", I2sAudioPort::class.java)?.playbackStats()?.toString() ?: "null"
            }.join()
            val maliSt = compState(rt, "soc-isp")["maliV61"] as? Map<*, *>
            println("[GAUNTLET][MOVIE_REC] saw0208=$sawRecScr durVSec=${"%.2f".format(recDurationVSec)} seqAdv=$recSeqAdvances i2sRx=$i2sBefore->$i2sAfter mali=$maliSt mov=${movFile?.fileName}")
            checkPhase(sawRecScr, "Expected Movie REC screen 0x0208 during long recording")
            checkPhase(recDurationVSec >= 3.5, "Expected >= 3.5 vSec movie recording, got $recDurationVSec")
            checkPhase(recSeqAdvances >= 5, "Display stalled during long video recording (advances=$recSeqAdvances)")
            if (movFile == null) {
                checkPhase(false, "Expected finalized .MOV file on SD1 DCIM after long movie recording")
            } else {
                val movInfo = inspectMov(movFile)
                println("[GAUNTLET][MOV_INSPECT] $movInfo")
                checkPhase(movInfo.sizeBytes > 16_384L, "Expected non-trivial .MOV (>16 KiB), got ${movInfo.sizeBytes} B")
                checkPhase(movInfo.hasFtyp && movInfo.hasMoov && movInfo.hasMdat, "Invalid .MOV box structure: ${movInfo.topBoxes}")
                checkPhase(movInfo.hasVideoTrack, "Expected H.264/HEVC video track in .MOV: $movInfo")
                checkPhase(movInfo.hasAudioTrack, "Expected PCM audio track in .MOV: $movInfo")
            }

            // Enter PLAYBACK, wait past 0x0506 transition for Playback screen 0x03xx, play .MOV & browse photos
            tapButton(rt, "play", 95L, 700L)
            for (pw in 0 until 14) {
                val s = readU32(rt, 0x01CC6424L)
                val (_, m) = captureMainMetrics(rt)
                if (s in 0x0300..0x03FF && m.nonBlack > 80_000) break
                stepVirtualMs(rt, 300L)
            }
            val scrPlay1 = readU32(rt, 0x01CC6424L)
            val (seqPlay1, mPlay1) = captureMainMetrics(rt)
            assertHealthyCamera(rt, "PLAYBACK_ENTER")
            println("[GAUNTLET][PLAYBACK] enter scr=0x${scrPlay1.toString(16)} seq=$seqPlay1 nb=${mPlay1.nonBlack}")
            checkPhase(scrPlay1 in 0x0300..0x03FF || mPlay1.nonBlack > 80_000, "Playback enter failed: scr=0x${scrPlay1.toString(16)} nb=${mPlay1.nonBlack}")

            tapButton(rt, "down", 95L, 750L)
            stepVirtualMs(rt, 1_000L)
            assertHealthyCamera(rt, "PLAYBACK_MOVIE_PLAY")
            tapButton(rt, "up", 95L, 450L)
            repeat(3) {
                tapButton(rt, "left", 85L, 420L)
                assertHealthyCamera(rt, "PLAYBACK_BROWSE_$it")
            }

            // Exit PLAYBACK & switch back to STILL mode
            patchComp(rt, "buttons", mapOf("movieMode" to false))
            tapButton(rt, "play", 95L, 600L)
            returnToStillHud(rt)
            waitForStillLiveView(rt, "AFTER_MOVIE_PLAYBACK")

            // =========================================================================
            // PHASE 4: SLEEP / WAKE -> POST-WAKE STILL CAPTURE -> INPUT STORM -> HEALTH GATE
            // =========================================================================
            patchComp(rt, "buttons", mapOf("power" to false))
            for (i in 0 until 40) {
                if (rt.session.isSocPoweredOff) break
                stepVirtualMs(rt, 450L)
            }
            checkPhase(rt.session.isSocPoweredOff, "Expected graceful Sub-MCU power-off standby in Phase 4")
            if (rt.session.isSocPoweredOff) {
                patchComp(rt, "buttons", mapOf("shutterHalf" to true))
                patchComp(rt, "buttons", mapOf("shutterHalf" to false))
                rt.owner.submit { rt.session.stepFast() }.join()
                checkPhase(rt.session.isSocPoweredOff, "shutterHalf while power=false must NOT wake SoC")

                patchComp(rt, "buttons", mapOf("power" to true))
                rt.owner.submit { rt.session.stepFast() }.join()
                checkPhase(!rt.session.isSocPoweredOff && rt.session.wakeCount == 1L, "Expected power=true wake from standby (off=${rt.session.isSocPoweredOff}, wake=${rt.session.wakeCount})")
                val shoulderSeqPostWake = rt.owner.submit {
                    rt.host.ports.find("display.shoulder", DisplayPort::class.java)?.contentSequence ?: -1L
                }.join()
                checkPhase(shoulderSeqPostWake >= 0L, "display.shoulder must be live immediately after cold-reset wake")

                for (i in 0 until 60) {
                    if (readU32(rt, 0x01CC6424L) in listOf(0x0100, 0x0102) && captureMainMetrics(rt).second.nonBlack > 140_000) break
                    stepVirtualMs(rt, 350L)
                }
                waitForStillLiveView(rt, "POST_WAKE_LIVEVIEW")
                patchComp(rt, "soc-gpio", mapOf("focusMode" to "MF"))
                stepVirtualMs(rt, 450L) // allow MF lever GPIO debounce & SD DCIM scan to settle post-wake
                val postWakeShot = fireStillAndWait(rt, workRoot, "POST_WAKE_STILL", maxWaitMs = 20_000L)
                checkPhase(postWakeShot.keys.any { it.endsWith(".JPG", true) }, "Expected successful still capture after standby wake")
            }

            // 4B: Rapid Dial / Button / Touch Storm
            val touchTarget = rt.owner.submit {
                if (rt.host.components().any { it.descriptor.id == "rear-touch-ic" && it.config["enabled"] == true }) {
                    "rear-touch-ic"
                } else {
                    "i2c-bus"
                }
            }.join()
            val stormStartTick = rt.telemetry.virtualTick
            val (stormSeq0, _) = captureMainMetrics(rt)
            for (i in 1..6) {
                patchComp(rt, "soc-adc", mapOf("rearDialDetents" to i.toLong(), "frontDialDetents" to (-i).toLong()))
                stepVirtualMs(rt, 120L)
                tapTouch(rt, touchTarget, 300 + i * 60, 260 + i * 35, holdMs = 55L, settleMs = 140L)
            }
            patchComp(rt, "soc-adc", mapOf("rearDialDetents" to 0L, "frontDialDetents" to 0L))
            for (btn in listOf("up", "down", "left", "right", "back")) {
                tapButton(rt, btn, holdMs = 70L, settleMs = 150L)
            }
            returnToStillHud(rt)
            stepVirtualMs(rt, 400L)
            val (stormSeq1, mStormEnd) = captureMainMetrics(rt)
            val stormTicks = rt.telemetry.virtualTick - stormStartTick
            assertHealthyCamera(rt, "POST_STORM")
            checkPhase(stormTicks > 0L && stormSeq1 > stormSeq0 && mStormEnd.nonBlack > 140_000, "Camera hung during rapid input storm (ticks=$stormTicks seq=$stormSeq0->$stormSeq1 nb=${mStormEnd.nonBlack})")

            // 4C: Final Zero-Fallback / Zero-Refusal / Timing Gate
            val finalState = rt.owner.submit { rt.session.state() }.join()
            val relaxed = finalState["relaxedFallback"] as? Map<*, *>
            val mmioFallbacks = (relaxed?.get("mmioFallbacks") as? Number)?.toLong() ?: 0L
            val discoveryFallbacks = (relaxed?.get("discoveryFallbacks") as? Number)?.toLong() ?: 0L
            val flashRefusals = (relaxed?.get("flashRefusals") as? Number)?.toLong() ?: 0L
            val report = rt.session.relaxedFeedbackSummaryReport().orEmpty()
            val wallSec = (System.nanoTime() - sessionWallStartNs) / 1_000_000_000.0
            val totalDcim = listDcimFiles(workRoot)
            println(
                "[GAUNTLET][FINAL] anomalies=${anomalies.size} wall=${"%.1f".format(wallSec)}s wakeCount=${rt.session.wakeCount} " +
                    "dcimFiles=${totalDcim.size} (JPG=${totalDcim.keys.count { it.endsWith(".JPG", true) }}, " +
                    "RAF=${totalDcim.keys.count { it.endsWith(".RAF", true) }}, MOV=${totalDcim.keys.count { it.endsWith(".MOV", true) }}) " +
                    "mmioFallbacks=$mmioFallbacks discoveryFallbacks=$discoveryFallbacks flashRefusals=$flashRefusals",
            )
            if (report.isNotBlank()) println("[GAUNTLET][RELAXED_REPORT]\n$report")
            checkPhase(mmioFallbacks == 0L, "Expected ZERO [RELAXED/MMIO] fallbacks across entire gauntlet (got $mmioFallbacks)")
            checkPhase(discoveryFallbacks == 0L, "Expected ZERO discoveryFallbacks across entire gauntlet (got $discoveryFallbacks)")
            checkPhase(flashRefusals == 0L, "Expected ZERO [RELAXED/FLASH] refusals across entire gauntlet (got $flashRefusals)")
            assertTrue(anomalies.isEmpty(), "Gauntlet caught ${anomalies.size} anomalies:\n" + anomalies.joinToString("\n - ", prefix = " - "))
        }
    }

    internal fun withLiveImagingSession(
        tempRoot: Path,
        directory: String,
        run: (EmulatorRuntime, Path) -> Unit,
    ) {
        assumeTrue(
            System.getenv("FUJI_RUN_V030_GAUNTLET") == "1" || System.getProperty("fuji.runV030Gauntlet") == "1",
            "Set FUJI_RUN_V030_GAUNTLET=1 to execute live imaging verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(Files.isDirectory(stagedRoot), "Requires :stageReleaseLinux output at $stagedRoot")

        val workRoot = tempRoot.resolve(directory)
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)
        Files.deleteIfExists(workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json"))
        val slot1Dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (Files.isDirectory(slot1Dcim)) {
            Files.walk(slot1Dcim).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        val config = LinkedHashMap(Formats.read(workRoot.resolve("configs/emulator.yaml"))).apply { put("realTimePacer", false) }

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepVirtualTicks(rt, 620_000_000L)
            tapButton(rt, "menu", 95L, 750L)
            tapButton(rt, "down", 95L, 420L)
            tapButton(rt, "down", 95L, 420L)
            tapButton(rt, "menu", 95L, 750L)
            tapButton(rt, "up", 85L, 280L)
            tapButton(rt, "menu", 95L, 750L)
            tapButton(rt, "menu", 95L, 700L)
            waitForStillLiveView(rt, directory)

            patchComp(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            patchComp(rt, "cmos", mapOf("sourceKind" to "optical-scene", "sourceFile" to "scene://depth-studio", "loop" to true))
            stepVirtualMs(rt, 500L)

            run(rt, workRoot)
        }
    }

    @Test
    fun writeProtectedCaptureRecoversInPlaceAfterFirmwareWarning(@TempDir tempRoot: Path) {
        withLiveImagingSession(tempRoot, "sd-write-protect-recovery") { rt, workRoot ->
            patchComp(rt, "sdcard-reader", mapOf("slot2Inserted" to false))
            stepVirtualMs(rt, 850L)
            settledPreview(rt)
            val original = fireStillAndWait(rt, workRoot, "writable").values.single {
                it.fileName.toString().endsWith(".JPG", ignoreCase = true)
            }
            val reference = Files.copy(original, tempRoot.resolve("baseline.jpg"))

            patchComp(rt, "sdcard-reader", mapOf("slot1ReadOnly" to true))
            stepVirtualMs(rt, 1_200L)
            assertTrue(fireStillAndWait(rt, workRoot, "protected", maxWaitMs = 9_000L).isEmpty())
            patchComp(rt, "sdcard-reader", mapOf("slot1ReadOnly" to false))

            // Sample the solid red edge of the firmware WRITE ERROR icon over the fixed depth-studio scene.
            // The screen ID stays at normal live view while this overlay blocks shutter input.
            val iconSamples = listOf(120 to 370, 125 to 380, 135 to 380, 147 to 380, 166 to 382)
            val display = rt.owner.submit {
                requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
            }.join()
            val pixels = IntArray(display.width * display.height)
            fun writeErrorVisible() = rt.owner.submit {
                check(display.copyArgb(pixels))
                iconSamples.all { (x, y) ->
                    val color = pixels[(y * display.height / 768) * display.width + x * display.width / 1024]
                    ((color ushr 16) and 255) > 140 && ((color ushr 8) and 255) < 40 && (color and 255) < 40
                }
            }.join()

            var sawWarning = false
            var warningCleared = false
            for (poll in 0..100) {
                if (writeErrorVisible()) {
                    sawWarning = true
                } else if (sawWarning) {
                    warningCleared = true
                    break
                }
                stepVirtualMs(rt, 200L)
            }
            assertTrue(sawWarning, "Firmware must display the write error on the protected capture")
            assertTrue(warningCleared, "Firmware must clear the warning with the same card inserted")
            val recovered = fireStillAndWait(rt, workRoot, "first-after-warning")
            assertTrue(recovered.keys.any { it.endsWith(".JPG", ignoreCase = true) })
            assertEquals(-1L, Files.mismatch(reference, original), "The previous JPEG must stay byte-identical")
        }
    }

    @Test
    fun indexPlaybackAndMovieStopPreserveStillCapture(@TempDir tempRoot: Path) {
        withLiveImagingSession(tempRoot, "index-movie-still") { rt, workRoot ->
            fun screen() = readU32(rt, 0x01CC6424L)
            fun waitForScreen(vararg allowed: Int) {
                repeat(72) {
                    if (screen() in allowed) return
                    stepVirtualMs(rt, 125L)
                }
                error("Expected ${allowed.joinToString { it.toString(16) }}, saw ${screen().toString(16)}")
            }
            fun capture(label: String) {
                var reference = emptyList<FilmSimChartComparison.Sample>()
                val files = fireStillAndWait(rt, workRoot, label, onHalfPress = {
                    reference = FilmSimChartComparison.lockedPreview(rt)
                })
                val jpeg = files.values.single { it.toString().endsWith(".JPG", true) }
                val contrast = reference.maxOf { it.y } - reference.minOf { it.y }
                assertTrue(contrast > 60, "$label locked a flat chart with only $contrast luma levels")
                FilmSimChartComparison.checkJpeg(label, reference, jpeg)
            }

            settledPreview(rt)
            tapButton(rt, "menu", 100L, 450L)
            tapButton(rt, "right", 100L, 350L)
            tapButton(rt, "down", 100L, 350L)
            tapButton(rt, "back", 100L, 350L)
            returnToStillHud(rt)
            val originalFiles = listDcimFiles(workRoot).keys
            tapButton(rt, "shutterHalf", 220L, 250L)
            assertEquals(originalFiles, listDcimFiles(workRoot).keys)
            capture("before-index")
            stepVirtualMs(rt, 500L)
            tapButton(rt, "play", 95L, 750L)
            waitForScreen(0x0300)
            var detents = (compState(rt, "soc-adc").getValue("rearDialDetents") as Number).toLong()
            for ((delta, expected) in listOf(-1 to 0x030A, -1 to 0x030A, 1 to 0x030A, 1 to 0x0300)) {
                detents += delta
                patchComp(rt, "soc-adc", mapOf("rearDialDetents" to detents))
                stepVirtualMs(rt, 700L)
                assertEquals(expected, screen(), "rear dial detent $detents")
            }
            tapButton(rt, "play", 95L, 400L)
            waitForScreen(0x0100, 0x0102)
            capture("after-index")

            fun shutter() {
                patchComp(rt, "buttons", mapOf("shutterHalf" to true))
                stepVirtualMs(rt, 160L)
                patchComp(rt, "buttons", mapOf("shutterFull" to true))
                stepVirtualMs(rt, 180L)
                patchComp(rt, "buttons", mapOf("shutterFull" to false, "shutterHalf" to false))
            }
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 2700L)
            shutter()
            waitForScreen(0x0208)
            patchComp(rt, "buttons", mapOf("movieMode" to false))
            stepVirtualMs(rt, 180L)
            if (screen() == 0x0208) shutter()
            stepVirtualMs(rt, 30L)
            patchComp(rt, "buttons", mapOf("movieMode" to true))
            stepVirtualMs(rt, 90L)
            patchComp(rt, "buttons", mapOf("movieMode" to false))
            stepVirtualMs(rt, 1700L)
            repeat(85) {
                stepVirtualMs(rt, 20L)
                val center = captureMainMetrics(rt, 0.30, 0.30, 0.70, 0.70).second
                assertFalse(center.meanLuma > 245 && center.stdLuma < 18, "White frame while switching to STILL: $center")
            }
            waitForScreen(0x0100, 0x0102)
            assertTrue(listDcimFiles(workRoot).keys.any { it.endsWith(".MOV", true) })
            capture("after-fast-movie-stop")
        }
    }

    @Test
    fun afOnAndLightingTransitionsKeepPreviewLive(@TempDir tempRoot: Path) {
        withLiveImagingSession(tempRoot, "af-lighting") { rt, _ ->
            patchComp(rt, "soc-gpio", mapOf("focusMode" to "AF-S"))
            val reference = settledPreview(rt)
            assertTrue(reference.meanChroma > 10.0)
            fun observe(label: String, slices: Int, expectColor: Boolean) {
                val firstSequence = captureMainMetrics(rt).first
                var previousSequence = firstSequence
                var staleSlices = 0
                val elapsedMs = ArrayList<Double>(slices)
                repeat(slices) {
                    val start = System.nanoTime()
                    stepVirtualMs(rt, 50L)
                    elapsedMs += (System.nanoTime() - start) / 1_000_000.0
                    val (seq, metrics) = captureMainMetrics(rt)
                    if (seq == previousSequence) staleSlices++
                    previousSequence = seq
                    if (expectColor) {
                        assertTrue(metrics.meanChroma > 5.0 && metrics.stdLuma > 10.0,
                            "$label produced a flat or desaturated preview at sequence $seq: $metrics")
                    }
                }
                elapsedMs.sort()
                println("[AF-LIGHT] $label slices=$slices stale=$staleSlices " +
                    "medianMs=${elapsedMs[slices / 2]} maxMs=${elapsedMs.last()}")
                assertTrue(previousSequence > firstSequence, "$label failed to publish a fresh preview")
                assertHealthyCamera(rt, label)
            }
            observe("baseline", 8, true)
            patchComp(rt, "buttons", mapOf("afOn" to true))
            observe("tap", 3, true)
            patchComp(rt, "buttons", mapOf("afOn" to false))
            observe("release", 15, true)
            patchComp(rt, "buttons", mapOf("afOn" to true))
            observe("hold", 36, true)
            patchComp(rt, "buttons", mapOf("afOn" to false))
            observe("after-hold", 10, true)
            patchComp(rt, "optical-filter", mapOf("ndStops" to 6.0))
            observe("darken", 30, false)
            patchComp(rt, "optical-filter", mapOf("ndStops" to 0.0))
            observe("brighten", 30, false)
            assertTrue(settledPreview(rt).meanChroma > 10.0)
        }
    }

    @Test
    fun repeatedAfOnPreservesSceneColorAfterSwitchingFromSingleToContinuousAf(@TempDir tempRoot: Path) {
        withLiveImagingSession(tempRoot, "af-on-color") { rt, _ ->
            patchComp(rt, "soc-adc", mapOf("modeDial" to "P"))
            fun observe(samples: Int, label: String) {
                repeat(samples) { sample ->
                    stepVirtualMs(rt, 50L)
                    // Sample the textured wall outside the AF box and firmware display overlays.
                    val (sequence, exterior) = captureMainMetrics(rt, 0.15, 0.20, 0.34, 0.70)
                    assertTrue(exterior.meanChroma > 5.0 && exterior.stdLuma > 10.0,
                        "$label sample=$sample sequence=$sequence lost texture or color: $exterior")
                }
            }

            for (mode in listOf("AF-S", "AF-C")) {
                patchComp(rt, "soc-gpio", mapOf("focusMode" to mode))
                stepVirtualMs(rt, 1_400L)
                observe(6, "$mode baseline")
                repeat(2) { tap ->
                    patchComp(rt, "buttons", mapOf("afOn" to true))
                    observe(3, "$mode press $tap")
                    patchComp(rt, "buttons", mapOf("afOn" to false))
                    observe(8, "$mode release $tap")
                }
                patchComp(rt, "buttons", mapOf("afOn" to true))
                observe(23, "$mode hold")
                patchComp(rt, "buttons", mapOf("afOn" to false))
                observe(7, "$mode after hold")
                if (mode == "AF-S") {
                    patchComp(rt, "optical-filter", mapOf("ndStops" to 6.0))
                    repeat(3) { stepVirtualMs(rt, 50L) }
                    patchComp(rt, "buttons", mapOf("afOn" to true))
                    repeat(4) { stepVirtualMs(rt, 50L) }
                    patchComp(rt, "optical-filter", mapOf("ndStops" to 0.0))
                    patchComp(rt, "buttons", mapOf("afOn" to false))
                    repeat(15) { stepVirtualMs(rt, 50L) }
                }
                assertHealthyCamera(rt, "$mode AF-ON")
            }
        }
    }

    @Test
    fun liveFilmSimPreviewVs102MpCaptureParity(@TempDir tempRoot: Path) {
        withLiveImagingSession(tempRoot, "v030-filmsim-parity") { rt, workRoot ->
            fun captureFilm(label: String): ImageMetrics {
                var reference = emptyList<FilmSimChartComparison.Sample>()
                val files = fireStillAndWait(rt, workRoot, label, onHalfPress = {
                    reference = FilmSimChartComparison.lockedPreview(rt)
                })
                val jpeg = files.values.single { it.toString().endsWith(".JPG", true) }
                FilmSimChartComparison.checkJpeg(label, reference, jpeg)
                return analyzeJpeg(jpeg)
            }

            // 1) PROVIA
            val lvProvia = settledPreview(rt)
            val jpgProvia = captureFilm("PARITY_PROVIA")

            // 2) Velvia (I.Q. R0 -> down 3 = R3 FILM SIMULATION -> menu -> down 1 = Velvia -> menu)
            assertTrue(ensureMenuOpen(rt))
            repeat(3) { tapButton(rt, "down", 85L, 220L) }
            tapButton(rt, "menu", 90L, 340L)
            tapButton(rt, "down", 85L, 260L)
            tapButton(rt, "menu", 90L, 340L)
            returnToStillHud(rt)
            stepVirtualMs(rt, 450L)
            val (_, lvVelvia) = captureMainMetrics(rt)
            val jpgVelvia = captureFilm("PARITY_VELVIA")

            // 3) ACROS STD (remembers R3 FILM SIMULATION -> menu -> down 9 = ACROS -> menu -> menu STD)
            assertTrue(ensureMenuOpen(rt))
            tapButton(rt, "menu", 90L, 340L)
            repeat(9) { tapButton(rt, "down", 75L, 210L) }
            tapButton(rt, "menu", 90L, 320L)
            tapButton(rt, "menu", 90L, 350L)
            returnToStillHud(rt)
            stepVirtualMs(rt, 450L)
            val (_, lvAcros) = captureMainMetrics(rt, 0.30, 0.30, 0.70, 0.70)
            val acrosCp = compState(rt, "soc-isp")["colorPipeline"] as? Map<*, *>
            val jpgAcros = captureFilm("PARITY_ACROS")

            println(
                "[GAUNTLET][FILMSIM_PARITY] PROVIA(LV=${"%.1f".format(lvProvia.meanChroma)},JPG=${"%.1f".format(jpgProvia.meanChroma)}) " +
                    "Velvia(LV=${"%.1f".format(lvVelvia.meanChroma)},JPG=${"%.1f".format(jpgVelvia.meanChroma)}) " +
                    "ACROS(LV=${"%.2f".format(lvAcros.meanChroma)},mono=${acrosCp?.get("monochromeMode")},JPG=${"%.2f".format(jpgAcros.meanChroma)})",
            )
            assertTrue(lvProvia.meanChroma > 5.0, "PROVIA LiveView must preserve scene color (got ${lvProvia.meanChroma})")
            assertTrue(jpgProvia.meanChroma > 15.0, "PROVIA 102MP JPEG must preserve color chroma (got ${jpgProvia.meanChroma})")
            assertTrue(jpgVelvia.meanChroma > jpgProvia.meanChroma * 1.05, "Velvia 102MP JPEG must boost chroma over PROVIA (${jpgProvia.meanChroma} -> ${jpgVelvia.meanChroma})")
            assertEquals(true, acrosCp?.get("monochromeMode"), "ACROS ISP colorPipeline must enter monochromeMode=true")
            assertTrue(lvAcros.meanChroma < lvVelvia.meanChroma * 0.15, "ACROS LiveView must drop scene chroma >=85% vs Velvia (${lvVelvia.meanChroma} -> ${lvAcros.meanChroma})")
            assertTrue(jpgAcros.meanChroma < 2.5, "ACROS 102MP JPEG must be pure monochrome (got ${jpgAcros.meanChroma})")

            patchComp(rt, "soc-adc", mapOf("modeDial" to "M"))
            stepVirtualMs(rt, 500L)
            var dial = 0
            fun captureAtDial(target: Int): Pair<Double, Double> {
                while (dial != target) {
                    dial += if (target > dial) 1 else -1
                    patchComp(rt, "soc-adc", mapOf("rearDialDetents" to dial.toLong()))
                    stepVirtualMs(rt, 100L)
                }
                val preview = settledPreview(rt)
                val photo = captureFilm("MANUAL_SHUTTER_$target")
                return preview.meanLuma to photo.meanLuma
            }
            val (lvA, photoA) = captureAtDial(-4)
            val (lvB, photoB) = captureAtDial(4)
            val previewDelta = lvB - lvA
            val photoDelta = photoB - photoA
            println("[GAUNTLET][MANUAL_SHUTTER] preview $lvA -> $lvB; decoded JPEG $photoA -> $photoB")
            assertTrue(abs(previewDelta) > 15.0 && abs(photoDelta) > 15.0 && previewDelta * photoDelta > 0,
                "Manual shutter changed preview by $previewDelta and JPEG by $photoDelta")

        }
    }
}
