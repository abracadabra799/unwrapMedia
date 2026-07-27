package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val INSPECTOR_WIDTH_DP = 240

// Combines the first byteCount bytes of `bytes` into a raw bit pattern, honoring endianness --
// returns null if fewer bytes are available than requested. The result is a bit-for-bit reading,
// not a value-preserving conversion: callers reinterpret it as signed/unsigned/float of the
// matching width (e.g. combineBytesAsLong(buf, 4, le)?.toInt() for a signed Int32).
fun combineBytesAsLong(bytes: ByteArray, byteCount: Int, littleEndian: Boolean): Long? {
    if (bytes.size < byteCount) return null
    var result = 0L
    for (i in 0 until byteCount) {
        val b = bytes[i].toLong() and 0xFF
        val shift = if (littleEndian) i * 8 else (byteCount - 1 - i) * 8
        result = result or (b shl shift)
    }
    return result
}

// Interprets the bytes at a selection's start offset as every common fixed-width numeric type at
// once (built up from bytes[0], not requiring the selection to be exactly N bytes wide) --
// clicking or selecting anywhere in the hex grid immediately shows int8/16/32/64, float/double,
// and a couple of Unix timestamp readings, the same "select a byte, read every interpretation at
// once" pattern hex editors like 010 Editor and HxD use for their inspector panels.
@Composable
fun ValueInspectorPanel(raf: RandomAccessFile, fileLength: Long, anchorOffset: Long?, selectionByteCount: Long) {
    var littleEndian by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .width(INSPECTOR_WIDTH_DP.dp)
            .fillMaxHeight()
            .background(AppColors.Panel)
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text("값 인스펙터", style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EndiannessChip("Little-endian", selected = littleEndian) { littleEndian = true }
            EndiannessChip("Big-endian", selected = !littleEndian) { littleEndian = false }
        }
        Spacer(Modifier.height(10.dp))

        if (anchorOffset == null) {
            Text(
                "헥스 바이트를 클릭하거나 드래그로 선택하면\n여기에 값 해석이 표시됩니다.",
                style = AppTypography.bodyLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary),
            )
            return@Column
        }

        val available = minOf(8L, fileLength - anchorOffset).toInt().coerceAtLeast(0)
        val buf = ByteArray(available)
        if (available > 0) {
            raf.seek(anchorOffset)
            raf.readFully(buf)
        }

        fun combine(byteCount: Int): Long? = combineBytesAsLong(buf, byteCount, littleEndian)

        InspectorRow("Offset", "$anchorOffset (0x${anchorOffset.toString(16).uppercase()})")

        if (available >= 1) {
            val byte0 = buf[0].toInt() and 0xFF
            InspectorRow("Binary", Integer.toBinaryString(byte0).padStart(8, '0'))
            InspectorRow("Int8 / UInt8", "${buf[0]} / $byte0")
            InspectorRow("ASCII", if (byte0 in 0x20..0x7E) "'${byte0.toChar()}'" else "(non-printable)")
        }
        combine(2)?.let { raw ->
            InspectorRow("Int16 / UInt16", "${raw.toShort()} / ${raw and 0xFFFF}")
        }
        combine(4)?.let { raw ->
            val i32 = raw.toInt()
            InspectorRow("Int32 / UInt32", "$i32 / ${raw and 0xFFFFFFFFL}")
            InspectorRow("Float32", Float.fromBits(i32).toString())
            InspectorRow("Unix time (s)", formatEpochSeconds(i32.toLong()))
        }
        combine(8)?.let { raw ->
            InspectorRow("Int64", raw.toString())
            InspectorRow("UInt64", raw.toULong().toString())
            InspectorRow("Float64", Double.fromBits(raw).toString())
            InspectorRow("Unix time (ms)", formatEpochMillis(raw))
        }

        if (selectionByteCount > 0) {
            val previewLength = minOf(selectionByteCount, 256L).toInt()
            val previewBuf = ByteArray(previewLength)
            raf.seek(anchorOffset)
            raf.readFully(previewBuf)
            val utf8 = String(previewBuf, Charsets.UTF_8)
            val suffix = if (selectionByteCount > previewLength) "…" else ""
            Spacer(Modifier.height(4.dp))
            Text("UTF-8 미리보기", style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
            Text(
                "\"$utf8$suffix\"",
                style = AppTypography.bodyLarge.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun formatEpochSeconds(seconds: Long): String = try {
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC)) + " UTC"
} catch (e: Exception) {
    "N/A"
}

private fun formatEpochMillis(millis: Long): String = try {
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)) + " UTC"
} catch (e: Exception) {
    "N/A"
}

@Composable
private fun EndiannessChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) AppColors.NeonBlue else AppColors.Border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) AppColors.NeonBlue else AppColors.TextPrimary,
        )
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary),
                modifier = Modifier.width(90.dp),
            )
            Text(
                value,
                style = AppTypography.bodyLarge.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.3f)))
    }
}
