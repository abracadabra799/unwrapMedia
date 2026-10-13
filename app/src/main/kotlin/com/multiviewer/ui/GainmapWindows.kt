package com.multiviewer.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.GainmapInfo
import com.multiviewer.parser.GainmapParser
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import kotlin.math.pow
import java.util.Locale

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

/** Pages of the single gain map window (it replaced separate image and XMP popups). */
enum class GainmapAnalyzerPage { OVERVIEW, IMAGE, HDR_PREVIEW, CHECK, RAW_METADATA }

/**
 * 게인맵 분석기: one window for everything gain-map related. The page lives in
 * [TabState.gainmapAnalyzerPage] (null = closed) so the overview summary box can open a specific page.
 */
@Composable
fun GainmapAnalyzerWindow(
    tab: TabState,
    page: GainmapAnalyzerPage,
    onPageChange: (GainmapAnalyzerPage) -> Unit,
    onSaveImage: () -> Unit,
    onCloseRequest: () -> Unit,
    themeMode: ThemeMode = ThemeMode.DARK,
    language: AppLanguage = AppLanguage.KO,
) {
    val gainmap = tab.gainmapInfo ?: return
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val windowState = rememberWindowState(size = DpSize(1040.dp, 780.dp), position = WindowPosition(Alignment.Center))

    Window(
        onCloseRequest = onCloseRequest,
        title = "${label("게인맵 분석기", "Gain Map Analyzer")} - ${tab.file.name}",
        state = windowState,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onCloseRequest()
                true
            } else {
                false
            }
        },
    ) {
        AppTheme(mode = themeMode, showPixelGrid = false) {
            CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
                Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        label("게인맵 분석기", "Gain Map Analyzer"),
                                        style = AppTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        color = AppColors.NeonOrange,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(AppColors.NeonOrange.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .border(1.dp, AppColors.NeonOrange.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(gainmap.formatType.displayName, style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = AppColors.NeonOrange))
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${tab.file.name} · ${gainmap.summaryDescription}",
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }
                            if (gainmap.hasGainmapImage) {
                                Button(
                                    onClick = onSaveImage,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonGreen),
                                    modifier = Modifier.border(1.dp, AppColors.NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(I18n.btnSaveGainmapImage(language), fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        val pages = GainmapAnalyzerPage.entries
                        TabRow(
                            selectedTabIndex = pages.indexOf(page),
                            containerColor = AppColors.Panel,
                            contentColor = AppColors.NeonOrange,
                        ) {
                            pages.forEach { p ->
                                val title = when (p) {
                                    GainmapAnalyzerPage.OVERVIEW -> label("개요", "Overview")
                                    GainmapAnalyzerPage.IMAGE -> label("게인맵 이미지", "Gain map image")
                                    GainmapAnalyzerPage.HDR_PREVIEW -> label("HDR 미리보기", "HDR preview")
                                    GainmapAnalyzerPage.CHECK -> label("검사", "Check")
                                    GainmapAnalyzerPage.RAW_METADATA -> label("메타데이터 원본", "Raw metadata")
                                }
                                Tab(
                                    selected = p == page,
                                    onClick = { onPageChange(p) },
                                    text = { Text(title, style = AppTypography.labelLarge) },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                        ) {
                            when (page) {
                                GainmapAnalyzerPage.OVERVIEW -> GainmapParametersCardView(gainmap, language)
                                GainmapAnalyzerPage.IMAGE -> GainmapImagePage(tab, gainmap, language)
                                GainmapAnalyzerPage.HDR_PREVIEW -> GainmapHdrPreviewPage(tab, gainmap, language)
                                GainmapAnalyzerPage.CHECK -> GainmapCheckPage(tab, language)
                                GainmapAnalyzerPage.RAW_METADATA -> GainmapRawMetadataPage(gainmap, language)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Decodes the gain map once per tab (cached in [TabState.gainmapBitmap]); null while decoding/failed. */
@Composable
internal fun rememberGainmapBitmap(tab: TabState, gainmap: GainmapInfo): Pair<androidx.compose.ui.graphics.ImageBitmap?, Boolean> {
    var gainmapBitmap by remember(tab.file, gainmap) { mutableStateOf(tab.gainmapBitmap) }
    var isDecoding by remember(tab.file, gainmap) { mutableStateOf(gainmapBitmap == null && gainmap.hasGainmapImage) }
    LaunchedEffect(tab.file, gainmap) {
        if (gainmapBitmap == null && gainmap.hasGainmapImage) {
            isDecoding = true
            GainmapParser.decodeGainmapBitmapAsync(tab.file, tab.root, gainmap) { decoded ->
                gainmapBitmap = decoded
                tab.gainmapBitmap = decoded
                isDecoding = false
            }
        }
    }
    return gainmapBitmap to isDecoding
}

@Composable
private fun GainmapImagePage(tab: TabState, gainmap: GainmapInfo, language: AppLanguage) {
    val (bitmap, isDecoding) = rememberGainmapBitmap(tab, gainmap)
    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            PixelInspectorPreview(bitmap = bitmap, modifier = Modifier.fillMaxSize())
            Row(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewCaption("${bitmap.width}x${bitmap.height} · Format: ${gainmap.imageFormat ?: "JPEG"} · ${gainmap.summaryDescription}")
            }
        } else if (isDecoding) {
            DecodingIndicator("게인맵 이미지 디코딩 중...", modifier = Modifier.align(Alignment.Center))
        } else {
            val missing = !gainmap.hasGainmapImage
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (missing) Icons.Default.Info else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (missing) AppColors.NeonBlue else AppColors.NeonRed,
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    if (missing) I18n.msgNoGainmapImage(language) else I18n.msgCannotDecodeGainmap(language),
                    style = AppTypography.titleMedium.copy(
                        color = if (missing) AppColors.TextPrimary else AppColors.NeonRed,
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (missing) I18n.descNoGainmapImage(language) else I18n.descCannotDecodeGainmap(language, gainmap.imageFormat ?: "HEVC/JPEG"),
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GainmapRawMetadataPage(gainmap: GainmapInfo, language: AppLanguage) {
    val sources = buildList {
        gainmap.secondaryXmp?.let { add(I18n.tabGainmapSecondaryXmp(language) to prettyPrintXmlOrRaw(it)) }
        gainmap.primaryXmp?.let { if (it != gainmap.secondaryXmp) add(I18n.tabGainmapPrimaryXmp(language) to prettyPrintXmlOrRaw(it)) }
        if (isEmpty()) gainmap.rawXmp?.let { add("XMP" to prettyPrintXmlOrRaw(it)) }
        gainmap.isoMetadata?.let { add("ISO 21496-1" to describeIsoGainmapMetadata(it, gainmap.isoPayloadOffset)) }
    }
    var selected by remember(gainmap) { mutableStateOf(0) }
    val current = sources.getOrNull(selected)?.second ?: ""
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(AppColors.Panel.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sources.forEachIndexed { i, (title, _) ->
                Row(
                    modifier = Modifier
                        .clickable { selected = i }
                        .background(if (selected == i) AppColors.NeonOrange.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        title,
                        style = AppTypography.labelMedium.copy(color = if (selected == i) AppColors.NeonOrange else AppColors.TextSecondary, fontSize = 11.sp),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (current.isNotBlank()) {
                Text(
                    "${formatBytes(current.length.toLong())} · ${current.lines().size} lines",
                    style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (ClipboardUtil.copyToClipboard(current)) copied = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (copied) AppColors.NeonGreen.copy(alpha = 0.8f) else AppColors.Panel,
                        contentColor = if (copied) Color.Black else AppColors.TextPrimary,
                    ),
                    modifier = Modifier.border(1.dp, if (copied) AppColors.NeonGreen else AppColors.Border, RoundedCornerShape(4.dp)),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(if (copied) "✓" else I18n.btnCopyXmp(language), fontSize = 12.sp)
                }
            }
        }
        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Box(modifier = Modifier.fillMaxSize().verticalScroll(vScroll).horizontalScroll(hScroll).padding(12.dp)) {
                    Text(
                        text = current.ifBlank { "(No XMP metadata XML found)" },
                        style = AppTypography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = AppColors.TextPrimary),
                    )
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(vScroll), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            HorizontalScrollbar(adapter = rememberScrollbarAdapter(hScroll), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
    }
}

/**
 * Clean, structured cards for Gain Map parameters (Max Boost, Headroom, Gamma, Offsets, Base Rendition).
 */
@Composable
private fun GainmapParametersCardView(gainmap: GainmapInfo, language: AppLanguage) {
    val params = gainmap.parameters
    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Dynamic Range Headroom Banner
            item {
                val maxBoost = params?.linearMaxBoost
                val stops = params?.stops ?: params?.gainMapMax
                val boostText = if (maxBoost != null) "%.2fx (%.2f EV stops)".format(maxBoost, stops ?: 0.0) else "Not specified"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E232B), RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.NeonOrange.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "HDR Headroom / Peak Boost (최대 다이내믹 레인지)",
                                style = AppTypography.labelMedium.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                boostText,
                                style = AppTypography.headlineSmall.copy(color = AppColors.NeonOrange, fontWeight = FontWeight.Bold, fontSize = 20.sp),
                            )
                        }

                        if (maxBoost != null) {
                            Box(
                                modifier = Modifier
                                    .background(AppColors.NeonOrange.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "+${"%.2f".format(stops ?: 0.0)} EV",
                                    style = AppTypography.labelLarge.copy(color = AppColors.NeonOrange, fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Visual Dynamic Range bar
                    val sdrFraction = 0.35f
                    val hdrFraction = 0.65f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .background(Color(0xFF101317), RoundedCornerShape(6.dp)),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(sdrFraction)
                                .fillMaxHeight()
                                .background(AppColors.NeonBlue.copy(alpha = 0.7f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .weight(hdrFraction)
                                .fillMaxHeight()
                                .background(AppColors.NeonOrange.copy(alpha = 0.85f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("SDR Range (0.0 ~ 1.0)", style = AppTypography.labelSmall.copy(color = AppColors.NeonBlue, fontSize = 10.sp))
                        Text("HDR Boost Headroom (+${"%.2f".format(stops ?: 0.0)} EV)", style = AppTypography.labelSmall.copy(color = AppColors.NeonOrange, fontSize = 10.sp))
                    }
                }
            }

            // 2. Math & Tone Mapping Parameters Grid
            item {
                Text(
                    "📐 게인맵 변환 수학 & 톤 매핑 파라미터 (ISO 21496-1 / Adobe HDRGM)",
                    style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF191D24), RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParameterRow("GainMapMin (최소 게인)", params?.gainMapMin?.let { "%.4f (%.2fx)".format(it, 2.0.pow(it)) } ?: "0.0000 (1.00x)")
                    ParameterRow("GainMapMax (최대 게인)", params?.gainMapMax?.let { "%.4f (+%.2f EV, %.2fx boost)".format(it, it, 2.0.pow(it)) } ?: "Not specified")
                    ParameterRow("Gamma (감마 곡선)", params?.gamma?.let { "%.4f (%s)".format(it, if (it == 1.0) "Linear / 선형" else "Non-linear") } ?: "1.0000 (Linear)")
                    ParameterRow("OffsetSDR (SDR 오프셋)", params?.offsetSdr?.let { "%.6f".format(it) } ?: "0.015625 (1/64 default)")
                    ParameterRow("OffsetHDR (HDR 오프셋)", params?.offsetHdr?.let { "%.6f".format(it) } ?: "0.015625 (1/64 default)")
                    ParameterRow("HDRCapacityMin (최소 HDR 용량)", params?.hdrCapacityMin?.let { "%.4f EV".format(it) } ?: "0.0000 EV")
                    ParameterRow("HDRCapacityMax (최대 HDR 용량)", params?.hdrCapacityMax?.let { "%.4f EV (%.2fx)".format(it, 2.0.pow(it)) } ?: params?.gainMapMax?.let { "%.4f EV".format(it) } ?: "Not specified")
                    ParameterRow("BaseRenditionIsHDR (기본 렌더링)", when (params?.baseRenditionIsHdr) {
                        true -> "True (기본 이미지가 HDR이며 게인맵으로 SDR을 생성)"
                        false -> "False (기본 이미지가 SDR이며 게인맵으로 HDR을 생성 - 표준)"
                        else -> "False (기본 이미지는 SDR)"
                    })
                    if (params?.alternateColorSpace != null) {
                        ParameterRow("AlternateColorSpace (대체 색공간)", params.alternateColorSpace)
                    }
                    if (params?.version != null) {
                        ParameterRow("Specification Version", params.version)
                    }
                }
            }

            // Where the parameters above come from, and whether the two encodings agree.
            item {
                Text(
                    if (language == AppLanguage.KO) "🧾 메타데이터 출처" else "🧾 Metadata sources",
                    style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF191D24), RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ParameterRow(
                        if (language == AppLanguage.KO) "발견된 메타데이터" else "Found",
                        gainmap.metadataSources.joinToString(" · ") { it.displayName }.ifEmpty { "—" },
                    )
                    ParameterRow(
                        if (language == AppLanguage.KO) "적용 파라미터 기준" else "Effective parameters from",
                        if (gainmap.isoMetadata != null) "ISO 21496-1" else if (gainmap.xmpParameters != null) "XMP" else "—",
                    )
                    gainmap.auxType?.let { ParameterRow("HEIF auxC aux_type", it) }
                    gainmap.tmapItem?.let { t ->
                        ParameterRow("HEIF tmap", "Item #${t.itemId} · version ${t.version ?: "?"} · dimg → ${t.inputItemIds.joinToString()}")
                    }
                    if (gainmap.formatType == com.multiviewer.parser.GainmapFormatType.ISO_21496_1_JPEG) {
                        ParameterRow(
                            if (language == AppLanguage.KO) "주 이미지 ISO 버전 블록" else "Primary ISO version block",
                            if (gainmap.hasIsoVersionBlockInPrimary) "✓" else "✗",
                        )
                    }
                }
            }

            val iso = gainmap.isoMetadata?.toParsedParameters()
            val xmp = gainmap.xmpParameters
            if (iso != null && xmp != null) {
                item {
                    Text(
                        if (language == AppLanguage.KO) "⚖️ XMP ↔ ISO 21496-1 비교" else "⚖️ XMP ↔ ISO 21496-1",
                        style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF191D24), RoundedCornerShape(6.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        gainmapParameterComparison(xmp, iso).forEach { row ->
                            ParameterRow(
                                row.name,
                                "XMP ${row.xmp?.let { "%.4f".format(it) } ?: "—"}  ·  ISO ${row.iso?.let { "%.4f".format(it) } ?: "—"}  ${if (row.matches) "✓" else "≠"}",
                            )
                        }
                    }
                }
            }

            // 3. Gain Map Image Payload Details
            item {
                Text(
                    "🖼️ 게인맵 보조 이미지 데이터 정보",
                    style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                )
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF191D24), RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val res = if (gainmap.imageWidth != null && gainmap.imageHeight != null) "${gainmap.imageWidth} x ${gainmap.imageHeight}" else "Unknown"
                    ParameterRow("해상도 (Resolution)", res)
                    ParameterRow("이미지 압축 포맷 (Format)", gainmap.imageFormat ?: "JPEG")
                    if (gainmap.byteLength != null) {
                        ParameterRow("데이터 크기 (Byte Size)", "${formatBytes(gainmap.byteLength)} (${gainmap.byteLength} bytes)")
                    }
                    if (gainmap.byteOffset != null) {
                        ParameterRow("파일 내 오프셋 (Offset)", "Offset ${gainmap.byteOffset} (0x${gainmap.byteOffset.toString(16).uppercase()})")
                    }
                    if (gainmap.itemId != null) {
                        ParameterRow("HEIF Item ID", "Item #${gainmap.itemId}")
                    }
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun ParameterRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp),
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = AppTypography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = AppColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

internal data class GainmapParameterComparisonRow(val name: String, val xmp: Double?, val iso: Double?) {
    val matches: Boolean get() = xmp != null && iso != null && kotlin.math.abs(xmp - iso) <= 1e-3
}

/** Field-by-field XMP vs ISO values (both in hdrgm units), as shown in the overview. */
internal fun gainmapParameterComparison(
    xmp: com.multiviewer.parser.GainmapParsedParameters,
    iso: com.multiviewer.parser.GainmapParsedParameters,
): List<GainmapParameterComparisonRow> = listOf(
    GainmapParameterComparisonRow("GainMapMin", xmp.gainMapMin, iso.gainMapMin),
    GainmapParameterComparisonRow("GainMapMax", xmp.gainMapMax, iso.gainMapMax),
    GainmapParameterComparisonRow("Gamma", xmp.gamma, iso.gamma),
    GainmapParameterComparisonRow("OffsetSDR", xmp.offsetSdr, iso.offsetSdr),
    GainmapParameterComparisonRow("OffsetHDR", xmp.offsetHdr, iso.offsetHdr),
    GainmapParameterComparisonRow("HDRCapacityMin", xmp.hdrCapacityMin, iso.hdrCapacityMin),
    GainmapParameterComparisonRow("HDRCapacityMax", xmp.hdrCapacityMax, iso.hdrCapacityMax),
)

/** Plain-text dump of the decoded ISO metadata for the raw page. */
internal fun describeIsoGainmapMetadata(m: com.multiviewer.parser.IsoGainmapMetadata, payloadOffset: Long?): String = buildString {
    appendLine("ISO 21496-1 gain map metadata")
    payloadOffset?.let { appendLine("payload offset      : $it (0x${it.toString(16).uppercase()})") }
    appendLine("minimum_version     : ${m.minimumVersion}")
    appendLine("writer_version      : ${m.writerVersion}")
    appendLine("multichannel        : ${m.isMultichannel}")
    appendLine("use_base_colour_space: ${m.useBaseColourSpace}")
    appendLine("backward_direction  : ${m.backwardDirection} (base rendition is ${if (m.backwardDirection) "HDR" else "SDR"})")
    appendLine("common_denominator  : ${m.commonDenominator}")
    appendLine("base_hdr_headroom   : ${m.baseHdrHeadroom} (log2)")
    appendLine("alternate_hdr_headroom: ${m.alternateHdrHeadroom} (log2)")
    m.channels.forEachIndexed { i, c ->
        appendLine("channel $i: min=${c.gainMapMin} max=${c.gainMapMax} gamma=${c.gamma} base_offset=${c.baseOffset} alternate_offset=${c.alternateOffset}")
    }
}
