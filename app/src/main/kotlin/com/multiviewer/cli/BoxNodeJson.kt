package com.multiviewer.cli

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GridData
import com.multiviewer.parser.TableData
import java.io.File

fun buildDumpJson(file: File, root: BoxNode): String {
    val wrapper = JsonValue.JObject(
        listOf(
            "file" to JsonValue.JString(file.name),
            "fileSize" to JsonValue.JNumber(file.length()),
            "root" to root.toJsonValue(),
        ),
    )
    return wrapper.render()
}

fun BoxNode.toJsonValue(): JsonValue {
    val entries = mutableListOf(
        "type" to JsonValue.JString(type),
        "offset" to JsonValue.JNumber(offset),
        "headerSize" to JsonValue.JNumber(headerSize.toLong()),
        "size" to JsonValue.JNumber(size),
    )
    summary?.let { entries.add("summary" to JsonValue.JString(it)) }
    if (fields.isNotEmpty()) {
        entries.add("fields" to JsonValue.JArray(fields.map { it.toJsonValue() }))
    }
    if (warnings.isNotEmpty()) {
        entries.add("warnings" to JsonValue.JArray(warnings.map { JsonValue.JString(it) }))
    }
    table?.let { entries.add("table" to it.toJsonValue()) }
    grid?.let { entries.add("grid" to it.toJsonValue()) }
    if (children.isNotEmpty()) {
        entries.add("children" to JsonValue.JArray(children.map { it.toJsonValue() }))
    }
    return JsonValue.JObject(entries)
}

private fun BoxField.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "name" to JsonValue.JString(name),
        "value" to JsonValue.JString(value),
        "offset" to JsonValue.JNumber(offset),
        "length" to JsonValue.JNumber(length),
    ),
)

private fun TableData.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "columns" to JsonValue.JArray(columns.map { JsonValue.JString(it) }),
        "fieldWidths" to JsonValue.JArray(fieldWidths.map { JsonValue.JNumber(it.toLong()) }),
        "entriesStart" to JsonValue.JNumber(entriesStart),
        "entryCount" to JsonValue.JNumber(entryCount),
    ),
)

private fun GridData.toJsonValue(): JsonValue = JsonValue.JObject(
    listOf(
        "columns" to JsonValue.JNumber(columns.toLong()),
        "rows" to JsonValue.JNumber(rows.toLong()),
        "values" to JsonValue.JArray(values.map { JsonValue.JString(it) }),
    ),
)
