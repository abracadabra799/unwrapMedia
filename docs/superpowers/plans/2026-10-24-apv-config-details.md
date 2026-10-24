# APV configuration array details
Continue approved APV metadata coverage. Existing decoder reads one frame at fixed offsets9..20, omitting FullBox/config headers and additional entries/frame-info/color descriptions. Parse v0 flags0 FullBox + configurationVersion1 + entry_count, entries(type/count), frame records(flags/profile/level/band/width/height/chroma-depth/capture distance, optional4-byte color description). Preserve first frame's seven legacy fields and summary at root for existing consumers; all entries/frames become source-backed child nodes. Record raw reserved bits. Bounded to file/box, max4096 frame nodes; limits warn and disclose remaining bytes. Do not infer width-minus-one from misleading source comments: actual writer serializes frame_width directly, matching verified3840/2160 fixture.

Actual checked-in config, independently dumped before code in av1-apv-config-bytes.txt: apvC offset499 payload507, 000000000101010101217b0200000f00000008702200. FullBox zero; configurationVersion1; one entry(type1), one frame, flags1(capture_time_distance_ignored), profile33 level123 band2 width3840 height2160 chroma2 depth10 capture_distance0. Official source https://raw.githubusercontent.com/FFmpeg/FFmpeg/master/libavformat/apv.c APVDecoderConfigurationRecord and writer (accessed2026-10-24).

- [x] Verify actual config against official serializer before code.
- [x] Red tests: actual header and capture flag source offsets; multiple entries/frames with optional color; malformed versions/truncation/file bounds.
- [x] Replace fixed-position decoder with bounded sequential parsing, retain root compatibility.
- [x] Focused/full regression and independent review; commit/local main merge.

Validation: focused tests and final whole-branch :app:test passed: {'tests': 2588, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent read-only reviews completed; reported defects have regression tests and were resolved. UI compiled with Material3 badges; no manual GUI screenshot verification was performed.

Merged into local main on 2026-10-24. Remote push not performed.
