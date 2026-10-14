package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object PngSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        // 1. PNG Signature (8 bytes)
        val sigRows = listOf(
            SpecRow(listOf(
                SpecCell("png_sig", "PNG Signature", "0x89 'P' 'N' 'G' \\r \\n 0x1A \\n", 8L, colSpan = 12, role = RegionRole.MARKER),
            ), 0L),
        )
        blocks += SpecBlock("block_png_sig", "PNG File Signature", "8 Bytes Magic", 0L, 8L, RegionRole.MARKER, sigRows)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        for ((idx, child) in children.withIndex()) {
            when (child.type) {
                "IHDR" -> {
                    val width = child.fields.find { it.name == "width" }?.value ?: "100"
                    val height = child.fields.find { it.name == "height" }?.value ?: "100"
                    val bitDepth = child.fields.find { it.name == "bit_depth" }?.value ?: "8"
                    val colorType = child.fields.find { it.name == "color_type" }?.value ?: "6 (RGBA)"

                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("ihdr_len", "Length", "13 B", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("ihdr_type", "Chunk Type", "IHDR", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("ihdr_crc", "CRC32", "4 B", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset),
                        SpecRow(listOf(
                            SpecCell("ihdr_w", "Width", "$width px", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("ihdr_h", "Height", "$height px", 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("ihdr_fmt", "Bit Depth / Color", "$bitDepth-bit, $colorType", 5L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset + 8L),
                    )
                    blocks += SpecBlock("block_ihdr", "Image Header (IHDR)", "${width}×${height}, $colorType", child.offset, child.size, RegionRole.CODEC_SETUP, rows)
                }
                "PLTE" -> {
                    val entries = (child.size - 12L) / 3L
                    val row = SpecRow(listOf(
                        SpecCell("plte_cell", "Palette Entries", "$entries RGB Triples", child.size - 12L, colSpan = 12, role = RegionRole.METADATA),
                    ), child.offset + 8L)
                    blocks += SpecBlock("block_plte", "Palette (PLTE)", "$entries Colors", child.offset, child.size, RegionRole.METADATA, listOf(row))
                }
                "IDAT" -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("idat_len_$idx", "Length", "${child.size - 12L} B", 4L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                            SpecCell("idat_type_$idx", "Chunk Type", "IDAT", 4L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                            SpecCell("idat_crc_$idx", "CRC32", "4 B", 4L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                        ), child.offset),
                        SpecRow(listOf(
                            SpecCell("idat_payload_$idx", "Zlib / Deflate Compressed Scanlines", "${child.size - 12L} B (Filtered scanlines)", child.size - 12L, colSpan = 12, role = RegionRole.MEDIA_DATA),
                        ), child.offset + 8L),
                    )
                    blocks += SpecBlock("block_idat_$idx", "Image Data (IDAT)", "${child.size - 12L} Bytes payload", child.offset, child.size, RegionRole.MEDIA_DATA, rows)
                }
                "IEND" -> {
                    val row = SpecRow(listOf(
                        SpecCell("iend_cell", "End of Image Marker", "IEND (Length: 0, CRC: 4 B)", 12L, colSpan = 12, role = RegionRole.MARKER),
                    ), child.offset)
                    blocks += SpecBlock("block_iend", "Image Trailer (IEND)", "End Marker", child.offset, child.size, RegionRole.MARKER, listOf(row))
                }
                else -> {
                    // Ancillary metadata chunks (sRGB, gAMA, pHYs, eXIf, tEXt, etc.)
                    val row = SpecRow(listOf(
                        SpecCell("chunk_c_$idx", child.type, child.summary ?: "${child.size} B", child.size, colSpan = 12, role = RegionRole.METADATA),
                    ), child.offset)
                    blocks += SpecBlock("block_chunk_$idx", "${child.type} Chunk", "${child.size} B", child.offset, child.size, RegionRole.METADATA, listOf(row))
                }
            }
        }

        callouts += SpecCallout("block_png_sig", "PNG Chunk Architecture (W3C PNG Spec): Length(4B) + Type(4B) + Data + CRC(4B)")

        return SpecDiagram(
            title = title,
            fileSize = fileSize,
            blocks = blocks,
            brackets = brackets,
            callouts = callouts,
            columnBytes = 12,
        )
    }
}
