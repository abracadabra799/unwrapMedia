package com.multiviewer.ui

import com.multiviewer.cli.JsonValue
import com.multiviewer.cli.scrubPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class AudioStreamMetadata(
    val index: Int,
    val codec: String,
    val sampleRate: Int?,
    val channels: Int?,
    val durationSeconds: Double?,
)

data class AudioStreamReport(
    val stream: AudioStreamMetadata,
    val status: IntegrityStatus,
    val decodedFrames: Long,
    /** Samples per channel, not interleaved sample values. */
    val decodedSamples: Long,
    val decodedDurationSeconds: Double,
    val observedSampleRates: Set<Int>,
    val observedChannels: Set<Int>,
    val durationToleranceSeconds: Double,
    val mismatches: List<String>,
    val logs: List<String>,
    val logsTruncated: Boolean = false,
)

data class AudioIntegrityReport(
    val probeStatus: IntegrityStatus,
    val streams: List<AudioStreamReport>,
    val logs: List<String> = emptyList(),
    val logsTruncated: Boolean = false,
) {
    val noAudio: Boolean get() = probeStatus == IntegrityStatus.CLEAN && streams.isEmpty()
    val status: IntegrityStatus get() = when {
        probeStatus == IntegrityStatus.FAILED || streams.any { it.status == IntegrityStatus.FAILED } -> IntegrityStatus.FAILED
        probeStatus == IntegrityStatus.ISSUES || streams.any { it.status == IntegrityStatus.ISSUES } -> IntegrityStatus.ISSUES
        else -> IntegrityStatus.CLEAN
    }

    fun toJsonValue(): JsonValue = JsonValue.JObject(listOf(
        "scope" to JsonValue.JString("all audio streams; software decoding; sample counts per channel"),
        "status" to JsonValue.JString(status.name),
        "probeStatus" to JsonValue.JString(probeStatus.name),
        "noAudio" to JsonValue.JBoolean(noAudio),
        "logs" to JsonValue.JArray(logs.map(JsonValue::JString)),
        "logsTruncated" to JsonValue.JBoolean(logsTruncated),
        "streams" to JsonValue.JArray(streams.map { r ->
            JsonValue.JObject(buildList {
                add("index" to JsonValue.JNumber(r.stream.index.toLong()))
                add("codec" to JsonValue.JString(r.stream.codec))
                add("status" to JsonValue.JString(r.status.name))
                r.stream.sampleRate?.let { add("declaredSampleRate" to JsonValue.JNumber(it.toLong())) }
                r.stream.channels?.let { add("declaredChannels" to JsonValue.JNumber(it.toLong())) }
                r.stream.durationSeconds?.let { add("declaredDurationSeconds" to JsonValue.JString(it.toString())) }
                add("decodedFrames" to JsonValue.JNumber(r.decodedFrames))
                add("decodedSamples" to JsonValue.JNumber(r.decodedSamples))
                add("decodedDurationSeconds" to JsonValue.JString(r.decodedDurationSeconds.toString()))
                add("durationToleranceSeconds" to JsonValue.JString(r.durationToleranceSeconds.toString()))
                add("observedSampleRates" to JsonValue.JArray(r.observedSampleRates.map { JsonValue.JNumber(it.toLong()) }))
                add("observedChannels" to JsonValue.JArray(r.observedChannels.map { JsonValue.JNumber(it.toLong()) }))
                add("mismatches" to JsonValue.JArray(r.mismatches.map(JsonValue::JString)))
                add("logs" to JsonValue.JArray(r.logs.map(JsonValue::JString)))
                add("logsTruncated" to JsonValue.JBoolean(r.logsTruncated))
            })
        }),
    ))
}

internal fun parseAudioStream(line: String): AudioStreamMetadata? {
    if (!line.startsWith("stream|")) return null
    val fields = line.split('|').drop(1).mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
    val index = fields["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return AudioStreamMetadata(index, fields["codec_name"] ?: "unknown",
        fields["sample_rate"]?.toIntOrNull()?.takeIf { it > 0 },
        fields["channels"]?.toIntOrNull()?.takeIf { it > 0 },
        fields["duration"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 })
}

internal data class AudioFrame(val rate: Int, val channels: Int, val samples: Int)

private val audioFrameField = Regex("(?:^|\\s)(rate|channels|nb_samples):(\\d+)(?=\\s|$)")
internal fun parseAudioFrame(line: String): AudioFrame? {
    if (!line.contains("Parsed_ashowinfo_") || !line.contains(" nb_samples:")) return null
    val fields = audioFrameField.findAll(line).associate { it.groupValues[1] to it.groupValues[2].toIntOrNull() }
    val rate = fields["rate"]?.takeIf { it > 0 } ?: return null
    val channels = fields["channels"]?.takeIf { it > 0 } ?: return null
    val samples = fields["nb_samples"]?.takeIf { it > 0 } ?: return null
    return AudioFrame(rate, channels, samples)
}

internal class AudioMeasurements {
    private var frames = 0L
    private var samples = 0L
    private var duration = 0.0
    private var largestFrame = 0.0
    private val rates = sortedSetOf<Int>()
    private val channels = sortedSetOf<Int>()

    fun add(frame: AudioFrame) {
        frames++
        samples += frame.samples
        val seconds = frame.samples.toDouble() / frame.rate
        duration += seconds
        largestFrame = maxOf(largestFrame, seconds)
        rates.add(frame.rate)
        channels.add(frame.channels)
    }

    fun report(stream: AudioStreamMetadata, exit: Int, logs: List<String>, truncated: Boolean = false): AudioStreamReport {
        val tolerance = maxOf(0.1, largestFrame * 2)
        val mismatches = buildList {
            if (frames > 0) {
                if (stream.sampleRate != null && rates.any { it != stream.sampleRate })
                    add("Sample rate: declared ${stream.sampleRate} Hz, decoded ${rates.joinToString()} Hz")
                if (stream.channels != null && channels.any { it != stream.channels })
                    add("Channels: declared ${stream.channels}, decoded ${channels.joinToString()}")
                if (stream.durationSeconds != null && abs(stream.durationSeconds - duration) > tolerance)
                    add("Duration: declared ${stream.durationSeconds} s, decoded $duration s (tolerance $tolerance s)")
            }
        }
        val status = when {
            exit != 0 || samples == 0L -> IntegrityStatus.FAILED
            logs.isNotEmpty() || mismatches.isNotEmpty() -> IntegrityStatus.ISSUES
            else -> IntegrityStatus.CLEAN
        }
        val details = logs + when {
            exit != 0 -> listOf("FFmpeg exited with code $exit; decoding incomplete")
            samples == 0L -> listOf("No audio samples decoded")
            else -> emptyList()
        }
        return AudioStreamReport(stream, status, frames, samples, duration, rates.toSet(), channels.toSet(),
            tolerance, mismatches, details.take(2000), truncated || details.size > 2000)
    }
}

private class AudioLogs(private val file: File) {
    val lines = mutableListOf<String>()
    var truncated = false
    fun add(line: String) {
        if (lines.size < 2000) lines.add(scrubPaths(line, file)) else truncated = true
    }
}

/** Independently decodes every audio stream; errors never turn into a successful no-audio report. */
suspend fun inspectAudioIntegrity(
    file: File,
    ffmpeg: String = FfmpegLocator.ffmpegPath(),
    ffprobe: String = FfmpegLocator.ffprobePath(),
): AudioIntegrityReport = withContext(Dispatchers.IO) {
    val streams = mutableListOf<AudioStreamMetadata>()
    val probeLogs = AudioLogs(file)
    val probeExit = try {
        integrityProcess(listOf(ffprobe, "-v", "warning", "-select_streams", "a", "-show_entries",
            "stream=index,codec_name,sample_rate,channels,duration", "-of", "compact", file.absolutePath)) { line ->
            if (line.isNotBlank()) {
                val stream = parseAudioStream(line)
                if (stream != null) streams.add(stream) else probeLogs.add(line)
            }
        }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        probeLogs.add("Audio probing timed out (30 minutes)")
        -1
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        probeLogs.add(e.message ?: e.toString())
        -1
    }
    if (probeExit != 0) {
        probeLogs.add("Audio probing failed (exit $probeExit)")
        return@withContext AudioIntegrityReport(IntegrityStatus.FAILED, emptyList(), probeLogs.lines, probeLogs.truncated)
    }
    val reports = streams.map { stream ->
        currentCoroutineContext().ensureActive()
        val measurements = AudioMeasurements()
        val logs = AudioLogs(file)
        val exit = try {
            integrityProcess(listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "level+info", "-nostats",
                "-guess_layout_max", "0", "-i", file.absolutePath, "-map", "0:${stream.index}", "-vn", "-sn", "-dn",
                "-af", "ashowinfo", "-f", "null", "-")) { line ->
                val frame = parseAudioFrame(line)
                when {
                    frame != null -> measurements.add(frame)
                    line.contains("[warning]") || line.contains("[error]") || line.contains("[fatal]") ||
                        line.contains("[panic]") -> logs.add(line)
                    line.contains("Parsed_ashowinfo_") && line.contains("nb_samples:") ->
                        logs.add("Unrecognized decoded audio frame: $line")
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            logs.add("Audio decoding timed out (30 minutes)")
            -1
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            logs.add(e.message ?: e.toString())
            -1
        }
        measurements.report(stream, exit, logs.lines, logs.truncated)
    }
    AudioIntegrityReport(if (probeLogs.lines.isEmpty()) IntegrityStatus.CLEAN else IntegrityStatus.ISSUES,
        reports, probeLogs.lines.toList(), probeLogs.truncated)
}
