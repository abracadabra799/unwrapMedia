# MediaInfo Tree View Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure Media Summary's section/field naming to match MediaInfo's Tree view categories (General/Image for still images; General/Video/Audio for video) across every format the app supports, reusing 100% of already-extracted data — no new byte-level parsing.

**Architecture:** All changes live in `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`. The image path's single `buildImageBasicInfo` function splits into `buildImageGeneral` (Format, File Size) and `buildImageDetail` (Width, Height, Color Space, Capture Date). The video path's `buildVideoBasicInfo`/`buildVideoTrackDetail`/`buildAudioTrackDetail` are renamed to `buildVideoGeneral`/`buildVideoDetail`/`buildAudioDetail`, with movie-level Width/Height moving out of General and into the Video section, and a new small `CODEC_DISPLAY_NAMES` lookup table translating raw codec box-type strings (`avc1`/`hvc1`/`av01`/`mp4a`) to MediaInfo's human names (AVC/HEVC/AV1/AAC) wherever a codec is displayed. Motion Photo's embedded-video summary reuses `buildVideoSummary` unchanged, so it automatically inherits the new General/Video/Audio structure.

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`.

## Global Constraints

- No new byte-level parsing — every field shown today continues to come from the exact same existing extraction logic; only section grouping, field labels, and codec display values change.
- Container/format-brand values are NOT translated (e.g. `ftyp` major_brand `"isom"` stays `"isom"`, not "MPEG-4") — only codec values (Video/Audio `Format` field) go through the new lookup table.
- Only 4 codecs are mapped (`avc1`→AVC, `hvc1`→HEVC, `av01`→AV1, `mp4a`→AAC) — any other codec box-type string falls back to itself unchanged, exactly as today's raw display already does.
- Camera Info, GPS Location, Samsung Metadata, and Track List sections are unchanged — same titles, same fields, same construction logic.
- No changes to `MediaSummaryView.kt` or any other file — this is a `MediaSummaryBuilder.kt`-only data restructuring.

---

### Task 1: Image summary — split Basic Info into General + Image

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findFirst(node, predicate): BoxNode?`, `findPrimaryItemProperty(root, propertyType): BoxNode?`, `formatFileSize(bytes: Long): String`, `PNG_COLOR_TYPE_NAMES: Map<Int, String>` (all existing, unchanged).
- Produces: `buildImageGeneral(root: BoxNode, file: File): SummarySection` and `buildImageDetail(root: BoxNode): SummarySection?` (new; replace the removed `buildImageBasicInfo`). `buildImageSummary` (existing, modified caller) now calls both. Task 2 does not depend on these — the image and video paths are independent — but future work should know the image section titles are now `"General"`/`"Image"`, not `"Basic Info"`.

- [ ] **Step 1: Update the existing tests to their new expected structure**

In `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`, make the following replacements.

Replace the test name and body of `a full image tree produces all four image sections with correct values`:

```kotlin
    @Test
    fun `a full image tree produces all four image sections with correct values`() {
```

with:

```kotlin
    @Test
    fun `a full image tree produces all five image sections with correct values`() {
```

Then, within that same test, replace:

```kotlin
        assertEquals(MediaCategory.IMAGE, summary.category)
        assertEquals(4, summary.sections.size)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("1.5 MB", basicInfo.fields.first { it.label == "File Size" }.value)
        assertEquals("JPEG", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("Color (YCbCr)", basicInfo.fields.first { it.label == "Color Space" }.value)
        assertEquals("2026:07:19 10:00:00", basicInfo.fields.first { it.label == "Capture Date" }.value)
```

with:

```kotlin
        assertEquals(MediaCategory.IMAGE, summary.category)
        assertEquals(5, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("1.5 MB", general.fields.first { it.label == "File Size" }.value)
        assertEquals("JPEG", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("640", image.fields.first { it.label == "Width" }.value)
        assertEquals("480", image.fields.first { it.label == "Height" }.value)
        assertEquals("Color (YCbCr)", image.fields.first { it.label == "Color Space" }.value)
        assertEquals("2026:07:19 10:00:00", image.fields.first { it.label == "Capture Date" }.value)
```

Replace the test name and body of `a minimal image tree with no Exif produces only a Basic Info section`:

```kotlin
    @Test
    fun `a minimal image tree with no Exif produces only a Basic Info section`() {
```

with:

```kotlin
    @Test
    fun `a minimal image tree with no Exif produces only General and Image sections`() {
```

Then, within that test, replace:

```kotlin
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(1, summary.sections.size)
        val basicInfo = summary.sections[0]
        assertEquals("Basic Info", basicInfo.title)
        assertEquals("Grayscale", basicInfo.fields.first { it.label == "Color Space" }.value)
```

with:

```kotlin
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(2, summary.sections.size)
        assertEquals("General", summary.sections[0].title)
        assertEquals("Image", summary.sections[1].title)
        val image = summary.sections.first { it.title == "Image" }
        assertEquals("Grayscale", image.fields.first { it.label == "Color Space" }.value)
```

In `Resolution and Color Space use the primary item's ispe and colr, not the first one in tree order`, replace:

```kotlin
        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("4000x2252", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("nclx: 9/16/9", basicInfo.fields.first { it.label == "Color Space" }.value)
```

with:

```kotlin
        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("4000", image.fields.first { it.label == "Width" }.value)
        assertEquals("2252", image.fields.first { it.label == "Height" }.value)
        assertEquals("nclx: 9/16/9", image.fields.first { it.label == "Color Space" }.value)
```

In `without pitm or ipma, Resolution falls back to the first ispe in tree order`, replace:

```kotlin
        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("800x600", basicInfo.fields.first { it.label == "Resolution" }.value)
```

with:

```kotlin
        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("800", image.fields.first { it.label == "Width" }.value)
        assertEquals("600", image.fields.first { it.label == "Height" }.value)
```

In `when the primary item's properties don't include a colr, Color Space falls back to the first colr in tree order`, replace:

```kotlin
        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("ICC profile (10 bytes)", basicInfo.fields.first { it.label == "Color Space" }.value)
```

with:

```kotlin
        val image = buildMediaSummary(root, tempFile()).sections.first { it.title == "Image" }
        assertEquals("ICC profile (10 bytes)", image.fields.first { it.label == "Color Space" }.value)
```

In `a motion photo image populates motionPhotoVideoSections from the embedded video, using the video's own size`, replace ONLY these two lines (leave the `videoSections`/`videoBasicInfo` lines below them untouched — those belong to Task 2):

```kotlin
        assertEquals(MediaCategory.IMAGE, summary.category)
        val imageBasicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("76 bytes", imageBasicInfo.fields.first { it.label == "File Size" }.value)
```

with:

```kotlin
        assertEquals(MediaCategory.IMAGE, summary.category)
        val imageGeneral = summary.sections.first { it.title == "General" }
        assertEquals("76 bytes", imageGeneral.fields.first { it.label == "File Size" }.value)
```

In `an AVIF-shaped tree (ftyp major_brand avif) produces correct Resolution, Format, and File Size`, replace:

```kotlin
        val basicInfo = buildMediaSummary(root, file).sections.first { it.title == "Basic Info" }
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("avif", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 KB", basicInfo.fields.first { it.label == "File Size" }.value)
```

with:

```kotlin
        val summary = buildMediaSummary(root, file)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("avif", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 KB", general.fields.first { it.label == "File Size" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("1920", image.fields.first { it.label == "Width" }.value)
        assertEquals("1080", image.fields.first { it.label == "Height" }.value)
```

In `a TIFF-shaped tree (IFD0 as a direct root child) produces Resolution, Format TIFF, Camera Info, and GPS Location`, replace:

```kotlin
        val summary = buildMediaSummary(root, file)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("TIFF", basicInfo.fields.first { it.label == "Format" }.value)
```

with:

```kotlin
        val summary = buildMediaSummary(root, file)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("TIFF", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("640", image.fields.first { it.label == "Width" }.value)
        assertEquals("480", image.fields.first { it.label == "Height" }.value)
```

In `a PNG-shaped tree (IHDR as a direct root child) produces Resolution, Format PNG, and Color Space`, replace:

```kotlin
        val basicInfo = buildMediaSummary(root, file).sections.first { it.title == "Basic Info" }
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("PNG", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("Truecolor+Alpha", basicInfo.fields.first { it.label == "Color Space" }.value)
```

with:

```kotlin
        val summary = buildMediaSummary(root, file)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("PNG", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("1920", image.fields.first { it.label == "Width" }.value)
        assertEquals("1080", image.fields.first { it.label == "Height" }.value)
        assertEquals("Truecolor+Alpha", image.fields.first { it.label == "Color Space" }.value)
```

In `a BMP-shaped tree produces Resolution and Format BMP, with no Color Space or Camera Info sections`, replace:

```kotlin
        val summary = buildMediaSummary(root, file)

        assertEquals(1, summary.sections.size)
        val basicInfo = summary.sections[0]
        assertEquals("Basic Info", basicInfo.title)
        assertEquals("100x50", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("BMP", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals(null, basicInfo.fields.find { it.label == "Color Space" })
```

with:

```kotlin
        val summary = buildMediaSummary(root, file)

        assertEquals(2, summary.sections.size)
        val general = summary.sections.first { it.title == "General" }
        assertEquals("BMP", general.fields.first { it.label == "Format" }.value)

        val image = summary.sections.first { it.title == "Image" }
        assertEquals("100", image.fields.first { it.label == "Width" }.value)
        assertEquals("50", image.fields.first { it.label == "Height" }.value)
        assertEquals(null, image.fields.find { it.label == "Color Space" })
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: FAIL — every test above looks for a `"General"`/`"Image"` section title that `buildImageBasicInfo` doesn't produce yet (it still emits one `"Basic Info"` section), and looks for `"Width"`/`"Height"` fields that don't exist yet (only the combined `"Resolution"` field exists).

- [ ] **Step 3: Replace `buildImageBasicInfo` with `buildImageGeneral` + `buildImageDetail`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${height}"))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Resolution", "${width}x${abs(height)}"))
        }
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else if (isPng) {
        "PNG"
    } else if (isBmp) {
        "BMP"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val colorType = ihdr?.fields?.find { it.name == "color_type" }?.value?.toIntOrNull()
        colorType?.let { PNG_COLOR_TYPE_NAMES[it] }
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

with:

```kotlin
private fun buildImageGeneral(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else if (isPng) {
        "PNG"
    } else if (isBmp) {
        "BMP"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))
    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    return SummarySection("General", fields)
}

private fun buildImageDetail(root: BoxNode): SummarySection? {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
    val isPng = root.children.any { it.type == "IHDR" }
    val isBmp = root.children.any { it.type == "BITMAPFILEHEADER" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

    if (sofOrIspe != null) {
        val width = sofOrIspe.fields.find { it.name == "width" || it.name == "image_width" }?.value
        val height = sofOrIspe.fields.find { it.name == "height" || it.name == "image_height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isTiff) {
        val tiffIfd0 = root.children.find { it.type == "IFD0" }
        val width = tiffIfd0?.fields?.find { it.name == "ImageWidth" }?.value
        val height = tiffIfd0?.fields?.find { it.name == "ImageLength" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val width = ihdr?.fields?.find { it.name == "width" }?.value
        val height = ihdr?.fields?.find { it.name == "height" }?.value
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", height))
        }
    } else if (isBmp) {
        val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" }
        val width = infoHeader?.fields?.find { it.name == "width" }?.value
        val height = infoHeader?.fields?.find { it.name == "height" }?.value?.toIntOrNull()
        if (width != null && height != null) {
            fields.add(SummaryField("Width", width))
            fields.add(SummaryField("Height", abs(height).toString()))
        }
    }

    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
    val colorSpace = if (colr != null) {
        colr.summary ?: "Unknown"
    } else if (sofOrIspe != null && isJpeg) {
        when (sofOrIspe.fields.find { it.name == "num_components" }?.value?.toIntOrNull()) {
            3 -> "Color (YCbCr)"
            1 -> "Grayscale"
            else -> "Unknown"
        }
    } else if (isPng) {
        val ihdr = root.children.find { it.type == "IHDR" }
        val colorType = ihdr?.fields?.find { it.name == "color_type" }?.value?.toIntOrNull()
        colorType?.let { PNG_COLOR_TYPE_NAMES[it] }
    } else {
        null
    }
    colorSpace?.let { fields.add(SummaryField("Color Space", it)) }

    val ifd0 = findFirst(root) { it.type == "IFD0" }
    val exif = ifd0?.children?.find { it.type == "Exif" }
    val captureDate = exif?.fields?.find { it.name == "DateTimeOriginal" }?.value
        ?: ifd0?.fields?.find { it.name == "DateTime" }?.value
    captureDate?.let { fields.add(SummaryField("Capture Date", it)) }

    return if (fields.isNotEmpty()) SummarySection("Image", fields) else null
}
```

`buildImageDetail` returns `SummarySection?` (null when empty) to match the established pattern already used by Camera Info/GPS Location/Video Track Detail/Audio Track Detail elsewhere in this file — an image tree with no recognizable width/height/color-space/capture-date shouldn't show an empty "Image" box. Note this can't happen for any currently-supported format (JPEG/TIFF/PNG/BMP/HEIC/AVIF all yield at least Width/Height), so no existing test's section count is affected by this nullability.

- [ ] **Step 4: Update `buildImageSummary`'s caller code**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageBasicInfo(root, file))
```

with:

```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
```

No other line in `buildImageSummary` changes — Camera Info, GPS Location, and Samsung Metadata construction below this block are untouched.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (175 tests — no new tests added in this task, only existing assertions updated)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: split image Basic Info into MediaInfo-style General + Image sections"
```

---

### Task 2: Video summary — rename to General/Video/Audio, add codec display names

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `findFirst(node, predicate): BoxNode?`, `formatFileSize`, `formatDuration`, `formatBitrate` (all existing, unchanged), `trakHandlerType(trak: BoxNode): String?` (existing, unchanged).
- Produces: `CODEC_DISPLAY_NAMES: Map<String, String>` (new, private, this file only — unlike `PNG_COLOR_TYPE_NAMES` this one isn't consumed elsewhere, so it stays `private`). `buildVideoGeneral`, `buildVideoDetail`, `buildAudioDetail` (renamed from `buildVideoBasicInfo`/`buildVideoTrackDetail`/`buildAudioTrackDetail`) — no other file calls these, they're wired together inside `buildVideoSummary` in this same task.

- [ ] **Step 1: Update the existing tests to their new expected structure, and add the codec-fallback test**

In `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`, make the following replacements.

Replace the test name and body of `a full video tree produces all four video sections with correct values`:

```kotlin
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
        assertEquals("0:00:20", basicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("isom", basicInfo.fields.first { it.label == "Container Brand" }.value)
        assertEquals("500.0 Kbps", basicInfo.fields.first { it.label == "Average Bitrate" }.value)

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video Track Detail" }
        assertEquals("avc1", videoDetail.fields.first { it.label == "Codec" }.value)
        // Deliberately distinct from mvhd's 20s movie-level duration above: this fixture's video
        // track has its own mdhd (30000/300000 = 10s). If frame-rate calculation ever regressed to
        // use mvhd's duration instead of the track's own mdhd, this would compute 15.00 fps instead
        // of the correct 30.00 fps.
        assertEquals("30.00 fps", videoDetail.fields.first { it.label == "Frame Rate" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio Track Detail" }
        assertEquals("mp4a", audioDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sample Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channels" }.value)
    }
```

with:

```kotlin
    @Test
    fun `a full video tree produces General, Track List, Video, and Audio sections with correct values`() {
        val root = buildVideoFixture(includeAudioTrack = true)
        val tmp = File.createTempFile("media-summary-video-test", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(1_250_000))

        val summary = buildMediaSummary(root, tmp)

        assertEquals(MediaCategory.VIDEO, summary.category)
        assertEquals(4, summary.sections.size)

        val general = summary.sections.first { it.title == "General" }
        assertEquals("0:00:20", general.fields.first { it.label == "Duration" }.value)
        assertEquals("isom", general.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 Kbps", general.fields.first { it.label == "Overall Bit Rate" }.value)
        assertEquals(null, general.fields.find { it.label == "Width" })

        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("1", trackList.fields.first { it.label == "Video Tracks" }.value)
        assertEquals("1", trackList.fields.first { it.label == "Audio Tracks" }.value)

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("AVC", videoDetail.fields.first { it.label == "Format" }.value)
        assertEquals("1920", videoDetail.fields.first { it.label == "Width" }.value)
        assertEquals("1080", videoDetail.fields.first { it.label == "Height" }.value)
        // Deliberately distinct from mvhd's 20s movie-level duration above: this fixture's video
        // track has its own mdhd (30000/300000 = 10s). If frame-rate calculation ever regressed to
        // use mvhd's duration instead of the track's own mdhd, this would compute 15.00 fps instead
        // of the correct 30.00 fps.
        assertEquals("30.00 fps", videoDetail.fields.first { it.label == "Frame Rate" }.value)

        val audioDetail = summary.sections.first { it.title == "Audio" }
        assertEquals("AAC", audioDetail.fields.first { it.label == "Format" }.value)
        assertEquals("44100.0 Hz", audioDetail.fields.first { it.label == "Sampling Rate" }.value)
        assertEquals("2", audioDetail.fields.first { it.label == "Channel(s)" }.value)
    }
```

Replace the test name and body of `a video-only tree (no audio track) omits Audio Track Detail`:

```kotlin
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

with:

```kotlin
    @Test
    fun `a video-only tree (no audio track) omits the Audio section`() {
        val root = buildVideoFixture(includeAudioTrack = false)
        val summary = buildMediaSummary(root, tempFile())

        assertEquals(3, summary.sections.size)
        assertEquals(null, summary.sections.find { it.title == "Audio" })
        val trackList = summary.sections.first { it.title == "Track List" }
        assertEquals("0", trackList.fields.first { it.label == "Audio Tracks" }.value)
    }
```

In `a motion photo image populates motionPhotoVideoSections from the embedded video, using the video's own size`, replace:

```kotlin
        val videoSections = summary.motionPhotoVideoSections
        assertEquals(true, videoSections != null)
        val videoBasicInfo = videoSections!!.first { it.title == "Basic Info" }
        assertEquals("0:00:02", videoBasicInfo.fields.first { it.label == "Duration" }.value)
        assertEquals("52 bytes", videoBasicInfo.fields.first { it.label == "File Size" }.value)
```

with:

```kotlin
        val videoSections = summary.motionPhotoVideoSections
        assertEquals(true, videoSections != null)
        val videoGeneral = videoSections!!.first { it.title == "General" }
        assertEquals("0:00:02", videoGeneral.fields.first { it.label == "Duration" }.value)
        assertEquals("52 bytes", videoGeneral.fields.first { it.label == "File Size" }.value)
```

Add this new test to the end of the `MediaSummaryBuilderTest` class (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `an unrecognized video codec falls back to its raw box-type string under Format`() {
        val videoHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val s263 = BoxNode(type = "s263", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("width", "352.0", 0, 2), BoxField("height", "288.0", 0, 2)))
        val videoStsd = BoxNode(type = "stsd", offset = 0, headerSize = 0, size = 0, children = listOf(s263))
        val videoStbl = BoxNode(type = "stbl", offset = 0, headerSize = 0, size = 0, children = listOf(videoStsd))
        val videoMinf = BoxNode(type = "minf", offset = 0, headerSize = 0, size = 0, children = listOf(videoStbl))
        val videoMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(videoHdlr, videoMinf))
        val videoTkhd = BoxNode(type = "tkhd", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("track_ID", "1", 0, 4), BoxField("width", "352.0", 0, 4), BoxField("height", "288.0", 0, 4)))
        val videoTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(videoTkhd, videoMdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(videoTrak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))

        val summary = buildMediaSummary(root, tempFile())

        val videoDetail = summary.sections.first { it.title == "Video" }
        assertEquals("s263", videoDetail.fields.first { it.label == "Format" }.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: FAIL — the updated tests look for `"General"`/`"Video"`/`"Audio"` section titles and `"Format"`/`"Sampling Rate"`/`"Channel(s)"` field labels that don't exist yet (the code still emits `"Basic Info"`/`"Video Track Detail"`/`"Audio Track Detail"` with `"Codec"`/`"Sample Rate"`/`"Channels"`), and the new codec-fallback test fails to compile against `buildVideoDetail`/`"Video"` title expectations that don't exist yet either.

- [ ] **Step 3: Add the codec display name map**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add this near the top of the file, directly after the `import kotlin.math.abs` line:

```kotlin

private val CODEC_DISPLAY_NAMES = mapOf(
    "avc1" to "AVC",
    "hvc1" to "HEVC",
    "av01" to "AV1",
    "mp4a" to "AAC",
)
```

- [ ] **Step 4: Rename and restructure `buildVideoSummary`'s three section-builder functions**

Replace:

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

with:

```kotlin
private fun buildVideoSummary(root: BoxNode, fileSizeBytes: Long): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    val moov = root.children.find { it.type == "moov" }
    val traks = moov?.children?.filter { it.type == "trak" } ?: emptyList()
    val videoTrak = traks.find { trakHandlerType(it) == "vide" }
    val audioTrak = traks.find { trakHandlerType(it) == "soun" }

    sections.add(buildVideoGeneral(root, fileSizeBytes, moov))
    sections.add(buildTrackList(traks))
    buildVideoDetail(videoTrak)?.let { sections.add(it) }
    buildAudioDetail(audioTrak)?.let { sections.add(it) }

    return sections
}
```

Replace:

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

with:

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

Replace:

```kotlin
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
```

with:

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

Replace:

```kotlin
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
```

with:

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

`videoTrak.children.find` (not `videoTrak?.children?.find`) is correct here: the function already returns early with `if (videoTrak == null) return null`, so Kotlin smart-casts `videoTrak` to non-null `BoxNode` for the rest of the function.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21; export PATH="$JAVA_HOME/bin:$PATH"; ./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (175 existing + 1 new = 176)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: rename video sections to MediaInfo-style General/Video/Audio, map codec display names"
```

- [ ] **Step 8: Manual verification note**

Launch the app (`./gradlew :app:run`) and open one file per format: a JPEG/HEIC/PNG/BMP/TIFF/AVIF image (confirm **General** shows Format + File Size, and **Image** shows Width/Height as separate rows plus Color Space/Capture Date where applicable); an MP4/MOV video (confirm **General** shows Format/File Size/Duration/Overall Bit Rate with no Width/Height, **Track List** is unchanged, **Video** shows Format as a human codec name like "AVC" plus Width/Height/Frame Rate, **Audio** shows Format/Sampling Rate/Channel(s)); a Motion Photo JPEG (confirm its embedded-video box also shows General/Video/Audio). Confirm Camera Info, GPS Location, and Samsung Metadata sections (where applicable) are unaffected.
