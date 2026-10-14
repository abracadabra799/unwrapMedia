package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object WebpSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        // 1. RIFF Header (12 Bytes)
        val riffRows = listOf(
            SpecRow(listOf(
                SpecCell("riff_magic", "RIFF Signature", "'R' 'I' 'F' 'F'", 4L, colSpan = 4, role = RegionRole.MARKER),
                SpecCell("riff_len", "File Size - 8", "${fileSize - 8} B", 4L, colSpan = 4, role = RegionRole.MARKER),
                SpecCell("riff_type", "FourCC Format", "'W' 'E' 'B' 'P'", 4L, colSpan = 4, role = RegionRole.MARKER),
            ), 0L),
        )
        blocks += SpecBlock("block_riff", "RIFF Container Header", "12 Bytes Header", 0L, 12L, RegionRole.MARKER, riffRows)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        for ((idx, child) in children.withIndex()) {
            when (child.type) {
                "VP8X" -> {
                    val w = child.fields.find { it.name == "canvas_width" }?.value ?: "Canvas Width"
                    val h = child.fields.find { it.name == "canvas_height" }?.value ?: "Canvas Height"
                    val flags = child.fields.filter { it.name.startsWith("has_") && it.value == "true" }.joinToString(", ") { it.name.removePrefix("has_") }

                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("vp8x_header", "Chunk Header", "VP8X (8 B)", 8L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("vp8x_flags", "Feature Flags", flags.ifEmpty { "None" }, 4L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("vp8x_dim", "Canvas Dimensions", "$w × $h", 6L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_vp8x", "Extended File Header (VP8X)", "$w × $h, Flags: $flags", child.offset, child.size, RegionRole.CODEC_SETUP, rows)
                }
                "VP8", "VP8 " -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("vp8_header", "Chunk Header", "VP8 (8 B)", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                            SpecCell("vp8_payload", "Lossy Keyframe Bitstream", "${child.size - 8L} Bytes DCT macroblocks", child.size - 8L, colSpan = 8, role = RegionRole.MEDIA_DATA),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_vp8", "Lossy Image Bitstream (VP8)", "${child.size} Bytes payload", child.offset, child.size, RegionRole.MEDIA_DATA, rows)
                }
                "VP8L" -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("vp8l_header", "Chunk Header", "VP8L (8 B)", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                            SpecCell("vp8l_payload", "Lossless Image Bitstream", "${child.size - 8L} Bytes Huffman/LZ77 transform", child.size - 8L, colSpan = 8, role = RegionRole.MEDIA_DATA),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_vp8l", "Lossless Image Bitstream (VP8L)", "${child.size} Bytes payload", child.offset, child.size, RegionRole.MEDIA_DATA, rows)
                }
                "ALPH" -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("alph_payload", "Alpha Channel Bitstream", "${child.size} Bytes transparent mask", child.size, colSpan = 12, role = RegionRole.MEDIA_DATA),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_alph", "Alpha Channel (ALPH)", "${child.size} Bytes", child.offset, child.size, RegionRole.MEDIA_DATA, rows)
                }
                else -> {
                    val role = if (child.type in setOf("EXIF", "XMP ", "ICCP")) RegionRole.METADATA else RegionRole.OTHER
                    val row = SpecRow(listOf(
                        SpecCell("webp_chunk_$idx", child.type, child.summary ?: "${child.size} B", child.size, colSpan = 12, role = role),
                    ), child.offset)
                    blocks += SpecBlock("block_webp_chunk_$idx", "${child.type} Chunk", "${child.size} B", child.offset, child.size, role, listOf(row))
                }
            }
        }

        callouts += SpecCallout("block_riff", "Google WebP RIFF Architecture: VP8X extended canvas + VP8/VP8L bitstream")

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
