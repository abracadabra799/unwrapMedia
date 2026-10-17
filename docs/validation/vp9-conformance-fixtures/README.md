# VP9 Content Check fixtures

Generated with `generate.sh` using FFmpeg 8.1.2 and libvpx-vp9 on macOS. The source is FFmpeg `testsrc2`, 64×64, 4 fps, 8 frames. `default-vp09.mp4` uses FFmpeg's standard VP9 in MP4 with a `vp09` sample entry and `vpcC` configuration box.

Independent checks:
- `ffprobe -v error -select_streams v:0 -show_entries stream=codec_tag_string,codec_name -of compact default-vp09.mp4` reports `vp09` and `vp9`.
- `ffprobe -v error -select_streams v:0 -show_entries packet=pos,size -of csv=p=0 default-vp09.mp4` reports the first packet as `1692,44` (size, position).

Raw bytes, which tests assert, are:

| Field | Absolute byte offset | Raw bytes / value |
| --- | ---: | --- |
| Sample entry tag | 5020–5023 | ASCII `vp09` |
| First sample offset & size | 44 | offset 44, size 1692 bytes |
| First sample frame header byte 0 | 44 | `82` (marker=2, profile=0, show_existing=0, frame_type=0 (KEY_FRAME), show_frame=1, error_resilient=0) |
| First sample sync code | 45–47 | `49 83 42` = `0x498342` |
| `vpcC` box header | 5102–5109 | Size at 5102 (20 bytes), type at 5106 (`vpcC`) |
| `vpcC` FullBox version & flags | 5110–5113 | `01 00 00 00` (version 1, flags 0) |
| `vpcC` profile & level | 5114–5115 | `00 0a` (profile 0, level 1.0) |
| `vpcC` bitDepth, subsampling, range | 5116 | `82` (bitDepth=8, subsampling=1, range=0) |

`mutate.py` changes one field per negative fixture:
- `bad-marker.mp4`: byte 44 bits 7..6 cleared (`0x02`, `frame_marker == 0 != 2`).
- `bad-sync-code.mp4`: byte 45 set to 0 (`0x008342 != 0x498342`).
- `profile3-reserved-bit.mp4`: byte 44 set to `0xB9` (profile=3, `reserved_zero == 1`).
- `malformed-vpcc.mp4`: `vpcC` FullBox version at byte 5110 set to `0xFF`.
- `truncated-frame.mp4`: `stsz` sample 0 size set to 2 bytes, leaving an incomplete keyframe header.
- `many-marker-failures.mp4`: 101 samples generated via FFmpeg with each sample's `frame_marker` cleared to 0, for occurrence counting.

All byte offsets and field values were directly verified against file bytes.
