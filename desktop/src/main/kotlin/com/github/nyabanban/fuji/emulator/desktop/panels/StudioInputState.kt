package com.github.nyabanban.fuji.emulator.desktop.panels

import java.util.concurrent.CompletableFuture

/** UI intent is separate from observed hardware. The caller serializes methods and completions on its UI thread. */
class StudioInputState(private val send: (String, Boolean) -> CompletableFuture<*>,
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val changed: () -> Unit = {}, private val failure: (Throwable) -> Unit = {},
    latchingKeys: Set<String> = PanelCatalog.latchingButtonKeys,
    private val appliedTickProvider: (() -> Long)? = null,
    private val onPressApplied: (String, Long) -> Unit = { _, _ -> }) {
    private val latchingKeys: Set<String> = latchingKeys.toSet().also { keys ->
        require(keys.all { it in PanelCatalog.buttonKeys }) { "Unknown latching button key" }
    }
    private val sources = PanelCatalog.buttonKeys.associateWith { linkedSetOf<String>() }
    private val desired = PanelCatalog.buttonKeys.associateWith { false }.toMutableMap()
    private val observed: MutableMap<String, Boolean?> = PanelCatalog.buttonKeys.associateWith { null as Boolean? }.toMutableMap()
    private val versions = PanelCatalog.buttonKeys.associateWith { 0L }.toMutableMap()
    enum class Submission { PENDING, APPLIED, FAILED }
    data class Request(val down: Boolean, val submission: Submission)
    private val requests = mutableMapOf<String, Request>()
    private val appliedTicks = mutableMapOf<String, Long>()
    internal fun appliedAt(key: String, down: Boolean): Long? {
        val request = requests[key] ?: return null
        return if (request.submission == Submission.APPLIED && request.down == down) appliedTicks[key] else null
    }
    fun lastRequest(key: String): Request? = requests[key]
    var pending = 0; private set
    var failureCount = 0L; private set
    var latchMode = false; private set
    private var available = PanelCatalog.buttonKeys
    fun supports(key: String) = key in available
    fun isLatching(key: String) = key in latchingKeys
    fun observe(state: Map<String, Any?>) {
        val keys = PanelCatalog.buttonKeys.intersect(state.keys)
        require(
            keys == PanelCatalog.buttonKeys ||
                keys == PanelCatalog.buttonKeys - setOf("frontDialPress", "fn4", "focusStickPress") ||
                keys == PanelCatalog.buttonKeys - setOf("drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress") ||
                keys == PanelCatalog.buttonKeys - setOf("movieMode", "drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress") ||
                keys == PanelCatalog.buttonKeys - setOf("delete", "rearDialPress", "movieMode", "drive", "afOn", "focusMode", "frontDialPress", "fn4", "focusStickPress")
        )
        require(keys.all { state[it] is Boolean })
        var mutated = (available != keys)
        available = keys
        PanelCatalog.buttonKeys.forEach { key ->
            val next = state[key] as Boolean?
            if (observed[key] != next) {
                observed[key] = next
                mutated = true
            }
        }
        available.filter(::isLatching).forEach { key ->
            if (requests[key]?.submission != Submission.PENDING) {
                val isOn = observed[key] == true
                if (desired[key] != isOn) {
                    desired[key] = isOn
                    mutated = true
                }
                val held = sources.getValue(key)
                if (isOn) {
                    if (held.size != 1 || "switch:$key" !in held) {
                        held.clear()
                        held += "switch:$key"
                        mutated = true
                    }
                } else if (held.isNotEmpty()) {
                    held.clear()
                    mutated = true
                }
            }
        }
        if (mutated) changed()
    }
    fun observed(key: String): Boolean? = observed.getValue(key)
    fun desired(key: String): Boolean = desired.getValue(key)
    fun isLocked(key: String): Boolean = !isLatching(key) && LOCK_SOURCE in sources.getValue(key)
    fun lockedKeys(): Set<String> = PanelCatalog.buttonKeys.filterTo(linkedSetOf()) { isLocked(it) }
    private fun transition(key: String, down: Boolean, force: Boolean = false) {
        require(key in PanelCatalog.buttonKeys)
        val old = desired.getValue(key)
        if (!force && old == down) return
        desired[key] = down
        val version = versions.getValue(key) + 1; versions[key] = version
        requests[key] = Request(down, Submission.PENDING)
        pending++; changed()
        val result = runCatching { send(key, down) }.getOrElse { CompletableFuture.failedFuture<Any>(it) }
        result.whenComplete { _, error ->
            val appliedTick = if (error == null) appliedTickProvider?.invoke() else null
            dispatch {
                pending--
                if (versions.getValue(key) == version) {
                    requests[key] = Request(down, if (error == null) Submission.APPLIED else Submission.FAILED)
                    if (error != null) {
                        desired[key] = old
                        if (down && !old) sources.getValue(key).remove(LOCK_SOURCE)
                    } else if (appliedTick != null) {
                        appliedTicks[key] = appliedTick
                        if (down) onPressApplied(key, appliedTick)
                    }
                }
                if (error != null) {
                    failureCount++; failure(error)
                }
                changed()
            }
        }
    }
    fun press(source: String, key: String) {
        require(source.startsWith("mouse:") || source.startsWith("keyboard:") || source.startsWith("gamepad:"))
        val held = sources.getValue(key)
        if (!supports(key)) return
        OPPOSITE_DIRECTION_KEYS[key]?.let { opposite ->
            if (desired.getValue(opposite) || isLocked(opposite)) releaseKey(opposite)
        }
        if (held.add(source)) transition(key, true)
    }
    fun release(source: String, key: String) {
        val held = sources.getValue(key)
        if (held.remove(source) && held.isEmpty()) transition(key, false)
    }
    fun releaseKey(key: String) {
        val held = sources.getValue(key)
        val hadLock = LOCK_SOURCE in held
        held.clear()
        if (desired.getValue(key)) transition(key, false)
        else if (hadLock) changed()
    }
    fun lock(key: String): Boolean {
        require(key in PanelCatalog.buttonKeys)
        if (!supports(key) || isLatching(key)) return false
        OPPOSITE_DIRECTION_KEYS[key]?.let { opposite ->
            if (desired.getValue(opposite) || isLocked(opposite)) releaseKey(opposite)
        }
        val held = sources.getValue(key)
        val added = held.add(LOCK_SOURCE)
        if (!desired.getValue(key)) {
            transition(key, true)
        } else if (added) {
            changed()
        }
        return true
    }
    fun unlock(key: String): Boolean {
        require(key in PanelCatalog.buttonKeys)
        val held = sources.getValue(key)
        if (!held.remove(LOCK_SOURCE)) return false
        if (held.isEmpty()) transition(key, false) else changed()
        return true
    }
    fun toggleLock(key: String): Boolean {
        require(key in PanelCatalog.buttonKeys)
        return if (isLocked(key)) {
            unlock(key)
            false
        } else {
            lock(key)
        }
    }
    fun unlockAll(): Int {
        var count = 0
        PanelCatalog.buttonKeys.filter(::isLocked).forEach { key ->
            if (unlock(key)) count++
        }
        return count
    }
    fun toggle(key: String) {
        val held = sources.getValue(key)
        if (!supports(key)) return
        if (desired.getValue(key)) { held.clear(); transition(key, false) }
        else {
            OPPOSITE_DIRECTION_KEYS[key]?.let { opposite ->
                if (desired.getValue(opposite) || isLocked(opposite)) releaseKey(opposite)
            }
            held += if (isLatching(key)) "switch:$key" else "latch"
            transition(key, true)
        }
    }
    fun setLatching(key: String, on: Boolean) {
        require(key in PanelCatalog.buttonKeys && isLatching(key)) { "Key '$key' is not a latching button" }
        if (!supports(key)) return
        val held = sources.getValue(key)
        if (on) {
            held.clear()
            held += "switch:$key"
        } else {
            held.clear()
        }
        val force = observed[key] != null && observed[key] != on
        transition(key, on, force = force)
    }
    fun pulse(key: String) { press("keyboard:pulse", key); release("keyboard:pulse", key) }
    fun setLatch(on: Boolean) {
        latchMode = on
        if (!on) {
            PanelCatalog.buttonKeys.filterNot(::isLatching).forEach { key ->
                val held = sources.getValue(key)
                held.remove("latch")
                if (held.isEmpty()) transition(key, false)
            }
        }
        changed()
    }
    /** Tab changes, pointer release outside a hotspot and focus loss release transient ownership. */
    fun releaseTransient(prefix: String? = null) {
        PanelCatalog.buttonKeys.reversed().filterNot(::isLatching).forEach { key ->
            val held = sources.getValue(key)
            held.removeIf { value -> if (prefix == null) value.startsWith("mouse:") || value.startsWith("keyboard:") else value.startsWith(prefix) }
            if (held.isEmpty()) transition(key, false)
        }
    }
    /** Always retries keys, including a previously rejected release. */
    fun releaseAll(includeLatching: Boolean = true) {
        available.filter { includeLatching || !isLatching(it) }.forEach { key ->
            sources.getValue(key).clear()
            transition(key, false, force = true)
        }
    }
    fun diagnostics(): Map<String, Any?> = mapOf("pending" to pending, "failureCount" to failureCount,
        "desired" to desired.toMap(), "observed" to observed.toMap(), "latchMode" to latchMode,
        "lockedKeys" to lockedKeys().toList())

    companion object {
        const val LOCK_SOURCE: String = "lock"

        /** Opposite physical D-pad axes that cannot be simultaneously pressed on GFX100S hardware. */
        val OPPOSITE_DIRECTION_KEYS: Map<String, String> = mapOf(
            "up" to "down",
            "down" to "up",
            "left" to "right",
            "right" to "left",
        )
    }
}
