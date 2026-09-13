# BMP Detail Parsing (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill in `BITMAPINFOHEADER`'s 6 missing fields (`planes`, `image_size`,
`x_pixels_per_meter`, `y_pixels_per_meter`, `colors_used`,
`colors_important`) plus a `compression` name mapping, and add full
recognition of `BITMAPV4HEADER` (108 bytes) and `BITMAPV5HEADER` (124 bytes),
which currently fall to a generic offset/name-only `DIBHEADER` node.

**Architecture:** `BmpWalker.kt`'s `decodeBitmapInfoHeader` is refactored so
its field-building logic lives in a reusable `buildBitmapInfoHeaderFields`
function; `decodeBitmapV4Header` calls it and appends V4's own 16 bytes via a
reusable `buildBitmapV4Fields`; `decodeBitmapV5Header` calls
`buildBitmapV4Fields` and appends V5's own 16 bytes. `parseBmpHeaders`'s
existing `if (headerSize == 40L)` becomes a `when` with `108L`/`124L`
branches. All new multi-byte reads use the file's existing
`readUInt16LE`/`readUInt32LE`/`readInt32LE` free functions (not the
extension-function style `WebpWalker.kt` uses — this file already has its
own established free-function convention, unchanged).

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-13-bmp-detail-parsing-design.md`
  — every numeric constant below was independently verified against
  Microsoft's official Win32 API documentation during that spec's writing
  (cited sources in the spec), not from memory.
- Do not add a palette/pixel-data tree node — out of scope (see spec's
  Non-goals).
- Do not touch OS/2 `BITMAPCOREHEADER` (12 bytes) or any header size other
  than 40/108/124 — the existing generic `DIBHEADER` fallback stays
  unchanged for those.
- No actual RLE4/RLE8 pixel decompression — `compression` gets a name, not a
  decode.
- No new dependency.

### Verified byte-offset reference table (absolute file offset, given a
### `BITMAPFILEHEADER` at file offset 0, so `dibStart = 14`)

Every literal offset used in this plan's code and tests was computed from
this table — cross-check any new literal against it before adding one.

| Absolute offset | Field | Size | Header |
|---|---|---|---|
| 14 | `header_size` | 4 | (dispatch only, not a shown field) |
| 18 | `width` | 4 | BITMAPINFOHEADER |
| 22 | `height` | 4 | BITMAPINFOHEADER |
| 26 | `planes` | 2 | BITMAPINFOHEADER |
| 28 | `bit_count` | 2 | BITMAPINFOHEADER |
| 30 | `compression` | 4 | BITMAPINFOHEADER |
| 34 | `image_size` | 4 | BITMAPINFOHEADER |
| 38 | `x_pixels_per_meter` | 4 | BITMAPINFOHEADER |
| 42 | `y_pixels_per_meter` | 4 | BITMAPINFOHEADER |
| 46 | `colors_used` | 4 | BITMAPINFOHEADER |
| 50 | `colors_important` | 4 | BITMAPINFOHEADER |
| **54** | *(BITMAPINFOHEADER ends: 14+40=54)* | | |
| 54 | `red_mask` | 4 | BITMAPV4HEADER |
| 58 | `green_mask` | 4 | BITMAPV4HEADER |
| 62 | `blue_mask` | 4 | BITMAPV4HEADER |
| 66 | `alpha_mask` | 4 | BITMAPV4HEADER |
| 70 | `color_space_type` | 4 | BITMAPV4HEADER |
| 74 | `endpoint_red_x` | 4 | BITMAPV4HEADER (conditional) |
| 78 | `endpoint_red_y` | 4 | BITMAPV4HEADER (conditional) |
| 82 | `endpoint_red_z` | 4 | BITMAPV4HEADER (conditional) |
| 86 | `endpoint_green_x` | 4 | BITMAPV4HEADER (conditional) |
| 90 | `endpoint_green_y` | 4 | BITMAPV4HEADER (conditional) |
| 94 | `endpoint_green_z` | 4 | BITMAPV4HEADER (conditional) |
| 98 | `endpoint_blue_x` | 4 | BITMAPV4HEADER (conditional) |
| 102 | `endpoint_blue_y` | 4 | BITMAPV4HEADER (conditional) |
| 106 | `endpoint_blue_z` | 4 | BITMAPV4HEADER (conditional) |
| 110 | `gamma_red` | 4 | BITMAPV4HEADER (conditional) |
| 114 | `gamma_green` | 4 | BITMAPV4HEADER (conditional) |
| 118 | `gamma_blue` | 4 | BITMAPV4HEADER (conditional) |
| **122** | *(BITMAPV4HEADER ends: 14+108=122)* | | |
| 122 | `intent` | 4 | BITMAPV5HEADER |
| 126 | `profile_data_offset` | 4 | BITMAPV5HEADER |
| 130 | `profile_size` | 4 | BITMAPV5HEADER |
| 134 | (reserved, not shown) | 4 | BITMAPV5HEADER |
| **138** | *(BITMAPV5HEADER ends: 14+124=138)* | | |

"Conditional" fields (`endpoint_*`, `gamma_*`) are added to the field list
only when `color_space_type == 0` (LCS_CALIBRATED_RGB).

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt` | Refactor `decodeBitmapInfoHeader`; add `decodeBitmapV4Header`, `decodeBitmapV5Header`; update `parseBmpHeaders`'s dispatch; relocate/extend `BMP_COMPRESSION_NAMES` here as `internal`. |
| `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt` | Remove its private `BMP_COMPRESSION_NAMES` copy; simplify `buildBmpDetail` to forward the tree's already-resolved `compression` field value instead of re-deriving it. |
| `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt` | New file — this walker currently has no tests at all. |
| `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` | Update 2 existing BMP tests' fixture `compression` field values (see Task 1). |

### Why tests build bytes programmatically, not as hex literals

Unlike `WebpWalkerTest.kt` (hand-written hex byte arrays), this file's tests
write fields into a zero-filled `ByteArray` using small `putUInt16LE`/
`putUInt32LE`/`putInt32LE` extension functions. BMP headers have far more
multi-byte little-endian fields per struct than WebP's RIFF chunks did
(`BITMAPV5HEADER` alone has 27), so hand-encoding each as literal hex bytes
would multiply the exact kind of arithmetic-transcription risk this effort
has repeatedly caught in earlier phases (e.g. Phase 2's zTXt/iTXt fixture
bugs). Programmatic construction eliminates that risk category entirely.

---

### Task 1: `BITMAPINFOHEADER`'s missing fields + compression name mapping

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt` (new)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt` (modify 2 existing tests)

**Interfaces:**
- Produces: `internal val BMP_COMPRESSION_NAMES: Map<Int, String>` (in
  `BmpWalker.kt`), `private fun buildBitmapInfoHeaderFields(reader: ByteReader, offset: Long): List<BoxField>`
  (10 fields, in field-declaration order) — consumed by Task 2.

**Important cross-file consequence found during plan-writing:** today,
`BmpWalker.kt`'s `compression` field stores a **raw number** as its string
value (e.g. `"0"`), and a separate copy of `BMP_COMPRESSION_NAMES` inside
`MediaSummaryBuilder.kt`'s `buildBmpDetail` re-derives the name from that raw
number for a *different*, pre-existing "BMP Detail" summary panel (from an
earlier, unrelated phase). This task changes `compression`'s field value to
be the **name itself** (matching this whole effort's established convention
— e.g. JPEG's `rendering_intent`, WebP's ALPH `filtering_method` — of
putting the human-readable label directly in the field, not the raw code).
That breaks `buildBmpDetail`'s `.toIntOrNull()` re-derivation (a name string
like `"None (BI_RGB)"` isn't parseable as an int), so `buildBmpDetail` must
be updated in the same task to just forward the already-resolved value, and
the map deleted from `MediaSummaryBuilder.kt` (single source of truth now
lives in `BmpWalker.kt`). Two existing `MediaSummaryBuilderTest.kt` tests
construct their own synthetic tree fixtures with a raw-number `compression`
field — those fixtures must be updated to use a name string instead, since
that's what the real pipeline produces from this task onward. This is a
legitimate test-fixture update reflecting an intentional interface change,
not a regression.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private fun byteReaderOf(bytes: ByteArray, namePrefix: String): ByteReader {
    val tmp = File.createTempFile(namePrefix, ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return ByteReader.open(tmp)
}

// BMP headers have many multi-byte little-endian fields per struct (27 in
// BITMAPV5HEADER alone) -- fields are written into a zero-filled buffer
// programmatically rather than hand-encoded as literal hex byte arrays, to
// avoid arithmetic transcription errors. See the plan's byte-offset
// reference table for every literal offset used below.
private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private fun ByteArray.putInt32LE(offset: Int, value: Int) = putUInt32LE(offset, value.toLong() and 0xFFFFFFFFL)

class BmpWalkerTest {
    @Test
    fun `parses a classic BITMAPFILEHEADER and 40-byte BITMAPINFOHEADER end to end`() {
        val bytes = ByteArray(54) // 14 (BITMAPFILEHEADER) + 40 (BITMAPINFOHEADER)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(2, 15054L) // file_size
        bytes.putUInt32LE(10, 54L) // pixel_data_offset
        bytes.putUInt32LE(14, 40L) // header_size
        bytes.putInt32LE(18, 100) // width
        bytes.putInt32LE(22, 50) // height
        bytes.putUInt16LE(26, 1) // planes
        bytes.putUInt16LE(28, 24) // bit_count
        bytes.putUInt32LE(30, 0L) // compression = BI_RGB
        bytes.putUInt32LE(34, 15000L) // image_size
        bytes.putInt32LE(38, 2835) // x_pixels_per_meter (~72 DPI)
        bytes.putInt32LE(42, 2835) // y_pixels_per_meter
        bytes.putUInt32LE(46, 0L) // colors_used
        bytes.putUInt32LE(50, 0L) // colors_important

        byteReaderOf(bytes, "bmp-walker-classic").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals(2, nodes.size)
            val fileHeader = nodes[0]
            assertEquals("BITMAPFILEHEADER", fileHeader.type)
            assertEquals("15054", fileHeader.fields.first { it.name == "file_size" }.value)
            assertEquals("54", fileHeader.fields.first { it.name == "pixel_data_offset" }.value)

            val infoHeader = nodes[1]
            assertEquals("BITMAPINFOHEADER", infoHeader.type)
            fun field(name: String) = infoHeader.fields.first { it.name == name }.value
            assertEquals("100", field("width"))
            assertEquals("50", field("height"))
            assertEquals("1", field("planes"))
            assertEquals("24", field("bit_count"))
            assertEquals("None (BI_RGB)", field("compression"))
            assertEquals("15000", field("image_size"))
            assertEquals("2835", field("x_pixels_per_meter"))
            assertEquals("2835", field("y_pixels_per_meter"))
            assertEquals("0", field("colors_used"))
            assertEquals("0", field("colors_important"))
            assertEquals("100x50, 24-bit", infoHeader.summary)
        }
    }

    @Test
    fun `each documented compression value maps to its name`() {
        val expected = mapOf(
            0L to "None (BI_RGB)",
            1L to "RLE 8-bit (BI_RLE8)",
            2L to "RLE 4-bit (BI_RLE4)",
            3L to "Bit Fields (BI_BITFIELDS)",
            4L to "JPEG (BI_JPEG)",
            5L to "PNG (BI_PNG)",
            6L to "Alpha Bit Fields (BI_ALPHABITFIELDS)",
            11L to "CMYK (BI_CMYK)",
            12L to "CMYK RLE 8-bit (BI_CMYKRLE8)",
            13L to "CMYK RLE 4-bit (BI_CMYKRLE4)",
        )
        for ((value, label) in expected) {
            val bytes = ByteArray(54)
            bytes[0] = 'B'.code.toByte()
            bytes[1] = 'M'.code.toByte()
            bytes.putUInt32LE(14, 40L)
            bytes.putInt32LE(18, 1)
            bytes.putInt32LE(22, 1)
            bytes.putUInt16LE(28, 1)
            bytes.putUInt32LE(30, value)

            byteReaderOf(bytes, "bmp-walker-compression-$value").use { reader ->
                val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
                assertEquals(label, nodes[1].fields.first { it.name == "compression" }.value)
            }
        }
    }

    @Test
    fun `an unrecognized compression value falls back to an Unknown label`() {
        val bytes = ByteArray(54)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 40L)
        bytes.putInt32LE(18, 1)
        bytes.putInt32LE(22, 1)
        bytes.putUInt32LE(30, 99L)

        byteReaderOf(bytes, "bmp-walker-unknown-compression").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("Unknown (99)", nodes[1].fields.first { it.name == "compression" }.value)
        }
    }

    @Test
    fun `a truncated BITMAPINFOHEADER produces a warning and no fields`() {
        val bytes = ByteArray(14 + 20) // header_size claims 40, but only 20 bytes follow
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 40L)

        byteReaderOf(bytes, "bmp-walker-truncated-infoheader").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val infoHeader = nodes[1]
            assertEquals(0, infoHeader.fields.size)
            assertEquals(listOf("Truncated BITMAPINFOHEADER"), infoHeader.warnings)
        }
    }
}
```

Also update `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`:
find the test `a BMP with bit_count and compression fields reports BMP Detail`
(around line 1324) and change its fixture's compression field from
`BoxField("compression", "0", 0, 4)` to
`BoxField("compression", "None (BI_RGB)", 0, 4)` — the assertion
(`assertEquals("None (BI_RGB)", ...)`, already present) does not change,
only the input fixture, since it must now match what `BmpWalker.kt` actually
produces (a resolved name, not a raw code). Add a one-line comment above the
field: `// BmpWalker.kt now resolves compression to its name directly (see
Phase 4 BMP detail-parsing plan) -- buildBmpDetail forwards this value as-is.`

Similarly find `an RLE8-compressed BMP labels Compression as RLE 8-bit (BI_RLE8)`
(around line 1345) and change `BoxField("compression", "1", 0, 4)` to
`BoxField("compression", "RLE 8-bit (BI_RLE8)", 0, 4)` with the same kind of
comment. Its assertion also does not change.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: FAIL — `BmpWalkerTest.kt` references `BoxNode`/`parseBmpHeaders`
fine (those already exist), but every new-field assertion fails since
`decodeBitmapInfoHeader` doesn't produce them yet, and `compression`
currently returns a raw number, not a name.

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: the 2 modified tests FAIL, since `buildBmpDetail` doesn't yet know
how to handle a non-numeric `compression` field value (its current
`.toIntOrNull()` returns `null` for a name string, so the `Compression`
summary field is silently dropped, and the assertion --
`bmpDetail.fields.first { it.label == "Compression" }` -- throws
`NoSuchElementException`).

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`, add near the
top of the file (after the existing `readUInt16LE`/`readUInt32LE`/
`readInt32LE` helpers):

```kotlin
internal val BMP_COMPRESSION_NAMES = mapOf(
    0 to "None (BI_RGB)",
    1 to "RLE 8-bit (BI_RLE8)",
    2 to "RLE 4-bit (BI_RLE4)",
    3 to "Bit Fields (BI_BITFIELDS)",
    4 to "JPEG (BI_JPEG)",
    5 to "PNG (BI_PNG)",
    6 to "Alpha Bit Fields (BI_ALPHABITFIELDS)",
    11 to "CMYK (BI_CMYK)",
    12 to "CMYK RLE 8-bit (BI_CMYKRLE8)",
    13 to "CMYK RLE 4-bit (BI_CMYKRLE4)",
)
```

Replace the existing `decodeBitmapInfoHeader` function with:

```kotlin
// Shared by BITMAPINFOHEADER, BITMAPV4HEADER, and BITMAPV5HEADER -- all
// three start with this exact 40-byte layout (verified against Microsoft's
// Win32 API docs; see the plan's byte-offset reference table).
private fun buildBitmapInfoHeaderFields(reader: ByteReader, offset: Long): List<BoxField> {
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val planes = readUInt16LE(reader, offset + 12)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    val compressionLabel = BMP_COMPRESSION_NAMES[compression.toInt()] ?: "Unknown ($compression)"
    val imageSize = readUInt32LE(reader, offset + 20)
    val xPelsPerMeter = readInt32LE(reader, offset + 24)
    val yPelsPerMeter = readInt32LE(reader, offset + 28)
    val colorsUsed = readUInt32LE(reader, offset + 32)
    val colorsImportant = readUInt32LE(reader, offset + 36)
    return listOf(
        BoxField("width", width.toString(), offset + 4, 4),
        BoxField("height", height.toString(), offset + 8, 4),
        BoxField("planes", planes.toString(), offset + 12, 2),
        BoxField("bit_count", bitCount.toString(), offset + 14, 2),
        BoxField("compression", compressionLabel, offset + 16, 4),
        BoxField("image_size", imageSize.toString(), offset + 20, 4),
        BoxField("x_pixels_per_meter", xPelsPerMeter.toString(), offset + 24, 4),
        BoxField("y_pixels_per_meter", yPelsPerMeter.toString(), offset + 28, 4),
        BoxField("colors_used", colorsUsed.toString(), offset + 32, 4),
        BoxField("colors_important", colorsImportant.toString(), offset + 36, 4),
    )
}

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val fields = buildBitmapInfoHeaderFields(reader, offset)
    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = fields,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
```

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`,
delete the existing `private val BMP_COMPRESSION_NAMES = mapOf(...)` block
(lines ~490-497 — now superseded by `BmpWalker.kt`'s `internal` copy, same
package so no import needed), and replace `buildBmpDetail`'s body with:

```kotlin
private fun buildBmpDetail(root: BoxNode): SummarySection? {
    val infoHeader = root.children.find { it.type == "BITMAPINFOHEADER" } ?: return null
    val fields = mutableListOf<SummaryField>()

    infoHeader.fields.find { it.name == "bit_count" }?.let { fields.add(SummaryField("Bit Count", "${it.value}-bit")) }
    infoHeader.fields.find { it.name == "compression" }?.let { fields.add(SummaryField("Compression", it.value)) }

    return if (fields.isNotEmpty()) SummarySection("BMP Detail", fields) else null
}
```

(Note: `buildBmpDetail` still only matches `type == "BITMAPINFOHEADER"`, so
it won't find a "BMP Detail" summary for `BITMAPV4HEADER`/`BITMAPV5HEADER`
files added by Tasks 2-3 — this is pre-existing behavior, unchanged by this
plan: today, any non-40-byte DIB header already falls to the generic
`DIBHEADER` type and gets no "BMP Detail" summary either. Extending that
separate summary panel to V4/V5 is out of scope for this structure-tree
detail-parsing effort.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: PASS, all 4 tests.

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: PASS, all tests including the 2 modified ones.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "feat: fill in BITMAPINFOHEADER's missing fields and compression names

Adds planes/image_size/x_pixels_per_meter/y_pixels_per_meter/colors_used/
colors_important (6 of BITMAPINFOHEADER's 11 fields were unparsed), and
maps compression's raw code to a human-readable name (BMP_COMPRESSION_NAMES,
relocated from MediaSummaryBuilder.kt to BmpWalker.kt as the single source
of truth and extended with 3 previously-unlisted values: BI_ALPHABITFIELDS,
BI_CMYK, BI_CMYKRLE8/4).

MediaSummaryBuilder.kt's buildBmpDetail (a separate, pre-existing summary
panel) now forwards the tree's already-resolved compression name instead of
re-deriving it from a raw number -- its own copy of the name map is
deleted. Two of its existing tests updated to pass a name string as their
compression fixture, matching the new real-world field shape; their
assertions are unchanged.

See docs/superpowers/specs/2026-09-13-bmp-detail-parsing-design.md"
```

---

### Task 2: `BITMAPV4HEADER` recognition

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt`

**Interfaces:**
- Consumes: `buildBitmapInfoHeaderFields(reader: ByteReader, offset: Long): List<BoxField>` (Task 1).
- Produces: `private fun buildBitmapV4Fields(reader: ByteReader, offset: Long): List<BoxField>` (Task 1's 10 fields + masks + color_space_type + conditional gamma/endpoints) — consumed by Task 3.

- [ ] **Step 1: Write the failing tests**

Add to `BmpWalkerTest.kt`:

```kotlin
    @Test
    fun `BITMAPV4HEADER shows RGBA masks and sRGB color space without calibrated-RGB fields`() {
        val bytes = ByteArray(122) // 14 (BITMAPFILEHEADER) + 108 (BITMAPV4HEADER)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 108L) // header_size
        bytes.putInt32LE(18, 200) // width
        bytes.putInt32LE(22, 100) // height
        bytes.putUInt16LE(28, 32) // bit_count
        bytes.putUInt32LE(30, 3L) // compression = BI_BITFIELDS
        bytes.putUInt32LE(54, 0x00FF0000L) // red_mask
        bytes.putUInt32LE(58, 0x0000FF00L) // green_mask
        bytes.putUInt32LE(62, 0x000000FFL) // blue_mask
        bytes.putUInt32LE(66, 0xFF000000L) // alpha_mask
        bytes.putUInt32LE(70, 0x73524742L) // color_space_type = LCS_sRGB

        byteReaderOf(bytes, "bmp-walker-v4-srgb").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v4 = nodes[1]
            assertEquals("BITMAPV4HEADER", v4.type)
            fun field(name: String) = v4.fields.first { it.name == name }.value
            assertEquals("0x00FF0000", field("red_mask"))
            assertEquals("0x0000FF00", field("green_mask"))
            assertEquals("0x000000FF", field("blue_mask"))
            assertEquals("0xFF000000", field("alpha_mask"))
            assertEquals("sRGB (LCS_sRGB)", field("color_space_type"))
            assertEquals(false, v4.fields.any { it.name == "gamma_red" })
            assertEquals(false, v4.fields.any { it.name.startsWith("endpoint_") })
            assertEquals("200x100, 32-bit", v4.summary)
        }
    }

    @Test
    fun `BITMAPV4HEADER with Calibrated RGB color space shows hand-verified gamma and endpoint fields`() {
        // Endpoint/gamma values chosen to be exactly representable so the
        // expected strings are unambiguous, not just "whatever the code
        // under test produces":
        // endpoint_red_x = 0x40000000 / 2^30 = 1.0 exactly
        // endpoint_red_y = 0x20000000 / 2^30 = 0.5 exactly
        // gamma_blue = upper 16 bits 1, lower 16 bits 0 = 1.0000 exactly (16.16 fixed point)
        val bytes = ByteArray(122)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 108L)
        bytes.putInt32LE(18, 10)
        bytes.putInt32LE(22, 10)
        bytes.putUInt16LE(28, 24)
        // color_space_type at offset 70 is left as 0x00000000 (LCS_CALIBRATED_RGB) by the zero-filled array
        bytes.putInt32LE(74, 0x40000000) // endpoint_red_x
        bytes.putInt32LE(78, 0x20000000) // endpoint_red_y
        bytes.putUInt32LE(118, 0x00010000L) // gamma_blue

        byteReaderOf(bytes, "bmp-walker-v4-calibrated-rgb").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v4 = nodes[1]
            fun field(name: String) = v4.fields.first { it.name == name }.value
            assertEquals("Calibrated RGB (LCS_CALIBRATED_RGB)", field("color_space_type"))
            assertEquals("1.000000", field("endpoint_red_x"))
            assertEquals("0.500000", field("endpoint_red_y"))
            assertEquals("1.0000", field("gamma_blue"))
        }
    }

    @Test
    fun `an unrecognized color_space_type falls back to a hex label`() {
        val bytes = ByteArray(122)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 108L)
        bytes.putInt32LE(18, 1)
        bytes.putInt32LE(22, 1)
        bytes.putUInt32LE(70, 0x12345678L)

        byteReaderOf(bytes, "bmp-walker-v4-unknown-colorspace").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("Unknown (0x12345678)", nodes[1].fields.first { it.name == "color_space_type" }.value)
        }
    }

    @Test
    fun `a truncated BITMAPV4HEADER produces a warning and no fields`() {
        val bytes = ByteArray(14 + 60) // header_size claims 108, but only 60 bytes follow
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 108L)

        byteReaderOf(bytes, "bmp-walker-v4-truncated").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v4 = nodes[1]
            assertEquals(0, v4.fields.size)
            assertEquals(listOf("Truncated BITMAPV4HEADER"), v4.warnings)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: FAIL for all 4 new tests — `parseBmpHeaders` still dispatches any
`headerSize != 40L` to the generic `DIBHEADER` fallback, so `nodes[1].type`
is `"DIBHEADER"`, not `"BITMAPV4HEADER"`, and none of the new fields exist.

- [ ] **Step 3: Implement**

Add to `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`, after
`decodeBitmapInfoHeader`:

```kotlin
private val COLOR_SPACE_TYPE_NAMES = mapOf(
    0x00000000L to "Calibrated RGB (LCS_CALIBRATED_RGB)",
    0x73524742L to "sRGB (LCS_sRGB)",
    0x57696E20L to "Windows Color Space (LCS_WINDOWS_COLOR_SPACE)",
    0x4C494E4BL to "Linked Profile (PROFILE_LINKED)",
    0x4D424544L to "Embedded Profile (PROFILE_EMBEDDED)",
)

private fun colorSpaceTypeLabel(value: Long): String =
    COLOR_SPACE_TYPE_NAMES[value] ?: "Unknown (0x%08X)".format(value)

// FXPT2DOT30: a signed 2.30 fixed-point number (2's-complement sign, 1
// integer bit, 30 fractional bits), stored little-endian like every other
// BMP field -- NOT the same encoding as ICC's big-endian s15Fixed16Number
// (readS15Fixed16, JpegWalker.kt) this superficially resembles.
private fun readFxpt2Dot30(reader: ByteReader, offset: Long): Double {
    val raw = readInt32LE(reader, offset)
    return raw / 1073741824.0 // 2^30
}

// Unsigned 16.16 fixed-point: upper 16 bits are the integer part, lower 16
// bits are the fractional part (used for gamma_red/green/blue).
private fun formatGamma(raw: Long): String {
    val integerPart = raw shr 16
    val fractionalPart = (raw and 0xFFFF) / 65536.0
    return "%.4f".format(integerPart + fractionalPart)
}

private fun buildCalibratedRgbFields(reader: ByteReader, endpointsOffset: Long, gammaOffset: Long): List<BoxField> {
    val x1 = readFxpt2Dot30(reader, endpointsOffset)
    val y1 = readFxpt2Dot30(reader, endpointsOffset + 4)
    val z1 = readFxpt2Dot30(reader, endpointsOffset + 8)
    val x2 = readFxpt2Dot30(reader, endpointsOffset + 12)
    val y2 = readFxpt2Dot30(reader, endpointsOffset + 16)
    val z2 = readFxpt2Dot30(reader, endpointsOffset + 20)
    val x3 = readFxpt2Dot30(reader, endpointsOffset + 24)
    val y3 = readFxpt2Dot30(reader, endpointsOffset + 28)
    val z3 = readFxpt2Dot30(reader, endpointsOffset + 32)
    val gammaRed = readUInt32LE(reader, gammaOffset)
    val gammaGreen = readUInt32LE(reader, gammaOffset + 4)
    val gammaBlue = readUInt32LE(reader, gammaOffset + 8)
    return listOf(
        BoxField("endpoint_red_x", "%.6f".format(x1), endpointsOffset, 4),
        BoxField("endpoint_red_y", "%.6f".format(y1), endpointsOffset + 4, 4),
        BoxField("endpoint_red_z", "%.6f".format(z1), endpointsOffset + 8, 4),
        BoxField("endpoint_green_x", "%.6f".format(x2), endpointsOffset + 12, 4),
        BoxField("endpoint_green_y", "%.6f".format(y2), endpointsOffset + 16, 4),
        BoxField("endpoint_green_z", "%.6f".format(z2), endpointsOffset + 20, 4),
        BoxField("endpoint_blue_x", "%.6f".format(x3), endpointsOffset + 24, 4),
        BoxField("endpoint_blue_y", "%.6f".format(y3), endpointsOffset + 28, 4),
        BoxField("endpoint_blue_z", "%.6f".format(z3), endpointsOffset + 32, 4),
        BoxField("gamma_red", formatGamma(gammaRed), gammaOffset, 4),
        BoxField("gamma_green", formatGamma(gammaGreen), gammaOffset + 4, 4),
        BoxField("gamma_blue", formatGamma(gammaBlue), gammaOffset + 8, 4),
    )
}

// Shared by BITMAPV4HEADER and BITMAPV5HEADER -- both start with this exact
// 108-byte layout (verified against Microsoft's Win32 API docs).
private fun buildBitmapV4Fields(reader: ByteReader, offset: Long): List<BoxField> {
    val fields = buildBitmapInfoHeaderFields(reader, offset).toMutableList()
    val redMask = readUInt32LE(reader, offset + 40)
    val greenMask = readUInt32LE(reader, offset + 44)
    val blueMask = readUInt32LE(reader, offset + 48)
    val alphaMask = readUInt32LE(reader, offset + 52)
    val colorSpaceType = readUInt32LE(reader, offset + 56)
    fields.add(BoxField("red_mask", "0x%08X".format(redMask), offset + 40, 4))
    fields.add(BoxField("green_mask", "0x%08X".format(greenMask), offset + 44, 4))
    fields.add(BoxField("blue_mask", "0x%08X".format(blueMask), offset + 48, 4))
    fields.add(BoxField("alpha_mask", "0x%08X".format(alphaMask), offset + 52, 4))
    fields.add(BoxField("color_space_type", colorSpaceTypeLabel(colorSpaceType), offset + 56, 4))
    if (colorSpaceType == 0L) {
        fields.addAll(buildCalibratedRgbFields(reader, offset + 60, offset + 96))
    }
    return fields
}

private fun decodeBitmapV4Header(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 108) {
        return BoxNode(type = "BITMAPV4HEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPV4HEADER"))
    }
    val fields = buildBitmapV4Fields(reader, offset)
    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPV4HEADER", offset = offset, headerSize = 0, size = 108,
        fields = fields,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
```

Change `parseBmpHeaders`'s dispatch from:

```kotlin
    result.add(
        if (headerSize == 40L) {
            decodeBitmapInfoHeader(reader, dibStart, end)
        } else {
            BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
```

to:

```kotlin
    result.add(
        when (headerSize) {
            40L -> decodeBitmapInfoHeader(reader, dibStart, end)
            108L -> decodeBitmapV4Header(reader, dibStart, end)
            else -> BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
```

(Task 3 will add a `124L` branch to this same `when`.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: PASS, all 8 tests (4 from Task 1 + 4 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt
git commit -m "feat: recognize and parse BITMAPV4HEADER (108-byte DIB header)

Adds red/green/blue/alpha_mask (hex) and color_space_type (named:
Calibrated RGB / sRGB / Windows Color Space / Linked Profile / Embedded
Profile, with a hex fallback for unrecognized values). gamma_red/green/blue
and the 9-value CIE XYZ endpoints are shown only when color_space_type is
Calibrated RGB, per spec (\"ignored\" otherwise) -- showing 12 meaningless
fields for the overwhelmingly common sRGB/Windows case would be noise, not
detail.

Field-building logic is split into buildBitmapV4Fields so Task 3
(BITMAPV5HEADER, which shares this exact 108-byte layout) can reuse it.

See docs/superpowers/specs/2026-09-13-bmp-detail-parsing-design.md"
```

---

### Task 3: `BITMAPV5HEADER` recognition (intent, embedded/linked ICC profile)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt`

**Interfaces:**
- Consumes: `buildBitmapV4Fields(reader: ByteReader, offset: Long): List<BoxField>`
  (Task 2), `decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField>`
  (already `internal` in `JpegWalker.kt`, already reused by PNG's `iCCP` and
  WebP's `ICCP` — this is the fourth reuse).

- [ ] **Step 1: Write the failing tests**

Add to `BmpWalkerTest.kt`:

```kotlin
    @Test
    fun `BITMAPV5HEADER decodes all four named intent values`() {
        val expected = mapOf(
            1L to "Saturation",
            2L to "Relative Colorimetric",
            4L to "Perceptual",
            8L to "Absolute Colorimetric",
        )
        for ((value, label) in expected) {
            val bytes = ByteArray(138) // 14 (BITMAPFILEHEADER) + 124 (BITMAPV5HEADER)
            bytes[0] = 'B'.code.toByte()
            bytes[1] = 'M'.code.toByte()
            bytes.putUInt32LE(14, 124L) // header_size
            bytes.putInt32LE(18, 1) // width
            bytes.putInt32LE(22, 1) // height
            bytes.putUInt32LE(122, value) // intent

            byteReaderOf(bytes, "bmp-walker-v5-intent-$value").use { reader ->
                val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
                assertEquals(label, nodes[1].fields.first { it.name == "intent" }.value)
            }
        }
    }

    @Test
    fun `an unrecognized intent value falls back to Unknown`() {
        val bytes = ByteArray(138)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 124L)
        bytes.putInt32LE(18, 1)
        bytes.putInt32LE(22, 1)
        bytes.putUInt32LE(122, 99L)

        byteReaderOf(bytes, "bmp-walker-v5-intent-unknown").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            assertEquals("Unknown (99)", nodes[1].fields.first { it.name == "intent" }.value)
        }
    }

    @Test
    fun `BITMAPV5HEADER with an embedded ICC profile parses its 128-byte header`() {
        // Reuses the same 128-byte ICC.1 header bytes already verified correct
        // by JpegWalkerTest's APP2 ICC test, and reused again by
        // PngWalkerTest's iCCP test and WebpWalkerTest's ICCP test -- this is
        // the fourth reuse of this exact fixture.
        val iccHeaderBytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x8e.toByte(), // profile_size = 142
            0x41, 0x50, 0x50, 0x4c,          // cmm_type = "APPL"
            0x02, 0x10, 0x00, 0x00,          // version = 2.1.0
            0x6d, 0x6e, 0x74, 0x72,          // profile_class = "mntr"
            0x52, 0x47, 0x42, 0x20,          // data_colour_space = "RGB "
            0x58, 0x59, 0x5a, 0x20,          // pcs = "XYZ "
            0x07, 0xe8.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // date_time_created
            0x61, 0x63, 0x73, 0x70,          // "acsp"
            0x41, 0x50, 0x50, 0x4c,          // primary_platform = "APPL"
            0x00, 0x00, 0x00, 0x00,          // profile_flags = 0
            0x41, 0x50, 0x50, 0x4c,          // device_manufacturer = "APPL"
            0x00, 0x00, 0x00, 0x00,          // device_model = (unspecified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // device_attributes = 0
            0x00, 0x00, 0x00, 0x00,          // rendering_intent = 0 (Perceptual)
            0x00, 0x00, 0xf6.toByte(), 0xd4.toByte(), // illuminant X = 0.9642
            0x00, 0x01, 0x00, 0x00,          // illuminant Y = 1.0000
            0x00, 0x00, 0xd3.toByte(), 0x2d, // illuminant Z = 0.8249
            0x41, 0x50, 0x50, 0x4c,          // profile_creator = "APPL"
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // profile_id = (not set)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 28 reserved bytes
        )
        val header = ByteArray(138)
        header[0] = 'B'.code.toByte()
        header[1] = 'M'.code.toByte()
        header.putUInt32LE(14, 124L)
        header.putInt32LE(18, 1)
        header.putInt32LE(22, 1)
        header.putUInt32LE(70, 0x4D424544L) // color_space_type = PROFILE_EMBEDDED
        header.putUInt32LE(126, 124L) // profile_data_offset -- from dibStart, right after the 124-byte header
        header.putUInt32LE(130, 128L) // profile_size

        val bytes = header + iccHeaderBytes
        byteReaderOf(bytes, "bmp-walker-v5-embedded-icc").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v5 = nodes[1]
            assertEquals("BITMAPV5HEADER", v5.type)
            assertEquals("2.1.0", v5.fields.first { it.name == "version" }.value)
            assertEquals("mntr", v5.fields.first { it.name == "profile_class" }.value)
            assertEquals(0, v5.warnings.size)
        }
    }

    @Test
    fun `BITMAPV5HEADER with a linked ICC profile decodes the NUL-terminated path`() {
        val path = "C:\\Profiles\\test.icm"
        val pathBytes = path.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val header = ByteArray(138)
        header[0] = 'B'.code.toByte()
        header[1] = 'M'.code.toByte()
        header.putUInt32LE(14, 124L)
        header.putInt32LE(18, 1)
        header.putInt32LE(22, 1)
        header.putUInt32LE(70, 0x4C494E4BL) // color_space_type = PROFILE_LINKED
        header.putUInt32LE(126, 124L)
        header.putUInt32LE(130, pathBytes.size.toLong())

        val bytes = header + pathBytes
        byteReaderOf(bytes, "bmp-walker-v5-linked-icc").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v5 = nodes[1]
            assertEquals(path, v5.fields.first { it.name == "linked_profile_path" }.value)
        }
    }

    @Test
    fun `a truncated BITMAPV5HEADER produces a warning and no fields`() {
        val bytes = ByteArray(14 + 100) // header_size claims 124, but only 100 bytes follow
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes.putUInt32LE(14, 124L)

        byteReaderOf(bytes, "bmp-walker-v5-truncated").use { reader ->
            val nodes = parseBmpHeaders(reader, 0, bytes.size.toLong())
            val v5 = nodes[1]
            assertEquals(0, v5.fields.size)
            assertEquals(listOf("Truncated BITMAPV5HEADER"), v5.warnings)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: FAIL for all 5 new tests — `headerSize == 124L` still falls to
the generic `DIBHEADER` fallback.

- [ ] **Step 3: Implement**

Add to `app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt`, after
`decodeBitmapV4Header`:

```kotlin
private val INTENT_NAMES = mapOf(
    1 to "Saturation",
    2 to "Relative Colorimetric",
    4 to "Perceptual",
    8 to "Absolute Colorimetric",
)

private const val PROFILE_EMBEDDED = 0x4D424544L
private const val PROFILE_LINKED = 0x4C494E4BL

private fun decodeBitmapV5Header(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 124) {
        return BoxNode(type = "BITMAPV5HEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPV5HEADER"))
    }
    val fields = buildBitmapV4Fields(reader, offset).toMutableList()
    val colorSpaceType = readUInt32LE(reader, offset + 56)
    val intent = readUInt32LE(reader, offset + 108).toInt()
    val profileDataOffset = readUInt32LE(reader, offset + 112)
    val profileSize = readUInt32LE(reader, offset + 116)
    fields.add(BoxField("intent", INTENT_NAMES[intent] ?: "Unknown ($intent)", offset + 108, 4))
    fields.add(BoxField("profile_data_offset", profileDataOffset.toString(), offset + 112, 4))
    fields.add(BoxField("profile_size", profileSize.toString(), offset + 116, 4))

    val warnings = mutableListOf<String>()
    val profileStart = offset + profileDataOffset
    when (colorSpaceType) {
        PROFILE_EMBEDDED -> {
            if (profileSize >= 128 && profileStart + 128 <= end) {
                val headerBytes = reader.readBytes(profileStart, 128)
                fields.addAll(decodeIccProfileHeader(headerBytes, profileStart))
            } else {
                warnings.add("Embedded ICC profile too short or out of range to parse")
            }
        }
        PROFILE_LINKED -> {
            val maxLen = minOf(260L, end - profileStart).toInt()
            if (maxLen > 0) {
                val nameBytes = reader.readBytes(profileStart, maxLen)
                val nullIndex = nameBytes.indexOf(0)
                val pathLength = if (nullIndex >= 0) nullIndex else nameBytes.size
                val path = String(nameBytes, 0, pathLength, Charsets.ISO_8859_1)
                fields.add(BoxField("linked_profile_path", path, profileStart, pathLength.toLong()))
            } else {
                warnings.add("Linked ICC profile path is out of range")
            }
        }
    }

    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPV5HEADER", offset = offset, headerSize = 0, size = 124,
        fields = fields, warnings = warnings,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
```

Change `parseBmpHeaders`'s `when (headerSize)` (from Task 2) to add the
`124L` branch:

```kotlin
    result.add(
        when (headerSize) {
            40L -> decodeBitmapInfoHeader(reader, dibStart, end)
            108L -> decodeBitmapV4Header(reader, dibStart, end)
            124L -> decodeBitmapV5Header(reader, dibStart, end)
            else -> BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.BmpWalkerTest"`
Expected: PASS, all 13 tests (8 from Tasks 1-2 + 5 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BmpWalker.kt app/src/test/kotlin/com/multiviewer/parser/BmpWalkerTest.kt
git commit -m "feat: recognize and parse BITMAPV5HEADER (124-byte DIB header)

Adds intent (named: Saturation/Relative Colorimetric/Perceptual/Absolute
Colorimetric, hex fallback otherwise), profile_data_offset, and
profile_size. When color_space_type is PROFILE_EMBEDDED, reuses
decodeIccProfileHeader on the profile bytes at the given offset (the
fourth reuse of this parser, after JPEG/PNG/WebP). When PROFILE_LINKED,
decodes the NUL-terminated Windows-1252 profile path as a text field.
Both degrade to a warning (never a crash) if the computed byte range would
run past the end of the file.

profile_data_offset is relative to the DIB header's own start (per spec),
not the file start -- profileStart = offset + profileDataOffset.

See docs/superpowers/specs/2026-09-13-bmp-detail-parsing-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Open a real classic (40-byte header) BMP through
  the app — `sips -s format bmp input.png -o classic.bmp` (macOS) produces
  one — via CLI `dump` and the live GUI, confirming `BITMAPINFOHEADER` shows
  all 10 fields with sensible values and no warnings.
- [ ] **Step 4 (manual)**: Attempt to produce a real `BITMAPV4HEADER`/
  `BITMAPV5HEADER` file. `sips` and Python's `Pillow` (confirmed available
  in this environment) both write 40-byte `BITMAPINFOHEADER`s by default, so
  this needs investigation — check whether either tool has a flag or mode
  for a newer DIB header (e.g. Pillow's BMP plugin internals, or a
  `convert`/ImageMagick installation if present, which supports `-define
  bmp:format=bmp4`or `bmp3` style hints in some versions). If no real V4/V5
  sample can be produced or found in this environment, hand-assemble one
  file using the same `putUInt32LE`-style approach as the unit tests
  (written as a one-off script, not committed) purely to exercise the real
  CLI `dump` path end-to-end once, and note in the report that V4/V5
  correctness rests on the unit tests (13 of this plan's tests target V4/V5
  specifically) rather than an independently-authored real-world file —
  matching this effort's established precedent of accepting unit-test-only
  coverage when no real sample is available (e.g. Phase 3's TIFF/RAW note in
  the sibling `image-formats-overview-detail` project).
