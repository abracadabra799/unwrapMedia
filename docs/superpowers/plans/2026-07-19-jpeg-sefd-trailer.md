# JPEG SEFD Trailer Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognize and decode Samsung's SEFD field-directory trailer when it's appended raw at the end of a JPEG file (instead of wrapped in an ISOBMFF box, as HEIC already handles), by reusing the existing `SefdBoxDecoder` unchanged.

**Architecture:** `JpegWalker.kt`'s main marker-walking loop, at the exact point it currently gives up on a non-`0xFF` byte, gets one new check: if the file's last 4 bytes are `"SEFT"`, call the already-shipped `SefdBoxDecoder.decode` directly on the remaining byte range (with `headerSize = 0`, so its internal `payloadStart`/`payloadEnd` math collapses to the raw trailing range) instead of emitting the generic `"?"` warning node.

**Tech Stack:** Kotlin 2.0.21, kotlin.test.

## Global Constraints

- No changes to `SefdBoxDecoder.kt` — this feature is pure reuse of the already-shipped, already-tested decoder.
- No PNG support — out of scope, matching this project's existing non-goals.
- Any malformed/inconsistent SEFD trailer falls back to whatever `SefdBoxDecoder.decode`'s own existing bounds-checking and warnings already produce — no new JPEG-specific error handling is introduced.
- The resulting tree node's `type` must be `"sefd"`, matching the label already used for the HEIC box path, so the two forms are visually recognizable as the same structure.

---

### Task 1: Detect and decode a SEFD trailer at the end of a JPEG file

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Consumes: `SefdBoxDecoder.decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode` (existing, unchanged, `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`); `ByteReader.readFourCC(offset: Long): String` (existing, `ByteReader.kt`).
- Produces: nothing consumed by other tasks — this plan has only one task.

- [ ] **Step 1: Write the failing tests**

Add these tests to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (inside the existing `JpegWalkerTest` class):

```kotlin
    @Test
    fun `a SEFT-terminated trailer after JPEG data decodes as a sefd node`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), 0x00, 0x00, 0x34, 0x12,
            0x0a, 0x00, 0x00, 0x00, 0x54, 0x65, 0x73, 0x74,
            0x5f, 0x46, 0x69, 0x65, 0x6c, 0x64, 0x68, 0x69,
            0x53, 0x45, 0x46, 0x48, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x34, 0x12,
            0x14, 0x00, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00, 0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "EOI", "sefd"), segments.map { it.type })
        val sefd = segments[2]
        assertEquals(4L, sefd.offset)
        assertEquals(52L, sefd.size)
        assertEquals(1, sefd.children.size)
        val field = sefd.children[0]
        assertEquals("Test_Field", field.type)
        assertEquals(4L, field.offset)
        assertEquals("0x1234", field.fields[0].value)
        assertEquals("hi", field.fields[1].value)
        assertTrue(sefd.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `a short non-SEFT trailer falls back to the existing malformed-marker warning`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), 0x00, 0x01, 0x02)
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "EOI", "?"), segments.map { it.type })
        assertTrue(segments[2].warnings.isNotEmpty())
        reader.close()
    }
```

The first fixture reuses the exact same 52-byte SEFD trailer body already used in `SefdBoxDecoderTest`'s `decodes a plain text field with its marker and value` test, unchanged — only prepended with a 4-byte `SOI`+`EOI` JPEG prefix. The trailer's directory entry offset (`20`, computed relative to the `SEFH` position, not any fixed origin) is unaffected by the 4-byte shift, so the field ends up at absolute offset `4` (right after the JPEG prefix) instead of `0`.

The second fixture has 3 trailing bytes after `SOI`+`EOI` that are not `0xFF`-prefixed and do not end in `"SEFT"` — this exercises the `end - start < 4` guard inside the new helper function without it ever needing to read past the buffer.

Also confirm the **existing** test `a byte that is not 0xFF where a marker is expected produces a warning and stops` (already in `JpegWalkerTest.kt`, unmodified) still passes after this change — it is the regression check that ordinary malformed JPEGs (no `SEFT` anywhere) are completely unaffected.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: FAIL — both new tests fail because `parseJpegSegments` currently emits a generic `"?"` warning node instead of a `"sefd"` node for the first fixture (the second fixture actually already passes today, since it's testing behavior that isn't changing, but write it now so it's captured alongside the feature).

- [ ] **Step 3: Implement SEFD trailer detection in `JpegWalker.kt`**

Change the malformed-marker-prefix branch inside `parseJpegSegments` from:

```kotlin
        val markerPrefix = reader.readUInt8(pos)
        if (markerPrefix != 0xFF) {
            result.add(
                BoxNode(
                    "?", pos, 0, remaining,
                    warnings = listOf("Expected marker prefix 0xFF, found 0x${markerPrefix.toString(16).padStart(2, '0')}"),
                ),
            )
            break
        }
```

to:

```kotlin
        val markerPrefix = reader.readUInt8(pos)
        if (markerPrefix != 0xFF) {
            val sefdNode = tryDecodeSefdTrailer(reader, pos, end)
            result.add(
                sefdNode ?: BoxNode(
                    "?", pos, 0, remaining,
                    warnings = listOf("Expected marker prefix 0xFF, found 0x${markerPrefix.toString(16).padStart(2, '0')}"),
                ),
            )
            break
        }
```

Then add this function at the end of the file:

```kotlin
private fun tryDecodeSefdTrailer(reader: ByteReader, start: Long, end: Long): BoxNode? {
    if (end - start < 4 || reader.readFourCC(end - 4) != "SEFT") return null
    return SefdBoxDecoder.decode(reader, "sefd", start, 0, end - start, emptyList())
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest" -i`
Expected: PASS — all tests pass, including both new tests and the pre-existing `a byte that is not 0xFF...` test (unmodified, still exercising the same fallback path for JPEGs with no `SEFT` trailer).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test -i`
Expected: PASS — no regressions in `SefdBoxDecoderTest`, `ExifDecoderTest`, `ParseFileIntegrationTest`, or any other existing test. `SefdBoxDecoder.kt` itself is untouched by this change.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: decode Samsung SEFD trailer appended at the end of JPEG files"
```
