package com.multiviewer.parser


data class QuickTimeMetadataKey(
    val index: Int,
    val namespace: String,
    val key: String,
    val offset: Long,
    val length: Long,
)

val KNOWN_QUICKTIME_KEYS = mapOf(
    "com.apple.quicktime.make" to "Make",
    "com.apple.quicktime.model" to "Model",
    "com.apple.quicktime.software" to "Software",
    "com.apple.quicktime.creationdate" to "Creation Date",
    "com.apple.quicktime.location.ISO6709" to "Location (ISO 6709)",
    "com.apple.quicktime.location.accuracy.horizontal" to "Location Horizontal Accuracy",
    "com.apple.quicktime.location.name" to "Location Name",
    "com.apple.quicktime.location.body" to "Location Body",
    "com.apple.quicktime.location.note" to "Location Note",
    "com.apple.quicktime.camera.lens_model" to "Lens Model",
    "com.apple.quicktime.camera.focal_length" to "Focal Length",
    "com.apple.quicktime.camera.focal_length.35mm_equivalent" to "Focal Length In 35mm Format",
    "com.apple.quicktime.camera.aperture_f_number" to "Aperture",
    "com.apple.quicktime.camera.exposure_time" to "Exposure Time",
    "com.apple.quicktime.camera.iso" to "ISO",
    "com.apple.quicktime.camera.flash" to "Flash",
    "com.apple.quicktime.camera.white_balance" to "White Balance",
    "com.apple.quicktime.camera.focus_distance" to "Focus Distance",
    "com.apple.quicktime.content.identifier" to "Content Identifier",
    "com.apple.quicktime.live-photo.vitality-score" to "Live Photo Vitality Score",
    "com.apple.quicktime.live-photo.vitality-scoring-version" to "Live Photo Vitality Scoring Version",
    "com.apple.quicktime.live-photo.still-image-time" to "Live Photo Still Image Time",
    "com.apple.quicktime.live-photo.still-image-transform" to "Live Photo Still Image Transform",
    "com.apple.quicktime.live-photo.still-image-dimensions" to "Live Photo Still Image Dimensions",
    "com.apple.quicktime.smartstyle.rendering-version" to "Smart Style Rendering Version",
    "com.apple.quicktime.smartstyle.tone" to "Smart Style Tone",
    "com.apple.quicktime.smartstyle.color" to "Smart Style Color",
    "com.apple.quicktime.smartstyle.intensity" to "Smart Style Intensity",
    "com.apple.quicktime.smartstyle.bypass" to "Smart Style Bypass",
    "com.apple.quicktime.smartstyle.cast" to "Smart Style Cast",
    "com.apple.quicktime.video-orientation" to "Video Orientation",
    "com.apple.quicktime.full-frame-rate-playback-intent" to "Full Frame Rate Playback Intent",
    "com.apple.quicktime.cinematic-video.params" to "Cinematic Video Parameters",
    "com.apple.quicktime.spatial-audio" to "Spatial Audio",
    "com.apple.quicktime.display-color-space" to "Display Color Space",
    "com.apple.quicktime.artwork" to "Artwork",
    "com.apple.quicktime.author" to "Author",
    "com.apple.quicktime.copyright" to "Copyright",
    "com.apple.quicktime.description" to "Description",
    "com.apple.quicktime.title" to "Title",
    "\u00A9nam" to "Title",
    "\u00A9ART" to "Artist",
    "\u00A9alb" to "Album",
    "\u00A9day" to "Year",
    "\u00A9cmt" to "Comment",
    "\u00A9gen" to "Genre",
    "\u00A9wrt" to "Composer",
    "\u00A9too" to "Encoder Software",
    "covr" to "Cover Art",
)

fun decodeDataValue(reader: ByteReader, typeCode: Int, dataOffset: Long, dataLen: Int): String =
    readQuickTimeValue(reader, typeCode, dataOffset, dataLen.toLong()).value

fun enrichQuickTimeMetadata(reader: ByteReader, children: List<BoxNode>): List<BoxNode> {
    val keysNode = children.find { it.type == "keys" }
    val ilstNode = children.find { it.type == "ilst" }

    // Use already validated key fields; never re-read unbounded raw key data during enrichment.
    val keysMap = mutableMapOf<Int, QuickTimeMetadataKey>()
    keysNode?.fields?.forEach { field ->
        val index = if (field.name.startsWith("key[") && field.name.endsWith("]"))
            field.name.removePrefix("key[").removeSuffix("]").toIntOrNull() else null
        if (index != null && field.interpretation == ValueInterpretation.PARSED && field.value.startsWith("mdta:")) {
            keysMap[index] = QuickTimeMetadataKey(index, "mdta", field.value.substring(5), field.offset, field.length)
        }
    }

    val extractedFields = mutableListOf<BoxField>()
    val resultChildren = children.toMutableList()

    if (ilstNode != null) {
        val enrichedIlstChildren = ilstNode.children.map { itemNode ->
            // Try to resolve key index (e.g. 0x00000001 or FourCC)
            val itemTypeChars = itemNode.type
            val indexVal = if (itemTypeChars.length == 4) {
                ((itemTypeChars[0].code and 0xFF) shl 24) or
                    ((itemTypeChars[1].code and 0xFF) shl 16) or
                    ((itemTypeChars[2].code and 0xFF) shl 8) or
                    (itemTypeChars[3].code and 0xFF)
            } else 0

            val keyEntry = keysMap[indexVal]
            val keyString = keyEntry?.key ?: itemNode.type
            val friendlyName = KNOWN_QUICKTIME_KEYS[keyString] ?: keyString

            // Find data box inside itemNode
            val dataChildren = itemNode.children.filter { it.type == "data" }
            val firstValue = dataChildren.firstOrNull()?.fields?.find { it.name == "value" }?.value
            val displayName = if (friendlyName != keyString) "$friendlyName ($keyString)" else keyString
            var interpretation = itemNode.interpretation
            dataChildren.forEachIndexed { index, data ->
                interpretation = quickTimeState(interpretation, detailInterpretation(data))
                data.fields.find { it.name == "value" }?.let { value ->
                    extractedFields += value.copy(name = if (index == 0) displayName else "$displayName [${index + 1}]")
                }
            }
            itemNode.copy(type = keyString, summary = firstValue ?: itemNode.summary, interpretation = interpretation)
        }

        val ilstIndex = resultChildren.indexOf(ilstNode)
        if (ilstIndex >= 0) {
            resultChildren[ilstIndex] = ilstNode.copy(children = enrichedIlstChildren)
        }
    }

    if (extractedFields.isNotEmpty()) {
        val appleMetaNode = BoxNode(
            type = "Apple QuickTime Metadata",
            offset = ilstNode?.offset ?: 0L,
            headerSize = 0,
            size = ilstNode?.size ?: 0L,
            fields = extractedFields,
            summary = "${extractedFields.size} properties",
        )
        resultChildren.add(appleMetaNode)
    }

    return resultChildren
}
