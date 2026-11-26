package com.github.nyabanban.fuji.emulator.catalog

import java.nio.file.Path

const val ARM32_LIMIT: Long = 0x1_0000_0000L

/** Every interval is half open; arithmetic never wraps through a signed Int. */
data class Interval(val base: Long, val endExclusive: Long) {
    init { require(base >= 0 && endExclusive > base && endExclusive <= ARM32_LIMIT) { "invalid ARM32 interval" } }
    val size: Long get() = endExclusive - base
    fun contains(address: Long, width: Int): Boolean = width > 0 && address >= base && address <= endExclusive - width.toLong()
    fun overlaps(other: Interval): Boolean = base < other.endExclusive && other.base < endExclusive
    fun toMap(): Map<String, Any> = mapOf("base" to base, "endExclusive" to endExclusive, "size" to size)
}

data class CatalogField(val id: String, val bitOffset: Int, val bitWidth: Int, val data: Map<String, Any?>) {
    val mask: Long get() = ((1L shl bitWidth) - 1) shl bitOffset
    fun toMap(): Map<String, Any?> = data
}

data class CatalogRegister(
    val id: String, val offset: Long, val size: Int, val count: Int, val stride: Long,
    val readWidths: List<Int>, val writeWidths: List<Int>, val alignment: Int,
    val status: String, val behaviorId: String, val fields: List<CatalogField>, val data: Map<String, Any?>
) {
    val endExclusive: Long get() = Math.addExact(offset, Math.addExact(Math.multiplyExact(count - 1L, stride), size.toLong()))
    fun elementAt(relative: Long): Int? {
        if (relative < offset) return null
        val index = (relative - offset) / stride
        return if (index < count && relative - offset - index * stride < size) index.toInt() else null
    }
    fun contains(relative: Long, width: Int): Boolean {
        val element = elementAt(relative) ?: return false
        return width > 0 && relative <= offset + element * stride + size - width
    }
    /** Exact profile match only; an unbound or older profile keeps the base declaration. */
    val writeWidthsByProfile: Map<String, List<Int>> = java.util.Collections.unmodifiableMap(
        obj(data["writeWidthsByProfile"] ?: emptyMap<String, Any?>()).mapValues { (_, value) ->
            immutableList((value as List<*>).map { Math.toIntExact(valueLong(it)) })
        }
    )
    fun effectiveWriteWidths(profile: String?): List<Int> = writeWidthsByProfile[profile] ?: writeWidths
    fun writeAccessDetails(profile: String?): Map<String, Any?> = mapOf(
        "componentProfile" to profile, "declaredWriteWidths" to writeWidths,
        "effectiveWriteWidths" to effectiveWriteWidths(profile), "writeWidthsByProfile" to writeWidthsByProfile
    )
    fun toMap(): Map<String, Any?> = data
}

data class CatalogWindow(
    val id: String, val componentId: String, val portId: String, val deviceId: String,
    val definitionId: String, val space: String, val base: Long, val size: Long, val optional: Boolean,
    val kind: String, val registers: List<CatalogRegister>, val data: Map<String, Any?>
) {
    val endExclusive: Long get() = Math.addExact(base, size)
    val interval: Interval get() = Interval(base, endExclusive)
    fun toMap(): Map<String, Any?> = data + mapOf("registers" to registers.map { it.toMap() })
}

data class CandidateRegion(
    val id: String, val deviceId: String, val space: String, val base: Long, val endExclusive: Long,
    val policy: String, val widths: List<Int>, val alignment: Int, val byteOrder: String,
    val initialFill: Int?, val prohibited: List<Interval>, val data: Map<String, Any?>
) {
    val size: Long get() = endExclusive - base
    val interval: Interval get() = Interval(base, endExclusive)
    fun toMap(): Map<String, Any?> = data
}

data class PortBinding(val componentId: String, val portId: String, val base: Long, val size: Long)

data class CatalogQuery(
    val classification: String, val space: String, val address: Long, val width: Int,
    val window: CatalogWindow? = null, val candidate: CandidateRegion? = null,
    val register: CatalogRegister? = null, val refusalReason: String? = null,
    val details: Map<String, Any?> = emptyMap()
) {
    val permitted: Boolean get() = refusalReason == null
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "classification" to classification, "space" to space, "address" to address, "width" to width,
        "regionId" to (window?.id ?: candidate?.id), "deviceId" to (window?.deviceId ?: candidate?.deviceId),
        "registerId" to register?.id, "fieldIds" to register?.fields?.map { it.id }.orEmpty(),
        "knownCoverage" to (register != null), "candidateCoverage" to (candidate != null),
        "window" to window?.data, "register" to register?.data, "candidate" to candidate?.data,
        "permitted" to permitted, "refusalReason" to refusalReason
    ) + details
}

internal fun valueLong(value: Any?): Long = when (value) {
    is String -> if (value.startsWith("0x", true)) value.substring(2).toLong(16) else value.toLong()
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    else -> error("integer or hexadecimal string required: " + value)
}
internal fun obj(value: Any?): Map<String, Any?> {
    require(value is Map<*, *> && value.keys.all { it is String }) { "string-keyed object required" }
    @Suppress("UNCHECKED_CAST") return value as Map<String, Any?>
}
internal fun rows(value: Any?): List<Map<String, Any?>> = (value as? List<*> ?: error("array required")).map(::obj)
internal fun Map<String, Any?>.text(key: String): String = get(key) as? String ?: error("string required: " + key)
internal fun Map<String, Any?>.long(key: String): Long = valueLong(get(key))
internal fun <T> immutableList(value:List<T>):List<T> = java.util.Collections.unmodifiableList(value)
internal fun Map<String, Any?>.ints(key: String): List<Int> = immutableList((get(key) as? List<*> ?: error("integer array required: " + key)).map { Math.toIntExact(valueLong(it)) })
private val SAFE_ID_REGEX = Regex("[a-zA-Z0-9][a-zA-Z0-9._:/-]*")
internal fun String.safeId(): Boolean = matches(SAFE_ID_REGEX)
