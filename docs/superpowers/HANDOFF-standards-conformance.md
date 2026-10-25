# Handoff — Standards conformance + spec citations (2026-10-25)

State: everything below is merged to `main` and pushed to `origin/main` (`bb6349bf` was the last conformance
commit; 2,914 tests, 0 failures, `ThumbnailDiskCacheTest` known flaky). No worktree or feature branch is open.
Communicate with the user in Korean. Read `AGENTS.md` first.

## What the user wants (binding rules)

1. **Content Check must validate every content type strictly against its public standard**, not just
   "parses/decodes". Severity: spec `shall`/`MUST`/Mandatory → **FAIL**; `should`/Recommended → **WARN**;
   **never INFO**, never downgraded because a vendor's normal files violate it (e.g. Samsung JPEGs FAIL Exif
   Mandatory tags; Samsung motion photos WARN on Google Motion Photo offsets — this is intended).
2. **Never a false PASS**: truncated / unsupported / unparsed / not decoded → `NOT_CHECKED` (or WARN), not PASS.
3. **Every WARN/FAIL carries its source** (`SpecCitation(document, section, page, url, verified)`): document +
   edition, section, physical PDF page, and a clickable deep link (`#page=N` for PDFs, `#anchor` for HTML).
   Download the original and match the quoted sentence before setting `verified=true`. **Never invent pages or
   anchors.** Paid ISO/IEC documents → iso.org store page, `verified=false`, page null ("원문 미대조").
   Rules derived from a definition (no verbatim requirement sentence) end their section with
   `DEFINITIONAL_SUFFIX` and show "인용 정의에서 도출한 검사". App-only observations have no citation and show a
   plain `sourceNote` label instead. Shown in the UI table, CLI `check` JSON, "JSON 복사" export and the AI prompt.
4. Documents used are listed with editions, URLs and sha256 in `docs/validation/spec-citations/README.md` —
   keep it updated.

## Roadmap status (user-chosen order)

| # | Area | Status | Spec / plan |
|---|---|---|---|
| 0 | Google Motion Photo 1.0 checker + builder modes (Google-spec / Samsung-compatible submenu) | shipped | `2026-10-22-google-motion-photo-spec-compliance*`, `2026-10-22-motion-photo-builder-modes*` |
| 1 | ISO-BMFF / MP4·MOV·M4A container (14496-12:2026, QTFF profile for MOV) phase 1 | shipped | `2026-10-23-isobmff-container-conformance*` |
| 2 | HEIF/HEIC/AVIF (23008-12:2025, AVIF v1.2.0) phase 1 + citation model | shipped | `2026-10-24-heif-avif-conformance*`, `2026-10-24-spec-citations-design.md` |
| — | Citation retrofit of the 9 codec bitstream catalogs + PNG | shipped | brief in commit history (`feat: cite ITU-T sections…`) |
| 3 | JPEG: T.81 + JFIF T.871 + Exif CIPA DC-008-2026 (3.1) + MPF DC-007-2026 | shipped | `2026-10-24-jpeg-exif-conformance*` |
| 4 | PNG-3 (incl. APNG, zlib RFC 1950) + WebP RFC 9649 (+ VP8 RFC 6386 §9.1) + GIF89a | shipped | `2026-10-25-png-webp-gif-conformance*` |
| 5 | **Codec bitstream deep syntax** (SPS/PPS/VPS, sequence headers, slice headers…) | **next — not started** | — |

Also deferred: ISO-BMFF phases 2-3 and HEIF phases 2-3 (see their specs' "later phases"), Google Motion Photo
phase 2 (§5 video tracks/AAC, §6 meta track), XMP conformance for JPEG, and making the CLI `check` print codec
bitstream findings (it currently prints container conformance only).

## Decisions the user already made (don't re-ask)

- MOV judged by QuickTime profile; ffmpeg fMP4 `next_track_ID == max` stays FAIL (strict).
- HEIF: brandless AV1 item stays HEIF with a `codec_brand` WARN; unused `idat` with iloc v0 → WARN.
- JFIF 1.00/1.01 → WARN (earlier edition); other non-1.02 → FAIL. Exif Recommended tags missing → one WARN row
  listing all names. Bytes after EOI explained by MPF / valid ISO-BMFF motion video / gap-free SEF → PASS.
- PNG data after IEND → FAIL (§15.3.1); WebP trailing data → WARN (SHOULD NOT); GIF87a header with 89a blocks →
  FAIL (macOS `sips` GIFs FAIL); unknown public critical PNG chunk → FAIL; VP8 version > 3 → WARN.
- A Samsung SEF trailer explains trailing bytes only if it has ≥ 1 entry and the entries tile
  `[end of image, SEFH)` exactly (shared helper in `parser/conformance/ImageRuleBook.kt`).
- `vvc.nal.reserved_zero_bit` stays FAIL ("shall be equal to 0").

## Code map

- Model: `parser/conformance/ConformanceModel.kt` (`ConformanceRule`, `SpecCitation`, `ConformanceStatus`,
  `DEFINITIONAL_SUFFIX`), `CodecCitations.kt`.
- Containers/images: `parser/conformance/{isobmff,heif,jpeg,png,webp,gif}/`, shared `ImageRuleBook.kt`;
  dispatch in `ui/ContentCheckPlan.kt` `containerConformanceFor(file)` → Content Check row "컨테이너 포맷 규격".
- Codecs: `parser/conformance/{Avc,Hevc,Vvc,Av1,Vp9,Apv,Aac,Opus,Flac}*` (catalog + scanner + analyzer);
  UI mapping `ui/CodecContentFindings.kt` (`codecProvenanceFact`), prompt/JSON `ui/ContentCheckSnapshot.kt`.
- Motion photo: `parser/motionphoto/GoogleMotionPhoto{Xmp,SpecChecker,Layout}.kt`.
- Source labels / link opening: `conformanceSourceNote`, `openCitation` (http/https only, daemon thread).
- Fixtures: `app/src/test/resources/<feature>-conformance/` with regeneration scripts under
  `docs/validation/<feature>-fixtures/`.

## How work was run (and what the user cares about)

- Per feature: brainstorm (short AskUserQuestion with recommended options) → spec → plan with compile-checked
  code → implement in `.worktrees/<name>` → one final whole-branch review → one fix pass → rebase onto `main`
  (the user commits to `main` in parallel — expect to rebase) → full test → ff merge → push only on the user's OK.
- **The user asked about token cost** — keep it lean: one research+spec+plan agent, one implementer for all
  tasks, one final review, one fix pass; spot-check citations rather than re-verifying all of them.
- Reviews repeatedly found false PASSes via crafted files (trailer gaps, duplicate start markers, empty SEF
  directories). Always hunt those with the real CLI: `./gradlew :app:run --args="check <absolute path>"`.
- Never modify or commit files from `~/Downloads` (real Samsung samples) — work on copies.

## Owed to the user (manual)

- GUI click-through of citation links in Content Check (macOS + Windows), e.g. on `ffmpeg.apng`, `sips.gif`,
  a Samsung JPEG, a HEIC; motion-photo submenu; label widths.
- Device playback of Google-spec motion photos made by the builder.
- Possible Samsung bug to report: S26 JPEG MPF primary-image size is off by exactly the C2PA APP11 size
  (−14,494 bytes); two other motion JPEGs off by +728 / +726.
