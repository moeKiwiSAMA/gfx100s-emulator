package com.github.nyabanban.fuji.emulator.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Opt-in configuration & validation for custom/patched `.DAT` firmware loading
 * (`allowCustomFirmware: true`) and direct raw ARM32 / Linux `zImage` loading
 * (`customRawImage` / `customKernel`).
 *
 * Strictly refused in `strict` mode; default OFF with zero effect on stock runs.
 */
object CustomFirmwareConfig {
    const val ALLOW_CUSTOM_KEY = "allowCustomFirmware"
    const val ALLOW_CUSTOM_PROPERTY = "fuji.firmware.allowCustom"
    const val CUSTOM_RAW_KEY = "customRawImage"
    const val CUSTOM_KERNEL_KEY = "customKernel"
    const val MAX_RAW_IMAGE_BYTES = 128L * 1024L * 1024L

    private val RAW_IMAGE_KEYS = setOf(
        "file",
        "loadAddress",
        "entry",
        "cpsr",
        "dtbFile",
        "dtbAddress",
        "initrdFile",
        "initrdAddress",
        "r0",
        "r1",
        "r2",
        "sp",
    )

    data class RawImageSpec(
        val file: String,
        val loadAddress: Long,
        val entry: Long,
        val cpsr: Long,
        val dtbFile: String?,
        val dtbAddress: Long?,
        val initrdFile: String?,
        val initrdAddress: Long?,
        val r0: Long,
        val r1: Long,
        val r2: Long,
        val sp: Long?,
    )

    data class LoadedRawImage(
        val spec: RawImageSpec,
        val imageBytes: ByteArray,
        val imageSha256: String,
        val dtbBytes: ByteArray?,
        val dtbSha256: String?,
        val initrdBytes: ByteArray?,
        val initrdSha256: String?,
    )

    fun allowCustomFirmware(config: Map<String, Any?>, mode: String): Boolean {
        val raw = config[ALLOW_CUSTOM_KEY] ?: return false
        require(raw is Boolean) { "$ALLOW_CUSTOM_KEY requires boolean" }
        if (raw) {
            require(mode != "strict") { "$ALLOW_CUSTOM_KEY is forbidden in strict mode" }
        }
        return raw
    }

    fun parseRawImage(config: Map<String, Any?>, mode: String): RawImageSpec? {
        val hasRaw = config.containsKey(CUSTOM_RAW_KEY)
        val hasKernel = config.containsKey(CUSTOM_KERNEL_KEY)
        if (!hasRaw && !hasKernel) return null
        require(!(hasRaw && hasKernel)) { "Specify at most one of $CUSTOM_RAW_KEY or $CUSTOM_KERNEL_KEY" }
        require(mode != "strict") { "customRawImage/customKernel is forbidden in strict mode" }
        val keyName = if (hasRaw) CUSTOM_RAW_KEY else CUSTOM_KERNEL_KEY
        val raw = config[keyName]
        if (raw == null) return null
        val map = raw as? Map<*, *> ?: throw IllegalArgumentException("$keyName requires a map")
        require(map.keys.all { it is String && it in RAW_IMAGE_KEYS }) {
            "$keyName contains unsupported keys: ${map.keys - RAW_IMAGE_KEYS}"
        }
        val file = map["file"] as? String ?: throw IllegalArgumentException("$keyName.file requires string")
        require(file.isNotBlank()) { "$keyName.file must not be blank" }
        val loadAddress = parseAddress(map["loadAddress"], "$keyName.loadAddress")
        val entry = if (map.containsKey("entry")) parseAddress(map["entry"], "$keyName.entry") else loadAddress
        require((entry and 3L) == 0L) { "$keyName.entry must be 4-byte aligned ARM32 address" }
        val cpsr = if (map.containsKey("cpsr")) parseAddress(map["cpsr"], "$keyName.cpsr") else 0xd3L
        require((cpsr and 0x220L) == 0L) { "$keyName.cpsr requires little-endian ARM32 state" }

        val dtbFile = (map["dtbFile"] as? String)?.takeIf { it.isNotBlank() }
        if (map.containsKey("dtbFile") && map["dtbFile"] != null) {
            require(map["dtbFile"] is String) { "$keyName.dtbFile requires string" }
        }
        val dtbAddress = if (map.containsKey("dtbAddress") && map["dtbAddress"] != null) {
            parseAddress(map["dtbAddress"], "$keyName.dtbAddress")
        } else null
        require((dtbFile == null) == (dtbAddress == null)) {
            "$keyName.dtbFile and $keyName.dtbAddress must be provided together"
        }

        val initrdFile = (map["initrdFile"] as? String)?.takeIf { it.isNotBlank() }
        if (map.containsKey("initrdFile") && map["initrdFile"] != null) {
            require(map["initrdFile"] is String) { "$keyName.initrdFile requires string" }
        }
        val initrdAddress = if (map.containsKey("initrdAddress") && map["initrdAddress"] != null) {
            parseAddress(map["initrdAddress"], "$keyName.initrdAddress")
        } else null
        require((initrdFile == null) == (initrdAddress == null)) {
            "$keyName.initrdFile and $keyName.initrdAddress must be provided together"
        }

        val r0 = if (map.containsKey("r0")) parseAddress(map["r0"], "$keyName.r0") else 0L
        val r1 = if (map.containsKey("r1")) parseAddress(map["r1"], "$keyName.r1") else if (dtbFile != null) 0xffff_ffffL else 0L
        val r2 = if (map.containsKey("r2")) parseAddress(map["r2"], "$keyName.r2") else (dtbAddress ?: 0L)
        val sp = if (map.containsKey("sp") && map["sp"] != null) parseAddress(map["sp"], "$keyName.sp") else null

        return RawImageSpec(
            file = file,
            loadAddress = loadAddress,
            entry = entry,
            cpsr = cpsr,
            dtbFile = dtbFile,
            dtbAddress = dtbAddress,
            initrdFile = initrdFile,
            initrdAddress = initrdAddress,
            r0 = r0,
            r1 = r1,
            r2 = r2,
            sp = sp,
        )
    }

    fun loadRawImage(home: Path, spec: RawImageSpec): LoadedRawImage {
        val imageBytes = readBoundedFile(home.resolve(spec.file), "custom raw image")
        val dtbBytes = spec.dtbFile?.let { readBoundedFile(home.resolve(it), "custom DTB") }
        val initrdBytes = spec.initrdFile?.let { readBoundedFile(home.resolve(it), "custom initrd") }
        return LoadedRawImage(
            spec = spec,
            imageBytes = imageBytes,
            imageSha256 = sha256Hex(imageBytes),
            dtbBytes = dtbBytes,
            dtbSha256 = dtbBytes?.let(::sha256Hex),
            initrdBytes = initrdBytes,
            initrdSha256 = initrdBytes?.let(::sha256Hex),
        )
    }

    fun applyCliOverrides(config: Map<String, Any>, args: Array<String>): Map<String, Any> {
        fun option(name: String): String? =
            args.indexOf(name).takeIf { it >= 0 }?.let {
                require(it + 1 < args.size) { "Missing argument for $name" }
                args[it + 1]
            }

        val out = LinkedHashMap<String, Any>(config)
        option("--firmware")?.let { out["firmwareFile"] = it }
        option("--cpu-engine")?.let { out[CpuEngineConfig.CPU_ENGINE_KEY] = it }
        option("--dynarmic-library")?.let { out[CpuEngineConfig.DYNARMIC_LIBRARY_KEY] = it }
        args.firstOrNull { it.startsWith("--smp-host-threads=") }?.let { flag ->
            val raw = flag.substringAfter('=').trim().lowercase()
            out[CpuEngineConfig.SMP_HOST_THREADS_KEY] = when (raw) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> throw IllegalArgumentException("Invalid --smp-host-threads=$raw (expected true | false)")
            }
        } ?: option("--smp-host-threads")?.let { raw ->
            out[CpuEngineConfig.SMP_HOST_THREADS_KEY] = when (raw.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> throw IllegalArgumentException("Invalid --smp-host-threads '$raw' (expected true | false)")
            }
        }
        if ("--no-smp-host-threads" in args) {
            out[CpuEngineConfig.SMP_HOST_THREADS_KEY] = false
        }
        if ("--allow-custom-firmware" in args || java.lang.Boolean.getBoolean(ALLOW_CUSTOM_PROPERTY)) {
            out[ALLOW_CUSTOM_KEY] = true
        }
        val cliKernel = option("--custom-kernel") ?: option("--custom-raw-image")
        if (cliKernel != null) {
            val kernelMap = linkedMapOf<String, Any>(
                "file" to cliKernel,
                "loadAddress" to (option("--load-address") ?: "0x10008000"),
            )
            option("--entry")?.let { kernelMap["entry"] = it }
            option("--dtb")?.let { kernelMap["dtbFile"] = it }
            option("--dtb-address")?.let { kernelMap["dtbAddress"] = it }
            option("--initrd")?.let { kernelMap["initrdFile"] = it }
            option("--initrd-address")?.let { kernelMap["initrdAddress"] = it }
            out.remove(CUSTOM_KERNEL_KEY)
            out[CUSTOM_RAW_KEY] = kernelMap
        }
        return out
    }

    private fun parseAddress(value: Any?, field: String): Long {
        val parsed = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is String -> {
                val trimmed = value.trim()
                if (trimmed.startsWith("0x", ignoreCase = true)) {
                    java.lang.Long.parseUnsignedLong(trimmed.substring(2), 16)
                } else {
                    trimmed.toLong()
                }
            }
            else -> throw IllegalArgumentException("$field requires integer or hex string")
        }
        require(parsed in 0L..0xffff_ffffL) { "$field out of 32-bit ARM range: $parsed" }
        return parsed
    }

    private fun readBoundedFile(path: Path, label: String): ByteArray {
        require(Files.isRegularFile(path)) { "$label file missing: $path" }
        val size = Files.size(path)
        require(size in 1L..MAX_RAW_IMAGE_BYTES) { "$label size out of bounds: $size" }
        val bytes = Files.newInputStream(path).use { it.readNBytes(Math.toIntExact(size + 1)) }
        require(bytes.size.toLong() == size) { "$label changed during read" }
        return bytes
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
