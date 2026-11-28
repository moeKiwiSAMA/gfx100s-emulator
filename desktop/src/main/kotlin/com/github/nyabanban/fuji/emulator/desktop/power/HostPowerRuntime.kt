package com.github.nyabanban.fuji.emulator.desktop.power

import com.github.nyabanban.fuji.emulator.api.BatteryPort
import com.github.nyabanban.fuji.emulator.api.NpW235BatteryCurve
import com.github.nyabanban.fuji.emulator.api.PowerControlPort
import com.github.nyabanban.fuji.emulator.runtime.ComponentHost
import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.OwnerLoop
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * Parsed command-line overrides for the NP-W235 battery & USB-C charging hardware state.
 *
 * Supported flags:
 * - `--charging` / `--no-charging`
 * - `--usb-power` / `--no-usb-power`
 * - `--battery-percent=<0..100>` (or `--battery-percent <0..100>`)
 */
data class PowerLaunchCliOverrides(
    val charging: Boolean? = null,
    val externalPowerConnected: Boolean? = null,
    val chargePercent: Double? = null,
) {
    fun hasOverrides(): Boolean =
        charging != null || externalPowerConnected != null || chargePercent != null

    fun applyTo(base: BatteryPowerConfig): BatteryPowerConfig {
        val nextCharging = charging ?: base.charging
        val nextExternalPower = when {
            nextCharging -> true
            externalPowerConnected != null -> externalPowerConnected
            charging == false -> false
            else -> base.externalPowerConnected
        }
        val nextPct = chargePercent ?: base.chargePercent
        val autoMv = if (chargePercent != null || charging != null) {
            BatteryPowerValidation.deriveNpW235Millivolts(nextPct, nextCharging)
        } else {
            base.voltageMillivolts
        }
        return base.copy(
            chargePercent = nextPct,
            charging = nextCharging,
            externalPowerConnected = nextExternalPower,
            voltageMillivolts = autoMv,
        )
    }
}

data class BatteryPowerConfig(
    val present: Boolean = true,
    val chargePercent: Double = 100.0,
    val voltageMillivolts: Int = 7400,
    val charging: Boolean = false,
    val externalPowerConnected: Boolean = false,
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "present" to present,
        "chargePercent" to chargePercent,
        "voltageMillivolts" to voltageMillivolts,
        "charging" to charging,
        "externalPowerConnected" to externalPowerConnected,
    )

    companion object {
        val DEFAULT = BatteryPowerConfig()
    }
}

object BatteryPowerValidation {
    const val CONFIG_KEY = "power"

    private val VALID_CONFIG_KEYS = setOf(
        "present",
        "chargePercent",
        "batteryPercent",
        "voltageMillivolts",
        "charging",
        "externalPowerConnected",
        "usbVbusConnected",
    )

    /**
     * Realistic Fujifilm NP-W235 (2S Li-Ion, 7.2V nominal, 6.4V..8.4V) open-circuit + CC/CV
     * charging terminal voltage curve in millivolts.
     */
    fun deriveNpW235Millivolts(chargePercent: Double, charging: Boolean): Int =
        NpW235BatteryCurve.deriveMillivolts(chargePercent, charging)

    fun validatePercent(raw: Any?, fieldName: String = "chargePercent"): Double {
        val v = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().removeSuffix("%").trim().toDoubleOrNull()
            else -> null
        }
        require(v != null && v.isFinite() && v in 0.0..100.0) {
            "$fieldName must be within 0..100, got '$raw'"
        }
        return v
    }

    fun validateMillivolts(raw: Any?, fieldName: String = "voltageMillivolts"): Int {
        val v = when (raw) {
            is Byte, is Short, is Int, is Long -> (raw as Number).toLong()
            is String -> raw.trim().toLongOrNull()
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite() && d % 1.0 == 0.0) d.toLong() else null
            }
            else -> null
        }
        require(v != null && v in 0L..12_000L) {
            "$fieldName must be an integer within 0..12000 mV, got '$raw'"
        }
        return v.toInt()
    }

    fun validateConfigValue(raw: Any?) {
        if (raw == null) return
        require(raw is Map<*, *>) { "power config requires map" }
        require(raw.keys.all { it is String && it in VALID_CONFIG_KEYS }) {
            "invalid power config keys: ${raw.keys - VALID_CONFIG_KEYS}"
        }
        raw["present"]?.let { require(it is Boolean) { "power.present requires boolean" } }
        (raw["chargePercent"] ?: raw["batteryPercent"])?.let { validatePercent(it, "power.chargePercent") }
        raw["voltageMillivolts"]?.let { validateMillivolts(it, "power.voltageMillivolts") }
        raw["charging"]?.let { require(it is Boolean) { "power.charging requires boolean" } }
        raw["externalPowerConnected"]?.let { require(it is Boolean) { "power.externalPowerConnected requires boolean" } }
        raw["usbVbusConnected"]?.let { require(it is Boolean) { "power.usbVbusConnected requires boolean" } }
    }

    fun resolveFromEmulatorYaml(config: Map<String, Any?>, base: BatteryPowerConfig): BatteryPowerConfig {
        val raw = config[CONFIG_KEY] ?: return base
        validateConfigValue(raw)
        return applyPatch(base, raw as Map<*, *>)
    }

    fun applyPatch(base: BatteryPowerConfig, patch: Map<*, *>): BatteryPowerConfig {
        val nextPresent = (patch["present"] as? Boolean) ?: base.present
        val hasPct = patch.containsKey("chargePercent") || patch.containsKey("batteryPercent")
        val nextPct = if (hasPct) {
            validatePercent(patch["chargePercent"] ?: patch["batteryPercent"], "chargePercent")
        } else {
            base.chargePercent
        }

        val explicitCharging = patch["charging"] as? Boolean
        val explicitExt = (patch["externalPowerConnected"] as? Boolean) ?: (patch["usbVbusConnected"] as? Boolean)

        val nextCharging: Boolean
        val nextExtPower: Boolean
        when {
            explicitCharging == true -> {
                nextCharging = nextPresent
                nextExtPower = explicitExt ?: true
            }
            explicitCharging == false -> {
                nextCharging = false
                nextExtPower = explicitExt ?: false
            }
            explicitExt == false -> {
                nextCharging = false
                nextExtPower = false
            }
            explicitExt == true -> {
                nextCharging = base.charging && nextPresent
                nextExtPower = true
            }
            else -> {
                nextCharging = base.charging && nextPresent
                nextExtPower = base.externalPowerConnected
            }
        }

        val explicitMv = if (patch.containsKey("voltageMillivolts") && patch["voltageMillivolts"] != null) {
            validateMillivolts(patch["voltageMillivolts"], "voltageMillivolts")
        } else {
            null
        }
        val stateChangedForAutoMv = hasPct || explicitCharging != null
        val nextMv = explicitMv ?: if (stateChangedForAutoMv) {
            deriveNpW235Millivolts(nextPct, nextCharging)
        } else {
            base.voltageMillivolts
        }

        return BatteryPowerConfig(
            present = nextPresent,
            chargePercent = nextPct,
            voltageMillivolts = nextMv,
            charging = nextCharging,
            externalPowerConnected = nextExtPower,
        )
    }
}

fun parsePowerLaunchArgs(args: Array<String>): PowerLaunchCliOverrides {
    var charging: Boolean? = null
    var externalPower: Boolean? = null
    var chargePercent: Double? = null

    fun requireNextValue(index: Int, flag: String): String {
        require(index + 1 < args.size) { "$flag requires a value" }
        return args[index + 1]
    }

    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--charging" -> {
                charging = true
                externalPower = true
                i++
            }
            arg == "--no-charging" -> {
                charging = false
                externalPower = false
                i++
            }
            arg == "--usb-power" -> {
                externalPower = true
                i++
            }
            arg == "--no-usb-power" -> {
                externalPower = false
                if (charging == true) charging = false
                i++
            }
            arg.startsWith("--battery-percent=") -> {
                chargePercent = BatteryPowerValidation.validatePercent(arg.substringAfter('='), "--battery-percent")
                i++
            }
            arg == "--battery-percent" -> {
                chargePercent = BatteryPowerValidation.validatePercent(
                    requireNextValue(i, "--battery-percent"),
                    "--battery-percent",
                )
                i += 2
            }
            else -> i++
        }
    }

    return PowerLaunchCliOverrides(
        charging = charging,
        externalPowerConnected = externalPower,
        chargePercent = chargePercent,
    )
}

object BatteryPowerStore {
    private val log = LoggerFactory.getLogger(BatteryPowerStore::class.java)
    const val SCHEMA_VERSION = 1
    const val FILE_NAME = "battery-power.yaml"

    fun defaultPath(home: Path, runtimeConfig: Map<String, Any?> = emptyMap()): Path {
        val configsDir = home.resolve("configs")
        if (Files.isDirectory(configsDir)) {
            return configsDir.resolve(FILE_NAME).toAbsolutePath().normalize()
        }
        val configDir = home.resolve("config")
        if (Files.isDirectory(configDir)) {
            return configDir.resolve(FILE_NAME).toAbsolutePath().normalize()
        }
        val compCfgRel = (runtimeConfig["componentConfigs"] as? String)?.takeIf { it.isNotBlank() }
        if (compCfgRel != null) {
            val compCfgParent = home.resolve(compCfgRel).parent
            if (compCfgParent != null) {
                return compCfgParent.resolve(FILE_NAME).toAbsolutePath().normalize()
            }
        }
        return configsDir.resolve(FILE_NAME).toAbsolutePath().normalize()
    }

    fun loadOrNull(path: Path, base: BatteryPowerConfig): BatteryPowerConfig? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            val raw = Formats.readData(path)
            BatteryPowerValidation.applyPatch(base, raw)
        }.onFailure { err ->
            log.warn("[FALLBACK][power] Failed to parse persisted battery/charging settings at {}: {}", path, err.message)
        }.getOrNull()
    }

    fun save(path: Path, config: BatteryPowerConfig) {
        val payload = linkedMapOf<String, Any>(
            "schema" to SCHEMA_VERSION,
            "present" to config.present,
            "chargePercent" to config.chargePercent,
            "voltageMillivolts" to config.voltageMillivolts,
            "charging" to config.charging,
            "externalPowerConnected" to config.externalPowerConnected,
        )
        Formats.atomicYaml(path, payload)
    }
}

/**
 * OS-agnostic host coordinator for Fujifilm GFX100S NP-W235 battery, USB-C VBUS, and charging state.
 *
 * Synchronizes CLI flags (`--charging`, `--no-charging`, `--battery-percent`), persisted
 * `configs/battery-power.yaml`, Console `/battery` commands, and Desktop Studio UI controls with
 * the `battery`, `max17048`, `power-peer`, and `soc-system-control` hardware components.
 */
class HostPowerRuntime(
    val home: Path,
    private val runtimeConfig: Map<String, Any?>,
    private val owner: OwnerLoop,
    private val hostProvider: () -> ComponentHost,
    cliOverrides: PowerLaunchCliOverrides = PowerLaunchCliOverrides(),
    val storePath: Path = BatteryPowerStore.defaultPath(home, runtimeConfig),
    private val persistChanges: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(HostPowerRuntime::class.java)
    private val lock = Any()

    @Volatile
    private var hasExplicitConfiguration: Boolean =
        runtimeConfig.containsKey(BatteryPowerValidation.CONFIG_KEY) ||
            Files.isRegularFile(storePath) ||
            cliOverrides.hasOverrides()

    @Volatile
    private var currentConfig: BatteryPowerConfig = run {
        val fromYaml = BatteryPowerValidation.resolveFromEmulatorYaml(runtimeConfig, BatteryPowerConfig.DEFAULT)
        val fromStore = BatteryPowerStore.loadOrNull(storePath, fromYaml) ?: fromYaml
        val resolved = cliOverrides.applyTo(fromStore)
        if (cliOverrides.hasOverrides() && persistChanges) {
            runCatching { BatteryPowerStore.save(storePath, resolved) }
                .onFailure { err -> log.warn("Unable to persist CLI battery/charging overrides to {}: {}", storePath, err.message) }
        }
        resolved
    }

    fun configSnapshot(): BatteryPowerConfig = currentConfig

    /**
     * Synchronizes [currentConfig] into hardware components on [owner] after `host.load()`,
     * Sub-MCU cold reset, or live Console/GUI updates.
     */
    fun syncToHardwareOnOwner() {
        owner.requireOwner()
        val host = hostProvider()
        val batteryLoaded = host.components().singleOrNull { it.descriptor.id == "battery" }
        if (!hasExplicitConfiguration) {
            if (batteryLoaded != null) {
                val cfg = batteryLoaded.config
                val st = batteryLoaded.component.state()
                currentConfig = BatteryPowerConfig(
                    present = (st["present"] as? Boolean) ?: (cfg["present"] as? Boolean) ?: true,
                    chargePercent = ((st["chargePercent"] as? Number) ?: (cfg["chargePercent"] as? Number))?.toDouble() ?: 100.0,
                    voltageMillivolts = ((st["voltageMillivolts"] as? Number) ?: (cfg["voltageMillivolts"] as? Number))?.toInt() ?: 7400,
                    charging = (st["charging"] as? Boolean) ?: (cfg["charging"] as? Boolean) ?: false,
                    externalPowerConnected = (st["externalPowerConnected"] as? Boolean)
                        ?: (cfg["externalPowerConnected"] as? Boolean)
                        ?: (st["usbVbusConnected"] as? Boolean)
                        ?: (cfg["usbVbusConnected"] as? Boolean)
                        ?: false,
                )
            }
            return
        }

        if (batteryLoaded != null) {
            val liveKeys = batteryLoaded.descriptor.settings.asSequence().filter { it.live }.map { it.key }.toSet()
            val candidate = linkedMapOf<String, Any>(
                "present" to currentConfig.present,
                "chargePercent" to currentConfig.chargePercent,
                "voltageMillivolts" to currentConfig.voltageMillivolts.toLong(),
                "charging" to currentConfig.charging,
                "externalPowerConnected" to currentConfig.externalPowerConnected,
                "usbVbusConnected" to currentConfig.externalPowerConnected,
            ).filterKeys { it in liveKeys }
            if (candidate.isNotEmpty()) {
                runCatching {
                    host.patchLatestDirect("battery", candidate, persistToDisk = false)
                }.onFailure { err ->
                    log.debug("Battery live patch skipped: {}", err.message)
                }
            }
        }

        // Also sync optional live USB VBUS / AC flags on soc-system-control or power-peer if declared
        for (compId in listOf("soc-system-control", "power-peer")) {
            val comp = host.components().singleOrNull { it.descriptor.id == compId } ?: continue
            val liveKeys = comp.descriptor.settings.asSequence().filter { it.live }.map { it.key }.toSet()
            val patch = linkedMapOf<String, Any>()
            if ("usbVbusConnected" in liveKeys) patch["usbVbusConnected"] = currentConfig.externalPowerConnected
            if ("externalPowerConnected" in liveKeys) patch["externalPowerConnected"] = currentConfig.externalPowerConnected
            if ("charging" in liveKeys) patch["charging"] = currentConfig.charging
            if (patch.isNotEmpty()) {
                runCatching { host.patchLatestDirect(compId, patch, persistToDisk = false) }
            }
        }

        // Also invoke optional PowerControlPort / BatteryPort charging methods if exposed on host.ports
        invokeControlPortIfPresent(host, currentConfig)
    }

    fun statusMap(): Map<String, Any?> {
        val cfg = currentConfig
        val hw = runCatching {
            owner.submit {
                val host = hostProvider()
                val batComp = host.components().singleOrNull { it.descriptor.id == "battery" }
                val batPort = host.ports.find("battery", BatteryPort::class.java)
                val batState = batComp?.component?.state().orEmpty()
                val batCfg = batComp?.config.orEmpty()
                val maxState = (host.components().singleOrNull { it.descriptor.id == "max17048" }
                    ?.component?.state()?.get("device") as? Map<*, *>).orEmpty()
                val sysState = host.components().singleOrNull { it.descriptor.id == "soc-system-control" }
                    ?.component?.state().orEmpty()
                val portStatus = readControlPortStatusIfPresent(host)

                val present = (portStatus?.get("present") as? Boolean)
                    ?: batPort?.present()
                    ?: (batState["present"] as? Boolean)
                    ?: (batCfg["present"] as? Boolean)
                    ?: cfg.present
                val pct = ((portStatus?.get("chargePercent") as? Number)
                    ?: (portStatus?.get("batteryPercent") as? Number))?.toDouble()
                    ?: batPort?.chargePercent()
                    ?: ((batState["chargePercent"] as? Number) ?: (batCfg["chargePercent"] as? Number))?.toDouble()
                    ?: cfg.chargePercent
                val mv = (portStatus?.get("voltageMillivolts") as? Number)?.toInt()
                    ?: batPort?.voltageMillivolts()
                    ?: ((batState["voltageMillivolts"] as? Number) ?: (batCfg["voltageMillivolts"] as? Number))?.toInt()
                    ?: cfg.voltageMillivolts
                val portCharging = batPort?.let { invokeBooleanNoArg(it, "charging") ?: invokeBooleanNoArg(it, "isCharging") }
                val charging = (portStatus?.get("charging") as? Boolean)
                    ?: portCharging
                    ?: (batState["charging"] as? Boolean)
                    ?: (batCfg["charging"] as? Boolean)
                    ?: cfg.charging
                val portExt = batPort?.let { invokeBooleanNoArg(it, "externalPowerConnected") ?: invokeBooleanNoArg(it, "usbVbusConnected") }
                val extPower = (portStatus?.get("externalPowerConnected") as? Boolean)
                    ?: (portStatus?.get("usbVbusConnected") as? Boolean)
                    ?: portExt
                    ?: (batState["externalPowerConnected"] as? Boolean)
                    ?: (batCfg["externalPowerConnected"] as? Boolean)
                    ?: (sysState["usbVbusConnected"] as? Boolean)
                    ?: if (charging) true else cfg.externalPowerConnected
                val crate = ((portStatus?.get("cratePercentPerHour") as? Number)
                    ?: (portStatus?.get("chargeRatePercentPerHour") as? Number)
                    ?: (batState["cratePercentPerHour"] as? Number)
                    ?: (batState["chargeRatePercentPerHour"] as? Number)
                    ?: (maxState["cratePercentPerHour"] as? Number))?.toDouble()

                linkedMapOf<String, Any?>(
                    "present" to present,
                    "chargePercent" to pct,
                    "voltageMillivolts" to mv,
                    "charging" to charging,
                    "externalPowerConnected" to extPower,
                    "usbVbusConnected" to extPower,
                    "cratePercentPerHour" to crate,
                    "persistedPath" to storePath.toString(),
                )
            }.join()
        }.getOrNull()

        return hw ?: linkedMapOf(
            "present" to cfg.present,
            "chargePercent" to cfg.chargePercent,
            "voltageMillivolts" to cfg.voltageMillivolts,
            "charging" to cfg.charging,
            "externalPowerConnected" to cfg.externalPowerConnected,
            "usbVbusConnected" to cfg.externalPowerConnected,
            "cratePercentPerHour" to null,
            "persistedPath" to storePath.toString(),
        )
    }

    fun reconfigure(patch: Map<String, Any?>, persist: Boolean = persistChanges): Map<String, Any?> {
        synchronized(lock) {
            if (!hasExplicitConfiguration) {
                owner.submit { syncToHardwareOnOwner() }.join()
            }
            hasExplicitConfiguration = true
            val next = BatteryPowerValidation.applyPatch(currentConfig, patch)
            currentConfig = next
            if (persist) {
                BatteryPowerStore.save(storePath, next)
            }
            owner.submit {
                syncToHardwareOnOwner()
            }.join()
        }
        return statusMap()
    }

    fun reconfigureAsync(patch: Map<String, Any?>, persist: Boolean = persistChanges): CompletableFuture<Map<String, Any?>> =
        CompletableFuture.supplyAsync { reconfigure(patch, persist = persist) }

    private fun invokeBooleanNoArg(target: Any, methodName: String): Boolean? = runCatching {
        val m = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: return null
        m.invoke(target) as? Boolean
    }.getOrNull()

    private fun resolvePowerControlPort(host: ComponentHost): PowerControlPort? =
        (host.ports.find("battery", BatteryPort::class.java) as? PowerControlPort)
            ?: runCatching { host.ports.find("battery.control", PowerControlPort::class.java) }.getOrNull()
            ?: runCatching { host.ports.find("power.control", PowerControlPort::class.java) }.getOrNull()

    private fun invokeControlPortIfPresent(host: ComponentHost, cfg: BatteryPowerConfig) {
        val ctrl = resolvePowerControlPort(host) ?: return
        runCatching {
            ctrl.updatePowerState(
                present = cfg.present,
                chargePercent = cfg.chargePercent,
                voltageMillivolts = cfg.voltageMillivolts,
                charging = cfg.charging,
                externalPowerConnected = cfg.externalPowerConnected,
            )
        }
    }

    private fun readControlPortStatusIfPresent(host: ComponentHost): Map<String, Any?>? {
        val ctrl = resolvePowerControlPort(host) ?: return null
        return runCatching { ctrl.powerStatus() }.getOrNull()
    }
}
