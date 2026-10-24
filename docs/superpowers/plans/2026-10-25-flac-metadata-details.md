# FLAC detailed metadata implementation plan

> Execute inline using executing-plans; independent whole-branch review before local main integration.

**Goal:** Expose remaining native FLAC metadata values without fabricating decoded values.
**Architecture:** Small decoders called by FlacWalker; shared bounded cursor retains BoxField source spans and explicit interpretation states. Existing UI renders fields and children.
**Tech stack:** Kotlin/JDK21, existing ByteReader/BoxNode; no runtime dependencies.
**Spec:** ../specs/2026-10-25-flac-metadata-details-design.md

## Constraints and review focus
- At most 4096 expanded nodes/comments, 65536 text bytes per field, 1048576 text bytes in total, 32-byte raw previews.
- All warnings/statuses bilingual. No reads beyond declared block or into following blocks.
- Verify empty/truncated CUESHEET, unsigned sample offsets, forged index counts, invalid ASCII/UTF8, and length/counts above Int.MAX_VALUE.
- Preserve duplicate tags and existing vendor/TITLE/picture_type/mime/description/width/height/color_depth consumers.

## Task 1 — CUESHEET
- [x] Generate actual fixtures before implementation; independent dumps recorded. metadata.flac CUESHEET146 payload150 len540: catalog1234567890123, lead-in88200, CD flag128, tracks3. Track1 at546, ISRC USAAA2600001, pre-emphasis1, index0@582 offset0, index1@594 offset588. Track2@606 offset44100; lead-out@654 number170 offset88200. non-cd.flac track1@492 offset17/type1, index@528 offset3, lead-out255.
- [ ] Add FlacCueSheetDetailsTest: actual CD/non-CD values/ranges, unsigned u64 max, every truncated prefix, following-block isolation, expanded-node limit.
- [ ] Observe red with `./gradlew :app:test --tests 'com.multiviewer.parser.FlacCueSheetDetailsTest'`.
- [ ] Implement FlacCueSheetDecoder and shared FlacMetadataCursor; dispatch CUESHEET even with empty payload. Preserve decoded prefix and explicit remaining raw ranges on errors/caps.
- [ ] Focused suite green; commit task.

## Task 2 — PICTURE and Vorbis details
- [ ] Add FlacMetadataValueDetailsTest. Actual first picture690: data_length96 at737, payload741 len96, num_colors0 at733, description 표지715 len6. URI payload888 len33. Vorbis count4 at63; TITLE value102 len11; repeated ARTIST values First/Second. Add forged lengths/counts, invalid text, trailing data and caps.
- [ ] Observe red; implement FlacPictureDecoder and FlacVorbisCommentDecoder using shared bounded cursor. Delete old duplicate functions from FlacWalker, retain STREAMINFO and other dispatch.
- [ ] Focused parser/summary tests green; commit task.

## Task 3 — Review and integration
- [ ] Independent code review; correct important findings with regression evidence.
- [ ] Full `./gradlew :app:test`, record counts and any failures/retries. Update broad parsing plan/provenance. Integrate with latest main and fast-forward locally. Initial requested push was completed before this work; no automatic second push.
