package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.CameraOrientationPort
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.IbisShiftPort
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
 * Live GXUP0004.DAT (Dynarmic V2) end-to-end verification for:
 *  - Chip-decoupled NXP MMA8452Q dual accelerometers (I2C1 0x1C body + I2C2 0x1D tilting LCD)
 *  - Authentic firmware `levelsens` orientation enum (`0x018799F0`), roll (`0x018798E4`), pitch (`0x01879918`)
 *    across Landscape (0°), Portrait CW (+90°), Inverted (180°), Portrait CCW (-90°), and Pitch (+25°)
 *  - Live `biscom` (SPI2) 5-axis IBIS sub-MCU telemetry (`soc-spi.biscom.ibis`) in STILL LiveView (`0x0100`).
 */
class GyroIbisPortraitE2eTest {
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
        val emuStage = repo.resolve("build/emulator")
        val compDir = emuStage.resolve("compoments")
        val dstCompDir = workRoot.resolve("runtime/compoments")
        if (Files.isDirectory(compDir)) {
            val expectedJars = Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .map { it.fileName.toString() }
                    .toList()
                    .toSet()
            }
            if (Files.isDirectory(dstCompDir)) {
                Files.list(dstCompDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") && it.fileName.toString() !in expectedJars }
                        .forEach { Files.deleteIfExists(it) }
                }
            }
            Files.list(compDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val dst = dstCompDir.resolve(jar.fileName)
                        Files.createDirectories(dst.parent)
                        Files.copy(jar, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
            }
        }
        val lensesDir = emuStage.resolve("lenses")
        if (Files.isDirectory(lensesDir)) {
            copyDirectory(lensesDir, workRoot.resolve("lenses"))
        }
        val emuYaml = repo.resolve("tools/release/emulator-release-linux.yaml")
        if (Files.isRegularFile(emuYaml)) {
            Files.copy(emuYaml, workRoot.resolve("configs/emulator.yaml"), StandardCopyOption.REPLACE_EXISTING)
        }
        val catalogJson = repo.resolve("catalog/compiled/catalog.json")
        if (Files.isRegularFile(catalogJson)) {
            Files.copy(catalogJson, workRoot.resolve("configs/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
        }
        val relMeta = repo.resolve("build/release-metadata/gfx100s-213-release.yaml")
        if (Files.isRegularFile(relMeta)) {
            val dst = workRoot.resolve("configs/firmware/gfx100s-213-release.yaml")
            Files.createDirectories(dst.parent)
            Files.copy(relMeta, dst, StandardCopyOption.REPLACE_EXISTING)
        }
        val compConfigs = repo.resolve("tools/release/component-configs")
        if (Files.isDirectory(compConfigs)) {
            copyDirectory(compConfigs, workRoot.resolve("configs/components"))
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

    private fun stepVirtualMs(rt: EmulatorRuntime, deltaMs: Long) {
        // GFX100S virtual clock runs at 54 MHz (54,000 ticks/ms)
        val target = rt.telemetry.virtualTick + deltaMs * 54_000L
        var guard = 0
        while (rt.telemetry.virtualTick < target && guard < 4000) {
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

    private fun tapButton(rt: EmulatorRuntime, key: String, holdAttempts: Long, settleAttempts: Long) {
        patchComponent(rt, "buttons", mapOf(key to true))
        stepBy(rt, holdAttempts)
        patchComponent(rt, "buttons", mapOf(key to false))
        stepBy(rt, settleAttempts)
    }

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readS16(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.short.toInt()
    }.join()

    private fun readU16(rt: EmulatorRuntime, addr: Long): Int = readS16(rt, addr) and 0xffff

    private fun readU8(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun captureMain(rt: EmulatorRuntime): Triple<Long, Pair<Int, Int>, IntArray> = rt.owner.submit {
        val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val pixels = IntArray(display.width * display.height)
        display.copyArgb(pixels)
        Triple(display.sequence, display.width to display.height, pixels)
    }.join()

    private fun countNonBlack(pixels: IntArray): Int = pixels.count { (it and 0x00ffffff) != 0 }

    private fun countDiffPixels(a: IntArray, b: IntArray): Int {
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        return diff
    }

    private fun sampleTelemetry(rt: EmulatorRuntime, label: String): Map<String, Any?> {
        val orientEnum = readU32(rt, 0x018799F0L)
        val roll0p1 = readU16(rt, 0x018798E4L)
        val pitch0p1 = readU16(rt, 0x01879918L)
        val filtX = readS16(rt, 0x0187995EL)
        val filtY = readS16(rt, 0x01879960L)
        val filtZ = readS16(rt, 0x01879962L)
        val bank14Flag = readU8(rt, 0x04E109EBL)
        val biscomState = readU8(rt, 0x01C66974L)
        val scr = readU32(rt, 0x01CC6424L)
        val snap = rt.owner.submit {
            val i2c = rt.host.components().single { it.descriptor.id == "i2c-bus" }.component.state()
            val mma = rt.host.components().singleOrNull { it.descriptor.id == "mma8452q" }?.component?.state()
            val tmd = rt.host.components().singleOrNull { it.descriptor.id == "tmd2772" }?.component?.state()
            val maxFuel = rt.host.components().singleOrNull { it.descriptor.id == "max17048" }?.component?.state()
            val wm = rt.host.components().singleOrNull { it.descriptor.id == "wm8962" }?.component?.state()
            val spi = rt.host.components().single { it.descriptor.id == "soc-spi" }.component.state()
            val op = rt.host.ports.find(CameraOrientationPort.MMA8452Q_PORT_NAME, CameraOrientationPort::class.java)
                ?: rt.host.ports.find("i2c-bus.orientation", CameraOrientationPort::class.java)
            val ip = rt.host.ports.find("soc-spi.biscom.ibis", IbisShiftPort::class.java)
            val lcdc = rt.host.ports.find("soc-vglib-init.lcdc1-functional", com.github.nyabanban.fuji.emulator.api.MmioDevice::class.java)
            val r008 = lcdc?.read(0x008, 4) ?: -1L
            val disp = rt.host.ports.find("display.main", DisplayPort::class.java)
            val evf = rt.host.ports.find("display.evf", DisplayPort::class.java)
            mapOf(
                "i2c" to i2c,
                "mma" to mma,
                "tmd" to tmd,
                "maxFuel" to maxFuel,
                "wm" to wm,
                "spi" to spi,
                "orientPort" to (op != null),
                "ibisPort" to (ip != null),
                "lcdc008" to r008,
                "mainSeq" to (disp?.sequence ?: -1L),
                "evfSeq" to (evf?.sequence ?: -1L),
            )
        }.join()
        @Suppress("UNCHECKED_CAST")
        val i2cMap = snap["i2c"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val mmaMap = snap["mma"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val spiMap = snap["spi"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val buses = i2cMap["buses"] as? List<Map<String, Any?>> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val bodyAccel = (mmaMap?.get("bodyAccel") as? Map<String, Any?>)
            ?: (buses.firstOrNull { (it["index"] as? Number)?.toInt() == 1 }?.get("accel") as? Map<String, Any?>)
        @Suppress("UNCHECKED_CAST")
        val lcdAccel = (mmaMap?.get("lcdAccel") as? Map<String, Any?>)
            ?: (buses.firstOrNull { (it["index"] as? Number)?.toInt() == 2 }?.get("accel") as? Map<String, Any?>)
        val accels = mapOf("body_i2c1_0x1c" to bodyAccel, "lcd_i2c2_0x1d" to lcdAccel)
        @Suppress("UNCHECKED_CAST")
        val ibis = spiMap["ibis"] as? Map<String, Any?>
        println(
            "[GYRO-E2E][$label] tick=${rt.telemetry.virtualTick / 54_000L}ms scr=0x${scr.toString(16)} " +
                "lcdc008=0x${(snap["lcdc008"] as Long).toString(16)} mainSeq=${snap["mainSeq"]} evfSeq=${snap["evfSeq"]} " +
                "orientEnum=$orientEnum roll=${roll0p1 / 10.0}deg pitch=${pitch0p1 / 10.0}deg " +
                "filtXYZ=($filtX,$filtY,$filtZ) b14[89eb]=$bank14Flag biscomSt=$biscomState " +
                "bodyAccel=${bodyAccel?.get("active")}/w=${bodyAccel?.get("writeCount")}/r=${bodyAccel?.get("readCount")} " +
                "lcdAccel=${lcdAccel?.get("active")}/w=${lcdAccel?.get("writeCount")}/r=${lcdAccel?.get("readCount")} " +
                "tmd=${snap["tmd"]} maxFuel=${snap["maxFuel"]} wm=${snap["wm"]} " +
                "ibis=$ibis orientPort=${snap["orientPort"]} ibisPort=${snap["ibisPort"]}",
        )
        return mapOf(
            "orientEnum" to orientEnum,
            "rollDeg" to (roll0p1 / 10.0),
            "pitchDeg" to (pitch0p1 / 10.0),
            "filtX" to filtX,
            "filtY" to filtY,
            "filtZ" to filtZ,
            "accels" to accels,
            "tmd" to snap["tmd"],
            "maxFuel" to snap["maxFuel"],
            "wm" to snap["wm"],
            "evfSeq" to snap["evfSeq"],
            "mainSeq" to snap["mainSeq"],
            "ibis" to ibis,
        )
    }

    @Test
    fun liveFirmwareTracksMma8452qPortraitOrientationAndBiscomIbis(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = (System.getProperty("fuji.releaseStageDir") ?: System.getenv("FUJI_RELEASE_STAGE_DIR"))
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires staged release at $stagedRoot",
        )

        val workRoot = tempRoot.resolve("gyro-ibis-release")
        copyDirectory(stagedRoot, workRoot)
        overlayWorktreeStage(repo, workRoot)

        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))
        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Boot to OOBE (0x0421)
            stepBy(rt, 620_000_000L)
            val bootStatus = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            assertEquals("running", bootStatus)
            assertEquals(0x0421, readU32(rt, 0x01CC6424L), "Expected Language OOBE 0x0421")

            // 2. Complete OOBE -> Live STILL HUD (0x0100)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0423, readU32(rt, 0x01CC6424L), "Expected Area OOBE 0x0423")
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0422, readU32(rt, 0x01CC6424L), "Expected Date/Time OOBE 0x0422")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            assertEquals(0x0482, readU32(rt, 0x01CC6424L), "Expected Start App OOBE 0x0482")
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            var hudReached = false
            var lastScr = 0
            var lastNonBlack = 0
            for (iter in 0 until 45) {
                stepBy(rt, 6_000_000L)
                val scr = readU32(rt, 0x01CC6424L)
                val (_, _, px) = captureMain(rt)
                lastScr = scr
                lastNonBlack = countNonBlack(px)
                if (scr == 0x0100 && lastNonBlack > 150_000) {
                    hudReached = true
                    break
                }
            }
            if (!hudReached) {
                sampleTelemetry(rt, "HUD_TIMEOUT_scr=0x${lastScr.toString(16)}_nb=$lastNonBlack")
            }
            assertTrue(hudReached, "Expected STILL LiveView HUD 0x0100 (lastScr=0x${lastScr.toString(16)}, lastNonBlack=$lastNonBlack)")

            // Let levelsens (10 Hz, 5-sample median) settle in Landscape 0°
            stepVirtualMs(rt, 800L)
            val land = sampleTelemetry(rt, "LANDSCAPE_0")
            val (_, _, landPixels) = captureMain(rt)

            // Rotate +90° (Portrait CW)
            patchComponent(rt, "i2c-bus", mapOf("orientationPreset" to "portrait-cw"))
            stepVirtualMs(rt, 900L)
            val portCw = sampleTelemetry(rt, "PORTRAIT_CW_90")
            val (_, _, portCwPixels) = captureMain(rt)
            val diffLandToPort = countDiffPixels(landPixels, portCwPixels)
            println("[GYRO-E2E] display.main diffPixels(Landscape -> Portrait CW) = $diffLandToPort")

            // Rotate 180° (Inverted)
            patchComponent(rt, "i2c-bus", mapOf("orientationPreset" to "inverted"))
            stepVirtualMs(rt, 900L)
            val inv = sampleTelemetry(rt, "INVERTED_180")

            // Rotate -90° (Portrait CCW)
            patchComponent(rt, "i2c-bus", mapOf("orientationPreset" to "portrait-ccw"))
            stepVirtualMs(rt, 900L)
            val portCcw = sampleTelemetry(rt, "PORTRAIT_CCW_270")

            // Pitch +25° in Landscape
            patchComponent(rt, "i2c-bus", mapOf("orientationPreset" to "custom", "rollDeg" to 0.0, "pitchDeg" to 25.0))
            stepVirtualMs(rt, 900L)
            val pitched = sampleTelemetry(rt, "PITCH_PLUS_25")

            // Return to Landscape 0°
            patchComponent(rt, "i2c-bus", mapOf("orientationPreset" to "landscape", "pitchDeg" to 0.0))
            stepVirtualMs(rt, 900L)
            val landBack = sampleTelemetry(rt, "LANDSCAPE_BACK_0")

            // Assertions on authentic firmware levelsens + MMA8452Q + biscom IBIS
            val statusEnd = rt.owner.submit { rt.session.state()["status"].toString() }.join()
            assertEquals("running", statusEnd, "Emulator must stay running through all rotations")
            assertEquals(1, land["orientEnum"], "Landscape (0°) expected levelsens orientEnum=1: $land")
            assertEquals(2, portCw["orientEnum"], "Portrait CW (+90°) expected levelsens orientEnum=2: $portCw")
            assertEquals(3, inv["orientEnum"], "Inverted (180°) expected levelsens orientEnum=3: $inv")
            assertEquals(4, portCcw["orientEnum"], "Portrait CCW (-90°) expected levelsens orientEnum=4: $portCcw")
            assertEquals(1, landBack["orientEnum"], "Return to Landscape expected levelsens orientEnum=1: $landBack")

            assertEquals(0.0, (land["rollDeg"] as Double), 0.5, "Landscape expected roll ~0°: $land")
            assertEquals(90.0, (portCw["rollDeg"] as Double), 0.5, "Portrait CW (+90°) expected roll ~90°: $portCw")
            assertEquals(180.0, (inv["rollDeg"] as Double), 0.5, "Inverted (180°) expected roll ~180°: $inv")
            assertEquals(270.0, (portCcw["rollDeg"] as Double), 0.5, "Portrait CCW (-90°) expected roll ~270°: $portCcw")
            assertEquals(25.0, (pitched["pitchDeg"] as Double), 0.5, "Pitch +25° expected firmware pitch ~25.0°: $pitched")
            assertEquals(0.0, (landBack["rollDeg"] as Double), 0.5, "Return to Landscape expected roll ~0°: $landBack")

            @Suppress("UNCHECKED_CAST")
            val accels = landBack["accels"] as? Map<String, Map<String, Any?>>
            assertNotNull(accels)
            assertEquals(true, accels["body_i2c1_0x1c"]?.get("active"), "Primary MMA8452Q (I2C1 0x1C) must be active")
            assertTrue((accels["body_i2c1_0x1c"]?.get("writeCount") as Number).toLong() >= 50L, "Primary MMA8452Q must receive full 25-reg init")
            assertEquals(true, accels["lcd_i2c2_0x1d"]?.get("active"), "Tilting LCD MMA8452Q (I2C2 0x1D) must be active")
            val lcdReads = (accels["lcd_i2c2_0x1d"]?.get("readCount") as Number).toLong()
            assertTrue(lcdReads > 0L, "Tilting LCD MMA8452Q must have firmware readCount > 0")

            @Suppress("UNCHECKED_CAST")
            val ibis = landBack["ibis"] as? Map<String, Any?>
            assertNotNull(ibis)
            val cmdCount = (ibis["commandCount"] as Number).toLong()
            assertTrue(cmdCount > 100L, "biscom (SPI2) must have processed live firmware IBIS commands: $ibis")
            assertEquals(IbisShiftPort.MODE_CONTINUOUS, ibis["ibisMode"], "biscom IBIS must enter CONTINUOUS in LiveView: $ibis")
            assertEquals(false, ibis["stageLocked"], "biscom IBIS stage must be unlocked in CONTINUOUS LiveView: $ibis")
            val expectedFocalMm = rt.owner.submit {
                (rt.host.components().single { it.descriptor.id == "lens" }.component.state()["focalLengthMm"] as Number).toDouble()
            }.join()
            assertEquals(expectedFocalMm, (ibis["focalLengthMm"] as Number).toDouble(), 0.5, "biscom focal length must match active mounted lens (${expectedFocalMm}mm): $ibis")

            // Exercise standalone TMD27723 eye sensor (FAR -> NEAR -> FAR) over I2C1 @ 0x39 + active-LOW GPIO(0, 8) INT
            assertEquals(2, readU8(rt, 0x0187974EL), "TMD27723 Task 0x46 must settle in FAR state (2) at idle")
            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to true))
            stepVirtualMs(rt, 400L)
            val evfNear = sampleTelemetry(rt, "EYE_NEAR")
            assertEquals(1, readU8(rt, 0x0187974EL), "Standalone tmd2772 eyeSensorNear=true must wake Task 0x46 via GPIO(0,8) LOW and enter NEAR state (1)")
            @Suppress("UNCHECKED_CAST")
            val tmdNearDev = (evfNear["tmd"] as? Map<String, Any?>)?.get("device") as? Map<String, Any?>
            assertNotNull(tmdNearDev, "Standalone tmd2772 component must report device state")
            assertEquals(432, (tmdNearDev["lowThreshold"] as Number).toInt(), "Firmware must reprogram TMD27723 PILT=432 on NEAR: $tmdNearDev")
            assertEquals(65535, (tmdNearDev["highThreshold"] as Number).toInt(), "Firmware must reprogram TMD27723 PIHT=65535 on NEAR: $tmdNearDev")
            assertEquals(false, tmdNearDev["intAsserted"], "Firmware 0xE5 clear must release TMD27723 INT HIGH after NEAR")

            patchComponent(rt, "tmd2772", mapOf("eyeSensorNear" to false))
            stepVirtualMs(rt, 400L)
            val evfFar = sampleTelemetry(rt, "EYE_FAR")
            assertEquals(2, readU8(rt, 0x0187974EL), "Standalone tmd2772 eyeSensorNear=false must wake Task 0x46 via GPIO(0,8) LOW and return to FAR state (2)")
            @Suppress("UNCHECKED_CAST")
            val tmdFarDev = (evfFar["tmd"] as? Map<String, Any?>)?.get("device") as? Map<String, Any?>
            assertNotNull(tmdFarDev)
            assertEquals(0, (tmdFarDev["lowThreshold"] as Number).toInt(), "Firmware must reprogram TMD27723 PILT=0 on FAR: $tmdFarDev")
            assertEquals(662, (tmdFarDev["highThreshold"] as Number).toInt(), "Firmware must reprogram TMD27723 PIHT=662 on FAR: $tmdFarDev")
            assertEquals(false, tmdFarDev["intAsserted"], "Firmware 0xE5 clear must release TMD27723 INT HIGH after FAR")

            // Verify standalone MAX17048/49 fuel gauge & WM8962 codec (I2C1 @ 0x1A + SPI4 DSP2) are live
            @Suppress("UNCHECKED_CAST")
            val maxFuel = evfFar["maxFuel"] as? Map<String, Any?>
            assertNotNull(maxFuel, "Standalone max17048 component must report state")
            assertEquals(true, maxFuel["busAttached"], "Standalone max17048 must attach to I2C1 @ 0x36: $maxFuel")
            assertEquals(true, maxFuel["batteryObserved"], "Standalone max17048 must observe battery telemetry: $maxFuel")

            @Suppress("UNCHECKED_CAST")
            val wmDevice = (evfFar["wm"] as? Map<String, Any?>)?.get("device") as? Map<String, Any?>
            assertNotNull(wmDevice, "Standalone wm8962 component must report device state")
            assertTrue((wmDevice["spiWordCount"] as Number).toLong() > 100L, "Standalone wm8962 must handle live SPI4 init: $wmDevice")
            assertTrue((wmDevice["dsp2ProgramWords"] as Number).toInt() > 0, "Standalone wm8962 must receive live DSP2 microcode: $wmDevice")
            assertEquals(true, wmDevice["sysclkEnabled"], "Standalone wm8962 SYSCLK must be enabled by live firmware: $wmDevice")
            assertEquals(true, wmDevice["dsp2Enabled"], "Standalone wm8962 DSP2 must be enabled by live firmware: $wmDevice")

            val relaxedSummary = rt.owner.submit { rt.session.relaxedFeedbackSummaryReport() }.join()
            if (relaxedSummary != null) {
                assertTrue(relaxedSummary.contains("discoveryFallbacks=0"), "Zero discovery fallbacks expected: $relaxedSummary")
                assertTrue(relaxedSummary.contains("refused=0"), "Zero refused flash ops expected: $relaxedSummary")
                assertTrue(!relaxedSummary.contains("0xfff3e") && !relaxedSummary.contains("0xfff3f"), "Zero I2C1/I2C2 fallbacks expected: $relaxedSummary")
                assertTrue(!relaxedSummary.contains("0xfff36") && !relaxedSummary.contains("0xfff41"), "Zero SPI4/GPIO fallbacks expected: $relaxedSummary")
            }
            println("[GYRO-E2E] ALL ASSERTIONS PASSED! diffLandToPort=$diffLandToPort relaxed=$relaxedSummary")
        }
    }
}
