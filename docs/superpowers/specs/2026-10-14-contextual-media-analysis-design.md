# Contextual Media Analysis — Design

Date: 2026-10-14

## Intent

Make unwrapMedia the engineer's workspace for understanding an opened media file, proving which checks it passed, sharing its actual structure, and discussing a focused issue with an AI agent. The active file and the user's current selection are the common context for all three features.

## Scope and order

Deliver three independently useful increments in this order:

1. Specification conformance report, integrated with Content Check.
2. Shareable structure diagram and table for the opened file.
3. Collapsible, context-aware AI panel backed initially by the already logged-in enterprise CLIs (Codex, Claude Code, Gemini CLI).

Samsung internal Motion Photo, SEF, and Gallery rules are **deferred** until the user supplies their rule documents or check lists. The rule model must allow a later `SAMSUNG_INTERNAL` profile without pretending those rules have been checked now.

## Shared context

All operations are pinned to a `TabState` and file identity (path, size, last-modified time; SHA-256 when producing a shareable report). Switching tabs switches the visible AI conversation and diagram target. Closing a tab cancels its running analyses and CLI process. Files changed on disk invalidate prior conformance and AI evidence. The user's selection includes the structure node and field, video frame/PTS, current visible viewport, and an optional pointed-to position or marked region. A screen capture for AI excludes the AI panel and is taken when the user sends a message or explicitly refreshes context; it does not stream continuously.

## 1. Specification conformance

The existing Content Check remains the place for structural integrity and decode results. Add a **규격 적합성 / Spec conformance** page that names the applicable format, profile, and version, then reports a fixed inventory of checks. Each check has a stable ID, reference to a published rule or an explicitly named application rule, checked byte range or frame where applicable, expected condition, observed value, and result `PASS`, `FAIL`, `WARN`, `NOT_CHECKED`, or `NOT_APPLICABLE`.

The report headline must say **“implemented checks passed”** with counts and coverage, never “the file complies with every specification.” Empty input, truncated data, unsupported profiles, skipped rules, failed or cancelled analysis must never yield a green overall result. Decode success, parser warnings, format conformance, and device compatibility remain distinct evidence categories. An AI answer may explain a result but cannot create or override a deterministic pass/fail verdict.

Initial rule inventory reuses independently verified existing structure checks for JPEG, PNG, HEIF, WebP, GIF, BMP, TIFF/RAW and supported audio formats; adds explicit container-level rules where the parser already exposes reliable offsets. An unsupported format gets a coverage report with `NOT_CHECKED` and no pass verdict. Rule references are versioned; where a normative document is unavailable or licensed, label a rule as an implementation/compatibility check rather than invent a clause number. Real-file fixtures and byte-level assertions are required for every newly added parser-level rule. The UI links each located result to the tree, Hex viewer, image, or frame timeline.

## 2. File structure diagram

Provide **파일 구조도 / File structure diagram** for the active tab. Its source is the parsed `BoxNode` tree, embedded media ranges, and verified motion-photo references, rather than AI-generated guesses. Two synchronized views serve different questions:

- **Physical map:** file byte ranges in order, offset and length, including gaps/overlaps or unparsed tails. Do not scale tiny metadata into illegibility; use a labeled, not-to-scale overview with exact numeric ranges.
- **Relationship view:** hierarchy and semantic links (for example JPEG still image, XMP motion-photo declaration, embedded MP4, Samsung SEF trailer and referenced payload). Only draw a link when the parser has evidence; otherwise mark it unknown.

The diagram supports overview/detail depth, selected-node emphasis, and a companion table with `component`, `offset`, `length`, `role`, and `status`. Large sample tables are summarized with counts rather than one shape per sample. Export the same underlying diagram data as SVG and PNG; copy an image directly to the clipboard for slides/email, or copy a Markdown table for text-based sharing. Default shared output contains the file's structural facts but omits local absolute paths, GPS coordinates, device serials, and raw image/video payload. Include the file hash and parser version in an optional technical footer. For invalid or partly parsed files, visibly mark unverified ranges.

## 3. Context-aware AI panel

The app starts as it does today. A file tab's **AI와 대화 / Ask AI** action opens a collapsible right-side panel. The panel remembers one conversation per open file, provider choice, and message history; it never silently changes the conversation target to another file. A message can include structured analysis results, the selected node/field, selected frame and PTS, a crop of the visible content, the mouse pointer marker, and an explicit user-marked region. The exact outgoing context is previewable before submission.

Begin with enterprise CLI credentials already used by the user, without assuming API access. Each CLI adapter must validate installed version, authenticated noninteractive invocation, text/image input support, output streaming, cancellation, and session continuity in the user's actual corporate proxy environment. If a provider lacks a capability, disable that capability visibly rather than claim it worked. Keep the existing browser/clipboard AI workflow available as a fallback. API provider adapters can be added later if the company approves them, without changing the panel's conversation or evidence model.

An app-owned, read-only tool bridge lets the agent request targeted data and analyses: inspect structure/fields/hex ranges, extract selected image or frame, run Content Check or conformance, inspect Motion Photo/SEF/HDR/A-V reports, and ask for a refreshed screen context. The bridge uses stable typed requests and bounded outputs; it does not grant a CLI arbitrary write access to media or the app's project directory. Web search uses whichever approved provider capability is available and includes source links in the answer. Agent actions and their tool results appear in an activity trail. Claims in the response link back to exact file offsets, frames, check IDs, or web sources, and separate observed facts from hypotheses.

## UI and interaction

The structure diagram and conformance report are normal analysis views for the active tab. The AI panel can be opened alongside them, so a user may ask “why did this check fail?” or “explain the selected block for another department.” A click on an answer's evidence reference selects the corresponding tree node or frame using existing selection-driven highlighting. Pointing at a pixel or frame for an AI question adds an annotation to the outgoing context; it does not change structural highlighting rules.

User-facing strings are Korean and English. Heavy parsing, rendering, CLI work, screenshots, and export run off the UI thread and cancel with tab/window closure. External processes have enforced timeouts. Windows paths and file locks are part of verification. No new heavy dependency is required.

## Acceptance examples

- A real Samsung or Google motion photo produces a diagram of its actual still/XMP/video/trailer layout with exact ranges, and the clipboard image can be pasted into a slide.
- A truncated or unsupported file reports failed/not-checked coverage rather than a green pass.
- A selected conformance failure opens the relevant byte/frame evidence and can be discussed in the AI panel without losing the target file when the user changes tabs.
- Asking “what is wrong at this point?” sends the current visible content, pointed-to region, selected frame and machine-checked results; the agent can request an additional read-only check and cite its result.
- A provider that cannot authenticate or accept an image in the corporate environment reports that limitation and leaves the existing browser/clipboard route usable.

## Deferred work

- Samsung internal Motion Photo, SEF, and Gallery rule profiles: wait for user-supplied rule documents/check lists and real-file fixtures. Do not infer private rules from a few samples.
- Full normative coverage for every codec, container, and profile: grow versioned rule inventories with evidence. The coverage display is permanent so partial support stays honest.
- API-backed AI adapters: require company approval and credentials; the CLI-backed design does not depend on them.
