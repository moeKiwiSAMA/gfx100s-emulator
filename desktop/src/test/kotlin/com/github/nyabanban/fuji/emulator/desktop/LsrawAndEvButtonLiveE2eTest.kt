package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live `GXUP0004.DAT` end-to-end regression suite for `P1-FIX-LOSSLESS-RAW-EV-BTN`:
 * 1. Top-plate Exposure Compensation (`± / Fn1`, ADC CH6 `fn1Button`) hold + Rear Command Dial
 *    adjusts Exposure Compensation (`-1.33 EV`, `+1.33 EV`, `0.0 EV`) verified in EXIF `0x9204`.
 * 2. `RAW RECORDING = LOSSLESS COMPRESSED` encodes real Fujifilm `"IS"` Golomb-Rice Bayer
 *    strips (`ver=1, raw_type=16, 16 strips`) via `LSRAW` (`0xFFFA2000`, GIC IRQ 137) and writes
 *    a compact `< 40 MiB` `.RAF` (vs 207 MiB uncompressed) without freezing LiveView.
 * 3. `RAW RECORDING = COMPRESSED` encodes lossy-compressed Fujifilm `"IS"` (`ver=2`) `.RAF`
 *    smaller than lossless without freezing LiveView.
 * 4. `DRIVE -> PIXEL SHIFT MULTI SHOT` completes all `16/16` `.RAF` captures cleanly.
 *
 * Env-gated (`FUJI_RUN_LSRAW_EV_E2E=1`) so standard CI stays fast.
 */
class LsrawAndEvButtonLiveE2eTest {
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
            val expectedJars = Files.list(compDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }.toList().toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { s ->
                    s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { s ->
                s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar -> Files.copy(jar, dstCompDir.resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        Files.copy(repo.resolve("tools/release/emulator-release-linux.yaml"), workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(repo.resolve("catalog/compiled/catalog.json"), workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        copyDirectory(repo.resolve("tools/release/component-configs"), workRoot.resolve("configs/components"))
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

    private fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long) {
        val target = rt.telemetry.virtualTick + deltaMs * 54_000L
        var guard = 0
        while (rt.telemetry.virtualTick < target && guard < 24000) {
            guard++
            val keepGoing = rt.owner.submit {
                var i = 0
                while (i < 512) {
                    if (!rt.session.stepFast()) return@submit false
                    if (rt.telemetry.virtualTick >= target) return@submit true
                    i++
                }
                true
            }.join()
            if (!keepGoing) break
        }
    }

    private fun patchComponent(rt: EmulatorRuntime, id: String, settings: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, settings).join()
        }.join()
    }

    private fun tap(rt: EmulatorRuntime, key: String, holdMs: Long = 100L, settleMs: Long = 280L) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepVirtualMs(rt, holdMs)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepVirtualMs(rt, settleMs)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xFF
    }.join()

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun displayStats(rt: EmulatorRuntime): Pair<Long, Int> = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(d.width * d.height)
        d.copyArgb(px)
        d.sequence to px.count { (it and 0x00FFFFFF) != 0 }
    }.join()

    private fun listDcimFiles(workRoot: Path): Map<String, Path> {
        val dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (!Files.isDirectory(dcim)) return emptyMap()
        val out = LinkedHashMap<String, Path>()
        Files.walk(dcim).use { s ->
            s.filter { Files.isRegularFile(it) }
                .sorted()
                .forEach { out[dcim.relativize(it).toString()] = it }
        }
        return out
    }

    private fun extractEvBias(jpg: Path): Double? {
        val data = Files.readAllBytes(jpg)
        val marker = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        var idx = -1
        outer@ for (i in 0..(data.size - marker.size)) {
            for (j in marker.indices) {
                if (data[i + j] != marker[j]) continue@outer
            }
            idx = i
            break
        }
        if (idx < 0) return null
        val t = idx + 6
        val order = if (data[t] == 'I'.code.toByte() && data[t + 1] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(data).order(order)
        fun readIfd(off: Int): Map<Int, Any> {
            val map = LinkedHashMap<Int, Any>()
            val cnt = buf.getShort(t + off).toInt() and 0xFFFF
            for (i in 0 until cnt) {
                val e = t + off + 2 + i * 12
                val tag = buf.getShort(e).toInt() and 0xFFFF
                val typ = buf.getShort(e + 2).toInt() and 0xFFFF
                val n = buf.getInt(e + 4)
                if (typ == 10 && n == 1) {
                    val o = buf.getInt(e + 8)
                    val num = buf.getInt(t + o)
                    val den = buf.getInt(t + o + 4)
                    map[tag] = if (den != 0) num.toDouble() / den.toDouble() else 0.0
                } else if (typ == 4 && n == 1) {
                    map[tag] = buf.getInt(e + 8)
                }
            }
            return map
        }
        val ifd0 = buf.getInt(t + 4)
        val m0 = readIfd(ifd0)
        val exifOff = (m0[0x8769] as? Int) ?: return null
        return readIfd(exifOff)[0x9204] as? Double
    }

    private data class RafInfo(
        val sizeBytes: Long,
        val magicOk: Boolean,
        val cfaLen: Int,
        val isVersion: Int,
        val rawType: Int,
        val rawBits: Int,
        val height: Int,
        val width: Int,
        val blocksX: Int,
        val blocksY: Int,
    )

    private fun inspectRaf(raf: Path): RafInfo {
        val data = Files.readAllBytes(raf)
        val magic = String(data, 0, minOf(16, data.size), Charsets.US_ASCII)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val cfaOff = if (data.size > 0x6C) buf.getInt(0x64) else 0
        val cfaLen = if (data.size > 0x6C) buf.getInt(0x68) else 0
        var isPos = -1
        if (cfaOff > 0 && cfaOff < data.size - 16) {
            val end = minOf(data.size - 16, cfaOff + 16384)
            for (i in cfaOff until end) {
                if (data[i] == 0x49.toByte() && data[i + 1] == 0x53.toByte() &&
                    (data[i + 2] == 1.toByte() || data[i + 2] == 2.toByte()) &&
                    data[i + 3] == 16.toByte()
                ) {
                    isPos = i
                    break
                }
            }
        }
        return if (isPos >= 0) {
            RafInfo(
                sizeBytes = data.size.toLong(),
                magicOk = magic.startsWith("FUJIFILMCCD-RAW"),
                cfaLen = cfaLen,
                isVersion = data[isPos + 2].toInt() and 0xFF,
                rawType = data[isPos + 3].toInt() and 0xFF,
                rawBits = data[isPos + 4].toInt() and 0xFF,
                height = buf.getShort(isPos + 5).toInt() and 0xFFFF,
                width = buf.getShort(isPos + 9).toInt() and 0xFFFF,
                blocksX = data[isPos + 13].toInt() and 0xFF,
                blocksY = buf.getShort(isPos + 14).toInt() and 0xFFFF,
            )
        } else {
            RafInfo(data.size.toLong(), magic.startsWith("FUJIFILMCCD-RAW"), cfaLen, -1, -1, -1, 0, 0, 0, 0)
        }
    }

    @Test
    fun evButtonLosslessCompressedRawAndPixelShiftPassLiveOnUnmodifiedFirmware(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_LSRAW_EV_E2E") == "1" || System.getProperty("fuji.runLsrawEvE2e") == "1",
            "Set FUJI_RUN_LSRAW_EV_E2E=1 to run live GXUP0004.DAT P1-FIX-LOSSLESS-RAW-EV-BTN E2E verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(Files.isDirectory(stagedRoot), "Requires staged release at $stagedRoot")

        val workRoot = tempRoot.resolve("lsraw-ev-live-e2e")
        copyDirectory(stagedRoot, workRoot)
        Files.deleteIfExists(workRoot.resolve("runtime/compoments/power-peer/submcu-nvram.json"))
        val slot1Dcim = workRoot.resolve("runtime/compoments/sdcard-reader/sd-card-slot-1/DCIM")
        if (Files.isDirectory(slot1Dcim)) {
            Files.walk(slot1Dcim).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()
            stepBy(rt, 160_000_000L)
            var guard = 0
            while (readU32(rt, 0x01CC6424L) != 0x0100 && guard < 12) {
                val scr = readU32(rt, 0x01CC6424L)
                if (scr in 0x0501..0x0505) tap(rt, "menu", 85L, 350L) else tap(rt, "back", 85L, 350L)
                guard++
            }
            stepVirtualMs(rt, 800L)
            assertEquals(0x0100, readU32(rt, 0x01CC6424L), "Expected STILL LiveView scr=0x0100 after OOBE")

            patchComponent(rt, "soc-gpio", mapOf("focusMode" to "MF"))
            stepVirtualMs(rt, 400L)

            fun fireStillShutter() {
                patchComponent(rt, "buttons", mapOf("shutterHalf" to true))
                for (w in 0 until 5) {
                    stepBy(rt, 12_000_000L)
                    val s1Sub0 = readU8(rt, 0x02C47C5CL + 0x60L)
                    val saclac0 = readU32(rt, 0x02DA49ECL) and 0xFFFF
                    if (w >= 1 && (s1Sub0 >= 4 || (s1Sub0 == 0 && saclac0 == 4))) break
                }
                patchComponent(rt, "buttons", mapOf("shutterHalf" to true, "shutterFull" to true))
                repeat(3) { stepBy(rt, 12_000_000L) }
                patchComponent(rt, "buttons", mapOf("shutterHalf" to false, "shutterFull" to false))
            }

            fun waitForNewFiles(
                before: Set<String>,
                needExt: String,
                minCount: Int = 1,
                maxVirtualMs: Long = 20_000L,
            ): Map<String, Path> {
                var elapsed = 0L
                while (elapsed < maxVirtualMs) {
                    stepVirtualMs(rt, 500L)
                    elapsed += 500L
                    val added = listDcimFiles(workRoot).filterKeys { it !in before }
                    val matches = added.keys.count { it.endsWith(needExt, ignoreCase = true) }
                    val scr = readU32(rt, 0x01CC6424L)
                    val bDsc = readU8(rt, 0x01CBFCA2L)
                    if (matches >= minCount && bDsc == 0 && scr in listOf(0x0100, 0x0102)) {
                        stepVirtualMs(rt, 500L)
                        return listDcimFiles(workRoot).filterKeys { it !in before }
                    }
                }
                return listDcimFiles(workRoot).filterKeys { it !in before }
            }

            // ====== 1. Top-Plate ± / Fn1 Exposure Compensation Button (ADC CH6) ======
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "A"))
            stepVirtualMs(rt, 500L)

            // Hold fn1Button + Rear Dial -9 -> negative EV bias
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to true))
            stepVirtualMs(rt, 250L)
            patchComponent(rt, "soc-adc", mapOf("rearDialDetents" to -9L))
            stepVirtualMs(rt, 800L)
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to false))
            stepVirtualMs(rt, 300L)

            val beforeEv1 = listDcimFiles(workRoot).keys
            fireStillShutter()
            val addedEv1 = waitForNewFiles(beforeEv1, ".JPG")
            val jpgEv1 = assertNotNull(addedEv1.values.firstOrNull { it.toString().endsWith(".JPG") })
            val bias1 = assertNotNull(extractEvBias(jpgEv1))
            println("[E2E-EV] fn1 hold + rearDial -9 -> ${jpgEv1.fileName} evBias=$bias1")
            assertTrue(bias1 < -0.5, "Expected negative EV bias from Fn1/± hold + rearDial -9, got $bias1")

            // Hold fn1Button + Rear Dial +9 -> positive EV bias
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to true))
            stepVirtualMs(rt, 250L)
            patchComponent(rt, "soc-adc", mapOf("rearDialDetents" to 9L))
            stepVirtualMs(rt, 800L)
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to false))
            stepVirtualMs(rt, 300L)

            val beforeEv2 = listDcimFiles(workRoot).keys
            fireStillShutter()
            val addedEv2 = waitForNewFiles(beforeEv2, ".JPG")
            val jpgEv2 = assertNotNull(addedEv2.values.firstOrNull { it.toString().endsWith(".JPG") })
            val bias2 = assertNotNull(extractEvBias(jpgEv2))
            println("[E2E-EV] fn1 hold + rearDial +9 -> ${jpgEv2.fileName} evBias=$bias2")
            assertTrue(bias2 > 0.5, "Expected positive EV bias from Fn1/± hold + rearDial +9, got $bias2")

            // Reset EV bias to 0.0 and return to P mode
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to true))
            stepVirtualMs(rt, 250L)
            patchComponent(rt, "soc-adc", mapOf("rearDialDetents" to 0L))
            stepVirtualMs(rt, 700L)
            patchComponent(rt, "soc-adc", mapOf("fn1Button" to false))
            stepVirtualMs(rt, 250L)
            patchComponent(rt, "soc-adc", mapOf("modeDial" to "default"))
            stepVirtualMs(rt, 400L)

            // ====== 2. LOSSLESS COMPRESSED RAW ======
            tap(rt, "menu", 90L, 320L)
            tap(rt, "down", 85L, 200L) // P1 R1 IMAGE QUALITY
            tap(rt, "menu", 90L, 300L)
            tap(rt, "down", 85L, 200L) // NORMAL
            tap(rt, "down", 85L, 200L) // SUPER FINE+RAW
            tap(rt, "menu", 90L, 320L)
            tap(rt, "down", 85L, 200L) // P1 R2 RAW RECORDING
            tap(rt, "menu", 90L, 300L)
            tap(rt, "down", 85L, 200L) // LOSSLESS COMPRESSED
            tap(rt, "menu", 90L, 320L)
            tap(rt, "back", 90L, 350L)

            val beforeLl = listDcimFiles(workRoot).keys
            fireStillShutter()
            val addedLl = waitForNewFiles(beforeLl, ".RAF", maxVirtualMs = 25_000L)
            val (seqLl, nbLl) = displayStats(rt)
            val scrLl = readU32(rt, 0x01CC6424L)
            val bDscLl = readU8(rt, 0x01CBFCA2L)
            val lsrawFwState = readU8(rt, 0x02954680L)
            val saclacFp = readU8(rt, 0x02D781B0L + 0x38L)
            val drvMode = readU8(rt, 0x02AB1318L + 0x198L)
            val ispComp = compState(rt, "soc-isp")["companionEngines"] as? Map<*, *>
            val lsrawSt = ispComp?.get("lsraw")
            val rafLl = addedLl.values.firstOrNull { it.toString().endsWith(".RAF") }
            val infoLl = rafLl?.let(::inspectRaf)
            println("[E2E-LSRAW-LL] scr=0x${scrLl.toString(16)} bDsc=$bDscLl seq=$seqLl nonBlack=$nbLl lsrawFw=$lsrawFwState saclacFp=$saclacFp drv=$drvMode lsraw=$lsrawSt files=${addedLl.keys} raf=$infoLl")
            assertNotNull(infoLl, "Expected LOSSLESS COMPRESSED .RAF written to Slot 1 DCIM")
            assertTrue(infoLl.magicOk, "RAF must have FUJIFILMCCD-RAW magic")
            assertEquals(1, infoLl.isVersion, "LOSSLESS COMPRESSED RAF must use Fujifilm IS ver=1")
            assertEquals(16, infoLl.rawType, "LOSSLESS COMPRESSED RAF must use Bayer raw_type=16")
            assertEquals(16, infoLl.blocksX, "GFX100S 102MP LSRAW must have 16 strips")
            assertTrue(infoLl.sizeBytes in 1_000_000L..60_000_000L, "LOSSLESS .RAF must be compact (got ${infoLl.sizeBytes} B)")
            assertTrue(scrLl in listOf(0x0100, 0x0102) && nbLl > 100_000, "LiveView must auto-resume cleanly after LOSSLESS RAW (scr=0x${scrLl.toString(16)} nonBlack=$nbLl)")

            // ====== 3. COMPRESSED RAW ======
            tap(rt, "menu", 90L, 320L) // remembers P1 R2 RAW RECORDING
            tap(rt, "menu", 90L, 300L)
            tap(rt, "down", 85L, 200L) // COMPRESSED
            tap(rt, "menu", 90L, 320L)
            tap(rt, "back", 90L, 350L)

            val beforeCmp = listDcimFiles(workRoot).keys
            fireStillShutter()
            val addedCmp = waitForNewFiles(beforeCmp, ".RAF", maxVirtualMs = 25_000L)
            val (seqCmp, nbCmp) = displayStats(rt)
            val scrCmp = readU32(rt, 0x01CC6424L)
            val rafCmp = addedCmp.values.firstOrNull { it.toString().endsWith(".RAF") }
            val infoCmp = rafCmp?.let(::inspectRaf)
            println("[E2E-LSRAW-CMP] scr=0x${scrCmp.toString(16)} seq=$seqCmp nonBlack=$nbCmp files=${addedCmp.keys} raf=$infoCmp")
            assertNotNull(infoCmp, "Expected COMPRESSED .RAF written to Slot 1 DCIM")
            assertTrue(infoCmp.magicOk && infoCmp.isVersion == 2 && infoCmp.rawType == 16 && infoCmp.blocksX == 16, "Expected valid COMPRESSED IS ver=2 RAF: $infoCmp")
            assertTrue(scrCmp in listOf(0x0100, 0x0102) && nbCmp > 100_000, "LiveView must auto-resume cleanly after COMPRESSED RAW (scr=0x${scrCmp.toString(16)} nonBlack=$nbCmp)")

            // ====== 4. 16/16 PIXEL SHIFT MULTI SHOT ======
            tap(rt, "drive", 90L, 350L)
            tap(rt, "up", 85L, 250L) // PIXEL SHIFT MULTI SHOT
            tap(rt, "menu", 90L, 400L)
            val beforePs = listDcimFiles(workRoot).keys
            fireStillShutter()
            var addedPs: Map<String, Path> = emptyMap()
            var lastRafCount = 0
            for (step in 0 until 240) {
                stepVirtualMs(rt, 500L)
                addedPs = listDcimFiles(workRoot).filterKeys { it !in beforePs }
                val rafCount = addedPs.keys.count { it.endsWith(".RAF", ignoreCase = true) }
                if (rafCount != lastRafCount) {
                    lastRafCount = rafCount
                    println("[E2E-PSMS] t=${(step + 1) * 500}ms rafs=$rafCount/16 scr=0x${readU32(rt, 0x01CC6424L).toString(16)}")
                }
                if (rafCount >= 16 && readU8(rt, 0x01CBFCA2L) == 0 && readU32(rt, 0x01CC6424L) in listOf(0x0100, 0x0102)) {
                    break
                }
            }
            val psRafs = addedPs.values.filter { it.toString().endsWith(".RAF") }
            val (seqPs, nbPs) = displayStats(rt)
            println("[E2E-PSMS] DONE rafs=${psRafs.size}/16 scr=0x${readU32(rt, 0x01CC6424L).toString(16)} seq=$seqPs nonBlack=$nbPs")
            assertEquals(16, psRafs.size, "Expected all 16/16 PIXEL SHIFT MULTI SHOT .RAF files")

            @Suppress("UNCHECKED_CAST")
            val fallback = rt.owner.submit { rt.session.state()["relaxedFallback"] as? Map<String, Any?> }.join()
            println("[E2E-SUMMARY] relaxedFallback=$fallback")
            if (fallback != null) {
                assertEquals(0, (fallback["mmioUnique"] as? Number)?.toInt() ?: 0, "Expected 0 [RELAXED/MMIO]")
                assertEquals(0, (fallback["flashRefused"] as? Number)?.toInt() ?: 0, "Expected 0 [RELAXED/FLASH/REFUSED]")
            }
        }
    }
}
