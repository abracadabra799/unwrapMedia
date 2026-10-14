package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object BmpSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val fileHeaderNode = root.children.find { it.type == "BITMAPFILEHEADER" }
        val dibNode = root.children.find { it.type != "BITMAPFILEHEADER" }

        val blocks = mutableListOf<SpecBlock>()
        val arrows = mutableListOf<SpecArrow>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        // 1. BITMAPFILEHEADER Block (14 bytes)
        val fileHeaderSize = 14L
        val sig = fileHeaderNode?.fields?.find { it.name == "signature" }?.value ?: "BM"
        val declFileSize = fileHeaderNode?.fields?.find { it.name == "file_size" }?.value?.toLongOrNull() ?: fileSize
        val offBits = fileHeaderNode?.fields?.find { it.name == "pixel_data_offset" }?.value?.toLongOrNull() ?: 54L
        val res1 = fileHeaderNode?.fields?.find { it.name == "reserved1" }?.value ?: "0"
        val res2 = fileHeaderNode?.fields?.find { it.name == "reserved2" }?.value ?: "0"

        val fileHeaderRows = listOf(
            SpecRow(listOf(SpecCell("bfType", "Signature", sig, 2L, colSpan = 12, role = RegionRole.MARKER)), 0L),
            SpecRow(listOf(SpecCell("bfSize", "File Size", "$declFileSize B", 4L, colSpan = 12, role = RegionRole.MARKER)), 2L),
            SpecRow(listOf(
                SpecCell("bfReserved1", "Reserved1", res1, 2L, colSpan = 6, role = RegionRole.MARKER),
                SpecCell("bfReserved2", "Reserved2", res2, 2L, colSpan = 6, role = RegionRole.MARKER),
            ), 6L),
            SpecRow(listOf(SpecCell("bfOffBits", "File Offset to PixelArray", "$offBits", 4L, colSpan = 12, role = RegionRole.MARKER)), 10L),
        )
        blocks += SpecBlock("block_file_header", "Bitmap File Header", "BITMAPFILEHEADER", 0L, fileHeaderSize, RegionRole.MARKER, fileHeaderRows)

        // File size bracket
        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        // 2. DIB Header Block
        val dibStart = 14L
        val dibSize = dibNode?.size ?: 40L
        val width = dibNode?.fields?.find { it.name == "width" }?.value?.toIntOrNull() ?: 1
        val height = dibNode?.fields?.find { it.name == "height" }?.value?.toIntOrNull() ?: 1
        val planes = dibNode?.fields?.find { it.name == "planes" }?.value ?: "1"
        val bitCount = dibNode?.fields?.find { it.name == "bit_count" }?.value?.toIntOrNull() ?: 24
        val compression = dibNode?.fields?.find { it.name == "compression" }?.value ?: "None (BI_RGB)"
        val imageSize = dibNode?.fields?.find { it.name == "image_size" }?.value ?: "0"
        val xpels = dibNode?.fields?.find { it.name == "x_pixels_per_meter" }?.value ?: "2835"
        val ypels = dibNode?.fields?.find { it.name == "y_pixels_per_meter" }?.value ?: "2835"
        val colorsUsed = dibNode?.fields?.find { it.name == "colors_used" }?.value?.toIntOrNull() ?: 0
        val colorsImportant = dibNode?.fields?.find { it.name == "colors_important" }?.value ?: "0"

        val dibRows = mutableListOf<SpecRow>()
        dibRows += SpecRow(listOf(
            SpecCell("biSize", "DIB Header Size", "$dibSize B", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
            SpecCell("biWidth", "Image Width (w)", "$width px", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
            SpecCell("biHeight", "Image Height (h)", "$height px", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
        ), dibStart)

        dibRows += SpecRow(listOf(
            SpecCell("biPlanes", "Planes", planes, 2L, colSpan = 2, role = RegionRole.CODEC_SETUP),
            SpecCell("biBitCount", "Bits per Pixel", "$bitCount bpp", 2L, colSpan = 2, role = RegionRole.CODEC_SETUP),
            SpecCell("biCompression", "Compression", compression, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
            SpecCell("biSizeImage", "Image Size", "$imageSize B", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
        ), dibStart + 12L)

        dibRows += SpecRow(listOf(
            SpecCell("biXPels", "X Pixels Per Meter", xpels, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
            SpecCell("biYPels", "Y Pixels Per Meter", ypels, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
            SpecCell("biClrUsed", "Colors in Color Table", "$colorsUsed", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
        ), dibStart + 24L)

        // Remaining fields for BITMAPINFOHEADER or V4/V5
        val remRowCells = mutableListOf(SpecCell("biClrImportant", "Important Color Count", colorsImportant, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP))
        val redMask = dibNode?.fields?.find { it.name == "red_mask" }?.value
        val greenMask = dibNode?.fields?.find { it.name == "green_mask" }?.value
        if (redMask != null) remRowCells += SpecCell("bV4RedMask", "Red channel bitmask", redMask, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP)
        if (greenMask != null) remRowCells += SpecCell("bV4GreenMask", "Green channel bitmask", greenMask, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP)
        // Pad to colSpan 12 if not full
        val currentSpan = remRowCells.sumOf { it.colSpan }
        if (currentSpan < 12) {
            val last = remRowCells.removeAt(remRowCells.lastIndex)
            remRowCells += last.copy(colSpan = last.colSpan + (12 - currentSpan))
        }
        dibRows += SpecRow(remRowCells, dibStart + 36L)

        // DIB V4/V5 extra rows if present
        val blueMask = dibNode?.fields?.find { it.name == "blue_mask" }?.value
        val alphaMask = dibNode?.fields?.find { it.name == "alpha_mask" }?.value
        val csType = dibNode?.fields?.find { it.name == "color_space_type" }?.value
        if (blueMask != null || csType != null) {
            val v4Cells = mutableListOf<SpecCell>()
            if (blueMask != null) v4Cells += SpecCell("bV4BlueMask", "Blue channel bitmask", blueMask, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP)
            if (alphaMask != null) v4Cells += SpecCell("bV4AlphaMask", "Alpha channel bitmask", alphaMask, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP)
            if (csType != null) v4Cells += SpecCell("bV4CSType", "Color Space Type", csType, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP)
            val v4Span = v4Cells.sumOf { it.colSpan }
            if (v4Span in 1..11) {
                val last = v4Cells.removeAt(v4Cells.lastIndex)
                v4Cells += last.copy(colSpan = last.colSpan + (12 - v4Span))
            }
            if (v4Cells.isNotEmpty()) dibRows += SpecRow(v4Cells, dibStart + 48L)

            val endpoints = dibNode.fields.find { it.name == "endpoints" }?.value
            if (endpoints != null) {
                dibRows += SpecRow(listOf(SpecCell("bV4Endpoints", "Color Space Endpoints (36 Bytes)", endpoints, 36L, colSpan = 12, role = RegionRole.CODEC_SETUP)), dibStart + 60L)
            }
        }

        val dibHeaderType = dibNode?.type ?: "BITMAPINFOHEADER"
        blocks += SpecBlock("block_dib_header", "DIB Header", dibHeaderType, dibStart, dibSize, RegionRole.CODEC_SETUP, dibRows)
        brackets += SpecBracket(dibStart, dibStart + dibSize, "DIB Header Size: $dibSize B", color = 0xFF0078D4L)

        // 3. Color Table (if present)
        var cursor = dibStart + dibSize
        val hasColorTable = colorsUsed > 0 || (bitCount <= 8 && offBits > cursor)
        val numColors = if (colorsUsed > 0) colorsUsed else if (bitCount <= 8) (1 shl bitCount) else 0
        if (hasColorTable && numColors > 0) {
            val colorEntrySize = if (dibSize == 12L) 3L else 4L
            val colorTableSize = numColors * colorEntrySize
            val tableRows = mutableListOf<SpecRow>()
            val sampleCount = minOf(numColors, 3)
            for (i in 0 until sampleCount) {
                tableRows += SpecRow(listOf(SpecCell("color_$i", "Color Definition (index $i)", null, colorEntrySize, colSpan = 12, role = RegionRole.METADATA)), cursor + i * colorEntrySize)
            }
            if (numColors > sampleCount) {
                tableRows += SpecRow(listOf(SpecCell("color_dots", "◆  ◆  ◆", "${numColors - sampleCount} more entries", (numColors - sampleCount) * colorEntrySize, colSpan = 12, role = RegionRole.METADATA)), cursor + sampleCount * colorEntrySize)
                tableRows += SpecRow(listOf(SpecCell("color_last", "Color Definition (index ${numColors - 1})", null, colorEntrySize, colSpan = 12, role = RegionRole.METADATA)), cursor + (numColors - 1) * colorEntrySize)
            }
            blocks += SpecBlock("block_color_table", "Color Table", "$numColors entries", cursor, colorTableSize, RegionRole.METADATA, tableRows)
            cursor += colorTableSize
        }

        // 4. GAP1 (optional padding between headers and pixel array)
        if (offBits > cursor) {
            val gapSize = offBits - cursor
            val gapRow = SpecRow(listOf(SpecCell("gap1", "GAP1", "(optional alignment padding)", gapSize, colSpan = 12, role = RegionRole.UNPARSED, isPadding = true)), cursor)
            blocks += SpecBlock("block_gap1", "GAP1", "(optional)", cursor, gapSize, RegionRole.UNPARSED, listOf(gapRow))
            cursor = offBits
        }

        // 5. Image Data (PixelArray[x, y])
        val pixelArrayStart = offBits
        val absHeight = kotlin.math.abs(height)
        val absWidth = kotlin.math.abs(width)
        val rowStride = ((absWidth * bitCount + 31) / 32) * 4
        val rawRowBytes = (absWidth * bitCount + 7) / 8
        val paddingBytes = (rowStride - rawRowBytes).toLong()

        val pixelArrayLength = if (fileSize > pixelArrayStart) fileSize - pixelArrayStart else (rowStride * absHeight).toLong()
        val pixelRows = mutableListOf<SpecRow>()

        fun makePixelRow(y: Int, rowOff: Long): SpecRow {
            val cells = mutableListOf<SpecCell>()
            if (absWidth <= 4) {
                val span = if (paddingBytes > 0) 10 / absWidth else 12 / absWidth
                for (x in 0 until absWidth) {
                    cells += SpecCell("pixel_${x}_$y", "Pixel[$x, $y]", null, (bitCount / 8).coerceAtLeast(1).toLong(), colSpan = span, role = RegionRole.MEDIA_DATA)
                }
            } else {
                cells += SpecCell("pixel_0_$y", "Pixel[0, $y]", null, (bitCount / 8).coerceAtLeast(1).toLong(), colSpan = 2, role = RegionRole.MEDIA_DATA)
                cells += SpecCell("pixel_1_$y", "Pixel[1, $y]", null, (bitCount / 8).coerceAtLeast(1).toLong(), colSpan = 2, role = RegionRole.MEDIA_DATA)
                cells += SpecCell("pixel_dots_$y", "…", null, 0L, colSpan = 4, role = RegionRole.MEDIA_DATA)
                cells += SpecCell("pixel_last_$y", "Pixel[${absWidth - 1}, $y]", null, (bitCount / 8).coerceAtLeast(1).toLong(), colSpan = if (paddingBytes > 0) 2 else 4, role = RegionRole.MEDIA_DATA)
            }
            if (paddingBytes > 0) {
                cells += SpecCell("pad_$y", "Padding", "$paddingBytes B", paddingBytes, colSpan = 2, role = RegionRole.UNPARSED, isPadding = true)
            }
            return SpecRow(cells, rowOff)
        }

        if (absHeight <= 4) {
            // Render from top down (BMP stores bottom-up by default, so display row h-1 down to 0)
            for (y in (absHeight - 1) downTo 0) {
                pixelRows += makePixelRow(y, pixelArrayStart + (absHeight - 1 - y) * rowStride)
            }
        } else {
            // Display top rows, ellipsis, and bottom rows
            pixelRows += makePixelRow(absHeight - 1, pixelArrayStart)
            pixelRows += makePixelRow(absHeight - 2, pixelArrayStart + rowStride)
            pixelRows += SpecRow(listOf(SpecCell("pixel_mid_dots", "◇   ◇   ◇", "… ${absHeight - 4} more rows …", (absHeight - 4L) * rowStride, colSpan = 12, role = RegionRole.MEDIA_DATA)), pixelArrayStart + 2 * rowStride)
            pixelRows += makePixelRow(1, pixelArrayStart + (absHeight - 2) * rowStride)
            pixelRows += makePixelRow(0, pixelArrayStart + (absHeight - 1) * rowStride)
        }

        val pixelBlock = SpecBlock("block_pixel_array", "Image Data", "PixelArray[x,y]", pixelArrayStart, pixelArrayLength, RegionRole.MEDIA_DATA, pixelRows)
        blocks += pixelBlock

        // Arrow from bfOffBits to PixelArray block
        arrows += SpecArrow("bfOffBits", pixelBlock.id, color = 0xFF202124L, label = "File Offset to PixelArray")

        // Callout if row padding is required
        if (paddingBytes > 0) {
            callouts += SpecCallout("block_pixel_array", "Pad row size to a multiple of 4 Bytes (+$paddingBytes B per row)")
        }

        return SpecDiagram(
            title = title,
            fileSize = fileSize,
            blocks = blocks,
            arrows = arrows,
            brackets = brackets,
            callouts = callouts,
            columnBytes = 12,
        )
    }
}
