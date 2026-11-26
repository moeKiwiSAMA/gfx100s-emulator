package com.github.nyabanban.fuji.emulator.components.sdcard

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Minimal zero-dependency deterministic JSON codec for SD directory sidecar files. */
internal object SdSidecarJson {
    fun encode(value: Any?, indent: Int = 0): String {
        val sb = StringBuilder()
        writeValue(sb, value, indent)
        sb.append('\n')
        return sb.toString()
    }

    fun writeAtomic(path: Path, value: Any?) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, encode(value), StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(tmp) }
            throw error
        }
    }

    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val result = parser.parseValue()
        parser.expectEnd()
        @Suppress("UNCHECKED_CAST")
        return result as? Map<String, Any?> ?: throw IllegalArgumentException("Sidecar root must be a JSON object")
    }

    private fun writeValue(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Byte, is Short, is Int, is Long -> sb.append((value as Number).toLong())
            is String -> writeString(sb, value)
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                } else {
                    val allSimple = value.all { it == null || it is Number || it is Boolean }
                    if (allSimple) {
                        sb.append('[')
                        value.forEachIndexed { idx, item ->
                            if (idx > 0) sb.append(", ")
                            writeValue(sb, item, indent)
                        }
                        sb.append(']')
                    } else {
                        sb.append("[\n")
                        val childIndent = indent + 2
                        value.forEachIndexed { idx, item ->
                            appendSpaces(sb, childIndent)
                            writeValue(sb, item, childIndent)
                            if (idx + 1 < value.size) sb.append(',')
                            sb.append('\n')
                        }
                        appendSpaces(sb, indent)
                        sb.append(']')
                    }
                }
            }
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                } else {
                    sb.append("{\n")
                    val childIndent = indent + 2
                    val entries = value.entries.toList()
                    entries.forEachIndexed { idx, (rawKey, entryVal) ->
                        val key = rawKey as? String ?: throw IllegalArgumentException("JSON object keys must be String")
                        appendSpaces(sb, childIndent)
                        writeString(sb, key)
                        sb.append(": ")
                        writeValue(sb, entryVal, childIndent)
                        if (idx + 1 < entries.size) sb.append(',')
                        sb.append('\n')
                    }
                    appendSpaces(sb, indent)
                    sb.append('}')
                }
            }
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${value::class.java.name}")
        }
    }

    private fun appendSpaces(sb: StringBuilder, count: Int) {
        repeat(count) { sb.append(' ') }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val source: String) {
        private var pos = 0

        private fun skipWs() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }

        fun expectEnd() {
            skipWs()
            require(pos == source.length) { "Trailing characters in JSON at $pos" }
        }

        fun parseValue(): Any? {
            skipWs()
            require(pos < source.length) { "Unexpected end of JSON" }
            return when (source[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            require(source[pos++] == '{')
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (pos < source.length && source[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                require(pos < source.length && source[pos++] == ':') { "Expected ':' in JSON object" }
                require(key !in map) { "Duplicate JSON key: $key" }
                map[key] = parseValue()
                skipWs()
                require(pos < source.length) { "Unterminated JSON object" }
                when (source[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("Malformed JSON object at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            require(source[pos++] == '[')
            skipWs()
            val list = ArrayList<Any?>()
            if (pos < source.length && source[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                require(pos < source.length) { "Unterminated JSON array" }
                when (source[pos++]) {
                    ',' -> continue
                    ']' -> return list
                    else -> throw IllegalArgumentException("Malformed JSON array at $pos")
                }
            }
        }

        private fun parseString(): String {
            require(pos < source.length && source[pos++] == '"') { "Expected string at $pos" }
            val sb = StringBuilder()
            while (pos < source.length) {
                val ch = source[pos++]
                if (ch == '"') return sb.toString()
                if (ch == '\\') {
                    require(pos < source.length) { "Unterminated escape in JSON string" }
                    when (val esc = source[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(pos + 4 <= source.length) { "Short unicode escape" }
                            val hex = source.substring(pos, pos + 4)
                            sb.append(hex.toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("Unsupported JSON escape: \\$esc")
                    }
                } else {
                    sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseBoolean(): Boolean = when {
            source.startsWith("true", pos) -> {
                pos += 4
                true
            }
            source.startsWith("false", pos) -> {
                pos += 5
                false
            }
            else -> throw IllegalArgumentException("Invalid JSON boolean at $pos")
        }

        private fun parseNull(): Any? {
            require(source.startsWith("null", pos)) { "Invalid JSON null at $pos" }
            pos += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < source.length && source[pos] == '-') pos++
            val digitsStart = pos
            while (pos < source.length && source[pos].isDigit()) pos++
            require(pos > digitsStart) { "Invalid JSON integer at $start" }
            val text = source.substring(start, pos)
            return text.toLong()
        }
    }
}
