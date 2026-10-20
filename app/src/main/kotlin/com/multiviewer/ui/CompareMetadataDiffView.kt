package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.*
import java.io.File
import java.util.Locale

// -------------------------------------------------------------------------------------------------
// 2. Metadata Diff View
// -------------------------------------------------------------------------------------------------

@Composable
internal fun MetadataDiffView(
    language: AppLanguage,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    metadataRows: List<MetadataDiffRow>,
    captureMismatches: List<String>,
) {
    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    var onlyDiffs by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredRows = remember(metadataRows, onlyDiffs, searchQuery) {
        metadataRows.filter { row ->
            (!onlyDiffs || row.isDifferent) &&
                (searchQuery.isBlank() || row.key.contains(searchQuery, ignoreCase = true) || row.valueA.contains(searchQuery, ignoreCase = true) || row.valueB.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (captureMismatches.isNotEmpty()) {
            Surface(
                color = Color(0xFFEF6C00).copy(alpha = 0.18f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF6C00)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    if (language == AppLanguage.KO) {
                        "⚠️ 촬영조건이 다릅니다: ${captureMismatches.joinToString(", ")} — 화질 비교 결과가 왜곡될 수 있습니다"
                    } else {
                        "⚠️ Capture conditions differ: ${captureMismatches.joinToString(", ")} — quality comparison may be misleading"
                    },
                    modifier = Modifier.padding(8.dp),
                    color = Color(0xFFEF6C00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (language == AppLanguage.KO) "🔍 메타데이터/트랙 정보 검색..." else "🔍 Search metadata/tracks...", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).height(42.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyDiffs, onCheckedChange = { onlyDiffs = it })
                Text(if (language == AppLanguage.KO) "차이점만 보기" else "Show Differences Only", fontSize = 12.sp)
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Category", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Property Name", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media A Value", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media B Value", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Diff", modifier = Modifier.weight(0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filteredRows) { row ->
                    val bgColor = if (row.isDifferent) Color(0xFFEF6C00).copy(alpha = 0.12f) else Color.Transparent
                    Row(
                        modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.category, modifier = Modifier.weight(0.8f), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Text(row.key, modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text(row.valueA.ifEmpty { "(none)" }, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(row.valueB.ifEmpty { "(none)" }, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (row.isDifferent) "≠ DIFF" else "✓",
                            modifier = Modifier.weight(0.4f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (row.isDifferent) Color(0xFFEF6C00) else Color.Gray,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

internal fun extractMetadataDiffRows(infoA: CompareMediaInfo, infoB: CompareMediaInfo): List<MetadataDiffRow> {
    val rows = mutableListOf<MetadataDiffRow>()
    val seenKeys = mutableSetOf<Pair<String, String>>()

    fun add(category: String, key: String, valA: String?, valB: String?) {
        if (!seenKeys.add(category to key)) return
        val a = valA ?: ""
        val b = valB ?: ""
        rows.add(MetadataDiffRow(category, key, a, b, a != b))
    }

    // 1. Basic Media Summary
    add("General", "File Name", infoA.file.name, infoB.file.name)
    add("General", "Media Type", if (infoA.isVideo) "Video" else "Image", if (infoB.isVideo) "Video" else "Image")
    add("General", "File Size", formatSize(infoA.fileSize), formatSize(infoB.fileSize))
    add("General", "Format", infoA.file.extension.uppercase(Locale.US), infoB.file.extension.uppercase(Locale.US))
    if (infoA.isVideo || infoB.isVideo) {
        add("General", "Duration", if (infoA.durationSeconds > 0) "${"%.2f".format(infoA.durationSeconds)}s" else "(none)", if (infoB.durationSeconds > 0) "${"%.2f".format(infoB.durationSeconds)}s" else "(none)")
    }

    // 2. Sections from MediaSummary
    val secA = infoA.summary?.sections ?: emptyList()
    val secB = infoB.summary?.sections ?: emptyList()
    val allTitles = (secA.map { it.title } + secB.map { it.title }).distinct()

    for (title in allTitles) {
        val fieldsA = secA.find { it.title == title }?.fields ?: emptyList()
        val fieldsB = secB.find { it.title == title }?.fields ?: emptyList()
        val allLabels = (fieldsA.map { it.label } + fieldsB.map { it.label }).distinct()

        for (label in allLabels) {
            val vA = fieldsA.find { it.label == label }?.value
            val vB = fieldsB.find { it.label == label }?.value
            add(title, label, vA, vB)
        }
    }

    // 3. Motion Photo Metadata (if any)
    val mpA = infoA.root?.let { root -> try { ByteReader.open(infoA.file).use { reader -> findEmbeddedVideo(root, reader) } } catch (_: Exception) { null } }
    val mpB = infoB.root?.let { root -> try { ByteReader.open(infoB.file).use { reader -> findEmbeddedVideo(root, reader) } } catch (_: Exception) { null } }
    if (mpA != null || mpB != null) {
        add("Motion Photo", "Has Motion Video", (mpA != null).toString(), (mpB != null).toString())
        add("Motion Photo", "Video Offset Range", mpA?.let { "${it.start}..${it.end} (${formatSize(it.end - it.start)})" }, mpB?.let { "${it.start}..${it.end} (${formatSize(it.end - it.start)})" })
        add("Motion Photo", "Embedded Video Format", mpA?.extension, mpB?.extension)
    }

    // 4. EXIF & Forensic
    if (infoA.forensic != null || infoB.forensic != null) {
        add("Exif", "Software", infoA.forensic?.software, infoB.forensic?.software)
        add("Exif", "Orientation", infoA.forensic?.orientation, infoB.forensic?.orientation)
        add("Exif", "DQT Quality Estimate", infoA.forensic?.dqtQuality?.takeIf { it > 0 }?.let { "$it%" }, infoB.forensic?.dqtQuality?.takeIf { it > 0 }?.let { "$it%" })
    }

    // 5. Samsung SEF Blocks
    val sefA = extractSefNames(infoA.root)
    val sefB = extractSefNames(infoB.root)
    if (sefA.isNotEmpty() || sefB.isNotEmpty()) {
        add("SEF Trailer", "SEF Block Count", sefA.size.toString(), sefB.size.toString())
        add("SEF Trailer", "SEF Block Names", sefA.joinToString(", "), sefB.joinToString(", "))
    }

    return rows
}

private val CAPTURE_CONDITION_LABELS = setOf("ISO", "Exposure Time", "F-Number", "Aperture", "Focal Length", "White Balance")

// Fields whose mismatch specifically invalidates a pixel-level quality comparison between two
// images (as opposed to any other metadata difference, e.g. file name or GPS, which doesn't).
// Two label spellings exist for the same underlying value across MediaSummaryBuilder.kt's two
// summary-building code paths ("F-Number" vs "Aperture") -- both are recognized.
// One side missing the field is *unknown*, not *matching* -- suppressing the warning here is
// deliberate, not accidental; don't "fix" this back to flagging one-sided presence as a mismatch.
internal fun captureConditionMismatches(rows: List<MetadataDiffRow>): List<String> {
    return rows.filter {
        it.key in CAPTURE_CONDITION_LABELS && it.isDifferent &&
            it.valueA.isNotBlank() && it.valueB.isNotBlank()
    }.map { it.key }.distinct()
}

private fun extractSefNames(root: BoxNode?): List<String> {
    if (root == null) return emptyList()
    val sefd = findFirst(root) { it.type == "sefd" } ?: return emptyList()
    return sefd.children.map { it.type }
}
