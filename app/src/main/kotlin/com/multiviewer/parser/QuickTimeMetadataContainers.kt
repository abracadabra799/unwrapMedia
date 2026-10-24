package com.multiviewer.parser

object QuickTimeKeysBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        var state = if (w.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE
        fun node(summary: String? = null) = BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary, interpretation = state)
        if (!validQuickTimeRange(reader, offset, headerSize, size, 8)) {
            w += "Truncated QuickTime keys header / QuickTime keys 헤더 잘림"; state = ValueInterpretation.UNPARSABLE; return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        val flags = reader.readUInt32(start) and 0xffffff
        val count = reader.readUInt32(start + 4)
        fields += listOf(BoxField("version", version.toString(), start, 1),
            BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", start + 1, 3), BoxField("entry_count", count.toString(), start + 4, 4))
        if (version != 0 || flags != 0L) {
            w += "Unsupported QuickTime keys version/flags / 미지원 keys 버전 또는 플래그"; state = ValueInterpretation.UNPARSABLE; return node()
        }
        var pos = start + 8
        var index = 1L
        var characters = 0L
        while (index <= count) {
            if (end - pos < 8) { w += "Truncated QuickTime key / QuickTime 키 잘림"; state = ValueInterpretation.UNPARSABLE; break }
            if (index > 4096 || characters >= 1_048_576) {
                w += "QuickTime key inspection limit / QuickTime 키 검사 한도"; state = quickTimeState(state, ValueInterpretation.UNINTERPRETED); break
            }
            val length = reader.readUInt32(pos)
            if (length < 8 || length > end - pos) { w += "Invalid QuickTime key size / QuickTime 키 길이 오류"; state = ValueInterpretation.UNPARSABLE; break }
            val namespace = reader.readFourCC(pos + 4)
            val value = if (namespace == "mdta") readQuickTimeValue(reader, 1, pos + 8, length - 8)
                else quickTimeRawValue(reader, pos + 8, length - 8, ValueInterpretation.UNINTERPRETED, "Unsupported key namespace / 미지원 키 namespace")
            value.warning?.let { w += it }
            state = quickTimeState(state, value.state)
            fields += BoxField("key[$index]", "$namespace:${value.value}", pos, length, value.state)
            fields += BoxField("key[$index].size", length.toString(), pos, 4)
            fields += BoxField("key[$index].namespace", namespace, pos + 4, 4)
            fields += BoxField("key[$index].key", value.value, pos + 8, length - 8, value.state)
            characters += value.value.length
            pos += length
            index++
        }
        if (pos < end) {
            val unparsed = if (state == ValueInterpretation.UNPARSABLE) state else ValueInterpretation.UNINTERPRETED
            fields += BoxField("unparsed_keys_bytes", (end - pos).toString(), pos, end - pos, unparsed)
            state = quickTimeState(state, unparsed)
        }
        return node("$count keys")
    }
}

private class QuickTimeItemIssue(message: String) : RuntimeException(message)
private data class QuickTimeItemSpan(val type: String, val header: Int, val size: Long)
private fun quickTimeItemSpan(reader: ByteReader, pos: Long, end: Long): QuickTimeItemSpan {
    if (end - pos < 8) throw QuickTimeItemIssue("Truncated metadata item header / 메타데이터 항목 헤더 잘림")
    var size = reader.readUInt32(pos)
    val type = reader.readFourCC(pos + 4)
    var header = 8
    if (size == 1L) {
        if (end - pos < 16) throw QuickTimeItemIssue("Truncated extended metadata header / 확장 메타데이터 헤더 잘림")
        size = reader.readUInt64(pos + 8); header = 16
    } else if (size == 0L) size = end - pos
    if (size < header || size > end - pos) throw QuickTimeItemIssue("Invalid metadata item extent / 메타데이터 항목 범위 오류")
    return QuickTimeItemSpan(type, header, size)
}

object QuickTimeIlstBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        val items = mutableListOf<BoxNode>()
        var state = if (w.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE
        fun node() = BoxNode(type, offset, headerSize, size, fields = fields, children = items, warnings = w, summary = "${items.size} items", interpretation = state)
        if (!validQuickTimeRange(reader, offset, headerSize, size, 0)) {
            w += "Invalid ilst range / ilst 범위 오류"; state = ValueInterpretation.UNPARSABLE; return node()
        }
        val end = offset + size
        var pos = offset + headerSize
        var nodes = 0
        var characters = 0L
        var limited = false
        while (pos < end) {
            if (nodes >= 4096 || characters >= 1_048_576) { limited = true; break }
            val item = try { quickTimeItemSpan(reader, pos, end) } catch (e: QuickTimeItemIssue) {
                w += e.message.orEmpty(); state = ValueInterpretation.UNPARSABLE; break
            }
            nodes++
            val children = mutableListOf<BoxNode>()
            val itemFields = mutableListOf<BoxField>()
            val itemWarnings = mutableListOf<String>()
            val itemEnd = pos + item.size
            var childPos = pos + item.header
            var itemState = ValueInterpretation.PARSED
            while (childPos < itemEnd) {
                if (nodes >= 4096 || characters >= 1_048_576) { limited = true; itemState = quickTimeState(itemState, ValueInterpretation.UNINTERPRETED); break }
                val child = try { quickTimeItemSpan(reader, childPos, itemEnd) } catch (e: QuickTimeItemIssue) {
                    itemWarnings += e.message.orEmpty(); itemState = ValueInterpretation.UNPARSABLE; break
                }
                // Only data atoms have defined item payload semantics here; other atoms remain opaque.
                val decoder = if (child.type == "data") QuickTimeDataBoxDecoder else LeafBoxDecoder
                val decoded = decoder.decode(reader, child.type, childPos, child.header, child.size, emptyList())
                children += decoded
                itemState = quickTimeState(itemState, detailInterpretation(decoded))
                nodes++
                characters += decoded.fields.sumOf { it.value.length.toLong() }
                childPos += child.size
            }
            if (childPos < itemEnd) itemFields += BoxField("unparsed_item_bytes", (itemEnd - childPos).toString(), childPos, itemEnd - childPos, itemState)
            items += BoxNode(item.type, pos, item.header, item.size, fields = itemFields, children = children, warnings = itemWarnings, interpretation = itemState)
            state = quickTimeState(state, itemState)
            pos = itemEnd
            if (limited) break
        }
        if (limited) { w += "QuickTime item inspection limit / QuickTime 항목 검사 한도"; state = quickTimeState(state, ValueInterpretation.UNINTERPRETED) }
        if (pos < end) fields += BoxField("unparsed_ilst_bytes", (end - pos).toString(), pos, end - pos,
            if (state == ValueInterpretation.UNPARSABLE) state else ValueInterpretation.UNINTERPRETED)
        return node()
    }
}
