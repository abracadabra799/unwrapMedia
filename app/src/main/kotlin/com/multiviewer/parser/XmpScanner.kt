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

// Namespace/property markers that identify what a packet is *for*. Order is the display order in
// a tab label. Matched as plain substrings because they appear both as namespace prefixes
// (xmlns:hdrgm=…) and as element names (<GCamera:MotionPhoto>), and either is enough to identify
// the packet.
private val XMP_TOPIC_MARKERS = listOf(
    "GainMap" to "GainMap",
    "hdrgm" to "hdrgm",
    "Container" to "Container",
    "MotionPhoto" to "MotionPhoto",
    "GCamera" to "GCamera",
    "GDepth" to "GDepth",
    "GImage" to "GImage",
    "photoshop" to "photoshop",
    "tiff" to "tiff",
    "exif" to "exif",
    "dc:" to "dc",
    "crs:" to "crs",
)

/**
 * The schemas a packet carries, for telling one packet apart from another at a glance.
 *
 * On the gain map files this was built against, the primary packet reports Container/MotionPhoto/
 * GCamera while the packet inside the embedded gain map image reports GainMap/hdrgm -- which is
 * exactly the distinction a tab label needs to make. Returns an empty list for a packet with
 * nothing recognizable; callers fall back to the packet's index.
 */
fun xmpPacketTopics(text: String): List<String> =
    XMP_TOPIC_MARKERS.filter { (marker, _) -> text.contains(marker, ignoreCase = true) }
        .map { (_, label) -> label }
        .distinct()

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
