# AAC ADTS header details
Continue approved AAC metadata coverage. Preserve six existing fields and append raw profile/frequency/channel indices, layer/private/original/home/copyright bits, ADTS buffer fullness and raw-data-block count. Expose header CRC only for CRC-protected single-block frames; multiple-block CRC positions are not assumed. Reserved frequency indices must display Unknown rather than fabricated44100Hz. No sample payload parsing or frame expansion beyond existing25-node cap.

Actual fixture BEFORE code: ffmpeg -hide_banner -loglevel error -f lavfi -i sine=frequency=440:sample_rate=48000 -t0.1 -c:a aac -f adts default-adts.aac. Independently read first7 bytes fff14c401b7ffc = MPEG4 LC, frequency_index3, channel_configuration1, frame_length219, buffer_fullness2047, raw blocks0. Existing AacWalkerTest documents the same ADTS bit layout. CRC and reserved-index branches use explicit synthetic header tests.

- [x] Generate actual ADTS fixture and verify bytes.
- [x] Red tests for actual raw fields/source spans, single-block CRC, reserved frequency handling.
- [x] Append bounded fields in AacWalker, preserve existing field order.
- [ ] Focused/full tests and review; commit/local main merge.

Validation: focused tests and final whole-branch :app:test passed: {'tests': 2588, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only reviews completed; reported defects have regression tests and were resolved. UI compiled with Material3 badges; no manual GUI screenshot verification was performed.
