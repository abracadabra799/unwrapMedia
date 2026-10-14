package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object HeicSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val arrows = mutableListOf<SpecArrow>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)
        val ftyp = children.find { it.type == "ftyp" }
        val meta = children.find { it.type == "meta" }
        val mdat = children.find { it.type == "mdat" || it.type == "idat" }

        // 1. ftyp Block
        if (ftyp != null) {
            val majorBrand = ftyp.fields.find { it.name == "major_brand" }?.value ?: "heic"
            val compBrands = ftyp.fields.filter { it.name.startsWith("compatible_brand") }.map { it.value }.joinToString(", ")
            val ftypRows = listOf(
                SpecRow(listOf(
                    SpecCell("ftyp_header", "Box Header", "ftyp (8 B)", 8L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("ftyp_major", "Major Brand", majorBrand, 4L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("ftyp_comp", "Compatible Brands", compBrands.ifEmpty { "mif1, heic" }, ftyp.size - 12L, colSpan = 4, role = RegionRole.MARKER),
                ), ftyp.offset),
            )
            blocks += SpecBlock("block_ftyp", "File Type Box (ftyp)", "Brand: $majorBrand", ftyp.offset, ftyp.size, RegionRole.MARKER, ftypRows)
        }

        // 2. meta Container Block
        if (meta != null) {
            val metaChildren = meta.children
            val metaRows = mutableListOf<SpecRow>()

            val hdlr = metaChildren.find { it.type == "hdlr" }
            val pitm = metaChildren.find { it.type == "pitm" }
            val iloc = metaChildren.find { it.type == "iloc" }
            val iinf = metaChildren.find { it.type == "iinf" }
            val iprp = metaChildren.find { it.type == "iprp" }

            val primaryItemId = pitm?.fields?.find { it.name == "item_ID" }?.value ?: "1"

            metaRows += SpecRow(listOf(
                SpecCell("meta_hdlr", "Handler (hdlr)", "pict (Image)", hdlr?.size ?: 33L, colSpan = 6, role = RegionRole.METADATA),
                SpecCell("meta_pitm", "Primary Item (pitm)", "Item ID: $primaryItemId", pitm?.size ?: 14L, colSpan = 6, role = RegionRole.METADATA),
            ), meta.offset)

            if (iloc != null) {
                metaRows += SpecRow(listOf(
                    SpecCell("meta_iloc", "Item Location (iloc)", "Item $primaryItemId Extents Mapping", iloc.size, colSpan = 12, role = RegionRole.METADATA),
                ), iloc.offset)
                // Arrow from iloc to mdat
                if (mdat != null) {
                    arrows += SpecArrow("meta_iloc", "block_mdat", color = 0xFF0078D4L, label = "Item Extents Offset")
                }
            }

            if (iinf != null) {
                val itemCount = iinf.children.count { it.type == "infe" }
                metaRows += SpecRow(listOf(
                    SpecCell("meta_iinf", "Item Info (iinf)", "$itemCount Item Entries (Master, Thmb, Aux)", iinf.size, colSpan = 12, role = RegionRole.METADATA),
                ), iinf.offset)
            }

            if (iprp != null) {
                val ipco = iprp.children.find { it.type == "ipco" }
                val ispe = ipco?.children?.find { it.type == "ispe" }
                val ispeText = ispe?.fields?.let { f ->
                    val w = f.find { it.name == "image_width" }?.value ?: "4000"
                    val h = f.find { it.name == "image_height" }?.value ?: "3000"
                    "${w}×${h}"
                } ?: "Resolution Property"

                metaRows += SpecRow(listOf(
                    SpecCell("meta_ispe", "Image Spatial Extents (ispe)", ispeText, ispe?.size ?: 20L, colSpan = 6, role = RegionRole.CODEC_SETUP),
                    SpecCell("meta_codec", "Codec Setup (hvcC/av1C)", "HEVC/AV1 Configuration", 40L, colSpan = 6, role = RegionRole.CODEC_SETUP),
                ), iprp.offset)
            }

            blocks += SpecBlock("block_meta", "Metadata Container (meta)", "ISOBMFF Image Item Model", meta.offset, meta.size, RegionRole.CONTAINER, metaRows)
            callouts += SpecCallout("block_meta", "HEIF/AVIF Item-based Architecture (ISO/IEC 23008-12)")
        }

        // 3. mdat Media Data Block
        if (mdat != null) {
            val mdatRows = listOf(
                SpecRow(listOf(
                    SpecCell("mdat_header", "Box Header", "${mdat.type} (8 B)", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                    SpecCell("mdat_payload", "Coded Image Bitstream", "${mdat.size - 8} B (Tiled/Grid HEVC/AV1 slices)", mdat.size - 8L, colSpan = 8, role = RegionRole.MEDIA_DATA),
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
}
