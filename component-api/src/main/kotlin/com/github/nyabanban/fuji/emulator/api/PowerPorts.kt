package com.github.nyabanban.fuji.emulator.api

import kotlin.math.roundToInt

/**
 * Fujifilm NP-W235 (2S Li-Ion, 7.2 V nominal, 2200 mAh, 6400..8400 mV operating window)
 * open-circuit + CC/CV charging terminal voltage curve and fuel-gauge scaling constants.
 */
object NpW235BatteryCurve {
    const val MIN_MILLIVOLTS: Int = 6400
    const val MAX_MILLIVOLTS: Int = 8400
    const val NOMINAL_MILLIVOLTS: Int = 7400
    const val FULL_CHARGE_CAPACITY_MAH: Int = 2200
    const val MAX17049_CRATE_LSB_PCT_PER_HOUR: Double = 0.208

    fun deriveMillivolts(chargePercent: Double, charging: Boolean): Int {
        val clamped = chargePercent.coerceIn(0.0, 100.0)
        val openCircuitMv = 6400.0 + (clamped * 19.5) // 6400 mV @ 0% .. 8350 mV @ 100%
        val chargeRiseMv = if (charging) 140.0 else 0.0
        return (openCircuitMv + chargeRiseMv).roundToInt().coerceIn(MIN_MILLIVOLTS, MAX_MILLIVOLTS)
    }
}

/**
 * Immutable OS-agnostic snapshot of the NP-W235 battery pack and external USB-C power path.
 */
data class BatteryPowerStatus(
    val present: Boolean,
    val chargePercent: Double,
    val voltageMillivolts: Int,
    val charging: Boolean,
    val externalPowerConnected: Boolean,
    val chargeRatePercentPerHour: Double,
    val autoChargeDrift: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "present" to present,
        "chargePercent" to chargePercent,
        "voltageMillivolts" to voltageMillivolts,
        "charging" to charging,
        "externalPowerConnected" to externalPowerConnected,
        "usbVbusConnected" to externalPowerConnected,
        "cratePercentPerHour" to chargeRatePercentPerHour,
        "chargeRatePercentPerHour" to chargeRatePercentPerHour,
        "autoChargeDrift" to autoChargeDrift,
    )
}

/**
 * Live owner-thread battery & external USB-C power control contract implemented by the `battery`
 * component ([PORT_NAME]) on top of [BatteryPort].
 */
interface PowerControlPort : BatteryPort {
    fun powerStatusSnapshot(): BatteryPowerStatus

    fun powerStatus(): Map<String, Any?> = powerStatusSnapshot().toMap()

    fun updatePowerState(
        present: Boolean? = null,
        chargePercent: Double? = null,
        voltageMillivolts: Int? = null,
        charging: Boolean? = null,
        externalPowerConnected: Boolean? = null,
        autoChargeDrift: Boolean? = null,
    ): BatteryPowerStatus

    fun applyPowerAction(changes: Map<String, Any?>): Map<String, Any?>

    companion object {
        const val PORT_NAME: String = "battery"
    }
}
