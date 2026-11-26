package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ButtonPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.desktop.panels.MomentaryHoldPolicy
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Automated end-to-end Release interactive smoke test:
 * 1. Cold-boots an isolated copy of the staged Linux release bundle at native 1024x768 to `START 言語/LANG. 1/5`.
 * 2. Scrolls into Page 2/5 (`中文简`) to verify CJK Flash font DMA completes with zero freeze, then completes
 *    full OOBE (`Language -> Area Setting -> Date/Time -> Start Application [OK] END`).
 * 3. Verifies autonomous transition into Live 1024x768 Shooting HUD (`Scr = 0x0100`, live PIC1 + OSD0 HUD).
 * 4. Switches `power = false` and verifies authentic firmware shutdown blanks `display.main` to pure black.
 * 5. Restarts on the same runtime copy and verifies `RelaxedFlashPersistence` restores `power = true` cleanly.
 */
class ReleaseInteractiveSmokeTest {
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

    private fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long): Long {
        val start = rt.owner.submit { (rt.session.state()["attempts"] as Number).toLong() }.join()
        val target = start + deltaAttempts
        var current = start
        while (current < target) {
            current = rt.owner.submit {
                var i = 0
                while (i < 2048) {
                    if (!rt.session.stepFast()) break
                    i++
                }
                (rt.session.state()["attempts"] as Number).toLong()
            }.join()
            val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            if (status != "running") break
        }
        return current
    }

    private fun stepVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long): Long {
        val target = rt.telemetry.virtualTick + deltaTicks
        while (rt.telemetry.virtualTick < target) {
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 64) {
                    if (!rt.session.stepFast()) return@submit false
                    if (rt.telemetry.virtualTick >= target) return@submit true
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
        return rt.telemetry.virtualTick
    }

    private fun patchButton(rt: EmulatorRuntime, key: String, down: Boolean) {
        rt.owner.submit {
            val btn = rt.host.components().single { it.descriptor.id == "buttons" }
            rt.host.patch("buttons", btn.revision, mapOf(key to down)).join()
        }.join()
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

    private fun waitForScreen(
        rt: EmulatorRuntime,
        maxChunks: Int,
        chunkAttempts: Long,
        predicate: (Int) -> Boolean,
    ): Int {
        var scr = readGuestU32(rt, 0x01CC6424L)
        if (predicate(scr)) return scr
        for (i in 0 until maxChunks) {
            stepBy(rt, chunkAttempts)
            val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            assertEquals("running", status, "Emulator must stay running while waiting for screen transition (chunk=$i)")
            scr = readGuestU32(rt, 0x01CC6424L)
            if (predicate(scr)) return scr
        }
        return scr
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdVirtualTicks: Long, settleVirtualTicks: Long) {
        patchButton(rt, key, true)
        stepVirtualTicks(rt, holdVirtualTicks)
        patchButton(rt, key, false)
        stepVirtualTicks(rt, settleVirtualTicks)
    }

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00FFFFFF) != 0 }

    private fun countUniqueColors(pixels: IntArray, limit: Int = 256): Int {
        val colors = HashSet<Int>(limit * 2)
        for (p in pixels) {
            colors.add(p and 0x00FFFFFF)
            if (colors.size >= limit) break
        }
        return colors.size
    }

    private fun countDiffPixels(a: IntArray, b: IntArray): Int {
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        return diff
    }

    @Test
    fun stagedReleaseColdBootsRespondsToDownMenuAndBlanksOnPowerOff(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isDirectory(stagedRoot.resolve("logs")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI/DSCF0001.JPG")),
            "Staged release Slot 1 directory must contain sample DSCF0001.JPG",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/FFDB/FFXFER.DAT")),
            "Staged release Slot 1 directory must contain FFDB/FFXFER.DAT",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-2/FFDB/FFXFER.DAT")),
            "Staged release Slot 2 directory must contain FFDB/FFXFER.DAT",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("configs/examples/emulator-relaxed-default.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/examples/sdcard-reader-directory-dual.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/examples/cmos-ffmpeg-camera-video.yaml")),
            "Staged release must include configs/examples/*.yaml mode presets",
        )

        val workRoot = tempRoot.resolve("release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot to START 言語/LANG. 1/5 at native 1024x768 resolution
            //    Allow dual FAT32 directory SD cards (Slot 1 + Slot 2) to complete boot mount & settle at 0x0421
            stepBy(rt, 580_000_000L)
            waitForScreen(rt, 25, 20_000_000L) { it == 0x0421 }
            val bootState = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", bootState["status"].toString(), "Cold boot must stay running: state=$bootState")
            val (bootSeq, bootDims, bootPixels) = captureMain(rt)
            assertEquals(1024 to 768, bootDims, "Expected native 1024x768 display.main dimensions")
            assertTrue(bootSeq >= 1L, "Expected language screen frame seq >= 1, got $bootSeq")
            val bootNonBlack = countNonBlack(bootPixels)
            assertTrue(bootNonBlack > 100_000, "Expected non-black language UI, got $bootNonBlack")
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")

            // 2. Press DOWN for the calibrated single-step virtual-tick window and verify EXACTLY 1 row move (no auto-repeat!)
            val calibratedHoldTicks = MomentaryHoldPolicy.CALIBRATED_SINGLE_STEP_VIRTUAL_TICKS
            patchButton(rt, "down", true)
            stepVirtualTicks(rt, calibratedHoldTicks)
            patchButton(rt, "down", false)
            stepVirtualTicks(rt, 15_000_000L)
            val (downSeq, _, downPixels) = captureMain(rt)
            assertEquals(
                bootSeq + 1L,
                downSeq,
                "Expected calibrated single-step DOWN ($calibratedHoldTicks vt) to publish EXACTLY 1 LCD redraw (no auto-repeat! boot=$bootSeq, afterDown=$downSeq)",
            )
            val downDiff = countDiffPixels(bootPixels, downPixels)
            assertTrue(downDiff > 5_000, "Expected DOWN highlight move to change >5000 pixels, got $downDiff")

            // Verify AsyncDisplayBridge publishes and delivers the live frame cleanly with zero extra allocations
            val publishedCount = rt.owner.submit { rt.displayBridge.publishAll(rt.host.ports) }.join()
            assertTrue(publishedCount >= 1, "Expected AsyncDisplayBridge to publish display.main")
            var bridgeSeq = 0L
            assertTrue(
                rt.displayBridge.consumeLatest("display.main") { w, h, seq, px ->
                    bridgeSeq = seq
                    assertTrue(w > 0 && h > 0 && px.size == w * h)
                    assertEquals(0, countDiffPixels(downPixels, px))
                },
            )
            assertEquals(downSeq, bridgeSeq)

            // 3. Press MENU/OK for the calibrated single-step window and verify EXACTLY 1 screen advance (Language -> Area)
            patchButton(rt, "menu", true)
            stepVirtualTicks(rt, calibratedHoldTicks)
            patchButton(rt, "menu", false)
            stepVirtualTicks(rt, 40_000_000L)
            val (menuSeq, _, menuPixels) = captureMain(rt)
            assertEquals(
                downSeq + 1L,
                menuSeq,
                "Expected calibrated single-step MENU/OK ($calibratedHoldTicks vt) to advance EXACTLY 1 screen (down=$downSeq, afterMenu=$menuSeq)",
            )
            val menuDiff = countDiffPixels(downPixels, menuPixels)
            assertTrue(menuDiff > 10_000, "Expected MENU/OK to advance screen (>10000 changed pixels), got $menuDiff")
            assertEquals(0x0423, readGuestU32(rt, 0x01CC6424L), "Expected Area Setting screen 0x0423")

            // Verify lock-free telemetry observed all 6 firmware milestones through live interactive UI
            val reachedMask = rt.telemetry.stageMask
            for (stage in FirmwareStage.entries) {
                assertTrue(
                    (reachedMask and stage.bit) != 0,
                    "Expected telemetry milestone ${stage.label} to be reached (mask=0x${reachedMask.toString(16)})",
                )
            }
            assertTrue(rt.telemetry.ownerHeartbeatNanos > 0L, "Expected non-zero owner heartbeat")
            assertTrue(rt.telemetry.publishedFrames >= menuSeq, "Expected telemetry publishedFrames >= $menuSeq")

            // 4. Switch power=false and verify real firmware shutdown unmounts both SD cards and blanks LCD to pure black
            patchButton(rt, "power", false)
            var offSeq = menuSeq
            var offNonBlack = Int.MAX_VALUE
            var offDiag = ""
            for (chunk in 0 until 6) {
                stepBy(rt, 30_000_000L)
                val (seq, _, px) = captureMain(rt)
                offSeq = seq
                offNonBlack = countNonBlack(px)
                val scr = readGuestU32(rt, 0x01CC6424L)
                offDiag = "chunk=$chunk scr=0x${scr.toString(16)} seq=$seq nonBlack=$offNonBlack colors=${countUniqueColors(px, 16)} px0=0x${px[0].toUInt().toString(16)}"
                if (offSeq > menuSeq && offNonBlack == 0) break
            }
            if (offNonBlack != 0) {
                val deep = rt.owner.submit {
                    val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                    fun r8(a: Long): Int { val b = ByteBuffer.allocate(1); mem.read(a, b); return b.get(0).toInt() and 0xff }
                    fun r16(a: Long): Int { val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getShort(0).toInt() and 0xffff }
                    fun r32(a: Long): Long { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getInt(0).toLong() and 0xffffffffL }
                    val sink = rt.host.ports.find("soc-gic.lines", com.github.nyabanban.fuji.emulator.api.InterruptSink::class.java) as? com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
                    val gicEn = if (sink != null) (32..319).filter { sink.lineEnabled(it) } else emptyList()
                    val rel = rt.session.relaxedFeedbackSummaryReport()
                    val mountSt = rt.host.components().single { it.descriptor.id == "mount" }.component.state()
                    val spiSt = rt.host.components().single { it.descriptor.id == "soc-spi" }.component.state()
                    "prev=0x${r32(0x01CC6420L).toString(16)} cur=0x${r32(0x01CBFFD4L).toString(16)} bDsc=${r8(0x01CBFCA2L)} errno=0x${r16(0x01CC0628L).toString(16)} " +
                        "ch21=(req=0x${r32(0x02D77FE8L).toString(16)},in=0x${r32(0x02D77FECL).toString(16)},done=0x${r32(0x02D77FF0L).toString(16)}) " +
                        "biscom=${r8(0x01C66974L)} lenscom=${r8(0x01A337F4L)} lensMgr=${r8(0x01BE013CL)} gic=$gicEn " +
                        "mount=${mountSt["protocolProfile"]}/rx=${mountSt["frame32Count"]} spi0=${(spiSt["controllers"] as? Map<*, *>)?.get("0")} relaxed=$rel"
                }.join()
                offDiag += " | $deep"
            }
            assertTrue(offSeq > menuSeq, "Expected power=false to publish blanking frame (menu=$menuSeq, afterOff=$offSeq, $offDiag)")
            assertEquals(0, offNonBlack, "Expected pure black LCD after power=false shutdown ($offDiag)")
        }

        // 5. Cold restart on the same release root copy must auto-restore power=true via RelaxedFlashPersistence
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { restarted ->
            val powerOn = restarted.owner.submit {
                requireNotNull(restarted.host.ports.find("buttons", ButtonPort::class.java)).pressed("power")
            }.join()
            assertTrue(powerOn, "Relaxed mode restart must recover power=true even after prior power-off")
        }
    }

    @Test
    fun stagedReleaseScrollsCjkPageAndCompletesOobeToLive1024x768ShootingHud(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isDirectory(stagedRoot.resolve("logs")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM/100_FUJI/DSCF0001.JPG")),
            "Staged release Slot 1 directory must contain sample DSCF0001.JPG",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/FFDB/FFXFER.DAT")),
            "Staged release Slot 1 directory must contain FFDB/FFXFER.DAT",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-2/FFDB/FFXFER.DAT")),
            "Staged release Slot 2 directory must contain FFDB/FFXFER.DAT",
        )
        assertTrue(
            Files.isRegularFile(stagedRoot.resolve("configs/examples/emulator-relaxed-default.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/examples/sdcard-reader-directory-dual.yaml")) &&
                Files.isRegularFile(stagedRoot.resolve("configs/examples/cmos-ffmpeg-camera-video.yaml")),
            "Staged release must include configs/examples/*.yaml mode presets",
        )

        val workRoot = tempRoot.resolve("release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot to START 言語/LANG. 1/5 at native 1024x768 resolution
            //    Allow dual FAT32 directory SD cards (Slot 1 + Slot 2) to complete boot mount & settle at 0x0421
            stepBy(rt, 580_000_000L)
            waitForScreen(rt, 25, 20_000_000L) { it == 0x0421 }
            val bootState = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", bootState["status"].toString(), "Cold boot must stay running: state=$bootState")
            val (bootSeq, bootDims, bootPixels) = captureMain(rt)
            assertEquals(1024 to 768, bootDims, "Expected native 1024x768 display.main dimensions")
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Expected Language OOBE screen 0x0421")

            // 2. Scroll down 8 rows into Page 2/5 (triggers CJK font DMA 0x027a0000 -> 0x009a0000..0x00b00000)
            for (step in 0 until 8) {
                tapButton(rt, "down", 5_000_000L, 8_000_000L)
            }
            val (downSeq, _, downPixels) = captureMain(rt)
            val afterDownState = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", afterDownState["status"].toString(), "Scroll must stay running: state=$afterDownState")
            assertTrue(downSeq > bootSeq, "Expected DOWN scrolls to publish new LCD frames (boot=$bootSeq, afterDown=$downSeq)")
            assertTrue(countDiffPixels(bootPixels, downPixels) > 5_000, "Expected Page 2/5 scroll to change >5000 pixels")

            // 3. Complete OOBE: Language -> Area Setting (down x2 -> SET) -> Date/Time -> Start Application [OK] END
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0423, readGuestU32(rt, 0x01CC6424L), "Expected Area Setting screen 0x0423")
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0422, readGuestU32(rt, 0x01CC6424L), "Expected Date/Time screen 0x0422")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0482, readGuestU32(rt, 0x01CC6424L), "Expected Start Application screen 0x0482")
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            // 4. Advance autonomously into Live 1024x768 Shooting HUD (Scr=0x0100, PIC1 + OSD0 HUD overlay)
            var hudReached = false
            var hudSeq = 0L
            var lastDiag = ""
            for (iter in 0 until 25) {
                stepBy(rt, 6_000_000L)
                val status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
                assertEquals("running", status, "Emulator must remain running during post-OOBE live-view bringup (iter=$iter)")
                val scr = readGuestU32(rt, 0x01CC6424L)
                val (seq, _, px) = captureMain(rt)
                val nonBlack = countNonBlack(px)
                val colors = countUniqueColors(px)
                lastDiag = "iter=$iter scr=0x${scr.toString(16)} seq=$seq nonBlack=$nonBlack colors=$colors tick=${rt.telemetry.virtualTick}"
                if (scr == 0x0100 && nonBlack > 150_000 && colors >= 40) {
                    hudReached = true
                    hudSeq = seq
                    break
                }
            }
            if (!hudReached) {
                val extra = rt.owner.submit {
                    val evf = rt.host.ports.find("display.evf", DisplayPort::class.java)
                    val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)
                    val lcdcStr = if (lcdc == null) "null" else "008=0x${lcdc.read(0x008, 4).toString(16)},030=0x${lcdc.read(0x030, 4).toString(16)},044=0x${lcdc.read(0x044, 4).toString(16)},168=0x${lcdc.read(0x168, 4).toString(16)}"
                    val sink = rt.host.ports.find("soc-gic.lines", com.github.nyabanban.fuji.emulator.api.InterruptSink::class.java) as? com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
                    val irqs = listOf(66, 67, 100, 102, 106, 108, 117, 118, 128, 132, 133, 296).filter { sink?.lineEnabled(it) == true }
                    val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
                    fun r8(a: Long): Int { val b = ByteBuffer.allocate(1); mem.read(a, b); return b.get(0).toInt() and 0xff }
                    fun r16(a: Long): Int { val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getShort(0).toInt() and 0xffff }
                    fun r32(a: Long): Long { val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN); mem.read(a, b); return b.getInt(0).toLong() and 0xffffffffL }
                    val gicAll = if (sink != null) (32..319).filter { sink.lineEnabled(it) } else emptyList()
                    val mountSt = rt.host.components().single { it.descriptor.id == "mount" }.component.state()
                    val ispSt = rt.host.components().single { it.descriptor.id == "soc-isp" }.component.state()
                    val isp69100 = rt.host.ports.find("soc-isp.config-69100", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)
                    val r69108 = isp69100?.read(0x008, 4) ?: -1L
                    val r6910c = isp69100?.read(0x00c, 4) ?: -1L
                    val ispCmdSt = rt.host.components().single { it.descriptor.id == "soc-isp-cmd" }.component.state()
                    val spiSt = rt.host.components().single { it.descriptor.id == "soc-spi" }.component.state()
                    val rel = rt.session.relaxedFeedbackSummaryReport()
                    val dmaSt = rt.host.components().single { it.descriptor.id == "soc-dma" }.component.state()
                    "evfSeq=${evf?.sequence} lcdc($lcdcStr) gic=$gicAll eye=(sts=${r8(0x01C98480L)},ui=${r8(0x01CC000AL)},mode=${r8(0x01C9840EL)}) " +
                        "T79=0x${r32(0x035059B8L).toString(16)} T80=0x${r32(0x032BD294L).toString(16)} T74=0x${r32(0x02C46BFCL).toString(16)} " +
                        "bDsc=${r8(0x01CBFCA2L)} errno=0x${r16(0x01CC0628L).toString(16)} " +
                        "ch21=(req=0x${r32(0x02D77FE8L).toString(16)},in=0x${r32(0x02D77FECL).toString(16)},done=0x${r32(0x02D77FF0L).toString(16)}) " +
                        "reccf=(M0=${r16(0x02D6F838L)},M2=${r16(0x02D6F83CL)}) " +
                        "biscom=${r8(0x01C66974L)}(10=${r8(0x01C66984L)},11=${r8(0x01C66985L)},14=0x${r16(0x01C66988L).toString(16)},1f6=0x${r16(0x01C66B6AL).toString(16)}) " +
                        "lenscom=${r8(0x01A337F4L)} lensMgr=${r8(0x01BE013CL)} " +
                        "69108=0x${r69108.toString(16)} 6910c=0x${r6910c.toString(16)} isp106=${ispSt["irq106"]} isp296=${ispSt["irq296Pending"]} " +
                        "ispCmd=${ispCmdSt["committedReplyCount"]}/${ispCmdSt["replyCount"]} spiEv=${spiSt["eventCount"]} mount=${mountSt["spiBridge"]} dmaSpi0=${dmaSt["spi0DmaProfile"]} rel=$rel"
                }.join()
                lastDiag += " $extra"
            }
            assertTrue(hudReached, "Expected autonomous transition to Live 1024x768 Shooting HUD (Scr=0x0100, PIC1+OSD0); last=$lastDiag")

            // Confirm live continuous scanout continues publishing frames after entering HUD
            stepBy(rt, 6_000_000L)
            val (liveSeq, liveDims, livePixels) = captureMain(rt)
            assertEquals(1024 to 768, liveDims)
            assertTrue(liveSeq > hudSeq, "Expected live continuous scanout to advance seq ($hudSeq -> $liveSeq)")
            assertTrue(countUniqueColors(livePixels) >= 40, "Expected rich multi-color shooting HUD overlay")
            assertEquals(0, readGuestU16(rt, 0x01CC0628L), "Expected activeErrno == 0 on Still Live View")
            assertEquals(0, readGuestU8(rt, 0x01CBFCA2L), "Expected bDscModeChgFlg == 0 on Still Live View")

            // 5. Live Shooting HUD interactive regression (#352 + #476/#479 MENU & PLAY unblock):
            //    BACK -> MENU (real Still Menu scr != 0x0100 && scr != 0 + DOWN highlight move) -> BACK (0x0100)
            //    -> PLAY (decodes DSCF0001.JPG from dual-directory Slot 1!) -> BACK/PLAY (0x0100).
            tapButton(rt, "back", 5_000_000L, 12_000_000L)
            assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join(), "Must stay running after BACK on Shooting HUD")
            val (afterBackSeq, _, afterBackPixels) = captureMain(rt)
            assertTrue(afterBackSeq >= liveSeq, "Expected valid frame after BACK")

            tapButton(rt, "menu", 5_000_000L, 20_000_000L)
            var menuScr = readGuestU32(rt, 0x01CC6424L)
            var menuBdsc = readGuestU8(rt, 0x01CBFCA2L)
            for (chunk in 0 until 12) {
                if (menuScr in 0x0400..0x04FF && menuBdsc == 0) break
                stepBy(rt, 10_000_000L)
                assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join(), "Must stay running opening Still Menu (chunk=$chunk)")
                menuScr = readGuestU32(rt, 0x01CC6424L)
                menuBdsc = readGuestU8(rt, 0x01CBFCA2L)
            }
            // Allow 1 settle window so the menu OSD renders completely before capturing baseline & pressing DOWN
            stepBy(rt, 8_000_000L)
            assertTrue(
                menuScr in 0x0400..0x04FF,
                "Expected MENU press on Still Live View to open Still Menu 0x0400..0x04FF (got scr=0x${menuScr.toString(16)}, bDsc=$menuBdsc, errno=0x${readGuestU16(rt, 0x01CC0628L).toString(16)})",
            )
            assertEquals(0, menuBdsc, "Expected bDscModeChgFlg == 0 in Still Menu (scr=0x${menuScr.toString(16)})")
            assertEquals(0, readGuestU16(rt, 0x01CC0628L), "Expected activeErrno == 0 after opening Still Menu (scr=0x${menuScr.toString(16)})")
            val (menuOpenSeq, menuDims, menuOpenPixels) = captureMain(rt)
            assertEquals(1024 to 768, menuDims)
            assertTrue(menuOpenSeq > afterBackSeq, "Expected MENU press on Shooting HUD to publish new frames ($afterBackSeq -> $menuOpenSeq)")
            assertTrue(countNonBlack(menuOpenPixels) > 100_000, "Expected rendered Shooting Menu screen (scr=0x${menuScr.toString(16)}, nonBlack=${countNonBlack(menuOpenPixels)})")
            assertTrue(countDiffPixels(afterBackPixels, menuOpenPixels) > 10_000, "Expected MENU to change LCD content significantly (scr=0x${menuScr.toString(16)})")

            tapButton(rt, "down", 4_000_000L, 20_000_000L)
            assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join(), "Must stay running navigating Shooting Menu")
            val (afterDownSeq, _, afterDownPixels) = captureMain(rt)
            val downDiff = countDiffPixels(menuOpenPixels, afterDownPixels)
            val downScr = readGuestU32(rt, 0x01CC6424L)
            assertTrue(
                afterDownSeq > menuOpenSeq && downDiff > 500,
                "Expected DOWN inside Still Menu to move highlight (menuScr=0x${menuScr.toString(16)} -> downScr=0x${downScr.toString(16)}, bDsc=${readGuestU8(rt, 0x01CBFCA2L)}, errno=0x${readGuestU16(rt, 0x01CC0628L).toString(16)}, accept0=0x${readGuestU32(rt, 0x01CBEFC8L).toUInt().toString(16)}, seq $menuOpenSeq -> $afterDownSeq, diff=$downDiff)",
            )

            var backScr = readGuestU32(rt, 0x01CC6424L)
            for (attempt in 0 until 3) {
                if (backScr == 0x0100 && readGuestU8(rt, 0x01CBFCA2L) == 0) break
                tapButton(rt, "back", 4_000_000L, 20_000_000L)
                for (chunk in 0 until 8) {
                    backScr = readGuestU32(rt, 0x01CC6424L)
                    if (backScr == 0x0100 && readGuestU8(rt, 0x01CBFCA2L) == 0) break
                    stepBy(rt, 10_000_000L)
                }
            }
            assertEquals(0x0100, backScr, "Expected BACK from Still Menu to return cleanly to Live View 0x0100")
            assertEquals(0, readGuestU8(rt, 0x01CBFCA2L), "Expected bDscModeChgFlg == 0 after returning from Still Menu")
            assertEquals(0, readGuestU16(rt, 0x01CC0628L), "Expected activeErrno == 0 after returning from Still Menu")
            val (backToHudSeq, _, backToHudPixels) = captureMain(rt)
            assertTrue(backToHudSeq > menuOpenSeq, "Expected BACK from Shooting Menu to publish updated frames")

            tapButton(rt, "play", 4_000_000L, 20_000_000L)
            var playScr = readGuestU32(rt, 0x01CC6424L)
            var playBdsc = readGuestU8(rt, 0x01CBFCA2L)
            for (chunk in 0 until 15) {
                if (playScr != 0x0100 && playScr != 0 && playScr != 0x0506 && playBdsc == 0) break
                stepBy(rt, 15_000_000L)
                assertEquals("running", rt.owner.submit { rt.session.state()["status"].toString() }.join(), "Must stay running entering PLAY (chunk=$chunk)")
                playScr = readGuestU32(rt, 0x01CC6424L)
                playBdsc = readGuestU8(rt, 0x01CBFCA2L)
            }
            stepBy(rt, 8_000_000L)
            val jpegState = rt.owner.submit { rt.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state() }.join()
            val decodeCount = (jpegState["decodeCount"] as? Number)?.toLong() ?: 0L
            val (playSeq, playDims, playPixels) = captureMain(rt)
            assertEquals(1024 to 768, playDims)
            assertTrue(
                playScr != 0x0100 && playScr != 0 && playScr != 0x0506,
                "Expected PLAY press to enter Playback screen (got scr=0x${playScr.toString(16)}, bDsc=$playBdsc, decodeCount=$decodeCount, errno=0x${readGuestU16(rt, 0x01CC0628L).toString(16)})",
            )
            assertEquals(0, readGuestU16(rt, 0x01CC0628L), "Expected activeErrno == 0 in Playback (scr=0x${playScr.toString(16)})")
            assertTrue(playSeq > backToHudSeq, "Expected PLAY press to publish Playback frame ($backToHudSeq -> $playSeq)")
            assertTrue(countNonBlack(playPixels) > 50_000, "Expected visible rendered Playback frame (scr=0x${playScr.toString(16)}, decodeCount=$decodeCount, nonBlack=${countNonBlack(playPixels)})")
            assertTrue(
                decodeCount >= 1L || countDiffPixels(backToHudPixels, playPixels) > 10_000,
                "Expected PLAY to decode DSCF0001.JPG or render Playback screen (scr=0x${playScr.toString(16)}, decodeCount=$decodeCount)",
            )

            // Return from PLAY to Still Live View 0x0100 via PLAY (or BACK)
            tapButton(rt, "play", 4_000_000L, 20_000_000L)
            var finalScr = waitForScreen(rt, maxChunks = 12, chunkAttempts = 12_000_000L) { it == 0x0100 }
            if (finalScr != 0x0100) {
                tapButton(rt, "back", 4_000_000L, 20_000_000L)
                finalScr = waitForScreen(rt, maxChunks = 10, chunkAttempts = 12_000_000L) { it == 0x0100 }
            }
            assertEquals(0x0100, finalScr, "Expected return from PLAY to Still Live View 0x0100")
            assertEquals(0, readGuestU16(rt, 0x01CC0628L), "Expected activeErrno == 0 after returning from PLAY")
            val (finalSeq, finalDims, _) = captureMain(rt)
            assertEquals(1024 to 768, finalDims)
            assertTrue(finalSeq > playSeq, "Expected valid frame sequence after returning from PLAY ($playSeq -> $finalSeq)")

            val relaxedReport = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join()
            println("ReleaseInteractiveSmokeTest Boot #1 summary: menuScr=0x${menuScr.toString(16)} playScr=0x${playScr.toString(16)} jpegDecodeCount=$decodeCount relaxed=$relaxedReport")
        }
    }

    private fun dumpPngIfRequested(name: String, dims: Pair<Int, Int>, pixels: IntArray) {
        val dir = System.getenv("FUJI_SMOKE_DUMP_DIR")?.takeIf { it.isNotBlank() } ?: return
        val out = Path.of(dir).resolve(name)
        Files.createDirectories(out.parent)
        val img = java.awt.image.BufferedImage(dims.first, dims.second, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, dims.first, dims.second, pixels, 0, dims.first)
        javax.imageio.ImageIO.write(img, "png", out.toFile())
    }

    private fun lcdcDiag(rt: EmulatorRuntime): String = rt.owner.submit {
        val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)
        val lcdcStr = if (lcdc == null) "null" else "008=0x${lcdc.read(0x008, 4).toString(16)},030=0x${lcdc.read(0x030, 4).toString(16)},044=0x${lcdc.read(0x044, 4).toString(16)},168=0x${lcdc.read(0x168, 4).toString(16)}"
        val vglib = rt.host.components().single { it.descriptor.id == "soc-vglib-init" }.component.state()
        @Suppress("UNCHECKED_CAST")
        val gf = vglib["graphicsFillTail"] as? Map<String, Any?>
        val fills = (gf?.get("readbacks") as? List<*>)?.size ?: -1
        val copies = (gf?.get("copyReadbacks") as? List<*>)?.size ?: -1
        val cmds = (gf?.get("commandReadbacks") as? List<*>)?.size ?: -1
        "lcdc($lcdcStr) vglib(fills=$fills,copies=$copies,cmds=$cmds) irq117=${vglib["irq117"]} irq118=${vglib["irq118"]}"
    }.join()

    @Test
    fun stagedReleaseSecondBootWithPersistedNvramReachesLiveUiCleanly(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )
        val workRoot = tempRoot.resolve("release-root-second-boot")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        // Boot #1: cold boot to OOBE (writes NVRAM BlockA/BlockB to cow-domain490-497.img & cow-domain504-505.img)
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 620_000_000L)
            val boot1State = rt.owner.submit { rt.session.state() }.join()
            assertEquals("running", boot1State["status"].toString(), "Boot #1 must stay running: $boot1State")
            assertEquals(0x0421, readGuestU32(rt, 0x01CC6424L), "Boot #1 expected OOBE 0x0421")
        }

        // Boot #2: cold restart on the SAME release root with persisted COW flash images (~105M ticks faster;
        // must NEVER halt at 0x03C016BC, must reach OOBE -> Live 1024x768 Shooting HUD -> MENU -> PLAY cleanly).
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt2 ->
            rt2.owner.submit { rt2.session.initialize() }.join()
            stepBy(rt2, 620_000_000L)
            val boot2State = rt2.owner.submit { rt2.session.state() }.join()
            val boot2Rel = rt2.owner.submit { rt2.session.relaxedFeedbackSummaryReport() }.join()
            assertEquals(
                "running",
                boot2State["status"].toString(),
                "Boot #2 halted: status=${boot2State["status"]} frontier=${boot2State["frontier"]} tick=${rt2.telemetry.virtualTick} attempts=${boot2State["attempts"]} rel=$boot2Rel",
            )
            var boot2Scr = readGuestU32(rt2, 0x01CC6424L)
            assertEquals(0x0421, boot2Scr, "Boot #2 expected OOBE 0x0421 when OOBE was not finished on Boot #1")
            val (seq2, dims2, px2) = captureMain(rt2)
            assertEquals(1024 to 768, dims2)
            assertTrue(seq2 >= 1L && countNonBlack(px2) > 100_000, "Boot #2 expected rendered OOBE UI (seq=$seq2, nonBlack=${countNonBlack(px2)})")
            dumpPngIfRequested("boot2-oobe.png", dims2, px2)

            // Complete OOBE on Boot #2 -> Live 1024x768 STILL Shooting HUD (0x0100)
            tapButton(rt2, "menu", 5_000_000L, 45_000_000L)
            boot2Scr = readGuestU32(rt2, 0x01CC6424L)
            if (boot2Scr == 0x0423) {
                tapButton(rt2, "down", 5_000_000L, 25_000_000L)
                tapButton(rt2, "down", 5_000_000L, 25_000_000L)
                tapButton(rt2, "menu", 5_000_000L, 45_000_000L)
                boot2Scr = readGuestU32(rt2, 0x01CC6424L)
            }
            if (boot2Scr == 0x0422) {
                tapButton(rt2, "menu", 5_000_000L, 45_000_000L)
                boot2Scr = readGuestU32(rt2, 0x01CC6424L)
            }
            if (boot2Scr == 0x0482) {
                tapButton(rt2, "menu", 5_000_000L, 40_000_000L)
            }

            var hud2Reached = false
            var hud2Seq = 0L
            var hud2Diag = ""
            for (iter in 0 until 25) {
                val st = rt2.owner.submit { rt2.session.state() }.join()
                assertEquals(
                    "running",
                    st["status"].toString(),
                    "Boot #2 must stay running entering Shooting HUD (iter=$iter, status=${st["status"]}, frontier=${st["frontier"]}, tick=${rt2.telemetry.virtualTick})",
                )
                val scr = readGuestU32(rt2, 0x01CC6424L)
                val (seq, _, px) = captureMain(rt2)
                val nonBlack = countNonBlack(px)
                val colors = countUniqueColors(px)
                hud2Diag = "iter=$iter scr=0x${scr.toString(16)} seq=$seq nonBlack=$nonBlack colors=$colors tick=${rt2.telemetry.virtualTick} bDsc=${readGuestU8(rt2, 0x01CBFCA2L)} errno=0x${readGuestU16(rt2, 0x01CC0628L).toString(16)}"
                if (scr == 0x0100 && nonBlack > 150_000 && colors >= 40 && readGuestU8(rt2, 0x01CBFCA2L) == 0) {
                    hud2Reached = true
                    hud2Seq = seq
                    break
                }
                stepBy(rt2, 10_000_000L)
            }
            assertTrue(hud2Reached, "Expected Boot #2 (persisted NVRAM) to reach Live 1024x768 STILL Shooting HUD (0x0100); $hud2Diag")
            assertEquals(0, readGuestU16(rt2, 0x01CC0628L), "Boot #2 expected activeErrno == 0 on STILL Shooting HUD ($hud2Diag)")
            stepBy(rt2, 8_000_000L)
            val (settledHud2Seq, hud2Dims, hud2Pixels) = captureMain(rt2)
            assertEquals(1024 to 768, hud2Dims)
            assertTrue(settledHud2Seq >= hud2Seq, "Boot #2 expected valid HUD sequence ($hud2Seq -> $settledHud2Seq)")
            dumpPngIfRequested("boot2-hud.png", hud2Dims, hud2Pixels)
            println("ReleaseInteractiveSmokeTest Boot #2 HUD: scr=0x${readGuestU32(rt2, 0x01CC6424L).toString(16)} curScr=0x${readGuestU32(rt2, 0x01CBFFD4L).toString(16)} prevScr=0x${readGuestU32(rt2, 0x01CC6420L).toString(16)} accept0=0x${readGuestU32(rt2, 0x01CBEFC8L).toUInt().toString(16)} seq=$settledHud2Seq nonBlack=${countNonBlack(hud2Pixels)} colors=${countUniqueColors(hud2Pixels)} ${lcdcDiag(rt2)}")

            // Match Boot #1: press BACK on Shooting HUD, then MENU -> BACK -> 0x0100
            tapButton(rt2, "back", 5_000_000L, 12_000_000L)
            val (afterBack2Seq, _, afterBack2Pixels) = captureMain(rt2)
            dumpPngIfRequested("boot2-after-back.png", hud2Dims, afterBack2Pixels)
            println("ReleaseInteractiveSmokeTest Boot #2 AFTER_BACK: scr=0x${readGuestU32(rt2, 0x01CC6424L).toString(16)} curScr=0x${readGuestU32(rt2, 0x01CBFFD4L).toString(16)} seq=$afterBack2Seq diffFromHud=${countDiffPixels(hud2Pixels, afterBack2Pixels)} ${lcdcDiag(rt2)}")

            tapButton(rt2, "menu", 5_000_000L, 20_000_000L)
            var menu2Scr = readGuestU32(rt2, 0x01CC6424L)
            var menu2Bdsc = readGuestU8(rt2, 0x01CBFCA2L)
            for (chunk in 0 until 12) {
                val (cSeq, _, cPx) = captureMain(rt2)
                val cDiff = countDiffPixels(afterBack2Pixels, cPx)
                println("ReleaseInteractiveSmokeTest Boot #2 MENU chunk=$chunk scr=0x${menu2Scr.toString(16)} curScr=0x${readGuestU32(rt2, 0x01CBFFD4L).toString(16)} bDsc=$menu2Bdsc seq=$cSeq diff=$cDiff ${lcdcDiag(rt2)}")
                if (menu2Scr in 0x0400..0x04FF && menu2Bdsc == 0 && cDiff > 10_000) break
                stepBy(rt2, 10_000_000L)
                assertEquals("running", rt2.owner.submit { rt2.session.state()["status"].toString() }.join(), "Boot #2 must stay running opening MENU")
                menu2Scr = readGuestU32(rt2, 0x01CC6424L)
                menu2Bdsc = readGuestU8(rt2, 0x01CBFCA2L)
            }
            assertTrue(menu2Scr in 0x0400..0x04FF, "Boot #2 expected Still Menu 0x0400..0x04FF (got scr=0x${menu2Scr.toString(16)}, bDsc=$menu2Bdsc)")
            assertEquals(0, readGuestU16(rt2, 0x01CC0628L), "Boot #2 expected activeErrno == 0 in Still Menu")
            var menu2Seq = afterBack2Seq
            var menu2Diff = 0
            var menu2Pixels = afterBack2Pixels
            for (settle in 0 until 4) {
                val (seq, _, px) = captureMain(rt2)
                menu2Seq = seq
                menu2Pixels = px
                menu2Diff = countDiffPixels(afterBack2Pixels, px)
                if (menu2Seq > afterBack2Seq && menu2Diff > 10_000 && countNonBlack(px) > 100_000) break
                stepBy(rt2, 8_000_000L)
            }
            dumpPngIfRequested("boot2-menu.png", hud2Dims, menu2Pixels)
            println("ReleaseInteractiveSmokeTest Boot #2 MENU: scr=0x${menu2Scr.toString(16)} curScr=0x${readGuestU32(rt2, 0x01CBFFD4L).toString(16)} seq=$menu2Seq nonBlack=${countNonBlack(menu2Pixels)} colors=${countUniqueColors(menu2Pixels)} diff=$menu2Diff ${lcdcDiag(rt2)}")
            assertTrue(
                menu2Seq > afterBack2Seq && menu2Diff > 10_000,
                "Boot #2 expected MENU to publish rendered menu frame (scr=0x${menu2Scr.toString(16)}, seq $afterBack2Seq -> $menu2Seq, diff=$menu2Diff, ${lcdcDiag(rt2)})",
            )

            var back2Scr = menu2Scr
            for (attempt in 0 until 3) {
                if (back2Scr == 0x0100 && readGuestU8(rt2, 0x01CBFCA2L) == 0) break
                tapButton(rt2, "back", 4_000_000L, 20_000_000L)
                for (chunk in 0 until 8) {
                    back2Scr = readGuestU32(rt2, 0x01CC6424L)
                    if (back2Scr == 0x0100 && readGuestU8(rt2, 0x01CBFCA2L) == 0) break
                    stepBy(rt2, 10_000_000L)
                }
            }
            assertEquals(0x0100, back2Scr, "Boot #2 expected BACK from Still Menu to return to 0x0100")

            // Boot #2 PLAY (DSCF0001.JPG from directory Slot 1) -> PLAY return to 0x0100
            tapButton(rt2, "play", 4_000_000L, 20_000_000L)
            var play2Scr = readGuestU32(rt2, 0x01CC6424L)
            var play2Bdsc = readGuestU8(rt2, 0x01CBFCA2L)
            for (chunk in 0 until 15) {
                if (play2Scr != 0x0100 && play2Scr != 0 && play2Scr != 0x0506 && play2Bdsc == 0) break
                stepBy(rt2, 15_000_000L)
                assertEquals("running", rt2.owner.submit { rt2.session.state()["status"].toString() }.join(), "Boot #2 must stay running entering PLAY")
                play2Scr = readGuestU32(rt2, 0x01CC6424L)
                play2Bdsc = readGuestU8(rt2, 0x01CBFCA2L)
            }
            stepBy(rt2, 8_000_000L)
            val jpeg2State = rt2.owner.submit { rt2.host.components().single { it.descriptor.id == "soc-jpeg" }.component.state() }.join()
            val decode2Count = (jpeg2State["decodeCount"] as? Number)?.toLong() ?: 0L
            assertTrue(play2Scr != 0x0100 && play2Scr != 0 && play2Scr != 0x0506, "Boot #2 expected PLAY screen (got 0x${play2Scr.toString(16)})")
            assertEquals(0, readGuestU16(rt2, 0x01CC0628L), "Boot #2 expected activeErrno == 0 in PLAY")
            assertTrue(decode2Count >= 1L, "Boot #2 expected soc-jpeg to decode DSCF0001.JPG (decodeCount=$decode2Count)")

            tapButton(rt2, "play", 4_000_000L, 20_000_000L)
            val final2Scr = waitForScreen(rt2, maxChunks = 12, chunkAttempts = 12_000_000L) { it == 0x0100 }
            assertEquals(0x0100, final2Scr, "Boot #2 expected return from PLAY to Still Live View 0x0100")
            val rel2 = rt2.owner.submit { rt2.session.relaxedFeedbackSummaryReport() }.join()
            println("ReleaseInteractiveSmokeTest Boot #2 summary: menuScr=0x${menu2Scr.toString(16)} playScr=0x${play2Scr.toString(16)} jpegDecodeCount=$decode2Count relaxed=$rel2")
        }
    }
}
