package com.multiviewer.ui

import java.io.File

/** Access-ordered thumbnail cache whose entries are invalidated when the source file changes. */
internal class ThumbnailMemoryCache<T>(private val maxEntries: Int = 200) {
    private data class Entry<T>(val length: Long, val lastModified: Long, val value: T)

    private val entries = LinkedHashMap<String, Entry<T>>(128, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun get(file: File): T? {
        val path = file.absolutePath
        val entry = entries[path] ?: return null
        if (entry.length != file.length() || entry.lastModified != file.lastModified()) {
            entries.remove(path)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(file: File, value: T) {
        entries[file.absolutePath] = Entry(file.length(), file.lastModified(), value)
        if (entries.size > maxEntries) {
            val oldestKey = entries.entries.iterator().next().key
            entries.remove(oldestKey)
        }
    }
}
