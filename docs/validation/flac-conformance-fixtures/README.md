# FLAC Conformance Test Fixtures

Provenance and byte structure for synthetic FLAC (`fLaC`) MP4 test vectors.

## Generator

- Script: `generate.py`
- Generates reproducible, minimal ISO-BMFF files with a `fLaC` audio sample entry, `dfLa` (`FLACSpecificBox`), and raw FLAC frames.

## Fixtures

| File | Key Property | Intended Conformance Verdict |
|---|---|---|
| `default-flac.mp4` | 8 samples, mono FLAC 44100Hz 16-bit (`dfLa` FullBox v0, flags=0, STREAMINFO=34 bytes, frame header CRC-8 and frame CRC-16 valid) | PASS across all implemented rules |
| `bad-dfla-version.mp4` | `dfLa` FullBox version = 1 (forbidden in ISO-BMFF; must be 0) | FAIL on `flac.config.dfla` |
| `bad-streaminfo-size.mp4` | `STREAMINFO` declared size = 32 bytes (must be exactly 34 bytes) | FAIL on `flac.config.dfla` |
| `bad-config-match.mp4` | AudioSampleEntry header `channelcount` = 2, while `STREAMINFO` declares 1 | FAIL on `flac.config.match` |
| `bad-frame-sync.mp4` | Sample 0 frame syncword corrupted (`0xFF 0x78` instead of `0xFF 0xF8`) | FAIL on `flac.frame.header` |
| `bad-frame-crc8.mp4` | Sample 0 frame header CRC-8 corrupted | FAIL on `flac.frame.header` |
| `bad-frame-crc16.mp4` | Sample 0 frame CRC-16 corrupted | FAIL on `flac.frame.crc` |
| `truncated-sample.mp4` | File truncated by 20 bytes so last sample extends beyond EOF | Incomplete coverage, FAIL on `flac.sample.framing` |
| `many-sync-failures.mp4` | 101 samples with corrupted syncword | Tests 100-occurrence display cap and 101 total count |

## Byte Offsets in `default-flac.mp4`

- `ftyp`: 0..24 (24 bytes, `mp42`)
- `moov`: 24..651 (contains `mvhd`, `trak` with `fLaC` and `dfLa`)
  - `fLaC` entry: 413..499 (contains 28-byte AudioSampleEntry fixed header + `dfLa`)
  - `dfLa` box: 449..499 (50 bytes: FullBox v0, flags=0, 4-byte block header, 34-byte STREAMINFO)
- `mdat`: 651..915 (header 8 bytes, 8 samples x 32 bytes = 256 bytes payload)
- First sample offset: 659..690 (32 bytes, starts with `0xFFF84908009E...` with valid CRC-8 `0x9E` and CRC-16 `0xC00A`)
