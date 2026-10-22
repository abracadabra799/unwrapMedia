package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

private data class FlatRow(
    val node: BoxNode,
    val depth: Int,
    val isLastSibling: Boolean,
    val ancestorContinuations: List<Boolean>,
)

private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 20

enum class BoxNodeCategory(
    val labelKo: String,
    val labelEn: String,
    val badgeColorHex: Long,
) {
    ROOT("루트", "Root", 0xFF90A4AE),
    CONTAINER("컨테이너", "Container", 0xFF4FC3F7),
    HEADER_MARKER("헤더/마커", "Header/Marker", 0xFFFFB74D),
    CODEC_CONFIG("코덱/파라미터", "Codec Config", 0xFFCE93D8),
    SAMPLE_TABLE("샘플 테이블", "Sample Table", 0xFFFFD54F),
    METADATA("메타데이터", "Metadata", 0xFF4DD0E1),
    MEDIA_DATA("미디어 데이터", "Media Data", 0xFF81C784),
    TRAILER_SEF("트레일러/SEF", "Trailer/SEF", 0xFFFF8A65),
}

fun classifyBoxNode(node: BoxNode, isRoot: Boolean = false): BoxNodeCategory {
    if (isRoot) return BoxNodeCategory.ROOT
    val rawType = node.type.trim()
    val upper = rawType.uppercase(Locale.US)

    if (upper in SEF_OR_TRAILER_TYPES || upper.startsWith("SEF") || upper.startsWith("MPV")) {
        return BoxNodeCategory.TRAILER_SEF
    }
    if (rawType in CODEC_CONFIG_TYPES || upper in CODEC_CONFIG_TYPES_UPPER || upper.startsWith("SOF")) {
        return BoxNodeCategory.CODEC_CONFIG
    }
    if (rawType in SAMPLE_TABLE_TYPES || upper in SAMPLE_TABLE_TYPES_UPPER) {
        return BoxNodeCategory.SAMPLE_TABLE
    }
    if (rawType in MEDIA_DATA_TYPES || upper in MEDIA_DATA_TYPES_UPPER) {
        return BoxNodeCategory.MEDIA_DATA
    }
    if (rawType in HEADER_MARKER_TYPES || upper in HEADER_MARKER_TYPES_UPPER || upper.startsWith("RST")) {
        return BoxNodeCategory.HEADER_MARKER
    }
    if (rawType in METADATA_TYPES || upper in METADATA_TYPES_UPPER || upper.startsWith("APP")) {
        return BoxNodeCategory.METADATA
    }
    if (node.children.isNotEmpty() || rawType in CONTAINER_TYPES || upper in CONTAINER_TYPES_UPPER) {
        return BoxNodeCategory.CONTAINER
    }
    return BoxNodeCategory.METADATA
}

private val SEF_OR_TRAILER_TYPES = setOf(
    "SEFD", "SEFH", "SEFT", "MPVD", "MPVF", "TRAILER", "GAINMAPIMAGE", "EMBEDDEDVIDEO",
)

private val CODEC_CONFIG_TYPES = setOf(
    "avcC", "hvcC", "vvcC", "av1C", "av2C", "apvC", "vpcC", "dvcC", "dvvC", "dvwC",
    "esds", "dac3", "dec3", "dOps", "dfLa", "alac", "stsd", "avc1", "avc3", "hev1",
    "hvc1", "vvc1", "vvi1", "av01", "av02", "apv1", "vp09", "mp4a", "Opus", "fLaC",
    "lhvC", "vexu", "eyes", "hero", "stri", "cams", "cmfy", "btrt", "pasp", "clap",
)

private val CODEC_CONFIG_TYPES_UPPER = setOf(
    "IHDR", "PLTE", "DQT", "DHT", "DRI", "DAC", "FMT", "FMT ", "STREAMINFO", "VP8X",
    "SEQUENCE HEADER", "CODECPRIVATE",
)

private val SAMPLE_TABLE_TYPES = setOf(
    "stts", "ctts", "stss", "stsc", "stsz", "stz2", "stco", "co64", "elst", "sbgp",
    "sgpd", "sdtp", "saiz", "saio", "tfdt", "trun", "tfhd", "mfhd", "mehd", "trex",
    "sidx", "ssix",
)

private val SAMPLE_TABLE_TYPES_UPPER = setOf(
    "CUES", "SEEKHEAD", "IDX1", "INDX", "SEEKTABLE",
)

private val MEDIA_DATA_TYPES = setOf(
    "mdat", "idat", "imda",
)

private val MEDIA_DATA_TYPES_UPPER = setOf(
    "IDAT", "SOS", "DATA", "SIMPLEBLOCK", "BLOCKGROUP", "BLOCK", "VP8", "VP8 ", "VP8L",
    "ANMF", "ALPH", "FRAME", "AUDIO FRAME", "JFIF SCAN", "SCAN DATA",
)

private val HEADER_MARKER_TYPES = setOf(
    "ftyp", "styp", "pdin", "free", "skip", "wide",
)

private val HEADER_MARKER_TYPES_UPPER = setOf(
    "SOI", "EOI", "IEND", "EBML", "SIGNATURE", "HEADER", "SYNC",
)

private val METADATA_TYPES = setOf(
    "mvhd", "tkhd", "mdhd", "hdlr", "vmhd", "smhd", "hmhd", "nmhd", "dinf", "dref",
    "udta", "meta", "keys", "ilst", "uuid", "colr", "mdcv", "clli", "amve", "c2pa",
    "jumb", "jumd", "iinf", "infe", "iloc", "iref", "ipma", "pitm", "ispe", "pixi",
    "irot", "imir", "auxC", "tmap", "tref", "cdsc", "auxl", "dimg", "thmb", "Exif",
    "mime",
)

private val METADATA_TYPES_UPPER = setOf(
    "EXIF", "XMP", "XMP ", "ICCP", "TEXT", "ITXT", "ZTXT", "TIME", "GAMA", "CHRM",
    "SRGB", "SBIT", "BKGD", "HIST", "PHYS", "SPLT", "TRNS", "CLLI", "MDCV", "CICp",
    "COM", "INFO", "VORBIS_COMMENT", "PICTURE", "APPLICATION", "TAGS", "CHAPTERS",
    "TRACKENTRY", "VIDEO", "AUDIO", "IFD0", "IFD1", "EXIFIFD", "GPSIFD", "INTEROPIFD",
)

private val CONTAINER_TYPES = setOf(
    "moov", "trak", "mdia", "minf", "stbl", "edts", "moof", "traf", "mfra", "iprp",
    "ipco", "grpl",
)

private val CONTAINER_TYPES_UPPER = setOf(
    "RIFF", "LIST", "SEGMENT", "CLUSTER", "TRACKS",
)

fun formatBoxByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatBoxTreeAsText(root: BoxNode): String {
    val totalSize = root.size.coerceAtLeast(1L)
    val sb = StringBuilder()

    fun walk(node: BoxNode, prefix: String, isLast: Boolean, isRoot: Boolean) {
        val connector = if (isRoot) "" else if (isLast) "└─ " else "├─ "
        val cat = classifyBoxNode(node, isRoot)
        val pct = if (!isRoot && node.size > 0L && totalSize > 0L) {
            String.format(Locale.US, " (%.1f%%)", (node.size.toDouble() / totalSize * 100.0).coerceAtMost(100.0))
        } else ""
        val sizeStr = "${formatBoxByteSize(node.size)}$pct @0x${node.offset.toString(16).uppercase(Locale.US)}"
        val summaryPart = node.summary?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        val warnPart = if (node.warnings.isNotEmpty()) " ⚠ [${node.warnings.joinToString("; ")}]" else ""
        sb.append(prefix)
            .append(connector)
            .append("[${node.type}] ")
            .append("(${cat.labelEn}, $sizeStr)")
            .append(summaryPart)
            .append(warnPart)
            .append('\n')

        val childPrefix = if (isRoot) "" else prefix + if (isLast) "   " else "│  "
        node.children.forEachIndexed { idx, child ->
            walk(child, childPrefix, idx == node.children.lastIndex, isRoot = false)
        }
    }

    walk(root, "", isLast = true, isRoot = true)
    return sb.toString().trimEnd()
}

private fun collectAllExpandableNodes(node: BoxNode, out: MutableSet<BoxNode> = mutableSetOf()): Set<BoxNode> {
    if (node.children.isNotEmpty()) {
        out.add(node)
        for (c in node.children) {
            collectAllExpandableNodes(c, out)
        }
    }
    return out
}

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit, language: AppLanguage = AppLanguage.KO) {
    fun label(ko: String, en: String) = if (language == AppLanguage.KO) ko else en
    val expanded = remember(root) { mutableStateOf(setOf(root)) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    // Selection can arrive from anywhere (a manual tree click, the Detailed Properties warnings
    // summary, etc.) -- whatever ancestors of the selected node aren't already expanded need to
    // be, or the node has no row to render/highlight at all (flatten() below skips children of
    // collapsed nodes entirely).
    LaunchedEffect(selected) {
        val target = selected ?: return@LaunchedEffect
        val ancestors = findAncestors(root, target, emptyList())
        if (!ancestors.isNullOrEmpty()) {
            expanded.value = expanded.value + ancestors
        }
    }

    val rows = remember(root, expanded.value) { flatten(root, 0, expanded.value, isLastSibling = true, ancestorContinuations = emptyList()) }
    val listState = rememberLazyListState()

    // Reveal once per selection change. Expanding another branch must not jump back
    // to the previous selection. Wait for its ancestors to become visible first.
    LaunchedEffect(root, selected) {
        val target = selected ?: return@LaunchedEffect
        if (findAncestors(root, target, emptyList()) == null) return@LaunchedEffect
        val index = snapshotFlow { visibleRowIndexOf(root, expanded.value, target) }
            .first { it >= 0 }
        listState.animateScrollToItem(index)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compact Top Action & Category Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Panel.copy(alpha = 0.65f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TreeActionChip(
                    text = label("모두 펼치기", "Expand all"),
                    onClick = { expanded.value = collectAllExpandableNodes(root) },
                )
                TreeActionChip(
                    text = label("모두 접기", "Collapse all"),
                    onClick = { expanded.value = setOf(root) },
                )
                TreeActionChip(
                    text = if (copied) label("복사됨", "Copied") else label("트리 복사", "Copy tree"),
                    highlight = copied,
                    onClick = {
                        if (ClipboardUtil.copyToClipboard(formatBoxTreeAsText(root))) {
                            copied = true
                        }
                    },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp).height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label("구조", "Structure"), color = AppColors.TextMuted, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(label("크기", "Size"), color = AppColors.TextMuted, fontSize = 10.sp,
                textAlign = TextAlign.End, modifier = Modifier.width(76.dp))
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows) { row ->
                    val node = row.node
                    val isRoot = row.depth == 0
                    val isSelected = node === selected
                    val isExpanded = node in expanded.value
                    val category = classifyBoxNode(node, isRoot)
                    val categoryColor = Color(category.badgeColorHex)
                    val hasWarnings = node.warnings.isNotEmpty()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(if (isSelected) AppColors.Selection else Color.Transparent)
                            .semantics { this.selected = isSelected }
                            .clickable { onSelect(node) }
                            .padding(start = 6.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Tree hierarchy indent guides
                            for (d in 0 until row.depth) {
                                val isConnectorColumn = d == row.depth - 1
                                val drawVerticalLine = if (isConnectorColumn) true else row.ancestorContinuations.getOrElse(d) { false }
                                Box(modifier = Modifier.width(DEPTH_INDENT_DP.dp).fillMaxHeight()) {
                                    if (drawVerticalLine) {
                                        Box(
                                            modifier = Modifier
                                                .align(if (isConnectorColumn && row.isLastSibling) Alignment.TopCenter else Alignment.Center)
                                                .width(1.dp)
                                                .fillMaxHeight(if (isConnectorColumn && row.isLastSibling) 0.55f else 1f)
                                                .background(AppColors.Border.copy(alpha = 0.75f)),
                                        )
                                    }
                                    if (isConnectorColumn) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .width((DEPTH_INDENT_DP / 2).dp)
                                                .height(1.dp)
                                                .background(AppColors.Border.copy(alpha = 0.75f)),
                                        )
                                    }
                                }
                            }

                            // Disclosure owns expansion; selecting a row never folds its children.
                            Box(
                                modifier = Modifier.width(ARROW_WIDTH_DP.dp).fillMaxHeight()
                                    .then(if (node.children.isNotEmpty()) Modifier.clickable(
                                        onClickLabel = if (isExpanded) label("접기", "Collapse") else label("펼치기", "Expand"),
                                    ) {
                                        expanded.value = if (isExpanded) expanded.value - node else expanded.value + node
                                    } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (node.children.isNotEmpty()) {
                                    Text(if (isExpanded) "▾" else "▸", color = AppColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                            Box(Modifier.width(5.dp).height(5.dp)
                                .background(categoryColor.copy(alpha = 0.7f), RoundedCornerShape(1.dp)))
                            Spacer(Modifier.width(6.dp))
                            Row(Modifier.weight(1f).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = node.type,
                                    color = AppColors.TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (node.children.isNotEmpty() && !isExpanded) {
                                    Text("  ${node.children.size}", color = AppColors.TextMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                node.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                                    Text(
                                        text = summary,
                                        color = AppColors.TextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                                    )
                                }
                            }
                        }
                        if (hasWarnings) {
                            Text("⚠ ${node.warnings.size}", color = AppColors.NeonRed, fontSize = 10.sp,
                                maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                        }
                        Text(
                            text = formatBoxByteSize(node.size),
                            color = AppColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(76.dp),
                        )
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun TreeActionChip(
    text: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val textColor = if (highlight) AppColors.NeonGreen else AppColors.TextSecondary
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

// Returns the chain of ancestors (root..parent-of-target, not including target itself) that must
// be expanded for target's row to be reachable, or null if target isn't in this subtree. Uses
// reference equality (===), matching how `selected` is already compared elsewhere in this file --
// BoxNode is a data class, so structurally-identical-but-distinct nodes are common in real trees
// (e.g. repeated small boxes) and must not be confused with each other.
fun findAncestors(current: BoxNode, target: BoxNode, path: List<BoxNode>): List<BoxNode>? {
    if (current === target) return path
    for (child in current.children) {
        val result = findAncestors(child, target, path + current)
        if (result != null) return result
    }
    return null
}

// The innermost box/marker covering [offset], or null when the offset lies outside the tree.
// Drives the hex viewer -> structure tree direction: a byte selected in the hex dump resolves to
// the node that actually contains it.
//
// Innermost rather than any match, because every byte inside a child is also inside its ancestors,
// so anything less specific would just answer "the root" for the whole file. Zero-sized nodes
// (markers recorded with no payload, e.g. JPEG's SOI) cover no bytes and are skipped, otherwise
// they would shadow the real container that starts at the same offset.
fun findNodeAtOffset(root: BoxNode, offset: Long): BoxNode? {
    if (root.size <= 0L || offset < root.offset || offset >= root.offset + root.size) return null
    for (child in root.children) {
        val hit = findNodeAtOffset(child, offset)
        if (hit != null) return hit
    }
    return root
}

// Row index of [target] in the tree as it is currently drawn, or -1 when it has no row (an ancestor
// is collapsed, or the node belongs to a different tree). Used to scroll a selection into view --
// expanding and highlighting a node is not enough when it sits far down a large file's structure,
// which is the usual case for a node reached by clicking a structural warning.
//
// Matched by identity, like findAncestors above: BoxNode is a data class, so repeated small boxes
// are frequently equal to one another while being different rows.
fun visibleRowIndexOf(root: BoxNode, expanded: Set<BoxNode>, target: BoxNode): Int =
    flatten(root, 0, expanded, isLastSibling = true, ancestorContinuations = emptyList()).indexOfFirst { it.node === target }

private fun flatten(
    node: BoxNode,
    depth: Int,
    expanded: Set<BoxNode>,
    isLastSibling: Boolean,
    ancestorContinuations: List<Boolean>,
): List<FlatRow> {
    val rows = mutableListOf(FlatRow(node, depth, isLastSibling, ancestorContinuations))
    if (node.children.isNotEmpty() && node in expanded) {
        val nextContinuations = if (depth == 0) ancestorContinuations else ancestorContinuations + (!isLastSibling)
        node.children.forEachIndexed { index, child ->
            val childIsLast = index == node.children.lastIndex
            rows.addAll(flatten(child, depth + 1, expanded, childIsLast, nextContinuations))
        }
    }
    return rows
}
