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
