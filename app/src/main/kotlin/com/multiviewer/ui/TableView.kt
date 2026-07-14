package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.TableData

private const val PAGE_SIZE = 200

@Composable
fun TableView(table: TableData) {
    var page by remember(table) { mutableStateOf(0) }
    val pageCount = ((table.rows.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
    val start = page * PAGE_SIZE
    val end = minOf(start + PAGE_SIZE, table.rows.size)

    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Row {
            Text(table.columns.joinToString("  |  "))
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(table.rows.subList(start, end)) { row ->
                Text(row.joinToString("  |  ") { it.toString() })
            }
        }
        Row {
            Button(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) {
                Text("Previous")
            }
            Text(" Page ${page + 1} / $pageCount (${table.rows.size} total entries) ")
            Button(onClick = { page = (page + 1).coerceAtMost(pageCount - 1) }, enabled = page < pageCount - 1) {
                Text("Next")
            }
        }
    }
}
