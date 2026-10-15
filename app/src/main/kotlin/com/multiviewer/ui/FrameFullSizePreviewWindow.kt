package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale

/** A paused video player at the clicked thumbnail, with an optional native-resolution still view. */
@Composable
fun FrameFullSizePreviewWindow(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val frames = tab.gopFrames ?: emptyList()
    val av2 = isAv2VideoTrack(tab.root)
    var nativeView by remember(tab) { mutableStateOf(av2) }
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
    var tracker by remember(tab) { mutableStateOf<PlaybackTimingTracker?>(null) }
    var sample by remember(tab) { mutableStateOf<PlaybackTimingSample?>(null) }
    var events by remember(tab) { mutableStateOf<List<PlaybackTimingEvent>>(emptyList()) }
    var dropCount by remember(tab) { mutableIntStateOf(0) }
    val frame = tab.selectedFrame
    fun label(ko: String, en: String) = if (language == AppLanguage.KO) ko else en

    fun seek(position: FramePosition) {
        pendingIndex = position.number - 1
        seekSeconds = position.ptsSeconds
        seekTick++
        tracker?.resetClock()
        sample = null
    }

    fun selectNative(target: FrameInfo) {
        tab.selectedFrame = target
        tab.selected = null
        if (!av2) timestamps?.atPts(target.ptsSeconds)?.let(::seek)
    }

    fun step(delta: Int): Boolean {
        if (nativeView) {
            val current = frames.indexOfFirst { it.index == frame?.index }
            if (current < 0) return false
            val next = (current + delta).coerceIn(frames.indices)
            if (next != current) selectNative(frames[next])
            return true
        }
        val index = timestamps ?: return false
        val current = pendingIndex ?: displayed?.number?.minus(1)
            ?: frame?.let { index.atPts(it.ptsSeconds)?.number?.minus(1) } ?: 0
        val next = index.step(current, delta)
        if (next.number - 1 != current) seek(next)
        return true
    }

    LaunchedEffect(pendingInterval, timestamps, nativeView) {
        val interval = pendingInterval ?: return@LaunchedEffect
        if (nativeView) {
            frames.find { it.index == interval.frameIndex }?.let(::selectNative)
            pendingInterval = null
        } else {
            val index = timestamps ?: return@LaunchedEffect
            index.forInterval(interval.frameIndex, interval.ptsSeconds)?.let(::seek)
            pendingInterval = null
        }
    }

    LaunchedEffect(tab.file, frame, nativeView) {
        tab.fullSizeFrameBitmap = null
        if (!nativeView || frame == null) return@LaunchedEffect
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
                Key.Spacebar -> if (!nativeView && !av2) { playPauseTick++; true } else false
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(AppColors.Background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!av2) {
                    OutlinedButton(onClick = {
                        nativeView = !nativeView
                        playing = false
                        displayed = null
                        pendingIndex = null
                        probeFinished = false
                        timestamps = null
                        tracker = null
                        sample = null
                        events = emptyList()
                        dropCount = 0
                    }) {
                        Text(if (nativeView) label("동영상 재생기", "Video player") else label("원본 프레임", "Native frame"))
                    }
                    if (!nativeView) {
                        OutlinedButton(onClick = { step(-1) }) { Text("◀") }
                        OutlinedButton(onClick = { playPauseTick++ }) { Text(if (playing) label("일시정지", "Pause") else label("재생", "Play")) }
                        OutlinedButton(onClick = { step(1) }) { Text("▶") }
                    }
                }
                OutlinedButton(onClick = { intervalOpen = true }) {
                    Text(label("프레임 간격 분석", "Frame interval analysis"))
                }
                Text(
                    if (av2) label("AV2는 원본 프레임 검사만 지원", "AV2 supports native-frame inspection only")
                    else label("←/→ 한 프레임 · Space 재생/일시정지", "←/→ one frame · Space play/pause"),
                    color = AppColors.TextSecondary,
                )
            }

            if (nativeView) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val bitmap = tab.fullSizeFrameBitmap
                    when {
                        frame == null -> Text(label("프레임을 선택하세요", "Select a frame"), modifier = Modifier.align(Alignment.Center), color = AppColors.TextSecondary)
                        bitmap != null -> PixelInspectorPreview(bitmap = bitmap, resetKey = frame, modifier = Modifier.fillMaxSize())
                        else -> DecodingIndicator(label("프레임 디코딩 중...", "Decoding frame..."), modifier = Modifier.align(Alignment.Center))
                    }
                    if (frames.isNotEmpty()) {
                        IconButton(onClick = { step(-1) }, modifier = Modifier.align(Alignment.CenterStart).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))) {
                            Text("◀", color = Color.White, fontSize = 16.sp)
                        }
                        IconButton(onClick = { step(1) }, modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))) {
                            Text("▶", color = Color.White, fontSize = 16.sp)
                        }
                    }
                    if (frame != null) {
                        val number = frames.indexOfFirst { it.index == frame.index }.let { if (it >= 0) it + 1 else frame.index }
                        PreviewCaption("${label("프레임", "Frame")} #$number/${frames.size} · PTS ${String.format(Locale.US, "%.6f", frame.ptsSeconds)} s" +
                            (bitmap?.let { " · ${it.width}×${it.height}" } ?: ""), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
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
                        handleArrowKeys = false,
                        handleSpacebar = false,
                        inspectionLanguage = language,
                        onProbeComplete = { probeFinished = true },
                        onFrameTimestamps = { values ->
                            timestamps = values?.takeIf { it.isNotEmpty() }?.let(::FrameTimestampIndex)
                            tracker = timestamps?.let(::PlaybackTimingTracker)
                        },
                        onPlaybackStateChanged = { isPlaying ->
                            playing = isPlaying
                            if (!isPlaying) {
                                tracker?.resetClock()
                                displayed?.let { position -> frames.getOrNull(position.number - 1)?.let { tab.selectedFrame = it } }
                            }
                        },
                        onFramePresented = { position ->
                            if (pendingIndex == null || pendingIndex == position.number - 1) {
                                displayed = position
                                pendingIndex = null
                                if (!playing) {
                                    frames.getOrNull(position.number - 1)?.let { tab.selectedFrame = it }
                                } else {
                                    sample = tracker?.record(position, System.nanoTime())
                                    events = tracker?.recentEvents ?: emptyList()
                                }
                            }
                        },
                        onFrameDropped = { position, reason ->
                            tracker?.recordDrop(position, reason)
                            dropCount = tracker?.dropCount ?: 0
                            events = tracker?.recentEvents ?: emptyList()
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
                Text("${label("파일 PTS 간격", "File PTS interval")}: ${sourceIntervalMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"} · ${label("플레이어 드롭", "Player drops")}: $dropCount", Modifier.padding(horizontal = 10.dp), color = AppColors.TextSecondary)
                sample?.let { s ->
                    fun ms(value: Double?) = value?.let { String.format(Locale.US, "%.1f ms", it) } ?: "—"
                    Text("${label("UI 전달 간격", "UI delivery interval")}: ${ms(s.uiDeliveryIntervalMs)} · ${label("초과 지연", "Excess delay")}: ${ms(s.deliveryDelayMs)} · ${label("건너뛴 프레임", "Skipped frames")}: ${s.skippedSinceDisplay}", Modifier.padding(horizontal = 10.dp), color = AppColors.TextSecondary)
                }
                if (events.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 100.dp).padding(horizontal = 10.dp)) {
                    items(events.asReversed()) { event ->
                        val detail = if (event.kind == PlaybackTimingEventKind.FRAME_DROP) {
                            when (event.dropReason) {
                                PlaybackDropReason.VIDEO_LAG -> label("영상 처리 지연으로 드롭", "Dropped: video lag")
                                PlaybackDropReason.AUDIO_SYNC -> label("오디오 동기화로 드롭", "Dropped: audio sync")
                                null -> label("드롭", "Dropped")
                            }
                        } else label("UI 초과 지연", "UI excess delay") + " " + String.format(Locale.US, "+%.1f ms", event.delayMs ?: 0.0)
                        Text("#${event.position.number} (${label("인덱스", "index")} ${event.position.number - 1}) · PTS ${String.format(Locale.US, "%.6f", event.position.ptsSeconds)} s · $detail", color = AppColors.TextSecondary)
                    }
                }
                Text(label("UI 전달 간격은 화면 표시 시각이 아닙니다.", "UI delivery interval is not physical display timing."), Modifier.padding(horizontal = 10.dp, vertical = 3.dp), color = AppColors.TextSecondary)
            }
        }
    }
    if (intervalOpen) {
        FrameIntervalAnalysisWindow(appState, tab, onCloseRequest = { intervalOpen = false }, onInspectFrame = { pendingInterval = it })
    }
}
