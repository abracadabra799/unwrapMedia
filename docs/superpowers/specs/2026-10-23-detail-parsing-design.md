# Detailed Properties parsing coverage

## Approved direction
Extend existing byte parsers and their BoxField/TableData outputs, retaining source byte ranges and Hex selection. The user approved proceeding format by format and added all identifiable SEF fields to scope. External normalized summaries would lose byte correspondence, so they are verification aids, not the source of displayed values.

## Priority
MP4 → HEIC → HEIF → JPG (including SEF) → HEVC → AVC → AVIF → MOV → APV → AAC → FLAC → MP3 → AV1 → AV2 → PNG → TIF → GIF → DNG. Shared ISO-BMFF/Exif/SEF decoder improvements apply to later formats as well. Track each as pending until actually investigated and tested.

## Investigation
DetailedPropertiesPanel renders selectedNode.fields/grid/table. Missing decoder registration falls back to LeafBoxDecoder, which has no fields. The fixture inventory confirms unregistered vmhd, smhd, btrt, fiel, sdtp, esds, sgpd and sbgp. Fragment container support also needs investigation. Existing stsc is registered via a multiline call and is not a missing decoder.

SefdBoxDecoder currently handles UTF-8/JSON, UTC timestamp, MCC, embedded MP4 and 12-byte MotionPhoto pointers. Other binary data exposes only marker and length. Audit directory/header fields and known payload layouts; proprietary unknown layouts must remain explicitly unparsed, with raw bytes accessible. Do not manufacture semantic values.

## Implementation units
1. MP4 media headers and bitrate/field-order boxes: vmhd, smhd, btrt, fiel, validated against real ffmpeg output and existing fixture bytes.
2. MP4 descriptors, dependencies, sample groups, fragment metadata; investigate and verify each layout before implementation.
3. Remaining formats in priority order; distinguish intentionally opaque compressed data from missing metadata parsing.

## Constraints
No heavy dependencies. Bound every read to the selected box and file; malformed/truncated/unknown versions surface warnings. Field offsets refer to original bytes. Do not expand compressed media payloads into millions of properties. Real fixture bytes precede parser code; keep default muxer settings. New UI text is bilingual. No release bump or push without request.
