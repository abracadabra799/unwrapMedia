# HEVC Bitstream Check in Content Check

## Goal and dependency

Add the first real codec-bitstream rule runner to the redesigned Content Check workflow. For an ordinary MP4/MOV HEVC video, tell a Galaxy codec engineer which implemented HEVC rules were checked, exactly where a violation occurred, and what remains outside coverage. This spec depends on `2026-10-17-content-check-workflow-design.md` for run state, progress, cancellation, and UI. It does not claim full HEVC conformance or MediaCodec compatibility.

## First supported scope

- Start with MP4/MOV files whose media type is `VIDEO`, whose first video track has an `hvc1` or `hev1` sample entry and a valid `hvcC`, and whose samples are indexed by non-fragmented `moov` sample tables. Both tags are required: ffmpeg's default HEVC MP4 output commonly uses `hev1`.
- The runner checks every indexed sample of that track up to the existing `MAX_VIDEO_INDEXED_SAMPLES` cap. If more samples exist, the file is fragmented (`moof`), sample tables disagree, data is encrypted, a sample extends outside the file, or a required codec configuration cannot be read, report the precise coverage gap as `부분 검사 / Partial` or `미지원 / Unsupported`. Do not show a codec PASS for an unscanned region or another video track.
- If there are multiple video tracks, identify the checked track and list each other track as `미검증 / Not checked`. Future codecs/tracks are separate follow-on specs, not inert green placeholders in this runner.
- Use a versioned rule catalog tied to [ITU-T H.265 (01/26)](https://www.itu.int/rec/T-REC-H.265) for HEVC syntax and ISO/IEC 14496-15 for the HEVC sample entry/configuration boundary. A rule is labeled normative only after its exact clause, profile applicability, and byte interpretation are independently verified. Unverified ideas are not emitted as PASS/FAIL rules.

## Rules and evidence

The first rule set is intentionally small and byte-verifiable:

| Rule family | Observation | Failure/evidence boundary |
| --- | --- | --- |
| Sample and NAL framing | A sample's length-prefixed NALs fit exactly in the indexed sample extent using the `hvcC` length size. | An oversize/truncated prefix or NAL is a violation with sample and absolute byte offset. A zero-length NAL has no header to validate and is NOT_CHECKED unless a separately verified format rule forbids it. An unreadable sample is an inspection failure, never PASS. |
| NAL header syntax | The two-byte header is present; `forbidden_zero_bit` is zero and `nuh_temporal_id_plus1` is nonzero. | Report NAL type, observed header bits, track/sample/NAL index, and the two-byte range. |
| Parameter-set syntax and references | Parse VPS/SPS/PPS with the existing bounded HEVC parsers and check only fields and ID references those parsers fully support. | A known-invalid value/reference is FAIL. A parser's unsupported syntax path, missing prerequisite parameter set, or incomplete slice-header parse is NOT_CHECKED with the reason, not a guessed FAIL or PASS. |
| Configuration consistency | Compare `hvcC` length-prefix size and parameter-set evidence with the actual sample representation where ISO/IEC 14496-15 gives a verified requirement for the applicable sample-entry type. | Display both values and both byte ranges. Keep `hvc1` and `hev1` conditions separate; no blanket assumption that all parameter sets must be in the same place for both. |

Each result stores a stable rule ID, severity/verdict, normative source and edition or an explicit `structural observation` label, applicability conditions, expected and observed values, track/sample/NAL index, exact byte range when available, and a concise KO/EN explanation. Several failures of one rule may be shown individually with bounded display/export counts and a total occurrence count. A valid NAL header only proves that header rule; it cannot make the full bitstream PASS.

## Execution and safety

- Reuse `IsoSampleTable.kt` for sample indexing, `HvcCBoxDecoder`, `HevcParameterSetExtraction`, `HevcParameterSets`, and `HevcSliceParser` where their syntax coverage is known. Gate the entry point on `tab.type == MediaType.VIDEO` and the matching HEVC config box; HEIC motion photos with an embedded `moov`/`hvcC` are excluded.
- Run bounded file reads on `Dispatchers.IO` in a cancellable job pinned to the opening tab and captured file identity. Check cancellation between samples/NALs and before publishing a result. Do not read a full long sample into an unbounded byte array merely to inspect its NAL header. Detect arithmetic overflow and out-of-file extents before reads.
- Report `checked / indexed samples`, current track/sample, and a bounded issue count. Progress remains indeterminate when the index is incomplete. Once the HEVC row completes, publish its findings immediately and recompute the conformance summary; the next independent row may continue. Cancellation retains already completed *other* rows, while the incomplete HEVC row is Partial/Cancelled with coverage, never PASS.
- Cross-layer conclusions consume the completed structure and HEVC observations. If either side failed or was not run, they are NOT_CHECKED and name the missing prerequisite. Decoder output remains a separate diagnostic and cannot change normative rule verdicts.
- Results survive switching the detail row, but a changed file invalidates byte evidence and exports. Extend the versioned Content Check JSON and bounded AI snapshot additively; preserve existing consumers and no-overwrite save behavior.

## Fixtures and verification

- Before implementing rules, generate and independently dump small real `hvc1` and default `hev1` MP4 fixtures with x265/ffmpeg. Record command versions, raw sample-entry, `hvcC`, length prefixes, and NAL headers in `docs/validation/hevc-conformance-fixtures/`. Compare ffprobe/Bento4 output for orientation only; assert raw bytes and parsed offsets in tests.
- Derive negative fixtures by one documented byte mutation each: truncated prefix, NAL beyond sample extent, forbidden bit, zero temporal ID, missing/inconsistent referenced parameter set, and malformed `hvcC`. Keep a valid default-settings fixture and a multi-sample file. Include a valid audio+video file, multiple video tracks, fragmented MP4, encrypted sample, and HEIC motion photo as boundary fixtures where practical.
- Pure tests cover header bit extraction, length arithmetic, rule applicability and reference handling. Real-file integration tests cover each supported tag, exact rule IDs/byte ranges, cross-layer prerequisites, cancellation/partial scan, file changes, and absence of false PASS. Run focused tests and `./gradlew :app:test`; perform manual UI inspection when a display can be inspected.

## Out of scope for this first runner

Full entropy-coded slice decoding, reconstruction, DPB/HRD conformance, full profile/level enforcement, all HEVC extensions, fragmented/encrypted MP4, HEVC in HEIF still images, other video tracks, H.264/AV1/VVC, and actual Android device playback. The UI must list these gaps rather than imply they passed. Future codec runners share the same stage and evidence model and receive their own verified rule catalogs and real-file fixtures.
