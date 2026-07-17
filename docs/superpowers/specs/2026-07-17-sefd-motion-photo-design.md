# Samsung SEFD / Motion Photo Parsing — Design

## Background

Samsung HEIC files (and, in an older JPEG-trailer form not covered here, JPEG/PNG files) carry a proprietary `sefd` top-level box holding editor/camera metadata and Motion Photo bookkeeping. A companion top-level box, `mpvd`, holds the full-resolution embedded Motion Photo video as a complete nested MP4 file. Neither box currently has a decoder, so selecting either in the tree shows nothing.

This design is based on empirical analysis of a real sample file (`~/Downloads/20260715_223828.heic`), not just secondhand documentation — every byte offset and field described below was independently verified against that file's actual bytes (see the conversation's analysis for the full walkthrough). The community-documented format (exiftool's `Samsung.pm`, the `doodspav/motionphoto` project) describes an older JPEG-trailer variant with similar but not identical framing; this design targets the verified HEIC-box variant only.

## Goal

1. Recognize the `mpvd` top-level box and expose its embedded MP4 as a normal nested box tree.
2. Parse the `sefd` top-level box's internal field directory, exposing every field (camera/editor metadata, Motion Photo bookkeeping) as its own selectable tree node with offset/size/name/value.
3. For the `MotionPhoto_Data` field specifically, decode its pointer structure (which locates the `mpvd` video) into named fields.
4. For any `sefd` field whose data is itself a complete embedded MP4 (e.g. `MotionPhoto_AutoPlay`, the short auto-play preview clip), expose it as a nested box tree too, the same way `mpvd` is handled.

## Non-Goals

- The older JPEG/PNG trailer form of this format (raw bytes appended after a JPEG, found by scanning from EOF rather than being a proper ISOBMFF box) — this tool doesn't open raw JPEG files at all today, and adding JPEG file support is out of scope for this feature.
- A lookup table mapping every known `marker` value to a friendly description. The `name` string already present in every field (e.g. `"MotionPhoto_Data"`, `"Image_UTC_Data"`) is the human-readable identifier; the numeric `marker` is shown alongside it as supplementary raw data, not specially interpreted, except for `MotionPhoto_Data` itself (Goal 3).
- Humanizing `Image_UTC_Data`'s millisecond-epoch timestamp into a calendar date/time — shown as the raw numeric string, matching how this project has generally kept scope to structural parsing over semantic enrichment.
- Following the `MotionPhoto_Data` pointer to auto-select or auto-scroll to the `mpvd` node — the decoded offset/length are displayed as plain numbers; the user cross-references manually (the numbers are absolute file offsets, directly comparable to the hex view's own offset column and to `mpvd`'s own Offset/Size shown in its metadata block).
- `iloc` remains out of scope, per the existing box-detail-parsing feature's established non-goal.

## Design

### 1. `mpvd` registration (`Decoders.kt`)

`mpvd`'s payload is, byte-for-byte, a sequence of ordinary top-level ISOBMFF boxes (`ftyp`, `mdat`, etc. — verified: the payload begins directly with a valid `ftyp` box). This is exactly what the existing `ContainerBoxDecoder()` (default, zero `childOffsetInPayload`) already does — no new decoder class needed:

```kotlin
BoxRegistry.register("mpvd", ContainerBoxDecoder())
```

### 2. `sefd` field-directory format (verified against the sample file)

Within the `sefd` box's payload:

**Field blocks** (variable-length, packed sequentially starting at payload offset 0):
```
reserved:  2 bytes, always 0x0000
marker:    2 bytes, little-endian uint16
name_size: 4 bytes, little-endian uint32
name:      name_size bytes, ASCII
data:      remaining bytes of this block (length determined by the directory entry's size, below — not self-terminating)
```

**Trailer** (a fixed-shape index over the field blocks, located at the end of the `sefd` payload):
```
["SEFH"]                    4 bytes, magic
[version]                   4 bytes, little-endian uint32
[count]                     4 bytes, little-endian uint32 — number of directory entries
[count × 12-byte entries]:
    reserved: 2 bytes, always 0x0000
    marker:   2 bytes, little-endian uint16 (must match the referenced field block's own marker)
    offset:   4 bytes, little-endian uint32 — see resolution formula below
    size:     4 bytes, little-endian uint32 — total size of the referenced field block, header included
[sef_size]                  4 bytes, little-endian uint32 — byte length of everything from "SEFH" through the last directory entry (i.e. excluding this sef_size field and the trailing "SEFT")
["SEFT"]                    4 bytes, magic
```

**Locating the trailer:** scan from the end of the `sefd` payload — the last 4 bytes must be `"SEFT"`; the 4 bytes before that are `sef_size`; the position of `"SEFH"` is `(payloadEnd - 8) - sef_size`.

**Resolving each entry's field block position:** `absoluteBlockStart = sefhPosition - entry.offset` (an absolute position within the `sefd` payload — the offset is a backward distance from the start of the `SEFH` magic, verified exactly against all 12 entries in the sample file, including the largest 834,454-byte entry and the smallest 19-byte entry).

### 3. `SefdBoxDecoder` (`app/src/main/kotlin/com/multiviewer/parser/SefdBoxDecoder.kt`)

Produces one child `BoxNode` per directory entry (in directory order), each representing one field block, with **absolute file offsets** (consistent with every other decoder and with the hex view's own offset column):

- `type` = the field's `name` string (e.g. `"MotionPhoto_Data"`) — read naturally in the tree, unlike a raw 4-char fourcc, but `BoxNode.type` has no length constraint and the tree/detail-panel code already treats it as an opaque label.
- `offset` = absolute file position of the field block's own `reserved` byte (i.e. `sefdPayloadStart + relativeBlockStart`).
- `headerSize` = `8 + name_size` (the field block's own local header: reserved + marker + name_size + name).
- `size` = the directory entry's `size` (total, header included — matches the project-wide `size`/`headerSize`/payload convention already surfaced by the always-shown metadata block from the box-detail-parsing feature).
- `fields` = `[BoxField("marker", "0x" + hex, ...)]` plus a value field (see below), UNLESS this field's data is a nested MP4 (see below), in which case `children` is populated instead and no value field is added.
- If the directory entry's `marker` doesn't match the field block's own embedded `marker`, add a warning (`"Field \"$name\": directory marker 0x... does not match block marker 0x..."`) — the established defensive-warning convention.

**Value decoding, per field, in this priority order:**

1. **Nested embedded MP4** (covers `MotionPhoto_AutoPlay` in the sample, and generically any future field shaped the same way): if the data's bytes at relative offset 4–7 spell `"ftyp"` (the same signal a real MP4/HEIC file's own first box uses), treat the data as a self-contained nested ISOBMFF stream: `children = parseBoxes(reader, dataStart, dataEnd)`, `summary = "${dataEnd - dataStart} bytes, embedded MP4"`. This heuristic is checked by marker/name-independent content sniffing, not a hardcoded name match, so it generalizes to any similarly-shaped field.
2. **`MotionPhoto_Data` pointer** (name-matched specifically, since this is the one field this design assigns real structural meaning beyond generic display): data is exactly 12 bytes — `format_tag` (4 bytes ASCII, e.g. `"mpv2"`), `video_offset` (4 bytes, **big-endian** uint32 — verified: this is an absolute file offset, distinct from the little-endian used everywhere else in this format), `video_length` (4 bytes, big-endian uint32). Exposed as three `BoxField`s; `summary = "offset=$videoOffset, length=$videoLength"`.
3. **Fallback plain value**: if every byte of `data` is printable ASCII (0x20–0x7E) or common whitespace, decode as a UTF-8 string and expose as a single `BoxField("value", ...)`; otherwise expose no value field and set `summary = "${data.size} bytes (binary)"`.

### 4. Testing

Unit tests for `SefdBoxDecoder` construct synthetic `sefd` payloads by hand (following the existing test convention — `byteReaderOf`, explicit `byteArrayOf`), covering: a small text field, the `MotionPhoto_Data` pointer decode, an embedded-MP4-shaped field recursing into its own children, and the marker-mismatch warning. `mpvd`'s registration needs no dedicated test beyond a registry-wiring assertion (`ContainerBoxDecoder` itself is already tested) — this is exactly the kind of registration-only change the prior feature's `DecodersRegistrationTest` guards against silently regressing; `"mpvd"` and `"sefd"` are added to that test's list of must-not-be-`LeafBoxDecoder` types.

Manual verification: open the real sample file, confirm `mpvd` and `sefd` both show children, confirm `sefd`'s 12 fields match the table gathered during analysis (names, sizes, and the `MotionPhoto_Data` pointer's decoded offset/length matching `mpvd`'s own Offset/Payload-size), and confirm `MotionPhoto_AutoPlay` and `mpvd` both recurse into a normal-looking nested MP4 box tree (`ftyp`/`mdat`/etc.).
