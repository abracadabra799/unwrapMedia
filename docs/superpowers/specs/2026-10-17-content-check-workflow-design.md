# Content Check Workflow Redesign

## Goal and boundary

Give a Galaxy multimedia framework and codec engineer one compact place to start applicable checks, watch each check progress, and jump from a finding to its exact evidence. Opening the window must not run Content Check jobs. The app may continue parsing a file for its ordinary tree and viewer. This spec changes orchestration and presentation around existing checks; a separate codec-rule spec defines new bitstream validation. The UI must not advertise a bitstream check as executable until a real runner is installed.

## Current behavior and duplication

`ContentCheckWindow` currently runs structure/conformance on open, validates gain maps from derived state, and runs image/video/audio/motion checks from one later action. A wrapping status header duplicates the result tabs. `ConformancePanel` maps existing structure findings into a rule inventory; it does not parse complete codec syntax. The video-integrity run already includes both ffprobe packet enumeration and ffmpeg frame decoding. `모션포토 검사 / Motion Photo Check` is another menu route into the same Content Check window. Gain Map Analyzer is a separate, broader preview and metadata tool.

## Entry and layout

- Keep one `분석 > 컨텐츠 검사 / Analysis > Content Check` entry. Remove the redundant `모션포토 검사 / Motion Photo Check` menu entry after making the conditional motion-photo row discoverable in Content Check. Keep Gain Map Analyzer because it provides preview and metadata exploration outside this check workflow; reuse its validation result when available rather than rerunning it.
- The window remains pinned to the tab that opened it. Header: file name, recognized container/format, video codec and profile only when known, plus an explicit message while the app's ordinary parse tree is loading. Do not infer a profile from the extension alone.
- At 1080×780 default size, use a 260–300 dp left checklist and a remaining-width detail pane. The header contains `모든 검사 실행 / Run all checks`, `취소 / Cancel` only during a run, the existing export menu, and AI diagnosis. The checklist and detail pane scroll independently; resizing must not clip actions.
- The checklist has two visible groups: `규격 검사 / Conformance checks` and `동작 진단 / Operation checks`. Each row has a short KO/EN description, execution state, verdict/count when completed, progress when running, and one `실행 / Run` or `다시 실행 / Rerun` action. Selecting a row only changes the detail pane; it never starts work.
- The conformance result is the summary of evaluated rules, shown in the header/detail pane, not a third executable row. Container/codec consistency rules live with the evidence-producing checks and in that summary, without their own run button. Packet mapping is a detail view of video operation diagnostics, without a separate top-level row.

## Checklist and copy

| Row | KO description | EN description | Availability |
| --- | --- | --- | --- |
| `파일 구조 / File structure` | `박스·청크 배치와 필수 데이터의 위치·범위를 검사합니다.` | `Checks box and chunk layout and the location and extent of required data.` | Any parsed file; exact rules depend on format. |
| `코덱 비트스트림 / Codec bitstream` | `코덱 구문, 값 범위, 파라미터 간 참조를 검사합니다.` | `Checks codec syntax, value ranges, and parameter references.` | Video with an installed codec runner and supported sample layout. Otherwise show disabled `미지원 / Unsupported` with a reason; never PASS. |
| `영상·이미지 디코딩 / Video or image decode` | `압축 데이터를 실제로 디코딩하고 오류를 확인합니다.` | `Decodes compressed data and checks for errors.` | Image or video as applicable. |
| `오디오 디코딩 / Audio decode` | `오디오 트랙을 디코딩하고 오류를 확인합니다.` | `Decodes audio tracks and checks for errors.` | Audio files or files with audio tracks. No track is `해당 없음 / Not applicable`, not PASS. |
| `모션포토 / Motion photo` | `정지 이미지와 포함된 영상의 연결·시점을 확인합니다.` | `Checks the link and timing between the still image and embedded video.` | Only detected motion photos; reuse existing analyzer/report. |
| `게인맵 / Gain map` | `게인맵 데이터와 메타데이터의 구조·값을 확인합니다.` | `Checks gain-map data and metadata structure and values.` | Only detected gain maps; reuse existing validation/report. |

The motion-photo and gain-map rows form a conditional `확장 검사 / Additional checks` group only when present. The video detail pane retains frame errors, packet positions/sizes/PTS/DTS/keyframe flags, logs, and Hex navigation. Label that subsection `영상 패킷 정보 / Video packet information`, with copy `패킷의 위치·크기·시간 정보를 읽고 오류를 확인합니다. / Reads packet position, size, and timing information and checks for errors.` Do not claim that enumerating packets proves timing conformance.

## Run behavior

- No Content Check stage runs merely because the popup opened. While the ordinary file tree is unavailable, run actions are disabled with `파일 구조 불러오는 중 / Loading file structure`; no false failure is shown.
- `Run all checks` builds a list of applicable, supported rows for that file and executes them sequentially: file structure, installed codec bitstream rules, video/image decode, audio decode, then conditional motion-photo and gain-map checks. It skips inapplicable stages with an explicit reason. Individual row actions run only the selected row and any required prerequisite data read; they do not silently run unrelated stages.
- A row transitions `미검사 / Not run` → `대기 / Queued` → `검사 중 / Running` → `완료 / Completed`, `부분 검사 / Partial`, `실패 / Failed`, or `취소 / Cancelled`. Execution state and finding verdict are distinct: `Completed` can contain WARN/FAIL, while `Failed` means the checker itself could not finish. `Unsupported` is distinct from `Not applicable`.
- Progress uses exact `checked/total samples`, `frames`, `tracks`, or bytes only when the total is trustworthy. Otherwise show an indeterminate bar plus the current stage and processed count, not an invented percent. Update progress at a bounded cadence to avoid Compose recomposition for every packet/frame.
- Publish each completed row's findings immediately; the next row starts without hiding or resetting them. A user can inspect completed rows while a later row runs. Do not change the selected detail pane while the user is reading it; initially select the active row, then leave manual selection alone.
- Cancel stops the active job and prevents queued rows from starting. Completed rows remain available and are marked with the file identity they describe. A failed row does not block independent later rows; dependent rules become `미검증 / Not checked` with a specific prerequisite reason.
- Rerunning a row invalidates only that row and derived conclusions that depend on it, then updates the conformance summary. A changed file invalidates all results and disables export/AI use until a new run binds the new bytes. Closing the window cancels its jobs.

## Verdict and evidence

- Conformance counts contain only checks tied to a documented normative rule and declared scope. Existing `unwrapMedia structural check` observations remain visible as structural findings, counted separately from normative coverage until their exact standard requirement is verified. The detail shows stable rule ID, standard/source and edition when known, applied profile, observed and expected values, track/sample/frame/NAL index where relevant, and a byte range when available. The Hex action follows the existing tree-driven highlight path.
- Operation diagnostics stay separate from normative conformance counts. A successful ffmpeg decode or ffprobe packet read cannot turn a conformance rule PASS. MediaCodec impact is an explicitly labeled engineering hypothesis unless observed on a device; no device compatibility verdict is inferred from desktop decode.
- Show counts for violations, warnings, covered PASS, not-checked, and not-applicable rules. Empty, truncated, unsupported, cancelled, or failed checks never produce an overall PASS. If some implemented rules remain unchecked, use `검사 완료 · 일부 미검증 / Completed · some rules not checked`; never claim full specification compliance.
- Keep the current All/Issues/Unverified finding filters and `Check ID` evidence, and apply the filter to the detail pane without changing authoritative reports. Show one useful empty state per filter.

## Integration, export, and reuse

- Reuse `contentStructureReport`, the existing conformance rule inventory, `inspectImageDecode`, `inspectVideoIntegrity`, `inspectAudioIntegrity`, `MotionPhotoIntegrityAnalyzer`, and gain-map validation. Do not create another packet scan, motion-photo analysis, or gain-map parser.
- Preserve existing JSON fields and case-save behavior (`CREATE_NEW`). Add stage execution/coverage metadata only under a versioned additive field so existing consumers continue reading the old fields. Clipboard and save work stay off the UI thread.
- Publish a bounded, file-identity-checked `ContentCheckSnapshot` to AI only for completed evidence; preserve path scrubbing. A running or skipped row is described as such, not as clean.
- Bilingual KO/EN strings, Material3 only, no new heavy dependencies. A standalone check must be cancellable and honor existing per-tab pinning. Gate video sample reads on `MediaType.VIDEO` and the codec configuration box so HEIC motion photos are never scanned as ordinary video.

## Benchmark interpretation

- Use MediaConch's separation of implementation checks, policy checks, and reporting as a model for distinguishing rule verdicts from user or device policy. Use Bento4's structural dump/JSON and ffprobe's stream/packet views as evidence cross-checks, not as normative truth. Record disagreements in raw byte fixtures before changing a rule.
- References: [MediaConch](https://github.com/MediaArea/MediaConch), [Bento4 mp4dump](https://www.bento4.com/documentation/mp4dump/), [ffprobe documentation](https://ffmpeg.org/ffprobe.html).

## Verification and delivery boundary

- Unit tests cover applicability, bilingual descriptions, execution state transitions, run order, cancellation, prerequisite failure, partial coverage, and no false PASS. Integration tests prove file-identity invalidation, stable snapshots, export backward compatibility, and result reuse.
- Manual desktop QA covers image (with/without motion photo and gain map), MP4/MOV video (with/without audio), audio-only, unsupported codec, truncated file, and a long run that is cancelled. Confirm progress, early result visibility, row navigation, and the absence of duplicate work.
- Deliver this workflow independently of a new codec parser. Until a codec-rule runner exists, the bitstream row is visibly unsupported and excluded from `Run all checks`; the conformance summary names the uncovered codec scope. The companion HEVC design supplies the first real runner. Do not remove or break the existing specialized Gain Map Analyzer.
