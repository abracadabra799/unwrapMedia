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
    val trak = descendants(root).firstOrNull { it.type == "trak" && descendants(it).any { child -> child.type == "av02" } } ?: return null
    val mdia = trak.children.firstOrNull { it.type == "mdia" } ?: return null
    val stbl = descendants(mdia).firstOrNull { it.type == "stbl" } ?: return null
    val warnings = mutableListOf<String>()
    val timescale = descendants(mdia).firstOrNull { it.type == "mdhd" }
        ?.fields?.firstOrNull { it.name == "timescale" }?.value?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    val timings = readTimings(reader, stbl.children.firstOrNull { it.type == "stts" }, warnings)
    val sizes = readSizes(reader, stbl.children.firstOrNull { it.type == "stsz" }, warnings)
    val offsets = readSampleOffsets(reader, stbl, sizes, warnings)
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

private fun descendants(node: BoxNode): Sequence<BoxNode> = sequence {
    yield(node)
    node.children.forEach { yieldAll(descendants(it)) }
}

private fun readTimings(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>): List<Pair<Long, Long>> {
    val table = node?.table ?: run { warnings += "AV2 track has no stts table"; return emptyList() }
    val result = mutableListOf<Pair<Long, Long>>()
    var dts = 0L
    for (row in 0 until boundedRows(reader, table, warnings, "stts")) {
        val at = table.entriesStart + row * 8L
        val count = reader.readUInt32(at)
        val delta = reader.readUInt32(at + 4)
        val remaining = MAX_AV2_INDEXED_SAMPLES - result.size
        if (count > remaining) warnings += "stts sample count capped at $MAX_AV2_INDEXED_SAMPLES"
        repeat(minOf(count, remaining.toLong()).toInt()) { result += dts to delta; dts += delta }
        if (result.size == MAX_AV2_INDEXED_SAMPLES) break
    }
    return result
}

private fun readSizes(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>): List<Long> {
    if (node == null) { warnings += "AV2 track has no stsz table"; return emptyList() }
    val uniform = node.fields.firstOrNull { it.name == "sample_size" }?.value?.toLongOrNull() ?: 0L
    val table = node.table
    if (uniform > 0) {
        val count = node.fields.firstOrNull { it.name == "sample_count" }?.value?.toLongOrNull() ?: 0L
        return List(minOf(count, MAX_AV2_INDEXED_SAMPLES.toLong()).toInt()) { uniform }
    }
    if (table == null) { warnings += "AV2 stsz has no sample-size entries"; return emptyList() }
    return (0 until boundedRows(reader, table, warnings, "stsz")).map { reader.readUInt32(table.entriesStart + it * 4L) }
}

private fun readSampleOffsets(reader: ByteReader, stbl: BoxNode, sizes: List<Long>, warnings: MutableList<String>): List<Long> {
    val chunkTable = stbl.children.firstOrNull { it.type == "stco" || it.type == "co64" }?.table
        ?: run { warnings += "AV2 track has no chunk-offset table"; return emptyList() }
    val chunkWidth = if (stbl.children.any { it.type == "co64" && it.table == chunkTable }) 8 else 4
    val chunks = (0 until boundedRows(reader, chunkTable, warnings, "chunk offset")).map {
        val at = chunkTable.entriesStart + it * chunkWidth.toLong()
        if (chunkWidth == 8) reader.readUInt64(at) else reader.readUInt32(at)
    }
    val stsc = stbl.children.firstOrNull { it.type == "stsc" }?.table ?: run { warnings += "AV2 track has no stsc table"; return emptyList() }
    data class Entry(val firstChunk: Long, val samplesPerChunk: Long)
    val entries = (0 until boundedRows(reader, stsc, warnings, "stsc")).map {
        val at = stsc.entriesStart + it * 12L
        Entry(reader.readUInt32(at), reader.readUInt32(at + 4))
    }.filter { it.firstChunk > 0 && it.samplesPerChunk > 0 }
    if (entries.isEmpty()) { warnings += "AV2 stsc has no usable entries"; return emptyList() }
    val offsets = mutableListOf<Long>(); var sample = 0
    for ((chunkIndex, chunkOffset) in chunks.withIndex()) {
        val entry = entries.lastOrNull { it.firstChunk <= chunkIndex + 1L } ?: continue
        var offset = chunkOffset
        repeat(entry.samplesPerChunk.coerceAtMost((sizes.size - sample).toLong()).toInt()) {
            offsets += offset
            offset += sizes[sample++]
        }
        if (sample >= sizes.size || sample >= MAX_AV2_INDEXED_SAMPLES) break
    }
    return offsets
}

private fun boundedRows(reader: ByteReader, table: TableData, warnings: MutableList<String>, label: String): Int {
    val width = table.fieldWidths.sum().toLong()
    if (width <= 0 || table.entriesStart < 0 || table.entriesStart > reader.length) { warnings += "$label table is outside file bounds"; return 0 }
    val available = (reader.length - table.entriesStart) / width
    val actual = minOf(table.entryCount, available, MAX_AV2_INDEXED_SAMPLES.toLong())
    if (actual < table.entryCount) warnings += "$label table is truncated or exceeds index limit"
    return actual.toInt()
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
