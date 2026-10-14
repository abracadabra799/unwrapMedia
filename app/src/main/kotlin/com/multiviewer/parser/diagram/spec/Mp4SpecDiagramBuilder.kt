package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object Mp4SpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val arrows = mutableListOf<SpecArrow>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        val ftyp = children.find { it.type == "ftyp" }
        val moov = children.find { it.type == "moov" }
        val mdat = children.find { it.type == "mdat" }

        // 1. ftyp Block
        if (ftyp != null) {
            val majorBrand = ftyp.fields.find { it.name == "major_brand" }?.value ?: "mp42"
            val compBrands = ftyp.fields.filter { it.name.startsWith("compatible_brand") }.map { it.value }.joinToString(", ")
            val ftypRows = listOf(
                SpecRow(listOf(
                    SpecCell("ftyp_h", "Box Header", "ftyp (8 B)", 8L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("ftyp_b", "Major Brand", majorBrand, 4L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("ftyp_cb", "Compatible Brands", compBrands.ifEmpty { "isom, mp42" }, ftyp.size - 12L, colSpan = 4, role = RegionRole.MARKER),
                ), ftyp.offset),
            )
            blocks += SpecBlock("block_ftyp", "File Type Box (ftyp)", "Brand: $majorBrand", ftyp.offset, ftyp.size, RegionRole.MARKER, ftypRows)
        }

        // 2. moov Movie Header Block
        if (moov != null) {
            val moovChildren = moov.children
            val mvhd = moovChildren.find { it.type == "mvhd" }
            val traks = moovChildren.filter { it.type == "trak" }

            val moovRows = mutableListOf<SpecRow>()

            val durationText = mvhd?.summary ?: "Timescale: 10000"
            moovRows += SpecRow(listOf(
                SpecCell("mvhd_header", "Movie Header (mvhd)", durationText, mvhd?.size ?: 108L, colSpan = 12, role = RegionRole.CONTAINER),
            ), mvhd?.offset ?: moov.offset)

            for ((tIdx, trak) in traks.withIndex()) {
                val hdlr = trak.findRecursive("hdlr")
                val isVideo = hdlr?.summary?.contains("vide", ignoreCase = true) == true || hdlr?.fields?.any { it.value.contains("vide") } == true
                val trackType = if (isVideo) "Video Track" else "Audio Track"

                val tkhd = trak.children.find { it.type == "tkhd" }
                val stbl = trak.findRecursive("stbl")
                val stsd = stbl?.children?.find { it.type == "stsd" }
                val stco = stbl?.children?.find { it.type == "stco" || it.type == "co64" }

                val codecText = stsd?.children?.firstOrNull()?.let { "${it.type} (${it.size} B)" } ?: "Codec Setup"

                moovRows += SpecRow(listOf(
                    SpecCell("trak_header_$tIdx", "Track $tIdx ($trackType)", tkhd?.summary ?: "${trak.size} B", trak.size, colSpan = 6, role = RegionRole.CONTAINER),
                    SpecCell("stsd_$tIdx", "Sample Description (stsd)", codecText, stsd?.size ?: 64L, colSpan = 6, role = RegionRole.CODEC_SETUP),
                ), trak.offset)

                if (stco != null) {
                    val stcoCellId = "stco_$tIdx"
                    moovRows += SpecRow(listOf(
                        SpecCell(stcoCellId, "Chunk Offsets (${stco.type})", stco.summary ?: "${stco.size} B Chunk Table", stco.size, colSpan = 12, role = RegionRole.CODEC_SETUP),
                    ), stco.offset)

                    // Chunk offset arrow pointing to mdat
                    if (mdat != null) {
                        arrows += SpecArrow(stcoCellId, "block_mdat", color = 0xFF7C3AEDL, label = "Chunk Offset")
                    }
                }
            }

            blocks += SpecBlock("block_moov", "Movie Header Box (moov)", "Tracks: ${traks.size}, Duration: $durationText", moov.offset, moov.size, RegionRole.CONTAINER, moovRows)
            callouts += SpecCallout("block_moov", "ISOBMFF Movie & Track Structure (ISO/IEC 14496-12)")
        }

        // 3. mdat Media Data Block
        if (mdat != null) {
            val mdatRows = listOf(
                SpecRow(listOf(
                    SpecCell("mdat_h", "Box Header", "${mdat.type} (8 B)", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                    SpecCell("mdat_data", "Interleaved A/V Sample Payload", "${mdat.size - 8} B (Video NALUs + Audio Frames)", mdat.size - 8L, colSpan = 8, role = RegionRole.MEDIA_DATA),
                ), mdat.offset),
            )
            blocks += SpecBlock("block_mdat", "Media Data Box (${mdat.type})", "Payload: ${mdat.size} Bytes", mdat.offset, mdat.size, RegionRole.MEDIA_DATA, mdatRows)
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

    private fun BoxNode.findRecursive(type: String): BoxNode? {
        if (this.type == type) return this
        for (child in children) {
            val found = child.findRecursive(type)
            if (found != null) return found
        }
        return null
    }
}
