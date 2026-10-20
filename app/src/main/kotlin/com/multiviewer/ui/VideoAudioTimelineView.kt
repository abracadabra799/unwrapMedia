package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
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

/** Shared presentation-time axis for video frames and the first audio stream. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VideoAudioTimelineView(
    tab: TabState,
    frames: List<FrameInfo>,
    currentVideoPts: Double?,
    language: AppLanguage,
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

    // This composable exists only while the strip is expanded. Collapse/tab close cancels the
    // interruptible worker; each ffmpeg/ffprobe helper terminates its child in its finally path.
    LaunchedEffect(tab) {
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
    var view by remember(tab) { mutableStateOf(AudioViewWindow(0.0, range.durationSeconds)) }
    LaunchedEffect(range) { view = AudioViewWindow(0.0, range.durationSeconds) }
    val selectedFrame = tab.selectedFrame
    val selectedPacket = chosenPacket ?: selectedFrame?.let { nearestAudioTimelinePacket(packets, it.ptsSeconds) }

    Column(modifier.background(AppColors.Surface).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(25.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ptsLabel(range.startSeconds + view.startSeconds)} – ${ptsLabel(range.startSeconds + view.startSeconds + view.durationSeconds)}",
                color = AppColors.TextSecondary, fontSize = 10.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 5.dp), onClick = {
                view = zoomAround(view, view.startSeconds + view.durationSeconds / 2, view.durationSeconds * 2, range.durationSeconds)
            }) { Text("−", fontSize = 12.sp) }
            TextButton(modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 5.dp), onClick = {
                view = zoomAround(view, view.startSeconds + view.durationSeconds / 2, view.durationSeconds / 2, range.durationSeconds)
            }) { Text("+", fontSize = 12.sp) }
            TextButton(modifier = Modifier.height(24.dp), contentPadding = PaddingValues(horizontal = 5.dp),
                onClick = { view = AudioViewWindow(0.0, range.durationSeconds) }) {
                Text(label("전체", "Fit"), fontSize = 10.sp)
            }
        }

        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(42.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Text(label("영상", "Video"), color = VIDEO_TICK, fontSize = 9.sp)
                Text(label("파형", "Wave"), color = AUDIO_WAVE, fontSize = 9.sp)
                Text("A PTS", color = AUDIO_TICK, fontSize = 9.sp)
            }
            Box(Modifier.fillMaxSize().background(AppColors.Background)) {
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
                        .pointerInput(orderedFrames, packets, range) {
                            detectTapGestures { tap ->
                                val time = timelineTimeAtFraction(tap.x / size.width, range, view)
                                if (tap.y < size.height * 0.25f) {
                                    nearestVideoFrame(orderedFrames, time)?.let { frame ->
                                        chosenPacket = null
                                        onSelectFrame(frame)
                                    }
                                } else {
                                    nearestAudioTimelinePacket(packets, time)?.let { packet ->
                                        chosenPacket = packet
                                        nearestVideoFrame(orderedFrames, packet.ptsSeconds)?.let(onSelectFrame)
                                    }
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

                    selectedFrame?.let { frame ->
                        val x = xFor(frame.ptsSeconds)
                        if (x in 0f..w) drawLine(VIDEO_TICK, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
                    }
                    selectedPacket?.let { packet ->
                        val x = xFor(packet.ptsSeconds)
                        if (x in 0f..w) drawLine(AUDIO_TICK, Offset(x, h * 0.78f), Offset(x, h), strokeWidth = 2f)
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
