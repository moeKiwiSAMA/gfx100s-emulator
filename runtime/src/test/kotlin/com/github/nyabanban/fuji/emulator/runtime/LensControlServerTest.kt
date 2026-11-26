package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.api.FujiLensDatDecoder
import com.github.nyabanban.fuji.emulator.api.LensControlContract
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LensControlServerTest {
    private class StubLensClient : ControlClient {
        private var rev = 1L
        private var ringMode = "A"
        private var aperture = 4.0
        private var focalLengthMm = 45.0
        private var focusDistanceM = 2.5
        private var oisEnabled = true

        private fun lensState(): Map<String, Any?> = linkedMapOf(
            "template" to "gf45-100",
            "model" to "GF45-100mmF4 R LM OIS WR",
            "communicationMode" to "GENERIC_SIMULATED",
            "electronicContacts" to true,
            "apertureRingMode" to ringMode,
            "aperture" to aperture,
            "irisStepIndex" to 0,
            "apertureRingStepIndex" to 0,
            "bodyIrisSynced" to true,
            "bodyCommandedAperture" to aperture,
            "minAperture" to 4.0,
            "maxAperture" to 32.0,
            "apertureStops" to listOf(4.0, 5.6, 8.0, 11.0),
            "focalLengthMm" to focalLengthMm,
            "minFocalLengthMm" to 45.0,
            "maxFocalLengthMm" to 100.0,
            "focusDistanceM" to focusDistanceM,
            "minFocusDistanceM" to 0.65,
            "focusMode" to "AF-S",
            "oisEnabled" to oisEnabled,
            "opticsEnabled" to true,
        )

        override fun snapshot(): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(
            mapOf(
                "components" to listOf(
                    mapOf("id" to "lens", "revision" to rev, "state" to lensState()),
                    mapOf(
                        "id" to "mount",
                        "revision" to 1L,
                        "state" to mapOf(
                            "attached" to true,
                            "protocolProfile" to "gf-lenscom-frame32-experimental-v1",
                        ),
                    ),
                ),
            ),
        )

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            require(id == "lens" && revision == rev)
            changes["apertureRingMode"]?.let { ringMode = it.toString().uppercase() }
            changes["aperture"]?.let { aperture = (it as Number).toDouble() }
            changes["focalLengthMm"]?.let { focalLengthMm = (it as Number).toDouble() }
            changes["focusDistanceM"]?.let { focusDistanceM = (it as Number).toDouble() }
            changes["oisEnabled"]?.let { oisEnabled = it as Boolean }
            rev++
            return CompletableFuture.completedFuture(
                mapOf("id" to "lens", "revision" to rev, "state" to lensState()),
            )
        }
    }

    @Test
    fun lensGetAndPostRoutesExposeAndPatchTwoWayLensSyncState() {
        val http = HttpClient.newHttpClient()
        ControlServer(control = StubLensClient(), port = 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"
            val getResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lens")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getResp.statusCode())
            val initial = Formats.json.readTree(getResp.body())
            assertEquals("A", initial.get("apertureRingMode").asText())
            assertEquals(true, initial.get("bodyIrisSynced").asBoolean())
            assertEquals(true, initial.get("mountAttached").asBoolean())

            val postResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lens"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            """{"apertureRingMode":"MANUAL","aperture":8.0,"focalLengthMm":70.0,"oisEnabled":false}""",
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postResp.statusCode())
            val updated = Formats.json.readTree(postResp.body())
            assertEquals("MANUAL", updated.get("apertureRingMode").asText())
            assertEquals(8.0, updated.get("aperture").asDouble())
            assertEquals(70.0, updated.get("focalLengthMm").asDouble())
            assertEquals(false, updated.get("oisEnabled").asBoolean())

            val badResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lens"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"apertureRingMode":"INVALID"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, badResp.statusCode())
            assertTrue(badResp.body().contains("apertureRingMode"))
        }
    }

    @Test
    fun missingRealFirmwareLensArtifactReturnsHttp400Not500() {
        val tempRoot = Files.createTempDirectory("lens-real-fw-400-")
        try {
            val realFwClient = object : ControlClient {
                override fun snapshot(): CompletableFuture<Map<String, Any?>> =
                    CompletableFuture.completedFuture(mapOf("components" to emptyList<Any>()))

                override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
                    CompletableFuture.supplyAsync {
                        if (changes["communicationMode"] == "REAL_FIRMWARE") {
                            val file = changes["realFirmwareFile"]?.toString() ?: "MISSING.DAT"
                            FujiLensDatDecoder.loadVerified(
                                LensControlContract.RealFirmware(
                                    expectedFirmwareFile = file,
                                    firmwareFormat = LensControlContract.RealFirmware.FORMAT_FUJI_TYPE4_DAT,
                                ),
                                tempRoot,
                            )
                        }
                        mapOf("id" to id, "revision" to (revision + 1L), "state" to emptyMap<String, Any?>())
                    }
            }
            val http = HttpClient.newHttpClient()
            ControlServer(control = realFwClient, port = 0).use { server ->
                val base = "http://127.0.0.1:${server.address.port}"
                val resp = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/v1/components/lens"))
                        .header("Content-Type", "application/json")
                        .method(
                            "PATCH",
                            HttpRequest.BodyPublishers.ofString(
                                """{"revision":1,"changes":{"communicationMode":"REAL_FIRMWARE","realFirmwareFile":"MISSING_LENS.DAT"}}""",
                            ),
                        )
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(400, resp.statusCode(), "Missing REAL_FIRMWARE artifact must return HTTP 400 (got ${resp.statusCode()}: ${resp.body()})")
                assertTrue(resp.body().contains("REAL_FIRMWARE"))
            }
        } finally {
            Files.walk(tempRoot).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
