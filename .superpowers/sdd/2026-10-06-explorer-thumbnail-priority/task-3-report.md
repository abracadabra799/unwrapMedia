# Task 3: Viewport priority and progressive preview delivery

## Implementation

- Added `ThumbnailLoadCoordinator<T>` as an injected seam for preview extraction, final decode, persistence, publication, and failure completion.
- Extended `ThumbnailRequestRegistry` so preview publications retain listeners and final publications remove them. Kept `complete` as a compatibility adapter for an existing test/caller.
- Replaced direct thumbnail executor submissions with the Task 1 priority queue and three worker loops. Grid requests receive visible/prefetch/background priority; the viewport recalculates the visible set and exactly one look-ahead row. List requests use equal priority, preserving FIFO order.
- On a disk miss, JPEG requests try the capped 240-pixel embedded thumbnail, publish it immediately when available, then run the existing 240-pixel final decode and fallback. Only the final decode result is encoded/persisted. Decode/preview exceptions are treated as misses, persistence exceptions are non-fatal, and a preview is published terminally when final decoding fails.
- Disk cache hits and non-JPEG/video paths retain their existing behavior. Diagnostic events contain no media paths or names.

## TDD evidence

RED command:

```text
./gradlew test --tests 'com.multiviewer.ui.ThumbnailRequestRegistryTest' --tests 'com.multiviewer.ui.ThumbnailLoadingCoordinatorTest' --no-daemon
```

The first sandbox attempt stopped before Gradle started because it could not create the wrapper lock at `~/.gradle/.../gradle-9.2.1-bin.zip.lck` (`Operation not permitted`). With the required cache access approved, the command reached `:app:compileTestKotlin` and failed as expected: unresolved `ThumbnailLoadCoordinator` and unresolved registry `publish` references in the new tests.

Focused GREEN command (after implementation):

```text
./gradlew test --tests 'com.multiviewer.ui.ThumbnailRequestRegistryTest' --tests 'com.multiviewer.ui.ThumbnailLoadingCoordinatorTest' --no-daemon
```

Output: `BUILD SUCCESSFUL in 4s` (8 actionable tasks; 2 executed, 6 up-to-date). This run included 10 new tests across the two requested test classes. During initial GREEN iteration, an existing test exposed its call to `complete`; the compatibility adapter was retained. Three event-order assertions initially failed because their test recorder was not shared with the coordinator; the tests were corrected to observe the same event stream, and the focused run passed.

Full verification:

```text
./gradlew test --no-daemon
```

Output: `BUILD SUCCESSFUL in 26s` (8 actionable tasks; 4 executed, 4 up-to-date). The JVM printed two macOS input-method initialization notices; the suite passed.

```text
git diff --check
```

Output: no output; exit code 0.

## Self-review

- Queue priority changes affect pending entries only, so active native decode work is not interrupted. Three fixed worker loops remain the only consumers.
- Grid prefetch indices are derived from visible grid indices and the row width reported by the visible layout, then limited by `thumbnailPrefetchIndices` to the immediately following row. List work retains FIFO through a common priority.
- The coordinator publishes preview before final decode, persists only a successful final decode, does not persist a terminal preview fallback, and discards listeners when both stages fail.
- Final thumbnail edge remains 240 pixels; the pre-existing video frame route remains capped below that. Non-JPEG fallback paths are preserved.
- No Windows/FastStone speedup is claimed; no matching benchmark was available in this macOS environment. Automated tests verify behavior, not comparative performance. No Compose-window visual/manual QA was run.

## Review follow-up: list-mode FIFO reprioritization

The review found that switching to list view reprioritized already queued requests as background priority `2`, while new list requests used priority `0`. The loader now carries an explicit list-mode flag through viewport updates. In list mode, the path-priority helper assigns every queued request priority `0` before reprioritization; queue sequence remains stable, so existing requests retain FIFO order ahead of later requests. Grid mode keeps its visible/prefetch/background priorities.

Regression test: `ThumbnailPriorityQueueTest` / `list mode gives existing and new requests one FIFO priority`. It starts with differently prioritized pending work, applies list-mode priority, enqueues a later list request, then asserts all priorities are `0` and dequeue order follows enqueue order.

RED:

```text
./gradlew test --tests 'com.multiviewer.ui.ThumbnailPriorityQueueTest' --no-daemon
```

The test compilation failed as expected with unresolved `thumbnailPriorityForPath`.

Focused GREEN:

```text
./gradlew test --tests 'com.multiviewer.ui.ThumbnailPriorityQueueTest' --no-daemon
```

Output: `BUILD SUCCESSFUL in 7s` (8 actionable tasks; 4 executed, 4 up-to-date).

Full suite after the fix:

```text
./gradlew test --no-daemon
```

Output: `BUILD SUCCESSFUL in 21s` (8 actionable tasks; 1 executed, 7 up-to-date). The JVM printed the same two macOS input-method initialization notices; the suite passed.

`git diff --check` produced no output and exited `0` for this follow-up before commit.
