package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlApiDocGeneratorTest {
    private val http = HttpClient.newHttpClient()

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath().normalize()
        while (!Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("Unable to locate repository root from ${Path.of("").toAbsolutePath()}")
        }
        return dir
    }

    private fun extractRoutesFromControlServer(source: String): Set<Pair<String, String>> {
        val exactRegex = Regex("""path\s*==\s*"(/[^"]+)"\s*&&\s*request\.requestMethod\s*==\s*"(GET|POST|PATCH|PUT|DELETE)"""")
        val patternRegex = Regex("""path\.matches\(Regex\("(/[^"]+)"\)\)\s*&&\s*request\.requestMethod\s*==\s*"(GET|POST|PATCH|PUT|DELETE)"""")
        val routes = linkedSetOf<Pair<String, String>>()
        for (m in exactRegex.findAll(source)) {
            routes += m.groupValues[2] to m.groupValues[1]
        }
        for (m in patternRegex.findAll(source)) {
            val rawPattern = m.groupValues[1]
            val normalizedPath = when (rawPattern) {
                "/api/v1/components/[a-z][a-z0-9-]{0,63}" -> "/api/v1/components/{id}"
                else -> rawPattern
            }
            routes += m.groupValues[2] to normalizedPath
        }
        return routes
    }

    @Test
    fun specHasExactOneToOneRouteParityWithControlServer() {
        val root = repoRoot()
        val serverSource = Files.readString(
            root.resolve("runtime/src/main/kotlin/com/github/nyabanban/fuji/emulator/runtime/ControlServer.kt"),
            Charsets.UTF_8,
        )
        val serverRoutes = extractRoutesFromControlServer(serverSource)
        assertTrue(serverRoutes.size >= 36, "Expected at least 36 routes parsed from ControlServer.kt, got ${serverRoutes.size}")

        val specRoutes = ControlApiSpec.endpoints.map { it.method.name to it.path }
        assertEquals(specRoutes.size, specRoutes.toSet().size, "Duplicate (method, path) entries in ControlApiSpec")

        val opIds = ControlApiSpec.endpoints.map { it.operationId }
        assertEquals(opIds.size, opIds.toSet().size, "Duplicate operationId entries in ControlApiSpec")

        val missingInSpec = serverRoutes - specRoutes.toSet()
        val extraInSpec = specRoutes.toSet() - serverRoutes
        assertEquals(
            emptySet(),
            missingInSpec,
            "Routes present in ControlServer.kt but missing from ControlApiSpec: $missingInSpec",
        )
        assertEquals(
            emptySet(),
            extraInSpec,
            "Routes present in ControlApiSpec but missing from ControlServer.kt: $extraInSpec",
        )
    }

    @Test
    fun generatedOpenApiSchemaIsValidOpenApi310() {
        val json = ControlApiDocGenerator.generateOpenApiJson()
        val root = Formats.json.readTree(json)
        assertEquals("3.1.0", root.path("openapi").asText())
        assertEquals(ControlApiSpec.API_TITLE, root.path("info").path("title").asText())
        assertEquals(ControlApiSpec.API_VERSION, root.path("info").path("version").asText())
        assertTrue(root.path("servers").isArray && root.path("servers").size() >= 1)
        assertTrue(root.path("tags").isArray && root.path("tags").size() == ControlApiSpec.tags.size)
        assertTrue(root.path("components").path("schemas").has("ErrorResponse"))

        val pathsNode = root.path("paths")
        assertTrue(pathsNode.isObject)
        val definedTags = ControlApiSpec.tags.map { it.first }.toSet()
        for (ep in ControlApiSpec.endpoints) {
            assertTrue(ep.tag in definedTags, "Endpoint ${ep.operationId} uses undeclared tag '${ep.tag}'")
            val pathItem = pathsNode.path(ep.path)
            assertTrue(pathItem.isObject, "Missing path '${ep.path}' in OpenAPI paths")
            val op = pathItem.path(ep.method.name.lowercase())
            assertTrue(op.isObject, "Missing ${ep.method} under '${ep.path}'")
            assertEquals(ep.operationId, op.path("operationId").asText())
            assertTrue(op.path("summary").asText().isNotBlank())
            val responses = op.path("responses")
            assertTrue(responses.isObject && responses.size() >= 1)
            for (r in ep.responses) {
                assertTrue(responses.has(r.status.toString()), "Missing status ${r.status} for ${ep.operationId}")
            }
        }
    }

    @Test
    fun committedDocsMatchGeneratorByteForByteAndContainNoExternalCdns() {
        val root = repoRoot()
        val docsDir = root.resolve("docs/api")
        val generated = ControlApiDocGenerator.generateAll()

        val expectedFiles = mapOf(
            ControlApiDocGenerator.OPENAPI_FILENAME to generated.openApiJson,
            ControlApiDocGenerator.MARKDOWN_FILENAME to generated.markdown,
            ControlApiDocGenerator.HTML_FILENAME to generated.html,
        )
        for ((name, expectedText) in expectedFiles) {
            val file = docsDir.resolve(name)
            assertTrue(Files.isRegularFile(file), "Missing generated doc file: docs/api/$name (run ./gradlew :generateHttpApiDocs)")
            val actualBytes = Files.readAllBytes(file)
            assertFalse(
                actualBytes.size >= 3 && actualBytes[0] == 0xEF.toByte() && actualBytes[1] == 0xBB.toByte() && actualBytes[2] == 0xBF.toByte(),
                "UTF-8 BOM prohibited in docs/api/$name",
            )
            assertFalse(actualBytes.contains('\r'.code.toByte()), "CRLF prohibited in docs/api/$name")
            assertTrue(actualBytes.isNotEmpty() && actualBytes.last() == '\n'.code.toByte(), "Single trailing LF required in docs/api/$name")
            assertFalse(
                actualBytes.size >= 2 && actualBytes[actualBytes.size - 2] == '\n'.code.toByte(),
                "Multiple trailing LFs prohibited in docs/api/$name",
            )
            assertContentEquals(
                expectedText.toByteArray(Charsets.UTF_8),
                actualBytes,
                "docs/api/$name is out of sync with ControlApiSpec/ControlApiDocGenerator; re-run ./gradlew :generateHttpApiDocs",
            )
        }

        // Strict offline HTML check: zero external http(s) <script> or <link> references
        val externalTagRegex = Regex("""<(script|link)\b[^>]*\b(src|href)\s*=\s*["']https?://""", RegexOption.IGNORE_CASE)
        assertFalse(
            externalTagRegex.containsMatchIn(generated.html),
            "Offline HTML explorer must not reference external CDN <script> or <link> URLs",
        )
        assertTrue(generated.html.contains("id=\"api-spec-data\""))
        assertTrue(generated.html.contains("fetch(url, opts)"))
    }

    @Test
    fun liveControlServerServesOpenApiJsonAndInteractiveDocsWithSameOriginTryIt() {
        val stub = object : ControlClient {
            override fun snapshot(): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(mapOf("components" to emptyList<Any>()))
            override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(mapOf("id" to id, "revision" to revision + 1))
        }
        ControlServer(control = stub, port = 0).use { server ->
            val port = server.address.port

            val openApiRes = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/openapi.json")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, openApiRes.statusCode())
            assertEquals("application/json; charset=utf-8", openApiRes.headers().firstValue("Content-Type").orElse(""))
            assertContentEquals(ControlApiDocGenerator.openApiJsonBytes, openApiRes.body())
            val parsed = Formats.json.readTree(openApiRes.body())
            assertEquals("3.1.0", parsed.path("openapi").asText())

            val docsRes = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/docs")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            assertEquals(200, docsRes.statusCode())
            assertEquals("text/html; charset=utf-8", docsRes.headers().firstValue("Content-Type").orElse(""))
            assertContentEquals(ControlApiDocGenerator.interactiveHtmlBytes, docsRes.body())
            val htmlText = String(docsRes.body(), Charsets.UTF_8)
            val externalTagRegex = Regex("""<(script|link)\b[^>]*\b(src|href)\s*=\s*["']https?://""", RegexOption.IGNORE_CASE)
            assertFalse(externalTagRegex.containsMatchIn(htmlText))

            // Interactive Try-It from the same loopback origin succeeds for POST
            val sameOriginPost = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/lang"))
                    .header("Content-Type", "application/json")
                    .header("Origin", "http://127.0.0.1:$port")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"language":"zh_CN"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, sameOriginPost.statusCode())
            assertEquals("zh_CN", Formats.json.readTree(sameOriginPost.body()).path("language").asText())

            // Cross-origin and wrong-port origins remain strictly refused (400)
            for (badOrigin in listOf("http://evil.example", "http://127.0.0.1:${port + 1}", "null")) {
                val rejected = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/openapi.json"))
                        .header("Origin", badOrigin)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(400, rejected.statusCode(), "Expected 400 for cross-origin '$badOrigin'")
            }
        }
    }
}
