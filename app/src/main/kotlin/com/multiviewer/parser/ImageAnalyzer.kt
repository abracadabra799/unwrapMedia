package com.multiviewer.parser

import androidx.compose.ui.graphics.toComposeImageBitmap
import com.multiviewer.ui.HistogramData
import com.multiviewer.ui.ImageForensicData
import org.jetbrains.skia.Image
import java.io.File

object ImageAnalyzer {
    fun analyze(file: File, root: BoxNode): ImageForensicData {
        val bytes = file.readBytes()
        
        // 1. Primary Decode Attempt (Standard way)
        val primaryImage = try {
            Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            null
        }
        
        println("File Structure Trace: ${file.name}")
        traceNodes(root, 0)

        // 2. Extract Embedded Thumbnail (Regardless of primary success)
        val thumbnail = tryExtractEmbeddedJpeg(file, root)

        val primaryBitmap = primaryImage?.toComposeImageBitmap()
        val thumbBitmap = thumbnail?.toComposeImageBitmap()
        
        val histogram = if (primaryImage != null) calculateHistogram(primaryImage) else null
        
        var quality = 0
        var isModified = false
        var software: String? = null
        
        fun traverse(node: BoxNode) {
            if (node.type == "QuantizationTable") {
                val qStr = node.fields.find { it.name == "quality_estimate" }?.value
                if (qStr != null) quality = qStr.removePrefix("~").removeSuffix("%").toIntOrNull() ?: quality
            }
            if (node.type == "IFD0" || node.type == "Exif") {
                software = node.fields.find { it.name == "Software" }?.value ?: software
            }
            node.children.forEach { traverse(it) }
        }
        traverse(root)
        
        if (software?.contains("Photoshop", ignoreCase = true) == true || 
            software?.contains("Adobe", ignoreCase = true) == true) isModified = true

        return ImageForensicData(
            bitmap = primaryBitmap,
            embeddedThumbnail = thumbBitmap,
            histogram = histogram,
            dqtQuality = quality,
            software = software,
            isModified = isModified
        )
    }

    private fun traceNodes(node: BoxNode, depth: Int) {
        val indent = "  ".repeat(depth)
        println("$indent- ${node.type} (${node.size} bytes) ${node.summary ?: ""}")
        node.children.forEach { traceNodes(it, depth + 1) }
    }

    private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): Image? {
        val meta = findFirst(root) { it.type == "meta" }
        val iloc = if (meta != null) findFirst(meta) { it.type == "iloc" } else null
        val iinf = if (meta != null) findFirst(meta) { it.type == "iinf" } else null
        val iref = if (meta != null) findFirst(meta) { it.type == "iref" } else null
        val pitm = if (meta != null) findFirst(meta) { it.type == "pitm" } else null
        val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()

        ByteReader.open(file).use { reader ->
            // --- Strategy 1: ISOBMFF Metadata (HEIC/AVIF/MP4) ---
            if (iloc != null) {
                // Map item IDs to types
                val itemTypes = mutableMapOf<Long, String>()
                iinf?.children?.forEach { infe ->
                    if (infe.type == "infe") {
                        val id = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull()
                        val type = infe.fields.find { it.name == "item_type" }?.value
                        if (id != null && type != null) itemTypes[id] = type
                    }
                }

                // Identify thumbnail IDs via iref
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

                val idat = findFirst(root) { it.type == "idat" }
                val idatBase = if (idat != null) idat.offset + idat.headerSize else 0L

                // Try identified thumbnails first
                for (id in thumbIds) {
                    val img = extractItemById(reader, iloc, id, idatBase)
                    if (img != null) return img
                }
                
                // Try any JPEG items found in iinf
                for ((id, type) in itemTypes) {
                    if (id in thumbIds) continue
                    if (type.lowercase() == "jpeg" || type.lowercase() == "jpg") {
                        val img = extractItemById(reader, iloc, id, idatBase)
                        if (img != null) return img
                    }
                }
            }

            // --- Strategy 2: EXIF Scanning (Standard JPEG/TIFF) ---
            val exifNode = findFirst(root) { it.type == "Exif" || it.type == "APP1" }
            if (exifNode != null) {
                // Search for JPEG magic bytes in the EXIF/APP1 payload
                val limit = exifNode.offset + exifNode.size
                var scanPos = exifNode.offset
                while (scanPos < limit - 4) {
                    if (reader.readUInt8(scanPos) == 0xFF && reader.readUInt8(scanPos + 1) == 0xD8) {
                        try {
                            val possibleImg = Image.makeFromEncoded(reader.readBytes(scanPos, (limit - scanPos).toInt().coerceAtMost(1_000_000)))
                            if (possibleImg != null && possibleImg.width > 10) return possibleImg
                        } catch (e: Exception) {}
                    }
                    scanPos++
                }
            }

            // --- Strategy 3: Brute Force Magic Byte Scan (Last Ditch) ---
            val scanLimit = minOf(reader.length, 4_000_000L)
            var pos = 0L
            while (pos < scanLimit - 4) {
                if (reader.readUInt8(pos) == 0xFF && reader.readUInt8(pos + 1) == 0xD8) {
                    try {
                        val possibleImg = Image.makeFromEncoded(reader.readBytes(pos, (reader.length - pos).toInt().coerceAtMost(1_000_000)))
                        if (possibleImg != null && possibleImg.width > 10) return possibleImg
                    } catch (e: Exception) {}
                }
                pos++
            }
        }
        return null
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
                    if (img != null) return img
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun calculateHistogram(image: Image): HistogramData {
        val r = FloatArray(256)
        val g = FloatArray(256)
        val b = FloatArray(256)
        val y = FloatArray(256)
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
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
