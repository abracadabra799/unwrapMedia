# Video and audio timeline — Design

## Goal

Let a video tab show video frame presentation times and the first audio track's waveform and packet presentation times on one horizontal time axis. Selecting a video frame or audio packet exposes their PTS values and the numeric gap between them. This gap is a timestamp comparison, not an A/V sync verdict.

## Interaction

Add a collapsed `A/V 타임라인 / A/V Timeline` strip under the existing video player, GOP chart and filmstrip. Expanding it requests frame analysis if needed, then reads the first audio track on background workers. A common axis shows video frame ticks, L/R waveform peaks, audio packet ticks, selection, and the currently displayed video frame. Clicking a video tick selects/seeks that frame; clicking an audio tick selects that packet. Wheel zooms around the pointer; dragging pans. A status line states when audio is absent, loading failed, timed out, or packet indexing stopped at its safety limit. Collapsing or closing cancels work and terminates its processes.

## Data and limits

Reuse `probeAudioFormat` and `computeWaveformPeaks` for decoded PCM peaks. Extend audio metadata with the first audio stream's edit-list-aware `start_time`; peaks occupy `[start_time, start_time + duration]`. Read `ffprobe -select_streams a:0 -show_entries packet=pts_time,duration_time,size,pos` in compact key-value format. A real `avc-aac.mp4` fixture shows the first AAC packet at `-0.021333` while audio stream `start_time` is `0.000000`; keep both facts rather than moving the waveform back to the priming packet. Cap retained packets at 200,000 and mark the result partial. Enforce a 30-second process deadline and cancellation. Never report partial data as complete. No new dependencies.

## Scope

The shared timeline is available on video tabs with ffprobe/ffmpeg playback. Motion photo and AV2 views stay on their current presentation path. The first audio stream is shown; multi-track selection is a later feature. The existing GOP chart and filmstrip keep their own zoom and scroll controls.
