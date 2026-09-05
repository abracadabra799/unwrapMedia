package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Represents a single timed packet or frame from either audio or video stream.
 */
data class StreamPacket(
    val mediaType: String, // "video" or "audio"
    val ptsSeconds: Double,
    val dtsSeconds: Double?,
    val durationSeconds: Double?,
    val sizeBytes: Int?,
    val pos: Long?,
)

/**
 * A time-aligned comparison point showing synchronization delta.
 */
data class SyncPoint(
    val timeSeconds: Double,
    val videoPts: Double,
    val audioPts: Double,
    val deltaMs: Double, // Video PTS - Audio PTS (positive = video is behind / audio leads)
    val videoFrameIndex: Int,
    val audioPacketIndex: Int,
)

enum class SyncSeverity {
    PASS,
    WARNING,
    CRITICAL
}

/**
 * Root-cause diagnosis and actionable recommendations.
 */
data class SyncDiagnosis(
    val category: String,
    val severity: SyncSeverity,
    val summary: String,
    val technicalDetails: List<String>,
    val androidImpact: String,
    val recommendedFix: String,
    val suggestedFfmpegCommand: String?,
    val commandDescription: String? = null,
)

/**
 * Complete analysis result for Audio/Video synchronization and duration matching.
 */
data class AvSyncReport(
    val file: File,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoDurationSec: Double,
    val audioDurationSec: Double,
    val durationDeltaSec: Double, // videoDuration - audioDuration
    val initialSkewMs: Double,   // (videoFirstPts - audioFirstPts) * 1000
    val maxSkewMs: Double,
    val minSkewMs: Double,
    val avgSkewMs: Double,
    val driftRateMsPerMin: Double,
    val syncPoints: List<SyncPoint>,
    val diagnoses: List<SyncDiagnosis>,
    val overallSeverity: SyncSeverity,
    val sampleVideoPackets: List<StreamPacket> = emptyList(),
    val sampleAudioPackets: List<StreamPacket> = emptyList(),
)

object AvSyncAnalyzer {

    suspend fun analyze(file: File): AvSyncReport? = withContext(Dispatchers.IO) {
        try {
            val packets = probePackets(file) ?: return@withContext null
            val videoPackets = packets.filter { it.mediaType == "video" }.sortedBy { it.ptsSeconds }
            val audioPackets = packets.filter { it.mediaType == "audio" }.sortedBy { it.ptsSeconds }

            val hasVideo = videoPackets.isNotEmpty()
            val hasAudio = audioPackets.isNotEmpty()

            if (!hasVideo || !hasAudio) {
                return@withContext null
            }

            val vFirst = videoPackets.first().ptsSeconds
            val aFirst = audioPackets.first().ptsSeconds
            val initialSkewMs = (vFirst - aFirst) * 1000.0

            val vLast = videoPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }
            val aLast = audioPackets.last().let { it.ptsSeconds + (it.durationSeconds ?: 0.0) }

            val videoDurationSec = (vLast - vFirst).coerceAtLeast(0.0)
            val audioDurationSec = (aLast - aFirst).coerceAtLeast(0.0)
            val durationDeltaSec = videoDurationSec - audioDurationSec

            // Generate Sync Points by matching closest audio packet for sample video frames
            val syncPoints = computeSyncPoints(videoPackets, audioPackets)

            val maxSkewMs = if (syncPoints.isNotEmpty()) syncPoints.maxOf { it.deltaMs } else initialSkewMs
            val minSkewMs = if (syncPoints.isNotEmpty()) syncPoints.minOf { it.deltaMs } else initialSkewMs
            val avgSkewMs = if (syncPoints.isNotEmpty()) syncPoints.map { it.deltaMs }.average() else initialSkewMs

            // Drift rate calculation (change in delta over duration)
            val effectiveDurationMin = (videoDurationSec.coerceAtLeast(audioDurationSec) / 60.0).coerceAtLeast(0.01)
            val firstDelta = syncPoints.firstOrNull()?.deltaMs ?: initialSkewMs
            val lastDelta = syncPoints.lastOrNull()?.deltaMs ?: initialSkewMs
            val driftRateMsPerMin = (lastDelta - firstDelta) / effectiveDurationMin

            // Diagnoses
            val diagnoses = mutableListOf<SyncDiagnosis>()

            // 1. Initial Skew Check
            if (abs(initialSkewMs) > 100.0) {
                diagnoses.add(
                    SyncDiagnosis(
                        category = "초기 립싱크 불일치 (Initial Skew)",
                        severity = SyncSeverity.CRITICAL,
                        summary = String.format(Locale.US, "시작 지점에서 %.1f ms의 심각한 오프셋이 감지되었습니다.", initialSkewMs),
                        technicalDetails = listOf(
                            "비디오 시작 PTS: ${String.format(Locale.US, "%.3f", vFirst)} s",
                            "오디오 시작 PTS: ${String.format(Locale.US, "%.3f", aFirst)} s",
                            if (initialSkewMs > 0) "오디오가 비디오보다 먼저 재생 시작됨 (Audio Leads Video)"
                            else "비디오가 오디오보다 먼저 재생 시작됨 (Video Leads Audio)"
                        ),
                        androidImpact = "ExoPlayer 및 NuPlayer 재생 시 시작 직후 버퍼 앵커 지연 또는 첫 화면 정지 후 음성만 재생되는 현상이 발생할 수 있습니다.",
                        recommendedFix = "Muxing 시 시작 타임스탬프를 0으로 리셋하거나 edit list(elst)를 적용하여 시작점을 정렬하세요.",
                        suggestedFfmpegCommand = "ffmpeg -i \"${file.name}\" -c copy -avoid_negative_ts make_zero \"fixed_${file.name}\"",
                        commandDescription = "재인코딩 없이 타임스탬프 기준점을 0으로 강제 초기화하여 시작 립싱크를 맞추는 무손실 복구 명령어입니다."
                    )
                )
            } else if (abs(initialSkewMs) > 40.0) {
                diagnoses.add(
                    SyncDiagnosis(
                        category = "초기 립싱크 편차 경고 (Lip-sync Warning)",
                        severity = SyncSeverity.WARNING,
                        summary = String.format(Locale.US, "시작 시점에 %.1f ms의 편차가 있습니다. (ITU-R BT.1359 권고 임계치)", initialSkewMs),
                        technicalDetails = listOf(
                            "비디오 시작 PTS: ${String.format(Locale.US, "%.3f", vFirst)} s",
                            "오디오 시작 PTS: ${String.format(Locale.US, "%.3f", aFirst)} s"
                        ),
                        androidImpact = "인간의 인지 범위(-90ms ~ +45ms)에 근접하거나 약간 초과하여 대화 장면에서 입모양 불일치(Lip-sync mismatch)가 느껴질 수 있습니다.",
                        recommendedFix = "오디오 및 비디오의 인코딩 시작 오프셋 보정 필터를 적용하세요.",
                        suggestedFfmpegCommand = "ffmpeg -i \"${file.name}\" -c copy -avoid_negative_ts make_zero \"fixed_${file.name}\"",
                        commandDescription = "재인코딩 없이 타임스탬프 기준점을 0으로 재정렬하여 미세 립싱크 편차를 교정하는 명령어입니다."
                    )
                )
            }

            // 2. Duration Mismatch Check
            val durationDiffMs = abs(durationDeltaSec) * 1000.0
            if (durationDiffMs > 250.0) {
                val isAudioLonger = durationDeltaSec < 0
                diagnoses.add(
                    SyncDiagnosis(
                        category = "트랙 재생 시간 불일치 (Duration Mismatch)",
                        severity = SyncSeverity.CRITICAL,
                        summary = String.format(Locale.US, "%s 트랙이 %.1f ms 더 깁니다.", if (isAudioLonger) "오디오" else "비디오", durationDiffMs),
                        technicalDetails = listOf(
                            "비디오 총 길이: ${String.format(Locale.US, "%.3f", videoDurationSec)} s",
                            "오디오 총 길이: ${String.format(Locale.US, "%.3f", audioDurationSec)} s",
                            "차이 (Delta): ${String.format(Locale.US, "%+.3f", durationDeltaSec)} s",
                            if (isAudioLonger) "원인: 비디오 녹화/인코딩 파이프라인 조기 종료 (Video Pipeline Truncated)"
                            else "원인: 오디오 버퍼 언더런 또는 마이크 캡처 조기 종료"
                        ),
                        androidImpact = "안드로이드 기본 미디어 플레이어 재생 시 영상 끝부분에서 화면이 멈춘 채 소리만 잔류하거나(Black screen with audio), 오디오가 뚝 끊기며 버퍼 언더런이 유발됩니다.",
                        recommendedFix = if (isAudioLonger) "더 짧은 비디오 길이에 맞춰 긴 오디오의 끝부분을 무손실 트리밍(-shortest)하세요."
                        else "더 짧은 오디오 길이에 맞춰 비디오 끝을 맞추거나 무음 패딩을 추가하세요.",
                        suggestedFfmpegCommand = "ffmpeg -i \"${file.name}\" -c copy -shortest \"fixed_${file.name}\"",
                        commandDescription = "재인코딩 없이 더 긴 트랙의 뒷부분을 짧은 트랙의 길이에 맞춰 딱 잘라내어 길이를 일치시키는 명령어입니다."
                    )
                )
            } else if (durationDiffMs > 60.0) {
                diagnoses.add(
                    SyncDiagnosis(
                        category = "사소한 트랙 길이 차이 (Minor Duration Drift)",
                        severity = SyncSeverity.WARNING,
                        summary = String.format(Locale.US, "비디오와 오디오의 끝 지점 차이가 %.1f ms입니다.", durationDiffMs),
                        technicalDetails = listOf(
                            "AAC 인코더의 1024 샘플 블록 패딩(약 21~23ms) 이상의 끝단 차이가 발생했습니다.",
                            "비디오: ${String.format(Locale.US, "%.3f", videoDurationSec)} s vs 오디오: ${String.format(Locale.US, "%.3f", audioDurationSec)} s"
                        ),
                        androidImpact = "재생 끝부분에서 0.1초 내외의 미세한 화면 멈춤 또는 오디오 드랍이 발생할 수 있습니다.",
                        recommendedFix = "Muxer 설정에서 마지막 프레임의 duration 정밀도를 확인하세요.",
                        suggestedFfmpegCommand = null
                    )
                )
            }

            // 3. Progressive Drift Check
            if (abs(driftRateMsPerMin) > 20.0) {
                diagnoses.add(
                    SyncDiagnosis(
                        category = "점진적 타임스탬프 드리프트 (Clock / Timestamp Drift)",
                        severity = SyncSeverity.CRITICAL,
                        summary = String.format(Locale.US, "분당 %.1f ms 비율로 오디오와 비디오의 간격이 점차 벌어지고 있습니다.", driftRateMsPerMin),
                        technicalDetails = listOf(
                            "시작 시점 편차: ${String.format(Locale.US, "%.1f", firstDelta)} ms",
                            "종료 시점 편차: ${String.format(Locale.US, "%.1f", lastDelta)} ms",
                            "드리프트 변화량: ${String.format(Locale.US, "%+.1f", lastDelta - firstDelta)} ms",
                            "원인: 비디오 클럭(90kHz)과 오디오 샘플레이트 클럭(44.1/48kHz) 간의 클럭 스큐(Clock Skew) 또는 비디오 가변 프레임레이트(VFR) 드랍 누적"
                        ),
                        androidImpact = "장시간 재생할수록 립싱크가 점차 심하게 밀려서 뒤로 갈수록 영상과 음성이 전혀 맞지 않게 됩니다.",
                        recommendedFix = "오디오 타임스탬프 자동 재샘플링(-af aresample=async=1000)을 적용하여 비디오 타임라인에 소리를 강제로 동기화하세요.",
                        suggestedFfmpegCommand = "ffmpeg -i \"${file.name}\" -af aresample=async=1000 -c:v copy \"fixed_${file.name}\"",
                        commandDescription = "비디오 화질 손실 없이(c:v copy), 오디오 샘플 타임스탬프를 비디오 시간에 맞춰 실시간 스트레칭/압축 동기화하는 명령어입니다."
                    )
                )
            }

            val overallSeverity = when {
                diagnoses.any { it.severity == SyncSeverity.CRITICAL } -> SyncSeverity.CRITICAL
                diagnoses.any { it.severity == SyncSeverity.WARNING } -> SyncSeverity.WARNING
                else -> SyncSeverity.PASS
            }

            if (diagnoses.isEmpty()) {
                diagnoses.add(
                    SyncDiagnosis(
                        category = "A/V 동기화 및 길이 일치 (All Synchronized)",
                        severity = SyncSeverity.PASS,
                        summary = "오디오와 비디오가 전 구간에서 안정적으로 동기화되어 있습니다.",
                        technicalDetails = listOf(
                            "초기 립싱크 오프셋: ${String.format(Locale.US, "%.1f", initialSkewMs)} ms (양호: ±40ms 이내)",
                            "트랙 길이 차이: ${String.format(Locale.US, "%.1f", durationDiffMs)} ms (양호: 인코더 패딩 허용치)",
                            "드리프트 비율: ${String.format(Locale.US, "%.2f", driftRateMsPerMin)} ms/분 (클럭 왜곡 없음)"
                        ),
                        androidImpact = "안드로이드 MediaCodec, ExoPlayer, NuPlayer에서 A/V 싱크 드랍이나 버퍼 언더런 없이 최적의 재생 품질을 보장합니다.",
                        recommendedFix = "별도의 수정이 필요하지 않습니다.",
                        suggestedFfmpegCommand = null
                    )
                )
            }

            AvSyncReport(
                file = file,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                videoDurationSec = videoDurationSec,
                audioDurationSec = audioDurationSec,
                durationDeltaSec = durationDeltaSec,
                initialSkewMs = initialSkewMs,
                maxSkewMs = maxSkewMs,
                minSkewMs = minSkewMs,
                avgSkewMs = avgSkewMs,
                driftRateMsPerMin = driftRateMsPerMin,
                syncPoints = syncPoints,
                diagnoses = diagnoses,
                overallSeverity = overallSeverity,
                sampleVideoPackets = videoPackets,
                sampleAudioPackets = audioPackets,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun computeSyncPoints(
        videoPackets: List<StreamPacket>,
        audioPackets: List<StreamPacket>,
        targetSampleCount: Int = 120
    ): List<SyncPoint> {
        if (videoPackets.isEmpty() || audioPackets.isEmpty()) return emptyList()

        val step = (videoPackets.size / targetSampleCount).coerceAtLeast(1)
        val points = ArrayList<SyncPoint>(targetSampleCount + 2)

        var audioSearchIdx = 0
        val audioCount = audioPackets.size

        for (vIdx in videoPackets.indices step step) {
            val vPkt = videoPackets[vIdx]
            val vPts = vPkt.ptsSeconds

            while (audioSearchIdx + 1 < audioCount &&
                abs(audioPackets[audioSearchIdx + 1].ptsSeconds - vPts) <= abs(audioPackets[audioSearchIdx].ptsSeconds - vPts)
            ) {
                audioSearchIdx++
            }

            val aPkt = audioPackets[audioSearchIdx]
            val aPts = aPkt.ptsSeconds
            val deltaMs = (vPts - aPts) * 1000.0

            points.add(
                SyncPoint(
                    timeSeconds = vPts,
                    videoPts = vPts,
                    audioPts = aPts,
                    deltaMs = deltaMs,
                    videoFrameIndex = vIdx,
                    audioPacketIndex = audioSearchIdx
                )
            )
        }

        if (videoPackets.size > 1 && (videoPackets.size - 1) % step != 0) {
            val vIdx = videoPackets.size - 1
            val vPkt = videoPackets[vIdx]
            val vPts = vPkt.ptsSeconds
            while (audioSearchIdx + 1 < audioCount &&
                abs(audioPackets[audioSearchIdx + 1].ptsSeconds - vPts) <= abs(audioPackets[audioSearchIdx].ptsSeconds - vPts)
            ) {
                audioSearchIdx++
            }
            val aPkt = audioPackets[audioSearchIdx]
            points.add(
                SyncPoint(
                    timeSeconds = vPts,
                    videoPts = vPts,
                    audioPts = aPkt.ptsSeconds,
                    deltaMs = (vPts - aPkt.ptsSeconds) * 1000.0,
                    videoFrameIndex = vIdx,
                    audioPacketIndex = audioSearchIdx
                )
            )
        }

        return points
    }

    private fun probePackets(file: File): List<StreamPacket>? {
        var process: Process? = null
        return try {
            process = ProcessManager.register(
                ProcessBuilder(
                    FfmpegLocator.ffprobePath(), "-v", "error",
                    "-show_entries", "packet=codec_type,pts_time,dts_time,duration_time,size,pos",
                    "-of", "default=noprint_wrappers=1", file.absolutePath
                ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { FfmpegLocator.configureEnvironment(it) }.start()
            )

            val packets = ArrayList<StreamPacket>(10000)
            val map = mutableMapOf<String, String>()

            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val eq = line.indexOf('=')
                    if (eq < 0) continue
                    val key = line.substring(0, eq)
                    val value = line.substring(eq + 1)
                    map[key] = value

                    if (key == "pos" || key == "size") {
                        val codecType = map["codec_type"]
                        val pts = map["pts_time"]?.toDoubleOrNull()
                        if (codecType != null && pts != null && (codecType == "video" || codecType == "audio")) {
                            val dts = map["dts_time"]?.toDoubleOrNull()
                            val dur = map["duration_time"]?.toDoubleOrNull()
                            val size = map["size"]?.toIntOrNull()
                            val pos = map["pos"]?.toLongOrNull()
                            packets.add(StreamPacket(codecType, pts, dts, dur, size, pos))
                            map.clear()
                        }
                    }
                }
            }
            process.waitFor()
            packets
        } catch (e: Exception) {
            null
        } finally {
            process?.let {
                ProcessManager.terminate(it)
                ProcessManager.unregister(it)
            }
        }
    }
}
