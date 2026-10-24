package com.multiviewer.parser

import com.multiviewer.parser.conformance.parseAacConfig

private class DescriptorBounds(message: String) : RuntimeException(message)

/** MPEG-4 descriptor fields; unknown descriptor contents remain explicitly raw. */
object EsdsBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader, type: String, offset: Long, headerSize: Int,
        size: Long, warnings: List<String>,
    ): BoxNode {
        val fields = mutableListOf<BoxField>()
        val w = warnings.toMutableList()
        fun node() = BoxNode(type, offset, headerSize, size, fields = fields.toList(), warnings = w.toList())
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length ||
            size > reader.length - offset || size - headerSize < 4 || size - headerSize > 1_048_576) {
            w += "Invalid or oversized esds / esds 범위 오류 또는 크기 제한 초과"
            return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${(reader.readUInt32(start) and 0xffffff).toString(16).padStart(6, '0')}", start + 1, 3)
        if (version != 0) {
            w += "Unsupported esds version / 미지원 esds 버전: $version"
            return node()
        }
        var descriptors = 0
        var aacRange: Pair<Long, Long>? = null
        fun walk(from: Long, limit: Long, depth: Int, audioConfig: Boolean = false) {
            if (depth > 8) throw DescriptorBounds("Descriptor nesting limit / 디스크립터 중첩 제한")
            var position = from
            while (position < limit) {
                if (++descriptors > 256) throw DescriptorBounds("Descriptor count limit / 디스크립터 개수 제한")
                val tagOffset = position
                val tag = reader.readUInt8(position++)
                val lengthOffset = position
                var length = 0L
                var terminated = false
                for (i in 0 until 4) {
                    if (position >= limit) throw DescriptorBounds("Truncated descriptor length / 디스크립터 길이 잘림")
                    val b = reader.readUInt8(position++)
                    length = (length shl 7) or (b and 127).toLong()
                    if (b and 128 == 0) { terminated = true; break }
                }
                if (!terminated || length > limit - position) throw DescriptorBounds("Invalid descriptor length / 디스크립터 길이 오류")
                val body = position
                val descriptorEnd = body + length
                fields += BoxField("descriptor[$descriptors].tag", "0x${tag.toString(16).padStart(2, '0')}", tagOffset, 1)
                fields += BoxField("descriptor[$descriptors].length", length.toString(), lengthOffset, body - lengthOffset)
                var cursor = body
                fun need(count: Long) {
                    if (count > descriptorEnd - cursor) throw DescriptorBounds("Truncated descriptor $tag / 디스크립터 잘림")
                }
                fun number(name: String, width: Int): Long {
                    need(width.toLong())
                    var value = 0L
                    repeat(width) { value = (value shl 8) or reader.readUInt8(cursor + it).toLong() }
                    fields += BoxField(name, value.toString(), cursor, width.toLong())
                    cursor += width
                    return value
                }
                fun raw(name: String, interpretation: ValueInterpretation = ValueInterpretation.UNINTERPRETED) {
                    val shown = minOf(64L, descriptorEnd - cursor).toInt()
                    val hex = reader.readBytes(cursor, shown).joinToString("") { "%02x".format(it.toInt() and 255) }
                    fields += BoxField(name, hex, cursor, shown.toLong(), interpretation)
                    if (shown.toLong() < descriptorEnd - cursor) fields += BoxField("$name.status", "Raw preview limited to 64 bytes / 원시 미리보기 64바이트 제한", cursor, 0)
                }
                when (tag) {
                    3 -> {
                        number("ES_ID", 2)
                        val flagOffset = cursor
                        val flags = number("ES_flags", 1).toInt()
                        fields += BoxField("streamPriority", (flags and 31).toString(), flagOffset, 1)
                        if (flags and 128 != 0) number("dependsOn_ES_ID", 2)
                        if (flags and 64 != 0) {
                            val urlLength = number("URLlength", 1)
                            need(urlLength)
                            fields += BoxField("URLstring", String(reader.readBytes(cursor, urlLength.toInt()), Charsets.UTF_8), cursor, urlLength)
                            cursor += urlLength
                        }
                        if (flags and 32 != 0) number("OCR_ES_Id", 2)
                        walk(cursor, descriptorEnd, depth + 1)
                    }
                    4 -> {
                        val oti = number("objectTypeIndication", 1)
                        val streamOffset = cursor
                        val packed = number("stream_flags", 1).toInt()
                        val streamType = packed shr 2
                        fields += BoxField("streamType", streamType.toString(), streamOffset, 1)
                        fields += BoxField("upStream", ((packed shr 1) and 1).toString(), streamOffset, 1)
                        number("bufferSizeDB", 3)
                        number("maxBitrate", 4)
                        number("avgBitrate", 4)
                        walk(cursor, descriptorEnd, depth + 1, oti == 0x40L && streamType == 5)
                    }
                    5 -> {
                        raw("decoderSpecificInfo", if (audioConfig) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED)
                        if (audioConfig && aacRange == null) aacRange = body to length
                    }
                    6 -> {
                        val predefined = number("SLConfig.predefined", 1)
                        if (cursor < descriptorEnd) raw("SLConfig.raw")
                        if (predefined == 0L) fields += BoxField("SLConfig.status", "Custom SL config is raw / 사용자 정의 SL 설정은 원시 데이터로 표시", body, 0)
                    }
                    else -> {
                        raw("descriptor[$descriptors].raw")
                        fields += BoxField("descriptor[$descriptors].status", "Unsupported descriptor $tag (raw) / 미지원 디스크립터 (원시 데이터)", body, 0)
                    }
                }
                position = descriptorEnd
            }
        }
        var valid = true
        try {
            if (start + 4 == end) throw DescriptorBounds("Missing ES descriptors / ES 디스크립터 없음")
            walk(start + 4, end, 0)
        } catch (e: DescriptorBounds) {
            w += e.message.orEmpty()
            valid = false
        }
        if (valid) aacRange?.let { (dataOffset, dataLength) ->
            // Derived AAC values share the bit-packed DecoderSpecificInfo source range.
            val config = parseAacConfig(reader, node())
            for ((name, value) in listOf(
                "audioObjectType" to config.aot,
                "samplingFrequencyIndex" to config.samplingFrequencyIndex,
                "sampleRate" to config.sampleRate,
                "channelConfiguration" to config.channelConfig,
                "extensionSampleRate" to config.extensionSampleRate,
            )) {
                value?.let { fields += BoxField(name, it.toString(), dataOffset, dataLength) }
            }
            // Semantic AAC issues are displayed as fields. Structural warnings are consumed by
            // conformance as a reason to stop; the AAC analyzer must still report its own FAIL.
            config.issue?.let { fields += BoxField("AAC_config_issue", it, dataOffset, dataLength, ValueInterpretation.UNPARSABLE) }
        }
        return node()
    }
}
