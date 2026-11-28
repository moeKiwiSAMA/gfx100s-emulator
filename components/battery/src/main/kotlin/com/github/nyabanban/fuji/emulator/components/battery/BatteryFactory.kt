package com.github.nyabanban.fuji.emulator.components.battery

import com.github.nyabanban.fuji.emulator.api.*
import java.util.concurrent.CopyOnWriteArrayList

class BatteryFactory : ComponentFactory {
    override val descriptor = ComponentDescriptor(
        "battery",
        "0.0.1",
        settings = listOf(
            Setting("chargePercent", ValueKind.DECIMAL, 100.0, 0.0, 100.0),
            Setting("voltageMillivolts", ValueKind.INTEGER, 7400L, 0.0, 12000.0),
            Setting("present", ValueKind.BOOLEAN, true),
            Setting("charging", ValueKind.BOOLEAN, false),
            Setting("externalPowerConnected", ValueKind.BOOLEAN, false),
            Setting("autoChargeDrift", ValueKind.BOOLEAN, false),
        ),
    )

    override fun create(context: ComponentContext, config: Map<String, Any>): HardwareComponent {
        val baseDefaults = descriptor.settings.associate { it.key to it.default }
        val initialNormalized = normalizePowerMutation(
            current = baseDefaults,
            patch = config,
            autoVoltageAllowed = "voltageMillivolts" !in config && ("charging" in config || "externalPowerConnected" in config),
        )
        return object : ScalarComponent("battery", context, initialNormalized), PowerControlPort, StatefulComponent {
            private val listeners = CopyOnWriteArrayList<() -> Unit>()
            private var driftAnchorTick: Long = context.clock.ticks()

            init {
                context.ports.register(id, "battery", BatteryPort::class.java, this)
            }

            fun autoChargeDriftEnabled(): Boolean = values.getValue("autoChargeDrift") as Boolean

            private fun rawChargePercent(): Double = (values.getValue("chargePercent") as Number).toDouble()

            override fun present(): Boolean = values.getValue("present") as Boolean

            override fun charging(): Boolean = present() && (values.getValue("charging") as Boolean)

            override fun externalPowerConnected(): Boolean =
                (values.getValue("externalPowerConnected") as Boolean) || (values.getValue("charging") as Boolean)

            override fun chargePercent(): Double {
                val base = rawChargePercent()
                if (!autoChargeDriftEnabled() || !charging() || base >= 100.0) {
                    return base
                }
                val elapsedTicks = (context.clock.ticks() - driftAnchorTick).coerceAtLeast(0L)
                val steps = elapsedTicks / DRIFT_INTERVAL_TICKS
                return if (steps <= 0L) base else (base + steps.toDouble() * DRIFT_STEP_PERCENT).coerceIn(0.0, 100.0)
            }

            override fun voltageMillivolts(): Int {
                val baseMv = (values.getValue("voltageMillivolts") as Number).toInt()
                if (!autoChargeDriftEnabled() || !charging()) {
                    return baseMv
                }
                val pct = chargePercent()
                return if (pct == rawChargePercent()) baseMv else NpW235BatteryCurve.deriveMillivolts(pct, charging = true)
            }

            override fun chargeRatePercentPerHour(): Double = when {
                !present() -> 0.0
                charging() -> if (chargePercent() < 99.5) 36.0 else 6.0
                externalPowerConnected() -> 0.0
                else -> -12.0
            }

            override fun addPowerStateListener(listener: () -> Unit): AutoCloseable {
                listeners.add(listener)
                return AutoCloseable { listeners.remove(listener) }
            }

            private fun notifyPowerListeners() {
                for (listener in listeners) {
                    listener()
                }
            }

            private fun commitCurrentDriftIntoScalars(): Map<String, Any> {
                if (!autoChargeDriftEnabled() || !charging()) return values
                val currentPct = chargePercent()
                if (currentPct == rawChargePercent()) return values
                val currentMv = NpW235BatteryCurve.deriveMillivolts(currentPct, charging = true).toLong()
                return values + mapOf("chargePercent" to currentPct, "voltageMillivolts" to currentMv)
            }

            override fun applySettings(settings: Map<String, Any>) {
                val base = commitCurrentDriftIntoScalars()
                val merged = normalizePowerMutation(
                    current = base,
                    patch = settings,
                    autoVoltageAllowed = "voltageMillivolts" !in settings &&
                        ("charging" in settings || ((base["charging"] as? Boolean) == true && "chargePercent" in settings)),
                )
                super.applySettings(merged)
                driftAnchorTick = context.clock.ticks()
                notifyPowerListeners()
            }

            override fun powerStatusSnapshot(): BatteryPowerStatus = BatteryPowerStatus(
                present = present(),
                chargePercent = chargePercent(),
                voltageMillivolts = voltageMillivolts(),
                charging = charging(),
                externalPowerConnected = externalPowerConnected(),
                chargeRatePercentPerHour = chargeRatePercentPerHour(),
                autoChargeDrift = autoChargeDriftEnabled(),
            )

            override fun updatePowerState(
                present: Boolean?,
                chargePercent: Double?,
                voltageMillivolts: Int?,
                charging: Boolean?,
                externalPowerConnected: Boolean?,
                autoChargeDrift: Boolean?,
            ): BatteryPowerStatus {
                val patch = LinkedHashMap<String, Any>()
                if (present != null) {
                    patch["present"] = present
                }
                if (chargePercent != null) {
                    require(chargePercent.isFinite() && chargePercent in 0.0..100.0) {
                        "chargePercent out of range 0..100: $chargePercent"
                    }
                    patch["chargePercent"] = chargePercent
                }
                if (voltageMillivolts != null) {
                    require(voltageMillivolts in 0..12000) {
                        "voltageMillivolts out of range 0..12000: $voltageMillivolts"
                    }
                    patch["voltageMillivolts"] = voltageMillivolts.toLong()
                }
                if (charging != null) {
                    patch["charging"] = charging
                }
                if (externalPowerConnected != null) {
                    patch["externalPowerConnected"] = externalPowerConnected
                }
                if (autoChargeDrift != null) {
                    patch["autoChargeDrift"] = autoChargeDrift
                }
                if (patch.isNotEmpty()) {
                    val base = commitCurrentDriftIntoScalars()
                    val merged = normalizePowerMutation(
                        current = base,
                        patch = patch,
                        autoVoltageAllowed = voltageMillivolts == null && (chargePercent != null || charging != null),
                    )
                    super.applySettings(merged)
                    driftAnchorTick = context.clock.ticks()
                    notifyPowerListeners()
                }
                return powerStatusSnapshot()
            }

            override fun applyPowerAction(changes: Map<String, Any?>): Map<String, Any?> {
                val allowed = setOf(
                    "present",
                    "chargePercent",
                    "batteryPercent",
                    "voltageMillivolts",
                    "charging",
                    "externalPowerConnected",
                    "usbVbusConnected",
                    "autoChargeDrift",
                )
                require(changes.keys.all { it in allowed }) {
                    "Unsupported battery power keys: ${changes.keys - allowed}"
                }
                val pctRaw = changes["chargePercent"] ?: changes["batteryPercent"]
                val pct = pctRaw?.let {
                    require(it is Number && it.toDouble().isFinite() && it.toDouble() in 0.0..100.0) {
                        "chargePercent requires finite number in 0..100"
                    }
                    it.toDouble()
                }
                val mv = changes["voltageMillivolts"]?.let { exactInt(it, "voltageMillivolts") }
                val presentVal = changes["present"]?.let {
                    require(it is Boolean) { "present requires boolean" }
                    it
                }
                val chargingVal = changes["charging"]?.let {
                    require(it is Boolean) { "charging requires boolean" }
                    it
                }
                val extRaw = changes["externalPowerConnected"] ?: changes["usbVbusConnected"]
                val extVal = extRaw?.let {
                    require(it is Boolean) { "externalPowerConnected requires boolean" }
                    it
                }
                val driftVal = changes["autoChargeDrift"]?.let {
                    require(it is Boolean) { "autoChargeDrift requires boolean" }
                    it
                }
                return updatePowerState(
                    present = presentVal,
                    chargePercent = pct,
                    voltageMillivolts = mv,
                    charging = chargingVal,
                    externalPowerConnected = extVal,
                    autoChargeDrift = driftVal,
                ).toMap()
            }

            override fun state(): Map<String, Any?> = super.state() + linkedMapOf(
                "chargePercent" to chargePercent(),
                "voltageMillivolts" to voltageMillivolts().toLong(),
                "charging" to charging(),
                "externalPowerConnected" to externalPowerConnected(),
                "usbVbusConnected" to externalPowerConnected(),
                "cratePercentPerHour" to chargeRatePercentPerHour(),
                "chargeRatePercentPerHour" to chargeRatePercentPerHour(),
            )

            override fun saveSnapshot(): Map<String, Any?> = linkedMapOf(
                "schema" to snapshotSchemaVersion,
                "componentId" to id,
                "scalarValues" to LinkedHashMap(commitCurrentDriftIntoScalars()),
            )

            override fun restoreSnapshot(data: Map<String, Any?>) {
                require(exactInt(data["schema"], "schema") == snapshotSchemaVersion) { "Unsupported snapshot schema for $id" }
                require(data["componentId"] == id) { "Snapshot componentId mismatch for $id" }
                require(data.keys.all { key -> key in SNAPSHOT_KEYS }) { "Unexpected key in snapshot for $id" }
                val rawScalars = data["scalarValues"] as? Map<*, *>
                    ?: throw IllegalArgumentException("Missing scalar settings map for $id")
                require(REQUIRED_LEGACY_SCALAR_KEYS.all { it in rawScalars }) {
                    "Missing required battery scalar setting in snapshot for $id"
                }
                val normalized = LinkedHashMap<Any?, Any?>(rawScalars)
                if ("charging" !in normalized) normalized["charging"] = false
                if ("externalPowerConnected" !in normalized) {
                    normalized["externalPowerConnected"] = (normalized["charging"] == true)
                }
                if ("autoChargeDrift" !in normalized) normalized["autoChargeDrift"] = false
                restoreScalarValues(normalized, descriptor)
                driftAnchorTick = context.clock.ticks()
                notifyPowerListeners()
            }

            override fun close() {
                listeners.clear()
            }
        }
    }

    private companion object {
        val SNAPSHOT_KEYS = setOf("schema", "componentId", "scalarValues")
        val REQUIRED_LEGACY_SCALAR_KEYS = setOf("chargePercent", "voltageMillivolts", "present")
        const val DRIFT_INTERVAL_TICKS: Long = 540_000_000L // 10 s at 54 MHz timer rate
        const val DRIFT_STEP_PERCENT: Double = 0.1 // +0.1% per 10 s = +36.0 %/hour

        fun normalizePowerMutation(
            current: Map<String, Any>,
            patch: Map<String, Any>,
            autoVoltageAllowed: Boolean,
        ): Map<String, Any> {
            val merged = LinkedHashMap<String, Any>(current)
            merged.putAll(patch)

            val explicitCharging = patch["charging"] as? Boolean
            val explicitExt = patch["externalPowerConnected"] as? Boolean
            when {
                explicitCharging == true -> {
                    merged["charging"] = true
                    if (explicitExt == null) {
                        merged["externalPowerConnected"] = true
                    }
                }
                explicitCharging == false -> {
                    merged["charging"] = false
                    if (explicitExt == null) {
                        merged["externalPowerConnected"] = false
                    }
                }
                explicitExt == false -> {
                    merged["externalPowerConnected"] = false
                    merged["charging"] = false
                }
            }

            if (autoVoltageAllowed && "voltageMillivolts" !in patch) {
                val pct = (merged.getValue("chargePercent") as Number).toDouble()
                val effCharging = (merged.getValue("present") as Boolean) && (merged.getValue("charging") as Boolean)
                merged["voltageMillivolts"] = NpW235BatteryCurve.deriveMillivolts(pct, effCharging).toLong()
            }
            return merged
        }

        fun exactInt(value: Any?, field: String): Int {
            val number: Long = when (value) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                is java.math.BigInteger -> {
                    require(value.bitLength() <= 63) { "$field out of Long range" }
                    value.toLong()
                }
                is java.math.BigDecimal -> try {
                    value.longValueExact()
                } catch (exception: ArithmeticException) {
                    throw IllegalArgumentException("$field requires exact integer", exception)
                }
                is Number -> {
                    val decimal = value.toDouble()
                    require(
                        decimal.isFinite() &&
                            decimal % 1.0 == 0.0 &&
                            decimal >= Long.MIN_VALUE.toDouble() &&
                            decimal < 9223372036854775808.0,
                    ) { "$field requires exact integer" }
                    decimal.toLong()
                }
                else -> throw IllegalArgumentException("$field requires integer")
            }
            require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$field out of Int range" }
            return number.toInt()
        }
    }
}
