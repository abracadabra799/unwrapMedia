package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.GridData
import com.multiviewer.parser.SummarySection
import com.multiviewer.parser.TableData
import com.multiviewer.parser.readTableRow
import java.io.File

private const val TABLE_PAGE_SIZE = 50

@Composable
fun SummaryBox(title: String, sections: List<SummarySection>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            style = AppTypography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = AppColors.NeonBlue
            )
        )
        CoreMetadataDisplay(sections = sections)
    }
}

@Composable
fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = AppTypography.bodyLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun GridDisplay(grid: GridData) {
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Border)
            .padding(8.dp)
    ) {
        Text(
            "Matrix Data (${grid.columns}x${grid.rows})",
            style = AppTypography.labelLarge.copy(color = AppColors.NeonGreen, fontSize = 10.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        for (row in 0 until grid.rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (col in 0 until grid.columns) {
                    val value = grid.values.getOrNull(row * grid.columns + col) ?: ""
                    Text(
                        value.padStart(3),
                        style = AppTypography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = AppColors.TextPrimary
                        ),
                        modifier = Modifier.width(24.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun DraggableDivider(
    orientation: Orientation,
    containerSizePx: Int,
    getSplit: () -> Float,
    setSplit: (Float) -> Unit,
) {
    val handleModifier = if (orientation == Orientation.Vertical) {
        Modifier.width(8.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().height(8.dp)
    }
    val lineModifier = if (orientation == Orientation.Vertical) {
        Modifier.width(1.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().height(1.dp)
    }
    Box(
        modifier = handleModifier.pointerInput(orientation, containerSizePx) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                if (containerSizePx > 0) {
                    val deltaPx = if (orientation == Orientation.Vertical) dragAmount.x else dragAmount.y
                    val delta = deltaPx / containerSizePx
                    setSplit((getSplit() + delta).coerceIn(0.1f, 0.9f))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = lineModifier.background(AppColors.Border))
    }
}

@Composable
fun EmbeddedTableView(file: File, table: TableData) {
    var page by remember(table) { mutableStateOf(0) }
    val pageCount = (((table.entryCount + TABLE_PAGE_SIZE - 1) / TABLE_PAGE_SIZE).coerceAtLeast(1)).toInt()
    val start = page.toLong() * TABLE_PAGE_SIZE
    val end = minOf(start + TABLE_PAGE_SIZE, table.entryCount)

    val reader = remember(file) { ByteReader.open(file) }
    DisposableEffect(reader) {
        onDispose { reader.close() }
    }

    val rows = remember(table, page) {
        (start until end).map { rowIndex -> readTableRow(reader, table.entriesStart, table.fieldWidths, rowIndex) }
    }

    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .background(AppColors.Panel)
            .border(1.dp, AppColors.Border)
            .padding(8.dp)
    ) {
        Text(
            "Entries (${table.entryCount} total)",
            style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue, fontSize = 10.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            table.columns.forEach { col ->
                Text(
                    col.uppercase(), 
                    style = AppTypography.labelLarge.copy(fontSize = 9.sp),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    row.forEach { cell ->
                        Text(
                            cell.toString(),
                            style = AppTypography.bodyLarge.copy(fontSize = 11.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { page = (page - 1).coerceAtLeast(0) },
                enabled = page > 0,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("PREV", fontSize = 9.sp)
            }
            Text("PAGE ${page + 1} / $pageCount", style = AppTypography.labelLarge.copy(fontSize = 10.sp))
            Button(
                onClick = { page = (page + 1).coerceAtMost(pageCount - 1) },
                enabled = page < pageCount - 1,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("NEXT", fontSize = 9.sp)
            }
        }
    }
}
