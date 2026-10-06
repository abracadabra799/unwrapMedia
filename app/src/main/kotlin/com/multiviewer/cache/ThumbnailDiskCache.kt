package com.multiviewer.cache

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Bounded disk cache for already-encoded explorer thumbnails. */
class ThumbnailDiskCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
    }

    @Synchronized
    fun get(source: File, longestEdge: Int): ByteArray? {
        val cachedFile = cacheFile(source, longestEdge)
        if (!cachedFile.isFile) return null
        return try {
            cachedFile.setLastModified(System.currentTimeMillis())
            cachedFile.readBytes()
        } catch (_: Exception) {
            cachedFile.delete()
            null
        }
    }

    @Synchronized
    fun put(source: File, longestEdge: Int, encodedThumbnail: ByteArray) {
        if (encodedThumbnail.isEmpty() || maxBytes == 0L) return
        if (!directory.exists() && !directory.mkdirs()) return

        val destination = cacheFile(source, longestEdge)
        val temp = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(encodedThumbnail)
                stream.fd.sync()
            }
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            evictToLimit(protectedFile = destination)
        } catch (_: Exception) {
            temp.delete()
        }
    }

    @Synchronized
    fun remove(source: File, longestEdge: Int) {
        cacheFile(source, longestEdge).delete()
    }

    /** Stable, path-private cache key; source metadata and output size invalidate stale entries. */
    fun cacheKeyFor(source: File, longestEdge: Int): String {
        require(longestEdge > 0) { "longestEdge must be positive" }
        val canonicalPath = runCatching { source.canonicalPath }.getOrDefault(source.absolutePath)
        val identity = "v1|$canonicalPath|${source.length()}|${source.lastModified()}|$longestEdge"
        return sha256(identity)
    }

    private fun cacheFile(source: File, longestEdge: Int): File =
        File(directory, "${cacheKeyFor(source, longestEdge)}.thumb")

    private fun evictToLimit(protectedFile: File) {
        val entries = directory.listFiles { file -> file.isFile && file.extension == "thumb" }
            ?.sortedBy { it.lastModified() }
            ?: return
        var totalBytes = entries.sumOf { it.length() }
        for (entry in entries) {
            if (totalBytes <= maxBytes) break
            if (entry == protectedFile) continue
            val size = entry.length()
            if (entry.delete()) totalBytes -= size
        }
        if (totalBytes > maxBytes) {
            protectedFile.delete()
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
    }
}
