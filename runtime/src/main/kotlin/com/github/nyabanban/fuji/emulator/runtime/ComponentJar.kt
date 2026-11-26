package com.github.nyabanban.fuji.emulator.runtime

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.nyabanban.fuji.emulator.api.ComponentDescriptor
import com.github.nyabanban.fuji.emulator.api.ComponentFactory
import com.github.nyabanban.fuji.emulator.api.ValueKind
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import java.nio.file.Path
import java.util.jar.JarFile

internal data class ComponentJar(
    val path: Path, val id: String, val provider: String, val depends: List<String>, val profiles: List<String>?,
) {
    fun checkProfiles(descriptor: ComponentDescriptor) {
        if (profiles == null) return
        val setting = descriptor.settings.singleOrNull { it.key == "profile" }
        require(setting != null && setting.kind == ValueKind.TEXT && !setting.live &&
            profiles.size == profiles.distinct().size && setting.choices.size == profiles.size &&
            setting.choices.toSet() == profiles.toSet()) { "Component profile contract differs: $id" }
    }

    companion object {
        private val idPattern = Regex("[a-z][a-z0-9-]{0,63}")

        fun read(path: Path, catalog: AddressCatalog?): ComponentJar = JarFile(path.toFile()).use { archive ->
            fun entry(name: String, limit: Long) = requireNotNull(archive.getJarEntry(name)) {
                "Missing $name in $path"
            }.also { require(it.size in 1..limit) { "Invalid $name size in $path" } }

            val service = entry("META-INF/services/${ComponentFactory::class.java.name}", 65536)
            val provider = archive.getInputStream(service).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct().singleOrNull()
            }
            require(provider != null) { "Each JAR must supply exactly one component factory: $path" }
            require(archive.getJarEntry(provider.replace('.', '/') + ".class") != null) { "Factory $provider missing in $path" }
            val descriptor = entry("META-INF/fuji/catalog.json", 4194304)
            val actual = archive.getInputStream(descriptor).use { Formats.json.readTree(it) } as? ObjectNode
                ?: error("Invalid component descriptor in $path")
            val id = actual.path("componentId").asText()
            require(id.matches(idPattern)) { "Invalid component id in $path" }
            val declared = actual.remove("depends")?.let { list ->
                require(list.isArray && list.all { it.isTextual && it.textValue().matches(idPattern) }) {
                    "Invalid depends for $id"
                }
                list.map { it.textValue() }.also { require(it.size == it.distinct().size) { "Duplicate dependency for $id" } }
            }.orEmpty()
            if (catalog != null) {
                val expected = catalog.componentDescriptor(id) - "depends"
                require(actual == Formats.json.readTree(Formats.jsonBytes(expected))) { "Component port contract differs: $id" }
            }
            val profiles = actual.get("profiles")?.map { it.textValue() }
            val dependencies = (declared + catalog?.componentDependencies(id).orEmpty()).distinct()
            ComponentJar(path, id, provider, dependencies, profiles)
        }
    }
}
