package com.github.nyabanban.fuji.emulator.components.memory

import com.github.nyabanban.fuji.emulator.api.*

/** Explicit region/permission policy. Byte backing remains the native CPU's shared physical RAM. */
class MemoryFactory : ComponentFactory {
    companion object {
        private val DESCRIPTOR = ComponentDescriptor("system-memory","0.0.1",settings = listOf(
            Setting("profile",ValueKind.TEXT,"loader-low128-experimental-v1",choices = listOf("loader-low128-experimental-v1","loader-workspace-experimental-v1","loader-footer-experimental-v2","loader-kernel-experimental-v3","loader-segments-experimental-v4","kernel-stacks-experimental-v5","kernel-page-table-experimental-v6","kernel-byte-pool-experimental-v7","kernel-trace-pools-experimental-v8","kernel-l2-tables-experimental-v9","kernel-flash-buffer-experimental-v10","kernel-aux-buffer-experimental-v11","kernel-auxiliary-task-buffer-experimental-v12","kernel-auxiliary-task-pair-experimental-v13","kernel-paired-flash-read-buffer-experimental-v14","kernel-config-record-pages-experimental-v15","kernel-runtime-flash-staging-experimental-v16","post-lens-object-loading-experimental-v17","calc-fixed-workspace-experimental-v18","raw-segment2-tail-experimental-v19","fixed-table-row5-buffer-experimental-v20","seven-row-table-buffer-experimental-v21","xlog-first-buffer-experimental-v22","xlog-all-buffers-experimental-v23","flashload-request-buffer-experimental-v24","linux-contiguous-256m-experimental-v1","boot-rom-cold-start-experimental-v1","boot-rom-cold-start-segments-experimental-v2","boot-rom-full-boot-v1"),live = false),
            Setting("spanPolicy",ValueKind.TEXT,"single-region-v1",choices=listOf("single-region-v1","contiguous-compatible-v1"),live=false),
            Setting("additionalRamProfile",ValueKind.TEXT,"",choices=listOf("",AdditionalRamRegions.PROFILE,AdditionalRamRegions.EXEC_CAPABLE_PROFILE),live=false,
                description="Explicit one-time finite RAM addition with profile-selected RW/NX or RWX permissions; zero native backing is HYPOTHESIS"),
        ))
        private val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalars", "additionalRamSelected", "additionalRegions")
        private val REGION_KEYS = setOf("id", "address", "size", "permissions")
        private fun profileRank(profileName: String): Int = when (profileName) {
            "" -> 0
            AdditionalRamRegions.PROFILE -> 1
            AdditionalRamRegions.EXEC_CAPABLE_PROFILE -> 2
            else -> throw IllegalArgumentException("Unknown additional RAM profile")
        }
        private fun snapshotLong(value: Any?, field: String, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Snapshot field $field requires integer" }
            val converted = (value as? Number)?.toLong() ?: throw IllegalArgumentException("Snapshot field $field requires integer")
            require(converted in minimum..maximum) { "Snapshot field $field out of range" }
            return converted
        }
        private fun snapshotInt(value: Any?, field: String, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int =
            snapshotLong(value, field, minimum.toLong(), maximum.toLong()).toInt()
        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")
        private fun snapshotString(value: Any?, field: String): String =
            value as? String ?: throw IllegalArgumentException("Snapshot field $field requires string")
        private fun snapshotList(value: Any?, field: String): List<*> =
            value as? List<*> ?: throw IllegalArgumentException("Snapshot field $field requires list")
        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { key -> key is String }) { "Snapshot field $field requires string keys" }
            return map
        }
    }
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent = Memory(context,config)
    private class Memory(context: ComponentContext, config: Map<String, Any>) : ScalarComponent("system-memory",context,config), ExpandableMemoryLayout, StatefulComponent {
        private data class AdditionalSelection(val regions: List<MemoryRegion>, val complete: List<MemoryRegion>)
        private val ownerThread = Thread.currentThread()
        private val additionalRamProfile = (values["additionalRamProfile"] ?: "").also {
            require(it is String) { "Additional RAM profile requires text" }
        } as String
        private var additionalSelection: AdditionalSelection? = null
        private var source: MemoryLayoutSource? = null
        private var cached: List<MemoryRegion> = emptyList()
        override val profile get() = values.getValue("profile") as String
        init {
            require(additionalRamProfile == "" || additionalRamProfile in AdditionalRamRegions.PROFILES) { "Unknown additional RAM profile" }
            if ("additionalRamProfile" !in values) {
                values = values + ("additionalRamProfile" to additionalRamProfile)
            }
            context.ports.register(id,"system-memory.layout",MemoryLayout::class.java,this)
        }
        override fun validateSettings(settings: Map<String, Any>) {
            require("additionalRamProfile" !in settings || settings["additionalRamProfile"] == additionalRamProfile) {
                "Additional RAM profile requires restart"
            }
        }
        override fun applySettings(settings: Map<String, Any>) {
            validateSettings(settings)
            super.applySettings(settings)
        }
        private fun requireOwner() {
            check(Thread.currentThread() === ownerThread) { "Additional RAM requires the component owner thread" }
        }
        override fun validateAdditionalRegions(regions: List<MemoryRegion>): List<MemoryRegion> {
            requireOwner()
            require(additionalRamProfile in AdditionalRamRegions.PROFILES) { "Additional RAM requires an explicit finite RAM profile" }
            check(additionalSelection == null) { "Additional RAM has already been selected" }
            return AdditionalRamRegions.validate(this.regions(),regions,additionalRamProfile)
        }
        override fun selectAdditionalRegions(regions: List<MemoryRegion>) {
            requireOwner()
            val additional = java.util.List.copyOf(regions)
            val complete = validateAdditionalRegions(additional)
            additionalSelection = AdditionalSelection(java.util.List.copyOf(additional.sortedBy { it.address }),complete)
        }
        override fun extendAdditionalRegions(regions: List<MemoryRegion>, beforePublish: () -> Unit) {
            requireOwner()
            val current = checkNotNull(additionalSelection) { "Extended additional RAM requires an existing selection" }
            check(source === context.ports.find("board.memory-layouts", MemoryLayoutSource::class.java)) {
                "Additional RAM base source changed after selection"
            }
            val additional = java.util.List.copyOf(regions.sortedBy { it.address })
            require(additional.containsAll(current.regions) && additional.size > current.regions.size) {
                "Extended additional RAM must strictly preserve all previously selected regions"
            }
            AdditionalRamRegions.validatePageAllowance(current.regions.size, additional - current.regions.toSet())
            val next = AdditionalSelection(additional, AdditionalRamRegions.validate(cached, additional, additionalRamProfile))
            beforePublish()
            additionalSelection = next
        }
        override fun additionalRegions(): List<MemoryRegion> {
            requireOwner()
            return additionalSelection?.regions ?: emptyList()
        }
        override fun regions(): List<MemoryRegion> {
            val selected = context.ports.find("board.memory-layouts",MemoryLayoutSource::class.java) ?: error("board memory metadata missing")
            additionalSelection?.let {
                check(source === selected) { "Additional RAM base source changed after selection" }
                return it.complete
            }
            if (source === selected) return cached
            val plan = selected.regions(profile).sortedBy { it.address }
            require(plan.isNotEmpty() && plan.map { it.id }.distinct().size == plan.size)
            plan.forEach { r ->
                require(r.id.isNotBlank() && r.address >= 0 && r.size > 0 && r.address <= 0x1_0000_0000L - r.size && r.address % 4096 == 0L && r.size % 4096 == 0L && r.permissions in 1..7) { "invalid RAM region" }
            }
            plan.zipWithNext().forEach { (a,b) -> require(a.address + a.size <= b.address) { "overlapping RAM regions" } }
            cached = java.util.List.copyOf(plan); source = selected; return cached
        }
        override fun check(address: Long, size: Long, permissions: Int) {
            if(values.getValue("spanPolicy")=="single-region-v1") {
                require(address >= 0 && size > 0 && permissions in 1..7 && regions().any {
                    address >= it.address && address <= it.address + it.size && size <= it.address + it.size - address && it.permissions and permissions == permissions
                }) { "transaction outside declared RAM/permissions" }
                return
            }
            require(address>=0 && size>0 && size<=0x1_0000_0000L && address<=0x1_0000_0000L-size && permissions in 1..7) { "transaction outside declared RAM/permissions" }
            val end=address+size; var cursor=address
            for(region in regions()) {
                val regionEnd=region.address+region.size
                if(regionEnd<=cursor)continue
                require(region.address<=cursor && region.permissions and permissions==permissions) { "transaction outside declared RAM/permissions" }
                cursor=minOf(end,regionEnd)
                if(cursor==end)return
            }
            require(cursor==end) { "transaction outside declared RAM/permissions" }
        }
        override fun state(): Map<String, Any?> {
            val additional = additionalRegions()
            val expansion = mapOf(
                "additionalRamProfile" to additionalRamProfile,
                "additionalRamSelected" to (additionalSelection != null),
                "additionalRegions" to additional.map { mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions) },
                "additionalRamInitialContent" to "HYPOTHESIS: explicit zero native backing supplied by the session; selection changes layout metadata only",
                "additionalRamPermissions" to if(additionalRamProfile==AdditionalRamRegions.EXEC_CAPABLE_PROFILE)
                    "HYPOTHESIS: finite RW/NX or RWX backing selected per region; physical geometry and purpose UNKNOWN"
                else "HYPOTHESIS: finite RW/NX backing; physical geometry and purpose UNKNOWN")
            return super.state() + expansion + if (context.ports.find("board.memory-layouts",MemoryLayoutSource::class.java) == null) mapOf("loaded" to false) else mapOf(
                "loaded" to true,"initialContent" to "explicit zero native backing; guest loader/DMA writes","regions" to regions().map { mapOf("id" to it.id,"address" to it.address,"size" to it.size,"permissions" to it.permissions) },"aliases" to false,"physicalGeometryMeasured" to false)
        }

        override fun saveSnapshot(): Map<String, Any?> {
            requireOwner()
            val selectedRegions = additionalRegions()
            return linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "additionalRamSelected" to (additionalSelection != null),
                "additionalRegions" to selectedRegions.map { region ->
                    linkedMapOf(
                        "id" to region.id,
                        "address" to region.address,
                        "size" to region.size,
                        "permissions" to region.permissions,
                    )
                },
            )
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            requireOwner()
            require(data.keys == SNAPSHOT_KEYS) { "Unexpected snapshot keys for $id" }
            require(snapshotInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            require(scalarsRaw.keys == DESCRIPTOR.settings.map { setting -> setting.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val savedAdditionalProfile = snapshotString(scalarsRaw["additionalRamProfile"], "scalars.additionalRamProfile")
            val previousValues = values
            val previousSource = source
            val previousCached = cached
            val nextSelection: AdditionalSelection?
            try {
                restoreScalarValues(
                    scalarsRaw,
                    DESCRIPTOR,
                    compatibleNonLiveKeys = setOf("additionalRamProfile"),
                )
                require(profileRank(savedAdditionalProfile) <= profileRank(additionalRamProfile)) {
                    "Incompatible additionalRamProfile downgrade in snapshot for $id"
                }
                val selectedFlag = snapshotBoolean(data["additionalRamSelected"], "additionalRamSelected")
                val regionRows = snapshotList(data["additionalRegions"], "additionalRegions")
                val parsedRegions = regionRows.mapIndexed { index, rawRegion ->
                    val regionMap = snapshotMap(rawRegion, "additionalRegions[$index]")
                    require(regionMap.keys == REGION_KEYS) { "Unexpected region keys at additionalRegions[$index]" }
                    val regionId = snapshotString(regionMap["id"], "additionalRegions[$index].id")
                    val address = snapshotLong(regionMap["address"], "additionalRegions[$index].address", 0L, 0x1_0000_0000L)
                    val size = snapshotLong(regionMap["size"], "additionalRegions[$index].size", 1L, 0x1_0000_0000L)
                    val permissions = snapshotInt(regionMap["permissions"], "additionalRegions[$index].permissions", 1, 7)
                    MemoryRegion(regionId, address, size, permissions)
                }
                if (!selectedFlag) {
                    require(parsedRegions.isEmpty()) { "Unselected additional RAM must have empty additionalRegions" }
                    require(additionalSelection == null) { "Live system-memory already selected additional RAM absent from snapshot" }
                    nextSelection = null
                } else {
                    require(savedAdditionalProfile in AdditionalRamRegions.PROFILES) {
                        "Snapshot selected additional RAM without an enabled saved additionalRamProfile"
                    }
                    require(additionalRamProfile in AdditionalRamRegions.PROFILES) {
                        "Additional RAM requires an explicit finite RAM profile"
                    }
                    val sortedRegions = java.util.List.copyOf(parsedRegions.sortedBy { region -> region.address })
                    val currentSelection = additionalSelection
                    if (currentSelection != null) {
                        val layoutSource = context.ports.find("board.memory-layouts", MemoryLayoutSource::class.java)
                            ?: error("board memory metadata missing")
                        check(source === layoutSource) { "Additional RAM base source changed after selection" }
                        val baseRegions = layoutSource.regions(profile).sortedBy { region -> region.address }
                        AdditionalRamRegions.validate(baseRegions, parsedRegions, savedAdditionalProfile)
                        AdditionalRamRegions.validate(baseRegions, parsedRegions, additionalRamProfile)
                        require(currentSelection.regions == sortedRegions) {
                            "Live system-memory additional RAM selection differs from snapshot"
                        }
                        nextSelection = currentSelection
                    } else {
                        val completeRegions = validateAdditionalRegions(parsedRegions)
                        AdditionalRamRegions.validate(this.regions(), parsedRegions, savedAdditionalProfile)
                        nextSelection = AdditionalSelection(sortedRegions, completeRegions)
                    }
                }
            } catch (failure: Throwable) {
                values = previousValues
                source = previousSource
                cached = previousCached
                throw failure
            }
            additionalSelection = nextSelection
        }
    }
}
