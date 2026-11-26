package com.github.nyabanban.fuji.emulator.cpu

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

internal object DynarmicTestHelper {
    fun repoRoot(): Path {
        var cur = Path.of("").toAbsolutePath().normalize()
        while (cur.parent != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts"))) return cur
            cur = cur.parent
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    fun requireDynarmicLib(): Path {
        val env = System.getenv("FUJI_DYNARMIC_LIBRARY")?.takeIf { it.isNotBlank() }?.let(Path::of)
        val found = if (env != null && Files.isRegularFile(env)) {
            env
        } else {
            val root = repoRoot()
            val isWin = System.getProperty("os.name").lowercase().contains("win")
            val name = if (isWin) "fuji_dynarmic.dll" else "libfuji_dynarmic.so"
            val buildDir = if (isWin) "build-win64" else "build-linux"
            listOf(
                root.resolve(".tools/dynarmic-v1/$buildDir/$name"),
                root.resolve(".tools/dynarmic-v1/build/$name"),
                root.resolve("runtime/native/$name"),
            ).firstOrNull { Files.isRegularFile(it) }
        }
        assumeTrue(found != null, "Skipping live Dynarmic test: native library missing")
        return checkNotNull(found)
    }

    fun words(vararg values: Long): ByteArray =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putInt(it.toInt()) }
        }.array()

    fun branch(from: Long, to: Long, cond: Long = 0xe000_0000L): Long =
        cond or 0x0a00_0000L or (((to - (from + 8L)) shr 2) and 0x00ff_ffffL)
}
