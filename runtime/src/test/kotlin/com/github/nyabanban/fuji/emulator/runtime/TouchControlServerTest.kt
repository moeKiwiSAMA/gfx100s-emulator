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

class TouchControlServerTest {
    private class StubControlClient(private val hasI2cBus: Boolean = true) : ControlClient {
        val patches = mutableListOf<Triple<String, Long, Map<String, Any>>>()
        private var revision = 3L

        override fun snapshot(): CompletableFuture<Map<String, Any?>> {
            val comps = if (hasI2cBus) {
                listOf(mapOf("id" to "i2c-bus", "revision" to revision, "config" to mapOf("touchEnabled" to false)))
            } else {
                emptyList()
            }
            return CompletableFuture.completedFuture(mapOf("components" to comps))
        }

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            patches += Triple(id, revision, changes)
            this.revision++
            return CompletableFuture.completedFuture(mapOf("id" to id, "revision" to this.revision, "config" to changes))
        }
    }

    private fun postTouch(
        port: Int,
        body: String,
        contentType: String = "application/json",
        origin: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/touch"))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (origin != null) builder.header("Origin", origin)
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun postApiTouchDispatchesValidatedCoordinatesToHandlerAndRejectsMalformedRequests() {
        val received = mutableListOf<Triple<Int, Int, Boolean>>()
        ControlServer(
            control = StubControlClient(),
            port = 0,
            touch = { x, y, pressed ->
                received += Triple(x, y, pressed)
                mapOf("ok" to true, "x" to x, "y" to y, "pressed" to pressed)
            },
        ).use { server ->
            val port = server.address.port

            val press = postTouch(port, """{"x":512,"y":384,"pressed":true}""")
            assertEquals(200, press.statusCode())
            val release = postTouch(port, """{"x":1023,"y":767,"pressed":false}""")
            assertEquals(200, release.statusCode())
            assertEquals(listOf(Triple(512, 384, true), Triple(1023, 767, false)), received)

            // Reject invalid payloads with 400
            for (badBody in listOf(
                """{"x":512,"y":384}""",
                """{"x":512,"y":384,"pressed":true,"extra":1}""",
                """{"x":-1,"y":10,"pressed":true}""",
                """{"x":65536,"y":10,"pressed":true}""",
                """{"x":10,"y":70000,"pressed":true}""",
                """{"x":12.5,"y":10,"pressed":true}""",
                """{"x":10,"y":10,"pressed":"true"}""",
            )) {
                assertEquals(400, postTouch(port, badBody).statusCode(), "Expected 400 for $badBody")
            }

            // Reject non-JSON content-type and cross-origin header
            assertEquals(400, postTouch(port, """{"x":1,"y":2,"pressed":true}""", contentType = "text/plain").statusCode())
            assertEquals(400, postTouch(port, """{"x":1,"y":2,"pressed":true}""", origin = "http://evil.example").statusCode())
            assertEquals(2, received.size)
        }
    }

    @Test
    fun postApiTouchFallsBackToControlClientPatchAndReturns404WhenI2cBusAbsent() {
        val stub = StubControlClient(hasI2cBus = true)
        ControlServer(control = stub, port = 0).use { server ->
            val res = postTouch(server.address.port, """{"x":200,"y":150,"pressed":true}""")
            assertEquals(200, res.statusCode())
            assertEquals(1, stub.patches.size)
            val (id, rev, changes) = stub.patches.single()
            assertEquals("i2c-bus", id)
            assertEquals(3L, rev)
            assertEquals(
                mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to 200L, "touchY" to 150L),
                changes,
            )
        }

        ControlServer(control = StubControlClient(hasI2cBus = false), port = 0).use { server ->
            val res = postTouch(server.address.port, """{"x":200,"y":150,"pressed":true}""")
            assertEquals(404, res.statusCode())
        }
    }

    @Test
    fun postApiTouchSwipeDispatchesDirectionalAndCoordinateSwipesAndRejectsInvalidPayloads() {
        val received = mutableListOf<Triple<Int, Int, Boolean>>()
        val http = HttpClient.newHttpClient()
        fun postSwipe(port: Int, body: String): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/touch/swipe"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        ControlServer(
            control = StubControlClient(hasI2cBus = true),
            port = 0,
            touch = { x, y, pressed ->
                received += Triple(x, y, pressed)
                mapOf("ok" to true, "component" to "rear-touch-ic", "x" to x, "y" to y, "pressed" to pressed, "revision" to 1L)
            },
        ).use { server ->
            val port = server.address.port
            val dirRes = postSwipe(port, """{"direction":"left","steps":2,"stepMs":5}""")
            assertEquals(200, dirRes.statusCode())
            val dirJson = Formats.map(Formats.json.readValue(dirRes.body(), Map::class.java))
            assertEquals("left", dirJson["direction"])
            assertEquals(
                listOf(
                    Triple(760, 384, true),
                    Triple(500, 384, true),
                    Triple(240, 384, true),
                    Triple(240, 384, false),
                ),
                received,
            )

            received.clear()
            val defaultDirRes = postSwipe(port, """{"direction":"right","stepMs":5}""")
            assertEquals(200, defaultDirRes.statusCode())
            assertEquals(
                listOf(
                    Triple(240, 384, true),
                    Triple(370, 384, true),
                    Triple(500, 384, true),
                    Triple(630, 384, true),
                    Triple(760, 384, true),
                    Triple(760, 384, false),
                ),
                received,
            )

            received.clear()
            val coordRes = postSwipe(port, """{"x1":512,"y1":560,"x2":512,"y2":180,"steps":2,"stepMs":5}""")
            assertEquals(200, coordRes.statusCode())
            assertEquals(
                listOf(
                    Triple(512, 560, true),
                    Triple(512, 370, true),
                    Triple(512, 180, true),
                    Triple(512, 180, false),
                ),
                received,
            )

            for (bad in listOf(
                """{}""",
                """{"direction":"diagonal"}""",
                """{"direction":"up","x1":0,"y1":0,"x2":10,"y2":10}""",
                """{"x1":0,"y1":0,"x2":10}""",
                """{"direction":"up","steps":9}""",
                """{"direction":"up","stepMs":1}""",
            )) {
                assertEquals(400, postSwipe(port, bad).statusCode(), "Expected 400 for $bad")
            }
        }

        ControlServer(control = StubControlClient(hasI2cBus = false), port = 0).use { server ->
            assertEquals(404, postSwipe(server.address.port, """{"direction":"up","stepMs":5}""").statusCode())
        }
    }

    @Test
    fun userActionLoggerFormatsTouchEventsConcise() {
        val lines = mutableListOf<String>()
        val logger = UserActionLogger(enabled = true, infoSink = { lines += it })
        val telemetry = FirmwareTelemetry().apply {
            virtualTick = 42L
            publishedFrames = 9L
            markStage(FirmwareStage.STAGE_6_INTERACTIVE_UI)
        }
        logger.logComponentPatch(
            "i2c-bus",
            mapOf("touchEnabled" to true, "touchPressed" to true, "touchX" to 512L, "touchY" to 384L),
            telemetry,
        )
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("[USER/ACTION] touch pressed=true x=512 y=384"))
    }

    @Test
    fun focusModeAndShootingModeEndpointsReadAndPatchGpioAdcAndButtons() {
        val stateById = mutableMapOf<String, MutableMap<String, Any?>>(
            "buttons" to mutableMapOf("movieMode" to false),
            "soc-gpio" to mutableMapOf("focusMode" to "default", "effectiveFocusMode" to "AF-S"),
            "soc-adc" to mutableMapOf("modeDial" to "P"),
        )
        val revs = mutableMapOf("buttons" to 1L, "soc-gpio" to 1L, "soc-adc" to 1L)
        val client = object : ControlClient {
            override fun snapshot(): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(
                    mapOf(
                        "components" to stateById.map { (id, st) ->
                            mapOf("id" to id, "revision" to revs.getValue(id), "state" to st.toMap(), "config" to st.toMap())
                        },
                    ),
                )

            override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
                val st = stateById.getValue(id)
                st.putAll(changes)
                if (id == "soc-gpio" && "focusMode" in changes) {
                    val fm = changes["focusMode"].toString()
                    st["effectiveFocusMode"] = if (fm == "default") "AF-S" else fm
                }
                val nextRev = revs.getValue(id) + 1L
                revs[id] = nextRev
                return CompletableFuture.completedFuture(mapOf("id" to id, "revision" to nextRev, "state" to st.toMap()))
            }
        }
        val http = HttpClient.newHttpClient()
        ControlServer(control = client, port = 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"
            val getFocus = http.send(HttpRequest.newBuilder(URI.create("$base/api/focus-mode")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, getFocus.statusCode())
            val focusJson = Formats.map(Formats.json.readValue(getFocus.body(), Map::class.java))
            assertEquals("AF-S", focusJson["effectiveFocusMode"])

            val postFocus = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/focus-mode"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"focusMode":"af-c"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postFocus.statusCode())
            assertEquals("AF-C", stateById.getValue("soc-gpio")["focusMode"])

            val postShooting = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/shooting-mode"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"mode":"MOVIE","modeDial":"M","focusMode":"MF"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postShooting.statusCode())
            assertEquals(true, stateById.getValue("buttons")["movieMode"])
            assertEquals("M", stateById.getValue("soc-adc")["modeDial"])
            assertEquals("MF", stateById.getValue("soc-gpio")["focusMode"])
        }
    }

    @Test fun themeEndpointGetsAndUpdatesBodyAndUiThemes() {
        var activeBody = "origin"
        var activeUi = "origin"
        val client = object : ControlClient {
            override fun snapshot(): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(mapOf("components" to emptyList<Any>()))
            override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
                CompletableFuture.completedFuture(mapOf("id" to id, "revision" to revision + 1))
        }
        val http = HttpClient.newHttpClient()
        ControlServer(
            control = client,
            port = 0,
            themeStatus = { mapOf("body" to activeBody, "ui" to activeUi) },
            themeUpdate = { req ->
                req["body"]?.let { activeBody = it.toString() }
                req["ui"]?.let { activeUi = it.toString() }
                mapOf("body" to activeBody, "ui" to activeUi)
            },
        ).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"
            val getResp = http.send(HttpRequest.newBuilder(URI.create("$base/api/theme")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, getResp.statusCode())
            assertEquals(mapOf("body" to "origin", "ui" to "origin"), Formats.map(Formats.json.readValue(getResp.body(), Map::class.java)))

            val postResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/theme"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"body":"hasselblad-x2d2","ui":"nordic-minimal"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postResp.statusCode())
            assertEquals("hasselblad-x2d2", activeBody)
            assertEquals("nordic-minimal", activeUi)

            val badResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/theme"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"unknown":"x"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, badResp.statusCode())
        }
    }
}
