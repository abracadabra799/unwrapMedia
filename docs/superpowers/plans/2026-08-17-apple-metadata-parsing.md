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

- [x] **Step 1: Write failing trailer and dictionary tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement the bounded reader**
- [x] **Step 4: Verify pass and commit**
- [x] **Step 1: Write failing vendor-dispatch tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement Apple IFD decoding**
- [x] **Step 4: Add corruption tests and verify**
- [x] **Step 5: Commit**
- [x] **Step 1: Write failing `keys/ilst/data` tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement sibling-aware enrichment**
- [x] **Step 4: Register and verify**
- [x] **Step 5: Commit**
- [x] **Step 1: Write failing graph tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement classification and correlation**
- [x] **Step 4: Verify and commit**
- [x] **Step 1: Write failing declaration and bounds tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement sample mapping and decoding**
- [x] **Step 4: Verify and commit**
- [x] **Step 1: Write failing bit-field tests**
- [x] **Step 2: Verify failure, implement, and verify pass**
- [x] **Step 3: Commit**
- [x] **Step 1: Write failing image/video summary tests**
- [x] **Step 2: Verify failure**
- [x] **Step 3: Implement pure tree projections**
- [x] **Step 4: Verify and commit**
- [x] **Step 1: Run all automated tests**
- [x] **Step 2: Parse both local originals through the app CLI**
- [x] **Step 3: Compare against independent tools**
- [x] **Step 4: Run corruption and legacy regressions**
- [x] **Step 5: Update documentation and commit**
- [x] Run `git diff --check` and inspect `git status --short`; expect no accidental sample files and only intentional changes.
- [x] Run `./gradlew test`; expect BUILD SUCCESSFUL.
- [x] Confirm the user's pre-existing `.gitignore` change was never staged or altered by this work.
