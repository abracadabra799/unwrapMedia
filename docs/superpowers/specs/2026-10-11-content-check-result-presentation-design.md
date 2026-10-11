# Content Check Result Presentation Design

## Goal

Make integrity and decode findings across the entire Content Check window easy to scan, compare, and investigate. Users should see the overall result first, then a concise table, then supporting evidence and next investigative steps without confusing verified facts with hypotheses.

## User-visible structure

Every applicable Content Check tab uses a consistent presentation pattern:

1. A compact summary shows overall status and counts for failures, warnings, passed checks, and skipped/unverified checks.
2. A findings table prioritizes actionable results. By default it shows failures, warnings, and failed inspection steps; a control reveals all checks, including pass and skip results.
3. Selecting a finding expands its details: verified facts, possible interpretation/cause (explicitly labeled as a hypothesis), and suggested next checks.

The common table columns are `Status | Check | Location | Finding`. Missing locations are shown explicitly as unavailable, never as an implied offset. Findings with byte ranges continue to select/highlight the corresponding range in the existing Hex view.

## Media-specific tables

- Image structure, image decode, motion photo, audio structure, and decode findings use the common findings table.
- Video packet mapping keeps its packet-specific columns (PTS, DTS, byte offset, size, keyframe) and existing Hex selection behavior, while adopting the shared summary and expandable finding details for packet warnings/errors.
- Audio decode presents tracks as rows and compares declared and decoded codec measurements, including sample rate, channels, and duration; mismatches expand to evidence and next checks.
- Existing parser warnings remain distinguishable from format-specific structure checks.

## Presentation model and data flow

Keep parser/checker report types and exported JSON schemas unchanged. Add a UI-facing presentation adapter that maps existing image, motion-photo, video/packet, and audio results into a shared finding view model. The model carries status, stage/category, title, location/range, concise summary, verified evidence, optional hypothesis, and suggested next checks.

Use stable check IDs and existing diagnostic mappings (including video log explanations) where available. Preserve unrecognized findings using their original title/detail and mark interpretation as unavailable; do not infer a root cause without a specific rule. Existing result payloads remain the source of truth.

Summary counts are counts of displayed normalized finding rows and must indicate that they are findings/checks, not unique root causes. Severity ordering is FAIL, WARN, INFO/SKIP, then PASS; entries with offsets sort by location within the same status.

## Facts, hypotheses, and privacy

- Byte positions, lengths, observed/declarative measurements, decoder output, and parser messages are presented as verified evidence only when present in the source report.
- Any root-cause interpretation is labeled `Possible cause / hypothesis` and is kept separate from evidence.
- A missing explanation is shown as `Cause not determined` / `추가 분석 필요`, not filled with a generic guess.
- Follow the current path-scrubbing behavior in AI diagnostic prompts. Do not add raw filesystem paths to the new presentation model or AI prompt.
- Keep the AI prompt's verified facts distinct from hypotheses and suggested follow-up checks.

## Interaction and behavior constraints

- Preserve existing inspection triggers and status semantics; this work changes presentation, not checker verdicts.
- Preserve selection/highlighting for structure and packet byte ranges.
- Keep large reports scrollable and cap/virtualize rendered findings as current panels do.
- Keep Korean and English labels aligned for all shared headings, statuses, and empty states.
- Do not alter CLI output or analysis-case JSON schema.

## Testing

- Unit-test mapping for each media result type, including verified fields, optional hypotheses, location/range, and no-explanation fallback.
- Test status ordering and summary counts, including skipped/pending/failed inspection states.
- Test that inferred causes are explicitly labeled and do not enter verified-facts content.
- Test audio-track declared-versus-decoded rows and video packet location mapping.
- Retain and run existing Hex-range selection and AI path-scrubbing tests; add regressions where the shared adapter changes their inputs.
- Run the focused Content Check tests and the full test suite.

## Out of scope

- Changing structural validation algorithms, decoder behavior, or inspection execution timing.
- Adding new CLI commands or changing JSON schemas.
- Automatically repairing or rewriting media files.
- Claiming decoder interoperability or visual layout verification without corresponding manual/runtime QA.
