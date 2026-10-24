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
