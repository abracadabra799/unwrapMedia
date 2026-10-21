# AGENTS.md — unwrapMedia (multiViewer)

Guidance for coding agents (Codex, Claude Code, …) working in this repository.
**Current work in progress and next steps: read `docs/superpowers/HANDOFF.md` first.**

## What this is

unwrapMedia: a Kotlin Compose Desktop (Material3) media viewer / forensic analysis workbench —
container structure tree + hex viewer + metadata + playback for images, video and audio
(JPEG/PNG/GIF/WebP/HEIC/AVIF/BMP/TIFF/RAW, MP4/MOV/WebM/AVI/…, H.264/HEVC/AV1/AV2/APV, WAV/MP3/FLAC/…),
plus analysis windows (Content Check, A/V sync, quality metrics, HDR/SEI metadata, SEF, motion photo).
The user is a Galaxy multimedia framework / image & video codec / motion-photo engineer.

## Build and test

- JDK 21. `./gradlew :app:run` (run app), `./gradlew :app:test` (all tests), `./gradlew :app:compileKotlin`.
- Focused test: `./gradlew :app:test --tests 'com.multiviewer.parser.SomeTest'`.
- `com.multiviewer.cache.ThumbnailDiskCacheTest` is a known pre-existing **flaky** test (fails occasionally in
  the full suite, passes alone). Any other failure is real.
- Sources: `app/src/main/kotlin/com/multiviewer/{parser,ui,cli,cache,update,util}`; tests mirror them under
  `app/src/test/kotlin/...`; real-file fixtures under `app/src/test/resources/<feature>/` with provenance +
  regeneration scripts under `docs/validation/<feature>-fixtures/`.
- Test helpers (package `com.multiviewer.parser`): `hex(String)`, `TestBitWriter` (TestBitWriter.kt),
  `byteReaderOf(ByteArray)`, `fileOf(ByteArray)` (TestSupport.kt), `parseFile(File)`, `findFirst(BoxNode, pred)`.

## Workflow the user expects

Every feature: brainstorm (clarifying questions, approaches, design approval) → design spec
`docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md` → implementation plan
`docs/superpowers/plans/YYYY-MM-DD-<topic>.md` (bite-sized TDD tasks with full code) → implement task by task on a
feature branch (git worktree under `.worktrees/<name>`, which is git-ignored) with a review after each task →
whole-branch review → merge to `main` (fast-forward) → user pushes. Communicate with the user in Korean.
Commit messages: conventional style (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).

## Hard-won rules (each came from a real bug)

1. **Verify against real files before writing parsers.** Generate fixtures with real tools (ffmpeg, x265,
   x264, SVT-AV1, c2patool …) and dump the bytes independently; put the verified byte layouts in the plan.
   Normalising tools lie: ffprobe repeats the previous frame's HDR10+ on frames without it; c2patool rewrites
   assertion labels. Assert raw bytes/fields.
2. **Keep a default-settings fixture.** ffmpeg's default HEVC MP4 tag is `hev1` (not `hvc1`); a feature whose
   fixtures all used `-tag:v hvc1` was silently unavailable on default files.
3. **Material3 only:** always import `androidx.compose.material3.Text` — Material2 `Text` renders invisible on
   this app's theme.
4. **Windows matters** (development happens on macOS): no unescaped paths in ffmpeg filter strings; no blocking
   file I/O on the UI thread (Windows file locks, e.g. a CSV open in Excel); write user-facing CSV with a UTF-8 BOM.
5. **External processes:** never `readLine()/readLines()` a process before an enforced timeout — use
   `readProcessOutputWithTimeout` (`ProcessOutputReader.kt`). Closing a window must cancel its work.
6. **Gate per-frame/sample reads** on both `tab.type == MediaType.VIDEO` and the codec config box — HEIC motion
   photos also contain `hvcC`/embedded `moov`, and reading their samples at video offsets is wrong. Analysis
   windows must be pinned to the tab they were opened for (see `hdrMetadataTab` in `Main.kt`).
7. **A validation tool must never show a false PASS:** empty/truncated/unsupported input must surface as a
   warning, not PASS.
8. **Version bump = 3 places:** `app/build.gradle.kts` `packageVersion`, `ui/I18n.kt` `APP_VERSION` (what the
   UI shows and the update checker compares), and `version.json` (update manifest: version, releaseDate,
   releaseNotes with `\n`-separated "• " lines). Current: 2.0.0.
9. **Structural highlighting is tree-driven:** drive hex/image highlights from tree selection
   (`tab.selected`, `tab.parameterSetHighlightRange: LongRange`), not new always-on overlays or click gestures.
10. No new heavy dependencies; the codebase hand-writes CBOR/CSV/JSON handling on purpose.
11. User-visible strings are bilingual (KO/EN) via `I18n` or a local `label(korean, english)` helper.

## Remotes

`origin` = github.com/abracadabra799/unwrapMedia (main tracks it). `internal` = github.sec.samsung.net (the
`version.json` download URLs point there; it may be unreachable from some networks). Never push without the
user's go-ahead.
