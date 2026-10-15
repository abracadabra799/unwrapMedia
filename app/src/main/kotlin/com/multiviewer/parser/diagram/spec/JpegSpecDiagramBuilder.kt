package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object JpegSpecDiagramBuilder {

    fun build(root: BoxNode, fileSize: Long, title: String): SpecDiagram {
        val blocks = mutableListOf<SpecBlock>()
        val arrows = mutableListOf<SpecArrow>()
        val brackets = mutableListOf<SpecBracket>()
        val callouts = mutableListOf<SpecCallout>()

        // 1. Whole file bracket (left side)
        brackets += SpecBracket(0L, fileSize, "File Size: $fileSize B", color = 0xFF107C41L)

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        // Identify primary and secondary (gain map) image boundaries
        val soiIndices = children.indices.filter { children[it].type == "SOI" }
        val eoiIndices = children.indices.filter { children[it].type == "EOI" }

        val hasGainMap = soiIndices.size > 1 && eoiIndices.size > 1
        val primaryEnd = if (eoiIndices.isNotEmpty()) children[eoiIndices[0]].offset + children[eoiIndices[0]].size else fileSize
        val gainMapStart = if (hasGainMap) children[soiIndices[1]].offset else -1L
        val gainMapEnd = if (hasGainMap) children[eoiIndices[1]].offset + children[eoiIndices[1]].size else -1L

        if (hasGainMap) {
            brackets += SpecBracket(0L, primaryEnd, "Primary Image (JPEG): ${primaryEnd} B", color = 0xFF107C41L)
            brackets += SpecBracket(gainMapStart, gainMapEnd, "Gain Map Image: ${gainMapEnd - gainMapStart} B", color = 0xFFD97706L)
        }

        var primaryApp2GainMapCellId: String? = null
        var primaryXmpCellId: String? = null
        val processedIndices = mutableSetOf<Int>()

        for ((idx, child) in children.withIndex()) {
            if (processedIndices.contains(idx)) continue
            val isGainMap = hasGainMap && idx >= soiIndices[1] && idx <= eoiIndices[1]

            when {
                child.type == "SOI" -> {
                    val blockId = if (isGainMap) "block_gainmap_soi" else "block_soi_$idx"
                    val blockTitle = if (isGainMap) "Gain Map Image (SOI)" else "Start of Image (SOI)"
                    val blockSubtitle = if (isGainMap) "Marker: 0xFFD8 (Secondary Image)" else "Marker: 0xFFD8"
                    val row = SpecRow(listOf(SpecCell(if (isGainMap) "gainmap_soi_marker" else "soi_marker", "Start of Image", "0xFFD8", 2L, colSpan = 12, role = RegionRole.MARKER)), child.offset)
                    blocks += SpecBlock(blockId, blockTitle, blockSubtitle, child.offset, 2L, RegionRole.MARKER, listOf(row))

                    if (isGainMap && primaryApp2GainMapCellId != null) {
                        arrows += SpecArrow(primaryApp2GainMapCellId, "block_gainmap_soi", color = 0xFFD97706L, label = "References Gain Map")
                    }
                }
                child.type == "EOI" -> {
                    val blockId = if (isGainMap) "block_gainmap_eoi_$idx" else "block_eoi_$idx"
                    val blockTitle = if (isGainMap) "Gain Map End of Image (EOI)" else "End of Image (EOI)"
                    val row = SpecRow(listOf(SpecCell(if (isGainMap) "gainmap_eoi_marker_$idx" else "eoi_marker_$idx", "End of Image", "0xFFD9", 2L, colSpan = 12, role = RegionRole.MARKER)), child.offset)
                    blocks += SpecBlock(blockId, blockTitle, "Marker: 0xFFD9", child.offset, 2L, RegionRole.MARKER, listOf(row))
                }
                child.type.startsWith("APP1") -> {
                    val isExif = child.summary?.contains("Exif", ignoreCase = true) == true || child.children.any { it.type.contains("IFD") }
                    val isXmp = child.summary?.contains("XMP", ignoreCase = true) == true || child.fields.any { it.name.contains("xmp", ignoreCase = true) }
                    val bannerTitle = when {
                        isGainMap -> "Gain Map XMP Metadata (APP1)"
                        isExif -> "APP1: Exif Metadata"
                        isXmp -> "APP1: XMP Metadata"
                        else -> "APP1 Application Marker"
                    }

                    val rows = mutableListOf<SpecRow>()
                    val cellId = "app1_id_$idx"
                    if (!isGainMap && isXmp) primaryXmpCellId = cellId

                    // Header row: Marker + Length + ID
                    rows += SpecRow(listOf(
                        SpecCell("app1_marker_$idx", "Marker", "0xFFE1", 2L, colSpan = 4, role = RegionRole.METADATA),
                        SpecCell("app1_len_$idx", "Payload Length", "${child.size - 2} B", 2L, colSpan = 4, role = RegionRole.METADATA),
                        SpecCell(cellId, "Identifier", if (isExif) "Exif\\0\\0" else if (isXmp) "http://ns.adobe.com" else "APP1", 4L, colSpan = 4, role = RegionRole.METADATA),
                    ), child.offset)

                    val ifds = child.children.filter { it.type.contains("IFD") || it.type == "Exif" || it.type == "GPS" }
                    if (ifds.isNotEmpty()) {
                        for (ifd in ifds) {
                            val tagCells = ifd.fields.take(3).mapIndexed { fIdx, f ->
                                SpecCell("ifd_${ifd.type}_${fIdx}", f.name, f.value, f.length, colSpan = 4, role = RegionRole.METADATA)
                            }
                            if (tagCells.isNotEmpty()) {
                                rows += SpecRow(tagCells, ifd.offset)
                            }
                        }
                    } else if (child.fields.isNotEmpty()) {
                        val cells = child.fields.take(3).mapIndexed { fIdx, f ->
                            SpecCell("app1_f_${fIdx}", f.name, f.value, f.length, colSpan = 4, role = RegionRole.METADATA)
                        }
                        rows += SpecRow(cells, child.offset + 4)
                    }

                    blocks += SpecBlock("block_app1_$idx", bannerTitle, "${child.size} Bytes", child.offset, child.size, RegionRole.METADATA, rows)
                }
                child.type.startsWith("APP2") -> {
                    val isIsoGainMap = child.summary?.contains("ISO 21496-1", ignoreCase = true) == true ||
                        child.summary?.contains("Gain Map", ignoreCase = true) == true ||
                        child.fields.any { it.name.contains("gainmap", ignoreCase = true) }
                    val bannerTitle = if (isGainMap) "Gain Map ISO 21496-1 Metadata (APP2)"
                        else if (isIsoGainMap) "APP2: ISO 21496-1 Gain Map Metadata"
                        else "APP2 Application Marker"

                    val cellId = "app2_cell_$idx"
                    if (!isGainMap && isIsoGainMap) primaryApp2GainMapCellId = cellId

                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("app2_marker_$idx", "Marker", "0xFFE2", 2L, colSpan = 4, role = RegionRole.METADATA),
                            SpecCell("app2_len_$idx", "Payload Length", "${child.size - 2} B", 2L, colSpan = 4, role = RegionRole.METADATA),
                            SpecCell(cellId, "Payload", child.summary ?: "${child.size} B", child.size - 4, colSpan = 4, role = RegionRole.METADATA),
                        ), child.offset)
                    )
                    blocks += SpecBlock("block_app2_$idx", bannerTitle, "${child.size} Bytes", child.offset, child.size, RegionRole.METADATA, rows)
                }
                child.type.startsWith("SOF") -> {
                    val width = child.fields.find { it.name == "width" }?.value ?: "4000"
                    val height = child.fields.find { it.name == "height" }?.value ?: "2252"
                    val precision = child.fields.find { it.name == "precision" }?.value ?: "8"
                    val components = child.fields.find { it.name == "components" }?.value ?: "3"

                    val bannerTitle = if (isGainMap) "Gain Map Frame Header (${child.type})" else "Frame Header (${child.type})"
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("sof_marker_$idx", "Marker", child.type, 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("sof_len_$idx", "Length", "${child.size} B", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("sof_prec_$idx", "Precision", "$precision bits", 1L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset),
                        SpecRow(listOf(
                            SpecCell("sof_w_$idx", "Image Width", "$width px", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("sof_h_$idx", "Image Height", "$height px", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("sof_comp_$idx", "Components", if (components == "1") "1 (Monochrome)" else "$components (Y/Cb/Cr)", 1L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset + 5),
                    )
                    blocks += SpecBlock("block_sof_$idx", bannerTitle, "${width}×${height}, ${precision}-bit", child.offset, child.size, RegionRole.CODEC_SETUP, rows)
                }
                child.type == "DQT" -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("dqt_m_$idx", "Marker", "0xFFDB", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("dqt_l_$idx", "Length", "${child.size} B", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("dqt_q_$idx", "Q-Table", child.summary ?: "64 coefficients", child.size - 4, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_dqt_$idx", "Quantization Table (DQT)", child.summary ?: "Precision: 8-bit", child.offset, child.size, RegionRole.CODEC_SETUP, rows)
                }
                child.type == "DHT" -> {
                    val rows = listOf(
                        SpecRow(listOf(
                            SpecCell("dht_m_$idx", "Marker", "0xFFC4", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("dht_l_$idx", "Length", "${child.size} B", 2L, colSpan = 4, role = RegionRole.CODEC_SETUP),
                            SpecCell("dht_t_$idx", "Huffman Table", child.summary ?: "DC/AC Table", child.size - 4, colSpan = 4, role = RegionRole.CODEC_SETUP),
                        ), child.offset),
                    )
                    blocks += SpecBlock("block_dht_$idx", "Huffman Table (DHT)", child.summary ?: "Table definition", child.offset, child.size, RegionRole.CODEC_SETUP, rows)
                }
                child.type == "SOS" -> {
                    val rows = mutableListOf<SpecRow>()
                    rows += SpecRow(listOf(
                        SpecCell("sos_m_$idx", "Marker", "0xFFDA", 2L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                        SpecCell("sos_l_$idx", "Header Size", "12 B", 2L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                        SpecCell("sos_sel_$idx", "Component Selectors", "Y, Cb, Cr", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                    ), child.offset)

                    val payloadSize = (child.size - 12L).coerceAtLeast(0L)
                    val payloadName = if (isGainMap) "Gain Map Scan Bitstream" else "Entropy-Coded Scan Data"
                    rows += SpecRow(listOf(
                        SpecCell("sos_payload_$idx", payloadName, "$payloadSize Bytes compressed DCT bitstream", payloadSize, colSpan = 12, role = RegionRole.MEDIA_DATA),
                    ), child.offset + 12L)

                    val bannerTitle = if (isGainMap) "Gain Map Scan Data (SOS)" else "Scan Header & Image Data (SOS)"
                    blocks += SpecBlock("block_sos_$idx", bannerTitle, "Payload: $payloadSize B", child.offset, child.size, RegionRole.MEDIA_DATA, rows)
                }
                child.type == "sefd" || child.type == "SEFT" -> {
                    // Samsung Extension Format (SEF) trailer
                    val rows = mutableListOf<SpecRow>()
                    var motionPhotoSub: BoxNode? = null
                    var motionPhotoCellId: String? = null

                    for ((subIdx, sub) in child.children.withIndex()) {
                        val subCellId = "sef_field_${subIdx}"
                        rows += SpecRow(listOf(
                            SpecCell(subCellId, sub.type, sub.summary ?: "${sub.size} B", sub.size, colSpan = 12, role = RegionRole.TRAILER),
                        ), sub.offset)

                        if (sub.type == "MotionPhoto_Data" || sub.summary?.contains("embedded MP4", ignoreCase = true) == true) {
                            motionPhotoSub = sub
                            motionPhotoCellId = subCellId
                        }
                    }

                    blocks += SpecBlock("block_sef_$idx", "Samsung Extension Format (SEF)", "Trailer: ${child.size} B", child.offset, child.size, RegionRole.TRAILER, rows)

                    if (motionPhotoSub != null) {
                        brackets += SpecBracket(motionPhotoSub.offset, motionPhotoSub.offset + motionPhotoSub.size, "Embedded MP4 Video: ${motionPhotoSub.size} B", color = 0xFF7C3AEDL)
                        callouts += SpecCallout("block_sef_$idx", "Samsung Motion Photo Trailer (Contains MP4)")

                        // Decompose inner MP4 boxes
                        buildMp4Blocks(motionPhotoSub.children, motionPhotoSub.offset, motionPhotoSub.size, blocks)

                        if (motionPhotoCellId != null) {
                            arrows += SpecArrow(motionPhotoCellId, "block_mp4_ftyp", color = 0xFF7C3AEDL, label = "Embedded Video")
                        }
                        if (primaryXmpCellId != null) {
                            arrows += SpecArrow(primaryXmpCellId, "block_mp4_ftyp", color = 0xFF7C3AEDL, label = "XMP Motion Photo")
                        }
                    }
                }
                child.type == "ftyp" -> {
                    // Google Motion Photo (standalone MP4 appended directly at EOF)
                    val mp4Boxes = mutableListOf<BoxNode>()
                    for (mIdx in idx until children.size) {
                        mp4Boxes += children[mIdx]
                        processedIndices += mIdx
                    }
                    val mp4Size = mp4Boxes.sumOf { it.size }
                    brackets += SpecBracket(child.offset, child.offset + mp4Size, "Embedded MP4 Video: $mp4Size B", color = 0xFF7C3AEDL)
                    callouts += SpecCallout("block_mp4_ftyp", "Google Motion Photo (Standalone MP4 Trailer)")

                    buildMp4Blocks(mp4Boxes, child.offset, mp4Size, blocks)

                    if (primaryXmpCellId != null) {
                        arrows += SpecArrow(primaryXmpCellId, "block_mp4_ftyp", color = 0xFF7C3AEDL, label = "Google Motion Photo")
                    }
                }
                else -> {
                    val role = if (child.type.startsWith("APP")) RegionRole.METADATA else RegionRole.OTHER
                    val row = SpecRow(listOf(
                        SpecCell("gen_cell_$idx", child.type, child.summary ?: "${child.size} B", child.size, colSpan = 12, role = role),
                    ), child.offset)
                    blocks += SpecBlock("block_gen_$idx", "${child.type} Segment", "${child.size} B", child.offset, child.size, role, listOf(row))
                }
            }
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

    private fun buildMp4Blocks(
        boxes: List<BoxNode>,
        fallbackOffset: Long,
        fallbackSize: Long,
        blocks: MutableList<SpecBlock>,
    ) {
        val ftyp = boxes.find { it.type == "ftyp" }
        val moov = boxes.find { it.type == "moov" }
        val mdat = boxes.find { it.type == "mdat" }

        // 1. ftyp
        if (ftyp != null) {
            val majorBrand = ftyp.fields.find { it.name == "major_brand" }?.value ?: "isom"
            val compBrands = ftyp.fields.filter { it.name.startsWith("compatible_brand") }.map { it.value }.joinToString(", ")
            val ftypRows = listOf(
                SpecRow(listOf(
                    SpecCell("mp4_ftyp_h", "Box Header", "ftyp (8 B)", 8L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("mp4_ftyp_b", "Major Brand", majorBrand, 4L, colSpan = 4, role = RegionRole.MARKER),
                    SpecCell("mp4_ftyp_cb", "Compatible Brands", compBrands.ifEmpty { "isom, mp42" }, ftyp.size - 12L, colSpan = 4, role = RegionRole.MARKER),
                ), ftyp.offset)
            )
            blocks += SpecBlock("block_mp4_ftyp", "Embedded File Type Box (ftyp)", "Brand: $majorBrand", ftyp.offset, ftyp.size, RegionRole.MARKER, ftypRows)
        } else {
            // Fallback header block if ftyp wasn't parsed as a separate child
            val headerRows = listOf(
                SpecRow(listOf(
                    SpecCell("mp4_stream_data", "Embedded MP4 Stream", "$fallbackSize B", fallbackSize, colSpan = 12, role = RegionRole.MEDIA_DATA)
                ), fallbackOffset)
            )
            blocks += SpecBlock("block_mp4_ftyp", "Embedded MP4 Video Stream", "$fallbackSize Bytes", fallbackOffset, fallbackSize, RegionRole.MEDIA_DATA, headerRows)
        }

        // 2. moov
        if (moov != null) {
            val moovChildren = moov.children
            val mvhd = moovChildren.find { it.type == "mvhd" }
            val traks = moovChildren.filter { it.type == "trak" }

            val moovRows = mutableListOf<SpecRow>()
            val durationText = mvhd?.summary ?: "Movie Header"
            moovRows += SpecRow(listOf(
                SpecCell("mp4_mvhd_header", "Movie Header (mvhd)", durationText, mvhd?.size ?: 108L, colSpan = 12, role = RegionRole.CONTAINER)
            ), mvhd?.offset ?: moov.offset)

            for ((tIdx, trak) in traks.withIndex()) {
                val hdlr = trak.findRecursive("hdlr")
                val isVideo = hdlr?.summary?.contains("vide", ignoreCase = true) == true || hdlr?.fields?.any { it.value.contains("vide") } == true
                val trackType = if (isVideo) "Video Track" else "Audio Track"
                val tkhd = trak.children.find { it.type == "tkhd" }
                val width = tkhd?.fields?.find { it.name == "width" }?.value
                val height = tkhd?.fields?.find { it.name == "height" }?.value
                val res = if (width != null && height != null) "${width}×${height}" else (tkhd?.summary ?: "${trak.size} B")

                val stbl = trak.findRecursive("stbl")
                val stsd = stbl?.children?.find { it.type == "stsd" }
                val codecText = stsd?.children?.firstOrNull()?.let { "${it.type} (${it.size} B)" } ?: "Codec Setup"

                moovRows += SpecRow(listOf(
                    SpecCell("mp4_trak_$tIdx", "Track $tIdx ($trackType)", res, trak.size, colSpan = 6, role = RegionRole.CONTAINER),
                    SpecCell("mp4_stsd_$tIdx", "Sample Description (stsd)", codecText, stsd?.size ?: 64L, colSpan = 6, role = RegionRole.CODEC_SETUP)
                ), trak.offset)
            }

            blocks += SpecBlock("block_mp4_moov", "Embedded Movie Header (moov)", "Tracks: ${traks.size}, $durationText", moov.offset, moov.size, RegionRole.CONTAINER, moovRows)
        }

        // 3. mdat
        if (mdat != null) {
            val payloadSize = (mdat.size - 8L).coerceAtLeast(0L)
            val mdatRows = listOf(
                SpecRow(listOf(
                    SpecCell("mp4_mdat_h", "Box Header", "mdat (8 B)", 8L, colSpan = 4, role = RegionRole.MEDIA_DATA),
                    SpecCell("mp4_mdat_data", "Compressed Video Frames", "$payloadSize B bitstream", payloadSize, colSpan = 8, role = RegionRole.MEDIA_DATA)
                ), mdat.offset)
            )
            blocks += SpecBlock("block_mp4_mdat", "Embedded Media Data Box (mdat)", "Payload: ${mdat.size} B", mdat.offset, mdat.size, RegionRole.MEDIA_DATA, mdatRows)
        }
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
