package com.github.nyabanban.fuji.emulator.components.sdcard

import com.github.nyabanban.fuji.emulator.api.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64

class SdcardFactory : ComponentFactory {
    companion object {
        private val LEGACY_SETTING_KEYS = (1..2).flatMap { slot ->
            listOf("slot${slot}Image", "slot${slot}Inserted", "slot${slot}ReadOnly")
        }.toSet()

        private val VALID_BACKENDS = setOf("raw", "qcow2", "directory")
        const val PORT_HOTPLUG = "sdcard-reader.hotplug"
        const val GPIO_CARD_DET_BANK = 10
        const val SLOT1_CARD_DET_PIN = 4 // slot0 (A:\) KEY_CARD_DET, active-LOW
        const val SLOT2_CARD_DET_PIN = 8 // slot1 (B:\) KEY_CARD2_DET, active-LOW
        private val HOTPLUG_SETTING_KEYS = setOf("cardInsertReadyDelayTicks", "syncCardDetectGpio")
        private val ASYNC_IO_SETTING_KEYS = setOf("asyncIo")

        fun cardDetectPinForSlot(slot: Int): Int = when (slot) {
            1 -> SLOT1_CARD_DET_PIN
            2 -> SLOT2_CARD_DET_PIN
            else -> throw IllegalArgumentException("Unsupported SD slot: $slot")
        }

        private fun parseIntegralLong(value: Any?, fieldName: String): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Integral value required for $fieldName" }
            return (value as? Number)?.toLong() ?: throw IllegalArgumentException("Missing numeric value for $fieldName")
        }

        private fun parseIntegralInt(value: Any?, fieldName: String): Int {
            val numberValue = parseIntegralLong(value, fieldName)
            require(numberValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Out of Int range for $fieldName" }
            return numberValue.toInt()
        }

        private fun pathsAliasOrNest(a: Path, aDir: Boolean, b: Path, bDir: Boolean): Boolean {
            if (a == b) return true
            if (Files.exists(a, NOFOLLOW_LINKS) && Files.exists(b, NOFOLLOW_LINKS) && Files.isSameFile(a, b)) return true
            if (aDir && b.startsWith(a)) return true
            if (bDir && a.startsWith(b)) return true
            return false
        }
    }

    private data class SlotSelection(
        val slot: Int,
        val backend: String,
        val readOnly: Boolean,
        val capacityBytes: Long,
        val backingFile: String,
        val targetPath: Path,
        val lockPath: Path,
        val sidecarPath: Path?,
        val backingPath: Path?,
    )

    private data class SlotRestorePlan(
        val slotIndex: Int,
        val baselineCaptures: Map<Long, ByteArray>,
        val sectorsToWrite: Map<Long, ByteArray>,
        val nextDirtySectors: Set<Long>,
    )

    override val descriptor = ComponentDescriptor("sdcard-reader", "0.0.1", settings = (1..2).flatMap { slot -> listOf(
        Setting("slot${slot}Image", ValueKind.TEXT, "sdcard-reader-slot-$slot.img", live = false),
        Setting("slot${slot}Inserted", ValueKind.BOOLEAN, false, live = true),
        Setting("slot${slot}ReadOnly", ValueKind.BOOLEAN, true, live = true),
        Setting("slot${slot}Backend", ValueKind.TEXT, "raw", choices = listOf("raw", "qcow2", "directory"), live = false),
        Setting("slot${slot}CapacityBytes", ValueKind.INTEGER, 0L, minimum = 0.0, live = false),
        Setting("slot${slot}BackingFile", ValueKind.TEXT, "", live = false),
    ) } + listOf(
        Setting("cardInsertReadyDelayTicks", ValueKind.INTEGER, 0L, minimum = 0.0, maximum = 1_000_000_000.0, live = false),
        Setting("syncCardDetectGpio", ValueKind.BOOLEAN, true, live = false),
        Setting("asyncIo", ValueKind.BOOLEAN, true, live = false),
    ))

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Sdcard(context, config)

    private inner class Sdcard(context: ComponentContext, config: Map<String, Any>) :
        ScalarComponent("sdcard-reader", context, config), CheckedBlockStoragePort, HotPlugCardControlPort, StatefulComponent {
        private val stores = arrayOfNulls<SdBlockStore>(2)
        private val leases = arrayOfNulls<SdImageLease>(2)
        private val originalSectors = Array(2) { HashMap<Long, ByteArray>() }
        private val dirtySectors = Array(2) { HashSet<Long>() }
        private val defaultReadyDelayTicks = ((config["cardInsertReadyDelayTicks"] as? Number)?.toLong() ?: 0L).also {
            require(it in 0L..1_000_000_000L) { "cardInsertReadyDelayTicks out of range: $it" }
        }
        private val syncCardDetectGpio = (config["syncCardDetectGpio"] as? Boolean) ?: true
        private val explicitSyncCardDetectGpio = config.containsKey("syncCardDetectGpio")
        private val asyncIoEnabled = (config["asyncIo"] as? Boolean) ?: true
        private val explicitAsyncIo = config.containsKey("asyncIo")
        private val cardInsertedRuntime = BooleanArray(2)
        private val insertedAtTick = arrayOfNulls<Long>(2)
        private val readyAtTick = arrayOfNulls<Long>(2)
        private val earlyAccessAttempts = LongArray(2)
        private var closed = false

        private fun slotBackend(slot: Int): String = (values["slot${slot}Backend"] as? String) ?: "raw"
        private fun slotCapacityBytes(slot: Int): Long = (values["slot${slot}CapacityBytes"] as? Number)?.toLong() ?: 0L
        private fun slotBackingFile(slot: Int): String = (values["slot${slot}BackingFile"] as? String) ?: ""

        private fun usesHotPlugSettings(): Boolean =
            defaultReadyDelayTicks != 0L || (explicitSyncCardDetectGpio && syncCardDetectGpio)

        private fun usesAsyncIoSetting(): Boolean = explicitAsyncIo || !asyncIoEnabled

        private fun usesExtendedSettings(): Boolean = usesHotPlugSettings() || usesAsyncIoSetting() || (1..2).any { slot ->
            slotBackend(slot) != "raw" || slotCapacityBytes(slot) != 0L || slotBackingFile(slot).isNotEmpty()
        }

        private fun effectiveSettingValue(setting: Setting): Any = when (setting.key) {
            "slot1Backend" -> slotBackend(1)
            "slot2Backend" -> slotBackend(2)
            "slot1CapacityBytes" -> slotCapacityBytes(1)
            "slot2CapacityBytes" -> slotCapacityBytes(2)
            "slot1BackingFile" -> slotBackingFile(1)
            "slot2BackingFile" -> slotBackingFile(2)
            "cardInsertReadyDelayTicks" -> defaultReadyDelayTicks
            "syncCardDetectGpio" -> syncCardDetectGpio
            "asyncIo" -> asyncIoEnabled
            else -> values[setting.key] ?: setting.default
        }

        private fun buildSlotSelection(slot: Int, readOnly: Boolean): SlotSelection {
            val name = values.getValue("slot${slot}Image") as String
            SdPathSecurity.validateNormalizedRelative(name, "SD image path")
            val target = context.dataPath(name)
            val backend = slotBackend(slot)
            val cap = slotCapacityBytes(slot)
            val backingRel = slotBackingFile(slot)
            val lockPath = target.resolveSibling(target.fileName.toString() + ".lock")
            val sidecarPath = if (backend == "directory") DirectoryFat32BlockStore.sidecarPathFor(target) else null
            val backingPath = if (backingRel.isNotEmpty()) {
                val resolved = target.parent.resolve(Path.of(backingRel)).normalize()
                val root = context.directory.toAbsolutePath().normalize()
                require(resolved.startsWith(root) && resolved != root) {
                    "SD backing file escapes component directory"
                }
                resolved
            } else null
            return SlotSelection(slot, backend, readOnly, cap, backingRel, target, lockPath, sidecarPath, backingPath)
        }

        private fun canAutoCreateMissingTarget(sel: SlotSelection, allowAutoCreateForSlots: Set<Int>): Boolean {
            if (sel.slot !in allowAutoCreateForSlots || sel.readOnly) return false
            if (Files.exists(sel.targetPath, NOFOLLOW_LINKS)) return false
            return when (sel.backend) {
                "directory" -> true
                "qcow2" -> sel.backingFile.isEmpty() && sel.capacityBytes > 0L
                "raw" -> sel.capacityBytes > 0L
                else -> false
            }
        }

        private fun validateSelections(
            selected: List<SlotSelection>,
            checkEmptyLockForSlots: Set<Int>,
            allowAutoCreateForSlots: Set<Int> = emptySet(),
        ) {
            data class ReservedEntry(val path: Path, val isDirectory: Boolean, val allowMissingDirectory: Boolean = false)
            val reserved = ArrayList<ReservedEntry>()
            for (sel in selected) {
                val autoCreate = canAutoCreateMissingTarget(sel, allowAutoCreateForSlots)
                reserved.add(ReservedEntry(sel.targetPath, sel.backend == "directory", allowMissingDirectory = autoCreate && sel.backend == "directory"))
                reserved.add(ReservedEntry(sel.lockPath, false))
                sel.sidecarPath?.let { reserved.add(ReservedEntry(it, false)) }
            }

            for ((index, entry) in reserved.withIndex()) {
                SdPathSecurity.requireNoSymlinks(entry.path, "SD image/lock")
                if (entry.isDirectory) {
                    if (!entry.allowMissingDirectory || Files.exists(entry.path, NOFOLLOW_LINKS)) {
                        require(Files.isDirectory(entry.path, NOFOLLOW_LINKS)) {
                            "SD directory backend target must exist as a directory"
                        }
                    }
                } else {
                    require(!Files.exists(entry.path, NOFOLLOW_LINKS) || Files.isRegularFile(entry.path, NOFOLLOW_LINKS)) {
                        "SD image/lock must be a regular file"
                    }
                }
                for (other in reserved.take(index)) {
                    require(!pathsAliasOrNest(entry.path, entry.isDirectory, other.path, other.isDirectory)) {
                        "SD image/lock alias refused"
                    }
                }
            }

            for (sel in selected) {
                val willAutoCreate = canAutoCreateMissingTarget(sel, allowAutoCreateForSlots)
                if (!willAutoCreate) {
                    when (sel.backend) {
                        "raw" -> {
                            require(
                                Files.isRegularFile(sel.targetPath, NOFOLLOW_LINKS) &&
                                    Files.size(sel.targetPath) > 0L &&
                                    Files.size(sel.targetPath) % SECTOR_BYTES == 0L,
                            ) { "SD image must exist and be sector aligned" }
                            if (sel.capacityBytes > 0L) {
                                require(Files.size(sel.targetPath) == sel.capacityBytes) {
                                    "Raw SD image size differs from configured capacityBytes"
                                }
                            }
                            if (!sel.readOnly) {
                                require(Files.isWritable(sel.targetPath)) { "SD raw image is not writable on host filesystem" }
                            }
                        }
                        "qcow2" -> {
                            require(
                                Files.isRegularFile(sel.targetPath, NOFOLLOW_LINKS) &&
                                    Files.size(sel.targetPath) >= Qcow2BlockStore.HEADER_V3_LENGTH,
                            ) { "QCOW2 SD image must exist as a valid regular file" }
                            if (!sel.readOnly) {
                                require(Files.isWritable(sel.targetPath)) { "SD QCOW2 image is not writable on host filesystem" }
                            }
                            sel.backingPath?.let { bp ->
                                SdPathSecurity.requireNoSymlinks(bp, "SD backing file")
                                require(Files.isRegularFile(bp, NOFOLLOW_LINKS)) {
                                    "QCOW2 backing file must exist as a regular file"
                                }
                                for (other in selected) {
                                    if (!other.readOnly) {
                                        require(!pathsAliasOrNest(bp, false, other.targetPath, other.backend == "directory")) {
                                            "QCOW2 backing file cannot alias a writable SD slot"
                                        }
                                    }
                                    require(!pathsAliasOrNest(bp, false, other.lockPath, false)) {
                                        "QCOW2 backing file cannot alias an SD lock file"
                                    }
                                }
                            }
                        }
                        "directory" -> {
                            require(Files.isDirectory(sel.targetPath, NOFOLLOW_LINKS)) {
                                "SD directory backend target must exist as a directory"
                            }
                            if (!sel.readOnly) {
                                require(Files.isWritable(sel.targetPath)) { "SD directory is not writable on host filesystem" }
                            }
                        }
                    }
                }
                if (sel.slot in checkEmptyLockForSlots) {
                    require(!Files.exists(sel.lockPath, NOFOLLOW_LINKS) || Files.size(sel.lockPath) == 0L) {
                        "SD lease file must be empty"
                    }
                }
            }
        }

        private fun openSlotMedia(sel: SlotSelection, allowAutoCreate: Boolean = false) {
            val slotIdx = sel.slot - 1
            if (allowAutoCreate && !sel.readOnly && !Files.exists(sel.targetPath, NOFOLLOW_LINKS)) {
                sel.targetPath.parent?.let { Files.createDirectories(it) }
                when (sel.backend) {
                    "directory" -> Files.createDirectories(sel.targetPath)
                    "qcow2" -> Qcow2BlockStore.create(sel.targetPath, sel.capacityBytes)
                    "raw" -> {
                        FileChannel.open(sel.targetPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { ch ->
                            ch.write(ByteBuffer.wrap(byteArrayOf(0)), sel.capacityBytes - 1L)
                        }
                    }
                }
            }
            val lease = if (sel.backend == "directory") {
                SdImageLease.acquireDirectory(sel.targetPath, sel.lockPath)
            } else {
                SdImageLease.acquire(sel.targetPath, sel.lockPath)
            }
            try {
                val rawStore = when (sel.backend) {
                    "raw" -> RawBlockStore(sel.targetPath, sel.readOnly, sel.capacityBytes)
                    "qcow2" -> Qcow2BlockStore.open(
                        path = sel.targetPath,
                        rootDirectory = context.directory,
                        readOnly = sel.readOnly,
                        expectedCapacityBytes = sel.capacityBytes,
                        overrideBackingFile = sel.backingFile,
                    )
                    "directory" -> DirectoryFat32BlockStore.open(
                        directory = sel.targetPath,
                        readOnly = sel.readOnly,
                        configuredCapacityBytes = sel.capacityBytes,
                    )
                    else -> error("Unsupported SD backend: ${sel.backend}")
                }
                val openedStore: SdBlockStore = if (asyncIoEnabled) {
                    AsyncBufferedBlockStore(rawStore, slot = sel.slot)
                } else {
                    rawStore
                }
                leases[slotIdx] = lease
                stores[slotIdx] = openedStore
            } catch (error: Throwable) {
                runCatching { lease.close() }
                throw error
            }
        }

        init {
            try {
                for (slot in 1..2) {
                    val backend = slotBackend(slot)
                    require(backend in VALID_BACKENDS) { "Unsupported SD slot $slot backend: $backend" }
                    val cap = slotCapacityBytes(slot)
                    require(cap >= 0L && cap % SECTOR_BYTES == 0L) {
                        "SD slot $slot capacityBytes must be non-negative and 512-byte sector aligned"
                    }
                    val backing = slotBackingFile(slot)
                    if (backing.isNotEmpty()) {
                        require(backend == "qcow2") { "SD slot $slot backingFile is only supported for qcow2 backend" }
                        SdPathSecurity.validateNormalizedRelative(backing, "SD backing file path")
                    }
                }

                val selected = (1..2).filter { values["slot${it}Inserted"] == true }.map { slot ->
                    buildSlotSelection(slot, values["slot${slot}ReadOnly"] == true)
                }

                // Validate every path BEFORE acquiring any lease (atomic failure, preserves existing lease semantics).
                validateSelections(selected, checkEmptyLockForSlots = selected.map { it.slot }.toSet())

                for (sel in selected) {
                    val slotIdx = sel.slot - 1
                    openSlotMedia(sel)
                    cardInsertedRuntime[slotIdx] = true
                    val startTick = context.clock.ticks().coerceAtLeast(0L)
                    insertedAtTick[slotIdx] = startTick
                    readyAtTick[slotIdx] = Math.addExact(startTick, defaultReadyDelayTicks)
                }

                context.ports.register(id, "sdcard-reader", BlockStoragePort::class.java, this)
                context.ports.register(id, PORT_HOTPLUG, HotPlugCardControlPort::class.java, this)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        private fun driveCardDetectGpio(slot: Int, inserted: Boolean) {
            val gpio = context.ports.find("soc-gpio.inputs", GpioInputPort::class.java) ?: return
            // Active-LOW: inserted -> LOW (false), ejected -> HIGH (true)
            gpio.setInputLevel(GPIO_CARD_DET_BANK, cardDetectPinForSlot(slot), !inserted)
        }

        override fun start() {
            require(!closed) { "SD card reader is closed" }
            if (syncCardDetectGpio) {
                for (slot in 1..2) {
                    driveCardDetectGpio(slot, cardInsertedRuntime[slot - 1])
                }
            }
        }

        override fun validateSettings(settings: Map<String, Any>) {
            require(!closed) { "SD card reader is closed" }
            for (setting in descriptor.settings) {
                if (!settings.containsKey(setting.key)) continue
                val validated = setting.validate(settings.getValue(setting.key))
                if (!setting.live) {
                    require(effectiveSettingValue(setting) == validated) {
                        "Restart-only setting ${setting.key} cannot be changed live"
                    }
                }
            }
            val needsOpenSlots = HashSet<Int>()
            val selectionsToCheck = ArrayList<SlotSelection>()
            for (slot in 1..2) {
                val idx = slot - 1
                val nextInserted = (settings["slot${slot}Inserted"] as? Boolean) ?: (values["slot${slot}Inserted"] == true)
                val nextReadOnly = (settings["slot${slot}ReadOnly"] as? Boolean) ?: (values["slot${slot}ReadOnly"] == true)
                val activeStore = stores[idx]
                if (nextInserted && activeStore == null) {
                    needsOpenSlots.add(slot)
                    selectionsToCheck.add(buildSlotSelection(slot, nextReadOnly))
                } else if (activeStore != null) {
                    selectionsToCheck.add(buildSlotSelection(slot, nextReadOnly))
                    if (nextInserted && !nextReadOnly && activeStore.readOnly) {
                        require(activeStore.canWrite()) { "SD slot $slot media is not writable on host filesystem" }
                    }
                }
            }
            if (needsOpenSlots.isNotEmpty()) {
                validateSelections(
                    selectionsToCheck,
                    checkEmptyLockForSlots = needsOpenSlots,
                    allowAutoCreateForSlots = needsOpenSlots,
                )
            }
        }

        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
            val newlyOpenedSlots = ArrayList<Int>()
            try {
                for (slot in 1..2) {
                    val idx = slot - 1
                    val nextInserted = (settings["slot${slot}Inserted"] as? Boolean) ?: (values["slot${slot}Inserted"] == true)
                    val nextReadOnly = (settings["slot${slot}ReadOnly"] as? Boolean) ?: (values["slot${slot}ReadOnly"] == true)
                    if (nextInserted && stores[idx] == null) {
                        openSlotMedia(buildSlotSelection(slot, nextReadOnly), allowAutoCreate = true)
                        newlyOpenedSlots.add(idx)
                    }
                }
            } catch (error: Throwable) {
                for (idx in newlyOpenedSlots) {
                    runCatching { stores[idx]?.close() }
                    runCatching { leases[idx]?.close() }
                    stores[idx] = null
                    leases[idx] = null
                }
                throw error
            }

            val prevInserted = booleanArrayOf(cardInsertedRuntime[0], cardInsertedRuntime[1])
            val prevReadOnly = booleanArrayOf(values["slot1ReadOnly"] == true, values["slot2ReadOnly"] == true)
            values = values + settings

            for (slot in 1..2) {
                val idx = slot - 1
                val nextInserted = values["slot${slot}Inserted"] == true
                val nextReadOnly = values["slot${slot}ReadOnly"] == true
                val activeStore = stores[idx]
                val now = context.clock.ticks().coerceAtLeast(0L)

                if (activeStore != null && activeStore.readOnly != nextReadOnly) {
                    activeStore.setReadOnly(nextReadOnly)
                }

                if (!prevInserted[idx] && nextInserted) {
                    val readyAt = Math.addExact(now, defaultReadyDelayTicks)
                    cardInsertedRuntime[idx] = true
                    insertedAtTick[idx] = now
                    readyAtTick[idx] = readyAt
                    driveCardDetectGpio(slot, inserted = true)
                    context.events.publish(
                        ComponentEvent(
                            id,
                            "hotplug",
                            now,
                            mapOf("slot" to slot, "action" to "insert", "readyAtTick" to readyAt, "readOnly" to nextReadOnly),
                        ),
                    )
                } else if (prevInserted[idx] && !nextInserted) {
                    activeStore?.flush()
                    cardInsertedRuntime[idx] = false
                    insertedAtTick[idx] = null
                    readyAtTick[idx] = null
                    driveCardDetectGpio(slot, inserted = false)
                    context.events.publish(
                        ComponentEvent(
                            id,
                            "hotplug",
                            now,
                            mapOf("slot" to slot, "action" to "eject"),
                        ),
                    )
                } else if (prevReadOnly[idx] != nextReadOnly) {
                    context.events.publish(
                        ComponentEvent(
                            id,
                            "write-protect",
                            now,
                            mapOf("slot" to slot, "readOnly" to nextReadOnly),
                        ),
                    )
                }
            }
        }

        override fun insertCard(slot: Int, readyDelayTicks: Long): SdSlotHotPlugStatus {
            require(!closed) { "SD card reader is closed" }
            require(slot in 1..2) { "Unsupported SD slot: $slot" }
            val idx = slot - 1
            require(stores[idx] != null) { "Cannot hot-insert SD slot $slot without configured backing media" }
            val delay = if (readyDelayTicks >= 0L) readyDelayTicks else defaultReadyDelayTicks
            require(delay in 0L..1_000_000_000L) { "SD hot-insert readyDelayTicks out of range: $delay" }
            val now = context.clock.ticks().coerceAtLeast(0L)
            val readyAt = Math.addExact(now, delay)
            cardInsertedRuntime[idx] = true
            values = values + ("slot${slot}Inserted" to true)
            insertedAtTick[idx] = now
            readyAtTick[idx] = readyAt
            driveCardDetectGpio(slot, inserted = true)
            context.events.publish(
                ComponentEvent(
                    id,
                    "hotplug",
                    now,
                    mapOf("slot" to slot, "action" to "insert", "readyAtTick" to readyAt),
                ),
            )
            return slotHotPlugStatus(slot)
        }

        override fun ejectCard(slot: Int): SdSlotHotPlugStatus {
            require(!closed) { "SD card reader is closed" }
            require(slot in 1..2) { "Unsupported SD slot: $slot" }
            val idx = slot - 1
            stores[idx]?.flush()
            cardInsertedRuntime[idx] = false
            values = values + ("slot${slot}Inserted" to false)
            insertedAtTick[idx] = null
            readyAtTick[idx] = null
            driveCardDetectGpio(slot, inserted = false)
            val now = context.clock.ticks().coerceAtLeast(0L)
            context.events.publish(
                ComponentEvent(
                    id,
                    "hotplug",
                    now,
                    mapOf("slot" to slot, "action" to "eject"),
                ),
            )
            return slotHotPlugStatus(slot)
        }

        override fun isMediaReady(slot: Int): Boolean {
            require(slot in 1..2) { "Unsupported SD slot: $slot" }
            val idx = slot - 1
            if (stores[idx] == null || !cardInsertedRuntime[idx]) return false
            val readyAt = readyAtTick[idx] ?: return false
            return context.clock.ticks() >= readyAt
        }

        override fun slotHotPlugStatus(slot: Int): SdSlotHotPlugStatus {
            require(slot in 1..2) { "Unsupported SD slot: $slot" }
            val idx = slot - 1
            return SdSlotHotPlugStatus(
                slot = slot,
                cardDetectAsserted = cardInsertedRuntime[idx],
                mediaReady = isMediaReady(slot),
                insertedAtTick = insertedAtTick[idx],
                readyAtTick = readyAtTick[idx],
                earlyAccessAttempts = earlyAccessAttempts[idx],
            )
        }

        private fun store(slot: Int): SdBlockStore {
            require(slot in 1..2)
            val idx = slot - 1
            val active = stores[idx] ?: error("SD slot $slot empty")
            if (!cardInsertedRuntime[idx]) error("SD slot $slot ejected")
            return active
        }

        private fun readyStore(slot: Int): SdBlockStore {
            val active = store(slot)
            val idx = slot - 1
            val readyAt = readyAtTick[idx] ?: 0L
            val now = context.clock.ticks()
            if (now < readyAt) {
                earlyAccessAttempts[idx]++
                error("SD slot $slot detected (CARD_DET=0) but media not yet mounted/ready (tick=$now < readyAt=$readyAt)")
            }
            return active
        }

        override fun size(slot: Int): Long? {
            require(slot in 1..2)
            val idx = slot - 1
            return if (cardInsertedRuntime[idx]) stores[idx]?.capacityBytes else null
        }

        override fun readOnly(slot: Int): Boolean {
            store(slot)
            return values["slot${slot}ReadOnly"] == true
        }

        override fun checkAccess(slot: Int, offset: Long, length: Long, write: Boolean) {
            val active = readyStore(slot)
            active.checkRange(offset, length)
            require(!write || !readOnly(slot)) { "SD slot write-protected" }
        }

        override fun read(slot: Int, offset: Long, target: ByteBuffer): Int {
            val active = readyStore(slot)
            return active.read(offset, target)
        }

        override fun write(slot: Int, offset: Long, source: ByteBuffer): Int {
            require(values["slot${slot}ReadOnly"] == false) { "SD slot write-protected" }
            val active = readyStore(slot)
            val count = source.remaining()
            active.checkRange(offset, count.toLong())
            if (count > 0 && active.capacityBytes <= MAX_WRITABLE_SYNC_BYTES) {
                val slotIndex = slot - 1
                val firstSector = offset / SECTOR_BYTES
                val lastSector = (offset + count - 1L) / SECTOR_BYTES
                for (sectorIndex in firstSector..lastSector) {
                    if (!originalSectors[slotIndex].containsKey(sectorIndex)) {
                        originalSectors[slotIndex][sectorIndex] = active.readSector(sectorIndex)
                    }
                    dirtySectors[slotIndex].add(sectorIndex)
                }
            }
            return active.write(offset, source)
        }

        override fun flush(slot: Int) {
            if (slot !in 1..2) return
            val idx = slot - 1
            if (!cardInsertedRuntime[idx] || values["slot${slot}ReadOnly"] == true) return
            stores[idx]?.flushIfConsistent()
        }

        override fun state(): Map<String, Any?> {
            val base = super.state() + mapOf(
                "slot1Bytes" to size(1),
                "slot2Bytes" to size(2),
                "sdHostProtocol" to "unimplemented",
            )
            return if (!usesExtendedSettings()) {
                base
            } else {
                base + mapOf(
                    "slot1Backend" to slotBackend(1),
                    "slot2Backend" to slotBackend(2),
                    "slot1Diagnostics" to stores[0]?.diagnostics(),
                    "slot2Diagnostics" to stores[1]?.diagnostics(),
                    "cardInsertReadyDelayTicks" to defaultReadyDelayTicks,
                    "syncCardDetectGpio" to syncCardDetectGpio,
                    "asyncIo" to asyncIoEnabled,
                    "hotPlugSlots" to (1..2).map { s ->
                        val st = slotHotPlugStatus(s)
                        linkedMapOf(
                            "slot" to st.slot,
                            "cardDetectAsserted" to st.cardDetectAsserted,
                            "mediaReady" to st.mediaReady,
                            "insertedAtTick" to st.insertedAtTick,
                            "readyAtTick" to st.readyAtTick,
                            "earlyAccessAttempts" to st.earlyAccessAttempts,
                        )
                    },
                )
            }
        }

        private fun snapshotScalarValues(): Map<String, Any> {
            val out = LinkedHashMap<String, Any>()
            if (!usesExtendedSettings()) {
                for (setting in descriptor.settings) {
                    if (setting.key in LEGACY_SETTING_KEYS) {
                        out[setting.key] = effectiveSettingValue(setting)
                    }
                }
            } else {
                for (setting in descriptor.settings) {
                    if (setting.key in HOTPLUG_SETTING_KEYS && !usesHotPlugSettings()) continue
                    if (setting.key in ASYNC_IO_SETTING_KEYS && !usesAsyncIoSetting()) continue
                    out[setting.key] = effectiveSettingValue(setting)
                }
            }
            return out
        }

        override fun saveSnapshot(): Map<String, Any?> {
            require(!closed) { "SD card reader is closed" }
            val slotEntries = (1..2).map { slot ->
                val slotIndex = slot - 1
                val activeStore = stores[slotIndex]
                val slotInserted = cardInsertedRuntime[slotIndex]
                val slotReadOnly = values["slot${slot}ReadOnly"] == true
                if (!slotInserted) {
                    linkedMapOf<String, Any?>(
                        "slot" to slot,
                        "inserted" to false,
                        "readOnly" to slotReadOnly,
                        "sizeBytes" to null,
                        "sha256" to null,
                    )
                } else {
                    require(activeStore != null) { "SD slot $slot insertion state inconsistent" }
                    require(activeStore.isOpen) { "SD slot $slot channel is closed" }
                    activeStore.flush()
                    val mediaSize = activeStore.capacityBytes
                    require(mediaSize > 0L && mediaSize % SECTOR_BYTES == 0L) { "SD slot $slot media size invalid" }
                    val mediaSha256 = activeStore.computeLogicalSha256()
                    val entry = linkedMapOf<String, Any?>(
                        "slot" to slot,
                        "inserted" to true,
                        "readOnly" to slotReadOnly,
                        "sizeBytes" to mediaSize,
                        "sha256" to mediaSha256,
                    )
                    if (!slotReadOnly && mediaSize <= MAX_WRITABLE_SYNC_BYTES) {
                        val modified = linkedMapOf<String, String>()
                        for (sectorIndex in dirtySectors[slotIndex].sorted()) {
                            val currentSector = activeStore.readSector(sectorIndex)
                            val baselineSector = originalSectors[slotIndex][sectorIndex]
                            if (baselineSector == null || !currentSector.contentEquals(baselineSector)) {
                                modified[sectorIndex.toString()] = Base64.getEncoder().encodeToString(currentSector)
                            }
                        }
                        entry["modifiedSectors"] = modified
                    }
                    entry
                }
            }
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to snapshotScalarValues(),
                "slots" to slotEntries,
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            require(!closed) { "SD card reader is closed" }
            require(data.keys == setOf("schema", "componentId", "scalarValues", "slots")) {
                "Unexpected sdcard-reader snapshot keys"
            }
            require(parseIntegralInt(data["schema"], "schema") == snapshotSchemaVersion) {
                "Unsupported sdcard-reader snapshot schema"
            }
            require(data["componentId"] == id) { "Mismatched sdcard-reader componentId" }
            val rawScalars = data["scalarValues"] as? Map<*, *> ?: throw IllegalArgumentException("Missing sdcard-reader scalarValues")
            val allSettingKeys = descriptor.settings.map { it.key }.toSet()
            val preAsyncIoKeys = allSettingKeys - ASYNC_IO_SETTING_KEYS
            val preHotPlugExtendedKeys = preAsyncIoKeys - HOTPLUG_SETTING_KEYS
            val asyncWithoutHotPlugKeys = allSettingKeys - HOTPLUG_SETTING_KEYS
            require(rawScalars.keys.all { key -> key is String && key in allSettingKeys }) {
                "Unknown scalar setting key in snapshot for $id"
            }
            require(
                rawScalars.keys == LEGACY_SETTING_KEYS ||
                    rawScalars.keys == preHotPlugExtendedKeys ||
                    rawScalars.keys == preAsyncIoKeys ||
                    rawScalars.keys == asyncWithoutHotPlugKeys ||
                    rawScalars.keys == allSettingKeys,
            ) {
                "Missing scalar setting key in snapshot for $id"
            }
            for (setting in descriptor.settings) {
                val rawSetting = if (rawScalars.containsKey(setting.key)) {
                    rawScalars[setting.key] ?: throw IllegalArgumentException("Null setting ${setting.key} in $id")
                } else {
                    setting.default
                }
                val validated = setting.validate(rawSetting)
                require(effectiveSettingValue(setting) == validated) {
                    "Restart-only setting ${setting.key} differs in snapshot for $id"
                }
            }

            val rawSlots = data["slots"] as? List<*> ?: throw IllegalArgumentException("Invalid slots list in sdcard-reader snapshot")
            require(rawSlots.size == 2) { "Expected two slot entries in sdcard-reader snapshot" }
            val restorePlans = (1..2).map { slot ->
                val slotIndex = slot - 1
                val slotMap = rawSlots[slotIndex] as? Map<*, *> ?: throw IllegalArgumentException("Invalid slot $slot map in snapshot")
                require(parseIntegralInt(slotMap["slot"], "slot") == slot) { "Slot index mismatch in sdcard-reader snapshot" }
                val snapshotInserted = slotMap["inserted"] as? Boolean ?: throw IllegalArgumentException("Missing inserted flag for slot $slot")
                val snapshotReadOnly = slotMap["readOnly"] as? Boolean ?: throw IllegalArgumentException("Missing readOnly flag for slot $slot")
                val expectedInserted = values["slot${slot}Inserted"] == true
                val expectedReadOnly = values["slot${slot}ReadOnly"] == true
                require(snapshotInserted == expectedInserted && snapshotReadOnly == expectedReadOnly) {
                    "Slot $slot media insertion or protection differs from snapshot"
                }
                if (!snapshotInserted) {
                    require(slotMap.keys == setOf("slot", "inserted", "readOnly", "sizeBytes", "sha256")) {
                        "Unexpected uninserted slot $slot snapshot keys"
                    }
                    require(slotMap["sizeBytes"] == null && slotMap["sha256"] == null) {
                        "Uninserted slot $slot must have null sizeBytes and sha256"
                    }
                    SlotRestorePlan(slotIndex, emptyMap(), emptyMap(), emptySet())
                } else {
                    val activeStore = stores[slotIndex] ?: throw IllegalArgumentException("Missing active store for slot $slot")
                    require(activeStore.isOpen) { "SD slot $slot channel is closed" }
                    val baseKeys = setOf("slot", "inserted", "readOnly", "sizeBytes", "sha256")
                    if (snapshotReadOnly) {
                        require(slotMap.keys == baseKeys) { "Unexpected read-only slot $slot snapshot keys" }
                    } else {
                        require(slotMap.keys == baseKeys || slotMap.keys == baseKeys + "modifiedSectors") {
                            "Unexpected writable slot $slot snapshot keys"
                        }
                    }
                    val snapshotSize = parseIntegralLong(slotMap["sizeBytes"], "sizeBytes")
                    val liveSize = activeStore.capacityBytes
                    require(snapshotSize > 0L && snapshotSize % SECTOR_BYTES == 0L && snapshotSize == liveSize) {
                        "Mounted SD media size mismatch for slot $slot"
                    }
                    val snapshotSha256 = slotMap["sha256"] as? String ?: throw IllegalArgumentException("Missing sha256 for slot $slot")
                    require(snapshotSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid sha256 format for slot $slot" }
                    if (snapshotReadOnly || liveSize > MAX_WRITABLE_SYNC_BYTES || !slotMap.containsKey("modifiedSectors")) {
                        require(!slotMap.containsKey("modifiedSectors")) { "Unexpected modifiedSectors for non-syncable slot $slot" }
                        val liveSha256 = activeStore.computeLogicalSha256()
                        require(liveSha256 == snapshotSha256) { "Mounted SD media SHA-256 mismatch for slot $slot" }
                        SlotRestorePlan(slotIndex, emptyMap(), emptyMap(), dirtySectors[slotIndex].toSet())
                    } else {
                        val rawModified = slotMap["modifiedSectors"] as? Map<*, *>
                            ?: throw IllegalArgumentException("Invalid modifiedSectors map for slot $slot")
                        val sectorCount = liveSize / SECTOR_BYTES
                        val parsedModified = LinkedHashMap<Long, ByteArray>()
                        for ((rawSectorKey, rawEncoded) in rawModified) {
                            require(rawSectorKey is String && rawSectorKey.matches(Regex("0|[1-9][0-9]*"))) {
                                "Invalid modified sector key for slot $slot"
                            }
                            val sectorIndex = rawSectorKey.toLongOrNull()
                                ?: throw IllegalArgumentException("Out-of-range sector key for slot $slot")
                            require(sectorIndex in 0L until sectorCount) { "Sector index out of bounds for slot $slot" }
                            val encodedSector = rawEncoded as? String
                                ?: throw IllegalArgumentException("Sector payload must be Base64 string for slot $slot")
                            val decodedBytes = Base64.getDecoder().decode(encodedSector)
                            require(decodedBytes.size == SECTOR_BYTES) { "Decoded sector byte length mismatch for slot $slot" }
                            parsedModified[sectorIndex] = decodedBytes
                        }
                        val predictedSha256 = activeStore.computeLogicalSha256(parsedModified, originalSectors[slotIndex])
                        require(predictedSha256 == snapshotSha256) { "Mounted SD media SHA-256 mismatch for slot $slot" }
                        val baselineCaptures = LinkedHashMap<Long, ByteArray>()
                        val sectorsToWrite = LinkedHashMap<Long, ByteArray>()
                        val nextDirtySectors = HashSet<Long>()
                        val candidateSectors = (parsedModified.keys + originalSectors[slotIndex].keys).sorted()
                        for (sectorIndex in candidateSectors) {
                            val currentDiskSector = activeStore.readSector(sectorIndex)
                            val existingBaseline = originalSectors[slotIndex][sectorIndex]
                            val targetSector = parsedModified[sectorIndex] ?: checkNotNull(existingBaseline)
                            val effectiveBaseline = existingBaseline ?: if (!currentDiskSector.contentEquals(targetSector)) {
                                baselineCaptures[sectorIndex] = currentDiskSector
                                currentDiskSector
                            } else null
                            if (!currentDiskSector.contentEquals(targetSector)) {
                                sectorsToWrite[sectorIndex] = targetSector
                            }
                            if (effectiveBaseline != null && !targetSector.contentEquals(effectiveBaseline)) {
                                nextDirtySectors.add(sectorIndex)
                            }
                        }
                        SlotRestorePlan(slotIndex, baselineCaptures, sectorsToWrite, nextDirtySectors)
                    }
                }
            }

            // All validation succeeded — apply atomically
            for (plan in restorePlans) {
                val activeStore = stores[plan.slotIndex]
                if (activeStore != null && plan.sectorsToWrite.isNotEmpty()) {
                    if (activeStore.capacityBytes > MAX_WRITABLE_SYNC_BYTES) {
                        activeStore.invalidateDigestCache()
                    }
                    originalSectors[plan.slotIndex].putAll(plan.baselineCaptures)
                    for ((sectorIndex, sectorBytes) in plan.sectorsToWrite) {
                        activeStore.writeSector(sectorIndex, sectorBytes)
                    }
                    activeStore.flush()
                }
                dirtySectors[plan.slotIndex].clear()
                dirtySectors[plan.slotIndex].addAll(plan.nextDirtySectors)
            }
        }

        override fun close() {
            closed = true
            stores.forEach { runCatching { it?.close() } }
            leases.forEach { runCatching { it?.close() } }
            // Keep each lease inode stable for checkpoint restoration and later opens.
        }
    }
}
