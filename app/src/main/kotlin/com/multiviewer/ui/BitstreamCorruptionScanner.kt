package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class CorruptionSeverity {
    PASS,
    CRITICAL,
    WARNING,
    INFO
}

/**
 * Human-readable technical explanation and actionable guide for a decoder error.
 */
data class ErrorExplanation(
    val title: String,
    val summary: String,
    val probableCause: String,
    val visualImpact: String,
    val mbCoordinates: Pair<Int, Int>? = null,
    val pixelCoordinates: Pair<Int, Int>? = null,
    val bufferOverrunBytes: Int? = null,
    val actionableFix: String,
)

/**
 * Details of a single detected corrupt/damaged frame or slice.
 */
data class CorruptFrameEntry(
    val frameIndex: Int?,
    val ptsSeconds: Double?,
    val frameType: Char?, // 'I', 'P', 'B' or '?'
    val byteOffset: Long?,
    val sizeBytes: Int?,
    val severity: CorruptionSeverity,
    val errorMessage: String,
    val affectedGopSpan: Int, // Number of subsequent frames affected due to reference corruption
    val androidDecoderRisk: String,
    val explanation: ErrorExplanation? = null,
) {
    /**
     * The exact byte range where the corruption occurred or was triggered,
     * e.g. the trailing buffer overrun bytes of the slice.
     */
    val errorSpecificByteRange: LongRange?
        get() {
            val start = byteOffset ?: return null
            val size = sizeBytes?.toLong() ?: return null
            val overrun = explanation?.bufferOverrunBytes
            return if (overrun != null && size > 0) {
                val absOverrun = kotlin.math.abs(overrun).toLong().coerceAtLeast(16L).coerceAtMost(size)
                val targetStart = (start + size - absOverrun).coerceAtLeast(start)
                targetStart until (start + size)
            } else {
                start until (start + size.coerceAtLeast(16L))
            }
        }
}

/**
 * Summary report of the bitstream scan.
 */
data class BitstreamCorruptionReport(
    val file: File,
    val totalFramesScanned: Int,
    val corruptFrameCount: Int,
    val criticalErrorCount: Int,
    val entries: List<CorruptFrameEntry>,
    val hasReferenceLoss: Boolean,
    val overallStatus: CorruptionSeverity,
    val summaryRecommendation: String,
)

object BitstreamCorruptionScanner {

    // Keywords in ffmpeg / libavcodec decoder output signaling bitstream corruption
    private val CRITICAL_KEYWORDS = listOf(
        "cabac decode error",
        "concealing",
        "error while decoding",
        "missing picture in dpb",
        "reference picture missing",
        "slice mismatch",
        "invalid mb_type",
        "corrupted stream",
        "out of range",
        "nal size exceeds",
        "invalid length"
    )

    private val WARNING_KEYWORDS = listOf(
        "non-existing pps",
        "non-existing sps",
        "co-located poc",
        "reinit context",
        "poc mismatch",
        "overflow in",
        "mmco: ref not found"
    )

    suspend fun scan(file: File, knownFrames: List<FrameInfo>? = null): BitstreamCorruptionReport? = withContext(Dispatchers.IO) {
        try {
            val (frames, rawErrors) = probeFramesAndErrors(file, knownFrames)
            val corruptEntries = mutableListOf<CorruptFrameEntry>()

            // Map each raw error line to the closest frame by PTS or sequential count
            for (raw in rawErrors) {
                val lower = raw.text.lowercase(Locale.US)
                val severity = when {
                    CRITICAL_KEYWORDS.any { lower.contains(it) } -> CorruptionSeverity.CRITICAL
                    WARNING_KEYWORDS.any { lower.contains(it) } -> CorruptionSeverity.WARNING
                    else -> CorruptionSeverity.INFO
                }

                // Match with frame by frameNumber, PTS, or approximate
                val matchedFrame = if (raw.frameNumber != null && raw.frameNumber > 0 && frames.isNotEmpty()) {
                    frames.getOrNull((raw.frameNumber - 1).coerceAtMost(frames.size - 1))
                } else if (raw.pts != null && frames.isNotEmpty()) {
                    frames.minByOrNull { kotlin.math.abs(it.ptsSeconds - raw.pts) }
                } else frames.firstOrNull()

                val frameIdx = matchedFrame?.index
                val pts = matchedFrame?.ptsSeconds ?: raw.pts
                val frameType = matchedFrame?.type ?: '?'
                val byteOffset = matchedFrame?.byteOffset ?: raw.pos
                val size = matchedFrame?.sizeBytes

                // Calculate cascading corruption risk
                // If an I or P reference frame is corrupted, all subsequent frames until next I-frame are visually corrupted.
                var affectedCount = 1
                if (frameIdx != null && frames.isNotEmpty()) {
                    if (frameType == 'I' || frameType == 'P') {
                        val nextI = frames.drop(frameIdx + 1).indexOfFirst { it.type == 'I' }
                        affectedCount = if (nextI >= 0) nextI + 1 else (frames.size - frameIdx)
                    }
                }

                val risk = when {
                    severity == CorruptionSeverity.CRITICAL && (frameType == 'I' || frameType == 'P') ->
                        "참조 프레임($frameType) 손상으로 다음 IDR 키프레임까지 최대 $affectedCount 프레임 동안 연쇄 모자이크/녹색 화면 발생"
                    severity == CorruptionSeverity.CRITICAL ->
                        "안드로이드 MediaCodec/OMX 하드웨어 디코더에서 프레임 드랍(ERROR_MALFORMED) 또는 디코더 행(Hang) 유발 가능"
                    else ->
                        "미세 디코딩 왜곡 또는 화면 떨림(Glitch) 가능성"
                }

                corruptEntries.add(
                    CorruptFrameEntry(
                        frameIndex = frameIdx,
                        ptsSeconds = pts,
                        frameType = frameType,
                        byteOffset = byteOffset,
                        sizeBytes = size,
                        severity = severity,
                        errorMessage = raw.text,
                        affectedGopSpan = affectedCount,
                        androidDecoderRisk = risk,
                        explanation = interpretDecoderError(raw.text),
                    )
                )
            }

            val criticalCount = corruptEntries.count { it.severity == CorruptionSeverity.CRITICAL }
            val hasRefLoss = corruptEntries.any { it.affectedGopSpan > 1 }

            val overallStatus = when {
                criticalCount > 0 -> CorruptionSeverity.CRITICAL
                corruptEntries.isNotEmpty() -> CorruptionSeverity.WARNING
                else -> CorruptionSeverity.PASS
            }

            val recommendation = when {
                criticalCount > 0 ->
                    "손상된 비트스트림 NAL 오프셋이 발견되었습니다. 프레임 바이트를 확인하거나 참조 무결성을 위해 해당 GOP 구간을 재인코딩(Re-encode)하거나 복구 필터를 적용하세요."
                corruptEntries.isNotEmpty() ->
                    "경미한 규격 이상이나 비표준 헤더가 있습니다. 모바일 디코더 호환성을 위해 리먹싱(-c copy)을 권장합니다."
                else ->
                    "비트스트림 디코딩 결함이 발견되지 않았습니다. 모든 슬라이스와 매크로블록 구문이 표준을 준수합니다."
            }

            BitstreamCorruptionReport(
                file = file,
                totalFramesScanned = frames.size,
                corruptFrameCount = corruptEntries.size,
                criticalErrorCount = criticalCount,
                entries = corruptEntries,
                hasReferenceLoss = hasRefLoss,
                overallStatus = overallStatus,
                summaryRecommendation = recommendation
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private data class RawDecoderError(
        val text: String,
        val pts: Double?,
        val frameNumber: Int?,
        val pos: Long?
    )

    private fun probeFramesAndErrors(
        file: File,
        knownFrames: List<FrameInfo>? = null
    ): Pair<List<FrameInfo>, List<RawDecoderError>> {
        val frames = mutableListOf<FrameInfo>()
        val errors = mutableListOf<RawDecoderError>()
        var process: Process? = null
        try {
            // Run ffprobe with -v warning, showing frames, so stderr errors and stdout frames are interleaved.
            process = ProcessManager.register(
                ProcessBuilder(
                    FfmpegLocator.ffprobePath(), "-v", "warning", "-select_streams", "v:0",
                    "-show_frames",
                    "-show_entries", "frame=pict_type,pkt_size,pts_time,pkt_pos",
                    file.absolutePath
                ).redirectErrorStream(true).also { FfmpegLocator.configureEnvironment(it) }.start()
            )

            val values = mutableMapOf<String, String>()
            var pendingError: String? = null

            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val lower = trimmed.lowercase(Locale.US)

                    val isError = CRITICAL_KEYWORDS.any { lower.contains(it) } || WARNING_KEYWORDS.any { lower.contains(it) }
                    if (isError) {
                        pendingError = trimmed
                        continue
                    }

                    if (trimmed == "[FRAME]") {
                        values.clear()
                    } else if (trimmed == "[/FRAME]") {
                        val pts = values["pts_time"]?.toDoubleOrNull()
                        val size = values["pkt_size"]?.toIntOrNull()
                        val type = values["pict_type"]?.firstOrNull() ?: '?'
                        val byteOffset = values["pkt_pos"]?.toLongOrNull()

                        val newFrame = FrameInfo(
                            index = frames.size,
                            type = type,
                            sizeBytes = size ?: 0,
                            ptsSeconds = pts ?: 0.0,
                            byteOffset = byteOffset
                        )
                        frames.add(newFrame)

                        if (pendingError != null) {
                            errors.add(
                                RawDecoderError(
                                    text = pendingError!!,
                                    pts = newFrame.ptsSeconds,
                                    frameNumber = newFrame.index + 1,
                                    pos = newFrame.byteOffset
                                )
                            )
                            pendingError = null
                        }
                        values.clear()
                    } else {
                        val eq = trimmed.indexOf('=')
                        if (eq > 0) {
                            values[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                        }
                    }
                }
            }

            if (pendingError != null) {
                val lastFrame = frames.lastOrNull()
                errors.add(
                    RawDecoderError(
                        text = pendingError!!,
                        pts = lastFrame?.ptsSeconds,
                        frameNumber = lastFrame?.let { it.index + 1 },
                        pos = lastFrame?.byteOffset
                    )
                )
            }
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            process?.let {
                ProcessManager.terminate(it)
                ProcessManager.unregister(it)
            }
        }

        // If knownFrames were already analyzed, keep the caller's frames but keep matched errors
        val finalFrames = if (knownFrames != null && knownFrames.isNotEmpty()) knownFrames else frames
        return Pair(finalFrames, errors)
    }

    /**
     * Interprets raw low-level decoder logs into structured, human-friendly engineering explanations.
     */
    fun interpretDecoderError(errorLine: String): ErrorExplanation {
        // Pattern 1: error while decoding MB X Y, bytestream Z
        val mbRegex = Regex("""error while decoding MB (\d+) (\d+),\s*bytestream\s*(-?\d+)""", RegexOption.IGNORE_CASE)
        val mbMatch = mbRegex.find(errorLine)
        if (mbMatch != null) {
            val mbX = mbMatch.groupValues[1].toInt()
            val mbY = mbMatch.groupValues[2].toInt()
            val overrun = mbMatch.groupValues[3].toInt()
            val pxX = mbX * 16
            val pxY = mbY * 16
            val overrunAbs = kotlin.math.abs(overrun)
            return ErrorExplanation(
                title = "H.264/AVC 슬라이스 비트스트림 손상 (Buffer Overrun)",
                summary = "매크로블록 ($mbX, $mbY) 디코딩 중 슬라이스 데이터가 $overrunAbs 바이트 조기 소진되었습니다.",
                probableCause = "비트 반전(Bit-flip)으로 인한 가변길이 부호(VLC) 오파싱 또는 패킷 유실로 슬라이스 데이터가 뒷부분에서 잘렸습니다.",
                visualImpact = "화면 상단/중간 [X: $pxX~${pxX + 16}px, Y: $pxY~${pxY + 16}px] 영역부터 슬라이스 끝까지 픽셀 깨짐(Glitch) 또는 에러 은닉(이전 프레임 복사) 발생",
                mbCoordinates = Pair(mbX, mbY),
                pixelCoordinates = Pair(pxX, pxY),
                bufferOverrunBytes = overrun,
                actionableFix = "1. Hex 뷰에서 해당 샘플 끝 $overrunAbs 바이트 구간 검사 (00 패딩 여부 확인)\n2. stsz 샘플 크기와 실제 NAL 페이로드 크기 일치 여부 대조"
            )
        }

        // Pattern 2: CABAC decode error at X Y
        val cabacRegex = Regex("""cabac decode error at (\d+) (\d+)""", RegexOption.IGNORE_CASE)
        val cabacMatch = cabacRegex.find(errorLine)
        if (cabacMatch != null) {
            val mbX = cabacMatch.groupValues[1].toInt()
            val mbY = cabacMatch.groupValues[2].toInt()
            val pxX = mbX * 16
            val pxY = mbY * 16
            return ErrorExplanation(
                title = "CABAC 산술 부호화 디코딩 문맥 오류",
                summary = "매크로블록 ($mbX, $mbY)의 CABAC 구문 디코딩 중 유효하지 않은 비트열 상태를 만났습니다.",
                probableCause = "비트스트림 엔트로피 코딩 데이터 비트 손상 또는 산술 복호화 확률 테이블 불일치",
                visualImpact = "해당 슬라이스 전체 디코딩 중단 및 모자이크 왜곡",
                mbCoordinates = Pair(mbX, mbY),
                pixelCoordinates = Pair(pxX, pxY),
                actionableFix = "인코더의 CABAC 엔트로피 코딩 옵션 점검 및 전송 중 패킷 손상 여부 확인"
            )
        }

        // Pattern 3: Concealing DC/AC/MV errors
        val concealRegex = Regex("""concealing (\d+) DC,\s*(\d+) AC,\s*(\d+) MV errors in ([IPB]) frame""", RegexOption.IGNORE_CASE)
        val concealMatch = concealRegex.find(errorLine)
        if (concealMatch != null) {
            val dc = concealMatch.groupValues[1]
            val ac = concealMatch.groupValues[2]
            val mv = concealMatch.groupValues[3]
            val fType = concealMatch.groupValues[4]
            return ErrorExplanation(
                title = "디코더 에러 은닉 (Error Concealment) 동작",
                summary = "$fType-프레임에서 주파수 계수(DC: $dc, AC: $ac) 및 움직임 벡터(MV: $mv) 손상으로 화면 땜질을 수행했습니다.",
                probableCause = "슬라이스 데이터 손상으로 인해 디코더가 깨진 블록을 버리고 주변 블록 또는 이전 프레임을 강제 복사함",
                visualImpact = "순간적인 화면 멈춤, 잔상(Ghosting), 모션 불연속 발생",
                actionableFix = "해당 프레임의 참조 무결성을 확인하고 필요 시 GOP 재인코딩 수행"
            )
        }

        // Pattern 4: Invalid NAL unit size / missing start code
        if (errorLine.contains("Invalid NAL unit size", ignoreCase = true) || errorLine.contains("nal size exceeds", ignoreCase = true)) {
            return ErrorExplanation(
                title = "NAL Unit 크기/경계 불일치 결함",
                summary = "NAL Unit 헤더가 지시하는 크기가 컨테이너 샘플 크기 한계를 초과했습니다.",
                probableCause = "AVCC/HVCC 4바이트 길이 접두사 손상 또는 비트스트림 오프셋 테이블(stco/co64) 부정합",
                visualImpact = "해당 NAL 패킷 전체 파싱 실패 및 프레임 디코딩 스킵",
                actionableFix = "1. stsz 및 NAL 4-byte big-endian 길이 필드 값 대조\n2. 컨테이너 리먹싱(Remux)으로 NAL 헤더 재작성"
            )
        }

        // Pattern 5: Non-existing SPS/PPS
        if (errorLine.contains("non-existing pps", ignoreCase = true) || errorLine.contains("non-existing sps", ignoreCase = true)) {
            return ErrorExplanation(
                title = "필수 코덱 파라미터 세트(SPS/PPS) 누락",
                summary = "슬라이스가 참조하는 파라미터 세트 ID가 스트림에 존재하지 않습니다.",
                probableCause = "avcC/hvcC 박스 내 파라미터 세트 추출 실패 또는 인-밴드(In-band) SPS/PPS 헤더 유실",
                visualImpact = "디코더 초기화 실패로 전체 비디오 또는 해당 구간 재생 불가",
                actionableFix = "avcC/hvcC 박스 검사 및 유효한 파라미터 세트 헤더 주입"
            )
        }

        // Default Fallback
        return ErrorExplanation(
            title = "비트스트림 디코더 신택스 경고",
            summary = errorLine.substringAfter("] ").take(100),
            probableCause = "비표준 코덱 플래그 또는 패킷 바이트 불일치",
            visualImpact = "하드웨어 가속 디코더에서 예기치 않은 디코딩 지연 또는 왜곡 가능",
            actionableFix = "Hex 뷰에서 해당 프레임 오프셋을 점검하고 표준 적합성을 확인하세요."
        )
    }
}
