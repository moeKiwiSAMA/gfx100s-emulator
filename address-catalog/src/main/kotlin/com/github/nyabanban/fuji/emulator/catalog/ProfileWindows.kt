package com.github.nyabanban.fuji.emulator.catalog

/** Mutually exclusive, finite physical layouts. The default dictionary remains unchanged. */
internal fun profileWindowRows(data: Map<String,Any?>) = rows(data["profileWindows"] ?: emptyList<Any>())

internal fun selectedWindowRows(data: Map<String,Any?>, profiles: Map<String,String>): List<Map<String,Any?>> {
    var selected = rows(data["windows"])
    profileWindowRows(data).filter { profiles[it.text("componentId")] == it.text("profile") }.forEach { variant ->
        val removed = (variant["replacePortIds"] as List<*>).toSet()
        selected = selected.filter { it["portId"] !in removed } + rows(variant["windows"])
    }
    val sorted = selected.sortedBy { it.long("base") }
    sorted.zipWithNext().forEach { (a,b) -> require(Math.addExact(a.long("base"),a.long("size")) <= b.long("base")) { "Selected profile windows overlap" } }
    require(selected.map { it["portId"] }.distinct().size == selected.size) { "Selected profile has duplicate ports" }
    return selected
}

internal fun validateProfileWindows(data: Map<String,Any?>, id: (Map<String,Any?>)->Unit) {
    val variants = profileWindowRows(data)
    require(variants.size <= 32) { "Too many profile window alternatives" }
    val identities = HashSet<Pair<String,String>>()
    val defaults = rows(data["windows"])
    val defaultPorts = defaults.associateBy { it.text("portId") }
    val alternatePorts = HashSet<String>()
    variants.forEach { variant ->
        require(variant.keys == setOf("id","componentId","profile","replacePortIds","windows")) { "Invalid profile window declaration" }
        id(variant)
        val component = variant.text("componentId"); val profile = variant.text("profile")
        require(identities.add(component to profile)) { "Duplicate component/profile alternative" }
        val device = rows(data["devices"]).singleOrNull { it["componentId"] == component } ?: error("Alternative owner absent")
        require(profile in (device["profiles"] as? List<*>).orEmpty()) { "Alternative profile absent from owner" }
        val replaced = variant["replacePortIds"] as? List<*> ?: error("Alternative replacement ports required")
        require(replaced.isNotEmpty() && replaced.size <= 256 && replaced.all { it is String } && replaced.distinct().size == replaced.size) { "Invalid alternative replacement ports" }
        replaced.forEach { port -> require(defaultPorts[port]?.get("componentId") == component) { "Alternative must replace existing ports of its own component" } }
        val additions = rows(variant["windows"])
        require(additions.isNotEmpty() && additions.size <= 256) { "Invalid alternative windows" }
        additions.forEach { w ->
            id(w)
            require(w["componentId"] == component && w["optional"] == true) { "Alternative windows must be opt-in and owned" }
            require(w.text("portId") !in defaultPorts && alternatePorts.add(w.text("portId"))) { "Alternative port must be globally unique" }
        }
        val selected = selectedWindowRows(data, mapOf(component to profile))
        val selectedIds = selected.map { it["id"] }.toSet()
        val addedSpans = additions.map { Interval(it.long("base"), Math.addExact(it.long("base"), it.long("size"))) }
        // Reuse every window/RAM/candidate/diagnostic check; static device/protocol/flash definitions
        // were already validated on the top-level catalog and are invariant across profileVariants.
        // Legacy single-read diagnostic hooks superseded by an opt-in alternative window are excluded for that variant.
        CatalogCompiler.validate((data - "profileWindows") + mapOf("windows" to selected,
            "mirrors" to rows(data["mirrors"] ?: emptyList<Any>()).filter { (it["windowIds"] as List<*>).all { name -> name in selectedIds } },
            "singleReadDiagnosticProfiles" to rows(data["singleReadDiagnosticProfiles"] ?: emptyList<Any>()).filter { p ->
                val pSpan = Interval(p.long("routeBase"), p.long("routeEndExclusive"))
                addedSpans.none { it.overlaps(pSpan) }
            }), validateStaticDefinitions = false)
    }
    // Independently selected owners must also be composable. Alternatives of one owner are exclusive.
    variants.forEachIndexed { i,a -> variants.drop(i+1).filter { it["componentId"] != a["componentId"] }.forEach { b ->
        selectedWindowRows(data, mapOf(a.text("componentId") to a.text("profile"),b.text("componentId") to b.text("profile")))
    } }
}
