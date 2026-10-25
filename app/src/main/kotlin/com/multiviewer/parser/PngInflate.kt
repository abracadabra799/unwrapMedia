package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal sealed interface PngInflateResult {
    data class Complete(val bytes: ByteArray, val consumed: Int) : PngInflateResult
    data object Malformed : PngInflateResult
    data object Limited : PngInflateResult
}

/** A decoded prefix is never success: the complete zlib stream/checksum must be consumed. */
internal fun inflatePngMetadata(input: ByteArray, limit: Int): PngInflateResult {
    val inflater = Inflater()
    val output = ByteArrayOutputStream(minOf(8192, limit))
    val buffer = ByteArray(8192)
    return try {
        inflater.setInput(input)
        while (!inflater.finished()) {
            if (Thread.currentThread().isInterrupted) throw CancellationException("PNG metadata read cancelled")
            val n = inflater.inflate(buffer)
            if (n > limit - output.size()) return PngInflateResult.Limited
            output.write(buffer, 0, n)
            if (n == 0 && !inflater.finished()) return PngInflateResult.Malformed
        }
        PngInflateResult.Complete(output.toByteArray(), input.size - inflater.remaining)
    } catch (_: DataFormatException) {
        PngInflateResult.Malformed
    } finally {
        inflater.end()
    }
}
