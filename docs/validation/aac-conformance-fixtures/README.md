# AAC Conformance Test Fixtures

Provenance and byte structure for synthetic AAC (`mp4a`) MP4/M4A test vectors.

## Generator

- Script: `generate.py`
- Generates reproducible, minimal ISO-BMFF files with an `mp4a` audio sample entry, `esds` elementary stream descriptor, and raw AAC access units.

## Fixtures

| File | Key Property | Intended Conformance Verdict |
|---|---|---|
| `default-mp4a.m4a` | 8 samples, stereo AAC LC 44100Hz (`AudioSpecificConfig = 0x12, 0x10`), valid `esds` | PASS across all implemented rules |
| `bad-adts-leaked.m4a` | Sample 0 starts with 12-bit `0xFFF` ADTS syncword | FAIL on `aac.frame.adts_guard` |
| `bad-asc-sampling.m4a` | `samplingFrequencyIndex = 13` (0x0D, reserved value) | FAIL on `aac.config.asc` |
| `bad-ics-reserved.m4a` | Sample 0 has `ics_reserved_bit = 1` | FAIL on `aac.frame.ics_reserved` |
| `malformed-esds.m4a` | Truncated `esds` descriptor payload | UNSUPPORTED / NOT_CHECKED on `aac.config.esds` |
| `truncated-sample.m4a` | File truncated by 20 bytes so last sample extends beyond EOF | Incomplete coverage, FAIL on `aac.sample.framing` |
| `many-adts-failures.m4a` | 101 samples with ADTS leak | Tests 100-occurrence display cap and 101 total count |

## Byte Offsets in `default-mp4a.m4a`

- `ftyp`: 0..31 (32 bytes, `M4A `)
- `moov`: 32..788 (contains `mvhd`, `trak` with `mp4a` and `esds`)
  - `mp4a` entry: contains 28-byte AudioSampleEntry fixed header + `esds`
  - `esds` box: contains `ES_Descriptor` (tag 0x03), `DecoderConfigDescriptor` (tag 0x04, streamType=5, objectTypeIndication=0x40), `DecoderSpecificInfo` (tag 0x05, ASC=`0x12 0x10`)
- `mdat`: 788..1052 (header 8 bytes, 8 samples x 32 bytes = 256 bytes payload)
- First sample offset: 796..827 (32 bytes, starts with `0x21 0x0A`)
