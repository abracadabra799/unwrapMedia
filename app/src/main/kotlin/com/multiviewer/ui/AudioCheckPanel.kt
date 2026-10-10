package com.multiviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun AudioCheckPanel(report: AudioIntegrityReport?, error: String?, ko: Boolean, modifier: Modifier = Modifier) {
    fun label(korean: String, english: String) = if (ko) korean else english
    fun seconds(value: Double?) = value?.let { String.format(Locale.ROOT, "%.3f s", it) } ?: "N/A"
    if (report == null) {
        Text(error?.let { label("오디오 검사 실패: ", "Audio inspection failed: ") + it }
            ?: label("'검사 시작'을 눌러 모든 오디오 트랙을 디코딩하세요.", "Press 'Start inspection' to decode all audio streams."),
            color = if (error != null) AppColors.NeonRed else AppColors.TextSecondary)
        return
    }
    if (report.noAudio) {
        Text(label("오디오 트랙이 없습니다. 해당 없음.", "No audio streams. Not applicable."), color = AppColors.TextSecondary)
        return
    }
    SelectionContainer(modifier) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(videoStatusLabel(report.status, ko), color = AppColors.TextPrimary)
                Text(label("선언값과 실제 디코딩 결과를 비교합니다. N/A는 선언값 없음이며, 샘플 수는 채널당 개수입니다.",
                    "Compares declared values with decoded output. N/A means undeclared; samples are counted per channel."),
                    color = AppColors.TextSecondary)
            }
            items(report.logs) { Text(it, color = AppColors.NeonYellow) }
            if (report.logsTruncated) item { Text(label("프로브 로그 일부 생략", "Probe logs truncated"), color = AppColors.NeonYellow) }
            report.streams.forEach { r ->
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HorizontalDivider(color = AppColors.Border)
                        Text(label("트랙", "Stream") + " #${r.stream.index} · ${r.stream.codec} · ${videoStatusLabel(r.status, ko)}", color = AppColors.TextPrimary)
                        Text(label("디코딩: ", "Decoded: ") + "${r.decodedFrames} frames · ${r.decodedSamples} samples", color = AppColors.TextPrimary)
                        Text(label("샘플레이트 (선언 → 디코딩): ", "Sample rate (declared → decoded): ") +
                            "${r.stream.sampleRate ?: "N/A"} → ${r.observedSampleRates.joinToString().ifEmpty { "N/A" }} Hz", color = AppColors.TextSecondary)
                        Text(label("채널 (선언 → 디코딩): ", "Channels (declared → decoded): ") +
                            "${r.stream.channels ?: "N/A"} → ${r.observedChannels.joinToString().ifEmpty { "N/A" }}", color = AppColors.TextSecondary)
                        Text(label("길이 (선언 → 디코딩): ", "Duration (declared → decoded): ") +
                            "${seconds(r.stream.durationSeconds)} → ${seconds(r.decodedDurationSeconds)}", color = AppColors.TextSecondary)
                        Text(label("길이 허용 오차: ", "Duration tolerance: ") + seconds(r.durationToleranceSeconds), color = AppColors.TextMuted)
                    }
                }
                items(r.mismatches) { Text(it, color = AppColors.NeonYellow) }
                items(r.logs) { Text(it, color = AppColors.TextSecondary) }
                if (r.logsTruncated) item { Text(label("디코더 로그 일부 생략", "Decoder logs truncated"), color = AppColors.NeonYellow) }
            }
        }
    }
}
