package com.github.nyabanban.fuji.emulator.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the zero-network offline KDoc API reference generator (`:generateApiDocs`)
 * on both synthetic Kotlin/Java sources and the real 5 core emulator modules.
 */
class OfflineKdocApiGeneratorTest {

    private fun repoRoot(): Path {
        System.getProperty("catalogRoot")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        var cur = Path.of("").toAbsolutePath().normalize()
        while (cur.parent != null) {
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts"))) return cur
            cur = cur.parent
        }
        error("Could not locate repository root")
    }

    @Test
    fun parsesKotlinAndJavaKdocAndFiltersNonPublicMembers() {
        val ktSource = """
            package com.example.demo

            /**
             * Primary sample contract.
             *
             * Second detail paragraph.
             * @param id unique identifier
             * @return formatted label
             */
            interface SamplePort {
                /** Computes a formatted label. */
                fun render(id: Int): String
                private fun secretHelper(): Unit = Unit
                internal val internalFlag: Boolean
            }

            private class HiddenImpl
        """.trimIndent()

        val ktTypes = OfflineKdocApiGenerator.parseSourceFile("demo", "demo/src/main/kotlin/SamplePort.kt", ktSource)
        assertEquals(1, ktTypes.size)
        val port = ktTypes.single()
        assertEquals("com.example.demo.SamplePort", port.fqName)
        assertEquals("interface", port.kind)
        assertEquals("Primary sample contract.", port.doc?.summary)
        assertEquals("Second detail paragraph.", port.doc?.details)
        assertEquals(listOf("id" to "unique identifier"), port.doc?.params)
        assertEquals("formatted label", port.doc?.returns)
        assertEquals(listOf("render"), port.members.map { it.name })

        val javaSource = """
            package com.example.demo;

            /** Java FFM bridge. */
            public final class NativeBridge {
                /** Opens a handle. */
                public long open(int mode) { return 0L; }
                private void hidden() {}
            }
        """.trimIndent()
        val javaTypes = OfflineKdocApiGenerator.parseSourceFile("demo", "demo/src/main/java/NativeBridge.java", javaSource)
        assertEquals(1, javaTypes.size)
        assertEquals("NativeBridge", javaTypes.single().name)
        assertEquals("Java FFM bridge.", javaTypes.single().doc?.summary)
        assertEquals(listOf("open"), javaTypes.single().members.map { it.name })
    }

    @Test
    fun generatesOfflineDeterministicApiDocsForAllFiveCoreModules(@TempDir tempDir: Path) {
        val outA = tempDir.resolve("run-a")
        val outB = tempDir.resolve("run-b")

        val reportA = OfflineKdocApiGenerator.generate(repoRoot(), outA)
        val reportB = OfflineKdocApiGenerator.generate(repoRoot(), outB)

        assertEquals(OfflineKdocApiGenerator.DEFAULT_MODULES, reportA.modules)
        assertEquals(reportA.totalTypes, reportB.totalTypes)
        assertEquals(reportA.totalMembers, reportB.totalMembers)
        assertTrue(reportA.totalFilesScanned >= 30, "Expected >=30 source files across 5 core modules")
        assertTrue(reportA.totalTypes >= 40, "Expected >=40 public API types across 5 core modules")

        // Verify key core contract files exist across all 5 modules
        assertTrue(Files.isRegularFile(outA.resolve("index.html")))
        assertTrue(Files.isRegularFile(outA.resolve("index.md")))
        assertTrue(Files.isRegularFile(outA.resolve("style.css")))
        assertTrue(Files.isRegularFile(outA.resolve("api-index.json")))
        for (mod in OfflineKdocApiGenerator.DEFAULT_MODULES) {
            assertTrue(Files.isRegularFile(outA.resolve("$mod/index.html")), "Missing $mod/index.html")
            assertTrue(Files.isRegularFile(outA.resolve("$mod/index.md")), "Missing $mod/index.md")
        }

        val apiJson = Files.readString(outA.resolve("api-index.json"), StandardCharsets.UTF_8)
        assertTrue(apiJson.contains("MmioDevice"), "Expected component-api MmioDevice in api-index.json")
        assertTrue(apiJson.contains("OwnerLoop"), "Expected runtime OwnerLoop in api-index.json")
        assertTrue(apiJson.contains("Dynarmic"), "Expected cpu-dynarmic Dynarmic in api-index.json")
        assertTrue(apiJson.contains("ArmCoreSnapshot"), "Expected cpu-dynarmic ArmCoreSnapshot in api-index.json")
        assertTrue(apiJson.contains("FirmwareImage"), "Expected firmware FirmwareImage in api-index.json")
        assertTrue(apiJson.contains("BootRomImage"), "Expected firmware BootRomImage in api-index.json")
        assertTrue(apiJson.contains("OfflineKdocApiGenerator"), "Expected address-catalog OfflineKdocApiGenerator in api-index.json")

        // Offline hygiene: zero external HTTP/HTTPS CDN refs, UTF-8 no-BOM LF single-trailing-LF, deterministic
        val filesA = Files.walk(outA).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        val filesB = Files.walk(outB).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }
        assertEquals(filesA.size, filesB.size)

        for (idx in filesA.indices) {
            val fa = filesA[idx]
            val fb = filesB[idx]
            assertEquals(outA.relativize(fa), outB.relativize(fb))
            val bytesA = Files.readAllBytes(fa)
            val bytesB = Files.readAllBytes(fb)
            assertTrue(bytesA.contentEquals(bytesB), "Non-deterministic output in ${outA.relativize(fa)}")
            val text = String(bytesA, StandardCharsets.UTF_8)
            assertFalse(text.startsWith("\uFEFF"), "BOM in $fa")
            assertFalse('\r' in text, "CRLF in $fa")
            assertTrue(text.endsWith("\n") && !text.endsWith("\n\n"), "Single trailing LF in $fa")
            val externalAssetRegex = Regex("""(?:href|src)\s*=\s*["']https?://|@import\s+url\(\s*["']?https?://""", RegexOption.IGNORE_CASE)
            assertFalse(externalAssetRegex.containsMatchIn(text), "External CDN asset in offline doc $fa")
            val hostRoot = repoRoot().toAbsolutePath().normalize().toString().replace('\\', '/')
            assertFalse(text.contains(hostRoot), "Host absolute path leak in $fa")
        }
        assertNotNull(reportA)
    }
}
