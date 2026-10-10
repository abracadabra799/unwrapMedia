package com.multiviewer.ui

import com.multiviewer.cli.JsonValue
import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class IntegrityStatus { NOT_RUN, CLEAN, ISSUES, FAILED }

data class IntegrityDiagnostic(val message: String, val explanation: ErrorExplanation) {
    // Decoder output does not establish the input packet responsible for an error.
    val locationStatus: String get() = "UNAVAILABLE"
}

fun explainIntegrityLog(message: String): IntegrityDiagnostic {
    val explanation = runCatching { BitstreamCorruptionScanner.interpretDecoderError(message) }.getOrElse {
        ErrorExplanation("디코더 오류", message, "로그만으로 원인을 확정할 수 없습니다.",
            "디코딩 실패 또는 영상 왜곡 가능", actionableFix = "패킷 및 코덱 파라미터를 원본과 비교하세요.")
    }
    // Legacy explanations may infer byte ranges or recommend a repair as if it were proven.
    // Reuse the diagnostic context, but offer evidence gathering instead of invented locations.
    return IntegrityDiagnostic(message, explanation.copy(
        actionableFix = "패킷의 PTS/DTS·크기, NAL 길이, SPS/PPS 등 코덱 설정을 원본과 대조하세요. " +
            "이 로그만으로 손상 바이트 위치나 복구 방법을 확정할 수 없습니다.",
    ))
}

data class IntegrityPacket(
    val index: Int, val pts: String?, val dts: String?, val offset: Long?,
    val size: Long?, val keyframe: Boolean,
)

data class VideoIntegrityReport(
    val decodeStatus: IntegrityStatus,
    val packetStatus: IntegrityStatus,
    val decodedFrames: Long,
    val logs: List<String>,
    val packetLogs: List<String>,
    val packets: List<IntegrityPacket>,
    val packetsTruncated: Boolean = false,
    val logsTruncated: Boolean = false,
) {
    fun toJsonValue(): JsonValue = JsonValue.JObject(listOf(
        "scope" to JsonValue.JString("first video stream; software decoding"),
        "decodeStatus" to JsonValue.JString(decodeStatus.name),
        "packetStatus" to JsonValue.JString(packetStatus.name),
        "decodedFrames" to JsonValue.JNumber(decodedFrames),
        "errorPacketMapping" to JsonValue.JString("UNAVAILABLE: decoder logs do not establish exact packet positions"),
        "packetsTruncated" to JsonValue.JBoolean(packetsTruncated),
        "logsTruncated" to JsonValue.JBoolean(logsTruncated),
        "logs" to JsonValue.JArray(logs.map(JsonValue::JString)),
        "diagnostics" to JsonValue.JArray(logs.map { message ->
            val diagnostic = explainIntegrityLog(message)
            JsonValue.JObject(listOf(
                "message" to JsonValue.JString(message),
                "locationStatus" to JsonValue.JString(diagnostic.locationStatus),
                "interpretationStatus" to JsonValue.JString("HYPOTHESIS"),
                "title" to JsonValue.JString(diagnostic.explanation.title),
                "summary" to JsonValue.JString(diagnostic.explanation.summary),
                "possibleCause" to JsonValue.JString(diagnostic.explanation.probableCause),
                "possibleImpact" to JsonValue.JString(diagnostic.explanation.visualImpact),
                "nextChecks" to JsonValue.JString(diagnostic.explanation.actionableFix),
            ))
        }),
        "packetLogs" to JsonValue.JArray(packetLogs.map(JsonValue::JString)),
        "packets" to JsonValue.JArray(packets.map { p ->
            JsonValue.JObject(buildList {
                add("index" to JsonValue.JNumber(p.index.toLong()))
                p.pts?.let { add("ptsSeconds" to JsonValue.JString(it)) }
                p.dts?.let { add("dtsSeconds" to JsonValue.JString(it)) }
                p.offset?.let { add("offset" to JsonValue.JNumber(it)) }
                p.size?.let { add("sizeBytes" to JsonValue.JNumber(it)) }
                add("keyframe" to JsonValue.JBoolean(p.keyframe))
            })
        }),
    ))
}

internal fun parseIntegrityPacket(line: String, index: Int): IntegrityPacket? {
    if (!line.startsWith("packet|")) return null
    val values = line.split('|').drop(1).mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
    return IntegrityPacket(index,
        values["pts_time"]?.takeUnless { it == "N/A" },
        values["dts_time"]?.takeUnless { it == "N/A" },
        values["pos"]?.toLongOrNull()?.takeIf { it >= 0 },
        values["size"]?.toLongOrNull()?.takeIf { it > 0 },
        values["flags"]?.contains('K') == true)
}

internal fun decodeIntegrityStatus(exit: Int, frames: Long, logs: List<String>): IntegrityStatus = when {
    exit != 0 || frames == 0L -> IntegrityStatus.FAILED
    logs.isNotEmpty() -> IntegrityStatus.ISSUES
    else -> IntegrityStatus.CLEAN
}

/** Streaming, bounded capture. Cancellation/timeout kills the child even while readLine blocks. */
internal suspend fun integrityProcess(command: List<String>, onLine: (String) -> Unit): Int =
    withTimeout(30 * 60 * 1000L) {
        suspendCancellableCoroutine { continuation ->
            val process = try {
                ProcessManager.register(ProcessBuilder(command).redirectErrorStream(true)
                    .also(FfmpegLocator::configureEnvironment).start())
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { ProcessManager.terminate(process) }
            Thread({
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (!continuation.isActive) break
                            onLine(line.take(8192))
                        }
                    }
                    val exit = process.waitFor()
                    continuation.resume(exit)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    ProcessManager.terminate(process)
                    ProcessManager.unregister(process)
                }
            }, "video-integrity-output").apply { isDaemon = true; start() }
        }
    }

suspend fun inspectVideoIntegrity(
    file: File,
    ffmpeg: String = FfmpegLocator.ffmpegPath(),
    ffprobe: String = FfmpegLocator.ffprobePath(),
    onProgress: (String, Long) -> Unit = { _, _ -> },
): VideoIntegrityReport = withContext(Dispatchers.IO) {
    val packets = mutableListOf<IntegrityPacket>()
    val packetLogs = mutableListOf<String>()
    val logs = mutableListOf<String>()
    var packetsTruncated = false
    var logsTruncated = false
    fun log(target: MutableList<String>, line: String) {
        if (target.size < 2000) target.add(line.replace(file.absolutePath, file.name)) else logsTruncated = true
    }
    var count = 0L
    val packetStatus = try {
        onProgress("packets", 0)
        val exit = integrityProcess(listOf(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_packets",
            "-show_entries", "packet=pts_time,dts_time,pos,size,flags", "-of", "compact", file.absolutePath)) { line ->
            val packet = parseIntegrityPacket(line, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (packet != null) {
                count++
                if (packets.size < 100_000) packets.add(packet) else packetsTruncated = true
                if (count % 500 == 0L) onProgress("packets", count)
            } else if (line.isNotBlank()) log(packetLogs, line)
        }
        when {
            exit != 0 || packets.isEmpty() -> IntegrityStatus.FAILED
            packetLogs.isNotEmpty() -> IntegrityStatus.ISSUES
            else -> IntegrityStatus.CLEAN
        }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log(packetLogs, "Packet scan timed out (30 minutes)")
        IntegrityStatus.FAILED
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        log(packetLogs, e.message ?: e.toString()); IntegrityStatus.FAILED
    }
    var frames = 0L
    val progressKeys = setOf("frame", "fps", "stream_0_0_q", "bitrate", "total_size", "out_time_us",
        "out_time_ms", "out_time", "dup_frames", "drop_frames", "speed", "progress")
    val decodeStatus = try {
        onProgress("decode", 0)
        val exit = integrityProcess(listOf(ffmpeg, "-nostdin", "-hide_banner", "-v", "error", "-nostats",
            "-progress", "pipe:1", "-i", file.absolutePath, "-map", "0:v:0", "-an", "-sn", "-dn",
            "-fps_mode", "passthrough", "-f", "null", "-")) { line ->
            val key = line.substringBefore('=')
            if (key == "frame") {
                frames = line.substringAfter('=').trim().toLongOrNull() ?: frames
                onProgress("decode", frames)
            } else if (key !in progressKeys && line.isNotBlank()) log(logs, line)
        }
        if (exit != 0) log(logs, "FFmpeg exited with code $exit; decoding incomplete")
        if (frames == 0L) log(logs, "No video frames decoded")
        decodeIntegrityStatus(exit, frames, logs)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        log(logs, "Decoding timed out (30 minutes)"); IntegrityStatus.FAILED
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        log(logs, e.message ?: e.toString()); IntegrityStatus.FAILED
    }
    VideoIntegrityReport(decodeStatus, packetStatus, frames, logs.toList(), packetLogs.toList(), packets.toList(),
        packetsTruncated, logsTruncated)
}
