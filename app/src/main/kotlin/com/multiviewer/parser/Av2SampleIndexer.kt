package com.multiviewer.parser

data class Av2SampleObu(val offset: Long, val size: Long, val typeName: String)
data class Av2SampleObuParseResult(val obus: List<Av2SampleObu>, val warnings: List<String>)

/** Walks one ISO-BMFF AV2 sample without reading beyond its declared sample extent. */
fun parseAv2SampleObus(reader: ByteReader, sampleOffset: Long, sampleSize: Long): Av2SampleObuParseResult {
    val warnings = mutableListOf<String>()
    val obus = mutableListOf<Av2SampleObu>()
    val end = sampleOffset + sampleSize
    if (sampleOffset < 0 || sampleSize < 0 || end < sampleOffset || end > reader.length) {
        return Av2SampleObuParseResult(emptyList(), listOf("AV2 sample is outside file bounds"))
    }
    var cursor = sampleOffset
    while (cursor < end) {
        val length = readAv2Leb128(reader, cursor, end)
        if (length is Av2ParseResult.Error) { warnings += length.message; break }
        length as Av2ParseResult.Value
        val obuStart = length.nextOffset
        val obuEnd = obuStart + length.value
        if (length.value <= 0 || obuEnd < obuStart || obuEnd > end) { warnings += "AV2 OBU extends beyond sample boundary"; break }
        val header = parseAv2ObuHeader(reader, obuStart, obuEnd)
        if (header is Av2ParseResult.Error) { warnings += header.message; break }
        header as Av2ParseResult.Value
        obus += Av2SampleObu(obuStart, length.value, av2ObuTypeName(header.value.obuType))
        cursor = obuEnd
    }
    return Av2SampleObuParseResult(obus, warnings)
}
