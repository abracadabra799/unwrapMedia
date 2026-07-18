# JPEG Detail Decoding (DQT/DHT/SOS/COM/APP0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JPEGsnoop-depth field decoding for `DQT`, `DHT`, `SOS`, `COM`, and `APP0` JPEG marker segments, which currently show only marker/offset/size with no field decode.

**Architecture:** All new decoding lives in `JpegWalker.kt` alongside the existing `decodeSof`/`decodeApp1`, following the exact same `BoxNode`/`BoxField` conventions. A new `GridData` model (parallel to the existing `TableData`) carries pre-computed grid values for `DQT`'s 8×8 quantization table display, since `TableData`'s file-sequential-read model can't represent the zigzag-to-raster reordering DQT needs. `FieldPanel.kt` gains rendering for `node.grid` alongside its existing fields list.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop, kotlin.test.

## Global Constraints

- Never throw on malformed/truncated input — bail out with a warning, matching the existing `parseJpegSegments`/`BoxWalker.kt` convention.
- No encoder/software signature database matching (JPEGsnoop's "known encoder" detection) — explicitly out of scope.
- No `APP2`/`APP13`/`APP14` decoding — remains structure-only.
- No entropy (Huffman) decoding, DCT coefficient extraction, or pixel reconstruction — structural/header level only.
- No rendering of JFIF thumbnail bitmaps — only declared pixel dimensions shown as fields.
- Quality estimate is a documented heuristic (compares `destination_id == 0` against the public ITU-T.81 Annex K.1 luminance baseline table, `destination_id >= 1` against the chrominance baseline), shown as `"~N%"` to signal it's estimated.
- All new code follows this codebase's existing conventions exactly: `BoxNode`/`BoxField` construction style, `byteReaderOf` test helper, warnings-list convention, absolute file offsets everywhere, big-endian reads via `ByteReader`.

---

### Task 1: `GridData` model, `FieldPanel` grid rendering, and DQT decoding

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `BoxNode`/`BoxField` (`BoxNode.kt`), `decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode` (`JpegWalker.kt`, existing `when` dispatcher).
- Produces: `data class GridData(val columns: Int, val rows: Int, val values: List<String>)`, added to `BoxNode` as `val grid: GridData? = null` — Task 2/3/4 don't need this, but any future grid-shaped decoder could reuse it. `private fun decodeDqt(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode` — internal to `JpegWalker.kt`, not called by other tasks.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (inside the existing `JpegWalkerTest` class):

```kotlin
    @Test
    fun `DQT decodes a single 8-bit table, de-zigzags it, and estimates quality`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x43, 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0x39, 0x33, 0x38,
            0x37, 0x40, 0x48, 0x5c, 0x4e, 0x40, 0x44, 0x57,
            0x45, 0x37, 0x38, 0x50, 0x6d, 0x51, 0x57, 0x5f,
            0x62, 0x67, 0x68, 0x67, 0x3e, 0x4d, 0x71, 0x79,
            0x70, 0x64, 0x78, 0x5c, 0x65, 0x67, 0x63, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DQT", "EOI"), segments.map { it.type })
        val dqt = segments[1]
        assertEquals(1, dqt.children.size)
        val table = dqt.children[0]
        assertEquals("QuantizationTable", table.type)
        assertEquals("0", table.fields.first { it.name == "precision" }.value)
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
        assertEquals("~50%", table.fields.first { it.name == "quality_estimate" }.value)
        val expectedRaster = listOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99,
        ).map { it.toString() }
        assertEquals(GridData(8, 8, expectedRaster), table.grid)
        reader.close()
    }

    @Test
    fun `DQT decodes multiple tables packed into one segment`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x84.toByte(), 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0x39, 0x33, 0x38,
            0x37, 0x40, 0x48, 0x5c, 0x4e, 0x40, 0x44, 0x57,
            0x45, 0x37, 0x38, 0x50, 0x6d, 0x51, 0x57, 0x5f,
            0x62, 0x67, 0x68, 0x67, 0x3e, 0x4d, 0x71, 0x79,
            0x70, 0x64, 0x78, 0x5c, 0x65, 0x67, 0x63, 0x01,
            0x11, 0x12, 0x12, 0x18, 0x15, 0x18, 0x2f, 0x1a,
            0x1a, 0x2f, 0x63, 0x42, 0x38, 0x42, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dqt = segments[1]
        assertEquals(2, dqt.children.size)
        assertEquals("0", dqt.children[0].fields.first { it.name == "destination_id" }.value)
        assertEquals("1", dqt.children[1].fields.first { it.name == "destination_id" }.value)
        assertEquals("~50%", dqt.children[0].fields.first { it.name == "quality_estimate" }.value)
        assertEquals("~50%", dqt.children[1].fields.first { it.name == "quality_estimate" }.value)
        assertEquals("2 quantization table(s)", dqt.summary)
        reader.close()
    }

    @Test
    fun `DQT with a truncated table produces a warning and no children`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x21, 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dqt = segments[1]
        assertEquals(0, dqt.children.size)
        assertTrue(dqt.warnings.isNotEmpty())
        reader.close()
    }
```

Add `import kotlin.test.assertTrue` to the top of `JpegWalkerTest.kt` if not already present (it is not — the existing file only imports `Test` and `assertEquals`).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — compilation error, `GridData` is unresolved and `decodeDqt`/DQT decoding doesn't exist yet (DQT currently falls through to the generic structural-node branch).

- [ ] **Step 3: Add `GridData` to `BoxNode.kt`**

In `app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt`, add this data class after `TableData` and before `BoxNode`:

```kotlin
data class GridData(
    val columns: Int,
    val rows: Int,
    val values: List<String>,
)
```

Then add `grid` to `BoxNode`'s constructor, after `table`:

```kotlin
data class BoxNode(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
    val children: List<BoxNode> = emptyList(),
    val fields: List<BoxField> = emptyList(),
    val warnings: List<String> = emptyList(),
    val summary: String? = null,
    val table: TableData? = null,
    val grid: GridData? = null,
)
```

- [ ] **Step 4: Render `node.grid` in `FieldPanel.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`, add the import:

```kotlin
import com.multiviewer.parser.GridData
```

Change the `FieldPanel` composable from:

```kotlin
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
    }
}
```

to:

```kotlin
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
        if (node.grid != null) {
            item {
                GridDisplay(node.grid!!)
            }
        }
    }
}

@Composable
private fun GridDisplay(grid: GridData) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        for (row in 0 until grid.rows) {
            Row {
                for (col in 0 until grid.columns) {
                    Text(
                        grid.values[row * grid.columns + col].padStart(4),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Implement DQT decoding in `JpegWalker.kt`**

Add this import at the top of `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`:

```kotlin
import kotlin.math.roundToInt
```

Change `decodeSegment` from:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

to:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Then add this at the end of the file:

```kotlin
private val ZIGZAG_TO_RASTER = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63,
)

private val BASELINE_LUMINANCE = intArrayOf(
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99,
)

private val BASELINE_CHROMINANCE = intArrayOf(
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
)

private fun estimateQuality(rasterTable: IntArray, baseline: IntArray): Int {
    var sumRatio = 0.0
    for (i in 0 until 64) {
        sumRatio += rasterTable[i].toDouble() / baseline[i]
    }
    val ratio = sumRatio / 64
    val scaleFactor = ratio * 100.0
    val quality = if (scaleFactor < 100.0) 100.0 - scaleFactor / 2.0 else 5000.0 / scaleFactor
    return quality.roundToInt().coerceIn(1, 100)
}

private fun decodeDqt(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var pos = payloadStart
    while (pos < payloadEnd) {
        if (pos + 1 > payloadEnd) {
            warnings.add("Trailing byte(s) too short for a quantization table header")
            break
        }
        val pqTq = reader.readUInt8(pos)
        val precision = pqTq shr 4
        val destinationId = pqTq and 0x0F
        val valueSize = if (precision == 0) 1 else 2
        val tableBytes = 1 + 64 * valueSize
        if (pos + tableBytes > payloadEnd) {
            warnings.add("Quantization table at offset $pos needs $tableBytes byte(s) but only ${payloadEnd - pos} remain")
            break
        }
        val zigzag = IntArray(64)
        var valuePos = pos + 1
        for (k in 0 until 64) {
            zigzag[k] = if (valueSize == 1) reader.readUInt8(valuePos) else reader.readUInt16(valuePos)
            valuePos += valueSize
        }
        val raster = IntArray(64)
        for (k in 0 until 64) {
            raster[ZIGZAG_TO_RASTER[k]] = zigzag[k]
        }
        val baseline = if (destinationId == 0) BASELINE_LUMINANCE else BASELINE_CHROMINANCE
        val quality = estimateQuality(raster, baseline)
        children.add(
            BoxNode(
                type = "QuantizationTable",
                offset = pos,
                headerSize = 1,
                size = tableBytes.toLong(),
                fields = listOf(
                    BoxField("precision", precision.toString(), pos, 1),
                    BoxField("destination_id", destinationId.toString(), pos, 1),
                    BoxField("quality_estimate", "~$quality%", pos, tableBytes.toLong()),
                ),
                grid = GridData(8, 8, raster.map { it.toString() }),
                summary = "precision=$precision, destination_id=$destinationId, quality~$quality%",
            ),
        )
        pos += tableBytes
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        children = children, warnings = warnings,
        summary = "${children.size} quantization table(s)",
    )
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS — all tests pass, including the 3 new DQT tests.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — no regressions in `ExifDecoderTest`, `ParseFileIntegrationTest`, or any other existing test.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BoxNode.kt app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: decode DQT quantization tables with de-zigzagged grid and quality estimate"
```

---

### Task 2: DHT (Huffman table) decoding

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeSegment` dispatcher (extends the `when` added in Task 1). Does not use `GridData` — DHT tables are shown as fields only, no grid.
- Produces: nothing consumed by later tasks — DHT is independent of Task 3/4.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`:

```kotlin
    @Test
    fun `DHT decodes a single Huffman table's bit counts and total code count`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x1f, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DHT", "EOI"), segments.map { it.type })
        val dht = segments[1]
        assertEquals(1, dht.children.size)
        val table = dht.children[0]
        assertEquals("HuffmanTable", table.type)
        assertEquals("DC", table.fields.first { it.name == "class" }.value)
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
        assertEquals("0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", table.fields.first { it.name == "bit_counts" }.value)
        assertEquals("12", table.fields.first { it.name == "total_codes" }.value)
        reader.close()
    }

    @Test
    fun `DHT decodes multiple tables packed into one segment`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x32, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x11, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xaa.toByte(), 0xbb.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dht = segments[1]
        assertEquals(2, dht.children.size)
        assertEquals("DC", dht.children[0].fields.first { it.name == "class" }.value)
        assertEquals("AC", dht.children[1].fields.first { it.name == "class" }.value)
        assertEquals("1", dht.children[1].fields.first { it.name == "destination_id" }.value)
        assertEquals("2", dht.children[1].fields.first { it.name == "total_codes" }.value)
        reader.close()
    }

    @Test
    fun `DHT with a truncated table produces a warning and no children`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x0d, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dht = segments[1]
        assertEquals(0, dht.children.size)
        assertTrue(dht.warnings.isNotEmpty())
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — `DHT` currently decodes as a generic structural node with no children, so `dht.children.size` is `0` where `1` or `2` is expected in the first two tests.

- [ ] **Step 3: Implement DHT decoding in `JpegWalker.kt`**

Change `decodeSegment` from:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

to:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Then add this function at the end of the file:

```kotlin
private fun decodeDht(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var pos = payloadStart
    while (pos < payloadEnd) {
        if (pos + 1 + 16 > payloadEnd) {
            warnings.add("Huffman table at offset $pos needs at least 17 byte(s) but only ${payloadEnd - pos} remain")
            break
        }
        val classDest = reader.readUInt8(pos)
        val tableClass = classDest shr 4
        val destinationId = classDest and 0x0F
        val bitCounts = IntArray(16)
        var totalCodes = 0
        for (i in 0 until 16) {
            bitCounts[i] = reader.readUInt8(pos + 1 + i)
            totalCodes += bitCounts[i]
        }
        val tableBytes = 1 + 16 + totalCodes
        if (pos + tableBytes > payloadEnd) {
            warnings.add("Huffman table at offset $pos declares $totalCodes code(s) but not enough symbol data remains")
            break
        }
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
        pos += tableBytes
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        children = children, warnings = warnings,
        summary = "${children.size} Huffman table(s)",
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS — all tests pass, including the 3 new DHT tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: decode DHT Huffman tables (class, destination, bit-length histogram)"
```

---

### Task 3: SOS (Start of Scan) header decoding

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeSegment` dispatcher. Does **not** touch the existing scan-data-skip sizing loop in `parseJpegSegments` (the code that computes `totalSize` for `marker == 0xDA` by scanning past `0xFF00` stuffing / `0xFFD0`-`0xFFD7` restart markers / `0xFF`-fill-byte runs) — that logic runs *before* `decodeSegment` is called and is unchanged by this task. This task only decodes the fixed-length SOS *header* fields using `declaredSize` (not `totalSize`) as the field-bounds reference, exactly like `decodeSof` already does.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Add this test to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`:

```kotlin
    @Test
    fun `SOS header decodes component selectors, spectral selection, and successive approximation`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0x00, 0x0c, 0x03, 0x01,
            0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3f, 0x00,
            0xab.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOS", "EOI"), segments.map { it.type })
        val sos = segments[1]
        assertEquals(2L, sos.offset)
        assertEquals(15L, sos.size)
        assertEquals("3", sos.fields.first { it.name == "num_components" }.value)
        val selectors = sos.fields.filter { it.name == "component_selector" }.map { it.value }
        assertEquals(listOf("1", "2", "3"), selectors)
        val dcTables = sos.fields.filter { it.name == "dc_table" }.map { it.value }
        assertEquals(listOf("0", "1", "1"), dcTables)
        val acTables = sos.fields.filter { it.name == "ac_table" }.map { it.value }
        assertEquals(listOf("0", "1", "1"), acTables)
        assertEquals("0", sos.fields.first { it.name == "spectral_selection_start" }.value)
        assertEquals("63", sos.fields.first { it.name == "spectral_selection_end" }.value)
        assertEquals("0", sos.fields.first { it.name == "successive_approx_high" }.value)
        assertEquals("0", sos.fields.first { it.name == "successive_approx_low" }.value)
        reader.close()
    }
```

This fixture has a 3-component header (a realistic baseline Y/Cb/Cr scan) rather than the existing SOS tests' 1-component header, but the same trailing shape (one scan-data byte `0xAB` before `EOI`) — so `sos.size` is still computed by the existing, unchanged scan-skip loop as `14` (header) `+ 1` (scan byte) `= 15`, exercising that sizing logic again as a side effect and confirming Task 3's header-field decoding doesn't disturb it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — `SOS` currently decodes as a generic structural node with no `fields`, so every `sos.fields.first { ... }` call throws `NoSuchElementException`.

- [ ] **Step 3: Implement SOS header decoding in `JpegWalker.kt`**

Change `decodeSegment` from:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

to:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Then add this function at the end of the file:

```kotlin
private fun decodeSos(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadStart + 1 > payloadEnd) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain num_components"))
    }
    val numComponents = reader.readUInt8(payloadStart)
    val fields = mutableListOf(BoxField("num_components", numComponents.toString(), payloadStart, 1))
    var pos = payloadStart + 1
    var componentCount = 0
    for (i in 0 until numComponents) {
        if (pos + 2 > payloadEnd) break
        val selector = reader.readUInt8(pos)
        val tables = reader.readUInt8(pos + 1)
        fields.add(BoxField("component_selector", selector.toString(), pos, 1))
        fields.add(BoxField("dc_table", (tables shr 4).toString(), pos + 1, 1))
        fields.add(BoxField("ac_table", (tables and 0x0F).toString(), pos + 1, 1))
        componentCount += 1
        pos += 2
    }
    if (pos + 3 > payloadEnd) {
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = fields,
            warnings = listOf("Segment too short to contain spectral selection / successive approximation fields"),
        )
    }
    val spectralStart = reader.readUInt8(pos)
    val spectralEnd = reader.readUInt8(pos + 1)
    val approx = reader.readUInt8(pos + 2)
    fields.add(BoxField("spectral_selection_start", spectralStart.toString(), pos, 1))
    fields.add(BoxField("spectral_selection_end", spectralEnd.toString(), pos + 1, 1))
    fields.add(BoxField("successive_approx_high", (approx shr 4).toString(), pos + 2, 1))
    fields.add(BoxField("successive_approx_low", (approx and 0x0F).toString(), pos + 2, 1))
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = fields,
        summary = "$componentCount component(s)",
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — in particular, confirm the pre-existing `SOS scan-data skip does not stop at a byte-stuffed FF00 or an RST marker` test and the `SOS scan-data skip treats a run of FF fill bytes before the real marker as scan data` test both still pass unchanged, proving this task didn't disturb the scan-data sizing logic.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: decode SOS header fields (component selectors, spectral selection, successive approximation)"
```

---

### Task 4: COM (comment) and APP0 (JFIF) decoding

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeSegment` dispatcher (final extension of the `when` block for this plan).
- Produces: nothing consumed by later tasks — this is the final task.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`:

```kotlin
    @Test
    fun `COM decodes the comment payload as UTF-8 text`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xfe.toByte(), 0x00, 0x0e, 0x54, 0x65,
            0x73, 0x74, 0x20, 0x63, 0x6f, 0x6d, 0x6d, 0x65,
            0x6e, 0x74, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "COM", "EOI"), segments.map { it.type })
        assertEquals("Test comment", segments[1].fields.first { it.name == "comment" }.value)
        reader.close()
    }

    @Test
    fun `APP0 decodes JFIF header fields`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x10, 0x4a, 0x46,
            0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
            0x00, 0x48, 0x00, 0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP0", "EOI"), segments.map { it.type })
        val app0 = segments[1]
        assertEquals("1.1", app0.fields.first { it.name == "version" }.value)
        assertEquals("pixels/inch", app0.fields.first { it.name == "units" }.value)
        assertEquals("72", app0.fields.first { it.name == "x_density" }.value)
        assertEquals("72", app0.fields.first { it.name == "y_density" }.value)
        assertEquals("0", app0.fields.first { it.name == "x_thumbnail" }.value)
        assertEquals("0", app0.fields.first { it.name == "y_thumbnail" }.value)
        reader.close()
    }

    @Test
    fun `non-JFIF APP0 falls back to a plain structural node`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x0f, 0x4e, 0x4f,
            0x54, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x65, 0x78,
            0x74, 0x72, 0x61, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP0", "EOI"), segments.map { it.type })
        assertEquals(0, segments[1].fields.size)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — `COM` and `APP0` currently decode as generic structural nodes with no fields, so the `COM`/`APP0` field-decode tests fail (the fallback test passes already, but is included here for completeness/regression coverage once `decodeApp0` exists).

- [ ] **Step 3: Implement COM and APP0 decoding in `JpegWalker.kt`**

Change `decodeSegment` from:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

to:

```kotlin
private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        marker == 0xFE -> decodeCom(reader, name, offset, declaredSize, totalSize)
        marker == 0xE0 -> decodeApp0(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
}
```

Then add these functions at the end of the file:

```kotlin
private fun decodeCom(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val text = String(reader.readBytes(payloadStart, (payloadEnd - payloadStart).toInt()), Charsets.UTF_8)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("comment", text, payloadStart, payloadEnd - payloadStart)),
        summary = text,
    )
}

private val JFIF_PREFIX = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00) // "JFIF" + NUL

private fun decodeApp0(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val jfifBodySize = 9 // version(2) + units(1) + x_density(2) + y_density(2) + x_thumbnail(1) + y_thumbnail(1)
    if (payloadEnd - payloadStart >= JFIF_PREFIX.size + jfifBodySize &&
        reader.readBytes(payloadStart, JFIF_PREFIX.size).contentEquals(JFIF_PREFIX)
    ) {
        val bodyStart = payloadStart + JFIF_PREFIX.size
        val majorVersion = reader.readUInt8(bodyStart)
        val minorVersion = reader.readUInt8(bodyStart + 1)
        val units = reader.readUInt8(bodyStart + 2)
        val xDensity = reader.readUInt16(bodyStart + 3)
        val yDensity = reader.readUInt16(bodyStart + 5)
        val xThumbnail = reader.readUInt8(bodyStart + 7)
        val yThumbnail = reader.readUInt8(bodyStart + 8)
        val unitsLabel = when (units) {
            0 -> "none"
            1 -> "pixels/inch"
            2 -> "pixels/cm"
            else -> units.toString()
        }
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = listOf(
                BoxField("version", "$majorVersion.$minorVersion", bodyStart, 2),
                BoxField("units", unitsLabel, bodyStart + 2, 1),
                BoxField("x_density", xDensity.toString(), bodyStart + 3, 2),
                BoxField("y_density", yDensity.toString(), bodyStart + 5, 2),
                BoxField("x_thumbnail", xThumbnail.toString(), bodyStart + 7, 1),
                BoxField("y_thumbnail", yThumbnail.toString(), bodyStart + 8, 1),
            ),
            summary = "JFIF $majorVersion.$minorVersion, ${xDensity}x${yDensity} $unitsLabel",
        )
    }
    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS — all tests pass, including the 3 new COM/APP0 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: decode COM comment text and APP0 JFIF header fields"
```
