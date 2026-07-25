# Codec Details Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Video and Audio codec-detail sections (main video, automatic; Motion Photo Video, button-triggered) also show per-stream Duration and Frame Count.

**Architecture:** `StreamCodecDetails.kt`'s ffprobe query gains `duration`/`nb_frames`, surfaced as two more fields per stream. `MediaSummaryBuilder.kt`'s merge logic is split so the section-list-level half can run on `motionPhotoVideoSections` directly (which has no `MediaCategory` to gate on, unlike the main video's `MediaSummary`). `AppState` gains a button-triggered `analyzeMotionPhotoCodecDetails` that extracts the embedded video to a short-lived temp file (reusing the existing `extractEmbeddedVideo`), probes it, merges, and deletes the temp file.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform Desktop, bundled `ffprobe`.

## Global Constraints

- Main video enrichment stays automatic (no button) — only the Motion Photo Video path is button-triggered, since it requires a temp-file extraction the main video path doesn't need (it's already a real file).
- No caching/sharing between this task's temp extraction and the separate one `MotionPhotoVideoPreview` already does for playback — different purposes, not worth the coordination complexity.
- Spec: `docs/superpowers/specs/2026-07-25-codec-details-extension-design.md`.

---

### Task 1: Add Duration and Frame Count to `probeStreamDetails`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `buildVideoFields`/`buildAudioFields` (both private, unchanged signatures) now also emit `SummaryField("Duration", ...)` and `SummaryField("Frame Count", ...)` when ffprobe reports them — consumed by Task 2/3 only insofar as those fields now flow through the existing `StreamCodecDetails.videoFields`/`audioFields` lists, no new public interface.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt`, replace:

```kotlin
        assertTrue(videoFields["Bit Rate"]?.contains("Kbps") == true, "Expected a Kbps bit rate, got ${videoFields["Bit Rate"]}")
        assertTrue(details.audioFields.isEmpty(), "This synthetic video has no audio stream")
```

with:

```kotlin
        assertTrue(videoFields["Bit Rate"]?.contains("Kbps") == true, "Expected a Kbps bit rate, got ${videoFields["Bit Rate"]}")
        assertEquals("0:00:02", videoFields["Duration"])
        assertEquals("20", videoFields["Frame Count"])
        assertTrue(details.audioFields.isEmpty(), "This synthetic video has no audio stream")
```

(Values verified by hand: `ffprobe ... -show_entries stream=...,duration,nb_frames` against this exact synthetic video reports `duration=2.000000`, `nb_frames=20`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.StreamCodecDetailsTest" --console=plain`
Expected: FAIL — `videoFields["Duration"]` and `videoFields["Frame Count"]` are both `null`, not `"0:00:02"`/`"20"`.

- [ ] **Step 3: Add the fields to the ffprobe query and field builders**

In `app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt`, replace:

```kotlin
            "-show_entries",
            "stream=index,codec_type,profile,level,pix_fmt,color_space,color_transfer,color_primaries,color_range,bit_rate,r_frame_rate,avg_frame_rate,channel_layout",
```

with:

```kotlin
            "-show_entries",
            "stream=index,codec_type,profile,level,pix_fmt,color_space,color_transfer,color_primaries,color_range,bit_rate,r_frame_rate,avg_frame_rate,channel_layout,duration,nb_frames",
```

Then replace:

```kotlin
private fun buildVideoFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["level"]?.let { fields.add(SummaryField("Level", it)) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
```

with:

```kotlin
private fun buildVideoFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["level"]?.let { fields.add(SummaryField("Level", it)) }
    values["duration"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Duration", formatCodecDuration(it))) }
    values["nb_frames"]?.toIntOrNull()?.let { fields.add(SummaryField("Frame Count", it.toString())) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
```

Then replace:

```kotlin
private fun buildAudioFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["channel_layout"]?.let { fields.add(SummaryField("Channel Layout", it)) }
    return fields
}
```

with:

```kotlin
private fun buildAudioFields(values: Map<String, String>): List<SummaryField> {
    val fields = mutableListOf<SummaryField>()
    values["profile"]?.let { fields.add(SummaryField("Profile", it)) }
    values["duration"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Duration", formatCodecDuration(it))) }
    values["nb_frames"]?.toIntOrNull()?.let { fields.add(SummaryField("Frame Count", it.toString())) }
    values["bit_rate"]?.toDoubleOrNull()?.let { fields.add(SummaryField("Bit Rate", formatCodecBitrate(it))) }
    values["channel_layout"]?.let { fields.add(SummaryField("Channel Layout", it)) }
    return fields
}

private fun formatCodecDuration(seconds: Double): String {
    val totalSeconds = seconds.toLong()
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d".format(h, m, s)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.ui.StreamCodecDetailsTest" --console=plain`
Expected: PASS, both tests green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/StreamCodecDetails.kt app/src/test/kotlin/com/multiviewer/ui/StreamCodecDetailsTest.kt
git commit -m "Add per-stream Duration and Frame Count to codec details"
```

---

### Task 2: Extract `mergeStreamCodecDetailsIntoSections` for the motion-photo path

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `fun mergeStreamCodecDetailsIntoSections(sections: List<SummarySection>, videoFields: List<SummaryField>, audioFields: List<SummaryField>): List<SummarySection>` — consumed by Task 3's `AppState.analyzeMotionPhotoCodecDetails`. `mergeStreamCodecDetails`'s existing public signature is unchanged (still `(MediaSummary, List<SummaryField>, List<SummaryField>) -> MediaSummary`), so nothing that already calls it needs to change.

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (inside the `MediaSummaryBuilderTest` class, after the existing `mergeStreamCodecDetails` tests):

```kotlin
    @Test
    fun `mergeStreamCodecDetailsIntoSections appends fields onto matching-titled sections directly`() {
        val sections = listOf(
            SummarySection("General", listOf(SummaryField("Format", "MOV"))),
            SummarySection("Video", listOf(SummaryField("Format", "HEVC"))),
            SummarySection("Audio", listOf(SummaryField("Format", "AAC"))),
        )

        val merged = mergeStreamCodecDetailsIntoSections(
            sections,
            videoFields = listOf(SummaryField("Profile", "Main")),
            audioFields = listOf(SummaryField("Profile", "LC")),
        )

        assertEquals(3, merged.size)
        assertEquals(listOf(SummaryField("Format", "MOV")), merged[0].fields)
        assertEquals(listOf(SummaryField("Format", "HEVC"), SummaryField("Profile", "Main")), merged[1].fields)
        assertEquals(listOf(SummaryField("Format", "AAC"), SummaryField("Profile", "LC")), merged[2].fields)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: FAIL to compile — `mergeStreamCodecDetailsIntoSections` doesn't exist yet.

- [ ] **Step 3: Refactor `mergeStreamCodecDetails`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    if (summary.category != MediaCategory.VIDEO) return summary
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

with:

```kotlin
fun mergeStreamCodecDetailsIntoSections(sections: List<SummarySection>, videoFields: List<SummaryField>, audioFields: List<SummaryField>): List<SummarySection> {
    return sections.map { section ->
        when (section.title) {
            "Video" -> section.copy(fields = section.fields + videoFields)
            "Audio" -> section.copy(fields = section.fields + audioFields)
            else -> section
        }
    }
}

fun mergeStreamCodecDetails(summary: MediaSummary, videoFields: List<SummaryField>, audioFields: List<SummaryField>): MediaSummary {
    if (summary.category != MediaCategory.VIDEO) return summary
    return summary.copy(sections = mergeStreamCodecDetailsIntoSections(summary.sections, videoFields, audioFields))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS, all tests green (including the existing `mergeStreamCodecDetails` tests, which must still pass unchanged since the refactor preserves that function's exact external behavior).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Extract mergeStreamCodecDetailsIntoSections for the motion-photo path"
```

---

### Task 3: Button-triggered codec-detail enrichment for the Motion Photo Video summary

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `mergeStreamCodecDetailsIntoSections` (Task 2), `probeStreamDetails`/`StreamCodecDetails` (already merged, same package as `AppState.kt`), `extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File)` (existing, `com.multiviewer.parser` package, already imported via `AppState.kt`'s `import com.multiviewer.parser.*`).
- Produces: nothing consumed by other tasks — last task in this plan.

No automated test for this task (established convention for this project's UI layer and for temp-extraction-and-probe orchestration that composes already-individually-tested pieces — matches how the GOP feature's equivalent UI-wiring task had no test). Verification is the full test suite compiling cleanly plus the manual check in Step 6.

- [ ] **Step 1: Add the new `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace:

```kotlin
    // GOP / frame-type analysis (see FrameTypeAnalyzer.kt) -- null gopFrames means "never asked";
    // an empty (non-null) list means "asked, ffprobe found nothing".
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)
}
```

with:

```kotlin
    // GOP / frame-type analysis (see FrameTypeAnalyzer.kt) -- null gopFrames means "never asked";
    // an empty (non-null) list means "asked, ffprobe found nothing".
    var gopFrames: List<FrameInfo>? by mutableStateOf(null)
    var isAnalyzingFrames: Boolean by mutableStateOf(false)
    var selectedFrame: FrameInfo? by mutableStateOf(null)

    // Motion Photo Video codec-detail enrichment (see StreamCodecDetails.kt) -- button-triggered
    // since, unlike the main video, this requires extracting the embedded video to a temp file
    // before ffprobe can see it. motionPhotoVideoSections is already non-null before this runs
    // (built by buildMediaSummary), so unlike gopFrames its nullability can't signal "not yet
    // asked" -- a separate flag is needed.
    var isAnalyzingMotionPhotoCodec: Boolean by mutableStateOf(false)
    var motionPhotoCodecDetailsLoaded: Boolean by mutableStateOf(false)
}
```

- [ ] **Step 2: Add `AppState.analyzeMotionPhotoCodecDetails`**

In the same file, add this method to the `AppState` class, right after `analyzeFrames`:

```kotlin
    fun analyzeMotionPhotoCodecDetails(tab: TabState) {
        val video = tab.embeddedVideo ?: return
        if (tab.isAnalyzingMotionPhotoCodec || tab.motionPhotoCodecDetailsLoaded) return
        tab.isAnalyzingMotionPhotoCodec = true
        Thread {
            val temp = try {
                val dest = File.createTempFile("motion-photo-codec-probe-", ".${video.extension}")
                dest.deleteOnExit()
                extractEmbeddedVideo(tab.file, video, dest)
                dest
            } catch (e: Exception) {
                null
            }
            val details = temp?.let { probeStreamDetails(it) }
            temp?.delete()
            EventQueue.invokeLater {
                val summary = tab.mediaSummary
                if (details != null && summary != null) {
                    val currentSections = summary.motionPhotoVideoSections ?: emptyList()
                    val merged = mergeStreamCodecDetailsIntoSections(currentSections, details.videoFields, details.audioFields)
                    tab.mediaSummary = summary.copy(motionPhotoVideoSections = merged)
                }
                tab.motionPhotoCodecDetailsLoaded = true
                tab.isAnalyzingMotionPhotoCodec = false
            }
        }.apply { isDaemon = true }.start()
    }
```

- [ ] **Step 3: Add `appState` parameter to `ImageInspectorUI`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, replace:

```kotlin
@Composable
fun ImageInspectorUI(
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
```

with:

```kotlin
@Composable
fun ImageInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
```

Add the missing `Button` import — replace:

```kotlin
import androidx.compose.material3.Text
```

with:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.Text
```

- [ ] **Step 4: Add the analyze button above the Motion Photo Video summary**

In the same file, replace:

```kotlin
                    item {
                        val videoSections = summary?.motionPhotoVideoSections
                        if (videoSections != null) {
                            Spacer(Modifier.height(16.dp))
                            SummaryBox("🎬 동영상 (모션포토)", videoSections)
                        }
                    }
```

with:

```kotlin
                    item {
                        val videoSections = summary?.motionPhotoVideoSections
                        if (videoSections != null) {
                            Spacer(Modifier.height(16.dp))
                            if (tab.isAnalyzingMotionPhotoCodec) {
                                Text("분석 중...", color = AppColors.TextSecondary, fontSize = 12.sp)
                            } else if (!tab.motionPhotoCodecDetailsLoaded) {
                                Button(onClick = { appState.analyzeMotionPhotoCodecDetails(tab) }) {
                                    Text("코덱 상세정보 분석")
                                }
                            }
                            SummaryBox("🎬 동영상 (모션포토)", videoSections)
                        }
                    }
```

- [ ] **Step 5: Update `ImageInspectorUI`'s call site in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, replace:

```kotlin
                            MediaType.IMAGE -> ImageInspectorUI(currentTab, leftPanel, bottomPanel)
```

with:

```kotlin
                            MediaType.IMAGE -> ImageInspectorUI(appState, currentTab, leftPanel, bottomPanel)
```

- [ ] **Step 6: Run the full test suite, then build and run the app**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing — confirms the `ImageInspectorUI` signature change and `Main.kt` call-site update compile cleanly.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew :app:run`
Expected: app window opens with no build errors.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Add button-triggered codec-detail enrichment for Motion Photo Video summary"
```

- [ ] **Step 8: Manually verify**

Open a motion-photo image file. Confirm the "코덱 상세정보 분석" button appears above the "🎬 동영상 (모션포토)" summary. Click it, confirm "분석 중..." appears briefly, then the button disappears and the summary's Video/Audio sections show Profile/Bit Rate/Duration/Frame Count/etc. Open a plain video file (not a motion photo) and confirm its main "🎬 비디오 분석 요약" panel's Video/Audio sections now also show Duration and Frame Count fields (added automatically, no button, from Task 1) alongside the previously-added codec fields. Open a plain image with no motion photo and confirm no button/section renders and nothing else regresses.
