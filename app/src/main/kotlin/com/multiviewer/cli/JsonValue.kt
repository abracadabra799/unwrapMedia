package com.multiviewer.cli

// A minimal, BoxNode-agnostic JSON value tree and pretty-printer -- this project has no JSON
// library dependency, and this shape (no polymorphism beyond these four cases, no maps) doesn't
// need one. One recursive `render` handles indentation uniformly for both JObject and JArray,
// rather than hand-rolling padding arithmetic separately at each call site.
sealed class JsonValue {
    data class JObject(val entries: List<Pair<String, JsonValue>>) : JsonValue()
    data class JArray(val items: List<JsonValue>) : JsonValue()
    data class JString(val value: String) : JsonValue()
    data class JNumber(val value: Long) : JsonValue()
}

fun JsonValue.render(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val childPad = "  ".repeat(indent + 1)
    return when (this) {
        is JsonValue.JObject -> if (entries.isEmpty()) {
            "{}"
        } else {
            "{\n" + entries.joinToString(",\n") { (key, value) ->
                "$childPad${jsonString(key)}: ${value.render(indent + 1)}"
            } + "\n$pad}"
        }
        is JsonValue.JArray -> if (items.isEmpty()) {
            "[]"
        } else {
            "[\n" + items.joinToString(",\n") { "$childPad${it.render(indent + 1)}" } + "\n$pad]"
        }
        is JsonValue.JString -> jsonString(value)
        is JsonValue.JNumber -> value.toString()
    }
}

private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}
