# Shared ByteReader on File Open Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce a file's open-count on the way to becoming interactive (structure
parse + media summary + image forensic analysis) from 4 independent `ByteReader`
opens down to 1, in both the Media Comparison Analyzer and the main app's normal
open flow.

**Architecture:** Add a reader-accepting sibling overload next to each of
`parseFile`, `buildMediaSummary`, and `ImageAnalyzer.analyze` that carries the
existing logic; the existing two-argument public functions become thin wrappers that
open one reader and delegate. Only the two real per-file-open orchestrators
(`ImageCompareWindow.loadInfo`, `AppState.openFile`) are changed to open one shared
reader and call the new overloads — every other caller (130+ existing tests, the CLI)
keeps calling the unchanged two-argument signatures.

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-11-shared-bytereader-on-open-design.md` — every requirement below traces back to it.
- Every reader-accepting overload must produce output identical to its existing file-only counterpart for the same input — this is a pure I/O-count reduction, not a behavior change.
- Existing two-argument public signatures (`parseFile(path: File)`, `buildMediaSummary(root: BoxNode, file: File)`, `ImageAnalyzer.analyze(file: File, root: BoxNode)`) are **not removed or changed** — 130+ existing test call sites and the CLI (`ParseForCli.kt`, `AiDiagnosticPromptBuilder.kt`) must compile and pass unmodified.
- Out of scope (do not touch): `AppState.openFile`'s `findEmbeddedVideo` call and `GainmapParser.findGainmapInfo` (both still open their own separate readers), and `ImageAnalyzer.decodePrimaryBitmapAndHistogram`'s `file.readBytes()`.
- After all tasks: run the full test suite AND manually verify basic open flows (plain image in the main window, two images in the Media Comparison Analyzer, a video, an audio file, a motion-photo JPEG) — explicitly requested since this touches the shared parse pipeline every file open goes through.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt` | Split `parseFile(path: File)` into a delegating wrapper + new `parseFile(path: File, reader: ByteReader)`. |
| `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` | Same split for `buildMediaSummary`; `buildMotionPhotoVideoSummary`/`buildThumbnail` take `reader: ByteReader` directly instead of opening their own. |
| `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt` | Same split for `analyze`; `tryExtractEmbeddedJpeg` takes `reader: ByteReader` directly instead of opening its own. |
| `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt` | Add an `internal` open-call counter so tests can assert on open counts without instrumenting every call site. |
| `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt` | `loadInfo`'s fresh-load branch opens one reader and calls the 3-argument overloads. |
| `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` | `openFile`'s equivalent span opens one reader and calls the 3-argument overloads. |
| `app/src/test/kotlin/com/multiviewer/parser/ParseFileReaderOverloadTest.kt` | New. Equivalence test for Task 1. |
| `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderReaderOverloadTest.kt` | New. Equivalence tests for Task 2. |
| `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerReaderOverloadTest.kt` | New. Equivalence test for Task 3. |
| `app/src/test/kotlin/com/multiviewer/parser/ByteReaderOpenCountTest.kt` | New. Open-count regression test for Task 4. |

---

### Task 1: Split `parseFile` into a reader-accepting overload

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ParseFileReaderOverloadTest.kt` (new)

**Interfaces:**
- Consumes: `ByteReader` (existing, `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`).
- Produces: `fun parseFile(path: File, reader: ByteReader): BoxNode` — same return value as the existing `parseFile(path: File): BoxNode` for the same file, but reads through the caller-supplied, already-open `reader` instead of opening its own.

- [ ] **Step 1: Write the failing equivalence test**

Create `app/src/test/kotlin/com/multiviewer/parser/ParseFileReaderOverloadTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ParseFileReaderOverloadTest {
    // Same minimal synthetic MP4 fixture ParseFileIntegrationTest.kt's first test uses --
    // reusing it (rather than a new fixture) keeps this test's only variable the reader
    // overload itself, not the fixture.
    private fun syntheticMp4Bytes(): ByteArray {
        val ftyp = box("ftyp", byteArrayOf(0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00))
        val mvhd = fullBox("mvhd", version = 0, body = uint32(0) + uint32(0) + uint32(600) + uint32(1200))
        val mdat = box("mdat", byteArrayOf(0x01, 0x02, 0x03))
        return ftyp + mvhd + mdat
    }

    @Test
    fun `reader-accepting overload produces the same tree as the file-only overload`() {
        val tmp = File.createTempFile("multiviewer-reader-overload", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(syntheticMp4Bytes())

        val viaFileOnly = parseFile(tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> parseFile(tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.ParseFileReaderOverloadTest"`
Expected: FAIL — `parseFile(tmp, reader)` is unresolved (no such overload yet; compilation failure in the test source set).

- [ ] **Step 3: Split `parseFile`**

In `app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt`, replace the existing
`fun parseFile(path: File): BoxNode { ... }` (the whole function, lines 5-45) with:

```kotlin
fun parseFile(path: File): BoxNode = ByteReader.open(path).use { reader -> parseFile(path, reader) }

fun parseFile(path: File, reader: ByteReader): BoxNode {
    registerAllDecoders()
    val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
    val isPng = !isJpeg && isPngMagic(reader)
    val isBmp = !isJpeg && !isPng && isBmpMagic(reader)
    val isGif = !isJpeg && !isPng && !isBmp && isGifMagic(reader)
    val isTiff = !isJpeg && !isPng && !isBmp && !isGif && isTiffMagic(reader)
    val isWebp = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && isWebpMagic(reader)
    val isWav = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && isWavMagic(reader)
    val isAvi = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && isAviMagic(reader)
    val isFlv = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && isFlvMagic(reader)
    val isAsf = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && isAsfMagic(reader)
    val isAac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && isAacMagic(reader)
    val isMp3 = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && isMp3Magic(reader)
    val isEbml = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && isEbmlMagic(reader)
    val isFlac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && isFlacMagic(reader)
    val isOgg = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && !isFlac && isOggMagic(reader)
    val isAiff = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && !isFlac && !isOgg && isAiffMagic(reader)
    val children = when {
        isJpeg -> parseJpegSegments(reader, 0, reader.length)
        isPng -> parsePngChunks(reader, 8, reader.length)
        isBmp -> parseBmpHeaders(reader, 0, reader.length)
        isGif -> parseGifBlocks(reader, 6, reader.length)
        isTiff -> decodeTiff(reader, 0, reader.length)
        isWebp -> parseWebpChunks(reader, 0, reader.length)
        isWav -> parseWavChunks(reader, 0, reader.length)
        isAvi -> parseAviChunks(reader, 0, reader.length)
        isFlv -> parseFlv(reader, 0, reader.length)
        isAsf -> parseAsf(reader, 0, reader.length)
        isAac -> parseAac(reader, 0, reader.length)
        isMp3 -> parseMp3(reader, 0, reader.length)
        isEbml -> parseEbmlElements(reader, 0, reader.length)
        isFlac -> parseFlacBlocks(reader, 0, reader.length)
        isOgg -> parseOggPages(reader, 0, reader.length)
        isAiff -> parseAiffChunks(reader, 0, reader.length)
        else -> parseBoxes(reader, 0, reader.length)
    }
    return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
}
```

Everything below this function in the file (`PNG_SIGNATURE`, `isPngMagic`, ...
`isAiffMagic`) is unchanged — only the top function is split.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.ParseFileReaderOverloadTest"`
Expected: PASS.

- [ ] **Step 5: Run the existing ParseFile-adjacent suites to confirm no regressions**

Run: `./gradlew test --tests "com.multiviewer.parser.ParseFileIntegrationTest" --tests "com.multiviewer.parser.ParseFileReaderOverloadTest"`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ParseFile.kt app/src/test/kotlin/com/multiviewer/parser/ParseFileReaderOverloadTest.kt
git commit -m "refactor: split parseFile into a reader-accepting overload

parseFile(path: File) becomes a thin wrapper delegating to the new
parseFile(path, reader: ByteReader), which carries the existing logic
unchanged. Sets up ImageCompareWindow/AppState to share one already-open
reader across parseFile + buildMediaSummary + ImageAnalyzer.analyze instead
of each opening its own (docs/superpowers/specs/2026-09-11-shared-bytereader-on-open-design.md)."
```

---

### Task 2: Split `buildMediaSummary` into a reader-accepting overload

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderReaderOverloadTest.kt` (new)

**Interfaces:**
- Consumes: `ByteReader` (Task 1's `parseFile(path, reader)` not required here — this task's test builds its own `BoxNode` fixtures directly, same as `MediaSummaryBuilderTest.kt` already does).
- Produces: `fun buildMediaSummary(root: BoxNode, file: File, reader: ByteReader): MediaSummary` — same return value as `buildMediaSummary(root: BoxNode, file: File): MediaSummary` for the same inputs.

- [ ] **Step 1: Write the failing equivalence test**

Create `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderReaderOverloadTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSummaryBuilderReaderOverloadTest {
    // A root with an EXIF ThumbnailImage node exercises buildThumbnail's real-data path
    // (not just its early return), which is exactly the path this task moves a reader
    // through -- the most important case to prove equivalent.
    private fun imageRootWithThumbnail(thumbnailBytes: ByteArray): BoxNode {
        val thumbNode = BoxNode(
            type = "ThumbnailImage", offset = 100, headerSize = 0, size = thumbnailBytes.size.toLong(),
        )
        val exif = BoxNode(type = "Exif", offset = 0, headerSize = 0, size = 100, children = listOf(thumbNode))
        val soi = BoxNode(type = "SOI", offset = 0, headerSize = 0, size = 2)
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = 100 + thumbnailBytes.size, children = listOf(soi, exif))
    }

    @Test
    fun `reader-accepting overload produces the same summary as the file-only overload for an image with a thumbnail`() {
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val root = imageRootWithThumbnail(thumbnailBytes)
        val tmp = File.createTempFile("multiviewer-reader-overload", ".jpg")
        tmp.deleteOnExit()
        // Byte 100 onward must actually be the thumbnail bytes buildThumbnail will read.
        tmp.writeBytes(ByteArray(100) + thumbnailBytes)

        val viaFileOnly = buildMediaSummary(root, tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> buildMediaSummary(root, tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }

    @Test
    fun `reader-accepting overload produces the same summary as the file-only overload for a non-image root`() {
        // A video root never touches buildMotionPhotoVideoSummary/buildThumbnail at all --
        // confirms the split didn't change behavior for the common non-image path either.
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 8, size = 16)
        val moov = BoxNode(type = "moov", offset = 16, headerSize = 8, size = 8)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 24, children = listOf(ftyp, moov))
        val tmp = File.createTempFile("multiviewer-reader-overload", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(24))

        val viaFileOnly = buildMediaSummary(root, tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> buildMediaSummary(root, tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderReaderOverloadTest"`
Expected: FAIL — `buildMediaSummary(root, tmp, reader)` is unresolved (compilation failure).

- [ ] **Step 3: Split `buildMediaSummary`, `buildMotionPhotoVideoSummary`, `buildThumbnail`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, replace the
existing `fun buildMediaSummary(root: BoxNode, file: File): MediaSummary { ... }`
(lines 15-35) with:

```kotlin
fun buildMediaSummary(root: BoxNode, file: File): MediaSummary =
    ByteReader.open(file).use { reader -> buildMediaSummary(root, file, reader) }

fun buildMediaSummary(root: BoxNode, file: File, reader: ByteReader): MediaSummary {
    val category = detectCategory(root)
    val sections = when (category) {
        MediaCategory.IMAGE -> buildImageSummary(root, file)
        MediaCategory.VIDEO -> when {
            isWebm(root) -> buildWebmVideoSummary(root, file.length())
            isAvi(root) -> buildAviVideoSummary(root, file.length())
            isFlv(root) -> buildFlvVideoSummary(root, file.length())
            isAsf(root) -> buildAsfVideoSummary(root, file.length())
            else -> buildVideoSummary(root, file.length())
        }
        MediaCategory.AUDIO -> when {
            root.children.any { it.type == "moov" } -> buildVideoSummary(root, file.length())
            isAac(root) -> buildAacSummary(root, file.length())
            else -> buildStandaloneAudioSummary(root, file.length())
        }
    }
    val motionPhotoVideoSections = if (category == MediaCategory.IMAGE) {
        buildMotionPhotoVideoSummary(root, reader)
    } else {
        null
    }
    val thumbnail = if (category == MediaCategory.IMAGE) buildThumbnail(root, reader) else null
    return MediaSummary(category, sections, motionPhotoVideoSections, thumbnail)
}
```

Then replace `buildMotionPhotoVideoSummary` and `buildThumbnail` (originally lines
39-63) with:

```kotlin
private fun buildMotionPhotoVideoSummary(root: BoxNode, reader: ByteReader): List<SummarySection>? {
    return try {
        val video = findEmbeddedVideo(root, reader) ?: return null
        val videoBoxes = parseBoxes(reader, video.start, video.end)
        val videoRoot = BoxNode(
            type = "root", offset = video.start, headerSize = 0,
            size = video.end - video.start, children = videoBoxes,
        )
        buildVideoSummary(videoRoot, video.end - video.start)
    } catch (e: Exception) {
        null
    }
}

private fun buildThumbnail(root: BoxNode, reader: ByteReader): ByteArray? {
    val thumbnailNode = findFirst(root) { it.type == "ThumbnailImage" } ?: return null
    return try {
        reader.readBytes(thumbnailNode.offset, thumbnailNode.size.toInt())
    } catch (e: Exception) {
        null
    }
}
```

(`file: File` was only ever used by these two private helpers to open their own
reader — with that removed, the parameter is unused, so it's dropped from both.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderReaderOverloadTest"`
Expected: PASS, both tests.

- [ ] **Step 5: Run the full existing MediaSummaryBuilder suite to confirm no regressions**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest" --tests "com.multiviewer.parser.MediaSummaryBuilderReaderOverloadTest"`
Expected: PASS, all tests (the existing suite has 100+ cases — this confirms the
`buildMotionPhotoVideoSummary`/`buildThumbnail` signature change didn't break any of
them).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderReaderOverloadTest.kt
git commit -m "refactor: split buildMediaSummary into a reader-accepting overload

buildMediaSummary(root, file) becomes a thin wrapper delegating to the new
buildMediaSummary(root, file, reader: ByteReader). Its two private helpers
that each opened their own reader (buildMotionPhotoVideoSummary,
buildThumbnail) now take the passed-down reader directly instead."
```

---

### Task 3: Split `ImageAnalyzer.analyze` into a reader-accepting overload

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerReaderOverloadTest.kt` (new)

**Interfaces:**
- Produces: `fun analyze(file: File, root: BoxNode, reader: ByteReader): ImageForensicData` — same return value as `analyze(file: File, root: BoxNode): ImageForensicData` for the same inputs.

- [ ] **Step 1: Write the failing equivalence test**

Create `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerReaderOverloadTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageAnalyzerReaderOverloadTest {
    @Test
    fun `reader-accepting overload produces the same forensic data as the file-only overload`() {
        // A minimal but real baseline JPEG (SOI, a DQT with a recognizable quality-estimate
        // field, EOI) -- rich enough that ImageAnalyzer.analyze does real work (quality
        // estimate, thumbnail-extraction attempt) rather than trivially returning defaults.
        val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val dqtBody = byteArrayOf(0x00) + ByteArray(64) { 8 } // table 0, all quant values = 8 (~high quality)
        val dqtLength = (dqtBody.size + 2).toShort()
        val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte()) +
            byteArrayOf((dqtLength.toInt() shr 8).toByte(), (dqtLength.toInt() and 0xFF).toByte()) +
            dqtBody
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val bytes = soi + dqt + eoi

        val tmp = File.createTempFile("multiviewer-reader-overload", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(bytes)
        val root = parseFile(tmp)

        val viaFileOnly = ImageAnalyzer.analyze(tmp, root)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> ImageAnalyzer.analyze(tmp, root, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.ImageAnalyzerReaderOverloadTest"`
Expected: FAIL — `ImageAnalyzer.analyze(tmp, root, reader)` is unresolved (compilation
failure).

- [ ] **Step 3: Split `analyze` and `tryExtractEmbeddedJpeg`**

In `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt`, change the existing
`fun analyze(file: File, root: BoxNode): ImageForensicData { ... }` (lines 20-82) so
its signature and its one call to `tryExtractEmbeddedJpeg` change, and split it into
two functions:

```kotlin
    fun analyze(file: File, root: BoxNode): ImageForensicData =
        ByteReader.open(file).use { reader -> analyze(file, root, reader) }

    fun analyze(file: File, root: BoxNode, reader: ByteReader): ImageForensicData {
        println("File Structure Trace: ${file.name}")
        traceNodes(root, 0)

        val thumbnailResult = tryExtractEmbeddedJpeg(reader, root)

        var quality = 0
        var isModified = false
        var software: String? = null
        var orientationCode: Int? = null
        var thumbOrientationCode: Int? = null

        fun traverse(node: BoxNode) {
            if (node.type == "QuantizationTable") {
                val qStr = node.fields.find { it.name == "quality_estimate" }?.value
                if (qStr != null) quality = qStr.removePrefix("~").removeSuffix("%").toIntOrNull() ?: quality
            }
            if (node.type == "IFD0" || node.type == "Exif") {
                software = node.fields.find { it.name == "Software" }?.value ?: software
                val orientVal = node.fields.find { it.name == "Orientation" }?.value
                if (orientVal != null) {
                    orientationCode = parseOrientationCode(orientVal) ?: orientationCode
                }
            }
            if (node.type == "IFD1") {
                val orientVal = node.fields.find { it.name == "Orientation" }?.value
                if (orientVal != null) {
                    thumbOrientationCode = parseOrientationCode(orientVal) ?: thumbOrientationCode
                }
            }
            node.children.forEach { traverse(it) }
        }
        traverse(root)

        if (software?.contains("Photoshop", ignoreCase = true) == true ||
            software?.contains("Adobe", ignoreCase = true) == true) isModified = true

        val heifOrientation = extractHeifOrientation(root)
        val heifCode = heifOrientationToCode(root)
        val primaryCode = orientationCode ?: heifCode
        val thumbCode = thumbOrientationCode ?: primaryCode

        val thumbBitmap = thumbnailResult.image?.let { img ->
            orientSkiaImage(img, thumbCode).toComposeImageBitmap()
        }

        val primaryOrientation = orientationCode?.let { "${orientationLabel(it)} ($it)" } ?: heifOrientation
        val thumbnailOrientation = thumbOrientationCode?.let { "${orientationLabel(it)} ($it)" } ?: primaryOrientation

        return ImageForensicData(
            bitmap = null,
            embeddedThumbnail = thumbBitmap,
            histogram = null,
            dqtQuality = quality,
            software = software,
            isModified = isModified,
            orientation = primaryOrientation,
            orientationCode = primaryCode,
            thumbnailOrientation = thumbnailOrientation,
            thumbnailOrientationCode = thumbCode,
            hasThumbnailReference = thumbnailResult.hasThumbnailReference,
        )
    }
```

(Only the signature line, the added 2-argument delegating wrapper above it, and the
`tryExtractEmbeddedJpeg(reader, root)` call changed from
`tryExtractEmbeddedJpeg(file, root)` — everything else in the function body is
unchanged.)

Then replace `tryExtractEmbeddedJpeg`'s signature line (originally
`private fun tryExtractEmbeddedJpeg(file: File, root: BoxNode): ThumbnailExtractionResult {`)
and its body's opening, so the function becomes:

```kotlin
    private fun tryExtractEmbeddedJpeg(reader: ByteReader, root: BoxNode): ThumbnailExtractionResult {
        val meta = findFirst(root) { it.type == "meta" }
        val iloc = if (meta != null) findFirst(meta) { it.type == "iloc" } else null
        val iinf = if (meta != null) findFirst(meta) { it.type == "iinf" } else null
        val iref = if (meta != null) findFirst(meta) { it.type == "iref" } else null
        val pitm = if (meta != null) findFirst(meta) { it.type == "pitm" } else null
        val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()

        val thumbIds = mutableSetOf<Long>()
        if (primaryId != null && iref != null) {
            for (ref in iref.children) {
                if (ref.type == "thmb") {
                    val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
                    val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
                    if (toIds.contains(primaryId) && fromId != null) thumbIds.add(fromId)
                }
            }
        }
        val hasThumbnailReference = thumbIds.isNotEmpty()

        val image = run {
            if (iloc != null && thumbIds.isNotEmpty()) {
                val idat = findFirst(root) { it.type == "idat" }
                val idatBase = if (idat != null) idat.offset + idat.headerSize else 0L

                for (id in thumbIds) {
                    val img = extractItemById(reader, iloc, id, idatBase)
                    if (img != null) return@run img
                }
            }

            val thumbNode = findFirst(root) { it.type == "ThumbnailImage" }
            if (thumbNode != null && thumbNode.size in 64..2_000_000) {
                try {
                    val possibleImg = Image.makeFromEncoded(reader.readBytes(thumbNode.offset, thumbNode.size.toInt()))
                    if (possibleImg.width > 10) return@run possibleImg
                } catch (e: Exception) {}
            }

            val exifNode = findFirst(root) { it.type == "Exif" }
            if (exifNode != null) {
                val limit = exifNode.offset + exifNode.size
                for (scanPos in findJpegMagicOffsets(reader, exifNode.offset, limit)) {
                    try {
                        val possibleImg = Image.makeFromEncoded(reader.readBytes(scanPos, (limit - scanPos).toInt().coerceAtMost(1_000_000)))
                        if (possibleImg.width > 10) return@run possibleImg
                    } catch (e: Exception) {}
                }
            }

            null
        }

        return ThumbnailExtractionResult(image, hasThumbnailReference)
    }
```

This is the same logic as before, with `ByteReader.open(file).use { reader -> ... }`
removed (the block's `return@use` become `return@run`, since the surrounding scope
function changed from `use` to a plain `run`) and `file` dropped from the parameter
list (it was only ever used to open that reader).

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.ImageAnalyzerReaderOverloadTest"`
Expected: PASS.

- [ ] **Step 5: Run the full existing ImageAnalyzer suite to confirm no regressions**

Run: `./gradlew test --tests "com.multiviewer.parser.ImageAnalyzerTest" --tests "com.multiviewer.parser.ImageAnalyzerReaderOverloadTest"`
Expected: PASS, all tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerReaderOverloadTest.kt
git commit -m "refactor: split ImageAnalyzer.analyze into a reader-accepting overload

analyze(file, root) becomes a thin wrapper delegating to the new
analyze(file, root, reader: ByteReader). tryExtractEmbeddedJpeg now takes
the passed-down reader directly instead of opening its own."
```

---

### Task 4: Wire the shared reader into the real open paths + verify

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ByteReaderOpenCountTest.kt` (new)

**Interfaces:**
- Consumes: `parseFile(path, reader)` (Task 1), `buildMediaSummary(root, file, reader)` (Task 2), `ImageAnalyzer.analyze(file, root, reader)` (Task 3).
- Produces: `internal var ByteReader.openCallCount: Int` (test-observability counter, not read by any production code).

- [ ] **Step 1: Add the open-call counter to `ByteReader`**

In `app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt`, replace the companion
object:

```kotlin
    companion object {
        fun open(file: File): ByteReader = ByteReader(RandomAccessFile(file, "r"))
    }
```

with:

```kotlin
    companion object {
        // Test-only observability: counts real ByteReader.open() calls so tests can assert on
        // file-open counts (e.g. "opening one file for the compare window opens it once, not
        // four times") without instrumenting every call site. No production code reads this.
        internal var openCallCount: Int = 0

        fun open(file: File): ByteReader {
            openCallCount++
            return ByteReader(RandomAccessFile(file, "r"))
        }
    }
```

- [ ] **Step 2: Write the failing open-count test**

Create `app/src/test/kotlin/com/multiviewer/parser/ByteReaderOpenCountTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteReaderOpenCountTest {
    // Same fixture shape as MediaSummaryBuilderReaderOverloadTest's thumbnail case -- rich
    // enough to exercise buildMotionPhotoVideoSummary's real path (not just its early return)
    // and buildThumbnail's real read, matching the shape a real camera JPEG's IFD1 thumbnail
    // takes through this pipeline.
    private fun imageRootWithThumbnail(thumbnailBytes: ByteArray): BoxNode {
        val thumbNode = BoxNode(type = "ThumbnailImage", offset = 100, headerSize = 0, size = thumbnailBytes.size.toLong())
        val exif = BoxNode(type = "Exif", offset = 0, headerSize = 0, size = 100, children = listOf(thumbNode))
        val soi = BoxNode(type = "SOI", offset = 0, headerSize = 0, size = 2)
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = 100 + thumbnailBytes.size, children = listOf(soi, exif))
    }

    @Test
    fun `parseFile, buildMediaSummary, and analyze share one reader when the caller opens one`() {
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val tmp = File.createTempFile("multiviewer-open-count", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(2) + ByteArray(98) + thumbnailBytes) // room for the fixture's offsets

        val root = imageRootWithThumbnail(thumbnailBytes)

        val before = ByteReader.openCallCount
        ByteReader.open(tmp).use { reader ->
            parseFile(tmp, reader)
            val summary = buildMediaSummary(root, tmp, reader)
            ImageAnalyzer.analyze(tmp, root, reader)
            summary
        }
        val opensForSharedPath = ByteReader.openCallCount - before

        assertEquals(1, opensForSharedPath, "expected exactly one ByteReader.open() for the whole shared-reader sequence")
    }

    @Test
    fun `the same sequence via the file-only overloads still opens four times`() {
        // Documents the baseline this task improves on -- if this ever drops below 4 on its
        // own, the file-only overloads changed in a way this test should catch.
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val tmp = File.createTempFile("multiviewer-open-count", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(2) + ByteArray(98) + thumbnailBytes)

        val root = imageRootWithThumbnail(thumbnailBytes)

        val before = ByteReader.openCallCount
        val fileOnlyRoot = parseFile(tmp)
        buildMediaSummary(fileOnlyRoot, tmp)
        ImageAnalyzer.analyze(tmp, fileOnlyRoot)
        val opensForFileOnlyPath = ByteReader.openCallCount - before

        assertEquals(4, opensForFileOnlyPath)
    }
}
```

- [ ] **Step 3: Run the tests to verify the first one fails**

Run: `./gradlew test --tests "com.multiviewer.parser.ByteReaderOpenCountTest"`
Expected: the second test (`...still opens four times`) PASSes already (Tasks 1-3
only *added* overloads, they didn't change the file-only ones' behavior). The first
test (`...share one reader...`) also currently passes at this point, since Tasks 1-3
already made the reader-accepting overloads truly share whatever reader they're
given — this test is really confirming Tasks 1-3's plumbing is correct end-to-end,
not gated on Task 4's own changes. Confirm both PASS before moving on; if the first
one fails, that means an earlier task's overload is silently opening its own reader
somewhere instead of using the one passed in — stop and investigate before
continuing, since Task 4's wiring below would just be hiding that bug.

- [ ] **Step 4: Wire `ImageCompareWindow.loadInfo`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, replace the
`compareExecutor.execute { ... }` block (the fresh-load branch inside `loadInfo`,
currently starting `compareExecutor.execute {` and ending at its matching closing
`}`) with:

```kotlin
        compareExecutor.execute {
            try {
                ByteReader.open(file).use { reader ->
                    val root = parseFile(file, reader)
                    val summary = buildMediaSummary(root, file, reader)
                    val isVid = summary.category == MediaCategory.VIDEO || isVideoExtension(file)
                    val dur = extractVideoDuration(root, summary)

                    if (isVid) {
                        FrameFullSizeDecoder.decodeFrameAsync(file, 0.0) { firstFrame ->
                            EventQueue.invokeLater {
                                onLoaded(
                                    CompareMediaInfo(
                                        file = file,
                                        root = root,
                                        forensic = null,
                                        bitmap = firstFrame,
                                        summary = summary,
                                        fileSize = file.length(),
                                        isVideo = true,
                                        durationSeconds = dur,
                                        isLoading = false,
                                    )
                                )
                            }
                        }
                    } else {
                        val forensic = ImageAnalyzer.analyze(file, root, reader)
                        val (decodedBitmap, _) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)

                        if (decodedBitmap != null) {
                            EventQueue.invokeLater {
                                onLoaded(
                                    CompareMediaInfo(
                                        file = file,
                                        root = root,
                                        forensic = forensic.copy(bitmap = decodedBitmap),
                                        bitmap = decodedBitmap,
                                        summary = summary,
                                        fileSize = file.length(),
                                        isVideo = false,
                                        durationSeconds = 0.0,
                                        isLoading = false,
                                    )
                                )
                            }
                        } else {
                            FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { fallbackBitmap ->
                                EventQueue.invokeLater {
                                    onLoaded(
                                        CompareMediaInfo(
                                            file = file,
                                            root = root,
                                            forensic = forensic.copy(bitmap = fallbackBitmap),
                                            bitmap = fallbackBitmap,
                                            summary = summary,
                                            fileSize = file.length(),
                                            isVideo = false,
                                            durationSeconds = 0.0,
                                            isLoading = false,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                EventQueue.invokeLater {
                    onLoaded(
                        CompareMediaInfo(
                            file = file,
                            root = null,
                            forensic = null,
                            bitmap = null,
                            summary = null,
                            fileSize = file.length(),
                            isLoading = false,
                            error = e.message ?: e.toString(),
                        )
                    )
                }
            }
        }
```

(Everything inside is identical to before except: the whole body is now inside
`ByteReader.open(file).use { reader -> ... }`, and the two calls that used to be
`parseFile(file)` / `buildMediaSummary(root, file)` / `ImageAnalyzer.analyze(file, root)`
now pass `reader` as an extra argument. `decodePrimaryBitmapAndHistogram` and the
`FfmpegImageSnapshotDecoder`/`FrameFullSizeDecoder` async calls are untouched -- they
don't use `ByteReader` at all.)

- [ ] **Step 5: Wire `AppState.openFile`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, replace the span from
`val root = parseFile(file)` through `val finalImageForensic = imageForensic`
(originally lines 619-679, inclusive of the early `return@Thread` and everything in
between) with:

```kotlin
                val (root, mediaSummary, resolution, hardBlockMessage, embeddedVideo, motionPhotoPreview, gainmapInfo, finalImageForensic, type) = ByteReader.open(file).use { reader ->
                    val root = parseFile(file, reader)

                    val type = when {
                        extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
                        extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
                        extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
                        else -> MediaType.UNKNOWN
                    }

                    val mediaSummary = try {
                        buildMediaSummary(root, file, reader)
                    } catch (e: Exception) {
                        null
                    }

                    val resolution = extractResolution(mediaSummary)
                    val hardBlockMessage = resolution?.let { (w, h) -> hardResolutionRejectionMessage(w, h) }

                    if (hardBlockMessage != null) {
                        return@use OpenFileParseResult(root, mediaSummary, resolution, hardBlockMessage, null, null, null, null, type)
                    }

                    val embeddedVideo = try {
                        ByteReader.open(file).use { embedReader -> findEmbeddedVideo(root, embedReader) }
                    } catch (e: Exception) {
                        null
                    }
                    val motionPhotoPreview = try {
                        findMotionPhotoPreview(root)
                    } catch (e: Exception) {
                        null
                    }

                    val gainmapInfo = try {
                        com.multiviewer.parser.GainmapParser.findGainmapInfo(file, root)
                    } catch (e: Exception) {
                        null
                    }

                    var imageForensic: ImageForensicData? = null
                    when (type) {
                        MediaType.IMAGE -> imageForensic = ImageAnalyzer.analyze(file, root, reader)
                        MediaType.VIDEO -> {
                            // Attempt to extract thumbnail for video files too
                            imageForensic = ImageAnalyzer.analyze(file, root, reader)
                        }
                        else -> {}
                    }
                    OpenFileParseResult(root, mediaSummary, resolution, hardBlockMessage, embeddedVideo, motionPhotoPreview, gainmapInfo, imageForensic, type)
                }

                if (hardBlockMessage != null) {
                    EventQueue.invokeLater {
                        val idx = tabs.indexOf(tab)
                        if (idx >= 0) tabs.removeAt(idx)
                        if (selectedTabIndex >= tabs.size) selectedTabIndex = (tabs.size - 1).coerceAtLeast(0)
                        openFileError = hardBlockMessage
                    }
                    return@Thread
                }

                val warning = resolution?.let { (w, h) -> resolutionWarningMessage(w, h, type == MediaType.VIDEO) }
```

This introduces one small private data holder — `ByteReader.open(file).use { }`
cannot itself do a labeled non-local `return@Thread` and still guarantee the reader
closes in every Kotlin version the way a plain early return inside the lambda can
(the original code relied on `return@Thread` reaching all the way out through the
`try` and the enclosing `Thread { }`; nesting it one level deeper inside `.use { }`
is still safe for `close()`, but replacing the early `return@Thread` **inside** the
lambda with a `return@use` that carries the block-or-early-block distinction out via
a named result type keeps the control flow explicit and easy to review, rather than
relying on a non-local return crossing two lambda boundaries). Add this small data
class right above `class AppState` in the same file. Field types are the verified,
actual return types of the functions that produce them (both `findEmbeddedVideo` and
`findMotionPhotoPreview`, in `MotionPhotoExtractor.kt`, return `EmbeddedVideo?`;
`GainmapParser.findGainmapInfo`, in `GainmapParser.kt:67`, returns `GainmapInfo?`;
`extractResolution`, in this same file at line 63, returns `Pair<Int, Int>?`):

```kotlin
private data class OpenFileParseResult(
    val root: BoxNode,
    val mediaSummary: MediaSummary?,
    val resolution: Pair<Int, Int>?,
    val hardBlockMessage: String?,
    val embeddedVideo: EmbeddedVideo?,
    val motionPhotoPreview: EmbeddedVideo?,
    val gainmapInfo: GainmapInfo?,
    val imageForensic: ImageForensicData?,
    val type: MediaType,
)
```

- [ ] **Step 6: Run the open-count test again plus the affected suites**

Run: `./gradlew test --tests "com.multiviewer.parser.ByteReaderOpenCountTest" --tests "com.multiviewer.parser.ParseFileReaderOverloadTest" --tests "com.multiviewer.parser.MediaSummaryBuilderReaderOverloadTest" --tests "com.multiviewer.parser.ImageAnalyzerReaderOverloadTest"`
Expected: PASS, all tests.

- [ ] **Step 7: Run the full suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, same total test count as before this plan plus the 6 new
tests added across Tasks 1-4 (1 + 2 + 1 + 2 = 6), zero failures.

- [ ] **Step 8: Compile the whole app**

Run: `./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ByteReader.kt app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/test/kotlin/com/multiviewer/parser/ByteReaderOpenCountTest.kt
git commit -m "perf: share one ByteReader across parseFile/buildMediaSummary/analyze on open

ImageCompareWindow.loadInfo and AppState.openFile now open one ByteReader
and pass it through parseFile, buildMediaSummary, and ImageAnalyzer.analyze
instead of each opening its own -- 4 file opens down to 1 per file. Every
existing two-argument call site (130+ tests, the CLI) is unaffected.

See docs/superpowers/specs/2026-09-11-shared-bytereader-on-open-design.md"
```

- [ ] **Step 10: Manual verification (tell the user)**

Automated coverage stops at the equivalence/open-count tests and the full suite —
confirming the actual felt difference (or lack of one, on macOS where file opens are
already cheap) needs the user to run the app. Ask the user to:
1. Run the app, open a plain image in the main window — confirms `AppState.openFile`'s
   rewired span still works end-to-end.
2. Open the Media Comparison Analyzer and load two images (the originally reported
   case) — confirms `ImageCompareWindow.loadInfo`'s rewired span still works, and
   ideally feels the same or faster.
3. Open a video file and an audio file in the main window — confirms the non-image
   branches of both rewired spans (which skip `ImageAnalyzer.analyze`/thumbnail
   extraction differently) still work.
4. If a real motion-photo JPEG or gain-map HDR photo is available, open it too —
   these exercise `buildMotionPhotoVideoSummary`'s real (non-early-return) path and
   the untouched `GainmapParser`/`embeddedVideo` calls respectively.
5. If reproducible on the Windows machine that reported the original slowness,
   compare the felt open time for two 12MP JPEGs before/after this change.
