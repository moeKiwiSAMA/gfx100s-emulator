package com.github.nyabanban.fuji.emulator.desktop

import com.fasterxml.jackson.databind.DeserializationFeature
import com.github.nyabanban.fuji.emulator.api.AdditionalRamRegions
import com.github.nyabanban.fuji.emulator.api.MemoryRegion
import com.github.nyabanban.fuji.emulator.catalog.AddressCatalog
import com.github.nyabanban.fuji.emulator.firmware.FirmwareMetadata
import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.file.Files
import java.nio.file.Path

@ConsistentCopyVisibility
data class AdditionalRamPlan private constructor(
    val baseProfile: String,
    val sourceProfile: String,
    val sourceCommit: String,
    val catalogSha256: String,
    val baseRegions: List<MemoryRegion>,
    val additionalRegions: List<MemoryRegion>,
    val profile: String = AdditionalRamRegions.PROFILE
) {
    init {
        require(baseProfile.matches(TEXT) && sourceProfile.matches(TEXT) && baseProfile != sourceProfile) {
            "Additional RAM requires distinct bounded profile names"
        }
        require(sourceCommit.matches(Regex("[0-9a-f]{40}"))) { "Additional RAM source commit requires 40 lowercase hex digits" }
        require(catalogSha256.matches(Regex("[0-9a-f]{64}"))) { "Additional RAM catalog digest requires 64 lowercase hex digits" }
        require((baseRegions + additionalRegions).all { it.id.matches(TEXT) }) { "Invalid additional RAM region ID" }
        AdditionalRamRegions.validate(baseRegions, additionalRegions, profile)
    }

    fun receipt(): Map<String, Any> = mapOf(
        "schema" to 1, "profile" to profile,
        "baseProfile" to baseProfile, "sourceProfile" to sourceProfile, "sourceCommit" to sourceCommit,
        "catalogSha256" to catalogSha256, "baseRegions" to rows(baseRegions), "additionalRegions" to rows(additionalRegions))

    companion object {
        const val MAX_INPUT_BYTES = 32768
        private val TEXT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val KEYS = setOf("schema", "profile", "baseProfile", "sourceProfile", "sourceCommit",
            "catalogSha256", "baseRegions", "additionalRegions")

        @JvmOverloads
        operator fun invoke(
            baseProfile: String, sourceProfile: String, sourceCommit: String, catalogSha256: String,
            baseRegions: List<MemoryRegion>, additionalRegions: List<MemoryRegion>,
            profile: String = AdditionalRamRegions.PROFILE
        ): AdditionalRamPlan = AdditionalRamPlan(baseProfile, sourceProfile, sourceCommit, catalogSha256,
            java.util.List.copyOf(baseRegions), java.util.List.copyOf(additionalRegions), profile)

        fun parse(raw: Map<String, Any?>): AdditionalRamPlan {
            require(raw.keys == KEYS) { "Invalid additional RAM plan fields" }
            require(integer(raw["schema"]) == 1L && raw["profile"] in AdditionalRamRegions.PROFILES) {
                "Unsupported additional RAM schema or profile"
            }
            fun text(key: String): String = raw[key] as? String ?: throw IllegalArgumentException("Additional RAM $key requires text")
            return invoke(text("baseProfile"), text("sourceProfile"), text("sourceCommit"), text("catalogSha256"),
                regions(raw["baseRegions"]), regions(raw["additionalRegions"]), text("profile"))
        }

        fun parseJson(text: String): AdditionalRamPlan {
            require(text.toByteArray(Charsets.UTF_8).size in 1..MAX_INPUT_BYTES) { "Additional RAM input exceeds 32KiB" }
            val raw = runCatching {
                Formats.json.readerFor(Map::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue<Map<*, *>>(text)
            }.getOrElse { throw IllegalArgumentException("Invalid additional RAM JSON", it) }
            require(raw.keys.all { it is String }) { "Additional RAM requires an object" }
            return parse(raw.entries.associate { it.key as String to it.value })
        }

        fun read(path: Path): AdditionalRamPlan {
            val size = runCatching { Files.size(path) }.getOrElse {
                throw IllegalArgumentException("Additional RAM input is not a readable file", it)
            }
            require(size in 1..MAX_INPUT_BYTES.toLong()) { "Additional RAM input exceeds 32KiB" }
            val bytes = runCatching { Files.newInputStream(path).use { it.readNBytes(MAX_INPUT_BYTES + 1) } }
                .getOrElse { throw IllegalArgumentException("Additional RAM input is not readable", it) }
            require(bytes.size in 1..MAX_INPUT_BYTES) { "Additional RAM input exceeds 32KiB" }
            val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            return parseJson(text)
        }

        @JvmOverloads
        fun fromCatalog(catalog: AddressCatalog, baseProfile: String, sourceProfile: String, sourceCommit: String,
            profile: String = AdditionalRamRegions.PROFILE): AdditionalRamPlan {
            val profiles = FirmwareMetadata(Formats.map(catalog.firmwareMetadata(emptyMap()))).memoryProfiles
            val base = profiles[baseProfile] ?: throw IllegalArgumentException("Unknown additional RAM base profile")
            val source = profiles[sourceProfile] ?: throw IllegalArgumentException("Unknown additional RAM source profile")
            require(source.map { it.id }.distinct().size == source.size) { "Duplicate source RAM IDs" }
            val sourceById = source.associateBy { it.id }
            require(base.all { sourceById[it.id] == it }) { "Additional RAM source changes or deletes a base region" }
            val baseIds = base.map { it.id }.toSet()
            return invoke(baseProfile, sourceProfile, sourceCommit, catalog.artifactSha256, base,
                source.filter { it.id !in baseIds }, profile)
        }

        internal fun regions(raw: Any?): List<MemoryRegion> {
            require(raw is List<*>) { "Additional RAM regions require an array" }
            return java.util.List.copyOf(raw.map {
                require(it is Map<*, *> && it.keys == setOf("id", "address", "size", "permissions")) {
                    "Invalid additional RAM region fields"
                }
                val id = it["id"] as? String ?: throw IllegalArgumentException("RAM region ID requires text")
                val permissions = integer(it["permissions"])
                require(permissions in 1..7) { "Invalid RAM permissions" }
                MemoryRegion(id, integer(it["address"]), integer(it["size"]), permissions.toInt())
            })
        }

        internal fun rows(regions: List<MemoryRegion>): List<Map<String, Any>> = regions.map {
            mapOf("id" to it.id, "address" to it.address, "size" to it.size, "permissions" to it.permissions)
        }

        private fun integer(value: Any?): Long {
            require(value is Byte || value is Short || value is Int || value is Long) { "Additional RAM requires an exact 64-bit integer" }
            return (value as Number).toLong()
        }
    }
}
