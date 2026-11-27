package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.ComponentEvent
import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.Exposure
import com.github.nyabanban.fuji.emulator.api.LensControlPort
import com.github.nyabanban.fuji.emulator.api.LensParameters
import com.github.nyabanban.fuji.emulator.api.LensPort
import com.github.nyabanban.fuji.emulator.api.ProgrammableImagePort
import com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort
import com.github.nyabanban.fuji.emulator.api.SensorPort
import com.github.nyabanban.fuji.emulator.api.TransferCapture
import com.github.nyabanban.fuji.emulator.api.TransferCapturePort
import com.github.nyabanban.fuji.emulator.api.display.DotLcdTransportCodec
import com.github.nyabanban.fuji.emulator.desktop.e2e.QrTestCodec
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedFlashPersistence
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReleaseLaunchConfigTest {
    @Test
    fun releaseLaunchConfigsPassStrictEnvelopeValidationWithUnlimitedRelaxedMode() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        for (name in listOf("emulator-release-linux.yaml", "emulator-release-windows.yaml")) {
            val config = Formats.read(repo.resolve("tools/release").resolve(name))
            validateLaunchConfiguration(config)
            assertEquals(RelaxedPeripheralFallback.MODE, config["executionMode"], "$name executionMode")
            assertEquals(InstructionBudget.UNLIMITED_TOKEN, config["instructionBudget"], "$name instructionBudget")
            assertEquals(
                InstructionBudget.UNLIMITED,
                InstructionBudget.parse(config["instructionBudget"], relaxed = true),
                "$name parsed unlimited budget",
            )
            assertEquals(RelaxedBootstrapRam.PROFILE, config[RelaxedBootstrapRam.CONFIG_KEY], "$name relaxedBootstrapRam")
            assertEquals("runtime/compoments", config["components"], "$name components path")
            assertEquals("configs/components", config["componentConfigs"], "$name componentConfigs path")
            assertEquals("configs/firmware/gfx100s-213-release.yaml", config["firmwareMetadata"], "$name firmwareMetadata path")
            assertEquals("flash/nor-flash.bin", config["norFlashFile"], "$name norFlashFile path")
            assertEquals("runtime/templates/factory-flash/nor-flash-2.13.bin", config["factoryFlashFile"], "$name factoryFlashFile path")
            assertEquals("bootrom/boot-rom.bin", config["bootRomFile"], "$name bootRomFile path")
            assertEquals(null, config["firmwareFile"], "$name release configs boot directly from norFlashFile without GXUP0004.DAT")
            assertEquals("dynarmic", config["cpuEngine"], "$name cpuEngine must enforce mandatory Dynarmic JIT")
            assertEquals("configs/catalog.compiled.json", config["addressDictionary"], "$name addressDictionary path")
            assertEquals(true, config["audio"], "$name audio enabled")
            @Suppress("UNCHECKED_CAST")
            val telemetryYaml = config["telemetry"] as Map<String, Any?>
            assertEquals(false, telemetryYaml["enabled"], "$name telemetry.enabled must default to false (opt-in)")
            assertEquals("https://fuji-ingest.kiwi.cat", telemetryYaml["endpoint"], "$name telemetry.endpoint")
            assertEquals("fuji-ingest-dev-token", telemetryYaml["ingestToken"], "$name telemetry.ingestToken")
            assertEquals(true, telemetryYaml["uploadLogOnExit"], "$name telemetry.uploadLogOnExit")
            val defaultTelemetry = com.github.nyabanban.fuji.emulator.runtime.TelemetryHubClient.resolveConfig(
                executionMode = RelaxedPeripheralFallback.MODE,
                config = config,
                env = emptyMap(),
            )
            assertEquals(null, defaultTelemetry, "$name must disable telemetry by default unless explicitly opted in")
            val optInTelemetry = com.github.nyabanban.fuji.emulator.runtime.TelemetryHubClient.resolveConfig(
                executionMode = RelaxedPeripheralFallback.MODE,
                config = config,
                cliEnableUpload = true,
                env = emptyMap(),
            )
            assertTrue(optInTelemetry != null, "$name must resolve opt-in TelemetryConfig when explicitly enabled")
            assertEquals("https://fuji-ingest.kiwi.cat", optInTelemetry.endpoint, "$name opt-in endpoint")
            assertEquals("fuji-ingest-dev-token", optInTelemetry.ingestToken, "$name opt-in token")
            @Suppress("UNCHECKED_CAST")
            val discovery = config["discovery"] as Map<String, Any?>
            assertEquals("fff7f000-irq133-completion-v1", discovery["imageScalerF000"], "$name imageScalerF000")
            assertEquals("fff7e000-irq132-completion-v1", discovery["imageScalerE000"], "$name imageScalerE000")
            assertEquals("fa000000-ready-idle-v1", discovery["playDspFa00"], "$name playDspFa00")
            assertEquals("isp-tg-auto-sync-v1", discovery["ispAutoSync"], "$name ispAutoSync")
            assertEquals("face-detect-irq123-v1", discovery["imageFace7c000"], "$name imageFace7c000")
            @Suppress("UNCHECKED_CAST")
            val devices = config["enabledDevices"] as List<String>
            assertEquals(26, devices.size, "$name enabledDevices count")
            assertTrue("soc-isp" in devices && "soc-isp-cmd" in devices && "soc-isp-setup" in devices && "soc-jpeg" in devices)
            assertTrue("soc-hdmi" in devices && "soc-panel" in devices && "soc-uart" in devices && "soc-sdio" in devices)
        }
    }

    @Test
    fun releaseComponentConfigsLoadAllV010UnifiedCapabilitiesWithoutConflict(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val releaseConfig = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))
        val catalog = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.bundled()

        // 1. Unified RelaxedBootstrapRam plan must validate cleanly against BOTH v24 and linux-contiguous-256m
        fun toMemoryRegions(rows: List<Map<String, Any?>>): List<com.github.nyabanban.fuji.emulator.api.MemoryRegion> =
            rows.map { r ->
                val base = (r.getValue("base") as Number).toLong()
                val end = (r.getValue("endExclusive") as Number).toLong()
                com.github.nyabanban.fuji.emulator.api.MemoryRegion(
                    id = r.getValue("id").toString(),
                    address = base,
                    size = end - base,
                    permissions = (r.getValue("permissions") as Number).toInt(),
                )
            }
        val v24Raw = catalog.memoryProfiles.getValue("flashload-request-buffer-experimental-v24")
        val v24Regions = toMemoryRegions(v24Raw)
        val v24Plan = RelaxedBootstrapRam.buildPlan(catalog, "flashload-request-buffer-experimental-v24", v24Regions)
        assertEquals(RelaxedBootstrapRam.REGIONS.size, v24Plan.additionalRegions.size, "Expected all non-overlapping unified DDR regions")
        assertTrue(v24Plan.additionalRegions.sumOf { it.size } <= 0xF000_0000L)
        com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.validate(
            v24Regions,
            v24Plan.additionalRegions,
            com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
        )

        val linuxRegions = toMemoryRegions(catalog.memoryProfiles.getValue("linux-contiguous-256m-experimental-v1"))
        val linuxPlan = RelaxedBootstrapRam.buildPlan(catalog, "linux-contiguous-256m-experimental-v1", linuxRegions)
        assertEquals(RelaxedBootstrapRam.REGIONS.size - 4, linuxPlan.additionalRegions.size, "4 regions inside Linux 256M [0x10000000..0x20000000) must be filtered out")
        com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.validate(
            linuxRegions,
            linuxPlan.additionalRegions,
            com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
        )

        // 2. Stage split outermost configs/components + runtime/compoments layout and verify ComponentHost loads cleanly
        val fixtureHome = IntegrationTest().fixture()
        val home = tempRoot.resolve("release-root")
        try {
            val runtimeCompDir = home.resolve("runtime/compoments")
            val configCompDir = home.resolve("configs/components")
            Files.createDirectories(runtimeCompDir)
            Files.createDirectories(configCompDir)
            Files.list(fixtureHome.resolve("compoments")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
                    Files.copy(jar, runtimeCompDir.resolve(jar.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val releaseOverrides = repo.resolve("tools/release/component-configs")
            Files.walk(releaseOverrides).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { src ->
                    val rel = releaseOverrides.relativize(src)
                    val isBinaryAsset = rel.toString().endsWith(".jpg") || rel.toString().endsWith(".png") ||
                        rel.toString().endsWith(".bin") || rel.toString().endsWith(".img")
                    val dest = (if (isBinaryAsset) runtimeCompDir else configCompDir).resolve(rel)
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val discoveryDir = home.resolve("configs/discovery")
            Files.createDirectories(discoveryDir)
            Files.copy(
                repo.resolve("tools/release/discovery/slot0-phy.seed.bin"),
                discoveryDir.resolve("slot0-phy.seed.bin"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            Files.copy(
                repo.resolve("tools/release/discovery/slot1-phy.seed.bin"),
                discoveryDir.resolve("slot1-phy.seed.bin"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )

            // Verify RelaxedFlashPersistence operates cleanly on split componentConfigs + components layout
            RelaxedFlashPersistence.prepare(home, releaseConfig)

            com.github.nyabanban.fuji.emulator.runtime.OwnerLoop().use { owner ->
                val host = com.github.nyabanban.fuji.emulator.runtime.ComponentHost(
                    owner,
                    runtimeCompDir,
                    catalog,
                    configRoot = configCompDir,
                )
                try {
                    owner.submit {
                        host.load()
                        val states = host.components().associate { it.descriptor.id to it.component.state() }

                        // Touchscreen + EVF eye sensor enabled out of the box
                        val i2cBus = states.getValue("i2c-bus")
                        assertEquals(true, i2cBus["touchEnabled"], "Release i2c-bus must enable rear touchscreen by default")
                        assertEquals(false, i2cBus["eyeSensorNear"], "Release i2c-bus must start with eyeSensorNear=false")
                        assertTrue(
                            host.ports.find("i2c-bus.bus1", com.github.nyabanban.fuji.emulator.api.I2cBusPort::class.java) != null,
                            "I2C1 bus (eye sensor @ 0x39) must be mounted",
                        )
                        assertTrue(
                            host.ports.find("i2c-bus.bus2", com.github.nyabanban.fuji.emulator.api.I2cBusPort::class.java) != null,
                            "I2C2 bus (touchscreen @ 0x55) must be mounted",
                        )
                        val socI2cIrqs = host.components().single { it.descriptor.id == "soc-i2c" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertTrue(66 in socI2cIrqs && 67 in socI2cIrqs, "soc-i2c must route real DAT IRQs 66 (I2C1 eye sensor) and 67 (I2C2 touch): $socI2cIrqs")

                        // Standalone decoupled commercial I2C ICs (tmd2772, mma8452q, max17048, wm8962, rear-touch-ic)
                        val tmd = states.getValue("tmd2772")
                        assertEquals(true, tmd["enabled"])
                        assertEquals(true, tmd["busAttached"])
                        assertEquals(true, tmd["gpioIntAttached"])
                        val mma = states.getValue("mma8452q")
                        assertEquals(true, mma["enabled"])
                        assertEquals(true, mma["bodyAttached"])
                        assertEquals(true, mma["lcdAttached"])
                        val maxFuel = states.getValue("max17048")
                        assertEquals(true, maxFuel["enabled"])
                        assertEquals(true, maxFuel["busAttached"])
                        assertEquals(true, maxFuel["batteryObserved"])
                        val wm = states.getValue("wm8962")
                        assertEquals(true, wm["enabled"])
                        assertEquals(true, wm["busAttached"])
                        val rearTouch = states.getValue("rear-touch-ic")
                        assertEquals(true, rearTouch["enabled"])
                        assertEquals(true, rearTouch["busAttached"])
                        assertEquals(true, rearTouch["touchEnabled"])
                        assertTrue(
                            host.ports.find(
                                com.github.nyabanban.fuji.emulator.api.RearTouchPort.PORT_NAME,
                                com.github.nyabanban.fuji.emulator.api.RearTouchPort::class.java,
                            )?.touchEnabled == true,
                            "Standalone rear-touch-ic port must be live on I2C2 @ 0x55",
                        )
                        val rearTouchIrqs = host.components().single { it.descriptor.id == "rear-touch-ic" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertTrue(153 in rearTouchIrqs, "Standalone rear-touch-ic must route GIC IRQ 153: $rearTouchIrqs")
                        val i2cBusIrqs = host.components().single { it.descriptor.id == "i2c-bus" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertTrue(153 !in i2cBusIrqs, "Pure SoC i2c-bus must defer IRQ 153 when rear-touch-ic is enabled: $i2cBusIrqs")

                        // WM8962 + Dual I2S Audio DMA + HostAudioPlayback port binding
                        assertTrue(
                            host.ports.find("wm8962.codec", com.github.nyabanban.fuji.emulator.api.AudioCodecControlPort::class.java) != null,
                            "Standalone WM8962 codec port must be live on I2C1 0x1A + SPI4",
                        )
                        assertTrue(
                            host.ports.find("soc-isp-setup.i2s-audio", com.github.nyabanban.fuji.emulator.api.I2sAudioPort::class.java) != null,
                            "Dual I2S PCM audio port must be live",
                        )
                        assertTrue(
                            host.ports.find("soc-isp-setup.i2s0-dma-tx", com.github.nyabanban.fuji.emulator.api.DmaDestinationPeer::class.java) != null &&
                                host.ports.find("soc-isp-setup.i2s1-dma-tx", com.github.nyabanban.fuji.emulator.api.DmaDestinationPeer::class.java) != null &&
                                host.ports.find("soc-isp-setup.i2s0-dma-rx", com.github.nyabanban.fuji.emulator.api.DmaSourcePeer::class.java) != null &&
                                host.ports.find("soc-isp-setup.i2s1-dma-rx", com.github.nyabanban.fuji.emulator.api.DmaSourcePeer::class.java) != null,
                            "I2S0/I2S1 DMA TX destination & RX source peers must be live",
                        )

                        // STILL/MOVIE lever + DRIVE/AF-ON/Focus Mode lever + GPIO v5 + dual SD card-detect + Shutter ExtIRQ
                        assertEquals(false, states.getValue("buttons")["movieMode"])
                        assertEquals(false, states.getValue("buttons")["drive"])
                        assertEquals(false, states.getValue("buttons")["afOn"])
                        assertEquals(false, states.getValue("buttons")["focusMode"])
                        assertEquals(false, states.getValue("buttons")["frontDialPress"])
                        assertEquals(false, states.getValue("buttons")["fn4"])
                        assertEquals(false, states.getValue("buttons")["focusStickPress"])
                        val gpio = states.getValue("soc-gpio")
                        assertEquals("gfx100s-2.13-gpio-buttons-v6", gpio["profile"])
                        assertEquals(0L, (gpio["frontDialDetents"] as Number).toLong())
                        assertEquals("gfx100s-2.13-shutter-extirq-v1", gpio["shutterIrqProfile"])
                        assertEquals("disabled", gpio["shutterMechanismProfile"])
                        val shutter = states.getValue("shutter")
                        assertEquals(true, shutter["enabled"])
                        assertEquals("IDLE", shutter["mode"])
                        assertEquals(0L, (shutter["curtainPulses"] as Number).toLong())
                        assertEquals(4000L, (shutter["curtainTravelUs"] as Number).toLong())
                        assertEquals(125000L, (shutter["rollingReadoutUs"] as Number).toLong())
                        val display = states.getValue("display")
                        assertEquals(1024L, (display["width"] as Number).toLong())
                        assertEquals(768L, (display["height"] as Number).toLong())
                        assertEquals(1280L, (display["evfWidth"] as Number).toLong())
                        assertEquals(960L, (display["evfHeight"] as Number).toLong())
                        assertEquals(false, states.getValue("lens")["focusMotorInstant"])
                        assertTrue(
                            host.ports.find(
                                com.github.nyabanban.fuji.emulator.api.ShutterPort.PORT_NAME,
                                com.github.nyabanban.fuji.emulator.api.ShutterPort::class.java,
                            )?.enabled == true,
                            "Standalone shutter.mechanism port must be live",
                        )
                        val optFilter = states.getValue("optical-filter")
                        assertEquals(true, optFilter["enabled"])
                        assertEquals("OFF", optFilter["ndPreset"])
                        assertEquals(0.0, (optFilter["ndStops"] as Number).toDouble(), 1e-6)
                        assertEquals("OFF", optFilter["bokehMask"])
                        assertEquals(false, optFilter["hasSpatialEffects"])
                        assertEquals(1.0, (optFilter["totalTransmittance"] as Number).toDouble(), 1e-6)
                        assertTrue(
                            host.ports.find(
                                com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort.PORT_NAME,
                                com.github.nyabanban.fuji.emulator.api.OpticalFilterStackPort::class.java,
                            )?.enabled == true,
                            "Standalone optical-filter.stack port must be live",
                        )
                        val shutterIrqs = host.components().single { it.descriptor.id == "shutter" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertEquals(setOf(154, 156, 161, 170, 172, 177), shutterIrqs, "Standalone shutter must own EXTINT #3/#5/#10 IRQs")
                        val gpioIrqs = host.components().single { it.descriptor.id == "soc-gpio" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertEquals(
                            setOf(162, 167, 168), gpioIrqs,
                            "GPIO owns lens detect and the two shutter button contacts",
                        )
                        assertEquals(16384L, (gpio["input0"] as Number).toLong())
                        assertEquals(8196L, (gpio["input2"] as Number).toLong())
                        assertEquals(18L, (gpio["input6"] as Number).toLong())
                        assertEquals(8256L, (gpio["input7"] as Number).toLong())
                        assertEquals(0L, (gpio["input8"] as Number).toLong(), "Headphone jack detect Bank 8 Pin 4 (DAT 0x01812DC4) is active-HIGH: 0 = unplugged at boot")
                        assertEquals(0L, (gpio["input10"] as Number).toLong(), "Both SD slot CARD_DET pins (bit 4 & bit 8) must be active-LOW inserted")

                        // Dual-card directory mode SD reader + auto-initialized FF-DSC01 card directories
                        val sdReader = states.getValue("sdcard-reader")
                        assertEquals(true, sdReader["slot1Inserted"])
                        assertEquals("directory", sdReader["slot1Backend"])
                        assertEquals("sd-card-slot-1", sdReader["slot1Image"])
                        assertEquals(false, sdReader["slot1ReadOnly"])
                        assertEquals(true, sdReader["slot2Inserted"])
                        assertEquals("directory", sdReader["slot2Backend"])
                        assertEquals("sd-card-slot-2", sdReader["slot2Image"])
                        assertEquals(false, sdReader["slot2ReadOnly"])
                        assertEquals(true, sdReader["syncCardDetectGpio"])
                        for (slot in 1..2) {
                            val cardDir = runtimeCompDir.resolve("sdcard-reader/sd-card-slot-$slot")
                            assertTrue(Files.isDirectory(cardDir.resolve("DCIM/100_FUJI")), "Slot $slot DCIM/100_FUJI must be initialized")
                            assertTrue(Files.isRegularFile(cardDir.resolve("FFDB/FFXFER.DAT")), "Slot $slot FFDB/FFXFER.DAT must be initialized")
                            val xferBytes = Files.readAllBytes(cardDir.resolve("FFDB/FFXFER.DAT"))
                            assertEquals(512, xferBytes.size)
                            assertEquals("00000002", String(xferBytes, 0, 8, Charsets.US_ASCII))
                        }

                        // ISP v3 (v2 + Mali-V61 + MOVDC/LSRAW/RETCH/UniDmac/companion banks) + autoFrameIrqs
                        val isp = states.getValue("soc-isp")
                        assertEquals("isp-banks-movie-capture-v3", isp["profile"])
                        assertEquals(true, isp["autoFrameIrqs"])
                        @Suppress("UNCHECKED_CAST")
                        val ispPorts = isp["ports"] as List<String>
                        assertTrue("soc-isp.result-surface-v3" in ispPorts)
                        assertTrue("soc-isp.result-surface-1-v3" in ispPorts)
                        assertTrue("soc-isp.mali-v61" in ispPorts)
                        assertTrue("soc-isp.pdaf" in ispPorts)
                        assertTrue("soc-isp.movdc" in ispPorts)
                        assertTrue("soc-isp.lsraw" in ispPorts)
                        assertTrue("soc-isp.retch" in ispPorts)
                        val ispIrqs = host.components().single { it.descriptor.id == "soc-isp" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertEquals(setOf(100, 101, 102, 105, 106, 107, 108, 109, 110, 122, 126, 127, 129, 134, 137, 138, 139, 140, 142, 296), ispIrqs)

                        assertEquals("gf-lenscom-frame32-experimental-v1", states.getValue("mount")["protocolProfile"])
                        assertEquals("spi0-four-byte-fifo-v1", states.getValue("soc-dma")["spi0DmaProfile"])
                        assertEquals("panel-init-repeatable-a8000-experimental-v2", states.getValue("soc-panel")["profile"])
                        assertEquals(true, states.getValue("soc-dram")["repeatableCommandModes"])
                        assertEquals(true, states.getValue("soc-hdmi")["repeatLastLinkRead"])
                        assertEquals(true, states.getValue("soc-serial-engine")["repeatLastPlan"])
                        assertEquals(true, states.getValue("soc-serial-engine")["completionIrqs"])
                        assertEquals("pl011-multi-uart-experimental-v1", states.getValue("soc-uart")["profile"])
                        assertEquals(true, states.getValue("soc-uart")["uart1IrqEnabled"])
                        assertEquals(true, states.getValue("soc-uart")["uart1PeerAttached"])
                        assertEquals("soc-sdio-sdhci3-experimental-v1", states.getValue("soc-sdio")["profile"])
                        assertEquals(true, states.getValue("soc-sdio")["cardAttached"])
                        val socSdioIrqs = host.components().single { it.descriptor.id == "soc-sdio" }
                            .component.interruptRoutes().flatMap { it.lines.toList() }.toSet()
                        assertEquals(setOf(224, 225), socSdioIrqs)
                        val bcm = states.getValue("bcm43438")
                        assertEquals(true, bcm["enabled"])
                        assertEquals("FujiEmu Host", bcm["deviceName"])
                        assertEquals(true, bcm["uartAttached"])
                        assertEquals(true, bcm["sdioAttached"])
                        assertEquals("soc-jpeg-codec-experimental-v2", states.getValue("soc-jpeg")["profile"])
                        assertEquals(64L, (states.getValue("soc-jpeg")["completionDelayTicks"] as Number).toLong())
                        assertEquals("gfx100s-sd-host-uhs-phy-experimental-v9", states.getValue("soc-sd-host")["profile"])
                        assertEquals("gfx100s-unified-boot-program-v6", states.getValue("soc-flash")["profile"])
                        assertEquals(2145056L, (states.getValue("soc-flash")["jedec"] as Number).toLong())
                        assertEquals("retained-banks-usb-dwc3-experimental-v2", states.getValue("soc-system-control")["profile"])
                        assertEquals(false, states.getValue("soc-system-control")["usbVbusPresent"])
                        val vglib = states.getValue("soc-vglib-init")
                        assertEquals(true, vglib["continuousScanout"])
                        assertEquals("lcdc1-pic-yuv-osd0-nearest-v1", vglib["evfScanout"])

                        // Config files live under configs/components/<id>/ while binaries stay in runtime/compoments/
                        assertTrue(
                            host.components().all { it.configFile.startsWith(configCompDir.toRealPath()) },
                            "All component YAML config files must resolve under configs/components",
                        )

                        // Discovery runtime with release discovery config must bind against active components with ZERO conflicts
                        val runtime = com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime(
                            catalog,
                            RelaxedPeripheralFallback.MODE,
                            home,
                            releaseConfig,
                        )
                        val releaseProfiles = mapOf(
                            "soc-sd-host" to "gfx100s-sd-host-uhs-phy-experimental-v9",
                            "soc-vglib-init" to "lcdc-functional-experimental-v1",
                            "soc-isp" to "isp-banks-movie-capture-v3",
                            "soc-jpeg" to "soc-jpeg-codec-experimental-v2",
                            "soc-panel" to "panel-init-repeatable-a8000-experimental-v2",
                            "soc-uart" to "pl011-multi-uart-experimental-v1",
                            "soc-system-control" to "retained-banks-usb-dwc3-experimental-v2",
                        )
                        val releaseMeta = catalog.firmwareMetadata(releaseProfiles)
                        @Suppress("UNCHECKED_CAST")
                        val enabledDeviceIds = (releaseConfig.getValue("enabledDevices") as List<*>).map { it.toString() }.toSet()
                        val additionalPortIds = host.components().flatMap { it.component.additionalMmioPorts() }.toSet()
                        @Suppress("UNCHECKED_CAST")
                        val portBindings = (releaseMeta["devices"] as List<Map<String, Any?>>)
                            .filter { d ->
                                d.getValue("id").toString() in enabledDeviceIds &&
                                    (d["optional"] != true || d["port"].toString() in additionalPortIds)
                            }
                            .map { d ->
                                com.github.nyabanban.fuji.emulator.catalog.PortBinding(
                                    componentId = d.getValue("id").toString(),
                                    portId = d.getValue("port").toString(),
                                    base = java.lang.Long.decode(d.getValue("address").toString()),
                                    size = java.lang.Long.decode(d.getValue("size").toString()),
                                )
                            }
                        runtime.bindPorts(
                            portBindings,
                            "flashload-request-buffer-experimental-v24",
                            v24Raw,
                            componentProfiles = releaseProfiles,
                            additionalRamProfile = com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions.EXEC_CAPABLE_PROFILE,
                        )
                    }.join()
                } finally {
                    owner.submit { host.close() }.join()
                }
            }
        } finally {
            fixtureHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun instructionBudgetEnforcesStrictCeilingOutsideRelaxedAndSupportsUnlimitedInRelaxed() {
        // discovery / strict modes keep strict 1..300_000_000L ceiling
        assertEquals(1L, InstructionBudget.parse(1, relaxed = false))
        assertEquals(InstructionBudget.MAX, InstructionBudget.parse(InstructionBudget.MAX, relaxed = false))
        assertFailsWith<IllegalArgumentException> { InstructionBudget.parse(null, relaxed = false) }
        assertFailsWith<IllegalArgumentException> { InstructionBudget.parse("unlimited", relaxed = false) }
        assertFailsWith<IllegalArgumentException> { InstructionBudget.parse(0, relaxed = false) }
        assertFailsWith<IllegalArgumentException> { InstructionBudget.parse(InstructionBudget.MAX + 1, relaxed = false) }

        // relaxed mode accepts omitted, "unlimited", 0, and explicit positive finite budgets
        assertEquals(InstructionBudget.UNLIMITED, InstructionBudget.parse(null, relaxed = true))
        assertEquals(InstructionBudget.UNLIMITED, InstructionBudget.parse("unlimited", relaxed = true))
        assertEquals(InstructionBudget.UNLIMITED, InstructionBudget.parse(0, relaxed = true))
        assertEquals(2_000_000L, InstructionBudget.parse(2_000_000, relaxed = true))
        assertEquals(InstructionBudget.MAX + 1, InstructionBudget.parse(InstructionBudget.MAX + 1, relaxed = true))
        assertFailsWith<IllegalArgumentException> { InstructionBudget.parse(-1, relaxed = true) }

        // relaxedBootstrapRam is refused outside relaxed mode
        assertFailsWith<IllegalArgumentException> {
            RelaxedBootstrapRam.selected(mapOf(RelaxedBootstrapRam.CONFIG_KEY to RelaxedBootstrapRam.PROFILE), relaxed = false)
        }
        assertTrue(
            RelaxedBootstrapRam.selected(mapOf(RelaxedBootstrapRam.CONFIG_KEY to RelaxedBootstrapRam.PROFILE), relaxed = true),
        )
    }

    @Test
    fun relaxedModeAutoResumesFactoryFlashCowAcrossRestartsAndPersistsWrites(@TempDir tempRoot: Path) {
        val fixtures = IntegrationTest()
        val home = fixtures.fixture()
        try {
            val flashDir = home.resolve("compoments/flash-image")
            Files.createDirectories(flashDir)

            val seedA = ByteArray(0x10000) { 0xff.toByte() }
            val seedB = ByteArray(0x10000) { 0xff.toByte() }
            val shaA = sha256(seedA)
            val shaB = sha256(seedB)
            Files.write(flashDir.resolve("factory-domain490-497.img"), seedA)
            Files.write(flashDir.resolve("factory-domain504-505.img"), seedB)

            val regionsJson = linkedMapOf(
                "schema" to 1,
                "profile" to "explicit-readonly-regions-experimental-v1",
                "regions" to listOf(
                    linkedMapOf(
                        "id" to "dat-domain490-497-ff",
                        "offset" to 0x03d40000L,
                        "length" to seedA.size.toLong(),
                        "file" to "factory-domain490-497.img",
                        "sha256" to shaA,
                    ),
                    linkedMapOf(
                        "id" to "dat-range504-ff",
                        "offset" to 0x03f00000L,
                        "length" to seedB.size.toLong(),
                        "file" to "factory-domain504-505.img",
                        "sha256" to shaB,
                    ),
                ),
            )
            val mutableJson = linkedMapOf(
                "schema" to 1,
                "profile" to "explicit-disjoint-copy-on-write-program-experimental-v3",
                "primaryRegionId" to "dat-domain490-497-ff",
                "regions" to listOf(
                    linkedMapOf(
                        "schema" to 1,
                        "profile" to "explicit-copy-on-write-program-experimental-v2",
                        "regionId" to "dat-domain490-497-ff",
                        "file" to "cow-domain490-497.img",
                        "mode" to "create",
                        "sha256" to shaA,
                        "writableOffset" to 0x03d40000L,
                        "writableLength" to 0x8000L,
                        "programLaw" to "strict-one-to-zero-no-page-wrap-experimental-v1",
                        "programPageBytes" to 256,
                    ),
                    linkedMapOf(
                        "schema" to 1,
                        "profile" to "explicit-copy-on-write-program-experimental-v2",
                        "regionId" to "dat-range504-ff",
                        "file" to "cow-domain504-505.img",
                        "mode" to "create",
                        "sha256" to shaB,
                        "writableOffset" to 0x03f00000L,
                        "writableLength" to 0x8000L,
                        "programLaw" to "strict-one-to-zero-no-page-wrap-experimental-v1",
                        "programPageBytes" to 256,
                    ),
                ),
            )
            Formats.writeJson(flashDir.resolve("regions.json"), regionsJson)
            Formats.writeJson(flashDir.resolve("mutable.json"), mutableJson)
            Formats.atomicYaml(
                flashDir.resolve("flash-image.yaml"),
                mapOf(
                    "tailProfile" to "dat-end-block-ff-experimental-v1",
                    "regionsProfile" to "explicit-readonly-regions-experimental-v1",
                    "regionsFile" to "regions.json",
                    "mutationProfile" to "explicit-disjoint-copy-on-write-program-experimental-v3",
                    "mutationFile" to "mutable.json",
                ),
            )

            // Also configure auto-sparse blank SD creation on first launch using real soc-sd-host v9 profile
            val repo = Path.of(System.getProperty("componentProjectRoot"))
            val sdHostDir = home.resolve("compoments/soc-sd-host")
            Files.createDirectories(sdHostDir)
            Files.copy(
                repo.resolve("tools/release/component-configs/soc-sd-host/sd-card-v9.json"),
                sdHostDir.resolve("sd-card-v9.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            Formats.atomicYaml(
                sdHostDir.resolve("soc-sd-host.yaml"),
                mapOf("profile" to "gfx100s-sd-host-uhs-phy-experimental-v9", "inputFile" to "sd-card-v9.json"),
            )
            Formats.atomicYaml(
                home.resolve("compoments/sdcard-reader/sdcard-reader.yaml"),
                mapOf(
                    "slot1Inserted" to true,
                    "slot1Image" to "auto-blank.img",
                    "slot1ReadOnly" to false,
                    "slot2Inserted" to true,
                    "slot2Image" to "auto-blank-2.img",
                    "slot2ReadOnly" to false,
                ),
            )

            val config = mapOf<String, Any>(
                "schema" to 1,
                "executionMode" to RelaxedPeripheralFallback.MODE,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to "unlimited",
                "enabledDevices" to listOf("soc-flash"),
            )
            validateLaunchConfiguration(config)

            // Discovery mode must NEVER mutate mutable.json
            assertEquals(0, RelaxedFlashPersistence.syncMutableManifest(home, config + ("executionMode" to "discovery")))

            // Launch #1: creates cow-*.img and auto-sparse SD image
            EmulatorRuntime(home, config).use { rt ->
                rt.owner.submit {
                    assertTrue(Files.isRegularFile(flashDir.resolve("cow-domain490-497.img")))
                    assertTrue(Files.isRegularFile(flashDir.resolve("cow-domain504-505.img")))
                    val sdImg = home.resolve("compoments/sdcard-reader/auto-blank.img")
                    assertTrue(Files.isRegularFile(sdImg))
                    assertEquals(4294967296L, Files.size(sdImg))
                }.join()
            }

            // Launch #2: auto-flips mode="resume", performs real Flash program write (0xFF -> 0x42)
            EmulatorRuntime(home, config).use { rt ->
                rt.owner.submit {
                    val prog = rt.host.ports.find("flash.image.program", ProgrammableImagePort::class.java)!!
                    prog.prepareProgram(0x03d40000L, ByteBuffer.wrap(byteArrayOf(0x42, 0x24, 0x11, 0x00))).use { tx ->
                        tx.commit()
                    }
                }.join()
            }

            // Launch #3: refreshes SHA-256 in mutable.json, reloads cleanly, and preserves persisted Flash write!
            EmulatorRuntime(home, config).use { rt ->
                rt.owner.submit {
                    val cowBytes = Files.readAllBytes(flashDir.resolve("cow-domain490-497.img"))
                    assertEquals(0x42.toByte(), cowBytes[0])
                    assertEquals(0x24.toByte(), cowBytes[1])
                    assertEquals(0x11.toByte(), cowBytes[2])
                    assertEquals(0x00.toByte(), cowBytes[3])
                    val mutableAfter = Formats.readData(flashDir.resolve("mutable.json"))
                    @Suppress("UNCHECKED_CAST")
                    val regions = mutableAfter["regions"] as List<Map<String, Any?>>
                    assertEquals("resume", regions[0]["mode"])
                    assertEquals(sha256(cowBytes), regions[0]["sha256"])
                }.join()
            }
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun releaseBundledCmosQrConfigAndAssetAreValidAndLoopContinuously(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val releaseCmosDir = repo.resolve("tools/release/component-configs/cmos")
        val releaseLensDir = repo.resolve("tools/release/component-configs/lens")
        val yamlPath = releaseCmosDir.resolve("cmos.yaml")
        val lensYamlPath = releaseLensDir.resolve("lens.yaml")
        val qrPath = releaseCmosDir.resolve("test-qr.jpg")
        assertTrue(Files.isRegularFile(yamlPath), "Missing release cmos.yaml")
        assertTrue(Files.isRegularFile(lensYamlPath), "Missing release lens.yaml")
        assertTrue(Files.isRegularFile(qrPath), "Missing release test-qr.jpg")

        val cfg = Formats.read(yamlPath)
        assertEquals("optical-scene", cfg["sourceKind"])
        assertEquals("scene://qr-ball", cfg["sourceFile"])
        assertEquals(true, cfg["loop"])

        val lensCfg = Formats.read(lensYamlPath)
        assertEquals("gf45-100", lensCfg["template"])
        assertEquals(true, lensCfg["opticsEnabled"])

        // Bundled static fallback QR asset remains valid and scannable for backwards compatibility
        val decoded = QrTestCodec.decode(ImageIO.read(qrPath.toFile()))
        assertEquals("FUJIFILM GFX100S 2.13 CMOS TEST QR", decoded)

        // Load real cmos + lens JARs with release configs and verify 3D scene://qr-ball looping,
        // thin-lens DoF sharpness inversion (0.85m sphere <-> 5.5m QR wall), and scannable 3D wall QR
        val componentsDir = Files.createDirectories(tempRoot.resolve("compoments"))
        Files.copy(repo.resolve("components/cmos/build/libs/cmos-0.3.1.jar"), componentsDir.resolve("cmos-0.3.1.jar"))
        Files.copy(repo.resolve("components/lens/build/libs/lens-0.3.1.jar"), componentsDir.resolve("lens-0.3.1.jar"))
        val cmosDir = Files.createDirectories(componentsDir.resolve("cmos"))
        Files.copy(yamlPath, cmosDir.resolve("cmos.yaml"))
        Files.copy(qrPath, cmosDir.resolve("test-qr.jpg"))
        val lensDir = Files.createDirectories(componentsDir.resolve("lens"))
        Files.copy(lensYamlPath, lensDir.resolve("lens.yaml"))

        fun bayer16RggbToArgb(raw: ByteArray, w: Int, h: Int): IntArray {
            val out = IntArray(w * h)
            fun s(x: Int, y: Int): Int {
                val off = (y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)) * 2
                return (raw[off].toInt() and 0xFF) or ((raw[off + 1].toInt() and 0xFF) shl 8)
            }
            fun to8(v16: Int): Int {
                val c = (v16 / 65535.0).coerceIn(0.0, 1.0)
                val srgb = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055
                return Math.round(srgb * 255.0).toInt().coerceIn(0, 255)
            }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val yOdd = (y and 1) != 0
                    val xOdd = (x and 1) != 0
                    val r: Int
                    val g: Int
                    val b: Int
                    when {
                        !yOdd && !xOdd -> {
                            r = s(x, y)
                            g = (s(x - 1, y) + s(x + 1, y) + s(x, y - 1) + s(x, y + 1)) ushr 2
                            b = (s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)) ushr 2
                        }
                        !yOdd && xOdd -> {
                            r = (s(x - 1, y) + s(x + 1, y)) ushr 1
                            g = s(x, y)
                            b = (s(x, y - 1) + s(x, y + 1)) ushr 1
                        }
                        yOdd && !xOdd -> {
                            r = (s(x, y - 1) + s(x, y + 1)) ushr 1
                            g = s(x, y)
                            b = (s(x - 1, y) + s(x + 1, y)) ushr 1
                        }
                        else -> {
                            r = (s(x - 1, y - 1) + s(x + 1, y - 1) + s(x - 1, y + 1) + s(x + 1, y + 1)) ushr 2
                            g = (s(x - 1, y) + s(x + 1, y) + s(x, y - 1) + s(x, y + 1)) ushr 2
                            b = s(x, y)
                        }
                    }
                    out[y * w + x] = (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
                }
            }
            return out
        }

        fun regionSharpness(argb: IntArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
            var acc = 0.0
            for (y in y0 until y1) {
                val row = y * w
                for (x in x0 until x1) {
                    val g0 = (argb[row + x] ushr 8) and 0xFF
                    val gx = (argb[row + x + 1] ushr 8) and 0xFF
                    val gy = (argb[row + w + x] ushr 8) and 0xFF
                    acc += kotlin.math.abs(gx - g0) + kotlin.math.abs(gy - g0)
                }
            }
            return acc
        }

        IntegrationTest().host(tempRoot) { owner, host ->
            owner.submit {
                val cmos = host.components().single { it.descriptor.id == "cmos" }.component
                val sensor = host.ports.find("cmos", SensorPort::class.java)!!
                val lensPort = host.ports.find("lens", LensPort::class.java)!!
                val lensCtrl = lensPort as LensControlPort
                assertEquals("optical-scene-procedural-depth-readout", cmos.state()["pipeline"])
                assertEquals("READY", cmos.state()["sourceCapability"])
                assertEquals("scene://qr-ball", cmos.state()["activeSourceRef"])
                assertEquals(true, cmos.state()["hasDepthBuffer"])
                assertEquals(true, cmos.state()["opticsFilterActive"])

                // Continuous cached loop exposure at default focus
                val exposure0 = Exposure(100L, 1000L, 100, lensPort.parameters())
                val first = ByteBuffer.allocate(1024 * 576 * 2)
                assertEquals(1024 * 576 * 2, sensor.expose(exposure0, first))
                val expected = first.array().copyOf()
                val reuse = ByteBuffer.allocate(1024 * 576 * 2)
                for (frame in 2..16) {
                    reuse.clear()
                    assertEquals(1024 * 576 * 2, sensor.expose(exposure0.copy(startTick = frame * 1000L), reuse))
                    assertContentEquals(expected, reuse.array())
                }
                assertEquals(16L, cmos.state()["framesExposed"])
                assertEquals(false, cmos.state()["exhausted"])

                // Measure the optics at each focus setting. Live view can still show a previously
                // completed frame while the rotating scene is rendering on the CPU fallback.
                lensCtrl.setFocusDistanceM(0.85)
                val nearBuf = ByteBuffer.allocate(1024 * 576 * 2)
                assertEquals(1024 * 576 * 2, sensor.exposeStill(Exposure(20_000L, 1000L, 100, lensPort.parameters()), nearBuf))
                val argbNear = bayer16RggbToArgb(nearBuf.array(), 1024, 576)

                lensCtrl.setFocusDistanceM(5.5)
                val farBuf = ByteBuffer.allocate(1024 * 576 * 2)
                assertEquals(1024 * 576 * 2, sensor.exposeStill(Exposure(30_000L, 1000L, 100, lensPort.parameters()), farBuf))
                val argbFar = bayer16RggbToArgb(farBuf.array(), 1024, 576)

                val sphereSharpAtNear = regionSharpness(argbNear, 1024, 240, 440, 230, 420)
                val sphereSharpAtFar = regionSharpness(argbFar, 1024, 240, 440, 230, 420)
                val qrSharpAtNear = regionSharpness(argbNear, 1024, 540, 840, 120, 410)
                val qrSharpAtFar = regionSharpness(argbFar, 1024, 540, 840, 120, 410)

                assertTrue(
                    sphereSharpAtNear > sphereSharpAtFar * 1.4,
                    "Focusing at 0.85m must sharpen foreground sphere vs 5.5m (near=$sphereSharpAtNear, far=$sphereSharpAtFar)",
                )
                assertTrue(
                    qrSharpAtFar > qrSharpAtNear * 1.4,
                    "Focusing at 5.5m must sharpen distant QR wall vs 0.85m (far=$qrSharpAtFar, near=$qrSharpAtNear)",
                )
                assertEquals(
                    "FUJIFILM GFX100S QR-BALL 5.5M",
                    QrTestCodec.decode(argbFar, 1024, 576),
                    "Distant 5.5m QR wall must decode cleanly through gf45-100 optics when focused at 5.5m",
                )

                val afFocusedM = lensCtrl.triggerAutoFocus()
                assertTrue(afFocusedM in 0.65..6.0, "Auto-focus on scene://qr-ball should lock within scene depth bounds (got $afFocusedM)")
            }.join()
        }
    }

    @Test
    fun releaseShoulderDisplayConfigEnablesUpright304x230Orientation(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val yamlPath = repo.resolve("tools/release/component-configs/shoulder-display/shoulder-display.yaml")
        assertTrue(Files.isRegularFile(yamlPath), "Missing release shoulder-display.yaml")

        val cfg = Formats.read(yamlPath)
        assertEquals(304, (cfg["width"] as Number).toInt())
        assertEquals(230, (cfg["height"] as Number).toInt())
        assertEquals("dotlcd-serial1-experimental-v1", cfg["profile"])
        assertEquals("upright", cfg["orientation"])

        // Load real shoulder-display JAR with release config and verify 180-degree upright mapping
        val componentsDir = Files.createDirectories(tempRoot.resolve("compoments"))
        Files.copy(
            repo.resolve("components/shoulder-display/build/libs/shoulder-display-0.3.1.jar"),
            componentsDir.resolve("shoulder-display-0.3.1.jar"),
        )
        val shDir = Files.createDirectories(componentsDir.resolve("shoulder-display"))
        Files.copy(yamlPath, shDir.resolve("shoulder-display.yaml"))

        val rawBits = ByteArray(DotLcdTransportCodec.WIDTH * DotLcdTransportCodec.TRANSPORT_ROWS)
        // Firmware logical top-left (x=0, y=0) -> canvas (303, 229)
        rawBits[229 * DotLcdTransportCodec.WIDTH + 303] = 1
        // Firmware logical (x=10, y=2) -> canvas (293, 227)
        rawBits[227 * DotLcdTransportCodec.WIDTH + 293] = 1
        val packet = DotLcdTransportCodec.encode(rawBits)

        com.github.nyabanban.fuji.emulator.runtime.OwnerLoop().use { owner ->
            val host = com.github.nyabanban.fuji.emulator.runtime.ComponentHost(owner, componentsDir)
            try {
                owner.submit {
                    val capture = TransferCapture(1L, DotLcdTransportCodec.BYTES, sha256(packet), 0L, 0L)
                    host.ports.register(
                        "soc-serial-engine",
                        "soc-serial-engine.capture-1",
                        TransferCapturePort::class.java,
                        object : TransferCapturePort {
                            override fun capture(): TransferCapture = capture
                            override fun read(sequence: Long, offset: Long, destination: ByteBuffer) {
                                destination.put(packet, offset.toInt(), destination.remaining())
                            }
                        },
                    )
                    host.load()
                    host.events.publish(
                        ComponentEvent(
                            "soc-serial-engine",
                            "transfer-capture-complete",
                            0L,
                            mapOf("port" to "soc-serial-engine.capture-1", "sequence" to 1L, "size" to DotLcdTransportCodec.BYTES),
                        ),
                    )
                    val display = host.ports.find("display.shoulder", DisplayPort::class.java)!!
                    assertEquals(304, display.width)
                    assertEquals(230, display.height)
                    assertEquals(1L, display.sequence)
                    val pixels = IntArray(304 * 230)
                    assertTrue(display.copyArgb(pixels))
                    assertEquals(0xffffffff.toInt(), pixels[0 * 304 + 0])
                    assertEquals(0xffffffff.toInt(), pixels[2 * 304 + 10])
                    assertEquals(0xff000000.toInt(), pixels[229 * 304 + 303])
                }.join()
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun relaxedResetButtonsForBootPreservesMovieModeAndRestoresPower(@TempDir tempRoot: Path) {
        val buttonsDir = Files.createDirectories(tempRoot.resolve("configs/components/buttons"))
        val buttonsYaml = buttonsDir.resolve("buttons.yaml")
        Formats.atomicYaml(
            buttonsYaml,
            mapOf(
                "menu" to true,
                "back" to false,
                "movieMode" to true,
                "power" to false,
            ),
        )
        val cfg = mapOf<String, Any>(
            "executionMode" to "relaxed",
            "components" to "runtime/compoments",
            "componentConfigs" to "configs/components",
        )
        assertTrue(RelaxedFlashPersistence.resetButtonsForBoot(tempRoot, cfg))
        val updated = Formats.read(buttonsYaml)
        assertEquals(false, updated["menu"], "Momentary keys must reset to false on boot")
        assertEquals(true, updated["movieMode"], "Latching STILL/MOVIE lever must be preserved on boot")
        assertEquals(true, updated["power"], "Power switch must restore to true on boot")
    }

    @Test
    fun releaseModeExamplesAndChineseCommentsValidateCleanly() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val examplesDir = repo.resolve("tools/release/examples")
        val expectedExamples = listOf(
            "emulator-relaxed-default.yaml",
            "emulator-discovery-research.yaml",
            "emulator-strict-gate.yaml",
            "sdcard-reader-directory-dual.yaml",
            "sdcard-reader-qcow2-cow.yaml",
            "sdcard-reader-raw-boot.yaml",
            "cmos-static-image.yaml",
            "cmos-ffmpeg-camera-video.yaml",
            "cmos-stream-ingest.yaml",
            "buttons-movie-mode.yaml",
        )
        for (name in expectedExamples) {
            val file = examplesDir.resolve(name)
            assertTrue(Files.isRegularFile(file), "Missing release mode example: $name")
            val text = Files.readString(file)
            assertTrue(text.contains("#"), "Release example $name must include explanatory comments")
            val parsed = Formats.read(file)
            assertTrue(parsed.isNotEmpty(), "Release example $name must parse as non-empty YAML map")
            if (name.startsWith("emulator-")) {
                validateLaunchConfiguration(parsed)
            }
        }
        assertEquals("relaxed", Formats.read(examplesDir.resolve("emulator-relaxed-default.yaml"))["executionMode"])
        assertEquals("discovery", Formats.read(examplesDir.resolve("emulator-discovery-research.yaml"))["executionMode"])
        assertEquals("strict", Formats.read(examplesDir.resolve("emulator-strict-gate.yaml"))["executionMode"])
        assertEquals("directory", Formats.read(examplesDir.resolve("sdcard-reader-directory-dual.yaml"))["slot1Backend"])
        assertEquals("qcow2", Formats.read(examplesDir.resolve("sdcard-reader-qcow2-cow.yaml"))["slot1Backend"])
        assertEquals("raw", Formats.read(examplesDir.resolve("sdcard-reader-raw-boot.yaml"))["slot1Backend"])
        assertEquals("JPEG", Formats.read(examplesDir.resolve("cmos-static-image.yaml"))["sourceKind"])
        assertEquals("ffmpeg-pipe", Formats.read(examplesDir.resolve("cmos-ffmpeg-camera-video.yaml"))["sourceKind"])
        assertEquals("stream-ingest", Formats.read(examplesDir.resolve("cmos-stream-ingest.yaml"))["sourceKind"])
        assertEquals(true, Formats.read(examplesDir.resolve("buttons-movie-mode.yaml"))["movieMode"])
    }

    @Test
    fun releaseJlinkModulesAndJpackageLaunchersValidateCleanly() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val modulesFile = repo.resolve("tools/release/jpackage/modules.txt")
        assertTrue(Files.isRegularFile(modulesFile), "Missing tools/release/jpackage/modules.txt")
        val modules = Files.readAllLines(modulesFile)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
        assertEquals(modules.distinct(), modules, "modules.txt must not contain duplicate module names")
        val requiredModules = setOf(
            "java.base",
            "java.compiler",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.prefs",
            "java.scripting",
            "java.sql",
            "java.xml",
            "jdk.crypto.ec",
            "jdk.httpserver",
            "jdk.jfr",
            "jdk.unsupported",
            "jdk.unsupported.desktop",
            "jdk.zipfs",
        )
        assertTrue(modules.toSet().containsAll(requiredModules), "modules.txt missing required JDK 25 modules: ${requiredModules - modules.toSet()}")

        val winLauncher = repo.resolve("tools/release/jpackage/win_launcher.c")
        assertTrue(Files.isRegularFile(winLauncher), "Missing tools/release/jpackage/win_launcher.c")
        val launcherSrc = Files.readString(winLauncher)
        for (flag in listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Xms512m",
            "-Xmx2g",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=15",
            "-Dprism.order=d3d,sw",
            "com.github.nyabanban.fuji.emulator.desktop.MainKt",
            "--auto-boot",
        )) {
            assertTrue(launcherSrc.contains(flag), "win_launcher.c missing required launch option: $flag")
        }

        val fetchWinJdk = repo.resolve("tools/release/jpackage/fetch-win64-jdk.sh")
        assertTrue(Files.isRegularFile(fetchWinJdk), "Missing tools/release/jpackage/fetch-win64-jdk.sh")
        val fetchWinJdkText = Files.readString(fetchWinJdk)
        assertTrue(fetchWinJdkText.contains("/windows/x64/jmods/"), "fetch-win64-jdk.sh must fetch JDK 25 JEP 493 image_type=jmods")
        assertTrue(fetchWinJdkText.contains("jmods/java.base.jmod"), "fetch-win64-jdk.sh must normalize extracted jmods into jmods/java.base.jmod")
        val startSh = Files.readString(repo.resolve("tools/release/start-gfx100s.sh"))
        assertTrue(startSh.contains("bin/fuji-emulator-gfx100s"), "start-gfx100s.sh must support jpackage native launcher")
        assertTrue(startSh.contains("jdk/bin/java"), "start-gfx100s.sh must prefer embedded jlink JRE")
        val startBat = Files.readString(repo.resolve("tools/release/start-gfx100s.bat"))
        assertTrue(startBat.contains("jdk\\bin\\java.exe"), "start-gfx100s.bat must prefer embedded jlink JRE")
    }

    @Test
    fun releaseBuildAndCiEnforceMandatoryHighPerformanceComponentsGate() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val gradle = Files.readString(repo.resolve("build.gradle.kts"))
        val ghWorkflow = repo.resolve(".github/workflows/build.yml")
        val ci = if (Files.isRegularFile(ghWorkflow)) Files.readString(ghWorkflow) else ""
        val readme = Files.readString(repo.resolve("tools/release/RELEASE-README.md"))

        // 1. Gradle build must enforce mandatory Dynarmic JIT, Native Audio Bridge, Native SIMD Render Bridge, Windows launcher, and verifyRelease gates
        assertTrue(!gradle.contains("fujiAllowMissingDynarmic"), "build.gradle.kts must not expose an optional Dynarmic bypass")
        for (requiredGradle in listOf(
            "resolveReleaseDynarmicCandidatesLinux()",
            "resolveReleaseDynarmicCandidatesWindows()",
            "resolveReleaseAudioCandidatesLinux()",
            "resolveReleaseAudioCandidatesWindows()",
            "resolveReleaseRenderCandidatesLinux()",
            "resolveReleaseRenderCandidatesWindows()",
            "resolveReleaseVulkanOpticsCandidatesLinux()",
            "resolveReleaseVulkanOpticsCandidatesWindows()",
            "val buildRenderNative = tasks.register(\"buildRenderNative\")",
            "val buildRenderNativeWindowsCross = tasks.register(\"buildRenderNativeWindowsCross\")",
            "val buildVulkanOpticsNative = tasks.register(\"buildVulkanOpticsNative\")",
            "val buildVulkanOpticsNativeWindowsCross = tasks.register(\"buildVulkanOpticsNativeWindowsCross\")",
            "\"runtime/native/libfuji_render.so\"",
            "\"runtime/native/fuji_render.dll\"",
            "\"runtime/native/libfuji_vulkan_optics.so\"",
            "\"runtime/native/fuji_vulkan_optics.dll\"",
            "resolveReleaseIspSimdCandidatesLinux()",
            "resolveReleaseIspSimdCandidatesWindows()",
            "resolveReleaseCodecCandidatesLinux()",
            "resolveReleaseCodecCandidatesWindows()",
            "resolveReleaseGamepadCandidatesLinux()",
            "resolveReleaseGamepadCandidatesWindows()",
            "\"runtime/native/libfuji_isp_simd.so\"",
            "\"runtime/native/fuji_isp_simd.dll\"",
            "\"runtime/native/libfuji_codec.so\"",
            "\"runtime/native/fuji_codec.dll\"",
            "\"runtime/native/libfuji_gamepad.so\"",
            "\"runtime/native/fuji_gamepad.dll\"",
            "val requireWindowsJlink = providers.gradleProperty(\"fujiRequireWindowsJlink\")",
            "val verifyReleaseLinux = tasks.register(\"verifyReleaseLinux\")",
            "val verifyReleaseWindows = tasks.register(\"verifyReleaseWindows\")",
            "dependsOn(stageReleaseLinux, verifyReleaseLinux)",
            "dependsOn(packageReleaseLinuxJpackage, verifyReleaseLinux)",
            "dependsOn(stageReleaseWindows, verifyReleaseWindows)",
        )) {
            assertTrue(gradle.contains(requiredGradle), "build.gradle.kts missing mandatory release gate fragment: $requiredGradle")
        }
        for (forbiddenGradle in listOf(
            "skipping fuji-emulator-gfx100s.exe cross-compile",
            "Optional native Linux PulseAudio/PipeWire audio bridge",
            "Optional Windows x86_64 WASAPI native audio bridge",
        )) {
            assertTrue(!gradle.contains(forbiddenGradle), "build.gradle.kts must not keep optional degradation: $forbiddenGradle")
        }

        // 2. Public GitHub Actions workflow must build & verify all native bridges and Linux/Windows releases cleanly
        for (requiredGh in listOf(
            "build_dynarmic.sh",
            "build_audio.sh",
            "build_render.sh",
            "build_vulkan_optics.sh",
            "build_isp_simd.sh",
            "build_codec.sh",
            "build_gamepad.sh",
            "tools/native/playback-ffmpeg/build.sh",
            ":cpu-dynarmic:test",
            ":components:lens:test",
            ":buildWindowsNativeLauncher",
            ":verifyReleaseLinux",
            ":verifyReleaseWindows",
            "-PfujiRequireWindowsJlink=true",
            "-PfujiBundleStockBins=false",
            "NativeAudioBridgeTest",
            "NativeRenderBridgeParityTest",
        )) {
            assertTrue(ci.contains(requiredGh), ".github/workflows/build.yml missing mandatory CI step: $requiredGh")
        }

        // 4. M9 lens config, launcher native render discovery, and RELEASE-README mandatory component documentation must stay intact
        val lensYaml = repo.resolve("tools/release/component-configs/lens/lens.yaml")
        assertTrue(Files.isRegularFile(lensYaml), "Missing mandatory Release lens config: tools/release/component-configs/lens/lens.yaml")
        assertTrue(Formats.read(lensYaml).isNotEmpty(), "tools/release/component-configs/lens/lens.yaml must not be empty")
        val startSh = Files.readString(repo.resolve("tools/release/start-gfx100s.sh"))
        val startBat = Files.readString(repo.resolve("tools/release/start-gfx100s.bat"))
        val winLauncherSrc = Files.readString(repo.resolve("tools/release/jpackage/win_launcher.c"))
        assertTrue(startSh.contains("runtime/native/libfuji_render.so"), "start-gfx100s.sh must discover runtime/native/libfuji_render.so")
        assertTrue(startBat.contains("runtime\\native\\fuji_render.dll"), "start-gfx100s.bat must discover runtime\\native\\fuji_render.dll")
        assertTrue(startSh.contains("runtime/native/libfuji_vulkan_optics.so"), "start-gfx100s.sh must discover runtime/native/libfuji_vulkan_optics.so")
        assertTrue(startBat.contains("runtime\\native\\fuji_vulkan_optics.dll"), "start-gfx100s.bat must discover runtime\\native\\fuji_vulkan_optics.dll")
        assertTrue(winLauncherSrc.contains("-Dfuji.vulkan.optics.library="), "win_launcher.c must inject -Dfuji.vulkan.optics.library=")
        assertTrue(startSh.contains("runtime/native/libfuji_isp_simd.so"), "start-gfx100s.sh must discover runtime/native/libfuji_isp_simd.so")
        assertTrue(startBat.contains("runtime\\native\\fuji_isp_simd.dll"), "start-gfx100s.bat must discover runtime\\native\\fuji_isp_simd.dll")
        assertTrue(winLauncherSrc.contains("-Dfuji.isp.simd.library="), "win_launcher.c must inject -Dfuji.isp.simd.library=")
        assertTrue(startSh.contains("runtime/native/libfuji_codec.so"), "start-gfx100s.sh must discover runtime/native/libfuji_codec.so")
        assertTrue(startBat.contains("runtime\\native\\fuji_codec.dll"), "start-gfx100s.bat must discover runtime\\native\\fuji_codec.dll")
        assertTrue(winLauncherSrc.contains("-Dfuji.codec.library="), "win_launcher.c must inject -Dfuji.codec.library=")
        assertTrue(startSh.contains("runtime/native/libfuji_gamepad.so"), "start-gfx100s.sh must discover runtime/native/libfuji_gamepad.so")
        assertTrue(startBat.contains("runtime\\native\\fuji_gamepad.dll"), "start-gfx100s.bat must discover runtime\\native\\fuji_gamepad.dll")
        assertTrue(winLauncherSrc.contains("-Dfuji.gamepad.library="), "win_launcher.c must inject -Dfuji.gamepad.library=")
        for (docToken in listOf(
            "libfuji_dynarmic.so", "fuji_dynarmic.dll",
            "libfuji_audio.so", "fuji_audio.dll",
            "libfuji_render.so", "fuji_render.dll",
            "libfuji_vulkan_optics.so", "fuji_vulkan_optics.dll",
            "libfuji_isp_simd.so", "fuji_isp_simd.dll",
            "libfuji_codec.so", "fuji_codec.dll",
            "libfuji_gamepad.so", "fuji_gamepad.dll",
            "fuji-emulator-gfx100s.exe", "verifyReleaseLinux", "verifyReleaseWindows",
        )) {
            assertTrue(readme.contains(docToken), "tools/release/RELEASE-README.md missing mandatory component reference: $docToken")
        }
    }

    @Test
    fun startupFeedbackGuardsMachineOutputModesInLauncherAndMain() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        assertTrue(!isMachineOutputMode(emptyArray()))
        assertTrue(!isMachineOutputMode(arrayOf("--headless", "--auto-boot", "--debug")))
        assertTrue(isMachineOutputMode(arrayOf("--probe")))
        assertTrue(isMachineOutputMode(arrayOf("--inspect")))
        assertTrue(isMachineOutputMode(arrayOf("--query-address", "0xfff40000")))

        val winLauncher = Files.readString(repo.resolve("tools/release/jpackage/win_launcher.c"))
        assertTrue(winLauncher.contains("Launching FUJIFILM GFX100S Emulator...\\n"), "win_launcher.c must emit instant startup banner")
        assertTrue(winLauncher.contains("fflush(stdout);"), "win_launcher.c must flush stdout before CreateProcessW")
        assertTrue(winLauncher.contains("is_machine_output_mode(argc, argv)"), "win_launcher.c must suppress banner in JSON machine-output modes")
    }

    @Test
    fun dynarmicOnlyReleaseOmitsUnicornAndPrunesOfflineResearchFromReleaseJar() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        // 1. All Dynarmic-only release & relaxed configs omit nativeLibrary and pass strict envelope validation
        for (rel in listOf(
            "tools/release/emulator-release-linux.yaml",
            "tools/release/emulator-release-windows.yaml",
            "tools/release/examples/emulator-relaxed-default.yaml",
            "tools/release/examples/dynarmic-turbo.yaml",
        )) {
            val cfg = Formats.read(repo.resolve(rel))
            assertEquals("dynarmic", cfg["cpuEngine"], "$rel must enforce cpuEngine=dynarmic")
            assertTrue(!cfg.containsKey("nativeLibrary"), "$rel must omit redundant Unicorn nativeLibrary")
            validateLaunchConfiguration(cfg)
            // Backwards compatibility: legacy user config with both cpuEngine=dynarmic and nativeLibrary stays valid
            validateLaunchConfiguration(cfg + ("nativeLibrary" to "runtime/native/libunicorn.so.2"))
        }

        // 2. Unicorn engine is fully pruned (rejected by validation), while strict/discovery no longer require nativeLibrary
        val linuxCfg = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))
        assertFailsWith<IllegalArgumentException>("removed unicorn engine must fail validation") {
            validateLaunchConfiguration(linuxCfg + ("cpuEngine" to "unicorn"))
        }
        val strictCfg = Formats.read(repo.resolve("tools/release/examples/emulator-strict-gate.yaml"))
        validateLaunchConfiguration(strictCfg - "nativeLibrary")

        // 3. Release packaging & CI must contain zero Unicorn build/packaging steps (100% pure MIT Dynarmic)
        val gradle = Files.readString(repo.resolve("build.gradle.kts"))
        assertTrue(gradle.contains("!stageDir.resolve(\"runtime/native/libunicorn.so.2\").exists()"))
        assertTrue(gradle.contains("!stageDir.resolve(\"runtime/native/unicorn.dll\").exists()"))
        val ghWorkflow = repo.resolve(".github/workflows/build.yml")
        val ci = if (Files.isRegularFile(ghWorkflow)) Files.readString(ghWorkflow) else ""
        assertTrue(ci.contains(":cpu-dynarmic:test"), "CI must run :cpu-dynarmic:test")
        assertTrue(!ci.contains("cpu-unicorn") && !ci.contains("buildArmFastNative"), "CI must not reference pruned Unicorn tasks")

        // 4. When :desktop:releaseJar has been built, verify it stays slim (<3 MiB) and keeps required runtime helpers
        val slimJar = repo.resolve("desktop/build/release-libs/desktop-0.3.1.jar")
        if (Files.isRegularFile(slimJar)) {
            val size = Files.size(slimJar)
            assertTrue(size in 1L..3_000_000L, "Expected slim desktop releaseJar < 3 MiB, got $size B")
            java.net.URLClassLoader(arrayOf(slimJar.toUri().toURL()), null).use { release ->
                for (texture in listOf(
                    "panels/gfx100s/artwork/top-shell.png",
                    "themes/body/shared/xcd-glass.png",
                    "themes/body/shared/leatherette.png",
                    "themes/body/shared/brushed-chrome.png",
                    "themes/body/compact-rangefinder/artwork/mode-dial.png",
                )) {
                    val url = assertNotNull(release.getResource(texture), "Missing release camera artwork: $texture")
                    assertTrue(url.path.contains("/camera-artwork-"), "Unexpected artwork location: $url")
                }
            }
            java.util.zip.ZipFile(slimJar.toFile()).use { zf ->
                assertTrue(zf.getEntry("com/github/nyabanban/fuji/emulator/desktop/MainKt.class") != null)
                assertTrue(zf.getEntry("com/github/nyabanban/fuji/emulator/desktop/EmulatorApp.class") != null)
                assertTrue(zf.getEntry("com/github/nyabanban/fuji/emulator/desktop/FirmwareSession.class") != null)
                assertTrue(zf.getEntry("com/github/nyabanban/fuji/emulator/desktop/qualification/DramOriginalQualification.class") == null)
                assertTrue(zf.getEntry("com/github/nyabanban/fuji/emulator/desktop/tools/Power2fStudyVerifier.class") == null)
            }
        }
    }

    @Test
    fun releaseComponentConfigsPruneInternalWiringAndKeepCleanEnglishDocs() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val base = repo.resolve("tools/release/component-configs")

        val forbiddenUserFields = mapOf(
            "sdcard-reader" to setOf("syncCardDetectGpio", "cardInsertReadyDelayTicks"),
            "shutter" to setOf("curtainTravelUs", "rollingReadoutUs", "camTravelTicks", "camContinueTicks", "curtainEdgeTicks"),
            "lens" to setOf("focusMotorInstant"),
            "rear-touch-ic" to setOf("busPort", "address7", "irqLine", "resetGpioBank", "resetGpioPin", "touchPressed", "touchX", "touchY"),
            "tmd2772" to setOf("busPort", "address7", "proximityRaw", "driveIntGpio", "intGpioBank", "intGpioPin"),
            "mma8452q" to setOf("bodyBusPort", "lcdBusPort", "accelEnabled"),
            "max17048" to setOf("busPort", "address7", "cells", "versionId"),
            "wm8962" to setOf("busPort", "address7"),
            "bcm43438" to setOf("sdioProdId", "uartPort", "sdioPort", "btRegOnBank", "btRegOnPin", "wlRegOnBank", "wlRegOnPin"),
            "display" to setOf("width", "height", "evfWidth", "evfHeight"),
            "mount" to setOf("lensPort"),
        )
        val userFacingComponents = listOf(
            "battery", "bcm43438", "buttons", "cmos", "display", "lens", "max17048",
            "mma8452q", "mount", "rear-touch-ic", "rtc", "sdcard-reader",
            "shoulder-display", "shutter", "temperature-sensor", "tmd2772", "wm8962",
        )
        val mmioHexRegex = Regex("0x[fF][fF][fF][0-9a-fA-F]{5}")
        for (id in userFacingComponents) {
            val file = base.resolve("$id/$id.yaml")
            assertTrue(Files.isRegularFile(file), "Missing release config for user-facing component: $id")
            val text = Files.readString(file)
            assertTrue(text.contains("#"), "$id.yaml must contain photographer-readable comments")
            assertTrue(text.none { it.code in 0x4E00..0x9FFF }, "$id.yaml must use clean English comments")
            assertTrue(!mmioHexRegex.containsMatchIn(text), "$id.yaml must not contain raw MMIO hex addresses")
            val parsed = Formats.read(file)
            assertTrue(parsed.isNotEmpty(), "$id.yaml must parse as a non-empty YAML map")
            val forbidden = forbiddenUserFields[id].orEmpty()
            for (key in forbidden) {
                assertTrue(key !in parsed, "$id.yaml must prune internal hardware/wiring key '$key'")
            }
        }

        val internalHeader = "# Internal GFX100S SoC hardware definition — no user edits needed."
        val internalComponents = listOf(
            "i2c-bus", "power-peer", "system-memory",
            "soc-adc", "soc-configuration", "soc-dma", "soc-dram", "soc-flash", "soc-gic",
            "soc-gpio", "soc-hdmi", "soc-i2c", "soc-isp", "soc-isp-cmd", "soc-isp-setup",
            "soc-jpeg", "soc-keyed-io", "soc-method4", "soc-panel", "soc-sd-host", "soc-sdio",
            "soc-selection", "soc-serial-engine", "soc-spi", "soc-system-control",
            "soc-temperature", "soc-timer", "soc-uart", "soc-vglib-init",
        )
        for (id in internalComponents) {
            val file = base.resolve("$id/$id.yaml")
            assertTrue(Files.isRegularFile(file), "Missing internal SoC config: $id")
            val lines = Files.readAllLines(file)
            assertEquals(internalHeader, lines.firstOrNull(), "$id.yaml must start with the standard internal SoC header")
            assertTrue(Formats.read(file).isNotEmpty(), "$id.yaml must parse as a non-empty YAML map")
        }

        // BUG-V3-3-6: Release soc-adc CH0 (CMOSTEMP 100k NTC + 51k pull-up) must be calibrated at room temp (~25C -> 2712),
        // well above the firmware overheat ceiling (adc <= 807 -> >= 72C) so PIXEL MAPPING executes cleanly at room temperature.
        val adcInput = Formats.readData(base.resolve("soc-adc/input.json"))
        @Suppress("UNCHECKED_CAST")
        val adcInputs = adcInput["inputs"] as Map<String, Any?>
        val ch0 = (adcInputs["0"] as Number).toInt()
        assertTrue(ch0 in 2000..3200, "Release soc-adc CH0 (CMOSTEMP) must be room-temperature NTC count (2000..3200), got $ch0")
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
