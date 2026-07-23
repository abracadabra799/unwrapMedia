package com.multiviewer.parser

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("UTC"))
private const val MP4_EPOCH_OFFSET = 2082844800L

internal fun readUIntOfWidth(reader: ByteReader, offset: Long, width: Int): Long = when (width) {
    4 -> reader.readUInt32(offset)
    8 -> reader.readUInt64(offset)
    else -> error("Unsupported field width: $width")
}

internal fun formatMp4Time(secondsSince1904: Long): String {
    if (secondsSince1904 == 0L) return "0 (not set)"
    return try {
        val instant = Instant.ofEpochSecond(secondsSince1904 - MP4_EPOCH_OFFSET)
        ISO_DATE_FORMATTER.format(instant)
    } catch (e: Exception) {
        secondsSince1904.toString()
    }
}

internal fun pluralize(count: Long, singular: String, plural: String = "${singular}s"): String =
    "$count " + if (count == 1L) singular else plural
