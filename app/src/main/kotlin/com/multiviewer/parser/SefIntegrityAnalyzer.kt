package com.multiviewer.parser

// Local copies, not shared with SefdBoxDecoder.kt's identically-named/signed private functions --
// see this plan's Task 1 Step 1 note: promoting either to `internal` would collide with 7 other
// files in this package that each already declare their own file-private function of this exact
// name/signature, since Kotlin's `private` is file-scoped but `internal` is module-scoped. This
// file joins that same established (if duplicative) per-file-copy convention rather than fighting it.
private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}

enum class SefIntegritySeverity {
    PASS, INFO, WARNING, CRITICAL, SKIPPED
}

data class SefCheckResult(
    val severity: SefIntegritySeverity,
    val label: String,
    val detail: String,
)

data class SefDirectoryEntryRow(
    val entryIndex: Int,
    val markerHex: String,
    // null when the entry's name couldn't be read: out of bounds, too short for its own 8-byte
    // header, or name_size overruns the block.
    val name: String?,
    val declaredOffset: Long,
    val declaredLength: Long,
    val computedDataStart: Long,
    val computedDataEnd: Long,
    val inBounds: Boolean,
    // null when unreachable (out of bounds, or too short for its own header) -- there was no
    // block to read a marker from at all.
    val markerMatches: Boolean?,
    val status: SefIntegritySeverity,
)

data class SefIntegrityReport(
    val overallSeverity: SefIntegritySeverity,
    val structuralChecks: List<SefCheckResult>,
    val semanticChecks: List<SefCheckResult>,
    // null when the SEFH entry count was never reached (SEFT/SEFH itself failed validation) --
    // distinguishes "couldn't read the count" from "genuinely declares 0 entries".
    val declaredEntryCount: Long?,
    val directoryEntries: List<SefDirectoryEntryRow>,
    val declaredEntryCountSeverity: SefIntegritySeverity,
)

private fun overallSeverityOf(severities: List<SefIntegritySeverity>): SefIntegritySeverity {
    return when {
        SefIntegritySeverity.CRITICAL in severities -> SefIntegritySeverity.CRITICAL
        SefIntegritySeverity.WARNING in severities -> SefIntegritySeverity.WARNING
        else -> SefIntegritySeverity.PASS
    }
}

// A field block whose directory entry, marker, and name were all successfully validated --
// the unit later semantic checks (Task 2) operate on.
internal data class SefFieldBlock(
    val entryIndex: Int,
    val marker: Int,
    val name: String,
    val dataStart: Long,
    val dataLength: Int,
)

object SefIntegrityAnalyzer {
    fun analyze(reader: ByteReader, offset: Long, headerSize: Int, size: Long, fileLength: Long): SefIntegrityReport {
        val structural = mutableListOf<SefCheckResult>()
        val semantic = mutableListOf<SefCheckResult>()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size

        var declaredEntryCount: Long? = null
        var directoryEntries: List<SefDirectoryEntryRow> = emptyList()

        fun finish(): SefIntegrityReport {
            val countSeverity = when {
                declaredEntryCount == null -> SefIntegritySeverity.SKIPPED
                declaredEntryCount == directoryEntries.size.toLong() -> SefIntegritySeverity.PASS
                else -> SefIntegritySeverity.CRITICAL
            }
            return SefIntegrityReport(
                overallSeverityOf((structural + semantic).map { it.severity } + directoryEntries.map { it.status } + listOf(countSeverity)),
                structural, semantic, declaredEntryCount, directoryEntries, countSeverity,
            )
        }

        if (payloadEnd - payloadStart < 12) {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "Trailer size", "Trailer is ${payloadEnd - payloadStart} bytes, too short to contain a SEFH/SEFT trailer (minimum 12 bytes)"))
            return finish()
        }

        val sefMagic = reader.readFourCC(payloadEnd - 4)
        if (sefMagic != "SEFT") {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFT tail magic", "Expected \"SEFT\" at offset ${payloadEnd - 4}, found \"$sefMagic\""))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "SEFH header magic", "Skipped -- depends on SEFT tail magic"))
            semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Semantic checks", "Skipped -- depends on SEFT tail magic"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFT tail magic", "\"SEFT\" found at offset ${payloadEnd - 4}"))

        val sefSize = readUInt32LE(reader, payloadEnd - 8)
        val sefhPosition = payloadEnd - 8 - sefSize

        if (sefhPosition < payloadStart || sefhPosition + 12 > payloadEnd) {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFH header position", "Computed position $sefhPosition (from sef_size=$sefSize) is out of bounds [$payloadStart, ${payloadEnd - 12}]"))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "SEFH header magic", "Skipped -- depends on SEFH header position"))
            semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Semantic checks", "Skipped -- depends on SEFH header position"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFH header position", "Computed position $sefhPosition (from sef_size=$sefSize) is within bounds [$payloadStart, ${payloadEnd - 12}]"))

        val sefhMagic = reader.readFourCC(sefhPosition)
        if (sefhMagic != "SEFH") {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFH header magic", "Expected \"SEFH\" at offset $sefhPosition, found \"$sefhMagic\""))
            semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Semantic checks", "Skipped -- depends on SEFH header magic"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFH header magic", "\"SEFH\" found at offset $sefhPosition"))

        val declaredCount = readUInt32LE(reader, sefhPosition + 8)
        declaredEntryCount = declaredCount

        val expectedSefSize = 12L + declaredCount * 12L
        structural.add(
            if (sefSize == expectedSefSize)
                SefCheckResult(
                    SefIntegritySeverity.PASS,
                    "SEFH directory size",
                    "sef_size=$sefSize matches 12 + $declaredCount * 12 bytes (directory ends at SEFT tail offset ${payloadEnd - 8})",
                )
            else
                SefCheckResult(
                    SefIntegritySeverity.CRITICAL,
                    "SEFH directory size",
                    "sef_size=$sefSize in SEFT tail does not match expected directory size $expectedSefSize (12 + $declaredCount * 12 bytes)",
                ),
        )

        data class DirEntry(
            val index: Int,
            val subField: Int,
            val marker: Int,
            val blockStart: Long,
            val blockEnd: Long,
            val inBounds: Boolean,
        )
        val dirEntries = mutableListOf<DirEntry>()
        var entryPos = sefhPosition + 12
        var entriesFound = 0L
        var idx = 0
        while (entriesFound < declaredCount && entryPos + 12 <= payloadEnd - 8) {
            idx++
            val entrySubField = readUInt16LE(reader, entryPos)
            val entryMarker = readUInt16LE(reader, entryPos + 2)
            val entryOffset = readUInt32LE(reader, entryPos + 4)
            val entrySize = readUInt32LE(reader, entryPos + 8)
            entryPos += 12
            entriesFound++
            val blockStart = sefhPosition - entryOffset
            val blockEnd = blockStart + entrySize
            val inBounds = entrySize > 0 && blockStart >= payloadStart && blockEnd <= sefhPosition
            dirEntries.add(DirEntry(idx, entrySubField, entryMarker, blockStart, blockEnd, inBounds))
        }

        val inBoundsEntries = dirEntries.filter { it.inBounds }
        val sortedByStart = inBoundsEntries.sortedBy { it.blockStart }
        val overlaps = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        val allEntriesInBounds = dirEntries.isNotEmpty() && inBoundsEntries.size == dirEntries.size && entriesFound == declaredCount
        if (allEntriesInBounds && sortedByStart.first().blockStart > payloadStart) {
            val first = sortedByStart.first()
            gaps.add("${first.blockStart - payloadStart} byte(s) before entry #${first.index} [$payloadStart, ${first.blockStart})")
        }
        for (i in 1 until sortedByStart.size) {
            val prev = sortedByStart[i - 1]
            val curr = sortedByStart[i]
            when {
                curr.blockStart < prev.blockEnd -> overlaps.add("entry #${prev.index} [${prev.blockStart}, ${prev.blockEnd}) overlaps entry #${curr.index} [${curr.blockStart}, ${curr.blockEnd})")
                curr.blockStart > prev.blockEnd -> gaps.add("${curr.blockStart - prev.blockEnd} byte(s) between entry #${prev.index} and entry #${curr.index}")
            }
        }
        if (allEntriesInBounds && sortedByStart.last().blockEnd < sefhPosition) {
            val last = sortedByStart.last()
            gaps.add("${sefhPosition - last.blockEnd} byte(s) between entry #${last.index} and SEFH header [${last.blockEnd}, $sefhPosition)")
        }
        structural.add(
            if (overlaps.isEmpty())
                SefCheckResult(SefIntegritySeverity.PASS, "Field block overlap", "No overlapping field blocks among ${sortedByStart.size} in-bounds entries")
            else
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Field block overlap", overlaps.joinToString("; ")),
        )
        structural.add(
            if (gaps.isEmpty())
                SefCheckResult(SefIntegritySeverity.PASS, "Field block gaps", "No gaps between consecutive field blocks across [$payloadStart, $sefhPosition)")
            else
                SefCheckResult(SefIntegritySeverity.WARNING, "Field block gaps", gaps.joinToString("; ")),
        )

        val fieldBlocks = mutableListOf<SefFieldBlock>()
        val directoryEntryRows = mutableListOf<SefDirectoryEntryRow>()
        for (e in dirEntries) {
            val markerLabel = "0x" + e.marker.toString(16).padStart(4, '0')
            val declaredOffset = sefhPosition - e.blockStart
            val declaredLength = e.blockEnd - e.blockStart

            if (!e.inBounds) {
                directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, null, declaredOffset, declaredLength, e.blockStart, e.blockEnd, false, null, SefIntegritySeverity.CRITICAL))
                continue
            }
            if (e.blockEnd - e.blockStart < 8) {
                directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, null, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, null, SefIntegritySeverity.CRITICAL))
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- depends on Entry #${e.index} block header"))
                continue
            }
            val blockSubField = readUInt16LE(reader, e.blockStart)
            val blockMarker = readUInt16LE(reader, e.blockStart + 2)
            val markerMatches = blockMarker == e.marker && blockSubField == e.subField
            val nameSize = readUInt32LE(reader, e.blockStart + 4)
            val nameOffset = e.blockStart + 8
            if (nameSize <= 0 || nameOffset + nameSize > e.blockEnd) {
                directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, null, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, markerMatches, SefIntegritySeverity.CRITICAL))
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- depends on Entry #${e.index} name_size"))
                continue
            }
            val nameBytes = reader.readBytes(nameOffset, nameSize.toInt())
            val name = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
            val nameValid = name.isNotEmpty() && name.none { it.code < 0x20 || it.code == 0x7F }
            if (!nameValid) {
                directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, null, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, markerMatches, SefIntegritySeverity.CRITICAL))
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- Entry #${e.index} key name at offset $nameOffset is invalid"))
                continue
            }
            val status = if (markerMatches) SefIntegritySeverity.PASS else SefIntegritySeverity.CRITICAL
            directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, name, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, markerMatches, status))
            val fieldHeaderSize = (8 + nameSize).toInt()
            val dataStart = e.blockStart + fieldHeaderSize
            val dataLength = (e.blockEnd - dataStart).toInt()
            fieldBlocks.add(SefFieldBlock(e.index, e.marker, name, dataStart, dataLength))
        }
        directoryEntries = directoryEntryRows

        for (fb in fieldBlocks) {
            semantic.add(
                when {
                    fb.marker == MARKER_UTC_TIMESTAMP -> checkUtcTimestamp(reader, fb)
                    fb.marker == MARKER_MCC -> checkMcc(reader, fb)
                    fb.name == "MotionPhoto_Data" ->
                        if (fb.dataLength == 12)
                            checkMotionPhotoData(reader, fb, offset, fileLength)
                        else if (isInlineFtypVideo(reader, fb))
                            inlineVideoResult(reader, fb, "MotionPhoto_Data")
                        else
                            SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "Expected 12-byte pointer or inline MP4 (ftyp), found ${fb.dataLength} bytes -- cannot verify bounds")
                    fb.name == "MotionPhoto_AutoPlay" ->
                        if (fb.dataLength == 12)
                            checkMotionPhotoAutoPlay(reader, fb, offset, fileLength)
                        else if (isInlineFtypVideo(reader, fb))
                            inlineVideoResult(reader, fb, "MotionPhoto_AutoPlay")
                        else
                            SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", "Expected 12-byte pointer or inline MP4 (ftyp), found ${fb.dataLength} bytes -- cannot verify bounds")
                    else -> checkTextOrJson(reader, fb)
                },
            )
        }

        return finish()
    }
}

private const val PLAUSIBLE_EPOCH_MIN_MS = 946684800000L // 2000-01-01T00:00:00Z, in milliseconds
private const val ONE_YEAR_MS = 365L * 24 * 3600 * 1000

// Image_UTC_Data is milliseconds-since-epoch, not seconds -- confirmed against real Samsung
// device files (e.g. a raw value of 1784372679960 corresponds to 2026, matching the file's real
// capture date; interpreting the same value as seconds lands in the year 58506). This was wrong
// in the original implementation and produced a bogus "outside plausible range" WARNING on every
// real SEF file tested, regardless of how genuinely valid the timestamp was.
private fun formatEpoch(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))

private fun checkUtcTimestamp(reader: ByteReader, fb: SefFieldBlock): SefCheckResult {
    val dataBytes = reader.readBytes(fb.dataStart, fb.dataLength)
    val text = decodeFieldText(dataBytes)
    if (text == null) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} UTC timestamp", "Field data is not decodable as UTF-8 text")
    }
    val epoch = text.trim().toLongOrNull()
    if (epoch == null) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} UTC timestamp", "Value \"$text\" is not a parseable integer epoch")
    }
    val maxEpoch = System.currentTimeMillis() + ONE_YEAR_MS
    val inPlausibleRange = epoch in PLAUSIBLE_EPOCH_MIN_MS..maxEpoch
    val formatted = try {
        formatEpoch(epoch)
    } catch (_: java.time.DateTimeException) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} UTC timestamp", "Epoch value $epoch cannot be represented as a valid date")
    }
    return if (inPlausibleRange) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} UTC timestamp", "$formatted is within a plausible range")
    } else {
        SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} UTC timestamp", "$formatted is outside the plausible range (2000-01-01 to 1 year from now)")
    }
}

private fun checkMcc(reader: ByteReader, fb: SefFieldBlock): SefCheckResult {
    val dataBytes = reader.readBytes(fb.dataStart, fb.dataLength)
    val rawText = decodeFieldText(dataBytes)
    if (rawText == null) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MCC format", "Field data is not decodable as UTF-8 text")
    }
    val text = rawText.trim()
    val mcc = text.toIntOrNull()
    if (mcc == null || text.length != 3) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MCC format", "Value \"$text\" is not a 3-digit number")
    }
    val country = MCC_COUNTRY_NAMES[mcc]
    return if (country != null) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} MCC", "$mcc is a valid code ($country) [ITU E.212 Annex A]")
    } else {
        SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} MCC", "$mcc is 3 digits but not in the current ITU E.212 Annex A assignment table")
    }
}

internal data class Mp4BoxWalkResult(
    val startOffset: Long,
    val endOffset: Long,
    val totalLength: Long,
    val boxes: List<String>,
    val hasFtyp: Boolean,
    val error: String?,
)

private fun isPrintableFourCC(type: String): Boolean =
    type.length == 4 && type.all { it.code in 0x20..0x7E }

private fun readUInt64BE(reader: ByteReader, offset: Long): Long {
    val hi = reader.readUInt32(offset)
    val lo = reader.readUInt32(offset + 4)
    return (hi shl 32) or (lo and 0xFFFF_FFFFL)
}

internal fun walkMp4TopLevelBoxes(
    reader: ByteReader,
    startOffset: Long,
    maxBound: Long,
    stopAtZeroPadding: Boolean = true,
): Mp4BoxWalkResult {
    val readableMax = minOf(maxBound, reader.length)
    if (startOffset < 0 || startOffset + 8 > readableMax) {
        return Mp4BoxWalkResult(startOffset, startOffset, 0L, emptyList(), false, "insufficient bytes at offset $startOffset for an 8-byte MP4 box header")
    }
    val firstType = reader.readFourCC(startOffset + 4)
    if (firstType != "ftyp") {
        return Mp4BoxWalkResult(startOffset, startOffset, 0L, emptyList(), false, "expected \"ftyp\" at offset ${startOffset + 4}, found \"$firstType\"")
    }

    val boxes = mutableListOf<String>()
    var pos = startOffset
    var error: String? = null
    while (pos + 8 <= readableMax) {
        val size32 = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        if (pos > startOffset) {
            if (type == "sefd") break
            if (stopAtZeroPadding && size32 == 0L && reader.readUInt32(pos + 4) == 0L) break
            if (!isPrintableFourCC(type)) {
                error = "non-MP4 box type \"$type\" at offset $pos"
                break
            }
        }
        val boxSize = when {
            size32 == 1L -> {
                if (pos + 16 > readableMax) {
                    error = "truncated 64-bit box header for \"$type\" at offset $pos"
                    break
                }
                val size64 = readUInt64BE(reader, pos + 8)
                if (size64 < 16L) {
                    error = "invalid 64-bit box size $size64 for \"$type\" at offset $pos"
                    break
                }
                size64
            }
            size32 < 8L -> {
                error = "invalid box size $size32 for \"$type\" at offset $pos"
                break
            }
            else -> size32
        }
        if (pos + boxSize > maxBound) {
            error = "box \"$type\" at offset $pos (size=$boxSize) ends at ${pos + boxSize}, exceeding boundary $maxBound"
            break
        }
        boxes.add(type)
        pos += boxSize
    }
    return Mp4BoxWalkResult(
        startOffset = startOffset,
        endOffset = pos,
        totalLength = pos - startOffset,
        boxes = boxes,
        hasFtyp = boxes.firstOrNull() == "ftyp",
        error = error,
    )
}

internal data class MotionPhotoPointerValidation(
    val formatTag: String,
    val declaredVideoOffset: Long,
    val declaredVideoLength: Long,
    val declaredVideoEnd: Long,
    val actualVideoOffset: Long?,
    val actualVideoLength: Long?,
    val actualVideoEnd: Long?,
    val mp4Boxes: List<String>,
    val severity: SefIntegritySeverity,
    val detail: String,
    val koDetail: String,
    val koRemediation: String?,
)

internal fun validateMotionPhotoPointer(
    reader: ByteReader,
    dataStart: Long,
    sefdOffset: Long,
    fileLength: Long,
    fieldName: String = "MotionPhoto_Data",
): MotionPhotoPointerValidation {
    val formatTag = reader.readFourCC(dataStart)
    val videoOffset = reader.readUInt32(dataStart + 4)
    val videoLength = reader.readUInt32(dataStart + 8)
    val end = videoOffset + videoLength
    val readableLength = minOf(fileLength, reader.length)

    if (videoOffset < 0 || videoLength <= 0 || end > fileLength) {
        return MotionPhotoPointerValidation(
            formatTag = formatTag,
            declaredVideoOffset = videoOffset,
            declaredVideoLength = videoLength,
            declaredVideoEnd = end,
            actualVideoOffset = null,
            actualVideoLength = null,
            actualVideoEnd = null,
            mp4Boxes = emptyList(),
            severity = SefIntegritySeverity.CRITICAL,
            detail = "video_offset=$videoOffset + video_length=$videoLength = $end, EXCEEDS file length $fileLength",
            koDetail = "$fieldName 포인터 범위(offset=$videoOffset, length=$videoLength, end=$end)가 파일 크기($fileLength bytes)를 초과합니다.",
            koRemediation = "SEF $fieldName 에 기록된 video_offset($videoOffset) + video_length($videoLength) = $end 가 전체 파일 크기($fileLength)를 벗어납니다. 파일 앞(0)에서부터의 실제 MP4 시작 오프셋과 길이를 다시 계산하여 기록하십시오.",
        )
    }

    val issues = mutableListOf<String>()
    val koIssues = mutableListOf<String>()

    if (formatTag != "mpv2") {
        issues.add("format_tag=\"$formatTag\" (expected \"mpv2\")")
        koIssues.add("포인터 태그가 \"mpv2\"가 아닙니다(실제: \"$formatTag\")")
    }
    if (videoLength < 8) {
        issues.add("video_length=$videoLength is too short for an MP4 ftyp header (minimum 8 bytes)")
        koIssues.add("video_length($videoLength bytes)가 최소 MP4 헤더 크기(8 bytes)보다 작습니다")
    }
    if (sefdOffset > 0 && videoOffset < sefdOffset && end > sefdOffset) {
        val overrun = end - sefdOffset
        issues.add("video_offset($videoOffset) + video_length($videoLength) = $end encroaches $overrun byte(s) into sefd box at offset $sefdOffset")
        koIssues.add("video_offset($videoOffset) + video_length($videoLength) = $end 범위가 sefd 박스 시작 위치($sefdOffset)를 ${overrun}바이트 침범합니다")
    }

    val maxVideoBound = if (sefdOffset > 0 && videoOffset < sefdOffset) sefdOffset else fileLength
    val isHeicContainer = readableLength >= 12L && reader.readFourCC(4) == "ftyp"
    val hasFtypAtDeclared = videoOffset + 8 <= readableLength && reader.readFourCC(videoOffset + 4) == "ftyp"
    val actualStart: Long? = if (hasFtypAtDeclared) {
        videoOffset
    } else if (videoOffset + 8 <= readableLength) {
        val corrected = correctMp4StartOffset(reader, videoOffset)
        if (corrected != videoOffset && corrected >= 0 && corrected + 8 <= readableLength && reader.readFourCC(corrected + 4) == "ftyp") {
            corrected
        } else {
            null
        }
    } else {
        null
    }

    if (!hasFtypAtDeclared) {
        if (actualStart != null) {
            val delta = videoOffset - actualStart
            val sign = if (delta > 0) "+$delta" else "$delta"
            val mpvdHintEn = if (isHeicContainer && delta == 8L) " — 8-byte \"mpvd\" box header is missing before MP4 \"ftyp\"" else ""
            val mpvdHintKo = if (isHeicContainer && delta == 8L) " — HEIC 필수 8바이트 \"mpvd\" 박스 헤더가 누락되어 MP4 \"ftyp\"가 8바이트 앞당겨졌습니다" else ""
            issues.add("video_offset=$videoOffset does not point to MP4 \"ftyp\" (actual MP4 \"ftyp\" starts at offset $actualStart, delta=$sign bytes$mpvdHintEn)")
            koIssues.add("기록된 video_offset($videoOffset, 0x${videoOffset.toString(16)})에 MP4 \"ftyp\" 박스가 없으며 실제 동영상은 오프셋 $actualStart(0x${actualStart.toString(16)})에서 시작합니다(오차: $sign bytes$mpvdHintKo)")
        } else {
            val foundFourCC = if (videoOffset + 8 <= readableLength) "\"${reader.readFourCC(videoOffset + 4)}\"" else "unreadable"
            issues.add("video_offset=$videoOffset does not point to an MP4 \"ftyp\" box (found $foundFourCC at offset ${videoOffset + 4})")
            koIssues.add("기록된 video_offset($videoOffset, 0x${videoOffset.toString(16)}) 위치에서 유효한 MP4 \"ftyp\" 박스를 찾을 수 없습니다(실제 값: $foundFourCC)")
        }
    } else if (isHeicContainer && sefdOffset > 0 && videoOffset >= 8L && videoOffset < sefdOffset) {
        val prevBoxType = reader.readFourCC(videoOffset - 4)
        val prevBoxSize = reader.readUInt32(videoOffset - 8)
        if (prevBoxType != "mpvd") {
            issues.add("missing 8-byte \"mpvd\" box header at offset ${videoOffset - 8} before MP4 \"ftyp\" (found \"$prevBoxType\")")
            koIssues.add("MP4 \"ftyp\" 시작 위치($videoOffset) 직전 오프셋(${videoOffset - 8})에 HEIC 모션포토 필수 \"mpvd\" 박스 헤더가 없습니다(실제 값: \"$prevBoxType\")")
        } else if (prevBoxSize != videoLength + 8L && prevBoxSize != 1L) {
            issues.add("enclosing \"mpvd\" box at offset ${videoOffset - 8} declares size=$prevBoxSize (expected video_length + 8 = ${videoLength + 8L})")
            koIssues.add("\"mpvd\" 박스(오프셋 ${videoOffset - 8})의 선언 크기($prevBoxSize bytes)가 video_length + 8(${videoLength + 8L} bytes)과 일치하지 않습니다")
        }
    }

    val walkResult = actualStart?.let { start ->
        walkMp4TopLevelBoxes(reader, start, maxVideoBound, stopAtZeroPadding = true)
    }

    val actualLength = walkResult?.totalLength?.takeIf { it > 0 }
    val actualEnd = walkResult?.endOffset?.takeIf { actualLength != null }

    if (walkResult != null) {
        if (walkResult.error != null) {
            issues.add("MP4 box walk error: ${walkResult.error}")
            koIssues.add("임베디드 MP4 박스 구조 오류: ${walkResult.error}")
        }
        if (actualLength != null && actualLength != videoLength) {
            val lenDelta = videoLength - actualLength
            val sign = if (lenDelta > 0) "+$lenDelta" else "$lenDelta"
            issues.add("video_length=$videoLength does not match actual MP4 byte length $actualLength (delta=$sign bytes, boxes=${walkResult.boxes.joinToString("+")})")
            koIssues.add("기록된 video_length($videoLength bytes)가 실제 임베디드 MP4 길이($actualLength bytes, 박스: ${walkResult.boxes.joinToString("+")})와 일치하지 않습니다(오차: $sign bytes)")
        }
    }

    return if (issues.isEmpty()) {
        val boxesSummary = walkResult?.boxes?.joinToString(", ") ?: "ftyp"
        MotionPhotoPointerValidation(
            formatTag = formatTag,
            declaredVideoOffset = videoOffset,
            declaredVideoLength = videoLength,
            declaredVideoEnd = end,
            actualVideoOffset = actualStart,
            actualVideoLength = actualLength,
            actualVideoEnd = actualEnd,
            mp4Boxes = walkResult?.boxes ?: emptyList(),
            severity = SefIntegritySeverity.PASS,
            detail = "video_offset=$videoOffset + video_length=$videoLength = $end, within file length $fileLength (verified MP4 boxes [$boxesSummary] at [$videoOffset, $end))",
            koDetail = "기록된 video_offset($videoOffset, 0x${videoOffset.toString(16)}) 및 video_length($videoLength bytes)가 파일 내 실제 MP4 동영상 영역([$boxesSummary], 0x${videoOffset.toString(16)}..0x${end.toString(16)})과 정확히 일치합니다.",
            koRemediation = null,
        )
    } else {
        val remediationHint = if (actualStart != null && actualLength != null) {
            if (isHeicContainer && videoOffset - actualStart == 8L) {
                "오프셋 $actualStart(0x${actualStart.toString(16)})의 MP4 \"ftyp\" 앞에 8바이트 \"mpvd\" 박스 헤더([4B BE size = ${actualLength + 8}][\"mpvd\"])를 추가하여 MP4 스트림을 감싸거나, SEF $fieldName 포인터를 실제 MP4 시작 오프셋($actualStart) 및 길이($actualLength bytes)와 동기화하십시오."
            } else {
                "SEF $fieldName 의 12바이트 포인터(\"mpv2\" + 4B BE video_offset + 4B BE video_length)를 실제 파일 내 MP4 시작 오프셋(video_offset=$actualStart [0x${actualStart.toString(16)}])과 실제 MP4 길이(video_length=$actualLength bytes, 종료 오프셋=${actualStart + actualLength})로 수정하십시오."
            }
        } else {
            "SEF $fieldName 의 12바이트 포인터(\"mpv2\" + 4B BE video_offset + 4B BE video_length)가 파일 앞(0)에서부터 실제 임베디드 MP4(ftyp 박스)의 시작 위치와 정확한 바이트 길이를 가리키도록 수정하십시오."
        }
        MotionPhotoPointerValidation(
            formatTag = formatTag,
            declaredVideoOffset = videoOffset,
            declaredVideoLength = videoLength,
            declaredVideoEnd = end,
            actualVideoOffset = actualStart,
            actualVideoLength = actualLength,
            actualVideoEnd = actualEnd,
            mp4Boxes = walkResult?.boxes ?: emptyList(),
            severity = SefIntegritySeverity.CRITICAL,
            detail = issues.joinToString("; "),
            koDetail = koIssues.joinToString("; "),
            koRemediation = remediationHint,
        )
    }
}

// Samsung JPEG motion photos store the MP4 itself in the SEF field (not a 12-byte pointer):
// the field data begins with an ISO-BMFF box header whose bytes 4..7 are "ftyp".
private fun isInlineFtypVideo(reader: ByteReader, fb: SefFieldBlock): Boolean {
    if (fb.dataLength < 8) return false
    val head = reader.readBytes(fb.dataStart, 8)
    return head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
        head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
}

private fun inlineVideoResult(reader: ByteReader, fb: SefFieldBlock, name: String): SefCheckResult {
    val expectedEnd = fb.dataStart + fb.dataLength
    val walk = walkMp4TopLevelBoxes(reader, fb.dataStart, expectedEnd, stopAtZeroPadding = false)
    val previewSuffix = if (name == "MotionPhoto_AutoPlay") " (header only; this preview clip is not decoded)" else ""
    return if (walk.error == null && walk.totalLength == fb.dataLength.toLong()) {
        SefCheckResult(
            SefIntegritySeverity.PASS,
            "Entry #${fb.entryIndex} $name bounds",
            "Video stored inline in the SEF field: ${fb.dataLength} bytes at offset ${fb.dataStart}, starting with an ftyp box (boxes: ${walk.boxes.joinToString(", ")})$previewSuffix",
        )
    } else {
        val reason = walk.error ?: "MP4 top-level boxes sum to ${walk.totalLength} bytes, but SEFH declared field data length is ${fb.dataLength} bytes"
        SefCheckResult(
            SefIntegritySeverity.CRITICAL,
            "Entry #${fb.entryIndex} $name bounds",
            "Inline MP4 in $name at offset ${fb.dataStart} (declared data length ${fb.dataLength} bytes) has mismatched box structure: $reason",
        )
    }
}

private fun checkMotionPhotoData(reader: ByteReader, fb: SefFieldBlock, sefdOffset: Long, fileLength: Long): SefCheckResult {
    val validation = validateMotionPhotoPointer(reader, fb.dataStart, sefdOffset, fileLength, "MotionPhoto_Data")
    return SefCheckResult(validation.severity, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", validation.detail)
}

// Mirrors checkMotionPhotoData exactly -- MotionPhoto_AutoPlay (the preview clip) uses the same
// 12-byte offset+length pointer shape when present, but unlike MotionPhoto_Data it's optional, so
// this is only ever invoked for entries that actually exist.
private fun checkMotionPhotoAutoPlay(reader: ByteReader, fb: SefFieldBlock, sefdOffset: Long, fileLength: Long): SefCheckResult {
    val validation = validateMotionPhotoPointer(reader, fb.dataStart, sefdOffset, fileLength, "MotionPhoto_AutoPlay")
    return SefCheckResult(validation.severity, "Entry #${fb.entryIndex} MotionPhoto_AutoPlay bounds", validation.detail)
}

private fun checkTextOrJson(reader: ByteReader, fb: SefFieldBlock): SefCheckResult {
    val dataBytes = reader.readBytes(fb.dataStart, fb.dataLength)
    val text = decodeFieldText(dataBytes)
    return when {
        text == null -> SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} (${fb.name}) encoding", "${fb.dataLength} bytes, binary (not UTF-8 text) -- no text validation applicable")
        isJsonShaped(text) -> {
            val jsonError = validateJsonSyntax(text)
            if (jsonError == null) {
                SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} (${fb.name}) JSON syntax", "Valid JSON (${text.length} chars)")
            } else {
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} (${fb.name}) JSON syntax", "Malformed JSON: $jsonError")
            }
        }
        else -> SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} (${fb.name}) encoding", "Valid UTF-8 text (${text.length} chars)")
    }
}

// Minimal, dependency-free strict JSON syntax validator -- returns null if [text] is valid JSON, or a
// human-readable error (with a 0-based character index) otherwise. Distinct from SefdBoxDecoder's
// prettyPrintJson, which only balances {}/[] depth and would accept syntactically invalid JSON (a
// trailing comma, an unquoted key, an unterminated string) as long as the brackets happen to balance.
internal fun validateJsonSyntax(text: String): String? = JsonSyntaxValidator(text.trim()).validate()

private class JsonSyntaxValidator(private val s: String) {
    private var i = 0
    private var depth = 0
    private val MAX_DEPTH = 512

    fun validate(): String? {
        skipWs()
        val err = parseValue()
        if (err != null) return err
        skipWs()
        if (i != s.length) return "trailing content after JSON value at position $i"
        return null
    }

    private fun error(msg: String) = "$msg at position $i"
    private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun parseValue(): String? {
        if (i >= s.length) return error("unexpected end of input")
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true")
            'f' -> parseLiteral("false")
            'n' -> parseLiteral("null")
            else -> parseNumber()
        }
    }

    private fun parseLiteral(literal: String): String? {
        if (i + literal.length > s.length || s.substring(i, i + literal.length) != literal) return error("expected \"$literal\"")
        i += literal.length
        return null
    }

    private fun parseObject(): String? {
        depth++
        if (depth > MAX_DEPTH) return error("nesting too deep (max $MAX_DEPTH)")
        i++
        skipWs()
        if (i < s.length && s[i] == '}') { i++; depth--; return null }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') { depth--; return error("expected string key") }
            parseString()?.let { depth--; return it }
            skipWs()
            if (i >= s.length || s[i] != ':') { depth--; return error("expected ':'") }
            i++
            skipWs()
            parseValue()?.let { depth--; return it }
            skipWs()
            if (i >= s.length) { depth--; return error("unterminated object") }
            when (s[i]) {
                ',' -> { i++; continue }
                '}' -> { i++; depth--; return null }
                else -> { depth--; return error("expected ',' or '}'") }
            }
        }
    }

    private fun parseArray(): String? {
        depth++
        if (depth > MAX_DEPTH) return error("nesting too deep (max $MAX_DEPTH)")
        i++
        skipWs()
        if (i < s.length && s[i] == ']') { i++; depth--; return null }
        while (true) {
            skipWs()
            parseValue()?.let { depth--; return it }
            skipWs()
            if (i >= s.length) { depth--; return error("unterminated array") }
            when (s[i]) {
                ',' -> { i++; continue }
                ']' -> { i++; depth--; return null }
                else -> { depth--; return error("expected ',' or ']'") }
            }
        }
    }

    private fun parseString(): String? {
        i++
        while (i < s.length) {
            when (val c = s[i]) {
                '"' -> { i++; return null }
                '\\' -> {
                    i++
                    if (i >= s.length) return error("unterminated escape")
                    when (s[i]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> i++
                        'u' -> {
                            if (i + 4 >= s.length) return error("incomplete \\u escape")
                            for (k in 1..4) {
                                val hc = s[i + k].lowercaseChar()
                                if (!hc.isDigit() && hc !in 'a'..'f') return error("invalid \\u escape hex digit")
                            }
                            i += 5
                        }
                        else -> return error("invalid escape character")
                    }
                }
                else -> {
                    if (c.code < 0x20) return error("unescaped control character in string")
                    i++
                }
            }
        }
        return error("unterminated string")
    }

    private fun parseNumber(): String? {
        val start = i
        if (i < s.length && s[i] == '-') i++
        if (i >= s.length || !s[i].isDigit()) return error("expected digit")
        if (s[i] == '0') { i++ } else { while (i < s.length && s[i].isDigit()) i++ }
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || !s[i].isDigit()) return error("expected digit after decimal point")
            while (i < s.length && s[i].isDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isDigit()) return error("expected digit in exponent")
            while (i < s.length && s[i].isDigit()) i++
        }
        if (i == start) return error("invalid value")
        return null
    }
}
