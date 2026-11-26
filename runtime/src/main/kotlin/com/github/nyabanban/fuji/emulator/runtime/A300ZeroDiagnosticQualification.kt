package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.catalog.CatalogIO
import java.nio.file.Files
import java.nio.file.Path

/** Fixed small source reports and actual model binding; no guest, native library or dump payload. */
object A300ZeroDiagnosticQualification {
    private const val BASELINE = "c1e1eb2ff7cfd58115a97834d1e732fe9d90d2a1"
    private fun same(a: Any?, b: Any?) = CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    private fun git(root: Path, maximum: Int, vararg args: String): ByteArray {
        val p = ProcessBuilder(listOf("git") + args).directory(root.toFile()).start()
        val errors = java.util.concurrent.CompletableFuture.supplyAsync { p.errorStream.use { it.readNBytes(65537) } }
        val bytes = p.inputStream.use { it.readNBytes(maximum + 1) }
        check(p.waitFor() == 0 && bytes.size <= maximum && errors.get().isEmpty()) { errors.get().toString(Charsets.UTF_8) }
        return bytes
    }
    private fun leaves(value: Any?, path: String): List<Map<String, Any?>> = when {
        value is Map<*, *> && value.isNotEmpty() -> value.entries.flatMap { (key, child) -> leaves(child, path + "/" + key.toString().replace("~", "~0").replace("/", "~1")) }
        value is List<*> && value.isNotEmpty() -> value.indices.flatMap { leaves(value[it], "$path/$it") }
        else -> listOf(mapOf("path" to path, "beforePresent" to false, "afterPresent" to true, "afterValue" to value))
    }
    @JvmStatic fun main(args: Array<String>) {
        check(args.size == 6)
        val options = args.toList().chunked(2).associate { it[0] to it[1] }
        check(options.keys == setOf("--repo", "--output", "--labels-commit"))
        val root = Path.of(options.getValue("--repo")).toRealPath()
        val source = git(root, 4096, "rev-parse", "HEAD").toString(Charsets.UTF_8).trim()
        val labelsCommit = options.getValue("--labels-commit")
        require(labelsCommit.matches(Regex("[a-f0-9]{40}")))
        val output = root.resolve(options.getValue("--output")).normalize()
        check(output.startsWith(root) && !Files.exists(output))
        val old = CatalogIO.readCompiled(git(root, CatalogIO.MAX_BYTES, "show", "$BASELINE:catalog/compiled/catalog.json"))
        val catalog = AddressCatalog.bundled()
        val declaration = catalog.singleReadDiagnosticProfiles.single()
        val parent = catalog.diagnosticProfiles.single { it.id == declaration.parentProfile }
        val checks = linkedMapOf<String, Boolean>()
        checks["completeBaselineRestored"] = same(catalog.data - "singleReadDiagnosticProfiles", old.data)
        checks["exact32ComponentDescriptors"] = catalog.componentIds.size == 32 && catalog.componentIds == old.componentIds &&
            catalog.componentIds.all { same(catalog.componentDescriptor(it), old.componentDescriptor(it)) }
        checks["sixRetainedDeclarationsUnchanged"] = catalog.diagnosticProfiles.size == 6 &&
            same(catalog.diagnosticProfiles.map { it.data }, old.diagnosticProfiles.map { it.data })
        checks["compiledFileMatchesBoundCatalog"] = same(CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json"))).data, catalog.data)
        checks["parentLawUnchanged"] = parent.successfulReadLimit == 540672 && parent.writeValue == 1L &&
            parent.id == "gfx100s-sd-finite-type3-parent-diagnostic-v1"
        checks["onlyOneExplicitZeroWord"] = declaration.id == "gfx100s-sd-finite-a300-zero-diagnostic-v1" &&
            declaration.samples == listOf(0L) && declaration.width == 4 && declaration.endExclusive - declaration.base == 4L

        val references = listOf(
            mapOf("role" to "caller", "commit" to "3f3d7bf4d8d060d497c2b51b4dd2a9fd3b984813", "path" to "evidence/refactor/a300-caller-fresh-48/qualified.generated.json"),
            mapOf("role" to "dumpScope", "commit" to "6c090473896381e80d226a4dc66762241fae3d50", "path" to "evidence/refactor/a300-dump-scope-fresh-48/qualification.generated.json"),
            mapOf("role" to "labels", "commit" to labelsCommit, "path" to "evidence/refactor/a300-references-fresh-49/qualified.generated.json"))
        val reports = references.map { reference ->
            CatalogIO.json.readTree(git(root, 524288, "show", reference.getValue("commit") + ":" + reference.getValue("path")))
        }
        val caller = reports[0]; val dump = reports[1]; val labels = reports[2]
        val consumer = caller["softwareConsumer"]
        checks["qualifiedBitZeroConsumer"] = caller["qualified"].asBoolean() &&
            caller["read32"]["addressPhysical"].asLong() == declaration.base && caller["read32"]["sizeBytes"].asInt() == declaration.width &&
            caller["read32"]["returnedValue"].isNull && caller["read32"]["physicalFunction"].isNull && caller["read32"]["resetValue"].isNull &&
            caller["function"]["start"].asLong() == 0x12ea70cL && caller["function"]["words"].asInt() == 21 &&
            consumer["mask"].asInt() == 1 && consumer["softwareRamAddress"].asLong() == 0x1a23808L &&
            consumer["storeSizeBytes"].asInt() == 1 && !consumer["writeObserved"].asBoolean() &&
            !consumer["readResultObserved"].asBoolean() &&
            consumer["bitClearPath"]["returnR0"].asInt() == 0 && consumer["bitClearPath"]["nextPc"].asLong() == 0x12ea738L &&
            consumer["bitSetPath"]["callee"].asLong() == 0x12ea630L &&
            consumer["bitSetPath"]["pairWords"].map { it.asInt() } == listOf(12356, 0)
        checks["dumpDoesNotProvideValue"] = dump["accepted"].asBoolean() && !dump["captureScope"]["qualifiedTargetCapture"].asBoolean() &&
            dump["captureScope"]["targetValue"].isNull && dump["captureScope"]["numericOverlapCount"].asInt() == 0
        val sourceLabels = labels["sourceLabels"]
        checks["idNamesAreQualifiedOnlyAsLabels"] = labels["qualified"].asBoolean() && sourceLabels.size() == 2 &&
            sourceLabels.all { it["confidence"].asText() == "SOURCE" && it["semanticScope"].asText().contains("not an event action") } &&
            sourceLabels.single { it["id"].asInt() == 12356 }.let { it["ascii"].asText() == "KEY_JIGBOOT" && it["terminated"].asBoolean() } &&
            sourceLabels.single { it["id"].asInt() == 12357 }.let { it["ascii"].asText() == "KEYEVENT_MODE_END" && it["terminated"].asBoolean() }
        val inventory = checkNotNull(catalog.byId(declaration.inventoryId))
        checks["routeUsesObservedCallbackCoordinate"] = (inventory["nativeCallbackAddress"] as Map<*, *>)["address"] == "0xFFF4A300" &&
            declaration.base == 0xfff4a300L && declaration.inventoryId == "INV-SYS-A300-001"
        checks["inventoryRemainsPassiveUnknown"] = same(inventory, old.byId(declaration.inventoryId)) &&
            inventory["cpuRoutable"] == false && (inventory["binding"] as Map<*, *>)["active"] == false &&
            listOf("physicalAddress", "resetValue", "returnValue", "deviceOwner").all { inventory.containsKey(it) && inventory[it] == null }

        val context = object : DiscoveryContext {
            override fun core() = 0
            override fun tick() = 0L
            override fun pc() = 0L
            override fun lr() = 0L
        }
        fun runtime(c: AddressCatalog, profile: String? = null, mode: String = "discovery") = CatalogRuntime(c, mode, root,
            if (profile == null) emptyMap() else mapOf("discovery" to mapOf("profile" to profile)))
        for (profile in listOf(null) + catalog.diagnosticProfiles.map { it.id }) {
            val current = checkNotNull(runtime(catalog, profile).createBus(context))
            val previous = checkNotNull(runtime(old, profile).createBus(context))
            checks["unchangedRoutes:" + (profile ?: "default")] = same(current.snapshot()["regions"], previous.snapshot()["regions"]) &&
                current.pageRanges() == previous.pageRanges() && runCatching { MmioRouter(emptyList(), current).read(declaration.base, 4) }.isFailure
        }
        checks["strictHasNoDiscoveryRoute"] = (catalog.diagnosticProfiles.map { it.id } + declaration.id + "gfx100s-sd-finite-v1").all {
            runtime(catalog, it, "strict").createBus(context) == null
        }
        val selectedRuntime = runtime(catalog, declaration.id)
        val selected = checkNotNull(selectedRuntime.createBus(context))
        val parentBus = checkNotNull(runtime(catalog, declaration.parentProfile).createBus(context))
        val added = selected.regions.single { it.id == declaration.id }
        checks["newSelectionOnlyAddsDeclaredScriptToParent"] =
            same((selected.snapshot()["regions"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }).filter { it["id"] != declaration.id }, parentBus.snapshot()["regions"]) &&
            added.base == declaration.base && added.length == 4 && added.widths == setOf(4) && added.alignment == 4 &&
            added.strategy == DiscoveryStrategy.SCRIPT && added.samples == declaration.samples && added.evidence == declaration.evidence &&
            added.deviceId == declaration.inventoryId && added.initial == null && added.fixedValue == null && added.retainedDiagnostic == null
        checks["oneNewTransportPage"] = selected.pageRanges() - parentBus.pageRanges().toSet() == listOf((declaration.base and -4096L) to 4096L)
        var queryCount = 0
        for (memory in catalog.memoryProfiles.keys) for (mode in listOf("discovery", "strict")) for (direction in listOf("read", "write")) {
            for (address in listOf(declaration.base - 4, declaration.base, declaration.endExclusive)) for (width in listOf(1, 2, 4, 8)) {
                val a = old.query("cpu-physical", address, width, direction, memory, mode).toMap()
                val b = catalog.query("cpu-physical", address, width, direction, memory, mode).toMap()
                check(b["classification"] == "UNASSIGNED" && b["permitted"] == false && same(b + ("artifactSha256" to old.artifactSha256), a))
                queryCount++
            }
        }
        checks["ordinaryAddressQueriesRemainUnassigned"] = queryCount > 0
        val router = MmioRouter(emptyList(), selected)
        check(router.read(declaration.base, declaration.width) == 0L)
        check(runCatching { router.read(declaration.base, declaration.width) }.isFailure)
        val observation = selectedRuntime.observation(selected)
        val accesses = observation["accesses"].let { list -> (list as List<*>).map { elem -> elem as Map<*, *> } }
        checks["singleHypothesisThenExhaustion"] = accesses.size == 2 && accesses[0]["accepted"] == true &&
            accesses[0]["confidence"] == "HYPOTHESIS" && accesses[0]["value"] == 0L && accesses[0]["valueSource"] == "scripted-read" &&
            accesses[1]["accepted"] == false && accesses[1]["value"] == null && accesses[1]["reason"] == "finite-input-missing-or-exhausted"
        checks["routingAnnotationDoesNotPromoteCatalogPermission"] = accesses.all {
            same(it["diagnosticRouting"], declaration.data) && it["classification"] == "UNASSIGNED" && it["catalogPermitted"] == false
        }
        val before = selected.snapshot(); router.beforeInstruction(1)
        checks["noScheduledEventOrTickChange"] = router.nextDeadline(1) == null && same(before, selected.snapshot()) && context.tick() == 0L
        val changes = git(root, 65536, "diff", "--name-only", BASELINE, source, "--", "catalog").toString(Charsets.UTF_8).trim().lines()
        checks["catalogSourceAndGeneratedPathsOnly"] = changes.toSet() == setOf("catalog/soc/xp4/address-space.yaml", "catalog/compiled/catalog.json", "catalog/compiled/manifest.json")
        val report = mapOf("schema" to 1, "accepted" to checks.values.all { it }, "sourceCommit" to source, "baselineCommit" to BASELINE,
            "evidenceClass" to "TEST", "guestExecuted" to false, "nativeLoaded" to false, "hardwareValidated" to false,
            "physicalFunctionKnown" to false, "actualRegisterValueKnown" to false, "sourceReferences" to references,
            "sourceLabels" to sourceLabels, "sourceConsumer" to consumer,
            "declaration" to declaration.data, "parentDeclaration" to parent.data,
            "catalogDelta" to mapOf("completeBaselineRestored" to checks.getValue("completeBaselineRestored"),
                "unchangedComponentDescriptorCount" to 32, "oldPayloadSha256" to old.artifactSha256, "newPayloadSha256" to catalog.artifactSha256,
                "catalogChangedPaths" to changes, "completePayloadLeafDifferences" to leaves(catalog.data["singleReadDiagnosticProfiles"], "/singleReadDiagnosticProfiles")),
            "physicalQueriesVerified" to queryCount, "checks" to checks, "observation" to observation,
            "scope" to "One explicit hypothetical zero read; software bit-zero path avoids the conditional 3044 submission. Labels establish no power-switch identity, event action, paired off-state or hardware reply. No DAT, dump payload, native library or guest execution.")
        CatalogIO.writeJson(output, report)
        check(checks.values.all { it }) { "A300 zero qualification failed: " + checks.filterValues { !it }.keys }
        println("accepted=true checks=${checks.size} unchangedComponents=32 physicalQueries=$queryCount guestExecuted=false")
    }
}
