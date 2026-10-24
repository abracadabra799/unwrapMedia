# FLAC detailed metadata completion

Continue the approved Detailed Properties audit in the existing native FLAC walker. CUESHEET becomes a bounded track/index tree with exact byte ranges: catalog, lead-in, CD flag, reserved bits/bytes, track count, per-track sample offset/number/ISRC/type/pre-emphasis/index count, and per-index sample offset/number/reserved bytes. Sample offsets are unsigned 64-bit values, not file positions. Known structure is decoded; this is not a new full conformance checker.

Complete PICTURE metadata with MIME/description lengths, palette color count, picture data length and exact raw payload span. URI pictures (`-->`) display their ASCII URI without fetching it. Embedded image bytes remain explicitly uninterpreted. Preserve existing field names used by summaries.

Complete VORBIS_COMMENT with vendor/comment lengths and count, exact value spans, repeated tags, strict UTF-8, and bounded expansion. Malformed text/counts/extents are UNPARSABLE; inspection caps, reserved extensions and opaque payloads are UNINTERPRETED. No replacement-character success. Limits: 4096 expanded cue nodes or comments, 65536 bytes per text, 1048576 aggregate text bytes, 32-byte raw preview. Every exposed range stays within its parent; malformed blocks must not consume the next block.

No new app dependencies or version bump. Fixture-only Mutagen 1.47.0 lives in a temporary environment. Evidence: RFC9639 §§8.6–8.8 and ffmpeg/Mutagen-generated default, CD and non-CD FLAC files with independently dumped bytes under docs/validation/flac-metadata-fixtures.
