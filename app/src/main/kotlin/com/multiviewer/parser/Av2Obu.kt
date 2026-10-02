package com.multiviewer.parser

/** A bounded AV2 parsing result. Malformed media is data to report, never an exception to escape. */
sealed interface Av2ParseResult<out T> {
    data class Value<T>(val value: T, val nextOffset: Long) : Av2ParseResult<T>
    data class Error(val message: String) : Av2ParseResult<Nothing>
}

data class Av2ObuHeader(
    val extensionFlag: Boolean,
    val obuType: Int,
    val temporalLayerId: Int,
    val embeddedLayerId: Int,
    val extendedLayerId: Int,
    val headerSize: Int,
)

private const val AV2_GLOBAL_XLAYER_ID = 31

private val AV2_OBU_TYPE_NAMES = mapOf(
    1 to "OBU_SEQUENCE_HEADER",
    2 to "OBU_TEMPORAL_DELIMITER",
    3 to "OBU_MULTI_FRAME_HEADER",
    4 to "OBU_CLOSED_LOOP_KEY",
    5 to "OBU_OPEN_LOOP_KEY",
    6 to "OBU_LEADING_TILE_GROUP",
    7 to "OBU_REGULAR_TILE_GROUP",
    8 to "OBU_METADATA_SHORT",
    9 to "OBU_METADATA_GROUP",
    10 to "OBU_SWITCH",
    11 to "OBU_LEADING_SEF",
    12 to "OBU_REGULAR_SEF",
    13 to "OBU_LEADING_TIP",
    14 to "OBU_REGULAR_TIP",
    15 to "OBU_BUFFER_REMOVAL_TIMING",
    16 to "OBU_LAYER_CONFIGURATION_RECORD",
    17 to "OBU_ATLAS_SEGMENT",
    18 to "OBU_OPERATING_POINT_SET",
    19 to "OBU_BRIDGE_FRAME",
    20 to "OBU_MSDO",
    21 to "OBU_RAS_FRAME",
    22 to "OBU_QUANTIZATION_MATRIX",
    23 to "OBU_FILM_GRAIN",
    24 to "OBU_CONTENT_INTERPRETATION",
    25 to "OBU_PADDING",
)

fun av2ObuTypeName(obuType: Int): String = AV2_OBU_TYPE_NAMES[obuType] ?: "unknown($obuType)"

// AV2 spec 5.2.2: first byte is extension flag (1), type (5), temporal layer ID (2). When the
// extension flag is set, the second byte holds embedded layer ID (3) and extended layer ID (5).
fun parseAv2ObuHeader(reader: ByteReader, offset: Long, endExclusive: Long): Av2ParseResult<Av2ObuHeader> {
    if (offset < 0 || endExclusive > reader.length || offset >= endExclusive) {
        return Av2ParseResult.Error("AV2 OBU header is outside its declared bounds")
    }
    val first = reader.readUInt8(offset)
    val extensionFlag = first and 0x80 != 0
    val obuType = (first shr 2) and 0x1F
    val temporalLayerId = first and 0x03
    if (!extensionFlag) {
        val inferredXLayer = if (obuType == 2 || obuType == 20) AV2_GLOBAL_XLAYER_ID else 0
        return Av2ParseResult.Value(
            Av2ObuHeader(false, obuType, temporalLayerId, 0, inferredXLayer, 1),
            offset + 1,
        )
    }
    if (offset + 1 >= endExclusive) return Av2ParseResult.Error("AV2 OBU header extension is truncated")
    val extension = reader.readUInt8(offset + 1)
    return Av2ParseResult.Value(
        Av2ObuHeader(true, obuType, temporalLayerId, extension shr 5, extension and 0x1F, 2),
        offset + 2,
    )
}

// AV2 spec 4.11.6 leb128(). The caller supplies the enclosing record boundary, which keeps a
// malformed config OBU from consuming a following OBU or a following ISO-BMFF box.
fun readAv2Leb128(reader: ByteReader, offset: Long, endExclusive: Long): Av2ParseResult<Long> {
    if (offset < 0 || endExclusive > reader.length || offset >= endExclusive) {
        return Av2ParseResult.Error("AV2 leb128 is outside its declared bounds")
    }
    var value = 0L
    var position = offset
    for (index in 0 until 8) {
        if (position >= endExclusive) return Av2ParseResult.Error("AV2 leb128 is truncated")
        val byte = reader.readUInt8(position)
        position += 1
        value = value or ((byte.toLong() and 0x7F) shl (index * 7))
        if (byte and 0x80 == 0) return Av2ParseResult.Value(value, position)
    }
    return Av2ParseResult.Error("AV2 leb128 exceeds 8 bytes")
}
