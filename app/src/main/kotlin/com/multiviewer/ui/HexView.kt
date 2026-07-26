package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
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

private fun copyBytesAsHex(raf: RandomAccessFile, range: LongRange) {
    val length = (range.last - range.first + 1).toInt()
    val buf = ByteArray(length)
    raf.seek(range.first)
    raf.readFully(buf)
    val hexText = buf.joinToString(" ") { "%02X".format(it) }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(hexText), null)
}

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val rowCount = ((raf.length() + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()
    // A click without Shift starts a new single-byte selection (anchor == end); Shift-click
    // extends the existing selection from anchor to the newly clicked byte. Cross-row ranges work
    // naturally since both clicks are resolved independently by their own row's layout -- no need
    // to track pointer position across row boundaries the way a continuous drag would.
    var selectionAnchor by remember(file) { mutableStateOf<Long?>(null) }
    var selectionEnd by remember(file) { mutableStateOf<Long?>(null) }
    val selectedRange = if (selectionAnchor != null && selectionEnd != null) {
        minOf(selectionAnchor!!, selectionEnd!!)..maxOf(selectionAnchor!!, selectionEnd!!)
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        selectedRange?.let { range ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val byteCount = range.last - range.first + 1
                val text = if (byteCount == 1L) {
                    raf.seek(range.first)
                    val value = raf.read()
                    val asciiSuffix = if (value in 0x20..0x7E) "  '${value.toChar()}'" else ""
                    "Selected byte -- offset ${range.first} (0x${range.first.toString(16).uppercase()}), value 0x${"%02X".format(value)} ($value)$asciiSuffix"
                } else {
                    "Selected range -- offset ${range.first} to ${range.last} (0x${range.first.toString(16).uppercase()}-0x${range.last.toString(16).uppercase()}), $byteCount bytes"
                }
                Text(
                    text,
                    style = AppTypography.labelLarge.copy(color = AppColors.NeonGreen),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Button(onClick = { copyBytesAsHex(raf, range) }) {
                    Text("Copy Hex", fontSize = 11.sp)
                }
            }
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
                                    selectedRange?.contains(byteOffset) == true ->
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
                                selectedRange?.contains(byteOffset) == true ->
                                    withStyle(SpanStyle(background = SelectedByteHighlight)) { append(char) }
                                highlightRange?.contains(byteOffset) == true ->
                                    withStyle(SpanStyle(background = AppColors.Highlight)) { append(char) }
                                else -> append(char)
                            }
                        }
                    },
                    onTextLayout = { layoutResult = it },
                    modifier = Modifier.pointerInput(rowStart, buf.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(pass = PointerEventPass.Main)
                            val isShiftExtend = currentEvent.keyboardModifiers.isShiftPressed && selectionAnchor != null
                            val charIndex = layoutResult?.getOffsetForPosition(down.position) ?: return@awaitEachGesture
                            val byteIndex = charIndexToByteIndex(charIndex, buf.size) ?: return@awaitEachGesture
                            val clickedOffset = rowStart + byteIndex
                            if (isShiftExtend) {
                                selectionEnd = clickedOffset
                            } else {
                                selectionAnchor = clickedOffset
                                selectionEnd = clickedOffset
                            }
                        }
                    },
                )
            }
        }
    }
}
