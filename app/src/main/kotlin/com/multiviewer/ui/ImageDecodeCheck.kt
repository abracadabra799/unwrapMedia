package com.multiviewer.ui

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.TiffIntegrity
import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ImageDecodeStatus { NOT_RUN, CLEAN, ISSUES, FAILED }

data class SkiaDecodeResult(val attempted: Boolean, val ok: Boolean, val detail: String)

data class ImageDecodeReport(
    val ffmpegStatus: ImageDecodeStatus,
    val decodedFrames: Long,
    val decodedWidth: Int?,
    val decodedHeight: Int?,
    val logs: List<String>,
    val logsTruncated: Boolean,
    val ffmpegVersion: String?,
    val source: String,
    val skia: SkiaDecodeResult,
    val declaredWidth: Int?,
    val declaredHeight: Int?,
) {
    val status: ImageDecodeStatus get() = combineDecodeStatus(ffmpegStatus, skia)
    val resolutionStatus: CheckStatus get() = resolutionCheck(declaredWidth, declaredHeight, decodedWidth, decodedHeight)
}

private val RAW_IMAGE_EXTENSIONS = setOf("cr2", "nef", "arw", "dng")
private val SKIA_FORMATS = setOf("JPEG", "PNG", "GIF", "WEBP", "BMP")
private val ANIMATED_FORMATS = setOf("GIF", "WEBP")
private const val MAX_LOG_LINES = 500
private const val MAX_SKIA_PIXELS = 100_000_000L

/** CLEAN requires exit 0 AND no error output AND at least one decoded frame. */
internal fun imageDecodeStatus(exit: Int, frames: Long, logs: List<String>): ImageDecodeStatus = when {
    exit != 0 || frames == 0L -> ImageDecodeStatus.FAILED
    logs.isNotEmpty() -> ImageDecodeStatus.ISSUES
    else -> ImageDecodeStatus.CLEAN
}

internal fun combineDecodeStatus(ffmpeg: ImageDecodeStatus, skia: SkiaDecodeResult): ImageDecodeStatus = when {
    ffmpeg == ImageDecodeStatus.FAILED -> ImageDecodeStatus.FAILED
    skia.attempted && !skia.ok -> ImageDecodeStatus.ISSUES
    else -> ffmpeg
}

/** A width/height swap matches: FFmpeg applies EXIF / irot rotation to the decoded frame. */
internal fun resolutionCheck(declaredW: Int?, declaredH: Int?, decodedW: Int?, decodedH: Int?): CheckStatus = when {
    declaredW == null || declaredH == null || decodedW == null || decodedH == null -> CheckStatus.SKIP
    (declaredW == decodedW && declaredH == decodedH) || (declaredW == decodedH && declaredH == decodedW) -> CheckStatus.PASS
    else -> CheckStatus.WARN
}

internal sealed class FramecrcLine {
    data class Dimensions(val width: Int, val height: Int) : FramecrcLine()
    object Header : FramecrcLine()
    object Frame : FramecrcLine()
    data class Log(val text: String) : FramecrcLine()
}

private val DIMENSIONS = Regex("""^#dimensions \d+: (\d+)x(\d+)""")
private val FRAME_LINE = Regex("""^\d+,\s""")

internal fun classifyFramecrcLine(line: String): FramecrcLine {
    DIMENSIONS.find(line)?.let { return FramecrcLine.Dimensions(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    return when {
        line.startsWith("#") -> FramecrcLine.Header
        FRAME_LINE.containsMatchIn(line) -> FramecrcLine.Frame
        else -> FramecrcLine.Log(line)
    }
}

/** Skia (libjpeg-turbo/libpng/libwebp/wuffs) is stricter than FFmpeg about truncation: it throws "Incomplete input". */
internal fun skiaDecode(bytes: ByteArray): SkiaDecodeResult = try {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val width = codec.width
    val height = codec.height
    if (width.toLong() * height > MAX_SKIA_PIXELS) {
        SkiaDecodeResult(false, false, "Skipped: ${width}x$height exceeds the Skia decode limit")
    } else {
        val bitmap = Bitmap()
        bitmap.allocPixels(codec.imageInfo)
        codec.readPixels(bitmap)
        bitmap.close()
        codec.close()
        SkiaDecodeResult(true, true, "Decoded ${width}x$height")
    }
} catch (e: Exception) {
    SkiaDecodeResult(true, false, e.message ?: e.toString())
}

/** Streams merged stdout/stderr line by line; cancellation or timeout kills the child process. */
internal suspend fun runImageDecodeProcess(
    command: List<String>,
    stdin: ByteArray?,
    timeoutMs: Long,
    onLine: (String) -> Unit,
): Int = withTimeout(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        val process = try {
            ProcessManager.register(
                ProcessBuilder(command).redirectErrorStream(true).also(FfmpegLocator::configureEnvironment).start(),
            )
        } catch (e: Exception) {
            cont.resumeWithException(e)
            return@suspendCancellableCoroutine
        }
        cont.invokeOnCancellation { ProcessManager.terminate(process) }
        if (stdin != null) {
            Thread({ runCatching { process.outputStream.use { it.write(stdin) } } }, "image-decode-stdin")
                .apply { isDaemon = true; start() }
        } else {
            runCatching { process.outputStream.close() }
        }
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!cont.isActive) break
                        onLine(line.take(8192))
                    }
                }
                val exit = process.waitFor()
                if (cont.isActive) cont.resume(exit)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally {
                ProcessManager.terminate(process)
                ProcessManager.unregister(process)
            }
        }, "image-decode-output").apply { isDaemon = true; start() }
    }
}

private suspend fun ffmpegVersion(ffmpeg: String): String? = try {
    var first: String? = null
    runImageDecodeProcess(listOf(ffmpeg, "-hide_banner", "-version"), null, 10_000) { if (first == null) first = it }
    first?.removePrefix("ffmpeg version ")?.substringBefore(' ')
} catch (e: TimeoutCancellationException) {
    null
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

suspend fun inspectImageDecode(
    file: File,
    structure: ImageStructureReport,
    ffmpeg: String = FfmpegLocator.ffmpegPath(),
    timeoutMs: Long = 120_000,
): ImageDecodeReport = withContext(Dispatchers.IO) {
    val isRaw = structure.format == "TIFF" && file.extension.lowercase(Locale.US) in RAW_IMAGE_EXTENSIONS
    val preview: Pair<Long, ByteArray>? = if (isRaw) {
        ByteReader.open(file).use { r -> TiffIntegrity.largestJpegPreview(r)?.let { (off, n) -> off to r.readBytes(off, n.toInt()) } }
    } else null
    val version = ffmpegVersion(ffmpeg)

    if (isRaw && preview == null) {
        return@withContext ImageDecodeReport(
            ImageDecodeStatus.FAILED, 0, null, null,
            listOf("No decodable embedded JPEG preview found; RAW sensor data cannot be decoded"), false, version,
            "RAW: no embedded preview", SkiaDecodeResult(false, false, "No embedded preview"), null, null,
        )
    }
    val source = if (preview != null) {
        "Embedded JPEG preview at offset ${preview.first} (${preview.second.size} bytes); RAW sensor data not verified"
    } else "File"

    val logs = mutableListOf<String>()
    var logsTruncated = false
    fun log(line: String) {
        if (logs.size < MAX_LOG_LINES) logs += line.replace(file.absolutePath, file.name) else logsTruncated = true
    }
    var frames = 0L
    var width: Int? = null
    var height: Int? = null
    val command = buildList {
        add(ffmpeg)
        if (preview == null) add("-nostdin")
        addAll(listOf("-hide_banner", "-v", "error", "-i", if (preview != null) "pipe:0" else file.absolutePath))
        if (structure.format !in ANIMATED_FORMATS) addAll(listOf("-frames:v", "1"))
        addAll(listOf("-an", "-sn", "-dn", "-f", "framecrc", "-"))
    }
    val ffmpegStatus = try {
        val exit = runImageDecodeProcess(command, preview?.second, timeoutMs) { line ->
            when (val c = classifyFramecrcLine(line)) {
                is FramecrcLine.Dimensions -> if (width == null) { width = c.width; height = c.height }
                FramecrcLine.Frame -> frames++
                FramecrcLine.Header -> Unit
                is FramecrcLine.Log -> if (line.isNotBlank()) log(line)
            }
        }
        if (exit != 0) log("FFmpeg exited with code $exit")
        if (frames == 0L) log("No frames decoded")
        imageDecodeStatus(exit, frames, logs)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log("Decoding timed out (${timeoutMs / 1000} s)")
        ImageDecodeStatus.FAILED
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("FFmpeg could not run: ${e.message ?: e.toString()}")
        ImageDecodeStatus.FAILED
    }

    val skia = when {
        preview != null -> skiaDecode(preview.second)
        structure.format in SKIA_FORMATS -> skiaDecode(file.readBytes())
        else -> SkiaDecodeResult(false, false, "Skia does not decode ${structure.format}")
    }
    ImageDecodeReport(
        ffmpegStatus, frames, width, height, logs.toList(), logsTruncated, version, source, skia,
        if (isRaw) null else structure.declaredWidth,
        if (isRaw) null else structure.declaredHeight,
    )
}
