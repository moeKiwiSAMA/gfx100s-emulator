package com.github.nyabanban.fuji.emulator.desktop.session

import com.github.nyabanban.fuji.emulator.api.DmaMemory
import com.github.nyabanban.fuji.emulator.desktop.ImageDmaMemory
import com.github.nyabanban.fuji.emulator.api.MemoryLayout
import com.github.nyabanban.fuji.emulator.api.StatefulComponent
import com.github.nyabanban.fuji.emulator.cpu.ArmCoreSnapshot
import com.github.nyabanban.fuji.emulator.cpu.ArmCpuBackend
import com.github.nyabanban.fuji.emulator.cpu.Dynarmic
import com.github.nyabanban.fuji.emulator.cpu.DynarmicCluster
import com.github.nyabanban.fuji.emulator.cpu.WordExclusive
import com.github.nyabanban.fuji.emulator.desktop.AdditionalRamPlan
import com.github.nyabanban.fuji.emulator.desktop.EmulatorSnapshot
import com.github.nyabanban.fuji.emulator.desktop.IdleClock
import com.github.nyabanban.fuji.emulator.desktop.InstructionBudget
import com.github.nyabanban.fuji.emulator.desktop.PromotedDiscoverySnapshotMigration
import com.github.nyabanban.fuji.emulator.desktop.SdSnapshotInsertion
import com.github.nyabanban.fuji.emulator.desktop.checkImageIrq106Snapshot
import com.github.nyabanban.fuji.emulator.desktop.configuredComponentProfiles
import com.github.nyabanban.fuji.emulator.firmware.SdTransactionContracts
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.DiscoveryBus
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.MmioRouter
import com.github.nyabanban.fuji.emulator.runtime.MmioTrace
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.HexFormat
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal data class SnapshotBudgetSelection(
    val limit: Long,
    val attempts: Long,
    val tick: Long,
)

/**
 * `EmulatorSnapshot` v1 state packing, validation, parallel RAM SHA-256 verification,
 * component migration orchestration, and restore codec.
 */
internal object SessionSnapshotCodec {
    private const val SPARSE_RAM_THRESHOLD_BYTES = 64L * 1024L * 1024L
    private const val SPARSE_CHUNK_BYTES = 64 * 1024
    private const val SPARSE_ENCODING = "sparse-gz-v1"
    private const val SPARSE_MAGIC = 0x46554A53 // "FUJS"

    interface SaveContext {
        val host: ComponentHost
        val backend: ArmCpuBackend
        val layout: MemoryLayout
        val router: MmioRouter
        val dynCluster: DynarmicCluster?
        val clusterProfile: String
        val mmuProfile: String
        val prefetchAbortProfile: String?
        val exceptionProfile: String
        val wordExclusive: WordExclusive?
        val idleClock: IdleClock?
        val activeIds: Set<String>
        val firmwareId: String
        val firmwareSha: String
        val executionMode: String
        val additionalRamReceipt: Map<String, Any?>?
        val discoveryBus: DiscoveryBus?
        val attempts: Long
        val virtualTick: Long
        val pc: Long
        val initialBudget: Long
        val effectiveBudget: Long
        val budgetSelection: SnapshotBudgetSelection?
        val reads: Long
        val writes: Long
        val cbarInput: Long?
        val cbarReadCount: Long
        val cbarReads: List<Map<String, Any>>
        val undefinedExceptionProfile: String
        val undefinedCount: Long
        val undefinedEntries: List<Map<String, Any>>
        val irqCount: Long
        val irqEntries: List<Map<String, Any>>
        val irqLineCount: Long
        val irqLines: List<Map<String, Any?>>
        val checkpointCount: Long
        val checkpoints: List<Map<String, Long>>
        val traceObservedMap: Map<String, Long>
        val transactions: MmioTrace
    }

    interface RestoreContext {
        val host: ComponentHost
        val backend: ArmCpuBackend
        val layout: MemoryLayout
        val router: MmioRouter
        val dynCluster: DynarmicCluster?
        val clusterProfile: String
        val mmuProfile: String
        val prefetchAbortProfile: String?
        val exceptionProfile: String
        val wordExclusive: WordExclusive?
        val idleClock: IdleClock?
        val activeIds: Set<String>
        val firmwareId: String?
        val firmwareSha: String?
        val executionMode: String
        val discoveryBus: DiscoveryBus?
        val cbarInput: Long?
        val undefinedExceptionProfile: String
        val transactions: MmioTrace
        val cbarReads: ArrayDeque<Map<String, Any>>
        val undefinedEntries: ArrayDeque<Map<String, Any>>
        val checkpoints: ArrayDeque<Map<String, Long>>
        val traceIndices: Map<Long, Int>
        val traceObserved: LongArray

        fun setExecutionCounters(
            attempts: Long,
            virtualTick: Long,
            pc: Long,
            budget: Long,
            budgetSelection: SnapshotBudgetSelection?,
        )

        fun selectAdditionalRamFromSnapshot(plan: AdditionalRamPlan, receiptAttempts: Long, receiptTick: Long)

        fun setMmioAndDiagnosticCounts(
            reads: Long,
            writes: Long,
            cbarReadCount: Long,
            undefinedCount: Long,
            irqCount: Long,
            irqLineCount: Long,
            checkpointCount: Long,
        )

        fun clearIrqEntries()
        fun addIrqEntry(entry: Map<String, Any>)
        fun clearIrqLines()
        fun addIrqLine(tick: Long, line: Int, high: Boolean, cpsr: Long, pcVirtual: Long, core: Int?)
        fun onPostBackendFlush()
    }

    fun save(directory: Path, ctx: SaveContext): Map<String, Any?> {
        require(!Files.exists(directory, NOFOLLOW_LINKS)) {
            "Snapshot directory already exists: $directory"
        }
        val loadedComponents = ctx.host.components()
        val statefulComponents = loadedComponents.map { item ->
            val stateful = item.component as? StatefulComponent
                ?: error("Component ${item.descriptor.id} does not implement StatefulComponent")
            item to stateful
        }
        Files.createDirectories(directory)
        val ramDir = Files.createDirectory(directory.resolve(EmulatorSnapshot.RAM_DIR))
        val cpuDir = Files.createDirectory(directory.resolve(EmulatorSnapshot.CPU_DIR))
        val imageRam = ctx.host.ports.find("cpu.memory", DmaMemory::class.java) as? ImageDmaMemory
        val imageWindows = imageRam?.saveWindows(ramDir).orEmpty()

        val coreFiles = ArrayList<Map<String, Any>>(4)
        val cpuSection: Map<String, Any?> = when {
            ctx.dynCluster != null -> {
                val snap = ctx.dynCluster!!.saveSnapshot()
                linkedMapOf(
                    "mode" to "four-context",
                    "engine" to "dynarmic",
                    "clusterProfile" to ctx.clusterProfile,
                    "mmuProfile" to ctx.mmuProfile,
                    "prefetchAbortProfile" to ctx.prefetchAbortProfile,
                    "cluster" to snap.metadata,
                    "coreContextFiles" to coreFiles,
                    "wordExclusive" to ctx.wordExclusive?.saveSnapshot(),
                    "mmu" to null,
                    "idleClock" to ctx.idleClock?.saveSnapshot(),
                ).apply {
                    if (ctx.exceptionProfile.isNotEmpty()) put("exceptionProfile", ctx.exceptionProfile)
                }
            }
            else -> {
                val dyn = ctx.backend as Dynarmic
                val architectural = ArmCoreSnapshot.capture(dyn, 0)
                linkedMapOf(
                    "mode" to "single-core",
                    "engine" to "dynarmic",
                    "clusterProfile" to "",
                    "mmuProfile" to ctx.mmuProfile,
                    "prefetchAbortProfile" to ctx.prefetchAbortProfile,
                    "singleCore" to architectural,
                    "coreContextFiles" to coreFiles,
                    "wordExclusive" to ctx.wordExclusive?.saveSnapshot(),
                    "mmu" to null,
                    "idleClock" to ctx.idleClock?.saveSnapshot(),
                ).apply {
                    if (ctx.exceptionProfile.isNotEmpty()) put("exceptionProfile", ctx.exceptionProfile)
                }
            }
        }

        var totalRamBytes = 0L
        val ramBuffer = ByteArray(4 * 1024 * 1024)
        val sparseChunk = ByteArray(SPARSE_CHUNK_BYTES)
        val ramRows = ctx.layout.regions().map { region ->
            totalRamBytes = Math.addExact(totalRamBytes, region.size)
            if (region.size > SPARSE_RAM_THRESHOLD_BYTES) {
                val fileName = EmulatorSnapshot.regionFileName(region.address, region.size, region.id)
                    .removeSuffix(".bin") + ".sparse.gz"
                val target = ramDir.resolve(fileName)
                val digest = MessageDigest.getInstance("SHA-256")
                var nonZeroChunks = 0L
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { rawOut ->
                    DigestOutputStream(BufferedOutputStream(rawOut, 1 shl 20), digest).use { digOut ->
                        GZIPOutputStream(digOut, 1 shl 16).use { gzOut ->
                            DataOutputStream(gzOut).use { dataOut ->
                                dataOut.writeInt(SPARSE_MAGIC)
                                dataOut.writeLong(region.size)
                                dataOut.writeInt(SPARSE_CHUNK_BYTES)
                                var offset = 0L
                                while (offset < region.size) {
                                    val chunkSize = minOf(SPARSE_CHUNK_BYTES.toLong(), region.size - offset).toInt()
                                    ctx.backend.readMemoryInto(region.address + offset, sparseChunk, 0, chunkSize)
                                    if (!isAllZero(sparseChunk, chunkSize)) {
                                        dataOut.writeLong(offset)
                                        dataOut.writeInt(chunkSize)
                                        dataOut.write(sparseChunk, 0, chunkSize)
                                        nonZeroChunks++
                                    }
                                    offset += chunkSize
                                }
                                dataOut.writeLong(-1L)
                            }
                        }
                    }
                }
                val fileSize = Files.size(target)
                linkedMapOf(
                    "id" to region.id,
                    "address" to region.address,
                    "size" to region.size,
                    "fileSize" to fileSize,
                    "encoding" to SPARSE_ENCODING,
                    "nonZeroChunks" to nonZeroChunks,
                    "permissions" to region.permissions,
                    "file" to "${EmulatorSnapshot.RAM_DIR}/$fileName",
                    "sha256" to HexFormat.of().formatHex(digest.digest()),
                )
            } else {
                val fileName = EmulatorSnapshot.regionFileName(region.address, region.size, region.id)
                val target = ramDir.resolve(fileName)
                val digest = MessageDigest.getInstance("SHA-256")
                BufferedOutputStream(
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    1 shl 20,
                ).use { output ->
                    var offset = 0L
                    while (offset < region.size) {
                        val chunkSize = minOf(ramBuffer.size.toLong(), region.size - offset).toInt()
                        ctx.backend.readMemoryInto(region.address + offset, ramBuffer, 0, chunkSize)
                        output.write(ramBuffer, 0, chunkSize)
                        digest.update(ramBuffer, 0, chunkSize)
                        offset += chunkSize
                    }
                }
                linkedMapOf(
                    "id" to region.id,
                    "address" to region.address,
                    "size" to region.size,
                    "permissions" to region.permissions,
                    "file" to "${EmulatorSnapshot.RAM_DIR}/$fileName",
                    "sha256" to HexFormat.of().formatHex(digest.digest()),
                )
            }
        }

        val componentEntries = linkedMapOf<String, Any?>()
        for ((item, stateful) in statefulComponents) {
            componentEntries[item.descriptor.id] = linkedMapOf(
                "schemaVersion" to stateful.snapshotSchemaVersion,
                "descriptorVersion" to item.descriptor.version,
                "abi" to item.descriptor.abi,
                "active" to (item.descriptor.id in ctx.activeIds),
                "profile" to item.config["profile"],
                "jarSha256" to item.jarSha256,
                "snapshot" to stateful.saveSnapshot(),
            )
        }
        val componentsPayload = linkedMapOf(
            "schema" to EmulatorSnapshot.SCHEMA,
            "format" to EmulatorSnapshot.FORMAT,
            "components" to componentEntries,
        )
        val (componentsSize, componentsSha256) = Formats.writeJsonWithSha256(
            directory.resolve(EmulatorSnapshot.COMPONENTS_FILE),
            componentsPayload,
        )

        val sessionSection = linkedMapOf<String, Any?>(
            "attempts" to ctx.attempts,
            "virtualTick" to ctx.virtualTick,
            "pc" to ctx.pc,
            "initialBudget" to ctx.initialBudget,
            "effectiveBudget" to ctx.effectiveBudget,
            "budgetSelection" to ctx.budgetSelection?.let {
                linkedMapOf("limit" to it.limit, "attempts" to it.attempts, "tick" to it.tick)
            },
            "reads" to ctx.reads,
            "writes" to ctx.writes,
            "routerAccessSequence" to ctx.router.accessSequenceCount(),
            "cbarInput" to ctx.cbarInput,
            "cbarReadCount" to ctx.cbarReadCount,
            "cbarReads" to ctx.cbarReads,
            "undefinedExceptionProfile" to ctx.undefinedExceptionProfile,
            "undefinedCount" to ctx.undefinedCount,
            "undefinedEntries" to ctx.undefinedEntries,
            "irqCount" to ctx.irqCount,
            "irqEntries" to ctx.irqEntries,
            "irqLineCount" to ctx.irqLineCount,
            "irqLines" to ctx.irqLines,
            "checkpointCount" to ctx.checkpointCount,
            "checkpoints" to ctx.checkpoints,
            "traceObserved" to ctx.traceObservedMap,
            "transactions" to ctx.transactions.saveSnapshot(),
        )
        val manifest = linkedMapOf<String, Any?>(
            "schema" to EmulatorSnapshot.SCHEMA,
            "format" to EmulatorSnapshot.FORMAT,
            "firmware" to linkedMapOf("id" to ctx.firmwareId, "sha256" to ctx.firmwareSha),
            "executionMode" to ctx.executionMode,
            "activeComponentIds" to ctx.activeIds.sorted(),
            "activeComponentProfiles" to configuredComponentProfiles(ctx.host, ctx.activeIds),
            "componentsFile" to linkedMapOf(
                "file" to EmulatorSnapshot.COMPONENTS_FILE,
                "size" to componentsSize,
                "sha256" to componentsSha256,
            ),
            "session" to sessionSection,
            "cpu" to cpuSection,
            "memory" to linkedMapOf(
                "baseProfile" to ctx.layout.profile,
                "additionalRamSelected" to (ctx.additionalRamReceipt != null),
                "additionalRamReceipt" to ctx.additionalRamReceipt,
                "regions" to ramRows,
            ).apply { if (imageWindows.isNotEmpty()) put("imageDmaWindows", imageWindows) },
            "discovery" to ctx.discoveryBus?.saveSnapshot(),
        )
        val manifestPath = directory.resolve(EmulatorSnapshot.MANIFEST_FILE)
        val (manifestSize, manifestSha256) = Formats.writeJsonWithSha256(manifestPath, manifest)
        val complete = linkedMapOf<String, Any?>(
            "schema" to EmulatorSnapshot.SCHEMA,
            "format" to EmulatorSnapshot.FORMAT,
            "status" to "complete",
            "attempts" to ctx.attempts,
            "virtualTick" to ctx.virtualTick,
            "pc" to ctx.pc,
            "ramRegionCount" to ramRows.size,
            "totalRamBytes" to totalRamBytes,
            "componentCount" to componentEntries.size,
            "manifestFile" to EmulatorSnapshot.MANIFEST_FILE,
            "manifestSize" to manifestSize,
            "manifestSha256" to manifestSha256,
        )
        Formats.writeJson(directory.resolve(EmulatorSnapshot.COMPLETE_FILE), complete)
        return complete
    }

    @Suppress("UNCHECKED_CAST")
    fun restore(
        directory: Path,
        options: EmulatorSnapshot.RestoreOptions,
        ctx: RestoreContext,
    ): Map<String, Any?> {
        val completePath = directory.resolve(EmulatorSnapshot.COMPLETE_FILE)
        require(Files.isRegularFile(completePath, NOFOLLOW_LINKS)) {
            "Missing snapshot completion marker: $completePath"
        }
        val complete = EmulatorSnapshot.readJsonObject(completePath)
        require(
            (complete["schema"] as? Number)?.toInt() == EmulatorSnapshot.SCHEMA &&
                complete["format"] == EmulatorSnapshot.FORMAT &&
                complete["status"] == "complete",
        ) { "Invalid or incomplete emulator snapshot marker" }
        val manifestPath = directory.resolve(EmulatorSnapshot.MANIFEST_FILE)
        EmulatorSnapshot.verifyRegularFileSha256(
            manifestPath,
            (complete["manifestSize"] as Number).toLong(),
            complete["manifestSha256"] as String,
        )
        val manifest = EmulatorSnapshot.readJsonObject(manifestPath)
        require(
            (manifest["schema"] as? Number)?.toInt() == EmulatorSnapshot.SCHEMA &&
                manifest["format"] == EmulatorSnapshot.FORMAT,
        ) { "Unsupported emulator snapshot manifest" }
        val firmwareMap = manifest["firmware"] as? Map<*, *> ?: error("Missing snapshot firmware identity")
        require(firmwareMap["id"] == ctx.firmwareId && firmwareMap["sha256"] == ctx.firmwareSha) {
            "Snapshot firmware identity differs from initialized session"
        }
        require(manifest["executionMode"] == ctx.executionMode) {
            "Snapshot executionMode differs from initialized session"
        }

        val savedActiveIds = ((manifest["activeComponentIds"] as? List<*>) ?: error("Missing activeComponentIds"))
            .map { it.toString() }.toSet()
        if (options.allowPromotedDiscoveryRegions) {
            require(savedActiveIds.all { it in ctx.activeIds }) { "Active component removed on promoted restore" }
        } else {
            require(savedActiveIds == ctx.activeIds) { "Active component set differs from snapshot" }
        }
        val savedProfiles = ((manifest["activeComponentProfiles"] as? Map<*, *>) ?: error("Missing activeComponentProfiles"))
            .entries.associate { it.key.toString() to it.value.toString() }
        val liveProfiles = configuredComponentProfiles(ctx.host, ctx.activeIds)
        if (options.unreadSd1 != null) {
            require(
                "soc-sd-host" in ctx.activeIds && "soc-gic" in ctx.activeIds &&
                    ctx.host.components().any { it.descriptor.id == "sdcard-reader" } &&
                    liveProfiles["soc-sd-host"] == SdTransactionContracts.ADDRESS_PROFILE,
            ) {
                "Unread SD1 replacement requires the v7 host, reader and GIC"
            }
        }
        options.sdInsertion?.let {
            require(!options.allowPromotedDiscoveryRegions && savedActiveIds == ctx.activeIds && "soc-sd-host" in ctx.activeIds)
            it.checkRoutes(savedProfiles, liveProfiles, manifest["discovery"])
        }
        for ((componentId, savedProfile) in savedProfiles) {
            if (options.sdInsertion != null && componentId == "soc-sd-host") continue
            if (componentId !in options.allowCompatibleProfileChanges) {
                require(liveProfiles[componentId] == savedProfile) {
                    "Active component profile differs for $componentId: saved=$savedProfile live=${liveProfiles[componentId]}"
                }
            }
        }
        for ((componentId, liveProfile) in liveProfiles) {
            if (componentId !in savedProfiles && componentId !in options.allowCompatibleProfileChanges && !options.allowPromotedDiscoveryRegions) {
                error("Unexpected newly profiled active component $componentId=$liveProfile")
            }
        }

        val componentsFileMeta = manifest["componentsFile"] as? Map<*, *> ?: error("Missing componentsFile metadata")
        val componentsPath = directory.resolve(componentsFileMeta["file"].toString())
        EmulatorSnapshot.verifyRegularFileSha256(
            componentsPath,
            (componentsFileMeta["size"] as Number).toLong(),
            componentsFileMeta["sha256"] as String,
        )
        val componentsRoot = EmulatorSnapshot.readJsonObject(componentsPath)
        require(
            (componentsRoot["schema"] as? Number)?.toInt() == EmulatorSnapshot.SCHEMA &&
                componentsRoot["format"] == EmulatorSnapshot.FORMAT,
        ) { "Invalid components snapshot header" }
        val savedComponents = componentsRoot["components"] as? Map<*, *> ?: error("Missing components map")
        val savedDiscovery = manifest["discovery"] as? Map<*, *>
        val loadedComponents = ctx.host.components()
        val statefulPairs = loadedComponents.mapNotNull { item ->
            val componentId = item.descriptor.id
            val stateful = item.component as? StatefulComponent
                ?: error("Component $componentId does not implement StatefulComponent")
            val entry = savedComponents[componentId] as? Map<*, *>
            if (entry == null) {
                if (componentId == "soc-jpeg") {
                    require(
                        componentId !in savedActiveIds &&
                            (item.config["profile"] == "disabled" || options.allowPromotedDiscoveryRegions),
                    ) {
                        "Missing saved state for component $componentId"
                    }
                    return@mapNotNull null
                }
                if (componentId !in PromotedDiscoverySnapshotMigration.PROMOTED_OPTIONAL_COMPONENTS) {
                    error("Missing saved state for component $componentId")
                }
                val isDisabled = componentId !in ctx.activeIds && item.component.additionalMmioPorts().isEmpty()
                if (isDisabled) {
                    return@mapNotNull null
                }
                require(options.allowPromotedDiscoveryRegions) {
                    "Missing saved state for active component $componentId"
                }
                val discovery = checkNotNull(savedDiscovery) {
                    "Missing discovery snapshot for promoted component $componentId"
                }
                val migrated = PromotedDiscoverySnapshotMigration.migrateOptionalComponent(
                    componentId,
                    stateful.saveSnapshot(),
                    discovery,
                )
                return@mapNotNull Triple(item, stateful, migrated)
            }
            require((entry["abi"] as? Number)?.toInt() == item.descriptor.abi) {
                "Component ABI mismatch for $componentId"
            }
            require((entry["schemaVersion"] as? Number)?.toInt() == stateful.snapshotSchemaVersion) {
                "Component snapshot schema version mismatch for $componentId"
            }
            val componentSnapshot = entry["snapshot"] as? Map<*, *> ?: error("Missing snapshot payload for $componentId")
            val payload = componentSnapshot.entries.associate { it.key.toString() to it.value }
            val replacement = options.unreadSd1
            val sdMigrated = if (replacement == null) options.sdInsertion?.payload(componentId, payload, stateful)
            else when (componentId) {
                "sdcard-reader" -> replacement.readerPayload(payload, stateful.saveSnapshot())
                "soc-sd-host" -> payload.also { replacement.checkHost(it, stateful.saveSnapshot()) }
                "soc-gic" -> payload.also { SdSnapshotInsertion.checkSdInterrupts(it) }
                else -> null
            }
            val promotedMigrated = if (sdMigrated == null && options.allowPromotedDiscoveryRegions) {
                when {
                    componentId == "soc-system-control" ->
                        PromotedDiscoverySnapshotMigration.migrateSystemControl(payload, stateful.saveSnapshot(), savedDiscovery)
                    componentId == "soc-dma" ->
                        PromotedDiscoverySnapshotMigration.migrateDma(payload, stateful.saveSnapshot(), savedDiscovery)
                    componentId in PromotedDiscoverySnapshotMigration.PROMOTED_OPTIONAL_COMPONENTS &&
                        payload["enabled"] == false &&
                        componentId in ctx.activeIds ->
                        PromotedDiscoverySnapshotMigration.migrateOptionalComponent(
                            componentId,
                            stateful.saveSnapshot(),
                            checkNotNull(savedDiscovery) {
                                "Missing discovery snapshot for promoted component $componentId"
                            },
                        )
                    else -> null
                }
            } else null
            Triple(item, stateful, sdMigrated ?: promotedMigrated ?: payload)
        }

        val restoreDiscovery = ctx.discoveryBus?.prepareSnapshotRestore(
            checkNotNull(savedDiscovery) { "Missing discovery snapshot" },
            options.allowPromotedDiscoveryRegions,
        )
        require(restoreDiscovery != null || savedDiscovery == null) { "Unexpected discovery snapshot" }
        checkImageIrq106Snapshot(savedDiscovery, statefulPairs.singleOrNull { it.first.descriptor.id == "soc-gic" }?.third)
        val memorySection = manifest["memory"] as? Map<*, *> ?: error("Missing memory section")
        require(memorySection["baseProfile"] == ctx.layout.profile) { "Base memory profile differs from snapshot" }
        val windowHashes = if ("imageDmaWindows" in memorySection) {
            memorySection["imageDmaWindows"] as? Map<*, *> ?: error("Invalid imaging DMA snapshot")
        } else emptyMap<String, String>()
        val imageRam = ctx.host.ports.find("cpu.memory", DmaMemory::class.java) as? ImageDmaMemory
        require(imageRam != null || windowHashes.isEmpty()) { "Snapshot requires the imaging DMA memory profile" }
        val restoreImageWindows = imageRam?.prepareWindowRestore(directory.resolve(EmulatorSnapshot.RAM_DIR), windowHashes)
        val ramRows = (memorySection["regions"] as? List<*>) ?: error("Missing memory regions")
        val parsedRamRows = ramRows.map { rawRow ->
            val row = rawRow as? Map<*, *> ?: error("Invalid RAM region row")
            val encoding = (row["encoding"] as? String) ?: "raw"
            val expectedFileSize = if (encoding == SPARSE_ENCODING) {
                (row["fileSize"] as Number).toLong()
            } else {
                (row["size"] as Number).toLong()
            }
            Triple(
                directory.resolve(row["file"].toString()),
                expectedFileSize,
                row["sha256"] as String,
            )
        }
        val ramVerifyThreads = minOf(8, maxOf(1, Runtime.getRuntime().availableProcessors() / 2), maxOf(1, parsedRamRows.size))
        if (ramVerifyThreads <= 1 || parsedRamRows.size <= 1) {
            for ((regionPath, expectedSize, expectedSha) in parsedRamRows) {
                EmulatorSnapshot.verifyRegularFileSha256(regionPath, expectedSize, expectedSha)
            }
        } else {
            val pool = Executors.newFixedThreadPool(ramVerifyThreads)
            try {
                val futures = parsedRamRows.map { (regionPath, expectedSize, expectedSha) ->
                    pool.submit(
                        Callable {
                            EmulatorSnapshot.verifyRegularFileSha256(regionPath, expectedSize, expectedSha)
                        },
                    )
                }
                for (f in futures) {
                    try {
                        f.get()
                    } catch (e: ExecutionException) {
                        throw (e.cause ?: e)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        }

        val cpuSection = manifest["cpu"] as? Map<*, *> ?: error("Missing cpu section")
        val expectedMode = if (ctx.dynCluster != null) "four-context" else "single-core"
        require(
            cpuSection["mode"] == expectedMode &&
                cpuSection["clusterProfile"] == ctx.clusterProfile &&
                cpuSection["mmuProfile"] == ctx.mmuProfile &&
                cpuSection["prefetchAbortProfile"] == ctx.prefetchAbortProfile &&
                ((cpuSection["exceptionProfile"] as? String).orEmpty() == ctx.exceptionProfile ||
                    (ctx.exceptionProfile.isNotEmpty() && !cpuSection.containsKey("exceptionProfile"))),
        ) {
            "CPU/MMU profile configuration differs from snapshot"
        }
        val coreFilesRaw = (cpuSection["coreContextFiles"] as? List<*>) ?: emptyList<Any>()
        val coreContextPayloads = coreFilesRaw.mapIndexed { expectedCore, rawFile ->
            val fileMeta = rawFile as? Map<*, *> ?: error("Invalid coreContextFiles entry")
            require((fileMeta["core"] as Number).toInt() == expectedCore) { "Core context file order mismatch" }
            val corePath = directory.resolve(fileMeta["file"].toString())
            EmulatorSnapshot.verifyRegularFileSha256(
                corePath,
                (fileMeta["size"] as Number).toLong(),
                fileMeta["sha256"] as String,
            )
            Files.readAllBytes(corePath)
        }

        val sessionSection = manifest["session"] as? Map<*, *> ?: error("Missing session section")
        require(sessionSection["cbarInput"]?.let { (it as Number).toLong() } == ctx.cbarInput) {
            "cbarInput differs from snapshot"
        }
        require(sessionSection["undefinedExceptionProfile"] == ctx.undefinedExceptionProfile) {
            "undefinedExceptionProfile differs from snapshot"
        }
        val nextAttempts = (sessionSection["attempts"] as Number).toLong().also { require(it >= 0L) }
        val nextTick = (sessionSection["virtualTick"] as Number).toLong().also { require(it >= 0L) }
        val nextPc = (sessionSection["pc"] as Number).toLong()
        val savedEffectiveBudget = (sessionSection["effectiveBudget"] as Number).toLong()
        val nextBudget = if (options.overrideBudget != null) {
            val validated = InstructionBudget.parseContinuation(options.overrideBudget)
            require(validated > nextAttempts) { "Override budget must exceed restored attempts ($nextAttempts)" }
            validated
        } else {
            require(savedEffectiveBudget > nextAttempts) { "Snapshot effectiveBudget does not exceed attempts" }
            savedEffectiveBudget
        }
        val nextBudgetSelection = if (options.overrideBudget != null) {
            SnapshotBudgetSelection(nextBudget, nextAttempts, nextTick)
        } else {
            (sessionSection["budgetSelection"] as? Map<*, *>)?.let { sel ->
                SnapshotBudgetSelection(
                    (sel["limit"] as Number).toLong(),
                    (sel["attempts"] as Number).toLong(),
                    (sel["tick"] as Number).toLong(),
                )
            }
        }

        ctx.host.advanceTick(nextTick)
        ctx.setExecutionCounters(nextAttempts, nextTick, nextPc, nextBudget, nextBudgetSelection)

        val savedAdditionalReceipt = (memorySection["additionalRamReceipt"] as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value }
        if (memorySection["additionalRamSelected"] == true) {
            val receipt = checkNotNull(savedAdditionalReceipt) { "Missing additionalRamReceipt for selected additional RAM" }
            val planRaw = receipt["plan"] as? Map<*, *> ?: error("Missing additionalRamReceipt plan")
            ctx.selectAdditionalRamFromSnapshot(
                AdditionalRamPlan.parse(planRaw.entries.associate { it.key.toString() to it.value }),
                (receipt["attempts"] as Number).toLong(),
                (receipt["tick"] as Number).toLong(),
            )
        }
        val liveRegions = ctx.layout.regions()
        require(liveRegions.size == ramRows.size) { "Live RAM region count differs after additional RAM restore" }
        val restoreBuffer = ByteArray(4 * 1024 * 1024)
        liveRegions.forEachIndexed { index, region ->
            val row = ramRows[index] as Map<*, *>
            require(
                row["id"] == region.id &&
                    (row["address"] as Number).toLong() == region.address &&
                    (row["size"] as Number).toLong() == region.size &&
                    (row["permissions"] as Number).toInt() == region.permissions,
            ) {
                "RAM region layout mismatch at index $index (${region.id})"
            }
            val regionPath = directory.resolve(row["file"].toString())
            val encoding = (row["encoding"] as? String) ?: "raw"
            if (encoding == SPARSE_ENCODING) {
                GZIPInputStream(BufferedInputStream(Files.newInputStream(regionPath), 1 shl 16), 1 shl 16).use { gzIn ->
                    DataInputStream(gzIn).use { dataIn ->
                        val magic = dataIn.readInt()
                        require(magic == SPARSE_MAGIC) { "Invalid sparse snapshot magic in $regionPath" }
                        val savedRegionSize = dataIn.readLong()
                        require(savedRegionSize == region.size) { "Sparse snapshot region size mismatch in $regionPath" }
                        val chunkCap = dataIn.readInt()
                        require(chunkCap in 4096..restoreBuffer.size) { "Invalid sparse chunk size $chunkCap in $regionPath" }
                        while (true) {
                            val chunkOffset = dataIn.readLong()
                            if (chunkOffset == -1L) break
                            val chunkLen = dataIn.readInt()
                            require(chunkOffset >= 0L && chunkLen > 0 && chunkLen <= chunkCap && chunkOffset + chunkLen <= region.size) {
                                "Out-of-bounds sparse chunk ($chunkOffset + $chunkLen > ${region.size}) in $regionPath"
                            }
                            dataIn.readFully(restoreBuffer, 0, chunkLen)
                            ctx.backend.writeMemory(region.address + chunkOffset, restoreBuffer, 0, chunkLen, false)
                        }
                    }
                }
            } else {
                Files.newInputStream(regionPath).use { input ->
                    var offset = 0L
                    while (offset < region.size) {
                        val targetChunk = minOf(restoreBuffer.size.toLong(), region.size - offset).toInt()
                        var filled = 0
                        while (filled < targetChunk) {
                            val readNow = input.read(restoreBuffer, filled, targetChunk - filled)
                            check(readNow > 0) { "Unexpected EOF reading snapshot RAM $regionPath" }
                            filled += readNow
                        }
                        ctx.backend.writeMemory(region.address + offset, restoreBuffer, 0, filled, false)
                        offset += filled
                    }
                }
            }
        }

        restoreImageWindows?.invoke()
        val (producerPairs, sinkPairs) = statefulPairs.partition { (item, _, _) -> item.descriptor.id != "soc-gic" }
        for ((_, stateful, payload) in producerPairs) {
            stateful.restoreSnapshot(payload)
        }
        for ((_, stateful, payload) in sinkPairs) {
            stateful.restoreSnapshot(payload)
        }
        restoreDiscovery?.invoke()

        val savedExclusive = cpuSection["wordExclusive"] as? Map<*, *>
        if (ctx.wordExclusive == null) require(savedExclusive == null) { "Unexpected wordExclusive state in snapshot" }
        else ctx.wordExclusive!!.restoreSnapshot(checkNotNull(savedExclusive) { "Missing wordExclusive state in snapshot" })

        when {
            ctx.dynCluster != null -> {
                val clusterMeta = cpuSection["cluster"] as? Map<*, *> ?: error("Missing cluster metadata in snapshot")
                ctx.dynCluster!!.restoreSnapshot(clusterMeta)
            }
            else -> {
                val arch = cpuSection["singleCore"] as? Map<*, *> ?: error("Missing singleCore architectural state")
                ArmCoreSnapshot.restore(ctx.backend as Dynarmic, 0, arch)
            }
        }

        val savedIdle = cpuSection["idleClock"] as? Map<*, *>
        if (ctx.idleClock == null) require(savedIdle == null) { "Unexpected idleClock state in snapshot" }
        else ctx.idleClock!!.restoreSnapshot(checkNotNull(savedIdle) { "Missing idleClock state in snapshot" })

        ctx.backend.flushBlocks()
        ctx.onPostBackendFlush()

        val nextReads = (sessionSection["reads"] as Number).toLong().also { require(it >= 0L) }
        val nextWrites = (sessionSection["writes"] as Number).toLong().also { require(it >= 0L) }
        ctx.router.restoreAccessSequence((sessionSection["routerAccessSequence"] as Number).toLong())
        ctx.transactions.restoreSnapshot(sessionSection["transactions"] as Map<*, *>)

        val nextCbarReadCount = (sessionSection["cbarReadCount"] as Number).toLong().also { require(it >= 0L) }
        restoreMapDeque(sessionSection["cbarReads"], 128, ctx.cbarReads)
        val nextUndefinedCount = (sessionSection["undefinedCount"] as Number).toLong().also { require(it >= 0L) }
        restoreMapDeque(sessionSection["undefinedEntries"], 128, ctx.undefinedEntries)
        val nextIrqCount = (sessionSection["irqCount"] as Number).toLong().also { require(it >= 0L) }
        restoreIrqEntries(sessionSection["irqEntries"], 256, ctx)
        val nextIrqLineCount = (sessionSection["irqLineCount"] as Number).toLong().also { require(it >= 0L) }
        val rawIrqLines = sessionSection["irqLines"] as? List<*> ?: error("Missing irqLines")
        require(rawIrqLines.size <= 512) { "irqLines exceeds bound" }
        ctx.clearIrqLines()
        for (rawEntry in rawIrqLines) {
            val entry = rawEntry as Map<*, *>
            ctx.addIrqLine(
                tick = (entry["tick"] as Number).toLong(),
                line = (entry["line"] as Number).toInt(),
                high = entry["high"] as Boolean,
                cpsr = (entry["cpsr"] as Number).toLong(),
                pcVirtual = (entry["pcVirtual"] as Number).toLong(),
                core = (entry["core"] as? Number)?.toInt(),
            )
        }

        val nextCheckpointCount = (sessionSection["checkpointCount"] as Number).toLong().also { require(it >= 0L) }
        val rawCheckpoints = sessionSection["checkpoints"] as? List<*> ?: error("Missing checkpoints")
        ctx.checkpoints.clear()
        ctx.checkpoints.addAll(
            rawCheckpoints.map { entry ->
                (entry as Map<*, *>).entries.associate { it.key.toString() to (it.value as Number).toLong() }
            },
        )
        ctx.traceObserved.fill(0L)
        val savedTraceCounts = sessionSection["traceObserved"] as? Map<*, *> ?: emptyMap<String, Any>()
        for ((keyRaw, valueRaw) in savedTraceCounts) {
            val parts = keyRaw.toString().split(':')
            if (parts.size == 2) {
                val address = parts[0].toLong()
                val coreIndex = parts[1].toInt()
                val traceSlot = ctx.traceIndices[address]
                if (traceSlot != null && coreIndex in 0..3) {
                    ctx.traceObserved[traceSlot * 4 + coreIndex] = (valueRaw as Number).toLong()
                }
            }
        }
        ctx.setMmioAndDiagnosticCounts(
            reads = nextReads,
            writes = nextWrites,
            cbarReadCount = nextCbarReadCount,
            undefinedCount = nextUndefinedCount,
            irqCount = nextIrqCount,
            irqLineCount = nextIrqLineCount,
            checkpointCount = nextCheckpointCount,
        )

        options.sdInsertion?.applyDetect(ctx.host)
        ctx.router.invalidateSchedule()
        return linkedMapOf(
            "schema" to EmulatorSnapshot.SCHEMA,
            "format" to EmulatorSnapshot.FORMAT,
            "restored" to true,
            "attempts" to nextAttempts,
            "virtualTick" to nextTick,
            "pc" to nextPc,
            "effectiveBudget" to nextBudget,
            "ramRegionCount" to liveRegions.size,
            "componentCount" to statefulPairs.size,
            "preferArchitecturalCpuRestore" to options.preferArchitecturalCpuRestore,
            "allowCompatibleProfileChanges" to options.allowCompatibleProfileChanges.sorted(),
            "allowPromotedDiscoveryRegions" to options.allowPromotedDiscoveryRegions,
        ) + (options.sdInsertion?.let { mapOf("sdInsertion" to it.receipt()) } ?: emptyMap()) +
            (options.unreadSd1?.let {
                mapOf(
                    "unreadSd1Replacement" to mapOf(
                        "previousSha256" to it.previousSha256,
                        "selectedSha256" to it.selectedSha256,
                        "commandCount" to 0,
                    ),
                )
            } ?: emptyMap())
    }

    private fun isAllZero(bytes: ByteArray, length: Int): Boolean {
        var i = 0
        while (i < length) {
            if (bytes[i] != 0.toByte()) return false
            i++
        }
        return true
    }

    private fun restoreMapDeque(raw: Any?, limit: Int, target: ArrayDeque<Map<String, Any>>) {
        val parsed = parseMapList(raw, limit)
        target.clear()
        target.addAll(parsed)
    }

    private fun restoreIrqEntries(raw: Any?, limit: Int, ctx: RestoreContext) {
        val parsed = parseMapList(raw, limit)
        ctx.clearIrqEntries()
        for (entry in parsed) ctx.addIrqEntry(entry)
    }

    private fun parseMapList(raw: Any?, limit: Int): List<Map<String, Any>> {
        val list = raw as? List<*> ?: error("Expected list for session deque")
        require(list.size <= limit) { "Session deque exceeds bound $limit" }
        return list.map { entry ->
            (entry as Map<*, *>).entries.associate { (rawKey, rawValue) ->
                val key = rawKey.toString()
                val value = checkNotNull(rawValue)
                val normalized: Any = when {
                    key == "core" || key == "line" || key == "rt" -> (value as Number).toInt()
                    value is Number -> value.toLong()
                    else -> value
                }
                key to normalized
            }
        }
    }
}
