# Gain Map Analyzer — implementation plan

Spec: `docs/superpowers/specs/2026-10-13-gainmap-analyzer-design.md`. Branch `feature/gainmap-analyzer`
(worktree `.worktrees/gainmap`). Fixtures: `app/src/test/resources/c2pa/s26-camera.jpg`,
`s26-motion-photo.heic` (byte layouts verified in the spec). Run `./gradlew :app:test` after each task.

## Phase 1 — menu + single window
1. `GainmapAnalyzerPage` enum (OVERVIEW, IMAGE, HDR_PREVIEW, CHECK, RAW_METADATA) and
   `TabState.gainmapAnalyzerPage` replacing `isGainmapImagePopupOpen` / `isGainmapXmpPopupOpen`.
2. `GainmapAnalyzerWindow` in `GainmapWindows.kt`: header (format badge, file, summary) + TabRow; pages reuse
   `GainmapParametersCardView` (overview), the existing image viewer body (image) and raw XMP view (raw);
   HDR/CHECK pages show a placeholder until phases 3/4. Delete the two old windows.
3. Main.kt: menu 게인맵 = 분석기 (⌘G) + 이미지 저장; move 파일 내 XMP 정보 to 분석; host the new window;
   overview summary box buttons → analyzer pages. I18n strings (KO/EN).

## Phase 2 — ISO 21496-1
4. TDD `parseIsoGainmapMetadata`: S26 61-byte payload → values in the spec; common-denominator and
   3-channel hand-built payloads; truncated / minimum_version≠0 → null; `toParsedParameters()` incl. backward swap.
5. JPEG: `readJpegIsoGainmapPayload(file, offset, length)` finds APP2 `urn:iso:std:iso:ts:21496:-1\0`.
   HEIF: `readHeifTmapPayload(file, root)` via iinf/iloc/idat. TDD against both S26 fixtures (bytes equal).
6. `GainmapInfo` new fields + evidence-based classification; S26 JPEG → `ISO_21496_1_JPEG` with sources
   {GCONTAINER, HDRGM_XMP, ISO_21496_1, MPF}; S26 HEIC → `ISO_21496_1_HEIC` with {HEIF_TMAP, HEIF_AUXC,
   ISO_21496_1, HDRGM_XMP}, auxType Samsung. Overview + raw page show ISO fields and sources.

## Phase 3 — HDR preview
7. TDD `GainmapMath`: `logBoost`, `weight`, `applyGain` (known values), sRGB EOTF/OETF round trip.
8. `renderHdrPreview(base, gainmap, params, headroom, mode, maxEdge)` → IntArray ARGB; test on synthetic
   2×2 inputs (gain 0 → unchanged at H=1; gain 1 at H=2^max → saturates).
9. HDR page UI: slider (stops), mode toggle (HDR sim / boost heat map / SDR base), cursor readout, render on
   Dispatchers.Default with cancellation; uses `tab.imageForensic.bitmap` + decoded gain map bitmap.

## Phase 4 — conformance
10. TDD `GainmapValidator.validate(file, root, info)` → `List<IntegrityCheckItem>`; S26 files all PASS/INFO;
    tampered copies (ISO max changed; GContainer length changed; HEIC `tmap` brand removed) → WARN/FAIL;
    missing metadata → WARN not PASS.
11. CHECK page renders the list; Content Check gets `ContentTab.GAINMAP` (images with a gain map).

## Wrap-up
12. Full test run, whole-branch review, ff-merge, version bump 1.22.0 (3 places + AGENTS.md), push on request.
