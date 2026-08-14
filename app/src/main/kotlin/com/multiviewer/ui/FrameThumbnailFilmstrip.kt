package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

private const val FILMSTRIP_CELL_WIDTH_DP = 120
private const val FILMSTRIP_HEIGHT_DP = 90
private const val FILMSTRIP_PREFETCH_MARGIN = 15

// Real decoded frame thumbnails below GopAnalysisView's bar chart -- see
// docs/superpowers/specs/2026-08-14-frame-thumbnail-filmstrip-design.md. Unlike GifFilmstripPlayer
// (which preloads every frame up front, viable only because GIF frame counts are small), this
// lazily batch-decodes only the currently-visible range (plus a prefetch margin) as the LazyRow
// scrolls, via FrameThumbnailDecoder -- safe for videos with thousands of frames. Not
// horizontally synchronized with GopAnalysisView's own scroll/zoom -- an independent LazyRow, by
// design (see spec's Out of scope section).
@Composable
fun FrameThumbnailFilmstrip(tab: TabState, frames: List<FrameInfo>, modifier: Modifier = Modifier) {
    if (frames.isEmpty()) return

    val listState = rememberLazyListState()

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
                tab.pendingThumbnailIndices = tab.pendingThumbnailIndices - range
            }
        }
    }

    val currentIndex = currentFrameIndex(frames, tab.playbackElapsedSeconds)
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentIndex }
        if (!isVisible) listState.animateScrollToItem(currentIndex)
    }

    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth().height(FILMSTRIP_HEIGHT_DP.dp).background(Color.Black),
    ) {
        itemsIndexed(frames) { index, frame ->
            val bitmap = tab.thumbnailCache[index]
            val isCurrent = index == currentIndex
            Box(
                modifier = Modifier
                    .width(FILMSTRIP_CELL_WIDTH_DP.dp)
                    .fillMaxHeight()
                    .padding(1.dp)
                    .let { if (isCurrent) it.border(2.dp, Color.White) else it }
                    .clickable {
                        tab.selectedFrame = frame
                        tab.selected = null
                        tab.seekTargetSeconds = frame.ptsSeconds
                        tab.seekRequestTick++
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
}
