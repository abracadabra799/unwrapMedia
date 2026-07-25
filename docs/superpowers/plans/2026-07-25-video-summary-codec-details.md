# Video Media Summary Codec Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Opening a video file automatically enriches the existing "Video"/"Audio" media-summary sections with MediaInfo-level codec details (profile, level, bit rate, chroma subsampling, bit depth, color primaries/transfer/matrix, color range, frame-rate mode) sourced from `ffprobe`.

**Architecture:** A new `probeStreamDetails` function (mirrors `probeVideo`/`probeFrameTypes`) extracts per-stream codec fields via one fast `ffprobe -show_entries stream=...` call. A new `mergeStreamCodecDetails` function appends those fields onto the existing box-parser-built `"Video"`/`"Audio"` `SummarySection`s. `AppState.openFile()` wires both into its existing background-thread analysis for video tabs — no new UI code, since `SummaryBox` already renders whatever fields each section has.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, bundled `ffprobe` (via `FfmpegLocator`).

## Global Constraints

- Video only — no change to the image summary path.
- No change to `buildMediaSummary`'s existing box-parsing logic — new data is merged in afterward, as a separate step.
- No hand-rolled bitstream parser — `ffprobe` supplies profile/level/color/chroma/bit-depth data.
- Automatic, no button — the ffprobe call this task adds is length-independent (`-show_entries stream=...`, not `-show_frames`), so it runs on the same background thread `openFile()` already uses for every video, same as `probeVideo` already does elsewhere in this app.
- "Level" is shown as ffprobe's raw value, not converted to a human "L4.0"-style string — H.264 and HEVC use different level-number formulas, and guessing wrong would show incorrect data.
- Spec: `docs/superpowers/specs/2026-07-25-video-summary-codec-details-design.md`.

---

### Task 1: `probeStreamDetails` — ffprobe-backed codec field extraction

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt`

**Interfaces:**
- Consumes: `FfmpegLocator.ffprobePath()` (existing, `com.multiviewer.ui` package). `SummaryField(label: String, value: String)` (existing, `com.multiviewer.parser` package).
- Produces: `data class StreamCodecDetails(val videoFields: List<SummaryField>, val audioFields: List<SummaryField>)` and `fun probeStreamDetails(file: File): StreamCodecDetails?` — both consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCodecDetailsTest {
    @Test
    fun `probeStreamDetails extracts video codec fields from a real synthetic video`() {
        val video = File.createTempFile("stream-codec-details-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val details = probeStreamDetails(video)

        assertTrue(details != null)
        val videoFields = details!!.videoFields.associate { it.label to it.value }
        assertEquals("High 4:4:4 Predictive", videoFields["Profile"])
        assertEquals("10", videoFields["Level"])
        assertEquals("4:4:4", videoFields["Chroma Subsampling"])
        assertEquals("8 bit", videoFields["Bit Depth"])
        assertEquals("Constant", videoFields["Frame Rate Mode"])
        assertTrue(videoFields["Bit Rate"]?.contains("Kbps") == true, "Expected a Kbps bit rate, got ${videoFields["Bit Rate"]}")
        assertTrue(details.audioFields.isEmpty(), "This synthetic video has no audio stream")
        video.delete()
    }

    @Test
    fun `probeStreamDetails returns null for a nonexistent file`() {
        assertNull(probeStreamDetails(File("/nonexistent/path/does-not-exist.mp4")))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.StreamCodecDetailsTest" --console=plain`
Expected: FAIL to compile — `probeStreamDetails` and `StreamCodecDetails` don't exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt`:

```kotlin
package com.multiviewer.ui

import com.multiviewer.parser.SummaryField
import java.io.File
import java.util.concurrent.TimeUnit

data class StreamCodecDetails(
    val videoFields: List<SummaryField>,
    val audioFields: List<SummaryField>,
)

// One ffprobe call, cost independent of video length (unlike -show_frames, used for GOP
// analysis) -- safe to run automatically on every video open. Output is flat "key=value" lines
// grouped per stream, each group starting with an "index=" line (verified directly against a
// real HEVC+AAC file and a synthetic video -- no multi-line fields are requested here, so unlike
// a bare -show_streams there's no side_data/displaymatrix block to worry about).
fun probeStreamDetails(file: File): StreamCodecDetails? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error",
            "-show_entries",
            "stream=index,codec_type,profile,level,pix_fmt,color_space,color_transfer,color_primaries,color_range,bit_rate,r_frame_rate,avg_frame_rate,channel_layout",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(30, TimeUnit.SECONDS)

        val videoFields = mutableListOf<SummaryField>()
        val audioFields = mutableListOf<SummaryField>()
        var values = mutableMapOf<String, String>()

        fun finalizeStream() {
            when (values["codec_type"]) {
                "video" -> videoFields.addAll(buildVideoFields(values))
                "audio" -> audioFields.addAll(buildAudioFields(values))
            }
        }

        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key == "index" && values.isNotEmpty()) {
                finalizeStream()
                values = mutableMapOf()
            }
            values[key] = value
        }
        if (values.isNotEmpty()) finalizeStream()

        if (videoFields.isEmpty() && audioFields.isEmpty()) null else StreamCodecDetails(videoFields, audioFields)
    } catch (e: Exception) {
        null
    }
}

private fun buildVideoFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["level"]?.let { fields.add(SummaryField("Level", it)) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["pix_fmt"]?.let { pixFmt ->
        fields.add(SummaryField("Chroma Subsampling", chromaSubsamplingFrom(pixFmt)))
        fields.add(SummaryField("Bit Depth", bitDepthFrom(pixFmt)))
    }
    values["color_primaries"]?.let { fields.add(SummaryField("Color Primaries", it)) }
    values["color_transfer"]?.let { fields.add(SummaryField("Transfer Characteristics", it)) }
    values["color_space"]?.let { fields.add(SummaryField("Matrix Coefficients", it)) }
    values["color_range"]?.let { fields.add(SummaryField("Color Range", it)) }
    val rFrameRate = values["r_frame_rate"]
    val avgFrameRate = values["avg_frame_rate"]
    if (rFrameRate != null && avgFrameRate != null) {
        fields.add(SummaryField("Frame Rate Mode", if (rFrameRate == avgFrameRate) "Constant" else "Variable"))
    }
    return fields
}

private fun buildAudioFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["channel_layout"]?.let { fields.add(SummaryField("Channel Layout", it)) }
    return fields
}

private fun formatCodecBitrate(bitsPerSecond: Double): String = when {
    bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000)
    bitsPerSecond >= 1_000 -> "%.1f Kbps".format(bitsPerSecond / 1_000)
    else -> "%.0f bps".format(bitsPerSecond)
}

private fun chromaSubsamplingFrom(pixFmt: String): String = when {
    pixFmt.contains("420") -> "4:2:0"
    pixFmt.contains("422") -> "4:2:2"
    pixFmt.contains("444") -> "4:4:4"
    else -> pixFmt
}

private fun bitDepthFrom(pixFmt: String): String = when {
    pixFmt.contains("10") -> "10 bit"
    pixFmt.contains("12") -> "12 bit"
    pixFmt.contains("16") -> "16 bit"
    else -> "8 bit"
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.StreamCodecDetailsTest" --console=plain`
Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt
git commit -m "Add ffprobe-backed video/audio codec detail extraction"
```

---

### Task 2: Merge codec details into the media summary + wire into `openFile()`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `StreamCodecDetails`, `probeStreamDetails(file: File): StreamCodecDetails?` from Task 1 (`com.multiviewer.ui` package; `AppState.kt` is in the same package, no import needed — `MediaSummaryBuilder.kt` doesn't call `probeStreamDetails` directly, only the merge function, so it never needs to import from `ui`).
- Produces: `fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary` — consumed only by `AppState.kt` in this same task. Nothing later depends on this (last task in the plan).

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (inside the `MediaSummaryBuilderTest` class):

```kotlin
    @Test
    fun `mergeStreamCodecDetails appends fields onto the existing Video and Audio sections`() {
        val summary = MediaSummary(
            category = MediaCategory.VIDEO,
            sections = listOf(
                SummarySection("General", listOf(SummaryField("Duration", "0:00:03"))),
                SummarySection("Video", listOf(SummaryField("Format", "HEVC"), SummaryField("Width", "1752"))),
                SummarySection("Audio", listOf(SummaryField("Format", "AAC"))),
            ),
        )

        val merged = mergeStreamCodecDetails(
            summary,
            videoFields = listOf(SummaryField("Profile", "Main"), SummaryField("Bit Depth", "8 bit")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(3, merged.sections.size)
        assertEquals("General", merged.sections[0].title)
        assertEquals(listOf(SummaryField("Duration", "0:00:03")), merged.sections[0].fields)
        assertEquals("Video", merged.sections[1].title)
        assertEquals(
            listOf(
                SummaryField("Format", "HEVC"), SummaryField("Width", "1752"),
                SummaryField("Profile", "Main"), SummaryField("Bit Depth", "8 bit"),
            ),
            merged.sections[1].fields,
        )
        assertEquals("Audio", merged.sections[2].title)
        assertEquals(
            listOf(SummaryField("Format", "AAC"), SummaryField("Profile", "LC")),
            merged.sections[2].fields,
        )
    }

    @Test
    fun `mergeStreamCodecDetails leaves a summary with no Video or Audio section unchanged`() {
        val summary = MediaSummary(
            category = MediaCategory.VIDEO,
            sections = listOf(SummarySection("General", listOf(SummaryField("Duration", "0:00:03")))),
        )

        val merged = mergeStreamCodecDetails(
            summary,
            videoFields = listOf(SummaryField("Profile", "Main")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(summary, merged)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: FAIL to compile — `mergeStreamCodecDetails` doesn't exist yet.

- [ ] **Step 3: Add the merge function**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add at the end of the file (after the existing `formatBitrate` function):

```kotlin

fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    val mergedSections = summary.sections.map { section ->
        when (section.title) {
            "Video" -> section.copy(fields = section.fields + videoFields)
            "Audio" -> section.copy(fields = section.fields + audioFields)
            else -> section
        }
    }
    return summary.copy(sections = mergedSections)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS, all tests green (including the two new ones).

- [ ] **Step 5: Wire it into `AppState.openFile()`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
                val mediaSummary = try {
                    buildMediaSummary(root, file)
                } catch (e: Exception) {
                    null
                }
                val embeddedVideo = try {
```

with:

```kotlin
                val mediaSummary = try {
                    buildMediaSummary(root, file)
                } catch (e: Exception) {
                    null
                }
                // ffprobe -show_entries stream=... is one fast call whose cost doesn't scale with
                // video length (unlike GOP frame analysis), so it's safe to run automatically here
                // on the same background thread, same as probeVideo already does elsewhere.
                val enrichedMediaSummary = if (type == MediaType.VIDEO && mediaSummary != null) {
                    val details = probeStreamDetails(file)
                    if (details != null) {
                        mergeStreamCodecDetails(mediaSummary, details.videoFields, details.audioFields)
                    } else {
                        mediaSummary
                    }
                } else {
                    mediaSummary
                }
                val embeddedVideo = try {
```

Then, in the same file, replace:

```kotlin
                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = mediaSummary
                    tab.embeddedVideo = embeddedVideo
```

with:

```kotlin
                EventQueue.invokeLater {
                    tab.root = root
                    tab.type = type
                    tab.mediaSummary = enrichedMediaSummary
                    tab.embeddedVideo = embeddedVideo
```

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing — confirms the `AppState.kt` edit compiles cleanly and doesn't affect the existing `AppStateTest` async-behavior tests (they don't assert on `mediaSummary` contents, only on `isLoading`/`embeddedVideo`/etc., so this change is invisible to them).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Merge ffprobe codec details into the video media summary"
```

- [ ] **Step 8: Build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: app window opens with no build errors.

- [ ] **Step 9: Manually verify**

Open a real video file with both a video and audio track. In the "🎬 비디오 분석 요약" panel, confirm the "Video" section now also shows Profile, Level, Bit Rate, Chroma Subsampling, Bit Depth, Color Primaries, Transfer Characteristics, Matrix Coefficients, Color Range, and Frame Rate Mode alongside the existing Format/Width/Height/Frame Rate fields, and the "Audio" section shows Profile, Bit Rate, and Channel Layout alongside the existing Format/Sampling Rate/Channel(s) fields. Open a video with no audio track and confirm the "Video" section is still enriched correctly with no crash from the missing "Audio" section.
