package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EyeSensorControlServerTest {
    private class StubControlClient(private val hasI2cBus: Boolean = true) : ControlClient {
        val patches = mutableListOf<Triple<String, Long, Map<String, Any>>>()
        private var revision = 5L
        private var eyeNear = false

        override fun snapshot(): CompletableFuture<Map<String, Any?>> {
            val comps = if (hasI2cBus) {
                listOf(
                    mapOf(
                        "id" to "i2c-bus",
                        "revision" to revision,
                        "config" to mapOf("eyeSensorNear" to eyeNear),
                        "state" to mapOf("eyeSensorNear" to eyeNear),
                    ),
                )
            } else {
                emptyList()
            }
            return CompletableFuture.completedFuture(mapOf("components" to comps))
        }

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            patches += Triple(id, revision, changes)
            (changes["eyeSensorNear"] as? Boolean)?.let { eyeNear = it }
            this.revision++
            return CompletableFuture.completedFuture(mapOf("id" to id, "revision" to this.revision, "config" to changes))
        }
    }

    private fun get(port: Int, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        port: Int,
        path: String,
        body: String,
        contentType: String = "application/json",
        origin: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (origin != null) builder.header("Origin", origin)
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun eyeSensorAndViewModeEndpointsDispatchAndValidateStrictJson() {
        var nearState = false
        val eyeUpdates = mutableListOf<Boolean>()
        val viewModeUpdates = mutableListOf<Boolean>()
        ControlServer(
            control = StubControlClient(),
            port = 0,
            eyeSensorState = {
                mapOf(
                    "near" to nearState,
                    "activeViewfinder" to if (nearState) "EVF" else "LCD",
                    "lcdSequence" to 3L,
                    "evfSequence" to if (nearState) 1L else 0L,
                )
            },
            setEyeSensor = { near ->
                nearState = near
                eyeUpdates += near
                mapOf("ok" to true, "near" to near, "activeViewfinder" to if (near) "EVF" else "LCD")
            },
            setViewMode = { pressed ->
                viewModeUpdates += pressed
                mapOf("ok" to true, "pressed" to pressed, "gpio" to "7.6")
            },
        ).use { server ->
            val port = server.address.port

            val initial = get(port, "/api/eye-sensor")
            assertEquals(200, initial.statusCode())
            val initialJson = Formats.json.readTree(initial.body())
            assertEquals(false, initialJson.path("near").asBoolean())
            assertEquals("LCD", initialJson.path("activeViewfinder").asText())

            val setNear = post(port, "/api/eye-sensor", """{"near":true}""")
            assertEquals(200, setNear.statusCode())
            assertEquals("EVF", Formats.json.readTree(setNear.body()).path("activeViewfinder").asText())

            val setFar = post(port, "/api/eye-sensor", """{"near":false}""")
            assertEquals(200, setFar.statusCode())
            assertEquals(listOf(true, false), eyeUpdates)

            val vmPress = post(port, "/api/view-mode", """{"pressed":true}""")
            assertEquals(200, vmPress.statusCode())
            val vmRelease = post(port, "/api/view-mode", """{"pressed":false}""")
            assertEquals(200, vmRelease.statusCode())
            assertEquals(listOf(true, false), viewModeUpdates)

            // Reject malformed bodies, non-JSON content types, and cross-origin requests
            for (badEye in listOf("""{}""", """{"near":"true"}""", """{"near":true,"extra":1}""", """[]""")) {
                assertEquals(400, post(port, "/api/eye-sensor", badEye).statusCode(), "Expected 400 for $badEye")
            }
            for (badVm in listOf("""{}""", """{"pressed":1}""", """{"pressed":true,"extra":1}""")) {
                assertEquals(400, post(port, "/api/view-mode", badVm).statusCode(), "Expected 400 for $badVm")
            }
            assertEquals(400, post(port, "/api/eye-sensor", """{"near":true}""", contentType = "text/plain").statusCode())
            assertEquals(400, post(port, "/api/eye-sensor", """{"near":true}""", origin = "http://evil.example").statusCode())
            assertEquals(400, post(port, "/api/view-mode", """{"pressed":true}""", origin = "http://evil.example").statusCode())
            assertEquals(2, eyeUpdates.size)
            assertEquals(2, viewModeUpdates.size)
        }
    }

    @Test
    fun eyeSensorFallsBackToControlClientPatchAndHandlesMissingComponentsWith404() {
        val stub = StubControlClient(hasI2cBus = true)
        ControlServer(control = stub, port = 0).use { server ->
            val port = server.address.port
            val initial = get(port, "/api/eye-sensor")
            assertEquals(200, initial.statusCode())
            assertEquals(false, Formats.json.readTree(initial.body()).path("near").asBoolean())

            val patched = post(port, "/api/eye-sensor", """{"near":true}""")
            assertEquals(200, patched.statusCode())
            assertEquals(1, stub.patches.size)
            val (id, rev, changes) = stub.patches.single()
            assertEquals("i2c-bus", id)
            assertEquals(5L, rev)
            assertEquals(mapOf("eyeSensorNear" to true), changes)

            val after = get(port, "/api/eye-sensor")
            assertEquals(200, after.statusCode())
            assertEquals(true, Formats.json.readTree(after.body()).path("near").asBoolean())

            // Without setViewMode handler, /api/view-mode returns 404 cleanly
            assertEquals(404, post(port, "/api/view-mode", """{"pressed":true}""").statusCode())
        }

        ControlServer(control = StubControlClient(hasI2cBus = false), port = 0).use { server ->
            assertEquals(404, get(server.address.port, "/api/eye-sensor").statusCode())
            assertEquals(404, post(server.address.port, "/api/eye-sensor", """{"near":true}""").statusCode())
        }
    }

    @Test
    fun eyeSensorPrefersStandaloneTmd2772OverLegacyI2cBusWhenEnabled() {
        var tmdNear = true
        var tmdRev = 7L
        val patches = mutableListOf<Triple<String, Long, Map<String, Any>>>()
        val client = object : ControlClient {
            override fun snapshot(): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(
                mapOf(
                    "components" to listOf(
                        mapOf(
                            "id" to "i2c-bus",
                            "revision" to 1L,
                            "config" to mapOf("eyeSensorNear" to false),
                            "state" to mapOf("eyeSensorNear" to false),
                        ),
                        mapOf(
                            "id" to "tmd2772",
                            "revision" to tmdRev,
                            "config" to mapOf("enabled" to true, "eyeSensorNear" to tmdNear),
                            "state" to mapOf("enabled" to true, "eyeSensorNear" to tmdNear),
                        ),
                    ),
                ),
            )

            override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
                patches += Triple(id, revision, changes)
                if (id == "tmd2772") {
                    (changes["eyeSensorNear"] as? Boolean)?.let { tmdNear = it }
                    tmdRev++
                    return CompletableFuture.completedFuture(mapOf("id" to id, "revision" to tmdRev, "config" to changes))
                }
                return CompletableFuture.completedFuture(mapOf("id" to id, "revision" to revision + 1L, "config" to changes))
            }
        }
        ControlServer(control = client, port = 0).use { server ->
            val port = server.address.port
            val initial = get(port, "/api/eye-sensor")
            assertEquals(200, initial.statusCode())
            val initJson = Formats.json.readTree(initial.body())
            assertEquals("tmd2772", initJson.path("component").asText())
            assertEquals(true, initJson.path("near").asBoolean())

            val setFar = post(port, "/api/eye-sensor", """{"near":false}""")
            assertEquals(200, setFar.statusCode())
            val farJson = Formats.json.readTree(setFar.body())
            assertEquals("tmd2772", farJson.path("component").asText())
            assertEquals(false, farJson.path("near").asBoolean())
            assertEquals(listOf(Triple("tmd2772", 7L, mapOf<String, Any>("eyeSensorNear" to false))), patches)
        }
    }

    @Test
    fun userActionLoggerFormatsViewModePressAndReleaseConcisely() {
        val lines = mutableListOf<String>()
        val logger = UserActionLogger(enabled = true, infoSink = { lines += it })
        val telemetry = FirmwareTelemetry().apply {
            virtualTick = 1234L
            publishedFrames = 7L
            markStage(FirmwareStage.STAGE_6_INTERACTIVE_UI)
        }
        logger.logViewMode(true, telemetry)
        logger.logViewMode(false, telemetry)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("[USER/ACTION] viewMode pressed=true gpio=7.6"))
        assertTrue(lines[1].contains("[USER/ACTION] viewMode pressed=false gpio=7.6"))
    }
}
