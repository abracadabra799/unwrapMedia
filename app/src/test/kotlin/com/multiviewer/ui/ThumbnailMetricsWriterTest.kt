package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThumbnailMetricsWriterTest {
    @Test
    fun `writes timing metrics as csv without any media path field`() {
        val directory = createTempDir(prefix = "thumbnail-metrics-test")
        val output = File(directory, "metrics.csv")
        try {
            ThumbnailMetricsWriter(output, enabled = true, sessionId = "test-session").record(
                event = "thumbnail",
                mediaType = "jpg",
                sourceBytes = 12_345,
                queueMs = 7,
                decodeMs = 42,
                publishMs = 3,
                elapsedMs = 52,
                itemCount = null,
                result = "skia",
                timestamp = "2026-10-06T00:00:00Z",
            )

            assertEquals(
                "session_id,timestamp,event,media_type,source_bytes,queue_ms,decode_ms,publish_ms,elapsed_ms,item_count,result\n" +
                    "test-session,2026-10-06T00:00:00Z,thumbnail,jpg,12345,7,42,3,52,,skia\n",
                output.readText(),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `does not create a metrics file when diagnostics are disabled`() {
        val directory = createTempDir(prefix = "thumbnail-metrics-disabled-test")
        val output = File(directory, "metrics.csv")
        try {
            ThumbnailMetricsWriter(output, enabled = false).record(
                event = "folder_list",
                elapsedMs = 10,
                timestamp = "2026-10-06T00:00:00Z",
            )
            assertFalse(output.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
