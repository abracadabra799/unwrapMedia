package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import java.awt.Cursor
import java.io.File

private const val TABLE_PAGE_SIZE = 50

// Overlay caption for preview panels (resolution/orientation/playback time) -- these sit directly
// on top of arbitrary image/video content, so plain text alone can disappear against a bright
// background. A dark scrim behind the text guarantees contrast regardless of what's underneath.
@Composable
fun PreviewCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier
            .background(AppColors.Background.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary),
    )
}

// Shared "async work is happening right now" indicator -- a small indeterminate spinner (genuine
// motion, not just static text) plus a label saying what's in progress. Used for every background
// decode/analysis state in the app (thumbnail loading, primary image decode, video stream decode,
// GOP frame analysis, motion photo codec probing) so they all read the same way instead of each
// screen inventing its own "Decoding..."/"분석 중..." text.
@Composable
fun DecodingIndicator(label: String, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = AppColors.NeonBlue,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(label, style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary, fontSize = 12.sp))
    }
}

// Non-blocking notice for a resolution above the soft warning threshold (see
// AppState.resolutionWarningMessage) -- shown once at the top of the inspector, dismissible so it
// doesn't permanently eat vertical space once acknowledged.
@Composable
fun ResolutionWarningBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.NeonYellow.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⚠ $message",
            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonYellow),
            modifier = Modifier.weight(1f),
        )
        Text(
            "✕",
            style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonYellow),
            modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDismiss),
        )
    }
}

@Composable
fun SummaryBox(
    title: String,
    sections: List<SummarySection>,
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = AppTypography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = AppColors.NeonBlue
                )
            )
            titleTrailingContent?.invoke(this)
        }
        CoreMetadataDisplay(sections = sections)
    }
}

// A hairline divider below each row -- with many fields stacked in a plain LazyColumn (only
// vertical padding between them) it was hard to tell where one field ended and the next began.
@Composable
fun PropertyRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = AppTypography.bodyLarge,
                color = if (onClick != null) AppColors.NeonBlue else Color.Unspecified,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.5f)))
    }
}

// XMP is a raw XML packet, often several KB -- right-aligning it like a normal short PropertyRow
// value (or cramming it into the same row as its label) made every wrapped line's ragged edge sit
// flush against the right side, or start mid-column with no indentation. Pretty-printing it and
// giving it its own full-width, left-aligned block lets it read naturally.
@Composable
fun XmpFieldDisplay(raw: String) {
    val formatted = remember(raw) { prettyPrintXmlOrRaw(raw) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("xmp:", style = AppTypography.labelLarge)
        Text(
            formatted,
            style = AppTypography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier.padding(top = 2.dp, start = 8.dp),
        )
    }
}

fun prettyPrintXmlOrRaw(raw: String): String {
    return try {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            // Untrusted file content -- block DOCTYPE/external entities outright (XMP never
            // legitimately needs either) to avoid XXE rather than trying to sanitize inputs.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
            isXIncludeAware = false
        }
        val doc = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(raw)))
        val domImplLS = doc.implementation.getFeature("LS", "3.0") as org.w3c.dom.ls.DOMImplementationLS
        val serializer = domImplLS.createLSSerializer().apply {
            domConfig.setParameter("format-pretty-print", true)
        }
        val output = domImplLS.createLSOutput().apply {
            encoding = "UTF-8"
            characterStream = java.io.StringWriter()
        }
        serializer.write(doc, output)
        (output.characterStream as java.io.StringWriter).toString().trim()
    } catch (e: Exception) {
        raw // not well-formed XML (or parsing failed) -- show the original text rather than nothing
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
            style = AppTypography.labelLarge.copy(color = AppColors.NeonGreen, fontSize = 11.sp),
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
                            fontSize = 12.sp,
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
    // 2dp lines (was 1dp) -- beta testers reported not realizing panels were resizable at all; a
    // hairline was too easy to miss even with good highlight/shadow contrast.
    val lineModifier = if (orientation == Orientation.Vertical) {
        Modifier.width(2.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().height(2.dp)
    }
    // Highlight on the side facing the notional light source (left for a vertical strip, top for
    // a horizontal one), shadow on the other -- a raised-ridge look instead of one flat line,
    // same treatment as DashboardLayout's VerticalResizeHandle.
    val highlightAlignment = if (orientation == Orientation.Vertical) Alignment.CenterStart else Alignment.TopCenter
    val shadowAlignment = if (orientation == Orientation.Vertical) Alignment.CenterEnd else Alignment.BottomCenter
    // Resize cursor on hover is the standard cross-platform signal for "this is draggable" --
    // E_RESIZE/N_RESIZE render as the familiar double-headed resize arrow on desktop OSes.
    val resizeCursor = remember(orientation) {
        PointerIcon(Cursor.getPredefinedCursor(if (orientation == Orientation.Vertical) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    }
    Box(
        modifier = handleModifier
            .pointerHoverIcon(resizeCursor)
            .pointerInput(orientation, containerSizePx) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (containerSizePx > 0) {
                        val deltaPx = if (orientation == Orientation.Vertical) dragAmount.x else dragAmount.y
                        val delta = deltaPx / containerSizePx
                        setSplit((getSplit() + delta).coerceIn(0.1f, 0.9f))
                    }
                }
            },
    ) {
        Box(modifier = lineModifier.align(highlightAlignment).background(AppColors.DividerHighlight))
        Box(modifier = lineModifier.align(shadowAlignment).background(AppColors.DividerShadow))
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
            style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue, fontSize = 11.sp),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            table.columns.forEach { col ->
                Text(
                    col.uppercase(), 
                    style = AppTypography.labelLarge.copy(fontSize = 10.sp),
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
                            style = AppTypography.bodyLarge.copy(fontSize = 12.sp),
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
                Text("PREV", fontSize = 10.sp)
            }
            Text("PAGE ${page + 1} / $pageCount", style = AppTypography.labelLarge.copy(fontSize = 11.sp))
            Button(
                onClick = { page = (page + 1).coerceAtMost(pageCount - 1) },
                enabled = page < pageCount - 1,
                modifier = Modifier.height(24.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("NEXT", fontSize = 10.sp)
            }
        }
    }
}
