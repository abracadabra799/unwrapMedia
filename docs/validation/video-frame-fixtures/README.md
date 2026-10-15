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
| hdr10.mp4 | libx265 10-bit (`yuv420p10le`), BT.2020 / PQ: `color_transfer=smpte2084` (verified with ffprobe), start 0 | 0.000000 |

Measured (see the design spec): plain `-ss PTS -i` picks a different frame for offset.ts and the player gets 0
frames there; `-copyts` + early `-ss` + `select=gte(t,PTS)` + showinfo PTS check matches on all fixtures.

`default.mp4` and `hevc.mp4` use ffmpeg's default settings/tags on purpose (hard-won rule 2: a fixture set
that only used non-default tags hid a feature that was unavailable on default files).

## Which containers need the exact path (measured 2026-10-16)

Plain `ffmpeg -hwaccel auto -ss <seconds from start> -i …` (the original FrameFullSizeDecoder / player seek) compared
against the first frame at/after `start_time + seconds` (decoded from the file start with `-copyts` + `select`),
640×360 testsrc2, 7 sample points each:

| container (`format_name`) | start_time | plain `-ss` |
|---|---|---|
| `mov,mp4,…` x264, no edit list | 0.067 | 7/7 |
| `mov,mp4,…` x265, no edit list | 0.067 | 7/7 |
| `mov,mp4,…` `-output_ts_offset 5` (also `.mov`) | 5.0 | 7/7 |
| `matroska,webm` (MKV x264, WebM VP9) offset 5 | 5.0 | 7/7 |
| `mpeg` (MPEG-PS, mpeg2video) offset 5 | 5.0 | 7/7 |
| **`mpegts`**, ffmpeg default settings | **1.467** | **3/7** (wrong frame / none) |

So only MPEG-TS (no seek index) takes the exact path (`FrameStreamInfo.needsExactSeek`, `VideoInfo.exactSeek`);
every other container keeps the fast hardware-accelerated path. Note that ffmpeg's *default* TS output already
starts at 1.47 s, so practically every TS file is affected. `offset5.mp4` pins the indexed-with-offset case.
Regenerating with `generate.sh` rewrites `vfr.mkv` with a new Matroska segment UID; keep the committed copy.
