package com.multiviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val presentation = audioFindings(report)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(videoStatusLabel(report.status, ko), color = AppColors.TextPrimary)
        Text(label("선언값과 실제 디코딩 결과를 비교합니다. 샘플 수는 채널당 개수입니다.",
            "Compares declared values with decoded output. Samples are counted per channel."),
            color = AppColors.TextSecondary, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                label("트랙 / 코덱", "Track / codec"), label("상태", "Status"),
                label("샘플레이트: 선언 → 관측", "Rate: declared → observed"), label("채널", "Channels"),
                label("길이: 선언 → 디코딩", "Duration: declared → decoded"), label("샘플", "Samples"),
            )
                .forEach { Text(it, color = AppColors.TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f)) }
        }
        LazyColumn(Modifier.weight(0.42f).fillMaxWidth()) {
            items(presentation.trackRows, key = { it.streamIndex }) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("#${row.streamIndex} ${row.codec}", color = AppColors.TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(checkStatusLabel(row.status, ko), color = checkStatusColor(row.status), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${row.declaredSampleRate ?: "N/A"} → ${row.observedSampleRates.sorted().joinToString().ifEmpty { "N/A" }} Hz",
                        color = AppColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${row.declaredChannels ?: "N/A"} → ${row.observedChannels.sorted().joinToString().ifEmpty { "N/A" }}",
                        color = AppColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${seconds(row.declaredDurationSeconds)} → ${seconds(row.decodedDurationSeconds)}",
                        color = AppColors.TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("${row.decodedSamples}", color = AppColors.TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = AppColors.Border)
            }
        }
        ContentFindingTable(
            presentation.probeFindings + presentation.trackFindings,
            Long.MAX_VALUE,
            ko,
            Modifier.weight(0.58f).fillMaxWidth(),
        )
    }
}
