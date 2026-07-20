# TIFF and AVIF Format Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add support for standalone AVIF and TIFF files — both confirmed via code inspection to need only small, targeted additions to existing infrastructure, not new parser modules.

**Architecture:** AVIF reuses the existing HEIF-family box-walking infrastructure already used for HEIC (just needs the `av01` codec box registered). TIFF reuses the existing `decodeTiff` IFD decoder (already used to parse JPEG/HEIC's embedded EXIF data) as a new top-level entry point, plus a small Media Summary addition for Resolution/Format.

**Tech Stack:** Kotlin 2.0.21, `kotlin.test`.

## Global Constraints

- No dedicated `av1C` (AV1 Codec Configuration Box) decoder — it shows as a generic node in Structure Analyser, which is acceptable; not in scope.
- No Color Space support for TIFF — not in scope.
- No multi-page TIFF support (`decodeTiff` only ever decodes IFD0, matching its existing behavior) — not in scope.
- No PNG, BMP, or GIF/animated-GIF work — separate, later efforts.

---

### Task 1: AVIF — register the `av01` codec box

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `VisualSampleEntryDecoder` (existing, already registered for `avc1`/`hvc1`), `buildMediaSummary`/`findPrimaryItemProperty` (existing, unchanged by this task — they already operate on `ispe`/`colr` box structure regardless of codec).
- Produces: nothing new — this task only adds a registry entry, no new public symbols.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`, add `"av01"` to the existing `typesThatMustHaveADecoder` list, so the full list reads:

```kotlin
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "av01", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd", "sefd", "iloc",
        )
```

Add this test to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `an AVIF-shaped tree (ftyp major_brand avif) produces correct Resolution, Format, and File Size`() {
        val ftyp = BoxNode(
            type = "ftyp", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("major_brand", "avif", 0, 4)),
        )
        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "1920", 0, 4), BoxField("image_height", "1080", 0, 4)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ftyp, meta))
        val file = File.createTempFile("avif-summary-test", ".avif")
        file.deleteOnExit()
        file.writeBytes(ByteArray(500_000))

        val basicInfo = buildMediaSummary(root, file).sections.first { it.title == "Basic Info" }
        assertEquals("1920x1080", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("avif", basicInfo.fields.first { it.label == "Format" }.value)
        assertEquals("500.0 KB", basicInfo.fields.first { it.label == "File Size" }.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.DecodersRegistrationTest" --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: `DecodersRegistrationTest` FAILs (`"av01"` falls back to `LeafBoxDecoder`); `MediaSummaryBuilderTest`'s new test already PASSes (the Resolution/Format/File Size logic is format-agnostic and needs no code change — only the registration test is testing genuinely new behavior). This is expected: it's the regression-proof that AVIF's Media Summary already worked before this task, confirming the design's claim.

- [ ] **Step 3: Register `av01`**

In `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`, add this line directly after the existing `BoxRegistry.register("hvc1", VisualSampleEntryDecoder)` line:

```kotlin
    BoxRegistry.register("av01", VisualSampleEntryDecoder)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.DecodersRegistrationTest" --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: PASS

- [ ] **Step 5: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (156 existing + 1 new = 157)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/Decoders.kt \
        app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: register av01 so AVIF's codec box decodes like HEIC's hvc1"
```

---

### Task 2: Standalone TIFF support

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `decodeTiff(reader: ByteReader, tiffStart: Long, itemEnd: Long): List<BoxNode>` (existing, `ExifDecoder.kt`, unchanged), `ByteReader.readBytes(offset: Long, len: Int): ByteArray` (existing).
- Produces: nothing new — `parseFile`'s signature is unchanged; this task only changes what it does internally for TIFF-magic files.

- [ ] **Step 1: Write the failing tests**

Add this test to the end of the `ParseFileIntegrationTest` class in `app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `parses a TIFF file via decodeTiff, not the ISOBMFF path`() {
        val bytes = byteArrayOf(
            0x49, 0x49, 0x2A, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x02, 0x00, // entry_count = 2
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(), 0x02, 0x00, 0x00, // ImageWidth = 640
            0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0xE0.toByte(), 0x01, 0x00, 0x00, // ImageLength = 480
            0x00, 0x00, 0x00, 0x00, // next IFD offset = 0
        )
        val tmp = File.createTempFile("multiviewer-tiff", ".tiff")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)

        val root = parseFile(tmp)

        assertEquals(listOf("IFD0"), root.children.map { it.type })
        val ifd0 = root.children.single()
        assertEquals("640", ifd0.fields.first { it.name == "ImageWidth" }.value)
        assertEquals("480", ifd0.fields.first { it.name == "ImageLength" }.value)
    }
```

Add this test to the end of the `MediaSummaryBuilderTest` class in `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (immediately before the class's closing `}`):

```kotlin
    @Test
    fun `a TIFF-shaped tree (IFD0 as a direct root child) produces Resolution, Format TIFF, Camera Info, and GPS Location`() {
        val gps = BoxNode(
            type = "GPS", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("GPSLatitudeRef", "N", 0, 1),
                BoxField("GPSLatitude", "37/1, 34/1, 0/1", 0, 24),
            ),
        )
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("Make", "TiffCam", 0, 7),
                BoxField("Model", "T200", 0, 4),
            ),
            children = listOf(gps),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))
        val file = File.createTempFile("tiff-summary-test", ".tiff")
        file.deleteOnExit()
        file.writeBytes(ByteArray(1000))

        val summary = buildMediaSummary(root, file)

        val basicInfo = summary.sections.first { it.title == "Basic Info" }
        assertEquals("640x480", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("TIFF", basicInfo.fields.first { it.label == "Format" }.value)

        val cameraInfo = summary.sections.first { it.title == "Camera Info" }
        assertEquals("TiffCam", cameraInfo.fields.first { it.label == "Make" }.value)
        assertEquals("T200", cameraInfo.fields.first { it.label == "Model" }.value)

        val gpsSection = summary.sections.first { it.title == "GPS Location" }
        assertEquals("N", gpsSection.fields.first { it.label == "Latitude Ref" }.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest" --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: `ParseFileIntegrationTest`'s new test FAILs — the synthetic TIFF bytes get routed through `parseBoxes` (the ISOBMFF path) instead of `decodeTiff`, producing a tree that doesn't have a single `"IFD0"` child (it'll be garbage/warning nodes from misinterpreting TIFF bytes as ISOBMFF box headers). `MediaSummaryBuilderTest`'s new test FAILs — `buildImageBasicInfo` has no TIFF branch yet, so Resolution and Format ("TIFF") are missing.

- [ ] **Step 3: Add TIFF detection to `parseFile`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` with:

```kotlin
package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isTiff = !isJpeg && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}
```

- [ ] **Step 4: Add the TIFF branch to `buildImageBasicInfo`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace:

```kotlin
private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe

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
```

with:

```kotlin
private fun buildImageBasicInfo(root: BoxNode, file: File): SummarySection {
    val fields = mutableListOf<SummaryField>()
    val isJpeg = root.children.any { it.type == "SOI" }
    val isTiff = root.children.any { it.type == "IFD0" }
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
    }

    fields.add(SummaryField("File Size", formatFileSize(file.length())))

    val format = if (isJpeg) {
        "JPEG"
    } else if (isTiff) {
        "TIFF"
    } else {
        root.children.find { it.type == "ftyp" }?.fields?.find { it.name == "major_brand" }?.value ?: "Unknown"
    }
    fields.add(SummaryField("Format", format))
```

No other line in `buildImageBasicInfo` changes — Color Space and Capture Date logic below this block are untouched. No other function in `MediaSummaryBuilder.kt` changes — `buildImageSummary`'s Camera Info/GPS Location construction already reads `ifd0.fields`/`ifd0.children` generically (via `findFirst(root) { it.type == "IFD0" }`), which correctly finds a root-level `IFD0` exactly as it already finds a deeply-nested one.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest" --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: PASS

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (157 existing + 2 new = 159)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt \
        app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/ParseFileIntegrationTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: parse standalone TIFF files via the existing IFD decoder"
```

- [ ] **Step 8: Manual verification note**

Launch the app (`./gradlew :app:run`) and open a real `.avif` file — confirm Media Summary shows correct Resolution/Color Space/Format/File Size, and Structure Analyser shows the `av01` box with decoded `width`/`height` fields (not a generic/empty node). Open a real `.tiff`/`.tif` file — confirm Media Summary shows Resolution, Format ("TIFF"), Camera Info (if the file has Make/Model tags), GPS Location (if present), and Capture Date (if present).
