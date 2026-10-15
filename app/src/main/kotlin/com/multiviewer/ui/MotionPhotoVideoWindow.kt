package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import java.io.File
import java.util.Locale

/** Uses the same extracted file as the inline preview; the caller unmounts inline playback. */
@Composable
fun MotionPhotoVideoWindow(
    appState: AppState,
    tab: TabState,
    file: File,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    var timestamps by remember(file) { mutableStateOf<FrameTimestampIndex?>(null) }
    var timelineProbeFinished by remember(file) { mutableStateOf(false) }
    var displayed by remember(file) { mutableStateOf<FramePosition?>(null) }
    var pendingTargetIndex by remember(file) { mutableStateOf<Int?>(null) }
    var requestedPts by remember(file) { mutableStateOf(0.0) }
    var seekTick by remember(file) { mutableIntStateOf(0) }
    var intervalOpen by remember(file) { mutableStateOf(false) }
    var pendingInterval by remember(file) { mutableStateOf<FrameInterval?>(null) }

    fun seek(position: FramePosition) {
        pendingTargetIndex = position.number - 1
        requestedPts = position.ptsSeconds
        seekTick++
    }

    fun step(delta: Int): Boolean {
        val index = timestamps ?: return false
        val current = pendingTargetIndex ?: displayed?.number?.minus(1) ?: 0
        val next = index.step(current, delta)
        if (next.number - 1 != current) seek(next)
        return true
    }

    LaunchedEffect(pendingInterval, timestamps) {
        val interval = pendingInterval ?: return@LaunchedEffect
        val index = timestamps ?: return@LaunchedEffect
        index.forInterval(interval.frameIndex, interval.ptsSeconds)?.let(::seek)
        pendingInterval = null
    }

    Window(
        title = if (language == AppLanguage.KO) "모션포토 프레임 검사 - ${tab.file.name}" else "Motion Photo Frame Inspection - ${tab.file.name}",
        onCloseRequest = onCloseRequest,
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> step(-1)
                Key.DirectionRight -> step(1)
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(AppColors.Background)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (language == AppLanguage.KO) "←/→ 한 프레임 이동 · Space 재생/일시정지" else "←/→ one frame · Space play/pause",
                    color = AppColors.TextSecondary,
                )
                OutlinedButton(onClick = { intervalOpen = true }) {
                    Text(if (language == AppLanguage.KO) "프레임 간격 분석" else "Frame interval analysis")
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FfmpegVideoPlayer(
                    file = file,
                    modifier = Modifier.fillMaxSize(),
                    seekRequestSeconds = requestedPts,
                    seekRequestTick = seekTick,
                    exactFrameInspection = true,
                    handleArrowKeys = false,
                    inspectionLanguage = language,
                    onProbeComplete = { timelineProbeFinished = true },
                    onFrameTimestamps = { values ->
                        timestamps = values?.takeIf { it.isNotEmpty() }?.let(::FrameTimestampIndex)
                    },
                    onFramePresented = { position ->
                        if (pendingTargetIndex == null || pendingTargetIndex == position.number - 1) {
                            displayed = position
                            pendingTargetIndex = null
                        }
                    },
                )
            }
            val position = displayed
            val caption = when {
                !timelineProbeFinished -> if (language == AppLanguage.KO) "프레임 타임스탬프 분석 중..." else "Analyzing frame timestamps..."
                timestamps == null -> if (language == AppLanguage.KO) "정확한 프레임 정보가 없습니다" else "Exact frame information unavailable"
                position == null -> if (language == AppLanguage.KO) "프레임 디코딩 중..." else "Decoding frame..."
                else -> {
                    val frameLabel = if (language == AppLanguage.KO) "프레임" else "Frame"
                    val indexLabel = if (language == AppLanguage.KO) "인덱스" else "index"
                    "$frameLabel ${position.number}/${position.total} ($indexLabel ${position.number - 1}) · PTS ${String.format(Locale.US, "%.6f", position.ptsSeconds)} s"
                }
            }
            val target = pendingTargetIndex
            val waiting = target != null && position?.number != target + 1
            Text(
                caption + if (waiting) {
                    if (language == AppLanguage.KO) " · ${target + 1}번 로딩 중" else " · loading #${target + 1}"
                } else "",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                color = AppColors.TextPrimary,
            )
        }
    }

    if (intervalOpen) {
        MotionPhotoFrameIntervalAnalysisWindow(
            appState = appState,
            tab = tab,
            onCloseRequest = { intervalOpen = false },
            onInspectFrame = { interval -> pendingInterval = interval },
        )
    }
}
