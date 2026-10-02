package com.multiviewer.ui

import java.io.InputStream

data class Y4mFormat(val width: Int, val height: Int, val fpsNumerator: Int, val fpsDenominator: Int, val chroma: String, val bitDepth: Int)
data class Av2DecodedFrame(val format: Y4mFormat, val yuv420Payload: ByteArray, val frameIndex: Long)

private const val MAX_Y4M_FRAME_BYTES = 64L * 1024 * 1024

fun yuv420ToRgba(frame: Av2DecodedFrame): ByteArray {
    require(frame.format.bitDepth == 8) { "Only 8-bit Y4M conversion is supported" }
    val width = frame.format.width; val height = frame.format.height
    val plane = width * height; val chroma = (width + 1) / 2 * ((height + 1) / 2)
    require(frame.yuv420Payload.size >= plane + chroma * 2) { "Y4M frame payload is truncated" }
    val rgba = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val yValue = frame.yuv420Payload[y * width + x].toInt() and 0xff
        val uv = (y / 2) * ((width + 1) / 2) + x / 2
        val u = (frame.yuv420Payload[plane + uv].toInt() and 0xff) - 128
        val v = (frame.yuv420Payload[plane + chroma + uv].toInt() and 0xff) - 128
        val c = yValue - 16
        val out = (y * width + x) * 4
        rgba[out] = clamp((298 * c + 409 * v + 128) shr 8).toByte()
        rgba[out + 1] = clamp((298 * c - 100 * u - 208 * v + 128) shr 8).toByte()
        rgba[out + 2] = clamp((298 * c + 516 * u + 128) shr 8).toByte()
        rgba[out + 3] = 0xff.toByte()
    }
    return rgba
}

private fun clamp(value: Int): Int = value.coerceIn(0, 255)

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
    val chromaSamples = ((format.width.toLong() + 1L) / 2L) * ((format.height.toLong() + 1L) / 2L)
    val payloadSize = (lumaSamples + chromaSamples * 2L) * bytesPerSample
    require(payloadSize <= MAX_Y4M_FRAME_BYTES) { "Y4M frame exceeds the 64 MiB decoder frame limit" }
    return input.readFullyOrNull(payloadSize.toInt())
        ?: throw IllegalArgumentException("Y4M frame payload is truncated")
}

fun streamY4mFrames(input: InputStream, onFormat: (Y4mFormat) -> Unit, onFrame: (Y4mFormat, ByteArray) -> Boolean): Boolean {
    val header = input.readLineAscii() ?: return false
    val format = parseY4mHeader(header)
    onFormat(format)
    while (true) {
        val frame = readY4mFrame(input, format) ?: return true
        if (!onFrame(format, frame)) return false
    }
}

fun streamAv2Frames(input: InputStream, onFrame: (Av2DecodedFrame) -> Boolean): Boolean {
    var frameIndex = 0L
    var format: Y4mFormat? = null
    return streamY4mFrames(input, { format = it }) { parsedFormat, payload ->
        val frame = Av2DecodedFrame(parsedFormat, payload, frameIndex++)
        onFrame(frame)
    }
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
