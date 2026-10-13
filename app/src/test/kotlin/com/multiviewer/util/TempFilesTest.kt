package com.multiviewer.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TempFilesTest {
    @Test
    fun `withTempFile hands the block a real file and returns its result`() {
        var seen: File? = null

        val result = withTempFile("temp-files-test-", ".bin") { temp ->
            seen = temp
            temp.writeBytes(byteArrayOf(1, 2, 3))
            temp.readBytes().size
        }

        assertEquals(3, result)
    }

    @Test
    fun `withTempFile deletes the file once the block returns`() {
        var seen: File? = null

        withTempFile("temp-files-test-", ".bin") { temp ->
            seen = temp
            temp.writeBytes(byteArrayOf(1))
        }

        assertFalse(seen!!.exists(), "temp file survived the success path")
    }

    // The case the callers actually got wrong: they deleted the temp file on the line after the work,
    // so any failure in that work (a probe throwing on a malformed embedded video) skipped the delete
    // and stranded a whole extracted video in the temp directory for the rest of the session.
    @Test
    fun `withTempFile deletes the file when the block throws`() {
        var seen: File? = null

        assertFailsWith<RuntimeException> {
            withTempFile("temp-files-test-", ".bin") { temp ->
                seen = temp
                temp.writeBytes(byteArrayOf(1))
                throw RuntimeException("boom")
            }
        }

        assertFalse(seen!!.exists(), "temp file survived the failure path")
    }

    // Windows refuses to delete a file another process still holds open -- e.g. the extracted
    // motion-photo video while the preview player's ffmpeg is being torn down.
    @Test
    fun `deleteWhenReleased retries until the file can be deleted`() {
        val file = File.createTempFile("temp-files-test-", ".bin")
        var attempts = 0
        val done = java.util.concurrent.CountDownLatch(1)
        deleteWhenReleased(file, retryDelayMs = 5, delete = { f ->
            attempts++
            (attempts >= 3 && f.delete()).also { if (it) done.countDown() }
        })
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS), "never deleted")
        assertFalse(file.exists())
        assertEquals(3, attempts)
    }

    @Test
    fun `deleteWhenReleased of null is a no-op`() {
        deleteWhenReleased(null)
    }
}
