package com.multiviewer.ui.ai

private const val MAX_JSON_DEPTH = 64

/** What one CLI run said, read from its stdout. [Error] = the CLI reported a failure in its own output. */
sealed interface AiParsedOutput {
    data class Answer(val text: String) : AiParsedOutput
    data class Error(val message: String) : AiParsedOutput
}

/**
 * Minimal JSON reader for CLI output (no JSON dependency in this project; `JsonValue` is write-only and has no
 * fractional numbers). Returns Map<String, Any?> / List<Any?> / String / Double / Boolean / null, and null
 * for any syntax error, so a top-level JSON `null` and bad input look the same -- callers expect objects.
 */
fun parseJsonLoose(text: String): Any? = try {
    val reader = JsonReader(text)
    val value = reader.value(0)
    reader.skipWs()
    if (reader.pos != text.length) null else value
} catch (_: JsonSyntax) {
    null
}

/**
 * The answer of a `claude -p --output-format stream-json` run: the last `type:"result"` event. Hook, init and
 * assistant events are skipped. No result event (cut-off or crashed run) is an error, so a partial stream is
 * never shown as an answer.
 */
fun parseClaudeStreamJson(stdout: String): AiParsedOutput {
    val result = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { parseJsonLoose(it) as? Map<*, *> }
        .lastOrNull { it["type"] == "result" }
        ?: return AiParsedOutput.Error("Claude CLI ended without a result event")
    val text = result["result"] as? String
    val failed = result["is_error"] == true || result["subtype"] != "success"
    return if (failed) {
        AiParsedOutput.Error(text?.takeIf { it.isNotBlank() } ?: (result["subtype"] as? String) ?: "error")
    } else {
        AiParsedOutput.Answer(text?.trim().orEmpty())
    }
}

private class JsonSyntax : Exception()

private class JsonReader(private val s: String) {
    var pos = 0

    fun skipWs() {
        while (pos < s.length && s[pos] in " \t\r\n") pos++
    }

    private fun fail(): Nothing = throw JsonSyntax()

    fun value(depth: Int): Any? {
        if (depth > MAX_JSON_DEPTH) fail()
        skipWs()
        if (pos >= s.length) fail()
        return when (s[pos]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> num()
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, pos)) fail()
        pos += word.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        pos++ // {
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (s.getOrNull(pos) == '}') { pos++; return out }
        while (true) {
            skipWs()
            if (s.getOrNull(pos) != '"') fail()
            val key = str()
            skipWs()
            if (s.getOrNull(pos) != ':') fail()
            pos++
            out[key] = value(depth + 1)
            skipWs()
            when (s.getOrNull(pos)) {
                ',' -> pos++
                '}' -> { pos++; return out }
                else -> fail()
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        pos++ // [
        val out = ArrayList<Any?>()
        skipWs()
        if (s.getOrNull(pos) == ']') { pos++; return out }
        while (true) {
            out += value(depth + 1)
            skipWs()
            when (s.getOrNull(pos)) {
                ',' -> pos++
                ']' -> { pos++; return out }
                else -> fail()
            }
        }
    }

    private fun str(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) fail()
            val c = s[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= s.length) fail()
                    when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) fail()
                            val hex = s.substring(pos, pos + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                            sb.append(Char(hex.toInt(16)))
                            pos += 4
                        }
                        else -> fail()
                    }
                }
                c < ' ' -> fail()
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
        if (pos == start) fail()
        return s.substring(start, pos).toDoubleOrNull() ?: fail()
    }
}
