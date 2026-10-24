# MP3 / ID3 implementation plan

- [x] Generate real ffmpeg/libmp3lame MPEG1(48kHz128k), MPEG2(24kHz64k), MPEG2.5(12kHz32k) fixtures with v2.3/v2.4 metadata and real PNG APIC. Independently dump headers before code.
- [x] Task1 red: real rate/bitrate/version, header flags/source spans, LayerII and reserved/free-format cases; reuse parseMpegFrame and expose bitrateKbps, replace duplicated sniff table.
- [x] Task2 red: actual TXXX/APIC/texts and synthetic COMM/WXXX/PRIV/UFID, strict encoding and UTF16 terminators, frame-flag guards. Implement bounded Id3FrameDecoder with source-mapped v2.4 unsync payload when needed; unknown/unsupported data explicitly flagged. Preserve existing known field prefix when possible.
- [x] Task3 red: tag/frame headers, extended-header/footer boundaries, unsupported v2.2/unknown version and v2.3 tag-unsync status, malformed sizes/caps. Implement dedicated Id3TagDecoder; preserve shared decodeId3v2 call for AAC.
- [x] Task4: ID3v1.1 track support with synthetic trailer regression.
- [x] Whole regression and independent review; fix findings with regression tests; commit/local main merge.

Verified actual bytes: mpeg1 header offset265 fffb94c0; MPEG2 offset160 fff384c0; MPEG2.5 offset160 ffe344c0. Actual TXXX custom_key at90, data offset100 encoding0(v2.3)/3(v2.4), description101..110, value112..123. Actual APIC offset150: payload160, encoding0, MIME image/png at161..169, picture type3 at171, description Cover at172..176, image178..254 (77bytes). TIT2 v2.3 UTF16 BOM; v2.4 UTF8. Dumps establish stored bytes separately from application output.

Focused validation: all initial new suites failed on missing fields/behavior before implementation. MPEG/header and integrity compatibility tests, real/synthetic frame tests, extended/footer/size/cap tests, and ID3v1.1 comment/track regression passed after implementation. Extended-header compile condition needed parentheses around the conditional minimum length and was corrected before the green run.

Builder decisions: v2.3 tag-wide unsynchronisation, v2.2 frames and compressed/encrypted frame bodies remain explicitly UNINTERPRETED (no guessed source mapping or decrypted text). v2.4 source mapping is bounded to1MiB physical payload; strings65536bytes, counter1024bytes, raw previews64bytes, frames4096 and cumulative decoded value text1MiB. Resource limits warn/disclose remaining data. MPEG bitrate uses existing integrity tables; no duplication. Existing NUL literal in Mp3Walker source normalized to escaped Unicode without changing decoding behavior.

Review fixes: official UFID definition requires nonempty owner; added malformed UFID owner and empty APIC image regression (observed fail, then pass). No minimum UFID binary identifier length was invented beyond the specification's up-to64-byte limit.

Integration fix: valid v2.4 footer was skipped by the detail parser but previously counted as leading junk by MP3 integrity and caused AAC sniffing to select MP3. Regression tests on authored footers attached to actual generated MP3/AAC streams failed before the shared id3v2StoredEnd helper; then passed across sniffing, parseAac and MPEG/ADTS integrity. Helper validates physical tag bounds and footer/header match without walking frames. Initially the leading-junk test used the wrong issue ID, corrected to mp3.frame.leading and rerun red before the fix.

Final validation: whole :app:test {'tests': 2607, 'failures': 0, 'errors': 0, 'skipped': 1}. Independent whole-branch review and correction re-review found no remaining concrete issues. No manual GUI verification; parser changes are consumed by existing Detailed Properties/JSON.

Merged into local main on 2026-10-24 (2607 tests, 0 failures, 1 skipped). Remote push not performed.
