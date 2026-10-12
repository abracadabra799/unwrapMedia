package com.multiviewer.parser.c2pa

import com.multiviewer.parser.BoxNode

enum class CoverageStatus {
    HASHED,
    EXCLUDED
}

data class StructureCoverageItem(
    val name: String,
    val type: String,
    val offset: Long,
    val size: Long,
    val status: CoverageStatus,
    val impactNote: String
)

data class EditImpactAssessment(
    val motionPhotoVideoSafe: Boolean,
    val motionPhotoVideoReason: String,
    val sefTrailerSafe: Boolean,
    val sefTrailerReason: String,
    val xmpMetadataSafe: Boolean,
    val xmpMetadataReason: String
)

fun buildStructureCoverage(
    root: BoxNode,
    dataExclusions: List<DataExclusion>,
    bmffExclusions: List<BmffExclusion>
): List<StructureCoverageItem> {
    val items = mutableListOf<StructureCoverageItem>()
    val nodes = root.children

    for (node in nodes) {
        val (status, note) = determineCoverageStatus(node, dataExclusions, bmffExclusions)
        val descriptiveName = getDescriptiveName(node)
        items += StructureCoverageItem(
            name = descriptiveName,
            type = node.type,
            offset = node.offset,
            size = node.size,
            status = status,
            impactNote = note
        )
    }
    return items
}

private fun determineCoverageStatus(
    node: BoxNode,
    dataExclusions: List<DataExclusion>,
    bmffExclusions: List<BmffExclusion>
): Pair<CoverageStatus, String> {
    if (dataExclusions.isNotEmpty()) {
        val nodeStart = node.offset
        val nodeEnd = node.offset + node.size
        val isExcluded = dataExclusions.any { ex ->
            val exEnd = ex.start + ex.length
            nodeStart >= ex.start && nodeEnd <= exEnd
        }
        return if (isExcluded) {
            CoverageStatus.EXCLUDED to "C2PA 제외 영역 (변경해도 해시 불변)"
        } else {
            CoverageStatus.HASHED to "C2PA 보호 영역 (변경 시 해시 불일치 발생)"
        }
    }

    if (bmffExclusions.isNotEmpty()) {
        val isExcluded = bmffExclusions.any { ex ->
            val target = ex.xpath.trimStart('/')
            if (node.type != target) return@any false
            if (node.type == "uuid" && ex.dataMatches.isNotEmpty()) {
                val dm = ex.dataMatches.first()
                val usertype = node.fields.firstOrNull { it.name == "usertype" }?.value
                if (usertype != null && usertype.contains("d8fec3d6")) return@any true
            }
            ex.subsets.isEmpty()
        }
        return if (isExcluded) {
            CoverageStatus.EXCLUDED to "C2PA 제외 박스 (변경해도 해시 불변)"
        } else {
            CoverageStatus.HASHED to "C2PA 보호 박스 (변경 시 해시 불일치 발생)"
        }
    }

    return CoverageStatus.HASHED to "C2PA 보호 영역"
}

private fun getDescriptiveName(node: BoxNode): String = when (node.type) {
    "APP0" -> "JFIF Header (APP0)"
    "APP1" -> when {
        node.summary?.contains("Exif") == true -> "Exif Metadata (APP1)"
        node.summary?.contains("http://ns.adobe.com/xap") == true -> "XMP Metadata (APP1)"
        else -> "Metadata Segment (APP1)"
    }
    "APP2" -> when {
        node.summary?.contains("MPF") == true -> "Multi-Picture Format (APP2)"
        node.summary?.contains("1884") == true -> "ICC Color Profile (APP2)"
        else -> "APP2 Segment"
    }
    "APP11" -> "C2PA JUMBF Manifest (APP11)"
    "DQT" -> "Quantization Table (DQT)"
    "SOF0", "SOF2" -> "Frame Header (${node.type})"
    "DHT" -> "Huffman Table (DHT)"
    "SOS" -> "Scan Image Data (SOS)"
    "EOI" -> "End of Image (EOI)"
    "ftyp" -> "File Type (ftyp)"
    "uuid" -> if (node.fields.any { it.name == "purpose" && it.value == "manifest" }) "C2PA Manifest Box (uuid)" else "User Extension Box (uuid)"
    "meta" -> "Item Metadata (meta)"
    "mdat" -> "Media Data (mdat)"
    "moov" -> "Movie Header (moov)"
    "free", "skip" -> "Padding Space (${node.type})"
    "sefd" -> "Samsung SEF Trailer (sefd)"
    "C2PA" -> "C2PA RIFF Chunk"
    "VP8 ", "VP8X", "VP8L" -> "WebP Bitstream (${node.type.trim()})"
    else -> "${node.type} Box/Segment"
}

fun evaluateEditImpact(
    container: C2paContainer?,
    bmffExclusions: List<BmffExclusion>,
    dataExclusions: List<DataExclusion>
): EditImpactAssessment {
    val sefSafe = if (container == C2paContainer.BMFF) {
        bmffExclusions.any { it.xpath == "/sefd" }
    } else {
        false
    }
    val sefReason = if (sefSafe) {
        "BMFF exclusions에 '/sefd'가 명시되어 있어 SEF 메타데이터 추가/수정 시에도 C2PA 바인딩이 유지됩니다."
    } else {
        "SEF 메타데이터 영역이 C2PA 해시 범위에 포함되어 있어 수정 시 바인딩이 손상됩니다."
    }

    val motionPhotoVideoSafe = false
    val motionPhotoVideoReason = if (container == C2paContainer.BMFF) {
        "모션포토 비디오 트랙(ftyp, mdat, moov)이 BMFF exclusions에 제외되어 있지 않아 비디오가 포함되면 해시 불일치가 발생합니다."
    } else {
        "JPEG 파일 뒤에 모션포토 비디오를 덧붙이면 전체 데이터 해시가 달라져 C2PA 바인딩이 손상됩니다."
    }

    val xmpSafe = false
    val xmpReason = "XMP 메타데이터(APP1 또는 meta 박스)는 exclusions에 포함되지 않아 메타데이터 변경 시 C2PA 바인딩이 손상됩니다."

    return EditImpactAssessment(
        motionPhotoVideoSafe = motionPhotoVideoSafe,
        motionPhotoVideoReason = motionPhotoVideoReason,
        sefTrailerSafe = sefSafe,
        sefTrailerReason = sefReason,
        xmpMetadataSafe = xmpSafe,
        xmpMetadataReason = xmpReason
    )
}
