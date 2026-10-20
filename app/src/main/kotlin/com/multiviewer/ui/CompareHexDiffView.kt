package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

// -------------------------------------------------------------------------------------------------
// 4. Hex Diff View
// -------------------------------------------------------------------------------------------------

data class HexDiffChunk(
    val index: Int, // 1-based index (1, 2, 3...)
    val startRow: Int,
    val endRow: Int,
    val startOffset: Long,
    val endOffset: Long,
    val sizeBytes: Long,
)

internal fun scanHexDifferences(fileA: File, fileB: File): List<HexDiffChunk> {
    val lenA = fileA.length()
    val lenB = fileB.length()
    val maxLen = maxOf(lenA, lenB)
    if (maxLen == 0L) return emptyList()

    val diffChunks = mutableListOf<HexDiffChunk>()
    val bufferSize = 65536 // 64KB chunks
    val bufA = ByteArray(bufferSize)
    val bufB = ByteArray(bufferSize)

    var currentChunkStartRow = -1
    var currentChunkEndRow = -1
    var chunkIndex = 1

    fun commitCurrentChunk() {
        if (currentChunkStartRow >= 0) {
            val startOff = currentChunkStartRow.toLong() * 16L
            val endOff = minOf((currentChunkEndRow.toLong() + 1L) * 16L, maxLen)
            diffChunks.add(
                HexDiffChunk(
                    index = chunkIndex++,
                    startRow = currentChunkStartRow,
                    endRow = currentChunkEndRow,
                    startOffset = startOff,
                    endOffset = endOff,
                    sizeBytes = endOff - startOff,
                )
            )
            currentChunkStartRow = -1
            currentChunkEndRow = -1
        }
    }

    try {
        fileA.inputStream().buffered(bufferSize).use { streamA ->
            fileB.inputStream().buffered(bufferSize).use { streamB ->
                var globalOffset = 0L

                while (globalOffset < maxLen) {
                    val readA = if (globalOffset < lenA) streamA.read(bufA).coerceAtLeast(0) else 0
                    val readB = if (globalOffset < lenB) streamB.read(bufB).coerceAtLeast(0) else 0
                    val bytesInBlock = maxOf(readA, readB)
                    if (bytesInBlock <= 0) break

                    var blockOffset = 0
                    while (blockOffset < bytesInBlock) {
                        val rowLen = minOf(16, bytesInBlock - blockOffset)
                        val rowIndex = ((globalOffset + blockOffset) / 16).toInt()

                        var isRowDiff = false
                        for (i in 0 until rowLen) {
                            val byteA = if (blockOffset + i < readA) bufA[blockOffset + i] else null
                            val byteB = if (blockOffset + i < readB) bufB[blockOffset + i] else null
                            if (byteA != byteB) {
                                isRowDiff = true
                                break
                            }
                        }

                        if (isRowDiff) {
                            if (currentChunkStartRow == -1) {
                                currentChunkStartRow = rowIndex
                                currentChunkEndRow = rowIndex
                            } else {
                                if (rowIndex - currentChunkEndRow <= 2) {
                                    currentChunkEndRow = rowIndex
                                } else {
                                    commitCurrentChunk()
                                    currentChunkStartRow = rowIndex
                                    currentChunkEndRow = rowIndex
                                }
                            }
                        }

                        blockOffset += 16
                    }
                    globalOffset += bytesInBlock
                }
                commitCurrentChunk()
            }
        }
    } catch (e: Exception) {
        // Fallback on read error
    }

    return diffChunks
}

@Composable
internal fun HexDiffView(language: AppLanguage, fileA: File?, fileB: File?) {
    if (fileA == null || fileB == null || !fileA.exists() || !fileB.exists()) {
        EmptyComparePlaceholder(language)
        return
    }

    val rafA = remember(fileA) { if (fileA.exists()) RandomAccessFile(fileA, "r") else null }
    val rafB = remember(fileB) { if (fileB.exists()) RandomAccessFile(fileB, "r") else null }
    DisposableEffect(rafA, rafB) {
        onDispose {
            rafA?.close()
            rafB?.close()
        }
    }

    val fileALen = fileA.length()
    val fileBLen = fileB.length()
    val maxLen = maxOf(fileALen, fileBLen)
    val rowCount = ((maxLen + 15) / 16).toInt()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var diffChunks by remember(fileA, fileB) { mutableStateOf<List<HexDiffChunk>>(emptyList()) }
    var isScanningDiffs by remember(fileA, fileB) { mutableStateOf(true) }
    var currentDiffIndex by remember(fileA, fileB) { mutableStateOf(0) }
    var isDiffDropdownOpen by remember { mutableStateOf(false) }

    LaunchedEffect(fileA, fileB) {
        isScanningDiffs = true
        val chunks = withContext(Dispatchers.IO) {
            scanHexDifferences(fileA, fileB)
        }
        diffChunks = chunks
        currentDiffIndex = if (chunks.isNotEmpty()) 0 else -1
        isScanningDiffs = false
    }

    fun jumpToDiff(index: Int) {
        if (diffChunks.isEmpty()) return
        val targetIdx = index.coerceIn(0, diffChunks.size - 1)
        currentDiffIndex = targetIdx
        val chunk = diffChunks[targetIdx]
        coroutineScope.launch {
            listState.animateScrollToItem(chunk.startRow)
        }
    }

    fun nextDiff() {
        if (diffChunks.isEmpty()) return
        val nextIdx = (currentDiffIndex + 1) % diffChunks.size
        jumpToDiff(nextIdx)
    }

    fun prevDiff() {
        if (diffChunks.isEmpty()) return
        val prevIdx = if (currentDiffIndex <= 0) diffChunks.size - 1 else currentDiffIndex - 1
        jumpToDiff(prevIdx)
    }

    val activeChunk = diffChunks.getOrNull(currentDiffIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.F7 -> { prevDiff(); true }
                    Key.F8 -> { nextDiff(); true }
                    Key.DirectionUp -> if (event.isAltPressed) { prevDiff(); true } else false
                    Key.DirectionDown -> if (event.isAltPressed) { nextDiff(); true } else false
                    else -> false
                }
            }
    ) {
        // Toolbar with Diff Navigation Controls (Beyond Compare / Araxis style)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left: File sizes info
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "File A: ${formatSize(fileALen)} | File B: ${formatSize(fileBLen)} (Δ: ${formatSize(Math.abs(fileBLen - fileALen))})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Right: Diff Navigator Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isScanningDiffs) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            if (language == AppLanguage.KO) "차이점 분석 중..." else "Scanning diffs...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (diffChunks.isEmpty()) {
                        Text(
                            if (language == AppLanguage.KO) "✓ 100% 바이너리 일치 (차이 없음)" else "✓ 100% Binary Match (0 diffs)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonGreen,
                        )
                    } else {
                        // First diff button
                        IconButton(
                            onClick = { jumpToDiff(0) },
                            modifier = Modifier.size(24.dp),
                            enabled = diffChunks.isNotEmpty(),
                        ) {
                            Text("⇤", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // Prev diff button
                        Button(
                            onClick = { prevDiff() },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = AppControlShape,
                        ) {
                            Text(
                                if (language == AppLanguage.KO) "◀ 이전 차이 (F7)" else "◀ Prev Diff (F7)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }

                        // Jump to Diff Dropdown Menu / Pill
                        Box {
                            OutlinedButton(
                                onClick = { isDiffDropdownOpen = true },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                val currentOffsetStr = activeChunk?.let { " (0x%08X)".format(it.startOffset) } ?: ""
                                Text(
                                    "${currentDiffIndex + 1} / ${diffChunks.size}$currentOffsetStr ▼",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF9500),
                                )
                            }

                            DropdownMenu(
                                expanded = isDiffDropdownOpen,
                                onDismissRequest = { isDiffDropdownOpen = false },
                            ) {
                                Text(
                                    if (language == AppLanguage.KO) " 차이점 목록 (총 ${diffChunks.size}개 구간)" else " Diff Blocks (${diffChunks.size} total)",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                HorizontalDivider()
                                diffChunks.forEachIndexed { idx, chunk ->
                                    val isCurrent = idx == currentDiffIndex
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "#${chunk.index}: 0x%08X ~ 0x%08X (%s)".format(chunk.startOffset, chunk.endOffset, formatSize(chunk.sizeBytes)),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                        onClick = {
                                            jumpToDiff(idx)
                                            isDiffDropdownOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        // Next diff button
                        Button(
                            onClick = { nextDiff() },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = AppControlShape,
                        ) {
                            Text(
                                if (language == AppLanguage.KO) "다음 차이 ▶ (F8)" else "Next Diff ▶ (F8)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }

                        // Last diff button
                        IconButton(
                            onClick = { jumpToDiff(diffChunks.size - 1) },
                            modifier = Modifier.size(24.dp),
                            enabled = diffChunks.isNotEmpty(),
                        ) {
                            Text("⇥", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Offset", modifier = Modifier.width(75.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Media A (Hex)", modifier = Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Media A (ASCII)", modifier = Modifier.width(130.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Media B (Hex)", modifier = Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Media B (ASCII)", modifier = Modifier.width(130.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(28.dp)) // Reserve space for Scrollbar & Minimap
        }

        HorizontalDivider()

        // Table + Scrollbar + Diff Minimap
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rowCount) { rowIndex ->
                    val offset = rowIndex.toLong() * 16L
                    val (bytesA, bytesB, isDiff) = readHexDiffRow(rafA, rafB, offset, fileALen, fileBLen)

                    val isActiveChunk = activeChunk != null && rowIndex in activeChunk.startRow..activeChunk.endRow
                    val bgColor = when {
                        isActiveChunk -> Color(0xFFEF6C00).copy(alpha = 0.35f)
                        isDiff -> Color(0xFFEF6C00).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .let {
                                if (isActiveChunk && rowIndex == activeChunk.startRow) {
                                    it.border(1.dp, Color(0xFFFF9500))
                                } else it
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%08X".format(offset),
                            modifier = Modifier.width(75.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isActiveChunk) Color(0xFFFF9500) else MaterialTheme.colorScheme.primary,
                            fontWeight = if (isActiveChunk) FontWeight.Bold else FontWeight.Normal,
                        )
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatHexBytes(bytesA),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else Color.Unspecified,
                            )
                            Text(
                                formatAsciiBytes(bytesA),
                                modifier = Modifier.width(130.dp),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatHexBytes(bytesB),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else Color.Unspecified,
                            )
                            Text(
                                formatAsciiBytes(bytesB),
                                modifier = Modifier.width(130.dp),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(28.dp))
                    }
                }
            }

            // Right side: Araxis/Beyond Compare style Diff Minimap Gutter + Vertical Scrollbar
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Interactive Diff Minimap Gutter (14dp width)
                if (rowCount > 0) {
                    Canvas(
                        modifier = Modifier
                            .width(14.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF1E2228))
                            .pointerInput(rowCount, diffChunks) {
                                detectTapGestures { tapOffset ->
                                    val ratio = (tapOffset.y / size.height).coerceIn(0f, 1f)
                                    val targetRow = (ratio * rowCount).toInt().coerceIn(0, rowCount - 1)
                                    coroutineScope.launch {
                                        listState.scrollToItem(targetRow)
                                    }
                                }
                            }
                    ) {
                        val canvasHeight = size.height
                        val canvasWidth = size.width

                        // Draw diff markers on minimap
                        for (chunk in diffChunks) {
                            val startY = (chunk.startRow.toFloat() / rowCount) * canvasHeight
                            val endY = ((chunk.endRow + 1).toFloat() / rowCount) * canvasHeight
                            val markerHeight = maxOf(2.5f, endY - startY)
                            val isCurrent = activeChunk != null && chunk.index == activeChunk.index
                            drawRect(
                                color = if (isCurrent) Color(0xFFFF3131) else Color(0xFFFF9500),
                                topLeft = Offset(1f, startY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth - 2f, markerHeight),
                            )
                        }

                        // Draw current viewport indicator on minimap
                        val visibleInfo = listState.layoutInfo.visibleItemsInfo
                        if (visibleInfo.isNotEmpty()) {
                            val firstVisible = visibleInfo.first().index
                            val visibleCount = visibleInfo.size
                            val viewStartY = (firstVisible.toFloat() / rowCount) * canvasHeight
                            val viewHeight = maxOf(6f, (visibleCount.toFloat() / rowCount) * canvasHeight)
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = Offset(0f, viewStartY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth, viewHeight),
                            )
                            drawRect(
                                color = Color.White.copy(alpha = 0.8f),
                                topLeft = Offset(0f, viewStartY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth, viewHeight),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

private fun readHexDiffRow(
    rafA: RandomAccessFile?,
    rafB: RandomAccessFile?,
    offset: Long,
    fileALength: Long,
    fileBLength: Long,
): Triple<ByteArray, ByteArray, Boolean> {
    val bA = ByteArray(16)
    val bB = ByteArray(16)
    var readA = 0
    var readB = 0

    if (rafA != null && offset < fileALength) {
        synchronized(rafA) {
            rafA.seek(offset)
            readA = rafA.read(bA).coerceAtLeast(0)
        }
    }
    if (rafB != null && offset < fileBLength) {
        synchronized(rafB) {
            rafB.seek(offset)
            readB = rafB.read(bB).coerceAtLeast(0)
        }
    }

    val actualA = bA.copyOf(readA)
    val actualB = bB.copyOf(readB)
    val isDiff = !actualA.contentEquals(actualB)
    return Triple(actualA, actualB, isDiff)
}

internal fun formatHexBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "(EOF)"
    val sb = StringBuilder()
    for (i in 0 until 16) {
        if (i < bytes.size) {
            sb.append("%02X ".format(bytes[i]))
        } else {
            sb.append("   ")
        }
        if (i == 7) sb.append(" ")
    }
    return sb.toString()
}

internal fun formatAsciiBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder()
    for (i in 0 until bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
    }
    return sb.toString()
}
