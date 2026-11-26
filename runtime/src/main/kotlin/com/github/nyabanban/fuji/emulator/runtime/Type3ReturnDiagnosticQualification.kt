package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Offline declaration and actual router binding only; no native library or guest execution. */
object Type3ReturnDiagnosticQualification {
    const val PROFILE = "gfx100s-sd-finite-type3-return-diagnostic-v1"
    private const val CALLBACK = "gfx100s-sd-finite-callback-return-diagnostic-v1"
    private val previousLimits = linkedMapOf(
        "gfx100s-sd-finite-f01c-retained-diagnostic-v1" to 4096,
        "gfx100s-sd-finite-core0-successor-diagnostic-v1" to 131072,
        CALLBACK to 262144,
        ParentReturnDiagnosticQualification.PROFILE to 327680)
    private fun exact(a: Any?, b: Any?) = CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))

    /** Historical qualification may omit only this single, fully checked extension. */
    fun profilesBeforeType3Return(inputProfiles: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val profiles = Type3ParentDiagnosticQualification.profilesBeforeType3Parent(inputProfiles)
        val added = profiles.filter { it["id"] == PROFILE }
        if (added.isEmpty()) return profiles
        require(profiles.size == 5 && added.size == 1 && profiles.map { it["id"] }.toSet() == previousLimits.keys + PROFILE) {
            "Type3-return declaration set differs"
        }
        // Every historical entry compares the 4096 declaration with its complete baseline.
        // Deriving from callback would let the oldest entry miss a coordinated later-row change.
        val expected = profiles.single { it["id"] == previousLimits.keys.first() } + mapOf("id" to PROFILE,
            "successfulReadLimit" to 409600,"evidence" to listOf("evidence/refactor/type3-return-model-fresh-43/README.md"))
        require(exact(added.single(),expected)) { "Complete type3-return declaration differs" }
        return profiles.filter { it["id"] != PROFILE }
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6)
        val arguments = args.toList().chunked(2).associate { it[0] to it[1] }
        require(arguments.keys == setOf("--baseline-catalog","--output","--source-commit"))
        val source = arguments.getValue("--source-commit")
        require(source.matches(Regex("[a-f0-9]{40}")))
        val baselinePath = Path.of(arguments.getValue("--baseline-catalog")).toAbsolutePath()
        val baseline = AddressCatalog.load(baselinePath)
        val catalog = AddressCatalog.bundled()
        val diagnostic = catalog.diagnosticProfiles.single { it.id == PROFILE }
        val checks = linkedMapOf<String, Boolean>()
        val scopedProfiles = Type3ParentDiagnosticQualification.profilesBeforeType3Parent(catalog.diagnosticProfiles.map { it.data })
        val prior = catalog.data + mapOf("diagnosticProfiles" to profilesBeforeType3Return(scopedProfiles))
        checks["p41BaselinePayloadExact"] = baseline.artifactSha256 == "cfe4d39f06e0dd647f7ff7c7fd0830186915aab7894a42ef73aabd3aa0858832"
        checks["allPriorCatalogDataExact"] = exact(prior,baseline.data)
        checks["all32ComponentDescriptorsExact"] = catalog.componentIds.size == 32 && catalog.componentIds == baseline.componentIds &&
            catalog.componentIds.all { exact(catalog.componentDescriptor(it),baseline.componentDescriptor(it)) }
        checks["completeNewDeclarationExact"] = scopedProfiles.size == 5 && diagnostic.successfulReadLimit == 409600
        val context = object : DiscoveryContext {
            override fun core() = 3
            override fun tick() = 0L
            override fun pc() = 0L
            override fun lr() = 0L
        }
        fun runtime(c: AddressCatalog, profile: String? = null, mode: String = "discovery") =
            CatalogRuntime(c,mode,Path.of("."),if (profile == null) emptyMap() else mapOf("discovery" to mapOf("profile" to profile)))
        val ordinary = checkNotNull(runtime(catalog).createBus(context))
        val priorOrdinary = checkNotNull(runtime(baseline).createBus(context))
        checks["defaultRegionsExact"] = ordinary.snapshot()["regions"] == priorOrdinary.snapshot()["regions"] && ordinary.pageRanges() == priorOrdinary.pageRanges()
        for ((id,limit) in previousLimits) {
            val declaration = catalog.diagnosticProfiles.single { it.id == id }
            checks["old${limit}DeclarationExact"] = declaration.successfulReadLimit == limit && exact(declaration.data,baseline.diagnosticProfiles.single { it.id == id }.data)
            val bus = checkNotNull(runtime(catalog,id).createBus(context))
            val oldBus = checkNotNull(runtime(baseline,id).createBus(context))
            checks["old${limit}SelectedRegionsExact"] = bus.snapshot()["regions"] == oldBus.snapshot()["regions"] && bus.pageRanges() == oldBus.pageRanges() &&
                bus.regions.map { it.id } == listOf(id) + ordinary.regions.map { it.id } &&
                bus.regions.single { it.id == id }.retainedDiagnostic == RetainedWriteDiagnostic(1,limit)
        }
        checks["strictAddsNoRoute"] = (previousLimits.keys + PROFILE).all { runtime(catalog,it,"strict").createBus(context) == null }
        val selectedRuntime = runtime(catalog,PROFILE)
        val selected = checkNotNull(selectedRuntime.createBus(context))
        checks["selectedProfileIsolation"] = selected.regions.map { it.id } == listOf(PROFILE) + ordinary.regions.map { it.id }
        checks["oneTransportPageOnly"] = selected.pageRanges() - ordinary.pageRanges().toSet() == listOf(0xfff4f000L to 4096L) &&
            selected.regions.sumOf { it.length } == ordinary.regions.sumOf { it.length } + 4
        val region = selected.regions.single { it.id == PROFILE }
        checks["actualBoundRegionExact"] = region.base == diagnostic.base && region.length == 4 && region.widths == setOf(4) &&
            region.alignment == 4 && region.littleEndian && region.strategy == DiscoveryStrategy.RETAINED &&
            region.initial == null && region.fixedValue == null && region.samples == null && region.prohibited.isEmpty() &&
            region.readAllowed == null && region.writeAllowed == null && region.deviceId == diagnostic.inventoryId &&
            region.evidence == diagnostic.evidence && region.retainedDiagnostic == RetainedWriteDiagnostic(1,409600)
        val before = selected.snapshot()
        val query = selectedRuntime.query(mapOf("address" to "0xFFF4F01C","width" to "4","direction" to "write"))
        checks["staticQueryRemainsUnassignedAndPassive"] = query["classification"] == "UNASSIGNED" && query["permitted"] == false && before == selected.snapshot()
        val router = MmioRouter(emptyList(),selected)
        val initialRefusal = runCatching { router.read(diagnostic.base,4) }.exceptionOrNull()
        router.write(diagnostic.base,4,1)
        val retained = router.read(diagnostic.base,4)
        val repeatedRefusal = runCatching { router.write(diagnostic.base,4,0) }.exceptionOrNull()
        val neighborRefusal = runCatching { router.read(diagnostic.endExclusive,4) }.exceptionOrNull()
        val observation = selectedRuntime.observation(selected)
        val accesses = observation["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        checks["writeFirstRetainedOne"] = initialRefusal != null && accesses[0]["reason"] == "diagnostic-read-before-write" &&
            accesses[1]["before"] == null && accesses[1]["after"] == 1L && accesses[1]["valueSource"] == "guest-write" && retained == 1L
        checks["repeatZeroAndNeighborRefuse"] = repeatedRefusal != null && neighborRefusal != null &&
            accesses[3]["reason"] == "diagnostic-repeated-write" && accesses[4]["reason"] == "outside-selected-envelope"
        checks["completeActualRoutingDeclaration"] = accesses.take(4).all { exact(it["diagnosticRouting"],diagnostic.data) } &&
            accesses.take(4).all { it["classification"] == "UNASSIGNED" && it["catalogPermitted"] == false }
        val preClock = selected.snapshot()
        router.beforeInstruction(100)
        checks["noScheduledEventOrClockMutation"] = router.nextDeadline(100) == null && preClock == selected.snapshot() && context.tick() == 0L
        val report = linkedMapOf<String, Any?>("schema" to 1,"sourceCommit" to source,"evidenceClass" to "TEST",
            "guestExecuted" to false,"nativeLoaded" to false,"hardwareValidated" to false,"functionalF01cModel" to false,
            "baselineArchiveCommit" to "159c4e37edfb1aab7306f63ca326c02f5a8058af",
            "baselineProducerCommit" to "58e7a079fd5a89ad3da2505615668e3701a908f8",
            "baselineCatalogGitPath" to "evidence/refactor/cache-clean-export-original-fresh-41/study/discovery-cap8-v5-gpio110-cache-clean-export/config/catalog.compiled.json",
            "baselineCatalog" to baselinePath.toString(),"baselineCatalogPayloadSha256" to baseline.artifactSha256,
            "catalogPayloadSha256" to catalog.artifactSha256,"diagnosticDeclaration" to diagnostic.data,
            "scope" to "Complete P41 catalog restored after complete type3-parent and type3-return declaration checks",
            "excludedLaterDeclarations" to catalog.diagnosticProfiles.filter { it.id == Type3ParentDiagnosticQualification.PROFILE }.map { it.data },
            "previousDiagnosticDeclarations" to catalog.diagnosticProfiles.filter { it.id in previousLimits }.map { it.data },
            "checks" to checks,"passed" to checks.values.all { it },"observation" to observation)
        val output = Path.of(arguments.getValue("--output")).toAbsolutePath()
        Files.createDirectories(output.parent)
        Files.write(output,Formats.jsonBytes(report),StandardOpenOption.CREATE_NEW)
        check(checks.values.all { it }) { "Type3 return diagnostic qualification failed: " + checks.filterValues { !it }.keys }
        println("Type3 return diagnostic offline assembly: ${checks.size} checks passed; no guest/native execution")
    }
}
