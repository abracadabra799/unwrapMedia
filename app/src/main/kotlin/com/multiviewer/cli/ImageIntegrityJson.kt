package com.multiviewer.cli

import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.ui.ImageDecodeReport
import com.multiviewer.ui.MotionPhotoIntegrityReport
import com.multiviewer.ui.toCheckStatus
import java.io.File

private fun size(w: Int?, h: Int?): JsonValue = JsonValue.JString(if (w != null && h != null) "${w}x$h" else "unknown")

/** Replaces temp-dir / inspected-file absolute paths in [text] with bare file names. */
internal fun scrubPaths(text: String, file: File?): String {
    var out = text
    // Inspected file: exact path -> name FIRST (longest first so nested paths win).
    // This must happen before stripping tmpdir, so paths like /tmp/secret-dir/photo.jpg
    // get replaced before /tmp/ is removed.
    val prefixes = LinkedHashSet<String>()
    file?.let {
        prefixes += it.absolutePath
        runCatching { it.canonicalPath }.getOrNull()?.let { c -> prefixes += c }
    }
    for (p in prefixes.sortedByDescending { it.length }) {
        if (p.isNotEmpty()) out = out.replace(p, file!!.name)
    }
    // Then strip tmpdir prefixes with anchored regex to avoid matching subpaths.
    // E.g., /var/tmp/x won't match when tmpdir is /tmp.
    val tmpRaw = System.getProperty("java.io.tmpdir")
    if (!tmpRaw.isNullOrEmpty()) {
        val dirs = LinkedHashSet<String>()
        val f = File(tmpRaw)
        dirs += f.path
        dirs += f.absolutePath
        runCatching { f.canonicalPath }.getOrNull()?.let { dirs += it }
        dirs += tmpRaw
        for (d in dirs) {
            val base = d.trimEnd('/', '\\')
            if (base.isEmpty()) continue
            val sep = "(?<![\\w/\\\\.-])" + Regex.escape(base) + "[/\\\\]+"
            out = Regex(sep).replace(out, "")
        }
    }
    return out
}

fun motionPhotoJson(detected: Boolean, report: MotionPhotoIntegrityReport?, file: File? = null, error: String? = null): JsonValue? {
    if (!detected) return null
    if (error != null) return JsonValue.JObject(listOf("status" to JsonValue.JString("FAIL"), "error" to JsonValue.JString(scrubPaths(error, file))))
    if (report == null) return JsonValue.JObject(listOf("status" to JsonValue.JString("NOT_RUN")))
    if (report.detectedFormats.isEmpty()) {
        return JsonValue.JObject(
            listOf(
                "status" to JsonValue.JString("SKIP"),
                "detail" to JsonValue.JString("No motion photo format confirmed by the analyzer"),
            ),
        )
    }
    fun check(section: String, c: SefCheckResult) = JsonValue.JObject(
        listOf(
            "section" to JsonValue.JString(section),
            "status" to JsonValue.JString(c.severity.toCheckStatus().name),
            "label" to JsonValue.JString(c.label),
            "detail" to JsonValue.JString(scrubPaths(c.detail, file)),
        ),
    )
    val checks = buildList {
        report.googleXmpChecks.forEach { add(check("google_xmp", it)) }
        report.sefSection?.let { sef ->
            sef.structuralChecks.forEach { add(check("sef_structure", it)) }
            sef.semanticChecks.forEach { add(check("sef_semantic", it)) }
            sef.directoryEntries.forEach { row ->
                add(
                    check(
                        "sef_directory",
                        SefCheckResult(
                            row.status,
                            row.name ?: "#${row.entryIndex}",
                            "marker ${row.markerHex} offset ${row.declaredOffset} length ${row.declaredLength}",
                        ),
                    ),
                )
            }
        }
        report.appleMpvdChecks.forEach { add(check("heic_mpvd", it)) }
        report.decodeChecks.forEach { add(check("video_decode", it)) }
    }
    return JsonValue.JObject(
        listOf(
            "status" to JsonValue.JString(report.overallSeverity.toCheckStatus().name),
            "detectedFormats" to JsonValue.JArray(report.detectedFormats.map { JsonValue.JString(it.name) }),
            "checks" to JsonValue.JArray(checks),
        ),
    )
}

fun imageIntegrityJson(structure: ImageStructureReport, decode: ImageDecodeReport?, motionPhoto: JsonValue? = null): JsonValue = JsonValue.JObject(
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
    ) + if (motionPhoto != null) listOf("motionPhoto" to motionPhoto) else emptyList(),
)
