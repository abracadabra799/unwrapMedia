# Image Integrity Check Design

## Goal

Add an **Analysis → Image Integrity…** check for every supported image format (JPEG, PNG, HEIC/HEIF/AVIF, WebP, GIF, BMP, TIFF, and the TIFF-based RAW formats CR2/NEF/ARW/DNG). It combines two independent verdicts:

1. **Structure verification** — format-specific, byte-level checks that point at an exact offset (e.g. PNG chunk CRC mismatch, JPEG missing EOI, HEIF `iloc` extent past end of file).
2. **Decode verification** — a real FFmpeg software decode with error-log capture, plus a declared-vs-decoded resolution cross-check.

It mirrors the (separately developed, not yet committed) Video Integrity feature: its own window with Start / Cancel / Save analysis case, and a CLI path `unwrapMedia check <image> --decode`.

## Non-goals

- No repair or rewriting of files.
- No RAW sensor-data decode (FFmpeg/Skia cannot). RAW decode verification uses the largest embedded JPEG preview instead, and says so.
- No merging of the two verdicts into a single score — they are shown side by side so "structure OK but decode failed" (or the reverse) is never hidden.
- No changes to the Video Integrity files. Shared code (process runner, menu, CLI, I18n) is reconciled when both branches land.

## Approach

Hybrid: the existing `parseFile` `BoxNode` tree is used as the map of *where* to look; verification itself re-reads the original bytes through `ByteReader` (CRC computation, scan-data end search, extent range comparisons). Existing parser warnings in the tree are surfaced as a "Parser warnings" check item rather than re-implemented. If the tree is too incomplete for a checker to run, that checker reports FAIL ("structure could not be interpreted") and the remaining checks still run.

## Data model (`parser/integrity/ImageIntegrityModels.kt`, UI-independent)

```kotlin
enum class CheckStatus { PASS, INFO, WARN, FAIL, SKIP }   // severity order for aggregation: FAIL > WARN > INFO/PASS; SKIP ignored

data class IntegrityCheckItem(
    val id: String,          // stable, e.g. "png.crc", "jpeg.eoi"
    val title: String,       // English; UI localizes via id where a translation exists
    val status: CheckStatus,
    val detail: String,      // evidence: expected vs found values
    val offset: Long? = null,
    val length: Long? = null,
)

data class ImageStructureReport(
    val format: String,              // "JPEG", "PNG", "HEIF", ...
    val items: List<IntegrityCheckItem>,
) { val overall: CheckStatus }       // worst non-SKIP item; PASS if all SKIP/PASS/INFO
```

`INFO` is used for recognized, legitimate extra data (Motion Photo video, Samsung SEF trailer, MPF secondary images after EOI) so it is visible but never reads as a defect.

## Per-format checks (`parser/integrity/`)

| Checker | Checks |
|---|---|
| `JpegIntegrity` | SOI at 0; at least one SOF and SOS; SOF before SOS; DQT/DHT present before first SOS (DHT optional only for arithmetic coding / MJPEG-style files — WARN); entropy-coded data after last SOS reaches an EOI (missing EOI → FAIL, offset = end of scan data); bytes after EOI: recognized trailer → INFO, unknown → WARN |
| `PngIntegrity` | 8-byte signature; IHDR is first chunk with length 13; CRC32 of every chunk (critical chunk mismatch → FAIL, ancillary → WARN); at least one IDAT and IDATs contiguous; IEND present and last; bytes after IEND → WARN |
| `HeifIntegrity` (HEIC/HEIF/AVIF) | `ftyp` first with a known brand; `meta` with `hdlr=pict`, `pitm`, `iinf`, `iloc`; primary item exists in `iinf`; every `iloc` extent (construction method 0) lies within the file, method 1 within `idat`; grid items: referenced tile items (`dimg`) exist and count = rows × columns; `mdat` declared size ≤ file |
| `WebpIntegrity` | RIFF size + 8 = file size (smaller → WARN for trailing bytes, larger → FAIL truncated); each chunk fits and is padded to even length; exactly one of VP8/VP8L image data (or ANIM/ANMF frames under VP8X); VP8X canvas size vs bitstream size |
| `GifIntegrity` | `GIF87a`/`GIF89a` header; logical screen descriptor + global color table fit; at least one image descriptor; every image's LZW sub-block chain terminates; trailer `0x3B` present (missing → FAIL); bytes after trailer → WARN |
| `BmpIntegrity` | `BM` signature; `bfSize` vs file size; `bfOffBits` within file; pixel array size ≥ row-stride × |height| for uncompressed BI_RGB/BITFIELDS (short → FAIL) |
| `TiffIntegrity` (TIFF, CR2, NEF, ARW, DNG) | byte-order mark + magic 42; every IFD offset (IFD chain, SubIFDs, Exif IFD) within file and no IFD visited twice (loop → FAIL); StripOffsets/StripByteCounts (and Tile*) counts equal and every range within file; JPEGInterchangeFormat/Length range within file |

Common to all: a "Parser warnings" item aggregating the tree's existing `warnings` (each with its offset) as WARN. Simple chunked formats (PNG, GIF, BMP, WebP, TIFF) are re-walked directly from bytes, because the checks need raw values (CRCs, LE sizes, IFD offsets) that the tree only exposes as display strings; JPEG and HEIF use the tree.

`ImageIntegrityChecker.check(file, root, reader): ImageStructureReport` dispatches by detected format (tree root types / magic), not by extension alone.

## Decode verification (`ui/ImageDecodeCheck.kt`)

- Primary decoder command: `ffmpeg -nostdin -hide_banner -v error -i <file> -frames:v 1 -an -sn -dn -f framecrc -` (animated GIF/WebP: all frames, no `-frames:v`). `framecrc` output gives the decoded frame count (one line per frame) and the decoded size (`#dimensions 0: WxH`, already rotated and grid-stitched — verified on a real 40-tile HEIC: `2252x4000`). HEIC/AVIF go through FFmpeg's HEIF demuxer; the FFmpeg version is reported in the result.
- Secondary decoder: Skia (`org.jetbrains.skia.Codec.readPixels`) for JPEG, PNG, GIF, WebP and BMP. Measured on 2026-10-10: a truncated JPEG makes FFmpeg print only `overread 8` with exit code 0, while Skia throws `Incomplete input`; Skia's verdict is therefore recorded separately and an exception counts as an issue. Skia does not decode HEIF/TIFF/RAW → `SKIP`.
- Correction (2026-10-10 manual verification): the earlier "HEIC truncated to 1/3 decodes cleanly" measurement was wrong — that Samsung HEIC stores its image `mdat` at the front, so the cut only removed the trailing motion-photo `mpvd` video (reported as a parser WARN). Truncating inside the image data makes FFmpeg fail *and* `heif.iloc`/`heif.meta` FAIL. The side-by-side verdicts remain because FFmpeg does under-report truncated JPEGs (`overread 8`, exit 0).
- RAW: extract the largest embedded JPEG preview (via the TIFF tree) and pipe its bytes to `ffmpeg -i pipe:0`; the result is labelled "embedded preview decoded, sensor data not verified".
- Status (same names as Video Integrity): `NOT_RUN`, `CLEAN`, `ISSUES`, `FAILED`. **CLEAN requires exit code 0 AND no error output AND ≥ 1 decoded frame** (exit code alone has been shown in this project to miss concealed decode errors).
- Overall decode status = worst of FFmpeg and Skia (Skia `SKIP` is ignored; Skia exception → `ISSUES`).
- Resolution cross-check: FFmpeg `#dimensions` vs the header-declared size (JPEG SOF, PNG IHDR, HEIF `ispe` of the primary/grid output, WebP, GIF LSD, BMP, TIFF ImageWidth/Length); mismatch → WARN item in the decode tab. A width/height swap counts as a match (FFmpeg applies EXIF/`irot` rotation).
- Limits: 2-minute timeout per process, cancellation kills the child process, at most 500 log lines (truncation recorded). The process runner follows `ProcessManager`/`FfmpegLocator.configureEnvironment` like the other FFmpeg callers; it is a local helper on this branch and is deduplicated against Video Integrity's `integrityProcess` after both branches merge.
- FFmpeg not found / failed to start → `FAILED` with the reason; structure results remain valid.

## UI (`ui/ImageIntegrityWindow.kt`)

- Menu: **Analysis → Image Integrity…** (`이미지 무결성 검사…`), enabled when the active tab is an image. Opens a window bound to that tab (closing/switching tabs does not retarget it).
- Header: file name, format, overall structure verdict, decode verdict.
- Buttons: Start inspection, Cancel, Save analysis case (JSON, never overwrites an existing file).
- Structure verification runs automatically on open (cheap). Decode verification runs on Start.
- Tab 1 — **Structure**: table of check items (status chip, title, offset, length, detail). Clicking a row with an offset highlights that byte range in the main Hex view (`tab.parameterSetHighlightRange`, same mechanism Video Integrity uses).
- Tab 2 — **Decode**: overall decode status, FFmpeg result (frames, version, log), Skia result, resolution comparison.
- Uses `androidx.compose.material3.Text` throughout (Material2 `Text` is invisible on this app's theme).

## CLI

`unwrapMedia check <image> --decode` adds an `imageIntegrity` object to the JSON (`structure` with items and overall, `decode` with status/log/resolution). Without `--decode` the structure report is still included and decode is `NOT_RUN`. `--case` embeds the same object in the analysis case. These touch `CheckFile.kt`/`CheckCommand.kt`, which the Video Integrity work also modifies — reconcile at merge.

## Error handling

- Checker exceptions are caught per checker → FAIL item with the exception message; other checkers continue.
- All byte reads are bounds-checked against file length; CRC and scan-data searches stream through `ByteReader`, never loading the whole file.
- Unknown/unsupported format detected → single SKIP item, decode still available.

## Testing

- Unit tests per checker using bytes synthesized in the test: valid baseline → all PASS; then targeted corruption (flip a CRC bit, truncate before EOI/IEND/trailer, inflate RIFF size, push an `iloc` extent past EOF, create an IFD loop, shorten BMP pixel array) → the expected item FAILs/WARNs with the right offset.
- Recognized trailer: JPEG + appended Motion Photo bytes → INFO, not WARN.
- Decode status function tested as pure logic (exit code / log / frame count matrix).
- FFmpeg integration tests (skipped when FFmpeg is unavailable): valid JPEG/PNG → CLEAN; truncated JPEG → ISSUES or FAILED, never CLEAN.
- Manual verification against real corrupted files of each major format before declaring done.
