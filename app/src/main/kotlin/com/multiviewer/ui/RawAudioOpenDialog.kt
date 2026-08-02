package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File

private val SAMPLE_RATE_PRESETS = listOf(8000, 16000, 22050, 44100, 48000, 96000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RawAudioOpenDialog(
    file: File,
    onConfirm: (params: RawAudioParams) -> Unit,
    onCancel: () -> Unit,
) {
    var sampleRateText by remember { mutableStateOf("44100") }
    var channelsText by remember { mutableStateOf("2") }
    var format by remember { mutableStateOf(RawAudioFormat.S16) }
    var byteOrder by remember { mutableStateOf(RawAudioByteOrder.LITTLE_ENDIAN) }
    var offsetText by remember { mutableStateOf("0") }

    val sampleRate = sampleRateText.toIntOrNull()
    val channels = channelsText.toIntOrNull()
    val offsetBytes = offsetText.toLongOrNull()
    val fileSize = remember(file) { file.length() }

    val offsetTooLarge = offsetBytes != null && offsetBytes >= fileSize
    val expectedDuration = if (sampleRate != null && sampleRate > 0 && channels != null && channels > 0 &&
        offsetBytes != null && offsetBytes >= 0 && !offsetTooLarge
    ) {
        computeRawAudioDuration(fileSize, offsetBytes, sampleRate, channels, format.bytesPerSample)
    } else {
        null
    }
    // Non-blocking: a trailing partial sample (the file doesn't end on an exact frame boundary)
    // is simply dropped during decode, same spirit as RawPixelOpenDialog's file-size mismatch
    // warning -- worth flagging to the user, not worth refusing to open over.
    val unevenFrameSize = channels != null && channels > 0 && offsetBytes != null && offsetBytes in 0 until fileSize &&
        (fileSize - offsetBytes) % (channels * format.bytesPerSample) != 0L

    val canOpen = sampleRate != null && sampleRate > 0 && channels != null && channels > 0 &&
        offsetBytes != null && offsetBytes >= 0 && !offsetTooLarge

    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(AppColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                .padding(20.dp),
        ) {
            Text("Raw PCM 오디오 열기", style = AppTypography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(file.name, style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
            Spacer(Modifier.height(16.dp))

            Text("샘플 포맷", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RawAudioFormat.entries.forEach { candidate ->
                    val selected = format == candidate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                            .clickable { format = candidate }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (selected) "●" else "○",
                            fontSize = 10.sp,
                            color = if (selected) AppColors.NeonBlue else AppColors.TextSecondary,
                        )
                        Text(
                            candidate.label,
                            fontSize = 10.sp,
                            color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (format.needsByteOrder) {
                Text("Byte order", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
                Spacer(Modifier.height(4.dp))
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RawAudioByteOrder.entries.forEach { candidate ->
                        val selected = byteOrder == candidate
                        Box(
                            modifier = Modifier
                                .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                                .clickable { byteOrder = candidate }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                candidate.label,
                                fontSize = 10.sp,
                                color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Text("샘플레이트 (Hz)", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SAMPLE_RATE_PRESETS.forEach { preset ->
                    val selected = sampleRateText == preset.toString()
                    Box(
                        modifier = Modifier
                            .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                            .clickable { sampleRateText = preset.toString() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(preset.toString(), fontSize = 10.sp, color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sampleRateText,
                    onValueChange = { sampleRateText = it.filter(Char::isDigit) },
                    label = { Text("샘플레이트") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = channelsText,
                    onValueChange = { channelsText = it.filter(Char::isDigit) },
                    label = { Text("채널 수") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = offsetText,
                onValueChange = { offsetText = it.filter(Char::isDigit) },
                label = { Text("건너뛸 오프셋 (bytes)") },
                singleLine = true,
                modifier = Modifier.width(200.dp),
            )
            Spacer(Modifier.height(8.dp))

            if (offsetTooLarge) {
                Text(
                    "오프셋이 파일 크기보다 크거나 같습니다 -- 재생 가능한 데이터가 없습니다.",
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonRed),
                )
                Spacer(Modifier.height(8.dp))
            } else if (expectedDuration != null) {
                Text(
                    "예상 재생시간: ${"%.2f".format(expectedDuration)}초 ($fileSize bytes)",
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen),
                )
                if (unevenFrameSize) {
                    Text(
                        "⚠ 파일 크기가 프레임 경계에 정확히 맞지 않습니다 -- 마지막 불완전한 샘플은 무시됩니다.",
                        style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonYellow),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onConfirm(RawAudioParams(sampleRate!!, channels!!, format, byteOrder, offsetBytes!!))
                    },
                    enabled = canOpen,
                ) { Text("열기") }
            }
        }
    }
}
