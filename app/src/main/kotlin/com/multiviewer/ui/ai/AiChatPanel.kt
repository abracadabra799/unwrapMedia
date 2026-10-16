package com.multiviewer.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.ui.AppColors
import com.multiviewer.ui.AppLanguage
import com.multiviewer.ui.AppState
import com.multiviewer.ui.ContentFileIdentity
import com.multiviewer.ui.TabState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The app-wide controller: its scope outlives the panel, so hiding the panel does not cancel a request
 * (closing the tab does, through TabState.aiJob).
 */
fun aiChatController(appState: AppState, scope: kotlinx.coroutines.CoroutineScope) = AiChatController(
    scope,
    language = { appState.aiLanguage },
    onCapabilityUpdate = { update ->
        appState.aiCapabilities[update.provider]?.let { current ->
            appState.aiCapabilities = appState.aiCapabilities + (update.provider to when {
                update.image != null -> current.copy(image = update.image, detail = update.detail)
                update.answered -> current.afterAnswer().let { c -> update.webSearch?.let { c.copy(webSearch = it) } ?: c }
                update.failure != null -> current.afterFailure(update.failure)
                else -> current
            })
        }
    },
    capabilities = { appState.aiCapabilities[it] },
    quality = { appState.lastQualityComparison },
)

/** Left edge of the AI panel: drag to resize (300–760 dp). */
@Composable
fun AiPanelResizeHandle(appState: AppState) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cursor = remember { PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR)) }
    Box(
        Modifier.width(6.dp).fillMaxHeight().background(AppColors.Border).pointerHoverIcon(cursor)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val deltaDp = with(density) { drag.x.toDp().value }
                    appState.aiPanelWidthDp = (appState.aiPanelWidthDp - deltaDp).coerceIn(300f, 760f)
                }
            },
    )
}

/** Finds each CLI and reads its version (local, no network). Never marks text/image/web as working. */
suspend fun discoverAiProviders(): Map<AiProviderId, AiProviderCapabilities> = withContext(Dispatchers.IO) {
    val dirs = cliSearchDirectories()
    AiProviderId.entries.associateWith { provider ->
        val exe = resolveExecutable(provider.command, dirs)
        AiProviderCapabilities(provider, executablePath = exe?.path, version = exe?.let { probeCliVersion(it) })
    }
}

private fun stateLabel(state: CapabilityState, ko: Boolean) = when (state) {
    CapabilityState.VERIFIED -> if (ko) "확인됨" else "verified"
    CapabilityState.UNSUPPORTED -> if (ko) "불가" else "unavailable"
    CapabilityState.UNKNOWN -> if (ko) "미확인" else "unverified"
}

/**
 * The collapsible right-side conversation for [tab]. Everything shown (transcript, run state, file warning)
 * belongs to that tab; the provider choice and capability probe results are app-wide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiChatPanel(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    controller: AiChatController,
    onOpenFallback: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val scope = rememberCoroutineScope()

    var rediscover by remember { mutableStateOf(0) }
    LaunchedEffect(rediscover) {
        if (appState.aiCapabilities.isEmpty() || rediscover > 0) {
            val found = discoverAiProviders()
            // Keep what was already verified for a CLI that is still at the same path.
            appState.aiCapabilities = found.mapValues { (p, fresh) ->
                appState.aiCapabilities[p]?.takeIf { it.executablePath == fresh.executablePath }?.copy(version = fresh.version) ?: fresh
            }
            if (appState.aiProvider == null) {
                appState.aiProvider = AiProviderId.entries.firstOrNull { found[it]?.executablePath != null }
            }
        }
    }

    // Changed-file warning for this tab, polled off the UI thread.
    var fileChanged by remember(tab) { mutableStateOf(false) }
    LaunchedEffect(tab, tab.parsedIdentity) {
        val parsed = tab.parsedIdentity ?: return@LaunchedEffect
        while (true) {
            fileChanged = withContext(Dispatchers.IO) { !tab.file.isFile || ContentFileIdentity.capture(tab.file) != parsed }
            delay(2_000)
        }
    }

    val conversation = tab.aiConversation
    // The first image send checks image input before the question is added, so that phase counts as running too.
    val running = conversation.status == AiRunStatus.RUNNING || tab.aiImageChecking || tab.aiFrameDecoding
    val provider = appState.aiProvider
    val caps = provider?.let { appState.aiCapabilities[it] }
    var input by remember(tab) { mutableStateOf("") }
    var preview by remember(tab) { mutableStateOf<String?>(null) }
    var expandedContext by remember(tab) { mutableStateOf<Long?>(null) }
    var showActivity by remember(tab) { mutableStateOf(false) }
    var providerMenu by remember { mutableStateOf(false) }
    val listState = remember(tab) { androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.size - 1)
    }

    fun send(text: String) {
        val p = provider ?: return
        controller.send(tab, text, p, appState.aiCapabilities[p]?.executablePath?.let { java.io.File(it) },
            webSearch = appState.aiWebSearch)
        input = ""
        preview = null
    }

    Column(
        modifier.fillMaxSize().background(AppColors.Panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. 상단 컨트롤 카드 (헤더 + 도구 선택 + 상태 + 부가 옵션)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface,
            border = BorderStroke(1.dp, AppColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 1행: 헤더 (제목 + 파일명 + 닫기 버튼)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label("AI와 대화", "Ask AI"), color = AppColors.NeonPurple, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AppColors.Background,
                        border = BorderStroke(1.dp, AppColors.Border.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            tab.file.name,
                            color = AppColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Text("✕", color = AppColors.TextMuted, fontSize = 12.sp)
                    }
                }

                // 2행: 도구 선택 드롭다운 + 상태 뱃지
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        OutlinedButton(
                            onClick = { providerMenu = true },
                            enabled = !running,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            val pName = provider?.displayName ?: label("도구 선택", "Select Tool")
                            val vText = caps?.version?.let { " ($it)" }.orEmpty()
                            Text("$pName$vText  ▾", fontSize = 11.sp, color = AppColors.TextPrimary)
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            for (p in AiProviderId.entries) {
                                val c = appState.aiCapabilities[p]
                                val status = when {
                                    c == null -> label("확인 중…", "checking…")
                                    c.executablePath == null -> label("설치 안 됨", "not installed")
                                    else -> c.version ?: label("버전 알 수 없음", "unknown version")
                                }
                                DropdownMenuItem(
                                    text = { Text("${p.displayName} — $status", fontSize = 12.sp) },
                                    enabled = c?.executablePath != null,
                                    onClick = { appState.aiProvider = p; providerMenu = false },
                                )
                            }
                        }
                    }

                    // 설치/준비 상태 뱃지
                    val isInstalled = caps?.executablePath != null
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isInstalled) AppColors.NeonGreen.copy(alpha = 0.12f) else AppColors.NeonOrange.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isInstalled) AppColors.NeonGreen.copy(alpha = 0.35f) else AppColors.NeonOrange.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        ) {
                            Box(
                                Modifier.size(6.dp).background(
                                    if (isInstalled) AppColors.NeonGreen else AppColors.NeonOrange,
                                    CircleShape,
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (isInstalled) label("준비됨", "Ready") else label("미설치", "Not Installed"),
                                color = if (isInstalled) AppColors.NeonGreen else AppColors.NeonOrange,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // Codex's read-only sandbox and Antigravity's plan mode can still read local files (Gemini runs with
                // its built-in tools off, Claude with --tools ""). Restores the note lost in 22e66a7e.
                if (provider == AiProviderId.CODEX || provider == AiProviderId.AGY) {
                    Text(
                        label(
                            "주의: 이 CLI는 읽기 전용 모드여도 이 컴퓨터의 다른 파일을 읽을 수 있습니다. 파일에서 나온 값은 지시가 아닌 데이터로 표시해 보내지만, 민감한 환경에서는 Claude Code(도구 꺼짐)를 권장합니다.",
                            "Note: even read-only, this CLI can read other files on this computer. File-derived values are fenced as data, but Claude Code (tools off) is safer for sensitive work.",
                        ),
                        color = AppColors.NeonYellow,
                        fontSize = 11.sp,
                    )
                }

                // 3행: 부가 옵션 (웹 검색 토글 & 이미지 영역 지정)
                val showWebSearch = provider != null
                val canChangeWebSearch = !running && caps?.executablePath != null
                val hasImageSource = tab.aiImageSource != null
                if (showWebSearch || hasImageSource) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 웹 검색 옵션 (충분한 독립 공간 확보 및 클릭 가능한 칩 스타일)
                        if (showWebSearch) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (appState.aiWebSearch) AppColors.NeonPurple.copy(alpha = 0.12f) else AppColors.Background,
                                border = BorderStroke(
                                    1.dp,
                                    if (appState.aiWebSearch) AppColors.NeonPurple.copy(alpha = 0.5f) else AppColors.Border,
                                ),
                                modifier = Modifier.clickable(enabled = canChangeWebSearch) {
                                    appState.aiWebSearch = !appState.aiWebSearch
                                },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Checkbox(
                                        checked = appState.aiWebSearch,
                                        enabled = canChangeWebSearch,
                                        onCheckedChange = { appState.aiWebSearch = it },
                                        modifier = Modifier.size(18.dp),
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = AppColors.NeonPurple,
                                            checkmarkColor = Color.White,
                                            uncheckedColor = AppColors.TextMuted,
                                        ),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (provider == AiProviderId.AGY) label("🌐 웹 검색 요청", "🌐 Request Web Search")
                                        else label("🌐 웹 검색", "🌐 Web Search"),
                                        color = if (appState.aiWebSearch) AppColors.NeonPurple else AppColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = if (appState.aiWebSearch) FontWeight.Medium else FontWeight.Normal,
                                        softWrap = false,
                                    )
                                }
                            }
                        }

                        // 이미지 화면 마킹 (이미지가 있을 때만 노출)
                        if (hasImageSource) {
                            Button(
                                onClick = { tab.aiMarkMode = !tab.aiMarkMode },
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (tab.aiMarkMode) AppColors.NeonPurple else AppColors.Background,
                                    contentColor = if (tab.aiMarkMode) Color.White else AppColors.TextPrimary,
                                ),
                                border = BorderStroke(1.dp, if (tab.aiMarkMode) AppColors.NeonPurple else AppColors.Border),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text(
                                    if (tab.aiMarkMode) label("✓ 지정 모드 켬", "✓ Marking On") else label("🎯 영역 지정", "🎯 Mark Region"),
                                    fontSize = 11.sp,
                                    softWrap = false,
                                )
                            }

                            val hasMark = tab.aiPointer != null || tab.aiMarked != null
                            if (hasMark) {
                                val markText = tab.aiMarked?.let { "${it.width}×${it.height}" } ?: tab.aiPointer?.let { "${it.x},${it.y}" }.orEmpty()
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = AppColors.NeonPurple.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, AppColors.NeonPurple.copy(alpha = 0.3f)),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                                        Text(markText, color = AppColors.NeonPurple, fontSize = 10.sp, softWrap = false)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "✕",
                                            color = AppColors.TextMuted,
                                            fontSize = 10.sp,
                                            modifier = Modifier.clickable { tab.aiPointer = null; tab.aiMarked = null },
                                        )
                                    }
                                }
                            } else if (tab.aiMarkMode) {
                                Text(label("이미지 위를 드래그하세요", "Drag on image"), color = AppColors.TextMuted, fontSize = 10.sp, softWrap = false)
                            }
                            if (tab.aiImageChecking) {
                                Text(label("🖼️ 이미지 입력 확인 중…", "🖼️ Checking image input…"), color = AppColors.TextMuted, fontSize = 10.sp, softWrap = false)
                            }
                        }
                    }
                }

                if (fileChanged) {
                    Text(
                        label("⚠️ 파일이 외부에서 변경되었습니다. 파일을 다시 열어 주세요.",
                            "⚠️ The file changed after it was opened. Reopen the file."),
                        color = AppColors.NeonOrange, fontSize = 11.sp,
                    )
                }
            }
        }

        // 2. 메시지 리스트 영역
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (conversation.messages.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("💬", fontSize = 28.sp)
                        Text(
                            label("파일에 대해 무엇이든 질문하세요", "Ask anything about this file"),
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                        )
                        Text(
                            label("현재 탭의 메타데이터와 분석 결과가 안전하게 전달됩니다.", "File metadata and analysis results are safely attached."),
                            color = AppColors.TextMuted,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        val suggestions = if (ko) listOf(
                            "🏷️ 파일 메타데이터 요약해줘",
                            "🔍 규격 적합성이나 이상 항목 분석해줘",
                            "📐 현재 선택된 노드의 구조 설명해줘",
                        ) else listOf(
                            "🏷️ Summarize file metadata",
                            "🔍 Analyze conformance and anomalies",
                            "📐 Explain the selected node structure",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            suggestions.forEach { q ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = AppColors.Surface,
                                    border = BorderStroke(1.dp, AppColors.Border),
                                    modifier = Modifier.clickable { input = q },
                                ) {
                                    Text(
                                        q,
                                        color = AppColors.TextSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            items(conversation.messages, key = { it.id }) { m ->
                when (m.role) {
                    AiRole.USER -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomEnd = 12.dp, bottomStart = 12.dp),
                                color = AppColors.Surface,
                                border = BorderStroke(1.dp, AppColors.NeonPurple.copy(alpha = 0.4f)),
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        label("나", "You") + " · " + SimpleDateFormat("HH:mm").format(Date(m.timestampMillis)),
                                        color = AppColors.NeonPurple,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    SelectionContainer {
                                        Text(m.text, color = AppColors.TextPrimary, fontSize = 12.sp)
                                    }
                                }
                            }
                            if (m.outgoingContext != null) {
                                Text(
                                    if (expandedContext == m.id) label("▲ 컨텍스트 접기", "▲ Hide context")
                                    else label("▼ 보낸 컨텍스트 (${m.outgoingContext.length}자)", "▼ Sent context (${m.outgoingContext.length} chars)"),
                                    color = AppColors.TextMuted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.clickable { expandedContext = if (expandedContext == m.id) null else m.id }.padding(top = 2.dp, end = 4.dp),
                                )
                                if (expandedContext == m.id) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = AppColors.Background,
                                        border = BorderStroke(1.dp, AppColors.Border),
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    ) {
                                        SelectionContainer {
                                            Text(m.outgoingContext, color = AppColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(6.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    AiRole.ASSISTANT -> {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp),
                                color = AppColors.Surface,
                                border = BorderStroke(1.dp, AppColors.Border),
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "🤖 " + (m.provider?.displayName ?: "AI"),
                                            color = AppColors.NeonBlue,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            SimpleDateFormat("HH:mm").format(Date(m.timestampMillis)),
                                            color = AppColors.TextMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    SelectionContainer {
                                        Text(m.text, color = AppColors.TextPrimary, fontSize = 12.sp)
                                    }
                                    EvidenceChips(appState, tab, m.text, ko)
                                }
                            }
                        }
                    }
                    AiRole.NOTICE -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = AppColors.NeonOrange.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, AppColors.NeonOrange.copy(alpha = 0.3f)),
                            ) {
                                Text(
                                    "⚠️ " + m.text,
                                    color = AppColors.NeonOrange,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    AiRole.TOOL -> {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AppColors.Background,
                            border = BorderStroke(1.dp, AppColors.Border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(6.dp)) {
                                Text(label("🔧 도구 실행 결과", "🔧 Tool Result"), color = AppColors.TextMuted, fontSize = 10.sp)
                                SelectionContainer {
                                    Text(m.text, color = AppColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (running) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (tab.aiFrameDecoding) label("🎞️ 프레임 디코딩 중…", "🎞️ Decoding frame…")
                    else if (tab.aiImageChecking) label("🖼️ 이미지 입력 확인 중…", "🖼️ Checking image input…")
                    else label("AI 응답 생성 중…", "Generating answer…"),
                    color = AppColors.NeonPurple,
                    fontSize = 11.sp,
                )
            }
        }

        // 3. 하단 입력 영역
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface,
            border = BorderStroke(1.dp, AppColors.Border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                preview?.let { text ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AppColors.Background,
                        border = BorderStroke(1.dp, AppColors.Border),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                    ) {
                        Column(Modifier.padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label("보낼 컨텍스트 미리보기", "Context preview"), color = AppColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text("✕", color = AppColors.TextMuted, fontSize = 10.sp, modifier = Modifier.clickable { preview = null })
                            }
                            LazyColumn(Modifier.padding(top = 4.dp)) {
                                item {
                                    SelectionContainer {
                                        Text(text, color = AppColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    enabled = !running,
                    placeholder = { Text(label("질문 입력… (Enter 또는 ⌘/Ctrl+Enter로 전송)", "Ask a question… (Enter or ⌘/Ctrl+Enter to send)"), fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 120.dp).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isMetaPressed || e.isCtrlPressed)) {
                            send(input); true
                        } else false
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                        focusedBorderColor = AppColors.NeonPurple, unfocusedBorderColor = AppColors.Border,
                        focusedContainerColor = AppColors.Background, unfocusedContainerColor = AppColors.Background,
                    ),
                    shape = RoundedCornerShape(6.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            onClick = {
                                if (preview != null) { preview = null; return@TextButton }
                                val file = tab.file; val root = tab.root; val type = tab.type; val selected = tab.selected
                                val field = tab.selectedField; val frame = tab.selectedFrame; val check = tab.contentCheck
                                val parsed = tab.parsedIdentity; val video = tab.embeddedVideo; val visual = tab.aiVisualContext()
                                scope.launch {
                                    preview = try {
                                        withContext(Dispatchers.IO) {
                                            renderAiContext(captureAiContext(file, root, type, selected, field, frame, check, parsed, video, visual))
                                        }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        label("컨텍스트를 만들지 못했습니다: ", "Could not build context: ") + (e.message ?: e.toString())
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(if (preview == null) label("👁️ 컨텍스트", "👁️ Context") else label("✕ 닫기", "✕ Close"), fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                        TextButton(
                            onClick = onOpenFallback,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(label("🌐 브라우저", "🌐 Browser"), fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                        TextButton(
                            enabled = !running,
                            onClick = { rediscover++ },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(label("🔄 재검색", "🔄 Re-scan"), fontSize = 11.sp, color = AppColors.TextSecondary)
                        }
                        if (conversation.activity.isNotEmpty()) {
                            TextButton(
                                onClick = { showActivity = !showActivity },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text("📋 ${conversation.activity.size}", fontSize = 11.sp, color = AppColors.TextSecondary)
                            }
                        }
                    }

                    Spacer(Modifier.weight(1f))

                    if (running) {
                        Button(
                            onClick = { controller.stop(tab) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonOrange),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(label("중지", "Stop"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        val lastQuestion = conversation.messages.lastOrNull { it.role == AiRole.USER }?.text
                        val lastFailed = conversation.status in setOf(AiRunStatus.FAILED, AiRunStatus.TIMED_OUT, AiRunStatus.CANCELLED)
                        if (lastQuestion != null && lastFailed) {
                            OutlinedButton(
                                enabled = provider != null && !fileChanged,
                                onClick = { send(lastQuestion) },
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text(label("다시 시도", "Retry"), fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        Button(
                            enabled = input.isNotBlank() && provider != null && caps?.executablePath != null && !fileChanged && !tab.isLoading && tab.root != null,
                            onClick = { send(input) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonPurple),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(label("보내기", "Send"), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (showActivity) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = AppColors.Background,
                border = BorderStroke(1.dp, AppColors.Border),
                modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp),
            ) {
                LazyColumn(Modifier.padding(6.dp)) {
                    items(conversation.activity) { a ->
                        Text("${SimpleDateFormat("HH:mm:ss").format(Date(a.timestampMillis))} ${a.text}", color = AppColors.TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Clickable references found in an answer: bytes/frames select in the app (tree-driven), URLs open outside. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceChips(appState: AppState, tab: TabState, text: String, ko: Boolean) {
    val root = tab.root
    val frames = tab.gopFrames
    val checkIds = remember(tab.contentCheck) {
        val check = tab.contentCheck
        (check?.conformance?.findings?.map { it.rule.id }.orEmpty() +
            check?.structure?.items?.map { it.id }.orEmpty() +
            check?.audioStructure?.items?.map { it.id }.orEmpty()).toSet()
    }
    // An offset becomes a chip only if the app sent it (context or tool output) or a node starts there;
    // otherwise it is likely a codec value such as 0xFFD8, not a location.
    val conversation = tab.aiConversation
    val sentOffsets = remember(conversation.messages.size) {
        val hexes = Regex("\\b0x[0-9A-Fa-f]{1,12}\\b")
        conversation.messages.flatMap { m -> listOfNotNull(m.outgoingContext, m.text.takeIf { m.role == AiRole.TOOL }) }
            .flatMap { t -> hexes.findAll(t).mapNotNull { it.value.drop(2).toLongOrNull(16) } }.toSet()
    }
    val refs = remember(text, root, frames, checkIds, sentOffsets) {
        val candidates = Regex("\\b0x[0-9A-Fa-f]{1,12}\\b").findAll(text).mapNotNull { it.value.drop(2).toLongOrNull(16) }
        val supported = candidates.filter { it in sentOffsets || (root != null && deepestNodeAt(root, it)?.offset == it) }.toSet()
        extractEvidenceRefs(text, root?.size ?: 0L, checkIds, frames?.size, supportedOffsets = supported)
    }
    if (refs.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (ko) "근거:" else "Evidence:", color = AppColors.TextMuted, fontSize = 10.sp)
        for (ref in refs) {
            val (label, color) = when (ref) {
                is AiEvidenceRef.ByteOffset -> "0x${ref.offset.toString(16).uppercase()}" to AppColors.NeonBlue
                is AiEvidenceRef.Frame -> (if (ko) "프레임 #${ref.index}" else "frame #${ref.index}") to AppColors.NeonBlue
                is AiEvidenceRef.Check -> ref.id to AppColors.NeonGreen
                is AiEvidenceRef.Url -> ((if (ko) "외부 출처: " else "External: ") + ref.url.removePrefix("https://").take(40)) to AppColors.NeonYellow
            }
            Text(
                label, color = color, fontSize = 11.sp,
                modifier = Modifier.border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).clickable {
                    when (ref) {
                        is AiEvidenceRef.ByteOffset -> root?.let { r -> deepestNodeAt(r, ref.offset) }?.let { tab.selectedField = null; tab.selected = it }
                        // Same as picking the frame in the GOP view: select it and seek there.
                        is AiEvidenceRef.Frame -> frames?.getOrNull(ref.index)?.let {
                            tab.selectedFrame = it
                            tab.selected = null
                            tab.seekTargetSeconds = it.ptsSeconds
                            tab.seekRequestTick++
                        }
                        // Conformance rule IDs open that tab; structure/audio check IDs open the structure tab.
                        is AiEvidenceRef.Check -> appState.openContentCheck(
                            tab,
                            if (tab.contentCheck?.conformance?.findings?.any { it.rule.id == ref.id } == true) com.multiviewer.ui.ContentTab.CONFORMANCE
                            else com.multiviewer.ui.ContentTab.STRUCTURE,
                        )
                        is AiEvidenceRef.Url -> com.multiviewer.util.AiCliDetector.openWebAi(ref.url)
                    }
                }.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}
