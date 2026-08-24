package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.multiviewer.ui.HistogramData
import com.multiviewer.ui.ImageForensicData
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File

private data class ThumbnailExtractionResult(val image: Image?, val hasThumbnailReference: Boolean)

object ImageAnalyzer {
    // Metadata/structure only -- deliberately does NOT touch the primary full-resolution pixel
    // decode (see decodePrimaryBitmapAndHistogram below), so this stays cheap regardless of image
    // size: a large JPEG's raster decode alone measured at ~45ms, and doing it here would block
    // the file from becoming interactive (structure tree, hex view) until it finished. Callers run
    // the primary decode separately, off AppState.openFile's synchronous path.
    fun analyze(file: File, root: BoxNode): ImageForensicData {
        println("File Structure Trace: ${file.name}")
        traceNodes(root, 0)

        val thumbnailResult = tryExtractEmbeddedJpeg(file, root)

        var quality = 0
        var isModified = false
        var software: String? = null
        var orientationCode: Int? = null
        var thumbOrientationCode: Int? = null

        fun traverse(node: BoxNode) {
            if (node.type == "QuantizationTable") {
                val qStr = node.fields.find { it.name == "quality_estimate" }?.value
                if (qStr != null) quality = qStr.removePrefix("~").removeSuffix("%").toIntOrNull() ?: quality
            }
            if (node.type == "IFD0" || node.type == "Exif") {
                software = node.fields.find { it.name == "Software" }?.value ?: software
                val orientVal = node.fields.find { it.name == "Orientation" }?.value
                if (orientVal != null) {
                    orientationCode = parseOrientationCode(orientVal) ?: orientationCode
                }
            }
            if (node.type == "IFD1") {
                val orientVal = node.fields.find { it.name == "Orientation" }?.value
                if (orientVal != null) {
                    thumbOrientationCode = parseOrientationCode(orientVal) ?: thumbOrientationCode
                }
            }
            node.children.forEach { traverse(it) }
        }
        traverse(root)

        if (software?.contains("Photoshop", ignoreCase = true) == true ||
            software?.contains("Adobe", ignoreCase = true) == true) isModified = true

        val heifOrientation = extractHeifOrientation(root)
        val heifCode = heifOrientationToCode(root)
        val primaryCode = orientationCode ?: heifCode
        val thumbCode = thumbOrientationCode ?: primaryCode

        val thumbBitmap = thumbnailResult.image?.let { img ->
            orientSkiaImage(img, thumbCode).toComposeImageBitmap()
        }

        val primaryOrientation = orientationCode?.let { "${orientationLabel(it)} ($it)" } ?: heifOrientation
        val thumbnailOrientation = thumbOrientationCode?.let { "${orientationLabel(it)} ($it)" } ?: primaryOrientation

        return ImageForensicData(
            bitmap = null,
            embeddedThumbnail = thumbBitmap,
            histogram = null,
            dqtQuality = quality,
            software = software,
            isModified = isModified,
            orientation = primaryOrientation,
            orientationCode = primaryCode,
            thumbnailOrientation = thumbnailOrientation,
            thumbnailOrientationCode = thumbCode,
            hasThumbnailReference = thumbnailResult.hasThumbnailReference,
        )
    }

    fun orientSkiaImage(image: Image, orientationCode: Int?): Image {
        if (orientationCode == null || orientationCode <= 1 || orientationCode > 8) return image

        val swapDims = orientationCode in listOf(5, 6, 7, 8)
        val targetW = if (swapDims) image.height else image.width
        val targetH = if (swapDims) image.width else image.height

        val surface = Surface.makeRasterN32Premul(targetW, targetH)
        val canvas = surface.canvas

        when (orientationCode) {
            2 -> { // Mirror horizontal
                canvas.translate(targetW.toFloat(), 0f)
                canvas.scale(-1f, 1f)
            }
            3 -> { // Rotate 180
                canvas.translate(targetW.toFloat(), targetH.toFloat())
                canvas.rotate(180f)
            }
            4 -> { // Mirror vertical
                canvas.translate(0f, targetH.toFloat())
                canvas.scale(1f, -1f)
            }
            5 -> { // Mirror horizontal and rotate 270 CW
                canvas.scale(1f, -1f)
                canvas.rotate(270f)
            }
            6 -> { // Rotate 90 CW
                canvas.translate(targetW.toFloat(), 0f)
                canvas.rotate(90f)
            }
            7 -> { // Mirror horizontal and rotate 90 CW
                canvas.translate(targetW.toFloat(), targetH.toFloat())
                canvas.rotate(90f)
                canvas.scale(-1f, 1f)
            }
            8 -> { // Rotate 270 CW
                canvas.translate(0f, targetH.toFloat())
                canvas.rotate(270f)
            }
        }

        canvas.drawImage(image, 0f, 0f)
        return surface.makeImageSnapshot()
    }

    fun orientImageBitmap(bitmap: ImageBitmap, orientationCode: Int?): ImageBitmap {
        if (orientationCode == null || orientationCode <= 1 || orientationCode > 8) return bitmap
        val skiaImage = Image.makeFromBitmap(bitmap.asSkiaBitmap())
        val oriented = orientSkiaImage(skiaImage, orientationCode)
        return oriented.toComposeImageBitmap()
    }

    private fun heifOrientationToCode(root: BoxNode): Int? {
        val irot = findPrimaryItemProperty(root, "irot") ?: findFirst(root) { it.type == "irot" }
        val imir = findPrimaryItemProperty(root, "imir") ?: findFirst(root) { it.type == "imir" }
        val angle = irot?.fields?.find { it.name == "angle" }?.value?.toIntOrNull()
        val axis = imir?.fields?.find { it.name == "axis" }?.value?.toIntOrNull()
        if (angle == null && axis == null) return null

        return when {
            axis == 0 && angle == 1 -> 7
            axis == 0 && angle == 3 -> 5
            axis == 0 -> 2
            axis == 1 -> 4
            angle == 1 -> 6
            angle == 2 -> 3
            angle == 3 -> 8
            angle == 0 -> 1
            else -> null
        }
    }

    private fun extractHeifOrientation(root: BoxNode): String? {
        val irot = findPrimaryItemProperty(root, "irot") ?: findFirst(root) { it.type == "irot" }
        val imir = findPrimaryItemProperty(root, "imir") ?: findFirst(root) { it.type == "imir" }
        val angle = irot?.fields?.find { it.name == "angle" }?.value?.toIntOrNull()
        val axis = imir?.fields?.find { it.name == "axis" }?.value?.toIntOrNull()
        if (angle == null && axis == null) return null

        val rotStr = when (angle) {
            1 -> "90° 회전"
            2 -> "180° 회전"
            3 -> "270° 회전"
            0 -> "0°"
            else -> if (angle != null) "${angle * 90}° 회전" else null
        }
        val mirStr = when (axis) {
            0 -> "좌우 반전"
            1 -> "상하 반전"
            else -> null
        }
        return when {
            mirStr != null && rotStr != null -> "$mirStr + $rotStr"
            rotStr != null -> rotStr
            mirStr != null -> mirStr
            else -> null
        }
    }

    internal fun parseOrientationCode(value: String): Int? {
        val trimmed = value.trim()
        trimmed.toIntOrNull()?.let { return if (it in 1..8) it else null }
        val lower = trimmed.lowercase()
        return when {
            lower.contains("horizontal (normal)") || lower == "top-left" || lower == "normal" -> 1
            lower.contains("mirror horizontal and rotate 270") || lower == "left-top" -> 5
            lower.contains("mirror horizontal and rotate 90") || lower == "right-bottom" -> 7
            lower.contains("mirror horizontal") || lower == "top-right" -> 2
            lower.contains("mirror vertical") || lower == "bottom-left" -> 4
            lower.contains("rotate 180") || lower == "bottom-right" -> 3
            lower.contains("rotate 90") || lower.contains("90 cw") || lower == "right-top" -> 6
            lower.contains("rotate 270") || lower.contains("270 cw") || lower == "left-bottom" -> 8
            else -> null
        }
    }

    // The expensive part split out of analyze() above: a full-resolution Skia raster decode plus
    // a histogram pass over it. Meant to be run off the caller's synchronous open path (see
    // AppState.openFile) -- null bitmap means Skia couldn't decode this format at all (HEIC/HEVC
    // and other HEIF-family stills), which callers use as the signal to fall back to
    // FfmpegImageSnapshotDecoder instead.
    fun decodePrimaryBitmapAndHistogram(file: File): Pair<ImageBitmap?, HistogramData?> {
        val primaryImage = try {
            Image.makeFromEncoded(file.readBytes())
        } catch (e: Exception) {
            null
        }
        val primaryBitmap = primaryImage?.toComposeImageBitmap()
        val histogram = primaryBitmap?.let { calculateHistogram(it.asSkiaBitmap()) }
        return primaryBitmap to histogram
    }

    private fun orientationLabel(code: Int): String = when (code) {
        1 -> "정상"
        2 -> "좌우 반전"
        3 -> "180° 회전"
        4 -> "상하 반전"
        5 -> "좌우 반전 + 270° 회전"
        6 -> "90° 회전"
        7 -> "좌우 반전 + 90° 회전"
        8 -> "270° 회전"
        else -> "알 수 없음"
    }

    private fun traceNodes(node: BoxNode, depth: Int) {
        val indent = "  ".repeat(depth)
        println("$indent- ${node.type} (${node.size} bytes) ${node.summary ?: ""}")
        node.children.forEach { traceNodes(it, depth + 1) }
    }

    private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): ThumbnailExtractionResult {
        val meta = findFirst(root) { it.type == "meta" }
        val iloc = if (meta != null) findFirst(meta) { it.type == "iloc" } else null
        val iinf = if (meta != null) findFirst(meta) { it.type == "iinf" } else null
        val iref = if (meta != null) findFirst(meta) { it.type == "iref" } else null
        val pitm = if (meta != null) findFirst(meta) { it.type == "pitm" } else null
        val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()

        // Identify thumbnail item IDs via iref — this is a structural fact about the file
        // (used for hasThumbnailReference) independent of whether we can decode those items' bytes.
        val thumbIds = mutableSetOf<Long>()
        if (primaryId != null && iref != null) {
            for (ref in iref.children) {
                if (ref.type == "thmb") {
                    val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
                    val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
                    if (toIds.contains(primaryId) && fromId != null) thumbIds.add(fromId)
                }
            }
        }
        val hasThumbnailReference = thumbIds.isNotEmpty()

        val image = ByteReader.open(file).use { reader ->
            // --- Strategy 1: ISOBMFF Metadata (HEIC/AVIF/MP4) ---
            // Only extract items that are explicitly referenced via 'thmb' in iref.
            // Never fall back to auxiliary items like GainMap (auxl) or depth maps.
            if (iloc != null && thumbIds.isNotEmpty()) {
                val idat = findFirst(root) { it.type == "idat" }
                val idatBase = if (idat != null) idat.offset + idat.headerSize else 0L

                for (id in thumbIds) {
                    val img = extractItemById(reader, iloc, id, idatBase)
                    if (img != null) return@use img
                }
            }

            // --- Strategy 2: EXIF IFD1 Thumbnail Scanning (Standard JPEG/TIFF) ---
            // 2a. Check for explicit ThumbnailImage box resolved from IFD1 (0x0201/0x0202)
            val thumbNode = findFirst(root) { it.type == "ThumbnailImage" }
            if (thumbNode != null && thumbNode.size in 64..2_000_000) {
                try {
                    val possibleImg = Image.makeFromEncoded(reader.readBytes(thumbNode.offset, thumbNode.size.toInt()))
                    if (possibleImg.width > 10) return@use possibleImg
                } catch (e: Exception) {}
            }

            // 2b. Search strictly within the Exif APP1 segment bounds (never beyond Exif)
            val exifNode = findFirst(root) { it.type == "Exif" }
            if (exifNode != null) {
                val limit = exifNode.offset + exifNode.size
                for (scanPos in findJpegMagicOffsets(reader, exifNode.offset, limit)) {
                    try {
                        val possibleImg = Image.makeFromEncoded(reader.readBytes(scanPos, (limit - scanPos).toInt().coerceAtMost(1_000_000)))
                        if (possibleImg.width > 10) return@use possibleImg
                    } catch (e: Exception) {}
                }
            }

            // No legitimate Exif or embedded thumbnail found -- do NOT fallback to GainMap, MPF secondary images, or auxl.
            null
        }

        return ThumbnailExtractionResult(image, hasThumbnailReference)
    }

    // Finds every 0xFFD8 (JPEG SOI) magic-byte offset in [start, end) by reading the region into
    // memory once and scanning in place, instead of one readUInt8() syscall pair per byte position
    // (which took up to several million individual seek+read calls on multi-MB files).
    private fun findJpegMagicOffsets(reader: ByteReader, start: Long, end: Long): List<Long> {
        if (end <= start) return emptyList()
        val bytes = reader.readBytes(start, (end - start).toInt())
        val offsets = mutableListOf<Long>()
        for (i in 0 until bytes.size - 1) {
            if (bytes[i].toInt() and 0xFF == 0xFF && bytes[i + 1].toInt() and 0xFF == 0xD8) {
                offsets.add(start + i)
            }
        }
        return offsets
    }

    private fun extractItemById(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): Image? {
        val itemNode = iloc.children.find { it.type == "item_$itemId" } ?: return null
        val method = itemNode.fields.find { it.name == "construction_method" }?.value?.toIntOrNull() ?: 0
        for (extent in itemNode.children) {
            val offsetVal = extent.fields.find { it.name == "offset" || it.name == "idat_relative_offset" }?.value?.toLongOrNull() ?: continue
            val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: continue
            if (length < 100) continue
            val absOffset = if (method == 1) idatBase + offsetVal else offsetVal
            try {
                val magic = reader.readBytes(absOffset, 2)
                if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
                    val img = Image.makeFromEncoded(reader.readBytes(absOffset, length.toInt()))
                    return img
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun calculateHistogram(bitmap: org.jetbrains.skia.Bitmap): HistogramData {
        val r = FloatArray(256)
        val g = FloatArray(256)
        val b = FloatArray(256)
        val y = FloatArray(256)
        val w = bitmap.width
        val h = bitmap.height
        val step = (w * h / 10000).coerceAtLeast(1)
        for (i in 0 until w * h step step) {
            val color = bitmap.getColor(i % w, i / w)
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF
            val cy = (0.299 * cr + 0.587 * cg + 0.114 * cb).toInt().coerceIn(0, 255)
            r[cr]++; g[cg]++; b[cb]++; y[cy]++
        }
        val max = listOf(r.maxOrNull() ?: 1f, g.maxOrNull() ?: 1f, b.maxOrNull() ?: 1f, y.maxOrNull() ?: 1f).max()
        if (max > 0) {
            for (i in 0..255) { r[i] /= max; g[i] /= max; b[i] /= max; y[i] /= max }
        }
        return HistogramData(r, g, b, y)
    }
}
