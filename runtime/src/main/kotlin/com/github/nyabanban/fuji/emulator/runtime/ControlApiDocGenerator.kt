package com.github.nyabanban.fuji.emulator.runtime

import java.nio.file.Files
import java.nio.file.Path

/**
 * Deterministic multi-format documentation generator for the FUJIFILM GFX100S Emulator
 * HTTP Control API ([ControlApiSpec]).
 *
 * Produces three synchronized artifacts with zero external dependencies and deterministic LF output:
 * 1. `http-openapi.json` — OpenAPI 3.1.0 JSON schema
 * 2. `http-control-api.md` — GitHub-Flavored Markdown reference with TOC, tables, examples, and curl snippets
 * 3. `http-control-api.html` — 100% offline self-contained interactive HTML API Explorer (studio dark UI + Try-It)
 */
object ControlApiDocGenerator {
    const val OPENAPI_FILENAME = "http-openapi.json"
    const val MARKDOWN_FILENAME = "http-control-api.md"
    const val HTML_FILENAME = "http-control-api.html"

    data class GeneratedDocs(
        val openApiJson: String,
        val markdown: String,
        val html: String,
    )

    val openApiJsonBytes: ByteArray by lazy {
        generateOpenApiJson().toByteArray(Charsets.UTF_8)
    }

    val interactiveHtmlBytes: ByteArray by lazy {
        generateHtml().toByteArray(Charsets.UTF_8)
    }

    fun generateAll(): GeneratedDocs = GeneratedDocs(
        openApiJson = generateOpenApiJson(),
        markdown = generateMarkdown(),
        html = generateHtml(),
    )

    fun writeAll(outputDir: Path): GeneratedDocs {
        Files.createDirectories(outputDir)
        val docs = generateAll()
        Formats.writeLf(outputDir.resolve(OPENAPI_FILENAME), docs.openApiJson)
        Formats.writeLf(outputDir.resolve(MARKDOWN_FILENAME), docs.markdown)
        Formats.writeLf(outputDir.resolve(HTML_FILENAME), docs.html)
        return docs
    }

    fun generateOpenApiDocument(): Map<String, Any?> {
        val paths = LinkedHashMap<String, MutableMap<String, Any?>>()
        for (ep in ControlApiSpec.endpoints) {
            val pathItem = paths.getOrPut(ep.path) { LinkedHashMap() }
            pathItem[ep.method.name.lowercase()] = buildOperationObject(ep)
        }
        val tagObjects = ControlApiSpec.tags.map { (name, desc) ->
            linkedMapOf("name" to name, "description" to desc)
        }
        val errorSchema = linkedMapOf<String, Any?>(
            "type" to "object",
            "required" to listOf("error"),
            "properties" to linkedMapOf(
                "error" to linkedMapOf(
                    "type" to "string",
                    "description" to "Human-readable error diagnostic message.",
                ),
            ),
        )
        return linkedMapOf(
            "openapi" to ControlApiSpec.OPENAPI_VERSION,
            "info" to linkedMapOf(
                "title" to ControlApiSpec.API_TITLE,
                "version" to ControlApiSpec.API_VERSION,
                "description" to ControlApiSpec.API_DESCRIPTION,
            ),
            "servers" to listOf(
                linkedMapOf(
                    "url" to ControlApiSpec.DEFAULT_BASE_URL,
                    "description" to "Local loopback ControlServer (replace port with --control-port)",
                ),
            ),
            "tags" to tagObjects,
            "paths" to paths,
            "components" to linkedMapOf(
                "schemas" to linkedMapOf(
                    "ErrorResponse" to errorSchema,
                ),
            ),
        )
    }

    fun generateOpenApiJson(): String = Formats.jsonText(generateOpenApiDocument(), pretty = true)

    private fun buildOperationObject(ep: ControlApiEndpoint): Map<String, Any?> {
        val op = LinkedHashMap<String, Any?>()
        op["operationId"] = ep.operationId
        op["tags"] = listOf(ep.tag)
        op["summary"] = ep.summary
        op["description"] = ep.description
        if (ep.params.isNotEmpty()) {
            op["parameters"] = ep.params.map { param ->
                val schema = LinkedHashMap<String, Any?>()
                schema["type"] = param.type
                if (param.enumValues.isNotEmpty()) schema["enum"] = param.enumValues
                if (param.pattern != null) schema["pattern"] = param.pattern
                if (param.minimum != null) schema["minimum"] = param.minimum
                if (param.maximum != null) schema["maximum"] = param.maximum
                val p = LinkedHashMap<String, Any?>()
                p["name"] = param.name
                p["in"] = param.location.openApiName
                p["required"] = param.required
                p["description"] = param.description
                p["schema"] = schema
                if (param.example != null) p["example"] = param.example
                p
            }
        }
        if (ep.requestContentType != null) {
            val schema = LinkedHashMap<String, Any?>()
            schema["type"] = "object"
            if (ep.requestFields.isNotEmpty()) {
                val props = LinkedHashMap<String, Any?>()
                val req = mutableListOf<String>()
                for (f in ep.requestFields) {
                    if (f.required) req += f.name
                    val prop = LinkedHashMap<String, Any?>()
                    prop["type"] = f.type
                    prop["description"] = f.description
                    if (f.enumValues.isNotEmpty()) prop["enum"] = f.enumValues
                    if (f.minimum != null) prop["minimum"] = f.minimum
                    if (f.maximum != null) prop["maximum"] = f.maximum
                    if (f.type == "array" && f.itemType != null) {
                        prop["items"] = linkedMapOf("type" to f.itemType)
                    }
                    if (f.example != null) prop["example"] = f.example
                    props[f.name] = prop
                }
                schema["properties"] = props
                if (req.isNotEmpty()) schema["required"] = req
            }
            val media = LinkedHashMap<String, Any?>()
            media["schema"] = schema
            if (ep.requestExample != null) media["example"] = ep.requestExample
            op["requestBody"] = linkedMapOf(
                "required" to ep.requestBodyRequired,
                "content" to linkedMapOf(ep.requestContentType to media),
            )
        }
        val responses = LinkedHashMap<String, Any?>()
        for (r in ep.responses) {
            val mediaType = r.contentType.substringBefore(';').trim()
            val media = LinkedHashMap<String, Any?>()
            if (r.status >= 400 && mediaType == "application/json" && (r.example as? Map<*, *>)?.keys == setOf("error")) {
                media["schema"] = linkedMapOf("\$ref" to "#/components/schemas/ErrorResponse")
            } else if (mediaType == "application/json") {
                media["schema"] = linkedMapOf("type" to "object")
            } else {
                media["schema"] = linkedMapOf("type" to "string")
            }
            if (r.example != null) media["example"] = r.example
            responses[r.status.toString()] = linkedMapOf(
                "description" to r.description,
                "content" to linkedMapOf(mediaType to media),
            )
        }
        op["responses"] = responses
        return op
    }

    fun generateMarkdown(): String {
        val sb = StringBuilder(32_768)
        sb.appendLine("# ${ControlApiSpec.API_TITLE}")
        sb.appendLine()
        sb.appendLine("> **Auto-generated** from `ControlApiSpec.kt` (`OpenAPI ${ControlApiSpec.OPENAPI_VERSION}`, `${ControlApiSpec.endpoints.size}` endpoints). Do not hand-edit; regenerate with `./gradlew :generateHttpApiDocs`.")
        sb.appendLine()
        sb.appendLine("## Overview")
        sb.appendLine()
        sb.appendLine("- **Base URL**: `${ControlApiSpec.DEFAULT_BASE_URL}` (loopback `127.0.0.1` only; port set via `--control-port`).")
        sb.appendLine("- **Live Interactive Explorer**: `GET /api/docs` (offline single-file HTML) and `GET /api/openapi.json` (OpenAPI `${ControlApiSpec.OPENAPI_VERSION}`).")
        sb.appendLine("- **Generated Artifacts**: [http-openapi.json]($OPENAPI_FILENAME) | [http-control-api.html]($HTML_FILENAME)")
        sb.appendLine("- **Security & Limits**: Cross-origin `Origin` headers are rejected (`400`); JSON write routes require `Content-Type: application/json`; maximum request body size is `64 KiB` (`65536` bytes).")
        sb.appendLine()
        sb.appendLine("### Standard HTTP Status Codes")
        sb.appendLine()
        sb.appendLine("| Status | Meaning |")
        sb.appendLine("| --- | --- |")
        sb.appendLine("| `200` / `201` | Request succeeded (`201` for `POST /api/v1/dump`). |")
        sb.appendLine("| `400` | Invalid JSON, missing/extra keys, out-of-range value, wrong `Content-Type`, or cross-origin `Origin`. |")
        sb.appendLine("| `404` | Unknown route/method or optional component/handler not enabled in this session. |")
        sb.appendLine("| `409` | Optimistic-concurrency revision conflict on `PATCH /api/v1/components/{id}`. |")
        sb.appendLine("| `500` / `503` | Internal error or executor rejected / external upload unavailable. |")
        sb.appendLine()

        val byTag = ControlApiSpec.endpoints.groupBy { it.tag }
        sb.appendLine("## Endpoint Index")
        sb.appendLine()
        sb.appendLine("| Category | Method | Path | Summary |")
        sb.appendLine("| --- | --- | --- | --- |")
        for ((tag, _) in ControlApiSpec.tags) {
            val eps = byTag[tag].orEmpty()
            for (ep in eps) {
                sb.appendLine("| $tag | `${ep.method}` | `${ep.path}` | ${ep.summary} |")
            }
        }
        sb.appendLine()

        sb.appendLine("## Categories")
        sb.appendLine()
        for ((tag, desc) in ControlApiSpec.tags) {
            val eps = byTag[tag].orEmpty()
            if (eps.isEmpty()) continue
            sb.appendLine("- [$tag](#${slugify(tag)}) (${eps.size}) — $desc")
        }
        sb.appendLine()

        for ((tag, desc) in ControlApiSpec.tags) {
            val eps = byTag[tag].orEmpty()
            if (eps.isEmpty()) continue
            sb.appendLine("## $tag")
            sb.appendLine()
            sb.appendLine(desc)
            sb.appendLine()
            for (ep in eps) {
                sb.appendLine("### `${ep.method} ${ep.path}`")
                sb.appendLine()
                sb.appendLine("**${ep.summary}** — ${ep.description}")
                sb.appendLine()
                if (ep.params.isNotEmpty()) {
                    sb.appendLine("**Parameters**")
                    sb.appendLine()
                    sb.appendLine("| Name | In | Type | Required | Description |")
                    sb.appendLine("| --- | --- | --- | --- | --- |")
                    for (p in ep.params) {
                        val req = if (p.required) "yes" else "no"
                        val extras = buildList {
                            if (p.enumValues.isNotEmpty()) add("values: `${p.enumValues.joinToString("`, `")}`")
                            if (p.minimum != null || p.maximum != null) add("range: `${p.minimum ?: ""}..${p.maximum ?: ""}`")
                        }
                        val detail = if (extras.isEmpty()) p.description else "${p.description} (${extras.joinToString("; ")})"
                        sb.appendLine("| `${p.name}` | `${p.location.openApiName}` | `${p.type}` | $req | $detail |")
                    }
                    sb.appendLine()
                }
                if (ep.requestFields.isNotEmpty()) {
                    sb.appendLine("**Request Body (`${ep.requestContentType}`)**")
                    sb.appendLine()
                    sb.appendLine("| Field | Type | Required | Description |")
                    sb.appendLine("| --- | --- | --- | --- |")
                    for (f in ep.requestFields) {
                        val req = if (f.required) "yes" else "no"
                        val extras = buildList {
                            if (f.enumValues.isNotEmpty()) add("values: `${f.enumValues.joinToString("`, `")}`")
                            if (f.minimum != null || f.maximum != null) add("range: `${f.minimum ?: ""}..${f.maximum ?: ""}`")
                        }
                        val detail = if (extras.isEmpty()) f.description else "${f.description} (${extras.joinToString("; ")})"
                        sb.appendLine("| `${f.name}` | `${f.type}` | $req | $detail |")
                    }
                    sb.appendLine()
                }
                if (ep.requestExample != null) {
                    sb.appendLine("**Request Example**")
                    sb.appendLine()
                    sb.appendLine("```json")
                    sb.append(Formats.jsonText(ep.requestExample, pretty = true))
                    sb.appendLine("```")
                    sb.appendLine()
                }
                sb.appendLine("**Responses**")
                sb.appendLine()
                sb.appendLine("| Status | Content-Type | Description |")
                sb.appendLine("| --- | --- | --- |")
                for (r in ep.responses) {
                    sb.appendLine("| `${r.status}` | `${r.contentType}` | ${r.description} |")
                }
                sb.appendLine()
                val okExample = ep.responses.firstOrNull { it.status in 200..299 && it.example != null }
                if (okExample != null && okExample.contentType.startsWith("application/json")) {
                    sb.appendLine("**Success Response Example (`${okExample.status}`)**")
                    sb.appendLine()
                    sb.appendLine("```json")
                    sb.append(Formats.jsonText(okExample.example, pretty = true))
                    sb.appendLine("```")
                    sb.appendLine()
                }
                sb.appendLine("**curl Example**")
                sb.appendLine()
                sb.appendLine("```bash")
                sb.appendLine(buildCurlExample(ep))
                sb.appendLine("```")
                sb.appendLine()
            }
        }
        return Formats.lf(sb.toString().trimEnd('\n'))
    }

    private fun buildCurlExample(ep: ControlApiEndpoint): String {
        var resolvedPath = ep.path
        for (p in ep.params.filter { it.location == ApiParamLocation.PATH }) {
            resolvedPath = resolvedPath.replace("{${p.name}}", p.example ?: "example")
        }
        val querySample = ep.params.firstOrNull { it.location == ApiParamLocation.QUERY && it.example != null }
        val url = if (querySample != null && ep.method == ApiMethod.GET) {
            "${ControlApiSpec.DEFAULT_BASE_URL}$resolvedPath?${querySample.name}=${querySample.example}"
        } else {
            "${ControlApiSpec.DEFAULT_BASE_URL}$resolvedPath"
        }
        return if (ep.method == ApiMethod.GET) {
            "curl -s \"$url\""
        } else if (ep.requestExample != null) {
            val compactJson = Formats.json.writeValueAsString(ep.requestExample)
            "curl -s -X ${ep.method} \"$url\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '$compactJson'"
        } else {
            "curl -s -X ${ep.method} \"$url\""
        }
    }

    private fun slugify(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9\\s-]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")

    fun generateHtml(): String {
        val specPayload = Formats.json.writeValueAsString(buildHtmlExplorerPayload())
            .replace("</", "<\\/")
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(ControlApiSpec.API_TITLE)}</title>
<style>
:root {
  --bg: #0d1117;
  --surface: #161b22;
  --surface-2: #1c222b;
  --border: #30363d;
  --text: #e6edf3;
  --muted: #8b949e;
  --accent: #58a6ff;
  --get: #2ea043;
  --post: #1f6feb;
  --patch: #d29922;
  --err: #f85149;
  --mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font-family: var(--sans);
  line-height: 1.5;
}
header {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(13, 17, 23, 0.95);
  border-bottom: 1px solid var(--border);
  backdrop-filter: blur(8px);
  padding: 14px 20px;
}
.header-inner {
  max-width: 1180px;
  margin: 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}
.brand h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.2px;
}
.brand .sub {
  font-size: 12px;
  color: var(--muted);
}
.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
input[type="text"], textarea {
  background: var(--surface);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 10px;
  font-size: 13px;
  font-family: var(--mono);
}
input[type="text"]:focus, textarea:focus {
  outline: none;
  border-color: var(--accent);
}
#search { width: 240px; font-family: var(--sans); }
#baseUrl { width: 220px; }
button {
  background: var(--surface-2);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 7px 11px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
button:hover { border-color: var(--accent); color: #fff; }
button.primary {
  background: var(--post);
  border-color: var(--post);
  color: #fff;
}
button.primary:hover { filter: brightness(1.12); }
main {
  max-width: 1180px;
  margin: 0 auto;
  padding: 18px 20px 64px;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}
.tag-btn {
  border-radius: 999px;
  padding: 5px 12px;
  font-size: 12px;
  background: var(--surface);
}
.tag-btn.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #0d1117;
}
.stats {
  color: var(--muted);
  font-size: 12px;
  margin-bottom: 14px;
}
.group-title {
  margin: 24px 0 6px;
  font-size: 16px;
  font-weight: 700;
}
.group-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--muted);
}
.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 8px;
  margin-bottom: 10px;
  overflow: hidden;
}
.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  cursor: pointer;
  user-select: none;
}
.card-head:hover { background: var(--surface-2); }
.badge {
  display: inline-block;
  min-width: 58px;
  text-align: center;
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 700;
  padding: 3px 7px;
  border-radius: 4px;
  color: #fff;
}
.badge.GET { background: var(--get); }
.badge.POST { background: var(--post); }
.badge.PATCH { background: var(--patch); color: #0d1117; }
.ep-path {
  font-family: var(--mono);
  font-size: 13px;
  font-weight: 600;
}
.ep-summary {
  color: var(--muted);
  font-size: 13px;
  margin-left: auto;
  text-align: right;
}
.card-body {
  display: none;
  border-top: 1px solid var(--border);
  padding: 14px 16px 16px;
  background: #11161d;
}
.card.open .card-body { display: block; }
.ep-desc {
  margin: 0 0 12px;
  font-size: 13px;
  color: #c9d1d9;
}
.section-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.7px;
  color: var(--muted);
  margin: 14px 0 6px;
  font-weight: 700;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  margin-bottom: 10px;
}
th, td {
  text-align: left;
  border-bottom: 1px solid var(--border);
  padding: 6px 8px;
  vertical-align: top;
}
th { color: var(--muted); font-weight: 600; }
code {
  font-family: var(--mono);
  font-size: 12px;
  background: rgba(110, 118, 129, 0.2);
  padding: 1px 4px;
  border-radius: 4px;
}
pre {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 10px 12px;
  overflow-x: auto;
  font-family: var(--mono);
  font-size: 12px;
  margin: 6px 0 10px;
}
.try-box {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  margin-top: 10px;
}
.try-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.try-row label {
  font-size: 12px;
  color: var(--muted);
  font-family: var(--mono);
}
.try-body {
  width: 100%;
  min-height: 84px;
  resize: vertical;
  margin-bottom: 8px;
}
.try-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.status-pill {
  font-family: var(--mono);
  font-size: 12px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 4px;
}
.status-ok { background: rgba(46, 160, 67, 0.2); color: #3fb950; }
.status-err { background: rgba(248, 81, 73, 0.2); color: var(--err); }
.hidden { display: none; }
</style>
</head>
<body>
<header>
  <div class="header-inner">
    <div class="brand">
      <h1>${escapeHtml(ControlApiSpec.API_TITLE)}</h1>
      <div class="sub">OpenAPI ${ControlApiSpec.OPENAPI_VERSION} &middot; Offline Interactive Explorer &middot; Loopback 127.0.0.1</div>
    </div>
    <div class="controls">
      <input id="baseUrl" type="text" title="ControlServer Base URL" placeholder="${ControlApiSpec.DEFAULT_BASE_URL}">
      <input id="search" type="text" placeholder="Search path, tag, field...">
      <button id="expandAll" type="button">Expand All</button>
      <button id="collapseAll" type="button">Collapse All</button>
    </div>
  </div>
</header>
<main>
  <div id="tags" class="tags"></div>
  <div id="stats" class="stats"></div>
  <div id="list"></div>
</main>
<script id="api-spec-data" type="application/json">$specPayload</script>
<script>
(function () {
  const spec = JSON.parse(document.getElementById("api-spec-data").textContent);
  const baseUrlInput = document.getElementById("baseUrl");
  const searchInput = document.getElementById("search");
  const tagsEl = document.getElementById("tags");
  const statsEl = document.getElementById("stats");
  const listEl = document.getElementById("list");
  let activeTag = "ALL";

  if (window.location && (window.location.protocol === "http:" || window.location.protocol === "https:")) {
    baseUrlInput.value = window.location.origin;
  } else {
    baseUrlInput.value = spec.defaultBaseUrl;
  }

  function esc(s) {
    return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function getBaseUrl() {
    return (baseUrlInput.value || spec.defaultBaseUrl).replace(/\/+$/, "");
  }

  function buildUrl(ep, card) {
    let p = ep.path;
    const queryParts = [];
    for (const prm of ep.params || []) {
      const inp = card ? card.querySelector('[data-param="' + prm.name + '"]') : null;
      const val = inp ? inp.value.trim() : (prm.location === "path" ? (prm.example || "") : "");
      if (prm.location === "path") {
        p = p.replace("{" + prm.name + "}", encodeURIComponent(val || prm.example || "example"));
      } else if (val) {
        queryParts.push(encodeURIComponent(prm.name) + "=" + encodeURIComponent(val));
      }
    }
    return getBaseUrl() + p + (queryParts.length ? "?" + queryParts.join("&") : "");
  }

  function buildCurl(ep, card) {
    const url = buildUrl(ep, card);
    if (ep.method === "GET") return 'curl -s "' + url + '"';
    const ta = card ? card.querySelector(".try-body") : null;
    const rawBody = ta ? ta.value.trim() : (ep.requestExampleJson || "");
    if (!rawBody) return 'curl -s -X ' + ep.method + ' "' + url + '"';
    let compact = rawBody;
    try { compact = JSON.stringify(JSON.parse(rawBody)); } catch (_) {}
    return 'curl -s -X ' + ep.method + ' "' + url + '" \\\n  -H "Content-Type: application/json" \\\n  -d \'' + compact.replace(/'/g, "'\\''") + "'";
  }

  function renderTags() {
    const counts = { ALL: spec.endpoints.length };
    for (const ep of spec.endpoints) counts[ep.tag] = (counts[ep.tag] || 0) + 1;
    const items = [{ name: "ALL", label: "All (" + counts.ALL + ")" }].concat(
      spec.tags.map(t => ({ name: t.name, label: t.name + " (" + (counts[t.name] || 0) + ")" }))
    );
    tagsEl.innerHTML = items.map(it =>
      '<button type="button" class="tag-btn' + (activeTag === it.name ? " active" : "") + '" data-tag="' + esc(it.name) + '">' + esc(it.label) + "</button>"
    ).join("");
    tagsEl.querySelectorAll(".tag-btn").forEach(btn => {
      btn.addEventListener("click", () => {
        activeTag = btn.getAttribute("data-tag");
        renderTags();
        renderList();
      });
    });
  }

  function matchesQuery(ep, q) {
    if (!q) return true;
    const hay = [
      ep.method, ep.path, ep.tag, ep.summary, ep.description, ep.operationId,
      ...(ep.params || []).map(p => p.name + " " + p.description),
      ...(ep.requestFields || []).map(f => f.name + " " + f.description)
    ].join(" ").toLowerCase();
    return hay.indexOf(q) !== -1;
  }

  function renderList() {
    const q = searchInput.value.trim().toLowerCase();
    const filtered = spec.endpoints.filter(ep => (activeTag === "ALL" || ep.tag === activeTag) && matchesQuery(ep, q));
    statsEl.textContent = "Showing " + filtered.length + " of " + spec.endpoints.length + " endpoints";
    const byTag = new Map();
    for (const ep of filtered) {
      if (!byTag.has(ep.tag)) byTag.set(ep.tag, []);
      byTag.get(ep.tag).push(ep);
    }
    listEl.innerHTML = "";
    for (const tagMeta of spec.tags) {
      const eps = byTag.get(tagMeta.name);
      if (!eps || !eps.length) continue;
      const h = document.createElement("h2");
      h.className = "group-title";
      h.textContent = tagMeta.name;
      listEl.appendChild(h);
      const d = document.createElement("p");
      d.className = "group-desc";
      d.textContent = tagMeta.description;
      listEl.appendChild(d);
      for (const ep of eps) {
        listEl.appendChild(renderCard(ep));
      }
    }
  }

  function renderCard(ep) {
    const card = document.createElement("div");
    card.className = "card";
    const head = document.createElement("div");
    head.className = "card-head";
    head.innerHTML =
      '<span class="badge ' + esc(ep.method) + '">' + esc(ep.method) + "</span>" +
      '<span class="ep-path">' + esc(ep.path) + "</span>" +
      '<span class="ep-summary">' + esc(ep.summary) + "</span>";
    head.addEventListener("click", () => card.classList.toggle("open"));
    card.appendChild(head);

    const body = document.createElement("div");
    body.className = "card-body";
    let html = '<p class="ep-desc">' + esc(ep.description) + "</p>";

    if (ep.params && ep.params.length) {
      html += '<div class="section-label">Parameters</div><table><thead><tr><th>Name</th><th>In</th><th>Type</th><th>Req</th><th>Description</th></tr></thead><tbody>';
      for (const p of ep.params) {
        html += "<tr><td><code>" + esc(p.name) + "</code></td><td><code>" + esc(p.location) + "</code></td><td><code>" + esc(p.type) + "</code></td><td>" + (p.required ? "yes" : "no") + "</td><td>" + esc(p.description) + "</td></tr>";
      }
      html += "</tbody></table>";
    }

    if (ep.requestFields && ep.requestFields.length) {
      html += '<div class="section-label">Request Body (' + esc(ep.requestContentType || "application/json") + ')</div><table><thead><tr><th>Field</th><th>Type</th><th>Req</th><th>Description</th></tr></thead><tbody>';
      for (const f of ep.requestFields) {
        html += "<tr><td><code>" + esc(f.name) + "</code></td><td><code>" + esc(f.type) + "</code></td><td>" + (f.required ? "yes" : "no") + "</td><td>" + esc(f.description) + "</td></tr>";
      }
      html += "</tbody></table>";
    }

    html += '<div class="section-label">Responses</div><table><thead><tr><th>Status</th><th>Content-Type</th><th>Description</th></tr></thead><tbody>';
    for (const r of ep.responses) {
      html += "<tr><td><code>" + esc(r.status) + "</code></td><td><code>" + esc(r.contentType) + "</code></td><td>" + esc(r.description) + "</td></tr>";
    }
    html += "</tbody></table>";

    if (ep.successExampleJson) {
      html += '<div class="section-label">Success Example</div><pre>' + esc(ep.successExampleJson) + "</pre>";
    }

    html += '<div class="try-box"><div class="section-label" style="margin-top:0">Try It &amp; curl</div>';
    if (ep.params && ep.params.length) {
      html += '<div class="try-row">';
      for (const p of ep.params) {
        const defVal = p.location === "path" ? (p.example || "") : "";
        html += '<label>' + esc(p.name) + ' <input type="text" data-param="' + esc(p.name) + '" value="' + esc(defVal) + '" placeholder="' + esc(p.example || p.type) + '"></label>';
      }
      html += "</div>";
    }
    if (ep.method !== "GET") {
      html += '<textarea class="try-body" spellcheck="false">' + esc(ep.requestExampleJson || "") + "</textarea>";
    }
    html += '<div class="try-actions">' +
      '<button type="button" class="primary btn-send">Send Request</button>' +
      '<button type="button" class="btn-curl">Copy curl</button>' +
      '<span class="status-pill hidden"></span>' +
      "</div>" +
      '<pre class="curl-preview"></pre>' +
      '<pre class="try-response hidden"></pre>' +
      "</div>";

    body.innerHTML = html;
    card.appendChild(body);

    const curlPreview = body.querySelector(".curl-preview");
    const statusPill = body.querySelector(".status-pill");
    const respPre = body.querySelector(".try-response");
    const updateCurl = () => { curlPreview.textContent = buildCurl(ep, card); };
    updateCurl();
    body.querySelectorAll("input, textarea").forEach(el => el.addEventListener("input", updateCurl));
    baseUrlInput.addEventListener("input", updateCurl);

    body.querySelector(".btn-curl").addEventListener("click", () => {
      const cmd = buildCurl(ep, card);
      curlPreview.textContent = cmd;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(cmd).catch(() => {});
      }
    });

    body.querySelector(".btn-send").addEventListener("click", async () => {
      const url = buildUrl(ep, card);
      const opts = { method: ep.method, headers: {} };
      const ta = body.querySelector(".try-body");
      if (ep.method !== "GET" && ta && ta.value.trim()) {
        opts.headers["Content-Type"] = "application/json";
        opts.body = ta.value;
      } else if (ep.method !== "GET" && ep.requestContentType) {
        opts.headers["Content-Type"] = ep.requestContentType;
      }
      const t0 = performance.now();
      statusPill.className = "status-pill";
      statusPill.textContent = "Sending...";
      respPre.classList.remove("hidden");
      try {
        const res = await fetch(url, opts);
        const dt = Math.round(performance.now() - t0);
        const text = await res.text();
        statusPill.className = "status-pill " + (res.ok ? "status-ok" : "status-err");
        statusPill.textContent = "HTTP " + res.status + " (" + dt + " ms)";
        try {
          respPre.textContent = JSON.stringify(JSON.parse(text), null, 2);
        } catch (_) {
          respPre.textContent = text;
        }
      } catch (err) {
        statusPill.className = "status-pill status-err";
        statusPill.textContent = "Network Error";
        respPre.textContent = String(err) + "\n(Open via http://127.0.0.1:<port>/api/docs for live Try-It.)";
      }
    });

    return card;
  }

  searchInput.addEventListener("input", renderList);
  document.getElementById("expandAll").addEventListener("click", () => {
    listEl.querySelectorAll(".card").forEach(c => c.classList.add("open"));
  });
  document.getElementById("collapseAll").addEventListener("click", () => {
    listEl.querySelectorAll(".card").forEach(c => c.classList.remove("open"));
  });

  renderTags();
  renderList();
})();
</script>
</body>
</html>
        """.trimIndent()
        return Formats.lf(html)
    }

    private fun buildHtmlExplorerPayload(): Map<String, Any?> = linkedMapOf(
        "title" to ControlApiSpec.API_TITLE,
        "version" to ControlApiSpec.API_VERSION,
        "openapi" to ControlApiSpec.OPENAPI_VERSION,
        "defaultBaseUrl" to ControlApiSpec.DEFAULT_BASE_URL,
        "tags" to ControlApiSpec.tags.map { (name, desc) -> linkedMapOf("name" to name, "description" to desc) },
        "endpoints" to ControlApiSpec.endpoints.map { ep ->
            val ok = ep.responses.firstOrNull { it.status in 200..299 && it.example != null }
            val okJson = if (ok != null && ok.contentType.startsWith("application/json")) {
                Formats.jsonText(ok.example, pretty = true).trimEnd()
            } else {
                null
            }
            linkedMapOf(
                "operationId" to ep.operationId,
                "method" to ep.method.name,
                "path" to ep.path,
                "tag" to ep.tag,
                "summary" to ep.summary,
                "description" to ep.description,
                "params" to ep.params.map { p ->
                    linkedMapOf(
                        "name" to p.name,
                        "location" to p.location.openApiName,
                        "type" to p.type,
                        "required" to p.required,
                        "description" to p.description,
                        "example" to p.example,
                    )
                },
                "requestContentType" to ep.requestContentType,
                "requestFields" to ep.requestFields.map { f ->
                    linkedMapOf(
                        "name" to f.name,
                        "type" to f.type,
                        "required" to f.required,
                        "description" to f.description,
                    )
                },
                "requestExampleJson" to ep.requestExample?.let { Formats.jsonText(it, pretty = true).trimEnd() },
                "responses" to ep.responses.map { r ->
                    linkedMapOf(
                        "status" to r.status,
                        "contentType" to r.contentType,
                        "description" to r.description,
                    )
                },
                "successExampleJson" to okJson,
            )
        },
    )

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

fun main(args: Array<String>) {
    val outDir = Path.of(args.firstOrNull() ?: "docs/api").toAbsolutePath().normalize()
    val docs = ControlApiDocGenerator.writeAll(outDir)
    println(
        "Generated HTTP Control API docs (${ControlApiSpec.endpoints.size} endpoints): " +
            "${ControlApiDocGenerator.OPENAPI_FILENAME}=${docs.openApiJson.length}B, " +
            "${ControlApiDocGenerator.MARKDOWN_FILENAME}=${docs.markdown.length}B, " +
            "${ControlApiDocGenerator.HTML_FILENAME}=${docs.html.length}B",
    )
}
