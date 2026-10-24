package com.multiviewer.parser

internal object FlacPictureDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        var summary: String? = null
        val node = decodeFlacMetadata(reader, type, offset, headerSize, size, warnings) { c ->
            val pictureType = c.uint("picture_type", 4)
            if (pictureType > 20) {
                c.fields[c.fields.lastIndex] = c.fields.last().copy(interpretation = ValueInterpretation.UNINTERPRETED)
                if (c.state == ValueInterpretation.PARSED) c.state = ValueInterpretation.UNINTERPRETED
            }
            val mimeLength = c.uint("mime_length", 4)
            val mime = c.text("mime", mimeLength, Charsets.US_ASCII, printableAscii = true)
            val descriptionLength = c.uint("description_length", 4)
            c.text("description", descriptionLength)
            val width = c.uint("width", 4)
            val height = c.uint("height", 4)
            c.uint("color_depth", 4)
            c.uint("num_colors", 4)
            val dataLength = c.uint("data_length", 4)
            if (dataLength == 0L) throw FlacMetadataIssue("Empty FLAC picture data / FLAC 이미지 데이터 없음")
            if (mime == "-->") c.text("picture_uri", dataLength, Charsets.US_ASCII, printableAscii = true)
            else c.raw("picture_data", dataLength)
            if (mime != null) summary = "$mime, ${width}x$height"
        }
        return node.copy(summary = summary)
    }
}
