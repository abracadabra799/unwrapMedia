package com.multiviewer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.util.ClipboardUtil
import java.awt.BasicStroke
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal class LightweightHistogram(
    val lumaBins: FloatArray,
    val rBins: FloatArray,
    val gBins: FloatArray,
    val bBins: FloatArray,
    val meanLuma: Double,
    val clipBlackPercent: Double,
    val clipWhitePercent: Double,
)

/**
 * Computes a normalized 256-bin RGB + BT.709 Luminance histogram using spatial subsampling
 * ([step] pixels on each axis) so even 4K/8K bitmaps finish in ~1–2 ms.
 */
internal fun computeLightweightHistogram(
    bitmap: ImageBitmap,
    step: Int = 4,
): LightweightHistogram? {
    return try {
        val skia = bitmap.asSkiaBitmap()
        val w = skia.width
        val h = skia.height
        if (w <= 0 || h <= 0) return null

        val stride = step.coerceAtLeast(1)
        val lumaCounts = IntArray(256)
        val rCounts = IntArray(256)
        val gCounts = IntArray(256)
        val bCounts = IntArray(256)

        var sampleCount = 0L
        var sumLuma = 0L
        var blackClip = 0L
        var whiteClip = 0L

        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val c = skia.getColor(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = ((r * 54 + g * 183 + b * 19) shr 8).coerceIn(0, 255)

                rCounts[r]++
                gCounts[g]++
                bCounts[b]++
                lumaCounts[luma]++

                sumLuma += luma
                if (luma <= 2) blackClip++
                if (luma >= 253) whiteClip++
                sampleCount++
                x += stride
            }
            y += stride
        }

        if (sampleCount <= 0L) return null

        val maxPeak = maxOf(
            lumaCounts.maxOrNull() ?: 1,
            rCounts.maxOrNull() ?: 1,
            gCounts.maxOrNull() ?: 1,
            bCounts.maxOrNull() ?: 1,
            1,
        ).toFloat()

        val lumaNorm = FloatArray(256) { (lumaCounts[it] / maxPeak).coerceIn(0f, 1f) }
        val rNorm = FloatArray(256) { (rCounts[it] / maxPeak).coerceIn(0f, 1f) }
        val gNorm = FloatArray(256) { (gCounts[it] / maxPeak).coerceIn(0f, 1f) }
        val bNorm = FloatArray(256) { (bCounts[it] / maxPeak).coerceIn(0f, 1f) }

        LightweightHistogram(
            lumaBins = lumaNorm,
            rBins = rNorm,
            gBins = gNorm,
            bBins = bNorm,
            meanLuma = sumLuma.toDouble() / sampleCount.toDouble(),
            clipBlackPercent = (blackClip.toDouble() * 100.0) / sampleCount.toDouble(),
            clipWhitePercent = (whiteClip.toDouble() * 100.0) / sampleCount.toDouble(),
        )
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun MiniHistogramOverlay(
    histogram: LightweightHistogram,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.76f),
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.28f)),
        modifier = modifier.width(156.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Canvas(modifier = Modifier.fillMaxWidth().height(42.dp)) {
                val w = size.width
                val h = size.height
                fun buildChannelPath(bins: FloatArray): Path {
                    val path = Path()
                    for (i in 0..255) {
                        val px = (i / 255f) * w
                        val py = h - (bins[i] * (h - 2f))
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    return path
                }
                drawPath(buildChannelPath(histogram.rBins), Color(0xFFE06C75).copy(alpha = 0.65f), style = Stroke(1.1f))
                drawPath(buildChannelPath(histogram.gBins), Color(0xFF98C379).copy(alpha = 0.65f), style = Stroke(1.1f))
                drawPath(buildChannelPath(histogram.bBins), Color(0xFF61AFEF).copy(alpha = 0.65f), style = Stroke(1.1f))
                drawPath(buildChannelPath(histogram.lumaBins), Color.White.copy(alpha = 0.9f), style = Stroke(1.3f))
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format(Locale.US, "Ȳ %.0f", histogram.meanLuma),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = String.format(Locale.US, "B:%.1f%% W:%.1f%%", histogram.clipBlackPercent, histogram.clipWhitePercent),
                    color = Color(0xFFABB2BF),
                    fontSize = 8.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private val IGNORED_QUICK_DIFF_KEYS = setOf(
    "file name", "filename", "파일 이름", "파일명",
    "file path", "filepath", "경로", "파일 경로",
)

/**
 * Extracts the most relevant non-identical metadata rows for the 1-line Quick Diff Strip
 * in the Visual Compare tab, omitting trivial filename/path differences.
 */
internal fun summarizeKeyMetadataDiffs(
    rows: List<MetadataDiffRow>,
    maxItems: Int = 5,
): List<MetadataDiffRow> {
    return rows
        .filter { it.isDifferent && it.key.trim().lowercase(Locale.US) !in IGNORED_QUICK_DIFF_KEYS }
        .take(maxItems.coerceAtLeast(1))
}

/**
 * Composites the current comparison view (Side-by-Side, Split Wiper, Diff Heatmap, or Blink)
 * into a single opaque `TYPE_INT_RGB` [BufferedImage] with file/metric labels for 1-click clipboard export.
 */
internal fun renderComparisonSnapshotBufferedImage(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    mode: VisualCompareMode,
    wiperPos: Float,
    diffBitmap: ImageBitmap?,
    labelA: String,
    labelB: String,
    metricsSummary: String? = null,
): BufferedImage {
    val imgA = imageBitmapToRgbBufferedImage(bitmapA)
    val imgB = imageBitmapToRgbBufferedImage(bitmapB)
    val headerH = 36

    return when (mode) {
        VisualCompareMode.SIDE_BY_SIDE -> {
            val gap = 8
            val outW = imgA.width + gap + imgB.width
            val outH = max(imgA.height, imgB.height) + headerH
            val out = BufferedImage(outW.coerceAtLeast(1), outH.coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.color = java.awt.Color(20, 22, 28)
                g.fillRect(0, 0, outW, outH)
                g.drawImage(imgA, 0, headerH, null)
                g.drawImage(imgB, imgA.width + gap, headerH, null)
                g.font = Font(Font.MONOSPACED, Font.BOLD, 15)
                g.color = java.awt.Color(97, 175, 239)
                g.drawString("[A] $labelA (${imgA.width}x${imgA.height})", 10, 24)
                g.color = java.awt.Color(152, 195, 121)
                g.drawString("[B] $labelB (${imgB.width}x${imgB.height})", imgA.width + gap + 10, 24)
            } finally {
                g.dispose()
            }
            out
        }

        VisualCompareMode.SPLIT_WIPER -> {
            val outW = max(imgA.width, imgB.width).coerceAtLeast(1)
            val outH = (max(imgA.height, imgB.height) + headerH).coerceAtLeast(1)
            val splitX = (outW * wiperPos.coerceIn(0f, 1f)).toInt()
            val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.color = java.awt.Color(20, 22, 28)
                g.fillRect(0, 0, outW, outH)
                g.drawImage(imgB, 0, headerH, outW, outH - headerH, null)
                val oldClip = g.clip
                g.setClip(0, headerH, splitX, outH - headerH)
                g.drawImage(imgA, 0, headerH, outW, outH - headerH, null)
                g.clip = oldClip
                g.color = java.awt.Color(0, 229, 255)
                g.stroke = BasicStroke(3f)
                g.drawLine(splitX, headerH, splitX, outH)
                g.font = Font(Font.MONOSPACED, Font.BOLD, 14)
                g.color = java.awt.Color(97, 175, 239)
                g.drawString("◀ [A] $labelA", 10, 24)
                g.color = java.awt.Color(152, 195, 121)
                val bText = "[B] $labelB ▶"
                val bw = g.fontMetrics.stringWidth(bText)
                g.drawString(bText, (outW - bw - 10).coerceAtLeast(160), 24)
            } finally {
                g.dispose()
            }
            out
        }

        VisualCompareMode.DIFF_HEATMAP -> {
            val base = (diffBitmap ?: bitmapA).let { imageBitmapToRgbBufferedImage(it) }
            val outW = base.width.coerceAtLeast(1)
            val outH = (base.height + headerH).coerceAtLeast(1)
            val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.color = java.awt.Color(20, 22, 28)
                g.fillRect(0, 0, outW, outH)
                g.drawImage(base, 0, headerH, null)
                g.font = Font(Font.MONOSPACED, Font.BOLD, 14)
                g.color = java.awt.Color(255, 213, 79)
                val title = buildString {
                    append("Diff Heatmap: $labelA vs $labelB")
                    if (!metricsSummary.isNullOrBlank()) append("  |  $metricsSummary")
                }
                g.drawString(title, 10, 24)
            } finally {
                g.dispose()
            }
            out
        }

        VisualCompareMode.BLINK -> {
            imageBitmapToRgbBufferedImage(bitmapA, "[A] $labelA")
        }
    }
}

internal fun copyComparisonViewToClipboard(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    mode: VisualCompareMode,
    wiperPos: Float,
    diffBitmap: ImageBitmap?,
    labelA: String,
    labelB: String,
    metricsSummary: String? = null,
): Boolean {
    return try {
        val img = renderComparisonSnapshotBufferedImage(
            bitmapA = bitmapA,
            bitmapB = bitmapB,
            mode = mode,
            wiperPos = wiperPos,
            diffBitmap = diffBitmap,
            labelA = labelA,
            labelB = labelB,
            metricsSummary = metricsSummary,
        )
        ClipboardUtil.copyImage(img)
        true
    } catch (_: Exception) {
        false
    }
}
