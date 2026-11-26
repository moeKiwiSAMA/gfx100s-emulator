package com.github.nyabanban.fuji.emulator.desktop

import com.github.nyabanban.fuji.emulator.runtime.Formats
import com.github.nyabanban.fuji.emulator.runtime.RelaxedPeripheralFallback
import com.github.nyabanban.fuji.emulator.runtime.TelemetryHubClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryDesktopConfigTest {
    @Test
    fun launchConfigValidationAcceptsOptionalTelemetryBlockAndRejectsInvalidValues() {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val base = Formats.read(repo.resolve("tools/release/emulator-release-linux.yaml"))

        // Omitted telemetry block -> valid (backward compatible)
        validateLaunchConfiguration(base)

        // Boolean & structured map forms -> valid
        validateLaunchConfiguration(base + ("telemetry" to true))
        validateLaunchConfiguration(base + ("telemetry" to false))
        validateLaunchConfiguration(
            base + (
                "telemetry" to mapOf(
                    "enabled" to true,
                    "endpoint" to "",
                    "ingestToken" to "",
                    "uploadLogOnExit" to true,
                )
            ),
        )
        validateLaunchConfiguration(
            base + (
                "telemetry" to mapOf(
                    "enabled" to true,
                    "endpoint" to "https://telemetry.example.test",
                    "ingestToken" to "placeholder",
                    "uploadLogOnExit" to false,
                )
            ),
        )

        // Invalid keys / types / endpoint schemes -> rejected
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("telemetry" to mapOf("enabled" to true, "extra" to 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("telemetry" to mapOf("endpoint" to "not-a-url")))
        }
        assertFailsWith<IllegalArgumentException> {
            validateLaunchConfiguration(base + ("telemetry" to "enabled"))
        }
    }

    @Test
    fun emulatorRuntimeEnforcesModeGateAndEnvOverridesYamlAndWiresControlApi(@TempDir tempRoot: Path) {
        val repo = Path.of(System.getProperty("componentProjectRoot"))
        val fixtures = IntegrationTest()
        val home = fixtures.fixture()
        val requests = CopyOnWriteArrayList<String>()
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val mockHub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16)
        mockHub.executor = executor
        mockHub.createContext("/") { ex ->
            try {
                requests.add("${ex.requestMethod} ${ex.requestURI.path} ${ex.requestHeaders.getFirst("Authorization")}")
                val payload = when {
                    ex.requestURI.path == "/api/v1/sessions" -> mapOf("sessionId" to "desk-sid-1", "status" to "RUNNING")
                    ex.requestURI.path == "/api/v1/ingest/emulator-log" -> mapOf("sessionId" to "desk-sid-1", "accepted" to 1)
                    else -> mapOf("ok" to true)
                }
                val bytes = Formats.jsonBytes(payload)
                ex.responseHeaders.set("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.write(bytes)
            } finally {
                ex.close()
            }
        }
        mockHub.start()

        try {
            val envEndpoint = "http://127.0.0.1:${mockHub.address.port}"
            val baseConfig = mapOf<String, Any>(
                "schema" to 1,
                "components" to "compoments",
                "httpPort" to 0,
                "firmwareMetadata" to repo.resolve("configurations/jvm/firmware/gfx100s-213.yaml").toString(),
                "firmwareFile" to tempRoot.resolve("unused.dat").toString(),
                "nativeLibrary" to tempRoot.resolve("unused.so").toString(),
                "instructionBudget" to 1000,
                "enabledDevices" to listOf("soc-timer"),
                "telemetry" to mapOf(
                    "enabled" to true,
                    "endpoint" to "http://127.0.0.1:1",
                    "ingestToken" to "yaml-token",
                    "uploadLogOnExit" to true,
                ),
            )
            val envOverride = mapOf(
                TelemetryHubClient.ENV_URL to envEndpoint,
                TelemetryHubClient.ENV_INGEST_TOKEN to "env-override-token",
            )

            // 1. discovery mode: telemetry MUST be null and make zero network requests
            EmulatorRuntime(home, baseConfig, telemetryEnv = envOverride).use { rt ->
                assertNull(rt.telemetryPublisher, "discovery mode must never enable telemetry")
            }
            assertTrue(requests.isEmpty(), "discovery mode must make zero network requests")

            // 2. relaxed mode + --no-telemetry: telemetry MUST be null
            val relaxedConfig = baseConfig + ("executionMode" to RelaxedPeripheralFallback.MODE)
            EmulatorRuntime(home, relaxedConfig, telemetryDisabled = true, telemetryEnv = envOverride).use { rt ->
                assertNull(rt.telemetryPublisher, "--no-telemetry must disable telemetry")
            }
            assertTrue(requests.isEmpty(), "--no-telemetry must make zero network requests")

            // 3. relaxed mode + env overriding YAML: env endpoint & token win, ControlServer route works
            Files.createDirectories(home.resolve("logs"))
            Files.writeString(home.resolve("logs/latest.log"), "desktop smoke log\n", StandardCharsets.UTF_8)

            EmulatorRuntime(home, relaxedConfig, telemetryEnv = envOverride).use { rt ->
                val publisher = rt.telemetryPublisher
                assertNotNull(publisher, "relaxed mode with resolved endpoint+token must enable telemetry")
                assertEquals(envEndpoint, publisher.config.endpoint)
                assertEquals("env-override-token", publisher.config.ingestToken)
                assertEquals("gfx100s-213", publisher.config.firmwareId)

                rt.serve()
                val port = rt.server!!.address.port
                val http = HttpClient.newHttpClient()
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/telemetry/upload-log"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, resp.statusCode(), resp.body())
            }

            assertTrue(
                requests.any { it == "POST /api/v1/ingest/emulator-log Bearer env-override-token" },
                "Expected upload to env-overridden endpoint with env-overridden token; saw $requests",
            )
            assertTrue(
                requests.any { it.startsWith("PATCH /api/v1/sessions/desk-sid-1 Bearer env-override-token") },
                "Expected session PATCH on close; saw $requests",
            )
            val aliveTelemetry = Thread.getAllStackTraces().keys.filter { it.name == "fuji-telemetry" && it.isAlive }
            assertTrue(aliveTelemetry.isEmpty(), "Expected zero leaked fuji-telemetry threads")
        } finally {
            mockHub.stop(0)
            executor.close()
            home.toFile().deleteRecursively()
        }
    }
}
