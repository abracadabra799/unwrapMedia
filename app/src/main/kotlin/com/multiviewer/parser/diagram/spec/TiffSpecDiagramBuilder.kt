package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object TiffSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val arrows = mutableListOf<SpecArrow>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        // 1. TIFF Header (8 Bytes)
        val headerRows = listOf(
            SpecRow(listOf(
                SpecCell("tiff_order", "Byte Order", "II (Little-Endian) / MM", 2L, colSpan = 4, role = RegionRole.MARKER),
                SpecCell("tiff_magic", "Version Magic", "42 (0x002A)", 2L, colSpan = 4, role = RegionRole.MARKER),
                SpecCell("tiff_ifd0_off", "Offset to IFD0", "8 (or pointer)", 4L, colSpan = 4, role = RegionRole.MARKER),
            ), 0L),
        )
        blocks += SpecBlock("block_tiff_header", "TIFF File Header", "8 Bytes Header", 0L, 8L, RegionRole.MARKER, headerRows)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        val ifds = children.filter { it.type.contains("IFD", ignoreCase = true) || it.type == "Exif" || it.type == "GPS" }
        val strips = children.filter { it.type.contains("Strip", ignoreCase = true) || it.type.contains("Tile", ignoreCase = true) || it.type.contains("Data", ignoreCase = true) }

        // Arrow from Header to first IFD
        if (ifds.isNotEmpty()) {
            arrows += SpecArrow("tiff_ifd0_off", "block_ifd_0", color = 0xFF0078D4L, label = "Pointer to IFD0")
        }

        // 2. IFD Blocks
        for ((idx, ifd) in ifds.withIndex()) {
            val rows = mutableListOf<SpecRow>()
            val entryCount = ifd.fields.size
            rows += SpecRow(listOf(
                SpecCell("ifd_count_$idx", "Directory Tag Count", "$entryCount Entries", 2L, colSpan = 12, role = RegionRole.METADATA),
            ), ifd.offset)

            for (f in ifd.fields.take(4)) {
                rows += SpecRow(listOf(
                    SpecCell("tag_${f.name}_$idx", f.name, f.value, 12L, colSpan = 12, role = RegionRole.METADATA),
                ), f.offset)
            }
            if (ifd.fields.size > 4) {
                rows += SpecRow(listOf(
                    SpecCell("tag_more_$idx", "…", "${ifd.fields.size - 4} more TIFF tags", 0L, colSpan = 12, role = RegionRole.METADATA),
                ), ifd.offset)
            }

            // Next IFD pointer
            rows += SpecRow(listOf(
                SpecCell("ifd_next_$idx", "Offset to Next IFD", "0x00000000 (End of chain)", 4L, colSpan = 12, role = RegionRole.METADATA),
            ), ifd.offset + ifd.size - 4L)

            blocks += SpecBlock("block_ifd_$idx", ifd.type, "$entryCount Tags (${ifd.size} B)", ifd.offset, ifd.size, RegionRole.METADATA, rows)
        }

        // 3. Image Raster Strips / Tiles
        val stripBlock = strips.firstOrNull()
        if (stripBlock != null) {
            val rows = listOf(
                SpecRow(listOf(
                    SpecCell("strip_payload", "Raster Pixel Strips", "${stripBlock.size} Bytes (RGB / YCbCr Raw or LZW compressed)", stripBlock.size, colSpan = 12, role = RegionRole.MEDIA_DATA),
                ), stripBlock.offset),
            )
            blocks += SpecBlock("block_strips", "Image Raster Data (${stripBlock.type})", "${stripBlock.size} Bytes", stripBlock.offset, stripBlock.size, RegionRole.MEDIA_DATA, rows)

            // Arrow from IFD0 to Raster Strip Data
            if (ifds.isNotEmpty()) {
                arrows += SpecArrow("ifd_count_0", "block_strips", color = 0xFF7C3AEDL, label = "StripOffsets Pointer")
            }
        }

        callouts += SpecCallout("block_tiff_header", "TIFF 6.0 / DNG Architecture: Tag-based directory entries pointing to strips/tiles")

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
