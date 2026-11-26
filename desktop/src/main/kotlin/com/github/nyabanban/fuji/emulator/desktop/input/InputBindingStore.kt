package com.github.nyabanban.fuji.emulator.desktop.input

import com.github.nyabanban.fuji.emulator.runtime.Formats
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Atomic YAML persistence for [InputBindingConfig] at `<home>/input-bindings.yaml`.
 *
 * A missing file yields the defaults without writing anything. A corrupt or incompatible file logs
 * `WARN [FALLBACK][input-bindings]` and also yields the defaults; the bad file is left untouched until the user
 * changes a binding, so nothing is silently destroyed. Individual unknown actions / inputs (e.g. from a newer
 * build) are skipped with a warning instead of discarding the whole file.
 */
class InputBindingStore(val path: Path) {
    private val log = LoggerFactory.getLogger(InputBindingStore::class.java)

    fun load(): InputBindingConfig {
        if (!Files.isRegularFile(path)) return InputBindingConfig.default()
        return try {
            fromYamlMap(Formats.readData(path)) { message -> log.warn("[FALLBACK][input-bindings] {} in {}", message, path) }
        } catch (error: Exception) {
            log.warn(
                "[FALLBACK][input-bindings] Ignoring unreadable {} ({}); using defaults (empty keyboard, default gamepad profile)",
                path, error.message ?: error.javaClass.simpleName,
            )
            InputBindingConfig.default()
        }
    }

    fun save(config: InputBindingConfig) {
        Formats.atomicYaml(path, toYamlMap(config))
    }

    companion object {
        const val FILE_NAME: String = "input-bindings.yaml"

        fun defaultPath(home: Path): Path = home.resolve(FILE_NAME)

        fun toYamlMap(config: InputBindingConfig): Map<String, Any> = linkedMapOf(
            "schema" to InputBindingConfig.SCHEMA,
            "gamepadEnabled" to config.gamepadEnabled,
            "stickDeadzone" to config.stickDeadzone,
            "twoStageTriggerEnabled" to config.twoStageTriggerEnabled,
            "triggerFullThreshold" to config.triggerFullThreshold,
            "selectedGamepad" to config.selectedGamepad,
            "keyboard" to CameraInputAction.entries.mapNotNull { action ->
                config.keyboardBindings[action]?.let { specs -> action.name to specs.map { it.encode() } }
            }.toMap(LinkedHashMap()),
            "gamepad" to CameraInputAction.entries.mapNotNull { action ->
                config.gamepadBindings[action]?.let { specs -> action.name to specs.map { it.input.name } }
            }.toMap(LinkedHashMap()),
        )

        /** Strict on structure (throws), lenient on unknown entries (reported through [warn] and skipped). */
        fun fromYamlMap(raw: Map<String, Any?>, warn: (String) -> Unit = {}): InputBindingConfig {
            val schema = (raw["schema"] as? Number)?.toInt()
            require(schema == InputBindingConfig.SCHEMA) { "Unsupported input-bindings schema: ${raw["schema"]}" }
            val defaults = InputBindingConfig.default()
            val gamepadEnabled = raw["gamepadEnabled"]?.let { require(it is Boolean) { "gamepadEnabled must be boolean" }; it }
                ?: defaults.gamepadEnabled
            val deadzone = raw["stickDeadzone"]?.let { require(it is Number) { "stickDeadzone must be numeric" }; it.toDouble() }
                ?: defaults.stickDeadzone
            val twoStage = raw["twoStageTriggerEnabled"]?.let { require(it is Boolean) { "twoStageTriggerEnabled must be boolean" }; it }
                ?: defaults.twoStageTriggerEnabled
            val triggerFull = raw["triggerFullThreshold"]?.let { require(it is Number) { "triggerFullThreshold must be numeric" }; it.toDouble() }
                ?: defaults.triggerFullThreshold

            fun section(name: String): Map<*, *>? = raw[name]?.let { value ->
                require(value is Map<*, *>) { "$name must be a mapping" }
                value
            }

            fun entries(name: String): List<Pair<CameraInputAction, List<String>>> {
                val section = section(name) ?: return emptyList()
                return section.mapNotNull { (key, value) ->
                    val action = CameraInputAction.entries.firstOrNull { it.name == key }
                    if (action == null) {
                        warn("Skipping unknown $name action '$key'")
                        return@mapNotNull null
                    }
                    val list = when (value) {
                        is String -> listOf(value)
                        is List<*> -> value.map { item -> require(item is String) { "$name.$key entries must be strings" }; item }
                        null -> emptyList()
                        else -> throw IllegalArgumentException("$name.$key must be a string or list of strings")
                    }
                    action to list
                }
            }

            val usedKeys = HashSet<KeyBindingSpec>()
            val keyboard = LinkedHashMap<CameraInputAction, List<KeyBindingSpec>>()
            entries("keyboard").forEach { (action, texts) ->
                val specs = texts.mapNotNull { text ->
                    val spec = runCatching { KeyBindingSpec.parse(text) }.getOrElse {
                        warn("Skipping invalid keyboard binding '$text' for $action (${it.message})")
                        return@mapNotNull null
                    }
                    if (!usedKeys.add(spec)) {
                        warn("Skipping duplicate keyboard binding '$text' for $action")
                        null
                    } else spec
                }
                if (specs.isNotEmpty()) keyboard[action] = specs
            }

            val gamepadSection = section("gamepad")
            val gamepad = if (gamepadSection == null) {
                defaults.gamepadBindings
            } else {
                val usedPads = HashSet<GamepadInput>()
                val parsed = LinkedHashMap<CameraInputAction, List<GamepadBindingSpec>>()
                entries("gamepad").forEach { (action, texts) ->
                    val specs = texts.mapNotNull { text ->
                        val input = GamepadInput.entries.firstOrNull { it.name == text }
                        when {
                            input == null -> { warn("Skipping unknown gamepad input '$text' for $action"); null }
                            !usedPads.add(input) -> { warn("Skipping duplicate gamepad input '$text' for $action"); null }
                            else -> GamepadBindingSpec(input)
                        }
                    }
                    if (specs.isNotEmpty()) parsed[action] = specs
                }
                parsed
            }
            val clampedDeadzone = deadzone.coerceIn(InputBindingConfig.MIN_STICK_DEADZONE, InputBindingConfig.MAX_STICK_DEADZONE)
            if (clampedDeadzone != deadzone) warn("Clamping stickDeadzone $deadzone to $clampedDeadzone")
            val clampedTriggerFull = triggerFull.coerceIn(
                InputBindingConfig.MIN_TRIGGER_FULL_THRESHOLD,
                InputBindingConfig.MAX_TRIGGER_FULL_THRESHOLD,
            )
            if (clampedTriggerFull != triggerFull) warn("Clamping triggerFullThreshold $triggerFull to $clampedTriggerFull")
            val selected = when (val value = raw["selectedGamepad"]) {
                null -> GamepadSelection.AUTO
                is String -> runCatching { GamepadSelection.parse(value).encode() }.getOrElse {
                    warn("Ignoring invalid selectedGamepad '$value' (${it.message}); using auto")
                    GamepadSelection.AUTO
                }
                else -> {
                    warn("Ignoring non-string selectedGamepad; using auto")
                    GamepadSelection.AUTO
                }
            }
            return InputBindingConfig(
                gamepadEnabled = gamepadEnabled,
                stickDeadzone = clampedDeadzone,
                twoStageTriggerEnabled = twoStage,
                triggerFullThreshold = clampedTriggerFull,
                keyboardBindings = keyboard,
                gamepadBindings = gamepad,
                selectedGamepad = selected,
            )
        }
    }
}
