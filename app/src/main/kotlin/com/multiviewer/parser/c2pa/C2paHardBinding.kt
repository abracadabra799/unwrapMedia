package com.multiviewer.parser.c2pa

import com.multiviewer.parser.ByteReader
import java.io.File
import java.security.MessageDigest

enum class BindingStatus {
    MATCH,
    MISMATCH,
    UNSUPPORTED
}

data class HashComparison(
    val algorithm: String,
    val status: BindingStatus,
    val storedHashHex: String,
    val computedHashHex: String,
    val excludedByteCount: Long,
    val hashedByteCount: Long,
    val error: String? = null
)

data class DataExclusion(val start: Long, val length: Long)

fun parseDataExclusions(assertionCbor: CborValue): List<DataExclusion> {
    val m = assertionCbor.asMap() ?: return emptyList()
    val exc = m["exclusions"]?.asArray() ?: return emptyList()
    return exc.mapNotNull { item ->
        val map = item.asMap() ?: return@mapNotNull null
        val start = map["start"]?.asLong() ?: return@mapNotNull null
        val length = map["length"]?.asLong() ?: return@mapNotNull null
        DataExclusion(start, length)
    }
}

fun normalizeDigestAlgorithm(alg: String): String = when (alg.lowercase().replace("-", "")) {
    "sha256" -> "SHA-256"
    "sha384" -> "SHA-384"
    "sha512" -> "SHA-512"
    else -> alg.uppercase()
}

fun computeDataHash(reader: ByteReader, exclusions: List<DataExclusion>, algorithm: String): ByteArray {
    val md = MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm))
    val sortedExclusions = exclusions.sortedBy { it.start }
    var current = 0L
    val fileLength = reader.length

    for (ex in sortedExclusions) {
        if (ex.start > current) {
            val toRead = minOf(ex.start, fileLength) - current
            if (toRead > 0) {
                hashStream(reader, current, toRead, md)
            }
        }
        current = maxOf(current, ex.start + ex.length)
    }

    if (current < fileLength) {
        hashStream(reader, current, fileLength - current, md)
    }

    return md.digest()
}

private const val CHUNK_SIZE = 64 * 1024

private fun hashStream(reader: ByteReader, start: Long, length: Long, md: MessageDigest) {
    var pos = start
    val end = start + length
    while (pos < end) {
        val count = minOf(CHUNK_SIZE.toLong(), end - pos).toInt()
        val chunk = reader.readBytes(pos, count)
        md.update(chunk)
        pos += count
    }
}

data class BmffDataMatch(val offset: Int, val value: ByteArray)
data class BmffSubset(val offset: Int, val length: Int)

data class BmffExclusion(
    val xpath: String,
    val dataMatches: List<BmffDataMatch> = emptyList(),
    val subsets: List<BmffSubset> = emptyList()
)

fun parseBmffExclusions(assertionCbor: CborValue): List<BmffExclusion> {
    val m = assertionCbor.asMap() ?: return emptyList()
    val exc = m["exclusions"]?.asArray() ?: return emptyList()
    return exc.mapNotNull { item ->
        val map = item.asMap() ?: return@mapNotNull null
        val xpath = map["xpath"]?.asText() ?: return@mapNotNull null
        val dataMatches = map["data"]?.asArray()?.mapNotNull { d ->
            val dm = d.asMap() ?: return@mapNotNull null
            val offset = dm["offset"]?.asLong()?.toInt() ?: return@mapNotNull null
            val value = dm["value"]?.asBytes() ?: return@mapNotNull null
            BmffDataMatch(offset, value)
        } ?: emptyList()
        val subsets = map["subset"]?.asArray()?.mapNotNull { s ->
            val sm = s.asMap() ?: return@mapNotNull null
            val offset = sm["offset"]?.asLong()?.toInt() ?: return@mapNotNull null
            val length = sm["length"]?.asLong()?.toInt() ?: return@mapNotNull null
            BmffSubset(offset, length)
        } ?: emptyList()
        BmffExclusion(xpath, dataMatches, subsets)
    }
}

data class BmffRawBox(val type: String, val offset: Long, val size: Long, val headerSize: Int)

fun readTopLevelBmffBoxes(reader: ByteReader): List<BmffRawBox> {
    val list = mutableListOf<BmffRawBox>()
    var pos = 0L
    val len = reader.length
    while (pos + 8 <= len) {
        val lbox = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        var headerSize = 8
        val size = when (lbox) {
            0L -> len - pos
            1L -> {
                if (pos + 16 > len) break
                headerSize = 16
                reader.readUInt64(pos + 8)
            }
            else -> lbox
        }
        if (size < headerSize || pos + size > len) {
            list += BmffRawBox(type, pos, len - pos, headerSize)
            break
        }
        list += BmffRawBox(type, pos, size, headerSize)
        pos += size
    }
    return list
}

fun matchesBmffExclusion(reader: ByteReader, box: BmffRawBox, exclusion: BmffExclusion): Boolean {
    val targetType = exclusion.xpath.trimStart('/')
    if (box.type != targetType) return false
    if (exclusion.dataMatches.isEmpty()) return true
    for (dm in exclusion.dataMatches) {
        val checkPos = box.offset + dm.offset
        if (checkPos + dm.value.size > box.offset + box.size) return false
        val bytes = reader.readBytes(checkPos, dm.value.size)
        if (!bytes.contentEquals(dm.value)) return false
    }
    return true
}

fun computeBmffV3Hash(reader: ByteReader, exclusions: List<BmffExclusion>, algorithm: String): ByteArray {
    val md = MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm))
    val boxes = readTopLevelBmffBoxes(reader)
    for (box in boxes) {
        val matchingExclusion = exclusions.firstOrNull { matchesBmffExclusion(reader, box, it) }
        if (matchingExclusion != null && matchingExclusion.subsets.isEmpty()) {
            continue
        }
        val offBytes = java.nio.ByteBuffer.allocate(8).putLong(box.offset).array()
        md.update(offBytes)
        hashStream(reader, box.offset, box.size, md)
    }
    return md.digest()
}

fun computeBmffV2Hash(reader: ByteReader, exclusions: List<BmffExclusion>, algorithm: String): ByteArray {
    val md = MessageDigest.getInstance(normalizeDigestAlgorithm(algorithm))
    val boxes = readTopLevelBmffBoxes(reader)
    for (box in boxes) {
        val matchingExclusion = exclusions.firstOrNull { matchesBmffExclusion(reader, box, it) }
        if (matchingExclusion != null && matchingExclusion.subsets.isEmpty()) {
            continue
        }
        hashStream(reader, box.offset, box.size, md)
    }
    return md.digest()
}

