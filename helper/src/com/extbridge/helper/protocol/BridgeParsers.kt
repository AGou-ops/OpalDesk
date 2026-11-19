package com.extbridge.helper.protocol

/**
 * Pure-Kotlin parsers for daemon IPC payloads (JSON window snapshots, EDID modes,
 * per-app display modes, and trusted Mac client lists) with zero `org.json` runtime dependency
 * so plain JVM unit tests match Android behavior identically.
 */
object BridgeParsers {

    fun parseWindowStateSnapshot(raw: String?): WindowStateSnapshot {
        if (raw.isNullOrBlank()) return WindowStateSnapshot()
        val parsed = MiniJsonParser(raw).parseRootObject() ?: return WindowStateSnapshot()
        val layoutMode = (parsed["layoutMode"] as? String)?.takeIf { it.isNotBlank() }
            ?: BridgeProtocol.LayoutMode.FULLSCREEN
        val adaptiveEnabled = parseBooleanOrDefault(parsed["adaptiveSingleEnabled"], default = true)
        val defaultWidthDp = DisplayGeometry.clampSingleWidthDp(
            (parsed["defaultSingleWidthDp"] as? Number)?.toInt() ?: DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP,
        )
        val tilingTopology = (parsed["tilingTopology"] as? String)?.takeIf { it.isNotBlank() }
            ?: BridgeProtocol.LayoutMode.NONE

        val ratios = (parsed["ratios"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toInt() }
            .orEmpty()

        val windows = (parsed["windows"] as? List<*>)
            ?.mapNotNull { elem -> parseManagedWindow(elem as? Map<*, *>) }
            .orEmpty()

        return WindowStateSnapshot(
            layoutMode = layoutMode,
            adaptiveSingleEnabled = adaptiveEnabled,
            defaultSingleWidthDp = defaultWidthDp,
            tilingTopology = tilingTopology,
            ratios = ratios,
            windows = windows,
        )
    }

    private fun parseManagedWindow(obj: Map<*, *>?): ManagedWindowInfo? {
        if (obj == null) return null
        val pkg = (obj["pkg"] as? String).orEmpty()
        if (pkg.isEmpty()) return null
        return ManagedWindowInfo(
            taskId = (obj["taskId"] as? Number)?.toInt() ?: -1,
            pkg = pkg,
            mode = (obj["mode"] as? String)?.takeIf { it.isNotBlank() }
                ?: BridgeProtocol.LayoutMode.FULLSCREEN,
            left = (obj["left"] as? Number)?.toInt() ?: 0,
            top = (obj["top"] as? Number)?.toInt() ?: 0,
            right = (obj["right"] as? Number)?.toInt() ?: 0,
            bottom = (obj["bottom"] as? Number)?.toInt() ?: 0,
            focused = parseBooleanOrDefault(obj["focused"], default = false),
            minimized = parseBooleanOrDefault(obj["minimized"], default = false),
        )
    }

    private fun parseBooleanOrDefault(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> default
    }

    fun parseTrustedMacClients(raw: String?): List<TrustedMacClient> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<TrustedMacClient>()
        val seen = LinkedHashSet<String>()
        for (entry in raw.split(';')) {
            val trimmed = entry.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val id = trimmed.substring(0, eq).trim()
            val name = trimmed.substring(eq + 1).trim().ifEmpty { id }
            if (id.isNotEmpty() && seen.add(id)) {
                out.add(TrustedMacClient(clientId = id, clientName = name))
            }
        }
        return out
    }

    fun parseAppModes(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val map = HashMap<String, Int>()
        for (item in raw.split(';')) {
            val eq = item.indexOf('=')
            if (eq <= 0 || eq >= item.length - 1) continue
            val pkg = item.substring(0, eq).trim()
            val mode = item.substring(eq + 1).trim().toIntOrNull() ?: continue
            if (pkg.isNotEmpty() && mode in 0..3) {
                map[pkg] = mode
            }
        }
        return map
    }

    fun parseEdidModes(modesRaw: String?): List<EdidMode> {
        if (modesRaw.isNullOrBlank()) return emptyList()
        val out = ArrayList<EdidMode>()
        for (entry in modesRaw.split(';')) {
            val kv = entry.trim().split(':')
            if (kv.size != 2) continue
            val id = kv[0].trim().toIntOrNull() ?: continue
            val rf = kv[1].trim().split('@')
            if (rf.size != 2) continue
            val wh = rf[0].trim().split('x')
            if (wh.size != 2) continue
            val pw = wh[0].trim().toIntOrNull() ?: continue
            val ph = wh[1].trim().toIntOrNull() ?: continue
            val pf = rf[1].trim().toFloatOrNull() ?: 60f
            if (pw > 0 && ph > 0 && pf > 0f) {
                out.add(EdidMode(id = id, width = pw, height = ph, fps = pf))
            }
        }
        return out
    }
}

/**
 * Lean, strict JSON object parser for JVM-testable IPC snapshots.
 * Rejects malformed syntax (unterminated strings/objects/arrays, missing separators,
 * unknown literals, excessive nesting, or trailing tokens) by returning `null`.
 */
internal class MiniJsonParser(private val src: String) {
    private class JsonFormatException(msg: String) : RuntimeException(msg)

    private var pos = 0

    fun parseRootObject(): Map<String, Any?>? {
        skipWs()
        if (pos >= src.length || src[pos] != '{') return null
        return try {
            val obj = parseObject(depth = 0)
            skipWs()
            if (pos != src.length) null else obj
        } catch (_: JsonFormatException) {
            null
        }
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun parseValue(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw JsonFormatException("Nesting depth exceeded")
        skipWs()
        if (pos >= src.length) throw JsonFormatException("Unexpected EOF")
        return when (src[pos]) {
            '{' -> parseObject(depth + 1)
            '[' -> parseArray(depth + 1)
            '"' -> parseString()
            't' -> expectLiteral("true", true)
            'f' -> expectLiteral("false", false)
            'n' -> expectLiteral("null", null)
            else -> parseNumber()
        }
    }

    private fun <T> expectLiteral(literal: String, value: T): T {
        if (!src.startsWith(literal, pos)) {
            throw JsonFormatException("Invalid token at $pos")
        }
        pos += literal.length
        return value
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        pos++ // '{'
        skipWs()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return map
        }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') {
                throw JsonFormatException("Expected object key at $pos")
            }
            val key = parseString()
            skipWs()
            if (pos >= src.length || src[pos] != ':') {
                throw JsonFormatException("Expected ':' at $pos")
            }
            pos++ // ':'
            map[key] = parseValue(depth)
            skipWs()
            if (pos >= src.length) throw JsonFormatException("Unterminated object")
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return map
                }
                else -> throw JsonFormatException("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun parseArray(depth: Int): List<Any?> {
        val list = ArrayList<Any?>()
        pos++ // '['
        skipWs()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return list
        }
        while (true) {
            list.add(parseValue(depth))
            skipWs()
            if (pos >= src.length) throw JsonFormatException("Unterminated array")
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return list
                }
                else -> throw JsonFormatException("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (pos >= src.length) throw JsonFormatException("Unterminated escape")
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (pos + 4 > src.length) throw JsonFormatException("Short unicode escape")
                        val code = src.substring(pos, pos + 4).toIntOrNull(16)
                            ?: throw JsonFormatException("Bad unicode escape")
                        sb.append(code.toChar())
                        pos += 4
                    }
                    else -> throw JsonFormatException("Unknown escape '\\$esc'")
                }
            } else {
                sb.append(c)
            }
        }
        throw JsonFormatException("Unterminated string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (pos < src.length && src[pos] == '-') pos++
        val intStart = pos
        while (pos < src.length && src[pos].isDigit()) pos++
        if (pos == intStart) throw JsonFormatException("Expected digit at $pos")
        var isFloat = false
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            val fracStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos == fracStart) throw JsonFormatException("Expected fractional digit at $pos")
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            isFloat = true
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            val expStart = pos
            while (pos < src.length && src[pos].isDigit()) pos++
            if (pos == expStart) throw JsonFormatException("Expected exponent digit at $pos")
        }
        val token = src.substring(start, pos)
        val parsed: Number? = if (isFloat) {
            token.toDoubleOrNull()
        } else {
            token.toIntOrNull() ?: token.toLongOrNull() ?: token.toDoubleOrNull()
        }
        return parsed ?: throw JsonFormatException("Invalid number '$token'")
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}
