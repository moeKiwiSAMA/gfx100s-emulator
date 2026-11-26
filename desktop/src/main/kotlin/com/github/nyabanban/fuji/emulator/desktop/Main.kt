package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.api.DisplayPort
import com.github.nyabanban.fuji.emulator.api.GpioInputPort
import com.github.nyabanban.fuji.emulator.desktop.audio.AudioDeviceCatalog
import com.github.nyabanban.fuji.emulator.desktop.audio.AudioPlaybackConfig
import com.github.nyabanban.fuji.emulator.desktop.audio.HostAudioPlayback
import com.github.nyabanban.fuji.emulator.desktop.console.EmulatorConsole
import com.github.nyabanban.fuji.emulator.desktop.console.RuntimeConsoleBackend
import com.github.nyabanban.fuji.emulator.desktop.i18n.I18n
import com.github.nyabanban.fuji.emulator.desktop.panels.AsyncDisplayBridge
import com.github.nyabanban.fuji.emulator.desktop.panels.ViewfinderStatus
import com.github.nyabanban.fuji.emulator.desktop.theme.ThemeRuntimeController
import com.github.nyabanban.fuji.emulator.desktop.theme.UserProfileStore
import com.github.nyabanban.fuji.emulator.firmware.*
import com.github.nyabanban.fuji.emulator.runtime.*
import javafx.application.Application
import javafx.application.Platform
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class EmulatorRuntime @JvmOverloads constructor(
    val home: Path,
    val config: Map<String, Any>,
    private val profilerAutoStart: Boolean = java.lang.Boolean.getBoolean("fuji.profiler.enabled"),
    private val profilerIntervalMs: Long = System.getProperty("fuji.profiler.intervalMs")?.toLongOrNull() ?: AsyncPerfProfiler.DEFAULT_SAMPLE_INTERVAL_MS,
    private val telemetryDisabled: Boolean = java.lang.Boolean.getBoolean("fuji.telemetry.disabled"),
    private val telemetryUploadRequested: Boolean = java.lang.Boolean.getBoolean("fuji.telemetry.upload"),
    private val telemetryEnv: Map<String, String> = System.getenv(),
    private val realTimePacerCliEnabled: Boolean = java.lang.Boolean.getBoolean("fuji.realtime.pace"),
    private val audioEnabledCli: Boolean = java.lang.Boolean.getBoolean("fuji.audio.enabled"),
    private val audioVolumeCli: Double? = System.getProperty("fuji.audio.volume")?.toDoubleOrNull(),
    private val startupProgress: Boolean = false,
    private val userProfilePath: Path? = null,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EmulatorRuntime::class.java)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private data class ResolvedBootPlan(
        val effectiveConfig: Map<String, Any>,
        val componentOverrides: Map<String, Map<String, Any>>,
        val activeNorPath: Path?,
        val bootPath: String,
        val bootRomPresent: Boolean,
    )
    private val bootPlan: ResolvedBootPlan = resolveBootPlan()
    private fun resolveBootPlan(): ResolvedBootPlan {
        val relaxed = CatalogRuntime.mode(config) == RelaxedPeripheralFallback.MODE
        val hasFirmwareDat = (config["firmwareFile"] as? String)?.isNotBlank() == true
        val norRel = (config["norFlashFile"] as? String)?.takeIf { it.isNotBlank() }
        if (!relaxed || hasFirmwareDat || norRel == null) {
            return ResolvedBootPlan(
                effectiveConfig = config,
                componentOverrides = emptyMap(),
                activeNorPath = if (!hasFirmwareDat) NorFlashTool.resolveNorFlashPath(home, config) else null,
                bootPath = if (!hasFirmwareDat && norRel != null) "flash-direct" else "legacy-dat",
                bootRomPresent = false,
            )
        }
        NorFlashTool.ensureLiveNorExists(home, config)
        val liveNor = requireNotNull(NorFlashTool.resolveNorFlashPath(home, config))
        if (!java.nio.file.Files.isRegularFile(liveNor)) {
            FirmwareGuidance.tryAutoSeedNorFromDroppedDat(home, config, liveNor, log)
        }
        if (!java.nio.file.Files.isRegularFile(liveNor)) {
            val factoryRel = (config["factoryFlashFile"] as? String)?.takeIf { it.isNotBlank() } ?: "none"
            val detail = "Missing 64 MiB QSPI NOR Flash image at '$liveNor' (and factory template '$factoryRel' is not bundled). " +
                "See flash/PUT_HERE for setup instructions, or flash an official/custom FUJIFILM .DAT firmware package with: " +
                "--flash-dat <path/to/GXUP0004.DAT> (or place a valid 64 MiB raw NOR image at $norRel)."
            throw MissingFirmwareException(
                FirmwareGuidance.buildIssue(
                    kind = FirmwareGuidance.Kind.MISSING_NOR_FLASH,
                    home = home,
                    config = config,
                    explicitTarget = liveNor,
                    detailMessage = detail,
                ),
            )
        }
        val bootRomRel = (config["bootRomFile"] as? String)?.takeIf { it.isNotBlank() }
        val bootRomOptIn = config[BOOT_ROM_BOOT_KEY] == true
        if (bootRomRel == null || !bootRomOptIn) {
            val present = bootRomRel != null && java.nio.file.Files.isRegularFile(home.resolve(bootRomRel))
            return ResolvedBootPlan(
                effectiveConfig = config,
                componentOverrides = emptyMap(),
                activeNorPath = liveNor,
                bootPath = "flash-direct",
                bootRomPresent = present,
            )
        }
        val bootRomPath = home.resolve(bootRomRel).toAbsolutePath().normalize()
        val bootRomReady = java.nio.file.Files.isRegularFile(bootRomPath) &&
            runCatching { java.nio.file.Files.size(bootRomPath) == 32_768L }.getOrDefault(false)
        if (!bootRomReady) {
            log.warn(
                "[FALLBACK][bootrom] {} not found; falling back to Flash-Direct boot from {}[0x20000..0x3FFFF]",
                bootRomRel,
                norRel,
            )
            return ResolvedBootPlan(
                effectiveConfig = config,
                componentOverrides = emptyMap(),
                activeNorPath = liveNor,
                bootPath = "flash-direct",
                bootRomPresent = false,
            )
        }
        val baseMetaRel = config["firmwareMetadata"] as? String
        val bootRomMetaRel = baseMetaRel?.let { rel ->
            val p = Path.of(rel)
            val parent = p.parent
            if (parent != null) parent.resolve("gfx100s-213-bootrom-release.yaml").toString().replace('\\', '/')
            else "gfx100s-213-bootrom-release.yaml"
        }
        val bootRomMetaPath = bootRomMetaRel?.let { home.resolve(it).toAbsolutePath().normalize() }
        val cfgCompRoot = (config["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }?.let { home.resolve(it) }
            ?: (config["components"] as? String)?.let { home.resolve(it) }
        val bootStrapExists = cfgCompRoot?.resolve("soc-system-control/boot-strap.json")
            ?.let { java.nio.file.Files.isRegularFile(it) } == true
        if (bootRomMetaRel != null && bootRomMetaPath != null && java.nio.file.Files.isRegularFile(bootRomMetaPath) && bootStrapExists) {
            val effective = LinkedHashMap<String, Any>(config)
            effective.remove(BOOT_ROM_BOOT_KEY)
            effective["firmwareMetadata"] = bootRomMetaRel
            effective.remove("clusterReset")
            effective.remove("flashPartitionScope")
            effective.remove("flashReadPairScope")
            effective.remove("flashWritableSetScope")
            @Suppress("UNCHECKED_CAST")
            val disc = LinkedHashMap<String, Any>((config["discovery"] as? Map<String, Any>).orEmpty())
            disc["bootRomColdStart"] = "bootrom-release-coldstart-v4"
            effective["discovery"] = disc
            val overrides = mapOf<String, Map<String, Any>>(
                "system-memory" to mapOf("profile" to "boot-rom-full-boot-v1"),
                "soc-dram" to mapOf("coldStartChannel0" to true),
                "soc-system-control" to mapOf("bootStrapInputFile" to "boot-strap.json"),
            )
            log.info(
                "[BOOT-ROM] Detected 32 KiB mask BootROM at {}; booting via full BootROM (PC=0xFFF00000) -> {}",
                bootRomRel,
                norRel,
            )
            return ResolvedBootPlan(
                effectiveConfig = effective,
                componentOverrides = overrides,
                activeNorPath = liveNor,
                bootPath = "bootrom-nor",
                bootRomPresent = true,
            )
        }
        log.warn(
            "[FALLBACK][bootrom] {} present but BootROM metadata/strap unavailable; falling back to Flash-Direct boot from {}[0x20000..0x3FFFF]",
            bootRomRel,
            norRel,
        )
        return ResolvedBootPlan(
            effectiveConfig = config,
            componentOverrides = emptyMap(),
            activeNorPath = liveNor,
            bootPath = "flash-direct",
            bootRomPresent = true,
        )
    }
    val themeController = ThemeRuntimeController(
        home = home,
        profilePath = userProfilePath ?: UserProfileStore.defaultPath(home),
    ).also { controller ->
        I18n.onLocaleChanged = { code -> controller.persistLanguage(code) }
    }
    val catalog = run {
        if (startupProgress) {
            log.info("[1/3] Loading address catalog & runtime persistence...")
        }
        CatalogRuntime.load(home, bootPlan.effectiveConfig)
    }
    private val osSchedulerReady = NativeOsScheduler.initialize(home)
    val owner = OwnerLoop()
    val host = ComponentHost(
        owner,
        home.resolve(bootPlan.effectiveConfig.getValue("components") as String),
        catalog.catalog,
        configRoot = (bootPlan.effectiveConfig["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }?.let { home.resolve(it) },
        relaxedMode = catalog.mode == RelaxedPeripheralFallback.MODE,
        norFlashPath = bootPlan.activeNorPath,
        configOverrides = bootPlan.componentOverrides,
    )
    val telemetry = FirmwareTelemetry()
    val telemetryPublisher: TelemetrySessionPublisher? = resolveTelemetryPublisher()
    val displayBridge = AsyncDisplayBridge()
    val audioPlayback = HostAudioPlayback(
        portsProvider = { host.ports },
        initialConfig = AudioPlaybackConfig.resolve(bootPlan.effectiveConfig, cliEnabled = audioEnabledCli, cliVolume = audioVolumeCli),
        deviceCatalog = AudioDeviceCatalog.defaultCatalog(home),
    )
    val session = FirmwareSession(
        host = host,
        home = home,
        config = bootPlan.effectiveConfig,
        catalogRuntime = catalog,
        telemetry = telemetry,
        telemetryPublisher = telemetryPublisher,
        onSubMcuColdReset = if (catalog.mode == RelaxedPeripheralFallback.MODE) ::performSubMcuColdResetOnOwner else null,
    )
    val profiler = AsyncPerfProfiler(
        telemetry = telemetry,
        ownerThreadProvider = { owner.ownerThread },
        defaultSampleIntervalMs = profilerIntervalMs.coerceIn(AsyncPerfProfiler.MIN_SAMPLE_INTERVAL_MS, AsyncPerfProfiler.MAX_SAMPLE_INTERVAL_MS),
        pageNameResolver = { pageBase -> session.resolveMmioPageLabel(pageBase) },
    )
    val watchdog = FirmwareWatchdog(
        telemetry,
        ownerThreadProvider = { owner.ownerThread },
        summaryProvider = { session.relaxedFeedbackSummaryReport() },
        telemetryPublisher = telemetryPublisher,
    )
    private val userActionLogger = UserActionLogger(
        enabled = catalog.mode == RelaxedPeripheralFallback.MODE,
        telemetryPublisher = telemetryPublisher,
    )
    val realTimePacer: RealTimePacer? = run {
        if (catalog.mode != RelaxedPeripheralFallback.MODE) return@run null
        val raw = config["realTimePacer"]
        val enabled = when (raw) {
            is Boolean -> raw
            is Map<*, *> -> raw["enabled"] != false
            else -> realTimePacerCliEnabled
        }
        if (!enabled) return@run null
        val map = raw as? Map<*, *>
        val ratio = (map?.get("targetRatio") as? Number)?.toDouble() ?: 1.0
        val tps = (map?.get("ticksPerRealSecond") as? Number)?.toDouble() ?: RealTimePacer.DEFAULT_TICKS_PER_SECOND
        RealTimePacer(ticksPerRealSecond = tps, targetRatio = ratio)
    }
    private val buttonEventSub = host.events.subscribe { event ->
        if (event.kind == "configuration") {
            val changedKeys = (event.values["keys"] as? List<*>).orEmpty().mapNotNull { it?.toString() }
            val currentConfig = host.components().singleOrNull { it.descriptor.id == event.source }?.config.orEmpty()
            val changedValues = linkedMapOf<String, Any?>()
            for (key in changedKeys) {
                if (currentConfig.containsKey(key)) {
                    changedValues[key] = currentConfig[key]
                }
            }
            if (event.source == "buttons") {
                session.observeButtonEventWhilePoweredOff(changedValues)
                val description = if (changedValues.isNotEmpty()) {
                    changedValues.entries.joinToString(",") { "${it.key}=${it.value}" }
                } else {
                    event.values["keys"]?.toString() ?: "buttons"
                }
                telemetry.recordButtonEvent(description)
            }
            userActionLogger.logComponentPatch(event.source, changedValues, telemetry)
        }
    }
    var server: ControlServer? = null
        private set
    @Volatile
    private var hostReloadGeneration: Long = 0L
    init {
        try { owner.submit {
            try {
                if (catalog.mode == RelaxedPeripheralFallback.MODE) {
                    RelaxedFlashPersistence.prepare(home, config)
                }
                if (startupProgress) {
                    log.info("[2/3] Loading hardware components...")
                }
                val loadStartNanos = System.nanoTime()
                host.load()
                if (startupProgress) {
                    val elapsedMs = (System.nanoTime() - loadStartNanos) / 1_000_000L
                    log.info("Loaded {} hardware components in {} ms", host.components().size, elapsedMs)
                }
                audioPlayback.bindPorts(host.ports)
                catalog.bindLogicalPorts(host.logicalPortBindings(),host.logicalComponentProfiles())
                catalog.bindCandidateInputs(host.candidateInputBindings())
            } catch(error:Throwable) { host.close();throw error }
        }.join()
            audioPlayback.startIfEnabled()
        }
        catch (error: Throwable) { audioPlayback.close(); buttonEventSub.close(); profiler.close(); watchdog.close(); telemetryPublisher?.close(); owner.close(); throw error }
    }
    private fun performSubMcuColdResetOnOwner() {
        owner.requireOwner()
        // Capture coin-cell-backed Sub-MCU state (RTC time/control + power-peer NVRAM selectors 0x1F..0x22
        // holding bDatetimeSet/bLanguageSet/bPerformanceSet) before closing components so main-SoC
        // sleep/wake preserves monotonic wall time and OOBE/clock completion flags across cold resets.
        val rtcBefore = host.components().singleOrNull { it.descriptor.id == "rtc" }?.component?.state()
        val rtcRegsBefore = rtcBefore?.get("registers") as? Map<*, *>
        val rtcSeconds = (rtcRegsBefore?.get("seconds") as? Number)?.toLong()
        val rtcControl = (rtcRegsBefore?.get("control") as? Number)?.toInt()
        val rtcRunning = rtcRegsBefore?.get("counterRunning") as? Boolean
        val sleepWallSeconds = session.consumeLastSocPoweredOffWallSeconds()
        val buttonsBefore = host.components().singleOrNull { it.descriptor.id == "buttons" }?.config
        val latchingButtonsBefore = LinkedHashMap<String, Any>()
        if (buttonsBefore != null) {
            for (key in com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog.latchingButtonKeys) {
                val v = buttonsBefore[key]
                if (v is Boolean) latchingButtonsBefore[key] = v
            }
        }
        val subMcuNvramBefore = LinkedHashMap<String, Int>()
        val powerPeerStoredBefore = host.components().singleOrNull { it.descriptor.id == "power-peer" }
            ?.component?.state()?.get("stored") as? Map<*, *>
        if (powerPeerStoredBefore != null) {
            for (sel in listOf("31", "32", "33", "34")) {
                val raw = powerPeerStoredBefore[sel] ?: powerPeerStoredBefore[sel.toInt()]
                if (raw is Number) subMcuNvramBefore[sel] = raw.toInt()
            }
        }
        host.close()
        host.resetClockForColdReset()
        RelaxedFlashPersistence.syncMutableManifest(home, config)
        host.load()
        // Carry physical latching switches (power, movieMode) across Sub-MCU cold reset and clear
        // any stale momentary button that may have been persisted to buttons.yaml before reset.
        val buttonsLoaded = host.components().singleOrNull { it.descriptor.id == "buttons" }
        if (buttonsLoaded != null) {
            val sanitized = LinkedHashMap<String, Any>()
            for ((key, value) in buttonsLoaded.config) {
                if (key in com.github.nyabanban.fuji.emulator.desktop.panels.PanelCatalog.latchingButtonKeys) {
                    val liveVal = latchingButtonsBefore[key]
                    if (liveVal != null && liveVal != value) sanitized[key] = liveVal
                } else if (value == true) {
                    sanitized[key] = false
                }
            }
            if (sanitized.isNotEmpty()) {
                runCatching { host.patchLatestDirect("buttons", sanitized, persistToDisk = false) }
            }
        }
        if (rtcRegsBefore != null) {
            val rtcComp = host.components().singleOrNull { it.descriptor.id == "rtc" }?.component as? com.github.nyabanban.fuji.emulator.api.StatefulComponent
            if (rtcComp != null) {
                runCatching {
                    val freshSnap = rtcComp.saveSnapshot()
                    val bank = freshSnap["bank"] as? Map<*, *>
                    if (bank != null) {
                        val updatedBank = LinkedHashMap<String, Any?>()
                        for ((k, v) in bank) updatedBank[k.toString()] = v
                        if (rtcSeconds != null) {
                            updatedBank["baseSeconds"] = (rtcSeconds + sleepWallSeconds) and 0xffffffffL
                        }
                        if (rtcControl != null) {
                            updatedBank["control"] = rtcControl
                        }
                        if (rtcRunning != null) {
                            updatedBank["counterRunning"] = rtcRunning
                        }
                        val updatedSnap = LinkedHashMap<String, Any?>(freshSnap)
                        updatedSnap["bank"] = updatedBank
                        rtcComp.restoreSnapshot(updatedSnap)
                    }
                }.onFailure { err ->
                    log.warn("[SUB-MCU] Could not carry RTC state across cold reset: {}", err.message)
                }
            }
        }
        if (subMcuNvramBefore.isNotEmpty()) {
            val peerComp = host.components().singleOrNull { it.descriptor.id == "power-peer" }?.component as? com.github.nyabanban.fuji.emulator.api.StatefulComponent
            if (peerComp != null) {
                runCatching {
                    val freshSnap = peerComp.saveSnapshot()
                    val stored = freshSnap["stored"] as? Map<*, *>
                    if (stored != null) {
                        val updatedStored = LinkedHashMap<String, Any?>()
                        for ((k, v) in stored) updatedStored[k.toString()] = v
                        for ((sel, value) in subMcuNvramBefore) {
                            if (updatedStored.containsKey(sel)) updatedStored[sel] = value
                        }
                        val updatedSnap = LinkedHashMap<String, Any?>(freshSnap)
                        updatedSnap["stored"] = updatedStored
                        peerComp.restoreSnapshot(updatedSnap)
                    }
                }.onFailure { err ->
                    log.warn("[SUB-MCU] Could not carry Sub-MCU NVRAM selectors 0x1F..0x22 across cold reset: {}", err.message)
                }
            }
        }
        audioPlayback.bindPorts(host.ports)
        catalog.resetForColdReset()
        catalog.bindLogicalPorts(host.logicalPortBindings(), host.logicalComponentProfiles())
        catalog.bindCandidateInputs(host.candidateInputBindings())
        displayBridge.resetForColdReset()
        hostReloadGeneration++
        watchdog.resetForColdReset()
        realTimePacer?.reanchor(0L)
    }
    fun norFlashStatus(): Map<String, Any?> = owner.submit {
        val livePath = NorFlashTool.resolveNorFlashPath(home, config)
        val factoryPath = NorFlashTool.resolveFactoryFlashPath(home, config)
        val flashComp = host.components().singleOrNull { it.descriptor.id == "flash-image" }?.component?.state()
        val socFlash = host.components().singleOrNull { it.descriptor.id == "soc-flash" }?.component?.state()
        val explicitSha = host.ports.find("flash.image", com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort::class.java)
            ?.let { port -> runCatching { port.sha256 }.getOrNull() }
            ?: flashComp?.get("sha256")
        linkedMapOf<String, Any?>(
            "unifiedNorMode" to (livePath != null || flashComp?.containsKey("unifiedNor") == true),
            "norFlashFile" to (livePath?.toString() ?: (flashComp?.get("unifiedNor") as? Map<*, *>)?.get("path")),
            "factoryFlashFile" to factoryPath?.toString(),
            "factoryAvailable" to (factoryPath != null && java.nio.file.Files.isRegularFile(factoryPath)),
            "bootRomFile" to (config["bootRomFile"] as? String),
            "bootRomPresent" to bootPlan.bootRomPresent,
            "bootPath" to bootPlan.bootPath,
            "loaded" to (flashComp?.get("loaded") ?: false),
            "length" to (flashComp?.get("length") ?: 0L),
            "sha256" to explicitSha,
            "writable" to (flashComp?.get("writable") ?: false),
            "eraseCommitted" to ((socFlash?.get("erase") as? Map<*, *>)?.get("committed") ?: 0L),
            "programCommitted" to ((socFlash?.get("program") as? Map<*, *>)?.get("committed") ?: 0L),
        )
    }.join()

    private fun requireLiveNorPath(): Path =
        NorFlashTool.resolveNorFlashPath(home, config)
            ?: throw IllegalStateException("norFlashFile is not configured in emulator.yaml")

    private fun reloadHostAfterFlashMutationOnOwner(mutation: (Path) -> Unit) {
        owner.requireOwner()
        val livePath = requireLiveNorPath()
        session.coldResetWithHostMutation {
            host.close()
            host.resetClockForColdReset()
            mutation(livePath)
            RelaxedFlashPersistence.syncMutableManifest(home, config)
            host.load()
            audioPlayback.bindPorts(host.ports)
            catalog.resetForColdReset()
            catalog.bindLogicalPorts(host.logicalPortBindings(), host.logicalComponentProfiles())
            catalog.bindCandidateInputs(host.candidateInputBindings())
            displayBridge.resetForColdReset()
            hostReloadGeneration++
            watchdog.resetForColdReset()
            realTimePacer?.reanchor(0L)
        }
    }

    fun flashDatIntoNorLive(datPath: Path, preserveCalibration: Boolean = true): NorFlashTool.FlashResult =
        owner.submit {
            var result: NorFlashTool.FlashResult? = null
            reloadHostAfterFlashMutationOnOwner { livePath ->
                result = NorFlashTool.flashDatIntoNor(datPath, livePath, preserveCalibration = preserveCalibration)
            }
            checkNotNull(result)
        }.join()

    fun exportNorFlashLive(destinationPath: Path): NorFlashTool.NorInspect =
        owner.submit {
            val livePath = requireLiveNorPath()
            NorFlashTool.exportNor(livePath, destinationPath)
        }.join()

    fun importNorFlashLive(sourcePath: Path): NorFlashTool.NorInspect =
        owner.submit {
            var result: NorFlashTool.NorInspect? = null
            reloadHostAfterFlashMutationOnOwner { livePath ->
                result = NorFlashTool.importNor(sourcePath, livePath)
            }
            checkNotNull(result)
        }.join()

    fun restoreFactoryFlashLive(): NorFlashTool.NorInspect =
        owner.submit {
            val factoryPath = NorFlashTool.resolveFactoryFlashPath(home, config)
                ?: throw IllegalStateException("factoryFlashFile is not configured in emulator.yaml")
            var result: NorFlashTool.NorInspect? = null
            reloadHostAfterFlashMutationOnOwner { livePath ->
                result = NorFlashTool.restoreFactoryNor(factoryPath, livePath)
                val compDir = (config["components"] as? String)?.let { home.resolve(it) }
                compDir?.resolve("power-peer/submcu-nvram.json")?.let { nvram ->
                    runCatching { java.nio.file.Files.deleteIfExists(nvram) }
                }
                compDir?.resolve("power-peer/submcu-nvram.bin")?.let { nvram ->
                    runCatching { java.nio.file.Files.deleteIfExists(nvram) }
                }
            }
            checkNotNull(result)
        }.join()

    private fun resolveTelemetryPublisher(): TelemetrySessionPublisher? = runCatching {
        val firmwareId = runCatching {
            val metaPath = config["firmwareMetadata"] as? String ?: return@runCatching null
            FirmwareMetadata(home.resolve(metaPath)).id
        }.getOrNull()
        val telemetryConfig = TelemetryHubClient.resolveConfig(
            executionMode = catalog.mode,
            config = config,
            cliDisable = telemetryDisabled,
            cliEnableUpload = telemetryUploadRequested,
            env = telemetryEnv,
            firmwareId = firmwareId,
        ) ?: return@runCatching null
        TelemetrySessionPublisher(
            config = telemetryConfig,
            logFile = home.resolve("logs").resolve(LogArchiver.LATEST_LOG_FILE),
        )
    }.getOrNull()
    @Volatile private var viewModePressed: Boolean = false
    fun setViewModeButtonAsync(pressed: Boolean): java.util.concurrent.CompletableFuture<Map<String, Any?>> =
        owner.submit { setViewModeButtonOnOwner(pressed) }
    private fun setViewModeButtonOnOwner(pressed: Boolean): Map<String, Any?> {
        val gpioInputs = host.ports.find("soc-gpio.inputs", GpioInputPort::class.java)
            ?: throw IllegalStateException("soc-gpio.inputs port unavailable")
        // VIEW MODE button is wired to GPIO Bank 7, Pin 6 (active-LOW).
        gpioInputs.setInputLevel(7, 6, !pressed)
        viewModePressed = pressed
        userActionLogger.logViewMode(pressed, telemetry)
        return mapOf(
            "ok" to true,
            "gpio" to "bank7.pin6",
            "pressed" to pressed,
        )
    }
    private fun eyeSensorStatusOnOwner(): Map<String, Any?> {
        val tmd = host.components().singleOrNull { it.descriptor.id == "tmd2772" && (it.config["enabled"] as? Boolean == true) }
        val target = tmd
            ?: host.components().singleOrNull { it.descriptor.id == "i2c-bus" }
            ?: throw IllegalStateException("i2c-bus component unavailable")
        val near = host.ports.find(com.github.nyabanban.fuji.emulator.api.EyeSensorPort.PORT_NAME, com.github.nyabanban.fuji.emulator.api.EyeSensorPort::class.java)?.near
            ?: (target.component.state()["eyeSensorNear"] as? Boolean)
            ?: (target.config["eyeSensorNear"] as? Boolean)
            ?: false
        val lcdSeq = host.ports.find("display.main", DisplayPort::class.java)?.sequence ?: 0L
        val evfSeq = host.ports.find("display.evf", DisplayPort::class.java)?.sequence ?: 0L
        val vglibState = host.components().singleOrNull { it.descriptor.id == "soc-vglib-init" }?.component?.state()
        val lcdActive = (vglibState?.get("scanout") as? Map<*, *>)?.get("outputActive") as? Boolean
        val evfActive = (vglibState?.get("evfScanoutState") as? Map<*, *>)?.get("outputActive") as? Boolean
        val status = ViewfinderStatus.derive(
            eyeSensorNear = near,
            lcdSequence = lcdSeq,
            evfSequence = evfSeq,
            lcdOutputActive = lcdActive,
            evfOutputActive = evfActive,
            lastUpdatedPort = null,
        )
        return linkedMapOf(
            "ok" to true,
            "component" to target.descriptor.id,
            "near" to near,
            "activeViewfinder" to status.active.name,
            "label" to status.summaryText(),
            "lcdSequence" to lcdSeq,
            "evfSequence" to evfSeq,
            "lcdOutputActive" to lcdActive,
            "evfOutputActive" to evfActive,
            "viewModePressed" to viewModePressed,
        )
    }
    fun serve() {
        server = ControlServer(
            control = host,
            port = (config.getValue("httpPort") as Number).toInt(),
            machine = { owner.submit { session.state() }.join() },
            dump = { dump().fileName.toString() },
            addressQuery = { query -> owner.submit { if(query["unknown"]=="true") session.discoveryState() else catalog.query(query) }.join() },
            profiler = profiler,
            touch = { x, y, pressed ->
                owner.submit {
                    val rearTouch = host.components().singleOrNull { it.descriptor.id == "rear-touch-ic" && (it.config["enabled"] as? Boolean == true) }
                    val target = rearTouch
                        ?: host.components().singleOrNull { it.descriptor.id == "i2c-bus" }
                        ?: throw IllegalStateException("i2c-bus component unavailable")
                    val updated = host.patch(
                        target.descriptor.id,
                        target.revision,
                        mapOf(
                            "touchEnabled" to true,
                            "touchPressed" to pressed,
                            "touchX" to x.toLong(),
                            "touchY" to y.toLong(),
                        ),
                    ).join()
                    mapOf(
                        "ok" to true,
                        "component" to target.descriptor.id,
                        "x" to x,
                        "y" to y,
                        "pressed" to pressed,
                        "revision" to updated["revision"],
                    )
                }.join()
            },
            touchSwipe = { x1, y1, x2, y2, steps, stepMs ->
                fun patchTouchPoint(x: Int, y: Int, pressed: Boolean): Map<String, Any?> = owner.submit {
                    val rearTouch = host.components().singleOrNull { it.descriptor.id == "rear-touch-ic" && (it.config["enabled"] as? Boolean == true) }
                    val target = rearTouch
                        ?: host.components().singleOrNull { it.descriptor.id == "i2c-bus" }
                        ?: throw IllegalStateException("i2c-bus component unavailable")
                    val updated = host.patch(
                        target.descriptor.id,
                        target.revision,
                        mapOf(
                            "touchEnabled" to true,
                            "touchPressed" to pressed,
                            "touchX" to x.toLong(),
                            "touchY" to y.toLong(),
                        ),
                    ).join()
                    mapOf("component" to target.descriptor.id, "revision" to updated["revision"])
                }.join()

                fun awaitSwipeStep() {
                    val running = telemetry.running && !telemetry.paused
                    val startTick = telemetry.virtualTick
                    if (!running && startTick <= 0L) {
                        Thread.sleep(stepMs)
                        return
                    }
                    val targetTick = startTick + stepMs * 54_000L
                    val deadline = System.nanoTime() + maxOf(stepMs * 8L, 250L) * 1_000_000L
                    while (telemetry.running && !telemetry.paused && telemetry.virtualTick < targetTick && System.nanoTime() < deadline) {
                        Thread.sleep(5L)
                    }
                }

                var last = patchTouchPoint(x1, y1, true)
                for (i in 1..steps) {
                    awaitSwipeStep()
                    val sx = x1 + ((x2 - x1) * i) / steps
                    val sy = y1 + ((y2 - y1) * i) / steps
                    last = patchTouchPoint(sx, sy, true)
                }
                awaitSwipeStep()
                last = patchTouchPoint(x2, y2, false)
                mapOf(
                    "ok" to true,
                    "component" to last["component"],
                    "x1" to x1,
                    "y1" to y1,
                    "x2" to x2,
                    "y2" to y2,
                    "steps" to steps,
                    "stepMs" to stepMs,
                    "revision" to last["revision"],
                )
            },
            telemetryUpload = telemetryPublisher?.let { publisher ->
                { notes ->
                    LogArchiver.flushAsyncLoggers()
                    publisher.uploadCurrentLogNow(notes)
                }
            },
            eyeSensorState = { owner.submit { eyeSensorStatusOnOwner() }.join() },
            setEyeSensor = { near ->
                owner.submit {
                    val tmd = host.components().singleOrNull { it.descriptor.id == "tmd2772" && (it.config["enabled"] as? Boolean == true) }
                    val target = tmd
                        ?: host.components().singleOrNull { it.descriptor.id == "i2c-bus" }
                        ?: throw IllegalStateException("i2c-bus component unavailable")
                    val updated = host.patch(target.descriptor.id, target.revision, mapOf("eyeSensorNear" to near)).join()
                    eyeSensorStatusOnOwner() + mapOf("revision" to updated["revision"])
                }.join()
            },
            setViewMode = { pressed -> owner.submit { setViewModeButtonOnOwner(pressed) }.join() },
            audioStatus = { audioPlayback.status() },
            audioDevices = { audioPlayback.listDevices() },
            audioUpdate = { patch ->
                val gpioPatchKeys = setOf("headphonePlugged", "extMicPlugged")
                val nonGpioPatch = patch.filterKeys { it !in gpioPatchKeys }
                val gpioPatch = patch.filterKeys { it in gpioPatchKeys }
                if (gpioPatch.isNotEmpty()) {
                    gpioPatch["headphonePlugged"]?.let { require(it is Boolean) { "headphonePlugged must be a boolean" } }
                    gpioPatch["extMicPlugged"]?.let { require(it is Boolean) { "extMicPlugged must be a boolean" } }
                }
                if (nonGpioPatch.isNotEmpty()) {
                    audioPlayback.applyPatch(nonGpioPatch)
                }
                if (gpioPatch.isNotEmpty()) {
                    owner.submit {
                        audioPlayback.applyPatch(gpioPatch)
                    }.join()
                }
                audioPlayback.status()
            },
            wirelessStatus = {
                owner.submit {
                    val port = host.ports.find("bcm43438.control", com.github.nyabanban.fuji.emulator.api.WirelessControlPort::class.java)
                        ?: throw IllegalStateException("bcm43438 wireless component unavailable")
                    port.wirelessStatus()
                }.join()
            },
            wirelessAction = { body ->
                val executionMode = config["executionMode"]?.toString() ?: "strict"
                if (body["ptpIpBridgeEnabled"] == true && executionMode != RelaxedPeripheralFallback.MODE) {
                    throw IllegalArgumentException("ptpIpBridgeEnabled is only permitted in relaxed executionMode")
                }
                owner.submit {
                    val port = host.ports.find("bcm43438.control", com.github.nyabanban.fuji.emulator.api.WirelessControlPort::class.java)
                        ?: throw IllegalStateException("bcm43438 wireless component unavailable")
                    port.applyWirelessAction(body)
                }.join()
            },
            languageStatus = {
                mapOf(
                    "language" to I18n.localeCode(),
                    "supported" to I18n.supportedLanguageCodes(),
                )
            },
            setLanguage = { raw ->
                require(I18n.isSupportedCode(raw)) {
                    "Unsupported language '$raw' (supported: ${I18n.supportedLanguageCodes().joinToString(", ")})"
                }
                val resolved = I18n.setLocale(raw)
                mapOf(
                    "ok" to true,
                    "language" to I18n.localeCode(resolved),
                    "supported" to I18n.supportedLanguageCodes(),
                )
            },
            themeStatus = { themeController.statusMap() },
            themeUpdate = { body ->
                themeController.applyTheme(
                    bodyId = body["body"] as? String,
                    uiId = body["ui"] as? String,
                )
                themeController.statusMap()
            },
            themeReload = {
                val r = themeController.reloadThemes()
                themeController.statusMap() + mapOf(
                    "reloaded" to true,
                    "reloadGeneration" to r.reloadGeneration,
                )
            },
            flashStatus = { norFlashStatus() },
            flashAction = { body ->
                val action = (body["action"] as String).trim().lowercase(java.util.Locale.ROOT)
                fun resolvePathField(): Path {
                    val raw = (body["path"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException("flash action '$action' requires 'path'")
                    val p = Path.of(raw)
                    return if (p.isAbsolute) p.normalize() else home.resolve(p).toAbsolutePath().normalize()
                }
                when (action) {
                    "write-dat" -> mapOf("ok" to true) + flashDatIntoNorLive(
                        resolvePathField(),
                        preserveCalibration = (body["preserveCalibration"] as? Boolean) ?: true,
                    ).toMap()
                    "export" -> mapOf("ok" to true) + exportNorFlashLive(resolvePathField()).toMap()
                    "import" -> mapOf("ok" to true) + importNorFlashLive(resolvePathField()).toMap()
                    "restore-factory" -> mapOf("ok" to true) + restoreFactoryFlashLive().toMap()
                    else -> throw IllegalArgumentException("Unsupported flash action '$action'")
                }
            },
        )
        log.info("Control API listening on {}", server!!.address)
    }
    private fun shouldAutoProfile(): Boolean {
        if (profilerAutoStart) return true
        val raw = config["profiler"] ?: return false
        return when (raw) {
            is Boolean -> raw
            is Map<*, *> -> raw["enabled"] == true
            else -> false
        }
    }
    private fun resolvedProfilerIntervalMs(): Long {
        val raw = config["profiler"] as? Map<*, *>
        val fromCfg = (raw?.get("intervalMs") as? Number)?.toLong()
        return (fromCfg ?: profilerIntervalMs).coerceIn(AsyncPerfProfiler.MIN_SAMPLE_INTERVAL_MS, AsyncPerfProfiler.MAX_SAMPLE_INTERVAL_MS)
    }
    private fun finishAutoProfileIfActive(wasAutoStarted: Boolean) {
        if (!wasAutoStarted && !profiler.isRunning()) return
        if (profiler.isRunning()) profiler.stop()
        val exportDir = if (wasAutoStarted || java.lang.Boolean.getBoolean("fuji.profiler.exportFile")) home.resolve("logs") else null
        profiler.exportReport(exportDir)
    }
    @JvmOverloads
    @Synchronized fun probe(snapshot: Path? = null): Map<String, Any?> {
        val started=System.nanoTime()
        telemetry.markSessionStarted(started)
        telemetryPublisher?.start()
        watchdog.start()
        val autoProfile = shouldAutoProfile()
        if (autoProfile && !profiler.isRunning()) profiler.start(resolvedProfilerIntervalMs())
        var finalStatus = "stopped"
        try {
            owner.submit {
                session.initialize()
                snapshot?.let { session.restoreEmulatorSnapshot(it) }
            }.join()
            val initialized=System.nanoTime()
            var more = true
            while (more && owner.isRunning) {
                more = owner.submitRunnerSlice {
                    var batch = 0
                    var keepGoing = true
                    while (keepGoing && batch < 4096) {
                        if (owner.hasPending()) owner.drainQueued()
                        if (!owner.isRunning) {
                            keepGoing = false
                            break
                        }
                        keepGoing = session.stepFast()
                        batch++
                    }
                    keepGoing
                }.join()
            }
            val stopped=System.nanoTime()
            return owner.submit { session.exportStoppedState() }.join().also {
                finalStatus = it["status"]?.toString() ?: "stopped"
                log.debug("Firmware probe host timing: initializeNanos={}, guestExecutionNanos={}, stoppedSnapshotExportStateNanos={}",initialized-started,stopped-initialized,System.nanoTime()-stopped)
                log.info("Firmware probe stopped: {}", it["frontier"] ?: it["status"])
            }
        } finally {
            telemetry.markSessionStopped(finalStatus)
            telemetryPublisher?.markFinalStatus(finalStatus)
            finishAutoProfileIfActive(autoProfile)
            watchdog.pollOnce()
            session.logRelaxedSummaryIfNeeded()
            watchdog.close()
        }
    }
    @JvmOverloads
    @Synchronized fun runInteractive(
        snapshot: Path? = null,
        paused: AtomicBoolean = AtomicBoolean(false),
        stopRequested: AtomicBoolean = AtomicBoolean(false),
    ): Map<String, Any?> {
        telemetry.markSessionStarted()
        telemetryPublisher?.start()
        watchdog.start()
        val autoProfile = shouldAutoProfile()
        if (autoProfile && !profiler.isRunning()) profiler.start(resolvedProfilerIntervalMs())
        val pacer = realTimePacer
        // Raise Windows timer resolution to 1 ms & disable EcoQoS / set Linux 1 ns timerslack for interactive runs.
        val timerResolution = WindowsTimerResolution.acquire(1)
        val osProcessTuningFlags = NativeOsScheduler.tuneProcess(true)
        val pacerNativeTimer = pacer?.usesNativeHighResTimer ?: false
        if (osProcessTuningFlags != 0 && (pacer == null || pacerNativeTimer)) {
            log.info(
                "OS-native interactive scheduler tuning active (processFlags=0x{}, ownerThreadFlags=0x{}, pacerNativeTimer={})",
                osProcessTuningFlags.toString(16),
                owner.ownerThreadTuningFlags.toString(16),
                pacerNativeTimer,
            )
        } else {
            log.warn(
                "[FALLBACK][OS-SCHED] OS-native interactive scheduler/timer degraded (ready={}, processFlags=0x{}, ownerThreadFlags=0x{}, pacerNativeTimer={}); falling back to JVM timers",
                osSchedulerReady,
                osProcessTuningFlags.toString(16),
                owner.ownerThreadTuningFlags.toString(16),
                pacerNativeTimer,
            )
        }
        if (pacer != null) {
            pacer.reset()
            owner.onExternalSubmit = pacer::wake
            log.info("Real-time clock pacing enabled (relaxed): targetRatio={}, ticksPerRealSecond={}", pacer.targetRatio, pacer.ticksPerRealSecond)
        }
        var finalStatus = "stopped"
        try {
            owner.submit {
                session.initialize()
                snapshot?.let { session.restoreEmulatorSnapshot(it) }
            }.join()
            pacer?.reanchor(telemetry.virtualTick)
            var more = true
            var lastPaused = false
            val sliceCapNanos = if (pacer != null) 4_000_000L else 12_000_000L
            // Cap virtual-clock lead per paced slice (~8 ms virtual time) so WFI-heavy idle loops
            // cannot leap multiple virtual seconds ahead before RealTimePacer sleeps, while busy
            // slices (boot/LiveView) stay on full 65,536-attempt stepFast() quanta with zero overhead.
            val maxSliceVirtualTicks = if (pacer != null) {
                (pacer.ticksPerRealSecond * pacer.targetRatio * 0.008).toLong().coerceAtLeast(54_000L)
            } else {
                Long.MAX_VALUE
            }
            var useFinePacedQuantum = false
            val displayPortNames = AsyncDisplayBridge.DEFAULT_PORTS
            var cachedDisplayPortsRef: com.github.nyabanban.fuji.emulator.api.Ports? = null
            var cachedHostReloadGen = -1L
            val cachedDisplayPorts = arrayOfNulls<com.github.nyabanban.fuji.emulator.api.DisplayPort>(displayPortNames.size)
            val lastPublishedContentSeq = LongArray(displayPortNames.size) { -1L }
            val lastPublishedDims = LongArray(displayPortNames.size) { -1L }
            while (more && !stopRequested.get() && owner.isRunning) {
                val isPaused = paused.get()
                if (isPaused != lastPaused) {
                    lastPaused = isPaused
                    userActionLogger.logPauseState(isPaused, telemetry)
                    if (!isPaused) pacer?.reanchor(telemetry.virtualTick)
                }
                telemetry.paused = isPaused
                if (isPaused) {
                    telemetry.ownerHeartbeatNanos = owner.heartbeatNanos
                    try {
                        Thread.sleep(20)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val sliceStartTick = telemetry.virtualTick
                var sliceEndedInWfi = false
                more = owner.submitRunnerSlice {
                    var batch = 0
                    var keepGoing = true
                    val sliceStart = System.nanoTime()
                    while (keepGoing && batch < 64 && !stopRequested.get() && !paused.get()) {
                        if (owner.hasPending()) owner.drainQueued()
                        if (!owner.isRunning || stopRequested.get() || paused.get()) break
                        keepGoing = if (useFinePacedQuantum) session.stepAtMost(2048L) else session.stepFast()
                        batch++
                        if (session.isSocPoweredOff) break
                        if (pacer != null && session.lastStepAllCoresWfi) {
                            if (pacer.leadNanos(telemetry.virtualTick) >= RealTimePacer.DEFAULT_MIN_IDLE_SLEEP_NANOS) {
                                sliceEndedInWfi = true
                                break
                            }
                        } else {
                            sliceEndedInWfi = false
                        }
                        if (pacer != null && (telemetry.virtualTick - sliceStartTick) >= maxSliceVirtualTicks) break
                        if (System.nanoTime() - sliceStart >= sliceCapNanos) break
                    }
                    if (cachedDisplayPortsRef !== host.ports || cachedHostReloadGen != hostReloadGeneration) {
                        cachedDisplayPorts.fill(null)
                        lastPublishedContentSeq.fill(-1L)
                        lastPublishedDims.fill(-1L)
                        cachedDisplayPortsRef = host.ports
                        cachedHostReloadGen = hostReloadGeneration
                    }
                    var anyDisplayContentChanged = false
                    for (i in displayPortNames.indices) {
                        val port = host.ports.find(displayPortNames[i], com.github.nyabanban.fuji.emulator.api.DisplayPort::class.java)
                        if (cachedDisplayPorts[i] !== port) {
                            cachedDisplayPorts[i] = port
                            lastPublishedContentSeq[i] = -1L
                            lastPublishedDims[i] = -1L
                        }
                        if (port == null) continue
                        val cseq: Long
                        val dims: Long
                        try {
                            cseq = port.contentSequence
                            dims = (port.width.toLong() shl 32) or (port.height.toLong() and 0xffff_ffffL)
                        } catch (_: IllegalStateException) {
                            // Port component closed concurrently (cold reset / hot-reload); drop stale handle cleanly.
                            cachedDisplayPorts[i] = null
                            lastPublishedContentSeq[i] = -1L
                            lastPublishedDims[i] = -1L
                            continue
                        }
                        if (cseq <= 0L) {
                            lastPublishedContentSeq[i] = -1L
                            lastPublishedDims[i] = -1L
                        } else if (cseq != lastPublishedContentSeq[i] || dims != lastPublishedDims[i]) {
                            lastPublishedContentSeq[i] = cseq
                            lastPublishedDims[i] = dims
                            anyDisplayContentChanged = true
                        }
                    }
                    if (anyDisplayContentChanged) {
                        displayBridge.publishAll(host.ports)
                    }
                    keepGoing
                }.join()
                if (pacer != null) {
                    val sliceVirtualDelta = (telemetry.virtualTick - sliceStartTick).coerceAtLeast(0L)
                    useFinePacedQuantum = sliceVirtualDelta >= maxSliceVirtualTicks
                }
                if (more && session.isSocPoweredOff && !session.hasPendingSocWake && !stopRequested.get() && !paused.get() && owner.isRunning && !owner.hasPending()) {
                    try {
                        Thread.sleep(15)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else if (more && pacer != null && !stopRequested.get() && !paused.get() && owner.isRunning) {
                    if (sliceEndedInWfi || session.lastStepAllCoresWfi) {
                        pacer.paceWfiIdle(telemetry.virtualTick) { stopRequested.get() || paused.get() || !owner.isRunning || owner.hasPending() }
                    } else {
                        pacer.pace(telemetry.virtualTick) { stopRequested.get() || paused.get() || !owner.isRunning || owner.hasPending() }
                    }
                }
            }
            return if (owner.isRunning) {
                owner.submit { session.state() }.join().also {
                    finalStatus = it["status"]?.toString() ?: "stopped"
                    if (stopRequested.get()) {
                        log.info("Interactive session stopped on shutdown at {} attempts", it["attempts"])
                    } else {
                        log.info("Interactive session halted: status={} frontier={}", it["status"], it["frontier"])
                    }
                }
            } else {
                emptyMap()
            }
        } catch (error: Throwable) {
            finalStatus = "crashed"
            val summary = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            if (LogArchiver.markLoggedOnce(error)) {
                log.error(
                    "Interactive firmware session crashed at tick={} frame={}: {}",
                    telemetry.virtualTick,
                    telemetry.publishedFrames,
                    summary,
                    error,
                )
                LogArchiver.flushAsyncLoggers()
            }
            throw error
        } finally {
            if (pacer != null) {
                owner.onExternalSubmit = null
                log.info("Real-time pacer summary: {}", pacer.stats())
            }
            if (osProcessTuningFlags != 0) {
                NativeOsScheduler.tuneProcess(false)
            }
            timerResolution.close()
            telemetry.markSessionStopped(finalStatus)
            telemetryPublisher?.markFinalStatus(finalStatus)
            finishAutoProfileIfActive(autoProfile)
            watchdog.pollOnce()
            session.logRelaxedSummaryIfNeeded()
            watchdog.close()
        }
    }
    fun dump() = owner.submit {
        val state = host.snapshot().join() + mapOf("machine" to session.state(), "registerSnapshot" to session.registerSnapshot(), "javaVersion" to System.getProperty("java.version"))
        DebugDump.write(home.resolve("dumps"), state)
    }.join()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { realTimePacer?.close() }
        finally {
        try { audioPlayback.close() }
        finally {
            try { profiler.close() }
            finally {
                try { watchdog.close() }
                finally {
                    try { buttonEventSub.close() }
                    finally {
                        try { server?.close() }
                        finally {
                            try { owner.submit { try { session.close() } finally { host.close() } }.join() }
                            finally {
                                try { owner.close() }
                                finally {
                                    telemetryPublisher?.let { publisher ->
                                        LogArchiver.flushAsyncLoggers()
                                        runCatching { publisher.close() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
internal class MissingFirmwareStartupContext(
    val issue: FirmwareGuidance.Issue,
    val home: Path,
    val config: Map<String, Any>,
    val userProfilePath: Path?,
    val createAndStartRuntime: () -> EmulatorRuntime,
) {
    @Volatile var completedRuntime: EmulatorRuntime? = null
    @Volatile var attachedConsole: EmulatorConsole? = null
}

object LaunchContext {
    lateinit var runtime: EmulatorRuntime
    val isRuntimeInitialized: Boolean
        get() = ::runtime.isInitialized
    internal var missingFirmwareStartup: MissingFirmwareStartupContext? = null
    var smokePath: Path? = null
    var snapshot: Path? = null
    var autoBoot: Boolean = false
    val paused: AtomicBoolean = AtomicBoolean(false)
    val stopRequested: AtomicBoolean = AtomicBoolean(false)
    @Volatile var bootTrigger: (() -> String)? = null
    @Volatile var onPauseChanged: ((Boolean) -> Unit)? = null
}

internal data class ConsoleLaunchOptions(
    val headless: Boolean,
    val consoleEnabled: Boolean,
)

internal fun parseConsoleLaunchOptions(
    args: Array<String>,
    consoleDisabledProperty: Boolean = java.lang.Boolean.getBoolean("fuji.console.disabled"),
): ConsoleLaunchOptions {
    val headless = args.any { it == "--headless" || it == "--nogui" || it.equals("nogui", ignoreCase = true) }
    val consoleEnabled = "--no-console" !in args && !consoleDisabledProperty
    return ConsoleLaunchOptions(headless = headless, consoleEnabled = consoleEnabled)
}

/** Same strict CLI envelope gate used by generated-profile JUnit tests. */
internal fun validateLaunchConfiguration(config: Map<String, Any?>) {
    val mode = CatalogRuntime.mode(config)
    val relaxed = mode == RelaxedPeripheralFallback.MODE
    InstructionBudget.parse(config["instructionBudget"], relaxed = relaxed)
    IdleClock.parse(config["idleClock"])
    RelaxedBootstrapRam.selected(config, relaxed)
    FlashPartitionPairContracts.selected(config)
    val powerBlockSelected=PowerBlockContracts.selected(config)
    if(config.containsKey(Power2Bit8Admission.CONFIG_KEY))
        require(config[Power2Bit8Admission.CONFIG_KEY]==Power2Bit8Admission.PROFILE && powerBlockSelected) {
            "Power2 bit8 requires the explicit profile and complete block contract"
        }
    if(config.containsKey("prefetchAbortProfile"))
        require(config["prefetchAbortProfile"]=="arm32-native-prefetch-entry-experimental-v1" &&
            config["mmuProfile"]=="arm-short-descriptor-native-experimental-v1" &&
            config["clusterProfile"]==com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster.PROFILE) {
            "Native prefetch entry requires the explicit profile, native MMU and cluster"
        }
    if (config.containsKey("exceptionProfile")) {
        val raw = config["exceptionProfile"]
        require(raw is String && raw in listOf("", "armv7-full-vectors-v1")) {
            "Unknown exceptionProfile: $raw"
        }
    }
    CustomFirmwareConfig.allowCustomFirmware(config, mode)
    val customRawImage = CustomFirmwareConfig.parseRawImage(config, mode)
    CpuEngineConfig.validate(config, mode)
    FlashReadPairContracts.selected(config)
    FlashWritableSetContracts.selected(config)
    if (config.containsKey("bootRomFile")) {
        require(config["bootRomFile"] is String && (config["bootRomFile"] as String).isNotBlank()) { "bootRomFile requires a nonblank path" }
    }
    val hasNorFlashFile = config.containsKey("norFlashFile")
    if (hasNorFlashFile) {
        require(config["norFlashFile"] is String && (config["norFlashFile"] as String).isNotBlank()) { "norFlashFile requires a nonblank path" }
    }
    if (config.containsKey("factoryFlashFile")) {
        require(config["factoryFlashFile"] is String && (config["factoryFlashFile"] as String).isNotBlank()) { "factoryFlashFile requires a nonblank path" }
    }
    if (config.containsKey("componentConfigs")) {
        require(config["componentConfigs"] is String && (config["componentConfigs"] as String).isNotBlank()) { "componentConfigs requires a nonblank path" }
    }
    if (config.containsKey("profiler")) {
        val raw = config["profiler"]
        when (raw) {
            is Boolean -> Unit
            is Map<*, *> -> {
                require(raw.keys.all { it is String && it in setOf("enabled", "intervalMs") }) { "invalid profiler config keys" }
                raw["enabled"]?.let { require(it is Boolean) { "profiler.enabled requires boolean" } }
                raw["intervalMs"]?.let { v ->
                    require(v is Byte || v is Short || v is Int || v is Long) { "profiler.intervalMs requires integer" }
                    val ms = (v as Number).toLong()
                    require(ms in AsyncPerfProfiler.MIN_SAMPLE_INTERVAL_MS..AsyncPerfProfiler.MAX_SAMPLE_INTERVAL_MS) {
                        "profiler.intervalMs out of bounds"
                    }
                }
            }
            else -> throw IllegalArgumentException("profiler requires boolean or map")
        }
    }
    AudioPlaybackConfig.validateConfigValue(config[AudioPlaybackConfig.CONFIG_KEY])
    TelemetryHubClient.validateLaunchConfig(config)
    if (config.containsKey("realTimePacer")) {
        require(relaxed) { "realTimePacer is only permitted in relaxed mode" }
        val raw = config["realTimePacer"]
        when (raw) {
            is Boolean -> Unit
            is Map<*, *> -> {
                require(raw.keys.all { it is String && it in setOf("enabled", "targetRatio", "ticksPerRealSecond") }) {
                    "invalid realTimePacer config keys"
                }
                raw["enabled"]?.let { require(it is Boolean) { "realTimePacer.enabled requires boolean" } }
                raw["targetRatio"]?.let { v ->
                    require(v is Number && v.toDouble().isFinite() && v.toDouble() in RealTimePacer.MIN_TARGET_RATIO..RealTimePacer.MAX_TARGET_RATIO) {
                        "realTimePacer.targetRatio out of bounds"
                    }
                }
                raw["ticksPerRealSecond"]?.let { v ->
                    require(v is Number && v.toDouble().isFinite() && v.toDouble() > 0.0) {
                        "realTimePacer.ticksPerRealSecond must be positive"
                    }
                }
            }
            else -> throw IllegalArgumentException("realTimePacer requires boolean or map")
        }
    }
    if (config.containsKey("bootHoldButtons")) {
        require(relaxed) { "bootHoldButtons is only permitted in relaxed mode" }
        when (val raw = config["bootHoldButtons"]) {
            is String -> Unit
            is List<*> -> require(raw.all { it is String && it.isNotBlank() }) { "bootHoldButtons list entries must be nonblank strings" }
            else -> throw IllegalArgumentException("bootHoldButtons requires string or list of strings")
        }
    }
    val baseRequired = if (relaxed) {
        setOf("schema", "components", "httpPort", "firmwareMetadata", "enabledDevices")
    } else {
        setOf("schema", "components", "httpPort", "firmwareMetadata", "instructionBudget", "enabledDevices")
    }
    val requiredKeys = if (customRawImage != null || hasNorFlashFile) baseRequired else baseRequired + "firmwareFile"
    val optionalKeys = setOf("componentConfigs","executionMode","addressDictionary","discovery","tracePoints","tracePerPointLimit","memoryWatches","cbarInput","clusterProfile","clusterReset","mmuProfile","wordExclusive","transactionLimit","idleClock","undefinedExceptionProfile","prefetchAbortProfile","exceptionProfile","flashPartitionScope","powerBlockScope","flashReadPairScope","flashWritableSetScope","bootRomFile",BOOT_ROM_BOOT_KEY,"norFlashFile","factoryFlashFile","bootHoldButtons","profiler","realTimePacer","nativeLibrary",CpuEngineConfig.CPU_ENGINE_KEY,CpuEngineConfig.DYNARMIC_LIBRARY_KEY,CpuEngineConfig.SMP_HOST_THREADS_KEY,CustomFirmwareConfig.ALLOW_CUSTOM_KEY,CustomFirmwareConfig.CUSTOM_RAW_KEY,CustomFirmwareConfig.CUSTOM_KERNEL_KEY,AudioPlaybackConfig.CONFIG_KEY,TelemetryHubClient.CONFIG_KEY,TelemetryHubClient.LEGACY_UPLOAD_CONFIG_KEY,KernelLateCallObservation.KEY,Power2Bit8Admission.CONFIG_KEY,RelaxedBootstrapRam.CONFIG_KEY) +
        (if (relaxed) setOf("instructionBudget") else emptySet()) +
        (if (customRawImage != null || hasNorFlashFile) setOf("firmwareFile") else emptySet())
    require(config.keys - optionalKeys == requiredKeys && config["schema"] == 1) { "invalid runtime config" }
    config[BOOT_ROM_BOOT_KEY]?.let { require(it is Boolean) { "$BOOT_ROM_BOOT_KEY must be a boolean" } }
}

/**
 * Explicit opt-in for the full SPX4 mask BootROM cold-boot path (`PC=0xFFF00000` -> Stage-1 IPL -> Stage-2 XIP ->
 * `flash/nor-flash.bin`). Default `false`: the verified Flash-Direct path boots `boot.bin` straight from
 * `nor-flash.bin[0x20000..0x3FFFF]`. Also enabled by the `--boot-rom` CLI flag.
 */
internal const val BOOT_ROM_BOOT_KEY = "bootRomBoot"

internal fun applyBootRomCliOverride(config: Map<String, Any>, args: Array<String>): Map<String, Any> =
    if ("--boot-rom" in args) config + (BOOT_ROM_BOOT_KEY to true) else config

internal fun isMachineOutputMode(args: Array<String>): Boolean = args.any { arg ->
    arg == "--probe" ||
        arg == "--inspect" ||
        arg == "--inspect-flash" ||
        arg == "--flash-dat" ||
        arg == "--export-flash" ||
        arg == "--import-flash" ||
        arg == "--restore-factory-flash" ||
        arg.startsWith("--query-")
}

internal fun isPureJsonCliMode(args: Array<String>): Boolean = args.any { arg ->
    arg == "--inspect" ||
        arg == "--inspect-flash" ||
        arg == "--flash-dat" ||
        arg == "--export-flash" ||
        arg == "--import-flash" ||
        arg == "--restore-factory-flash" ||
        arg.startsWith("--query-")
}

fun main(args: Array<String>) {
    val exitCode = executeMain(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}

internal fun executeMain(
    args: Array<String>,
    allowInteractiveGuiPrompt: Boolean = true,
): Int {
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { require(it + 1 < args.size); args[it + 1] }
    val home = Path.of(option("--home") ?: ".").toAbsolutePath().normalize()
    val pureJsonCli = isPureJsonCliMode(args)
    val cliConsoleLogLevel = LogArchiver.parseCliLogLevel(args)
    var shutdownLog: Thread? = null
    var loadedConfig: Map<String, Any?> = emptyMap()
    var explicitCliTarget: Path? = null
    if (!pureJsonCli) {
        val effectiveConsoleLevel = cliConsoleLogLevel ?: if (isMachineOutputMode(args)) "OFF" else null
        LogArchiver.initializeLogging(home, consoleLevel = effectiveConsoleLevel)
        if (effectiveConsoleLevel != null) {
            LogArchiver.setConsoleLevel(effectiveConsoleLevel)
        }
        shutdownLog = Thread({ LogArchiver.shutdownLogging() }, "fuji-log-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownLog)
    }
    try {
        val configPath = option("--config")?.let { home.resolve(it) }
            ?: home.resolve("configs/emulator.yaml").takeIf { java.nio.file.Files.isRegularFile(it) }
            ?: home.resolve("config/emulator.yaml")
        val config = try {
            applyBootRomCliOverride(CustomFirmwareConfig.applyCliOverrides(Formats.read(configPath), args), args).also(::validateLaunchConfiguration)
        } catch (error: Throwable) {
            if (!pureJsonCli) {
                LoggerFactory.getLogger("com.github.nyabanban.fuji.emulator.desktop.MainKt")
                    .error("Failed to load or validate runtime configuration at {}", configPath, error)
            }
            throw error
        }
        loadedConfig = config
        if ("--inspect-flash" in args) {
            val norPath = NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("norFlashFile is not configured in $configPath")
            explicitCliTarget = norPath
            NorFlashTool.ensureLiveNorExists(home, config)
            print(Formats.jsonText(NorFlashTool.inspectNor(norPath).toMap())); return 0
        }
        option("--flash-dat")?.let { rawDat ->
            val norPath = NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("norFlashFile is not configured in $configPath")
            NorFlashTool.ensureLiveNorExists(home, config)
            val datPath = Path.of(rawDat).let { if (it.isAbsolute) it else home.resolve(it) }.normalize()
            explicitCliTarget = datPath
            val preserveCal = "--no-preserve-calibration" !in args
            val result = NorFlashTool.flashDatIntoNor(datPath, norPath, preserveCalibration = preserveCal)
            print(Formats.jsonText(result.toMap())); return 0
        }
        option("--export-flash")?.let { rawOut ->
            val norPath = NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("norFlashFile is not configured in $configPath")
            explicitCliTarget = norPath
            NorFlashTool.ensureLiveNorExists(home, config)
            val outPath = Path.of(rawOut).let { if (it.isAbsolute) it else home.resolve(it) }.normalize()
            val result = NorFlashTool.exportNor(norPath, outPath)
            print(Formats.jsonText(result.toMap())); return 0
        }
        option("--import-flash")?.let { rawIn ->
            val norPath = NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("norFlashFile is not configured in $configPath")
            val inPath = Path.of(rawIn).let { if (it.isAbsolute) it else home.resolve(it) }.normalize()
            explicitCliTarget = inPath
            val result = NorFlashTool.importNor(inPath, norPath)
            print(Formats.jsonText(result.toMap())); return 0
        }
        if ("--restore-factory-flash" in args) {
            val norPath = NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("norFlashFile is not configured in $configPath")
            val factoryPath = NorFlashTool.resolveFactoryFlashPath(home, config)
                ?: error("factoryFlashFile is not configured in $configPath")
            explicitCliTarget = factoryPath
            val result = NorFlashTool.restoreFactoryNor(factoryPath, norPath)
            print(Formats.jsonText(result.toMap())); return 0
        }
        if ("--query-address" in args || "--query-selector" in args || "--query-id" in args || "--query-coverage" in args) {
            val query = linkedMapOf<String,String>()
            option("--query-address")?.let { query["address"]=it }
            option("--query-selector")?.let { query["selector"]=it }
            option("--word-bits")?.let { query["wordBits"]=it }
            option("--component-profile")?.let { query["componentProfile"]=it }
            option("--query-id")?.let { query["id"]=it }
            option("--property")?.let { query["property"]=it }
            option("--space")?.let { query["space"]=it }
            option("--width")?.let { query["width"]=it }
            option("--direction")?.let { query["direction"]=it }
            if("--query-coverage" in args)query["coverage"]="true"
            print(Formats.jsonText(CatalogRuntime.load(home,config).query(query))); return 0
        }
        if ("--inspect" in args) {
            val rawFirmwareFile = (config["firmwareFile"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (rawFirmwareFile != null) {
                val datPath = home.resolve(rawFirmwareFile).normalize()
                explicitCliTarget = datPath
                val allowCustom = CustomFirmwareConfig.allowCustomFirmware(config, CatalogRuntime.mode(config))
                val image = FirmwareImage(
                    FirmwareMetadata(home.resolve(config.getValue("firmwareMetadata") as String)),
                    datPath,
                    allowCustomFirmware = allowCustom,
                )
                print(Formats.jsonText(image.inspect())); return 0
            }
            val norPath = com.github.nyabanban.fuji.emulator.runtime.NorFlashTool.resolveNorFlashPath(home, config)
                ?: error("Neither firmwareFile nor norFlashFile is configured")
            explicitCliTarget = norPath
            com.github.nyabanban.fuji.emulator.runtime.NorFlashTool.ensureLiveNorExists(home, config)
            print(Formats.jsonText(com.github.nyabanban.fuji.emulator.runtime.NorFlashTool.inspectNor(norPath).toMap())); return 0
        }
        val snapshot = option("--resume-snapshot")?.let { Path.of(it).toRealPath() }
        val (cliUserProfilePath, cliProfilerFlag) = parseProfileLaunchArgs(args, home)
        val cliProfile = cliProfilerFlag || java.lang.Boolean.getBoolean("fuji.profiler.enabled")
        val cliProfileInterval = option("--profile-interval-ms")?.toLongOrNull()
            ?: System.getProperty("fuji.profiler.intervalMs")?.toLongOrNull()
            ?: AsyncPerfProfiler.DEFAULT_SAMPLE_INTERVAL_MS
        val cliNoTelemetry = "--no-telemetry" in args || java.lang.Boolean.getBoolean("fuji.telemetry.disabled")
        val cliTelemetryUpload = "--telemetry-upload" in args || java.lang.Boolean.getBoolean("fuji.telemetry.upload")
        val cliRealTimePace = "--realtime-pace" in args || java.lang.Boolean.getBoolean("fuji.realtime.pace")
        val cliAudio = "--audio" in args || java.lang.Boolean.getBoolean("fuji.audio.enabled")
        val cliAudioVolume = (option("--audio-volume")?.toDoubleOrNull()
            ?: System.getProperty("fuji.audio.volume")?.toDoubleOrNull())
            ?.let { if (it > 1.0 && it <= 100.0) it / 100.0 else it }
        val interactiveProgress = !isMachineOutputMode(args)
        val consoleOptions = parseConsoleLaunchOptions(args)
        val smokeUiRaw = option("--smoke-ui")

        fun buildRuntime(): EmulatorRuntime = EmulatorRuntime(
            home = home,
            config = config,
            profilerAutoStart = cliProfile,
            profilerIntervalMs = cliProfileInterval,
            telemetryDisabled = cliNoTelemetry,
            telemetryUploadRequested = cliTelemetryUpload,
            realTimePacerCliEnabled = cliRealTimePace,
            audioEnabledCli = cliAudio,
            audioVolumeCli = cliAudioVolume,
            startupProgress = interactiveProgress,
            userProfilePath = cliUserProfilePath,
        )

        val initialRuntime = try {
            buildRuntime()
        } catch (missingErr: MissingFirmwareException) {
            val canOpenGuiPrompt = allowInteractiveGuiPrompt &&
                !pureJsonCli &&
                !isMachineOutputMode(args) &&
                !consoleOptions.headless &&
                smokeUiRaw == null &&
                !java.awt.GraphicsEnvironment.isHeadless()
            if (!canOpenGuiPrompt) {
                throw missingErr
            }
            val startupLog = LoggerFactory.getLogger("com.github.nyabanban.fuji.emulator.desktop.MainKt")
            if (LogArchiver.markLoggedOnce(missingErr)) {
                startupLog.warn("Firmware setup required before starting GUI:\n{}", missingErr.issue.cliReport)
                LogArchiver.flushAsyncLoggers()
            }
            lateinit var startupCtx: MissingFirmwareStartupContext
            startupCtx = MissingFirmwareStartupContext(
                issue = missingErr.issue,
                home = home,
                config = config,
                userProfilePath = cliUserProfilePath,
                createAndStartRuntime = {
                    val created = buildRuntime()
                    created.serve()
                    LaunchContext.runtime = created
                    startupCtx.completedRuntime = created
                    if (consoleOptions.consoleEnabled) {
                        startupCtx.attachedConsole = EmulatorConsole(
                            RuntimeConsoleBackend(
                                runtime = created,
                                paused = LaunchContext.paused,
                                stopRequested = LaunchContext.stopRequested,
                                onBootRequested = { LaunchContext.bootTrigger?.invoke() ?: "GUI is still initializing; retry 'boot' shortly." },
                                onStopRequested = { runCatching { Platform.exit() } },
                                onPauseChanged = { isPaused -> LaunchContext.onPauseChanged?.invoke(isPaused) },
                            ),
                        ).also { it.start() }
                    }
                    created
                },
            )
            LaunchContext.missingFirmwareStartup = startupCtx
            LaunchContext.snapshot = snapshot
            LaunchContext.autoBoot = "--auto-boot" in args
            LaunchContext.smokePath = null
            LaunchContext.paused.set(false)
            LaunchContext.stopRequested.set(false)
            LaunchContext.bootTrigger = null
            LaunchContext.onPauseChanged = null
            try {
                Application.launch(EmulatorApp::class.java)
            } finally {
                LaunchContext.missingFirmwareStartup = null
                try {
                    startupCtx.attachedConsole?.close()
                } finally {
                    startupCtx.completedRuntime?.close()
                }
            }
            return if (startupCtx.completedRuntime != null) {
                0
            } else {
                System.err.println(missingErr.issue.cliReport)
                1
            }
        }

        initialRuntime.use { runtime ->
            if ("--probe" in args) {
                print(Formats.jsonText(runtime.probe(snapshot))); return 0
            }
            if (interactiveProgress) {
                val startupLog = LoggerFactory.getLogger("com.github.nyabanban.fuji.emulator.desktop.MainKt")
                val target = if (consoleOptions.headless) "headless console" else "GUI"
                startupLog.info("[3/3] Starting Control API & {}...", target)
            }
            runtime.serve()
            if (consoleOptions.headless) {
                val paused = AtomicBoolean(false)
                val stopRequested = AtomicBoolean(false)
                val stopLatch = CountDownLatch(1)
                val runnerThread = AtomicReference<Thread?>(null)
                val headlessFailure = AtomicReference<Throwable?>(null)
                val headlessLog = LoggerFactory.getLogger("FujiHeadless")

                fun logHeadlessError(err: Throwable, contextLabel: String) {
                    val guidance = FirmwareGuidance.diagnose(err, home, config)
                    if (LogArchiver.markLoggedOnce(err)) {
                        if (guidance != null) {
                            headlessLog.error("{}:\n{}", contextLabel, guidance.cliReport)
                        } else {
                            headlessLog.error("{}: {}", contextLabel, err.message, err)
                        }
                        LogArchiver.flushAsyncLoggers()
                    }
                    if (guidance != null && consoleOptions.consoleEnabled) {
                        System.err.println(guidance.cliReport)
                    }
                }

                fun triggerHeadlessBoot(): String {
                    val existing = runnerThread.get()
                    if (existing != null && existing.isAlive) {
                        return if (paused.compareAndSet(true, false)) {
                            runtime.telemetry.paused = false
                            "Firmware resumed."
                        } else {
                            "Firmware is already running."
                        }
                    }
                    if (stopRequested.get()) return "Emulator is stopping."
                    paused.set(false)
                    val thread = Thread.ofVirtual()
                        .name("firmware-headless-interactive")
                        .uncaughtExceptionHandler { t, err ->
                            logHeadlessError(err, "Uncaught exception on ${t.name}")
                            if (!consoleOptions.consoleEnabled) {
                                headlessFailure.compareAndSet(null, err)
                                stopLatch.countDown()
                            }
                        }
                        .start {
                            runCatching {
                                if (runtime.catalog.mode == RelaxedPeripheralFallback.MODE) {
                                    runtime.runInteractive(snapshot, paused, stopRequested)
                                } else {
                                    runtime.probe(snapshot)
                                }
                            }.onFailure { err ->
                                logHeadlessError(err, "Headless firmware run failed")
                                if (!consoleOptions.consoleEnabled) {
                                    headlessFailure.compareAndSet(null, err)
                                    stopLatch.countDown()
                                }
                            }
                        }
                    runnerThread.set(thread)
                    return "Firmware boot started (headless)."
                }

                if ("--auto-boot" in args) {
                    triggerHeadlessBoot()
                }

                val shutdownHook = Thread({
                    stopRequested.set(true)
                    paused.set(false)
                    stopLatch.countDown()
                    try { runtime.close() } finally { LogArchiver.shutdownLogging() }
                }, "fuji-headless-shutdown")
                Runtime.getRuntime().addShutdownHook(shutdownHook)

                val console = if (consoleOptions.consoleEnabled) {
                    EmulatorConsole(
                        RuntimeConsoleBackend(
                            runtime = runtime,
                            paused = paused,
                            stopRequested = stopRequested,
                            onBootRequested = ::triggerHeadlessBoot,
                            onStopRequested = { stopLatch.countDown() },
                        ),
                    ).also { it.start() }
                } else {
                    null
                }
                try {
                    stopLatch.await()
                } finally {
                    stopRequested.set(true)
                    paused.set(false)
                    console?.close()
                    runnerThread.get()?.let { runCatching { it.join(2000) } }
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                }
                headlessFailure.get()?.let { throw it }
            } else {
                LaunchContext.missingFirmwareStartup = null
                LaunchContext.runtime = runtime
                LaunchContext.snapshot = snapshot
                LaunchContext.autoBoot = "--auto-boot" in args
                LaunchContext.smokePath = smokeUiRaw?.let { home.resolve(it) }
                LaunchContext.paused.set(false)
                LaunchContext.stopRequested.set(false)
                LaunchContext.bootTrigger = null
                LaunchContext.onPauseChanged = null

                val console = if (consoleOptions.consoleEnabled && LaunchContext.smokePath == null) {
                    EmulatorConsole(
                        RuntimeConsoleBackend(
                            runtime = runtime,
                            paused = LaunchContext.paused,
                            stopRequested = LaunchContext.stopRequested,
                            onBootRequested = { LaunchContext.bootTrigger?.invoke() ?: "GUI is still initializing; retry 'boot' shortly." },
                            onStopRequested = { runCatching { Platform.exit() } },
                            onPauseChanged = { isPaused -> LaunchContext.onPauseChanged?.invoke(isPaused) },
                        ),
                    ).also { it.start() }
                } else {
                    null
                }
                try {
                    Application.launch(EmulatorApp::class.java)
                } finally {
                    console?.close()
                }
            }
        }
        return 0
    } catch (error: Throwable) {
        val guidanceIssue = FirmwareGuidance.diagnose(error, home, loadedConfig, explicitTarget = explicitCliTarget)
        if (guidanceIssue != null) {
            if (!pureJsonCli && LogArchiver.markLoggedOnce(error)) {
                LoggerFactory.getLogger("com.github.nyabanban.fuji.emulator.desktop.MainKt")
                    .error("Firmware setup required:\n{}", guidanceIssue.cliReport)
                LogArchiver.flushAsyncLoggers()
            }
            System.err.println(guidanceIssue.cliReport)
            return 1
        }
        throw error
    } finally {
        if (!pureJsonCli) {
            shutdownLog?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
            LogArchiver.shutdownLogging()
        }
    }
}

/**
 * Parses `--profile <path>` / `--user-profile <path>` while preserving backward compatibility
 * with the standalone boolean `--profile` profiler auto-start flag (when `--profile` is NOT
 * followed by a path argument).
 */
internal fun parseProfileLaunchArgs(args: Array<String>, home: Path): Pair<Path?, Boolean> {
    var userProfilePath: Path? = System.getProperty("fuji.user.profile")
        ?.takeIf { it.isNotBlank() }
        ?.let { resolveProfilePath(home, it) }
    var profilerFlag = false
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--user-profile" && i + 1 < args.size -> {
                userProfilePath = resolveProfilePath(home, args[i + 1])
                i += 2
            }
            arg.startsWith("--user-profile=") -> {
                userProfilePath = resolveProfilePath(home, arg.substringAfter('='))
                i++
            }
            arg.startsWith("--profile=") -> {
                userProfilePath = resolveProfilePath(home, arg.substringAfter('='))
                i++
            }
            arg == "--profile" -> {
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("-")) {
                    userProfilePath = resolveProfilePath(home, next)
                    i += 2
                } else {
                    profilerFlag = true
                    i++
                }
            }
            else -> i++
        }
    }
    return userProfilePath to profilerFlag
}

private fun resolveProfilePath(home: Path, raw: String): Path {
    val p = Path.of(raw)
    return if (p.isAbsolute) p.normalize() else home.resolve(p).normalize()
}
