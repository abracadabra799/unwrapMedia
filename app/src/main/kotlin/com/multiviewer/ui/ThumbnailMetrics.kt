package com.multiviewer.ui

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** Appends opt-in thumbnail timings without recording file paths or names. */
internal class ThumbnailMetricsWriter(
    private val outputFile: File,
    private val enabled: Boolean,
    private val sessionId: String = UUID.randomUUID().toString(),
) {
    @Synchronized
    fun record(
        event: String,
        mediaType: String? = null,
        sourceBytes: Long? = null,
        queueMs: Long? = null,
        decodeMs: Long? = null,
        publishMs: Long? = null,
        elapsedMs: Long? = null,
        itemCount: Int? = null,
        result: String? = null,
        timestamp: String = Instant.now().toString(),
    ) {
        if (!enabled) return
        outputFile.parentFile?.mkdirs()
        val hasContent = outputFile.exists() && outputFile.length() > 0L
        OutputStreamWriter(FileOutputStream(outputFile, true), StandardCharsets.UTF_8).use { writer ->
            if (!hasContent) {
                writer.appendLine("session_id,timestamp,event,media_type,source_bytes,queue_ms,decode_ms,publish_ms,elapsed_ms,item_count,result")
            }
            val fields = listOf(
                sessionId,
                timestamp,
                event,
                mediaType.orEmpty(),
                sourceBytes?.toString().orEmpty(),
                queueMs?.toString().orEmpty(),
                decodeMs?.toString().orEmpty(),
                publishMs?.toString().orEmpty(),
                elapsedMs?.toString().orEmpty(),
                itemCount?.toString().orEmpty(),
                result.orEmpty(),
            )
            writer.appendLine(fields.joinToString(",", transform = ::escapeCsv))
        }
    }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}

/** Enable with UNWRAPMEDIA_THUMBNAIL_METRICS=1. The default app path has no logging I/O. */
internal object ThumbnailMetrics {
    private const val ENABLE_ENV = "UNWRAPMEDIA_THUMBNAIL_METRICS"
    private val writer: ThumbnailMetricsWriter? by lazy {
        if (System.getenv(ENABLE_ENV) != "1") {
            null
        } else {
            val file = File(System.getProperty("user.home"), ".unwrapMedia/diagnostics/thumbnail-benchmark.csv")
            ThumbnailMetricsWriter(file, enabled = true)
        }
    }

    fun record(
        event: String,
        mediaType: String? = null,
        sourceBytes: Long? = null,
        queueMs: Long? = null,
        decodeMs: Long? = null,
        publishMs: Long? = null,
        elapsedMs: Long? = null,
        itemCount: Int? = null,
        result: String? = null,
    ) {
        runCatching {
            writer?.record(
                event = event,
                mediaType = mediaType,
                sourceBytes = sourceBytes,
                queueMs = queueMs,
                decodeMs = decodeMs,
                publishMs = publishMs,
                elapsedMs = elapsedMs,
                itemCount = itemCount,
                result = result,
            )
        }
    }
}
