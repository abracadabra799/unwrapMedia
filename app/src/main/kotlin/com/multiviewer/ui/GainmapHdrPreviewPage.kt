package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.GainmapInfo
import com.multiviewer.parser.GainmapMath
import com.multiviewer.parser.HdrPreviewMode
import com.multiviewer.parser.renderHdrPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.pow
import kotlin.math.roundToInt

private const val HDR_PREVIEW_MAX_EDGE = 1600

/** Base and gain map resampled to one preview size, RGBA unpremultiplied. */
private class HdrPreviewInputs(val width: Int, val height: Int, val base: ByteArray, val gain: ByteArray)

/** Bilinear resample of [this] to [w]x[h] RGBA bytes (alpha is opaque, so premul == unpremul). */
internal fun ImageBitmap.resampledRgba(w: Int, h: Int): ByteArray? {
    val source = Image.makeFromBitmap(asSkiaBitmap())
    val surface = Surface.makeRasterN32Premul(w, h)
    return try {
        surface.canvas.drawImageRect(
            source,
            Rect.makeWH(width.toFloat(), height.toFloat()),
            Rect.makeWH(w.toFloat(), h.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            null,
            true,
        )
        val out = Bitmap()
        out.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        try {
            if (surface.readPixels(out, 0, 0)) out.readPixels() else null
        } finally {
            out.close()
        }
    } finally {
        surface.close()
        source.close()
    }
}

/**
 * HDR 미리보기: applies the gain map for a chosen display headroom. The screen is SDR, so the result
 * is shown relative to that display's peak (SDR white sits at 1/H); the heat map shows the stops of
 * boost each pixel receives at that headroom.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun GainmapHdrPreviewPage(tab: TabState, gainmap: GainmapInfo, language: AppLanguage) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    val params = gainmap.parameters
    val base = tab.imageForensic?.bitmap
    val (gainBitmap, decodingGain) = rememberGainmapBitmap(tab, gainmap)

    val message = when {
        params == null -> label("게인맵 파라미터가 없어 HDR을 계산할 수 없습니다", "No gain map parameters to render HDR with")
        base == null -> label("기본 이미지를 디코딩하는 중이거나 디코딩할 수 없습니다", "Base image is still decoding or could not be decoded")
        gainBitmap == null && decodingGain -> label("게인맵 이미지 디코딩 중...", "Decoding gain map...")
        gainBitmap == null -> label("게인맵 이미지를 디코딩할 수 없습니다", "Gain map image could not be decoded")
        else -> null
    }
    if (message != null || params == null || base == null || gainBitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(message ?: "", style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary))
        }
        return
    }

    val maxLog2 = maxOf(params.hdrCapacityMax ?: 0.0, params.gainMapMax ?: 0.0).coerceAtLeast(0.1)
    var headroomLog2 by remember(gainmap) { mutableStateOf(maxLog2) }
    var mode by remember { mutableStateOf(HdrPreviewMode.HDR_SIMULATION) }

    val orientationCode = tab.imageForensic?.orientationCode
    val inputs by produceState<HdrPreviewInputs?>(null, base, gainBitmap, orientationCode) {
        value = withContext(Dispatchers.Default) {
            // The base is shown EXIF/irot-oriented but the gain map is stored in the coded orientation:
            // orient it the same way, or a portrait photo gets its map stretched across the wrong axis.
            val gainOriented = com.multiviewer.parser.ImageAnalyzer.orientImageBitmap(gainBitmap, orientationCode)
            val scale = minOf(1.0, HDR_PREVIEW_MAX_EDGE.toDouble() / maxOf(base.width, base.height))
            val w = (base.width * scale).roundToInt().coerceAtLeast(1)
            val h = (base.height * scale).roundToInt().coerceAtLeast(1)
            val b = base.resampledRgba(w, h)
            val g = gainOriented.resampledRgba(w, h)
            if (b != null && g != null) HdrPreviewInputs(w, h, b, g) else null
        }
    }
    val rendered by produceState<ImageBitmap?>(null, inputs, headroomLog2, mode) {
        val src = inputs ?: return@produceState
        delay(30) // slider drags re-key this; only the settled value is rendered
        value = withContext(Dispatchers.Default) {
            val rgba = renderHdrPreview(src.base, src.gain, src.width, src.height, params, headroomLog2, mode)
            pixelBufferToImageBitmap(src.width, src.height, rgba, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        }
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var hover by remember { mutableStateOf<Offset?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                HdrPreviewMode.HDR_SIMULATION to label("HDR 시뮬레이션", "HDR simulation"),
                HdrPreviewMode.BOOST_HEATMAP to label("부스트 히트맵", "Boost heat map"),
                HdrPreviewMode.SDR_BASE to label("SDR 기본 이미지", "SDR base"),
            ).forEach { (m, text) ->
                Button(
                    onClick = { mode = m },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (mode == m) AppColors.NeonOrange.copy(alpha = 0.25f) else AppColors.Panel,
                        contentColor = if (mode == m) AppColors.NeonOrange else AppColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text(text, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label("디스플레이 헤드룸", "Display headroom"), style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp))
            Spacer(Modifier.width(10.dp))
            Slider(
                value = headroomLog2.toFloat(),
                onValueChange = { headroomLog2 = it.toDouble() },
                valueRange = 0f..maxLog2.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = AppColors.NeonOrange, activeTrackColor = AppColors.NeonOrange),
            )
            Spacer(Modifier.width(10.dp))
            val weight = GainmapMath.weight(headroomLog2, params)
            Text(
                "%.2f×  (%.2f stops) · %s %.0f%%".format(2.0.pow(headroomLog2), headroomLog2, label("적용", "applied"), weight * 100),
                style = AppTypography.bodyMedium.copy(color = AppColors.NeonOrange, fontSize = 12.sp),
                modifier = Modifier.widthIn(min = 210.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AppColors.Background, RoundedCornerShape(4.dp))
                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                .onSizeChanged { boxSize = it }
                .onPointerEvent(PointerEventType.Move) { hover = it.changes.firstOrNull()?.position }
                .onPointerEvent(PointerEventType.Exit) { hover = null },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = rendered
            if (bmp != null) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator(label("HDR 렌더링 중...", "Rendering HDR..."))
            }
        }
        Spacer(Modifier.height(6.dp))
        val src = inputs
        val readout = hover?.let { p -> src?.let { pixelUnder(p, boxSize, it.width, it.height) } }?.let { (x, y) ->
            val i = (y * src!!.width + x) * 4
            val g = ((src.gain[i].toInt() and 0xFF) + (src.gain[i + 1].toInt() and 0xFF) + (src.gain[i + 2].toInt() and 0xFF)) / 3.0
            val stops = GainmapMath.appliedStops(g / 255.0, params, headroomLog2)
            label("커서 ($x, $y) · 게인 %.0f/255 · 부스트 +%.2f stops (×%.2f)", "Cursor ($x, $y) · gain %.0f/255 · boost +%.2f stops (×%.2f)")
                .format(g, stops, 2.0.pow(stops))
        }
        Text(
            readout ?: label(
                "화면은 SDR이므로 헤드룸 H인 디스플레이의 최대 밝기를 기준으로 표시합니다 (SDR 흰색 = 1/H). 색역 변환은 하지 않습니다(sRGB 가정).",
                "The screen is SDR, so this is shown relative to a display of headroom H (SDR white = 1/H). No gamut conversion (sRGB assumed).",
            ),
            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
        )
    }
}

/** Maps a pointer position in a ContentScale.Fit box to the preview pixel under it, or null if outside. */
internal fun pixelUnder(pointer: Offset, box: IntSize, imageWidth: Int, imageHeight: Int): Pair<Int, Int>? {
    if (box.width <= 0 || box.height <= 0) return null
    val scale = minOf(box.width.toFloat() / imageWidth, box.height.toFloat() / imageHeight)
    val left = (box.width - imageWidth * scale) / 2f
    val top = (box.height - imageHeight * scale) / 2f
    val x = ((pointer.x - left) / scale).toInt()
    val y = ((pointer.y - top) / scale).toInt()
    return if (x in 0 until imageWidth && y in 0 until imageHeight) x to y else null
}
