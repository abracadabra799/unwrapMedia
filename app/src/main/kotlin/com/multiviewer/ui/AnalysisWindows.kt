package com.multiviewer.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.AiDiagnosticPromptBuilder
import com.multiviewer.cli.buildCheckJson
import com.multiviewer.cli.buildDumpJson
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import com.multiviewer.ui.terminal.EmbeddedTerminalPanel
import com.multiviewer.ui.terminal.SessionState
import com.multiviewer.ui.terminal.WindowsPtyCliSession
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

    var editingService by remember { mutableStateOf<WebAiService?>(null) }
    var customUrlInput by remember { mutableStateOf("") }

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
 * Window dialog for "구조 정합성 검사" (Validate Structure).
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

    var editingService by remember { mutableStateOf<WebAiService?>(null) }
    var customUrlInput by remember { mutableStateOf("") }

    Window(
        onCloseRequest = onCloseRequest,
        title = "Validate Structure - ${tab.file.name}",
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
                                        "Structure Integrity",
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
 * A small confirm/cancel prompt rendered as a real OS dialog window. Used by the
 * embedded-terminal flow because a `SwingPanel` always paints above Compose
 * content, so an in-window Compose dialog overlapping the terminal is invisible.
 */
@Composable
private fun CliConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    themeMode: ThemeMode,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(430.dp, 190.dp), position = WindowPosition(Alignment.Center)),
        title = title,
    ) {
        AppTheme(mode = themeMode, showPixelGrid = false) {
            Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        message,
                        style = AppTypography.bodyMedium.copy(fontSize = 13.sp, color = AppColors.TextPrimary),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("취소", fontSize = 12.sp, color = AppColors.TextSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onConfirm,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.NeonPurple,
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(4.dp),
                        ) { Text(confirmLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

/**
 * Window dialog for "Generate AI Prompt..."
 * Displays the AI diagnostic prompt ready to be sent to Claude / ChatGPT / Gemini.
 */
@Composable
fun AiPromptPreviewWindow(
    tab: TabState,
    initialTargetWarning: com.multiviewer.parser.WarningEntry? = null,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(920.dp, 700.dp),
        position = WindowPosition(Alignment.Center),
    )

    val root = tab.root
    val allWarnings = remember(root) { root?.let { collectWarnings(it) } ?: emptyList() }
    var selectedWarning by remember(initialTargetWarning) { mutableStateOf(initialTargetWarning) }

    val promptText = remember(tab.file, tab.root, tab.avSyncReport, selectedWarning) {
        AiDiagnosticPromptBuilder.buildPrompt(
            file = tab.file,
            root = root,
            warnings = allWarnings,
            avSyncReport = tab.avSyncReport,
            targetWarning = selectedWarning,
        )
    }

    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    var editingService by remember { mutableStateOf<WebAiService?>(null) }
    var customUrlInput by remember { mutableStateOf("") }

    var statusMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(3000)
            statusMessage = null
        }
    }

    val isWindows = remember { System.getProperty("os.name").lowercase().contains("win") }
    var activeCliSession by remember { mutableStateOf<WindowsPtyCliSession?>(null) }
    var terminalHeight by remember { mutableStateOf(320.dp) }
    var pendingSwitchCli by remember { mutableStateOf<com.multiviewer.util.AiCliType?>(null) }
    var confirmCloseWhileRunning by remember { mutableStateOf(false) }
    // How much the window was grown for the terminal, so the exact amount can be
    // subtracted back on end — preserving any manual resize done in between.
    var windowGrowth by remember { mutableStateOf(0.dp) }
    // Grown window must stay inside the usable screen (1366x768 laptops are a
    // target). Compose Desktop's WindowState.size Dp == raw AWT px, and
    // maximumWindowBounds is in that same unit, so `.dp` is the right conversion.
    val maxWindowHeight = remember {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds.height.dp
    }

    fun startCliSession(cli: com.multiviewer.util.AiCliType): String? {
        val bin = com.multiviewer.util.AiCliDetector.findBinary(cli.binaryName)
            ?: return "${cli.binaryName} 실행 파일을 찾을 수 없습니다"
        val s = WindowsPtyCliSession(cli, bin, tab.file.parentFile, promptText)
        s.start()
        if (s.state is SessionState.Failed) {
            com.multiviewer.util.AiCliDetector.launchInteractiveCli(cli, promptText, tab.file.parentFile)
            return "임베드 터미널 실패 — 외부 창으로 실행"
        }
        // tear down any prior session (e.g. one that already exited) before replacing it
        activeCliSession?.destroy()
        activeCliSession = s
        val current = windowState.size.height
        // never below `current` — on a very short screen the cap can be < current,
        // and a negative growth would shrink the window as the terminal appears.
        val target = (current + terminalHeight + 48.dp).coerceIn(current, maxOf(maxWindowHeight, current))
        windowGrowth = target - current
        windowState.size = windowState.size.copy(height = target)
        return "${cli.displayName} 임베드 세션 시작 (프롬프트 자동 입력 예정)"
    }

    fun endCliSession() {
        activeCliSession?.destroy()
        activeCliSession = null
        windowState.size = windowState.size.copy(
            height = (windowState.size.height - windowGrowth).coerceAtLeast(400.dp),
        )
        windowGrowth = 0.dp
    }

    val requestClose: () -> Unit = {
        if (activeCliSession?.isAlive == true) {
            confirmCloseWhileRunning = true
        } else {
            activeCliSession?.destroy()
            onCloseRequest()
        }
    }

    DisposableEffect(Unit) {
        onDispose { activeCliSession?.destroy() }
    }

    Window(
        onCloseRequest = requestClose,
        title = "AI Analysis Prompt - ${tab.file.name}",
        state = windowState,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                requestClose()
                true
            } else {
                false
            }
        },
    ) {
        AppTheme(mode = themeMode, showPixelGrid = false) {
            CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
                Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                  Box(Modifier.fillMaxSize()) {
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

                        // Scope Selector (All vs specific warning)
                        if (allWarnings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "진단 범위:",
                                    style = AppTypography.labelSmall.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                                )
                                // All Warnings chip
                                FilterChip(
                                    selected = selectedWarning == null,
                                    onClick = { selectedWarning = null },
                                    label = { Text("전체 결함 종합 진단 (${allWarnings.size}건)", fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppColors.NeonPurple.copy(alpha = 0.25f),
                                        selectedLabelColor = AppColors.NeonPurple,
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selectedWarning == null,
                                        borderColor = AppColors.Border,
                                        selectedBorderColor = AppColors.NeonPurple,
                                    ),
                                )
                                allWarnings.take(4).forEachIndexed { idx, w ->
                                    val isSelected = selectedWarning === w
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedWarning = w },
                                        label = { Text("#${idx + 1} ${w.node.type}", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AppColors.NeonPurple.copy(alpha = 0.25f),
                                            selectedLabelColor = AppColors.NeonPurple,
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = AppColors.Border,
                                            selectedBorderColor = AppColors.NeonPurple,
                                        ),
                                    )
                                }
                                if (allWarnings.size > 4 && selectedWarning != null && allWarnings.indexOf(selectedWarning) >= 4) {
                                    val idx = allWarnings.indexOf(selectedWarning)
                                    FilterChip(
                                        selected = true,
                                        onClick = {},
                                        label = { Text("#${idx + 1} ${selectedWarning?.node?.type}", fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AppColors.NeonPurple.copy(alpha = 0.25f),
                                            selectedLabelColor = AppColors.NeonPurple,
                                        ),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

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
                        val availableClis = remember {
                            com.multiviewer.util.AiCliType.entries.filter { it.isAvailable }
                        }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Prompt size: ${formatBytes(promptText.length.toLong())} (${promptText.length} chars)",
                                        style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextSecondary),
                                    )
                                    if (statusMessage != null) {
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            statusMessage ?: "",
                                            style = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.NeonGreen, fontWeight = FontWeight.Bold),
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Web AI Links (copies to clipboard and opens Chrome/browser with company login)
                                    Text("Open Web (Chrome):", style = AppTypography.labelSmall.copy(fontSize = 11.sp, color = AppColors.TextSecondary))
                                    Spacer(Modifier.width(4.dp))
                                    WebAiService.entries.forEach { service ->
                                        OutlinedButton(
                                            onClick = {
                                                if (editingService == service) {
                                                    editingService = null
                                                } else {
                                                    editingService = service
                                                    customUrlInput = AiWebPreferences.getUrl(service)
                                                }
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (editingService == service) AppColors.NeonPurple.copy(alpha = 0.25f) else Color.Transparent,
                                            ),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (editingService == service) AppColors.NeonPurple else AppColors.Border
                                            ),
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                service.displayName + if (editingService == service) " ▾" else "",
                                                fontSize = 11.sp,
                                                color = if (editingService == service) AppColors.NeonPurple else AppColors.TextPrimary,
                                            )
                                        }
                                        Spacer(Modifier.width(4.dp))
                                    }
                                }
                            }

                            if (editingService != null) {
                                val currentService = editingService!!
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(AppColors.Panel, RoundedCornerShape(6.dp))
                                        .border(1.dp, AppColors.NeonPurple.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        "${currentService.displayName} 웹 주소:",
                                        style = AppTypography.labelSmall.copy(fontSize = 11.sp, color = AppColors.NeonPurple, fontWeight = FontWeight.Bold),
                                    )
                                    OutlinedTextField(
                                        value = customUrlInput,
                                        onValueChange = { customUrlInput = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f).height(48.dp),
                                        textStyle = AppTypography.bodyMedium.copy(fontSize = 11.sp, color = AppColors.TextPrimary),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = AppColors.NeonPurple,
                                            unfocusedBorderColor = AppColors.Border,
                                            focusedContainerColor = AppColors.Background,
                                            unfocusedContainerColor = AppColors.Background,
                                        ),
                                        placeholder = { Text(currentService.defaultUrl, fontSize = 11.sp, color = AppColors.TextSecondary) },
                                    )
                                    Button(
                                        onClick = {
                                            val targetUrl = customUrlInput.trim().ifBlank { currentService.defaultUrl }
                                            AiWebPreferences.setUrl(currentService, targetUrl)
                                            ClipboardUtil.copyToClipboard(promptText)
                                            com.multiviewer.util.AiCliDetector.openWebAi(targetUrl)
                                            statusMessage = "복사됨 & ${currentService.displayName} (Chrome) 열림"
                                            editingService = null
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AppColors.NeonPurple,
                                            contentColor = Color.White,
                                        ),
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text("연결 (Open)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    if (customUrlInput.trim() != currentService.defaultUrl && customUrlInput.isNotBlank()) {
                                        OutlinedButton(
                                            onClick = {
                                                AiWebPreferences.resetUrl(currentService)
                                                customUrlInput = currentService.defaultUrl
                                            },
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text("기본값", fontSize = 10.sp, color = AppColors.TextSecondary)
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { editingService = null },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text("닫기", fontSize = 10.sp, color = AppColors.TextSecondary)
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                // Local CLI Buttons (only if detected on current machine)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (availableClis.isNotEmpty()) {
                                        Text("Local CLI:", style = AppTypography.labelSmall.copy(fontSize = 11.sp, color = AppColors.NeonPurple, fontWeight = FontWeight.Bold))
                                        Spacer(Modifier.width(6.dp))
                                        availableClis.forEach { cli ->
                                            Button(
                                                onClick = {
                                                    ClipboardUtil.copyToClipboard(promptText)
                                                    if (isWindows) {
                                                        if (activeCliSession?.isAlive == true) {
                                                            pendingSwitchCli = cli
                                                        } else {
                                                            statusMessage = startCliSession(cli)
                                                        }
                                                    } else {
                                                        val success = com.multiviewer.util.AiCliDetector.launchInteractiveCli(
                                                            cli,
                                                            promptText,
                                                            tab.file.parentFile,
                                                        )
                                                        statusMessage = if (success) {
                                                            "${cli.displayName} 터미널 실행됨 (전체 프롬프트 클립보드 복사 완료: 붙여넣기 가능)"
                                                        } else {
                                                            "${cli.displayName} 실행 실패"
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = AppColors.NeonPurple.copy(alpha = 0.2f),
                                                    contentColor = AppColors.NeonPurple,
                                                ),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.NeonPurple),
                                                modifier = Modifier.height(30.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text("▶ ${cli.displayName}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                            Spacer(Modifier.width(6.dp))
                                        }
                                    }
                                }

                                // Copy and Close Buttons
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                        modifier = Modifier.height(32.dp).border(1.dp, if (copied) AppColors.NeonGreen else AppColors.NeonPurple, RoundedCornerShape(4.dp)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text(if (copied) "✓ Copied to Clipboard" else "Copy Prompt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = requestClose,
                                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Panel, contentColor = AppColors.TextPrimary),
                                        modifier = Modifier.height(32.dp).border(1.dp, AppColors.Border, RoundedCornerShape(4.dp)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                        shape = RoundedCornerShape(4.dp),
                                    ) {
                                        Text("Close", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        if (isWindows && activeCliSession != null) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .background(AppColors.Border, RoundedCornerShape(3.dp))
                                    .pointerHoverIcon(
                                        PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)),
                                    )
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            // PointerInputScope implements Density
                                            terminalHeight = (terminalHeight - dragAmount.y.toDp())
                                                .coerceIn(180.dp, 640.dp)
                                        }
                                    },
                            )
                            Spacer(Modifier.height(4.dp))
                            // key on the session instance so a CLI switch fully
                            // remounts the panel (its SwingPanel factory binds the
                            // connector once and is not re-invoked on recomposition)
                            key(activeCliSession) {
                                EmbeddedTerminalPanel(
                                    session = activeCliSession!!,
                                    onEndSession = { endCliSession() },
                                    modifier = Modifier.fillMaxWidth().height(terminalHeight),
                                )
                            }
                        }
                    }

                    // Real OS dialog windows, not in-window Compose layers: the
                    // SwingPanel terminal always paints on top of Compose content,
                    // so an AlertDialog overlapping it would be invisible.
                    pendingSwitchCli?.let { next ->
                        CliConfirmDialog(
                            title = "세션 전환",
                            message = "현재 실행 중인 ${activeCliSession?.displayName ?: ""} 세션을 종료하고 ${next.displayName}(으)로 전환할까요?",
                            confirmLabel = "전환",
                            themeMode = themeMode,
                            onConfirm = {
                                pendingSwitchCli = null
                                endCliSession()
                                ClipboardUtil.copyToClipboard(promptText)
                                statusMessage = startCliSession(next)
                            },
                            onDismiss = { pendingSwitchCli = null },
                        )
                    }
                    if (confirmCloseWhileRunning) {
                        CliConfirmDialog(
                            title = "세션 종료",
                            message = "실행 중인 CLI 세션을 종료하고 창을 닫습니다.",
                            confirmLabel = "종료 후 닫기",
                            themeMode = themeMode,
                            onConfirm = {
                                confirmCloseWhileRunning = false
                                activeCliSession?.destroy()
                                activeCliSession = null
                                onCloseRequest()
                            },
                            onDismiss = { confirmCloseWhileRunning = false },
                        )
                    }
                  }
                }
            }
        }
    }
}
