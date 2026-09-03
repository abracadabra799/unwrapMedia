package com.multiviewer.parser

import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private val CODEC_DISPLAY_NAMES = mapOf(
    "avc1" to "AVC",
    "hvc1" to "HEVC",
    "av01" to "AV1",
    "apv1" to "APV",
    "mp4a" to "AAC",
)

fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = when (category) {
        MediaCategory.IMAGE -> buildImageSummary(root, file)
        MediaCategory.VIDEO -> when {
            isWebm(root) -> buildWebmVideoSummary(root, file.length())
            isAvi(root) -> buildAviVideoSummary(root, file.length())
            isFlv(root) -> buildFlvVideoSummary(root, file.length())
            isAsf(root) -> buildAsfVideoSummary(root, file.length())
            else -> buildVideoSummary(root, file.length())
        }
        MediaCategory.AUDIO -> when {
            root.children.any { it.type == "moov" } -> buildVideoSummary(root, file.length())
            isAac(root) -> buildAacSummary(root, file.length())
            else -> buildStandaloneAudioSummary(root, file.length())
        }
    }
    val motionPhotoVideoSections = if (category == MediaCategory.IMAGE) {
        buildMotionPhotoVideoSummary(root, file)
    } else {
        null
    }
    val thumbnail = if (category == MediaCategory.IMAGE) buildThumbnail(root, file) else null
    return MediaSummary(category, sections, motionPhotoVideoSections, thumbnail)
}

private fun buildMotionPhotoVideoSummary(root: BoxNode, file: File): List<SummarySection>? {
    return try {
        ByteReader.open(file).use { reader ->
            val video = findEmbeddedVideo(root, reader) ?: return null
            val videoBoxes = parseBoxes(reader, video.start, video.end)
            val videoRoot = BoxNode(
                type = "root", offset = video.start, headerSize = 0,
                size = video.end - video.start, children = videoBoxes,
            )
            buildVideoSummary(videoRoot, video.end - video.start)
        }
    } catch (e: Exception) {
        null
    }
}

private fun buildThumbnail(root: BoxNode, file: File): ByteArray? {
    val thumbnailNode = findFirst(root) { it.type == "ThumbnailImage" } ?: return null
    return try {
        ByteReader.open(file).use { reader ->
            reader.readBytes(thumbnailNode.offset, thumbnailNode.size.toInt())
        }
    } catch (e: Exception) {
        null
    }
}

private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val ftyp = root.children.find { it.type == "ftyp" }
    val majorBrand = ftyp?.fields?.find { it.name == "major_brand" }?.value
    if (majorBrand == "avif" || majorBrand == "avis" || majorBrand == "heic") return MediaCategory.IMAGE
    if (isWebm(root)) return MediaCategory.VIDEO

    // WAV and WebP both put a "RIFF" node at the root (WebP stores its form-type in a field,
    // not the node's own type string), so a WAV check can't key off "RIFF" alone -- "fmt " is
    // WAV-specific and never appears in a WebP tree.
    if (root.children.any { it.type == "fmt " }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "ID3v2" || it.type == "AudioFrames" || it.type == "ID3v1" }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "fLaC" }) return MediaCategory.AUDIO
    if (root.children.any { it.type.startsWith("Ogg") }) return MediaCategory.AUDIO
    if (root.children.any { it.type == "COMM" }) return MediaCategory.AUDIO
    if (isAac(root)) return MediaCategory.AUDIO

    if (isAvi(root) || isFlv(root) || isAsf(root)) return MediaCategory.VIDEO

    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val trakHandlerTypes = moov.children.filter { it.type == "trak" }.mapNotNull { trak ->
        findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
    }
    return when {
        trakHandlerTypes.contains("vide") -> MediaCategory.VIDEO
        trakHandlerTypes.contains("soun") -> MediaCategory.AUDIO
        else -> MediaCategory.IMAGE
    }
}

private fun isWebm(root: BoxNode): Boolean = root.children.any { it.type == "EBML" }

private fun isAvi(root: BoxNode): Boolean =
    root.children.any { it.type == "RIFF" && it.fields.any { f -> f.value == "AVI " || f.value == "AVIX" } }

private fun isFlv(root: BoxNode): Boolean =
    root.children.any { it.type == "FLV Header" }

private fun isAsf(root: BoxNode): Boolean =
    root.children.any { it.type == "ASF Header Object" }

private fun isAac(root: BoxNode): Boolean =
    root.children.any { it.type.startsWith("ADTS Frame") }

fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
    if (predicate(node)) return node
    for (child in node.children) {
        val found = findFirst(child, predicate)
        if (found != null) return found
    }
    return null
}


private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}

private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
    buildJpegDetail(root)?.let { sections.add(it) }
    buildPngDetail(root)?.let { sections.add(it) }
    buildBmpDetail(root)?.let { sections.add(it) }
    buildGifDetail(root)?.let { sections.add(it) }
    buildWebpDetail(root)?.let { sections.add(it) }
    buildTiffDetail(root)?.let { sections.add(it) }
    buildHeicDetail(root)?.let { sections.add(it) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    if (ifd0 != null) {
        val cameraFields = mutableListOf<SummaryField>()
        ifd0.fields.find { it.name == "Make" }?.let { cameraFields.add(SummaryField("Make", it.value)) }
        ifd0.fields.find { it.name == "Model" }?.let { cameraFields.add(SummaryField("Model", it.value)) }
        val exif = ifd0.children.find { it.type == "Exif" }
        exif?.fields?.find { it.name == "ExposureTime" }?.let { cameraFields.add(SummaryField("Exposure Time", it.value)) }
        exif?.fields?.find { it.name == "FNumber" }?.let { cameraFields.add(SummaryField("F-Number", it.value)) }
        exif?.fields?.find { it.name == "ISOSpeedRatings" }?.let { cameraFields.add(SummaryField("ISO", it.value)) }
        exif?.fields?.find { it.name == "FocalLength" }?.let { cameraFields.add(SummaryField("Focal Length", it.value)) }
        if (cameraFields.isNotEmpty()) {
            sections.add(SummarySection("Camera Info", cameraFields))
        }

        val gps = ifd0.children.find { it.type == "GPS" }
        if (gps != null) {
            val gpsFields = mutableListOf<SummaryField>()
            gps.fields.find { it.name == "GPSLatitudeRef" }?.let { gpsFields.add(SummaryField("Latitude Ref", it.value)) }
            gps.fields.find { it.name == "GPSLatitude" }?.let { gpsFields.add(SummaryField("Latitude", it.value)) }
            gps.fields.find { it.name == "GPSLongitudeRef" }?.let { gpsFields.add(SummaryField("Longitude Ref", it.value)) }
            gps.fields.find { it.name == "GPSLongitude" }?.let { gpsFields.add(SummaryField("Longitude", it.value)) }
            if (gpsFields.isNotEmpty()) {
                sections.add(SummarySection("GPS Location", gpsFields))
            }
        }
    }

    val sefd = findFirst(root) { it.type == "sefd" }
    if (sefd != null && sefd.children.isNotEmpty()) {
        val sefdFields = sefd.children.map { field ->
            SummaryField(field.type, field.summary ?: field.fields.firstOrNull()?.value ?: "")
        }
        sections.add(SummarySection("Samsung Metadata", sefdFields))
    }

    buildAppleDeviceSection(root)?.let { sections.add(it) }
    buildAppleCameraAndCaptureSection(root)?.let { sections.add(it) }
    buildAppleComputationalPhotographySection(root)?.let { sections.add(it) }
    buildAppleDepthAndPortraitSection(root)?.let { sections.add(it) }
    buildAppleLivePhotoSection(root)?.let { sections.add(it) }

    return sections
}

private fun buildImageGeneral(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val isGif = root.children.any { it.type == "LogicalScreenDescriptor" }
    val isWebp = root.children.any { it.type == "RIFF" }

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else if (isPng) {
        "PNG"
    } else if (isBmp) {
        "BMP"
    } else if (isGif) {
        "GIF"
    } else if (isWebp) {
        "WebP"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))
    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    return SummarySection("General", fields)
}

private fun buildImageDetail(root: BoxNode): SummarySection? {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val isGif = root.children.any { it.type == "LogicalScreenDescriptor" }
    
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val webpInfo = findFirst(root) { it.type in listOf("VP8X", "VP8 ", "VP8L") }
    
    val infoNode = sof ?: ispe ?: webpInfo

    if (infoNode != null) {
        val width = infoNode.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = infoNode.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", abs(height).toString()))
        }
    } else if (isGif) {
        val lsd = root.children.find { it.type == "LogicalScreenDescriptor" }
        val width = lsd?.fields?.find { it.name == "width" }?.value
        val height = lsd?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    }

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (infoNode != null && isJpeg) {
        when (infoNode.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val colorType = ihdr?.fields?.find { it.name == "color_type" }?.value?.toIntOrNull()
        colorType?.let { PNG_COLOR_TYPE_NAMES[it] }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    val frameCount = root.children.count { it.type == "ImageDescriptor" }
    if (frameCount > 0) {
        fields.add(SummaryField("Frame Count", frameCount.toString()))
    }

    val loopCount = root.children.find { it.type == "ApplicationExtension" }
        ?.fields?.find { it.name == "loop_count" }?.value?.toIntOrNull()
    if (loopCount != null) {
        fields.add(SummaryField("Loop Count", if (loopCount == 0) "Infinite" else loopCount.toString()))
    }

    return if (fields.isNotEmpty()) SummarySection("Image", fields) else null
}

private val SOF_ENCODING_NAMES = mapOf(
    0 to "Baseline DCT (Huffman)",
    1 to "Extended Sequential DCT (Huffman)",
    2 to "Progressive DCT (Huffman)",
    3 to "Lossless (Sequential, Huffman)",
    5 to "Differential Sequential DCT (Huffman)",
    6 to "Differential Progressive DCT (Huffman)",
    7 to "Differential Lossless (Huffman)",
    9 to "Extended Sequential DCT (Arithmetic)",
    10 to "Progressive DCT (Arithmetic)",
    11 to "Lossless (Sequential, Arithmetic)",
    13 to "Differential Sequential DCT (Arithmetic)",
    14 to "Differential Progressive DCT (Arithmetic)",
    15 to "Differential Lossless (Arithmetic)",
)

private val STANDARD_DC_LUMINANCE_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
private val STANDARD_DC_LUMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_DC_CHROMINANCE_BITS = intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
private val STANDARD_DC_CHROMINANCE_VALUES = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

private val STANDARD_AC_LUMINANCE_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D)
private val STANDARD_AC_LUMINANCE_VALUES = intArrayOf(
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)

private val STANDARD_AC_CHROMINANCE_BITS = intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
private val STANDARD_AC_CHROMINANCE_VALUES = intArrayOf(
    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
    0xF9, 0xFA,
)

private fun standardHuffmanTable(className: String, destinationId: String): Pair<IntArray, IntArray>? = when {
    className == "DC" && destinationId == "0" -> STANDARD_DC_LUMINANCE_BITS to STANDARD_DC_LUMINANCE_VALUES
    className == "DC" && destinationId == "1" -> STANDARD_DC_CHROMINANCE_BITS to STANDARD_DC_CHROMINANCE_VALUES
    className == "AC" && destinationId == "0" -> STANDARD_AC_LUMINANCE_BITS to STANDARD_AC_LUMINANCE_VALUES
    className == "AC" && destinationId == "1" -> STANDARD_AC_CHROMINANCE_BITS to STANDARD_AC_CHROMINANCE_VALUES
    else -> null
}

private fun huffmanTableMatchesStandard(table: BoxNode): Boolean {
    val className = table.fields.find { it.name == "class" }?.value ?: return false
    val destinationId = table.fields.find { it.name == "destination_id" }?.value ?: return false
    val (standardBits, standardValues) = standardHuffmanTable(className, destinationId) ?: return false

    val actualBits = table.fields.find { it.name == "bit_counts" }?.value
        ?.split(", ")?.map { it.trim().toInt() }?.toIntArray() ?: return false
    if (!actualBits.contentEquals(standardBits)) return false

    val actualValues = (1..16).flatMap { length ->
        val field = table.fields.find { it.name == "codes_length_${length.toString().padStart(2, '0')}" }
        field?.value?.split(", ")?.map { it.trim().toInt(16) } ?: emptyList()
    }.toIntArray()
    return actualValues.contentEquals(standardValues)
}

// A real-world file can hold more than one complete JPEG stream back-to-back at the top level
// (e.g. a Samsung Motion Photo's outer photo followed by an embedded MPF-style secondary/preview
// JPEG, both landing as direct root children -- confirmed against a real device photo during
// manual verification). Scoping to children up through the first EOI keeps every field here
// (Quality Estimate, Huffman Tables, Adobe Color Transform, Restart Interval, Comment) describing
// only the primary image, not an indiscriminate mix of both streams' markers.
private fun primaryJpegStreamChildren(root: BoxNode): List<BoxNode> {
    val eoiIndex = root.children.indexOfFirst { it.type == "EOI" }
    return if (eoiIndex >= 0) root.children.subList(0, eoiIndex + 1) else root.children
}

private fun buildJpegDetail(root: BoxNode): SummarySection? {
    val streamChildren = primaryJpegStreamChildren(root)
    val sof = streamChildren.find { it.type.startsWith("SOF") } ?: return null
    val fields = mutableListOf<SummaryField>()

    sof.type.removePrefix("SOF").toIntOrNull()?.let { sofNumber ->
        SOF_ENCODING_NAMES[sofNumber]?.let { fields.add(SummaryField("Encoding", it)) }
    }
    sof.fields.find { it.name == "precision" }?.let { fields.add(SummaryField("Precision", "${it.value}-bit")) }

    val quantizationTables = streamChildren.filter { it.type == "DQT" }.flatMap { it.children }
    val luminanceTable = quantizationTables.find {
        it.fields.find { f -> f.name == "destination_id" }?.value?.startsWith("0") == true
    }
    (luminanceTable ?: quantizationTables.firstOrNull())
        ?.fields?.find { it.name == "quality_estimate" }
        ?.let { fields.add(SummaryField("Quality Estimate", it.value)) }

    val huffmanTables = streamChildren.filter { it.type == "DHT" }.flatMap { it.children }
    if (huffmanTables.isNotEmpty()) {
        val mismatchLabels = huffmanTables.mapNotNull { table ->
            if (huffmanTableMatchesStandard(table)) {
                null
            } else {
                val className = table.fields.find { it.name == "class" }?.value ?: "?"
                val destinationId = table.fields.find { it.name == "destination_id" }?.value ?: "?"
                "$className$destinationId"
            }
        }
        val huffmanValue = if (mismatchLabels.isEmpty()) {
            "Standard"
        } else {
            "Custom/Optimized (differs: ${mismatchLabels.joinToString(", ")})"
        }
        fields.add(SummaryField("Huffman Tables", huffmanValue))
    }

    val app14 = streamChildren.find { it.type == "APP14" }
    app14?.fields?.find { it.name == "color_transform" }?.value?.toIntOrNull()?.let { transform ->
        val numComponents = sof.fields.find { it.name == "num_components" }?.value?.toIntOrNull()
        val label = when (transform) {
            1 -> "YCbCr"
            2 -> "YCCK"
            0 -> if (numComponents == 4) "CMYK" else "RGB"
            else -> "Unknown ($transform)"
        }
        fields.add(SummaryField("Adobe Color Transform", label))
    }

    val dri = streamChildren.find { it.type == "DRI" }
    dri?.fields?.find { it.name == "restart_interval" }
        ?.let { fields.add(SummaryField("Restart Interval", "${it.value} MCUs")) }

    val com = streamChildren.find { it.type == "COM" }
    com?.fields?.find { it.name == "comment" }?.let { fields.add(SummaryField("Comment", it.value)) }

    return if (fields.isNotEmpty()) SummarySection("JPEG Detail", fields) else null
}

private val PNG_INTERLACE_NAMES = mapOf(0 to "None", 1 to "Adam7")

private fun buildPngDetail(root: BoxNode): SummarySection? {
    val ihdr = root.children.find { it.type == "IHDR" } ?: return null
    val fields = mutableListOf<SummaryField>()

    ihdr.fields.find { it.name == "bit_depth" }?.let { fields.add(SummaryField("Bit Depth", "${it.value}-bit")) }
    ihdr.fields.find { it.name == "compression_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Compression Method", if (method == 0) "Deflate/Inflate" else method.toString()))
    }
    ihdr.fields.find { it.name == "interlace_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Interlace", PNG_INTERLACE_NAMES[method] ?: method.toString()))
    }

    val phys = root.children.find { it.type == "pHYs" }
    val ppuX = phys?.fields?.find { it.name == "pixels_per_unit_x" }?.value?.toDoubleOrNull()
    val ppuY = phys?.fields?.find { it.name == "pixels_per_unit_y" }?.value?.toDoubleOrNull()
    val unitSpecifier = phys?.fields?.find { it.name == "unit_specifier" }?.value
    if (ppuX != null && ppuY != null) {
        if (unitSpecifier == "meter") {
            val dpiX = (ppuX * 0.0254).roundToInt()
            val dpiY = (ppuY * 0.0254).roundToInt()
            fields.add(SummaryField("Pixel Density", if (dpiX == dpiY) "$dpiX DPI" else "$dpiX x $dpiY DPI"))
        } else {
            fields.add(SummaryField("Pixel Density", "${ppuX.toInt()} x ${ppuY.toInt()} px/unit"))
        }
    }

    root.children.filter { it.type == "tEXt" }.forEach { chunk ->
        val keyword = chunk.fields.find { it.name == "keyword" }?.value
        val text = chunk.fields.find { it.name == "text" }?.value
        if (keyword != null && text != null) fields.add(SummaryField(keyword, text))
    }

    return if (fields.isNotEmpty()) SummarySection("PNG Detail", fields) else null
}

private val BMP_COMPRESSION_NAMES = mapOf(
    0 to "None (BI_RGB)",
    1 to "RLE 8-bit (BI_RLE8)",
    2 to "RLE 4-bit (BI_RLE4)",
    3 to "Bit Fields (BI_BITFIELDS)",
    4 to "JPEG (BI_JPEG)",
    5 to "PNG (BI_PNG)",
)

private fun buildBmpDetail(root: BoxNode): SummarySection? {
    val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" } ?: return null
    val fields = mutableListOf<SummaryField>()

    infoHeader.fields.find { it.name == "bit_count" }?.let { fields.add(SummaryField("Bit Count", "${it.value}-bit")) }
    infoHeader.fields.find { it.name == "compression" }?.value?.toIntOrNull()?.let { compression ->
        fields.add(SummaryField("Compression", BMP_COMPRESSION_NAMES[compression] ?: compression.toString()))
    }

    return if (fields.isNotEmpty()) SummarySection("BMP Detail", fields) else null
}

private val GIF_DISPOSAL_METHOD_NAMES = mapOf(
    0 to "Unspecified",
    1 to "Do Not Dispose",
    2 to "Restore to Background",
    3 to "Restore to Previous",
)

private fun buildGifDetail(root: BoxNode): SummarySection? {
    val lsd = root.children.find { it.type == "LogicalScreenDescriptor" } ?: return null
    val fields = mutableListOf<SummaryField>()

    lsd.fields.find { it.name == "color_resolution" }?.value?.toIntOrNull()?.let { fields.add(SummaryField("Color Resolution", "${it + 1}-bit")) }

    val globalColorTableFlag = lsd.fields.find { it.name == "global_color_table_flag" }?.value
    if (globalColorTableFlag == "1") {
        lsd.fields.find { it.name == "global_color_table_size" }?.value?.toIntOrNull()?.let { size ->
            fields.add(SummaryField("Global Color Table", "Yes (${1 shl (size + 1)} colors)"))
        }
    }

    val gce = root.children.find { it.type == "GraphicControlExtension" }
    gce?.fields?.find { it.name == "disposal_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Disposal Method", GIF_DISPOSAL_METHOD_NAMES[method] ?: method.toString()))
    }
    gce?.fields?.find { it.name == "delay_time" }?.value?.toIntOrNull()?.let { delay ->
        fields.add(SummaryField("Frame Delay", "${delay * 10} ms"))
    }

    val comment = root.children.find { it.type == "CommentExtension" }
    comment?.fields?.find { it.name == "comment" }?.let { fields.add(SummaryField("Comment", it.value)) }

    return if (fields.isNotEmpty()) SummarySection("GIF Detail", fields) else null
}

private fun buildWebpDetail(root: BoxNode): SummarySection? {
    val isWebp = root.children.any { it.type == "RIFF" }
    if (!isWebp) return null
    val fields = mutableListOf<SummaryField>()

    val vp8x = root.children.find { it.type == "VP8X" }
    val codec = when {
        vp8x != null -> "Extended (VP8X)"
        root.children.any { it.type == "VP8 " } -> "Lossy (VP8)"
        root.children.any { it.type == "VP8L" } -> "Lossless (VP8L)"
        else -> null
    }
    codec?.let { fields.add(SummaryField("Codec", it)) }

    val flags = vp8x?.fields?.find { it.name == "flags" }?.value?.removePrefix("0x")?.toIntOrNull(16)
    if (flags != null) {
        fields.add(SummaryField("Has Alpha", if (flags and 0x10 != 0) "Yes" else "No"))
        fields.add(SummaryField("Has Animation", if (flags and 0x02 != 0) "Yes" else "No"))
        fields.add(SummaryField("Has ICC Profile", if (flags and 0x20 != 0) "Yes" else "No"))
    }

    return if (fields.isNotEmpty()) SummarySection("WebP Detail", fields) else null
}

private fun buildTiffDetail(root: BoxNode): SummarySection? {
    val ifd0 = findFirst(root) { it.type == "IFD0" } ?: return null
    val fields = mutableListOf<SummaryField>()

    ifd0.fields.find { it.name == "Orientation" }?.let { fields.add(SummaryField("Orientation", it.value)) }
    ifd0.fields.find { it.name == "Compression" }?.let { fields.add(SummaryField("Compression", it.value)) }
    ifd0.fields.find { it.name == "PhotometricInterpretation" }?.let { fields.add(SummaryField("Photometric Interpretation", it.value)) }
    ifd0.fields.find { it.name == "BitsPerSample" }?.let { fields.add(SummaryField("Bits Per Sample", it.value)) }
    ifd0.fields.find { it.name == "SamplesPerPixel" }?.let { fields.add(SummaryField("Samples Per Pixel", it.value)) }

    val xRes = ifd0.fields.find { it.name == "XResolution" }?.value
    val yRes = ifd0.fields.find { it.name == "YResolution" }?.value
    if (xRes != null && yRes != null) {
        val unit = ifd0.fields.find { it.name == "ResolutionUnit" }?.value
        fields.add(SummaryField("Resolution", if (unit != null) "$xRes x $yRes $unit" else "$xRes x $yRes"))
    }

    return if (fields.isNotEmpty()) SummarySection("TIFF Detail", fields) else null
}

private fun buildHeicDetail(root: BoxNode): SummarySection? {
    val fields = mutableListOf<SummaryField>()

    val irot = findPrimaryItemProperty(root, "irot")
    irot?.fields?.find { it.name == "angle" }?.value?.toIntOrNull()?.let { angle ->
        fields.add(SummaryField("Rotation", "${angle * 90}°"))
    }

    val imir = findPrimaryItemProperty(root, "imir")
    imir?.fields?.find { it.name == "axis" }?.value?.toIntOrNull()?.let { axis ->
        fields.add(SummaryField("Mirror", if (axis == 0) "Horizontal Flip (좌우반전)" else "Vertical Flip (상하반전)"))
    }

    val pixi = findPrimaryItemProperty(root, "pixi")
    pixi?.fields?.find { it.name == "bits_per_channel" }?.let { fields.add(SummaryField("Bit Depth", it.value)) }

    val auxC = findFirst(root) { it.type == "auxC" }
    val auxType = auxC?.fields?.find { it.name == "aux_type" }?.value
    if (auxType != null && auxType.contains("alpha", ignoreCase = true)) {
        fields.add(SummaryField("Has Alpha Channel", "Yes"))
    }

    return if (fields.isNotEmpty()) SummarySection("HEIC/AVIF Detail", fields) else null
}

private val WEBM_CODEC_DISPLAY_NAMES = mapOf(
    "V_VP8" to "VP8",
    "V_VP9" to "VP9",
    "V_AV1" to "AV1",
    "A_OPUS" to "Opus",
    "A_VORBIS" to "Vorbis",
)

private fun buildWebmVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val segment = root.children.find { it.type == "Segment" }
    val info = segment?.children?.find { it.type == "Info" }
    val tracks = segment?.children?.find { it.type == "Tracks" }
    val trackEntries = tracks?.children?.filter { it.type == "TrackEntry" } ?: emptyList()
    val videoTrack = trackEntries.find { entryTrackType(it) == 1L }
    val audioTrack = trackEntries.find { entryTrackType(it) == 2L }

    val sections = mutableListOf<SummarySection>()
    sections.add(buildWebmGeneral(fileSizeBytes, info))
    sections.add(buildWebmTrackList(trackEntries))
    buildWebmVideoDetail(videoTrack)?.let { sections.add(it) }
    buildWebmAudioDetail(audioTrack)?.let { sections.add(it) }
    return sections
}

private fun entryTrackType(trackEntry: BoxNode): Long? =
    webmFieldValue(trackEntry, "TrackType")?.toLongOrNull()

private fun webmFieldValue(node: BoxNode?, childType: String): String? =
    node?.children?.find { it.type == childType }?.fields?.find { it.name == "value" }?.value

private fun webmCodecDisplayName(codecId: String?): String? =
    codecId?.let { WEBM_CODEC_DISPLAY_NAMES[it] ?: it }

private fun buildWebmGeneral(fileSizeBytes: Long, info: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val timecodeScale = webmFieldValue(info, "TimecodeScale")?.toDoubleOrNull() ?: 1_000_000.0
    val durationTicks = webmFieldValue(info, "Duration")?.toDoubleOrNull()
    val durationSeconds = durationTicks?.let { it * timecodeScale / 1_000_000_000.0 }
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }
    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))
    fields.add(SummaryField("Format", "WebM"))
    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }
    webmFieldValue(info, "DateUTC")?.takeIf { !it.startsWith("0 ") }?.let { fields.add(SummaryField("Creation Date", it)) }
    webmFieldValue(info, "MuxingApp")?.let { fields.add(SummaryField("Muxing App", it)) }
    webmFieldValue(info, "WritingApp")?.let { fields.add(SummaryField("Writing App", it)) }
    return SummarySection("General", fields)
}

private fun buildWebmTrackList(trackEntries: List<BoxNode>): SummarySection {
    val videoCount = trackEntries.count { entryTrackType(it) == 1L }
    val audioCount = trackEntries.count { entryTrackType(it) == 2L }
    val otherCount = trackEntries.size - videoCount - audioCount
    val fields = mutableListOf(
        SummaryField("Video Tracks", videoCount.toString()),
        SummaryField("Audio Tracks", audioCount.toString()),
    )
    if (otherCount > 0) fields.add(SummaryField("Other Tracks", otherCount.toString()))
    return SummarySection("Track List", fields)
}

private val WEBM_STEREO_MODE_NAMES = mapOf(
    1 to "Side by Side (Left Eye First)",
    2 to "Top-Bottom (Right Eye First)",
    3 to "Top-Bottom (Left Eye First)",
    11 to "Side by Side (Right Eye First)",
)

private fun buildWebmVideoDetail(videoTrack: BoxNode?): SummarySection? {
    if (videoTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(videoTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val video = videoTrack.children.find { it.type == "Video" }
    webmFieldValue(video, "PixelWidth")?.let { fields.add(SummaryField("Width", it)) }
    webmFieldValue(video, "PixelHeight")?.let { fields.add(SummaryField("Height", it)) }
    webmFieldValue(video, "StereoMode")?.toIntOrNull()?.takeIf { it != 0 }?.let { mode ->
        fields.add(SummaryField("Stereo Mode", WEBM_STEREO_MODE_NAMES[mode] ?: mode.toString()))
    }
    return if (fields.isEmpty()) null else SummarySection("Video", fields)
}

private fun buildWebmAudioDetail(audioTrack: BoxNode?): SummarySection? {
    if (audioTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(audioTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val audio = audioTrack.children.find { it.type == "Audio" }
    webmFieldValue(audio, "SamplingFrequency")?.toDoubleOrNull()?.let {
        fields.add(SummaryField("Sampling Rate", "$it Hz"))
    }
    webmFieldValue(audio, "Channels")?.let { fields.add(SummaryField("Channel(s)", it)) }
    return if (fields.isEmpty()) null else SummarySection("Audio", fields)
}

private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildVideoStructureDetail(videoTrak, movieTimescale(moov))?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }
    buildAppleDeviceSection(root)?.let { sections.add(it) }
    buildAppleLivePhotoSection(root)?.let { sections.add(it) }
    buildAppleVideoMetadataSection(root, traks)?.let { sections.add(it) }

    return sections
}

private fun trakHandlerType(trak: BoxNode): String? {
    val hdlr = findFirst(trak) { it.type == "hdlr" }
    return hdlr?.fields?.find { it.name == "handler_type" }?.value
}

private fun buildVideoGeneral(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?, videoTrak: BoxNode?, audioTrak: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()

    val mvhd = moov?.children?.find { it.type == "mvhd" }
    val timescale = mvhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mvhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val durationSeconds = if (timescale != null && timescale > 0 && duration != null) duration.toDouble() / timescale else null
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }

    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Format", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }

    mvhd?.fields?.find { it.name == "creation_time" }?.value?.takeIf { !it.startsWith("0 ") }?.let {
        fields.add(SummaryField("Creation Time", it))
    }
    mvhd?.fields?.find { it.name == "modification_time" }?.value?.takeIf { !it.startsWith("0 ") }?.let {
        fields.add(SummaryField("Modification Time", it))
    }

    trackDuration(videoTrak)?.let { fields.add(SummaryField("Video Track Duration", it)) }
    trackDuration(audioTrak)?.let { fields.add(SummaryField("Audio Track Duration", it)) }

    return SummarySection("General", fields)
}

private fun trackDuration(trak: BoxNode?): String? {
    if (trak == null) return null
    val mdhd = findFirst(trak) { it.type == "mdhd" }
    val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    if (timescale == null || timescale <= 0 || duration == null) return null
    return formatDuration(duration.toDouble() / timescale)
}

private fun buildTrackList(traks: List<BoxNode>): SummarySection {
    val videoCount = traks.count { trakHandlerType(it) == "vide" }
    val audioCount = traks.count { trakHandlerType(it) == "soun" }
    val otherCount = traks.size - videoCount - audioCount
    val fields = mutableListOf(
        SummaryField("Video Tracks", videoCount.toString()),
        SummaryField("Audio Tracks", audioCount.toString()),
    )
    if (otherCount > 0) {
        fields.add(SummaryField("Other Tracks", otherCount.toString()))
    }
    return SummarySection("Track List", fields)
}

private fun buildVideoDetail(videoTrak: BoxNode?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val stsd = findFirst(videoTrak) { it.type == "stsd" }
    stsd?.children?.firstOrNull()?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }

    val tkhd = videoTrak.children.find { it.type == "tkhd" }
    val width = tkhd?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()
    val height = tkhd?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()
    if (width != null && height != null) {
        fields.add(SummaryField("Width", width.toInt().toString()))
        fields.add(SummaryField("Height", height.toInt().toString()))
    }

    val mdhd = findFirst(videoTrak) { it.type == "mdhd" }
    val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val sampleCount = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (timescale != null && timescale > 0 && duration != null && duration > 0 && sampleCount != null) {
        val durationSeconds = duration.toDouble() / timescale
        val fps = sampleCount / durationSeconds
        fields.add(SummaryField("Frame Rate", "%.2f fps".format(fps)))
    }

    val handlerName = findFirst(videoTrak) { it.type == "hdlr" }?.fields?.find { it.name == "name" }?.value
    if (!handlerName.isNullOrBlank()) fields.add(SummaryField("Handler Name", handlerName))
    mdhd?.fields?.find { it.name == "language" }?.value?.takeIf { it != "und" }?.let {
        fields.add(SummaryField("Language", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Video", fields) else null
}

private fun buildAudioDetail(audioTrak: BoxNode?): SummarySection? {
    if (audioTrak == null) return null
    val stsd = findFirst(audioTrak) { it.type == "stsd" }
    val audioEntry = stsd?.children?.firstOrNull()
    val fields = mutableListOf<SummaryField>()
    audioEntry?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }
    audioEntry?.fields?.find { it.name == "samplerate" }?.let { fields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    audioEntry?.fields?.find { it.name == "channelcount" }?.let { fields.add(SummaryField("Channel(s)", it.value)) }

    val handlerName = findFirst(audioTrak) { it.type == "hdlr" }?.fields?.find { it.name == "name" }?.value
    if (!handlerName.isNullOrBlank()) fields.add(SummaryField("Handler Name", handlerName))
    findFirst(audioTrak) { it.type == "mdhd" }?.fields?.find { it.name == "language" }?.value?.takeIf { it != "und" }?.let {
        fields.add(SummaryField("Language", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Audio", fields) else null
}

private fun movieTimescale(moov: BoxNode?): Long? =
    moov?.children?.find { it.type == "mvhd" }?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()

private fun buildVideoStructureDetail(videoTrak: BoxNode?, movieTimescale: Long?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val avcC = findFirst(videoTrak) { it.type == "avcC" }
    val hvcC = findFirst(videoTrak) { it.type == "hvcC" }
    when {
        avcC != null -> {
            avcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numSps = avcC.fields.find { it.name == "num_sps" }?.value
            val numPps = avcC.fields.find { it.name == "num_pps" }?.value
            if (numSps != null && numPps != null) fields.add(SummaryField("Parameter Sets", "$numSps SPS, $numPps PPS"))
        }
        hvcC != null -> {
            hvcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numVps = hvcC.fields.find { it.name == "num_vps" }?.value
            val numSps = hvcC.fields.find { it.name == "num_sps" }?.value
            val numPps = hvcC.fields.find { it.name == "num_pps" }?.value
            if (numVps != null && numSps != null && numPps != null) {
                fields.add(SummaryField("Parameter Sets", "$numVps VPS, $numSps SPS, $numPps PPS"))
            }
        }
    }

    val elst = findFirst(videoTrak) { it.type == "elst" }
    if (elst != null && elst.fields.isNotEmpty()) {
        val editCount = elst.fields.count { it.name == "segment_duration" }
        val firstMediaTime = elst.fields.find { it.name == "media_time" }?.value
        val firstSegmentDuration = elst.fields.find { it.name == "segment_duration" }?.value?.toDoubleOrNull()
        val label = if (firstMediaTime == "-1" && firstSegmentDuration != null && movieTimescale != null && movieTimescale > 0) {
            val offsetSeconds = firstSegmentDuration / movieTimescale
            "${pluralize(editCount.toLong(), "edit", "edits")} (empty edit, ${formatDuration(offsetSeconds)} offset)"
        } else {
            pluralize(editCount.toLong(), "edit", "edits")
        }
        fields.add(SummaryField("Edit List", label))
    }

    val stss = findFirst(videoTrak) { it.type == "stss" }
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val totalSamples = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (totalSamples != null && totalSamples > 0) {
        if (stss == null) {
            fields.add(SummaryField("Keyframe Interval", "All frames (no separate sync sample table)"))
        } else {
            val keyframeCount = stss.table?.entryCount ?: 0
            if (keyframeCount > 0) {
                val avgInterval = totalSamples.toDouble() / keyframeCount
                fields.add(SummaryField("Keyframe Interval", "$keyframeCount of $totalSamples frames (every ~${"%.0f".format(avgInterval)} frames)"))
            }
        }
    }

    val ctts = findFirst(videoTrak) { it.type == "ctts" }
    fields.add(SummaryField("B-Frames", if (ctts != null && (ctts.table?.entryCount ?: 0) > 0) "Yes" else "No"))

    return if (fields.isNotEmpty()) SummarySection("Video Detail", fields) else null
}

private val MP3_TEXT_FRAME_LABELS = setOf("TIT2", "TPE1", "TALB", "TYER", "TDRC", "TCON", "TRCK", "TCOM", "TENC", "TSSE")
private val ID3V1_FIELD_LABELS = mapOf("title" to "Title", "artist" to "Artist", "album" to "Album", "year" to "Year", "comment" to "Comment")

private fun buildStandaloneAudioSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val fmt = root.children.find { it.type == "fmt " }
    return when {
        fmt != null -> buildWavSummary(root, fmt, fileSizeBytes)
        root.children.any { it.type == "fLaC" } -> buildFlacSummary(root, fileSizeBytes)
        root.children.any { it.type.startsWith("Ogg") } -> buildOggSummary(root, fileSizeBytes)
        root.children.any { it.type == "COMM" } -> buildAiffSummary(root, fileSizeBytes)
        else -> buildMp3Summary(root, fileSizeBytes)
    }
}

private fun buildFlacSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "FLAC"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val streamInfo = root.children.find { it.type == "STREAMINFO" }
    val sampleRate = streamInfo?.fields?.find { it.name == "sample_rate" }?.value?.toDoubleOrNull()
    val totalSamples = streamInfo?.fields?.find { it.name == "total_samples" }?.value?.toDoubleOrNull()
    if (sampleRate != null && sampleRate > 0 && totalSamples != null) {
        val durationSeconds = totalSamples / sampleRate
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        if (durationSeconds > 0) {
            val bitrate = (fileSizeBytes * 8) / durationSeconds
            generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
        }
    }

    val audioFields = mutableListOf<SummaryField>()
    streamInfo?.fields?.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    streamInfo?.fields?.find { it.name == "channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
    streamInfo?.fields?.find { it.name == "bits_per_sample" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}

private fun buildOggSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val vorbisHeader = root.children.find { it.type == "OggVorbisIdentificationHeader" }
    val opusHeader = root.children.find { it.type == "OggOpusIdentificationHeader" }
    val oggPages = root.children.find { it.type == "OggPages" }
    val finalGranulePosition = oggPages?.fields?.find { it.name == "final_granule_position" }?.value?.toDoubleOrNull()

    val generalFields = mutableListOf(
        SummaryField("Format", if (opusHeader != null) "Opus" else "Vorbis"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )
    val audioFields = mutableListOf<SummaryField>()

    if (opusHeader != null) {
        val preSkip = opusHeader.fields.find { it.name == "pre_skip" }?.value?.toDoubleOrNull() ?: 0.0
        val channelCount = opusHeader.fields.find { it.name == "channel_count" }?.value
        audioFields.add(SummaryField("Sampling Rate", "48000 Hz"))
        channelCount?.let { audioFields.add(SummaryField("Channel(s)", it)) }
        // Opus's granule position is always counted in 48kHz units, regardless of the stream's
        // actual/original sample rate (RFC 7845) -- never divide by input_sample_rate here.
        if (finalGranulePosition != null && finalGranulePosition > preSkip) {
            val durationSeconds = (finalGranulePosition - preSkip) / 48000.0
            generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
            if (durationSeconds > 0) {
                val bitrate = (fileSizeBytes * 8) / durationSeconds
                generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
            }
        }
    } else if (vorbisHeader != null) {
        val sampleRateField = vorbisHeader.fields.find { it.name == "sample_rate" }?.value
        val sampleRate = sampleRateField?.toDoubleOrNull()
        val channels = vorbisHeader.fields.find { it.name == "channels" }?.value
        val bitrateNominal = vorbisHeader.fields.find { it.name == "bitrate_nominal" }?.value?.toDoubleOrNull()
        sampleRateField?.let { audioFields.add(SummaryField("Sampling Rate", "$it Hz")) }
        channels?.let { audioFields.add(SummaryField("Channel(s)", it)) }
        if (bitrateNominal != null && bitrateNominal > 0) {
            audioFields.add(SummaryField("Bit Rate", formatBitrate(bitrateNominal)))
        }
        if (sampleRate != null && sampleRate > 0 && finalGranulePosition != null) {
            val durationSeconds = finalGranulePosition / sampleRate
            generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
            if (durationSeconds > 0) {
                val bitrate = (fileSizeBytes * 8) / durationSeconds
                generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
            }
        }
    }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}

private fun buildAiffSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val form = root.children.find { it.type == "FORM" }
    val comm = root.children.find { it.type == "COMM" }
    val formType = form?.fields?.find { it.name == "form_type" }?.value ?: "AIFF"

    val generalFields = mutableListOf(
        SummaryField("Format", formType),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val sampleRateField = comm?.fields?.find { it.name == "sample_rate" }?.value
    val sampleRate = sampleRateField?.toDoubleOrNull()
    val numSampleFrames = comm?.fields?.find { it.name == "num_sample_frames" }?.value?.toDoubleOrNull()
    if (sampleRate != null && sampleRate > 0 && numSampleFrames != null) {
        val durationSeconds = numSampleFrames / sampleRate
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        if (durationSeconds > 0) {
            val bitrate = (fileSizeBytes * 8) / durationSeconds
            generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
        }
    }

    val audioFields = mutableListOf<SummaryField>()
    val compressionType = comm?.fields?.find { it.name == "compression_type" }?.value
    audioFields.add(SummaryField("Format", compressionType ?: "PCM"))
    sampleRateField?.let { audioFields.add(SummaryField("Sampling Rate", "$it Hz")) }
    comm?.fields?.find { it.name == "num_channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
    comm?.fields?.find { it.name == "sample_size" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}

private fun buildWavSummary(root: BoxNode, fmt: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "WAV"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val byteRate = fmt.fields.find { it.name == "byte_rate" }?.value?.toDoubleOrNull()
    val dataChunk = root.children.find { it.type == "data" }
    val dataSize = dataChunk?.let { it.size - it.headerSize }
    if (byteRate != null && byteRate > 0 && dataSize != null) {
        generalFields.add(SummaryField("Duration", formatDuration(dataSize / byteRate)))
        generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(byteRate * 8)))
    }

    val audioFields = mutableListOf<SummaryField>()
    fmt.fields.find { it.name == "audio_format" }?.let { audioFields.add(SummaryField("Format", it.value)) }
    fmt.fields.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    fmt.fields.find { it.name == "num_channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
    fmt.fields.find { it.name == "bits_per_sample" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }

    return listOf(SummarySection("General", generalFields), SummarySection("Audio", audioFields))
}

private fun buildMp3Summary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "MP3"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val id3v2 = root.children.find { it.type == "ID3v2" }
    if (id3v2 != null) {
        id3v2.children.filter { it.type in MP3_TEXT_FRAME_LABELS }.forEach { frame ->
            frame.fields.firstOrNull()?.let { field -> generalFields.add(SummaryField(field.name, field.value)) }
        }
    } else {
        val id3v1 = root.children.find { it.type == "ID3v1" }
        id3v1?.fields?.forEach { field ->
            val label = ID3V1_FIELD_LABELS[field.name] ?: return@forEach
            if (field.value.isNotBlank()) generalFields.add(SummaryField(label, field.value))
        }
    }

    val frames = root.children.find { it.type == "AudioFrames" }
    val audioFields = mutableListOf<SummaryField>()
    frames?.fields?.find { it.name == "mpeg_version" }?.let { audioFields.add(SummaryField("Format", "MPEG Audio (${it.value})")) }
    frames?.fields?.find { it.name == "bitrate" }?.let { audioFields.add(SummaryField("Bit Rate", it.value)) }
    frames?.fields?.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", it.value)) }
    frames?.fields?.find { it.name == "channel_mode" }?.let { audioFields.add(SummaryField("Channel Mode", it.value)) }

    val bitrateKbps = frames?.fields?.find { it.name == "bitrate" }?.value?.removeSuffix(" kbps")?.toDoubleOrNull()
    if (bitrateKbps != null && bitrateKbps > 0) {
        val durationSeconds = (fileSizeBytes * 8) / (bitrateKbps * 1000)
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrateKbps * 1000)))
    }

    val sections = mutableListOf(SummarySection("General", generalFields))
    if (audioFields.isNotEmpty()) sections.add(SummarySection("Audio", audioFields))
    return sections
}

private fun formatDuration(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong()
    val h = totalMs / 3_600_000
    val m = (totalMs % 3_600_000) / 60_000
    val s = (totalMs % 60_000) / 1000
    val ms = totalMs % 1000
    return "%d:%02d:%02d.%03d".format(h, m, s, ms)
}

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
    bitsPerSecond >= 1_000 -> "%.1f Kbps".format(bitsPerSecond / 1_000)
    else -> "%.0f bps".format(bitsPerSecond)
}

fun mergeStreamCodecDetailsIntoSections(sections: List<SummarySection>, videoFields: List<SummaryField>, audioFields: List<SummaryField>): List<SummarySection> {
    return sections.map { section ->
        when (section.title) {
            "Video" -> {
                val existingNames = section.fields.map { it.label }.toSet()
                val uniqueNewFields = videoFields.distinctBy { it.label }.filter { it.label !in existingNames }
                section.copy(fields = section.fields + uniqueNewFields)
            }
            "Audio" -> {
                val existingNames = section.fields.map { it.label }.toSet()
                val uniqueNewFields = audioFields.distinctBy { it.label }.filter { it.label !in existingNames }
                section.copy(fields = section.fields + uniqueNewFields)
            }
            else -> section
        }
    }
}

fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    if (summary.category == MediaCategory.IMAGE) return summary
    return summary.copy(sections = mergeStreamCodecDetailsIntoSections(summary.sections, videoFields, audioFields))
}

private fun findAppleMetaField(root: BoxNode, partialKey: String): String? {
    val appleMeta = findFirst(root) { it.type == "Apple QuickTime Metadata" }
    return appleMeta?.fields?.find { it.name.contains(partialKey, ignoreCase = true) }?.value
}

private fun findTimedMetadataField(root: BoxNode, partialKey: String): String? {
    val timedMeta = findFirst(root) { it.type == "Timed Metadata" }
    return timedMeta?.fields?.find { it.name.contains(partialKey, ignoreCase = true) }?.value
        ?: timedMeta?.children?.find { it.type.contains(partialKey, ignoreCase = true) }?.fields?.find { it.name == "Value" }?.value
}

private fun buildAppleDeviceSection(root: BoxNode): SummarySection? {
    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val make = ifd0?.fields?.find { it.name == "Make" }?.value ?: findAppleMetaField(root, "make")
    val isApple = make?.contains("Apple", ignoreCase = true) == true || findAppleMetaField(root, "make") != null

    if (!isApple) return null

    val fields = mutableListOf<SummaryField>()
    fields.add(SummaryField("Make", make ?: "Apple"))
    (ifd0?.fields?.find { it.name == "Model" }?.value ?: findAppleMetaField(root, "model"))?.let {
        fields.add(SummaryField("Model", it))
    }
    (ifd0?.fields?.find { it.name == "Software" }?.value ?: findAppleMetaField(root, "software"))?.let {
        fields.add(SummaryField("Software", it))
    }
    (ifd0?.fields?.find { it.name == "DateTime" }?.value ?: findAppleMetaField(root, "creationdate"))?.let {
        fields.add(SummaryField("Creation Date", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Apple Device", fields) else null
}

private fun buildAppleCameraAndCaptureSection(root: BoxNode): SummarySection? {
    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val make = ifd0?.fields?.find { it.name == "Make" }?.value ?: findAppleMetaField(root, "make")
    val isApple = make?.contains("Apple", ignoreCase = true) == true || findAppleMetaField(root, "camera") != null
    val makerNote = findFirst(root) { it.type == "MakerNote" }
    val hasAppleMakerNote = makerNote?.summary?.contains("Apple", ignoreCase = true) == true ||
        makerNote?.fields?.any { it.name.startsWith("Apple Tag") || it.name in listOf("CameraType", "ImageCaptureType", "FocusPosition", "HDRGain", "HDRHeadroom", "AFConfidence", "AFMeasuredDepth") } == true

    if (!isApple && !hasAppleMakerNote) return null

    val exif = findFirst(root) { it.type == "Exif" }
    val fields = mutableListOf<SummaryField>()

    (findAppleMetaField(root, "camera.lens_model") ?: exif?.fields?.find { it.name == "LensModel" }?.value)?.let {
        fields.add(SummaryField("Lens Model", it))
    }
    (findAppleMetaField(root, "camera.focal_length") ?: exif?.fields?.find { it.name == "FocalLength" }?.value)?.let {
        fields.add(SummaryField("Focal Length", it))
    }
    (findAppleMetaField(root, "camera.focal_length.35mm_equivalent") ?: exif?.fields?.find { it.name == "FocalLengthIn35mmFilm" }?.value)?.let {
        fields.add(SummaryField("Focal Length in 35mm Format", it))
    }
    (findAppleMetaField(root, "camera.aperture_f_number") ?: exif?.fields?.find { it.name == "FNumber" }?.value)?.let {
        fields.add(SummaryField("Aperture", it))
    }
    makerNote?.fields?.find { it.name == "CameraType" }?.let {
        fields.add(SummaryField("Camera Type", it.value))
    }
    makerNote?.fields?.find { it.name == "ImageCaptureType" }?.let {
        fields.add(SummaryField("Image Capture Type", it.value))
    }
    makerNote?.fields?.find { it.name == "FocusPosition" }?.let {
        fields.add(SummaryField("Focus Position", it.value))
    }
    (findAppleMetaField(root, "camera.exposure_time") ?: exif?.fields?.find { it.name == "ExposureTime" }?.value)?.let {
        fields.add(SummaryField("Exposure Time", it))
    }
    (findAppleMetaField(root, "camera.iso") ?: exif?.fields?.find { it.name == "ISOSpeedRatings" }?.value)?.let {
        fields.add(SummaryField("ISO", it))
    }
    (findAppleMetaField(root, "camera.flash") ?: exif?.fields?.find { it.name == "Flash" }?.value)?.let {
        fields.add(SummaryField("Flash", it))
    }
    (findAppleMetaField(root, "camera.white_balance") ?: exif?.fields?.find { it.name == "WhiteBalance" }?.value)?.let {
        fields.add(SummaryField("White Balance", it))
    }
    (findAppleMetaField(root, "camera.focus_distance") ?: makerNote?.fields?.find { it.name == "FocusDistanceRange" }?.value)?.let {
        fields.add(SummaryField("Focus Distance", it))
    }
    makerNote?.fields?.find { it.name == "AEStable" }?.let {
        fields.add(SummaryField("AE Stable", it.value))
    }
    makerNote?.fields?.find { it.name == "AFStable" }?.let {
        fields.add(SummaryField("AF Stable", it.value))
    }
    makerNote?.fields?.find { it.name == "AccelerationVector" }?.let {
        fields.add(SummaryField("Acceleration Vector", it.value))
    }

    return if (fields.isNotEmpty()) SummarySection("Camera & Capture", fields) else null
}

private fun buildAppleComputationalPhotographySection(root: BoxNode): SummarySection? {
    val makerNote = findFirst(root) { it.type == "MakerNote" }
    val hasAppleComp = makerNote?.fields?.any { it.name in listOf("HDRImageType", "HDRGain", "HDRHeadroom", "MeteorHeadroom", "SmartStyle", "SmartStyleTone", "SmartStyleColor", "SmartStyleIntensity", "SmartStyleCast", "SemanticStyle", "PhotographicStyle") } == true ||
        findAppleMetaField(root, "smartstyle") != null

    if (!hasAppleComp) return null

    val fields = mutableListOf<SummaryField>()

    makerNote?.fields?.find { it.name == "HDRImageType" }?.let {
        fields.add(SummaryField("HDR Image Type", it.value))
    }
    makerNote?.fields?.find { it.name == "HDRGain" }?.let {
        fields.add(SummaryField("HDR Gain", it.value))
    }
    makerNote?.fields?.find { it.name == "HDRHeadroom" }?.let {
        fields.add(SummaryField("HDR Headroom", it.value))
    }
    makerNote?.fields?.find { it.name == "MeteorHeadroom" }?.let {
        fields.add(SummaryField("Meteor Headroom", it.value))
    }
    (makerNote?.fields?.find { it.name == "SmartStyle" }?.value ?: findAppleMetaField(root, "smartstyle.rendering-version"))?.let {
        fields.add(SummaryField("Smart Style", it))
    }
    (makerNote?.fields?.find { it.name == "SmartStyleTone" }?.value ?: findAppleMetaField(root, "smartstyle.tone"))?.let {
        fields.add(SummaryField("Smart Style Tone", it))
    }
    (makerNote?.fields?.find { it.name == "SmartStyleColor" }?.value ?: findAppleMetaField(root, "smartstyle.color"))?.let {
        fields.add(SummaryField("Smart Style Color", it))
    }
    (makerNote?.fields?.find { it.name == "SmartStyleIntensity" }?.value ?: findAppleMetaField(root, "smartstyle.intensity"))?.let {
        fields.add(SummaryField("Smart Style Intensity", it))
    }
    (makerNote?.fields?.find { it.name == "SmartStyleCast" }?.value ?: findAppleMetaField(root, "smartstyle.cast"))?.let {
        fields.add(SummaryField("Smart Style Cast", it))
    }
    findAppleMetaField(root, "smartstyle.bypass")?.let {
        fields.add(SummaryField("Smart Style Bypass", it))
    }
    makerNote?.fields?.find { it.name == "SemanticStyle" }?.let {
        fields.add(SummaryField("Semantic Style", it.value))
    }
    makerNote?.fields?.find { it.name == "PhotographicStyle" }?.let {
        fields.add(SummaryField("Photographic Style", it.value))
    }

    return if (fields.isNotEmpty()) SummarySection("Computational Photography", fields) else null
}

private fun buildAppleDepthAndPortraitSection(root: BoxNode): SummarySection? {
    val makerNote = findFirst(root) { it.type == "MakerNote" }
    val auxNode = findFirst(root) { it.type == "Auxiliary Images" }
    val hasAppleDepth = makerNote?.fields?.any { it.name in listOf("AFMeasuredDepth", "AFConfidence", "AFPerformance") } == true ||
        (auxNode != null && auxNode.children.any { it.fields.any { f -> f.name == "Role" && f.value in listOf("DEPTH", "DISPARITY", "PORTRAIT_EFFECTS", "SKY", "PERSON", "SKIN", "HAIR", "TEETH", "GLASSES") } })

    if (!hasAppleDepth) return null

    val fields = mutableListOf<SummaryField>()

    makerNote?.fields?.find { it.name == "AFMeasuredDepth" }?.let {
        fields.add(SummaryField("AF Measured Depth", it.value))
    }
    makerNote?.fields?.find { it.name == "AFConfidence" }?.let {
        fields.add(SummaryField("AF Confidence", it.value))
    }
    makerNote?.fields?.find { it.name == "AFPerformance" }?.let {
        fields.add(SummaryField("AF Performance", it.value))
    }

    if (auxNode != null && auxNode.children.isNotEmpty()) {
        val roles = auxNode.children.mapNotNull { it.fields.find { f -> f.name == "Role Description" }?.value ?: it.type }
        fields.add(SummaryField("Auxiliary Roles", roles.joinToString(", ")))
    }

    return if (fields.isNotEmpty()) SummarySection("Depth & Portrait", fields) else null
}

private fun buildAppleLivePhotoSection(root: BoxNode): SummarySection? {
    val makerNote = findFirst(root) { it.type == "MakerNote" }
    val hasLivePhoto = makerNote?.fields?.any { it.name in listOf("ContentIdentifier", "LivePhotoVideoIndex") } == true ||
        findAppleMetaField(root, "content.identifier") != null ||
        findAppleMetaField(root, "live-photo") != null

    if (!hasLivePhoto) return null

    val fields = mutableListOf<SummaryField>()

    val contentId = makerNote?.fields?.find { it.name == "ContentIdentifier" }?.value
        ?: findAppleMetaField(root, "content.identifier")
    contentId?.let { fields.add(SummaryField("Content Identifier", it)) }

    val stillImageTime = findAppleMetaField(root, "live-photo.still-image-time")
        ?: findTimedMetadataField(root, "still-image-time")
    stillImageTime?.let { fields.add(SummaryField("Still Image Time", it)) }

    findAppleMetaField(root, "live-photo.vitality-score")?.let {
        fields.add(SummaryField("Vitality Score", it))
    }
    findAppleMetaField(root, "live-photo.vitality-scoring-version")?.let {
        fields.add(SummaryField("Vitality Scoring Version", it))
    }
    makerNote?.fields?.find { it.name == "LivePhotoVideoIndex" }?.let {
        fields.add(SummaryField("Video Index", it.value))
    }
    findAppleMetaField(root, "full-frame-rate-playback-intent")?.let {
        fields.add(SummaryField("Full Frame Rate Playback Intent", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Live Photo", fields) else null
}

private fun buildAppleVideoMetadataSection(root: BoxNode, traks: List<BoxNode>): SummarySection? {
    val fields = mutableListOf<SummaryField>()

    val dolbyVisionNode = findFirst(root) { it.type == "dvcC" || it.type == "dvvC" }
    dolbyVisionNode?.let {
        val summary = it.summary ?: "Dolby Vision Profile ${it.fields.find { f -> f.name == "dv_profile" }?.value}"
        fields.add(SummaryField("Dolby Vision", summary))
    }

    val colrNode = findFirst(root) { it.type == "colr" }
    colrNode?.let {
        val primaries = it.fields.find { f -> f.name == "colour_primaries" }?.value
        val transfer = it.fields.find { f -> f.name == "transfer_characteristics" }?.value
        val matrix = it.fields.find { f -> f.name == "matrix_coefficients" }?.value
        if (primaries != null || transfer != null || matrix != null) {
            fields.add(SummaryField("Color Primaries / Transfer / Matrix", "${primaries ?: "?"} / ${transfer ?: "?"} / ${matrix ?: "?"}"))
        }
    }

    val timedMetaCount = traks.count { trak ->
        findFirst(trak) { it.type == "Timed Metadata" } != null ||
            findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value in listOf("mebx", "mdta")
    }
    if (timedMetaCount > 0) {
        fields.add(SummaryField("Timed Metadata Tracks", timedMetaCount.toString()))
    }

    val videoOrientation = findAppleMetaField(root, "video-orientation")
        ?: findTimedMetadataField(root, "video-orientation")
    videoOrientation?.let {
        fields.add(SummaryField("Video Orientation", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Video Metadata", fields) else null
}

private fun buildAviVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "AVI"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )
    val avih = findFirst(root) { it.type == "avih" }
    val fps = avih?.fields?.find { it.name == "fps" }?.value?.toDoubleOrNull()
    val totalFrames = avih?.fields?.find { it.name == "total_frames" }?.value?.toDoubleOrNull()
    if (fps != null && fps > 0 && totalFrames != null) {
        val durationSeconds = totalFrames / fps
        generalFields.add(SummaryField("Duration", formatDuration(durationSeconds)))
        if (durationSeconds > 0) {
            val bitrate = (fileSizeBytes * 8) / durationSeconds
            generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
        }
    }

    val sections = mutableListOf(SummarySection("General", generalFields))

    val strlNodes = mutableListOf<BoxNode>()
    fun collectStrl(node: BoxNode) {
        if (node.type == "LIST (strl)") strlNodes.add(node)
        node.children.forEach { collectStrl(it) }
    }
    collectStrl(root)

    var videoTrackCount = 0
    var audioTrackCount = 0
    val trackFields = mutableListOf<SummaryField>()

    for (strl in strlNodes) {
        val strh = strl.children.find { it.type == "strh" }
        val streamType = strh?.fields?.find { it.name == "stream_type" }?.value
        if (streamType == "vids") videoTrackCount++
        else if (streamType == "auds") audioTrackCount++
    }
    if (videoTrackCount > 0) trackFields.add(SummaryField("Video Tracks", videoTrackCount.toString()))
    if (audioTrackCount > 0) trackFields.add(SummaryField("Audio Tracks", audioTrackCount.toString()))
    if (trackFields.isNotEmpty()) sections.add(SummarySection("Track List", trackFields))

    // Video details
    val videoStrl = strlNodes.find {
        it.children.find { c -> c.type == "strh" }?.fields?.find { f -> f.name == "stream_type" }?.value == "vids"
    }
    val videoStrh = videoStrl?.children?.find { it.type == "strh" }
    val videoStrf = videoStrl?.children?.find { it.type == "strf" }
    val videoFields = mutableListOf<SummaryField>()

    val videoCodec = videoStrf?.fields?.find { it.name == "compression" }?.value
        ?: videoStrh?.fields?.find { it.name == "handler_codec" }?.value
    videoCodec?.let { videoFields.add(SummaryField("Format", it)) }

    val width = videoStrf?.fields?.find { it.name == "width" }?.value
        ?: avih?.fields?.find { it.name == "width" }?.value
    val height = videoStrf?.fields?.find { it.name == "height" }?.value
        ?: avih?.fields?.find { it.name == "height" }?.value
    width?.let { videoFields.add(SummaryField("Width", it)) }
    height?.let { videoFields.add(SummaryField("Height", it)) }
    fps?.let { videoFields.add(SummaryField("Frame Rate", "${String.format(java.util.Locale.US, "%.2f", it)} fps")) }
    totalFrames?.let { videoFields.add(SummaryField("Total Frames", it.toLong().toString())) }
    if (videoFields.isNotEmpty()) sections.add(SummarySection("Video", videoFields))

    // Audio details
    val audioStrl = strlNodes.find {
        it.children.find { c -> c.type == "strh" }?.fields?.find { f -> f.name == "stream_type" }?.value == "auds"
    }
    val audioStrf = audioStrl?.children?.find { it.type == "strf" }
    if (audioStrf != null) {
        val audioFields = mutableListOf<SummaryField>()
        audioStrf.fields.find { it.name == "audio_format" }?.let { audioFields.add(SummaryField("Format", it.value)) }
        audioStrf.fields.find { it.name == "channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
        audioStrf.fields.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
        audioStrf.fields.find { it.name == "bits_per_sample" }?.takeIf { it.value != "0" }?.let { audioFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }
        if (audioFields.isNotEmpty()) sections.add(SummarySection("Audio", audioFields))
    }

    return sections
}

private fun buildFlvVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val scriptTag = findFirst(root) { it.type == "ScriptTag (onMetaData)" }
    val generalFields = mutableListOf(
        SummaryField("Format", "Flash Video (FLV)"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )
    val durationSec = scriptTag?.fields?.find { it.name == "duration" }?.value?.toDoubleOrNull()
    if (durationSec != null && durationSec > 0) {
        generalFields.add(SummaryField("Duration", formatDuration(durationSec)))
        val bitrate = (fileSizeBytes * 8) / durationSec
        generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }
    val sections = mutableListOf(SummarySection("General", generalFields))

    val videoTag = findFirst(root) { it.type == "VideoTag" }
    val audioTag = findFirst(root) { it.type == "AudioTag" }

    val trackFields = mutableListOf<SummaryField>()
    if (videoTag != null || scriptTag?.fields?.any { it.name == "videocodecid" } == true) {
        trackFields.add(SummaryField("Video Tracks", "1"))
    }
    if (audioTag != null || scriptTag?.fields?.any { it.name == "audiocodecid" } == true) {
        trackFields.add(SummaryField("Audio Tracks", "1"))
    }
    if (trackFields.isNotEmpty()) sections.add(SummarySection("Track List", trackFields))

    // Video section
    val videoFields = mutableListOf<SummaryField>()
    val rawVideoCodec = scriptTag?.fields?.find { it.name == "videocodecid" }?.value
    val scriptVideoCodec = when (rawVideoCodec?.toDoubleOrNull()?.toInt()) {
        7 -> "AVC/H.264"
        2 -> "Sorenson Spark"
        4 -> "On2 VP6"
        5 -> "On2 VP6 with alpha"
        else -> rawVideoCodec
    }
    val videoCodec = videoTag?.fields?.find { it.name == "codec" }?.value ?: scriptVideoCodec
    videoCodec?.let { videoFields.add(SummaryField("Format", it)) }
    scriptTag?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()?.let {
        videoFields.add(SummaryField("Width", it.toInt().toString()))
    }
    scriptTag?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()?.let {
        videoFields.add(SummaryField("Height", it.toInt().toString()))
    }
    scriptTag?.fields?.find { it.name == "framerate" }?.value?.let {
        videoFields.add(SummaryField("Frame Rate", "$it fps"))
    }
    if (videoFields.isNotEmpty()) sections.add(SummarySection("Video", videoFields))

    // Audio section
    val audioFields = mutableListOf<SummaryField>()
    val rawAudioCodec = scriptTag?.fields?.find { it.name == "audiocodecid" }?.value
    val scriptAudioCodec = when (rawAudioCodec?.toDoubleOrNull()?.toInt()) {
        10 -> "AAC"
        2 -> "MP3"
        else -> rawAudioCodec
    }
    val audioCodec = audioTag?.fields?.find { it.name == "format" }?.value ?: scriptAudioCodec
    audioCodec?.let { audioFields.add(SummaryField("Format", it)) }
    val sampleRate = audioTag?.fields?.find { it.name == "sample_rate" }?.value
        ?: scriptTag?.fields?.find { it.name == "audiosamplerate" }?.value
    sampleRate?.let { audioFields.add(SummaryField("Sampling Rate", if (it.endsWith("Hz")) it else "$it Hz")) }
    val channels = audioTag?.fields?.find { it.name == "channels" }?.value
        ?: scriptTag?.fields?.find { it.name == "stereo" }?.value?.let { if (it == "true") "2 channels (Stereo)" else "1 channel (Mono)" }
    channels?.let { audioFields.add(SummaryField("Channel(s)", it)) }
    if (audioFields.isNotEmpty()) sections.add(SummarySection("Audio", audioFields))

    return sections
}

private fun buildAsfVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "Windows Media (ASF/WMV)"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )
    val fileProps = findFirst(root) { it.type == "File Properties Object" }
    val durationSec = fileProps?.fields?.find { it.name == "play_duration_sec" }?.value?.removeSuffix("s")?.toDoubleOrNull()
    if (durationSec != null && durationSec > 0) {
        generalFields.add(SummaryField("Duration", formatDuration(durationSec)))
        val bitrate = (fileSizeBytes * 8) / durationSec
        generalFields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }

    val contentDesc = findFirst(root) { it.type == "Content Description Object" }
    contentDesc?.fields?.find { it.name == "title" }?.let { generalFields.add(SummaryField("Title", it.value)) }
    contentDesc?.fields?.find { it.name == "author" }?.let { generalFields.add(SummaryField("Author", it.value)) }

    val sections = mutableListOf(SummarySection("General", generalFields))

    val streamProps = mutableListOf<BoxNode>()
    fun collectStreamProps(node: BoxNode) {
        if (node.type == "Stream Properties Object") streamProps.add(node)
        node.children.forEach { collectStreamProps(it) }
    }
    collectStreamProps(root)

    val videoStreams = streamProps.filter { it.fields.any { f -> f.name == "stream_type" && f.value == "Video" } }
    val audioStreams = streamProps.filter { it.fields.any { f -> f.name == "stream_type" && f.value == "Audio" } }

    val trackFields = mutableListOf<SummaryField>()
    if (videoStreams.isNotEmpty()) trackFields.add(SummaryField("Video Tracks", videoStreams.size.toString()))
    if (audioStreams.isNotEmpty()) trackFields.add(SummaryField("Audio Tracks", audioStreams.size.toString()))
    if (trackFields.isNotEmpty()) sections.add(SummarySection("Track List", trackFields))

    // Video details
    val firstVideo = videoStreams.firstOrNull()
    if (firstVideo != null) {
        val videoFields = mutableListOf<SummaryField>()
        val compression = firstVideo.fields.find { it.name == "compression" }?.value ?: "WMV"
        videoFields.add(SummaryField("Format", compression))
        firstVideo.fields.find { it.name == "width" }?.let { videoFields.add(SummaryField("Width", it.value)) }
        firstVideo.fields.find { it.name == "height" }?.let { videoFields.add(SummaryField("Height", it.value)) }
        firstVideo.fields.find { it.name == "bit_count" }?.let { videoFields.add(SummaryField("Bit Depth", "${it.value}-bit")) }
        sections.add(SummarySection("Video", videoFields))
    }

    // Audio details
    val firstAudio = audioStreams.firstOrNull()
    if (firstAudio != null) {
        val audioFields = mutableListOf<SummaryField>()
        firstAudio.fields.find { it.name == "format" }?.let { audioFields.add(SummaryField("Format", it.value)) }
        firstAudio.fields.find { it.name == "channels" }?.let { audioFields.add(SummaryField("Channel(s)", it.value)) }
        firstAudio.fields.find { it.name == "sample_rate" }?.let { audioFields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
        sections.add(SummarySection("Audio", audioFields))
    }

    return sections
}

private fun buildAacSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val generalFields = mutableListOf(
        SummaryField("Format", "AAC (ADTS)"),
        SummaryField("File Size", formatFileSize(fileSizeBytes)),
    )

    val id3v2 = root.children.find { it.type == "ID3v2" }
    id3v2?.children?.filter { it.type in MP3_TEXT_FRAME_LABELS }?.forEach { frame ->
        frame.fields.firstOrNull()?.let { field -> generalFields.add(SummaryField(field.name, field.value)) }
    }

    val firstFrame = findFirst(root) { it.type.startsWith("ADTS Frame") }
    val audioFields = mutableListOf<SummaryField>()

    val profile = firstFrame?.fields?.find { it.name == "profile" }?.value ?: "LC"
    val sampleRate = firstFrame?.fields?.find { it.name == "sample_rate" }?.value ?: "44100Hz"
    val channels = firstFrame?.fields?.find { it.name == "channels" }?.value ?: "2 channels (Stereo)"

    audioFields.add(SummaryField("Format", "AAC ($profile)"))
    audioFields.add(SummaryField("Sampling Rate", sampleRate))
    audioFields.add(SummaryField("Channel(s)", channels))

    val sections = mutableListOf(SummarySection("General", generalFields))
    if (audioFields.isNotEmpty()) sections.add(SummarySection("Audio", audioFields))
    return sections
}
