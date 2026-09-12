# WebP Detail Parsing (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse `ICCP`, `XMP `, `ANIM`, `ANMF`, and `ALPH` WebP chunks in
`WebpWalker.kt`, which currently fall through to a bare offset/name-only node.

**Architecture:** Add one `when` branch per chunk type inside the existing
`decodeWebpChunk` function, each an early `return BoxNode(...)` (matching the
file's existing `EXIF` branch style) rather than mutating the outer
`fields`/`summary` variables (which only `VP8X`/`VP8 `/`VP8L` use). `ICCP`
reuses `decodeIccProfileHeader` (already `internal`, already shared by
JPEG/PNG). `XMP ` reuses the `"xmp"` field-name convention JPEG's APP1
established. `ANIM`/`ANMF`/`ALPH` are new fixed-layout decoders using the
file's existing `readUInt8`/`readUInt24`/`readUInt16LE` helpers (no new
low-level helpers needed).

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-12-webp-detail-parsing-design.md`
  (as corrected during plan-writing — the ANMF flags byte is
  `bits 7-2 reserved, bit 1 = blending, bit 0 = disposal`, verified against
  Google's official WebP Container Specification's raw bit-diagram; an
  earlier draft of the spec had bit6/bit7 and was wrong).
- Do not recurse into `ANMF`'s nested sub-chunks (`ALPH`/`VP8 `/`VP8L`) —
  parse only the 16-byte `ANMF` header, summarize the remaining bytes as
  unparsed. This was confirmed directly with the user (not assumed).
- Do not implement actual alpha-bitstream or animation-frame image decoding
  for `ALPH` — header fields only.
- No changes to `VP8X`/`VP8 `/`VP8L`/`EXIF`'s existing behavior.
- No new dependency.
- `WebpWalker.kt`'s two little-endian helpers, `readUInt16LE`/`readUInt32LE`
  (private extensions on `ByteReader`, at the bottom of the file), and the
  pre-existing `readUInt24` are the only low-level reads this plan needs —
  no new helper functions required.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt` | Add 5 new `when` branches to `decodeWebpChunk` (`ICCP`, `XMP `, `ANIM`, `ANMF`, `ALPH`). |
| `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt` | Add tests for each new chunk type, following the file's existing byte-array-construction convention. |

---

### Task 1: `ICCP` and `XMP ` chunks

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField>` (already `internal` in `JpegWalker.kt`, same signature PNG's Phase 2 `iCCP` already reuses unchanged).
- Produces: nothing new consumed by later tasks in this plan — `ICCP`/`XMP ` are independent of `ANIM`/`ANMF`/`ALPH`.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt` (inside
the existing `class WebpWalkerTest { ... }`, after the last existing test):

```kotlin
    @Test
    fun `ICCP payload is parsed as a raw (uncompressed) 128-byte ICC header`() {
        // The same 128-byte ICC.1 header bytes already verified correct by
        // JpegWalkerTest's "APP2 ICC profile first chunk parses the full
        // 128-byte header" test -- reused here since WebP's ICCP chunk is
        // this exact 128-byte header with no name prefix and no compression
        // (unlike PNG's iCCP, which needed zlib-inflate in Phase 2).
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
        val header = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x49, 0x43, 0x43, 0x50, // "ICCP"
            0x80, 0x00, 0x00, 0x00, // chunk_size = 128 (LE)
        )
        val bytes = header + iccHeaderBytes
        byteReaderOf(bytes, "webp-walker-iccp").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[1]
            assertEquals("ICCP", iccp.type)
            assertEquals("2.1.0", iccp.fields.first { it.name == "version" }.value)
            assertEquals("mntr", iccp.fields.first { it.name == "profile_class" }.value)
            assertEquals("RGB", iccp.fields.first { it.name == "data_colour_space" }.value)
            assertEquals("ICC Profile v2.1.0 (128 bytes)", iccp.summary)
        }
    }

    @Test
    fun `ICCP shorter than 128 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x49, 0x43, 0x43, 0x50, // "ICCP"
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (LE)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, // 10 arbitrary bytes
        )
        byteReaderOf(bytes, "webp-walker-iccp-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[1]
            assertEquals("ICCP", iccp.type)
            assertEquals(0, iccp.fields.size)
            assertEquals(listOf("ICC profile too short to contain a valid header"), iccp.warnings)
        }
    }

    @Test
    fun `XMP payload is exposed as a single UTF-8 text field`() {
        val text = "<x:xmpmeta></x:xmpmeta>" // 24 chars, ASCII, even length
        val payload = text.toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x58, 0x4d, 0x50, 0x20, // "XMP " (trailing space, same FourCC padding convention as "VP8 ")
            payload.size.toByte(), 0x00, 0x00, 0x00, // chunk_size (LE) -- payload.size is 24, fits in one byte
        ) + payload
        byteReaderOf(bytes, "webp-walker-xmp").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val xmp = nodes[1]
            assertEquals("XMP ", xmp.type)
            assertEquals(text, xmp.fields.first { it.name == "xmp" }.value)
            assertEquals("XMP (24 chars)", xmp.summary)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: FAIL — `ICCP`/`XMP ` currently fall through to the bare
`BoxNode(type, offset, headerSize, totalSize)` fallback with no fields and no
summary, so every `fields.first { ... }` lookup throws
`NoSuchElementException` and every summary assertion fails.

- [ ] **Step 3: Implement the two decoders**

In `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`, inside the
`when (type) { ... }` block in `decodeWebpChunk` (add these branches after
the existing `"EXIF" -> { ... }` branch, before the closing `}` of the
`when`):

```kotlin
        "ICCP" -> {
            if (payloadSize < 128) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ICC profile too short to contain a valid header"))
            }
            val headerBytes = reader.readBytes(payloadStart, 128)
            val headerFields = decodeIccProfileHeader(headerBytes, payloadStart)
            val version = headerFields.first { it.name == "version" }.value
            return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = headerFields, summary = "ICC Profile v$version ($payloadSize bytes)")
        }
        "XMP " -> {
            val text = String(reader.readBytes(payloadStart, payloadSize.toInt()), Charsets.UTF_8)
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(BoxField("xmp", text, payloadStart, payloadSize)),
                summary = "XMP (${text.length} chars)",
            )
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: PASS, all tests (existing 4 + 3 new).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt
git commit -m "feat: parse WebP ICCP and XMP chunks

ICCP holds a raw (uncompressed) ICC profile -- no name prefix, no
compression, unlike PNG's iCCP -- so it reuses decodeIccProfileHeader
directly with no zlib-inflate detour. XMP reuses the single-'xmp'-field
UI convention JPEG's APP1 XMP handling already established.

See docs/superpowers/specs/2026-09-12-webp-detail-parsing-design.md"
```

---

### Task 2: `ANIM` chunk

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt`

**Interfaces:**
- Consumes: `reader.readUInt8(offset: Long): Int`, `reader.readUInt16LE(offset: Long): Int` (both already exist in this file/`ByteReader`).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing tests**

Add to `WebpWalkerTest.kt`:

```kotlin
    @Test
    fun `ANIM decodes background color and a non-zero loop count`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x41, 0x4e, 0x49, 0x4d, // "ANIM"
            0x06, 0x00, 0x00, 0x00, // chunk_size = 6 (LE)
            0x11, 0x22, 0x33, 0xff.toByte(), // background color: B=0x11, G=0x22, R=0x33, A=0xFF
            0x05, 0x00, // loop_count = 5 (LE)
        )
        byteReaderOf(bytes, "webp-walker-anim").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals("ANIM", anim.type)
            assertEquals("#332211FF", anim.fields.first { it.name == "background_color" }.value)
            assertEquals("5", anim.fields.first { it.name == "loop_count" }.value)
            assertEquals("Loop count: 5", anim.summary)
        }
    }

    @Test
    fun `ANIM loop count of zero is labeled infinite`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x49, 0x4d,
            0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // background color = black, opaque=0 (not asserted here)
            0x00, 0x00, // loop_count = 0
        )
        byteReaderOf(bytes, "webp-walker-anim-infinite").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals("0 (infinite)", anim.fields.first { it.name == "loop_count" }.value)
            assertEquals("Loop count: 0 (infinite)", anim.summary)
        }
    }

    @Test
    fun `ANIM shorter than 6 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x49, 0x4d,
            0x03, 0x00, 0x00, 0x00, // chunk_size = 3 (too short)
            0x01, 0x02, 0x03,
        )
        byteReaderOf(bytes, "webp-walker-anim-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anim = nodes[1]
            assertEquals(0, anim.fields.size)
            assertEquals(listOf("ANIM chunk too short to contain background color and loop count"), anim.warnings)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: FAIL for the 3 new tests (same reason as Task 1: `ANIM` currently
falls through to the bare fallback node).

- [ ] **Step 3: Implement the decoder**

Add to the `when (type)` block in `decodeWebpChunk`, after the `"XMP "`
branch added in Task 1:

```kotlin
        "ANIM" -> {
            if (payloadSize < 6) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ANIM chunk too short to contain background color and loop count"))
            }
            val blue = reader.readUInt8(payloadStart)
            val green = reader.readUInt8(payloadStart + 1)
            val red = reader.readUInt8(payloadStart + 2)
            val alpha = reader.readUInt8(payloadStart + 3)
            val backgroundColor = "#%02X%02X%02X%02X".format(red, green, blue, alpha)
            val loopCount = reader.readUInt16LE(payloadStart + 4)
            val loopCountLabel = if (loopCount == 0) "0 (infinite)" else loopCount.toString()
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(
                    BoxField("background_color", backgroundColor, payloadStart, 4),
                    BoxField("loop_count", loopCountLabel, payloadStart + 4, 2),
                ),
                summary = "Loop count: $loopCountLabel",
            )
        }
```

- [ ] **Step 4: Update the stale comment in the existing multi-chunk test**

The existing test `the chunk-walking loop advances correctly past a VP8X
chunk using the fixed little-endian size` (added by the prerequisite
little-endian fix, before this plan) builds a trailing `ANIM` chunk with an
all-zero 6-byte payload and the comment `// arbitrary ANIM payload (not
decoded by this task)`. That comment is now stale — this task decodes `ANIM`.
The test doesn't assert on the `ANIM` node's fields (it's testing the
chunk-walking loop, not `ANIM` decoding, which has its own dedicated tests
above), so no behavior changes, but update the comment for accuracy. Find
this line in `WebpWalkerTest.kt`:

```kotlin
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // arbitrary ANIM payload (not decoded by this task)
```

Replace it with:

```kotlin
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // all-zero ANIM payload (decodes to background_color #00000000, loop_count 0/infinite -- not asserted here, this test is about the chunk-walking loop, not ANIM's fields; see the dedicated ANIM tests above)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: PASS, all tests.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt
git commit -m "feat: parse WebP ANIM chunk (background color, loop count)

Background Color is stored BGRA in the file; displayed reordered as
#RRGGBBAA to match the conventional hex-color reading order (same
convention PNG Phase 2 used for PLTE's #RRGGBB). Loop count of 0 is
labeled '0 (infinite)' per the WebP spec's meaning for that value.

See docs/superpowers/specs/2026-09-12-webp-detail-parsing-design.md"
```

---

### Task 3: `ANMF` chunk

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt`

**Interfaces:**
- Consumes: `reader.readUInt24(offset: Long): Int` (already exists, private extension in this file, already used by `VP8X`), `reader.readUInt8(offset: Long): Int`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing tests**

Add to `WebpWalkerTest.kt`:

```kotlin
    @Test
    fun `ANMF decodes its 16-byte frame header and summarizes the unparsed frame data`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x00, 0x00, 0x00, 0x00, // file_size (not asserted in this test)
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x41, 0x4e, 0x4d, 0x46, // "ANMF"
            0x14, 0x00, 0x00, 0x00, // chunk_size = 20 (LE) -- 16-byte header + 4 bytes of unparsed frame data
            0x05, 0x00, 0x00, // frame_x raw = 5 -> frame_x = 10 (raw * 2)
            0x03, 0x00, 0x00, // frame_y raw = 3 -> frame_y = 6
            0x9f.toByte(), 0x00, 0x00, // width_minus_one = 159 -> width = 160
            0x77, 0x00, 0x00, // height_minus_one = 119 -> height = 120
            0x64, 0x00, 0x00, // duration raw = 100 -> duration_ms = 100
            0x03, // flags: bit1 (blending) = 1, bit0 (disposal) = 1
            0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(), // 4 bytes of unparsed frame sub-chunk data
        )
        byteReaderOf(bytes, "webp-walker-anmf").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals("ANMF", anmf.type)
            assertEquals("10", anmf.fields.first { it.name == "frame_x" }.value)
            assertEquals("6", anmf.fields.first { it.name == "frame_y" }.value)
            assertEquals("160", anmf.fields.first { it.name == "width" }.value)
            assertEquals("120", anmf.fields.first { it.name == "height" }.value)
            assertEquals("100", anmf.fields.first { it.name == "duration_ms" }.value)
            assertEquals("Do not blend", anmf.fields.first { it.name == "blending" }.value)
            assertEquals("Dispose to background", anmf.fields.first { it.name == "disposal" }.value)
            assertEquals("160x120, 100ms (frame data: 4 bytes, not parsed)", anmf.summary)
        }
    }

    @Test
    fun `ANMF flags of zero mean blend and do not dispose`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x4d, 0x46,
            0x10, 0x00, 0x00, 0x00, // chunk_size = 16 (header only, no trailing frame data)
            0x00, 0x00, 0x00, // frame_x raw = 0
            0x00, 0x00, 0x00, // frame_y raw = 0
            0x00, 0x00, 0x00, // width_minus_one = 0 -> width = 1
            0x00, 0x00, 0x00, // height_minus_one = 0 -> height = 1
            0x00, 0x00, 0x00, // duration raw = 0
            0x00, // flags = 0
        )
        byteReaderOf(bytes, "webp-walker-anmf-zero-flags").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals("Blend", anmf.fields.first { it.name == "blending" }.value)
            assertEquals("Do not dispose", anmf.fields.first { it.name == "disposal" }.value)
            assertEquals("1x1, 0ms (frame data: 0 bytes, not parsed)", anmf.summary)
        }
    }

    @Test
    fun `ANMF shorter than 16 bytes produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4e, 0x4d, 0x46,
            0x0a, 0x00, 0x00, 0x00, // chunk_size = 10 (too short for the 16-byte header)
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        )
        byteReaderOf(bytes, "webp-walker-anmf-short").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val anmf = nodes[1]
            assertEquals(0, anmf.fields.size)
            assertEquals(listOf("ANMF chunk too short to contain its frame header"), anmf.warnings)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: FAIL for the 3 new tests.

- [ ] **Step 3: Implement the decoder**

Add to the `when (type)` block in `decodeWebpChunk`, after the `"ANIM"`
branch added in Task 2. The flags-byte bit layout below is
`| Reserved (6 bits) | B | D |` per Google's official WebP Container
Specification's raw bit-diagram — blending is bit 1 (`0x02`), disposal is
bit 0 (`0x01`):

```kotlin
        "ANMF" -> {
            if (payloadSize < 16) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ANMF chunk too short to contain its frame header"))
            }
            val frameX = reader.readUInt24(payloadStart) * 2
            val frameY = reader.readUInt24(payloadStart + 3) * 2
            val width = reader.readUInt24(payloadStart + 6) + 1
            val height = reader.readUInt24(payloadStart + 9) + 1
            val durationMs = reader.readUInt24(payloadStart + 12)
            val flags = reader.readUInt8(payloadStart + 15)
            val blending = if (flags and 0x02 != 0) "Do not blend" else "Blend"
            val disposal = if (flags and 0x01 != 0) "Dispose to background" else "Do not dispose"
            val frameDataSize = payloadSize - 16
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(
                    BoxField("frame_x", frameX.toString(), payloadStart, 3),
                    BoxField("frame_y", frameY.toString(), payloadStart + 3, 3),
                    BoxField("width", width.toString(), payloadStart + 6, 3),
                    BoxField("height", height.toString(), payloadStart + 9, 3),
                    BoxField("duration_ms", durationMs.toString(), payloadStart + 12, 3),
                    BoxField("blending", blending, payloadStart + 15, 1),
                    BoxField("disposal", disposal, payloadStart + 15, 1),
                ),
                summary = "${width}x${height}, ${durationMs}ms (frame data: $frameDataSize bytes, not parsed)",
            )
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: PASS, all tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt
git commit -m "feat: parse WebP ANMF chunk header (position, size, duration, flags)

Parses only the 16-byte ANMF frame header; the trailing per-frame image
sub-chunks (ALPH/VP8/VP8L) are summarized by byte count, not recursed
into as tree nodes -- confirmed with the user during design.

Flags-byte bit positions verified against Google's official WebP
Container Specification's raw bit-diagram (| Reserved(6) | B | D |):
blending is bit 1, disposal is bit 0 -- corrected from an initial
bit6/bit7 assumption caught during plan-writing.

See docs/superpowers/specs/2026-09-12-webp-detail-parsing-design.md"
```

---

### Task 4: `ALPH` chunk

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt`

**Interfaces:**
- Consumes: `reader.readUInt8(offset: Long): Int`.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Write the failing tests**

Add to `WebpWalkerTest.kt`:

```kotlin
    private fun alphBytes(headerByte: Byte): ByteArray = byteArrayOf(
        0x52, 0x49, 0x46, 0x46, // "RIFF"
        0x00, 0x00, 0x00, 0x00, // file_size (not asserted in these tests)
        0x57, 0x45, 0x42, 0x50, // "WEBP"
        0x41, 0x4c, 0x50, 0x48, // "ALPH"
        0x01, 0x00, 0x00, 0x00, // chunk_size = 1
        headerByte,
    )

    @Test
    fun `ALPH decodes all four preprocessing values`() {
        val labels = listOf("None", "Level reduction", "Reserved (2)", "Reserved (3)")
        for (value in 0..3) {
            val bytes = alphBytes((value shl 4).toByte())
            byteReaderOf(bytes, "webp-walker-alph-preprocessing-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "preprocessing" }.value)
            }
        }
    }

    @Test
    fun `ALPH decodes all four filtering_method values`() {
        val labels = listOf("None", "Horizontal", "Vertical", "Gradient")
        for (value in 0..3) {
            val bytes = alphBytes((value shl 2).toByte())
            byteReaderOf(bytes, "webp-walker-alph-filtering-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "filtering_method" }.value)
            }
        }
    }

    @Test
    fun `ALPH decodes all four compression_method values`() {
        val labels = listOf("None", "Lossless (WebP)", "Reserved (2)", "Reserved (3)")
        for (value in 0..3) {
            val bytes = alphBytes(value.toByte())
            byteReaderOf(bytes, "webp-walker-alph-compression-$value").use { reader ->
                val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
                val alph = nodes[1]
                assertEquals(labels[value], alph.fields.first { it.name == "compression_method" }.value)
            }
        }
    }

    @Test
    fun `ALPH exposes non-zero reserved bits and builds a combined summary`() {
        // byte 0b11_01_10_11: reserved=3, preprocessing=1, filtering=2, compression=3
        val bytes = alphBytes(0xdb.toByte())
        byteReaderOf(bytes, "webp-walker-alph-combined").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val alph = nodes[1]
            assertEquals("3", alph.fields.first { it.name == "reserved" }.value)
            assertEquals("Level reduction", alph.fields.first { it.name == "preprocessing" }.value)
            assertEquals("Vertical", alph.fields.first { it.name == "filtering_method" }.value)
            assertEquals("Reserved (3)", alph.fields.first { it.name == "compression_method" }.value)
            assertEquals("Vertical filtering, Reserved (3) compression", alph.summary)
        }
    }

    @Test
    fun `ALPH with an empty payload produces a warning and no fields`() {
        val bytes = byteArrayOf(
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50,
            0x41, 0x4c, 0x50, 0x48,
            0x00, 0x00, 0x00, 0x00, // chunk_size = 0
        )
        byteReaderOf(bytes, "webp-walker-alph-empty").use { reader ->
            val nodes = parseWebpChunks(reader, 0, bytes.size.toLong())
            val alph = nodes[1]
            assertEquals(0, alph.fields.size)
            assertEquals(listOf("ALPH chunk too short to contain its header byte"), alph.warnings)
        }
    }
```

Verify the combined-byte test's bit math before running: `0xdb` = `1101 1011`
in binary. Reading MSB-first as `RR PP FF CC` (2 bits each): `RR=11`(3),
`PP=01`(1), `FF=10`(2), `CC=11`(3) — reserved=3, preprocessing=1 ("Level
reduction"), filtering_method=2 ("Vertical"), compression_method=3
("Reserved (3)"), matching the assertions above.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: FAIL for all 5 new tests.

- [ ] **Step 3: Implement the decoder**

Add to the `when (type)` block in `decodeWebpChunk`, after the `"ANMF"`
branch added in Task 3:

```kotlin
        "ALPH" -> {
            if (payloadSize < 1) {
                return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, warnings = listOf("ALPH chunk too short to contain its header byte"))
            }
            val byte = reader.readUInt8(payloadStart)
            val reserved = (byte shr 6) and 0x3
            val preprocessing = (byte shr 4) and 0x3
            val filteringMethod = (byte shr 2) and 0x3
            val compressionMethod = byte and 0x3
            val preprocessingLabel = when (preprocessing) {
                0 -> "None"
                1 -> "Level reduction"
                else -> "Reserved ($preprocessing)"
            }
            val filteringLabel = when (filteringMethod) {
                0 -> "None"
                1 -> "Horizontal"
                2 -> "Vertical"
                else -> "Gradient"
            }
            val compressionLabel = when (compressionMethod) {
                0 -> "None"
                1 -> "Lossless (WebP)"
                else -> "Reserved ($compressionMethod)"
            }
            return BoxNode(
                type = type, offset = offset, headerSize = 8, size = totalSize,
                fields = listOf(
                    BoxField("reserved", reserved.toString(), payloadStart, 1),
                    BoxField("preprocessing", preprocessingLabel, payloadStart, 1),
                    BoxField("filtering_method", filteringLabel, payloadStart, 1),
                    BoxField("compression_method", compressionLabel, payloadStart, 1),
                ),
                summary = "$filteringLabel filtering, $compressionLabel compression",
            )
        }
```

Note: `filteringMethod` and `compressionMethod` are each masked to 2 bits
(`and 0x3`), so their range is exactly `0..3` — the `when` branches above are
exhaustive over that range even though the `else` arm is written as a plain
`else` rather than an explicit `3 ->` (Kotlin requires an `else` for a
non-sealed-type `when` used as an expression; `filteringMethod`'s `3` case is
folded into `else -> "Gradient"` since 3 is its only remaining possibility).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.WebpWalkerTest"`
Expected: PASS, all tests.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/WebpWalker.kt app/src/test/kotlin/com/multiviewer/parser/WebpWalkerTest.kt
git commit -m "feat: parse WebP ALPH chunk header (preprocessing, filtering, compression)

1-byte header split into 4 bit-packed 2-bit fields per the WebP spec.
Only the header is parsed -- the alpha bitstream itself is not decoded,
matching this file's existing VP8/VP8L posture of parsing bitstream
headers, not image data.

See docs/superpowers/specs/2026-09-12-webp-detail-parsing-design.md"
```

---

### Task 5: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Check for `img2webp` (`which img2webp`) alongside
  the already-confirmed `cwebp`/`dwebp`/`webpmux`. Generate real WebP files
  covering each new chunk type and open them through the app (CLI `dump` +
  live GUI), confirming each renders sensibly with no warnings:
  - An ICC profile: `cwebp -icc <path-to-a-real-icc-profile> input.png -o icc.webp` (macOS ships ICC profiles under `/System/Library/ColorSync/Profiles/` — reuse the same approach prior phases used) — confirm `ICCP` shows a `version`/`profile_class`/etc. matching the source profile.
  - XMP metadata: `cwebp -metadata xmp input.png -o xmp.webp` if the source has XMP (or `exiftool`/similar to inject XMP first) — confirm `XMP ` shows the expected text.
  - Animation: `img2webp -loop 3 -d 100 frame1.png frame2.png -o anim.webp` (if `img2webp` is available) — confirm `ANIM` shows `loop_count: 3` and `ANMF` nodes show correct `width`/`height`/`duration_ms`. If `img2webp` isn't available, note this in the report and skip the animation-specific manual check (the unit tests from Task 2/3 already cover the byte-level decoding logic).
  - Alpha: any WebP with a genuine alpha channel (e.g. `cwebp -alpha_q 100 input_with_alpha.png -o alpha.webp` from a PNG with transparency) — confirm `ALPH` shows sensible `filtering_method`/`compression_method` labels.
