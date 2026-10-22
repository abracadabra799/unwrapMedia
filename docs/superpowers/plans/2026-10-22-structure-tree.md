# Structure Tree Implementation Plan

**Goal:** Make the left structure tree quieter and easier to scan.
**Architecture:** Refine BoxTreeView presentation and pass language from Main; retain parsing and hierarchy helpers.
**Tech stack:** Kotlin, Compose Desktop Material3.
**Spec:** ../specs/2026-10-22-structure-tree-design.md

## Task 1: Tree presentation and interaction
- [x] Run existing BoxTreeViewTest as baseline.
- [x] Update BoxTreeView with 24dp rows, 16dp indentation, neutral names, subtle guides, 76dp size column, compact warnings and ellipsized summaries.
- [x] Separate disclosure clicks from selection, keeping selection reveal intact.
- [x] Pass AppLanguage from Main; localize tree toolbar and headings.
- [x] Compile and run existing BoxTreeViewTest. Review diff for narrow panels, long labels, deep nesting, warnings, and external selection.
- [x] Fresh branch review, commit, fast-forward main, launch updated application.

## Validation
This is a reversible presentation change. Use existing hierarchy/export tests and compilation rather than tests that repeat layout constants. Check the running UI if desktop capture is available; report the limits otherwise.

## Results
- Baseline and final BoxTreeViewTest: 14 tests, zero failures/errors. Kotlin compilation passed.
- Desktop screenshot checked with restored JPEG/motion-photo tab: hierarchy, muted summaries, fixed size alignment and toolbar rendered correctly.
- Fresh review found selection-scroll and name-priority issues; both corrected. Reveal now runs only on selection changes; names measure before summaries.
- Scope: existing helper tests and static interaction review; no automated desktop click test added.
