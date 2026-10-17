# HEVC Content Check fixtures

Generated with `generate.sh` using FFmpeg 8.1.2 and libx265 4.2+1-e444744 on macOS. The source is FFmpeg `testsrc2`, 64×64, 4 fps, 8 frames. `default-hev1.mp4` uses FFmpeg's default MP4 tag; `explicit-hvc1.mp4` remuxes the same coded samples with `-tag:v hvc1`.

Independent checks: `ffprobe -v error -select_streams v:0 -show_entries stream=codec_tag_string,codec_name -of compact <file>` reports `hev1` and `hvc1` respectively. `ffprobe -v error -select_streams v:0 -show_entries packet=pos,size -of csv=p=0 default-hev1.mp4` reports the first packet as `1275,44` (size, position). Raw bytes, which tests assert, are:

| Field | Absolute byte offset | Raw bytes / value |
| --- | ---: | --- |
| First sample length prefix | 44–47 | `00 00 04 f7` = 1271 bytes |
| First NAL header | 48–49 | `28 01`; NAL type 20, forbidden bit 0, temporal ID plus 1 = 1 |
| Sample entry tag | 4692–4695 | ASCII `hev1` or `hvc1` |
| `hvcC` box header | 4774–4781 | Type at 4778; payload at 4782 |
| `hvcC` length size | 4803 | low 2 bits + 1 = 4 |

`mutate.py` changes one field per negative fixture: first NAL forbidden bit, temporal ID, length prefix (oversize or zero), configuration version, or first `stsz` sample size (2 bytes, leaving an incomplete 4-byte prefix). These files are intentionally damaged and are never used to infer a global conformance PASS. The script asserts source bytes before mutation so a changed encoder output fails instead of silently creating mislabeled fixtures.

The current ITU page lists H.265 (01/26) as in force: https://www.itu.int/rec/T-REC-H.265 . Its PDF download timed out during implementation, so these rules are labeled structural observations until the exact normative clauses and applicable sample-entry wording can be independently verified. `ffprobe` gives orientation only; all offsets and header values above came from direct file-byte reads.
