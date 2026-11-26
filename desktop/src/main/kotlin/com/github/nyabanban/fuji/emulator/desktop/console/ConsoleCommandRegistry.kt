package com.github.nyabanban.fuji.emulator.desktop.console

import java.util.Locale

/**
 * Outcome returned by a registered [ConsoleCommand] executor.
 */
sealed interface CommandResult {
    /** Command succeeded normally. */
    data object Ok : CommandResult

    /** Command arguments were malformed; registry will print [reason] (if any) and the command's `Usage:`. */
    data class UsageError(val reason: String? = null) : CommandResult

    /** Command failed with a user-facing error message. */
    data class Error(val message: String) : CommandResult

    /** Command requested clean console and emulator shutdown (`stop` / `quit`). */
    data object StopConsole : CommandResult
}

/**
 * Invocation context passed to a registered [ConsoleCommand].
 */
class ConsoleCommandContext(
    val label: String,
    val args: List<String>,
    val registry: ConsoleCommandRegistry,
    val info: (String) -> Unit,
    val warn: (String) -> Unit,
)

/**
 * Metadata and execution handler for an interactive emulator console command.
 */
data class ConsoleCommand(
    val name: String,
    val owner: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val usage: String,
    val executor: (ConsoleCommandContext) -> CommandResult,
) {
    val qualifiedName: String = "${owner.lowercase(Locale.ROOT)}:${name.lowercase(Locale.ROOT)}"
}

/**
 * Namespaced command registry with alias resolution, component ownership, structured usage errors,
 * and typo suggestions for unknown commands.
 */
class ConsoleCommandRegistry {
    private val commands = LinkedHashMap<String, ConsoleCommand>()
    private val lookup = LinkedHashMap<String, ConsoleCommand>()
    private val ownerIndex = LinkedHashMap<String, MutableList<ConsoleCommand>>()

    /** Registers [command] under its canonical `<owner>:<name>`, unqualified `<name>`, and all aliases. */
    fun register(command: ConsoleCommand) {
        val ownerKey = command.owner.lowercase(Locale.ROOT)
        val nameKey = command.name.lowercase(Locale.ROOT)
        require(ownerKey.isNotBlank() && nameKey.isNotBlank()) { "Command owner and name must be non-blank" }
        val qualified = "$ownerKey:$nameKey"

        commands[qualified] = command
        val ownerList = ownerIndex.computeIfAbsent(ownerKey) { ArrayList() }
        ownerList.removeIf { it.qualifiedName == qualified }
        ownerList.add(command)

        lookup[qualified] = command
        lookup.putIfAbsent(nameKey, command)
        for (rawAlias in command.aliases) {
            val aliasKey = rawAlias.lowercase(Locale.ROOT)
            if (aliasKey.isBlank()) continue
            lookup["$ownerKey:$aliasKey"] = command
            lookup.putIfAbsent(aliasKey, command)
        }
    }

    /** Resolves an unqualified name, alias, or `<owner>:<name|alias>` label (case-insensitive). */
    fun resolve(rawLabel: String): ConsoleCommand? {
        val key = rawLabel.trim().removePrefix("/").lowercase(Locale.ROOT)
        if (key.isEmpty()) return null
        return lookup[key]
    }

    /** Returns all registered commands ordered with core (`fuji`) first, then component owners alphabetically. */
    fun allCommands(): List<ConsoleCommand> =
        commands.values.sortedWith(
            compareBy<ConsoleCommand>({ if (it.owner.equals(CORE_OWNER, ignoreCase = true)) 0 else 1 })
                .thenBy { it.owner.lowercase(Locale.ROOT) }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )

    /** Returns all commands registered under [owner] (case-insensitive), sorted by command name. */
    fun commandsByOwner(owner: String): List<ConsoleCommand> =
        ownerIndex[owner.lowercase(Locale.ROOT)]
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()

    /** Returns all registered command owners (`fuji` first, then component owners alphabetically). */
    fun owners(): List<String> =
        ownerIndex.keys.sortedWith(
            compareBy<String>({ if (it == CORE_OWNER) 0 else 1 }).thenBy { it },
        )

    /** Returns the number of commands registered under [owner]. */
    fun commandCountForOwner(owner: String): Int =
        ownerIndex[owner.lowercase(Locale.ROOT)]?.size ?: 0

    /**
     * Suggests the closest registered command name or alias for a mistyped [rawInput],
     * or `null` when no command is sufficiently close.
     */
    fun suggestClosest(rawInput: String): String? {
        val input = rawInput.trim().removePrefix("/").lowercase(Locale.ROOT)
        if (input.isEmpty()) return null

        val searchNamespaced = ':' in input
        val candidates = LinkedHashSet<String>()
        for (cmd in commands.values) {
            if (searchNamespaced) {
                candidates.add(cmd.qualifiedName)
            } else {
                candidates.add(cmd.name.lowercase(Locale.ROOT))
                cmd.aliases.forEach { candidates.add(it.lowercase(Locale.ROOT)) }
            }
        }

        // Exact prefix match first (only for inputs >= 2 chars to avoid noisy 1-char matches).
        if (input.length >= 2) {
            val prefixMatch = candidates
                .filter { it.startsWith(input) }
                .minByOrNull { it.length }
            if (prefixMatch != null) return prefixMatch
        }

        val maxDistance = when {
            input.length <= 2 -> 1
            input.length <= 5 -> 2
            else -> 3
        }
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (candidate in candidates) {
            val dist = levenshtein(input, candidate)
            if (dist in 1..maxDistance && (dist < bestDist || (dist == bestDist && candidate < (best ?: "")))) {
                bestDist = dist
                best = candidate
            }
        }
        return best
    }

    /**
     * Dispatches [rawLabel] with [args], emitting formatted unknown-command suggestions,
     * `Usage:` reminders on [CommandResult.UsageError], and error diagnostics.
     */
    fun dispatch(
        rawLabel: String,
        args: List<String>,
        info: (String) -> Unit,
        warn: (String) -> Unit,
    ): CommandResult {
        val label = rawLabel.trim().removePrefix("/")
        val command = resolve(label)
        if (command == null) {
            val suggestion = suggestClosest(label)
            if (suggestion != null) {
                warn("Unknown command \"$label\". Did you mean \"$suggestion\"? Type \"help\" for help.")
            } else {
                warn("Unknown command \"$label\". Type \"help\" for help.")
            }
            return CommandResult.Error("Unknown command: $label")
        }

        val ctx = ConsoleCommandContext(
            label = label,
            args = args,
            registry = this,
            info = info,
            warn = warn,
        )
        val result = try {
            command.executor(ctx)
        } catch (error: Throwable) {
            val msg = error.cause?.message ?: error.message ?: error.javaClass.simpleName
            warn("Command failed ($label): $msg")
            return CommandResult.Error(msg)
        }

        when (result) {
            is CommandResult.Ok, is CommandResult.StopConsole -> Unit
            is CommandResult.UsageError -> {
                if (!result.reason.isNullOrBlank()) {
                    warn(result.reason)
                }
                info("Usage: ${command.usage}")
            }
            is CommandResult.Error -> warn(result.message)
        }
        return result
    }

    companion object {
        const val CORE_OWNER: String = "fuji"

        internal fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var curr = IntArray(b.length + 1)
            for (i in 1..a.length) {
                curr[0] = i
                val ca = a[i - 1]
                for (j in 1..b.length) {
                    val cost = if (ca == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,
                        curr[j - 1] + 1,
                        prev[j - 1] + cost,
                    )
                }
                val tmp = prev
                prev = curr
                curr = tmp
            }
            return prev[b.length]
        }
    }
}
