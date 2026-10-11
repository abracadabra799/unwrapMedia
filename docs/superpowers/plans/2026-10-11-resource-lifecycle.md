# Resource lifecycle and bounded memory implementation plan

> For agentic workers: implement inline using executing-plans and test-driven-development. The user has authorized fixes after a read-only audit.

**Goal:** Stop abandoned media work and child processes, and bound retained decoded images and diagnostic output.

**Architecture:** Keep UI behavior and existing public entry points. Fix process ownership centrally, give tab work cancellable ownership, and use byte budgets for decoded images. Avoid unrelated UI restructuring.

**Tech stack:** Kotlin/JVM 21, Compose Desktop, kotlinx.coroutines, Skia, JUnit 5.

**Spec:** Audit in this conversation: parent termination leaves children alive; silent ffprobe blocks tab cancellation; GIF frames and filmstrip thumbnails lack byte budgets; background tasks retain closed tabs; motion analysis detaches from caller cancellation; AVM diagnostics are unbounded.

## Global constraints

- Do not change CLI/report schemas or media decoding semantics.
- Preserve user changes. Do not push as part of this request.
- Tests clean only their own child processes and temporary resources.
- Limit each GIF animation to 32 MiB of retained pixels; at least one frame must fit or decoding returns null. Keep the existing 500-frame cap too.
- Limit each open tab's filmstrip thumbnails to 8 MiB and 400 entries, prioritizing recently visible frames.
- Preserve AVM diagnostic tails within 64 KiB, marking truncation.

## Review focus

- Parent exits before child: both must terminate.
- ffprobe produces no output: cancellation must still terminate it.
- Closing a tab with running and queued work: queued work must not run; running work must receive interruption.
- Large GIF dimensions/many frames: memory budget must be applied before allocation.
- Decoder output flood/callback failure: retain bounded diagnostics and stop failed work.

### Task 1: Process ownership and cancellation

Files: `util/ProcessManager.kt`, `ui/FrameTypeAnalyzer.kt`, process tests.

- [x] Add real process-tree and silent-output cancellation regression tests; observe failures.
- [x] Capture descendants before parent termination, retain tracking through cleanup, and bound cleanup waits.
- [x] Add cancellation monitoring independent of stdout reads and propagate coroutine cancellation.
- [x] Verify process tests and record results.

### Task 2: Tab and inspection task ownership

Files: `ui/BackgroundTask.kt`, `ui/AppState.kt`, `ui/ContentCheckWindow.kt`, `ui/DetachedBlocking.kt`, `ui/ProcessOutputReader.kt` and tests.

- [x] Add running/queued task cancellation and interruptible blocking regressions; observe failures.
- [x] Own futures per tab; cancel/remove queued tasks on close and suppress stale UI callbacks.
- [x] Replace detached motion inspection with interruptible work; preserve interruption while stopping child processes.
- [x] Verify lifecycle and content inspection tests.

### Task 3: Bounded decoded image retention

Files: `parser/GifFrameDecoder.kt`, `ui/FrameThumbnailFilmstrip.kt`, `ui/FrameThumbnailDecoder.kt`, bounded cache helper and tests.

- [x] Add GIF byte-budget and thumbnail eviction regressions; observe failures.
- [x] Apply GIF pixel budget before allocation, close temporary native objects, honor interruption.
- [x] Bound thumbnail entries/bytes, expose cancellable requests and clear failed pending ranges.
- [x] Protect visible thumbnails against delayed offscreen cache insertions.
- [x] Verify image/cache tests and preserve playback/selection behavior.

### Task 4: AVM diagnostics and frame analysis allocation

Files: `ui/AvmProcessRunner.kt`, `ui/FrameTypeAnalyzer.kt`, relevant tests.

- [x] Add bounded diagnostic tail and callback failure regressions; observe failures.
- [x] Drain stderr in fixed-size chunks so a newline-free stream cannot allocate an unbounded line.
- [x] Route AVM cancellation through process-tree cleanup and stop on failed consumers.
- [x] Reduce full-frame-list progress snapshot frequency with geometric publication intervals.
- [x] Run targeted tests, full suite, review diff, and report any remaining limitations.

## Progress

- Audit: 19 existing tests passed. Two regressions reproduced with the compiled application code. Working tree clean at cc5c258 before changes.
- Implementation: process ownership/cancellation, tab task lifecycle, bounded GIF and thumbnail retention, interruptible motion analysis, and bounded AVM diagnostics completed.
- Follow-up review findings (newline-free stderr allocation, AVM descendant cancellation, visible thumbnail eviction) were reproduced and fixed with regression coverage.
- Verification: `./gradlew :app:test` succeeded; `git diff --check` succeeded. Changes remain uncommitted and unpushed on `main`.

## Follow-up: viewport ownership and repeated lifecycle checks

- Corrected the earlier thumbnail fix: protection now belongs to the cache's latest viewport, not the viewport captured by a request callback. Leaving the filmstrip or clearing the cache releases protection.
- Strengthened cache regressions with multiple delayed insertions and a viewport change. Temporarily disabling protection made both regressions fail; restored the implementation before final verification.
- Added real image open/close repetition (20 measured cycles after warm-up) and silent ffprobe cancellation through `AppState.closeTab` (10 cycles).
- Final full run: 1,416 tests, 0 failures/errors/skips. Closed tab references after GC: 0. Surviving probe processes: 0. Tracked processes: 0.
- JVM heap sample in the full run: 16,050,400 bytes before and 16,041,432 bytes after image cycles and GC. This is a short automated lifecycle check, not a long-running GUI or native-memory/RSS profile.
