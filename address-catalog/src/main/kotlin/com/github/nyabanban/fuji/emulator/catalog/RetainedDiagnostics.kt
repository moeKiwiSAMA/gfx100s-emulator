package com.github.nyabanban.fuji.emulator.catalog

/** Experimental routing metadata, deliberately absent from physical address/ownership queries. */
class CatalogRetainedDiagnostic internal constructor(val data: Map<String, Any?>) {
    val id = data.text("id")
    val inventoryId = data.text("inventoryId")
    val base = data.long("routeBase")
    val endExclusive = data.long("routeEndExclusive")
    val width = Math.toIntExact(data.long("width"))
    val writeValue = data.long("writeValue")
    val successfulReadLimit = Math.toIntExact(data.long("successfulReadLimit"))
    val evidence = immutableList((data["evidence"] as List<*>).map { it as String })
}

internal fun validateRetainedDiagnostics(data: Map<String, Any?>, addId: (Map<String, Any?>) -> Unit) {
    val profiles = rows(data["diagnosticProfiles"] ?: emptyList<Any>())
    require(profiles.size <= 14) { "At most fourteen independently selected single-word diagnostics are supported" }
    for (p in profiles) {
        addId(p)
        require(p.keys == setOf("id", "inventoryId", "routeBase", "routeEndExclusive", "width", "strategy",
            "writeValue", "successfulReadLimit", "initialValue", "repeatWrite", "addressKind", "confidence",
            "physicalAddress", "reset", "dataEffect", "automaticEvents", "evidence")) { "Diagnostic declaration keys differ" }
        require(p.text("id") != "gfx100s-sd-finite-v1") { "A diagnostic cannot replace the default profile" }
        val span = Interval(p.long("routeBase"), p.long("routeEndExclusive"))
        require(p.long("width") == 4L && span.size == 4L && span.base % 4 == 0L) { "Diagnostic requires one aligned four-byte word" }
        require(p["strategy"] == "retained-bytes" && p.long("writeValue") == 1L && p.long("successfulReadLimit") in 1..2097152) { "Unsupported retained diagnostic law" }
        require(p["initialValue"] == null && p["physicalAddress"] == null && p["reset"] == null && p["dataEffect"] == null && p["automaticEvents"] == false) { "Diagnostic cannot assert initial/physical/completion state" }
        require(p["repeatWrite"] == "refuse" && p["addressKind"] == "observed-native-callback-address" && p["confidence"] == "HYPOTHESIS")
        require(rows(data["inventory"]).any { it["id"] == p["inventoryId"] }) { "Diagnostic inventory reference missing" }
        require((p["evidence"] as? List<*>)?.let { it.isNotEmpty() && it.all { path -> path is String && path.isNotBlank() } } == true)
        val occupied = rows(data["windows"]).map { Interval(it.long("base"), Math.addExact(it.long("base"), it.long("size"))) } +
            rows(data["candidates"]).map { Interval(it.long("base"), it.long("endExclusive")) } +
            obj(data["memoryProfiles"]).values.flatMap { rows(it).map { r -> Interval(r.long("base"), r.long("endExclusive")) } }
        require(occupied.none { it.overlaps(span) }) { "Diagnostic overlaps an existing route or RAM" }
    }
}
