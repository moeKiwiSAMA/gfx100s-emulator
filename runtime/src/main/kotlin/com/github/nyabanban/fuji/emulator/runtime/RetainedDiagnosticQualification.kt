package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Offline assembly/query evidence only. Does not load DAT, native code, RAM, a snapshot or a guest. */
object RetainedDiagnosticQualification {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6)
        val arguments = args.toList().chunked(2).associate { it[0] to it[1] }
        require(arguments.keys == setOf("--baseline-catalog", "--output", "--source-commit"))
        val source = arguments.getValue("--source-commit")
        require(source.matches(Regex("[a-f0-9]{40}")))
        val baselinePath = Path.of(arguments.getValue("--baseline-catalog")).toAbsolutePath()
        val baseline = AddressCatalog.load(baselinePath)
        val catalog = AddressCatalog.bundled()
        val diagnostic = catalog.diagnosticProfiles.single { it.id == "gfx100s-sd-finite-f01c-retained-diagnostic-v1" }
        val checks = linkedMapOf<String, Boolean>()
        checks["allPriorCatalogDataExact"] = CatalogIO.canonical(catalog.data - "diagnosticProfiles").contentEquals(CatalogIO.canonical(baseline.data))
        checks["all32ComponentDescriptorsExact"] = catalog.componentIds.size == 32 && catalog.componentIds == baseline.componentIds &&
            catalog.componentIds.all { CatalogIO.canonical(catalog.componentDescriptor(it)).contentEquals(CatalogIO.canonical(baseline.componentDescriptor(it))) }
        checks["exactSelectedProfile"] = diagnostic.id == "gfx100s-sd-finite-f01c-retained-diagnostic-v1" &&
            diagnostic.base == 0xfff4f01cL && diagnostic.endExclusive == 0xfff4f020L && diagnostic.width == 4 &&
            diagnostic.writeValue == 1L && diagnostic.successfulReadLimit == 4096
        val context = object : DiscoveryContext {
            override fun core() = 3
            override fun tick() = 0L
            override fun pc() = 0L
            override fun lr() = 0L
        }
        val runtime = CatalogRuntime(catalog,"discovery",Path.of("."),mapOf("discovery" to mapOf("profile" to diagnostic.id)))
        val selected = checkNotNull(runtime.createBus(context))
        val ordinary = checkNotNull(CatalogRuntime(catalog,"discovery",Path.of("."),emptyMap()).createBus(context))
        val old = checkNotNull(CatalogRuntime(baseline,"discovery",Path.of("."),emptyMap()).createBus(context))
        checks["defaultRegionsExact"] = ordinary.snapshot()["regions"] == old.snapshot()["regions"] && ordinary.pageRanges() == old.pageRanges()
        checks["strictAddsNoRoute"] = CatalogRuntime(catalog,"strict",Path.of("."),mapOf("discovery" to mapOf("profile" to diagnostic.id))).createBus(context) == null
        checks["oneTransportPageOnly"] = selected.pageRanges() - ordinary.pageRanges().toSet() == listOf(0xfff4f000L to 4096L)
        val before = selected.snapshot()
        val query = runtime.query(mapOf("address" to "0xFFF4F01C","width" to "4","direction" to "write"))
        checks["staticQueryRemainsUnassignedAndPassive"] = query["classification"] == "UNASSIGNED" && query["permitted"] == false && before == selected.snapshot()
        val router = MmioRouter(emptyList(),selected)
        val initialRefusal = runCatching { router.read(diagnostic.base,4) }.exceptionOrNull()
        router.write(diagnostic.base,4,1)
        val retained = router.read(diagnostic.base,4)
        val repeatedRefusal = runCatching { router.write(diagnostic.base,4,0) }.exceptionOrNull()
        val neighborRefusal = runCatching { router.read(diagnostic.endExclusive,4) }.exceptionOrNull()
        val observation = runtime.observation(selected)
        val accesses = observation["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        checks["writeFirstRetainedOne"] = initialRefusal != null && accesses[0]["reason"] == "diagnostic-read-before-write" &&
            accesses[1]["before"] == null && accesses[1]["after"] == 1L && accesses[1]["valueSource"] == "guest-write" && retained == 1L
        checks["repeatAndNeighborRefuse"] = repeatedRefusal != null && neighborRefusal != null &&
            accesses[3]["reason"] == "diagnostic-repeated-write" && accesses[4]["reason"] == "outside-selected-envelope"
        val routing = accesses[1]["diagnosticRouting"] as Map<*, *>
        checks["observedRouteNotPhysicalClaim"] = routing["confidence"] == "HYPOTHESIS" && routing.containsKey("physicalAddress") &&
            routing["physicalAddress"] == null && accesses[1]["classification"] == "UNASSIGNED" && accesses[1]["catalogPermitted"] == false
        val preClock = selected.snapshot()
        router.beforeInstruction(100)
        checks["noScheduledEventOrClockMutation"] = router.nextDeadline(100) == null && preClock == selected.snapshot() && context.tick() == 0L
        val report = linkedMapOf<String, Any?>("schema" to 1,"sourceCommit" to source,"evidenceClass" to "TEST",
            "guestExecuted" to false,"nativeLoaded" to false,"hardwareValidated" to false,"functionalF01cModel" to false,
            "baselineCatalog" to baselinePath.toString(),"baselineCatalogPayloadSha256" to baseline.artifactSha256,
            "catalogPayloadSha256" to catalog.artifactSha256,"diagnosticDeclaration" to diagnostic.data,
            "checks" to checks,"passed" to checks.values.all { it },"observation" to observation)
        val output = Path.of(arguments.getValue("--output")).toAbsolutePath()
        Files.createDirectories(output.parent)
        Files.write(output,Formats.jsonBytes(report),StandardOpenOption.CREATE_NEW)
        check(checks.values.all { it }) { "Retained diagnostic qualification failed: " + checks.filterValues { !it }.keys }
        println("Retained diagnostic offline assembly: ${checks.size} checks passed; no guest/native execution")
    }
}
