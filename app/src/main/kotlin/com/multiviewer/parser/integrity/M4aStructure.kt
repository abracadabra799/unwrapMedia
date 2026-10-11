package com.multiviewer.parser.integrity

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

private const val MAX_MP4_TABLE_ENTRIES = 1_000_000L
private const val MAX_MP4_FINDINGS = 1000

internal fun checkM4aStructure(reader: ByteReader, root: BoxNode): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    var findingsTruncated = false
    fun add(id: String, title: String, status: CheckStatus, detail: String, node: BoxNode? = null) {
        if (items.size < MAX_MP4_FINDINGS) items += IntegrityCheckItem("m4a.$id", title, status, detail, node?.offset, node?.size)
        else findingsTruncated = true
    }
    val tracks = descendants(root).filter { it.type == "trak" && descendants(it).any { child ->
        child.type == "hdlr" && child.fields.any { field -> field.name == "handler_type" && field.value == "soun" }
    } }.toList()
    val fragmented = descendants(root).any { it.type == "mvex" || it.type == "moof" }
    val mediaExtents = descendants(root).filter { it.type == "mdat" }.mapNotNull { node ->
        checkedBoxEnd(node, reader)?.let { end -> (node.offset + node.headerSize) until end }
    }.toList()
    if (tracks.isEmpty()) return listOf(IntegrityCheckItem("m4a.audioTrack", "Audio track", CheckStatus.SKIP,
        "No audio track was identified; audio sample-table checks are unavailable"))
    var checkedTracks = 0
    tracks.forEachIndexed { trackIndex, track ->
        val stbl = descendants(track).firstOrNull { it.type == "stbl" }
        if (stbl == null) {
            add("sampleMapping", "Sample mapping (track ${trackIndex + 1})", CheckStatus.WARN,
                "Audio track has no sample table; fragmented or incomplete layouts are not covered", track)
            return@forEachIndexed
        }
        val stsz = stbl.children.firstOrNull { it.type == "stsz" }
        val chunkTable = stbl.children.firstOrNull { it.type == "stco" || it.type == "co64" }
        val stsc = stbl.children.firstOrNull { it.type == "stsc" }
        if (stsz == null || chunkTable == null) {
            add("sampleMapping", "Sample mapping (track ${trackIndex + 1})", CheckStatus.WARN,
                "Audio sample table is missing stsz or stco/co64", stbl)
            return@forEachIndexed
        }
        val sizes = readStsz(reader, stsz, ::add) ?: return@forEachIndexed
        val chunks = readChunkOffsets(reader, chunkTable, ::add) ?: return@forEachIndexed
        checkedTracks++
        if (stsc == null) {
            add("sampleMapping", "Sample mapping (track ${trackIndex + 1})", CheckStatus.SKIP,
                "stsc mapping is absent; chunk-to-sample coverage cannot be verified", stbl)
        } else {
            validateStsc(reader, stsc, chunks, sizes, mediaExtents, fragmented, ::add)
        }
        if (chunks.capped) add("table.limit", "Table entry limit (track ${trackIndex + 1})", CheckStatus.WARN,
            "More than $MAX_MP4_TABLE_ENTRIES entries; only the first $MAX_MP4_TABLE_ENTRIES chunk offsets are checked", stbl)
    }
    if (checkedTracks > 0) add("audioTrack", "Audio sample tables", CheckStatus.PASS,
        "$checkedTracks audio track(s) had in-bounds stsz and chunk-offset tables")
    if (findingsTruncated) items += IntegrityCheckItem("m4a.findings.truncated", "Finding limit", CheckStatus.WARN,
        "More than $MAX_MP4_FINDINGS findings occurred; additional findings were omitted")
    return items.ifEmpty { listOf(IntegrityCheckItem("m4a.structure", "M4A structure", CheckStatus.SKIP, "No supported audio sample table was found")) }
}

private data class Mp4Sizes(val sampleCount: Long, val uniformSize: Long, val sampleSizes: List<Long>?)
private data class Mp4Chunks(val declaredCount: Long, val offsets: List<Long>, val capped: Boolean)

private fun readStsz(
    reader: ByteReader,
    node: BoxNode,
    add: (String, String, CheckStatus, String, BoxNode?) -> Unit,
): Mp4Sizes? {
    val start = node.offset + node.headerSize
    val end = checkedBoxEnd(node, reader)
    if (end == null || end - start < 12) {
        add("stsz.bounds", "stsz bounds", CheckStatus.FAIL, "stsz is too short for FullBox, sample_size, and sample_count", node)
        return null
    }
    val uniformSize = reader.readUInt32(start + 4)
    val sampleCount = reader.readUInt32(start + 8)
    val required = if (uniformSize == 0L) sampleCount * 4L else 0L
    if (required > end - (start + 12)) {
        add("stsz.bounds", "stsz bounds", CheckStatus.FAIL,
            "stsz declares $sampleCount variable-size entries but its box contains only ${(end - start - 12).coerceAtLeast(0) / 4} entries", node)
        return null
    }
    add("stsz.bounds", "stsz bounds", CheckStatus.PASS, "stsz sample-size table fits within its box", node)
    val sizes = if (uniformSize == 0L && sampleCount <= MAX_MP4_TABLE_ENTRIES) {
        List(sampleCount.toInt()) { index -> reader.readUInt32(start + 12 + index * 4L) }
    } else null
    return Mp4Sizes(sampleCount, uniformSize, sizes)
}

private fun readChunkOffsets(
    reader: ByteReader,
    node: BoxNode,
    add: (String, String, CheckStatus, String, BoxNode?) -> Unit,
): Mp4Chunks? {
    val start = node.offset + node.headerSize
    val end = checkedBoxEnd(node, reader)
    if (end == null || end - start < 8) {
        add("chunkTable.bounds", "Chunk-offset table bounds", CheckStatus.FAIL, "Chunk-offset table is too short for its FullBox header and count", node)
        return null
    }
    val width = if (node.type == "co64") 8 else 4
    val count = reader.readUInt32(start + 4)
    if (count > (Long.MAX_VALUE - 8) / width) {
        add("chunkTable.count", "Chunk-offset entry count", CheckStatus.FAIL, "Chunk-offset entry count overflows its byte extent", node)
        return null
    }
    val required = count * width
    if (required > end - (start + 8)) {
        add("chunkTable.bounds", "Chunk-offset table bounds", CheckStatus.FAIL,
            "${node.type} declares $count entries but the box does not contain them all", node)
        return null
    }
    val inspectCount = minOf(count, MAX_MP4_TABLE_ENTRIES)
    val offsets = ArrayList<Long>(inspectCount.toInt())
    for (index in 0 until inspectCount) {
        checkAudioScanActive()
        val offset = if (width == 4) reader.readUInt32(start + 8 + index * width) else reader.readUInt64(start + 8 + index * width)
        offsets += offset
        if (offset < 0 || offset >= reader.length) {
            add("chunkOffset", "Chunk offset", CheckStatus.FAIL,
                "Chunk ${index + 1} points to byte $offset outside file size ${reader.length}", node)
        }
    }
    if (count <= MAX_MP4_TABLE_ENTRIES) add("chunkTable.bounds", "Chunk-offset table bounds", CheckStatus.PASS,
        "$count ${node.type} offset(s) fit within the box and point inside the file", node)
    return Mp4Chunks(count, offsets, count > MAX_MP4_TABLE_ENTRIES)
}

private fun validateStsc(
    reader: ByteReader,
    node: BoxNode,
    chunks: Mp4Chunks,
    sizes: Mp4Sizes,
    mediaExtents: List<LongRange>,
    fragmented: Boolean,
    add: (String, String, CheckStatus, String, BoxNode?) -> Unit,
) {
    val start = node.offset + node.headerSize
    val end = checkedBoxEnd(node, reader)
    if (end == null || end - start < 8) {
        add("stsc.bounds", "stsc bounds", CheckStatus.FAIL, "stsc is too short for its FullBox header and entry count", node)
        return
    }
    val count = reader.readUInt32(start + 4)
    if (count == 0L && fragmented) {
        add("stsc.fragmented", "Fragmented sample mapping", CheckStatus.SKIP,
            "Empty stsc is valid for a fragmented initialization segment; moof sample extents are not verified", node)
        return
    }
    if (count == 0L || count > MAX_MP4_TABLE_ENTRIES || count * 12L > end - start - 8) {
        add("stsc.bounds", "stsc bounds", if (count > MAX_MP4_TABLE_ENTRIES) CheckStatus.WARN else CheckStatus.FAIL,
            "stsc entry count $count is empty, exceeds the verification cap, or extends past the box", node)
        return
    }
    val entries = ArrayList<StscEntry>(count.toInt())
    for (i in 0 until count.toInt()) {
        checkAudioScanActive()
        val at = start + 8 + i * 12L
        entries += StscEntry(reader.readUInt32(at), reader.readUInt32(at + 4), reader.readUInt32(at + 8))
    }
    var valid = entries.first().firstChunk == 1L
    for (i in entries.indices) {
        checkAudioScanActive()
        val entry = entries[i]
        if (entry.firstChunk < 1 || entry.samplesPerChunk < 1 || entry.sampleDescription < 1 ||
            (i > 0 && entry.firstChunk <= entries[i - 1].firstChunk) || entry.firstChunk > chunks.declaredCount) valid = false
    }
    if (!valid) {
        add("stsc.mapping", "Sample-to-chunk mapping", CheckStatus.FAIL,
            "stsc entries must start at chunk 1, increase strictly, and have non-zero sample and description counts", node)
        return
    }
    var mappedSamples = 0L
    for (i in entries.indices) {
        checkAudioScanActive()
        val firstChunk = entries[i].firstChunk
        val lastChunkExclusive = if (i + 1 < entries.size) entries[i + 1].firstChunk else chunks.declaredCount + 1
        val chunkRun = (lastChunkExclusive - firstChunk).coerceAtLeast(0)
        val perChunk = entries[i].samplesPerChunk
        if (chunkRun > 0 && perChunk > Long.MAX_VALUE / chunkRun) {
            add("stsc.mapping", "Sample-to-chunk mapping", CheckStatus.FAIL, "stsc sample coverage overflows", node)
            return
        }
        val mappedRun = chunkRun * perChunk
        if (mappedRun > Long.MAX_VALUE - mappedSamples) {
            add("stsc.mapping", "Sample-to-chunk mapping", CheckStatus.FAIL, "stsc sample coverage overflows", node)
            return
        }
        mappedSamples += mappedRun
    }
    if (mappedSamples != sizes.sampleCount) {
        add("stsc.mapping", "Sample-to-chunk mapping", CheckStatus.FAIL,
            "stsc maps $mappedSamples sample(s), but stsz declares ${sizes.sampleCount}", node)
        return
    }
    if (sizes.sampleCount > MAX_MP4_TABLE_ENTRIES || chunks.capped || sizes.sampleSizes == null && sizes.uniformSize == 0L) {
        add("sampleExtent", "Sample byte extents", CheckStatus.WARN,
            "Sample-size or chunk-offset table exceeds the verification cap; complete mdat extents were not checked", node)
    } else {
        var sampleIndex = 0
        var extentValid = true
        entries.forEachIndexed { entryIndex, entry ->
            val firstChunk = entry.firstChunk
            val lastChunkExclusive = if (entryIndex + 1 < entries.size) entries[entryIndex + 1].firstChunk else chunks.declaredCount + 1
            for (chunkNumber in firstChunk until lastChunkExclusive) {
                checkAudioScanActive()
                val offset = chunks.offsets.getOrNull((chunkNumber - 1).toInt()) ?: continue
                var byteCount = 0L
                repeat(entry.samplesPerChunk.toInt()) {
                    val sampleSize = if (sizes.uniformSize > 0) sizes.uniformSize else sizes.sampleSizes?.getOrNull(sampleIndex) ?: 0L
                    if (sampleSize > Long.MAX_VALUE - byteCount) { extentValid = false; return@repeat }
                    byteCount += sampleSize
                    sampleIndex++
                }
                val endOffset = if (byteCount > Long.MAX_VALUE - offset) Long.MAX_VALUE else offset + byteCount
                val inMdat = mediaExtents.any { extent -> offset >= extent.first && endOffset <= extent.last + 1 }
                if (!inMdat) {
                    extentValid = false
                    add("sampleExtent", "Sample byte extents", CheckStatus.FAIL,
                        "Chunk $chunkNumber sample bytes [$offset, $endOffset) do not fit inside an mdat payload", node)
                }
            }
        }
        if (extentValid) add("sampleExtent", "Sample byte extents", CheckStatus.PASS,
            "All mapped sample byte ranges fit inside mdat payloads", node)
    }
    add("stsc.mapping", "Sample-to-chunk mapping", CheckStatus.PASS,
        "stsc maps all ${sizes.sampleCount} samples across ${chunks.declaredCount} chunk(s)", node)
}

private data class StscEntry(val firstChunk: Long, val samplesPerChunk: Long, val sampleDescription: Long)

private fun checkedBoxEnd(node: BoxNode, reader: ByteReader): Long? {
    val fileLength = reader.length
    if (node.offset < 0 || node.headerSize < 8 || node.size < node.headerSize || node.offset > fileLength - node.headerSize) return null
    val size32 = reader.readUInt32(node.offset)
    val declaredSize = when (size32) {
        0L -> fileLength - node.offset
        1L -> {
            if (node.headerSize < 16) return null
            reader.readUInt64(node.offset + 8).takeIf { it >= 0 } ?: return null
        }
        else -> size32
    }
    if (declaredSize < node.headerSize || node.offset > Long.MAX_VALUE - declaredSize || declaredSize > node.size) return null
    val end = node.offset + declaredSize
    return end.takeIf { it <= fileLength }
}

private fun descendants(root: BoxNode): Sequence<BoxNode> = sequence {
    yield(root)
    root.children.forEach { yieldAll(descendants(it)) }
}
