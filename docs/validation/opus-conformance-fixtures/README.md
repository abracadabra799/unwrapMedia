# Opus Conformance Test Fixtures

Provenance and byte structure for synthetic Opus (`Opus`) MP4 test vectors.

## Generator

- Script: `generate.py`
- Generates reproducible, minimal ISO-BMFF files with an `Opus` audio sample entry, `dOps` (`OpusSpecificBox`), and Opus access units.

## Fixtures

| File | Key Property | Intended Conformance Verdict |
|---|---|---|
| `default-opus.mp4` | 8 samples, mono Opus 48000Hz (`dOps` version=0, channels=1, pre_skip=312, rate=48000, gain=0, family=0) | PASS across all implemented rules |
| `bad-dops-version.mp4` | `dOps` version = 1 (forbidden in ISO-BMFF; must be 0) | FAIL on `opus.config.dops` |
| `bad-channel-count.mp4` | `dOps` OutputChannelCount = 0 | FAIL on `opus.config.dops` |
| `bad-config-match.mp4` | AudioSampleEntry header `channelcount` = 2, while `dOps` declares 1 | FAIL on `opus.config.match` |
| `bad-mono-stereo-flag.mp4` | Sample 0 TOC byte has stereo flag `s = 1` in mono stream | FAIL on `opus.packet.toc` |
| `bad-code3-zero-frames.mp4` | Sample 0 is Code 3 packet with `M = 0` (forbidden by RFC 6716 §3.2.5) | FAIL on `opus.packet.framing` |
| `truncated-sample.mp4` | File truncated by 20 bytes so last sample extends beyond EOF | Incomplete coverage, FAIL on `opus.sample.framing` |
| `many-toc-failures.mp4` | 101 samples with stereo flag in mono track | Tests 100-occurrence display cap and 101 total count |

## Byte Offsets in `default-opus.mp4`

- `ftyp`: 0..24 (24 bytes, `mp42`)
- `moov`: 24..620 (contains `mvhd`, `trak` with `Opus` and `dOps`)
  - `Opus` entry: 413..468 (contains 28-byte AudioSampleEntry fixed header + `dOps`)
  - `dOps` box: 449..468 (19 bytes: version=0, channel_count=1, pre_skip=312, input_sample_rate=48000, output_gain=0, channel_mapping_family=0)
- `mdat`: 620..884 (header 8 bytes, 8 samples x 32 bytes = 256 bytes payload)
- First sample offset: 628..659 (32 bytes, starts with `0xF8`: config=31 CELT FB 20ms, mono, 1 frame)
