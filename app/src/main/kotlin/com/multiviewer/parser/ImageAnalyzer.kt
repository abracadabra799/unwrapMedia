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
        
        // HEIC/AVIF Fallback: Try to find primary item or embedded JPEG if direct loading fails
        if (skiaImage == null && file.extension.lowercase() in listOf("heic", "heif", "avif")) {
            println("Skia failed to decode ${file.extension}, attempting targeted JPEG extraction...")
            skiaImage = tryExtractEmbeddedJpeg(file, root)
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

    private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): Image? {
        val meta = findFirst(root) { it.type == "meta" } ?: return null
        val pitm = findFirst(meta) { it.type == "pitm" }
        val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()
        val iref = findFirst(meta) { it.type == "iref" }
        val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
        
        // 1. Identify potential thumbnail IDs
        val potentialThumbIds = mutableSetOf<Long>()
        if (primaryId != null && iref != null) {
            for (ref in iref.children) {
                if (ref.type == "thmb") {
                    val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
                    val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
                    if (toIds.contains(primaryId) && fromId != null) {
                        potentialThumbIds.add(fromId)
                        println("Identified thumbnail ID $fromId for primary ID $primaryId")
                    }
                }
            }
        }
        
        // 2. Find 'idat' box offset for construction method 1
        val idat = findFirst(root) { it.type == "idat" }
        val idatPayloadOffset = if (idat != null) idat.offset + idat.headerSize else 0L

        ByteReader.open(file).use { reader ->
            // Try potential thumbnails first
            for (id in potentialThumbIds) {
                val img = extractItemById(reader, iloc, id, idatPayloadOffset)
                if (img != null) return img
            }
            
            // Fallback: Scan all items in iloc for JPEG
            for (item in iloc.children) {
                val itemId = item.type.removePrefix("item_").toLongOrNull() ?: continue
                if (itemId in potentialThumbIds) continue // Already tried
                
                val img = extractItemById(reader, iloc, itemId, idatPayloadOffset)
                if (img != null) return img
            }
        }
        return null
    }

    private fun extractItemById(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): Image? {
        val itemNode = iloc.children.find { it.type == "item_$itemId" } ?: return null
        val methodField = itemNode.fields.find { it.name == "construction_method" }
        val method = methodField?.value?.toIntOrNull() ?: 0
        
        for (extent in itemNode.children) {
            val offsetVal = extent.fields.find { it.name == "offset" || it.name == "idat_relative_offset" }?.value?.toLongOrNull() ?: continue
            val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: continue
            
            if (length < 4) continue
            
            val absoluteOffset = if (method == 1) idatBase + offsetVal else offsetVal
            
            try {
                val header = reader.readBytes(absoluteOffset, 2)
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) {
                    println("Extracting JPEG from item $itemId at absolute offset $absoluteOffset")
                    val jpegBytes = reader.readBytes(absoluteOffset, length.toInt())
                    val img = Image.makeFromEncoded(jpegBytes)
                    if (img != null) return img
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return null
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
