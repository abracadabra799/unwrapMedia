package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

@Composable
fun RawPixelOpenDialog(
    file: File,
    onConfirm: (width: Int, height: Int, format: RawPixelFormat, byteOrder: RawPixelByteOrder) -> Unit,
    onCancel: () -> Unit,
) {
    var widthText by remember { mutableStateOf("") }
    var heightText by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(RawPixelFormat.RGB888) }
    var byteOrder by remember { mutableStateOf(RawPixelByteOrder.LITTLE_ENDIAN) }

    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val fileSize = remember(file) { file.length() }
    val expectedSize = if (width != null && height != null && width > 0 && height > 0) {
        expectedRawFileSize(width, height, format)
    } else {
        null
    }
    val canOpen = width != null && width > 0 && height != null && height > 0

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
            Text(file.name, style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextSecondary))
            Spacer(Modifier.height(16.dp))

            Text("포맷", style = AppTypography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RawPixelFormat.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { candidate ->
                            val selected = format == candidate
                            Box(
                                modifier = Modifier
                                    .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
                                    .clickable { format = candidate }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    candidate.label,
                                    fontSize = 11.sp,
                                    color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (format.needsByteOrder) {
                Text("Byte order", style = AppTypography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                fontSize = 11.sp,
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

            if (expectedSize != null) {
                val matches = expectedSize == fileSize
                Text(
                    if (matches) {
                        "파일 크기 일치 ($fileSize bytes)"
                    } else {
                        "예상 크기 $expectedSize bytes / 실제 파일 크기 $fileSize bytes (불일치 -- 그래도 열 수 있음)"
                    },
                    style = AppTypography.labelLarge.copy(
                        fontSize = 11.sp,
                        color = if (matches) AppColors.NeonGreen else AppColors.NeonYellow,
                    ),
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onConfirm(width!!, height!!, format, byteOrder) }, enabled = canOpen) { Text("열기") }
            }
        }
    }
}
