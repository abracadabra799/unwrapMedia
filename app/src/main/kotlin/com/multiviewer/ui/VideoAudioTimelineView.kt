package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private val VIDEO_TICK = Color(0xFF5DC9F4)
private val AUDIO_WAVE = Color(0xFF58D68D)
private val AUDIO_TICK = Color(0xFFFFB86B)

private fun ptsLabel(seconds: Double) = String.format(Locale.US, "%.3f s", seconds)

private fun nearestVideoFrame(frames: List<FrameInfo>, seconds: Double): FrameInfo? {
    if (frames.isEmpty()) return null
    val found = frames.binarySearch { it.ptsSeconds.compareTo(seconds) }
    if (found >= 0) return frames[found]
    val next = -found - 1
    return listOfNotNull(frames.getOrNull(next - 1), frames.getOrNull(next))
        .minByOrNull { kotlin.math.abs(it.ptsSeconds - seconds) }
}

@Composable
private fun TimelineHeaderAction(text: String, fontSize: TextUnit = 10.sp, onClick: () -> Unit) {
    Box(
        modifier = Modifier.height(28.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AppColors.NeonBlue, fontSize = fontSize)
    }
}

/** Shared presentation-time axis for video frames and the first audio stream. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoAudioTimelineView(
    tab: TabState,
    frames: List<FrameInfo>,
    currentVideoPts: Double?,
    language: AppLanguage,
    expanded: Boolean,
    onToggleExpanded: (() -> Unit)?,
    onTogglePlayback: (() -> Unit)? = null,
    focusMode: Boolean = false,
    onToggleFocusMode: (() -> Unit)? = null,
    onOpenPopup: (() -> Unit)? = null,
    focusOnOpen: Boolean = false,
    onSelectFrame: (FrameInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    var audioInfo by remember(tab) { mutableStateOf<AudioFileInfo?>(null) }
    var infoDone by remember(tab) { mutableStateOf(false) }
    var packetResult by remember(tab) { mutableStateOf<AudioTimelinePackets?>(null) }
    var packetsDone by remember(tab) { mutableStateOf(false) }
    var peaks by remember(tab) { mutableStateOf<WaveformPeaks?>(null) }
    var peaksDone by remember(tab) { mutableStateOf(false) }
    var chosenPacket by remember(tab) { mutableStateOf<AudioTimelinePacket?>(null) }
    var customCursorPts by remember(tab) { mutableStateOf<Double?>(null) }
    var lastInternalFrame by remember(tab) { mutableStateOf<FrameInfo?>(null) }

    // Collapsing or closing the tab cancels the interruptible worker; ffmpeg/ffprobe helpers
    // terminate their child process in their finally paths.
    LaunchedEffect(tab, expanded) {
        if (!expanded || (infoDone && (audioInfo == null || (packetsDone && peaksDone)))) return@LaunchedEffect
        infoDone = false
        packetsDone = false
        peaksDone = false
        audioInfo = null
        packetResult = null
        peaks = null
        val info = runInterruptible(Dispatchers.IO) { probeAudioFormat(tab.file) }
        audioInfo = info
        infoDone = true
        if (info == null) return@LaunchedEffect
        val workerJob = coroutineContext[kotlinx.coroutines.Job]
        packetResult = runInterruptible(Dispatchers.IO) {
            probeAudioTimelinePackets(tab.file, isCancelled = { workerJob?.isActive == false })
        }
        packetsDone = true
        if (info.duration <= 0.0) {
            peaksDone = true
            return@LaunchedEffect
        }
        peaks = runInterruptible(Dispatchers.IO) {
            computeWaveformPeaks(tab.file, info, bucketCount = WAVEFORM_PEAK_BUCKET_COUNT)
        }
        peaksDone = true
    }

    var orderedFrames by remember(tab) { mutableStateOf<List<FrameInfo>>(emptyList()) }
    LaunchedEffect(frames) {
        orderedFrames = withContext(Dispatchers.Default) { frames.sortedBy { it.ptsSeconds } }
    }
    val packets = packetResult?.packets.orEmpty()
    val range = remember(orderedFrames, audioInfo, packetResult) { audioVideoTimelineRange(orderedFrames, audioInfo, packets) }
    val firstVideoPts = orderedFrames.firstOrNull()?.ptsSeconds
    val lastVideoPts = orderedFrames.lastOrNull()?.ptsSeconds
    val videoFrameTail = remember(orderedFrames) {
        if (orderedFrames.size > 1 && firstVideoPts != null && lastVideoPts != null) {
            ((lastVideoPts - firstVideoPts) / (orderedFrames.size - 1)).coerceIn(0.001, 1.0)
        } else {
            1.0 / 30.0
        }
    }
    val audioEndPts = audioInfo?.let { it.startTimeSeconds + it.duration }
    var view by remember(tab) { mutableStateOf(AudioViewWindow(0.0, range.durationSeconds)) }
    LaunchedEffect(range) { view = AudioViewWindow(0.0, range.durationSeconds) }
    val selectedFrame = tab.selectedFrame
    // If the user selects a frame externally (e.g. from the GOP panel), reset audio-packet override
    // so the timeline follows the newly selected video frame.
    LaunchedEffect(selectedFrame) {
        if (selectedFrame != null && selectedFrame != lastInternalFrame) {
            chosenPacket = null
            customCursorPts = null
        }
    }
    val selectedPacket = chosenPacket ?: (customCursorPts ?: selectedFrame?.ptsSeconds)?.let { nearestAudioTimelinePacket(packets, it) }
    val activeCursorPts = customCursorPts ?: chosenPacket?.ptsSeconds ?: selectedFrame?.ptsSeconds
    val focusRequester = remember(tab) { FocusRequester() }
    val currentView by rememberUpdatedState(view)
    LaunchedEffect(focusOnOpen, expanded) {
        if (focusOnOpen && expanded) focusRequester.requestFocus()
    }

    fun ensureTimeVisible(ptsSeconds: Double) {
        val visibleStart = range.startSeconds + view.startSeconds
        val visibleEnd = visibleStart + view.durationSeconds
        if (ptsSeconds < visibleStart || ptsSeconds > visibleEnd) {
            val targetStart = ptsSeconds - range.startSeconds - view.durationSeconds * 0.5
            view = clampWindow(targetStart, view.durationSeconds, range.durationSeconds)
        }
    }

    fun applyCursorSelection(frame: FrameInfo?, packet: AudioTimelinePacket?, targetPtsSeconds: Double) {
        chosenPacket = packet
        customCursorPts = if (packet != null || frame == null || kotlin.math.abs(targetPtsSeconds - frame.ptsSeconds) > 1e-4) {
            targetPtsSeconds
        } else {
            null
        }
        lastInternalFrame = frame
        if (frame != null) {
            onSelectFrame(frame)
        }
        if (customCursorPts != null) {
            tab.seekTargetSeconds = targetPtsSeconds
            tab.seekRequestTick++
        }
        ensureTimeVisible(targetPtsSeconds)
    }

    fun stepFrame(delta: Int): Boolean {
        val step = stepTimelineCursor(
            orderedFrames = orderedFrames,
            packets = packets,
            selectedFrame = selectedFrame,
            chosenPacket = chosenPacket,
            delta = delta,
        ) ?: return false
        applyCursorSelection(step.frame, step.packet, step.cursorPtsSeconds)
        return true
    }

    Column(modifier.background(AppColors.Surface).padding(horizontal = 7.dp, vertical = 1.dp)) {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ptsLabel(range.startSeconds + view.startSeconds)} – ${ptsLabel(range.startSeconds + view.startSeconds + view.durationSeconds)}",
                color = AppColors.TextSecondary, fontSize = 10.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (onTogglePlayback != null) {
                TimelineHeaderAction(
                    if (tab.videoPlaybackPlaying) label("Ⅱ 일시정지", "Ⅱ Pause") else label("▶ 재생", "▶ Play"),
                    onClick = onTogglePlayback,
                )
            }
            if (expanded) {
                TimelineHeaderAction("−", fontSize = 12.sp, onClick = {
                    view = zoomAround(view, view.startSeconds + view.durationSeconds / 2, view.durationSeconds * 2, range.durationSeconds)
                })
                TimelineHeaderAction("+", fontSize = 12.sp, onClick = {
                    view = zoomAround(view, view.startSeconds + view.durationSeconds / 2, view.durationSeconds / 2, range.durationSeconds)
                })
                TimelineHeaderAction(label("전체", "Fit")) { view = AudioViewWindow(0.0, range.durationSeconds) }
                if (onToggleFocusMode != null) {
                    TimelineHeaderAction(
                        if (focusMode) label("⛶ 기본 레이아웃 복귀", "⛶ Exit Focus View")
                        else label("⛶ 포커스 최대화", "⛶ Focus View"),
                        onClick = onToggleFocusMode,
                    )
                }
            }
            if (onToggleExpanded != null) {
                TimelineHeaderAction(if (expanded) label("▾ A/V 타임라인 접기", "▾ Collapse A/V Timeline")
                                     else label("▸ A/V 타임라인 펼치기", "▸ Expand A/V Timeline"),
                    onClick = onToggleExpanded)
            }
            if (onOpenPopup != null) {
                TimelineHeaderAction(label("팝업 ↗", "Popup ↗"), onClick = onOpenPopup)
            }
        }

        if (!expanded) return@Column

        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(42.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text("V PTS", color = VIDEO_TICK, fontSize = 9.sp)
                Text(label("파형", "Wave"), color = AUDIO_WAVE, fontSize = 9.sp)
                Text("A PTS", color = AUDIO_TICK, fontSize = 9.sp)
            }
            Box(
                Modifier.fillMaxSize().background(AppColors.Background)
                    .focusRequester(focusRequester).focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> stepFrame(-1)
                            Key.DirectionRight -> stepFrame(1)
                            Key.Spacebar -> if (onTogglePlayback != null) { onTogglePlayback(); true } else false
                            else -> false
                        }
                    },
            ) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                            if (size.width <= 0) return@onPointerEvent
                            if (change.scrollDelta.y != 0f) {
                                val anchor = view.startSeconds + change.position.x / size.width * view.durationSeconds
                                val scale = if (change.scrollDelta.y < 0) 0.75 else 1.33
                                view = zoomAround(view, anchor, view.durationSeconds * scale, range.durationSeconds)
                            } else if (change.scrollDelta.x != 0f) {
                                view = clampWindow(view.startSeconds + change.scrollDelta.x / size.width * view.durationSeconds,
                                    view.durationSeconds, range.durationSeconds)
                            } else return@onPointerEvent
                            event.changes.forEach { it.consume() }
                        }
                        .pointerInput(orderedFrames, packets, range, firstVideoPts, lastVideoPts, videoFrameTail) {
                            detectTapGestures { tap ->
                                focusRequester.requestFocus()
                                val time = timelineTimeAtFraction(tap.x / size.width, range, view)
                                val outsideVideoSpan = orderedFrames.isEmpty() ||
                                    (lastVideoPts != null && time > lastVideoPts + videoFrameTail) ||
                                    (firstVideoPts != null && time < firstVideoPts - videoFrameTail * 0.5)
                                if (tap.y < size.height * 0.25f && !outsideVideoSpan) {
                                    nearestVideoFrame(orderedFrames, time)?.let { frame ->
                                        applyCursorSelection(frame, null, frame.ptsSeconds)
                                    }
                                } else {
                                    val packet = nearestAudioTimelinePacket(packets, time, maxDistanceSeconds = 1.0)
                                    val targetPts = packet?.ptsSeconds ?: time
                                    val frame = nearestVideoFrame(orderedFrames, targetPts)
                                    applyCursorSelection(frame, packet, targetPts)
                                }
                            }
                        }
                        .pointerInput(range) {
                            detectDragGestures { change, drag ->
                                if (size.width > 0) {
                                    view = clampWindow(view.startSeconds - drag.x / size.width * view.durationSeconds,
                                        view.durationSeconds, range.durationSeconds)
                                }
                                change.consume()
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    if (w <= 0f || h <= 0f) return@Canvas
                    val visibleStart = range.startSeconds + view.startSeconds
                    val visibleEnd = visibleStart + view.durationSeconds
                    fun xFor(time: Double) = timelineFraction(time, range, view) * w

                    drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f))
                    drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, h * 0.78f), Offset(w, h * 0.78f))
                    val step = niceTimeStep(view.durationSeconds)
                    var tick = ceil(visibleStart / step) * step
                    var ticks = 0
                    while (tick <= visibleEnd && ticks++ < 40) {
                        val x = xFor(tick)
                        drawLine(Color.White.copy(alpha = 0.1f), Offset(x, 0f), Offset(x, h))
                        tick += step
                    }

                    val frameStart = orderedFrames.binarySearch { it.ptsSeconds.compareTo(visibleStart) }
                        .let { if (it < 0) -it - 1 else it }
                    var frameIndex = frameStart
                    while (frameIndex < orderedFrames.size) {
                        val frame = orderedFrames[frameIndex]
                        if (frame.ptsSeconds > visibleEnd) break
                        val x = xFor(frame.ptsSeconds)
                        val pixel = x.toInt()
                        drawLine(if (frame.type == 'I') AUDIO_TICK else VIDEO_TICK,
                            Offset(x, 3f), Offset(x, h * 0.24f), strokeWidth = if (frame.type == 'I') 2f else 1f)
                        // Jump to the next pixel's time instead of visiting every frame in a
                        // multi-hour file on every redraw.
                        val nextTime = visibleStart + ((pixel + 1.0) / w) * view.durationSeconds
                        val found = orderedFrames.binarySearch(frameIndex + 1, orderedFrames.size) {
                            it.ptsSeconds.compareTo(nextTime)
                        }
                        frameIndex = max(frameIndex + 1, if (found < 0) -found - 1 else found)
                    }

                    lastVideoPts?.let { pts ->
                        val x = xFor(pts)
                        if (x in 0f..w) drawCircle(VIDEO_TICK, radius = 4f, center = Offset(x, h * 0.12f))
                    }

                    val info = audioInfo
                    val wave = peaks
                    if (info != null && wave != null && info.duration > 0.0) {
                        val audioStart = info.startTimeSeconds
                        val overlapStart = max(visibleStart, audioStart)
                        val overlapEnd = min(visibleEnd, audioStart + info.duration)
                        if (overlapEnd > overlapStart) {
                            val firstBucket = ((overlapStart - audioStart) / info.duration * wave.bucketCount)
                                .toInt().coerceIn(0, wave.bucketCount - 1)
                            val lastBucket = ceil((overlapEnd - audioStart) / info.duration * wave.bucketCount)
                                .toInt().coerceIn(firstBucket + 1, wave.bucketCount)
                            val left = xFor(overlapStart)
                            val span = (xFor(overlapEnd) - left).coerceAtLeast(1f)
                            val channels = wave.channels.take(2)
                            channels.forEachIndexed { channelIndex, channel ->
                                val laneTop = h * (0.27f + channelIndex * 0.25f)
                                val laneHeight = h * (if (channels.size == 2) 0.24f else 0.48f)
                                val center = laneTop + laneHeight / 2f
                                forEachPeakColumn(channel, firstBucket until lastBucket, span.toInt().coerceAtLeast(1)) { col, count, mn, mx ->
                                    val x = left + (col + 0.5f) * span / count
                                    drawLine(AUDIO_WAVE, Offset(x, center - mx * laneHeight / 2f),
                                        Offset(x, center - mn * laneHeight / 2f))
                                }
                            }
                        }
                    }

                    audioEndPts?.let { pts ->
                        val x = xFor(pts)
                        if (x in 0f..w) drawLine(AUDIO_WAVE, Offset(x, h * 0.27f), Offset(x, h * 0.76f), strokeWidth = 2f)
                    }

                    val packetStart = packets.binarySearch { it.ptsSeconds.compareTo(visibleStart) }
                        .let { if (it < 0) -it - 1 else it }
                    var packetIndex = packetStart
                    while (packetIndex < packets.size) {
                        val packet = packets[packetIndex]
                        if (packet.ptsSeconds > visibleEnd) break
                        val x = xFor(packet.ptsSeconds)
                        val pixel = x.toInt()
                        drawLine(AUDIO_TICK.copy(alpha = 0.8f), Offset(x, h * 0.8f), Offset(x, h - 2f))
                        val nextTime = visibleStart + ((pixel + 1.0) / w) * view.durationSeconds
                        val found = packets.binarySearch(packetIndex + 1, packets.size) {
                            it.ptsSeconds.compareTo(nextTime)
                        }
                        packetIndex = max(packetIndex + 1, if (found < 0) -found - 1 else found)
                    }

                    if (chosenPacket != null || customCursorPts != null) {
                        selectedFrame?.let { frame ->
                            val fx = xFor(frame.ptsSeconds)
                            if (fx in 0f..w) drawLine(VIDEO_TICK, Offset(fx, 0f), Offset(fx, h * 0.25f), strokeWidth = 2f)
                        }
                        activeCursorPts?.let { cPts ->
                            val cx = xFor(cPts)
                            if (cx in 0f..w) {
                                drawLine(VIDEO_TICK.copy(alpha = 0.85f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 2f)
                                drawLine(AUDIO_TICK, Offset(cx, h * 0.25f), Offset(cx, h), strokeWidth = 2.5f)
                            }
                        }
                    } else {
                        selectedFrame?.let { frame ->
                            val x = xFor(frame.ptsSeconds)
                            if (x in 0f..w) drawLine(VIDEO_TICK, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
                        }
                        selectedPacket?.let { packet ->
                            val x = xFor(packet.ptsSeconds)
                            if (x in 0f..w) drawLine(AUDIO_TICK, Offset(x, h * 0.78f), Offset(x, h), strokeWidth = 2f)
                        }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    currentVideoPts?.let { pts ->
                        val x = timelineFraction(pts, range, view) * size.width
                        if (x in 0f..size.width) drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                    }
                }
            }
        }

        if (view.durationSeconds < range.durationSeconds) {
            val scrollbarColor = AppColors.NeonBlue
            Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(42.dp))
                Canvas(
                    Modifier.fillMaxSize()
                        .padding(vertical = 2.dp)
                        .pointerInput(range.durationSeconds) {
                            detectTapGestures { tap ->
                                if (size.width > 0) {
                                    view = clampWindow(
                                        tap.x / size.width * range.durationSeconds - currentView.durationSeconds / 2,
                                        currentView.durationSeconds,
                                        range.durationSeconds,
                                    )
                                }
                            }
                        }
                        .pointerInput(range.durationSeconds) {
                            detectDragGestures { change, drag ->
                                if (size.width > 0) {
                                    view = clampWindow(
                                        currentView.startSeconds + drag.x / size.width * range.durationSeconds,
                                        currentView.durationSeconds,
                                        range.durationSeconds,
                                    )
                                }
                                change.consume()
                            }
                        },
                ) {
                    drawRect(Color.White.copy(alpha = 0.12f))
                    val left = (view.startSeconds / range.durationSeconds * size.width).toFloat()
                    val width = (view.durationSeconds / range.durationSeconds * size.width).toFloat()
                    drawRect(scrollbarColor, Offset(left, 0f), androidx.compose.ui.geometry.Size(width, size.height))
                }
            }
        }

        val statuses = buildList {
            if (tab.gopFrames == null || tab.isAnalyzingFrames) add(label("영상 프레임 PTS 읽는 중…", "Reading video frame PTS…"))
            else if (tab.gopFrames?.isEmpty() == true) add(label("영상 프레임 PTS 없음", "No video frame PTS"))
            if (!infoDone) add(label("오디오 확인 중…", "Checking audio…"))
            else if (audioInfo == null) add(label("오디오 스트림 없음 또는 읽기 실패", "No audio stream or probe failed"))
            else {
                if (!packetsDone) add(label("패킷 PTS 읽는 중…", "Reading packet PTS…"))
                else if (packetResult == null) add(label("패킷 PTS 읽기 실패 또는 시간 초과", "Packet PTS unavailable or timed out"))
                else if (packetResult!!.packets.isEmpty()) add(label("오디오 패킷 PTS 없음", "No audio packet PTS"))
                else if (packetResult!!.truncated) add(label("패킷 첫 200,000개만 표시", "Showing first 200,000 packets"))
                if (!peaksDone) add(label("파형 생성 중…", "Generating waveform…"))
                else if (peaks == null) add(label("파형 생성 실패 또는 시간 초과", "Waveform unavailable or timed out"))
            }
        }
        if (statuses.isNotEmpty()) Text(
            statuses.joinToString(" · "), color = AppColors.TextMuted, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (lastVideoPts != null || audioEndPts != null) {
            Text(
                listOfNotNull(
                    lastVideoPts?.let { "${label("영상 마지막 PTS", "Video last PTS")} ${ptsLabel(it)}" },
                    audioEndPts?.let { "${label("오디오 끝", "Audio end")} ${ptsLabel(it)}" },
                ).joinToString(" · "),
                color = AppColors.TextSecondary, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectedFrame != null || selectedPacket != null) {
            val detail = buildList {
                selectedFrame?.let { add("V #${it.index} PTS ${ptsLabel(it.ptsSeconds)}") }
                selectedPacket?.let { add("A #${it.index} PTS ${ptsLabel(it.ptsSeconds)}") }
                if (selectedFrame != null && selectedPacket != null) {
                    add(label("PTS 간격(동기화 판정 아님)", "PTS gap (not sync verdict)") + " " +
                        String.format(Locale.US, "%+.1f ms", (selectedFrame.ptsSeconds - selectedPacket.ptsSeconds) * 1000.0))
                }
            }.joinToString("  ·  ")
            Text(detail, color = AppColors.TextPrimary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
