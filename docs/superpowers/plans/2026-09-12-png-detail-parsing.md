# PNG Detail Parsing Implementation Plan (Phase 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse PNG's `gAMA`, `cHRM`, `sRGB`, `tIME`, `iCCP`, `zTXt`, `iTXt`, and
`PLTE` chunks into detailed fields, closing the same "offset/name only" gap Phase 1
closed for JPEG.

**Architecture:** Refactor Phase 1's ICC header parser to operate on a plain
`ByteArray` (rather than a live `ByteReader`) so it's reusable for `iCCP`'s
zlib-decompressed profile bytes. Add one decode function per new PNG chunk type to
`PngWalker.kt`, following that file's existing per-chunk-function style. A small
shared zlib-inflate helper backs `iCCP`/`zTXt`/`iTXt`.

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`), `java.util.zip.Inflater`
(JDK-provided, no new dependency).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md` — every requirement below traces back to it.
- **Out of scope, do not implement:** `tRNS`, `bKGD` (need cross-chunk `color_type` state — separate follow-up), APNG chunks (`acTL`/`fcTL`/`fdAT`), per-entry detail for every `PLTE` color (bounded preview only, see Task 5).
- Decompression (`iCCP`/`zTXt`/`iTXt`) is capped at 64 MB of output and must never crash or hang on malformed/adversarial input — a failure degrades to a warning with whatever fields were already extracted before the failure, never fabricated data.
- No new dependency. `java.util.zip.Inflater` is JDK-provided.
- Every existing chunk decoder in `PngWalker.kt` (`decodeIhdr`, `decodePhys`, `decodeText`, `decodeExifChunk`) and every existing JPEG decoder in `JpegWalker.kt` besides the ICC-header-parsing trio being refactored is untouched.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt` | `decodeIccProfileHeader`/`readIccSignature`/`readS15Fixed16` change from `(ByteReader, Long offset)` to `(ByteArray, Int/Long offset)`, become `internal` (from `private`); one call-site update in `decodeApp2`. Adds 4 small private `ByteArray` bit-reading extensions. |
| `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt` | Adds `decodeGama`, `decodeChrm`, `decodeSrgb`, `decodeTime`, `decodeIccp`, `decodeZtxt`, `decodeItxt`, `decodePlte`, plus a shared `inflateZlib` helper and a `PNG_RENDERING_INTENT_NAMES` map; 8 new `when` branches in `decodePngChunk`. |
| `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` | No new tests needed — existing 3 ICC tests are the regression check for Task 1's refactor. |
| `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt` | New tests for all 8 new chunk types. |

---

### Task 1: Refactor the ICC header parser to operate on `ByteArray`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`

**Interfaces:**
- Produces: `internal fun decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField>`, `internal fun readIccSignature(bytes: ByteArray, offset: Int): String`, `internal fun readS15Fixed16(bytes: ByteArray, offset: Int): Double` — all now callable from `PngWalker.kt` (same Gradle module).
- Consumes: nothing new.

This is a pure refactor — no behavior change for the existing JPEG APP2 ICC path.
Verified by Phase 1's existing 3 tests continuing to pass unchanged; no new test is
needed for this task itself (Task 3 below adds the first real test of the
`ByteArray`-based API, via PNG's `iCCP`).

- [ ] **Step 1: Confirm the current tests pass (pre-refactor baseline)**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS (33/33 — this is the safety net the refactor must not break).

- [ ] **Step 2: Replace the ICC helper functions**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, replace the block from
`private val RENDERING_INTENT_NAMES = mapOf(` through `decodeIccProfileHeader`'s
closing `}` (currently lines 228-313) with:

```kotlin
internal val RENDERING_INTENT_NAMES = mapOf(
    0 to "Perceptual",
    1 to "Media-Relative Colorimetric",
    2 to "Saturation",
    3 to "ICC-Absolute Colorimetric",
)

private fun ByteArray.u8At(i: Int): Int = this[i].toInt() and 0xFF
private fun ByteArray.u16At(i: Int): Int = (u8At(i) shl 8) or u8At(i + 1)
private fun ByteArray.u32At(i: Int): Long =
    (u8At(i).toLong() shl 24) or (u8At(i + 1).toLong() shl 16) or (u8At(i + 2).toLong() shl 8) or u8At(i + 3).toLong()
private fun ByteArray.u64At(i: Int): Long = (u32At(i) shl 32) or u32At(i + 4)

// Reads a 4-byte ICC signature from [bytes] at [offset]: printable ASCII (trimmed of
// trailing padding spaces) when every byte is in the printable range, "(unspecified)"
// for an all-zero signature (legitimately common -- several ICC header fields are
// optional), or a hex dump for anything else rather than risking mojibake.
internal fun readIccSignature(bytes: ByteArray, offset: Int): String {
    val slice = bytes.copyOfRange(offset, offset + 4)
    if (slice.all { it == 0.toByte() }) return "(unspecified)"
    val printable = slice.all { b -> val v = b.toInt() and 0xFF; v in 0x20..0x7E }
    return if (printable) {
        String(slice, Charsets.US_ASCII).trimEnd(' ')
    } else {
        "0x" + slice.joinToString("") { "%02X".format(it) }
    }
}

// ICC's s15Fixed16Number: a signed 32-bit big-endian integer, divided by 65536 to
// get the real value (used for the PCS illuminant XYZ triplet).
internal fun readS15Fixed16(bytes: ByteArray, offset: Int): Double {
    val raw = bytes.u32At(offset)
    val signed = if (raw > 0x7FFFFFFFL) raw - 0x100000000L else raw
    return signed / 65536.0
}

// Parses the 128-byte ICC.1 profile header (a stable format, unchanged since the
// 2001 spec) out of [headerBytes] (exactly 128 bytes, already read/decompressed by
// the caller -- this function does no I/O of its own, so it works equally for a live
// file-backed read (JPEG APP2, below) or an in-memory decompressed buffer (PNG's
// iCCP chunk). [baseOffset] is only used to compute the returned BoxFields' offsets,
// so a caller with no real file position for these bytes (e.g. post-decompression)
// can pass whatever's most useful -- this codebase's existing convention for such
// synthetic fields (e.g. decodeApp2's MPF entries) is to point at the nearest real
// chunk/byte range rather than a byte-exact position that doesn't exist.
internal fun decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField> {
    val profileSize = headerBytes.u32At(0)
    val cmmType = readIccSignature(headerBytes, 4)
    val versionByte0 = headerBytes.u8At(8)
    val versionByte1 = headerBytes.u8At(9)
    val version = "$versionByte0.${versionByte1 shr 4}.${versionByte1 and 0x0F}"
    val profileClass = readIccSignature(headerBytes, 12)
    val dataColourSpace = readIccSignature(headerBytes, 16)
    val pcs = readIccSignature(headerBytes, 20)
    val year = headerBytes.u16At(24)
    val month = headerBytes.u16At(26)
    val day = headerBytes.u16At(28)
    val hour = headerBytes.u16At(30)
    val minute = headerBytes.u16At(32)
    val second = headerBytes.u16At(34)
    val dateTimeCreated = "%04d-%02d-%02d %02d:%02d:%02d UTC".format(year, month, day, hour, minute, second)
    val primaryPlatform = readIccSignature(headerBytes, 40)
    val profileFlags = headerBytes.u32At(44)
    val deviceManufacturer = readIccSignature(headerBytes, 48)
    val deviceModel = readIccSignature(headerBytes, 52)
    val deviceAttributes = headerBytes.u64At(56)
    val renderingIntentCode = headerBytes.u32At(64).toInt()
    val renderingIntent = RENDERING_INTENT_NAMES[renderingIntentCode] ?: "Unknown ($renderingIntentCode)"
    val illumX = readS15Fixed16(headerBytes, 68)
    val illumY = readS15Fixed16(headerBytes, 72)
    val illumZ = readS15Fixed16(headerBytes, 76)
    val profileCreator = readIccSignature(headerBytes, 80)
    val profileIdBytes = headerBytes.copyOfRange(84, 100)
    val profileId = if (profileIdBytes.all { it == 0.toByte() }) {
        "(not set)"
    } else {
        profileIdBytes.joinToString("") { "%02x".format(it) }
    }

    return listOf(
        BoxField("profile_size", "$profileSize bytes", baseOffset, 4),
        BoxField("cmm_type", cmmType, baseOffset + 4, 4),
        BoxField("version", version, baseOffset + 8, 4),
        BoxField("profile_class", profileClass, baseOffset + 12, 4),
        BoxField("data_colour_space", dataColourSpace, baseOffset + 16, 4),
        BoxField("pcs", pcs, baseOffset + 20, 4),
        BoxField("date_time_created", dateTimeCreated, baseOffset + 24, 12),
        BoxField("primary_platform", primaryPlatform, baseOffset + 40, 4),
        BoxField("profile_flags", "0x${profileFlags.toString(16).padStart(8, '0')}", baseOffset + 44, 4),
        BoxField("device_manufacturer", deviceManufacturer, baseOffset + 48, 4),
        BoxField("device_model", deviceModel, baseOffset + 52, 4),
        BoxField("device_attributes", "0x${deviceAttributes.toString(16).padStart(16, '0')}", baseOffset + 56, 8),
        BoxField("rendering_intent", renderingIntent, baseOffset + 64, 4),
        BoxField("pcs_illuminant", "X=%.4f, Y=%.4f, Z=%.4f".format(illumX, illumY, illumZ), baseOffset + 68, 12),
        BoxField("profile_creator", profileCreator, baseOffset + 80, 4),
        BoxField("profile_id", profileId, baseOffset + 84, 16),
    )
}
```

- [ ] **Step 3: Update the JPEG APP2 call site**

In the same file's `decodeApp2` function, find:

```kotlin
            if (chunkSequenceNumber == 1 && payloadEnd - headerStart >= 128) {
                val headerFields = decodeIccProfileHeader(reader, headerStart)
```

Replace with:

```kotlin
            if (chunkSequenceNumber == 1 && payloadEnd - headerStart >= 128) {
                val headerBytes = reader.readBytes(headerStart, 128)
                val headerFields = decodeIccProfileHeader(headerBytes, headerStart)
```

- [ ] **Step 4: Run the tests to confirm the refactor is behavior-preserving**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS, all 33 tests unchanged (same assertions as Step 1, now exercising
the refactored code path).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt
git commit -m "refactor: make the ICC header parser operate on ByteArray, not ByteReader

decodeIccProfileHeader/readIccSignature/readS15Fixed16 now read from a
plain ByteArray instead of a live file-backed ByteReader, and are internal
instead of private. Behavior-preserving for the existing JPEG APP2 path
(verified by its unchanged existing tests) -- this is groundwork so PNG's
iCCP chunk (Task 3) can reuse the same 128-byte ICC.1 header parser on its
zlib-decompressed profile bytes, which have no real file offset to read
through a ByteReader.

See docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md"
```

---

### Task 2: PNG `gAMA`, `cHRM`, `sRGB`, `tIME`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: 4 new private functions (`decodeGama`, `decodeChrm`, `decodeSrgb`, `decodeTime`), a `PNG_RENDERING_INTENT_NAMES` map, all internal to `PngWalker.kt`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt` (inside the
`PngWalkerTest` class, after its last existing test):

```kotlin
    @Test
    fun `decodes gAMA as a gamma value`() {
        val bytes = pngChunk("gAMA", byteArrayOf(0x00, 0x00, 0xB1.toByte(), 0x8F.toByte())) // 45455 -> 0.45455
        readerOver(bytes, "png-walker-gama").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val gama = nodes[0]
            assertEquals("gAMA", gama.type)
            assertEquals("0.45455", gama.fields.first { it.name == "gamma" }.value)
            assertEquals("gamma=0.45455", gama.summary)
        }
    }

    @Test
    fun `decodes cHRM chromaticity points`() {
        val data = byteArrayOf(
            0x00, 0x00, 0x7A, 0x26, // white_x = 31270 -> 0.3127
            0x00, 0x00, 0x80.toByte(), 0x84.toByte(), // white_y = 32900 -> 0.3290
            0x00, 0x00, 0xFA.toByte(), 0x00, // red_x = 64000 -> 0.6400
            0x00, 0x00, 0x80.toByte(), 0xE8.toByte(), // red_y = 33000 -> 0.3300
            0x00, 0x00, 0x75, 0x30, // green_x = 30000 -> 0.3000
            0x00, 0x00, 0xEA.toByte(), 0x60, // green_y = 60000 -> 0.6000
            0x00, 0x00, 0x3A, 0x98.toByte(), // blue_x = 15000 -> 0.1500
            0x00, 0x00, 0x17, 0x70, // blue_y = 6000 -> 0.0600
        )
        val bytes = pngChunk("cHRM", data)
        readerOver(bytes, "png-walker-chrm").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val chrm = nodes[0]
            assertEquals("cHRM", chrm.type)
            assertEquals("x=0.3127, y=0.3290", chrm.fields.first { it.name == "white_point" }.value)
            assertEquals("x=0.6400, y=0.3300", chrm.fields.first { it.name == "red" }.value)
            assertEquals("x=0.3000, y=0.6000", chrm.fields.first { it.name == "green" }.value)
            assertEquals("x=0.1500, y=0.0600", chrm.fields.first { it.name == "blue" }.value)
        }
    }

    @Test
    fun `decodes sRGB rendering intent`() {
        val bytes = pngChunk("sRGB", byteArrayOf(0x00)) // 0 = Perceptual
        readerOver(bytes, "png-walker-srgb").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val srgb = nodes[0]
            assertEquals("sRGB", srgb.type)
            assertEquals("Perceptual", srgb.fields.first { it.name == "rendering_intent" }.value)
            assertEquals("Perceptual", srgb.summary)
        }
    }

    @Test
    fun `decodes tIME last-modified timestamp`() {
        val bytes = pngChunk("tIME", byteArrayOf(0x07, 0xE8.toByte(), 0x01, 0x0F, 0x0C, 0x1E, 0x00)) // 2024-01-15 12:30:00
        readerOver(bytes, "png-walker-time").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val time = nodes[0]
            assertEquals("tIME", time.type)
            assertEquals("2024-01-15 12:30:00 UTC", time.fields.first { it.name == "last_modified" }.value)
            assertEquals("2024-01-15 12:30:00 UTC", time.summary)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: FAIL — these 4 chunk types currently fall to the generic field-less
fallback, so `fields.first { ... }` throws `NoSuchElementException`.

- [ ] **Step 3: Implement the 4 decoders**

In `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`, add after the existing
`decodeExifChunk` function:

```kotlin
private fun decodeGama(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 16) { // 8 (length+type) + 4 (gAMA body) + 4 (crc)
        return BoxNode(type = "gAMA", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("gAMA chunk too short to contain the gamma value"))
    }
    val gamma = reader.readUInt32(dataStart) / 100000.0
    return BoxNode(
        type = "gAMA", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("gamma", "%.5f".format(gamma), dataStart, 4)),
        summary = "gamma=%.5f".format(gamma),
    )
}

private fun decodeChrm(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 44) { // 8 + 32 (cHRM body) + 4 (crc)
        return BoxNode(type = "cHRM", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("cHRM chunk too short to contain all chromaticity values"))
    }
    fun point(pos: Long) = reader.readUInt32(pos) / 100000.0
    val whiteX = point(dataStart)
    val whiteY = point(dataStart + 4)
    val redX = point(dataStart + 8)
    val redY = point(dataStart + 12)
    val greenX = point(dataStart + 16)
    val greenY = point(dataStart + 20)
    val blueX = point(dataStart + 24)
    val blueY = point(dataStart + 28)
    return BoxNode(
        type = "cHRM", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("white_point", "x=%.4f, y=%.4f".format(whiteX, whiteY), dataStart, 8),
            BoxField("red", "x=%.4f, y=%.4f".format(redX, redY), dataStart + 8, 8),
            BoxField("green", "x=%.4f, y=%.4f".format(greenX, greenY), dataStart + 16, 8),
            BoxField("blue", "x=%.4f, y=%.4f".format(blueX, blueY), dataStart + 24, 8),
        ),
        summary = "white=(%.4f, %.4f)".format(whiteX, whiteY),
    )
}

private val PNG_RENDERING_INTENT_NAMES = mapOf(
    0 to "Perceptual",
    1 to "Media-Relative Colorimetric",
    2 to "Saturation",
    3 to "ICC-Absolute Colorimetric",
)

private fun decodeSrgb(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 13) { // 8 + 1 (sRGB body) + 4 (crc)
        return BoxNode(type = "sRGB", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("sRGB chunk too short to contain the rendering intent"))
    }
    val intentCode = reader.readUInt8(dataStart)
    val intent = PNG_RENDERING_INTENT_NAMES[intentCode] ?: "Unknown ($intentCode)"
    return BoxNode(
        type = "sRGB", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("rendering_intent", intent, dataStart, 1)),
        summary = intent,
    )
}

private fun decodeTime(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 19) { // 8 + 7 (tIME body) + 4 (crc)
        return BoxNode(type = "tIME", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("tIME chunk too short to contain all fields"))
    }
    val year = reader.readUInt16(dataStart)
    val month = reader.readUInt8(dataStart + 2)
    val day = reader.readUInt8(dataStart + 3)
    val hour = reader.readUInt8(dataStart + 4)
    val minute = reader.readUInt8(dataStart + 5)
    val second = reader.readUInt8(dataStart + 6)
    val formatted = "%04d-%02d-%02d %02d:%02d:%02d UTC".format(year, month, day, hour, minute, second)
    return BoxNode(
        type = "tIME", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("last_modified", formatted, dataStart, 7)),
        summary = formatted,
    )
}
```

Then add 4 new branches to `decodePngChunk`'s `when` (currently ending with the
`eXIf` branch before `else`):

```kotlin
        "gAMA" -> decodeGama(reader, offset, dataStart, totalSize)
        "cHRM" -> decodeChrm(reader, offset, dataStart, totalSize)
        "sRGB" -> decodeSrgb(reader, offset, dataStart, totalSize)
        "tIME" -> decodeTime(reader, offset, dataStart, totalSize)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: PASS, all tests including the 4 new ones.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt
git commit -m "feat: parse PNG gAMA, cHRM, sRGB, and tIME chunks

Each was previously a bare, field-less node. gAMA/cHRM decode their scaled
uint32 fixed-point values; sRGB reuses the same 4 rendering-intent labels
ICC uses; tIME formats its 7-byte timestamp (which the PNG spec defines to
be UTC) as a readable date.

See docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md"
```

---

### Task 3: PNG `iCCP` (reusing the refactored ICC header parser)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`

**Interfaces:**
- Consumes: `decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField>` from Task 1 (`JpegWalker.kt`, same package `com.multiviewer.parser` — no import needed).
- Produces: `private fun decodeIccp(...)`, a shared `private fun inflateZlib(compressed: ByteArray, maxOutputBytes: Int): ByteArray?` (also used by Task 4), and a `private const val ICCP_MAX_DECOMPRESSED_BYTES` constant.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`:

```kotlin
    @Test
    fun `decodes iCCP by inflating the profile and reusing the ICC header parser`() {
        // Profile name "sRGB" + NUL + compression_method=0 + zlib-compressed 128-byte
        // ICC header (profile_size=142, cmm_type=APPL, version=2.1.0, profile_class=mntr,
        // data_colour_space=RGB, pcs=XYZ, date_time_created=2024-01-01, primary_platform=APPL,
        // rendering_intent=0 Perceptual, illuminant D50, profile_creator=APPL) --
        // the same field values Phase 1's JpegWalkerTest ICC test already covers.
        val nameAndMethod = byteArrayOf(0x73, 0x52, 0x47, 0x42, 0x00, 0x00) // "sRGB\0" + compression_method=0
        val compressedHeader = byteArrayOf(
            0x78, 0xda.toByte(), 0x63, 0x60, 0x60, 0xe8.toByte(), 0x73, 0x0c, 0x08, 0xf0.toByte(),
            0x61, 0x12, 0x60, 0x60, 0xc8.toByte(), 0xcd.toByte(), 0x2b, 0x29, 0x0a, 0x72, 0x77,
            0x52, 0x88.toByte(), 0x88.toByte(), 0x8c.toByte(), 0x52, 0x60, 0x7f, 0xc1.toByte(),
            0xc0.toByte(), 0x08, 0x84.toByte(), 0x60, 0x90.toByte(), 0x98.toByte(), 0x5c, 0x5c,
            0x00, 0x52, 0x03, 0x62, 0xc3.toByte(), 0x68, 0x54, 0xf0.toByte(), 0xed.toByte(), 0x1a,
            0x44, 0xed.toByte(), 0x65, 0x5d, 0xec.toByte(), 0xf2.toByte(), 0xb8.toByte(), 0x01,
            0x00, 0x18, 0xe8.toByte(), 0x0e, 0xa1.toByte(),
        )
        val bytes = pngChunk("iCCP", nameAndMethod + compressedHeader)
        readerOver(bytes, "png-walker-iccp").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[0]
            assertEquals("iCCP", iccp.type)
            assertEquals("sRGB", iccp.fields.first { it.name == "profile_name" }.value)
            assertEquals("142 bytes", iccp.fields.first { it.name == "profile_size" }.value)
            assertEquals("APPL", iccp.fields.first { it.name == "cmm_type" }.value)
            assertEquals("2.1.0", iccp.fields.first { it.name == "version" }.value)
            assertEquals("mntr", iccp.fields.first { it.name == "profile_class" }.value)
            assertEquals("RGB", iccp.fields.first { it.name == "data_colour_space" }.value)
            assertEquals("2024-01-01 00:00:00 UTC", iccp.fields.first { it.name == "date_time_created" }.value)
            assertEquals("Perceptual", iccp.fields.first { it.name == "rendering_intent" }.value)
            assertEquals("X=0.9642, Y=1.0000, Z=0.8249", iccp.fields.first { it.name == "pcs_illuminant" }.value)
        }
    }

    @Test
    fun `an iCCP chunk with an unknown compression method is not decompressed`() {
        val data = byteArrayOf(0x78, 0x00, 0x01) + byteArrayOf(0xAA.toByte(), 0xAA.toByte()) // "x\0" + compression_method=1 (unknown) + arbitrary bytes
        val bytes = pngChunk("iCCP", data)
        readerOver(bytes, "png-walker-iccp-unknown").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val iccp = nodes[0]
            assertEquals("x", iccp.fields.first { it.name == "profile_name" }.value)
            assertEquals(true, iccp.warnings.isNotEmpty())
            assertEquals(true, iccp.fields.none { it.name == "profile_size" })
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: FAIL — `iCCP` currently falls to the field-less generic fallback.

- [ ] **Step 3: Implement `iCCP`, `inflateZlib`, and the branch**

In `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`, add after Task 2's decoders:

```kotlin
private const val ICCP_MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024 // 64 MB safety cap against a decompression bomb

// Shared zlib inflate for iCCP/zTXt/iTXt. Returns null (never throws, never hangs)
// on malformed input or if the output would exceed [maxOutputBytes].
private fun inflateZlib(compressed: ByteArray, maxOutputBytes: Int): ByteArray? {
    val inflater = java.util.zip.Inflater()
    inflater.setInput(compressed)
    val out = java.io.ByteArrayOutputStream(minOf(compressed.size * 4, maxOutputBytes))
    val buf = ByteArray(8192)
    return try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > maxOutputBytes) return null
        }
        out.toByteArray()
    } catch (e: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

private fun decodeIccp(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val dataEnd = dataStart + length
    val nameBytes = reader.readBytes(dataStart, minOf(length, 80L).toInt())
    val nullIndex = nameBytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "iCCP", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing profile name terminator"))
    }
    val profileName = String(nameBytes, 0, nullIndex, Charsets.ISO_8859_1)
    val nameField = BoxField("profile_name", profileName, dataStart, nullIndex.toLong())
    val compressionMethodPos = dataStart + nullIndex + 1
    if (compressionMethodPos >= dataEnd) {
        return BoxNode(type = "iCCP", offset = offset, headerSize = 8, size = totalSize, fields = listOf(nameField), warnings = listOf("Missing compression method byte"))
    }
    val compressionMethod = reader.readUInt8(compressionMethodPos)
    if (compressionMethod != 0) {
        return BoxNode(
            type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
            fields = listOf(nameField),
            warnings = listOf("Unknown iCCP compression method $compressionMethod"),
            summary = profileName,
        )
    }
    val compressedStart = compressionMethodPos + 1
    val compressed = reader.readBytes(compressedStart, (dataEnd - compressedStart).toInt())
    val decompressed = inflateZlib(compressed, ICCP_MAX_DECOMPRESSED_BYTES)
    if (decompressed == null) {
        return BoxNode(
            type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
            fields = listOf(nameField),
            warnings = listOf("Failed to decompress ICC profile data"),
            summary = profileName,
        )
    }
    val fields = mutableListOf(nameField)
    if (decompressed.size >= 128) {
        fields.addAll(decodeIccProfileHeader(decompressed, dataStart))
    }
    return BoxNode(
        type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
        fields = fields,
        summary = "$profileName (${decompressed.size} bytes decompressed)",
    )
}
```

Then add one branch to `decodePngChunk`'s `when`:

```kotlin
        "iCCP" -> decodeIccp(reader, offset, dataStart, length, totalSize)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: PASS, all tests including the 2 new ones.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt
git commit -m "feat: parse PNG iCCP by reusing the ICC header parser

Inflates the chunk's zlib-compressed profile data (via a new shared
inflateZlib helper, capped at 64MB against decompression bombs) and, once
decompressed, hands the profile bytes to Task 1's ByteArray-based
decodeIccProfileHeader -- the exact same 16 fields JPEG's APP2 ICC profile
already gets. An unknown compression method or a failed decompression
degrades to a warning with whatever fields were already extracted, never a
crash.

See docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md"
```

---

### Task 4: PNG `zTXt` and `iTXt`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`

**Interfaces:**
- Consumes: `inflateZlib` and `ICCP_MAX_DECOMPRESSED_BYTES` from Task 3 (same file).
- Produces: `private fun decodeZtxt(...)`, `private fun decodeItxt(...)`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`:

```kotlin
    @Test
    fun `decodes zTXt by inflating the compressed text`() {
        // keyword "Comment" + NUL + compression_method=0 + zlib-compressed "Made with unwrapMedia"
        val keywordAndMethod = "Comment".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00, 0x00)
        val compressedText = byteArrayOf(
            0x78, 0xda.toByte(), 0xf3.toByte(), 0x4d, 0x4c, 0x49, 0x55, 0x28, 0xcf.toByte(),
            0x2c, 0xc9.toByte(), 0x50, 0x28, 0xcd.toByte(), 0x2b, 0x2f, 0x4a, 0x2c, 0xf0.toByte(),
            0x4d, 0x4d, 0xc9.toByte(), 0x4c, 0x04, 0x00, 0x55, 0x24, 0x07, 0xf1.toByte(),
        )
        val bytes = pngChunk("zTXt", keywordAndMethod + compressedText)
        readerOver(bytes, "png-walker-ztxt").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val ztxt = nodes[0]
            assertEquals("zTXt", ztxt.type)
            assertEquals("Comment", ztxt.fields.first { it.name == "keyword" }.value)
            assertEquals("Made with unwrapMedia", ztxt.fields.first { it.name == "text" }.value)
            assertEquals("Comment: Made with unwrapMedia", ztxt.summary)
        }
    }

    @Test
    fun `decodes uncompressed iTXt with non-ASCII text`() {
        // keyword "Comment" + NUL + compression_flag=0 + compression_method=0 +
        // language_tag "" + NUL + translated_keyword "" + NUL + UTF-8 text "밝게 편집됨"
        val head = "Comment".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x00) + // keyword NUL terminator
            byteArrayOf(0x00, 0x00) + // compression_flag=0, compression_method=0
            byteArrayOf(0x00) + // empty language_tag + NUL
            byteArrayOf(0x00) // empty translated_keyword + NUL
        val text = "밝게 편집됨".toByteArray(Charsets.UTF_8)
        val bytes = pngChunk("iTXt", head + text)
        readerOver(bytes, "png-walker-itxt").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val itxt = nodes[0]
            assertEquals("iTXt", itxt.type)
            assertEquals("Comment", itxt.fields.first { it.name == "keyword" }.value)
            assertEquals("", itxt.fields.first { it.name == "language_tag" }.value)
            assertEquals("", itxt.fields.first { it.name == "translated_keyword" }.value)
            assertEquals("밝게 편집됨", itxt.fields.first { it.name == "text" }.value)
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: FAIL — `zTXt`/`iTXt` currently fall to the field-less generic fallback.

- [ ] **Step 3: Implement `zTXt` and `iTXt`**

In `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`, add after `decodeIccp`:

```kotlin
private fun decodeZtxt(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val headBytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = headBytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword terminator"))
    }
    val keyword = String(headBytes, 0, nullIndex, Charsets.ISO_8859_1)
    val keywordField = BoxField("keyword", keyword, dataStart, nullIndex.toLong())
    val compressionMethodPos = nullIndex + 1
    if (compressionMethodPos >= headBytes.size) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Missing compression method byte"))
    }
    val compressionMethod = headBytes[compressionMethodPos].toInt() and 0xFF
    if (compressionMethod != 0) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Unknown zTXt compression method $compressionMethod"), summary = keyword)
    }
    val compressed = headBytes.copyOfRange(compressionMethodPos + 1, headBytes.size)
    val decompressed = inflateZlib(compressed, ICCP_MAX_DECOMPRESSED_BYTES)
        ?: return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Failed to decompress zTXt text"), summary = keyword)
    val text = String(decompressed, Charsets.ISO_8859_1)
    return BoxNode(
        type = "zTXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(keywordField, BoxField("text", text, dataStart + compressionMethodPos + 1, compressed.size.toLong())),
        summary = "$keyword: $text",
    )
}

private fun decodeItxt(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())

    val keywordEnd = bytes.indexOf(0)
    if (keywordEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword terminator"))
    }
    val keyword = String(bytes, 0, keywordEnd, Charsets.ISO_8859_1)

    val flagsStart = keywordEnd + 1
    if (flagsStart + 2 > bytes.size) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing compression flag/method"))
    }
    val compressionFlag = bytes[flagsStart].toInt() and 0xFF
    val compressionMethod = bytes[flagsStart + 1].toInt() and 0xFF

    val langStart = flagsStart + 2
    val langEnd = bytes.indexOf(0, langStart)
    if (langEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing language tag terminator"))
    }
    val languageTag = String(bytes, langStart, langEnd - langStart, Charsets.US_ASCII)

    val translatedStart = langEnd + 1
    val translatedEnd = bytes.indexOf(0, translatedStart)
    if (translatedEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing translated keyword terminator"))
    }
    val translatedKeyword = String(bytes, translatedStart, translatedEnd - translatedStart, Charsets.UTF_8)

    val baseFields = listOf(
        BoxField("keyword", keyword, dataStart, keywordEnd.toLong()),
        BoxField("language_tag", languageTag, dataStart + langStart, (langEnd - langStart).toLong()),
        BoxField("translated_keyword", translatedKeyword, dataStart + translatedStart, (translatedEnd - translatedStart).toLong()),
    )

    val textStart = translatedEnd + 1
    val rawTextBytes = bytes.copyOfRange(textStart, bytes.size)
    if (compressionFlag == 1 && compressionMethod != 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, fields = baseFields, warnings = listOf("Unknown iTXt compression method $compressionMethod"), summary = keyword)
    }
    val textBytes = if (compressionFlag == 1) {
        inflateZlib(rawTextBytes, ICCP_MAX_DECOMPRESSED_BYTES)
            ?: return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, fields = baseFields, warnings = listOf("Failed to decompress iTXt text"), summary = keyword)
    } else {
        rawTextBytes
    }
    val text = String(textBytes, Charsets.UTF_8)
    return BoxNode(
        type = "iTXt", offset = offset, headerSize = 8, size = totalSize,
        fields = baseFields + BoxField("text", text, dataStart + textStart, rawTextBytes.size.toLong()),
        summary = "$keyword: $text",
    )
}
```

Then add two branches to `decodePngChunk`'s `when`:

```kotlin
        "zTXt" -> decodeZtxt(reader, offset, dataStart, length, totalSize)
        "iTXt" -> decodeItxt(reader, offset, dataStart, length, totalSize)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: PASS, all tests including the 2 new ones.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt
git commit -m "feat: parse PNG zTXt and iTXt chunks

zTXt: keyword + inflated (zlib) Latin-1 text, same field shape as tEXt.
iTXt: keyword, language tag, translated keyword, and UTF-8 text (inflated
first when its compression flag is set). Non-ASCII text (the case that
motivated broadening SEFD's text detection in a much earlier phase) is
native here since iTXt's text is UTF-8 by spec, not something to detect.

See docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md"
```

---

### Task 5: PNG `PLTE`

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`

**Interfaces:**
- Produces: `private fun decodePlte(...)`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt`:

```kotlin
    @Test
    fun `decodes PLTE with a bounded color preview`() {
        val data = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, // color_1 = red
            0x00, 0xFF.toByte(), 0x00, // color_2 = green
            0x00, 0x00, 0xFF.toByte(), // color_3 = blue
        )
        val bytes = pngChunk("PLTE", data)
        readerOver(bytes, "png-walker-plte").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val plte = nodes[0]
            assertEquals("PLTE", plte.type)
            assertEquals("#FF0000", plte.fields.first { it.name == "color_1" }.value)
            assertEquals("#00FF00", plte.fields.first { it.name == "color_2" }.value)
            assertEquals("#0000FF", plte.fields.first { it.name == "color_3" }.value)
            assertEquals("3 colors", plte.summary)
        }
    }

    @Test
    fun `PLTE with a length not a multiple of 3 adds a warning`() {
        val bytes = pngChunk("PLTE", byteArrayOf(0xFF.toByte(), 0x00)) // 2 bytes, not a multiple of 3
        readerOver(bytes, "png-walker-plte-bad").use { reader ->
            val nodes = parsePngChunks(reader, 0, bytes.size.toLong())
            val plte = nodes[0]
            assertEquals(true, plte.warnings.isNotEmpty())
            assertEquals(true, plte.fields.isEmpty())
        }
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: FAIL — `PLTE` currently falls to the field-less generic fallback.

- [ ] **Step 3: Implement `PLTE`**

In `app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt`, add after `decodeItxt`:

```kotlin
private fun decodePlte(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    if (length % 3 != 0L) {
        return BoxNode(type = "PLTE", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("PLTE length $length is not a multiple of 3"))
    }
    val count = (length / 3).toInt()
    val previewCount = minOf(count, 8)
    val fields = mutableListOf<BoxField>()
    for (i in 0 until previewCount) {
        val entryStart = dataStart + i * 3L
        val rgb = reader.readBytes(entryStart, 3)
        val hex = "#" + rgb.joinToString("") { "%02X".format(it) }
        fields.add(BoxField("color_${i + 1}", hex, entryStart, 3))
    }
    return BoxNode(
        type = "PLTE", offset = offset, headerSize = 8, size = totalSize,
        fields = fields,
        summary = "$count colors",
    )
}
```

Then add one branch to `decodePngChunk`'s `when`:

```kotlin
        "PLTE" -> decodePlte(reader, offset, dataStart, length, totalSize)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.PngWalkerTest"`
Expected: PASS, all tests including the 2 new ones.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`. Total test count = baseline (951) + 4 (Task 2) + 2 (Task 3) + 2 (Task 4) + 2 (Task 5) = 961.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/PngWalker.kt app/src/test/kotlin/com/multiviewer/parser/PngWalkerTest.kt
git commit -m "feat: parse PNG PLTE with a bounded color preview

Shows the palette's entry count plus up to the first 8 colors as #RRGGBB
fields, rather than either nothing or one field per palette entry (which
could mean up to 256 fields for a typical indexed-color PNG).

See docs/superpowers/specs/2026-09-12-png-detail-parsing-design.md"
```

---

### Task 6: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual, tell the user)**: Ask the user to open a real PNG with an
  embedded ICC profile (or produce one, e.g. macOS's `sips -m <profile.icc> in.png
  --out out.png`, the same technique Phase 1 used for JPEG) and confirm the
  Structure tree's `iCCP` node shows the parsed header fields. If a PNG with
  `gAMA`/`cHRM`/`tIME`/`zTXt`/`iTXt`/`PLTE` is available (e.g. any indexed-color PNG
  for `PLTE`, or one saved by an image editor for the metadata chunks), check those
  too.
