package com.multiviewer.parser

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.multiviewer.ui.HistogramData
import com.multiviewer.ui.ImageForensicData
import org.jetbrains.skia.Image
import java.io.File

object ImageAnalyzer {
    fun analyze(file: File, root: BoxNode): ImageForensicData {
        val bytes = file.readBytes()
        var skiaImage = try {
            Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            null
        }
        
        // HEIC Fallback: Try to find primary item if direct loading fails
        if (skiaImage == null && file.extension.lowercase() in listOf("heic", "heif", "avif")) {
            skiaImage = tryExtractHeicPreview(file, root)
        }

        val bitmap = skiaImage?.toComposeImageBitmap()
        
        val histogram = if (skiaImage != null) calculateHistogram(skiaImage) else null
        
        // Analyze DQT from BoxNode tree
        var quality = 0
        var isModified = false
        var software: String? = null
        
        fun traverse(node: BoxNode) {
            if (node.type == "QuantizationTable") {
                val qStr = node.fields.find { it.name == "quality_estimate" }?.value
                if (qStr != null) {
                    quality = qStr.removePrefix("~").removeSuffix("%").toIntOrNull() ?: quality
                }
            }
            if (node.type == "IFD0" || node.type == "Exif") {
                software = node.fields.find { it.name == "Software" }?.value ?: software
            }
            node.children.forEach { traverse(it) }
        }
        traverse(root)
        
        if (software?.contains("Photoshop", ignoreCase = true) == true || 
            software?.contains("Adobe", ignoreCase = true) == true) {
            isModified = true
        }

        return ImageForensicData(
            bitmap = bitmap,
            histogram = histogram,
            dqtQuality = quality,
            software = software,
            isModified = isModified
        )
    }

    private fun tryExtractHeicPreview(file: File, root: BoxNode): Image? {
        // HEIC is ISOBMFF based. We look for 'iloc' items.
        // This is a simplified extraction of the first large item (usually the primary image)
        val iloc = findFirst(root) { it.type == "iloc" } ?: return null
        // If we can't decode the HEVC bitstream via Skia directly, 
        // we might be out of luck without a native HEIF decoder library.
        // However, many HEIC files contain a JPEG thumbnail in a 'thmb' or similar box.
        return null // Placeholder: Real HEVC decoding usually requires a specialized library like libheif
    }

    private fun calculateHistogram(image: Image): HistogramData {
        val r = FloatArray(256)
        val g = FloatArray(256)
        val b = FloatArray(256)
        val y = FloatArray(256)
        
        // Simple pixel sampling for performance
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val width = bitmap.width
        val height = bitmap.height
        val step = (width * height / 10000).coerceAtLeast(1) // Sample ~10k pixels
        
        for (i in 0 until width * height step step) {
            val px = i % width
            val py = i / width
            val color = bitmap.getColor(px, py)
            
            val cr = (color shr 16) and 0xFF
            val cg = (color shr 8) and 0xFF
            val cb = color and 0xFF
            val cy = (0.299 * cr + 0.587 * cg + 0.114 * cb).toInt().coerceIn(0, 255)
            
            r[cr]++
            g[cg]++
            b[cb]++
            y[cy]++
        }
        
        // Normalize
        val max = listOf(r.maxOrNull() ?: 1f, g.maxOrNull() ?: 1f, b.maxOrNull() ?: 1f, y.maxOrNull() ?: 1f).max()
        if (max > 0) {
            for (i in 0..255) {
                r[i] /= max
                g[i] /= max
                b[i] /= max
                y[i] /= max
            }
        }
        
        return HistogramData(r, g, b, y)
    }
}
