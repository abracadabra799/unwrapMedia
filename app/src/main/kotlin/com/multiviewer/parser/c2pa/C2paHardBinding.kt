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
