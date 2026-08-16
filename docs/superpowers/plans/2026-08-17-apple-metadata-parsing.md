# Apple Metadata Parsing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse Apple MakerNote, QuickTime, auxiliary/timed, Dolby Vision, and frame-rate metadata natively and expose it through unwrapMedia's existing tree and summary UI.

**Architecture:** Add focused decoders that all produce `BoxNode`/`BoxField`, then project selected fields through `MediaSummaryBuilder`. The tasks are independently reviewable and ship in dependency order: Apple EXIF, QuickTime static metadata, auxiliary/timed metadata, codec metadata, then summaries and real-file regression verification.

**Tech Stack:** Kotlin/JVM, Compose Desktop, existing random-access `ByteReader`, ISO-BMFF and TIFF parsers, `kotlin.test`, Gradle.

## Global Constraints

- Do not add an Apple-only UI tab.
- Do not add an ExifTool runtime dependency.
- Preserve every structurally valid unknown Apple tag/key and its source byte range.
- Never commit the local Apple originals.
- Bound plist recursion/object counts and timed-metadata sample bytes.
- Do not infer undocumented meanings; mark community-source uncertainty explicitly.
- Reuse `BoxNode`/`BoxField` so tree, detail view, CLI, warnings, and hex navigation share one result.

---

## File Map

- Create `parser/AppleMakerNoteDecoder.kt`: Apple IFD vocabulary, typed values, plist delegation.
- Create `parser/BinaryPlistDecoder.kt`: bounded binary-plist object graph reader.
- Modify `parser/ExifDecoder.kt`: vendor dispatch and Apple decoder call.
- Create `parser/QuickTimeMetadataDecoder.kt`: `keys`, `ilst`, numeric key entries, and typed `data` values.
- Create `parser/AppleAuxiliaryMetadata.kt`: classify and correlate HEIF auxiliary roles.
- Create `parser/AppleTimedMetadataDecoder.kt`: `mebx` declarations and bounded sample metadata.
- Create `parser/DolbyVisionConfigDecoder.kt`: `dvcC`/`dvvC` bit fields.
- Modify `parser/Decoders.kt`: register new box decoders/containers.
- Modify `parser/MetaBoxDecoder.kt`: enrich metadata only after sibling `keys` and `ilst` are available.
- Modify `parser/MediaSummaryBuilder.kt`: conditional Apple Overview sections.
- Add matching focused test files under `app/src/test/kotlin/com/multiviewer/parser/`.

### Task 1: Bounded Binary Plist Decoder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/BinaryPlistDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/BinaryPlistDecoderTest.kt`

**Interfaces:**
- Produces: `fun decodeBinaryPlist(reader: ByteReader, start: Long, length: Long, limits: BinaryPlistLimits = BinaryPlistLimits()): BoxNode`
- Produces: `data class BinaryPlistLimits(val maxDepth: Int = 16, val maxObjects: Int = 4096, val maxPreviewBytes: Int = 256)`

- [ ] **Step 1: Write failing trailer and dictionary tests**

Create a fixture builder for `bplist00`, one integer, one ASCII string, and a two-entry dictionary. Assert the root is `BinaryPlist`, dictionary keys become child fields, and offsets point inside the plist. Add invalid offset-table and self-reference cases that return warnings rather than throw.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*BinaryPlistDecoderTest'`
Expected: compilation fails because `decodeBinaryPlist` and `BinaryPlistLimits` do not exist.

- [ ] **Step 3: Implement the bounded reader**

Parse the 32-byte trailer, offset-int size, object-ref size, object count, top object, and offset table with checked `Long` arithmetic. Decode markers `0x0`, `0x1`, `0x2`, `0x3`, `0x4`, `0x5`, `0x6`, `0x8`, `0xA`, `0xC`, and `0xD`; represent arrays/sets/dictionaries as child nodes and scalars as fields. Track active object IDs for cycles and enforce all three limits.

- [ ] **Step 4: Verify pass and commit**

Run: `./gradlew test --tests '*BinaryPlistDecoderTest'`
Expected: PASS.

```bash
git add app/src/main/kotlin/com/multiviewer/parser/BinaryPlistDecoder.kt app/src/test/kotlin/com/multiviewer/parser/BinaryPlistDecoderTest.kt
git commit -m "Add bounded binary plist decoder"
```

### Task 2: Apple MakerNote Parsing

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AppleMakerNoteDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AppleMakerNoteDecoderTest.kt`

**Interfaces:**
- Consumes: `decodeBinaryPlist(...)` from Task 1.
- Produces: `fun decodeAppleMakerNote(reader: ByteReader, absolutePos: Long, byteLength: Int, littleEndian: Boolean, itemEnd: Long): BoxNode`

- [ ] **Step 1: Write failing vendor-dispatch tests**

Construct a TIFF with `Make="Apple"`, Exif tag `0x927c`, and MakerNote tags `0x0001`, `0x0008`, `0x000a`, `0x0014`, `0x0021`, `0x002d`, and `0x0038`. Assert names and documented labels (`ImageCaptureType=ProRAW`) plus `Apple Tag 0x7777` fallback. Retain the existing Samsung/non-Samsung assertions.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*AppleMakerNoteDecoderTest' --tests '*ExifDecoderTest'`
Expected: Apple tags are generic or decoder is missing.

- [ ] **Step 3: Implement Apple IFD decoding**

Move vendor selection from a tag-name map to a sealed dispatch (`Samsung`, `Apple`, `Unknown`) and call `decodeAppleMakerNote` for Apple. Decode TIFF types using existing endian helpers; add ExifTool-backed names/labels from the design. Detect `bplist00` payloads and attach `decodeBinaryPlist` children. Keep raw value text when a friendly interpretation is added.

- [ ] **Step 4: Add corruption tests and verify**

Add count overflow, out-of-range value, short IFD, and malformed plist cases. Run the two targeted suites and expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AppleMakerNoteDecoder.kt app/src/main/kotlin/com/multiviewer/parser/ExifDecoder.kt app/src/test/kotlin/com/multiviewer/parser/AppleMakerNoteDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/ExifDecoderTest.kt
git commit -m "Parse Apple EXIF MakerNote metadata"
```

### Task 3: QuickTime Static Metadata

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/QuickTimeMetadataDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/QuickTimeMetadataDecoderTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt`

**Interfaces:**
- Produces: `data class QuickTimeMetadataKey(val index: Int, val namespace: String, val key: String, val offset: Long, val length: Long)`
- Produces: `fun decodeQuickTimeKeys(...): BoxNode`, `fun enrichQuickTimeMetadata(meta: BoxNode, reader: ByteReader): BoxNode`

- [ ] **Step 1: Write failing `keys/ilst/data` tests**

Build a `meta` box with `hdlr=mdta`, keys for `com.apple.quicktime.make`, `model`, `content.identifier`, and an unknown key. Add UTF-8, signed integer, float, double, binary, and malformed `data` atoms. Assert exact key resolution, values, offsets, and unknown preservation.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*QuickTimeMetadataDecoderTest'`
Expected: `keys`/numeric `ilst` children remain leaf nodes.

- [ ] **Step 3: Implement sibling-aware enrichment**

Decode full-box `keys` entries first, then resolve `ilst` numeric child FourCC values as one-based indices. Decode `data` header type/locale and types 0, 1, 2, 3, 4, 5, 13, 14, 21-24, 27-28, 65-79. Add an `Apple QuickTime Metadata` derived child while retaining original boxes.

- [ ] **Step 4: Register and verify**

Register `keys`, `ilst`, and `data` handling without treating arbitrary media `data` boxes as metadata. Run targeted tests plus `MetaBoxDecoderTest` and `DecodersRegistrationTest`; expect PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/QuickTimeMetadataDecoder.kt app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/QuickTimeMetadataDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt
git commit -m "Decode Apple QuickTime metadata keys"
```

### Task 4: HEIF Auxiliary Metadata Correlation

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AppleAuxiliaryMetadata.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AppleAuxiliaryMetadataTest.kt`

**Interfaces:**
- Produces: `enum class AppleAuxiliaryRole { HDR_GAIN_MAP, DEPTH, DISPARITY, PORTRAIT_EFFECTS, SKY, PERSON, SKIN, HAIR, TEETH, GLASSES, SMART_STYLE_LINEAR_THUMBNAIL, SMART_STYLE_DELTA_MAP, OTHER }`
- Produces: `fun buildAppleAuxiliaryNode(meta: BoxNode): BoxNode?`

- [ ] **Step 1: Write failing graph tests**

Build synthetic `pitm`, `iinf`, `iloc`, `ipma/ipco`, `auxC`, and `iref` nodes for HDR, disparity, portrait, and one semantic matte. Assert role, item ID, primary relationship, dimensions, bit depth, and dangling-reference warnings.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*AppleAuxiliaryMetadataTest'`
Expected: classifier/builder symbols are missing.

- [ ] **Step 3: Implement classification and correlation**

Normalize exact URN/tag strings, join property indices to `auxC`/`ispe`/`pixi`/`hvcC`, join `iref` endpoints, and emit one `Auxiliary Images` node. Do not read pixel extents.

- [ ] **Step 4: Verify and commit**

Run targeted tests plus `IpmaBoxDecoderTest`, `IrefBoxDecoderTest`, and `MetaBoxDecoderTest`; expect PASS.

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AppleAuxiliaryMetadata.kt app/src/main/kotlin/com/multiviewer/parser/MetaBoxDecoder.kt app/src/test/kotlin/com/multiviewer/parser/AppleAuxiliaryMetadataTest.kt
git commit -m "Correlate Apple HEIF auxiliary metadata"
```

### Task 5: Apple Timed Metadata Tracks

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/AppleTimedMetadataDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/AppleTimedMetadataDecoderTest.kt`

**Interfaces:**
- Produces: `data class TimedMetadataLimits(val maxSamples: Int = 256, val maxSampleBytes: Int = 64 * 1024, val maxTotalBytes: Long = 4L * 1024 * 1024)`
- Produces: `fun decodeTimedMetadataTrack(reader: ByteReader, trak: BoxNode, limits: TimedMetadataLimits = TimedMetadataLimits()): BoxNode?`

- [ ] **Step 1: Write failing declaration and bounds tests**

Construct a `mebx` track with `stsd`, `keyd`, `stts`, `stsc`, `stsz`, and `stco`. Assert parsing of still-image-time/orientation keys, timestamp calculation, unknown payload preview, and warnings when sample/count/byte caps are exceeded.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*AppleTimedMetadataDecoderTest'`
Expected: missing decoder symbols.

- [ ] **Step 3: Implement sample mapping and decoding**

Resolve declared metadata keys, expand sample timing/chunk mapping only up to limits, validate every file range, and decode known scalar/compact plist payloads. Emit a derived `Timed Metadata` child under `mebx` tracks.

- [ ] **Step 4: Verify and commit**

Run targeted tests plus fixed-width/stsz tests; expect PASS.

```bash
git add app/src/main/kotlin/com/multiviewer/parser/AppleTimedMetadataDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/AppleTimedMetadataDecoderTest.kt
git commit -m "Parse bounded Apple timed metadata"
```

### Task 6: Dolby Vision Configuration

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/DolbyVisionConfigDecoder.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/Decoders.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/DolbyVisionConfigDecoderTest.kt`

**Interfaces:**
- Produces: `object DolbyVisionConfigDecoder : BoxDecoder`

- [ ] **Step 1: Write failing bit-field tests**

Use synthetic `dvcC` and `dvvC` records and assert version, profile, level, RPU/EL/BL flags, and compatibility ID. Add short-record warning coverage.

- [ ] **Step 2: Verify failure, implement, and verify pass**

Run targeted test (missing decoder), implement checked bit extraction with `BoxField` byte ranges, register both FourCCs, then rerun targeted and registration tests; expect PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/DolbyVisionConfigDecoder.kt app/src/main/kotlin/com/multiviewer/parser/Decoders.kt app/src/test/kotlin/com/multiviewer/parser/DolbyVisionConfigDecoderTest.kt app/src/test/kotlin/com/multiviewer/parser/DecodersRegistrationTest.kt
git commit -m "Decode Dolby Vision configuration boxes"
```

### Task 7: Apple Overview Projection

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt`

**Interfaces:**
- Consumes: tree nodes produced by Tasks 2-6.
- Produces: conditional `SummarySection`s named `Apple Device`, `Camera & Capture`, `Computational Photography`, `Depth & Portrait`, `Live Photo`, and `Video Metadata`.

- [ ] **Step 1: Write failing image/video summary tests**

Build representative trees and assert exact section/field labels, omission of empty sections, no base64/matrix payload in Overview, separate nominal/average frame rates, and separate Dolby Vision/Apple Log labels.

- [ ] **Step 2: Verify failure**

Run: `./gradlew test --tests '*MediaSummaryBuilderTest'`
Expected: Apple sections are absent.

- [ ] **Step 3: Implement pure tree projections**

Add small helpers that search only decoded nodes and return `SummarySection?`; do not invoke ffprobe or re-read the file. Append sections in the approved order for image and video summaries.

- [ ] **Step 4: Verify and commit**

Run `MediaSummaryBuilderTest`; expect PASS.

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt app/src/test/kotlin/com/multiviewer/parser/MediaSummaryBuilderTest.kt
git commit -m "Show Apple metadata in media overview"
```

### Task 8: Full Regression and Real Apple Samples

**Files:**
- Modify if findings require fixes: files introduced in Tasks 1-7
- Modify: `README.md`

**Interfaces:**
- Consumes: complete Apple metadata feature.
- Produces: documented support and verification evidence.

- [ ] **Step 1: Run all automated tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL with no failed tests.

- [ ] **Step 2: Parse both local originals through the app CLI**

Run the repository's dump/check entry points against the HEIC and MOV paths from the design. Assert the HEIC exposes Apple identity, MakerNote, HDR gain map, depth/portrait/semantic roles; assert the MOV exposes every ffprobe-observed `com.apple.quicktime.*` key, auxiliary/mebx tracks, and frame-rate distinction. Do not stage either file.

- [ ] **Step 3: Compare against independent tools**

Use ffprobe JSON for MOV static keys/streams and a locally installed ExifTool if available. Record discrepancies as tests before changing production code; unknown-but-preserved values are acceptable, missing structurally valid keys are not.

- [ ] **Step 4: Run corruption and legacy regressions**

Run: `./gradlew test --tests '*ExifDecoderTest' --tests '*MetaBoxDecoderTest' --tests '*IrefBoxDecoderTest' --tests '*MediaSummaryBuilderTest' --tests '*ParseFileIntegrationTest' --tests '*DumpFileTest' --tests '*CheckFileTest'`
Expected: PASS with Samsung MakerNote and generic formats unchanged.

- [ ] **Step 5: Update documentation and commit**

Add Apple MakerNote, QuickTime keys, auxiliary metadata, timed metadata, and Dolby Vision configuration to the README feature list without claiming pixel visualization.

```bash
git add README.md app/src/main app/src/test
git commit -m "Complete Apple metadata support"
```

## Final Verification

- [ ] Run `git diff --check HEAD~8..HEAD` and inspect `git status --short`; expect no accidental sample files and only intentional changes.
- [ ] Run `./gradlew test`; expect BUILD SUCCESSFUL.
- [ ] Confirm the user's pre-existing `.gitignore` change was never staged or altered by this work.
