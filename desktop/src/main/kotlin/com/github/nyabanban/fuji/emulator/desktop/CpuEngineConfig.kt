package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.cpu.CpuEngineKind
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Resolution & validation for `cpuEngine` (`dynarmic` | `auto`) and optional `dynarmicLibrary`.
 *
 * **Dynarmic JIT (`dynarmic`)** is the sole native ARMv7-A CPU engine across all execution modes
 * (`relaxed`, `discovery`, `strict`). `smpHostThreads` defaults to `true` in `relaxed` mode and is
 * forced `false` in `strict`/`discovery` modes for deterministic serialized qualification.
 */
object CpuEngineConfig {
    const val CPU_ENGINE_KEY = "cpuEngine"
    const val DYNARMIC_LIBRARY_KEY = "dynarmicLibrary"
    const val SMP_HOST_THREADS_KEY = "smpHostThreads"
    const val CPU_ENGINE_PROPERTY = "fuji.cpu.engine"
    const val SMP_HOST_THREADS_PROPERTY = "fuji.smp.host.threads"
    const val DYNARMIC_LIBRARY_ENV = "FUJI_DYNARMIC_LIBRARY"

    data class Selection(
        val requested: CpuEngineKind,
        val effective: CpuEngineKind,
        val dynarmicLibrary: Path?,
        val smpHostThreads: Boolean = false,
    )

    private fun parseSmpHostThreadsProperty(): Boolean? {
        val raw = System.getProperty(SMP_HOST_THREADS_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (raw.lowercase(Locale.ROOT)) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> throw IllegalArgumentException(
                "Invalid -D$SMP_HOST_THREADS_PROPERTY='$raw' (expected true | false)",
            )
        }
    }

    fun resolveSmpHostThreads(config: Map<String, Any?>, mode: String): Boolean {
        val hasKey = config.containsKey(SMP_HOST_THREADS_KEY) && config[SMP_HOST_THREADS_KEY] != null
        val configVal: Boolean? = if (hasKey) {
            val raw = config[SMP_HOST_THREADS_KEY]
            require(raw is Boolean) { "$SMP_HOST_THREADS_KEY requires boolean" }
            raw
        } else {
            null
        }
        val propVal = parseSmpHostThreadsProperty()
        val effectiveRequested = propVal ?: configVal
        if (mode != RelaxedPeripheralFallback.MODE) {
            require(effectiveRequested != true) {
                "$SMP_HOST_THREADS_KEY=true is only permitted in relaxed mode (current mode: $mode)"
            }
            return false
        }
        return effectiveRequested ?: true
    }

    fun validate(config: Map<String, Any?>, mode: String): CpuEngineKind {
        val rawEngine = config[CPU_ENGINE_KEY] ?: System.getProperty(CPU_ENGINE_PROPERTY)
        val kind = if (rawEngine == null) {
            CpuEngineKind.DYNARMIC
        } else {
            CpuEngineKind.parse(rawEngine)
        }
        if (config.containsKey(DYNARMIC_LIBRARY_KEY) && config[DYNARMIC_LIBRARY_KEY] != null) {
            val lib = config[DYNARMIC_LIBRARY_KEY]
            require(lib is String && lib.isNotBlank()) {
                "$DYNARMIC_LIBRARY_KEY requires a nonblank path"
            }
        }
        resolveSmpHostThreads(config, mode)
        return kind
    }

    fun resolve(home: Path, config: Map<String, Any?>, mode: String): Selection {
        val requested = validate(config, mode)
        val smpRequested = resolveSmpHostThreads(config, mode)
        val lib = findDynarmicLibrary(home, config)
        require(lib != null) {
            "Native Dynarmic library not found " +
                "(set dynarmicLibrary, $DYNARMIC_LIBRARY_ENV, or run :cpu-dynarmic:buildDynarmicNative)"
        }
        return Selection(requested, CpuEngineKind.DYNARMIC, lib, smpHostThreads = smpRequested)
    }

    fun findDynarmicLibrary(home: Path, config: Map<String, Any?>): Path? {
        val isWin = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
        val libName = if (isWin) "fuji_dynarmic.dll" else "libfuji_dynarmic.so"
        val candidates = ArrayList<Path>()

        (config[DYNARMIC_LIBRARY_KEY] as? String)?.takeIf { it.isNotBlank() }?.let {
            candidates.add(home.resolve(it).toAbsolutePath().normalize())
        }
        System.getenv(DYNARMIC_LIBRARY_ENV)?.takeIf { it.isNotBlank() }?.let {
            candidates.add(Path.of(it).toAbsolutePath().normalize())
        }
        candidates.add(home.resolve("runtime/native/$libName").toAbsolutePath().normalize())
        val platformBuildDir = if (isWin) "build-win64" else "build-linux"
        candidates.add(home.resolve(".tools/dynarmic-v1/$platformBuildDir/$libName").toAbsolutePath().normalize())
        candidates.add(home.resolve(".tools/dynarmic-v1/build/$libName").toAbsolutePath().normalize())
        if (isWin) {
            candidates.add(home.resolve(".tools/dynarmic-v1-win64/build/$libName").toAbsolutePath().normalize())
        }
        System.getProperty("componentProjectRoot")?.takeIf { it.isNotBlank() }?.let { root ->
            val rootPath = Path.of(root)
            candidates.add(rootPath.resolve(".tools/dynarmic-v1/$platformBuildDir/$libName").toAbsolutePath().normalize())
            candidates.add(rootPath.resolve(".tools/dynarmic-v1/build/$libName").toAbsolutePath().normalize())
            if (isWin) {
                candidates.add(rootPath.resolve(".tools/dynarmic-v1-win64/build/$libName").toAbsolutePath().normalize())
            }
        }
        val cacheSubdir = if (isWin) "dynarmic-v1-win64" else "dynarmic-v1-linux"
        candidates.add(Path.of("/opt/fuji-cache/$cacheSubdir/$libName"))
        candidates.add(Path.of("/opt/fuji-cache/dynarmic-v1/$libName"))
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }
}
