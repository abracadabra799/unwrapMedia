# EXIF / XMP / Samsung MakerNote Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse `iloc` (Item Location Box), fix `infe`'s handling of `mime`-typed items' `content_type`, decode EXIF (TIFF/IFD0/Exif SubIFD/GPS IFD/Samsung MakerNote) and XMP (RDF/XML text) content, and have `meta` show both inline in the tree.

**Architecture:** `InfeBoxDecoder` gets a small, backward-compatible fix (Task 1). `IlocBoxDecoder` is a new, self-contained decoder producing structural offset/length info per item/extent, with no awareness of item types (Task 2). `ExifDecoder` is a new plain function (not a registered `BoxDecoder`) that walks TIFF/IFD structure given a raw byte range (Task 3). `MetaBoxDecoder` is enhanced to cross-reference its already-parsed `iinf`/`iloc`/`idat` children — the one place with visibility into all three — resolving `idat`-relative offsets and calling `ExifDecoder`/decoding XMP text for the relevant items (Task 4).

**Tech Stack:** Kotlin, JVM. Tests use `kotlin.test` + the existing `byteReaderOf(bytes: ByteArray): ByteReader` helper. Every test byte array in this plan was independently verified byte-for-byte using a Python simulation of the exact same encoding before being written here.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-18-exif-xmp-makernote-design.md`
- `decode(...)` signature for new `BoxDecoder`s must exactly match the interface: `decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode`.
- `payloadStart = offset + headerSize`, `payloadEnd = offset + size` — the exact convention every existing decoder uses.
- Undersized/malformed bail-out convention (copy exactly): add a warning, `return BoxNode(type, offset, headerSize, size, warnings = w)` — no fields, no children, no summary.
- Declared-vs-found truncation convention: loop bounds checked before every read, warning added if declared count exceeds actual found count — this project has previously shipped a real bug where a count was silently defaulted to 0 with no warning on truncated input; do not repeat it.
- `iloc` construction_method 2 ("item offset") is unsupported — add a warning naming the item, show raw unresolved fields.
- An `iloc` item with more than one extent is not decoded as Exif/XMP content (structural offset/length info only) — combining multiple non-contiguous extents into one logical byte stream is out of scope; every item in the real sample file has exactly one extent.
- Samsung MakerNote tag names are the subset documented at the public, community-maintained exiv2.org reference — unrecognized tags (in any of the four tag tables: IFD0, Exif SubIFD, GPS IFD, MakerNote) display as `Tag 0xXXXX`, never guessed at.
- No JPEG/PNG file support (this tool doesn't open raw JPEG/PNG files) — only the HEIC/ISOBMFF `meta`/`iinf`/`iloc` path.
- No new UI-layer tests — this project's UI layer has no test suite by design.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Fix `InfeBoxDecoder` to read `content_type` for `mime` items

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/InfeBoxDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/InfeBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` (existing, same package).
- Produces: `InfeBoxDecoder` now adds a `content_type` `BoxField` when `item_type == "mime"`. Later tasks (Task 4) read this field by name (`"content_type"`) from an already-decoded `infe` `BoxNode`'s `fields` list — this exact field name is required for that lookup to work.

- [ ] **Step 1: Write the failing test**

Add this test to the existing `InfeBoxDecoderTest.kt` (alongside its current three tests):

```kotlin
    @Test
    fun `mime item_type reads content_type after item_name`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00) + // version=2, flags=0
            byteArrayOf(0x00, 0x32) +                     // item_ID = 50
            byteArrayOf(0x00, 0x00) +                     // item_protection_index = 0
            "mime".toByteArray() +                        // item_type
            byteArrayOf(0) +                              // item_name = "" (empty, null-terminated)
            "application/rdf+xml".toByteArray() + byteArrayOf(0) // content_type, null-terminated
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())

        assertEquals("50", node.fields[0].value)
        assertEquals("mime", node.fields[2].value)
        assertEquals("", node.fields[3].value)
        assertEquals("application/rdf+xml", node.fields[4].value)
        reader.close()
    }
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests InfeBoxDecoderTest
```
Expected: FAIL — `node.fields[4]` doesn't exist (only 4 fields currently produced).

- [ ] **Step 3: Replace the full content of `InfeBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object InfeBoxDecoder : BoxDecoder {
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
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        if (version < 2) {
            w.add("Unsupported infe version $version")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemIdWidth = if (version == 2) 2 else 4
        val needed = 4 + itemIdWidth + 2 + 4
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for infe version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemIdOffset = payloadStart + 4
        val itemId = if (itemIdWidth == 2) {
            reader.readUInt16(itemIdOffset).toLong()
        } else {
            reader.readUInt32(itemIdOffset)
        }
        val protectionIndexOffset = itemIdOffset + itemIdWidth
        val protectionIndex = reader.readUInt16(protectionIndexOffset)
        val itemTypeOffset = protectionIndexOffset + 2
        val itemType = reader.readFourCC(itemTypeOffset)
        val nameOffset = itemTypeOffset + 4
        val remainingBytes = reader.readBytes(nameOffset, (payloadEnd - nameOffset).toInt())
        val nameNullIndex = remainingBytes.indexOf(0)
        val nameLength = if (nameNullIndex >= 0) nameNullIndex else remainingBytes.size
        val itemName = String(remainingBytes, 0, nameLength, Charsets.UTF_8)

        val fields = mutableListOf(
            BoxField("item_ID", itemId.toString(), itemIdOffset, itemIdWidth.toLong()),
            BoxField("item_protection_index", protectionIndex.toString(), protectionIndexOffset, 2),
            BoxField("item_type", itemType, itemTypeOffset, 4),
            BoxField("item_name", itemName, nameOffset, nameLength.toLong()),
        )

        var summary = "$itemType: $itemName"
        if (itemType == "mime" && nameNullIndex >= 0) {
            val contentTypeStart = nameNullIndex + 1
            val contentTypeBytes = remainingBytes.copyOfRange(contentTypeStart, remainingBytes.size)
            val contentTypeNullIndex = contentTypeBytes.indexOf(0)
            val contentTypeLength = if (contentTypeNullIndex >= 0) contentTypeNullIndex else contentTypeBytes.size
            val contentType = String(contentTypeBytes, 0, contentTypeLength, Charsets.UTF_8)
            fields.add(BoxField("content_type", contentType, nameOffset + contentTypeStart, contentTypeLength.toLong()))
            summary = "$itemType ($contentType): $itemName"
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = summary,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests InfeBoxDecoderTest
```
Expected: PASS (4/4 — the 3 existing tests plus the new one).

- [ ] **Step 5: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/InfeBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/InfeBoxDecoderTest.kt
git commit -m "fix(parser): read content_type for mime-typed infe items"
```

---

### Task 2: `IlocBoxDecoder` (Item Location Box)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/IlocBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/IlocBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `readUIntOfWidth` (in `BinaryUtil.kt`, internal, same package), `pluralize` (in `BinaryUtil.kt`, internal, same package) — all existing.
- Produces: `object IlocBoxDecoder : BoxDecoder`, registered for `"iloc"`. Each decoded item child node is named `"item_$itemId"` (e.g. `"item_49"`) with a `"construction_method"` field, and one extent-child per extent named `"extent"`. Extent nodes have fields named `"offset"`/`"length"` (construction_method 0), `"idat_relative_offset"`/`"length"` (construction_method 1), or `"base_offset"`/`"extent_offset"`/`"length"` (construction_method 2, unresolved) — **these exact field names are required** for Task 4's `MetaBoxDecoder` enhancement to find and resolve/replace them by name.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IlocBoxDecoderTest {
    @Test
    fun `decodes construction_method 0, 1 and 2 with correct extent resolution`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,             // version=1, flags=0
            0x44,                               // offset_size=4, length_size=4
            0x00,                               // base_offset_size=0, index_size=0
            0x00, 0x03,                         // item_count = 3
            // item 1: construction_method=0, base_offset=0, extent=(1000, 500)
            0x00, 0x01,                         // item_ID = 1
            0x00, 0x00,                         // construction_method = 0
            0x00, 0x01,                         // data_reference_index = 1
            0x00, 0x01,                         // extent_count = 1
            0x00, 0x00, 0x03, 0xe8.toByte(),    // extent_offset = 1000
            0x00, 0x00, 0x01, 0xf4.toByte(),    // extent_length = 500
            // item 2: construction_method=1, base_offset=0, extent=(10, 20)
            0x00, 0x02,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0a,
            0x00, 0x00, 0x00, 0x14,
            // item 3: construction_method=2, base_offset=0, extent=(5, 5)
            0x00, 0x03,
            0x00, 0x02,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x05,
            0x00, 0x00, 0x00, 0x05,
        )
        val reader = byteReaderOf(body)
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, body.size.toLong(), emptyList())

        assertEquals(3, node.children.size)
        assertEquals("item_1", node.children[0].type)
        assertEquals("0", node.children[0].fields[0].value)
        assertEquals("1000", node.children[0].children[0].fields.first { it.name == "offset" }.value)
        assertEquals("500", node.children[0].children[0].fields.first { it.name == "length" }.value)

        assertEquals("item_2", node.children[1].type)
        assertEquals("1", node.children[1].fields[0].value)
        assertEquals("10", node.children[1].children[0].fields.first { it.name == "idat_relative_offset" }.value)

        assertEquals("item_3", node.children[2].type)
        assertEquals(1, node.warnings.size)
        assertEquals("5", node.children[2].children[0].fields.first { it.name == "base_offset" || it.name == "extent_offset" }.value)

        assertEquals("3 items", node.summary)
        reader.close()
    }

    @Test
    fun `declared item_count larger than available data truncates with a warning`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,
            0x44,
            0x00,
            0x00, 0x02,                         // item_count = 2 (only 1 fits)
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x64.toByte(),
            0x00, 0x00, 0x00, 0x32,
        )
        val reader = byteReaderOf(body)
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals(1, node.warnings.size)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header and size fields returns a warning and no children`() {
        val reader = byteReaderOf(ByteArray(4))
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, 4, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests IlocBoxDecoderTest
```
Expected: FAIL — `IlocBoxDecoder` is unresolved.

- [ ] **Step 3: Create `IlocBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object IlocBoxDecoder : BoxDecoder {
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
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short for a FullBox header and iloc size fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val sizesByte1 = reader.readUInt8(payloadStart + 4)
        val offsetSize = (sizesByte1 shr 4) and 0xF
        val lengthSize = sizesByte1 and 0xF
        val sizesByte2 = reader.readUInt8(payloadStart + 5)
        val baseOffsetSize = (sizesByte2 shr 4) and 0xF
        val indexSize = sizesByte2 and 0xF

        var pos = payloadStart + 6
        val itemCountWidth = if (version < 2) 2 else 4
        if (pos + itemCountWidth > payloadEnd) {
            w.add("Box too short to contain item_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemCount = if (itemCountWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
        pos += itemCountWidth

        val children = mutableListOf<BoxNode>()
        var itemsFound = 0L
        val itemIdWidth = if (version < 2) 2 else 4
        val constructionMethodWidth = if (version == 1 || version == 2) 2 else 0
        while (itemsFound < itemCount) {
            val fixedItemHeaderSize = itemIdWidth + constructionMethodWidth + 2 + baseOffsetSize + 2
            if (pos + fixedItemHeaderSize > payloadEnd) break

            val itemStart = pos
            val itemId = if (itemIdWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
            pos += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) {
                reader.readUInt16(pos) and 0xF
            } else {
                0
            }
            pos += constructionMethodWidth
            pos += 2 // data_reference_index, not surfaced
            val baseOffset = if (baseOffsetSize > 0) readUIntOfWidth(reader, pos, baseOffsetSize) else 0L
            pos += baseOffsetSize
            val extentCount = reader.readUInt16(pos)
            pos += 2

            val extentEntryWidth = indexSize + offsetSize + lengthSize
            val extentsNeeded = extentCount.toLong() * extentEntryWidth
            if (pos + extentsNeeded > payloadEnd) {
                w.add("Item $itemId's extents run past the end of the box")
                break
            }

            val extents = mutableListOf<BoxNode>()
            for (e in 0 until extentCount) {
                val extentStart = pos
                if (indexSize > 0) pos += indexSize
                val extentOffset = readUIntOfWidth(reader, pos, offsetSize)
                pos += offsetSize
                val extentLength = readUIntOfWidth(reader, pos, lengthSize)
                pos += lengthSize
                extents.add(
                    buildExtentNode(extentStart, pos - extentStart, constructionMethod, baseOffset, extentOffset, extentLength, itemId, w),
                )
            }

            children.add(
                BoxNode(
                    type = "item_$itemId",
                    offset = itemStart,
                    headerSize = fixedItemHeaderSize,
                    size = pos - itemStart,
                    children = extents,
                    fields = listOf(BoxField("construction_method", constructionMethod.toString(), itemStart + itemIdWidth, 2)),
                ),
            )
            itemsFound++
        }
        if (itemsFound < itemCount) {
            w.add("Declared $itemCount items but only found $itemsFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(itemCount, "item", "items"),
        )
    }
}

private fun buildExtentNode(
    offset: Long,
    size: Long,
    constructionMethod: Int,
    baseOffset: Long,
    extentOffset: Long,
    extentLength: Long,
    itemId: Long,
    warnings: MutableList<String>,
): BoxNode {
    return when (constructionMethod) {
        0 -> {
            val absoluteOffset = baseOffset + extentOffset
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("offset", absoluteOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "offset=$absoluteOffset, length=$extentLength",
            )
        }
        1 -> {
            val idatRelativeOffset = baseOffset + extentOffset
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("idat_relative_offset", idatRelativeOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "idat_relative_offset=$idatRelativeOffset, length=$extentLength",
            )
        }
        else -> {
            warnings.add("Item $itemId: construction_method=$constructionMethod (item offset) is not supported")
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("base_offset", baseOffset.toString(), offset, size),
                    BoxField("extent_offset", extentOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "unresolved (construction_method=$constructionMethod)",
            )
        }
    }
}
```

- [ ] **Step 4: Register `iloc` in `Decoders.kt`**

Add directly after the `sefd` line:

```kotlin
    BoxRegistry.register("iloc", IlocBoxDecoder)
```

- [ ] **Step 5: Add `"iloc"` to `DecodersRegistrationTest`'s required-type list**

Add `"iloc"` to the end of the existing `typesThatMustHaveADecoder` list (after `"sefd"`).

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew test --tests IlocBoxDecoderTest --tests DecodersRegistrationTest
```
Expected: PASS (3/3 in `IlocBoxDecoderTest`, 1/1 in `DecodersRegistrationTest`).

- [ ] **Step 7: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/IlocBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt app/src/test/kotlin/com/multiviewer/parser/IlocBoxDecoderTest.kt
git commit -m "feat(parser): decode iloc item locations (construction_method 0/1/2)"
```

---

### Task 3: `ExifDecoder` — TIFF/IFD0/Exif SubIFD/GPS IFD/Samsung MakerNote

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` (existing, same package).
- Produces: `fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode>` — a plain function, NOT a `BoxDecoder` (it decodes a raw byte range handed to it by the caller, not a registered box type). Task 4's `MetaBoxDecoder` enhancement calls this exact function with the Exif item's resolved absolute file offset/end. Independent of Tasks 1/2 (different files); no other task depends on this one except Task 4, which only calls it as a black box.

- [ ] **Step 1: Write the failing tests**

Every byte array below was generated and independently verified with a Python simulation of the exact same TIFF/IFD encoding this decoder implements — transcribe them exactly.

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ExifDecoderTest {
    @Test
    fun `decodes IFD0, follows the Exif pointer, and decodes a nested Samsung MakerNote`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x02, 0x00, 0x0f, 0x01,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x41, 0x42,
            0x43, 0x00, 0x69, 0x87.toByte(), 0x04, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x26, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x7c, 0x92.toByte(), 0x07, 0x00,
            0x0e, 0x00, 0x00, 0x00, 0x38, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x30, 0x31,
            0x30, 0x31,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        assertEquals(1, ifds.size)
        val ifd0 = ifds[0]
        assertEquals("IFD0", ifd0.type)
        assertEquals("ABC", ifd0.fields.first { it.name == "Make" }.value)

        val exifIfd = ifd0.children.first { it.type == "Exif" }
        val makerNote = exifIfd.children.first { it.type == "MakerNote" }
        assertEquals("0101", makerNote.fields.first { it.name == "Version" }.value)
        reader.close()
    }

    @Test
    fun `follows the GPS pointer and decodes a GPS tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x25, 0x88.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00,
            0x4e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        val ifd0 = ifds[0]
        val gpsIfd = ifd0.children.first { it.type == "GPS" }
        assertEquals("N", gpsIfd.fields.first { it.name == "GPSLatitudeRef" }.value)
        reader.close()
    }

    @Test
    fun `unrecognized tag falls back to a hex label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x34, 0x12, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("42", ifds[0].fields.first { it.name == "Tag 0x1234" }.value)
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests ExifDecoderTest
```
Expected: FAIL — `decodeExif` is unresolved.

- [ ] **Step 3: Create `ExifDecoder.kt`**

```kotlin
package com.multiviewer.parser

private const val TAG_EXIF_IFD_POINTER = 0x8769
private const val TAG_GPS_IFD_POINTER = 0x8825
private const val TAG_INTEROP_IFD_POINTER = 0xA005
private const val TAG_MAKER_NOTE = 0x927C

private val TIFF_TYPE_SIZES = mapOf(
    1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8,
)

private val TAG_NAMES_IFD0 = mapOf(
    0x0100 to "ImageWidth",
    0x0101 to "ImageLength",
    0x010F to "Make",
    0x0110 to "Model",
    0x0112 to "Orientation",
    0x011A to "XResolution",
    0x011B to "YResolution",
    0x0128 to "ResolutionUnit",
    0x0131 to "Software",
    0x0132 to "DateTime",
    0x0213 to "YCbCrPositioning",
)

private val TAG_NAMES_EXIF = mapOf(
    0x829A to "ExposureTime",
    0x829D to "FNumber",
    0x8822 to "ExposureProgram",
    0x8827 to "ISOSpeedRatings",
    0x9000 to "ExifVersion",
    0x9003 to "DateTimeOriginal",
    0x9004 to "DateTimeDigitized",
    0x9010 to "OffsetTime",
    0x9011 to "OffsetTimeOriginal",
    0x9201 to "ShutterSpeedValue",
    0x9202 to "ApertureValue",
    0x9203 to "BrightnessValue",
    0x9204 to "ExposureBiasValue",
    0x9205 to "MaxApertureValue",
    0x9207 to "MeteringMode",
    0x9209 to "Flash",
    0x920A to "FocalLength",
    0x9290 to "SubSecTime",
    0x9291 to "SubSecTimeOriginal",
    0x9292 to "SubSecTimeDigitized",
    0xA001 to "ColorSpace",
    0xA002 to "PixelXDimension",
    0xA003 to "PixelYDimension",
    0xA402 to "ExposureMode",
    0xA403 to "WhiteBalance",
    0xA404 to "DigitalZoomRatio",
    0xA405 to "FocalLengthIn35mmFilm",
    0xA406 to "SceneCaptureType",
    0xA420 to "ImageUniqueID",
)

private val TAG_NAMES_GPS = mapOf(
    0x0001 to "GPSLatitudeRef",
    0x0002 to "GPSLatitude",
    0x0003 to "GPSLongitudeRef",
    0x0004 to "GPSLongitude",
    0x0005 to "GPSAltitudeRef",
    0x0006 to "GPSAltitude",
)

private val TAG_NAMES_MAKERNOTE = mapOf(
    0x0001 to "Version",
    0x0002 to "DeviceType",
    0x0021 to "PictureWizard",
    0x0030 to "LocalLocationName",
    0x0031 to "LocationName",
    0x0035 to "Preview",
    0x0043 to "CameraTemperature",
    0xA001 to "FirmwareName",
    0xA003 to "LensType",
    0xA004 to "LensFirmware",
    0xA010 to "SensorAreas",
    0xA011 to "ColorSpace",
    0xA012 to "SmartRange",
    0xA013 to "ExposureBiasValue",
    0xA014 to "ISO",
    0xA018 to "ExposureTime",
    0xA019 to "FNumber",
    0xA01A to "FocalLengthIn35mmFormat",
    0xA020 to "EncryptionKey",
)

fun decodeExif(reader: ByteReader, itemStart: Long, itemEnd: Long): List<BoxNode> {
    if (itemEnd - itemStart < 4) return emptyList()
    val tiffHeaderOffsetField = reader.readUInt32(itemStart)
    val tiffStart = itemStart + 4 + tiffHeaderOffsetField
    if (tiffStart + 8 > itemEnd) return emptyList()
    val byteOrderBytes = reader.readBytes(tiffStart, 2)
    val littleEndian = byteOrderBytes[0] == 'I'.code.toByte() && byteOrderBytes[1] == 'I'.code.toByte()
    val ifd0Offset = readUInt32Endian(reader, tiffStart + 4, littleEndian)
    val ifd0AbsoluteOffset = tiffStart + ifd0Offset
    return listOf(decodeIfd(reader, tiffStart, ifd0AbsoluteOffset, itemEnd, littleEndian, "IFD0", TAG_NAMES_IFD0))
}

private fun decodeIfd(
    reader: ByteReader,
    tiffStart: Long,
    ifdOffset: Long,
    itemEnd: Long,
    littleEndian: Boolean,
    label: String,
    tagNames: Map<Int, String>,
): BoxNode {
    if (ifdOffset + 2 > itemEnd) {
        return BoxNode(label, ifdOffset, 0, 0, warnings = listOf("IFD too short to contain entry_count"))
    }
    val entryCount = readUInt16Endian(reader, ifdOffset, littleEndian)
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    var pos = ifdOffset + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > itemEnd) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }

        when (tag) {
            TAG_EXIF_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Exif", TAG_NAMES_EXIF))
            }
            TAG_GPS_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "GPS", TAG_NAMES_GPS))
            }
            TAG_INTEROP_IFD_POINTER -> {
                val subOffset = tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
                children.add(decodeIfd(reader, tiffStart, subOffset, itemEnd, littleEndian, "Interop", TAG_NAMES_EXIF))
            }
            TAG_MAKER_NOTE -> {
                children.add(decodeMakerNote(reader, tiffStart, valueAbsolutePos, count.toInt(), littleEndian))
            }
            else -> {
                val name = tagNames[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
                val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
                fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
            }
        }
        pos += 12
    }
    return BoxNode(
        type = label, offset = ifdOffset, headerSize = 2, size = pos - ifdOffset,
        fields = fields, children = children,
    )
}

private fun decodeMakerNote(
    reader: ByteReader,
    tiffStart: Long,
    absolutePos: Long,
    byteLength: Int,
    littleEndian: Boolean,
): BoxNode {
    val endPos = absolutePos + byteLength
    if (byteLength < 2) {
        return BoxNode("MakerNote", absolutePos, 0, byteLength.toLong(), warnings = listOf("MakerNote too short"))
    }
    val entryCount = readUInt16Endian(reader, absolutePos, littleEndian)
    val fields = mutableListOf<BoxField>()
    var pos = absolutePos + 2
    for (i in 0 until entryCount) {
        if (pos + 12 > endPos) break
        val tag = readUInt16Endian(reader, pos, littleEndian)
        val fieldType = readUInt16Endian(reader, pos + 2, littleEndian)
        val count = readUInt32Endian(reader, pos + 4, littleEndian)
        val valueOffsetPos = pos + 8
        val typeSize = TIFF_TYPE_SIZES[fieldType] ?: 1
        val totalSize = typeSize * count
        val valueAbsolutePos = if (totalSize <= 4) {
            valueOffsetPos
        } else {
            tiffStart + readUInt32Endian(reader, valueOffsetPos, littleEndian)
        }
        val name = TAG_NAMES_MAKERNOTE[tag] ?: "Tag 0x${tag.toString(16).padStart(4, '0')}"
        val display = formatTiffValue(reader, fieldType, count.toInt(), valueAbsolutePos, littleEndian)
        fields.add(BoxField(name, display, valueAbsolutePos, totalSize))
        pos += 12
    }
    return BoxNode(type = "MakerNote", offset = absolutePos, headerSize = 2, size = byteLength.toLong(), fields = fields)
}

private fun formatTiffValue(reader: ByteReader, type: Int, count: Int, valuePos: Long, littleEndian: Boolean): String {
    return when (type) {
        2 -> {
            val bytes = reader.readBytes(valuePos, count)
            val nullIndex = bytes.indexOf(0)
            String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.UTF_8)
        }
        3 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toString() }
        8 -> (0 until count).joinToString(", ") { i -> readUInt16Endian(reader, valuePos + i * 2, littleEndian).toShort().toString() }
        4 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toString() }
        9 -> (0 until count).joinToString(", ") { i -> readUInt32Endian(reader, valuePos + i * 4, littleEndian).toInt().toString() }
        5 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian)
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian)
            "$num/$den"
        }
        10 -> (0 until count).joinToString(", ") { i ->
            val num = readUInt32Endian(reader, valuePos + i * 8, littleEndian).toInt()
            val den = readUInt32Endian(reader, valuePos + i * 8 + 4, littleEndian).toInt()
            "$num/$den"
        }
        else -> {
            val bytes = reader.readBytes(valuePos, count.coerceAtMost(64))
            bytes.joinToString(" ") { "%02x".format(it) }
        }
    }
}

private fun readUInt16Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Int {
    if (!littleEndian) return reader.readUInt16(offset)
    val bytes = reader.readBytes(offset, 2)
    return ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
}

private fun readUInt32Endian(reader: ByteReader, offset: Long, littleEndian: Boolean): Long {
    if (!littleEndian) return reader.readUInt32(offset)
    val bytes = reader.readBytes(offset, 4)
    return ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests ExifDecoderTest
```
Expected: PASS (3/3).

- [ ] **Step 5: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "feat(parser): decode EXIF TIFF/IFD0/Exif/GPS and Samsung MakerNote"
```

---

### Task 4: `MetaBoxDecoder` cross-referencing (final task)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt` (append to the existing file — do not remove its 4 existing tests)

**Interfaces:**
- Consumes: `IlocBoxDecoder`'s exact field names from Task 2 (`"offset"`/`"idat_relative_offset"`/`"length"` on extent nodes, item nodes named `"item_$itemId"`), `InfeBoxDecoder`'s `content_type` field from Task 1, and `decodeExif(reader, itemStart, itemEnd): List<BoxNode>` from Task 3.
- Produces: nothing new for other tasks — this is the final task in the plan.

- [ ] **Step 1: Write the failing tests**

Append these three tests to the existing `MetaBoxDecoderTest.kt` (keep its current 4 tests unchanged):

```kotlin
    @Test
    fun `cross-references iinf and iloc to decode an Exif item inline`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x4b, 0x6d, 0x65, 0x74, 0x61,
            0x00, 0x00, 0x00, 0x23, 0x69, 0x69, 0x6e, 0x66,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x15, 0x69, 0x6e, 0x66, 0x65, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x45, 0x78,
            0x69, 0x66, 0x00, 0x00, 0x00, 0x00, 0x20, 0x69,
            0x6c, 0x6f, 0x63, 0x01, 0x00, 0x00, 0x00, 0x44,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x4b, 0x00,
            0x00, 0x00, 0x1e, 0x00, 0x00, 0x00, 0x00, 0x49,
            0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01,
            0x00, 0x0f, 0x01, 0x02, 0x00, 0x04, 0x00, 0x00,
            0x00, 0x58, 0x59, 0x5a, 0x00, 0x00, 0x00, 0x00,
            0x00,
        )
        val reader = byteReaderOf(body)
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 75, emptyList())

        val ilocNode = node.children.first { it.type == "iloc" }
        val item1 = ilocNode.children.first { it.type == "item_1" }
        val ifd0 = item1.children.first { it.type == "IFD0" }
        assertEquals("XYZ", ifd0.fields.first { it.name == "Make" }.value)
        reader.close()
    }

    @Test
    fun `cross-references iinf and iloc to decode an XMP item inline`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x5f, 0x6d, 0x65, 0x74, 0x61,
            0x00, 0x00, 0x00, 0x37, 0x69, 0x69, 0x6e, 0x66,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x29, 0x69, 0x6e, 0x66, 0x65, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x6d, 0x69,
            0x6d, 0x65, 0x00, 0x61, 0x70, 0x70, 0x6c, 0x69,
            0x63, 0x61, 0x74, 0x69, 0x6f, 0x6e, 0x2f, 0x72,
            0x64, 0x66, 0x2b, 0x78, 0x6d, 0x6c, 0x00, 0x00,
            0x00, 0x00, 0x20, 0x69, 0x6c, 0x6f, 0x63, 0x01,
            0x00, 0x00, 0x00, 0x44, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x5f, 0x00, 0x00, 0x00, 0x1b, 0x3c,
            0x78, 0x3a, 0x78, 0x6d, 0x70, 0x6d, 0x65, 0x74,
            0x61, 0x3e, 0x74, 0x65, 0x73, 0x74, 0x3c, 0x2f,
            0x78, 0x3a, 0x78, 0x6d, 0x70, 0x6d, 0x65, 0x74,
            0x61, 0x3e,
        )
        val reader = byteReaderOf(body)
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 95, emptyList())

        val ilocNode = node.children.first { it.type == "iloc" }
        val item1 = ilocNode.children.first { it.type == "item_1" }
        assertEquals("<x:xmpmeta>test</x:xmpmeta>", item1.fields.first { it.name == "xmp" }.value)
        reader.close()
    }

    @Test
    fun `resolves a construction_method 1 item's offset using the sibling idat box`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x5b, 0x6d, 0x65, 0x74, 0x61,
            0x00, 0x00, 0x00, 0x23, 0x69, 0x69, 0x6e, 0x66,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x15, 0x69, 0x6e, 0x66, 0x65, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x68, 0x76,
            0x63, 0x31, 0x00, 0x00, 0x00, 0x00, 0x20, 0x69,
            0x6c, 0x6f, 0x63, 0x01, 0x00, 0x00, 0x00, 0x44,
            0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00,
            0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x10, 0x69,
            0x64, 0x61, 0x74, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x41, 0x41, 0x41,
        )
        val reader = byteReaderOf(body)
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 91, emptyList())

        val ilocNode = node.children.first { it.type == "iloc" }
        val item1 = ilocNode.children.first { it.type == "item_1" }
        val extent = item1.children.first { it.type == "extent" }
        assertEquals("88", extent.fields.first { it.name == "offset" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests MetaBoxDecoderTest
```
Expected: FAIL — the 3 new tests fail because `MetaBoxDecoder` doesn't yet cross-reference `iinf`/`iloc` (the 4 existing tests still pass).

- [ ] **Step 3: Replace the full content of `MetaBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object MetaBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val childOffsetInPayload = if (isPlainBoxLayout(reader, payloadStart, payloadEnd)) 0 else 4
        val children = parseBoxes(reader, payloadStart + childOffsetInPayload, payloadEnd)
        val enrichedChildren = enrichItemMetadata(reader, children)
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = enrichedChildren,
            warnings = warnings,
        )
    }
}

private fun isPlainBoxLayout(reader: ByteReader, payloadStart: Long, payloadEnd: Long): Boolean {
    val fourCcOffset = payloadStart + 4
    if (fourCcOffset + 4 > payloadEnd) return false
    val bytes = reader.readBytes(fourCcOffset, 4)
    return bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
}

private fun enrichItemMetadata(reader: ByteReader, children: List<BoxNode>): List<BoxNode> {
    val iinfIndex = children.indexOfFirst { it.type == "iinf" }
    val ilocIndex = children.indexOfFirst { it.type == "iloc" }
    if (iinfIndex < 0 || ilocIndex < 0) return children

    val itemInfo = mutableMapOf<Long, Pair<String, String?>>()
    for (infe in children[iinfIndex].children) {
        val itemId = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: continue
        val itemType = infe.fields.find { it.name == "item_type" }?.value ?: continue
        val contentType = infe.fields.find { it.name == "content_type" }?.value
        itemInfo[itemId] = itemType to contentType
    }

    val idatPayloadOffset = children.find { it.type == "idat" }?.let { it.offset + it.headerSize }

    val ilocNode = children[ilocIndex]
    val enrichedItems = ilocNode.children.map { itemNode ->
        val itemId = itemNode.type.removePrefix("item_").toLongOrNull() ?: return@map itemNode
        val (itemType, contentType) = itemInfo[itemId] ?: return@map itemNode
        enrichIlocItem(reader, itemNode, itemType, contentType, idatPayloadOffset)
    }
    val enrichedIloc = ilocNode.copy(children = enrichedItems)

    return children.toMutableList().also { it[ilocIndex] = enrichedIloc }
}

private fun enrichIlocItem(
    reader: ByteReader,
    itemNode: BoxNode,
    itemType: String,
    contentType: String?,
    idatPayloadOffset: Long?,
): BoxNode {
    val extentWarnings = mutableListOf<String>()
    val resolvedExtents = itemNode.children.map { extent ->
        val idatRelative = extent.fields.find { it.name == "idat_relative_offset" }?.value?.toLongOrNull()
        if (idatRelative == null) {
            extent
        } else if (idatPayloadOffset == null) {
            extentWarnings.add("Item ${itemNode.type}: idat box not found, cannot resolve idat-relative offset")
            extent
        } else {
            val absoluteOffset = idatPayloadOffset + idatRelative
            val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: 0L
            extent.copy(
                fields = listOf(
                    BoxField("offset", absoluteOffset.toString(), extent.offset, extent.size),
                    BoxField("length", length.toString(), extent.offset, extent.size),
                ),
                summary = "offset=$absoluteOffset, length=$length",
            )
        }
    }
    val enrichedItem = itemNode.copy(
        children = resolvedExtents,
        warnings = itemNode.warnings + extentWarnings,
    )

    val singleExtent = resolvedExtents.singleOrNull() ?: return enrichedItem
    val extentOffset = singleExtent.fields.find { it.name == "offset" }?.value?.toLongOrNull() ?: return enrichedItem
    val extentLength = singleExtent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: return enrichedItem

    return when {
        itemType == "Exif" -> {
            val exifChildren = decodeExif(reader, extentOffset, extentOffset + extentLength)
            enrichedItem.copy(children = enrichedItem.children + exifChildren, summary = "Exif metadata")
        }
        itemType == "mime" && contentType == "application/rdf+xml" -> {
            val bytes = reader.readBytes(extentOffset, extentLength.toInt())
            val text = String(bytes, Charsets.UTF_8).trimEnd(' ', Char(0))
            enrichedItem.copy(
                fields = enrichedItem.fields + BoxField("xmp", text, extentOffset, extentLength),
                summary = "XMP (${text.length} chars)",
            )
        }
        else -> enrichedItem
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests MetaBoxDecoderTest
```
Expected: PASS (7/7 — the 4 existing tests plus the 3 new ones).

- [ ] **Step 5: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 6: Manual verification against the real sample file**

```bash
./gradlew run
```
Open `~/Downloads/20260715_223828.heic` and confirm:
- Under `meta` → `iloc` → `item_49`, an `IFD0` node appears with `Make=samsung`, `Model=Galaxy Z TriFold`, `DateTime=2026:07:15 22:38:28`; its `Exif` child has `DateTimeOriginal`/`FNumber`/`ISOSpeedRatings`/etc.; the `Exif` child's `MakerNote` child has `Version=0101` and `FocalLengthIn35mmFormat=13`; `IFD0`'s `GPS` child shows all-zero coordinates.
- Under `meta` → `iloc` → `item_50` and `item_51`, an `xmp` field shows the actual RDF/XML text matching what was found during analysis (`<x:xmpmeta ...>` content).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/MetaBoxDecoderTest.kt
git commit -m "feat(parser): cross-reference iinf/iloc/idat to decode Exif and XMP items in meta"
```
