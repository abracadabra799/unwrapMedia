# Detailed Properties Implementation Plan

**Goal:** Fill verified metadata parsing gaps in the user's format priority order.
**Architecture:** Register small bounded BoxDecoder implementations and reuse DetailedPropertiesPanel's field rendering and Hex linkage.
**Spec:** ../specs/2026-10-23-detail-parsing-design.md

## Task 1 — MP4 basic media headers
Files: BasicMediaBoxDecoder.kt, Decoders.kt, BasicMediaBoxDecoderTest.kt.
- [x] Generate default AVC/AAC MP4 with ffmpeg and independently dump raw bytes (docs/validation/detail-parsing).
- [x] Write failing tests through registry for real vmhd/smhd/btrt/fiel, signed balance, nonzero fields, all truncated prefixes and unsupported versions.
- [x] Implement version-0 vmhd (FullBox + graphicsmode u16 + opcolor[3] u16), smhd (FullBox + signed 8.8 balance + reserved u16), btrt (three u32: bufferSizeDB,maxBitrate,avgBitrate), fiel (two u8: field_count,field_order). Every field records absolute offset/length.
- [x] Run focused tests, then required parser/full suite gates. Review and commit.

Verified payloads and offsets are in docs/validation/detail-parsing/verified-bytes.txt. Example vmhd: 00000001 0000 0000 0000 0000. smhd: 00000000 0000 0000. Audio btrt: 00000000 00011952 00011952 (72018 bit/s). fiel in existing hdr/av1_hdr.mp4: 0100.

## Subsequent units (pending investigation and detailed plans)
MP4 descriptors/dependencies/groups/fragments; HEIC/HEIF; JPG + SEF; HEVC; AVC; AVIF; MOV; APV; AAC; FLAC; MP3; AV1; AV2; PNG; TIFF; GIF; DNG.

## Review focus
Truncation at every field, unsupported FullBox versions, signed fixed point, UInt32 values above Int.MAX_VALUE, field offsets within node, default muxer fixture, no reads into following box.

Task 1 evidence: 6 new tests failed before registration and passed after implementation; full :app:test passed; independent review found no issues.

## Task 2 — ES descriptors
- [x] Registry-driven tests for actual default AAC esds, compact TLV lengths, optional ES flags, overlong/unterminated length, truncation, non-AAC config, unknown version.
- [x] Add bounded EsdsBoxDecoder: FullBox v0, MPEG-4 TLV lengths up to four bytes; tags 03 (ES_ID, priority and optional dependency/URL/OCR), 04 (objectTypeIndication, streamType, upStream, bufferSizeDB, bitrates), 05 (decoderSpecificInfo), 06 (predefined SL config). Retain unknown descriptors as bounded raw fields. Limit nesting and total descriptor count.
- [x] For verified MPEG-4 audio object type 0x40 / audio stream, reuse existing parseAacConfig for derived AAC fields, anchored to the original DecoderSpecificInfo byte range. Propagate its limitations.
- [x] Validate against saved raw esds bytes, existing AAC scanner tests, full suite and review; commit.

Verified default fixture: ES_ID=2; objectTypeIndication=0x40; streamType=5; bufferSizeDB=0; max/avg bitrate=72018; decoderSpecificInfo=118856e500; audioObjectType=2, samplingFrequencyIndex=3, sampleRate=48000, channelConfiguration=1. Four-byte descriptor length encoding is present in the default fixture.

## Task 3 — MP4 counted headers and sample groups
- Add version/flags/entry_count to stsd and dref while retaining existing child nodes; validate count and short headers.
- Decode sbgp v0/v1 grouping_type, grouping_type_parameter (v1), entry_count and lazy sample_count/group_description_index table.
- Real default fixture sbgp at 1409: v0, roll, one entry, sample_count=20, group_description_index=1 (verified-bytes.txt).
- Tests: real fixture fields, v1 parameter, truncated table clamping and unsupported version. No eager expansion of sample tables.

Tasks 2/3 validation: new registry tests failed before implementation and passed after; AAC extension regression failed then passed after separating display notices from structural warnings. Full :app:test passed (2026-10-24); independent review found no remaining issues.

## Tasks 4/5 — sample dependencies and fragment headers
Implemented sdtp bitfields, sgpd roll/prol signed recovery distances, mfhd/trex/tfhd/tfdt scalar fields and lazy trun rows, plus fragment container traversal. See 2026-10-24-sample-details.md and 2026-10-24-fragment-details.md for evidence. Fragment playback indexing, other group descriptions, HEIC/HEIF and subsequent formats remain pending.

## Tasks 6/7 — HEIF properties and SEF structural details
Completed pixi/auxC version/flags, channel count, raw subtype with explicit display limits; SEFH header/version/count, trailer, directory and field header/name/length display, unknown binary preview/status, and SEF bounds checks. Verified actual S26 HEIC/JPG layouts with independent scripts and regression tests. See 2026-10-24-heif-property-details.md and 2026-10-24-sef-details.md.

Remaining: infe v0/v1 and missing MIME content_encoding/URI fields, broader HEIF property inventory; proprietary SEF binary payload semantics (only known layouts may be decoded), existing SEF large-name/payload allocation limits; JPG metadata and remaining codec/audio/image formats in approved priority order. These units do not mean full HEIF or SEF semantic coverage.

## Tasks 8–12 — item information, sample tables and codec metadata
Completed HEIF infe v2/v3 hidden/version/flags, optional MIME encoding and URI; ctts v1 signed offsets and fixed sample-table header fields; JPEG Adobe version/flags; omitted HEVC configuration bitfields; AVC High Profile extension chroma/bit depths/SPS-ext counts. Real/generated fixture byte dumps and per-unit plans under 2026-10-24-*details.md document evidence. Existing full suite and independent reviews passed.

Updated remaining work: legacy infe v0/v1 requires verified fixture (not required for HEIF v2/v3); further HEIF properties and AVIF/AV1 initial presentation delay/config OBUs; proprietary SEF binary meanings require reliable definitions; SEF name/payload memory limits; MOV-specific metadata inventory; APV configuration header coverage; AAC, FLAC, MP3, AV2, PNG, TIFF/GIF/DNG remaining metadata inventory. Prior pending lines are historical, not current completion claims.

## Tasks 13–17 — AVIF/AV1, APV, AAC, FLAC and explicit interpretation states
Implemented optional AV1 initial presentation delay and bounded config OBU tree; APV sequential entry/frame-info/color-description arrays; AAC ADTS raw header/CRC fields and unknown reserved frequencies; native FLAC seek points/APPLICATION/common headers and MP4 FLAC MD5 with short-block protection. User also requested visible uninterpreted values: explicit field/node states now drive bilingual Detailed Properties badges/notices and optional JSON metadata. Existing raw values/ranges remain available. Per-unit 2026-10-24 plans and fixture provenance record validation and review corrections.

Still pending: MPEG2/2.5 and broader MP3/ID3 value coverage, remaining MOV-specific metadata, FLAC CUESHEET and other metadata not yet decoded, TIFF/GIF/DNG and PNG inventory, broader AV2 inventory, private SEF binary semantic definitions and large-name/payload limits. No claim of complete format specification coverage.

## Task18 — MP3 / ID3 detailed metadata
Implemented MPEG1/2/2.5 LayersI/II/III header detail via shared integrity tables; ID3v2.3/v2.4 generic text/TXXX/URLs/comments/lyrics/APIC/UFID/private data/counters/GEOB; strict encoding, source-mapped v2.4 unsync; extended headers/footer, caps and failure states; ID3v1.1 track separation. Shared physical ID3 footer extent fixes MP3 leading-junk false warning and AAC misclassification. Real fixtures, raw dumps and detailed validation in 2026-10-24-mp3-id3-details.md and docs/validation/mp3-details-fixtures.

Remaining after task18: MOV-specific metadata inventory; FLAC CUESHEET and other metadata not yet decoded; PNG/TIFF/GIF/DNG and broader AV2 inventory; private SEF binary definitions and large-name/payload limits. ID3v2.2, v2.3 tag-wide unsync, compressed/encrypted frame content and other unsupported ID3 records remain explicitly uninterpreted rather than fully implemented. No complete-format-coverage claim.
