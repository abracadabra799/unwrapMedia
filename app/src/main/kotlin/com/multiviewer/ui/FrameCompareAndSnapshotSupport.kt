package com.multiviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.multiviewer.util.ClipboardUtil
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

enum class FilmstripSizePreset(
    val labelKo: String,
    val labelEn: String,
    val splitRatio: Float,
    val gridMinWidthDp: Int,
) {
    SMALL("소", "S", splitRatio = 0.66f, gridMinWidthDp = 115),
    MEDIUM("중", "M", splitRatio = 0.52f, gridMinWidthDp = 165),
    LARGE("대", "L", splitRatio = 0.35f, gridMinWidthDp = 235),
}

enum class FilmstripLayoutMode {
    STRIP,
    GRID,
}

enum class FrameReferenceTarget {
    PINNED_OR_PREV,
    PREVIOUS_FRAME,
    GOP_KEYFRAME,
}

enum class FrameCompareDisplayMode {
    SIDE_BY_SIDE,
    SPLIT_WIPER,
    DIFF_HEATMAP,
    BLINK,
}

data class FramePixelDiffStats(
    val diffBitmap: ImageBitmap,
    val meanAbsDiff: Double,
    val maxAbsDiff: Int,
    val changedPixelPercent: Double,
    val psnrDb: Double,
    val width: Int,
    val height: Int,
)

/**
 * Resolves the reference frame (Slot A) to compare against [currentFrame] (Slot B).
 */
internal fun resolveReferenceFrameForCompare(
    frames: List<FrameInfo>,
    currentFrame: FrameInfo?,
    pinnedReferenceFrame: FrameInfo?,
    target: FrameReferenceTarget,
): FrameInfo? {
    if (frames.isEmpty() || currentFrame == null) return pinnedReferenceFrame
    val currentPos = frames.indexOfFirst { it.index == currentFrame.index }.takeIf { it >= 0 } ?: 0
    return when (target) {
        FrameReferenceTarget.PINNED_OR_PREV -> {
            pinnedReferenceFrame ?: frames.getOrNull((currentPos - 1).coerceAtLeast(0))
        }
        FrameReferenceTarget.PREVIOUS_FRAME -> {
            frames.getOrNull((currentPos - 1).coerceAtLeast(0))
        }
        FrameReferenceTarget.GOP_KEYFRAME -> {
            val gopStart = (currentPos downTo 0).firstOrNull { frames[it].type == 'I' } ?: 0
            frames.getOrNull(gopStart)
        }
    }
}

/**
 * Converts a Compose [ImageBitmap] to an opaque `TYPE_INT_RGB` [BufferedImage] suitable for
 * system clipboard transfer (`ClipboardUtil.copyImage`) on both macOS and Windows.
 * If [badgeText] is non-null, stamps a compact forensic info pill at the top-left corner.
 */
internal fun imageBitmapToRgbBufferedImage(
    bitmap: ImageBitmap,
    badgeText: String? = null,
): BufferedImage {
    val skia = bitmap.asSkiaBitmap()
    val w = skia.width.coerceAtLeast(1)
    val h = skia.height.coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val argb = skia.getColor(x, y)
            out.setRGB(x, y, argb and 0xFFFFFF)
        }
    }
    if (!badgeText.isNullOrBlank()) {
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fontSize = (min(w, h) * 0.026f).toInt().coerceIn(11, 24)
            g.font = Font(Font.MONOSPACED, Font.BOLD, fontSize)
            val fm = g.fontMetrics
            val textW = fm.stringWidth(badgeText)
            val textH = fm.height
            val padX = (fontSize * 0.6f).toInt().coerceAtLeast(6)
            val padY = (fontSize * 0.35f).toInt().coerceAtLeast(4)
            val boxX = 8
            val boxY = 8
            val boxW = textW + padX * 2
            val boxH = textH + padY * 2
            g.color = java.awt.Color(15, 18, 24, 210)
            g.fillRoundRect(boxX, boxY, boxW, boxH, 8, 8)
            g.color = java.awt.Color(79, 195, 247, 220)
            g.drawRoundRect(boxX, boxY, boxW, boxH, 8, 8)
            g.color = java.awt.Color(245, 248, 255)
            g.drawString(badgeText, boxX + padX, boxY + padY + fm.ascent)
        } finally {
            g.dispose()
        }
    }
    return out
}

internal fun copyFrameBitmapToClipboard(
    bitmap: ImageBitmap,
    badgeText: String? = null,
): Boolean {
    return try {
        val buffered = imageBitmapToRgbBufferedImage(bitmap, badgeText)
        ClipboardUtil.copyImage(buffered)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Computes pixel-by-pixel RGB differences between [bmA] (Reference) and [bmB] (Current),
 * producing a thermal diff heatmap and quantitative error statistics (MAE, Max Delta,
 * Changed Pixel Ratio, and PSNR in dB).
 */
internal fun computeFramePixelDiffStats(
    bmA: ImageBitmap,
    bmB: ImageBitmap,
): FramePixelDiffStats? {
    return try {
        val skiaA = bmA.asSkiaBitmap()
        val skiaB = bmB.asSkiaBitmap()
        val w = min(skiaA.width, skiaB.width)
        val h = min(skiaA.height, skiaB.height)
        if (w <= 0 || h <= 0) return null

        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        var sumAbsDiff = 0L
        var sumSquaredError = 0.0
        var maxDiff = 0
        var changedPixels = 0L
        val totalPixels = w.toLong() * h.toLong()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pA = skiaA.getColor(x, y)
                val pB = skiaB.getColor(x, y)

                val rA = (pA shr 16) and 0xFF
                val gA = (pA shr 8) and 0xFF
                val bA = pA and 0xFF

                val rB = (pB shr 16) and 0xFF
                val gB = (pB shr 8) and 0xFF
                val bB = pB and 0xFF

                val dR = abs(rA - rB)
                val dG = abs(gA - gB)
                val dB = abs(bA - bB)

                val pixelMax = max(dR, max(dG, dB))
                if (pixelMax > maxDiff) maxDiff = pixelMax
                if (pixelMax > 2) changedPixels++

                sumAbsDiff += (dR + dG + dB)
                sumSquaredError += (dR * dR + dG * dG + dB * dB).toDouble()

                val amplified = min(255, (dR + dG + dB) * 3)
                val heatColor = when {
                    amplified == 0 -> {
                        // Dimmed grayscale of current frame so structural context remains visible
                        val luma = ((rB * 77 + gB * 150 + bB * 29) shr 8) / 5
                        (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
                    }
                    amplified < 96 -> {
                        // Blue-cyan for subtle compression noise / minor quantization delta
                        (0xFF shl 24) or (0x18 shl 16) or ((90 + amplified) shl 8) or min(255, 140 + amplified)
                    }
                    amplified < 180 -> {
                        // Amber/Yellow for moderate motion or inter-frame residual
                        (0xFF shl 24) or (min(255, 140 + amplified / 2) shl 16) or (min(255, 120 + amplified / 3) shl 8) or 0x10
                    }
                    else -> {
                        // Hot Red/Magenta for strong pixel difference or artifact
                        (0xFF shl 24) or (0xFF shl 16) or ((255 - amplified) / 2 shl 8) or 0x30
                    }
                }
                out.setRGB(x, y, heatColor)
            }
        }

        val totalChannels = (totalPixels * 3L).coerceAtLeast(1L)
        val meanAbsDiff = sumAbsDiff.toDouble() / totalChannels.toDouble()
        val mse = sumSquaredError / totalChannels.toDouble()
        val psnrDb = if (mse <= 1e-9) 99.99 else (10.0 * log10((255.0 * 255.0) / mse)).coerceIn(0.0, 99.99)
        val changedPercent = (changedPixels.toDouble() * 100.0) / totalPixels.coerceAtLeast(1L).toDouble()

        FramePixelDiffStats(
            diffBitmap = out.toComposeImageBitmap(),
            meanAbsDiff = meanAbsDiff,
            maxAbsDiff = maxDiff,
            changedPixelPercent = changedPercent,
            psnrDb = psnrDb,
            width = w,
            height = h,
        )
    } catch (_: Exception) {
        null
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun FrameAbCompareView(
    tab: TabState,
    frames: List<FrameInfo>,
    currentFrame: FrameInfo?,
    currentBitmap: ImageBitmap?,
    refFrame: FrameInfo?,
    refBitmap: ImageBitmap?,
    refTarget: FrameReferenceTarget,
    onSelectRefTarget: (FrameReferenceTarget) -> Unit,
    onPinCurrentAsRef: () -> Unit,
    onClearPinnedRef: () -> Unit,
    language: AppLanguage,
    modifier: Modifier = Modifier,
) {
    fun label(ko: String, en: String) = if (language == AppLanguage.KO) ko else en
    var displayMode by remember { mutableStateOf(FrameCompareDisplayMode.SIDE_BY_SIDE) }
    var wiperPos by remember { mutableStateOf(0.5f) }
    var blinkShowRef by remember { mutableStateOf(false) }

    var scale by remember(currentBitmap?.width, currentBitmap?.height) { mutableStateOf(1f) }
    var offset by remember(currentBitmap?.width, currentBitmap?.height) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverPixel by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    var diffStats by remember(refBitmap, currentBitmap) { mutableStateOf<FramePixelDiffStats?>(null) }
    LaunchedEffect(refBitmap, currentBitmap) {
        if (refBitmap != null && currentBitmap != null) {
            diffStats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                computeFramePixelDiffStats(refBitmap, currentBitmap)
            }
        } else {
            diffStats = null
        }
    }

    Column(modifier = modifier.fillMaxSize().background(AppColors.Background)) {
        // Top Compare Control Bar
        Surface(
            color = AppColors.Surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Left: Reference Frame Target Selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label("기준 프레임(A):", "Reference (A):"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonOrange,
                            maxLines = 1,
                            softWrap = false,
                        )
                        val pinned = tab.pinnedReferenceFrame
                        FilterChip(
                            selected = refTarget == FrameReferenceTarget.PINNED_OR_PREV,
                            onClick = { onSelectRefTarget(FrameReferenceTarget.PINNED_OR_PREV) },
                            label = {
                                Text(
                                    if (pinned != null) label("📌 고정 #${pinned.index} (${pinned.type})", "📌 Pin #${pinned.index} (${pinned.type})")
                                    else label("📌 고정 프레임 (미지정 시 N-1)", "📌 Pinned (or N-1)"),
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            modifier = Modifier.height(26.dp),
                        )
                        FilterChip(
                            selected = refTarget == FrameReferenceTarget.PREVIOUS_FRAME,
                            onClick = { onSelectRefTarget(FrameReferenceTarget.PREVIOUS_FRAME) },
                            label = { Text(label("⏮ 직전 프레임 (N-1)", "⏮ Prev (N-1)"), fontSize = 10.5.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(26.dp),
                        )
                        FilterChip(
                            selected = refTarget == FrameReferenceTarget.GOP_KEYFRAME,
                            onClick = { onSelectRefTarget(FrameReferenceTarget.GOP_KEYFRAME) },
                            label = { Text(label("🔑 GOP 첫 I-프레임", "🔑 GOP Keyframe"), fontSize = 10.5.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(26.dp),
                        )
                        AssistChip(
                            onClick = onPinCurrentAsRef,
                            label = {
                                Text(
                                    label("📌 현재 프레임(#${currentFrame?.index ?: 0}) 고정", "📌 Pin Current (#${currentFrame?.index ?: 0})"),
                                    fontSize = 10.5.sp,
                                    color = AppColors.NeonOrange,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            border = BorderStroke(1.dp, AppColors.NeonOrange.copy(alpha = 0.6f)),
                            modifier = Modifier.height(26.dp),
                        )
                        if (pinned != null) {
                            AssistChip(
                                onClick = onClearPinnedRef,
                                label = { Text(label("고정 해제 ✕", "Unpin ✕"), fontSize = 10.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false) },
                                modifier = Modifier.height(26.dp),
                            )
                        }
                    }

                    // Right: Display Mode Selector
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = displayMode == FrameCompareDisplayMode.SIDE_BY_SIDE,
                            onClick = { displayMode = FrameCompareDisplayMode.SIDE_BY_SIDE },
                            label = { Text(label("좌우 나란히", "Side-by-Side"), fontSize = 10.5.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(26.dp),
                        )
                        FilterChip(
                            selected = displayMode == FrameCompareDisplayMode.SPLIT_WIPER,
                            onClick = { displayMode = FrameCompareDisplayMode.SPLIT_WIPER },
                            label = { Text(label("분할 와이퍼", "Split Wiper"), fontSize = 10.5.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(26.dp),
                        )
                        FilterChip(
                            selected = displayMode == FrameCompareDisplayMode.DIFF_HEATMAP,
                            onClick = { displayMode = FrameCompareDisplayMode.DIFF_HEATMAP },
                            label = { Text(label("픽셀 차이 히트맵", "Diff Heatmap"), fontSize = 10.5.sp, maxLines = 1, softWrap = false) },
                            modifier = Modifier.height(26.dp),
                        )
                        FilterChip(
                            selected = displayMode == FrameCompareDisplayMode.BLINK,
                            onClick = {
                                if (displayMode == FrameCompareDisplayMode.BLINK) {
                                    blinkShowRef = !blinkShowRef
                                } else {
                                    displayMode = FrameCompareDisplayMode.BLINK
                                    blinkShowRef = false
                                }
                            },
                            label = {
                                Text(
                                    if (displayMode == FrameCompareDisplayMode.BLINK) {
                                        if (blinkShowRef) label("교차: [A 기준] (클릭 전환)", "Blink: [A Ref]")
                                        else label("교차: [B 현재] (클릭 전환)", "Blink: [B Cur]")
                                    } else label("깜빡임 교차", "Blink A/B"),
                                    fontSize = 10.5.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            },
                            modifier = Modifier.height(26.dp),
                        )
                    }
                }

                // Quantitative Frame Diff Summary Banner
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val refDesc = refFrame?.let {
                        "#${it.index} (${it.type}) · ${String.format(Locale.US, "%.3fs", it.ptsSeconds)} · ${formatCompactFrameBytes(it.sizeBytes.toLong())}"
                    } ?: "—"
                    val curDesc = currentFrame?.let {
                        "#${it.index} (${it.type}) · ${String.format(Locale.US, "%.3fs", it.ptsSeconds)} · ${formatCompactFrameBytes(it.sizeBytes.toLong())}"
                    } ?: "—"
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "[A ${label("기준", "Ref")}] $refDesc",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.NeonOrange,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text("vs", fontSize = 10.sp, color = AppColors.TextSecondary, maxLines = 1, softWrap = false)
                        Text(
                            text = "[B ${label("현재", "Cur")}] $curDesc",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.NeonBlue,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    val stats = diffStats
                    if (stats != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "PSNR: ${
                                    if (stats.psnrDb >= 99.0) label("∞ (동일)", "∞ (Identical)")
                                    else String.format(Locale.US, "%.2f dB", stats.psnrDb)
                                }",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (stats.psnrDb >= 38.0) AppColors.NeonGreen else if (stats.psnrDb >= 30.0) AppColors.NeonOrange else AppColors.FrameTypeI,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = "${label("평균 오차(MAE)", "MAE")}: ${String.format(Locale.US, "%.2f", stats.meanAbsDiff)}",
                                fontSize = 10.5.sp,
                                color = AppColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = "Max ΔRGB: ${stats.maxAbsDiff}",
                                fontSize = 10.5.sp,
                                color = AppColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                text = "${label("변경 픽셀", "Changed")}: ${String.format(Locale.US, "%.1f%%", stats.changedPixelPercent)}",
                                fontSize = 10.5.sp,
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    } else {
                        Text(
                            text = label("프레임 픽셀 차이 계산 중...", "Computing pixel difference..."),
                            fontSize = 10.5.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        // Main Inspection Canvas Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(6.dp),
        ) {
            if (currentBitmap == null || refBitmap == null) {
                DecodingIndicator(
                    label("비교 프레임 원본 해상도 디코딩 중...", "Decoding native frames for comparison..."),
                    Modifier.align(Alignment.Center),
                )
            } else {
                val nativeSize = Size(currentBitmap.width.toFloat(), currentBitmap.height.toFloat())
                val fittedSize = fittedContentSize(paneSize, nativeSize)

                when (displayMode) {
                    FrameCompareDisplayMode.SIDE_BY_SIDE -> {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                Triple(refBitmap, "[A ${label("기준", "Ref")}] #${refFrame?.index ?: 0} (${refFrame?.type ?: '?'})", AppColors.NeonOrange),
                                Triple(currentBitmap, "[B ${label("현재", "Cur")}] #${currentFrame?.index ?: 0} (${currentFrame?.type ?: '?'})", AppColors.NeonBlue),
                            ).forEach { (bm, badgeTitle, borderColor) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clipToBounds()
                                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                        .border(1.5.dp, borderColor.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                        .onGloballyPositioned { paneSize = it.size.toSize() }
                                        .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                            val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                            val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                                            scale = newScale
                                            offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSize)
                                            event.changes.forEach { it.consume() }
                                        }
                                        .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                                            val pos = event.changes.firstOrNull()?.position
                                            hoverPixel = pos?.let { screenPointToNativePixel(it, paneSize, nativeSize, scale, offset) }
                                        }
                                        .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                                            hoverPixel = null
                                        }
                                        .pointerInput(refBitmap, currentBitmap) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSize)
                                            }
                                        }
                                        .pointerInput(refBitmap, currentBitmap) {
                                            detectTapGestures(
                                                onDoubleTap = {
                                                    scale = 1f
                                                    offset = Offset.Zero
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = bm,
                                        contentDescription = badgeTitle,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = scale,
                                                scaleY = scale,
                                                translationX = offset.x,
                                                translationY = offset.y,
                                                transformOrigin = TransformOrigin(0f, 0f),
                                            ),
                                        contentScale = ContentScale.Fit,
                                    )
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.72f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.7f)),
                                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                    ) {
                                        Text(
                                            text = badgeTitle,
                                            color = borderColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    FrameCompareDisplayMode.SPLIT_WIPER -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clipToBounds()
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .onGloballyPositioned { paneSize = it.size.toSize() }
                                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                                    scale = newScale
                                    offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSize)
                                    event.changes.forEach { it.consume() }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures { change, _ ->
                                        change.consume()
                                        if (paneSize.width > 0f) {
                                            wiperPos = (change.position.x / paneSize.width).coerceIn(0.02f, 0.98f)
                                        }
                                    }
                                },
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y,
                                        transformOrigin = TransformOrigin(0f, 0f),
                                    ),
                            ) {
                                val w = size.width
                                val h = size.height
                                val splitX = w * wiperPos
                                drawImage(currentBitmap, dstSize = IntSize(w.toInt(), h.toInt()))
                                clipRect(left = 0f, top = 0f, right = splitX, bottom = h) {
                                    drawImage(refBitmap, dstSize = IntSize(w.toInt(), h.toInt()))
                                }
                                drawLine(
                                    color = Color.Cyan,
                                    start = Offset(splitX, 0f),
                                    end = Offset(splitX, h),
                                    strokeWidth = 2.5f,
                                )
                            }
                            Text(
                                text = "◀ [A ${label("기준", "Ref")}] #${refFrame?.index ?: 0}",
                                color = AppColors.NeonOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Text(
                                text = "[B ${label("현재", "Cur")}] #${currentFrame?.index ?: 0} ▶",
                                color = AppColors.NeonBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }

                    FrameCompareDisplayMode.DIFF_HEATMAP -> {
                        val heatBm = diffStats?.diffBitmap ?: currentBitmap
                        PixelInspectorPreview(
                            bitmap = heatBm,
                            resetKey = currentFrame?.index to refFrame?.index,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    FrameCompareDisplayMode.BLINK -> {
                        val shownBm = if (blinkShowRef) refBitmap else currentBitmap
                        Box(modifier = Modifier.fillMaxSize()) {
                            PixelInspectorPreview(
                                bitmap = shownBm,
                                resetKey = Unit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.78f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(
                                    1.5.dp,
                                    if (blinkShowRef) AppColors.NeonOrange else AppColors.NeonBlue,
                                ),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp)
                                    .clickable { blinkShowRef = !blinkShowRef },
                            ) {
                                Text(
                                    text = if (blinkShowRef) {
                                        "📌 [A ${label("기준 프레임", "Reference")}] #${refFrame?.index ?: 0} (${refFrame?.type ?: '?'}) — ${label("클릭하여 B로 전환", "Click to switch to B")}"
                                    } else {
                                        "🎯 [B ${label("현재 프레임", "Current")}] #${currentFrame?.index ?: 0} (${currentFrame?.type ?: '?'}) — ${label("클릭하여 A로 전환", "Click to switch to A")}"
                                    },
                                    color = if (blinkShowRef) AppColors.NeonOrange else AppColors.NeonBlue,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }

                // Hover Pixel RGB & Delta readout in Side-by-Side mode
                hoverPixel?.let { (nx, ny) ->
                    val skiaA = refBitmap.asSkiaBitmap()
                    val skiaB = currentBitmap.asSkiaBitmap()
                    if (nx in 0 until min(skiaA.width, skiaB.width) && ny in 0 until min(skiaA.height, skiaB.height)) {
                        val cA = skiaA.getColor(nx, ny)
                        val cB = skiaB.getColor(nx, ny)
                        val dR = abs(((cA shr 16) and 0xFF) - ((cB shr 16) and 0xFF))
                        val dG = abs(((cA shr 8) and 0xFF) - ((cB shr 8) and 0xFF))
                        val dB = abs((cA and 0xFF) - (cB and 0xFF))
                        Surface(
                            color = Color.Black.copy(alpha = 0.82f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "XY(%d, %d)  |  A: #%06X  vs  B: #%06X  |  ΔRGB(%d, %d, %d)",
                                    nx, ny, cA and 0xFFFFFF, cB and 0xFFFFFF, dR, dG, dB,
                                ),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatCompactFrameBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
