# SPS/PPS/VPS → Hex Viewer Jump Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clicking a SPS/PPS/VPS id row in the Detail Properties panel's "H.264 Parameter Sets" / "HEVC Parameter Sets" sections scrolls and highlights the hex viewer to that specific parameter set's actual raw NAL bytes inside the `avcC`/`hvcC` box.

**Architecture:** `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets` already walk a `pos` cursor past each SPS/PPS/VPS's file offset while reading its bytes — Task 1 retains that value (wrapped in a new shared `RawNal(bytes, offset)`) instead of discarding it, with no new parsing logic. Task 2 threads that offset through to the UI: `VideoInspectorUI.kt`'s existing per-tab parse pass builds an "id → file byte range" map alongside the already-parsed SPS/PPS/VPS lists; `PropertyRow` gains an optional `onClick`; the existing combined id rows split into one row per parameter set, each clickable when its offset is known; and `Main.kt`'s existing hex-highlight/scroll fallback chain (already used by tile selection, tree-node selection, and frame selection) gains this as its new top-priority source, with automatic reset back to frame-highlighting on the next frame selection.

**Tech Stack:** Kotlin, Compose Desktop. No new dependencies. Reuses `ByteReader`, `BoxNode`, and the existing hex-highlight fallback-chain machinery in `Main.kt`.

Full technical background and the exact current code being modified are in `docs/superpowers/specs/2026-08-15-parameter-set-hex-jump-design.md`.

## Global Constraints

- Only the "H.264 Parameter Sets" / "HEVC Parameter Sets" sections' id rows change layout (combined → split, one row per parameter set); no other row in either section changes.
- HEVC's VPS row is not clickable when `vps == null` (the existing best-effort VPS lookup already returns null when unresolved) — it keeps showing `"-"`, just without `onClick`.
- Selecting a different frame (any path that changes `TabState.selectedFrame`: GOP bar, filmstrip, arrow-key stepping) clears the parameter-set highlight automatically, reverting the hex viewer to that new frame's own bytes — implemented via a `LaunchedEffect(tab.selectedFrame)` inside `DetailPropertiesTabContent`, not by touching any of the several existing call sites that set `selectedFrame`.
- `PropertyRow`'s new `onClick: (() -> Unit)? = null` parameter defaults to `null` — every existing call site in the app is source-compatible and unchanged.
- `RawNal` is shared by both `H264ParameterSetExtraction.kt` and `HevcParameterSetExtraction.kt` — one data class, not duplicated per codec.
- Task 1's test fixtures are the SAME synthetic byte fixtures already used by `H264ParameterSetExtractionTest`/`HevcParameterSetExtractionTest` (no new fixture needed) — only new assertions on `RawNal.offset` are added, using byte positions hand-computed from those existing fixtures' known layout.

---

### Task 1: `RawNal` + offset capture in `avcC`/`hvcC` extraction

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/RawNal.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/H264ParameterSetExtraction.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/HevcParameterSetExtraction.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (minimal fixup only -- see Step 6; Task 2 replaces these lines again with the fuller offset-map version)
- Modify: `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt`

**Interfaces:**
- Produces: `data class RawNal(val bytes: ByteArray, val offset: Long)`.
- Changes: `AvcCRawParameterSets.spsList`/`.ppsList` and `HvcCRawParameterSets.vpsList`/`.spsList`/`.ppsList` change type from `List<ByteArray>` to `List<RawNal>`. `extractAvcCRawParameterSets`/`extractHvcCRawParameterSets`'s own signatures are unchanged. Task 2 consumes `RawNal.bytes` (passed to `parseH264Sps`/`parseHevcVps`/etc., same as the raw `ByteArray` was before) and `RawNal.offset` (new — for the offset maps).
- Consumes: `ByteReader`, `BoxNode` (existing, unchanged).

- [ ] **Step 1: Write the failing test changes**

In `app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt`, change the first test's two assertions (the SPS/PPS byte-content checks) to account for `RawNal` and add offset assertions. Replace:

```kotlin
    @Test
    fun `extractAvcCRawParameterSets reads length_size and the declared SPS and PPS byte ranges`() {
        val (node, file) = avcCBoxNode(avcCPayload())
        val result = extractAvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x67, 0xAA.toByte(), 0xBB.toByte()).toList(), result.spsList[0].toList())
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x68, 0xCC.toByte()).toList(), result.ppsList[0].toList())
    }
```

with:

```kotlin
    @Test
    fun `extractAvcCRawParameterSets reads length_size and the declared SPS and PPS byte ranges and offsets`() {
        val (node, file) = avcCBoxNode(avcCPayload())
        val result = extractAvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x67, 0xAA.toByte(), 0xBB.toByte()).toList(), result.spsList[0].bytes.toList())
        // headerSize=8 + payload index 8 (sps bytes start right after the 2-byte length field at
        // payload index 6-7) = file offset 16.
        assertEquals(16L, result.spsList[0].offset)
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x68, 0xCC.toByte()).toList(), result.ppsList[0].bytes.toList())
        // headerSize=8 + payload index 14 (pps bytes start right after the 2-byte length field at
        // payload index 12-13, itself right after the 1-byte declaredPps count at index 11) = 22.
        assertEquals(22L, result.ppsList[0].offset)
    }
```

In `app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt`, replace:

```kotlin
    @Test
    fun `extractHvcCRawParameterSets reads length_size and the declared VPS, SPS, and PPS NAL bytes`() {
        val (node, file) = hvcCBoxNode(hvcCPayload())
        val result = extractHvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.vpsList.size)
        assertEquals(byteArrayOf(0x40, 0xaa.toByte(), 0xbb.toByte()).toList(), result.vpsList[0].toList())
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x42, 0xcc.toByte(), 0xdd.toByte()).toList(), result.spsList[0].toList())
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x44, 0xee.toByte()).toList(), result.ppsList[0].toList())
    }
```

with:

```kotlin
    @Test
    fun `extractHvcCRawParameterSets reads length_size and the declared VPS, SPS, and PPS NAL bytes and offsets`() {
        val (node, file) = hvcCBoxNode(hvcCPayload())
        val result = extractHvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.vpsList.size)
        assertEquals(byteArrayOf(0x40, 0xaa.toByte(), 0xbb.toByte()).toList(), result.vpsList[0].bytes.toList())
        // headerSize=8 + payload index 28 (VPS array: 23-byte fixed header, then type(1)+numNalus(2)
        // +length(2)=5 bytes before the NAL bytes start at payload index 23+5=28) = file offset 36.
        assertEquals(36L, result.vpsList[0].offset)
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x42, 0xcc.toByte(), 0xdd.toByte()).toList(), result.spsList[0].bytes.toList())
        // SPS array starts right after VPS array's 8 bytes (payload index 23+8=31); NAL bytes start
        // 5 bytes into it, at payload index 36 -> file offset 44.
        assertEquals(44L, result.spsList[0].offset)
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x44, 0xee.toByte()).toList(), result.ppsList[0].bytes.toList())
        // PPS array starts right after SPS array's 8 bytes (payload index 31+8=39); NAL bytes start
        // 5 bytes into it, at payload index 44 -> file offset 52.
        assertEquals(52L, result.ppsList[0].offset)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetExtractionTest" --tests "com.multiviewer.parser.HevcParameterSetExtractionTest"`
Expected: FAIL — compile errors (`RawNal` unresolved, `.bytes` doesn't exist on `ByteArray`).

- [ ] **Step 3: Create `RawNal.kt`**

```kotlin
package com.multiviewer.parser

// A raw NAL's bytes plus the absolute file offset where those bytes begin (immediately after the
// NAL's length-prefix, inside its containing avcC/hvcC box) -- lets callers map a parsed
// SPS/PPS/VPS back to its exact on-disk location, e.g. for hex-viewer navigation.
data class RawNal(val bytes: ByteArray, val offset: Long)
```

- [ ] **Step 4: Update `H264ParameterSetExtraction.kt`**

Replace the `AvcCRawParameterSets` data class and `extractAvcCRawParameterSets` function (everything from `data class AvcCRawParameterSets` through the end of `extractAvcCRawParameterSets`'s closing `}`) with:

```kotlin
data class AvcCRawParameterSets(val lengthSize: Int, val spsList: List<RawNal>, val ppsList: List<RawNal>)

// Mirrors AvcCBoxDecoder's own walk of this exact box structure, but COLLECTS the raw SPS/PPS
// bytes (and each one's own file offset) instead of only counting/validating them -- AvcCBoxDecoder
// deliberately doesn't retain them (see docs/superpowers/specs/2026-07-17-box-detail-parsing-design.md).
fun extractAvcCRawParameterSets(file: File, avcCNode: BoxNode): AvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = avcCNode.offset + avcCNode.headerSize
            val payloadEnd = avcCNode.offset + avcCNode.size
            if (payloadEnd - payloadStart < 6) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 4) and 0x03) + 1
            val declaredSps = reader.readUInt8(payloadStart + 5) and 0x1F

            var pos = payloadStart + 6
            val spsList = mutableListOf<RawNal>()
            while (spsList.size < declaredSps && pos + 2 <= payloadEnd) {
                val spsLength = reader.readUInt16(pos)
                if (pos + 2 + spsLength > payloadEnd) break
                spsList.add(RawNal(reader.readBytes(pos + 2, spsLength), pos + 2))
                pos += 2 + spsLength
            }

            val ppsList = mutableListOf<RawNal>()
            if (pos < payloadEnd) {
                val declaredPps = reader.readUInt8(pos)
                pos += 1
                while (ppsList.size < declaredPps && pos + 2 <= payloadEnd) {
                    val ppsLength = reader.readUInt16(pos)
                    if (pos + 2 + ppsLength > payloadEnd) break
                    ppsList.add(RawNal(reader.readBytes(pos + 2, ppsLength), pos + 2))
                    pos += 2 + ppsLength
                }
            }
            AvcCRawParameterSets(lengthSize, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}
```

`resolveActivePicParameterSetId` and `resolveActiveParameterSets` (the rest of the file) are unchanged.

- [ ] **Step 5: Update `HevcParameterSetExtraction.kt`**

Replace the `HvcCRawParameterSets` data class and `extractHvcCRawParameterSets` function (everything from `data class HvcCRawParameterSets` through the end of `extractHvcCRawParameterSets`'s closing `}`) with:

```kotlin
data class HvcCRawParameterSets(
    val lengthSize: Int,
    val vpsList: List<RawNal>,
    val spsList: List<RawNal>,
    val ppsList: List<RawNal>,
)

private const val HVCC_FIXED_HEADER_SIZE = 23
private const val HEVC_NAL_TYPE_VPS = 32
private const val HEVC_NAL_TYPE_SPS = 33
private const val HEVC_NAL_TYPE_PPS = 34

// Mirrors HvcCBoxDecoder's own walk of this exact box structure (and HeifHevcThumbnail.kt's
// private readHvcCInfo, which walks the same structure for a different purpose -- feeding a HEIF
// image item to ffmpeg as one concatenated Annex-B buffer), but COLLECTS the raw VPS/SPS/PPS
// bytes (and each one's own file offset) as three separate lists instead.
fun extractHvcCRawParameterSets(file: File, hvcCNode: BoxNode): HvcCRawParameterSets? {
    return try {
        ByteReader.open(file).use { reader ->
            val payloadStart = hvcCNode.offset + hvcCNode.headerSize
            val payloadEnd = hvcCNode.offset + hvcCNode.size
            if (payloadEnd - payloadStart < HVCC_FIXED_HEADER_SIZE) return@use null
            val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
            val numArrays = reader.readUInt8(payloadStart + 22)

            val vpsList = mutableListOf<RawNal>()
            val spsList = mutableListOf<RawNal>()
            val ppsList = mutableListOf<RawNal>()
            var pos = payloadStart + HVCC_FIXED_HEADER_SIZE
            var arraysWalked = 0
            while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
                val nalType = reader.readUInt8(pos) and 0x3F
                val numNalus = reader.readUInt16(pos + 1)
                pos += 3
                var nalusWalked = 0
                while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                    val nalLength = reader.readUInt16(pos)
                    pos += 2
                    if (pos + nalLength > payloadEnd) break
                    val rawNal = RawNal(reader.readBytes(pos, nalLength), pos)
                    when (nalType) {
                        HEVC_NAL_TYPE_VPS -> vpsList.add(rawNal)
                        HEVC_NAL_TYPE_SPS -> spsList.add(rawNal)
                        HEVC_NAL_TYPE_PPS -> ppsList.add(rawNal)
                    }
                    pos += nalLength
                    nalusWalked++
                }
                arraysWalked++
            }
            HvcCRawParameterSets(lengthSize, vpsList, spsList, ppsList)
        }
    } catch (e: Exception) {
        null
    }
}
```

`resolveActiveHevcPicParameterSetId` and `resolveActiveHevcParameterSets` (the rest of the file) are unchanged.

- [ ] **Step 6: Minimal fixup of `VideoInspectorUI.kt` to keep the build green**

`spsList`/`ppsList`/`vpsList` are now `List<RawNal>`, not `List<ByteArray>` -- the two existing `LaunchedEffect`s in `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` that call `parseH264Sps`/`parseH264Pps`/`parseHevcVps`/`parseHevcSps`/`parseHevcPps` on each list element no longer compile as written. This step makes the SMALLEST possible fix to restore a green build -- Task 2 replaces these same lines again with the fuller version that also builds the offset maps, so do not add anything beyond what's needed to compile here.

Replace:

```kotlin
            tab.avcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseH264Sps(it) }
            tab.avcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseH264Pps(it) }
```

with:

```kotlin
            tab.avcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseH264Sps(it.bytes) }
            tab.avcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseH264Pps(it.bytes) }
```

and replace:

```kotlin
            tab.hevcVpsList = raw.vpsList.mapNotNull { com.multiviewer.parser.parseHevcVps(it) }
            tab.hevcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseHevcSps(it) }
            tab.hevcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseHevcPps(it) }
```

with:

```kotlin
            tab.hevcVpsList = raw.vpsList.mapNotNull { com.multiviewer.parser.parseHevcVps(it.bytes) }
            tab.hevcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseHevcSps(it.bytes) }
            tab.hevcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseHevcPps(it.bytes) }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.parser.H264ParameterSetExtractionTest" --tests "com.multiviewer.parser.HevcParameterSetExtractionTest"`
Expected: PASS (6/6 and 7/7)

- [ ] **Step 8: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/RawNal.kt \
        app/src/main/kotlin/com/multiviewer/parser/H264ParameterSetExtraction.kt \
        app/src/main/kotlin/com/multiviewer/parser/HevcParameterSetExtraction.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/test/kotlin/com/multiviewer/parser/H264ParameterSetExtractionTest.kt \
        app/src/test/kotlin/com/multiviewer/parser/HevcParameterSetExtractionTest.kt
git commit -m "Capture each SPS/PPS/VPS NAL's file offset during avcC/hvcC extraction"
```

---

### Task 2: Wire clicks through to the hex viewer

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (add six `TabState` fields after the existing HEVC fields)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/Components.kt` (`PropertyRow` gains `onClick`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt` (both existing `LaunchedEffect`s also populate the new offset maps)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt` (split the combined id rows, wire their `onClick`, add the auto-clear effect)
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (both the highlight-range computation and the scroll `LaunchedEffect` gain `parameterSetHighlightRange` as their new top-priority source)

**Interfaces:**
- Consumes: `RawNal` (Task 1) in `VideoInspectorUI.kt`.
- Consumes: `TabState.avcSpsOffsets`/`avcPpsOffsets`/`hevcVpsOffsets`/`hevcSpsOffsets`/`hevcPpsOffsets`/`parameterSetHighlightRange` (this task's own `AppState.kt` addition) in `ImageInspectorUI.kt` and `Main.kt`.
- Consumes: `PropertyRow`'s new `onClick` parameter (this task's own `Components.kt` addition) in `ImageInspectorUI.kt`.

No new automated tests in this task — UI wiring only, matching this codebase's established convention (verified via manual app testing, same as every other UI-integration task this session).

- [ ] **Step 1: Add `TabState` fields**

In `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`, immediately after the existing:

```kotlin
    var hevcVpsList: List<com.multiviewer.parser.HevcVps> by mutableStateOf(emptyList())
    var hevcSpsList: List<com.multiviewer.parser.HevcSps> by mutableStateOf(emptyList())
    var hevcPpsList: List<com.multiviewer.parser.HevcPps> by mutableStateOf(emptyList())
    var hevcLengthSize: Int? by mutableStateOf(null)
```

insert:

```kotlin

    // Byte-range maps for the "click a Parameter Sets id row to jump the hex viewer to its actual
    // NAL bytes" feature -- keyed by each parameter set's own id (the same id already used to look
    // them up in resolveActiveParameterSets/resolveActiveHevcParameterSets above), each value the
    // exact file byte range of that entry's raw NAL bytes inside the avcC/hvcC box (see RawNal).
    var avcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var avcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcVpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcSpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    var hevcPpsOffsets: Map<Int, LongRange> by mutableStateOf(emptyMap())
    // Set when a Parameter Sets id row is clicked (see ImageInspectorUI.kt); cleared automatically
    // whenever a different frame is selected (see DetailPropertiesTabContent's LaunchedEffect), so
    // the hex viewer naturally reverts to highlighting the newly-selected frame's own bytes. Read
    // by Main.kt's existing hex-highlight/scroll fallback chain as its new top-priority source.
    var parameterSetHighlightRange: LongRange? by mutableStateOf(null)
```

- [ ] **Step 2: Add `onClick` to `PropertyRow`**

In `app/src/main/kotlin/com/multiviewer/ui/Components.kt`, replace:

```kotlin
@Composable
fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = AppTypography.bodyLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.5f)))
    }
}
```

with:

```kotlin
@Composable
fun PropertyRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = AppTypography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = AppTypography.bodyLarge,
                color = if (onClick != null) AppColors.NeonBlue else Color.Unspecified,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.5f)))
    }
}
```

(`Modifier.clickable` and `Color` are already imported in this file -- no new imports needed.)

- [ ] **Step 3: Populate the offset maps in `VideoInspectorUI.kt`**

Replace:

```kotlin
    // Parses the video track's avcC box once per tab -- independent of tab.videoCodecName's own
    // probe above (this just checks whether an avcC box exists in the tree at all, the same gate
    // parseH264Sps/parseH264Pps's own callers rely on implicitly via an empty list otherwise).
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val avcCNode = com.multiviewer.parser.findFirst(root) { it.type == "avcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractAvcCRawParameterSets(tab.file, avcCNode) ?: return@withContext
            tab.avcLengthSize = raw.lengthSize
            tab.avcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseH264Sps(it.bytes) }
            tab.avcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseH264Pps(it.bytes) }
        }
    }

    // Parses the video track's hvcC box once per tab -- mirrors the avcC LaunchedEffect above.
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val hvcCNode = com.multiviewer.parser.findFirst(root) { it.type == "hvcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractHvcCRawParameterSets(tab.file, hvcCNode) ?: return@withContext
            tab.hevcLengthSize = raw.lengthSize
            tab.hevcVpsList = raw.vpsList.mapNotNull { com.multiviewer.parser.parseHevcVps(it.bytes) }
            tab.hevcSpsList = raw.spsList.mapNotNull { com.multiviewer.parser.parseHevcSps(it.bytes) }
            tab.hevcPpsList = raw.ppsList.mapNotNull { com.multiviewer.parser.parseHevcPps(it.bytes) }
        }
    }
```

(This is the intermediate state left by Task 1's Step 6 minimal fixup -- note the `.bytes` already present on each `parseXxx(...)` call. Task 2 replaces it with:)

```kotlin
    // Parses the video track's avcC box once per tab -- independent of tab.videoCodecName's own
    // probe above (this just checks whether an avcC box exists in the tree at all, the same gate
    // parseH264Sps/parseH264Pps's own callers rely on implicitly via an empty list otherwise).
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val avcCNode = com.multiviewer.parser.findFirst(root) { it.type == "avcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractAvcCRawParameterSets(tab.file, avcCNode) ?: return@withContext
            tab.avcLengthSize = raw.lengthSize
            val parsedSps = raw.spsList.mapNotNull { nal -> com.multiviewer.parser.parseH264Sps(nal.bytes)?.let { it to nal } }
            val parsedPps = raw.ppsList.mapNotNull { nal -> com.multiviewer.parser.parseH264Pps(nal.bytes)?.let { it to nal } }
            tab.avcSpsList = parsedSps.map { it.first }
            tab.avcPpsList = parsedPps.map { it.first }
            tab.avcSpsOffsets = parsedSps.associate { (sps, nal) -> sps.seqParameterSetId to (nal.offset until nal.offset + nal.bytes.size) }
            tab.avcPpsOffsets = parsedPps.associate { (pps, nal) -> pps.picParameterSetId to (nal.offset until nal.offset + nal.bytes.size) }
        }
    }

    // Parses the video track's hvcC box once per tab -- mirrors the avcC LaunchedEffect above.
    LaunchedEffect(tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        val hvcCNode = com.multiviewer.parser.findFirst(root) { it.type == "hvcC" } ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val raw = com.multiviewer.parser.extractHvcCRawParameterSets(tab.file, hvcCNode) ?: return@withContext
            tab.hevcLengthSize = raw.lengthSize
            val parsedVps = raw.vpsList.mapNotNull { nal -> com.multiviewer.parser.parseHevcVps(nal.bytes)?.let { it to nal } }
            val parsedSps = raw.spsList.mapNotNull { nal -> com.multiviewer.parser.parseHevcSps(nal.bytes)?.let { it to nal } }
            val parsedPps = raw.ppsList.mapNotNull { nal -> com.multiviewer.parser.parseHevcPps(nal.bytes)?.let { it to nal } }
            tab.hevcVpsList = parsedVps.map { it.first }
            tab.hevcSpsList = parsedSps.map { it.first }
            tab.hevcPpsList = parsedPps.map { it.first }
            tab.hevcVpsOffsets = parsedVps.associate { (vps, nal) -> vps.vpsId to (nal.offset until nal.offset + nal.bytes.size) }
            tab.hevcSpsOffsets = parsedSps.associate { (sps, nal) -> sps.spsId to (nal.offset until nal.offset + nal.bytes.size) }
            tab.hevcPpsOffsets = parsedPps.associate { (pps, nal) -> pps.ppsId to (nal.offset until nal.offset + nal.bytes.size) }
        }
    }
```

- [ ] **Step 4: Split the id rows and wire clicks in `ImageInspectorUI.kt`**

First, find the `DetailPropertiesTabContent` composable's `resolvedH264Params`/`resolvedHevcParams` block (the two `produceState` blocks computed before the `LazyColumn`) and, immediately after them (still before `Box(modifier = Modifier.fillMaxSize())`), insert:

```kotlin
        LaunchedEffect(tab.selectedFrame) { tab.parameterSetHighlightRange = null }
```

Then, inside the `selectedFrame != null ->` item block, replace:

```kotlin
                                PropertyRow("SPS ID / PPS ID", "${sps.seqParameterSetId} / ${pps.picParameterSetId}")
```

with:

```kotlin
                                PropertyRow(
                                    "SPS ID", sps.seqParameterSetId.toString(),
                                    onClick = tab.avcSpsOffsets[sps.seqParameterSetId]?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
                                PropertyRow(
                                    "PPS ID", pps.picParameterSetId.toString(),
                                    onClick = tab.avcPpsOffsets[pps.picParameterSetId]?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
```

And replace:

```kotlin
                                PropertyRow("VPS ID / SPS ID / PPS ID", "${vps?.vpsId ?: "-"} / ${sps.spsId} / ${pps.ppsId}")
```

with:

```kotlin
                                PropertyRow(
                                    "VPS ID", vps?.vpsId?.toString() ?: "-",
                                    onClick = vps?.let { tab.hevcVpsOffsets[it.vpsId] }?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
                                PropertyRow(
                                    "SPS ID", sps.spsId.toString(),
                                    onClick = tab.hevcSpsOffsets[sps.spsId]?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
                                PropertyRow(
                                    "PPS ID", pps.ppsId.toString(),
                                    onClick = tab.hevcPpsOffsets[pps.ppsId]?.let { range -> { tab.parameterSetHighlightRange = range } },
                                )
```

- [ ] **Step 5: Wire `parameterSetHighlightRange` into `Main.kt`'s hex-highlight chain**

Replace:

```kotlin
                            val hexHighlightRange = currentTab.tileHighlightRange
                                ?: activeField?.let { it.offset until (it.offset + it.length) }
                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                                ?: currentTab.selectedFrame?.let { frame ->
                                    frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                                }
```

with:

```kotlin
                            val hexHighlightRange = currentTab.parameterSetHighlightRange
                                ?: currentTab.tileHighlightRange
                                ?: activeField?.let { it.offset until (it.offset + it.length) }
                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                                ?: currentTab.selectedFrame?.let { frame ->
                                    frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                                }
```

Then find the separate scroll-to-item `LaunchedEffect` (keyed on `currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame`) and replace:

```kotlin
                        LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame) {
                            val tileRange = currentTab.tileHighlightRange
                            val field = activeField
                            val frameOffset = currentTab.selectedFrame?.byteOffset
                            when {
                                tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
                                field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                                frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
                                else -> currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }
```

with:

```kotlin
                        LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame, currentTab.parameterSetHighlightRange) {
                            val paramRange = currentTab.parameterSetHighlightRange
                            val tileRange = currentTab.tileHighlightRange
                            val field = activeField
                            val frameOffset = currentTab.selectedFrame?.byteOffset
                            when {
                                paramRange != null -> hexListState.scrollToItem((paramRange.first / BYTES_PER_ROW).toInt())
                                tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
                                field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                                frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
                                else -> currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }
```

- [ ] **Step 6: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions (this also confirms Task 1's tests, which couldn't compile the full app until this task's `VideoInspectorUI.kt` fix, now pass end-to-end)

- [ ] **Step 8: Manual verification**

Launch the app (`./gradlew :app:run`), open a real H.264 file, select a frame, confirm:
- "SPS ID" and "PPS ID" now show as two separate rows (not one combined "SPS ID / PPS ID" row), each rendered in the accent color used for clickable rows.
- Clicking "SPS ID" scrolls/highlights the hex viewer to that SPS's actual bytes inside the file's `avcC` box (a different, typically much earlier, file region than the currently-selected frame's own highlighted bytes in `mdat`). Clicking "PPS ID" does the same for the PPS.
- Selecting a different frame (GOP bar, filmstrip, or arrow key) reverts the hex viewer's highlight back to that new frame's own bytes, with no extra click needed.

Then open a real HEVC file and repeat for "VPS ID"/"SPS ID"/"PPS ID" (three separate rows). If VPS resolution fails for a given file (shows "-"), confirm that row is not clickable (no color change, no highlight change on click) while SPS ID/PPS ID remain clickable.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/ui/Components.kt \
        app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt \
        app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Click a SPS/PPS/VPS id row to jump the hex viewer to its bytes"
```
