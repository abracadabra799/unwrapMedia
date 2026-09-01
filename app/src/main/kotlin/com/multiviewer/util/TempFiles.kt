package com.multiviewer.util

import java.io.File

/**
 * Runs [block] against a fresh temp file and deletes that file on every exit path, including the one
 * that throws.
 *
 * This exists because the "create temp, use it, delete it" sequence written out longhand puts the
 * delete on the line *after* the work, so any failure in the work skips it -- and the usual patch
 * for that, [File.deleteOnExit], defers the cleanup to JVM exit while permanently pinning the path
 * in an internal set that is never pruned. A finally block cleans up immediately and leaves nothing
 * behind, so callers whose temp file's whole lifetime fits inside one function need neither.
 *
 * Returns null (without running [block]) if the temp file can't be created; exceptions thrown by
 * [block] itself propagate to the caller, which is what makes the delete-on-failure behavior
 * observable rather than silently swallowed.
 */
internal fun <T> withTempFile(prefix: String, suffix: String, block: (File) -> T): T? {
    val temp = try {
        File.createTempFile(prefix, suffix)
    } catch (e: Exception) {
        return null
    }
    return try {
        block(temp)
    } finally {
        temp.delete()
    }
}
