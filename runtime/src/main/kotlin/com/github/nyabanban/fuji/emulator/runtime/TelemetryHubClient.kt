package com.github.nyabanban.fuji.emulator.runtime

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

data class TelemetryConfig(
    val enabled: Boolean,
    val endpoint: String,
    val ingestToken: String,
    val uploadLogOnExit: Boolean = true,
    val sourceType: String = TelemetryHubClient.DEFAULT_SOURCE_TYPE,
    val deviceKind: String = TelemetryHubClient.detectDeviceKind(),
    val firmwareId: String? = null,
    val executionMode: String = RelaxedPeripheralFallback.MODE,
    val repoCommit: String? = null,
)

data class BatchIngestOutcome(
    val statusCode: Int,
    val accepted: Int,
    val deduplicated: Int,
    val rejected: Int,
)

data class LogUploadOutcome(
    val statusCode: Int,
    val sessionId: String?,
    val filename: String?,
    val totalLines: Int,
    val accepted: Int,
    val deduplicated: Int,
    val rejected: Int,
    val mmioEvents: Int,
    val flashEvents: Int,
    val userActionEvents: Int,
    val watchdogAndMilestoneEvents: Int,
    val summaryLine: String?,
)

/**
 * Lightweight, zero-extra-dependency HTTP client for `fuji-telemetry-hub` built on Java's
 * standard [HttpClient] and the runtime's existing Jackson [Formats].
 *
 * Security & fault-tolerance guarantees:
 * - Credentials (`ingestToken`) are never logged or embedded in error messages.
 * - Network timeouts, unreachable hosts, and HTTP 4xx/5xx responses degrade silently with
 *   rate-limited `WARN` logs and never propagate uncaught exceptions.
 */
class TelemetryHubClient(
    val config: TelemetryConfig,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val logger: Logger = LoggerFactory.getLogger(TelemetryHubClient::class.java),
    private val warnCooldownNanos: Long = DEFAULT_WARN_COOLDOWN_NANOS,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs.coerceAtLeast(100L)))
        .build()

    private val lastWarnNanos = AtomicLong(0L)

    fun createSession(
        sessionId: String?,
        startedAt: Instant = Instant.now(),
        tags: List<String> = emptyList(),
        notes: String? = null,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): String? {
        val bodyMap = linkedMapOf<String, Any?>(
            "sourceType" to config.sourceType,
            "deviceKind" to config.deviceKind,
            "executionMode" to config.executionMode,
            "startedAt" to startedAt.toString(),
        )
        if (!sessionId.isNullOrBlank()) bodyMap["sessionId"] = sessionId
        if (!config.firmwareId.isNullOrBlank()) bodyMap["firmwareId"] = config.firmwareId
        if (!config.repoCommit.isNullOrBlank()) bodyMap["repoCommit"] = config.repoCommit
        if (tags.isNotEmpty()) bodyMap["tags"] = tags
        if (!notes.isNullOrBlank()) bodyMap["notes"] = notes

        val request = buildRequestSafely("createSession") {
            val bodyBytes = Formats.jsonBytes(bodyMap)
            HttpRequest.newBuilder(URI.create("${config.endpoint}/api/v1/sessions"))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(100L)))
                .header("Authorization", "Bearer ${config.ingestToken}")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build()
        } ?: return null

        val response = sendSafely(request, "createSession") ?: return null
        if (response.statusCode() !in 200..299) {
            warnRateLimited("Telemetry hub createSession returned HTTP ${response.statusCode()}")
            return null
        }
        return runCatching {
            val parsed = Formats.map(Formats.json.readValue(response.body(), Map::class.java))
            (parsed["sessionId"] as? String)?.takeIf { it.isNotBlank() } ?: sessionId
        }.getOrElse { sessionId }
    }

    fun ingestBatch(
        events: List<Map<String, Any?>>,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): BatchIngestOutcome? {
        if (events.isEmpty()) return BatchIngestOutcome(200, 0, 0, 0)
        val request = buildRequestSafely("ingestBatch") {
            val rawJson = Formats.jsonBytes(events)
            val gzipped = gzip(rawJson)
            HttpRequest.newBuilder(URI.create("${config.endpoint}/api/v1/ingest/batch"))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(100L)))
                .header("Authorization", "Bearer ${config.ingestToken}")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-Encoding", "gzip")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(gzipped))
                .build()
        } ?: return null

        val response = sendSafely(request, "ingestBatch") ?: return null
        if (response.statusCode() !in 200..299) {
            warnRateLimited("Telemetry hub ingestBatch returned HTTP ${response.statusCode()}")
            return null
        }
        return runCatching {
            val parsed = Formats.map(Formats.json.readValue(response.body(), Map::class.java))
            BatchIngestOutcome(
                statusCode = response.statusCode(),
                accepted = (parsed["accepted"] as? Number)?.toInt() ?: events.size,
                deduplicated = (parsed["deduplicated"] as? Number)?.toInt() ?: 0,
                rejected = (parsed["rejected"] as? Number)?.toInt() ?: 0,
            )
        }.getOrElse {
            BatchIngestOutcome(response.statusCode(), events.size, 0, 0)
        }
    }

    fun uploadEmulatorLog(
        logFile: Path,
        sessionId: String? = null,
        notes: String? = null,
        timeoutMs: Long = DEFAULT_UPLOAD_TIMEOUT_MS,
    ): LogUploadOutcome? {
        if (!Files.isRegularFile(logFile)) return null
        val fileBytes = runCatching { Files.readAllBytes(logFile) }.getOrNull() ?: return null
        if (fileBytes.isEmpty()) return null

        val params = linkedMapOf<String, String>()
        if (!sessionId.isNullOrBlank()) params["sessionId"] = sessionId
        params["sourceType"] = config.sourceType
        params["deviceKind"] = config.deviceKind
        if (!config.firmwareId.isNullOrBlank()) params["firmwareId"] = config.firmwareId
        if (!config.repoCommit.isNullOrBlank()) params["repoCommit"] = config.repoCommit
        params["executionMode"] = config.executionMode
        if (!notes.isNullOrBlank()) params["notes"] = notes

        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val boundary = "----FujiTelemetryBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val filename = logFile.fileName?.toString()?.replace("\"", "_") ?: LogArchiver.LATEST_LOG_FILE
        val multipart = buildMultipartBody(boundary, "file", filename, fileBytes)

        val url = "${config.endpoint}/api/v1/ingest/emulator-log" + if (query.isNotEmpty()) "?$query" else ""
        val request = buildRequestSafely("uploadEmulatorLog") {
            HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(100L)))
                .header("Authorization", "Bearer ${config.ingestToken}")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
                .build()
        } ?: return null

        val response = sendSafely(request, "uploadEmulatorLog") ?: return null
        if (response.statusCode() !in 200..299) {
            warnRateLimited("Telemetry hub uploadEmulatorLog returned HTTP ${response.statusCode()}")
            return null
        }
        return runCatching {
            val parsed = Formats.map(Formats.json.readValue(response.body(), Map::class.java))
            LogUploadOutcome(
                statusCode = response.statusCode(),
                sessionId = parsed["sessionId"] as? String ?: sessionId,
                filename = parsed["filename"] as? String ?: filename,
                totalLines = (parsed["totalLines"] as? Number)?.toInt() ?: 0,
                accepted = (parsed["accepted"] as? Number)?.toInt() ?: 0,
                deduplicated = (parsed["deduplicated"] as? Number)?.toInt() ?: 0,
                rejected = (parsed["rejected"] as? Number)?.toInt() ?: 0,
                mmioEvents = (parsed["mmioEvents"] as? Number)?.toInt() ?: 0,
                flashEvents = (parsed["flashEvents"] as? Number)?.toInt() ?: 0,
                userActionEvents = (parsed["userActionEvents"] as? Number)?.toInt() ?: 0,
                watchdogAndMilestoneEvents = (parsed["watchdogAndMilestoneEvents"] as? Number)?.toInt() ?: 0,
                summaryLine = parsed["summaryLine"] as? String,
            )
        }.getOrElse {
            LogUploadOutcome(
                statusCode = response.statusCode(),
                sessionId = sessionId,
                filename = filename,
                totalLines = 0,
                accepted = 0,
                deduplicated = 0,
                rejected = 0,
                mmioEvents = 0,
                flashEvents = 0,
                userActionEvents = 0,
                watchdogAndMilestoneEvents = 0,
                summaryLine = null,
            )
        }
    }

    fun updateSession(
        sessionId: String,
        status: String,
        endedAt: Instant = Instant.now(),
        notes: String? = null,
        timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    ): Boolean {
        if (sessionId.isBlank()) return false
        val bodyMap = linkedMapOf<String, Any?>(
            "status" to status,
            "endedAt" to endedAt.toString(),
        )
        if (!notes.isNullOrBlank()) bodyMap["notes"] = notes

        val request = buildRequestSafely("updateSession") {
            val bodyBytes = Formats.jsonBytes(bodyMap)
            val encodedId = urlEncode(sessionId)
            HttpRequest.newBuilder(URI.create("${config.endpoint}/api/v1/sessions/$encodedId"))
                .timeout(Duration.ofMillis(timeoutMs.coerceAtLeast(100L)))
                .header("Authorization", "Bearer ${config.ingestToken}")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build()
        } ?: return false

        val response = sendSafely(request, "updateSession") ?: return false
        if (response.statusCode() !in 200..299) {
            warnRateLimited("Telemetry hub updateSession returned HTTP ${response.statusCode()}")
            return false
        }
        return true
    }

    private fun buildRequestSafely(operation: String, builder: () -> HttpRequest): HttpRequest? {
        return try {
            builder()
        } catch (error: Throwable) {
            warnRateLimited("Telemetry hub $operation request build failed: ${error.javaClass.simpleName}: ${error.message ?: "invalid"}")
            null
        }
    }

    private fun sendSafely(request: HttpRequest, operation: String): HttpResponse<ByteArray>? {
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            warnRateLimited("Telemetry hub $operation interrupted")
            null
        } catch (error: Throwable) {
            warnRateLimited("Telemetry hub $operation failed: ${error.javaClass.simpleName}: ${error.message ?: "unavailable"}")
            null
        }
    }

    private fun warnRateLimited(message: String) {
        val now = System.nanoTime()
        val prev = lastWarnNanos.get()
        if (prev == 0L || (now - prev) >= warnCooldownNanos) {
            if (lastWarnNanos.compareAndSet(prev, now)) {
                logger.warn("[Telemetry] {}", message)
            }
        }
    }

    companion object {
        const val ENV_URL = "FUJI_TELEMETRY_URL"
        const val ENV_INGEST_TOKEN = "FUJI_INGEST_TOKEN"
        const val CONFIG_KEY = "telemetry"
        const val LEGACY_UPLOAD_CONFIG_KEY = "telemetryUpload"
        const val DEFAULT_SOURCE_TYPE = "EMU_STOCK_FW"
        const val DEFAULT_CONNECT_TIMEOUT_MS = 1_500L
        const val DEFAULT_REQUEST_TIMEOUT_MS = 2_000L
        const val DEFAULT_UPLOAD_TIMEOUT_MS = 2_500L
        val DEFAULT_WARN_COOLDOWN_NANOS: Long = TimeUnit.SECONDS.toNanos(30L)
        private val ALLOWED_MAP_KEYS = setOf("enabled", "endpoint", "ingestToken", "uploadLogOnExit")

        /**
         * Strict schema validator invoked by `validateLaunchConfiguration` for optional
         * `telemetry` and `telemetryUpload` keys in `emulator.yaml`.
         */
        fun validateLaunchConfig(config: Map<String, Any?>) {
            if (config.containsKey(CONFIG_KEY)) {
                val raw = config[CONFIG_KEY]
                when (raw) {
                    is Boolean -> Unit
                    is Map<*, *> -> {
                        require(raw.keys.all { it is String && it in ALLOWED_MAP_KEYS }) {
                            "invalid telemetry config keys"
                        }
                        raw["enabled"]?.let {
                            require(it is Boolean) { "telemetry.enabled requires boolean" }
                        }
                        raw["endpoint"]?.let {
                            require(it is String) { "telemetry.endpoint requires string" }
                            val trimmed = it.trim()
                            if (trimmed.isNotEmpty()) {
                                require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                    "telemetry.endpoint must start with http:// or https://"
                                }
                            }
                        }
                        raw["ingestToken"]?.let {
                            require(it is String) { "telemetry.ingestToken requires string" }
                        }
                        raw["uploadLogOnExit"]?.let {
                            require(it is Boolean) { "telemetry.uploadLogOnExit requires boolean" }
                        }
                    }
                    else -> throw IllegalArgumentException("telemetry requires boolean or map")
                }
            }
            if (config.containsKey(LEGACY_UPLOAD_CONFIG_KEY)) {
                require(config[LEGACY_UPLOAD_CONFIG_KEY] is Boolean) {
                    "telemetryUpload requires boolean"
                }
            }
        }

        /**
         * Resolves effective runtime telemetry settings using strict priority:
         * 1. Execution mode gate: `discovery` and `strict` ALWAYS return `null` (zero network).
         * 2. `--no-telemetry` CLI flag: highest priority, forces `null`.
         * 3. Environment variables (`FUJI_TELEMETRY_URL`, `FUJI_INGEST_TOKEN`) override matching
         *    fields in `emulator.yaml`'s `telemetry` block when non-blank.
         * 4. `emulator.yaml` `telemetry` block (default `enabled = true`, `uploadLogOnExit = true`).
         * 5. If either `endpoint` or `ingestToken` is blank, returns `null` silently.
         */
        fun resolveConfig(
            executionMode: String,
            config: Map<String, Any?>,
            cliDisable: Boolean = false,
            cliEnableUpload: Boolean = false,
            env: Map<String, String> = System.getenv(),
            firmwareId: String? = null,
            repoCommit: String? = null,
            osName: String = System.getProperty("os.name").orEmpty(),
        ): TelemetryConfig? {
            if (executionMode != RelaxedPeripheralFallback.MODE) return null
            if (cliDisable) return null

            var yamlEnabled = true
            var yamlEndpoint = ""
            var yamlToken = ""
            var uploadLogOnExit = true
            var uploadExplicitInMap = false

            when (val raw = config[CONFIG_KEY]) {
                is Boolean -> yamlEnabled = raw
                is Map<*, *> -> {
                    (raw["enabled"] as? Boolean)?.let { yamlEnabled = it }
                    (raw["endpoint"] as? String)?.let { yamlEndpoint = it.trim() }
                    (raw["ingestToken"] as? String)?.let { yamlToken = it.trim() }
                    (raw["uploadLogOnExit"] as? Boolean)?.let {
                        uploadLogOnExit = it
                        uploadExplicitInMap = true
                    }
                }
            }
            if (!uploadExplicitInMap && config[LEGACY_UPLOAD_CONFIG_KEY] is Boolean) {
                uploadLogOnExit = config[LEGACY_UPLOAD_CONFIG_KEY] as Boolean
            }
            if (cliEnableUpload) {
                uploadLogOnExit = true
            }
            if (!yamlEnabled && !cliEnableUpload) return null

            val endpoint = env[ENV_URL]?.trim()?.takeIf { it.isNotEmpty() } ?: yamlEndpoint
            val ingestToken = env[ENV_INGEST_TOKEN]?.trim()?.takeIf { it.isNotEmpty() } ?: yamlToken
            val normalizedEndpoint = endpoint.trimEnd('/')

            if (normalizedEndpoint.isEmpty() || ingestToken.isEmpty()) return null
            if (!normalizedEndpoint.startsWith("http://") && !normalizedEndpoint.startsWith("https://")) return null

            return TelemetryConfig(
                enabled = true,
                endpoint = normalizedEndpoint,
                ingestToken = ingestToken,
                uploadLogOnExit = uploadLogOnExit,
                sourceType = DEFAULT_SOURCE_TYPE,
                deviceKind = detectDeviceKind(osName),
                firmwareId = firmwareId?.takeIf { it.isNotBlank() },
                executionMode = executionMode,
                repoCommit = repoCommit?.takeIf { it.isNotBlank() },
            )
        }

        fun detectDeviceKind(osName: String = System.getProperty("os.name").orEmpty()): String {
            val lower = osName.lowercase(Locale.ROOT)
            return when {
                lower.contains("win") -> "EMU_WIN"
                lower.contains("mac") || lower.contains("darwin") -> "EMU_MAC"
                else -> "EMU_LINUX"
            }
        }

        internal fun gzip(data: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(maxOf(64, data.size / 2))
            GZIPOutputStream(out).use { it.write(data) }
            return out.toByteArray()
        }

        internal fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        internal fun buildMultipartBody(
            boundary: String,
            fieldName: String,
            filename: String,
            content: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream(content.size + 256)
            val header = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"").append(fieldName)
                    .append("\"; filename=\"").append(filename).append("\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(content)
            out.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
            return out.toByteArray()
        }
    }
}
