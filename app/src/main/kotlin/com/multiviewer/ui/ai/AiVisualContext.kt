package com.multiviewer.ui.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Longest edge of an image crop sent to a provider. */
const val MAX_AI_CROP_EDGE = 1024

/** Screen drags shorter than this (px) are clicks, not marked regions. */
private const val MIN_MARK_PX = 4f

data class ImagePoint(val x: Int, val y: Int)

data class ImageRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Where the user is looking and pointing, in the image's native pixels. Captured from the preview's
 * own transform at send time; never a continuous screen capture.
 */
data class AiVisualContext(
    val source: String,
    val imageWidth: Int,
    val imageHeight: Int,
    /** The part of the image visible in the preview (zoom/pan). */
    val visible: ImageRegion,
    val pointer: ImagePoint?,
    val marked: ImageRegion?,
)

// Same model as PixelInspectorPreview: ContentScale.Fit inside the box, then a top-left-pivot
// graphicsLayer(scale, offset). All values are in the same pixel space, so HiDPI cancels out.
private fun toNative(position: Offset, box: Size, native: Size, scale: Float, offset: Offset): Offset? {
    if (box.width <= 0f || box.height <= 0f || native.width <= 0f || native.height <= 0f || scale <= 0f) return null
    val fit = min(box.width / native.width, box.height / native.height)
    val left = (box.width - native.width * fit) / 2f
    val top = (box.height - native.height * fit) / 2f
    return Offset(((position.x - offset.x) / scale - left) / fit, ((position.y - offset.y) / scale - top) / fit)
}

fun viewToImagePoint(position: Offset, box: Size, native: Size, scale: Float, offset: Offset): ImagePoint? {
    val p = toNative(position, box, native, scale, offset) ?: return null
    if (p.x < 0f || p.y < 0f || p.x >= native.width || p.y >= native.height) return null
    return ImagePoint(floor(p.x).toInt(), floor(p.y).toInt())
}

private fun regionBetween(a: Offset, b: Offset, native: Size): ImageRegion? {
    val x0 = floor(min(a.x, b.x).coerceIn(0f, native.width)).toInt()
    val y0 = floor(min(a.y, b.y).coerceIn(0f, native.height)).toInt()
    val x1 = ceil(max(a.x, b.x).coerceIn(0f, native.width)).toInt()
    val y1 = ceil(max(a.y, b.y).coerceIn(0f, native.height)).toInt()
    return if (x1 > x0 && y1 > y0) ImageRegion(x0, y0, x1 - x0, y1 - y0) else null
}

/** A drag from [start] to [end] on screen, clamped to the image; null for a click or a mark outside it. */
fun viewRectToImageRegion(start: Offset, end: Offset, box: Size, native: Size, scale: Float, offset: Offset): ImageRegion? {
    if (kotlin.math.abs(end.x - start.x) < MIN_MARK_PX || kotlin.math.abs(end.y - start.y) < MIN_MARK_PX) return null
    val a = toNative(start, box, native, scale, offset) ?: return null
    val b = toNative(end, box, native, scale, offset) ?: return null
    return regionBetween(a, b, native)
}

fun visibleImageRegion(box: Size, native: Size, scale: Float, offset: Offset): ImageRegion? {
    val a = toNative(Offset.Zero, box, native, scale, offset) ?: return null
    val b = toNative(Offset(box.width, box.height), box, native, scale, offset) ?: return null
    return regionBetween(a, b, native)
}

/** The image position of [point]/[region] on screen, for drawing the marks over the preview. */
fun imageToView(point: Offset, box: Size, native: Size, scale: Float, offset: Offset): Offset {
    val fit = min(box.width / native.width, box.height / native.height)
    val left = (box.width - native.width * fit) / 2f
    val top = (box.height - native.height * fit) / 2f
    return Offset(offset.x + scale * (left + point.x * fit), offset.y + scale * (top + point.y * fit))
}

/**
 * The pixels of [region] only, downscaled to [MAX_AI_CROP_EDGE] and redrawn into a fresh opaque image, so
 * nothing outside the region and no source metadata (EXIF/GPS/XMP) can travel with it.
 */
fun cropForAi(source: BufferedImage, region: ImageRegion): BufferedImage {
    val x = region.x.coerceIn(0, source.width - 1)
    val y = region.y.coerceIn(0, source.height - 1)
    val w = region.width.coerceIn(1, source.width - x)
    val h = region.height.coerceIn(1, source.height - y)
    val factor = min(1.0, MAX_AI_CROP_EDGE.toDouble() / max(w, h))
    val outW = max(1, (w * factor).toInt())
    val outH = max(1, (h * factor).toInt())
    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g = out.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, outW, outH)
        g.drawImage(source, 0, 0, outW, outH, x, y, x + w, y + h, null)
    } finally {
        g.dispose()
    }
    return out
}

/** Prompt section for [visual]; says plainly whether a crop is attached and why not. */
fun renderVisualContext(visual: AiVisualContext, attachedImage: ImageRegion?, imageOmittedReason: String?): String = buildString {
    appendLine("### [화면에서 보고 있는 위치 (표시 이미지 픽셀 좌표)]")
    appendLine("- 대상: ${visual.source}, 표시 크기 ${visual.imageWidth}x${visual.imageHeight} (EXIF 방향 회전이 적용된 화면 기준이며, 저장된 픽셀 배열과 x/y가 다를 수 있음)")
    with(visual.visible) { appendLine("- 화면에 보이는 영역: x=$x, y=$y, w=$width, h=$height") }
    visual.pointer?.let { appendLine("- 사용자가 가리킨 점: (${it.x}, ${it.y})") }
    visual.marked?.let { appendLine("- 사용자가 표시한 영역: x=${it.x}, y=${it.y}, w=${it.width}, h=${it.height}") }
    when {
        attachedImage != null -> with(attachedImage) { appendLine("- 첨부 이미지: 위 좌표 x=$x, y=$y, w=$width, h=$height 영역을 잘라 축소한 PNG (메타데이터 없음)") }
        imageOmittedReason != null -> appendLine("- 첨부 이미지: 없음 — $imageOmittedReason. 좌표만으로 답하세요.")
    }
}
