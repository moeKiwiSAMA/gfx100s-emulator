package com.github.nyabanban.fuji.emulator.desktop.qualification

import com.github.nyabanban.fuji.emulator.runtime.Formats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Test-only synthetic A32 firmware container; never ships in desktop.jar. */
object SyntheticGuest {
    fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun prepare(
        home: Path,
        components: Path,
        library: Path,
        code: ByteArray,
        profile: String,
        budget: Int,
        tracePoints: List<Long> = emptyList(),
        workspacePermissions: Int = 3,
    ): Map<String, Any> {
        Files.createDirectories(home.resolve("compoments"))
        Files.list(components).use { paths ->
            paths.filter { it.toString().endsWith(".jar") }.forEach {
                Files.copy(it, home.resolve("compoments").resolve(it.fileName))
            }
        }
        Formats.atomicYaml(home.resolve("compoments/system-memory/system-memory.yaml"), mapOf("profile" to profile))
        Formats.atomicYaml(home.resolve("compoments/soc-dma/soc-dma.yaml"), mapOf("interruptPort" to "soc-gic.lines"))
        val update = ByteArray(0x20274 + code.size)
        ByteBuffer.wrap(update).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 6)
            putInt(0x24c, 0x20000 + code.size)
        }
        code.indices.forEach { update[0x20274 + it] = code[it].toInt().inv().toByte() }
        Files.write(home.resolve("synthetic.update"), update)
        val low = mapOf("id" to "loader", "address" to 0, "size" to 0x20000, "permissions" to 7)
        val workspace = mapOf("id" to "metadata-workspace", "address" to 0xe20000, "size" to 0x1000, "permissions" to workspacePermissions)
        val footer = mapOf("id" to "footer", "address" to 0x2a69c000, "size" to 0x1000, "permissions" to 3)
        val kernel = mapOf("id" to "kernel", "address" to 0x40000, "size" to 0x46000, "permissions" to 7)
        val devices = listOf(
            mapOf("id" to "soc-dma", "address" to "0xfffe0000", "size" to "0x10000", "port" to "soc-dma.registers"),
            mapOf("id" to "soc-dma", "address" to "0xfff4a500", "size" to "0x20", "port" to "soc-dma.mux"),
            mapOf("id" to "soc-dma", "address" to "0xfff4a800", "size" to "0x80", "port" to "soc-dma.requests"),
            mapOf("id" to "soc-gic", "address" to "0xffff1000", "size" to "0x1000", "port" to "soc-gic.distributor"),
            mapOf("id" to "soc-gic", "address" to "0xffff2000", "size" to "0x1000", "port" to "soc-gic.interface"),
        )
        Formats.atomicYaml(
            home.resolve("metadata.yaml"),
            mapOf(
                "schema" to 1,
                "id" to "synthetic-memory",
                "version" to "TEST",
                "size" to update.size,
                "sha256" to hash(update),
                "entry" to 0,
                "cpsr" to 0xd3,
                "loaderMemoryBytes" to 0x20000,
                "memoryProfiles" to mapOf(
                    "loader-low128-experimental-v1" to listOf(low),
                    "loader-workspace-experimental-v1" to listOf(low, workspace),
                    "loader-footer-experimental-v2" to listOf(low, workspace, footer),
                    "loader-kernel-experimental-v3" to listOf(low, kernel, workspace, footer),
                ),
                "bootMode" to "public-synthetic-guest",
                "sections" to listOf(
                    mapOf("name" to "boot", "address" to 0, "size" to code.size, "offset" to 0x20274, "encoding" to "raw", "sha256" to hash(code)),
                ),
                "devices" to devices,
                "evidence" to listOf("TEST"),
            ),
        )
        return mapOf(
            "executionMode" to "strict",
            "components" to "compoments",
            "firmwareMetadata" to "metadata.yaml",
            "firmwareFile" to "synthetic.update",
            "dynarmicLibrary" to library.toAbsolutePath().toString(),
            "instructionBudget" to budget,
            "enabledDevices" to listOf("soc-dma", "soc-gic"),
            "tracePoints" to tracePoints,
        )
    }
}
