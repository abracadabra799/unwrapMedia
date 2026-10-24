package com.multiviewer.parser

internal object FlacVorbisCommentDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        var count: Long? = null
        val node = decodeFlacMetadata(reader, type, offset, headerSize, size, warnings) { c ->
            val vendorLength = c.uint("vendor_length", 4, littleEndian = true)
            c.text("vendor", vendorLength)
            val commentCount = c.uint("comment_count", 4, littleEndian = true)
            count = commentCount
            if (commentCount > c.remaining / 4) throw FlacMetadataIssue("Impossible Vorbis comment count / Vorbis 태그 개수가 블록 범위를 초과")
            var index = 0L
            while (index < commentCount) {
                // Check structure before the display cap, so missing records are not reported as a cap.
                c.requireBytes(4)
                c.expand()
                val length = c.uint("comment[$index].length", 4, littleEndian = true)
                val entryStart = c.pos
                val entry = c.text("comment[$index]", length)
                if (entry != null) {
                    val equals = entry.indexOf('=')
                    if (equals < 0 || entry.take(equals).any { it.code !in 0x20..0x7e }) {
                        val field = c.fields.removeAt(c.fields.lastIndex)
                        c.fields += field.copy(interpretation = ValueInterpretation.UNPARSABLE)
                        c.state = ValueInterpretation.UNPARSABLE
                        c.warnings += "Invalid Vorbis field name or separator / Vorbis 필드 이름 또는 구분자 오류"
                    } else {
                        c.fields.removeAt(c.fields.lastIndex)
                        val name = entry.substring(0, equals)
                        // Valid field names are ASCII, so their character and byte offsets coincide.
                        c.field("comment[$index].name", name, entryStart, equals.toLong())
                        c.field(name, entry.substring(equals + 1), entryStart + equals + 1, length - equals - 1)
                    }
                }
                index++
            }
        }
        return node.copy(summary = count?.let { "$it comment(s)" })
    }
}
