package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class AdcAndDumpControlServerTest {
    private class StubAdcControlClient(private val hasAdc: Boolean = true) : ControlClient {
        val patches = mutableListOf<Triple<String, Long, Map<String, Any>>>()
        private var revision = 3L
        private val liveState = linkedMapOf<String, Any?>(
            "qButton" to false,
            "aelButton" to false,
            "modeDial" to "default",
            "fn1Button" to false,
            "fn2Button" to false,
            "fn3Button" to false,
            "lightButton" to false,
            "rearDialPosition" to -1L,
            "rearDialDetents" to 0L,
            "ch3Override" to -1L,
            "ch5Override" to -1L,
            "ch6Override" to -1L,
            "ch7Override" to -1L,
            "ch11Override" to -1L,
        )

        override fun snapshot(): CompletableFuture<Map<String, Any?>> {
            val comps = if (hasAdc) {
                listOf(
                    mapOf(
                        "id" to "soc-adc",
                        "revision" to revision,
                        "config" to liveState.toMap(),
                        "state" to (liveState + mapOf(
                            "registers" to mapOf("samples" to listOf(0, 257, 514, 771, 1028, 1285, 1542, 1799, 2056, 4000, 2570, 2827)),
                            "eventCount" to 12L,
                            "sampleReadCount" to 24L,
                        )),
                    ),
                )
            } else {
                emptyList()
            }
            return CompletableFuture.completedFuture(mapOf("components" to comps))
        }

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            patches += Triple(id, revision, changes)
            liveState.putAll(changes)
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
    fun dumpEndpointAcceptsEmptyBodyAndEmptyJsonObjectWhileRejectingInvalidPayloads() {
        var dumpCalls = 0
        ControlServer(
            control = StubAdcControlClient(),
            port = 0,
            dump = { "dump-${++dumpCalls}.zip" },
        ).use { server ->
            val port = server.address.port

            val emptyResp = post(port, "/api/v1/dump", "")
            assertEquals(201, emptyResp.statusCode())
            assertEquals("dump-1.zip", Formats.json.readTree(emptyResp.body()).path("dump").asText())

            val wsResp = post(port, "/api/v1/dump", "  \n ")
            assertEquals(201, wsResp.statusCode())
            assertEquals("dump-2.zip", Formats.json.readTree(wsResp.body()).path("dump").asText())

            val emptyObjResp = post(port, "/api/v1/dump", "{}")
            assertEquals(201, emptyObjResp.statusCode())
            assertEquals("dump-3.zip", Formats.json.readTree(emptyObjResp.body()).path("dump").asText())

            val spacedObjResp = post(port, "/api/v1/dump", " { } ")
            assertEquals(201, spacedObjResp.statusCode())
            assertEquals("dump-4.zip", Formats.json.readTree(spacedObjResp.body()).path("dump").asText())

            assertEquals(400, post(port, "/api/v1/dump", """{"extra":1}""").statusCode())
            assertEquals(400, post(port, "/api/v1/dump", "[]").statusCode())
            assertEquals(400, post(port, "/api/v1/dump", "not-json").statusCode())
            assertEquals(400, post(port, "/api/v1/dump", "{}", contentType = "text/plain").statusCode())
            assertEquals(400, post(port, "/api/v1/dump", "{}", origin = "http://evil.example").statusCode())
            assertEquals(4, dumpCalls)
        }
    }

    @Test
    fun adcEndpointInspectsAndDispatchesLiveControlsAndRearDialSteps() {
        val stub = StubAdcControlClient()
        ControlServer(control = stub, port = 0).use { server ->
            val port = server.address.port

            val initial = get(port, "/api/adc")
            assertEquals(200, initial.statusCode())
            val initialJson = Formats.json.readTree(initial.body())
            assertEquals(false, initialJson.path("controls").path("qButton").asBoolean())
            assertEquals("default", initialJson.path("controls").path("modeDial").asText())
            assertEquals(12L, initialJson.path("eventCount").asLong())

            val qPress = post(port, "/api/adc", """{"qButton":true,"modeDial":"M"}""")
            assertEquals(200, qPress.statusCode())
            assertEquals(4L, Formats.json.readTree(qPress.body()).path("revision").asLong())

            val stepCw = post(port, "/api/adc", """{"rearDialStep":2}""")
            assertEquals(200, stepCw.statusCode())
            assertEquals(2L, stub.patches.last().third["rearDialDetents"])
            assertEquals(-1L, stub.patches.last().third["rearDialPosition"])

            val stepCcw = post(port, "/api/adc", """{"rearDialStep":-1}""")
            assertEquals(200, stepCcw.statusCode())
            assertEquals(1L, stub.patches.last().third["rearDialDetents"])

            // Validation failures
            for (bad in listOf("""{}""", """{"unknown":true}""", """{"qButton":"yes"}""", """{"ch3Override":5000}""")) {
                assertEquals(400, post(port, "/api/adc", bad).statusCode(), "Expected 400 for $bad")
            }
            assertEquals(400, post(port, "/api/adc", """{"qButton":true}""", contentType = "text/plain").statusCode())
            assertEquals(400, post(port, "/api/adc", """{"qButton":true}""", origin = "http://evil.example").statusCode())
        }

        // 404 when soc-adc is unavailable
        ControlServer(control = StubAdcControlClient(hasAdc = false), port = 0).use { server ->
            val port = server.address.port
            assertEquals(404, get(port, "/api/adc").statusCode())
            assertEquals(404, post(port, "/api/adc", """{"qButton":true}""").statusCode())
        }
    }
}
