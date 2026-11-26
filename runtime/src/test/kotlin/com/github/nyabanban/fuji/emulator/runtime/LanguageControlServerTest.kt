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

class LanguageControlServerTest {
    private class MinimalControlClient : ControlClient {
        override fun snapshot(): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(mapOf("tick" to 0L, "components" to emptyList<Any>()))

        override fun patch(id: String, revision: Long, changes: Map<String, Any>): CompletableFuture<Map<String, Any?>> =
            CompletableFuture.completedFuture(emptyMap())
    }

    @Test
    fun getAndPostApiLangSwitchSupportedLocalesAndRejectInvalidCodes() {
        val http = HttpClient.newHttpClient()
        ControlServer(control = MinimalControlClient(), port = 0).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"

            // Default language is English ("en")
            val getDefault = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getDefault.statusCode())
            val defJson = Formats.map(Formats.json.readValue(getDefault.body(), Map::class.java))
            assertEquals("en", defJson["language"])
            assertEquals(listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES"), defJson["supported"])

            // Switch to zh_CN (accepts zh-CN alias cleanly)
            val postZh = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"language":"zh-CN"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postZh.statusCode(), postZh.body())
            val zhJson = Formats.map(Formats.json.readValue(postZh.body(), Map::class.java))
            assertEquals(true, zhJson["ok"])
            assertEquals("zh_CN", zhJson["language"])

            val getAfterZh = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getAfterZh.statusCode())
            assertEquals("zh_CN", Formats.map(Formats.json.readValue(getAfterZh.body(), Map::class.java))["language"])

            // Switch across all remaining built-in languages (ja_JP, de_DE, fr_FR, es_ES)
            for ((input, expected) in listOf("ja-JP" to "ja_JP", "de" to "de_DE", "fr-FR" to "fr_FR", "es" to "es_ES")) {
                val postLoc = http.send(
                    HttpRequest.newBuilder(URI.create("$base/api/lang"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"language":"$input"}"""))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(200, postLoc.statusCode(), postLoc.body())
                assertEquals(expected, Formats.map(Formats.json.readValue(postLoc.body(), Map::class.java))["language"])
            }

            // Switch back to en using alias key "lang"
            val postEn = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"lang":"en"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, postEn.statusCode())
            assertEquals("en", Formats.map(Formats.json.readValue(postEn.body(), Map::class.java))["language"])

            // Unsupported language code returns 400
            val badLang = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"language":"ko-KR"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, badLang.statusCode())
            assertTrue(badLang.body().contains("Unsupported language"))

            // Invalid payload key returns 400
            val badKey = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"locale":"en"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, badKey.statusCode())
        }
    }

    @Test
    fun injectedLanguageCallbacksForwardToDesktopI18nAndProfileHook() {
        var current = "en"
        val savedEvents = mutableListOf<String>()
        val http = HttpClient.newHttpClient()
        ControlServer(
            control = MinimalControlClient(),
            port = 0,
            languageStatus = { mapOf("language" to current, "supported" to listOf("en", "zh_CN")) },
            setLanguage = { raw ->
                require(raw == "en" || raw == "zh_CN") { "Unsupported language '$raw'" }
                current = raw
                savedEvents += raw
                mapOf("ok" to true, "language" to current, "supported" to listOf("en", "zh_CN"))
            },
        ).use { server ->
            val base = "http://127.0.0.1:${server.address.port}"
            val res = http.send(
                HttpRequest.newBuilder(URI.create("$base/api/lang"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"language":"zh_CN"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode())
            assertEquals("zh_CN", current)
            assertEquals(listOf("zh_CN"), savedEvents)
        }
    }
}
