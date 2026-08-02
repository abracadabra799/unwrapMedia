package com.multiviewer.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

private fun copyBytesAsHex(raf: RandomAccessFile, range: LongRange) {
    val length = (range.last - range.first + 1).toInt()
    val buf = ByteArray(length)
    raf.seek(range.first)
    raf.readFully(buf)
    val hexText = buf.joinToString(" ") { "%02X".format(it) }
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(hexText), null)
}

// Resolves a pointer position (in the LazyColumn's own coordinate space, i.e. relative to its
// viewport top-left) to a byte offset. Rows outside the currently visible window can't be hit --
// dragging above/below the visible area clamps to the nearest visible row's first/last byte
// instead of failing, so a drag that overshoots the list still produces a sensible selection.
private fun resolveByteAt(
    position: Offset,
    listState: LazyListState,
    layoutResults: Map<Int, TextLayoutResult>,
    fileLength: Long,
): Long? {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    val item = visible.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
        ?: if (position.y < visible.first().offset) visible.first() else visible.last()
    val rowIndex = item.index
    val rowStart = rowIndex.toLong() * BYTES_PER_ROW
    val rowLength = minOf(BYTES_PER_ROW.toLong(), fileLength - rowStart).toInt()
    val layoutResult = layoutResults[rowIndex] ?: return null
    val clampedX = position.x.coerceIn(0f, layoutResult.size.width.toFloat() - 1f)
    val charIndex = layoutResult.getOffsetForPosition(Offset(clampedX, 0f))
    val byteIndex = charIndexToByteIndex(charIndex, rowLength) ?: return null
    return rowStart + byteIndex
}

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val fileLength = raf.length()
    val rowCount = ((fileLength + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()
    // A plain drag starts a new selection (anchor = press point, end tracks the drag); Shift-click
    // (or shift-drag) extends the existing selection from anchor to the new point instead. Each
    // row reports its own TextLayoutResult into layoutResults as it's composed, so a single
    // top-level gesture can resolve any visible row/column pair to a byte offset without needing
    // per-row pointer input.
    var selectionAnchor by remember(file) { mutableStateOf<Long?>(null) }
    var selectionEnd by remember(file) { mutableStateOf<Long?>(null) }
    val layoutResults = remember(file) { mutableStateMapOf<Int, TextLayoutResult>() }
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
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            // Hex/ASCII grid takes whatever width is left after the value inspector -- wrapped in
            // its own weighted Box since ContextMenuArea's content lambda isn't a RowScope, so
            // Modifier.weight() has to be applied one level up from where the grid itself lives.
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Right-click brings up the same native-look context menu BasicTextField uses for
                // its own copy/paste -- items() is re-evaluated each time the menu opens, so it
                // always reflects whatever's selected (or nothing) at that moment rather than what
                // was selected when this composable was first built.
                ContextMenuArea(
                    items = {
                        selectedRange?.let { range -> listOf(ContextMenuItem("Copy") { copyBytesAsHex(raf, range) }) } ?: emptyList()
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().pointerInput(file) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val isShiftExtend = currentEvent.keyboardModifiers.isShiftPressed && selectionAnchor != null
                                val startOffset = resolveByteAt(down.position, listState, layoutResults, fileLength) ?: return@awaitEachGesture
                                if (isShiftExtend) {
                                    selectionEnd = startOffset
                                } else {
                                    selectionAnchor = startOffset
                                    selectionEnd = startOffset
                                }
                                drag(down.id) { change ->
                                    change.consume()
                                    val offset = resolveByteAt(change.position, listState, layoutResults, fileLength)
                                    if (offset != null) selectionEnd = offset
                                }
                            }
                        },
                    ) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(rowCount) { rowIndex ->
                                val rowStart = rowIndex.toLong() * BYTES_PER_ROW
                                val rowLength = minOf(BYTES_PER_ROW.toLong(), fileLength - rowStart).toInt()
                                val buf = ByteArray(rowLength)
                                raf.seek(rowStart)
                                raf.readFully(buf)

                                Text(
                                    text = buildAnnotatedString {
                                        append("%08X  ".format(rowStart))
                                        for (i in 0 until BYTES_PER_ROW) {
                                            if (i < buf.size) {
                                                val byteOffset = rowStart + i
                                                val hex = "%02X ".format(buf[i])
                                                when {
                                                    selectedRange?.contains(byteOffset) == true ->
                                                        withStyle(SpanStyle(background = AppColors.NeonGreen.copy(alpha = 0.35f))) { append(hex) }
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
                                                    withStyle(SpanStyle(background = AppColors.NeonGreen.copy(alpha = 0.35f))) { append(char) }
                                                highlightRange?.contains(byteOffset) == true ->
                                                    withStyle(SpanStyle(background = AppColors.Highlight)) { append(char) }
                                                else -> append(char)
                                            }
                                        }
                                    },
                                    onTextLayout = { layoutResults[rowIndex] = it },
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(listState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            }
            ValueInspectorPanel(
                raf = raf,
                fileLength = fileLength,
                anchorOffset = selectedRange?.first,
                selectionByteCount = selectedRange?.let { it.last - it.first + 1 } ?: 0L,
            )
        }
    }
}
