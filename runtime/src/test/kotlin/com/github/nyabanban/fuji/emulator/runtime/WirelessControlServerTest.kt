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

class WirelessControlServerTest {
    private class StubWirelessClient : ControlClient {
        private var rev = 1L
        private var deviceName = "FujiEmu Host"
        private var bleConnected = false
        private var wifiConnected = false

        override fun snapshot(): CompletableFuture<Map<String, Any?>> = CompletableFuture.completedFuture(
            mapOf(
                "components" to listOf(
                    mapOf(
                        "id" to "bcm43438",
                        "revision" to rev,
                        "state" to mapOf(
                            "enabled" to true,
                            "deviceName" to deviceName,
                            "connected" to bleConnected,
                            "connectedClients" to if (wifiConnected) 1 else 0,
                        ),
                    ),
                ),
            ),
        )

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> {
            require(id == "bcm43438" && revision == rev)
            changes["deviceName"]?.let { deviceName = it.toString() }
            changes["blePeerConnected"]?.let { bleConnected = it as Boolean }
            changes["wifiPeerConnected"]?.let { wifiConnected = it as Boolean }
            rev++
            return CompletableFuture.completedFuture(
                mapOf(
                    "id" to "bcm43438",
                    "revision" to rev,
                    "state" to mapOf(
                        "enabled" to true,
                        "deviceName" to deviceName,
                        "connected" to bleConnected,
                        "connectedClients" to if (wifiConnected) 1 else 0,
                    ),
                ),
            )
        }
    }

    @Test
    fun wirelessGetAndPostRoutesExposeAndControlBcm43438PeerState() {
        val http = HttpClient.newHttpClient()
        ControlServer(control = StubWirelessClient(), port = 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"
            val getResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/wireless")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getResp.statusCode())
            assertTrue(getResp.body().contains("FujiEmu Host"))

            val postResp = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/wireless"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"blePeerConnected":true,"wifiPeerConnected":true}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postResp.statusCode())
            val parsed = Formats.json.readTree(postResp.body())
            assertEquals(true, parsed.get("connected").asBoolean())
            assertEquals(1, parsed.get("connectedClients").asInt())
        }
    }
}
