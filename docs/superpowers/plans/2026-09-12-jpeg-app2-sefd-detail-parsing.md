# JPEG APP2 & SEFD Detail Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill in structure-tree detail for two JPEG-specific gaps identified in
`docs/superpowers/specs/2026-09-12-jpeg-app2-sefd-detail-parsing-design.md`: the APP2
ICC Profile header (currently just an "identifier" field) and SEFD field values
(currently a raw string-or-binary fallback with no semantic interpretation).

**Architecture:** Add a self-contained ICC header parser to `JpegWalker.kt`'s
existing `decodeApp2` function, and extend `SefdBoxDecoder.kt`'s `decodeField`
function with known-marker semantic handling (UTC timestamp, MCC label) plus a
broadened UTF-8 text detector and a hand-rolled JSON pretty-printer, all as pure
functions with no new dependencies.

**Tech Stack:** Kotlin, JUnit5 (`kotlin("test-junit5")`), `java.time` (for the UTC
timestamp formatting).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-12-jpeg-app2-sefd-detail-parsing-design.md` — every requirement below traces back to it.
- **Out of scope, do not implement:** ISO 21496-1 gain map binary metadata, MCC-to-country-name resolution, Samsung `SingleShotMeta`/`DualShotExtra` binary sub-fields. If a step below seems to invite any of these, it doesn't — stop and re-read the brief.
- **Do not touch Motion Photo detection/extraction**: `tryDecodeSefdTrailer`'s invocation condition, `MotionPhotoExtractor.kt`, or anything about *whether*/*when* the SEFD trailer is found. Only `SefdBoxDecoder.decodeField`'s value-rendering logic changes.
- Every new parse path degrades to today's existing behavior on failure — never throw, never show fabricated/guessed data.
- No new library dependency. The JSON pretty-printer is hand-rolled (no `org.json`, no `kotlinx.serialization`), matching this codebase's existing hand-rolled binary/text parsing style.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt` | `decodeApp2`'s ICC branch gains chunk-sequence fields and, for the first/only chunk, a full 128-byte ICC header parse. New private helpers: `readIccSignature`, `readS15Fixed16`, `decodeIccProfileHeader`. |
| `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt` | `decodeField`'s final fallback tier replaced with known-marker semantic handling (UTC, MCC) plus broadened text detection and JSON pretty-printing. New private helpers: `decodeFieldText`, `isJsonShaped`, `prettyPrintJson`, plus two marker constants. |
| `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` | New tests for the ICC header parse (first chunk, continuation chunk, truncated chunk). |
| `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt` | New tests for UTC, MCC, JSON pretty-printing with non-ASCII content, and a binary-fallback regression case. |

---

### Task 1: APP2 ICC Profile header parsing

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt`

**Interfaces:**
- Produces: `decodeApp2`'s existing return type/signature is unchanged (`BoxNode`) — only the ICC branch's *content* changes. New private helpers (`readIccSignature`, `readS15Fixed16`, `decodeIccProfileHeader`) are internal to `JpegWalker.kt`, not called from anywhere else.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt` (inside the
`JpegWalkerTest` class, after its last existing test):

```kotlin
    @Test
    fun `APP2 ICC profile first chunk parses the full 128-byte header`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe2.toByte(), 0x00, 0x90,
            // "ICC_PROFILE\0"
            0x49, 0x43, 0x43, 0x5f, 0x50, 0x52, 0x4f, 0x46, 0x49, 0x4c, 0x45, 0x00,
            // chunk_sequence_number=1, chunk_count=1
            0x01, 0x01,
            // --- 128-byte ICC header ---
            0x00, 0x00, 0x00, 0x8e,             // profile_size = 142
            0x41, 0x50, 0x50, 0x4c,             // cmm_type = "APPL"
            0x02, 0x10, 0x00, 0x00,             // version = 2.1.0
            0x6d, 0x6e, 0x74, 0x72,             // profile_class = "mntr"
            0x52, 0x47, 0x42, 0x20,             // data_colour_space = "RGB "
            0x58, 0x59, 0x5a, 0x20,             // pcs = "XYZ "
            0x07, 0xe8.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // date_time_created = 2024-01-01 00:00:00
            0x61, 0x63, 0x73, 0x70,             // "acsp" signature
            0x41, 0x50, 0x50, 0x4c,             // primary_platform = "APPL"
            0x00, 0x00, 0x00, 0x00,             // profile_flags = 0
            0x41, 0x50, 0x50, 0x4c,             // device_manufacturer = "APPL"
            0x00, 0x00, 0x00, 0x00,             // device_model = (unspecified)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // device_attributes = 0
            0x00, 0x00, 0x00, 0x00,             // rendering_intent = 0 (Perceptual)
            0x00, 0x00, 0xf6.toByte(), 0xd4.toByte(), // illuminant X = 0.9642
            0x00, 0x01, 0x00, 0x00,             // illuminant Y = 1.0000
            0x00, 0x00, 0xd3.toByte(), 0x32,    // illuminant Z = 0.8249
            0x41, 0x50, 0x50, 0x4c,             // profile_creator = "APPL"
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // profile_id = (not set)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // 28 reserved bytes
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val app2 = segments[1]
        assertEquals("APP2", app2.type)
        fun field(name: String) = app2.fields.first { it.name == name }.value
        assertEquals("1", field("chunk_sequence_number"))
        assertEquals("1", field("chunk_count"))
        assertEquals("142 bytes", field("profile_size"))
        assertEquals("APPL", field("cmm_type"))
        assertEquals("2.1.0", field("version"))
        assertEquals("mntr", field("profile_class"))
        assertEquals("RGB", field("data_colour_space"))
        assertEquals("XYZ", field("pcs"))
        assertEquals("2024-01-01 00:00:00 UTC", field("date_time_created"))
        assertEquals("APPL", field("primary_platform"))
        assertEquals("APPL", field("device_manufacturer"))
        assertEquals("(unspecified)", field("device_model"))
        assertEquals("Perceptual", field("rendering_intent"))
        assertEquals("X=0.9642, Y=1.0000, Z=0.8249", field("pcs_illuminant"))
        assertEquals("APPL", field("profile_creator"))
        assertEquals("(not set)", field("profile_id"))
        assertEquals("ICC Profile v2.1.0 (142 bytes)", app2.summary)
        reader.close()
    }

    @Test
    fun `APP2 ICC profile continuation chunk does not attempt a header parse`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe2.toByte(), 0x00, 0x1a,
            0x49, 0x43, 0x43, 0x5f, 0x50, 0x52, 0x4f, 0x46, 0x49, 0x4c, 0x45, 0x00,
            0x02, 0x02, // chunk_sequence_number=2, chunk_count=2
            0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(),
            0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(), 0xaa.toByte(),
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val app2 = segments[1]
        assertEquals("2", app2.fields.first { it.name == "chunk_sequence_number" }.value)
        assertEquals("2", app2.fields.first { it.name == "chunk_count" }.value)
        assertEquals(true, app2.fields.none { it.name == "version" })
        assertEquals("ICC Profile chunk 2 of 2 (continuation, 24 bytes)", app2.summary)
        reader.close()
    }

    @Test
    fun `APP2 ICC profile with truncated header data does not crash`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe2.toByte(), 0x00, 0x1a,
            0x49, 0x43, 0x43, 0x5f, 0x50, 0x52, 0x4f, 0x46, 0x49, 0x4c, 0x45, 0x00,
            0x01, 0x01, // chunk_sequence_number=1, chunk_count=1, but only 10 bytes follow (< 128)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val app2 = segments[1]
        assertEquals(true, app2.fields.none { it.name == "version" })
        assertEquals("1", app2.fields.first { it.name == "chunk_sequence_number" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: FAIL — the first test's field assertions fail (only the `identifier` field
exists today), or fail to compile if `field(...)` finds nothing (`NoSuchElementException`
at runtime, not a compile error, since `fields.first { }` throws — either way, a
clear pre-implementation failure).

- [ ] **Step 3: Implement the ICC header parser**

In `app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt`, add these three
private helpers immediately after the `ICC_PREFIX`/`MPF_PREFIX` constant
declarations (before `decodeApp2`, around line 226):

```kotlin
private val RENDERING_INTENT_NAMES = mapOf(
    0 to "Perceptual",
    1 to "Media-Relative Colorimetric",
    2 to "Saturation",
    3 to "ICC-Absolute Colorimetric",
)

// Reads a 4-byte ICC signature: printable ASCII (trimmed of trailing padding spaces)
// when every byte is in the printable range, "(unspecified)" for an all-zero
// signature (legitimately common -- several ICC header fields are optional), or a
// hex dump for anything else rather than risking mojibake.
private fun readIccSignature(reader: ByteReader, offset: Long): String {
    val bytes = reader.readBytes(offset, 4)
    if (bytes.all { it == 0.toByte() }) return "(unspecified)"
    val printable = bytes.all { b -> val v = b.toInt() and 0xFF; v in 0x20..0x7E }
    return if (printable) {
        String(bytes, Charsets.US_ASCII).trimEnd(' ')
    } else {
        "0x" + bytes.joinToString("") { "%02X".format(it) }
    }
}

// ICC's s15Fixed16Number: a signed 32-bit big-endian integer, divided by 65536 to
// get the real value (used for the PCS illuminant XYZ triplet).
private fun readS15Fixed16(reader: ByteReader, offset: Long): Double {
    val raw = reader.readUInt32(offset)
    val signed = if (raw > 0x7FFFFFFFL) raw - 0x100000000L else raw
    return signed / 65536.0
}

// Parses the 128-byte ICC.1 profile header (a stable format, unchanged since the
// 2001 spec) starting at [headerStart]. Caller guarantees at least 128 bytes are
// available from headerStart.
private fun decodeIccProfileHeader(reader: ByteReader, headerStart: Long): List<BoxField> {
    val profileSize = reader.readUInt32(headerStart)
    val cmmType = readIccSignature(reader, headerStart + 4)
    val versionByte0 = reader.readUInt8(headerStart + 8)
    val versionByte1 = reader.readUInt8(headerStart + 9)
    val version = "$versionByte0.${versionByte1 shr 4}.${versionByte1 and 0x0F}"
    val profileClass = readIccSignature(reader, headerStart + 12)
    val dataColourSpace = readIccSignature(reader, headerStart + 16)
    val pcs = readIccSignature(reader, headerStart + 20)
    val year = reader.readUInt16(headerStart + 24)
    val month = reader.readUInt16(headerStart + 26)
    val day = reader.readUInt16(headerStart + 28)
    val hour = reader.readUInt16(headerStart + 30)
    val minute = reader.readUInt16(headerStart + 32)
    val second = reader.readUInt16(headerStart + 34)
    val dateTimeCreated = "%04d-%02d-%02d %02d:%02d:%02d UTC".format(year, month, day, hour, minute, second)
    val primaryPlatform = readIccSignature(reader, headerStart + 40)
    val profileFlags = reader.readUInt32(headerStart + 44)
    val deviceManufacturer = readIccSignature(reader, headerStart + 48)
    val deviceModel = readIccSignature(reader, headerStart + 52)
    val deviceAttributes = reader.readUInt64(headerStart + 56)
    val renderingIntentCode = reader.readUInt32(headerStart + 64).toInt()
    val renderingIntent = RENDERING_INTENT_NAMES[renderingIntentCode] ?: "Unknown ($renderingIntentCode)"
    val illumX = readS15Fixed16(reader, headerStart + 68)
    val illumY = readS15Fixed16(reader, headerStart + 72)
    val illumZ = readS15Fixed16(reader, headerStart + 76)
    val profileCreator = readIccSignature(reader, headerStart + 80)
    val profileIdBytes = reader.readBytes(headerStart + 84, 16)
    val profileId = if (profileIdBytes.all { it == 0.toByte() }) {
        "(not set)"
    } else {
        profileIdBytes.joinToString("") { "%02x".format(it) }
    }

    return listOf(
        BoxField("profile_size", "$profileSize bytes", headerStart, 4),
        BoxField("cmm_type", cmmType, headerStart + 4, 4),
        BoxField("version", version, headerStart + 8, 4),
        BoxField("profile_class", profileClass, headerStart + 12, 4),
        BoxField("data_colour_space", dataColourSpace, headerStart + 16, 4),
        BoxField("pcs", pcs, headerStart + 20, 4),
        BoxField("date_time_created", dateTimeCreated, headerStart + 24, 12),
        BoxField("primary_platform", primaryPlatform, headerStart + 40, 4),
        BoxField("profile_flags", "0x${profileFlags.toString(16).padStart(8, '0')}", headerStart + 44, 4),
        BoxField("device_manufacturer", deviceManufacturer, headerStart + 48, 4),
        BoxField("device_model", deviceModel, headerStart + 52, 4),
        BoxField("device_attributes", "0x${deviceAttributes.toString(16).padStart(16, '0')}", headerStart + 56, 8),
        BoxField("rendering_intent", renderingIntent, headerStart + 64, 4),
        BoxField("pcs_illuminant", "X=%.4f, Y=%.4f, Z=%.4f".format(illumX, illumY, illumZ), headerStart + 68, 12),
        BoxField("profile_creator", profileCreator, headerStart + 80, 4),
        BoxField("profile_id", profileId, headerStart + 84, 16),
    )
}
```

Then replace the existing ICC branch inside `decodeApp2` (currently):

```kotlin
    if (payloadEnd - payloadStart >= ICC_PREFIX.size &&
        reader.readBytes(payloadStart, ICC_PREFIX.size).contentEquals(ICC_PREFIX)
    ) {
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = listOf(BoxField("identifier", "ICC_PROFILE", payloadStart, ICC_PREFIX.size.toLong())),
            summary = "ICC Profile (${declaredSize} bytes)",
        )
    }
```

with:

```kotlin
    if (payloadEnd - payloadStart >= ICC_PREFIX.size &&
        reader.readBytes(payloadStart, ICC_PREFIX.size).contentEquals(ICC_PREFIX)
    ) {
        val fields = mutableListOf(BoxField("identifier", "ICC_PROFILE", payloadStart, ICC_PREFIX.size.toLong()))
        var summary = "ICC Profile (${declaredSize} bytes)"
        val chunkInfoStart = payloadStart + ICC_PREFIX.size
        if (payloadEnd - chunkInfoStart >= 2) {
            val chunkSequenceNumber = reader.readUInt8(chunkInfoStart)
            val chunkCount = reader.readUInt8(chunkInfoStart + 1)
            fields.add(BoxField("chunk_sequence_number", chunkSequenceNumber.toString(), chunkInfoStart, 1))
            fields.add(BoxField("chunk_count", chunkCount.toString(), chunkInfoStart + 1, 1))
            val headerStart = chunkInfoStart + 2
            if (chunkSequenceNumber == 1 && payloadEnd - headerStart >= 128) {
                val headerFields = decodeIccProfileHeader(reader, headerStart)
                fields.addAll(headerFields)
                val version = headerFields.first { it.name == "version" }.value
                summary = "ICC Profile v$version (${declaredSize} bytes)"
            } else if (chunkSequenceNumber != 1) {
                summary = "ICC Profile chunk $chunkSequenceNumber of $chunkCount (continuation, ${declaredSize} bytes)"
            }
        }
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = fields,
            summary = summary,
        )
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.JpegWalkerTest"`
Expected: PASS, all tests including the 3 new ones.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/JpegWalker.kt app/src/test/kotlin/com/multiviewer/parser/JpegWalkerTest.kt
git commit -m "feat: parse the APP2 ICC profile header into detailed fields

Adds chunk_sequence_number/chunk_count fields unconditionally, and for the
first (or only) chunk, parses the full 128-byte ICC.1 profile header
(profile size/version/class, colour space, PCS, creation date, primary
platform, rendering intent, PCS illuminant, manufacturer/model/creator
signatures, profile ID) instead of showing only a bare 'ICC_PROFILE'
identifier. Continuation chunks (sequence != 1) and truncated data skip
header parsing without crashing.

See docs/superpowers/specs/2026-09-12-jpeg-app2-sefd-detail-parsing-design.md"
```

---

### Task 2: SEFD known-marker fields — UTC timestamp and MCC

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt`

**Interfaces:**
- Produces: two new private `const val` marker constants (`MARKER_UTC_TIMESTAMP = 0x0a01`, `MARKER_MCC = 0x0aa1`) used only within `SefdBoxDecoder.kt`.
- Consumes: `decodeField`'s existing `directoryMarker: Int` parameter (already present, currently used only for the marker-mismatch warning check) — no signature change.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt` (inside
the `SefdBoxDecoderTest` class, after its last existing test):

```kotlin
    @Test
    fun `a UTC TimeStamp field (marker 0x0a01) gets a human-readable date alongside the raw epoch value`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x01, 0x0a, 0x03, 0x00, 0x00, 0x00,
            0x55, 0x54, 0x43,
            0x30,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x0a, 0x0c, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("UTC", field.type)
        assertEquals("0", field.fields.first { it.name == "value" }.value)
        assertEquals("1970-01-01 00:00:00 UTC", field.fields.first { it.name == "timestamp_utc" }.value)
        reader.close()
    }

    @Test
    fun `a non-numeric UTC field falls back to raw display with no timestamp_utc field`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x01, 0x0a, 0x03, 0x00, 0x00, 0x00,
            0x55, 0x54, 0x43,
            0x3f, // "?" -- not a valid epoch integer
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x0a, 0x0c, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("?", field.fields.first { it.name == "value" }.value)
        assertEquals(true, field.fields.none { it.name == "timestamp_utc" })
        reader.close()
    }

    @Test
    fun `an MCC field (marker 0x0aa1) is clearly labeled without a guessed country name`() {
        val body = byteArrayOf(
            0x00, 0x00, 0xa1.toByte(), 0x0a, 0x03, 0x00, 0x00, 0x00,
            0x4d, 0x43, 0x43,
            0x34, 0x35, 0x30,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0xa1.toByte(), 0x0a, 0x0e, 0x00, 0x00, 0x00, 0x0e, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("MCC", field.type)
        assertEquals("450", field.fields.first { it.name == "value" }.value)
        assertEquals("Mobile Country Code (MCC)", field.fields.first { it.name == "meaning" }.value)
        reader.close()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefdBoxDecoderTest"`
Expected: FAIL — the `timestamp_utc`/`meaning` fields don't exist yet
(`NoSuchElementException` from `fields.first { }`).

- [ ] **Step 3: Implement the marker constants and semantic handling**

In `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`, add these two
constants near the top of the file (after the `package` line, before
`object SefdBoxDecoder`):

```kotlin
// Known Samsung SEFD trailer field markers (per ExifTool's Samsung.pm trailer
// table) whose values this decoder interprets semantically instead of showing raw.
private const val MARKER_UTC_TIMESTAMP = 0x0a01
private const val MARKER_MCC = 0x0aa1
```

Then, in `decodeField`, find the final fallback block (currently):

```kotlin
        val dataBytes = reader.readBytes(dataStart, dataLength)
        val isPrintable = dataBytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
        }
        return if (isPrintable) {
            val value = String(dataBytes, Charsets.UTF_8)
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField, BoxField("value", value, dataStart, dataLength.toLong())),
                warnings = warnings,
                summary = value,
            )
        } else {
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes (binary)",
            )
        }
    }
```

Replace it with (this task only adds the marker-based `fields.add(...)` calls after
the existing printable check — Task 3 replaces the `isPrintable` computation itself,
so for now leave it as-is and only insert the new logic between computing `value`
and constructing the returned `BoxNode`):

```kotlin
        val dataBytes = reader.readBytes(dataStart, dataLength)
        val isPrintable = dataBytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
        }
        return if (isPrintable) {
            val value = String(dataBytes, Charsets.UTF_8)
            val fields = mutableListOf(markerField, BoxField("value", value, dataStart, dataLength.toLong()))
            if (directoryMarker == MARKER_UTC_TIMESTAMP) {
                value.trim().toLongOrNull()?.let { epochSeconds ->
                    val formatted = java.time.Instant.ofEpochSecond(epochSeconds)
                        .atZone(java.time.ZoneOffset.UTC)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
                    fields.add(BoxField("timestamp_utc", formatted, dataStart, dataLength.toLong()))
                }
            }
            if (directoryMarker == MARKER_MCC) {
                fields.add(BoxField("meaning", "Mobile Country Code (MCC)", dataStart, 0))
            }
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = fields,
                warnings = warnings,
                summary = value,
            )
        } else {
            BoxNode(
                type = name, offset = blockStart, headerSize = fieldHeaderSize, size = blockSize,
                fields = listOf(markerField), warnings = warnings,
                summary = "$dataLength bytes (binary)",
            )
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefdBoxDecoderTest"`
Expected: PASS, all tests including the 3 new ones.

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt
git commit -m "feat: interpret SEFD UTC timestamp and MCC field values

Marker 0x0a01 (TimeStamp/UTC) gets a human-readable date alongside its raw
epoch value; marker 0x0aa1 (MCC) gets a clear 'Mobile Country Code' label.
No country-name resolution for MCC -- deliberately out of scope, see the
design spec's Non-goals section (a cross-check against a fetched country
table caught a real error, so no lookup table is embedded).

See docs/superpowers/specs/2026-09-12-jpeg-app2-sefd-detail-parsing-design.md"
```

---

### Task 3: SEFD broadened text detection and JSON pretty-printing

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt`

**Interfaces:**
- Consumes: nothing from Task 2 beyond the same `decodeField` function (this task
  replaces the `isPrintable` computation Task 2 left untouched).
- Produces: three new private functions (`decodeFieldText`, `isJsonShaped`,
  `prettyPrintJson`), internal to `SefdBoxDecoder.kt`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt` (after
Task 2's tests):

```kotlin
    @Test
    fun `a JSON-shaped field with non-ASCII text is decoded and pretty-printed instead of shown as binary`() {
        // data = {"tone":"밝게"} as UTF-8 bytes -- {,",t,o,n,e,",:,",<밝=EB B0 9D>,<게=EA B2 8C>,",}
        val body = byteArrayOf(
            0x00, 0x00, 0xa1.toByte(), 0x0b, 0x0a, 0x00, 0x00, 0x00,
            0x52, 0x65, 0x45, 0x64, 0x69, 0x74, 0x44, 0x61, 0x74, 0x61, // "ReEditData"
            0x7b, 0x22, 0x74, 0x6f, 0x6e, 0x65, 0x22, 0x3a, 0x22,
            0xeb.toByte(), 0xb0.toByte(), 0x9d.toByte(), 0xea.toByte(), 0xb2.toByte(), 0x8c.toByte(),
            0x22, 0x7d,
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0xa1.toByte(), 0x0b, 0x23, 0x00, 0x00, 0x00, 0x23, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("ReEditData", field.type)
        assertEquals("{\n  \"tone\": \"밝게\"\n}", field.fields.first { it.name == "value" }.value)
        assertEquals("JSON (17 bytes)", field.summary)
        reader.close()
    }

    @Test
    fun `genuinely binary data is still shown as a byte count, not garbled text`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x34, 0x12, 0x07, 0x00, 0x00, 0x00,
            0x46, 0x69, 0x65, 0x6c, 0x64, 0x5f, 0x41,
            0xff.toByte(), 0xfe.toByte(), // invalid UTF-8 (lone continuation-style bytes), not printable ASCII either
            0x53, 0x45, 0x46, 0x48,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x34, 0x12, 0x11, 0x00, 0x00, 0x00, 0x11, 0x00, 0x00, 0x00,
            0x18, 0x00, 0x00, 0x00,
            0x53, 0x45, 0x46, 0x54,
        )
        val reader = byteReaderOf(body)
        val node = SefdBoxDecoder.decode(reader, "sefd", 0, 0, body.size.toLong(), emptyList())

        val field = node.children[0]
        assertEquals("2 bytes (binary)", field.summary)
        assertEquals(true, field.fields.none { it.name == "value" })
        reader.close()
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.SefdBoxDecoderTest"`
Expected: FAIL — the first new test fails because the JSON bytes contain non-ASCII
and are currently classified as binary (`"17 bytes (binary)"`, not the expected
pretty-printed value); the second new test should already PASS today (it's a
regression guard) — if it doesn't, stop and investigate before continuing, since
that would mean the *existing* binary-fallback behavior is already broken.

- [ ] **Step 3: Implement broadened text detection and JSON pretty-printing**

In `app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`, add these three
private functions after `decodeField` (before the file-level `readUInt16LE`/
`readUInt32LE` helpers at the bottom):

```kotlin
// Broader than the old strict-ASCII-only check: accepts any bytes that decode as
// valid UTF-8 (rejecting malformed/unmappable sequences, never silently replacing
// them) with no disallowed control characters. ASCII text already accepted by the
// old check still decodes identically here (ASCII is a subset of UTF-8) -- this
// only *additionally* rescues genuinely valid multi-byte UTF-8 (e.g. Korean-language
// JSON values) that a byte-range check misclassifies as binary. Returns null for
// anything that isn't valid, safely-printable text, so genuinely binary data still
// falls through to the existing "N bytes (binary)" display.
private fun decodeFieldText(bytes: ByteArray): String? {
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: Exception) {
        return null
    }
    val hasDisallowedControlChars = text.any { c ->
        (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') || c.code == 0x7F
    }
    return if (hasDisallowedControlChars) null else text
}

private fun isJsonShaped(text: String): Boolean {
    val trimmed = text.trim().trimEnd(Char(0)).trim()
    return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
}

// Re-indents JSON-shaped text for readability without a JSON parsing library:
// walks the text tracking {}/[] nesting depth, passing string-literal contents
// through untouched (respecting \" escapes) so braces/commas inside string values
// never affect indentation.
private fun prettyPrintJson(text: String): String {
    val trimmed = text.trim().trimEnd(Char(0)).trim()
    val sb = StringBuilder()
    var depth = 0
    var inString = false
    var i = 0
    while (i < trimmed.length) {
        val c = trimmed[i]
        if (inString) {
            sb.append(c)
            if (c == '\\' && i + 1 < trimmed.length) {
                i++
                sb.append(trimmed[i])
            } else if (c == '"') {
                inString = false
            }
        } else {
            when (c) {
                '"' -> { inString = true; sb.append(c) }
                '{', '[' -> { sb.append(c); depth++; sb.append('\n'); sb.append("  ".repeat(depth)) }
                '}', ']' -> { depth--; sb.append('\n'); sb.append("  ".repeat(depth)); sb.append(c) }
                ',' -> { sb.append(c); sb.append('\n'); sb.append("  ".repeat(depth)) }
                ':' -> { sb.append(c); sb.append(' ') }
                ' ', '\t', '\n', '\r' -> {} // drop the original formatting; we control it
                else -> sb.append(c)
            }
        }
        i++
    }
    return sb.toString()
}
```

Then, in `decodeField`, replace the `isPrintable`/`value` computation from Task 2
(currently):

```kotlin
        val dataBytes = reader.readBytes(dataStart, dataLength)
        val isPrintable = dataBytes.all { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == 0x09 || v == 0x0A || v == 0x0D
        }
        return if (isPrintable) {
            val value = String(dataBytes, Charsets.UTF_8)
            val fields = mutableListOf(markerField, BoxField("value", value, dataStart, dataLength.toLong()))
```

with:

```kotlin
        val dataBytes = reader.readBytes(dataStart, dataLength)
        val decodedText = decodeFieldText(dataBytes)
        return if (decodedText != null) {
            val displayValue = if (isJsonShaped(decodedText)) prettyPrintJson(decodedText) else decodedText
            val fields = mutableListOf(markerField, BoxField("value", displayValue, dataStart, dataLength.toLong()))
```

and change that branch's closing `summary = value,` to:

```kotlin
                summary = if (isJsonShaped(decodedText)) "JSON ($dataLength bytes)" else decodedText,
```

(the rest of that branch — the `MARKER_UTC_TIMESTAMP`/`MARKER_MCC` checks from Task
2 — stays exactly as it is, just referencing `decodedText` wherever it previously
referenced `value`; the `else` branch producing `"$dataLength bytes (binary)"` for a
`null` `decodedText` is unchanged).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.SefdBoxDecoderTest"`
Expected: PASS, all tests (Task 2's + Task 3's).

- [ ] **Step 5: Run the full test suite to confirm no regressions**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures, total count = baseline + 8 new tests
across Tasks 1-3 (3 + 3 + 2).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/SefdBoxDecoderTest.kt
git commit -m "feat: broaden SEFD text detection to valid UTF-8 and pretty-print JSON fields

Fields whose bytes are valid UTF-8 (not just strict ASCII) now display as
text instead of being misclassified as binary -- fixes JSON-shaped fields
(ReEditData, RemasterInfo, SamsungCaptureInfo, PEgInfo) that contain
non-ASCII text (e.g. Korean). JSON-shaped text is additionally re-indented
for readability via a small hand-rolled formatter (no new dependency).
Genuinely binary data is unaffected -- strict UTF-8 decoding rejects it
the same way the old ASCII check did.

See docs/superpowers/specs/2026-09-12-jpeg-app2-sefd-detail-parsing-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual, tell the user)**: Ask the user to open a real JPEG with an
  embedded ICC profile (most camera photos and any image exported from an editor
  with colour management have one) and confirm the Structure tree's APP2 node now
  shows the parsed header fields in Detailed Properties. If a real Samsung
  motion-photo or edited JPEG with a SEFD trailer is available, select a UTC/MCC/
  ReEditData-type field there too and confirm the new values render sensibly.
