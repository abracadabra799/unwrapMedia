package com.multiviewer.ui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

private const val BYTES_PER_ROW = 16

const val MIN_HEX_FONT_SP = 8f
const val MAX_HEX_FONT_SP = 28f
private const val HEX_ZOOM_STEP_FACTOR = 0.08f

fun hexZoomFontSize(currentSp: Float, scrollDeltaY: Float): Float =
    (currentSp * (1f - scrollDeltaY * HEX_ZOOM_STEP_FACTOR)).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP)

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

internal fun formatBytesAsPythonBytes(buf: ByteArray): String =
    "b\"" + buf.joinToString("") { "\\x%02X".format(it) } + "\""

internal fun formatBytesAsBase64(buf: ByteArray): String =
    Base64.getEncoder().encodeToString(buf)

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

internal fun parseOffsetInput(input: String, fileLength: Long): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.endsWith("%")) {
        val pct = trimmed.dropLast(1).toDoubleOrNull() ?: return null
        return ((fileLength * (pct / 100.0)).toLong()).coerceIn(0L, maxOf(0L, fileLength - 1))
    }
    val hexStr = when {
        trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2)
        trimmed.endsWith("h", ignoreCase = true) -> trimmed.dropLast(1)
        trimmed.any { it in 'a'..'f' || it in 'A'..'F' } -> trimmed
        else -> null
    }
    if (hexStr != null) {
        val hexVal = hexStr.toLongOrNull(16)
        if (hexVal != null) return hexVal.coerceIn(0L, maxOf(0L, fileLength - 1))
    }
    val decVal = trimmed.toLongOrNull()
    if (decVal != null) return decVal.coerceIn(0L, maxOf(0L, fileLength - 1))
    return null
}

internal fun parseHexSearchPattern(input: String, isHexMode: Boolean): ByteArray? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (!isHexMode) {
        return trimmed.toByteArray(Charsets.UTF_8)
    }
    val clean = trimmed.replace("0x", "", ignoreCase = true)
        .replace(" ", "")
        .replace(",", "")
        .replace(":", "")
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    return try {
        clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: Exception) {
        null
    }
}

internal fun searchHex(raf: RandomAccessFile, pattern: ByteArray, maxResults: Int = 100): List<Long> {
    if (pattern.isEmpty()) return emptyList()
    val fileLength = raf.length()
    if (fileLength < pattern.size) return emptyList()

    val results = mutableListOf<Long>()
    val chunkSize = 65536
    val overlap = pattern.size - 1
    val buffer = ByteArray(chunkSize + overlap)
    var currentOffset = 0L

    while (currentOffset < fileLength && results.size < maxResults) {
        val readLen = minOf(chunkSize.toLong() + overlap, fileLength - currentOffset).toInt()
        raf.seek(currentOffset)
        raf.readFully(buffer, 0, readLen)

        val searchLimit = if (currentOffset + readLen >= fileLength) readLen - pattern.size + 1 else readLen - overlap
        for (i in 0 until searchLimit) {
            var match = true
            for (j in pattern.indices) {
                if (buffer[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                results.add(currentOffset + i)
                if (results.size >= maxResults) break
            }
        }
        currentOffset += chunkSize
    }
    return results
}

data class DataInspectorValues(
    val offset: Long,
    val uint8: Int,
    val int8: Byte,
    val uint16LE: Int?,
    val uint16BE: Int?,
    val int16LE: Short?,
    val int16BE: Short?,
    val uint32LE: Long?,
    val uint32BE: Long?,
    val int32LE: Int?,
    val int32BE: Int?,
    val int64LE: Long?,
    val int64BE: Long?,
    val float32LE: Float?,
    val float32BE: Float?,
    val double64LE: Double?,
    val double64BE: Double?,
    val binary8: String,
    val asciiChar: String,
)

internal fun readDataInspectorValues(raf: RandomAccessFile, offset: Long, fileLength: Long): DataInspectorValues? {
    if (offset < 0 || offset >= fileLength) return null
    val maxLen = minOf(8L, fileLength - offset).toInt()
    val buf = ByteArray(maxLen)
    raf.seek(offset)
    raf.readFully(buf)

    val u8 = buf[0].toInt() and 0xFF
    val i8 = buf[0]
    val bin8 = (0..7).map { if ((u8 and (1 shl (7 - it))) != 0) '1' else '0' }.joinToString("")
    val ascii = if (u8 in 0x20..0x7E) "'${u8.toChar()}'" else "'.'"

    val i16LE = if (maxLen >= 2) ByteBuffer.wrap(buf, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short else null
    val i16BE = if (maxLen >= 2) ByteBuffer.wrap(buf, 0, 2).order(ByteOrder.BIG_ENDIAN).short else null
    val u16LE = i16LE?.let { it.toInt() and 0xFFFF }
    val u16BE = i16BE?.let { it.toInt() and 0xFFFF }

    val i32LE = if (maxLen >= 4) ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int else null
    val i32BE = if (maxLen >= 4) ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).int else null
    val u32LE = i32LE?.let { it.toLong() and 0xFFFFFFFFL }
    val u32BE = i32BE?.let { it.toLong() and 0xFFFFFFFFL }

    val f32LE = if (maxLen >= 4) ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float else null
    val f32BE = if (maxLen >= 4) ByteBuffer.wrap(buf, 0, 4).order(ByteOrder.BIG_ENDIAN).float else null

    val i64LE = if (maxLen >= 8) ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long else null
    val i64BE = if (maxLen >= 8) ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).long else null
    val d64LE = if (maxLen >= 8) ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.LITTLE_ENDIAN).double else null
    val d64BE = if (maxLen >= 8) ByteBuffer.wrap(buf, 0, 8).order(ByteOrder.BIG_ENDIAN).double else null

    return DataInspectorValues(
        offset = offset,
        uint8 = u8,
        int8 = i8,
        uint16LE = u16LE,
        uint16BE = u16BE,
        int16LE = i16LE,
        int16BE = i16BE,
        uint32LE = u32LE,
        uint32BE = u32BE,
        int32LE = i32LE,
        int32BE = i32BE,
        int64LE = i64LE,
        int64BE = i64BE,
        float32LE = f32LE,
        float32BE = f32BE,
        double64LE = d64LE,
        double64BE = d64BE,
        binary8 = bin8,
        asciiChar = ascii,
    )
}

internal fun readRangeBytes(raf: RandomAccessFile, range: LongRange, maxBytes: Int = 5 * 1024 * 1024): ByteArray {
    val totalLen = maxOf(0L, range.last - range.first + 1)
    val readLen = minOf(maxBytes.toLong(), totalLen).toInt()
    val buf = ByteArray(readLen)
    raf.seek(range.first)
    raf.readFully(buf)
    return buf
}

internal fun copyBytesAsText(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsText(buf))
}

internal fun copyBytesAsPrintableAscii(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsPrintableAscii(buf))
}

internal fun copyBytesAsHex(raf: RandomAccessFile, range: LongRange, multiLine: Boolean = true) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsHex(buf, multiLine))
}

internal fun copyBytesAsContinuousHex(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsContinuousHex(buf))
}

internal fun copyBytesAsFormattedDump(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatHexDump(buf, range.first))
}

internal fun copyBytesAsCodeArray(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsCodeArray(buf))
}

internal fun copyBytesAsPythonBytes(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsPythonBytes(buf))
}

internal fun copyBytesAsBase64(raf: RandomAccessFile, range: LongRange) {
    val buf = readRangeBytes(raf, range)
    com.multiviewer.util.ClipboardUtil.copyToClipboard(formatBytesAsBase64(buf))
}

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
    val coroutineScope = rememberCoroutineScope()

    var selectionAnchor by remember(file) { mutableStateOf<Long?>(null) }
    var selectionEnd by remember(file) { mutableStateOf<Long?>(null) }
    var fontSizeSp by remember(file) { mutableStateOf(12f) }
    val layoutResults = remember(file) { mutableStateMapOf<Int, TextLayoutResult>() }
    val focusRequester = remember { FocusRequester() }

    // 010 Editor / UltraEdit Features State
    var showInspector by remember(file) { mutableStateOf(false) }
    var showFindBar by remember(file) { mutableStateOf(false) }
    var showGoToBar by remember(file) { mutableStateOf(false) }

    // Find state
    var searchQuery by remember(file) { mutableStateOf("") }
    var isHexSearchMode by remember(file) { mutableStateOf(false) }
    var searchMatches by remember(file) { mutableStateOf<List<Long>>(emptyList()) }
    var currentMatchIndex by remember(file) { mutableStateOf(0) }
    var activeSearchPatternLength by remember(file) { mutableStateOf(0) }

    // Go to state
    var goToInput by remember(file) { mutableStateOf("") }

    val findFocusRequester = remember { FocusRequester() }
    val goToFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showFindBar) {
        if (showFindBar) {
            findFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(showGoToBar) {
        if (showGoToBar) {
            goToFocusRequester.requestFocus()
        }
    }

    val selectedRange = if (selectionAnchor != null && selectionEnd != null) {
        minOf(selectionAnchor!!, selectionEnd!!)..maxOf(selectionAnchor!!, selectionEnd!!)
    } else {
        null
    }

    val activeCopyRange = selectedRange ?: highlightRange
    var copyToastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copyToastMessage) {
        if (copyToastMessage != null) {
            kotlinx.coroutines.delay(2000)
            copyToastMessage = null
        }
    }

    val activeCursorOffset = selectionEnd ?: selectionAnchor ?: highlightRange?.first ?: 0L
    val inspectorValues = remember(activeCursorOffset, fileLength, showInspector) {
        if (showInspector && fileLength > 0) {
            readDataInspectorValues(raf, activeCursorOffset, fileLength)
        } else {
            null
        }
    }

    fun scrollToOffset(offset: Long) {
        val rowIndex = (offset / BYTES_PER_ROW).toInt().coerceIn(0, maxOf(0, rowCount - 1))
        coroutineScope.launch {
            listState.animateScrollToItem(rowIndex)
        }
    }

    fun performSearch() {
        val pattern = parseHexSearchPattern(searchQuery, isHexSearchMode)
        if (pattern == null || pattern.isEmpty()) {
            searchMatches = emptyList()
            activeSearchPatternLength = 0
            return
        }
        activeSearchPatternLength = pattern.size
        coroutineScope.launch {
            val results = withContext(Dispatchers.IO) {
                searchHex(raf, pattern)
            }
            searchMatches = results
            currentMatchIndex = 0
            if (results.isNotEmpty()) {
                val matchOffset = results[0]
                selectionAnchor = matchOffset
                selectionEnd = matchOffset + pattern.size - 1
                scrollToOffset(matchOffset)
            }
        }
    }

    fun nextMatch() {
        if (searchMatches.isEmpty()) return
        val nextIdx = (currentMatchIndex + 1) % searchMatches.size
        currentMatchIndex = nextIdx
        val matchOffset = searchMatches[nextIdx]
        selectionAnchor = matchOffset
        selectionEnd = matchOffset + activeSearchPatternLength - 1
        scrollToOffset(matchOffset)
    }

    fun prevMatch() {
        if (searchMatches.isEmpty()) return
        val prevIdx = if (currentMatchIndex - 1 < 0) searchMatches.size - 1 else currentMatchIndex - 1
        currentMatchIndex = prevIdx
        val matchOffset = searchMatches[prevIdx]
        selectionAnchor = matchOffset
        selectionEnd = matchOffset + activeSearchPatternLength - 1
        scrollToOffset(matchOffset)
    }

    fun clearSelection() {
        selectionAnchor = null
        selectionEnd = null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar with 010 Editor / UltraEdit Action Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AppColors.Surface,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            showFindBar = !showFindBar
                            if (showFindBar) showGoToBar = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                        colors = if (showFindBar) ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.NeonBlue.copy(alpha = 0.3f)) else ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text("🔍 Find (Ctrl+F)", fontSize = 10.sp)
                    }

                    FilledTonalButton(
                        onClick = {
                            showGoToBar = !showGoToBar
                            if (showGoToBar) showFindBar = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                        colors = if (showGoToBar) ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.NeonBlue.copy(alpha = 0.3f)) else ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text("📍 Go to (Ctrl+G)", fontSize = 10.sp)
                    }

                    FilledTonalButton(
                        onClick = { showInspector = !showInspector },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp),
                        colors = if (showInspector) ButtonDefaults.filledTonalButtonColors(containerColor = AppColors.NeonGreen.copy(alpha = 0.3f)) else ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text("📊 Data Inspector (Ctrl+I)", fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Font Zoom Controls
                    Text("Font:", fontSize = 10.sp, color = AppColors.TextSecondary)
                    IconButton(
                        onClick = { fontSizeSp = (fontSizeSp - 1f).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Text("-", fontSize = 12.sp, color = AppColors.TextPrimary)
                    }
                    Text("%.0fsp".format(fontSizeSp), fontSize = 10.sp, color = AppColors.NeonBlue)
                    IconButton(
                        onClick = { fontSizeSp = (fontSizeSp + 1f).coerceIn(MIN_HEX_FONT_SP, MAX_HEX_FONT_SP) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Text("+", fontSize = 12.sp, color = AppColors.TextPrimary)
                    }
                }

                // Expandable Search Bar (Ctrl+F)
                if (showFindBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(Color(0xFF252525), RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.NeonBlue.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .clickable {
                                    isHexSearchMode = !isHexSearchMode
                                    performSearch()
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                if (isHexSearchMode) "HEX" else "TEXT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isHexSearchMode) AppColors.NeonYellow else AppColors.NeonBlue,
                            )
                        }

                        androidx.compose.foundation.text.BasicTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                performSearch()
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.NeonBlue),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .focusRequester(findFocusRequester)
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF444444), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.key) {
                                            Key.Enter -> {
                                                if (keyEvent.isShiftPressed) prevMatch() else nextMatch()
                                                true
                                            }
                                            Key.Escape -> {
                                                showFindBar = false
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            if (isHexSearchMode) "Hex sequence (e.g. FF D8 FF E0)" else "Text string (e.g. ftyp, moov)",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )

                        FilledTonalButton(
                            onClick = { prevMatch() },
                            enabled = searchMatches.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("◀ Prev", fontSize = 10.sp)
                        }

                        FilledTonalButton(
                            onClick = { nextMatch() },
                            enabled = searchMatches.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("Next ▶", fontSize = 10.sp)
                        }

                        if (searchMatches.isNotEmpty()) {
                            Text(
                                "${currentMatchIndex + 1} / ${searchMatches.size}",
                                fontSize = 10.sp,
                                color = AppColors.NeonGreen,
                                fontWeight = FontWeight.Bold,
                            )
                        } else if (searchQuery.isNotEmpty()) {
                            Text("No match", fontSize = 10.sp, color = AppColors.NeonRed)
                        }

                        IconButton(onClick = { showFindBar = false; searchMatches = emptyList() }, modifier = Modifier.size(20.dp)) {
                            Text("✕", fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                    }
                }

                // Expandable Go To Offset Bar (Ctrl+G)
                if (showGoToBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(Color(0xFF252525), RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.NeonBlue.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Go to Offset:", fontSize = 11.sp, color = AppColors.TextPrimary)
                        androidx.compose.foundation.text.BasicTextField(
                            value = goToInput,
                            onValueChange = { goToInput = it },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.NeonBlue),
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .focusRequester(goToFocusRequester)
                                .background(Color(0xFF1A1A1A), RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF444444), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown) {
                                        when (keyEvent.key) {
                                            Key.Enter -> {
                                                val offset = parseOffsetInput(goToInput, fileLength)
                                                if (offset != null) {
                                                    selectionAnchor = offset
                                                    selectionEnd = offset
                                                    scrollToOffset(offset)
                                                }
                                                true
                                            }
                                            Key.Escape -> {
                                                showGoToBar = false
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                },
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (goToInput.isEmpty()) {
                                        Text("e.g. 0x1A40 or 4096 or 50%", fontSize = 11.sp, color = Color.Gray)
                                    }
                                    innerTextField()
                                }
                            },
                        )
                        FilledTonalButton(
                            onClick = {
                                val offset = parseOffsetInput(goToInput, fileLength)
                                if (offset != null) {
                                    selectionAnchor = offset
                                    selectionEnd = offset
                                    scrollToOffset(offset)
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("Go", fontSize = 10.sp)
                        }
                        IconButton(onClick = { showGoToBar = false }, modifier = Modifier.size(20.dp)) {
                            Text("✕", fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                    }
                }
            }
        }

        // Selection / Box Range / Copy Info Bar
        activeCopyRange?.let { range ->
            val isManualSelection = selectedRange != null
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isManualSelection) Color(0xFF1E281E) else Color(0xFF16253A))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val byteCount = range.last - range.first + 1
                val labelPrefix = if (isManualSelection) "📍 선택 영역 (Selection)" else "📦 선택된 박스/마커 (Box/Marker)"
                val text = if (byteCount == 1L) {
                    raf.seek(range.first)
                    val value = raf.read()
                    val asciiSuffix = if (value in 0x20..0x7E) "  '${value.toChar()}'" else ""
                    "$labelPrefix: 0x${range.first.toString(16).uppercase()} · Value: 0x${"%02X".format(value)} ($value)$asciiSuffix"
                } else {
                    "$labelPrefix: 0x${range.first.toString(16).uppercase()} - 0x${range.last.toString(16).uppercase()} ($byteCount bytes)"
                }
                Text(
                    text,
                    style = AppTypography.labelLarge.copy(
                        color = if (isManualSelection) AppColors.NeonGreen else AppColors.NeonBlue,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                if (isManualSelection) {
                    Text(
                        "(ESC: 선택 해제)",
                        fontSize = 10.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }

                // Copy Toast Feedback Indicator
                copyToastMessage?.let { toast ->
                    Text(
                        "✓ $toast",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.NeonGreen,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }

                // Quick copy buttons
                FilledTonalButton(
                    onClick = {
                        copyBytesAsFormattedDump(raf, range)
                        copyToastMessage = "덤프 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 2.dp),
                ) {
                    Text("Dump", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyBytesAsHex(raf, range, multiLine = true)
                        copyToastMessage = "Hex 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 2.dp),
                ) {
                    Text("Hex", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyBytesAsText(raf, range)
                        copyToastMessage = "Text 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 2.dp),
                ) {
                    Text("Text", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyBytesAsBase64(raf, range)
                        copyToastMessage = "Base64 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 2.dp),
                ) {
                    Text("Base64", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyBytesAsCodeArray(raf, range)
                        copyToastMessage = "C-Array 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp).padding(end = 2.dp),
                ) {
                    Text("C-Array", fontSize = 10.sp)
                }
                FilledTonalButton(
                    onClick = {
                        copyBytesAsPythonBytes(raf, range)
                        copyToastMessage = "Py-Bytes 복사됨"
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("Py-Bytes", fontSize = 10.sp)
                }
            }
        }

        // 010 Editor Style Column Header Guide
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF141414),
        ) {
            Text(
                text = "Offset (h)   00 01 02 03 04 05 06 07  08 09 0A 0B 0C 0D 0E 0F  Decoded text",
                style = AppTypography.bodyLarge.copy(
                    fontSize = fontSizeSp.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextSecondary,
                    letterSpacing = 0.2.sp,
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        // Hex/ASCII Grid
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            ContextMenuArea(
                items = {
                    activeCopyRange?.let { range ->
                        listOf(
                            ContextMenuItem("📑 Copy as Formatted Dump (Offset + Hex + ASCII) [Ctrl+C]") {
                                copyBytesAsFormattedDump(raf, range)
                                copyToastMessage = "덤프 복사됨"
                            },
                            ContextMenuItem("🔢 Copy as Hex (Space-separated, 16B/line) [Ctrl+Shift+C]") {
                                copyBytesAsHex(raf, range, multiLine = true)
                                copyToastMessage = "Hex 복사됨"
                            },
                            ContextMenuItem("📋 Copy as Text (Raw UTF-8 / String)") {
                                copyBytesAsText(raf, range)
                                copyToastMessage = "Text 복사됨"
                            },
                            ContextMenuItem("🔤 Copy as Printable ASCII") {
                                copyBytesAsPrintableAscii(raf, range)
                                copyToastMessage = "ASCII 복사됨"
                            },
                            ContextMenuItem("🔗 Copy as Hex Stream (Continuous)") {
                                copyBytesAsContinuousHex(raf, range)
                                copyToastMessage = "Continuous Hex 복사됨"
                            },
                            ContextMenuItem("📦 Copy as Base64 String") {
                                copyBytesAsBase64(raf, range)
                                copyToastMessage = "Base64 복사됨"
                            },
                            ContextMenuItem("🐍 Copy as Python Bytes (b'\\x00...')") {
                                copyBytesAsPythonBytes(raf, range)
                                copyToastMessage = "Python Bytes 복사됨"
                            },
                            ContextMenuItem("💻 Copy as C/C++ Byte Array (0xXX, ...)") {
                                copyBytesAsCodeArray(raf, range)
                                copyToastMessage = "C-Array 복사됨"
                            },
                        ) + (if (selectedRange != null) listOf(
                            ContextMenuItem("❌ Clear Selection (ESC)") { clearSelection() }
                        ) else emptyList())
                    } ?: listOf(
                        ContextMenuItem("🔍 Find in Hex (Ctrl+F)") { showFindBar = true },
                        ContextMenuItem("📍 Go to Offset (Ctrl+G)") { showGoToBar = true },
                        ContextMenuItem("📊 Toggle Data Inspector (Ctrl+I)") { showInspector = !showInspector },
                    )
                },
            ) {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when {
                                    // ESC key clears selection & closes overlays
                                    keyEvent.key == Key.Escape -> {
                                        clearSelection()
                                        showFindBar = false
                                        showGoToBar = false
                                        true
                                    }
                                    // Ctrl+F / Cmd+F opens Find
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.F -> {
                                        showFindBar = !showFindBar
                                        if (showFindBar) showGoToBar = false
                                        true
                                    }
                                    // Ctrl+G / Cmd+G opens Go To
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.G -> {
                                        showGoToBar = !showGoToBar
                                        if (showGoToBar) showFindBar = false
                                        true
                                    }
                                    // Ctrl+I / Cmd+I toggles Data Inspector
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.key == Key.I -> {
                                        showInspector = !showInspector
                                        true
                                    }
                                    // Ctrl+C / Cmd+C copies as Formatted Dump
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && !keyEvent.isShiftPressed && !keyEvent.isAltPressed && keyEvent.key == Key.C -> {
                                        activeCopyRange?.let { range ->
                                            copyBytesAsFormattedDump(raf, range)
                                            copyToastMessage = "덤프 복사됨"
                                            true
                                        } ?: false
                                    }
                                    // Ctrl+Shift+C / Cmd+Shift+C copies as Hex
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.isShiftPressed && keyEvent.key == Key.C -> {
                                        activeCopyRange?.let { range ->
                                            copyBytesAsHex(raf, range, multiLine = true)
                                            copyToastMessage = "Hex 복사됨"
                                            true
                                        } ?: false
                                    }
                                    // Ctrl+Alt+C / Cmd+Alt+C copies as Text
                                    (keyEvent.isCtrlPressed || keyEvent.isMetaPressed) && keyEvent.isAltPressed && keyEvent.key == Key.C -> {
                                        activeCopyRange?.let { range ->
                                            copyBytesAsText(raf, range)
                                            copyToastMessage = "Text 복사됨"
                                            true
                                        } ?: false
                                    }
                                    else -> false
                                }
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
                                style = AppTypography.bodyLarge.copy(
                                    fontSize = fontSizeSp.sp,
                                    lineHeight = (fontSizeSp * (16f / 12f)).sp,
                                    letterSpacing = 0.2.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                softWrap = false,
                                text = buildAnnotatedString {
                                    append("%08X  ".format(rowStart))
                                    for (i in 0 until BYTES_PER_ROW) {
                                        if (i < buf.size) {
                                            val byteOffset = rowStart + i
                                            val hex = "%02X ".format(buf[i])
                                            val isSearchMatch = searchMatches.any { it <= byteOffset && byteOffset < it + activeSearchPatternLength }
                                            when {
                                                selectedRange?.contains(byteOffset) == true ->
                                                    withStyle(SpanStyle(background = AppColors.NeonGreen.copy(alpha = 0.45f), color = Color.White)) { append(hex) }
                                                isSearchMatch ->
                                                    withStyle(SpanStyle(background = AppColors.NeonYellow.copy(alpha = 0.45f), color = Color.White)) { append(hex) }
                                                highlightRange?.contains(byteOffset) == true ->
                                                    withStyle(SpanStyle(background = AppColors.Highlight)) { append(hex) }
                                                else -> append(hex)
                                            }
                                        } else {
                                            append("   ")
                                        }
                                        if (i == 7) append(" ")
                                    }
                                    append(" ")
                                    for (i in buf.indices) {
                                        val byteOffset = rowStart + i
                                        val byteValue = buf[i].toInt() and 0xFF
                                        val char = if (byteValue in 0x20..0x7E) byteValue.toChar() else '.'
                                        val isSearchMatch = searchMatches.any { it <= byteOffset && byteOffset < it + activeSearchPatternLength }
                                        when {
                                            selectedRange?.contains(byteOffset) == true ->
                                                withStyle(SpanStyle(background = AppColors.NeonGreen.copy(alpha = 0.45f), color = Color.White)) { append(char) }
                                            isSearchMatch ->
                                                withStyle(SpanStyle(background = AppColors.NeonYellow.copy(alpha = 0.45f), color = Color.White)) { append(char) }
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

        // 010 Editor Style Real-Time Data Inspector Panel
        if (showInspector && inspectorValues != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF181818),
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AppColors.Border)
                        .padding(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "📊 010 Editor Data Inspector  (Offset: 0x${inspectorValues.offset.toString(16).uppercase()} / ${inspectorValues.offset})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonGreen,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showInspector = false }, modifier = Modifier.size(18.dp)) {
                            Text("✕", fontSize = 10.sp, color = AppColors.TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Column 1: 8-bit & 16-bit
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InspectorRow("uint8 / int8", "${inspectorValues.uint8} (0x${"%02X".format(inspectorValues.uint8)}) / ${inspectorValues.int8}")
                            InspectorRow("uint16 (LE / BE)", "${inspectorValues.uint16LE ?: "-"} (0x${inspectorValues.uint16LE?.toString(16)?.uppercase() ?: "-"}) / ${inspectorValues.uint16BE ?: "-"}")
                            InspectorRow("int16 (LE / BE)", "${inspectorValues.int16LE ?: "-"} / ${inspectorValues.int16BE ?: "-"}")
                            InspectorRow("Binary (8-bit)", inspectorValues.binary8)
                        }

                        // Column 2: 32-bit & Floats
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InspectorRow("uint32 (LE / BE)", "${inspectorValues.uint32LE ?: "-"} (0x${inspectorValues.uint32LE?.toString(16)?.uppercase() ?: "-"}) / ${inspectorValues.uint32BE ?: "-"}")
                            InspectorRow("int32 (LE / BE)", "${inspectorValues.int32LE ?: "-"} / ${inspectorValues.int32BE ?: "-"}")
                            InspectorRow("float32 (LE / BE)", "${inspectorValues.float32LE ?: "-"} / ${inspectorValues.float32BE ?: "-"}")
                            InspectorRow("ASCII / Char", inspectorValues.asciiChar)
                        }

                        // Column 3: 64-bit & Double
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            InspectorRow("int64 (LE)", "${inspectorValues.int64LE ?: "-"}")
                            InspectorRow("int64 (BE)", "${inspectorValues.int64BE ?: "-"}")
                            InspectorRow("double64 (LE)", "${inspectorValues.double64LE ?: "-"}")
                            InspectorRow("double64 (BE)", "${inspectorValues.double64BE ?: "-"}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectorRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            fontSize = 10.sp,
            color = AppColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

