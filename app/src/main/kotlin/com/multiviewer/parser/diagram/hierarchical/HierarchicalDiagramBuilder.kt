package com.multiviewer.parser.diagram.hierarchical

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus

object HierarchicalDiagramBuilder {

    // dispatchName: the name whose extension picks the builder -- the content-implied one
    // (contentExtension) when it differs from the real file name, which stays the display label.
    fun build(root: BoxNode, fileSize: Long, fileName: String, dispatchName: String = fileName): HierarchicalDiagram {
        val lower = dispatchName.lowercase()
        val types = root.children.map { it.type }.toSet()

        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") || types.contains("SOI") -> {
                buildJpeg(root, fileSize, fileName)
            }
            lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".m4a") || types.contains("moov") -> {
                buildMp4(root, fileSize, fileName)
            }
            lower.endsWith(".heic") || lower.endsWith(".heif") || lower.endsWith(".avif") || lower.endsWith(".hif") ||
                (types.contains("meta") && !types.contains("moov")) -> {
                buildHeic(root, fileSize, fileName)
            }
            lower.endsWith(".png") || types.contains("IHDR") -> {
                buildPng(root, fileSize, fileName)
            }
            lower.endsWith(".webp") || types.contains("VP8X") || types.contains("VP8") -> {
                buildWebp(root, fileSize, fileName)
            }
            lower.endsWith(".bmp") || types.contains("BITMAPFILEHEADER") -> {
                buildBmp(root, fileSize, fileName)
            }
            else -> {
                buildGeneric(root, fileSize, fileName)
            }
        }
    }

    private fun buildJpeg(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()

        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        // Column 1: Structure of Compressed file (Canonical JEITA fig2d style)
        val col1Sections = mutableListOf<HierarchicalSection>()
        var app1ExifNode: BoxNode? = null
        var app1ExifId: String? = null
        var sefNode: BoxNode? = null
        var sefId: String? = null
        var motionPhotoNode: BoxNode? = null

        val soiIndices = children.indices.filter { children[it].type == "SOI" }
        val eoiIndices = children.indices.filter { children[it].type == "EOI" }
        val hasGainMap = soiIndices.size > 1 && eoiIndices.size > 1

        for ((idx, child) in children.withIndex()) {
            val isGainMap = hasGainMap && idx >= soiIndices[1] && idx <= eoiIndices[1]
            val secId = "c1_sec_$idx"

            when {
                child.type == "SOI" -> {
                    val desc = if (isGainMap) "Start of Image (Gain Map Secondary)" else "Start of Image"
                    col1Sections += HierarchicalSection(secId, "SOI", desc, "0xFFD8", child.offset, 2L, RegionRole.MARKER)
                }
                child.type.startsWith("APP1") -> {
                    val isExif = child.summary?.contains("Exif", ignoreCase = true) == true || child.children.any { it.type.contains("IFD") }
                    val isXmp = child.summary?.contains("XMP", ignoreCase = true) == true || child.fields.any { it.name.contains("xmp", ignoreCase = true) }
                    val name = when {
                        isGainMap -> "Application Marker Segment 1 (Gain Map XMP)"
                        isExif -> "Application Marker Segment 1 (Exif Attribute Information)"
                        isXmp -> "Application Marker Segment 1 (XMP Metadata)"
                        else -> "Application Marker Segment 1"
                    }
                    col1Sections += HierarchicalSection(secId, "APP1", name, "${child.size} B", child.offset, child.size, RegionRole.METADATA)
                    if (isExif && app1ExifNode == null) {
                        app1ExifNode = child
                        app1ExifId = secId
                    }
                }
                child.type.startsWith("APP2") -> {
                    val isIso = child.summary?.contains("ISO 21496-1", ignoreCase = true) == true
                    val isIcc = child.summary?.contains("ICC", ignoreCase = true) == true
                    val name = when {
                        isGainMap -> "(Application Marker Segment 2) (Gain Map ISO Metadata)"
                        isIso -> "(Application Marker Segment 2) (ISO 21496-1 Gain Map)"
                        isIcc -> "(Application Marker Segment 2) (ICC Color Profile)"
                        else -> "(Application Marker Segment 2)"
                    }
                    col1Sections += HierarchicalSection(secId, "(APP2)", name, "${child.size} B", child.offset, child.size, RegionRole.METADATA, isOptional = true)
                }
                child.type == "DQT" -> {
                    col1Sections += HierarchicalSection(secId, "DQT", "Quantization Table", child.summary ?: "${child.size} B", child.offset, child.size, RegionRole.CODEC_SETUP)
                }
                child.type == "DHT" -> {
                    col1Sections += HierarchicalSection(secId, "DHT", "Huffman Table", child.summary ?: "${child.size} B", child.offset, child.size, RegionRole.CODEC_SETUP)
                }
                child.type == "DRI" -> {
                    col1Sections += HierarchicalSection(secId, "(DRI)", "(Restart Interval)", child.summary ?: "${child.size} B", child.offset, child.size, RegionRole.CODEC_SETUP, isOptional = true)
                }
                child.type.startsWith("SOF") -> {
                    val name = if (isGainMap) "Frame Header (Gain Map SOF0)" else "Frame Header"
                    col1Sections += HierarchicalSection(secId, "SOF", name, child.summary ?: "${child.size} B", child.offset, child.size, RegionRole.CODEC_SETUP)
                }
                child.type == "SOS" -> {
                    val name = if (isGainMap) "Scan Header & Gain Map Data" else "Scan Header & Compressed Data"
                    col1Sections += HierarchicalSection(secId, "SOS", name, "${child.size} B", child.offset, child.size, RegionRole.MEDIA_DATA)
                }
                child.type == "EOI" -> {
                    val name = if (isGainMap) "End of Image (Gain Map)" else "End of Image"
                    col1Sections += HierarchicalSection(secId, "EOI", name, "0xFFD9", child.offset, 2L, RegionRole.MARKER)
                }
                child.type == "sefd" || child.type == "SEFT" -> {
                    col1Sections += HierarchicalSection(secId, "[SEF]", "Samsung Extension Format (Trailer)", "${child.size} B", child.offset, child.size, RegionRole.TRAILER)
                    sefNode = child
                    sefId = secId
                }
                child.type == "ftyp" -> {
                    col1Sections += HierarchicalSection(secId, "[MP4]", "Embedded MP4 Video (Google Motion Photo)", "${child.size} B", child.offset, child.size, RegionRole.MEDIA_DATA)
                    motionPhotoNode = child
                }
                else -> {
                    col1Sections += HierarchicalSection(secId, child.type, "${child.type} Segment", "${child.size} B", child.offset, child.size, RegionRole.OTHER)
                }
            }
        }
        columns += HierarchicalColumn("col_file", "Structure of Compressed file", "JPEG Container Layout", col1Sections)

        // Column 2: Structure of APP1 (Exif) - Canonical JEITA Breakdown
        if (app1ExifNode != null && app1ExifId != null) {
            val app1 = app1ExifNode
            val col2Sections = mutableListOf<HierarchicalSection>()
            col2Sections += HierarchicalSection("c2_m", "APP1 Marker", "Application Marker 1", "0xFFE1", app1.offset, 2L, RegionRole.MARKER)
            col2Sections += HierarchicalSection("c2_l", "APP1 Length", "Segment Payload Length", "${app1.size - 2} B", app1.offset + 2, 2L, RegionRole.METADATA)
            col2Sections += HierarchicalSection("c2_id", "Exif Identifier", "Exif Identifier Code", "Exif\\0\\0", app1.offset + 4, 6L, RegionRole.METADATA)
            col2Sections += HierarchicalSection("c2_tiff", "TIFF Header", "Byte Order (II/MM) + 0x002A + IFD Offset", "8 Bytes", app1.offset + 10, 8L, RegionRole.METADATA)

            val ifd0 = app1.findRecursive("IFD0")
            if (ifd0 != null) {
                val count = ifd0.fields.size
                col2Sections += HierarchicalSection("c2_ifd0", "0th IFD", "Primary Image Directory ($count tags)", "${ifd0.size} B", ifd0.offset, ifd0.size, RegionRole.METADATA)
                col2Sections += HierarchicalSection("c2_ifd0_v", "0th IFD Value", "Tag Data (Make, Model, Orientation, Exif)", "Values Offset", ifd0.offset + 2, ifd0.size - 2, RegionRole.METADATA)
            } else {
                col2Sections += HierarchicalSection("c2_ifd0", "0th IFD", "Primary Image Directory Entries", "IFD0", app1.offset + 18, 64L, RegionRole.METADATA)
            }

            val ifd1 = app1.findRecursive("IFD1")
            val thumb = app1.findRecursive("ThumbnailImage") ?: app1.children.find { it.type.contains("Thumb") }
            if (ifd1 != null) {
                col2Sections += HierarchicalSection("c2_ifd1", "1st IFD", "Thumbnail Directory Attributes", "${ifd1.size} B", ifd1.offset, ifd1.size, RegionRole.METADATA)
            }
            if (thumb != null) {
                col2Sections += HierarchicalSection("c2_ifd1_img", "1st IFD Image Data", "Embedded JPEG Thumbnail", "${thumb.size} B", thumb.offset, thumb.size, RegionRole.MEDIA_DATA)
            }

            columns += HierarchicalColumn("col_app1", "Structure of APP1", "Exif Attribute Information", col2Sections)
            projections += HierarchicalProjection(app1ExifId, "col_app1", 0xFF0284C7L, "Exif Breakdown")
        }

        // Column 2 or 3: Structure of SEF Trailer
        if (sefNode != null && sefId != null) {
            val sef = sefNode
            val sefSections = mutableListOf<HierarchicalSection>()
            var mpSubId: String? = null

            for ((sIdx, sub) in sef.children.withIndex()) {
                val subId = "c_sef_sub_$sIdx"
                val isMp = sub.type == "MotionPhoto_Data" || sub.summary?.contains("embedded MP4", ignoreCase = true) == true
                sefSections += HierarchicalSection(subId, sub.type, sub.type, sub.summary ?: "${sub.size} B", sub.offset, sub.size, if (isMp) RegionRole.MEDIA_DATA else RegionRole.TRAILER)
                if (isMp) {
                    motionPhotoNode = sub
                    mpSubId = subId
                }
            }
            sefSections += HierarchicalSection("c_sef_ft", "SEFT", "Samsung Trailer Footer & Key Count", "Trailer Magic", sef.offset + sef.size - 32, 32L, RegionRole.TRAILER)

            val colSefId = "col_sef"
            columns += HierarchicalColumn(colSefId, "Structure of SEF Trailer", "Samsung Extension Format", sefSections)
            projections += HierarchicalProjection(sefId, colSefId, 0xFF7C3AEDL, "SEF Directory")

            // Column 3: Structure of Embedded MP4 (from MotionPhoto_Data)
            if (motionPhotoNode != null && mpSubId != null) {
                val mp4Cols = buildMp4Columns(motionPhotoNode)
                if (mp4Cols != null) {
                    columns += mp4Cols
                    projections += HierarchicalProjection(mpSubId, mp4Cols.id, 0xFF7C3AEDL, "Embedded MP4")
                }
            }
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildMp4(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        // Col 1: Structure of ISO Base Media File
        val col1Sections = mutableListOf<HierarchicalSection>()
        var moovNode: BoxNode? = null
        var moovId: String? = null

        for ((idx, child) in children.withIndex()) {
            val sId = "mp4_c1_$idx"
            col1Sections += HierarchicalSection(sId, child.type, "${child.type} Box", child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
            if (child.type == "moov") {
                moovNode = child
                moovId = sId
            }
        }
        columns += HierarchicalColumn("col_mp4_file", "Structure of ISO Base Media File", "ISOBMFF Top-Level Box Hierarchy", col1Sections)

        // Col 2: Structure of moov Box
        if (moovNode != null && moovId != null) {
            val col2Sections = mutableListOf<HierarchicalSection>()
            var videoTrakNode: BoxNode? = null
            var videoTrakId: String? = null

            for ((tIdx, sub) in moovNode.children.withIndex()) {
                val sId = "moov_sub_$tIdx"
                val isTrak = sub.type == "trak"
                val hdlr = sub.findRecursive("hdlr")
                val isVideo = hdlr?.fields?.any { it.value.contains("vide") } == true || hdlr?.summary?.contains("vide") == true
                val desc = if (isTrak) (if (isVideo) "Video Track" else "Audio Track") else sub.type
                col2Sections += HierarchicalSection(sId, sub.type, desc, sub.summary ?: "${sub.size} B", sub.offset, sub.size, roleOf(sub.type))
                if (isTrak && isVideo && videoTrakNode == null) {
                    videoTrakNode = sub
                    videoTrakId = sId
                }
            }
            columns += HierarchicalColumn("col_moov", "Structure of moov Box", "Movie Header & Tracks", col2Sections)
            projections += HierarchicalProjection(moovId, "col_moov", 0xFF0078D4L, "Movie Resource")

            // Col 3: Structure of Video trak (Sample Table Breakdown)
            if (videoTrakNode != null && videoTrakId != null) {
                val col3Sections = mutableListOf<HierarchicalSection>()
                val tkhd = videoTrakNode.children.find { it.type == "tkhd" }
                val mdia = videoTrakNode.children.find { it.type == "mdia" }
                val minf = mdia?.children?.find { it.type == "minf" }
                val stbl = minf?.children?.find { it.type == "stbl" }

                if (tkhd != null) col3Sections += HierarchicalSection("trak_tkhd", "tkhd", "Track Header Box", tkhd.summary ?: "${tkhd.size} B", tkhd.offset, tkhd.size, RegionRole.CONTAINER)
                if (mdia != null) col3Sections += HierarchicalSection("trak_mdhd", "mdhd", "Media Header Box", mdia.summary ?: "${mdia.size} B", mdia.offset, mdia.size, RegionRole.CONTAINER)
                if (mdia != null) col3Sections += HierarchicalSection("trak_hdlr", "hdlr", "Handler Reference Box (vide)", "Video Handle", mdia.offset, 33L, RegionRole.METADATA)

                if (stbl != null) {
                    for ((sIdx, sChild) in stbl.children.withIndex()) {
                        val sId = "stbl_$sIdx"
                        val name = when (sChild.type) {
                            "stsd" -> "Sample Description Box (${sChild.children.firstOrNull()?.type ?: "Codec"})"
                            "stts" -> "Decoding Time-to-Sample Box (Frame Delays)"
                            "stss" -> "Sync Sample Box (IDR / Keyframes)"
                            "stsc" -> "Sample-to-Chunk Mapping Box"
                            "stsz" -> "Sample Size Box (Per-Frame Sizes)"
                            "stco", "co64" -> "Chunk Offset Box (mdat Offsets)"
                            else -> "${sChild.type} Box"
                        }
                        col3Sections += HierarchicalSection(sId, sChild.type, name, sChild.summary ?: "${sChild.size} B", sChild.offset, sChild.size, RegionRole.CODEC_SETUP)
                    }
                }
                columns += HierarchicalColumn("col_trak", "Structure of Video trak (stbl)", "Sample Table & Codec Specification", col3Sections)
                projections += HierarchicalProjection(videoTrakId, "col_trak", 0xFF7C3AEDL, "Sample Table")
            }
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildHeic(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        // Col 1: Structure of HEIF Container
        val col1Sections = mutableListOf<HierarchicalSection>()
        var metaNode: BoxNode? = null
        var metaId: String? = null

        for ((idx, child) in children.withIndex()) {
            val sId = "heic_c1_$idx"
            col1Sections += HierarchicalSection(sId, child.type, "${child.type} Box", child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
            if (child.type == "meta") {
                metaNode = child
                metaId = sId
            }
        }
        columns += HierarchicalColumn("col_heif_file", "Structure of HEIF Container", "ISO/IEC 23008-12 Image Item Model", col1Sections)

        // Col 2: Structure of meta Box
        if (metaNode != null && metaId != null) {
            val col2Sections = mutableListOf<HierarchicalSection>()
            var iprpNode: BoxNode? = null
            var iprpId: String? = null

            for ((mIdx, sub) in metaNode.children.withIndex()) {
                val sId = "meta_sub_$mIdx"
                val desc = when (sub.type) {
                    "hdlr" -> "Handler Reference Box (pict: Image Item Handler)"
                    "pitm" -> "Primary Item Reference Box (Master Image ID)"
                    "iloc" -> "Item Location Box (Item Extents in mdat)"
                    "iinf" -> "Item Information Box (Item Type Registry)"
                    "iprp" -> "Item Properties Box (Visual & Codec Properties)"
                    "idat" -> "Item Data Box (In-meta Image Bytes)"
                    else -> "${sub.type} Box"
                }
                col2Sections += HierarchicalSection(sId, sub.type, desc, sub.summary ?: "${sub.size} B", sub.offset, sub.size, roleOf(sub.type))
                if (sub.type == "iprp") {
                    iprpNode = sub
                    iprpId = sId
                }
            }
            columns += HierarchicalColumn("col_meta", "Structure of meta Box", "Item Management & Extents", col2Sections)
            projections += HierarchicalProjection(metaId, "col_meta", 0xFF0284C7L, "Item Metadata")

            // Col 3: Structure of iprp Box (Properties)
            if (iprpNode != null && iprpId != null) {
                val col3Sections = mutableListOf<HierarchicalSection>()
                val ipco = iprpNode.children.find { it.type == "ipco" }
                val ipma = iprpNode.children.find { it.type == "ipma" }

                if (ipco != null) {
                    for ((pIdx, prop) in ipco.children.withIndex()) {
                        val sId = "prop_$pIdx"
                        val desc = when (prop.type) {
                            "ispe" -> "Image Spatial Extents (${prop.fields.find { it.name == "image_width" }?.value}×${prop.fields.find { it.name == "image_height" }?.value})"
                            "hvcC" -> "HEVC Decoder Configuration Record (SPS / PPS)"
                            "av1C" -> "AV1 Decoder Configuration Record"
                            "auxC" -> "Auxiliary Type Property (${prop.summary ?: "Gain Map / Alpha"})"
                            "colr" -> "Colour Information Property (NCLX / ICC)"
                            "pixi" -> "Pixel Information Property (Bits per channel)"
                            else -> "${prop.type} Property"
                        }
                        col3Sections += HierarchicalSection(sId, prop.type, desc, prop.summary ?: "${prop.size} B", prop.offset, prop.size, RegionRole.CODEC_SETUP)
                    }
                }
                if (ipma != null) {
                    col3Sections += HierarchicalSection("ipma_sec", "ipma", "Item Property Association Box (Item-to-Property Map)", "${ipma.size} B", ipma.offset, ipma.size, RegionRole.METADATA)
                }
                columns += HierarchicalColumn("col_iprp", "Structure of iprp Box", "Item Property Container (ipco/ipma)", col3Sections)
                projections += HierarchicalProjection(iprpId, "col_iprp", 0xFF7C3AEDL, "Property Record")
            }
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildPng(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        // Col 1: Structure of PNG File
        val col1Sections = mutableListOf<HierarchicalSection>()
        var ihdrNode: BoxNode? = null
        var ihdrId: String? = null

        col1Sections += HierarchicalSection("png_sig", "Signature", "8-Byte PNG Header (89 50 4E 47 0D 0A 1A 0A)", "8 Bytes", 0L, 8L, RegionRole.MARKER)

        for ((idx, child) in children.withIndex()) {
            val sId = "png_c1_$idx"
            val desc = when (child.type) {
                "IHDR" -> "Image Header (Critical: Dimensions, Depth, Color)"
                "PLTE" -> "Palette Table (Indexed Color)"
                "IDAT" -> "Image Data Chunk (Compressed Scanlines)"
                "IEND" -> "Image Trailer (End of PNG Datastream)"
                "sRGB" -> "Standard RGB Color Space"
                "iCCP" -> "Embedded ICC Color Profile"
                "eXIf" -> "Exif Metadata Chunk"
                "tEXt", "iTXt", "zTXt" -> "Textual Information Metadata"
                else -> "${child.type} Chunk"
            }
            col1Sections += HierarchicalSection(sId, child.type, desc, child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
            if (child.type == "IHDR") {
                ihdrNode = child
                ihdrId = sId
            }
        }
        columns += HierarchicalColumn("col_png_file", "Structure of PNG File", "W3C Portable Network Graphics Spec", col1Sections)

        // Col 2: Structure of IHDR Chunk
        if (ihdrNode != null && ihdrId != null) {
            val col2Sections = mutableListOf<HierarchicalSection>()
            val off = ihdrNode.offset
            col2Sections += HierarchicalSection("ihdr_len", "Length", "Chunk Data Length (13 Bytes)", "13 B", off, 4L, RegionRole.METADATA)
            col2Sections += HierarchicalSection("ihdr_type", "Chunk Type", "Chunk Identifier ('IHDR')", "IHDR", off + 4, 4L, RegionRole.MARKER)
            col2Sections += HierarchicalSection("ihdr_w", "Width", "Image Width in Pixels", "${ihdrNode.fields.find { it.name == "width" }?.value ?: "W"} px", off + 8, 4L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_h", "Height", "Image Height in Pixels", "${ihdrNode.fields.find { it.name == "height" }?.value ?: "H"} px", off + 12, 4L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_depth", "Bit Depth", "Bits per Sample or Palette Index", "${ihdrNode.fields.find { it.name == "bit_depth" }?.value ?: "8"} bits", off + 16, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_color", "Color Type", "PNG Color Model (Greyscale/RGB/Indexed/Alpha)", "${ihdrNode.fields.find { it.name == "color_type" }?.value ?: "6"}", off + 17, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_comp", "Compression", "Compression Method (0: Deflate/Inflate)", "0", off + 18, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_filter", "Filter Method", "Adaptive Filter Method (0: Standard)", "0", off + 19, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_interlace", "Interlace", "Transmission Order (0: None, 1: Adam7)", "${ihdrNode.fields.find { it.name == "interlace" }?.value ?: "0"}", off + 20, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("ihdr_crc", "CRC", "Cyclic Redundancy Code", "4 Bytes", off + 21, 4L, RegionRole.METADATA)

            columns += HierarchicalColumn("col_ihdr", "Structure of IHDR Chunk", "13-Byte Canonical Image Header", col2Sections)
            projections += HierarchicalProjection(ihdrId, "col_ihdr", 0xFF059669L, "Header Breakdown")
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildWebp(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        val col1Sections = mutableListOf<HierarchicalSection>()
        var vp8xNode: BoxNode? = null
        var vp8xId: String? = null

        for ((idx, child) in children.withIndex()) {
            val sId = "webp_c1_$idx"
            col1Sections += HierarchicalSection(sId, child.type, "${child.type} Chunk", child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
            if (child.type == "VP8X") {
                vp8xNode = child
                vp8xId = sId
            }
        }
        columns += HierarchicalColumn("col_webp_file", "Structure of WebP (RIFF)", "WebP Container Datastream", col1Sections)

        if (vp8xNode != null && vp8xId != null) {
            val col2Sections = mutableListOf<HierarchicalSection>()
            val off = vp8xNode.offset
            col2Sections += HierarchicalSection("vp8x_header", "Chunk Header", "FourCC 'VP8X' + 10 Bytes", "10 B", off, 8L, RegionRole.MARKER)
            col2Sections += HierarchicalSection("vp8x_flags", "Feature Flags", "ICC / Alpha / EXIF / XMP / Animation flags", "1 Byte", off + 8, 1L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("vp8x_res", "Canvas Dimensions", "Canvas Width × Height (24-bit values)", vp8xNode.summary ?: "Dimensions", off + 12, 6L, RegionRole.CODEC_SETUP)

            columns += HierarchicalColumn("col_vp8x", "Structure of VP8X Chunk", "Extended WebP Feature Header", col2Sections)
            projections += HierarchicalProjection(vp8xId, "col_vp8x", 0xFFD97706L, "VP8X Features")
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildBmp(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val projections = mutableListOf<HierarchicalProjection>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        val col1Sections = mutableListOf<HierarchicalSection>()
        var dibNode: BoxNode? = null
        var dibId: String? = null

        for ((idx, child) in children.withIndex()) {
            val sId = "bmp_c1_$idx"
            col1Sections += HierarchicalSection(sId, child.type, "${child.type} Segment", child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
            if (child.type == "BITMAPINFOHEADER" || child.type.contains("DIB") || child.type.contains("BITMAPV")) {
                dibNode = child
                dibId = sId
            }
        }
        columns += HierarchicalColumn("col_bmp_file", "Structure of BMP File", "Canonical Windows Bitmap Architecture", col1Sections)

        if (dibNode != null && dibId != null) {
            val col2Sections = mutableListOf<HierarchicalSection>()
            val off = dibNode.offset
            col2Sections += HierarchicalSection("dib_size", "biSize", "Header Size (40 / 56 / 108 / 124 Bytes)", "${dibNode.size} B", off, 4L, RegionRole.METADATA)
            col2Sections += HierarchicalSection("dib_w", "biWidth", "Bitmap Width in Pixels", "${dibNode.fields.find { it.name == "width" }?.value ?: "W"} px", off + 4, 4L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("dib_h", "biHeight", "Bitmap Height in Pixels", "${dibNode.fields.find { it.name == "height" }?.value ?: "H"} px", off + 8, 4L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("dib_planes", "biPlanes", "Color Planes (Must be 1)", "1", off + 12, 2L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("dib_bpp", "biBitCount", "Bits Per Pixel (1, 4, 8, 16, 24, 32)", "${dibNode.fields.find { it.name == "bpp" }?.value ?: "24"} bpp", off + 14, 2L, RegionRole.CODEC_SETUP)
            col2Sections += HierarchicalSection("dib_comp", "biCompression", "Compression Type (BI_RGB, BI_BITFIELDS)", "0 (BI_RGB)", off + 16, 4L, RegionRole.CODEC_SETUP)

            columns += HierarchicalColumn("col_dib", "Structure of DIB Header", "Device Independent Bitmap Header", col2Sections)
            projections += HierarchicalProjection(dibId, "col_dib", 0xFF0284C7L, "DIB Fields")
        }

        return HierarchicalDiagram(fileName, fileSize, columns, projections)
    }

    private fun buildGeneric(root: BoxNode, fileSize: Long, fileName: String): HierarchicalDiagram {
        val columns = mutableListOf<HierarchicalColumn>()
        val children = if (root.children.isNotEmpty()) root.children else listOf(root)

        val col1Sections = children.mapIndexed { idx, child ->
            HierarchicalSection("gen_sec_$idx", child.type, "${child.type} Box", child.summary ?: "${child.size} B", child.offset, child.size, roleOf(child.type))
        }
        columns += HierarchicalColumn("col_gen_file", "Structure of $fileName", "Container Segment Layout", col1Sections)

        return HierarchicalDiagram(fileName, fileSize, columns)
    }

    private fun buildMp4Columns(mpNode: BoxNode): HierarchicalColumn? {
        val mpChildren = mpNode.children
        val sec = mutableListOf<HierarchicalSection>()

        val ftyp = mpChildren.find { it.type == "ftyp" }
        val moov = mpChildren.find { it.type == "moov" }
        val mdat = mpChildren.find { it.type == "mdat" }

        if (ftyp != null) {
            val major = ftyp.fields.find { it.name == "major_brand" }?.value ?: "isom"
            sec += HierarchicalSection("mp_ftyp", "ftyp", "File Type Box (Brand: $major)", "${ftyp.size} B", ftyp.offset, ftyp.size, RegionRole.MARKER)
        }
        if (moov != null) {
            val mvhd = moov.findRecursive("mvhd")
            val trak = moov.findRecursive("trak")
            val stsd = trak?.findRecursive("stsd")
            val codec = stsd?.children?.firstOrNull()?.type ?: "HEVC"
            val tkhd = trak?.children?.find { it.type == "tkhd" }
            val w = tkhd?.fields?.find { it.name == "width" }?.value
            val h = tkhd?.fields?.find { it.name == "height" }?.value
            val res = if (w != null && h != null) "${w}×${h}" else ""
            val duration = mvhd?.summary ?: "Duration"

            sec += HierarchicalSection("mp_moov", "moov", "Movie Header Box ($duration)", "${moov.size} B", moov.offset, moov.size, RegionRole.CONTAINER)
            sec += HierarchicalSection("mp_trak", "trak", "Video Track ($res $codec)", trak?.summary ?: "${trak?.size ?: 0} B", trak?.offset ?: moov.offset, trak?.size ?: 0L, RegionRole.CONTAINER)
        }
        if (mdat != null) {
            sec += HierarchicalSection("mp_mdat", "mdat", "Media Data Box (Video Bitstream)", "${mdat.size} B", mdat.offset, mdat.size, RegionRole.MEDIA_DATA)
        }

        if (sec.isEmpty()) return null
        return HierarchicalColumn("col_mp4_embed", "Structure of Embedded MP4", "Motion Photo Video Clip", sec)
    }

    private fun BoxNode.findRecursive(type: String): BoxNode? {
        if (this.type == type) return this
        for (child in children) {
            val found = child.findRecursive(type)
            if (found != null) return found
        }
        return null
    }

    private fun roleOf(type: String): RegionRole = when {
        type in setOf("SOI", "EOI", "ftyp", "RIFF", "IEND") -> RegionRole.MARKER
        type in setOf("SOS", "mdat", "IDAT", "data") -> RegionRole.MEDIA_DATA
        type in setOf("DQT", "DHT", "SOF0", "SOF2", "IHDR", "stsd", "hvcC", "av1C") -> RegionRole.CODEC_SETUP
        type in setOf("sefd", "SEFT", "SEFH") -> RegionRole.TRAILER
        type in setOf("APP1", "APP2", "Exif", "XMP", "meta", "iCCP", "eXIf") -> RegionRole.METADATA
        type in setOf("moov", "trak", "minf", "stbl") -> RegionRole.CONTAINER
        else -> RegionRole.OTHER
    }
}
