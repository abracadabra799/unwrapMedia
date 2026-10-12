package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

enum class C2paContainer { JPEG, BMFF, WEBP }

const val MAX_C2PA_STORE_BYTES = 64 * 1024 * 1024

val C2PA_UUID: ByteArray = byteArrayOf(
    0xD8.toByte(), 0xFE.toByte(), 0xC3.toByte(), 0xD6.toByte(), 0x1B, 0x0E, 0x48, 0x3C,
    0x92.toByte(), 0x97.toByte(), 0x58, 0x28, 0x87.toByte(), 0x7E, 0xC4.toByte(), 0x81.toByte(),
)

data class C2paSpan(val logicalStart: Int, val fileStart: Long, val length: Int)

class C2paOffsetMap(val spans: List<C2paSpan>) {
    fun toFile(logical: Int): Long {
        val span = spans.lastOrNull { logical >= it.logicalStart } ?: spans.first()
        return span.fileStart + (logical - span.logicalStart)
    }

    fun crossesSpan(logicalStart: Int, length: Int): Boolean {
        if (length <= 0) return false
        val first = spans.indexOfLast { logicalStart >= it.logicalStart }
        val last = spans.indexOfLast { logicalStart + length - 1 >= it.logicalStart }
        return first != last
    }
}

class C2paStoreLocation(
    val container: C2paContainer,
    val bytes: ByteArray,
    val offsetMap: C2paOffsetMap,
    val anchorOffsets: List<Long>,
    val warnings: List<String>,
)

fun locateC2paStore(reader: ByteReader, root: BoxNode): C2paStoreLocation? {
    val top = root.children
    val app11 = top.filter { it.type == "APP11" }
    if (app11.isNotEmpty()) return readJpegC2paStore(reader, app11.map { it.offset })
    top.firstOrNull { it.type == "C2PA" }?.let { return readWebpC2paStore(reader, it.offset, it.size - 8) }
    for (node in top.filter { it.type == "uuid" }) {
        readBmffC2paStore(reader, node.offset, node.headerSize, node.size)?.let { return it }
    }
    return null
}

private class App11(val offset: Long, val en: Int, val z: Long, val dataStart: Long, val dataEnd: Long)

fun readJpegC2paStore(reader: ByteReader, app11Offsets: List<Long>): C2paStoreLocation? {
    val segments = app11Offsets.mapNotNull { off ->
        if (off < 0 || off > reader.length - 4) return@mapNotNull null
        val len = reader.readUInt16(off + 2)
        val segEnd = off + 2 + len
        if (len < 2 + 8 + 8 || segEnd > reader.length) return@mapNotNull null
        if (reader.readFourCC(off + 4).substring(0, 2) != "JP") return@mapNotNull null
        App11(off, reader.readUInt16(off + 6), reader.readUInt32(off + 8), off + 12, segEnd)
    }
    val groups = segments.groupBy { it.en }
    for ((_, group) in groups) {
        val warnings = mutableListOf<String>()
        if (groups.size > 1) warnings += "Multiple APP11 instance groups (${groups.size}); displaying first C2PA store"
        val ordered = mutableListOf<App11>()
        group.sortedBy { it.z }.forEach { s ->
            if (ordered.any { it.z == s.z }) warnings += "Duplicate APP11 sequence number Z=${s.z} (En=${s.en}) ignored"
            else ordered += s
        }
        if (ordered.first().z != 1L) warnings += "APP11 sequence starts at Z=${ordered.first().z}, expected 1"
        ordered.zipWithNext().forEach { (a, b) -> if (b.z != a.z + 1) warnings += "APP11 sequence gap: Z=${a.z} then Z=${b.z} (En=${a.en})" }
        val spans = mutableListOf<C2paSpan>()
        val out = java.io.ByteArrayOutputStream()
        var logical = 0
        var truncated = false
        ordered.forEachIndexed { i, s ->
            val from = if (i == 0) s.dataStart else s.dataStart + 8
            var length = (s.dataEnd - from).coerceAtLeast(0)
            if (logical + length > MAX_C2PA_STORE_BYTES) { length = (MAX_C2PA_STORE_BYTES - logical).toLong(); truncated = true }
            if (length > 0) {
                spans += C2paSpan(logical, from, length.toInt())
                out.write(reader.readBytes(from, length.toInt()))
                logical += length.toInt()
            }
            if (truncated) return@forEachIndexed
        }
        val bytes = out.toByteArray()
        if (bytes.size < 8 || spans.isEmpty()) continue
        if (truncated) warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes"
        val lbox = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
        if (!truncated && lbox != bytes.size.toLong()) warnings += "Reassembled APP11 data is ${bytes.size} bytes but the superbox declares $lbox"
        val top = parseJumbfBoxes(bytes).firstOrNull()
        if ((top?.type == "jumb" && top.label == "c2pa") || hasC2paDescription(bytes)) {
            return C2paStoreLocation(C2paContainer.JPEG, bytes, C2paOffsetMap(spans), ordered.map { it.offset }, warnings)
        }
    }
    return null
}

fun readBmffC2paStore(reader: ByteReader, uuidBoxOffset: Long, headerSize: Int, boxSize: Long): C2paStoreLocation? {
    val payload = uuidBoxOffset + headerSize
    val end = uuidBoxOffset + boxSize
    if (end - payload < 16 + 4 + 1 + 8) return null
    if (!reader.readBytes(payload, 16).contentEquals(C2PA_UUID)) return null
    var q = payload + 16 + 4
    val purposeBytes = java.io.ByteArrayOutputStream()
    while (q < end) {
        val b = reader.readUInt8(q)
        q++
        if (b == 0) break
        purposeBytes.write(b)
    }
    if (purposeBytes.toString(Charsets.UTF_8) != "manifest") return null
    q += 8 // merkle offset
    if (q >= end) return null
    var length = end - q
    val warnings = mutableListOf<String>()
    if (length > MAX_C2PA_STORE_BYTES) { length = MAX_C2PA_STORE_BYTES.toLong(); warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes" }
    val bytes = reader.readBytes(q, length.toInt())
    return C2paStoreLocation(C2paContainer.BMFF, bytes, C2paOffsetMap(listOf(C2paSpan(0, q, bytes.size))), listOf(uuidBoxOffset), warnings)
}

fun readWebpC2paStore(reader: ByteReader, chunkOffset: Long, payloadSize: Long): C2paStoreLocation? {
    if (payloadSize < 8) return null
    val warnings = mutableListOf<String>()
    var length = payloadSize
    if (length > MAX_C2PA_STORE_BYTES) { length = MAX_C2PA_STORE_BYTES.toLong(); warnings += "Manifest store truncated at $MAX_C2PA_STORE_BYTES bytes" }
    if (chunkOffset < 0 || chunkOffset > reader.length - 8) return null
    val start = chunkOffset + 8
    if (length > reader.length - start) {
        length = reader.length - start
        warnings += "C2PA chunk payload extends past end of file"
    }
    val bytes = reader.readBytes(start, length.toInt())
    return C2paStoreLocation(C2paContainer.WEBP, bytes, C2paOffsetMap(listOf(C2paSpan(0, start, bytes.size))), listOf(chunkOffset), warnings)
}

// Identify an intact description even when the enclosing superbox was truncated.
private fun hasC2paDescription(bytes: ByteArray): Boolean {
    if (bytes.size < 8 || String(bytes, 4, 4, Charsets.US_ASCII) != "jumb") return false
    val header = if (bytes.take(4) == listOf<Byte>(0, 0, 0, 1)) 16 else 8
    if (bytes.size < header + 30) return false
    if (String(bytes, header + 4, 4, Charsets.US_ASCII) != "jumd") return false
    return bytes[header + 24].toInt() and 2 != 0 &&
        String(bytes, header + 25, 5, Charsets.US_ASCII) == "c2pa\u0000"
}
