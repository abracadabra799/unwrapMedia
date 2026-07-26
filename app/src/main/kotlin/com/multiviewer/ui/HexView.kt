package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.RandomAccessFile

private const val BYTES_PER_ROW = 16

// Row text layout: "%08X  " (8 hex digits + 2 spaces) then 16 * "XX " hex byte groups, then one
// more space, then up to 16 ASCII characters. Used to translate a click's character offset (from
// TextLayoutResult.getOffsetForPosition) back to which byte in the row was clicked.
private const val OFFSET_PREFIX_CHARS = 10
private const val HEX_SECTION_CHARS = BYTES_PER_ROW * 3
private const val ASCII_SECTION_START = OFFSET_PREFIX_CHARS + HEX_SECTION_CHARS + 1

private fun charIndexToByteIndex(charIndex: Int, rowByteCount: Int): Int? {
    val hexEnd = OFFSET_PREFIX_CHARS + HEX_SECTION_CHARS
    return when {
        charIndex in OFFSET_PREFIX_CHARS until hexEnd ->
            ((charIndex - OFFSET_PREFIX_CHARS) / 3).takeIf { it < rowByteCount }
        charIndex >= ASCII_SECTION_START ->
            (charIndex - ASCII_SECTION_START).takeIf { it < rowByteCount }
        else -> null
    }
}

private val SelectedByteHighlight = Color(0xFF39FF14).copy(alpha = 0.35f)

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val rowCount = ((raf.length() + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()
    var selectedOffset by remember(file) { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        selectedOffset?.let { offset ->
            raf.seek(offset)
            val value = raf.read()
            val asciiSuffix = if (value in 0x20..0x7E) "  '${value.toChar()}'" else ""
            Text(
                "Selected byte -- offset $offset (0x${offset.toString(16).uppercase()}), value 0x${"%02X".format(value)} ($value)$asciiSuffix",
                style = AppTypography.labelLarge.copy(color = AppColors.NeonGreen),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rowCount) { rowIndex ->
                val rowStart = rowIndex.toLong() * BYTES_PER_ROW
                val rowLength = minOf(BYTES_PER_ROW.toLong(), raf.length() - rowStart).toInt()
                val buf = ByteArray(rowLength)
                raf.seek(rowStart)
                raf.readFully(buf)

                var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                Text(
                    text = buildAnnotatedString {
                        append("%08X  ".format(rowStart))
                        for (i in 0 until BYTES_PER_ROW) {
                            if (i < buf.size) {
                                val byteOffset = rowStart + i
                                val hex = "%02X ".format(buf[i])
                                when {
                                    selectedOffset == byteOffset ->
                                        withStyle(SpanStyle(background = SelectedByteHighlight)) { append(hex) }
                                    highlightRange?.contains(byteOffset) == true ->
                                        withStyle(SpanStyle(background = AppColors.Highlight)) { append(hex) }
                                    else -> append(hex)
                                }
                            } else {
                                append("   ")
                            }
                        }
                        append(" ")
                        for (i in buf.indices) {
                            val byteOffset = rowStart + i
                            val byteValue = buf[i].toInt() and 0xFF
                            val char = if (byteValue in 0x20..0x7E) byteValue.toChar() else '.'
                            when {
                                selectedOffset == byteOffset ->
                                    withStyle(SpanStyle(background = SelectedByteHighlight)) { append(char) }
                                highlightRange?.contains(byteOffset) == true ->
                                    withStyle(SpanStyle(background = AppColors.Highlight)) { append(char) }
                                else -> append(char)
                            }
                        }
                    },
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.pointerInput(rowStart, buf.size) {
                        detectTapGestures { tapPosition ->
                            val charIndex = layoutResult?.getOffsetForPosition(tapPosition) ?: return@detectTapGestures
                            val byteIndex = charIndexToByteIndex(charIndex, buf.size) ?: return@detectTapGestures
                            selectedOffset = rowStart + byteIndex
                        }
                    },
                )
            }
        }
    }
}
