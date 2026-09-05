package com.multiviewer.parser

import java.io.File
import java.io.RandomAccessFile

/**
 * One XMP packet as it physically sits in the file.
 *
 * [index] is its position in file order (0-based), [offset] the byte offset of its opening
 * `<x:xmpmeta`, and [byteLength] the packet's size through its closing tag.
 */
data class XmpPacket(
    val index: Int,
    val offset: Long,
    val byteLength: Int,
    val text: String,
)

private val XMP_START = "<x:xmpmeta".toByteArray(Charsets.US_ASCII)
private val XMP_END = "</x:xmpmeta>".toByteArray(Charsets.US_ASCII)

private const val SCAN_CHUNK_BYTES = 1 shl 20

/**
 * Finds every XMP packet in [file] by scanning its raw bytes.
 *
 * Deliberately not driven off the parsed box/segment tree: the walkers surface only the XMP they
 * descend into (JPEG's top-level APP1, HEIC's meta items), whereas real gain map files also carry a
 * packet inside the *embedded* secondary image, which no walker enters. Scanning bytes finds both,
 * and works the same for any container -- JPEG, HEIC, or MP4's uuid boxes.
 *
 * Packets are matched on the `<x:xmpmeta` … `</x:xmpmeta>` element rather than the `<?xpacket?>`
 * wrapper, since the wrapper is optional while the element is present in every packet observed in
 * the wild. An opening tag with no matching close (a truncated file) is skipped rather than being
 * reported as a packet running to EOF.
 *
 * Returns an empty list, never throws, when the file is missing or unreadable -- callers treat "no
 * XMP" and "couldn't read" the same way.
 */
fun scanXmpPackets(
    file: File,
    maxPackets: Int = 32,
    maxPacketBytes: Int = 8 * 1024 * 1024,
): List<XmpPacket> {
    if (!file.isFile) return emptyList()
    return try {
        RandomAccessFile(file, "r").use { raf ->
            val starts = findOffsets(raf, XMP_START, maxPackets)
            if (starts.isEmpty()) return emptyList()
            val ends = findOffsets(raf, XMP_END, maxPackets + 1)
            val packets = mutableListOf<XmpPacket>()
            for (start in starts) {
                // The first close after this open. A packet whose close is missing (or absurdly far
                // away, which means the close belongs to something else) is dropped.
                val end = ends.firstOrNull { it > start } ?: continue
                val length = (end + XMP_END.size - start)
                if (length <= 0 || length > maxPacketBytes) continue
                val bytes = ByteArray(length.toInt())
                raf.seek(start)
                raf.readFully(bytes)
                packets.add(
                    XmpPacket(
                        index = packets.size,
                        offset = start,
                        byteLength = length.toInt(),
                        text = String(bytes, Charsets.UTF_8),
                    ),
                )
                if (packets.size >= maxPackets) break
            }
            packets
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * The schemas or role a packet carries, for telling one packet apart from another at a glance.
 *
 * Distinguishes primary container packets (which may reference Container:Directory, MotionPhoto,
 * or Primary semantic) from dedicated secondary packets (such as GainMap / hdrgm parameter blocks).
 */
fun xmpPacketTopics(text: String): List<String> {
    val topics = mutableListOf<String>()

    val hasContainer = text.contains("Container:Directory", ignoreCase = true) ||
        text.contains("Item:Semantic=\"Primary\"", ignoreCase = true) ||
        text.contains("xmlns:Container=", ignoreCase = true)

    val hasMotionPhoto = text.contains("MotionPhoto", ignoreCase = true)
    val hasGainMapParams = text.contains("GainMapMin", ignoreCase = true) ||
        text.contains("GainMapMax", ignoreCase = true) ||
        text.contains("HDRCapacityMin", ignoreCase = true)

    if (hasContainer) {
        topics.add("Primary")
        if (text.contains("Container", ignoreCase = true) && !topics.contains("Container")) {
            topics.add("Container")
        }
        if (hasMotionPhoto && !topics.contains("MotionPhoto")) {
            topics.add("MotionPhoto")
        }
        if (text.contains("GCamera", ignoreCase = true) && !topics.contains("GCamera")) {
            topics.add("GCamera")
        }
        if (text.contains("GDepth", ignoreCase = true) && !topics.contains("Depth")) {
            topics.add("Depth")
        }
    } else if (hasGainMapParams) {
        topics.add("GainMap")
        if (text.contains("hdrgm", ignoreCase = true)) {
            topics.add("hdrgm")
        }
    } else {
        // General metadata schemas
        val generalMarkers = listOf(
            "MotionPhoto" to "MotionPhoto",
            "GCamera" to "GCamera",
            "GDepth" to "Depth",
            "photoshop" to "photoshop",
            "tiff" to "tiff",
            "exif" to "exif",
            "dc:" to "dc",
            "crs:" to "crs",
        )
        for ((marker, label) in generalMarkers) {
            if (text.contains(marker, ignoreCase = true) && !topics.contains(label)) {
                topics.add(label)
            }
        }
    }

    return topics
}

// Chunked search so a multi-gigabyte video never lands in memory at once. Each read keeps the last
// (pattern.size - 1) bytes of the previous chunk in front of the new data, so a match straddling a
// chunk boundary is still found.
private fun findOffsets(raf: RandomAccessFile, pattern: ByteArray, limit: Int): List<Long> {
    val overlap = pattern.size - 1
    val buffer = ByteArray(SCAN_CHUNK_BYTES + overlap)
    val offsets = mutableListOf<Long>()
    var windowStart = 0L
    var carry = 0
    raf.seek(0)
    while (offsets.size < limit) {
        val read = raf.read(buffer, carry, SCAN_CHUNK_BYTES)
        if (read <= 0) break
        val available = carry + read
        var i = 0
        while (i <= available - pattern.size) {
            if (matchesAt(buffer, i, pattern)) {
                offsets.add(windowStart + i)
                if (offsets.size >= limit) break
                i += pattern.size
            } else {
                i++
            }
        }
        if (offsets.size >= limit) break
        val keep = minOf(overlap, available)
        System.arraycopy(buffer, available - keep, buffer, 0, keep)
        windowStart += available - keep
        carry = keep
    }
    return offsets
}

private fun matchesAt(buffer: ByteArray, at: Int, pattern: ByteArray): Boolean {
    for (j in pattern.indices) {
        if (buffer[at + j] != pattern[j]) return false
    }
    return true
}
