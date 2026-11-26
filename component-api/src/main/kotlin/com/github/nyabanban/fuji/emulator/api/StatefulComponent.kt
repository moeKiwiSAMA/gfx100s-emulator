package com.github.nyabanban.fuji.emulator.api

/**
 * Explicit versioned state snapshot contract for hardware and peripheral components.
 * Each component owns its schema version and validates invariants before mutating live state.
 */
interface StatefulComponent : HardwareComponent {
    val snapshotSchemaVersion: Int get() = 1
    fun saveSnapshot(): Map<String, Any?>
    fun restoreSnapshot(data: Map<String, Any?>)
}
