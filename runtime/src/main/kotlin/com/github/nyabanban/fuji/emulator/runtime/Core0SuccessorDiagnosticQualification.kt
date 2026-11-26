package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Offline declaration and actual router binding only; no DAT, native library or guest execution. */
object Core0SuccessorDiagnosticQualification {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6)
        val arguments = args.toList().chunked(2).associate { it[0] to it[1] }
        require(arguments.keys == setOf("--baseline-catalog", "--output", "--source-commit"))
        val source = arguments.getValue("--source-commit")
        require(source.matches(Regex("[a-f0-9]{40}")))
        val baselinePath = Path.of(arguments.getValue("--baseline-catalog")).toAbsolutePath()
        val baseline = AddressCatalog.load(baselinePath)
        val catalog = AddressCatalog.bundled()
        val oldId = "gfx100s-sd-finite-f01c-retained-diagnostic-v1"
        val newId = "gfx100s-sd-finite-core0-successor-diagnostic-v1"
        val old = catalog.diagnosticProfiles.single { it.id == oldId }
        val diagnostic = catalog.diagnosticProfiles.single { it.id == newId }
        fun exact(a: Any?, b: Any?) = CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
        val checks = linkedMapOf<String, Boolean>()
        val laterId = "gfx100s-sd-finite-callback-return-diagnostic-v1"
        val beforeParent = ParentReturnDiagnosticQualification.profilesBeforeParentReturn(catalog.diagnosticProfiles.map { it.data })
        val scopedProfiles = beforeParent.filter { it["id"] != laterId }
        val prior = catalog.data + mapOf("diagnosticProfiles" to scopedProfiles.filter { it["id"] != newId })
        checks["allPriorCatalogDataExact"] = exact(prior, baseline.data)
        checks["all32ComponentDescriptorsExact"] = catalog.componentIds.size == 32 && catalog.componentIds == baseline.componentIds &&
            catalog.componentIds.all { exact(catalog.componentDescriptor(it), baseline.componentDescriptor(it)) }
        checks["oldDeclarationExact"] = exact(old.data, baseline.diagnosticProfiles.single { it.id == oldId }.data) && old.successfulReadLimit == 4096
        val expected = old.data + mapOf("id" to newId,"successfulReadLimit" to 131072,
            "evidence" to listOf("evidence/refactor/core0-diagnostic-model-fresh-30/README.md"))
        checks["completeNewDeclarationExact"] = scopedProfiles.size == 2 && exact(diagnostic.data, expected)
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
        val oldBus = checkNotNull(runtime(catalog,oldId).createBus(context))
        val priorOldBus = checkNotNull(runtime(baseline,oldId).createBus(context))
        checks["oldSelectedRegionsExact"] = oldBus.snapshot()["regions"] == priorOldBus.snapshot()["regions"] && oldBus.pageRanges() == priorOldBus.pageRanges()
        checks["strictAddsNoRoute"] = listOf(oldId,newId).all { runtime(catalog,it,"strict").createBus(context) == null }
        val selectedRuntime = runtime(catalog,newId)
        val selected = checkNotNull(selectedRuntime.createBus(context))
        checks["selectedProfileIsolation"] = selected.regions.map { it.id } == listOf(newId) + ordinary.regions.map { it.id } &&
            oldBus.regions.map { it.id } == listOf(oldId) + ordinary.regions.map { it.id }
        checks["oneTransportPageOnly"] = selected.pageRanges() - ordinary.pageRanges().toSet() == listOf(0xfff4f000L to 4096L) &&
            selected.regions.sumOf { it.length } == ordinary.regions.sumOf { it.length } + 4
        val region = selected.regions.single { it.id == newId }
        checks["actualBoundRegionExact"] = region.base == diagnostic.base && region.length == 4 && region.widths == setOf(4) &&
            region.alignment == 4 && region.littleEndian && region.strategy == DiscoveryStrategy.RETAINED &&
            region.initial == null && region.fixedValue == null && region.samples == null && region.prohibited.isEmpty() &&
            region.readAllowed == null && region.writeAllowed == null && region.deviceId == diagnostic.inventoryId &&
            region.evidence == diagnostic.evidence && region.retainedDiagnostic == RetainedWriteDiagnostic(1,131072)
        val before = selected.snapshot()
        val query = selectedRuntime.query(mapOf("address" to "0xFFF4F01C","width" to "4","direction" to "write"))
        checks["staticQueryRemainsUnassignedAndPassive"] = query["classification"] == "UNASSIGNED" && query["permitted"] == false && before == selected.snapshot()
        val router = MmioRouter(emptyList(),selected)
        val initialRefusal = runCatching { router.read(diagnostic.base,4) }.exceptionOrNull()
        router.write(diagnostic.base,4,1)
        val retained = router.read(diagnostic.base,4)
        val repeatedRefusal = runCatching { router.write(diagnostic.base,4,1) }.exceptionOrNull()
        val neighborRefusal = runCatching { router.read(diagnostic.endExclusive,4) }.exceptionOrNull()
        val observation = selectedRuntime.observation(selected)
        val accesses = observation["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        checks["writeFirstRetainedOne"] = initialRefusal != null && accesses[0]["reason"] == "diagnostic-read-before-write" &&
            accesses[1]["before"] == null && accesses[1]["after"] == 1L && accesses[1]["valueSource"] == "guest-write" && retained == 1L
        checks["repeatAndNeighborRefuse"] = repeatedRefusal != null && neighborRefusal != null &&
            accesses[3]["reason"] == "diagnostic-repeated-write" && accesses[4]["reason"] == "outside-selected-envelope"
        checks["completeActualRoutingDeclaration"] = accesses.take(4).all { exact(it["diagnosticRouting"], diagnostic.data) } &&
            accesses.take(4).all { it["classification"] == "UNASSIGNED" && it["catalogPermitted"] == false }
        val preClock = selected.snapshot()
        router.beforeInstruction(100)
        checks["noScheduledEventOrClockMutation"] = router.nextDeadline(100) == null && preClock == selected.snapshot() && context.tick() == 0L
        val report = linkedMapOf<String, Any?>("schema" to 1,"sourceCommit" to source,"evidenceClass" to "TEST",
            "guestExecuted" to false,"nativeLoaded" to false,"hardwareValidated" to false,"functionalF01cModel" to false,
            "baselineCatalog" to baselinePath.toString(),"baselineCatalogPayloadSha256" to baseline.artifactSha256,
            "catalogPayloadSha256" to catalog.artifactSha256,"oldDiagnosticDeclaration" to old.data,"diagnosticDeclaration" to diagnostic.data,
            "scope" to "Original 4096/131072 profiles; complete callback-return, parent-return, type3-return and type3-parent declarations separately qualified and excluded from this historical comparison",
            "excludedLaterDeclarations" to catalog.diagnosticProfiles.filter { it.id in setOf(laterId,ParentReturnDiagnosticQualification.PROFILE,Type3ReturnDiagnosticQualification.PROFILE,Type3ParentDiagnosticQualification.PROFILE) }.map { it.data },
            "checks" to checks,"passed" to checks.values.all { it },"observation" to observation)
        val output = Path.of(arguments.getValue("--output")).toAbsolutePath()
        Files.createDirectories(output.parent)
        Files.write(output,Formats.jsonBytes(report),StandardOpenOption.CREATE_NEW)
        check(checks.values.all { it }) { "Core0 successor diagnostic qualification failed: " + checks.filterValues { !it }.keys }
        println("Core0 successor diagnostic offline assembly: ${checks.size} checks passed; no guest/native execution")
    }
}
