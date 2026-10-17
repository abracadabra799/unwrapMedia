package com.multiviewer.parser

// Shared, bounds-checked ISO-BMFF sample-table readers over an already parsed box tree. Moved out of
// Av2SampleIndexer.kt so the HDR/SEI scanner can index H.264/HEVC/AV1 tracks with the same logic;
// `label` only prefixes warning text, `maxSamples` bounds every table walk.

internal fun boxDescendants(node: BoxNode): Sequence<BoxNode> = sequence {
    yield(node)
    node.children.forEach { yieldAll(boxDescendants(it)) }
}

internal fun readSampleTimings(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Pair<Long, Long>> {
    val table = node?.table ?: run { warnings += "$label track has no stts table"; return emptyList() }
    val result = mutableListOf<Pair<Long, Long>>()
    var dts = 0L
    for (row in 0 until boundedTableRows(reader, table, warnings, "stts", maxSamples)) {
        val at = table.entriesStart + row * 8L
        val count = reader.readUInt32(at)
        val delta = reader.readUInt32(at + 4)
        val remaining = maxSamples - result.size
        if (count > remaining) warnings += "stts sample count capped at $maxSamples"
        repeat(minOf(count, remaining.toLong()).toInt()) { result += dts to delta; dts += delta }
        if (result.size == maxSamples) break
    }
    return result
}

internal fun readSampleSizes(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    if (node == null) { warnings += "$label track has no stsz table"; return emptyList() }
    val uniform = node.fields.firstOrNull { it.name == "sample_size" }?.value?.toLongOrNull() ?: 0L
    val table = node.table
    if (uniform > 0) {
        val count = node.fields.firstOrNull { it.name == "sample_count" }?.value?.toLongOrNull() ?: 0L
        return List(minOf(count, maxSamples.toLong()).toInt()) { uniform }
    }
    if (table == null) { warnings += "$label stsz has no sample-size entries"; return emptyList() }
    return (0 until boundedTableRows(reader, table, warnings, "stsz", maxSamples)).map { reader.readUInt32(table.entriesStart + it * 4L) }
}

internal fun readChunkSampleOffsets(reader: ByteReader, stbl: BoxNode, sizes: List<Long>, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    val chunkTable = stbl.children.firstOrNull { it.type == "stco" || it.type == "co64" }?.table
        ?: run { warnings += "$label track has no chunk-offset table"; return emptyList() }
    val chunkWidth = if (stbl.children.any { it.type == "co64" && it.table == chunkTable }) 8 else 4
    val chunks = (0 until boundedTableRows(reader, chunkTable, warnings, "chunk offset", maxSamples)).map {
        val at = chunkTable.entriesStart + it * chunkWidth.toLong()
        if (chunkWidth == 8) reader.readUInt64(at) else reader.readUInt32(at)
    }
    val stsc = stbl.children.firstOrNull { it.type == "stsc" }?.table ?: run { warnings += "$label track has no stsc table"; return emptyList() }
    data class Entry(val firstChunk: Long, val samplesPerChunk: Long)
    val entries = (0 until boundedTableRows(reader, stsc, warnings, "stsc", maxSamples)).map {
        val at = stsc.entriesStart + it * 12L
        Entry(reader.readUInt32(at), reader.readUInt32(at + 4))
    }.filter { it.firstChunk > 0 && it.samplesPerChunk > 0 }
    if (entries.isEmpty()) { warnings += "$label stsc has no usable entries"; return emptyList() }
    val offsets = mutableListOf<Long>(); var sample = 0
    for ((chunkIndex, chunkOffset) in chunks.withIndex()) {
        val entry = entries.lastOrNull { it.firstChunk <= chunkIndex + 1L } ?: continue
        var offset = chunkOffset
        repeat(entry.samplesPerChunk.coerceAtMost((sizes.size - sample).toLong()).toInt()) {
            offsets += offset
            offset += sizes[sample++]
        }
        if (sample >= sizes.size || sample >= maxSamples) break
    }
    return offsets
}

internal fun readSyncSampleNumbers(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): Set<Long>? {
    val table = node?.table ?: return null
    return (0 until boundedTableRows(reader, table, warnings, "$label stss", maxSamples))
        .map { reader.readUInt32(table.entriesStart + it * 4L) }
        .toSet()
}

// ctts version 0 is unsigned and version 1 signed; reading both as signed 32-bit is correct for
// every realistic offset (< 2^31 ticks).
internal fun readCompositionOffsets(reader: ByteReader, node: BoxNode?, warnings: MutableList<String>, label: String, maxSamples: Int): List<Long> {
    val table = node?.table ?: return emptyList()
    val result = mutableListOf<Long>()
    for (row in 0 until boundedTableRows(reader, table, warnings, "$label ctts", maxSamples)) {
        val at = table.entriesStart + row * 8L
        val count = reader.readUInt32(at)
        val offset = reader.readUInt32(at + 4).toInt().toLong()
        val remaining = maxSamples - result.size
        repeat(minOf(count, remaining.toLong()).toInt()) { result += offset }
        if (result.size == maxSamples) break
    }
    return result
}

private fun boundedTableRows(reader: ByteReader, table: TableData, warnings: MutableList<String>, label: String, maxSamples: Int): Int {
    val width = table.fieldWidths.sum().toLong()
    if (width <= 0 || table.entriesStart < 0 || table.entriesStart > reader.length) { warnings += "$label table is outside file bounds"; return 0 }
    val available = (reader.length - table.entriesStart) / width
    val actual = minOf(table.entryCount, available, maxSamples.toLong())
    if (actual < table.entryCount) warnings += "$label table is truncated or exceeds index limit"
    return actual.toInt()
}

data class VideoSample(
    val index: Int,
    val offset: Long,
    val size: Long,
    val decodeTime: Long,
    val compositionOffset: Long,
    val isSync: Boolean,
)

data class VideoSampleTable(
    val codec: VideoCodec,
    val nalLengthSize: Int?,
    val timescale: Long,
    val samples: List<VideoSample>,
    val warnings: List<String>,
)

const val MAX_VIDEO_INDEXED_SAMPLES = 1_000_000

internal val SAMPLE_ENTRY_CODECS = mapOf(
    "avc1" to VideoCodec.H264, "avc3" to VideoCodec.H264,
    "hvc1" to VideoCodec.HEVC, "hev1" to VideoCodec.HEVC,
    "av01" to VideoCodec.AV1,
    "vp09" to VideoCodec.VP9,
    "apv1" to VideoCodec.APV,
)

// The first trak whose sample entry is H.264/HEVC/AV1/VP9/APV, paired with that sample entry.
internal fun firstVideoSampleEntry(root: BoxNode): Pair<BoxNode, BoxNode>? =
    boxDescendants(root).filter { it.type == "trak" }.firstNotNullOfOrNull { trak ->
        boxDescendants(trak).firstOrNull { it.type in SAMPLE_ENTRY_CODECS }?.let { trak to it }
    }

// Indexes the first trak whose sample entry is H.264/HEVC/AV1/VP9/APV. nalLengthSize comes from
// avcC (byte 4) / hvcC (byte 21), low two bits + 1; null for AV1, VP9, and APV.
fun buildVideoSampleTable(reader: ByteReader, root: BoxNode, maxSamples: Int = MAX_VIDEO_INDEXED_SAMPLES): VideoSampleTable? {
    val (trak, entry) = firstVideoSampleEntry(root) ?: return null
    val codec = SAMPLE_ENTRY_CODECS.getValue(entry.type)
    val nalLengthSize = when (codec) {
        VideoCodec.H264 -> entry.children.firstOrNull { it.type == "avcC" }?.takeIf { it.size - it.headerSize >= 5 }
            ?.let { (reader.readUInt8(it.offset + it.headerSize + 4) and 0x03) + 1 }
        VideoCodec.HEVC -> entry.children.firstOrNull { it.type == "hvcC" }?.takeIf { it.size - it.headerSize >= 23 }
            ?.let { (reader.readUInt8(it.offset + it.headerSize + 21) and 0x03) + 1 }
        VideoCodec.AV1, VideoCodec.VP9, VideoCodec.APV -> null
    }
    if (codec != VideoCodec.AV1 && codec != VideoCodec.VP9 && codec != VideoCodec.APV && nalLengthSize == null) return null
    val mdia = trak.children.firstOrNull { it.type == "mdia" } ?: return null
    val stbl = boxDescendants(mdia).firstOrNull { it.type == "stbl" } ?: return null
    val warnings = mutableListOf<String>()
    val label = codec.name
    val timescale = boxDescendants(mdia).firstOrNull { it.type == "mdhd" }
        ?.fields?.firstOrNull { it.name == "timescale" }?.value?.toLongOrNull()?.takeIf { it > 0 } ?: 1L
    fun child(type: String) = stbl.children.firstOrNull { it.type == type }
    val timings = readSampleTimings(reader, child("stts"), warnings, label, maxSamples)
    val sizes = readSampleSizes(reader, child("stsz"), warnings, label, maxSamples)
    val offsets = readChunkSampleOffsets(reader, stbl, sizes, warnings, label, maxSamples)
    val sync = readSyncSampleNumbers(reader, child("stss"), warnings, label, maxSamples)
    val composition = readCompositionOffsets(reader, child("ctts"), warnings, label, maxSamples)
    val count = minOf(timings.size, sizes.size, offsets.size)
    if (count < maxOf(timings.size, sizes.size, offsets.size)) {
        warnings += "$label sample tables disagree: stts=${timings.size}, stsz=${sizes.size}, chunk offsets=${offsets.size}; indexed $count"
    }
    if (boxDescendants(root).any { it.type == "mvex" || it.type == "moof" }) {
        warnings += "Fragmented MP4 (moof) is not supported; only samples in the moov sample tables were scanned"
    }
    val samples = (0 until count).map { i ->
        VideoSample(
            index = i, offset = offsets[i], size = sizes[i], decodeTime = timings[i].first,
            compositionOffset = composition.getOrElse(i) { 0L }, isSync = sync?.contains(i + 1L) ?: true,
        )
    }
    return VideoSampleTable(codec, nalLengthSize, timescale, samples, warnings)
}
