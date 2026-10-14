# Contextual AI Panel Implementation Plan

> Execute after `2026-10-14-spec-conformance.md` and `2026-10-14-file-structure-diagram.md`. Use TDD for each model/adapter task, review each task, then review the whole branch. Do not push without the user's approval.

**Goal:** Let the user discuss the focused media file and the app's verified findings in a collapsible right panel. Begin with the enterprise CLIs already used through daily login; API access is a later, separately approved adapter.

**Design:** `docs/superpowers/specs/2026-10-14-contextual-media-analysis-design.md`, section 3. Existing `AiDiagnosticPromptBuilder`, `AiCliDetector`, and browser/clipboard diagnosis remain as a fallback.

**Hard limits:** A provider answer never changes deterministic PASS/FAIL. The app shows exactly what data will leave through a CLI before sending. No implicit full-file upload, path/serial/GPS leakage, arbitrary write tool, or silent provider capability assumption. A missing login, unsupported image input, unavailable web search, timeout, or closed tab is a visible incomplete action, not a fabricated answer. All user-facing strings are KO/EN. Never block Compose's UI thread on process or file I/O.

## Task 1: Per-tab conversation and bounded evidence snapshot

**Files:** Add `ui/ai/AiConversation.kt`, `ui/ai/AiContextSnapshot.kt`, tests under `ui/ai/`; extend `TabState` in `ui/AppState.kt` only for per-tab state.

- Define typed message roles, provider, timestamp, source references, activity events, and run state. Conversation state belongs to the `TabState` instance, never to a global selected-tab index.
- Build a snapshot from current file identity (path used locally, size, modified time; path scrubbed from outgoing text), selected `BoxNode`/field, selected frame/PTS, `ContentCheckSnapshot` including conformance coverage, and current structure-diagram evidence. Bound node/field count, text length, and byte reads. Track omitted counts.
- Write RED tests for tab switching, a file modified after snapshot, sensitive path/GPS/serial redaction, bounded output, and a FAIL finding that AI cannot promote to PASS. Implement and run GREEN.
- Commit `feat: capture bounded per-file ai context`.

## Task 2: CLI adapters, capability probe, and cancellation

**Files:** Add `ui/ai/AiCliAdapter.kt`, `ui/ai/AiCliRunner.kt`, `ui/ai/AiProviderCapabilities.kt`; tests with fake executables/scripts. Reuse `ProcessOutputReader.kt` timeout patterns.

- Support installed Codex, Claude Code, and Gemini CLI behind one adapter interface. Probe executable/version and authenticated noninteractive text operation independently; record image, session-continuity, streaming, web-search, and tool-bridge support as verified/unsupported/unknown. Never infer capability from binary presence alone. Do not force a live enterprise request in automated tests.
- Build argument lists without shell interpolation; run in a restricted temporary working directory, with enforced wall timeout, bounded stdout/stderr, cancellation that terminates the process tree where supported, and cleanup of only app-owned temporary files. Keep user files read-only and do not expose repository write access. Resume provider sessions only after a verified adapter test; otherwise send bounded conversation history explicitly.
- Write RED tests for spaces/non-ASCII Windows paths, failed login, unsupported image capability, hung process, oversized output, cancellation, and stale file identity; then GREEN. Document exact CLI versions tested locally and the still-required corporate-proxy acceptance test.
- Commit `feat: run enterprise ai cli adapters safely`.

## Task 3: Collapsible right panel and text conversation

**Files:** Add `ui/ai/AiChatPanel.kt`; modify `Main.kt`, `ui/AppState.kt`, and menu wiring; UI-state tests where practical.

- Closed by default. `AI와 대화 / Ask AI` opens a resizable right panel adjacent to the focused file view, not another file tab or separate terminal. Switching tabs shows that tab's own conversation; closing a tab cancels its process. The target file name and stale/changed-file warning stay visible.
- Show provider/capability status, transcript, streaming response, send/stop/retry, and an outgoing-context preview. New sends capture current selection at send time. Existing browser/clipboard workflow remains reachable when the CLI cannot authenticate or a capability is missing.
- Write state tests first: independent histories, send-time selection, close cancellation, stale response from previous tab not rendered under current tab. Implement and compile; manually inspect panel sizes, narrow window, Korean/English, macOS and Windows when available.
- Commit `feat: add per-file contextual ai sidebar`.

## Task 4: Visible-content annotation and image context

**Files:** Add `ui/ai/AiVisualContext.kt` and selection overlay integration in the image/video preview components; tests for coordinate mapping and crop bounds.

- At send or explicit refresh, capture only the visible content viewport, excluding the AI panel and unrelated desktop windows. Carry viewport dimensions, pointer position, optional marked rectangle, active frame/PTS, and an image crop only when the selected provider's image capability has been verified. Show the outgoing preview and require the user's send action; never stream captures continuously.
- If screenshot/crop is unsupported on a platform or provider, send structured coordinates and selection evidence only, visibly label the omitted image. Avoid uploading raw full media files by default.
- RED/GREEN tests for letterboxing, zoom/pan, out-of-bounds pointer, HiDPI scaling, frame change before send, and capture redaction. Manual verification on available OSes.
- Commit `feat: attach focused visual context to ai chat`.

## Task 5: Bounded read-only analysis bridge and sourced web results

**Files:** Add `ui/ai/AiToolBridge.kt`, `ui/ai/AiEvidenceRef.kt`, provider-specific bridge configuration, tests.

- Allow only typed read-only requests with size/time limits: selected node/field and bounded hex range, current frame fact, Content Check/conformance result, structure-diagram region, and existing Motion Photo/SEF/HDR/A-V reports. Reuse existing analyzers; never let the CLI execute arbitrary app commands or mutate/open a different file without user action. Log each request/result in the activity trail.
- Web search is enabled only where the corporate-approved provider capability is verified. Display clickable source URLs and distinguish external claims from byte/frame observations. If unavailable, say so and offer the browser fallback.
- RED/GREEN tests for denied write/unknown tool, out-of-file range, cross-tab request, changed file, bounded output, cancellation, and evidence link to existing tree/frame selection. Run full suite and manually exercise with a non-sensitive test file in the actual corporate proxy environment before claiming provider support.
- Commit `feat: add bounded ai analysis tools and evidence links`.

## Completion checks

- Run `./gradlew :app:test`; if only the documented intermittent `ThumbnailDiskCacheTest` fails, rerun it alone and record both results.
- Review privacy of outgoing context, process permissions, timeout/cancellation, per-tab pinning, source links, and no false conformance claims. Use a fake CLI for automated tests and a non-sensitive fixture for corporate-login manual acceptance. Record unverified platform/provider capabilities explicitly.
- Whole-branch review, then fast-forward merge per repository workflow only after the user confirms the handoff/merge timing. Never push automatically.
