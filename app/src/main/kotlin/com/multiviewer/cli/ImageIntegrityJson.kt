package com.multiviewer.cli

import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.ui.I18n
import com.multiviewer.ui.ImageDecodeReport
import java.io.File
import java.security.MessageDigest

private fun size(w: Int?, h: Int?): JsonValue = JsonValue.JString(if (w != null && h != null) "${w}x$h" else "unknown")

fun imageIntegrityJson(structure: ImageStructureReport, decode: ImageDecodeReport?): JsonValue = JsonValue.JObject(
    listOf(
        "structure" to JsonValue.JObject(
            listOf(
                "format" to JsonValue.JString(structure.format),
                "overall" to JsonValue.JString(structure.overall.name),
                "declaredSize" to size(structure.declaredWidth, structure.declaredHeight),
                "items" to JsonValue.JArray(structure.items.map { item ->
                    JsonValue.JObject(buildList {
                        add("id" to JsonValue.JString(item.id))
                        add("title" to JsonValue.JString(item.title))
                        add("status" to JsonValue.JString(item.status.name))
                        add("detail" to JsonValue.JString(item.detail))
                        item.offset?.let { add("offset" to JsonValue.JNumber(it)) }
                        item.length?.let { add("length" to JsonValue.JNumber(it)) }
                    })
                }),
            ),
        ),
        "decode" to if (decode == null) {
            JsonValue.JObject(listOf("status" to JsonValue.JString("NOT_RUN")))
        } else {
            JsonValue.JObject(
                listOf(
                    "status" to JsonValue.JString(decode.status.name),
                    "source" to JsonValue.JString(decode.source),
                    "resolution" to JsonValue.JString(decode.resolutionStatus.name),
                    "ffmpeg" to JsonValue.JObject(
                        listOf(
                            "status" to JsonValue.JString(decode.ffmpegStatus.name),
                            "version" to JsonValue.JString(decode.ffmpegVersion ?: "unknown"),
                            "decodedFrames" to JsonValue.JNumber(decode.decodedFrames),
                            "decodedSize" to size(decode.decodedWidth, decode.decodedHeight),
                            "logsTruncated" to JsonValue.JString(decode.logsTruncated.toString()),
                            "logs" to JsonValue.JArray(decode.logs.map { JsonValue.JString(it) }),
                        ),
                    ),
                    "skia" to JsonValue.JObject(
                        listOf(
                            "status" to JsonValue.JString(
                                when {
                                    !decode.skia.attempted -> "SKIP"
                                    decode.skia.ok -> "CLEAN"
                                    else -> "ISSUES"
                                },
                            ),
                            "detail" to JsonValue.JString(decode.skia.detail),
                        ),
                    ),
                ),
            )
        },
    ),
)

/** Reproducible case file: identity (name, size, SHA-256 — never the absolute path) + integrity results. */
fun buildImageIntegrityCaseJson(file: File, structure: ImageStructureReport, decode: ImageDecodeReport?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return JsonValue.JObject(
        listOf(
            "schemaVersion" to JsonValue.JNumber(1),
            "tool" to JsonValue.JObject(listOf("name" to JsonValue.JString("unwrapMedia"), "version" to JsonValue.JString(I18n.APP_VERSION))),
            "file" to JsonValue.JObject(
                listOf(
                    "name" to JsonValue.JString(file.name),
                    "sizeBytes" to JsonValue.JNumber(file.length()),
                    "sha256" to JsonValue.JString(digest.digest().joinToString("") { "%02x".format(it) }),
                ),
            ),
            "imageIntegrity" to imageIntegrityJson(structure, decode),
        ),
    ).render()
}
