# Video Filmstrip Linkage and Popup Inspection Design

**Date:** 2026-10-16  
**Status:** Approved  

## Problem

In `VideoInspectorUI`:
1. **Unclear Filmstrip Click Behavior:** Clicking a thumbnail in `FrameThumbnailFilmstrip` immediately pops up a separate playback inspection window (`FrameFullSizePreviewWindow`). Users have no prior indication or visual clue that clicking the filmstrip will open a popup window.
2. **Disconnected Live Player & GOP/Filmstrip:** The left "LIVE PLAYER" (`FfmpegVideoPlayer`) and the right GOP analysis / filmstrip panels operate completely independently:
   - Clicking a GOP bar or filmstrip thumbnail does not seek the live player.
   - When the filmstrip is clicked, the popup opens and the live player is unmounted ("팝업에서 동영상 검사 중"), creating redundancy between the two players and breaking forensic workflow (engineers cannot quickly compare GOP spikes with the video frame on the main screen without opening/closing popups).
   - Pausing the live player does not identify or highlight which frame in the GOP timeline the video stopped on.

## Goals

1. **Clear UI Cue for Filmstrip:** Add a clean, compact sub-header above the filmstrip that displays a bilingual description of the interaction and provides an explicit button/chip to open the inspection popup.
2. **Interaction Hierarchy (Single Click vs Double Click / Button):**
   - **Single Click** on GOP bar or Filmstrip thumbnail: Selects the frame and seeks the live player to that frame's PTS in paused state. Users can inspect any frame in the main player instantly without modal disruption.
   - **Double Click** on Filmstrip thumbnail OR clicking the **`[ ↗ 정밀 검사 팝업 / Popup Inspector ]`** chip: Opens `FrameFullSizePreviewWindow` for deep pixel zoom, PTS interval analysis, and step-by-step forensic inspection.
3. **Smart Player Sync on Pause (Zero Overhead During Playback):**
   - When the live player is paused, synchronize `tab.selectedFrame` to the current playback position.
   - During continuous playback, do NOT trigger continuous scrolling or heavy recomposition of the GOP/filmstrip (guaranteeing 0% overhead and preventing UI jitter).

## Technical Details

### 1. Filmstrip Sub-Header UI (`FrameThumbnailFilmstrip.kt` or `VideoInspectorUI.kt`)
Above the filmstrip LazyRow:
- Row with height ~26dp:
  - Left: `🎞️ 프레임 필름스트립` / `🎞️ Frame Filmstrip` (bold label) + `• 클릭: 플레이어 이동 · 더블클릭: 팝업 검사기` / `• Click: seek player · Double-click: popup inspector` (secondary caption).
  - Right: `AssistChip` labeled `정밀 검사 팝업 ↗` / `Popup Inspector ↗` triggering `tab.fullSizeFramePreviewOpen = true`.

### 2. Double-Click Support in Filmstrip
Use Compose Foundation's `combinedClickable`:
- `onClick`: Requests focus, updates `tab.selectedFrame`, sets `tab.seekTargetSeconds = frame.ptsSeconds`, increments `tab.seekRequestTick`.
- `onDoubleClick`: Same as single click + sets `tab.fullSizeFramePreviewOpen = true`.

### 3. Live Player Wiring (`VideoInspectorUI.kt`)
In `VideoInspectorUI.kt`, wire the following into `FfmpegVideoPlayer`:
- `seekRequestSeconds = tab.seekTargetSeconds`
- `seekRequestTick = tab.seekRequestTick`
- `onPlaybackStateChanged`: When transitioning from playing to paused, map the current playback position to the nearest `FrameInfo` from `tab.gopFrames` and update `tab.selectedFrame`.
- `onElapsedChanged`: Record the latest elapsed seconds in a local remember state for use when paused.

### 4. Bilingual Support
Support `AppLanguage.KO` and `AppLanguage.EN` via `I18n` or local `label(ko, en)` helper.

## Verification
- Unit tests: verify frame index lookup by PTS on pause, verify double-click state triggers.
- `./gradlew :app:compileKotlin` and `./gradlew :app:test`.
- Manual verification:
  - Verify filmstrip subheader displays properly in Korean and English.
  - Single click on GOP bar and filmstrip thumbnail seeks the live player to the exact frame.
  - Double click on filmstrip thumbnail opens the inspection popup.
  - Clicking `[ ↗ 정밀 검사 팝업 ]` chip opens the inspection popup for the selected frame.
  - Pausing the live player selects the corresponding frame in the GOP timeline without lagging during playback.
