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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.multiviewer.cli.AiDiagnosticPromptBuilder
import com.multiviewer.cli.buildCheckJson
import com.multiviewer.cli.buildDumpJson
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
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
 * Window dialog for "Dump Structure..."
 * Displays the complete JSON structure of the media container / boxes.
 */
@Composable
fun StructureDumpWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(880.dp, 660.dp),
        position = WindowPosition(Alignment.Center),
    )

    val jsonText = remember(tab.file, tab.root) {
        val root = tab.root
        if (root != null) {
            try {
                buildDumpJson(tab.file, root)
            } catch (e: Exception) {
                "{\n  \"error\": \"Failed to dump structure: ${e.message ?: e.toString()}\"\n}"
            }
        } else {
            "{\n  \"status\": \"File structure is loading or unavailable.\"\n}"
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
        title = "Structure Dump - ${tab.file.name}",
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
                                        "Structure Dump",
                                        style = AppTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        color = AppColors.NeonBlue,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text("JSON", style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${tab.file.name} · ${formatBytes(tab.file.length())}",
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Code Viewer Area
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                        ) {
                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(vScroll)
                                        .horizontalScroll(hScroll)
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = jsonText,
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

                        Spacer(Modifier.height(12.dp))

                        // Bottom Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Dump size: ${formatBytes(jsonText.length.toLong())} · ${jsonText.lines().size} lines",
                                style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )

                            Row {
                                Button(
                                    onClick = {
                                        if (ClipboardUtil.copyToClipboard(jsonText)) {
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
                                    Text(if (copied) "✓ Copied to Clipboard" else "Copy JSON", fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = onCloseRequest,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                                    modifier = Modifier.border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("Close", fontSize = 12.sp)
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
 * Window dialog for "Check Structure..."
 * Displays structural defects, anomalies, warnings and severity levels.
 */
@Composable
fun StructureCheckWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(880.dp, 660.dp),
        position = WindowPosition(Alignment.Center),
    )

    val warnings = remember(tab.root) {
        tab.root?.let { collectWarnings(it) } ?: emptyList()
    }
    val checkJson = remember(tab.file, warnings) {
        buildCheckJson(tab.file, warnings)
    }

    var viewMode by remember { mutableStateOf(0) } // 0: Issues list, 1: Raw JSON
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "Check Structure - ${tab.file.name}",
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
                                        "Structure Check",
                                        style = AppTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        color = if (warnings.isEmpty()) AppColors.NeonGreen else AppColors.NeonYellow,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    val badgeColor = if (warnings.isEmpty()) AppColors.NeonGreen else AppColors.NeonRed
                                    Box(
                                        modifier = Modifier
                                            .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .border(1.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            if (warnings.isEmpty()) "✓ CLEAN (0 ISSUES)" else "${warnings.size} WARNINGS",
                                            style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = badgeColor, fontWeight = FontWeight.Bold),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${tab.file.name} · ${formatBytes(tab.file.length())}",
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }

                            // View mode selector
                            Row(
                                modifier = Modifier
                                    .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                    .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                    .padding(2.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(if (viewMode == 0) AppColors.Surface else Color.Transparent, RoundedCornerShape(3.dp))
                                        .clickable { viewMode = 0 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Issues View",
                                        style = AppTypography.labelSmall.copy(
                                            color = if (viewMode == 0) AppColors.NeonBlue else AppColors.TextSecondary,
                                            fontWeight = if (viewMode == 0) FontWeight.Bold else FontWeight.Normal,
                                        ),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(if (viewMode == 1) AppColors.Surface else Color.Transparent, RoundedCornerShape(3.dp))
                                        .clickable { viewMode = 1 }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Raw JSON",
                                        style = AppTypography.labelSmall.copy(
                                            color = if (viewMode == 1) AppColors.NeonBlue else AppColors.TextSecondary,
                                            fontWeight = if (viewMode == 1) FontWeight.Bold else FontWeight.Normal,
                                        ),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Body View
                        if (viewMode == 0) {
                            // Issues card list view
                            if (warnings.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("✓", fontSize = 48.sp, color = AppColors.NeonGreen)
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            "No structural defects or warnings detected.",
                                            style = AppTypography.titleMedium,
                                            color = AppColors.TextPrimary,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "This media file strictly conforms to container box & table specifications.",
                                            style = AppTypography.bodyMedium,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                }
                            } else {
                                val listState = rememberLazyListState()
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                                ) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        items(warnings) { w ->
                                            WarningCard(w)
                                        }
                                    }
                                    VerticalScrollbar(
                                        adapter = rememberScrollbarAdapter(listState),
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    )
                                }
                            }
                        } else {
                            // Raw JSON text view
                            val vScroll = rememberScrollState()
                            val hScroll = rememberScrollState()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                    .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                            ) {
                                SelectionContainer {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .verticalScroll(vScroll)
                                            .horizontalScroll(hScroll)
                                            .padding(12.dp),
                                    ) {
                                        Text(
                                            text = checkJson,
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

                        Spacer(Modifier.height(12.dp))

                        // Bottom Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${warnings.size} warning(s) found",
                                style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )

                            Row {
                                Button(
                                    onClick = {
                                        val contentToCopy = if (viewMode == 1) checkJson else buildCheckTextReport(tab.file, warnings)
                                        if (ClipboardUtil.copyToClipboard(contentToCopy)) {
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
                                    Text(if (copied) "✓ Copied to Clipboard" else "Copy Report", fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = onCloseRequest,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                                    modifier = Modifier.border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("Close", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningCard(warningEntry: WarningEntry) {
    val severity = AiDiagnosticPromptBuilder.determineSeverity(warningEntry.node.type, warningEntry.warning)
    val (badgeBg, badgeBorder, badgeTextColor) = when (severity) {
        "CRITICAL" -> Triple(AppColors.NeonRed.copy(alpha = 0.2f), AppColors.NeonRed, AppColors.NeonRed)
        "WARNING" -> Triple(AppColors.NeonYellow.copy(alpha = 0.2f), AppColors.NeonYellow, AppColors.NeonYellow)
        else -> Triple(AppColors.NeonBlue.copy(alpha = 0.2f), AppColors.NeonBlue, AppColors.NeonBlue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(badgeBg, RoundedCornerShape(3.dp))
                        .border(1.dp, badgeBorder, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(severity, style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = badgeTextColor, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Box: ${warningEntry.node.type}",
                    style = AppTypography.titleMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary),
                )
            }

            Text(
                "Offset: 0x${warningEntry.node.offset.toString(16).uppercase(Locale.US)} (${warningEntry.node.offset}) · Size: ${warningEntry.node.size}B",
                style = AppTypography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AppColors.TextSecondary),
            )
        }

        Spacer(Modifier.height(6.dp))

        SelectionContainer {
            Text(
                text = warningEntry.warning,
                style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextPrimary),
            )
        }
    }
}

private fun buildCheckTextReport(file: java.io.File, warnings: List<WarningEntry>): String {
    val sb = StringBuilder()
    sb.appendLine("unwrapMedia Check Report")
    sb.appendLine("File: ${file.name} (${file.length()} bytes)")
    sb.appendLine("Status: ${if (warnings.isEmpty()) "Clean (0 warnings)" else "${warnings.size} warning(s) found"}")
    sb.appendLine("=".repeat(60))
    if (warnings.isEmpty()) {
        sb.appendLine("No structural defects or anomalies detected.")
    } else {
        warnings.forEachIndexed { i, w ->
            val severity = AiDiagnosticPromptBuilder.determineSeverity(w.node.type, w.warning)
            sb.appendLine("[$severity #${i + 1}]")
            sb.appendLine("  Box   : ${w.node.type}")
            sb.appendLine("  Offset: ${w.node.offset} (0x${w.node.offset.toString(16)})")
            sb.appendLine("  Size  : ${w.node.size}")
            sb.appendLine("  Issue : ${w.warning}")
            sb.appendLine()
        }
    }
    return sb.toString()
}

/**
 * Window dialog for "Generate AI Prompt..."
 * Displays the AI diagnostic prompt ready to be sent to Claude / ChatGPT / Gemini.
 */
@Composable
fun AiPromptPreviewWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(920.dp, 700.dp),
        position = WindowPosition(Alignment.Center),
    )

    val promptText = remember(tab.file, tab.root) {
        val root = tab.root
        val warnings = root?.let { collectWarnings(it) } ?: emptyList()
        AiDiagnosticPromptBuilder.buildPrompt(tab.file, root, warnings)
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
        title = "AI Analysis Prompt - ${tab.file.name}",
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
                                        "AI Diagnostic Prompt",
                                        style = AppTypography.headlineSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                                        color = AppColors.NeonPurple,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text("Claude / ChatGPT / Gemini Ready", style = AppTypography.labelSmall.copy(fontSize = 10.sp, color = AppColors.NeonPurple))
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${tab.file.name} · Domain context and detected evidence included",
                                    style = AppTypography.bodyMedium.copy(fontSize = 12.sp, color = AppColors.TextSecondary),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Code Viewer Area
                        val vScroll = rememberScrollState()
                        val hScroll = rememberScrollState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF13161A), RoundedCornerShape(6.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)),
                        ) {
                            SelectionContainer {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(vScroll)
                                        .horizontalScroll(hScroll)
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = promptText,
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

                        Spacer(Modifier.height(12.dp))

                        // Bottom Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "Prompt size: ${formatBytes(promptText.length.toLong())} (${promptText.length} chars)",
                                style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                            )

                            Row {
                                Button(
                                    onClick = {
                                        if (ClipboardUtil.copyToClipboard(promptText)) {
                                            copied = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (copied) AppColors.NeonGreen.copy(alpha = 0.8f) else AppColors.NeonPurple.copy(alpha = 0.8f),
                                        contentColor = if (copied) Color.Black else Color.White,
                                    ),
                                    modifier = Modifier.border(1.dp, if (copied) AppColors.NeonGreen else AppColors.NeonPurple, RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(if (copied) "✓ Copied to Clipboard" else "Copy Prompt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = onCloseRequest,
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                                    modifier = Modifier.border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("Close", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
