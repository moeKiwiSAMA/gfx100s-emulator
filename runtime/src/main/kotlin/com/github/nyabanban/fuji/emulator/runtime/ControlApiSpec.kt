package com.github.nyabanban.fuji.emulator.runtime

/**
 * Single-source-of-truth specification for the FUJIFILM GFX100S Emulator loopback HTTP Control API
 * served by [ControlServer].
 *
 * Every route registered in [ControlServer] must have a 1:1 entry in [endpoints]; this invariant
 * is enforced automatically by `ControlApiDocGeneratorTest`.
 */
enum class ApiMethod { GET, POST, PATCH }

enum class ApiParamLocation(val openApiName: String) {
    PATH("path"),
    QUERY("query"),
}

data class ApiParam(
    val name: String,
    val location: ApiParamLocation,
    val type: String,
    val required: Boolean = location == ApiParamLocation.PATH,
    val description: String,
    val example: String? = null,
    val enumValues: List<String> = emptyList(),
    val pattern: String? = null,
    val minimum: Number? = null,
    val maximum: Number? = null,
)

data class ApiField(
    val name: String,
    val type: String,
    val required: Boolean = false,
    val description: String,
    val example: Any? = null,
    val enumValues: List<String> = emptyList(),
    val minimum: Number? = null,
    val maximum: Number? = null,
    val itemType: String? = null,
)

data class ApiResponse(
    val status: Int,
    val description: String,
    val contentType: String = "application/json",
    val example: Any? = null,
)

data class ControlApiEndpoint(
    val operationId: String,
    val method: ApiMethod,
    val path: String,
    val tag: String,
    val summary: String,
    val description: String,
    val params: List<ApiParam> = emptyList(),
    val requestContentType: String? = null,
    val requestBodyRequired: Boolean = false,
    val requestFields: List<ApiField> = emptyList(),
    val requestExample: Any? = null,
    val responses: List<ApiResponse>,
    val notes: List<String> = emptyList(),
)

object ControlApiSpec {
    const val API_TITLE = "FUJIFILM GFX100S Emulator HTTP Control API"
    const val API_VERSION = "1.0.0"
    const val OPENAPI_VERSION = "3.1.0"
    const val DEFAULT_BASE_URL = "http://127.0.0.1:18080"

    val API_DESCRIPTION = """
        Loopback (`127.0.0.1`) HTTP Control API for inspecting and automating a live FUJIFILM GFX100S emulator session.

        - **Security**: Bound exclusively to loopback (`127.0.0.1`). Cross-origin browser requests are denied (`400`); JSON write endpoints require `Content-Type: application/json` and bodies are capped at 64 KiB.
        - **Error Contract**: `400` invalid input / cross-origin / bad Content-Type, `404` unavailable component or unknown route, `409` optimistic-concurrency revision conflict (`PATCH /api/v1/components/{id}`), `503` runtime busy / executor rejected / upload unavailable, `500` internal error.
        - **Live Self-Documentation**: Served directly at `GET /api/docs` (offline interactive HTML explorer) and `GET /api/openapi.json` (OpenAPI 3.1.0 schema).
    """.trimIndent()

    val tags: List<Pair<String, String>> = listOf(
        "Core & Catalog" to "Live emulator state snapshots, component patching, memory dumps, and hardware address dictionary queries.",
        "Profiler" to "Asynchronous CPU/instruction profiler start, stop, and JSON or plain-text reports.",
        "Touch & Controls" to "Rear LCD touch taps/swipes, EVF eye sensor, ADC buttons/dials, focus mode, shooting mode, and VIEW MODE.",
        "Optics & Filters" to "Mounted lens control, multi-slot optical filter rack (UV, ND, Bokeh Mask, Star, Mist, GND), and ND shorthand.",
        "Audio" to "Host audio output/input mixer, device enumeration, headphone/mic jack simulation, and loopback monitoring.",
        "Wireless" to "BCM43438 Wi-Fi / BLE combo radio status and virtual mobile peer / PTP-IP bridge controls.",
        "Telemetry" to "Manual diagnostic session log upload.",
        "UI & Personalization" to "Runtime UI locale switching and camera body / studio theme customization.",
        "NOR Flash" to "Unified 64 MiB QSPI NOR Flash status inspection, .DAT firmware flashing, raw export/import, and factory restore.",
        "Documentation" to "Self-hosted OpenAPI 3.1.0 specification and offline interactive HTML API explorer.",
    )

    private fun errorExample(message: String): Map<String, Any> = linkedMapOf("error" to message)

    val endpoints: List<ControlApiEndpoint> = listOf(
        // ==================== Core & Catalog ====================
        ControlApiEndpoint(
            operationId = "getCatalog",
            method = ApiMethod.GET,
            path = "/api/v1/catalog",
            tag = "Core & Catalog",
            summary = "Query hardware address catalog",
            description = "Queries the bundled GFX100S 2.13 MMIO/memory address dictionary using up to 16 distinct query parameters.",
            params = listOf(
                ApiParam(
                    name = "address",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Hex or decimal MMIO/memory address to look up (e.g. `0xfff40000`).",
                    example = "0xfff40000",
                ),
                ApiParam(
                    name = "subsystem",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Optional subsystem filter (e.g. `isp`, `dma`, `gpio`).",
                    example = "isp",
                ),
                ApiParam(
                    name = "query",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Optional free-text search term across device/region names and notes.",
                    example = "RECC_S",
                ),
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Matching address catalog entries.",
                    example = linkedMapOf(
                        "count" to 1,
                        "entries" to listOf(
                            linkedMapOf(
                                "name" to "soc-isp.core",
                                "start" to "0xfff70000",
                                "end" to "0xfff702ec",
                                "basis" to "DAT",
                            ),
                        ),
                    ),
                ),
                ApiResponse(400, "Duplicate, malformed, or excessive (>16) query parameters.", example = errorExample("Duplicate or excessive query parameters")),
                ApiResponse(500, "Address dictionary unavailable.", example = errorExample("address dictionary unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getState",
            method = ApiMethod.GET,
            path = "/api/v1/state",
            tag = "Core & Catalog",
            summary = "Get live emulator and component state snapshot",
            description = "Returns the complete component roster (id, revision, config, state) merged with the live `machine` execution telemetry map.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current component snapshot and machine telemetry.",
                    example = linkedMapOf(
                        "components" to listOf(
                            linkedMapOf(
                                "id" to "buttons",
                                "revision" to 1,
                                "config" to linkedMapOf("power" to true, "movieMode" to false),
                                "state" to linkedMapOf("power" to true, "movieMode" to false),
                            ),
                        ),
                        "machine" to linkedMapOf(
                            "status" to "running",
                            "tick" to 125000000,
                            "screenId" to "0x0100",
                        ),
                    ),
                ),
            ),
        ),
        ControlApiEndpoint(
            operationId = "createDump",
            method = ApiMethod.POST,
            path = "/api/v1/dump",
            tag = "Core & Catalog",
            summary = "Trigger a memory and state dump",
            description = "Captures an emulator state/memory dump archive via the runtime dump handler. Body must be empty or `{}`.",
            requestContentType = "application/json",
            requestBodyRequired = false,
            requestExample = emptyMap<String, Any>(),
            responses = listOf(
                ApiResponse(
                    status = 201,
                    description = "Dump created successfully.",
                    example = linkedMapOf("dump" to "dumps/dump-20261125-120000"),
                ),
                ApiResponse(400, "Non-empty JSON object or missing `application/json` Content-Type.", example = errorExample("dump body must be empty or {}")),
                ApiResponse(500, "Dump handler unavailable.", example = errorExample("dump unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "patchComponent",
            method = ApiMethod.PATCH,
            path = "/api/v1/components/{id}",
            tag = "Core & Catalog",
            summary = "Patch live settings of a hardware component",
            description = "Applies a live settings patch to component `{id}` with optimistic concurrency control using its current `revision` from `GET /api/v1/state`.",
            params = listOf(
                ApiParam(
                    name = "id",
                    location = ApiParamLocation.PATH,
                    type = "string",
                    required = true,
                    description = "Component identifier matching `[a-z][a-z0-9-]{0,63}` (e.g. `buttons`, `cmos`, `soc-adc`).",
                    example = "buttons",
                    pattern = "^[a-z][a-z0-9-]{0,63}$",
                ),
            ),
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("revision", "integer", required = true, description = "Expected non-negative component revision.", example = 1, minimum = 0),
                ApiField("changes", "object", required = true, description = "Key-value map of live component settings to update.", example = linkedMapOf("menu" to true)),
            ),
            requestExample = linkedMapOf(
                "revision" to 1,
                "changes" to linkedMapOf("menu" to true),
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated component descriptor with incremented revision.",
                    example = linkedMapOf(
                        "id" to "buttons",
                        "revision" to 2,
                        "state" to linkedMapOf("menu" to true),
                    ),
                ),
                ApiResponse(400, "Malformed payload, invalid setting key/value, or wrong Content-Type.", example = errorExample("application/json required")),
                ApiResponse(409, "Revision mismatch (stale revision).", example = errorExample("revision conflict")),
            ),
        ),

        // ==================== Profiler ====================
        ControlApiEndpoint(
            operationId = "startProfiler",
            method = ApiMethod.POST,
            path = "/api/profiler/start",
            tag = "Profiler",
            summary = "Start asynchronous performance profiler",
            description = "Starts sampling CPU/PC hot spots and subsystem timings. `intervalMs` may be provided as a query parameter or JSON body field (body overrides query).",
            params = listOf(
                ApiParam(
                    name = "intervalMs",
                    location = ApiParamLocation.QUERY,
                    type = "integer",
                    description = "Sampling interval in milliseconds (defaults to profiler default).",
                    example = "5",
                    minimum = 1,
                ),
            ),
            requestContentType = "application/json",
            requestBodyRequired = false,
            requestFields = listOf(
                ApiField("intervalMs", "integer", description = "Optional sampling interval in milliseconds.", example = 5, minimum = 1),
            ),
            requestExample = linkedMapOf("intervalMs" to 5),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Profiler started.",
                    example = linkedMapOf("running" to true, "intervalMs" to 5),
                ),
                ApiResponse(400, "Invalid `intervalMs` or non-JSON body.", example = errorExample("intervalMs must be integer")),
                ApiResponse(404, "Profiler not attached to this session.", example = errorExample("profiler unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "stopProfiler",
            method = ApiMethod.POST,
            path = "/api/profiler/stop",
            tag = "Profiler",
            summary = "Stop profiler and fetch report",
            description = "Stops active profiling and returns the collected report as JSON, or as `text/plain` when `?format=text|txt` or `Accept: text/plain` is sent.",
            params = listOf(
                ApiParam(
                    name = "format",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Response format override (`json`, `text`, or `txt`).",
                    example = "json",
                    enumValues = listOf("json", "text", "txt"),
                ),
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Profiler stopped with summary report (JSON or plain text).",
                    example = linkedMapOf("running" to false, "samples" to 1240, "elapsedMs" to 6200),
                ),
                ApiResponse(404, "Profiler not attached to this session.", example = errorExample("profiler unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getProfilerReport",
            method = ApiMethod.GET,
            path = "/api/profiler/report",
            tag = "Profiler",
            summary = "Read latest profiler report",
            description = "Returns the current or last completed profiler report without stopping a running session. Supports `text/plain` via `?format=text|txt` or `Accept: text/plain`.",
            params = listOf(
                ApiParam(
                    name = "format",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Response format override (`json`, `text`, or `txt`).",
                    example = "json",
                    enumValues = listOf("json", "text", "txt"),
                ),
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Latest profiler report.",
                    example = linkedMapOf("running" to true, "samples" to 610, "elapsedMs" to 3050),
                ),
                ApiResponse(404, "Profiler not attached to this session.", example = errorExample("profiler unavailable")),
            ),
        ),

        // ==================== Touch & Controls ====================
        ControlApiEndpoint(
            operationId = "postTouch",
            method = ApiMethod.POST,
            path = "/api/touch",
            tag = "Touch & Controls",
            summary = "Send a rear-LCD touch event",
            description = "Injects a single capacitive touch down/move/up event onto the rear LCD (`rear-touch-ic` preferred, falling back to `i2c-bus`). Payload must contain exact keys `x`, `y`, `pressed`.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("x", "integer", required = true, description = "Horizontal touch coordinate (`0..65535`; rear LCD native range `0..1023`).", example = 512, minimum = 0, maximum = 65535),
                ApiField("y", "integer", required = true, description = "Vertical touch coordinate (`0..65535`; rear LCD native range `0..767`).", example = 384, minimum = 0, maximum = 65535),
                ApiField("pressed", "boolean", required = true, description = "`true` for finger down/move, `false` for finger release.", example = true),
            ),
            requestExample = linkedMapOf("x" to 512, "y" to 384, "pressed" to true),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Touch event applied.",
                    example = linkedMapOf("ok" to true, "component" to "rear-touch-ic", "x" to 512, "y" to 384, "pressed" to true, "revision" to 4),
                ),
                ApiResponse(400, "Missing/extra keys or out-of-range coordinates.", example = errorExample("touch payload requires exact keys: x, y, pressed")),
                ApiResponse(404, "No touch controller component available.", example = errorExample("i2c-bus touch target unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postTouchSwipe",
            method = ApiMethod.POST,
            path = "/api/touch/swipe",
            tag = "Touch & Controls",
            summary = "Send a multi-step rear-LCD touch swipe/flick",
            description = "Executes a calibrated multi-step capacitive swipe gesture. Provide either a cardinal `direction` (`up`/`down`/`left`/`right`) OR explicit `x1`/`y1`/`x2`/`y2` endpoints (never both). Default `4` steps x `20 ms` satisfies both LiveView T-Fn flicks (<260 ms) and Playback slide-commit (>341 px).",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("direction", "string", description = "Preset swipe direction (mutually exclusive with `x1`/`y1`/`x2`/`y2`).", example = "left", enumValues = listOf("up", "down", "left", "right")),
                ApiField("x1", "integer", description = "Start X coordinate (`0..65535`). Required when `direction` is omitted.", example = 760, minimum = 0, maximum = 65535),
                ApiField("y1", "integer", description = "Start Y coordinate (`0..65535`). Required when `direction` is omitted.", example = 384, minimum = 0, maximum = 65535),
                ApiField("x2", "integer", description = "End X coordinate (`0..65535`). Required when `direction` is omitted.", example = 240, minimum = 0, maximum = 65535),
                ApiField("y2", "integer", description = "End Y coordinate (`0..65535`). Required when `direction` is omitted.", example = 384, minimum = 0, maximum = 65535),
                ApiField("steps", "integer", description = "Number of intermediate move steps (`1..8`, default `4`).", example = 4, minimum = 1, maximum = 8),
                ApiField("stepMs", "integer", description = "Per-step dwell in milliseconds (`5..100`, default `20`).", example = 20, minimum = 5, maximum = 100),
            ),
            requestExample = linkedMapOf("direction" to "left", "steps" to 4, "stepMs" to 20),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Swipe gesture completed.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "rear-touch-ic",
                        "direction" to "left",
                        "x1" to 760,
                        "y1" to 384,
                        "x2" to 240,
                        "y2" to 384,
                        "steps" to 4,
                        "stepMs" to 20,
                        "revision" to 10,
                    ),
                ),
                ApiResponse(400, "Invalid direction, mixed direction+coordinates, or out-of-range steps/stepMs.", example = errorExample("touch swipe payload cannot mix direction with x1/y1/x2/y2")),
                ApiResponse(404, "No touch controller component available.", example = errorExample("i2c-bus touch target unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getEyeSensor",
            method = ApiMethod.GET,
            path = "/api/eye-sensor",
            tag = "Touch & Controls",
            summary = "Get EVF eye proximity sensor state",
            description = "Inspects the TMD2772 (or `i2c-bus` fallback) eye proximity state along with active viewfinder detection (`LCD`, `EVF`, `BOTH`, or `WAITING`) and display frame sequences.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current eye sensor and viewfinder routing state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "tmd2772",
                        "near" to false,
                        "activeViewfinder" to "LCD",
                        "lcdSequence" to 42,
                        "evfSequence" to 0,
                        "lcdOutputActive" to true,
                        "evfOutputActive" to false,
                    ),
                ),
                ApiResponse(404, "Eye sensor component unavailable.", example = errorExample("i2c-bus eye sensor unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postEyeSensor",
            method = ApiMethod.POST,
            path = "/api/eye-sensor",
            tag = "Touch & Controls",
            summary = "Set EVF eye proximity sensor",
            description = "Sets whether an eye is detected near the EVF (`near: true` switches automatic viewfinder routing to EVF; `false` returns to rear LCD).",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("near", "boolean", required = true, description = "`true` for eye close to EVF, `false` for eye away.", example = true),
            ),
            requestExample = linkedMapOf("near" to true),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Eye sensor updated.",
                    example = linkedMapOf("ok" to true, "component" to "tmd2772", "near" to true, "revision" to 2),
                ),
                ApiResponse(400, "Payload missing exact `near` boolean key.", example = errorExample("eye-sensor payload requires exact key: near")),
                ApiResponse(404, "Eye sensor component unavailable.", example = errorExample("i2c-bus eye sensor unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getAdc",
            method = ApiMethod.GET,
            path = "/api/adc",
            tag = "Touch & Controls",
            summary = "Inspect ADC buttons, dials, and channel samples",
            description = "Returns live `soc-adc` control states (`qButton`, `aelButton`, `modeDial`, `fn1Button`..`fn3Button`, `lightButton`, rear command dial, and channel overrides) plus sampled ADC register counts.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current `soc-adc` control values and sample counters.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "soc-adc",
                        "revision" to 3,
                        "controls" to linkedMapOf(
                            "qButton" to false,
                            "aelButton" to false,
                            "modeDial" to "default",
                            "fn1Button" to false,
                            "fn2Button" to false,
                            "fn3Button" to false,
                            "lightButton" to false,
                            "rearDialPosition" to -1,
                            "rearDialDetents" to 0,
                        ),
                        "eventCount" to 12,
                        "sampleReadCount" to 420,
                    ),
                ),
                ApiResponse(404, "`soc-adc` component unavailable.", example = errorExample("soc-adc unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postAdc",
            method = ApiMethod.POST,
            path = "/api/adc",
            tag = "Touch & Controls",
            summary = "Update ADC buttons, dials, or raw channel overrides",
            description = "Updates a non-empty subset of `soc-adc` inputs, including momentary buttons, mode dial, relative `rearDialStep` detent increments, or raw 12-bit channel overrides (`-1` clears override).",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("qButton", "boolean", description = "Press/release the Q (Quick Menu) button.", example = true),
                ApiField("aelButton", "boolean", description = "Press/release the AE-L button.", example = false),
                ApiField("fn1Button", "boolean", description = "Press/release the Fn1 / Exposure Comp (`+/-`) button.", example = false),
                ApiField("fn2Button", "boolean", description = "Press/release the Fn2 button.", example = false),
                ApiField("fn3Button", "boolean", description = "Press/release the Fn3 button.", example = false),
                ApiField("lightButton", "boolean", description = "Press/release the top LCD backlight button.", example = false),
                ApiField("modeDial", "string", description = "Mode dial position (e.g. `default`, `P`, `S`, `A`, `M`, `C1`..`C6`).", example = "M"),
                ApiField("rearDialPosition", "integer", description = "Explicit rear dial quadrature phase (`-1..3`, `-1` uses detents).", example = -1, minimum = -1, maximum = 3),
                ApiField("rearDialDetents", "integer", description = "Absolute rear command dial detent counter (`-10000..10000`).", example = 2, minimum = -10000, maximum = 10000),
                ApiField("rearDialStep", "integer", description = "Relative rear command dial detent delta (`-1000..1000`).", example = 1, minimum = -1000, maximum = 1000),
                ApiField("ch3Override", "integer", description = "Raw 12-bit ADC CH3 override (`-1..4095`).", example = -1, minimum = -1, maximum = 4095),
                ApiField("ch5Override", "integer", description = "Raw 12-bit ADC CH5 override (`-1..4095`).", example = -1, minimum = -1, maximum = 4095),
                ApiField("ch6Override", "integer", description = "Raw 12-bit ADC CH6 override (`-1..4095`).", example = -1, minimum = -1, maximum = 4095),
                ApiField("ch7Override", "integer", description = "Raw 12-bit ADC CH7 override (`-1..4095`).", example = -1, minimum = -1, maximum = 4095),
                ApiField("ch11Override", "integer", description = "Raw 12-bit ADC CH11 override (`-1..4095`).", example = -1, minimum = -1, maximum = 4095),
            ),
            requestExample = linkedMapOf("qButton" to true, "modeDial" to "M"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "ADC settings patched.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "soc-adc",
                        "revision" to 4,
                        "changes" to linkedMapOf("qButton" to true, "modeDial" to "M"),
                    ),
                ),
                ApiResponse(400, "Empty payload, unknown key, wrong type, or out-of-range integer.", example = errorExample("adc payload requires non-empty subset of [...]")),
                ApiResponse(404, "`soc-adc` component unavailable.", example = errorExample("soc-adc unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getFocusMode",
            method = ApiMethod.GET,
            path = "/api/focus-mode",
            tag = "Touch & Controls",
            summary = "Get S/C/M focus mode lever state",
            description = "Inspects the `soc-gpio` focus mode selector (`focusMode` configuration and resolved `effectiveFocusMode`).",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current focus mode lever state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "soc-gpio",
                        "revision" to 2,
                        "focusMode" to "default",
                        "effectiveFocusMode" to "AF-S",
                    ),
                ),
                ApiResponse(404, "`soc-gpio` component unavailable.", example = errorExample("soc-gpio unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postFocusMode",
            method = ApiMethod.POST,
            path = "/api/focus-mode",
            tag = "Touch & Controls",
            summary = "Set S/C/M focus mode lever",
            description = "Sets the physical S/C/M focus mode lever via exact key `focusMode` or `mode`. Accepts `AF-S` (`AFS`/`S`), `AF-C` (`AFC`/`C`), `MF` (`M`), or `default`.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("focusMode", "string", description = "Target focus mode (provide either `focusMode` or `mode`).", example = "MF", enumValues = listOf("AF-S", "AF-C", "MF", "default")),
                ApiField("mode", "string", description = "Alias for `focusMode`.", example = "AF-C", enumValues = listOf("AF-S", "AF-C", "MF", "default")),
            ),
            requestExample = linkedMapOf("focusMode" to "MF"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Focus mode updated.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "soc-gpio",
                        "revision" to 3,
                        "focusMode" to "MF",
                        "effectiveFocusMode" to "MF",
                    ),
                ),
                ApiResponse(400, "Invalid key or unsupported focus mode value.", example = errorExample("focus-mode payload requires exact key: focusMode (or mode)")),
                ApiResponse(404, "`soc-gpio` component unavailable.", example = errorExample("soc-gpio unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getShootingMode",
            method = ApiMethod.GET,
            path = "/api/shooting-mode",
            tag = "Touch & Controls",
            summary = "Get combined STILL/MOVIE, mode dial, and focus mode state",
            description = "Reads the `buttons` STILL/MOVIE lever (`movieMode`), `soc-adc` `modeDial`, and `soc-gpio` `effectiveFocusMode` in a single request.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Combined shooting mode summary.",
                    example = linkedMapOf(
                        "ok" to true,
                        "mode" to "STILL",
                        "movieMode" to false,
                        "modeDial" to "default",
                        "focusMode" to "AF-S",
                    ),
                ),
                ApiResponse(404, "`buttons` component unavailable.", example = errorExample("buttons unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postShootingMode",
            method = ApiMethod.POST,
            path = "/api/shooting-mode",
            tag = "Touch & Controls",
            summary = "Update STILL/MOVIE switch, mode dial, and/or focus mode",
            description = "Updates one or more of `mode` (`STILL`/`MOVIE`/`PHOTO`/`VIDEO`), `movieMode` (boolean), `modeDial`, and `focusMode`.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("mode", "string", description = "Shooting mode switch (`STILL`, `PHOTO`, `MOVIE`, or `VIDEO`).", example = "MOVIE", enumValues = listOf("STILL", "MOVIE", "PHOTO", "VIDEO")),
                ApiField("movieMode", "boolean", description = "Boolean equivalent of `mode` (`true` = MOVIE, `false` = STILL).", example = true),
                ApiField("modeDial", "string", description = "Exposure mode dial position (e.g. `P`, `S`, `A`, `M`, `C1`..`C6`).", example = "M"),
                ApiField("focusMode", "string", description = "Focus lever position (`AF-S`, `AF-C`, `MF`, or `default`).", example = "AF-C", enumValues = listOf("AF-S", "AF-C", "MF", "default")),
            ),
            requestExample = linkedMapOf("mode" to "MOVIE", "modeDial" to "M", "focusMode" to "AF-C"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated shooting mode summary with applied `changes`.",
                    example = linkedMapOf(
                        "ok" to true,
                        "mode" to "MOVIE",
                        "movieMode" to true,
                        "modeDial" to "M",
                        "focusMode" to "AF-C",
                        "changes" to linkedMapOf("movieMode" to true, "mode" to "MOVIE", "modeDial" to "M", "focusMode" to "AF-C"),
                    ),
                ),
                ApiResponse(400, "Empty payload or invalid field value.", example = errorExample("mode must be STILL or MOVIE")),
                ApiResponse(404, "`buttons` component unavailable.", example = errorExample("buttons unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postViewMode",
            method = ApiMethod.POST,
            path = "/api/view-mode",
            tag = "Touch & Controls",
            summary = "Press or release the VIEW MODE button",
            description = "Asserts or releases the hardware VIEW MODE GPIO button (`pressed: true` / `false`) to cycle LCD/EVF viewfinder modes.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("pressed", "boolean", required = true, description = "`true` to press VIEW MODE, `false` to release.", example = true),
            ),
            requestExample = linkedMapOf("pressed" to true),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "VIEW MODE button state updated.",
                    example = linkedMapOf("ok" to true, "pressed" to true),
                ),
                ApiResponse(400, "Payload missing exact `pressed` boolean key.", example = errorExample("view-mode payload requires exact key: pressed")),
                ApiResponse(404, "VIEW MODE GPIO target unavailable.", example = errorExample("view-mode GPIO target unavailable")),
            ),
        ),

        // ==================== Telemetry ====================
        ControlApiEndpoint(
            operationId = "postTelemetryUploadLog",
            method = ApiMethod.POST,
            path = "/api/telemetry/upload-log",
            tag = "Telemetry",
            summary = "Upload current diagnostic session log",
            description = "Triggers a manual upload of the current emulator diagnostic log when telemetry is explicitly enabled. Optional `notes` may be passed in the query string or JSON body.",
            params = listOf(
                ApiParam(
                    name = "notes",
                    location = ApiParamLocation.QUERY,
                    type = "string",
                    description = "Optional operator notes attached to the uploaded diagnostic bundle.",
                    example = "repro-raw-conv",
                ),
            ),
            requestContentType = "application/json",
            requestBodyRequired = false,
            requestFields = listOf(
                ApiField("notes", "string", description = "Optional operator notes (overrides query parameter if provided).", example = "repro-raw-conv"),
            ),
            requestExample = linkedMapOf("notes" to "repro-raw-conv"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Log uploaded (`uploaded == true`).",
                    example = linkedMapOf("uploaded" to true, "sessionId" to "sess-001"),
                ),
                ApiResponse(400, "Non-string `notes` or invalid JSON body.", example = errorExample("notes must be string")),
                ApiResponse(404, "Telemetry handler unavailable.", example = errorExample("telemetry unavailable")),
                ApiResponse(503, "Upload attempted but failed or telemetry disabled (`uploaded == false`).", example = linkedMapOf("uploaded" to false, "reason" to "disabled")),
            ),
        ),

        // ==================== Audio ====================
        ControlApiEndpoint(
            operationId = "getAudio",
            method = ApiMethod.GET,
            path = "/api/audio",
            tag = "Audio",
            summary = "Get audio output and input mixer state",
            description = "Returns live WM8962 + host audio engine state, volume, mute, backend, buffer size, headphone/mic jack state, and input capture settings.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current audio subsystem status.",
                    example = linkedMapOf(
                        "enabled" to true,
                        "volume" to 0.8,
                        "muted" to false,
                        "bufferMs" to 24,
                        "backendPreference" to "auto",
                        "headphonePlugged" to false,
                        "inputMode" to "SILENCE",
                        "extMicPlugged" to false,
                    ),
                ),
                ApiResponse(404, "Audio engine unavailable.", example = errorExample("audio unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getAudioDevices",
            method = ApiMethod.GET,
            path = "/api/audio/devices",
            tag = "Audio",
            summary = "List host audio output and input devices",
            description = "Enumerates host playback and capture devices available to the emulator audio bridge.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Available host audio devices.",
                    example = linkedMapOf(
                        "outputs" to listOf(linkedMapOf("id" to "default", "name" to "System Default")),
                        "inputs" to listOf(linkedMapOf("id" to "default", "name" to "System Default")),
                    ),
                ),
                ApiResponse(404, "Audio device enumeration unavailable.", example = errorExample("audio devices unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postAudio",
            method = ApiMethod.POST,
            path = "/api/audio",
            tag = "Audio",
            summary = "Update audio output, input, and jack settings",
            description = "Applies a non-empty subset of live host audio mixer and jack-detection settings.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("enabled", "boolean", description = "Enable or disable host audio playback.", example = true),
                ApiField("volume", "number", description = "Master output volume scalar (`0.0..1.0`).", example = 0.75, minimum = 0.0, maximum = 1.0),
                ApiField("muted", "boolean", description = "Mute output playback.", example = false),
                ApiField("outputDeviceId", "string", description = "Host playback device ID from `GET /api/audio/devices`.", example = "default"),
                ApiField("bufferMs", "integer", description = "Audio ring buffer latency target in milliseconds.", example = 24, minimum = 4),
                ApiField("backendPreference", "string", description = "Preferred audio backend (`auto`, `native`, `javax`, `silent`).", example = "auto"),
                ApiField("headphonePlugged", "boolean", description = "Simulate 3.5 mm headphone jack insertion.", example = false),
                ApiField("inputMode", "string", description = "Microphone input source mode (e.g. `SILENCE`, `TEST_TONE`, `HOST_MIC`).", example = "TEST_TONE"),
                ApiField("inputDeviceId", "string", description = "Host capture device ID from `GET /api/audio/devices`.", example = "default"),
                ApiField("inputGainDb", "number", description = "Input microphone gain in dB.", example = 0.0),
                ApiField("inputMuted", "boolean", description = "Mute microphone capture.", example = false),
                ApiField("inputMonitor", "boolean", description = "Enable live input-to-output monitoring.", example = false),
                ApiField("testToneHz", "number", description = "Frequency in Hz when `inputMode` is `TEST_TONE`.", example = 440.0),
                ApiField("extMicPlugged", "boolean", description = "Simulate 3.5 mm external microphone jack insertion.", example = false),
            ),
            requestExample = linkedMapOf("volume" to 0.75, "muted" to false, "headphonePlugged" to true),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated audio subsystem state.",
                    example = linkedMapOf("enabled" to true, "volume" to 0.75, "muted" to false, "headphonePlugged" to true),
                ),
                ApiResponse(400, "Empty body or unknown audio setting key.", example = errorExample("audio update accepts non-empty subset of [...]")),
                ApiResponse(404, "Audio engine unavailable.", example = errorExample("audio unavailable")),
            ),
        ),

        // ==================== Wireless ====================
        ControlApiEndpoint(
            operationId = "getWireless",
            method = ApiMethod.GET,
            path = "/api/wireless",
            tag = "Wireless",
            summary = "Get BCM43438 Wi-Fi / BLE wireless state",
            description = "Returns the current BCM43438 wireless combo state (firmware download progress, BLE peer connection, Wi-Fi STA/AP state, and PTP-IP bridge status).",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "BCM43438 wireless status.",
                    example = linkedMapOf(
                        "deviceName" to "GFX100S-EMU",
                        "blePeerConnected" to false,
                        "wifiPeerConnected" to false,
                        "ptpIpBridgeEnabled" to false,
                    ),
                ),
                ApiResponse(404, "`bcm43438` wireless component unavailable.", example = errorExample("bcm43438 wireless unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postWireless",
            method = ApiMethod.POST,
            path = "/api/wireless",
            tag = "Wireless",
            summary = "Update BCM43438 virtual wireless peer state",
            description = "Controls virtual mobile BLE/Wi-Fi peer attachment, advertised camera device name, or host PTP-IP bridge enablement.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("deviceName", "string", description = "Advertised camera wireless name.", example = "GFX100S-EMU"),
                ApiField("blePeerConnected", "boolean", description = "Connect or disconnect the virtual BLE mobile peer.", example = true),
                ApiField("wifiPeerConnected", "boolean", description = "Connect or disconnect the virtual Wi-Fi peer.", example = false),
                ApiField("ptpIpBridgeEnabled", "boolean", description = "Enable or disable the host PTP-IP socket bridge.", example = true),
            ),
            requestExample = linkedMapOf("blePeerConnected" to true, "ptpIpBridgeEnabled" to true),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated BCM43438 wireless state.",
                    example = linkedMapOf(
                        "deviceName" to "GFX100S-EMU",
                        "blePeerConnected" to true,
                        "wifiPeerConnected" to false,
                        "ptpIpBridgeEnabled" to true,
                    ),
                ),
                ApiResponse(400, "No recognized wireless keys provided.", example = errorExample("wireless update requires non-empty subset of [...]")),
                ApiResponse(404, "`bcm43438` wireless component unavailable.", example = errorExample("bcm43438 wireless unavailable")),
            ),
        ),

        // ==================== Optics & Filters ====================
        ControlApiEndpoint(
            operationId = "getLens",
            method = ApiMethod.GET,
            path = "/api/lens",
            tag = "Optics & Filters",
            summary = "Get mounted lens optical and electronic state",
            description = "Inspects the currently mounted lens template, focal length, aperture/iris sync, focus distance, OIS/optics flags, and mount contact protocol.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current mounted lens state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "lens",
                        "revision" to 2,
                        "template" to "gf50-f35",
                        "model" to "GF50mmF3.5 R LM WR",
                        "communicationMode" to "SIMULATED",
                        "electronicContacts" to true,
                        "apertureRingMode" to "A",
                        "aperture" to 3.5,
                        "focalLengthMm" to 50.0,
                        "focusDistanceM" to 2.0,
                        "oisEnabled" to false,
                        "opticsEnabled" to true,
                        "mountAttached" to true,
                    ),
                ),
                ApiResponse(404, "`lens` component unavailable.", example = errorExample("lens component unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postLens",
            method = ApiMethod.POST,
            path = "/api/lens",
            tag = "Optics & Filters",
            summary = "Update mounted lens profile or optical parameters",
            description = "Swaps the mounted lens template/adapter or adjusts live optical controls such as focal length, aperture ring mode (`A`/`C`/`MANUAL`), aperture, focus distance, OIS, and optics simulation.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("template", "string", description = "Lens module ID (e.g. `gf50-f35`, `gf32-64-f4`, `gf110-f2`, `gf45-100-f4`).", example = "gf110-f2"),
                ApiField("adapterId", "string", description = "Mount adapter ID for adapted lenses (e.g. `fringer-ef-gfx`, `m42-gfx`).", example = ""),
                ApiField("communicationMode", "string", description = "Lens communication mode (`SIMULATED`, `MANUAL`, or `REAL_FIRMWARE`).", example = "SIMULATED"),
                ApiField("realFirmwareFile", "string", description = "Path to real lens firmware file when using `REAL_FIRMWARE` mode.", example = ""),
                ApiField("apertureRingMode", "string", description = "Aperture ring position (`A`, `C`, or `MANUAL`).", example = "A", enumValues = listOf("A", "C", "MANUAL")),
                ApiField("aperture", "number", description = "Manual aperture f-number (e.g. `2.0`, `3.5`, `8.0`).", example = 2.8),
                ApiField("focalLengthMm", "number", description = "Zoom focal length in millimeters.", example = 50.0),
                ApiField("focusDistanceM", "number", description = "Focus distance in meters.", example = 1.5),
                ApiField("subjectDistanceM", "number", description = "Optical scene subject distance in meters.", example = 1.5),
                ApiField("focusMode", "string", description = "Lens focus mode setting.", example = "AF-S"),
                ApiField("oisEnabled", "boolean", description = "Enable/disable optical image stabilization (OIS).", example = true),
                ApiField("opticsEnabled", "boolean", description = "Enable/disable physically based lens optics pipeline.", example = true),
                ApiField("electronicContacts", "boolean", description = "Enable/disable electronic lens mount contacts.", example = true),
                ApiField("afTrigger", "boolean", description = "Trigger an autofocus step.", example = false),
            ),
            requestExample = linkedMapOf("apertureRingMode" to "MANUAL", "aperture" to 4.0, "focusDistanceM" to 1.5),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated lens state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "lens",
                        "revision" to 3,
                        "apertureRingMode" to "MANUAL",
                        "aperture" to 4.0,
                        "focusDistanceM" to 1.5,
                    ),
                ),
                ApiResponse(400, "Unknown lens key, null value, or invalid `apertureRingMode`.", example = errorExample("apertureRingMode must be one of [A, C, MANUAL], got 'X'")),
                ApiResponse(404, "`lens` component unavailable.", example = errorExample("lens component unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getFilters",
            method = ApiMethod.GET,
            path = "/api/filters",
            tag = "Optics & Filters",
            summary = "Get optical filter stack state",
            description = "Inspects the `optical-filter` component configuration and computed state across the ordered multi-slot rack (`slots`) as well as UV, ND, Bokeh Mask, Star, Black Mist, and Graduated ND stages.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current optical filter stack state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "optical-filter",
                        "revision" to 1,
                        "enabled" to true,
                        "slots" to "",
                        "ndPreset" to "OFF",
                        "ndStops" to 0.0,
                        "uvPreset" to "OFF",
                        "bokehMask" to "OFF",
                        "starEnabled" to false,
                        "mistEnabled" to false,
                        "gndEnabled" to false,
                    ),
                ),
                ApiResponse(404, "`optical-filter` component unavailable.", example = errorExample("optical-filter component unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postFilters",
            method = ApiMethod.POST,
            path = "/api/filters",
            tag = "Optics & Filters",
            summary = "Configure optical filter stack",
            description = "Configures the `optical-filter` stack using `clear`/`off`, an ordered `slots` array (up to 4 stacked filters), flat settings (`ndPreset`, `ndStops`, `uvPreset`, `bokehMask`, `star*`, `mist*`, `gnd*`), or nested shorthand objects (`uv`, `nd`, `bokeh`, `star`, `mist`, `gnd`).",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("clear", "boolean", description = "Reset all filter slots and stages to OFF.", example = false),
                ApiField("off", "boolean", description = "Alias for `clear`.", example = false),
                ApiField("enabled", "boolean", description = "Master optical filter bypass switch.", example = true),
                ApiField("slots", "array", description = "Ordered multi-slot rack array (max 4 filter objects with `type`) or encoded string.", itemType = "object"),
                ApiField("ndPreset", "string", description = "ND preset ID (`OFF`, `ND2`, `ND4`, `ND8`, `ND16`, `ND32`, `ND64`, `ND1000`, `VND`).", example = "ND8"),
                ApiField("ndStops", "number", description = "ND attenuation in EV stops (`0.0..10.0`).", example = 3.0, minimum = 0.0, maximum = 10.0),
                ApiField("uvPreset", "string", description = "UV protector preset (`OFF`, `MULTI_COATED`, `UNCOATED`, `WARMING`, `HAZE`, `CUSTOM`).", example = "MULTI_COATED"),
                ApiField("bokehMask", "string", description = "Custom aperture bokeh mask shape (`OFF`, `HEART`, `STAR`, `HEXAGON`, `CAT_EYE`, `ANAMORPHIC`).", example = "HEART"),
                ApiField("starEnabled", "boolean", description = "Enable diffraction star filter.", example = false),
                ApiField("mistEnabled", "boolean", description = "Enable black mist / diffusion glow filter.", example = false),
                ApiField("gndEnabled", "boolean", description = "Enable graduated neutral-density filter.", example = false),
                ApiField("uv", "object", description = "Nested UV shorthand object (`preset`, `transmittance`, `veilingGlare`, `ghostIntensity`, `softness`, `tintR/G/B`) or preset string."),
                ApiField("nd", "object", description = "Nested ND shorthand object (`preset`, `stops`).", example = linkedMapOf("preset" to "ND8")),
                ApiField("bokeh", "object", description = "Nested bokeh shorthand object (`shape`/`mask`, `angleDeg`, `scale`)."),
                ApiField("star", "object", description = "Nested star shorthand object (`enabled`, `points`, `strength`, `threshold`, `angleDeg`, `length`)."),
                ApiField("mist", "object", description = "Nested mist shorthand object (`enabled`, `preset`, `strength`, `threshold`)."),
                ApiField("gnd", "object", description = "Nested GND shorthand object (`enabled`, `stops`, `horizon`, `feather`, `angleDeg`)."),
            ),
            requestExample = linkedMapOf(
                "enabled" to true,
                "nd" to linkedMapOf("preset" to "ND8"),
                "mist" to linkedMapOf("enabled" to true, "strength" to 0.35),
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated optical filter stack state.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "optical-filter",
                        "revision" to 2,
                        "enabled" to true,
                        "ndPreset" to "ND8",
                        "ndStops" to 3.0,
                        "mistEnabled" to true,
                        "mistStrength" to 0.35,
                    ),
                ),
                ApiResponse(400, "No valid filter setting or unknown preset/shape.", example = errorExample("filters update requires at least one valid filter setting")),
                ApiResponse(404, "`optical-filter` component unavailable.", example = errorExample("optical-filter component unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getNdFilter",
            method = ApiMethod.GET,
            path = "/api/nd",
            tag = "Optics & Filters",
            summary = "Get ND filter transmittance and stop metrics",
            description = "Returns dedicated neutral-density (ND) filter telemetry including stops, effective stops, filter factor, optical density, transmittance percentage, and human-readable summary.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current ND filter optical metrics.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "optical-filter",
                        "revision" to 2,
                        "enabled" to true,
                        "preset" to "ND8",
                        "stops" to 3.0,
                        "effectiveStops" to 3.0,
                        "factor" to 8.0,
                        "opticalDensity" to 0.903,
                        "transmittance" to 0.125,
                        "transmittancePercent" to 12.5,
                        "summary" to "ND8 (-3.0 EV)",
                    ),
                ),
                ApiResponse(404, "`optical-filter` component unavailable.", example = errorExample("optical-filter component unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postNdFilter",
            method = ApiMethod.POST,
            path = "/api/nd",
            tag = "Optics & Filters",
            summary = "Configure ND filter stops or preset",
            description = "Shorthand endpoint for setting the neutral-density filter by preset (`preset`/`ndPreset`), explicit EV stops (`stops`/`ndStops` in `0.0..10.0`), `enabled`, or `off: true`.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("off", "boolean", description = "Set to `true` to clear ND filter to `OFF` (0 stops).", example = false),
                ApiField("preset", "string", description = "ND preset name (e.g. `ND2`, `ND4`, `ND8`, `ND64`, `ND1000`, `VND`).", example = "ND8"),
                ApiField("ndPreset", "string", description = "Alias for `preset`.", example = "ND8"),
                ApiField("stops", "number", description = "ND density in EV stops (`0.0..10.0`).", example = 3.0, minimum = 0.0, maximum = 10.0),
                ApiField("ndStops", "number", description = "Alias for `stops`.", example = 3.0, minimum = 0.0, maximum = 10.0),
                ApiField("enabled", "boolean", description = "Enable or bypass optical filter processing.", example = true),
            ),
            requestExample = linkedMapOf("preset" to "ND8"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Updated ND filter metrics.",
                    example = linkedMapOf(
                        "ok" to true,
                        "component" to "optical-filter",
                        "revision" to 3,
                        "enabled" to true,
                        "preset" to "ND8",
                        "stops" to 3.0,
                        "effectiveStops" to 3.0,
                    ),
                ),
                ApiResponse(400, "Missing parameters, unknown preset, or out-of-range stops.", example = errorExample("nd update requires preset, stops, off, or enabled")),
                ApiResponse(404, "`optical-filter` component unavailable.", example = errorExample("optical-filter component unavailable")),
            ),
        ),

        // ==================== UI & Personalization ====================
        ControlApiEndpoint(
            operationId = "getLanguage",
            method = ApiMethod.GET,
            path = "/api/lang",
            tag = "UI & Personalization",
            summary = "Get active UI language and supported locales",
            description = "Returns the current desktop studio UI locale and list of supported language codes.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current UI locale state.",
                    example = linkedMapOf(
                        "language" to "en",
                        "supported" to listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES"),
                    ),
                ),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postLanguage",
            method = ApiMethod.POST,
            path = "/api/lang",
            tag = "UI & Personalization",
            summary = "Switch desktop UI language live",
            description = "Hot-switches the emulator desktop UI language without restarting. Provide exact key `language` or `lang`.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("language", "string", description = "Target locale code (`en`, `zh_CN`, `ja_JP`, `de_DE`, `fr_FR`, `es_ES`). Provide either `language` or `lang`.", example = "zh_CN"),
                ApiField("lang", "string", description = "Alias for `language`.", example = "en"),
            ),
            requestExample = linkedMapOf("language" to "zh_CN"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Language updated.",
                    example = linkedMapOf(
                        "ok" to true,
                        "language" to "zh_CN",
                        "supported" to listOf("en", "zh_CN", "ja_JP", "de_DE", "fr_FR", "es_ES"),
                    ),
                ),
                ApiResponse(400, "Missing exact key or unsupported locale.", example = errorExample("lang payload requires exact key: language (or lang)")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getTheme",
            method = ApiMethod.GET,
            path = "/api/theme",
            tag = "UI & Personalization",
            summary = "Get active camera body and UI studio theme",
            description = "Returns the currently active camera body theme (`body`) and desktop studio UI theme (`ui`), plus available presets.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current theme configuration.",
                    example = linkedMapOf(
                        "body" to "graphite",
                        "ui" to "studio-dark",
                    ),
                ),
                ApiResponse(404, "Theme controller unavailable (headless mode).", example = errorExample("theme controller unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postTheme",
            method = ApiMethod.POST,
            path = "/api/theme",
            tag = "UI & Personalization",
            summary = "Switch camera body and/or studio UI theme",
            description = "Hot-switches the camera body finish (`body`) and/or desktop studio UI theme (`ui`). Accepts a non-empty subset of `body` and `ui` with non-blank string values.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField("body", "string", description = "Camera body theme identifier.", example = "graphite"),
                ApiField("ui", "string", description = "Desktop studio UI theme identifier.", example = "studio-dark"),
            ),
            requestExample = linkedMapOf("body" to "graphite", "ui" to "studio-dark"),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Theme updated.",
                    example = linkedMapOf("ok" to true, "body" to "graphite", "ui" to "studio-dark"),
                ),
                ApiResponse(400, "Empty body, unknown key, or blank theme string.", example = errorExample("theme update requires non-empty subset of [body, ui]")),
                ApiResponse(404, "Theme controller unavailable (headless mode).", example = errorExample("theme controller unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postThemeReload",
            method = ApiMethod.POST,
            path = "/api/theme/reload",
            tag = "UI & Personalization",
            summary = "Hot-reload external camera body and UI themes",
            description = "Rescans `./themes/body/` and `./themes/ui/` packs from disk, re-resolves the active camera body and UI themes, and notifies all live studio windows without restarting.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Themes reloaded.",
                    example = linkedMapOf(
                        "reloaded" to true,
                        "reloadGeneration" to 1,
                        "body" to "origin",
                        "ui" to "origin",
                    ),
                ),
                ApiResponse(400, "Non-empty request body.", example = errorExample("theme reload body must be empty or {}")),
                ApiResponse(404, "Theme reload unavailable (headless mode).", example = errorExample("theme reload unavailable")),
            ),
        ),

        // ==================== NOR Flash ====================
        ControlApiEndpoint(
            operationId = "getFlashStatus",
            method = ApiMethod.GET,
            path = "/api/flash",
            tag = "NOR Flash",
            summary = "Get unified 64 MiB QSPI NOR Flash status",
            description = "Returns the active 64 MiB QSPI NOR Flash image path, boot path (`flash-direct` or `bootrom-nor`), BootROM presence, SHA-256 digest, writability, and committed erase/program counters.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Current unified NOR Flash status.",
                    example = linkedMapOf(
                        "unifiedNorMode" to true,
                        "norFlashFile" to "flash/nor-flash.bin",
                        "factoryAvailable" to true,
                        "bootPath" to "flash-direct",
                        "bootRomPresent" to true,
                        "loaded" to true,
                        "length" to 67108864L,
                        "writable" to true,
                        "eraseCommitted" to 0L,
                        "programCommitted" to 0L,
                    ),
                ),
                ApiResponse(404, "Flash controller unavailable.", example = errorExample("flash controller unavailable")),
            ),
        ),
        ControlApiEndpoint(
            operationId = "postFlashAction",
            method = ApiMethod.POST,
            path = "/api/flash",
            tag = "NOR Flash",
            summary = "Flash .DAT firmware, export/import raw NOR image, or restore factory Flash",
            description = "Performs a live host flasher action against the unified 64 MiB QSPI NOR Flash image (`write-dat`, `export`, `import`, or `restore-factory`). Mutating actions automatically reload the component host and cold-reboot the camera.",
            requestContentType = "application/json",
            requestBodyRequired = true,
            requestFields = listOf(
                ApiField(
                    name = "action",
                    type = "string",
                    required = true,
                    description = "Flash operation to perform.",
                    enumValues = listOf("write-dat", "export", "import", "restore-factory"),
                    example = "write-dat",
                ),
                ApiField(
                    name = "path",
                    type = "string",
                    required = false,
                    description = "Source or destination file path (required for `write-dat`, `export`, and `import`; resolved relative to emulator home if not absolute).",
                    example = "GXUP0004.DAT",
                ),
                ApiField(
                    name = "preserveCalibration",
                    type = "boolean",
                    required = false,
                    description = "For `write-dat` only: preserve calibration and user settings `[0x02FE0000..0x04000000)` (default `true`).",
                    example = true,
                ),
            ),
            requestExample = linkedMapOf(
                "action" to "write-dat",
                "path" to "GXUP0004.DAT",
                "preserveCalibration" to true,
            ),
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Flash action succeeded.",
                    example = linkedMapOf(
                        "ok" to true,
                        "sizeBytes" to 67108864L,
                        "preservedCalibration" to true,
                    ),
                ),
                ApiResponse(400, "Invalid action, missing path, or unknown field.", example = errorExample("unsupported flash action 'bad' (expected: write-dat, export, import, restore-factory)")),
                ApiResponse(404, "Flash controller unavailable.", example = errorExample("flash controller unavailable")),
            ),
        ),

        // ==================== Documentation ====================
        ControlApiEndpoint(
            operationId = "getOpenApiSpec",
            method = ApiMethod.GET,
            path = "/api/openapi.json",
            tag = "Documentation",
            summary = "Get OpenAPI 3.1.0 specification",
            description = "Serves the deterministically generated OpenAPI 3.1.0 JSON schema describing every route on this ControlServer, ready for Swagger UI, Postman, Apifox, Bruno, Insomnia, or Redoc.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "OpenAPI 3.1.0 JSON document.",
                    example = linkedMapOf(
                        "openapi" to OPENAPI_VERSION,
                        "info" to linkedMapOf("title" to API_TITLE, "version" to API_VERSION),
                    ),
                ),
            ),
        ),
        ControlApiEndpoint(
            operationId = "getApiDocsHtml",
            method = ApiMethod.GET,
            path = "/api/docs",
            tag = "Documentation",
            summary = "Open offline interactive HTTP API Explorer",
            description = "Serves a zero-CDN, self-contained HTML interactive API explorer with instant search, tag filtering, copy-as-curl snippets, and live same-origin Try-It execution against this ControlServer.",
            responses = listOf(
                ApiResponse(
                    status = 200,
                    description = "Self-contained interactive HTML API Explorer.",
                    contentType = "text/html; charset=utf-8",
                    example = "<!DOCTYPE html><html lang=\"en\">...</html>",
                ),
            ),
        ),
    )
}
