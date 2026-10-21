package com.multiviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Video filmstrip popup. AV2 retains the still decoder until its player supports seeking. */
@Composable
fun FrameFullSizePreviewWindow(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val frames = tab.gopFrames ?: emptyList()
    val av2 = isAv2VideoTrack(tab.root)
    var compareMode by remember(tab) {
        val initial = tab.openFramePreviewInCompareMode
        tab.openFramePreviewInCompareMode = false
        mutableStateOf(initial)
    }
    var nativeView by remember(tab) { mutableStateOf(av2) }
    var refTarget by remember(tab) { mutableStateOf(FrameReferenceTarget.PINNED_OR_PREV) }
    var intervalOpen by remember(tab) { mutableStateOf(false) }
    var timestamps by remember(tab) { mutableStateOf<FrameTimestampIndex?>(null) }
    var probeFinished by remember(tab) { mutableStateOf(false) }
    var displayed by remember(tab) { mutableStateOf<FramePosition?>(null) }
    var pendingIndex by remember(tab) { mutableStateOf<Int?>(null) }
    var pendingInterval by remember(tab) { mutableStateOf<FrameInterval?>(null) }
    var seekSeconds by remember(tab) { mutableStateOf(tab.selectedFrame?.ptsSeconds ?: 0.0) }
    var seekTick by remember(tab) { mutableIntStateOf(0) }
    var playPauseTick by remember(tab) { mutableIntStateOf(0) }
    var playing by remember(tab) { mutableStateOf(false) }
    var copiedFeedback by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val filmstripSelection = remember(tab) { FilmstripSelectionTracker(tab.seekRequestTick) }
    val frame = tab.selectedFrame ?: frames.firstOrNull()
    val refFrame = remember(frames, frame, tab.pinnedReferenceFrame, refTarget) {
        resolveReferenceFrameForCompare(frames, frame, tab.pinnedReferenceFrame, refTarget)
    }
    fun label(ko: String, en: String) = if (language == AppLanguage.KO) ko else en

    LaunchedEffect(copiedFeedback) {
        if (copiedFeedback != null) {
            delay(2000L)
            copiedFeedback = null
        }
    }

    fun seek(position: FramePosition) {
        pendingIndex = position.number - 1
        seekSeconds = position.ptsSeconds
        seekTick++
    }

    fun step(delta: Int): Boolean {
        if (nativeView || compareMode) {
            val current = frames.indexOfFirst { it.index == frame?.index }
            if (current < 0) return false
            tab.selectedFrame = frames[(current + delta).coerceIn(frames.indices)]
            return true
        }
        val index = timestamps ?: return false
        val current = pendingIndex ?: displayed?.number?.minus(1)
            ?: frame?.let { index.atPts(it.ptsSeconds)?.number?.minus(1) } ?: 0
        val next = index.step(current, delta)
        if (next.number - 1 != current) seek(next)
        return true
    }

    // Only the filmstrip increments tab.seekRequestTick. Popup navigation and playback update
    // selectedFrame without touching that tick, so they cannot trigger a redundant second seek.
    LaunchedEffect(tab.seekRequestTick) {
        val selected = filmstripSelection.consume(tab.seekRequestTick, tab.selectedFrame) ?: return@LaunchedEffect
        if (av2 || compareMode) return@LaunchedEffect // The still decoder below follows selectedFrame directly.
        val position = timestamps?.atPts(selected.ptsSeconds)
        if (position != null) seek(position) else {
            pendingIndex = selected.index
            seekSeconds = selected.ptsSeconds
            seekTick++
        }
    }

    LaunchedEffect(pendingInterval, timestamps) {
        val interval = pendingInterval ?: return@LaunchedEffect
        if (av2 || compareMode) {
            frames.find { it.index == interval.frameIndex }?.let { tab.selectedFrame = it }
        } else {
            val index = timestamps ?: return@LaunchedEffect
            index.forInterval(interval.frameIndex, interval.ptsSeconds)?.let(::seek)
        }
        pendingInterval = null
    }

    // Nothing else shows the native-resolution frame: drop it once the popup closes (~33 MB per 4K frame).
    DisposableEffect(tab) {
        onDispose {
            tab.fullSizeFrameBitmap = null
            tab.pinnedReferenceFrameBitmap = null
        }
    }

    LaunchedEffect(tab.file, frame, nativeView, compareMode) {
        tab.fullSizeFrameBitmap = null
        if ((!nativeView && !compareMode) || frame == null) return@LaunchedEffect
        delay(35)
        val bitmap = suspendCancellableCoroutine<ImageBitmap?> { cont ->
            val future = FrameFullSizeDecoder.decodeFrameAtPtsAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { future?.cancel(true) }
        }
        tab.fullSizeFrameBitmap = bitmap
    }

    LaunchedEffect(tab.file, refFrame, compareMode) {
        if (!compareMode || refFrame == null) {
            tab.pinnedReferenceFrameBitmap = null
            return@LaunchedEffect
        }
        delay(35)
        val bitmap = suspendCancellableCoroutine<ImageBitmap?> { cont ->
            val future = FrameFullSizeDecoder.decodeFrameAtPtsAsync(tab.file, refFrame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { future?.cancel(true) }
        }
        tab.pinnedReferenceFrameBitmap = bitmap
    }

    fun copyCurrentFrameSnapshot() {
        val targetFrame = frame ?: return
        val badge = "#${targetFrame.index} (${targetFrame.type}) · ${String.format(Locale.US, "%.3fs", targetFrame.ptsSeconds)}"
        coroutineScope.launch {
            val bm = tab.fullSizeFrameBitmap ?: suspendCancellableCoroutine<ImageBitmap?> { cont ->
                val future = FrameFullSizeDecoder.decodeFrameAtPtsAsync(tab.file, targetFrame.ptsSeconds) { res ->
                    if (cont.isActive) cont.resume(res)
                }
                cont.invokeOnCancellation { future?.cancel(true) }
            }
            if (bm != null && copyFrameBitmapToClipboard(bm, badge)) {
                copiedFeedback = "#${targetFrame.index}"
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${label("동영상 프레임 검사", "Video Frame Inspection")} - ${tab.file.name}",
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft, Key.A, Key.Comma -> step(-1)
                Key.DirectionRight, Key.D, Key.Period -> step(1)
                Key.Spacebar -> if (!nativeView && !compareMode && !av2) { playPauseTick++; true } else false
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(AppColors.Background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (compareMode || nativeView) label("←/→ 프레임 이동 · 휠 확대/축소 · 드래그 이동", "←/→ step · wheel zoom · drag pan")
                    else label("←/→ 한 프레임 · Space 재생/일시정지 · 휠 확대 · 드래그 이동", "←/→ one frame · Space play/pause · wheel zoom · drag pan"),
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))

                // Proposal ③: A/B Frame Compare & Pixel Diff Toggle
                AssistChip(
                    onClick = {
                        compareMode = !compareMode
                        if (compareMode) playing = false
                    },
                    label = {
                        Text(
                            if (compareMode) label("⚖️ 프레임 비교 닫기 (단일 뷰)", "⚖️ Exit Frame Compare")
                            else label("⚖️ 프레임 A/B 비교 & 픽셀 차이", "⚖️ A/B Frame Compare & Diff"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (compareMode) AppColors.NeonOrange else AppColors.NeonGreen,
                        )
                    },
                    border = BorderStroke(
                        1.dp,
                        if (compareMode) AppColors.NeonOrange else AppColors.NeonGreen.copy(alpha = 0.7f),
                    ),
                    colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.Surface),
                    modifier = Modifier.height(30.dp),
                )
                Spacer(Modifier.width(6.dp))

                // Proposal ⑤: Copy Native Frame Snapshot to Clipboard
                AssistChip(
                    onClick = { copyCurrentFrameSnapshot() },
                    label = {
                        Text(
                            if (copiedFeedback != null) label("✓ 이미지 복사됨 ($copiedFeedback)", "✓ Copied ($copiedFeedback)")
                            else label("📋 프레임 이미지 복사", "📋 Copy Frame Image"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copiedFeedback != null) AppColors.NeonGreen else AppColors.TextPrimary,
                        )
                    },
                    border = BorderStroke(1.dp, if (copiedFeedback != null) AppColors.NeonGreen else AppColors.Border),
                    colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.Surface),
                    modifier = Modifier.height(30.dp),
                )
                Spacer(Modifier.width(6.dp))

                if (!av2 && !compareMode) {
                    AssistChip(
                        onClick = {
                            nativeView = !nativeView
                            playing = false
                        },
                        label = {
                            Text(
                                if (nativeView) label("▶ 동영상 재생기", "▶ Video Player")
                                else label("🖼️ 원본 프레임 보기", "🖼️ View Native Frame"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (nativeView) AppColors.NeonGreen else AppColors.NeonBlue,
                            )
                        },
                        modifier = Modifier.height(30.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                AssistChip(
                    onClick = { intervalOpen = true },
                    label = { Text(label("간격 분석", "Intervals"), fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp),
                )
            }

            if (compareMode) {
                FrameAbCompareView(
                    tab = tab,
                    frames = frames,
                    currentFrame = frame,
                    currentBitmap = tab.fullSizeFrameBitmap,
                    refFrame = refFrame,
                    refBitmap = tab.pinnedReferenceFrameBitmap,
                    refTarget = refTarget,
                    onSelectRefTarget = { refTarget = it },
                    onPinCurrentAsRef = {
                        if (frame != null) {
                            tab.pinnedReferenceFrame = frame
                            refTarget = FrameReferenceTarget.PINNED_OR_PREV
                        }
                    },
                    onClearPinnedRef = { tab.pinnedReferenceFrame = null },
                    language = language,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (nativeView) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val bitmap = tab.fullSizeFrameBitmap
                    when {
                        frame == null -> Text(label("프레임을 선택하세요", "Select a frame"), Modifier.align(Alignment.Center), color = AppColors.TextSecondary)
                        bitmap != null -> PixelInspectorPreview(bitmap = bitmap, resetKey = frame, modifier = Modifier.fillMaxSize())
                        else -> DecodingIndicator(label("원본 프레임 디코딩 중...", "Decoding native frame..."), Modifier.align(Alignment.Center))
                    }
                    frame?.let { selected ->
                        Text(
                            "${label("프레임", "Frame")} ${selected.index + 1}/${frames.size} (${selected.type}) · PTS ${String.format(Locale.US, "%.6f", selected.ptsSeconds)} s",
                            Modifier.align(Alignment.BottomStart).padding(8.dp),
                            color = AppColors.TextPrimary,
                        )
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    FfmpegVideoPlayer(
                        file = tab.file,
                        modifier = Modifier.fillMaxSize(),
                        initialSeekSeconds = frame?.ptsSeconds ?: seekSeconds,
                        knownFrameTimestamps = frames.takeIf { !tab.isAnalyzingFrames && it.isNotEmpty() }?.map { it.ptsSeconds },
                        seekRequestSeconds = seekSeconds,
                        seekRequestTick = seekTick,
                        playPauseRequestTick = playPauseTick,
                        exactFrameInspection = true,
                        enableFrameZoom = true,
                        handleArrowKeys = false,
                        handleSpacebar = false,
                        inspectionLanguage = language,
                        onProbeComplete = { probeFinished = true },
                        onFrameTimestamps = { values -> timestamps = values?.takeIf { it.isNotEmpty() }?.let(::FrameTimestampIndex) },
                        onPlaybackStateChanged = { isPlaying ->
                            playing = isPlaying
                            if (!isPlaying) displayed?.let { position -> frames.getOrNull(position.number - 1)?.let { tab.selectedFrame = it } }
                        },
                        onFramePresented = { position ->
                            if (pendingIndex == null || pendingIndex == position.number - 1) {
                                displayed = position
                                pendingIndex = null
                                if (!playing) frames.getOrNull(position.number - 1)?.let { tab.selectedFrame = it }
                            }
                        },
                    )
                }
                val position = displayed
                val sourceIntervalMs = position?.let { p -> timestamps?.position(p.number - 2)?.let { (p.ptsSeconds - it.ptsSeconds) * 1000.0 } }
                val caption = when {
                    !probeFinished -> label("프레임 타임스탬프 분석 중...", "Analyzing frame timestamps...")
                    timestamps == null -> label("정확한 프레임 정보가 없습니다", "Exact frame information unavailable")
                    position == null -> label("프레임 디코딩 중...", "Decoding frame...")
                    else -> "${label("프레임", "Frame")} ${position.number}/${position.total} (${label("인덱스", "index")} ${position.number - 1}) · PTS ${String.format(Locale.US, "%.6f", position.ptsSeconds)} s"
                }
                Text(caption + (pendingIndex?.let { " · ${label("로딩 중", "loading")} #${it + 1}" } ?: ""), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = AppColors.TextPrimary)
                Text("${label("파일 PTS 간격", "File PTS interval")}: ${sourceIntervalMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"}", Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = AppColors.TextSecondary)
            }
        }
    }
    if (intervalOpen) FrameIntervalAnalysisWindow(appState, tab, onCloseRequest = { intervalOpen = false }, onInspectFrame = { pendingInterval = it })
}
