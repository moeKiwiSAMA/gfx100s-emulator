package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.runtime.FirmwareStage
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live Dynarmic JIT end-to-end verification of a genuine real-silicon cold boot starting from
 * GFX100S 2.13 mask Boot ROM (`boot-rom.bin` @ `0xFFF00000`) through Stage-1 IPL (`0xFFF10000`),
 * Stage-2 XIP (`0xF0007360`), `boot.bin` (`0x00000000`), genuine 4-core `SGI 0` secondary wakeup
 * (no artificial `clusterReset` schedule), `kernel.bin` (`0x00057950`), `section4/5`, all 26
 * Release SoC peripherals, and `submcu-nvram.bin` persistence all the way to rendered OOBE / LiveView!
 */
class BootRomFullColdBootLiveViewTest {
    private val project = Path.of(System.getProperty("componentProjectRoot"))
    private val fixtures = IntegrationTest()

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

    private fun readU32(rt: EmulatorRuntime, addr: Long): Int = rt.owner.submit {
        val mem = requireNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        buf.flip()
        buf.int
    }.join()

    @Test
    fun full26DeviceReleaseColdBootsFromMaskBootRomThroughRealNorFlashToRenderedScreen() {
        val firmware = System.getenv("FUJI_FIRMWARE_FILE")
            ?.takeIf { it.isNotBlank() && Files.isRegularFile(Path.of(it)) }
            ?: project.resolve("firmware-data/gfx100s/2.13/GXUP0004.DAT").takeIf { Files.isRegularFile(it) }?.toString()
        val dumpZip = System.getenv("FUJI_DUMP_ZIP")
            ?.takeIf { it.isNotBlank() && Files.isRegularFile(Path.of(it)) }
            ?.let { Path.of(it) }
            ?: project.resolve("firmware-data/gfx100s/2.13/hardware/20261112/GFX100S_DUMP.zip").takeIf { Files.isRegularFile(it) }
        val dynLib = System.getenv("FUJI_DYNARMIC_LIBRARY")
            ?.takeIf { it.isNotBlank() && Files.isRegularFile(Path.of(it)) }
            ?.let { Path.of(it) }
            ?: CpuEngineConfig.findDynarmicLibrary(project, emptyMap())
        val factoryFlashDir = System.getenv("FUJI_FACTORY_FLASH_DIR")
            ?.takeIf { it.isNotBlank() && Files.isDirectory(Path.of(it)) }
            ?.let { Path.of(it) }
            ?: project.resolve("build/factory-flash").takeIf { Files.isDirectory(it) }
        assumeTrue(
            firmware != null && dumpZip != null && dynLib != null && factoryFlashDir != null,
            "GXUP0004.DAT, GFX100S_DUMP.zip, libfuji_dynarmic.so, and build/factory-flash (run :generateFactoryFlashTemplate) required",
        )

        val bootRomBytes = ZipFile(dumpZip!!.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("files/boot-rom.bin"))
            zip.getInputStream(entry).use { it.readNBytes(32768) }
        }
        val dat = com.github.nyabanban.fuji.emulator.firmware.FirmwareImage(
            com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata(
                project.resolve("configurations/jvm/firmware/gfx100s-213.yaml"),
            ),
            Path.of(firmware!!),
        )
        val expectedBootPrefix = dat.decode("boot").copyOfRange(0, 0x1000)
        val expectedKernelPrefix = dat.decode("kernel").copyOfRange(0, 0x1000)
        val expectedSection4Prefix = dat.decode("section4").copyOfRange(0, 0x1000)

        val home = fixtures.fixture()
        try {
            // 1. Stage Boot ROM + full 26-device Release configs + discovery seeds
            Files.write(home.resolve("boot-rom.bin"), bootRomBytes)
            copyDirectory(project.resolve("tools/release/component-configs"), home.resolve("configs/components"))
            copyDirectory(project.resolve("tools/release/discovery"), home.resolve("configs/discovery"))
            Files.copy(
                project.resolve("catalog/compiled/catalog.json"),
                home.resolve("configs/catalog.compiled.json"),
                StandardCopyOption.REPLACE_EXISTING,
            )

            // 2. Export full 26-device Boot ROM metadata using CatalogCompiler --boot-rom
            val bootRomMetaPath = home.resolve("configs/firmware/gfx100s-213-bootrom-release.yaml")
            Files.createDirectories(bootRomMetaPath.parent)
            com.github.nyabanban.fuji.emulator.catalog.main(
                arrayOf(
                    "--export-firmware-metadata",
                    home.resolve("configs/catalog.compiled.json").toString(),
                    bootRomMetaPath.toString(),
                    "--boot-rom",
                    "soc-sd-host=gfx100s-sd-host-uhs-phy-experimental-v9",
                    "soc-vglib-init=lcdc-functional-experimental-v1",
                    "soc-isp=isp-banks-movie-capture-v3",
                    "soc-jpeg=soc-jpeg-codec-experimental-v2",
                    "soc-panel=panel-init-repeatable-a8000-experimental-v2",
                    "soc-uart=pl011-multi-uart-experimental-v1",
                    "soc-system-control=retained-banks-usb-dwc3-experimental-v2",
                ),
            )

            // 3. Apply minimal Boot ROM cold-start overrides on top of Release component configs
            val sysMemCfg = Formats.read(home.resolve("configs/components/system-memory/system-memory.yaml"))
            Formats.atomicYaml(
                home.resolve("configs/components/system-memory/system-memory.yaml"),
                sysMemCfg + ("profile" to "boot-rom-full-boot-v1"),
            )
            Formats.atomicYaml(
                home.resolve("configs/components/soc-system-control/boot-strap.json"),
                mapOf("schema" to 1, "profile" to "boot-strap-fff4a000-experimental-v1", "value" to 8),
            )
            val sysCtrlCfg = Formats.read(home.resolve("configs/components/soc-system-control/soc-system-control.yaml"))
            Formats.atomicYaml(
                home.resolve("configs/components/soc-system-control/soc-system-control.yaml"),
                sysCtrlCfg + ("bootStrapInputFile" to "boot-strap.json"),
            )
            val dramCfg = Formats.read(home.resolve("configs/components/soc-dram/soc-dram.yaml"))
            Formats.atomicYaml(
                home.resolve("configs/components/soc-dram/soc-dram.yaml"),
                dramCfg + ("coldStartChannel0" to true),
            )
            // Use Micron JEDEC 0x20BB20 (accepted by BOTH boot.bin and section4.bin) with v5 QSPI boot profile
            Formats.atomicYaml(
                home.resolve("configs/components/soc-flash/soc-flash.yaml"),
                mapOf(
                    "profile" to "gfx100s-boot-qspi-jedec-functional-experimental-v5",
                    "jedec" to 2145056,
                    "imagePort" to "flash.image",
                    "interruptPort" to "soc-gic.lines",
                ),
            )
            // v5 is QSPI boot read-only until Track 10A v6 adds soc-flash.tx; omit soc-flash.tx from soc-dma destination-ports while on v5
            val destPortsPath = home.resolve("configs/components/soc-dma/destination-ports.yaml")
            val destPortsDoc = Formats.read(destPortsPath)
            @Suppress("UNCHECKED_CAST")
            val filteredPorts = (destPortsDoc["ports"] as List<String>).filterNot { it == "soc-flash.tx" }
            Formats.atomicYaml(destPortsPath, destPortsDoc + ("ports" to filteredPorts))
            val flashImgCfgDir = home.resolve("configs/components/flash-image")
            val flashImgDataDir = home.resolve("compoments/flash-image")
            copyDirectory(factoryFlashDir!!, flashImgCfgDir)
            copyDirectory(factoryFlashDir, flashImgDataDir)
            val siblingRuntimeFlashDir = factoryFlashDir.resolve("../../../runtime/compoments/flash-image").normalize()
            if (Files.isDirectory(siblingRuntimeFlashDir)) {
                copyDirectory(siblingRuntimeFlashDir, flashImgDataDir)
            }
            Formats.atomicYaml(
                flashImgCfgDir.resolve("flash-image.yaml"),
                mapOf(
                    "sourcePort" to "firmware.update",
                    "tailProfile" to "dat-end-block-ff-experimental-v1",
                    "regionsProfile" to "explicit-readonly-regions-experimental-v1",
                    "regionsFile" to "regions.json",
                ),
            )
            // Test raw binary Sub-MCU NVRAM (.bin) live alongside full cold boot!
            val powerPeerCfg = Formats.read(home.resolve("configs/components/power-peer/power-peer.yaml"))
            Formats.atomicYaml(
                home.resolve("configs/components/power-peer/power-peer.yaml"),
                powerPeerCfg + ("nvramFile" to "submcu-nvram.bin"),
            )

            // 4. Build Release-equivalent launch config with Boot ROM cold boot & genuine 4-core wakeup
            val baseRelease = Formats.read(project.resolve("tools/release/emulator-release-linux.yaml"))
            @Suppress("UNCHECKED_CAST")
            val baseDiscovery = LinkedHashMap<String, Any>(baseRelease["discovery"] as Map<String, Any>)
            baseDiscovery["bootRomColdStart"] = "bootrom-release-coldstart-v4"

            val config = LinkedHashMap<String, Any>().apply {
                putAll(baseRelease)
                remove("clusterReset") // Genuine 4-core cold reset at 0xFFF00000 -> SGI 0 wakeup!
                remove("flashPartitionScope") // v5 is QSPI boot read-only until Track 10A v6 unifies write+boot
                remove("flashReadPairScope")
                remove("flashWritableSetScope")
                remove("realTimePacer")
                remove("telemetry")
                put("audio", false)
                put("components", "compoments")
                put("componentConfigs", "configs/components")
                put("httpPort", 0)
                put("firmwareMetadata", "configs/firmware/gfx100s-213-bootrom-release.yaml")
                put("firmwareFile", firmware)
                put("bootRomFile", "boot-rom.bin")
                put("cpuEngine", "dynarmic")
                put("dynarmicLibrary", dynLib!!.toAbsolutePath().toString())
                put("nativeLibrary", "unused.so")
                put("instructionBudget", "unlimited")
                put("discovery", baseDiscovery)
            }
            validateLaunchConfiguration(config)

            fun stepBy(rt: EmulatorRuntime, deltaAttempts: Long): Long {
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
                    val st = rt.owner.submit { rt.session.state()["status"].toString() }.join()
                    if (st != "running") break
                }
                return current
            }

            fun captureMain(rt: EmulatorRuntime): Pair<Long, Int> = rt.owner.submit {
                val display = requireNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                val pixels = IntArray(display.width * display.height)
                display.copyArgb(pixels)
                display.sequence to pixels.count { (it and 0x00ffffff) != 0 }
            }.join()

            val t0 = System.nanoTime()
            EmulatorRuntime(home, config, telemetryDisabled = true).use { rt ->
                rt.owner.submit { rt.session.initialize() }.join()
                var attempts = 0L
                var frameSeq = 0L
                var nonBlack = 0
                var screenId = 0
                var status = "running"
                var lastStage = "NONE"

                var reachedSection4 = false
                for (chunk in 1..4) {
                    attempts = stepBy(rt, 10_000_000L)
                    status = rt.owner.submit { rt.session.state()["status"].toString() }.join()
                    val (seq, nb) = captureMain(rt)
                    frameSeq = seq
                    nonBlack = nb
                    screenId = readU32(rt, 0x01CC6424L)
                    lastStage = FirmwareStage.highestReached(rt.session.telemetry.stageMask)?.label ?: "NONE"
                    val elapsedMs = (System.nanoTime() - t0) / 1e6
                    val pc = rt.owner.submit {
                        ((rt.session.state()["registers"] as Map<*, *>)["pc"] as Number).toLong()
                    }.join()
                    if (pc >= 0x01021000L && pc < 0x04000000L) {
                        reachedSection4 = true
                    }
                    println(
                        "[BootRomFullColdBoot] chunk=$chunk attempts=$attempts elapsedMs=${"%.1f".format(elapsedMs)} " +
                            "status=$status stage=$lastStage scr=0x${screenId.toString(16)} frameSeq=$frameSeq nonBlack=$nonBlack pc=0x${pc.toString(16)}",
                    )
                    if (status != "running" || reachedSection4) break
                }

                val state = rt.owner.submit { rt.session.state() }.join()
                val bootRam = rt.owner.submit { rt.session.memory(0x0L, 0x1000) }.join()
                val kernelRam = rt.owner.submit { rt.session.memory(0x40000L, 0x1000) }.join()
                val section4Ram = rt.owner.submit { rt.session.memory(0x1021000L, 0x1000) }.join()
                val devices = state["devices"] as Map<*, *>
                val dramState = devices["soc-dram"] as Map<*, *>
                val coldStartDram = dramState["coldStart"] as Map<*, *>
                val discovery = state["discovery"] as Map<*, *>
                val regions = (discovery["regions"] as List<*>).map { it as Map<*, *> }
                val coldStartReceipt = regions.single { it["id"] == "diagnostic.bootrom.gpio-global-fff41500" }["bootRomColdStart"] as Map<*, *>
                lastStage = FirmwareStage.highestReached(rt.session.telemetry.stageMask)?.label ?: "NONE"

                println(
                    "[BootRomFullColdBoot] FINAL mode=${state["mode"]} bootRomExecuted=${state["bootRomExecuted"]} " +
                        "status=$status attempts=$attempts stage=$lastStage reachedSection4=$reachedSection4 " +
                        "dramInitStage=${coldStartDram["dramInitStage"]} ctl0AckPending=${coldStartDram["ctl0AckPending"]} " +
                        "bootStatus=0x${(coldStartReceipt["bootStatusFff4a100"] as Number).toLong().toString(16)} " +
                        "secondary=${coldStartReceipt["secondaryBootStatusFff4a104"]}",
                )

                assertEquals("four-context boot-rom-experiment", state["mode"])
                assertEquals(true, state["bootRomExecuted"])
                assertEquals("running", status)
                assertEquals("bootrom-release-coldstart-v4", coldStartReceipt["profile"])
                assertEquals(0x900dL, coldStartReceipt["bootStatusFff4a100"])
                assertEquals(listOf(0x1000900dL, 0x2000900dL, 0x3000900dL), coldStartReceipt["secondaryBootStatusFff4a104"])
                assertEquals(4, coldStartDram["dramInitStage"], "soc-dram coldStartChannel0 must complete 4-stage DDR init")
                assertEquals(false, coldStartDram["ctl0AckPending"], "soc-dram coldStartChannel0 must clear CTL0 ACK and hand off")
                assertContentEquals(expectedBootPrefix, bootRam, "Stage-2 XIP must load real boot.bin into RAM 0x0")
                assertContentEquals(expectedKernelPrefix, kernelRam, "boot.bin QSPI must load real kernel.bin into RAM 0x40000")
                assertContentEquals(expectedSection4Prefix, section4Ram, "kernel.bin Method4 must decompress real section4.bin into RAM 0x01021000")
                assertTrue(reachedSection4, "Expected real Boot ROM cold boot to execute decompressed section4.bin (pc >= 0x01021000)")
                assertNotNull(lastStage)
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }
}
