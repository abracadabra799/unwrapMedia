# HEIC Tile Grid ↔ Hex Data Mapping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** For grid-tiled HEIC/HEIF images, draw a clickable tile-boundary overlay on the primary image view; clicking a tile highlights that tile's real pixel-data byte range in the Hex viewer and shows a decoded preview of just that tile in a popup.

**Architecture:** A new pure decoder (`decodeGridItemPayload`) reads the HEIF `ImageGrid` item's raw bytes (rows/columns/output size). A new `findHeicTileGrid` combines that with the already-parsed `iref`/`dimg` reference (tile item IDs, in row-major order) and the first tile's `ispe` property (tile pixel size) into one `TileGridInfo`. The existing `HeifHevcThumbnail.kt` HEVC-item-extraction logic (written for the `thmb` reference specifically) is generalized to accept any item ID, so the same machinery decodes both thumbnails and individual tiles. `PixelInspectorPreview` gets an optional `tileGrid`/`onTileClick` pair (both default to no-op, so every other call site is unaffected) that draws the overlay and resolves taps to a tile item ID.

**Tech Stack:** Kotlin, Compose Desktop, ffmpeg (raw HEVC Annex-B decode, already-established pattern).

## Global Constraints

- No change in behavior for non-tiled images or any existing `PixelInspectorPreview` call site (thumbnail box, GIF filmstrip, Raw Pixel viewer) — new parameters default to `null`/no-op.
- Only the primary-image box in `ImageInspectorUI.kt` passes real `tileGrid`/`onTileClick` values.
- Tiles decode on click only, never all up front.
- `IlocBoxDecoder`'s existing `extent` node `offset`/`size` fields are not changed — the tile click path reads the already-computed absolute `offset`/`length` field *values* directly, via a separate code path.
- AVIF (`av01`-coded tiles) is out of scope for this plan.
- Real sample files confirmed to have a `grid` item + `dimg` reference, for manual verification: `~/Downloads/20260715_223828.heic`, `~/Downloads/20260715_223835.heic`, `~/Downloads/20260728/20260402_185008_IMG_0002.HEIC`.

---

### Task 1: `decodeGridItemPayload` — HEIF `ImageGrid` item payload decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt` (new)

**Interfaces:**
- Produces: `data class GridLayout(val rows: Int, val columns: Int, val outputWidth: Int, val outputHeight: Int)`, `fun decodeGridItemPayload(bytes: ByteArray): GridLayout?` — Task 3 calls this.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeicTileGridTest {
    @Test
    fun `decodeGridItemPayload reads a 1x2 grid with 16-bit output dimensions`() {
        // version=0, flags=0 (16-bit fields), rows_minus_one=0 (1 row), columns_minus_one=1 (2 cols),
        // output_width=32 (0x0020), output_height=16 (0x0010).
        val bytes = byteArrayOf(0, 0, 0, 1, 0x00, 0x20, 0x00, 0x10)
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16), layout)
    }

    @Test
    fun `decodeGridItemPayload reads a 3x1 grid with 32-bit output dimensions`() {
        // version=0, flags=1 (32-bit fields), rows_minus_one=2 (3 rows), columns_minus_one=0 (1 col),
        // output_width=300 (0x0000012C), output_height=200 (0x000000C8).
        val bytes = byteArrayOf(
            0, 1, 2, 0,
            0x00, 0x00, 0x01, 0x2C.toByte(),
            0x00, 0x00, 0x00, 0xC8.toByte(),
        )
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 3, columns = 1, outputWidth = 300, outputHeight = 200), layout)
    }

    @Test
    fun `decodeGridItemPayload returns null for input too short to contain the fixed header`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun `decodeGridItemPayload returns null when 32-bit fields are declared but truncated`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeicTileGridTest"`
Expected: FAIL to compile — `decodeGridItemPayload`/`GridLayout` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt`:

```kotlin
package com.multiviewer.parser

// HEIF "ImageGrid" item payload (ISO/IEC 23008-12 §6.6.3): a grid-derived image's own item data
// (referenced via iloc like any other item, but never itself a nested box) records how many
// tile rows/columns make up the image and the assembled canvas size.
data class GridLayout(val rows: Int, val columns: Int, val outputWidth: Int, val outputHeight: Int)

// byte 0 = version (unused -- this decoder, like IspeBoxDecoder, doesn't need to branch on it),
// byte 1 = flags (bit 0: 0 = 16-bit output_width/output_height fields, 1 = 32-bit),
// byte 2 = rows_minus_one, byte 3 = columns_minus_one,
// then output_width, output_height as 16-bit or 32-bit big-endian per the flags bit.
fun decodeGridItemPayload(bytes: ByteArray): GridLayout? {
    if (bytes.size < 4) return null
    val flags = bytes[1].toInt() and 0xFF
    val rows = (bytes[2].toInt() and 0xFF) + 1
    val columns = (bytes[3].toInt() and 0xFF) + 1
    val large = (flags and 1) == 1
    val fieldSize = if (large) 4 else 2
    if (bytes.size < 4 + fieldSize * 2) return null
    fun readUInt(offset: Int): Int = if (large) {
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    } else {
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
    val outputWidth = readUInt(4)
    val outputHeight = readUInt(4 + fieldSize)
    return GridLayout(rows, columns, outputWidth, outputHeight)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeicTileGridTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt
git commit -m "Add decodeGridItemPayload for HEIF ImageGrid item payloads"
```

---

### Task 2: Generalize `HeifHevcThumbnail.kt` to extract any item's HEVC bitstream

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/HeifHevcThumbnail.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/HeifHevcThumbnailTest.kt`

**Interfaces:**
- Produces: `fun extractHevcItemAnnexB(file: File, root: BoxNode, itemId: Long): ByteArray?` (new, public), `internal fun findItemProperty(meta: BoxNode, itemId: Long, propertyType: String): BoxNode?` and `internal fun extractItemBytes(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): ByteArray?` (visibility widened from `private` to `internal` — same two functions, same signatures, no behavior change) — Task 3 (`findHeicTileGrid`) calls both `internal` functions directly; Task 4 (`decodeHeicTileAsync`) calls `extractHevcItemAnnexB`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/parser/HeifHevcThumbnailTest.kt`, add this test at the end of the class (before the closing `}`):

```kotlin
    @Test
    fun `extractHevcItemAnnexB reconstructs a decodable stream for an arbitrary item ID, not just the thumbnail`() {
        val rawH265 = File.createTempFile("hevc-item-source-", ".h265")
        rawH265.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=16x16:duration=1",
            "-frames:v", "1", "-c:v", "libx265", "-x265-params", "log-level=none",
            "-f", "hevc", rawH265.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val nals = splitAnnexBNalUnits(rawH265.readBytes())
        val vps = nals.first { nalType(it) == 32 }
        val sps = nals.first { nalType(it) == 33 }
        val pps = nals.first { nalType(it) == 34 }
        val slice = nals.first { nalType(it) <= 31 }
        rawH265.delete()

        val hvcCPayload = buildHvcCPayload(vps, sps, pps, lengthSizeMinusOne = 3)
        val itemBytes = ByteArrayOutputStream().apply {
            write((slice.size shr 24) and 0xFF); write((slice.size shr 16) and 0xFF)
            write((slice.size shr 8) and 0xFF); write(slice.size and 0xFF)
            write(slice)
        }.toByteArray()

        val hvcCOffset = 0L
        val itemOffset = hvcCPayload.size.toLong()
        val fileBytes = hvcCPayload + itemBytes
        val file = File.createTempFile("heic-hevc-item-fixture-", ".heic")
        file.deleteOnExit()
        file.writeBytes(fileBytes)

        // Item ID 7 -- deliberately not 1 or the primary item ID, to prove this isn't secretly
        // still keyed on "the thumbnail" or "the primary item".
        val hvcC = BoxNode(type = "hvcC", offset = hvcCOffset, headerSize = 0, size = hvcCPayload.size.toLong())
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(hvcC))
        val ipmaItem = BoxNode(
            type = "item_7", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 0)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaItem))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))

        val extent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", itemOffset.toString(), 0, 0), BoxField("length", itemBytes.size.toString(), 0, 0)),
        )
        val ilocItem7 = BoxNode(
            type = "item_7", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(extent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem7))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iloc, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val annexB = extractHevcItemAnnexB(file, root, itemId = 7)
        assertNotNull(annexB, "Expected a reconstructed Annex-B HEVC stream")

        val reconstructed = File.createTempFile("hevc-item-reconstructed-", ".h265")
        reconstructed.deleteOnExit()
        reconstructed.writeBytes(annexB)
        val outPng = File.createTempFile("hevc-item-decoded-", ".png")
        outPng.deleteOnExit()
        val process = ProcessBuilder(
            "ffmpeg", "-y", "-f", "hevc", "-i", reconstructed.absolutePath, "-frames:v", "1", outPng.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.waitFor(10, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), "Expected ffmpeg to decode the reconstructed stream successfully")

        val decoded = javax.imageio.ImageIO.read(outPng)
        assertNotNull(decoded)
        assertEquals(16, decoded.width)
        assertEquals(16, decoded.height)

        file.delete()
        reconstructed.delete()
        outPng.delete()
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeifHevcThumbnailTest"`
Expected: FAIL to compile — `extractHevcItemAnnexB` is an unresolved reference. (The pre-existing `extractHevcThumbnailAnnexB` test still compiles and would still pass on its own; the new test is what fails.)

- [ ] **Step 3: Generalize the implementation**

In `app/src/main/kotlin/com/multiviewer/parser/HeifHevcThumbnail.kt`, find:

```kotlin
fun extractHevcThumbnailAnnexB(file: File, root: BoxNode): ByteArray? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" }
    val iref = findFirst(meta) { it.type == "iref" }
    val pitm = findFirst(meta) { it.type == "pitm" }
    val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull() ?: return null

    val thumbId = iref?.children
        ?.filter { it.type == "thmb" }
        ?.firstNotNullOfOrNull { ref ->
            val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
            val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
            if (fromId != null && toIds.contains(primaryId)) fromId else null
        } ?: return null

    val itemType = iinf?.children
        ?.find { it.type == "infe" && it.fields.find { f -> f.name == "item_ID" }?.value?.toLongOrNull() == thumbId }
        ?.fields?.find { it.name == "item_type" }?.value
    if (itemType?.lowercase() != "hvc1") return null

    val hvcC = findItemProperty(meta, thumbId, "hvcC") ?: return null
    val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L

    return try {
        ByteReader.open(file).use { reader ->
            val hvcCInfo = readHvcCInfo(reader, hvcC) ?: return@use null
            val itemBytes = extractItemBytes(reader, iloc, thumbId, idatBase) ?: return@use null
            val pictureAnnexB = convertLengthPrefixedToAnnexB(itemBytes, hvcCInfo.lengthSize)
            if (pictureAnnexB.isEmpty()) null else hvcCInfo.parameterSetsAnnexB + pictureAnnexB
        }
    } catch (e: Exception) {
        null
    }
}
```

Replace with:

```kotlin
fun extractHevcThumbnailAnnexB(file: File, root: BoxNode): ByteArray? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" }
    val iref = findFirst(meta) { it.type == "iref" }
    val pitm = findFirst(meta) { it.type == "pitm" }
    val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull() ?: return null

    val thumbId = iref?.children
        ?.filter { it.type == "thmb" }
        ?.firstNotNullOfOrNull { ref ->
            val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
            val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
            if (fromId != null && toIds.contains(primaryId)) fromId else null
        } ?: return null

    val itemType = iinf?.children
        ?.find { it.type == "infe" && it.fields.find { f -> f.name == "item_ID" }?.value?.toLongOrNull() == thumbId }
        ?.fields?.find { it.name == "item_type" }?.value
    if (itemType?.lowercase() != "hvc1") return null

    return extractHevcItemAnnexB(file, root, thumbId)
}

// Generalized from extractHevcThumbnailAnnexB (originally hardcoded to the "thmb" iref reference)
// so any item ID -- e.g. one of a grid-tiled image's individual tile items -- can be extracted the
// same way. Callers resolve their own item ID first (extractHevcThumbnailAnnexB does so via
// "thmb"; tile extraction does so via "dimg", see HeicTileGrid.kt).
fun extractHevcItemAnnexB(file: File, root: BoxNode, itemId: Long): ByteArray? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val hvcC = findItemProperty(meta, itemId, "hvcC") ?: return null
    val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L

    return try {
        ByteReader.open(file).use { reader ->
            val hvcCInfo = readHvcCInfo(reader, hvcC) ?: return@use null
            val itemBytes = extractItemBytes(reader, iloc, itemId, idatBase) ?: return@use null
            val pictureAnnexB = convertLengthPrefixedToAnnexB(itemBytes, hvcCInfo.lengthSize)
            if (pictureAnnexB.isEmpty()) null else hvcCInfo.parameterSetsAnnexB + pictureAnnexB
        }
    } catch (e: Exception) {
        null
    }
}
```

Then find both remaining `private fun` declarations:

```kotlin
private fun findItemProperty(meta: BoxNode, itemId: Long, propertyType: String): BoxNode? {
```

Replace with:

```kotlin
internal fun findItemProperty(meta: BoxNode, itemId: Long, propertyType: String): BoxNode? {
```

And find:

```kotlin
private fun extractItemBytes(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): ByteArray? {
```

Replace with:

```kotlin
internal fun extractItemBytes(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): ByteArray? {
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeifHevcThumbnailTest"`
Expected: PASS (2 tests — the pre-existing thumbnail test and the new item test).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HeifHevcThumbnail.kt app/src/test/kotlin/com/multiviewer/parser/HeifHevcThumbnailTest.kt
git commit -m "Generalize HEVC item extraction to any item ID, not just the thumbnail"
```

---

### Task 3: `findHeicTileGrid` — resolve a file's tile grid structure

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt`

**Interfaces:**
- Consumes: `GridLayout`, `decodeGridItemPayload` (Task 1, same file); `internal findItemProperty`, `internal extractItemBytes` (Task 2, same package, no import needed).
- Produces: `data class TileGridInfo(val layout: GridLayout, val tileItemIds: List<Long>, val tileWidth: Int, val tileHeight: Int)`, `fun findHeicTileGrid(file: File, root: BoxNode): TileGridInfo?` — Task 6 calls this from `ImageInspectorUI.kt`.

- [ ] **Step 1: Write the failing test**

In `app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt`, add (adjust imports at the top of the file to include `java.io.File` and `kotlin.test.assertEquals`, `kotlin.test.assertNull` already present):

```kotlin
import java.io.File
```

Add this test to the `HeicTileGridTest` class:

```kotlin
    @Test
    fun `findHeicTileGrid resolves a 1x2 grid's layout, tile item IDs, and tile size`() {
        // Grid item (item_ID=3, type "grid") whose own iloc extent points at grid-payload bytes;
        // iref's dimg says 3 -> [1, 2] (row-major tile order); item 1 has an ispe of 16x16.
        // Tile items' own picture data doesn't matter here -- findHeicTileGrid never decodes
        // pixels, only structure (extractHevcItemAnnexB, tested separately in Task 2, covers
        // actually decoding a tile).
        val gridPayload = byteArrayOf(0, 0, 0, 1, 0x00, 0x20, 0x00, 0x10) // 1 row, 2 cols, 32x16 output
        val file = File.createTempFile("heic-tile-grid-fixture-", ".heic")
        file.deleteOnExit()
        file.writeBytes(gridPayload)

        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "16", 0, 0), BoxField("image_height", "16", 0, 0)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val ipmaItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 0)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaItem1))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))

        val gridExtent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "0", 0, 0), BoxField("length", gridPayload.size.toString(), 0, 0)),
        )
        val ilocItem3 = BoxNode(
            type = "item_3", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(gridExtent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem3))

        val infeGrid = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "3", 0, 0), BoxField("item_type", "grid", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infeGrid))

        val dimg = BoxNode(
            type = "dimg", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("from_item_ID", "3", 0, 0),
                BoxField("to_item_ID[0]", "1", 0, 0),
                BoxField("to_item_ID[1]", "2", 0, 0),
            ),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(dimg))

        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iloc, iinf, iref, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val result = findHeicTileGrid(file, root)

        assertEquals(
            TileGridInfo(
                layout = GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16),
                tileItemIds = listOf(1L, 2L),
                tileWidth = 16,
                tileHeight = 16,
            ),
            result,
        )
        file.delete()
    }

    @Test
    fun `findHeicTileGrid returns null when the file has no grid item`() {
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = emptyList())
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))
        assertNull(findHeicTileGrid(File("/nonexistent/does-not-matter.heic"), root))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeicTileGridTest"`
Expected: FAIL to compile — `findHeicTileGrid`/`TileGridInfo` are unresolved references.

- [ ] **Step 3: Write the implementation**

In `app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt`, add at the end of the file:

```kotlin
data class TileGridInfo(val layout: GridLayout, val tileItemIds: List<Long>, val tileWidth: Int, val tileHeight: Int)

// Combines three already-parsed pieces into one lookup: which item is the grid, what its own
// row/column/output-size payload says (decodeGridItemPayload above), and which tile items it
// references (iref's "dimg", in row-major order per the HEIF spec) -- plus each tile's pixel size
// from the first tile's own ispe property (HEIF requires uniform tile size except naturally-
// cropped right/bottom edge tiles, so the first tile is representative). Returns null at any
// missing piece, mirroring extractHevcThumbnailAnnexB's own all-or-nothing style.
fun findHeicTileGrid(file: File, root: BoxNode): TileGridInfo? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" } ?: return null
    val iref = findFirst(meta) { it.type == "iref" } ?: return null

    val gridItemId = iinf.children
        .find { it.type == "infe" && it.fields.find { f -> f.name == "item_type" }?.value == "grid" }
        ?.fields?.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: return null

    val tileItemIds = iref.children
        .find { it.type == "dimg" && it.fields.find { f -> f.name == "from_item_ID" }?.value?.toLongOrNull() == gridItemId }
        ?.fields?.filter { it.name.startsWith("to_item_ID") }?.mapNotNull { it.value.toLongOrNull() }
        ?: return null
    if (tileItemIds.isEmpty()) return null

    val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L
    val layout = try {
        ByteReader.open(file).use { reader ->
            val gridBytes = extractItemBytes(reader, iloc, gridItemId, idatBase) ?: return@use null
            decodeGridItemPayload(gridBytes)
        }
    } catch (e: Exception) {
        null
    } ?: return null

    val firstTileId = tileItemIds.first()
    val ispe = findItemProperty(meta, firstTileId, "ispe") ?: return null
    val tileWidth = ispe.fields.find { it.name == "image_width" }?.value?.toIntOrNull() ?: return null
    val tileHeight = ispe.fields.find { it.name == "image_height" }?.value?.toIntOrNull() ?: return null

    return TileGridInfo(layout, tileItemIds, tileWidth, tileHeight)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.parser.HeicTileGridTest"`
Expected: PASS (6 tests — 4 from Task 1, 2 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HeicTileGrid.kt app/src/test/kotlin/com/multiviewer/parser/HeicTileGridTest.kt
git commit -m "Add findHeicTileGrid to resolve a HEIC's tile grid structure"
```

---

### Task 4: `FfmpegImageSnapshotDecoder.decodeHeicTileAsync`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`

**Interfaces:**
- Consumes: `extractHevcItemAnnexB` (Task 2, `com.multiviewer.parser` package — needs a new import in this file).
- Produces: `fun decodeHeicTileAsync(file: File, root: BoxNode, itemId: Long, onResult: (ImageBitmap?) -> Unit)` — Task 6 calls this from `ImageInspectorUI.kt`.

- [ ] **Step 1: Add the import**

In `app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt`, find:

```kotlin
import com.multiviewer.parser.extractHevcThumbnailAnnexB
```

Replace with:

```kotlin
import com.multiviewer.parser.extractHevcItemAnnexB
import com.multiviewer.parser.extractHevcThumbnailAnnexB
```

- [ ] **Step 2: Add `decodeHeicTileAsync`**

In the same file, find:

```kotlin
    // Shared "ffmpeg <inputArgs> -> one PNG frame -> Skia decode" pipeline. Runs synchronously on
    // the caller's own thread (both call sites above already run off a dedicated background Thread).
    private fun decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap? {
```

Replace with:

```kotlin
    // Same pipeline as decodeEmbeddedHevcThumbnailAsync above, but for an arbitrary tile item ID
    // (see HeicTileGrid.kt's findHeicTileGrid) instead of the "thmb" thumbnail item -- decodes just
    // the one tile the user clicked, not the whole grid.
    fun decodeHeicTileAsync(file: File, root: BoxNode, itemId: Long, onResult: (ImageBitmap?) -> Unit) {
        Thread {
            val annexB = try {
                extractHevcItemAnnexB(file, root, itemId)
            } catch (e: Exception) {
                null
            }
            if (annexB == null) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            val tempH265 = try {
                File.createTempFile("hevc-tile-item-", ".h265")
            } catch (e: Exception) {
                EventQueue.invokeLater { onResult(null) }
                return@Thread
            }
            tempH265.deleteOnExit()
            val result = try {
                tempH265.writeBytes(annexB)
                decodeSingleFrameToBitmap(
                    listOf(FfmpegLocator.ffmpegPath(), "-y", "-f", "hevc", "-i", tempH265.absolutePath, "-frames:v", "1", "-update", "1"),
                )
            } finally {
                tempH265.delete()
            }
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Shared "ffmpeg <inputArgs> -> one PNG frame -> Skia decode" pipeline. Runs synchronously on
    // the caller's own thread (both call sites above already run off a dedicated background Thread).
    private fun decodeSingleFrameToBitmap(inputArgs: List<String>): ImageBitmap? {
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions. (No new test for this function itself — `decodeEmbeddedHevcThumbnailAsync`, its direct sibling, has none either; both are thin `Thread`/`EventQueue` wrappers around already-tested pieces, verified manually in Task 7 like the thumbnail path already is.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FfmpegImageSnapshotDecoder.kt
git commit -m "Add decodeHeicTileAsync to decode a single clicked tile"
```

---

### Task 5: `TileGridOverlay` — pure tap resolution + Canvas drawing

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/TileGridOverlay.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/TileGridOverlayTest.kt` (new)

**Interfaces:**
- Consumes: `TileGridInfo` (Task 3, `com.multiviewer.parser` package).
- Produces: `fun resolveTileAt(tapPosition: Offset, nativeSize: Size, boxSize: Size, tileGrid: TileGridInfo): Long?`, `@Composable fun TileGridOverlay(tileGrid: TileGridInfo, nativeSize: Size, modifier: Modifier = Modifier)` — Task 6 wires both into `PixelInspectorPreview.kt`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/TileGridOverlayTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.multiviewer.parser.GridLayout
import com.multiviewer.parser.TileGridInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileGridOverlayTest {
    // A 1-row, 2-column grid of 16x16 tiles (32x16 output), fit into a 320x160 box -- 10x fit scale,
    // so each tile occupies a 160x160 screen-space rectangle at that scale.
    private val tileGrid = TileGridInfo(
        layout = GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16),
        tileItemIds = listOf(101L, 102L),
        tileWidth = 16,
        tileHeight = 16,
    )
    private val nativeSize = Size(32f, 16f)
    private val boxSize = Size(320f, 160f)

    @Test
    fun `resolveTileAt returns the first tile's item ID for a tap in the left half`() {
        assertEquals(101L, resolveTileAt(Offset(50f, 50f), nativeSize, boxSize, tileGrid))
    }

    @Test
    fun `resolveTileAt returns the second tile's item ID for a tap in the right half`() {
        assertEquals(102L, resolveTileAt(Offset(250f, 50f), nativeSize, boxSize, tileGrid))
    }

    @Test
    fun `resolveTileAt returns null for a tap outside the fitted image bounds`() {
        assertNull(resolveTileAt(Offset(-10f, 50f), nativeSize, boxSize, tileGrid))
        assertNull(resolveTileAt(Offset(50f, 5000f), nativeSize, boxSize, tileGrid))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.TileGridOverlayTest"`
Expected: FAIL to compile — `resolveTileAt` is an unresolved reference.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/kotlin/com/multiviewer/ui/TileGridOverlay.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.multiviewer.parser.TileGridInfo

private val TILE_GRID_LINE_COLOR = Color.White // caller applies AppColors.NeonPurple via tint if desired; see PixelInspectorPreview wiring

// Resolves a tap (in the same Box-local coordinate space PixelInspectorPreview's own gestures use)
// to the tile item ID at that point, at plain fit-scale (the caller's own graphicsLayer handles
// zoom/pan, exactly as PixelGridOverlay.kt's drawing does) -- null if the tap landed outside the
// fitted image bounds (letterboxed margin) or, degenerately, outside the grid's own dimensions.
fun resolveTileAt(tapPosition: Offset, nativeSize: Size, boxSize: Size, tileGrid: TileGridInfo): Long? {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return null
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    val fittedWidth = nativeSize.width * fitScale
    val fittedHeight = nativeSize.height * fitScale
    val left = (boxSize.width - fittedWidth) / 2f
    val top = (boxSize.height - fittedHeight) / 2f

    val localX = tapPosition.x - left
    val localY = tapPosition.y - top
    if (localX < 0f || localY < 0f || localX >= fittedWidth || localY >= fittedHeight) return null

    val nativeX = localX / fitScale
    val nativeY = localY / fitScale
    val column = (nativeX / tileGrid.tileWidth).toInt()
    val row = (nativeY / tileGrid.tileHeight).toInt()
    if (row !in 0 until tileGrid.layout.rows || column !in 0 until tileGrid.layout.columns) return null

    val index = row * tileGrid.layout.columns + column
    return tileGrid.tileItemIds.getOrNull(index)
}

// Draws one rectangle per tile, in this Canvas's own untransformed coordinate space -- a caller
// that zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this
// composable as it applies to its own Image, the same pattern PixelGridOverlay.kt already
// establishes, so the overlay tracks the zoomed image with no zoom-aware drawing logic here.
@Composable
fun TileGridOverlay(tileGrid: TileGridInfo, nativeSize: Size, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (nativeSize.width <= 0f || nativeSize.height <= 0f) return@Canvas
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        for (row in 0 until tileGrid.layout.rows) {
            for (column in 0 until tileGrid.layout.columns) {
                val tileLeft = left + column * tileGrid.tileWidth * fitScale
                val tileTop = top + row * tileGrid.tileHeight * fitScale
                val tileRight = (left + (column + 1) * tileGrid.tileWidth * fitScale).coerceAtMost(left + fittedWidth)
                val tileBottom = (top + (row + 1) * tileGrid.tileHeight * fitScale).coerceAtMost(top + fittedHeight)
                drawRect(
                    color = TILE_GRID_LINE_COLOR,
                    topLeft = Offset(tileLeft, tileTop),
                    size = Size(tileRight - tileLeft, tileBottom - tileTop),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:test --tests "com.multiviewer.ui.TileGridOverlayTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/TileGridOverlay.kt app/src/test/kotlin/com/multiviewer/ui/TileGridOverlayTest.kt
git commit -m "Add TileGridOverlay and resolveTileAt for HEIC tile-grid selection"
```

---

### Task 6: Wire tile overlay, hex highlight, and preview popup into the app

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `TileGridOverlay`, `resolveTileAt` (Task 5); `findHeicTileGrid`, `TileGridInfo` (Task 3); `FfmpegImageSnapshotDecoder.decodeHeicTileAsync` (Task 4) — all same package or already-imported package (`com.multiviewer.parser.*` is wildcard-imported in files that need it, per existing convention; add explicit imports only where a file doesn't already wildcard-import that package).

- [ ] **Step 1: Add `tileGrid`/`onTileClick` parameters to `PixelInspectorPreview`**

In `app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt`, find:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier, resetKey: Any = bitmap) {
```

Replace with:

```kotlin
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    resetKey: Any = bitmap,
    tileGrid: com.multiviewer.parser.TileGridInfo? = null,
    onTileClick: ((itemId: Long) -> Unit)? = null,
) {
```

Then find:

```kotlin
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tapPosition -> offset = panToPoint(offset, boxSize, scale, tapPosition) },
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
```

Replace with:

```kotlin
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tapPosition ->
                        if (tileGrid != null) {
                            resolveTileAt(tapPosition, Size(bitmap.width.toFloat(), bitmap.height.toFloat()), boxSize, tileGrid)
                                ?.let { onTileClick?.invoke(it) }
                        }
                        offset = panToPoint(offset, boxSize, scale, tapPosition)
                    },
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
```

Then find (the closing of the pixel grid overlay block, right before the final two closing braces of the function):

```kotlin
        if (LocalShowPixelGrid.current) {
            PixelGridOverlay(
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                scale = scale,
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
    }
}
```

Replace with:

```kotlin
        if (LocalShowPixelGrid.current) {
            PixelGridOverlay(
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                scale = scale,
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
        if (tileGrid != null) {
            TileGridOverlay(
                tileGrid = tileGrid,
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Add tile-grid state to `TabState`**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, find:

```kotlin
    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
```

Replace with:

```kotlin
    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var motionPhotoPreview: EmbeddedVideo? by mutableStateOf(null)
    // Set once (per file, in ImageInspectorUI's LaunchedEffect below) when the open HEIC/HEIF has a
    // grid-tiled structure -- null for every other file, which is what gates PixelInspectorPreview's
    // new tile overlay off entirely for the overwhelming majority of images.
    var tileGrid: com.multiviewer.parser.TileGridInfo? by mutableStateOf(null)
    // The most recently clicked tile's real pixel-data byte range (see findHeicTileGrid + the iloc
    // extent's own offset/length field values) -- takes priority over tree-node selection in
    // Main.kt's Hex viewer highlight, matching activeField's existing precedence over plain node
    // selection.
    var tileHighlightRange: LongRange? by mutableStateOf(null)
    var selectedTileItemId: Long? by mutableStateOf(null)
    var selectedTileBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
```

- [ ] **Step 4: Compute `tileGrid` and handle tile clicks in `ImageInspectorUI.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`, find:

```kotlin
@Composable
fun ImageInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    val forensic = tab.imageForensic ?: return
```

Replace with:

```kotlin
@Composable
fun ImageInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    val forensic = tab.imageForensic ?: return

    LaunchedEffect(tab.file, tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        tab.tileGrid = withContext(Dispatchers.IO) { com.multiviewer.parser.findHeicTileGrid(tab.file, root) }
    }

    fun onTileClick(itemId: Long) {
        val root = tab.root ?: return
        val iloc = com.multiviewer.parser.findFirst(root) { it.type == "meta" }
            ?.let { meta -> com.multiviewer.parser.findFirst(meta) { it.type == "iloc" } }
        val extent = iloc?.children?.find { it.type == "item_$itemId" }?.children?.firstOrNull()
        val offset = extent?.fields?.find { it.name == "offset" || it.name == "idat_relative_offset" }?.value?.toLongOrNull()
        val length = extent?.fields?.find { it.name == "length" }?.value?.toLongOrNull()
        tab.tileHighlightRange = if (offset != null && length != null) offset until (offset + length) else null
        tab.selectedTileItemId = itemId
        tab.selectedTileBitmap = null
        FfmpegImageSnapshotDecoder.decodeHeicTileAsync(tab.file, root, itemId) { bitmap ->
            if (tab.selectedTileItemId == itemId) tab.selectedTileBitmap = bitmap
        }
    }
```

Then find the primary image box's `Image`/`PixelInspectorPreview` call:

```kotlin
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback) {
```

Replace with:

```kotlin
                        forensic.bitmap?.let {
                            PixelInspectorPreview(it, tileGrid = tab.tileGrid, onTileClick = ::onTileClick)
                        } ?: if (forensic.isDecodingFallback) {
```

Then find the end of the `ImageInspectorUI` function (its closing brace) to add the tile-preview popup. Find:

```kotlin
private enum class DetailPanelTab { OVERVIEW, DETAIL }
```

Replace with:

```kotlin
tab.selectedTileItemId?.let { itemId ->
    androidx.compose.ui.window.Dialog(onDismissRequest = { tab.selectedTileItemId = null; tab.tileHighlightRange = null }) {
        Column(
            modifier = Modifier
                .background(AppColors.Surface, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Border, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            Text("Tile item $itemId", style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = AppColors.NeonPurple))
            Spacer(Modifier.height(8.dp))
            val tileBitmap = tab.selectedTileBitmap
            if (tileBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = tileBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(200.dp),
                )
            } else {
                Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private enum class DetailPanelTab { OVERVIEW, DETAIL }
```

- [ ] **Step 5: Fold `tileHighlightRange` into the Hex viewer's highlight precedence in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            HexView(
                                file = currentTab.file,
                                highlightRange = activeField?.let { it.offset until (it.offset + it.length) }
                                    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                listState = hexListState,
                            )
                        }
```

Replace with:

```kotlin
                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            HexView(
                                file = currentTab.file,
                                highlightRange = currentTab.tileHighlightRange
                                    ?: activeField?.let { it.offset until (it.offset + it.length) }
                                    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                listState = hexListState,
                            )
                        }
```

Then find:

```kotlin
                        LaunchedEffect(currentTab.selected, currentTab.selectedField) {
                            val field = activeField
                            if (field != null) {
                                hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                            } else {
                                currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }
```

Replace with:

```kotlin
                        LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange) {
                            val tileRange = currentTab.tileHighlightRange
                            val field = activeField
                            when {
                                tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
                                field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                                else -> currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileKotlin`
Expected: `BUILD SUCCESSFUL`. If `findFirst` is unresolved in `ImageInspectorUI.kt`'s new `onTileClick` function, confirm it's exposed as `com.multiviewer.parser.findFirst` (it's already used the same way, unqualified, inside `com.multiviewer.parser` package files like `HeifHevcThumbnail.kt` — from `ImageInspectorUI.kt`, which is in `com.multiviewer.ui` and already does `import com.multiviewer.parser.*`, the fully-qualified form used above works regardless either way).

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew :app:test`
Expected: `BUILD SUCCESSFUL`, all tests pass, no regressions.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/PixelInspectorPreview.kt app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Wire HEIC tile grid overlay, hex highlight, and tile preview popup"
```

---

### Task 7: Manual verification

**Files:** None (no code changes).

- [ ] **Step 1: Run the app**

```bash
./gradlew :app:run
```

- [ ] **Step 2: Verify against real tiled HEIC files**

Open each of:
- `~/Downloads/20260715_223828.heic`
- `~/Downloads/20260715_223835.heic`
- `~/Downloads/20260728/20260402_185008_IMG_0002.HEIC`

For each: confirm the PRIMARY IMAGE VIEW shows a purple tile-boundary grid over the image. Click a tile: confirm a popup appears showing a small decoded preview of just that tile, and the Hex & Raw Data Viewer scrolls to and highlights that tile's actual pixel-data bytes (not the small iloc metadata entry describing them). Close the popup (click outside or dismiss) and confirm the highlight clears/reverts to tree-selection behavior.

- [ ] **Step 3: Verify no regression for non-tiled images**

Open a normal (non-tiled) JPEG or HEIC. Confirm no tile overlay appears and zoom/pan/pixel-grid behavior is unchanged from before this plan.

- [ ] **Step 4: Report**

Note in the progress ledger (`.git/sdd/progress.md`) what was actually confirmed. If GUI interaction isn't reliable in the current environment (documented history of this sandbox), say so explicitly rather than claiming a full interactive pass; Tasks 1-6's automated tests (including the real-encoder-NAL-unit-based HEVC roundtrip tests), compiles, and full-suite passes stand as code-level confirmation regardless.
