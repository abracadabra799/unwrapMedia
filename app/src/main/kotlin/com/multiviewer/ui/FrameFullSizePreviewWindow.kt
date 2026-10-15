package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import java.util.Locale
import kotlin.coroutines.resume
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
    val filmstripSelection = remember(tab) { FilmstripSelectionTracker(tab.seekRequestTick) }
    val frame = tab.selectedFrame
    fun label(ko: String, en: String) = if (language == AppLanguage.KO) ko else en

    fun seek(position: FramePosition) {
        pendingIndex = position.number - 1
        seekSeconds = position.ptsSeconds
        seekTick++
    }

    fun step(delta: Int): Boolean {
        if (av2) {
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
        if (av2) return@LaunchedEffect // The still decoder below follows selectedFrame directly.
        val position = timestamps?.atPts(selected.ptsSeconds)
        if (position != null) seek(position) else {
            pendingIndex = selected.index
            seekSeconds = selected.ptsSeconds
            seekTick++
        }
    }

    LaunchedEffect(pendingInterval, timestamps) {
        val interval = pendingInterval ?: return@LaunchedEffect
        if (av2) {
            frames.find { it.index == interval.frameIndex }?.let { tab.selectedFrame = it }
        } else {
            val index = timestamps ?: return@LaunchedEffect
            index.forInterval(interval.frameIndex, interval.ptsSeconds)?.let(::seek)
        }
        pendingInterval = null
    }

    LaunchedEffect(tab.file, frame, av2) {
        tab.fullSizeFrameBitmap = null
        if (!av2 || frame == null) return@LaunchedEffect
        kotlinx.coroutines.delay(40)
        val bitmap = suspendCancellableCoroutine<androidx.compose.ui.graphics.ImageBitmap?> { cont ->
            val future = FrameFullSizeDecoder.decodeFrameAsync(tab.file, frame.ptsSeconds) { result ->
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation { future?.cancel(true) }
        }
        tab.fullSizeFrameBitmap = bitmap
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "${label("동영상 프레임 검사", "Video Frame Inspection")} - ${tab.file.name}",
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft, Key.A, Key.Comma -> step(-1)
                Key.DirectionRight, Key.D, Key.Period -> step(1)
                Key.Spacebar -> if (!av2) { playPauseTick++; true } else false
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
                    if (av2) label("AV2 원본 프레임 검사 · ←/→ 이동", "AV2 native frame inspection · ←/→ step")
                    else label("←/→ 한 프레임 · Space 재생/일시정지 · 휠 확대 · 드래그 이동", "←/→ one frame · Space play/pause · wheel zoom · drag pan"),
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = { intervalOpen = true },
                    label = { Text(label("간격 분석", "Intervals"), fontSize = 11.sp) },
                    modifier = Modifier.height(30.dp),
                )
            }

            if (av2) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val bitmap = tab.fullSizeFrameBitmap
                    when {
                        frame == null -> Text(label("프레임을 선택하세요", "Select a frame"), Modifier.align(Alignment.Center), color = AppColors.TextSecondary)
                        bitmap != null -> PixelInspectorPreview(bitmap = bitmap, resetKey = frame, modifier = Modifier.fillMaxSize())
                        else -> DecodingIndicator(label("프레임 디코딩 중...", "Decoding frame..."), Modifier.align(Alignment.Center))
                    }
                    frame?.let { selected ->
                        Text(
                            "${label("프레임", "Frame")} ${selected.index + 1}/${frames.size} · PTS ${String.format(Locale.US, "%.6f", selected.ptsSeconds)} s",
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
