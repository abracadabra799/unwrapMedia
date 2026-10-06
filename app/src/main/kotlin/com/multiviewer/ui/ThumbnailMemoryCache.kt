package com.multiviewer.ui

import java.io.File
import com.multiviewer.cache.ThumbnailSourceFingerprint

/** Access-ordered thumbnail cache whose entries are invalidated when the source file changes. */
internal class ThumbnailMemoryCache<T>(private val maxEntries: Int = 200) {
    private data class Entry<T>(val fingerprint: ThumbnailSourceFingerprint, val value: T)

    private val entries = LinkedHashMap<String, Entry<T>>(128, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun get(file: File): T? {
        val path = file.absolutePath
        val entry = entries[path] ?: return null
        if (!entry.fingerprint.matches(file)) {
            entries.remove(path)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(file: File, value: T) = put(file, value, ThumbnailSourceFingerprint.capture(file))

    @Synchronized
    fun put(file: File, value: T, fingerprint: ThumbnailSourceFingerprint) {
        entries[file.absolutePath] = Entry(fingerprint, value)
        if (entries.size > maxEntries) {
            val oldestKey = entries.entries.iterator().next().key
            entries.remove(oldestKey)
        }
    }
}
