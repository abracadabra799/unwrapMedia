# WebP Little-Endian Parsing Bug Fix — Design

**Date:** 2026-09-12
**Status:** Approved (pending user spec review)

## Goal

Fix a real, pre-existing bug in `WebpWalker.kt` discovered while surveying WebP for
the next structure-tree detail-parsing phase: it currently misreads every WebP
chunk's size, and the RIFF container's own `file_size` field, as big-endian, when
the RIFF/WebP container format defines them as little-endian. This isn't a missing-
detail gap like the rest of this multi-phase effort — it's a correctness bug that
breaks WebP structure parsing entirely for real files, confirmed with an actual
`cwebp`-generated file: `file_size` read as `3457089544` instead of the correct
`4046`, and the first real chunk (`VP8 `) immediately triggers a false "Chunk
extends past end of file" warning with zero parsed fields, because its size was
similarly misread.

Fixing this is a prerequisite for the WebP detail-parsing phase (ICCP/XMP/ANIM/ANMF/
ALPH) that follows — none of that new work can be meaningfully tested against real
files while the chunk walker itself can't correctly step through a file's chunks.

## Root cause

`ByteReader`'s shared `readUInt16`/`readUInt32` are always big-endian (correct for
the ISOBMFF/EXIF/JPEG-heavy majority of this codebase's formats) — but RIFF-based
formats (WebP, like WAV/AVI) store their multi-byte integers little-endian.
`WebpWalker.kt` already has one correctly-written little-endian helper
(`ByteReader.readUInt24`, used for `VP8X`'s width/height) — but four other reads in
the same file still use the shared big-endian methods directly:

| Call site | Field | Current (wrong) | Correct |
|---|---|---|---|
| `WebpWalker.kt:14` | RIFF `file_size` | `reader.readUInt32(start + 4)` (BE) | LE |
| `WebpWalker.kt:24` | each chunk's declared size | `reader.readUInt32(pos + 4)` (BE) | LE |
| `WebpWalker.kt:59` | `VP8 ` width | `reader.readUInt16(payloadStart + 6)` (BE) | LE |
| `WebpWalker.kt:60` | `VP8 ` height | `reader.readUInt16(payloadStart + 8)` (BE) | LE |

`VP8X`'s width/height (via `readUInt24`) and `VP8L`'s width/height (built by hand
from individual bytes, already composed in little-endian bit order) are **not**
affected — confirmed correct both by reading the code and by the real-file test
below.

## Approach

Add `ByteReader.readUInt16LE`/`readUInt32LE` as private extension functions in
`WebpWalker.kt` itself — mirroring the file's own existing `readUInt24` convention
(a local little-endian helper living next to the format that needs it, rather than
changing the shared `ByteReader` class, which stays big-endian-only for the many
other formats that need that). Swap in the 4 identified call sites. No other logic
in the file changes.

## Testing

- `WebpWalker.kt` currently has **no test file at all** — this fix is also the
  opportunity to create `WebpWalkerTest.kt` (there is precedent for every other
  walker having one). Tests should cover: RIFF `file_size` parses correctly,
  a `VP8 ` chunk's declared size and width/height parse correctly, a `VP8X`
  chunk's width/height still parse correctly (regression guard that this fix
  didn't touch the already-correct 24-bit path), and a `VP8L` chunk's width/height
  still parse correctly (same regression guard for the hand-composed path).
- Manual verification: open a real `cwebp`-generated WebP file (or any real WebP)
  through the app and confirm the `RIFF`/`VP8 ` (or `VP8X`/`VP8L`) nodes now show
  correct values instead of the garbage/warning currently produced.
