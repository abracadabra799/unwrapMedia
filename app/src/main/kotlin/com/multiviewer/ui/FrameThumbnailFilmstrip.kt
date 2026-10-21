package com.multiviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

// Fallback aspect ratio (width/height) used only until the first thumbnail decodes and reveals
// the video's actual aspect -- 16:9 is a reasonable landscape-video default guess. Fixing cell
// width at a constant regardless of the source video's aspect (the original v1 shape) letterboxed
// hard on portrait footage: a 120px-wide, 213px-tall decoded thumbnail fit into a fixed 120x90dp
// cell left ~35dp of black space on EACH side, reading as "sparse" frames with big gaps. Matching
// the cell's own aspect ratio to the video's actual aspect eliminates that letterboxing entirely.
private const val FALLBACK_ASPECT_RATIO = 16f / 9f
private const val FILMSTRIP_PREFETCH_MARGIN = 20

// Real decoded frame thumbnails below GopAnalysisView's bar chart -- see
// docs/superpowers/specs/2026-08-14-frame-thumbnail-filmstrip-design.md. Unlike GifFilmstripPlayer
// (which preloads every frame up front, viable only because GIF frame counts are small), this
// lazily batch-decodes only the currently-visible range (plus a prefetch margin) as the LazyRow
// or LazyVerticalGrid scrolls, via FrameThumbnailDecoder -- safe for videos with thousands of frames.
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun FrameThumbnailFilmstrip(
    tab: TabState,
    frames: List<FrameInfo>,
    language: AppLanguage = AppLanguage.KO,
    onRequestSplitRatio: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (frames.isEmpty()) return

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    var dragLastPos by remember { mutableStateOf(Offset.Zero) }
    var isDragPanning by remember { mutableStateOf(false) }

    var sizePreset by remember(tab.file) { mutableStateOf(FilmstripSizePreset.MEDIUM) }
    var layoutMode by remember(tab.file) { mutableStateOf(FilmstripLayoutMode.STRIP) }
    var copiedFrameFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedFrameFeedback) {
        if (copiedFrameFeedback != null) {
            delay(2000L)
            copiedFrameFeedback = null
        }
    }

    // Derived from the first successfully-decoded thumbnail's own pixel dimensions -- converges
    // to the real value almost immediately (as soon as the first batch lands) and every cell
    // (loaded or still pending) shares it, so cell width doesn't jump around per-item as different
    // batches complete.
    var aspectRatio by remember(tab.file) { mutableStateOf(FALLBACK_ASPECT_RATIO) }
    LaunchedEffect(tab.thumbnailCache) {
        val firstBitmap = tab.thumbnailCache.values.firstOrNull() ?: return@LaunchedEffect
        if (firstBitmap.height > 0) aspectRatio = firstBitmap.width.toFloat() / firstBitmap.height.toFloat()
    }

    LaunchedEffect(listState, gridState, layoutMode, frames, tab.file) {
        val request = LatestThumbnailRequest()
        try {
            snapshotFlow {
                if (layoutMode == FilmstripLayoutMode.STRIP) {
                    val visible = listState.layoutInfo.visibleItemsInfo
                    if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
                } else {
                    val visible = gridState.layoutInfo.visibleItemsInfo
                    if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
                }
            }.collect { visibleRange ->
                request.clear()
                tab.pendingThumbnailIndices = emptySet()
                tab.filmstripThumbnails.protect(visibleRange)
                val alreadyCachedOrPending = tab.thumbnailCache.keys + tab.pendingThumbnailIndices
                val range = missingThumbnailRange(visibleRange, FILMSTRIP_PREFETCH_MARGIN, frames.size, alreadyCachedOrPending)
                    ?: return@collect
                tab.pendingThumbnailIndices = tab.pendingThumbnailIndices + range
                var token = 0L
                val future = FrameThumbnailDecoder.decodeRangeAsync(
                    tab.file, range.first, frames[range.first].ptsSeconds, range.last - range.first + 1,
                    onComplete = {
                        if (request.isCurrent(token) && !tab.isClosed) {
                            tab.pendingThumbnailIndices = tab.pendingThumbnailIndices - range.toSet()
                        }
                    },
                ) { decoded ->
                    if (request.isCurrent(token) && !tab.isClosed) {
                        tab.filmstripThumbnails.putAll(decoded)
                        tab.thumbnailCache = tab.filmstripThumbnails.snapshot()
                        tab.pendingThumbnailIndices = tab.pendingThumbnailIndices - decoded.keys
                    }
                }
                token = request.replace(future)
            }
        } finally {
            request.clear()
            tab.pendingThumbnailIndices = emptySet()
            tab.filmstripThumbnails.protect(emptySet())
        }
    }

    val activeFrameIndex = remember(frames, tab.selectedFrame, tab.currentVideoPtsSeconds, tab.videoPlaybackPlaying) {
        resolveActiveGopFrameIndex(frames, tab.selectedFrame, tab.currentVideoPtsSeconds, tab.videoPlaybackPlaying)
    }

    // Keeps a keyboard/click-selected frame AND live playback frame in view -- without this, arrow-key
    // stepping or playback past the visible window would move the active frame out of sight.
    LaunchedEffect(activeFrameIndex, tab.videoPlaybackPlaying, layoutMode) {
        val index = activeFrameIndex ?: return@LaunchedEffect
        if (layoutMode == FilmstripLayoutMode.STRIP) {
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!isVisible) {
                if (tab.videoPlaybackPlaying) listState.scrollToItem(index) else listState.animateScrollToItem(index)
            }
        } else {
            val isVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!isVisible) {
                if (tab.videoPlaybackPlaying) gridState.scrollToItem(index) else gridState.animateScrollToItem(index)
            }
        }
    }

    fun selectFrame(frame: FrameInfo) {
        tab.selectedFrame = frame
        tab.selected = null
        tab.seekTargetSeconds = frame.ptsSeconds
        tab.seekRequestTick++
    }

    // Only meaningful once the filmstrip actually has keyboard focus (see the .clickable below,
    // which requests it on click) -- there's no auto-focus-on-mount here, unlike GopAnalysisView's
    // own filmstrip, so opening this panel doesn't steal focus from wherever the user already was.
    fun stepFrame(delta: Int) {
        val current = activeFrameIndex ?: tab.selectedFrame?.index ?: 0
        val target = (current + delta).coerceIn(0, frames.size - 1)
        selectFrame(frames[target])
    }

    fun copyActiveFrameToClipboard() {
        val idx = activeFrameIndex ?: tab.selectedFrame?.index ?: 0
        val frame = frames.getOrNull(idx) ?: return
        val badge = "#${frame.index} (${frame.type}) · ${String.format(Locale.US, "%.3fs", frame.ptsSeconds)}"
        coroutineScope.launch {
            val fullBitmap = suspendCancellableCoroutine<ImageBitmap?> { cont ->
                val future = FrameFullSizeDecoder.decodeFrameAtPtsAsync(tab.file, frame.ptsSeconds) { res ->
                    if (cont.isActive) cont.resume(res)
                }
                cont.invokeOnCancellation { future?.cancel(true) }
            } ?: tab.thumbnailCache[idx]
            if (fullBitmap != null && copyFrameBitmapToClipboard(fullBitmap, badge)) {
                copiedFrameFeedback = "#${frame.index}"
            }
        }
    }

    val activeFrame = activeFrameIndex?.let { frames.getOrNull(it) } ?: tab.selectedFrame ?: frames.firstOrNull()
    val pinnedFrame = tab.pinnedReferenceFrame

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: Title + Size Presets (소/중/대) + Layout Toggle (스트립/그리드)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (language == AppLanguage.KO) "🎞️ 필름스트립" else "🎞️ Filmstrip",
                    style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonBlue, fontWeight = FontWeight.Bold),
                )

                // Size Preset Segmented Selector: 소 / 중 / 대
                Surface(
                    color = AppColors.Surface,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, AppColors.Border),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilmstripSizePreset.values().forEach { preset ->
                            val selected = sizePreset == preset
                            Text(
                                text = if (language == AppLanguage.KO) preset.labelKo else preset.labelEn,
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) AppColors.NeonBlue else AppColors.TextSecondary,
                                modifier = Modifier
                                    .background(if (selected) AppColors.NeonBlue.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable {
                                        sizePreset = preset
                                        onRequestSplitRatio?.invoke(preset.splitRatio)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                // Layout Toggle: ━ 스트립 / ⊞ 그리드
                Surface(
                    color = AppColors.Surface,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, AppColors.Border),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isStrip = layoutMode == FilmstripLayoutMode.STRIP
                        Text(
                            text = if (language == AppLanguage.KO) "━ 스트립" else "━ Strip",
                            fontSize = 10.sp,
                            fontWeight = if (isStrip) FontWeight.Bold else FontWeight.Medium,
                            color = if (isStrip) AppColors.NeonBlue else AppColors.TextSecondary,
                            modifier = Modifier
                                .background(if (isStrip) AppColors.NeonBlue.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    layoutMode = FilmstripLayoutMode.STRIP
                                    onRequestSplitRatio?.invoke(sizePreset.splitRatio)
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                        Text(
                            text = if (language == AppLanguage.KO) "⊞ 그리드" else "⊞ Grid",
                            fontSize = 10.sp,
                            fontWeight = if (!isStrip) FontWeight.Bold else FontWeight.Medium,
                            color = if (!isStrip) AppColors.NeonBlue else AppColors.TextSecondary,
                            modifier = Modifier
                                .background(if (!isStrip) AppColors.NeonBlue.copy(alpha = 0.18f) else Color.Transparent)
                                .clickable {
                                    layoutMode = FilmstripLayoutMode.GRID
                                    if (sizePreset == FilmstripSizePreset.SMALL) {
                                        onRequestSplitRatio?.invoke(0.45f)
                                    } else {
                                        onRequestSplitRatio?.invoke(0.35f)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // Right: Reference Pin + Compare + Copy Snapshot + Popup Inspector
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Proposal ③: Pin Reference Frame & Open A/B Compare
                if (pinnedFrame == null) {
                    AssistChip(
                        onClick = {
                            if (activeFrame != null) {
                                tab.pinnedReferenceFrame = activeFrame
                            }
                        },
                        label = {
                            Text(
                                if (language == AppLanguage.KO) "📌 기준 고정" else "📌 Pin Ref",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.NeonOrange,
                            )
                        },
                        border = BorderStroke(1.dp, AppColors.NeonOrange.copy(alpha = 0.6f)),
                        colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.Surface),
                        modifier = Modifier.height(26.dp),
                    )
                } else {
                    AssistChip(
                        onClick = { tab.pinnedReferenceFrame = null },
                        label = {
                            Text(
                                if (language == AppLanguage.KO) "📌 기준 #${pinnedFrame.index} ✕" else "📌 Ref #${pinnedFrame.index} ✕",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.NeonOrange,
                            )
                        },
                        border = BorderStroke(1.dp, AppColors.NeonOrange),
                        colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.NeonOrange.copy(alpha = 0.14f)),
                        modifier = Modifier.height(26.dp),
                    )
                    AssistChip(
                        onClick = {
                            tab.openFramePreviewInCompareMode = true
                            tab.fullSizeFramePreviewOpen = true
                        },
                        label = {
                            Text(
                                if (language == AppLanguage.KO) "⚖️ #${pinnedFrame.index}↔#${activeFrame?.index ?: 0} 비교 ↗"
                                else "⚖️ #${pinnedFrame.index}↔#${activeFrame?.index ?: 0} Diff ↗",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.NeonGreen,
                            )
                        },
                        border = BorderStroke(1.dp, AppColors.NeonGreen.copy(alpha = 0.75f)),
                        colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.Surface),
                        modifier = Modifier.height(26.dp),
                    )
                }

                // Proposal ⑤: Copy Native Frame Snapshot to Clipboard
                AssistChip(
                    onClick = { copyActiveFrameToClipboard() },
                    label = {
                        Text(
                            if (copiedFrameFeedback != null) {
                                if (language == AppLanguage.KO) "✓ 복사됨 ($copiedFrameFeedback)" else "✓ Copied ($copiedFrameFeedback)"
                            } else {
                                if (language == AppLanguage.KO) "📋 캡처 복사" else "📋 Copy Frame"
                            },
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (copiedFrameFeedback != null) AppColors.NeonGreen else AppColors.TextPrimary,
                        )
                    },
                    border = BorderStroke(
                        1.dp,
                        if (copiedFrameFeedback != null) AppColors.NeonGreen else AppColors.Border,
                    ),
                    colors = AssistChipDefaults.assistChipColors(containerColor = AppColors.Surface),
                    modifier = Modifier.height(26.dp),
                )

                // Popup Inspector
                AssistChip(
                    onClick = { tab.fullSizeFramePreviewOpen = true },
                    label = {
                        Text(
                            if (language == AppLanguage.KO) "🔍 정밀 검사 팝업 ↗" else "🔍 Popup Inspector ↗",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonBlue,
                        )
                    },
                    border = BorderStroke(1.dp, AppColors.NeonBlue.copy(alpha = 0.7f)),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = AppColors.Surface,
                        labelColor = AppColors.NeonBlue,
                    ),
                    modifier = Modifier.height(26.dp),
                )
            }
        }

        if (layoutMode == FilmstripLayoutMode.STRIP) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                        val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                        val scrollDelta = if (delta.x != 0f) delta.x else delta.y
                        if (scrollDelta != 0f) {
                            coroutineScope.launch {
                                listState.scrollBy(scrollDelta * 60f)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    }
                    .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                        dragStartPos = pos
                        dragLastPos = pos
                        isDragPanning = false
                    }
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        if (change.pressed) {
                            val currentPos = change.position
                            val dx = currentPos.x - dragLastPos.x
                            val totalDist = (currentPos - dragStartPos).getDistance()
                            if (totalDist > 4f) {
                                isDragPanning = true
                                dragLastPos = currentPos
                                if (dx != 0f) {
                                    coroutineScope.launch {
                                        listState.scrollBy(-dx)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                    .onPointerEvent(PointerEventType.Release, pass = PointerEventPass.Initial) { event ->
                        if (isDragPanning) {
                            event.changes.forEach { it.consume() }
                            isDragPanning = false
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { stepFrame(-1); true }
                            Key.DirectionRight -> { stepFrame(1); true }
                            else -> false
                        }
                    },
            ) {
                itemsIndexed(frames) { index, frame ->
                    FilmstripCell(
                        frame = frame,
                        bitmap = tab.thumbnailCache[index],
                        aspectRatio = aspectRatio,
                        isSelected = index == activeFrameIndex,
                        isPlayingActive = index == activeFrameIndex && tab.videoPlaybackPlaying,
                        isPinnedRef = pinnedFrame?.index == frame.index,
                        fillHeight = true,
                        onClick = {
                            focusRequester.requestFocus()
                            selectFrame(frame)
                        },
                        onDoubleClick = {
                            focusRequester.requestFocus()
                            selectFrame(frame)
                            tab.fullSizeFramePreviewOpen = true
                        },
                        onLongClick = {
                            tab.pinnedReferenceFrame = if (tab.pinnedReferenceFrame?.index == frame.index) null else frame
                        },
                    )
                }
            }

            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        } else {
            // Proposal ④: Multi-row Contact Sheet Grid View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = sizePreset.gridMinWidthDp.dp),
                    state = gridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp)
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> { stepFrame(-1); true }
                                Key.DirectionRight -> { stepFrame(1); true }
                                else -> false
                            }
                        },
                ) {
                    gridItemsIndexed(frames) { index, frame ->
                        FilmstripCell(
                            frame = frame,
                            bitmap = tab.thumbnailCache[index],
                            aspectRatio = aspectRatio,
                            isSelected = index == activeFrameIndex,
                            isPlayingActive = index == activeFrameIndex && tab.videoPlaybackPlaying,
                            isPinnedRef = pinnedFrame?.index == frame.index,
                            fillHeight = false,
                            onClick = {
                                focusRequester.requestFocus()
                                selectFrame(frame)
                            },
                            onDoubleClick = {
                                focusRequester.requestFocus()
                                selectFrame(frame)
                                tab.fullSizeFramePreviewOpen = true
                            },
                            onLongClick = {
                                tab.pinnedReferenceFrame = if (tab.pinnedReferenceFrame?.index == frame.index) null else frame
                            },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(gridState),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilmstripCell(
    frame: FrameInfo,
    bitmap: ImageBitmap?,
    aspectRatio: Float,
    isSelected: Boolean,
    isPlayingActive: Boolean,
    isPinnedRef: Boolean,
    fillHeight: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val activeBorderColor = when {
        isPlayingActive -> AppColors.NeonGreen
        isSelected -> AppColors.NeonBlue
        isPinnedRef -> AppColors.NeonOrange
        else -> Color.Transparent
    }
    val outerModifier = if (fillHeight) {
        Modifier.fillMaxHeight()
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = outerModifier.combinedClickable(
            onClick = onClick,
            onDoubleClick = onDoubleClick,
            onLongClick = onLongClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = (if (fillHeight) Modifier.weight(1f) else Modifier.fillMaxWidth())
                .aspectRatio(aspectRatio)
                .background(AppColors.Panel)
                .let {
                    if (isSelected || isPinnedRef) {
                        it.border(if (isPlayingActive || isSelected) 2.5.dp else 2.dp, activeBorderColor)
                    } else it
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            val typeColor = when (frame.type) {
                'I' -> AppColors.FrameTypeI
                'P' -> AppColors.FrameTypeP
                'B' -> AppColors.FrameTypeB
                else -> AppColors.TextSecondary
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = frame.type.toString(),
                    color = typeColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "#${frame.index}",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
            if (isPinnedRef) {
                Text(
                    text = "📌 REF",
                    color = Color.Black,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .background(AppColors.NeonOrange, RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            if (isSelected) {
                Text(
                    text = String.format(Locale.US, "%.3fs", frame.ptsSeconds),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(activeBorderColor.copy(alpha = 0.85f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}
