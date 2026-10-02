package com.multiviewer.ui

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class AvmDecodeResult(
    val frameCount: Int,
    val stderr: String,
    val error: String? = null,
    val cancelled: Boolean = false,
) {
    val succeeded: Boolean get() = frameCount > 0 && error == null && !cancelled
}

/** AVM accepts an OBU elementary stream and writes Y4M to stdout with `-o -`. */
fun avmDecoderCommand(decoderPath: String, inputPath: String): List<String> =
    listOf(decoderPath, "-o", "-", inputPath)

fun av2OutputFormatError(format: Y4mFormat): String? =
    if (format.bitDepth == 8 && format.chroma.startsWith("420")) null
    else "AV2 playback supports 8-bit 4:2:0 Y4M output only (got ${format.chroma}, ${format.bitDepth}-bit)"

/** Decodes one assembled AV2 OBU stream, keeping stdout frame-by-frame and stderr drained. */
fun decodeAv2Bitstream(
    decoderPath: String,
    input: File,
    onFrame: (Av2DecodedFrame) -> Boolean,
    cancelRequested: () -> Boolean = { false },
): AvmDecodeResult {
    val frameCount = AtomicInteger()
    val stopped = AtomicBoolean(false)
    val error = AtomicReference<String?>(null)
    val processResult = try {
        runAvmProcess(
            command = avmDecoderCommand(decoderPath, input.absolutePath),
            onStdout = { stdout ->
                try {
                    val validStream = streamAv2Frames(stdout) { frame ->
                        val formatError = av2OutputFormatError(frame.format)
                        if (formatError != null) {
                            error.compareAndSet(null, formatError)
                            stopped.set(true)
                            false
                        } else {
                            try {
                                val keepReading = onFrame(frame)
                                if (keepReading) frameCount.incrementAndGet() else stopped.set(true)
                                keepReading
                            } catch (e: Exception) {
                                error.compareAndSet(null, e.message ?: "AV2 frame delivery failed")
                                stopped.set(true)
                                false
                            }
                        }
                    }
                    if (!validStream && !stopped.get()) {
                        error.compareAndSet(null, "Invalid or truncated AVM Y4M output")
                        stopped.set(true)
                    }
                } catch (e: Exception) {
                    error.compareAndSet(null, e.message ?: "Could not read AVM Y4M output")
                    stopped.set(true)
                }
            },
            cancelRequested = { cancelRequested() || stopped.get() },
        )
    } catch (e: Exception) {
        return AvmDecodeResult(0, "", e.message ?: "Could not start AVM decoder")
    }

    val diagnostic = error.get()
    return when {
        diagnostic != null -> AvmDecodeResult(frameCount.get(), processResult.stderr, diagnostic)
        cancelRequested() -> AvmDecodeResult(frameCount.get(), processResult.stderr, cancelled = true)
        stopped.get() && frameCount.get() > 0 -> AvmDecodeResult(frameCount.get(), processResult.stderr)
        processResult.exitCode != 0 -> AvmDecodeResult(
            frameCount.get(), processResult.stderr,
            "AVM decoder exited with code ${processResult.exitCode}" +
                processResult.stderr.takeIf { it.isNotBlank() }?.let { ": ${it.trim()}" }.orEmpty(),
        )
        frameCount.get() == 0 -> AvmDecodeResult(0, processResult.stderr, "AVM decoder produced no frames")
        else -> AvmDecodeResult(frameCount.get(), processResult.stderr)
    }
}
