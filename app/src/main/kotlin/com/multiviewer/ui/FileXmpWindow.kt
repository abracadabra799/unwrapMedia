package com.multiviewer.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.multiviewer.parser.XmpPacket
import com.multiviewer.parser.scanXmpPackets
import com.multiviewer.parser.xmpPacketTopics
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import java.util.Locale

// A short, distinguishing name for one packet's tab. The schemas it carries say far more about
// which packet this is than a bare number does -- on a real gain map file the two packets read
// "Container, MotionPhoto" and "GainMap, hdrgm" respectively.
internal fun xmpTabLabel(packet: XmpPacket): String {
    val topics = xmpPacketTopics(packet.text)
    val displayTopics = when {
        topics.contains("Primary") && topics.contains("MotionPhoto") -> listOf("Primary", "MotionPhoto")
        topics.contains("Primary") && topics.contains("Depth") -> listOf("Primary", "Depth")
        topics.contains("Primary") -> listOf("Primary", "Container")
        topics.contains("GainMap") && topics.contains("hdrgm") -> listOf("GainMap", "hdrgm")
        topics.contains("GainMap") -> listOf("GainMap")
        else -> topics.take(2)
    }
    val suffix = if (displayTopics.isEmpty()) "" else " · ${displayTopics.joinToString(", ")}"
    return "XMP ${packet.index + 1}$suffix"
}

internal fun xmpPacketSubtitle(packet: XmpPacket): String =
    String.format(Locale.US, "offset 0x%X · %,d bytes", packet.offset, packet.byteLength)

/**
 * Window for "파일 내 XMP 정보" -- every XMP packet physically present in the file, one tab each.
 *
 * Distinct from [GainmapXmpWindow], which interprets the gain map's own XMP: this one makes no
 * assumption about what the packets are for, so it also surfaces the packets no other view reaches
 * (notably the one inside an embedded gain map image).
 */
@Composable
fun FileXmpWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    language: AppLanguage = AppLanguage.KO,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(920.dp, 700.dp),
        position = WindowPosition(Alignment.Center),
    )

    // Scanning re-reads the file, so it happens off the UI thread and only when the file changes.
    var packets by remember(tab.file) { mutableStateOf<List<XmpPacket>?>(null) }
    LaunchedEffect(tab.file) {
        packets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanXmpPackets(tab.file)
        }
    }

    var activeTab by remember(tab.file) { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${I18n.titleFileXmpWindow(language)} - ${tab.file.name}",
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
                        val found = packets

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    I18n.titleFileXmpWindow(language),
                                    style = AppTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                    color = AppColors.NeonOrange,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (found == null) {
                                        "${tab.file.name} · ${I18n.labelFileXmpScanning(language)}"
                                    } else {
                                        "${tab.file.name} · ${I18n.labelFileXmpCount(language, found.size)}"
                                    },
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }
                            val current = found?.getOrNull(activeTab)
                            if (current != null) {
                                Button(
                                    onClick = { copied = ClipboardUtil.copyToClipboard(current.text) },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.NeonGreen),
                                    modifier = Modifier.border(1.dp, AppColors.NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        if (copied) I18n.btnCopied(language) else I18n.btnCopyXmp(language),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        when {
                            found == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    I18n.labelFileXmpScanning(language),
                                    style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary),
                                )
                            }

                            found.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    I18n.labelFileXmpNone(language),
                                    style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary),
                                )
                            }

                            else -> {
                                val selected = activeTab.coerceIn(0, found.size - 1)
                                ScrollableTabRow(
                                    selectedTabIndex = selected,
                                    containerColor = AppColors.Panel,
                                    contentColor = AppColors.NeonOrange,
                                    edgePadding = 0.dp,
                                ) {
                                    found.forEachIndexed { index, packet ->
                                        Tab(
                                            selected = selected == index,
                                            onClick = { activeTab = index },
                                            text = {
                                                Text(
                                                    xmpTabLabel(packet),
                                                    style = AppTypography.labelLarge,
                                                    maxLines = 1,
                                                )
                                            },
                                        )
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    xmpPacketSubtitle(found[selected]),
                                    style = AppTypography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = AppColors.TextSecondary,
                                    ),
                                )

                                Spacer(Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                                ) {
                                    val verticalScroll = rememberScrollState()
                                    val horizontalScroll = rememberScrollState()
                                    val pretty = remember(found[selected]) { prettyPrintXmlOrRaw(found[selected].text) }
                                    SelectionContainer {
                                        Text(
                                            pretty,
                                            style = AppTypography.bodyLarge.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = AppColors.TextPrimary,
                                            ),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(verticalScroll)
                                                .horizontalScroll(horizontalScroll)
                                                .padding(12.dp),
                                        )
                                    }
                                    VerticalScrollbar(
                                        adapter = rememberScrollbarAdapter(verticalScroll),
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
