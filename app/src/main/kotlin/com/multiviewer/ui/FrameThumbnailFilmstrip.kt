package com.multiviewer.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
// scrolls, via FrameThumbnailDecoder -- safe for videos with thousands of frames. Not
// horizontally synchronized with GopAnalysisView's own scroll/zoom -- an independent LazyRow, by
// design (see spec's Out of scope section).
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrameThumbnailFilmstrip(tab: TabState, frames: List<FrameInfo>, modifier: Modifier = Modifier) {
    if (frames.isEmpty()) return

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    var dragStartPos by remember { mutableStateOf(Offset.Zero) }
    var dragLastPos by remember { mutableStateOf(Offset.Zero) }
    var isDragPanning by remember { mutableStateOf(false) }

    // Derived from the first successfully-decoded thumbnail's own pixel dimensions -- converges
    // to the real value almost immediately (as soon as the first batch lands) and every cell
    // (loaded or still pending) shares it, so cell width doesn't jump around per-item as different
    // batches complete.
    var aspectRatio by remember(tab.file) { mutableStateOf(FALLBACK_ASPECT_RATIO) }
    LaunchedEffect(tab.thumbnailCache) {
        val firstBitmap = tab.thumbnailCache.values.firstOrNull() ?: return@LaunchedEffect
        if (firstBitmap.height > 0) aspectRatio = firstBitmap.width.toFloat() / firstBitmap.height.toFloat()
    }

    LaunchedEffect(listState, frames, tab.file) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) IntRange.EMPTY else visible.first().index..visible.last().index
        }.collect { visibleRange ->
            val alreadyCachedOrPending = tab.thumbnailCache.keys + tab.pendingThumbnailIndices
            val range = missingThumbnailRange(visibleRange, FILMSTRIP_PREFETCH_MARGIN, frames.size, alreadyCachedOrPending)
                ?: return@collect
            tab.pendingThumbnailIndices = tab.pendingThumbnailIndices + range
            FrameThumbnailDecoder.decodeRangeAsync(
                tab.file, range.first, frames[range.first].ptsSeconds, range.last - range.first + 1,
            ) { decoded ->
                tab.thumbnailCache = tab.thumbnailCache + decoded
                tab.pendingThumbnailIndices = tab.pendingThumbnailIndices - decoded.keys
            }
        }
    }

    // remember, not a plain val -- without it, the O(frames.size) indexOfLast scan inside
    // currentFrameIndex re-runs on EVERY recomposition of this composable, including ones
    // Keeps a keyboard/click-selected frame in view -- without this, arrow-key stepping past the visible
    // window would move the selection out of sight with no way to tell where it landed.
    LaunchedEffect(tab.selectedFrame) {
        val index = tab.selectedFrame?.index ?: return@LaunchedEffect
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!isVisible) listState.animateScrollToItem(index)
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
        val current = tab.selectedFrame?.index ?: 0
        val target = (current + delta).coerceIn(0, frames.size - 1)
        selectFrame(frames[target])
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
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
                val bitmap = tab.thumbnailCache[index]
                val isSelected = index == tab.selectedFrame?.index
                Box(
                    modifier = Modifier
                        .aspectRatio(aspectRatio)
                        .fillMaxHeight()
                        .padding(1.dp)
                        .let { if (isSelected) it.border(2.dp, Color.White) else it }
                        .clickable {
                            focusRequester.requestFocus()
                            selectFrame(frame)
                            tab.fullSizeFramePreviewOpen = true
                        },
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    PreviewCaption(
                        "#${frame.index} ${frame.type}",
                        modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                    )
                }
            }
        }

        HorizontalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}
