package com.multiviewer.parser

import java.io.File

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
    return MediaSummary(category, sections, motionPhotoVideoSections)
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
    sections.add(buildImageBasicInfo(root, file))

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

private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    return SummarySection("Basic Info", fields)
}

private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoBasicInfo(root, fileSizeBytes, moov, videoTrak))
    sections.add(buildTrackList(traks))
    buildVideoTrackDetail(videoTrak)?.let { sections.add(it) }
    buildAudioTrackDetail(audioTrak)?.let { sections.add(it) }

    return sections
}

private fun trakHandlerType(trak: BoxNode): String? {
    val hdlr = findFirst(trak) { it.type == "hdlr" }
    return hdlr?.fields?.find { it.name == "handler_type" }?.value
}

private fun buildVideoBasicInfo(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?, videoTrak: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()

    val mvhd = moov?.children?.find { it.type == "mvhd" }
    val timescale = mvhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mvhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val durationSeconds = if (timescale != null && timescale > 0 && duration != null) duration.toDouble() / timescale else null
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }

    val tkhd = videoTrak?.children?.find { it.type == "tkhd" }
    val width = tkhd?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()
    val height = tkhd?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()
    if (width != null && height != null) {
        fields.add(SummaryField("Resolution", "${width.toInt()}x${height.toInt()}"))
    }

    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Container Brand", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Average Bitrate", formatBitrate(bitrate)))
    }

    return SummarySection("Basic Info", fields)
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

private fun buildVideoTrackDetail(videoTrak: BoxNode?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val stsd = findFirst(videoTrak) { it.type == "stsd" }
    stsd?.children?.firstOrNull()?.type?.let { fields.add(SummaryField("Codec", it)) }

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

    return if (fields.isNotEmpty()) SummarySection("Video Track Detail", fields) else null
}

private fun buildAudioTrackDetail(audioTrak: BoxNode?): SummarySection? {
    if (audioTrak == null) return null
    val stsd = findFirst(audioTrak) { it.type == "stsd" }
    val audioEntry = stsd?.children?.firstOrNull()
    val fields = mutableListOf<SummaryField>()
    audioEntry?.type?.let { fields.add(SummaryField("Codec", it)) }
    audioEntry?.fields?.find { it.name == "samplerate" }?.let { fields.add(SummaryField("Sample Rate", "${it.value} Hz")) }
    audioEntry?.fields?.find { it.name == "channelcount" }?.let { fields.add(SummaryField("Channels", it.value)) }
    return if (fields.isNotEmpty()) SummarySection("Audio Track Detail", fields) else null
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
