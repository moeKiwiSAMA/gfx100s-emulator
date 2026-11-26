package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.api.EnabledEdgeInterruptSink
import com.github.nyabanban.fuji.emulator.api.InterruptSink
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.api.MmioDevice
import com.github.nyabanban.fuji.emulator.api.ScheduledMmioDevice
import com.github.nyabanban.fuji.emulator.api.SpiDmaFifo
import com.github.nyabanban.fuji.emulator.desktop.e2e.E2eHarness
import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Track R2 verification suite:
 * - Deterministic host-level SPI0 lenscom (mount.spi + Spi0Dma + GIC 166/182) & SPI2 biscom integration;
 * - Env-gated (FUJI_M1_LIVE=1) live snapshot verification for Still-mode UI_LIB_MENU_REC (0x0400)
 *   with zero errno 0x0095 and blank-card ch21 bit 0x800 (0x914F) / bDscModeChgFlg clearing.
 */
class M1StillMenuAndCh21E2eTest {

    private fun readU32(mem: DmaMemory, addr: Long): Long {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        return buf.getInt(0).toLong() and 0xffffffffL
    }

    private fun readU16(mem: DmaMemory, addr: Long): Int {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        mem.read(addr, buf)
        return buf.getShort(0).toInt() and 0xffff
    }

    private fun readU8(mem: DmaMemory, addr: Long): Int {
        val buf = ByteBuffer.allocate(1)
        mem.read(addr, buf)
        return buf.get(0).toInt() and 0xff
    }

    private fun probeGuestDiag(rt: EmulatorRuntime): Map<String, Any> = rt.owner.submit {
        val mem = checkNotNull(rt.host.ports.find("cpu.memory", DmaMemory::class.java))
        val adcState = rt.host.components().single { it.descriptor.id == "soc-adc" }.component.state()
        @Suppress("UNCHECKED_CAST")
        val adcRegs = adcState["registers"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val adcSamples = (adcRegs?.get("samples") as? List<*>)?.map { (it as Number).toInt() }.orEmpty()
        val adcReads = (adcState["sampleReadCount"] as? Number)?.toLong() ?: -1L
        val adcEvents = (adcState["eventCount"] as? Number)?.toLong() ?: -1L
        val sink = rt.host.ports.find("soc-gic.lines", InterruptSink::class.java) as? EnabledEdgeInterruptSink
        val enabledIrqs = if (sink != null) {
            (32..319).filter { sink.lineEnabled(it) }.joinToString(",")
        } else {
            ""
        }
        linkedMapOf<String, Any>(
            "scr" to readU32(mem, 0x01CC6424L),
            "prevScr" to readU32(mem, 0x01CC6420L),
            "curScr" to readU32(mem, 0x01CBFFD4L),
            "bDscModeChgFlg" to readU8(mem, 0x01CBFCA2L),
            "dscMode0" to readU32(mem, 0x01CBFCA0L),
            "dscMode4" to readU32(mem, 0x01CBFCA4L),
            "activeErrno" to readU16(mem, 0x01CC0628L),
            "acceptWord0" to readU32(mem, 0x01CBEFC8L),
            "acceptWord1" to readU32(mem, 0x01CBEFCCL),
            "uiEvt0" to readU32(mem, 0x01CB6B70L),
            "uiEvt1" to readU32(mem, 0x01CB6B74L),
            "subEvt" to readU16(mem, 0x01CBF020L),
            "c0a6f" to readU8(mem, 0x01CC0A6FL),
            "sm94" to readU16(mem, 0x02D6A80CL + 0xBCL),
            "irq90Cb" to readU32(mem, 0x01871278L + 20L * 90L + 16L),
            "crec3a" to readU16(mem, 0x02D6A80CL + 0x3AL),
            "c0a60" to readU8(mem, 0x01CC0A60L),
            "evtFlags" to readU32(mem, 0x01CBF4D8L),
            "ch21Req" to readU32(mem, 0x02D77FE8L),
            "ch21InFlight" to readU32(mem, 0x02D77FECL),
            "ch21Done" to readU32(mem, 0x02D77FF0L),
            "reccfM0" to readU16(mem, 0x02D6F838L),
            "reccfM2" to readU16(mem, 0x02D6F83CL),
            "biscomState" to readU8(mem, 0x01C66974L),
            "lenscomState" to readU8(mem, 0x01A337F4L),
            "lensMgrState" to readU8(mem, 0x01BE013CL),
            "adcReads" to adcReads,
            "adcEvents" to adcEvents,
            "adcSamples" to adcSamples.joinToString(","),
            "gicEn" to enabledIrqs,
        )
    }.join()

    private fun formatDiag(d: Map<String, Any>): String =
        "scr=0x${(d["scr"] as Long).toString(16)}(prev=0x${(d["prevScr"] as Long).toString(16)},cur=0x${(d["curScr"] as Long).toString(16)}) " +
            "bDscModeChgFlg=${d["bDscModeChgFlg"]}(m0=0x${(d["dscMode0"] as Long).toString(16)},m4=0x${(d["dscMode4"] as Long).toString(16)}) " +
            "errno=0x${(d["activeErrno"] as Int).toString(16)} " +
            "accept=(0x${(d["acceptWord0"] as Long).toString(16)},0x${(d["acceptWord1"] as Long).toString(16)}) " +
            "uiEvt=(0x${(d["uiEvt0"] as Long).toString(16)},0x${(d["uiEvt1"] as Long).toString(16)},sub=0x${(d["subEvt"] as Int).toString(16)}) " +
            "c0a6f=${d["c0a6f"]} sm94=${d["sm94"]} irq90=0x${(d["irq90Cb"] as Long).toString(16)} " +
            "crec=(3a=${d["crec3a"]},a60=${d["c0a60"]},ev=0x${(d["evtFlags"] as Long).toString(16)}) " +
            "ch21=(req=0x${(d["ch21Req"] as Long).toString(16)},in=0x${(d["ch21InFlight"] as Long).toString(16)},done=0x${(d["ch21Done"] as Long).toString(16)}) " +
            "reccf=(M0=${d["reccfM0"]},M2=${d["reccfM2"]}) " +
            "biscom=${d["biscomState"]} lenscom=${d["lenscomState"]} lensMgr=${d["lensMgrState"]} " +
            "adc=(ev=${d["adcEvents"]},rd=${d["adcReads"]},s=[${d["adcSamples"]}]) gic=[${d["gicEn"]}]"

    private fun captureDisplayMain(rt: EmulatorRuntime): BufferedImage? {
        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
        val px = IntArray(main.width * main.height)
        if (!main.copyArgb(px)) return null
        return BufferedImage(main.width, main.height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, main.width, main.height, px, 0, main.width)
        }
    }

    private fun countNonBlackPixels(img: BufferedImage): Int {
        var count = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) and 0x00ffffff) != 0) count++
            }
        }
        return count
    }

    private fun countDiffPixels(a: BufferedImage, b: BufferedImage): Int {
        require(a.width == b.width && a.height == b.height)
        var diff = 0
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) diff++
            }
        }
        return diff
    }

    private fun extendedRamPlan(snapshotDir: Path): AdditionalRamPlan {
        val manifest = Formats.json.readTree(snapshotDir.resolve("manifest.json").toFile())
        val planNode = manifest.path("memory").path("additionalRamReceipt").path("plan")
        require(!planNode.isMissingNode && !planNode.isNull) { "Missing additionalRamReceipt.plan in $snapshotDir" }
        @Suppress("UNCHECKED_CAST")
        val rawPlan = Formats.json.convertValue(planNode, Map::class.java) as Map<String, Any?>
        val basePlan = AdditionalRamPlan.parse(rawPlan)
        val extraRegions = listOf(
            MemoryRegion("liveview-dma-ring-1a0ca000", 0x1a0ca000L, 2_957_312L, 3),
            MemoryRegion("still-disp-2b3a2000", 0x2b3a2000L, 0x00280000L, 3),
            MemoryRegion("still-di-2c482000", 0x2c482000L, 0x000c4000L, 3),
            MemoryRegion("still-di-2c548000", 0x2c548000L, 0x00006000L, 3),
            MemoryRegion("still-di-2c59e000", 0x2c59e000L, 0x00014000L, 3),
            MemoryRegion("still-di-2c5b6000", 0x2c5b6000L, 0x0004c000L, 3),
            MemoryRegion("still-pic1-liveview-ring-3d79a000", 0x3d79a000L, 0x02000000L, 3),
            MemoryRegion("movie-return-work-7fb00000", 0x7fb00000L, 5_242_880L, 3),
        )
        val merged = basePlan.additionalRegions.toMutableList()
        for (region in extraRegions) {
            if (merged.none { it.id == region.id }) merged.add(region)
        }
        return AdditionalRamPlan(
            baseProfile = basePlan.baseProfile,
            sourceProfile = basePlan.sourceProfile,
            sourceCommit = basePlan.sourceCommit,
            catalogSha256 = basePlan.catalogSha256,
            baseRegions = basePlan.baseRegions,
            additionalRegions = merged,
            profile = basePlan.profile,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun prepareStillMenuHome(sourceHome: Path, tempRoot: Path, firmwareFile: Path, nativeLib: Path): Path {
        val home = E2eHarness.cloneBringupHome(
            sourceHome,
            tempRoot,
            copySdImage = true,
            overrideSdReadOnly = false,
        )
        val stagedCompDir = Path.of("build/emulator/compoments").toAbsolutePath().normalize()
            .let { if (Files.isDirectory(it)) it else Path.of("../build/emulator/compoments").toAbsolutePath().normalize() }
        if (Files.isDirectory(stagedCompDir)) {
            Files.list(stagedCompDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".jar") }.forEach { jar ->
                    Files.copy(jar, home.resolve("compoments").resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        // Enable soc-gpio v4 (binds movieMode -> GPIO Bank 7 Pin 5 active-LOW)
        val gpioYaml = home.resolve("compoments/soc-gpio/soc-gpio.yaml")
        val gpioCfg = Formats.read(gpioYaml).toMutableMap()
        gpioCfg["profile"] = "gfx100s-2.13-gpio-buttons-v4"
        Formats.atomicYaml(gpioYaml, gpioCfg)

        // Enable repeatable DRAM command plans + repeatable post-ACK PHY replay
        val dramInputYaml = home.resolve("compoments/soc-dram/input.yaml")
        val dramInput = Formats.read(dramInputYaml).toMutableMap()
        val banks = (dramInput["banks"] as Map<String, Any?>).toMutableMap()
        for (bankKey in listOf("0", "1", "2", "3")) {
            val bank = (banks[bankKey] as Map<String, Any?>).toMutableMap()
            bank["commandPlanMode"] = "any-qualified-mode-repeatable-v1"
            if (bankKey in listOf("1", "2", "3")) {
                bank["postAckPhyControl"] = "dat-replay-repeatable-v1"
            }
            banks[bankKey] = bank
        }
        dramInput["banks"] = banks
        Formats.atomicYaml(dramInputYaml, dramInput)

        // Promote soc-isp to isp-banks-experimental-v2 + autoFrameIrqs=true (drives enabled 100/102/106/107/108/109/296)
        val ispDir = home.resolve("compoments/soc-isp")
        Files.createDirectories(ispDir)
        Formats.atomicYaml(
            ispDir.resolve("soc-isp.yaml"),
            mapOf("profile" to "isp-banks-experimental-v2", "initialFill" to 0, "autoFrameIrqs" to true),
        )

        // Promote soc-panel to panel-init-repeatable-v1
        val panelDir = home.resolve("compoments/soc-panel")
        Files.createDirectories(panelDir)
        Formats.atomicYaml(panelDir.resolve("soc-panel.yaml"), mapOf("profile" to "panel-init-repeatable-v1"))

        // Promote soc-uart to pl011-tx-ready-experimental-v1 (UARTFR TXFE=1/BUSY=0 so prio-10 btinit does not busy-spin and starve prio-10 media tasks!)
        val uartDir = home.resolve("compoments/soc-uart")
        Files.createDirectories(uartDir)
        Formats.atomicYaml(uartDir.resolve("soc-uart.yaml"), mapOf("profile" to "pl011-tx-ready-experimental-v1"))

        // Promote mount to gf-lenscom-frame32-experimental-v1 + soc-dma spi0DmaProfile + soc-spi SPI0 requestEnable3Limit
        val mountDir = home.resolve("compoments/mount")
        Files.createDirectories(mountDir)
        Formats.atomicYaml(
            mountDir.resolve("mount.yaml"),
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to "gf-lenscom-frame32-experimental-v1"),
        )
        val dmaYaml = home.resolve("compoments/soc-dma/soc-dma.yaml")
        val dmaCfg = Formats.read(dmaYaml).toMutableMap()
        dmaCfg["spi0DmaProfile"] = "spi0-four-byte-fifo-v1"
        Formats.atomicYaml(dmaYaml, dmaCfg)

        // Enable repeatable serial-engine plans + IRQ 90/91 completion status + HDMI link reads + high SPI4/5 exchange limits for Still-mode MENU
        val serialYaml = home.resolve("compoments/soc-serial-engine/soc-serial-engine.yaml")
        if (Files.isRegularFile(serialYaml)) {
            val serialCfg = Formats.read(serialYaml).toMutableMap()
            serialCfg["repeatLastPlan"] = true
            serialCfg["completionIrqs"] = true
            Formats.atomicYaml(serialYaml, serialCfg)
        }
        val hdmiYaml = home.resolve("compoments/soc-hdmi/soc-hdmi.yaml")
        if (Files.isRegularFile(hdmiYaml)) {
            val hdmiCfg = Formats.read(hdmiYaml).toMutableMap()
            hdmiCfg["repeatLastLinkRead"] = true
            Formats.atomicYaml(hdmiYaml, hdmiCfg)
        }
        val spiPortsPath = home.resolve("compoments/soc-spi/ports.json")
        if (Files.isRegularFile(spiPortsPath)) {
            val portsData = Formats.readData(spiPortsPath).toMutableMap()
            val controllers = (portsData["controllers"] as? List<Map<String, Any?>>)?.map { ctrl ->
                val updated = ctrl.toMutableMap()
                when ((updated["index"] as? Number)?.toInt()) {
                    4 -> (updated["audio16Replies"] as? Map<String, Any?>)?.let { audio ->
                        updated["audio16Replies"] = audio.toMutableMap().apply { this["exchangeLimit"] = 16384 }
                    }
                    5 -> (updated["panel16Replies"] as? Map<String, Any?>)?.let { panel ->
                        updated["panel16Replies"] = panel.toMutableMap().apply { this["exchangeLimit"] = 2048 }
                    }
                }
                updated
            }
            if (controllers != null) {
                portsData["controllers"] = controllers
                Formats.writeJson(spiPortsPath, portsData)
            }
        }

        val vglibYaml = home.resolve("compoments/soc-vglib-init/soc-vglib-init.yaml")
        val vglibCfg = Formats.read(vglibYaml).toMutableMap()
        vglibCfg["firstScanout"] = "lcdc1-pic-yuv-osd0-nearest-v1"
        vglibCfg["continuousScanout"] = true
        vglibCfg["frameSyncResumeTick"] = 650_000_000L
        vglibCfg["frameSyncFollowupTicks"] = (0 until 120).joinToString(",") { (716_000_000L + it * 3_000_000L).toString() }
        Formats.atomicYaml(vglibYaml, vglibCfg)

        val emuYaml = home.resolve("config/emulator.yaml")
        val emuText = Files.readString(emuYaml, Charsets.UTF_8)
            .replace("fff49530-write-pair-once-v1", "fff49530-write-pair-repeatable-v1")
            .replace("fff49534-49500-write-tail-once-v1", "fff49534-49500-write-tail-repeatable-v1")
        Files.writeString(emuYaml, emuText, Charsets.UTF_8)
        val emuCfg = Formats.read(emuYaml).toMutableMap()
        val enabledDevices = (emuCfg["enabledDevices"] as? List<*>).orEmpty().map { it.toString() }.toMutableList()
        for (dev in listOf("soc-isp", "soc-panel", "soc-uart")) {
            if (dev !in enabledDevices) enabledDevices.add(dev)
        }
        emuCfg["enabledDevices"] = enabledDevices
        emuCfg["idleClock"] = mapOf(
            "profile" to "next-deadline-functional-experimental-v1",
            "maxAdvances" to 65536,
            "maxIdleTicks" to 1_000_000_000L,
        )
        val discovery = (emuCfg["discovery"] as? Map<String, Any?>)?.toMutableMap()
        if (discovery != null) {
            for (legacyKey in listOf(
                "image50000", "image50000Control", "image51000", "image59000", "image68000",
                "image6e000", "image70000", "imageIrq106Status", "imageLiveViewBanks", "imageResultStats",
            )) {
                discovery.remove(legacyKey)
            }
            val inputs = (discovery["inputs"] as? Map<String, Any?>)?.toMutableMap()
            if (inputs != null) {
                for (retiredId in listOf(
                    "gfx100s.fff69000.config.candidate", "gfx100s.fff69100.config-set.candidate",
                    "gfx100s.fff6b000.config.candidate", "gfx100s.fff11060.result-surface.candidate",
                    "gfx100s.fff72028.config-words.candidate", "gfx100s.panel.init-words.candidate",
                    "gfx100s.fff3a000.uart-bank.candidate", "gfx100s.fff3a030.uart-config.candidate",
                    "gfx100s.fff3a034.uart-tail.candidate",
                )) {
                    inputs.remove(retiredId)
                }
                discovery["inputs"] = inputs
            }
            emuCfg["discovery"] = discovery
        }
        val repoRoot = Path.of("configurations/jvm/firmware/gfx100s-213.yaml").toAbsolutePath().normalize()
            .let { if (Files.isRegularFile(it)) Path.of(".").toAbsolutePath().normalize() else Path.of("..").toAbsolutePath().normalize() }
        val freshCatalog = repoRoot.resolve("catalog/compiled/catalog.json")
        if (Files.isRegularFile(freshCatalog)) {
            Files.copy(freshCatalog, home.resolve("config/catalog.compiled.json"), StandardCopyOption.REPLACE_EXISTING)
            val compiled = com.github.nyabanban.fuji.emulator.catalog.AddressCatalog.load(freshCatalog)
            val baseMeta = compiled.legacyFirmwareMetadata().toMutableMap()
            val profMeta = compiled.firmwareMetadata(
                mapOf(
                    "soc-sd-host" to "gfx100s-sd-host-address-extension-experimental-v7",
                    "soc-vglib-init" to "lcdc-functional-experimental-v1",
                    "soc-isp" to "isp-banks-experimental-v2",
                ),
            )
            val overrideIds = setOf("soc-sd-host", "soc-vglib-init", "soc-isp")
            val mergedDevices = (baseMeta["devices"] as List<Map<String, Any?>>).filter { it["id"] !in overrideIds } +
                (profMeta["devices"] as List<Map<String, Any?>>).filter { it["id"] in overrideIds }
            baseMeta["devices"] = mergedDevices
            val metaYaml = home.resolve("config/firmware-metadata-m1.yaml")
            Formats.atomicYaml(metaYaml, baseMeta)
            emuCfg["firmwareMetadata"] = metaYaml.toString()
        }
        emuCfg["firmwareFile"] = firmwareFile.toAbsolutePath().normalize().toString()
        emuCfg["nativeLibrary"] = nativeLib.toAbsolutePath().normalize().toString()
        emuCfg["httpPort"] = 0
        Formats.atomicYaml(emuYaml, emuCfg)
        return home
    }

    private fun runStillModeMenuCheck(
        snapRoot: Path,
        tempRoot: Path,
        label: String,
        exportDir: Path?,
    ) {
        val fw = E2eHarness.resolveFirmwareFile()
        val lib = E2eHarness.resolveNativeLibrary()
        assumeTrue(fw != null && lib != null, "Missing firmware/native library")
        val sourceHome = snapRoot.resolve("home")
        val snapDir = snapRoot.resolve("snap")
        assumeTrue(Files.isDirectory(sourceHome) && Files.isDirectory(snapDir), "Missing home/ or snap/ under $snapRoot")

        val home = prepareStillMenuHome(sourceHome, tempRoot.resolve("$label-home"), fw!!, lib!!)
        val ramPlan = extendedRamPlan(snapDir)
        val cfg = Formats.read(home.resolve("config/emulator.yaml"))
        EmulatorRuntime(home, cfg).use { rt ->
            rt.owner.submit {
                rt.session.initialize()
                rt.session.restoreEmulatorSnapshot(
                    snapDir,
                    EmulatorSnapshot.RestoreOptions(
                        overrideBudget = 1_200_000_000L,
                        allowCompatibleProfileChanges = setOf(
                            "soc-gpio", "soc-dram", "soc-vglib-init", "soc-isp", "soc-panel",
                            "soc-uart", "mount", "soc-dma", "soc-serial-engine", "soc-hdmi", "soc-spi",
                        ),
                        allowPromotedDiscoveryRegions = true,
                    ),
                )
                rt.session.extendAdditionalRam(ramPlan)
                val gpio = rt.host.components().single { it.descriptor.id == "soc-gpio" }
                rt.host.patch(
                    "soc-gpio",
                    gpio.revision,
                    mapOf("input0" to 0x4000L, "input2" to 0x2004L, "input6" to 0x0012L, "input7" to 0x2040L, "input8" to 0x0010L),
                ).join()
                val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                rt.host.patch("buttons", btn.revision, mapOf("movieMode" to true)).join()
            }.join()

            fun patchButton(key: String, value: Boolean) {
                rt.owner.submit {
                    val btn = rt.host.components().single { it.descriptor.id == "buttons" }
                    rt.host.patch("buttons", btn.revision, mapOf(key to value)).join()
                }.join()
            }

            var saw296 = false

            fun stepPhase(
                deltaAttempts: Long,
                phaseLabel: String,
                autoDriveIrqs: Boolean = true,
                stopWhen: ((Map<String, Any>, Boolean, Int, Int) -> Boolean)? = null,
            ): BufferedImage? {
                var lastSeq = rt.owner.submit { checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java)).sequence }.join()
                var lastImg: BufferedImage? = null
                var framesAfter296 = 0
                var framesInPhase = 0
                val startAtt = rt.owner.submit { rt.session.checkpointAttempts() }.join()
                val targetAtt = startAtt + deltaAttempts
                var nextIrq117Att = startAtt + 3_000_000L
                var nextDiagAtt = startAtt + 4_000_000L

                while (true) {
                    val (running, att, img) = rt.owner.submit {
                        val ok = rt.session.step()
                        val cur = rt.session.checkpointAttempts()
                        val main = checkNotNull(rt.host.ports.find("display.main", DisplayPort::class.java))
                        var frame: BufferedImage? = null
                        if (main.sequence > lastSeq) {
                            lastSeq = main.sequence
                            frame = captureDisplayMain(rt)
                        }
                        if (autoDriveIrqs) {
                            val sink = rt.host.ports.find("soc-gic.lines", InterruptSink::class.java) as EnabledEdgeInterruptSink
                            if (cur >= nextIrq117Att && sink.lineEnabled(117)) {
                                val st117 = sink.edgeState(117)
                                if (st117.edge && !st117.high && !st117.pending) {
                                    sink.setLine(117, true)
                                    sink.setLine(117, false)
                                    nextIrq117Att = cur + 4_000_000L
                                }
                            }
                            if (sink.lineEnabled(296)) {
                                saw296 = true
                            }
                        }
                        Triple(ok, cur, frame)
                    }.join()
                    if (img != null) {
                        lastImg = img
                        framesInPhase++
                        if (saw296) framesAfter296++
                        val d = probeGuestDiag(rt)
                        println("[R2][$label][$phaseLabel] frame seq=$lastSeq att=$att nonBlack=${countNonBlackPixels(img)} saw296=$saw296 ${formatDiag(d)}")
                        if (stopWhen != null && stopWhen(d, saw296, framesInPhase, framesAfter296)) break
                    } else if (att >= nextDiagAtt) {
                        val d = probeGuestDiag(rt)
                        println("[R2][$label][$phaseLabel] tick att=$att saw296=$saw296 ${formatDiag(d)}")
                        nextDiagAtt += 4_000_000L
                        if (stopWhen != null && stopWhen(d, saw296, framesInPhase, framesAfter296)) break
                    }
                    if (!running || att >= targetAtt) break
                }
                val finalDiag = probeGuestDiag(rt)
                val summary = rt.owner.submit { rt.session.stateWithoutDiscovery() }.join()
                val lcdcDiag = rt.owner.submit {
                    val vglib = rt.host.components().single { it.descriptor.id == "soc-vglib-init" }.component.state()
                    "scanout=${vglib["firstScanout"]} frames=${vglib["publishedFrames"]} active=${vglib["outputActive"]} blank=${vglib["blankReason"]}"
                }.join()
                println("[R2][$label][$phaseLabel] DONE status=${summary["status"]} frontier=${summary["frontier"]} lcdc1=($lcdcDiag) frames=$framesInPhase framesAfter296=$framesAfter296 ${formatDiag(finalDiag)}")
                return lastImg
            }

            println("[R2][$label] INITIAL ${formatDiag(probeGuestDiag(rt))}")
            // 1. Flip lever MOVIE -> STILL (movieMode=false) and wait until Still HUD fully settles (ev=0, prevScr=0)
            patchButton("movieMode", false)
            val stillHudImg = stepPhase(170_000_000L, "TO-STILL") { d, s296, _, post296 ->
                s296 && post296 >= 2 && (d["scr"] as Long) == 0x0100L && (d["prevScr"] as Long) == 0L &&
                    (d["evtFlags"] as Long) == 0L && (d["bDscModeChgFlg"] as Int) == 0 &&
                    (d["reccfM0"] as Int) == 1 && (d["reccfM2"] as Int) == 0
            }
            val stillDiag = probeGuestDiag(rt)
            println("[R2][$label] STILL-HUD ${formatDiag(stillDiag)}")
            assertEquals(0x0100L, stillDiag["scr"], "Expected Still Live View (0x0100), got ${formatDiag(stillDiag)}")
            assertEquals(0, stillDiag["activeErrno"], "Still mode must not carry errno 0x0095 (${formatDiag(stillDiag)})")
            assertEquals(0, stillDiag["bDscModeChgFlg"], "Still mode must clear bDscModeChgFlg=0 (${formatDiag(stillDiag)})")
            assertEquals(1, stillDiag["reccfM0"], "Expected reccf M0=1 in Still mode (${formatDiag(stillDiag)})")
            assertEquals(0, stillDiag["reccfM2"], "Expected reccf M2=0 in Still mode (${formatDiag(stillDiag)})")
            assertNotNull(stillHudImg, "Expected rendered Still Live View HUD frame")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                ImageIO.write(stillHudImg, "png", dir.resolve("$label-still-hud.png").toFile())
            }
            // On an uninitialized blank card (0x01CBFCA3 == 0x12), GFX100S 2.13 raises a 3000 ms modal
            // "CARD NOT INITIALIZED" banner (table entry 0x01CBFD4C = [3000, 257, 0]) that intentionally
            // gates MENU/OK during the 3s warning window; the blank-card contract is clearing bDscModeChgFlg=0
            // and reaching Still Live View (0x0100) with zero errno 0x0095.
            if (label == "blank") {
                return
            }

            // 2. Press MENU/OK (3.0M attempts ~= 22 ADC scans) in Still mode -> verify Still-mode UI_MODE_SETUP_REC (0x0400..0x04FF)
            patchButton("menu", true)
            stepPhase(3_000_000L, "MENU-DOWN", autoDriveIrqs = true)
            patchButton("menu", false)
            var lastMenuFrame: BufferedImage? = null
            fun hasMenuContent(img: BufferedImage?): Boolean {
                if (img == null) return false
                val c0 = img.getRGB(0, 0)
                var varied = 0
                for (y in 0 until img.height step 4) {
                    for (x in 0 until img.width step 4) {
                        if (img.getRGB(x, y) != c0) varied++
                    }
                }
                return varied > 1000
            }
            val menuImg = stepPhase(60_000_000L, "MENU-OPEN", autoDriveIrqs = true) { d, _, frames, _ ->
                (d["scr"] as Long) in 0x0400L..0x04FFL && (d["bDscModeChgFlg"] as Int) == 0 && frames >= 1
            }
            lastMenuFrame = menuImg
            val menuSettledImg = if (hasMenuContent(lastMenuFrame)) {
                lastMenuFrame
            } else {
                stepPhase(25_000_000L, "MENU-SETTLE", autoDriveIrqs = true) { _, _, frames, _ ->
                    frames >= 2
                } ?: lastMenuFrame
            }
            val menuDiag = probeGuestDiag(rt)
            println("[R2][$label] AFTER-MENU ${formatDiag(menuDiag)}")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                menuSettledImg?.let { ImageIO.write(it, "png", dir.resolve("$label-still-menu-rec.png").toFile()) }
            }
            val menuScr = menuDiag["scr"] as Long
            assertTrue(menuScr in 0x0400L..0x04FFL, "Expected Still-mode UI_MODE_SETUP_REC screen (0x0400..0x04FF), got ${formatDiag(menuDiag)}")
            assertEquals(0, menuDiag["activeErrno"], "MENU in Still mode must not trigger errno 0x0095")
            assertEquals(0, menuDiag["bDscModeChgFlg"], "MENU in Still mode must clear bDscModeChgFlg=0")
            assertTrue(((menuDiag["ch21Done"] as Long) and 0x800L) != 0L, "Expected ch21 bit 0x800 (0x914F) completed during Still MENU transition (${formatDiag(menuDiag)})")
            assertNotNull(menuSettledImg, "Expected rendered Still-mode menu frame")
            assertTrue(hasMenuContent(menuSettledImg), "Expected non-blank rendered Still-mode menu frame")

            // 3. Navigate DOWN inside Still-mode menu
            patchButton("down", true)
            stepPhase(3_000_000L, "DOWN-HOLD", autoDriveIrqs = true)
            patchButton("down", false)
            val navImg = stepPhase(20_000_000L, "DOWN-NAV", autoDriveIrqs = true) { d, _, frames, _ ->
                (d["scr"] as Long) in 0x0400L..0x04FFL && frames >= 2
            }
            val navDiag = probeGuestDiag(rt)
            println("[R2][$label] AFTER-DOWN ${formatDiag(navDiag)}")
            exportDir?.let { dir ->
                Files.createDirectories(dir)
                navImg?.let { ImageIO.write(it, "png", dir.resolve("$label-still-menu-nav.png").toFile()) }
            }
            val navScr = navDiag["scr"] as Long
            assertTrue(navScr in 0x0400L..0x04FFL, "Expected to remain in Still-mode menu (0x0400..0x04FF) after DOWN, got ${formatDiag(navDiag)}")
            assertEquals(0, navDiag["activeErrno"], "DOWN navigation in Still menu must not trigger errno")
            assertNotNull(navImg, "Expected rendered frame after DOWN in Still menu")
            assertTrue(hasMenuContent(navImg), "Expected non-blank rendered Still-mode menu frame after DOWN")
            val navDiff = countDiffPixels(menuSettledImg, navImg)
            println("[R2][$label] MENU-NAV diffPixels=$navDiff")
            assertTrue(navDiff > 500, "Expected DOWN navigation to visibly move highlight in Still menu (diffPixels=$navDiff)")

            // 4. Press BACK (up to 3 levels if snapshot remembered a nested submenu) -> return cleanly to Still Live View (scr=0x0100)
            var backImg: BufferedImage? = null
            var backDiag = navDiag
            for (attempt in 1..3) {
                if ((backDiag["scr"] as Long) == 0x0100L && (backDiag["bDscModeChgFlg"] as Int) == 0) break
                patchButton("back", true)
                stepPhase(3_000_000L, "BACK-HOLD-$attempt", autoDriveIrqs = true)
                patchButton("back", false)
                val prevBackScr = backDiag["scr"] as Long
                backImg = stepPhase(60_000_000L, "BACK-TO-STILL-$attempt", autoDriveIrqs = true) { d, _, frames, _ ->
                    val s = d["scr"] as Long
                    (s == 0x0100L && (d["bDscModeChgFlg"] as Int) == 0 && frames >= 1) ||
                        (s in 0x0400L..0x04FFL && s != prevBackScr && frames >= 1)
                }
                backDiag = probeGuestDiag(rt)
                println("[R2][$label] AFTER-BACK-$attempt ${formatDiag(backDiag)}")
            }
            assertEquals(0x0100L, backDiag["scr"], "Expected BACK from Still menu to return to Live View (0x0100), got ${formatDiag(backDiag)}")
            assertEquals(0, backDiag["activeErrno"], "BACK from Still menu must not trigger errno")
            assertEquals(0, backDiag["bDscModeChgFlg"], "BACK from Still menu must clear bDscModeChgFlg=0")
            assertNotNull(backImg, "Expected rendered Still Live View frame after BACK")
        }
    }

    @Test
    fun spi0LenscomAndSpi2BiscomDmaFifoContractsLoadCleanlyInHost(@TempDir tempRoot: Path) {
        val home = E2eHarness.stageComponentHome(tempRoot.resolve("home"))
        val mountDir = home.resolve("compoments/mount")
        Files.createDirectories(mountDir)
        Formats.atomicYaml(
            mountDir.resolve("mount.yaml"),
            mapOf("attached" to true, "lensPort" to "lens", "protocolProfile" to "gf-lenscom-frame32-experimental-v1"),
        )
        val spiDir = home.resolve("compoments/soc-spi")
        Files.createDirectories(spiDir)
        Formats.atomicYaml(
            spiDir.resolve("soc-spi.yaml"),
            mapOf("profile" to "spi-fifo-no-peer-experimental-v1", "peerPort" to "", "portsFile" to "ports.json"),
        )
        Formats.writeJson(
            spiDir.resolve("ports.json"),
            mapOf(
                "profile" to "spi-additional-ports-experimental-v1",
                "controllers" to listOf(
                    mapOf("index" to 0, "peerPort" to "mount.spi", "requestEnable3Limit" to 16384),
                    mapOf("index" to 2, "peerPort" to "soc-spi.biscom", "requestEnable3Limit" to 16384),
                ),
            ),
        )
        val dmaDir = home.resolve("compoments/soc-dma")
        Files.createDirectories(dmaDir)
        Formats.atomicYaml(
            dmaDir.resolve("soc-dma.yaml"),
            mapOf(
                "profile" to "standalone-experimental-v1",
                "spi0DmaProfile" to "spi0-four-byte-fifo-v1",
                "spi2DmaProfile" to "spi2-four-byte-fifo-v1",
            ),
        )
        com.github.nyabanban.fuji.emulator.runtime.OwnerLoop().use { owner ->
            val host = com.github.nyabanban.fuji.emulator.runtime.ComponentHost(owner, home.resolve("compoments"))
            try {
                owner.submit { host.load() }.join()
                owner.submit {
                    val spi0 = checkNotNull(host.ports.find("soc-spi.spi0", MmioDevice::class.java) as? ScheduledMmioDevice)
                    val spi0Dma = checkNotNull(host.ports.find("soc-spi.spi0.dma", SpiDmaFifo::class.java))
                    val spi2Dma = checkNotNull(host.ports.find("soc-spi.spi2.dma", SpiDmaFifo::class.java))
                    assertNotNull(spi2Dma)

                    // Configure SPI0 in 8-bit DMA master mode and verify 2-phase Frame32 exchange against mount.spi
                    spi0.write(0x24L, 4, 3L)
                    spi0.write(0L, 4, 0x00c7L)
                    spi0.write(0x10L, 4, 36L)
                    spi0.write(4L, 4, 0x0aL)
                    assertEquals(8, spi0Dma.writableBytes())

                    fun checksum(w: Long): Long =
                        (((w ushr 27) and 0x1fL) + ((w ushr 22) and 0x1fL) + ((w ushr 17) and 0x1fL) +
                            ((w ushr 12) and 0x1fL) + ((w ushr 7) and 0x1fL) + ((w ushr 6) and 1L)) and 0x1fL
                    fun frame(cmdId: Long, ft: Long, upper: Long = 0L): Long {
                        val b = ((upper and 0x1fL) shl 27) or ((ft and 1L) shl 15) or ((cmdId and 0x1ffL) shl 6)
                        return b or (checksum(b) shl 1)
                    }
                    var tick = 0L
                    fun xferDma32(word: Long): Long {
                        val txBuf = ByteBuffer.allocate(4).putInt(word.toInt()).apply { flip() }
                        spi0Dma.write(txBuf)
                        while (spi0.nextDeadline() != null) {
                            tick = spi0.nextDeadline()!!
                            spi0.beforeInstruction(tick)
                        }
                        assertEquals(4, spi0Dma.readableBytes())
                        val rxBuf = ByteBuffer.allocate(4)
                        spi0Dma.read(rxBuf)
                        rxBuf.flip()
                        return rxBuf.int.toLong() and 0xffffffffL
                    }

                    assertEquals(0L, xferDma32(frame(0x10L, 0L)))
                    val ack = xferDma32(frame(0x10L, 1L, upper = 1L))
                    assertEquals(0x10L, (ack ushr 6) and 0x1ffL)
                    assertEquals(1L, (ack ushr 15) and 1L)
                    val data = xferDma32(0xA55A3CC3L)
                    assertEquals(0x10L, (data ushr 6) and 0x1ffL)
                    assertEquals(0L, (data ushr 15) and 1L)
                    assertEquals(0x0100L, (data ushr 16) and 0xffffL)
                }.join()
            } finally {
                owner.submit { host.close() }.join()
            }
        }
    }

    @Test
    fun livePhotoAndBlankStillModeOpenUiLibMenuRecWithZeroErrno0095(@TempDir tempRoot: Path) {
        assumeTrue(System.getenv("FUJI_M1_LIVE") == "1", "Set FUJI_M1_LIVE=1 to run live Still-mode UI_LIB_MENU_REC E2E")
        val exportDir = System.getenv("FUJI_M1_EXPORT_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val photoRoot = System.getenv("FUJI_M1_PHOTO_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        val blankRoot = System.getenv("FUJI_M1_BLANK_ROOT")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
        assumeTrue(photoRoot != null || blankRoot != null, "Set FUJI_M1_PHOTO_ROOT and/or FUJI_M1_BLANK_ROOT")

        photoRoot?.let { runStillModeMenuCheck(it, tempRoot, "photo", exportDir) }
        blankRoot?.let { runStillModeMenuCheck(it, tempRoot, "blank", exportDir) }
    }
}
