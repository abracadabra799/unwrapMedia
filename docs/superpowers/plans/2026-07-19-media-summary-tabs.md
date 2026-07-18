# Media Summary / Structure Analyser 2-Tab UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every opened file a 2-tab sub-view — a curated **Media Summary** and the existing tree/field/hex explorer relabeled as **Structure Analyser** — where the engine picks an image-shaped or video-shaped summary layout by inspecting the file's own parsed structure.

**Architecture:** A new pure-Kotlin `MediaSummaryBuilder` walks the already-parsed `BoxNode` tree (no new byte-level parsing) to classify the file as `IMAGE` or `VIDEO` and extract a small curated `MediaSummary` data structure. A new `MediaSummaryView` composable renders it. `AppState`/`Main.kt` wire a second, per-file `TabRow` that switches between `MediaSummaryView` and the existing (unchanged) tree/field/hex layout.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop, kotlin.test.

## Global Constraints

- No new byte-level parsing — every Media Summary value comes from `BoxField`/`summary` values existing decoders already produce.
- GPS values are shown exactly as already stored (raw DMS rational strings) — no coordinate conversion.
- Only a single whole-file average bitrate is computed (`file.length() * 8 / durationSeconds`) — no per-track bitrate.
- Sections with nothing to show are omitted entirely from `MediaSummary.sections` — never rendered empty.
- Category detection only inspects root's **direct** children and (for ISOBMFF) `moov`'s **direct** `trak` children — never recurses into nested embedded content (e.g. a `sefd`/`mpvd` field's own embedded MP4 must not cause a JPEG/HEIC to be misclassified as `VIDEO`).
- `Structure Analyser` must render byte-for-byte identically to the app's current (pre-this-feature) tree/field/hex layout — this feature only adds a new tab alongside it, never modifies its behavior.

---

### Task 1: `MediaSummary` data model and category detection

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `BoxNode`/`BoxField` (`BoxNode.kt`, existing).
- Produces: `enum class MediaCategory { IMAGE, VIDEO }`, `data class SummaryField(val label: String, val value: String)`, `data class SummarySection(val title: String, val fields: List<SummaryField>)`, `data class MediaSummary(val category: MediaCategory, val sections: List<SummarySection>)` (all in `MediaSummary.kt`) — used by every later task. `fun buildMediaSummary(root: BoxNode, file: File): MediaSummary` (in `MediaSummaryBuilder.kt`) — the one public entry point Task 4 wires into the UI. In this task, `buildMediaSummary` delegates to `buildImageSummary`/`buildVideoSummary`, which are implemented here as stubs returning `emptyList()` — Tasks 2 and 3 replace those stub bodies with real extraction logic; this task's own tests only assert on `category`, not `sections` content.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSummaryBuilderTest {
    private fun tempFile(bytes: Int = 0): File {
        val tmp = File.createTempFile("media-summary-test", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(bytes))
        return tmp
    }

    @Test
    fun `a JPEG-shaped root (has an SOI child) is classified as IMAGE`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 4,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
                BoxNode(type = "EOI", offset = 2, headerSize = 2, size = 2),
            ),
        )
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with a moov track whose handler is video is classified as VIDEO`() {
        val hdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val mdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(hdlr))
        val trak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(trak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))
        assertEquals(MediaCategory.VIDEO, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with no moov (HEIC-shaped) is classified as IMAGE`() {
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `a nested moov reachable only through non-root paths does not affect classification`() {
        val nestedHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val nestedMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(nestedHdlr))
        val nestedTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMdia))
        val nestedMoov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(nestedTrak))
        val mpvd = BoxNode(type = "mpvd", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMoov))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta, mpvd))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: FAIL — compilation error, `MediaCategory`/`buildMediaSummary`/`MediaSummary` don't exist yet.

- [ ] **Step 3: Create `MediaSummary.kt`**

```kotlin
package com.multiviewer.parser

enum class MediaCategory { IMAGE, VIDEO }

data class SummaryField(
    val label: String,
    val value: String,
)

data class SummarySection(
    val title: String,
    val fields: List<SummaryField>,
)

data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
)
```

- [ ] **Step 4: Create `MediaSummaryBuilder.kt`**

```kotlin
package com.multiviewer.parser

import java.io.File

fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file)
    }
    return MediaSummary(category, sections)
}

private fun detectCategory(root: BoxNode): MediaCategory {
    if (root.children.any { it.type == "SOI" }) return MediaCategory.IMAGE
    val moov = root.children.find { it.type == "moov" } ?: return MediaCategory.IMAGE
    val hasVideoOrAudioTrack = moov.children.filter { it.type == "trak" }.any { trak ->
        val handlerType = findFirst(trak) { it.type == "hdlr" }?.fields?.find { it.name == "handler_type" }?.value
        handlerType == "vide" || handlerType == "soun"
    }
    return if (hasVideoOrAudioTrack) MediaCategory.VIDEO else MediaCategory.IMAGE
}

private fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
    if (predicate(node)) return node
    for (child in node.children) {
        val found = findFirst(child, predicate)
        if (found != null) return found
    }
    return null
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}

private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}

private fun buildVideoSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}
```

`findFirst` and `formatFileSize` are added now (private, file-scoped per this codebase's existing convention for `JpegWalker.kt`'s helpers) because both `buildImageSummary` and `buildVideoSummary` need them in Tasks 2 and 3 — defining them once here avoids duplicating them in both later tasks.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: PASS — all 4 tests pass.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — no regressions in any existing test.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: add MediaSummary data model and IMAGE/VIDEO category detection"
```

---

### Task 2: Image summary extraction

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode?`, `formatFileSize(bytes: Long): String` (both from Task 1, `MediaSummaryBuilder.kt`, unchanged).
- Produces: nothing new consumed elsewhere — `buildImageSummary`'s signature (`(root: BoxNode, file: File): List<SummarySection>`) doesn't change, only its body.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (inside the existing `MediaSummaryBuilderTest` class):

```kotlin
    @Test
    fun `a full image tree produces all four image sections with correct values`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "3", 0, 1),
            ),
        )
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("GPSLatitudeRef", "N", 0, 1),
                BoxField("GPSLatitude", "37/1, 34/1, 0/1", 0, 24),
                BoxField("GPSLongitudeRef", "E", 0, 1),
                BoxField("GPSLongitude", "127/1, 0/1, 0/1", 0, 24),
            ),
        )
        val exif = BoxNode(
            type = "Exif", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ExposureTime", "1/100", 0, 8),
                BoxField("FNumber", "28/10", 0, 8),
                BoxField("ISOSpeedRatings", "200", 0, 2),
                BoxField("FocalLength", "50/1", 0, 8),
                BoxField("DateTimeOriginal", "2026:07:19 10:00:00", 0, 20),
            ),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("Make", "TestCam", 0, 8),
                BoxField("Model", "X100", 0, 5),
                BoxField("DateTime", "2026:07:19 09:00:00", 0, 20),
            ),
            children = listOf(exif, gps),
        )
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 4, size = 0, children = listOf(ifd0))
        val sefdField = BoxNode(type = "Image_UTC_Data", offset = 0, headerSize = 0, size = 0, summary = "1784372666391")
        val sefd = BoxNode(type = "sefd", offset = 0, headerSize = 0, size = 0, children = listOf(sefdField))
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), app1, sof0, sefd),
        )
        val tmp = File.createTempFile("media-summary-image-test", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_500_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.IMAGE, summary.category)
        assertEquals(4, summary.sections.size)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("1.5 MB", basicInfo.fields.first { it.label == "File Size" }.value)
        assertEquals("JPEG", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("Color (YCbCr)", basicInfo.fields.first { it.label == "Color Space" }.value)
        assertEquals("2026:07:19 10:00:00", basicInfo.fields.first { it.label == "Capture Date" }.value)

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("TestCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("X100", cameraInfo.fields.first { it.label == "Model" }.value)
        assertEquals("1/100", cameraInfo.fields.first { it.label == "Exposure Time" }.value)
        assertEquals("28/10", cameraInfo.fields.first { it.label == "F-Number" }.value)
        assertEquals("200", cameraInfo.fields.first { it.label == "ISO" }.value)
        assertEquals("50/1", cameraInfo.fields.first { it.label == "Focal Length" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
        assertEquals("37/1, 34/1, 0/1", gpsSection.fields.first { it.label == "Latitude" }.value)

        val samsungSection = summary.sections.first { it.title == "Samsung Metadata" }
        assertEquals("1784372666391", samsungSection.fields.first { it.label == "Image_UTC_Data" }.value)
    }

    @Test
    fun `a minimal image tree with no Exif produces only a Basic Info section`() {
        val sof0 = BoxNode(
            type = "SOF0", offset = 0, headerSize = 4, size = 19,
            fields = listOf(
                BoxField("precision", "8", 0, 1),
                BoxField("height", "480", 0, 2),
                BoxField("width", "640", 0, 2),
                BoxField("num_components", "1", 0, 1),
            ),
        )
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 0,
            children = listOf(BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2), sof0),
        )
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(1, summary.sections.size)
        val basicInfo = summary.sections[0]
        assertEquals("Basic Info", basicInfo.title)
        assertEquals("Grayscale", basicInfo.fields.first { it.label == "Color Space" }.value)
    }
```

Also add `import java.io.File` to the top of the test file if not already present (it already is, from Task 1's `tempFile` helper).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: FAIL — `buildImageSummary` currently returns `emptyList()`, so `summary.sections.size` is `0`, not `4` or `1`, and every `.first { ... }` lookup throws `NoSuchElementException`.

- [ ] **Step 3: Implement `buildImageSummary` in `MediaSummaryBuilder.kt`**

Replace the stub:

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}
```

with:

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageBasicInfo(root, file))

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    if (ifd0 != null) {
        val cameraFields = mutableListOf<SummaryField>()
        ifd0.fields.find { it.name == "Make" }?.let { cameraFields.add(SummaryField("Make", it.value)) }
        ifd0.fields.find { it.name == "Model" }?.let { cameraFields.add(SummaryField("Model", it.value)) }
        val exif = ifd0.children.find { it.type == "Exif" }
        exif?.fields?.find { it.name == "ExposureTime" }?.let { cameraFields.add(SummaryField("Exposure Time", it.value)) }
        exif?.fields?.find { it.name == "FNumber" }?.let { cameraFields.add(SummaryField("F-Number", it.value)) }
        exif?.fields?.find { it.name == "ISOSpeedRatings" }?.let { cameraFields.add(SummaryField("ISO", it.value)) }
        exif?.fields?.find { it.name == "FocalLength" }?.let { cameraFields.add(SummaryField("Focal Length", it.value)) }
        if (cameraFields.isNotEmpty()) {
            sections.add(SummarySection("Camera Info", cameraFields))
        }

        val gps = ifd0.children.find { it.type == "GPS" }
        if (gps != null) {
            val gpsFields = mutableListOf<SummaryField>()
            gps.fields.find { it.name == "GPSLatitudeRef" }?.let { gpsFields.add(SummaryField("Latitude Ref", it.value)) }
            gps.fields.find { it.name == "GPSLatitude" }?.let { gpsFields.add(SummaryField("Latitude", it.value)) }
            gps.fields.find { it.name == "GPSLongitudeRef" }?.let { gpsFields.add(SummaryField("Longitude Ref", it.value)) }
            gps.fields.find { it.name == "GPSLongitude" }?.let { gpsFields.add(SummaryField("Longitude", it.value)) }
            if (gpsFields.isNotEmpty()) {
                sections.add(SummarySection("GPS Location", gpsFields))
            }
        }
    }

    val sefd = findFirst(root) { it.type == "sefd" }
    if (sefd != null && sefd.children.isNotEmpty()) {
        val sefdFields = sefd.children.map { field ->
            SummaryField(field.type, field.summary ?: field.fields.firstOrNull()?.value ?: "")
        }
        sections.add(SummarySection("Samsung Metadata", sefdFields))
    }

    return sections
}

private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val sofOrIspe = findFirst(root) { it.type.startsWith("SOF") || it.type == "ispe" }

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))

    val colr = findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    return SummarySection("Basic Info", fields)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: PASS — all 6 tests pass (4 from Task 1, 2 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: extract image Media Summary (Basic Info, Camera Info, GPS, Samsung Metadata)"
```

---

### Task 3: Video summary extraction

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findFirst`, `formatFileSize` (Task 1, unchanged).
- Produces: nothing consumed by other tasks.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`:

```kotlin
    private fun buildVideoFixture(includeAudioTrack: Boolean): BoxNode {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val videoMdhd = BoxNode(type = "mdhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "30000", 0, 4), BoxField("duration", "300000", 0, 4)))
        val avc1 = BoxNode(type = "avc1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "1920.0", 0, 2), BoxField("height", "1080.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(avc1))
        val videoStsz = BoxNode(type = "stsz", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("sample_size", "0", 0, 4), BoxField("sample_count", "300", 0, 4)))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd, videoStsz))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMdhd, videoMinf))
        val videoTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "1", 0, 4), BoxField("duration", "300000", 0, 4), BoxField("width", "1920.0", 0, 4), BoxField("height", "1080.0", 0, 4)))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoTkhd, videoMdia))

        val moovChildren = mutableListOf<BoxNode>()
        val mvhd = BoxNode(type = "mvhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("timescale", "1000", 0, 4), BoxField("duration", "10000", 0, 4)))
        moovChildren.add(mvhd)
        moovChildren.add(videoTrak)

        if (includeAudioTrack) {
            val audioHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "soun", 0, 4)))
            val mp4a = BoxNode(type = "mp4a", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("channelcount", "2", 0, 2), BoxField("samplerate", "44100.0", 0, 4)))
            val audioStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(mp4a))
            val audioStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(audioStsd))
            val audioMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(audioStbl))
            val audioMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(audioHdlr, audioMinf))
            val audioTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "2", 0, 4)))
            val audioTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(audioTkhd, audioMdia))
            moovChildren.add(audioTrak)
        }

        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = moovChildren)
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("major_brand", "isom", 0, 4)))
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, moov))
    }

    @Test
    fun `a full video tree produces all four video sections with correct values`() {
        val root = buildVideoFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("media-summary-video-test", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
        assertEquals(4, summary.sections.size)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("0:00:10", basicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("isom", basicInfo.fields.first { it.label == "Container Brand" }.value)
        assertEquals("1.0 Mbps", basicInfo.fields.first { it.label == "Average Bitrate" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video Track Detail" }
        assertEquals("avc1", videoDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("30.00 fps", videoDetail.fields.first { it.label == "Frame Rate" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio Track Detail" }
        assertEquals("mp4a", audioDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sample Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channels" }.value)
    }

    @Test
    fun `a video-only tree (no audio track) omits Audio Track Detail`() {
        val root = buildVideoFixture(includeAudioTrack = false)
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio Track Detail" })
        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("0", trackList.fields.first { it.label == "Audio Tracks" }.value)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: FAIL — `buildVideoSummary` currently returns `emptyList()`.

- [ ] **Step 3: Implement `buildVideoSummary` in `MediaSummaryBuilder.kt`**

Replace the stub:

```kotlin
private fun buildVideoSummary(root: BoxNode, file: File): List<SummarySection> {
    return emptyList()
}
```

with:

```kotlin
private fun buildVideoSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoBasicInfo(root, file, moov, videoTrak))
    sections.add(buildTrackList(traks))
    buildVideoTrackDetail(videoTrak)?.let { sections.add(it) }
    buildAudioTrackDetail(audioTrak)?.let { sections.add(it) }

    return sections
}

private fun trakHandlerType(trak: BoxNode): String? {
    val hdlr = findFirst(trak) { it.type == "hdlr" }
    return hdlr?.fields?.find { it.name == "handler_type" }?.value
}

private fun buildVideoBasicInfo(root: BoxNode, file: File, moov: BoxNode?, videoTrak: BoxNode?): SummarySection {
    val fields = mutableListOf<SummaryField>()

    val mvhd = moov?.children?.find { it.type == "mvhd" }
    val timescale = mvhd?.fields?.find { it.name == "timescale" }?.value?.toLongOrNull()
    val duration = mvhd?.fields?.find { it.name == "duration" }?.value?.toLongOrNull()
    val durationSeconds = if (timescale != null && timescale > 0 && duration != null) duration.toDouble() / timescale else null
    durationSeconds?.let { fields.add(SummaryField("Duration", formatDuration(it))) }

    val tkhd = videoTrak?.children?.find { it.type == "tkhd" }
    val width = tkhd?.fields?.find { it.name == "width" }?.value?.toDoubleOrNull()
    val height = tkhd?.fields?.find { it.name == "height" }?.value?.toDoubleOrNull()
    if (width != null && height != null) {
        fields.add(SummaryField("Resolution", "${width.toInt()}x${height.toInt()}"))
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Container Brand", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (file.length() * 8) / durationSeconds
        fields.add(SummaryField("Average Bitrate", formatBitrate(bitrate)))
    }

    return SummarySection("Basic Info", fields)
}

private fun buildTrackList(traks: List<BoxNode>): SummarySection {
    val videoCount = traks.count { trakHandlerType(it) == "vide" }
    val audioCount = traks.count { trakHandlerType(it) == "soun" }
    val otherCount = traks.size - videoCount - audioCount
    val fields = mutableListOf(
        SummaryField("Video Tracks", videoCount.toString()),
        SummaryField("Audio Tracks", audioCount.toString()),
    )
    if (otherCount > 0) {
        fields.add(SummaryField("Other Tracks", otherCount.toString()))
    }
    return SummarySection("Track List", fields)
}

private fun buildVideoTrackDetail(videoTrak: BoxNode?): SummarySection? {
    if (videoTrak == null) return null
    val fields = mutableListOf<SummaryField>()

    val stsd = findFirst(videoTrak) { it.type == "stsd" }
    stsd?.children?.firstOrNull()?.type?.let { fields.add(SummaryField("Codec", it)) }

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

    return if (fields.isNotEmpty()) SummarySection("Video Track Detail", fields) else null
}

private fun buildAudioTrackDetail(audioTrak: BoxNode?): SummarySection? {
    if (audioTrak == null) return null
    val stsd = findFirst(audioTrak) { it.type == "stsd" }
    val audioEntry = stsd?.children?.firstOrNull()
    val fields = mutableListOf<SummaryField>()
    audioEntry?.type?.let { fields.add(SummaryField("Codec", it)) }
    audioEntry?.fields?.find { it.name == "samplerate" }?.let { fields.add(SummaryField("Sample Rate", "${it.value} Hz")) }
    audioEntry?.fields?.find { it.name == "channelcount" }?.let { fields.add(SummaryField("Channels", it.value)) }
    return if (fields.isNotEmpty()) SummarySection("Audio Track Detail", fields) else null
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}

private fun formatBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
    bitsPerSecond >= 1_000 -> "%.1f Kbps".format(bitsPerSecond / 1_000)
    else -> "%.0f bps".format(bitsPerSecond)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" -i`
Expected: PASS — all 8 tests pass (6 from Tasks 1-2, 2 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: extract video Media Summary (Basic Info, Track List, Video/Audio Track Detail)"
```

---

### Task 4: `MediaSummaryView` and 2-tab UI integration

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `MediaSummary`/`SummarySection`/`SummaryField` (Task 1), `buildMediaSummary(root: BoxNode, file: File): MediaSummary` (Task 1, fully functional as of Task 3).
- Produces: nothing consumed elsewhere — this is the final task.

This task is pure UI wiring with no new pure-logic functions to unit test — this codebase has no Compose UI test infrastructure (an established, deliberate choice noted in this project's prior UI-touching features). Verification here is: the project compiles, the full test suite still passes (proving Tasks 1-3's logic is unaffected), and a manual run of the app (Step 5 below).

- [ ] **Step 1: Make `MetadataRow` reusable**

`FieldPanel.kt`'s `MetadataRow` composable is currently `private`, which in Kotlin means file-private — not visible from a new file in the same package. `MediaSummaryView.kt` needs it. In `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`, change:

```kotlin
@Composable
private fun MetadataRow(label: String, value: String) {
```

to:

```kotlin
@Composable
fun MetadataRow(label: String, value: String) {
```

(Removing `private` makes it visible to any file in the module — Kotlin's default visibility — with no other change to its body.)

- [ ] **Step 2: Create `MediaSummaryView.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.MediaSummary

@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        items(summary.sections) { section ->
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Text(section.title, modifier = Modifier.padding(bottom = 4.dp))
                section.fields.forEach { field ->
                    MetadataRow(field.label, field.value)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Wire `MediaSummary` into `TabState`/`AppState`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, add the import:

```kotlin
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.buildMediaSummary
```

Change `TabState` from:

```kotlin
class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
}
```

to:

```kotlin
class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)
}
```

Change `AppState.openFile` from:

```kotlin
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            tab.root = parseFile(file)
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
```

to:

```kotlin
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            val root = parseFile(file)
            tab.root = root
            tab.mediaSummary = buildMediaSummary(root, file)
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
```

- [ ] **Step 4: Add the 2-tab selector in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, replace the `when { ... }` block (everything from `when {` through its closing `}`, currently the last statement inside the `if (appState.tabs.isNotEmpty()) { ... }` block) — from:

```kotlin
                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> {
                            var columnHeightPx by remember { mutableStateOf(0) }
                            var rowWidthPx by remember { mutableStateOf(0) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { columnHeightPx = it.size.height },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(currentTab.horizontalSplit)
                                        .fillMaxWidth()
                                        .onGloballyPositioned { rowWidthPx = it.size.width },
                                ) {
                                    Column(modifier = Modifier.weight(currentTab.verticalSplit).fillMaxWidth()) {
                                        BoxTreeView(
                                            root = currentTab.root!!,
                                            selected = currentTab.selected,
                                            onSelect = { currentTab.selected = it },
                                        )
                                    }
                                    DraggableDivider(
                                        orientation = Orientation.Vertical,
                                        containerSizePx = rowWidthPx,
                                        tabKey = currentTab,
                                        getSplit = { currentTab.verticalSplit },
                                        setSplit = { currentTab.verticalSplit = it },
                                    )
                                    Column(modifier = Modifier.weight(1f - currentTab.verticalSplit).fillMaxWidth()) {
                                        val selectedNode = currentTab.selected
                                        if (selectedNode?.table != null) {
                                            com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                        } else {
                                            com.multiviewer.ui.FieldPanel(selectedNode)
                                        }
                                    }
                                }
                                DraggableDivider(
                                    orientation = Orientation.Horizontal,
                                    containerSizePx = columnHeightPx,
                                    tabKey = currentTab,
                                    getSplit = { currentTab.horizontalSplit },
                                    setSplit = { currentTab.horizontalSplit = it },
                                )
                                Column(modifier = Modifier.weight(1f - currentTab.horizontalSplit).fillMaxWidth()) {
                                    HexView(
                                        file = currentTab.file,
                                        highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                        listState = hexListState,
                                    )
                                }
                            }
                        }
                    }
```

to:

```kotlin
                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> {
                            TabRow(selectedTabIndex = currentTab.summaryTabIndex) {
                                Tab(
                                    selected = currentTab.summaryTabIndex == 0,
                                    onClick = { currentTab.summaryTabIndex = 0 },
                                    text = { Text("Media Summary") },
                                )
                                Tab(
                                    selected = currentTab.summaryTabIndex == 1,
                                    onClick = { currentTab.summaryTabIndex = 1 },
                                    text = { Text("Structure Analyser") },
                                )
                            }
                            if (currentTab.summaryTabIndex == 0) {
                                com.multiviewer.ui.MediaSummaryView(currentTab.mediaSummary)
                            } else {
                                var columnHeightPx by remember { mutableStateOf(0) }
                                var rowWidthPx by remember { mutableStateOf(0) }

                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { columnHeightPx = it.size.height },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(currentTab.horizontalSplit)
                                            .fillMaxWidth()
                                            .onGloballyPositioned { rowWidthPx = it.size.width },
                                    ) {
                                        Column(modifier = Modifier.weight(currentTab.verticalSplit).fillMaxWidth()) {
                                            BoxTreeView(
                                                root = currentTab.root!!,
                                                selected = currentTab.selected,
                                                onSelect = { currentTab.selected = it },
                                            )
                                        }
                                        DraggableDivider(
                                            orientation = Orientation.Vertical,
                                            containerSizePx = rowWidthPx,
                                            tabKey = currentTab,
                                            getSplit = { currentTab.verticalSplit },
                                            setSplit = { currentTab.verticalSplit = it },
                                        )
                                        Column(modifier = Modifier.weight(1f - currentTab.verticalSplit).fillMaxWidth()) {
                                            val selectedNode = currentTab.selected
                                            if (selectedNode?.table != null) {
                                                com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                            } else {
                                                com.multiviewer.ui.FieldPanel(selectedNode)
                                            }
                                        }
                                    }
                                    DraggableDivider(
                                        orientation = Orientation.Horizontal,
                                        containerSizePx = columnHeightPx,
                                        tabKey = currentTab,
                                        getSplit = { currentTab.horizontalSplit },
                                        setSplit = { currentTab.horizontalSplit = it },
                                    )
                                    Column(modifier = Modifier.weight(1f - currentTab.horizontalSplit).fillMaxWidth()) {
                                        HexView(
                                            file = currentTab.file,
                                            highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                            listState = hexListState,
                                        )
                                    }
                                }
                            }
                        }
                    }
```

The only change is: the previous single `Column(...)` tree/hex layout is now nested one level deeper inside `if (currentTab.summaryTabIndex == 0) { MediaSummaryView(...) } else { <exact same Column as before> }`, preceded by the new sub-`TabRow`. No line inside that `Column` block changed.

- [ ] **Step 5: Verify it compiles and the full suite passes**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileKotlin -i
./gradlew test -i
```
Expected: both `BUILD SUCCESSFUL`, full test suite passes with no regressions (Tasks 1-3's tests, plus every pre-existing test in the project).

- [ ] **Step 6: Manual verification**

Run the app (`./gradlew :app:run`), open a real sample JPEG or HEIC and a real sample MP4/MOV (any files already used for this project's prior manual testing). Confirm:
- Each opened file shows two sub-tabs: "Media Summary" (selected by default) and "Structure Analyser".
- For the image file, Media Summary shows Basic Info at minimum, plus Camera Info/GPS/Samsung Metadata if that file has Exif/GPS/`sefd` data.
- For the video file, Media Summary shows Basic Info, Track List, and Video/Audio Track Detail as applicable.
- Switching to "Structure Analyser" shows the exact same tree/field/hex explorer that existed before this feature — clicking tree nodes still updates the field panel and hex view highlight as before.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: add Media Summary / Structure Analyser 2-tab UI per file"
```
