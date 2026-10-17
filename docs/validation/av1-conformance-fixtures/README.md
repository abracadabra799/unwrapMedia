# AV1 Content Check fixtures

Generated with `generate.sh` using FFmpeg 8.1.2 and SVT-AV1 Encoder Lib v4.2.0 on macOS. The source is FFmpeg `testsrc2`, 64×64, 4 fps, 8 frames. `default-av01.mp4` uses FFmpeg's standard AV1 in MP4 with an `av01` sample entry and `av1C` configuration box.

Independent checks:
- `ffprobe -v error -select_streams v:0 -show_entries stream=codec_tag_string,codec_name -of compact default-av01.mp4` reports `av01` and `av1`.
- `ffprobe -v error -select_streams v:0 -show_entries packet=pos,size -of csv=p=0 default-av01.mp4` reports the first packet as `958,48` (size, position).

Raw bytes, which tests assert, are:

| Field | Absolute byte offset | Raw bytes / value |
| --- | ---: | --- |
| Sample entry tag | 20–23 | ASCII `av01` |
| First sample offset & size | 48 | offset 48, size 958 bytes |
| First sample OBU 0 header | 48 | `0a` (type 1 = SEQUENCE_HEADER, forbidden=0, ext=0, has_size=1, res=0) |
| First sample OBU 0 size (leb128) | 49 | `0b` = 11 bytes payload |
| First sample OBU 0 payload | 50–60 | `02 00 00 05 55 7f fc 6a f9 80 40` (11 bytes) |
| First sample OBU 1 header | 61 | `32` (type 6 = FRAME, forbidden=0, ext=0, has_size=1, res=0) |
| First sample OBU 1 size (leb128) | 62–63 | `ae 07` = 942 bytes payload |
| First sample OBU 1 payload | 64–1005 | 942 bytes (total sample 0 = 13 + 945 = 958 bytes) |
| `av1C` box header | 4306–4313 | Type at 4310; payload at 4314 |
| `av1C` marker & version | 4314 | `81` (marker=1, version=1) |
| `av1C` profile & level | 4315 | `00` (seq_profile=0, seq_level_idx_0=0) |

`mutate.py` changes one field per negative fixture:
- `forbidden-bit.mp4`: byte 48 bit 7 set to 1 (`0x8a`).
- `reserved-bit.mp4`: byte 48 bit 0 set to 1 (`0x0b`).
- `truncated-obu.mp4`: byte 62–63 leb128 obu_size set to `0xff 0x7f` (16383 bytes), exceeding sample boundary.
- `malformed-av1c.mp4`: `av1C` marker/version byte at 4314 set to 0.
- `extension-reserved-bit.mp4`: OBU 0 modified to have extension header (`0x0e 0x07 0x0a`), where extension header reserved bits are 7 (`!= 0`).
- `many-forbidden-headers.mp4`: 101 malformed OBUs in sample 0 (each 3 bytes: `0x8a 0x01 0x00`), followed by a valid OBU_PADDING (655 bytes), preserving exact 958-byte sample size.

All byte offsets and field values were directly verified against file bytes.
