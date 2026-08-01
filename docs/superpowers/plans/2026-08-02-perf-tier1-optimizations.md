# Tier 1 Performance Optimizations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two small, independent, output-preserving performance fixes found during a codebase-wide optimization audit: avoid materializing GIF pixel-data bytes that are immediately discarded, and stop recomputing a GOP frame-size max on every playback-position update.

**Architecture:** Each fix is a self-contained change to one file, with no change to any function's observable output (tree structure, field values, UI display). Both are drawn from a prior read-only audit; a third candidate (caching `FfmpegLocator`'s resolved path) was explicitly dropped because it conflicts with `FfmpegLocatorTest.kt`'s existing design (which relies on re-resolving per system-property state across test methods within the same JVM singleton).

**Tech Stack:** Kotlin, no new dependencies.

## Global Constraints

- Neither fix may change any observable output: `GifWalker.kt`'s change must produce byte-identical `BoxNode` trees to today (same types, offsets, sizes, fields, warnings) for every GIF this app already parses correctly -- this is provable because the existing `GifWalkerTest.kt` suite already asserts on exact tree/field/summary values for the two code paths being touched, and must keep passing completely unmodified.
- `GopAnalysisView.kt`'s change must not alter what's displayed -- only when the `maxSize` value is recomputed, not what it computes to.
- Do not touch `FfmpegLocator.kt` in this plan (explicitly descoped -- see Architecture).

---

### Task 1: `GifWalker.kt` -- skip GIF pixel sub-block bytes instead of reading them

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`

**Interfaces:**
- Consumes: `ByteReader.readUInt8(offset: Long): Int` (existing, unchanged).
- Produces: `private fun skipSubBlocks(reader: ByteReader, pos: Long, end: Long): Long?` -- a new private helper, used only within this file by the two call sites that currently discard `readSubBlocks`'s block-bytes result.

- [ ] **Step 1: Confirm the existing test suite passes before touching anything**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest"`
Expected: PASS, all tests green (this is the baseline the refactor must not disturb).

- [ ] **Step 2: Add the `skipSubBlocks` helper**

In `app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt`, find:

```kotlin
private fun readSubBlocks(reader: ByteReader, pos: Long, end: Long): Pair<List<ByteArray>, Long>? {
    val blocks = mutableListOf<ByteArray>()
    var p = pos
    while (true) {
        if (p >= end) return null
        val size = reader.readUInt8(p)
        p += 1
        if (size == 0) break
        if (p + size > end) return null
        blocks.add(reader.readBytes(p, size))
        p += size
    }
    return blocks to p
}
```

Add this new function immediately after it (same file, same visibility):

```kotlin
// Same position-tracking control flow as readSubBlocks above (identical bounds checks and
// termination condition), but never materializes the sub-block payload bytes -- for call sites
// that only need to know where the sub-block sequence ends (e.g. the GIF's compressed pixel
// data, typically the bulk of the file), reading every byte into a ByteArray just to discard it
// wastes an allocation and a copy per sub-block for no benefit.
private fun skipSubBlocks(reader: ByteReader, pos: Long, end: Long): Long? {
    var p = pos
    while (true) {
        if (p >= end) return null
        val size = reader.readUInt8(p)
        p += 1
        if (size == 0) break
        if (p + size > end) return null
        p += size
    }
    return p
}
```

- [ ] **Step 3: Switch the two discarding call sites to use it**

Find:

```kotlin
private fun decodeGenericSubBlockExtension(reader: ByteReader, type: String, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (_, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    return BoxNode(type = type, offset = offset, headerSize = 2, size = nextPos - offset) to nextPos
}
```

Replace with:

```kotlin
private fun decodeGenericSubBlockExtension(reader: ByteReader, type: String, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val nextPos = skipSubBlocks(reader, offset + 2, end) ?: return null
    return BoxNode(type = type, offset = offset, headerSize = 2, size = nextPos - offset) to nextPos
}
```

Find:

```kotlin
    val (_, nextPos) = readSubBlocks(reader, pos, end) ?: return null

    return BoxNode(
        type = "ImageDescriptor", offset = offset, headerSize = 10, size = nextPos - offset,
```

Replace with:

```kotlin
    val nextPos = skipSubBlocks(reader, pos, end) ?: return null

    return BoxNode(
        type = "ImageDescriptor", offset = offset, headerSize = 10, size = nextPos - offset,
```

Do **not** change `decodeGraphicControlExtension` or `decodeApplicationExtension` -- both actually use the returned block bytes (`data`/`header`/`loopBlock`) and must keep calling `readSubBlocks`.

- [ ] **Step 4: Run the test suite to confirm byte-identical output**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test --tests "com.multiviewer.parser.GifWalkerTest"`
Expected: PASS, all tests green, completely unmodified from Step 1 -- this is the proof the refactor is output-preserving (the animated-GIF, Comment Extension, and both Image Descriptor tests all exercise the exact two call sites that changed).

Then run the full suite once to confirm nothing else regressed:

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/GifWalker.kt
git commit -m "perf: skip GIF pixel sub-block bytes instead of reading and discarding them"
```

---

### Task 2: `GopAnalysisView.kt` -- memoize the frame-size max

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

**Interfaces:**
- Consumes: nothing new (`androidx.compose.runtime.remember` is already imported and used elsewhere in this file, e.g. `frameBarWidthDp`/`currentFrameIndex`).
- Produces: no signature changes -- `maxSize`'s computed value and every downstream use of it (the frame bar heights) are unchanged, only when it's recomputed changes.

- [ ] **Step 1: Wrap the computation in `remember(frames)`**

In `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`, find:

```kotlin
                    val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
```

Replace with:

```kotlin
                    val maxSize = remember(frames) { frames.maxOf { it.sizeBytes }.coerceAtLeast(1) }
```

- [ ] **Step 2: Build and confirm no regressions**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: PASS, no new failures (there's no automated test for `GopAnalysisView.kt` -- confirmed no such test file exists -- so this build+full-suite check is the only automated signal for this task; Task 3 covers the manual visual check).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
git commit -m "perf: stop recomputing the GOP frame-size max on every playback-position update"
```

---

### Task 3: Manual end-to-end verification (controller-performed)

No automated coverage is possible for the GOP bar-chart visual (Compose UI). This step is performed by the controller directly, not dispatched to a subagent.

- [ ] Launch the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`) and open a real GIF (including an animated one, if easily available) -- confirm the tree view still shows `LogicalScreenDescriptor`/`ImageDescriptor`/`GraphicControlExtension`/etc. exactly as before, with correct fields and no new warnings
- [ ] Open a real video file, run frame analysis, and confirm the GOP bar chart still renders with correct proportional bar heights (tallest bar = largest frame) and that playing the video still moves the current-frame highlight/scroll correctly
- [ ] If any issue is found, treat it as a real bug -- return to systematic-debugging, not a quick patch

---

## Self-Review Notes

- **Spec coverage:** GifWalker skip-optimization (byte-identical output, proven by the existing unmodified test suite) ✅ (Task 1), GopAnalysisView memoization (no display change) ✅ (Task 2), manual verification of both ✅ (Task 3). The dropped FfmpegLocator item is explicitly noted as out of scope in Global Constraints, not silently omitted.
- **Placeholder scan:** none found.
- **Type consistency:** `skipSubBlocks(reader: ByteReader, pos: Long, end: Long): Long?` is defined once in Task 1 and used at its two call sites within the same task/file -- no cross-task interface to keep consistent, since both tasks are fully independent single-file changes.
