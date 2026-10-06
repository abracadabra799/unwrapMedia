# Explorer Thumbnail Priority and Progressive Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first viewport of large media folders show useful thumbnails sooner by prioritizing visible work and progressively displaying embedded JPEG previews.

**Architecture:** Add a stable priority queue that can reprioritize pending thumbnail work as the grid viewport changes, while preserving the existing three-worker limit. Add a focused EXIF IFD1 thumbnail decode path, then integrate preview/final updates into the existing loader and disk cache so only final-size results persist.

**Tech Stack:** Kotlin, Compose Desktop LazyVerticalGrid, Skia, existing ByteReader/JPEG/EXIF parser, JUnit 5 via Gradle.

**Spec:** `docs/superpowers/specs/2026-10-06-explorer-thumbnail-priority-design.md`

## Global Constraints

- Final explorer thumbnail longest edge remains 240 px.
- Existing image decode concurrency remains capped at three workers.
- Only the immediately following grid row is prefetched; visible items outrank prefetch.
- A viewport change reprioritizes queued work but never interrupts active native decode work.
- Only final thumbnail results are persisted in the disk cache.
- Non-JPEG media retains its existing decode/fallback behavior.
- No diagnostic event may include media paths or names.
- Do not claim a numeric Windows/FastStone speedup without a matching benchmark.

## Review Focus

- JPEG with no IFD1 thumbnail: skip preview and finish through the current scaled decoder; pin in Task 2.
- JPEG with malformed or out-of-bounds thumbnail offsets: treat as a miss, not a failure; pin in Task 2.
- JPEG with a valid embedded preview: publish preview before final and persist only final; pin in Task 3.
- Rapid scroll while visible work is queued: promote new visible jobs and demote off-screen jobs without duplicating work; pin in Task 1 and Task 3.
- Non-JPEG and video items: keep current decode/fallback routes and the three-worker limit; pin in Task 3.

---

### Task 1: Add a reprioritizable thumbnail work queue

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/ThumbnailPriorityQueue.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/ThumbnailViewportPriority.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ThumbnailPriorityQueueTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ThumbnailViewportPriorityTest.kt`

**Interfaces:**
- Produces `internal data class ScheduledThumbnailWork(val key: String, val priority: Int, val sequence: Long, val action: () -> Unit)`.
- Produces `internal class ThumbnailPriorityQueue` with `enqueue(key: String, priority: Int, action: () -> Unit): Boolean`, `reprioritize(priorityForKey: (String) -> Int): Unit`, blocking `takeNext(): ScheduledThumbnailWork`, and `remove(key: String): Boolean`.
- Produces `internal fun thumbnailPrefetchIndices(visibleIndices: Set<Int>, columnCount: Int, itemCount: Int): Set<Int>`, returning only valid indices in the single row immediately after the last visible row.
- Produces `internal fun thumbnailPriorityForIndex(index: Int, visibleIndices: Set<Int>, prefetchIndices: Set<Int>): Int`, returning `0` for visible items, `1` for prefetch indices, and `2` for other queued items.
- `thumbnailPrefetchIndices` requires `columnCount > 0`; it returns empty for an empty visible set or non-positive item count and ignores visible indices outside `0 until itemCount`.
- `enqueue` returns false when that key is already pending; order is ascending priority, then ascending insertion sequence.
- `reprioritize` updates every queued task and restores queue order. Running work is removed from the queue and is not changed.

- [ ] **Step 1: Write failing scheduler tests**

Add tests named:
- `visible work is returned before prefetch work`
- `same-priority work remains FIFO`
- `reprioritize promotes newly visible work and demotes off-screen work`
- `enqueue deduplicates a pending key`
- `remove deletes only queued work`

Assert returned keys and priorities; assert duplicate enqueue returns `false` and the original action remains queued.

In `ThumbnailViewportPriorityTest.kt`, add `prioritizes_visible_items_then_exactly_one_following_row`; assert the prefetch set contains exactly the next row, priority values are 0/1/2 for visible/prefetch/background, and visible items at the end of the file produce no out-of-range indices.
Also add `empty_or_out_of-range_visible_indices_do_not_prefetch` and `column_count_must_be_positive` for the API behavior above.

- [ ] **Step 2: Run tests and verify the expected failure**

Run: `./gradlew test --tests 'com.multiviewer.ui.ThumbnailPriorityQueueTest' --tests 'com.multiviewer.ui.ThumbnailViewportPriorityTest' --no-daemon`

Expected: compilation fails because the queue and viewport-priority APIs do not exist.

- [ ] **Step 3: Implement the queue and viewport helpers**

Implement the interfaces above in their named files. Protect queue/map mutation with one lock; `takeNext()` waits on that lock until work is available. When reprioritizing, remove and reinsert queued entries so mutable priorities cannot violate heap ordering. Calculate the prefetch row from the largest valid visible index and clamp its end to `itemCount`.

- [ ] **Step 4: Run scheduler tests**

Run: `./gradlew test --tests 'com.multiviewer.ui.ThumbnailPriorityQueueTest' --tests 'com.multiviewer.ui.ThumbnailViewportPriorityTest' --no-daemon`

Expected: all five queue tests and the viewport-priority test pass.

---

### Task 2: Decode JPEG EXIF IFD1 thumbnails without full image analysis

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/parser/ImageAnalyzer.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/ImageAnalyzerTest.kt`

**Interfaces:**
- Produces `ImageAnalyzer.decodeEmbeddedJpegThumbnail(file: File, longestEdge: Int): ImageBitmap?`.
- Reuses `ByteReader.open`, `parseJpegSegments`, and the existing bounded EXIF `ThumbnailImage` extraction logic.
- Does not decode the primary JPEG, calculate a histogram, or invoke `ImageAnalyzer.analyze`.
- Returns `null` for absent, malformed, invalid, or undecodable embedded thumbnails; otherwise returns a bitmap whose longest edge is at most `longestEdge`.

- [ ] **Step 1: Add fixture-based failing tests**

Add tests named:
- `decodeEmbeddedJpegThumbnail returns a bounded bitmap for a JPEG with IFD1 thumbnail`
- `decodeEmbeddedJpegThumbnail returns null when IFD1 thumbnail is absent`
- `decodeEmbeddedJpegThumbnail returns null for an out-of-bounds IFD1 thumbnail`

Build the JPEG/EXIF bytes in test helpers with a small valid JPEG payload and explicit TIFF IFD0/IFD1 offsets. Assert decoded dimensions for the valid case and `null` for the other cases.

- [ ] **Step 2: Run the focused tests and verify the expected failure**

Run: `./gradlew test --tests 'com.multiviewer.parser.ImageAnalyzerTest' --no-daemon`

Expected: compilation fails because `decodeEmbeddedJpegThumbnail` does not exist.

- [ ] **Step 3: Implement the focused extractor**

Add the public method in `ImageAnalyzer.kt`. Parse JPEG segments into a root node, call the existing embedded-image extraction routine, and scale only if the extracted image is larger than the requested edge. Close temporary Skia resources on all paths.

- [ ] **Step 4: Run parser tests**

Run: `./gradlew test --tests 'com.multiviewer.parser.ImageAnalyzerTest' --no-daemon`

Expected: all ImageAnalyzer tests pass, including the three new embedded-thumbnail cases.

---

### Task 3: Integrate viewport priority and progressive preview delivery

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/ThumbnailLoadCoordinator.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ThumbnailRequestRegistryTest.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ThumbnailLoadingCoordinatorTest.kt`

**Interfaces:**
- Consumes `ThumbnailPriorityQueue` from Task 1 and `ImageAnalyzer.decodeEmbeddedJpegThumbnail(file, longestEdge)` from Task 2.
- Existing `ThumbnailRequestRegistry<T>` in `ImageCompareWindow.kt` gains `publish(key: String, value: T, isFinal: Boolean): List<(T) -> Unit>`: non-final updates preserve listeners; final updates remove them.
- Produces `internal class ThumbnailLoadCoordinator<T>(preview: (File) -> T?, decodeFinal: (File) -> T?, persistFinal: (File, T) -> Unit, publish: (File, T, Boolean) -> Unit, finishWithoutBitmap: (File) -> Unit)` with `load(file: File): Unit`; it publishes an optional preview as non-final, publishes a successful final decode as final, and persists only a non-null final decode. If final decode fails after a preview was published, it republishes that preview as the terminal UI result without persisting it; if both stages fail, it calls `finishWithoutBitmap` so listeners can be discarded.
- Grid request API accepts item index and a callback. The grid viewport publishes visible item indices and the one-row look-ahead set to the loader; list view uses FIFO priority.
- A single worker loop per existing worker executes dequeued jobs; total worker count remains three.

- [ ] **Step 1: Add failing registry/coordinator tests**

Add tests named:
- `non-final preview keeps listeners for the final bitmap`
- `final bitmap completes and removes all listeners`
- `embedded preview is delivered before final decode`
- `preview miss delivers only the final bitmap`
- `final result is cached but embedded preview is not persisted`
- `preview remains terminal when final decode fails without being persisted`
- `total decode failure discards listeners`
- `preview extractor exception falls through to final decoder`

Use injected preview/final decoder functions and a test cache boundary so these tests assert callback order, decoder call count, and persistence behavior without launching a Compose window or decoding arbitrary large images. Registry tests should assert that a preview publication retains all listeners and a final publication removes them.

- [ ] **Step 2: Run focused tests and verify expected failures**

Run: `./gradlew test --tests 'com.multiviewer.ui.ThumbnailRequestRegistryTest' --tests 'com.multiviewer.ui.ThumbnailLoadingCoordinatorTest' --no-daemon`

Expected: compilation fails because progressive registry/coordinator behavior is not implemented.

- [ ] **Step 3: Implement progressive request delivery**

Update `ThumbnailRequestRegistry<T>` in `ImageCompareWindow.kt` with `publish(key: String, value: T, isFinal: Boolean): List<(T) -> Unit>` and `finish(key: String): Unit`. Non-final publication keeps listeners, final publication returns and removes them, and `finish` removes listeners after a total decode failure. Keep deduplication keyed by the existing file request identity.

- [ ] **Step 4: Integrate viewport-driven queue priorities**

Use `LazyGridState` and indexed grid items to calculate visible indices plus exactly one row after the last visible row. Reprioritize pending queue entries on viewport changes. Submit only one decode per file and retain the fixed three-worker concurrency limit.

- [ ] **Step 5: Integrate preview/final/cache pipeline**

Implement `ThumbnailLoadCoordinator<T>.load(file)` in `ThumbnailLoadCoordinator.kt`, injecting the preview extractor, existing 240-pixel decode/fallback, final cache writer, update publisher, and no-bitmap completion callback. Treat preview/decode exceptions as misses; treat persistence exceptions as non-fatal and still publish the final bitmap. Connect the coordinator in `ImageCompareWindow.kt`: on memory/disk miss, attempt EXIF preview for JPEGs, publish it as non-final when available, then run the current decode/fallback. Encode and persist only the final bitmap; preserve existing non-JPEG/video paths. If both decode stages fail, discard pending listeners.

- [ ] **Step 6: Run coordinator tests**

Run: `./gradlew test --tests 'com.multiviewer.ui.ThumbnailRequestRegistryTest' --tests 'com.multiviewer.ui.ThumbnailLoadingCoordinatorTest' --no-daemon`

Expected: all registry/coordinator tests pass, including order, deduplication, fallback, and final-only persistence.

- [ ] **Step 7: Run full verification**

Run: `./gradlew test --no-daemon`

Expected: `BUILD SUCCESSFUL` with all project tests passing.

Run: `git diff --check`

Expected: no whitespace errors.

## Handoff Notes

- Preserve the existing uncommitted disk-cache and diagnostic-instrumentation work in the shared worktree; do not reset or overwrite it.
- The design document is committed as `1598afe`; implementation starts only after this plan is approved and an execution method is selected.
- Windows/FastStone comparative timing is not available from the current macOS environment and is not part of automated test claims.
