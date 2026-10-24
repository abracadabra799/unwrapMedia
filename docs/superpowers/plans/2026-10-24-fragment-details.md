# Fragment MP4 details

Approved scope: continuing MP4 property coverage. Register moof/traf/mvex/mfra containers to expose their structure, plus mfhd/trex/tfhd/tfdt/trun decoders. Retain sample tables as lazy reads. This adds structural inspection, not fragmented video playback/index support.

- [x] Generate actual ffmpeg fragmented AVC fixture, independently record bytes in docs/validation/detail-parsing/fragmented-bytes.txt.
- [x] Add registry/real-file tests and synthetic flag/version/truncation tests; verify failure.
- [x] Decode FullBox scalars and flag-gated fields with bounded reads. tfdt supports uint64 via unsigned string. trun data_offset is signed; v1 composition offsets use SIGNED_LAST_32 table encoding, v0 unsigned.
- [x] Validate full suite and author review.
- [x] Commit and merge local main.

Verified actual values: mfhd sequence_number=1; trex track_ID=1 and default_sample_description_index=1; tfhd flags=0x020038, track_ID=1, default_sample_duration=2048, default_sample_size=1980, default_sample_flags=0x01010000; tfdt v1 baseMediaDecodeTime=0; trun flags=0x000205, sample_count=2, data_offset=120, first_sample_flags=0x02000000, sizes=1980/510.

Validation: all three fragment tests failed before implementation; focused fragment/sample tests and full `:app:test` passed. Author reviewed bounded field reads, flag masks, uint64 formatting, signed composition offsets, and lazy table integration. Independent reviewer unavailable due to agent usage limit.

Full regression result: 2549 tests, 0 failures, 0 errors, 1 skipped. Merged into local main on 2026-10-24; no remote push.
