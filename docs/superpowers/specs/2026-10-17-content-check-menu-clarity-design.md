# Content Check Menu Clarity Design

## Goal

Make the Content Check window tell a media engineer what ran, what remains uninspected, and where to investigate a result. Keep all checker verdicts, execution timing, byte ranges, AI context, and exported JSON unchanged. This design follows the approved three improvements: grouped status summary, explicit run/export actions, and useful result filters and evidence.

## Current behavior

`ContentCheckWindow` opens with automatic structure and spec-conformance checks. The `검사 시작 / Start inspection` button starts the applicable decode, packet, audio, or motion-photo work. Its header currently places every tab status in one wrapping row, while a second row holds five actions. `ContentFindingTable` (motion photo and gain map) now shows all findings by default, but its checkbox combines pass and unverified into one toggle. Finding IDs are in the data model yet not shown in the expanded row.

## Window layout

Keep the existing file name and media type/format at the top. Replace the single ungrouped status row with two compact, wrapping groups:

| Group | Applicable tabs | Meaning |
| --- | --- | --- |
| `기본 검사 / On open` | Structure, spec conformance, gain map when detected | Runs when the window opens or the parsed tree becomes available. |
| `추가 검사 / On request` | Image decode, motion photo, video decode, packet mapping, audio decode as applicable | Runs when the user starts the inspection. |

Each group lists only applicable stages with their current status. Preserve the existing status labels and colors, including `미검증 / Not checked`, `미검사 / Not run`, and `해당 없음 / Not applicable`. During loading, show `검사 중 / Checking` instead of an apparent pass. Do not calculate or display a global `PASS`: one stage passing does not prove complete conformance or that another stage ran. Keep the existing tab row and its navigation behavior.

## Actions

The primary action states exactly what it will run:

| Media | KO | EN |
| --- | --- | --- |
| Image | `이미지 디코딩 검사 실행` | `Run image decode check` |
| Image with motion photo | `이미지 디코딩·모션포토 검사 실행` | `Run image decode and motion-photo checks` |
| Video | `영상·패킷·오디오 검사 실행` | `Run video, packet, and audio checks` |
| Audio | `오디오 디코딩 검사 실행` | `Run audio decode check` |
| RAW pixel or unknown | No run button; retain the existing structure-only explanation. | Same. |

Show `취소 / Cancel` only while a heavy run is active. Keep progress and completion/error text below the actions. Keep `AI 진단 / AI diagnosis` as a separate action. Replace the two export buttons with a Material3 `결과 내보내기 / Export results` menu containing `JSON 복사 / Copy JSON` and `분석 케이스 저장 / Save analysis case`. Preserve each action's current enable rules, background work, cancellation, error messages, and no-overwrite save behavior. Opening the menu never starts an export.

## Findings table

Replace the combined checkbox with one selected Material3 filter chip among:

| Filter | Included statuses | Empty state |
| --- | --- | --- |
| `전체 / All` (default) | FAIL, WARN, INFO, SKIP, PASS | Say that no check results are available; never imply a pass. |
| `문제만 / Issues` | FAIL, WARN | Say that no failures or warnings match, with total/pass-info/unverified counts. |
| `미검증 / Unverified` | SKIP | Say that no unverified items match, with the total count. |

Preserve FAIL → WARN → INFO/SKIP → PASS ordering within `All`, and byte-offset ordering within each status. The summary bar always counts the complete input, independent of the filter. `PASS` and `INFO` remain separately labeled where displayed; a combined summary count is labeled `정상·정보 / Pass/info`, never just `PASS`. Filtering never modifies the authoritative report or exported JSON.

The existing `상태 | 검사 | 위치 | 결과` columns remain. Expanding a row additionally shows its stable check ID in monospace before verified facts, hypotheses, and next checks. The existing `Hex에서 바이트 범위 선택 / Select byte range in Hex` action remains the route to the tree-driven highlight range; do not add a new click gesture to the location column. Invalid or absent ranges remain unavailable.

## State and implementation boundary

- Keep `ContentCheckWindow` as the owner of inspection jobs and result state. Extract only small presentation helpers for stage grouping and context-specific run labels so they can be tested without Compose.
- Introduce a UI-only `ContentFindingFilter` in the findings presentation. Keep normalized finding IDs and report models unchanged. If existing callers/tests need the `showAll` helper, retain it as a compatibility wrapper while the table uses the new filter.
- Keep per-window/tab state scoped to the target `TabState`. Do not persist filter selection across files. The filter starts at `All` whenever a new findings table is created.
- Bilingual strings use the existing `label(korean, english)` pattern or the table's `ko` parameter. Material3 components only.

## Verification

1. Pure tests: stage grouping for image/video/audio/unknown, including not-run and not-checked states; run-label mapping; filter membership, ordering, counts, and empty states. Test that an empty report cannot yield a pass claim.
2. Focused Content Check tests, then `./gradlew :app:test` on the completed branch.
3. Manual UI pass when a desktop display is available: image with and without motion photo, video with and without audio, audio-only, and unsupported type; verify the menu actions, filter chips, and expanded evidence. If the current agent session cannot inspect the desktop, report that limitation instead of claiming a visual pass.

## Out of scope

- New parser or conformance rules, changes to inspection triggers or supported formats, new export schemas, automatic AI diagnosis, or any claim that an unchecked stage passed.
