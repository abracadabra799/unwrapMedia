package com.multiviewer.parser

internal object FlacCueSheetDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode =
        decodeFlacMetadata(reader, type, offset, headerSize, size, warnings) { c ->
            c.text("media_catalog_number", 128, Charsets.US_ASCII, nulPadded = true, printableAscii = true)
            c.uint("lead_in_samples", 8)
            val flagOffset = c.pos
            val flags = c.uint("flags", 1).toInt()
            c.field("is_cd", (flags and 0x80 != 0).toString(), flagOffset, 1)
            c.reservedBits("reserved_bits", flags and 0x7f, flagOffset)
            c.reserved("reserved", 258)
            val count = c.uint("track_count", 1).toInt()
            if (count == 0) throw FlacMetadataIssue("CUESHEET has no lead-out track / CUESHEET lead-out 트랙 없음")
            repeat(count) {
                c.expand()
                c.requireBytes(36)
                val trackStart = c.pos
                val indices = reader.readUInt8(trackStart + 35)
                val trackSize = minOf(c.remaining, 36L + indices * 12L)
                val track = FlacMetadataCursor(reader, trackStart, trackStart + trackSize, c.budget)
                c.children += track.decode("CueTrack", trackStart, 36, trackSize) { t ->
                    t.uint("sample_offset", 8)
                    t.uint("track_number", 1)
                    t.text("isrc", 12, Charsets.US_ASCII, nulPadded = true, printableAscii = true)
                    val trackFlagOffset = t.pos
                    val trackFlags = t.uint("flags", 1).toInt()
                    t.field("track_type", (trackFlags ushr 7).toString(), trackFlagOffset, 1)
                    t.field("pre_emphasis", (trackFlags and 0x40 != 0).toString(), trackFlagOffset, 1)
                    t.reservedBits("reserved_bits", trackFlags and 0x3f, trackFlagOffset)
                    t.reserved("reserved", 13)
                    t.uint("index_count", 1)
                    repeat(indices) {
                        t.expand()
                        t.requireBytes(12)
                        val indexStart = t.pos
                        val index = FlacMetadataCursor(reader, indexStart, indexStart + 12, t.budget)
                        t.children += index.decode("CueIndex", indexStart, 0, 12) { i ->
                            i.uint("sample_offset", 8)
                            i.uint("index_number", 1)
                            i.reserved("reserved", 3)
                        }
                        t.pos += 12
                    }
                }
                c.pos += trackSize
            }
        }
}
