# Box Detail Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Always show common box metadata (offset/size/header/payload/children/warnings) in the detail panel, and add box-specific field parsing for `avc1`/`hvc1`/`mp4a` sample entries, `avcC`/`hvcC` codec configs, `elst`, `dref`'s `url `/`urn ` entries, `colr`, `pasp`, and HEIC's `iinf`/`infe`.

**Architecture:** One new `BoxDecoder` object per box type in `app/src/main/kotlin/com/multiviewer/parser/`, each registered in `Decoders.kt`, following the exact pattern of the existing decoders (`FtypBoxDecoder`, `MvhdBoxDecoder`, etc.): read fixed-position fields via `ByteReader`, bounds-check before reading, bail out with a `BoxNode(...warnings = w)` (no fields) on undersized input, return a `BoxNode` with `fields`/`children`/`summary` set. `FieldPanel.kt` gets a metadata block that reads directly off `BoxNode`'s existing properties, independent of all the parser-side tasks.

**Tech Stack:** Kotlin, JVM. Parser tests use `kotlin.test` + the existing `byteReaderOf(bytes: ByteArray): ByteReader` helper in `app/src/test/kotlin/com/multiviewer/parser/TestSupport.kt`.

## Global Constraints

- Every new decoder's `decode(...)` signature must exactly match `BoxDecoder`'s interface (`app/src/main/kotlin/com/multiviewer/parser/BoxRegistry.kt`): `decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode`.
- `BoxField(name: String, value: String, offset: Long, length: Long)` and `TableData(columns: List<String>, fieldWidths: List<Int>, entriesStart: Long, entryCount: Long)` (`BoxNode.kt`) are reused as-is — no changes to either data class.
- `payloadStart = offset + headerSize`, `payloadEnd = offset + size` — the exact convention every existing decoder uses; `parseBoxes(reader, rangeStart, rangeEnd): List<BoxNode>` (`BoxWalker.kt`) is the shared child-walking function, same package, no import needed.
- Undersized-input bail-out convention (copy exactly): add a warning string to a mutable copy of `warnings`, then `return BoxNode(type, offset, headerSize, size, warnings = w)` — no fields, no children, no summary.
- Truncation-warning convention (copy exactly, see `FixedWidthTableDecoder`/`StszBoxDecoder`): `"Declared $declaredCount ... but only found/space for $actualCount"`.
- `avcC`/`hvcC` decode only their own structural fields (profile/level/length-size/counts) — they must NOT parse SPS/PPS/VPS byte contents (no RBSP/bitstream decoding). This is a hard scope boundary from the design spec, not an implementation shortcut.
- `iloc` is out of scope for this plan entirely — do not add any decoder for it.
- No new UI-layer tests — this project's UI layer has no test suite by design (parser is unit-tested; UI is verified by manual/process-level checks).
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Always-shown box metadata in the detail panel

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt`

**Interfaces:**
- Consumes: `BoxNode` (`type`, `offset`, `headerSize`, `size`, `children`, `fields`, `warnings`) — all already exist, no parser changes.
- Produces: nothing new for other tasks — purely a UI change, independent of every other task in this plan.

- [ ] **Step 1: Replace the full content of `FieldPanel.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

@Composable
fun FieldPanel(node: BoxNode?) {
    if (node == null) return
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        item {
            Column {
                MetadataRow("Type", node.type)
                MetadataRow("Offset", "${node.offset} (0x${node.offset.toString(16).uppercase()})")
                MetadataRow("Size", "${node.size}")
                MetadataRow("Header size", "${node.headerSize}")
                MetadataRow("Payload size", "${node.size - node.headerSize}")
                if (node.children.isNotEmpty()) {
                    MetadataRow("Children", "${node.children.size}")
                }
                if (node.warnings.isNotEmpty()) {
                    Text("Warnings:", modifier = Modifier.padding(top = 4.dp))
                    node.warnings.forEach { warning ->
                        Text("- $warning", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        items(node.fields) { field ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("${field.name}: ", modifier = Modifier.padding(end = 4.dp))
                Text(field.value)
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", modifier = Modifier.padding(end = 4.dp))
        Text(value)
    }
}
```

Note the early-return changed from `if (node == null || node.fields.isEmpty()) return` to just `if (node == null) return` — a box with no decoder-specific fields must still show the metadata block.

- [ ] **Step 2: Verify it compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures (this change touches no parser code and no test files).

- [ ] **Step 4: Manual verification**

```bash
./gradlew run
```
Open a real file and confirm: selecting any box (with or without existing decoder fields) shows Type/Offset/Size/Header size/Payload size; selecting a container shows a Children count; if any box has a warning (e.g. an intentionally truncated test file), its text is visible under "Warnings:".

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FieldPanel.kt
git commit -m "feat(parser): always show box metadata (offset/size/warnings) in detail panel"
```

---

### Task 2: VisualSampleEntry decoder (`avc1`/`hvc1`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/VisualSampleEntryDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/VisualSampleEntryDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `parseBoxes` (all existing, same package).
- Produces: `object VisualSampleEntryDecoder : BoxDecoder` — registered for both `"avc1"` and `"hvc1"` in Task's Step 4. No other task depends on this one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualSampleEntryDecoderTest {
    @Test
    fun `decodes data_reference_index, width, height and recurses into child boxes`() {
        val body = ByteArray(78 + 8)
        body[7] = 0x01 // data_reference_index = 1
        body[24] = 0x02; body[25] = 0x80 // width = 640
        body[26] = 0x01; body[27] = 0xE0.toByte() // height = 480
        // child box at offset 78: size=8, type="free"
        body[78] = 0x00; body[79] = 0x00; body[80] = 0x00; body[81] = 0x08
        body[82] = 'f'.code.toByte(); body[83] = 'r'.code.toByte()
        body[84] = 'e'.code.toByte(); body[85] = 'e'.code.toByte()

        val reader = byteReaderOf(body)
        val node = VisualSampleEntryDecoder.decode(reader, "avc1", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("640", node.fields[1].value)
        assertEquals("480", node.fields[2].value)
        assertEquals("640x480", node.summary)
        assertEquals(listOf("free"), node.children.map { it.type })
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = VisualSampleEntryDecoder.decode(reader, "avc1", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests VisualSampleEntryDecoderTest
```
Expected: FAIL — `VisualSampleEntryDecoder` is unresolved.

- [ ] **Step 3: Create `VisualSampleEntryDecoder.kt`**

```kotlin
package com.multiviewer.parser

object VisualSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 78

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
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for VisualSampleEntry fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val dataReferenceIndexOffset = payloadStart + 6
        val dataReferenceIndex = reader.readUInt16(dataReferenceIndexOffset)
        val widthOffset = payloadStart + 24
        val width = reader.readUInt16(widthOffset)
        val heightOffset = payloadStart + 26
        val height = reader.readUInt16(heightOffset)
        val fields = listOf(
            BoxField("data_reference_index", dataReferenceIndex.toString(), dataReferenceIndexOffset, 2),
            BoxField("width", width.toString(), widthOffset, 2),
            BoxField("height", height.toString(), heightOffset, 2),
        )
        val children = parseBoxes(reader, payloadStart + FIXED_HEADER_SIZE, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = w,
            summary = "${width}x${height}",
        )
    }
}
```

- [ ] **Step 4: Register for both `avc1` and `hvc1` in `Decoders.kt`**

In `registerAllDecoders()`, add these two lines directly after the existing `BoxRegistry.register("ispe", IspeBoxDecoder)` line:

```kotlin
    BoxRegistry.register("avc1", VisualSampleEntryDecoder)
    BoxRegistry.register("hvc1", VisualSampleEntryDecoder)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests VisualSampleEntryDecoderTest
```
Expected: PASS (2/2).

- [ ] **Step 6: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/VisualSampleEntryDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/VisualSampleEntryDecoderTest.kt
git commit -m "feat(parser): decode avc1/hvc1 VisualSampleEntry (data_reference_index, width, height)"
```

---

### Task 3: AudioSampleEntry decoder (`mp4a`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AudioSampleEntryDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AudioSampleEntryDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `parseBoxes` (existing, same package).
- Produces: `object AudioSampleEntryDecoder : BoxDecoder` — registered for `"mp4a"`. Independent of Task 2 (different file, different registration line); no other task depends on this one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioSampleEntryDecoderTest {
    @Test
    fun `decodes data_reference_index, channelcount, samplesize, samplerate and recurses into children`() {
        val body = ByteArray(28 + 8)
        body[7] = 0x01 // data_reference_index = 1
        body[16] = 0x00; body[17] = 0x02 // channelcount = 2
        body[18] = 0x00; body[19] = 0x10 // samplesize = 16
        body[24] = 0xAC.toByte(); body[25] = 0x44; body[26] = 0x00; body[27] = 0x00 // samplerate = 44100.0 (0xAC440000 / 65536)
        // child box at offset 28: size=8, type="esds"
        body[28] = 0x00; body[29] = 0x00; body[30] = 0x00; body[31] = 0x08
        body[32] = 'e'.code.toByte(); body[33] = 's'.code.toByte()
        body[34] = 'd'.code.toByte(); body[35] = 's'.code.toByte()

        val reader = byteReaderOf(body)
        val node = AudioSampleEntryDecoder.decode(reader, "mp4a", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("2", node.fields[1].value)
        assertEquals("16", node.fields[2].value)
        assertEquals("44100.0", node.fields[3].value)
        assertEquals("2ch, 44100Hz", node.summary)
        assertEquals(listOf("esds"), node.children.map { it.type })
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = AudioSampleEntryDecoder.decode(reader, "mp4a", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests AudioSampleEntryDecoderTest
```
Expected: FAIL — `AudioSampleEntryDecoder` is unresolved.

- [ ] **Step 3: Create `AudioSampleEntryDecoder.kt`**

```kotlin
package com.multiviewer.parser

object AudioSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 28

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
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for AudioSampleEntry fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val dataReferenceIndexOffset = payloadStart + 6
        val dataReferenceIndex = reader.readUInt16(dataReferenceIndexOffset)
        val channelCountOffset = payloadStart + 16
        val channelCount = reader.readUInt16(channelCountOffset)
        val sampleSizeOffset = payloadStart + 18
        val sampleSize = reader.readUInt16(sampleSizeOffset)
        val sampleRateOffset = payloadStart + 24
        val sampleRateRaw = reader.readUInt32(sampleRateOffset)
        val sampleRate = sampleRateRaw / 65536.0
        val fields = listOf(
            BoxField("data_reference_index", dataReferenceIndex.toString(), dataReferenceIndexOffset, 2),
            BoxField("channelcount", channelCount.toString(), channelCountOffset, 2),
            BoxField("samplesize", sampleSize.toString(), sampleSizeOffset, 2),
            BoxField("samplerate", sampleRate.toString(), sampleRateOffset, 4),
        )
        val children = parseBoxes(reader, payloadStart + FIXED_HEADER_SIZE, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = w,
            summary = "${channelCount}ch, ${"%.0f".format(sampleRate)}Hz",
        )
    }
}
```

- [ ] **Step 4: Register for `mp4a` in `Decoders.kt`**

Add directly after the two lines added in Task 2:

```kotlin
    BoxRegistry.register("mp4a", AudioSampleEntryDecoder)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests AudioSampleEntryDecoderTest
```
Expected: PASS (2/2).

- [ ] **Step 6: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AudioSampleEntryDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/AudioSampleEntryDecoderTest.kt
git commit -m "feat(parser): decode mp4a AudioSampleEntry (channelcount, samplesize, samplerate)"
```

---

### Task 4: `avcC` (AVCDecoderConfigurationRecord) decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AvcCBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AvcCBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` (existing, same package). `avcC` is not a FullBox — no version/flags header, its own fixed layout starts immediately at `payloadStart`.
- Produces: `object AvcCBoxDecoder : BoxDecoder` — registered for `"avcC"`. Does not parse SPS/PPS byte contents (counts and bounds-checks only) per the plan's Global Constraints. Independent of Tasks 2/3/5 (different file); no other task depends on this one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AvcCBoxDecoderTest {
    @Test
    fun `decodes profile, level, length_size and counts one SPS and one PPS`() {
        val body = byteArrayOf(
            0x01,                   // configuration_version
            0x64,                   // avc_profile_indication = 100
            0x00,                   // profile_compatibility
            0x1F,                   // avc_level_indication = 31
            0xFF.toByte(),          // lengthSizeMinusOne bits -> length_size = 4
            0xE1.toByte(),          // numSps bits -> declared 1 SPS
            0x00, 0x04,             // sps_length = 4
            0x67, 0x64, 0x00, 0x1F, // sps bytes (not decoded)
            0x01,                   // num_pps = 1
            0x00, 0x02,             // pps_length = 2
            0x68, 0xCE.toByte(),    // pps bytes (not decoded)
        )
        val reader = byteReaderOf(body)
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value) // configuration_version
        assertEquals("100", node.fields[1].value) // avc_profile_indication
        assertEquals("0", node.fields[2].value) // profile_compatibility
        assertEquals("31", node.fields[3].value) // avc_level_indication
        assertEquals("4", node.fields[4].value) // length_size
        assertEquals("1", node.fields[5].value) // num_sps
        assertEquals("1", node.fields[6].value) // num_pps
        assertEquals("profile=100, level=31, 1 SPS, 1 PPS", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `declared SPS count larger than available data truncates with a warning`() {
        val body = byteArrayOf(
            0x01, 0x64, 0x00, 0x1F,
            0xFF.toByte(),
            0xE2.toByte(),          // numSps bits -> declared 2 SPS (only 1 fits)
            0x00, 0x04,
            0x67, 0x64, 0x00, 0x1F,
            0x01,
            0x00, 0x02,
            0x68, 0xCE.toByte(),
        )
        val reader = byteReaderOf(body)
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals("1", node.fields[5].value) // num_sps found despite declaring 2
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(3))
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, 3, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests AvcCBoxDecoderTest
```
Expected: FAIL — `AvcCBoxDecoder` is unresolved.

- [ ] **Step 3: Create `AvcCBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object AvcCBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 6) {
            w.add("Box too short for avcC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val configVersion = reader.readUInt8(payloadStart)
        val profileIndication = reader.readUInt8(payloadStart + 1)
        val profileCompatibility = reader.readUInt8(payloadStart + 2)
        val levelIndication = reader.readUInt8(payloadStart + 3)
        val lengthSize = (reader.readUInt8(payloadStart + 4) and 0x03) + 1
        val declaredSps = reader.readUInt8(payloadStart + 5) and 0x1F

        var pos = payloadStart + 6
        var numSps = 0
        while (numSps < declaredSps && pos + 2 <= payloadEnd) {
            val spsLength = reader.readUInt16(pos)
            if (pos + 2 + spsLength > payloadEnd) break
            pos += 2 + spsLength
            numSps++
        }
        if (numSps < declaredSps) {
            w.add("Declared $declaredSps SPS entries but only found $numSps")
        }

        var numPps = 0
        var declaredPps = 0
        val ppsCountOffset = pos
        if (pos < payloadEnd) {
            declaredPps = reader.readUInt8(pos)
            pos += 1
            while (numPps < declaredPps && pos + 2 <= payloadEnd) {
                val ppsLength = reader.readUInt16(pos)
                if (pos + 2 + ppsLength > payloadEnd) break
                pos += 2 + ppsLength
                numPps++
            }
            if (numPps < declaredPps) {
                w.add("Declared $declaredPps PPS entries but only found $numPps")
            }
        }

        val fields = listOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("avc_profile_indication", profileIndication.toString(), payloadStart + 1, 1),
            BoxField("profile_compatibility", profileCompatibility.toString(), payloadStart + 2, 1),
            BoxField("avc_level_indication", levelIndication.toString(), payloadStart + 3, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 4, 1),
            BoxField("num_sps", numSps.toString(), payloadStart + 5, 1),
            BoxField("num_pps", numPps.toString(), ppsCountOffset, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$profileIndication, level=$levelIndication, $numSps SPS, $numPps PPS",
        )
    }
}
```

- [ ] **Step 4: Register for `avcC` in `Decoders.kt`**

Add directly after the `mp4a` line added in Task 3:

```kotlin
    BoxRegistry.register("avcC", AvcCBoxDecoder)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests AvcCBoxDecoderTest
```
Expected: PASS (3/3).

- [ ] **Step 6: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AvcCBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/AvcCBoxDecoderTest.kt
git commit -m "feat(parser): decode avcC structural fields (profile, level, SPS/PPS counts)"
```

---

### Task 5: `hvcC` (HEVCDecoderConfigurationRecord) decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/HvcCBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/HvcCBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` (existing, same package). `hvcC` is not a FullBox — its own fixed layout starts immediately at `payloadStart`.
- Produces: `object HvcCBoxDecoder : BoxDecoder` — registered for `"hvcC"`. Does not parse VPS/SPS/PPS NAL byte contents (counts and bounds-checks only, bucketed by NAL unit type) per the plan's Global Constraints. Independent of Tasks 2/3/4 (different file); no other task depends on this one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class HvcCBoxDecoderTest {
    private val validBody = byteArrayOf(
        0x01,                            // configuration_version
        0x01,                            // profile_space=0, tier=0, profile_idc=1
        0x60, 0x00, 0x00, 0x00,          // general_profile_compatibility_flags
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // general_constraint_indicator_flags
        0x5D,                            // general_level_idc = 93
        0xF0.toByte(), 0x00,             // reserved + min_spatial_segmentation_idc
        0xFC.toByte(),                   // parallelismType
        0xFC.toByte(),                   // chroma_format_idc
        0xF8.toByte(),                   // bit_depth_luma_minus8
        0xF8.toByte(),                   // bit_depth_chroma_minus8
        0x00, 0x00,                      // avgFrameRate
        0x0F,                            // lengthSizeMinusOne bits -> length_size = 4
        0x02,                            // num_arrays = 2
        // array 1: SPS (type 33), 1 NAL of length 3
        0xA1.toByte(), 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x02,
        // array 2: PPS (type 34), 1 NAL of length 2
        0xA2.toByte(), 0x00, 0x01, 0x00, 0x02, 0x44, 0x01,
    )

    @Test
    fun `decodes profile, level, length_size and counts one SPS and one PPS across arrays`() {
        val reader = byteReaderOf(validBody)
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, validBody.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value) // configuration_version
        assertEquals("1", node.fields[1].value) // general_profile_idc
        assertEquals("93", node.fields[2].value) // general_level_idc
        assertEquals("4", node.fields[3].value) // length_size
        assertEquals("2", node.fields[4].value) // num_arrays
        assertEquals("0", node.fields[5].value) // num_vps
        assertEquals("1", node.fields[6].value) // num_sps
        assertEquals("1", node.fields[7].value) // num_pps
        assertEquals("profile=1, level=93, 1 SPS, 1 PPS", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `declared array count larger than available data truncates with a warning`() {
        val body = validBody.copyOf()
        body[22] = 0x03 // declare 3 arrays but only 2 are present
        val reader = byteReaderOf(body)
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals("1", node.fields[6].value) // num_sps still counted from the 2 arrays found
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests HvcCBoxDecoderTest
```
Expected: FAIL — `HvcCBoxDecoder` is unresolved.

- [ ] **Step 3: Create `HvcCBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object HvcCBoxDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 23
    private const val NAL_TYPE_VPS = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PPS = 34

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
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for hvcC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val configVersion = reader.readUInt8(payloadStart)
        val generalProfileIdc = reader.readUInt8(payloadStart + 1) and 0x1F
        val generalLevelIdc = reader.readUInt8(payloadStart + 12)
        val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
        val numArrays = reader.readUInt8(payloadStart + 22)

        var pos = payloadStart + FIXED_HEADER_SIZE
        var numVps = 0
        var numSps = 0
        var numPps = 0
        var arraysWalked = 0
        while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
            val nalType = reader.readUInt8(pos) and 0x3F
            val numNalus = reader.readUInt16(pos + 1)
            pos += 3
            var nalusWalked = 0
            while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                val nalLength = reader.readUInt16(pos)
                if (pos + 2 + nalLength > payloadEnd) break
                pos += 2 + nalLength
                nalusWalked++
            }
            when (nalType) {
                NAL_TYPE_VPS -> numVps += nalusWalked
                NAL_TYPE_SPS -> numSps += nalusWalked
                NAL_TYPE_PPS -> numPps += nalusWalked
            }
            arraysWalked++
        }
        if (arraysWalked < numArrays) {
            w.add("Declared $numArrays NAL arrays but only found $arraysWalked")
        }

        val fields = listOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("general_profile_idc", generalProfileIdc.toString(), payloadStart + 1, 1),
            BoxField("general_level_idc", generalLevelIdc.toString(), payloadStart + 12, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 21, 1),
            BoxField("num_arrays", numArrays.toString(), payloadStart + 22, 1),
            BoxField("num_vps", numVps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_sps", numSps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_pps", numPps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$generalProfileIdc, level=$generalLevelIdc, $numSps SPS, $numPps PPS",
        )
    }
}
```

- [ ] **Step 4: Register for `hvcC` in `Decoders.kt`**

Add directly after the `avcC` line added in Task 4:

```kotlin
    BoxRegistry.register("hvcC", HvcCBoxDecoder)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests HvcCBoxDecoderTest
```
Expected: PASS (3/3).

- [ ] **Step 6: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/HvcCBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/HvcCBoxDecoderTest.kt
git commit -m "feat(parser): decode hvcC structural fields (profile, level, SPS/PPS counts)"
```

---

### Task 6: `elst` (Edit List Box) decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ElstBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ElstBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `readUIntOfWidth` (in `BinaryUtil.kt`, internal, same package), `pluralize` (in `BinaryUtil.kt`, internal, same package) — all existing.
- Produces: `object ElstBoxDecoder : BoxDecoder` — registered for `"elst"`. Uses `fields` (not `TableData`) so `media_time`/`media_rate` can display as signed values — see the design spec's rationale. Independent of every other task in this plan; no other task depends on this one.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ElstBoxDecoderTest {
    @Test
    fun `version 0 entry decodes with signed media_time and fixed-point media_rate`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,             // version=0, flags=0
            0x00, 0x00, 0x00, 0x01,             // entry_count = 1
            0x00, 0x00, 0x03, 0xE8.toByte(),    // segment_duration = 1000
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // media_time = -1 (empty edit)
            0x00, 0x01,                         // media_rate_integer = 1
            0x00, 0x00,                         // media_rate_fraction = 0
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1000", node.fields[0].value) // segment_duration
        assertEquals("-1", node.fields[1].value)    // media_time
        assertEquals("1.0", node.fields[2].value)   // media_rate
        assertEquals("1 edit", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `version 1 entry uses 8-byte duration and media_time fields`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,             // version=1, flags=0
            0x00, 0x00, 0x00, 0x01,             // entry_count = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8.toByte(), // segment_duration = 1000
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // media_time = -1
            0x00, 0x01,                         // media_rate_integer = 1
            0x00, 0x00,                         // media_rate_fraction = 0
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1000", node.fields[0].value)
        assertEquals("-1", node.fields[1].value)
        assertEquals("1.0", node.fields[2].value)
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available bytes truncates with a warning`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x02,             // entry_count = 2 (only 1 fits)
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x01,
            0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(3, node.fields.size) // only the 1 entry that fit
        reader.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests ElstBoxDecoderTest
```
Expected: FAIL — `ElstBoxDecoder` is unresolved.

- [ ] **Step 3: Create `ElstBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object ElstBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val entryWidth = (timeFieldWidth * 2 + 4).toLong()
        val declaredCount = reader.readUInt32(payloadStart + 4)
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0L) 0L else available / entryWidth
        val actualCount = minOf(declaredCount, fitCount)
        if (actualCount < declaredCount) {
            w.add("Declared $declaredCount entries but only enough space for $fitCount")
        }

        val fields = mutableListOf<BoxField>()
        var pos = entriesStart
        for (i in 0L until actualCount) {
            val durationOffset = pos
            val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
            pos += timeFieldWidth
            val mediaTimeOffset = pos
            val mediaTime = if (timeFieldWidth == 8) {
                reader.readUInt64(pos)
            } else {
                reader.readUInt32(pos).toInt().toLong()
            }
            pos += timeFieldWidth
            val rateIntegerOffset = pos
            val rateInteger = reader.readUInt16(pos).toShort().toInt()
            pos += 2
            val rateFraction = reader.readUInt16(pos).toShort().toInt()
            pos += 2
            val mediaRate = rateInteger + rateFraction / 65536.0

            fields.add(BoxField("segment_duration", duration.toString(), durationOffset, timeFieldWidth.toLong()))
            fields.add(BoxField("media_time", mediaTime.toString(), mediaTimeOffset, timeFieldWidth.toLong()))
            fields.add(BoxField("media_rate", mediaRate.toString(), rateIntegerOffset, 4))
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = pluralize(declaredCount, "edit", "edits"),
        )
    }
}
```

- [ ] **Step 4: Register for `elst` in `Decoders.kt`**

Add directly after the `hvcC` line added in Task 5:

```kotlin
    BoxRegistry.register("elst", ElstBoxDecoder)
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests ElstBoxDecoderTest
```
Expected: PASS (3/3).

- [ ] **Step 6: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ElstBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/ElstBoxDecoderTest.kt
git commit -m "feat(parser): decode elst edit list entries with signed media_time/media_rate"
```

---

### Task 7: `dref` container registration + `url `/`urn ` entry decoders

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/UrlBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/UrnBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/DrefBoxDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/UrlBoxDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/UrnBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `ContainerBoxDecoder` (existing, same package, used as-is for `dref` — no new class).
- Produces: `object UrlBoxDecoder : BoxDecoder`, `object UrnBoxDecoder : BoxDecoder` — registered for `"url "` and `"urn "` (note the trailing space in both registration keys — the box walker reads exactly 4 raw fourcc bytes). Independent of every other task in this plan; no other task depends on this one.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/DrefBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class DrefBoxDecoderTest {
    @Test
    fun `dref skips version, flags and entry_count before recursing into data entries`() {
        BoxRegistry.register("dref", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x14, 0x64, 0x72, 0x65, 0x66, // "dref", size 20
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x01,                         // entry_count = 1
                0x00, 0x00, 0x00, 0x0C, 0x75, 0x72, 0x6C, 0x20, // "url ", size 12
                0x00, 0x00, 0x00, 0x01,                         // url version/flags = self-contained
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("url ", boxes[0].children[0].type)
        assertEquals("1 entry", boxes[0].summary)
        reader.close()
    }
}
```

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/UrlBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlBoxDecoderTest {
    @Test
    fun `self-contained flag produces no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x01) // version=0, flags=1 (self-contained)
        val reader = byteReaderOf(body)
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, body.size.toLong(), emptyList())
        assertEquals(true, node.fields.isEmpty())
        assertEquals("self-contained", node.summary)
        reader.close()
    }

    @Test
    fun `non-self-contained flag decodes the location string`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + "file.mp4".toByteArray() + byteArrayOf(0)
        val reader = byteReaderOf(body)
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, body.size.toLong(), emptyList())
        assertEquals("file.mp4", node.fields[0].value)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/UrnBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class UrnBoxDecoderTest {
    @Test
    fun `decodes name and location as two consecutive null-terminated strings`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "urn:example".toByteArray() + byteArrayOf(0) + "http://example.com/asset".toByteArray() + byteArrayOf(0)
        val reader = byteReaderOf(body)
        val node = UrnBoxDecoder.decode(reader, "urn ", 0, 0, body.size.toLong(), emptyList())
        assertEquals("urn:example", node.fields[0].value)
        assertEquals("http://example.com/asset", node.fields[1].value)
        assertEquals("urn:example: http://example.com/asset", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = UrnBoxDecoder.decode(reader, "urn ", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests DrefBoxDecoderTest --tests UrlBoxDecoderTest --tests UrnBoxDecoderTest
```
Expected: FAIL — `UrlBoxDecoder`/`UrnBoxDecoder` are unresolved, and `dref` is not yet registered (falls back to `LeafBoxDecoder`, so `boxes[0].children` is empty).

- [ ] **Step 3: Create `UrlBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object UrlBoxDecoder : BoxDecoder {
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
        val flags = reader.readUInt32(payloadStart) and 0xFFFFFFL
        val selfContained = (flags and 0x1L) == 1L
        val fields = mutableListOf<BoxField>()
        if (!selfContained && payloadEnd > payloadStart + 4) {
            val locationOffset = payloadStart + 4
            val locationBytes = reader.readBytes(locationOffset, (payloadEnd - locationOffset).toInt())
            val location = String(locationBytes, Charsets.UTF_8).trimEnd(Char(0))
            fields.add(BoxField("location", location, locationOffset, locationBytes.size.toLong()))
        }
        val summary = if (selfContained) "self-contained" else null
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}
```

- [ ] **Step 4: Create `UrnBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object UrnBoxDecoder : BoxDecoder {
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
        val stringsOffset = payloadStart + 4
        val stringBytes = reader.readBytes(stringsOffset, (payloadEnd - stringsOffset).toInt())
        val nullIndex = stringBytes.indexOf(0)
        val nameEnd = if (nullIndex >= 0) nullIndex else stringBytes.size
        val name = String(stringBytes, 0, nameEnd, Charsets.UTF_8)
        val locationStart = if (nullIndex >= 0) nullIndex + 1 else stringBytes.size
        val locationBytes = stringBytes.copyOfRange(locationStart, stringBytes.size)
        val location = String(locationBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fields = listOf(
            BoxField("name", name, stringsOffset, nameEnd.toLong()),
            BoxField("location", location, stringsOffset + locationStart, locationBytes.size.toLong()),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "$name: $location")
    }
}
```

- [ ] **Step 5: Register `dref`, `url `, `urn ` in `Decoders.kt`**

Add directly after the `elst` line added in Task 6:

```kotlin
    BoxRegistry.register("dref", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
    BoxRegistry.register("url ", UrlBoxDecoder)
    BoxRegistry.register("urn ", UrnBoxDecoder)
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew test --tests DrefBoxDecoderTest --tests UrlBoxDecoderTest --tests UrnBoxDecoderTest
```
Expected: PASS (6/6 total across the three test classes).

- [ ] **Step 7: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/UrlBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/UrnBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/DrefBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/UrlBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/UrnBoxDecoderTest.kt
git commit -m "feat(parser): register dref container and decode url /urn  data entries"
```

---

### Task 8: `colr` and `pasp` decoders

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/ColrBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/PaspBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ColrBoxDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/PaspBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField` (existing, same package). Neither box is a FullBox.
- Produces: `object ColrBoxDecoder : BoxDecoder`, `object PaspBoxDecoder : BoxDecoder` — registered for `"colr"` and `"pasp"`. Independent of every other task in this plan; no other task depends on this one.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/ColrBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ColrBoxDecoderTest {
    @Test
    fun `nclx colour_type decodes primaries, transfer, matrix and full_range_flag`() {
        val body = byteArrayOf(
            'n'.code.toByte(), 'c'.code.toByte(), 'l'.code.toByte(), 'x'.code.toByte(), // colour_type = "nclx"
            0x00, 0x01, // colour_primaries = 1
            0x00, 0x01, // transfer_characteristics = 1
            0x00, 0x01, // matrix_coefficients = 1
            0x80.toByte(), // full_range_flag = true (top bit set)
        )
        val reader = byteReaderOf(body)
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, body.size.toLong(), emptyList())

        assertEquals("nclx", node.fields[0].value)
        assertEquals("1", node.fields[1].value)
        assertEquals("1", node.fields[2].value)
        assertEquals("1", node.fields[3].value)
        assertEquals("true", node.fields[4].value)
        assertEquals("nclx: 1/1/1", node.summary)
        reader.close()
    }

    @Test
    fun `non-nclx colour_type surfaces only colour_type and summarizes remaining bytes as an ICC profile`() {
        val body = byteArrayOf(
            'r'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), // colour_type = "rICC"
            0x01, 0x02, 0x03, 0x04, // arbitrary ICC profile bytes
        )
        val reader = byteReaderOf(body)
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.fields.size)
        assertEquals("rICC", node.fields[0].value)
        assertEquals("ICC profile (4 bytes)", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for colour_type returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/PaspBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PaspBoxDecoderTest {
    @Test
    fun `decodes hSpacing and vSpacing`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // hSpacing = 1
            0x00, 0x00, 0x00, 0x01, // vSpacing = 1
        )
        val reader = byteReaderOf(body)
        val node = PaspBoxDecoder.decode(reader, "pasp", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields[0].value)
        assertEquals("1", node.fields[1].value)
        assertEquals("1:1", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for hSpacing and vSpacing returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(4))
        val node = PaspBoxDecoder.decode(reader, "pasp", 0, 0, 4, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests ColrBoxDecoderTest --tests PaspBoxDecoderTest
```
Expected: FAIL — `ColrBoxDecoder`/`PaspBoxDecoder` are unresolved.

- [ ] **Step 3: Create `ColrBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object ColrBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain colour_type")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val colourType = reader.readFourCC(payloadStart)
        if (colourType != "nclx") {
            val remaining = payloadEnd - (payloadStart + 4)
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size,
                fields = listOf(BoxField("colour_type", colourType, payloadStart, 4)),
                warnings = w,
                summary = "ICC profile ($remaining bytes)",
            )
        }
        if (payloadEnd - payloadStart < 11) {
            w.add("Box too short for nclx fields")
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size,
                fields = listOf(BoxField("colour_type", colourType, payloadStart, 4)),
                warnings = w,
            )
        }
        val primariesOffset = payloadStart + 4
        val primaries = reader.readUInt16(primariesOffset)
        val transferOffset = payloadStart + 6
        val transfer = reader.readUInt16(transferOffset)
        val matrixOffset = payloadStart + 8
        val matrix = reader.readUInt16(matrixOffset)
        val fullRangeOffset = payloadStart + 10
        val fullRange = (reader.readUInt8(fullRangeOffset) and 0x80) != 0
        val fields = listOf(
            BoxField("colour_type", colourType, payloadStart, 4),
            BoxField("colour_primaries", primaries.toString(), primariesOffset, 2),
            BoxField("transfer_characteristics", transfer.toString(), transferOffset, 2),
            BoxField("matrix_coefficients", matrix.toString(), matrixOffset, 2),
            BoxField("full_range_flag", fullRange.toString(), fullRangeOffset, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "nclx: $primaries/$transfer/$matrix",
        )
    }
}
```

- [ ] **Step 4: Create `PaspBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object PaspBoxDecoder : BoxDecoder {
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
            w.add("Box too short for pasp fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val hSpacingOffset = payloadStart
        val hSpacing = reader.readUInt32(hSpacingOffset)
        val vSpacingOffset = payloadStart + 4
        val vSpacing = reader.readUInt32(vSpacingOffset)
        val fields = listOf(
            BoxField("hSpacing", hSpacing.toString(), hSpacingOffset, 4),
            BoxField("vSpacing", vSpacing.toString(), vSpacingOffset, 4),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "$hSpacing:$vSpacing",
        )
    }
}
```

- [ ] **Step 5: Register `colr` and `pasp` in `Decoders.kt`**

Add directly after the `url `/`urn ` lines added in Task 7:

```kotlin
    BoxRegistry.register("colr", ColrBoxDecoder)
    BoxRegistry.register("pasp", PaspBoxDecoder)
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew test --tests ColrBoxDecoderTest --tests PaspBoxDecoderTest
```
Expected: PASS (5/5 total across the two test classes).

- [ ] **Step 7: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/ColrBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/PaspBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/ColrBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/PaspBoxDecoderTest.kt
git commit -m "feat(parser): decode colr colour info and pasp pixel aspect ratio"
```

---

### Task 9: `iinf` and `infe` decoders (HEIC item info)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/IinfBoxDecoder.kt`
- Create: `app/src/main/kotlin/com/multiviewer/parser/InfeBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/IinfBoxDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/InfeBoxDecoderTest.kt`

**Interfaces:**
- Consumes: `ByteReader`, `BoxNode`, `BoxField`, `parseBoxes`, `pluralize` (in `BinaryUtil.kt`, internal, same package) — all existing.
- Produces: `object IinfBoxDecoder : BoxDecoder`, `object InfeBoxDecoder : BoxDecoder` — registered for `"iinf"` and `"infe"`. `IinfBoxDecoder` needs its own decoder (not `ContainerBoxDecoder`) because its child-boxes' starting offset depends on `version` (2-byte `entry_count` for version 0, 4-byte for version 1+). `InfeBoxDecoder` supports versions 2 and 3 only (the versions HEIF requires for the `item_type` field); versions below 2 add a warning and return no fields. Independent of every other task in this plan; no other task depends on this one.

- [ ] **Step 1: Write the failing tests**

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/IinfBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IinfBoxDecoderTest {
    @Test
    fun `version 0 uses a 2-byte entry_count and recurses into infe children`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version=0, flags=0
            0x00, 0x01,             // entry_count = 1 (2 bytes)
            0x00, 0x00, 0x00, 0x08, 0x69, 0x6E, 0x66, 0x65, // child "infe", size 8
        )
        val reader = byteReaderOf(body)
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals("infe", node.children[0].type)
        assertEquals("1 item", node.summary)
        reader.close()
    }

    @Test
    fun `version 1 uses a 4-byte entry_count`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, // version=1, flags=0
            0x00, 0x00, 0x00, 0x01, // entry_count = 1 (4 bytes)
            0x00, 0x00, 0x00, 0x08, 0x69, 0x6E, 0x66, 0x65, // child "infe", size 8
        )
        val reader = byteReaderOf(body)
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals("1 item", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for a FullBox header returns a warning and no children`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }
}
```

```kotlin
// app/src/test/kotlin/com/multiviewer/parser/InfeBoxDecoderTest.kt
package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class InfeBoxDecoderTest {
    @Test
    fun `version 2 decodes item_ID, item_protection_index, item_type and item_name`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00) + // version=2, flags=0
            byteArrayOf(0x00, 0x01) +                     // item_ID = 1 (2 bytes)
            byteArrayOf(0x00, 0x00) +                     // item_protection_index = 0
            "hvc1".toByteArray() +                        // item_type
            "Image".toByteArray() + byteArrayOf(0)         // item_name, null-terminated
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("0", node.fields[1].value)
        assertEquals("hvc1", node.fields[2].value)
        assertEquals("Image", node.fields[3].value)
        assertEquals("hvc1: Image", node.summary)
        reader.close()
    }

    @Test
    fun `version below 2 is unsupported and returns a warning with no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }

    @Test
    fun `box too short for version 2 fixed fields returns a warning and no fields`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test --tests IinfBoxDecoderTest --tests InfeBoxDecoderTest
```
Expected: FAIL — `IinfBoxDecoder`/`InfeBoxDecoder` are unresolved.

- [ ] **Step 3: Create `IinfBoxDecoder.kt`**

```kotlin
package com.multiviewer.parser

object IinfBoxDecoder : BoxDecoder {
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
        val entryCountWidth = if (version == 0) 2 else 4
        if (payloadEnd - payloadStart < 4 + entryCountWidth) {
            w.add("Box too short to contain entry_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val entryCount = if (entryCountWidth == 2) {
            reader.readUInt16(payloadStart + 4).toLong()
        } else {
            reader.readUInt32(payloadStart + 4)
        }
        val childrenStart = payloadStart + 4 + entryCountWidth
        val children = parseBoxes(reader, childrenStart, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(entryCount, "item", "items"),
        )
    }
}
```

- [ ] **Step 4: Create `InfeBoxDecoder.kt`**

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
        val nameBytes = reader.readBytes(nameOffset, (payloadEnd - nameOffset).toInt())
        val itemName = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fields = listOf(
            BoxField("item_ID", itemId.toString(), itemIdOffset, itemIdWidth.toLong()),
            BoxField("item_protection_index", protectionIndex.toString(), protectionIndexOffset, 2),
            BoxField("item_type", itemType, itemTypeOffset, 4),
            BoxField("item_name", itemName, nameOffset, nameBytes.size.toLong()),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "$itemType: $itemName",
        )
    }
}
```

- [ ] **Step 5: Register `iinf` and `infe` in `Decoders.kt`**

Add directly after the `colr`/`pasp` lines added in Task 8:

```kotlin
    BoxRegistry.register("iinf", IinfBoxDecoder)
    BoxRegistry.register("infe", InfeBoxDecoder)
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
./gradlew test --tests IinfBoxDecoderTest --tests InfeBoxDecoderTest
```
Expected: PASS (6/6 total across the two test classes).

- [ ] **Step 7: Run the full test suite (confirm no regression)**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 0 failures.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/IinfBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/InfeBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/IinfBoxDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/InfeBoxDecoderTest.kt
git commit -m "feat(parser): decode iinf item-info container and infe item entries"
```
