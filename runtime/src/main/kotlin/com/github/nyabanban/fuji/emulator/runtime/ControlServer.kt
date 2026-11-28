package com.github.nyabanban.fuji.emulator.runtime

import com.github.nyabanban.fuji.emulator.api.BokehMaskShape
import com.github.nyabanban.fuji.emulator.api.ControlClient
import com.github.nyabanban.fuji.emulator.api.NdFilterPreset
import com.github.nyabanban.fuji.emulator.api.NdFilterSpec
import com.github.nyabanban.fuji.emulator.api.NpW235BatteryCurve
import com.github.nyabanban.fuji.emulator.api.OpticalFilterSlotCodec
import com.github.nyabanban.fuji.emulator.api.UvFilterPreset
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Loopback API; browser writes require JSON and do not receive CORS permission. */
class ControlServer(
    private val control: ControlClient, port: Int,
    private val machine: () -> Map<String, Any?> = { emptyMap() },
    private val dump: () -> String = { error("dump unavailable") },
    private val addressQuery: (Map<String,String>) -> Map<String,Any?> = { error("address dictionary unavailable") },
    private val profiler: AsyncPerfProfiler? = null,
    private val touch: ((x: Int, y: Int, pressed: Boolean) -> Map<String, Any?>)? = null,
    private val touchSwipe: ((x1: Int, y1: Int, x2: Int, y2: Int, steps: Int, stepMs: Long) -> Map<String, Any?>)? = null,
    private val telemetryUpload: ((String?) -> Map<String, Any?>)? = null,
    private val eyeSensorState: (() -> Map<String, Any?>)? = null,
    private val setEyeSensor: ((near: Boolean) -> Map<String, Any?>)? = null,
    private val setViewMode: ((pressed: Boolean) -> Map<String, Any?>)? = null,
    private val audioStatus: (() -> Map<String, Any?>)? = null,
    private val audioUpdate: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    private val audioDevices: (() -> Map<String, Any?>)? = null,
    private val wirelessStatus: (() -> Map<String, Any?>)? = null,
    private val wirelessAction: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    private val languageStatus: (() -> Map<String, Any?>)? = null,
    private val setLanguage: ((String) -> Map<String, Any?>)? = null,
    private val themeStatus: (() -> Map<String, Any?>)? = null,
    private val themeUpdate: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    private val themeReload: (() -> Map<String, Any?>)? = null,
    private val flashStatus: (() -> Map<String, Any?>)? = null,
    private val flashAction: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
    private val batteryStatus: (() -> Map<String, Any?>)? = null,
    private val batteryAction: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
) : AutoCloseable {
    @Volatile
    private var fallbackLanguage: String = "en"
    private val FALLBACK_SUPPORTED_LANGUAGES: List<String> = listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES")
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 32)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    val address get() = server.address
    init {
        server.executor = executor
        server.createContext("/") { request ->
            try { handle(request) }
            catch (error: Throwable) {
                val cause = generateSequence(error) { if (it is CompletionException || it is java.util.concurrent.ExecutionException) it.cause else null }.last()
                val code = when (cause) { is RevisionConflict -> 409; is IllegalArgumentException -> 400; is RejectedExecutionException -> 503; else -> 500 }
                respond(request, code, mapOf("error" to (cause.message ?: cause.javaClass.simpleName)))
            } finally { request.close() }
        }
        server.start()
    }
    private fun handle(request: HttpExchange) {
        val origin = request.requestHeaders.getFirst("Origin")
        if (origin != null) {
            val port = address.port
            val sameOrigin = origin == "http://127.0.0.1:$port" || origin == "http://localhost:$port"
            require(sameOrigin) { "cross-origin control denied" }
        }
        val path = request.requestURI.path
        when {
            path == "/api/v1/catalog" && request.requestMethod == "GET" -> {
                val fields=request.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
                    val parts=pair.split('=',limit=2); require(parts.size==2)
                    java.net.URLDecoder.decode(parts[0],Charsets.UTF_8) to java.net.URLDecoder.decode(parts[1],Charsets.UTF_8)
                }
                require(fields.size<=16 && fields.map { it.first }.distinct().size==fields.size) { "Duplicate or excessive query parameters" }
                respond(request,200,addressQuery(fields.toMap()))
            }
            path == "/api/v1/state" && request.requestMethod == "GET" -> respond(request, 200, control.snapshot().join() + mapOf("machine" to machine()))
            path == "/api/v1/dump" && request.requestMethod == "POST" -> {
                requireJson(request)
                val rawBody = readBody(request)
                if (rawBody.isNotEmpty()) {
                    val trimmed = String(rawBody, Charsets.UTF_8).trim()
                    if (trimmed.isNotEmpty()) {
                        val body = readJsonObject(rawBody)
                        require(body.isEmpty()) { "dump body must be empty or {}" }
                    }
                }
                respond(request, 201, mapOf("dump" to dump()))
            }
            path.matches(Regex("/api/v1/components/[a-z][a-z0-9-]{0,63}")) && request.requestMethod == "PATCH" -> {
                requireJson(request)
                val body = Formats.map(Formats.json.readValue(readBody(request), Map::class.java))
                require(body.keys == setOf("revision", "changes"))
                val revision = body["revision"]
                require(revision is Number && revision.toDouble() == revision.toLong().toDouble() && revision.toLong() >= 0)
                respond(request, 200, control.patch(path.substringAfterLast('/'), revision.toLong(), Formats.map(body["changes"])).join())
            }
            path == "/api/profiler/start" && request.requestMethod == "POST" -> {
                val activeProfiler = profiler ?: return respond(request, 404, mapOf("error" to "profiler unavailable"))
                val query = parseQueryParams(request.requestURI.rawQuery)
                val rawBody = readBody(request)
                var intervalMs = AsyncPerfProfiler.DEFAULT_SAMPLE_INTERVAL_MS
                query["intervalMs"]?.let { intervalMs = it.toLongOrNull() ?: throw IllegalArgumentException("invalid intervalMs") }
                if (rawBody.isNotEmpty()) {
                    requireJson(request)
                    val body = Formats.map(Formats.json.readValue(rawBody, Map::class.java))
                    body["intervalMs"]?.let { raw ->
                        require(raw is Number && raw.toDouble() == raw.toLong().toDouble()) { "intervalMs must be integer" }
                        intervalMs = raw.toLong()
                    }
                }
                respond(request, 200, activeProfiler.start(intervalMs))
            }
            path == "/api/profiler/stop" && request.requestMethod == "POST" -> {
                val activeProfiler = profiler ?: return respond(request, 404, mapOf("error" to "profiler unavailable"))
                val query = parseQueryParams(request.requestURI.rawQuery)
                val report = activeProfiler.stop()
                if (wantsText(request, query)) respondText(request, 200, activeProfiler.formatTextReport())
                else respond(request, 200, report)
            }
            path == "/api/profiler/report" && request.requestMethod == "GET" -> {
                val activeProfiler = profiler ?: return respond(request, 404, mapOf("error" to "profiler unavailable"))
                val query = parseQueryParams(request.requestURI.rawQuery)
                if (wantsText(request, query)) respondText(request, 200, activeProfiler.formatTextReport())
                else respond(request, 200, activeProfiler.reportMap())
            }
            path == "/api/touch" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = Formats.map(Formats.json.readValue(readBody(request), Map::class.java))
                require(body.keys == setOf("x", "y", "pressed")) { "touch payload requires exact keys: x, y, pressed" }
                val x = requireExactInt(body["x"], "x", 0..65535)
                val y = requireExactInt(body["y"], "y", 0..65535)
                val pressed = body["pressed"] as? Boolean ?: throw IllegalArgumentException("pressed must be boolean")
                val result = touch?.invoke(x, y, pressed) ?: dispatchTouchViaControl(x, y, pressed)
                if (result == null) respond(request, 404, mapOf("error" to "i2c-bus touch target unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/touch/swipe" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val allowedKeys = setOf("direction", "x1", "y1", "x2", "y2", "steps", "stepMs")
                require(body.keys.isNotEmpty() && body.keys.all { it in allowedKeys }) {
                    "touch swipe payload accepts direction or x1/y1/x2/y2 (optional steps, stepMs)"
                }
                // DAT s4:0x0124ef18..0x0124fa98 & s5:0x02061e6c / 0x02092120:
                // 4 steps x 20ms (=100ms virtual total) stays well under LiveView T-Fn's 260ms flick window
                // while leaving >=390px post-m1 drag travel (>1/3 screen) for Playback slide-commit (0x0300).
                val steps = body["steps"]?.let { requireExactInt(it, "steps", 1..8) } ?: 4
                val stepMs = body["stepMs"]?.let { requireExactInt(it, "stepMs", 5..100).toLong() } ?: 20L
                val dirRaw = body["direction"]
                val x1: Int
                val y1: Int
                val x2: Int
                val y2: Int
                val directionLabel: String?
                if (dirRaw != null) {
                    require("x1" !in body && "y1" !in body && "x2" !in body && "y2" !in body) {
                        "touch swipe payload cannot mix direction with x1/y1/x2/y2"
                    }
                    val dir = (dirRaw as? String)?.trim()?.lowercase()
                        ?: throw IllegalArgumentException("direction must be one of: up, down, left, right")
                    val preset = when (dir) {
                        "up" -> intArrayOf(512, 560, 512, 180)
                        "down" -> intArrayOf(512, 180, 512, 560)
                        "left" -> intArrayOf(760, 384, 240, 384)
                        "right" -> intArrayOf(240, 384, 760, 384)
                        else -> throw IllegalArgumentException("direction must be one of: up, down, left, right")
                    }
                    x1 = preset[0]; y1 = preset[1]; x2 = preset[2]; y2 = preset[3]
                    directionLabel = dir
                } else {
                    require(setOf("x1", "y1", "x2", "y2").all { it in body }) {
                        "touch swipe payload requires direction or all of x1, y1, x2, y2"
                    }
                    x1 = requireExactInt(body["x1"], "x1", 0..65535)
                    y1 = requireExactInt(body["y1"], "y1", 0..65535)
                    x2 = requireExactInt(body["x2"], "x2", 0..65535)
                    y2 = requireExactInt(body["y2"], "y2", 0..65535)
                    directionLabel = null
                }
                val result = touchSwipe?.invoke(x1, y1, x2, y2, steps, stepMs)
                    ?: dispatchTouchSwipeViaControl(x1, y1, x2, y2, steps, stepMs)
                if (result == null) {
                    respond(request, 404, mapOf("error" to "i2c-bus touch target unavailable"))
                } else {
                    val out = LinkedHashMap(result)
                    if (directionLabel != null) out["direction"] = directionLabel
                    respond(request, 200, out)
                }
            }
            path == "/api/eye-sensor" && request.requestMethod == "GET" -> {
                val result = eyeSensorState?.invoke() ?: inspectEyeSensorViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "i2c-bus eye sensor unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/eye-sensor" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                require(body.keys == setOf("near")) { "eye-sensor payload requires exact key: near" }
                val near = body["near"] as? Boolean ?: throw IllegalArgumentException("near must be boolean")
                val result = setEyeSensor?.invoke(near) ?: dispatchEyeSensorViaControl(near)
                if (result == null) respond(request, 404, mapOf("error" to "i2c-bus eye sensor unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/adc" && request.requestMethod == "GET" -> {
                val result = inspectAdcViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "soc-adc unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/adc" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchAdcViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "soc-adc unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/focus-mode" && request.requestMethod == "GET" -> {
                val result = inspectFocusModeViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "soc-gpio unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/focus-mode" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchFocusModeViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "soc-gpio unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/shooting-mode" && request.requestMethod == "GET" -> {
                val result = inspectShootingModeViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "buttons unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/shooting-mode" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchShootingModeViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "buttons unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/view-mode" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                require(body.keys == setOf("pressed")) { "view-mode payload requires exact key: pressed" }
                val pressed = body["pressed"] as? Boolean ?: throw IllegalArgumentException("pressed must be boolean")
                val result = setViewMode?.invoke(pressed)
                if (result == null) respond(request, 404, mapOf("error" to "view-mode GPIO target unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/telemetry/upload-log" && request.requestMethod == "POST" -> {
                val uploadHandler = telemetryUpload ?: return respond(request, 404, mapOf("error" to "telemetry unavailable"))
                val query = parseQueryParams(request.requestURI.rawQuery)
                val rawBody = readBody(request)
                var notes = query["notes"]
                if (rawBody.isNotEmpty()) {
                    requireJson(request)
                    val body = Formats.map(Formats.json.readValue(rawBody, Map::class.java))
                    body["notes"]?.let { raw ->
                        require(raw is String) { "notes must be string" }
                        notes = raw
                    }
                }
                val result = uploadHandler(notes)
                val status = if (result["uploaded"] == true) 200 else 503
                respond(request, status, result)
            }
            path == "/api/audio" && request.requestMethod == "GET" -> {
                val handler = audioStatus ?: return respond(request, 404, mapOf("error" to "audio unavailable"))
                respond(request, 200, handler())
            }
            path == "/api/audio/devices" && request.requestMethod == "GET" -> {
                val handler = audioDevices ?: return respond(request, 404, mapOf("error" to "audio devices unavailable"))
                respond(request, 200, handler())
            }
            path == "/api/audio" && request.requestMethod == "POST" -> {
                val handler = audioUpdate ?: return respond(request, 404, mapOf("error" to "audio unavailable"))
                requireJson(request)
                val body = Formats.map(Formats.json.readValue(readBody(request), Map::class.java))
                val allowed = setOf(
                    "enabled",
                    "volume",
                    "muted",
                    "outputDeviceId",
                    "bufferMs",
                    "backendPreference",
                    "headphonePlugged",
                    "inputMode",
                    "inputDeviceId",
                    "inputGainDb",
                    "inputMuted",
                    "inputMonitor",
                    "testToneHz",
                    "extMicPlugged",
                )
                require(body.isNotEmpty() && body.keys.all { it in allowed }) {
                    "audio update accepts non-empty subset of $allowed"
                }
                respond(request, 200, handler(body))
            }
            path == "/api/wireless" && request.requestMethod == "GET" -> {
                val result = wirelessStatus?.invoke() ?: inspectWirelessViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "bcm43438 wireless unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/wireless" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = wirelessAction?.invoke(body) ?: dispatchWirelessViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "bcm43438 wireless unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/battery" && request.requestMethod == "GET" -> {
                val result = batteryStatus?.invoke() ?: inspectBatteryViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "battery component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/battery" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val normalized = validateBatteryPayload(body)
                val result = batteryAction?.invoke(normalized) ?: dispatchBatteryViaControl(normalized)
                if (result == null) respond(request, 404, mapOf("error" to "battery component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/power" && request.requestMethod == "GET" -> {
                val result = batteryStatus?.invoke() ?: inspectBatteryViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "battery component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/power" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val normalized = validateBatteryPayload(body)
                val result = batteryAction?.invoke(normalized) ?: dispatchBatteryViaControl(normalized)
                if (result == null) respond(request, 404, mapOf("error" to "battery component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/lens" && request.requestMethod == "GET" -> {
                val result = inspectLensViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "lens component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/lens" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchLensViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "lens component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/filters" && request.requestMethod == "GET" -> {
                val result = inspectOpticalFiltersViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "optical-filter component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/filters" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchOpticalFiltersViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "optical-filter component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/nd" && request.requestMethod == "GET" -> {
                val result = inspectNdViaControl()
                if (result == null) respond(request, 404, mapOf("error" to "optical-filter component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/nd" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val result = dispatchNdViaControl(body)
                if (result == null) respond(request, 404, mapOf("error" to "optical-filter component unavailable"))
                else respond(request, 200, result)
            }
            path == "/api/lang" && request.requestMethod == "GET" -> {
                val result = languageStatus?.invoke() ?: mapOf(
                    "language" to fallbackLanguage,
                    "supported" to FALLBACK_SUPPORTED_LANGUAGES,
                )
                respond(request, 200, result)
            }
            path == "/api/lang" && request.requestMethod == "POST" -> {
                requireJson(request)
                val body = readJsonObject(readBody(request))
                require(body.size == 1 && (body.containsKey("language") || body.containsKey("lang"))) {
                    "lang payload requires exact key: language (or lang)"
                }
                val rawLang = (body["language"] ?: body["lang"]) as? String
                    ?: throw IllegalArgumentException("language must be a string")
                val result = setLanguage?.invoke(rawLang) ?: applyFallbackLanguage(rawLang)
                respond(request, 200, result)
            }
            path == "/api/theme" && request.requestMethod == "GET" -> {
                val handler = themeStatus ?: return respond(request, 404, mapOf("error" to "theme controller unavailable"))
                respond(request, 200, handler())
            }
            path == "/api/theme" && request.requestMethod == "POST" -> {
                val handler = themeUpdate ?: return respond(request, 404, mapOf("error" to "theme controller unavailable"))
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val allowed = setOf("body", "ui")
                require(body.isNotEmpty() && body.keys.all { it in allowed }) {
                    "theme update requires non-empty subset of $allowed"
                }
                body.forEach { (k, v) ->
                    require(v is String && v.isNotBlank()) { "theme field '$k' must be a non-blank string" }
                }
                respond(request, 200, handler(body))
            }
            path == "/api/theme/reload" && request.requestMethod == "POST" -> {
                val handler = themeReload ?: return respond(request, 404, mapOf("error" to "theme reload unavailable"))
                val rawBody = readBody(request)
                if (rawBody.isNotEmpty() && String(rawBody, Charsets.UTF_8).trim().isNotEmpty()) {
                    requireJson(request)
                    val body = readJsonObject(rawBody)
                    require(body.isEmpty()) { "theme reload body must be empty or {}" }
                }
                respond(request, 200, handler())
            }
            path == "/api/flash" && request.requestMethod == "GET" -> {
                val handler = flashStatus ?: return respond(request, 404, mapOf("error" to "flash controller unavailable"))
                respond(request, 200, handler())
            }
            path == "/api/flash" && request.requestMethod == "POST" -> {
                val handler = flashAction ?: return respond(request, 404, mapOf("error" to "flash controller unavailable"))
                requireJson(request)
                val body = readJsonObject(readBody(request))
                val allowed = setOf("action", "path", "preserveCalibration")
                require(body.isNotEmpty() && body.keys.all { it in allowed }) {
                    "flash action requires non-empty subset of $allowed"
                }
                val action = (body["action"] as? String)?.trim()?.lowercase(java.util.Locale.ROOT)
                    ?: throw IllegalArgumentException("flash action requires string field 'action'")
                require(action in setOf("write-dat", "export", "import", "restore-factory")) {
                    "unsupported flash action '$action' (expected: write-dat, export, import, restore-factory)"
                }
                body["path"]?.let { require(it is String && it.isNotBlank()) { "path must be a non-blank string" } }
                body["preserveCalibration"]?.let { require(it is Boolean) { "preserveCalibration must be boolean" } }
                respond(request, 200, handler(body))
            }
            path == "/api/openapi.json" && request.requestMethod == "GET" -> {
                respondRaw(request, 200, "application/json; charset=utf-8", ControlApiDocGenerator.openApiJsonBytes)
            }
            path == "/api/docs" && request.requestMethod == "GET" -> {
                respondRaw(request, 200, "text/html; charset=utf-8", ControlApiDocGenerator.interactiveHtmlBytes)
            }
            else -> respond(request, 404, mapOf("error" to "unknown route or method"))
        }
    }
    private fun applyFallbackLanguage(rawLang: String): Map<String, Any?> {
        val norm = rawLang.trim().lowercase(java.util.Locale.ROOT).replace('-', '_')
        val canonical = when {
            norm == "en" || norm.startsWith("en_") || norm == "english" -> "en"
            norm == "zh" || norm == "zh_cn" || norm == "zh_hans" || norm.startsWith("zh_hans_") || norm == "chinese" -> "zh_CN"
            norm == "ja" || norm == "jp" || norm == "ja_jp" || norm.startsWith("ja_") || norm == "japanese" -> "ja_JP"
            norm == "de" || norm == "de_de" || norm.startsWith("de_") || norm == "german" || norm == "deutsch" -> "de_DE"
            norm == "fr" || norm == "fr_fr" || norm.startsWith("fr_") || norm == "french" || norm == "francais" || norm == "fran\u00E7ais" -> "fr_FR"
            norm == "es" || norm == "es_es" || norm.startsWith("es_") || norm == "spanish" || norm == "espanol" || norm == "espa\u00F1ol" -> "es_ES"
            else -> throw IllegalArgumentException(
                "Unsupported language '$rawLang' (supported: ${FALLBACK_SUPPORTED_LANGUAGES.joinToString(", ")})",
            )
        }
        fallbackLanguage = canonical
        return mapOf(
            "ok" to true,
            "language" to canonical,
            "supported" to FALLBACK_SUPPORTED_LANGUAGES,
        )
    }
    private fun readJsonObject(bytes: ByteArray): Map<String, Any?> = try {
        Formats.map(Formats.json.readValue(bytes, Map::class.java))
    } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
        throw IllegalArgumentException("JSON object required", e)
    }
    private fun requireExactInt(raw: Any?, field: String, range: IntRange): Int {
        require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() == raw.toLong().toDouble()) {
            "$field must be an integer"
        }
        val value = raw.toLong()
        require(value in range.first.toLong()..range.last.toLong()) { "$field out of range $range" }
        return value.toInt()
    }
    private fun dispatchTouchViaControl(x: Int, y: Int, pressed: Boolean): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val rearTouch = components.singleOrNull { comp ->
            comp["id"] == "rear-touch-ic" &&
                ((((comp["state"] as? Map<*, *>)?.get("enabled") as? Boolean)
                    ?: ((comp["config"] as? Map<*, *>)?.get("enabled") as? Boolean)) == true)
        }
        val target = rearTouch ?: components.singleOrNull { it["id"] == "i2c-bus" } ?: return null
        val targetId = target["id"] as? String ?: return null
        val revision = (target["revision"] as? Number)?.toLong() ?: return null
        val updated = control.patch(
            targetId,
            revision,
            mapOf(
                "touchEnabled" to true,
                "touchPressed" to pressed,
                "touchX" to x.toLong(),
                "touchY" to y.toLong(),
            ),
        ).join()
        return mapOf(
            "ok" to true,
            "component" to targetId,
            "x" to x,
            "y" to y,
            "pressed" to pressed,
            "revision" to updated["revision"],
        )
    }
    private fun dispatchTouchSwipeViaControl(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        steps: Int,
        stepMs: Long,
    ): Map<String, Any?>? {
        fun send(x: Int, y: Int, pressed: Boolean): Map<String, Any?>? =
            touch?.invoke(x, y, pressed) ?: dispatchTouchViaControl(x, y, pressed)

        var last = send(x1, y1, true) ?: return null
        for (i in 1..steps) {
            Thread.sleep(stepMs)
            val sx = x1 + ((x2 - x1) * i) / steps
            val sy = y1 + ((y2 - y1) * i) / steps
            last = send(sx, sy, true) ?: return null
        }
        Thread.sleep(stepMs)
        last = send(x2, y2, false) ?: return null
        return linkedMapOf(
            "ok" to true,
            "component" to last["component"],
            "x1" to x1,
            "y1" to y1,
            "x2" to x2,
            "y2" to y2,
            "steps" to steps,
            "stepMs" to stepMs,
            "revision" to last["revision"],
        )
    }
    private fun inspectWirelessViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = (snapshot["components"] as? List<Map<String, Any?>>)?.associateBy { it["id"] } ?: return null
        val chip = components["bcm43438"] ?: return null
        @Suppress("UNCHECKED_CAST")
        return chip["state"] as? Map<String, Any?>
    }
    private fun dispatchWirelessViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val chip = components.singleOrNull { it["id"] == "bcm43438" } ?: return null
        val revision = (chip["revision"] as? Number)?.toLong() ?: return null
        val allowed = setOf(
            "deviceName", "blePeerConnected", "wifiPeerConnected", "ptpIpBridgeEnabled",
            "wifiNetworkEnabled", "wifiNetworkMode", "wifiSsid", "wifiPassphrase",
            "wifiMacAddr", "wifiSubnetCidr", "wifiGatewayIp", "wifiGuestIp",
            "wifiDnsServers", "wifiBridgeInterface", "wifiHostOnlyLoopbackForward",
            "wifiHostOnlySubnetCidr", "wifiPortForwards",
        )
        val patch = body.filterKeys { it in allowed }.mapValues { it.value as Any }
        require(patch.isNotEmpty()) { "wireless update requires non-empty subset of $allowed" }
        val updated = control.patch("bcm43438", revision, patch).join()
        @Suppress("UNCHECKED_CAST")
        return (updated["state"] as? Map<String, Any?>) ?: updated
    }
    private fun validateBatteryPayload(body: Map<String, Any?>): Map<String, Any> {
        val allowed = setOf(
            "present",
            "chargePercent",
            "batteryPercent",
            "voltageMillivolts",
            "charging",
            "externalPowerConnected",
            "usbVbusConnected",
            "autoChargeDrift",
        )
        require(body.isNotEmpty() && body.keys.all { it in allowed }) {
            "battery update requires non-empty subset of $allowed"
        }
        val out = LinkedHashMap<String, Any>()
        if ("present" in body) {
            out["present"] = body["present"] as? Boolean ?: throw IllegalArgumentException("present must be boolean")
        }
        val pctRaw = body["chargePercent"] ?: body["batteryPercent"]
        if (pctRaw != null || "chargePercent" in body || "batteryPercent" in body) {
            require(pctRaw is Number && pctRaw.toDouble().isFinite() && pctRaw.toDouble() in 0.0..100.0) {
                "chargePercent must be a finite number in 0..100"
            }
            out["chargePercent"] = pctRaw.toDouble()
        }
        if ("voltageMillivolts" in body) {
            out["voltageMillivolts"] = requireExactInt(body["voltageMillivolts"], "voltageMillivolts", 0..12000).toLong()
        }
        if ("charging" in body) {
            out["charging"] = body["charging"] as? Boolean ?: throw IllegalArgumentException("charging must be boolean")
        }
        if ("externalPowerConnected" in body || "usbVbusConnected" in body) {
            val extRaw = body["externalPowerConnected"] ?: body["usbVbusConnected"]
            out["externalPowerConnected"] = extRaw as? Boolean
                ?: throw IllegalArgumentException("externalPowerConnected must be boolean")
        }
        if ("autoChargeDrift" in body) {
            out["autoChargeDrift"] = body["autoChargeDrift"] as? Boolean
                ?: throw IllegalArgumentException("autoChargeDrift must be boolean")
        }
        return out
    }
    private fun inspectBatteryViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = (snapshot["components"] as? List<Map<String, Any?>>)?.associateBy { it["id"] } ?: return null
        val bat = components["battery"] ?: return null
        @Suppress("UNCHECKED_CAST")
        val st = (bat["state"] as? Map<String, Any?>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val cfg = (bat["config"] as? Map<String, Any?>).orEmpty()
        val present = (st["present"] as? Boolean) ?: (cfg["present"] as? Boolean) ?: true
        val pct = ((st["chargePercent"] as? Number) ?: (cfg["chargePercent"] as? Number))?.toDouble() ?: 100.0
        val mv = ((st["voltageMillivolts"] as? Number) ?: (cfg["voltageMillivolts"] as? Number))?.toInt()
            ?: NpW235BatteryCurve.NOMINAL_MILLIVOLTS
        val rawCharging = (st["charging"] as? Boolean) ?: (cfg["charging"] as? Boolean) ?: false
        val charging = present && rawCharging
        val extPower = (st["externalPowerConnected"] as? Boolean)
            ?: (cfg["externalPowerConnected"] as? Boolean)
            ?: rawCharging
        val crate = ((st["cratePercentPerHour"] as? Number) ?: (st["chargeRatePercentPerHour"] as? Number))?.toDouble()
            ?: when {
                !present -> 0.0
                charging -> if (pct < 99.5) 36.0 else 6.0
                extPower -> 0.0
                else -> -12.0
            }
        val autoDrift = (st["autoChargeDrift"] as? Boolean) ?: (cfg["autoChargeDrift"] as? Boolean) ?: false
        return linkedMapOf(
            "ok" to true,
            "component" to "battery",
            "revision" to bat["revision"],
            "present" to present,
            "chargePercent" to pct,
            "voltageMillivolts" to mv,
            "charging" to charging,
            "externalPowerConnected" to extPower,
            "usbVbusConnected" to extPower,
            "cratePercentPerHour" to crate,
            "autoChargeDrift" to autoDrift,
        )
    }
    private fun dispatchBatteryViaControl(changes: Map<String, Any>): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val bat = components.singleOrNull { it["id"] == "battery" } ?: return null
        val revision = (bat["revision"] as? Number)?.toLong() ?: return null
        control.patch("battery", revision, changes).join()
        return inspectBatteryViaControl()
    }
    private fun inspectLensViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = (snapshot["components"] as? List<Map<String, Any?>>)?.associateBy { it["id"] } ?: return null
        val lens = components["lens"] ?: return null
        val mount = components["mount"]
        @Suppress("UNCHECKED_CAST")
        val lensState = (lens["state"] as? Map<String, Any?>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val mountState = (mount?.get("state") as? Map<String, Any?>).orEmpty()
        return linkedMapOf<String, Any?>(
            "ok" to true,
            "component" to "lens",
            "revision" to lens["revision"],
            "template" to lensState["template"],
            "model" to lensState["model"],
            "communicationMode" to lensState["communicationMode"],
            "electronicContacts" to lensState["electronicContacts"],
            "apertureRingMode" to lensState["apertureRingMode"],
            "aperture" to lensState["aperture"],
            "irisStepIndex" to lensState["irisStepIndex"],
            "apertureRingStepIndex" to lensState["apertureRingStepIndex"],
            "bodyIrisSynced" to lensState["bodyIrisSynced"],
            "bodyCommandedAperture" to lensState["bodyCommandedAperture"],
            "minAperture" to lensState["minAperture"],
            "maxAperture" to lensState["maxAperture"],
            "apertureStops" to lensState["apertureStops"],
            "focalLengthMm" to lensState["focalLengthMm"],
            "minFocalLengthMm" to lensState["minFocalLengthMm"],
            "maxFocalLengthMm" to lensState["maxFocalLengthMm"],
            "focusDistanceM" to lensState["focusDistanceM"],
            "minFocusDistanceM" to lensState["minFocusDistanceM"],
            "focusMode" to lensState["focusMode"],
            "oisEnabled" to lensState["oisEnabled"],
            "opticsEnabled" to lensState["opticsEnabled"],
            "mountAttached" to (mountState["attached"] ?: true),
            "mountProtocol" to mountState["contactProtocol"],
        )
    }
    private fun dispatchLensViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val allowed = setOf(
            "template", "adapterId", "communicationMode", "realFirmwareFile",
            "apertureRingMode", "aperture", "focalLengthMm", "focusDistanceM",
            "subjectDistanceM", "focusMode", "oisEnabled", "opticsEnabled",
            "electronicContacts", "afTrigger",
        )
        require(body.isNotEmpty() && body.keys.all { it in allowed }) {
            "lens update requires non-empty subset of $allowed"
        }
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val lens = components.singleOrNull { it["id"] == "lens" } ?: return null
        val revision = (lens["revision"] as? Number)?.toLong() ?: return null
        val changes = linkedMapOf<String, Any>()
        for ((key, raw) in body) {
            if (raw == null) throw IllegalArgumentException("$key must not be null")
            if (key == "apertureRingMode") {
                val mode = (raw as? String)?.trim()?.uppercase()
                    ?: throw IllegalArgumentException("apertureRingMode must be string (A, C, or MANUAL)")
                require(mode in setOf("A", "C", "MANUAL")) {
                    "apertureRingMode must be one of [A, C, MANUAL], got '$raw'"
                }
                changes[key] = mode
            } else {
                changes[key] = raw
            }
        }
        control.patch("lens", revision, changes).join()
        return inspectLensViaControl()
    }
    private fun inspectEyeSensorViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = (snapshot["components"] as? List<Map<String, Any?>>)?.associateBy { it["id"] } ?: return null
        val tmd = components["tmd2772"]?.takeIf { comp ->
            (((comp["state"] as? Map<*, *>)?.get("enabled") as? Boolean)
                ?: ((comp["config"] as? Map<*, *>)?.get("enabled") as? Boolean)) == true
        }
        val target = tmd ?: components["i2c-bus"] ?: return null
        val targetId = target["id"] as? String ?: return null
        val targetState = target["state"] as? Map<*, *>
        val targetConfig = target["config"] as? Map<*, *>
        val near = (targetState?.get("eyeSensorNear") as? Boolean) ?: (targetConfig?.get("eyeSensorNear") as? Boolean) ?: false
        val displayState = components["display"]?.get("state") as? Map<*, *>
        val lcdSeq = (displayState?.get("mainSequence") as? Number)?.toLong() ?: 0L
        val evfSeq = (displayState?.get("evfSequence") as? Number)?.toLong() ?: 0L
        val vglibState = components["soc-vglib-init"]?.get("state") as? Map<*, *>
        val lcdOutputActive = (vglibState?.get("scanout") as? Map<*, *>)?.get("outputActive") as? Boolean
        val evfOutputActive = (vglibState?.get("evfScanoutState") as? Map<*, *>)?.get("outputActive") as? Boolean
        val activeViewfinder = when {
            evfOutputActive == true && lcdOutputActive != true -> "EVF"
            lcdOutputActive == true && evfOutputActive != true -> "LCD"
            evfOutputActive == true && lcdOutputActive == true -> if (near) "EVF" else "BOTH"
            evfSeq == 0L && lcdSeq == 0L -> "WAITING"
            evfSeq > 0L && lcdSeq == 0L -> "EVF"
            lcdSeq > 0L && evfSeq == 0L -> "LCD"
            else -> if (near) "EVF" else "LCD"
        }
        return linkedMapOf(
            "ok" to true,
            "component" to targetId,
            "near" to near,
            "activeViewfinder" to activeViewfinder,
            "lcdSequence" to lcdSeq,
            "evfSequence" to evfSeq,
            "lcdOutputActive" to lcdOutputActive,
            "evfOutputActive" to evfOutputActive,
        )
    }
    private fun dispatchEyeSensorViaControl(near: Boolean): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val tmd = components.singleOrNull { comp ->
            comp["id"] == "tmd2772" &&
                ((((comp["state"] as? Map<*, *>)?.get("enabled") as? Boolean)
                    ?: ((comp["config"] as? Map<*, *>)?.get("enabled") as? Boolean)) == true)
        }
        val target = tmd ?: components.singleOrNull { it["id"] == "i2c-bus" } ?: return null
        val targetId = target["id"] as? String ?: return null
        val revision = (target["revision"] as? Number)?.toLong() ?: return null
        val updated = control.patch(targetId, revision, mapOf("eyeSensorNear" to near)).join()
        return mapOf(
            "ok" to true,
            "component" to targetId,
            "near" to near,
            "revision" to updated["revision"],
        )
    }
    private fun inspectAdcViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val adc = components.singleOrNull { it["id"] == "soc-adc" } ?: return null
        val adcState = adc["state"] as? Map<*, *>
        val adcConfig = adc["config"] as? Map<*, *>
        val registers = adcState?.get("registers") as? Map<*, *>
        val liveKeys = listOf(
            "qButton", "aelButton", "modeDial",
            "fn1Button", "fn2Button", "fn3Button", "lightButton",
            "rearDialPosition", "rearDialDetents",
            "ch3Override", "ch5Override", "ch6Override", "ch7Override", "ch11Override",
        )
        val liveValues = linkedMapOf<String, Any?>()
        for (key in liveKeys) {
            liveValues[key] = adcState?.get(key) ?: adcConfig?.get(key)
        }
        return linkedMapOf(
            "ok" to true,
            "component" to "soc-adc",
            "revision" to adc["revision"],
            "controls" to liveValues,
            "samples" to registers?.get("samples"),
            "eventCount" to adcState?.get("eventCount"),
            "sampleReadCount" to adcState?.get("sampleReadCount"),
        )
    }
    private fun dispatchAdcViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val boolKeys = setOf("qButton", "aelButton", "fn1Button", "fn2Button", "fn3Button", "lightButton")
        val intKeys = mapOf(
            "rearDialPosition" to (-1..3),
            "rearDialDetents" to (-10000..10000),
            "ch3Override" to (-1..4095),
            "ch5Override" to (-1..4095),
            "ch6Override" to (-1..4095),
            "ch7Override" to (-1..4095),
            "ch11Override" to (-1..4095),
        )
        val allowed = boolKeys + intKeys.keys + setOf("modeDial", "rearDialStep")
        require(body.isNotEmpty() && body.keys.all { it in allowed }) {
            "adc payload requires non-empty subset of $allowed"
        }
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val adc = components.singleOrNull { it["id"] == "soc-adc" } ?: return null
        val revision = (adc["revision"] as? Number)?.toLong() ?: return null
        val adcState = adc["state"] as? Map<*, *>
        val adcConfig = adc["config"] as? Map<*, *>
        val changes = linkedMapOf<String, Any>()
        for ((key, raw) in body) {
            when {
                key in boolKeys -> {
                    val value = raw as? Boolean ?: throw IllegalArgumentException("$key must be boolean")
                    changes[key] = value
                }
                key == "modeDial" -> {
                    val value = raw as? String ?: throw IllegalArgumentException("modeDial must be string")
                    changes[key] = value
                }
                key in intKeys -> {
                    changes[key] = requireExactInt(raw, key, intKeys.getValue(key)).toLong()
                }
                key == "rearDialStep" -> {
                    val delta = requireExactInt(raw, "rearDialStep", -1000..1000).toLong()
                    val currentDetents = ((adcState?.get("rearDialDetents") as? Number)
                        ?: (adcConfig?.get("rearDialDetents") as? Number))?.toLong() ?: 0L
                    val nextDetents = (currentDetents + delta).coerceIn(-10000L, 10000L)
                    changes["rearDialDetents"] = nextDetents
                    if ("rearDialPosition" !in body) {
                        changes["rearDialPosition"] = -1L
                    }
                }
            }
        }
        val updated = control.patch("soc-adc", revision, changes).join()
        return linkedMapOf(
            "ok" to true,
            "component" to "soc-adc",
            "revision" to updated["revision"],
            "changes" to changes,
        )
    }
    private fun inspectFocusModeViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val gpio = components.singleOrNull { it["id"] == "soc-gpio" } ?: return null
        val gpioState = gpio["state"] as? Map<*, *>
        val gpioConfig = gpio["config"] as? Map<*, *>
        val configured = (gpioState?.get("focusMode") as? String) ?: (gpioConfig?.get("focusMode") as? String) ?: "default"
        val effective = (gpioState?.get("effectiveFocusMode") as? String) ?: if (configured != "default") configured else "AF-S"
        return linkedMapOf(
            "ok" to true,
            "component" to "soc-gpio",
            "revision" to gpio["revision"],
            "focusMode" to configured,
            "effectiveFocusMode" to effective,
        )
    }
    private fun dispatchFocusModeViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        require(body.keys == setOf("focusMode") || body.keys == setOf("mode")) {
            "focus-mode payload requires exact key: focusMode (or mode)"
        }
        val raw = (body["focusMode"] ?: body["mode"]) as? String
            ?: throw IllegalArgumentException("focusMode must be string")
        val normalized = when (raw.trim().uppercase()) {
            "AF-S", "AFS", "S" -> "AF-S"
            "AF-C", "AFC", "C" -> "AF-C"
            "MF", "M" -> "MF"
            "DEFAULT" -> "default"
            else -> throw IllegalArgumentException("focusMode must be one of [AF-S, AF-C, MF, default], got $raw")
        }
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val gpio = components.singleOrNull { it["id"] == "soc-gpio" } ?: return null
        val revision = (gpio["revision"] as? Number)?.toLong() ?: return null
        val updated = control.patch("soc-gpio", revision, mapOf("focusMode" to normalized)).join()
        val updatedState = updated["state"] as? Map<*, *>
        return linkedMapOf(
            "ok" to true,
            "component" to "soc-gpio",
            "revision" to updated["revision"],
            "focusMode" to normalized,
            "effectiveFocusMode" to (updatedState?.get("effectiveFocusMode") ?: normalized),
        )
    }
    private fun inspectShootingModeViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val buttons = components.singleOrNull { it["id"] == "buttons" } ?: return null
        val btnState = buttons["state"] as? Map<*, *>
        val btnConfig = buttons["config"] as? Map<*, *>
        val movieMode = (btnState?.get("movieMode") as? Boolean) ?: (btnConfig?.get("movieMode") as? Boolean) ?: false
        val adc = components.singleOrNull { it["id"] == "soc-adc" }
        val adcState = adc?.get("state") as? Map<*, *>
        val adcConfig = adc?.get("config") as? Map<*, *>
        val modeDial = (adcState?.get("modeDial") as? String) ?: (adcConfig?.get("modeDial") as? String) ?: "default"
        val gpio = components.singleOrNull { it["id"] == "soc-gpio" }
        val gpioState = gpio?.get("state") as? Map<*, *>
        val focusMode = (gpioState?.get("effectiveFocusMode") as? String) ?: "AF-S"
        return linkedMapOf(
            "ok" to true,
            "mode" to if (movieMode) "MOVIE" else "STILL",
            "movieMode" to movieMode,
            "modeDial" to modeDial,
            "focusMode" to focusMode,
        )
    }
    private fun dispatchShootingModeViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val allowed = setOf("mode", "movieMode", "modeDial", "focusMode")
        require(body.isNotEmpty() && body.keys.all { it in allowed }) {
            "shooting-mode payload requires non-empty subset of $allowed"
        }
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val applied = linkedMapOf<String, Any>()
        if ("mode" in body || "movieMode" in body) {
            val buttons = components.singleOrNull { it["id"] == "buttons" } ?: return null
            val revision = (buttons["revision"] as? Number)?.toLong() ?: return null
            val movie = when {
                "movieMode" in body -> body["movieMode"] as? Boolean
                    ?: throw IllegalArgumentException("movieMode must be boolean")
                else -> when ((body["mode"] as? String)?.trim()?.uppercase()) {
                    "MOVIE", "VIDEO" -> true
                    "STILL", "PHOTO" -> false
                    else -> throw IllegalArgumentException("mode must be STILL or MOVIE")
                }
            }
            control.patch("buttons", revision, mapOf("movieMode" to movie)).join()
            applied["movieMode"] = movie
            applied["mode"] = if (movie) "MOVIE" else "STILL"
        }
        if ("modeDial" in body) {
            val adc = components.singleOrNull { it["id"] == "soc-adc" }
                ?: throw IllegalStateException("soc-adc unavailable")
            val revision = (adc["revision"] as? Number)?.toLong() ?: throw IllegalStateException("soc-adc revision missing")
            val dial = body["modeDial"] as? String ?: throw IllegalArgumentException("modeDial must be string")
            control.patch("soc-adc", revision, mapOf("modeDial" to dial)).join()
            applied["modeDial"] = dial
        }
        if ("focusMode" in body) {
            val res = dispatchFocusModeViaControl(mapOf("focusMode" to body["focusMode"]))
                ?: throw IllegalStateException("soc-gpio unavailable")
            applied["focusMode"] = res["effectiveFocusMode"] ?: res["focusMode"] ?: "AF-S"
        }
        return (inspectShootingModeViaControl() ?: linkedMapOf("ok" to true)) + mapOf("changes" to applied)
    }

    private fun inspectOpticalFiltersViaControl(): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val comp = components.singleOrNull { it["id"] == "optical-filter" } ?: return null
        return buildOpticalFilterApiResponse(comp)
    }

    private fun dispatchOpticalFiltersViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val comp = components.singleOrNull { it["id"] == "optical-filter" } ?: return null
        val revision = (comp["revision"] as? Number)?.toLong() ?: return null
        val patch = LinkedHashMap<String, Any>()

        if (body["clear"] == true || body["off"] == true) {
            patch["enabled"] = true
            patch["slots"] = ""
            patch["ndPreset"] = NdFilterPreset.OFF.id
            patch["ndStops"] = 0.0
            patch["bokehMask"] = BokehMaskShape.OFF.id
            patch["starEnabled"] = false
            patch["mistEnabled"] = false
            patch["mistPreset"] = "OFF"
            patch["mistStrength"] = 0.0
            patch["gndEnabled"] = false
            patch["gndStops"] = 0.0
            patch["uvPreset"] = UvFilterPreset.OFF.id
            patch["uvTransmittance"] = 1.0
            patch["uvVeilingGlare"] = 0.0
            patch["uvGhostIntensity"] = 0.0
            patch["uvSoftness"] = 0.0
            patch["uvTintR"] = 1.0
            patch["uvTintG"] = 1.0
            patch["uvTintB"] = 1.0
        }
        (body["enabled"] as? Boolean)?.let { patch["enabled"] = it }

        // Ordered multi-slot rack: accepts JSON array [{"type":"uv",...}, {"type":"nd",...}] or JSON string.
        if (body.containsKey("slots")) {
            val rawSlots = body["slots"]
            when (rawSlots) {
                null -> patch["slots"] = ""
                is String -> {
                    val parsed = OpticalFilterSlotCodec.parse(rawSlots)
                    patch["slots"] = OpticalFilterSlotCodec.encode(parsed)
                }
                is List<*> -> {
                    require(rawSlots.size <= OpticalFilterSlotCodec.MAX_SLOTS) {
                        "slots supports at most ${OpticalFilterSlotCodec.MAX_SLOTS} entries"
                    }
                    val parsed = rawSlots.mapIndexed { idx, entry ->
                        val map = entry as? Map<*, *>
                            ?: throw IllegalArgumentException("slots[$idx] must be a JSON object")
                        OpticalFilterSlotCodec.fromMap(map)
                    }
                    patch["slots"] = OpticalFilterSlotCodec.encode(parsed)
                }
                else -> throw IllegalArgumentException("slots must be a JSON array or string")
            }
        }

        // Flat keys directly matching optical-filter component schema
        val directStringKeys = setOf("ndPreset", "bokehMask", "mistPreset", "uvPreset")
        val directBoolKeys = setOf("starEnabled", "mistEnabled", "gndEnabled")
        val directNumKeys = setOf(
            "ndStops",
            "bokehMaskAngleDeg", "bokehMaskScale",
            "starPoints", "starStrength", "starThreshold", "starAngleDeg", "starLength",
            "mistStrength", "mistThreshold",
            "gndStops", "gndHorizon", "gndFeather", "gndAngleDeg",
            "uvTransmittance", "uvVeilingGlare", "uvGhostIntensity", "uvSoftness",
            "uvTintR", "uvTintG", "uvTintB",
        )
        for (k in directStringKeys) {
            val v = body[k] ?: continue
            require(v is String) { "$k must be a string" }
            when (k) {
                "ndPreset" -> {
                    val parsed = NdFilterSpec.parsePreset(v)
                        ?: throw IllegalArgumentException("Unknown ndPreset '$v'")
                    patch["ndPreset"] = parsed.id
                }
                "bokehMask" -> {
                    val parsed = BokehMaskShape.parse(v)
                        ?: throw IllegalArgumentException("Unknown bokehMask '$v'")
                    patch["bokehMask"] = parsed.id
                }
                "uvPreset" -> {
                    val parsed = UvFilterPreset.parse(v)
                        ?: throw IllegalArgumentException("Unknown uvPreset '$v'")
                    patch["uvPreset"] = parsed.id
                }
                else -> patch[k] = v
            }
        }
        for (k in directBoolKeys) {
            val v = body[k] ?: continue
            require(v is Boolean) { "$k must be a boolean" }
            patch[k] = v
        }
        for (k in directNumKeys) {
            val v = body[k] ?: continue
            require(v is Number && v.toDouble().isFinite()) { "$k must be a finite number" }
            patch[k] = if (k == "starPoints") v.toLong() else v.toDouble()
        }

        // Convenient nested shorthand objects: uv, nd, bokeh, star, mist, gnd
        when (val rawUv = body["uv"]) {
            is String -> {
                val parsed = UvFilterPreset.parse(rawUv)
                    ?: throw IllegalArgumentException("Unknown uv preset '$rawUv'")
                patch["uvPreset"] = parsed.id
            }
            is Map<*, *> -> {
                (rawUv["preset"] as? String)?.let { p ->
                    val parsed = UvFilterPreset.parse(p)
                        ?: throw IllegalArgumentException("Unknown uv.preset '$p'")
                    patch["uvPreset"] = parsed.id
                }
                (rawUv["transmittance"] as? Number)?.let { patch["uvTransmittance"] = it.toDouble() }
                (rawUv["veilingGlare"] as? Number)?.let { patch["uvVeilingGlare"] = it.toDouble() }
                (rawUv["ghostIntensity"] as? Number)?.let { patch["uvGhostIntensity"] = it.toDouble() }
                (rawUv["softness"] as? Number)?.let { patch["uvSoftness"] = it.toDouble() }
                (rawUv["tintR"] as? Number)?.let { patch["uvTintR"] = it.toDouble() }
                (rawUv["tintG"] as? Number)?.let { patch["uvTintG"] = it.toDouble() }
                (rawUv["tintB"] as? Number)?.let { patch["uvTintB"] = it.toDouble() }
            }
        }
        (body["nd"] as? Map<*, *>)?.let { nd ->
            (nd["preset"] as? String)?.let { p ->
                val parsed = NdFilterSpec.parsePreset(p) ?: throw IllegalArgumentException("Unknown nd.preset '$p'")
                patch["ndPreset"] = parsed.id
                val fixed = parsed.fixedStops
                if (fixed != null && !nd.containsKey("stops")) patch["ndStops"] = fixed
            }
            (nd["stops"] as? Number)?.let { s -> patch["ndStops"] = NdFilterSpec.clampStops(s.toDouble()) }
        }
        (body["bokeh"] as? Map<*, *>)?.let { bk ->
            (bk["shape"] as? String ?: bk["mask"] as? String)?.let { s ->
                val parsed = BokehMaskShape.parse(s) ?: throw IllegalArgumentException("Unknown bokeh.shape '$s'")
                patch["bokehMask"] = parsed.id
            }
            (bk["angleDeg"] as? Number)?.let { patch["bokehMaskAngleDeg"] = it.toDouble() }
            (bk["scale"] as? Number)?.let { patch["bokehMaskScale"] = it.toDouble() }
        }
        (body["star"] as? Map<*, *>)?.let { st ->
            (st["enabled"] as? Boolean)?.let { patch["starEnabled"] = it }
            (st["points"] as? Number)?.let { patch["starPoints"] = it.toLong() }
            (st["strength"] as? Number)?.let { patch["starStrength"] = it.toDouble() }
            (st["threshold"] as? Number)?.let { patch["starThreshold"] = it.toDouble() }
            (st["angleDeg"] as? Number)?.let { patch["starAngleDeg"] = it.toDouble() }
            (st["length"] as? Number)?.let { patch["starLength"] = it.toDouble() }
        }
        (body["mist"] as? Map<*, *>)?.let { ms ->
            (ms["enabled"] as? Boolean)?.let { patch["mistEnabled"] = it }
            (ms["preset"] as? String)?.let { patch["mistPreset"] = it }
            (ms["strength"] as? Number)?.let { patch["mistStrength"] = it.toDouble() }
            (ms["threshold"] as? Number)?.let { patch["mistThreshold"] = it.toDouble() }
        }
        (body["gnd"] as? Map<*, *>)?.let { gn ->
            (gn["enabled"] as? Boolean)?.let { patch["gndEnabled"] = it }
            (gn["stops"] as? Number)?.let { patch["gndStops"] = it.toDouble() }
            (gn["horizon"] as? Number)?.let { patch["gndHorizon"] = it.toDouble() }
            (gn["feather"] as? Number)?.let { patch["gndFeather"] = it.toDouble() }
            (gn["angleDeg"] as? Number)?.let { patch["gndAngleDeg"] = it.toDouble() }
        }

        require(patch.isNotEmpty()) { "filters update requires at least one valid filter setting" }
        val updated = control.patch("optical-filter", revision, patch).join()
        return buildOpticalFilterApiResponse(updated)
    }

    private fun buildOpticalFilterApiResponse(comp: Map<String, Any?>): Map<String, Any?> {
        val state = comp["state"] as? Map<*, *>
        val config = comp["config"] as? Map<*, *>
        val out = LinkedHashMap<String, Any?>()
        out["ok"] = true
        out["component"] = "optical-filter"
        out["revision"] = comp["revision"]
        config?.forEach { (k, v) -> if (k is String) out[k] = v }
        state?.forEach { (k, v) -> if (k is String) out[k] = v }
        return out
    }

    private fun inspectNdViaControl(): Map<String, Any?>? {
        val full = inspectOpticalFiltersViaControl() ?: return null
        val stops = NdFilterSpec.clampStops((full["ndStops"] as? Number)?.toDouble() ?: 0.0)
        val enabled = full["enabled"] as? Boolean ?: true
        val effStops = (full["effectiveNdStops"] as? Number)?.toDouble() ?: if (enabled) stops else 0.0
        val preset = (full["ndPreset"] as? String) ?: NdFilterSpec.presetForStops(stops).id
        return linkedMapOf(
            "ok" to true,
            "component" to "optical-filter",
            "revision" to full["revision"],
            "enabled" to enabled,
            "preset" to preset,
            "stops" to stops,
            "effectiveStops" to effStops,
            "factor" to NdFilterSpec.factor(effStops),
            "opticalDensity" to NdFilterSpec.opticalDensity(effStops),
            "transmittance" to NdFilterSpec.transmittance(effStops),
            "transmittancePercent" to NdFilterSpec.transmittancePercent(effStops),
            "summary" to NdFilterSpec.formatSummary(effStops),
        )
    }

    private fun dispatchNdViaControl(body: Map<String, Any?>): Map<String, Any?>? {
        val snapshot = control.snapshot().join()
        @Suppress("UNCHECKED_CAST")
        val components = snapshot["components"] as? List<Map<String, Any?>> ?: return null
        val comp = components.singleOrNull { it["id"] == "optical-filter" } ?: return null
        val revision = (comp["revision"] as? Number)?.toLong() ?: return null
        val patch = LinkedHashMap<String, Any>()
        if (body["off"] == true) {
            patch["enabled"] = true
            patch["ndPreset"] = NdFilterPreset.OFF.id
            patch["ndStops"] = 0.0
        } else {
            val rawPreset = body["preset"] ?: body["ndPreset"]
            val rawStops = body["stops"] ?: body["ndStops"]
            val rawEnabled = body["enabled"]
            if (rawEnabled != null) {
                require(rawEnabled is Boolean) { "enabled must be a boolean" }
                patch["enabled"] = rawEnabled
            }
            if (rawPreset != null) {
                require(rawPreset is String) { "preset must be a string" }
                val parsed = NdFilterSpec.parsePreset(rawPreset)
                    ?: throw IllegalArgumentException("Unknown ND preset '$rawPreset'")
                patch["enabled"] = rawEnabled ?: true
                patch["ndPreset"] = parsed.id
                val fixed = parsed.fixedStops
                if (fixed != null && rawStops == null) {
                    patch["ndStops"] = fixed
                }
            }
            if (rawStops != null) {
                require(rawStops is Number && rawStops.toDouble().isFinite()) { "stops must be a finite number" }
                val v = rawStops.toDouble()
                require(v in NdFilterSpec.MIN_STOPS..NdFilterSpec.MAX_STOPS) {
                    "stops must be in ${NdFilterSpec.MIN_STOPS}..${NdFilterSpec.MAX_STOPS}, got $v"
                }
                patch["enabled"] = rawEnabled ?: true
                patch["ndStops"] = v
                if (rawPreset == null) patch["ndPreset"] = NdFilterSpec.presetForStops(v).id
            }
        }
        require(patch.isNotEmpty()) { "nd update requires preset, stops, off, or enabled" }
        control.patch("optical-filter", revision, patch).join()
        return inspectNdViaControl()
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val fields = rawQuery.split('&').filter { it.isNotEmpty() }.map { pair ->
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2) { "malformed query parameter" }
            java.net.URLDecoder.decode(parts[0], Charsets.UTF_8) to java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
        require(fields.size <= 16 && fields.map { it.first }.distinct().size == fields.size) { "Duplicate or excessive query parameters" }
        return fields.toMap()
    }
    private fun wantsText(request: HttpExchange, query: Map<String, String>): Boolean {
        val format = query["format"]?.lowercase()
        if (format == "text" || format == "txt") return true
        val accept = request.requestHeaders.getFirst("Accept")?.lowercase().orEmpty()
        return accept.contains("text/plain") && !accept.contains("application/json")
    }
    private fun respondText(request: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        request.responseHeaders.set("Content-Type", "text/plain; charset=utf-8")
        request.responseHeaders.set("Cache-Control", "no-store")
        request.sendResponseHeaders(status, bytes.size.toLong())
        request.responseBody.write(bytes)
    }
    private fun requireJson(request: HttpExchange) {
        require(request.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim() == "application/json") { "application/json required" }
    }
    private fun readBody(request: HttpExchange): ByteArray {
        val bytes = request.requestBody.readNBytes(65537)
        require(bytes.size <= 65536) { "request exceeds 64 KiB" }; return bytes
    }
    private fun respond(request: HttpExchange, status: Int, value: Any) {
        respondRaw(request, status, "application/json; charset=utf-8", Formats.jsonBytes(value))
    }
    private fun respondRaw(request: HttpExchange, status: Int, contentType: String, bytes: ByteArray) {
        request.responseHeaders.set("Content-Type", contentType)
        request.responseHeaders.set("Cache-Control", "no-store")
        request.sendResponseHeaders(status, bytes.size.toLong())
        request.responseBody.write(bytes)
    }
    override fun close() { server.stop(0); executor.close() }
}
