package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.assembleAv2Bitstream
import com.multiviewer.parser.buildAv2SampleIndex
import com.multiviewer.parser.findFirst
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun isAv2VideoTrack(root: BoxNode?): Boolean {
    val entry = root?.let { findFirst(it) { node -> node.type == "av02" } } ?: return false
    return findFirst(entry) { it.type == "av2C" } != null
}

@Composable
fun Av2VideoPlayer(
    file: File,
    root: BoxNode,
    modifier: Modifier = Modifier,
    onIndexReady: () -> Unit = {},
) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var isPlaying by remember(file) { mutableStateOf(false) }
    var decoderFinished by remember(file) { mutableStateOf(false) }
    var playedFrames by remember(file) { mutableStateOf(0L) }
    val cancelled = remember(file, root) { AtomicBoolean(false) }
    val frames = remember(file, root) { ArrayBlockingQueue<Av2DecodedFrame>(1) }

    DisposableEffect(file, root) {
        onDispose { cancelled.set(true) }
    }

    LaunchedEffect(file, root) {
        val result = withContext(Dispatchers.IO) {
            val av2C = findFirst(root) { it.type == "av2C" }
            val index = buildAv2SampleIndex(file, root)
            if (av2C == null || index == null || index.samples.isEmpty()) {
                error = "AV2 sample tables or av2C configuration are unavailable"
                decoderFinished = true
                return@withContext null
            }
            withContext(Dispatchers.Main) { onIndexReady() }
            val bitstream = File.createTempFile("unwrapMedia-av2-", ".obu")
            try {
                val range = index.samples.indices
                if (!assembleAv2Bitstream(file, av2C, index, range, bitstream)) {
                    error = "AV2 samples are malformed or outside the file bounds"
                    return@withContext null
                }
                decodeAv2Bitstream(
                    decoderPath = AvmLocator.decoderPathOrThrow(),
                    input = bitstream,
                    onFrame = { frame ->
                        frames.put(frame)
                        true
                    },
                    cancelRequested = { cancelled.get() },
                )
            } catch (e: Exception) {
                error = e.message ?: "Could not start AV2 playback"
                null
            } finally {
                bitstream.delete()
                decoderFinished = true
            }
        }
        if (!cancelled.get() && result != null && !result.succeeded) {
            error = result.error ?: result.stderr.takeIf { it.isNotBlank() } ?: "AV2 decoder produced no frames"
        }
    }

    LaunchedEffect(file, root) {
        var first = true
        while (!cancelled.get()) {
            val next = withContext(Dispatchers.IO) { frames.poll(100, TimeUnit.MILLISECONDS) }
            if (next != null) {
                if (first) {
                    bitmap = withContext(Dispatchers.Default) { next.toImageBitmap() }
                    playedFrames = 1
                    first = false
                } else {
                    while (!isPlaying && !cancelled.get()) delay(40)
                    if (cancelled.get()) break
                    val frameMillis = (1000.0 * next.format.fpsDenominator / next.format.fpsNumerator)
                        .toLong().coerceAtLeast(1L)
                    delay(frameMillis)
                    bitmap = withContext(Dispatchers.Default) { next.toImageBitmap() }
                    playedFrames++
                }
            } else if (decoderFinished && frames.isEmpty()) {
                isPlaying = false
                break
            }
        }
    }

    Box(modifier.fillMaxSize().background(AppColors.Background), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } ?: if (error == null) {
            DecodingIndicator("AV2 프레임 디코딩 중...")
        } else Unit

        error?.let {
            Text(it, color = AppColors.NeonRed, modifier = Modifier.align(Alignment.Center).padding(12.dp))
        }

        Box(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                if (isPlaying) "Ⅱ" else "▶",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterStart).size(24.dp)
                    .clickable(enabled = bitmap != null && !decoderFinished) { isPlaying = !isPlaying },
            )
            Text(
                "AV2 · ${playedFrames} frame(s) · seek disabled (no RAP index)",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}
