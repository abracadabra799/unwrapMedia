package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.*
import java.io.File
import java.io.RandomAccessFile

// -------------------------------------------------------------------------------------------------
// 1. Structure Diff View
// -------------------------------------------------------------------------------------------------

@Composable
internal fun StructureDiffView(language: AppLanguage, infoA: CompareMediaInfo?, infoB: CompareMediaInfo?) {
    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    val rows = remember(infoA.root, infoB.root) {
        computeStructureDiff(infoA.root, infoB.root)
    }

    var selectedRow by remember { mutableStateOf<StructureDiffRow?>(null) }
    var isDetailExpanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        val addedCount = rows.count { it.status == DiffStatus.ADDED_IN_B }
        val removedCount = rows.count { it.status == DiffStatus.REMOVED_IN_B }
        val modifiedCount = rows.count { it.status == DiffStatus.MODIFIED }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (language == AppLanguage.KO)
                        "📊 박스 비교 요약: 변경 ${modifiedCount}개 | 추가 ${addedCount}개 | 제거 ${removedCount}개 (💡 행을 클릭하면 상세 Hex/ASCII 데이터를 비교할 수 있습니다)"
                    else
                        "📊 Box Diff Summary: Modified $modifiedCount | Added $addedCount | Removed $removedCount (💡 Click a row to inspect detailed Hex/ASCII diff)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Box / Marker Name", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media A (Offset / Size)", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media B (Offset / Size)", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Status", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows) { row ->
                    val isSelected = selectedRow == row
                    val defaultBgColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFB71C1C).copy(alpha = 0.15f)
                        DiffStatus.MODIFIED -> Color(0xFFE65100).copy(alpha = 0.15f)
                        DiffStatus.MATCH -> Color.Transparent
                    }
                    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else defaultBgColor

                    val statusText = when (row.status) {
                        DiffStatus.ADDED_IN_B -> "➕ B에 추가됨"
                        DiffStatus.REMOVED_IN_B -> "➖ A에만 존재"
                        DiffStatus.MODIFIED -> "⚡ 크기/내용 변경"
                        DiffStatus.MATCH -> "✓ 일치"
                    }
                    val statusColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF2E7D32)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFC62828)
                        DiffStatus.MODIFIED -> Color(0xFFEF6C00)
                        DiffStatus.MATCH -> Color.Gray
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .then(
                                if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                else Modifier
                            )
                            .clickable {
                                if (selectedRow == row) {
                                    isDetailExpanded = !isDetailExpanded
                                } else {
                                    selectedRow = row
                                    isDetailExpanded = true
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.name, modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        Text(
                            if (row.sizeA != null) "0x${row.offsetA?.toString(16) ?: "0"} (${formatSize(row.sizeA)}) ${row.summaryA ?: ""}" else "-",
                            modifier = Modifier.weight(1.4f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            if (row.sizeB != null) "0x${row.offsetB?.toString(16) ?: "0"} (${formatSize(row.sizeB)}) ${row.summaryB ?: ""}" else "-",
                            modifier = Modifier.weight(1.4f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(statusText, modifier = Modifier.weight(0.8f), fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }

        // Collapsible Detail Hex & ASCII Diff Panel
        selectedRow?.let { row ->
            BoxHexDetailDiffPanel(
                language = language,
                fileA = infoA.file,
                fileB = infoB.file,
                row = row,
                isExpanded = isDetailExpanded,
                onToggleExpand = { isDetailExpanded = !isDetailExpanded },
                onClose = { selectedRow = null },
            )
        }
    }
}

@Composable
private fun BoxHexDetailDiffPanel(
    language: AppLanguage,
    fileA: File?,
    fileB: File?,
    row: StructureDiffRow,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val rafA = remember(fileA) { if (fileA != null && fileA.exists()) RandomAccessFile(fileA, "r") else null }
    val rafB = remember(fileB) { if (fileB != null && fileB.exists()) RandomAccessFile(fileB, "r") else null }
    DisposableEffect(rafA, rafB) {
        onDispose {
            rafA?.close()
            rafB?.close()
        }
    }

    val offsetA = row.offsetA
    val sizeA = row.sizeA ?: 0L
    val offsetB = row.offsetB
    val sizeB = row.sizeB ?: 0L
    val maxSize = maxOf(sizeA, sizeB)
    val rowCount = if (maxSize > 0) ((maxSize + 15) / 16).toInt() else 0

    val listState = rememberLazyListState()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isExpanded) "▼" else "▶",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        "🔍 [${row.name.trim()}] 상세 Hex / String 데이터 비교",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "A: 0x${row.offsetA?.toString(16) ?: "0"} (${formatSize(sizeA)})  vs  B: 0x${row.offsetB?.toString(16) ?: "0"} (${formatSize(sizeB)})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val statusText = when (row.status) {
                        DiffStatus.ADDED_IN_B -> "➕ B에 추가됨"
                        DiffStatus.REMOVED_IN_B -> "➖ A에만 존재"
                        DiffStatus.MODIFIED -> "⚡ 내용/크기 차이"
                        DiffStatus.MATCH -> "✓ 일치"
                    }
                    val statusColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF2E7D32)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFC62828)
                        DiffStatus.MODIFIED -> Color(0xFFEF6C00)
                        DiffStatus.MATCH -> Color.Gray
                    }
                    Text(statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (isExpanded) {
                if (rowCount == 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        Text("데이터가 비어있습니다 (0 bytes)", fontSize = 11.sp, color = Color.Gray)
                    }
                } else {
                    // Sub-Header
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Rel Offset", modifier = Modifier.width(75.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Media A (Hex)", modifier = Modifier.weight(1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Media A (ASCII)", modifier = Modifier.width(130.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Media B (Hex)", modifier = Modifier.weight(1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Media B (ASCII)", modifier = Modifier.width(130.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider()

                    Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(rowCount) { rowIndex ->
                                val relOffset = rowIndex.toLong() * 16L
                                val (bytesA, bytesB, isDiff) = readBoxHexDiffRow(rafA, rafB, offsetA, sizeA, offsetB, sizeB, relOffset)

                                val bgColor = if (isDiff) Color(0xFFEF6C00).copy(alpha = 0.15f) else Color.Transparent
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "+%04X".format(relOffset),
                                        modifier = Modifier.width(75.dp),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (bytesA.isNotEmpty()) formatHexBytes(bytesA) else "-",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else Color.Unspecified,
                                        )
                                        Text(
                                            if (bytesA.isNotEmpty()) formatAsciiBytes(bytesA) else "-",
                                            modifier = Modifier.width(130.dp),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (bytesB.isNotEmpty()) formatHexBytes(bytesB) else "-",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else Color.Unspecified,
                                        )
                                        Text(
                                            if (bytesB.isNotEmpty()) formatAsciiBytes(bytesB) else "-",
                                            modifier = Modifier.width(130.dp),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

internal fun readBoxHexDiffRow(
    rafA: RandomAccessFile?,
    rafB: RandomAccessFile?,
    offsetA: Long?,
    sizeA: Long,
    offsetB: Long?,
    sizeB: Long,
    relOffset: Long,
): Triple<ByteArray, ByteArray, Boolean> {
    val bA = if (offsetA != null && relOffset < sizeA && rafA != null) {
        val count = minOf(16L, sizeA - relOffset).toInt()
        val buf = ByteArray(count)
        synchronized(rafA) {
            rafA.seek(offsetA + relOffset)
            val read = rafA.read(buf, 0, count).coerceAtLeast(0)
            if (read == count) buf else buf.copyOf(read)
        }
    } else {
        byteArrayOf()
    }

    val bB = if (offsetB != null && relOffset < sizeB && rafB != null) {
        val count = minOf(16L, sizeB - relOffset).toInt()
        val buf = ByteArray(count)
        synchronized(rafB) {
            rafB.seek(offsetB + relOffset)
            val read = rafB.read(buf, 0, count).coerceAtLeast(0)
            if (read == count) buf else buf.copyOf(read)
        }
    } else {
        byteArrayOf()
    }

    val isDiff = !bA.contentEquals(bB)
    return Triple(bA, bB, isDiff)
}

internal data class FlatBoxItem(
    val path: String,
    val name: String,
    val node: BoxNode,
)

internal fun flattenBoxes(root: BoxNode?): List<FlatBoxItem> {
    if (root == null) return emptyList()
    val list = mutableListOf<FlatBoxItem>()
    fun traverse(node: BoxNode, currentPath: String, depth: Int) {
        if (node.type != "root") {
            val indent = "  ".repeat(depth)
            val displayName = "$indent${node.type}"
            val newPath = if (currentPath.isEmpty()) node.type else "$currentPath / ${node.type}"
            list.add(FlatBoxItem(path = newPath, name = displayName, node = node))
            node.children.forEach { traverse(it, newPath, depth + 1) }
        } else {
            node.children.forEach { traverse(it, "", 0) }
        }
    }
    traverse(root, "", 0)
    return list
}

internal fun computeStructureDiff(rootA: BoxNode?, rootB: BoxNode?): List<StructureDiffRow> {
    val listA = flattenBoxes(rootA)
    val listB = flattenBoxes(rootB)

    val n = listA.size
    val m = listB.size

    // dp[i][j] stores the max alignment score between listA[0 until i] and listB[0 until j]
    val dp = Array(n + 1) { IntArray(m + 1) }

    for (i in 1..n) {
        for (j in 1..m) {
            val a = listA[i - 1]
            val b = listB[j - 1]
            if (a.path == b.path) {
                val score = if (a.node.size == b.node.size && a.node.summary == b.node.summary) 4 else 3
                dp[i][j] = dp[i - 1][j - 1] + score
            } else if (a.node.type == b.node.type) {
                val score = if (a.node.size == b.node.size && a.node.summary == b.node.summary) 2 else 1
                dp[i][j] = dp[i - 1][j - 1] + score
            } else {
                dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // Backtrack to assemble aligned sequence
    var i = n
    var j = m
    val aligned = mutableListOf<StructureDiffRow>()

    while (i > 0 || j > 0) {
        val a = if (i > 0) listA[i - 1] else null
        val b = if (j > 0) listB[j - 1] else null

        val isMatchByPath = a != null && b != null && a.path == b.path
        val isMatchByType = a != null && b != null && a.node.type == b.node.type

        val matchScore = when {
            isMatchByPath -> if (a!!.node.size == b!!.node.size && a.node.summary == b.node.summary) 4 else 3
            isMatchByType -> if (a!!.node.size == b!!.node.size && a.node.summary == b.node.summary) 2 else 1
            else -> -1
        }

        if (a != null && b != null && matchScore > 0 && dp[i][j] == dp[i - 1][j - 1] + matchScore) {
            val isDiff = a.node.size != b.node.size || a.node.summary != b.node.summary
            aligned.add(
                StructureDiffRow(
                    path = a.path,
                    name = a.name,
                    sizeA = a.node.size,
                    sizeB = b.node.size,
                    offsetA = a.node.offset,
                    offsetB = b.node.offset,
                    status = if (isDiff) DiffStatus.MODIFIED else DiffStatus.MATCH,
                    summaryA = a.node.summary,
                    summaryB = b.node.summary,
                ),
            )
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            aligned.add(
                StructureDiffRow(
                    path = b!!.path,
                    name = b.name,
                    sizeA = null,
                    sizeB = b.node.size,
                    offsetA = null,
                    offsetB = b.node.offset,
                    status = DiffStatus.ADDED_IN_B,
                    summaryA = null,
                    summaryB = b.node.summary,
                ),
            )
            j--
        } else if (i > 0) {
            aligned.add(
                StructureDiffRow(
                    path = a!!.path,
                    name = a.name,
                    sizeA = a.node.size,
                    sizeB = null,
                    offsetA = a.node.offset,
                    offsetB = null,
                    status = DiffStatus.REMOVED_IN_B,
                    summaryA = a.node.summary,
                    summaryB = null,
                ),
            )
            i--
        }
    }

    return aligned.reversed()
}
