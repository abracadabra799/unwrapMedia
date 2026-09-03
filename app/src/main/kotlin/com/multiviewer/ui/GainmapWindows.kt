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
import androidx.compose.ui.input.key.onKeyEvent
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
import com.multiviewer.parser.GainmapParsedParameters
import com.multiviewer.parser.GainmapParser
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import kotlin.math.pow
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
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

/**
 * Window dialog for "Gain Map XMP Metadata..."
 * Displays raw pretty-printed XMP metadata XML as well as parsed gain map parameters.
 */
@Composable
fun GainmapXmpWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    language: AppLanguage = AppLanguage.KO,
    onOpenImage: () -> Unit = {},
    onCloseRequest: () -> Unit,
) {
    val gainmap = tab.gainmapInfo ?: return
    val windowState = rememberWindowState(
        size = DpSize(920.dp, 700.dp),
        position = WindowPosition(Alignment.Center),
    )

    var activeTab by remember { mutableStateOf(0) } // 0: Parameters, 1: Raw XMP
    var selectedXmpSource by remember { mutableStateOf(0) } // 0: Gainmap/Secondary, 1: Primary

    val currentRawXmp = remember(gainmap, selectedXmpSource) {
        if (selectedXmpSource == 1 && gainmap.primaryXmp != null) {
            prettyPrintXmlOrRaw(gainmap.primaryXmp)
        } else {
            prettyPrintXmlOrRaw(gainmap.secondaryXmp ?: gainmap.rawXmp ?: "")
        }
    }

    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${I18n.titleGainmapXmpWindow(language)} - ${tab.file.name}",
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        I18n.titleGainmapXmpWindow(language),
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
                                        Text(
                                            gainmap.formatType.displayName,
                                            style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = AppColors.NeonOrange),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${tab.file.name} · ${gainmap.summaryDescription}",
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }

                            // Quick Open Image button if available
                            Button(
                                onClick = onOpenImage,
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonGreen),
                                modifier = Modifier.border(1.dp, AppColors.NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                OpenInNewIcon(modifier = Modifier.size(12.dp), color = AppColors.NeonGreen)
                                Spacer(Modifier.width(4.dp))
                                Text(I18n.btnViewGainmapImage(language), fontSize = 12.sp)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Navigation TabRow
                        TabRow(
                            selectedTabIndex = activeTab,
                            containerColor = AppColors.Panel,
                            contentColor = AppColors.NeonOrange,
                        ) {
                            Tab(
                                selected = activeTab == 0,
                                onClick = { activeTab = 0 },
                                text = { Text(I18n.tabGainmapParameters(language), style = AppTypography.labelLarge) },
                            )
                            Tab(
                                selected = activeTab == 1,
                                onClick = { activeTab = 1 },
                                text = { Text(I18n.tabGainmapRawXmp(language), style = AppTypography.labelLarge) },
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        // Main Content Area
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                        ) {
                            when (activeTab) {
                                0 -> GainmapParametersCardView(gainmap, language)
                                1 -> {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Sub-tabs for Secondary vs Primary XMP if both exist
                                        if (gainmap.secondaryXmp != null && gainmap.primaryXmp != null) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(AppColors.Panel.copy(alpha = 0.7f))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .clickable { selectedXmpSource = 0 }
                                                        .background(if (selectedXmpSource == 0) AppColors.NeonOrange.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        I18n.tabGainmapSecondaryXmp(language),
                                                        style = AppTypography.labelMedium.copy(
                                                            color = if (selectedXmpSource == 0) AppColors.NeonOrange else AppColors.TextSecondary,
                                                            fontSize = 11.sp,
                                                        ),
                                                    )
                                                }
                                                Spacer(Modifier.width(8.dp))
                                                Row(
                                                    modifier = Modifier
                                                        .clickable { selectedXmpSource = 1 }
                                                        .background(if (selectedXmpSource == 1) AppColors.NeonOrange.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(4.dp))
                                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                                ) {
                                                    Text(
                                                        I18n.tabGainmapPrimaryXmp(language),
                                                        style = AppTypography.labelMedium.copy(
                                                            color = if (selectedXmpSource == 1) AppColors.NeonOrange else AppColors.TextSecondary,
                                                            fontSize = 11.sp,
                                                        ),
                                                    )
                                                }
                                            }
                                        }

                                        val vScroll = rememberScrollState()
                                        val hScroll = rememberScrollState()

                                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            SelectionContainer {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .verticalScroll(vScroll)
                                                        .horizontalScroll(hScroll)
                                                        .padding(12.dp),
                                                ) {
                                                    Text(
                                                        text = if (currentRawXmp.isNotBlank()) currentRawXmp else "(No XMP metadata XML found)",
                                                        style = AppTypography.bodyMedium.copy(
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 12.sp,
                                                            lineHeight = 18.sp,
                                                            color = AppColors.TextPrimary,
                                                        ),
                                                    )
                                                }
                                            }
                                            VerticalScrollbar(
                                                adapter = rememberScrollbarAdapter(vScroll),
                                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                            )
                                            HorizontalScrollbar(
                                                adapter = rememberScrollbarAdapter(hScroll),
                                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Bottom Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (currentRawXmp.isNotBlank()) "XMP size: ${formatBytes(currentRawXmp.length.toLong())} · ${currentRawXmp.lines().size} lines" else "",
                                style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )

                            Row {
                                if (currentRawXmp.isNotBlank()) {
                                    Button(
                                        onClick = {
                                            if (ClipboardUtil.copyToClipboard(currentRawXmp)) {
                                                copied = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (copied) AppColors.NeonGreen.copy(alpha = 0.8f) else AppColors.Panel,
                                            contentColor = if (copied) Color.Black else AppColors.TextPrimary,
                                        ),
                                        modifier = Modifier.border(1.dp, if (copied) AppColors.NeonGreen else AppColors.Border, RoundedCornerShape(4.dp)),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(if (copied) "✓ 복사 완료" else I18n.btnCopyXmp(language), fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Button(
                                    onClick = onCloseRequest,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                                    modifier = Modifier.border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("닫기", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
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

/**
 * Window dialog for "Gain Map Image (Popup)..."
 * Renders the extracted gainmap image with full PixelInspectorPreview (zoom/pan, pixel inspector, save to disk).
 */
@Composable
fun GainmapImagePopupWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    language: AppLanguage = AppLanguage.KO,
    onOpenXmp: () -> Unit = {},
    onCloseRequest: () -> Unit,
) {
    val gainmap = tab.gainmapInfo ?: return
    val windowState = rememberWindowState(
        size = DpSize(1000.dp, 750.dp),
        position = WindowPosition(Alignment.Center),
    )

    // Decode gainmap bitmap asynchronously if needed
    var gainmapBitmap by remember(tab.file, gainmap) { mutableStateOf(tab.gainmapBitmap) }
    var isDecoding by remember(tab.file, gainmap) { mutableStateOf(gainmapBitmap == null) }

    LaunchedEffect(tab.file, gainmap) {
        if (gainmapBitmap == null) {
            isDecoding = true
            GainmapParser.decodeGainmapBitmapAsync(tab.file, tab.root, gainmap) { decoded ->
                gainmapBitmap = decoded
                tab.gainmapBitmap = decoded
                isDecoding = false
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${I18n.titleGainmapImageWindow(language)} - ${tab.file.name}",
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
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = gainmapBitmap
            if (bitmap != null) {
                PixelInspectorPreview(
                    bitmap = bitmap,
                    modifier = Modifier.fillMaxSize(),
                )

                // Top Floating Badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.NeonOrange.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "🔆 GAIN MAP IMAGE (${bitmap.width}x${bitmap.height})",
                        style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonOrange, fontWeight = FontWeight.Bold),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        gainmap.formatType.displayName,
                        style = AppTypography.bodySmall.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                    )
                }

                // Bottom Floating Control Bar
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val caption = "${bitmap.width}x${bitmap.height} · Format: ${gainmap.imageFormat ?: "JPEG"} · ${gainmap.summaryDescription}"
                    PreviewCaption(caption)

                    Row {
                        Button(
                            onClick = onOpenXmp,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonOrange),
                            modifier = Modifier.border(1.dp, AppColors.NeonOrange.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(I18n.btnViewXmp(language), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                saveGainmapImageWithDialog(tab.file, tab.root, gainmap, language)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonGreen),
                            modifier = Modifier.border(1.dp, AppColors.NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(I18n.btnSaveGainmapImage(language), fontSize = 12.sp)
                        }
                    }
                }
            } else if (isDecoding) {
                DecodingIndicator("게인맵 이미지 디코딩 중...", modifier = Modifier.align(Alignment.Center))
            } else if (!gainmap.hasGainmapImage) {
                // CASE 1: 게인맵 이미지 데이터가 아예 존재하지 않는 경우 (XMP 파라미터만 있는 경우)
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "No Gainmap Image",
                        tint = AppColors.NeonBlue,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        I18n.msgNoGainmapImage(language),
                        style = AppTypography.titleMedium.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        I18n.descNoGainmapImage(language),
                        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenXmp,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonOrange),
                        modifier = Modifier.border(1.dp, AppColors.NeonOrange.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(I18n.btnViewXmp(language), fontSize = 12.sp)
                    }
                }
            } else {
                // CASE 2: 게인맵 이미지 데이터는 존재하지만 디코딩에 실패한 경우
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Cannot Decode Gainmap",
                        tint = AppColors.NeonRed,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        I18n.msgCannotDecodeGainmap(language),
                        style = AppTypography.titleMedium.copy(color = AppColors.NeonRed, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        I18n.descCannotDecodeGainmap(language, gainmap.imageFormat ?: "HEVC/JPEG"),
                        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenXmp,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                        modifier = Modifier.border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(I18n.btnViewXmp(language), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun saveGainmapImageWithDialog(
    file: File,
    root: com.multiviewer.parser.BoxNode?,
    info: GainmapInfo,
    language: AppLanguage,
) {
    val dialog = FileDialog(null as Frame?, I18n.saveGainmapDialogTitle(language), FileDialog.SAVE)
    val ext = if (info.imageFormat?.contains("hvc", ignoreCase = true) == true) "png" else "jpg"
    dialog.file = "${file.nameWithoutExtension}_gainmap.$ext"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName != null && directory != null) {
        val destination = File(directory, fileName)
        GainmapParser.extractGainmapImageToFile(file, root, info, destination)
    }
}

@Composable
private fun OpenInNewIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.45f, h * 0.15f)
            lineTo(w * 0.15f, h * 0.15f)
            lineTo(w * 0.15f, h * 0.85f)
            lineTo(w * 0.85f, h * 0.85f)
            lineTo(w * 0.85f, h * 0.55f)
        }
        drawPath(path, color, style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.55f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), strokeWidth = 1.5f)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.55f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), strokeWidth = 1.5f)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.45f), strokeWidth = 1.5f)
    }
}
