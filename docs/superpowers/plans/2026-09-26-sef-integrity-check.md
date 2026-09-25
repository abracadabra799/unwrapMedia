# SEF Integrity Check Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new menu item and dedicated window that independently re-walks a
parsed Samsung SEF trailer (SEFH header + field data blocks + SEFT tail,
already recognized as the `"sefd"`-typed `BoxNode` by the existing
`SefdBoxDecoder`) and reports a PASS/WARNING/CRITICAL checklist covering both
structural integrity and semantic value validity — every checkable unit
(each directory entry, each field block, each semantically-checked value)
shown as its own line, none summarized away.

**Architecture:** `SefIntegrityAnalyzer.kt` (new, `com.multiviewer.parser`
package, pure Kotlin, no Compose) re-walks the trailer from raw bytes via
`ByteReader`, producing an ordered `SefIntegrityReport`. `MccCountryNames.kt`
(new, same package) holds a `Map<Int, String>` sourced directly from the
official ITU-T E.212 Annex A document (not a secondary source — a prior
phase of this app found a real Wikipedia-sourced MCC/country error).
`SefIntegrityWindow.kt` (new, `com.multiviewer.ui` package, matching where
`AvSyncAnalysisWindow.kt` lives) renders the report, styled like the existing
`AvSyncAnalysisWindow`. A new menu item, enabled only when the current tab's
parsed tree contains a `"sefd"` node, opens it.

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`), Compose Desktop.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-26-sef-integrity-check-design.md`
  — every check, severity rule, and display format below traces back to it.
- Show every checkable unit individually (every directory entry, every field
  block's marker/name_size check, every semantically-checked field) — never
  collapse passing ones into a summary count. Confirmed directly with the
  user.
- `MccCountryNames`'s table must be sourced from the official ITU-T E.212
  Annex A document, not a secondary source. The exact table used in this
  plan was extracted from `https://www.itu.int/dms_pub/itu-t/opb/sp/T-SP-E.212A-2017-PDF-E.pdf`
  via `pdftotext -layout` during this plan's writing, and spot-verified
  against the raw extracted text (including the MCC 450/467 pair that a
  prior Wikipedia-sourced table had swapped) — do not hand-edit these
  values without re-deriving from an official source.
- No new dependency (JSON validation is hand-rolled, matching this
  codebase's established convention for `SefdBoxDecoder.kt`'s own
  `prettyPrintJson`).
- No repair/rewrite of SEF data — read-only diagnostic.
- `SefIntegrityAnalyzer` must not mutate or depend on Compose/UI state —
  `SefIntegrityWindow.kt` is the only file with Compose imports.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt` | Promote `MARKER_UTC_TIMESTAMP`, `MARKER_MCC`, `decodeFieldText`, `isJsonShaped` from `private` to `internal` so `SefIntegrityAnalyzer.kt` (same package) can reuse them instead of duplicating. `readUInt16LE`/`readUInt32LE` stay `private` (see Task 1). No behavior change. |
| `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt` | New — the analyzer: data model, structural checks (Task 1), semantic checks (Task 2). |
| `app/src/main/kotlin/com/multiviewer/parser/MccCountryNames.kt` | New — the ITU E.212 MCC→country table (Task 2). |
| `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt` | New — unit tests (Tasks 1-2). |
| `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt` | New — the report window (Task 3). |
| `app/src/main/kotlin/com/multiviewer/Main.kt` | New menu item + window-open state (Task 3). |

---

### Task 1: Data model + structural checks

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`

**Interfaces:**
- Produces: `enum class SefIntegritySeverity { PASS, INFO, WARNING, CRITICAL, SKIPPED }`,
  `data class SefCheckResult(val severity: SefIntegritySeverity, val label: String, val detail: String)`,
  `data class SefIntegrityReport(val overallSeverity: SefIntegritySeverity, val structuralChecks: List<SefCheckResult>, val semanticChecks: List<SefCheckResult>)`,
  `object SefIntegrityAnalyzer { fun analyze(reader: ByteReader, offset: Long, headerSize: Int, size: Long, fileLength: Long): SefIntegrityReport }`.
  Task 2 extends `analyze`'s body to add semantic checks — this task's
  version returns `semanticChecks = emptyList()`, which is a legitimate
  interim state of the real interface, not a placeholder (the report type
  and window UI Task 3 builds against are already final).
- Consumes (after promotion below): `SefdBoxDecoder`'s `MARKER_UTC_TIMESTAMP`,
  `MARKER_MCC`, `decodeFieldText(bytes: ByteArray): String?`,
  `isJsonShaped(text: String): Boolean` — all `internal`.
- Produces (in `SefIntegrityAnalyzer.kt` itself, private, NOT promoted from
  `SefdBoxDecoder.kt` — see the note on Step 1 below):
  `private fun readUInt16LE(reader: ByteReader, offset: Long): Int`,
  `private fun readUInt32LE(reader: ByteReader, offset: Long): Long`.

- [ ] **Step 1: Promote `SefdBoxDecoder.kt`'s internals to `internal`
  (NOT its `readUInt16LE`/`readUInt32LE`)**

In `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`, change:
```kotlin
private const val MARKER_UTC_TIMESTAMP = 0x0a01
private const val MARKER_MCC = 0x0aa1
```
to:
```kotlin
internal const val MARKER_UTC_TIMESTAMP = 0x0a01
internal const val MARKER_MCC = 0x0aa1
```
And change `decodeFieldText` and `isJsonShaped`'s `private fun` to
`internal fun` (leave `prettyPrintJson` `private` — it's not reused by the
analyzer).

**Do NOT touch `readUInt16LE`/`readUInt32LE`'s visibility.** Kotlin's
top-level `private` is file-scoped, but `internal` is module-scoped —
`SefdBoxDecoder.kt`'s `readUInt16LE`/`readUInt32LE` share their exact name
and signature with file-private functions of the same name already
declared in 7 other files in this package (`AsfWalker.kt`, `AviWalker.kt`,
`BmpWalker.kt`, `FlacWalker.kt`, `GifWalker.kt`, `OggWalker.kt`,
`WavWalker.kt` — this codebase's established, if imperfect, convention for
these trivial 2-3 line little-endian readers is a fresh file-local copy per
file, not a shared one; a prior phase's final review already flagged this
duplication as a known, deliberately-deferred cleanup, not something to fix
here). Promoting either to `internal` produces "Conflicting overloads"
compile errors across all 7 of those files. `SefIntegrityAnalyzer.kt` gets
its own private copy instead — see Step 4.

This step is a visibility-only change for the 4 identifiers it does touch;
run `./gradlew test --tests "com.multiviewer.parser.SefdBoxDecoderTest"`
afterward to confirm zero behavior change (expected: all existing tests
still pass unchanged).

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`:

```kotlin
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
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: FAIL to compile (`SefIntegrityAnalyzer`/`SefIntegritySeverity`/
`SefCheckResult`/`SefIntegrityReport` don't exist yet).

- [ ] **Step 4: Implement**

Create `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`:

```kotlin
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

        // Task 2 appends semantic checks here, iterating `fieldBlocks`.

        return finish()
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: PASS, all 7 tests.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures (including `SefdBoxDecoderTest`
unaffected by the visibility-only change in Step 1).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt
git commit -m "feat: structural integrity checks for Samsung SEF trailers

New SefIntegrityAnalyzer independently re-walks a parsed sefd trailer
(SEFT tail -> SEFH header -> directory entries -> field blocks) and
reports every checkable unit individually as PASS/WARNING/CRITICAL/
SKIPPED/INFO, rather than SefdBoxDecoder's existing scattered warning
strings with no consolidated verdict. Adds two checks SefdBoxDecoder
never performed: field blocks overlapping each other (CRITICAL) and
unexplained gaps between them (INFO only -- Samsung's format may
legitimately pad between blocks).

Promotes SefdBoxDecoder's MARKER_UTC_TIMESTAMP/MARKER_MCC/decodeFieldText/
isJsonShaped from private to internal so this new analyzer (same package)
can reuse them rather than duplicating -- visibility-only change, no
behavior change (SefdBoxDecoderTest unaffected). readUInt16LE/readUInt32LE
are NOT promoted -- they'd collide with 7 other files in this package that
already declare their own identically-named/signed file-private copies
(private is file-scoped, internal is module-scoped); this analyzer adds
its own private copy instead, joining that same established convention.

See docs/superpowers/specs/2026-09-26-sef-integrity-check-design.md"
```

---

### Task 2: MCC table + semantic checks

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MccCountryNames.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`

**Interfaces:**
- Consumes: `SefFieldBlock` list built by Task 1's `analyze` (the `// Task 2
  appends semantic checks here` marker in Task 1's code).
- Produces: `internal val MCC_COUNTRY_NAMES: Map<Int, String>` (in
  `MccCountryNames.kt`), `internal fun validateJsonSyntax(text: String): String?`
  (in `SefIntegrityAnalyzer.kt`, returns `null` for valid JSON or a
  human-readable error otherwise).

- [ ] **Step 1: Create the MCC table**

Create `app/src/main/kotlin/com/multiviewer/parser/MccCountryNames.kt`:

```kotlin
package com.multiviewer.parser

// Mobile Country Code -> country/geographical area name, sourced directly from the official
// ITU-T E.212 Annex A ("List of Mobile Country or Geographical Area Codes"), NOT a secondary
// source: a prior phase of this app found a real error in a Wikipedia-sourced version of this
// same table (MCC 450 and 467 -- South and North Korea -- were swapped). This table was
// extracted from the ITU's own PDF (https://www.itu.int/dms_pub/itu-t/opb/sp/T-SP-E.212A-2017-PDF-E.pdf,
// "Annex to ITU Operational Bulletin No. 1117 - 1.II.2017", numerical-order section) via
// `pdftotext -layout`, confirming MCC 450 = "Korea (Republic of)" and 467 = "Democratic
// People's Republic of Korea" against the raw extracted text -- the swap in the earlier,
// never-shipped Wikipedia-sourced attempt was real, this source is correct. A few codes are
// legitimately shared between multiple named territories in the official table (e.g. 340
// between Guadeloupe and Martinique, 362 between three Dutch Caribbean territories) -- those
// are combined into a single "/"-joined display string rather than picking one arbitrarily.
//
// This table reflects the ITU's 2017 edition. ITU republishes Annex A periodically; refreshing
// this table means re-running the same extraction against the current Annex A PDF (search
// itu.int for "ITU-T E.212 Annex A list of mobile country codes") and diffing against this file.
internal val MCC_COUNTRY_NAMES: Map<Int, String> = mapOf(
    202 to "Greece",
    204 to "Netherlands (Kingdom of the)",
    206 to "Belgium",
    208 to "France",
    212 to "Monaco (Principality of)",
    213 to "Andorra (Principality of)",
    214 to "Spain",
    216 to "Hungary",
    218 to "Bosnia and Herzegovina",
    219 to "Croatia (Republic of)",
    220 to "Serbia (Republic of)",
    221 to "Kosovo*",
    222 to "Italy",
    225 to "Vatican City State",
    226 to "Romania",
    228 to "Switzerland (Confederation of)",
    230 to "Czech Republic",
    231 to "Slovak Republic",
    232 to "Austria",
    234 to "United Kingdom of Great Britain and Northern Ireland",
    235 to "United Kingdom of Great Britain and Northern Ireland",
    238 to "Denmark",
    240 to "Sweden",
    242 to "Norway",
    244 to "Finland",
    246 to "Lithuania (Republic of)",
    247 to "Latvia (Republic of)",
    248 to "Estonia (Republic of)",
    250 to "Russian Federation",
    255 to "Ukraine",
    257 to "Belarus (Republic of)",
    259 to "Moldova (Republic of)",
    260 to "Poland (Republic of)",
    262 to "Germany (Federal Republic of)",
    266 to "Gibraltar",
    268 to "Portugal",
    270 to "Luxembourg",
    272 to "Ireland",
    274 to "Iceland",
    276 to "Albania (Republic of)",
    278 to "Malta",
    280 to "Cyprus (Republic of)",
    282 to "Georgia",
    283 to "Armenia (Republic of)",
    284 to "Bulgaria (Republic of)",
    286 to "Turkey",
    288 to "Faroe Islands",
    290 to "Greenland (Denmark)",
    292 to "San Marino (Republic of)",
    293 to "Slovenia (Republic of)",
    294 to "The Former Yugoslav Republic of Macedonia",
    295 to "Liechtenstein (Principality of)",
    297 to "Montenegro",
    302 to "Canada",
    308 to "Saint Pierre and Miquelon (Collectivité territoriale de la République française)",
    310 to "United States of America",
    311 to "United States of America",
    312 to "United States of America",
    313 to "United States of America",
    314 to "United States of America",
    315 to "United States of America",
    316 to "United States of America",
    330 to "Puerto Rico",
    332 to "United States Virgin Islands",
    334 to "Mexico",
    338 to "Jamaica",
    340 to "Guadeloupe (French Department of) / Martinique (French Department of)",
    342 to "Barbados",
    344 to "Antigua and Barbuda",
    346 to "Cayman Islands",
    348 to "British Virgin Islands",
    350 to "Bermuda",
    352 to "Grenada",
    354 to "Montserrat",
    356 to "Saint Kitts and Nevis",
    358 to "Saint Lucia",
    360 to "Saint Vincent and the Grenadines",
    362 to "Curaçao / Sint Maarten (Dutch part) / Bonaire, Sint Eustatius and Saba",
    363 to "Aruba",
    364 to "Bahamas (Commonwealth of the)",
    365 to "Anguilla",
    366 to "Dominica (Commonwealth of)",
    368 to "Cuba",
    370 to "Dominican Republic",
    372 to "Haiti (Republic of)",
    374 to "Trinidad and Tobago",
    376 to "Turks and Caicos Islands",
    400 to "Azerbaijan (Republic of)",
    401 to "Kazakhstan (Republic of)",
    402 to "Bhutan (Kingdom of)",
    404 to "India (Republic of)",
    405 to "India (Republic of)",
    406 to "India (Republic of)",
    410 to "Pakistan (Islamic Republic of)",
    412 to "Afghanistan",
    413 to "Sri Lanka (Democratic Socialist Republic of)",
    414 to "Myanmar (the Republic of the Union of)",
    415 to "Lebanon",
    416 to "Jordan (Hashemite Kingdom of)",
    417 to "Syrian Arab Republic",
    418 to "Iraq (Republic of)",
    419 to "Kuwait (State of)",
    420 to "Saudi Arabia (Kingdom of)",
    421 to "Yemen (Republic of)",
    422 to "Oman (Sultanate of)",
    424 to "United Arab Emirates",
    425 to "Israel (State of)",
    426 to "Bahrain (Kingdom of)",
    427 to "Qatar (State of)",
    428 to "Mongolia",
    429 to "Nepal (Federal Democratic Republic of)",
    430 to "United Arab Emirates",
    431 to "United Arab Emirates",
    432 to "Iran (Islamic Republic of)",
    434 to "Uzbekistan (Republic of)",
    436 to "Tajikistan (Republic of)",
    437 to "Kyrgyz Republic",
    438 to "Turkmenistan",
    440 to "Japan",
    441 to "Japan",
    450 to "Korea (Republic of)",
    452 to "Viet Nam (Socialist Republic of)",
    454 to "Hong Kong, China",
    455 to "Macao, China",
    456 to "Cambodia (Kingdom of)",
    457 to "Lao People's Democratic Republic",
    460 to "China (People's Republic of)",
    461 to "China (People's Republic of)",
    466 to "Taiwan, China",
    467 to "Democratic People's Republic of Korea",
    470 to "Bangladesh (People's Republic of)",
    472 to "Maldives (Republic of)",
    502 to "Malaysia",
    505 to "Australia",
    510 to "Indonesia (Republic of)",
    514 to "Timor-Leste (Democratic Republic of)",
    515 to "Philippines (Republic of the)",
    520 to "Thailand",
    525 to "Singapore (Republic of)",
    528 to "Brunei Darussalam",
    530 to "New Zealand",
    536 to "Nauru (Republic of)",
    537 to "Papua New Guinea",
    539 to "Tonga (Kingdom of)",
    540 to "Solomon Islands",
    541 to "Vanuatu (Republic of)",
    542 to "Fiji (Republic of)",
    543 to "Wallis and Futuna (Territoire français d'outre-mer)",
    544 to "American Samoa",
    545 to "Kiribati (Republic of)",
    546 to "New Caledonia (Territoire français d'outre-mer)",
    547 to "French Polynesia (Territoire français d'outre-mer)",
    548 to "Cook Islands",
    549 to "Samoa (Independent State of)",
    550 to "Micronesia (Federated States of)",
    551 to "Marshall Islands (Republic of the)",
    552 to "Palau (Republic of)",
    553 to "Tuvalu",
    554 to "Tokelau",
    555 to "Niue",
    602 to "Egypt (Arab Republic of)",
    603 to "Algeria (People's Democratic Republic of)",
    604 to "Morocco (Kingdom of)",
    605 to "Tunisia",
    606 to "Libya",
    607 to "Gambia (Republic of the)",
    608 to "Senegal (Republic of)",
    609 to "Mauritania (Islamic Republic of)",
    610 to "Mali (Republic of)",
    611 to "Guinea (Republic of)",
    612 to "Côte d'Ivoire (Republic of)",
    613 to "Burkina Faso",
    614 to "Niger (Republic of the)",
    615 to "Togolese Republic",
    616 to "Benin (Republic of)",
    617 to "Mauritius (Republic of)",
    618 to "Liberia (Republic of)",
    619 to "Sierra Leone",
    620 to "Ghana",
    621 to "Nigeria (Federal Republic of)",
    622 to "Chad (Republic of)",
    623 to "Central African Republic",
    624 to "Cameroon (Republic of)",
    625 to "Cabo Verde (Republic of)",
    626 to "Sao Tome and Principe (Democratic Republic of)",
    627 to "Equatorial Guinea (Republic of)",
    628 to "Gabonese Republic",
    629 to "Congo (Republic of the)",
    630 to "Democratic Republic of the Congo",
    631 to "Angola (Republic of)",
    632 to "Guinea-Bissau (Republic of)",
    633 to "Seychelles (Republic of)",
    634 to "Sudan (Republic of the)",
    635 to "Rwanda (Republic of)",
    636 to "Ethiopia (Federal Democratic Republic of)",
    637 to "Somalia (Federal Republic of)",
    638 to "Djibouti (Republic of)",
    639 to "Kenya (Republic of)",
    640 to "Tanzania (United Republic of)",
    641 to "Uganda (Republic of)",
    642 to "Burundi (Republic of)",
    643 to "Mozambique (Republic of)",
    645 to "Zambia (Republic of)",
    646 to "Madagascar (Republic of)",
    647 to "French Departments and Territories in the Indian Ocean",
    648 to "Zimbabwe (Republic of)",
    649 to "Namibia (Republic of)",
    650 to "Malawi",
    651 to "Lesotho (Kingdom of)",
    652 to "Botswana (Republic of)",
    653 to "Swaziland (Kingdom of)",
    654 to "Comoros (Union of the)",
    655 to "South Africa (Republic of)",
    657 to "Eritrea",
    658 to "Saint Helena, Ascension and Tristan da Cunha",
    659 to "South Sudan (Republic of)",
    702 to "Belize",
    704 to "Guatemala (Republic of)",
    706 to "El Salvador (Republic of)",
    708 to "Honduras (Republic of)",
    710 to "Nicaragua",
    712 to "Costa Rica",
    714 to "Panama (Republic of)",
    716 to "Peru",
    722 to "Argentine Republic",
    724 to "Brazil (Federative Republic of)",
    730 to "Chile",
    732 to "Colombia (Republic of)",
    734 to "Venezuela (Bolivarian Republic of)",
    736 to "Bolivia (Plurinational State of)",
    738 to "Guyana",
    740 to "Ecuador",
    742 to "French Guiana (French Department of)",
    744 to "Paraguay (Republic of)",
    746 to "Suriname (Republic of)",
    748 to "Uruguay (Eastern Republic of)",
    750 to "Falkland Islands (Malvinas)",
    901 to "International Mobile, shared code",
)
```

- [ ] **Step 2: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: FAIL — `report.semanticChecks` is empty (Task 1 returns
`emptyList()`), and `validateJsonSyntax` doesn't exist yet.

- [ ] **Step 4: Implement**

Add to the bottom of `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`:

```kotlin
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
```

Then, in `SefIntegrityAnalyzer.analyze`, replace the line
`// Task 2 appends semantic checks here, iterating \`fieldBlocks\`.` with:

```kotlin
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: PASS, all 12 tests (7 from Task 1 + 5 new).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MccCountryNames.kt app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt
git commit -m "feat: semantic value validation for SEF trailer fields

Adds four semantic checks operating on the field blocks Task 1's
structural walk already validated: UTC timestamp plausibility (2000-01-01
through one year from now), MCC format + ITU E.212 Annex A country
lookup, strict JSON syntax validation for JSON-shaped fields (a real
recursive-descent validator, not SefdBoxDecoder's bracket-depth-only
prettyPrintJson), and MotionPhoto_Data's video_offset+video_length
against the actual file length.

MccCountryNames.kt is sourced directly from the official ITU-T E.212
Annex A PDF (extracted via pdftotext -layout during this plan's writing,
spot-verified against the raw text including the MCC 450/467 pair a
prior, never-shipped Wikipedia-sourced table had swapped) -- not a
secondary source.

See docs/superpowers/specs/2026-09-26-sef-integrity-check-design.md"
```

---

### Task 3: Report window + menu wiring

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`

**Interfaces:**
- Consumes: `SefIntegrityAnalyzer.analyze(...)`, `SefIntegrityReport`,
  `SefCheckResult`, `SefIntegritySeverity` (Tasks 1-2). `findFirst` (existing
  helper already used by `MotionPhotoExtractor.kt`/`MediaSummaryBuilder.kt`
  for the identical `{ it.type == "sefd" }` lookup pattern).

- [ ] **Step 1: Add I18n labels**

In `app/src/main/kotlin/com/multiviewer/ui/I18n.kt`, find
`fun menuBitstreamCorruption(lang: AppLanguage)` (or the nearest existing
`menu*` function) and add nearby:

```kotlin
    fun menuSefIntegrityCheck(lang: AppLanguage) = if (lang == AppLanguage.KO) "SEF 무결성 검사" else "SEF Integrity Check"
```

- [ ] **Step 2: Create the report window**

Create `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File

private fun severityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

private fun severityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
private fun SeverityBadge(severity: SefIntegritySeverity) {
    val color = severityColor(severity)
    Text(
        severityBadgeText(severity),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun CheckSection(title: String, checks: List<SefCheckResult>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { CheckRow(it) }
}

@Composable
fun SefIntegrityWindow(file: File, sefdOffset: Long, sefdHeaderSize: Int, sefdSize: Long, onCloseRequest: () -> Unit) {
    var report by remember { mutableStateOf<SefIntegrityReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    remember {
        try {
            ByteReader.open(file).use { reader ->
                report = SefIntegrityAnalyzer.analyze(reader, sefdOffset, sefdHeaderSize, sefdSize, file.length())
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        true
    }

    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 640.dp, height = 720.dp), title = "SEF Integrity Check") {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val currentReport = report
            when {
                error != null -> Text("Error: $error", color = Color.Red)
                currentReport == null -> Text("Analyzing...")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeverityBadge(currentReport.overallSeverity)
                        val issueCount = (currentReport.structuralChecks + currentReport.semanticChecks)
                            .count { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL }
                        Text("$issueCount issue(s) found", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        item { CheckSection("구조적 검사 (Structural Checks)", currentReport.structuralChecks) }
                        item { CheckSection("필드별 의미론 검사 (Semantic Checks)", currentReport.semanticChecks) }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire the menu item**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find the block containing
the `menuAvSyncAnalysis`/`menuBitstreamCorruption` `Item(...)` entries
(around the `avSyncWindowOpen`/`bitstreamCorruptionWindowOpen` state
declarations, per this plan's earlier grep of lines 391 and 470-500) and:

1. Add a new state variable alongside `avSyncWindowOpen`:
```kotlin
        var sefIntegrityWindowOpen by remember { mutableStateOf(false) }
```

2. In the same menu block as the `menuAvSyncAnalysis`/`menuBitstreamCorruption`
   items, compute `hasSefData` and add the new menu item right after
   `menuBitstreamCorruption`'s `Item(...)` block:
```kotlin
                val hasSefData = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } } != null
                Item(
                    I18n.menuSefIntegrityCheck(language),
                    enabled = hasSefData,
                    onClick = { sefIntegrityWindowOpen = true },
                )
```
(No keyboard shortcut assigned — mirror `menuGenerateAiPrompt`'s use of one
if this plan's reviewer finds an unused shortcut letter, but this is not
required.)

3. Near where `AvSyncAnalysisWindow`/`BitstreamCorruptionWindow` are
   conditionally rendered (around line 762-780 per this plan's earlier
   `grep`), add:
```kotlin
            if (sefIntegrityWindowOpen) {
                val sefdNode = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } }
                if (sefdNode != null && currentTab != null) {
                    SefIntegrityWindow(
                        file = currentTab.file,
                        sefdOffset = sefdNode.offset,
                        sefdHeaderSize = sefdNode.headerSize,
                        sefdSize = sefdNode.size,
                        onCloseRequest = { sefIntegrityWindowOpen = false },
                    )
                } else {
                    sefIntegrityWindowOpen = false
                }
            }
```

If `findFirst` is not already visible at this point in `Main.kt` (it lives
in `com.multiviewer.parser`, already used by `MotionPhotoExtractor.kt`/
`MediaSummaryBuilder.kt` in that same package), check `Main.kt`'s existing
imports — if it already imports `com.multiviewer.parser.*` (likely, given
`BoxNode`/`MediaType` etc. are already used directly in the surrounding
code this plan quoted), no new import is needed; otherwise add
`import com.multiviewer.parser.findFirst`.

- [ ] **Step 4: Compile and smoke-check**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures (this task adds no new unit
tests of its own — Compose UI in this codebase is verified manually, matching
`AvSyncAnalysisWindow.kt`/`BitstreamCorruptionWindow.kt`'s own precedent of
having no dedicated test file).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt app/src/main/kotlin/com/multiviewer/Main.kt app/src/main/kotlin/com/multiviewer/ui/I18n.kt
git commit -m "feat: SEF Integrity Check menu item and report window

Adds a new menu item, enabled only when the current tab's parsed tree
contains a sefd node (covers both the JPEG-trailer and HEIC-box cases
automatically, since both already parse into the same sefd-typed node).
Opens SefIntegrityWindow, styled like the existing AvSyncAnalysisWindow --
overall verdict badge + issue count, then Structural and Semantic check
sections listing every individual check result.

See docs/superpowers/specs/2026-09-26-sef-integrity-check-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Open a real Motion Photo / SEF-bearing JPEG (a
  Samsung Galaxy Motion Photo, if available in this environment or from the
  user) through the live GUI. Confirm the new menu item is enabled, opens
  the window, and every structural check shows PASS with no warnings for a
  well-formed real file; confirm semantic checks render sensibly (a real
  UTC timestamp near the photo's actual capture date, a real MCC mapping to
  a plausible country, JSON fields — if present — passing strict validation).
- [ ] **Step 4 (manual)**: Open a JPEG/HEIC with no SEF data at all and
  confirm the menu item is disabled (greyed out), not merely producing an
  empty report.
