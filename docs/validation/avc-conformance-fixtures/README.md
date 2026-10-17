# AVC (H.264) Content Check fixtures

Generated with `generate.sh` using FFmpeg 8.1.2 and libx264 on macOS. The source is FFmpeg `testsrc2`, 64×64, 4 fps, 8 frames. `default-avc1.mp4` uses FFmpeg's standard H.264 in MP4 with an `avc1` sample entry and `avcC` configuration box.

Independent checks: `ffprobe -v error -select_streams v:0 -show_entries stream=codec_tag_string,codec_name -of compact default-avc1.mp4` reports `avc1` and `h264`. `ffprobe -v error -select_streams v:0 -show_entries packet=pos,size -of csv=p=0 default-avc1.mp4` reports the first packet as `2174,48` (size, position). Raw bytes, which tests assert, are:

| Field | Absolute byte offset | Raw bytes / value |
| --- | ---: | --- |
| First sample length prefix | 48–51 | `00 00 02 ad` = 685 bytes (NAL 1) |
| First NAL header | 52 | `06`; NAL type 6 (SEI), forbidden bit 0, nal_ref_idc = 0 |
| Second NAL length prefix | 737–740 | `00 00 05 c9` = 1481 bytes (NAL 2) |
| Second NAL header | 741 | `65`; NAL type 5 (IDR), forbidden bit 0, nal_ref_idc = 3 |
| Sample entry tag | 24–27 | ASCII `avc1` |
| `avcC` box header | 6987–6994 | Type at 6991; payload at 6995 |
| `avcC` configurationVersion | 6995 | `01` |
| `avcC` length size | 6999 | `ff` -> low 2 bits + 1 = 4 |

`mutate.py` changes one field per negative fixture:
- `forbidden-bit.mp4`: byte 52 bit 7 set to 1 (`0x86`).
- `idr-zero-ref.mp4`: byte 741 bits 6..5 cleared to 0 (`0x05`, IDR slice with `nal_ref_idc == 0`).
- `oversize-nal.mp4`: byte 48 length prefix set to `0x7fffffff`.
- `zero-length-nal.mp4`: byte 48 length prefix set to 0.
- `malformed-avcc.mp4`: `configurationVersion` at 6995 set to 0.
- `truncated-prefix.mp4`: `stsz` first entry size set to 2 bytes, leaving an incomplete 4-byte prefix.
- `many-forbidden-headers.mp4`: 101 malformed NALs in sample 1 (each 4-byte length + 1-byte `0x86` header) followed by a filler NAL, maintaining the 2174-byte sample size.

All byte offsets and field values were directly verified against file bytes.
