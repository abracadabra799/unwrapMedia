package com.multiviewer.parser

import java.io.File
import kotlin.math.abs

private val CODEC_DISPLAY_NAMES = mapOf(
    "avc1" to "AVC",
    "hvc1" to "HEVC",
    "av01" to "AV1",
    "mp4a" to "AAC",
)

fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file.length())
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
        val video = findEmbeddedVideo(root) ?: return null
        ByteReader.open(file).use { reader ->
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
    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val hasVideoOrAudioTrack = moov.children.filter { it.type == "trak" }.any { trak ->
        val handlerType = findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
        handlerType == "vide" || handlerType == "soun"
    }
    return if (hasVideoOrAudioTrack) MediaCategory.VIDEO else MediaCategory.IMAGE
}

fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
    if (predicate(node)) return node
    for (child in node.children) {
        val found = findFirst(child, predicate)
        if (found != null) return found
    }
    return null
}

private fun findPrimaryItemProperty(root: BoxNode, propertyType: String): BoxNode? {
    val meta = root.children.find { it.type == "meta" } ?: return null
    val pitm = meta.children.find { it.type == "pitm" } ?: return null
    val primaryItemId = pitm.fields.find { it.name == "primary_item_ID" }?.value ?: return null
    val ipma = findFirst(meta) { it.type == "ipma" } ?: return null
    val itemEntry = ipma.children.find { it.type == "item_$primaryItemId" } ?: return null
    val propertyIndices = itemEntry.fields
        .filter { it.name == "property_index" }
        .mapNotNull { it.value.toIntOrNull() }
    val ipco = findFirst(meta) { it.type == "ipco" } ?: return null
    for (index in propertyIndices) {
        val property = ipco.children.getOrNull(index - 1) ?: continue
        if (property.type == propertyType) return property
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

    return sections
}

private fun buildImageGeneral(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val isGif = root.children.any { it.type == "LogicalScreenDescriptor" }

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
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
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
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
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

private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }

    return sections
}

private fun trakHandlerType(trak: BoxNode): String? {
    val hdlr = findFirst(trak) { it.type == "hdlr" }
    return hdlr?.fields?.find { it.name == "handler_type" }?.value
}

private fun buildVideoGeneral(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?): SummarySection {
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

    return SummarySection("General", fields)
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
    return if (fields.isNotEmpty()) SummarySection("Audio", fields) else null
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
    bitsPerSecond >= 1_000 -> "%.1f Kbps".format(bitsPerSecond / 1_000)
    else -> "%.0f bps".format(bitsPerSecond)
}
