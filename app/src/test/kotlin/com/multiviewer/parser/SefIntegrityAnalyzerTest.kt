package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun byteReaderOf(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

data class SefTestField(val marker: Int, val name: String, val data: ByteArray)

// Assembles a complete, well-formed SEF trailer (field blocks + SEFH directory
// + SEFT tail) from a list of fields, computing every offset automatically.
// A real trailer involves dozens of interdependent byte offsets (field block
// headers, directory entries measured *backward* from the SEFH position,
// the SEFT tail's own size field) -- hand-encoding these as literal hex
// arrays would be exactly the kind of arithmetic-transcription risk this
// project's later phases (WebP, BMP) already established programmatic
// builders to avoid. Verified against SefdBoxDecoder.kt's actual read
// offsets (blockMarker @ blockStart+2, name_size @ blockStart+4, name @
// blockStart+8, SEFH's count @ sefhPosition+8, directory entries 12 bytes
// each starting at sefhPosition+12, SEFT's sef_size @ payloadEnd-8) --
// see this plan's own derivation, not re-derived ad hoc per test.
private fun buildSefTrailer(fields: List<SefTestField>): ByteArray {
    val blockBytesList = fields.map { f ->
        val nameBytes = f.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val header = ByteArray(8)
        header.putUInt16LE(2, f.marker)
        header.putUInt32LE(4, nameBytes.size.toLong())
        header + nameBytes + f.data
    }
    val blocksTotalSize = blockBytesList.sumOf { it.size }

    val dirSize = 12 + fields.size * 12
    val dir = ByteArray(dirSize)
    dir[0] = 'S'.code.toByte(); dir[1] = 'E'.code.toByte(); dir[2] = 'F'.code.toByte(); dir[3] = 'H'.code.toByte()
    dir.putUInt32LE(8, fields.size.toLong())
    var remaining = blocksTotalSize.toLong()
    for ((idx, f) in fields.withIndex()) {
        val entryPos = 12 + idx * 12
        dir.putUInt16LE(entryPos + 2, f.marker)
        dir.putUInt32LE(entryPos + 4, remaining)
        dir.putUInt32LE(entryPos + 8, blockBytesList[idx].size.toLong())
        remaining -= blockBytesList[idx].size
    }

    val seft = ByteArray(8)
    seft.putUInt32LE(0, dirSize.toLong())
    seft[4] = 'S'.code.toByte(); seft[5] = 'E'.code.toByte(); seft[6] = 'F'.code.toByte(); seft[7] = 'T'.code.toByte()

    var result = ByteArray(0)
    for (b in blockBytesList) result += b
    result += dir
    result += seft
    return result
}

class SefIntegrityAnalyzerTest {
    @Test
    fun `a well-formed trailer with two fields passes every structural check`() {
        val trailer = buildSefTrailer(
            listOf(
                SefTestField(0x0b01, "SomeField", "hello".toByteArray()),
                SefTestField(0x0aa1, "MCC", "450".toByteArray()),
            ),
        )
        byteReaderOf(trailer, "sef-well-formed").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            assertEquals(SefIntegritySeverity.PASS, report.overallSeverity)
            assertTrue(report.structuralChecks.isNotEmpty())
            assertTrue(report.structuralChecks.none { it.severity == SefIntegritySeverity.CRITICAL })
            // Both entries individually present, not collapsed
            assertTrue(report.structuralChecks.any { it.label.contains("Entry #1") })
            assertTrue(report.structuralChecks.any { it.label.contains("Entry #2") })
        }
    }

    @Test
    fun `missing SEFT magic is CRITICAL and skips everything downstream`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0b01, "X", byteArrayOf(1))))
        trailer[trailer.size - 1] = 'X'.code.toByte() // corrupt "SEFT" -> "SEFX"
        byteReaderOf(trailer, "sef-bad-seft").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
            val seftCheck = report.structuralChecks.first { it.label == "SEFT tail magic" }
            assertEquals(SefIntegritySeverity.CRITICAL, seftCheck.severity)
            assertTrue(report.structuralChecks.any { it.severity == SefIntegritySeverity.SKIPPED })
        }
    }

    @Test
    fun `missing SEFH magic is CRITICAL and skips the directory count check`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0b01, "X", byteArrayOf(1))))
        // SEFH magic sits right after the field block; corrupt it.
        val blockSize = 8 + ("X".toByteArray().size + 1) + 1 // header(8) + name("X\0") + data(1 byte)
        trailer[blockSize] = 'X'.code.toByte() // corrupt "SEFH" -> "XEFH"
        byteReaderOf(trailer, "sef-bad-sefh").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
            assertEquals(SefIntegritySeverity.PASS, report.structuralChecks.first { it.label == "SEFT tail magic" }.severity)
            assertEquals(SefIntegritySeverity.CRITICAL, report.structuralChecks.first { it.label == "SEFH header magic" }.severity)
            assertTrue(report.structuralChecks.any { it.label == "Directory entry count" && it.severity == SefIntegritySeverity.SKIPPED })
        }
    }

    @Test
    fun `an out-of-bounds directory entry is reported CRITICAL individually while other entries still pass`() {
        val trailer = buildSefTrailer(
            listOf(
                SefTestField(0x0b01, "Good", "ok".toByteArray()),
                SefTestField(0x0c01, "Bad", "x".toByteArray()),
            ),
        ).copyOf()
        // Corrupt entry #2's offset field to point out of bounds. Directory starts right after
        // both field blocks; entry #2 is the second 12-byte entry, so its offset field (at
        // entryPos+4) needs locating -- compute the same way buildSefTrailer does.
        val block1Size = 8 + ("Good".toByteArray().size + 1) + 2
        val block2Size = 8 + ("Bad".toByteArray().size + 1) + 1
        val dirStart = block1Size + block2Size
        val entry2Pos = dirStart + 12 + 12 // SEFH header(12) + entry #1(12) = entry #2 starts here
        trailer.putUInt32LE(entry2Pos + 4, 999_999L) // absurd offset -> out of bounds
        byteReaderOf(trailer, "sef-oob-entry").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
            val entry1 = report.structuralChecks.first { it.label.contains("Entry #1") }
            val entry2 = report.structuralChecks.first { it.label.contains("Entry #2") }
            assertEquals(SefIntegritySeverity.PASS, entry1.severity)
            assertEquals(SefIntegritySeverity.CRITICAL, entry2.severity)
            assertTrue(entry2.detail.contains("exceeds trailer bounds"))
        }
    }

    @Test
    fun `overlapping field blocks are reported CRITICAL as a single combined check`() {
        // Build a normal 2-field trailer, then corrupt entry #1's size so its declared range
        // extends into entry #2's territory.
        val trailer = buildSefTrailer(
            listOf(
                SefTestField(0x0b01, "A", "aa".toByteArray()),
                SefTestField(0x0b02, "B", "bb".toByteArray()),
            ),
        ).copyOf()
        val block1Size = 8 + ("A".toByteArray().size + 1) + 2
        val block2Size = 8 + ("B".toByteArray().size + 1) + 2
        val dirStart = block1Size + block2Size
        val entry1Pos = dirStart + 12
        trailer.putUInt32LE(entry1Pos + 8, (block1Size + 5).toLong()) // grow entry #1's declared size to overlap entry #2
        byteReaderOf(trailer, "sef-overlap").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val overlapCheck = report.structuralChecks.first { it.label == "Field block overlap" }
            assertEquals(SefIntegritySeverity.CRITICAL, overlapCheck.severity)
            assertTrue(overlapCheck.detail.contains("overlaps"))
        }
    }

    @Test
    fun `a gap between field blocks is reported INFO, never CRITICAL or WARNING`() {
        val fieldA = SefTestField(0x0b01, "A", "aa".toByteArray())
        val fieldB = SefTestField(0x0b02, "B", "bb".toByteArray())
        val trailer = buildSefTrailer(listOf(fieldA, fieldB)).copyOf()
        val block1Size = 8 + ("A".toByteArray().size + 1) + 2
        val block2Size = 8 + ("B".toByteArray().size + 1) + 2
        val dirStart = block1Size + block2Size
        val entry1Pos = dirStart + 12
        // Shrink entry #1's declared size by 1 byte so a 1-byte gap opens up before entry #2.
        // Must shrink by at most 2: block1's own header+name needs nameOffset(8)+nameSize(2)=10
        // bytes minimum, and block1Size is 12, so shrinking by 3+ would trip an unrelated
        // "name_size runs past end of block" CRITICAL instead of the gap this test targets
        // (an off-by-enough bug caught by Task 1's implementer during a prior attempt at this
        // exact plan -- verify this arithmetic yourself before trusting it further).
        trailer.putUInt32LE(entry1Pos + 8, (block1Size - 1).toLong())
        byteReaderOf(trailer, "sef-gap").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val gapCheck = report.structuralChecks.first { it.label == "Field block gaps" }
            assertEquals(SefIntegritySeverity.INFO, gapCheck.severity)
            // INFO must never escalate overall severity on its own
            assertEquals(SefIntegritySeverity.PASS, report.overallSeverity)
        }
    }

    @Test
    fun `a block marker that disagrees with its directory entry is CRITICAL`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0b01, "A", "aa".toByteArray()))).copyOf()
        // The block's own marker lives at blockStart+2 -- blockStart is 0 for the only/first block.
        trailer.putUInt16LE(2, 0x9999) // block's own marker now disagrees with its directory entry (0x0b01)
        byteReaderOf(trailer, "sef-marker-mismatch").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val markerCheck = report.structuralChecks.first { it.label.contains("marker match") }
            assertEquals(SefIntegritySeverity.CRITICAL, markerCheck.severity)
        }
    }

    @Test
    fun `a plausible UTC timestamp passes and an implausible one WARNs`() {
        val plausible = buildSefTrailer(listOf(SefTestField(MARKER_UTC_TIMESTAMP, "TimeStamp", "1700000000".toByteArray())))
        byteReaderOf(plausible, "sef-timestamp-ok").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, plausible.size.toLong(), plausible.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("UTC timestamp") }
            assertEquals(SefIntegritySeverity.PASS, check.severity)
            assertTrue(check.detail.contains("2023")) // 1700000000 epoch -> 2023-11-14
        }

        val implausible = buildSefTrailer(listOf(SefTestField(MARKER_UTC_TIMESTAMP, "TimeStamp", "9999999999".toByteArray())))
        byteReaderOf(implausible, "sef-timestamp-bad").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, implausible.size.toLong(), implausible.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("UTC timestamp") }
            assertEquals(SefIntegritySeverity.WARNING, check.severity)
        }
    }

    @Test
    fun `a valid MCC maps to its ITU country name and an unmapped one WARNs`() {
        val valid = buildSefTrailer(listOf(SefTestField(MARKER_MCC, "MCC", "450".toByteArray())))
        byteReaderOf(valid, "sef-mcc-valid").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, valid.size.toLong(), valid.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("MCC") && !it.label.contains("format") }
            assertEquals(SefIntegritySeverity.PASS, check.severity)
            assertTrue(check.detail.contains("Korea (Republic of)"))
        }

        val unmapped = buildSefTrailer(listOf(SefTestField(MARKER_MCC, "MCC", "999".toByteArray())))
        byteReaderOf(unmapped, "sef-mcc-unmapped").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, unmapped.size.toLong(), unmapped.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("MCC") && !it.label.contains("format") }
            assertEquals(SefIntegritySeverity.WARNING, check.severity)
        }
    }

    @Test
    fun `well-formed JSON passes strict validation and malformed JSON is CRITICAL`() {
        val validJson = """{"key":"value","n":42}"""
        val trailer1 = buildSefTrailer(listOf(SefTestField(0x0c01, "ReEditData", validJson.toByteArray())))
        byteReaderOf(trailer1, "sef-json-valid").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer1.size.toLong(), trailer1.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("JSON syntax") }
            assertEquals(SefIntegritySeverity.PASS, check.severity)
        }

        val malformedJson = """{"key":"value",}""" // trailing comma -- invalid JSON
        val trailer2 = buildSefTrailer(listOf(SefTestField(0x0c01, "ReEditData", malformedJson.toByteArray())))
        byteReaderOf(trailer2, "sef-json-malformed").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer2.size.toLong(), trailer2.size.toLong())
            val check = report.semanticChecks.first { it.label.contains("JSON syntax") }
            assertEquals(SefIntegritySeverity.CRITICAL, check.severity)
        }
    }

    @Test
    fun `MotionPhoto_Data bounds check passes within the file and fails past it`() {
        val payloadOk = "mpv2".toByteArray() + ByteArray(8).also {
            it[3] = 10 // video_offset = 10 (big-endian uint32)
            it[7] = 5  // video_length = 5 (big-endian uint32)
        }
        val trailerOk = buildSefTrailer(listOf(SefTestField(0x0d01, "MotionPhoto_Data", payloadOk)))
        byteReaderOf(trailerOk, "sef-mp-ok").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailerOk.size.toLong(), 1000L)
            val check = report.semanticChecks.first { it.label.contains("MotionPhoto_Data") }
            assertEquals(SefIntegritySeverity.PASS, check.severity)
        }

        val payloadBad = "mpv2".toByteArray() + ByteArray(8).also {
            it[0] = 0x7F.toByte() // video_offset = a huge number
            it[1] = 0xFF.toByte()
            it[2] = 0xFF.toByte()
            it[3] = 0xFF.toByte()
        }
        val trailerBad = buildSefTrailer(listOf(SefTestField(0x0d01, "MotionPhoto_Data", payloadBad)))
        byteReaderOf(trailerBad, "sef-mp-bad").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailerBad.size.toLong(), 1000L)
            val check = report.semanticChecks.first { it.label.contains("MotionPhoto_Data") }
            assertEquals(SefIntegritySeverity.CRITICAL, check.severity)
        }
    }

    @Test
    fun `validateJsonSyntax accepts nested well-formed JSON and rejects specific malformations`() {
        assertEquals(null, validateJsonSyntax("""{"a":[1,2,3],"b":{"c":true,"d":null}}"""))
        assertTrue(validateJsonSyntax("""{"a":1,}""") != null) // trailing comma
        assertTrue(validateJsonSyntax("""{a:1}""") != null) // unquoted key
        assertTrue(validateJsonSyntax("""{"a":"unterminated""") != null) // unterminated string
        assertTrue(validateJsonSyntax("""[1,2""") != null) // unterminated array
    }
}
