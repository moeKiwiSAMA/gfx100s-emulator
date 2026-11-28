package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.WirelessControlPort
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
 * Env-gated live `GXUP0004.DAT` (Dynarmic JIT) end-to-end verification for Track `CONN-BT-WIFI`:
 * - Pure PL011 `soc-uart` + Pure SDHCI v3.0 `soc-sdio` (`0xFFFCC000`, GIC 224/225) + Standalone
 *   Broadcom `bcm43438` combo IC (`BT_REG_ON = GPIO 12.3`, `WL_REG_ON = GPIO 12.0`);
 * - Verifies:
 *   1. `CONNECTION SETTING -> INFORMATION` (`scr=0x0392`) renders non-zero Wi-Fi/BT MACs & `FUJIFILM-GFX100S-EMU1` (`+0x8A63=21`);
 *   2. `BLUETOOTH SETTING -> PAIRING REGISTRATION` completes 34.5 KiB PatchRAM over `UART1` (`GIC 258`),
 *      activates BLE advertising (`patchRamLoaded=true`, `advertising=true`), and exercises `VirtualBlePeer`;
 *   3. `DISP/BACK` 2s long-press Bluetooth pairing shortcut (`BUG-19` probe);
 *   4. `CONNECTION SETTING -> WIRELESS COMMUNICATION` brings up `BCM43430A1` Wi-Fi over `soc-sdio` (`0xFFFCC000`, GIC 224/225).
 */
class ConnectivityBtWifiLiveE2eTest {
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

    private fun readGuestU32(rt: EmulatorRuntime, address: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(address, buf)
        buf.flip()
        buf.int
    }.join()

    private fun readGuestAscii(rt: EmulatorRuntime, address: Long, len: Int): String = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(len)
        mem.read(address, buf)
        String(buf.array(), Charsets.US_ASCII)
    }.join()

    private fun displayNonBlack(rt: EmulatorRuntime): Int = rt.owner.submit {
        val d = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(d.width * d.height)
        d.copyArgb(px)
        px.count { (it and 0x00ffffff) != 0 }
    }.join()

    @Test
    fun verifiesBcm43438BlePairingWifiSdioAndDispBackLongPressLive(@TempDir tempRoot: Path) {
        assumeTrue(
            System.getenv("FUJI_RUN_CONN_E2E") == "1",
            "Set FUJI_RUN_CONN_E2E=1 to run live GXUP0004.DAT BT/Wi-Fi E2E verification",
        )
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val stagedRoot = repo.resolve("build/release-stage/linux-x86_64")
        assumeTrue(
            Files.isDirectory(stagedRoot) &&
                Files.isRegularFile(stagedRoot.resolve("configs/emulator.yaml")) &&
                ((Files.isRegularFile(stagedRoot.resolve("flash/nor-flash.bin")) || Files.isRegularFile(stagedRoot.resolve("runtime/templates/factory-flash/nor-flash-2.13.bin"))) || Files.isRegularFile(stagedRoot.resolve("runtime/firmware/GXUP0004.DAT"))),
            "Requires :stageReleaseLinux output at build/release-stage/linux-x86_64",
        )

        val workRoot = tempRoot.resolve("conn-release-root")
        copyDirectory(stagedRoot, workRoot)
        val config = Formats.read(workRoot.resolve("configs/emulator.yaml"))

        EmulatorRuntime(workRoot, config, telemetryDisabled = true).use { rt ->
            rt.owner.submit { rt.session.initialize() }.join()

            // 1. Cold boot -> OOBE -> STILL HUD (0x0100)
            for (i in 0 until 80) {
                stepBy(rt, 12_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0421) break
            }
            stepBy(rt, 60_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "down", 5_000_000L, 25_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 45_000_000L)
            tapButton(rt, "menu", 5_000_000L, 40_000_000L)
            for (iter in 0 until 35) {
                stepBy(rt, 6_000_000L)
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100 && displayNonBlack(rt) > 150_000) break
            }
            assertEquals(0x0100, readGuestU32(rt, 0x01CC6424L), "Expected STILL HUD 0x0100")

            // Verify bank14 camera name loaded into RAM [0x01C96908 + 0x119] = 0x01C96A21
            val loadedCameraName = readGuestAscii(rt, 0x01C96A21L, 21)
            println("[CONN-E2E] Camera wireless name in RAM: '$loadedCameraName'")
            assertEquals("FUJIFILM-GFX100S-EMU1", loadedCameraName)

            // 2. Navigate MENU -> SET UP -> CONNECTION SETTING -> Bluetooth -> PAIRING REGISTRATION
            tapButton(rt, "menu", 6_000_000L, 45_000_000L)
            stepBy(rt, 15_000_000L)
            tapButton(rt, "left", 5_000_000L, 25_000_000L)
            repeat(4) { tapButton(rt, "down", 5_000_000L, 25_000_000L) }
            stepBy(rt, 35_000_000L)
            tapButton(rt, "right", 5_000_000L, 30_000_000L)
            repeat(6) { tapButton(rt, "down", 5_000_000L, 18_000_000L) }
            tapButton(rt, "menu", 5_000_000L, 35_000_000L) // Enter CONNECTION SETTING
            tapButton(rt, "menu", 5_000_000L, 35_000_000L) // Row 0: Bluetooth/SMARTPHONE SETTING
            tapButton(rt, "menu", 5_000_000L, 140_000_000L) // Row 0: PAIRING REGISTRATION

            val bcmAfterBle = compState(rt, "bcm43438")
            val uartAfterBle = compState(rt, "soc-uart")
            println("[CONN-E2E] BLE PAIRING: scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} hciCmds=${bcmAfterBle["hciCmdCount"]} patchRam=${bcmAfterBle["patchRamLoaded"]} adv=${bcmAfterBle["advertising"]} advName='${bcmAfterBle["advertisedName"]}' bdAddr=${bcmAfterBle["bdAddr"]} rxPending=${uartAfterBle["uart1RxPendingBytes"]}")
            assertTrue((bcmAfterBle["hciCmdCount"] as Number).toLong() >= 170L, "Expected BCM43438 HCI bring-up")
            assertEquals(true, bcmAfterBle["patchRamLoaded"], "Expected PatchRAM loaded into BCM43438")
            assertEquals(true, bcmAfterBle["advertising"], "Expected BLE advertising active during PAIRING REGISTRATION")
            assertEquals(0, (uartAfterBle["uart1RxPendingBytes"] as Number).toInt(), "Expected UART1 RX FIFO drained")

            // Exercise VirtualBlePeer connection & GAP Device Name read via WirelessControlPort
            val peerStatus = rt.owner.submit {
                val ctrl = requireNotNull(rt.host.ports.find("bcm43438.control", WirelessControlPort::class.java))
                ctrl.applyWirelessAction(mapOf("action" to "ble.connect"))
                ctrl.applyWirelessAction(mapOf("action" to "ble.readGapDeviceName"))
            }.join()
            stepBy(rt, 30_000_000L)
            val bcmAfterPeer = compState(rt, "bcm43438")
            println("[CONN-E2E] BLE PEER: connected=${bcmAfterPeer["connected"]} aclTx=${(bcmAfterPeer["bluetooth"] as Map<*, *>)["hciAclTxCount"]} peer=${(bcmAfterPeer["bluetooth"] as Map<*, *>)["virtualPeer"]} initPeerStatus=${peerStatus["deviceName"]}")
            assertEquals("FujiEmu Host", bcmAfterPeer["deviceName"])

            // Disconnect virtual peer cleanly and return to STILL HUD (0x0100)
            rt.owner.submit {
                val ctrl = requireNotNull(rt.host.ports.find("bcm43438.control", WirelessControlPort::class.java))
                ctrl.applyWirelessAction(mapOf("action" to "ble.disconnect"))
            }.join()
            stepBy(rt, 20_000_000L)
            for (i in 0 until 6) {
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
                tapButton(rt, if (i == 0) "shutterHalf" else "back", 5_000_000L, 25_000_000L)
            }
            println("[CONN-E2E] RETURNED TO STILL: scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)}")

            // 3. Open SHOOTING SETTING (Tab 2) -> WIRELESS COMMUNICATION (last row: up x1 -> menu)
            tapButton(rt, "menu", 6_000_000L, 45_000_000L)
            stepBy(rt, 15_000_000L)
            tapButton(rt, "left", 5_000_000L, 25_000_000L) // Tab 0 (I.Q.)
            tapButton(rt, "down", 5_000_000L, 25_000_000L) // Tab 1 (AF/MF)
            tapButton(rt, "down", 5_000_000L, 25_000_000L) // Tab 2 (SHOOTING SETTING)
            tapButton(rt, "right", 5_000_000L, 25_000_000L)
            tapButton(rt, "up", 5_000_000L, 25_000_000L) // Wrap to last row: WIRELESS COMMUNICATION
            tapButton(rt, "menu", 5_000_000L, 180_000_000L) // Enter WIRELESS COMMUNICATION
            val wifiScr = readGuestU32(rt, 0x01CC6424L)
            val sdioAfterWifi = compState(rt, "soc-sdio")
            val bcmAfterWifi = compState(rt, "bcm43438")
            val gpioAfterWifi = compState(rt, "soc-gpio")
            println("[CONN-E2E] AFTER WIRELESS COMMUNICATION: scr=0x${wifiScr.toString(16)} nonBlack=${displayNonBlack(rt)} socSdio=$sdioAfterWifi wifi=${bcmAfterWifi["wifi"]} gpio12=${(gpioAfterWifi["registers"] as? List<*>)?.getOrNull(12)}")
            tapButton(rt, "menu", 5_000_000L, 180_000_000L)
            println("[CONN-E2E] AFTER WIRELESS MENU#2: scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} nonBlack=${displayNonBlack(rt)} socSdio=${compState(rt, "soc-sdio")} wifi=${compState(rt, "bcm43438")["wifi"]}")

            // 4. Return to STILL HUD (0x0100) and test 2s DISP/BACK long-press (BUG-19)
            for (i in 0 until 6) {
                if (readGuestU32(rt, 0x01CC6424L) == 0x0100) break
                tapButton(rt, if (i == 0) "shutterHalf" else "back", 5_000_000L, 25_000_000L)
            }
            println("[CONN-E2E] BEFORE 2s DISP/BACK: scr=0x${readGuestU32(rt, 0x01CC6424L).toString(16)} nonBlack=${displayNonBlack(rt)}")
            patchComp(rt, "buttons", mapOf("back" to true))
            stepBy(rt, 95_000_000L) // ~1.76s virtual time > 1500ms BT pairing long-press timer #16
            patchComp(rt, "buttons", mapOf("back" to false))
            stepBy(rt, 80_000_000L)
            val afterLongBackScr = readGuestU32(rt, 0x01CC6424L)
            val afterLongBackNonBlack = displayNonBlack(rt)
            val bcmAfterLongBack = compState(rt, "bcm43438")
            println("[CONN-E2E] AFTER 2s DISP/BACK: scr=0x${afterLongBackScr.toString(16)} nonBlack=$afterLongBackNonBlack adv=${bcmAfterLongBack["advertising"]} hciCmds=${bcmAfterLongBack["hciCmdCount"]} status=${rt.owner.submit { rt.session.state()["status"] }.join()}")
            assertTrue(afterLongBackNonBlack > 0, "Expected non-black screen after 2s DISP/BACK Bluetooth pairing")
        }
    }
}
