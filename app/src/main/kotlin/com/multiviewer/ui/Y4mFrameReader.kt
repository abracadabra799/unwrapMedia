package com.multiviewer.ui

import java.io.InputStream

data class Y4mFormat(val width: Int, val height: Int, val fpsNumerator: Int, val fpsDenominator: Int, val chroma: String, val bitDepth: Int)

fun parseY4mHeader(header: String): Y4mFormat {
    require(header.startsWith("YUV4MPEG2 ")) { "Unsupported decoder output: missing YUV4MPEG2 header" }
    val fields = header.trim().split(Regex("\\s+")).drop(1)
    fun value(prefix: String): String = fields.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        ?: error("Y4M header is missing $prefix")
    val size = value("W").toInt() to value("H").toInt()
    require(size.first > 0 && size.second > 0) { "Y4M dimensions must be positive" }
    val fps = value("F").split(":")
    require(fps.size == 2 && fps[0].toInt() > 0 && fps[1].toInt() > 0) { "Invalid Y4M frame rate" }
    val chromaToken = fields.firstOrNull { it.startsWith("C") } ?: error("Y4M header is missing chroma")
    val chroma = chromaToken.removePrefix("C")
    val bitDepth = when {
        chroma.contains("p10") -> 10
        chroma.contains("p12") -> 12
        chroma.contains("p8") || chroma == "420" || chroma == "420jpeg" -> 8
        else -> throw IllegalArgumentException("Unsupported Y4M chroma format: $chroma")
    }
    require(chroma.startsWith("420")) { "Only 4:2:0 Y4M output is supported" }
    return Y4mFormat(size.first, size.second, fps[0].toInt(), fps[1].toInt(), chroma, bitDepth)
}

fun readY4mFrame(input: InputStream, format: Y4mFormat): ByteArray? {
    val marker = input.readLineAscii() ?: return null
    require(marker.startsWith("FRAME")) { "Invalid Y4M frame marker" }
    val bytesPerSample = if (format.bitDepth <= 8) 1 else 2
    val lumaSamples = format.width.toLong() * format.height
    val payloadSize = ((lumaSamples * 3L + 1L) / 2L) * bytesPerSample
    require(payloadSize <= Int.MAX_VALUE) { "Y4M frame is too large" }
    return input.readFullyOrNull(payloadSize.toInt())
}

private fun InputStream.readLineAscii(): String? {
    val bytes = ByteArray(256); var count = 0
    while (count < bytes.size) {
        val value = read()
        if (value < 0) return if (count == 0) null else String(bytes, 0, count, Charsets.US_ASCII)
        if (value == '\n'.code) return String(bytes, 0, count, Charsets.US_ASCII).trimEnd('\r')
        bytes[count++] = value.toByte()
    }
    error("Y4M frame marker is too long")
}

private fun InputStream.readFullyOrNull(size: Int): ByteArray? {
    val result = ByteArray(size); var offset = 0
    while (offset < size) {
        val count = read(result, offset, size - offset)
        if (count < 0) return null
        offset += count
    }
    return result
}
