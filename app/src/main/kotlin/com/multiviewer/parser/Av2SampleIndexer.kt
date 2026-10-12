package com.multiviewer.parser

import java.io.File
import java.io.FileOutputStream

data class Av2SampleObu(val offset: Long, val size: Long, val typeName: String)
data class Av2SampleObuParseResult(val obus: List<Av2SampleObu>, val warnings: List<String>)
data class Av2IndexedSample(
    val index: Int,
    val decodeTime: Long,
    val duration: Long,
    val offset: Long,
    val size: Long,
    val obus: List<Av2SampleObu>,
    val warnings: List<String>,
)
data class Av2SampleIndex(val timescale: Long, val samples: List<Av2IndexedSample>, val warnings: List<String>)

private const val MAX_AV2_INDEXED_SAMPLES = 100_000

/** Writes configuration OBUs followed by an ordered sample range without trusting unchecked offsets. */
fun assembleAv2Bitstream(file: File, av2CNode: BoxNode, index: Av2SampleIndex, range: IntRange, destination: File): Boolean {
    val selected = range.filter { it in index.samples.indices }.map { index.samples[it] }
    if (selected.isEmpty() || selected.any { it.warnings.isNotEmpty() || it.offset < 0 || it.size <= 0 }) return false
    val part = File(destination.parentFile ?: file.parentFile, destination.name + ".part")
    return try {
        ByteReader.open(file).use { reader ->
            FileOutputStream(part).use { output ->
                val start = av2CNode.offset + av2CNode.headerSize
                val end = minOf(av2CNode.offset + av2CNode.size, reader.length)
                if (end - start < 2) return false
                var cursor = start + 2
                val count = reader.readUInt8(start + 1) + 1
                repeat(count) {
                    val length = (readAv2Leb128(reader, cursor, end) as? Av2ParseResult.Value) ?: return false
                    val obuEnd = length.nextOffset + length.value
                    if (length.value <= 0 || obuEnd > end) return false
                    output.write(reader.readBytes(cursor, (obuEnd - cursor).toInt()))
                    cursor = obuEnd
                }
                selected.forEach { sample ->
                    if (sample.offset + sample.size > reader.length) return false
                    output.write(reader.readBytes(sample.offset, sample.size.toInt()))
                }
            }
        }
        if (destination.exists()) destination.delete()
        if (!part.renameTo(destination)) return false
        true
    } catch (_: Exception) {
        false
    } finally {
        if (part.exists()) part.delete()
    }
}

/** Builds a bounded AV2 sample index from an already parsed ISO-BMFF box tree. */
fun buildAv2SampleIndex(file: File, root: BoxNode): Av2SampleIndex? = ByteReader.open(file).use { reader ->
    val trak = boxDescendants(root).firstOrNull { it.type == "trak" && boxDescendants(it).any { child -> child.type == "av02" } } ?: return null
    val mdia = trak.children.firstOrNull { it.type == "mdia" } ?: return null
    val stbl = boxDescendants(mdia).firstOrNull { it.type == "stbl" } ?: return null
    val warnings = mutableListOf<String>()
    val timescale = boxDescendants(mdia).firstOrNull { it.type == "mdhd" }
        ?.fields?.firstOrNull { it.name == "timescale" }?.value?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    val timings = readSampleTimings(reader, stbl.children.firstOrNull { it.type == "stts" }, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val sizes = readSampleSizes(reader, stbl.children.firstOrNull { it.type == "stsz" }, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val offsets = readChunkSampleOffsets(reader, stbl, sizes, warnings, "AV2", MAX_AV2_INDEXED_SAMPLES)
    val sampleCount = minOf(timings.size, sizes.size, offsets.size, MAX_AV2_INDEXED_SAMPLES)
    if (sampleCount < minOf(timings.size, sizes.size, offsets.size)) warnings += "AV2 sample index capped at $MAX_AV2_INDEXED_SAMPLES samples"
    if (sampleCount == 0 && (timings.isNotEmpty() || sizes.isNotEmpty() || offsets.isNotEmpty())) warnings += "AV2 sample tables have inconsistent counts"
    val samples = (0 until sampleCount).map { index ->
        val (dts, duration) = timings[index]
        val parsed = parseAv2SampleObus(reader, offsets[index], sizes[index])
        Av2IndexedSample(index, dts, duration, offsets[index], sizes[index], parsed.obus, parsed.warnings)
    }
    Av2SampleIndex(timescale, samples, warnings)
}

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
