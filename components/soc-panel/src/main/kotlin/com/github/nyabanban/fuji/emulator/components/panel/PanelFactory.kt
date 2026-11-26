package com.github.nyabanban.fuji.emulator.components.panel

import com.github.nyabanban.fuji.emulator.api.*

/**
 * Panel initialization/release state machine at [0xFFFAB000, 0xFFFAB018) implementing
 * the 11-step INV-PANEL-CONFIG-FFFAB000 sequence (once or twice). Physical owner, reset,
 * and completion latency remain UNKNOWN; readback/poll values are HYPOTHESIS.
 */
class PanelFactory : ComponentFactory {
    override val descriptor = DESCRIPTOR
    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent =
        Controller(context, config)

    companion object {
        const val PROFILE_ONCE = "panel-init-once-v1"
        const val PROFILE_TWICE = "panel-init-twice-v1"
        const val PROFILE_REPEATABLE = "panel-init-repeatable-v1"
        const val PROFILE_REPEATABLE_A8000 = "panel-init-repeatable-a8000-experimental-v2"
        const val MASK = 0x001f0fffL
        const val CYCLE_LENGTH = 11
        const val MAX_REPEATABLE_CYCLES = 1024
        const val WINDOW_SIZE = 24L

        private val DESCRIPTOR = ComponentDescriptor(
            id = "soc-panel",
            version = "0.0.1",
            settings = listOf(
                Setting(
                    key = "profile",
                    kind = ValueKind.TEXT,
                    default = "disabled",
                    choices = listOf("disabled", PROFILE_ONCE, PROFILE_TWICE, PROFILE_REPEATABLE, PROFILE_REPEATABLE_A8000),
                    live = false,
                ),
            ),
        )

        private val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalars", "enabled", "position", "control")
        private val A8000_SNAPSHOT_KEYS = SNAPSHOT_KEYS + "toneLutA8000"

        private fun controls(released: Long): Set<Long> = setOf(
            released,
            released and 0xfefffbe0L,
            released and 0xfefff41fL,
            released and 0xfefff000L,
            (released and 0xffe0ffffL) or 0x01000000L,
        )

        private val firstControls = controls(MASK)
        private val secondControls = firstControls.flatMap { controls(it or MASK) }.toSet()
        private val repeatableControls: Set<Long> = buildSet {
            addAll(firstControls)
            while (true) {
                val added = flatMap { controls(it or MASK) }.filter { it !in this }
                if (added.isEmpty()) break
                addAll(added)
            }
        }

        private fun maxCyclesFor(profile: String): Int = when (profile) {
            PROFILE_ONCE -> 1
            PROFILE_TWICE -> 2
            PROFILE_REPEATABLE, PROFILE_REPEATABLE_A8000 -> MAX_REPEATABLE_CYCLES
            else -> 0
        }

        fun stepAccess(
            position: Int,
            offset: Int,
            write: Boolean,
            value: Long,
            control: Long,
            twice: Boolean,
            maxCycles: Int = if (twice) 2 else 1,
        ): Long? {
            if (position !in 0 until CYCLE_LENGTH * maxCycles) return null
            val initial = if (position < CYCLE_LENGTH) 0L else control or MASK
            return when (val phase = position % CYCLE_LENGTH) {
                0 -> initial.takeIf { !write && offset == 4 }
                1 -> value.takeIf { write && offset == 4 && value in controls(initial or MASK) }
                2, 3 -> value.takeIf { write && offset == phase * 4 }
                4 -> value.takeIf { write && offset == 16 && value <= 0xffffffL }
                5 -> value.takeIf { write && offset == 20 && value <= 0xffffL }
                6 -> 0L.takeIf { write && offset == 0 && value == 0L }
                7, 8 -> (phase - 7).toLong().takeIf { !write && offset == 0 }
                9 -> control.takeIf { !write && offset == 4 }
                10 -> value.takeIf { write && offset == 4 && value == (control or MASK) }
                else -> null
            }
        }

        fun validState(
            position: Int,
            control: Long,
            twice: Boolean,
            maxCycles: Int = if (twice) 2 else 1,
        ): Boolean = when {
            position in 0..1 -> control == 0L
            position in 2..CYCLE_LENGTH -> control in firstControls
            position == CYCLE_LENGTH + 1 -> maxCycles >= 2 && control in firstControls
            position in (CYCLE_LENGTH + 2)..(maxCycles * CYCLE_LENGTH) ->
                maxCycles >= 2 && control in (if (maxCycles > 2) repeatableControls else secondControls)
            else -> false
        }

        private fun snapshotLong(value: Any?, field: String, min: Long, max: Long): Long {
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Snapshot field $field requires integer"
            }
            val converted = (value as Number).toLong()
            require(converted in min..max) { "Snapshot field $field out of range" }
            return converted
        }

        private fun snapshotMap(value: Any?, field: String): Map<*, *> {
            val map = value as? Map<*, *> ?: throw IllegalArgumentException("Snapshot field $field requires map")
            require(map.keys.all { it is String }) { "Snapshot field $field requires string keys" }
            return map
        }

        private fun snapshotBoolean(value: Any?, field: String): Boolean =
            value as? Boolean ?: throw IllegalArgumentException("Snapshot field $field requires boolean")
    }

    private class Controller(
        context: ComponentContext,
        config: Map<String, Any>,
    ) : ScalarComponent("soc-panel", context, config), StatefulComponent {
        private var closed = false
        private val profile = config.getValue("profile") as String
        private val maxCycles = maxCyclesFor(profile)
        private val active = maxCycles > 0
        private val twice = maxCycles >= 2
        private val toneLutEnabled = profile == PROFILE_REPEATABLE_A8000
        private val mainPortName = if (toneLutEnabled) ToneLutA8000Device.PORT_MAIN_V2 else id
        private val toneLut = if (toneLutEnabled) ToneLutA8000Device() else null
        private var position = 0
        private var control = 0L

        init {
            require(profile in listOf("disabled", PROFILE_ONCE, PROFILE_TWICE, PROFILE_REPEATABLE, PROFILE_REPEATABLE_A8000)) {
                "Unsupported soc-panel profile: $profile"
            }
            if (active) {
                context.ports.register(id, mainPortName, MmioDevice::class.java, object : MmioDevice {
                    override val clockOrder = 74
                    override fun read(offset: Long, size: Int): Long {
                        check(!closed) { "soc-panel controller closed" }
                        require(size == 4 && offset >= 0L && offset < WINDOW_SIZE && offset % 4L == 0L) {
                            "soc-panel requires aligned 32-bit word within [0, $WINDOW_SIZE)"
                        }
                        val result = stepAccess(position, offset.toInt(), write = false, value = 0L, control = control, twice = twice, maxCycles = maxCycles)
                            ?: error("soc-panel refused read at position=$position offset=0x${offset.toString(16)}")
                        position++
                        return result
                    }

                    override fun write(offset: Long, size: Int, value: Long) {
                        check(!closed) { "soc-panel controller closed" }
                        require(size == 4 && offset >= 0L && offset < WINDOW_SIZE && offset % 4L == 0L) {
                            "soc-panel requires aligned 32-bit word within [0, $WINDOW_SIZE)"
                        }
                        require(value in 0L..0xffffffffL) { "soc-panel write value outside u32" }
                        stepAccess(position, offset.toInt(), write = true, value = value, control = control, twice = twice, maxCycles = maxCycles)
                            ?: error("soc-panel refused write at position=$position offset=0x${offset.toString(16)} value=0x${value.toString(16)}")
                        if (position % CYCLE_LENGTH == 1) {
                            control = value
                        }
                        position++
                    }
                })
            }
            toneLut?.let { dev ->
                context.ports.register(id, ToneLutA8000Device.PORT_TONE_LUT, MmioDevice::class.java, dev)
            }
        }

        override fun interruptRoutes(): List<InterruptRoute> =
            if (toneLutEnabled) listOf(InterruptRoute("soc-gic.lines", ToneLutA8000Device.IRQ_LINE..ToneLutA8000Device.IRQ_LINE)) else emptyList()

        override fun start() {
            check(!closed) { "soc-panel controller closed" }
            toneLut?.attachInterruptSink(context.ports.find("soc-gic.lines", InterruptSink::class.java))
            toneLut?.attachMemoryProvider { context.ports.find("cpu.memory", DmaMemory::class.java) }
        }

        override fun close() {
            toneLut?.detachMemory()
            toneLut?.detachInterruptSink()
            closed = true
        }

        override fun additionalMmioPorts(): List<String> = when {
            !active -> emptyList()
            toneLutEnabled -> listOf(mainPortName, ToneLutA8000Device.PORT_TONE_LUT)
            else -> listOf(id)
        }

        override fun state(): Map<String, Any?> = super.state() + mapOf(
            "enabled" to active,
            "twice" to twice,
            "position" to position,
            "control" to control,
            "basis" to "INV-PANEL-CONFIG-FFFAB000 setup/release offsets and control bitmasks are SOURCE; readback, poll sequence, and cycle limits are HYPOTHESIS; physical identity/reset/timing UNKNOWN",
        ) + (toneLut?.let { mapOf("toneLutA8000" to it.state()) } ?: emptyMap())

        override fun saveSnapshot(): Map<String, Any?> {
            check(!closed) { "soc-panel controller closed" }
            val out = linkedMapOf<String, Any?>(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalars" to saveScalarValues(),
                "enabled" to active,
                "position" to position,
                "control" to control,
            )
            if (toneLut != null) {
                out["toneLutA8000"] = toneLut.saveSnapshot()
            }
            return out
        }

        override fun restoreSnapshot(data: Map<String, Any?>) {
            check(!closed) { "soc-panel controller closed" }
            val hasToneLut = data.keys == A8000_SNAPSHOT_KEYS && toneLutEnabled
            val legacyKeys = data.keys == SNAPSHOT_KEYS
            require(hasToneLut || legacyKeys) { "Unexpected snapshot keys for $id" }
            require(snapshotLong(data["schema"], "schema", 1L, Int.MAX_VALUE.toLong()).toInt() == snapshotSchemaVersion) {
                "Unsupported snapshot schema for $id"
            }
            require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
            val scalarsRaw = snapshotMap(data["scalars"], "scalars")
            require(scalarsRaw.keys == DESCRIPTOR.settings.map { it.key }.toSet()) {
                "Scalar setting keys differ in snapshot for $id"
            }
            val previousValues = values
            val nextPosition: Int
            val nextControl: Long
            val validatedToneLut: ToneLutA8000Device.ValidatedSnapshot?
            try {
                require(snapshotBoolean(data["enabled"], "enabled") == active) {
                    "Snapshot enabled flag differs for $id"
                }
                val savedPos = snapshotLong(data["position"], "position", 0L, (MAX_REPEATABLE_CYCLES * CYCLE_LENGTH).toLong()).toInt()
                val savedControl = snapshotLong(data["control"], "control", 0L, 0xffffffffL)
                if (!active) {
                    restoreScalarValues(scalarsRaw, DESCRIPTOR)
                    require(savedPos == 0 && savedControl == 0L) {
                        "Disabled soc-panel snapshot cannot carry active state"
                    }
                    validatedToneLut = null
                } else {
                    val savedProfile = scalarsRaw["profile"] as? String
                        ?: throw IllegalArgumentException("Snapshot scalars.profile requires string for $id")
                    val savedMaxCycles = maxCyclesFor(savedProfile)
                    require(savedMaxCycles in 1..maxCycles) {
                        "Incompatible soc-panel profile restore: saved=$savedProfile live=$profile"
                    }
                    require(!(savedProfile == PROFILE_REPEATABLE_A8000 && !toneLutEnabled)) {
                        "Cannot downgrade $PROFILE_REPEATABLE_A8000 snapshot into $profile"
                    }
                    val compatible = if (savedProfile != profile) setOf("profile") else emptySet()
                    restoreScalarValues(scalarsRaw, DESCRIPTOR, compatible)
                    require(validState(savedPos, savedControl, twice = savedMaxCycles >= 2, maxCycles = savedMaxCycles)) {
                        "Invalid soc-panel snapshot state: position=$savedPos control=0x${savedControl.toString(16)}"
                    }
                    validatedToneLut = if (hasToneLut) toneLut!!.validateSnapshot(data["toneLutA8000"]) else null
                }
                nextPosition = savedPos
                nextControl = savedControl
            } catch (failure: Throwable) {
                values = previousValues
                throw failure
            }
            position = nextPosition
            control = nextControl
            if (toneLut != null) {
                if (validatedToneLut != null) toneLut.applySnapshot(validatedToneLut) else toneLut.resetToDefault()
            }
        }
    }
}
