# Claude handoff — contextual media analysis

Updated: 2026-10-14, paused for an explicit handoff to Claude. This document is the resume point for the current feature branch; verify `git status`, `git log`, and the task ledger before continuing.

## User intent and settled choices

The user is a Galaxy multimedia framework, codec, Motion Photo, editor, and Gallery engineer. They want unwrapMedia to replace internal media-analysis/playback utilities. Three connected capabilities are in scope:

1. Evidence- and coverage-aware per-file spec conformance, without a false all-spec PASS.
2. A real-file structure diagram/table that can be copied into cross-team documents (especially Motion Photo layouts).
3. A collapsible right-side AI conversation for the focused file, app analyses, visible content, selected frame/structure, mouse point or marked region, optional web search, and read-only agent actions.

The company uses Enterprise ChatGPT, Claude, and Gemini through a corporate proxy. The user signs in daily via browser or CLI. Direct app API calls require separate approval, so the chosen first integration is existing logged-in CLIs, with the browser/clipboard route retained as fallback. A screen/context snapshot is taken on send or explicit refresh, not continuously. Provider capability must be tested in the actual corporate environment before enabling/claiming it.

**Deferred by the user:** internal Samsung Motion Photo, SEF, and Gallery conformance rules. They will provide those later. Keep a future `SAMSUNG_INTERNAL` rule profile in mind, but do not invent or implement private pass/fail rules from examples. Public/verified structure may still be drawn. The deferral is also recorded in `docs/superpowers/specs/2026-10-14-contextual-media-analysis-design.md`.

## Repository and workspace

- Main checkout: `/Users/dong.kim/AndroidStudioProjects/multiViewer`.
- Isolated worktree: `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/contextual-analysis`.
- Branch: `feat/contextual-analysis`, based on `main` commit `91daca1` (at worktree creation). Do **not** start over on `main` and do **not** push without the user's go-ahead.
- Repository `AGENTS.md` is binding: Korean communication, design → plan → TDD tasks/reviews → whole-branch review → fast-forward merge; Material3, JDK 21, no heavy deps, cancellation/timeout for external processes, Windows path/lock checks, bilingual strings, and no false PASS.
- SDD task progress is in the ignored worktree-local ledger `.superpowers/sdd/2026-10-14-spec-conformance/progress.md`. Read it first; do not redispatch completed tasks. The task briefs/reports/review packages live beside it.

## Plans and current implementation

- Integrated approved design: `docs/superpowers/specs/2026-10-14-contextual-media-analysis-design.md` (committed in `6251a8c`).
- Spec conformance plan: `docs/superpowers/plans/2026-10-14-spec-conformance.md` (committed in `6251a8c`).
- Structure diagram plan: `docs/superpowers/plans/2026-10-14-file-structure-diagram.md` (written, not yet implemented at this checkpoint).
- Contextual AI plan: `docs/superpowers/plans/2026-10-14-contextual-ai-panel.md` (written, not yet implemented at this checkpoint).

Conformance Task 1 is committed as `d51616a feat: add conformance rule model and catalog`; independent task review approved. Task 2 is committed as `61467f2 feat: normalize conformance findings with coverage`; independent task review approved. Task 3 is committed as `573400c feat: show conformance coverage in content check`; independent task review approved. Whole-feature review found two Important issues. A fix agent investigated but was stopped immediately on the user's handoff request; it made **no code edits or fix commit**. Treat conformance as **incomplete** until both findings are fixed and re-reviewed.

Task 1/2 behavior: fixed inventories for existing image/audio structure checks; PNG cites W3C PNG Third Edition but only for conditions actually checked, other inventories explicitly say `unwrapMedia structural check`. `ConformanceReport.overall` prioritizes FAIL, WARN, NOT_CHECKED, then PASS. Empty/all-skipped/unsupported input cannot pass; inspection errors have an explicit guard while completed observations remain visible. A missing conditional rule ID stays NOT_CHECKED, so a healthy PNG may intentionally show NOT_CHECKED overall even with several PASS findings. Parser warnings remain separate evidence, not invented spec rules. Duplicated rule IDs keep the worst status and every valid byte range.

## Test evidence so far

- Baseline before implementation: 1652 tests; only documented intermittent `ThumbnailDiskCacheTest` failed; isolated rerun passed.
- Task 1: TDD RED; 10 focused tests GREEN. Full suite 1662 tests, same flaky cache test only; isolated 5 cache tests GREEN.
- Task 2: TDD RED; 8 analyzer tests plus existing integrity tests GREEN. Full suite 1670 tests, same flaky cache test only; isolated rerun GREEN.
- Task 3: TDD RED 8 tests with the expected missing-tab failure; compile and 49 focused tests GREEN. Final full suite: 1674 tests, 0 failures/errors/skips. Independent task review approved; noted no direct same-file-two-tab/close-race UI lifecycle test.
- Full GUI and Windows manual acceptance remain to be recorded. Do not claim them complete based on macOS unit tests.

Gradle commands are `./gradlew :app:test`, focused `./gradlew :app:test --tests 'package.TestClass'`, and `./gradlew :app:compileKotlin`. In this sandbox Gradle needs approved access to the user Gradle cache; an initial wrapper-lock failure is infrastructure, not TDD RED.

## Next work, in order

1. Finish the one final-review fix wave for conformance. Broad review found: (a) external modification leaves stale UI/AI PASS and byte evidence because no size/mtime identity invalidation; (b) closing during a large PNG CRC scan does not interrupt the read loop. The stopped fix agent's diagnosis was: snapshot needs file identity; the AI preview currently builds its prompt in a UI-thread `remember`; image reads need a cooperative interrupt check and cancellation must escape the checker rather than become a normal FAIL. Suggested touch points: `ContentCheckWindow.kt`, `ContentCheckSnapshot.kt`, `AnalysisWindows.kt`, `ByteReader.kt`, `ImageIntegrityChecker.kt`, tests. This is a proposal, not implemented or validated. Write RED tests, fix both, run focused and full tests, then one scoped re-review. Record outcome in SDD ledger. GUI/Windows and same-file-two-tab lifecycle manual checks remain open.
2. The handoff and both follow-on plan documents are committed as an intentional docs-only change; check Git history for the exact commit. Do not treat the docs commit as feature completion.
3. Execute structure-diagram plan on this branch, task by task with TDD and review. Real fixture `docs/showcase-media/images/campfire-motion-photo.jpg` is 1,041,843 bytes; independent raw inspection found `ftyp` at 237,772, MP4 box start 237,768, SEFT marker 1,041,839. Verify again from raw bytes. Never draw an embedded-video relationship without parser-verified range. Default exports exclude paths/GPS/serial/payload. SVG/PNG and Markdown table share one model; test clipboard paste on available platforms.
4. Execute contextual AI plan. Existing `AiDiagnosticPromptBuilder`, `AiCliDetector`, `AiPromptPreviewWindow`, and browser/clipboard workflow are starting points, not two-way chat. Installed local CLIs (`codex`, `claude`, `gemini`) expose noninteractive invocation, but actual auth/image/web/tool support in corporate proxy remains unverified. Use fake CLI executables for automated tests, then non-sensitive files for live acceptance. Keep outgoing preview, per-tab conversation, process timeout/cancel, read-only bounded bridge, and evidence links.
5. Whole-branch review and final verification. Decide merge timing with the user/Claude; do not push. Keep the internal Samsung rules as a separate later feature after user documents/fixtures arrive.

## Pause snapshot

- The user's last instruction was to transfer the work to Claude now. All coding/review agents were stopped. No conformance fix code is in progress or staged at this snapshot.
- Latest implementation commit is `573400c` on `feat/contextual-analysis`. No merge to `main`, no push, and no version bump was performed.
- The latest full test run (at `573400c`) passed 1,674/1,674, but the whole-feature review still blocks completion on the two issues above. Earlier full runs intermittently failed only `ThumbnailDiskCacheTest`, which passed alone.
- For exact per-task reports and decisions, read `.superpowers/sdd/2026-10-14-spec-conformance/progress.md` in the worktree and the three `task-*-report.md` files. The SDD directory is intentionally Git-ignored and exists only in this local worktree.

## Important implementation rulings

- Task 2 could add defaulted `ConformanceReport.inspectionError` beyond its original file list, because an all-PASS observation set plus failed inspection otherwise false-passed. Cost if wrong: one redundant model field.
- Empty rule catalog with unknown PASS observations stays NOT_CHECKED and keeps those observations visible. Cost if wrong: conservative understatement of coverage, not a false conformance claim.
- Samsung internal Motion Photo/SEF/Gallery rules are deferred. Cost: partial coverage in these domains until the user provides source rules.

## External references checked

CLI docs: [Codex noninteractive](https://developers.openai.com/codex/noninteractive), [Claude headless](https://code.claude.com/docs/en/headless), [Gemini headless](https://geminicli.com/docs/cli/headless/). These establish generic CLI capabilities, not corporate tenant authorization. PNG reference: [W3C PNG Third Edition](https://www.w3.org/TR/png-3/).
