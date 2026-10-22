# Thumbnail Collapse Implementation Plan

**Goal:** Add per-tab thumbnail collapse and restore, including files without embedded thumbnails.
**Architecture:** TabState owns visibility; ImageInspectorUI renders a persistent toolbar and conditionally displays the existing thumbnail panel. Existing equal weights restore the original width allocation.
**Spec:** ../specs/2026-10-22-thumbnail-collapse-design.md

## Task 1
- [x] Add thumbnailPreviewExpanded (default true) to TabState.
- [x] Add bilingual always-enabled thumbnail toggle above the non-GIF preview row and a minus button in the thumbnail header.
- [x] Keep unavailable/loading/unsupported messages, with no-thumbnail state collapsible.
- [x] Preserve other previews' composition identity and existing layout weights; no independent width resizing added.
- [x] Compile, run existing UI tests, inspect actual screenshot, and review diff.
- [x] Commit, fast-forward main, launch updated app.

Presentation-only change: use existing tests and actual UI inspection rather than new tests mirroring a boolean toggle.

## Validation results
- Kotlin compilation and existing BoxTreeViewTest: 14 passed.
- Fresh static review: no findings; confirmed absent-thumbnail toggle, stable preview identity, per-tab visibility, and GIF branch.
- Actual PNG screenshot verified toolbar, minus button, and no-thumbnail message. End-to-end collapse/restore click behavior was not conclusively verified because the active tab changed during desktop inspection.
