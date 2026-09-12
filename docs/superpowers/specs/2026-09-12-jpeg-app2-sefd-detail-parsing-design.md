# JPEG APP2 & SEFD Detailed Property Parsing — Design

**Date:** 2026-09-12
**Status:** Approved (pending user spec review)

## Goal

Phase 1 of a multi-phase effort (JPEG → PNG → WebP → BMP) to fill in structure-tree
nodes that currently show only offset/name and a generic summary, with no actual
parsed field detail in the Detailed Properties panel (`DetailPropertiesTabContent`,
which renders whatever `selectedNode.fields` a decoder populated — a node with no
fields shows just Type/Offset/Size and nothing else).

This phase covers two concrete, already-identified gaps in JPEG parsing
(`JpegWalker.kt`, `SefdBoxDecoder.kt`):

1. **APP2 ICC Profile** — currently only shows an `identifier` field
   ("ICC_PROFILE") and a byte-count summary. The actual 128-byte ICC profile
   header (used by every camera/editor-embedded colour profile) is never parsed.
2. **SEFD field values** (Samsung's proprietary metadata trailer, already
   structurally parsed into named fields by `SefdBoxDecoder.kt`) — each field's
   *value* is only ever shown as a raw printable string or a bare
   `"N bytes (binary)"` fallback. Several well-documented field types (a Unix
   timestamp, a Mobile Country Code, several JSON-shaped fields) are legible data
   being shown as either an unformatted number or, in the JSON case, sometimes
   misclassified as binary because their bytes contain non-ASCII (e.g. Korean)
   text that fails the current strict-ASCII printability check.

## Non-goals (explicitly out of scope, with why)

- **ISO 21496-1 Gain Map binary metadata** (the other APP2 sub-case besides ICC
  and MPF). Its exact byte-for-byte layout (header/flags portion before the
  per-channel gain values) could not be verified with confidence from available
  public sources during research for this spec — implementing it from an unverified
  layout risks silently showing wrong gain-map values, which is worse than the
  current honest "identifier only" display. Left untouched.
- **MCC → country-name resolution.** A country lookup was considered, but a
  cross-check against a fetched Wikipedia MCC table caught a real error (450 was
  reported as North Korea; North Korea is actually 467, and 450 is South Korea) —
  that specific research pass was not reliable enough to embed in code. The SEFD
  MCC field will be clearly *labeled* (Mobile Country Code) but will show only the
  raw numeric code, no country name.
- **Samsung `SingleShotMeta` (marker `0x0b40`) and `DualShotExtra` (`0x0ab3`)**
  binary sub-fields. ExifTool's own documentation describes these only in terms of
  named sub-fields (`inputWidth`, `beautyRetouchLevel`, `DepthMapWidth`, ...)
  without confirmed byte offsets — same wrong-data risk as above.
- **Motion Photo detection/extraction** (`tryDecodeSefdTrailer`'s invocation
  condition, `MotionPhotoExtractor.kt`, the motion-photo video preview feature) is
  completely untouched. This phase only changes how an *already-selected* SEFD
  field's *value* renders in the Detailed Properties panel — not whether, when, or
  how the SEFD trailer is detected or its embedded video is extracted for preview.
- PNG, WebP, and BMP gaps (also surveyed, see below) are separate, later phases —
  each gets its own design/plan cycle.

## Background: what's actually missing

Surveyed via direct code reading (`JpegWalker.kt`, `SefdBoxDecoder.kt`) plus public
documentation for the proprietary Samsung format (ExifTool's `Samsung.pm` tag
tables, cross-referenced via
[ExifTool Samsung Tags](https://exiftool.org/TagNames/Samsung.html) and
[Hacker Factor: Reversing Samsung Metadata](https://www.hackerfactor.com/blog/index.php?/archives/1039-Reversing-Samsung-Metadata.html)).

### 1. APP2 ICC Profile

`decodeApp2`'s `ICC_PREFIX` branch (`JpegWalker.kt`) returns:
```kotlin
BoxNode(..., fields = listOf(BoxField("identifier", "ICC_PROFILE", ...)), summary = "ICC Profile (N bytes)")
```
An ICC profile embedded in JPEG can be split across multiple APP2 segments; right
after the 12-byte `"ICC_PROFILE\0"` identifier come two bytes — chunk sequence
number and total chunk count — before the actual profile bytes begin (only the
*first* chunk's payload starts with a real 128-byte header; later chunks are raw
continuation data with no header to parse).

The 128-byte ICC header itself (ICC.1 specification, unchanged since 2001 — a
stable, decades-old format, unlike ISO 21496-1) contains: profile size, CMM type
signature, profile version (major.minor.bugfix), profile/device class signature,
data colour space signature, PCS (profile connection space) signature, creation
date/time, the `"acsp"` magic signature, primary platform signature, profile flags,
device manufacturer/model signatures, device attributes, rendering intent (one of 4
named values), PCS illuminant (X/Y/Z, fixed-point), profile creator signature, and a
16-byte profile ID (MD5 checksum).

### 2. SEFD field values

`SefdBoxDecoder.decodeField` already extracts each field's embedded name (e.g.
`"UTC"`, `"MCC"`) as the child `BoxNode`'s `type`, and already special-cases exactly
two field shapes (an embedded MP4 by `ftyp` sniffing, and 12-byte
`"MotionPhoto_Data"`). Every other field falls to a generic tier:
```kotlin
if (isPrintable) BoxField("value", String(dataBytes, UTF_8), ...)
else summary = "$dataLength bytes (binary)"
```
where `isPrintable` requires **every** byte to be in the strict ASCII printable
range (`0x20..0x7E` plus tab/LF/CR).

Cross-referencing ExifTool's `Samsung.pm` trailer tag table against
`SefdBoxDecoder.kt`'s existing `directoryMarker: Int` parameter (already computed,
currently used only for a marker-mismatch sanity check) identifies concrete,
well-documented field markers whose *values* are currently under-rendered:

| Marker | Field | Current display | What it actually is |
|---|---|---|---|
| `0x0a01` | TimeStamp / UTC | Raw digit string, e.g. `1614556800` | Unix epoch seconds |
| `0x0aa1` | MCCData | Raw number | Mobile Country Code (ITU E.212) |
| `0x0ba1` | ReEditData (photo editor re-edit history) | Often `"N bytes (binary)"` | JSON (tone/effect/portrait-mode edit values) — misclassified as binary whenever it contains non-ASCII (e.g. Korean) text |
| `0x0bf0` | RemasterInfo | String or binary fallback | String/JSON |
| `0x0c51` | SamsungCaptureInfo | String or binary fallback | String/JSON |
| `0x0d91` | PEgInfo | Often binary fallback | JSON |

## Approach

### ICC Profile header parsing

In `decodeApp2`'s ICC branch: read the 2 chunk bytes right after the identifier: add
`chunk_sequence_number` / `chunk_count` fields unconditionally. When
`chunk_sequence_number == 1` and at least 128 bytes of profile data follow, parse
the ICC header into named fields (profile_size, cmm_type, version, profile_class,
data_colour_space, pcs, date_time_created, primary_platform, profile_flags,
device_manufacturer, device_model, device_attributes, rendering_intent [labeled: 0
Perceptual / 1 Media-Relative Colorimetric / 2 Saturation / 3 ICC-Absolute
Colorimetric], pcs_illuminant [X, Y, Z as fixed-point decimals], profile_creator,
profile_id [hex]). A shared small helper reads a 4-byte signature as ASCII when
every byte is printable, else as hex, and renders an all-zero signature as
`"(unspecified)"` — several of these fields are legitimately zero/absent in many
real profiles. When `chunk_sequence_number != 1`, only the chunk fields are added
(no header parse attempted) and the summary notes it's a continuation chunk.

### SEFD field value interpretation

In `SefdBoxDecoder.decodeField`, before the existing printable/binary fallback:

1. **Known-marker semantic fields** (checked first, by `directoryMarker`):
   - `0x0a01` (TimeStamp/UTC): if the raw bytes parse as a plain base-10 integer,
     add a second field with the value formatted as a human-readable UTC date
     (`java.time.Instant.ofEpochSecond`), alongside the existing raw numeric value
     — never replacing it, since the raw epoch value has its own diagnostic worth.
   - `0x0aa1` (MCC): relabel the field clearly as *Mobile Country Code*; show only
     the raw numeric value (no country name, per the Non-goals section above).
2. **Broadened text detection** (applies to any field, not marker-specific): before
   falling to `"N bytes (binary)"`, attempt a strict UTF-8 decode (rejecting
   malformed/unmappable input, not silently replacing it) and check the result has
   no disallowed control characters. This is a superset of the existing ASCII-only
   check, so it never changes what was already shown as text — it only rescues
   genuinely valid UTF-8 (e.g. Korean-language JSON values) that the narrower ASCII
   check was misclassifying as binary.
3. **JSON pretty-printing**: if the decoded text (from either tier 1's existing
   ASCII path or tier 2's new UTF-8 path) is JSON-shaped (trimmed text starts with
   `{`/`[` and ends with the matching `}`/`]`), reformat it with indentation via a
   small hand-rolled formatter that walks the text tracking `{}`/`[]` nesting depth
   while passing string-literal contents through untouched (respecting `\"`
   escapes) — no JSON parsing library needed or added, matching this codebase's
   existing hand-rolled-parsing style elsewhere. Plain non-JSON text (e.g.
   RemasterInfo/SamsungCaptureInfo when they're simple strings) still displays as
   before, just no longer at risk of the binary-misclassification bug for
   non-ASCII content.

Everything not covered by 1-3 (genuinely binary fields, and the two already-special-
cased shapes: embedded MP4, `MotionPhoto_Data`) is unchanged.

## Error handling

- Every new parse path degrades to today's existing behavior on failure: a
  timestamp that doesn't parse as an integer is shown as before (no `NumberFormatException`
  escapes); a truncated/malformed ICC header (less than 128 bytes actually present,
  or the `"acsp"` magic doesn't match at the expected offset) skips header field
  parsing and keeps just the chunk fields + existing summary, with a warning added
  rather than throwing.
- The UTF-8 decode is strict specifically so that genuinely binary data (which
  will almost always fail strict UTF-8 decoding within a few bytes) still falls
  through to the existing binary-size fallback — this must not turn truly binary
  fields into garbled "text."

## Testing

- **ICC header**: unit tests constructing a real 128-byte ICC header byte-for-byte
  (using well-known, stable constant values from the ICC.1 spec) and asserting each
  parsed field's value; a chunked (sequence 2 of N) case asserting only the chunk
  fields appear, no header fields; a truncated/malformed case asserting the
  no-crash, warning-added fallback.
- **SEFD**: unit tests for the UTC field (a known epoch value → expected date
  string, plus a non-numeric value falling back to raw display unchanged), the MCC
  field (label present, no country name emitted), the JSON pretty-printer (a
  representative nested JSON string with a Korean-language value → correctly
  reformatted, not classified as binary), and a regression test confirming a
  genuinely binary field (that previously showed `"N bytes (binary)"`) still does.
- Manual verification: open a real JPEG with an embedded ICC profile and a real
  Samsung motion-photo/edited JPEG with a SEFD trailer (if available) and confirm
  the new fields render sensibly in the Detailed Properties panel.
