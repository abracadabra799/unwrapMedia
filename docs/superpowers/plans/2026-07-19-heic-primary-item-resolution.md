# HEIC Primary-Item Resolution/Color-Space Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the Media Summary's "Resolution" and "Color Space" fields for HEIC files to reflect the primary HEIF item, not whichever `ispe`/`colr` box happens to appear first in the box tree.

**Architecture:** Add decoders for the `pitm` (Primary Item Box) and `ipma` (Item Property Association Box) boxes, then add a small resolver in `MediaSummaryBuilder.kt` that walks `pitm` → `ipma` → `ipco` to find the property box actually associated with the primary item, falling back to today's first-match behavior when that chain can't be resolved.

**Tech Stack:** Kotlin 2.0.21, plain JVM, `kotlin.test` for unit tests (no new dependencies).

## Global Constraints

- No change to Camera Info, GPS Location, Samsung Metadata, or Capture Date sections.
- No change to JPEG or video (moov/trak) resolution/color-space handling — those code paths don't use `ispe`/`colr` at all.
- When `pitm`/`ipma`/`ipco` can't be resolved (missing, malformed, or the primary item has no matching property), fall back to the existing `findFirst`-based first-match behavior — never show nothing where the old code showed something.
- Follow existing decoder conventions exactly: `BoxDecoder` interface, `warnings.toMutableList()` pattern, `BoxField(name, value, offset, length)`, short-box warnings that return an empty-fields `BoxNode` rather than throwing.

---

### Task 1: `PitmBoxDecoder` and `IpmaBoxDecoder`

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/PitmBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/IpmaBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PitmBoxDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/IpmaBoxDecoderTest.kt`

**Interfaces:**
- Produces: `PitmBoxDecoder.decode(...)` returns a `BoxNode` with a single `BoxField("primary_item_ID", "<id>", ...)`.
- Produces: `IpmaBoxDecoder.decode(...)` returns a `BoxNode` whose `children` are one `BoxNode` per item entry, `type = "item_$itemId"`, each holding one `BoxField("property_index", "<1-based index>", ...)` per association in that item's list, in order.
- Both follow the existing `BoxDecoder` interface (`app/src/main/kotlin/com/multiviewer/parser/BoxRegistry.kt`) used by every other decoder in this package (e.g. `InfeBoxDecoder.kt`, `IlocBoxDecoder.kt`).

- [ ] **Step 1: Write the failing tests for `PitmBoxDecoder`**

Create `app/src/test/kotlin/com/multiviewer/parser/PitmBoxDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PitmBoxDecoderTest {
    @Test
    fun `version 0 decodes a 2-byte primary_item_ID`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0
            byteArrayOf(0x00, 0x29) // primary_item_ID = 41
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals("primary_item_ID", node.fields[0].name)
        assertEquals("41", node.fields[0].value)
        assertEquals("primary_item_ID=41", node.summary)
        reader.close()
    }

    @Test
    fun `version 1 decodes a 4-byte primary_item_ID`() {
        val body = byteArrayOf(0x01, 0x00, 0x00, 0x00) + // version=1, flags=0
            byteArrayOf(0x00, 0x00, 0x01, 0x2C) // primary_item_ID = 300
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals("300", node.fields[0].value)
        reader.close()
    }

    @Test
    fun `box too short for primary_item_ID returns a warning and no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PitmBoxDecoderTest"`
Expected: FAIL with "Unresolved reference: PitmBoxDecoder"

- [ ] **Step 3: Implement `PitmBoxDecoder`**

Create `app/src/main/kotlin/com/multiviewer/parser/PitmBoxDecoder.kt`:

```kotlin
package com.multiviewer.parser

object PitmBoxDecoder : BoxDecoder {
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
        val itemIdWidth = if (version == 0) 2 else 4
        val itemIdOffset = payloadStart + 4
        if (payloadEnd - itemIdOffset < itemIdWidth) {
            w.add("Box too short for primary_item_ID")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val primaryItemId = if (itemIdWidth == 2) {
            reader.readUInt16(itemIdOffset).toLong()
        } else {
            reader.readUInt32(itemIdOffset)
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = listOf(BoxField("primary_item_ID", primaryItemId.toString(), itemIdOffset, itemIdWidth.toLong())),
            warnings = w,
            summary = "primary_item_ID=$primaryItemId",
        )
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PitmBoxDecoderTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Write the failing tests for `IpmaBoxDecoder`**

Create `app/src/test/kotlin/com/multiviewer/parser/IpmaBoxDecoderTest.kt`:

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IpmaBoxDecoderTest {
    @Test
    fun `version 0, flags 0 decodes 2-byte item_IDs and 1-byte property associations`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0 (1-byte associations)
            byteArrayOf(0x00, 0x00, 0x00, 0x02) +         // entry_count = 2
            // entry: item_ID=1, association_count=2, associations=[1, 2]
            byteArrayOf(0x00, 0x01, 0x02, 0x01, 0x02) +
            // entry: item_ID=41, association_count=1, associations=[3]
            byteArrayOf(0x00, 0x29, 0x01, 0x03)
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(2, node.children.size)
        assertEquals("item_1", node.children[0].type)
        assertEquals(listOf("1", "2"), node.children[0].fields.map { it.value })
        assertEquals("item_41", node.children[1].type)
        assertEquals(listOf("3"), node.children[1].fields.map { it.value })
        assertEquals("2 entries", node.summary)
        reader.close()
    }

    @Test
    fun `version 1, flags 1 decodes 4-byte item_IDs and masks the essential bit from 2-byte associations`() {
        val body = byteArrayOf(0x01, 0x00, 0x00, 0x01) + // version=1, flags=1 (2-byte associations)
            byteArrayOf(0x00, 0x00, 0x00, 0x01) +         // entry_count = 1
            // entry: item_ID=300, association_count=1, association=[essential=1, property_index=5]
            byteArrayOf(0x00, 0x00, 0x01, 0x2C, 0x01, 0x80.toByte(), 0x05)
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.children.size)
        assertEquals("item_300", node.children[0].type)
        assertEquals("5", node.children[0].fields[0].value)
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available data truncates with a warning`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0
            byteArrayOf(0x00, 0x00, 0x00, 0x02) +         // entry_count = 2 (only 1 present)
            byteArrayOf(0x00, 0x01, 0x01, 0x01)           // entry: item_ID=1, association_count=1, associations=[1]
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.warnings.size)
        assertEquals(1, node.children.size)
        reader.close()
    }
}
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.IpmaBoxDecoderTest"`
Expected: FAIL with "Unresolved reference: IpmaBoxDecoder"

- [ ] **Step 7: Implement `IpmaBoxDecoder`**

Create `app/src/main/kotlin/com/multiviewer/parser/IpmaBoxDecoder.kt`:

```kotlin
package com.multiviewer.parser

object IpmaBoxDecoder : BoxDecoder {
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
            w.add("Box too short for a FullBox header and entry_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val flagsLastByte = reader.readUInt8(payloadStart + 3)
        val twoByteAssociations = (flagsLastByte and 0x1) != 0
        val entryCount = reader.readUInt32(payloadStart + 4)

        var pos = payloadStart + 8
        val itemIdWidth = if (version == 0) 2 else 4
        val associationWidth = if (twoByteAssociations) 2 else 1
        val children = mutableListOf<BoxNode>()
        var entriesFound = 0L
        while (entriesFound < entryCount) {
            if (pos + itemIdWidth + 1 > payloadEnd) break
            val itemStart = pos
            val itemId = if (itemIdWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
            pos += itemIdWidth
            val associationCount = reader.readUInt8(pos)
            pos += 1

            val associationsNeeded = associationCount.toLong() * associationWidth
            if (pos + associationsNeeded > payloadEnd) {
                w.add("Item $itemId's property associations run past the end of the box")
                break
            }

            val fields = mutableListOf<BoxField>()
            for (a in 0 until associationCount) {
                val assocOffset = pos
                val propertyIndex = if (twoByteAssociations) {
                    reader.readUInt16(pos) and 0x7FFF
                } else {
                    reader.readUInt8(pos) and 0x7F
                }
                pos += associationWidth
                fields.add(BoxField("property_index", propertyIndex.toString(), assocOffset, associationWidth.toLong()))
            }

            children.add(
                BoxNode(
                    type = "item_$itemId",
                    offset = itemStart,
                    headerSize = itemIdWidth + 1,
                    size = pos - itemStart,
                    fields = fields,
                    summary = "properties: ${fields.joinToString(", ") { it.value }}",
                ),
            )
            entriesFound++
        }
        if (entriesFound < entryCount) {
            w.add("Declared $entryCount entries but only found $entriesFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(entryCount, "entry", "entries"),
        )
    }
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.IpmaBoxDecoderTest"`
Expected: PASS (3 tests)

- [ ] **Step 9: Register both decoders**

In `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`, add these two lines directly below the existing `BoxRegistry.register("infe", InfeBoxDecoder)` line:

```kotlin
    BoxRegistry.register("pitm", PitmBoxDecoder)
    BoxRegistry.register("ipma", IpmaBoxDecoder)
```

- [ ] **Step 10: Run the full test suite to verify nothing broke**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (121 existing + 6 new = 127)

- [ ] **Step 11: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PitmBoxDecoder.kt \
        app/src/main/kotlin/com/multiviewer/parser/IpmaBoxDecoder.kt \
        app/src/main/kotlin/com/multiviewer/parser/Decoders.kt \
        app/src/test/kotlin/com/multiviewer/parser/PitmBoxDecoderTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/IpmaBoxDecoderTest.kt
git commit -m "feat: decode pitm and ipma boxes"
```

---

### Task 2: Resolve Resolution/Color Space through the primary item

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes (from Task 1, as tree shape — these tests build `BoxNode` trees directly in memory, matching this file's existing style, not by invoking the decoders): a `pitm` node with `BoxField("primary_item_ID", "<id>", ...)`; an `ipma` node whose children are `BoxNode(type = "item_$id", fields = listOf(BoxField("property_index", "<n>", ...), ...))`.
- Produces: `private fun findPrimaryItemProperty(root: BoxNode, propertyType: String): BoxNode?`, used only within this file.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`, inside the `MediaSummaryBuilderTest` class:

```kotlin
    @Test
    fun `Resolution and Color Space use the primary item's ispe and colr, not the first one in tree order`() {
        val tileIspe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "512", 0, 4), BoxField("image_height", "512", 0, 4)),
        )
        val tileColr = BoxNode(type = "colr", offset = 0, headerSize = 0, size = 0, summary = "ICC profile (10 bytes)")
        val primaryIspe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "4000", 0, 4), BoxField("image_height", "2252", 0, 4)),
        )
        val primaryColr = BoxNode(type = "colr", offset = 0, headerSize = 0, size = 0, summary = "nclx: 9/16/9")
        val ipco = BoxNode(
            type = "ipco", offset = 0, headerSize = 0, size = 0,
            children = listOf(tileIspe, tileColr, primaryIspe, primaryColr),
        )
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val pitm = BoxNode(
            type = "pitm", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("primary_item_ID", "99", 0, 4)),
        )
        val ipmaTileItem = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 1), BoxField("property_index", "2", 0, 1)),
        )
        val ipmaPrimaryItem = BoxNode(
            type = "item_99", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "3", 0, 1), BoxField("property_index", "4", 0, 1)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaTileItem, ipmaPrimaryItem))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(pitm, ipma, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("4000x2252", basicInfo.fields.first { it.label == "Resolution" }.value)
        assertEquals("nclx: 9/16/9", basicInfo.fields.first { it.label == "Color Space" }.value)
    }

    @Test
    fun `without pitm or ipma, Resolution falls back to the first ispe in tree order`() {
        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "800", 0, 4), BoxField("image_height", "600", 0, 4)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))

        val basicInfo = buildMediaSummary(root, tempFile()).sections.first { it.title == "Basic Info" }
        assertEquals("800x600", basicInfo.fields.first { it.label == "Resolution" }.value)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MediaSummaryBuilderTest"`
Expected: FAIL on the first new test — Resolution resolves to "512x512" (the first `ispe` in tree order) instead of "4000x2252". The second new test passes already (it only exercises the existing fallback path), which is expected — it exists to guard against a regression, not to prove new behavior.

- [ ] **Step 3: Implement `findPrimaryItemProperty` and wire it into `buildImageBasicInfo`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, add this function directly below `findFirst`:

```kotlin
private fun findPrimaryItemProperty(root: BoxNode, propertyType: String): BoxNode? {
    val meta = root.children.find { it.type == "meta" } ?: return null
    val pitm = meta.children.find { it.type == "pitm" } ?: return null
    val primaryItemId = pitm.fields.find { it.name == "primary_item_ID" }?.value ?: return null
    val ipma = meta.children.find { it.type == "ipma" } ?: return null
    val itemEntry = ipma.children.find { it.type == "item_$primaryItemId" } ?: return null
    val propertyIndices = itemEntry.fields
        .filter { it.name == "property_index" }
        .mapNotNull { it.value.toIntOrNull() }
    val ipco = findFirst(meta) { it.type == "ipco" } ?: return null
    for (index in propertyIndices) {
        val property = ipco.children.getOrNull(index - 1) ?: continue
        if (property.type == propertyType) return property
    }
    return null
}
```

Then in `buildImageBasicInfo`, replace:

```kotlin
    val isJpeg = root.children.any { it.type == "SOI" }
    val sofOrIspe = findFirst(root) { it.type.startsWith("SOF") || it.type == "ispe" }
```

with:

```kotlin
    val isJpeg = root.children.any { it.type == "SOI" }
    val sof = findFirst(root) { it.type.startsWith("SOF") }
    val ispe = findPrimaryItemProperty(root, "ispe") ?: findFirst(root) { it.type == "ispe" }
    val sofOrIspe = sof ?: ispe
```

And replace:

```kotlin
    val colr = findFirst(root) { it.type == "colr" }
```

with:

```kotlin
    val colr = findPrimaryItemProperty(root, "colr") ?: findFirst(root) { it.type == "colr" }
```

No other lines in `buildImageBasicInfo` change — `sofOrIspe` is still used exactly as before for the width/height lookup and the JPEG `num_components` grayscale check.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (127 existing + 2 new = 129)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "fix: resolve HEIC Resolution and Color Space through the primary item"
```
