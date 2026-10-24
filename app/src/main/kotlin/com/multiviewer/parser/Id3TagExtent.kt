package com.multiviewer.parser

/** Lightweight physical extent for sniffers/validators, including a validated v2.4 footer. */
internal fun id3v2StoredEnd(reader: ByteReader): Long? {
    if (reader.length < 10 || String(reader.readBytes(0, 3), Charsets.US_ASCII) != "ID3") return null
    val version = reader.readUInt8(3)
    if (version !in 2..4) return null
    val sizeBytes = reader.readBytes(6, 4)
    if (sizeBytes.any { it.toInt() and 128 != 0 }) return null
    val size = sizeBytes.fold(0L) { acc, b -> (acc shl 7) or (b.toLong() and 127) }
    val end = 10 + size
    if (end > reader.length) return null
    if (version != 4 || reader.readUInt8(5) and 16 == 0) return end
    if (reader.length - end < 10) return null
    val footer = reader.readBytes(end, 10)
    if (!footer.copyOfRange(0, 3).contentEquals("3DI".toByteArray()) ||
        !footer.copyOfRange(3, 10).contentEquals(reader.readBytes(3, 7))) return null
    return end + 10
}
