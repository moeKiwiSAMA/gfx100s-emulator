package com.github.nyabanban.fuji.emulator.catalog

internal fun deviceDependencies(device: Map<String, Any?>): List<String> {
    if ("depends" !in device) return emptyList()
    val owner = device.text("componentId")
    val declared = device["depends"]
    require(declared is List<*>) { "$owner depends must be an array" }
    val dependencies = declared.map { target ->
        require(target is String && target.safeId()) { "$owner depends must contain component IDs" }
        target
    }
    require(dependencies.distinct().size == dependencies.size) { "$owner has duplicate dependencies" }
    return immutableList(dependencies)
}

internal fun validateDeviceDependencies(devices: List<Map<String, Any?>>) {
    componentLoadOrder(devices.associate { it.text("componentId") to deviceDependencies(it) })
}

fun componentLoadOrder(graph: Map<String, List<String>>): List<String> {
    val ordered = linkedSetOf<String>()
    val visiting = linkedSetOf<String>()
    fun visit(id: String) {
        if (id in ordered) return
        require(visiting.add(id)) {
            "Component dependency cycle: " + (visiting.dropWhile { it != id } + id).joinToString(" -> ")
        }
        for (dependency in graph.getValue(id).sorted()) {
            require(dependency in graph) { "Missing component dependency: $id -> $dependency" }
            visit(dependency)
        }
        visiting.remove(id)
        ordered.add(id)
    }
    graph.keys.sorted().forEach(::visit)
    return ordered.toList()
}
