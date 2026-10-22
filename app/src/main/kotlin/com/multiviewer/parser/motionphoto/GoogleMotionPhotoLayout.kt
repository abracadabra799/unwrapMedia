package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat

internal data class GmpBox(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    /** The size field as written: 0 means "to end of file" (forbidden for mpvd). */
    val rawSize: Long,
    /** The box claims more bytes than remain before the walk limit. */
    val truncated: Boolean,
) {
    val end: Long get() = offset + size
}

/**
 * Consecutive ISOBMFF boxes in [start, limit). Stops at the first header that is not a box (e.g. a SEF
 * trailer after an MP4); an overrunning box is kept with truncated=true and ends the walk.
 */
internal fun walkBoxes(reader: ByteReader, start: Long, limit: Long): List<GmpBox> {
    val boxes = mutableListOf<GmpBox>()
    var pos = start
    while (pos + 8 <= limit) {
        val raw = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        if (!type.all { it in ' '..'~' }) break
        var header = 8
        val size = when (raw) {
            1L -> {
                if (pos + 16 > limit) break
                header = 16
                reader.readUInt64(pos + 8)
            }
            0L -> limit - pos
            else -> raw
        }
        if (size < header) break
        if (pos + size > limit) {
            boxes += GmpBox(type, pos, header, size, raw, truncated = true)
            break
        }
        boxes += GmpBox(type, pos, header, size, raw, truncated = false)
        pos += size
    }
    return boxes
}

/** End (exclusive) of the primary JPEG: through the EOI that closes its scan data. Null when the markers can't be walked. */
internal fun primaryJpegEnd(reader: ByteReader): Long? {
    val length = reader.length
    if (length < 4 || reader.readUInt8(0) != 0xFF || reader.readUInt8(1) != 0xD8) return null
    var pos = 2L
    while (pos + 2 <= length) {
        if (reader.readUInt8(pos) != 0xFF) return null
        val marker = reader.readUInt8(pos + 1)
        if (marker == 0xFF) { pos++; continue }
        if (marker == 0xD9) return pos + 2
        if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
        if (pos + 4 > length) return null
        val segmentLength = reader.readUInt16(pos + 2)
        if (segmentLength < 2) return null
        pos += 2 + segmentLength
        if (marker == 0xDA) pos = endOfEntropyData(reader, pos) ?: return null
    }
    return null
}

// Entropy-coded data runs until a marker other than FF00 stuffing, RSTn or FF fill; returns that marker's offset.
private fun endOfEntropyData(reader: ByteReader, start: Long): Long? {
    val length = reader.length
    var pos = start
    while (pos < length - 1) {
        val chunk = reader.readBytes(pos, minOf(65536L, length - pos).toInt())
        for (i in 0 until chunk.size - 1) {
            if (chunk[i] == 0xFF.toByte()) {
                val next = chunk[i + 1].toInt() and 0xFF
                if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) return pos + i
            }
        }
        pos += chunk.size - 1 // the last byte is re-read with its successor
    }
    return null
}

private fun isFtyp(reader: ByteReader, offset: Long) =
    offset >= 0 && offset + 12 <= reader.length && reader.readFourCC(offset + 4) == "ftyp"

// Nearest video ftyp within ±1024 bytes of the declared start. Offset 0 is skipped: in HEIC/AVIF it is the
// image's own ftyp, which a first-match search (correctMp4StartOffset) would wrongly return.
private fun nearestVideoFtyp(reader: ByteReader, declaredStart: Long): Long? {
    for (distance in 1L..1024L) {
        for (candidate in longArrayOf(declaredStart - distance, declaredStart + distance)) {
            if (candidate > 0 && isFtyp(reader, candidate)) return candidate
        }
    }
    return null
}

/** §3.2–3.4 byte layout and §4 mpvd rules. Never throws on malformed input: an unreadable file becomes a SKIP. */
internal fun checkLayout(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>) {
    try {
        layoutRules(input, xmp, reader, out)
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (e: Exception) {
        out += specSkip("gmp.layout", "3.3", SpecRequirement.MUST, "항목 배치 / Item layout",
            "파일을 읽는 중 오류가 발생했습니다: ${e.message ?: e.toString()}")
    }
}

private fun layoutRules(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>) {
    val fileLength = reader.length
    val isobmff = input.format == ContainerFormat.HEIF || input.format == ContainerFormat.AVIF
    val topLevel = if (isobmff) walkBoxes(reader, 0, fileLength) else emptyList()
    val mpvd = topLevel.firstOrNull { it.type == "mpvd" }
    if (isobmff) checkMpvdBox(topLevel, mpvd, fileLength, out)

    val items = xmp?.directory.orEmpty()
    val motionIndex = items.indexOfFirst { it.semantic == "MotionPhoto" }
    val motion = items.getOrNull(motionIndex)
    val length = motion?.length?.toLongOrNull()?.takeIf { it > 0 }
    if (motion == null || length == null) {
        out += specSkip("gmp.layout", "3.3", SpecRequirement.MUST, "항목 배치 / Item layout",
            "유효한 Item:Length를 가진 MotionPhoto 항목이 없어 영상 위치·배치 규칙을 평가하지 않았습니다")
        return
    }
    val startTitle = "영상 시작 위치 / Video start"
    if (length > fileLength) {
        out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, false, startTitle,
            "MotionPhoto Item:Length=$length 가 파일 크기 $fileLength 보다 큽니다")
        return
    }
    val declaredStart = fileLength - length
    val ftypAtDeclared = isFtyp(reader, declaredStart)
    val videoStart = if (ftypAtDeclared) declaredStart
    else nearestVideoFtyp(reader, declaredStart)
    out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, ftypAtDeclared, startTitle,
        when {
            ftypAtDeclared -> "파일 끝 − Item:Length($length) = $declaredStart 에 영상 ftyp가 있습니다"
            videoStart != null -> "파일 끝 − Item:Length = $declaredStart 이지만 실제 영상 ftyp는 ${videoStart - declaredStart}바이트 떨어진 $videoStart 에 있습니다 — 항목 바이트는 영상 파일 원본이어야 합니다"
            else -> "파일 끝 − Item:Length = $declaredStart 근처(±1024바이트)에서 영상 ftyp를 찾지 못했습니다"
        })

    val mimeTitle = "영상 Item:Mime / Video Item:Mime"
    if (videoStart != null) {
        val brand = reader.readFourCC(videoStart + 8).trim()
        val expected = if (brand == "qt") "video/quicktime" else "video/mp4"
        out += spec("gmp.item.video_mime", "3.4", SpecRequirement.MUST, motion.mime == expected, mimeTitle,
            "Item:Mime=\"${motion.mime ?: "없음"}\", 실제 ftyp major_brand=\"$brand\" → \"$expected\"")
    } else {
        out += specSkip("gmp.item.video_mime", "3.4", SpecRequirement.MUST, mimeTitle, "영상 ftyp를 찾지 못했습니다")
    }

    val packedTitle = "빈틈없는 배치 / Tightly packed"
    val primaryEnd = if (isobmff) mpvd?.offset else primaryJpegEnd(reader)
    if (primaryEnd == null || motionIndex < 1) {
        out += specSkip("gmp.layout.packed", "3.2", SpecRequirement.MUST, packedTitle,
            if (primaryEnd == null) "기본 이미지의 끝을 확인할 수 없습니다" else "MotionPhoto가 첫 항목입니다")
    } else {
        val padding = items[0].padding?.toLongOrNull() ?: 0L
        val between = items.subList(1, motionIndex).sumOf { it.length?.toLongOrNull()?.coerceAtLeast(0) ?: 0L }
        val expected = primaryEnd + padding + between
        out += spec("gmp.layout.packed", "3.2", SpecRequirement.MUST, expected == declaredStart, packedTitle,
            "기본 이미지 끝 $primaryEnd + Padding $padding" + (if (between > 0) " + 앞 항목 Length $between" else "") +
                " = $expected, 파일 끝 − Item:Length = $declaredStart" +
                if (expected == declaredStart) "" else " — ${declaredStart - expected}바이트 차이")
    }

    val lastTitle = "영상이 파일 끝 / Video at end of file"
    if (videoStart != null) {
        val limit = if (mpvd != null && videoStart in mpvd.offset until mpvd.end) mpvd.end.coerceAtMost(fileLength) else fileLength
        val boxes = walkBoxes(reader, videoStart, limit)
        val truncated = boxes.lastOrNull()?.takeIf { it.truncated }
        val videoEnd = boxes.lastOrNull()?.let { if (it.truncated) it.offset else it.end } ?: videoStart
        val trailing = fileLength - videoEnd
        val sef = trailing > 0 && fileLength >= 4 && reader.readFourCC(fileLength - 4) == "SEFT"
        out += spec("gmp.layout.video_last", "3.4", SpecRequirement.MUST, truncated == null && trailing == 0L, lastTitle,
            when {
                truncated != null -> "영상의 ${truncated.type} 박스(size ${truncated.size})가 $limit 을 넘어 잘렸습니다"
                trailing == 0L -> "영상 컨테이너가 파일 끝($fileLength)에서 끝납니다"
                else -> "영상 컨테이너가 $videoEnd 에서 끝나고 뒤에 ${trailing}바이트가 더 있습니다" +
                    (if (sef) " (삼성 SEF 트레일러)" else "") + " — MotionPhoto 항목 뒤에는 어떤 바이트도 올 수 없습니다"
            })
    } else {
        out += specSkip("gmp.layout.video_last", "3.4", SpecRequirement.MUST, lastTitle, "영상 ftyp를 찾지 못했습니다")
    }

    if (input.xmpText?.contains(HDR_GAINMAP_NS) == true) {
        val gainIndex = items.indexOfFirst { it.semantic == "GainMap" }
        out += spec("gmp.gainmap.item", "3.4", SpecRequirement.MUST, gainIndex in 1 until motionIndex, "GainMap 항목 / GainMap item",
            when {
                gainIndex < 0 -> "게인맵(hdrgm) 이미지인데 GainMap 항목이 없습니다"
                gainIndex > motionIndex -> "GainMap 항목(#${gainIndex + 1})이 MotionPhoto 항목(#${motionIndex + 1}) 뒤에 있습니다"
                else -> "GainMap 항목 #${gainIndex + 1}이 MotionPhoto 항목 앞에 있습니다"
            })
    }

    if (mpvd != null && !mpvd.truncated) {
        val payload = mpvd.size - mpvd.headerSize
        out += spec("gmp.mpvd.length", "4", SpecRequirement.MUST, payload == length, "mpvd 데이터 = 영상 Length / mpvd payload = Length",
            "mpvd 데이터 $payload 바이트, MotionPhoto Item:Length $length" + if (payload == length) "" else " — ${length - payload}바이트 차이")
    }
}

private fun checkMpvdBox(topLevel: List<GmpBox>, mpvd: GmpBox?, fileLength: Long, out: MutableList<GoogleSpecFinding>) {
    out += spec("gmp.mpvd.present", "4", SpecRequirement.MUST, mpvd != null, "최상위 mpvd 박스 / Top-level mpvd box",
        if (mpvd != null) "mpvd @ ${mpvd.offset}, size ${mpvd.size}"
        else "최상위 mpvd 박스가 없습니다 (최상위 박스: ${topLevel.joinToString { it.type }})")
    if (mpvd == null) return
    out += spec("gmp.mpvd.size", "4", SpecRequirement.MUST, mpvd.rawSize != 0L && !mpvd.truncated, "mpvd 크기 / mpvd size",
        when {
            mpvd.rawSize == 0L -> "mpvd size 필드가 0입니다 (스펙상 금지)"
            mpvd.truncated -> "mpvd(size ${mpvd.size})가 파일 끝($fileLength)을 넘습니다"
            else -> "size ${mpvd.size}"
        })
    val after = topLevel.filter { it.offset > mpvd.offset }
    val trailingBytes = fileLength - mpvd.end
    out += spec("gmp.mpvd.last", "4", SpecRequirement.MUST, after.isEmpty() && trailingBytes <= 0, "mpvd가 마지막 박스 / mpvd is the last box",
        when {
            after.isNotEmpty() -> "mpvd 뒤에 박스가 있습니다: ${after.joinToString { "${it.type}(${it.size})" }}"
            trailingBytes > 0 -> "mpvd 뒤에 박스가 아닌 ${trailingBytes}바이트가 있습니다"
            else -> "mpvd가 파일의 마지막 박스입니다"
        })
}
