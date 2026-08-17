package com.multiviewer.cache

import com.multiviewer.ui.FrameInfo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object MediaIndexCache {
    private const val MAX_L1_ENTRIES = 10
    private const val CACHE_MAGIC = 0x554E5752 // "UNWR"

    // L1: In-Memory LRU Cache
    private val l1Cache = object : LinkedHashMap<String, List<FrameInfo>>(MAX_L1_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<FrameInfo>>?): Boolean {
            return size > MAX_L1_ENTRIES
        }
    }

    private var cacheDir: File? = null

    fun initialize(directory: File) {
        synchronized(this) {
            cacheDir = directory
            if (!directory.exists()) {
                directory.mkdirs()
            }
        }
    }

    fun getCacheKey(file: File): String {
        val canonical = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
        val raw = "${canonical}_${file.length()}_${file.lastModified()}"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun get(file: File): List<FrameInfo>? = synchronized(this) {
        val key = getCacheKey(file)
        l1Cache[key]?.let { return it }

        // Try L2 disk cache
        val diskFrames = loadFromDisk(key)
        if (diskFrames != null) {
            l1Cache[key] = diskFrames
            return diskFrames
        }
        return null
    }

    fun put(file: File, frames: List<FrameInfo>) = synchronized(this) {
        if (frames.isEmpty()) return
        val key = getCacheKey(file)
        l1Cache[key] = frames
        saveToDisk(key, frames)
    }

    fun clear() = synchronized(this) {
        l1Cache.clear()
        val dir = cacheDir
        if (dir != null && dir.exists()) {
            dir.listFiles { _, name -> name.endsWith(".fidx") }?.forEach { it.delete() }
        }
    }

    private fun loadFromDisk(key: String): List<FrameInfo>? {
        val dir = cacheDir ?: return null
        val cacheFile = File(dir, "$key.fidx")
        if (!cacheFile.exists()) return null

        return try {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile))).use { input ->
                val magic = input.readInt()
                if (magic != CACHE_MAGIC) return null
                val version = input.readInt()
                if (version != 1) return null
                val count = input.readInt()
                if (count <= 0 || count > 10_000_000) return null

                val frames = ArrayList<FrameInfo>(count)
                for (i in 0 until count) {
                    val index = input.readInt()
                    val type = input.readChar()
                    val sizeBytes = input.readInt()
                    val ptsSeconds = input.readDouble()
                    val hasOffset = input.readBoolean()
                    val byteOffset = if (hasOffset) input.readLong() else null
                    frames.add(FrameInfo(index, type, sizeBytes, ptsSeconds, byteOffset))
                }
                frames
            }
        } catch (e: Exception) {
            cacheFile.delete()
            null
        }
    }

    private fun saveToDisk(key: String, frames: List<FrameInfo>) {
        val dir = cacheDir ?: return
        val cacheFile = File(dir, "$key.fidx")
        val tempFile = File(dir, "$key.tmp")

        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeInt(1) // version
                out.writeInt(frames.size)
                for (frame in frames) {
                    out.writeInt(frame.index)
                    out.writeChar(frame.type.code)
                    out.writeInt(frame.sizeBytes)
                    out.writeDouble(frame.ptsSeconds)
                    if (frame.byteOffset != null) {
                        out.writeBoolean(true)
                        out.writeLong(frame.byteOffset)
                    } else {
                        out.writeBoolean(false)
                    }
                }
            }
            if (tempFile.exists()) {
                if (cacheFile.exists()) cacheFile.delete()
                tempFile.renameTo(cacheFile)
            }
        } catch (e: Exception) {
            tempFile.delete()
        }
    }
}
