# Video Overview Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface already-parsed video metadata (timestamps, per-track duration, handler/language, NAL/parameter-set structure, edit lists, keyframe interval, B-frame usage) in the Overview tab for MP4-family and WebM video files, plus fix a pre-existing WebM date-formatting bug found while planning this work.

**Architecture:** Additive changes to five existing functions in `MediaSummaryBuilder.kt` (no new sections for most of it) plus one brand-new "Video Detail" section for MP4-family files. One small, justified parser fix (`formatWebmDate`) in `BinaryUtil.kt`/`EbmlWalker.kt`.

**Tech Stack:** Kotlin, existing `BoxNode`/`BoxField`/`findFirst` parser primitives, `kotlin.test` for unit tests.

## Global Constraints

- Reference design doc: `docs/superpowers/specs/2026-08-07-video-overview-detail-design.md`.
- No new box/element parsing beyond the one narrow `formatWebmDate` fix (Task 1) — everything else in this plan reuses fields already produced by existing decoders.
- Every new/added field is independently optional unless stated otherwise. The one exception: `B-Frames` in the new "Video Detail" section is **always** added once that section renders at all (a complete, authoritative "Yes"/"No" fact from `ctts` presence, not a partial heuristic).
- "Don't show an uninformative default" fields, exact rules: `Creation Time`/`Modification Time` (MP4) skip when the value starts with `"0 "`; `Language` (MP4 Video/Audio) skip when the value is `"und"`; `Stereo Mode` (WebM) skip when the value is `"0"`; `Handler Name` (MP4 Video/Audio) skip when blank.
- The new MP4-family section title is exactly `"Video Detail"` (distinct from the existing `"Video"` section — see spec's Architecture section for why they're separate).
- No changes to the Detailed Properties (tree) tab or any UI/Compose file.

---

### Task 1: Fix WebM `DateUTC` formatting (`formatWebmDate`)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BinaryUtil.kt` (add `formatWebmDate` next to the existing `formatMp4Time`)
- Modify: `app/src/main/kotlin/com/multiviewer/parser/EbmlWalker.kt:121-129` (split `DATE` out of the `UINT` branch in `decodeLeafElement`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/EbmlWalkerTest.kt` (append before the final closing `}`)

**Interfaces:**
- Produces: `internal fun formatWebmDate(nanosSince2001: Long): String` (in `BinaryUtil.kt`, same file/visibility as `formatMp4Time`). A `DateUTC`-type `BoxNode`'s `value` field and `summary` are now a formatted date string (or `"0 (not set)"` for zero) instead of a raw nanosecond integer. Task 3 consumes this via `webmFieldValue(info, "DateUTC")`.

- [ ] **Step 1: Write the failing tests**

Add before `EbmlWalkerTest`'s final closing `}`:

```kotlin
    @Test
    fun `DateUTC decodes to a formatted date string, not a raw nanosecond integer`() {
        // DateUTC (ID 0x4461, 2 bytes), size=8, value=1_000_000_000 ns after 2001-01-01T00:00:00Z
        // (0x3B9ACA00), i.e. 2001-01-01T00:00:01Z.
        val reader = byteReaderOf(
            byteArrayOf(
                0x44, 0x61.toByte(), // DateUTC element ID (2 bytes)
                0x88.toByte(), // size = 8 (1-byte VINT)
                0x00, 0x00, 0x00, 0x00, 0x3B, 0x9A.toByte(), 0xCA.toByte(), 0x00,
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("DateUTC", elements[0].type)
        assertEquals("2001-01-01T00:00:01", elements[0].fields.single { it.name == "value" }.value)
        assertEquals("2001-01-01T00:00:01", elements[0].summary)
        reader.close()
    }

    @Test
    fun `a DateUTC of 0 shows as not set, matching the MP4 zero-timestamp convention`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x44, 0x61.toByte(),
                0x88.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals("0 (not set)", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.EbmlWalkerTest"
```
Expected: FAIL — `DateUTC` currently falls into the same branch as `UINT`, producing `"1000000000"` (the raw nanosecond count) instead of a formatted date.

- [ ] **Step 3: Add `formatWebmDate`**

In `app/src/main/kotlin/com/multiviewer/parser/BinaryUtil.kt`, add right after `formatMp4Time`'s closing `}`:

```kotlin
private const val WEBM_EPOCH_OFFSET_SECONDS = 978307200L // 2001-01-01T00:00:00Z in Unix epoch seconds

internal fun formatWebmDate(nanosSince2001: Long): String {
    if (nanosSince2001 == 0L) return "0 (not set)"
    return try {
        val instant = Instant.ofEpochSecond(WEBM_EPOCH_OFFSET_SECONDS + nanosSince2001 / 1_000_000_000L, nanosSince2001 % 1_000_000_000L)
        ISO_DATE_FORMATTER.format(instant)
    } catch (e: Exception) {
        nanosSince2001.toString()
    }
}
```

- [ ] **Step 4: Split `DATE` out of the `UINT` branch in `EbmlWalker.kt`**

Find (`decodeLeafElement`, around line 121-129):
```kotlin
    return when (type) {
        EbmlElementType.UINT, EbmlElementType.DATE -> {
            val value = readUnsignedBigEndian(reader, dataStart, dataLength)
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value.toString(), dataStart, dataLength.toLong())),
                summary = value.toString(), warnings = warnings,
            )
        }
```

Replace with:
```kotlin
    return when (type) {
        EbmlElementType.UINT -> {
            val value = readUnsignedBigEndian(reader, dataStart, dataLength)
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", value.toString(), dataStart, dataLength.toLong())),
                summary = value.toString(), warnings = warnings,
            )
        }
        EbmlElementType.DATE -> {
            val value = readUnsignedBigEndian(reader, dataStart, dataLength)
            val formatted = formatWebmDate(value)
            BoxNode(
                name, offset, headerSize, size,
                fields = listOf(BoxField("value", formatted, dataStart, dataLength.toLong())),
                summary = formatted, warnings = warnings,
            )
        }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.EbmlWalkerTest"
```
Expected: PASS, both new tests plus all pre-existing `EbmlWalkerTest` cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/BinaryUtil.kt app/src/main/kotlin/com/multiviewer/parser/EbmlWalker.kt app/src/test/kotlin/com/multiviewer/parser/EbmlWalkerTest.kt
git commit -m "Fix WebM DateUTC to format as a date instead of a raw nanosecond integer"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 2: General section enrichment (MP4) — timestamps and per-track duration

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildVideoSummary` at line 681-694, `buildVideoGeneral` at line 701-722)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `mvhd`'s existing `creation_time`/`modification_time` fields (already formatted or `"0 (not set)"` via `formatMp4Time`); each track's own `mdhd` `timescale`/`duration` fields.
- Produces: `buildVideoGeneral` gains two new parameters (`videoTrak: BoxNode?, audioTrak: BoxNode?`); a new private helper `private fun trackDuration(trak: BoxNode?): String?`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `mvhd creation_time and modification_time appear in General when actually set`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val mvhd = BoxNode(
            type = "mvhd", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("timescale", "1000", 0, 4),
                BoxField("duration", "5000", 0, 4),
                BoxField("creation_time", "2026-01-15T10:30:00", 0, 4),
                BoxField("modification_time", "2026-01-16T09:00:00", 0, 4),
            ),
        )
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("2026-01-15T10:30:00", general.fields.first { it.label == "Creation Time" }.value)
        assertEquals("2026-01-16T09:00:00", general.fields.first { it.label == "Modification Time" }.value)
    }

    @Test
    fun `mvhd creation_time and modification_time of 0 (not set) are omitted from General`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val mvhd = BoxNode(
            type = "mvhd", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("timescale", "1000", 0, 4),
                BoxField("duration", "5000", 0, 4),
                BoxField("creation_time", "0 (not set)", 0, 4),
                BoxField("modification_time", "0 (not set)", 0, 4),
            ),
        )
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals(null, general.fields.find { it.label == "Creation Time" })
        assertEquals(null, general.fields.find { it.label == "Modification Time" })
    }

    @Test
    fun `Video Track Duration and Audio Track Duration reflect each track's own mdhd with millisecond precision`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))

        val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
        val audioMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "10500", 0, 4)))
        val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMdhd))
        val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioMdia))

        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "20000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak, audioTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:10.000", general.fields.first { it.label == "Video Track Duration" }.value)
        assertEquals("0:00:10.500", general.fields.first { it.label == "Audio Track Duration" }.value)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — all 3 new tests throw `NoSuchElementException` (none of these 4 field labels exist yet).

- [ ] **Step 3: Add `trackDuration` and update `buildVideoGeneral`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, find `buildVideoGeneral` (currently lines 701-722):

```kotlin
private fun buildVideoGeneral(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()

    val mvhd = moov?.children?.find { it.type == "mvhd" }
    val timescale = mvhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mvhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val durationSeconds = if (timescale != null && timescale > 0 && duration != null) duration.toDouble() / timescale else null
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }

    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Format", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }

    return SummarySection("General", fields)
}
```

Replace with:

```kotlin
private fun buildVideoGeneral(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?, videoTrak: BoxNode?, audioTrak: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()

    val mvhd = moov?.children?.find { it.type == "mvhd" }
    val timescale = mvhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mvhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val durationSeconds = if (timescale != null && timescale > 0 && duration != null) duration.toDouble() / timescale else null
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }

    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Format", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }

    mvhd?.fields?.find { it.name == "creation_time" }?.value?.takeIf { !it.startsWith("0 ") }?.let {
        fields.add(SummaryField("Creation Time", it))
    }
    mvhd?.fields?.find { it.name == "modification_time" }?.value?.takeIf { !it.startsWith("0 ") }?.let {
        fields.add(SummaryField("Modification Time", it))
    }

    trackDuration(videoTrak)?.let { fields.add(SummaryField("Video Track Duration", it)) }
    trackDuration(audioTrak)?.let { fields.add(SummaryField("Audio Track Duration", it)) }

    return SummarySection("General", fields)
}

private fun trackDuration(trak: BoxNode?): String? {
    if (trak == null) return null
    val mdhd = findFirst(trak) { it.type == "mdhd" }
    val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    if (timescale == null || timescale <= 0 || duration == null) return null
    return formatDuration(duration.toDouble() / timescale)
}
```

- [ ] **Step 4: Update the `buildVideoGeneral` call site in `buildVideoSummary`**

Find (line 688):
```kotlin
    sections.add(buildVideoGeneral(root, fileSizeBytes, moov))
```
Change to:
```kotlin
    sections.add(buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak))
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 3 new tests plus all pre-existing cases (none of the pre-existing video tests assert `mvhd` creation/modification time or per-track duration, so none break here).

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add Creation/Modification Time and per-track Duration to video General"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 3: General section enrichment (WebM) — creation date and muxing tools

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildWebmGeneral`, currently lines 632-645)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: Task 1's fixed `DateUTC` formatting via the existing `webmFieldValue(info, "DateUTC")`; `Info`'s existing `MuxingApp`/`WritingApp` children.

- [ ] **Step 1: Write the failing test**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `WebM General reports Creation Date, Muxing App, and Writing App when present`() {
        val dateUtc = BoxNode(type = "DateUTC", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "2026-01-15T10:30:01", 0, 8)))
        val muxingApp = BoxNode(type = "MuxingApp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "libwebm-0.3.0", 0, 13)))
        val writingApp = BoxNode(type = "WritingApp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "google/video-file", 0, 18)))
        val info = BoxNode(type = "Info", offset = 0, headerSize = 0, size = 0, children = listOf(dateUtc, muxingApp, writingApp))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(info))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals("2026-01-15T10:30:01", general.fields.first { it.label == "Creation Date" }.value)
        assertEquals("libwebm-0.3.0", general.fields.first { it.label == "Muxing App" }.value)
        assertEquals("google/video-file", general.fields.first { it.label == "Writing App" }.value)
    }

    @Test
    fun `WebM General omits Creation Date, Muxing App, and Writing App when absent`() {
        val info = BoxNode(type = "Info", offset = 0, headerSize = 0, size = 0)
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(info))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val general = summary.sections.first { it.title == "General" }
        assertEquals(null, general.fields.find { it.label == "Creation Date" })
        assertEquals(null, general.fields.find { it.label == "Muxing App" })
        assertEquals(null, general.fields.find { it.label == "Writing App" })
    }
```

Both fixtures include a top-level `EBML` node alongside `Segment`: `MediaSummaryBuilder.kt`'s `isWebm(root)` checks `root.children.any { it.type == "EBML" }`, and `buildMediaSummary` only routes to `buildWebmVideoSummary` when that's true — without it, `detectCategory` would classify this tree differently and neither test would reach `buildWebmGeneral` at all.

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first test throws `NoSuchElementException` (no such fields yet). The second passes already (nothing to omit yet) — that's fine, it locks in the non-regression guarantee going forward.

- [ ] **Step 3: Update `buildWebmGeneral`**

Find (currently lines 632-645):
```kotlin
private fun buildWebmGeneral(fileSizeBytes: Long, info: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val timecodeScale = webmFieldValue(info, "TimecodeScale")?.toDoubleOrNull() ?: 1_000_000.0
    val durationTicks = webmFieldValue(info, "Duration")?.toDoubleOrNull()
    val durationSeconds = durationTicks?.let { it * timecodeScale / 1_000_000_000.0 }
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }
    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))
    fields.add(SummaryField("Format", "WebM"))
    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }
    return SummarySection("General", fields)
}
```

Replace with:
```kotlin
private fun buildWebmGeneral(fileSizeBytes: Long, info: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val timecodeScale = webmFieldValue(info, "TimecodeScale")?.toDoubleOrNull() ?: 1_000_000.0
    val durationTicks = webmFieldValue(info, "Duration")?.toDoubleOrNull()
    val durationSeconds = durationTicks?.let { it * timecodeScale / 1_000_000_000.0 }
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }
    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))
    fields.add(SummaryField("Format", "WebM"))
    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Overall Bit Rate", formatBitrate(bitrate)))
    }
    webmFieldValue(info, "DateUTC")?.let { fields.add(SummaryField("Creation Date", it)) }
    webmFieldValue(info, "MuxingApp")?.let { fields.add(SummaryField("Muxing App", it)) }
    webmFieldValue(info, "WritingApp")?.let { fields.add(SummaryField("Writing App", it)) }
    return SummarySection("General", fields)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, both new tests plus all pre-existing cases.

- [ ] **Step 5: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add Creation Date, Muxing App, and Writing App to WebM General"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 4: Video/Audio section enrichment (MP4) — Handler Name and Language

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildVideoDetail` at line 738-765, `buildAudioDetail` at line 767-776)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `hdlr`'s existing `name` field; `mdhd`'s existing `language` field (both already reachable via `findFirst` inside these functions, same track parameter each function already receives).

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `Video and Audio sections report Handler Name and Language when present and not the und default`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4), BoxField("name", "VideoHandler", 0, 12)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("language", "eng", 0, 2)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))

        val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4), BoxField("name", "SoundHandler", 0, 12)))
        val audioMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("language", "kor", 0, 2)))
        val mp4a = BoxNode(type = "mp4a", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("channelcount", "2", 0, 2), BoxField("samplerate", "44100.0", 0, 4)))
        val audioStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(mp4a))
        val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMdhd, BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(audioStsd))))))
        val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioMdia))

        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak, audioTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("VideoHandler", videoDetail.fields.first { it.label == "Handler Name" }.value)
        assertEquals("eng", videoDetail.fields.first { it.label == "Language" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("SoundHandler", audioDetail.fields.first { it.label == "Handler Name" }.value)
        assertEquals("kor", audioDetail.fields.first { it.label == "Language" }.value)
    }

    @Test
    fun `Video section omits Handler Name when blank and Language when und`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4), BoxField("name", "", 0, 0)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("language", "und", 0, 2)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals(null, videoDetail.fields.find { it.label == "Handler Name" })
        assertEquals(null, videoDetail.fields.find { it.label == "Language" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first test throws `NoSuchElementException` (no such fields yet). The second passes already.

- [ ] **Step 3: Update `buildVideoDetail` and `buildAudioDetail`**

Find `buildVideoDetail` (currently lines 738-765):
```kotlin
private fun buildVideoDetail(videoTrak: BoxNode?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val stsd = findFirst(videoTrak) { it.type == "stsd" }
    stsd?.children?.firstOrNull()?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }

    val tkhd = videoTrak.children.find { it.type == "tkhd" }
    val width = tkhd?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()
    val height = tkhd?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()
    if (width != null && height != null) {
        fields.add(SummaryField("Width", width.toInt().toString()))
        fields.add(SummaryField("Height", height.toInt().toString()))
    }

    val mdhd = findFirst(videoTrak) { it.type == "mdhd" }
    val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val sampleCount = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (timescale != null && timescale > 0 && duration != null && duration > 0 && sampleCount != null) {
        val durationSeconds = duration.toDouble() / timescale
        val fps = sampleCount / durationSeconds
        fields.add(SummaryField("Frame Rate", "%.2f fps".format(fps)))
    }

    return if (fields.isNotEmpty()) SummarySection("Video", fields) else null
}
```

Replace with:
```kotlin
private fun buildVideoDetail(videoTrak: BoxNode?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val stsd = findFirst(videoTrak) { it.type == "stsd" }
    stsd?.children?.firstOrNull()?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }

    val tkhd = videoTrak.children.find { it.type == "tkhd" }
    val width = tkhd?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()
    val height = tkhd?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()
    if (width != null && height != null) {
        fields.add(SummaryField("Width", width.toInt().toString()))
        fields.add(SummaryField("Height", height.toInt().toString()))
    }

    val mdhd = findFirst(videoTrak) { it.type == "mdhd" }
    val timescale = mdhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mdhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val sampleCount = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (timescale != null && timescale > 0 && duration != null && duration > 0 && sampleCount != null) {
        val durationSeconds = duration.toDouble() / timescale
        val fps = sampleCount / durationSeconds
        fields.add(SummaryField("Frame Rate", "%.2f fps".format(fps)))
    }

    val handlerName = findFirst(videoTrak) { it.type == "hdlr" }?.fields?.find { it.name == "name" }?.value
    if (!handlerName.isNullOrBlank()) fields.add(SummaryField("Handler Name", handlerName))
    mdhd?.fields?.find { it.name == "language" }?.value?.takeIf { it != "und" }?.let {
        fields.add(SummaryField("Language", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Video", fields) else null
}
```

Find `buildAudioDetail` (currently lines 767-776):
```kotlin
private fun buildAudioDetail(audioTrak: BoxNode?): SummarySection? {
    if (audioTrak == null) return null
    val stsd = findFirst(audioTrak) { it.type == "stsd" }
    val audioEntry = stsd?.children?.firstOrNull()
    val fields = mutableListOf<SummaryField>()
    audioEntry?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }
    audioEntry?.fields?.find { it.name == "samplerate" }?.let { fields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    audioEntry?.fields?.find { it.name == "channelcount" }?.let { fields.add(SummaryField("Channel(s)", it.value)) }
    return if (fields.isNotEmpty()) SummarySection("Audio", fields) else null
}
```

Replace with:
```kotlin
private fun buildAudioDetail(audioTrak: BoxNode?): SummarySection? {
    if (audioTrak == null) return null
    val stsd = findFirst(audioTrak) { it.type == "stsd" }
    val audioEntry = stsd?.children?.firstOrNull()
    val fields = mutableListOf<SummaryField>()
    audioEntry?.type?.let { fields.add(SummaryField("Format", CODEC_DISPLAY_NAMES[it] ?: it)) }
    audioEntry?.fields?.find { it.name == "samplerate" }?.let { fields.add(SummaryField("Sampling Rate", "${it.value} Hz")) }
    audioEntry?.fields?.find { it.name == "channelcount" }?.let { fields.add(SummaryField("Channel(s)", it.value)) }

    val handlerName = findFirst(audioTrak) { it.type == "hdlr" }?.fields?.find { it.name == "name" }?.value
    if (!handlerName.isNullOrBlank()) fields.add(SummaryField("Handler Name", handlerName))
    findFirst(audioTrak) { it.type == "mdhd" }?.fields?.find { it.name == "language" }?.value?.takeIf { it != "und" }?.let {
        fields.add(SummaryField("Language", it))
    }

    return if (fields.isNotEmpty()) SummarySection("Audio", fields) else null
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, both new tests plus all pre-existing cases.

- [ ] **Step 5: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add Handler Name and Language to video/audio track sections"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 5: WebM Video section enrichment — Stereo Mode

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildWebmVideoDetail`, currently lines 659-667)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `Video`'s existing `StereoMode` child (`webmFieldValue(video, "StereoMode")`).

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `WebM Video reports a labeled Stereo Mode when non-zero`() {
        val codecId = BoxNode(type = "CodecID", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "V_VP9", 0, 5)))
        val stereoMode = BoxNode(type = "StereoMode", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val pixelWidth = BoxNode(type = "PixelWidth", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1920", 0, 2)))
        val pixelHeight = BoxNode(type = "PixelHeight", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1080", 0, 2)))
        val video = BoxNode(type = "Video", offset = 0, headerSize = 0, size = 0, children = listOf(stereoMode, pixelWidth, pixelHeight))
        val trackType = BoxNode(type = "TrackType", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val videoTrack = BoxNode(type = "TrackEntry", offset = 0, headerSize = 0, size = 0, children = listOf(trackType, codecId, video))
        val tracks = BoxNode(type = "Tracks", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrack))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(tracks))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("Side by Side (Left Eye First)", videoDetail.fields.first { it.label == "Stereo Mode" }.value)
    }

    @Test
    fun `WebM Video omits Stereo Mode when 0 (mono)`() {
        val codecId = BoxNode(type = "CodecID", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "V_VP9", 0, 5)))
        val stereoMode = BoxNode(type = "StereoMode", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "0", 0, 1)))
        val pixelWidth = BoxNode(type = "PixelWidth", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1920", 0, 2)))
        val pixelHeight = BoxNode(type = "PixelHeight", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1080", 0, 2)))
        val video = BoxNode(type = "Video", offset = 0, headerSize = 0, size = 0, children = listOf(stereoMode, pixelWidth, pixelHeight))
        val trackType = BoxNode(type = "TrackType", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("value", "1", 0, 1)))
        val videoTrack = BoxNode(type = "TrackEntry", offset = 0, headerSize = 0, size = 0, children = listOf(trackType, codecId, video))
        val tracks = BoxNode(type = "Tracks", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrack))
        val segment = BoxNode(type = "Segment", offset = 0, headerSize = 0, size = 0, children = listOf(tracks))
        val ebml = BoxNode(type = "EBML", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ebml, segment))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals(null, videoDetail.fields.find { it.label == "Stereo Mode" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first test throws `NoSuchElementException`. The second passes already.

- [ ] **Step 3: Update `buildWebmVideoDetail`**

Find (currently lines 659-667):
```kotlin
private fun buildWebmVideoDetail(videoTrack: BoxNode?): SummarySection? {
    if (videoTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(videoTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val video = videoTrack.children.find { it.type == "Video" }
    webmFieldValue(video, "PixelWidth")?.let { fields.add(SummaryField("Width", it)) }
    webmFieldValue(video, "PixelHeight")?.let { fields.add(SummaryField("Height", it)) }
    return if (fields.isEmpty()) null else SummarySection("Video", fields)
}
```

Replace with:
```kotlin
private val WEBM_STEREO_MODE_NAMES = mapOf(
    1 to "Side by Side (Left Eye First)",
    2 to "Top-Bottom (Right Eye First)",
    3 to "Top-Bottom (Left Eye First)",
    11 to "Side by Side (Right Eye First)",
)

private fun buildWebmVideoDetail(videoTrack: BoxNode?): SummarySection? {
    if (videoTrack == null) return null
    val fields = mutableListOf<SummaryField>()
    webmCodecDisplayName(webmFieldValue(videoTrack, "CodecID"))?.let { fields.add(SummaryField("Format", it)) }
    val video = videoTrack.children.find { it.type == "Video" }
    webmFieldValue(video, "PixelWidth")?.let { fields.add(SummaryField("Width", it)) }
    webmFieldValue(video, "PixelHeight")?.let { fields.add(SummaryField("Height", it)) }
    webmFieldValue(video, "StereoMode")?.toIntOrNull()?.takeIf { it != 0 }?.let { mode ->
        fields.add(SummaryField("Stereo Mode", WEBM_STEREO_MODE_NAMES[mode] ?: mode.toString()))
    }
    return if (fields.isEmpty()) null else SummarySection("Video", fields)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, both new tests plus all pre-existing cases.

- [ ] **Step 5: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add Stereo Mode to WebM Video section"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 6: New "Video Detail" section (MP4-family) — NAL/parameter sets, edit list, keyframe interval, B-frames

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildVideoSummary` at line 681-694; new functions added after `buildAudioDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (including two pre-existing tests that need their section-count assertion updated — see Step 1's note)

**Interfaces:**
- Consumes: `avcC`/`hvcC`'s existing `length_size`/`num_sps`/`num_pps`/`num_vps` fields; `elst`'s existing repeated `segment_duration`/`media_time`/`media_rate` fields; `stss`'s/`stsz`'s existing `table.entryCount`/`sample_count`; `ctts`'s existing presence/`table.entryCount`.
- Produces: `private fun movieTimescale(moov: BoxNode?): Long?`; `private fun buildVideoStructureDetail(videoTrak: BoxNode?, movieTimescale: Long?): SummarySection?`, called from `buildVideoSummary`.

- [ ] **Step 1: Write the failing tests**

**Important:** this task also requires updating two pre-existing tests in `MediaSummaryBuilderTest.kt` whose fixtures (via the shared `buildVideoFixture` helper) have a video track with an `stsz` (`sample_count = "300"`, `stss` absent) — once `buildVideoStructureDetail` is wired in, that combination always produces a non-null "Video Detail" section (`Keyframe Interval: "All frames (no separate sync sample table)"` plus `B-Frames: "No"`, both unconditional given `totalSamples > 0`), so both fixtures gain a 5th/4th section. Find and update:

```kotlin
        assertEquals(MediaCategory.VIDEO, summary.category)
        assertEquals(4, summary.sections.size)
```
to:
```kotlin
        assertEquals(MediaCategory.VIDEO, summary.category)
        // 5, not 4: this fixture's video track (stsz sample_count=300, no stss) now also produces
        // a "Video Detail" section (Keyframe Interval + B-Frames, both unconditional once any
        // sample count is known).
        assertEquals(5, summary.sections.size)
```

and:
```kotlin
        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
```
to:
```kotlin
        // 4, not 3: see the comment on the "full video tree" test above -- same cause.
        assertEquals(4, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
```

Then add these new tests before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `Video Detail reports NAL Length Size and Parameter Sets from avcC`() {
        val avcC = BoxNode(
            type = "avcC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("length_size", "4", 0, 1),
                BoxField("num_sps", "1", 0, 1),
                BoxField("num_pps", "1", 0, 1),
            ),
        )
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, children = listOf(avcC))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd, videoStsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("4 bytes", videoDetail.fields.first { it.label == "NAL Length Size" }.value)
        assertEquals("1 SPS, 1 PPS", videoDetail.fields.first { it.label == "Parameter Sets" }.value)
    }

    @Test
    fun `Video Detail reports NAL Length Size and Parameter Sets from hvcC`() {
        val hvcC = BoxNode(
            type = "hvcC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("length_size", "4", 0, 1),
                BoxField("num_vps", "1", 0, 1),
                BoxField("num_sps", "1", 0, 1),
                BoxField("num_pps", "1", 0, 1),
            ),
        )
        val hvc1 = BoxNode(type = "hvc1", offset = 0, headerSize = 0, size = 0, children = listOf(hvcC))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(hvc1))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("4 bytes", videoDetail.fields.first { it.label == "NAL Length Size" }.value)
        assertEquals("1 VPS, 1 SPS, 1 PPS", videoDetail.fields.first { it.label == "Parameter Sets" }.value)
    }

    @Test
    fun `Video Detail describes an empty edit's offset using the movie timescale`() {
        val elst = BoxNode(
            type = "elst", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("segment_duration", "1000", 0, 4),
                BoxField("media_time", "-1", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
            ),
        )
        val edts = BoxNode(type = "edts", offset = 0, headerSize = 0, size = 0, children = listOf(elst))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(edts, videoMdia))
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        // segment_duration=1000 in a movie timescale of 1000 -> 1.000s offset
        assertEquals("1 edit (empty edit, 0:00:01.000 offset)", videoDetail.fields.first { it.label == "Edit List" }.value)
    }

    @Test
    fun `Video Detail reports a plain edit count when the first edit is not empty`() {
        val elst = BoxNode(
            type = "elst", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("segment_duration", "1000", 0, 4),
                BoxField("media_time", "0", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
                BoxField("segment_duration", "2000", 0, 4),
                BoxField("media_time", "1000", 0, 4),
                BoxField("media_rate", "1.0", 0, 4),
            ),
        )
        val edts = BoxNode(type = "edts", offset = 0, headerSize = 0, size = 0, children = listOf(elst))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(edts, videoMdia))
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4)))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(mvhd, videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("2 edits", videoDetail.fields.first { it.label == "Edit List" }.value)
    }

    @Test
    fun `Video Detail reports the actual Keyframe Interval when stss is present, and B-Frames Yes when ctts has entries`() {
        val stss = BoxNode(
            type = "stss", offset = 0, headerSize = 0, size = 0,
            table = TableData(columns = listOf("sample_number"), fieldWidths = listOf(4), entriesStart = 0, entryCount = 10),
        )
        val stsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val ctts = BoxNode(
            type = "ctts", offset = 0, headerSize = 0, size = 0,
            table = TableData(columns = listOf("sample_count", "sample_offset"), fieldWidths = listOf(4, 4), entriesStart = 0, entryCount = 5),
        )
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(stss, stsz, ctts))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("10 of 300 frames (every ~30 frames)", videoDetail.fields.first { it.label == "Keyframe Interval" }.value)
        assertEquals("Yes", videoDetail.fields.first { it.label == "B-Frames" }.value)
    }

    @Test
    fun `Video Detail reports No B-Frames and an All-frames Keyframe Interval when stss and ctts are both absent`() {
        val stsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(stsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video Detail" }
        assertEquals("All frames (no separate sync sample table)", videoDetail.fields.first { it.label == "Keyframe Interval" }.value)
        assertEquals("No", videoDetail.fields.first { it.label == "B-Frames" }.value)
    }

    @Test
    fun `a non-video media type (JPEG) has no Video Detail section`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2)),
        )

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "Video Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the two pre-existing tests fail on their old count assertions (4/3, before your edit above) until you've made that edit; all 7 new tests throw `NoSuchElementException` (no "Video Detail" section exists yet). The final "non-video" test passes already.

- [ ] **Step 3: Add `movieTimescale` and `buildVideoStructureDetail`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add right after `buildAudioDetail`'s closing `}` (currently line 776, right before the `MP3_TEXT_FRAME_LABELS` constant):

```kotlin
private fun movieTimescale(moov: BoxNode?): Long? =
    moov?.children?.find { it.type == "mvhd" }?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()

private fun buildVideoStructureDetail(videoTrak: BoxNode?, movieTimescale: Long?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val avcC = findFirst(videoTrak) { it.type == "avcC" }
    val hvcC = findFirst(videoTrak) { it.type == "hvcC" }
    when {
        avcC != null -> {
            avcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numSps = avcC.fields.find { it.name == "num_sps" }?.value
            val numPps = avcC.fields.find { it.name == "num_pps" }?.value
            if (numSps != null && numPps != null) fields.add(SummaryField("Parameter Sets", "$numSps SPS, $numPps PPS"))
        }
        hvcC != null -> {
            hvcC.fields.find { it.name == "length_size" }?.let { fields.add(SummaryField("NAL Length Size", "${it.value} bytes")) }
            val numVps = hvcC.fields.find { it.name == "num_vps" }?.value
            val numSps = hvcC.fields.find { it.name == "num_sps" }?.value
            val numPps = hvcC.fields.find { it.name == "num_pps" }?.value
            if (numVps != null && numSps != null && numPps != null) {
                fields.add(SummaryField("Parameter Sets", "$numVps VPS, $numSps SPS, $numPps PPS"))
            }
        }
    }

    val elst = findFirst(videoTrak) { it.type == "elst" }
    if (elst != null && elst.fields.isNotEmpty()) {
        val editCount = elst.fields.count { it.name == "segment_duration" }
        val firstMediaTime = elst.fields.find { it.name == "media_time" }?.value
        val firstSegmentDuration = elst.fields.find { it.name == "segment_duration" }?.value?.toDoubleOrNull()
        val label = if (firstMediaTime == "-1" && firstSegmentDuration != null && movieTimescale != null && movieTimescale > 0) {
            val offsetSeconds = firstSegmentDuration / movieTimescale
            "${pluralize(editCount.toLong(), "edit", "edits")} (empty edit, ${formatDuration(offsetSeconds)} offset)"
        } else {
            pluralize(editCount.toLong(), "edit", "edits")
        }
        fields.add(SummaryField("Edit List", label))
    }

    val stss = findFirst(videoTrak) { it.type == "stss" }
    val stsz = findFirst(videoTrak) { it.type == "stsz" }
    val totalSamples = stsz?.fields?.find { it.name == "sample_count" }?.value?.toLongOrNull() ?: stsz?.table?.entryCount
    if (totalSamples != null && totalSamples > 0) {
        if (stss == null) {
            fields.add(SummaryField("Keyframe Interval", "All frames (no separate sync sample table)"))
        } else {
            val keyframeCount = stss.table?.entryCount ?: 0
            if (keyframeCount > 0) {
                val avgInterval = totalSamples.toDouble() / keyframeCount
                fields.add(SummaryField("Keyframe Interval", "$keyframeCount of $totalSamples frames (every ~${"%.0f".format(avgInterval)} frames)"))
            }
        }
    }

    val ctts = findFirst(videoTrak) { it.type == "ctts" }
    fields.add(SummaryField("B-Frames", if (ctts != null && (ctts.table?.entryCount ?: 0) > 0) "Yes" else "No"))

    return if (fields.isNotEmpty()) SummarySection("Video Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildVideoSummary`**

Find (currently lines 681-694):
```kotlin
private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }

    return sections
}
```

Replace with:
```kotlin
private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov, videoTrak, audioTrak))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildVideoStructureDetail(videoTrak, movieTimescale(moov))?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }

    return sections
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 7 new tests plus all pre-existing cases (including the 2 you updated in Step 1).

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add Video Detail section: NAL/parameter sets, edit list, keyframe interval, B-frames"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 7: Manual verification

**Files:** none (no code changes — confirms Tasks 1-6 render correctly against real files, and catches any real-file surprise the way JPEG's Task 3 caught the two-concatenated-streams bug)

**Interfaces:** none

- [ ] **Step 1: Run the full suite one more time as a clean baseline**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test
```
Expected: full suite passes, 0 failures.

- [ ] **Step 2: Verify against real files**

Prefer a temporary scratch test (written, run, output captured, then deleted -- never committed) over GUI screenshots, matching the verification approach already used for the JPEG and image-formats sub-projects:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test

class ScratchVideoDetailVerifyTest {
    @Test
    fun `scratch print video Overview sections for real files`() {
        val files = listOf(
            "/path/to/a/real.mp4",  // H.264, if available
            "/path/to/a/real.mov",  // HEVC, if available
            "/path/to/a/real.webm",
        )
        for (path in files) {
            val file = File(path)
            if (!file.exists()) { println("SKIP (not found): $path"); continue }
            println("=== $path ===")
            val root = parseFile(file)
            val summary = buildMediaSummary(root, file)
            summary.sections.forEach { section ->
                println(section.title)
                section.fields.forEach { println("  ${it.label}: ${it.value}") }
            }
        }
    }
}
```

Run with:
```
./gradlew :app:test --tests "com.multiviewer.parser.ScratchVideoDetailVerifyTest" -q --info 2>&1 | grep -A 30 "=== "
```

Delete the scratch test file after verifying (never commit it). For each file, confirm:
- "Video Detail" (MP4-family only) shows plausible NAL Length Size/Parameter Sets, and `B-Frames` is always present (`Yes` or `No`, never missing).
- If the real file has more than one video track, confirm the shown structure matches the *first* video track specifically (per the spec's noted risk area).
- General shows Creation Time (most real-world camera/phone-recorded files have one) and, for WebM, a correctly-formatted Creation Date (not a raw nanosecond integer -- this is the Task 1 fix, worth double-checking against a real WebM file specifically).
- Open the Motion Photo Overview card (if a Motion Photo sample is available) and confirm Video Track Duration/Audio Track Duration render there too, per the explicit request that motivated adding them.

- [ ] **Step 3: Report result**

If all available real-file checks pass and the full suite is green, this plan is complete. If anything looks wrong, root-cause it (same discipline as the JPEG sub-project's Task 3 bug), fix, add a regression test, and re-verify before considering the plan done.
