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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RawPixelOpenDialog(
    file: File,
    onConfirm: (width: Int, height: Int, format: RawPixelFormat, byteOrder: RawPixelByteOrder, fps: Double) -> Unit,
    onCancel: () -> Unit,
) {
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(defaultRawPixelFormat(file.extension)) }
    var byteOrder by remember { mutableStateOf(RawPixelByteOrder.LITTLE_ENDIAN) }
    var fpsText by remember { mutableStateOf("30") }

    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val fileSize = remember(file) { file.length() }
    val expectedSize = if (width != null && height != null && width > 0 && height > 0) {
        expectedRawFileSize(width, height, format)
    } else {
        null
    }
    // A dump larger than one frame is a sequence -- ask for a frame rate too so it can be played
    // back as a raw video stream, not just stepped through one frame at a time.
    val frameCount = if (width != null && height != null && width > 0 && height > 0) {
        rawPixelFrameCount(fileSize, width, height, format)
    } else {
        0
    }
    val fps = fpsText.toDoubleOrNull()
    val resolutionHardBlock = if (width != null && height != null && width > 0 && height > 0) {
        hardResolutionRejectionMessage(width, height)
    } else {
        null
    }
    val resolutionWarning = if (resolutionHardBlock == null && width != null && height != null && width > 0 && height > 0) {
        resolutionWarningMessage(width, height, continuousPlayback = frameCount > 1)
    } else {
        null
    }
    val canOpen = width != null && width > 0 && height != null && height > 0 &&
        (frameCount <= 1 || (fps != null && fps > 0)) && resolutionHardBlock == null

    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .background(AppColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                .padding(20.dp),
        ) {
            Text("Raw 픽셀 데이터 열기", style = AppTypography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(file.name, style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
            Spacer(Modifier.height(16.dp))

            Text("포맷", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Spacer(Modifier.height(4.dp))
            // One format per line -- a wrapping chip layout let selections land almost anywhere
            // in the block depending on label length, which read as confusing/hard to scan.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                RawPixelFormat.entries.forEach { candidate ->
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
                    RawPixelByteOrder.entries.forEach { candidate ->
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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { widthText = it.filter(Char::isDigit) },
                    label = { Text("Width") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it.filter(Char::isDigit) },
                    label = { Text("Height") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            if (resolutionHardBlock != null) {
                Text(
                    resolutionHardBlock,
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonRed),
                )
                Spacer(Modifier.height(8.dp))
            } else if (resolutionWarning != null) {
                Text(
                    "⚠ $resolutionWarning",
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonYellow),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (expectedSize != null) {
                val matches = expectedSize == fileSize
                Text(
                    if (matches) {
                        "파일 크기 일치 ($fileSize bytes)"
                    } else {
                        "예상 크기 $expectedSize bytes / 실제 파일 크기 $fileSize bytes (불일치 -- 그래도 열 수 있음)"
                    },
                    style = AppTypography.labelLarge.copy(
                        fontSize = 10.sp,
                        color = if (matches) AppColors.NeonGreen else AppColors.NeonYellow,
                    ),
                )
                if (frameCount > 1) {
                    Text(
                        "$frameCount 프레임 시퀀스로 인식됨 -- 재생 프레임레이트를 입력하세요",
                        style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonBlue),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (frameCount > 1) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = fpsText,
                    onValueChange = { fpsText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("FPS") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp),
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(width!!, height!!, format, byteOrder, if (frameCount > 1) fps!! else 1.0) },
                    enabled = canOpen,
                ) { Text("열기") }
            }
        }
    }
}
