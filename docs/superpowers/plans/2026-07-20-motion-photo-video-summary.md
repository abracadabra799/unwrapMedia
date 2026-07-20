# Motion Photo Video Summary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the open file is a Motion Photo, show two visually distinct boxed regions in Media Summary — one for the image, one for the embedded video — each with its own curated summary sections.

**Architecture:** `MediaSummary` gains an optional `motionPhotoVideoSections` field, populated by independently re-parsing the embedded video's byte range and feeding it through the existing `buildVideoSummary` (refactored to take a plain byte count instead of a `File`, since the video's size differs from the containing photo's). `MediaSummaryView` renders two bordered boxes when that field is present, otherwise renders exactly as it does today.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop 1.7.3, `kotlin.test`.

## Global Constraints

- Non-motion-photo files must render identically to today — no boxes, no layout change, byte-for-byte same output from `MediaSummaryView` for the flat-list case.
- The video box's "File Size" (and any bitrate math) must reflect the *embedded video's own byte length*, never the containing photo file's size.
- A failure anywhere in building the video summary (malformed embedded video, parse error) must degrade to `motionPhotoVideoSections = null` — never break or throw out of the image's own summary.
- Approved layout: stacked (image box, then video box below), not side-by-side.
- Box headers: "📷 이미지" for the image box, "🎬 동영상 (모션포토)" for the video box.

---

### Task 1: `motionPhotoVideoSections` data + build logic

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findEmbeddedVideo(root: BoxNode): EmbeddedVideo?` (existing, `com.multiviewer.parser.MotionPhotoExtractor`), `parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode>` (existing, `com.multiviewer.parser.BoxWalker`), `ByteReader.open(file: File): ByteReader` (existing).
- Produces: `MediaSummary.motionPhotoVideoSections: List<SummarySection>?` — Task 2's UI reads this field directly; no other new public symbols.

- [ ] **Step 1: Write the failing tests**

Add these two tests to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `a motion photo image populates motionPhotoVideoSections from the embedded video, using the video's own size`() {
        val bytes = byteArrayOf(
            // outer ftyp (16 bytes) — the containing photo's own top-level ftyp
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            // mpvd header, size=60 — the embedded video wrapper
            0x00, 0x00, 0x00, 0x3C, 'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
            // nested ftyp (16 bytes) — the embedded video's own ftyp
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            // moov, size=36
            0x00, 0x00, 0x00, 0x24, 'm'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(),
            // mvhd, size=28: version+flags, creation_time, modification_time, timescale=1000, duration=2000
            0x00, 0x00, 0x00, 0x1C, 'm'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte(),
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0x00, 0x00, 0x07, 0xD0.toByte(),
        )
        val file = File.createTempFile("motion-photo-video-summary", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val root = parseFile(file)
        val summary = buildMediaSummary(root, file)

        assertEquals(MediaCategory.IMAGE, summary.category)
        val imageBasicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("76 bytes", imageBasicInfo.fields.first { it.label == "File Size" }.value)

        val videoSections = summary.motionPhotoVideoSections
        assertEquals(true, videoSections != null)
        val videoBasicInfo = videoSections!!.first { it.title == "Basic Info" }
        assertEquals("0:00:02", videoBasicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("52 bytes", videoBasicInfo.fields.first { it.label == "File Size" }.value)
    }

    @Test
    fun `an ordinary non-motion-photo image leaves motionPhotoVideoSections null`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("ordinary-image", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val root = parseFile(file)
        val summary = buildMediaSummary(root, file)

        assertEquals(null, summary.motionPhotoVideoSections)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: FAIL — `motionPhotoVideoSections` is an unresolved reference (doesn't exist on `MediaSummary` yet).

- [ ] **Step 3: Add the field to `MediaSummary`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt`, change:

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
)
```

to:

```kotlin
data class MediaSummary(
    val category: MediaCategory,
    val sections: List<SummarySection>,
    val motionPhotoVideoSections: List<SummarySection>? = null,
)
```

- [ ] **Step 4: Refactor `buildVideoSummary`/`buildVideoBasicInfo` to take a byte count instead of a `File`, and wire in the new video-summary builder**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`:

Replace:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file)
    }
    return MediaSummary(category, sections)
}
```

with:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary {
    val category = detectCategory(root)
    val sections = if (category == MediaCategory.IMAGE) {
        buildImageSummary(root, file)
    } else {
        buildVideoSummary(root, file.length())
    }
    val motionPhotoVideoSections = if (category == MediaCategory.IMAGE) {
        buildMotionPhotoVideoSummary(root, file)
    } else {
        null
    }
    return MediaSummary(category, sections, motionPhotoVideoSections)
}

private fun buildMotionPhotoVideoSummary(root: BoxNode, file: File): List<SummarySection>? {
    val video = findEmbeddedVideo(root) ?: return null
    return try {
        ByteReader.open(file).use { reader ->
            val videoBoxes = parseBoxes(reader, video.start, video.end)
            val videoRoot = BoxNode(
                type = "root", offset = video.start, headerSize = 0,
                size = video.end - video.start, children = videoBoxes,
            )
            buildVideoSummary(videoRoot, video.end - video.start)
        }
    } catch (e: Exception) {
        null
    }
}
```

Replace:

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
```

with:

```kotlin
private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoBasicInfo(root, fileSizeBytes, moov, videoTrak))
    sections.add(buildTrackList(traks))
    buildVideoTrackDetail(videoTrak)?.let { sections.add(it) }
    buildAudioTrackDetail(audioTrak)?.let { sections.add(it) }

    return sections
}
```

Replace:

```kotlin
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
```

with:

```kotlin
private fun buildVideoBasicInfo(root: BoxNode, fileSizeBytes: Long, moov: BoxNode?, videoTrak: BoxNode?): SummarySection {
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

    fields.add(SummaryField("File Size", formatFileSize(fileSizeBytes)))

    root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.let {
        fields.add(SummaryField("Container Brand", it.value))
    }

    if (durationSeconds != null && durationSeconds > 0) {
        val bitrate = (fileSizeBytes * 8) / durationSeconds
        fields.add(SummaryField("Average Bitrate", formatBitrate(bitrate)))
    }

    return SummarySection("Basic Info", fields)
}
```

No other function in this file changes. `findEmbeddedVideo`, `parseBoxes`, and `ByteReader` are all in the same package (`com.multiviewer.parser`) as `MediaSummaryBuilder.kt` — no new imports are needed.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: PASS (10 tests: 8 existing + 2 new)

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (151 existing + 2 new = 153)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummary.kt \
        app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: build a video summary for a motion photo's embedded video"
```

---

### Task 2: Stacked image/video boxes in `MediaSummaryView`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt`

**Interfaces:**
- Consumes: `MediaSummary.motionPhotoVideoSections: List<SummarySection>?` (from Task 1), `SummarySection`/`SummaryField` (existing, `com.multiviewer.parser`).
- Produces: no new public symbols — `MediaSummaryView`'s existing signature (`fun MediaSummaryView(summary: MediaSummary?)`) is unchanged; this task only changes what it renders.

- [ ] **Step 1: Replace the full contents of `MediaSummaryView.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.SummarySection

@Composable
fun MediaSummaryView(summary: MediaSummary?) {
    if (summary == null) return
    val videoSections = summary.motionPhotoVideoSections
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        if (videoSections != null) {
            item {
                SummaryBox("📷 이미지", summary.sections)
                Spacer(modifier = Modifier.height(12.dp))
                SummaryBox("🎬 동영상 (모션포토)", videoSections)
            }
        } else {
            items(summary.sections) { section ->
                SectionContent(section)
            }
        }
    }
}

@Composable
private fun SummaryBox(title: String, sections: List<SummarySection>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            title,
            modifier = Modifier.padding(bottom = 8.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        sections.forEach { section -> SectionContent(section) }
    }
}

@Composable
private fun SectionContent(section: SummarySection) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            section.title,
            modifier = Modifier.padding(bottom = 6.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        section.fields.forEach { field ->
            MetadataRow(field.label, field.value)
        }
    }
}
```

This extracts the existing per-section rendering (title + fields) into `SectionContent`, reused identically by both the flat (non-motion-photo) and boxed (motion-photo) rendering paths — the flat path's output is unchanged from before this task.

- [ ] **Step 2: Run the full suite to confirm nothing broke**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all 153 tests still pass (this file has no dedicated unit tests — Compose UI rendering isn't unit-tested in this codebase — but the build must compile and the rest of the suite must stay green).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MediaSummaryView.kt
git commit -m "feat: show stacked image/video boxes in Media Summary for motion photos"
```

- [ ] **Step 4: Manual verification note**

Launch the app (`./gradlew :app:run`) and open a real Motion Photo file (e.g. one of the Samsung JPEG/HEIC files already used to verify prior Motion Photo work, or a Google-format sample). Confirm: the Media Summary tab shows the "📷 이미지" box followed by the "🎬 동영상 (모션포토)" box, both bordered and clearly separated; the video box's Duration/Resolution/Codec values are plausible; opening an ordinary (non-motion-photo) file still shows the flat, unboxed list exactly as before.
