package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object UniversalSpecDiagramBuilder {

    private val METADATA = setOf("COM", "XMP", "Exif", "EXIF", "tEXt", "iTXt", "zTXt", "eXIf", "iCCP", "udta", "uuid", "ICCP", "XMP ", "meta")
    private val CODEC_SETUP = setOf("DQT", "DHT", "DRI", "IHDR", "PLTE", "stsd", "avcC", "hvcC", "av1C", "VP8X", "iprp")
    private val MEDIA_DATA = setOf("SOS", "mdat", "idat", "IDAT", "VP8 ", "VP8L", "ANMF", "data")
    private val MARKER = setOf("SOI", "EOI", "IEND", "ftyp", "RIFF")
    private val TRAILER = setOf("sefd", "SEFH", "SEFT")

    private fun roleOf(node: BoxNode): RegionRole = when {
        node.type in TRAILER -> RegionRole.TRAILER
        node.type in MARKER -> RegionRole.MARKER
        node.type in MEDIA_DATA -> RegionRole.MEDIA_DATA
        node.type in CODEC_SETUP || node.type.startsWith("SOF") -> RegionRole.CODEC_SETUP
        node.type in METADATA || node.type.startsWith("APP") || node.type.startsWith("IFD") -> RegionRole.METADATA
        node.children.isNotEmpty() -> RegionRole.CONTAINER
        else -> RegionRole.OTHER
    }

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val brackets = mutableListOf<SpecBracket>()

        // File size bracket
        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        for ((idx, child) in children.withIndex()) {
            val role = roleOf(child)
            val rows = mutableListOf<SpecRow>()

            if (child.fields.isNotEmpty()) {
                // Pack fields into 12-colSpan rows
                val rowCells = mutableListOf<SpecCell>()
                var currentSpan = 0
                var rowStartOffset = child.offset

                for ((fIdx, f) in child.fields.withIndex()) {
                    val span = when {
                        f.length <= 1L -> 2
                        f.length <= 2L -> 3
                        f.length <= 4L -> 4
                        f.length <= 8L -> 6
                        else -> 12
                    }

                    if (currentSpan + span > 12 && rowCells.isNotEmpty()) {
                        // Pad existing row if needed
                        if (currentSpan < 12) {
                            val last = rowCells.removeAt(rowCells.lastIndex)
                            rowCells += last.copy(colSpan = last.colSpan + (12 - currentSpan))
                        }
                        rows += SpecRow(rowCells.toList(), rowStartOffset)
                        rowCells.clear()
                        currentSpan = 0
                        rowStartOffset = f.offset
                    }

                    rowCells += SpecCell(
                        id = "${child.type}_f_${idx}_$fIdx",
                        name = f.name,
                        value = f.value,
                        byteLength = f.length,
                        colSpan = span,
                        role = role,
                    )
                    currentSpan += span

                    if (currentSpan >= 12) {
                        rows += SpecRow(rowCells.toList(), rowStartOffset)
                        rowCells.clear()
                        currentSpan = 0
                        rowStartOffset = f.offset + f.length
                    }
                }

                if (rowCells.isNotEmpty()) {
                    if (currentSpan < 12) {
                        val last = rowCells.removeAt(rowCells.lastIndex)
                        rowCells += last.copy(colSpan = last.colSpan + (12 - currentSpan))
                    }
                    rows += SpecRow(rowCells.toList(), rowStartOffset)
                }
            } else if (child.children.isNotEmpty()) {
                // Container with nested child summaries
                for ((cIdx, sub) in child.children.take(6).withIndex()) {
                    rows += SpecRow(listOf(
                        SpecCell(
                            id = "${child.type}_sub_${idx}_$cIdx",
                            name = sub.type,
                            value = "${sub.size} B (offset ${sub.offset})",
                            byteLength = sub.size,
                            colSpan = 12,
                            role = roleOf(sub),
                        ),
                    ), sub.offset)
                }
                if (child.children.size > 6) {
                    rows += SpecRow(listOf(
                        SpecCell(
                            id = "${child.type}_sub_more_$idx",
                            name = "…",
                            value = "${child.children.size - 6} more child boxes",
                            byteLength = 0L,
                            colSpan = 12,
                            role = RegionRole.CONTAINER,
                        ),
                    ), child.offset)
                }
            } else {
                // Leaf payload block (e.g. mdat, raw data)
                rows += SpecRow(listOf(
                    SpecCell(
                        id = "${child.type}_payload_$idx",
                        name = child.type,
                        value = "${child.size} Bytes payload",
                        byteLength = child.size,
                        colSpan = 12,
                        role = role,
                    ),
                ), child.offset)
            }

            val subtitle = child.summary ?: "Range: ${child.offset} – ${child.offset + child.size} (${child.size} B)"
            blocks += SpecBlock(
                id = "block_${child.type}_$idx",
                title = child.type,
                subtitle = subtitle,
                offset = child.offset,
                length = child.size,
                role = role,
                rows = rows,
            )
        }

        return SpecDiagram(
            title = title,
            fileSize = fileSize,
            blocks = blocks,
            brackets = brackets,
            columnBytes = 12,
        )
    }
}
