# JPEG Marker Detail Enrichment (JPEGsnoop-style) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Detailed Properties panel show richer, JPEGsnoop-benchmarked information for JPEG markers (DQT/SOF/DHT/SOS) and a new whole-image Scan Statistics section computed when the SOS node is selected.

**Architecture:** Two independent pieces. (1) Pure additions to `JpegWalker.kt`'s existing marker decoders -- label suffixes on already-parsed field values, plus new DHT per-length symbol-code fields; no new data model, no behavior change to node types or field names. (2) A new parser-layer pure function (`computeScanStatistics`) that scans the already-decoded preview `Bitmap` for average luminance and the brightest pixel, wired into `DetailedPropertiesPanel` to render only when the selected node is `"SOS"`, computed lazily and off the UI thread.

**Tech Stack:** Kotlin, Compose Desktop, Skia (`org.jetbrains.skia.Bitmap`), existing `kotlin.test` + JUnit5 test setup.

## Global Constraints

- No `BoxField.name` changes -- only `value` strings gain suffixes on existing fields (DQT `destination_id`, SOF `component_id`/`sampling_factors`/`quantization_table`, SOS `component_selector`). Existing consumers keyed by field name must keep working.
- DHT's new fields (`codes_length_01`..`codes_length_16`) are additive only -- existing `class`/`destination_id`/`bit_counts`/`total_codes` fields are unchanged.
- Scan Statistics: full pixel scan (every pixel), not sampled -- accuracy over speed, per the approved design.
- Scan Statistics reports plain pixel `(x, y)` coordinates, not MCU-grid coordinates (this app has no self-implemented block/MCU-level decode to report against).
- No YCC/RGB "clipping" statistics, no camera compression-signature database matching, no self-implemented JPEG entropy/IDCT decoder -- all explicitly out of scope (see the design spec's Non-Goals section).
- Spec: `docs/superpowers/specs/2026-07-28-jpeg-marker-detail-jpegsnoop-design.md`

---

### Task 1: DQT and SOF label enrichment

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Produces: two new file-private helper functions in `JpegWalker.kt` -- `dqtDestinationLabel(id: Int): String` and `componentName(id: Int): String` -- reused by Task 2 (SOS).

- [ ] **Step 1: Update the failing assertions in the existing SOF0 and DQT tests**

In `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`, update `` `decodes SOF0 dimensions and a single component` `` (the test body around line 28-49) to expect the new label suffixes and the new `orientation` field. Replace the whole test body with:

```kotlin
    @Test
    fun `decodes SOF0 dimensions and a single component`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x01,
            0xe0.toByte(), 0x02, 0x80.toByte(), 0x01, 0x01, 0x11, 0x00, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOF0", "EOI"), segments.map { it.type })
        val sof0 = segments[1]
        assertEquals(13L, sof0.size)
        assertEquals("8", sof0.fields.first { it.name == "precision" }.value)
        assertEquals("480", sof0.fields.first { it.name == "height" }.value)
        assertEquals("640", sof0.fields.first { it.name == "width" }.value)
        assertEquals("1", sof0.fields.first { it.name == "num_components" }.value)
        assertEquals("Landscape", sof0.fields.first { it.name == "orientation" }.value)
        assertEquals("1 (Y)", sof0.fields.first { it.name == "component_id" }.value)
        assertEquals("0x11 (1x1)", sof0.fields.first { it.name == "sampling_factors" }.value)
        assertEquals("0 (Luminance)", sof0.fields.first { it.name == "quantization_table" }.value)
        assertEquals("640x480, 1 component(s)", sof0.summary)
        reader.close()
    }
```

Then update `` `DQT decodes a single 8-bit table, de-zigzags it, and estimates quality` `` (around line 173-209): change the line

```kotlin
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
```

to:

```kotlin
        assertEquals("0 (Luminance)", table.fields.first { it.name == "destination_id" }.value)
```

Then update `` `DQT decodes multiple tables packed into one segment` `` (around line 212-244): change

```kotlin
        assertEquals("0", dqt.children[0].fields.first { it.name == "destination_id" }.value)
        assertEquals("1", dqt.children[1].fields.first { it.name == "destination_id" }.value)
```

to:

```kotlin
        assertEquals("0 (Luminance)", dqt.children[0].fields.first { it.name == "destination_id" }.value)
        assertEquals("1 (Chrominance)", dqt.children[1].fields.first { it.name == "destination_id" }.value)
```

- [ ] **Step 2: Run the tests to verify they now fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: FAIL -- the three updated assertions fail because the source still emits the old unlabeled values (e.g. actual `"0"` vs expected `"0 (Luminance)"`).

- [ ] **Step 3: Add the label helpers and wire them into decodeDqt and decodeSof**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, add these two private functions right after the existing `markerName` function (after the line `MARKER_NAMES[marker] ?: "0x${marker.toString(16).padStart(2, '0').uppercase()}"` and its closing brace, before `fun parseJpegSegments`):

```kotlin
// JPEG only defines destination IDs 0/1 by convention (not by spec) -- Luminance and Chrominance
// respectively. Any other ID is left as a bare number. Shared by DQT's own destination_id field
// and SOF's per-component quantization_table field (which references a DQT table by the same ID).
private fun dqtDestinationLabel(id: Int): String = when (id) {
    0 -> "$id (Luminance)"
    1 -> "$id (Chrominance)"
    else -> id.toString()
}

// Same convention-not-spec caveat as dqtDestinationLabel: component IDs 1/2/3 = Y/Cb/Cr is the
// near-universal convention (not a hard JPEG rule), so anything else is left as a bare number.
// Shared by SOF's component_id field and SOS's component_selector field (Task 2).
private fun componentName(id: Int): String = when (id) {
    1 -> "$id (Y)"
    2 -> "$id (Cb)"
    3 -> "$id (Cr)"
    else -> id.toString()
}

private fun subsamplingLabel(samplingFactors: Int): String {
    val horizontal = (samplingFactors shr 4) and 0x0F
    val vertical = samplingFactors and 0x0F
    return "0x${samplingFactors.toString(16).padStart(2, '0')} (${horizontal}x${vertical})"
}
```

Then replace the whole `decodeDqt` function's `BoxField("destination_id", destinationId.toString(), pos, 1),` line with:

```kotlin
                    BoxField("destination_id", dqtDestinationLabel(destinationId), pos, 1),
```

Then replace the whole `decodeSof` function with:

```kotlin
private fun decodeSof(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadEnd - payloadStart < 6) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain SOF fields"))
    }
    val precision = reader.readUInt8(payloadStart)
    val height = reader.readUInt16(payloadStart + 1)
    val width = reader.readUInt16(payloadStart + 3)
    val numComponents = reader.readUInt8(payloadStart + 5)
    val orientation = when {
        height > width -> "Portrait"
        width > height -> "Landscape"
        else -> "Square"
    }
    val fields = mutableListOf(
        BoxField("precision", precision.toString(), payloadStart, 1),
        BoxField("height", height.toString(), payloadStart + 1, 2),
        BoxField("width", width.toString(), payloadStart + 3, 2),
        BoxField("num_components", numComponents.toString(), payloadStart + 5, 1),
        BoxField("orientation", orientation, payloadStart + 1, 4),
    )
    var pos = payloadStart + 6
    var componentCount = 0
    for (i in 0 until numComponents) {
        if (pos + 3 > payloadEnd) break
        val componentId = reader.readUInt8(pos)
        val samplingFactors = reader.readUInt8(pos + 1)
        val quantizationTable = reader.readUInt8(pos + 2)
        fields.add(BoxField("component_id", componentName(componentId), pos, 1))
        fields.add(BoxField("sampling_factors", subsamplingLabel(samplingFactors), pos + 1, 1))
        fields.add(BoxField("quantization_table", dqtDestinationLabel(quantizationTable), pos + 2, 1))
        componentCount += 1
        pos += 3
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = fields,
        summary = "${width}x${height}, $componentCount component(s)",
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS (all tests in the class, including the three updated ones)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "Label DQT/SOF field values with Luminance/Chrominance, Y/Cb/Cr, subsampling, orientation"
```

---

### Task 2: SOS component_selector labeling

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `componentName(id: Int): String` from Task 1 (same file, already file-private and in scope).

- [ ] **Step 1: Update the failing assertion in the existing SOS test**

In `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`, in `` `SOS header decodes component selectors, spectral selection, and successive approximation` `` (around line 385-410), change:

```kotlin
        val selectors = sos.fields.filter { it.name == "component_selector" }.map { it.value }
        assertEquals(listOf("1", "2", "3"), selectors)
```

to:

```kotlin
        val selectors = sos.fields.filter { it.name == "component_selector" }.map { it.value }
        assertEquals(listOf("1 (Y)", "2 (Cb)", "3 (Cr)"), selectors)
```

Leave the `dcTables`/`acTables` assertions unchanged -- DC/AC table indices are Huffman table references, not component identities, so they stay bare numbers (this is a small, intentional clarification versus the design spec's looser wording, which suggested labeling those too; only `component_selector` maps 1:1 onto SOF's Y/Cb/Cr convention unambiguously).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: FAIL on the updated `selectors` assertion (actual `["1", "2", "3"]` vs expected `["1 (Y)", "2 (Cb)", "3 (Cr)"]`)

- [ ] **Step 3: Wire componentName into decodeSos**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, in `decodeSos`, change:

```kotlin
        fields.add(BoxField("component_selector", selector.toString(), pos, 1))
```

to:

```kotlin
        fields.add(BoxField("component_selector", componentName(selector), pos, 1))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "Label SOS component_selector with the same Y/Cb/Cr convention as SOF"
```

---

### Task 3: DHT per-bit-length symbol code fields

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Produces: new `HuffmanTable` node fields named `codes_length_01` through `codes_length_16` (2-digit zero-padded), one per bit-length that has at least one code, value a comma-separated list of 2-digit uppercase hex symbol bytes (e.g. `"01, 02, 03"`). Lengths with zero codes get no field.

- [ ] **Step 1: Write the new failing test**

Add this test to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (anywhere among the other DHT tests, e.g. right after `` `DHT decodes multiple tables packed into one segment` ``):

```kotlin
    @Test
    fun `DHT exposes per-bit-length symbol code lists`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x16, 0x00,
            0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DHT", "EOI"), segments.map { it.type })
        val table = segments[1].children[0]
        assertEquals("DC", table.fields.first { it.name == "class" }.value)
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
        assertEquals("3", table.fields.first { it.name == "total_codes" }.value)
        // bit_counts[0] (length 1) = 2 codes -> symbols 0xAA, 0xBB
        assertEquals("AA, BB", table.fields.first { it.name == "codes_length_01" }.value)
        // bit_counts[1] (length 2) = 1 code -> symbol 0xCC
        assertEquals("CC", table.fields.first { it.name == "codes_length_02" }.value)
        // every other length has 0 codes -- no field emitted
        assertEquals(null, table.fields.find { it.name == "codes_length_03" })
        assertEquals(null, table.fields.find { it.name == "codes_length_16" })
        reader.close()
    }
```

This fixture: DHT marker, length=0x0016=22 (2 length bytes + 1 classDest + 16 bit-count bytes + 3 symbol bytes), classDest=0x00 (DC, destination 0), bit_counts=[2,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0] (length 1 has 2 codes, length 2 has 1 code, total=3), symbols=[0xAA, 0xBB, 0xCC] (first 2 belong to length 1, last 1 to length 2).

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest.DHT exposes per-bit-length symbol code lists"`
Expected: FAIL with "No element of the array/collection matched the predicate" (or similar) on the `codes_length_01` lookup -- the field doesn't exist yet.

- [ ] **Step 3: Add the per-length symbol grouping to decodeDht**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, in `decodeDht`, find this block (right after the existing bounds check that adds a warning and `break`s):

```kotlin
        val className = if (tableClass == 0) "DC" else "AC"
        children.add(
            BoxNode(
                type = "HuffmanTable",
                offset = pos,
                headerSize = 1,
                size = tableBytes.toLong(),
                fields = listOf(
                    BoxField("class", className, pos, 1),
                    BoxField("destination_id", destinationId.toString(), pos, 1),
                    BoxField("bit_counts", bitCounts.joinToString(", "), pos + 1, 16),
                    BoxField("total_codes", totalCodes.toString(), pos + 1, 16),
                ),
                summary = "$className table $destinationId, $totalCodes code(s)",
            ),
        )
```

Replace it with:

```kotlin
        val className = if (tableClass == 0) "DC" else "AC"
        val symbolsStart = pos + 1 + 16
        val codeLengthFields = mutableListOf<BoxField>()
        var symbolPos = symbolsStart
        for (length in 1..16) {
            val count = bitCounts[length - 1]
            if (count == 0) continue
            val symbols = reader.readBytes(symbolPos, count)
            val hexList = symbols.joinToString(", ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
            codeLengthFields.add(BoxField("codes_length_${length.toString().padStart(2, '0')}", hexList, symbolPos, count.toLong()))
            symbolPos += count
        }
        children.add(
            BoxNode(
                type = "HuffmanTable",
                offset = pos,
                headerSize = 1,
                size = tableBytes.toLong(),
                fields = listOf(
                    BoxField("class", className, pos, 1),
                    BoxField("destination_id", destinationId.toString(), pos, 1),
                    BoxField("bit_counts", bitCounts.joinToString(", "), pos + 1, 16),
                    BoxField("total_codes", totalCodes.toString(), pos + 1, 16),
                ) + codeLengthFields,
                summary = "$className table $destinationId, $totalCodes code(s)",
            ),
        )
```

This is safe to read (`reader.readBytes(symbolPos, count)`) because it runs after the existing `if (pos + tableBytes > payloadEnd) { warnings.add(...); break }` check a few lines above, which already guarantees the full `1 + 16 + totalCodes` byte range (including all symbol bytes) is within `payloadEnd`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS (the new test, and all pre-existing DHT tests -- their fields are unchanged, only new fields were added)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "Expose DHT per-bit-length Huffman symbol code lists"
```

---

### Task 4: computeScanStatistics pure function

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/JpegScanStatistics.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegScanStatisticsTest.kt`

**Interfaces:**
- Produces: `data class ScanStatistics(val averageLuminance: Double, val brightestX: Int, val brightestY: Int, val brightestR: Int, val brightestG: Int, val brightestB: Int)` and `fun computeScanStatistics(bitmap: org.jetbrains.skia.Bitmap): ScanStatistics`, both in package `com.multiviewer.parser`. Task 5 (UI wiring) consumes both by name.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/com/multiviewer/parser/JpegScanStatisticsTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

class JpegScanStatisticsTest {
    private fun bitmapOf(width: Int, height: Int, bgraBytes: ByteArray): Bitmap {
        return Bitmap().apply {
            allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), width, height))
            installPixels(imageInfo, bgraBytes, width * 4)
        }
    }

    @Test
    fun `computeScanStatistics finds the average luminance and the single brightest pixel`() {
        // 2x2 image, row-major, BGRA bytes per pixel:
        //   (0,0) gray 30              (1,0) R=200 G=100 B=50 (brightest -- distinct channels
        //                                so a channel-order bug would be caught)
        //   (0,1) gray 60              (1,1) gray 90
        val bytes = byteArrayOf(
            30, 30, 30, 255.toByte(), // (0,0): B,G,R,A
            50, 100, 200.toByte(), 255.toByte(), // (1,0): B,G,R,A
            60, 60, 60, 255.toByte(), // (0,1)
            90, 90, 90, 255.toByte(), // (1,1)
        )
        val bitmap = bitmapOf(2, 2, bytes)

        val stats = computeScanStatistics(bitmap)

        // luminances: (0,0)=30, (1,0)=0.299*200+0.587*100+0.114*50=124.2, (0,1)=60, (1,1)=90
        // average = (30 + 124.2 + 60 + 90) / 4 = 76.05
        assertEquals(76.05, stats.averageLuminance, 0.001)
        assertEquals(1, stats.brightestX)
        assertEquals(0, stats.brightestY)
        assertEquals(200, stats.brightestR)
        assertEquals(100, stats.brightestG)
        assertEquals(50, stats.brightestB)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegScanStatisticsTest"`
Expected: FAIL to compile -- `computeScanStatistics` and `ScanStatistics` don't exist yet.

- [ ] **Step 3: Create the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/JpegScanStatistics.kt`:

```kotlin
package com.multiviewer.parser

import org.jetbrains.skia.Bitmap

data class ScanStatistics(
    val averageLuminance: Double,
    val brightestX: Int,
    val brightestY: Int,
    val brightestR: Int,
    val brightestG: Int,
    val brightestB: Int,
)

// Whole-image pixel statistics computed from an already-decoded bitmap (the same one the preview
// panel and ImageAnalyzer.calculateHistogram already use) -- benchmarked against JPEGsnoop's
// "Decoding SCAN Data" section, but reusing Skia's real decode instead of implementing our own
// JPEG entropy/IDCT decoder. Deliberately does NOT reproduce JPEGsnoop's YCC/RGB "clipping"
// statistics -- those measure quantization-coefficient overflow in JPEGsnoop's own simplified
// DC-only decoder, which has no equivalent here (see the design spec's Non-Goals section).
fun computeScanStatistics(bitmap: Bitmap): ScanStatistics {
    val width = bitmap.width
    val height = bitmap.height
    var sumLuminance = 0.0
    var maxLuminance = -1.0
    var brightestX = 0
    var brightestY = 0
    var brightestR = 0
    var brightestG = 0
    var brightestB = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = bitmap.getColor(x, y)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            sumLuminance += luminance
            if (luminance > maxLuminance) {
                maxLuminance = luminance
                brightestX = x
                brightestY = y
                brightestR = r
                brightestG = g
                brightestB = b
            }
        }
    }
    val pixelCount = width * height
    val averageLuminance = if (pixelCount > 0) sumLuminance / pixelCount else 0.0
    return ScanStatistics(averageLuminance, brightestX, brightestY, brightestR, brightestG, brightestB)
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegScanStatisticsTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegScanStatistics.kt app/src/test/kotlin/com/multiviewer/parser/JpegScanStatisticsTest.kt
git commit -m "Add computeScanStatistics: average luminance and brightest pixel from a decoded bitmap"
```

---

### Task 5: Wire Scan Statistics into DetailedPropertiesPanel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

**Interfaces:**
- Consumes: `ScanStatistics` and `computeScanStatistics(bitmap: org.jetbrains.skia.Bitmap): ScanStatistics` from Task 4 (`com.multiviewer.parser` package). Consumes `TabState.imageForensic` (`ImageForensicData?`, with `.bitmap: ImageBitmap?` and `.isDecodingFallback: Boolean`) and `DecodingIndicator(label: String)` (both already defined elsewhere in this codebase -- `AppState.kt` and `Components.kt` respectively).

No automated test for this task -- per the design spec, this codebase has no Compose UI test setup, and this is the wiring/rendering layer over an already-tested pure function (Task 4). Verified manually.

- [ ] **Step 1: Add the new imports**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, add these two import lines alongside the existing `com.multiviewer.parser.*` imports (near the top of the file, after `import com.multiviewer.parser.extractEmbeddedVideo`):

```kotlin
import com.multiviewer.parser.ScanStatistics
import com.multiviewer.parser.computeScanStatistics
import androidx.compose.ui.graphics.asSkiaBitmap
```

- [ ] **Step 2: Add the SosScanStatistics composable**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, add this new private composable right after the `DetailedPropertiesPanel` function's closing brace:

```kotlin
@Composable
private fun SosScanStatistics(tab: TabState, selectedNode: BoxNode) {
    val forensic = tab.imageForensic
    val bitmap = forensic?.bitmap
    if (bitmap == null) {
        if (forensic?.isDecodingFallback == true) {
            Spacer(Modifier.height(8.dp))
            DecodingIndicator("이미지 디코딩 대기 중...")
        }
        return
    }

    var stats by remember(selectedNode, bitmap) { mutableStateOf<ScanStatistics?>(null) }
    LaunchedEffect(selectedNode, bitmap) {
        stats = withContext(Dispatchers.IO) { computeScanStatistics(bitmap.asSkiaBitmap()) }
    }

    Spacer(Modifier.height(8.dp))
    Text("Scan Statistics:", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
    val current = stats
    if (current == null) {
        DecodingIndicator("통계 계산 중...")
    } else {
        PropertyRow("Average Pixel Luminance (Y)", "%.1f (range: 0..255)".format(current.averageLuminance))
        PropertyRow(
            "Brightest Pixel",
            "RGB=[${current.brightestR}, ${current.brightestG}, ${current.brightestB}] @ (${current.brightestX}, ${current.brightestY})",
        )
    }
}
```

- [ ] **Step 3: Call it from DetailedPropertiesPanel when the SOS node is selected**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, inside `DetailedPropertiesPanel`'s `LazyColumn`, find this block:

```kotlin
                selectedNode.table?.let { table ->
                    item { EmbeddedTableView(tab.file, table) }
                }
                if (selectedNode.warnings.isNotEmpty()) {
```

Insert a new `item` block between them, so it reads:

```kotlin
                selectedNode.table?.let { table ->
                    item { EmbeddedTableView(tab.file, table) }
                }
                if (selectedNode.type == "SOS") {
                    item { SosScanStatistics(tab, selectedNode) }
                }
                if (selectedNode.warnings.isNotEmpty()) {
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL, no errors.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including every test from Tasks 1-4).

- [ ] **Step 6: Manual smoke test**

Run: `./gradlew :app:run`
Open a real JPEG file, wait for the primary image preview to finish decoding, select the `SOS` node in the left structure tree. Expected: the right Detailed Properties panel shows a new "Scan Statistics" section below the existing SOS fields, with "Average Pixel Luminance (Y): NN.N (range: 0..255)" and "Brightest Pixel: RGB=[r, g, b] @ (x, y)" -- briefly showing a "통계 계산 중..." spinner first on a large image. Also verify DQT/SOF/DHT/SOS nodes show the new labeled/enriched field values from Tasks 1-3. Close the app.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Show Scan Statistics (average luminance, brightest pixel) when the SOS node is selected"
```
