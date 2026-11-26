package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlServerOpticalFilterApiTest {
    private class FakeFilterControl : ControlClient {
        var revision = 1L
        val state = linkedMapOf<String, Any?>(
            "enabled" to true,
            "ndPreset" to "OFF",
            "ndStops" to 0.0,
            "bokehMask" to "OFF",
            "bokehMaskAngleDeg" to 0.0,
            "starEnabled" to false,
            "starPoints" to 6L,
            "starStrength" to 0.75,
            "mistEnabled" to false,
            "mistPreset" to "OFF",
            "mistStrength" to 0.0,
            "gndEnabled" to false,
            "gndStops" to 0.0,
        )

        override fun snapshot(): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(
            mapOf(
                "components" to listOf(
                    mapOf(
                        "id" to "optical-filter",
                        "revision" to revision,
                        "config" to state.toMap(),
                        "state" to state.toMap(),
                    ),
                ),
            ),
        )

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            require(id == "optical-filter") { "unexpected component $id" }
            if (revision != this.revision) return CompletableFuture.failedFuture(RevisionConflict())
            this.revision++
            state.putAll(changes)
            val stops = NdFilterSpec.clampStops((state["ndStops"] as? Number)?.toDouble() ?: 0.0)
            state["ndStops"] = stops
            return CompletableFuture.completedFuture(
                mapOf("id" to id, "revision" to this.revision, "config" to state.toMap(), "state" to state.toMap()),
            )
        }
    }

    @Test
    fun `api filters and api nd round-trip presets bokeh star mist gnd clear and reject invalid`() {
        val fake = FakeFilterControl()
        val client = HttpClient.newHttpClient()
        ControlServer(fake, 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"

            // GET /api/filters
            val get0 = client.send(HttpRequest.newBuilder(URI("$base/api/filters")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, get0.statusCode())
            val get0Map = Formats.map(Formats.json.readValue(get0.body(), Map::class.java))
            assertEquals("optical-filter", get0Map["component"])

            // POST /api/filters with stacked ND8 + STAR_5 bokeh + 6pt star + mist 1/4
            val postStack = client.send(
                HttpRequest.newBuilder(URI("$base/api/filters"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"nd":{"preset":"ND8"},"bokeh":{"shape":"star","angleDeg":12},"star":{"enabled":true,"points":6},"mist":{"enabled":true,"preset":"1/4"}}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postStack.statusCode())
            val stackMap = Formats.map(Formats.json.readValue(postStack.body(), Map::class.java))
            assertEquals("ND8", stackMap["ndPreset"])
            assertEquals(3.0, (stackMap["ndStops"] as Number).toDouble(), 1e-6)
            assertEquals("STAR_5", stackMap["bokehMask"])
            assertEquals(true, stackMap["starEnabled"])
            assertEquals(true, stackMap["mistEnabled"])

            // GET /api/nd reflects ND8
            val getNd = client.send(HttpRequest.newBuilder(URI("$base/api/nd")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, getNd.statusCode())
            val ndMap = Formats.map(Formats.json.readValue(getNd.body(), Map::class.java))
            assertEquals(3.0, (ndMap["stops"] as Number).toDouble(), 1e-6)
            assertEquals(0.125, (ndMap["transmittance"] as Number).toDouble(), 1e-6)

            // POST /api/nd continuous stops + invalid stops -> 400
            val postNd = client.send(
                HttpRequest.newBuilder(URI("$base/api/nd"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"stops":2.5}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postNd.statusCode())
            assertEquals(2.5, (Formats.map(Formats.json.readValue(postNd.body(), Map::class.java))["stops"] as Number).toDouble(), 1e-6)

            val badNd = client.send(
                HttpRequest.newBuilder(URI("$base/api/nd"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"stops":99.0}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, badNd.statusCode())

            // POST /api/filters clear: true resets everything
            val clearRes = client.send(
                HttpRequest.newBuilder(URI("$base/api/filters"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"clear":true}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, clearRes.statusCode())
            val cleared = Formats.map(Formats.json.readValue(clearRes.body(), Map::class.java))
            assertEquals("OFF", cleared["ndPreset"])
            assertEquals("OFF", cleared["bokehMask"])
            assertEquals(false, cleared["starEnabled"])
            assertEquals("OFF", cleared["uvPreset"])
            assertEquals("", cleared["slots"])

            // POST /api/filters with UV preset shorthand + ordered multi-slot array
            val postUv = client.send(
                HttpRequest.newBuilder(URI("$base/api/filters"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"uv":{"preset":"cheap","veilingGlare":0.2}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postUv.statusCode())
            val uvMap = Formats.map(Formats.json.readValue(postUv.body(), Map::class.java))
            assertEquals("CHEAP_POOR", uvMap["uvPreset"])
            assertEquals(0.2, (uvMap["uvVeilingGlare"] as Number).toDouble(), 1e-6)

            val postSlots = client.send(
                HttpRequest.newBuilder(URI("$base/api/filters"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"slots":[{"type":"uv","enabled":true,"preset":"CHEAP_POOR"},{"type":"nd","enabled":true,"preset":"ND8","stops":3.0},{"type":"nd","enabled":true,"preset":"ND64","stops":6.0}]}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postSlots.statusCode())
            val slotsMap = Formats.map(Formats.json.readValue(postSlots.body(), Map::class.java))
            val parsedSlots = OpticalFilterSlotCodec.parse(slotsMap["slots"] as? String)
            assertEquals(3, parsedSlots.size)
            assertEquals("uv", parsedSlots[0].type)
            assertEquals("nd", parsedSlots[1].type)
            assertEquals("nd", parsedSlots[2].type)
        }
    }
}
