package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS

/** TIFF and the TIFF-based RAW formats (CR2, NEF, ARW, DNG). Walks IFDs from raw bytes. */
object TiffIntegrity {
    private const val MAX_IFDS = 512
    private const val MAX_ENTRIES = 4096
    private const val MAX_VALUES = 1_000_000L
    private val TYPE_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8, 13 to 4, 16 to 8)
    private val INTEGER_TYPES = setOf(1, 3, 4, 13)

    private class Walk(
        val items: List<IntegrityCheckItem>,
        val width: Int?,
        val height: Int?,
        val jpegCandidates: List<Pair<Long, Long>>,
    )

    fun check(reader: ByteReader): FormatCheckResult = walk(reader).let { FormatCheckResult(it.items, it.width, it.height) }

    /** (offset, length) of the largest embedded JPEG that is not lossless (SOF3) — a RAW file's preview. */
    fun largestJpegPreview(reader: ByteReader): Pair<Long, Long>? =
        walk(reader).jpegCandidates.distinct()
            .filter { (off, n) ->
                n >= 4 && off + n <= reader.length &&
                    reader.readUInt8(off) == 0xFF && reader.readUInt8(off + 1) == 0xD8 &&
                    jpegSofMarker(reader, off, n).let { it != null && it != 0xC3 }
            }
            .maxByOrNull { it.second }

    internal fun jpegSofMarker(reader: ByteReader, start: Long, length: Long): Int? {
        var p = start + 2
        val end = start + length
        while (p + 4 <= end) {
            if (reader.readUInt8(p) != 0xFF) return null
            val m = reader.readUInt8(p + 1)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return m
            if (m == 0xDA || m == 0xD9) return null
            p += 2 + reader.readUInt16(p + 2)
        }
        return null
    }

    private fun walk(reader: ByteReader): Walk {
        val len = reader.length
        if (len < 8) {
            return Walk(listOf(IntegrityCheckItem("tiff.header", "TIFF header", FAIL, "The file is too short ($len bytes) for a TIFF header", 0, len)), null, null, emptyList())
        }
        val le = reader.readUInt8(0) == 0x49
        fun u16(off: Long): Int = reader.readBytes(off, 2).let { if (le) it.u16le(0) else it.u16be(0) }
        fun u32(off: Long): Long = reader.readBytes(off, 4).let { if (le) it.u32le(0) else it.u32be(0) }

        val problems = mutableListOf<IntegrityCheckItem>()
        val visited = HashSet<Long>()
        val candidates = mutableListOf<Pair<Long, Long>>()
        var rangeCount = 0
        var width: Int? = null
        var height: Int? = null

        fun readValues(entry: Long): List<Long>? {
            val type = u16(entry + 2)
            val count = u32(entry + 4)
            val size = TYPE_SIZES[type] ?: return null
            if (type !in INTEGER_TYPES || count > MAX_VALUES) return null
            val total = size * count
            val dataOff = if (total <= 4) entry + 8 else u32(entry + 8)
            if (dataOff + total > len) return null
            return (0 until count.toInt()).map { i ->
                when (size) {
                    1 -> reader.readUInt8(dataOff + i).toLong()
                    2 -> u16(dataOff + 2L * i).toLong()
                    else -> u32(dataOff + 4L * i)
                }
            }
        }

        fun checkRanges(name: String, offsets: List<Long>?, counts: List<Long>?, kind: String, entry: Long?) {
            if (offsets == null && counts == null) return
            if (offsets == null || counts == null || offsets.size != counts.size) {
                problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                    "$name: ${kind}Offsets has ${offsets?.size ?: 0} value(s) but ${kind}ByteCounts has ${counts?.size ?: 0}", entry, 12)
                return
            }
            rangeCount += offsets.size
            val bad = offsets.indices.filter { offsets[it] + counts[it] > len }
            if (bad.isNotEmpty()) {
                val i = bad.first()
                problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                    "$name: ${bad.size} of ${offsets.size} $kind(s) exceed the file size $len; first is #$i at [${offsets[i]}, ${offsets[i] + counts[i]})",
                    offsets[i].coerceAtMost(len), (len - offsets[i]).coerceAtLeast(0))
            }
        }

        fun walkChain(start: Long, label: String, topLevel: Boolean) {
            var off = start
            var index = 0
            while (off != 0L) {
                val name = if (topLevel) "IFD$index" else if (index == 0) label else "$label+$index"
                if (visited.size >= MAX_IFDS) return
                if (off < 8 || off + 2 > len) {
                    problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL, "$name offset $off lies outside the file ($len bytes)")
                    return
                }
                if (!visited.add(off)) {
                    problems += IntegrityCheckItem("tiff.loop", "IFD loop", FAIL, "$name at offset $off was already visited (IFD loop)", off, 2)
                    return
                }
                val count = u16(off)
                val entriesEnd = off + 2 + 12L * count
                if (count > MAX_ENTRIES || entriesEnd + 4 > len) {
                    problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL,
                        "$name at offset $off declares $count entries that run past the end of the file", off, len - off)
                    return
                }
                val entries = (0 until count).associate { i -> val e = off + 2 + 12L * i; u16(e) to e }
                for ((tag, e) in entries) {
                    val size = TYPE_SIZES[u16(e + 2)] ?: continue
                    val total = size.toLong() * u32(e + 4)
                    if (total > 4) {
                        val d = u32(e + 8)
                        if (d + total > len) {
                            problems += IntegrityCheckItem("tiff.ifd", "IFD structure", FAIL,
                                "$name tag 0x%04x: value data [%d, %d) lies outside the file".format(tag, d, d + total), e, 12)
                        }
                    }
                }
                fun values(tag: Int) = entries[tag]?.let { readValues(it) }
                if (topLevel && index == 0) {
                    width = values(0x100)?.firstOrNull()?.toInt()
                    height = values(0x101)?.firstOrNull()?.toInt()
                }
                val stripOffsets = values(0x111)
                val stripCounts = values(0x117)
                checkRanges(name, stripOffsets, stripCounts, "strip", entries[0x111] ?: entries[0x117])
                checkRanges(name, values(0x144), values(0x145), "tile", entries[0x144] ?: entries[0x145])
                val jpegOff = values(0x201)?.firstOrNull()
                val jpegLen = values(0x202)?.firstOrNull()
                if (jpegOff != null && jpegLen != null) {
                    rangeCount++
                    if (jpegOff + jpegLen > len) {
                        problems += IntegrityCheckItem("tiff.data", "Image data ranges", FAIL,
                            "$name: JPEG preview [$jpegOff, ${jpegOff + jpegLen}) exceeds the file size $len", entries[0x201], 12)
                    } else {
                        candidates += jpegOff to jpegLen
                    }
                }
                val compression = values(0x103)?.firstOrNull()
                if ((compression == 6L || compression == 7L) && stripOffsets?.size == 1 && stripCounts?.size == 1 &&
                    stripOffsets[0] + stripCounts[0] <= len
                ) {
                    candidates += stripOffsets[0] to stripCounts[0]
                }
                values(0x14A)?.forEachIndexed { i, sub -> walkChain(sub, "$name/SubIFD$i", topLevel = false) }
                values(0x8769)?.firstOrNull()?.let { walkChain(it, "$name/ExifIFD", topLevel = false) }
                off = u32(entriesEnd)
                index++
            }
        }

        walkChain(u32(4), "IFD", topLevel = true)

        val structural = problems.filter { it.id == "tiff.ifd" || it.id == "tiff.loop" }
        val data = problems.filter { it.id == "tiff.data" }
        val items = structural.ifEmpty {
            listOf(IntegrityCheckItem("tiff.ifd", "IFD structure", PASS, "${visited.size} IFD(s), all within the file, no loops"))
        } + data.ifEmpty {
            listOf(IntegrityCheckItem("tiff.data", "Image data ranges", PASS, "$rangeCount strip/tile/preview range(s) within the file"))
        }
        return Walk(items, width, height, candidates)
    }
}
