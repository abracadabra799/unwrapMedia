# AVIF / AV1 configuration details
Continue approved byte-backed metadata coverage. Append derived bit_depth, reserved bits, optional initial_presentation_delay_minus_one and delay count (samples); never fabricate absent delay. Expose configOBUs as bounded child nodes with header fields, optional temporal/spatial IDs, LEB128 size and payload range. No full frame decoding. Preserve first12 fields and summary. Limit256 OBU nodes; explicitly disclose unparsed bytes and warn if malformed/limited. Unknown av1C marker/version cannot be interpreted as supported.

Actual Pillow11.3.0 fixture created BEFORE parser code: `Image.new('RGB',(16,16),(32,96,160)).save(...,format='AVIF')`; av1C at213 payload221 is81000c00, no configOBUs (legal). Existing default-av01 MP4 av1C payload4314, bytes81000c000a0b02000005557ffc02f80040, one sequence-header OBU offset4318 with11-byte payload at4320. Independently saved av1-apv-config-bytes.txt. Source: https://aomediacodec.github.io/av1-isobmff/ sec2.3 (accessed2026-10-24): delay is samples-minus-one, configOBUs may be empty, has_size_field=1 and LEB128 required.

- [x] Verify actual AVIF/MP4 bytes before implementation.
- [x] Red tests: empty actual AVIF config, actual sequence OBU source range, optional delay12-bit, extension IDs, malformed LEB128/payload/range and node cap.
- [x] Implement Av1ConfigObuDecoder helper and append fixed fields in Av1CBoxDecoder; retain existing order.
- [ ] Focused/full tests, independent review, commit/local main merge. No push.

Integration correction: new av1C warnings initially preempted the analyzer's specific invalid-marker/version diagnostic. Existing malformedAv1cReportsUnsupported reproduced this. Moved the generic layout-warning check after raw marker/version validation; focused analyzer regression passed without relaxing the assertion.

Validation: focused tests and final whole-branch :app:test passed: {'tests': 2588, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only reviews completed; reported defects have regression tests and were resolved. UI compiled with Material3 badges; no manual GUI screenshot verification was performed.
