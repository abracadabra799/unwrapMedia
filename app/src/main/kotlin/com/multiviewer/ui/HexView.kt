package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import java.io.File
import java.io.RandomAccessFile

private const val BYTES_PER_ROW = 16

@Composable
fun HexView(file: File, highlightRange: LongRange?, listState: LazyListState) {
    val raf = remember(file) { RandomAccessFile(file, "r") }
    DisposableEffect(raf) {
        onDispose { raf.close() }
    }
    val rowCount = ((raf.length() + BYTES_PER_ROW - 1) / BYTES_PER_ROW).toInt()

    LazyColumn(state = listState) {
        items(rowCount) { rowIndex ->
            val rowStart = rowIndex.toLong() * BYTES_PER_ROW
            val rowLength = minOf(BYTES_PER_ROW.toLong(), raf.length() - rowStart).toInt()
            val buf = ByteArray(rowLength)
            raf.seek(rowStart)
            raf.readFully(buf)

            Text(buildAnnotatedString {
                append("%08X  ".format(rowStart))
                for (i in buf.indices) {
                    val byteOffset = rowStart + i
                    val isHighlighted = highlightRange?.contains(byteOffset) == true
                    val hex = "%02X ".format(buf[i])
                    if (isHighlighted) {
                        withStyle(SpanStyle(background = Color.Yellow)) { append(hex) }
                    } else {
                        append(hex)
                    }
                }
            })
        }
    }
}
