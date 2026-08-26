package com.multiviewer.parser

enum class AppleAuxiliaryRole(val displayName: String) {
    HDR_GAIN_MAP("HDR Gain Map"),
    DEPTH("Depth Map"),
    DISPARITY("Disparity Map"),
    PORTRAIT_EFFECTS("Portrait Effects Matte"),
    SKY("Sky Matte"),
    PERSON("Person Matte"),
    SKIN("Skin Matte"),
    HAIR("Hair Matte"),
    TEETH("Teeth Matte"),
    GLASSES("Glasses Matte"),
    SMART_STYLE_LINEAR_THUMBNAIL("Smart Style Linear Thumbnail"),
    SMART_STYLE_DELTA_MAP("Smart Style Delta Map"),
    OTHER("Auxiliary Image"),
}

fun classifyAppleAuxiliaryRole(auxType: String): AppleAuxiliaryRole {
    val lower = auxType.lowercase()
    return when {
        lower.contains("hdrgainmap") || lower.contains("hdr-gain-map") || lower.contains("21496") -> AppleAuxiliaryRole.HDR_GAIN_MAP
        lower.contains("disparity") -> AppleAuxiliaryRole.DISPARITY
        lower.contains("depth") -> AppleAuxiliaryRole.DEPTH
        lower.contains("portraiteffects") || lower.contains("portrait-effects") -> AppleAuxiliaryRole.PORTRAIT_EFFECTS
        lower.contains("semanticsegmentationsky") || lower.endsWith(":sky") -> AppleAuxiliaryRole.SKY
        lower.contains("semanticsegmentationperson") || lower.endsWith(":person") -> AppleAuxiliaryRole.PERSON
        lower.contains("semanticsegmentationskin") || lower.endsWith(":skin") -> AppleAuxiliaryRole.SKIN
        lower.contains("semanticsegmentationhair") || lower.endsWith(":hair") -> AppleAuxiliaryRole.HAIR
        lower.contains("semanticsegmentationteeth") || lower.endsWith(":teeth") -> AppleAuxiliaryRole.TEETH
        lower.contains("semanticsegmentationglasses") || lower.endsWith(":glasses") -> AppleAuxiliaryRole.GLASSES
        lower.contains("smartstylelinearthumbnail") || lower.contains("linearthumbnail") -> AppleAuxiliaryRole.SMART_STYLE_LINEAR_THUMBNAIL
        lower.contains("smartstyledeltamap") || lower.contains("deltamap") -> AppleAuxiliaryRole.SMART_STYLE_DELTA_MAP
        else -> AppleAuxiliaryRole.OTHER
    }
}

fun buildAppleAuxiliaryNode(meta: BoxNode): BoxNode? {
    val pitmNode = meta.children.find { it.type == "pitm" }
    val primaryItemId = pitmNode?.fields?.find { it.name == "item_ID" }?.value?.toLongOrNull()

    val iinfNode = meta.children.find { it.type == "iinf" }
    val itemTypes = mutableMapOf<Long, String>()
    val itemNames = mutableMapOf<Long, String>()
    if (iinfNode != null) {
        for (infe in iinfNode.children) {
            val id = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: continue
            val itemType = infe.fields.find { it.name == "item_type" }?.value ?: ""
            val itemName = infe.fields.find { it.name == "item_name" }?.value ?: ""
            itemTypes[id] = itemType
            itemNames[id] = itemName
        }
    }

    val iprpNode = meta.children.find { it.type == "iprp" }
    val ipcoNode = iprpNode?.children?.find { it.type == "ipco" }
    val ipmaNode = iprpNode?.children?.find { it.type == "ipma" }

    val propertiesList = ipcoNode?.children ?: emptyList()

    // Map itemId -> list of 1-based property indices
    val itemProperties = mutableMapOf<Long, List<Int>>()
    if (ipmaNode != null) {
        for (itemAssoc in ipmaNode.children) {
            val id = itemAssoc.type.removePrefix("item_").toLongOrNull() ?: continue
            val indices = itemAssoc.fields
                .filter { it.name == "property_index" }
                .mapNotNull { it.value.toIntOrNull() }
            itemProperties[id] = indices
        }
    }

    val irefNode = meta.children.find { it.type == "iref" }
    // Map fromId -> list of (refType, toId)
    val itemRefs = mutableMapOf<Long, MutableList<Pair<String, Long>>>()
    if (irefNode != null) {
        for (refBox in irefNode.children) {
            val fromId = refBox.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull() ?: continue
            val toIds = refBox.fields
                .filter { it.name.startsWith("to_item_ID") }
                .mapNotNull { it.value.toLongOrNull() }
            val list = itemRefs.getOrPut(fromId) { mutableListOf() }
            for (toId in toIds) {
                list.add(refBox.type to toId)
            }
        }
    }

    // Identify auxiliary items
    val auxItemIds = mutableSetOf<Long>()
    for ((id, propIndices) in itemProperties) {
        for (idx in propIndices) {
            if (idx in 1..propertiesList.size) {
                val prop = propertiesList[idx - 1]
                if (prop.type == "auxC") {
                    auxItemIds.add(id)
                }
            }
        }
    }

    for ((fromId, refs) in itemRefs) {
        if (refs.any { it.first == "auxl" }) {
            auxItemIds.add(fromId)
        }
    }

    if (auxItemIds.isEmpty()) return null

    val auxChildren = mutableListOf<BoxNode>()
    for (itemId in auxItemIds.sorted()) {
        val warnings = mutableListOf<String>()
        val propIndices = itemProperties[itemId] ?: emptyList()

        var auxTypeStr: String? = null
        var widthStr: String? = null
        var heightStr: String? = null
        var bitDepthStr: String? = null

        for (idx in propIndices) {
            if (idx in 1..propertiesList.size) {
                val prop = propertiesList[idx - 1]
                when (prop.type) {
                    "auxC" -> {
                        auxTypeStr = prop.fields.find { it.name == "aux_type" }?.value ?: prop.summary
                    }
                    "ispe" -> {
                        widthStr = prop.fields.find { it.name == "image_width" }?.value
                        heightStr = prop.fields.find { it.name == "image_height" }?.value
                    }
                    "pixi" -> {
                        val bits = prop.fields.filter { it.name.startsWith("bits_per_channel") }.map { it.value }
                        val channels = prop.fields.find { it.name == "num_channels" }?.value
                        if (bits.isNotEmpty()) {
                            bitDepthStr = "${bits.joinToString("/")} bits (${channels ?: bits.size.toString()} ch)"
                        }
                    }
                }
            }
        }

        val role = classifyAppleAuxiliaryRole(auxTypeStr ?: "")
        val refs = itemRefs[itemId] ?: emptyList()
        val targetId = refs.firstOrNull { it.first == "auxl" || it.first == "cdsc" }?.second
            ?: primaryItemId

        if (targetId != null && itemTypes.isNotEmpty() && !itemTypes.containsKey(targetId)) {
            warnings.add("Dangling reference: target item $targetId not found in iinf")
        }

        val format = itemTypes[itemId] ?: "unknown"
        val resolution = if (widthStr != null && heightStr != null) "${widthStr}x${heightStr}" else null

        val fields = mutableListOf<BoxField>()
        fields.add(BoxField("Role", role.name, 0, 0))
        fields.add(BoxField("Role Description", role.displayName, 0, 0))
        fields.add(BoxField("Item ID", itemId.toString(), 0, 0))
        if (targetId != null) {
            fields.add(BoxField("Primary Item ID", targetId.toString(), 0, 0))
        }
        if (auxTypeStr != null) {
            fields.add(BoxField("Auxiliary Type", auxTypeStr, 0, 0))
        }
        if (resolution != null) {
            fields.add(BoxField("Resolution", resolution, 0, 0))
        }
        if (bitDepthStr != null) {
            fields.add(BoxField("Bit Depth", bitDepthStr, 0, 0))
        }
        fields.add(BoxField("Format", format, 0, 0))

        auxChildren.add(
            BoxNode(
                type = "${role.displayName} (Item $itemId)",
                offset = 0,
                headerSize = 0,
                size = 0,
                fields = fields,
                warnings = warnings,
                summary = "${role.displayName}${if (resolution != null) " $resolution" else ""} ($format)",
            ),
        )
    }

    return BoxNode(
        type = "Auxiliary Images",
        offset = iprpNode?.offset ?: 0L,
        headerSize = 0,
        size = iprpNode?.size ?: 0L,
        children = auxChildren,
        summary = "${auxChildren.size} auxiliary images",
    )
}
