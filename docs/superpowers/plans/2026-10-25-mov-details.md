# MOV metadata implementation plan

- [x] Actual fixtures and independent dumps before code: ffmpeg keyed.mov/legacy.mov; Apple AVFoundation typed.mov;96kHz2-channel float PCM float-audio.mov; existing qt-v2 fixture. Source/provenance retained in docs/validation/mov-details-fixtures.
- [x] Unit1 red: actual float precision and derived exact payload ranges; fixed integer types65/66/67/74..78, UTF16-sort5, vectors70..72/matrix79, strict lengths/strings/type-set, raw unknown-state propagation. Implement bounded QuickTimeDataDetailsDecoder/value helper; keys validation/caps, safe enrichment copies, ilst structure warnings/caps.
- [x] Unit2 red: real v1/v2 packet fields and lpcm values, chan/frma/enda and synthetic explicit channel descriptions/truncation. Add fields without changing valid legacy prefix, register PCM entries and small sound extension decoders. No expansion of conformance indexing codec list.
- [x] Unit3 red: actual legacy title/encoder record, official header-inclusive form, multiple languages, malformed/ambiguous/unsupported encoding. Register bounded userdata text decoder, preserve exact raw source and states.
- [x] Whole regression + independent review; address findings with regression tests; commit and merge local main. No push.

Raw evidence: keyed title data offset5582 payload5598 UTF8 검증 MOV; typed float64 data5816 value5832 length8 hex3ff3c0ca428c51f2=1.234567890123; typed uint64 data5987 value6003 len8=UInt64.max. Actual AVFoundation serialized fixed integer requests as legacy21/22, including8-byte values; synthetic fixed-code tests derive from Apple well-known types. lpcm sample15861 payload15869 version2 sizeOfStructOnly72, sampleRate96000, channels2, constBits32, flags9, constBytesPerAudioPacket8, constLPCMFramesPerAudioPacket1. Legacy ©nam5419 payload000a55c4+10 UTF8 bytes (header-excluding form).

Review correction: `raw ` is ambiguous between PCM and RGB video. Removed its global audio registration; ffmpeg RGB24 fixture and regression test failed before the correction. FourCC decoding now uses lossless Latin-1, enabling actual ©nam/©swr legacy atom routing.

Pre-integration validation: full :app:test passed (2621 tests, 0 failures, 1 skipped). Independent review confirmed the raw RGB correction and found no remaining blockers. One earlier full run hit the CLI timeout test's missing PID file; the unchanged CLI suite passed alone. An overlapping test invocation caused report-write contention in another run; final full run was sequential and successful.

Integration validation: rebased onto main 1a56ce4c without conflicts; full :app:test passed (2795 tests, 0 failures, 1 skipped). Ready for local fast-forward. Remote push is not part of this task.
