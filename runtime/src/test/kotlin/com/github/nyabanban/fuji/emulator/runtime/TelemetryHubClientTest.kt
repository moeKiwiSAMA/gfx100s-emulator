package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryHubClientTest {
    private data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val authHeader: String?,
        val contentType: String?,
        val contentEncoding: String?,
        val rawBody: ByteArray,
    )

    private class MockTelemetryHub(
        private val responseDelayMs: Long = 0L,
        private val forceStatus: Int? = null,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32)
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { ex -> handle(ex) }
            server.start()
        }

        private fun handle(ex: HttpExchange) {
            try {
                val rawBody = ex.requestBody.readAllBytes()
                requests.add(
                    RecordedRequest(
                        method = ex.requestMethod,
                        path = ex.requestURI.path,
                        query = ex.requestURI.rawQuery,
                        authHeader = ex.requestHeaders.getFirst("Authorization"),
                        contentType = ex.requestHeaders.getFirst("Content-Type"),
                        contentEncoding = ex.requestHeaders.getFirst("Content-Encoding"),
                        rawBody = rawBody,
                    ),
                )
                if (responseDelayMs > 0L) {
                    Thread.sleep(responseDelayMs)
                }
                if (forceStatus != null) {
                    respondJson(ex, forceStatus, mapOf("error" to "forced-$forceStatus"))
                    return
                }
                when {
                    ex.requestMethod == "POST" && ex.requestURI.path == "/api/v1/sessions" -> {
                        val parsed = Formats.map(Formats.json.readValue(rawBody, Map::class.java))
                        val sid = (parsed["sessionId"] as? String)?.takeIf { it.isNotBlank() } ?: "srv-session-1"
                        respondJson(ex, 201, mapOf("sessionId" to sid, "status" to "RUNNING"))
                    }
                    ex.requestMethod == "POST" && ex.requestURI.path == "/api/v1/ingest/batch" -> {
                        val decoded = if (ex.requestHeaders.getFirst("Content-Encoding") == "gzip") {
                            GZIPInputStream(ByteArrayInputStream(rawBody)).readAllBytes()
                        } else {
                            rawBody
                        }
                        val list = Formats.json.readValue(decoded, List::class.java)
                        respondJson(ex, 200, mapOf("accepted" to list.size, "deduplicated" to 0, "rejected" to 0))
                    }
                    ex.requestMethod == "POST" && ex.requestURI.path == "/api/v1/ingest/emulator-log" -> {
                        val requestedSid = ex.requestURI.rawQuery
                            ?.split('&')
                            ?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
                            ?.firstOrNull { it[0] == "sessionId" }
                            ?.get(1)
                            ?: "mock-sid"
                        respondJson(
                            ex,
                            200,
                            mapOf(
                                "sessionId" to requestedSid,
                                "filename" to "latest.log",
                                "totalLines" to 4,
                                "accepted" to 3,
                                "deduplicated" to 1,
                                "rejected" to 0,
                                "mmioEvents" to 1,
                                "flashEvents" to 1,
                                "userActionEvents" to 1,
                                "watchdogAndMilestoneEvents" to 1,
                                "summaryLine" to "ok",
                            ),
                        )
                    }
                    ex.requestMethod == "PATCH" && ex.requestURI.path.startsWith("/api/v1/sessions/") -> {
                        val sid = ex.requestURI.path.substringAfterLast('/')
                        respondJson(ex, 200, mapOf("sessionId" to sid, "status" to "FINISHED"))
                    }
                    else -> respondJson(ex, 404, mapOf("error" to "not found"))
                }
            } catch (_: Throwable) {
                runCatching { respondJson(ex, 500, mapOf("error" to "mock error")) }
            } finally {
                ex.close()
            }
        }

        private fun respondJson(ex: HttpExchange, code: Int, payload: Map<String, Any?>) {
            val bytes = Formats.jsonBytes(payload)
            ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.write(bytes)
        }

        override fun close() {
            server.stop(0)
            executor.close()
        }
    }

    private class StubControlClient : ControlClient {
        override fun snapshot(): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(mapOf("revision" to 0L))

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(mapOf("revision" to revision + 1L))
    }

    @Test
    fun configResolutionHonorsModeGateCliPriorityAndEnvOverYamlOverride() {
        val yamlOnly = mapOf(
            "telemetry" to mapOf(
                "enabled" to true,
                "endpoint" to "https://yaml.example.test/",
                "ingestToken" to "yaml-token",
                "uploadLogOnExit" to false,
            ),
        )
        val envBoth = mapOf(
            TelemetryHubClient.ENV_URL to "https://env.example.test/",
            TelemetryHubClient.ENV_INGEST_TOKEN to "env-token",
        )

        // 1. discovery and strict ALWAYS disabled, even with env + yaml credentials
        assertNull(TelemetryHubClient.resolveConfig("discovery", yamlOnly, env = envBoth))
        assertNull(TelemetryHubClient.resolveConfig("strict", yamlOnly, env = envBoth))

        // 2. --no-telemetry ALWAYS wins over env and yaml
        assertNull(TelemetryHubClient.resolveConfig("relaxed", yamlOnly, cliDisable = true, env = envBoth))

        // 3. Env vars override matching YAML fields while preserving YAML uploadLogOnExit
        val envWins = TelemetryHubClient.resolveConfig("relaxed", yamlOnly, env = envBoth, firmwareId = "gfx100s-213")
        assertNotNull(envWins)
        assertEquals("https://env.example.test", envWins.endpoint)
        assertEquals("env-token", envWins.ingestToken)
        assertFalse(envWins.uploadLogOnExit)
        assertEquals("gfx100s-213", envWins.firmwareId)

        // 4. Empty env falls back cleanly to YAML endpoint & ingestToken
        val yamlFallback = TelemetryHubClient.resolveConfig("relaxed", yamlOnly, env = emptyMap())
        assertNotNull(yamlFallback)
        assertEquals("https://yaml.example.test", yamlFallback.endpoint)
        assertEquals("yaml-token", yamlFallback.ingestToken)

        // 5. Partial env overrides only the provided field and falls back to YAML for the other
        val partialEnv = TelemetryHubClient.resolveConfig(
            "relaxed",
            yamlOnly,
            env = mapOf(TelemetryHubClient.ENV_INGEST_TOKEN to "override-only-token"),
        )
        assertNotNull(partialEnv)
        assertEquals("https://yaml.example.test", partialEnv.endpoint)
        assertEquals("override-only-token", partialEnv.ingestToken)

        // 6. Legacy config without telemetry block auto-enables in relaxed when env is set
        val autoEnabled = TelemetryHubClient.resolveConfig("relaxed", emptyMap(), env = envBoth)
        assertNotNull(autoEnabled)
        assertEquals("https://env.example.test", autoEnabled.endpoint)
        assertTrue(autoEnabled.uploadLogOnExit)

        // 7. Unconfigured env + empty YAML silently disables with zero error
        assertNull(TelemetryHubClient.resolveConfig("relaxed", emptyMap(), env = emptyMap()))

        // 8. telemetry.enabled=false disables auto-start
        val disabledYaml = mapOf("telemetry" to mapOf("enabled" to false, "endpoint" to "https://yaml.example.test", "ingestToken" to "tok"))
        assertNull(TelemetryHubClient.resolveConfig("relaxed", disabledYaml, env = envBoth))
        assertNull(TelemetryHubClient.resolveConfig("relaxed", mapOf("telemetry" to false), env = envBoth))
    }

    @Test
    fun validateLaunchConfigAcceptsValidTelemetryFormsAndRejectsInvalidOnes() {
        TelemetryHubClient.validateLaunchConfig(emptyMap())
        TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to true))
        TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to false))
        TelemetryHubClient.validateLaunchConfig(
            mapOf(
                "telemetry" to mapOf(
                    "enabled" to true,
                    "endpoint" to "",
                    "ingestToken" to "",
                    "uploadLogOnExit" to true,
                ),
            ),
        )
        TelemetryHubClient.validateLaunchConfig(
            mapOf(
                "telemetry" to mapOf(
                    "endpoint" to "https://telemetry.example.test",
                    "ingestToken" to "placeholder-token",
                ),
                "telemetryUpload" to true,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to "yes"))
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to mapOf("unknownKey" to true)))
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to mapOf("endpoint" to "ftp://bad.scheme")))
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryHubClient.validateLaunchConfig(mapOf("telemetry" to mapOf("enabled" to "true")))
        }
        assertFailsWith<IllegalArgumentException> {
            TelemetryHubClient.validateLaunchConfig(mapOf("telemetryUpload" to "yes"))
        }
    }

    @Test
    fun publisherStreamsGzipBatchesUploadsLogAndPatchesSessionOnClose(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("latest.log")
        Files.writeString(
            logFile,
            "[Watchdog] Milestone STAGE_1_BOOT_START reached\n[RELAXED/MMIO] source=unmapped op=READ addr=0xfff80000\n",
            StandardCharsets.UTF_8,
        )

        MockTelemetryHub().use { hub ->
            val cfg = TelemetryConfig(
                enabled = true,
                endpoint = hub.baseUrl,
                ingestToken = "unit-test-token",
                uploadLogOnExit = true,
                firmwareId = "gfx100s-213",
                executionMode = RelaxedPeripheralFallback.MODE,
            )
            val publisher = TelemetrySessionPublisher(
                config = cfg,
                logFile = logFile,
                batchSize = 4,
                flushIntervalMs = 200L,
                shutdownBudgetMs = 2_500L,
            )
            publisher.start()

            // Wire all 3 producers (Watchdog, RelaxedPeripheralFallback, UserActionLogger)
            val fwTelemetry = FirmwareTelemetry()
            fwTelemetry.markSessionStarted(1_000_000L)
            fwTelemetry.markStage(FirmwareStage.STAGE_1_BOOT_INIT)
            fwTelemetry.virtualTick = 12_345L
            val watchdog = FirmwareWatchdog(
                telemetry = fwTelemetry,
                infoSink = {},
                warnSink = {},
                telemetryPublisher = publisher,
            )
            watchdog.pollOnce(2_000_000L)

            val fallback = RelaxedPeripheralFallback(
                context = object : RelaxedPeripheralFallback.Context {
                    override fun pc(): Long = 0x01234568L
                    override fun tick(): Long = 12_500L
                    override fun stage(): String = FirmwareStage.STAGE_1_BOOT_INIT.label
                },
                flashLayout = RelaxedPeripheralFallback.FlashLayout(
                    apertureBase = 0x1000_0000L,
                    apertureSize = 0x0400_0000L,
                    registersBase = 0xfffa_0000L,
                    registersSize = 0x1000L,
                    dmaRegistersBase = 0xfffc_0000L,
                    dmaRegistersSize = 0x1_0000L,
                ),
                warnSink = {},
                infoSink = {},
                telemetryPublisher = publisher,
            )
            fallback.read(0xfff9_0010L, 4)
            fallback.read(0xfff9_0010L, 4) // deduplicated -> must NOT emit duplicate event
            fallback.read(0x1000_1000L, 4, source = "device", reason = "unmapped hole")

            val userLogger = UserActionLogger(
                enabled = true,
                infoSink = {},
                telemetryPublisher = publisher,
            )
            userLogger.logComponentPatch("buttons", mapOf("MENU_OK" to true), fwTelemetry)
            userLogger.logPauseState(true, fwTelemetry)

            publisher.markFinalStatus("stopped")
            publisher.close()

            // Verify Channel 1 + Channel 2 HTTP traffic
            val sessionPosts = hub.requests.filter { it.method == "POST" && it.path == "/api/v1/sessions" }
            assertEquals(1, sessionPosts.size, "Expected exactly one session registration")
            assertEquals("Bearer unit-test-token", sessionPosts.single().authHeader)

            val batchPosts = hub.requests.filter { it.method == "POST" && it.path == "/api/v1/ingest/batch" }
            assertTrue(batchPosts.isNotEmpty(), "Expected at least one batch ingest POST")
            val allEnvelopes = mutableListOf<Map<String, Any?>>()
            for (req in batchPosts) {
                assertEquals("Bearer unit-test-token", req.authHeader)
                assertEquals("gzip", req.contentEncoding)
                val unzipped = GZIPInputStream(ByteArrayInputStream(req.rawBody)).readAllBytes()
                @Suppress("UNCHECKED_CAST")
                val rows = Formats.json.readValue(unzipped, List::class.java) as List<Map<String, Any?>>
                allEnvelopes.addAll(rows)
            }
            // Expect 5 distinct events: milestone + mmio + flash + button + pause
            assertEquals(5, allEnvelopes.size, "Expected 5 deduplicated events, got $allEnvelopes")
            val seqs = allEnvelopes.map { (it["seq"] as Number).toLong() }
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seqs, "Expected strictly monotonic seq for idempotency")
            assertEquals(
                setOf("BOOT_AND_CRASH", "HARDWARE_PROBE", "USER_ACTION"),
                allEnvelopes.map { it["eventType"] }.toSet(),
            )
            assertTrue(allEnvelopes.all { it["sessionId"] == publisher.sessionId })

            val logUploads = hub.requests.filter { it.method == "POST" && it.path == "/api/v1/ingest/emulator-log" }
            assertEquals(1, logUploads.size, "Expected auto-upload of latest.log on close")
            assertEquals("Bearer unit-test-token", logUploads.single().authHeader)
            assertTrue(logUploads.single().contentType.orEmpty().startsWith("multipart/form-data; boundary="))
            val bodyText = String(logUploads.single().rawBody, StandardCharsets.UTF_8)
            assertTrue(bodyText.contains("filename=\"latest.log\""), "Multipart must include latest.log filename")
            assertTrue(bodyText.contains("[RELAXED/MMIO] source=unmapped"), "Multipart must carry log file contents")
            assertTrue(logUploads.single().query.orEmpty().contains("sessionId=${publisher.sessionId}"))

            val patches = hub.requests.filter { it.method == "PATCH" && it.path == "/api/v1/sessions/${publisher.sessionId}" }
            assertEquals(1, patches.size, "Expected PATCH /api/v1/sessions/{id} on close")
            val patchJson = Formats.map(Formats.json.readValue(patches.single().rawBody, Map::class.java))
            assertEquals("FINISHED", patchJson["status"])
            assertNotNull(patchJson["endedAt"])
        }
    }

    @Test
    fun queueOverflowDropsWithoutBlockingAndControlServerExposesManualLogUpload(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("latest.log")
        Files.writeString(logFile, "manual upload line\n", StandardCharsets.UTF_8)

        MockTelemetryHub().use { hub ->
            val cfg = TelemetryConfig(
                enabled = true,
                endpoint = hub.baseUrl,
                ingestToken = "tok-manual",
                uploadLogOnExit = false,
            )
            val publisher = TelemetrySessionPublisher(
                config = cfg,
                logFile = logFile,
                queueCapacity = 16,
                batchSize = 100,
                flushIntervalMs = 60_000L,
                shutdownBudgetMs = 1_500L,
            )
            // Do not start worker yet so queue fills deterministically
            val startNanos = System.nanoTime()
            repeat(40) { idx ->
                publisher.recordMmioFallback("unmapped", false, 0xfff8_0000L + idx * 4L, 4, 0L, 0x1000L, idx.toLong(), "S1", null)
            }
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            assertTrue(elapsedMs < 500L, "Enqueue must never block caller")
            assertEquals(16, publisher.queuedEvents)
            assertEquals(24L, publisher.droppedEvents)

            // Verify ControlServer POST /api/telemetry/upload-log when wired vs unwired
            ControlServer(StubControlClient(), 0).use { disabledServer ->
                val http = HttpClient.newHttpClient()
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${disabledServer.address.port}/api/telemetry/upload-log"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(404, resp.statusCode())
            }

            ControlServer(
                StubControlClient(),
                0,
                telemetryUpload = { notes -> publisher.uploadCurrentLogNow(notes) },
            ).use { activeServer ->
                val http = HttpClient.newHttpClient()
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${activeServer.address.port}/api/telemetry/upload-log?notes=qa-check"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, resp.statusCode(), resp.body())
                val json = Formats.map(Formats.json.readValue(resp.body(), Map::class.java))
                assertEquals(true, json["uploaded"])
                assertEquals(3, (json["accepted"] as Number).toInt())
            }

            publisher.close("HUNG")
            val patch = hub.requests.last { it.method == "PATCH" }
            val patchJson = Formats.map(Formats.json.readValue(patch.rawBody, Map::class.java))
            assertEquals("HUNG", patchJson["status"])
        }
    }

    @Test
    fun networkFailureAndServerErrorsDegradeSilentlyWithinShutdownBudget(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("latest.log")
        Files.writeString(logFile, "offline test line\n", StandardCharsets.UTF_8)

        // 1. Server returning HTTP 500
        MockTelemetryHub(forceStatus = 500).use { brokenHub ->
            val cfg = TelemetryConfig(
                enabled = true,
                endpoint = brokenHub.baseUrl,
                ingestToken = "tok-500",
                uploadLogOnExit = true,
            )
            val publisher = TelemetrySessionPublisher(
                config = cfg,
                logFile = logFile,
                flushIntervalMs = 100L,
                shutdownBudgetMs = 800L,
            )
            publisher.start()
            publisher.recordUserAction("button", "buttons", mapOf("DISP" to true), "S5", 99L, 1L, "disp")
            val t0 = System.nanoTime()
            publisher.close() // Must NOT throw
            val closeMs = (System.nanoTime() - t0) / 1_000_000L
            assertTrue(closeMs <= 1_800L, "Close must stay bounded on 500 errors (took ${closeMs}ms)")
        }

        // 2. Slow/hanging server exceeding shutdown budget
        MockTelemetryHub(responseDelayMs = 4_000L).use { slowHub ->
            val cfg = TelemetryConfig(
                enabled = true,
                endpoint = slowHub.baseUrl,
                ingestToken = "tok-slow",
                uploadLogOnExit = true,
            )
            val publisher = TelemetrySessionPublisher(
                config = cfg,
                logFile = logFile,
                flushIntervalMs = 5_000L,
                shutdownBudgetMs = 600L,
            )
            publisher.recordUserAction("pause", null, emptyMap(), "S1", 10L, 0L, "pause")
            val t0 = System.nanoTime()
            publisher.close() // Must NOT hang
            val closeMs = (System.nanoTime() - t0) / 1_000_000L
            assertTrue(closeMs <= 1_800L, "Close must enforce shutdown budget against hanging server (took ${closeMs}ms)")
        }
    }

    @Test
    fun unreachableEndpointNeverBlocksStartupNeverThrowsAndShortCircuitsOnClose(@TempDir tempDir: Path) {
        val logFile = tempDir.resolve("latest.log")
        Files.writeString(logFile, "unreachable hub smoke line\n", StandardCharsets.UTF_8)

        val cfg = TelemetryConfig(
            enabled = true,
            endpoint = "http://127.0.0.1:1",
            ingestToken = "tok-unreachable",
            uploadLogOnExit = true,
        )
        val publisher = TelemetrySessionPublisher(
            config = cfg,
            logFile = logFile,
            flushIntervalMs = 5_000L,
            shutdownBudgetMs = 2_000L,
        )
        val startNanos = System.nanoTime()
        publisher.start()
        val startMs = (System.nanoTime() - startNanos) / 1_000_000L
        assertTrue(startMs < 200L, "start() must never block caller against unreachable telemetry hub (took ${startMs}ms)")

        // Give daemon worker a brief moment to hit connection refused and mark hub unreachable
        Thread.sleep(150L)
        publisher.recordUserAction("button", "buttons", mapOf("MENU" to true), "S1", 42L, 0L, "menu")

        val closeNanos = System.nanoTime()
        publisher.close()
        val closeMs = (System.nanoTime() - closeNanos) / 1_000_000L
        assertTrue(closeMs < 800L, "close() must short-circuit cleanly when hub is already unreachable (took ${closeMs}ms)")
        assertEquals(0L, publisher.sentEvents)

        val aliveTelemetry = Thread.getAllStackTraces().keys.filter { it.name == "fuji-telemetry" && it.isAlive }
        assertTrue(aliveTelemetry.isEmpty(), "Expected zero leaked fuji-telemetry daemon threads")
    }
}
