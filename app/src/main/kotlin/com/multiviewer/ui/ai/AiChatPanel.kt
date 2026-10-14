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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val running = conversation.status == AiRunStatus.RUNNING
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
            webSearch = appState.aiWebSearch && adapterFor(p).supportsWebSearch)
        input = ""
        preview = null
    }

    Column(
        modifier.fillMaxSize().background(AppColors.Panel).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label("AI와 대화", "Ask AI"), color = AppColors.NeonPurple, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(tab.file.name, color = AppColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
            }
            TextButton(onClick = onClose) { Text("✕", color = AppColors.TextSecondary) }
        }
        if (fileChanged) {
            Text(
                label("파일이 열린 뒤 변경되었습니다. 이 파일에 대해서는 보내지 않습니다 — 다시 열어 주세요.",
                    "The file changed after it was opened. Nothing will be sent for it — reopen the file."),
                color = AppColors.NeonOrange, fontSize = 12.sp,
            )
        }

        // Provider and what is actually verified for it.
        Box {
            OutlinedButton(onClick = { providerMenu = true }, enabled = !running) {
                Text(provider?.displayName ?: label("CLI 없음", "No CLI"), fontSize = 12.sp)
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
        if (caps != null) {
            Text(
                listOf(
                    label("로그인·텍스트 ", "Login/text ") + stateLabel(caps.text, ko),
                    label("이미지 ", "Image ") + stateLabel(caps.image, ko),
                    label("웹 검색 ", "Web search ") + stateLabel(caps.webSearch, ko),
                    label("세션 이어가기 미사용 (최근 대화를 다시 보냄)", "No session resume (recent turns are resent)"),
                ).joinToString(" · "),
                color = AppColors.TextMuted, fontSize = 11.sp,
            )
            if (caps.provider != AiProviderId.CLAUDE) {
                // Codex's read-only sandbox and Gemini's plan mode still allow reading local files.
                Text(
                    label("주의: 이 CLI는 읽기 전용 모드여도 이 컴퓨터의 다른 파일을 읽을 수 있습니다. 파일에서 나온 값은 지시가 아닌 데이터로 표시해 보내지만, 민감한 환경에서는 Claude Code(도구 꺼짐)를 권장합니다.",
                        "Note: even read-only, this CLI can read other files on this computer. File-derived values are fenced as data, but Claude Code (tools off) is safer for sensitive work."),
                    color = AppColors.NeonYellow, fontSize = 11.sp,
                )
            }
            if (caps.executablePath == null) {
                Text(label("이 CLI가 설치되어 있지 않습니다.", "This CLI is not installed."), color = AppColors.NeonOrange, fontSize = 12.sp)
            } else if (caps.image == CapabilityState.UNKNOWN && adapterFor(caps.provider).argumentsWithImages(listOf(java.io.File("p.png"))) != null) {
                var checking by remember { mutableStateOf(false) }
                TextButton(enabled = !checking && !running, onClick = {
                    checking = true
                    controller.verifyImageInput(caps.provider, java.io.File(caps.executablePath)) { checking = false }
                }) {
                    Text(
                        if (checking) label("이미지 입력 확인 중… (빨간 사각형 1장 전송)", "Checking image input… (sends one red square)")
                        else label("이미지 입력 확인 (빨간 사각형 1장 전송)", "Check image input (sends one red square)"),
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (caps != null && caps.executablePath != null) {
            if (adapterFor(caps.provider).supportsWebSearch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = appState.aiWebSearch, enabled = !running, onCheckedChange = { appState.aiWebSearch = it })
                    Text(label("웹 검색 허용 (질문과 함께 보내는 파일 정보 일부가 검색어로 검색 엔진에 나갈 수 있음)",
                        "Allow web search (parts of the question and the file details sent with it may reach a search engine as queries)"),
                        color = AppColors.TextSecondary, fontSize = 11.sp)
                }
            } else {
                Text(label("이 연동에서는 웹 검색을 쓸 수 없습니다. 필요하면 브라우저/클립보드 방식을 쓰세요.",
                    "Web search is not available through this integration; use the browser/clipboard route if needed."),
                    color = AppColors.TextMuted, fontSize = 11.sp)
            }
        }

        // Pointing at the image: opt-in, so the preview's normal pan/tap stays untouched otherwise.
        if (tab.aiImageSource != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (tab.aiMarkMode) Button(onClick = { tab.aiMarkMode = false }) { Text(label("화면 지정 끝내기", "Stop marking"), fontSize = 11.sp) }
                else OutlinedButton(onClick = { tab.aiMarkMode = true }) { Text(label("화면에서 지정", "Mark on screen"), fontSize = 11.sp) }
                if (tab.aiPointer != null || tab.aiMarked != null) {
                    TextButton(onClick = { tab.aiPointer = null; tab.aiMarked = null }) { Text(label("지정 지우기", "Clear marks"), fontSize = 11.sp) }
                }
            }
            if (tab.aiMarkMode) {
                Text(label("주 이미지에서 클릭하면 점, 드래그하면 영역을 지정합니다 (지정하는 동안 이동·타일 클릭·더블클릭 초기화는 꺼집니다).",
                    "In the primary image, click to point or drag to mark a region (pan, tile click and double-click reset are off meanwhile)."),
                    color = AppColors.TextMuted, fontSize = 11.sp)
            }
            val marks = listOfNotNull(
                tab.aiPointer?.let { label("점 (${it.x}, ${it.y})", "point (${it.x}, ${it.y})") },
                tab.aiMarked?.let { label("영역 ${it.x},${it.y} ${it.width}x${it.height}", "region ${it.x},${it.y} ${it.width}x${it.height}") },
            )
            if (marks.isNotEmpty()) Text(marks.joinToString(" · "), color = AppColors.NeonPurple, fontSize = 11.sp)
        } else if (tab.type == com.multiviewer.ui.MediaType.VIDEO) {
            Text(label("영상 화면 지정은 아직 지원하지 않습니다. 선택한 프레임과 PTS는 함께 보냅니다.",
                "Marking on video is not supported yet; the selected frame and PTS are sent."),
                color = AppColors.TextMuted, fontSize = 11.sp)
        }

        // Transcript.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (conversation.messages.isEmpty()) {
                item {
                    Text(
                        label(
                            "이 파일에 대해 질문하세요. 보낼 때 선택한 노드·필드·프레임, 구조 개요, 컨텐츠 검사 결과가 함께 전달되고, 경로·GPS·시리얼은 빠집니다. 앱의 PASS/FAIL 판정은 AI 답변으로 바뀌지 않습니다.",
                            "Ask about this file. Each message carries the selected node/field/frame, the structure overview and Content Check results at send time; paths, GPS and serials are removed. The app's PASS/FAIL verdicts never change from an answer.",
                        ),
                        color = AppColors.TextMuted, fontSize = 12.sp,
                    )
                }
            }
            items(conversation.messages, key = { it.id }) { m ->
                val (bg, fg, who) = when (m.role) {
                    AiRole.USER -> Triple(AppColors.Surface, AppColors.TextPrimary, label("나", "You"))
                    AiRole.ASSISTANT -> Triple(AppColors.Background, AppColors.TextPrimary, m.provider?.displayName ?: "AI")
                    AiRole.NOTICE -> Triple(AppColors.Background, AppColors.NeonOrange, label("앱 알림", "App notice"))
                    AiRole.TOOL -> Triple(AppColors.Background, AppColors.TextSecondary, label("앱 도구 (읽기 전용)", "App tool (read-only)"))
                }
                Column(
                    Modifier.fillMaxWidth().background(bg, RoundedCornerShape(6.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp)).padding(8.dp),
                ) {
                    Text("$who · ${SimpleDateFormat("HH:mm:ss").format(Date(m.timestampMillis))}", color = AppColors.TextMuted, fontSize = 10.sp)
                    SelectionContainer { Text(m.text, color = fg, fontSize = 13.sp, fontFamily = if (m.role == AiRole.TOOL) FontFamily.Monospace else null) }
                    if (m.role == AiRole.ASSISTANT) EvidenceChips(appState, tab, m.text, ko)
                    if (m.outgoingContext != null) {
                        TextButton(onClick = { expandedContext = if (expandedContext == m.id) null else m.id }) {
                            Text(
                                if (expandedContext == m.id) label("보낸 컨텍스트 접기", "Hide sent context")
                                else label("보낸 컨텍스트 보기 (${m.outgoingContext.length}자)", "Show sent context (${m.outgoingContext.length} chars)"),
                                fontSize = 11.sp,
                            )
                        }
                        if (expandedContext == m.id) {
                            SelectionContainer {
                                Text(m.outgoingContext, color = AppColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }

        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(label("응답 기다리는 중… (최대 3분)", "Waiting for the answer… (up to 3 min)"), color = AppColors.TextMuted, fontSize = 11.sp)
        }

        preview?.let { text ->
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).background(AppColors.Background)
                    .border(1.dp, AppColors.Border).padding(6.dp),
            ) {
                Text(label("보낼 컨텍스트 미리보기 (지금 기준)", "Outgoing context preview (as of now)"), color = AppColors.TextSecondary, fontSize = 11.sp)
                LazyColumn { item { SelectionContainer { Text(text, color = AppColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) } } }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !running,
            placeholder = { Text(label("질문 입력 (⌘/Ctrl+Enter로 보내기)", "Ask a question (⌘/Ctrl+Enter to send)"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 180.dp).onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && (e.isMetaPressed || e.isCtrlPressed)) {
                    send(input); true
                } else false
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                focusedBorderColor = AppColors.NeonPurple, unfocusedBorderColor = AppColors.Border,
            ),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running) {
                Button(onClick = { controller.stop(tab) }) { Text(label("중지", "Stop"), fontSize = 12.sp) }
            } else {
                Button(
                    enabled = input.isNotBlank() && provider != null && caps?.executablePath != null && !fileChanged,
                    onClick = { send(input) },
                ) { Text(label("보내기", "Send"), fontSize = 12.sp) }
                val lastQuestion = conversation.messages.lastOrNull { it.role == AiRole.USER }?.text
                val lastFailed = conversation.status in setOf(AiRunStatus.FAILED, AiRunStatus.TIMED_OUT, AiRunStatus.CANCELLED)
                if (lastQuestion != null && lastFailed) {
                    OutlinedButton(enabled = provider != null && !fileChanged, onClick = { send(lastQuestion) }) {
                        Text(label("다시 시도", "Retry"), fontSize = 12.sp)
                    }
                }
            }
            OutlinedButton(onClick = {
                if (preview != null) { preview = null; return@OutlinedButton }
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
                        label("컨텍스트를 만들지 못했습니다: ", "Could not build the context: ") + (e.message ?: e.toString())
                    }
                }
            }) { Text(if (preview == null) label("보낼 내용 보기", "Preview context") else label("미리보기 닫기", "Close preview"), fontSize = 12.sp) }
            TextButton(enabled = !running, onClick = { rediscover++ }) { Text(label("CLI 다시 찾기", "Re-detect CLIs"), fontSize = 11.sp) }
            OutlinedButton(onClick = onOpenFallback) { Text(label("브라우저/클립보드", "Browser/clipboard"), fontSize = 12.sp) }
            TextButton(onClick = { showActivity = !showActivity }) {
                Text(label("활동 기록 ${conversation.activity.size}", "Activity ${conversation.activity.size}"), fontSize = 11.sp)
            }
        }
        if (showActivity) {
            Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).background(AppColors.Background).padding(6.dp)) {
                LazyColumn {
                    items(conversation.activity) { a ->
                        Text("${SimpleDateFormat("HH:mm:ss").format(Date(a.timestampMillis))} ${a.text}", color = AppColors.TextMuted, fontSize = 11.sp)
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
