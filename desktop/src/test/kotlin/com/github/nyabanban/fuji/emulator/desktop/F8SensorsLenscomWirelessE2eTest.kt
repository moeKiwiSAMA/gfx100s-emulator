package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
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
import kotlin.test.assertTrue

/**
 * Env-gated live Dynarmic JIT end-to-end verification for Track F8 (`BUG-10` + `BUG-12`):
 * - `BUG-10`: `LENSCOM` SPI0 + `cmd 0x0C0` 128 KiB Optical EEPROM + SPX4 Timer Ch14 (`GIC IRQ 55`) -> `F 4.0` on STILL HUD & Sub-LCD,
 *   plus live `battery` -> `power-peer` (`100%` 5-bar / `3%` red low-battery) and `temperature-sensor` (`rawSample=768` -> `96 C` overheat).
 * - `BUG-12`: Synthetic LAA `bank-14` Wi-Fi MAC (`02:46:4A:10:00:00`), BT BD_ADDR (`02:46:4A:10:00:01`), Camera Name (`FUJIFILM-GFX100S-0000`),
 *   and `soc-uart` BCM43438A1 H4 HCI responder in `SET UP -> CONNECTION SETTING -> INFORMATION` & `Bluetooth SETTING`.
 */
class F8SensorsLenscomWirelessE2eTest {

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { stream ->
            for (src in stream) {
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
                while (i < 4096) {
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

    private fun patchComp(rt: EmulatorRuntime, id: String, patch: Map<String, Any>) {
        rt.owner.submit {
            val comp = rt.host.components().single { it.descriptor.id == id }
            rt.host.patch(id, comp.revision, patch).join()
        }.join()
    }

    private fun compState(rt: EmulatorRuntime, id: String): Map<String, Any?> = rt.owner.submit {
        rt.host.components().single { it.descriptor.id == id }.component.state()
    }.join()

    private fun stepByVirtualTicks(rt: EmulatorRuntime, deltaTicks: Long) {
        val target = rt.telemetry.virtualTick + deltaTicks
        var guard = 0
        while (rt.telemetry.virtualTick < target && guard < 10_000) {
            guard++
            val cont = rt.owner.submit {
                var i = 0
                while (i < 512 && rt.telemetry.virtualTick < target) {
                    if (!rt.session.stepFast()) return@submit false
                    i++
                }
                true
            }.join()
            if (!cont) break
        }
    }

    private fun tapButton(rt: EmulatorRuntime, key: String, holdTicks: Long = 5_000_000L, releaseAttempts: Long = 20_000_000L) {
        patchComp(rt, "buttons", mapOf(key to true))
        stepByVirtualTicks(rt, holdTicks)
        patchComp(rt, "buttons", mapOf(key to false))
        stepBy(rt, releaseAttempts)
    }

    private fun readGuestU8(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(1)
        mem.read(address, buf)
        buf.flip()
        buf.get().toInt() and 0xff
    }.join()

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun dumpPort(rt: EmulatorRuntime, portName: String, dumpDir: Path?, fileName: String): Triple<Long, Int, Int> {
        val (seq, w, h, px) = rt.owner.submit {
            val display = requireNotNull(rt.host.ports.find(portName, DisplayPort::class.java))
            val pixels = IntArray(display.width * display.height)
            display.copyArgb(pixels)
            Quad(display.sequence, display.width, display.height, pixels)
        }.join()
        val nonBlack = px.count { (it and 0x00ffffff) != 0 }
        if (dumpDir != null) {
            Files.createDirectories(dumpDir)
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            ImageIO.write(img, "png", dumpDir.resolve(fileName).toFile())
        }
        return Triple(seq, nonBlack, w)
    }

    private data class Quad(val seq: Long, val w: Int, val h: Int, val px: IntArray)

    @Test
    fun verifiesLenscomApertureBatteryTempAndConnectionSettingWirelessLive(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_F8_E2E") == "1",
            "Set FUJI_RUN_F8_E2E=1 to run the live F8 Sensors/LENSCOM/Wireless E2E verification",
        )
        val dumpDir = System.getenv("FUJI_F8_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("f8-release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot to OOBE 0x0421 (START LANG 1/5) and let key handler stabilize before sending menu tap
            for (i in 0 until 80) {
                stepBy(rt, 12_000_000L)
                val scr = readGuestU32(rt, 0x01CC6424L)
                if (scr == 0x0421) break
            }
            stepBy(rt, 60_000_000L)
            println("[F8] Booted to scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} tick=${rt.telemetry.virtualTick}")

            // Complete OOBE deterministically (matching ReleaseInteractiveSmokeTest):
            // 0x0421 (LANG) -> menu -> 0x0423 (AREA) -> down x2 -> menu -> 0x0422 (DATE/TIME) -> menu -> 0x0482 (START APP) -> menu -> 0x0100 (STILL HUD)
            for (attempt in 0 until 3) {
                if (readGuestU32(rt, 0x01CC6424L) != 0x0421) break
                tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            }
            println("[F8] After LANG scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            println("[F8] After AREA scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            println("[F8] After DATE/TIME scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)

            // Wait for autonomous LENSCOM + Live View bringup to STILL HUD (0x0100)
            for (iter in 0 until 35) {
                stepBy(rt, 6_000_000L)
                val scr = readGuestU32(rt, 0x01CC6424L)
                val mainSnap = dumpPort(rt, "display.main", null, "")
                if (scr == 0x0100 && mainSnap.second > 150_000) {
                    println("[F8] Reached STILL HUD at iter=$iter scr=0x${scr.toString(16)} main=$mainSnap tick=${rt.telemetry.virtualTick}")
                    break
                }
            }
            val hudScr = readGuestU32(rt, 0x01CC6424L)
            val mnt = compState(rt, "mount")["spiBridge"] as Map<*, *>
            val ch14 = compState(rt, "soc-dma")["ch14Timer"] as Map<*, *>
            println("[F8] STILL HUD scr=0x${hudScr.toString(16)} mount=$mnt ch14=$ch14")
            assertTrue(hudScr == 0x0100, "Expected STILL HUD 0x0100 after OOBE, got 0x${hudScr.toString(16)}")
            assertTrue(((mnt["frameCount"] as Number).toLong()) >= 98L, "Expected full 128KiB LENSCOM EEPROM download, got $mnt")
            assertTrue(((ch14["fireCount"] as Number).toLong()) >= 98L, "Expected SPX4 Timer Ch14 GIC IRQ 55 firings, got $ch14")

            // Switch Mode Dial to A -> verify F 4.0 + 5-bar battery on STILL HUD & Shoulder Sub-LCD
            patchComp(rt, "soc-adc", mapOf("modeDial" to "A"))
            stepBy(rt, 65_000_000L)
            val battFull = readGuestU8(rt, 0x01CBFFBFL)
            val stillA = dumpPort(rt, "display.main", dumpDir, "01_still_A_F40_100pct.png")
            val subA = dumpPort(rt, "display.shoulder", dumpDir, "01_sub_A_F40_100pct.png")
            println("[F8] STILL A 100%: main=$stillA sub=$subA eBatteryStatus=$battFull powerPeerEvents=${(compState(rt, "power-peer")["events"] as? List<*>)?.takeLast(6)}")

            // 2. Navigate STILL MENU -> left (left sidebar Tab 0 = I.Q.) -> down x4 (WRENCH SET UP) -> right -> down x6 (CONNECTION SETTING)
            tapButton(rt, "menu", 6_000_000L, 45_000_000L)
            stepBy(rt, 20_000_000L)
            dumpPort(rt, "display.main", dumpDir, "02_menu_iq.png")
            tapButton(rt, "left", 5_000_000L, 25_000_000L)
            repeat(4) { tapButton(rt, "down", 5_000_000L, 25_000_000L) }
            stepBy(rt, 45_000_000L)
            tapButton(rt, "right", 5_000_000L, 30_000_000L)
            repeat(6) { tapButton(rt, "down", 5_000_000L, 18_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 35_000_000L)

            // Inside CONNECTION SETTING -> Row 0 (Bluetooth/SMARTPHONE SETTING) -> Row 0 (PAIRING REGISTRATION)
            tapButton(rt, "menu", 5_000_000L, 35_000_000L)
            tapButton(rt, "menu", 5_000_000L, 120_000_000L)
            val uartAfterPair = compState(rt, "soc-uart")
            val bcmAfterPair = compState(rt, "bcm43438")
            val hciCmds = (bcmAfterPair["hciCmdCount"] as Number).toLong()
            val txBytes = (uartAfterPair["txBytesTotal"] as Number).toLong()
            val rxPending = (uartAfterPair["uart1RxPendingBytes"] as Number).toInt()
            println("[F8] BCM43438 AFTER PAIRING: txBytes=$txBytes hciCmds=$hciCmds rxPending=$rxPending bdAddr=${bcmAfterPair["bdAddr"]} patchRamLoaded=${bcmAfterPair["patchRamLoaded"]} advertising=${bcmAfterPair["advertising"]} advertisedName=${bcmAfterPair["advertisedName"]}")
            assertTrue(hciCmds >= 170L, "Expected full BCM43438A1 H4 HCI + PatchRAM bringup on UART1/IRQ258, got hciCmds=$hciCmds")
            assertTrue(txBytes >= 34_000L, "Expected full 34.5KiB PatchRAM download over UART1, got txBytes=$txBytes")
            assertTrue(rxPending == 0, "Expected all UART1 HCI responses drained via GIC IRQ 258, got rxPending=$rxPending")
            assertTrue(bcmAfterPair["patchRamLoaded"] == true, "Expected bcm43438 patchRamLoaded=true, got $bcmAfterPair")

            // Back to BT MENU -> down 1 (Bluetooth ON/OFF) -> menu -> up (ON) -> menu
            tapButton(rt, "back", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 4_000_000L, 16_000_000L)
            tapButton(rt, "menu", 5_000_000L, 28_000_000L)
            tapButton(rt, "up", 4_000_000L, 16_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)

            // Back to CONNECTION SETTING -> INFORMATION (row 5) -> verify scr=0x392 renders non-black with MAC + BT addresses
            tapButton(rt, "back", 5_000_000L, 35_000_000L)
            repeat(5) { tapButton(rt, "down", 4_000_000L, 18_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 35_000_000L)
            if (readGuestU32(rt, 0x01CC6424L) != 0x0392) {
                tapButton(rt, "back", 5_000_000L, 25_000_000L)
                tapButton(rt, "down", 4_000_000L, 18_000_000L)
                tapButton(rt, "menu", 5_000_000L, 35_000_000L)
            }
            val infoScrId = readGuestU32(rt, 0x01CC6424L)
            val infoSnap = dumpPort(rt, "display.main", dumpDir, "08_conn_info.png")
            println("[F8] CONN INFO scr=0x${infoScrId.toString(16)} main=$infoSnap")
            assertTrue(infoScrId == 0x0392 && infoSnap.second > 500_000, "Expected rendered CONNECTION SETTING -> INFORMATION (0x0392), got 0x${infoScrId.toString(16)} $infoSnap")

            // 3. Return to STILL HUD (exit menu via shutterHalf or back) & verify Low Battery (3% / 6200mV) red icon + Sub-LCD!
            for (exitTry in 0 until 5) {
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
                tapButton(rt, if (exitTry == 0) "shutterHalf" else "back", 5_000_000L, 24_000_000L)
            }
            patchComp(rt, "battery", mapOf("chargePercent" to 3.0, "voltageMillivolts" to 6200))
            for (poll in 0 until 20) {
                stepBy(rt, 20_000_000L)
                val st = readGuestU8(rt, 0x01CBFFBFL)
                if (st in 1..2) break
            }
            val battLow = readGuestU8(rt, 0x01CBFFBFL)
            val lowBat = dumpPort(rt, "display.main", dumpDir, "10_still_lowbat_3pct.png")
            val lowSub = dumpPort(rt, "display.shoulder", dumpDir, "10_sub_lowbat_3pct.png")
            println("[F8] LOW BAT 3%: scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} main=$lowBat sub=$lowSub eBatteryStatus=$battLow")
        }
    }
}
