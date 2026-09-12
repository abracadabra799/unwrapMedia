# WebP Little-Endian Parsing Bug Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `WebpWalker.kt` reading RIFF/WebP's little-endian multi-byte integers
(RIFF `file_size`, every chunk's declared size, `VP8 `'s width/height) as big-endian,
which currently breaks WebP structure parsing for real files.

**Architecture:** Add `ByteReader.readUInt16LE`/`readUInt32LE` as private extension
functions local to `WebpWalker.kt` (matching the file's own existing
`readUInt24` little-endian helper convention), and swap the 4 identified call sites
from the shared big-endian `readUInt16`/`readUInt32` to these. Create
`WebpWalkerTest.kt` (this file currently has no tests at all).

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-12-webp-endianness-fix-design.md` — every requirement below traces back to it.
- Do not touch `VP8X`'s width/height (`readUInt24`, already correct) or `VP8L`'s width/height (hand-composed from individual bytes, already correct little-endian bit order) — both must keep producing identical output, verified by new regression tests.
- No new dependency.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt` | Add `readUInt16LE`/`readUInt32LE` private extensions; change 4 call sites (RIFF `file_size`, chunk-loop `chunkSize`, `VP8 `'s width, `VP8 `'s height) to use them. |
| `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt` | New file — this walker currently has no tests. |

---

### Task 1: Fix the endianness bug and add regression tests

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt` (new)

**Interfaces:**
- Produces: two new private extensions, `ByteReader.readUInt16LE(offset: Long): Int` and `ByteReader.readUInt32LE(offset: Long): Long`, used only within this file.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt`. The byte
values below for the `RIFF`/`VP8 ` case are taken directly from a real file produced
by `cwebp` (not hand-computed) — its actual on-disk bytes for the RIFF header and
`VP8 ` chunk header/payload prefix were captured and are reused verbatim here. The
`VP8X`/`VP8L` cases were computed from their documented bit-packing formulas
(matching the exact arithmetic already implemented in `decodeWebpChunk`) and the
final multi-chunk test builds on the `VP8X` case to prove the chunk-walking loop
itself advances correctly using the fixed little-endian size — the actual
real-world impact of this bug (every chunk after the first was unreachable).

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

class WebpWalkerTest {
    @Test
    fun `RIFF file_size and a VP8 chunk's size and dimensions parse as little-endian`() {
        // Bytes captured verbatim from a real cwebp-generated file's header:
        // "RIFF" + file_size(LE)=22 + "WEBP" + "VP8 " + chunk_size(LE)=10 +
        // [frame_tag(3) + sync_code(3) + width(LE 16-bit)=100 + height(LE 16-bit)=50]
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x16, 0x00, 0x00, 0x00, // file_size = 22 (LE)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x20, // "VP8 "
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0xd0.toByte(), 0x4f, 0x00, // frame tag (from a real VP8 keyframe)
            0x9d.toByte(), 0x01, 0x2a, // VP8 sync code (from a real VP8 keyframe)
            0x64, 0x00, // width = 100 (LE 16-bit)
            0x32, 0x00, // height = 50 (LE 16-bit)
        )
        byteReaderOf(bytes, "webp-walker-riff-vp8").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            assertEquals(2, nodes.size)
            val riff = nodes[0]
            assertEquals("22", riff.fields.first { it.name == "file_size" }.value)
            val vp8 = nodes[1]
            assertEquals("VP8 ", vp8.type)
            assertEquals("100", vp8.fields.first { it.name == "width" }.value)
            assertEquals("50", vp8.fields.first { it.name == "height" }.value)
            assertEquals("Lossy, 100x50", vp8.summary)
        }
    }

    @Test
    fun `VP8X width and height (already little-endian via readUInt24) are unaffected by this fix`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x58, // "VP8X"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x00, // flags
            0x00, 0x00, 0x00, // reserved
            0x1f, 0x03, 0x00, // width_minus_one = 799 (LE 24-bit) -> width = 800
            0x57, 0x02, 0x00, // height_minus_one = 599 (LE 24-bit) -> height = 600
        )
        byteReaderOf(bytes, "webp-walker-vp8x").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val vp8x = nodes[1]
            assertEquals("VP8X", vp8x.type)
            assertEquals("800", vp8x.fields.first { it.name == "width" }.value)
            assertEquals("600", vp8x.fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `VP8L width and height (already hand-composed little-endian) are unaffected by this fix`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x4c, // "VP8L"
            0x05, 0x00, 0x00, 0x00, // chunk_size = 5 (LE)
            0x2f, // VP8L signature
            0x63, 0x40, 0x0c, 0x00, // packed width=100, height=50 (see plan comments for the bit math)
        )
        byteReaderOf(bytes, "webp-walker-vp8l").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val vp8l = nodes[1]
            assertEquals("VP8L", vp8l.type)
            assertEquals("100", vp8l.fields.first { it.name == "width" }.value)
            assertEquals("50", vp8l.fields.first { it.name == "height" }.value)
        }
    }

    @Test
    fun `the chunk-walking loop advances correctly past a VP8X chunk using the fixed little-endian size`() {
        // This is the test that actually proves the real-world impact of the bug fix:
        // before this fix, VP8X's chunk_size (10, LE) was misread as a huge big-endian
        // number, so the loop's "pos + totalSize > end" check would reject the very
        // next chunk (or worse) -- every chunk after the first was unreachable.
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x24, 0x00, 0x00, 0x00, // file_size = 36 (LE)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x58, // "VP8X"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x00, 0x00, 0x00, 0x00, // flags + reserved
            0x1f, 0x03, 0x00, // width_minus_one = 799
            0x57, 0x02, 0x00, // height_minus_one = 599
            0x41, 0x4e, 0x49, 0x4d, // "ANIM"
            0x06, 0x00, 0x00, 0x00, // chunk_size = 6 (LE)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // arbitrary ANIM payload (not decoded by this task)
        )
        byteReaderOf(bytes, "webp-walker-multi-chunk").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            assertEquals(3, nodes.size)
            assertEquals("RIFF", nodes[0].type)
            assertEquals("VP8X", nodes[1].type)
            assertEquals(12L, nodes[1].offset)
            assertEquals("ANIM", nodes[2].type)
            assertEquals(30L, nodes[2].offset)
            assertEquals(14L, nodes[2].size)
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: FAIL — the first test (`RIFF file_size...`) and the last test (chunk-walk)
fail on wrong `file_size`/`width`/`height` values and/or a "Chunk extends past end of
file" warning derailing chunk discovery; the `VP8X`/`VP8L` regression tests are
expected to already PASS at this point (they exercise paths this task doesn't
change) — confirm they do, since that's the baseline these two are meant to guard.

- [ ] **Step 3: Add the little-endian helpers and fix the 4 call sites**

In `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`, add after the
existing `readUInt24` extension (at the end of the file):

```kotlin
// RIFF/WebP stores all its multi-byte integers little-endian (unlike most other
// formats this parser handles, which is why these live here rather than on the
// shared ByteReader) -- see readUInt24 above for the existing precedent.
private fun ByteReader.readUInt16LE(offset: Long): Int {
    val bytes = readBytes(offset, 2)
    return (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
}

private fun ByteReader.readUInt32LE(offset: Long): Long {
    val bytes = readBytes(offset, 4)
    return (bytes[0].toLong() and 0xFF) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[3].toLong() and 0xFF) shl 24)
}
```

Then change these 4 lines:

`parseWebpChunks`'s RIFF header construction — replace:
```kotlin
            BoxField("file_size", (reader.readUInt32(start + 4) + 8).toString(), start + 4, 4),
```
with:
```kotlin
            BoxField("file_size", (reader.readUInt32LE(start + 4) + 8).toString(), start + 4, 4),
```

`parseWebpChunks`'s chunk loop — replace:
```kotlin
        val chunkSize = reader.readUInt32(pos + 4)
```
with:
```kotlin
        val chunkSize = reader.readUInt32LE(pos + 4)
```

`decodeWebpChunk`'s `"VP8 "` branch — replace:
```kotlin
                val width = reader.readUInt16(payloadStart + 6) and 0x3FFF
                val height = reader.readUInt16(payloadStart + 8) and 0x3FFF
```
with:
```kotlin
                val width = reader.readUInt16LE(payloadStart + 6) and 0x3FFF
                val height = reader.readUInt16LE(payloadStart + 8) and 0x3FFF
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: PASS, all 4 tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures. Total test count = baseline + 4 new
tests (this file had zero tests before).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt
git commit -m "fix: read WebP/RIFF multi-byte integers as little-endian

WebpWalker.kt was reading RIFF file_size, every chunk's declared size, and
VP8's width/height with ByteReader's shared big-endian readUInt16/readUInt32
-- but RIFF-based formats (WebP, like WAV/AVI) store these little-endian.
Confirmed broken against a real cwebp-generated file before this fix:
file_size read as 3457089544 instead of 4046, and the first real chunk
immediately produced a false 'extends past end of file' warning, making
every chunk after the RIFF header unreachable.

VP8X's width/height (via the file's existing readUInt24 helper) and VP8L's
(hand-composed already in little-endian bit order) were already correct --
new regression tests confirm this fix didn't touch either path.

Added WebpWalkerTest.kt -- this walker had no tests at all before this fix.

See docs/superpowers/specs/2026-09-12-webp-endianness-fix-design.md"
```

---

### Task 2: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual, tell the user)**: Ask the user (or do it directly if in an
  environment that can run the app) to open a real WebP file — `cwebp` (available
  in this environment) can produce one from any JPEG/PNG via
  `cwebp input.jpg -o output.webp` — through the app and confirm the `RIFF` node's
  `file_size` and the first image chunk's width/height now show correct values
  instead of garbage, and that subsequent chunks (if the file has any, e.g. `EXIF`)
  are now actually reachable in the tree.
