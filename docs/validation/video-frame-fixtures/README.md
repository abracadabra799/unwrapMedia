# video-frame fixtures

Purpose: verify that a selected frame is decoded exactly by its ABSOLUTE stream PTS (ffprobe `frame=pts_time`).
Regenerate with `sh docs/validation/video-frame-fixtures/generate.sh` (writes `app/src/test/resources/video-frame/`).
All sources are `testsrc2` 160x90 @30fps, 2 s (60 frames), so every frame is visually distinct.

| File | Characteristic | Expected format start_time |
|---|---|---|
| default.mp4 | libx264 B-frames, default settings, start 0 | 0.000000 |
| offset.ts | MPEG-TS with `-output_ts_offset 10` (PTS starts at 11.4: 10 s + 1.4 s TS mux delay) | 11.400000 (measured) |
| hevc.mp4 | libx265 with 3 B-frames (ffmpeg default MP4 tag `hev1`) | 0.000000 |
| vfr.mkv | variable frame rate: a 0.2 s gap after frame 20 | 0.000000 |

Measured (see the design spec): plain `-ss PTS -i` picks a different frame for offset.ts and the player gets 0
frames there; `-copyts` + early `-ss` + `select=gte(t,PTS)` + showinfo PTS check matches on all fixtures.

`default.mp4` and `hevc.mp4` use ffmpeg's default settings/tags on purpose (hard-won rule 2: a fixture set
that only used non-default tags hid a feature that was unavailable on default files).
