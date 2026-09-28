# SEF Integrity Check Directory Entry Table Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the SEF Integrity Check window's flat, scattered
per-entry structural rows ("Entry #N (marker ...)", "Entry #N marker
match", "Entry #N name_size") with a genuine table — one row per SEFH
directory entry, showing name/marker/declared offset/declared
length/computed data location/in-bounds/marker-match/status — plus a
promoted standalone summary line for the declared-vs-found entry count.

**Architecture:** `SefIntegrityAnalyzer.kt` gains a new `SefDirectoryEntryRow`
data class and two new `SefIntegrityReport` fields (`declaredEntryCount:
Long?`, `directoryEntries: List<SefDirectoryEntryRow>`), populated by
merging the existing bounds-check loop and marker/name_size-check loop
(previously two separate loops emitting flat `SefCheckResult`s) into one
loop that builds structured rows instead — no new verification logic, the
same values are just captured differently. `SefIntegrityWindow.kt` renders
the new fields as a summary line + table, replacing that portion of the
existing flat "구조적 검사" list.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 (`kotlin("test-junit5")`).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-28-sef-integrity-directory-table-design.md`.
- No new verification logic — every value the table shows is already
  computed by the existing analyzer code; this plan only changes how that
  data is captured and displayed.
- `structuralChecks` keeps SEFT tail magic, SEFH header position, SEFH
  header magic, field block overlap, and field block gaps (whole-trailer
  or cross-entry checks, not per-single-entry). The three existing
  per-entry checks ("Entry #N (marker ...)" bounds, "Entry #N marker
  match", "Entry #N name_size") and "Directory entry count" are REMOVED
  from `structuralChecks` — their information moves to the new
  `directoryEntries`/`declaredEntryCount` fields instead. No duplication
  between the flat list and the table.
- `semanticChecks` is completely unchanged (UTC timestamp, MCC, JSON
  validity, MotionPhoto_Data bounds, and the "Entry #N semantic check"
  SKIPPED placeholders all keep their exact existing labels/behavior).
- `declaredEntryCount` is `null` (not `0`) whenever the SEFH count field
  was never reached (SEFT tail magic bad, SEFH position out of bounds,
  SEFH magic bad) — distinguishing "genuinely 0 entries declared" from
  "couldn't even read the count."

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt` | Add `SefDirectoryEntryRow`, add `declaredEntryCount`/`directoryEntries` to `SefIntegrityReport`, merge the two per-entry loops into one that builds rows instead of flat checks (Task 1). |
| `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt` | Update 5 existing tests whose assertions reference the now-removed flat labels; add tests for the new fields (Task 1). |
| `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt` | Add a count-summary line + directory-entry table, rendered between the existing "구조적 검사" and "필드별 의미론 검사" sections (Task 2). |

---

### Task 1: Structured directory-entry data in `SefIntegrityAnalyzer`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`

**Interfaces:**
- Consumes: nothing new (this task only restructures existing internal analyzer logic).
- Produces: `data class SefDirectoryEntryRow(entryIndex: Int, markerHex: String, name: String?, declaredOffset: Long, declaredLength: Long, computedDataStart: Long, computedDataEnd: Long, inBounds: Boolean, markerMatches: Boolean?, status: SefIntegritySeverity)`, and `SefIntegrityReport`'s two new fields `declaredEntryCount: Long?` and `directoryEntries: List<SefDirectoryEntryRow>` — both consumed by Task 2's window.

- [ ] **Step 1: Update the existing tests that assert on now-removed flat labels**

These 5 existing tests currently assert on `structuralChecks` labels that
this task removes ("Entry #N (marker ...)", "Entry #N marker match",
"Entry #N name_size", "Directory entry count"). Update each to assert on
the new `directoryEntries`/`declaredEntryCount` fields instead, keeping
every other part of each test (trailer construction, byte corruption)
unchanged.

In `app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt`,
find:
```kotlin
            // Both entries individually present, not collapsed
            assertTrue(report.structuralChecks.any { it.label.contains("Entry #1") })
            assertTrue(report.structuralChecks.any { it.label.contains("Entry #2") })
```
Replace with:
```kotlin
            // Both entries individually present, not collapsed
            assertEquals(2, report.directoryEntries.size)
            assertTrue(report.directoryEntries.all { it.status == SefIntegritySeverity.PASS })
```

Find:
```kotlin
            assertTrue(report.structuralChecks.any { it.label == "Directory entry count" && it.severity == SefIntegritySeverity.SKIPPED })
```
Replace with:
```kotlin
            assertEquals(null, report.declaredEntryCount)
```

Find:
```kotlin
            val entry1 = report.structuralChecks.first { it.label.contains("Entry #1") }
            val entry2 = report.structuralChecks.first { it.label.contains("Entry #2") }
            assertEquals(SefIntegritySeverity.PASS, entry1.severity)
            assertEquals(SefIntegritySeverity.CRITICAL, entry2.severity)
            assertTrue(entry2.detail.contains("exceeds trailer bounds"))
```
Replace with:
```kotlin
            val entry1 = report.directoryEntries.first { it.entryIndex == 1 }
            val entry2 = report.directoryEntries.first { it.entryIndex == 2 }
            assertEquals(SefIntegritySeverity.PASS, entry1.status)
            assertEquals(SefIntegritySeverity.CRITICAL, entry2.status)
            assertEquals(false, entry2.inBounds)
```

Find:
```kotlin
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
```
Replace with:
```kotlin
    fun `a block marker that disagrees with its directory entry is CRITICAL`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0b01, "A", "aa".toByteArray()))).copyOf()
        // The block's own marker lives at blockStart+2 -- blockStart is 0 for the only/first block.
        trailer.putUInt16LE(2, 0x9999) // block's own marker now disagrees with its directory entry (0x0b01)
        byteReaderOf(trailer, "sef-marker-mismatch").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val row = report.directoryEntries.single()
            assertEquals(false, row.markerMatches)
            assertEquals(SefIntegritySeverity.CRITICAL, row.status)
        }
    }
```

Find:
```kotlin
            val nameSizeCheck = report.structuralChecks.first { it.label == "Entry #1 name_size" }
            assertEquals(SefIntegritySeverity.CRITICAL, nameSizeCheck.severity)
```
Replace with:
```kotlin
            val row = report.directoryEntries.single()
            assertEquals(SefIntegritySeverity.CRITICAL, row.status)
            assertEquals(null, row.name)
```

Find:
```kotlin
            val countCheck = report.structuralChecks.first { it.label == "Directory entry count" }
            assertEquals(SefIntegritySeverity.CRITICAL, countCheck.severity)
            assertTrue(countCheck.detail.contains("5"))
            assertTrue(countCheck.detail.contains("2"))
```
Replace with:
```kotlin
            assertEquals(5L, report.declaredEntryCount)
            assertEquals(2, report.directoryEntries.size)
```

Find:
```kotlin
            assertEquals(
                SefIntegritySeverity.SKIPPED,
                report.structuralChecks.first { it.label == "Directory entry count" }.severity,
            )
```
Replace with:
```kotlin
            assertEquals(null, report.declaredEntryCount)
```

- [ ] **Step 2: Add new tests for the added fields**

Add these 3 test methods inside the existing `class SefIntegrityAnalyzerTest { ... }` (anywhere among the other tests, e.g. right after the "an out-of-bounds directory entry..." test):

```kotlin
    @Test
    fun `directoryEntries reports the exact declared offset, length, and computed data range for a well-formed entry`() {
        val trailer = buildSefTrailer(listOf(SefTestField(0x0b01, "Hello", "world".toByteArray())))
        byteReaderOf(trailer, "sef-row-values").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val row = report.directoryEntries.single()
            assertEquals(1, row.entryIndex)
            assertEquals("0x0b01", row.markerHex)
            assertEquals("Hello", row.name)
            assertEquals(true, row.inBounds)
            assertEquals(true, row.markerMatches)
            assertEquals(SefIntegritySeverity.PASS, row.status)
            // The whole single-block trailer starts at byte 0 and ends at the SEFH position --
            // computedDataStart/End must exactly bound that one block.
            assertEquals(0L, row.computedDataStart)
            assertEquals(row.computedDataStart + row.declaredLength, row.computedDataEnd)
        }
    }

    @Test
    fun `directoryEntries marks an out-of-bounds entry with null name and null markerMatches (unreachable)`() {
        val trailer = buildSefTrailer(
            listOf(
                SefTestField(0x0b01, "Good", "ok".toByteArray()),
                SefTestField(0x0c01, "Bad", "x".toByteArray()),
            ),
        ).copyOf()
        val block1Size = 8 + ("Good".toByteArray().size + 1) + 2
        val block2Size = 8 + ("Bad".toByteArray().size + 1) + 1
        val dirStart = block1Size + block2Size
        val entry2Pos = dirStart + 12 + 12
        trailer.putUInt32LE(entry2Pos + 4, 999_999L)
        byteReaderOf(trailer, "sef-row-oob").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            val badRow = report.directoryEntries.first { it.entryIndex == 2 }
            assertEquals(false, badRow.inBounds)
            assertEquals(null, badRow.name)
            assertEquals(null, badRow.markerMatches)
            assertEquals(SefIntegritySeverity.CRITICAL, badRow.status)
        }
    }

    @Test
    fun `declaredEntryCount matches directoryEntries size for a well-formed trailer`() {
        val trailer = buildSefTrailer(
            listOf(
                SefTestField(0x0b01, "A", "aa".toByteArray()),
                SefTestField(0x0b02, "B", "bb".toByteArray()),
            ),
        )
        byteReaderOf(trailer, "sef-count-match").use { reader ->
            val report = SefIntegrityAnalyzer.analyze(reader, 0L, 0, trailer.size.toLong(), trailer.size.toLong())
            assertEquals(2L, report.declaredEntryCount)
            assertEquals(2, report.directoryEntries.size)
        }
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: FAIL to compile (`SefDirectoryEntryRow`, `report.directoryEntries`, `report.declaredEntryCount` don't exist yet).

- [ ] **Step 4: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt`, find:
```kotlin
data class SefIntegrityReport(
    val overallSeverity: SefIntegritySeverity,
    val structuralChecks: List<SefCheckResult>,
    val semanticChecks: List<SefCheckResult>,
)
```
Replace with:
```kotlin
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
)
```

**Critical: `overallSeverityOf` must also see `directoryEntries`' statuses.** The
per-entry CRITICAL checks (out-of-bounds, marker mismatch, name_size
overrun) are being REMOVED from `structural` in this task — their
severity now lives only in each row's `status` field. Without this next
change, `overallSeverity` would stop reflecting those failures entirely
(a trailer with only an out-of-bounds entry would wrongly compute
`overallSeverity = PASS`) — a real regression, not just a cosmetic
change. Find:
```kotlin
private fun overallSeverityOf(results: List<SefCheckResult>): SefIntegritySeverity {
    val severities = results.map { it.severity }
    return when {
        SefIntegritySeverity.CRITICAL in severities -> SefIntegritySeverity.CRITICAL
        SefIntegritySeverity.WARNING in severities -> SefIntegritySeverity.WARNING
        else -> SefIntegritySeverity.PASS
    }
}
```
Replace with:
```kotlin
private fun overallSeverityOf(severities: List<SefIntegritySeverity>): SefIntegritySeverity {
    return when {
        SefIntegritySeverity.CRITICAL in severities -> SefIntegritySeverity.CRITICAL
        SefIntegritySeverity.WARNING in severities -> SefIntegritySeverity.WARNING
        else -> SefIntegritySeverity.PASS
    }
}
```
(Signature changed from `List<SefCheckResult>` to `List<SefIntegritySeverity>`
so the caller can combine `SefCheckResult` severities with
`SefDirectoryEntryRow` statuses in one list — see the `finish()` change
right below, its only call site.)

Find:
```kotlin
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size

        fun finish() = SefIntegrityReport(overallSeverityOf(structural + semantic), structural, semantic)
```
Replace with:
```kotlin
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size

        var declaredEntryCount: Long? = null
        var directoryEntries: List<SefDirectoryEntryRow> = emptyList()

        fun finish() = SefIntegrityReport(
            overallSeverityOf((structural + semantic).map { it.severity } + directoryEntries.map { it.status }),
            structural, semantic, declaredEntryCount, directoryEntries,
        )
```

Find the entire block from `val declaredCount = readUInt32LE(reader, sefhPosition + 8)` through the end of the `for (e in inBoundsEntries) { ... }` loop that builds `fieldBlocks` (this spans the directory-walking loop, the old "Directory entry count" check, the old per-entry bounds-check loop, the overlap/gap checks, and the old marker-match/name_size loop):
```kotlin
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
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- depends on Entry #${e.index} block header"))
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
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- depends on Entry #${e.index} name_size"))
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
```

Replace with:
```kotlin
        val declaredCount = readUInt32LE(reader, sefhPosition + 8)
        declaredEntryCount = declaredCount

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
            val blockMarker = readUInt16LE(reader, e.blockStart + 2)
            val markerMatches = blockMarker == e.marker
            val nameSize = readUInt32LE(reader, e.blockStart + 4)
            val nameOffset = e.blockStart + 8
            if (nameOffset + nameSize > e.blockEnd) {
                directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, null, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, markerMatches, SefIntegritySeverity.CRITICAL))
                semantic.add(SefCheckResult(SefIntegritySeverity.SKIPPED, "Entry #${e.index} semantic check", "Skipped -- depends on Entry #${e.index} name_size"))
                continue
            }
            val nameBytes = reader.readBytes(nameOffset, nameSize.toInt())
            val name = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
            val status = if (markerMatches) SefIntegritySeverity.PASS else SefIntegritySeverity.CRITICAL
            directoryEntryRows.add(SefDirectoryEntryRow(e.index, markerLabel, name, declaredOffset, declaredLength, e.blockStart, e.blockEnd, true, markerMatches, status))
            val fieldHeaderSize = (8 + nameSize).toInt()
            val dataStart = e.blockStart + fieldHeaderSize
            val dataLength = (e.blockEnd - dataStart).toInt()
            fieldBlocks.add(SefFieldBlock(e.index, e.marker, name, dataStart, dataLength))
        }
        directoryEntries = directoryEntryRows
```

(Note: a marker mismatch does NOT `continue` -- semantic checks still run on
a field whose block marker disagrees with its directory entry, exactly
matching the original code's behavior of still adding it to `fieldBlocks`.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefIntegrityAnalyzerTest"`
Expected: PASS, all tests (the existing suite's other tests, e.g. overlap/gap/UTC/MCC/JSON/MotionPhoto_Data, are untouched by this change and must still pass unmodified).

- [ ] **Step 6: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures. (This will also catch
`SefIntegrityWindow.kt` if it references any now-removed flat check
labels -- it currently doesn't reference per-entry labels directly, only
renders whatever `structuralChecks` contains generically, so it should
still compile as-is until Task 2 updates it.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefIntegrityAnalyzer.kt app/src/test/kotlin/com/multiviewer/parser/SefIntegrityAnalyzerTest.kt
git commit -m "feat: structured per-entry data in SefIntegrityReport

Adds SefDirectoryEntryRow and two new SefIntegrityReport fields
(declaredEntryCount, directoryEntries), replacing the flat per-entry
structural checks (bounds/marker-match/name_size, 3 scattered rows per
entry) with structured data a caller can render as a table. No new
verification logic -- the same values the flat checks already computed
are now captured into rows instead of formatted strings. semanticChecks
and the whole-trailer/cross-entry structural checks (SEFT/SEFH/overlap/
gap) are unchanged.

See docs/superpowers/specs/2026-09-28-sef-integrity-directory-table-design.md"
```

---

### Task 2: Directory entry table in the report window

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt`

**Interfaces:**
- Consumes: `SefIntegrityReport.declaredEntryCount: Long?`, `SefIntegrityReport.directoryEntries: List<SefDirectoryEntryRow>`, `SefDirectoryEntryRow` (Task 1).

This task has no new unit tests (Compose UI rendering, matching this
window's existing precedent of no dedicated test file). Verify via
`./gradlew compileKotlin` and Task 3's manual verification.

- [ ] **Step 1: Widen the window and add scroll room for the table**

Find:
```kotlin
    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 640.dp, height = 720.dp), title = "SEF 무결성 검사 - ${file.name}") {
```
Replace with:
```kotlin
    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 900.dp, height = 760.dp), title = "SEF 무결성 검사 - ${file.name}") {
```

- [ ] **Step 2: Add the count-summary and table composables**

Add these new composables to `app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt`, right after the existing `CheckSection` composable:

```kotlin
@Composable
private fun DirectoryEntryCountSummary(declaredCount: Long?, foundCount: Int) {
    val severity = when {
        declaredCount == null -> SefIntegritySeverity.SKIPPED
        declaredCount == foundCount.toLong() -> SefIntegritySeverity.PASS
        else -> SefIntegritySeverity.CRITICAL
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(severity)
        Text(
            if (declaredCount == null) {
                "SEFH 선언 엔트리 수: 확인 불가 (상위 검사 실패)"
            } else {
                "SEFH 선언 엔트리 수: ${declaredCount}개, 실제 발견: ${foundCount}개"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DirectoryEntryTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("이름", modifier = Modifier.width(140.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커", modifier = Modifier.width(64.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 오프셋", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 길이", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("실제 위치(시작~끝)", modifier = Modifier.width(170.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("범위 내", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커 일치", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("상태", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun DirectoryEntryTableRow(row: SefDirectoryEntryRow) {
    val color = if (row.status == SefIntegritySeverity.CRITICAL) Color(0xFFC62828) else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${row.entryIndex}", modifier = Modifier.width(28.dp), fontSize = 11.sp, color = color)
        Text(row.name ?: "—", modifier = Modifier.width(140.dp), fontSize = 11.sp, color = color)
        Text(row.markerHex, modifier = Modifier.width(64.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredOffset}", modifier = Modifier.width(90.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredLength}", modifier = Modifier.width(80.dp), fontSize = 11.sp, color = color)
        Text("${row.computedDataStart}~${row.computedDataEnd}", modifier = Modifier.width(170.dp), fontSize = 11.sp, color = color)
        Text(if (row.inBounds) "✓" else "✗", modifier = Modifier.width(60.dp), fontSize = 11.sp, color = color)
        Text(
            when (row.markerMatches) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            },
            modifier = Modifier.width(70.dp), fontSize = 11.sp, color = color,
        )
        Box(modifier = Modifier.width(90.dp)) { SeverityBadge(row.status) }
    }
}

@Composable
private fun DirectoryEntryTable(declaredCount: Long?, entries: List<SefDirectoryEntryRow>) {
    Text("SEFH 디렉토리 엔트리", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    DirectoryEntryCountSummary(declaredCount, entries.size)
    if (entries.isEmpty()) return
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scrollState)) {
        DirectoryEntryTableHeader()
        entries.forEach { DirectoryEntryTableRow(it) }
    }
}
```

- [ ] **Step 3: Wire the new table into the report's `LazyColumn`**

Find:
```kotlin
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        item { CheckSection("구조적 검사", currentReport.structuralChecks) }
                        item { CheckSection("필드별 의미론 검사", currentReport.semanticChecks) }
                    }
```
Replace with:
```kotlin
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        item { CheckSection("구조적 검사", currentReport.structuralChecks) }
                        item { DirectoryEntryTable(currentReport.declaredEntryCount, currentReport.directoryEntries) }
                        item { CheckSection("필드별 의미론 검사", currentReport.semanticChecks) }
                    }
```

- [ ] **Step 4: Add the two new imports this task needs**

Find:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
```
Replace with:
```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
```

Find:
```kotlin
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
```
Replace with:
```kotlin
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
```

(Check the existing import list first — `Modifier.fillMaxWidth`, `Column`,
`Row`, `Text`, `Color`, `FontWeight`, `dp`, `sp`, `Arrangement`,
`Alignment` are already imported in this file per its existing code; only
add the ones genuinely missing among the ones shown above.)

- [ ] **Step 5: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/SefIntegrityWindow.kt
git commit -m "feat: render SEF directory entries as a table

New DirectoryEntryTable (+ count summary line) replaces the flat
per-entry structural rows in the report window: one row per SEFH
directory entry showing name/marker/declared offset/declared length/
computed data location/in-bounds/marker-match/status, horizontally
scrollable. Window widened from 640dp to 900dp to give the table more
room without immediately needing the scroll.

See docs/superpowers/specs/2026-09-28-sef-integrity-directory-table-design.md"
```

---

### Task 3: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Open a real Samsung SEF file (or synthesize one
  via `MotionPhotoBuilder.createSamsungHeicMotionPhoto`, matching this
  project's established manual-verification approach for SEF-bearing
  files) in the "SEF 무결성 검사" window and confirm: the count-summary
  line shows matching declared/found numbers with a PASS badge, the table
  renders one row per real SEF field (Image_UTC_Data, MCC_Data,
  MotionPhoto_Data, etc.) with sensible offset/length/computed-location
  values and all-PASS status, and the flat "구조적 검사"/"필드별 의미론
  검사" sections above/below the table still render correctly.
- [ ] **Step 4 (manual)**: Confirm the same real file opened via the
  "모션포토 정합성 검사" window's embedded SEF section still shows
  correctly (that window delegates to the same `SefIntegrityAnalyzer.analyze()`
  and consumes only `structuralChecks`/`semanticChecks`, not the new
  fields — confirm it still compiles/renders without needing changes,
  matching this plan's stated non-goal of not touching that window).
