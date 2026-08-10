package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File

// Matches WAVEFORM_PEAK_BUCKET_COUNT (AudioWaveformPeaks.kt) so both panels share the same
// column-to-time mapping via visibleBucketRange -- see that function's doc comment.
const val SPECTROGRAM_WIDTH_PX = 4096
const val SPECTROGRAM_HEIGHT_PX = 512

// One-time full-file render, reusing generateSpectrogramImage (FfmpegAudioPlayer.kt) with
// window = null instead of the per-zoom AudioViewWindow it's normally called with. Fixed
// dimensions mean this runs exactly once per file open, not on every zoom/pan/resize --
// SpectrogramDisplay below crops/stretches the result instead of asking ffmpeg to re-render.
fun generateFullSpectrogramImage(file: File, rawAudioParams: RawAudioParams? = null): ImageBitmap? =
    generateSpectrogramImage(file, SPECTROGRAM_WIDTH_PX, SPECTROGRAM_HEIGHT_PX, rawAudioParams, window = null)

// Draws the visibleRange column slice of a full-file spectrogram bitmap, stretched to fill this
// Canvas -- the spectrogram equivalent of AudioWaveformPeaks.kt's WaveformChannelCanvas, which
// draws a slice of the peaks array instead of a slice of an image. Coerced so a visibleRange right
// at the bitmap's own edge (e.g. the full-duration case, which ends exactly at bitmap.width) can
// never push srcOffset + srcSize past the bitmap's actual bounds.
@Composable
fun SpectrogramDisplay(bitmap: ImageBitmap, visibleRange: IntRange, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val srcWidth = (visibleRange.last - visibleRange.first + 1).coerceIn(1, bitmap.width)
        val srcX = visibleRange.first.coerceIn(0, bitmap.width - srcWidth)
        drawImage(
            image = bitmap,
            srcOffset = IntOffset(srcX, 0),
            srcSize = IntSize(srcWidth, bitmap.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
