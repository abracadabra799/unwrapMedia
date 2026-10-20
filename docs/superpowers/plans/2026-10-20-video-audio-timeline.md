# Video and Audio Timeline Implementation Plan

**Goal:** Display video frames and first-track audio waveform/packets on a shared PTS axis.

**Architecture:** A bounded packet probe and existing waveform decoder feed a Compose timeline panel. The video tab owns expansion and frame selection; panel work is canceled with composition.

**Tech Stack:** Kotlin, Compose Desktop, ffprobe, ffmpeg, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-20-video-audio-timeline-design.md`

## Tasks

- [x] Add failing tests for real AAC packet parsing, retained packet cap, axis alignment, and nearest packet selection.
- [x] Implement bounded packet probe and audio start metadata; run focused tests.
- [x] Implement shared-axis timeline rendering, selection and zoom/pan; integrate in video inspector.
- [x] Run full tests, review, commit, fast-forward main. Push only with a task-specific authorization.
