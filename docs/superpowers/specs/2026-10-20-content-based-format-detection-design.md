# Content-based format detection (phase 1) — design

Date: 2026-10-20 · Status: implemented on `feat/content-based-format-detection`

## Problem
`AppState.openFile()` refused any file whose extension was not in the supported lists, and derived the
tab's `MediaType` from the extension. A HEIC saved as `.jpg`, a MOV saved as `.bin`, or an extension-less
dump could not be opened, and a HEIC renamed to `.mp4` would have been treated as video (rule 6 hazard).

## Decisions (agreed with the user)
1. **Scope:** the file-open paths (open dialog, main-window drag-and-drop, CLI `dump`/`check`) **plus** the
   extension branches inside an open tab. Folder-explorer listing and the Compare window stay
   extension-filtered (phase 2).
2. **Hybrid rule:** confident content detection wins; otherwise a supported extension opens the file as
   before; otherwise it is refused.
3. **Mismatch display:** ⚠ before the tab title + tooltip line, and a `Content Format` row at the top of the
   summary panel's first section. No warning when detection fell back to the extension.
4. **Approach:** one detector (`parser/FormatSniffer.kt`) used by both `parseFile()` dispatch and the open gate.

## Design
- `ContainerFormat` enum: display name, primary `MediaType`, extension family (for mismatch), and alternate
  media types chosen by extension (`.wma` ASF → audio, `.m4a` ISO → audio).
- `sniffFormat(reader)`: identical magic-byte order to the old `parseFile` chain (so parser choice for
  recognized files is unchanged), plus a new ISO BMFF branch: `ftyp` major/compatible brands → AVIF / HEIF /
  M4A / MOV / MP4; legacy QuickTime opening boxes (`moov mdat free skip wide pnot`) → MOV; anything else → null.
  `parseFile` still sends null to the box walker (only reachable for supported extensions such as `.apv`/`.ivf`).
- `isConfidentSniff`: BMP needs a valid DIB header size; MP3 needs ID3v2 or a second frame exactly where the
  first ends; ADTS needs a second frame. Other signatures are strong enough on their own.
- `confidentFormat(file)`, `contentExtension(file[, format])`, `contentMediaType(file)` helpers for features.
- `AppState.openFile`: the content check reads the file, so it runs on the tab's background task (rule 4);
  a refused file's placeholder tab is discarded via `discardOpeningTab` (shared with the resolution hard block)
  before any parsing. `TabState.detectedFormat` / `extensionMismatch` are new.
- Extension branches switched to content: motion-photo creation/menu `isHeic`, GIF filmstrip, gain map
  parser/validator, image decode RAW check, motion photo builder input, audio-integrity "audio file" check,
  CLI `check` media type, AI diagnostic prompt, Content Check format label fallback.

## Verified real-file headers
See `docs/validation/format-sniff-fixtures/generate.sh` (ffmpeg + sips; `qt  `, `isom`, `M4A `, `heic`+`mif1`,
`avif`+`mif1`, ADTS `FFF1`, MP3 `FFFB`, ID3, ASF GUID).

## Out of scope (phase 2)
Folder explorer listing by content, Compare window drag/drop & picker, Live Photo pair lookup by name,
a Content Check "extension mismatch" item.
