package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.GifAnimationData
import kotlinx.coroutines.delay

private const val GIF_CELL_MIN_WIDTH_DP = 60f
private const val GIF_CELL_MAX_WIDTH_DP = 600f
private const val GIF_CELL_DEFAULT_WIDTH_DP = 200f
private const val GIF_CELL_ZOOM_STEP_DP = 20f
// GIF delay times of 0 are common in the wild (authoring tools often omit a real value) and would
// otherwise busy-loop the play coroutine; browsers commonly clamp to something in this range too.
private const val GIF_MIN_FRAME_DELAY_MS = 20L

// Full-width replacement for ImageInspectorUI's usual three-box preview row when the open file is
// an animated GIF -- see docs/superpowers/specs/2026-08-01-gif-animation-playback-design.md. Each
// filmstrip cell renders a real decoded frame at panel height (not a small thumbnail), which is
// why there's no separate "now playing" preview elsewhere in the panel -- the current cell IS the
// view. Caller is responsible for only rendering this when animation.frames is non-empty.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GifFilmstripPlayer(tab: TabState, animation: GifAnimationData, modifier: Modifier = Modifier) {
    val gifFrameNodes = remember(tab.root) {
        val nodes = mutableListOf<BoxNode>()
        fun walk(node: BoxNode) {
            if (node.type == "ImageDescriptor") nodes.add(node)
            node.children.forEach { walk(it) }
        }
        tab.root?.let { walk(it) }
        nodes
    }

    if (animation.frames.size <= 1) {
        PixelInspectorPreview(animation.frames.first(), modifier = modifier.fillMaxSize())
        return
    }

    var cellWidthDp by remember { mutableStateOf(GIF_CELL_DEFAULT_WIDTH_DP) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun selectFrame(index: Int) {
        tab.gifFrameIndex = index.coerceIn(0, animation.frames.size - 1)
        tab.gifIsPlaying = false
        tab.selected = gifFrameNodes.getOrNull(tab.gifFrameIndex)
        tab.selectedFrame = null
        focusRequester.requestFocus()
    }

    LaunchedEffect(tab.gifIsPlaying, animation) {
        if (!tab.gifIsPlaying) return@LaunchedEffect
        var repeatsCompleted = 0
        while (tab.gifIsPlaying) {
            delay(animation.durationsMs[tab.gifFrameIndex].toLong().coerceAtLeast(GIF_MIN_FRAME_DELAY_MS))
            if (!tab.gifIsPlaying) break
            val next = tab.gifFrameIndex + 1
            if (next < animation.frames.size) {
                tab.gifFrameIndex = next
            } else if (animation.loopCount in 0..repeatsCompleted) {
                // loopCount counts REPEATS after the first playthrough (0 = play once, never
                // repeat) -- stop once we've completed that many wraps back to frame 0.
                tab.gifIsPlaying = false
            } else {
                repeatsCompleted++
                tab.gifFrameIndex = 0
            }
        }
    }

    LaunchedEffect(tab.gifFrameIndex) {
        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == tab.gifFrameIndex }
        if (!isVisible) {
            listState.animateScrollToItem(tab.gifFrameIndex)
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                    cellWidthDp = (cellWidthDp - scrollDeltaY * GIF_CELL_ZOOM_STEP_DP)
                        .coerceIn(GIF_CELL_MIN_WIDTH_DP, GIF_CELL_MAX_WIDTH_DP)
                    event.changes.forEach { it.consume() }
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { selectFrame(tab.gifFrameIndex - 1); true }
                        Key.DirectionRight -> { selectFrame(tab.gifFrameIndex + 1); true }
                        else -> false
                    }
                },
        ) {
            itemsIndexed(animation.frames) { index, frameBitmap ->
                val isCurrent = index == tab.gifFrameIndex
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(cellWidthDp.dp)
                        .padding(2.dp)
                        .let { if (isCurrent) it.border(2.dp, AppColors.NeonGreen) else it }
                        .clickable { selectFrame(index) },
                ) {
                    PixelInspectorPreview(frameBitmap, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (!tab.gifIsPlaying) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { tab.gifIsPlaying = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        if (animation.truncated) {
            PreviewCaption(
                "First ${animation.frames.size} of ${animation.totalFrameCount} frames shown",
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }

        PreviewCaption(
            "Frame ${tab.gifFrameIndex + 1}/${animation.frames.size} · ${animation.durationsMs[tab.gifFrameIndex]}ms",
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
        )
    }
}
