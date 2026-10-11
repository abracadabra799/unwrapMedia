package com.multiviewer.parser.integrity

import com.multiviewer.parser.AsfGuids
import com.multiviewer.parser.ByteReader
import java.util.UUID

private const val ASF_MAX_HEADER_CHILDREN = 100_000L

internal fun checkAsfStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        items += IntegrityCheckItem("asf.$id", title, status, detail, offset, length)
    }
    if (reader.length < 24) return listOf(IntegrityCheckItem("asf.header", "ASF object header", CheckStatus.FAIL,
        "File is too short for an ASF object header", 0, reader.length))
    val expectedHeader = guidBytes(AsfGuids.HEADER)
    if (!reader.readBytes(0, 16).contentEquals(expectedHeader)) {
        return listOf(IntegrityCheckItem("asf.signature", "ASF header GUID", CheckStatus.FAIL, "Missing ASF Header Object GUID", 0, 16))
    }
    var position = 0L
    var headerCount = 0
    var filePropertiesOffset: Long? = null
    var filePropertiesSize = 0L
    var streamPropertiesCount = 0
    var audioStreamCount = 0
    var dataCount = 0
    var topLevelCount = 0
    while (position < reader.length) {
        checkAudioScanActive()
        if (reader.length - position < 24) {
            add("object.bounds", "ASF object bounds", CheckStatus.FAIL,
                "${reader.length - position} trailing byte(s) cannot contain an ASF object header", position, reader.length - position)
            break
        }
        val guid = reader.readBytes(position, 16)
        val size = readU64Le(reader, position + 16)
        if (size < 24 || size < 0 || size > reader.length - position) {
            add("object.bounds", "ASF object bounds", CheckStatus.FAIL,
                "Object size $size is invalid or extends past file size ${reader.length}", position, 24)
            break
        }
        topLevelCount++
        when {
            guid.contentEquals(expectedHeader) -> {
                headerCount++
                validateHeader(reader, position, size, ::add) { id, objectSize, audio ->
                    when (id) {
                        AsfGuids.FILE_PROPERTIES -> {
                            if (filePropertiesOffset == null) { filePropertiesOffset = objectSize.first; filePropertiesSize = objectSize.second }
                        }
                        AsfGuids.STREAM_PROPERTIES -> {
                            streamPropertiesCount++
                            if (audio) audioStreamCount++
                        }
                    }
                }
            }
            guid.contentEquals(guidBytes(AsfGuids.DATA)) -> {
                dataCount++
                if (size < 50) add("data.size", "ASF Data Object size", CheckStatus.FAIL,
                    "Data Object is $size bytes; at least 50 are required", position, size)
                else add("data.bounds", "ASF Data Object", CheckStatus.PASS, "Data Object is within the file", position, size)
            }
        }
        position += size
    }
    if (headerCount == 0) add("header.missing", "ASF Header Object", CheckStatus.FAIL, "Required ASF Header Object is missing")
    if (headerCount > 1) add("header.duplicate", "ASF Header Objects", CheckStatus.WARN, "$headerCount Header Objects are present")
    val fileProps = filePropertiesOffset
    if (fileProps == null) add("fileProperties.missing", "File Properties Object", CheckStatus.FAIL,
        "Required File Properties Object is missing")
    else if (filePropertiesSize < 104) add("fileProperties.size", "File Properties Object", CheckStatus.FAIL,
        "File Properties Object is shorter than its 80-byte payload", fileProps, filePropertiesSize)
    else {
        val declaredFileSize = readU64Le(reader, fileProps + 40)
        if (declaredFileSize < 0 || declaredFileSize != reader.length) add("fileSize", "Declared file size", CheckStatus.FAIL,
            "File Properties declares $declaredFileSize bytes, actual file size is ${reader.length}", fileProps + 40, 8)
        else add("fileSize", "Declared file size", CheckStatus.PASS, "Declared file size matches actual file size", fileProps + 40, 8)
    }
    if (streamPropertiesCount == 0) add("streamProperties.missing", "Stream Properties Object", CheckStatus.FAIL,
        "Required Stream Properties Object is missing")
    else if (audioStreamCount == 0) add("audioStream", "ASF audio stream", CheckStatus.WARN,
        "No audio Stream Properties Object was identified")
    else add("streamProperties", "Stream Properties Object", CheckStatus.PASS,
        "$streamPropertiesCount stream properties object(s), including $audioStreamCount audio stream(s)")
    if (dataCount == 0) add("data.missing", "ASF Data Object", CheckStatus.FAIL, "Required ASF Data Object is missing")
    if (topLevelCount > 0 && headerCount == 1) add("objects", "ASF objects", CheckStatus.PASS,
        "$topLevelCount top-level object(s) have valid extents")
    return items
}

private fun validateHeader(
    reader: ByteReader,
    offset: Long,
    size: Long,
    add: (String, String, CheckStatus, String, Long?, Long?) -> Unit,
    onChild: (UUID, Pair<Long, Long>, Boolean) -> Unit,
) {
    if (size < 30) {
        add("header.size", "ASF Header Object size", CheckStatus.FAIL,
            "Header Object is too short for child count and reserved bytes", offset, size)
        return
    }
    val declaredCount = readU32Le(reader, offset + 24)
    val childEnd = offset + size
    if (declaredCount > ASF_MAX_HEADER_CHILDREN) {
        add("header.count", "ASF Header child count", CheckStatus.FAIL,
            "Declared child count $declaredCount exceeds the verification limit", offset + 24, 4)
        return
    }
    if (reader.readUInt8(offset + 28) != 1 || reader.readUInt8(offset + 29) != 2) {
        add("header.reserved", "ASF Header reserved bytes", CheckStatus.WARN,
            "Reserved bytes are not the specified 0x01, 0x02", offset + 28, 2)
    }
    var position = offset + 30
    var actualCount = 0L
    while (actualCount < declaredCount) {
        checkAudioScanActive()
        if (childEnd - position < 24) {
            add("header.count", "ASF Header child count", CheckStatus.FAIL,
                "Header declares $declaredCount children but remaining bytes cannot contain child $actualCount", offset + 24, 4)
            return
        }
        val guid = reader.readBytes(position, 16)
        val childSize = readU64Le(reader, position + 16)
        if (childSize < 24 || childSize < 0 || childSize > childEnd - position) {
            add("header.childBounds", "ASF Header child bounds", CheckStatus.FAIL,
                "Child object size $childSize is invalid or exceeds Header Object", position, 24)
            return
        }
        val id = when {
            guid.contentEquals(guidBytes(AsfGuids.FILE_PROPERTIES)) -> AsfGuids.FILE_PROPERTIES
            guid.contentEquals(guidBytes(AsfGuids.STREAM_PROPERTIES)) -> AsfGuids.STREAM_PROPERTIES
            else -> UUID(0, 0)
        }
        val audio = if (id == AsfGuids.STREAM_PROPERTIES && childSize >= 78) {
            reader.readBytes(position + 24, 16).contentEquals(guidBytes(AsfGuids.STREAM_TYPE_AUDIO))
        } else false
        if (id == AsfGuids.FILE_PROPERTIES && childSize < 104) add("fileProperties.size", "File Properties Object", CheckStatus.FAIL,
            "File Properties Object is shorter than its 80-byte payload", position, childSize)
        if (id == AsfGuids.STREAM_PROPERTIES && childSize < 78) add("streamProperties.size", "Stream Properties Object", CheckStatus.FAIL,
            "Stream Properties Object is shorter than its 54-byte fixed payload", position, childSize)
        if (id != UUID(0, 0)) onChild(id, position to childSize, audio)
        position += childSize
        actualCount++
    }
    if (actualCount == declaredCount) add("header.count", "ASF Header child count", CheckStatus.PASS,
        "Header declares $declaredCount child object(s) and all fit within the Header Object", offset + 24, 4)
    if (position < childEnd) add("header.trailing", "ASF Header trailing data", CheckStatus.WARN,
        "${childEnd - position} byte(s) remain after the declared child objects", position, childEnd - position)
}

private fun guidBytes(uuid: UUID): ByteArray {
    val most = uuid.mostSignificantBits
    val least = uuid.leastSignificantBits
    val bytes = ByteArray(16)
    val d1 = most ushr 32 and 0xffff_ffffL
    val d2 = most ushr 16 and 0xffffL
    val d3 = most and 0xffffL
    for (i in 0..3) bytes[i] = (d1 ushr (i * 8)).toByte()
    bytes[4] = d2.toByte(); bytes[5] = (d2 ushr 8).toByte()
    bytes[6] = d3.toByte(); bytes[7] = (d3 ushr 8).toByte()
    for (i in 0..7) bytes[8 + i] = (least ushr (56 - i * 8)).toByte()
    return bytes
}

private fun readU32Le(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
        ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
}

private fun readU64Le(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 8)
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or (b[i].toLong() and 0xff)
    return value
}
