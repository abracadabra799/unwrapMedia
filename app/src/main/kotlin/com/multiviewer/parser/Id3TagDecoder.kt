package com.multiviewer.parser

internal fun decodeId3Tag(reader: ByteReader, start: Long, end: Long): BoxNode {
    val fields = mutableListOf<BoxField>()
    val warnings = mutableListOf<String>()
    val children = mutableListOf<BoxNode>()
    var state = ValueInterpretation.PARSED
    fun adopt(value: ValueInterpretation) {
        if (value == ValueInterpretation.UNPARSABLE || state == ValueInterpretation.PARSED) state = value
    }
    val actualEnd = minOf(end, reader.length)
    if (start < 0 || start > actualEnd || actualEnd - start < 10) {
        return BoxNode("ID3v2", start, 0, maxOf(0, actualEnd - start),
            warnings = listOf("Truncated ID3 header / ID3 헤더 잘림"), interpretation = ValueInterpretation.UNPARSABLE)
    }
    if (end > reader.length) { warnings += "ID3 range exceeds file / ID3 범위가 파일 초과"; adopt(ValueInterpretation.UNPARSABLE) }
    val major = reader.readUInt8(start + 3)
    val revision = reader.readUInt8(start + 4)
    val flags = reader.readUInt8(start + 5)
    fields += BoxField("version", "2.$major.$revision", start + 3, 2)
    fields += BoxField("flags", "0x${flags.toString(16).padStart(2, '0')}", start + 5, 1)
    val sizeView = Id3Payload(reader, start + 6, 4, false)
    val tagSize = try { sizeView.syncsafe(0) } catch (e: Id3DecodeIssue) {
        return BoxNode("ID3v2", start, 10, 10, fields = fields, warnings = listOf(e.message.orEmpty()), interpretation = ValueInterpretation.UNPARSABLE)
    }
    fields += BoxField("tag_size", tagSize.toString(), start + 6, 4)
    val bodyStart = start + 10
    val dataEnd = bodyStart + minOf(tagSize, actualEnd - bodyStart)
    val bodyTruncated = tagSize > actualEnd - bodyStart
    if (bodyTruncated) { warnings += "Truncated ID3 tag body / ID3 태그 본문 잘림"; adopt(ValueInterpretation.UNPARSABLE) }
    val footerPresent = major == 4 && flags and 0x10 != 0
    val fullEnd = if (footerPresent && !bodyTruncated) dataEnd + minOf(10L, actualEnd - dataEnd) else dataEnd
    if (major in 2..4) fields += BoxField("unsynchronisation", (flags and 128 != 0).toString(), start + 5, 1)
    if (major in 3..4) fields += BoxField("extended_header", (flags and 64 != 0).toString(), start + 5, 1)
    if (major == 4) fields += BoxField("footer_present", footerPresent.toString(), start + 5, 1)
    fun unparsed(pos: Long, interpretation: ValueInterpretation) {
        adopt(interpretation)
        if (pos < dataEnd) fields += BoxField("unparsed_tag_bytes", (dataEnd - pos).toString(), pos, dataEnd - pos, interpretation)
    }
    var pos = bodyStart
    when {
        reader.readFourCC(start).take(3) != "ID3" -> { warnings += "Missing ID3 signature / ID3 서명 없음"; unparsed(pos, ValueInterpretation.UNPARSABLE) }
        major == 2 -> { warnings += "ID3v2.2 frames are uninterpreted / ID3v2.2 프레임 미해석"; unparsed(pos, ValueInterpretation.UNINTERPRETED) }
        major !in 3..4 -> { warnings += "Unsupported ID3 version / 미지원 ID3 버전"; unparsed(pos, ValueInterpretation.UNPARSABLE) }
        flags and (if (major == 3) 0x1f else 0x0f) != 0 -> { warnings += "Unknown ID3 tag flags / 미지원 ID3 태그 플래그"; unparsed(pos, ValueInterpretation.UNPARSABLE) }
        major == 3 && flags and 128 != 0 -> { warnings += "Tag-wide unsynchronisation is uninterpreted / 태그 전체 동기화 제거 미해석"; unparsed(pos, ValueInterpretation.UNINTERPRETED) }
        else -> try {
            if (flags and 64 != 0) {
                val ext = decodeId3ExtendedHeader(reader, pos, dataEnd, major)
                children += ext
                adopt(detailInterpretation(ext))
                if (detailInterpretation(ext) == ValueInterpretation.UNPARSABLE) throw Id3DecodeIssue("Cannot parse extended header / 확장 헤더 파싱 불가")
                pos += ext.size
            }
            var frameCount = 0
            var characters = 0L
            while (pos < dataEnd && frameCount < 4096 && characters < 1_048_576) {
                if (reader.readUInt8(pos) == 0) {
                    val shown = minOf(dataEnd - pos, 65_536L).toInt()
                    if (reader.readBytes(pos, shown).any { it != 0.toByte() }) throw Id3DecodeIssue("Nonzero ID3 padding / ID3 패딩 오류")
                    fields += BoxField("padding_bytes", shown.toString(), pos, shown.toLong())
                    pos += shown
                    if (pos < dataEnd) { warnings += "ID3 padding inspection limit / ID3 패딩 검사 한도"; unparsed(pos, ValueInterpretation.UNINTERPRETED) }
                    break
                }
                if (dataEnd - pos < 10) throw Id3DecodeIssue("Truncated ID3 frame header / ID3 프레임 헤더 잘림")
                val id = reader.readFourCC(pos)
                if (id.any { it !in 'A'..'Z' && it !in '0'..'9' }) throw Id3DecodeIssue("Invalid ID3 frame ID / ID3 프레임 ID 오류")
                val frameSize = if (major == 4) Id3Payload(reader, pos + 4, 4, false).syncsafe(0) else reader.readUInt32(pos + 4)
                if (frameSize > dataEnd - pos - 10) throw Id3DecodeIssue("ID3 frame exceeds tag / ID3 프레임이 태그 범위 초과")
                val frame = decodeId3Frame(reader, id, pos, frameSize, major, flags and 128 != 0)
                children += frame
                adopt(detailInterpretation(frame))
                characters += frame.fields.sumOf { it.value.length.toLong() }
                pos += 10 + frameSize
                frameCount++
            }
            if (pos < dataEnd && (frameCount >= 4096 || characters >= 1_048_576)) {
                warnings += "ID3 frame inspection limit / ID3 프레임 검사 한도"
                unparsed(pos, ValueInterpretation.UNINTERPRETED)
            }
        } catch (e: Id3DecodeIssue) {
            warnings += e.message.orEmpty()
            unparsed(pos, e.state)
        }
    }
    if (footerPresent && !bodyTruncated) {
        val footerWarnings = mutableListOf<String>()
        val footerFields = mutableListOf<BoxField>()
        if (fullEnd - dataEnd < 10) footerWarnings += "Truncated ID3 footer / ID3 푸터 잘림"
        else {
            val bytes = reader.readBytes(dataEnd, 10)
            footerFields += BoxField("signature", String(bytes, 0, 3, Charsets.US_ASCII), dataEnd, 3)
            footerFields += BoxField("version", "2.${bytes[3].toInt() and 255}.${bytes[4].toInt() and 255}", dataEnd + 3, 2)
            val matches = bytes.copyOfRange(0, 3).contentEquals("3DI".toByteArray()) && bytes.copyOfRange(3, 10).contentEquals(reader.readBytes(start + 3, 7))
            if (!matches) footerWarnings += "ID3 footer/header mismatch / ID3 푸터와 헤더 불일치"
        }
        children += BoxNode("ID3Footer", dataEnd, 0, fullEnd - dataEnd, fields = footerFields, warnings = footerWarnings,
            interpretation = if (footerWarnings.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE)
        if (footerWarnings.isNotEmpty()) adopt(ValueInterpretation.UNPARSABLE)
    }
    return BoxNode("ID3v2", start, 10, fullEnd - start, fields = fields, children = children, warnings = warnings,
        summary = "ID3v2.$major.$revision tag", interpretation = state)
}
