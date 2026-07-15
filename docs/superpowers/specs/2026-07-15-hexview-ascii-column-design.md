# HexView ASCII Column — Design

## Background

The hex dump view (`HexView.kt`) currently shows only offset + hex bytes per row. The user asked for an adjacent ASCII representation, matching the classic hex-editor layout (010 Editor, HxD): `offset | hex bytes | ASCII`.

## Goal

Add an ASCII column to `HexView`, in the same row as the offset and hex bytes, so a user can read printable text embedded in the file (e.g. `ftyp` brand strings, handler names, iTunes-style metadata) without mentally decoding hex.

## Non-Goals

- No separate/detachable ASCII panel — it's part of the same row, same component.
- No configurable non-printable glyph — always `.`.
- No new tests — this project's UI layer has no automated tests by design (parser is unit-tested exhaustively; UI is verified by manual/process-level checks, per the original design spec). This change follows the same approach: compile + manual run-through.

## Design

**File changed:** `app/src/main/kotlin/com/multiviewer/ui/HexView.kt` (only file touched).

**Per-row rendering**, for each of the `BYTES_PER_ROW` (16) byte slots:
1. Offset: unchanged, `%08X` format.
2. Hex bytes: unchanged for existing content, but the last row (which may have fewer than 16 bytes) is now padded with 3 spaces per missing slot (`"   "`) so the ASCII column starts at the same horizontal position on every row, including the final partial row. This is a small existing-code fix bundled into this change — the current code doesn't pad the last row, so the ASCII column added here would otherwise be misaligned exactly on the file's last row.
3. ASCII column: for each byte in the row (only the bytes that actually exist — no padding needed here since it's the last thing on the line), render:
   - The literal character if the byte is in the printable ASCII range `0x20`–`0x7E`.
   - `.` otherwise.
4. Highlighting: the existing `highlightRange` (driven by tree selection, per-byte-offset) applies to both the hex byte and its corresponding ASCII character — same yellow `SpanStyle` background, same condition (`highlightRange?.contains(byteOffset) == true`), applied twice (once when appending the hex text, once when appending the ASCII char) for the same offset.

**Data flow:** unchanged. `HexView` still opens its own `RandomAccessFile` per file (independent of the parser, per the established parser/UI I/O boundary) and reads one row's bytes at a time inside the `LazyColumn`'s `items` block — the ASCII column is derived from the same already-read `buf: ByteArray`, no additional I/O.

## Testing / Verification

Compile (`./gradlew compileKotlin`), then run (`./gradlew run`) and manually confirm:
- ASCII column appears to the right of the hex bytes, aligned across all rows including the last partial row.
- Printable bytes show their character; non-printable bytes show `.`.
- Selecting a tree node highlights both the hex bytes and the matching ASCII characters in yellow.
