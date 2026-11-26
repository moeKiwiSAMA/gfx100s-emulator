package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Files
import java.nio.file.Path

/** One passive record; no firmware execution, input source, window or runtime activation. */
object A300InventoryQualification {
    const val ID = "INV-SYS-A300-001"
    const val BASE = "2aaff96835844a3cfd5233941c4d8b8daeee6cdd"
    private val references = listOf(
        mapOf("commit" to "ab78a81f98e19cf0619e076a26a516f5cc0c2611", "path" to "evidence/refactor/vmov-retry-reader-fresh-47/original-acceptance.generated.json"),
        mapOf("commit" to "3f3d7bf4d8d060d497c2b51b4dd2a9fd3b984813", "path" to "evidence/refactor/a300-caller-fresh-48/qualified.generated.json"),
        mapOf("commit" to "6c090473896381e80d226a4dc66762241fae3d50", "path" to "evidence/refactor/a300-dump-scope-fresh-48/qualification.generated.json"))
    private val unknown = listOf("address", "physicalAddress", "physicalSpan", "deviceOwner", "resetValue", "readSideEffect", "returnValue")
    private fun same(a: Any?, b: Any?) = CatalogIO.canonical(a).contentEquals(CatalogIO.canonical(b))
    private fun git(root: Path, vararg arguments: String): ByteArray {
        val p = ProcessBuilder(listOf("git") + arguments).directory(root.toFile()).start()
        val errors = java.util.concurrent.CompletableFuture.supplyAsync { p.errorStream.use { it.readNBytes(65537) } }
        val bytes = p.inputStream.use { it.readNBytes(CatalogIO.MAX_BYTES + 1) }
        check(p.waitFor() == 0 && bytes.size <= CatalogIO.MAX_BYTES) { errors.get().toString(Charsets.UTF_8) }
        check(errors.get().isEmpty())
        return bytes
    }
    fun baseline(root: Path) = CatalogIO.readCompiled(git(root, "show", "$BASE:catalog/compiled/catalog.json"))
    fun record(catalog: AddressCatalog): Map<String, Any?> {
        val r = checkNotNull(catalog.byId(ID))
        check(r["status"] == "RESEARCH_RECORD" && r["physicalIdentity"] == "UNKNOWN")
        unknown.forEach { check(r.containsKey(it) && r[it] == null) { "Unknown property changed: $it" } }
        check(r["cpuRoutable"] == false && r["runtimeActivation"] == false)
        check(same(r["binding"], mapOf("active" to false, "componentId" to null, "portId" to null)))
        check(listOf("componentId", "portId", "deviceId").none { it in r })
        check(same(r["evidence"], references))
        val sources = obj(r["provenance"])
        val confidence = unknown.associateWith { "UNKNOWN" } + mapOf("softwareEffectiveAddress" to "DAT", "nativeCallbackAddress" to "SIM", "attemptedRead" to "SIM", "softwareConsumer" to "DAT", "frameBinding" to "SIM", "captureCoverage" to "FILE", "binding" to "SOURCE", "cpuRoutable" to "SOURCE", "runtimeActivation" to "SOURCE")
        confidence.forEach { (key, value) ->
            val p = obj(sources[key]); check(p["confidence"] == value && (p["support"] as List<*>).isNotEmpty())
            check(p.text("counterexample").isNotBlank() && p.text("nextValidation").isNotBlank())
        }
        val software = obj(r["softwareEffectiveAddress"]); val callback = obj(r["nativeCallbackAddress"])
        check(software["coordinateKind"] == "GUEST_VIRTUAL" && software["address"] == "0xFFF4A300" && software.long("widthBytes") == 4L)
        check(callback["coordinateKind"] == "NATIVE_CALLBACK_ADDRESS_PHYSICAL_FIELD" && callback["field"] == "addressPhysical" && callback["address"] == software["address"])
        val read = obj(r["attemptedRead"])
        check(read["completed"] == false && read.containsKey("value") && read["value"] == null)
        check(read["pcVirtual"] == "0x0005AFE0" && read.long("core") == 0L && read.long("tick") == 98695494L && read.long("widthBytes") == 4L)
        val consumer = obj(r["softwareConsumer"])
        check(consumer["functionStart"] == "0x012EA70C" && consumer["functionEndExclusive"] == "0x012EA760" && consumer.long("words") == 21L)
        check(consumer.long("mask") == 1L && consumer["softwareRamAddress"] == "0x01A23808" && consumer.long("storeBytes") == 1L)
        check(consumer["conditionalCallee"] == "0x012EA630" && same(consumer["explicitArgumentWords"], listOf(12356, 0)))
        check(consumer["storeObserved"] == false && consumer.long("ordinaryReturnValue") == 0L && consumer.text("condition").isNotBlank())
        val frame = obj(r["frameBinding"])
        check(frame["status"] == "CONDITIONALLY_QUALIFIED" && frame.long("frameBytes") == 64L && frame.long("helperBytes") == 4L)
        check(frame["entryAndCallsitesSelected"] == false && frame["fullDynamicAncestryObserved"] == false && frame.text("condition").isNotBlank())
        check(same(r["captureCoverage"], mapOf("qualifiedMmioCapture" to false, "qualifiedSoftwareByteCapture" to false, "mappingInferred" to false)))
        return r
    }
    fun delta(old: AddressCatalog, current: AddressCatalog): Map<String, Any?> {
        val before = rows(old.data["inventory"]); val after = rows(current.data["inventory"])
        check(after.size == before.size + 1 && after.last()["id"] == ID && before.none { it["id"] == ID })
        check(same(current.data + ("inventory" to after.dropLast(1)), old.data)) { "Removing only the new inventory row must restore the complete baseline" }
        check(current.componentIds == old.componentIds && current.componentIds.size == 32)
        current.componentIds.forEach { check(same(current.componentDescriptor(it), old.componentDescriptor(it))) { "ABI differs: $it" } }
        fun leaves(value: Any?, path: String): List<Map<String, Any?>> = when {
            value is Map<*, *> && value.isNotEmpty() -> value.entries.flatMap { (k, v) -> leaves(v, path + "/" + k.toString().replace("~", "~0").replace("/", "~1")) }
            value is List<*> && value.isNotEmpty() -> value.indices.flatMap { leaves(value[it], "$path/$it") }
            else -> listOf(mapOf("path" to path, "beforePresent" to false, "afterPresent" to true, "afterValue" to value))
        }
        return mapOf("baselineCommit" to BASE, "oldInventoryCount" to before.size, "newInventoryCount" to after.size,
            "onlyAddedId" to ID, "completeBaselineRestored" to true, "unchangedComponentDescriptorCount" to 32,
            "baselinePayloadSha256" to old.artifactSha256, "payloadSha256" to current.artifactSha256,
            "completePayloadLeafDifferences" to leaves(after.last(), "/inventory/${before.size}"))
    }
    private fun queries(old: AddressCatalog, current: AddressCatalog): Map<String, Any?> {
        val properties = (unknown + listOf("softwareEffectiveAddress.address", "nativeCallbackAddress.address", "softwareConsumer.mask", "softwareConsumer.ordinaryReturnValue", "cpuRoutable", "binding.active")).map { current.property(ID, it) }
        properties.filter { it["property"] in unknown }.forEach { check(it["value"] == null && it["status"] == "UNKNOWN" && obj(it["provenance"])["confidence"] == "UNKNOWN") }
        check(properties.filter { it["property"] in listOf("softwareEffectiveAddress.address", "nativeCallbackAddress.address") }.all { it["value"] == "0xFFF4A300" && it["status"] == "DECLARED" })
        val addresses = listOf(0xfff4a000L, 0xfff4a2fcL, 0xfff4a300L, 0xfff4a304L, 0xfff4affcL)
        var count = 0
        for (profile in current.memoryProfiles.keys) for (mode in listOf("strict", "discovery")) for (direction in listOf("read", "write")) for (address in addresses) for (width in listOf(1, 2, 4, 8)) {
            val a = old.query("cpu-physical", address, width, direction, profile, mode).toMap()
            val b = current.query("cpu-physical", address, width, direction, profile, mode).toMap()
            check(b["classification"] == "UNASSIGNED" && b["permitted"] == false)
            check(same(b + ("artifactSha256" to old.artifactSha256), a)) { "Passive row changed an address query" }; count++
        }
        return mapOf("properties" to properties, "memoryProfiles" to current.memoryProfiles.keys, "modes" to listOf("strict", "discovery"), "directions" to listOf("read", "write"), "addresses" to addresses, "widths" to listOf(1, 2, 4, 8), "physicalQueriesVerified" to count, "allRefused" to true, "onlyQueryDifference" to "artifactSha256")
    }
    @JvmStatic fun main(args: Array<String>) {
        check(args.size == 4); val options = args.toList().chunked(2).associate { it[0] to it[1] }
        check(options.keys == setOf("--repo", "--output"))
        val root = Path.of(options.getValue("--repo")).toRealPath(); val source = git(root, "rev-parse", "HEAD").toString(Charsets.UTF_8).trim()
        val output = root.resolve(options.getValue("--output")).normalize(); check(output.startsWith(root) && !Files.exists(output))
        val current = CatalogCompiler.compile(root); val old = baseline(root); val r = record(current)
        check(same(CatalogIO.readCompiled(Files.readAllBytes(root.resolve("catalog/compiled/catalog.json"))).data, current.data))
        val reports = references.map { ref -> val bytes = git(root, "show", ref.getValue("commit") + ":" + ref.getValue("path")); check(bytes.size <= 524288); CatalogIO.json.readTree(bytes) }
        val original = reports[0]; val caller = reports[1]; val dump = reports[2]
        check(original["accepted"].asBoolean() && original["formalExecutionVerified"].asBoolean() && caller["qualified"].asBoolean() && dump["accepted"].asBoolean())
        val frontier = original["details"]["terminal"]["frontier"]
        check(frontier["addressPhysical"].asLong() == 0xfff4a300L && frontier["size"].asInt() == 4 && frontier["value"].isNull && frontier["pcVirtual"].asLong() == 0x5afe0L && frontier["tick"].asLong() == obj(r["attemptedRead"]).long("tick") && frontier["core"].asInt() == 0)
        check(caller["function"]["start"].asLong() == 0x12ea70cL && caller["function"]["endExclusive"].asLong() == 0x12ea760L && caller["function"]["words"].asInt() == 21 && caller["function"]["allWordsGated"].asBoolean())
        val consumer = caller["softwareConsumer"]
        check(consumer["mask"].asInt() == 1 && consumer["softwareRamAddress"].asLong() == 0x1a23808L && consumer["storeSizeBytes"].asInt() == 1 && !consumer["writeObserved"].asBoolean() && !consumer["readResultObserved"].asBoolean())
        check(consumer["bitSetPath"]["callee"].asLong() == 0x12ea630L && consumer["bitSetPath"]["pairWords"].map { it.asInt() } == listOf(12356, 0))
        val frame = caller["currentFrame"]; check(frame["status"].asText() == "CONDITIONALLY_QUALIFIED" && !caller["selectedCoverage"]["fullAncestryObserved"].asBoolean())
        for ((key, referenceKey) in mapOf("savedHelperFp" to "savedHelperFp", "savedOuterFp" to "savedOuterFp", "savedOuterReturn" to "savedOuterReturn")) check(obj(r["frameBinding"]).text(key).removePrefix("0x").toLong(16) == frame[referenceKey].asLong())
        check(!dump["captureScope"]["qualifiedTargetCapture"].asBoolean() && dump["captureScope"]["targetValue"].isNull && dump["captureScope"]["numericOverlapCount"].asInt() == 0)
        check(dump["captureScope"]["coverage"].none { !it["recordedAddress"].isNull && it["recordedAddress"].asLong() <= 0x1a23808L && 0x1a23808L < it["recordedEndExclusive"].asLong() })
        val report = mapOf("schema" to 1, "accepted" to true, "sourceCommit" to source, "references" to references, "record" to r,
            "catalogDelta" to delta(old, current), "queries" to queries(old, current),
            "scope" to "One passive inventory entry. Three fixed small Git reports; no DAT, dump payload, guest, native, input selection or device access. Current rejected read stays rejected.")
        CatalogIO.writeJson(output, report)
        println("accepted=true passiveId=$ID references=3 componentDescriptorsUnchanged=32")
    }
}
