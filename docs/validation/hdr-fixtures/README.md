# HDR / SEI test fixtures

`make_fixtures.sh` regenerates `app/src/test/resources/hdr/*.mp4` (ffmpeg 8.1.2 with libx265 4.2,
libx264 and libsvtav1 were used originally). Homebrew x265 is built without HDR10+ support, so
`inject_hdr10plus.py` writes the ST 2094-40 SEI itself; its layout was verified against
`ffprobe -show_frames`. Note that ffprobe repeats the previous frame's HDR10+ values on frames
that carry none, so it cannot be used to check for missing HDR10+.

| File | Content |
|---|---|
| hevc_hdr10plus.mp4 | 30 frames, keyint 10, no B-frames; in-band MDCV/CLL on sync samples; HDR10+ on every frame except 12-14; MaxSCL jump at frame 17; no mdcv/clli boxes |
| hevc_hev1.mp4 | Same stream as hevc_hdr10plus.mp4 remuxed with ffmpeg's default `hev1` sample entry |
| hevc_box.mp4 | 30 frames with B-frames (ctts); `mdcv`/`clli` boxes in the `hvc1` sample entry |
| h264_hdr.mp4 | 10 frames, keyint 5; MDCV/CLL SEI on IDR samples |
| av1_hdr.mp4 | 10 frames, keyint 5; HDR_MDCV/HDR_CLL metadata OBUs on keyframes |
