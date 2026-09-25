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

data class SefIntegrityReport(
    val overallSeverity: SefIntegritySeverity,
    val structuralChecks: List<SefCheckResult>,
    val semanticChecks: List<SefCheckResult>,
)

private fun overallSeverityOf(results: List<SefCheckResult>): SefIntegritySeverity {
    val severities = results.map { it.severity }
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

        fun finish() = SefIntegrityReport(overallSeverityOf(structural + semantic), structural, semantic)

        if (payloadEnd - payloadStart < 12) {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "Trailer size", "Trailer is ${payloadEnd - payloadStart} bytes, too short to contain a SEFH/SEFT trailer (minimum 12 bytes)"))
            return finish()
        }

        val sefMagic = reader.readFourCC(payloadEnd - 4)
        if (sefMagic != "SEFT") {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFT tail magic", "Expected \"SEFT\" at offset ${payloadEnd - 4}, found \"$sefMagic\""))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "SEFH header magic", "Skipped -- depends on SEFT tail magic"))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Directory entry count", "Skipped -- depends on SEFT tail magic"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFT tail magic", "\"SEFT\" found at offset ${payloadEnd - 4}"))

        val sefSize = readUInt32LE(reader, payloadEnd - 8)
        val sefhPosition = payloadEnd - 8 - sefSize

        if (sefhPosition < payloadStart || sefhPosition + 12 > payloadEnd) {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFH header position", "Computed position $sefhPosition (from sef_size=$sefSize) is out of bounds [$payloadStart, ${payloadEnd - 12}]"))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "SEFH header magic", "Skipped -- depends on SEFH header position"))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Directory entry count", "Skipped -- depends on SEFH header position"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFH header position", "Computed position $sefhPosition (from sef_size=$sefSize) is within bounds [$payloadStart, ${payloadEnd - 12}]"))

        val sefhMagic = reader.readFourCC(sefhPosition)
        if (sefhMagic != "SEFH") {
            structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "SEFH header magic", "Expected \"SEFH\" at offset $sefhPosition, found \"$sefhMagic\""))
            structural.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Directory entry count", "Skipped -- depends on SEFH header magic"))
            return finish()
        }
        structural.add(SefCheckResult(SefIntegritySeverity.PASS, "SEFH header magic", "\"SEFH\" found at offset $sefhPosition"))

        val declaredCount = readUInt32LE(reader, sefhPosition + 8)

        data class DirEntry(val index: Int, val marker: Int, val blockStart: Long, val blockEnd: Long, val inBounds: Boolean)
        val dirEntries = mutableListOf<DirEntry>()
        var entryPos = sefhPosition + 12
        var entriesFound = 0L
        var idx = 0
        while (entriesFound < declaredCount && entryPos + 12 <= payloadEnd) {
            idx++
            val entryMarker = readUInt16LE(reader, entryPos + 2)
            val entryOffset = readUInt32LE(reader, entryPos + 4)
            val entrySize = readUInt32LE(reader, entryPos + 8)
            entryPos += 12
            entriesFound++
            val blockStart = sefhPosition - entryOffset
            val blockEnd = blockStart + entrySize
            dirEntries.add(DirEntry(idx, entryMarker, blockStart, blockEnd, blockStart >= payloadStart && blockEnd <= payloadEnd))
        }

        structural.add(
            if (entriesFound < declaredCount)
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Directory entry count", "SEFH declares $declaredCount entries but only $entriesFound were found before running out of trailer space")
            else
                SefCheckResult(SefIntegritySeverity.PASS, "Directory entry count", "SEFH declares $declaredCount entries, $entriesFound found"),
        )

        for (e in dirEntries) {
            val markerLabel = "0x" + e.marker.toString(16).padStart(4, '0')
            structural.add(
                if (e.inBounds)
                    SefCheckResult(SefIntegritySeverity.PASS, "Entry #${e.index} (marker $markerLabel)", "offset=${sefhPosition - e.blockStart}, size=${e.blockEnd - e.blockStart} -- within bounds")
                else
                    SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${e.index} (marker $markerLabel)", "computed range [${e.blockStart}, ${e.blockEnd}) exceeds trailer bounds [$payloadStart, $payloadEnd) -- field block skipped"),
            )
        }

        val inBoundsEntries = dirEntries.filter { it.inBounds }
        val sortedByStart = inBoundsEntries.sortedBy { it.blockStart }
        val overlaps = mutableListOf<String>()
        val gaps = mutableListOf<String>()
        for (i in 1 until sortedByStart.size) {
            val prev = sortedByStart[i - 1]
            val curr = sortedByStart[i]
            when {
                curr.blockStart < prev.blockEnd -> overlaps.add("entry #${prev.index} [${prev.blockStart}, ${prev.blockEnd}) overlaps entry #${curr.index} [${curr.blockStart}, ${curr.blockEnd})")
                curr.blockStart > prev.blockEnd -> gaps.add("${curr.blockStart - prev.blockEnd} byte(s) between entry #${prev.index} and entry #${curr.index}")
            }
        }
        structural.add(
            if (overlaps.isEmpty())
                SefCheckResult(SefIntegritySeverity.PASS, "Field block overlap", "No overlapping field blocks among ${sortedByStart.size} in-bounds entries")
            else
                SefCheckResult(SefIntegritySeverity.CRITICAL, "Field block overlap", overlaps.joinToString("; ")),
        )
        structural.add(
            if (gaps.isEmpty())
                SefCheckResult(SefIntegritySeverity.PASS, "Field block gaps", "No gaps between consecutive field blocks")
                else
                SefCheckResult(SefIntegritySeverity.INFO, "Field block gaps", gaps.joinToString("; ")),
        )

        val fieldBlocks = mutableListOf<SefFieldBlock>()
        for (e in inBoundsEntries) {
            val markerLabel = "0x" + e.marker.toString(16).padStart(4, '0')
            if (e.blockEnd - e.blockStart < 8) {
                structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${e.index} block header", "Block is ${e.blockEnd - e.blockStart} bytes, too short for its own 8-byte header"))
                continue
            }
            val blockMarker = readUInt16LE(reader, e.blockStart + 2)
            structural.add(
                if (blockMarker == e.marker)
                    SefCheckResult(SefIntegritySeverity.PASS, "Entry #${e.index} marker match", "Block's own marker $markerLabel matches its directory entry")
                else
                    SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${e.index} marker match", "Directory marker $markerLabel does not match block's own marker 0x${blockMarker.toString(16).padStart(4, '0')}"),
            )
            val nameSize = readUInt32LE(reader, e.blockStart + 4)
            val nameOffset = e.blockStart + 8
            if (nameOffset + nameSize > e.blockEnd) {
                structural.add(SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${e.index} name_size", "name_size=$nameSize runs past the end of its block"))
                continue
            }
            structural.add(SefCheckResult(SefIntegritySeverity.PASS, "Entry #${e.index} name_size", "name_size=$nameSize fits within block"))
            val nameBytes = reader.readBytes(nameOffset, nameSize.toInt())
            val name = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
            val fieldHeaderSize = (8 + nameSize).toInt()
            val dataStart = e.blockStart + fieldHeaderSize
            val dataLength = (e.blockEnd - dataStart).toInt()
            fieldBlocks.add(SefFieldBlock(e.index, e.marker, name, dataStart, dataLength))
        }

        for (fb in fieldBlocks) {
            semantic.add(
                when {
                    fb.marker == MARKER_UTC_TIMESTAMP -> checkUtcTimestamp(reader, fb)
                    fb.marker == MARKER_MCC -> checkMcc(reader, fb)
                    fb.name == "MotionPhoto_Data" && fb.dataLength == 12 -> checkMotionPhotoData(reader, fb, fileLength)
                    else -> checkTextOrJson(reader, fb)
                },
            )
        }

        return finish()
    }
}

private const val PLAUSIBLE_EPOCH_MIN = 946684800L // 2000-01-01T00:00:00Z
private const val ONE_YEAR_SECONDS = 365L * 24 * 3600

private fun formatEpoch(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneOffset.UTC)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))

private fun checkUtcTimestamp(reader: ByteReader, fb: SefFieldBlock): SefCheckResult {
    val dataBytes = reader.readBytes(fb.dataStart, fb.dataLength)
    val text = decodeFieldText(dataBytes)
    val epoch = text?.trim()?.toLongOrNull()
    if (epoch == null) {
        return SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} UTC timestamp", "Value \"$text\" is not a parseable integer epoch")
    }
    val maxEpoch = System.currentTimeMillis() / 1000 + ONE_YEAR_SECONDS
    val formatted = formatEpoch(epoch)
    return if (epoch in PLAUSIBLE_EPOCH_MIN..maxEpoch) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} UTC timestamp", "$formatted is within a plausible range")
    } else {
        SefCheckResult(SefIntegritySeverity.WARNING, "Entry #${fb.entryIndex} UTC timestamp", "$formatted is outside the plausible range (2000-01-01 to 1 year from now)")
    }
}

private fun checkMcc(reader: ByteReader, fb: SefFieldBlock): SefCheckResult {
    val dataBytes = reader.readBytes(fb.dataStart, fb.dataLength)
    val text = decodeFieldText(dataBytes)?.trim()
    val mcc = text?.toIntOrNull()
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

private fun checkMotionPhotoData(reader: ByteReader, fb: SefFieldBlock, fileLength: Long): SefCheckResult {
    val videoOffset = reader.readUInt32(fb.dataStart + 4)
    val videoLength = reader.readUInt32(fb.dataStart + 8)
    val end = videoOffset + videoLength
    return if (end <= fileLength) {
        SefCheckResult(SefIntegritySeverity.PASS, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, within file length $fileLength")
    } else {
        SefCheckResult(SefIntegritySeverity.CRITICAL, "Entry #${fb.entryIndex} MotionPhoto_Data bounds", "video_offset=$videoOffset + video_length=$videoLength = $end, EXCEEDS file length $fileLength")
    }
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
        i++
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return null }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') return error("expected string key")
            parseString()?.let { return it }
            skipWs()
            if (i >= s.length || s[i] != ':') return error("expected ':'")
            i++
            skipWs()
            parseValue()?.let { return it }
            skipWs()
            if (i >= s.length) return error("unterminated object")
            when (s[i]) {
                ',' -> { i++; continue }
                '}' -> { i++; return null }
                else -> return error("expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): String? {
        i++
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return null }
        while (true) {
            skipWs()
            parseValue()?.let { return it }
            skipWs()
            if (i >= s.length) return error("unterminated array")
            when (s[i]) {
                ',' -> { i++; continue }
                ']' -> { i++; return null }
                else -> return error("expected ',' or ']'")
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
