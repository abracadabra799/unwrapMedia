# Sample details plan

- [x] Failing registry tests: real AV1 sdtp at 16694 (10 entries, first byte 20); default AAC sgpd at 1383 (v1 roll, default_length=2, one roll_distance=-1); synthetic v0/v2, variable-length entries, truncated lengths, unknown groups/versions.
- [x] Implement TableRowEncoding default UNSIGNED, SDTP_BITS and SIGNED_16; table-aware reader; UI and JSON propagation. Assert unsigned rows remain unchanged.
- [x] Implement bounded SampleDetailsDecoder registration for sdtp/sgpd. Check every source range; fixed tables avoid eager reads; variable entry limit disclosed as a status field.
- [x] Focused tests then full suite; author review (independent reviewer unavailable).
- [ ] Merge local main.

Verified layouts: SDTP FullBox v0 + one byte per sample (bits 7..6, 5..4, 3..2, 1..0). SGPD v0: FullBox, grouping_type, entry_count, known implicit-size descriptions. v1: default_length before count; v2: default_sample_description_index after default_length. When default_length=0 for v1/v2, each description begins with u32 description_length. For roll/prol, signed roll_distance uses two bytes.

Validation: failing registry tests reproduced missing parsers; focused tests passed after implementation. Full :app:test passed. The added compatibility test initially assumed unsupported one-byte unsigned rows; corrected to the existing four-byte unsigned representation and rerun. Independent review was unavailable due to agent usage limit; author reviewed bounds, versions, row storage, signed values, UI routing and JSON compatibility.
