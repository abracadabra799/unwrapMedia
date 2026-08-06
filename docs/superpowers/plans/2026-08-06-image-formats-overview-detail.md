# Image Formats Overview Detail Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JPEGsnoop-depth Overview sections for PNG, BMP, GIF, WebP, TIFF/camera-RAW, and HEIC/AVIF — the second Overview-depth sub-project after JPEG (`docs/superpowers/plans/2026-08-06-jpeg-overview-detail.md`).

**Architecture:** One `build<Format>Detail(root: BoxNode): SummarySection?` function per format in `MediaSummaryBuilder.kt`, called unconditionally from `buildImageSummary`, each internally detecting its format and returning `null` otherwise (exact same pattern as the existing `buildJpegDetail`). Two formats need new parser-level work first: GIF's Comment Extension (currently discards its text) and HEIC/AVIF's `irot`/`imir`/`pixi`/`auxC` item properties (currently unregistered, parse as empty leaf nodes).

**Tech Stack:** Kotlin, existing `BoxNode`/`BoxField`/`ByteReader`/`BoxDecoder`/`BoxRegistry` parser primitives, `kotlin.test` for unit tests.

## Global Constraints

- Reference design doc: `docs/superpowers/specs/2026-08-06-image-formats-overview-detail-design.md`.
- Section titles are exactly: `"PNG Detail"`, `"BMP Detail"`, `"GIF Detail"`, `"WebP Detail"`, `"TIFF Detail"`, `"HEIC/AVIF Detail"`.
- Every field within every section is independently optional — a missing prerequisite omits only that field, never the whole section or other fields. Each `build<Format>Detail` returns `null` when its resulting field list is empty.
- No changes to the Detailed Properties (tree) tab or any UI/Compose file — the Overview tab already renders arbitrary `SummarySection` lists generically.
- `pixi` and `auxC` are ISOBMFF FullBoxes (4-byte version+flags prefix before their payload, same convention the existing `IspeBoxDecoder` already uses via `payloadStart + 4`); `irot` and `imir` are plain Boxes (no such prefix).
- GIF comment text uses `Charsets.ISO_8859_1`, matching `PngWalker.kt`'s existing `tEXt` decoding convention.
- WebP VP8X flag bit masks (from the WebP container spec / libwebp's `mux_types.h`): `ICCP_FLAG = 0x20`, `ALPHA_FLAG = 0x10`, `ANIM_FLAG = 0x02`.
- HEIF `imir` axis labels are translated to image-editing terminology per explicit user decision: `axis=0` (HEIF "vertical axis") → `"Horizontal Flip (좌우반전)"`; `axis=1` (HEIF "horizontal axis") → `"Vertical Flip (상하반전)"`.

---

### Task 1: PNG Detail

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (imports at line 1-4, `buildImageSummary` at line 118-122, new function inserted after `buildJpegDetail` ends at line 429)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (append before the final closing `}`)

**Interfaces:**
- Consumes: `PngWalker.kt`'s existing `IHDR` fields (`bit_depth`, `compression_method`, `interlace_method`), existing `pHYs` fields (`pixels_per_unit_x`, `pixels_per_unit_y`, `unit_specifier`), existing `tEXt` fields (`keyword`, `text`) — all pre-existing, no parser changes.
- Produces: `private fun buildPngDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `a PNG with full IHDR fields, pHYs, and tEXt chunks reports PNG Detail`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "640", 0, 4),
                BoxField("height", "480", 0, 4),
                BoxField("bit_depth", "8", 0, 1),
                BoxField("color_type", "6", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "1", 0, 1),
            ),
        )
        val phys = BoxNode(
            type = "pHYs", offset = 0, headerSize = 8, size = 21,
            fields = listOf(
                BoxField("pixels_per_unit_x", "2835", 0, 4),
                BoxField("pixels_per_unit_y", "2835", 0, 4),
                BoxField("unit_specifier", "meter", 0, 1),
            ),
        )
        val text = BoxNode(
            type = "tEXt", offset = 0, headerSize = 8, size = 20,
            fields = listOf(BoxField("keyword", "Software", 0, 8), BoxField("text", "GIMP", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, phys, text))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("8-bit", pngDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals("Deflate/Inflate", pngDetail.fields.first { it.label == "Compression Method" }.value)
        assertEquals("Adam7", pngDetail.fields.first { it.label == "Interlace" }.value)
        assertEquals("72 DPI", pngDetail.fields.first { it.label == "Pixel Density" }.value)
        assertEquals("GIMP", pngDetail.fields.first { it.label == "Software" }.value)
    }

    @Test
    fun `PNG pHYs with an unknown unit specifier reports raw pixels-per-unit instead of a DPI conversion`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "640", 0, 4),
                BoxField("height", "480", 0, 4),
                BoxField("bit_depth", "8", 0, 1),
                BoxField("color_type", "6", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "0", 0, 1),
            ),
        )
        val phys = BoxNode(
            type = "pHYs", offset = 0, headerSize = 8, size = 21,
            fields = listOf(
                BoxField("pixels_per_unit_x", "1", 0, 4),
                BoxField("pixels_per_unit_y", "1", 0, 4),
                BoxField("unit_specifier", "unknown", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr, phys))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("1 x 1 px/unit", pngDetail.fields.first { it.label == "Pixel Density" }.value)
        assertEquals("None", pngDetail.fields.first { it.label == "Interlace" }.value)
    }

    @Test
    fun `a minimal PNG with no pHYs or tEXt still reports Bit Depth, Compression Method, and Interlace`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 8, size = 25,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "100", 0, 4),
                BoxField("bit_depth", "1", 0, 1),
                BoxField("color_type", "0", 0, 1),
                BoxField("compression_method", "0", 0, 1),
                BoxField("filter_method", "0", 0, 1),
                BoxField("interlace_method", "0", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        val pngDetail = summary.sections.first { it.title == "PNG Detail" }
        assertEquals("1-bit", pngDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals(null, pngDetail.fields.find { it.label == "Pixel Density" })
        assertEquals(3, pngDetail.fields.size)
    }

    @Test
    fun `a non-PNG image (BMP) has no PNG Detail section`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "PNG Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — `summary.sections.first { it.title == "PNG Detail" }` throws `NoSuchElementException` in the first 3 new tests (no such section exists yet). The 4th test passes already (nothing to remove) — that's fine, it locks in the non-regression guarantee going forward.

- [ ] **Step 3: Add the import and `buildPngDetail`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, change the imports at the top:

```kotlin
import java.io.File
import kotlin.math.abs
```
to:
```kotlin
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
```

Then insert this function right after `buildJpegDetail`'s closing `}` (currently ends at line 429, right before `private val WEBM_CODEC_DISPLAY_NAMES` at line 431):

```kotlin
private val PNG_INTERLACE_NAMES = mapOf(0 to "None", 1 to "Adam7")

private fun buildPngDetail(root: BoxNode): SummarySection? {
    val ihdr = root.children.find { it.type == "IHDR" } ?: return null
    val fields = mutableListOf<SummaryField>()

    ihdr.fields.find { it.name == "bit_depth" }?.let { fields.add(SummaryField("Bit Depth", "${it.value}-bit")) }
    ihdr.fields.find { it.name == "compression_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Compression Method", if (method == 0) "Deflate/Inflate" else method.toString()))
    }
    ihdr.fields.find { it.name == "interlace_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Interlace", PNG_INTERLACE_NAMES[method] ?: method.toString()))
    }

    val phys = root.children.find { it.type == "pHYs" }
    val ppuX = phys?.fields?.find { it.name == "pixels_per_unit_x" }?.value?.toDoubleOrNull()
    val ppuY = phys?.fields?.find { it.name == "pixels_per_unit_y" }?.value?.toDoubleOrNull()
    val unitSpecifier = phys?.fields?.find { it.name == "unit_specifier" }?.value
    if (ppuX != null && ppuY != null) {
        if (unitSpecifier == "meter") {
            val dpiX = (ppuX * 0.0254).roundToInt()
            val dpiY = (ppuY * 0.0254).roundToInt()
            fields.add(SummaryField("Pixel Density", if (dpiX == dpiY) "$dpiX DPI" else "$dpiX x $dpiY DPI"))
        } else {
            fields.add(SummaryField("Pixel Density", "${ppuX.toInt()} x ${ppuY.toInt()} px/unit"))
        }
    }

    root.children.filter { it.type == "tEXt" }.forEach { chunk ->
        val keyword = chunk.fields.find { it.name == "keyword" }?.value
        val text = chunk.fields.find { it.name == "text" }?.value
        if (keyword != null && text != null) fields.add(SummaryField(keyword, text))
    }

    return if (fields.isNotEmpty()) SummarySection("PNG Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

Find (line 118-122):
```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
    buildJpegDetail(root)?.let { sections.add(it) }
```
Change to:
```kotlin
private fun buildImageSummary(root: BoxNode, file: File): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    sections.add(buildImageGeneral(root, file))
    buildImageDetail(root)?.let { sections.add(it) }
    buildJpegDetail(root)?.let { sections.add(it) }
    buildPngDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 4 new tests plus all pre-existing cases (the existing PNG-shaped tests don't assert exact `sections.size`, so they're unaffected).

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add PNG Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 2: BMP Detail

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildImageSummary`, new function after `buildPngDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `BmpWalker.kt`'s existing `BITMAPINFOHEADER` fields (`bit_count`, `compression`) — pre-existing, no parser changes.
- Produces: `private fun buildBmpDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `a BMP with bit_count and compression fields reports BMP Detail`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 14)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 40,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "50", 0, 4),
                BoxField("bit_count", "24", 0, 2),
                BoxField("compression", "0", 0, 4),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        val bmpDetail = summary.sections.first { it.title == "BMP Detail" }
        assertEquals("24-bit", bmpDetail.fields.first { it.label == "Bit Count" }.value)
        assertEquals("None (BI_RGB)", bmpDetail.fields.first { it.label == "Compression" }.value)
    }

    @Test
    fun `an RLE8-compressed BMP labels Compression as RLE 8-bit (BI_RLE8)`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 14)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 40,
            fields = listOf(
                BoxField("width", "100", 0, 4),
                BoxField("height", "50", 0, 4),
                BoxField("bit_count", "8", 0, 2),
                BoxField("compression", "1", 0, 4),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        val bmpDetail = summary.sections.first { it.title == "BMP Detail" }
        assertEquals("RLE 8-bit (BI_RLE8)", bmpDetail.fields.first { it.label == "Compression" }.value)
    }

    @Test
    fun `a BMP with no bit_count or compression fields has no BMP Detail section`() {
        val fileHeader = BoxNode(type = "BITMAPFILEHEADER", offset = 0, headerSize = 0, size = 0)
        val infoHeader = BoxNode(
            type = "BITMAPINFOHEADER", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "100", 0, 4), BoxField("height", "-50", 0, 4)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(fileHeader, infoHeader))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "BMP Detail" })
    }

    @Test
    fun `a non-BMP image (GIF) has no BMP Detail section`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "BMP Detail" })
    }
```

Note: this task's fixture in the *third* test above is byte-for-byte the same shape as the pre-existing `` `a BMP-shaped tree produces Resolution and Format BMP, with no Color Space or Camera Info sections` `` test elsewhere in this file (also 2-section-total). That pre-existing test's fixture has no `bit_count`/`compression` fields either, so `buildBmpDetail` returns `null` for it and its `assertEquals(2, summary.sections.size)` is **not** affected — confirm this stays true in Step 5's full-suite run rather than assuming it.

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first 2 new tests throw `NoSuchElementException` (no "BMP Detail" section exists yet). The last 2 pass already.

- [ ] **Step 3: Add `buildBmpDetail`**

Insert right after `buildPngDetail`'s closing `}` (added in Task 1):

```kotlin
private val BMP_COMPRESSION_NAMES = mapOf(
    0 to "None (BI_RGB)",
    1 to "RLE 8-bit (BI_RLE8)",
    2 to "RLE 4-bit (BI_RLE4)",
    3 to "Bit Fields (BI_BITFIELDS)",
    4 to "JPEG (BI_JPEG)",
    5 to "PNG (BI_PNG)",
)

private fun buildBmpDetail(root: BoxNode): SummarySection? {
    val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" } ?: return null
    val fields = mutableListOf<SummaryField>()

    infoHeader.fields.find { it.name == "bit_count" }?.let { fields.add(SummaryField("Bit Count", "${it.value}-bit")) }
    infoHeader.fields.find { it.name == "compression" }?.value?.toIntOrNull()?.let { compression ->
        fields.add(SummaryField("Compression", BMP_COMPRESSION_NAMES[compression] ?: compression.toString()))
    }

    return if (fields.isNotEmpty()) SummarySection("BMP Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

```kotlin
    buildJpegDetail(root)?.let { sections.add(it) }
    buildPngDetail(root)?.let { sections.add(it) }
    buildBmpDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 4 new tests plus all pre-existing cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add BMP Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 3: GIF Comment Extension text parsing

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt` (`decodeExtension` at line 69-76, new function added after `decodeGenericSubBlockExtension`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt` (modify the existing Comment Extension test, add one more)

**Interfaces:**
- Produces: `CommentExtension`-type `BoxNode`s now carry a `BoxField("comment", <decoded text>, ...)` field when the extension has non-empty sub-block data (previously always field-less). Task 4 consumes this `comment` field by name.

- [ ] **Step 1: Update the existing test and add a new one**

In `app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt`, find:

```kotlin
    @Test
    fun `a Comment Extension shows as a generic, field-less node`() {
        val commentData = subBlock("hello".toByteArray(Charsets.US_ASCII)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFE.toByte()) + commentData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-comment").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val commentNode = nodes.first { it.type == "CommentExtension" }
            assertTrue(commentNode.fields.isEmpty())
        }
    }
```

Replace with (renamed to match the new behavior, and one new test added right after it):

```kotlin
    @Test
    fun `a Comment Extension decodes its text into a comment field`() {
        val commentData = subBlock("hello".toByteArray(Charsets.US_ASCII)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFE.toByte()) + commentData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-comment").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val commentNode = nodes.first { it.type == "CommentExtension" }
            assertEquals("hello", commentNode.fields.first { it.name == "comment" }.value)
        }
    }

    @Test
    fun `a Comment Extension spanning multiple sub-blocks concatenates them into one comment field`() {
        val commentData = subBlock("hello ".toByteArray(Charsets.US_ASCII)) + subBlock("world".toByteArray(Charsets.US_ASCII)) + SUB_BLOCK_TERMINATOR
        val bytes = logicalScreenDescriptor(width = 4, height = 4, globalColorTableFlag = false, globalColorTableSize = 0) +
            byteArrayOf(0x21, 0xFE.toByte()) + commentData +
            byteArrayOf(0x3B)
        readerOver(bytes, "gif-walker-comment-multi").use { reader ->
            val nodes = parseGifBlocks(reader, 0, bytes.size.toLong())
            val commentNode = nodes.first { it.type == "CommentExtension" }
            assertEquals("hello world", commentNode.fields.first { it.name == "comment" }.value)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.GifWalkerTest"
```
Expected: FAIL — both tests throw `NoSuchElementException` (`CommentExtension` nodes have no fields yet).

- [ ] **Step 3: Add `decodeCommentExtension` and wire it in**

In `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`, find:

```kotlin
private fun decodeExtension(reader: ByteReader, label: Int, offset: Long, end: Long): Pair<BoxNode, Long>? =
    when (label) {
        GRAPHIC_CONTROL_LABEL -> decodeGraphicControlExtension(reader, offset, end)
        APPLICATION_LABEL -> decodeApplicationExtension(reader, offset, end)
        COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)
        PLAIN_TEXT_LABEL -> decodeGenericSubBlockExtension(reader, "PlainTextExtension", offset, end)
        else -> decodeGenericSubBlockExtension(reader, "Extension_0x${label.toString(16).padStart(2, '0').uppercase()}", offset, end)
    }
```

Change the `COMMENT_LABEL` line to:
```kotlin
        COMMENT_LABEL -> decodeCommentExtension(reader, offset, end)
```

Then add this new function right after `decodeGenericSubBlockExtension`'s closing `}`:

```kotlin
private fun decodeCommentExtension(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (blocks, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    val text = blocks.joinToString("") { String(it, Charsets.ISO_8859_1) }
    val fields = if (text.isNotEmpty()) listOf(BoxField("comment", text, offset + 2, nextPos - (offset + 2))) else emptyList()
    return BoxNode(type = "CommentExtension", offset = offset, headerSize = 2, size = nextPos - offset, fields = fields) to nextPos
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.GifWalkerTest"
```
Expected: PASS, all cases including the 2 above.

- [ ] **Step 5: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt app/src/test/kotlin/com/multiviewer/parser/GifWalkerTest.kt
git commit -m "Decode GIF Comment Extension text into a comment field"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 4: GIF Detail Overview section

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildImageSummary`, new function after `buildBmpDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `GifWalker.kt`'s existing `LogicalScreenDescriptor` fields (`color_resolution`, `global_color_table_flag`, `global_color_table_size`), existing `GraphicControlExtension` fields (`disposal_method`, `delay_time`), and Task 3's new `CommentExtension` `comment` field.
- Produces: `private fun buildGifDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `a GIF with color resolution, a global color table, disposal, delay, and a comment reports GIF Detail`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 7,
            fields = listOf(
                BoxField("width", "320", 0, 2),
                BoxField("height", "240", 0, 2),
                BoxField("global_color_table_flag", "1", 0, 1),
                BoxField("color_resolution", "7", 0, 1),
                BoxField("global_color_table_size", "7", 0, 1),
            ),
        )
        val gce = BoxNode(
            type = "GraphicControlExtension", offset = 0, headerSize = 2, size = 8,
            fields = listOf(
                BoxField("disposal_method", "2", 0, 1),
                BoxField("delay_time", "50", 0, 2),
            ),
        )
        val comment = BoxNode(
            type = "CommentExtension", offset = 0, headerSize = 2, size = 10,
            fields = listOf(BoxField("comment", "Created with GIMP", 0, 18)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd, gce, comment))

        val summary = buildMediaSummary(root, tempFile())

        val gifDetail = summary.sections.first { it.title == "GIF Detail" }
        assertEquals("8-bit", gifDetail.fields.first { it.label == "Color Resolution" }.value)
        assertEquals("Yes (256 colors)", gifDetail.fields.first { it.label == "Global Color Table" }.value)
        assertEquals("Restore to Background", gifDetail.fields.first { it.label == "Disposal Method" }.value)
        assertEquals("500 ms", gifDetail.fields.first { it.label == "Frame Delay" }.value)
        assertEquals("Created with GIMP", gifDetail.fields.first { it.label == "Comment" }.value)
    }

    @Test
    fun `a GIF with no global color table omits the Global Color Table, Disposal Method, and Comment fields`() {
        val lsd = BoxNode(
            type = "LogicalScreenDescriptor", offset = 0, headerSize = 0, size = 7,
            fields = listOf(
                BoxField("width", "320", 0, 2),
                BoxField("height", "240", 0, 2),
                BoxField("global_color_table_flag", "0", 0, 1),
                BoxField("color_resolution", "7", 0, 1),
                BoxField("global_color_table_size", "0", 0, 1),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(lsd))

        val summary = buildMediaSummary(root, tempFile())

        val gifDetail = summary.sections.first { it.title == "GIF Detail" }
        assertEquals(null, gifDetail.fields.find { it.label == "Global Color Table" })
        assertEquals(null, gifDetail.fields.find { it.label == "Disposal Method" })
        assertEquals(null, gifDetail.fields.find { it.label == "Comment" })
    }

    @Test
    fun `a non-GIF image (PNG) has no GIF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "GIF Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first 2 new tests throw `NoSuchElementException`. The 3rd passes already.

- [ ] **Step 3: Add `buildGifDetail`**

Insert right after `buildBmpDetail`'s closing `}` (added in Task 2):

```kotlin
private val GIF_DISPOSAL_METHOD_NAMES = mapOf(
    0 to "Unspecified",
    1 to "Do Not Dispose",
    2 to "Restore to Background",
    3 to "Restore to Previous",
)

private fun buildGifDetail(root: BoxNode): SummarySection? {
    val lsd = root.children.find { it.type == "LogicalScreenDescriptor" } ?: return null
    val fields = mutableListOf<SummaryField>()

    lsd.fields.find { it.name == "color_resolution" }?.value?.toIntOrNull()?.let { fields.add(SummaryField("Color Resolution", "${it + 1}-bit")) }

    val globalColorTableFlag = lsd.fields.find { it.name == "global_color_table_flag" }?.value
    if (globalColorTableFlag == "1") {
        lsd.fields.find { it.name == "global_color_table_size" }?.value?.toIntOrNull()?.let { size ->
            fields.add(SummaryField("Global Color Table", "Yes (${1 shl (size + 1)} colors)"))
        }
    }

    val gce = root.children.find { it.type == "GraphicControlExtension" }
    gce?.fields?.find { it.name == "disposal_method" }?.value?.toIntOrNull()?.let { method ->
        fields.add(SummaryField("Disposal Method", GIF_DISPOSAL_METHOD_NAMES[method] ?: method.toString()))
    }
    gce?.fields?.find { it.name == "delay_time" }?.value?.toIntOrNull()?.let { delay ->
        fields.add(SummaryField("Frame Delay", "${delay * 10} ms"))
    }

    val comment = root.children.find { it.type == "CommentExtension" }
    comment?.fields?.find { it.name == "comment" }?.let { fields.add(SummaryField("Comment", it.value)) }

    return if (fields.isNotEmpty()) SummarySection("GIF Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

```kotlin
    buildBmpDetail(root)?.let { sections.add(it) }
    buildGifDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 3 new tests plus all pre-existing cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add GIF Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 5: WebP Detail

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildImageSummary`, new function after `buildGifDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `WebpWalker.kt`'s existing `VP8X`/`VP8 `/`VP8L` node presence and `VP8X`'s existing `flags` field (format `"0x<hex>"`) — pre-existing, no parser changes.
- Produces: `private fun buildWebpDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `a VP8X WebP reports Codec Extended and decodes alpha, animation, and ICC flags`() {
        val vp8x = BoxNode(
            type = "VP8X", offset = 0, headerSize = 8, size = 18,
            fields = listOf(BoxField("flags", "0x30", 0, 1), BoxField("width", "640", 0, 3), BoxField("height", "480", 0, 3)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8x))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Extended (VP8X)", webpDetail.fields.first { it.label == "Codec" }.value)
        assertEquals("Yes", webpDetail.fields.first { it.label == "Has Alpha" }.value)
        assertEquals("No", webpDetail.fields.first { it.label == "Has Animation" }.value)
        assertEquals("Yes", webpDetail.fields.first { it.label == "Has ICC Profile" }.value)
    }

    @Test
    fun `a plain VP8 WebP (no VP8X) reports Codec Lossy with no alpha, animation, or ICC fields`() {
        val vp8 = BoxNode(
            type = "VP8 ", offset = 0, headerSize = 8, size = 10,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Lossy (VP8)", webpDetail.fields.first { it.label == "Codec" }.value)
        assertEquals(null, webpDetail.fields.find { it.label == "Has Alpha" })
    }

    @Test
    fun `a plain VP8L WebP reports Codec Lossless`() {
        val vp8l = BoxNode(
            type = "VP8L", offset = 0, headerSize = 8, size = 9,
            fields = listOf(BoxField("width", "320", 0, 2), BoxField("height", "240", 0, 2)),
        )
        val riff = BoxNode(type = "RIFF", offset = 0, headerSize = 8, size = 12)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(riff, vp8l))

        val summary = buildMediaSummary(root, tempFile())

        val webpDetail = summary.sections.first { it.title == "WebP Detail" }
        assertEquals("Lossless (VP8L)", webpDetail.fields.first { it.label == "Codec" }.value)
    }

    @Test
    fun `a non-WebP image (PNG) has no WebP Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "WebP Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first 3 new tests throw `NoSuchElementException`. The 4th passes already.

- [ ] **Step 3: Add `buildWebpDetail`**

Insert right after `buildGifDetail`'s closing `}` (added in Task 4):

```kotlin
private fun buildWebpDetail(root: BoxNode): SummarySection? {
    val isWebp = root.children.any { it.type == "RIFF" }
    if (!isWebp) return null
    val fields = mutableListOf<SummaryField>()

    val vp8x = root.children.find { it.type == "VP8X" }
    val codec = when {
        vp8x != null -> "Extended (VP8X)"
        root.children.any { it.type == "VP8 " } -> "Lossy (VP8)"
        root.children.any { it.type == "VP8L" } -> "Lossless (VP8L)"
        else -> null
    }
    codec?.let { fields.add(SummaryField("Codec", it)) }

    val flags = vp8x?.fields?.find { it.name == "flags" }?.value?.removePrefix("0x")?.toIntOrNull(16)
    if (flags != null) {
        fields.add(SummaryField("Has Alpha", if (flags and 0x10 != 0) "Yes" else "No"))
        fields.add(SummaryField("Has Animation", if (flags and 0x02 != 0) "Yes" else "No"))
        fields.add(SummaryField("Has ICC Profile", if (flags and 0x20 != 0) "Yes" else "No"))
    }

    return if (fields.isNotEmpty()) SummarySection("WebP Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

```kotlin
    buildGifDetail(root)?.let { sections.add(it) }
    buildWebpDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 4 new tests plus all pre-existing cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add WebP Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 6: TIFF/RAW Detail

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildImageSummary`, new function after `buildWebpDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: `IFD0`'s existing `Orientation`/`Compression`/`PhotometricInterpretation`/`BitsPerSample`/`SamplesPerPixel`/`XResolution`/`YResolution`/`ResolutionUnit` fields — all already human-readable-labeled by the existing `ExifDecoder.kt` (`TAG_VALUE_LABELS`), no parser changes. Applies identically to camera-RAW (CR2/NEF/ARW/DNG), which route through the same generic TIFF/IFD0 walker.
- Produces: `private fun buildTiffDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `a TIFF with Orientation, Compression, PhotometricInterpretation, sample fields, and Resolution reports TIFF Detail`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("Orientation", "Horizontal (normal)", 0, 2),
                BoxField("Compression", "Uncompressed", 0, 2),
                BoxField("PhotometricInterpretation", "RGB", 0, 2),
                BoxField("BitsPerSample", "8, 8, 8", 0, 6),
                BoxField("SamplesPerPixel", "3", 0, 2),
                BoxField("XResolution", "300/1", 0, 8),
                BoxField("YResolution", "300/1", 0, 8),
                BoxField("ResolutionUnit", "inches", 0, 2),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        val tiffDetail = summary.sections.first { it.title == "TIFF Detail" }
        assertEquals("Horizontal (normal)", tiffDetail.fields.first { it.label == "Orientation" }.value)
        assertEquals("Uncompressed", tiffDetail.fields.first { it.label == "Compression" }.value)
        assertEquals("RGB", tiffDetail.fields.first { it.label == "Photometric Interpretation" }.value)
        assertEquals("8, 8, 8", tiffDetail.fields.first { it.label == "Bits Per Sample" }.value)
        assertEquals("3", tiffDetail.fields.first { it.label == "Samples Per Pixel" }.value)
        assertEquals("300/1 x 300/1 inches", tiffDetail.fields.first { it.label == "Resolution" }.value)
    }

    @Test
    fun `a TIFF with XResolution and YResolution but no ResolutionUnit omits the unit suffix`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("ImageWidth", "640", 0, 2),
                BoxField("ImageLength", "480", 0, 2),
                BoxField("XResolution", "72/1", 0, 8),
                BoxField("YResolution", "72/1", 0, 8),
            ),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        val tiffDetail = summary.sections.first { it.title == "TIFF Detail" }
        assertEquals("72/1 x 72/1", tiffDetail.fields.first { it.label == "Resolution" }.value)
    }

    @Test
    fun `a TIFF with no Orientation, Compression, or Resolution fields has no TIFF Detail section`() {
        val ifd0 = BoxNode(
            type = "IFD0", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("ImageWidth", "640", 0, 2), BoxField("ImageLength", "480", 0, 2), BoxField("Make", "TiffCam", 0, 7)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ifd0))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "TIFF Detail" })
    }

    @Test
    fun `a non-TIFF image (PNG) has no TIFF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "TIFF Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first 2 new tests throw `NoSuchElementException`. The 3rd and 4th pass already.

- [ ] **Step 3: Add `buildTiffDetail`**

Insert right after `buildWebpDetail`'s closing `}` (added in Task 5):

```kotlin
private fun buildTiffDetail(root: BoxNode): SummarySection? {
    val ifd0 = findFirst(root) { it.type == "IFD0" } ?: return null
    val fields = mutableListOf<SummaryField>()

    ifd0.fields.find { it.name == "Orientation" }?.let { fields.add(SummaryField("Orientation", it.value)) }
    ifd0.fields.find { it.name == "Compression" }?.let { fields.add(SummaryField("Compression", it.value)) }
    ifd0.fields.find { it.name == "PhotometricInterpretation" }?.let { fields.add(SummaryField("Photometric Interpretation", it.value)) }
    ifd0.fields.find { it.name == "BitsPerSample" }?.let { fields.add(SummaryField("Bits Per Sample", it.value)) }
    ifd0.fields.find { it.name == "SamplesPerPixel" }?.let { fields.add(SummaryField("Samples Per Pixel", it.value)) }

    val xRes = ifd0.fields.find { it.name == "XResolution" }?.value
    val yRes = ifd0.fields.find { it.name == "YResolution" }?.value
    if (xRes != null && yRes != null) {
        val unit = ifd0.fields.find { it.name == "ResolutionUnit" }?.value
        fields.add(SummaryField("Resolution", if (unit != null) "$xRes x $yRes $unit" else "$xRes x $yRes"))
    }

    return if (fields.isNotEmpty()) SummarySection("TIFF Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

```kotlin
    buildWebpDetail(root)?.let { sections.add(it) }
    buildTiffDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 4 new tests plus all pre-existing cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add TIFF Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 7: HEIC/AVIF item property box decoders (irot, imir, pixi, auxC)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/IrotBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/ImirBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/PixiBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/AuxCBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt` (registration, line 25-26)
- Test: `app/src/test/kotlin/com/multiviewer/parser/IrotBoxDecoderTest.kt` (create)
- Test: `app/src/test/kotlin/com/multiviewer/parser/ImirBoxDecoderTest.kt` (create)
- Test: `app/src/test/kotlin/com/multiviewer/parser/PixiBoxDecoderTest.kt` (create)
- Test: `app/src/test/kotlin/com/multiviewer/parser/AuxCBoxDecoderTest.kt` (create)

**Interfaces:**
- Produces: an `irot`-type `BoxNode` with `BoxField("angle", <0-3>, ...)`; an `imir`-type `BoxNode` with `BoxField("axis", <0 or 1>, ...)`; a `pixi`-type `BoxNode` with `BoxField("bits_per_channel", <comma-joined ints>, ...)`; an `auxC`-type `BoxNode` with `BoxField("aux_type", <string>, ...)`. `irot`/`imir`/`pixi` land inside `ipco` (already reachable via the existing `findPrimaryItemProperty` helper with zero changes to it, since `ipco` is already a registered generic container). `auxC` is a top-level item-info box reachable via a plain `findFirst`. Task 8 consumes all four field names above.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/IrotBoxDecoderTest.kt`:
```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IrotBoxDecoderTest {
    @Test
    fun `decodes a 90-degree rotation`() {
        val body = byteArrayOf(0x01)
        val reader = byteReaderOf(body)
        val node = IrotBoxDecoder.decode(reader, "irot", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields.first { it.name == "angle" }.value)
        assertEquals("90°", node.summary)
        reader.close()
    }

    @Test
    fun `ignores reserved high bits, keeping only the low 2-bit angle`() {
        val body = byteArrayOf(0xFB.toByte()) // reserved bits all 1, angle bits = 11 (3)
        val reader = byteReaderOf(body)
        val node = IrotBoxDecoder.decode(reader, "irot", 0, 0, body.size.toLong(), emptyList())
        assertEquals("3", node.fields.first { it.name == "angle" }.value)
        assertEquals("270°", node.summary)
        reader.close()
    }
}
```

Create `app/src/test/kotlin/com/multiviewer/parser/ImirBoxDecoderTest.kt`:
```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ImirBoxDecoderTest {
    @Test
    fun `decodes axis 0`() {
        val body = byteArrayOf(0x00)
        val reader = byteReaderOf(body)
        val node = ImirBoxDecoder.decode(reader, "imir", 0, 0, body.size.toLong(), emptyList())
        assertEquals("0", node.fields.first { it.name == "axis" }.value)
        reader.close()
    }

    @Test
    fun `decodes axis 1, ignoring reserved high bits`() {
        val body = byteArrayOf(0xFF.toByte())
        val reader = byteReaderOf(body)
        val node = ImirBoxDecoder.decode(reader, "imir", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields.first { it.name == "axis" }.value)
        reader.close()
    }
}
```

Create `app/src/test/kotlin/com/multiviewer/parser/PixiBoxDecoderTest.kt`:
```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixiBoxDecoderTest {
    @Test
    fun `decodes 3 channels of 8-bit each`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x03, // num_channels
            0x08, 0x08, 0x08, // bits_per_channel
        )
        val reader = byteReaderOf(body)
        val node = PixiBoxDecoder.decode(reader, "pixi", 0, 0, body.size.toLong(), emptyList())
        assertEquals("8, 8, 8", node.fields.first { it.name == "bits_per_channel" }.value)
        assertEquals("8-bit, 8-bit, 8-bit", node.summary)
        reader.close()
    }

    @Test
    fun `a declared channel count exceeding the remaining bytes produces a warning and no fields`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x05, // num_channels = 5, but only 1 byte follows
            0x0A,
        )
        val reader = byteReaderOf(body)
        val node = PixiBoxDecoder.decode(reader, "pixi", 0, 0, body.size.toLong(), emptyList())
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(node.fields.isEmpty())
        reader.close()
    }
}
```

Create `app/src/test/kotlin/com/multiviewer/parser/AuxCBoxDecoderTest.kt`:
```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AuxCBoxDecoderTest {
    @Test
    fun `decodes a null-terminated aux_type string`() {
        val auxType = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + auxType + byteArrayOf(0x00)
        val reader = byteReaderOf(body)
        val node = AuxCBoxDecoder.decode(reader, "auxC", 0, 0, body.size.toLong(), emptyList())
        assertEquals("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha", node.fields.first { it.name == "aux_type" }.value)
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.IrotBoxDecoderTest" --tests "com.multiviewer.parser.ImirBoxDecoderTest" --tests "com.multiviewer.parser.PixiBoxDecoderTest" --tests "com.multiviewer.parser.AuxCBoxDecoderTest"
```
Expected: FAIL to compile — `IrotBoxDecoder`, `ImirBoxDecoder`, `PixiBoxDecoder`, `AuxCBoxDecoder` don't exist yet.

- [ ] **Step 3: Create the four decoders**

`app/src/main/kotlin/com/multiviewer/parser/IrotBoxDecoder.kt`:
```kotlin
package com.multiviewer.parser

object IrotBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        if (offset + size - payloadStart < 1) {
            w.add("Box too short for irot angle byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val angle = reader.readUInt8(payloadStart) and 0x03
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("angle", angle.toString(), payloadStart, 1)),
            warnings = w, summary = "${angle * 90}°",
        )
    }
}
```

`app/src/main/kotlin/com/multiviewer/parser/ImirBoxDecoder.kt`:
```kotlin
package com.multiviewer.parser

object ImirBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        if (offset + size - payloadStart < 1) {
            w.add("Box too short for imir axis byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val axis = reader.readUInt8(payloadStart) and 0x01
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("axis", axis.toString(), payloadStart, 1)),
            warnings = w,
        )
    }
}
```

`app/src/main/kotlin/com/multiviewer/parser/PixiBoxDecoder.kt`:
```kotlin
package com.multiviewer.parser

object PixiBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for pixi num_channels")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val numChannels = reader.readUInt8(payloadStart)
        if (payloadEnd - (payloadStart + 1) < numChannels) {
            w.add("pixi declares $numChannels channel(s) but not enough bytes remain")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bits = (0 until numChannels).map { reader.readUInt8(payloadStart + 1 + it) }
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("bits_per_channel", bits.joinToString(", "), payloadStart + 1, numChannels.toLong())),
            warnings = w, summary = bits.joinToString(", ") { "${it}-bit" },
        )
    }
}
```

`app/src/main/kotlin/com/multiviewer/parser/AuxCBoxDecoder.kt`:
```kotlin
package com.multiviewer.parser

object AuxCBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for auxC aux_type")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bytes = reader.readBytes(payloadStart, (payloadEnd - payloadStart).toInt())
        val nullIndex = bytes.indexOf(0)
        val auxType = String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.US_ASCII)
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("aux_type", auxType, payloadStart, (if (nullIndex >= 0) nullIndex else bytes.size).toLong())),
            warnings = w, summary = auxType,
        )
    }
}
```

- [ ] **Step 4: Register all four in `Decoders.kt`**

Find (line 25-26):
```kotlin
    BoxRegistry.register("colr", ColrBoxDecoder)
    BoxRegistry.register("pasp", PaspBoxDecoder)
```
Change to:
```kotlin
    BoxRegistry.register("colr", ColrBoxDecoder)
    BoxRegistry.register("pasp", PaspBoxDecoder)
    BoxRegistry.register("irot", IrotBoxDecoder)
    BoxRegistry.register("imir", ImirBoxDecoder)
    BoxRegistry.register("pixi", PixiBoxDecoder)
    BoxRegistry.register("auxC", AuxCBoxDecoder)
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.IrotBoxDecoderTest" --tests "com.multiviewer.parser.ImirBoxDecoderTest" --tests "com.multiviewer.parser.PixiBoxDecoderTest" --tests "com.multiviewer.parser.AuxCBoxDecoderTest"
```
Expected: PASS, all 7 new tests.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/IrotBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/ImirBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/PixiBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/AuxCBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/IrotBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/ImirBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/PixiBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/AuxCBoxDecoderTest.kt
git commit -m "Add irot/imir/pixi/auxC HEIC/AVIF item property box decoders"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 8: HEIC/AVIF Detail Overview section

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` (`buildImageSummary`, new function after `buildTiffDetail`)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: Task 7's `irot`/`imir`/`pixi` fields (via the existing `findPrimaryItemProperty(root, "<type>")` helper) and `auxC`'s `aux_type` field (via a plain `findFirst`).
- Produces: `private fun buildHeicDetail(root: BoxNode): SummarySection?`, called from `buildImageSummary`.

- [ ] **Step 1: Write the failing tests**

Add before `MediaSummaryBuilderTest`'s final closing `}`:

```kotlin
    @Test
    fun `HEIC primary item properties (irot, imir, pixi) and an alpha auxC report HEIC-AVIF Detail`() {
        val irot = BoxNode(type = "irot", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("angle", "1", 0, 1)))
        val imir = BoxNode(type = "imir", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("axis", "0", 0, 1)))
        val pixi = BoxNode(type = "pixi", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("bits_per_channel", "8, 8, 8", 0, 3)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(irot, imir, pixi))
        val ipmaPrimaryItem = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("property_index", "1", 0, 1),
                BoxField("property_index", "2", 0, 1),
                BoxField("property_index", "3", 0, 1),
            ),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaPrimaryItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "1", 0, 4)))
        val auxC = BoxNode(
            type = "auxC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("aux_type", "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha", 0, 44)),
        )
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp, auxC))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        val heicDetail = summary.sections.first { it.title == "HEIC/AVIF Detail" }
        assertEquals("90°", heicDetail.fields.first { it.label == "Rotation" }.value)
        assertEquals("Horizontal Flip (좌우반전)", heicDetail.fields.first { it.label == "Mirror" }.value)
        assertEquals("8, 8, 8", heicDetail.fields.first { it.label == "Bit Depth" }.value)
        assertEquals("Yes", heicDetail.fields.first { it.label == "Has Alpha Channel" }.value)
    }

    @Test
    fun `a HEIC with no irot, imir, pixi, or auxC has no HEIC-AVIF Detail section`() {
        val ispe = BoxNode(type = "ispe", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("image_width", "800", 0, 4), BoxField("image_height", "600", 0, 4)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "HEIC/AVIF Detail" })
    }

    @Test
    fun `an auxC with a non-alpha aux_type does not set Has Alpha Channel`() {
        val auxC = BoxNode(
            type = "auxC", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("aux_type", "urn:mpeg:mpegB:cicp:systems:auxiliary:depth", 0, 44)),
        )
        val irot = BoxNode(type = "irot", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("angle", "0", 0, 1)))
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(irot))
        val ipmaPrimaryItem = BoxNode(type = "item_1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("property_index", "1", 0, 1)))
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaPrimaryItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))
        val pitm = BoxNode(type = "pitm", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("primary_item_ID", "1", 0, 4)))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, iprp, auxC))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val summary = buildMediaSummary(root, tempFile())

        val heicDetail = summary.sections.first { it.title == "HEIC/AVIF Detail" }
        assertEquals(null, heicDetail.fields.find { it.label == "Has Alpha Channel" })
        assertEquals("0°", heicDetail.fields.first { it.label == "Rotation" }.value)
    }

    @Test
    fun `a non-HEIC image (PNG) has no HEIC-AVIF Detail section`() {
        val ihdr = BoxNode(
            type = "IHDR", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("width", "1920", 0, 4), BoxField("height", "1080", 0, 4), BoxField("color_type", "6", 0, 1)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(ihdr))

        val summary = buildMediaSummary(root, tempFile())

        assertEquals(null, summary.sections.find { it.title == "HEIC/AVIF Detail" })
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: FAIL — the first 3 new tests throw `NoSuchElementException`. The 4th passes already.

- [ ] **Step 3: Add `buildHeicDetail`**

Insert right after `buildTiffDetail`'s closing `}` (added in Task 6):

```kotlin
private fun buildHeicDetail(root: BoxNode): SummarySection? {
    val fields = mutableListOf<SummaryField>()

    val irot = findPrimaryItemProperty(root, "irot")
    irot?.fields?.find { it.name == "angle" }?.value?.toIntOrNull()?.let { angle ->
        fields.add(SummaryField("Rotation", "${angle * 90}°"))
    }

    val imir = findPrimaryItemProperty(root, "imir")
    imir?.fields?.find { it.name == "axis" }?.value?.toIntOrNull()?.let { axis ->
        fields.add(SummaryField("Mirror", if (axis == 0) "Horizontal Flip (좌우반전)" else "Vertical Flip (상하반전)"))
    }

    val pixi = findPrimaryItemProperty(root, "pixi")
    pixi?.fields?.find { it.name == "bits_per_channel" }?.let { fields.add(SummaryField("Bit Depth", it.value)) }

    val auxC = findFirst(root) { it.type == "auxC" }
    val auxType = auxC?.fields?.find { it.name == "aux_type" }?.value
    if (auxType != null && auxType.contains("alpha", ignoreCase = true)) {
        fields.add(SummaryField("Has Alpha Channel", "Yes"))
    }

    return if (fields.isNotEmpty()) SummarySection("HEIC/AVIF Detail", fields) else null
}
```

- [ ] **Step 4: Wire it into `buildImageSummary`**

```kotlin
    buildTiffDetail(root)?.let { sections.add(it) }
    buildHeicDetail(root)?.let { sections.add(it) }
```

- [ ] **Step 5: Run tests to verify they pass**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"
```
Expected: PASS, all 4 new tests plus all pre-existing cases.

- [ ] **Step 6: Run the full suite and commit**

```
./gradlew :app:test
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Add HEIC/AVIF Detail section to the Overview tab"
```
Expected: full suite passes, 0 failures, 0 regressions.

---

### Task 9: Manual verification

**Files:** none (no code changes — this task confirms Tasks 1-8 render correctly in the running app, and catches any real-file surprise the way JPEG's Task 3 caught the two-concatenated-streams bug)

**Interfaces:** none

- [ ] **Step 1: Run the full suite one more time as a clean baseline**

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:test
```
Expected: full suite passes, 0 failures.

- [ ] **Step 2: Verify each format against a real file**

For each of PNG, BMP, GIF, WebP, TIFF (or a camera-RAW file if one is available), and HEIC/AVIF (if a real sample is available), either open it in the running app (`./gradlew :app:run`) and check the Overview tab for the new `<Format> Detail` section, or — to avoid any risk of screenshotting personal file content — write a temporary scratch test (not committed) that calls `buildMediaSummary` directly on a real file path and prints the relevant section's fields, matching the verification approach already used for the JPEG sub-project's Task 3:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test

class ScratchOverviewDetailVerifyTest {
    @Test
    fun `scratch print a format's Detail section for a real file`() {
        val file = File("/path/to/a/real/file.png") // swap per format under test
        val root = parseFile(file)
        val summary = buildMediaSummary(root, file)
        summary.sections.forEach { section ->
            println(section.title)
            section.fields.forEach { println("  ${it.label}: ${it.value}") }
        }
    }
}
```

Run with:
```
./gradlew :app:test --tests "com.multiviewer.parser.ScratchOverviewDetailVerifyTest" -q --info 2>&1 | grep -A 20 "PNG Detail\|BMP Detail\|GIF Detail\|WebP Detail\|TIFF Detail\|HEIC/AVIF Detail"
```

Delete the scratch test file after verifying each format (never commit it).

For each format checked, confirm:
- The new `<Format> Detail` section appears with plausible values (not blank, not crashing).
- Fields that shouldn't apply to that specific file (e.g. no `pHYs` chunk → no Pixel Density; no VP8X → no alpha/animation/ICC fields) are simply absent, not shown as empty rows.
- A file of a *different* format shows no trace of another format's `Detail` section.

If a real sample for a given format genuinely isn't available (e.g. no HEIC/AVIF file on hand), rely on Task 7/8's unit test coverage for that format instead and note the gap explicitly rather than silently skipping it.

- [ ] **Step 3: Report result**

If all available real-file checks pass and the full suite is green, this plan is complete. If anything looks wrong, note the exact file and field, root-cause it (same discipline as the JPEG sub-project's Task 3 bug), fix, add a regression test, and re-verify before considering the plan done.
