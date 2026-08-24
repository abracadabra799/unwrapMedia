package com.multiviewer.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.onPointerEvent
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

const val MIN_HEX_FONT_SP = 8f
const val MAX_HEX_FONT_SP = 28f
private const val HEX_ZOOM_STEP_FACTOR = 0.08f // matches PixelInspectorPreview's ZOOM_STEP_FACTOR

// Cmd/Ctrl+scroll font-size zoom for the hex/ASCII grid below. Scroll-up (negative delta) zooms
// in, matching PixelInspectorPreview's own scroll-up-zooms-in convention.
fun hexZoomFontSize(currentSp: Float, scrollDeltaY: Float): Float =
    (currentSp * (1f - scrollDeltaY * HEX_ZOOM_STEP_FACTOR)).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP)

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

internal fun formatBytesAsText(buf: ByteArray): String =
    String(buf, Charsets.UTF_8)

internal fun formatBytesAsPrintableAscii(buf: ByteArray): String =
    buf.map { b ->
        val v = b.toInt() and 0xFF
        if (v in 0x20..0x7E) v.toChar() else '.'
    }.joinToString("")

internal fun formatBytesAsHex(buf: ByteArray, multiLine: Boolean = false): String {
    return if (multiLine && buf.size > 16) {
        buf.toList().chunked(16).joinToString("\n") { row ->
            row.joinToString(" ") { "%02X".format(it) }
        }
    } else {
        buf.joinToString(" ") { "%02X".format(it) }
    }
}

internal fun formatBytesAsContinuousHex(buf: ByteArray): String =
    buf.joinToString("") { "%02X".format(it) }

internal fun formatBytesAsCodeArray(buf: ByteArray): String =
    buf.joinToString(", ") { "0x%02X".format(it) }

internal fun formatHexDump(buf: ByteArray, startOffset: Long): String {
    val sb = StringBuilder()
    var offset = startOffset
    var i = 0
    while (i < buf.size) {
        val chunkLen = minOf(16, buf.size - i)
        sb.append("%08X  ".format(offset))
        for (j in 0 until 16) {
            if (j < chunkLen) {
                sb.append("%02X ".format(buf[i + j]))
            } else {
                sb.append("   ")
            }
            if (j == 7) sb.append(" ")
        }
        sb.append(" |")
        for (j in 0 until chunkLen) {
            val b = buf[i + j].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        sb.append("|\n")
        i += chunkLen
        offset += chunkLen
    }
    return sb.toString().trimEnd()
}

private fun readRangeBytes(raf: RandomAccessFile, range: LongRange): ByteArray {
    val length = (range.last - range.first + 1).toInt()
    val buf = ByteArray(length)
    raf.seek(range.first)
    raf.readFully(buf)
    return buf
}

private fun copyBytesAsText(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsText(buf))
}

private fun copyBytesAsPrintableAscii(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsPrintableAscii(buf))
}

private fun copyBytesAsHex(raf: RandomAccessFile, range: LongRange, multiLine: Boolean = true) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsHex(buf, multiLine))
}

private fun copyBytesAsContinuousHex(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsContinuousHex(buf))
}

private fun copyBytesAsFormattedDump(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatHexDump(buf, range.first))
}

private fun copyBytesAsCodeArray(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsCodeArray(buf))
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

@OptIn(ExperimentalComposeUiApi::class)
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
    var fontSizeSp by remember(file) { mutableStateOf(12f) }
    val layoutResults = remember(file) { mutableStateMapOf<Int, TextLayoutResult>() }
    val focusRequester = remember { FocusRequester() }
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
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )

                // Quick copy buttons
                FilledTonalButton(
                    onClick = { copyBytesAsText(raf, range) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 4.dp),
                ) {
                    Text("Copy Text", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = { copyBytesAsHex(raf, range, multiLine = true) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 4.dp),
                ) {
                    Text("Copy Hex", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = { copyBytesAsFormattedDump(raf, range) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("Copy Dump", fontSize = 10.sp)
                }
            }
        }
        // Hex/ASCII grid.
        Box(modifier = Modifier.fillMaxSize()) {
            // Right-click brings up the context menu with rich copy options
            ContextMenuArea(
                items = {
                    selectedRange?.let { range ->
                        listOf(
                            ContextMenuItem("📋 Copy as Text (Raw UTF-8 / String)") { copyBytesAsText(raf, range) },
                            ContextMenuItem("🔤 Copy as Printable ASCII") { copyBytesAsPrintableAscii(raf, range) },
                            ContextMenuItem("🔢 Copy as Hex (Space-separated)") { copyBytesAsHex(raf, range, multiLine = true) },
                            ContextMenuItem("🔗 Copy as Hex Stream (Continuous)") { copyBytesAsContinuousHex(raf, range) },
                            ContextMenuItem("📑 Copy as Formatted Dump (Offset + Hex + ASCII)") { copyBytesAsFormattedDump(raf, range) },
                            ContextMenuItem("💻 Copy as Byte Array (0xXX, 0xXX...)") { copyBytesAsCodeArray(raf, range) },
                        )
                    } ?: emptyList()
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown &&
                                (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) &&
                                keyEvent.key == Key.C
                            ) {
                                selectedRange?.let { range ->
                                    copyBytesAsText(raf, range)
                                    true
                                } ?: false
                            } else {
                                false
                            }
                        }
                        .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                            val modifiers = event.keyboardModifiers
                            if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) return@onPointerEvent
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                            fontSizeSp = hexZoomFontSize(fontSizeSp, delta)
                            event.changes.forEach { it.consume() }
                        }
                        .pointerInput(file) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                focusRequester.requestFocus()
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
                                // Smaller than the app's default 14sp body text -- at the
                                // default 12sp a row ("%08X  " + 16 "XX " hex groups + 16
                                // ASCII chars, ~75 monospace chars) could exceed the panel's
                                // available width and wrap, breaking the hex/ASCII column
                                // alignment this grid depends on. softWrap = false is a second
                                // guard against the same failure mode if the panel is ever
                                // narrower than a row -- still true at any zoom level, since a
                                // larger fontSizeSp only makes a row wider, never narrower.
                                // lineHeight keeps the same 4:3 ratio to fontSizeSp that the
                                // fixed 12sp/16sp pair had, at every zoom level.
                                style = AppTypography.bodyLarge.copy(
                                    fontSize = fontSizeSp.sp,
                                    lineHeight = (fontSizeSp * (16f / 12f)).sp,
                                    letterSpacing = 0.2.sp,
                                ),
                                softWrap = false,
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
    }
}
