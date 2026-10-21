package com.multiviewer.cache

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class ThumbnailSourceFingerprint(val length: Long, val lastModified: Long) {
    fun matches(source: File): Boolean = length == source.length() && lastModified == source.lastModified()

    companion object {
        fun capture(source: File) = ThumbnailSourceFingerprint(source.length(), source.lastModified())
    }
}

/** Bounded disk cache for already-encoded explorer thumbnails. */
class ThumbnailDiskCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val accessOrder = mutableMapOf<String, Long>()
    private var nextAccessOrder = 0L

    init {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }
    }

    fun get(source: File, longestEdge: Int): ByteArray? =
        get(source, longestEdge, ThumbnailSourceFingerprint.capture(source))

    fun get(source: File, longestEdge: Int, fingerprint: ThumbnailSourceFingerprint): ByteArray? {
        val cachedFile = cacheFile(source, longestEdge, fingerprint)
        if (!cachedFile.isFile) return null
        return try {
            val bytes = cachedFile.readBytes()
            synchronized(this) {
                accessOrder[cachedFile.name] = ++nextAccessOrder
            }
            bytes
        } catch (_: Exception) {
            synchronized(this) {
                cachedFile.delete()
                accessOrder.remove(cachedFile.name)
            }
            null
        }
    }

    fun put(source: File, longestEdge: Int, encodedThumbnail: ByteArray) =
        put(source, longestEdge, encodedThumbnail, ThumbnailSourceFingerprint.capture(source))

    fun put(source: File, longestEdge: Int, encodedThumbnail: ByteArray, fingerprint: ThumbnailSourceFingerprint) {
        if (encodedThumbnail.isEmpty() || maxBytes == 0L) return
        if (!directory.exists() && !directory.mkdirs()) return

        val destination = cacheFile(source, longestEdge, fingerprint)
        val temp = File(directory, "${destination.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(encodedThumbnail)
            }
            synchronized(this) {
                try {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                accessOrder[destination.name] = ++nextAccessOrder
                evictToLimit(protectedFile = destination)
            }
        } catch (_: Exception) {
            temp.delete()
        }
    }

    @Synchronized
    fun remove(source: File, longestEdge: Int) =
        remove(source, longestEdge, ThumbnailSourceFingerprint.capture(source))

    @Synchronized
    fun remove(source: File, longestEdge: Int, fingerprint: ThumbnailSourceFingerprint) {
        val cachedFile = cacheFile(source, longestEdge, fingerprint)
        cachedFile.delete()
        accessOrder.remove(cachedFile.name)
    }

    /** Stable, path-private cache key; source metadata and output size invalidate stale entries. */
    fun cacheKeyFor(source: File, longestEdge: Int): String =
        cacheKeyFor(source, longestEdge, ThumbnailSourceFingerprint.capture(source))

    fun cacheKeyFor(source: File, longestEdge: Int, fingerprint: ThumbnailSourceFingerprint): String {
        require(longestEdge > 0) { "longestEdge must be positive" }
        val canonicalPath = runCatching { source.canonicalPath }.getOrDefault(source.absolutePath)
        val identity = "v1|$canonicalPath|${fingerprint.length}|${fingerprint.lastModified}|$longestEdge"
        return sha256(identity)
    }

    private fun cacheFile(source: File, longestEdge: Int): File =
        cacheFile(source, longestEdge, ThumbnailSourceFingerprint.capture(source))

    private fun cacheFile(source: File, longestEdge: Int, fingerprint: ThumbnailSourceFingerprint): File =
        File(directory, "${cacheKeyFor(source, longestEdge, fingerprint)}.thumb")

    private fun evictToLimit(protectedFile: File) {
        val entries = directory.listFiles { file -> file.isFile && file.extension == "thumb" }
            ?.sortedWith(compareBy<File> { if (it.name in accessOrder) 1 else 0 }
                .thenBy { accessOrder[it.name] ?: it.lastModified() }
                .thenBy { it.name })
            ?: return
        accessOrder.keys.retainAll(entries.mapTo(mutableSetOf()) { it.name })
        var totalBytes = entries.sumOf { it.length() }
        for (entry in entries) {
            if (totalBytes <= maxBytes) break
            if (entry == protectedFile) continue
            val size = entry.length()
            if (entry.delete()) {
                totalBytes -= size
                accessOrder.remove(entry.name)
            }
        }
        if (totalBytes > maxBytes) {
            protectedFile.delete()
            accessOrder.remove(protectedFile.name)
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
