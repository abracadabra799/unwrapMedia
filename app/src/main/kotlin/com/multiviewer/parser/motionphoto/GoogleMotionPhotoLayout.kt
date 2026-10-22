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
internal fun primaryJpegEnd(reader: ByteReader): Long? = jpegEnd(reader, 0)

/** End (exclusive) of the JPEG whose SOI is at [start], walking its markers to the EOI. Null when they can't be walked. */
internal fun jpegEnd(reader: ByteReader, start: Long): Long? {
    val length = reader.length
    if (start < 0 || start + 4 > length || reader.readUInt8(start) != 0xFF || reader.readUInt8(start + 1) != 0xD8) return null
    var pos = start + 2
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

/**
 * Ultra HDR gain map stored as a second JPEG right after the primary one (Samsung writes it without a GainMap
 * directory item): true when the bytes at [start] are an SOI and one of its APP1 segments before SOS declares
 * the hdrgm namespace. Reads at most the marker segments, never the scan data.
 */
internal fun embeddedJpegDeclaresGainMap(reader: ByteReader, start: Long): Boolean {
    val length = reader.length
    if (start < 0 || start + 4 > length || reader.readUInt8(start) != 0xFF || reader.readUInt8(start + 1) != 0xD8) return false
    val ns = HDR_GAINMAP_NS.toByteArray(Charsets.US_ASCII)
    var pos = start + 2
    var segments = 0
    while (pos + 4 <= length && segments++ < 64) {
        if (reader.readUInt8(pos) != 0xFF) return false
        val marker = reader.readUInt8(pos + 1)
        if (marker == 0xFF) { pos++; continue }
        if (marker == 0xDA || marker == 0xD9) return false
        if (marker == 0x01 || marker in 0xD0..0xD7) { pos += 2; continue }
        val segmentLength = reader.readUInt16(pos + 2)
        if (segmentLength < 2 || pos + 2 + segmentLength > length) return false
        if (marker == 0xE1 && containsBytes(reader.readBytes(pos + 4, segmentLength - 2), ns)) return true
        pos += 2 + segmentLength
    }
    return false
}

private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return true
    }
    return false
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

// JPEG only: nearest video ftyp within ±1024 bytes of the declared start. Offset 0 is skipped (never the video).
// HEIC/AVIF locate the video structurally from the mpvd box instead, so the image's own ftyp is never a candidate.
internal fun nearestVideoFtyp(reader: ByteReader, declaredStart: Long): Long? {
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

private const val START_TITLE = "영상 시작 위치 / Video start"
private const val MIME_TITLE = "영상 Item:Mime / Video Item:Mime"
private const val PACKED_TITLE = "빈틈없는 배치 / Tightly packed"
private const val LAST_TITLE = "영상이 파일 끝 / Video at end of file"
private const val GAINMAP_TITLE = "GainMap 항목 / GainMap item"

private fun layoutRules(input: GmpSpecInput, xmp: GmpXmp?, reader: ByteReader, out: MutableList<GoogleSpecFinding>) {
    val fileLength = reader.length
    val isobmff = input.format == ContainerFormat.HEIF || input.format == ContainerFormat.AVIF
    val topLevel = if (isobmff) walkBoxes(reader, 0, fileLength) else emptyList()
    val mpvd = topLevel.firstOrNull { it.type == "mpvd" }
    if (isobmff) checkMpvdBox(topLevel, mpvd, fileLength, out)

    val items = xmp?.directory.orEmpty()
    val motionIndex = items.indexOfFirst { it.semantic == "MotionPhoto" }
    val motion = items.getOrNull(motionIndex)
    val primaryEnd = if (isobmff) mpvd?.offset else primaryJpegEnd(reader)
    // A gain-map JPEG right after the primary one makes this an Ultra HDR image even when the primary XMP is silent.
    val trailingGainMap = !isobmff && primaryEnd != null && embeddedJpegDeclaresGainMap(reader, primaryEnd)
    val gainMapDetected = input.xmpText?.contains(HDR_GAINMAP_NS) == true || trailingGainMap

    val length = motion?.length?.toLongOrNull()?.takeIf { it > 0 }
    val unusable = when {
        motion == null -> "MotionPhoto 항목이 없습니다"
        length == null -> "MotionPhoto Item:Length(\"${motion.length ?: "없음"}\")가 양의 정수가 아닙니다"
        length > fileLength -> "MotionPhoto Item:Length=$length 가 파일 크기 $fileLength 보다 큽니다"
        else -> null
    }
    if (unusable != null) {
        if (length != null && length > fileLength) out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, false, START_TITLE, unusable)
        else out += specSkip("gmp.layout.video_start", "3.3", SpecRequirement.MUST, START_TITLE, unusable)
        val reason = "$unusable — 영상 위치를 알 수 없어 평가하지 않았습니다"
        out += specSkip("gmp.item.video_mime", "3.4", SpecRequirement.MUST, MIME_TITLE, reason)
        out += specSkip("gmp.layout.packed", "3.2", SpecRequirement.MUST, PACKED_TITLE, reason)
        out += specSkip("gmp.layout.video_last", "3.4", SpecRequirement.MUST, LAST_TITLE, reason)
        if (gainMapDetected) gainMapRule(items, motionIndex, trailingGainMap, primaryEnd, out)
        if (mpvd != null && !mpvd.truncated) out += specSkip("gmp.mpvd.length", "4", SpecRequirement.MUST,
            "mpvd 데이터 = 영상 Length / mpvd payload = Length", reason)
        return
    }
    length!!
    val declaredStart = fileLength - length

    // Where the video really starts, and whether the declared item bytes are exactly that video.
    val videoStart: Long?
    val declaredIsVideo: Boolean
    val noVideoReason: String
    if (isobmff) {
        if (mpvd == null || mpvd.truncated) {
            noVideoReason = if (mpvd == null) "최상위 mpvd 박스가 없어 영상 위치를 확인할 수 없습니다"
            else "mpvd 박스가 파일 끝을 넘어 잘려 영상 위치를 확인할 수 없습니다"
            out += specSkip("gmp.layout.video_start", "3.3", SpecRequirement.MUST, START_TITLE, noVideoReason)
            videoStart = null
            declaredIsVideo = false
        } else {
            val payloadStart = mpvd.offset + mpvd.headerSize
            val payloadIsFtyp = payloadStart + 12 <= mpvd.end && isFtyp(reader, payloadStart)
            val problems = buildList {
                if (declaredStart != payloadStart) {
                    add("선언 위치 $declaredStart, mpvd 데이터 시작 $payloadStart — ${kotlin.math.abs(declaredStart - payloadStart)}바이트 차이")
                }
                if (!payloadIsFtyp) add("mpvd 데이터가 ftyp로 시작하지 않습니다")
            }
            out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, problems.isEmpty(), START_TITLE,
                if (problems.isEmpty()) "파일 끝 − Item:Length($length) = $declaredStart = mpvd 데이터 시작, 영상 ftyp로 시작합니다"
                else problems.joinToString("; "))
            noVideoReason = "mpvd 데이터가 ftyp로 시작하지 않습니다"
            videoStart = if (payloadIsFtyp) payloadStart else null
            declaredIsVideo = declaredStart == payloadStart
        }
    } else {
        val ftypAtDeclared = isFtyp(reader, declaredStart)
        videoStart = if (ftypAtDeclared) declaredStart else nearestVideoFtyp(reader, declaredStart)
        // JPEG keeps evaluating the Mime/last rules on a nearby ftyp: a SEF prefix shifts it, and the bytes are still the video.
        declaredIsVideo = true
        noVideoReason = "영상 ftyp를 찾지 못했습니다"
        out += spec("gmp.layout.video_start", "3.3", SpecRequirement.MUST, ftypAtDeclared, START_TITLE,
            when {
                ftypAtDeclared -> "파일 끝 − Item:Length($length) = $declaredStart 에 영상 ftyp가 있습니다"
                videoStart != null -> "파일 끝 − Item:Length = $declaredStart 이지만 실제 영상 ftyp는 ${videoStart - declaredStart}바이트 떨어진 $videoStart 에 있습니다 — 항목 바이트는 영상 파일 원본이어야 합니다"
                else -> "파일 끝 − Item:Length = $declaredStart 근처(±1024바이트)에서 영상 ftyp를 찾지 못했습니다"
            })
    }

    // A failure found on the real video is definite; a pass only holds when the declared item bytes are that video.
    fun videoRule(ruleId: String, title: String, ok: Boolean, detail: String) =
        if (ok && !declaredIsVideo) specSkip(ruleId, "3.4", SpecRequirement.MUST, title,
            "선언된 영상 위치($declaredStart)가 실제 영상 시작과 달라 MotionPhoto 항목을 확인할 수 없습니다 ($detail)")
        else spec(ruleId, "3.4", SpecRequirement.MUST, ok, title, detail)

    if (videoStart != null) {
        val brand = reader.readFourCC(videoStart + 8).trim()
        val expected = if (brand == "qt") "video/quicktime" else "video/mp4"
        out += videoRule("gmp.item.video_mime", MIME_TITLE, motion!!.mime == expected,
            "Item:Mime=\"${motion.mime ?: "없음"}\", 실제 ftyp major_brand=\"$brand\" → \"$expected\"")
    } else {
        out += specSkip("gmp.item.video_mime", "3.4", SpecRequirement.MUST, MIME_TITLE, noVideoReason)
    }

    if (primaryEnd == null || motionIndex < 1) {
        out += specSkip("gmp.layout.packed", "3.2", SpecRequirement.MUST, PACKED_TITLE,
            if (primaryEnd == null) "기본 이미지의 끝을 확인할 수 없습니다" else "MotionPhoto가 첫 항목입니다")
    } else {
        val padding = items[0].padding?.toLongOrNull() ?: 0L
        val between = items.subList(1, motionIndex).sumOf { it.length?.toLongOrNull()?.coerceAtLeast(0) ?: 0L }
        val expected = primaryEnd + padding + between
        val undeclaredJpeg = if (!isobmff && expected != declaredStart && between == 0L &&
            primaryEnd + 2 <= fileLength && reader.readUInt8(primaryEnd) == 0xFF && reader.readUInt8(primaryEnd + 1) == 0xD8
        ) (jpegEnd(reader, primaryEnd)?.minus(primaryEnd) ?: (declaredStart - expected)) else null
        out += spec("gmp.layout.packed", "3.2", SpecRequirement.MUST, expected == declaredStart, PACKED_TITLE,
            "기본 이미지 끝 $primaryEnd + Padding $padding" + (if (between > 0) " + 앞 항목 Length $between" else "") +
                " = $expected, 파일 끝 − Item:Length = $declaredStart" +
                (if (expected == declaredStart) "" else " — ${declaredStart - expected}바이트 차이") +
                (if (undeclaredJpeg != null) " — 기본 이미지 뒤 ${primaryEnd}에 디렉터리에 없는 JPEG(게인맵) ${undeclaredJpeg}바이트가 있습니다" else ""))
    }

    if (videoStart != null) {
        val limit = if (mpvd != null && videoStart in mpvd.offset until mpvd.end) mpvd.end.coerceAtMost(fileLength) else fileLength
        val boxes = walkBoxes(reader, videoStart, limit)
        val truncated = boxes.lastOrNull()?.takeIf { it.truncated }
        val videoEnd = boxes.lastOrNull()?.let { if (it.truncated) it.offset else it.end } ?: videoStart
        val trailing = fileLength - videoEnd
        val sef = trailing > 0 && fileLength >= 4 && reader.readFourCC(fileLength - 4) == "SEFT"
        out += videoRule("gmp.layout.video_last", LAST_TITLE, truncated == null && trailing == 0L,
            when {
                truncated != null -> "영상의 ${truncated.type} 박스(size ${truncated.size})가 $limit 을 넘어 잘렸습니다"
                trailing == 0L -> "영상 컨테이너가 파일 끝($fileLength)에서 끝납니다"
                else -> "영상 컨테이너가 $videoEnd 에서 끝나고 뒤에 ${trailing}바이트가 더 있습니다" +
                    (if (sef) " (삼성 SEF 트레일러)" else "") + " — MotionPhoto 항목 뒤에는 어떤 바이트도 올 수 없습니다"
            })
    } else {
        out += specSkip("gmp.layout.video_last", "3.4", SpecRequirement.MUST, LAST_TITLE, noVideoReason)
    }

    if (gainMapDetected) gainMapRule(items, motionIndex, trailingGainMap, primaryEnd, out)

    if (mpvd != null && !mpvd.truncated) {
        val payload = mpvd.size - mpvd.headerSize
        out += spec("gmp.mpvd.length", "4", SpecRequirement.MUST, payload == length, "mpvd 데이터 = 영상 Length / mpvd payload = Length",
            "mpvd 데이터 $payload 바이트, MotionPhoto Item:Length $length" + if (payload == length) "" else " — ${length - payload}바이트 차이")
    }
}

private fun gainMapRule(
    items: List<GmpDirectoryItem>, motionIndex: Int, trailingGainMap: Boolean, primaryEnd: Long?, out: MutableList<GoogleSpecFinding>,
) {
    if (motionIndex < 0) {
        out += specSkip("gmp.gainmap.item", "3.4", SpecRequirement.MUST, GAINMAP_TITLE,
            "MotionPhoto 항목이 없어 GainMap 항목 순서를 평가하지 않았습니다")
        return
    }
    val gainIndex = items.indexOfFirst { it.semantic == "GainMap" }
    val source = if (trailingGainMap) " (기본 이미지 뒤 ${primaryEnd}에 hdrgm XMP를 가진 게인맵 JPEG가 있음)" else ""
    out += spec("gmp.gainmap.item", "3.4", SpecRequirement.MUST, gainIndex in 1 until motionIndex, GAINMAP_TITLE,
        when {
            gainIndex < 0 -> "게인맵(hdrgm) 이미지인데 GainMap 항목이 없습니다$source"
            gainIndex == 0 -> "GainMap 항목이 첫 항목입니다 (Primary 다음에 와야 함)"
            gainIndex > motionIndex -> "GainMap 항목(#${gainIndex + 1})이 MotionPhoto 항목(#${motionIndex + 1}) 뒤에 있습니다"
            else -> "GainMap 항목 #${gainIndex + 1}이 MotionPhoto 항목 앞에 있습니다"
        })
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
