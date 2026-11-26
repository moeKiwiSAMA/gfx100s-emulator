package com.github.nyabanban.fuji.emulator.catalog

/** Explicit finite hypothesis; never part of physical-address ownership or reset queries. */
class CatalogSingleReadDiagnostic internal constructor(val data: Map<String, Any?>) {
    val id = data.text("id")
    val parentProfile = data.text("parentProfile")
    val inventoryId = data.text("inventoryId")
    val base = data.long("routeBase")
    val endExclusive = data.long("routeEndExclusive")
    val width = Math.toIntExact(data.long("width"))
    val samples = immutableList((data["samples"] as List<*>).map(::valueLong))
    val evidence = immutableList((data["evidence"] as List<*>).map { it as String })
}

internal fun validateSingleReadDiagnostics(data: Map<String, Any?>, addId: (Map<String, Any?>) -> Unit) {
    val profiles = rows(data["singleReadDiagnosticProfiles"] ?: emptyList<Any>())
    require(profiles.size <= 9) { "At most nine independently selected single-read diagnostics are supported" }
    val retained = rows(data["diagnosticProfiles"] ?: emptyList<Any>())
    for (p in profiles) {
        addId(p)
        require(p.keys == setOf("id", "parentProfile", "inventoryId", "routeBase", "routeEndExclusive", "width",
            "strategy", "samples", "exhaustion", "addressKind", "confidence", "physicalAddress", "reset",
            "function", "dataEffect", "automaticEvents", "evidence")) { "Single-read diagnostic declaration keys differ" }
        require(p.text("id") != "gfx100s-sd-finite-v1") { "A diagnostic cannot replace the default profile" }
        require(retained.count { it["id"] == p["parentProfile"] } == 1) { "Single-read parent must name one retained diagnostic" }
        val span = Interval(p.long("routeBase"), p.long("routeEndExclusive"))
        require(p.long("width") == 4L && span.size == 4L && span.base % 4 == 0L) { "Single-read requires one aligned four-byte word" }
        val samples = p["samples"] as? List<*>
        require(p["strategy"] == "scripted-read" && samples?.size == 1 &&
            samples.all { it is Byte || it is Short || it is Int || it is Long } && valueLong(samples.single()) == 0L &&
            p["exhaustion"] == "refuse") { "Single-read requires exactly one explicit zero then refusal" }
        require(listOf("physicalAddress", "reset", "function", "dataEffect").all { p[it] == null } && p["automaticEvents"] == false) {
            "Single-read cannot assert physical, reset, function or completion state"
        }
        require(p["addressKind"] == "observed-native-callback-address" && p["confidence"] == "HYPOTHESIS")
        require(rows(data["inventory"]).any { it["id"] == p["inventoryId"] }) { "Single-read inventory reference missing" }
        require((p["evidence"] as? List<*>)?.let { it.isNotEmpty() && it.all { path -> path is String && path.isNotBlank() } } == true)
        val occupied = rows(data["windows"]).map { Interval(it.long("base"), Math.addExact(it.long("base"), it.long("size"))) } +
            rows(data["candidates"]).map { Interval(it.long("base"), it.long("endExclusive")) } +
            obj(data["memoryProfiles"]).values.flatMap { rows(it).map { r -> Interval(r.long("base"), r.long("endExclusive")) } } +
            retained.map { Interval(it.long("routeBase"), it.long("routeEndExclusive")) }
        require(occupied.none { it.overlaps(span) }) { "Single-read overlaps an existing route or RAM" }
    }
}
