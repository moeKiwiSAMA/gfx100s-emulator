package com.github.nyabanban.fuji.emulator.desktop.session

import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.ReadOnlyImagePort
import com.github.nyabanban.fuji.emulator.cpu.ArmCpuBackend
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.desktop.CpuEngineConfig
import com.github.nyabanban.fuji.emulator.desktop.CustomFirmwareConfig
import com.github.nyabanban.fuji.emulator.desktop.FirmwareGuidance
import com.github.nyabanban.fuji.emulator.desktop.InvalidFirmwareArgumentException
import com.github.nyabanban.fuji.emulator.firmware.BootRomImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareImage
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.CatalogRuntime
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import org.slf4j.Logger
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads and validates `boot-rom.bin`, `GXUP0004.DAT`, and raw ARM32 images, maps NOR Flash Stage-2 XIP
 * pages and MMIO/relaxed-aperture intervals without overlap, and initializes 4-core reset vectors
 * on the Dynarmic JIT backend.
 */
internal object SessionBootLoader {
    const val STAGE2_XIP_BASE = 0xf0007000L
    const val STAGE2_XIP_SIZE = 0x1000L

    private val STAGE2_XIP_DISCOVERY_PROFILES = setOf(
        "bootrom-stage1-ddr-init-v2",
        "bootrom-qspi-kernel-v3",
    )

    data class LoadedArtifacts(
        val meta: FirmwareMetadata,
        val isBootRom: Boolean,
        val bootRomBytes: ByteArray?,
        val loadedRaw: CustomFirmwareConfig.LoadedRawImage?,
        val image: FirmwareImage?,
        val loader: ByteArray?,
        val customContracts: Map<String, Any?>,
        val firmwareSha: String,
        val bootMode: String,
    )

    fun loadMetadataAndBootRom(
        home: Path,
        config: Map<String, Any>,
        catalogRuntime: CatalogRuntime,
        rawImageSpec: CustomFirmwareConfig.RawImageSpec?,
    ): Triple<FirmwareMetadata, Boolean, ByteArray?> {
        val file = config["firmwareFile"] as? String ?: ""
        val norFlashFile = config["norFlashFile"] as? String ?: ""
        if (rawImageSpec == null && norFlashFile.isBlank()) {
            if (file.isBlank()) {
                throw InvalidFirmwareArgumentException(
                    FirmwareGuidance.buildIssue(
                        kind = FirmwareGuidance.Kind.MISSING_FIRMWARE_DAT,
                        home = home,
                        config = config,
                        detailMessage = "firmwareFile or norFlashFile must be selected",
                    ),
                )
            }
        }
        val declaration = Formats.read(home.resolve(config.getValue("firmwareMetadata") as String))
        catalogRuntime.validateFirmware(declaration)
        val meta = FirmwareMetadata(declaration)
        val isBootRom = meta.bootMode == FirmwareMetadata.BOOT_ROM_EXPERIMENT
        val bootRomFile = config["bootRomFile"] as? String
        val bootRomBytes = if (isBootRom) {
            require(!bootRomFile.isNullOrBlank()) { "boot-rom-experiment requires bootRomFile" }
            val resolvedBootRom = home.resolve(bootRomFile)
            try {
                BootRomImage.load(resolvedBootRom, meta)
            } catch (err: Throwable) {
                val issue = FirmwareGuidance.diagnose(err, home, config, explicitTarget = resolvedBootRom)
                    ?: FirmwareGuidance.buildIssue(
                        kind = if (Files.isRegularFile(resolvedBootRom)) {
                            FirmwareGuidance.Kind.INVALID_BOOT_ROM
                        } else {
                            FirmwareGuidance.Kind.MISSING_BOOT_ROM
                        },
                        home = home,
                        config = config,
                        explicitTarget = resolvedBootRom,
                        detailMessage = err.message ?: "Failed to load BootROM image: $resolvedBootRom",
                    )
                throw InvalidFirmwareArgumentException(issue, err)
            }
        } else {
            // Allow bootRomFile to be declared alongside flash-direct metadata only when not boot-rom-experiment?
            // Keep strict refusal in non-relaxed modes; in relaxed mode allow staged bootRomFile without boot-rom metadata.
            if (bootRomFile != null && catalogRuntime.mode != RelaxedPeripheralFallback.MODE) {
                throw IllegalArgumentException("bootRomFile is only valid with boot-rom-experiment")
            }
            null
        }
        return Triple(meta, isBootRom, bootRomBytes)
    }

    fun loadImages(
        home: Path,
        config: Map<String, Any>,
        meta: FirmwareMetadata,
        isBootRom: Boolean,
        bootRomBytes: ByteArray?,
        allowCustom: Boolean,
        rawImageSpec: CustomFirmwareConfig.RawImageSpec?,
        log: Logger,
        flashImage: ReadOnlyImagePort? = null,
    ): LoadedArtifacts {
        val file = config["firmwareFile"] as? String ?: ""
        val loadedRaw = rawImageSpec?.let { CustomFirmwareConfig.loadRawImage(home, it) }
        val image = if (file.isNotBlank()) {
            val resolvedFirmware = home.resolve(file)
            try {
                FirmwareImage(meta, resolvedFirmware, allowCustomFirmware = allowCustom)
            } catch (err: Throwable) {
                val issue = FirmwareGuidance.diagnose(err, home, config, explicitTarget = resolvedFirmware)
                    ?: FirmwareGuidance.buildIssue(
                        kind = if (Files.isRegularFile(resolvedFirmware)) {
                            FirmwareGuidance.Kind.INVALID_FIRMWARE_DAT
                        } else {
                            FirmwareGuidance.Kind.MISSING_FIRMWARE_DAT
                        },
                        home = home,
                        config = config,
                        explicitTarget = resolvedFirmware,
                        detailMessage = err.message ?: "Failed to load firmware image: $resolvedFirmware",
                    )
                throw InvalidFirmwareArgumentException(issue, err)
            }
        } else {
            null
        }
        val loader = when {
            loadedRaw != null -> null
            image != null -> try {
                image.decode("boot")
            } catch (err: Throwable) {
                val resolvedFirmware = home.resolve(file)
                val issue = FirmwareGuidance.diagnose(err, home, config, explicitTarget = resolvedFirmware)
                    ?: FirmwareGuidance.buildIssue(
                        kind = FirmwareGuidance.Kind.INVALID_FIRMWARE_DAT,
                        home = home,
                        config = config,
                        explicitTarget = resolvedFirmware,
                        detailMessage = err.message ?: "Failed to decode firmware boot section",
                    )
                throw InvalidFirmwareArgumentException(issue, err)
            }
            isBootRom -> null
            else -> {
                val nor = flashImage ?: throw InvalidFirmwareArgumentException(
                    FirmwareGuidance.buildIssue(
                        kind = FirmwareGuidance.Kind.MISSING_NOR_FLASH,
                        home = home,
                        config = config,
                        detailMessage = "Direct-from-flash boot requires flash.image ReadOnlyImagePort (nor-flash.bin)",
                    ),
                )
                val bootSection = meta.sections.singleOrNull { it.name == "boot" }
                val bootOffset = bootSection?.let { (it.offset - FirmwareImage.DAT_HEADER_SIZE).toLong() } ?: 0x20000L
                val bootSize = bootSection?.size ?: 0x20000
                val bootBytes = ByteArray(bootSize)
                val buf = ByteBuffer.wrap(bootBytes)
                nor.read(bootOffset, buf)
                require(!buf.hasRemaining()) { "Incomplete boot.bin read from NOR Flash at 0x${bootOffset.toString(16)}" }
                log.info(
                    "[FLASH-BOOT] Loaded boot.bin ({} bytes) directly from NOR Flash @ 0x{} (norSha256={})",
                    bootSize,
                    bootOffset.toString(16),
                    nor.sha256,
                )
                bootBytes
            }
        }
        val customContracts = linkedMapOf<String, Any?>()
        if (image == null && loadedRaw == null && flashImage != null) {
            customContracts["norFlashBoot"] = linkedMapOf<String, Any?>(
                "mode" to if (isBootRom) "boot-rom-nor" else "flash-direct",
                "norLength" to flashImage.length,
                "norSha256" to flashImage.sha256,
            )
        }
        if (allowCustom && image != null) {
            log.debug(
                "[CUSTOM-FW] Custom/patched DAT enabled: file={} actualSha256={} stockSha256={} modified={} seg0Len=0x{} seg0Checksum=0x{}",
                file,
                image.actualSha256,
                meta.sha256,
                image.customFirmwareLoaded,
                image.seg0Length.toString(16),
                image.seg0Checksum.toString(16),
            )
            customContracts["customFirmware"] = linkedMapOf<String, Any?>(
                "enabled" to true,
                "actualSha256" to image.actualSha256,
                "stockSha256" to meta.sha256,
                "modified" to image.customFirmwareLoaded,
                "seg0Length" to image.seg0Length,
                "seg0Checksum" to image.seg0Checksum,
            )
        }
        if (loadedRaw != null) {
            log.info(
                "[CUSTOM-FW] Direct raw ARM32 image enabled: file={} bytes={} load=0x{} entry=0x{} sha256={} dtb={} initrd={}",
                loadedRaw.spec.file,
                loadedRaw.imageBytes.size,
                loadedRaw.spec.loadAddress.toString(16),
                loadedRaw.spec.entry.toString(16),
                loadedRaw.imageSha256,
                loadedRaw.spec.dtbFile ?: "none",
                loadedRaw.spec.initrdFile ?: "none",
            )
            customContracts["customRawImage"] = linkedMapOf<String, Any?>(
                "enabled" to true,
                "file" to loadedRaw.spec.file,
                "bytes" to loadedRaw.imageBytes.size,
                "sha256" to loadedRaw.imageSha256,
                "loadAddress" to loadedRaw.spec.loadAddress,
                "entry" to loadedRaw.spec.entry,
                "cpsr" to loadedRaw.spec.cpsr,
                "dtbAddress" to loadedRaw.spec.dtbAddress,
                "dtbSha256" to loadedRaw.dtbSha256,
                "initrdAddress" to loadedRaw.spec.initrdAddress,
                "initrdSha256" to loadedRaw.initrdSha256,
            )
        }
        val firmwareSha = loadedRaw?.imageSha256 ?: image?.actualSha256 ?: flashImage?.sha256 ?: meta.sha256
        val bootMode = when {
            loadedRaw != null -> "custom-raw-image"
            image == null && !isBootRom -> "flash-direct"
            else -> meta.bootMode
        }
        return LoadedArtifacts(
            meta = meta,
            isBootRom = isBootRom,
            bootRomBytes = bootRomBytes,
            loadedRaw = loadedRaw,
            image = image,
            loader = loader,
            customContracts = customContracts,
            firmwareSha = firmwareSha,
            bootMode = bootMode,
        )
    }

    fun isStage2XipTraceAllowed(
        isBootRom: Boolean,
        selectedDevices: List<String>,
        executionMode: String,
        config: Map<String, Any>,
        relaxedMode: Boolean,
    ): Boolean {
        if (!isBootRom || "soc-flash" !in selectedDevices) return false
        if (relaxedMode) return true
        return executionMode == "discovery" &&
            (config["discovery"] as? Map<*, *>)?.get("bootRomColdStart") in STAGE2_XIP_DISCOVERY_PROFILES
    }

    fun validateBootMemoryRegions(layout: MemoryLayout, artifacts: LoadedArtifacts) {
        if (artifacts.loadedRaw != null) return
        if (artifacts.isBootRom) {
            val romSpec = checkNotNull(artifacts.meta.bootRom)
            layout.check(romSpec.address, checkNotNull(artifacts.bootRomBytes).size.toLong(), 5)
            if (artifacts.meta.loaderMemoryBytes > 0L) {
                layout.check(0, artifacts.meta.loaderMemoryBytes, 7)
            }
        } else {
            layout.check(0, checkNotNull(artifacts.loader).size.toLong(), 7)
        }
    }

    fun writeInitialBootImages(backend: ArmCpuBackend, artifacts: LoadedArtifacts) {
        if (artifacts.loadedRaw != null) return
        if (artifacts.isBootRom) {
            backend.writeMemory(checkNotNull(artifacts.meta.bootRom).address, checkNotNull(artifacts.bootRomBytes))
        } else {
            backend.writeMemory(0, checkNotNull(artifacts.loader))
        }
    }

    fun applyDynarmicResetVectors(dyn: Dynarmic, numCores: Int, artifacts: LoadedArtifacts): Pair<Long, Long> {
        val initialPc = artifacts.loadedRaw?.spec?.entry ?: artifacts.meta.entry
        val initialCpsr = artifacts.loadedRaw?.spec?.cpsr ?: artifacts.meta.cpsr.toLong()
        for (c in 0 until numCores) {
            dyn.setCpsr(c, initialCpsr)
            dyn.setReg(c, 15, initialPc)
        }
        return initialPc to initialCpsr
    }

    fun mapStage2XipIfNeeded(
        backend: ArmCpuBackend,
        meta: FirmwareMetadata,
        flashImage: ReadOnlyImagePort,
        enableStage2Xip: Boolean,
    ): Pair<Long, Long>? {
        if (!enableStage2Xip) return null
        val flashAperture = meta.devices.single { it.port == "soc-flash.aperture" }
        val xipBase = STAGE2_XIP_BASE
        val xipSize = STAGE2_XIP_SIZE
        require(xipBase >= flashAperture.address && xipBase + xipSize <= flashAperture.address + flashAperture.size) {
            "NOR Flash Stage-2 XIP page outside soc-flash.aperture"
        }
        val xipPage = ByteArray(xipSize.toInt())
        val xipBuf = ByteBuffer.wrap(xipPage)
        flashImage.read(xipBase - flashAperture.address, xipBuf)
        require(!xipBuf.hasRemaining()) { "Incomplete NOR Flash Stage-2 XIP page read" }
        backend.map(xipBase, xipSize, 5)
        backend.writeMemory(xipBase, xipPage)
        return xipBase to xipSize
    }

    fun mapMmioAndRelaxedAperture(
        backend: ArmCpuBackend,
        layout: MemoryLayout,
        router: MmioRouter,
        stage2XipRange: Pair<Long, Long>?,
        relaxedFallback: RelaxedPeripheralFallback?,
    ) {
        val mmioRanges = stage2XipRange?.let { (xipBase, xipSize) ->
            val xipEnd = xipBase + xipSize
            router.pageRanges().flatMap { (base, size) ->
                val end = base + size
                when {
                    end <= xipBase || base >= xipEnd -> listOf(base to size)
                    else -> buildList {
                        if (base < xipBase) add(base to (xipBase - base))
                        if (end > xipEnd) add(xipEnd to (end - xipEnd))
                    }
                }
            }
        } ?: router.pageRanges()
        mmioRanges.forEach { (base, size) -> backend.mmio(base, size) }
        if (relaxedFallback != null) {
            val occupied = buildList {
                layout.regions().forEach { add(it.address to (it.address + it.size)) }
                router.pageRanges().forEach { (b, s) -> add(b to (b + s)) }
                stage2XipRange?.let { (b, s) -> add(b to (b + s)) }
            }
            RelaxedPeripheralFallback.subtractIntervals(
                RelaxedPeripheralFallback.PERIPHERAL_APERTURE_BASE,
                RelaxedPeripheralFallback.PERIPHERAL_APERTURE_END,
                occupied,
            ).forEach { (base, size) -> backend.mmio(base, size) }
        }
    }

    fun loadRawImageAndConfigureRegisters(
        dyn: Dynarmic,
        layout: MemoryLayout,
        loadedRaw: CustomFirmwareConfig.LoadedRawImage,
    ) {
        layout.check(loadedRaw.spec.loadAddress, loadedRaw.imageBytes.size.toLong(), 5)
        dyn.writeMemory(loadedRaw.spec.loadAddress, loadedRaw.imageBytes)
        loadedRaw.dtbBytes?.let { dtb ->
            val addr = checkNotNull(loadedRaw.spec.dtbAddress)
            layout.check(addr, dtb.size.toLong(), 1)
            dyn.writeMemory(addr, dtb)
        }
        loadedRaw.initrdBytes?.let { initrd ->
            val addr = checkNotNull(loadedRaw.spec.initrdAddress)
            layout.check(addr, initrd.size.toLong(), 1)
            dyn.writeMemory(addr, initrd)
        }
        dyn.setCpsr(0, loadedRaw.spec.cpsr)
        dyn.setReg(0, 15, loadedRaw.spec.entry)
        dyn.setReg(0, 0, loadedRaw.spec.r0)
        dyn.setReg(0, 1, loadedRaw.spec.r1)
        dyn.setReg(0, 2, loadedRaw.spec.r2)
        loadedRaw.spec.sp?.let { dyn.setReg(0, 13, it) }
    }
}
