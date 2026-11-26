package com.github.nyabanban.fuji.emulator.api

import java.nio.file.Path
import java.util.concurrent.CompletableFuture

const val COMPONENT_ABI = 1
enum class ValueKind { BOOLEAN, INTEGER, DECIMAL, TEXT }
data class Setting(
    val key: String, val kind: ValueKind, val default: Any,
    val minimum: Double? = null, val maximum: Double? = null,
    val choices: List<String> = emptyList(), val live: Boolean = true,
    val description: String = "",
) {
    fun validate(value: Any): Any {
        val result: Any = when (kind) {
            ValueKind.BOOLEAN -> { require(value is Boolean) { "$key requires boolean" }; value }
            ValueKind.INTEGER -> {
                require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()) { "$key requires integer" }
                value.toLong()
            }
            ValueKind.DECIMAL -> { require(value is Number && value.toDouble().isFinite()) { "$key requires finite number" }; value.toDouble() }
            ValueKind.TEXT -> { require(value is String && value.length <= 4096) { "$key requires bounded text" }; value }
        }
        if (result is Number) {
            require(minimum == null || result.toDouble() >= minimum) { "$key below minimum" }
            require(maximum == null || result.toDouble() <= maximum) { "$key above maximum" }
        }
        require(choices.isEmpty() || result in choices) { "$key invalid choice" }
        return result
    }
}
data class ComponentDescriptor(val id: String, val version: String, val abi: Int = COMPONENT_ABI, val settings: List<Setting> = emptyList())

/** Factory is discovered in each hardware JAR via ServiceLoader; API remains parent loaded. */
interface ComponentFactory {
    val descriptor: ComponentDescriptor
    fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent
}
interface HardwareComponent : AutoCloseable {
    val id: String
    /** Pure cross-field/resource validation on the owner thread, before persistence or mutation. */
    fun validateSettings(settings: Map<String, Any>) {}
    /** Called only on the emulation owner thread after validation and persistence. Must apply atomically. */
    fun applySettings(settings: Map<String, Any>)
    fun state(): Map<String, Any?>
    /** Restart-only routes, validated together before guest execution. Shared lines need a qualified combiner. */
    fun interruptRoutes(): List<InterruptRoute> = emptyList()
    /** Opt-in metadata windows required by this component's frozen configuration; names, never addresses. */
    fun additionalMmioPorts(): List<String> = emptyList()
    fun start() {}
    override fun close() {}
}
interface ComponentContext {
    val directory: Path
    val ports: Ports
    val events: EventChannel
    val clock: EmulationClock
    /** True only when the host is running in user-facing `relaxed` mode (never in `strict` or `discovery`). */
    val relaxedMode: Boolean get() = false
    /** Data paths must remain inside this component directory. */
    fun dataPath(relative: String): Path
    /** Bounded, duplicate-checked YAML/JSON data inside this component; load only on the owner thread. */
    fun readData(relative: String): Map<String, Any?> = error("component data reader unavailable")
}
fun interface EmulationClock { fun ticks(): Long }

/** Hot data plane: typed direct calls, no JSON, reflection or unbounded queues. */
interface Ports {
    fun <T : Any> register(owner: String, name: String, type: Class<T>, port: T)
    fun <T : Any> find(name: String, type: Class<T>): T?
    fun removeOwner(owner: String)
}
data class ComponentEvent(val source: String, val kind: String, val tick: Long, val values: Map<String, Any?>)
/** Owner-thread zero-allocation observer for GIC IRQ line edge transitions. */
fun interface IrqLineListener {
    fun onIrqLine(tick: Long, line: Int, high: Boolean)
}
/** Owner-thread synchronous low-volume notifications. Payloads must be immutable. */
interface EventChannel {
    fun publish(event: ComponentEvent)
    fun subscribe(listener: (ComponentEvent) -> Unit): AutoCloseable
    /**
     * Hot-path IRQ line edge publication. Implementations may dispatch directly to primitive
     * observers without allocating a [ComponentEvent] or [Map] when no general listeners exist.
     */
    fun publishIrqLine(source: String, tick: Long, line: Int, high: Boolean) {
        publish(ComponentEvent(source, "irq-line", tick, java.util.Map.of("operation", "line", "tick", tick, "line", line, "high", high)))
    }
}
interface ControlClient {
    fun snapshot(): CompletableFuture<Map<String, Any?>>
    fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>>
}

/** Utility for components with scalar settings; registry rejects unknown keys. */
abstract class ScalarComponent(
    final override val id: String, protected val context: ComponentContext, initial: Map<String, Any>,
) : HardwareComponent {
    protected var values = initial.toMap()
    override fun applySettings(settings: Map<String, Any>) { values = values + settings }
    override fun state(): Map<String, Any?> = values.toMap()
    protected fun saveScalarValues(): Map<String, Any> = LinkedHashMap(values)
    protected fun restoreScalarValues(
        raw: Any?,
        descriptor: ComponentDescriptor? = null,
        compatibleNonLiveKeys: Set<String> = emptySet(),
    ) {
        val supplied = raw as? Map<*, *> ?: error("Missing scalar settings map for $id")
        if (descriptor != null) {
            require(supplied.keys.all { it is String && descriptor.settings.any { s -> s.key == it } }) {
                "Unknown scalar setting key in snapshot for $id"
            }
            val next = LinkedHashMap<String, Any>(values)
            for (setting in descriptor.settings) {
                if (!supplied.containsKey(setting.key)) continue
                val validated = setting.validate(checkNotNull(supplied[setting.key]) { "Null setting ${setting.key} in $id" })
                if (!setting.live) {
                    if (setting.key !in compatibleNonLiveKeys) {
                        require(values[setting.key] == validated) {
                            "Restart-only setting ${setting.key} differs in snapshot for $id"
                        }
                    }
                } else {
                    next[setting.key] = validated
                }
            }
            validateSettings(next)
            values = next.toMap()
        } else {
            require(supplied.keys.map { it.toString() }.toSet() == values.keys) { "Scalar setting keys differ for $id" }
            val next = LinkedHashMap<String, Any>()
            for ((k, v) in supplied) {
                next[k.toString()] = checkNotNull(v) { "Null scalar setting $k in $id" }
            }
            validateSettings(next)
            values = next.toMap()
        }
    }
}
